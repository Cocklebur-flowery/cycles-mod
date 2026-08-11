#include "cycles_engine.h"

#include <Windows.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstddef>
#include <cstring>
#include <filesystem>
#include <limits>
#include <memory>
#include <mutex>
#include <optional>
#include <sstream>
#include <stdexcept>
#include <thread>
#include <tuple>
#include <unordered_map>
#include <utility>
#include <vector>

#include "device/device.h"
#include "scene/attribute.h"
#include "scene/camera.h"
#include "scene/film.h"
#include "scene/image.h"
#include "scene/image_loader.h"
#include "scene/integrator.h"
#include "scene/mesh.h"
#include "scene/object.h"
#include "scene/pass.h"
#include "scene/scene.h"
#include "scene/shader.h"
#include "scene/background.h"
#include "scene/shader_graph.h"
#include "scene/shader_nodes.h"
#include "session/buffers.h"
#include "session/output_driver.h"
#include "session/session.h"
#include "util/log.h"
#include "util/image_metadata.h"
#include "util/path.h"
#include "util/string.h"
#include "util/system.h"
#include "util/transform.h"
#include "util/types.h"
#include "util/unique_ptr.h"

namespace {

using namespace std::chrono_literals;

constexpr std::uint32_t kMaximumRenderWidth = 3840;
constexpr std::uint32_t kMaximumRenderHeight = 2160;

CyclesBridgeRenderSettings default_settings() {
    CyclesBridgeRenderSettings settings{};
    settings.struct_size = sizeof(settings);
    settings.struct_version = 1;
    settings.revision = 0;
    settings.device_policy = 0;
    settings.resolution_mode = 0;
    settings.render_width = 480;
    settings.render_height = 270;
    settings.resolution_percentage = 100;
    settings.interactive_samples = 1;
    settings.still_samples = 8;
    settings.stationary_delay_millis = 150;
    settings.noise_threshold = 0.01F;
    settings.maximum_bounce = 3;
    settings.diffuse_bounces = 2;
    settings.glossy_bounces = 1;
    settings.clamp_indirect = 10.0F;
    settings.pixel_filter = 0;
    settings.filter_width = 1.0F;
    settings.denoiser_start_sample = 1;
    settings.denoiser_input = 2;
    settings.denoiser_prefilter = 1;
    settings.denoiser_quality = 1;
    settings.denoiser_use_gpu = 1;
    settings.gamma = 1.0F;
    settings.active_pass = CYCLES_BRIDGE_PASS_COMBINED;
    return settings;
}

bool same_render_settings(
    CyclesBridgeRenderSettings first,
    CyclesBridgeRenderSettings second) {
    first.revision = 0;
    second.revision = 0;
    first.debug_overlay = 0;
    second.debug_overlay = 0;
    std::fill(std::begin(first.reserved), std::end(first.reserved), 0U);
    std::fill(std::begin(second.reserved), std::end(second.reserved), 0U);
    return std::memcmp(&first, &second, sizeof(first)) == 0;
}

const char* pass_name(std::uint32_t pass) {
    switch (pass) {
        case CYCLES_BRIDGE_PASS_DEPTH: return "depth";
        case CYCLES_BRIDGE_PASS_NORMAL: return "normal";
        case CYCLES_BRIDGE_PASS_DIFFUSE_COLOR: return "diffuse_color";
        case CYCLES_BRIDGE_PASS_EMISSION: return "emission";
        case CYCLES_BRIDGE_PASS_ROUGHNESS: return "roughness";
        case CYCLES_BRIDGE_PASS_SAMPLE_COUNT: return "sample_count";
        default: return "combined";
    }
}

ccl::PassType pass_type(std::uint32_t pass) {
    switch (pass) {
        case CYCLES_BRIDGE_PASS_DEPTH: return ccl::PASS_DEPTH;
        case CYCLES_BRIDGE_PASS_NORMAL: return ccl::PASS_NORMAL;
        case CYCLES_BRIDGE_PASS_DIFFUSE_COLOR: return ccl::PASS_DIFFUSE_COLOR;
        case CYCLES_BRIDGE_PASS_EMISSION: return ccl::PASS_EMISSION;
        case CYCLES_BRIDGE_PASS_ROUGHNESS: return ccl::PASS_ROUGHNESS;
        case CYCLES_BRIDGE_PASS_SAMPLE_COUNT: return ccl::PASS_SAMPLE_COUNT;
        default: return ccl::PASS_COMBINED;
    }
}

int pass_components(std::uint32_t pass) {
    switch (pass) {
        case CYCLES_BRIDGE_PASS_DEPTH:
        case CYCLES_BRIDGE_PASS_ROUGHNESS:
        case CYCLES_BRIDGE_PASS_SAMPLE_COUNT:
            return 1;
        case CYCLES_BRIDGE_PASS_NORMAL:
        case CYCLES_BRIDGE_PASS_DIFFUSE_COLOR:
        case CYCLES_BRIDGE_PASS_EMISSION:
            return 3;
        default:
            return 4;
    }
}

struct SectionRequest {
    CyclesBridgeSection section{};
    std::vector<CyclesBridgeVertex> vertices;
    std::vector<CyclesBridgeTriangle> triangles;
};

struct SceneResourcesData {
    CyclesBridgeSceneResources resources{};
    std::vector<CyclesBridgeMaterial> materials;
    std::vector<CyclesBridgeTexture> textures;
    std::vector<std::uint8_t> texture_pixels;
};

struct SceneRequest {
    std::shared_ptr<const SceneResourcesData> resources;
    std::unordered_map<std::int64_t, std::shared_ptr<const SectionRequest>> sections;
    std::uint64_t revision = 0;
};

struct SectionSceneNodes {
    ccl::Mesh* mesh = nullptr;
    ccl::Object* object = nullptr;
    std::shared_ptr<const SectionRequest> source;
};

struct SceneRuntime {
    std::shared_ptr<const SceneResourcesData> resources;
    std::vector<ccl::Shader*> shaders;
    std::unordered_map<std::int64_t, SectionSceneNodes> sections;

    void clear() {
        resources.reset();
        shaders.clear();
        sections.clear();
    }
};

struct CameraRequest {
    CyclesBridgeCamera camera{};
    std::uint32_t render_width = 0;
    std::uint32_t render_height = 0;
    int sample_count = 1;
    std::uint32_t sampling_state = CYCLES_BRIDGE_SAMPLING_INTERACTIVE;
    std::uint64_t revision = 0;
};

std::string wide_to_utf8(const std::wstring& value) {
    if (value.empty()) {
        return {};
    }
    const int size = WideCharToMultiByte(
        CP_UTF8, 0, value.data(), static_cast<int>(value.size()), nullptr, 0, nullptr, nullptr);
    if (size <= 0) {
        throw std::runtime_error("failed to convert the native module path to UTF-8");
    }
    std::string result(static_cast<std::size_t>(size), '\0');
    WideCharToMultiByte(
        CP_UTF8,
        0,
        value.data(),
        static_cast<int>(value.size()),
        result.data(),
        size,
        nullptr,
        nullptr);
    return result;
}

void module_anchor() {}

std::string native_module_directory() {
    HMODULE module = nullptr;
    if (!GetModuleHandleExW(
            GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
            reinterpret_cast<LPCWSTR>(&module_anchor),
            &module)) {
        throw std::runtime_error("failed to locate cyclesrenderer_native.dll");
    }

    std::wstring path(32768, L'\0');
    const DWORD length = GetModuleFileNameW(module, path.data(), static_cast<DWORD>(path.size()));
    if (length == 0 || length >= path.size()) {
        throw std::runtime_error("failed to read the native module path");
    }
    path.resize(length);
    return wide_to_utf8(std::filesystem::path(path).parent_path().wstring());
}

void initialize_cycles_runtime() {
    static std::once_flag once;
    std::call_once(once, [] {
        ccl::log_init(nullptr);
        ccl::path_init(native_module_directory());
        ccl::system_max_open_files_ensure();
    });
}

std::string device_type_name(ccl::DeviceType type) {
    return ccl::Device::string_from_type(type);
}

std::vector<ccl::DeviceInfo> enumerate_devices() {
    std::vector<ccl::DeviceInfo> result;
    const std::array<unsigned int, 3> masks = {
        ccl::DEVICE_MASK_OPTIX,
        ccl::DEVICE_MASK_CUDA,
        ccl::DEVICE_MASK_CPU,
    };
    for (const unsigned int mask : masks) {
        for (const ccl::DeviceInfo& device : ccl::Device::available_devices(mask)) {
            const bool duplicate = std::any_of(
                result.begin(), result.end(), [&device](const ccl::DeviceInfo& existing) {
                    return existing.type == device.type && existing.id == device.id;
                });
            if (!duplicate) {
                result.push_back(device);
            }
        }
    }
    return result;
}

std::uint32_t device_mask(const ccl::DeviceInfo& device) {
    switch (device.type) {
        case ccl::DEVICE_OPTIX: return CYCLES_BRIDGE_DEVICE_OPTIX;
        case ccl::DEVICE_CUDA: return CYCLES_BRIDGE_DEVICE_CUDA;
        case ccl::DEVICE_CPU: return CYCLES_BRIDGE_DEVICE_CPU;
        default: return 0;
    }
}

bool device_matches_policy(const ccl::DeviceInfo& device, std::uint32_t policy) {
    return policy == 0U
        || (policy == 1U && device.type == ccl::DEVICE_OPTIX)
        || (policy == 2U && device.type == ccl::DEVICE_CUDA)
        || (policy == 3U && device.type == ccl::DEVICE_CPU);
}

std::uint32_t device_diagnostic_id(const ccl::DeviceInfo& device) {
    switch (device.type) {
        case ccl::DEVICE_OPTIX: return 1U;
        case ccl::DEVICE_CUDA: return 2U;
        case ccl::DEVICE_CPU: return 3U;
        default: return 0U;
    }
}

bool finite_camera(const CyclesBridgeCamera& camera) {
    return std::isfinite(camera.position_x)
        && std::isfinite(camera.position_y)
        && std::isfinite(camera.position_z)
        && std::isfinite(camera.rotation_x)
        && std::isfinite(camera.rotation_y)
        && std::isfinite(camera.rotation_z)
        && std::isfinite(camera.rotation_w)
        && std::isfinite(camera.vertical_fov_radians)
        && std::isfinite(camera.depth_far);
}

std::pair<std::uint32_t, std::uint32_t> render_dimensions(
    std::uint32_t viewport_width,
    std::uint32_t viewport_height,
    const CyclesBridgeRenderSettings& settings) {
    const double percentage = static_cast<double>(settings.resolution_percentage) / 100.0;
    const std::uint32_t requested_width = std::clamp(
        static_cast<std::uint32_t>(std::floor(settings.render_width * percentage)),
        1U,
        kMaximumRenderWidth);
    const std::uint32_t requested_height = std::clamp(
        static_cast<std::uint32_t>(std::floor(settings.render_height * percentage)),
        1U,
        kMaximumRenderHeight);
    if (settings.resolution_mode == 1U) {
        return {requested_width, requested_height};
    }
    const double scale = std::min({
        1.0,
        static_cast<double>(requested_width) / viewport_width,
        static_cast<double>(requested_height) / viewport_height,
    });
    return {
        std::max(1U, static_cast<std::uint32_t>(std::floor(viewport_width * scale))),
        std::max(1U, static_cast<std::uint32_t>(std::floor(viewport_height * scale))),
    };
}

bool nearly_equal(double first, double second, double tolerance) {
    return std::abs(first - second) <= tolerance;
}

bool same_camera(const CameraRequest& current, const CameraRequest& requested) {
    const CyclesBridgeCamera& first = current.camera;
    const CyclesBridgeCamera& second = requested.camera;
    return current.render_width == requested.render_width
        && current.render_height == requested.render_height
        && nearly_equal(first.position_x, second.position_x, 1.0e-5)
        && nearly_equal(first.position_y, second.position_y, 1.0e-5)
        && nearly_equal(first.position_z, second.position_z, 1.0e-5)
        && nearly_equal(first.rotation_x, second.rotation_x, 1.0e-6)
        && nearly_equal(first.rotation_y, second.rotation_y, 1.0e-6)
        && nearly_equal(first.rotation_z, second.rotation_z, 1.0e-6)
        && nearly_equal(first.rotation_w, second.rotation_w, 1.0e-6)
        && nearly_equal(first.vertical_fov_radians, second.vertical_fov_radians, 1.0e-6)
        && nearly_equal(first.depth_far, second.depth_far, 1.0e-3);
}

float linear_to_srgb(float value) {
    if (!std::isfinite(value) || value <= 0.0F) {
        return 0.0F;
    }
    if (value <= 0.0031308F) {
        return value * 12.92F;
    }
    return 1.055F * std::pow(value, 1.0F / 2.4F) - 0.055F;
}

float srgb_to_linear(std::uint32_t value) {
    const float channel = static_cast<float>(value) / 255.0F;
    if (channel <= 0.04045F) {
        return channel / 12.92F;
    }
    return std::pow((channel + 0.055F) / 1.055F, 2.4F);
}

std::uint8_t to_unorm(float value) {
    const float clamped = std::clamp(value, 0.0F, 1.0F);
    return static_cast<std::uint8_t>(std::lround(clamped * 255.0F));
}

std::uint32_t elapsed_micros(
    std::chrono::steady_clock::time_point start,
    std::chrono::steady_clock::time_point end) {
    const auto value = std::chrono::duration_cast<std::chrono::microseconds>(end - start).count();
    return static_cast<std::uint32_t>(std::clamp<std::int64_t>(
        value, 0, std::numeric_limits<std::uint32_t>::max()));
}

std::uint32_t update_ema(std::uint32_t previous, std::uint32_t value) {
    if (previous == 0U) {
        return value;
    }
    return static_cast<std::uint32_t>(
        (static_cast<std::uint64_t>(previous) * 7U + value) / 8U);
}

class FrameStore final {
 public:
    void clear() {
        std::lock_guard lock(mutex_);
        width_ = 0;
        height_ = 0;
        sample_count_ = 0;
        last_frame_completed_ = {};
        rgba_.clear();
        generation_++;
    }

    void configure(
        const CyclesBridgeRenderSettings& settings,
        float depth_far,
        int target_samples) {
        std::lock_guard lock(mutex_);
        active_pass_ = settings.active_pass;
        exposure_scale_ = std::exp2(settings.exposure_ev);
        gamma_ = settings.gamma;
        view_transform_ = settings.view_transform;
        depth_far_ = std::max(1.0F, depth_far);
        target_samples_ = std::max(1, target_samples);
    }

    bool update(const ccl::OutputDriver::Tile& tile) {
        const auto convert_start = std::chrono::steady_clock::now();
        if (tile.size.x <= 0 || tile.size.y <= 0
            || tile.full_size.x <= 0 || tile.full_size.y <= 0) {
            return false;
        }

        std::uint32_t active_pass;
        float exposure_scale;
        float gamma;
        std::uint32_t view_transform;
        float depth_far;
        int target_samples;
        {
            std::lock_guard lock(mutex_);
            active_pass = active_pass_;
            exposure_scale = exposure_scale_;
            gamma = gamma_;
            view_transform = view_transform_;
            depth_far = depth_far_;
            target_samples = target_samples_;
        }
        const int components = pass_components(active_pass);
        std::vector<float> pixels(
            static_cast<std::size_t>(tile.size.x) * tile.size.y
                * static_cast<std::size_t>(components));
        if (!tile.get_pass_pixels(pass_name(active_pass), components, pixels.data())) {
            return false;
        }

        std::lock_guard lock(mutex_);
        if (width_ != static_cast<std::uint32_t>(tile.full_size.x)
            || height_ != static_cast<std::uint32_t>(tile.full_size.y)) {
            width_ = static_cast<std::uint32_t>(tile.full_size.x);
            height_ = static_cast<std::uint32_t>(tile.full_size.y);
            rgba_.assign(static_cast<std::size_t>(width_) * height_ * 4U, 0U);
        }

        for (int tile_y = 0; tile_y < tile.size.y; ++tile_y) {
            const int source_y = tile_y;
            const int target_y = tile.offset.y + tile_y;
            if (target_y < 0 || target_y >= static_cast<int>(height_)) {
                continue;
            }
            for (int tile_x = 0; tile_x < tile.size.x; ++tile_x) {
                const int target_x = tile.offset.x + tile_x;
                if (target_x < 0 || target_x >= static_cast<int>(width_)) {
                    continue;
                }
                const std::size_t source =
                    (static_cast<std::size_t>(source_y) * tile.size.x + tile_x)
                        * static_cast<std::size_t>(components);
                const std::size_t target =
                    (static_cast<std::size_t>(target_y) * width_ + target_x) * 4U;
                float red = 0.0F;
                float green = 0.0F;
                float blue = 0.0F;
                float alpha = 1.0F;
                if (active_pass == CYCLES_BRIDGE_PASS_DEPTH) {
                    const float depth = pixels[source];
                    const float value = std::isfinite(depth)
                        ? 1.0F - std::exp(-std::max(0.0F, depth) * 8.0F / depth_far)
                        : 1.0F;
                    red = green = blue = value;
                } else if (active_pass == CYCLES_BRIDGE_PASS_NORMAL) {
                    red = pixels[source] * 0.5F + 0.5F;
                    green = pixels[source + 1U] * 0.5F + 0.5F;
                    blue = pixels[source + 2U] * 0.5F + 0.5F;
                } else if (active_pass == CYCLES_BRIDGE_PASS_ROUGHNESS) {
                    red = green = blue = pixels[source];
                } else if (active_pass == CYCLES_BRIDGE_PASS_SAMPLE_COUNT) {
                    const float value = pixels[source] / static_cast<float>(target_samples);
                    red = green = blue = value;
                } else {
                    red = pixels[source] * exposure_scale;
                    green = pixels[source + 1U] * exposure_scale;
                    blue = pixels[source + 2U] * exposure_scale;
                    if (active_pass == CYCLES_BRIDGE_PASS_COMBINED) {
                        alpha = pixels[source + 3U];
                    }
                    if (view_transform != 1U) {
                        red = linear_to_srgb(red);
                        green = linear_to_srgb(green);
                        blue = linear_to_srgb(blue);
                    }
                    if (!nearly_equal(gamma, 1.0F, 1.0e-6)) {
                        const float inverse_gamma = 1.0F / gamma;
                        red = std::pow(std::max(0.0F, red), inverse_gamma);
                        green = std::pow(std::max(0.0F, green), inverse_gamma);
                        blue = std::pow(std::max(0.0F, blue), inverse_gamma);
                    }
                }
                rgba_[target] = to_unorm(red);
                rgba_[target + 1U] = to_unorm(green);
                rgba_[target + 2U] = to_unorm(blue);
                rgba_[target + 3U] = to_unorm(alpha);
            }
        }
        const auto completed = std::chrono::steady_clock::now();
        last_convert_micros_ = elapsed_micros(convert_start, completed);
        ema_convert_micros_ = update_ema(ema_convert_micros_, last_convert_micros_);
        max_convert_micros_ = std::max(max_convert_micros_, last_convert_micros_);
        produced_frame_count_++;
        last_frame_completed_ = completed;
        generation_++;
        return true;
    }

    void set_sample_count(int sample_count) {
        std::lock_guard lock(mutex_);
        sample_count_ = sample_count;
    }

    bool copy_native(
        std::uint8_t* output,
        std::uint64_t capacity,
        std::uint64_t previous_generation,
        CyclesBridgeFrame& frame,
        std::string& error) {
        std::lock_guard lock(mutex_);
        frame.width = width_;
        frame.height = height_;
        frame.generation = generation_;
        frame.sample_count = static_cast<std::uint32_t>(std::max(0, sample_count_));
        frame.pixel_byte_count = 0;
        frame.flags = 0;
        if (rgba_.empty() || width_ == 0 || height_ == 0) {
            return true;
        }

        frame.flags |= CYCLES_BRIDGE_FRAME_READY;
        if (generation_ == previous_generation) {
            unchanged_poll_count_++;
            return true;
        }
        if (output == nullptr || capacity < rgba_.size()) {
            error = "native frame output buffer is too small";
            return false;
        }
        const auto copy_start = std::chrono::steady_clock::now();
        std::memcpy(output, rgba_.data(), rgba_.size());
        const auto copy_end = std::chrono::steady_clock::now();
        last_copy_micros_ = elapsed_micros(copy_start, copy_end);
        ema_copy_micros_ = update_ema(ema_copy_micros_, last_copy_micros_);
        max_copy_micros_ = std::max(max_copy_micros_, last_copy_micros_);
        copied_frame_count_++;
        copied_byte_count_ += rgba_.size();
        frame.pixel_byte_count = static_cast<std::uint32_t>(rgba_.size());
        frame.flags |= CYCLES_BRIDGE_FRAME_UPDATED;
        return true;
    }

    void copy_scaled(std::uint8_t* output, std::uint32_t width, std::uint32_t height) const {
        std::lock_guard lock(mutex_);
        if (rgba_.empty() || width_ == 0 || height_ == 0) {
            for (std::uint64_t pixel = 0; pixel < static_cast<std::uint64_t>(width) * height;
                 ++pixel) {
                output[pixel * 4U] = 104U;
                output[pixel * 4U + 1U] = 151U;
                output[pixel * 4U + 2U] = 204U;
                output[pixel * 4U + 3U] = 255U;
            }
            return;
        }

        for (std::uint32_t y = 0; y < height; ++y) {
            const std::uint32_t source_y = std::min(
                height_ - 1U,
                static_cast<std::uint32_t>(static_cast<std::uint64_t>(y) * height_ / height));
            for (std::uint32_t x = 0; x < width; ++x) {
                const std::uint32_t source_x = std::min(
                    width_ - 1U,
                    static_cast<std::uint32_t>(static_cast<std::uint64_t>(x) * width_ / width));
                const std::size_t source =
                    (static_cast<std::size_t>(source_y) * width_ + source_x) * 4U;
                const std::size_t target =
                    (static_cast<std::size_t>(y) * width + x) * 4U;
                std::memcpy(output + target, rgba_.data() + source, 4U);
            }
        }
    }

    [[nodiscard]] bool ready() const {
        std::lock_guard lock(mutex_);
        return !rgba_.empty();
    }

    [[nodiscard]] std::uint64_t generation() const {
        std::lock_guard lock(mutex_);
        return generation_;
    }

    [[nodiscard]] std::pair<std::uint32_t, std::uint32_t> size() const {
        std::lock_guard lock(mutex_);
        return {width_, height_};
    }

    [[nodiscard]] int sample_count() const {
        std::lock_guard lock(mutex_);
        return sample_count_;
    }

    void fill_diagnostics(CyclesBridgeDiagnostics& diagnostics) const {
        std::lock_guard lock(mutex_);
        diagnostics.frame_generation = generation_;
        diagnostics.width = width_;
        diagnostics.height = height_;
        diagnostics.sample_count = static_cast<std::uint32_t>(std::max(0, sample_count_));
        diagnostics.frame_ready = rgba_.empty() ? 0U : 1U;
        diagnostics.produced_frame_count = produced_frame_count_;
        diagnostics.copied_frame_count = copied_frame_count_;
        diagnostics.copied_byte_count = copied_byte_count_;
        diagnostics.unchanged_poll_count = unchanged_poll_count_;
        diagnostics.last_convert_micros = last_convert_micros_;
        diagnostics.ema_convert_micros = ema_convert_micros_;
        diagnostics.max_convert_micros = max_convert_micros_;
        diagnostics.last_copy_micros = last_copy_micros_;
        diagnostics.ema_copy_micros = ema_copy_micros_;
        diagnostics.max_copy_micros = max_copy_micros_;
        diagnostics.frame_age_micros = last_frame_completed_ ==
                std::chrono::steady_clock::time_point{}
            ? 0U
            : elapsed_micros(last_frame_completed_, std::chrono::steady_clock::now());
    }

 private:
    mutable std::mutex mutex_;
    std::uint32_t width_ = 0;
    std::uint32_t height_ = 0;
    std::uint64_t generation_ = 0;
    int sample_count_ = 0;
    std::uint32_t active_pass_ = CYCLES_BRIDGE_PASS_COMBINED;
    float exposure_scale_ = 1.0F;
    float gamma_ = 1.0F;
    std::uint32_t view_transform_ = 0;
    float depth_far_ = 1.0F;
    int target_samples_ = 1;
    std::vector<std::uint8_t> rgba_;
    std::uint64_t produced_frame_count_ = 0;
    std::uint64_t copied_frame_count_ = 0;
    std::uint64_t copied_byte_count_ = 0;
    std::uint64_t unchanged_poll_count_ = 0;
    std::uint32_t last_convert_micros_ = 0;
    std::uint32_t ema_convert_micros_ = 0;
    std::uint32_t max_convert_micros_ = 0;
    std::uint32_t last_copy_micros_ = 0;
    std::uint32_t ema_copy_micros_ = 0;
    std::uint32_t max_copy_micros_ = 0;
    std::chrono::steady_clock::time_point last_frame_completed_{};
};

class FrameOutputDriver final : public ccl::OutputDriver {
 public:
    explicit FrameOutputDriver(FrameStore& frames) : frames_(frames) {}

    void write_render_tile(const Tile& tile) override {
        frames_.update(tile);
    }

    bool update_render_tile(const Tile& tile) override {
        return frames_.update(tile);
    }

 private:
    FrameStore& frames_;
};

class MemoryImageLoader final : public ccl::ImageLoader {
 public:
    MemoryImageLoader(
        std::string name,
        std::uint32_t width,
        std::uint32_t height,
        std::shared_ptr<const SceneResourcesData> resources,
        std::uint32_t pixel_offset,
        std::uint32_t pixel_size)
        : name_(std::move(name)),
          width_(width),
          height_(height),
          resources_(std::move(resources)),
          pixel_offset_(pixel_offset),
          pixel_size_(pixel_size) {}

    bool load_metadata(
        ccl::ImageMetaData& metadata,
        const ccl::ImageLoaderParams&,
        ccl::Progress&) override {
        metadata.width = width_;
        metadata.height = height_;
        metadata.channels = 4;
        metadata.type = ccl::IMAGE_DATA_TYPE_BYTE4;
        metadata.is_compressible_as_srgb = true;
        return true;
    }

    bool load_pixels(const ccl::ImageMetaData& metadata, void* pixels) override {
        if (metadata.memory_size() != pixel_size_) {
            return false;
        }
        std::memcpy(
            pixels,
            resources_->texture_pixels.data() + pixel_offset_,
            pixel_size_);
        metadata.conform_pixels(pixels);
        return true;
    }

    ccl::string name() const override {
        return name_;
    }

    bool equals(const ccl::ImageLoader& other) const override {
        const auto* image = dynamic_cast<const MemoryImageLoader*>(&other);
        return image != nullptr && image->name_ == name_;
    }

 private:
    std::string name_;
    std::uint32_t width_;
    std::uint32_t height_;
    std::shared_ptr<const SceneResourcesData> resources_;
    std::uint32_t pixel_offset_;
    std::uint32_t pixel_size_;
};

std::vector<ccl::ImageHandle> create_images(
    ccl::Scene* scene,
    const SceneRequest& request) {
    const SceneResourcesData& resources = *request.resources;
    ccl::ImageParams params;
    params.colorspace = ccl::u_colorspace_scene_linear_srgb;
    params.alpha_type = ccl::IMAGE_ALPHA_UNASSOCIATED;
    params.interpolation = ccl::INTERPOLATION_CLOSEST;
    params.extension = ccl::EXTENSION_REPEAT;

    std::vector<ccl::ImageHandle> images;
    images.reserve(resources.textures.size());
    for (std::size_t index = 0; index < resources.textures.size(); ++index) {
        const CyclesBridgeTexture& texture = resources.textures[index];
        auto loader = ccl::make_unique<MemoryImageLoader>(
            "minecraft_texture_" + std::to_string(index),
            texture.width,
            texture.height,
            request.resources,
            texture.pixel_offset,
            texture.pixel_size);
        images.push_back(scene->image_manager->add_image(std::move(loader), params));
    }
    return images;
}

ccl::Shader* create_material_shader(
    ccl::Scene* scene,
    const CyclesBridgeMaterial& material,
    const ccl::ImageHandle& image,
    std::size_t index) {
    auto graph = ccl::make_unique<ccl::ShaderGraph>();
    ccl::TextureCoordinateNode* coordinates =
        graph->create_node<ccl::TextureCoordinateNode>();
    ccl::ImageTextureNode* texture = graph->create_node<ccl::ImageTextureNode>();
    texture->handle = image;
    texture->set_colorspace(ccl::u_colorspace_scene_linear_srgb);
    texture->set_alpha_type(ccl::IMAGE_ALPHA_UNASSOCIATED);
    texture->set_interpolation(ccl::INTERPOLATION_CLOSEST);
    texture->set_extension(ccl::EXTENSION_REPEAT);
    graph->connect(coordinates->output("UV"), texture->input("Vector"));

    ccl::VertexColorNode* vertex_color = graph->create_node<ccl::VertexColorNode>();
    ccl::VectorMathNode* multiply = graph->create_node<ccl::VectorMathNode>();
    multiply->set_math_type(ccl::NODE_VECTOR_MATH_MULTIPLY);
    graph->connect(texture->output("Color"), multiply->input("Vector1"));
    graph->connect(vertex_color->output("Color"), multiply->input("Vector2"));

    ccl::DiffuseBsdfNode* diffuse = graph->create_node<ccl::DiffuseBsdfNode>();
    diffuse->set_roughness(0.8F);
    graph->connect(multiply->output("Vector"), diffuse->input("Color"));
    ccl::ShaderOutput* opaque_closure = diffuse->output("BSDF");

    if (material.emission_strength > 0.0F) {
        ccl::EmissionNode* emission = graph->create_node<ccl::EmissionNode>();
        emission->set_strength(material.emission_strength);
        graph->connect(multiply->output("Vector"), emission->input("Color"));
        ccl::AddClosureNode* add = graph->create_node<ccl::AddClosureNode>();
        graph->connect(opaque_closure, add->input("Closure1"));
        graph->connect(emission->output("Emission"), add->input("Closure2"));
        opaque_closure = add->output("Closure");
    }

    ccl::ShaderOutput* surface = opaque_closure;
    if ((material.flags & CYCLES_BRIDGE_MATERIAL_BLEND) != 0U) {
        ccl::TransparentBsdfNode* transparent =
            graph->create_node<ccl::TransparentBsdfNode>();
        ccl::MixClosureNode* blend = graph->create_node<ccl::MixClosureNode>();
        graph->connect(texture->output("Alpha"), blend->input("Fac"));
        graph->connect(transparent->output("BSDF"), blend->input("Closure1"));
        graph->connect(opaque_closure, blend->input("Closure2"));
        surface = blend->output("Closure");
    } else if ((material.flags & CYCLES_BRIDGE_MATERIAL_CUTOUT) != 0U) {
        ccl::MathNode* threshold = graph->create_node<ccl::MathNode>();
        threshold->set_math_type(ccl::NODE_MATH_GREATER_THAN);
        threshold->set_value2(material.alpha_cutoff);
        graph->connect(texture->output("Alpha"), threshold->input("Value1"));

        ccl::TransparentBsdfNode* transparent =
            graph->create_node<ccl::TransparentBsdfNode>();
        ccl::MixClosureNode* cutout = graph->create_node<ccl::MixClosureNode>();
        graph->connect(threshold->output("Value"), cutout->input("Fac"));
        graph->connect(transparent->output("BSDF"), cutout->input("Closure1"));
        graph->connect(opaque_closure, cutout->input("Closure2"));
        surface = cutout->output("Closure");
    }
    graph->connect(surface, graph->output()->input("Surface"));

    ccl::Shader* shader = scene->create_node<ccl::Shader>();
    shader->name = "minecraft_material_" + std::to_string(index);
    shader->set_graph(std::move(graph));
    shader->tag_update(scene);
    return shader;
}

void configure_background(ccl::Scene* scene) {
    auto graph = ccl::make_unique<ccl::ShaderGraph>();
    ccl::BackgroundNode* background = graph->create_node<ccl::BackgroundNode>();
    background->set_color(ccl::make_float3(0.18F, 0.32F, 0.55F));
    background->set_strength(0.8F);
    graph->connect(background->output("Background"), graph->output()->input("Surface"));
    scene->default_background->set_graph(std::move(graph));
    scene->default_background->tag_update(scene);
    scene->background->set_shader(scene->default_background);
    scene->background->set_transparent(false);
    scene->background->tag_update(scene);
}

void populate_section_mesh(ccl::Mesh* mesh, const SectionRequest& section) {
    mesh->resize_mesh(
        static_cast<int>(section.vertices.size()),
        static_cast<int>(section.triangles.size()));

    ccl::packed_float3* positions = mesh->get_position_for_write();
    int* triangles = mesh->get_triangles().data();
    int* triangle_shaders = mesh->get_shader().data();
    bool* smooth = mesh->get_smooth().data();
    ccl::Attribute* normal_attribute = mesh->attributes.add(ccl::ATTR_STD_VERTEX_NORMAL);
    ccl::packed_normal* normals = normal_attribute->data_for_write<ccl::packed_normal>();
    ccl::Attribute* uv_attribute = mesh->attributes.add(ccl::ATTR_STD_UV);
    ccl::float2* uvs = uv_attribute->data_for_write<ccl::float2>();
    ccl::Attribute* color_attribute = mesh->attributes.add(ccl::ATTR_STD_VERTEX_COLOR);
    ccl::uchar4* colors = color_attribute->data_for_write<ccl::uchar4>();

    for (std::size_t index = 0; index < section.vertices.size(); ++index) {
        const CyclesBridgeVertex& vertex = section.vertices[index];
        positions[index] = ccl::make_float3(
            vertex.position_x, vertex.position_y, vertex.position_z);
        ccl::float3 normal = ccl::make_float3(
            vertex.normal_x, vertex.normal_y, vertex.normal_z);
        const float length = ccl::len(normal);
        normal = length <= 1.0e-8F
            ? ccl::make_float3(0.0F, 1.0F, 0.0F)
            : normal / length;
        normals[index] = ccl::packed_normal(normal);
    }
    for (std::size_t index = 0; index < section.triangles.size(); ++index) {
        const CyclesBridgeTriangle& triangle = section.triangles[index];
        const std::uint32_t indices[3] = {
            triangle.vertex_0, triangle.vertex_1, triangle.vertex_2};
        for (std::size_t corner = 0; corner < 3; ++corner) {
            const std::size_t output_index = index * 3U + corner;
            const CyclesBridgeVertex& vertex = section.vertices[indices[corner]];
            triangles[output_index] = static_cast<int>(indices[corner]);
            uvs[output_index] = ccl::make_float2(vertex.texture_u, vertex.texture_v);
            const std::uint32_t rgba = vertex.packed_rgba;
            colors[output_index] = ccl::make_uchar4(
                to_unorm(srgb_to_linear(rgba & 0xFFU)),
                to_unorm(srgb_to_linear((rgba >> 8U) & 0xFFU)),
                to_unorm(srgb_to_linear((rgba >> 16U) & 0xFFU)),
                static_cast<std::uint8_t>((rgba >> 24U) & 0xFFU));
        }
        triangle_shaders[index] = static_cast<int>(triangle.material_index);
        smooth[index] = false;
    }

    mesh->tag_position_modified();
    mesh->tag_triangles_modified();
    mesh->tag_shader_modified();
    mesh->tag_smooth_modified();
}

SectionSceneNodes create_section_nodes(
    ccl::Scene* scene,
    const SceneRequest& request,
    const std::shared_ptr<const SectionRequest>& section,
    const std::vector<ccl::Shader*>& shaders) {
    ccl::Mesh* mesh = scene->create_node<ccl::Mesh>();
    ccl::array<ccl::Node*> used_shaders;
    for (ccl::Shader* shader : shaders) {
        used_shaders.push_back_slow(shader);
    }
    mesh->set_used_shaders(used_shaders);
    populate_section_mesh(mesh, *section);

    ccl::Object* object = scene->create_node<ccl::Object>();
    object->set_geometry(mesh);
    object->set_tfm(ccl::transform_translate(ccl::make_float3(
        static_cast<float>(section->section.origin_x - request.resources->resources.origin_x),
        static_cast<float>(section->section.origin_y - request.resources->resources.origin_y),
        static_cast<float>(section->section.origin_z - request.resources->resources.origin_z))));
    return {mesh, object, section};
}

void build_scene(ccl::Scene* scene, const SceneRequest& request, SceneRuntime& runtime) {
    runtime.clear();
    runtime.resources = request.resources;
    configure_background(scene);
    scene->integrator->set_max_bounce(3);
    scene->integrator->set_max_diffuse_bounce(2);
    scene->integrator->set_max_glossy_bounce(1);
    scene->integrator->set_max_transmission_bounce(0);
    scene->integrator->set_max_volume_bounce(0);
    scene->integrator->set_use_adaptive_sampling(false);

    const std::vector<ccl::ImageHandle> images = create_images(scene, request);
    const SceneResourcesData& resources = *request.resources;
    runtime.shaders.assign(resources.materials.size(), nullptr);
    for (std::size_t index = 0; index < resources.materials.size(); ++index) {
        const CyclesBridgeMaterial& material = resources.materials[index];
        runtime.shaders[index] = create_material_shader(
            scene, material, images[material.texture_index], index);
    }

    for (const auto& entry : request.sections) {
        runtime.sections.emplace(
            entry.first,
            create_section_nodes(scene, request, entry.second, runtime.shaders));
    }
}

void apply_scene_delta(
    ccl::Scene* scene,
    const SceneRequest& request,
    SceneRuntime& runtime) {
    if (runtime.resources != request.resources) {
        throw std::logic_error("incremental scene update changed shared resources");
    }

    for (auto current = runtime.sections.begin(); current != runtime.sections.end();) {
        if (request.sections.contains(current->first)) {
            ++current;
            continue;
        }
        scene->delete_node(current->second.object);
        scene->delete_node(current->second.mesh);
        current = runtime.sections.erase(current);
    }

    for (const auto& entry : request.sections) {
        auto current = runtime.sections.find(entry.first);
        if (current == runtime.sections.end()) {
            runtime.sections.emplace(
                entry.first,
                create_section_nodes(scene, request, entry.second, runtime.shaders));
            continue;
        }
        if (current->second.source == entry.second) {
            continue;
        }

        ccl::Mesh* mesh = current->second.mesh;
        mesh->clear(true);
        populate_section_mesh(mesh, *entry.second);
        mesh->tag_update(scene, true);
        current->second.source = entry.second;
    }
}

ccl::Transform camera_transform(
    const CyclesBridgeCamera& camera,
    const CyclesBridgeScene& scene) {
    double qx = camera.rotation_x;
    double qy = camera.rotation_y;
    double qz = camera.rotation_z;
    double qw = camera.rotation_w;
    const double length = std::sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
    if (length <= 1.0e-12 || !std::isfinite(length)) {
        qx = qy = qz = 0.0;
        qw = 1.0;
    } else {
        qx /= length;
        qy /= length;
        qz /= length;
        qw /= length;
    }

    const float xx = static_cast<float>(qx * qx);
    const float yy = static_cast<float>(qy * qy);
    const float zz = static_cast<float>(qz * qz);
    const float xy = static_cast<float>(qx * qy);
    const float xz = static_cast<float>(qx * qz);
    const float yz = static_cast<float>(qy * qz);
    const float xw = static_cast<float>(qx * qw);
    const float yw = static_cast<float>(qy * qw);
    const float zw = static_cast<float>(qz * qw);
    const float px = static_cast<float>(camera.position_x - scene.origin_x);
    const float py = static_cast<float>(camera.position_y - scene.origin_y);
    const float pz = static_cast<float>(camera.position_z - scene.origin_z);

    // Minecraft rotates a camera whose local forward axis is -Z, while Cycles
    // emits perspective rays along local +Z. Negate the local Z basis column,
    // matching Cycles' own Hydra camera conversion.
    return {
        ccl::make_float4(1.0F - 2.0F * (yy + zz), 2.0F * (xy - zw), -2.0F * (xz + yw), px),
        ccl::make_float4(2.0F * (xy + zw), 1.0F - 2.0F * (xx + zz), -2.0F * (yz - xw), py),
        ccl::make_float4(2.0F * (xz - yw), 2.0F * (yz + xw), -(1.0F - 2.0F * (xx + yy)), pz),
    };
}

ccl::BufferParams configure_camera(
    ccl::Session& session,
    const SceneRequest& scene_request,
    const CameraRequest& camera_request) {
    ccl::Camera* camera = session.scene->camera;
    camera->set_camera_type(ccl::CAMERA_PERSPECTIVE);
    camera->set_full_width(static_cast<int>(camera_request.render_width));
    camera->set_full_height(static_cast<int>(camera_request.render_height));
    camera->set_fov(camera_request.camera.vertical_fov_radians);
    camera->set_nearclip(0.05F);
    camera->set_farclip(std::max(1.0F, camera_request.camera.depth_far));
    const CyclesBridgeSceneResources& resources = scene_request.resources->resources;
    CyclesBridgeScene scene{};
    scene.origin_x = resources.origin_x;
    scene.origin_y = resources.origin_y;
    scene.origin_z = resources.origin_z;
    camera->set_matrix(camera_transform(camera_request.camera, scene));
    camera->compute_auto_viewplane();
    camera->need_flags_update = true;
    camera->need_device_update = true;

    ccl::BufferParams buffer;
    buffer.width = static_cast<int>(camera_request.render_width);
    buffer.height = static_cast<int>(camera_request.render_height);
    buffer.full_width = buffer.width;
    buffer.full_height = buffer.height;
    return buffer;
}

std::uint32_t configure_scene_settings(
    ccl::Scene* scene,
    const ccl::DeviceInfo& device,
    const CyclesBridgeRenderSettings& settings) {
    ccl::Integrator* integrator = scene->integrator;
    integrator->set_min_bounce(static_cast<int>(settings.minimum_bounce));
    integrator->set_max_bounce(static_cast<int>(settings.maximum_bounce));
    integrator->set_max_diffuse_bounce(static_cast<int>(settings.diffuse_bounces));
    integrator->set_max_glossy_bounce(static_cast<int>(settings.glossy_bounces));
    integrator->set_max_transmission_bounce(static_cast<int>(settings.transmission_bounces));
    integrator->set_max_volume_bounce(static_cast<int>(settings.volume_bounces));
    integrator->set_transparent_max_bounce(static_cast<int>(settings.transparent_bounces));
    integrator->set_sample_clamp_direct(settings.clamp_direct);
    integrator->set_sample_clamp_indirect(settings.clamp_indirect);
    integrator->set_filter_glossy(settings.filter_glossy);
    integrator->set_caustics_reflective(settings.reflective_caustics != 0U);
    integrator->set_caustics_refractive(settings.refractive_caustics != 0U);
    integrator->set_seed(settings.seed);
    integrator->set_use_adaptive_sampling(settings.adaptive_sampling != 0U);
    integrator->set_adaptive_min_samples(static_cast<int>(settings.minimum_samples));
    integrator->set_adaptive_threshold(settings.noise_threshold);

    std::uint32_t effective_denoiser = 0;
    const bool optix_available = (device.denoisers & ccl::DENOISER_OPTIX) != 0;
    const bool oidn_available = (device.denoisers & ccl::DENOISER_OPENIMAGEDENOISE) != 0;
    if ((settings.denoiser_mode == 1U || settings.denoiser_mode == 2U)
        && optix_available) {
        effective_denoiser = 1U;
        integrator->set_denoiser_type(ccl::DENOISER_OPTIX);
    } else if ((settings.denoiser_mode == 1U || settings.denoiser_mode == 3U)
               && oidn_available) {
        effective_denoiser = 2U;
        integrator->set_denoiser_type(ccl::DENOISER_OPENIMAGEDENOISE);
    }
    integrator->set_use_denoise(effective_denoiser != 0U);
    integrator->set_denoise_start_sample(static_cast<int>(settings.denoiser_start_sample));
    int denoiser_passes = ccl::DENOISER_PASS_NONE;
    if (settings.denoiser_input >= 1U) {
        denoiser_passes |= ccl::DENOISER_PASS_ALBEDO;
    }
    if (settings.denoiser_input >= 2U) {
        denoiser_passes |= ccl::DENOISER_PASS_NORMAL;
    }
    integrator->set_denoiser_passes(denoiser_passes);
    const std::array<ccl::DenoiserPrefilter, 3> prefilters = {
        ccl::DENOISER_PREFILTER_NONE,
        ccl::DENOISER_PREFILTER_FAST,
        ccl::DENOISER_PREFILTER_ACCURATE,
    };
    integrator->set_denoiser_prefilter(prefilters[settings.denoiser_prefilter]);
    const std::array<ccl::DenoiserQuality, 3> qualities = {
        ccl::DENOISER_QUALITY_FAST,
        ccl::DENOISER_QUALITY_BALANCED,
        ccl::DENOISER_QUALITY_HIGH,
    };
    integrator->set_denoiser_quality(qualities[settings.denoiser_quality]);
    integrator->set_denoise_use_gpu(settings.denoiser_use_gpu != 0U);

    ccl::Film* film = scene->film;
    const std::array<ccl::FilterType, 3> filters = {
        ccl::FILTER_BOX,
        ccl::FILTER_GAUSSIAN,
        ccl::FILTER_BLACKMAN_HARRIS,
    };
    film->set_filter_type(filters[settings.pixel_filter]);
    film->set_filter_width(settings.filter_width);
    film->set_display_pass(pass_type(settings.active_pass));
    film->set_use_sample_count(
        settings.adaptive_sampling != 0U
        || settings.active_pass == CYCLES_BRIDGE_PASS_SAMPLE_COUNT);
    return effective_denoiser;
}

void create_output_passes(ccl::Scene* scene, const CyclesBridgeRenderSettings& settings) {
    ccl::Pass* combined = scene->create_node<ccl::Pass>();
    combined->set_name(ccl::ustring(pass_name(CYCLES_BRIDGE_PASS_COMBINED)));
    combined->set_type(ccl::PASS_COMBINED);
    if (settings.active_pass != CYCLES_BRIDGE_PASS_COMBINED) {
        ccl::Pass* selected = scene->create_node<ccl::Pass>();
        selected->set_name(ccl::ustring(pass_name(settings.active_pass)));
        selected->set_type(pass_type(settings.active_pass));
    }
}

}  // namespace

class CyclesEngine::Impl final {
 public:
    Impl() {
        initialize_cycles_runtime();
        devices_ = enumerate_devices();
        if (devices_.empty()) {
            throw std::runtime_error("Cycles reported no OptiX, CUDA, or CPU devices");
        }
        requested_settings_ = default_settings();
        selected_device_ = devices_.front();
        state_ = "waiting-scene";
        worker_ = std::thread([this] { worker_main(); });
    }

    ~Impl() {
        {
            std::lock_guard lock(request_mutex_);
            stopping_ = true;
        }
        request_changed_.notify_all();
        if (worker_.joinable()) {
            worker_.join();
        }
    }

    bool upload(
        const CyclesBridgeScene& scene,
        const CyclesBridgeVertex* vertices,
        const CyclesBridgeTriangle* triangles,
        const CyclesBridgeMaterial* materials,
        const CyclesBridgeTexture* textures,
        const std::uint8_t* texture_pixels,
        std::string& error) {
        auto resources = std::make_shared<SceneResourcesData>();
        resources->resources.struct_size = sizeof(CyclesBridgeSceneResources);
        resources->resources.struct_version = 1;
        resources->resources.origin_x = scene.origin_x;
        resources->resources.origin_y = scene.origin_y;
        resources->resources.origin_z = scene.origin_z;
        resources->resources.material_count = scene.material_count;
        resources->resources.texture_count = scene.texture_count;
        resources->resources.texture_byte_count = scene.texture_byte_count;
        if (scene.material_count != 0) {
            resources->materials.assign(materials, materials + scene.material_count);
        }
        if (scene.texture_count != 0) {
            resources->textures.assign(textures, textures + scene.texture_count);
        }
        if (scene.texture_byte_count != 0) {
            resources->texture_pixels.assign(
                texture_pixels, texture_pixels + scene.texture_byte_count);
        }

        auto request = std::make_shared<SceneRequest>();
        request->resources = resources;
        if (scene.triangle_count != 0) {
            auto section = std::make_shared<SectionRequest>();
            section->section.struct_size = sizeof(CyclesBridgeSection);
            section->section.struct_version = 1;
            section->section.section_id = 0;
            section->section.origin_x = scene.origin_x;
            section->section.origin_y = scene.origin_y;
            section->section.origin_z = scene.origin_z;
            section->section.vertex_count = scene.vertex_count;
            section->section.triangle_count = scene.triangle_count;
            section->vertices.assign(vertices, vertices + scene.vertex_count);
            section->triangles.assign(triangles, triangles + scene.triangle_count);
            request->sections.emplace(0, std::move(section));
        }
        {
            std::lock_guard lock(request_mutex_);
            if (stopping_) {
                error = "Cycles worker is stopping";
                return false;
            }
            staging_resources_ = resources;
            staging_sections_ = request->sections;
            request->revision = ++scene_revision_;
            requested_scene_ = std::move(request);
        }
        set_state("scene-queued", {});
        request_changed_.notify_all();
        return true;
    }

    bool reset_scene(
        const CyclesBridgeSceneResources& resources,
        const CyclesBridgeMaterial* materials,
        const CyclesBridgeTexture* textures,
        const std::uint8_t* texture_pixels,
        std::string& error) {
        auto copied = std::make_shared<SceneResourcesData>();
        copied->resources = resources;
        if (resources.material_count != 0) {
            copied->materials.assign(materials, materials + resources.material_count);
        }
        if (resources.texture_count != 0) {
            copied->textures.assign(textures, textures + resources.texture_count);
        }
        if (resources.texture_byte_count != 0) {
            copied->texture_pixels.assign(
                texture_pixels, texture_pixels + resources.texture_byte_count);
        }
        {
            std::lock_guard lock(request_mutex_);
            if (stopping_) {
                error = "Cycles worker is stopping";
                return false;
            }
            staging_resources_ = std::move(copied);
            staging_sections_.clear();
            requested_scene_.reset();
            requested_camera_.reset();
            ++scene_reset_revision_;
        }
        frames_.clear();
        set_state("scene-staging", {});
        request_changed_.notify_all();
        return true;
    }

    bool upsert_section(
        const CyclesBridgeSection& section,
        const CyclesBridgeVertex* vertices,
        const CyclesBridgeTriangle* triangles,
        std::string& error) {
        auto copied = std::make_shared<SectionRequest>();
        copied->section = section;
        if (section.vertex_count != 0) {
            copied->vertices.assign(vertices, vertices + section.vertex_count);
        }
        if (section.triangle_count != 0) {
            copied->triangles.assign(triangles, triangles + section.triangle_count);
        }
        std::lock_guard lock(request_mutex_);
        if (stopping_) {
            error = "Cycles worker is stopping";
            return false;
        }
        if (!staging_resources_) {
            error = "scene resources have not been reset";
            return false;
        }
        for (const CyclesBridgeTriangle& triangle : copied->triangles) {
            if (triangle.material_index >= staging_resources_->materials.size()) {
                error = "section references an unknown material";
                return false;
            }
        }
        staging_sections_[section.section_id] = std::move(copied);
        return true;
    }

    bool remove_section(std::int64_t section_id, std::string& error) {
        std::lock_guard lock(request_mutex_);
        if (stopping_) {
            error = "Cycles worker is stopping";
            return false;
        }
        if (!staging_resources_) {
            error = "scene resources have not been reset";
            return false;
        }
        staging_sections_.erase(section_id);
        return true;
    }

    bool commit_scene(std::string& error) {
        const auto commit_start = std::chrono::steady_clock::now();
        auto request = std::make_shared<SceneRequest>();
        {
            std::lock_guard lock(request_mutex_);
            if (stopping_) {
                error = "Cycles worker is stopping";
                return false;
            }
            if (!staging_resources_) {
                error = "scene resources have not been reset";
                return false;
            }
            request->resources = staging_resources_;
            request->sections = staging_sections_;
            request->revision = ++scene_revision_;
            requested_scene_ = request;
            if (requested_camera_) {
                requested_camera_->sample_count =
                    static_cast<int>(requested_settings_.interactive_samples);
                requested_camera_->sampling_state =
                    CYCLES_BRIDGE_SAMPLING_INTERACTIVE;
                requested_camera_->revision = ++camera_revision_;
                last_camera_change_ = std::chrono::steady_clock::now();
                last_camera_generation_ = frames_.generation();
            }
        }
        record_scene_commit(elapsed_micros(
            commit_start, std::chrono::steady_clock::now()));
        set_state("scene-queued", {});
        request_changed_.notify_all();
        return true;
    }

    bool apply_settings(
        const CyclesBridgeRenderSettings& settings,
        std::string& error) {
        std::uint32_t reset_level = CYCLES_BRIDGE_RESET_NONE;
        bool display_only_no_op = false;
        {
            std::lock_guard lock(request_mutex_);
            if (stopping_) {
                error = "Cycles worker is stopping";
                return false;
            }
            display_only_no_op = settings_revision_ > 0
                && same_render_settings(settings, requested_settings_);
            if (!display_only_no_op) {
                if (settings.device_policy != requested_settings_.device_policy) {
                    reset_level = CYCLES_BRIDGE_RESET_SESSION;
                } else if (settings.active_pass != requested_settings_.active_pass
                           || settings.resolution_mode != requested_settings_.resolution_mode
                           || settings.render_width != requested_settings_.render_width
                           || settings.render_height != requested_settings_.render_height
                           || settings.resolution_percentage
                               != requested_settings_.resolution_percentage) {
                    reset_level = CYCLES_BRIDGE_RESET_BUFFER;
                } else if (!same_render_settings(settings, requested_settings_)) {
                    reset_level = CYCLES_BRIDGE_RESET_ACCUMULATION;
                }
                requested_settings_ = settings;
                if (requested_settings_.revision <= settings_revision_) {
                    requested_settings_.revision = settings_revision_ + 1U;
                }
                settings_revision_ = requested_settings_.revision;
                requested_reset_level_ = reset_level;
                if (requested_camera_) {
                    std::tie(requested_camera_->render_width, requested_camera_->render_height) =
                        render_dimensions(
                            requested_camera_->camera.viewport_width,
                            requested_camera_->camera.viewport_height,
                            requested_settings_);
                    requested_camera_->sample_count =
                        static_cast<int>(requested_settings_.interactive_samples);
                    requested_camera_->sampling_state =
                        CYCLES_BRIDGE_SAMPLING_INTERACTIVE;
                    requested_camera_->revision = ++camera_revision_;
                    last_camera_change_ = std::chrono::steady_clock::now();
                    last_camera_generation_ = frames_.generation();
                }
            }
        }
        if (display_only_no_op) {
            std::lock_guard lock(state_mutex_);
            last_reset_level_ = CYCLES_BRIDGE_RESET_NONE;
            return true;
        }
        if (reset_level >= CYCLES_BRIDGE_RESET_BUFFER) {
            frames_.clear();
        }
        {
            std::lock_guard lock(state_mutex_);
            last_reset_level_ = reset_level;
        }
        set_state("settings-queued", {});
        request_changed_.notify_all();
        return true;
    }

    void query_capabilities(CyclesBridgeCapabilities& capabilities) const {
        capabilities = {};
        capabilities.struct_size = sizeof(capabilities);
        capabilities.struct_version = 1;
        capabilities.capability_flags =
            CYCLES_BRIDGE_CAPABILITY_SETTINGS
            | CYCLES_BRIDGE_CAPABILITY_PASS_VIEWER
            | CYCLES_BRIDGE_CAPABILITY_DENOISE;
#if defined(WITH_OPTIX)
        capabilities.capability_flags |= CYCLES_BRIDGE_CAPABILITY_OPTIX_COMPILED;
#endif
#if defined(WITH_CUDA)
        capabilities.capability_flags |= CYCLES_BRIDGE_CAPABILITY_CUDA_COMPILED;
#endif
#if defined(WITH_OPENIMAGEDENOISE)
        capabilities.capability_flags |= CYCLES_BRIDGE_CAPABILITY_OIDN_COMPILED;
#endif
#if defined(WITH_OCIO)
        capabilities.capability_flags |= CYCLES_BRIDGE_CAPABILITY_OCIO_COMPILED;
#endif
        capabilities.pass_mask = (1ULL << CYCLES_BRIDGE_PASS_COUNT) - 1ULL;
        capabilities.maximum_width = kMaximumRenderWidth;
        capabilities.maximum_height = kMaximumRenderHeight;
        capabilities.device_count = static_cast<std::uint32_t>(devices_.size());
        for (const ccl::DeviceInfo& device : devices_) {
            capabilities.device_mask |= device_mask(device);
            if ((device.denoisers & ccl::DENOISER_OPTIX) != 0) {
                capabilities.denoiser_mask |= CYCLES_BRIDGE_DENOISER_OPTIX;
            }
#if defined(WITH_OPENIMAGEDENOISE)
            if ((device.denoisers & ccl::DENOISER_OPENIMAGEDENOISE) != 0) {
                capabilities.denoiser_mask |= CYCLES_BRIDGE_DENOISER_OPENIMAGEDENOISE;
            }
#endif
        }
    }

    void query_diagnostics(CyclesBridgeDiagnostics& diagnostics) const {
        diagnostics = {};
        diagnostics.struct_size = sizeof(diagnostics);
        diagnostics.struct_version = 1;
        {
            std::lock_guard lock(request_mutex_);
            diagnostics.scene_revision = requested_scene_ ? requested_scene_->revision : 0;
            diagnostics.camera_revision = requested_camera_ ? requested_camera_->revision : 0;
            diagnostics.section_count = requested_scene_
                ? static_cast<std::uint32_t>(requested_scene_->sections.size())
                : 0U;
        }
        {
            std::lock_guard lock(state_mutex_);
            diagnostics.state_code = state_code_;
            diagnostics.device_type = device_diagnostic_id(selected_device_);
            diagnostics.effective_denoiser = effective_denoiser_;
            diagnostics.reset_level = last_reset_level_;
            diagnostics.settings_revision = active_settings_revision_diagnostic_;
            diagnostics.active_pass = active_pass_diagnostic_;
            diagnostics.target_sample_count = target_sample_count_diagnostic_;
            diagnostics.sampling_state = sampling_state_diagnostic_;
            diagnostics.sample_rate = sample_rate_diagnostic_;
            diagnostics.scene_commit_count = scene_commit_count_;
            diagnostics.scene_delta_count = scene_delta_count_;
            diagnostics.render_start_count = render_start_count_;
            diagnostics.last_scene_commit_micros = last_scene_commit_micros_;
            diagnostics.ema_scene_commit_micros = ema_scene_commit_micros_;
            diagnostics.max_scene_commit_micros = max_scene_commit_micros_;
            diagnostics.last_scene_delta_micros = last_scene_delta_micros_;
            diagnostics.ema_scene_delta_micros = ema_scene_delta_micros_;
            diagnostics.max_scene_delta_micros = max_scene_delta_micros_;
            diagnostics.last_render_start_micros = last_render_start_micros_;
            diagnostics.ema_render_start_micros = ema_render_start_micros_;
            diagnostics.max_render_start_micros = max_render_start_micros_;
        }
        frames_.fill_diagnostics(diagnostics);
    }

    bool render(
        const CyclesBridgeCamera& camera,
        std::uint8_t* rgba,
        std::uint64_t rgba_capacity,
        std::string& error) {
        if (camera.viewport_width == 0 || camera.viewport_height == 0
            || !finite_camera(camera)
            || camera.vertical_fov_radians <= 0.0F
            || camera.vertical_fov_radians >= 3.14159265F
            || camera.depth_far <= 0.0F) {
            error = "invalid camera";
            return false;
        }
        const std::uint64_t pixels =
            static_cast<std::uint64_t>(camera.viewport_width) * camera.viewport_height;
        if (pixels > std::numeric_limits<std::uint64_t>::max() / 4U
            || rgba_capacity < pixels * 4U) {
            error = "RGBA output buffer is too small";
            return false;
        }

        if (!queue_camera(camera, error)) {
            return false;
        }
        frames_.copy_scaled(rgba, camera.viewport_width, camera.viewport_height);
        return true;
    }

    bool render_frame(
        const CyclesBridgeCamera& camera,
        CyclesBridgeFrame& frame,
        std::uint8_t* rgba,
        std::uint64_t rgba_capacity,
        std::string& error) {
        if (!valid_camera(camera, error)) {
            return false;
        }
        if (!queue_camera(camera, error)) {
            return false;
        }
        return frames_.copy_native(rgba, rgba_capacity, frame.generation, frame, error);
    }

    [[nodiscard]] std::string info() const {
        ccl::DeviceInfo selected;
        std::string state;
        std::string error;
        {
            std::lock_guard lock(state_mutex_);
            selected = selected_device_;
            state = state_;
            error = terminal_error_;
        }
        const auto [width, height] = frames_.size();
        std::ostringstream output;
        output << "backend=" << device_type_name(selected.type)
               << ";device=" << selected.description
               << ";state=" << state
               << ";frame=" << (frames_.ready() ? "ready" : "pending")
               << ";resolution=" << width << 'x' << height;
        if (!error.empty()) {
            output << ";error=" << error;
        }
        return output.str();
    }

 private:
    static bool valid_camera(const CyclesBridgeCamera& camera, std::string& error) {
        if (camera.viewport_width == 0 || camera.viewport_height == 0
            || !finite_camera(camera)
            || camera.vertical_fov_radians <= 0.0F
            || camera.vertical_fov_radians >= 3.14159265F
            || camera.depth_far <= 0.0F) {
            error = "invalid camera";
            return false;
        }
        return true;
    }

    bool queue_camera(const CyclesBridgeCamera& camera, std::string& error) {
        if (!valid_camera(camera, error)) {
            return false;
        }

        CameraRequest request;
        request.camera = camera;
        const auto now = std::chrono::steady_clock::now();
        bool changed = false;
        {
            std::lock_guard lock(request_mutex_);
            if (!requested_scene_) {
                return true;
            }
            std::tie(request.render_width, request.render_height) = render_dimensions(
                camera.viewport_width,
                camera.viewport_height,
                requested_settings_);
            if (!requested_camera_ || !same_camera(*requested_camera_, request)) {
                request.sample_count =
                    static_cast<int>(requested_settings_.interactive_samples);
                request.sampling_state = CYCLES_BRIDGE_SAMPLING_INTERACTIVE;
                request.revision = ++camera_revision_;
                requested_camera_ = request;
                last_camera_change_ = now;
                last_camera_generation_ = frames_.generation();
                changed = true;
            } else if (requested_camera_->sample_count
                           == static_cast<int>(requested_settings_.interactive_samples)
                       && requested_settings_.still_samples
                           != requested_settings_.interactive_samples
                       && now - last_camera_change_
                           >= std::chrono::milliseconds(
                               requested_settings_.stationary_delay_millis)
                       && frames_.generation() != last_camera_generation_) {
                request.sample_count = static_cast<int>(requested_settings_.still_samples);
                request.sampling_state = CYCLES_BRIDGE_SAMPLING_STILL;
                request.revision = ++camera_revision_;
                requested_camera_ = request;
                changed = true;
            }
        }
        if (changed) {
            set_state("camera-queued", {});
            request_changed_.notify_all();
        }

        std::lock_guard lock(state_mutex_);
        if (!terminal_error_.empty()) {
            error = terminal_error_;
            return false;
        }
        return true;
    }

    void set_state(std::string state, std::string terminal_error) {
        std::lock_guard lock(state_mutex_);
        state_code_ = state == "failed" ? 7U
            : state == "fallback" ? 6U
            : state == "rendering" ? 5U
            : state == "scene-ready" ? 4U
            : state == "initializing" ? 3U
            : (state == "camera-queued" || state == "scene-queued"
               || state == "settings-queued") ? 2U
            : state == "scene-staging" ? 1U
            : 0U;
        state_ = std::move(state);
        terminal_error_ = std::move(terminal_error);
    }

    void set_device_state(
        const ccl::DeviceInfo& device,
        std::string state,
        std::string terminal_error = {}) {
        std::lock_guard lock(state_mutex_);
        selected_device_ = device;
        state_code_ = state == "failed" ? 7U
            : state == "fallback" ? 6U
            : state == "rendering" ? 5U
            : state == "scene-ready" ? 4U
            : state == "initializing" ? 3U
            : 0U;
        state_ = std::move(state);
        terminal_error_ = std::move(terminal_error);
    }

    ccl::SessionParams make_session_params(const ccl::DeviceInfo& device) const {
        ccl::SessionParams params;
        params.device = device;
        params.denoise_device = device;
        params.headless = true;
        params.background = false;
        params.samples = 1;
        params.use_auto_tile = false;
        params.use_resolution_divider = false;
        return params;
    }

    ccl::unique_ptr<ccl::Session> create_session(
        const ccl::DeviceInfo& device,
        const SceneRequest& scene_request,
        const CyclesBridgeRenderSettings& settings,
        ccl::SessionParams& session_params,
        SceneRuntime& runtime) {
        session_params = make_session_params(device);
        ccl::SceneParams scene_params;
        scene_params.background = false;
        auto session = ccl::make_unique<ccl::Session>(session_params, scene_params);
        session->set_output_driver(ccl::make_unique<FrameOutputDriver>(frames_));
        create_output_passes(session->scene.get(), settings);
        build_scene(session->scene.get(), scene_request, runtime);
        const std::uint32_t effective_denoiser =
            configure_scene_settings(session->scene.get(), device, settings);
        {
            std::lock_guard lock(state_mutex_);
            effective_denoiser_ = effective_denoiser;
        }
        return session;
    }

    bool rebuild_session(
        ccl::unique_ptr<ccl::Session>& session,
        ccl::SessionParams& params,
        const SceneRequest& scene_request,
        const CyclesBridgeRenderSettings& settings,
        SceneRuntime& runtime,
        std::size_t& device_index) {
        if (session) {
            session->cancel(true);
            session.reset();
        }
        runtime.clear();
        while (device_index < devices_.size()) {
            const ccl::DeviceInfo device = devices_[device_index];
            if (!device_matches_policy(device, settings.device_policy)) {
                device_index++;
                continue;
            }
            try {
                set_device_state(device, "initializing");
                session = create_session(device, scene_request, settings, params, runtime);
                set_device_state(device, "scene-ready");
                return true;
            } catch (const std::exception& exception) {
                set_device_state(device, "fallback", exception.what());
                session.reset();
                runtime.clear();
                device_index++;
            }
        }
        const std::string message =
            "no usable Cycles backend matched the selected device policy";
        set_state("failed", message);
        return false;
    }

    void update_session_scene(
        ccl::Session& session,
        const SceneRequest& scene_request,
        SceneRuntime& runtime) {
        const auto delta_start = std::chrono::steady_clock::now();
        set_state("scene-updating", {});
        {
            const ccl::thread_scoped_lock scene_lock(session.scene->mutex);
            apply_scene_delta(session.scene.get(), scene_request, runtime);
        }
        record_scene_delta(elapsed_micros(
            delta_start, std::chrono::steady_clock::now()));
        set_state("scene-ready", {});
    }

    void start_render(
        ccl::Session& session,
        const ccl::SessionParams& params,
        const SceneRequest& scene_request,
        const CameraRequest& camera_request,
        const CyclesBridgeRenderSettings& settings) {
        const auto start_time = std::chrono::steady_clock::now();
        ccl::BufferParams buffer;
        std::uint32_t effective_denoiser = 0;
        {
            const ccl::thread_scoped_lock scene_lock(session.scene->mutex);
            buffer = configure_camera(session, scene_request, camera_request);
            effective_denoiser =
                configure_scene_settings(session.scene.get(), params.device, settings);
        }
        ccl::SessionParams render_params = params;
        render_params.samples = std::max(1, camera_request.sample_count);
        const bool still =
            camera_request.sampling_state == CYCLES_BRIDGE_SAMPLING_STILL;
        const std::uint32_t time_limit_millis = still
            ? settings.still_time_limit_millis
            : settings.interactive_time_limit_millis;
        render_params.time_limit = static_cast<double>(time_limit_millis) / 1000.0;
        frames_.configure(
            settings,
            camera_request.camera.depth_far,
            render_params.samples);
        frames_.set_sample_count(0);
        sampling_target_ = render_params.samples;
        sampling_measure_count_ = 0;
        sampling_rate_ = 0.0F;
        sampling_measure_time_ = std::chrono::steady_clock::now();
        {
            std::lock_guard lock(state_mutex_);
            effective_denoiser_ = effective_denoiser;
            target_sample_count_diagnostic_ =
                static_cast<std::uint32_t>(render_params.samples);
            sampling_state_diagnostic_ = camera_request.sampling_state;
            sample_rate_diagnostic_ = 0.0F;
        }
        session.reset(render_params, buffer);
        session.start();
        record_render_start(elapsed_micros(
            start_time, std::chrono::steady_clock::now()));
        set_state("rendering", {});
    }

    void record_scene_commit(std::uint32_t micros) {
        std::lock_guard lock(state_mutex_);
        last_scene_commit_micros_ = micros;
        ema_scene_commit_micros_ = update_ema(ema_scene_commit_micros_, micros);
        max_scene_commit_micros_ = std::max(max_scene_commit_micros_, micros);
        scene_commit_count_++;
    }

    void record_scene_delta(std::uint32_t micros) {
        std::lock_guard lock(state_mutex_);
        last_scene_delta_micros_ = micros;
        ema_scene_delta_micros_ = update_ema(ema_scene_delta_micros_, micros);
        max_scene_delta_micros_ = std::max(max_scene_delta_micros_, micros);
        scene_delta_count_++;
    }

    void record_render_start(std::uint32_t micros) {
        std::lock_guard lock(state_mutex_);
        last_render_start_micros_ = micros;
        ema_render_start_micros_ = update_ema(ema_render_start_micros_, micros);
        max_render_start_micros_ = std::max(max_render_start_micros_, micros);
        render_start_count_++;
    }

    void update_sampling_progress(ccl::Session& session) {
        const int actual = std::clamp(
            session.progress.get_current_sample(), 0, sampling_target_);
        frames_.set_sample_count(actual);

        const auto now = std::chrono::steady_clock::now();
        if (actual != sampling_measure_count_) {
            const double seconds = std::chrono::duration<double>(
                now - sampling_measure_time_).count();
            if (seconds > 0.0) {
                sampling_rate_ = static_cast<float>(
                    static_cast<double>(actual - sampling_measure_count_) / seconds);
            }
            sampling_measure_count_ = actual;
            sampling_measure_time_ = now;
        } else if (now - sampling_measure_time_ >= std::chrono::milliseconds(500)) {
            sampling_rate_ = 0.0F;
        }

        std::lock_guard lock(state_mutex_);
        sample_rate_diagnostic_ = std::max(0.0F, sampling_rate_);
    }

    void worker_main() {
        ccl::unique_ptr<ccl::Session> session;
        ccl::SessionParams session_params;
        SceneRuntime scene_runtime;
        std::shared_ptr<const SceneRequest> active_scene;
        std::uint64_t active_scene_revision = 0;
        std::uint64_t active_camera_revision = 0;
        std::uint64_t active_reset_revision = 0;
        CyclesBridgeRenderSettings active_settings = default_settings();
        std::uint64_t active_settings_revision = 0;
        std::uint64_t observed_scene_revision = 0;
        std::uint64_t observed_camera_revision = 0;
        std::uint64_t render_start_generation = 0;
        bool render_in_flight = false;
        std::size_t device_index = 0;

        try {
            while (true) {
                std::shared_ptr<const SceneRequest> requested_scene;
                std::optional<CameraRequest> requested_camera;
                std::uint64_t requested_reset_revision = 0;
                CyclesBridgeRenderSettings requested_settings{};
                std::uint32_t requested_settings_reset = CYCLES_BRIDGE_RESET_NONE;
                {
                    std::unique_lock lock(request_mutex_);
                    request_changed_.wait_for(lock, 16ms, [this, observed_scene_revision,
                                                           observed_camera_revision,
                                                           active_reset_revision,
                                                           active_settings_revision] {
                        return stopping_
                            || scene_reset_revision_ != active_reset_revision
                            || settings_revision_ != active_settings_revision
                            || (requested_scene_
                                && requested_scene_->revision != observed_scene_revision)
                            || (requested_camera_
                                && requested_camera_->revision != observed_camera_revision);
                    });
                    if (stopping_) {
                        break;
                    }
                    requested_scene = requested_scene_;
                    requested_camera = requested_camera_;
                    requested_reset_revision = scene_reset_revision_;
                    requested_settings = requested_settings_;
                    requested_settings_reset = requested_reset_level_;
                }
                observed_scene_revision = requested_scene ? requested_scene->revision : 0;
                observed_camera_revision = requested_camera ? requested_camera->revision : 0;

                if (session) {
                    update_sampling_progress(*session);
                }

                if (requested_settings.revision != active_settings_revision) {
                    const bool pass_changed = requested_settings.active_pass
                        != active_settings.active_pass;
                    if (session && (requested_settings_reset == CYCLES_BRIDGE_RESET_SESSION
                                    || pass_changed)) {
                        session->cancel(true);
                        session.reset();
                        scene_runtime.clear();
                        active_scene.reset();
                        active_scene_revision = 0;
                        device_index = 0;
                    } else if (session && render_in_flight) {
                        session->cancel(true);
                    }
                    if (requested_settings_reset >= CYCLES_BRIDGE_RESET_ACCUMULATION) {
                        active_camera_revision = 0;
                        render_in_flight = false;
                        frames_.clear();
                    }
                    active_settings = requested_settings;
                    active_settings_revision = requested_settings.revision;
                    {
                        std::lock_guard lock(state_mutex_);
                        active_settings_revision_diagnostic_ = active_settings_revision;
                        active_pass_diagnostic_ = active_settings.active_pass;
                    }
                }

                if (requested_reset_revision != active_reset_revision) {
                    if (session) {
                        session->cancel(true);
                        session.reset();
                    }
                    scene_runtime.clear();
                    active_scene.reset();
                    active_scene_revision = 0;
                    active_camera_revision = 0;
                    active_reset_revision = requested_reset_revision;
                    render_in_flight = false;
                    frames_.clear();
                } else if (render_in_flight
                           && frames_.generation() != render_start_generation) {
                    render_in_flight = false;
                }

                if (session && session->progress.get_error()) {
                    const std::string backend_error = session->progress.get_error_message();
                    session->cancel(true);
                    session.reset();
                    scene_runtime.clear();
                    device_index++;
                    active_scene_revision = 0;
                    active_camera_revision = 0;
                    render_in_flight = false;
                    frames_.clear();
                    set_state("fallback", backend_error);
                }

                if (!render_in_flight && requested_scene
                    && requested_scene->revision != active_scene_revision) {
                    const bool resources_changed = !session
                        || !active_scene
                        || scene_runtime.resources != requested_scene->resources;
                    if (resources_changed) {
                        if (!rebuild_session(
                                session,
                                session_params,
                                *requested_scene,
                                active_settings,
                                scene_runtime,
                                device_index)) {
                            continue;
                        }
                    } else {
                        update_session_scene(*session, *requested_scene, scene_runtime);
                    }
                    active_scene = requested_scene;
                    active_scene_revision = active_scene->revision;
                    active_camera_revision = 0;
                }

                if (!render_in_flight && session && active_scene && requested_camera
                    && requested_camera->revision != active_camera_revision) {
                    render_start_generation = frames_.generation();
                    start_render(
                        *session,
                        session_params,
                        *active_scene,
                        *requested_camera,
                        active_settings);
                    active_camera_revision = requested_camera->revision;
                    render_in_flight = true;
                }
            }
        } catch (const std::exception& exception) {
            set_state("failed", exception.what());
        } catch (...) {
            set_state("failed", "unknown Cycles worker failure");
        }

        if (session) {
            try {
                session->cancel(true);
                session.reset();
                scene_runtime.clear();
            } catch (...) {
            }
        }
    }

    mutable std::mutex request_mutex_;
    std::condition_variable request_changed_;
    bool stopping_ = false;
    std::uint64_t scene_revision_ = 0;
    std::uint64_t camera_revision_ = 0;
    std::uint64_t scene_reset_revision_ = 0;
    std::uint64_t settings_revision_ = 0;
    std::uint32_t requested_reset_level_ = CYCLES_BRIDGE_RESET_NONE;
    CyclesBridgeRenderSettings requested_settings_{};
    std::shared_ptr<const SceneResourcesData> staging_resources_;
    std::unordered_map<std::int64_t, std::shared_ptr<const SectionRequest>> staging_sections_;
    std::shared_ptr<const SceneRequest> requested_scene_;
    std::optional<CameraRequest> requested_camera_;
    std::chrono::steady_clock::time_point last_camera_change_{};
    std::uint64_t last_camera_generation_ = 0;

    mutable std::mutex state_mutex_;
    ccl::DeviceInfo selected_device_;
    std::uint32_t state_code_ = 0;
    std::uint32_t effective_denoiser_ = 0;
    std::uint32_t last_reset_level_ = CYCLES_BRIDGE_RESET_NONE;
    std::uint64_t active_settings_revision_diagnostic_ = 0;
    std::uint32_t active_pass_diagnostic_ = CYCLES_BRIDGE_PASS_COMBINED;
    std::uint32_t target_sample_count_diagnostic_ = 0;
    std::uint32_t sampling_state_diagnostic_ = CYCLES_BRIDGE_SAMPLING_IDLE;
    float sample_rate_diagnostic_ = 0.0F;
    std::uint64_t scene_commit_count_ = 0;
    std::uint64_t scene_delta_count_ = 0;
    std::uint64_t render_start_count_ = 0;
    std::uint32_t last_scene_commit_micros_ = 0;
    std::uint32_t ema_scene_commit_micros_ = 0;
    std::uint32_t max_scene_commit_micros_ = 0;
    std::uint32_t last_scene_delta_micros_ = 0;
    std::uint32_t ema_scene_delta_micros_ = 0;
    std::uint32_t max_scene_delta_micros_ = 0;
    std::uint32_t last_render_start_micros_ = 0;
    std::uint32_t ema_render_start_micros_ = 0;
    std::uint32_t max_render_start_micros_ = 0;
    std::string state_;
    std::string terminal_error_;

    int sampling_target_ = 0;
    int sampling_measure_count_ = 0;
    float sampling_rate_ = 0.0F;
    std::chrono::steady_clock::time_point sampling_measure_time_{};

    std::vector<ccl::DeviceInfo> devices_;
    FrameStore frames_;
    std::thread worker_;
};

CyclesEngine::CyclesEngine() : impl_(std::make_unique<Impl>()) {}

CyclesEngine::~CyclesEngine() = default;

bool CyclesEngine::upload_scene(
    const CyclesBridgeScene& scene,
    const CyclesBridgeVertex* vertices,
    const CyclesBridgeTriangle* triangles,
    const CyclesBridgeMaterial* materials,
    const CyclesBridgeTexture* textures,
    const std::uint8_t* texture_pixels,
    std::string& error) {
    return impl_->upload(
        scene, vertices, triangles, materials, textures, texture_pixels, error);
}

bool CyclesEngine::reset_scene(
    const CyclesBridgeSceneResources& resources,
    const CyclesBridgeMaterial* materials,
    const CyclesBridgeTexture* textures,
    const std::uint8_t* texture_pixels,
    std::string& error) {
    return impl_->reset_scene(resources, materials, textures, texture_pixels, error);
}

bool CyclesEngine::upsert_section(
    const CyclesBridgeSection& section,
    const CyclesBridgeVertex* vertices,
    const CyclesBridgeTriangle* triangles,
    std::string& error) {
    return impl_->upsert_section(section, vertices, triangles, error);
}

bool CyclesEngine::remove_section(std::int64_t section_id, std::string& error) {
    return impl_->remove_section(section_id, error);
}

bool CyclesEngine::commit_scene(std::string& error) {
    return impl_->commit_scene(error);
}

bool CyclesEngine::apply_settings(
    const CyclesBridgeRenderSettings& settings,
    std::string& error) {
    return impl_->apply_settings(settings, error);
}

void CyclesEngine::query_capabilities(CyclesBridgeCapabilities& capabilities) const {
    impl_->query_capabilities(capabilities);
}

void CyclesEngine::query_diagnostics(CyclesBridgeDiagnostics& diagnostics) const {
    impl_->query_diagnostics(diagnostics);
}

bool CyclesEngine::render(
    const CyclesBridgeCamera& camera,
    std::uint8_t* rgba,
    std::uint64_t rgba_capacity,
    std::string& error) {
    return impl_->render(camera, rgba, rgba_capacity, error);
}

bool CyclesEngine::render_frame(
    const CyclesBridgeCamera& camera,
    CyclesBridgeFrame& frame,
    std::uint8_t* rgba,
    std::uint64_t rgba_capacity,
    std::string& error) {
    return impl_->render_frame(camera, frame, rgba, rgba_capacity, error);
}

std::string CyclesEngine::renderer_info() const {
    return impl_->info();
}

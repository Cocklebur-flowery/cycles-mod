#include "cycles_engine.h"

#include "color_management.h"

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
#include "device/cuda/device.h"
#if defined(WITH_CUDA)
#include "cuew.h"
#endif
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
#include "session/display_driver.h"
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
constexpr float kDegreesToRadians = 0.01745329251994329577F;

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
    settings.dynamic_resolution = 0;
    settings.interactive_resolution_percentage = 50;
    settings.pass_cache_megabytes = 256;
    settings.sampling_pattern = CYCLES_BRIDGE_SAMPLING_PATTERN_BLUE_NOISE_FIRST;
    settings.camera_clip_near = 0.05F;
    settings.camera_clip_far = 0.0F;
    settings.projection_mode = CYCLES_BRIDGE_PROJECTION_MINECRAFT_FOV;
    settings.focal_length_mm = 50.0F;
    settings.sensor_width_mm = 36.0F;
    settings.depth_of_field = 0U;
    settings.focus_distance = 10.0F;
    settings.f_stop = 2.8F;
    settings.aperture_blades = 0U;
    settings.aperture_rotation_degrees = 0.0F;
    settings.aperture_ratio = 1.0F;
    settings.atmosphere_sun_disc = 1U;
    settings.atmosphere_sun_size_degrees = 0.545F;
    settings.atmosphere_sun_intensity = 1.0F;
    settings.atmosphere_sun_elevation_degrees = 45.0F;
    settings.atmosphere_sun_rotation_degrees = 35.0F;
    settings.atmosphere_altitude_meters = 1000.0F;
    settings.atmosphere_air_density = 1.0F;
    settings.atmosphere_aerosol_density = 1.0F;
    settings.atmosphere_ozone_density = 2.0F;
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
    return std::memcmp(&first, &second, sizeof(first)) == 0;
}

bool same_render_settings_except_pass(
    CyclesBridgeRenderSettings first,
    CyclesBridgeRenderSettings second) {
    first.active_pass = second.active_pass;
    return same_render_settings(first, second);
}

bool same_render_settings_except_cache_budget(
    CyclesBridgeRenderSettings first,
    CyclesBridgeRenderSettings second) {
    first.pass_cache_megabytes = second.pass_cache_megabytes;
    return same_render_settings(first, second);
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
    bool preserve_pass_cache = false;
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

std::optional<std::array<std::uint8_t, 16>> query_cuda_device_uuid(
    const ccl::DeviceInfo& device) {
#if defined(WITH_CUDA)
    if (device.type != ccl::DEVICE_OPTIX && device.type != ccl::DEVICE_CUDA) {
        return std::nullopt;
    }
    if (!ccl::device_cuda_init()) {
        return std::nullopt;
    }
    CUdevice cuda_device = 0;
    CUuuid cuda_uuid{};
    if (cuDeviceGet(&cuda_device, device.num) != CUDA_SUCCESS
        || cuDeviceGetUuid(&cuda_uuid, cuda_device) != CUDA_SUCCESS) {
        return std::nullopt;
    }
    std::array<std::uint8_t, 16> result{};
    static_assert(sizeof(cuda_uuid.bytes) == sizeof(result));
    std::memcpy(result.data(), cuda_uuid.bytes, result.size());
    return result;
#else
    (void) device;
    return std::nullopt;
#endif
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
    const CyclesBridgeRenderSettings& settings,
    std::uint32_t sampling_state) {
    const std::uint32_t percentage_value = settings.dynamic_resolution != 0U
            && sampling_state != CYCLES_BRIDGE_SAMPLING_STILL
        ? std::min(
            settings.resolution_percentage,
            std::clamp(settings.interactive_resolution_percentage, 1U, 100U))
        : settings.resolution_percentage;
    const double percentage = static_cast<double>(percentage_value) / 100.0;
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

bool same_camera(
    const CameraRequest& current,
    const CameraRequest& requested,
    bool compare_minecraft_fov,
    bool compare_minecraft_far) {
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
        && (!compare_minecraft_fov
            || nearly_equal(first.vertical_fov_radians, second.vertical_fov_radians, 1.0e-6))
        && (!compare_minecraft_far
            || nearly_equal(first.depth_far, second.depth_far, 1.0e-3));
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
    if (std::isnan(value)) {
        return 0U;
    }
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

constexpr std::size_t kFrameSlotCount = 3U;

struct FrameSlot {
    std::vector<ccl::half4> pixels;
    std::uint32_t pass = CYCLES_BRIDGE_PASS_COMBINED;
    std::uint32_t variant = CYCLES_BRIDGE_FRAME_VARIANT_RAW;
    std::uint64_t camera_revision = 0;
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::uint32_t sample_count = 0;
    std::uint32_t readers = 0;
    std::uint64_t generation = 0;
};

struct CachedPassFrame {
    std::uint32_t pass = CYCLES_BRIDGE_PASS_COMBINED;
    std::uint32_t variant = CYCLES_BRIDGE_FRAME_VARIANT_RAW;
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::uint32_t sample_count = 0;
    std::uint64_t last_access = 0;
    std::vector<ccl::half4> pixels;
};

class FrameStore final {
 public:
    void clear() {
        std::lock_guard lock(mutex_);
        width_ = 0;
        height_ = 0;
        sample_count_ = 0;
        last_frame_completed_ = {};
        published_slot_ = -1;
        writing_slot_ = -1;
        zero_next_display_ = true;
        produced_camera_revision_ = 0;
        pass_cache_.clear();
        pass_cache_bytes_ = 0U;
        generation_++;
    }

    void invalidate_pass_cache() {
        std::lock_guard lock(mutex_);
        pass_cache_.clear();
        pass_cache_bytes_ = 0U;
    }

    void set_cache_budget(std::uint32_t megabytes) {
        std::lock_guard lock(mutex_);
        set_cache_budget_locked(megabytes);
    }

    void configure(
        const CyclesBridgeRenderSettings& settings,
        float depth_far,
        int target_samples,
        bool denoised,
        std::uint64_t camera_revision) {
        std::lock_guard lock(mutex_);
        set_cache_budget_locked(settings.pass_cache_megabytes);
        const std::uint32_t requested_variant = denoised
                && settings.active_pass == CYCLES_BRIDGE_PASS_COMBINED
            ? CYCLES_BRIDGE_FRAME_VARIANT_DENOISED
            : CYCLES_BRIDGE_FRAME_VARIANT_RAW;
        if (active_pass_ != settings.active_pass || active_variant_ != requested_variant) {
            cache_published_locked();
            active_pass_ = settings.active_pass;
            active_variant_ = requested_variant;
            restore_cached_locked();
        }
        const float exposure_scale = std::exp2(settings.exposure_ev);
        const float normalized_depth_far = std::max(1.0F, depth_far);
        const int normalized_target_samples = std::max(1, target_samples);
        const bool rebuild_lut = !display_lut_ready_
            || active_pass_ != settings.active_pass
            || !nearly_equal(exposure_scale_, exposure_scale, 1.0e-6)
            || !nearly_equal(gamma_, settings.gamma, 1.0e-6)
            || view_transform_ != settings.view_transform
            || !nearly_equal(depth_far_, normalized_depth_far, 1.0e-6)
            || target_samples_ != normalized_target_samples;
        active_pass_ = settings.active_pass;
        exposure_scale_ = exposure_scale;
        gamma_ = settings.gamma;
        view_transform_ = settings.view_transform;
        depth_far_ = normalized_depth_far;
        target_samples_ = normalized_target_samples;
        active_camera_revision_ = camera_revision;
        if (rebuild_lut) {
            rebuild_display_luts_locked();
            display_lut_ready_ = true;
        }
    }

    bool display_update_begin(
        const ccl::DisplayDriver::Params& params,
        int texture_width,
        int texture_height) {
        if (params.full_size.x <= 0 || params.full_size.y <= 0
            || texture_width <= 0 || texture_height <= 0) {
            return false;
        }
        mutex_.lock();
        if (display_update_active_) {
            mutex_.unlock();
            return false;
        }
        int next_slot = -1;
        for (std::size_t index = 0; index < slots_.size(); ++index) {
            const FrameSlot& slot = slots_[index];
            if (static_cast<int>(index) != published_slot_ && slot.readers == 0U) {
                next_slot = static_cast<int>(index);
                break;
            }
        }
        if (next_slot < 0) {
            dropped_display_updates_++;
            mutex_.unlock();
            return false;
        }
        display_update_active_ = true;
        writing_slot_ = next_slot;
        display_update_started_ = std::chrono::steady_clock::now();
        const auto width = static_cast<std::uint32_t>(params.full_size.x);
        const auto height = static_cast<std::uint32_t>(params.full_size.y);
        FrameSlot& slot = slots_[static_cast<std::size_t>(writing_slot_)];
        slot.pass = active_pass_;
        slot.variant = active_variant_;
        slot.camera_revision = active_camera_revision_;
        slot.width = width;
        slot.height = height;
        const std::size_t pixel_count = static_cast<std::size_t>(width) * height;
        if (slot.pixels.size() != pixel_count) {
            const ccl::half zero(0U);
            slot.pixels.assign(
                pixel_count,
                ccl::half4{zero, zero, zero, zero});
        } else if (zero_next_display_) {
            const ccl::half zero(0U);
            std::fill(
                slot.pixels.begin(),
                slot.pixels.end(),
                ccl::half4{zero, zero, zero, zero});
        }
        zero_next_display_ = false;
        return true;
    }

    ccl::half4* display_buffer() {
        if (writing_slot_ < 0) {
            return nullptr;
        }
        FrameSlot& slot = slots_[static_cast<std::size_t>(writing_slot_)];
        return slot.pixels.empty() ? nullptr : slot.pixels.data();
    }

    void display_update_end() {
        const auto completed = std::chrono::steady_clock::now();
        FrameSlot& slot = slots_[static_cast<std::size_t>(writing_slot_)];
        slot.generation = ++generation_;
        slot.sample_count = static_cast<std::uint32_t>(std::max(0, sample_count_));
        width_ = slot.width;
        height_ = slot.height;
        published_slot_ = writing_slot_;
        writing_slot_ = -1;
        last_convert_micros_ = elapsed_micros(display_update_started_, completed);
        ema_convert_micros_ = update_ema(ema_convert_micros_, last_convert_micros_);
        max_convert_micros_ = std::max(max_convert_micros_, last_convert_micros_);
        produced_frame_count_++;
        produced_camera_revision_ = slot.camera_revision;
        last_frame_completed_ = completed;
        display_update_active_ = false;
        mutex_.unlock();
    }

    void display_update_cancel() {
        if (!display_update_active_) {
            return;
        }
        writing_slot_ = -1;
        display_update_active_ = false;
        mutex_.unlock();
    }

    void zero_display() {
        std::lock_guard lock(mutex_);
        zero_next_display_ = true;
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
        const FrameSlot* slot = published_frame_locked();
        if (slot == nullptr) {
            return true;
        }

        frame.flags |= CYCLES_BRIDGE_FRAME_READY;
        if (generation_ == previous_generation) {
            unchanged_poll_count_++;
            return true;
        }
        const std::size_t output_size = slot->pixels.size() * 4U;
        if (output == nullptr || capacity < output_size) {
            error = "native frame output buffer is too small";
            return false;
        }
        const auto copy_start = std::chrono::steady_clock::now();
        for (std::size_t index = 0; index < slot->pixels.size(); ++index) {
            write_compatibility_pixel(output + index * 4U, slot->pixels[index]);
        }
        const auto copy_end = std::chrono::steady_clock::now();
        last_copy_micros_ = elapsed_micros(copy_start, copy_end);
        ema_copy_micros_ = update_ema(ema_copy_micros_, last_copy_micros_);
        max_copy_micros_ = std::max(max_copy_micros_, last_copy_micros_);
        copied_frame_count_++;
        copied_byte_count_ += output_size;
        frame.pixel_byte_count = static_cast<std::uint32_t>(output_size);
        frame.flags |= CYCLES_BRIDGE_FRAME_UPDATED;
        return true;
    }

    void copy_scaled(std::uint8_t* output, std::uint32_t width, std::uint32_t height) const {
        std::lock_guard lock(mutex_);
        const FrameSlot* slot = published_frame_locked();
        if (slot == nullptr) {
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
                    static_cast<std::size_t>(source_y) * width_ + source_x;
                const std::size_t target =
                    (static_cast<std::size_t>(y) * width + x) * 4U;
                write_compatibility_pixel(output + target, slot->pixels[source]);
            }
        }
    }

    [[nodiscard]] bool ready() const {
        std::lock_guard lock(mutex_);
        return published_frame_locked() != nullptr;
    }

    [[nodiscard]] std::uint64_t generation() const {
        std::lock_guard lock(mutex_);
        return generation_;
    }

    [[nodiscard]] std::uint64_t produced_frame_count() const {
        std::lock_guard lock(mutex_);
        return produced_frame_count_;
    }

    [[nodiscard]] std::uint64_t produced_camera_revision() const {
        std::lock_guard lock(mutex_);
        return produced_camera_revision_;
    }

    [[nodiscard]] std::pair<std::uint32_t, std::uint32_t> size() const {
        std::lock_guard lock(mutex_);
        return {width_, height_};
    }

    [[nodiscard]] int sample_count() const {
        std::lock_guard lock(mutex_);
        return sample_count_;
    }

    bool acquire_frame(
        std::uint64_t previous_generation,
        CyclesBridgeFrameView& view,
        std::string&) {
        std::lock_guard lock(mutex_);
        view.width = width_;
        view.height = height_;
        view.generation = generation_;
        view.sample_count = static_cast<std::uint32_t>(std::max(0, sample_count_));
        view.pixel_format = CYCLES_BRIDGE_PIXEL_FORMAT_RGBA16_FLOAT;
        view.pixel_byte_count = 0U;
        view.token = 0U;
        view.pixels = nullptr;
        view.flags = 0U;
        FrameSlot* slot = published_frame_locked();
        if (slot == nullptr) {
            return true;
        }
        view.flags |= CYCLES_BRIDGE_FRAME_READY;
        view.sample_count = slot->sample_count;
        if (slot->generation == previous_generation) {
            unchanged_poll_count_++;
            return true;
        }
        const std::size_t slot_index = static_cast<std::size_t>(published_slot_);
        slot->readers++;
        active_frame_leases_++;
        peak_frame_leases_ = std::max(peak_frame_leases_, active_frame_leases_);
        view.width = slot->width;
        view.height = slot->height;
        view.generation = slot->generation;
        view.pixel_byte_count = slot->pixels.size() * sizeof(ccl::half4);
        view.token = (slot->generation << 2U) | (slot_index + 1U);
        view.pixels = reinterpret_cast<const std::uint8_t*>(slot->pixels.data());
        view.flags |= CYCLES_BRIDGE_FRAME_UPDATED;
        return true;
    }

    bool release_frame(std::uint64_t token, std::string& error) {
        std::lock_guard lock(mutex_);
        const std::uint64_t encoded_slot = token & 3U;
        if (encoded_slot == 0U || encoded_slot > slots_.size()) {
            error = "invalid frame lease token";
            return false;
        }
        FrameSlot& slot = slots_[static_cast<std::size_t>(encoded_slot - 1U)];
        if (slot.readers == 0U || slot.generation != (token >> 2U)) {
            error = "expired frame lease token";
            return false;
        }
        slot.readers--;
        active_frame_leases_--;
        return true;
    }

    void fill_diagnostics(CyclesBridgeDiagnostics& diagnostics) const {
        std::lock_guard lock(mutex_);
        diagnostics.frame_generation = generation_;
        diagnostics.width = width_;
        diagnostics.height = height_;
        diagnostics.sample_count = static_cast<std::uint32_t>(std::max(0, sample_count_));
        diagnostics.frame_ready = published_frame_locked() == nullptr ? 0U : 1U;
        diagnostics.active_frame_leases = active_frame_leases_;
        diagnostics.peak_frame_leases = peak_frame_leases_;
        diagnostics.frame_slot_count = static_cast<std::uint32_t>(slots_.size());
        diagnostics.dropped_display_updates = dropped_display_updates_;
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
        diagnostics.frame_pixel_format = CYCLES_BRIDGE_PIXEL_FORMAT_RGBA16_FLOAT;
        diagnostics.cached_raw_pass_mask = 0U;
        diagnostics.cached_denoised_pass_mask = 0U;
        for (const CachedPassFrame& cached : pass_cache_) {
            if (cached.pass >= CYCLES_BRIDGE_PASS_COUNT) {
                continue;
            }
            if (cached.variant == CYCLES_BRIDGE_FRAME_VARIANT_DENOISED) {
                diagnostics.cached_denoised_pass_mask |= 1ULL << cached.pass;
            } else {
                diagnostics.cached_raw_pass_mask |= 1ULL << cached.pass;
            }
        }
        diagnostics.pass_cache_bytes = pass_cache_bytes_;
        diagnostics.pass_cache_budget_bytes = pass_cache_budget_bytes_;
        diagnostics.pass_cache_entry_count = static_cast<std::uint32_t>(pass_cache_.size());
        diagnostics.pass_cache_eviction_count = pass_cache_eviction_count_;
        diagnostics.pass_cache_hit_count = pass_cache_hit_count_;
        const FrameSlot* published = published_frame_locked();
        diagnostics.active_pass = published == nullptr
            ? active_pass_
            : published->pass;
        diagnostics.active_frame_variant = published == nullptr
            ? active_variant_
            : published->variant;
    }

 private:
    void set_cache_budget_locked(std::uint32_t megabytes) {
        pass_cache_budget_bytes_ = static_cast<std::uint64_t>(
            std::clamp(megabytes, 64U, 4096U)) * 1024U * 1024U;
        while (pass_cache_bytes_ > pass_cache_budget_bytes_ && !pass_cache_.empty()) {
            evict_oldest_cache_entry_locked();
        }
    }

    void evict_oldest_cache_entry_locked() {
        const auto oldest = std::min_element(
            pass_cache_.begin(),
            pass_cache_.end(),
            [](const CachedPassFrame& first, const CachedPassFrame& second) {
                return first.last_access < second.last_access;
            });
        if (oldest == pass_cache_.end()) {
            return;
        }
        pass_cache_bytes_ -= oldest->pixels.size() * sizeof(ccl::half4);
        pass_cache_.erase(oldest);
        pass_cache_eviction_count_++;
    }

    void cache_published_locked() {
        const FrameSlot* slot = published_frame_locked();
        if (slot == nullptr || slot->pixels.empty()) {
            return;
        }
        const std::uint64_t bytes = slot->pixels.size() * sizeof(ccl::half4);
        if (bytes > pass_cache_budget_bytes_) {
            return;
        }
        const auto existing = std::find_if(
            pass_cache_.begin(),
            pass_cache_.end(),
            [slot](const CachedPassFrame& cached) {
                return cached.pass == slot->pass && cached.variant == slot->variant;
            });
        if (existing != pass_cache_.end()) {
            pass_cache_bytes_ -= existing->pixels.size() * sizeof(ccl::half4);
            pass_cache_.erase(existing);
        }
        while (pass_cache_bytes_ + bytes > pass_cache_budget_bytes_
               && !pass_cache_.empty()) {
            evict_oldest_cache_entry_locked();
        }
        CachedPassFrame cached;
        cached.pass = slot->pass;
        cached.variant = slot->variant;
        cached.width = slot->width;
        cached.height = slot->height;
        cached.sample_count = slot->sample_count;
        cached.last_access = ++pass_cache_access_sequence_;
        cached.pixels = slot->pixels;
        pass_cache_bytes_ += bytes;
        pass_cache_.push_back(std::move(cached));
    }

    void restore_cached_locked() {
        const auto cached = std::find_if(
            pass_cache_.begin(),
            pass_cache_.end(),
            [this](const CachedPassFrame& candidate) {
                return candidate.pass == active_pass_
                    && candidate.variant == active_variant_;
            });
        if (cached == pass_cache_.end()) {
            return;
        }
        int restore_slot = -1;
        for (std::size_t index = 0; index < slots_.size(); ++index) {
            if (static_cast<int>(index) != published_slot_ && slots_[index].readers == 0U) {
                restore_slot = static_cast<int>(index);
                break;
            }
        }
        if (restore_slot < 0) {
            return;
        }
        cached->last_access = ++pass_cache_access_sequence_;
        FrameSlot& slot = slots_[static_cast<std::size_t>(restore_slot)];
        slot.pixels = cached->pixels;
        slot.pass = cached->pass;
        slot.variant = cached->variant;
        slot.width = cached->width;
        slot.height = cached->height;
        slot.sample_count = cached->sample_count;
        slot.generation = ++generation_;
        width_ = slot.width;
        height_ = slot.height;
        sample_count_ = static_cast<int>(slot.sample_count);
        published_slot_ = restore_slot;
        last_frame_completed_ = std::chrono::steady_clock::now();
        pass_cache_hit_count_++;
    }

    FrameSlot* published_frame_locked() {
        if (published_slot_ < 0) {
            return nullptr;
        }
        FrameSlot& slot = slots_[static_cast<std::size_t>(published_slot_)];
        return slot.pixels.empty() ? nullptr : &slot;
    }

    const FrameSlot* published_frame_locked() const {
        if (published_slot_ < 0) {
            return nullptr;
        }
        const FrameSlot& slot = slots_[static_cast<std::size_t>(published_slot_)];
        return slot.pixels.empty() ? nullptr : &slot;
    }

    void rebuild_display_luts_locked() {
        for (std::uint32_t bits = 0; bits <= std::numeric_limits<std::uint16_t>::max(); ++bits) {
            const ccl::half encoded(static_cast<std::uint16_t>(bits));
            const float source = ccl::half_to_float(encoded);
            float display = source;
            if (active_pass_ == CYCLES_BRIDGE_PASS_DEPTH) {
                display = std::isfinite(source)
                    ? 1.0F - std::exp(-std::max(0.0F, source) * 8.0F / depth_far_)
                    : 1.0F;
            } else if (active_pass_ == CYCLES_BRIDGE_PASS_NORMAL) {
                display = source * 0.5F + 0.5F;
            } else if (active_pass_ == CYCLES_BRIDGE_PASS_SAMPLE_COUNT) {
                display = source / static_cast<float>(target_samples_);
            } else if (active_pass_ != CYCLES_BRIDGE_PASS_ROUGHNESS) {
                display *= exposure_scale_;
                if (view_transform_ != 1U) {
                    display = linear_to_srgb(display);
                }
                if (!nearly_equal(gamma_, 1.0F, 1.0e-6)) {
                    display = std::pow(std::max(0.0F, display), 1.0F / gamma_);
                }
            }
            display_lut_[bits] = to_unorm(display);
            alpha_lut_[bits] = to_unorm(source);
        }
    }

    void write_compatibility_pixel(std::uint8_t* output, const ccl::half4& pixel) const {
        output[0] = display_lut_[static_cast<std::uint16_t>(pixel.x)];
        output[1] = display_lut_[static_cast<std::uint16_t>(pixel.y)];
        output[2] = display_lut_[static_cast<std::uint16_t>(pixel.z)];
        output[3] = active_pass_ == CYCLES_BRIDGE_PASS_COMBINED
            ? alpha_lut_[static_cast<std::uint16_t>(pixel.w)]
            : 255U;
    }

    mutable std::mutex mutex_;
    std::uint32_t width_ = 0;
    std::uint32_t height_ = 0;
    std::uint64_t generation_ = 0;
    int sample_count_ = 0;
    std::uint32_t active_pass_ = CYCLES_BRIDGE_PASS_COMBINED;
    std::uint32_t active_variant_ = CYCLES_BRIDGE_FRAME_VARIANT_RAW;
    float exposure_scale_ = 1.0F;
    float gamma_ = 1.0F;
    std::uint32_t view_transform_ = 0;
    float depth_far_ = 1.0F;
    int target_samples_ = 1;
    std::array<FrameSlot, kFrameSlotCount> slots_{};
    std::vector<CachedPassFrame> pass_cache_;
    std::uint64_t pass_cache_bytes_ = 0U;
    std::uint64_t pass_cache_budget_bytes_ = 256ULL * 1024ULL * 1024ULL;
    std::uint64_t pass_cache_access_sequence_ = 0U;
    std::uint32_t pass_cache_eviction_count_ = 0U;
    std::uint32_t pass_cache_hit_count_ = 0U;
    std::array<std::uint8_t, 65536> display_lut_{};
    std::array<std::uint8_t, 65536> alpha_lut_{};
    bool display_lut_ready_ = false;
    bool display_update_active_ = false;
    bool zero_next_display_ = true;
    int published_slot_ = -1;
    int writing_slot_ = -1;
    std::uint32_t active_frame_leases_ = 0;
    std::uint32_t peak_frame_leases_ = 0;
    std::uint32_t dropped_display_updates_ = 0;
    std::chrono::steady_clock::time_point display_update_started_{};
    std::uint64_t produced_frame_count_ = 0;
    std::uint64_t active_camera_revision_ = 0;
    std::uint64_t produced_camera_revision_ = 0;
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

class FrameDisplayDriver final : public ccl::DisplayDriver {
 public:
    explicit FrameDisplayDriver(FrameStore& frames) : frames_(frames) {}

    void next_tile_begin() override {}

    bool update_begin(
        const Params& params,
        int texture_width,
        int texture_height) override {
        return frames_.display_update_begin(params, texture_width, texture_height);
    }

    void update_end() override {
        frames_.display_update_end();
    }

    ccl::half4* map_texture_buffer() override {
        return frames_.display_buffer();
    }

    void unmap_texture_buffer() override {}

    void zero() override {
        frames_.zero_display();
    }

    void draw(const Params&) override {}

 private:
    FrameStore& frames_;
};

struct VulkanInteropSnapshot {
    HANDLE memory_handle = nullptr;
    HANDLE ready_semaphore_handle = nullptr;
    HANDLE release_semaphore_handle = nullptr;
    CyclesBridgeVulkanInteropBuffer descriptor{};

    VulkanInteropSnapshot() = default;
    ~VulkanInteropSnapshot() {
        if (memory_handle != nullptr) {
            CloseHandle(memory_handle);
        }
        if (ready_semaphore_handle != nullptr) {
            CloseHandle(ready_semaphore_handle);
        }
        if (release_semaphore_handle != nullptr) {
            CloseHandle(release_semaphore_handle);
        }
    }
    VulkanInteropSnapshot(const VulkanInteropSnapshot&) = delete;
    VulkanInteropSnapshot& operator=(const VulkanInteropSnapshot&) = delete;
    VulkanInteropSnapshot(VulkanInteropSnapshot&& other) noexcept
        : memory_handle(std::exchange(other.memory_handle, nullptr)),
          ready_semaphore_handle(
              std::exchange(other.ready_semaphore_handle, nullptr)),
          release_semaphore_handle(
              std::exchange(other.release_semaphore_handle, nullptr)),
          descriptor(other.descriptor) {}
    VulkanInteropSnapshot& operator=(VulkanInteropSnapshot&& other) noexcept {
        if (this != &other) {
            if (memory_handle != nullptr) {
                CloseHandle(memory_handle);
            }
            if (ready_semaphore_handle != nullptr) {
                CloseHandle(ready_semaphore_handle);
            }
            if (release_semaphore_handle != nullptr) {
                CloseHandle(release_semaphore_handle);
            }
            memory_handle = std::exchange(other.memory_handle, nullptr);
            ready_semaphore_handle =
                std::exchange(other.ready_semaphore_handle, nullptr);
            release_semaphore_handle =
                std::exchange(other.release_semaphore_handle, nullptr);
            descriptor = other.descriptor;
        }
        return *this;
    }
};

enum class VulkanInteropSlotOwner : std::uint8_t {
    FREE,
    WRITING,
    READY,
    ACQUIRED,
};

struct VulkanInteropSlot {
    VulkanInteropSlotOwner owner = VulkanInteropSlotOwner::FREE;
    std::uint64_t generation = 0U;
    std::uint32_t width = 0U;
    std::uint32_t height = 0U;
    std::uint32_t sample_count = 0U;
    std::uint64_t release_wait_value = 0U;
};

using VulkanInteropSlots = std::array<VulkanInteropSlot, 3>;

void refresh_vulkan_interop_slot_flags(
    CyclesBridgeVulkanInteropState& state,
    const VulkanInteropSlots& slots,
    std::uint32_t slot_count) {
    state.flags &= ~(
        CYCLES_BRIDGE_VULKAN_INTEROP_FRAME_READY
        | CYCLES_BRIDGE_VULKAN_INTEROP_FRAME_ACQUIRED);
    state.ready_slot_count = 0U;
    for (std::uint32_t index = 0; index < slot_count; ++index) {
        if (slots[index].owner == VulkanInteropSlotOwner::READY) {
            state.flags |= CYCLES_BRIDGE_VULKAN_INTEROP_FRAME_READY;
            state.ready_slot_count++;
        } else if (slots[index].owner == VulkanInteropSlotOwner::ACQUIRED) {
            state.flags |= CYCLES_BRIDGE_VULKAN_INTEROP_FRAME_ACQUIRED;
        }
    }
}

class VulkanInteropDisplayDriver final : public ccl::DisplayDriver {
 public:
    VulkanInteropDisplayDriver(
        VulkanInteropSnapshot&& snapshot,
        FrameStore& frames,
        CyclesBridgeVulkanInteropState& state,
        VulkanInteropSlots& slots,
        std::mutex& state_mutex,
        std::condition_variable& state_changed,
        bool& stopping,
        std::uint64_t& configured_camera_revision,
        std::uint64_t& produced_camera_revision)
        : snapshot_(std::move(snapshot)),
          frames_(frames),
          state_(state),
          slots_(slots),
          state_mutex_(state_mutex),
          state_changed_(state_changed),
          stopping_(stopping),
          configured_camera_revision_(configured_camera_revision),
          produced_camera_revision_(produced_camera_revision) {}

    ~VulkanInteropDisplayDriver() override {
        std::unique_lock lock(state_mutex_);
        state_changed_.wait(lock, [this] {
            if (stopping_) {
                return true;
            }
            return std::none_of(slots_.begin(), slots_.end(), [](const auto& slot) {
                return slot.owner == VulkanInteropSlotOwner::ACQUIRED;
            });
        });
        state_.flags = 0U;
    }

    void next_tile_begin() override {}

    bool update_begin(
        const Params& params,
        int texture_width,
        int texture_height) override {
        if (params.full_size.x <= 0 || params.full_size.y <= 0
            || texture_width <= 0 || texture_height <= 0) {
            return false;
        }
        const std::uint64_t width = static_cast<std::uint32_t>(params.full_size.x);
        const std::uint64_t height = static_cast<std::uint32_t>(params.full_size.y);
        compatible_ = width <= std::numeric_limits<std::uint64_t>::max() / height
            && width * height
                <= snapshot_.descriptor.slot_stride_bytes / sizeof(ccl::half4);
        current_width_ = static_cast<std::uint32_t>(width);
        current_height_ = static_cast<std::uint32_t>(height);
        current_slot_ = -1;
        if (compatible_) {
            std::unique_lock lock(state_mutex_);
            const bool has_free_slot = [this] {
                return std::any_of(
                    slots_.begin(),
                    slots_.begin() + snapshot_.descriptor.slot_count,
                    [](const auto& slot) {
                        return slot.owner == VulkanInteropSlotOwner::FREE;
                    });
            }();
            if (!has_free_slot) {
                state_.producer_wait_count++;
            }
            state_changed_.wait(lock, [this] {
                return stopping_ || std::any_of(
                    slots_.begin(),
                    slots_.begin() + snapshot_.descriptor.slot_count,
                    [](const auto& slot) {
                        return slot.owner == VulkanInteropSlotOwner::FREE;
                    });
            });
            if (stopping_) {
                return false;
            }
            const auto free_slot = std::find_if(
                slots_.begin(),
                slots_.begin() + snapshot_.descriptor.slot_count,
                [](const auto& slot) {
                    return slot.owner == VulkanInteropSlotOwner::FREE;
                });
            current_slot_ = static_cast<int>(std::distance(slots_.begin(), free_slot));
            free_slot->owner = VulkanInteropSlotOwner::WRITING;
        }
        if (!frames_.display_update_begin(params, texture_width, texture_height)) {
            release_writing_slot();
            return false;
        }
        used_interop_ = false;
        update_started_ = std::chrono::steady_clock::now();
        return true;
    }

    void update_end() override {
        if (!used_interop_) {
            release_writing_slot();
            frames_.display_update_end();
            return;
        }
        frames_.display_update_cancel();
        const std::uint32_t elapsed = elapsed_micros(
            update_started_, std::chrono::steady_clock::now());
        std::lock_guard lock(state_mutex_);
        state_.flags |= CYCLES_BRIDGE_VULKAN_INTEROP_ACTIVE;
        state_.flags |= CYCLES_BRIDGE_VULKAN_INTEROP_FRAME_READY;
        state_.generation++;
        state_.completed_frame_count++;
        state_.width = current_width_;
        state_.height = current_height_;
        state_.last_sync_micros = elapsed;
        state_.ema_sync_micros = update_ema(state_.ema_sync_micros, elapsed);
        state_.max_sync_micros = std::max(state_.max_sync_micros, elapsed);
        VulkanInteropSlot& slot = slots_[current_slot_];
        slot.owner = VulkanInteropSlotOwner::READY;
        slot.generation = state_.generation;
        slot.width = current_width_;
        slot.height = current_height_;
        slot.sample_count = state_.sample_count;
        state_.slot_index = static_cast<std::uint32_t>(current_slot_);
        state_.ready_slot_count = static_cast<std::uint32_t>(std::count_if(
            slots_.begin(),
            slots_.begin() + snapshot_.descriptor.slot_count,
            [](const auto& candidate) {
                return candidate.owner == VulkanInteropSlotOwner::READY;
            }));
        refresh_vulkan_interop_slot_flags(
            state_, slots_, snapshot_.descriptor.slot_count);
        current_slot_ = -1;
        produced_camera_revision_ = configured_camera_revision_;
    }

    ccl::half4* map_texture_buffer() override {
        used_interop_ = false;
        release_writing_slot();
        return frames_.display_buffer();
    }

    void unmap_texture_buffer() override {}

    ccl::GraphicsInteropDevice graphics_interop_get_device() override {
        ccl::GraphicsInteropDevice device;
        if (!compatible_) {
            return device;
        }
        device.type = ccl::GraphicsInteropDevice::VULKAN;
        device.uuid.assign(
            std::begin(snapshot_.descriptor.device_uuid),
            std::end(snapshot_.descriptor.device_uuid));
        return device;
    }

    void graphics_interop_update_buffer() override {
        if (snapshot_.memory_handle != nullptr
            && graphics_interop_buffer_.is_empty()) {
            const auto handle = static_cast<std::int64_t>(
                reinterpret_cast<std::intptr_t>(snapshot_.memory_handle));
            graphics_interop_buffer_.assign(
                ccl::GraphicsInteropDevice::VULKAN,
                handle,
                static_cast<std::size_t>(snapshot_.descriptor.allocation_byte_count));
            snapshot_.memory_handle = nullptr;
            graphics_interop_buffer_.assign_timeline_semaphores(
                static_cast<std::int64_t>(reinterpret_cast<std::intptr_t>(
                    snapshot_.ready_semaphore_handle)),
                static_cast<std::int64_t>(reinterpret_cast<std::intptr_t>(
                    snapshot_.release_semaphore_handle)));
            snapshot_.ready_semaphore_handle = nullptr;
            snapshot_.release_semaphore_handle = nullptr;
        }
        used_interop_ = !graphics_interop_buffer_.is_empty() && current_slot_ >= 0;
        if (used_interop_) {
            const std::size_t stride = snapshot_.descriptor.slot_stride_bytes;
            graphics_interop_buffer_.set_range(
                static_cast<std::size_t>(current_slot_) * stride,
                stride);
            graphics_interop_buffer_.set_timeline_values(
                state_.generation + 1U,
                slots_[current_slot_].release_wait_value);
        }
    }

    void zero() override {
        graphics_interop_buffer_.zero();
        frames_.zero_display();
    }

    void draw(const Params&) override {}

 private:
    void release_writing_slot() {
        if (current_slot_ < 0) {
            return;
        }
        {
            std::lock_guard lock(state_mutex_);
            VulkanInteropSlot& slot = slots_[current_slot_];
            if (slot.owner == VulkanInteropSlotOwner::WRITING) {
                const std::uint64_t release_wait_value = slot.release_wait_value;
                slot = {};
                slot.release_wait_value = release_wait_value;
            }
        }
        current_slot_ = -1;
        state_changed_.notify_all();
    }

    VulkanInteropSnapshot snapshot_;
    FrameStore& frames_;
    CyclesBridgeVulkanInteropState& state_;
    VulkanInteropSlots& slots_;
    std::mutex& state_mutex_;
    std::condition_variable& state_changed_;
    bool& stopping_;
    std::uint64_t& configured_camera_revision_;
    std::uint64_t& produced_camera_revision_;
    std::chrono::steady_clock::time_point update_started_{};
    bool compatible_ = false;
    bool used_interop_ = false;
    std::uint32_t current_width_ = 0U;
    std::uint32_t current_height_ = 0U;
    int current_slot_ = -1;
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

void configure_background(
    ccl::Scene* scene,
    const CyclesBridgeRenderSettings& settings) {
    auto graph = ccl::make_unique<ccl::ShaderGraph>();
    ccl::SkyTextureNode* sky = graph->create_node<ccl::SkyTextureNode>();
    sky->set_sky_type(ccl::NODE_SKY_MULTIPLE_SCATTERING);
    sky->set_sun_disc(settings.atmosphere_sun_disc != 0U);
    sky->set_sun_size(settings.atmosphere_sun_size_degrees * kDegreesToRadians);
    sky->set_sun_intensity(settings.atmosphere_sun_intensity);
    sky->set_sun_elevation(
        settings.atmosphere_sun_elevation_degrees * kDegreesToRadians);
    sky->set_sun_rotation(
        settings.atmosphere_sun_rotation_degrees * kDegreesToRadians);
    sky->set_altitude(settings.atmosphere_altitude_meters);
    sky->set_air_density(settings.atmosphere_air_density);
    sky->set_aerosol_density(settings.atmosphere_aerosol_density);
    sky->set_ozone_density(settings.atmosphere_ozone_density);

    ccl::BackgroundNode* background = graph->create_node<ccl::BackgroundNode>();
    background->set_strength(1.0F);
    graph->connect(sky->output("Color"), background->input("Color"));
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

void build_scene(
    ccl::Scene* scene,
    const SceneRequest& request,
    const CyclesBridgeRenderSettings& settings,
    SceneRuntime& runtime) {
    runtime.clear();
    runtime.resources = request.resources;
    configure_background(scene, settings);
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
    const CameraRequest& camera_request,
    const CyclesBridgeRenderSettings& settings) {
    ccl::Camera* camera = session.scene->camera;
    camera->set_camera_type(ccl::CAMERA_PERSPECTIVE);
    camera->set_full_width(static_cast<int>(camera_request.render_width));
    camera->set_full_height(static_cast<int>(camera_request.render_height));
    const float aspect = static_cast<float>(camera_request.render_width)
        / static_cast<float>(std::max(1U, camera_request.render_height));
    const float vertical_fov = settings.projection_mode
            == CYCLES_BRIDGE_PROJECTION_PHYSICAL_LENS
        ? 2.0F * std::atan(
            settings.sensor_width_mm / (2.0F * settings.focal_length_mm * aspect))
        : camera_request.camera.vertical_fov_radians;
    camera->set_fov(vertical_fov);
    const float near_clip = settings.camera_clip_near;
    const float requested_far_clip = settings.camera_clip_far > 0.0F
        ? settings.camera_clip_far
        : camera_request.camera.depth_far;
    camera->set_nearclip(near_clip);
    camera->set_farclip(std::max(near_clip + 0.001F, requested_far_clip));
    const float aperture_size = settings.depth_of_field != 0U
        ? (settings.focal_length_mm / 1000.0F) / (2.0F * settings.f_stop)
        : 0.0F;
    camera->set_focaldistance(settings.focus_distance);
    camera->set_aperturesize(aperture_size);
    camera->set_blades(settings.aperture_blades);
    camera->set_bladesrotation(
        settings.aperture_rotation_degrees * 3.14159265358979323846F / 180.0F);
    camera->set_aperture_ratio(settings.aperture_ratio);
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

struct DenoiserSchedule final {
    std::uint32_t selected = 0;
    std::uint32_t effective = 0;
    std::uint32_t start_sample = 0;
    std::uint32_t reason = CYCLES_BRIDGE_DENOISER_SCHEDULE_DISABLED;
};

DenoiserSchedule configure_scene_settings(
    ccl::Scene* scene,
    const ccl::DeviceInfo& device,
    const CyclesBridgeRenderSettings& settings,
    std::uint32_t sampling_state,
    int target_samples) {
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
    integrator->set_sampling_pattern(
        static_cast<ccl::SamplingPattern>(settings.sampling_pattern));
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
    DenoiserSchedule denoiser_schedule{};
    denoiser_schedule.selected = effective_denoiser;
    if (effective_denoiser == 0U) {
        denoiser_schedule.reason = CYCLES_BRIDGE_DENOISER_SCHEDULE_DISABLED;
    } else if (settings.active_pass != CYCLES_BRIDGE_PASS_COMBINED) {
        denoiser_schedule.reason = CYCLES_BRIDGE_DENOISER_SCHEDULE_DEBUG_PASS;
    } else if (sampling_state == CYCLES_BRIDGE_SAMPLING_STILL) {
        denoiser_schedule.effective = effective_denoiser;
        denoiser_schedule.start_sample = std::min(
            settings.denoiser_start_sample,
            static_cast<std::uint32_t>(std::max(1, target_samples)));
        denoiser_schedule.reason = CYCLES_BRIDGE_DENOISER_SCHEDULE_STILL;
    } else if (sampling_state == CYCLES_BRIDGE_SAMPLING_SETTLING) {
        denoiser_schedule.reason = CYCLES_BRIDGE_DENOISER_SCHEDULE_SETTLING;
    } else {
        denoiser_schedule.reason = CYCLES_BRIDGE_DENOISER_SCHEDULE_INTERACTIVE;
    }
    const bool denoise_active = denoiser_schedule.effective != 0U;
    integrator->set_use_denoise(denoise_active);
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
    return denoiser_schedule;
}

void create_output_passes(ccl::Scene* scene, std::uint64_t registered_pass_mask) {
    registered_pass_mask |= 1ULL << CYCLES_BRIDGE_PASS_COMBINED;
    for (std::uint32_t pass = 0; pass < CYCLES_BRIDGE_PASS_COUNT; ++pass) {
        if ((registered_pass_mask & (1ULL << pass)) == 0U) {
            continue;
        }
        ccl::Pass* output = scene->create_node<ccl::Pass>();
        output->set_name(ccl::ustring(pass_name(pass)));
        output->set_type(pass_type(pass));
    }
}

}  // namespace

class CyclesEngine::Impl final {
 public:
    Impl() {
        initialize_cycles_runtime();
        color_management_ = std::make_unique<ColorManagement>();
        devices_ = enumerate_devices();
        if (devices_.empty()) {
            throw std::runtime_error("Cycles reported no OptiX, CUDA, or CPU devices");
        }
        requested_settings_ = default_settings();
        selected_device_ = devices_.front();
        selected_device_uuid_ = query_cuda_device_uuid(selected_device_);
        state_ = "waiting-scene";
        worker_ = std::thread([this] { worker_main(); });
    }

    ~Impl() {
        {
            std::lock_guard lock(interop_mutex_);
            interop_stopping_ = true;
        }
        interop_changed_.notify_all();
        {
            std::lock_guard lock(request_mutex_);
            stopping_ = true;
        }
        request_changed_.notify_all();
        if (worker_.joinable()) {
            worker_.join();
        }
        std::string ignored;
        unbind_vulkan_interop_buffer(ignored);
    }

    bool bind_vulkan_interop_buffer(
        const CyclesBridgeVulkanInteropBuffer& descriptor,
        std::uint64_t memory_handle,
        std::uint64_t ready_semaphore_handle,
        std::uint64_t release_semaphore_handle,
        std::string& error) {
        {
            std::lock_guard lock(state_mutex_);
            if (!selected_device_uuid_.has_value()) {
                error = "selected Cycles device has no CUDA UUID";
                return false;
            }
            if (std::memcmp(
                    descriptor.device_uuid,
                    selected_device_uuid_->data(),
                    selected_device_uuid_->size()) != 0) {
                error = "Vulkan and Cycles device UUIDs do not match";
                return false;
            }
        }
        std::lock_guard lock(interop_mutex_);
        if (interop_memory_handle_ != nullptr
            || interop_ready_semaphore_handle_ != nullptr
            || interop_release_semaphore_handle_ != nullptr
            || (interop_state_.flags
                & (CYCLES_BRIDGE_VULKAN_INTEROP_BOUND
                   | CYCLES_BRIDGE_VULKAN_INTEROP_ACTIVE
                   | CYCLES_BRIDGE_VULKAN_INTEROP_SESSION_ATTACHED)) != 0U) {
            error = "Vulkan interop buffer is already bound";
            return false;
        }
        if (interop_memory_handle_ != nullptr) {
            CloseHandle(interop_memory_handle_);
        }
        interop_memory_handle_ = reinterpret_cast<HANDLE>(
            static_cast<std::uintptr_t>(memory_handle));
        interop_ready_semaphore_handle_ = reinterpret_cast<HANDLE>(
            static_cast<std::uintptr_t>(ready_semaphore_handle));
        interop_release_semaphore_handle_ = reinterpret_cast<HANDLE>(
            static_cast<std::uintptr_t>(release_semaphore_handle));
        interop_descriptor_ = descriptor;
        interop_descriptor_.memory_handle = 0U;
        interop_descriptor_.ready_semaphore_handle = 0U;
        interop_descriptor_.release_semaphore_handle = 0U;
        interop_state_ = {};
        interop_state_.struct_size = sizeof(interop_state_);
        interop_state_.struct_version = 1U;
        interop_state_.flags = CYCLES_BRIDGE_VULKAN_INTEROP_BOUND
            | CYCLES_BRIDGE_VULKAN_INTEROP_TIMELINE_SYNC;
        interop_state_.width = descriptor.width;
        interop_state_.height = descriptor.height;
        interop_state_.slot_count = descriptor.slot_count;
        interop_slots_ = {};
        return true;
    }

    bool unbind_vulkan_interop_buffer(std::string& error) {
        std::lock_guard lock(interop_mutex_);
        if ((interop_state_.flags
             & (CYCLES_BRIDGE_VULKAN_INTEROP_ACTIVE
                | CYCLES_BRIDGE_VULKAN_INTEROP_SESSION_ATTACHED)) != 0U) {
            error = "Vulkan interop is active; destroy the renderer before releasing Vulkan memory";
            return false;
        }
        if (interop_memory_handle_ != nullptr) {
            CloseHandle(interop_memory_handle_);
            interop_memory_handle_ = nullptr;
        }
        if (interop_ready_semaphore_handle_ != nullptr) {
            CloseHandle(interop_ready_semaphore_handle_);
            interop_ready_semaphore_handle_ = nullptr;
        }
        if (interop_release_semaphore_handle_ != nullptr) {
            CloseHandle(interop_release_semaphore_handle_);
            interop_release_semaphore_handle_ = nullptr;
        }
        interop_descriptor_ = {};
        interop_state_ = {};
        interop_slots_ = {};
        return true;
    }

    void query_vulkan_interop_state(
        CyclesBridgeVulkanInteropState& state) const {
        std::lock_guard lock(interop_mutex_);
        const std::uint32_t struct_size = state.struct_size;
        const std::uint32_t struct_version = state.struct_version;
        state = interop_state_;
        state.struct_size = struct_size;
        state.struct_version = struct_version;
    }

    void acquire_vulkan_interop_frame(
        std::uint64_t previous_generation,
        CyclesBridgeVulkanInteropState& state) {
        bool released_stale_slots = false;
        {
            std::lock_guard lock(interop_mutex_);
            VulkanInteropSlot* selected = nullptr;
            std::uint32_t selected_index = 0U;
            for (std::uint32_t index = 0; index < interop_descriptor_.slot_count; ++index) {
                VulkanInteropSlot& slot = interop_slots_[index];
                if (slot.owner == VulkanInteropSlotOwner::READY
                    && slot.generation > previous_generation
                    && (selected == nullptr || slot.generation > selected->generation)) {
                    selected = &slot;
                    selected_index = index;
                }
            }
            if (selected != nullptr) {
                for (std::uint32_t index = 0; index < interop_descriptor_.slot_count; ++index) {
                    VulkanInteropSlot& slot = interop_slots_[index];
                    if (&slot != selected
                        && slot.owner == VulkanInteropSlotOwner::READY
                        && slot.generation < selected->generation) {
                        const std::uint64_t release_wait_value = selected->generation;
                        slot = {};
                        slot.release_wait_value = release_wait_value;
                        released_stale_slots = true;
                    }
                }
                selected->owner = VulkanInteropSlotOwner::ACQUIRED;
                interop_state_.width = selected->width;
                interop_state_.height = selected->height;
                interop_state_.sample_count = selected->sample_count;
                interop_state_.generation = selected->generation;
                interop_state_.slot_index = selected_index;
            }
            refresh_vulkan_interop_slot_flags(
                interop_state_, interop_slots_, interop_descriptor_.slot_count);
            const std::uint32_t struct_size = state.struct_size;
            const std::uint32_t struct_version = state.struct_version;
            state = interop_state_;
            state.struct_size = struct_size;
            state.struct_version = struct_version;
        }
        if (released_stale_slots) {
            interop_changed_.notify_all();
        }
    }

    bool release_vulkan_interop_frame(
        std::uint64_t generation,
        std::string& error) {
        {
            std::lock_guard lock(interop_mutex_);
            const auto acquired = std::find_if(
                interop_slots_.begin(),
                interop_slots_.begin() + interop_descriptor_.slot_count,
                [generation](const auto& slot) {
                    return slot.owner == VulkanInteropSlotOwner::ACQUIRED
                        && slot.generation == generation;
                });
            if (acquired == interop_slots_.begin() + interop_descriptor_.slot_count) {
                error = "Vulkan interop frame token is not acquired";
                return false;
            }
            acquired->owner = VulkanInteropSlotOwner::FREE;
            acquired->release_wait_value = generation;
            acquired->generation = 0U;
            acquired->width = 0U;
            acquired->height = 0U;
            acquired->sample_count = 0U;
            refresh_vulkan_interop_slot_flags(
                interop_state_, interop_slots_, interop_descriptor_.slot_count);
        }
        interop_changed_.notify_all();
        return true;
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
                requested_camera_->preserve_pass_cache = false;
                requested_camera_->revision = ++camera_revision_;
                last_camera_change_ = std::chrono::steady_clock::now();
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
        bool pass_only_change = false;
        frames_.set_cache_budget(settings.pass_cache_megabytes);
        {
            std::lock_guard lock(request_mutex_);
            if (stopping_) {
                error = "Cycles worker is stopping";
                return false;
            }
            display_only_no_op = settings_revision_ > 0
                && same_render_settings(settings, requested_settings_);
            if (!display_only_no_op) {
                const bool pass_changed = settings_revision_ > 0
                    && settings.active_pass != requested_settings_.active_pass;
                pass_only_change = pass_changed
                    && same_render_settings_except_pass(settings, requested_settings_);
                const bool cache_budget_only = settings_revision_ > 0
                    && settings.pass_cache_megabytes
                        != requested_settings_.pass_cache_megabytes
                    && same_render_settings_except_cache_budget(settings, requested_settings_);
                const bool denoiser_topology_changed = settings_revision_ > 0
                    && (settings.denoiser_mode != requested_settings_.denoiser_mode
                        || settings.denoiser_input != requested_settings_.denoiser_input
                        || settings.denoiser_use_gpu
                            != requested_settings_.denoiser_use_gpu);
                if (settings.device_policy != requested_settings_.device_policy
                    || denoiser_topology_changed) {
                    reset_level = CYCLES_BRIDGE_RESET_SESSION;
                } else if (settings.resolution_mode != requested_settings_.resolution_mode
                           || settings.render_width != requested_settings_.render_width
                           || settings.render_height != requested_settings_.render_height
                           || settings.resolution_percentage
                               != requested_settings_.resolution_percentage
                           || settings.dynamic_resolution
                               != requested_settings_.dynamic_resolution
                           || settings.interactive_resolution_percentage
                               != requested_settings_.interactive_resolution_percentage) {
                    reset_level = CYCLES_BRIDGE_RESET_BUFFER;
                } else if (pass_changed) {
                    reset_level = CYCLES_BRIDGE_RESET_ACCUMULATION;
                } else if (cache_budget_only) {
                    reset_level = CYCLES_BRIDGE_RESET_NONE;
                } else if (!same_render_settings(settings, requested_settings_)) {
                    reset_level = CYCLES_BRIDGE_RESET_ACCUMULATION;
                }
                requested_settings_ = settings;
                if (requested_settings_.revision <= settings_revision_) {
                    requested_settings_.revision = settings_revision_ + 1U;
                }
                settings_revision_ = requested_settings_.revision;
                requested_reset_level_ = reset_level;
                requested_pass_only_change_ = pass_only_change;
                if (requested_camera_) {
                    std::tie(requested_camera_->render_width, requested_camera_->render_height) =
                        render_dimensions(
                            requested_camera_->camera.viewport_width,
                            requested_camera_->camera.viewport_height,
                            requested_settings_,
                            CYCLES_BRIDGE_SAMPLING_INTERACTIVE);
                    requested_camera_->sample_count =
                        static_cast<int>(requested_settings_.interactive_samples);
                    requested_camera_->sampling_state =
                        CYCLES_BRIDGE_SAMPLING_INTERACTIVE;
                    requested_camera_->preserve_pass_cache = pass_only_change;
                    requested_camera_->revision = ++camera_revision_;
                    last_camera_change_ = std::chrono::steady_clock::now();
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
        } else if (reset_level >= CYCLES_BRIDGE_RESET_ACCUMULATION
                   && !pass_only_change) {
            frames_.invalidate_pass_cache();
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
        capabilities.color_transform_mask = color_management_->transform_mask();
        capabilities.color_lut_edge_length = color_management_->lut_edge_length();
        capabilities.color_lut_pixel_format = CYCLES_BRIDGE_PIXEL_FORMAT_RGBA32_FLOAT;
        capabilities.color_config_state = color_management_->state();
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

    [[nodiscard]] std::string color_management_info() const {
        return color_management_->info();
    }

    bool query_color_lut(
        std::uint32_t view_transform,
        CyclesBridgeColorLutDescriptor& descriptor,
        float* rgba,
        std::uint64_t rgba_capacity,
        std::string& error) const {
        return color_management_->query_lut(
            view_transform, descriptor, rgba, rgba_capacity, error);
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
            diagnostics.settling_remaining_millis =
                settling_remaining_millis_diagnostic_;
            diagnostics.sampling_transition_count =
                sampling_transition_count_diagnostic_;
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
            diagnostics.registered_pass_mask = registered_pass_mask_diagnostic_;
            diagnostics.pass_registry_rebuild_count = pass_registry_rebuild_count_;
            diagnostics.pass_registry_hit_count = pass_registry_hit_count_;
            diagnostics.selected_denoiser = selected_denoiser_;
            diagnostics.denoiser_scheduled = effective_denoiser_ != 0U ? 1U : 0U;
            diagnostics.effective_denoiser_start_sample =
                effective_denoiser_start_sample_;
            diagnostics.denoiser_schedule_reason = denoiser_schedule_reason_;
            diagnostics.denoiser_schedule_run_count = denoiser_schedule_run_count_;
            diagnostics.denoiser_schedule_skip_count = denoiser_schedule_skip_count_;
            diagnostics.sampling_pattern = sampling_pattern_diagnostic_;
            diagnostics.effective_camera_clip_near = camera_clip_near_diagnostic_;
            diagnostics.effective_camera_clip_far = camera_clip_far_diagnostic_;
            diagnostics.projection_mode = projection_mode_diagnostic_;
            diagnostics.vertical_fov_radians = vertical_fov_diagnostic_;
            diagnostics.depth_of_field = depth_of_field_diagnostic_;
            diagnostics.focus_distance = focus_distance_diagnostic_;
            diagnostics.f_stop = f_stop_diagnostic_;
            diagnostics.aperture_size = aperture_size_diagnostic_;
            diagnostics.aperture_blades = aperture_blades_diagnostic_;
            diagnostics.aperture_rotation_radians = aperture_rotation_diagnostic_;
            diagnostics.aperture_ratio = aperture_ratio_diagnostic_;
            if (selected_device_uuid_.has_value()) {
                diagnostics.device_uuid_valid = 1U;
                std::memcpy(
                    diagnostics.device_uuid,
                    selected_device_uuid_->data(),
                    selected_device_uuid_->size());
            }
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

    bool update_camera(const CyclesBridgeCamera& camera, std::string& error) {
        return queue_camera(camera, error);
    }

    bool acquire_frame(
        std::uint64_t previous_generation,
        CyclesBridgeFrameView& frame_view,
        std::string& error) {
        return frames_.acquire_frame(previous_generation, frame_view, error);
    }

    bool release_frame(std::uint64_t token, std::string& error) {
        return frames_.release_frame(token, error);
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
        bool update_sampling_phase = false;
        std::uint32_t sampling_phase = CYCLES_BRIDGE_SAMPLING_IDLE;
        std::uint32_t settling_remaining_millis = 0U;
        {
            std::lock_guard lock(request_mutex_);
            if (!requested_scene_) {
                return true;
            }
            const std::uint32_t current_sampling_state = requested_camera_
                ? requested_camera_->sampling_state
                : CYCLES_BRIDGE_SAMPLING_INTERACTIVE;
            std::tie(request.render_width, request.render_height) = render_dimensions(
                camera.viewport_width,
                camera.viewport_height,
                requested_settings_,
                current_sampling_state);
            if (!requested_camera_
                || !same_camera(
                    *requested_camera_,
                    request,
                    requested_settings_.projection_mode
                        == CYCLES_BRIDGE_PROJECTION_MINECRAFT_FOV,
                    requested_settings_.camera_clip_far == 0.0F)) {
                std::tie(request.render_width, request.render_height) = render_dimensions(
                    camera.viewport_width,
                    camera.viewport_height,
                    requested_settings_,
                    CYCLES_BRIDGE_SAMPLING_INTERACTIVE);
                request.sample_count =
                    static_cast<int>(requested_settings_.interactive_samples);
                request.sampling_state = CYCLES_BRIDGE_SAMPLING_INTERACTIVE;
                request.revision = ++camera_revision_;
                requested_camera_ = request;
                last_camera_change_ = now;
                changed = true;
                update_sampling_phase = true;
                sampling_phase = CYCLES_BRIDGE_SAMPLING_INTERACTIVE;
                settling_remaining_millis = requested_settings_.stationary_delay_millis;
            } else if (requested_camera_->sampling_state
                           != CYCLES_BRIDGE_SAMPLING_STILL
                       && frames_.produced_camera_revision()
                           == requested_camera_->revision) {
                const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                    now - last_camera_change_);
                const auto delay = std::chrono::milliseconds(
                    requested_settings_.stationary_delay_millis);
                update_sampling_phase = true;
                if (elapsed >= delay) {
                    std::tie(request.render_width, request.render_height) = render_dimensions(
                        camera.viewport_width,
                        camera.viewport_height,
                        requested_settings_,
                        CYCLES_BRIDGE_SAMPLING_STILL);
                    request.sample_count = static_cast<int>(requested_settings_.still_samples);
                    request.sampling_state = CYCLES_BRIDGE_SAMPLING_STILL;
                    request.preserve_pass_cache = true;
                    request.revision = ++camera_revision_;
                    requested_camera_ = request;
                    changed = true;
                    sampling_phase = CYCLES_BRIDGE_SAMPLING_STILL;
                } else {
                    sampling_phase = CYCLES_BRIDGE_SAMPLING_SETTLING;
                    settling_remaining_millis = static_cast<std::uint32_t>(
                        std::max<std::int64_t>(0, (delay - elapsed).count()));
                }
            }
        }
        if (update_sampling_phase) {
            set_sampling_phase(sampling_phase, settling_remaining_millis);
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

    void set_sampling_phase(
        std::uint32_t sampling_state,
        std::uint32_t settling_remaining_millis) {
        std::lock_guard lock(state_mutex_);
        if (sampling_state_diagnostic_ != sampling_state) {
            sampling_transition_count_diagnostic_++;
        }
        sampling_state_diagnostic_ = sampling_state;
        settling_remaining_millis_diagnostic_ = settling_remaining_millis;
        if (sampling_state == CYCLES_BRIDGE_SAMPLING_SETTLING
            && selected_denoiser_ != 0U
            && effective_denoiser_ == 0U
            && active_pass_diagnostic_ == CYCLES_BRIDGE_PASS_COMBINED) {
            denoiser_schedule_reason_ = CYCLES_BRIDGE_DENOISER_SCHEDULE_SETTLING;
        }
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
        if (selected_device_.type != device.type
            || selected_device_.num != device.num
            || selected_device_.id != device.id) {
            selected_device_uuid_ = query_cuda_device_uuid(device);
        }
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

    ccl::SessionParams make_session_params(
        const ccl::DeviceInfo& device,
        bool use_graphics_interop) const {
        ccl::SessionParams params;
        params.device = device;
        params.denoise_device = device;
        params.headless = !use_graphics_interop;
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
        std::uint64_t registered_pass_mask,
        ccl::SessionParams& session_params,
        SceneRuntime& runtime) {
        VulkanInteropSnapshot interop_snapshot;
        {
            std::lock_guard lock(interop_mutex_);
            const auto device_uuid = query_cuda_device_uuid(device);
            const bool compatible_device = device_uuid.has_value()
                && std::memcmp(
                    interop_descriptor_.device_uuid,
                    device_uuid->data(),
                    device_uuid->size()) == 0;
            if (interop_memory_handle_ != nullptr && compatible_device) {
                interop_snapshot.memory_handle = interop_memory_handle_;
                interop_snapshot.ready_semaphore_handle =
                    interop_ready_semaphore_handle_;
                interop_snapshot.release_semaphore_handle =
                    interop_release_semaphore_handle_;
                interop_snapshot.descriptor = interop_descriptor_;
                interop_memory_handle_ = nullptr;
                interop_ready_semaphore_handle_ = nullptr;
                interop_release_semaphore_handle_ = nullptr;
            }
        }
        const bool use_graphics_interop = interop_snapshot.memory_handle != nullptr;
        session_params = make_session_params(device, use_graphics_interop);
        ccl::SceneParams scene_params;
        scene_params.background = false;
        auto session = ccl::make_unique<ccl::Session>(session_params, scene_params);
        if (use_graphics_interop) {
            session->set_display_driver(
                ccl::make_unique<VulkanInteropDisplayDriver>(
                    std::move(interop_snapshot),
                    frames_,
                    interop_state_,
                    interop_slots_,
                    interop_mutex_,
                    interop_changed_,
                    interop_stopping_,
                    interop_configured_camera_revision_,
                    interop_produced_camera_revision_));
            {
                std::lock_guard lock(interop_mutex_);
                interop_state_.flags |=
                    CYCLES_BRIDGE_VULKAN_INTEROP_SESSION_ATTACHED;
            }
        } else {
            session->set_display_driver(ccl::make_unique<FrameDisplayDriver>(frames_));
        }
        create_output_passes(session->scene.get(), registered_pass_mask);
        build_scene(session->scene.get(), scene_request, settings, runtime);
        const DenoiserSchedule denoiser_schedule = configure_scene_settings(
            session->scene.get(), device, settings,
            CYCLES_BRIDGE_SAMPLING_INTERACTIVE,
            static_cast<int>(settings.interactive_samples));
        {
            std::lock_guard lock(state_mutex_);
            selected_denoiser_ = denoiser_schedule.selected;
            effective_denoiser_ = denoiser_schedule.effective;
            effective_denoiser_start_sample_ = denoiser_schedule.start_sample;
            denoiser_schedule_reason_ = denoiser_schedule.reason;
        }
        return session;
    }

    bool rebuild_session(
        ccl::unique_ptr<ccl::Session>& session,
        ccl::SessionParams& params,
        const SceneRequest& scene_request,
        const CyclesBridgeRenderSettings& settings,
        std::uint64_t registered_pass_mask,
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
                session = create_session(
                    device,
                    scene_request,
                    settings,
                    registered_pass_mask,
                    params,
                    runtime);
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
        DenoiserSchedule denoiser_schedule{};
        {
            const ccl::thread_scoped_lock scene_lock(session.scene->mutex);
            buffer = configure_camera(session, scene_request, camera_request, settings);
            denoiser_schedule = configure_scene_settings(
                session.scene.get(), params.device, settings,
                camera_request.sampling_state,
                camera_request.sample_count);
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
            render_params.samples,
            denoiser_schedule.effective != 0U,
            camera_request.revision);
        frames_.set_sample_count(0);
        sampling_target_ = render_params.samples;
        sampling_measure_count_ = 0;
        sampling_rate_ = 0.0F;
        sampling_measure_time_ = std::chrono::steady_clock::now();
        {
            std::lock_guard lock(state_mutex_);
            selected_denoiser_ = denoiser_schedule.selected;
            effective_denoiser_ = denoiser_schedule.effective;
            effective_denoiser_start_sample_ = denoiser_schedule.start_sample;
            denoiser_schedule_reason_ = denoiser_schedule.reason;
            if (denoiser_schedule.selected != 0U) {
                if (denoiser_schedule.effective != 0U) {
                    denoiser_schedule_run_count_++;
                } else {
                    denoiser_schedule_skip_count_++;
                }
            }
            target_sample_count_diagnostic_ =
                static_cast<std::uint32_t>(render_params.samples);
            camera_clip_near_diagnostic_ = settings.camera_clip_near;
            camera_clip_far_diagnostic_ = std::max(
                settings.camera_clip_near + 0.001F,
                settings.camera_clip_far > 0.0F
                    ? settings.camera_clip_far
                    : camera_request.camera.depth_far);
            projection_mode_diagnostic_ = settings.projection_mode;
            const float aspect = static_cast<float>(camera_request.render_width)
                / static_cast<float>(std::max(1U, camera_request.render_height));
            vertical_fov_diagnostic_ = settings.projection_mode
                    == CYCLES_BRIDGE_PROJECTION_PHYSICAL_LENS
                ? 2.0F * std::atan(
                    settings.sensor_width_mm
                    / (2.0F * settings.focal_length_mm * aspect))
                : camera_request.camera.vertical_fov_radians;
            depth_of_field_diagnostic_ = settings.depth_of_field;
            focus_distance_diagnostic_ = settings.focus_distance;
            f_stop_diagnostic_ = settings.f_stop;
            aperture_size_diagnostic_ = settings.depth_of_field != 0U
                ? (settings.focal_length_mm / 1000.0F) / (2.0F * settings.f_stop)
                : 0.0F;
            aperture_blades_diagnostic_ = settings.aperture_blades;
            aperture_rotation_diagnostic_ =
                settings.aperture_rotation_degrees * 3.14159265358979323846F / 180.0F;
            aperture_ratio_diagnostic_ = settings.aperture_ratio;
            if (sampling_state_diagnostic_ != camera_request.sampling_state) {
                sampling_transition_count_diagnostic_++;
            }
            sampling_state_diagnostic_ = camera_request.sampling_state;
            settling_remaining_millis_diagnostic_ =
                camera_request.sampling_state == CYCLES_BRIDGE_SAMPLING_INTERACTIVE
                ? settings.stationary_delay_millis
                : 0U;
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

    [[nodiscard]] std::uint64_t produced_camera_revision() const {
        std::lock_guard lock(interop_mutex_);
        if ((interop_state_.flags & CYCLES_BRIDGE_VULKAN_INTEROP_ACTIVE) != 0U) {
            return interop_produced_camera_revision_;
        }
        return frames_.produced_camera_revision();
    }

    void update_sampling_progress(ccl::Session& session) {
        const int actual = std::clamp(
            session.progress.get_current_sample(), 0, sampling_target_);
        frames_.set_sample_count(actual);
        {
            std::lock_guard lock(interop_mutex_);
            interop_state_.sample_count = static_cast<std::uint32_t>(actual);
        }

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
        std::uint64_t render_camera_revision = 0;
        std::uint64_t registered_pass_mask = 1ULL << CYCLES_BRIDGE_PASS_COMBINED;
        bool render_in_flight = false;
        std::size_t device_index = 0;

        try {
            while (true) {
                std::shared_ptr<const SceneRequest> requested_scene;
                std::optional<CameraRequest> requested_camera;
                std::uint64_t requested_reset_revision = 0;
                CyclesBridgeRenderSettings requested_settings{};
                std::uint32_t requested_settings_reset = CYCLES_BRIDGE_RESET_NONE;
                bool requested_pass_only_change = false;
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
                    requested_pass_only_change = requested_pass_only_change_;
                }
                observed_scene_revision = requested_scene ? requested_scene->revision : 0;
                observed_camera_revision = requested_camera ? requested_camera->revision : 0;

                if (session) {
                    update_sampling_progress(*session);
                }

                bool pass_only_settings_update = false;
                if (requested_settings.revision != active_settings_revision) {
                    const bool pass_changed = requested_settings.active_pass
                        != active_settings.active_pass;
                    const std::uint64_t requested_pass_bit =
                        1ULL << requested_settings.active_pass;
                    const bool pass_registration_required = pass_changed
                        && (registered_pass_mask & requested_pass_bit) == 0U;
                    if (pass_registration_required) {
                        registered_pass_mask |= requested_pass_bit;
                    }
                    if (session && pass_changed && !pass_registration_required) {
                        std::lock_guard lock(state_mutex_);
                        pass_registry_hit_count_++;
                    }
                    pass_only_settings_update = requested_pass_only_change;
                    if (session && (requested_settings_reset == CYCLES_BRIDGE_RESET_SESSION
                                    || pass_changed)) {
                        session->cancel(true);
                        session.reset();
                        scene_runtime.clear();
                        active_scene.reset();
                        active_scene_revision = 0;
                        device_index = 0;
                        if (pass_registration_required) {
                            std::lock_guard lock(state_mutex_);
                            pass_registry_rebuild_count_++;
                        }
                    } else if (session && render_in_flight) {
                        session->cancel(true);
                    }
                    if (requested_settings_reset >= CYCLES_BRIDGE_RESET_ACCUMULATION) {
                        active_camera_revision = 0;
                        render_in_flight = false;
                        if (!requested_pass_only_change) {
                            frames_.clear();
                        }
                    }
                    active_settings = requested_settings;
                    active_settings_revision = requested_settings.revision;
                    {
                        std::lock_guard lock(state_mutex_);
                        active_settings_revision_diagnostic_ = active_settings_revision;
                        active_pass_diagnostic_ = active_settings.active_pass;
                        sampling_pattern_diagnostic_ = active_settings.sampling_pattern;
                        registered_pass_mask_diagnostic_ = registered_pass_mask;
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
                           && produced_camera_revision()
                               == render_camera_revision) {
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
                    if (!pass_only_settings_update) {
                        frames_.invalidate_pass_cache();
                    }
                    const bool resources_changed = !session
                        || !active_scene
                        || scene_runtime.resources != requested_scene->resources;
                    if (resources_changed) {
                        if (!rebuild_session(
                                session,
                                session_params,
                                *requested_scene,
                                active_settings,
                                registered_pass_mask,
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
                    if (!requested_camera->preserve_pass_cache) {
                        frames_.invalidate_pass_cache();
                    }
                    render_camera_revision = requested_camera->revision;
                    {
                        std::lock_guard lock(interop_mutex_);
                        interop_configured_camera_revision_ = render_camera_revision;
                    }
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
    bool requested_pass_only_change_ = false;
    CyclesBridgeRenderSettings requested_settings_{};
    std::shared_ptr<const SceneResourcesData> staging_resources_;
    std::unordered_map<std::int64_t, std::shared_ptr<const SectionRequest>> staging_sections_;
    std::shared_ptr<const SceneRequest> requested_scene_;
    std::optional<CameraRequest> requested_camera_;
    std::chrono::steady_clock::time_point last_camera_change_{};

    mutable std::mutex state_mutex_;
    ccl::DeviceInfo selected_device_;
    std::optional<std::array<std::uint8_t, 16>> selected_device_uuid_;
    std::uint32_t state_code_ = 0;
    std::uint32_t effective_denoiser_ = 0;
    std::uint32_t last_reset_level_ = CYCLES_BRIDGE_RESET_NONE;
    std::uint64_t active_settings_revision_diagnostic_ = 0;
    std::uint32_t active_pass_diagnostic_ = CYCLES_BRIDGE_PASS_COMBINED;
    std::uint32_t target_sample_count_diagnostic_ = 0;
    std::uint32_t sampling_state_diagnostic_ = CYCLES_BRIDGE_SAMPLING_IDLE;
    float sample_rate_diagnostic_ = 0.0F;
    std::uint32_t settling_remaining_millis_diagnostic_ = 0;
    std::uint32_t sampling_transition_count_diagnostic_ = 0;
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
    std::uint64_t registered_pass_mask_diagnostic_ =
        1ULL << CYCLES_BRIDGE_PASS_COMBINED;
    std::uint32_t pass_registry_rebuild_count_ = 0;
    std::uint32_t pass_registry_hit_count_ = 0;
    std::uint32_t selected_denoiser_ = 0;
    std::uint32_t effective_denoiser_start_sample_ = 0;
    std::uint32_t denoiser_schedule_reason_ =
        CYCLES_BRIDGE_DENOISER_SCHEDULE_DISABLED;
    std::uint32_t denoiser_schedule_run_count_ = 0;
    std::uint32_t denoiser_schedule_skip_count_ = 0;
    std::uint32_t sampling_pattern_diagnostic_ =
        CYCLES_BRIDGE_SAMPLING_PATTERN_BLUE_NOISE_FIRST;
    float camera_clip_near_diagnostic_ = 0.05F;
    float camera_clip_far_diagnostic_ = 0.0F;
    std::uint32_t projection_mode_diagnostic_ = CYCLES_BRIDGE_PROJECTION_MINECRAFT_FOV;
    float vertical_fov_diagnostic_ = 0.0F;
    std::uint32_t depth_of_field_diagnostic_ = 0U;
    float focus_distance_diagnostic_ = 10.0F;
    float f_stop_diagnostic_ = 2.8F;
    float aperture_size_diagnostic_ = 0.0F;
    std::uint32_t aperture_blades_diagnostic_ = 0U;
    float aperture_rotation_diagnostic_ = 0.0F;
    float aperture_ratio_diagnostic_ = 1.0F;
    std::string state_;
    std::string terminal_error_;

    int sampling_target_ = 0;
    int sampling_measure_count_ = 0;
    float sampling_rate_ = 0.0F;
    std::chrono::steady_clock::time_point sampling_measure_time_{};

    std::vector<ccl::DeviceInfo> devices_;
    std::unique_ptr<ColorManagement> color_management_;
    FrameStore frames_;
    std::thread worker_;
    mutable std::mutex interop_mutex_;
    std::condition_variable interop_changed_;
    bool interop_stopping_ = false;
    HANDLE interop_memory_handle_ = nullptr;
    HANDLE interop_ready_semaphore_handle_ = nullptr;
    HANDLE interop_release_semaphore_handle_ = nullptr;
    CyclesBridgeVulkanInteropBuffer interop_descriptor_{};
    CyclesBridgeVulkanInteropState interop_state_{};
    VulkanInteropSlots interop_slots_{};
    std::uint64_t interop_configured_camera_revision_ = 0;
    std::uint64_t interop_produced_camera_revision_ = 0;
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

std::string CyclesEngine::color_management_info() const {
    return impl_->color_management_info();
}

bool CyclesEngine::query_color_lut(
    std::uint32_t view_transform,
    CyclesBridgeColorLutDescriptor& descriptor,
    float* rgba,
    std::uint64_t rgba_capacity,
    std::string& error) const {
    return impl_->query_color_lut(
        view_transform, descriptor, rgba, rgba_capacity, error);
}

void CyclesEngine::query_diagnostics(CyclesBridgeDiagnostics& diagnostics) const {
    impl_->query_diagnostics(diagnostics);
}

bool CyclesEngine::bind_vulkan_interop_buffer(
    const CyclesBridgeVulkanInteropBuffer& descriptor,
    std::uint64_t memory_handle,
    std::uint64_t ready_semaphore_handle,
    std::uint64_t release_semaphore_handle,
    std::string& error) {
    return impl_->bind_vulkan_interop_buffer(
        descriptor,
        memory_handle,
        ready_semaphore_handle,
        release_semaphore_handle,
        error);
}

bool CyclesEngine::unbind_vulkan_interop_buffer(std::string& error) {
    return impl_->unbind_vulkan_interop_buffer(error);
}

void CyclesEngine::query_vulkan_interop_state(
    CyclesBridgeVulkanInteropState& state) const {
    impl_->query_vulkan_interop_state(state);
}

void CyclesEngine::acquire_vulkan_interop_frame(
    std::uint64_t previous_generation,
    CyclesBridgeVulkanInteropState& state) {
    impl_->acquire_vulkan_interop_frame(previous_generation, state);
}

bool CyclesEngine::release_vulkan_interop_frame(
    std::uint64_t generation,
    std::string& error) {
    return impl_->release_vulkan_interop_frame(generation, error);
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

bool CyclesEngine::update_camera(
    const CyclesBridgeCamera& camera,
    std::string& error) {
    return impl_->update_camera(camera, error);
}

bool CyclesEngine::acquire_frame(
    std::uint64_t previous_generation,
    CyclesBridgeFrameView& frame_view,
    std::string& error) {
    return impl_->acquire_frame(previous_generation, frame_view, error);
}

bool CyclesEngine::release_frame(std::uint64_t token, std::string& error) {
    return impl_->release_frame(token, error);
}

std::string CyclesEngine::renderer_info() const {
    return impl_->info();
}

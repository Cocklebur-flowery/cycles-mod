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
#include "scene/camera.h"
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
#include "util/path.h"
#include "util/string.h"
#include "util/system.h"
#include "util/transform.h"
#include "util/types.h"
#include "util/unique_ptr.h"

namespace {

using namespace std::chrono_literals;

constexpr std::uint32_t kMaximumRenderWidth = 480;
constexpr std::uint32_t kMaximumRenderHeight = 270;
constexpr int kRenderSamples = 8;
constexpr char kCombinedPass[] = "combined";

struct SceneRequest {
    CyclesBridgeVoxelScene scene{};
    std::vector<std::uint32_t> voxels;
    std::uint64_t revision = 0;
};

struct CameraRequest {
    CyclesBridgeCamera camera{};
    std::uint32_t render_width = 0;
    std::uint32_t render_height = 0;
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
    std::uint32_t viewport_height) {
    const double scale = std::min({
        1.0,
        static_cast<double>(kMaximumRenderWidth) / viewport_width,
        static_cast<double>(kMaximumRenderHeight) / viewport_height,
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

class FrameStore final {
 public:
    void clear() {
        std::lock_guard lock(mutex_);
        width_ = 0;
        height_ = 0;
        rgba_.clear();
        generation_++;
    }

    bool update(const ccl::OutputDriver::Tile& tile) {
        if (tile.size.x <= 0 || tile.size.y <= 0
            || tile.full_size.x <= 0 || tile.full_size.y <= 0) {
            return false;
        }

        std::vector<float> pixels(
            static_cast<std::size_t>(tile.size.x) * tile.size.y * 4U);
        if (!tile.get_pass_pixels(kCombinedPass, 4, pixels.data())) {
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
                    (static_cast<std::size_t>(source_y) * tile.size.x + tile_x) * 4U;
                const std::size_t target =
                    (static_cast<std::size_t>(target_y) * width_ + target_x) * 4U;
                rgba_[target] = to_unorm(linear_to_srgb(pixels[source]));
                rgba_[target + 1U] = to_unorm(linear_to_srgb(pixels[source + 1U]));
                rgba_[target + 2U] = to_unorm(linear_to_srgb(pixels[source + 2U]));
                rgba_[target + 3U] = to_unorm(pixels[source + 3U]);
            }
        }
        generation_++;
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

    [[nodiscard]] std::pair<std::uint32_t, std::uint32_t> size() const {
        std::lock_guard lock(mutex_);
        return {width_, height_};
    }

 private:
    mutable std::mutex mutex_;
    std::uint32_t width_ = 0;
    std::uint32_t height_ = 0;
    std::uint64_t generation_ = 0;
    std::vector<std::uint8_t> rgba_;
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

std::size_t voxel_index(
    const CyclesBridgeVoxelScene& scene,
    std::uint32_t x,
    std::uint32_t y,
    std::uint32_t z) {
    return static_cast<std::size_t>(x)
        + static_cast<std::size_t>(scene.size_x)
            * (static_cast<std::size_t>(z) + static_cast<std::size_t>(scene.size_z) * y);
}

bool solid_at(
    const SceneRequest& request,
    int x,
    int y,
    int z) {
    if (x < 0 || y < 0 || z < 0
        || x >= static_cast<int>(request.scene.size_x)
        || y >= static_cast<int>(request.scene.size_y)
        || z >= static_cast<int>(request.scene.size_z)) {
        return false;
    }
    return (request.voxels[voxel_index(
                request.scene,
                static_cast<std::uint32_t>(x),
                static_cast<std::uint32_t>(y),
                static_cast<std::uint32_t>(z))]
            >> 24U)
        != 0U;
}

struct FaceDefinition {
    int neighbor_x;
    int neighbor_y;
    int neighbor_z;
    std::array<std::array<float, 3>, 4> corners;
};

constexpr std::array<FaceDefinition, 6> kFaces = {{
    {-1, 0, 0, {{{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}}}},
    {1, 0, 0, {{{1, 0, 1}, {1, 0, 0}, {1, 1, 0}, {1, 1, 1}}}},
    {0, -1, 0, {{{0, 0, 1}, {0, 0, 0}, {1, 0, 0}, {1, 0, 1}}}},
    {0, 1, 0, {{{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}}}},
    {0, 0, -1, {{{1, 0, 0}, {0, 0, 0}, {0, 1, 0}, {1, 1, 0}}}},
    {0, 0, 1, {{{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}}}},
}};

ccl::Shader* create_diffuse_shader(
    ccl::Scene* scene,
    std::uint32_t packed_color,
    std::size_t index) {
    auto graph = ccl::make_unique<ccl::ShaderGraph>();
    ccl::DiffuseBsdfNode* diffuse = graph->create_node<ccl::DiffuseBsdfNode>();
    diffuse->set_color(ccl::make_float3(
        srgb_to_linear(packed_color & 0xFFU),
        srgb_to_linear((packed_color >> 8U) & 0xFFU),
        srgb_to_linear((packed_color >> 16U) & 0xFFU)));
    diffuse->set_roughness(0.8F);
    graph->connect(diffuse->output("BSDF"), graph->output()->input("Surface"));

    ccl::Shader* shader = scene->create_node<ccl::Shader>();
    shader->name = "voxel_" + std::to_string(index);
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

void build_voxel_scene(ccl::Scene* scene, const SceneRequest& request) {
    configure_background(scene);
    scene->integrator->set_max_bounce(3);
    scene->integrator->set_max_diffuse_bounce(2);
    scene->integrator->set_max_glossy_bounce(1);
    scene->integrator->set_max_transmission_bounce(0);
    scene->integrator->set_max_volume_bounce(0);
    scene->integrator->set_use_adaptive_sampling(false);

    std::unordered_map<std::uint32_t, int> shader_indices;
    std::size_t visible_faces = 0;
    for (std::uint32_t y = 0; y < request.scene.size_y; ++y) {
        for (std::uint32_t z = 0; z < request.scene.size_z; ++z) {
            for (std::uint32_t x = 0; x < request.scene.size_x; ++x) {
                const std::uint32_t packed =
                    request.voxels[voxel_index(request.scene, x, y, z)];
                if ((packed >> 24U) == 0U) {
                    continue;
                }
                shader_indices.try_emplace(packed, static_cast<int>(shader_indices.size()));
                for (const FaceDefinition& face : kFaces) {
                    if (!solid_at(
                            request,
                            static_cast<int>(x) + face.neighbor_x,
                            static_cast<int>(y) + face.neighbor_y,
                            static_cast<int>(z) + face.neighbor_z)) {
                        visible_faces++;
                    }
                }
            }
        }
    }

    std::vector<ccl::Shader*> shaders(shader_indices.size(), nullptr);
    for (const auto& [packed, index] : shader_indices) {
        shaders[static_cast<std::size_t>(index)] =
            create_diffuse_shader(scene, packed, static_cast<std::size_t>(index));
    }

    if (visible_faces == 0) {
        return;
    }
    if (visible_faces > static_cast<std::size_t>(std::numeric_limits<int>::max() / 4)) {
        throw std::runtime_error("voxel mesh exceeds the Cycles vertex limit");
    }

    ccl::Mesh* mesh = scene->create_node<ccl::Mesh>();
    ccl::array<ccl::Node*> used_shaders;
    for (ccl::Shader* shader : shaders) {
        used_shaders.push_back_slow(shader);
    }
    mesh->set_used_shaders(used_shaders);
    mesh->resize_mesh(
        static_cast<int>(visible_faces * 4U),
        static_cast<int>(visible_faces * 2U));

    ccl::packed_float3* positions = mesh->get_position_for_write();
    int* triangles = mesh->get_triangles().data();
    int* triangle_shaders = mesh->get_shader().data();
    bool* smooth = mesh->get_smooth().data();
    std::size_t face_index = 0;
    for (std::uint32_t y = 0; y < request.scene.size_y; ++y) {
        for (std::uint32_t z = 0; z < request.scene.size_z; ++z) {
            for (std::uint32_t x = 0; x < request.scene.size_x; ++x) {
                const std::uint32_t packed =
                    request.voxels[voxel_index(request.scene, x, y, z)];
                if ((packed >> 24U) == 0U) {
                    continue;
                }
                const int shader_index = shader_indices.at(packed);
                for (const FaceDefinition& face : kFaces) {
                    if (solid_at(
                            request,
                            static_cast<int>(x) + face.neighbor_x,
                            static_cast<int>(y) + face.neighbor_y,
                            static_cast<int>(z) + face.neighbor_z)) {
                        continue;
                    }

                    const int vertex_base = static_cast<int>(face_index * 4U);
                    const int triangle_base = static_cast<int>(face_index * 2U);
                    for (int corner = 0; corner < 4; ++corner) {
                        positions[vertex_base + corner] = ccl::make_float3(
                            static_cast<float>(x) + face.corners[corner][0],
                            static_cast<float>(y) + face.corners[corner][1],
                            static_cast<float>(z) + face.corners[corner][2]);
                    }
                    const int indices[6] = {
                        vertex_base,
                        vertex_base + 1,
                        vertex_base + 2,
                        vertex_base,
                        vertex_base + 2,
                        vertex_base + 3,
                    };
                    std::copy_n(indices, 6, triangles + triangle_base * 3);
                    triangle_shaders[triangle_base] = shader_index;
                    triangle_shaders[triangle_base + 1] = shader_index;
                    smooth[triangle_base] = false;
                    smooth[triangle_base + 1] = false;
                    face_index++;
                }
            }
        }
    }

    mesh->tag_position_modified();
    mesh->tag_triangles_modified();
    mesh->tag_shader_modified();
    mesh->tag_smooth_modified();
    ccl::Object* object = scene->create_node<ccl::Object>();
    object->set_geometry(mesh);
    object->set_tfm(ccl::transform_identity());
}

ccl::Transform camera_transform(
    const CyclesBridgeCamera& camera,
    const CyclesBridgeVoxelScene& scene) {
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
    camera->set_matrix(camera_transform(camera_request.camera, scene_request.scene));
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

}  // namespace

class CyclesEngine::Impl final {
 public:
    Impl() {
        initialize_cycles_runtime();
        devices_ = enumerate_devices();
        if (devices_.empty()) {
            throw std::runtime_error("Cycles reported no OptiX, CUDA, or CPU devices");
        }
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
        const CyclesBridgeVoxelScene& scene,
        const std::uint32_t* packed_voxels,
        std::uint64_t voxel_count,
        std::string& error) {
        auto request = std::make_shared<SceneRequest>();
        request->scene = scene;
        request->voxels.assign(packed_voxels, packed_voxels + voxel_count);
        {
            std::lock_guard lock(request_mutex_);
            if (stopping_) {
                error = "Cycles worker is stopping";
                return false;
            }
            request->revision = ++scene_revision_;
            requested_scene_ = std::move(request);
        }
        frames_.clear();
        set_state("scene-queued", {});
        request_changed_.notify_all();
        return true;
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

        CameraRequest request;
        request.camera = camera;
        std::tie(request.render_width, request.render_height) =
            render_dimensions(camera.viewport_width, camera.viewport_height);
        bool changed = false;
        {
            std::lock_guard lock(request_mutex_);
            if (!requested_scene_) {
                error = "voxel scene has not been uploaded";
                return false;
            }
            if (!requested_camera_ || !same_camera(*requested_camera_, request)) {
                request.revision = ++camera_revision_;
                requested_camera_ = request;
                changed = true;
            }
        }
        if (changed) {
            set_state("camera-queued", {});
            request_changed_.notify_all();
        }

        {
            std::lock_guard lock(state_mutex_);
            if (!terminal_error_.empty()) {
                error = terminal_error_;
                return false;
            }
        }
        frames_.copy_scaled(rgba, camera.viewport_width, camera.viewport_height);
        return true;
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
    void set_state(std::string state, std::string terminal_error) {
        std::lock_guard lock(state_mutex_);
        state_ = std::move(state);
        terminal_error_ = std::move(terminal_error);
    }

    void set_device_state(
        const ccl::DeviceInfo& device,
        std::string state,
        std::string terminal_error = {}) {
        std::lock_guard lock(state_mutex_);
        selected_device_ = device;
        state_ = std::move(state);
        terminal_error_ = std::move(terminal_error);
    }

    ccl::SessionParams make_session_params(const ccl::DeviceInfo& device) const {
        ccl::SessionParams params;
        params.device = device;
        params.denoise_device = device;
        params.headless = true;
        params.background = false;
        params.samples = kRenderSamples;
        params.use_auto_tile = false;
        params.use_resolution_divider = false;
        return params;
    }

    ccl::unique_ptr<ccl::Session> create_session(
        const ccl::DeviceInfo& device,
        const SceneRequest& scene_request,
        ccl::SessionParams& session_params) {
        session_params = make_session_params(device);
        ccl::SceneParams scene_params;
        scene_params.background = false;
        auto session = ccl::make_unique<ccl::Session>(session_params, scene_params);
        session->set_output_driver(ccl::make_unique<FrameOutputDriver>(frames_));
        ccl::Pass* pass = session->scene->create_node<ccl::Pass>();
        pass->set_name(ccl::ustring(kCombinedPass));
        pass->set_type(ccl::PASS_COMBINED);
        build_voxel_scene(session->scene.get(), scene_request);
        return session;
    }

    bool rebuild_session(
        ccl::unique_ptr<ccl::Session>& session,
        ccl::SessionParams& params,
        const SceneRequest& scene_request,
        std::size_t& device_index) {
        if (session) {
            session->cancel(true);
            session.reset();
        }
        while (device_index < devices_.size()) {
            const ccl::DeviceInfo device = devices_[device_index];
            try {
                set_device_state(device, "initializing");
                session = create_session(device, scene_request, params);
                set_device_state(device, "scene-ready");
                return true;
            } catch (const std::exception& exception) {
                set_device_state(device, "fallback", exception.what());
                session.reset();
                device_index++;
            }
        }
        const std::string message = "all Cycles backends failed during session creation";
        set_state("failed", message);
        return false;
    }

    void start_render(
        ccl::Session& session,
        const ccl::SessionParams& params,
        const SceneRequest& scene_request,
        const CameraRequest& camera_request) {
        ccl::BufferParams buffer;
        {
            const ccl::thread_scoped_lock scene_lock(session.scene->mutex);
            buffer = configure_camera(session, scene_request, camera_request);
        }
        session.reset(params, buffer);
        session.start();
        set_state("rendering", {});
    }

    void worker_main() {
        ccl::unique_ptr<ccl::Session> session;
        ccl::SessionParams session_params;
        std::shared_ptr<const SceneRequest> active_scene;
        std::uint64_t active_scene_revision = 0;
        std::uint64_t active_camera_revision = 0;
        std::size_t device_index = 0;

        try {
            while (true) {
                std::shared_ptr<const SceneRequest> requested_scene;
                std::optional<CameraRequest> requested_camera;
                {
                    std::unique_lock lock(request_mutex_);
                    request_changed_.wait_for(lock, 100ms, [this, active_scene_revision, active_camera_revision] {
                        return stopping_
                            || (requested_scene_
                                && requested_scene_->revision != active_scene_revision)
                            || (requested_camera_
                                && requested_camera_->revision != active_camera_revision);
                    });
                    if (stopping_) {
                        break;
                    }
                    requested_scene = requested_scene_;
                    requested_camera = requested_camera_;
                }

                if (session && session->progress.get_error()) {
                    const std::string backend_error = session->progress.get_error_message();
                    session->cancel(true);
                    session.reset();
                    device_index++;
                    active_scene_revision = 0;
                    active_camera_revision = 0;
                    frames_.clear();
                    if (device_index >= devices_.size()) {
                        set_state("failed", "all Cycles backends failed; last error: " + backend_error);
                        continue;
                    }
                    set_device_state(devices_[device_index], "fallback");
                }

                if (requested_scene && requested_scene->revision != active_scene_revision) {
                    active_scene = requested_scene;
                    if (!rebuild_session(session, session_params, *active_scene, device_index)) {
                        continue;
                    }
                    active_scene_revision = active_scene->revision;
                    active_camera_revision = 0;
                }

                if (session && active_scene && requested_camera
                    && requested_camera->revision != active_camera_revision) {
                    start_render(*session, session_params, *active_scene, *requested_camera);
                    active_camera_revision = requested_camera->revision;
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
            } catch (...) {
            }
        }
    }

    mutable std::mutex request_mutex_;
    std::condition_variable request_changed_;
    bool stopping_ = false;
    std::uint64_t scene_revision_ = 0;
    std::uint64_t camera_revision_ = 0;
    std::shared_ptr<const SceneRequest> requested_scene_;
    std::optional<CameraRequest> requested_camera_;

    mutable std::mutex state_mutex_;
    ccl::DeviceInfo selected_device_;
    std::string state_;
    std::string terminal_error_;

    std::vector<ccl::DeviceInfo> devices_;
    FrameStore frames_;
    std::thread worker_;
};

CyclesEngine::CyclesEngine() : impl_(std::make_unique<Impl>()) {}

CyclesEngine::~CyclesEngine() = default;

bool CyclesEngine::upload_voxel_scene(
    const CyclesBridgeVoxelScene& scene,
    const std::uint32_t* packed_voxels,
    std::uint64_t voxel_count,
    std::string& error) {
    return impl_->upload(scene, packed_voxels, voxel_count, error);
}

bool CyclesEngine::render(
    const CyclesBridgeCamera& camera,
    std::uint8_t* rgba,
    std::uint64_t rgba_capacity,
    std::string& error) {
    return impl_->render(camera, rgba, rgba_capacity, error);
}

std::string CyclesEngine::renderer_info() const {
    return impl_->info();
}

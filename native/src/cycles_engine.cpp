#include "cycles_engine.h"

#include "color_management.h"
#include "cycles_camera.h"
#include "cycles_scene_builder.h"
#include "cycles_session_config.h"
#include "cycles_scene_timing.h"
#include "frame_store.h"
#include "labpbr_material.h"
#include "realtime_section_mesh.h"
#include "scene_update.h"
#include "vulkan_interop_display.h"

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
#if defined(CYCLESRENDERER_DLSS_EXPERIMENTAL)
namespace ccl {
void request_dlss_history_reset();
}
#endif
#if defined(WITH_CUDA)
#include "cuew.h"
#endif
#include "scene/attribute.h"
#include "scene/camera.h"
#include "scene/image.h"
#include "scene/image_loader.h"
#include "scene/light.h"
#include "scene/mesh.h"
#include "scene/object.h"
#include "scene/scene.h"
#include "scene/shader.h"
#include "scene/background.h"
#include "scene/shader_graph.h"
#include "scene/shader_nodes.h"
#include "session/buffers.h"
#include "session/display_driver.h"
#include "session/session.h"
#include "util/colorspace.h"
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
using cyclesrenderer::FrameDisplayDriver;
using cyclesrenderer::FrameStore;
using cyclesrenderer::VulkanInteropBinding;
using cyclesrenderer::VulkanInteropDisplayDriver;
using cyclesrenderer::VulkanInteropSnapshot;
using cyclesrenderer::camera_adapter::CameraRequest;
using cyclesrenderer::camera_adapter::configure_camera;
using cyclesrenderer::camera_adapter::kMaximumRenderHeight;
using cyclesrenderer::camera_adapter::kMaximumRenderWidth;
using cyclesrenderer::camera_adapter::render_dimensions;
using cyclesrenderer::camera_adapter::same_camera;
using cyclesrenderer::camera_adapter::valid_camera;
using cyclesrenderer::scene_builder::SceneRuntime;
using cyclesrenderer::scene_builder::apply_scene_delta;
using cyclesrenderer::scene_builder::build_scene;
using cyclesrenderer::session_config::DenoiserSchedule;
using cyclesrenderer::session_config::SettingsChange;
using cyclesrenderer::session_config::classify_settings_change;
using cyclesrenderer::session_config::configure_scene_settings;
using cyclesrenderer::session_config::create_output_passes;
using cyclesrenderer::session_config::default_settings;
using cyclesrenderer::session_config::device_diagnostic_id;
using cyclesrenderer::session_config::device_mask;
using cyclesrenderer::session_config::device_matches_policy;
using cyclesrenderer::session_config::device_type_name;
using cyclesrenderer::session_config::enumerate_devices;
using cyclesrenderer::session_config::interop_depth_resolution_divider;
using cyclesrenderer::session_config::make_session_params;
using cyclesrenderer::session_config::required_output_pass_mask;
using cyclesrenderer::session_config::uses_post_process_depth_of_field;

using SectionRequest = cyclesrenderer::scene::SectionData;
using SceneResourcesData = cyclesrenderer::scene::ResourcesData;
using SceneRequest = cyclesrenderer::scene::SceneSnapshot;
using SceneUpdate = cyclesrenderer::scene::SceneUpdate;

CyclesBridgeReprojectionMetadata make_reprojection_metadata(
    const CameraRequest& camera_request,
    std::uint64_t scene_revision,
    const CyclesBridgeRenderSettings& settings) {
    CyclesBridgeReprojectionMetadata metadata{};
    metadata.struct_size = sizeof(metadata);
    metadata.struct_version = 1U;
    const bool perspective = settings.camera_type == CYCLES_BRIDGE_CAMERA_PERSPECTIVE
        && settings.projection_mode == CYCLES_BRIDGE_PROJECTION_MINECRAFT_FOV;
    const bool physical_depth_of_field = settings.depth_of_field != 0U
        && settings.depth_of_field_mode == CYCLES_BRIDGE_DEPTH_OF_FIELD_PHYSICAL;
    metadata.flags = perspective && !physical_depth_of_field
        ? CYCLES_BRIDGE_REPROJECTION_METADATA_VALID : 0U;
    metadata.projection = CYCLES_BRIDGE_REPROJECTION_PROJECTION_PERSPECTIVE;
    metadata.depth_semantic = CYCLES_BRIDGE_REPROJECTION_DEPTH_AXIAL_CAMERA_Z;
    metadata.color_width = camera_request.render_width;
    metadata.color_height = camera_request.render_height;
    metadata.frame_revision = camera_request.camera.frame_id;
    metadata.camera_revision = camera_request.revision;
    metadata.scene_revision = scene_revision;
    metadata.position_x = camera_request.camera.position_x;
    metadata.position_y = camera_request.camera.position_y;
    metadata.position_z = camera_request.camera.position_z;
    double qx = camera_request.camera.rotation_x;
    double qy = camera_request.camera.rotation_y;
    double qz = camera_request.camera.rotation_z;
    double qw = camera_request.camera.rotation_w;
    const double quaternion_length = std::sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
    if (quaternion_length <= 1.0e-12 || !std::isfinite(quaternion_length)) {
        qx = qy = qz = 0.0;
        qw = 1.0;
    } else {
        qx /= quaternion_length;
        qy /= quaternion_length;
        qz /= quaternion_length;
        qw /= quaternion_length;
    }
    metadata.rotation_x = static_cast<float>(qx);
    metadata.rotation_y = static_cast<float>(qy);
    metadata.rotation_z = static_cast<float>(qz);
    metadata.rotation_w = static_cast<float>(qw);
    metadata.vertical_fov_radians = camera_request.camera.vertical_fov_radians;
    metadata.aspect = static_cast<float>(camera_request.render_width)
        / static_cast<float>(std::max(1U, camera_request.render_height));
    metadata.shift_x = settings.camera_shift_x;
    metadata.shift_y = settings.camera_shift_y;
    metadata.near_clip = settings.camera_clip_near;
    metadata.far_clip = std::max(
        settings.camera_clip_near + 0.001F,
        settings.camera_clip_far > 0.0F
            ? settings.camera_clip_far : camera_request.camera.depth_far);
    return metadata;
}

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
        interop_.stop();
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
        std::optional<std::array<std::uint8_t, 16>> selected_device_uuid;
        {
            std::lock_guard lock(state_mutex_);
            selected_device_uuid = selected_device_uuid_;
        }
        return interop_.bind(
            descriptor,
            memory_handle,
            ready_semaphore_handle,
            release_semaphore_handle,
            selected_device_uuid,
            error);
    }

    bool unbind_vulkan_interop_buffer(std::string& error) {
        return interop_.unbind(error);
    }

    void query_vulkan_interop_state(
        CyclesBridgeVulkanInteropState& state) const {
        interop_.query_state(state);
    }

    void acquire_vulkan_interop_frame(
        std::uint64_t previous_generation,
        CyclesBridgeVulkanInteropState& state) {
        interop_.acquire_frame(previous_generation, state);
    }

    void acquire_vulkan_reprojection_frame(
        std::uint64_t previous_generation,
        CyclesBridgeVulkanInteropState& state,
        CyclesBridgeReprojectionMetadata& metadata) {
        interop_.acquire_reprojection_frame(previous_generation, state, metadata);
    }

    bool release_vulkan_interop_frame(
        std::uint64_t generation,
        std::string& error) {
        return interop_.release_frame(generation, error);
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

        cyclesrenderer::scene::SectionMap sections;
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
            sections.emplace(0, std::move(section));
        }
        {
            std::lock_guard lock(request_mutex_);
            if (stopping_) {
                error = "Cycles worker is stopping";
                return false;
            }
            scene_updates_.replace(resources, std::move(sections));
            requested_scene_ = scene_updates_.commit(++scene_revision_);
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
            scene_updates_.reset(std::move(copied));
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
        if (!scene_updates_.resources()) {
            error = "scene resources have not been reset";
            return false;
        }
        for (const CyclesBridgeTriangle& triangle : copied->triangles) {
            if (triangle.material_index >= scene_updates_.resources()->materials.size()) {
                error = "section references an unknown material";
                return false;
            }
        }
        scene_updates_.upsert(std::move(copied));
        return true;
    }

    bool remove_section(std::int64_t section_id, std::string& error) {
        std::lock_guard lock(request_mutex_);
        if (stopping_) {
            error = "Cycles worker is stopping";
            return false;
        }
        if (!scene_updates_.resources()) {
            error = "scene resources have not been reset";
            return false;
        }
        scene_updates_.remove(section_id);
        return true;
    }

    bool commit_scene(std::string& error) {
        const auto commit_start = std::chrono::steady_clock::now();
        std::uint64_t committed_revision = 0U;
        {
            std::lock_guard lock(request_mutex_);
            if (stopping_) {
                error = "Cycles worker is stopping";
                return false;
            }
            if (!scene_updates_.resources()) {
                error = "scene resources have not been reset";
                return false;
            }
            requested_scene_ = scene_updates_.commit(++scene_revision_);
            committed_revision = requested_scene_->revision;
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
        scene_timing_.record_commit(committed_revision);
        set_state("scene-queued", {});
        request_changed_.notify_all();
        return true;
    }

    bool apply_settings(
        const CyclesBridgeRenderSettings& settings,
        std::string& error) {
        SettingsChange change{};
        frames_.set_cache_budget(settings.pass_cache_megabytes);
        {
            std::lock_guard lock(request_mutex_);
            if (stopping_) {
                error = "Cycles worker is stopping";
                return false;
            }
            change = classify_settings_change(
                settings, requested_settings_, settings_revision_ > 0);
            if (!change.display_only_no_op) {
                requested_settings_ = settings;
                if (requested_settings_.revision <= settings_revision_) {
                    requested_settings_.revision = settings_revision_ + 1U;
                }
                settings_revision_ = requested_settings_.revision;
                requested_reset_level_ = change.reset_level;
                requested_pass_only_change_ = change.pass_only_change;
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
                    requested_camera_->preserve_pass_cache = change.pass_only_change;
                    requested_camera_->revision = ++camera_revision_;
                    last_camera_change_ = std::chrono::steady_clock::now();
                }
            }
        }
        if (change.display_only_no_op) {
            std::lock_guard lock(state_mutex_);
            last_reset_level_ = CYCLES_BRIDGE_RESET_NONE;
            return true;
        }
        if (change.reset_level >= CYCLES_BRIDGE_RESET_BUFFER) {
            frames_.clear();
        } else if (change.reset_level >= CYCLES_BRIDGE_RESET_ACCUMULATION
                   && !change.pass_only_change) {
            frames_.invalidate_pass_cache();
        }
        {
            std::lock_guard lock(state_mutex_);
            last_reset_level_ = change.reset_level;
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
#if defined(CYCLESRENDERER_DLSS_EXPERIMENTAL)
        capabilities.capability_flags |=
            CYCLES_BRIDGE_CAPABILITY_DLSS_EXPERIMENTAL_COMPILED;
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
#if defined(CYCLESRENDERER_DLSS_EXPERIMENTAL)
            if ((device.denoisers & ccl::DENOISER_DLSS) != 0) {
                capabilities.denoiser_mask |= CYCLES_BRIDGE_DENOISER_DLSS_EXPERIMENTAL;
            }
#endif
        }
    }

    [[nodiscard]] std::string color_management_info() const {
        return color_management_->info();
    }

    bool query_color_lut(
        std::uint32_t display_device,
        std::uint32_t view_transform,
        std::uint32_t color_look,
        std::uint32_t working_space,
        CyclesBridgeColorLutDescriptor& descriptor,
        float* rgba,
        std::uint64_t rgba_capacity,
        std::string& error) const {
        return color_management_->query_lut(
            display_device, view_transform, color_look, working_space,
            descriptor, rgba, rgba_capacity, error);
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
                ? static_cast<std::uint32_t>(requested_scene_->section_count)
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
            diagnostics.camera_type = camera_type_diagnostic_;
            diagnostics.panorama_type = panorama_type_diagnostic_;
            diagnostics.camera_shift_x = camera_shift_x_diagnostic_;
            diagnostics.camera_shift_y = camera_shift_y_diagnostic_;
            if (selected_device_uuid_.has_value()) {
                diagnostics.device_uuid_valid = 1U;
                std::memcpy(
                    diagnostics.device_uuid,
                    selected_device_uuid_->data(),
                    selected_device_uuid_->size());
            }
        }
        frames_.fill_diagnostics(diagnostics);
        scene_timing_.fill_diagnostics(diagnostics);
    }

    bool render(
        const CyclesBridgeCamera& camera,
        std::uint8_t* rgba,
        std::uint64_t rgba_capacity,
        std::string& error) {
        if (!valid_camera(camera, error)) {
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
                    requested_settings_.camera_type == CYCLES_BRIDGE_CAMERA_PERSPECTIVE
                        && requested_settings_.projection_mode
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
                       && produced_camera_revision()
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

    ccl::unique_ptr<ccl::Session> create_session(
        const ccl::DeviceInfo& device,
        const SceneRequest& scene_request,
        const CyclesBridgeRenderSettings& settings,
        std::uint64_t registered_pass_mask,
        ccl::SessionParams& session_params,
        SceneRuntime& runtime) {
        std::string color_error;
        if (!color_management_->activate_working_space(
                settings.working_space, color_error)) {
            throw std::runtime_error(
                "failed to activate Cycles working space: " + color_error);
        }
        VulkanInteropSnapshot interop_snapshot =
            interop_.snapshot(query_cuda_device_uuid(device));
        const bool use_graphics_interop = interop_snapshot.memory_handle != nullptr;
        const bool export_depth = uses_post_process_depth_of_field(settings);
        const float depth_resolution_divider =
            interop_depth_resolution_divider(device, settings);
        session_params = make_session_params(device, use_graphics_interop);
#if defined(CYCLESRENDERER_DLSS_EXPERIMENTAL)
        if (settings.denoiser_mode == 4U) {
            session_params.headless = false;
        }
#endif
        ccl::SceneParams scene_params;
        scene_params.background = false;
        auto session = ccl::make_unique<ccl::Session>(session_params, scene_params);
        ccl::Session* session_pointer = session.get();
        session->progress.set_update_callback([this, session_pointer] {
            ccl::string status;
            ccl::string substatus;
            session_pointer->progress.get_status(status, substatus);
            scene_timing_.observe_status(status, substatus);
        });
        if (use_graphics_interop) {
            session->set_display_driver(
                ccl::make_unique<VulkanInteropDisplayDriver>(
                    std::move(interop_snapshot),
                    frames_,
                    interop_,
                    request_changed_,
                    export_depth,
                    depth_resolution_divider));
            interop_.mark_session_attached();
        } else {
            session->set_display_driver(
                ccl::make_unique<FrameDisplayDriver>(frames_, request_changed_));
        }
        create_output_passes(
            session->scene.get(),
            registered_pass_mask | required_output_pass_mask(settings));
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
        std::string backend_error;
        {
            std::lock_guard lock(state_mutex_);
            if (state_ == "fallback") {
                backend_error = terminal_error_;
            }
        }
        std::string message =
            "no usable Cycles backend matched the selected device policy";
        if (!backend_error.empty()) {
            message += "; last backend error: " + backend_error;
        }
        set_state("failed", message);
        return false;
    }

    void update_session_scene(
        ccl::Session& session,
        const SceneRequest& scene_request,
        const SceneUpdate& scene_update,
        SceneRuntime& runtime,
        ccl::thread_scoped_lock& scene_lock) {
        if (!scene_lock.owns_lock()) {
            throw std::logic_error("scene update requires the Cycles scene lock");
        }
        const auto delta_start = std::chrono::steady_clock::now();
        set_state("scene-updating", {});
        apply_scene_delta(
            session.scene.get(), scene_request, scene_update, runtime);
        record_scene_delta(elapsed_micros(
            delta_start, std::chrono::steady_clock::now()));
        set_state("scene-ready", {});
    }

    void start_render(
        ccl::Session& session,
        const ccl::SessionParams& params,
        const SceneRequest& scene_request,
        std::uint64_t scene_revision,
        const CameraRequest& camera_request,
        const CyclesBridgeRenderSettings& settings,
        const SceneUpdate* scene_update = nullptr,
        SceneRuntime* scene_runtime = nullptr,
        ccl::thread_scoped_lock* acquired_scene_lock = nullptr) {
        ccl::BufferParams buffer;
        DenoiserSchedule denoiser_schedule{};
        ccl::SessionParams render_params = params;
        std::uint32_t reset_wait_micros = 0U;
        std::uint32_t render_configure_micros = 0U;
        std::uint32_t render_prepare_micros = 0U;
        std::uint32_t session_start_micros = 0U;
        std::uint32_t scene_delta_micros = 0U;
        const bool apply_delta = scene_update != nullptr && scene_runtime != nullptr;
        if (apply_delta) {
            set_state("scene-updating", {});
        }
        const auto delta_start = std::chrono::steady_clock::now();
        auto start_time = std::chrono::steady_clock::now();
        auto reset_end = start_time;
        ccl::thread_scoped_lock local_scene_lock(
            session.scene->mutex, std::defer_lock);
        if (acquired_scene_lock == nullptr) {
            local_scene_lock.lock();
            acquired_scene_lock = &local_scene_lock;
        } else if (!acquired_scene_lock->owns_lock()) {
            throw std::logic_error("render start received an unlocked Cycles scene lock");
        }
        {
            if (apply_delta) {
                apply_scene_delta(
                    session.scene.get(), scene_request, *scene_update, *scene_runtime);
                scene_delta_micros = elapsed_micros(
                    delta_start, std::chrono::steady_clock::now());
            }
            start_time = std::chrono::steady_clock::now();
            buffer = configure_camera(session, scene_request, camera_request, settings);
            denoiser_schedule = configure_scene_settings(
                session.scene.get(), params.device, settings,
                camera_request.sampling_state,
                camera_request.sample_count);
            render_params.samples = std::max(1, camera_request.sample_count);
            const bool still =
                camera_request.sampling_state == CYCLES_BRIDGE_SAMPLING_STILL;
            const std::uint32_t time_limit_millis = still
                ? settings.still_time_limit_millis
                : settings.interactive_time_limit_millis;
            render_params.time_limit = static_cast<double>(time_limit_millis) / 1000.0;
            const auto reset_start = std::chrono::steady_clock::now();
            render_configure_micros = elapsed_micros(start_time, reset_start);
            session.reset(render_params, buffer);
            reset_end = std::chrono::steady_clock::now();
            reset_wait_micros = elapsed_micros(reset_start, reset_end);
            interop_.set_configured_reprojection_metadata(make_reprojection_metadata(
                camera_request, scene_revision, settings));
            frames_.configure(
                settings,
                camera_request.camera.depth_far,
                render_params.samples,
                denoiser_schedule.effective != 0U,
                camera_request.revision);
        }
        if (apply_delta) {
            record_scene_delta(scene_delta_micros);
        }
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
            camera_type_diagnostic_ = settings.camera_type;
            panorama_type_diagnostic_ = settings.panorama_type;
            camera_shift_x_diagnostic_ = settings.camera_shift_x;
            camera_shift_y_diagnostic_ = settings.camera_shift_y;
            const float aspect = static_cast<float>(camera_request.render_width)
                / static_cast<float>(std::max(1U, camera_request.render_height));
            vertical_fov_diagnostic_ = settings.projection_mode
                    == CYCLES_BRIDGE_PROJECTION_PHYSICAL_LENS
                ? 2.0F * std::atan(
                    settings.sensor_width_mm
                    / (2.0F * settings.focal_length_mm * aspect))
                : camera_request.camera.vertical_fov_radians;
            depth_of_field_diagnostic_ = settings.depth_of_field;
            focus_distance_diagnostic_ =
                (camera_request.camera.flags & CYCLES_BRIDGE_CAMERA_FOCUS_DISTANCE_VALID) != 0U
                ? camera_request.camera.focus_distance
                : settings.focus_distance;
            f_stop_diagnostic_ = settings.f_stop;
            aperture_size_diagnostic_ = settings.depth_of_field != 0U
                    && settings.depth_of_field_mode
                        == CYCLES_BRIDGE_DEPTH_OF_FIELD_PHYSICAL
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
        scene_timing_.record_reset_wait(scene_revision, reset_wait_micros);
        const auto session_start_time = std::chrono::steady_clock::now();
        render_prepare_micros = elapsed_micros(reset_end, session_start_time);
        session.start();
        const auto session_started_time = std::chrono::steady_clock::now();
        session_start_micros = elapsed_micros(session_start_time, session_started_time);
        scene_timing_.record_render_start_phases(
            render_configure_micros,
            reset_wait_micros,
            render_prepare_micros,
            session_start_micros);
        record_render_start(elapsed_micros(
            start_time, session_started_time));
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
        return interop_.produced_camera_revision(frames_);
    }

    void update_sampling_progress(ccl::Session& session) {
        const int actual = std::clamp(
            session.progress.get_current_sample(), 0, sampling_target_);
        frames_.set_sample_count(actual);
        interop_.set_sample_count(static_cast<std::uint32_t>(actual));

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
        SceneRequest active_scene;
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
                std::shared_ptr<const SceneUpdate> requested_scene;
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
                                                           active_settings_revision,
                                                           &render_in_flight,
                                                           &render_camera_revision] {
                        return stopping_
                            || scene_reset_revision_ != active_reset_revision
                            || settings_revision_ != active_settings_revision
                            || (requested_scene_
                                && requested_scene_->revision != observed_scene_revision)
                            || (requested_camera_
                                && requested_camera_->revision != observed_camera_revision)
                            || (render_in_flight
                                && produced_camera_revision()
                                    == render_camera_revision);
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
                    const std::uint64_t requested_pass_mask =
                        required_output_pass_mask(requested_settings)
                        | (1ULL << requested_settings.active_pass);
                    const bool pass_registration_required =
                        (requested_pass_mask & ~registered_pass_mask) != 0U;
                    if (pass_registration_required) {
                        registered_pass_mask |= requested_pass_mask;
                    }
                    if (session && pass_changed && !pass_registration_required) {
                        std::lock_guard lock(state_mutex_);
                        pass_registry_hit_count_++;
                    }
                    pass_only_settings_update = requested_pass_only_change;
                    if (session && (requested_settings_reset == CYCLES_BRIDGE_RESET_SESSION
                                    || pass_changed
                                    || pass_registration_required)) {
                        session->cancel(true);
                        session.reset();
                        scene_runtime.clear();
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
#if defined(CYCLESRENDERER_DLSS_EXPERIMENTAL)
                        ccl::request_dlss_history_reset();
#endif
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
                    active_scene.clear();
                    active_scene_revision = 0;
                    active_camera_revision = 0;
                    active_reset_revision = requested_reset_revision;
                    render_in_flight = false;
                    frames_.clear();
                } else if (render_in_flight
                           && produced_camera_revision()
                                == render_camera_revision) {
                    scene_timing_.complete_scene_update(active_scene_revision);
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

                if (requested_scene
                    && requested_scene->revision != active_scene_revision) {
                    ccl::thread_scoped_lock scene_lock;
                    const bool initially_incremental = session
                        && active_scene.resources
                        && scene_runtime.resources == requested_scene->resources;
                    if (initially_incremental) {
                        scene_lock = ccl::thread_scoped_lock(
                            session->scene->mutex, std::defer_lock);
                        set_state("scene-queued", {});
                        // The Cycles render thread releases this mutex between scene-update
                        // iterations. Waiting here gives a queued revision a guaranteed handoff;
                        // polling with try_lock can repeatedly miss that narrow window.
                        scene_lock.lock();
                    }
                    if (initially_incremental) {
                        std::lock_guard request_lock(request_mutex_);
                        if (scene_reset_revision_ != requested_reset_revision) {
                            continue;
                        }
                        requested_scene = requested_scene_;
                        requested_camera = requested_camera_;
                    }
                    if (!requested_scene
                        || requested_scene->revision == active_scene_revision) {
                        continue;
                    }
                    scene_timing_.begin_scene_update(requested_scene->revision);
                    if (!pass_only_settings_update) {
                        frames_.invalidate_pass_cache();
                    }
                    const bool resources_changed = !session
                        || !active_scene.resources
                        || scene_runtime.resources != requested_scene->resources;
                    if (resources_changed && scene_lock.owns_lock()) {
                        scene_lock.unlock();
                    }
                    cyclesrenderer::scene::apply_scene_update(
                        active_scene, *requested_scene);
                    bool scene_render_started = false;
                    if (resources_changed) {
                        if (!rebuild_session(
                                session,
                                session_params,
                                active_scene,
                                active_settings,
                                registered_pass_mask,
                                scene_runtime,
                                device_index)) {
                            continue;
                        }
                        render_in_flight = false;
                    } else if (requested_camera) {
                        if (!scene_lock.owns_lock()) {
                            throw std::logic_error(
                                "incremental render started without the Cycles scene lock");
                        }
                        render_camera_revision = requested_camera->revision;
                        start_render(
                            *session,
                            session_params,
                            active_scene,
                            requested_scene->revision,
                            *requested_camera,
                            active_settings,
                            requested_scene.get(),
                            &scene_runtime,
                            &scene_lock);
                        active_camera_revision = requested_camera->revision;
                        render_in_flight = true;
                        scene_render_started = true;
                    } else {
                        if (!scene_lock.owns_lock()) {
                            throw std::logic_error(
                                "incremental update started without the Cycles scene lock");
                        }
                        update_session_scene(
                            *session,
                            active_scene,
                            *requested_scene,
                            scene_runtime,
                            scene_lock);
                    }
                    if (scene_lock.owns_lock()) {
                        scene_lock.unlock();
                    }
                    active_scene_revision = requested_scene->revision;
                    {
                        std::lock_guard lock(request_mutex_);
                        scene_updates_.acknowledge(*requested_scene);
                    }
                    if (!scene_render_started) {
                        active_camera_revision = 0;
                    }
                }

                if (!render_in_flight && session && active_scene.resources && requested_camera
                    && requested_camera->revision != active_camera_revision) {
                    if (!requested_camera->preserve_pass_cache) {
                        frames_.invalidate_pass_cache();
                    }
                    render_camera_revision = requested_camera->revision;
                    start_render(
                        *session,
                        session_params,
                        active_scene,
                        active_scene_revision,
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
    cyclesrenderer::scene::SceneUpdateAccumulator scene_updates_;
    std::shared_ptr<const SceneUpdate> requested_scene_;
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
    cyclesrenderer::timing::CyclesSceneTiming scene_timing_;
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
    std::uint32_t camera_type_diagnostic_ = CYCLES_BRIDGE_CAMERA_PERSPECTIVE;
    std::uint32_t panorama_type_diagnostic_ = CYCLES_BRIDGE_PANORAMA_EQUIRECTANGULAR;
    float camera_shift_x_diagnostic_ = 0.0F;
    float camera_shift_y_diagnostic_ = 0.0F;
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
    VulkanInteropBinding interop_;
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
    std::uint32_t display_device,
    std::uint32_t view_transform,
    std::uint32_t color_look,
    std::uint32_t working_space,
    CyclesBridgeColorLutDescriptor& descriptor,
    float* rgba,
    std::uint64_t rgba_capacity,
    std::string& error) const {
    return impl_->query_color_lut(
        display_device, view_transform, color_look, working_space,
        descriptor, rgba, rgba_capacity, error);
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

void CyclesEngine::acquire_vulkan_reprojection_frame(
    std::uint64_t previous_generation,
    CyclesBridgeVulkanInteropState& state,
    CyclesBridgeReprojectionMetadata& metadata) {
    impl_->acquire_vulkan_reprojection_frame(previous_generation, state, metadata);
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

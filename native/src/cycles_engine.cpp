#include "cycles_engine.h"

#include "color_management.h"
#include "cycles_scene_builder.h"
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
#include "scene/film.h"
#include "scene/image.h"
#include "scene/image_loader.h"
#include "scene/integrator.h"
#include "scene/light.h"
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
using cyclesrenderer::VulkanInteropDisplayDriver;
using cyclesrenderer::VulkanInteropSlot;
using cyclesrenderer::VulkanInteropSlotOwner;
using cyclesrenderer::VulkanInteropSlots;
using cyclesrenderer::VulkanInteropSnapshot;
using cyclesrenderer::refresh_vulkan_interop_slot_flags;
using cyclesrenderer::scene_builder::SceneRuntime;
using cyclesrenderer::scene_builder::apply_scene_delta;
using cyclesrenderer::scene_builder::build_scene;

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
    settings.pbr_normal_strength = 1.0F;
    settings.pbr_emission_scale = 1.0F;
    settings.working_space = CYCLES_BRIDGE_WORKING_SPACE_LINEAR_REC709;
    settings.dlss_quality_mode = CYCLES_BRIDGE_DLSS_QUALITY_QUALITY;
    settings.depth_of_field_mode = CYCLES_BRIDGE_DEPTH_OF_FIELD_PHYSICAL;
    settings.camera_type = CYCLES_BRIDGE_CAMERA_PERSPECTIVE;
    settings.panorama_type = CYCLES_BRIDGE_PANORAMA_EQUIRECTANGULAR;
    settings.fisheye_fov_degrees = 180.0F;
    settings.fisheye_lens_mm = 10.5F;
    settings.latitude_min_degrees = -90.0F;
    settings.latitude_max_degrees = 90.0F;
    settings.longitude_min_degrees = -180.0F;
    settings.longitude_max_degrees = 180.0F;
    settings.fisheye_polynomial_k0 = -1.1735143712967577e-05F;
    settings.fisheye_polynomial_k1 = -0.019988736953434998F;
    settings.fisheye_polynomial_k2 = -3.3525322965709175e-06F;
    settings.fisheye_polynomial_k3 = 3.099275275886036e-06F;
    settings.fisheye_polynomial_k4 = -2.6064646454854524e-08F;
    settings.central_cylindrical_longitude_min_degrees = -180.0F;
    settings.central_cylindrical_longitude_max_degrees = 180.0F;
    settings.central_cylindrical_height_min = -1.0F;
    settings.central_cylindrical_height_max = 1.0F;
    settings.central_cylindrical_radius = 1.0F;
    settings.camera_shift_x = 0.0F;
    settings.camera_shift_y = 0.0F;
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

bool same_atmosphere_settings(
    const CyclesBridgeRenderSettings& first,
    const CyclesBridgeRenderSettings& second) {
    return first.atmosphere_sun_disc == second.atmosphere_sun_disc
        && first.atmosphere_sun_size_degrees == second.atmosphere_sun_size_degrees
        && first.atmosphere_sun_intensity == second.atmosphere_sun_intensity
        && first.atmosphere_sun_elevation_degrees
            == second.atmosphere_sun_elevation_degrees
        && first.atmosphere_sun_rotation_degrees
            == second.atmosphere_sun_rotation_degrees
        && first.atmosphere_altitude_meters == second.atmosphere_altitude_meters
        && first.atmosphere_air_density == second.atmosphere_air_density
        && first.atmosphere_aerosol_density == second.atmosphere_aerosol_density
        && first.atmosphere_ozone_density == second.atmosphere_ozone_density;
}

bool same_material_shader_settings(
    const CyclesBridgeRenderSettings& first,
    const CyclesBridgeRenderSettings& second) {
    return first.pbr_normal_strength == second.pbr_normal_strength
        && first.pbr_emission_scale == second.pbr_emission_scale
        && first.pbr_wetness == second.pbr_wetness
        && first.pbr_subsurface_scale == second.pbr_subsurface_scale
        && first.pbr_height_strength == second.pbr_height_strength
        && first.pbr_height_distance == second.pbr_height_distance
        && first.pbr_height_mapping_mode == second.pbr_height_mapping_mode
        && first.pbr_parallax_steps == second.pbr_parallax_steps;
}

float dlss_upscale_factor(std::uint32_t quality_mode) {
    constexpr std::array<float, 5> factors = {
        1.0F,
        1.0F / 0.65F,
        1.0F / 0.57F,
        2.0F,
        3.0F,
    };
    return factors[quality_mode];
}

bool uses_post_process_depth_of_field(
    const CyclesBridgeRenderSettings& settings) {
    return settings.depth_of_field != 0U
        && settings.depth_of_field_mode
            == CYCLES_BRIDGE_DEPTH_OF_FIELD_POST_PROCESS
        && settings.camera_type == CYCLES_BRIDGE_CAMERA_PERSPECTIVE
        && settings.active_pass == CYCLES_BRIDGE_PASS_COMBINED;
}

std::uint64_t required_output_pass_mask(
    const CyclesBridgeRenderSettings& settings) {
    std::uint64_t mask = 1ULL << CYCLES_BRIDGE_PASS_COMBINED;
    if (uses_post_process_depth_of_field(settings)) {
        mask |= 1ULL << CYCLES_BRIDGE_PASS_DEPTH;
    }
    return mask;
}

float interop_depth_resolution_divider(
    const ccl::DeviceInfo& device,
    const CyclesBridgeRenderSettings& settings) {
#if defined(CYCLESRENDERER_DLSS_EXPERIMENTAL)
    if (settings.denoiser_mode == 4U
        && (device.denoisers & ccl::DENOISER_DLSS) != 0) {
        return dlss_upscale_factor(settings.dlss_quality_mode);
    }
#else
    (void)device;
    (void)settings;
#endif
    return 1.0F;
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

using SectionRequest = cyclesrenderer::scene::SectionData;
using SceneResourcesData = cyclesrenderer::scene::ResourcesData;
using SceneRequest = cyclesrenderer::scene::SceneSnapshot;
using SceneUpdate = cyclesrenderer::scene::SceneUpdate;

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
        && std::isfinite(camera.depth_far)
        && ((camera.flags & CYCLES_BRIDGE_CAMERA_FOCUS_DISTANCE_VALID) == 0U
            || std::isfinite(camera.focus_distance));
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
            || nearly_equal(first.depth_far, second.depth_far, 1.0e-3))
        && first.flags == second.flags
        && ((first.flags & CYCLES_BRIDGE_CAMERA_FOCUS_DISTANCE_VALID) == 0U
            || nearly_equal(first.focus_distance, second.focus_distance, 1.0e-4));
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

ccl::Transform camera_transform(
    const CyclesBridgeCamera& camera,
    const CyclesBridgeScene& scene,
    const CyclesBridgeRenderSettings& settings) {
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

    const ccl::Transform minecraft_transform = {
        ccl::make_float4(1.0F - 2.0F * (yy + zz), 2.0F * (xy - zw), 2.0F * (xz + yw), px),
        ccl::make_float4(2.0F * (xy + zw), 1.0F - 2.0F * (xx + zz), 2.0F * (yz - xw), py),
        ccl::make_float4(2.0F * (xz - yw), 2.0F * (yz + xw), 1.0F - 2.0F * (xx + yy), pz),
    };

    // Minecraft and Blender cameras both point down local -Z. Match Blender's
    // own Cycles adapter so every panorama subtype has the same visual heading
    // as its Blender counterpart (mirror ball has a distinct convention).
    if (settings.camera_type == CYCLES_BRIDGE_CAMERA_PANORAMA) {
        if (settings.panorama_type == CYCLES_BRIDGE_PANORAMA_MIRRORBALL) {
            return ccl::transform_clear_scale(
                minecraft_transform
                * ccl::make_transform(
                    1.0F, 0.0F, 0.0F, 0.0F,
                    0.0F, 0.0F, 1.0F, 0.0F,
                    0.0F, 1.0F, 0.0F, 0.0F));
        }
        return ccl::transform_clear_scale(
            minecraft_transform
            * ccl::make_transform(
                0.0F, -1.0F, 0.0F, 0.0F,
                0.0F, 0.0F, 1.0F, 0.0F,
                -1.0F, 0.0F, 0.0F, 0.0F));
    }
    return ccl::transform_clear_scale(
        minecraft_transform * ccl::transform_scale(1.0F, 1.0F, -1.0F));
}

ccl::BufferParams configure_camera(
    ccl::Session& session,
    const SceneRequest& scene_request,
    const CameraRequest& camera_request,
    const CyclesBridgeRenderSettings& settings) {
    ccl::Camera* camera = session.scene->camera;
    const bool panorama = settings.camera_type == CYCLES_BRIDGE_CAMERA_PANORAMA;
    camera->set_camera_type(panorama ? ccl::CAMERA_PANORAMA : ccl::CAMERA_PERSPECTIVE);
    camera->set_panorama_type(static_cast<ccl::PanoramaType>(settings.panorama_type));
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
    camera->set_fisheye_fov(settings.fisheye_fov_degrees * kDegreesToRadians);
    camera->set_fisheye_lens(settings.fisheye_lens_mm);
    camera->set_latitude_min(settings.latitude_min_degrees * kDegreesToRadians);
    camera->set_latitude_max(settings.latitude_max_degrees * kDegreesToRadians);
    camera->set_longitude_min(settings.longitude_min_degrees * kDegreesToRadians);
    camera->set_longitude_max(settings.longitude_max_degrees * kDegreesToRadians);
    camera->set_fisheye_polynomial_k0(settings.fisheye_polynomial_k0);
    camera->set_fisheye_polynomial_k1(settings.fisheye_polynomial_k1);
    camera->set_fisheye_polynomial_k2(settings.fisheye_polynomial_k2);
    camera->set_fisheye_polynomial_k3(settings.fisheye_polynomial_k3);
    camera->set_fisheye_polynomial_k4(settings.fisheye_polynomial_k4);
    camera->set_central_cylindrical_range_u_min(
        settings.central_cylindrical_longitude_min_degrees * kDegreesToRadians);
    camera->set_central_cylindrical_range_u_max(
        settings.central_cylindrical_longitude_max_degrees * kDegreesToRadians);
    camera->set_central_cylindrical_range_v_min(
        settings.central_cylindrical_height_min / settings.central_cylindrical_radius);
    camera->set_central_cylindrical_range_v_max(
        settings.central_cylindrical_height_max / settings.central_cylindrical_radius);
    camera->set_sensorwidth(settings.sensor_width_mm);
    camera->set_sensorheight(settings.sensor_width_mm / aspect);
    const float near_clip = settings.camera_clip_near;
    const float requested_far_clip = settings.camera_clip_far > 0.0F
        ? settings.camera_clip_far
        : camera_request.camera.depth_far;
    camera->set_nearclip(near_clip);
    camera->set_farclip(std::max(near_clip + 0.001F, requested_far_clip));
    const bool physical_depth_of_field = settings.depth_of_field != 0U
        && settings.depth_of_field_mode
            == CYCLES_BRIDGE_DEPTH_OF_FIELD_PHYSICAL;
    const float aperture_size = physical_depth_of_field
        ? (settings.focal_length_mm / 1000.0F) / (2.0F * settings.f_stop)
        : 0.0F;
    const float focus_distance =
        (camera_request.camera.flags & CYCLES_BRIDGE_CAMERA_FOCUS_DISTANCE_VALID) != 0U
        ? camera_request.camera.focus_distance
        : settings.focus_distance;
#if defined(CYCLESRENDERER_DLSS_EXPERIMENTAL)
    if (!nearly_equal(camera->get_focaldistance(), focus_distance, 1.0e-5)) {
        ccl::request_dlss_history_reset();
    }
#endif
    camera->set_focaldistance(focus_distance);
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
    const ccl::Transform transform = camera_transform(
        camera_request.camera, scene, settings);
    camera->set_matrix(transform);
    ccl::array<ccl::Transform> motion = camera->get_motion();
    motion.resize(2, transform);
    motion[1] = transform;
    camera->set_motion(motion);
    camera->set_use_perspective_motion(false);
    camera->compute_auto_viewplane();
    if (panorama) {
        camera->set_viewplane_left(settings.camera_shift_x);
        camera->set_viewplane_right(1.0F + settings.camera_shift_x);
        camera->set_viewplane_bottom(settings.camera_shift_y);
        camera->set_viewplane_top(1.0F + settings.camera_shift_y);
    } else {
        const float fit_aspect = std::max(aspect, 1.0F / aspect);
        const float radius_x = aspect >= 1.0F ? aspect : 1.0F;
        const float radius_y = aspect >= 1.0F ? 1.0F : 1.0F / aspect;
        const float offset_x = 2.0F * fit_aspect * settings.camera_shift_x;
        const float offset_y = 2.0F * fit_aspect * settings.camera_shift_y;
        camera->set_viewplane_left(-radius_x + offset_x);
        camera->set_viewplane_right(radius_x + offset_x);
        camera->set_viewplane_bottom(-radius_y + offset_y);
        camera->set_viewplane_top(radius_y + offset_y);
    }
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
    // Keep Blender's Fast GI Approximation disabled. These defaults are also
    // zero in Cycles, but setting them explicitly prevents future scene/setup
    // changes from silently replacing true diffuse bounces with AO.
    integrator->set_ao_bounces(0);
    integrator->set_ao_factor(0.0F);
    integrator->set_ao_additive_factor(0.0F);
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
#if defined(CYCLESRENDERER_DLSS_EXPERIMENTAL)
    const bool dlss_available = (device.denoisers & ccl::DENOISER_DLSS) != 0;
#endif
    if ((settings.denoiser_mode == 1U || settings.denoiser_mode == 2U)
        && optix_available) {
        effective_denoiser = 1U;
        integrator->set_denoiser_type(ccl::DENOISER_OPTIX);
    } else if ((settings.denoiser_mode == 1U || settings.denoiser_mode == 3U)
               && oidn_available) {
        effective_denoiser = 2U;
        integrator->set_denoiser_type(ccl::DENOISER_OPENIMAGEDENOISE);
#if defined(CYCLESRENDERER_DLSS_EXPERIMENTAL)
    } else if (settings.denoiser_mode == 4U && dlss_available) {
        effective_denoiser = 3U;
        integrator->set_denoiser_type(ccl::DENOISER_DLSS);
#endif
    }
    DenoiserSchedule denoiser_schedule{};
    denoiser_schedule.selected = effective_denoiser;
    if (effective_denoiser == 0U) {
        denoiser_schedule.reason = CYCLES_BRIDGE_DENOISER_SCHEDULE_DISABLED;
    } else if (settings.active_pass != CYCLES_BRIDGE_PASS_COMBINED) {
        denoiser_schedule.reason = CYCLES_BRIDGE_DENOISER_SCHEDULE_DEBUG_PASS;
    } else if (effective_denoiser == 3U) {
        denoiser_schedule.effective = effective_denoiser;
        denoiser_schedule.start_sample = 0;
        denoiser_schedule.reason = CYCLES_BRIDGE_DENOISER_SCHEDULE_REALTIME;
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
    integrator->set_denoise_start_sample(static_cast<int>(
        effective_denoiser == 3U ? 0U : settings.denoiser_start_sample));
    int denoiser_passes = ccl::DENOISER_PASS_NONE;
#if defined(CYCLESRENDERER_DLSS_EXPERIMENTAL)
    if (effective_denoiser == 3U) {
        denoiser_passes = ccl::DENOISER_PASS_ALBEDO
            | ccl::DENOISER_PASS_SPECULAR_ALBEDO
            | ccl::DENOISER_PASS_NORMAL
            | ccl::DENOISER_PASS_ROUGHNESS
            | ccl::DENOISER_PASS_DEPTH
            | ccl::DENOISER_PASS_MOTION
            | ccl::DENOISER_PASS_SPECULAR_MOTION;
    } else
#endif
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
    integrator->set_denoise_use_gpu(
        effective_denoiser == 3U || settings.denoiser_use_gpu != 0U);
    integrator->set_denoiser_upscale_factor(
        effective_denoiser == 3U
            ? dlss_upscale_factor(settings.dlss_quality_mode)
            : 1.0F);

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
                interop_state_.depth_width = selected->depth_width;
                interop_state_.depth_height = selected->depth_height;
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
            acquired->depth_width = 0U;
            acquired->depth_height = 0U;
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
                const bool atmosphere_changed = settings_revision_ > 0
                    && !same_atmosphere_settings(settings, requested_settings_);
                const bool material_shader_changed = settings_revision_ > 0
                    && !same_material_shader_settings(settings, requested_settings_);
                const bool camera_shift_changed = settings_revision_ > 0
                    && (settings.camera_shift_x != requested_settings_.camera_shift_x
                        || settings.camera_shift_y != requested_settings_.camera_shift_y);
                const bool camera_topology_changed = settings_revision_ > 0
                    && (settings.camera_type != requested_settings_.camera_type
                        || settings.panorama_type
                            != requested_settings_.panorama_type);
                if (settings.device_policy != requested_settings_.device_policy
                    || denoiser_topology_changed
                    || atmosphere_changed
                    || material_shader_changed
                    || camera_shift_changed
                    || camera_topology_changed
                    || settings.depth_of_field_mode
                        != requested_settings_.depth_of_field_mode
                    || settings.working_space != requested_settings_.working_space) {
                    reset_level = CYCLES_BRIDGE_RESET_SESSION;
                } else if (settings.resolution_mode != requested_settings_.resolution_mode
                           || settings.render_width != requested_settings_.render_width
                           || settings.render_height != requested_settings_.render_height
                           || settings.resolution_percentage
                               != requested_settings_.resolution_percentage
                           || settings.dynamic_resolution
                               != requested_settings_.dynamic_resolution
                           || settings.interactive_resolution_percentage
                               != requested_settings_.interactive_resolution_percentage
                           || settings.dlss_quality_mode
                               != requested_settings_.dlss_quality_mode) {
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
        if (camera.viewport_width == 0 || camera.viewport_height == 0
            || !finite_camera(camera)
            || camera.vertical_fov_radians <= 0.0F
            || camera.vertical_fov_radians >= 3.14159265F
            || camera.depth_far <= 0.0F
            || (camera.flags & ~CYCLES_BRIDGE_CAMERA_FOCUS_DISTANCE_VALID) != 0U
            || ((camera.flags & CYCLES_BRIDGE_CAMERA_FOCUS_DISTANCE_VALID) != 0U
                && (camera.focus_distance < 0.01F
                    || camera.focus_distance > 1000000.0F))) {
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
            || camera.depth_far <= 0.0F
            || (camera.flags & ~CYCLES_BRIDGE_CAMERA_FOCUS_DISTANCE_VALID) != 0U
            || ((camera.flags & CYCLES_BRIDGE_CAMERA_FOCUS_DISTANCE_VALID) != 0U
                && (camera.focus_distance < 0.01F
                    || camera.focus_distance > 1000000.0F))) {
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
        std::string color_error;
        if (!color_management_->activate_working_space(
                settings.working_space, color_error)) {
            throw std::runtime_error(
                "failed to activate Cycles working space: " + color_error);
        }
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
                interop_snapshot = VulkanInteropSnapshot::duplicate(
                    interop_memory_handle_,
                    interop_ready_semaphore_handle_,
                    interop_release_semaphore_handle_,
                    interop_descriptor_);
            }
        }
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
                    interop_state_,
                    interop_slots_,
                    interop_mutex_,
                    interop_changed_,
                    request_changed_,
                    interop_stopping_,
                    interop_configured_camera_revision_,
                    interop_produced_camera_revision_,
                    export_depth,
                    depth_resolution_divider));
            {
                std::lock_guard lock(interop_mutex_);
                interop_state_.flags |=
                    CYCLES_BRIDGE_VULKAN_INTEROP_SESSION_ATTACHED;
            }
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
            {
                std::lock_guard lock(interop_mutex_);
                interop_configured_camera_revision_ = camera_request.revision;
            }
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
                    {
                        std::lock_guard lock(interop_mutex_);
                        interop_configured_camera_revision_ = render_camera_revision;
                    }
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

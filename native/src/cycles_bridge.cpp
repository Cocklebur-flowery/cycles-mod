#include "cycles_bridge.h"

#include "cycles_engine.h"

#include <Windows.h>

#include <cstddef>
#include <cmath>
#include <cstring>
#include <limits>
#include <memory>
#include <new>
#include <string>

struct CyclesBridgeRenderer {
    std::unique_ptr<CyclesEngine> engine;
};

namespace {

constexpr std::uint32_t kAbiVersion = 36;
constexpr std::uint32_t kStructVersion = 1;
constexpr char kBuildInfo[] = "cyclesrenderer-native/cycles-5.2;abi=36";

static_assert(sizeof(CyclesBridgeCamera) == 80);
static_assert(offsetof(CyclesBridgeCamera, frame_id) == 8);
static_assert(offsetof(CyclesBridgeCamera, position_x) == 24);
static_assert(offsetof(CyclesBridgeCamera, rotation_x) == 48);
static_assert(offsetof(CyclesBridgeCamera, vertical_fov_radians) == 64);
static_assert(sizeof(CyclesBridgeScene) == 48);
static_assert(offsetof(CyclesBridgeScene, origin_x) == 8);
static_assert(offsetof(CyclesBridgeScene, vertex_count) == 20);
static_assert(sizeof(CyclesBridgeSceneResources) == 48);
static_assert(sizeof(CyclesBridgeSection) == 48);
static_assert(sizeof(CyclesBridgeFrame) == 40);
static_assert(sizeof(CyclesBridgeFrameView) == 72);
static_assert(offsetof(CyclesBridgeFrameView, generation) == 16);
static_assert(offsetof(CyclesBridgeFrameView, pixels) == 48);
static_assert(sizeof(CyclesBridgeRenderSettings) == 368);
static_assert(offsetof(CyclesBridgeRenderSettings, camera_type) == 288);
static_assert(offsetof(CyclesBridgeRenderSettings, central_cylindrical_radius) == 356);
static_assert(offsetof(CyclesBridgeRenderSettings, camera_shift_x) == 360);
static_assert(offsetof(CyclesBridgeRenderSettings, camera_shift_y) == 364);
static_assert(sizeof(CyclesBridgePassDescriptor) == 64);
static_assert(sizeof(CyclesBridgeCapabilities) == 64);
static_assert(sizeof(CyclesBridgeColorLutDescriptor) == 72);
static_assert(offsetof(CyclesBridgeColorLutDescriptor, pixel_byte_count) == 32);
static_assert(sizeof(CyclesBridgeDiagnostics) == 624);
static_assert(offsetof(CyclesBridgeDiagnostics, device_uuid_valid) == 376);
static_assert(offsetof(CyclesBridgeDiagnostics, device_uuid) == 380);
static_assert(offsetof(CyclesBridgeDiagnostics, scene_timing_revision) == 400);
static_assert(offsetof(CyclesBridgeDiagnostics, last_scene_queue_micros) == 416);
static_assert(offsetof(CyclesBridgeDiagnostics, timing_reserved) == 500);
static_assert(offsetof(CyclesBridgeDiagnostics, camera_type) == 504);
static_assert(offsetof(CyclesBridgeDiagnostics, panorama_type) == 508);
static_assert(offsetof(CyclesBridgeDiagnostics, active_device_phase) == 512);
static_assert(offsetof(CyclesBridgeDiagnostics, last_device_phase_micros) == 520);
static_assert(offsetof(CyclesBridgeDiagnostics, ema_device_phase_micros) == 552);
static_assert(offsetof(CyclesBridgeDiagnostics, max_device_phase_micros) == 584);
static_assert(offsetof(CyclesBridgeDiagnostics, camera_shift_x) == 616);
static_assert(offsetof(CyclesBridgeDiagnostics, camera_shift_y) == 620);
static_assert(sizeof(CyclesBridgeVulkanInteropBuffer) == 80);
static_assert(offsetof(CyclesBridgeVulkanInteropBuffer, allocation_byte_count) == 24);
static_assert(offsetof(CyclesBridgeVulkanInteropBuffer, memory_handle) == 32);
static_assert(offsetof(CyclesBridgeVulkanInteropBuffer, device_uuid) == 40);
static_assert(offsetof(CyclesBridgeVulkanInteropBuffer, slot_count) == 56);
static_assert(offsetof(CyclesBridgeVulkanInteropBuffer, ready_semaphore_handle) == 64);
static_assert(sizeof(CyclesBridgeVulkanInteropState) == 72);
static_assert(offsetof(CyclesBridgeVulkanInteropState, generation) == 24);
static_assert(offsetof(CyclesBridgeVulkanInteropState, last_sync_micros) == 40);
static_assert(offsetof(CyclesBridgeVulkanInteropState, producer_wait_count) == 64);
static_assert(sizeof(CyclesBridgeVertex) == 40);
static_assert(offsetof(CyclesBridgeVertex, packed_rgba) == 32);
static_assert(sizeof(CyclesBridgeTriangle) == 16);
static_assert(sizeof(CyclesBridgeMaterial) == 32);
static_assert(offsetof(CyclesBridgeMaterial, normal_texture_index) == 16);
static_assert(offsetof(CyclesBridgeMaterial, material_texture_index) == 20);
static_assert(offsetof(CyclesBridgeMaterial, pbr_format) == 24);
static_assert(sizeof(CyclesBridgeTexture) == 32);
static_assert(offsetof(CyclesBridgeTexture, role) == 16);

std::uint8_t to_byte(std::uint64_t value) {
    return static_cast<std::uint8_t>(value & 0xFFU);
}

std::uint32_t write_string(const std::string& value, char* output, std::uint32_t capacity) {
    if (output == nullptr) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    if (value.size() + 1U > capacity) {
        return CYCLES_BRIDGE_STATUS_BUFFER_TOO_SMALL;
    }
    std::memcpy(output, value.c_str(), value.size() + 1U);
    return CYCLES_BRIDGE_STATUS_OK;
}

bool valid_scene_data(
    const CyclesBridgeScene& scene,
    const CyclesBridgeVertex* vertices,
    const CyclesBridgeTriangle* triangles,
    const CyclesBridgeMaterial* materials,
    const CyclesBridgeTexture* textures,
    const std::uint8_t* texture_pixels) {
    if (scene.triangle_count == 0) {
        return scene.vertex_count == 0 && scene.material_count == 0
            && scene.texture_count == 0 && scene.texture_byte_count == 0;
    }
    if (scene.vertex_count == 0 || scene.material_count == 0 || scene.texture_count == 0
        || scene.texture_byte_count == 0 || vertices == nullptr || triangles == nullptr
        || materials == nullptr || textures == nullptr || texture_pixels == nullptr) {
        return false;
    }
    if (scene.vertex_count > static_cast<std::uint32_t>(std::numeric_limits<int>::max())
        || scene.triangle_count > static_cast<std::uint32_t>(std::numeric_limits<int>::max())
        || scene.material_count > static_cast<std::uint32_t>(std::numeric_limits<int>::max())) {
        return false;
    }
    for (std::uint32_t index = 0; index < scene.vertex_count; ++index) {
        const CyclesBridgeVertex& vertex = vertices[index];
        if (!std::isfinite(vertex.position_x) || !std::isfinite(vertex.position_y)
            || !std::isfinite(vertex.position_z) || !std::isfinite(vertex.normal_x)
            || !std::isfinite(vertex.normal_y) || !std::isfinite(vertex.normal_z)
            || !std::isfinite(vertex.texture_u) || !std::isfinite(vertex.texture_v)) {
            return false;
        }
    }
    for (std::uint32_t index = 0; index < scene.triangle_count; ++index) {
        const CyclesBridgeTriangle& triangle = triangles[index];
        if (triangle.vertex_0 >= scene.vertex_count || triangle.vertex_1 >= scene.vertex_count
            || triangle.vertex_2 >= scene.vertex_count
            || triangle.material_index >= scene.material_count) {
            return false;
        }
    }
    for (std::uint32_t index = 0; index < scene.material_count; ++index) {
        const CyclesBridgeMaterial& material = materials[index];
        const bool no_pbr = material.pbr_format == CYCLES_BRIDGE_PBR_NONE;
        const bool lab_pbr = material.pbr_format == CYCLES_BRIDGE_PBR_LAB_1_3;
        if (material.texture_index >= scene.texture_count
            || (material.flags
                & ~(CYCLES_BRIDGE_MATERIAL_CUTOUT | CYCLES_BRIDGE_MATERIAL_BLEND)) != 0U
            || !std::isfinite(material.emission_strength)
            || !std::isfinite(material.alpha_cutoff)
            || material.emission_strength < 0.0F
            || material.alpha_cutoff < 0.0F || material.alpha_cutoff > 1.0F
            || (!no_pbr && !lab_pbr)
            || (no_pbr
                && (material.normal_texture_index != CYCLES_BRIDGE_TEXTURE_INDEX_INVALID
                    || material.material_texture_index != CYCLES_BRIDGE_TEXTURE_INDEX_INVALID))
            || (lab_pbr
                && (material.normal_texture_index >= scene.texture_count
                    || material.material_texture_index >= scene.texture_count))
            || material.reserved != 0U) {
            return false;
        }
    }
    for (std::uint32_t index = 0; index < scene.texture_count; ++index) {
        const CyclesBridgeTexture& texture = textures[index];
        const std::uint64_t expected_size =
            static_cast<std::uint64_t>(texture.width) * texture.height * 4U;
        const std::uint64_t end =
            static_cast<std::uint64_t>(texture.pixel_offset) + texture.pixel_size;
        if (texture.width == 0 || texture.height == 0
            || expected_size != texture.pixel_size || end > scene.texture_byte_count
            || (texture.role != CYCLES_BRIDGE_TEXTURE_COLOR_SRGB
                && texture.role != CYCLES_BRIDGE_TEXTURE_DATA_LINEAR)
            || texture.reserved[0] != 0U || texture.reserved[1] != 0U
            || texture.reserved[2] != 0U) {
            return false;
        }
    }
    for (std::uint32_t index = 0; index < scene.material_count; ++index) {
        const CyclesBridgeMaterial& material = materials[index];
        if (textures[material.texture_index].role != CYCLES_BRIDGE_TEXTURE_COLOR_SRGB
            || (material.pbr_format == CYCLES_BRIDGE_PBR_LAB_1_3
                && (textures[material.normal_texture_index].role
                        != CYCLES_BRIDGE_TEXTURE_DATA_LINEAR
                    || textures[material.material_texture_index].role
                        != CYCLES_BRIDGE_TEXTURE_DATA_LINEAR))) {
            return false;
        }
    }
    return true;
}

bool valid_resources_data(
    const CyclesBridgeSceneResources& resources,
    const CyclesBridgeMaterial* materials,
    const CyclesBridgeTexture* textures,
    const std::uint8_t* texture_pixels) {
    CyclesBridgeScene scene{};
    scene.material_count = resources.material_count;
    scene.texture_count = resources.texture_count;
    scene.texture_byte_count = resources.texture_byte_count;
    scene.vertex_count = 1;
    scene.triangle_count = 1;
    CyclesBridgeVertex vertex{};
    CyclesBridgeTriangle triangle{};
    return resources.material_count != 0 && resources.texture_count != 0
        && resources.texture_byte_count != 0
        && valid_scene_data(
            scene, &vertex, &triangle, materials, textures, texture_pixels);
}

bool valid_section_data(
    const CyclesBridgeSection& section,
    const CyclesBridgeVertex* vertices,
    const CyclesBridgeTriangle* triangles) {
    if (section.triangle_count == 0) {
        return section.vertex_count == 0;
    }
    if (section.vertex_count == 0 || vertices == nullptr || triangles == nullptr
        || section.vertex_count > static_cast<std::uint32_t>(std::numeric_limits<int>::max())
        || section.triangle_count > static_cast<std::uint32_t>(std::numeric_limits<int>::max())) {
        return false;
    }
    for (std::uint32_t index = 0; index < section.vertex_count; ++index) {
        const CyclesBridgeVertex& vertex = vertices[index];
        if (!std::isfinite(vertex.position_x) || !std::isfinite(vertex.position_y)
            || !std::isfinite(vertex.position_z) || !std::isfinite(vertex.normal_x)
            || !std::isfinite(vertex.normal_y) || !std::isfinite(vertex.normal_z)
            || !std::isfinite(vertex.texture_u) || !std::isfinite(vertex.texture_v)) {
            return false;
        }
    }
    for (std::uint32_t index = 0; index < section.triangle_count; ++index) {
        const CyclesBridgeTriangle& triangle = triangles[index];
        if (triangle.vertex_0 >= section.vertex_count
            || triangle.vertex_1 >= section.vertex_count
            || triangle.vertex_2 >= section.vertex_count) {
            return false;
        }
    }
    return true;
}

bool valid_settings(const CyclesBridgeRenderSettings& settings) {
    const auto valid_bool = [](std::uint32_t value) { return value <= 1U; };
    return settings.revision >= 1U
        && settings.device_policy <= 3U
        && settings.resolution_mode <= 1U
        && settings.render_width >= 160U && settings.render_width <= 3840U
        && settings.render_height >= 90U && settings.render_height <= 2160U
        && settings.resolution_percentage >= 25U && settings.resolution_percentage <= 100U
        && valid_bool(settings.dynamic_resolution)
        && settings.interactive_resolution_percentage >= 25U
        && settings.interactive_resolution_percentage <= 100U
        && settings.pass_cache_megabytes >= 64U
        && settings.pass_cache_megabytes <= 4096U
        && settings.sampling_pattern
            <= CYCLES_BRIDGE_SAMPLING_PATTERN_BLUE_NOISE_ROUND
        && std::isfinite(settings.camera_clip_near)
        && settings.camera_clip_near >= 0.001F
        && settings.camera_clip_near <= 10.0F
        && std::isfinite(settings.camera_clip_far)
        && settings.camera_clip_far >= 0.0F
        && settings.camera_clip_far <= 1000000.0F
        && settings.projection_mode <= CYCLES_BRIDGE_PROJECTION_PHYSICAL_LENS
        && std::isfinite(settings.focal_length_mm)
        && settings.focal_length_mm >= 1.0F && settings.focal_length_mm <= 300.0F
        && std::isfinite(settings.sensor_width_mm)
        && settings.sensor_width_mm >= 1.0F && settings.sensor_width_mm <= 100.0F
        && settings.camera_type <= CYCLES_BRIDGE_CAMERA_PANORAMA
        && settings.panorama_type <= CYCLES_BRIDGE_PANORAMA_CENTRAL_CYLINDRICAL
        && std::isfinite(settings.fisheye_fov_degrees)
        && settings.fisheye_fov_degrees >= 10.0F
        && settings.fisheye_fov_degrees <= 1800.0F
        && std::isfinite(settings.fisheye_lens_mm)
        && settings.fisheye_lens_mm >= 0.01F && settings.fisheye_lens_mm <= 100.0F
        && std::isfinite(settings.latitude_min_degrees)
        && settings.latitude_min_degrees >= -90.0F
        && settings.latitude_min_degrees <= 90.0F
        && std::isfinite(settings.latitude_max_degrees)
        && settings.latitude_max_degrees >= -90.0F
        && settings.latitude_max_degrees <= 90.0F
        && std::isfinite(settings.longitude_min_degrees)
        && settings.longitude_min_degrees >= -180.0F
        && settings.longitude_min_degrees <= 180.0F
        && std::isfinite(settings.longitude_max_degrees)
        && settings.longitude_max_degrees >= -180.0F
        && settings.longitude_max_degrees <= 180.0F
        && std::isfinite(settings.fisheye_polynomial_k0)
        && std::isfinite(settings.fisheye_polynomial_k1)
        && std::isfinite(settings.fisheye_polynomial_k2)
        && std::isfinite(settings.fisheye_polynomial_k3)
        && std::isfinite(settings.fisheye_polynomial_k4)
        && std::isfinite(settings.central_cylindrical_longitude_min_degrees)
        && settings.central_cylindrical_longitude_min_degrees >= -180.0F
        && settings.central_cylindrical_longitude_min_degrees <= 180.0F
        && std::isfinite(settings.central_cylindrical_longitude_max_degrees)
        && settings.central_cylindrical_longitude_max_degrees >= -180.0F
        && settings.central_cylindrical_longitude_max_degrees <= 180.0F
        && std::isfinite(settings.central_cylindrical_height_min)
        && settings.central_cylindrical_height_min >= -10.0F
        && settings.central_cylindrical_height_min <= 10.0F
        && std::isfinite(settings.central_cylindrical_height_max)
        && settings.central_cylindrical_height_max >= -10.0F
        && settings.central_cylindrical_height_max <= 10.0F
        && std::isfinite(settings.central_cylindrical_radius)
        && settings.central_cylindrical_radius >= 1.0e-5F
        && settings.central_cylindrical_radius <= 1000000.0F
        && std::isfinite(settings.camera_shift_x)
        && settings.camera_shift_x >= -10.0F
        && settings.camera_shift_x <= 10.0F
        && std::isfinite(settings.camera_shift_y)
        && settings.camera_shift_y >= -10.0F
        && settings.camera_shift_y <= 10.0F
        && valid_bool(settings.depth_of_field)
        && std::isfinite(settings.focus_distance)
        && settings.focus_distance >= 0.01F && settings.focus_distance <= 1000000.0F
        && std::isfinite(settings.f_stop)
        && settings.f_stop >= 0.1F && settings.f_stop <= 128.0F
        && (settings.aperture_blades == 0U
            || (settings.aperture_blades >= 3U && settings.aperture_blades <= 16U))
        && std::isfinite(settings.aperture_rotation_degrees)
        && settings.aperture_rotation_degrees >= -360.0F
        && settings.aperture_rotation_degrees <= 360.0F
        && std::isfinite(settings.aperture_ratio)
        && settings.aperture_ratio >= 0.1F && settings.aperture_ratio <= 10.0F
        && valid_bool(settings.atmosphere_sun_disc)
        && std::isfinite(settings.atmosphere_sun_size_degrees)
        && settings.atmosphere_sun_size_degrees >= 0.01F
        && settings.atmosphere_sun_size_degrees <= 180.0F
        && std::isfinite(settings.atmosphere_sun_intensity)
        && settings.atmosphere_sun_intensity >= 0.0F
        && settings.atmosphere_sun_intensity <= 1000.0F
        && std::isfinite(settings.atmosphere_sun_elevation_degrees)
        && settings.atmosphere_sun_elevation_degrees >= -90.0F
        && settings.atmosphere_sun_elevation_degrees <= 90.0F
        && std::isfinite(settings.atmosphere_sun_rotation_degrees)
        && settings.atmosphere_sun_rotation_degrees >= -360.0F
        && settings.atmosphere_sun_rotation_degrees <= 360.0F
        && std::isfinite(settings.atmosphere_altitude_meters)
        && settings.atmosphere_altitude_meters >= 0.0F
        && settings.atmosphere_altitude_meters <= 60000.0F
        && std::isfinite(settings.atmosphere_air_density)
        && settings.atmosphere_air_density >= 0.0F
        && settings.atmosphere_air_density <= 10.0F
        && std::isfinite(settings.atmosphere_aerosol_density)
        && settings.atmosphere_aerosol_density >= 0.0F
        && settings.atmosphere_aerosol_density <= 10.0F
        && std::isfinite(settings.atmosphere_ozone_density)
        && settings.atmosphere_ozone_density >= 0.0F
        && settings.atmosphere_ozone_density <= 10.0F
        && std::isfinite(settings.pbr_normal_strength)
        && settings.pbr_normal_strength >= 0.0F
        && settings.pbr_normal_strength <= 4.0F
        && std::isfinite(settings.pbr_emission_scale)
        && settings.pbr_emission_scale >= 0.0F
        && settings.pbr_emission_scale <= 100.0F
        && settings.working_space <= CYCLES_BRIDGE_WORKING_SPACE_ACESCG
        && settings.interactive_samples >= 1U && settings.interactive_samples <= 4096U
        && settings.still_samples >= 1U && settings.still_samples <= 4096U
        && settings.stationary_delay_millis <= 10000U
        && valid_bool(settings.adaptive_sampling)
        && settings.minimum_samples <= 4096U
        && std::isfinite(settings.noise_threshold)
        && settings.noise_threshold >= 0.0F && settings.noise_threshold <= 1.0F
        && settings.interactive_time_limit_millis <= 60000U
        && settings.still_time_limit_millis <= 600000U
        && settings.minimum_bounce <= 64U && settings.maximum_bounce <= 64U
        && settings.minimum_bounce <= settings.maximum_bounce
        && settings.diffuse_bounces <= 64U && settings.glossy_bounces <= 64U
        && settings.transmission_bounces <= 64U && settings.volume_bounces <= 64U
        && settings.transparent_bounces <= 64U
        && std::isfinite(settings.clamp_direct)
        && settings.clamp_direct >= 0.0F && settings.clamp_direct <= 100000.0F
        && std::isfinite(settings.clamp_indirect)
        && settings.clamp_indirect >= 0.0F && settings.clamp_indirect <= 100000.0F
        && std::isfinite(settings.filter_glossy)
        && settings.filter_glossy >= 0.0F && settings.filter_glossy <= 100.0F
        && valid_bool(settings.reflective_caustics)
        && valid_bool(settings.refractive_caustics)
        && settings.pixel_filter <= 2U
        && std::isfinite(settings.filter_width)
        && settings.filter_width >= 0.01F && settings.filter_width <= 10.0F
        && settings.seed >= 0
        && settings.denoiser_mode <= 4U
        && settings.denoiser_start_sample >= 1U
        && settings.denoiser_start_sample <= 4096U
        && settings.denoiser_input <= 2U
        && settings.denoiser_prefilter <= 2U
        && settings.denoiser_quality <= 2U
        && settings.dlss_quality_mode
            <= CYCLES_BRIDGE_DLSS_QUALITY_ULTRA_PERFORMANCE
        && settings.dlss_reserved == 0U
        && valid_bool(settings.denoiser_use_gpu)
        && std::isfinite(settings.exposure_ev)
        && settings.exposure_ev >= -20.0F && settings.exposure_ev <= 20.0F
        && std::isfinite(settings.gamma)
        && settings.gamma >= 0.1F && settings.gamma <= 5.0F
        && settings.view_transform <= CYCLES_BRIDGE_VIEW_TRANSFORM_AGX_HDR_1000
        && settings.active_pass < CYCLES_BRIDGE_PASS_COUNT
        && valid_bool(settings.debug_overlay);
}

class OwnedWin32Handle final {
 public:
    explicit OwnedWin32Handle(std::uint64_t value)
        : handle_(reinterpret_cast<HANDLE>(
              static_cast<std::uintptr_t>(value))) {}

    ~OwnedWin32Handle() {
        if (handle_ != nullptr) {
            CloseHandle(handle_);
        }
    }

    OwnedWin32Handle(const OwnedWin32Handle&) = delete;
    OwnedWin32Handle& operator=(const OwnedWin32Handle&) = delete;

    std::uint64_t release() {
        const auto value = reinterpret_cast<std::uintptr_t>(handle_);
        handle_ = nullptr;
        return static_cast<std::uint64_t>(value);
    }

 private:
    HANDLE handle_ = nullptr;
};

bool valid_vulkan_interop_buffer(
    const CyclesBridgeVulkanInteropBuffer& descriptor) {
    if (descriptor.struct_size < sizeof(CyclesBridgeVulkanInteropBuffer)
        || descriptor.struct_version != kStructVersion
        || descriptor.width == 0U || descriptor.height == 0U
        || descriptor.pixel_format != CYCLES_BRIDGE_PIXEL_FORMAT_RGBA16_FLOAT
        || descriptor.flags != CYCLES_BRIDGE_VULKAN_INTEROP_OWNERSHIP_TRANSFER
        || descriptor.memory_handle == 0U
        || descriptor.ready_semaphore_handle == 0U
        || descriptor.release_semaphore_handle == 0U
        || descriptor.slot_count == 0U || descriptor.slot_count > 3U) {
        return false;
    }
    const std::uint64_t pixel_count =
        static_cast<std::uint64_t>(descriptor.width) * descriptor.height;
    if (pixel_count > std::numeric_limits<std::uint64_t>::max() / 8U
        || descriptor.slot_stride_bytes < pixel_count * 8U) {
        return false;
    }
    return descriptor.slot_stride_bytes
            <= std::numeric_limits<std::uint64_t>::max() / descriptor.slot_count
        && descriptor.allocation_byte_count
            >= static_cast<std::uint64_t>(descriptor.slot_stride_bytes)
                * descriptor.slot_count;
}

}  // namespace

std::uint32_t cycles_bridge_abi_version() {
    return kAbiVersion;
}

std::uint32_t cycles_bridge_write_build_info(char* output, std::uint32_t capacity) {
    return write_string(kBuildInfo, output, capacity);
}

std::uint32_t cycles_bridge_create_renderer(CyclesBridgeRenderer** output_renderer) {
    if (output_renderer == nullptr) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    *output_renderer = nullptr;
    try {
        auto renderer = std::make_unique<CyclesBridgeRenderer>();
        renderer->engine = std::make_unique<CyclesEngine>();
        *output_renderer = renderer.release();
        return CYCLES_BRIDGE_STATUS_OK;
    } catch (const std::bad_alloc&) {
        return CYCLES_BRIDGE_STATUS_OUT_OF_MEMORY;
    } catch (...) {
        return CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    }
}

void cycles_bridge_destroy_renderer(CyclesBridgeRenderer* renderer) {
    delete renderer;
}

std::uint32_t cycles_bridge_write_renderer_info(
    const CyclesBridgeRenderer* renderer,
    char* output,
    std::uint32_t capacity) {
    if (renderer == nullptr || renderer->engine == nullptr) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    return write_string(renderer->engine->renderer_info(), output, capacity);
}

std::uint32_t cycles_bridge_query_capabilities(
    const CyclesBridgeRenderer* renderer,
    CyclesBridgeCapabilities* capabilities) {
    if (renderer == nullptr || renderer->engine == nullptr || capabilities == nullptr
        || capabilities->struct_size < sizeof(CyclesBridgeCapabilities)
        || capabilities->struct_version != kStructVersion) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    try {
        renderer->engine->query_capabilities(*capabilities);
        return CYCLES_BRIDGE_STATUS_OK;
    } catch (...) {
        return CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    }
}

std::uint32_t cycles_bridge_write_color_management_info(
    const CyclesBridgeRenderer* renderer,
    char* output,
    std::uint32_t capacity) {
    if (renderer == nullptr || renderer->engine == nullptr) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    return write_string(renderer->engine->color_management_info(), output, capacity);
}

std::uint32_t cycles_bridge_query_color_lut(
    const CyclesBridgeRenderer* renderer,
    std::uint32_t display_device,
    std::uint32_t view_transform,
    std::uint32_t color_look,
    std::uint32_t working_space,
    CyclesBridgeColorLutDescriptor* descriptor,
    float* rgba,
    std::uint64_t rgba_capacity) {
    if (renderer == nullptr || renderer->engine == nullptr || descriptor == nullptr
        || descriptor->struct_size != sizeof(CyclesBridgeColorLutDescriptor)
        || descriptor->struct_version != kStructVersion
        || (rgba == nullptr && rgba_capacity != 0U)) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    try {
        CyclesBridgeColorLutDescriptor result{};
        std::string error;
        if (!renderer->engine->query_color_lut(
                display_device, view_transform, color_look, working_space,
                result, nullptr, 0U, error)) {
            return CYCLES_BRIDGE_STATUS_RENDER_ERROR;
        }
        *descriptor = result;
        if (rgba == nullptr) {
            return CYCLES_BRIDGE_STATUS_OK;
        }
        if (rgba_capacity < result.pixel_byte_count) {
            return CYCLES_BRIDGE_STATUS_BUFFER_TOO_SMALL;
        }
        return renderer->engine->query_color_lut(
                   display_device, view_transform, color_look, working_space,
                   result, rgba, rgba_capacity, error)
            ? CYCLES_BRIDGE_STATUS_OK
            : CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    } catch (const std::bad_alloc&) {
        return CYCLES_BRIDGE_STATUS_OUT_OF_MEMORY;
    } catch (...) {
        return CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    }
}

std::uint32_t cycles_bridge_query_pass_descriptor(
    std::uint32_t pass_id,
    CyclesBridgePassDescriptor* descriptor) {
    if (descriptor == nullptr
        || descriptor->struct_size < sizeof(CyclesBridgePassDescriptor)
        || descriptor->struct_version != kStructVersion
        || pass_id >= CYCLES_BRIDGE_PASS_COUNT) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    CyclesBridgePassDescriptor result{};
    result.struct_size = sizeof(result);
    result.struct_version = kStructVersion;
    result.pass_id = pass_id;
    result.display_component_count = 4U;
    result.pixel_format = CYCLES_BRIDGE_PIXEL_FORMAT_RGBA16_FLOAT;
    result.flags = CYCLES_BRIDGE_PASS_DISPLAYABLE | CYCLES_BRIDGE_PASS_CACHE_RAW;
    switch (pass_id) {
        case CYCLES_BRIDGE_PASS_COMBINED:
            result.source_component_count = 4U;
            result.semantic = CYCLES_BRIDGE_PASS_SEMANTIC_COLOR;
            result.flags |= CYCLES_BRIDGE_PASS_COLOR_MANAGED
                | CYCLES_BRIDGE_PASS_DENOISE_RESULT
                | CYCLES_BRIDGE_PASS_CACHE_DENOISED;
            break;
        case CYCLES_BRIDGE_PASS_DEPTH:
            result.source_component_count = 1U;
            result.semantic = CYCLES_BRIDGE_PASS_SEMANTIC_DEPTH;
            result.flags |= CYCLES_BRIDGE_PASS_DEBUG;
            break;
        case CYCLES_BRIDGE_PASS_NORMAL:
            result.source_component_count = 3U;
            result.semantic = CYCLES_BRIDGE_PASS_SEMANTIC_NORMAL;
            result.flags |= CYCLES_BRIDGE_PASS_DEBUG;
            break;
        case CYCLES_BRIDGE_PASS_DIFFUSE_COLOR:
        case CYCLES_BRIDGE_PASS_EMISSION:
            result.source_component_count = 3U;
            result.semantic = CYCLES_BRIDGE_PASS_SEMANTIC_COLOR;
            result.flags |= CYCLES_BRIDGE_PASS_COLOR_MANAGED;
            break;
        case CYCLES_BRIDGE_PASS_ROUGHNESS:
        case CYCLES_BRIDGE_PASS_SAMPLE_COUNT:
            result.source_component_count = 1U;
            result.semantic = CYCLES_BRIDGE_PASS_SEMANTIC_SCALAR;
            result.flags |= CYCLES_BRIDGE_PASS_DEBUG;
            break;
        default:
            return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    *descriptor = result;
    return CYCLES_BRIDGE_STATUS_OK;
}

std::uint32_t cycles_bridge_apply_settings(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeRenderSettings* settings) {
    if (renderer == nullptr || renderer->engine == nullptr || settings == nullptr
        || settings->struct_size < sizeof(CyclesBridgeRenderSettings)
        || settings->struct_version != kStructVersion
        || !valid_settings(*settings)) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    try {
        std::string error;
        return renderer->engine->apply_settings(*settings, error)
            ? CYCLES_BRIDGE_STATUS_OK
            : CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    } catch (...) {
        return CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    }
}

std::uint32_t cycles_bridge_query_diagnostics(
    const CyclesBridgeRenderer* renderer,
    CyclesBridgeDiagnostics* diagnostics) {
    if (renderer == nullptr || renderer->engine == nullptr || diagnostics == nullptr
        || diagnostics->struct_size < sizeof(CyclesBridgeDiagnostics)
        || diagnostics->struct_version != kStructVersion) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    try {
        renderer->engine->query_diagnostics(*diagnostics);
        return CYCLES_BRIDGE_STATUS_OK;
    } catch (...) {
        return CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    }
}

std::uint32_t cycles_bridge_bind_vulkan_interop_buffer(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeVulkanInteropBuffer* descriptor) {
    OwnedWin32Handle owned_memory(
        descriptor != nullptr ? descriptor->memory_handle : 0U);
    OwnedWin32Handle owned_ready(
        descriptor != nullptr ? descriptor->ready_semaphore_handle : 0U);
    OwnedWin32Handle owned_release(
        descriptor != nullptr ? descriptor->release_semaphore_handle : 0U);
    if (renderer == nullptr || renderer->engine == nullptr || descriptor == nullptr
        || !valid_vulkan_interop_buffer(*descriptor)) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    try {
        std::string error;
        if (!renderer->engine->bind_vulkan_interop_buffer(
                *descriptor,
                descriptor->memory_handle,
                descriptor->ready_semaphore_handle,
                descriptor->release_semaphore_handle,
                error)) {
            return CYCLES_BRIDGE_STATUS_RENDER_ERROR;
        }
        owned_memory.release();
        owned_ready.release();
        owned_release.release();
        return CYCLES_BRIDGE_STATUS_OK;
    } catch (...) {
        return CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    }
}

std::uint32_t cycles_bridge_unbind_vulkan_interop_buffer(
    CyclesBridgeRenderer* renderer) {
    if (renderer == nullptr || renderer->engine == nullptr) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    std::string error;
    return renderer->engine->unbind_vulkan_interop_buffer(error)
        ? CYCLES_BRIDGE_STATUS_OK
        : CYCLES_BRIDGE_STATUS_RENDER_ERROR;
}

std::uint32_t cycles_bridge_query_vulkan_interop_state(
    const CyclesBridgeRenderer* renderer,
    CyclesBridgeVulkanInteropState* state) {
    if (renderer == nullptr || renderer->engine == nullptr || state == nullptr
        || state->struct_size < sizeof(CyclesBridgeVulkanInteropState)
        || state->struct_version != kStructVersion) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    renderer->engine->query_vulkan_interop_state(*state);
    return CYCLES_BRIDGE_STATUS_OK;
}

std::uint32_t cycles_bridge_acquire_vulkan_interop_frame(
    CyclesBridgeRenderer* renderer,
    std::uint64_t previous_generation,
    CyclesBridgeVulkanInteropState* state) {
    if (renderer == nullptr || renderer->engine == nullptr || state == nullptr
        || state->struct_size < sizeof(CyclesBridgeVulkanInteropState)
        || state->struct_version != kStructVersion) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    renderer->engine->acquire_vulkan_interop_frame(previous_generation, *state);
    return CYCLES_BRIDGE_STATUS_OK;
}

std::uint32_t cycles_bridge_release_vulkan_interop_frame(
    CyclesBridgeRenderer* renderer,
    std::uint64_t generation) {
    if (renderer == nullptr || renderer->engine == nullptr || generation == 0U) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    std::string error;
    return renderer->engine->release_vulkan_interop_frame(generation, error)
        ? CYCLES_BRIDGE_STATUS_OK
        : CYCLES_BRIDGE_STATUS_RENDER_ERROR;
}

void cycles_bridge_close_win32_handle(std::uint64_t handle) {
    if (handle != 0U) {
        CloseHandle(reinterpret_cast<HANDLE>(
            static_cast<std::uintptr_t>(handle)));
    }
}

std::uint32_t cycles_bridge_upload_scene(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeScene* scene,
    const CyclesBridgeVertex* vertices,
    const CyclesBridgeTriangle* triangles,
    const CyclesBridgeMaterial* materials,
    const CyclesBridgeTexture* textures,
    const std::uint8_t* texture_pixels) {
    if (renderer == nullptr || renderer->engine == nullptr || scene == nullptr
        || scene->struct_size < sizeof(CyclesBridgeScene)
        || scene->struct_version != kStructVersion) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    if (!valid_scene_data(
            *scene, vertices, triangles, materials, textures, texture_pixels)) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }

    try {
        std::string error;
        return renderer->engine->upload_scene(
                   *scene,
                   vertices,
                   triangles,
                   materials,
                   textures,
                   texture_pixels,
                   error)
            ? CYCLES_BRIDGE_STATUS_OK
            : CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    } catch (const std::bad_alloc&) {
        return CYCLES_BRIDGE_STATUS_OUT_OF_MEMORY;
    } catch (...) {
        return CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    }
}

std::uint32_t cycles_bridge_reset_scene(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeSceneResources* resources,
    const CyclesBridgeMaterial* materials,
    const CyclesBridgeTexture* textures,
    const std::uint8_t* texture_pixels) {
    if (renderer == nullptr || renderer->engine == nullptr || resources == nullptr
        || resources->struct_size < sizeof(CyclesBridgeSceneResources)
        || resources->struct_version != kStructVersion
        || !valid_resources_data(*resources, materials, textures, texture_pixels)) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    try {
        std::string error;
        return renderer->engine->reset_scene(
                   *resources, materials, textures, texture_pixels, error)
            ? CYCLES_BRIDGE_STATUS_OK
            : CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    } catch (const std::bad_alloc&) {
        return CYCLES_BRIDGE_STATUS_OUT_OF_MEMORY;
    } catch (...) {
        return CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    }
}

std::uint32_t cycles_bridge_upsert_section(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeSection* section,
    const CyclesBridgeVertex* vertices,
    const CyclesBridgeTriangle* triangles) {
    if (renderer == nullptr || renderer->engine == nullptr || section == nullptr
        || section->struct_size < sizeof(CyclesBridgeSection)
        || section->struct_version != kStructVersion
        || !valid_section_data(*section, vertices, triangles)) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    try {
        std::string error;
        return renderer->engine->upsert_section(*section, vertices, triangles, error)
            ? CYCLES_BRIDGE_STATUS_OK
            : CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    } catch (const std::bad_alloc&) {
        return CYCLES_BRIDGE_STATUS_OUT_OF_MEMORY;
    } catch (...) {
        return CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    }
}

std::uint32_t cycles_bridge_remove_section(
    CyclesBridgeRenderer* renderer,
    std::int64_t section_id) {
    if (renderer == nullptr || renderer->engine == nullptr) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    try {
        std::string error;
        return renderer->engine->remove_section(section_id, error)
            ? CYCLES_BRIDGE_STATUS_OK
            : CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    } catch (...) {
        return CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    }
}

std::uint32_t cycles_bridge_commit_scene(CyclesBridgeRenderer* renderer) {
    if (renderer == nullptr || renderer->engine == nullptr) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    try {
        std::string error;
        return renderer->engine->commit_scene(error)
            ? CYCLES_BRIDGE_STATUS_OK
            : CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    } catch (const std::bad_alloc&) {
        return CYCLES_BRIDGE_STATUS_OUT_OF_MEMORY;
    } catch (...) {
        return CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    }
}

std::uint32_t cycles_bridge_render(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeCamera* camera,
    std::uint8_t* rgba,
    std::uint64_t rgba_capacity) {
    if (renderer == nullptr || renderer->engine == nullptr || camera == nullptr || rgba == nullptr
        || camera->struct_size < sizeof(CyclesBridgeCamera)
        || camera->struct_version != kStructVersion) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }

    try {
        std::string error;
        return renderer->engine->render(*camera, rgba, rgba_capacity, error)
            ? CYCLES_BRIDGE_STATUS_OK
            : CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    } catch (const std::bad_alloc&) {
        return CYCLES_BRIDGE_STATUS_OUT_OF_MEMORY;
    } catch (...) {
        return CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    }
}

std::uint32_t cycles_bridge_render_frame(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeCamera* camera,
    CyclesBridgeFrame* frame,
    std::uint8_t* rgba,
    std::uint64_t rgba_capacity) {
    if (renderer == nullptr || renderer->engine == nullptr || camera == nullptr
        || frame == nullptr
        || camera->struct_size < sizeof(CyclesBridgeCamera)
        || camera->struct_version != kStructVersion
        || frame->struct_size < sizeof(CyclesBridgeFrame)
        || frame->struct_version != kStructVersion) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    try {
        std::string error;
        return renderer->engine->render_frame(*camera, *frame, rgba, rgba_capacity, error)
            ? CYCLES_BRIDGE_STATUS_OK
            : CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    } catch (const std::bad_alloc&) {
        return CYCLES_BRIDGE_STATUS_OUT_OF_MEMORY;
    } catch (...) {
        return CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    }
}

std::uint32_t cycles_bridge_update_camera(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeCamera* camera) {
    if (renderer == nullptr || renderer->engine == nullptr || camera == nullptr
        || camera->struct_size < sizeof(CyclesBridgeCamera)
        || camera->struct_version != kStructVersion) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    try {
        std::string error;
        return renderer->engine->update_camera(*camera, error)
            ? CYCLES_BRIDGE_STATUS_OK
            : CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    } catch (const std::bad_alloc&) {
        return CYCLES_BRIDGE_STATUS_OUT_OF_MEMORY;
    } catch (...) {
        return CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    }
}

std::uint32_t cycles_bridge_acquire_frame(
    CyclesBridgeRenderer* renderer,
    std::uint64_t previous_generation,
    CyclesBridgeFrameView* frame_view) {
    if (renderer == nullptr || renderer->engine == nullptr || frame_view == nullptr
        || frame_view->struct_size < sizeof(CyclesBridgeFrameView)
        || frame_view->struct_version != kStructVersion) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    try {
        std::string error;
        return renderer->engine->acquire_frame(previous_generation, *frame_view, error)
            ? CYCLES_BRIDGE_STATUS_OK
            : CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    } catch (const std::bad_alloc&) {
        return CYCLES_BRIDGE_STATUS_OUT_OF_MEMORY;
    } catch (...) {
        return CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    }
}

std::uint32_t cycles_bridge_release_frame(
    CyclesBridgeRenderer* renderer,
    std::uint64_t token) {
    if (renderer == nullptr || renderer->engine == nullptr || token == 0U) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    try {
        std::string error;
        return renderer->engine->release_frame(token, error)
            ? CYCLES_BRIDGE_STATUS_OK
            : CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    } catch (...) {
        return CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    }
}

std::uint32_t cycles_bridge_fill_test_frame(
    std::uint8_t* rgba,
    std::uint32_t width,
    std::uint32_t height,
    std::uint64_t frame_id) {
    if (rgba == nullptr || width == 0 || height == 0) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }

    constexpr std::uint64_t kChannels = 4;
    if (static_cast<std::uint64_t>(width) * height
        > std::numeric_limits<std::uint64_t>::max() / kChannels) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }

    for (std::uint32_t y = 0; y < height; ++y) {
        for (std::uint32_t x = 0; x < width; ++x) {
            const std::uint64_t offset =
                (static_cast<std::uint64_t>(y) * width + x) * kChannels;
            rgba[offset] = to_byte(static_cast<std::uint64_t>(x) * 17 + frame_id);
            rgba[offset + 1] = to_byte(static_cast<std::uint64_t>(y) * 17 + frame_id * 3);
            rgba[offset + 2] = to_byte(static_cast<std::uint64_t>(x ^ y) * 15 + frame_id * 5);
            rgba[offset + 3] = 0xFFU;
        }
    }

    return CYCLES_BRIDGE_STATUS_OK;
}

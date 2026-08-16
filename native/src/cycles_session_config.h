#pragma once

#include "cycles_bridge.h"

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <vector>

#include "device/device.h"
#include "scene/film.h"
#include "scene/integrator.h"
#include "scene/pass.h"
#include "scene/scene.h"
#include "session/session.h"
#include "util/string.h"

namespace cyclesrenderer::session_config {

struct SettingsChange final {
    std::uint32_t reset_level = CYCLES_BRIDGE_RESET_NONE;
    bool display_only_no_op = false;
    bool pass_only_change = false;
};
struct DenoiserSchedule final {
    std::uint32_t selected = 0;
    std::uint32_t effective = 0;
    std::uint32_t start_sample = 0;
    std::uint32_t reason = CYCLES_BRIDGE_DENOISER_SCHEDULE_DISABLED;
};

inline CyclesBridgeRenderSettings default_settings() {
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

inline bool same_render_settings(
    CyclesBridgeRenderSettings first,
    CyclesBridgeRenderSettings second) {
    first.revision = 0;
    second.revision = 0;
    first.debug_overlay = 0;
    second.debug_overlay = 0;
    return std::memcmp(&first, &second, sizeof(first)) == 0;
}

inline bool same_render_settings_except_pass(
    CyclesBridgeRenderSettings first,
    CyclesBridgeRenderSettings second) {
    first.active_pass = second.active_pass;
    return same_render_settings(first, second);
}

inline bool same_render_settings_except_cache_budget(
    CyclesBridgeRenderSettings first,
    CyclesBridgeRenderSettings second) {
    first.pass_cache_megabytes = second.pass_cache_megabytes;
    return same_render_settings(first, second);
}

inline bool same_atmosphere_settings(
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

inline bool same_material_shader_settings(
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

inline SettingsChange classify_settings_change(
    const CyclesBridgeRenderSettings& settings,
    const CyclesBridgeRenderSettings& current,
    bool initialized) {
    SettingsChange change{};
    change.display_only_no_op = initialized
        && same_render_settings(settings, current);
    if (change.display_only_no_op) {
        return change;
    }
    const bool pass_changed = initialized
        && settings.active_pass != current.active_pass;
    change.pass_only_change = pass_changed
        && same_render_settings_except_pass(settings, current);
    const bool cache_budget_only = initialized
        && settings.pass_cache_megabytes != current.pass_cache_megabytes
        && same_render_settings_except_cache_budget(settings, current);
    const bool denoiser_topology_changed = initialized
        && (settings.denoiser_mode != current.denoiser_mode
            || settings.denoiser_input != current.denoiser_input
            || settings.denoiser_use_gpu != current.denoiser_use_gpu);
    const bool atmosphere_changed = initialized
        && !same_atmosphere_settings(settings, current);
    const bool material_shader_changed = initialized
        && !same_material_shader_settings(settings, current);
    const bool camera_shift_changed = initialized
        && (settings.camera_shift_x != current.camera_shift_x
            || settings.camera_shift_y != current.camera_shift_y);
    const bool camera_topology_changed = initialized
        && (settings.camera_type != current.camera_type
            || settings.panorama_type != current.panorama_type);
    if (settings.device_policy != current.device_policy
        || denoiser_topology_changed
        || atmosphere_changed
        || material_shader_changed
        || camera_shift_changed
        || camera_topology_changed
        || settings.depth_of_field_mode != current.depth_of_field_mode
        || settings.working_space != current.working_space) {
        change.reset_level = CYCLES_BRIDGE_RESET_SESSION;
    } else if (settings.resolution_mode != current.resolution_mode
               || settings.render_width != current.render_width
               || settings.render_height != current.render_height
               || settings.resolution_percentage != current.resolution_percentage
               || settings.dynamic_resolution != current.dynamic_resolution
               || settings.interactive_resolution_percentage
                   != current.interactive_resolution_percentage
               || settings.dlss_quality_mode != current.dlss_quality_mode) {
        change.reset_level = CYCLES_BRIDGE_RESET_BUFFER;
    } else if (pass_changed) {
        change.reset_level = CYCLES_BRIDGE_RESET_ACCUMULATION;
    } else if (cache_budget_only) {
        change.reset_level = CYCLES_BRIDGE_RESET_NONE;
    } else if (!same_render_settings(settings, current)) {
        change.reset_level = CYCLES_BRIDGE_RESET_ACCUMULATION;
    }
    return change;
}

inline float dlss_upscale_factor(std::uint32_t quality_mode) {
    constexpr std::array<float, 5> factors = {
        1.0F,
        1.0F / 0.65F,
        1.0F / 0.57F,
        2.0F,
        3.0F,
    };
    return factors[quality_mode];
}

inline bool uses_post_process_depth_of_field(
    const CyclesBridgeRenderSettings& settings) {
    return settings.depth_of_field != 0U
        && settings.depth_of_field_mode
            == CYCLES_BRIDGE_DEPTH_OF_FIELD_POST_PROCESS
        && settings.camera_type == CYCLES_BRIDGE_CAMERA_PERSPECTIVE
        && settings.active_pass == CYCLES_BRIDGE_PASS_COMBINED;
}

inline std::uint64_t required_output_pass_mask(
    const CyclesBridgeRenderSettings& settings) {
    std::uint64_t mask = 1ULL << CYCLES_BRIDGE_PASS_COMBINED;
    if (uses_post_process_depth_of_field(settings)) {
        mask |= 1ULL << CYCLES_BRIDGE_PASS_DEPTH;
    }
    return mask;
}

inline float interop_depth_resolution_divider(
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

inline const char* pass_name(std::uint32_t pass) {
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

inline ccl::PassType pass_type(std::uint32_t pass) {
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

inline std::string device_type_name(ccl::DeviceType type) {
    return ccl::Device::string_from_type(type);
}

inline std::vector<ccl::DeviceInfo> enumerate_devices() {
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

inline std::uint32_t device_mask(const ccl::DeviceInfo& device) {
    switch (device.type) {
        case ccl::DEVICE_OPTIX: return CYCLES_BRIDGE_DEVICE_OPTIX;
        case ccl::DEVICE_CUDA: return CYCLES_BRIDGE_DEVICE_CUDA;
        case ccl::DEVICE_CPU: return CYCLES_BRIDGE_DEVICE_CPU;
        default: return 0;
    }
}

inline bool device_matches_policy(
    const ccl::DeviceInfo& device,
    std::uint32_t policy) {
    return policy == 0U
        || (policy == 1U && device.type == ccl::DEVICE_OPTIX)
        || (policy == 2U && device.type == ccl::DEVICE_CUDA)
        || (policy == 3U && device.type == ccl::DEVICE_CPU);
}

inline std::uint32_t device_diagnostic_id(const ccl::DeviceInfo& device) {
    switch (device.type) {
        case ccl::DEVICE_OPTIX: return 1U;
        case ccl::DEVICE_CUDA: return 2U;
        case ccl::DEVICE_CPU: return 3U;
        default: return 0U;
    }
}

inline ccl::SessionParams make_session_params(
    const ccl::DeviceInfo& device,
    bool use_graphics_interop) {
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

inline DenoiserSchedule configure_scene_settings(
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
    // Keep Blender's Fast GI Approximation disabled so true diffuse bounces
    // cannot silently be replaced with ambient occlusion.
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

inline void create_output_passes(
    ccl::Scene* scene,
    std::uint64_t registered_pass_mask) {
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

}  // namespace cyclesrenderer::session_config

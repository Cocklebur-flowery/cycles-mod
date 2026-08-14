#include "cycles_bridge_smoke_support.h"

#include <Windows.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <string>
#include <vector>

namespace cyclesrenderer::smoke {

bool require_ok(std::uint32_t status, const char* operation) {
    if (status == CYCLES_BRIDGE_STATUS_OK) {
        return true;
    }
    std::cerr << operation << " failed with status " << status << '\n';
    return false;
}

std::string renderer_info(const CyclesBridgeRenderer* renderer) {
    std::array<char, 512> output{};
    if (!require_ok(
            cycles_bridge_write_renderer_info(
                renderer, output.data(), static_cast<std::uint32_t>(output.size())),
            "renderer info")) {
        return {};
    }
    return output.data();
}

std::uint64_t checksum(const std::vector<std::uint8_t>& pixels) {
    std::uint64_t result = 1469598103934665603ULL;
    for (const std::uint8_t value : pixels) {
        result ^= value;
        result *= 1099511628211ULL;
    }
    return result;
}

bool has_rgb_variation(const std::vector<std::uint8_t>& pixels) {
    if (pixels.size() < 8U) {
        return false;
    }
    for (std::size_t offset = 4U; offset + 2U < pixels.size(); offset += 4U) {
        if (pixels[offset] != pixels[0]
            || pixels[offset + 1U] != pixels[1U]
            || pixels[offset + 2U] != pixels[2U]) {
            return true;
        }
    }
    return false;
}

bool has_green_dominant_pixel(const std::vector<std::uint8_t>& pixels) {
    for (std::size_t offset = 0; offset + 3U < pixels.size(); offset += 4U) {
        const int red = pixels[offset];
        const int green = pixels[offset + 1U];
        const int blue = pixels[offset + 2U];
        if (green > red + 16 && green > blue + 16) {
            return true;
        }
    }
    return false;
}

bool wait_for_updated_frame(
    CyclesBridgeRenderer* renderer,
    CyclesBridgeCamera& camera,
    CyclesBridgeFrame& frame,
    std::vector<std::uint8_t>& pixels,
    const char* stage,
    std::string& info,
    bool require_green,
    int expected_pass) {
    for (int attempt = 0; attempt < 1200; ++attempt) {
        camera.frame_id++;
        if (!require_ok(
                cycles_bridge_render_frame(
                    renderer, &camera, &frame, pixels.data(), pixels.size()),
                "frame render")) {
            info = renderer_info(renderer);
            std::cerr << info << '\n';
            return false;
        }
        info = renderer_info(renderer);
        if (attempt % 50 == 0) {
            CyclesBridgeDiagnostics progress{};
            progress.struct_size = sizeof(progress);
            progress.struct_version = 1;
            cycles_bridge_query_diagnostics(renderer, &progress);
            std::cerr << "[smoke] " << stage << ": " << info
                      << ";sample=" << progress.sample_count << '/'
                      << progress.target_sample_count
                      << ";camera=" << progress.camera_revision
                      << ";produced=" << progress.produced_frame_count
                      << ";starts=" << progress.render_start_count << '\n';
        }
        if ((frame.flags & CYCLES_BRIDGE_FRAME_READY) != 0U
            && (frame.flags & CYCLES_BRIDGE_FRAME_UPDATED) != 0U) {
            CyclesBridgeDiagnostics diagnostics{};
            diagnostics.struct_size = sizeof(diagnostics);
            diagnostics.struct_version = 1;
            const bool pass_matches = expected_pass < 0
                || (require_ok(
                        cycles_bridge_query_diagnostics(renderer, &diagnostics),
                        "frame pass diagnostics")
                    && diagnostics.active_pass == static_cast<std::uint32_t>(expected_pass));
            if (pass_matches && (!require_green || has_green_dominant_pixel(pixels))) {
                return true;
            }
        }
        Sleep(100);
    }
    std::cerr << stage << " did not produce an updated frame before timeout: "
              << info << '\n';
    return false;
}

bool wait_for_checksum_change(
    CyclesBridgeRenderer* renderer,
    CyclesBridgeCamera& camera,
    CyclesBridgeFrame& frame,
    std::vector<std::uint8_t>& pixels,
    std::uint64_t previous_checksum,
    const char* stage,
    std::string& info) {
    for (int attempt = 0; attempt < 300; ++attempt) {
        camera.frame_id++;
        if (!require_ok(
                cycles_bridge_render_frame(
                    renderer, &camera, &frame, pixels.data(), pixels.size()),
                "frame render")) {
            info = renderer_info(renderer);
            std::cerr << info << '\n';
            return false;
        }
        info = renderer_info(renderer);
        if (attempt % 50 == 0) {
            std::cerr << "[smoke] " << stage << ": " << info << '\n';
        }
        if ((frame.flags & CYCLES_BRIDGE_FRAME_UPDATED) != 0U
            && checksum(pixels) != previous_checksum) {
            return true;
        }
        Sleep(100);
    }
    std::cerr << stage << " did not change the frame checksum\n";
    return false;
}

bool wait_for_empty_scene_frame(
    CyclesBridgeRenderer* renderer,
    CyclesBridgeCamera& camera,
    CyclesBridgeFrame& frame,
    std::vector<std::uint8_t>& pixels,
    std::uint64_t previous_generation,
    const char* stage,
    std::string& info) {
    for (int attempt = 0; attempt < 300; ++attempt) {
        camera.frame_id++;
        if (!require_ok(
                cycles_bridge_render_frame(
                    renderer, &camera, &frame, pixels.data(), pixels.size()),
                "frame render")) {
            info = renderer_info(renderer);
            std::cerr << info << '\n';
            return false;
        }
        info = renderer_info(renderer);
        if (attempt % 50 == 0) {
            std::cerr << "[smoke] " << stage << ": " << info << '\n';
        }
        if ((frame.flags & CYCLES_BRIDGE_FRAME_UPDATED) != 0U
            && frame.generation > previous_generation) {
            CyclesBridgeDiagnostics diagnostics{};
            diagnostics.struct_size = sizeof(CyclesBridgeDiagnostics);
            diagnostics.struct_version = 1;
            if (!require_ok(
                    cycles_bridge_query_diagnostics(renderer, &diagnostics),
                    "empty scene diagnostics")) {
                return false;
            }
            if (diagnostics.section_count == 0U) {
                return true;
            }
        }
        Sleep(100);
    }
    std::cerr << stage << " did not produce an updated empty-scene frame\n";
    return false;
}

bool wait_for_frame_dimensions(
    CyclesBridgeRenderer* renderer,
    CyclesBridgeCamera& camera,
    CyclesBridgeFrame& frame,
    std::vector<std::uint8_t>& pixels,
    std::uint32_t expected_width,
    std::uint32_t expected_height,
    const char* stage,
    std::string& info) {
    for (int attempt = 0; attempt < 400; ++attempt) {
        camera.frame_id++;
        if (!require_ok(
                cycles_bridge_render_frame(
                    renderer, &camera, &frame, pixels.data(), pixels.size()),
                "frame render")) {
            info = renderer_info(renderer);
            std::cerr << info << '\n';
            return false;
        }
        info = renderer_info(renderer);
        if ((frame.flags & CYCLES_BRIDGE_FRAME_UPDATED) != 0U
            && frame.width == expected_width
            && frame.height == expected_height) {
            return true;
        }
        Sleep(10);
    }
    std::cerr << stage << " did not produce " << expected_width << 'x'
              << expected_height << ";actual=" << frame.width << 'x'
              << frame.height << ";info=" << info << '\n';
    return false;
}

CyclesBridgeRenderSettings default_settings() {
    CyclesBridgeRenderSettings settings{};
    settings.struct_size = sizeof(settings);
    settings.struct_version = 1;
    settings.revision = 1;
    settings.render_width = 480;
    settings.render_height = 270;
    settings.resolution_percentage = 100;
    settings.interactive_resolution_percentage = 50;
    settings.pass_cache_megabytes = 256;
    settings.sampling_pattern = CYCLES_BRIDGE_SAMPLING_PATTERN_BLUE_NOISE_FIRST;
    settings.camera_clip_near = 0.05F;
    settings.camera_clip_far = 0.0F;
    settings.projection_mode = CYCLES_BRIDGE_PROJECTION_PHYSICAL_LENS;
    settings.focal_length_mm = 18.0F;
    settings.sensor_width_mm = 36.0F;
    settings.depth_of_field = 1U;
    settings.focus_distance = 8.0F;
    settings.f_stop = 4.0F;
    settings.aperture_blades = 6U;
    settings.aperture_rotation_degrees = 15.0F;
    settings.aperture_ratio = 1.2F;
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
    settings.pbr_height_strength = 1.0F;
    settings.pbr_height_distance = 0.05F;
    settings.pbr_height_mapping_mode = CYCLES_BRIDGE_HEIGHT_MAPPING_PARALLAX_OCCLUSION;
    settings.pbr_parallax_steps = 4U;
    settings.working_space = CYCLES_BRIDGE_WORKING_SPACE_LINEAR_REC709;
    settings.dlss_quality_mode = CYCLES_BRIDGE_DLSS_QUALITY_QUALITY;
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
    settings.still_samples = 1;
    settings.stationary_delay_millis = 150;
    settings.noise_threshold = 0.01F;
    settings.maximum_bounce = 3;
    settings.diffuse_bounces = 2;
    settings.glossy_bounces = 1;
    settings.clamp_indirect = 10.0F;
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

bool wait_for_settings(CyclesBridgeRenderer* renderer, std::uint64_t revision) {
    CyclesBridgeDiagnostics diagnostics{};
    diagnostics.struct_size = sizeof(diagnostics);
    diagnostics.struct_version = 1;
    for (int attempt = 0; attempt < 200; ++attempt) {
        if (!require_ok(
                cycles_bridge_query_diagnostics(renderer, &diagnostics),
                "settings diagnostics")) {
            return false;
        }
        if (diagnostics.settings_revision == revision) {
            return true;
        }
        Sleep(10);
    }
    std::cerr << "settings revision " << revision << " was not activated\n";
    return false;
}

bool wait_for_actual_sample(
    CyclesBridgeRenderer* renderer,
    CyclesBridgeDiagnostics& diagnostics) {
    for (int attempt = 0; attempt < 200; ++attempt) {
        if (!require_ok(
                cycles_bridge_query_diagnostics(renderer, &diagnostics),
                "sampling diagnostics")) {
            return false;
        }
        if (diagnostics.sample_count > 0U) {
            return true;
        }
        Sleep(10);
    }
    std::cerr << "native diagnostics never reported a completed sample\n";
    return false;
}

bool wait_for_denoised_still(
    CyclesBridgeRenderer* renderer,
    CyclesBridgeCamera& camera,
    CyclesBridgeFrame& frame,
    std::vector<std::uint8_t>& pixels,
    CyclesBridgeDiagnostics& diagnostics,
    std::string& info,
    std::uint32_t expected_denoiser,
    const char* denoiser_name) {
    for (int attempt = 0; attempt < 400; ++attempt) {
        camera.frame_id++;
        if (!require_ok(
                cycles_bridge_render_frame(
                    renderer, &camera, &frame, pixels.data(), pixels.size()),
                "denoised still frame")
            || !require_ok(
                cycles_bridge_query_diagnostics(renderer, &diagnostics),
                "denoised still diagnostics")) {
            return false;
        }
        info = renderer_info(renderer);
        if (diagnostics.sampling_state == CYCLES_BRIDGE_SAMPLING_STILL
            && diagnostics.effective_denoiser == expected_denoiser
            && diagnostics.selected_denoiser == expected_denoiser
            && diagnostics.denoiser_scheduled != 0U
            && diagnostics.effective_denoiser_start_sample == 1U
            && diagnostics.denoiser_schedule_reason
                == CYCLES_BRIDGE_DENOISER_SCHEDULE_STILL
            && diagnostics.denoiser_schedule_run_count > 0U
            && diagnostics.active_frame_variant
                == CYCLES_BRIDGE_FRAME_VARIANT_DENOISED
            && (frame.flags & CYCLES_BRIDGE_FRAME_UPDATED) != 0U) {
            return true;
        }
        Sleep(10);
    }
    std::cerr << denoiser_name << " denoiser never produced a Still frame: " << info
              << ";state=" << diagnostics.sampling_state
              << ";effective=" << diagnostics.effective_denoiser
              << ";selected=" << diagnostics.selected_denoiser
              << ";scheduled=" << diagnostics.denoiser_scheduled
              << ";start=" << diagnostics.effective_denoiser_start_sample
              << ";reason=" << diagnostics.denoiser_schedule_reason
              << ";run/skip=" << diagnostics.denoiser_schedule_run_count
              << '/' << diagnostics.denoiser_schedule_skip_count
              << ";variant=" << diagnostics.active_frame_variant << '\n';
    return false;
}

bool wait_for_realtime_dlss(
    CyclesBridgeRenderer* renderer,
    CyclesBridgeCamera& camera,
    CyclesBridgeFrame& frame,
    std::vector<std::uint8_t>& pixels,
    CyclesBridgeDiagnostics& diagnostics,
    std::string& info) {
    for (int attempt = 0; attempt < 400; ++attempt) {
        camera.frame_id++;
        if (!require_ok(
                cycles_bridge_render_frame(
                    renderer, &camera, &frame, pixels.data(), pixels.size()),
                "DLSS realtime frame")
            || !require_ok(
                cycles_bridge_query_diagnostics(renderer, &diagnostics),
                "DLSS realtime diagnostics")) {
            return false;
        }
        info = renderer_info(renderer);
        if (diagnostics.effective_denoiser == 3U
            && diagnostics.selected_denoiser == 3U
            && diagnostics.denoiser_scheduled != 0U
            && diagnostics.effective_denoiser_start_sample == 0U
            && diagnostics.denoiser_schedule_reason
                == CYCLES_BRIDGE_DENOISER_SCHEDULE_REALTIME
            && diagnostics.denoiser_schedule_run_count > 0U
            && diagnostics.active_frame_variant
                == CYCLES_BRIDGE_FRAME_VARIANT_DENOISED
            && (frame.flags & CYCLES_BRIDGE_FRAME_UPDATED) != 0U) {
            return true;
        }
        Sleep(10);
    }
    std::cerr << "DLSS never produced a realtime denoised frame: " << info
              << ";state=" << diagnostics.sampling_state
              << ";effective=" << diagnostics.effective_denoiser
              << ";selected=" << diagnostics.selected_denoiser
              << ";scheduled=" << diagnostics.denoiser_scheduled
              << ";start=" << diagnostics.effective_denoiser_start_sample
              << ";reason=" << diagnostics.denoiser_schedule_reason
              << ";run/skip=" << diagnostics.denoiser_schedule_run_count
              << '/' << diagnostics.denoiser_schedule_skip_count
              << ";variant=" << diagnostics.active_frame_variant << '\n';
    return false;
}

bool verify_progressive_sampling(
    CyclesBridgeRenderer* renderer,
    CyclesBridgeCamera& camera,
    CyclesBridgeDiagnostics& diagnostics) {
    camera.position_x += 0.125;
    bool saw_interactive = false;
    bool saw_settling = false;
    bool saw_still = false;
    std::uint64_t still_start_generation = 0U;
    for (int attempt = 0; attempt < 200; ++attempt) {
        camera.frame_id++;
        if (!require_ok(
                cycles_bridge_update_camera(renderer, &camera),
                "progressive camera update")
            || !require_ok(
                cycles_bridge_query_diagnostics(renderer, &diagnostics),
                "progressive diagnostics")) {
            return false;
        }
        saw_interactive |=
            diagnostics.sampling_state == CYCLES_BRIDGE_SAMPLING_INTERACTIVE;
        saw_settling |=
            diagnostics.sampling_state == CYCLES_BRIDGE_SAMPLING_SETTLING;
        if (diagnostics.sampling_state == CYCLES_BRIDGE_SAMPLING_STILL) {
            if (!saw_still) {
                saw_still = true;
                still_start_generation = diagnostics.frame_generation;
            } else if (diagnostics.frame_generation != still_start_generation) {
                return saw_interactive && saw_settling
                    && diagnostics.settling_remaining_millis == 0U
                    && diagnostics.sampling_transition_count >= 3U;
            }
        }
        Sleep(10);
    }
    std::cerr << "progressive sampler did not reach Still; state="
              << diagnostics.sampling_state
              << ";remaining=" << diagnostics.settling_remaining_millis
              << ";transitions=" << diagnostics.sampling_transition_count << '\n';
    return false;
}

}  // namespace cyclesrenderer::smoke

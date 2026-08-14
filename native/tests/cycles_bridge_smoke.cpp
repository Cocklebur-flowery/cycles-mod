#include "cycles_bridge.h"

#include <Windows.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <string>
#include <vector>

namespace {

constexpr std::uint32_t kWidth = 320;
constexpr std::uint32_t kHeight = 180;

bool require_ok(std::uint32_t status, const char* operation) {
    if (status == CYCLES_BRIDGE_STATUS_OK) {
        return true;
    }
    std::cerr << operation << " failed with status " << status << '\n';
    return false;
}

bool duplicate_win32_handle(HANDLE source, HANDLE& duplicate) {
    return DuplicateHandle(
        GetCurrentProcess(),
        source,
        GetCurrentProcess(),
        &duplicate,
        0U,
        FALSE,
        DUPLICATE_SAME_ACCESS) != FALSE;
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
    bool require_green = false,
    int expected_pass = -1) {
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

}  // namespace

int main(int argc, char** argv) {
    const bool require_optix = argc > 1 && std::strcmp(argv[1], "--require-optix") == 0;
    std::cerr << "[smoke] ABI check\n";
    if (cycles_bridge_abi_version() != 38U) {
        std::cerr << "unexpected native ABI " << cycles_bridge_abi_version() << '\n';
        return 1;
    }
    for (std::uint32_t pass = 0; pass < CYCLES_BRIDGE_PASS_COUNT; ++pass) {
        CyclesBridgePassDescriptor descriptor{};
        descriptor.struct_size = sizeof(descriptor);
        descriptor.struct_version = 1;
        if (!require_ok(
                cycles_bridge_query_pass_descriptor(pass, &descriptor),
                "pass descriptor query")
            || descriptor.pass_id != pass
            || descriptor.source_component_count == 0U
            || descriptor.display_component_count != 4U
            || descriptor.pixel_format != CYCLES_BRIDGE_PIXEL_FORMAT_RGBA16_FLOAT
            || descriptor.semantic == 0U
            || (descriptor.flags & CYCLES_BRIDGE_PASS_DISPLAYABLE) == 0U
            || (descriptor.flags & CYCLES_BRIDGE_PASS_CACHE_RAW) == 0U) {
            std::cerr << "invalid descriptor for pass " << pass << '\n';
            return 1;
        }
    }

    CyclesBridgeRenderer* renderer = nullptr;
    std::cerr << "[smoke] Creating renderer\n";
    if (!require_ok(cycles_bridge_create_renderer(&renderer), "renderer creation")
        || renderer == nullptr) {
        return 1;
    }

    CyclesBridgeCapabilities capabilities{};
    capabilities.struct_size = sizeof(capabilities);
    capabilities.struct_version = 1;
    CyclesBridgeRenderSettings settings = default_settings();
    CyclesBridgeDiagnostics initial_diagnostics{};
    initial_diagnostics.struct_size = sizeof(initial_diagnostics);
    initial_diagnostics.struct_version = 1;
    if (!require_ok(
            cycles_bridge_query_diagnostics(renderer, &initial_diagnostics),
            "initial diagnostics")) {
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    CyclesBridgeVulkanInteropBuffer interop{};
    interop.struct_size = sizeof(interop);
    interop.struct_version = 1;
    interop.width = 480U;
    interop.height = 270U;
    interop.pixel_format = CYCLES_BRIDGE_PIXEL_FORMAT_RGBA16_FLOAT;
    interop.flags = CYCLES_BRIDGE_VULKAN_INTEROP_OWNERSHIP_TRANSFER;
    interop.allocation_byte_count = 480ULL * 270ULL * 8ULL * 3ULL;
    interop.slot_count = 3U;
    interop.slot_stride_bytes = 480U * 270U * 8U;
    std::memcpy(
        interop.device_uuid,
        initial_diagnostics.device_uuid,
        sizeof(interop.device_uuid));
    HANDLE accepted_handle = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    HANDLE accepted_ready_handle = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    HANDLE accepted_release_handle = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    if (accepted_handle == nullptr || accepted_ready_handle == nullptr
        || accepted_release_handle == nullptr) {
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    HANDLE session_handle = nullptr;
    HANDLE session_ready_handle = nullptr;
    HANDLE session_release_handle = nullptr;
    if (initial_diagnostics.device_uuid_valid != 0U
        && (!duplicate_win32_handle(accepted_handle, session_handle)
            || !duplicate_win32_handle(
                accepted_ready_handle, session_ready_handle)
            || !duplicate_win32_handle(
                accepted_release_handle, session_release_handle))) {
        std::cerr << "failed to duplicate interop handles for session ownership test\n";
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    interop.memory_handle = static_cast<std::uint64_t>(
        reinterpret_cast<std::uintptr_t>(accepted_handle));
    interop.ready_semaphore_handle = static_cast<std::uint64_t>(
        reinterpret_cast<std::uintptr_t>(accepted_ready_handle));
    interop.release_semaphore_handle = static_cast<std::uint64_t>(
        reinterpret_cast<std::uintptr_t>(accepted_release_handle));
    const std::uint32_t bind_status =
        cycles_bridge_bind_vulkan_interop_buffer(renderer, &interop);
    if (initial_diagnostics.device_uuid_valid != 0U) {
        CyclesBridgeVulkanInteropState interop_state{};
        interop_state.struct_size = sizeof(interop_state);
        interop_state.struct_version = 1U;
        if (!require_ok(bind_status, "interop handle bind")
            || !require_ok(
                cycles_bridge_query_vulkan_interop_state(
                    renderer, &interop_state),
                "interop state query")
            || (interop_state.flags & CYCLES_BRIDGE_VULKAN_INTEROP_BOUND) == 0U) {
            std::cerr << "interop handle ownership was not transferred and closed\n";
            cycles_bridge_destroy_renderer(renderer);
            return 1;
        }
        HANDLE duplicate_handle = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        HANDLE duplicate_ready_handle = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        HANDLE duplicate_release_handle = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        if (duplicate_handle == nullptr || duplicate_ready_handle == nullptr
            || duplicate_release_handle == nullptr) {
            cycles_bridge_destroy_renderer(renderer);
            return 1;
        }
        interop.memory_handle = static_cast<std::uint64_t>(
            reinterpret_cast<std::uintptr_t>(duplicate_handle));
        interop.ready_semaphore_handle = static_cast<std::uint64_t>(
            reinterpret_cast<std::uintptr_t>(duplicate_ready_handle));
        interop.release_semaphore_handle = static_cast<std::uint64_t>(
            reinterpret_cast<std::uintptr_t>(duplicate_release_handle));
        if (cycles_bridge_bind_vulkan_interop_buffer(renderer, &interop)
                != CYCLES_BRIDGE_STATUS_RENDER_ERROR
            || CloseHandle(duplicate_handle) != FALSE
            || CloseHandle(duplicate_ready_handle) != FALSE
            || CloseHandle(duplicate_release_handle) != FALSE) {
            std::cerr << "duplicate interop handles were not rejected and closed\n";
            cycles_bridge_destroy_renderer(renderer);
            return 1;
        }
        CyclesBridgeVulkanInteropState acquired_state{};
        acquired_state.struct_size = sizeof(acquired_state);
        acquired_state.struct_version = 1U;
        if (!require_ok(
                cycles_bridge_acquire_vulkan_interop_frame(
                    renderer, 0U, &acquired_state),
                "empty interop frame acquire")
            || (acquired_state.flags
                & CYCLES_BRIDGE_VULKAN_INTEROP_FRAME_ACQUIRED) != 0U
            || cycles_bridge_release_vulkan_interop_frame(renderer, 1U)
                != CYCLES_BRIDGE_STATUS_RENDER_ERROR
            || !require_ok(
                cycles_bridge_unbind_vulkan_interop_buffer(renderer),
                "interop handle unbind")
            || CloseHandle(accepted_handle) != FALSE
            || CloseHandle(accepted_ready_handle) != FALSE
            || CloseHandle(accepted_release_handle) != FALSE) {
            std::cerr << "empty interop frame ownership was not rejected\n";
            cycles_bridge_destroy_renderer(renderer);
            return 1;
        }
        if (SetEvent(session_handle) == FALSE
            || SetEvent(session_ready_handle) == FALSE
            || SetEvent(session_release_handle) == FALSE
            || CloseHandle(session_handle) == FALSE
            || CloseHandle(session_ready_handle) == FALSE
            || CloseHandle(session_release_handle) == FALSE) {
            std::cerr << "session interop handle copies did not retain independent ownership\n";
            cycles_bridge_destroy_renderer(renderer);
            return 1;
        }
        HANDLE rejected_handle = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        HANDLE rejected_ready_handle = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        HANDLE rejected_release_handle = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        if (rejected_handle == nullptr || rejected_ready_handle == nullptr
            || rejected_release_handle == nullptr) {
            cycles_bridge_destroy_renderer(renderer);
            return 1;
        }
        interop.device_uuid[0] ^= 0xFFU;
        interop.memory_handle = static_cast<std::uint64_t>(
            reinterpret_cast<std::uintptr_t>(rejected_handle));
        interop.ready_semaphore_handle = static_cast<std::uint64_t>(
            reinterpret_cast<std::uintptr_t>(rejected_ready_handle));
        interop.release_semaphore_handle = static_cast<std::uint64_t>(
            reinterpret_cast<std::uintptr_t>(rejected_release_handle));
        if (cycles_bridge_bind_vulkan_interop_buffer(renderer, &interop)
                != CYCLES_BRIDGE_STATUS_RENDER_ERROR
            || CloseHandle(rejected_handle) != FALSE
            || CloseHandle(rejected_ready_handle) != FALSE
            || CloseHandle(rejected_release_handle) != FALSE) {
            std::cerr << "UUID-mismatched interop handles were not rejected and closed\n";
            cycles_bridge_destroy_renderer(renderer);
            return 1;
        }
    } else if (bind_status != CYCLES_BRIDGE_STATUS_RENDER_ERROR
               || CloseHandle(accepted_handle) != FALSE) {
        std::cerr << "UUID-less interop handle was not rejected and closed\n";
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    if (!require_ok(
            cycles_bridge_query_capabilities(renderer, &capabilities),
            "capability query")
        || (capabilities.capability_flags & CYCLES_BRIDGE_CAPABILITY_SETTINGS) == 0U
        || (capabilities.capability_flags & CYCLES_BRIDGE_CAPABILITY_PASS_VIEWER) == 0U
        || capabilities.pass_mask != ((1ULL << CYCLES_BRIDGE_PASS_COUNT) - 1ULL)
        || capabilities.color_config_state != CYCLES_BRIDGE_COLOR_CONFIG_READY
        || capabilities.color_lut_edge_length != 64U
        || capabilities.color_lut_pixel_format != CYCLES_BRIDGE_PIXEL_FORMAT_RGBA32_FLOAT
        || (capabilities.color_transform_mask
            & (1U << CYCLES_BRIDGE_VIEW_TRANSFORM_AGX)) == 0U
        || (capabilities.color_transform_mask
            & (1U << CYCLES_BRIDGE_VIEW_TRANSFORM_KHRONOS_PBR_NEUTRAL)) == 0U
        || (capabilities.color_transform_mask
            & (1U << CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_2)) == 0U
        || !require_ok(
            cycles_bridge_apply_settings(renderer, &settings),
            "initial settings")) {
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }

    for (std::uint32_t view = CYCLES_BRIDGE_VIEW_TRANSFORM_STANDARD;
         view <= CYCLES_BRIDGE_VIEW_TRANSFORM_AGX_HDR_1000;
         ++view) {
        settings.view_transform = view;
        settings.revision++;
        if (!require_ok(
                cycles_bridge_apply_settings(renderer, &settings),
                "color view settings")) {
            cycles_bridge_destroy_renderer(renderer);
            return 1;
        }
    }
    CyclesBridgeRenderSettings invalid_view = settings;
    invalid_view.view_transform = CYCLES_BRIDGE_VIEW_TRANSFORM_AGX_HDR_1000 + 1U;
    invalid_view.revision++;
    if (cycles_bridge_apply_settings(renderer, &invalid_view)
        != CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT) {
        std::cerr << "invalid color view was accepted\n";
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    settings.view_transform = CYCLES_BRIDGE_VIEW_TRANSFORM_AGX;
    for (std::uint32_t working_space = CYCLES_BRIDGE_WORKING_SPACE_LINEAR_REC709;
         working_space <= CYCLES_BRIDGE_WORKING_SPACE_ACESCG;
         ++working_space) {
        settings.working_space = working_space;
        settings.revision++;
        if (!require_ok(
                cycles_bridge_apply_settings(renderer, &settings),
                "working-space settings")) {
            cycles_bridge_destroy_renderer(renderer);
            return 1;
        }
    }
    settings.working_space = CYCLES_BRIDGE_WORKING_SPACE_LINEAR_REC709;

    for (std::uint32_t pattern = CYCLES_BRIDGE_SAMPLING_PATTERN_SOBOL_BURLEY;
         pattern <= CYCLES_BRIDGE_SAMPLING_PATTERN_BLUE_NOISE_ROUND;
         ++pattern) {
        settings.sampling_pattern = pattern;
        settings.revision++;
        if (!require_ok(
                cycles_bridge_apply_settings(renderer, &settings),
                "sampling pattern settings")) {
            cycles_bridge_destroy_renderer(renderer);
            return 1;
        }
    }
    CyclesBridgeRenderSettings invalid_sampling = settings;
    invalid_sampling.sampling_pattern =
        CYCLES_BRIDGE_SAMPLING_PATTERN_BLUE_NOISE_ROUND + 1U;
    invalid_sampling.revision++;
    if (cycles_bridge_apply_settings(renderer, &invalid_sampling)
        != CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT) {
        std::cerr << "invalid sampling pattern was accepted\n";
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    settings.sampling_pattern = CYCLES_BRIDGE_SAMPLING_PATTERN_BLUE_NOISE_FIRST;
    for (std::uint32_t dlss_mode = CYCLES_BRIDGE_DLSS_QUALITY_DLAA;
         dlss_mode <= CYCLES_BRIDGE_DLSS_QUALITY_ULTRA_PERFORMANCE;
         ++dlss_mode) {
        settings.dlss_quality_mode = dlss_mode;
        settings.revision++;
        if (!require_ok(
                cycles_bridge_apply_settings(renderer, &settings),
                "DLSS quality mode settings")) {
            cycles_bridge_destroy_renderer(renderer);
            return 1;
        }
    }
    CyclesBridgeRenderSettings invalid_dlss_mode = settings;
    invalid_dlss_mode.dlss_quality_mode =
        CYCLES_BRIDGE_DLSS_QUALITY_ULTRA_PERFORMANCE + 1U;
    invalid_dlss_mode.revision++;
    if (cycles_bridge_apply_settings(renderer, &invalid_dlss_mode)
        != CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT) {
        std::cerr << "invalid DLSS quality mode was accepted\n";
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    settings.dlss_quality_mode = CYCLES_BRIDGE_DLSS_QUALITY_QUALITY;
    settings.camera_clip_near = 0.125F;
    settings.camera_clip_far = 50.0F;
    settings.revision++;
    if (!require_ok(
            cycles_bridge_apply_settings(renderer, &settings),
            "Blue Noise First settings")) {
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }

    std::array<char, 1024> color_info{};
    if (!require_ok(
            cycles_bridge_write_color_management_info(
                renderer,
                color_info.data(),
                static_cast<std::uint32_t>(color_info.size())),
            "color management info")
        || std::strstr(color_info.data(), "state=ready") == nullptr
        || std::strstr(color_info.data(), "displays=6") == nullptr) {
        std::cerr << "invalid color management info: " << color_info.data() << '\n';
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }

    CyclesBridgeColorLutDescriptor color_lut{};
    color_lut.struct_size = sizeof(color_lut);
    color_lut.struct_version = 1;
    if (!require_ok(
            cycles_bridge_query_color_lut(
                renderer,
                CYCLES_BRIDGE_DISPLAY_SRGB,
                CYCLES_BRIDGE_VIEW_TRANSFORM_AGX,
                CYCLES_BRIDGE_COLOR_LOOK_AGX_PUNCHY,
                CYCLES_BRIDGE_WORKING_SPACE_LINEAR_REC709,
                &color_lut,
                nullptr,
                0U),
            "AgX LUT descriptor")
        || color_lut.edge_length != 64U
        || color_lut.width != color_lut.edge_length * color_lut.edge_length
        || color_lut.height != color_lut.edge_length
        || color_lut.pixel_format != CYCLES_BRIDGE_PIXEL_FORMAT_RGBA32_FLOAT
        || color_lut.color_look != CYCLES_BRIDGE_COLOR_LOOK_AGX_PUNCHY
        || color_lut.working_space != CYCLES_BRIDGE_WORKING_SPACE_LINEAR_REC709
        || color_lut.display_device != CYCLES_BRIDGE_DISPLAY_SRGB
        || color_lut.pixel_byte_count
            != static_cast<std::uint64_t>(color_lut.width) * color_lut.height
                * 4U * sizeof(float)) {
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }

    const auto verify_color_pipeline = [&](std::uint32_t display,
                                           std::uint32_t view,
                                           std::uint32_t look,
                                           const char* label) {
        CyclesBridgeColorLutDescriptor descriptor{};
        descriptor.struct_size = sizeof(descriptor);
        descriptor.struct_version = 1;
        return require_ok(
                   cycles_bridge_query_color_lut(
                       renderer, display, view, look,
                       CYCLES_BRIDGE_WORKING_SPACE_LINEAR_REC709,
                       &descriptor, nullptr, 0U),
                   label)
            && descriptor.display_device == display
            && descriptor.view_transform == view
            && descriptor.color_look == look;
    };
    if (!verify_color_pipeline(
            CYCLES_BRIDGE_DISPLAY_P3,
            CYCLES_BRIDGE_VIEW_TRANSFORM_AGX,
            CYCLES_BRIDGE_COLOR_LOOK_AGX_PUNCHY,
            "Display P3 AgX Punchy LUT")
        || !verify_color_pipeline(
            CYCLES_BRIDGE_DISPLAY_SRGB,
            CYCLES_BRIDGE_VIEW_TRANSFORM_FILMIC,
            CYCLES_BRIDGE_COLOR_LOOK_FILMIC_MEDIUM_CONTRAST,
            "sRGB Filmic Medium Contrast LUT")
        || !verify_color_pipeline(
            CYCLES_BRIDGE_DISPLAY_SRGB,
            CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_2,
            CYCLES_BRIDGE_COLOR_LOOK_ACES_2_GAMUT_COMPRESSION,
            "sRGB ACES 2 gamut compression LUT")
        || !verify_color_pipeline(
            CYCLES_BRIDGE_DISPLAY_REC2100_PQ,
            CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_2_HDR_1000,
            CYCLES_BRIDGE_COLOR_LOOK_NONE,
            "PQ ACES 2 HDR 1000 LUT")) {
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    CyclesBridgeColorLutDescriptor incompatible_look{};
    incompatible_look.struct_size = sizeof(incompatible_look);
    incompatible_look.struct_version = 1;
    if (!require_ok(
            cycles_bridge_query_color_lut(
                renderer,
                CYCLES_BRIDGE_DISPLAY_P3,
                CYCLES_BRIDGE_VIEW_TRANSFORM_STANDARD,
                CYCLES_BRIDGE_COLOR_LOOK_AGX_PUNCHY,
                CYCLES_BRIDGE_WORKING_SPACE_LINEAR_REC709,
                &incompatible_look,
                nullptr,
                0U),
            "incompatible look fallback")
        || incompatible_look.color_look != CYCLES_BRIDGE_COLOR_LOOK_NONE) {
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    std::vector<float> color_lut_pixels(
        static_cast<std::size_t>(color_lut.pixel_byte_count / sizeof(float)));
    color_lut.struct_size = sizeof(color_lut);
    color_lut.struct_version = 1;
    if (cycles_bridge_query_color_lut(
            renderer,
            CYCLES_BRIDGE_DISPLAY_SRGB,
            CYCLES_BRIDGE_VIEW_TRANSFORM_AGX,
            CYCLES_BRIDGE_COLOR_LOOK_AGX_PUNCHY,
            CYCLES_BRIDGE_WORKING_SPACE_LINEAR_REC709,
            &color_lut,
            color_lut_pixels.data(),
            sizeof(float)) != CYCLES_BRIDGE_STATUS_BUFFER_TOO_SMALL
        || !require_ok(
            cycles_bridge_query_color_lut(
                renderer,
                CYCLES_BRIDGE_DISPLAY_SRGB,
                CYCLES_BRIDGE_VIEW_TRANSFORM_AGX,
                CYCLES_BRIDGE_COLOR_LOOK_AGX_PUNCHY,
                CYCLES_BRIDGE_WORKING_SPACE_LINEAR_REC709,
                &color_lut,
                color_lut_pixels.data(),
                color_lut.pixel_byte_count),
            "AgX LUT pixels")) {
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    const std::size_t neutral_midpoint = (
        static_cast<std::size_t>(32U) * color_lut.width
        + static_cast<std::size_t>(32U) * color_lut.edge_length
        + 32U) * 4U;
    if (!std::isfinite(color_lut_pixels[neutral_midpoint])
        || std::abs(color_lut_pixels[neutral_midpoint] - 0.941668F) > 0.0001F
        || std::abs(color_lut_pixels[neutral_midpoint + 3U] - 1.0F) > 0.0001F) {
        std::cerr << "unexpected AgX LUT midpoint "
                  << color_lut_pixels[neutral_midpoint] << '\n';
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    for (const std::uint32_t working_space : {
             CYCLES_BRIDGE_WORKING_SPACE_LINEAR_REC2020,
             CYCLES_BRIDGE_WORKING_SPACE_ACESCG}) {
        CyclesBridgeColorLutDescriptor working_lut{};
        working_lut.struct_size = sizeof(working_lut);
        working_lut.struct_version = 1;
        if (!require_ok(
                cycles_bridge_query_color_lut(
                    renderer,
                    CYCLES_BRIDGE_DISPLAY_SRGB,
                    CYCLES_BRIDGE_VIEW_TRANSFORM_AGX,
                    CYCLES_BRIDGE_COLOR_LOOK_AGX_PUNCHY,
                    working_space,
                    &working_lut,
                    nullptr,
                    0U),
                "wide-gamut AgX LUT descriptor")
            || working_lut.working_space != working_space
            || working_lut.pixel_byte_count != color_lut.pixel_byte_count) {
            cycles_bridge_destroy_renderer(renderer);
            return 1;
        }
    }
    CyclesBridgeColorLutDescriptor standard_lut{};
    standard_lut.struct_size = sizeof(standard_lut);
    standard_lut.struct_version = 1;
    if (!require_ok(
            cycles_bridge_query_color_lut(
                renderer,
                CYCLES_BRIDGE_DISPLAY_SRGB,
                CYCLES_BRIDGE_VIEW_TRANSFORM_STANDARD,
                CYCLES_BRIDGE_COLOR_LOOK_NONE,
                CYCLES_BRIDGE_WORKING_SPACE_ACESCG,
                &standard_lut,
                nullptr,
                0U),
            "ACEScg Standard LUT descriptor")
        || standard_lut.view_transform != CYCLES_BRIDGE_VIEW_TRANSFORM_STANDARD
        || standard_lut.working_space != CYCLES_BRIDGE_WORKING_SPACE_ACESCG) {
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }

    const std::array<CyclesBridgeVertex, 4> vertices = {{
        {-2.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0xFFFFFFFFU, 0U},
        {2.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 0.0F, 0xFFFFFFFFU, 0U},
        {2.0F, 4.0F, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 0xFFFFFFFFU, 0U},
        {-2.0F, 4.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 0xFFFFFFFFU, 0U},
    }};
    const std::array<CyclesBridgeTriangle, 2> triangles = {{
        {0U, 1U, 2U, 0U},
        {0U, 2U, 3U, 0U},
    }};
    const std::array<CyclesBridgeMaterial, 1> materials = {{
        {0U,
         CYCLES_BRIDGE_MATERIAL_CUTOUT,
         0.0F,
         0.5F,
         1U,
         2U,
         CYCLES_BRIDGE_PBR_LAB_1_3,
         3U},
    }};
    const std::array<std::uint8_t, 64> texture_pixels = {{
        255U, 64U, 32U, 255U,
        32U, 255U, 64U, 255U,
        32U, 64U, 255U, 255U,
        255U, 255U, 32U, 0U,
        128U, 128U, 255U, 255U,
        128U, 128U, 255U, 255U,
        128U, 128U, 255U, 255U,
        128U, 128U, 255U, 255U,
        204U, 0U, 10U, 0U,
        128U, 0U, 10U, 0U,
        64U, 255U, 10U, 0U,
        204U, 0U, 10U, 0U,
        255U, 128U, 10U, 0U,
        255U, 128U, 230U, 32U,
        192U, 192U, 231U, 96U,
        128U, 64U, 10U, 160U,
    }};
    const std::array<CyclesBridgeTexture, 4> textures = {{
        {2U,
         2U,
         0U,
         16U,
         CYCLES_BRIDGE_TEXTURE_COLOR_SRGB,
         {0U, 0U, 0U}},
        {2U, 2U, 16U, 16U, CYCLES_BRIDGE_TEXTURE_DATA_LINEAR, {0U, 0U, 0U}},
        {2U, 2U, 32U, 16U, CYCLES_BRIDGE_TEXTURE_DATA_LINEAR, {0U, 0U, 0U}},
        {2U, 2U, 48U, 16U, CYCLES_BRIDGE_TEXTURE_DATA_LINEAR, {0U, 0U, 0U}},
    }};
    CyclesBridgeSceneResources resources{};
    resources.struct_size = sizeof(resources);
    resources.struct_version = 1;
    resources.material_count = static_cast<std::uint32_t>(materials.size());
    resources.texture_count = static_cast<std::uint32_t>(textures.size());
    resources.texture_byte_count = static_cast<std::uint32_t>(texture_pixels.size());
    auto invalid_materials = materials;
    invalid_materials[0].normal_texture_index = CYCLES_BRIDGE_TEXTURE_INDEX_INVALID;
    if (cycles_bridge_reset_scene(
            renderer,
            &resources,
            invalid_materials.data(),
            textures.data(),
            texture_pixels.data()) != CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT) {
        std::cerr << "LabPBR material with missing data texture indexes was accepted\n";
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    invalid_materials = materials;
    invalid_materials[0].auxiliary_texture_index = CYCLES_BRIDGE_TEXTURE_INDEX_INVALID;
    if (cycles_bridge_reset_scene(
            renderer,
            &resources,
            invalid_materials.data(),
            textures.data(),
            texture_pixels.data()) != CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT) {
        std::cerr << "LabPBR material with missing auxiliary texture was accepted\n";
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    auto invalid_textures = textures;
    invalid_textures[0].role = CYCLES_BRIDGE_TEXTURE_DATA_LINEAR;
    if (cycles_bridge_reset_scene(
            renderer,
            &resources,
            materials.data(),
            invalid_textures.data(),
            texture_pixels.data()) != CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT) {
        std::cerr << "base color texture with a linear-data role was accepted\n";
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    CyclesBridgeSection section{};
    section.struct_size = sizeof(section);
    section.struct_version = 1;
    section.section_id = 42;
    section.vertex_count = static_cast<std::uint32_t>(vertices.size());
    section.triangle_count = static_cast<std::uint32_t>(triangles.size());

    std::cerr << "[smoke] Streaming textured section; " << renderer_info(renderer) << '\n';
    if (!require_ok(
            cycles_bridge_reset_scene(
                renderer,
                &resources,
                materials.data(),
                textures.data(),
                texture_pixels.data()),
            "scene reset")
        || !require_ok(
            cycles_bridge_upsert_section(
                renderer, &section, vertices.data(), triangles.data()),
            "section upsert")
        || !require_ok(cycles_bridge_commit_scene(renderer), "scene commit")) {
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }

    CyclesBridgeCamera camera{};
    camera.struct_size = sizeof(camera);
    camera.struct_version = 1;
    camera.viewport_width = kWidth;
    camera.viewport_height = kHeight;
    camera.position_x = 0.0;
    camera.position_y = 2.0;
    camera.position_z = 8.0;
    camera.rotation_w = 1.0F;
    camera.vertical_fov_radians = 1.04719755F;
    camera.depth_far = 100.0F;
    camera.frame_id++;
    if (!require_ok(
            cycles_bridge_update_camera(renderer, &camera),
            "camera update")) {
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }

    std::vector<std::uint8_t> pixels(
        static_cast<std::size_t>(kWidth) * kHeight * 4U);
    CyclesBridgeFrame frame{};
    frame.struct_size = sizeof(frame);
    frame.struct_version = 1;
    std::string info;
    if (!wait_for_updated_frame(
            renderer, camera, frame, pixels, "initial section", info, true)) {
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    if (require_optix && info.find("backend=OPTIX") == std::string::npos) {
        std::cerr << "OptiX was required but another backend was selected: " << info << '\n';
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    if (!has_rgb_variation(pixels)) {
        std::cerr << "completed frame contains only the background; camera may face away from the scene\n";
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    if (!has_green_dominant_pixel(pixels)) {
        std::cerr << "completed frame did not preserve the green texture channel\n";
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }

    std::cerr << "[smoke] Applying camera shift\n";
    const std::uint64_t unshifted_checksum = checksum(pixels);
    settings.camera_shift_x = 0.01F;
    settings.camera_shift_y = -0.005F;
    settings.revision++;
    CyclesBridgeDiagnostics shifted_diagnostics{};
    shifted_diagnostics.struct_size = sizeof(shifted_diagnostics);
    shifted_diagnostics.struct_version = 1;
    if (!require_ok(
            cycles_bridge_apply_settings(renderer, &settings),
            "camera shift settings")
        || !wait_for_settings(renderer, settings.revision)
        || !wait_for_updated_frame(
            renderer, camera, frame, pixels, "camera shift", info, false,
            CYCLES_BRIDGE_PASS_COMBINED)
        || !require_ok(
            cycles_bridge_query_diagnostics(renderer, &shifted_diagnostics),
            "camera shift diagnostics")
        || std::abs(shifted_diagnostics.camera_shift_x - settings.camera_shift_x) > 1.0e-6F
        || std::abs(shifted_diagnostics.camera_shift_y - settings.camera_shift_y) > 1.0e-6F
        || checksum(pixels) == unshifted_checksum) {
        std::cerr << "camera shift did not change the projection; shift="
                  << shifted_diagnostics.camera_shift_x << '/'
                  << shifted_diagnostics.camera_shift_y << '\n';
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    settings.camera_shift_x = 0.0F;
    settings.camera_shift_y = 0.0F;
    settings.revision++;
    if (!require_ok(
            cycles_bridge_apply_settings(renderer, &settings),
            "camera shift restore")
        || !wait_for_settings(renderer, settings.revision)
        || !wait_for_updated_frame(
            renderer, camera, frame, pixels, "camera shift restore", info, true,
            CYCLES_BRIDGE_PASS_COMBINED)) {
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }

    std::cerr << "[smoke] Applying autofocus distance override\n";
    camera.focus_distance = 3.0F;
    camera.flags = CYCLES_BRIDGE_CAMERA_FOCUS_DISTANCE_VALID;
    camera.frame_id++;
    CyclesBridgeDiagnostics autofocus_diagnostics{};
    autofocus_diagnostics.struct_size = sizeof(autofocus_diagnostics);
    autofocus_diagnostics.struct_version = 1;
    if (!require_ok(
            cycles_bridge_update_camera(renderer, &camera),
            "autofocus camera update")
        || !wait_for_updated_frame(
            renderer, camera, frame, pixels, "autofocus override", info, false,
            CYCLES_BRIDGE_PASS_COMBINED)
        || !require_ok(
            cycles_bridge_query_diagnostics(renderer, &autofocus_diagnostics),
            "autofocus diagnostics")
        || std::abs(autofocus_diagnostics.focus_distance - camera.focus_distance) > 1.0e-6F) {
        std::cerr << "autofocus override did not reach Cycles; focus="
                  << autofocus_diagnostics.focus_distance << '\n';
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    camera.focus_distance = 0.0F;
    camera.flags = 0U;

    for (std::uint32_t pass = CYCLES_BRIDGE_PASS_DEPTH;
         pass < CYCLES_BRIDGE_PASS_COUNT;
         ++pass) {
        settings.active_pass = pass;
        settings.revision++;
        const std::string stage = std::string("pass ") + std::to_string(pass);
        if (!require_ok(
                cycles_bridge_apply_settings(renderer, &settings),
                "pass settings")
            || !wait_for_settings(renderer, settings.revision)
            || !wait_for_updated_frame(
                renderer, camera, frame, pixels, stage.c_str(), info, false,
                static_cast<int>(pass))
            || frame.width != kWidth || frame.height != kHeight) {
            cycles_bridge_destroy_renderer(renderer);
            return 1;
        }
    }
    settings.active_pass = CYCLES_BRIDGE_PASS_COMBINED;
    settings.revision++;
    if (!require_ok(
            cycles_bridge_apply_settings(renderer, &settings),
            "combined pass restore")
        || !wait_for_settings(renderer, settings.revision)
        || !wait_for_updated_frame(
            renderer, camera, frame, pixels, "combined pass restore", info, true,
            CYCLES_BRIDGE_PASS_COMBINED)) {
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }

    std::cerr << "[smoke] Rendering all Cycles panorama camera types\n";
    settings.camera_type = CYCLES_BRIDGE_CAMERA_PANORAMA;
    for (std::uint32_t panorama_type = CYCLES_BRIDGE_PANORAMA_EQUIRECTANGULAR;
         panorama_type <= CYCLES_BRIDGE_PANORAMA_CENTRAL_CYLINDRICAL;
         ++panorama_type) {
        settings.panorama_type = panorama_type;
        settings.revision++;
        const std::string stage = "panorama " + std::to_string(panorama_type);
        CyclesBridgeDiagnostics panorama_diagnostics{};
        panorama_diagnostics.struct_size = sizeof(panorama_diagnostics);
        panorama_diagnostics.struct_version = 1;
        if (!require_ok(
                cycles_bridge_apply_settings(renderer, &settings),
                stage.c_str())
            || !wait_for_settings(renderer, settings.revision)
            || !wait_for_updated_frame(
                renderer, camera, frame, pixels, stage.c_str(), info, false,
                CYCLES_BRIDGE_PASS_COMBINED)
            || !require_ok(
                cycles_bridge_query_diagnostics(renderer, &panorama_diagnostics),
                "panorama diagnostics")
            || panorama_diagnostics.camera_type != CYCLES_BRIDGE_CAMERA_PANORAMA
            || panorama_diagnostics.panorama_type != panorama_type) {
            std::cerr << stage << " did not reach the native camera diagnostics\n";
            cycles_bridge_destroy_renderer(renderer);
            return 1;
        }
    }
    settings.camera_type = CYCLES_BRIDGE_CAMERA_PERSPECTIVE;
    settings.panorama_type = CYCLES_BRIDGE_PANORAMA_EQUIRECTANGULAR;
    settings.revision++;
    if (!require_ok(
            cycles_bridge_apply_settings(renderer, &settings),
            "perspective camera restore")
        || !wait_for_settings(renderer, settings.revision)
        || !wait_for_updated_frame(
            renderer, camera, frame, pixels, "perspective camera restore", info,
            true, CYCLES_BRIDGE_PASS_COMBINED)) {
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }

    CyclesBridgeDiagnostics diagnostics{};
    diagnostics.struct_size = sizeof(diagnostics);
    diagnostics.struct_version = 1;
    if (!require_ok(
            cycles_bridge_query_diagnostics(renderer, &diagnostics),
            "diagnostics query")) {
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    std::cerr << "[smoke] Device update phase telemetry\n";
    if (diagnostics.scene_timing_revision == 0U
        || diagnostics.active_device_phase > CYCLES_BRIDGE_DEVICE_PHASE_COUNT
        || std::all_of(
            std::begin(diagnostics.last_device_phase_micros),
            std::end(diagnostics.last_device_phase_micros),
            [](std::uint32_t micros) { return micros == 0U; })) {
        std::cerr << "device update phases were not captured for the completed scene\n";
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    const std::uint64_t all_passes_mask = (1ULL << CYCLES_BRIDGE_PASS_COUNT) - 1ULL;
    const float expected_physical_fov = 2.0F * std::atan(
        36.0F / (2.0F * 18.0F * (static_cast<float>(kWidth) / kHeight)));
    if (diagnostics.sampling_pattern
            != CYCLES_BRIDGE_SAMPLING_PATTERN_BLUE_NOISE_FIRST
        || std::abs(diagnostics.effective_camera_clip_near - 0.125F) > 1.0e-6F
        || std::abs(diagnostics.effective_camera_clip_far - 50.0F) > 1.0e-6F
        || diagnostics.projection_mode != CYCLES_BRIDGE_PROJECTION_PHYSICAL_LENS
        || diagnostics.camera_type != CYCLES_BRIDGE_CAMERA_PERSPECTIVE
        || diagnostics.panorama_type != CYCLES_BRIDGE_PANORAMA_EQUIRECTANGULAR
        || std::abs(diagnostics.vertical_fov_radians - expected_physical_fov) > 1.0e-5F
        || diagnostics.depth_of_field != 1U
        || std::abs(diagnostics.focus_distance - 8.0F) > 1.0e-6F
        || std::abs(diagnostics.f_stop - 4.0F) > 1.0e-6F
        || std::abs(diagnostics.aperture_size - 0.00225F) > 1.0e-6F
        || diagnostics.aperture_blades != 6U
        || std::abs(diagnostics.aperture_rotation_radians - 0.2617994F) > 1.0e-5F
        || std::abs(diagnostics.aperture_ratio - 1.2F) > 1.0e-6F
        || diagnostics.cached_raw_pass_mask != all_passes_mask
        || diagnostics.cached_denoised_pass_mask != 0U
        || diagnostics.pass_cache_entry_count < CYCLES_BRIDGE_PASS_COUNT
        || diagnostics.pass_cache_bytes == 0U
        || diagnostics.pass_cache_bytes > diagnostics.pass_cache_budget_bytes
        || diagnostics.pass_cache_hit_count == 0U
        || diagnostics.registered_pass_mask != all_passes_mask
        || diagnostics.pass_registry_rebuild_count
            < CYCLES_BRIDGE_PASS_COUNT - 1U
        || diagnostics.pass_registry_hit_count == 0U
        || diagnostics.active_frame_variant != CYCLES_BRIDGE_FRAME_VARIANT_RAW
        || ((diagnostics.device_type == 1U || diagnostics.device_type == 2U)
            && diagnostics.device_uuid_valid == 0U)
        || (diagnostics.device_type == 3U && diagnostics.device_uuid_valid != 0U)) {
        std::cerr << "unexpected raw pass cache state: sampling-pattern="
                  << diagnostics.sampling_pattern
                  << ";clip=" << diagnostics.effective_camera_clip_near
                  << '/' << diagnostics.effective_camera_clip_far
                  << ";projection/fov=" << diagnostics.projection_mode
                  << '/' << diagnostics.vertical_fov_radians
                  << ";dof/focus/fstop/aperture=" << diagnostics.depth_of_field
                  << '/' << diagnostics.focus_distance
                  << '/' << diagnostics.f_stop
                  << '/' << diagnostics.aperture_size
                  << ";raw=" << diagnostics.cached_raw_pass_mask
                  << ";denoised=" << diagnostics.cached_denoised_pass_mask
                  << ";entries=" << diagnostics.pass_cache_entry_count
                  << ";bytes=" << diagnostics.pass_cache_bytes
                  << ";budget=" << diagnostics.pass_cache_budget_bytes
                  << ";hits=" << diagnostics.pass_cache_hit_count
                  << ";registered=" << diagnostics.registered_pass_mask
                  << ";registry-rebuilds=" << diagnostics.pass_registry_rebuild_count
                  << ";registry-hits=" << diagnostics.pass_registry_hit_count << '\n';
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }

    std::cerr << "[smoke] Applying runtime atmosphere settings\n";
    const std::uint32_t atmosphere_section_count = diagnostics.section_count;
    const std::uint32_t atmosphere_scene_delta_count = diagnostics.scene_delta_count;
    settings.atmosphere_sun_elevation_degrees = 20.0F;
    settings.atmosphere_sun_rotation_degrees = 120.0F;
    settings.revision++;
    if (!require_ok(
            cycles_bridge_apply_settings(renderer, &settings),
            "atmosphere settings")
        || !wait_for_settings(renderer, settings.revision)
        || !wait_for_updated_frame(
            renderer, camera, frame, pixels, "atmosphere runtime update", info, true)
        || !require_ok(
            cycles_bridge_query_diagnostics(renderer, &diagnostics),
            "atmosphere diagnostics")
        || diagnostics.section_count != atmosphere_section_count
        || diagnostics.scene_delta_count != atmosphere_scene_delta_count
        || diagnostics.reset_level != CYCLES_BRIDGE_RESET_SESSION) {
        std::cerr << "atmosphere update changed streamed scene data or missed the session reset: "
                  << "sections=" << diagnostics.section_count
                  << ";scene-deltas=" << diagnostics.scene_delta_count
                  << ";reset=" << diagnostics.reset_level << '\n';
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }

    if ((capabilities.denoiser_mask & CYCLES_BRIDGE_DENOISER_DLSS_EXPERIMENTAL) != 0U) {
        std::cerr << "[smoke] Enabling experimental DLSS Quality mode\n";
        settings.denoiser_mode = 4U;
        settings.dlss_quality_mode = CYCLES_BRIDGE_DLSS_QUALITY_QUALITY;
        settings.revision++;
        if (!require_ok(
                cycles_bridge_apply_settings(renderer, &settings),
                "DLSS denoiser settings")
            || !wait_for_settings(renderer, settings.revision)
            || !wait_for_realtime_dlss(
                renderer, camera, frame, pixels, diagnostics, info)) {
            cycles_bridge_destroy_renderer(renderer);
            return 1;
        }
        const std::uint64_t dlss_checksum = checksum(pixels);
        camera.position_x += 0.125;
        if (!wait_for_checksum_change(
                renderer,
                camera,
                frame,
                pixels,
                dlss_checksum,
                "DLSS camera motion",
                info)
            || !wait_for_realtime_dlss(
                renderer, camera, frame, pixels, diagnostics, info)) {
            cycles_bridge_destroy_renderer(renderer);
            return 1;
        }
        settings.denoiser_mode = 0U;
        settings.revision++;
        if (!require_ok(
                cycles_bridge_apply_settings(renderer, &settings),
                "DLSS denoiser restore settings")
            || !wait_for_settings(renderer, settings.revision)
            || !wait_for_updated_frame(
                renderer, camera, frame, pixels, "DLSS denoiser restore", info, true)) {
            cycles_bridge_destroy_renderer(renderer);
            return 1;
        }
    }

    if ((capabilities.denoiser_mask & CYCLES_BRIDGE_DENOISER_OPTIX) != 0U) {
        std::cerr << "[smoke] Enabling the detected OptiX denoiser\n";
        settings.denoiser_mode = 2U;
        settings.stationary_delay_millis = 500U;
        settings.revision++;
        if (!require_ok(
                cycles_bridge_apply_settings(renderer, &settings),
                "OptiX denoiser settings")
            || !wait_for_settings(renderer, settings.revision)
            || !wait_for_updated_frame(
                renderer, camera, frame, pixels, "OptiX denoiser", info, true)
            || !require_ok(
                cycles_bridge_query_diagnostics(renderer, &diagnostics),
                "OptiX diagnostics")
            || diagnostics.effective_denoiser != 0U
            || diagnostics.selected_denoiser != 1U
            || diagnostics.denoiser_scheduled != 0U
            || diagnostics.effective_denoiser_start_sample != 0U
            || (diagnostics.denoiser_schedule_reason
                    != CYCLES_BRIDGE_DENOISER_SCHEDULE_INTERACTIVE
                && diagnostics.denoiser_schedule_reason
                    != CYCLES_BRIDGE_DENOISER_SCHEDULE_SETTLING)
            || diagnostics.denoiser_schedule_skip_count == 0U
            || diagnostics.active_frame_variant != CYCLES_BRIDGE_FRAME_VARIANT_RAW
            || !wait_for_denoised_still(
                renderer, camera, frame, pixels, diagnostics, info, 1U, "OptiX")) {
            std::cerr << "detected OptiX denoiser did not follow Interactive Raw -> Still Denoised: "
                      << "state=" << diagnostics.sampling_state
                      << ";effective=" << diagnostics.effective_denoiser
                      << ";selected=" << diagnostics.selected_denoiser
                      << ";scheduled=" << diagnostics.denoiser_scheduled
                      << ";start=" << diagnostics.effective_denoiser_start_sample
                      << ";reason=" << diagnostics.denoiser_schedule_reason
                      << ";run/skip=" << diagnostics.denoiser_schedule_run_count
                      << '/' << diagnostics.denoiser_schedule_skip_count
                      << ";variant=" << diagnostics.active_frame_variant << '\n';
            cycles_bridge_destroy_renderer(renderer);
            return 1;
        }

        const std::uint32_t cache_hits_before_denoised_switch =
            diagnostics.pass_cache_hit_count;
        settings.active_pass = CYCLES_BRIDGE_PASS_DEPTH;
        settings.revision++;
        if (!require_ok(
                cycles_bridge_apply_settings(renderer, &settings),
                "denoised cache depth settings")
            || !wait_for_settings(renderer, settings.revision)
            || !wait_for_updated_frame(
                renderer, camera, frame, pixels, "denoised cache depth", info, false,
                CYCLES_BRIDGE_PASS_DEPTH)) {
            cycles_bridge_destroy_renderer(renderer);
            return 1;
        }
        settings.active_pass = CYCLES_BRIDGE_PASS_COMBINED;
        settings.revision++;
        if (!require_ok(
                cycles_bridge_apply_settings(renderer, &settings),
                "denoised cache combined settings")
            || !wait_for_settings(renderer, settings.revision)
            || !wait_for_updated_frame(
                renderer, camera, frame, pixels, "denoised cache combined", info, true,
                CYCLES_BRIDGE_PASS_COMBINED)
            || !wait_for_denoised_still(
                renderer, camera, frame, pixels, diagnostics, info, 1U, "OptiX")
            || !require_ok(
                cycles_bridge_query_diagnostics(renderer, &diagnostics),
                "denoised pass cache diagnostics")
            || (diagnostics.cached_raw_pass_mask & (1ULL << CYCLES_BRIDGE_PASS_DEPTH)) == 0U
            || (diagnostics.cached_denoised_pass_mask
                & (1ULL << CYCLES_BRIDGE_PASS_COMBINED)) == 0U
            || diagnostics.pass_cache_hit_count <= cache_hits_before_denoised_switch
            || diagnostics.active_frame_variant
                != CYCLES_BRIDGE_FRAME_VARIANT_DENOISED) {
            std::cerr << "raw and denoised pass cache variants were not kept separately\n";
            cycles_bridge_destroy_renderer(renderer);
            return 1;
        }

        settings.denoiser_mode = 0U;
        settings.stationary_delay_millis = 150U;
        settings.revision++;
        if (!require_ok(
                cycles_bridge_apply_settings(renderer, &settings),
                "denoiser restore settings")
            || !wait_for_settings(renderer, settings.revision)
            || !wait_for_updated_frame(
                renderer, camera, frame, pixels, "denoiser restore", info, true)) {
            cycles_bridge_destroy_renderer(renderer);
            return 1;
        }
    }

    if ((capabilities.capability_flags & CYCLES_BRIDGE_CAPABILITY_OIDN_COMPILED) == 0U
        || (capabilities.denoiser_mask & CYCLES_BRIDGE_DENOISER_OPENIMAGEDENOISE) == 0U) {
        std::cerr << "OpenImageDenoise was not compiled or exposed by the active device\n";
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }

    std::cerr << "[smoke] Enabling the detected OpenImageDenoise denoiser\n";
    settings.denoiser_mode = 3U;
    settings.stationary_delay_millis = 500U;
    settings.revision++;
    if (!require_ok(
            cycles_bridge_apply_settings(renderer, &settings),
            "OpenImageDenoise settings")
        || !wait_for_settings(renderer, settings.revision)
        || !wait_for_updated_frame(
            renderer, camera, frame, pixels, "OpenImageDenoise", info, true)
        || !require_ok(
            cycles_bridge_query_diagnostics(renderer, &diagnostics),
            "OpenImageDenoise diagnostics")
        || diagnostics.effective_denoiser != 0U
        || diagnostics.selected_denoiser != 2U
        || diagnostics.denoiser_scheduled != 0U
        || diagnostics.effective_denoiser_start_sample != 0U
        || (diagnostics.denoiser_schedule_reason
                != CYCLES_BRIDGE_DENOISER_SCHEDULE_INTERACTIVE
            && diagnostics.denoiser_schedule_reason
                != CYCLES_BRIDGE_DENOISER_SCHEDULE_SETTLING)
        || diagnostics.denoiser_schedule_skip_count == 0U
        || diagnostics.active_frame_variant != CYCLES_BRIDGE_FRAME_VARIANT_RAW
        || !wait_for_denoised_still(
            renderer, camera, frame, pixels, diagnostics, info, 2U, "OpenImageDenoise")) {
        std::cerr << "detected OpenImageDenoise denoiser did not follow Interactive Raw -> Still Denoised: "
                  << "state=" << diagnostics.sampling_state
                  << ";effective=" << diagnostics.effective_denoiser
                  << ";selected=" << diagnostics.selected_denoiser
                  << ";scheduled=" << diagnostics.denoiser_scheduled
                  << ";start=" << diagnostics.effective_denoiser_start_sample
                  << ";reason=" << diagnostics.denoiser_schedule_reason
                  << ";run/skip=" << diagnostics.denoiser_schedule_run_count
                  << '/' << diagnostics.denoiser_schedule_skip_count
                  << ";variant=" << diagnostics.active_frame_variant << '\n';
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }

    settings.denoiser_mode = 0U;
    settings.stationary_delay_millis = 150U;
    settings.revision++;
    if (!require_ok(
            cycles_bridge_apply_settings(renderer, &settings),
            "OpenImageDenoise restore settings")
        || !wait_for_settings(renderer, settings.revision)
        || !wait_for_updated_frame(
            renderer, camera, frame, pixels, "OpenImageDenoise restore", info, true)) {
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }

    if (!wait_for_actual_sample(renderer, diagnostics)) {
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    if (!verify_progressive_sampling(renderer, camera, diagnostics)) {
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    CyclesBridgeFrameView acquired{};
    acquired.struct_size = sizeof(acquired);
    acquired.struct_version = 1;
    if (!require_ok(
            cycles_bridge_acquire_frame(renderer, 0U, &acquired),
            "frame acquire")
        || (acquired.flags & CYCLES_BRIDGE_FRAME_READY) == 0U
        || (acquired.flags & CYCLES_BRIDGE_FRAME_UPDATED) == 0U
        || acquired.width != kWidth || acquired.height != kHeight
        || acquired.pixel_format != CYCLES_BRIDGE_PIXEL_FORMAT_RGBA16_FLOAT
        || acquired.pixel_byte_count
            != static_cast<std::uint64_t>(kWidth) * kHeight * 8U
        || acquired.token == 0U || acquired.pixels == nullptr) {
        std::cerr << "acquired frame view is invalid\n";
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    CyclesBridgeFrameView unchanged{};
    unchanged.struct_size = sizeof(unchanged);
    unchanged.struct_version = 1;
    if (!require_ok(
            cycles_bridge_acquire_frame(renderer, acquired.generation, &unchanged),
            "unchanged frame acquire")
        || (unchanged.flags & CYCLES_BRIDGE_FRAME_READY) == 0U
        || (unchanged.flags & CYCLES_BRIDGE_FRAME_UPDATED) != 0U
        || unchanged.token != 0U || unchanged.pixels != nullptr
        || !require_ok(
            cycles_bridge_release_frame(renderer, acquired.token),
            "frame release")) {
        std::cerr << "frame lease lifecycle is invalid\n";
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    if (!require_ok(
            cycles_bridge_query_diagnostics(renderer, &diagnostics),
            "frame lease diagnostics")) {
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    if (diagnostics.settings_revision != settings.revision
        || diagnostics.active_pass != CYCLES_BRIDGE_PASS_COMBINED
        || diagnostics.width != kWidth || diagnostics.height != kHeight
        || diagnostics.target_sample_count != settings.interactive_samples
        || diagnostics.sample_count > diagnostics.target_sample_count
        || !std::isfinite(diagnostics.sample_rate)
        || diagnostics.sample_rate < 0.0F
        || diagnostics.produced_frame_count == 0U
        || diagnostics.copied_frame_count == 0U
        || diagnostics.copied_byte_count
            < static_cast<std::uint64_t>(kWidth) * kHeight * 4U
        || diagnostics.frame_pixel_format != CYCLES_BRIDGE_PIXEL_FORMAT_RGBA16_FLOAT
        || diagnostics.active_frame_leases != 0U
        || diagnostics.peak_frame_leases == 0U
        || diagnostics.frame_slot_count != 3U
        || diagnostics.settling_remaining_millis != 0U
        || diagnostics.sampling_transition_count < 3U
        || diagnostics.sampling_state == CYCLES_BRIDGE_SAMPLING_IDLE) {
        std::cerr << "unexpected diagnostics after Combined restore: revision="
                  << diagnostics.settings_revision << ";pass=" << diagnostics.active_pass
                  << ";resolution=" << diagnostics.width << 'x' << diagnostics.height
                  << ";samples=" << diagnostics.sample_count << '/'
                  << diagnostics.target_sample_count
                  << ";sampling_state=" << diagnostics.sampling_state
                  << ";produced=" << diagnostics.produced_frame_count
                  << ";copied=" << diagnostics.copied_frame_count
                  << ";bytes=" << diagnostics.copied_byte_count << '\n';
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }

    const std::uint64_t render_settings_revision = diagnostics.settings_revision;
    settings.debug_overlay = 1U;
    settings.revision++;
    if (!require_ok(
            cycles_bridge_apply_settings(renderer, &settings),
            "debug-only settings")
        || !require_ok(
            cycles_bridge_query_diagnostics(renderer, &diagnostics),
            "debug-only diagnostics")
        || diagnostics.settings_revision != render_settings_revision
        || diagnostics.reset_level != CYCLES_BRIDGE_RESET_NONE) {
        std::cerr << "debug-only settings unexpectedly reset the renderer\n";
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    settings.debug_overlay = 0U;
    settings.revision++;
    if (!require_ok(
            cycles_bridge_apply_settings(renderer, &settings),
            "debug settings restore")) {
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }

    const std::uint64_t initial_generation = frame.generation;
    const std::uint64_t initial_checksum = checksum(pixels);
    std::vector<CyclesBridgeVertex> expanded_vertices(vertices.begin(), vertices.end());
    expanded_vertices.insert(expanded_vertices.end(), vertices.begin(), vertices.end());
    for (std::size_t index = vertices.size(); index < expanded_vertices.size(); ++index) {
        CyclesBridgeVertex& vertex = expanded_vertices[index];
        vertex.position_x += 3.0F;
    }
    std::vector<CyclesBridgeTriangle> expanded_triangles(triangles.begin(), triangles.end());
    expanded_triangles.push_back({4U, 5U, 6U, 0U});
    expanded_triangles.push_back({4U, 6U, 7U, 0U});
    CyclesBridgeSection expanded_section = section;
    expanded_section.vertex_count = static_cast<std::uint32_t>(expanded_vertices.size());
    expanded_section.triangle_count = static_cast<std::uint32_t>(expanded_triangles.size());
    std::cerr << "[smoke] Updating the existing section in place\n";
    if (!require_ok(
            cycles_bridge_upsert_section(
                renderer,
                &expanded_section,
                expanded_vertices.data(),
                expanded_triangles.data()),
            "section update")
        || !require_ok(cycles_bridge_commit_scene(renderer), "updated scene commit")
        || !wait_for_checksum_change(
            renderer,
            camera,
            frame,
            pixels,
            initial_checksum,
            "updated section",
            info)) {
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    if (frame.generation <= initial_generation) {
        std::cerr << "section update did not change the rendered frame\n";
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    if (!require_ok(
            cycles_bridge_query_diagnostics(renderer, &diagnostics),
            "scene preemption diagnostics")
        || diagnostics.scene_timing_revision == 0U
        || diagnostics.scene_timing_count == 0U
        || diagnostics.last_scene_first_frame_micros == 0U) {
        std::cerr << "scene update was not associated with a completed first frame\n";
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }

    const std::uint64_t updated_generation = frame.generation;
    std::cerr << "[smoke] Removing the section\n";
    if (!require_ok(
            cycles_bridge_remove_section(renderer, section.section_id),
            "section removal")
        || !require_ok(cycles_bridge_commit_scene(renderer), "removed scene commit")
        || !wait_for_empty_scene_frame(
            renderer,
            camera,
            frame,
            pixels,
            updated_generation,
            "removed section",
            info)) {
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    if (frame.generation <= updated_generation) {
        std::cerr << "section removal did not advance the rendered frame\n";
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    if (!require_ok(
            cycles_bridge_query_diagnostics(renderer, &diagnostics),
            "scene timing diagnostics")
        || diagnostics.scene_commit_count < 3U
        || diagnostics.scene_delta_count < 2U
        || diagnostics.render_start_count == 0U) {
        std::cerr << "missing scene timing telemetry: commits="
                  << diagnostics.scene_commit_count
                  << ";deltas=" << diagnostics.scene_delta_count
                  << ";starts=" << diagnostics.render_start_count << '\n';
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }

    std::cerr << "[smoke] Verifying interactive dynamic resolution\n";
    settings.dynamic_resolution = 1U;
    settings.interactive_resolution_percentage = 50U;
    settings.revision++;
    camera.position_x += 0.125;
    if (!require_ok(
            cycles_bridge_apply_settings(renderer, &settings),
            "dynamic resolution settings")
        || !wait_for_settings(renderer, settings.revision)
        || !wait_for_frame_dimensions(
            renderer, camera, frame, pixels, 240U, 135U,
            "interactive dynamic resolution", info)
        || !wait_for_frame_dimensions(
            renderer, camera, frame, pixels, kWidth, kHeight,
            "still full resolution", info)) {
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    std::cout << info << '\n'
              << "frame=" << kWidth << 'x' << kHeight
              << ";checksum=" << checksum(pixels) << std::endl;
    std::cerr << "[smoke] Destroying renderer\n";
    cycles_bridge_destroy_renderer(renderer);
    std::cerr << "[smoke] Complete\n";
    return 0;
}

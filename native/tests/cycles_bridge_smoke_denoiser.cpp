#include "cycles_bridge_smoke_support.h"

#include <cmath>
#include <cstdint>
#include <iostream>
#include <vector>

namespace cyclesrenderer::smoke {

bool run_denoiser_scenarios(SmokeContext& context) {
    CyclesBridgeRenderer* renderer = context.renderer;
    const CyclesBridgeCapabilities& capabilities = context.capabilities;
    CyclesBridgeRenderSettings& settings = context.settings;
    CyclesBridgeCamera& camera = context.camera;
    CyclesBridgeFrame& frame = context.frame;
    std::vector<std::uint8_t>& pixels = context.pixels;
    CyclesBridgeDiagnostics& diagnostics = context.diagnostics;
    std::string& info = context.info;
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
            return false;
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
            return false;
        }
        settings.denoiser_mode = 0U;
        settings.revision++;
        if (!require_ok(
                cycles_bridge_apply_settings(renderer, &settings),
                "DLSS denoiser restore settings")
            || !wait_for_settings(renderer, settings.revision)
            || !wait_for_updated_frame(
                renderer, camera, frame, pixels, "DLSS denoiser restore", info, true)) {
            return false;
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
            return false;
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
            return false;
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
            return false;
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
            return false;
        }
    }

    if ((capabilities.capability_flags & CYCLES_BRIDGE_CAPABILITY_OIDN_COMPILED) == 0U
        || (capabilities.denoiser_mask & CYCLES_BRIDGE_DENOISER_OPENIMAGEDENOISE) == 0U) {
        std::cerr << "OpenImageDenoise was not compiled or exposed by the active device\n";
        return false;
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
        return false;
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
        return false;
    }

    if (!wait_for_actual_sample(renderer, diagnostics)) {
        return false;
    }
    if (!verify_progressive_sampling(renderer, camera, diagnostics)) {
        return false;
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
        return false;
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
        return false;
    }
    if (!require_ok(
            cycles_bridge_query_diagnostics(renderer, &diagnostics),
            "frame lease diagnostics")) {
        return false;
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
        return false;
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
        return false;
    }
    settings.debug_overlay = 0U;
    settings.revision++;
    if (!require_ok(
            cycles_bridge_apply_settings(renderer, &settings),
            "debug settings restore")) {
        return false;
    }


    return true;
}

}  // namespace cyclesrenderer::smoke

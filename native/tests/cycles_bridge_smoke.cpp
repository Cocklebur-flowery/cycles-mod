#include "cycles_bridge.h"

#include <Windows.h>

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
    bool require_green = false) {
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
            std::cerr << "[smoke] " << stage << ": " << info << '\n';
        }
        if ((frame.flags & CYCLES_BRIDGE_FRAME_READY) != 0U
            && (frame.flags & CYCLES_BRIDGE_FRAME_UPDATED) != 0U) {
            if (!require_green || has_green_dominant_pixel(pixels)) {
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

bool wait_for_background_frame(
    CyclesBridgeRenderer* renderer,
    CyclesBridgeCamera& camera,
    CyclesBridgeFrame& frame,
    std::vector<std::uint8_t>& pixels,
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
            && !has_rgb_variation(pixels)) {
            return true;
        }
        Sleep(100);
    }
    std::cerr << stage << " did not converge to the background frame\n";
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
    settings.interactive_samples = 1;
    settings.still_samples = 1;
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

}  // namespace

int main(int argc, char** argv) {
    const bool require_optix = argc > 1 && std::strcmp(argv[1], "--require-optix") == 0;
    std::cerr << "[smoke] ABI check\n";
    if (cycles_bridge_abi_version() != 11U) {
        std::cerr << "unexpected native ABI " << cycles_bridge_abi_version() << '\n';
        return 1;
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
    if (!require_ok(
            cycles_bridge_query_capabilities(renderer, &capabilities),
            "capability query")
        || (capabilities.capability_flags & CYCLES_BRIDGE_CAPABILITY_SETTINGS) == 0U
        || (capabilities.capability_flags & CYCLES_BRIDGE_CAPABILITY_PASS_VIEWER) == 0U
        || capabilities.pass_mask != ((1ULL << CYCLES_BRIDGE_PASS_COUNT) - 1ULL)
        || !require_ok(
            cycles_bridge_apply_settings(renderer, &settings),
            "initial settings")) {
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
        {0U, CYCLES_BRIDGE_MATERIAL_CUTOUT, 0.0F, 0.5F, {0U, 0U, 0U, 0U}},
    }};
    const std::array<std::uint8_t, 16> texture_pixels = {{
        255U, 64U, 32U, 255U,
        32U, 255U, 64U, 255U,
        32U, 64U, 255U, 255U,
        255U, 255U, 32U, 0U,
    }};
    const std::array<CyclesBridgeTexture, 1> textures = {{
        {2U, 2U, 0U, static_cast<std::uint32_t>(texture_pixels.size()), {0U, 0U, 0U, 0U}},
    }};
    CyclesBridgeSceneResources resources{};
    resources.struct_size = sizeof(resources);
    resources.struct_version = 1;
    resources.material_count = static_cast<std::uint32_t>(materials.size());
    resources.texture_count = static_cast<std::uint32_t>(textures.size());
    resources.texture_byte_count = static_cast<std::uint32_t>(texture_pixels.size());
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
                renderer, camera, frame, pixels, stage.c_str(), info)
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
            renderer, camera, frame, pixels, "combined pass restore", info, true)) {
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

    if ((capabilities.denoiser_mask & CYCLES_BRIDGE_DENOISER_OPTIX) != 0U) {
        std::cerr << "[smoke] Enabling the detected OptiX denoiser\n";
        settings.denoiser_mode = 2U;
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
            || diagnostics.effective_denoiser != 1U) {
            std::cerr << "detected OptiX denoiser was not activated\n";
            cycles_bridge_destroy_renderer(renderer);
            return 1;
        }

        settings.denoiser_mode = 0U;
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

    if (!wait_for_actual_sample(renderer, diagnostics)) {
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

    const std::uint64_t updated_generation = frame.generation;
    std::cerr << "[smoke] Removing the section\n";
    if (!require_ok(
            cycles_bridge_remove_section(renderer, section.section_id),
            "section removal")
        || !require_ok(cycles_bridge_commit_scene(renderer), "removed scene commit")
        || !wait_for_background_frame(
            renderer, camera, frame, pixels, "removed section", info)) {
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
    std::cout << info << '\n'
              << "frame=" << kWidth << 'x' << kHeight
              << ";checksum=" << checksum(pixels) << std::endl;
    std::cerr << "[smoke] Destroying renderer\n";
    cycles_bridge_destroy_renderer(renderer);
    std::cerr << "[smoke] Complete\n";
    return 0;
}

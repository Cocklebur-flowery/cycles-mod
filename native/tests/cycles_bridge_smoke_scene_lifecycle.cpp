#include "cycles_bridge_smoke_support.h"

#include <cstddef>
#include <cstdint>
#include <iostream>
#include <string>
#include <vector>

namespace cyclesrenderer::smoke {

bool run_scene_lifecycle_scenarios(SmokeContext& context) {
    CyclesBridgeRenderer* renderer = context.renderer;
    CyclesBridgeRenderSettings& settings = context.settings;
    const auto& vertices = context.vertices;
    const auto& triangles = context.triangles;
    const CyclesBridgeSection& section = context.section;
    CyclesBridgeCamera& camera = context.camera;
    CyclesBridgeFrame& frame = context.frame;
    std::vector<std::uint8_t>& pixels = context.pixels;
    CyclesBridgeDiagnostics& diagnostics = context.diagnostics;
    std::string& info = context.info;
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
        return false;
    }
    if (frame.generation <= initial_generation) {
        std::cerr << "section update did not change the rendered frame\n";
        return false;
    }
    if (!require_ok(
            cycles_bridge_query_diagnostics(renderer, &diagnostics),
            "scene preemption diagnostics")
        || diagnostics.scene_timing_revision == 0U
        || diagnostics.scene_timing_count == 0U
        || diagnostics.last_scene_first_frame_micros == 0U) {
        std::cerr << "scene update was not associated with a completed first frame\n";
        return false;
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
        return false;
    }
    if (frame.generation <= updated_generation) {
        std::cerr << "section removal did not advance the rendered frame\n";
        return false;
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
        return false;
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
        return false;
    }
    std::cout << info << '\n'
              << "frame=" << kWidth << 'x' << kHeight
              << ";checksum=" << checksum(pixels) << std::endl;

    return true;
}

}  // namespace cyclesrenderer::smoke

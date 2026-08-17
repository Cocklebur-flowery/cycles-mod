#include "cycles_bridge_smoke_support.h"

#include <array>
#include <cstdint>
#include <iostream>
#include <vector>

namespace cyclesrenderer::smoke {
namespace {

bool render_material(
    SmokeContext& context,
    std::uint32_t material_index,
    std::uint64_t previous_checksum,
    const char* stage,
    std::uint64_t& rendered_checksum) {
    for (CyclesBridgeTriangle& triangle : context.triangles) {
        triangle.material_index = material_index;
    }
    if (!require_ok(
            cycles_bridge_upsert_section(
                context.renderer,
                &context.section,
                context.vertices.data(),
                context.triangles.data()),
            stage)
        || !require_ok(cycles_bridge_commit_scene(context.renderer), stage)
        || !wait_for_checksum_change(
            context.renderer,
            context.camera,
            context.frame,
            context.pixels,
            previous_checksum,
            stage,
            context.info)) {
        return false;
    }

    CyclesBridgeDiagnostics diagnostics{};
    diagnostics.struct_size = sizeof(diagnostics);
    diagnostics.struct_version = 1U;
    rendered_checksum = checksum(context.pixels);
    if (!require_ok(
            cycles_bridge_query_diagnostics(context.renderer, &diagnostics),
            "PBR material diagnostics")
        || diagnostics.section_count != 1U
        || diagnostics.active_pass != CYCLES_BRIDGE_PASS_COMBINED
        || rendered_checksum == previous_checksum
        || !has_rgb_variation(context.pixels)
        || !has_green_dominant_pixel(context.pixels)) {
        std::cerr << stage
                  << " did not produce the expected active material frame: sections="
                  << diagnostics.section_count
                  << ";pass=" << diagnostics.active_pass
                  << ";checksum=" << rendered_checksum << '\n';
        return false;
    }
    return true;
}

bool render_pass(
    SmokeContext& context,
    std::uint32_t pass,
    const char* stage,
    bool require_green,
    std::uint64_t& rendered_checksum) {
    context.settings.active_pass = pass;
    context.settings.revision++;
    const std::uint64_t previous_checksum = checksum(context.pixels);
    if (!require_ok(
            cycles_bridge_apply_settings(context.renderer, &context.settings),
            stage)
        || !wait_for_settings(context.renderer, context.settings.revision)
        || !wait_for_updated_frame(
            context.renderer,
            context.camera,
            context.frame,
            context.pixels,
            stage,
            context.info,
            require_green,
            static_cast<int>(pass))) {
        return false;
    }
    rendered_checksum = checksum(context.pixels);
    if (rendered_checksum == previous_checksum
        || !has_rgb_variation(context.pixels)) {
        std::cerr << stage << " did not produce distinct PBR pass content\n";
        return false;
    }
    return true;
}

bool all_unique(const std::array<std::uint64_t, 5>& checksums) {
    for (std::size_t left = 0U; left < checksums.size(); ++left) {
        for (std::size_t right = left + 1U; right < checksums.size(); ++right) {
            if (checksums[left] == checksums[right]) {
                return false;
            }
        }
    }
    return true;
}

}  // namespace

bool run_pbr_material_scenarios(SmokeContext& context) {
    std::cerr << "[smoke] Rendering active LabPBR cutout, glass, and water triangles\n";
    context.settings.dynamic_resolution = 0U;
    context.settings.resolution_percentage = 100U;
    context.settings.active_pass = CYCLES_BRIDGE_PASS_COMBINED;
    context.settings.denoiser_mode = 0U;
    context.settings.depth_of_field = 0U;
    context.settings.pbr_normal_strength = 1.0F;
    context.settings.pbr_emission_scale = 1.0F;
    context.settings.pbr_height_strength = 1.0F;
    context.settings.pbr_height_distance = 0.05F;
    context.settings.pbr_height_mapping_mode = CYCLES_BRIDGE_HEIGHT_MAPPING_BUMP;
    context.settings.revision++;
    if (!require_ok(
            cycles_bridge_apply_settings(context.renderer, &context.settings),
            "PBR smoke settings")
        || !wait_for_settings(context.renderer, context.settings.revision)) {
        return false;
    }

    context.camera = {};
    context.camera.struct_size = sizeof(context.camera);
    context.camera.struct_version = 1U;
    context.camera.viewport_width = kWidth;
    context.camera.viewport_height = kHeight;
    context.camera.position_y = 2.0;
    context.camera.position_z = 8.0;
    context.camera.rotation_w = 1.0F;
    context.camera.vertical_fov_radians = 1.04719755F;
    context.camera.depth_far = 100.0F;
    context.camera.frame_id = 1U;
    if (!require_ok(
            cycles_bridge_update_camera(context.renderer, &context.camera),
            "PBR smoke camera")) {
        return false;
    }

    const std::array<CyclesBridgeMaterial, 3> materials = {{
        {0U, CYCLES_BRIDGE_MATERIAL_CUTOUT, 0.0F, 0.5F,
         1U, 2U, CYCLES_BRIDGE_PBR_LAB_1_3, 3U},
        {0U, CYCLES_BRIDGE_MATERIAL_TRANSMISSION, 0.0F, 0.5F,
         1U, 2U, CYCLES_BRIDGE_PBR_LAB_1_3, 3U},
        {0U, CYCLES_BRIDGE_MATERIAL_TRANSMISSION | CYCLES_BRIDGE_MATERIAL_WATER,
         0.0F, 0.5F, 1U, 2U, CYCLES_BRIDGE_PBR_LAB_1_3, 3U},
    }};
    const std::array<std::uint8_t, 64> texture_pixels = {{
        32U, 255U, 64U, 192U, 32U, 255U, 64U, 192U,
        32U, 255U, 64U, 192U, 32U, 255U, 64U, 192U,
        128U, 128U, 255U, 255U, 191U, 128U, 221U, 255U,
        128U, 191U, 221U, 255U, 64U, 128U, 221U, 255U,
        32U, 0U, 10U, 0U, 96U, 0U, 10U, 64U,
        160U, 0U, 10U, 128U, 224U, 0U, 10U, 255U,
        255U, 64U, 10U, 0U, 224U, 128U, 10U, 32U,
        192U, 192U, 10U, 96U, 160U, 255U, 10U, 160U,
    }};
    const std::array<CyclesBridgeTexture, 4> textures = {{
        {2U, 2U, 0U, 16U, CYCLES_BRIDGE_TEXTURE_COLOR_SRGB, {0U, 0U, 0U}},
        {2U, 2U, 16U, 16U, CYCLES_BRIDGE_TEXTURE_DATA_LINEAR, {0U, 0U, 0U}},
        {2U, 2U, 32U, 16U, CYCLES_BRIDGE_TEXTURE_DATA_LINEAR, {0U, 0U, 0U}},
        {2U, 2U, 48U, 16U, CYCLES_BRIDGE_TEXTURE_DATA_LINEAR, {0U, 0U, 0U}},
    }};
    CyclesBridgeSceneResources resources{};
    resources.struct_size = sizeof(resources);
    resources.struct_version = 1U;
    resources.material_count = static_cast<std::uint32_t>(materials.size());
    resources.texture_count = static_cast<std::uint32_t>(textures.size());
    resources.texture_byte_count = static_cast<std::uint32_t>(texture_pixels.size());
    if (!require_ok(
            cycles_bridge_reset_scene(
                context.renderer,
                &resources,
                materials.data(),
                textures.data(),
                texture_pixels.data()),
            "PBR scene resources")) {
        return false;
    }

    const std::uint64_t starting_checksum = checksum(context.pixels);
    std::array<std::uint64_t, 3> material_checksums{};
    if (!render_material(
            context, 0U, starting_checksum, "LabPBR cutout",
            material_checksums[0])
        || !render_material(
            context, 1U, material_checksums[0], "LabPBR glass",
            material_checksums[1])
        || !render_material(
            context, 2U, material_checksums[1], "LabPBR water",
            material_checksums[2])
        || material_checksums[0] == material_checksums[1]
        || material_checksums[0] == material_checksums[2]
        || material_checksums[1] == material_checksums[2]) {
        std::cerr << "LabPBR cutout, glass, and water did not render distinctly\n";
        return false;
    }

    std::uint64_t combined_checksum = 0U;
    if (!render_material(
            context, 0U, material_checksums[2], "LabPBR cutout restore",
            combined_checksum)) {
        return false;
    }
    std::array<std::uint64_t, 5> pass_checksums{{combined_checksum, 0U, 0U, 0U, 0U}};
    if (!render_pass(
            context, CYCLES_BRIDGE_PASS_NORMAL, "LabPBR normal pass", false,
            pass_checksums[1])
        || !render_pass(
            context, CYCLES_BRIDGE_PASS_DIFFUSE_COLOR, "LabPBR diffuse pass", true,
            pass_checksums[2])
        || !render_pass(
            context, CYCLES_BRIDGE_PASS_EMISSION, "LabPBR emission pass", true,
            pass_checksums[3])
        || !render_pass(
            context, CYCLES_BRIDGE_PASS_ROUGHNESS, "LabPBR roughness pass", false,
            pass_checksums[4])
        || !all_unique(pass_checksums)) {
        std::cerr << "LabPBR channel passes were not distinct\n";
        return false;
    }

    std::uint64_t restored_checksum = 0U;
    if (!render_pass(
            context, CYCLES_BRIDGE_PASS_COMBINED, "LabPBR combined restore", true,
            restored_checksum)) {
        return false;
    }
    return true;
}

}  // namespace cyclesrenderer::smoke

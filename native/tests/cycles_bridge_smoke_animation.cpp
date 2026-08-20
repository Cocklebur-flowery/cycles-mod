#include "cycles_bridge_smoke_support.h"

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <iostream>
#include <string>
#include <vector>

namespace cyclesrenderer::smoke {
namespace {

enum class ColorClass : std::uint8_t {
    Other,
    Red,
    Green,
    Blue,
    Yellow,
    Cyan,
};

void set_quad(
    std::array<CyclesBridgeVertex, 16>& vertices,
    std::array<CyclesBridgeTriangle, 8>& triangles,
    std::size_t quad,
    float left,
    float bottom,
    float right,
    float top,
    float u,
    float v) {
    const std::size_t vertex = quad * 4U;
    vertices[vertex] =
        {left, bottom, 0.0F, 0.0F, 0.0F, 1.0F, u, v, 0xFFFFFFFFU, 0U};
    vertices[vertex + 1U] =
        {right, bottom, 0.0F, 0.0F, 0.0F, 1.0F, u, v, 0xFFFFFFFFU, 0U};
    vertices[vertex + 2U] =
        {right, top, 0.0F, 0.0F, 0.0F, 1.0F, u, v, 0xFFFFFFFFU, 0U};
    vertices[vertex + 3U] =
        {left, top, 0.0F, 0.0F, 0.0F, 1.0F, u, v, 0xFFFFFFFFU, 0U};
    const std::size_t triangle = quad * 2U;
    triangles[triangle] = {
        static_cast<std::uint32_t>(vertex),
        static_cast<std::uint32_t>(vertex + 1U),
        static_cast<std::uint32_t>(vertex + 2U),
        0U,
    };
    triangles[triangle + 1U] = {
        static_cast<std::uint32_t>(vertex),
        static_cast<std::uint32_t>(vertex + 2U),
        static_cast<std::uint32_t>(vertex + 3U),
        0U,
    };
}

std::array<std::uint8_t, 64> initial_texture_pixels() {
    std::array<std::uint8_t, 64> pixels{};
    const std::array<std::uint8_t, 16> color{{
        255U, 16U, 16U, 255U,
        16U, 255U, 16U, 255U,
        16U, 16U, 255U, 255U,
        255U, 255U, 16U, 255U,
    }};
    std::copy(color.begin(), color.end(), pixels.begin());
    for (std::size_t pixel = 0U; pixel < 4U; ++pixel) {
        const std::size_t normal = 16U + pixel * 4U;
        pixels[normal] = 128U;
        pixels[normal + 1U] = 128U;
        pixels[normal + 2U] = 255U;
        pixels[normal + 3U] = 255U;
        const std::size_t material = 32U + pixel * 4U;
        pixels[material] = 204U;
        pixels[material + 2U] = 10U;
        const std::size_t auxiliary = 48U + pixel * 4U;
        pixels[auxiliary] = 255U;
        pixels[auxiliary + 1U] = 128U;
        pixels[auxiliary + 2U] = 10U;
    }
    return pixels;
}

ColorClass classify(const std::uint8_t* pixel) {
    const int red = pixel[0];
    const int green = pixel[1];
    const int blue = pixel[2];
    constexpr int kThreshold = 24;
    if (red > green * 2 && red > blue * 2 && red > kThreshold) {
        return ColorClass::Red;
    }
    if (green > red * 2 && green > blue * 2 && green > kThreshold) {
        return ColorClass::Green;
    }
    if (blue > red * 2 && blue > green * 2 && blue > kThreshold) {
        return ColorClass::Blue;
    }
    if (red > blue * 2 && green > blue * 2
        && red > kThreshold && green > kThreshold) {
        return ColorClass::Yellow;
    }
    if (green > red * 2 && blue > red * 2
        && green > kThreshold && blue > kThreshold) {
        return ColorClass::Cyan;
    }
    return ColorClass::Other;
}

bool verify_region_transition(
    const std::vector<std::uint8_t>& before,
    const std::vector<std::uint8_t>& after) {
    if (before.size() != after.size()) {
        return false;
    }
    std::size_t red_before = 0U;
    std::size_t cyan_after = 0U;
    std::size_t target_transitions = 0U;
    std::size_t outside_transitions = 0U;
    for (std::size_t offset = 0U; offset + 3U < before.size(); offset += 4U) {
        const ColorClass previous = classify(before.data() + offset);
        const ColorClass current = classify(after.data() + offset);
        red_before += previous == ColorClass::Red ? 1U : 0U;
        cyan_after += current == ColorClass::Cyan ? 1U : 0U;
        if (previous == current) {
            continue;
        }
        if (previous == ColorClass::Red && current == ColorClass::Cyan) {
            target_transitions++;
        } else if (previous == ColorClass::Green || previous == ColorClass::Blue
                   || previous == ColorClass::Yellow
                   || current == ColorClass::Green || current == ColorClass::Blue
                   || current == ColorClass::Yellow) {
            outside_transitions++;
        }
    }
    if (red_before < 64U || cyan_after < 64U
        || target_transitions < 64U || outside_transitions != 0U) {
        std::cerr << "animation region pixels were not isolated: red-before="
                  << red_before << ";cyan-after=" << cyan_after
                  << ";target-transitions=" << target_transitions
                  << ";outside-transitions=" << outside_transitions << '\n';
        return false;
    }
    return true;
}

}  // namespace

bool run_animation_region_scenarios(SmokeContext& context) {
    std::cerr << "[smoke] Rendering public ABI texture animation update\n";
    context.settings.dynamic_resolution = 0U;
    context.settings.resolution_percentage = 100U;
    context.settings.active_pass = CYCLES_BRIDGE_PASS_DIFFUSE_COLOR;
    context.settings.denoiser_mode = 0U;
    context.settings.depth_of_field = 0U;
    context.settings.revision++;
    if (!require_ok(
            cycles_bridge_apply_settings(context.renderer, &context.settings),
            "animation smoke settings")
        || !wait_for_settings(context.renderer, context.settings.revision)) {
        return false;
    }

    std::array<CyclesBridgeVertex, 16> vertices{};
    std::array<CyclesBridgeTriangle, 8> triangles{};
    set_quad(vertices, triangles, 0U, -3.0F, -2.5F, -0.5F, -0.25F, 0.25F, 0.25F);
    set_quad(vertices, triangles, 1U, 0.5F, -2.5F, 3.0F, -0.25F, 0.75F, 0.25F);
    set_quad(vertices, triangles, 2U, -3.0F, 0.25F, -0.5F, 2.5F, 0.25F, 0.75F);
    set_quad(vertices, triangles, 3U, 0.5F, 0.25F, 3.0F, 2.5F, 0.75F, 0.75F);

    const std::array<CyclesBridgeMaterial, 1> materials = {{
        {0U, 0U, 0.0F, 0.5F, 1U, 2U, CYCLES_BRIDGE_PBR_LAB_1_3, 3U},
    }};
    const std::array<std::uint8_t, 64> texture_pixels = initial_texture_pixels();
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
    CyclesBridgeSection section{};
    section.struct_size = sizeof(section);
    section.struct_version = 1U;
    section.section_id = 7001;
    section.vertex_count = static_cast<std::uint32_t>(vertices.size());
    section.triangle_count = static_cast<std::uint32_t>(triangles.size());
    if (!require_ok(
            cycles_bridge_reset_scene(
                context.renderer,
                &resources,
                materials.data(),
                textures.data(),
                texture_pixels.data()),
            "animation scene reset")
        || !require_ok(
            cycles_bridge_upsert_section(
                context.renderer, &section, vertices.data(), triangles.data()),
            "animation section upsert")
        || !require_ok(
            cycles_bridge_commit_scene(context.renderer),
            "animation initial commit")) {
        return false;
    }

    context.camera = {};
    context.camera.struct_size = sizeof(context.camera);
    context.camera.struct_version = 1U;
    context.camera.viewport_width = kWidth;
    context.camera.viewport_height = kHeight;
    context.camera.position_z = 10.0;
    context.camera.rotation_w = 1.0F;
    context.camera.vertical_fov_radians = 1.04719755F;
    context.camera.depth_far = 100.0F;
    context.camera.frame_id++;
    if (!require_ok(
            cycles_bridge_update_camera(context.renderer, &context.camera),
            "animation camera update")) {
        return false;
    }
    context.pixels.assign(static_cast<std::size_t>(kWidth) * kHeight * 4U, 0U);
    context.frame = {};
    context.frame.struct_size = sizeof(context.frame);
    context.frame.struct_version = 1U;
    context.info.clear();
    if (!wait_for_updated_frame(
            context.renderer,
            context.camera,
            context.frame,
            context.pixels,
            "animation baseline",
            context.info,
            false,
            CYCLES_BRIDGE_PASS_DIFFUSE_COLOR)) {
        return false;
    }
    if (context.require_optix && context.info.find("backend=OPTIX") == std::string::npos) {
        std::cerr << "OptiX was required but animation used another backend: "
                  << context.info << '\n';
        return false;
    }
    const std::vector<std::uint8_t> before = context.pixels;
    CyclesBridgeDiagnostics diagnostics_before{};
    diagnostics_before.struct_size = sizeof(diagnostics_before);
    diagnostics_before.struct_version = 1U;
    if (!require_ok(
            cycles_bridge_query_diagnostics(context.renderer, &diagnostics_before),
            "animation baseline diagnostics")) {
        return false;
    }

    const std::array<std::uint8_t, 16> update_pixels{{
        16U, 255U, 255U, 255U,
        128U, 128U, 255U, 255U,
        204U, 0U, 10U, 0U,
        255U, 128U, 10U, 0U,
    }};
    CyclesBridgeTextureRegionUpdate update{};
    update.struct_size = sizeof(update);
    update.struct_version = 1U;
    update.generation = 3U;
    update.revision = 1U;
    update.sprite_index = 0U;
    update.width = 1U;
    update.height = 1U;
    update.row_stride = 4U;
    update.pixel_byte_count = 4U;
    update.normal_pixel_offset = 4U;
    update.material_pixel_offset = 8U;
    update.auxiliary_pixel_offset = 12U;
    const std::uint64_t previous_checksum = checksum(context.pixels);
    if (!require_ok(
            cycles_bridge_stage_texture_region(
                context.renderer, &update, update_pixels.data(), update_pixels.size()),
            "animation region stage")
        || !require_ok(
            cycles_bridge_commit_scene(context.renderer),
            "animation region commit")
        || !wait_for_checksum_change(
            context.renderer,
            context.camera,
            context.frame,
            context.pixels,
            previous_checksum,
            "animation region render",
            context.info)
        || !verify_region_transition(before, context.pixels)) {
        return false;
    }

    CyclesBridgeDiagnostics diagnostics_after{};
    diagnostics_after.struct_size = sizeof(diagnostics_after);
    diagnostics_after.struct_version = 1U;
    if (!require_ok(
            cycles_bridge_query_diagnostics(context.renderer, &diagnostics_after),
            "animation updated diagnostics")
        || diagnostics_after.section_count != 1U
        || diagnostics_after.active_pass != CYCLES_BRIDGE_PASS_DIFFUSE_COLOR
        || diagnostics_after.scene_commit_count != diagnostics_before.scene_commit_count + 1U
        || diagnostics_after.scene_delta_count != diagnostics_before.scene_delta_count + 1U) {
        std::cerr << "animation region did not use one incremental scene delta: commits="
                  << diagnostics_before.scene_commit_count << "->"
                  << diagnostics_after.scene_commit_count << ";deltas="
                  << diagnostics_before.scene_delta_count << "->"
                  << diagnostics_after.scene_delta_count << '\n';
        return false;
    }
    return true;
}

}  // namespace cyclesrenderer::smoke

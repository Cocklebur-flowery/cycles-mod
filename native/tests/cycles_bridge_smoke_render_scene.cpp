#include "cycles_bridge_smoke_support.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <iostream>
#include <string>
#include <vector>

namespace cyclesrenderer::smoke {

bool run_render_scenarios(SmokeContext& context) {
    const bool require_optix = context.require_optix;
    CyclesBridgeRenderer* renderer = context.renderer;
    CyclesBridgeRenderSettings& settings = context.settings;
    auto& vertices = context.vertices;
    auto& triangles = context.triangles;
    CyclesBridgeSection& section = context.section;
    CyclesBridgeCamera& camera = context.camera;
    std::vector<std::uint8_t>& pixels = context.pixels;
    CyclesBridgeFrame& frame = context.frame;
    std::string& info = context.info;
    CyclesBridgeDiagnostics& diagnostics = context.diagnostics;
    vertices = {{
        {-2.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0xFFFFFFFFU, 0U},
        {2.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 0.0F, 0xFFFFFFFFU, 0U},
        {2.0F, 4.0F, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 0xFFFFFFFFU, 0U},
        {-2.0F, 4.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 0xFFFFFFFFU, 0U},
    }};
    // Keep the initial color baseline entirely CUTOUT; transmission materials are exercised later.
    triangles = {{
        {0U, 1U, 2U, 0U},
        {0U, 2U, 3U, 0U},
    }};
    const std::array<CyclesBridgeMaterial, 3> materials = {{
        {0U,
         CYCLES_BRIDGE_MATERIAL_CUTOUT,
         0.0F,
         0.5F,
         1U,
         2U,
         CYCLES_BRIDGE_PBR_LAB_1_3,
         3U},
        {0U,
         CYCLES_BRIDGE_MATERIAL_TRANSMISSION | CYCLES_BRIDGE_MATERIAL_WATER,
         0.0F,
         0.5F,
         1U,
         2U,
         CYCLES_BRIDGE_PBR_LAB_1_3,
         3U},
        {0U,
         CYCLES_BRIDGE_MATERIAL_TRANSMISSION,
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
        return false;
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
        return false;
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
        return false;
    }
    section = {};
    section.struct_size = sizeof(section);
    section.struct_version = 1;
    section.section_id = 42;
    section.vertex_count = static_cast<std::uint32_t>(vertices.size());
    section.triangle_count = static_cast<std::uint32_t>(triangles.size());

    settings.transmission_bounces = 2U;
    settings.transparent_bounces = 32U;
    settings.revision++;
    std::cerr << "[smoke] Streaming textured section; " << renderer_info(renderer) << '\n';
    if (!require_ok(
            cycles_bridge_apply_settings(renderer, &settings),
            "transmission settings")
        || !wait_for_settings(renderer, settings.revision)
        || !require_ok(
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
        return false;
    }

    camera = {};
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
        return false;
    }

    pixels.assign(static_cast<std::size_t>(kWidth) * kHeight * 4U, 0U);
    frame = {};
    frame.struct_size = sizeof(frame);
    frame.struct_version = 1;
    info.clear();
    if (!wait_for_updated_frame(
            renderer, camera, frame, pixels, "initial section", info, false)) {
        return false;
    }
    if (require_optix && info.find("backend=OPTIX") == std::string::npos) {
        std::cerr << "OptiX was required but another backend was selected: " << info << '\n';
        return false;
    }
    if (!has_rgb_variation(pixels)) {
        std::cerr << "completed frame contains only the background; camera may face away from the scene\n";
        return false;
    }
    if (!has_green_dominant_pixel(pixels)) {
        std::cerr << "completed frame did not preserve the green texture channel\n";
        return false;
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
        return false;
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
        return false;
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
        return false;
    }
    camera.focus_distance = 0.0F;
    camera.flags = 0U;

    std::cerr << "[smoke] Switching to post-process depth of field\n";
    settings.depth_of_field_mode = CYCLES_BRIDGE_DEPTH_OF_FIELD_POST_PROCESS;
    settings.revision++;
    CyclesBridgeDiagnostics post_dof_diagnostics{};
    post_dof_diagnostics.struct_size = sizeof(post_dof_diagnostics);
    post_dof_diagnostics.struct_version = 1U;
    if (!require_ok(
            cycles_bridge_apply_settings(renderer, &settings),
            "post-process depth-of-field settings")
        || !wait_for_settings(renderer, settings.revision)
        || !wait_for_updated_frame(
            renderer, camera, frame, pixels, "post-process depth of field", info,
            false, CYCLES_BRIDGE_PASS_COMBINED)
        || !require_ok(
            cycles_bridge_query_diagnostics(renderer, &post_dof_diagnostics),
            "post-process depth-of-field diagnostics")
        || post_dof_diagnostics.reset_level != CYCLES_BRIDGE_RESET_SESSION
        || std::abs(post_dof_diagnostics.aperture_size) > 1.0e-7F
        || (post_dof_diagnostics.registered_pass_mask
            & (1ULL << CYCLES_BRIDGE_PASS_DEPTH)) == 0U) {
        std::cerr << "post-process depth of field did not use a pinhole camera/depth pass: "
                  << "reset=" << post_dof_diagnostics.reset_level
                  << ";aperture=" << post_dof_diagnostics.aperture_size
                  << ";passes=" << post_dof_diagnostics.registered_pass_mask << '\n';
        return false;
    }
    settings.depth_of_field_mode = CYCLES_BRIDGE_DEPTH_OF_FIELD_PHYSICAL;
    settings.revision++;
    CyclesBridgeDiagnostics physical_dof_diagnostics{};
    physical_dof_diagnostics.struct_size = sizeof(physical_dof_diagnostics);
    physical_dof_diagnostics.struct_version = 1U;
    if (!require_ok(
            cycles_bridge_apply_settings(renderer, &settings),
            "physical depth-of-field restore")
        || !wait_for_settings(renderer, settings.revision)
        || !wait_for_updated_frame(
            renderer, camera, frame, pixels, "physical depth-of-field restore", info,
            false, CYCLES_BRIDGE_PASS_COMBINED)
        || !require_ok(
            cycles_bridge_query_diagnostics(renderer, &physical_dof_diagnostics),
            "physical depth-of-field diagnostics")
        || physical_dof_diagnostics.reset_level != CYCLES_BRIDGE_RESET_SESSION
        || physical_dof_diagnostics.aperture_size <= 0.0F) {
        std::cerr << "physical depth of field did not restore the Cycles aperture: "
                  << "reset=" << physical_dof_diagnostics.reset_level
                  << ";aperture=" << physical_dof_diagnostics.aperture_size << '\n';
        return false;
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
                renderer, camera, frame, pixels, stage.c_str(), info, false,
                static_cast<int>(pass))
            || frame.width != kWidth || frame.height != kHeight) {
            return false;
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
        return false;
    }

    const std::uint64_t all_passes_mask = (1ULL << CYCLES_BRIDGE_PASS_COUNT) - 1ULL;
    CyclesBridgeDiagnostics render_start_diagnostics{};
    render_start_diagnostics.struct_size = sizeof(render_start_diagnostics);
    render_start_diagnostics.struct_version = 1;
    if (!require_ok(
            cycles_bridge_query_diagnostics(renderer, &render_start_diagnostics),
            "render start phase diagnostics")
        || render_start_diagnostics.render_start_count == 0U
        || static_cast<std::uint64_t>(
                render_start_diagnostics.max_render_configure_micros)
                + render_start_diagnostics.max_render_reset_micros
                + render_start_diagnostics.max_render_prepare_micros
                + render_start_diagnostics.max_session_start_micros == 0U) {
        std::cerr << "missing render start phase telemetry: starts="
                  << render_start_diagnostics.render_start_count
                  << ";phases=" << render_start_diagnostics.max_render_configure_micros
                  << '/' << render_start_diagnostics.max_render_reset_micros
                  << '/' << render_start_diagnostics.max_render_prepare_micros
                  << '/' << render_start_diagnostics.max_session_start_micros << '\n';
        return false;
    }
    if (render_start_diagnostics.cached_raw_pass_mask != all_passes_mask
        || render_start_diagnostics.cached_denoised_pass_mask != 0U
        || render_start_diagnostics.pass_cache_entry_count
            < CYCLES_BRIDGE_PASS_COUNT
        || render_start_diagnostics.pass_cache_bytes == 0U
        || render_start_diagnostics.pass_cache_bytes
            > render_start_diagnostics.pass_cache_budget_bytes) {
        std::cerr << "unexpected pre-panorama raw pass cache state: raw="
                  << render_start_diagnostics.cached_raw_pass_mask
                  << ";denoised="
                  << render_start_diagnostics.cached_denoised_pass_mask
                  << ";entries="
                  << render_start_diagnostics.pass_cache_entry_count
                  << ";bytes=" << render_start_diagnostics.pass_cache_bytes
                  << ";budget="
                  << render_start_diagnostics.pass_cache_budget_bytes << '\n';
        return false;
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
            || panorama_diagnostics.panorama_type != panorama_type
            || panorama_diagnostics.reset_level != CYCLES_BRIDGE_RESET_SESSION) {
            std::cerr << stage << " did not reach the native camera diagnostics\n";
            return false;
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
        return false;
    }

    diagnostics = {};
    diagnostics.struct_size = sizeof(diagnostics);
    diagnostics.struct_version = 1;
    if (!require_ok(
            cycles_bridge_query_diagnostics(renderer, &diagnostics),
            "diagnostics query")) {
        return false;
    }
    std::cerr << "[smoke] Device update phase telemetry\n";
    if (diagnostics.scene_timing_revision == 0U
        || diagnostics.active_device_phase > CYCLES_BRIDGE_DEVICE_PHASE_COUNT
        || std::all_of(
            std::begin(diagnostics.last_device_phase_micros),
            std::end(diagnostics.last_device_phase_micros),
            [](std::uint32_t micros) { return micros == 0U; })) {
        std::cerr << "device update phases were not captured for the completed scene\n";
        return false;
    }
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
        || diagnostics.cached_raw_pass_mask != 0U
        || diagnostics.cached_denoised_pass_mask != 0U
        || diagnostics.pass_cache_entry_count != 0U
        || diagnostics.pass_cache_bytes != 0U
        || diagnostics.pass_cache_hit_count == 0U
        || diagnostics.registered_pass_mask != all_passes_mask
        || diagnostics.pass_registry_rebuild_count
            < CYCLES_BRIDGE_PASS_COUNT - 1U
        || diagnostics.pass_registry_hit_count == 0U
        || diagnostics.active_frame_variant != CYCLES_BRIDGE_FRAME_VARIANT_RAW
        || ((diagnostics.device_type == 1U || diagnostics.device_type == 2U)
            && diagnostics.device_uuid_valid == 0U)
        || (diagnostics.device_type == 3U && diagnostics.device_uuid_valid != 0U)) {
        std::cerr << "unexpected post-panorama render state: sampling-pattern="
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
        return false;
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
        return false;
    }


    return true;
}

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

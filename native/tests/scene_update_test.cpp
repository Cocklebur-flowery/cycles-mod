#include "scene_update.h"

#include <Windows.h>

#include <array>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <memory>
#include <utility>
#include <vector>

namespace {

using cyclesrenderer::scene::ResourcesData;
using cyclesrenderer::scene::SceneSnapshot;
using cyclesrenderer::scene::SceneUpdateAccumulator;
using cyclesrenderer::scene::SectionData;
using cyclesrenderer::scene::SectionMap;

std::shared_ptr<const ResourcesData> make_resources(std::int32_t origin_x) {
    auto resources = std::make_shared<ResourcesData>();
    resources->resources.struct_size = sizeof(CyclesBridgeSceneResources);
    resources->resources.struct_version = 1;
    resources->resources.origin_x = origin_x;
    return resources;
}

std::shared_ptr<const SectionData> make_section(
    std::int64_t section_id,
    std::int32_t origin_x) {
    auto section = std::make_shared<SectionData>();
    section->section.struct_size = sizeof(CyclesBridgeSection);
    section->section.struct_version = 1;
    section->section.section_id = section_id;
    section->section.origin_x = origin_x;
    return section;
}

bool require(bool condition, const char* message) {
    if (condition) {
        return true;
    }
    std::cerr << "scene update test failed: " << message << '\n';
    return false;
}

}  // namespace

bool run_scene_update_tests() {
    const auto resources = make_resources(0);
    const auto first = make_section(11, 0);
    const auto first_updated = make_section(11, 16);
    const auto second = make_section(22, 32);

    SceneUpdateAccumulator accumulator;
    accumulator.reset(resources);
    accumulator.upsert(first);
    const auto update_1 = accumulator.commit(1);
    if (!require(update_1->replace_all, "first update must replace the snapshot")
        || !require(update_1->mutations.size() == 1, "first update mutation count")
        || !require(update_1->section_count == 1, "first update section count")) {
        return false;
    }

    accumulator.upsert(first_updated);
    accumulator.upsert(second);
    const auto update_2 = accumulator.commit(2);
    if (!require(update_2->replace_all, "replacement remains pending until acknowledged")
        || !require(update_2->mutations.size() == 2, "coalesced update mutation count")) {
        return false;
    }

    SceneSnapshot snapshot;
    cyclesrenderer::scene::apply_scene_update(snapshot, *update_1);
    accumulator.acknowledge(*update_1);
    if (!require(snapshot.sections.at(11) == first, "first update was not applied")
        || !require(accumulator.pending_count() == 2,
                    "stale acknowledgement removed newer mutations")
        || !require(!accumulator.replace_all_pending(),
                    "applied full replacement was not acknowledged")) {
        return false;
    }

    accumulator.remove(11);
    const auto update_3 = accumulator.commit(3);
    if (!require(!update_3->replace_all, "incremental update became a replacement")
        || !require(update_3->mutations.size() == 2, "pending mutations were not merged")
        || !require(update_3->section_count == 1, "removal section count")) {
        return false;
    }
    cyclesrenderer::scene::apply_scene_update(snapshot, *update_3);
    accumulator.acknowledge(*update_3);
    if (!require(!snapshot.sections.contains(11), "section removal was not applied")
        || !require(snapshot.sections.at(22) == second, "new section was not applied")
        || !require(accumulator.pending_count() == 0, "applied mutations remain pending")) {
        return false;
    }

    accumulator.acknowledge(*update_2);
    if (!require(accumulator.pending_count() == 0,
                 "late acknowledgement changed cleared state")) {
        return false;
    }

    accumulator.upsert(make_section(44, 64));
    const auto update_after_ack = accumulator.commit(4);
    if (!require(update_after_ack->mutations.size() == 1,
                 "commit copied acknowledged resident sections")) {
        return false;
    }
    cyclesrenderer::scene::apply_scene_update(snapshot, *update_after_ack);
    accumulator.acknowledge(*update_after_ack);

    const auto replacement_resources = make_resources(128);
    const auto replacement_section = make_section(33, 128);
    SectionMap replacement_sections;
    replacement_sections.emplace(33, replacement_section);
    accumulator.replace(replacement_resources, std::move(replacement_sections));
    const auto update_4 = accumulator.commit(5);
    accumulator.acknowledge(*update_3);
    if (!require(update_4->replace_all, "new epoch must replace the snapshot")
        || !require(accumulator.pending_count() == 1,
                    "old epoch acknowledgement removed replacement")) {
        return false;
    }
    cyclesrenderer::scene::apply_scene_update(snapshot, *update_4);
    accumulator.acknowledge(*update_4);
    return require(snapshot.resources == replacement_resources,
                   "replacement resources were not applied")
        && require(snapshot.sections.size() == 1
                       && snapshot.sections.at(33) == replacement_section,
                   "replacement sections were not applied")
        && require(accumulator.pending_count() == 0,
                   "replacement acknowledgement left pending state");
}

namespace {

constexpr std::uint32_t kWidth = 160;
constexpr std::uint32_t kHeight = 90;

bool require_ok(std::uint32_t status, const char* operation) {
    if (status == CYCLES_BRIDGE_STATUS_OK) {
        return true;
    }
    std::cerr << operation << " failed with status " << status << '\n';
    return false;
}

std::uint64_t checksum(const std::vector<std::uint8_t>& pixels) {
    std::uint64_t result = 1469598103934665603ULL;
    for (const std::uint8_t value : pixels) {
        result ^= value;
        result *= 1099511628211ULL;
    }
    return result;
}

bool wait_for_changed_frame(
    CyclesBridgeRenderer* renderer,
    CyclesBridgeCamera& camera,
    CyclesBridgeFrame& frame,
    std::vector<std::uint8_t>& pixels,
    std::uint64_t previous_checksum,
    std::uint32_t expected_section_count,
    std::uint64_t minimum_commit_count) {
    for (int attempt = 0; attempt < 400; ++attempt) {
        camera.frame_id++;
        if (!require_ok(
                cycles_bridge_render_frame(
                    renderer, &camera, &frame, pixels.data(), pixels.size()),
                "scene update frame")) {
            return false;
        }
        CyclesBridgeDiagnostics diagnostics{};
        diagnostics.struct_size = sizeof(diagnostics);
        diagnostics.struct_version = 1;
        if (!require_ok(
                cycles_bridge_query_diagnostics(renderer, &diagnostics),
                "scene update diagnostics")) {
            return false;
        }
        if ((frame.flags & CYCLES_BRIDGE_FRAME_UPDATED) != 0U
            && checksum(pixels) != previous_checksum
            && diagnostics.section_count == expected_section_count
            && diagnostics.scene_commit_count >= minimum_commit_count) {
            return true;
        }
        Sleep(10);
    }
    std::cerr << "scene update did not produce the expected frame\n";
    return false;
}

}  // namespace

bool run_scene_update_integration_test() {
    CyclesBridgeRenderer* renderer = nullptr;
    if (!require_ok(
            cycles_bridge_create_renderer(&renderer), "scene update renderer creation")
        || renderer == nullptr) {
        return false;
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
         0U,
         0.0F,
         0.5F,
         CYCLES_BRIDGE_TEXTURE_INDEX_INVALID,
         CYCLES_BRIDGE_TEXTURE_INDEX_INVALID,
         CYCLES_BRIDGE_PBR_NONE,
         0U},
    }};
    const std::array<std::uint8_t, 4> texture_pixels = {{255U, 255U, 255U, 255U}};
    const std::array<CyclesBridgeTexture, 1> textures = {{
        {1U, 1U, 0U, 4U, CYCLES_BRIDGE_TEXTURE_COLOR_SRGB, {0U, 0U, 0U}},
    }};
    CyclesBridgeSceneResources resources{};
    resources.struct_size = sizeof(resources);
    resources.struct_version = 1;
    resources.material_count = 1;
    resources.texture_count = 1;
    resources.texture_byte_count = 4;
    CyclesBridgeSection section{};
    section.struct_size = sizeof(section);
    section.struct_version = 1;
    section.section_id = 42;
    section.vertex_count = static_cast<std::uint32_t>(vertices.size());
    section.triangle_count = static_cast<std::uint32_t>(triangles.size());

    if (!require_ok(
            cycles_bridge_reset_scene(
                renderer,
                &resources,
                materials.data(),
                textures.data(),
                texture_pixels.data()),
            "scene update reset")
        || !require_ok(
            cycles_bridge_upsert_section(
                renderer, &section, vertices.data(), triangles.data()),
            "scene update initial upsert")
        || !require_ok(
            cycles_bridge_commit_scene(renderer), "scene update initial commit")) {
        cycles_bridge_destroy_renderer(renderer);
        return false;
    }

    CyclesBridgeCamera camera{};
    camera.struct_size = sizeof(camera);
    camera.struct_version = 1;
    camera.viewport_width = kWidth;
    camera.viewport_height = kHeight;
    camera.position_y = 2.0;
    camera.position_z = 8.0;
    camera.rotation_w = 1.0F;
    camera.vertical_fov_radians = 1.04719755F;
    camera.depth_far = 100.0F;
    CyclesBridgeFrame frame{};
    frame.struct_size = sizeof(frame);
    frame.struct_version = 1;
    std::vector<std::uint8_t> pixels(
        static_cast<std::size_t>(kWidth) * kHeight * 4U);
    if (!wait_for_changed_frame(
            renderer, camera, frame, pixels, 0U, 1U, 1U)) {
        cycles_bridge_destroy_renderer(renderer);
        return false;
    }
    const std::uint64_t initial_checksum = checksum(pixels);

    std::vector<CyclesBridgeVertex> expanded_vertices(vertices.begin(), vertices.end());
    expanded_vertices.insert(expanded_vertices.end(), vertices.begin(), vertices.end());
    for (std::size_t index = vertices.size(); index < expanded_vertices.size(); ++index) {
        expanded_vertices[index].position_x += 3.0F;
    }
    std::vector<CyclesBridgeTriangle> expanded_triangles(triangles.begin(), triangles.end());
    expanded_triangles.push_back({4U, 5U, 6U, 0U});
    expanded_triangles.push_back({4U, 6U, 7U, 0U});
    CyclesBridgeSection expanded = section;
    expanded.vertex_count = static_cast<std::uint32_t>(expanded_vertices.size());
    expanded.triangle_count = static_cast<std::uint32_t>(expanded_triangles.size());

    constexpr std::uint64_t kBurstCommitCount = 32U;
    for (std::uint64_t index = 0; index < kBurstCommitCount; ++index) {
        const bool use_expanded = index % 2U == 0U
            || index + 1U == kBurstCommitCount;
        const CyclesBridgeSection& next_section = use_expanded ? expanded : section;
        const CyclesBridgeVertex* next_vertices = use_expanded
            ? expanded_vertices.data() : vertices.data();
        const CyclesBridgeTriangle* next_triangles = use_expanded
            ? expanded_triangles.data() : triangles.data();
        if (!require_ok(
                cycles_bridge_upsert_section(
                    renderer, &next_section, next_vertices, next_triangles),
                "burst update")
            || !require_ok(cycles_bridge_commit_scene(renderer), "burst update commit")) {
            cycles_bridge_destroy_renderer(renderer);
            return false;
        }
    }
    if (!wait_for_changed_frame(
            renderer,
            camera,
            frame,
            pixels,
            initial_checksum,
            1U,
            1U + kBurstCommitCount)) {
        cycles_bridge_destroy_renderer(renderer);
        return false;
    }

    const std::uint64_t expanded_checksum = checksum(pixels);
    const bool removed = require_ok(
            cycles_bridge_remove_section(renderer, section.section_id), "final removal")
        && require_ok(cycles_bridge_commit_scene(renderer), "final removal commit")
        && wait_for_changed_frame(
            renderer,
            camera,
            frame,
            pixels,
            expanded_checksum,
            0U,
            2U + kBurstCommitCount);
    cycles_bridge_destroy_renderer(renderer);
    return removed;
}

int main() {
    std::cerr << "[scene-update] Accumulator semantics\n";
    if (!run_scene_update_tests()) {
        return 1;
    }
    std::cerr << "[scene-update] Public ABI integration\n";
    if (!run_scene_update_integration_test()) {
        return 1;
    }
    std::cerr << "[scene-update] Complete\n";
    return 0;
}

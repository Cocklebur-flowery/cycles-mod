#include "scene_update.h"

#include <cstdint>
#include <iostream>
#include <memory>
#include <utility>

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

    const auto replacement_resources = make_resources(128);
    const auto replacement_section = make_section(33, 128);
    SectionMap replacement_sections;
    replacement_sections.emplace(33, replacement_section);
    accumulator.replace(replacement_resources, std::move(replacement_sections));
    const auto update_4 = accumulator.commit(4);
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

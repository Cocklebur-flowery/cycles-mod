#include "scene_update.h"

#include <stdexcept>
#include <utility>

namespace cyclesrenderer::scene {

void SceneSnapshot::clear() {
    resources.reset();
    sections.clear();
    revision = 0;
}

void SceneUpdateAccumulator::reset(
    std::shared_ptr<const ResourcesData> resources) {
    resources_ = std::move(resources);
    sections_.clear();
    pending_.clear();
    ++epoch_;
    mutation_sequence_ = 0;
    replace_all_pending_ = true;
}

void SceneUpdateAccumulator::replace(
    std::shared_ptr<const ResourcesData> resources,
    SectionMap sections) {
    reset(std::move(resources));
    sections_ = std::move(sections);
    for (const auto& entry : sections_) {
        record(entry.first, entry.second);
    }
}

void SceneUpdateAccumulator::upsert(SectionPtr section) {
    if (!section) {
        throw std::invalid_argument("cannot stage a null section");
    }
    const std::int64_t section_id = section->section.section_id;
    sections_[section_id] = section;
    record(section_id, std::move(section));
}

void SceneUpdateAccumulator::remove(std::int64_t section_id) {
    sections_.erase(section_id);
    record(section_id, nullptr);
}

std::shared_ptr<const SceneUpdate> SceneUpdateAccumulator::commit(
    std::uint64_t revision) const {
    auto update = std::make_shared<SceneUpdate>();
    update->resources = resources_;
    update->revision = revision;
    update->epoch = epoch_;
    update->section_count = sections_.size();
    update->replace_all = replace_all_pending_;
    update->mutations.reserve(pending_.size());
    for (const auto& entry : pending_) {
        update->mutations.emplace(
            entry.first,
            SectionMutation{entry.second.section, entry.second.sequence});
    }
    return update;
}

void SceneUpdateAccumulator::acknowledge(const SceneUpdate& update) {
    if (update.epoch != epoch_) {
        return;
    }
    for (const auto& entry : update.mutations) {
        const auto pending = pending_.find(entry.first);
        if (pending != pending_.end()
            && pending->second.sequence == entry.second.sequence) {
            pending_.erase(pending);
        }
    }
    if (update.replace_all) {
        replace_all_pending_ = false;
    }
}

const std::shared_ptr<const ResourcesData>& SceneUpdateAccumulator::resources() const {
    return resources_;
}

std::size_t SceneUpdateAccumulator::section_count() const {
    return sections_.size();
}

std::size_t SceneUpdateAccumulator::pending_count() const {
    return pending_.size();
}

bool SceneUpdateAccumulator::replace_all_pending() const {
    return replace_all_pending_;
}

void SceneUpdateAccumulator::record(
    std::int64_t section_id,
    SectionPtr section) {
    pending_[section_id] = PendingMutation{
        std::move(section), ++mutation_sequence_};
}

void apply_scene_update(SceneSnapshot& snapshot, const SceneUpdate& update) {
    if (!update.resources) {
        throw std::logic_error("scene update has no resources");
    }
    if (snapshot.resources != update.resources) {
        if (snapshot.resources && !update.replace_all) {
            throw std::logic_error("scene resources changed without a full replacement");
        }
        snapshot.resources = update.resources;
        snapshot.sections.clear();
    } else if (update.replace_all) {
        snapshot.sections.clear();
    }

    for (const auto& entry : update.mutations) {
        if (entry.second.section) {
            snapshot.sections[entry.first] = entry.second.section;
        } else {
            snapshot.sections.erase(entry.first);
        }
    }
    if (snapshot.sections.size() != update.section_count) {
        throw std::logic_error("scene update produced an unexpected section count");
    }
    snapshot.revision = update.revision;
}

}  // namespace cyclesrenderer::scene

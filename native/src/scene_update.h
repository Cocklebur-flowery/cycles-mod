#pragma once

#include "cycles_bridge.h"

#include <cstddef>
#include <cstdint>
#include <memory>
#include <unordered_map>
#include <vector>

namespace cyclesrenderer::scene {

struct SectionData {
    CyclesBridgeSection section{};
    std::vector<CyclesBridgeVertex> vertices;
    std::vector<CyclesBridgeTriangle> triangles;
};

struct ResourcesData {
    CyclesBridgeSceneResources resources{};
    std::vector<CyclesBridgeMaterial> materials;
    std::vector<CyclesBridgeTexture> textures;
    std::vector<std::uint8_t> texture_pixels;
};

using SectionPtr = std::shared_ptr<const SectionData>;
using SectionMap = std::unordered_map<std::int64_t, SectionPtr>;

struct SectionMutation {
    SectionPtr section;
    std::uint64_t sequence = 0;
};

struct SceneUpdate {
    std::shared_ptr<const ResourcesData> resources;
    std::unordered_map<std::int64_t, SectionMutation> mutations;
    std::uint64_t revision = 0;
    std::uint64_t epoch = 0;
    std::size_t section_count = 0;
    bool replace_all = false;
};

struct SceneSnapshot {
    std::shared_ptr<const ResourcesData> resources;
    SectionMap sections;
    std::uint64_t revision = 0;

    void clear();
};

class SceneUpdateAccumulator {
 public:
    void reset(std::shared_ptr<const ResourcesData> resources);
    void replace(
        std::shared_ptr<const ResourcesData> resources,
        SectionMap sections);
    void upsert(SectionPtr section);
    void remove(std::int64_t section_id);

    [[nodiscard]] std::shared_ptr<const SceneUpdate> commit(
        std::uint64_t revision) const;
    void acknowledge(const SceneUpdate& update);

    [[nodiscard]] const std::shared_ptr<const ResourcesData>& resources() const;
    [[nodiscard]] std::size_t section_count() const;
    [[nodiscard]] std::size_t pending_count() const;
    [[nodiscard]] bool replace_all_pending() const;

 private:
    struct PendingMutation {
        SectionPtr section;
        std::uint64_t sequence = 0;
    };

    void record(std::int64_t section_id, SectionPtr section);

    std::shared_ptr<const ResourcesData> resources_;
    SectionMap sections_;
    std::unordered_map<std::int64_t, PendingMutation> pending_;
    std::uint64_t epoch_ = 0;
    std::uint64_t mutation_sequence_ = 0;
    bool replace_all_pending_ = false;
};

void apply_scene_update(SceneSnapshot& snapshot, const SceneUpdate& update);

}  // namespace cyclesrenderer::scene

#include "texture_region_replay.h"

#include "cycles_scene_builder.h"

#include <algorithm>

namespace cyclesrenderer::texture_update {

void TextureRegionReplay::reset() {
    resident_ = false;
}

void TextureRegionReplay::mark_resident() {
    resident_ = true;
}

bool TextureRegionReplay::resident() const {
    return resident_;
}

bool TextureRegionReplay::apply(
    ccl::Scene* scene,
    std::span<const TextureRegionBatchPtr> updates,
    scene_builder::SceneRuntime& runtime) const {
    if (!resident_ || updates.empty()) {
        return false;
    }
    for (const TextureRegionBatchPtr& update : updates) {
        scene_builder::apply_texture_region_batch(scene, *update, runtime);
    }
    return true;
}

void TextureRegionReplay::acknowledge(
    TextureRegionStaging& staging,
    std::span<const TextureRegionBatchPtr> applied,
    std::vector<TextureRegionBatchPtr>& published) const {
    staging.acknowledge(applied);
    if (std::ranges::equal(published, applied)) {
        published.clear();
    }
}

}  // namespace cyclesrenderer::texture_update

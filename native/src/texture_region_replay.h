#pragma once

#include "texture_region_staging.h"

#include <span>

namespace ccl {
class Scene;
}

namespace cyclesrenderer::scene_builder {
struct SceneRuntime;
}

namespace cyclesrenderer::texture_update {

/** Defers committed atlas regions until the active Cycles images are resident. */
class TextureRegionReplay final {
 public:
    void reset();
    void mark_resident();
    [[nodiscard]] bool resident() const;

    bool apply(
        ccl::Scene* scene,
        std::span<const TextureRegionBatchPtr> updates,
        scene_builder::SceneRuntime& runtime) const;

    void acknowledge(
        TextureRegionStaging& staging,
        std::span<const TextureRegionBatchPtr> applied,
        std::vector<TextureRegionBatchPtr>& published) const;

 private:
    bool resident_ = false;
};

}  // namespace cyclesrenderer::texture_update

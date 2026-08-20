#include "texture_region_staging.h"

#include <utility>

namespace cyclesrenderer::texture_update {

void TextureRegionStaging::reset() {
    accumulator_.clear();
    generation_pending_ = true;
}

bool TextureRegionStaging::stage(
    TextureRegionBatch update,
    std::span<const TextureLayout> layouts,
    std::string& error) {
    if (!validate_texture_region_batch(update, layouts, error)) {
        return false;
    }
    if (!generation_pending_) {
        return accumulator_.stage(std::move(update), error);
    }
    if (update.generation <= last_generation_) {
        error = "texture region generation did not cross the reset barrier";
        return false;
    }
    if (!accumulator_.reset(update.generation, layouts, error)) {
        return false;
    }
    if (!accumulator_.stage(std::move(update), error)) {
        accumulator_.clear();
        return false;
    }
    last_generation_ = accumulator_.generation();
    generation_pending_ = false;
    return true;
}

std::vector<TextureRegionBatchPtr> TextureRegionStaging::commit() const {
    return accumulator_.commit();
}

void TextureRegionStaging::acknowledge(
    std::span<const TextureRegionBatchPtr> updates) {
    accumulator_.acknowledge(updates);
}

}  // namespace cyclesrenderer::texture_update

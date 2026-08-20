#pragma once

#include "texture_region_update.h"

#include <span>
#include <string>
#include <vector>

namespace cyclesrenderer::texture_update {

/** Adds a persistent reset-generation barrier around the private accumulator. */
class TextureRegionStaging {
 public:
    void reset();

    bool stage(
        TextureRegionBatch update,
        std::span<const TextureLayout> layouts,
        std::string& error);

    std::vector<TextureRegionBatchPtr> commit() const;

    void acknowledge(std::span<const TextureRegionBatchPtr> updates);

 private:
    TextureRegionUpdateAccumulator accumulator_;
    std::uint64_t last_generation_ = 0U;
    bool generation_pending_ = true;
};

}  // namespace cyclesrenderer::texture_update

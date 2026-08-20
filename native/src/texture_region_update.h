#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <span>
#include <string>
#include <unordered_map>
#include <vector>

namespace cyclesrenderer::texture_update {

constexpr std::size_t kTextureRegionSlotCount = 4U;

struct TextureLayout {
    std::uint32_t width = 0U;
    std::uint32_t height = 0U;
};

struct TextureRegion {
    std::uint32_t texture_index = 0U;
    std::uint32_t x = 0U;
    std::uint32_t y = 0U;
    std::uint32_t width = 0U;
    std::uint32_t height = 0U;
    std::uint32_t row_stride = 0U;
    std::vector<std::uint8_t> pixels;
};

struct TextureRegionBatch {
    std::uint64_t generation = 0U;
    std::uint64_t revision = 0U;
    std::uint32_t sprite_index = 0U;
    std::vector<TextureRegion> regions;
};

using TextureRegionBatchPtr = std::shared_ptr<const TextureRegionBatch>;

bool validate_texture_region_batch(
    const TextureRegionBatch& batch,
    std::span<const TextureLayout> layouts,
    std::string& error);

class TextureRegionUpdateAccumulator {
 public:
    bool reset(
        std::uint64_t generation,
        std::span<const TextureLayout> layouts,
        std::string& error);

    bool stage(TextureRegionBatch batch, std::string& error);

    std::vector<TextureRegionBatchPtr> commit() const;

    void acknowledge(std::span<const TextureRegionBatchPtr> updates);

    void clear();

    std::uint64_t generation() const;
    std::size_t pending_size() const;

 private:
    bool active_ = false;
    std::uint64_t generation_ = 0U;
    std::vector<TextureLayout> layouts_;
    std::unordered_map<std::uint32_t, std::uint64_t> latest_revisions_;
    std::unordered_map<std::uint32_t, TextureRegionBatchPtr> pending_;
};

}  // namespace cyclesrenderer::texture_update

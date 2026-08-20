#include "texture_region_update.h"

#include <algorithm>
#include <limits>
#include <utility>

namespace cyclesrenderer::texture_update {
namespace {

bool validate_layouts(
    std::span<const TextureLayout> layouts,
    std::string& error) {
    if (layouts.size() != kTextureRegionSlotCount) {
        error = "texture region generation requires four atlas layouts";
        return false;
    }
    const TextureLayout first = layouts.front();
    if (first.width == 0U || first.height == 0U) {
        error = "texture region atlas dimensions must be positive";
        return false;
    }
    for (const TextureLayout layout : layouts) {
        if (layout.width != first.width || layout.height != first.height) {
            error = "texture region atlases do not share one layout";
            return false;
        }
    }
    return true;
}

bool same_rectangle(
    const TextureRegion& first,
    const TextureRegion& candidate) {
    return candidate.x == first.x
        && candidate.y == first.y
        && candidate.width == first.width
        && candidate.height == first.height
        && candidate.row_stride == first.row_stride;
}

}  // namespace

bool validate_texture_region_batch(
    const TextureRegionBatch& batch,
    std::span<const TextureLayout> layouts,
    std::string& error) {
    if (!validate_layouts(layouts, error)) {
        return false;
    }
    if (batch.generation == 0U || batch.revision == 0U) {
        error = "texture region generation and revision must be positive";
        return false;
    }
    if (batch.regions.size() != kTextureRegionSlotCount) {
        error = "texture region batch must contain four regions";
        return false;
    }

    const TextureRegion& first = batch.regions.front();
    for (std::size_t index = 0; index < batch.regions.size(); ++index) {
        const TextureRegion& region = batch.regions[index];
        if (region.texture_index != index) {
            error = "texture regions are not in fixed slot order";
            return false;
        }
        if (!same_rectangle(first, region)) {
            error = "texture regions do not share one rectangle";
            return false;
        }
        const TextureLayout layout = layouts[index];
        if (region.width == 0U || region.height == 0U
            || region.width > layout.width || region.height > layout.height
            || region.x > layout.width - region.width
            || region.y > layout.height - region.height) {
            error = "texture region lies outside its atlas";
            return false;
        }
        const std::uint64_t expected_stride =
            static_cast<std::uint64_t>(region.width) * 4U;
        if (expected_stride > std::numeric_limits<std::uint32_t>::max()
            || region.row_stride != expected_stride) {
            error = "texture region row stride is not tightly packed RGBA8";
            return false;
        }
        const std::uint64_t expected_bytes =
            expected_stride * static_cast<std::uint64_t>(region.height);
        if (expected_bytes > std::numeric_limits<std::size_t>::max()
            || region.pixels.size() != expected_bytes) {
            error = "texture region pixel byte count is invalid";
            return false;
        }
    }
    error.clear();
    return true;
}

bool TextureRegionUpdateAccumulator::reset(
    const std::uint64_t generation,
    std::span<const TextureLayout> layouts,
    std::string& error) {
    if (generation == 0U || (active_ && generation <= generation_)) {
        error = "texture region generation did not advance";
        return false;
    }
    if (!validate_layouts(layouts, error)) {
        return false;
    }
    active_ = true;
    generation_ = generation;
    layouts_.assign(layouts.begin(), layouts.end());
    latest_revisions_.clear();
    pending_.clear();
    error.clear();
    return true;
}

bool TextureRegionUpdateAccumulator::stage(
    TextureRegionBatch batch,
    std::string& error) {
    if (!active_) {
        error = "texture region generation is not active";
        return false;
    }
    if (batch.generation != generation_) {
        error = "texture region batch belongs to a stale generation";
        return false;
    }
    if (!validate_texture_region_batch(batch, layouts_, error)) {
        return false;
    }
    const auto latest = latest_revisions_.find(batch.sprite_index);
    if (latest != latest_revisions_.end() && batch.revision <= latest->second) {
        error = "texture region revision is not newer for its sprite";
        return false;
    }
    const std::uint32_t sprite_index = batch.sprite_index;
    const std::uint64_t revision = batch.revision;
    pending_[sprite_index] =
        std::make_shared<const TextureRegionBatch>(std::move(batch));
    latest_revisions_[sprite_index] = revision;
    error.clear();
    return true;
}

std::vector<TextureRegionBatchPtr> TextureRegionUpdateAccumulator::commit() const {
    std::vector<TextureRegionBatchPtr> result;
    result.reserve(pending_.size());
    for (const auto& entry : pending_) {
        result.push_back(entry.second);
    }
    std::ranges::sort(result, [](const auto& first, const auto& second) {
        return first->revision < second->revision
            || (first->revision == second->revision
                && first->sprite_index < second->sprite_index);
    });
    return result;
}

void TextureRegionUpdateAccumulator::acknowledge(
    std::span<const TextureRegionBatchPtr> updates) {
    for (const TextureRegionBatchPtr& update : updates) {
        if (!update) {
            continue;
        }
        const auto current = pending_.find(update->sprite_index);
        if (current != pending_.end() && current->second == update) {
            pending_.erase(current);
        }
    }
}

void TextureRegionUpdateAccumulator::clear() {
    active_ = false;
    generation_ = 0U;
    layouts_.clear();
    latest_revisions_.clear();
    pending_.clear();
}

std::uint64_t TextureRegionUpdateAccumulator::generation() const {
    return generation_;
}

std::size_t TextureRegionUpdateAccumulator::pending_size() const {
    return pending_.size();
}

}  // namespace cyclesrenderer::texture_update

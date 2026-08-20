#include "texture_region_update.h"

#include <array>
#include <cstdint>
#include <iostream>
#include <string>
#include <utility>
#include <vector>

namespace {

using cyclesrenderer::texture_update::TextureLayout;
using cyclesrenderer::texture_update::TextureRegion;
using cyclesrenderer::texture_update::TextureRegionBatch;
using cyclesrenderer::texture_update::TextureRegionBatchPtr;
using cyclesrenderer::texture_update::TextureRegionUpdateAccumulator;

constexpr std::array<TextureLayout, 4> kLayouts{{
    {8U, 8U}, {8U, 8U}, {8U, 8U}, {8U, 8U},
}};

bool require(bool condition, const char* message) {
    if (!condition) {
        std::cerr << "texture region update test failed: " << message << '\n';
    }
    return condition;
}

TextureRegionBatch make_batch(
    std::uint64_t generation,
    std::uint64_t revision,
    std::uint32_t sprite_index,
    std::uint32_t x = 2U,
    std::uint32_t y = 3U) {
    TextureRegionBatch batch{};
    batch.generation = generation;
    batch.revision = revision;
    batch.sprite_index = sprite_index;
    for (std::uint32_t texture_index = 0U; texture_index < 4U; ++texture_index) {
        TextureRegion region{};
        region.texture_index = texture_index;
        region.x = x;
        region.y = y;
        region.width = 2U;
        region.height = 2U;
        region.row_stride = 8U;
        region.pixels.assign(16U, static_cast<std::uint8_t>(revision + texture_index));
        batch.regions.push_back(std::move(region));
    }
    return batch;
}

bool test_latest_wins_and_ordering() {
    TextureRegionUpdateAccumulator accumulator;
    std::string error;
    if (!require(accumulator.reset(1U, kLayouts, error), "initial reset")) {
        return false;
    }
    if (!require(accumulator.stage(make_batch(1U, 3U, 7U), error), "first stage")
        || !require(accumulator.stage(make_batch(1U, 2U, 9U), error), "second sprite")
        || !require(accumulator.stage(make_batch(1U, 4U, 7U), error), "latest sprite")) {
        return false;
    }
    const std::vector<TextureRegionBatchPtr> snapshot = accumulator.commit();
    return require(snapshot.size() == 2U, "coalesced batch count")
        && require(snapshot[0]->revision == 2U && snapshot[0]->sprite_index == 9U,
                   "global revision order")
        && require(snapshot[1]->revision == 4U && snapshot[1]->sprite_index == 7U,
                   "latest sprite revision")
        && require(snapshot[1]->regions[3].pixels[0] == 7U,
                   "owned latest region pixels")
        && require(!accumulator.stage(make_batch(1U, 3U, 7U), error),
                   "out-of-order stage was accepted");
}

bool test_acknowledge_does_not_remove_newer_update() {
    TextureRegionUpdateAccumulator accumulator;
    std::string error;
    accumulator.reset(1U, kLayouts, error);
    accumulator.stage(make_batch(1U, 1U, 5U), error);
    const std::vector<TextureRegionBatchPtr> old_snapshot = accumulator.commit();
    accumulator.stage(make_batch(1U, 2U, 5U), error);
    accumulator.acknowledge(old_snapshot);
    const std::vector<TextureRegionBatchPtr> latest = accumulator.commit();
    accumulator.acknowledge(latest);
    return require(latest.size() == 1U && latest[0]->revision == 2U,
                   "old acknowledge removed latest update")
        && require(accumulator.pending_size() == 0U,
                   "matching acknowledge did not remove update");
}

bool test_generation_barrier() {
    TextureRegionUpdateAccumulator accumulator;
    std::string error;
    accumulator.reset(1U, kLayouts, error);
    accumulator.stage(make_batch(1U, 1U, 2U), error);
    if (!require(accumulator.reset(2U, kLayouts, error), "generation advance")
        || !require(accumulator.generation() == 2U, "active generation")
        || !require(accumulator.pending_size() == 0U, "barrier retained pending update")
        || !require(!accumulator.stage(make_batch(1U, 2U, 2U), error),
                   "stale generation was accepted")
        || !require(!accumulator.reset(2U, kLayouts, error),
                   "non-advancing generation was accepted")) {
        return false;
    }
    accumulator.clear();
    return require(accumulator.generation() == 0U && accumulator.pending_size() == 0U,
                   "clear retained generation state");
}

bool test_batch_validation() {
    TextureRegionUpdateAccumulator accumulator;
    std::string error;
    accumulator.reset(1U, kLayouts, error);

    TextureRegionBatch invalid = make_batch(1U, 1U, 1U);
    invalid.regions.pop_back();
    if (!require(!accumulator.stage(std::move(invalid), error), "partial batch was accepted")) {
        return false;
    }
    invalid = make_batch(1U, 1U, 1U);
    invalid.regions[1].texture_index = 2U;
    if (!require(!accumulator.stage(std::move(invalid), error), "slot order was accepted")) {
        return false;
    }
    invalid = make_batch(1U, 1U, 1U, 7U, 3U);
    if (!require(!accumulator.stage(std::move(invalid), error), "out-of-bounds batch was accepted")) {
        return false;
    }
    invalid = make_batch(1U, 1U, 1U);
    invalid.regions[2].row_stride = 9U;
    if (!require(!accumulator.stage(std::move(invalid), error), "invalid stride was accepted")) {
        return false;
    }
    invalid = make_batch(1U, 1U, 1U);
    invalid.regions[3].pixels.pop_back();
    if (!require(!accumulator.stage(std::move(invalid), error), "invalid byte count was accepted")) {
        return false;
    }
    invalid = make_batch(1U, 0U, 1U);
    return require(!accumulator.stage(std::move(invalid), error),
                   "zero revision was accepted");
}

}  // namespace

int main() {
    std::cerr << "[texture-region] Latest-wins ordering\n";
    if (!test_latest_wins_and_ordering()) {
        return 1;
    }
    std::cerr << "[texture-region] Acknowledge ordering\n";
    if (!test_acknowledge_does_not_remove_newer_update()) {
        return 1;
    }
    std::cerr << "[texture-region] Generation barrier\n";
    if (!test_generation_barrier()) {
        return 1;
    }
    std::cerr << "[texture-region] Batch validation\n";
    if (!test_batch_validation()) {
        return 1;
    }
    std::cerr << "[texture-region] Complete\n";
    return 0;
}

#include "cycles_bridge.h"

#include "cycles_bridge_renderer.h"
#include "cycles_bridge_texture_region_update_asserts.h"

#include <cstddef>
#include <cstdint>
#include <limits>
#include <new>
#include <string>
#include <utility>

namespace {

constexpr std::uint32_t kStructVersion = 1U;

bool copy_texture_region_update(
    const CyclesBridgeTextureRegionUpdate& source,
    const std::uint8_t* pixels,
    const std::uint64_t pixel_capacity,
    cyclesrenderer::texture_update::TextureRegionBatch& target) {
    const std::uint64_t expected_stride =
        static_cast<std::uint64_t>(source.width) * 4U;
    if (source.generation == 0U || source.revision == 0U
        || source.width == 0U || source.height == 0U
        || expected_stride > std::numeric_limits<std::uint32_t>::max()
        || source.row_stride != expected_stride) {
        return false;
    }
    const std::uint64_t expected_bytes =
        expected_stride * static_cast<std::uint64_t>(source.height);
    if (source.pixel_byte_count != expected_bytes || expected_bytes == 0U
        || expected_bytes > std::numeric_limits<std::size_t>::max() / 4U
        || pixel_capacity != expected_bytes * 4U || pixels == nullptr) {
        return false;
    }
    const std::uint64_t offsets[] = {
        source.color_pixel_offset,
        source.normal_pixel_offset,
        source.material_pixel_offset,
        source.auxiliary_pixel_offset,
    };
    target = {};
    target.generation = source.generation;
    target.revision = source.revision;
    target.sprite_index = source.sprite_index;
    target.regions.reserve(cyclesrenderer::texture_update::kTextureRegionSlotCount);
    const std::size_t byte_count = static_cast<std::size_t>(expected_bytes);
    for (std::uint32_t index = 0U;
         index < cyclesrenderer::texture_update::kTextureRegionSlotCount;
         ++index) {
        const std::uint64_t expected_offset = expected_bytes * index;
        if (offsets[index] != expected_offset) {
            return false;
        }
        cyclesrenderer::texture_update::TextureRegion region{};
        region.texture_index = index;
        region.x = source.x;
        region.y = source.y;
        region.width = source.width;
        region.height = source.height;
        region.row_stride = source.row_stride;
        const std::size_t offset = static_cast<std::size_t>(expected_offset);
        region.pixels.assign(pixels + offset, pixels + offset + byte_count);
        target.regions.push_back(std::move(region));
    }
    return true;
}

}  // namespace

std::uint32_t cycles_bridge_stage_texture_region(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeTextureRegionUpdate* update,
    const std::uint8_t* pixels,
    std::uint64_t pixel_capacity) {
    if (renderer == nullptr || renderer->engine == nullptr || update == nullptr
        || update->struct_size < sizeof(CyclesBridgeTextureRegionUpdate)
        || update->struct_version != kStructVersion) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    try {
        cyclesrenderer::texture_update::TextureRegionBatch copied;
        if (!copy_texture_region_update(*update, pixels, pixel_capacity, copied)) {
            return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
        }
        std::string error;
        return renderer->engine->stage_texture_region(std::move(copied), error)
            ? CYCLES_BRIDGE_STATUS_OK
            : CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    } catch (const std::bad_alloc&) {
        return CYCLES_BRIDGE_STATUS_OUT_OF_MEMORY;
    } catch (...) {
        return CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    }
}

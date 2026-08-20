#pragma once

// Included by cycles_bridge.h after CYCLES_BRIDGE_API and the renderer handle
// have been declared.
extern "C" {

struct CyclesBridgeTextureRegionUpdate {
    std::uint32_t struct_size;
    std::uint32_t struct_version;
    std::uint64_t generation;
    std::uint64_t revision;
    std::uint32_t sprite_index;
    std::uint32_t x;
    std::uint32_t y;
    std::uint32_t width;
    std::uint32_t height;
    std::uint32_t row_stride;
    std::uint64_t pixel_byte_count;
    std::uint64_t color_pixel_offset;
    std::uint64_t normal_pixel_offset;
    std::uint64_t material_pixel_offset;
    std::uint64_t auxiliary_pixel_offset;
};

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_stage_texture_region(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeTextureRegionUpdate* update,
    const std::uint8_t* pixels,
    std::uint64_t pixel_capacity);

}

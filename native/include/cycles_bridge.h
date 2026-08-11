#pragma once

#include <cstdint>

#if defined(_WIN32)
#if defined(CYCLES_BRIDGE_BUILD)
#define CYCLES_BRIDGE_API __declspec(dllexport)
#else
#define CYCLES_BRIDGE_API __declspec(dllimport)
#endif
#else
#define CYCLES_BRIDGE_API
#endif

extern "C" {

enum CyclesBridgeStatus : std::uint32_t {
    CYCLES_BRIDGE_STATUS_OK = 0,
    CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT = 1,
    CYCLES_BRIDGE_STATUS_BUFFER_TOO_SMALL = 2,
    CYCLES_BRIDGE_STATUS_OUT_OF_MEMORY = 3,
    CYCLES_BRIDGE_STATUS_UNINITIALIZED = 4,
};

struct CyclesBridgeCamera {
    std::uint32_t struct_size;
    std::uint32_t struct_version;
    std::uint64_t frame_id;
    std::uint32_t viewport_width;
    std::uint32_t viewport_height;
    double position_x;
    double position_y;
    double position_z;
    float rotation_x;
    float rotation_y;
    float rotation_z;
    float rotation_w;
    float vertical_fov_radians;
    float depth_far;
    std::uint32_t reserved[2];
};

struct CyclesBridgeVoxelScene {
    std::uint32_t struct_size;
    std::uint32_t struct_version;
    std::int32_t origin_x;
    std::int32_t origin_y;
    std::int32_t origin_z;
    std::uint32_t size_x;
    std::uint32_t size_y;
    std::uint32_t size_z;
    std::uint32_t reserved[2];
};

struct CyclesBridgeRenderer;

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_abi_version();

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_write_build_info(
    char* output,
    std::uint32_t capacity);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_fill_test_frame(
    std::uint8_t* rgba,
    std::uint32_t width,
    std::uint32_t height,
    std::uint64_t frame_id);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_create_renderer(
    CyclesBridgeRenderer** output_renderer);

CYCLES_BRIDGE_API void cycles_bridge_destroy_renderer(
    CyclesBridgeRenderer* renderer);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_upload_voxel_scene(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeVoxelScene* scene,
    const std::uint32_t* packed_voxels,
    std::uint64_t voxel_count);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_render(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeCamera* camera,
    std::uint8_t* rgba,
    std::uint64_t rgba_capacity);

}

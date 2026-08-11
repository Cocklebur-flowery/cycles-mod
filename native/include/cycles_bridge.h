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
    CYCLES_BRIDGE_STATUS_RENDER_ERROR = 5,
};

enum CyclesBridgeMaterialFlags : std::uint32_t {
    CYCLES_BRIDGE_MATERIAL_CUTOUT = 1U << 0U,
    CYCLES_BRIDGE_MATERIAL_BLEND = 1U << 1U,
};

enum CyclesBridgeFrameFlags : std::uint32_t {
    CYCLES_BRIDGE_FRAME_READY = 1U << 0U,
    CYCLES_BRIDGE_FRAME_UPDATED = 1U << 1U,
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

struct CyclesBridgeScene {
    std::uint32_t struct_size;
    std::uint32_t struct_version;
    std::int32_t origin_x;
    std::int32_t origin_y;
    std::int32_t origin_z;
    std::uint32_t vertex_count;
    std::uint32_t triangle_count;
    std::uint32_t material_count;
    std::uint32_t texture_count;
    std::uint32_t texture_byte_count;
    std::uint32_t reserved[2];
};

struct CyclesBridgeSceneResources {
    std::uint32_t struct_size;
    std::uint32_t struct_version;
    std::int32_t origin_x;
    std::int32_t origin_y;
    std::int32_t origin_z;
    std::uint32_t material_count;
    std::uint32_t texture_count;
    std::uint32_t texture_byte_count;
    std::uint32_t reserved[4];
};

struct CyclesBridgeSection {
    std::uint32_t struct_size;
    std::uint32_t struct_version;
    std::int64_t section_id;
    std::int32_t origin_x;
    std::int32_t origin_y;
    std::int32_t origin_z;
    std::uint32_t vertex_count;
    std::uint32_t triangle_count;
    std::uint32_t reserved[2];
};

struct CyclesBridgeFrame {
    std::uint32_t struct_size;
    std::uint32_t struct_version;
    std::uint32_t width;
    std::uint32_t height;
    std::uint64_t generation;
    std::uint32_t pixel_byte_count;
    std::uint32_t flags;
    std::uint32_t sample_count;
    std::uint32_t reserved;
};

struct CyclesBridgeVertex {
    float position_x;
    float position_y;
    float position_z;
    float normal_x;
    float normal_y;
    float normal_z;
    float texture_u;
    float texture_v;
    std::uint32_t packed_rgba;
    std::uint32_t reserved;
};

struct CyclesBridgeTriangle {
    std::uint32_t vertex_0;
    std::uint32_t vertex_1;
    std::uint32_t vertex_2;
    std::uint32_t material_index;
};

struct CyclesBridgeMaterial {
    std::uint32_t texture_index;
    std::uint32_t flags;
    float emission_strength;
    float alpha_cutoff;
    std::uint32_t reserved[4];
};

struct CyclesBridgeTexture {
    std::uint32_t width;
    std::uint32_t height;
    std::uint32_t pixel_offset;
    std::uint32_t pixel_size;
    std::uint32_t reserved[4];
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

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_write_renderer_info(
    const CyclesBridgeRenderer* renderer,
    char* output,
    std::uint32_t capacity);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_upload_scene(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeScene* scene,
    const CyclesBridgeVertex* vertices,
    const CyclesBridgeTriangle* triangles,
    const CyclesBridgeMaterial* materials,
    const CyclesBridgeTexture* textures,
    const std::uint8_t* texture_pixels);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_reset_scene(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeSceneResources* resources,
    const CyclesBridgeMaterial* materials,
    const CyclesBridgeTexture* textures,
    const std::uint8_t* texture_pixels);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_upsert_section(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeSection* section,
    const CyclesBridgeVertex* vertices,
    const CyclesBridgeTriangle* triangles);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_remove_section(
    CyclesBridgeRenderer* renderer,
    std::int64_t section_id);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_commit_scene(
    CyclesBridgeRenderer* renderer);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_render(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeCamera* camera,
    std::uint8_t* rgba,
    std::uint64_t rgba_capacity);

CYCLES_BRIDGE_API std::uint32_t cycles_bridge_render_frame(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeCamera* camera,
    CyclesBridgeFrame* frame,
    std::uint8_t* rgba,
    std::uint64_t rgba_capacity);

}

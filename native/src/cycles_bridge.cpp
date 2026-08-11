#include "cycles_bridge.h"

#include "cycles_engine.h"

#include <cstddef>
#include <cstring>
#include <limits>
#include <memory>
#include <new>
#include <string>

struct CyclesBridgeRenderer {
    std::unique_ptr<CyclesEngine> engine;
};

namespace {

constexpr std::uint32_t kAbiVersion = 3;
constexpr std::uint32_t kStructVersion = 1;
constexpr char kBuildInfo[] = "cyclesrenderer-native/cycles-5.2;abi=3";

static_assert(sizeof(CyclesBridgeCamera) == 80);
static_assert(offsetof(CyclesBridgeCamera, frame_id) == 8);
static_assert(offsetof(CyclesBridgeCamera, position_x) == 24);
static_assert(offsetof(CyclesBridgeCamera, rotation_x) == 48);
static_assert(offsetof(CyclesBridgeCamera, vertical_fov_radians) == 64);
static_assert(sizeof(CyclesBridgeVoxelScene) == 40);
static_assert(offsetof(CyclesBridgeVoxelScene, origin_x) == 8);
static_assert(offsetof(CyclesBridgeVoxelScene, size_x) == 20);

std::uint8_t to_byte(std::uint64_t value) {
    return static_cast<std::uint8_t>(value & 0xFFU);
}

std::uint32_t write_string(const std::string& value, char* output, std::uint32_t capacity) {
    if (output == nullptr) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    if (value.size() + 1U > capacity) {
        return CYCLES_BRIDGE_STATUS_BUFFER_TOO_SMALL;
    }
    std::memcpy(output, value.c_str(), value.size() + 1U);
    return CYCLES_BRIDGE_STATUS_OK;
}

bool expected_voxel_count(const CyclesBridgeVoxelScene& scene, std::uint64_t& output_count) {
    if (scene.size_x == 0 || scene.size_y == 0 || scene.size_z == 0) {
        return false;
    }
    const std::uint64_t xy = static_cast<std::uint64_t>(scene.size_x) * scene.size_y;
    if (xy > std::numeric_limits<std::uint64_t>::max() / scene.size_z) {
        return false;
    }
    output_count = xy * scene.size_z;
    return output_count <= std::numeric_limits<std::size_t>::max();
}

}  // namespace

std::uint32_t cycles_bridge_abi_version() {
    return kAbiVersion;
}

std::uint32_t cycles_bridge_write_build_info(char* output, std::uint32_t capacity) {
    return write_string(kBuildInfo, output, capacity);
}

std::uint32_t cycles_bridge_create_renderer(CyclesBridgeRenderer** output_renderer) {
    if (output_renderer == nullptr) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    *output_renderer = nullptr;
    try {
        auto renderer = std::make_unique<CyclesBridgeRenderer>();
        renderer->engine = std::make_unique<CyclesEngine>();
        *output_renderer = renderer.release();
        return CYCLES_BRIDGE_STATUS_OK;
    } catch (const std::bad_alloc&) {
        return CYCLES_BRIDGE_STATUS_OUT_OF_MEMORY;
    } catch (...) {
        return CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    }
}

void cycles_bridge_destroy_renderer(CyclesBridgeRenderer* renderer) {
    delete renderer;
}

std::uint32_t cycles_bridge_write_renderer_info(
    const CyclesBridgeRenderer* renderer,
    char* output,
    std::uint32_t capacity) {
    if (renderer == nullptr || renderer->engine == nullptr) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    return write_string(renderer->engine->renderer_info(), output, capacity);
}

std::uint32_t cycles_bridge_upload_voxel_scene(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeVoxelScene* scene,
    const std::uint32_t* packed_voxels,
    std::uint64_t voxel_count) {
    if (renderer == nullptr || renderer->engine == nullptr || scene == nullptr
        || packed_voxels == nullptr
        || scene->struct_size < sizeof(CyclesBridgeVoxelScene)
        || scene->struct_version != kStructVersion) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }

    std::uint64_t expected_count = 0;
    if (!expected_voxel_count(*scene, expected_count) || voxel_count != expected_count) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }

    try {
        std::string error;
        return renderer->engine->upload_voxel_scene(
                   *scene, packed_voxels, voxel_count, error)
            ? CYCLES_BRIDGE_STATUS_OK
            : CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    } catch (const std::bad_alloc&) {
        return CYCLES_BRIDGE_STATUS_OUT_OF_MEMORY;
    } catch (...) {
        return CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    }
}

std::uint32_t cycles_bridge_render(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeCamera* camera,
    std::uint8_t* rgba,
    std::uint64_t rgba_capacity) {
    if (renderer == nullptr || renderer->engine == nullptr || camera == nullptr || rgba == nullptr
        || camera->struct_size < sizeof(CyclesBridgeCamera)
        || camera->struct_version != kStructVersion) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }

    try {
        std::string error;
        return renderer->engine->render(*camera, rgba, rgba_capacity, error)
            ? CYCLES_BRIDGE_STATUS_OK
            : CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    } catch (const std::bad_alloc&) {
        return CYCLES_BRIDGE_STATUS_OUT_OF_MEMORY;
    } catch (...) {
        return CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    }
}

std::uint32_t cycles_bridge_fill_test_frame(
    std::uint8_t* rgba,
    std::uint32_t width,
    std::uint32_t height,
    std::uint64_t frame_id) {
    if (rgba == nullptr || width == 0 || height == 0) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }

    constexpr std::uint64_t kChannels = 4;
    if (static_cast<std::uint64_t>(width) * height
        > std::numeric_limits<std::uint64_t>::max() / kChannels) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }

    for (std::uint32_t y = 0; y < height; ++y) {
        for (std::uint32_t x = 0; x < width; ++x) {
            const std::uint64_t offset =
                (static_cast<std::uint64_t>(y) * width + x) * kChannels;
            rgba[offset] = to_byte(static_cast<std::uint64_t>(x) * 17 + frame_id);
            rgba[offset + 1] = to_byte(static_cast<std::uint64_t>(y) * 17 + frame_id * 3);
            rgba[offset + 2] = to_byte(static_cast<std::uint64_t>(x ^ y) * 15 + frame_id * 5);
            rgba[offset + 3] = 0xFFU;
        }
    }

    return CYCLES_BRIDGE_STATUS_OK;
}

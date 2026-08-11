#include "cycles_bridge.h"

#include "cycles_engine.h"

#include <cstddef>
#include <cmath>
#include <cstring>
#include <limits>
#include <memory>
#include <new>
#include <string>

struct CyclesBridgeRenderer {
    std::unique_ptr<CyclesEngine> engine;
};

namespace {

constexpr std::uint32_t kAbiVersion = 4;
constexpr std::uint32_t kStructVersion = 1;
constexpr char kBuildInfo[] = "cyclesrenderer-native/cycles-5.2;abi=4";

static_assert(sizeof(CyclesBridgeCamera) == 80);
static_assert(offsetof(CyclesBridgeCamera, frame_id) == 8);
static_assert(offsetof(CyclesBridgeCamera, position_x) == 24);
static_assert(offsetof(CyclesBridgeCamera, rotation_x) == 48);
static_assert(offsetof(CyclesBridgeCamera, vertical_fov_radians) == 64);
static_assert(sizeof(CyclesBridgeScene) == 48);
static_assert(offsetof(CyclesBridgeScene, origin_x) == 8);
static_assert(offsetof(CyclesBridgeScene, vertex_count) == 20);
static_assert(sizeof(CyclesBridgeVertex) == 40);
static_assert(offsetof(CyclesBridgeVertex, packed_rgba) == 32);
static_assert(sizeof(CyclesBridgeTriangle) == 16);
static_assert(sizeof(CyclesBridgeMaterial) == 32);
static_assert(sizeof(CyclesBridgeTexture) == 32);

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

bool valid_scene_data(
    const CyclesBridgeScene& scene,
    const CyclesBridgeVertex* vertices,
    const CyclesBridgeTriangle* triangles,
    const CyclesBridgeMaterial* materials,
    const CyclesBridgeTexture* textures,
    const std::uint8_t* texture_pixels) {
    if (scene.triangle_count == 0) {
        return scene.vertex_count == 0 && scene.material_count == 0
            && scene.texture_count == 0 && scene.texture_byte_count == 0;
    }
    if (scene.vertex_count == 0 || scene.material_count == 0 || scene.texture_count == 0
        || scene.texture_byte_count == 0 || vertices == nullptr || triangles == nullptr
        || materials == nullptr || textures == nullptr || texture_pixels == nullptr) {
        return false;
    }
    if (scene.vertex_count > static_cast<std::uint32_t>(std::numeric_limits<int>::max())
        || scene.triangle_count > static_cast<std::uint32_t>(std::numeric_limits<int>::max())
        || scene.material_count > static_cast<std::uint32_t>(std::numeric_limits<int>::max())) {
        return false;
    }
    for (std::uint32_t index = 0; index < scene.vertex_count; ++index) {
        const CyclesBridgeVertex& vertex = vertices[index];
        if (!std::isfinite(vertex.position_x) || !std::isfinite(vertex.position_y)
            || !std::isfinite(vertex.position_z) || !std::isfinite(vertex.normal_x)
            || !std::isfinite(vertex.normal_y) || !std::isfinite(vertex.normal_z)
            || !std::isfinite(vertex.texture_u) || !std::isfinite(vertex.texture_v)) {
            return false;
        }
    }
    for (std::uint32_t index = 0; index < scene.triangle_count; ++index) {
        const CyclesBridgeTriangle& triangle = triangles[index];
        if (triangle.vertex_0 >= scene.vertex_count || triangle.vertex_1 >= scene.vertex_count
            || triangle.vertex_2 >= scene.vertex_count
            || triangle.material_index >= scene.material_count) {
            return false;
        }
    }
    for (std::uint32_t index = 0; index < scene.material_count; ++index) {
        const CyclesBridgeMaterial& material = materials[index];
        if (material.texture_index >= scene.texture_count
            || (material.flags & ~CYCLES_BRIDGE_MATERIAL_CUTOUT) != 0U
            || !std::isfinite(material.emission_strength)
            || !std::isfinite(material.alpha_cutoff)
            || material.emission_strength < 0.0F
            || material.alpha_cutoff < 0.0F || material.alpha_cutoff > 1.0F) {
            return false;
        }
    }
    for (std::uint32_t index = 0; index < scene.texture_count; ++index) {
        const CyclesBridgeTexture& texture = textures[index];
        const std::uint64_t expected_size =
            static_cast<std::uint64_t>(texture.width) * texture.height * 4U;
        const std::uint64_t end =
            static_cast<std::uint64_t>(texture.pixel_offset) + texture.pixel_size;
        if (texture.width == 0 || texture.height == 0
            || expected_size != texture.pixel_size || end > scene.texture_byte_count) {
            return false;
        }
    }
    return true;
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

std::uint32_t cycles_bridge_upload_scene(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeScene* scene,
    const CyclesBridgeVertex* vertices,
    const CyclesBridgeTriangle* triangles,
    const CyclesBridgeMaterial* materials,
    const CyclesBridgeTexture* textures,
    const std::uint8_t* texture_pixels) {
    if (renderer == nullptr || renderer->engine == nullptr || scene == nullptr
        || scene->struct_size < sizeof(CyclesBridgeScene)
        || scene->struct_version != kStructVersion) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    if (!valid_scene_data(
            *scene, vertices, triangles, materials, textures, texture_pixels)) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }

    try {
        std::string error;
        return renderer->engine->upload_scene(
                   *scene,
                   vertices,
                   triangles,
                   materials,
                   textures,
                   texture_pixels,
                   error)
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

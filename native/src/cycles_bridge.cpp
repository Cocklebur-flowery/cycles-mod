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

constexpr std::uint32_t kAbiVersion = 8;
constexpr std::uint32_t kStructVersion = 1;
constexpr char kBuildInfo[] = "cyclesrenderer-native/cycles-5.2;abi=8";

static_assert(sizeof(CyclesBridgeCamera) == 80);
static_assert(offsetof(CyclesBridgeCamera, frame_id) == 8);
static_assert(offsetof(CyclesBridgeCamera, position_x) == 24);
static_assert(offsetof(CyclesBridgeCamera, rotation_x) == 48);
static_assert(offsetof(CyclesBridgeCamera, vertical_fov_radians) == 64);
static_assert(sizeof(CyclesBridgeScene) == 48);
static_assert(offsetof(CyclesBridgeScene, origin_x) == 8);
static_assert(offsetof(CyclesBridgeScene, vertex_count) == 20);
static_assert(sizeof(CyclesBridgeSceneResources) == 48);
static_assert(sizeof(CyclesBridgeSection) == 48);
static_assert(sizeof(CyclesBridgeFrame) == 40);
static_assert(sizeof(CyclesBridgeRenderSettings) == 208);
static_assert(sizeof(CyclesBridgeCapabilities) == 64);
static_assert(sizeof(CyclesBridgeDiagnostics) == 176);
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
            || (material.flags
                & ~(CYCLES_BRIDGE_MATERIAL_CUTOUT | CYCLES_BRIDGE_MATERIAL_BLEND)) != 0U
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

bool valid_resources_data(
    const CyclesBridgeSceneResources& resources,
    const CyclesBridgeMaterial* materials,
    const CyclesBridgeTexture* textures,
    const std::uint8_t* texture_pixels) {
    CyclesBridgeScene scene{};
    scene.material_count = resources.material_count;
    scene.texture_count = resources.texture_count;
    scene.texture_byte_count = resources.texture_byte_count;
    scene.vertex_count = 1;
    scene.triangle_count = 1;
    CyclesBridgeVertex vertex{};
    CyclesBridgeTriangle triangle{};
    return resources.material_count != 0 && resources.texture_count != 0
        && resources.texture_byte_count != 0
        && valid_scene_data(
            scene, &vertex, &triangle, materials, textures, texture_pixels);
}

bool valid_section_data(
    const CyclesBridgeSection& section,
    const CyclesBridgeVertex* vertices,
    const CyclesBridgeTriangle* triangles) {
    if (section.triangle_count == 0) {
        return section.vertex_count == 0;
    }
    if (section.vertex_count == 0 || vertices == nullptr || triangles == nullptr
        || section.vertex_count > static_cast<std::uint32_t>(std::numeric_limits<int>::max())
        || section.triangle_count > static_cast<std::uint32_t>(std::numeric_limits<int>::max())) {
        return false;
    }
    for (std::uint32_t index = 0; index < section.vertex_count; ++index) {
        const CyclesBridgeVertex& vertex = vertices[index];
        if (!std::isfinite(vertex.position_x) || !std::isfinite(vertex.position_y)
            || !std::isfinite(vertex.position_z) || !std::isfinite(vertex.normal_x)
            || !std::isfinite(vertex.normal_y) || !std::isfinite(vertex.normal_z)
            || !std::isfinite(vertex.texture_u) || !std::isfinite(vertex.texture_v)) {
            return false;
        }
    }
    for (std::uint32_t index = 0; index < section.triangle_count; ++index) {
        const CyclesBridgeTriangle& triangle = triangles[index];
        if (triangle.vertex_0 >= section.vertex_count
            || triangle.vertex_1 >= section.vertex_count
            || triangle.vertex_2 >= section.vertex_count) {
            return false;
        }
    }
    return true;
}

bool valid_settings(const CyclesBridgeRenderSettings& settings) {
    const auto valid_bool = [](std::uint32_t value) { return value <= 1U; };
    return settings.revision >= 1U
        && settings.device_policy <= 3U
        && settings.resolution_mode <= 1U
        && settings.render_width >= 160U && settings.render_width <= 3840U
        && settings.render_height >= 90U && settings.render_height <= 2160U
        && settings.resolution_percentage >= 25U && settings.resolution_percentage <= 100U
        && settings.interactive_samples >= 1U && settings.interactive_samples <= 4096U
        && settings.still_samples >= 1U && settings.still_samples <= 4096U
        && settings.stationary_delay_millis <= 10000U
        && valid_bool(settings.adaptive_sampling)
        && settings.minimum_samples <= 4096U
        && std::isfinite(settings.noise_threshold)
        && settings.noise_threshold >= 0.0F && settings.noise_threshold <= 1.0F
        && settings.interactive_time_limit_millis <= 60000U
        && settings.still_time_limit_millis <= 600000U
        && settings.minimum_bounce <= 64U && settings.maximum_bounce <= 64U
        && settings.minimum_bounce <= settings.maximum_bounce
        && settings.diffuse_bounces <= 64U && settings.glossy_bounces <= 64U
        && settings.transmission_bounces <= 64U && settings.volume_bounces <= 64U
        && settings.transparent_bounces <= 64U
        && std::isfinite(settings.clamp_direct)
        && settings.clamp_direct >= 0.0F && settings.clamp_direct <= 100000.0F
        && std::isfinite(settings.clamp_indirect)
        && settings.clamp_indirect >= 0.0F && settings.clamp_indirect <= 100000.0F
        && std::isfinite(settings.filter_glossy)
        && settings.filter_glossy >= 0.0F && settings.filter_glossy <= 100.0F
        && valid_bool(settings.reflective_caustics)
        && valid_bool(settings.refractive_caustics)
        && settings.pixel_filter <= 2U
        && std::isfinite(settings.filter_width)
        && settings.filter_width >= 0.01F && settings.filter_width <= 10.0F
        && settings.seed >= 0
        && settings.denoiser_mode <= 3U
        && settings.denoiser_start_sample >= 1U
        && settings.denoiser_start_sample <= 4096U
        && settings.denoiser_input <= 2U
        && settings.denoiser_prefilter <= 2U
        && settings.denoiser_quality <= 2U
        && valid_bool(settings.denoiser_use_gpu)
        && std::isfinite(settings.exposure_ev)
        && settings.exposure_ev >= -20.0F && settings.exposure_ev <= 20.0F
        && std::isfinite(settings.gamma)
        && settings.gamma >= 0.1F && settings.gamma <= 5.0F
        && settings.view_transform <= 3U
        && settings.active_pass < CYCLES_BRIDGE_PASS_COUNT
        && valid_bool(settings.debug_overlay);
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

std::uint32_t cycles_bridge_query_capabilities(
    const CyclesBridgeRenderer* renderer,
    CyclesBridgeCapabilities* capabilities) {
    if (renderer == nullptr || renderer->engine == nullptr || capabilities == nullptr
        || capabilities->struct_size < sizeof(CyclesBridgeCapabilities)
        || capabilities->struct_version != kStructVersion) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    try {
        renderer->engine->query_capabilities(*capabilities);
        return CYCLES_BRIDGE_STATUS_OK;
    } catch (...) {
        return CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    }
}

std::uint32_t cycles_bridge_apply_settings(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeRenderSettings* settings) {
    if (renderer == nullptr || renderer->engine == nullptr || settings == nullptr
        || settings->struct_size < sizeof(CyclesBridgeRenderSettings)
        || settings->struct_version != kStructVersion
        || !valid_settings(*settings)) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    try {
        std::string error;
        return renderer->engine->apply_settings(*settings, error)
            ? CYCLES_BRIDGE_STATUS_OK
            : CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    } catch (...) {
        return CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    }
}

std::uint32_t cycles_bridge_query_diagnostics(
    const CyclesBridgeRenderer* renderer,
    CyclesBridgeDiagnostics* diagnostics) {
    if (renderer == nullptr || renderer->engine == nullptr || diagnostics == nullptr
        || diagnostics->struct_size < sizeof(CyclesBridgeDiagnostics)
        || diagnostics->struct_version != kStructVersion) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    try {
        renderer->engine->query_diagnostics(*diagnostics);
        return CYCLES_BRIDGE_STATUS_OK;
    } catch (...) {
        return CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    }
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

std::uint32_t cycles_bridge_reset_scene(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeSceneResources* resources,
    const CyclesBridgeMaterial* materials,
    const CyclesBridgeTexture* textures,
    const std::uint8_t* texture_pixels) {
    if (renderer == nullptr || renderer->engine == nullptr || resources == nullptr
        || resources->struct_size < sizeof(CyclesBridgeSceneResources)
        || resources->struct_version != kStructVersion
        || !valid_resources_data(*resources, materials, textures, texture_pixels)) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    try {
        std::string error;
        return renderer->engine->reset_scene(
                   *resources, materials, textures, texture_pixels, error)
            ? CYCLES_BRIDGE_STATUS_OK
            : CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    } catch (const std::bad_alloc&) {
        return CYCLES_BRIDGE_STATUS_OUT_OF_MEMORY;
    } catch (...) {
        return CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    }
}

std::uint32_t cycles_bridge_upsert_section(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeSection* section,
    const CyclesBridgeVertex* vertices,
    const CyclesBridgeTriangle* triangles) {
    if (renderer == nullptr || renderer->engine == nullptr || section == nullptr
        || section->struct_size < sizeof(CyclesBridgeSection)
        || section->struct_version != kStructVersion
        || !valid_section_data(*section, vertices, triangles)) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    try {
        std::string error;
        return renderer->engine->upsert_section(*section, vertices, triangles, error)
            ? CYCLES_BRIDGE_STATUS_OK
            : CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    } catch (const std::bad_alloc&) {
        return CYCLES_BRIDGE_STATUS_OUT_OF_MEMORY;
    } catch (...) {
        return CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    }
}

std::uint32_t cycles_bridge_remove_section(
    CyclesBridgeRenderer* renderer,
    std::int64_t section_id) {
    if (renderer == nullptr || renderer->engine == nullptr) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    try {
        std::string error;
        return renderer->engine->remove_section(section_id, error)
            ? CYCLES_BRIDGE_STATUS_OK
            : CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    } catch (...) {
        return CYCLES_BRIDGE_STATUS_RENDER_ERROR;
    }
}

std::uint32_t cycles_bridge_commit_scene(CyclesBridgeRenderer* renderer) {
    if (renderer == nullptr || renderer->engine == nullptr) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    try {
        std::string error;
        return renderer->engine->commit_scene(error)
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

std::uint32_t cycles_bridge_render_frame(
    CyclesBridgeRenderer* renderer,
    const CyclesBridgeCamera* camera,
    CyclesBridgeFrame* frame,
    std::uint8_t* rgba,
    std::uint64_t rgba_capacity) {
    if (renderer == nullptr || renderer->engine == nullptr || camera == nullptr
        || frame == nullptr
        || camera->struct_size < sizeof(CyclesBridgeCamera)
        || camera->struct_version != kStructVersion
        || frame->struct_size < sizeof(CyclesBridgeFrame)
        || frame->struct_version != kStructVersion) {
        return CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT;
    }
    try {
        std::string error;
        return renderer->engine->render_frame(*camera, *frame, rgba, rgba_capacity, error)
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

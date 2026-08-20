#pragma once

#include "cycles_bridge.h"
#include "labpbr_material.h"
#include "realtime_section_mesh.h"
#include "scene_update.h"
#include "texture_region_update.h"

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

#include "scene/image.h"
#include "scene/image_loader.h"
#include "scene/integrator.h"
#include "scene/light.h"
#include "scene/object.h"
#include "scene/scene.h"
#include "scene/shader.h"
// Keep Cycles' scene include order. On MSVC, including background.h before
// scene.h and shader.h changes inline socket access and crashes tag_update().
#include "scene/background.h"
#include "scene/shader_graph.h"
#include "scene/shader_nodes.h"
#include "util/colorspace.h"
#include "util/image_metadata.h"
#include "util/transform.h"
#include "util/types.h"
#include "util/unique_ptr.h"

#if defined(CYCLESRENDERER_DLSS_EXPERIMENTAL)
namespace ccl {
void request_dlss_history_reset();
}
#endif

namespace cyclesrenderer::scene_builder {

using SceneResourcesData = scene::ResourcesData;
using SceneRequest = scene::SceneSnapshot;
using SceneUpdate = scene::SceneUpdate;

constexpr float kDegreesToRadians = 0.01745329251994329577F;

inline ccl::Transform rec709_to_working_space(std::uint32_t working_space) {
    const ccl::Transform xyz_to_target = working_space
            == CYCLES_BRIDGE_WORKING_SPACE_LINEAR_REC2020
        ? ccl::ColorSpaceManager::get_xyz_to_rec2020()
        : working_space == CYCLES_BRIDGE_WORKING_SPACE_ACESCG
            ? ccl::ColorSpaceManager::get_xyz_to_acescg()
            : ccl::ColorSpaceManager::get_xyz_to_rec709();
    return xyz_to_target
        * ccl::transform_inverse(ccl::ColorSpaceManager::get_xyz_to_rec709());
}

struct SceneRuntime {
    std::shared_ptr<const SceneResourcesData> resources;
    std::vector<ccl::ImageHandle> images;
    std::vector<ccl::Shader*> shaders;
    std::unordered_map<
        std::int64_t,
        realtime::SectionMeshSlot> sections;
    std::vector<realtime::SectionMeshSlot> free_sections;
    ccl::BackgroundLight* background_light = nullptr;
    ccl::Object* background_light_object = nullptr;
    ccl::Transform rec709_to_working = ccl::transform_identity();

    void clear() {
        resources.reset();
        images.clear();
        shaders.clear();
        sections.clear();
        free_sections.clear();
        background_light = nullptr;
        background_light_object = nullptr;
        rec709_to_working = ccl::transform_identity();
    }
};

class MemoryImageLoader final : public ccl::ImageLoader {
 public:
    MemoryImageLoader(
        std::string name,
        std::uint32_t width,
        std::uint32_t height,
        std::shared_ptr<const SceneResourcesData> resources,
        std::uint32_t pixel_offset,
        std::uint32_t pixel_size,
        std::uint32_t role)
        : name_(std::move(name)),
          width_(width),
          height_(height),
          resources_(std::move(resources)),
          pixel_offset_(pixel_offset),
          pixel_size_(pixel_size),
          role_(role) {}

    bool load_metadata(
        ccl::ImageMetaData& metadata,
        const ccl::ImageLoaderParams&,
        ccl::Progress&) override {
        metadata.width = width_;
        metadata.height = height_;
        metadata.channels = 4;
        metadata.type = ccl::IMAGE_DATA_TYPE_BYTE4;
        metadata.is_compressible_as_srgb =
            role_ == CYCLES_BRIDGE_TEXTURE_COLOR_SRGB;
        return true;
    }

    bool load_pixels(const ccl::ImageMetaData& metadata, void* pixels) override {
        if (metadata.memory_size() != pixel_size_) {
            return false;
        }
        std::memcpy(
            pixels,
            resources_->texture_pixels.data() + pixel_offset_,
            pixel_size_);
        metadata.conform_pixels(pixels);
        return true;
    }

    ccl::string name() const override {
        return name_;
    }

    bool equals(const ccl::ImageLoader& other) const override {
        const auto* image = dynamic_cast<const MemoryImageLoader*>(&other);
        return image != nullptr && image->name_ == name_;
    }

 private:
    std::string name_;
    std::uint32_t width_;
    std::uint32_t height_;
    std::shared_ptr<const SceneResourcesData> resources_;
    std::uint32_t pixel_offset_;
    std::uint32_t pixel_size_;
    std::uint32_t role_;
};

inline std::vector<ccl::ImageHandle> create_images(
    ccl::Scene* scene,
    const SceneRequest& request) {
    const SceneResourcesData& resources = *request.resources;
    std::vector<ccl::ImageHandle> images;
    images.reserve(resources.textures.size());
    for (std::size_t index = 0; index < resources.textures.size(); ++index) {
        const CyclesBridgeTexture& texture = resources.textures[index];
        auto loader = ccl::make_unique<MemoryImageLoader>(
            "minecraft_texture_" + std::to_string(index),
            texture.width,
            texture.height,
            request.resources,
            texture.pixel_offset,
            texture.pixel_size,
            texture.role);
        ccl::ImageParams params;
        params.colorspace = texture.role == CYCLES_BRIDGE_TEXTURE_DATA_LINEAR
            ? ccl::u_colorspace_data
            : ccl::u_colorspace_scene_linear_srgb;
        params.alpha_type = ccl::IMAGE_ALPHA_UNASSOCIATED;
        params.interpolation = ccl::INTERPOLATION_CLOSEST;
        params.extension = ccl::EXTENSION_REPEAT;
        images.push_back(scene->image_manager->add_image(std::move(loader), params));
    }
    return images;
}

inline ccl::Shader* create_material_shader(
    ccl::Scene* scene,
    const CyclesBridgeMaterial& material,
    const std::vector<ccl::ImageHandle>& images,
    const CyclesBridgeRenderSettings& settings,
    std::size_t index) {
    auto graph = cyclesrenderer::labpbr::build_material_graph(material, images, settings);

    ccl::Shader* shader = scene->create_node<ccl::Shader>();
    shader->name = "minecraft_material_" + std::to_string(index);
    if (material.pbr_format == CYCLES_BRIDGE_PBR_LAB_1_3) {
        // Emission is encoded per atlas texel. Material-level MIS would otherwise
        // register every Minecraft triangle as a light when any texel emits.
        shader->set_emission_sampling_method(ccl::EMISSION_SAMPLING_NONE);
    }
    shader->set_graph(std::move(graph));
    shader->tag_update(scene);
    return shader;
}

inline void apply_atmosphere_settings(
    ccl::SkyTextureNode* sky,
    const CyclesBridgeRenderSettings& settings) {
    sky->set_sky_type(ccl::NODE_SKY_MULTIPLE_SCATTERING);
    sky->set_sun_disc(settings.atmosphere_sun_disc != 0U);
    sky->set_sun_size(settings.atmosphere_sun_size_degrees * kDegreesToRadians);
    sky->set_sun_intensity(settings.atmosphere_sun_intensity);
    sky->set_sun_elevation(
        settings.atmosphere_sun_elevation_degrees * kDegreesToRadians);
    sky->set_sun_rotation(
        settings.atmosphere_sun_rotation_degrees * kDegreesToRadians);
    sky->set_altitude(settings.atmosphere_altitude_meters);
    sky->set_air_density(settings.atmosphere_air_density);
    sky->set_aerosol_density(settings.atmosphere_aerosol_density);
    sky->set_ozone_density(settings.atmosphere_ozone_density);
}

inline void configure_background(
    ccl::Scene* scene,
    const CyclesBridgeRenderSettings& settings,
    SceneRuntime& runtime) {
    auto graph = ccl::make_unique<ccl::ShaderGraph>();
    ccl::TextureCoordinateNode* coordinates =
        graph->create_node<ccl::TextureCoordinateNode>();
    ccl::SkyTextureNode* sky = graph->create_node<ccl::SkyTextureNode>();
    sky->set_tex_mapping_type(ccl::TextureMapping::VECTOR);
    // Minecraft is Y-up while the Cycles sky model is Z-up. Keep this on
    // SkyTextureNode's own mapping so Cycles can transform the analytic sun
    // direction and retain its dedicated sun-guiding path.
    sky->set_tex_mapping_rotation(ccl::make_float3(
        90.0F * kDegreesToRadians, 0.0F, 0.0F));
    apply_atmosphere_settings(sky, settings);

    ccl::BackgroundNode* background = graph->create_node<ccl::BackgroundNode>();
    background->set_strength(1.0F);
    graph->connect(coordinates->output("Generated"), sky->input("Vector"));
    graph->connect(sky->output("Color"), background->input("Color"));
    graph->connect(background->output("Background"), graph->output()->input("Surface"));
    scene->default_background->set_graph(std::move(graph));
    scene->default_background->tag_update(scene);
    scene->background->set_shader(scene->default_background);
    scene->background->set_transparent(false);
    scene->background->tag_update(scene);

    // A world shader alone is visible to camera/miss rays, but it is not
    // sampled as an emitter. Cycles requires a BackgroundLight for direct
    // environment lighting, MIS and analytic Nishita sun sampling.
    runtime.background_light = scene->create_node<ccl::BackgroundLight>();
    runtime.background_light->name = "minecraft_world";
    runtime.background_light->set_use_mis(true);
    ccl::array<ccl::Node*> used_shaders;
    used_shaders.push_back_slow(scene->default_background);
    runtime.background_light->set_used_shaders(used_shaders);

    runtime.background_light_object = scene->create_node<ccl::Object>();
    runtime.background_light_object->name = "minecraft_world";
    runtime.background_light_object->set_geometry(runtime.background_light);
    runtime.background_light_object->set_visibility(
        ccl::PATH_RAY_VISIBILITY_ALL & ~ccl::PATH_RAY_VISIBILITY_CAMERA);
    runtime.background_light->tag_update(scene);
    runtime.background_light_object->tag_update(scene);
}

inline void build_scene(
    ccl::Scene* scene,
    const SceneRequest& request,
    const CyclesBridgeRenderSettings& settings,
    SceneRuntime& runtime) {
    runtime.clear();
    runtime.resources = request.resources;
    runtime.rec709_to_working = rec709_to_working_space(settings.working_space);
    configure_background(scene, settings, runtime);
    scene->integrator->set_max_bounce(3);
    scene->integrator->set_max_diffuse_bounce(2);
    scene->integrator->set_max_glossy_bounce(1);
    scene->integrator->set_max_transmission_bounce(0);
    scene->integrator->set_max_volume_bounce(0);
    scene->integrator->set_use_adaptive_sampling(false);

    runtime.images = create_images(scene, request);
    const SceneResourcesData& resources = *request.resources;
    runtime.shaders.assign(resources.materials.size(), nullptr);
    for (std::size_t index = 0; index < resources.materials.size(); ++index) {
        const CyclesBridgeMaterial& material = resources.materials[index];
        runtime.shaders[index] = create_material_shader(
            scene, material, runtime.images, settings, index);
    }

    for (const auto& entry : request.sections) {
        runtime.sections.emplace(
            entry.first,
            cyclesrenderer::realtime::create_section_mesh_slot(
                scene,
                resources,
                entry.second,
                runtime.shaders,
                runtime.rec709_to_working));
    }
}

inline std::vector<texture_update::TextureLayout> texture_layouts(
    const SceneRuntime& runtime) {
    std::vector<texture_update::TextureLayout> layouts;
    if (!runtime.resources) {
        return layouts;
    }
    layouts.reserve(runtime.resources->textures.size());
    for (const CyclesBridgeTexture& texture : runtime.resources->textures) {
        layouts.push_back({texture.width, texture.height});
    }
    return layouts;
}

inline void apply_texture_region_batch(
    ccl::Scene* scene,
    const texture_update::TextureRegionBatch& batch,
    SceneRuntime& runtime) {
    if (scene == nullptr || !runtime.resources
        || runtime.images.size() != runtime.resources->textures.size()) {
        throw std::logic_error("texture region update requires active scene resources");
    }
    const std::vector<texture_update::TextureLayout> layouts =
        texture_layouts(runtime);
    std::string error;
    if (!texture_update::validate_texture_region_batch(batch, layouts, error)) {
        throw std::invalid_argument(error);
    }

    std::vector<ccl::device_image*> identities;
    identities.reserve(batch.regions.size());
    for (const texture_update::TextureRegion& region : batch.regions) {
        ccl::ImageHandle& image = runtime.images[region.texture_index];
        ccl::device_image* identity = image.vdb_image_memory();
        if (image.empty() || image.get_manager() != scene->image_manager.get()
            || identity == nullptr) {
            throw std::logic_error("texture region image is not resident");
        }
        identities.push_back(identity);
    }

    for (std::size_t index = 0; index < batch.regions.size(); ++index) {
        const texture_update::TextureRegion& region = batch.regions[index];
        ccl::ImageHandle& image = runtime.images[region.texture_index];
        if (!scene->image_manager->update_image_region(
                image,
                static_cast<int>(region.x),
                static_cast<int>(region.y),
                static_cast<int>(region.width),
                static_cast<int>(region.height),
                region.pixels.data(),
                region.row_stride)) {
            throw std::runtime_error("Cycles rejected a validated texture region");
        }
        if (image.vdb_image_memory() != identities[index]) {
            throw std::runtime_error("Cycles texture region changed image identity");
        }
    }
}

inline void apply_scene_delta(
    ccl::Scene* scene,
    const SceneRequest& request,
    const SceneUpdate& update,
    SceneRuntime& runtime) {
    if (runtime.resources != request.resources) {
        throw std::logic_error("incremental scene update changed shared resources");
    }
    const auto deactivate = [&](std::int64_t section_id) {
        const auto current = runtime.sections.find(section_id);
        if (current == runtime.sections.end()) {
            return;
        }
        cyclesrenderer::realtime::deactivate_section_mesh_slot(
            scene, current->second);
        runtime.free_sections.push_back(std::move(current->second));
        runtime.sections.erase(current);
    };

    if (update.replace_all) {
        std::vector<std::int64_t> removed;
        removed.reserve(runtime.sections.size());
        for (const auto& current : runtime.sections) {
            if (!request.sections.contains(current.first)) {
                removed.push_back(current.first);
            }
        }
        for (std::int64_t section_id : removed) {
            deactivate(section_id);
        }
    }

    // Retire old sections before additions so walking can reuse the slots
    // unloaded by the same coalesced scene update.
    for (const auto& entry : update.mutations) {
        if (!entry.second.section) {
            deactivate(entry.first);
        }
    }

    for (const auto& entry : update.mutations) {
        if (!entry.second.section) {
            continue;
        }
        auto current = runtime.sections.find(entry.first);
        if (current != runtime.sections.end()
            && current->second.source == entry.second.section) {
            continue;
        }
        if (current != runtime.sections.end()) {
            static_cast<void>(cyclesrenderer::realtime::update_section_mesh_slot(
                scene,
                current->second,
                *runtime.resources,
                entry.second.section,
                runtime.rec709_to_working));
            continue;
        }

        const std::size_t required = entry.second.section->triangles.size();
        auto reusable = runtime.free_sections.end();
        for (auto candidate = runtime.free_sections.begin();
             candidate != runtime.free_sections.end(); ++candidate) {
            if (candidate->triangle_capacity >= required
                && (reusable == runtime.free_sections.end()
                    || candidate->triangle_capacity < reusable->triangle_capacity)) {
                reusable = candidate;
            }
        }
        if (reusable == runtime.free_sections.end() && !runtime.free_sections.empty()) {
            reusable = std::max_element(
                runtime.free_sections.begin(),
                runtime.free_sections.end(),
                [](const auto& first, const auto& second) {
                    return first.triangle_capacity < second.triangle_capacity;
                });
        }
        if (reusable != runtime.free_sections.end()) {
            auto slot = std::move(*reusable);
            runtime.free_sections.erase(reusable);
            static_cast<void>(cyclesrenderer::realtime::update_section_mesh_slot(
                scene,
                slot,
                *runtime.resources,
                entry.second.section,
                runtime.rec709_to_working));
            runtime.sections.emplace(entry.first, std::move(slot));
        } else {
            runtime.sections.emplace(
                entry.first,
                cyclesrenderer::realtime::create_section_mesh_slot(
                    scene,
                    *runtime.resources,
                    entry.second.section,
                    runtime.shaders,
                    runtime.rec709_to_working));
        }
    }
#if defined(CYCLESRENDERER_DLSS_EXPERIMENTAL)
    // Scene topology has no usable per-pixel velocity. Reject the previous
    // temporal image once so DLSS does not reproject removed or replaced voxels.
    ccl::request_dlss_history_reset();
#endif
}

}  // namespace cyclesrenderer::scene_builder

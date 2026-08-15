#include "labpbr_material.h"
#include "labpbr_height.h"
#include "labpbr_metals.h"
#include "labpbr_parallax.h"
#include "labpbr_surface.h"

#include "scene/shader_graph.h"
#include "scene/shader_nodes.h"

namespace cyclesrenderer::labpbr {
namespace {

constexpr float kFoliageAlbedoGain = 1.5F;

ccl::ImageTextureNode* create_texture_node(
    ccl::ShaderGraph* graph,
    const ccl::ImageHandle& image,
    ccl::ustring colorspace) {
    ccl::ImageTextureNode* texture = graph->create_node<ccl::ImageTextureNode>();
    texture->handle = image;
    texture->set_colorspace(colorspace);
    texture->set_alpha_type(ccl::IMAGE_ALPHA_UNASSOCIATED);
    texture->set_interpolation(ccl::INTERPOLATION_CLOSEST);
    texture->set_extension(ccl::EXTENSION_REPEAT);
    return texture;
}

struct GlassClosures {
    ccl::GlassBsdfNode* visible = nullptr;
    ccl::FresnelNode* fresnel = nullptr;
    ccl::ShaderOutput* surface = nullptr;
};

GlassClosures create_glass_closures(ccl::ShaderGraph* graph) {
    constexpr float kGlassIor = 1.5F;

    ccl::GlassBsdfNode* glass = graph->create_node<ccl::GlassBsdfNode>();
    glass->set_color(ccl::make_float3(1.0F));
    glass->set_roughness(0.01F);
    glass->set_IOR(kGlassIor);
    glass->set_distribution(ccl::CLOSURE_BSDF_MICROFACET_MULTI_GGX_GLASS_ID);

    ccl::FresnelNode* fresnel = graph->create_node<ccl::FresnelNode>();
    fresnel->set_IOR(kGlassIor);
    ccl::MathNode* transmitted = graph->create_node<ccl::MathNode>();
    transmitted->set_math_type(ccl::NODE_MATH_SUBTRACT);
    transmitted->set_value1(1.0F);
    graph->connect(fresnel->output("Fac"), transmitted->input("Value2"));

    ccl::CombineColorNode* transmitted_rgb = graph->create_node<ccl::CombineColorNode>();
    transmitted_rgb->set_color_type(ccl::NODE_COMBSEP_COLOR_RGB);
    graph->connect(transmitted->output("Value"), transmitted_rgb->input("Red"));
    graph->connect(transmitted->output("Value"), transmitted_rgb->input("Green"));
    graph->connect(transmitted->output("Value"), transmitted_rgb->input("Blue"));

    ccl::TransparentBsdfNode* shadow_transmission =
        graph->create_node<ccl::TransparentBsdfNode>();
    graph->connect(transmitted_rgb->output("Color"), shadow_transmission->input("Color"));

    ccl::LightPathNode* light_path = graph->create_node<ccl::LightPathNode>();
    ccl::MixClosureNode* ray_visibility = graph->create_node<ccl::MixClosureNode>();
    graph->connect(light_path->output("Is Shadow Ray"), ray_visibility->input("Fac"));
    graph->connect(glass->output("BSDF"), ray_visibility->input("Closure1"));
    graph->connect(
        shadow_transmission->output("BSDF"), ray_visibility->input("Closure2"));
    return {glass, fresnel, ray_visibility->output("Closure")};
}

}  // namespace

ccl::unique_ptr<ccl::ShaderGraph> build_material_graph(
    const CyclesBridgeMaterial& material,
    const std::vector<ccl::ImageHandle>& images,
    const CyclesBridgeRenderSettings& settings) {
    auto graph = ccl::make_unique<ccl::ShaderGraph>();
    ccl::TextureCoordinateNode* coordinates =
        graph->create_node<ccl::TextureCoordinateNode>();
    ccl::ShaderOutput* texture_vector = coordinates->output("UV");
    if (material.pbr_format == CYCLES_BRIDGE_PBR_LAB_1_3
        && settings.pbr_height_mapping_mode
            == CYCLES_BRIDGE_HEIGHT_MAPPING_PARALLAX_OCCLUSION) {
        texture_vector = connect_parallax_uv(
            graph.get(),
            texture_vector,
            images[material.auxiliary_texture_index],
            settings.pbr_height_strength,
            settings.pbr_height_distance,
            settings.pbr_parallax_steps);
    }
    ccl::ImageTextureNode* texture = create_texture_node(
        graph.get(), images[material.texture_index], ccl::u_colorspace_scene_linear_srgb);
    graph->connect(texture_vector, texture->input("Vector"));

    ccl::VertexColorNode* vertex_color = graph->create_node<ccl::VertexColorNode>();
    ccl::VectorMathNode* albedo = graph->create_node<ccl::VectorMathNode>();
    albedo->set_math_type(ccl::NODE_VECTOR_MATH_MULTIPLY);
    graph->connect(texture->output("Color"), albedo->input("Vector1"));
    graph->connect(vertex_color->output("Color"), albedo->input("Vector2"));

    const bool transmissive =
        (material.flags & CYCLES_BRIDGE_MATERIAL_TRANSMISSION) != 0U;
    const bool water = (material.flags & CYCLES_BRIDGE_MATERIAL_WATER) != 0U;
    const bool foliage = (material.flags & CYCLES_BRIDGE_MATERIAL_FOLIAGE) != 0U;
    const bool glass = transmissive && !water;
    ccl::ShaderOutput* surface_albedo = albedo->output("Vector");
    if (foliage) {
        ccl::VectorMathNode* foliage_gain = graph->create_node<ccl::VectorMathNode>();
        foliage_gain->set_math_type(ccl::NODE_VECTOR_MATH_SCALE);
        foliage_gain->set_scale(kFoliageAlbedoGain);
        graph->connect(surface_albedo, foliage_gain->input("Vector1"));
        surface_albedo = foliage_gain->output("Vector");
    }
    ccl::PrincipledBsdfNode* principled = graph->create_node<ccl::PrincipledBsdfNode>();
    // LabPBR encodes subsurface weight but has no independent scattering-color map.
    // Keep the per-channel mean free path neutral so the textured Base Color drives
    // the scattering hue instead of Cycles' skin-oriented (1.0, 0.2, 0.1) default.
    principled->set_subsurface_radius(ccl::make_float3(1.0F));
    principled->set_roughness(
        glass ? 0.01F : (water ? 0.05F : (foliage ? 0.501F : 0.8F)));
    if (foliage) {
        principled->set_ior(1.45F);
        principled->set_thin_wall(true);
        principled->set_diffuse_roughness(0.247F);
        principled->set_subsurface_weight(1.0F);
        principled->set_subsurface_scale(0.2F);
        principled->set_subsurface_anisotropy(0.357F);
        principled->set_specular_ior_level(0.5F);
        principled->set_transmission_weight(0.229F);
        principled->set_sheen_weight(1.0F);
        principled->set_sheen_roughness(0.5F);
        principled->set_coat_weight(0.075F);
        principled->set_coat_roughness(0.03F);
        principled->set_coat_ior(1.5F);
        graph->connect(surface_albedo, principled->input("Specular Tint"));
        graph->connect(surface_albedo, principled->input("Sheen Tint"));
        graph->connect(surface_albedo, principled->input("Coat Tint"));
    } else if (water) {
        principled->set_transmission_weight(1.0F);
        principled->set_ior(1.333F);
        principled->set_thin_wall(true);
    } else if (glass) {
        principled->set_ior(1.45F);
    }
    ccl::ShaderOutput* surface = principled->output("BSDF");
    const GlassClosures glass_closures = glass
        ? create_glass_closures(graph.get())
        : GlassClosures{};

    if (material.pbr_format == CYCLES_BRIDGE_PBR_LAB_1_3) {
        ccl::ImageTextureNode* normal_texture = create_texture_node(
            graph.get(), images[material.normal_texture_index], ccl::u_colorspace_data);
        graph->connect(texture_vector, normal_texture->input("Vector"));

        ccl::NormalMapNode* normal_map = graph->create_node<ccl::NormalMapNode>();
        normal_map->set_space(ccl::NODE_NORMAL_MAP_TANGENT);
        normal_map->set_convention(ccl::NODE_NORMAL_MAP_CONVENTION_DIRECTX);
        normal_map->set_strength(settings.pbr_normal_strength);
        graph->connect(normal_texture->output("Color"), normal_map->input("Color"));

        ccl::ImageTextureNode* material_texture = create_texture_node(
            graph.get(), images[material.material_texture_index], ccl::u_colorspace_data);
        graph->connect(texture_vector, material_texture->input("Vector"));

        ccl::SeparateColorNode* material_channels =
            graph->create_node<ccl::SeparateColorNode>();
        material_channels->set_color_type(ccl::NODE_COMBSEP_COLOR_RGB);
        graph->connect(material_texture->output("Color"), material_channels->input("Color"));
        graph->connect(material_channels->output("Red"), principled->input("Roughness"));
        if (glass) {
            graph->connect(
                material_channels->output("Red"), glass_closures.visible->input("Roughness"));
        }
        if (!transmissive) {
            graph->connect(material_channels->output("Green"), principled->input("Metallic"));
        }

        ccl::ImageTextureNode* auxiliary_texture = create_texture_node(
            graph.get(), images[material.auxiliary_texture_index], ccl::u_colorspace_data);
        graph->connect(texture_vector, auxiliary_texture->input("Vector"));

        ccl::SeparateColorNode* auxiliary_channels =
            graph->create_node<ccl::SeparateColorNode>();
        auxiliary_channels->set_color_type(ccl::NODE_COMBSEP_COLOR_RGB);
        graph->connect(auxiliary_texture->output("Color"), auxiliary_channels->input("Color"));

        ccl::ShaderOutput* surface_normal = connect_height_bump(
            graph.get(),
            auxiliary_channels->output("Green"),
            normal_map->output("Normal"),
            settings.pbr_height_strength,
            settings.pbr_height_distance);
        graph->connect(surface_normal, principled->input("Normal"));
        if (glass) {
            graph->connect(surface_normal, glass_closures.visible->input("Normal"));
            graph->connect(surface_normal, glass_closures.fresnel->input("Normal"));
        }

        if (transmissive || foliage) {
            graph->connect(surface_albedo, principled->input("Base Color"));
        } else {
            ccl::MathNode* f0_to_specular = graph->create_node<ccl::MathNode>();
            f0_to_specular->set_math_type(ccl::NODE_MATH_MULTIPLY);
            f0_to_specular->set_value2(12.5F);
            graph->connect(material_channels->output("Blue"), f0_to_specular->input("Value1"));

            ccl::VectorMathNode* occluded_albedo = graph->create_node<ccl::VectorMathNode>();
            occluded_albedo->set_math_type(ccl::NODE_VECTOR_MATH_SCALE);
            graph->connect(albedo->output("Vector"), occluded_albedo->input("Vector1"));
            graph->connect(auxiliary_channels->output("Red"), occluded_albedo->input("Scale"));
            const SurfaceInputs surface_inputs = apply_porosity_wetness(
                graph.get(),
                auxiliary_texture->output("Alpha"),
                occluded_albedo->output("Vector"),
                f0_to_specular->output("Value"),
                settings.pbr_wetness);
            graph->connect(surface_inputs.albedo, principled->input("Base Color"));
            graph->connect(surface_inputs.specular, principled->input("Specular IOR Level"));
            connect_subsurface(
                graph.get(), auxiliary_texture->output("Alpha"), principled,
                settings.pbr_subsurface_scale);

            surface = apply_exact_metal_closures(
                graph.get(),
                auxiliary_channels->output("Blue"),
                material_channels->output("Red"),
                surface_normal,
                surface);
        }

        ccl::EmissionNode* emission = graph->create_node<ccl::EmissionNode>();
        graph->connect(albedo->output("Vector"), emission->input("Color"));
        ccl::MathNode* emission_scale = graph->create_node<ccl::MathNode>();
        emission_scale->set_math_type(ccl::NODE_MATH_MULTIPLY);
        emission_scale->set_value2(settings.pbr_emission_scale);
        graph->connect(material_texture->output("Alpha"), emission_scale->input("Value1"));
        graph->connect(emission_scale->output("Value"), emission->input("Strength"));

        ccl::AddClosureNode* add_emission = graph->create_node<ccl::AddClosureNode>();
        graph->connect(surface, add_emission->input("Closure1"));
        graph->connect(emission->output("Emission"), add_emission->input("Closure2"));
        surface = add_emission->output("Closure");
    } else if (material.emission_strength > 0.0F) {
        principled->set_emission_strength(material.emission_strength);
        graph->connect(surface_albedo, principled->input("Base Color"));
        graph->connect(albedo->output("Vector"), principled->input("Emission Color"));
    } else {
        graph->connect(surface_albedo, principled->input("Base Color"));
    }

    if (glass) {
        ccl::MixClosureNode* textured_glass = graph->create_node<ccl::MixClosureNode>();
        graph->connect(texture->output("Alpha"), textured_glass->input("Fac"));
        graph->connect(glass_closures.surface, textured_glass->input("Closure1"));
        graph->connect(surface, textured_glass->input("Closure2"));
        surface = textured_glass->output("Closure");
    } else if ((material.flags & CYCLES_BRIDGE_MATERIAL_BLEND) != 0U) {
        ccl::TransparentBsdfNode* transparent = graph->create_node<ccl::TransparentBsdfNode>();
        ccl::MixClosureNode* blend = graph->create_node<ccl::MixClosureNode>();
        graph->connect(texture->output("Alpha"), blend->input("Fac"));
        graph->connect(transparent->output("BSDF"), blend->input("Closure1"));
        graph->connect(surface, blend->input("Closure2"));
        surface = blend->output("Closure");
    } else if ((material.flags & CYCLES_BRIDGE_MATERIAL_CUTOUT) != 0U) {
        ccl::MathNode* threshold = graph->create_node<ccl::MathNode>();
        threshold->set_math_type(ccl::NODE_MATH_GREATER_THAN);
        threshold->set_value2(material.alpha_cutoff);
        graph->connect(texture->output("Alpha"), threshold->input("Value1"));

        ccl::TransparentBsdfNode* transparent = graph->create_node<ccl::TransparentBsdfNode>();
        ccl::MixClosureNode* cutout = graph->create_node<ccl::MixClosureNode>();
        graph->connect(threshold->output("Value"), cutout->input("Fac"));
        graph->connect(transparent->output("BSDF"), cutout->input("Closure1"));
        graph->connect(surface, cutout->input("Closure2"));
        surface = cutout->output("Closure");
    }
    graph->connect(surface, graph->output()->input("Surface"));
    return graph;
}

}  // namespace cyclesrenderer::labpbr

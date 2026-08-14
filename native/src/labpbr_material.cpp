#include "labpbr_material.h"

#include "scene/shader_graph.h"
#include "scene/shader_nodes.h"

namespace cyclesrenderer::labpbr {
namespace {

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

}  // namespace

ccl::unique_ptr<ccl::ShaderGraph> build_material_graph(
    const CyclesBridgeMaterial& material,
    const std::vector<ccl::ImageHandle>& images,
    const CyclesBridgeRenderSettings& settings) {
    auto graph = ccl::make_unique<ccl::ShaderGraph>();
    ccl::TextureCoordinateNode* coordinates =
        graph->create_node<ccl::TextureCoordinateNode>();
    ccl::ImageTextureNode* texture = create_texture_node(
        graph.get(), images[material.texture_index], ccl::u_colorspace_scene_linear_srgb);
    graph->connect(coordinates->output("UV"), texture->input("Vector"));

    ccl::VertexColorNode* vertex_color = graph->create_node<ccl::VertexColorNode>();
    ccl::VectorMathNode* multiply = graph->create_node<ccl::VectorMathNode>();
    multiply->set_math_type(ccl::NODE_VECTOR_MATH_MULTIPLY);
    graph->connect(texture->output("Color"), multiply->input("Vector1"));
    graph->connect(vertex_color->output("Color"), multiply->input("Vector2"));

    ccl::PrincipledBsdfNode* principled = graph->create_node<ccl::PrincipledBsdfNode>();
    principled->set_roughness(0.8F);
    graph->connect(multiply->output("Vector"), principled->input("Base Color"));

    if (material.pbr_format == CYCLES_BRIDGE_PBR_LAB_1_3) {
        ccl::ImageTextureNode* normal_texture = create_texture_node(
            graph.get(), images[material.normal_texture_index], ccl::u_colorspace_data);
        graph->connect(coordinates->output("UV"), normal_texture->input("Vector"));

        ccl::NormalMapNode* normal_map = graph->create_node<ccl::NormalMapNode>();
        normal_map->set_space(ccl::NODE_NORMAL_MAP_TANGENT);
        normal_map->set_convention(ccl::NODE_NORMAL_MAP_CONVENTION_DIRECTX);
        normal_map->set_strength(settings.pbr_normal_strength);
        graph->connect(normal_texture->output("Color"), normal_map->input("Color"));
        graph->connect(normal_map->output("Normal"), principled->input("Normal"));

        ccl::ImageTextureNode* material_texture = create_texture_node(
            graph.get(), images[material.material_texture_index], ccl::u_colorspace_data);
        graph->connect(coordinates->output("UV"), material_texture->input("Vector"));

        ccl::SeparateColorNode* material_channels =
            graph->create_node<ccl::SeparateColorNode>();
        material_channels->set_color_type(ccl::NODE_COMBSEP_COLOR_RGB);
        graph->connect(material_texture->output("Color"), material_channels->input("Color"));
        graph->connect(material_channels->output("Red"), principled->input("Roughness"));
        graph->connect(material_channels->output("Green"), principled->input("Metallic"));

        ccl::MathNode* f0_to_specular = graph->create_node<ccl::MathNode>();
        f0_to_specular->set_math_type(ccl::NODE_MATH_MULTIPLY);
        f0_to_specular->set_value2(12.5F);
        graph->connect(material_channels->output("Blue"), f0_to_specular->input("Value1"));
        graph->connect(f0_to_specular->output("Value"), principled->input("Specular IOR Level"));

        graph->connect(multiply->output("Vector"), principled->input("Emission Color"));
        ccl::MathNode* emission_scale = graph->create_node<ccl::MathNode>();
        emission_scale->set_math_type(ccl::NODE_MATH_MULTIPLY);
        emission_scale->set_value2(settings.pbr_emission_scale);
        graph->connect(material_texture->output("Alpha"), emission_scale->input("Value1"));
        graph->connect(emission_scale->output("Value"), principled->input("Emission Strength"));
    } else if (material.emission_strength > 0.0F) {
        principled->set_emission_strength(material.emission_strength);
        graph->connect(multiply->output("Vector"), principled->input("Emission Color"));
    }

    ccl::ShaderOutput* surface = principled->output("BSDF");
    if ((material.flags & CYCLES_BRIDGE_MATERIAL_BLEND) != 0U) {
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

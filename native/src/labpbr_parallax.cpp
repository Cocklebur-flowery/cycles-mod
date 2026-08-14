#include "labpbr_parallax.h"

#include "labpbr_attributes.h"

#include "scene/shader_graph.h"
#include "scene/shader_nodes.h"

#include <algorithm>

namespace cyclesrenderer::labpbr {
namespace {

ccl::VectorMathNode* vector_math(ccl::ShaderGraph* graph, ccl::NodeVectorMathType type) {
    ccl::VectorMathNode* node = graph->create_node<ccl::VectorMathNode>();
    node->set_math_type(type);
    return node;
}

ccl::MathNode* scalar_math(ccl::ShaderGraph* graph, ccl::NodeMathType type) {
    ccl::MathNode* node = graph->create_node<ccl::MathNode>();
    node->set_math_type(type);
    return node;
}

ccl::ShaderOutput* clamp_to_sprite(
    ccl::ShaderGraph* graph,
    ccl::ShaderOutput* uv,
    ccl::ShaderOutput* minimum,
    ccl::ShaderOutput* maximum) {
    ccl::VectorMathNode* lower = vector_math(graph, ccl::NODE_VECTOR_MATH_MAXIMUM);
    graph->connect(uv, lower->input("Vector1"));
    graph->connect(minimum, lower->input("Vector2"));

    ccl::VectorMathNode* upper = vector_math(graph, ccl::NODE_VECTOR_MATH_MINIMUM);
    graph->connect(lower->output("Vector"), upper->input("Vector1"));
    graph->connect(maximum, upper->input("Vector2"));
    return upper->output("Vector");
}

ccl::ImageTextureNode* height_texture(
    ccl::ShaderGraph* graph,
    const ccl::ImageHandle& image,
    ccl::ShaderOutput* uv) {
    ccl::ImageTextureNode* texture = graph->create_node<ccl::ImageTextureNode>();
    texture->handle = image;
    texture->set_colorspace(ccl::u_colorspace_data);
    texture->set_alpha_type(ccl::IMAGE_ALPHA_UNASSOCIATED);
    texture->set_interpolation(ccl::INTERPOLATION_CLOSEST);
    texture->set_extension(ccl::EXTENSION_REPEAT);
    graph->connect(uv, texture->input("Vector"));
    return texture;
}

}  // namespace

ccl::ShaderOutput* connect_parallax_uv(
    ccl::ShaderGraph* graph,
    ccl::ShaderOutput* base_uv,
    const ccl::ImageHandle& height_image,
    float strength,
    float distance,
    std::uint32_t requested_steps) {
    if (strength <= 0.0F || distance <= 0.0F) {
        return base_uv;
    }
    const std::uint32_t steps = std::clamp(requested_steps, 4U, 64U);

    ccl::AttributeNode* bounds = graph->create_node<ccl::AttributeNode>();
    bounds->set_attribute(ccl::ustring(kUvBoundsAttribute));
    ccl::SeparateXYZNode* bounds_color = graph->create_node<ccl::SeparateXYZNode>();
    graph->connect(bounds->output("Color"), bounds_color->input("Vector"));

    ccl::CombineXYZNode* minimum = graph->create_node<ccl::CombineXYZNode>();
    graph->connect(bounds_color->output("X"), minimum->input("X"));
    graph->connect(bounds_color->output("Y"), minimum->input("Y"));
    ccl::CombineXYZNode* maximum = graph->create_node<ccl::CombineXYZNode>();
    graph->connect(bounds_color->output("Z"), maximum->input("X"));
    graph->connect(bounds->output("Alpha"), maximum->input("Y"));

    ccl::VectorMathNode* sprite_span = vector_math(graph, ccl::NODE_VECTOR_MATH_SUBTRACT);
    graph->connect(maximum->output("Vector"), sprite_span->input("Vector1"));
    graph->connect(minimum->output("Vector"), sprite_span->input("Vector2"));

    ccl::GeometryNode* geometry = graph->create_node<ccl::GeometryNode>();
    ccl::TangentNode* tangent = graph->create_node<ccl::TangentNode>();
    tangent->set_direction_type(ccl::NODE_TANGENT_UVMAP);
    ccl::VectorMathNode* bitangent = vector_math(graph, ccl::NODE_VECTOR_MATH_CROSS_PRODUCT);
    graph->connect(geometry->output("Normal"), bitangent->input("Vector1"));
    graph->connect(tangent->output("Tangent"), bitangent->input("Vector2"));

    ccl::VectorMathNode* view_x = vector_math(graph, ccl::NODE_VECTOR_MATH_DOT_PRODUCT);
    graph->connect(geometry->output("Incoming"), view_x->input("Vector1"));
    graph->connect(tangent->output("Tangent"), view_x->input("Vector2"));
    ccl::VectorMathNode* view_y = vector_math(graph, ccl::NODE_VECTOR_MATH_DOT_PRODUCT);
    graph->connect(geometry->output("Incoming"), view_y->input("Vector1"));
    graph->connect(bitangent->output("Vector"), view_y->input("Vector2"));
    ccl::VectorMathNode* view_z = vector_math(graph, ccl::NODE_VECTOR_MATH_DOT_PRODUCT);
    graph->connect(geometry->output("Incoming"), view_z->input("Vector1"));
    graph->connect(geometry->output("Normal"), view_z->input("Vector2"));

    ccl::MathNode* absolute_z = scalar_math(graph, ccl::NODE_MATH_ABSOLUTE);
    graph->connect(view_z->output("Value"), absolute_z->input("Value1"));
    ccl::MathNode* safe_z = scalar_math(graph, ccl::NODE_MATH_MAXIMUM);
    safe_z->set_value2(0.05F);
    graph->connect(absolute_z->output("Value"), safe_z->input("Value1"));
    ccl::MathNode* projected_x = scalar_math(graph, ccl::NODE_MATH_DIVIDE);
    graph->connect(view_x->output("Value"), projected_x->input("Value1"));
    graph->connect(safe_z->output("Value"), projected_x->input("Value2"));
    ccl::MathNode* projected_y = scalar_math(graph, ccl::NODE_MATH_DIVIDE);
    graph->connect(view_y->output("Value"), projected_y->input("Value1"));
    graph->connect(safe_z->output("Value"), projected_y->input("Value2"));

    ccl::CombineXYZNode* projected_view = graph->create_node<ccl::CombineXYZNode>();
    graph->connect(projected_x->output("Value"), projected_view->input("X"));
    graph->connect(projected_y->output("Value"), projected_view->input("Y"));
    ccl::VectorMathNode* sprite_direction = vector_math(graph, ccl::NODE_VECTOR_MATH_MULTIPLY);
    graph->connect(projected_view->output("Vector"), sprite_direction->input("Vector1"));
    graph->connect(sprite_span->output("Vector"), sprite_direction->input("Vector2"));
    ccl::VectorMathNode* parallax = vector_math(graph, ccl::NODE_VECTOR_MATH_SCALE);
    parallax->set_scale(strength * distance);
    graph->connect(sprite_direction->output("Vector"), parallax->input("Vector1"));

    ccl::VectorMathNode* top_offset = vector_math(graph, ccl::NODE_VECTOR_MATH_SCALE);
    top_offset->set_scale(0.5F);
    graph->connect(parallax->output("Vector"), top_offset->input("Vector1"));
    ccl::VectorMathNode* top_uv = vector_math(graph, ccl::NODE_VECTOR_MATH_ADD);
    graph->connect(base_uv, top_uv->input("Vector1"));
    graph->connect(top_offset->output("Vector"), top_uv->input("Vector2"));
    ccl::ShaderOutput* current_uv = clamp_to_sprite(
        graph, top_uv->output("Vector"), minimum->output("Vector"), maximum->output("Vector"));

    ccl::VectorMathNode* step_delta = vector_math(graph, ccl::NODE_VECTOR_MATH_SCALE);
    step_delta->set_scale(-1.0F / static_cast<float>(steps));
    graph->connect(parallax->output("Vector"), step_delta->input("Vector1"));

    ccl::ValueNode* active_value = graph->create_node<ccl::ValueNode>();
    active_value->set_value(1.0F);
    ccl::ShaderOutput* active = active_value->output("Value");

    for (std::uint32_t step = 0; step < steps; ++step) {
        ccl::ImageTextureNode* sample = height_texture(graph, height_image, current_uv);
        ccl::SeparateColorNode* channels = graph->create_node<ccl::SeparateColorNode>();
        channels->set_color_type(ccl::NODE_COMBSEP_COLOR_RGB);
        graph->connect(sample->output("Color"), channels->input("Color"));

        ccl::MathNode* hit = scalar_math(graph, ccl::NODE_MATH_GREATER_THAN);
        hit->set_value2(1.0F
            - (static_cast<float>(step) + 0.5F) / static_cast<float>(steps));
        graph->connect(channels->output("Green"), hit->input("Value1"));
        ccl::MathNode* continue_ray = scalar_math(graph, ccl::NODE_MATH_SUBTRACT);
        continue_ray->set_value1(1.0F);
        graph->connect(hit->output("Value"), continue_ray->input("Value2"));
        ccl::MathNode* next_active = scalar_math(graph, ccl::NODE_MATH_MULTIPLY);
        graph->connect(active, next_active->input("Value1"));
        graph->connect(continue_ray->output("Value"), next_active->input("Value2"));

        ccl::VectorMathNode* active_delta = vector_math(graph, ccl::NODE_VECTOR_MATH_SCALE);
        graph->connect(step_delta->output("Vector"), active_delta->input("Vector1"));
        graph->connect(next_active->output("Value"), active_delta->input("Scale"));
        ccl::VectorMathNode* advanced = vector_math(graph, ccl::NODE_VECTOR_MATH_ADD);
        graph->connect(current_uv, advanced->input("Vector1"));
        graph->connect(active_delta->output("Vector"), advanced->input("Vector2"));
        current_uv = clamp_to_sprite(
            graph, advanced->output("Vector"), minimum->output("Vector"), maximum->output("Vector"));
        active = next_active->output("Value");
    }
    return current_uv;
}

}  // namespace cyclesrenderer::labpbr

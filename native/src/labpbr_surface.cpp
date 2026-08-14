#include "labpbr_surface.h"

#include "scene/shader_graph.h"
#include "scene/shader_nodes.h"

namespace cyclesrenderer::labpbr {
namespace {

constexpr float kPorosityUpperBound = 65.0F / 255.0F;
constexpr float kPorosityDecodeScale = 255.0F / 64.0F;
constexpr float kWetAlbedoLoss = 0.35F;
constexpr float kWetSpecularLoss = 0.50F;
constexpr float kSubsurfaceLowerBound = 65.0F / 255.0F;
constexpr float kSubsurfaceDecodeScale = 255.0F / 190.0F;

ccl::ShaderOutput* scale_from_one(
    ccl::ShaderGraph* graph,
    ccl::ShaderOutput* response,
    float loss) {
    ccl::MathNode* scaled_loss = graph->create_node<ccl::MathNode>();
    scaled_loss->set_math_type(ccl::NODE_MATH_MULTIPLY);
    scaled_loss->set_value2(loss);
    graph->connect(response, scaled_loss->input("Value1"));

    ccl::MathNode* remaining = graph->create_node<ccl::MathNode>();
    remaining->set_math_type(ccl::NODE_MATH_SUBTRACT);
    remaining->set_value1(1.0F);
    remaining->set_use_clamp(true);
    graph->connect(scaled_loss->output("Value"), remaining->input("Value2"));
    return remaining->output("Value");
}

}  // namespace

SurfaceInputs apply_porosity_wetness(
    ccl::ShaderGraph* graph,
    ccl::ShaderOutput* encoded_surface,
    ccl::ShaderOutput* albedo,
    ccl::ShaderOutput* specular,
    float wetness) {
    ccl::MathNode* porosity_range = graph->create_node<ccl::MathNode>();
    porosity_range->set_math_type(ccl::NODE_MATH_LESS_THAN);
    porosity_range->set_value2(kPorosityUpperBound);
    graph->connect(encoded_surface, porosity_range->input("Value1"));

    ccl::MathNode* decoded_porosity = graph->create_node<ccl::MathNode>();
    decoded_porosity->set_math_type(ccl::NODE_MATH_MULTIPLY);
    decoded_porosity->set_value2(kPorosityDecodeScale);
    decoded_porosity->set_use_clamp(true);
    graph->connect(encoded_surface, decoded_porosity->input("Value1"));

    ccl::MathNode* porosity = graph->create_node<ccl::MathNode>();
    porosity->set_math_type(ccl::NODE_MATH_MULTIPLY);
    graph->connect(decoded_porosity->output("Value"), porosity->input("Value1"));
    graph->connect(porosity_range->output("Value"), porosity->input("Value2"));

    ccl::MathNode* wet_response = graph->create_node<ccl::MathNode>();
    wet_response->set_math_type(ccl::NODE_MATH_MULTIPLY);
    wet_response->set_value2(wetness);
    wet_response->set_use_clamp(true);
    graph->connect(porosity->output("Value"), wet_response->input("Value1"));

    ccl::VectorMathNode* wet_albedo = graph->create_node<ccl::VectorMathNode>();
    wet_albedo->set_math_type(ccl::NODE_VECTOR_MATH_SCALE);
    graph->connect(albedo, wet_albedo->input("Vector1"));
    graph->connect(
        scale_from_one(graph, wet_response->output("Value"), kWetAlbedoLoss),
        wet_albedo->input("Scale"));

    ccl::MathNode* wet_specular = graph->create_node<ccl::MathNode>();
    wet_specular->set_math_type(ccl::NODE_MATH_MULTIPLY);
    graph->connect(specular, wet_specular->input("Value1"));
    graph->connect(
        scale_from_one(graph, wet_response->output("Value"), kWetSpecularLoss),
        wet_specular->input("Value2"));

    return {wet_albedo->output("Vector"), wet_specular->output("Value")};
}

void connect_subsurface(
    ccl::ShaderGraph* graph,
    ccl::ShaderOutput* encoded_surface,
    ccl::PrincipledBsdfNode* principled,
    float scale) {
    ccl::MathNode* sss_range = graph->create_node<ccl::MathNode>();
    sss_range->set_math_type(ccl::NODE_MATH_SUBTRACT);
    sss_range->set_value2(kSubsurfaceLowerBound);
    graph->connect(encoded_surface, sss_range->input("Value1"));

    ccl::MathNode* sss_weight = graph->create_node<ccl::MathNode>();
    sss_weight->set_math_type(ccl::NODE_MATH_MULTIPLY);
    sss_weight->set_value2(kSubsurfaceDecodeScale);
    sss_weight->set_use_clamp(true);
    graph->connect(sss_range->output("Value"), sss_weight->input("Value1"));
    graph->connect(sss_weight->output("Value"), principled->input("Subsurface Weight"));
    principled->set_subsurface_scale(scale);
}

}  // namespace cyclesrenderer::labpbr

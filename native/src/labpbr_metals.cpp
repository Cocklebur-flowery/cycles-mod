#include "labpbr_metals.h"

#include "scene/shader_graph.h"
#include "scene/shader_nodes.h"
#include "util/math.h"

#include <array>

namespace cyclesrenderer::labpbr {
namespace {

constexpr std::array<MetalOptics, 8> kExactMetals{{
    {230, {2.9114F, 2.9497F, 2.5845F}, {3.0893F, 2.9318F, 2.7670F}},
    {231, {0.18299F, 0.42108F, 1.3734F}, {3.4242F, 2.3459F, 1.7704F}},
    {232, {1.3456F, 0.96521F, 0.61722F}, {7.4746F, 6.3995F, 5.3031F}},
    {233, {3.1071F, 3.1812F, 2.3230F}, {3.3314F, 3.3291F, 3.1350F}},
    {234, {0.27105F, 0.67693F, 1.3164F}, {3.6092F, 2.6248F, 2.2921F}},
    {235, {1.91F, 1.83F, 1.44F}, {3.51F, 3.40F, 3.18F}},
    {236, {2.3757F, 2.0847F, 1.8453F}, {4.2655F, 3.7153F, 3.1365F}},
    {237, {0.15943F, 0.14512F, 0.13547F}, {3.9291F, 3.1900F, 2.3808F}},
}};

constexpr float kByteScale = 1.0F / 255.0F;
constexpr float kIdTolerance = 0.5F * kByteScale;

}  // namespace

const MetalOptics* exact_metal_optics(const std::uint8_t id) noexcept {
    for (const MetalOptics& metal : kExactMetals) {
        if (metal.id == id) {
            return &metal;
        }
    }
    return nullptr;
}

ccl::ShaderOutput* apply_exact_metal_closures(
    ccl::ShaderGraph* graph,
    ccl::ShaderOutput* encoded_metal_id,
    ccl::ShaderOutput* roughness,
    ccl::ShaderOutput* normal,
    ccl::ShaderOutput* fallback_surface) {
    ccl::ShaderOutput* surface = fallback_surface;
    for (const MetalOptics& metal : kExactMetals) {
        ccl::MetallicBsdfNode* conductor = graph->create_node<ccl::MetallicBsdfNode>();
        conductor->set_fresnel_type(ccl::CLOSURE_BSDF_PHYSICAL_CONDUCTOR);
        conductor->set_ior(ccl::make_float3(metal.ior[0], metal.ior[1], metal.ior[2]));
        conductor->set_k(ccl::make_float3(
            metal.extinction[0], metal.extinction[1], metal.extinction[2]));
        graph->connect(roughness, conductor->input("Roughness"));
        graph->connect(normal, conductor->input("Normal"));

        ccl::MathNode* id_match = graph->create_node<ccl::MathNode>();
        id_match->set_math_type(ccl::NODE_MATH_COMPARE);
        id_match->set_value2(static_cast<float>(metal.id) * kByteScale);
        id_match->set_value3(kIdTolerance);
        graph->connect(encoded_metal_id, id_match->input("Value1"));

        ccl::MixClosureNode* select = graph->create_node<ccl::MixClosureNode>();
        graph->connect(id_match->output("Value"), select->input("Fac"));
        graph->connect(surface, select->input("Closure1"));
        graph->connect(conductor->output("BSDF"), select->input("Closure2"));
        surface = select->output("Closure");
    }
    return surface;
}

}  // namespace cyclesrenderer::labpbr

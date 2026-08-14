#pragma once

namespace ccl {
class PrincipledBsdfNode;
class ShaderGraph;
class ShaderOutput;
}

namespace cyclesrenderer::labpbr {

struct SurfaceInputs {
    ccl::ShaderOutput* albedo;
    ccl::ShaderOutput* specular;
};

SurfaceInputs apply_porosity_wetness(
    ccl::ShaderGraph* graph,
    ccl::ShaderOutput* encoded_surface,
    ccl::ShaderOutput* albedo,
    ccl::ShaderOutput* specular,
    float wetness);

void connect_subsurface(
    ccl::ShaderGraph* graph,
    ccl::ShaderOutput* encoded_surface,
    ccl::PrincipledBsdfNode* principled,
    float scale);

}  // namespace cyclesrenderer::labpbr

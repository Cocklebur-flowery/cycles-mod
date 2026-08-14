#pragma once

#include <cstdint>

namespace ccl {
class ShaderGraph;
class ShaderOutput;
}

namespace cyclesrenderer::labpbr {

struct MetalOptics final {
    std::uint8_t id;
    float ior[3];
    float extinction[3];
};

const MetalOptics* exact_metal_optics(std::uint8_t id) noexcept;

ccl::ShaderOutput* apply_exact_metal_closures(
    ccl::ShaderGraph* graph,
    ccl::ShaderOutput* encoded_metal_id,
    ccl::ShaderOutput* roughness,
    ccl::ShaderOutput* normal,
    ccl::ShaderOutput* fallback_surface);

}  // namespace cyclesrenderer::labpbr

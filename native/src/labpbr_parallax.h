#pragma once

#include <cstdint>

namespace ccl {
class ImageHandle;
class ShaderGraph;
class ShaderOutput;
}

namespace cyclesrenderer::labpbr {

ccl::ShaderOutput* connect_parallax_uv(
    ccl::ShaderGraph* graph,
    ccl::ShaderOutput* base_uv,
    const ccl::ImageHandle& height_image,
    float strength,
    float distance,
    std::uint32_t steps);

}  // namespace cyclesrenderer::labpbr

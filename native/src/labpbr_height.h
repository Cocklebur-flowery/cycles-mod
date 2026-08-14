#pragma once

namespace ccl {
class ShaderGraph;
class ShaderOutput;
}

namespace cyclesrenderer::labpbr {

ccl::ShaderOutput* connect_height_bump(
    ccl::ShaderGraph* graph,
    ccl::ShaderOutput* height,
    ccl::ShaderOutput* normal,
    float strength,
    float distance);

}  // namespace cyclesrenderer::labpbr

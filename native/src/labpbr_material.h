#pragma once

#include "cycles_bridge.h"

#include "scene/image.h"
#include "util/unique_ptr.h"

#include <vector>

namespace ccl {
class ShaderGraph;
}

namespace cyclesrenderer::labpbr {

ccl::unique_ptr<ccl::ShaderGraph> build_material_graph(
    const CyclesBridgeMaterial& material,
    const std::vector<ccl::ImageHandle>& images,
    const CyclesBridgeRenderSettings& settings);

}  // namespace cyclesrenderer::labpbr

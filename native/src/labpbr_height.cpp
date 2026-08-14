#include "labpbr_height.h"

#include "scene/shader_graph.h"
#include "scene/shader_nodes.h"

namespace cyclesrenderer::labpbr {

ccl::ShaderOutput* connect_height_bump(
    ccl::ShaderGraph* graph,
    ccl::ShaderOutput* height,
    ccl::ShaderOutput* normal,
    float strength,
    float distance) {
    if (strength <= 0.0F || distance <= 0.0F) {
        return normal;
    }

    ccl::BumpNode* bump = graph->create_node<ccl::BumpNode>();
    bump->set_strength(strength);
    bump->set_distance(distance);
    graph->connect(height, bump->input("Height"));
    graph->connect(normal, bump->input("Normal"));
    return bump->output("Normal");
}

}  // namespace cyclesrenderer::labpbr

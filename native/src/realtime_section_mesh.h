#pragma once

#include "scene_update.h"

#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

namespace ccl {
class Mesh;
class Object;
class Scene;
class Shader;
struct Transform;
}

namespace cyclesrenderer::realtime {

struct SectionMeshSlot {
    ccl::Mesh* mesh = nullptr;
    ccl::Object* object = nullptr;
    scene::SectionPtr source;
    std::size_t triangle_capacity = 0;
    std::size_t active_triangle_count = 0;
};

enum class SectionMeshUpdate {
    REFIT,
    REBUILD,
};

[[nodiscard]] std::size_t choose_triangle_capacity(
    std::size_t triangle_count);

[[nodiscard]] SectionMeshSlot create_section_mesh_slot(
    ccl::Scene* scene,
    const scene::ResourcesData& resources,
    const scene::SectionPtr& section,
    const std::vector<ccl::Shader*>& shaders,
    const ccl::Transform& rec709_to_working);

[[nodiscard]] SectionMeshUpdate update_section_mesh_slot(
    ccl::Scene* scene,
    SectionMeshSlot& slot,
    const scene::ResourcesData& resources,
    const scene::SectionPtr& section,
    const ccl::Transform& rec709_to_working);

void deactivate_section_mesh_slot(ccl::Scene* scene, SectionMeshSlot& slot);

}  // namespace cyclesrenderer::realtime

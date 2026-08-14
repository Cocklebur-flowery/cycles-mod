#include "realtime_section_mesh.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>
#include "scene/attribute.h"
#include "scene/geometry.h"
#include "scene/mesh.h"
#include "scene/object.h"
#include "scene/scene.h"
#include "util/transform.h"
#include "util/types.h"

namespace cyclesrenderer::realtime {
namespace {

constexpr std::size_t kMinimumTriangleCapacity = 64;
constexpr std::size_t kTriangleCapacityQuantum = 64;

float srgb_to_linear(std::uint32_t value) {
    const float channel = static_cast<float>(value) / 255.0F;
    if (channel <= 0.04045F) {
        return channel / 12.92F;
    }
    return std::pow((channel + 0.055F) / 1.055F, 2.4F);
}

std::uint8_t to_unorm(float value) {
    if (std::isnan(value)) {
        return 0U;
    }
    return static_cast<std::uint8_t>(std::lround(
        std::clamp(value, 0.0F, 1.0F) * 255.0F));
}

void initialize_fixed_topology(ccl::Mesh* mesh, std::size_t triangle_capacity) {
    if (triangle_capacity > static_cast<std::size_t>(
            std::numeric_limits<int>::max() / 3)) {
        throw std::overflow_error("section triangle capacity exceeds Cycles mesh limits");
    }
    mesh->resize_mesh(
        static_cast<int>(triangle_capacity * 3U),
        static_cast<int>(triangle_capacity));
    int* triangles = mesh->get_triangles().data();
    bool* smooth = mesh->get_smooth().data();
    for (std::size_t triangle = 0; triangle < triangle_capacity; ++triangle) {
        const int vertex = static_cast<int>(triangle * 3U);
        triangles[triangle * 3U] = vertex;
        triangles[triangle * 3U + 1U] = vertex + 1;
        triangles[triangle * 3U + 2U] = vertex + 2;
        smooth[triangle] = false;
    }
    mesh->tag_triangles_modified();
    mesh->tag_smooth_modified();
}

void write_vertex(
    ccl::packed_float3* positions,
    ccl::packed_normal* normals,
    ccl::float2* uvs,
    ccl::uchar4* colors,
    std::size_t output_index,
    const CyclesBridgeVertex& vertex,
    const ccl::float3& section_offset,
    const ccl::Transform& rec709_to_working) {
    positions[output_index] = ccl::make_float3(
        vertex.position_x + section_offset.x,
        vertex.position_y + section_offset.y,
        vertex.position_z + section_offset.z);
    ccl::float3 normal = ccl::make_float3(
        vertex.normal_x, vertex.normal_y, vertex.normal_z);
    const float length = ccl::len(normal);
    normal = length <= 1.0e-8F
        ? ccl::make_float3(0.0F, 1.0F, 0.0F)
        : normal / length;
    normals[output_index] = ccl::packed_normal(normal);
    uvs[output_index] = ccl::make_float2(vertex.texture_u, vertex.texture_v);

    const std::uint32_t rgba = vertex.packed_rgba;
    const ccl::float3 linear_rec709 = ccl::make_float3(
        srgb_to_linear(rgba & 0xFFU),
        srgb_to_linear((rgba >> 8U) & 0xFFU),
        srgb_to_linear((rgba >> 16U) & 0xFFU));
    const ccl::float3 working = ccl::transform_direction(
        &rec709_to_working, linear_rec709);
    colors[output_index] = ccl::make_uchar4(
        to_unorm(working.x),
        to_unorm(working.y),
        to_unorm(working.z),
        static_cast<std::uint8_t>((rgba >> 24U) & 0xFFU));
}

void write_degenerate_vertex(
    ccl::packed_float3* positions,
    ccl::packed_normal* normals,
    ccl::float2* uvs,
    ccl::uchar4* colors,
    std::size_t output_index) {
    positions[output_index] = ccl::make_float3(0.0F, 0.0F, 0.0F);
    normals[output_index] = ccl::packed_normal(ccl::make_float3(0.0F, 1.0F, 0.0F));
    uvs[output_index] = ccl::make_float2(0.0F, 0.0F);
    colors[output_index] = ccl::make_uchar4(0U, 0U, 0U, 0U);
}

void write_slot_contents(
    SectionMeshSlot& slot,
    const scene::ResourcesData& resources,
    const scene::SectionData* section,
    const ccl::Transform& rec709_to_working) {
    ccl::Mesh* mesh = slot.mesh;
    ccl::packed_float3* positions = mesh->get_position_for_write();
    int* triangle_shaders = mesh->get_shader().data();
    ccl::Attribute* normal_attribute = mesh->attributes.add(ccl::ATTR_STD_VERTEX_NORMAL);
    ccl::Attribute* uv_attribute = mesh->attributes.add(ccl::ATTR_STD_UV);
    ccl::Attribute* color_attribute = mesh->attributes.add(ccl::ATTR_STD_VERTEX_COLOR);
    normal_attribute->modified = true;
    uv_attribute->modified = true;
    color_attribute->modified = true;
    ccl::packed_normal* normals =
        normal_attribute->data_for_write<ccl::packed_normal>();
    ccl::float2* uvs = uv_attribute->data_for_write<ccl::float2>();
    ccl::uchar4* colors = color_attribute->data_for_write<ccl::uchar4>();

    const std::size_t active_triangles = section == nullptr
        ? 0U : section->triangles.size();
    const ccl::float3 section_offset = section == nullptr
        ? ccl::make_float3(0.0F, 0.0F, 0.0F)
        : ccl::make_float3(
            static_cast<float>(section->section.origin_x - resources.resources.origin_x),
            static_cast<float>(section->section.origin_y - resources.resources.origin_y),
            static_cast<float>(section->section.origin_z - resources.resources.origin_z));
    for (std::size_t triangle = 0; triangle < slot.triangle_capacity; ++triangle) {
        const std::size_t output_vertex = triangle * 3U;
        if (triangle < active_triangles) {
            const CyclesBridgeTriangle& source_triangle = section->triangles[triangle];
            const std::uint32_t indices[3] = {
                source_triangle.vertex_0,
                source_triangle.vertex_1,
                source_triangle.vertex_2,
            };
            for (std::size_t corner = 0; corner < 3U; ++corner) {
                write_vertex(
                    positions,
                    normals,
                    uvs,
                    colors,
                    output_vertex + corner,
                    section->vertices[indices[corner]],
                    section_offset,
                    rec709_to_working);
            }
            triangle_shaders[triangle] = static_cast<int>(
                source_triangle.material_index);
        } else {
            for (std::size_t corner = 0; corner < 3U; ++corner) {
                write_degenerate_vertex(
                    positions, normals, uvs, colors, output_vertex + corner);
            }
            triangle_shaders[triangle] = 0;
        }
    }
    mesh->tag_position_modified();
    mesh->tag_shader_modified();
    slot.active_triangle_count = active_triangles;
}

}  // namespace

std::size_t choose_triangle_capacity(std::size_t triangle_count) {
    const std::size_t headroom = std::max(kTriangleCapacityQuantum, triangle_count / 8U);
    if (triangle_count > std::numeric_limits<std::size_t>::max() - headroom) {
        throw std::overflow_error("section triangle capacity overflow");
    }
    const std::size_t requested = std::max(
        kMinimumTriangleCapacity, triangle_count + headroom);
    return (requested + kTriangleCapacityQuantum - 1U)
        / kTriangleCapacityQuantum * kTriangleCapacityQuantum;
}

SectionMeshSlot create_section_mesh_slot(
    ccl::Scene* scene,
    const scene::ResourcesData& resources,
    const scene::SectionPtr& section,
    const std::vector<ccl::Shader*>& shaders,
    const ccl::Transform& rec709_to_working) {
    SectionMeshSlot slot;
    slot.mesh = scene->create_node<ccl::Mesh>();
    slot.mesh->name = "minecraft_section_slot";
    ccl::array<ccl::Node*> used_shaders;
    for (ccl::Shader* shader : shaders) {
        used_shaders.push_back_slow(shader);
    }
    slot.mesh->set_used_shaders(used_shaders);
    slot.triangle_capacity = choose_triangle_capacity(section->triangles.size());
    initialize_fixed_topology(slot.mesh, slot.triangle_capacity);
    write_slot_contents(slot, resources, section.get(), rec709_to_working);
    slot.source = section;

    slot.object = scene->create_node<ccl::Object>();
    slot.object->name = "minecraft_section_slot";
    slot.object->set_geometry(slot.mesh);
    return slot;
}

SectionMeshUpdate update_section_mesh_slot(
    ccl::Scene* scene,
    SectionMeshSlot& slot,
    const scene::ResourcesData& resources,
    const scene::SectionPtr& section,
    const ccl::Transform& rec709_to_working) {
    const bool rebuild = section->triangles.size() > slot.triangle_capacity;
    if (rebuild) {
        slot.triangle_capacity = choose_triangle_capacity(section->triangles.size());
        initialize_fixed_topology(slot.mesh, slot.triangle_capacity);
    }
    write_slot_contents(slot, resources, section.get(), rec709_to_working);
    slot.source = section;
    if (rebuild) {
        slot.mesh->tag_update(scene, true);
        return SectionMeshUpdate::REBUILD;
    }
    slot.mesh->tag_update(scene, false);
    return SectionMeshUpdate::REFIT;
}

void deactivate_section_mesh_slot(ccl::Scene* scene, SectionMeshSlot& slot) {
    if (!slot.source) {
        return;
    }
    // The resource origin is irrelevant for an empty slot; only its fixed-size
    // buffers and identity object remain resident for later reuse.
    scene::ResourcesData empty_resources;
    write_slot_contents(slot, empty_resources, nullptr, ccl::transform_identity());
    slot.source.reset();
    slot.mesh->tag_update(scene, false);
}

}  // namespace cyclesrenderer::realtime

#include "cycles_bridge.h"

#include <Windows.h>

#include <array>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <string>
#include <vector>

namespace {

constexpr std::uint32_t kWidth = 320;
constexpr std::uint32_t kHeight = 180;

bool require_ok(std::uint32_t status, const char* operation) {
    if (status == CYCLES_BRIDGE_STATUS_OK) {
        return true;
    }
    std::cerr << operation << " failed with status " << status << '\n';
    return false;
}

std::string renderer_info(const CyclesBridgeRenderer* renderer) {
    std::array<char, 512> output{};
    if (!require_ok(
            cycles_bridge_write_renderer_info(
                renderer, output.data(), static_cast<std::uint32_t>(output.size())),
            "renderer info")) {
        return {};
    }
    return output.data();
}

std::uint64_t checksum(const std::vector<std::uint8_t>& pixels) {
    std::uint64_t result = 1469598103934665603ULL;
    for (const std::uint8_t value : pixels) {
        result ^= value;
        result *= 1099511628211ULL;
    }
    return result;
}

bool has_rgb_variation(const std::vector<std::uint8_t>& pixels) {
    if (pixels.size() < 8U) {
        return false;
    }
    for (std::size_t offset = 4U; offset + 2U < pixels.size(); offset += 4U) {
        if (pixels[offset] != pixels[0]
            || pixels[offset + 1U] != pixels[1U]
            || pixels[offset + 2U] != pixels[2U]) {
            return true;
        }
    }
    return false;
}

bool has_green_dominant_pixel(const std::vector<std::uint8_t>& pixels) {
    for (std::size_t offset = 0; offset + 3U < pixels.size(); offset += 4U) {
        const int red = pixels[offset];
        const int green = pixels[offset + 1U];
        const int blue = pixels[offset + 2U];
        if (green > red + 16 && green > blue + 16) {
            return true;
        }
    }
    return false;
}

bool wait_for_updated_frame(
    CyclesBridgeRenderer* renderer,
    CyclesBridgeCamera& camera,
    CyclesBridgeFrame& frame,
    std::vector<std::uint8_t>& pixels,
    const char* stage,
    std::string& info) {
    for (int attempt = 0; attempt < 1200; ++attempt) {
        camera.frame_id++;
        if (!require_ok(
                cycles_bridge_render_frame(
                    renderer, &camera, &frame, pixels.data(), pixels.size()),
                "frame render")) {
            info = renderer_info(renderer);
            std::cerr << info << '\n';
            return false;
        }
        info = renderer_info(renderer);
        if (attempt % 50 == 0) {
            std::cerr << "[smoke] " << stage << ": " << info << '\n';
        }
        if ((frame.flags & CYCLES_BRIDGE_FRAME_READY) != 0U
            && (frame.flags & CYCLES_BRIDGE_FRAME_UPDATED) != 0U) {
            return true;
        }
        Sleep(100);
    }
    std::cerr << stage << " did not produce an updated frame before timeout: "
              << info << '\n';
    return false;
}

}  // namespace

int main(int argc, char** argv) {
    const bool require_optix = argc > 1 && std::strcmp(argv[1], "--require-optix") == 0;
    std::cerr << "[smoke] ABI check\n";
    if (cycles_bridge_abi_version() != 5U) {
        std::cerr << "unexpected native ABI " << cycles_bridge_abi_version() << '\n';
        return 1;
    }

    CyclesBridgeRenderer* renderer = nullptr;
    std::cerr << "[smoke] Creating renderer\n";
    if (!require_ok(cycles_bridge_create_renderer(&renderer), "renderer creation")
        || renderer == nullptr) {
        return 1;
    }

    const std::array<CyclesBridgeVertex, 4> vertices = {{
        {-2.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0xFFFFFFFFU, 0U},
        {2.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 0.0F, 0xFFFFFFFFU, 0U},
        {2.0F, 4.0F, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 0xFFFFFFFFU, 0U},
        {-2.0F, 4.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 0xFFFFFFFFU, 0U},
    }};
    const std::array<CyclesBridgeTriangle, 2> triangles = {{
        {0U, 1U, 2U, 0U},
        {0U, 2U, 3U, 0U},
    }};
    const std::array<CyclesBridgeMaterial, 1> materials = {{
        {0U, CYCLES_BRIDGE_MATERIAL_CUTOUT, 0.0F, 0.5F, {0U, 0U, 0U, 0U}},
    }};
    const std::array<std::uint8_t, 16> texture_pixels = {{
        255U, 64U, 32U, 255U,
        32U, 255U, 64U, 255U,
        32U, 64U, 255U, 255U,
        255U, 255U, 32U, 0U,
    }};
    const std::array<CyclesBridgeTexture, 1> textures = {{
        {2U, 2U, 0U, static_cast<std::uint32_t>(texture_pixels.size()), {0U, 0U, 0U, 0U}},
    }};
    CyclesBridgeSceneResources resources{};
    resources.struct_size = sizeof(resources);
    resources.struct_version = 1;
    resources.material_count = static_cast<std::uint32_t>(materials.size());
    resources.texture_count = static_cast<std::uint32_t>(textures.size());
    resources.texture_byte_count = static_cast<std::uint32_t>(texture_pixels.size());
    CyclesBridgeSection section{};
    section.struct_size = sizeof(section);
    section.struct_version = 1;
    section.section_id = 42;
    section.vertex_count = static_cast<std::uint32_t>(vertices.size());
    section.triangle_count = static_cast<std::uint32_t>(triangles.size());

    std::cerr << "[smoke] Streaming textured section; " << renderer_info(renderer) << '\n';
    if (!require_ok(
            cycles_bridge_reset_scene(
                renderer,
                &resources,
                materials.data(),
                textures.data(),
                texture_pixels.data()),
            "scene reset")
        || !require_ok(
            cycles_bridge_upsert_section(
                renderer, &section, vertices.data(), triangles.data()),
            "section upsert")
        || !require_ok(cycles_bridge_commit_scene(renderer), "scene commit")) {
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }

    CyclesBridgeCamera camera{};
    camera.struct_size = sizeof(camera);
    camera.struct_version = 1;
    camera.viewport_width = kWidth;
    camera.viewport_height = kHeight;
    camera.position_x = 0.0;
    camera.position_y = 2.0;
    camera.position_z = 8.0;
    camera.rotation_w = 1.0F;
    camera.vertical_fov_radians = 1.04719755F;
    camera.depth_far = 100.0F;

    std::vector<std::uint8_t> pixels(
        static_cast<std::size_t>(kWidth) * kHeight * 4U);
    CyclesBridgeFrame frame{};
    frame.struct_size = sizeof(frame);
    frame.struct_version = 1;
    std::string info;
    if (!wait_for_updated_frame(
            renderer, camera, frame, pixels, "initial section", info)) {
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    if (require_optix && info.find("backend=OPTIX") == std::string::npos) {
        std::cerr << "OptiX was required but another backend was selected: " << info << '\n';
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    if (!has_rgb_variation(pixels)) {
        std::cerr << "completed frame contains only the background; camera may face away from the scene\n";
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    if (!has_green_dominant_pixel(pixels)) {
        std::cerr << "completed frame did not preserve the green texture channel\n";
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }

    const std::uint64_t initial_generation = frame.generation;
    const std::uint64_t initial_checksum = checksum(pixels);
    std::vector<CyclesBridgeVertex> expanded_vertices(vertices.begin(), vertices.end());
    expanded_vertices.insert(expanded_vertices.end(), vertices.begin(), vertices.end());
    for (std::size_t index = vertices.size(); index < expanded_vertices.size(); ++index) {
        CyclesBridgeVertex& vertex = expanded_vertices[index];
        vertex.position_x += 3.0F;
    }
    std::vector<CyclesBridgeTriangle> expanded_triangles(triangles.begin(), triangles.end());
    expanded_triangles.push_back({4U, 5U, 6U, 0U});
    expanded_triangles.push_back({4U, 6U, 7U, 0U});
    CyclesBridgeSection expanded_section = section;
    expanded_section.vertex_count = static_cast<std::uint32_t>(expanded_vertices.size());
    expanded_section.triangle_count = static_cast<std::uint32_t>(expanded_triangles.size());
    std::cerr << "[smoke] Updating the existing section in place\n";
    if (!require_ok(
            cycles_bridge_upsert_section(
                renderer,
                &expanded_section,
                expanded_vertices.data(),
                expanded_triangles.data()),
            "section update")
        || !require_ok(cycles_bridge_commit_scene(renderer), "updated scene commit")
        || !wait_for_updated_frame(
            renderer, camera, frame, pixels, "updated section", info)) {
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    if (frame.generation <= initial_generation || checksum(pixels) == initial_checksum) {
        std::cerr << "section update did not change the rendered frame\n";
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }

    const std::uint64_t updated_generation = frame.generation;
    std::cerr << "[smoke] Removing the section\n";
    if (!require_ok(
            cycles_bridge_remove_section(renderer, section.section_id),
            "section removal")
        || !require_ok(cycles_bridge_commit_scene(renderer), "removed scene commit")
        || !wait_for_updated_frame(
            renderer, camera, frame, pixels, "removed section", info)) {
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    if (frame.generation <= updated_generation) {
        std::cerr << "section removal did not advance the rendered frame\n";
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    if (has_rgb_variation(pixels)) {
        std::cerr << "removed section remains visible in the rendered frame\n";
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }

    std::cout << info << '\n'
              << "frame=" << kWidth << 'x' << kHeight
              << ";checksum=" << checksum(pixels) << std::endl;
    std::cerr << "[smoke] Destroying renderer\n";
    cycles_bridge_destroy_renderer(renderer);
    std::cerr << "[smoke] Complete\n";
    return 0;
}

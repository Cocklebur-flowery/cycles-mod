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

std::size_t index(std::uint32_t x, std::uint32_t y, std::uint32_t z) {
    constexpr std::uint32_t size_x = 8;
    constexpr std::uint32_t size_z = 8;
    return x + size_x * (z + size_z * y);
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

}  // namespace

int main(int argc, char** argv) {
    const bool require_optix = argc > 1 && std::strcmp(argv[1], "--require-optix") == 0;
    std::cerr << "[smoke] ABI check\n";
    if (cycles_bridge_abi_version() != 3U) {
        std::cerr << "unexpected native ABI " << cycles_bridge_abi_version() << '\n';
        return 1;
    }

    CyclesBridgeRenderer* renderer = nullptr;
    std::cerr << "[smoke] Creating renderer\n";
    if (!require_ok(cycles_bridge_create_renderer(&renderer), "renderer creation")
        || renderer == nullptr) {
        return 1;
    }

    constexpr std::uint32_t size_x = 8;
    constexpr std::uint32_t size_y = 5;
    constexpr std::uint32_t size_z = 8;
    std::vector<std::uint32_t> voxels(size_x * size_y * size_z, 0U);
    for (std::uint32_t z = 0; z < size_z; ++z) {
        for (std::uint32_t x = 0; x < size_x; ++x) {
            voxels[index(x, 0, z)] = 0xFF4D8B39U;
        }
    }
    for (std::uint32_t y = 1; y < 4; ++y) {
        voxels[index(3, y, 3)] = 0xFF3C70B8U;
        voxels[index(4, y, 3)] = 0xFF3C70B8U;
    }
    voxels[index(3, 4, 3)] = 0xFF4967D8U;
    voxels[index(4, 4, 3)] = 0xFF4967D8U;

    CyclesBridgeVoxelScene scene{};
    scene.struct_size = sizeof(scene);
    scene.struct_version = 1;
    scene.size_x = size_x;
    scene.size_y = size_y;
    scene.size_z = size_z;
    std::cerr << "[smoke] Uploading scene; " << renderer_info(renderer) << '\n';
    if (!require_ok(
            cycles_bridge_upload_voxel_scene(
                renderer, &scene, voxels.data(), voxels.size()),
            "scene upload")) {
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }

    CyclesBridgeCamera camera{};
    camera.struct_size = sizeof(camera);
    camera.struct_version = 1;
    camera.viewport_width = kWidth;
    camera.viewport_height = kHeight;
    camera.position_x = 4.0;
    camera.position_y = 3.0;
    camera.position_z = 13.0;
    camera.rotation_w = 1.0F;
    camera.vertical_fov_radians = 1.04719755F;
    camera.depth_far = 100.0F;

    std::vector<std::uint8_t> pixels(
        static_cast<std::size_t>(kWidth) * kHeight * 4U);
    bool frame_ready = false;
    std::string info;
    for (int attempt = 0; attempt < 1200; ++attempt) {
        camera.frame_id = static_cast<std::uint64_t>(attempt);
        if (!require_ok(
                cycles_bridge_render(
                    renderer, &camera, pixels.data(), pixels.size()),
                "frame render")) {
            std::cerr << renderer_info(renderer) << '\n';
            cycles_bridge_destroy_renderer(renderer);
            return 1;
        }
        info = renderer_info(renderer);
        if (attempt % 50 == 0) {
            std::cerr << "[smoke] " << info << '\n';
        }
        if (info.find("frame=ready") != std::string::npos) {
            frame_ready = true;
            break;
        }
        Sleep(100);
    }

    if (!frame_ready) {
        std::cerr << "Cycles did not produce a frame before timeout: " << info << '\n';
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    if (require_optix && info.find("backend=OPTIX") == std::string::npos) {
        std::cerr << "OptiX was required but another backend was selected: " << info << '\n';
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    if (!require_ok(
            cycles_bridge_render(
                renderer, &camera, pixels.data(), pixels.size()),
            "completed frame readback")) {
        cycles_bridge_destroy_renderer(renderer);
        return 1;
    }
    if (!has_rgb_variation(pixels)) {
        std::cerr << "completed frame contains only the background; camera may face away from the scene\n";
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

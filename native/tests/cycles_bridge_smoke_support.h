#pragma once

#include "cycles_bridge.h"

#include <array>
#include <cstdint>
#include <string>
#include <vector>

namespace cyclesrenderer::smoke {

inline constexpr std::uint32_t kWidth = 320U;
inline constexpr std::uint32_t kHeight = 180U;

struct SmokeContext {
    bool require_optix = false;
    CyclesBridgeRenderer* renderer = nullptr;
    CyclesBridgeCapabilities capabilities{};
    CyclesBridgeRenderSettings settings{};
    std::array<CyclesBridgeVertex, 4> vertices{};
    std::array<CyclesBridgeTriangle, 2> triangles{};
    CyclesBridgeSection section{};
    CyclesBridgeCamera camera{};
    std::vector<std::uint8_t> pixels;
    CyclesBridgeFrame frame{};
    std::string info;
    CyclesBridgeDiagnostics diagnostics{};
};

bool require_ok(std::uint32_t status, const char* operation);
std::string renderer_info(const CyclesBridgeRenderer* renderer);
std::uint64_t checksum(const std::vector<std::uint8_t>& pixels);
bool has_rgb_variation(const std::vector<std::uint8_t>& pixels);
bool has_green_dominant_pixel(const std::vector<std::uint8_t>& pixels);

bool wait_for_updated_frame(
    CyclesBridgeRenderer* renderer,
    CyclesBridgeCamera& camera,
    CyclesBridgeFrame& frame,
    std::vector<std::uint8_t>& pixels,
    const char* stage,
    std::string& info,
    bool require_green = false,
    int expected_pass = -1);
bool wait_for_checksum_change(
    CyclesBridgeRenderer* renderer,
    CyclesBridgeCamera& camera,
    CyclesBridgeFrame& frame,
    std::vector<std::uint8_t>& pixels,
    std::uint64_t previous_checksum,
    const char* stage,
    std::string& info);
bool wait_for_empty_scene_frame(
    CyclesBridgeRenderer* renderer,
    CyclesBridgeCamera& camera,
    CyclesBridgeFrame& frame,
    std::vector<std::uint8_t>& pixels,
    std::uint64_t previous_generation,
    const char* stage,
    std::string& info);
bool wait_for_frame_dimensions(
    CyclesBridgeRenderer* renderer,
    CyclesBridgeCamera& camera,
    CyclesBridgeFrame& frame,
    std::vector<std::uint8_t>& pixels,
    std::uint32_t expected_width,
    std::uint32_t expected_height,
    const char* stage,
    std::string& info);

CyclesBridgeRenderSettings default_settings();
bool wait_for_settings(CyclesBridgeRenderer* renderer, std::uint64_t revision);
bool wait_for_actual_sample(
    CyclesBridgeRenderer* renderer,
    CyclesBridgeDiagnostics& diagnostics);
bool wait_for_denoised_still(
    CyclesBridgeRenderer* renderer,
    CyclesBridgeCamera& camera,
    CyclesBridgeFrame& frame,
    std::vector<std::uint8_t>& pixels,
    CyclesBridgeDiagnostics& diagnostics,
    std::string& info,
    std::uint32_t expected_denoiser,
    const char* denoiser_name);
bool wait_for_realtime_dlss(
    CyclesBridgeRenderer* renderer,
    CyclesBridgeCamera& camera,
    CyclesBridgeFrame& frame,
    std::vector<std::uint8_t>& pixels,
    CyclesBridgeDiagnostics& diagnostics,
    std::string& info);
bool verify_progressive_sampling(
    CyclesBridgeRenderer* renderer,
    CyclesBridgeCamera& camera,
    CyclesBridgeDiagnostics& diagnostics);

bool run_bridge_contract_scenarios(SmokeContext& context);
bool run_animation_region_scenarios(SmokeContext& context);
bool run_color_contract_scenarios(SmokeContext& context);
bool run_render_scenarios(SmokeContext& context);
bool run_pbr_material_scenarios(SmokeContext& context);
bool run_denoiser_scenarios(SmokeContext& context);
bool run_scene_lifecycle_scenarios(SmokeContext& context);

}  // namespace cyclesrenderer::smoke

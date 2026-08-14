#include "cycles_bridge_smoke_support.h"

#include <Windows.h>

#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <vector>

namespace cyclesrenderer::smoke {
namespace {

bool duplicate_win32_handle(HANDLE source, HANDLE& duplicate) {
    return DuplicateHandle(
        GetCurrentProcess(),
        source,
        GetCurrentProcess(),
        &duplicate,
        0U,
        FALSE,
        DUPLICATE_SAME_ACCESS) != FALSE;
}

bool verify_material_flag_contract(CyclesBridgeRenderer* renderer) {
    const std::array<std::uint8_t, 4> texture_pixels = {{255U, 255U, 255U, 255U}};
    const std::array<CyclesBridgeTexture, 1> textures = {{
        {1U, 1U, 0U, 4U, CYCLES_BRIDGE_TEXTURE_COLOR_SRGB, {0U, 0U, 0U}},
    }};
    CyclesBridgeSceneResources resources{};
    resources.struct_size = sizeof(resources);
    resources.struct_version = 1U;
    resources.material_count = 1U;
    resources.texture_count = 1U;
    resources.texture_byte_count = static_cast<std::uint32_t>(texture_pixels.size());

    const auto reset_with_flags = [&](std::uint32_t flags) {
        const std::array<CyclesBridgeMaterial, 1> materials = {{
            {0U,
             flags,
             0.0F,
             0.5F,
             CYCLES_BRIDGE_TEXTURE_INDEX_INVALID,
             CYCLES_BRIDGE_TEXTURE_INDEX_INVALID,
             CYCLES_BRIDGE_PBR_NONE,
             CYCLES_BRIDGE_TEXTURE_INDEX_INVALID},
        }};
        return cycles_bridge_reset_scene(
            renderer,
            &resources,
            materials.data(),
            textures.data(),
            texture_pixels.data());
    };

    if (reset_with_flags(CYCLES_BRIDGE_MATERIAL_WATER)
            != CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT
        || reset_with_flags(1U << 31U) != CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT
        || !require_ok(
            reset_with_flags(CYCLES_BRIDGE_MATERIAL_TRANSMISSION),
            "glass material flags")
        || !require_ok(
            reset_with_flags(
                CYCLES_BRIDGE_MATERIAL_TRANSMISSION | CYCLES_BRIDGE_MATERIAL_WATER),
            "water material flags")) {
        std::cerr << "material transmission flag contract was not enforced\n";
        return false;
    }
    return true;
}

}  // namespace

bool run_bridge_contract_scenarios(SmokeContext& context) {
    CyclesBridgeRenderer*& renderer = context.renderer;
    CyclesBridgeCapabilities& capabilities = context.capabilities;
    CyclesBridgeRenderSettings& settings = context.settings;
    std::cerr << "[smoke] ABI check\n";
    if (cycles_bridge_abi_version() != 42U) {
        std::cerr << "unexpected native ABI " << cycles_bridge_abi_version() << '\n';
        return false;
    }
    for (std::uint32_t pass = 0; pass < CYCLES_BRIDGE_PASS_COUNT; ++pass) {
        CyclesBridgePassDescriptor descriptor{};
        descriptor.struct_size = sizeof(descriptor);
        descriptor.struct_version = 1;
        if (!require_ok(
                cycles_bridge_query_pass_descriptor(pass, &descriptor),
                "pass descriptor query")
            || descriptor.pass_id != pass
            || descriptor.source_component_count == 0U
            || descriptor.display_component_count != 4U
            || descriptor.pixel_format != CYCLES_BRIDGE_PIXEL_FORMAT_RGBA16_FLOAT
            || descriptor.semantic == 0U
            || (descriptor.flags & CYCLES_BRIDGE_PASS_DISPLAYABLE) == 0U
            || (descriptor.flags & CYCLES_BRIDGE_PASS_CACHE_RAW) == 0U) {
            std::cerr << "invalid descriptor for pass " << pass << '\n';
            return false;
        }
    }

    std::cerr << "[smoke] Creating renderer\n";
    if (!require_ok(cycles_bridge_create_renderer(&renderer), "renderer creation")
        || renderer == nullptr) {
        return false;
    }

    capabilities = {};
    capabilities.struct_size = sizeof(capabilities);
    capabilities.struct_version = 1;
    settings = default_settings();
    CyclesBridgeDiagnostics initial_diagnostics{};
    initial_diagnostics.struct_size = sizeof(initial_diagnostics);
    initial_diagnostics.struct_version = 1;
    if (!require_ok(
            cycles_bridge_query_diagnostics(renderer, &initial_diagnostics),
            "initial diagnostics")) {
        return false;
    }
    CyclesBridgeVulkanInteropBuffer interop{};
    interop.struct_size = sizeof(interop);
    interop.struct_version = 1;
    interop.width = 480U;
    interop.height = 270U;
    interop.pixel_format = CYCLES_BRIDGE_PIXEL_FORMAT_RGBA16_FLOAT;
    interop.flags = CYCLES_BRIDGE_VULKAN_INTEROP_OWNERSHIP_TRANSFER;
    interop.allocation_byte_count = 480ULL * 270ULL * 8ULL * 3ULL;
    interop.slot_count = 3U;
    interop.slot_stride_bytes = 480U * 270U * 8U;
    std::memcpy(
        interop.device_uuid,
        initial_diagnostics.device_uuid,
        sizeof(interop.device_uuid));
    HANDLE accepted_handle = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    HANDLE accepted_ready_handle = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    HANDLE accepted_release_handle = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    if (accepted_handle == nullptr || accepted_ready_handle == nullptr
        || accepted_release_handle == nullptr) {
        return false;
    }
    HANDLE session_handle = nullptr;
    HANDLE session_ready_handle = nullptr;
    HANDLE session_release_handle = nullptr;
    if (initial_diagnostics.device_uuid_valid != 0U
        && (!duplicate_win32_handle(accepted_handle, session_handle)
            || !duplicate_win32_handle(
                accepted_ready_handle, session_ready_handle)
            || !duplicate_win32_handle(
                accepted_release_handle, session_release_handle))) {
        std::cerr << "failed to duplicate interop handles for session ownership test\n";
        return false;
    }
    interop.memory_handle = static_cast<std::uint64_t>(
        reinterpret_cast<std::uintptr_t>(accepted_handle));
    interop.ready_semaphore_handle = static_cast<std::uint64_t>(
        reinterpret_cast<std::uintptr_t>(accepted_ready_handle));
    interop.release_semaphore_handle = static_cast<std::uint64_t>(
        reinterpret_cast<std::uintptr_t>(accepted_release_handle));
    const std::uint32_t bind_status =
        cycles_bridge_bind_vulkan_interop_buffer(renderer, &interop);
    if (initial_diagnostics.device_uuid_valid != 0U) {
        CyclesBridgeVulkanInteropState interop_state{};
        interop_state.struct_size = sizeof(interop_state);
        interop_state.struct_version = 1U;
        if (!require_ok(bind_status, "interop handle bind")
            || !require_ok(
                cycles_bridge_query_vulkan_interop_state(
                    renderer, &interop_state),
                "interop state query")
            || (interop_state.flags & CYCLES_BRIDGE_VULKAN_INTEROP_BOUND) == 0U) {
            std::cerr << "interop handle ownership was not transferred and closed\n";
            return false;
        }
        HANDLE duplicate_handle = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        HANDLE duplicate_ready_handle = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        HANDLE duplicate_release_handle = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        if (duplicate_handle == nullptr || duplicate_ready_handle == nullptr
            || duplicate_release_handle == nullptr) {
            return false;
        }
        interop.memory_handle = static_cast<std::uint64_t>(
            reinterpret_cast<std::uintptr_t>(duplicate_handle));
        interop.ready_semaphore_handle = static_cast<std::uint64_t>(
            reinterpret_cast<std::uintptr_t>(duplicate_ready_handle));
        interop.release_semaphore_handle = static_cast<std::uint64_t>(
            reinterpret_cast<std::uintptr_t>(duplicate_release_handle));
        if (cycles_bridge_bind_vulkan_interop_buffer(renderer, &interop)
                != CYCLES_BRIDGE_STATUS_RENDER_ERROR
            || CloseHandle(duplicate_handle) != FALSE
            || CloseHandle(duplicate_ready_handle) != FALSE
            || CloseHandle(duplicate_release_handle) != FALSE) {
            std::cerr << "duplicate interop handles were not rejected and closed\n";
            return false;
        }
        CyclesBridgeVulkanInteropState acquired_state{};
        acquired_state.struct_size = sizeof(acquired_state);
        acquired_state.struct_version = 1U;
        if (!require_ok(
                cycles_bridge_acquire_vulkan_interop_frame(
                    renderer, 0U, &acquired_state),
                "empty interop frame acquire")
            || (acquired_state.flags
                & CYCLES_BRIDGE_VULKAN_INTEROP_FRAME_ACQUIRED) != 0U
            || cycles_bridge_release_vulkan_interop_frame(renderer, 1U)
                != CYCLES_BRIDGE_STATUS_RENDER_ERROR
            || !require_ok(
                cycles_bridge_unbind_vulkan_interop_buffer(renderer),
                "interop handle unbind")
            || CloseHandle(accepted_handle) != FALSE
            || CloseHandle(accepted_ready_handle) != FALSE
            || CloseHandle(accepted_release_handle) != FALSE) {
            std::cerr << "empty interop frame ownership was not rejected\n";
            return false;
        }
        if (SetEvent(session_handle) == FALSE
            || SetEvent(session_ready_handle) == FALSE
            || SetEvent(session_release_handle) == FALSE
            || CloseHandle(session_handle) == FALSE
            || CloseHandle(session_ready_handle) == FALSE
            || CloseHandle(session_release_handle) == FALSE) {
            std::cerr << "session interop handle copies did not retain independent ownership\n";
            return false;
        }
        HANDLE rejected_handle = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        HANDLE rejected_ready_handle = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        HANDLE rejected_release_handle = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        if (rejected_handle == nullptr || rejected_ready_handle == nullptr
            || rejected_release_handle == nullptr) {
            return false;
        }
        interop.device_uuid[0] ^= 0xFFU;
        interop.memory_handle = static_cast<std::uint64_t>(
            reinterpret_cast<std::uintptr_t>(rejected_handle));
        interop.ready_semaphore_handle = static_cast<std::uint64_t>(
            reinterpret_cast<std::uintptr_t>(rejected_ready_handle));
        interop.release_semaphore_handle = static_cast<std::uint64_t>(
            reinterpret_cast<std::uintptr_t>(rejected_release_handle));
        if (cycles_bridge_bind_vulkan_interop_buffer(renderer, &interop)
                != CYCLES_BRIDGE_STATUS_RENDER_ERROR
            || CloseHandle(rejected_handle) != FALSE
            || CloseHandle(rejected_ready_handle) != FALSE
            || CloseHandle(rejected_release_handle) != FALSE) {
            std::cerr << "UUID-mismatched interop handles were not rejected and closed\n";
            return false;
        }
    } else if (bind_status != CYCLES_BRIDGE_STATUS_RENDER_ERROR
               || CloseHandle(accepted_handle) != FALSE) {
        std::cerr << "UUID-less interop handle was not rejected and closed\n";
        return false;
    }
    if (!require_ok(
            cycles_bridge_query_capabilities(renderer, &capabilities),
            "capability query")
        || (capabilities.capability_flags & CYCLES_BRIDGE_CAPABILITY_SETTINGS) == 0U
        || (capabilities.capability_flags & CYCLES_BRIDGE_CAPABILITY_PASS_VIEWER) == 0U
        || capabilities.pass_mask != ((1ULL << CYCLES_BRIDGE_PASS_COUNT) - 1ULL)
        || capabilities.color_config_state != CYCLES_BRIDGE_COLOR_CONFIG_READY
        || capabilities.color_lut_edge_length != 64U
        || capabilities.color_lut_pixel_format != CYCLES_BRIDGE_PIXEL_FORMAT_RGBA32_FLOAT
        || (capabilities.color_transform_mask
            & (1U << CYCLES_BRIDGE_VIEW_TRANSFORM_AGX)) == 0U
        || (capabilities.color_transform_mask
            & (1U << CYCLES_BRIDGE_VIEW_TRANSFORM_KHRONOS_PBR_NEUTRAL)) == 0U
        || (capabilities.color_transform_mask
            & (1U << CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_2)) == 0U
        || !require_ok(
            cycles_bridge_apply_settings(renderer, &settings),
            "initial settings")) {
        return false;
    }

    for (std::uint32_t view = CYCLES_BRIDGE_VIEW_TRANSFORM_STANDARD;
         view <= CYCLES_BRIDGE_VIEW_TRANSFORM_AGX_HDR_1000;
         ++view) {
        settings.view_transform = view;
        settings.revision++;
        if (!require_ok(
                cycles_bridge_apply_settings(renderer, &settings),
                "color view settings")) {
            return false;
        }
    }
    CyclesBridgeRenderSettings invalid_view = settings;
    invalid_view.view_transform = CYCLES_BRIDGE_VIEW_TRANSFORM_AGX_HDR_1000 + 1U;
    invalid_view.revision++;
    if (cycles_bridge_apply_settings(renderer, &invalid_view)
        != CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT) {
        std::cerr << "invalid color view was accepted\n";
        return false;
    }
    settings.view_transform = CYCLES_BRIDGE_VIEW_TRANSFORM_AGX;
    for (std::uint32_t working_space = CYCLES_BRIDGE_WORKING_SPACE_LINEAR_REC709;
         working_space <= CYCLES_BRIDGE_WORKING_SPACE_ACESCG;
         ++working_space) {
        settings.working_space = working_space;
        settings.revision++;
        if (!require_ok(
                cycles_bridge_apply_settings(renderer, &settings),
                "working-space settings")) {
            return false;
        }
    }
    settings.working_space = CYCLES_BRIDGE_WORKING_SPACE_LINEAR_REC709;

    for (std::uint32_t pattern = CYCLES_BRIDGE_SAMPLING_PATTERN_SOBOL_BURLEY;
         pattern <= CYCLES_BRIDGE_SAMPLING_PATTERN_BLUE_NOISE_ROUND;
         ++pattern) {
        settings.sampling_pattern = pattern;
        settings.revision++;
        if (!require_ok(
                cycles_bridge_apply_settings(renderer, &settings),
                "sampling pattern settings")) {
            return false;
        }
    }
    CyclesBridgeRenderSettings invalid_sampling = settings;
    invalid_sampling.sampling_pattern =
        CYCLES_BRIDGE_SAMPLING_PATTERN_BLUE_NOISE_ROUND + 1U;
    invalid_sampling.revision++;
    if (cycles_bridge_apply_settings(renderer, &invalid_sampling)
        != CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT) {
        std::cerr << "invalid sampling pattern was accepted\n";
        return false;
    }
    settings.sampling_pattern = CYCLES_BRIDGE_SAMPLING_PATTERN_BLUE_NOISE_FIRST;
    for (std::uint32_t dlss_mode = CYCLES_BRIDGE_DLSS_QUALITY_DLAA;
         dlss_mode <= CYCLES_BRIDGE_DLSS_QUALITY_ULTRA_PERFORMANCE;
         ++dlss_mode) {
        settings.dlss_quality_mode = dlss_mode;
        settings.revision++;
        if (!require_ok(
                cycles_bridge_apply_settings(renderer, &settings),
                "DLSS quality mode settings")) {
            return false;
        }
    }
    CyclesBridgeRenderSettings invalid_dlss_mode = settings;
    invalid_dlss_mode.dlss_quality_mode =
        CYCLES_BRIDGE_DLSS_QUALITY_ULTRA_PERFORMANCE + 1U;
    invalid_dlss_mode.revision++;
    if (cycles_bridge_apply_settings(renderer, &invalid_dlss_mode)
        != CYCLES_BRIDGE_STATUS_INVALID_ARGUMENT) {
        std::cerr << "invalid DLSS quality mode was accepted\n";
        return false;
    }
    settings.dlss_quality_mode = CYCLES_BRIDGE_DLSS_QUALITY_QUALITY;
    settings.camera_clip_near = 0.125F;
    settings.camera_clip_far = 50.0F;
    settings.revision++;
    if (!require_ok(
            cycles_bridge_apply_settings(renderer, &settings),
            "Blue Noise First settings")) {
        return false;
    }

    if (!verify_material_flag_contract(renderer)) {
        return false;
    }


    return true;
}

}  // namespace cyclesrenderer::smoke

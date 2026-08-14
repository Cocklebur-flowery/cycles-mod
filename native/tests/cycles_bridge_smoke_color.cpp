#include "cycles_bridge_smoke_support.h"

#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <vector>

namespace cyclesrenderer::smoke {

bool run_color_contract_scenarios(SmokeContext& context) {
    CyclesBridgeRenderer* renderer = context.renderer;
    std::array<char, 1024> color_info{};
    if (!require_ok(
            cycles_bridge_write_color_management_info(
                renderer,
                color_info.data(),
                static_cast<std::uint32_t>(color_info.size())),
            "color management info")
        || std::strstr(color_info.data(), "state=ready") == nullptr
        || std::strstr(color_info.data(), "displays=6") == nullptr) {
        std::cerr << "invalid color management info: " << color_info.data() << '\n';
        return false;
    }

    CyclesBridgeColorLutDescriptor color_lut{};
    color_lut.struct_size = sizeof(color_lut);
    color_lut.struct_version = 1;
    if (!require_ok(
            cycles_bridge_query_color_lut(
                renderer,
                CYCLES_BRIDGE_DISPLAY_SRGB,
                CYCLES_BRIDGE_VIEW_TRANSFORM_AGX,
                CYCLES_BRIDGE_COLOR_LOOK_AGX_PUNCHY,
                CYCLES_BRIDGE_WORKING_SPACE_LINEAR_REC709,
                &color_lut,
                nullptr,
                0U),
            "AgX LUT descriptor")
        || color_lut.edge_length != 64U
        || color_lut.width != color_lut.edge_length * color_lut.edge_length
        || color_lut.height != color_lut.edge_length
        || color_lut.pixel_format != CYCLES_BRIDGE_PIXEL_FORMAT_RGBA32_FLOAT
        || color_lut.color_look != CYCLES_BRIDGE_COLOR_LOOK_AGX_PUNCHY
        || color_lut.working_space != CYCLES_BRIDGE_WORKING_SPACE_LINEAR_REC709
        || color_lut.display_device != CYCLES_BRIDGE_DISPLAY_SRGB
        || color_lut.pixel_byte_count
            != static_cast<std::uint64_t>(color_lut.width) * color_lut.height
                * 4U * sizeof(float)) {
        return false;
    }

    const auto verify_color_pipeline = [&](std::uint32_t display,
                                           std::uint32_t view,
                                           std::uint32_t look,
                                           const char* label) {
        CyclesBridgeColorLutDescriptor descriptor{};
        descriptor.struct_size = sizeof(descriptor);
        descriptor.struct_version = 1;
        return require_ok(
                   cycles_bridge_query_color_lut(
                       renderer, display, view, look,
                       CYCLES_BRIDGE_WORKING_SPACE_LINEAR_REC709,
                       &descriptor, nullptr, 0U),
                   label)
            && descriptor.display_device == display
            && descriptor.view_transform == view
            && descriptor.color_look == look;
    };
    if (!verify_color_pipeline(
            CYCLES_BRIDGE_DISPLAY_P3,
            CYCLES_BRIDGE_VIEW_TRANSFORM_AGX,
            CYCLES_BRIDGE_COLOR_LOOK_AGX_PUNCHY,
            "Display P3 AgX Punchy LUT")
        || !verify_color_pipeline(
            CYCLES_BRIDGE_DISPLAY_SRGB,
            CYCLES_BRIDGE_VIEW_TRANSFORM_FILMIC,
            CYCLES_BRIDGE_COLOR_LOOK_FILMIC_MEDIUM_CONTRAST,
            "sRGB Filmic Medium Contrast LUT")
        || !verify_color_pipeline(
            CYCLES_BRIDGE_DISPLAY_SRGB,
            CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_2,
            CYCLES_BRIDGE_COLOR_LOOK_ACES_2_GAMUT_COMPRESSION,
            "sRGB ACES 2 gamut compression LUT")
        || !verify_color_pipeline(
            CYCLES_BRIDGE_DISPLAY_REC2100_PQ,
            CYCLES_BRIDGE_VIEW_TRANSFORM_ACES_2_HDR_1000,
            CYCLES_BRIDGE_COLOR_LOOK_NONE,
            "PQ ACES 2 HDR 1000 LUT")) {
        return false;
    }
    CyclesBridgeColorLutDescriptor incompatible_look{};
    incompatible_look.struct_size = sizeof(incompatible_look);
    incompatible_look.struct_version = 1;
    if (!require_ok(
            cycles_bridge_query_color_lut(
                renderer,
                CYCLES_BRIDGE_DISPLAY_P3,
                CYCLES_BRIDGE_VIEW_TRANSFORM_STANDARD,
                CYCLES_BRIDGE_COLOR_LOOK_AGX_PUNCHY,
                CYCLES_BRIDGE_WORKING_SPACE_LINEAR_REC709,
                &incompatible_look,
                nullptr,
                0U),
            "incompatible look fallback")
        || incompatible_look.color_look != CYCLES_BRIDGE_COLOR_LOOK_NONE) {
        return false;
    }
    std::vector<float> color_lut_pixels(
        static_cast<std::size_t>(color_lut.pixel_byte_count / sizeof(float)));
    color_lut.struct_size = sizeof(color_lut);
    color_lut.struct_version = 1;
    if (cycles_bridge_query_color_lut(
            renderer,
            CYCLES_BRIDGE_DISPLAY_SRGB,
            CYCLES_BRIDGE_VIEW_TRANSFORM_AGX,
            CYCLES_BRIDGE_COLOR_LOOK_AGX_PUNCHY,
            CYCLES_BRIDGE_WORKING_SPACE_LINEAR_REC709,
            &color_lut,
            color_lut_pixels.data(),
            sizeof(float)) != CYCLES_BRIDGE_STATUS_BUFFER_TOO_SMALL
        || !require_ok(
            cycles_bridge_query_color_lut(
                renderer,
                CYCLES_BRIDGE_DISPLAY_SRGB,
                CYCLES_BRIDGE_VIEW_TRANSFORM_AGX,
                CYCLES_BRIDGE_COLOR_LOOK_AGX_PUNCHY,
                CYCLES_BRIDGE_WORKING_SPACE_LINEAR_REC709,
                &color_lut,
                color_lut_pixels.data(),
                color_lut.pixel_byte_count),
            "AgX LUT pixels")) {
        return false;
    }
    const std::size_t neutral_midpoint = (
        static_cast<std::size_t>(32U) * color_lut.width
        + static_cast<std::size_t>(32U) * color_lut.edge_length
        + 32U) * 4U;
    if (!std::isfinite(color_lut_pixels[neutral_midpoint])
        || std::abs(color_lut_pixels[neutral_midpoint] - 0.941668F) > 0.0001F
        || std::abs(color_lut_pixels[neutral_midpoint + 3U] - 1.0F) > 0.0001F) {
        std::cerr << "unexpected AgX LUT midpoint "
                  << color_lut_pixels[neutral_midpoint] << '\n';
        return false;
    }
    for (const std::uint32_t working_space : {
             CYCLES_BRIDGE_WORKING_SPACE_LINEAR_REC2020,
             CYCLES_BRIDGE_WORKING_SPACE_ACESCG}) {
        CyclesBridgeColorLutDescriptor working_lut{};
        working_lut.struct_size = sizeof(working_lut);
        working_lut.struct_version = 1;
        if (!require_ok(
                cycles_bridge_query_color_lut(
                    renderer,
                    CYCLES_BRIDGE_DISPLAY_SRGB,
                    CYCLES_BRIDGE_VIEW_TRANSFORM_AGX,
                    CYCLES_BRIDGE_COLOR_LOOK_AGX_PUNCHY,
                    working_space,
                    &working_lut,
                    nullptr,
                    0U),
                "wide-gamut AgX LUT descriptor")
            || working_lut.working_space != working_space
            || working_lut.pixel_byte_count != color_lut.pixel_byte_count) {
            return false;
        }
    }
    CyclesBridgeColorLutDescriptor standard_lut{};
    standard_lut.struct_size = sizeof(standard_lut);
    standard_lut.struct_version = 1;
    if (!require_ok(
            cycles_bridge_query_color_lut(
                renderer,
                CYCLES_BRIDGE_DISPLAY_SRGB,
                CYCLES_BRIDGE_VIEW_TRANSFORM_STANDARD,
                CYCLES_BRIDGE_COLOR_LOOK_NONE,
                CYCLES_BRIDGE_WORKING_SPACE_ACESCG,
                &standard_lut,
                nullptr,
                0U),
            "ACEScg Standard LUT descriptor")
        || standard_lut.view_transform != CYCLES_BRIDGE_VIEW_TRANSFORM_STANDARD
        || standard_lut.working_space != CYCLES_BRIDGE_WORKING_SPACE_ACESCG) {
        return false;
    }


    return true;
}

}  // namespace cyclesrenderer::smoke

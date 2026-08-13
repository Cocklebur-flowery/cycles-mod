#pragma once

#include "cycles_bridge.h"

#include <cstdint>
#include <memory>
#include <string>

class ColorManagement final {
 public:
    ColorManagement();
    ~ColorManagement();

    ColorManagement(const ColorManagement&) = delete;
    ColorManagement& operator=(const ColorManagement&) = delete;

    [[nodiscard]] std::uint32_t state() const;
    [[nodiscard]] std::uint32_t transform_mask() const;
    [[nodiscard]] std::uint32_t lut_edge_length() const;
    [[nodiscard]] std::string info() const;

    bool activate_working_space(
        std::uint32_t working_space,
        std::string& error);

    bool query_lut(
        std::uint32_t display_device,
        std::uint32_t view_transform,
        std::uint32_t color_look,
        std::uint32_t working_space,
        CyclesBridgeColorLutDescriptor& descriptor,
        float* rgba,
        std::uint64_t rgba_capacity,
        std::string& error) const;

 private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};

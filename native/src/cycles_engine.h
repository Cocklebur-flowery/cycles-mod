#pragma once

#include "cycles_bridge.h"

#include <cstdint>
#include <memory>
#include <string>

class CyclesEngine final {
 public:
    CyclesEngine();
    ~CyclesEngine();

    CyclesEngine(const CyclesEngine&) = delete;
    CyclesEngine& operator=(const CyclesEngine&) = delete;

    bool upload_scene(
        const CyclesBridgeScene& scene,
        const CyclesBridgeVertex* vertices,
        const CyclesBridgeTriangle* triangles,
        const CyclesBridgeMaterial* materials,
        const CyclesBridgeTexture* textures,
        const std::uint8_t* texture_pixels,
        std::string& error);

    bool render(
        const CyclesBridgeCamera& camera,
        std::uint8_t* rgba,
        std::uint64_t rgba_capacity,
        std::string& error);

    [[nodiscard]] std::string renderer_info() const;

 private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};

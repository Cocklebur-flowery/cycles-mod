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

    bool reset_scene(
        const CyclesBridgeSceneResources& resources,
        const CyclesBridgeMaterial* materials,
        const CyclesBridgeTexture* textures,
        const std::uint8_t* texture_pixels,
        std::string& error);

    bool upsert_section(
        const CyclesBridgeSection& section,
        const CyclesBridgeVertex* vertices,
        const CyclesBridgeTriangle* triangles,
        std::string& error);

    bool remove_section(std::int64_t section_id, std::string& error);

    bool commit_scene(std::string& error);

    bool apply_settings(
        const CyclesBridgeRenderSettings& settings,
        std::string& error);

    void query_capabilities(CyclesBridgeCapabilities& capabilities) const;

    void query_diagnostics(CyclesBridgeDiagnostics& diagnostics) const;

    bool render(
        const CyclesBridgeCamera& camera,
        std::uint8_t* rgba,
        std::uint64_t rgba_capacity,
        std::string& error);

    bool render_frame(
        const CyclesBridgeCamera& camera,
        CyclesBridgeFrame& frame,
        std::uint8_t* rgba,
        std::uint64_t rgba_capacity,
        std::string& error);

    [[nodiscard]] std::string renderer_info() const;

 private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};

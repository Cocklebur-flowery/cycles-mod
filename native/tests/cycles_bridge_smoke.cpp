#include "cycles_bridge_smoke_support.h"

#include <cstring>
#include <iostream>

int main(int argc, char** argv) {
    cyclesrenderer::smoke::SmokeContext context{};
    context.require_optix =
        argc > 1 && std::strcmp(argv[1], "--require-optix") == 0;

    const bool succeeded =
        cyclesrenderer::smoke::run_bridge_contract_scenarios(context)
        && cyclesrenderer::smoke::run_color_contract_scenarios(context)
        && cyclesrenderer::smoke::run_render_scenarios(context)
        && cyclesrenderer::smoke::run_denoiser_scenarios(context)
        && cyclesrenderer::smoke::run_scene_lifecycle_scenarios(context);
    if (!succeeded) {
        if (context.renderer != nullptr) {
            cycles_bridge_destroy_renderer(context.renderer);
        }
        return 1;
    }

    std::cerr << "[smoke] Destroying renderer\n";
    cycles_bridge_destroy_renderer(context.renderer);
    std::cerr << "[smoke] Complete\n";
    return 0;
}

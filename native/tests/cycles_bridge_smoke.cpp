#include "cycles_bridge_smoke_support.h"

#include <array>
#include <cstring>
#include <iostream>

namespace {

using SmokeContext = cyclesrenderer::smoke::SmokeContext;
using ScenarioRunner = bool (*)(SmokeContext&);

enum class SmokeSuite {
    Full,
    Contract,
    Animation,
    Color,
    Render,
    Pbr,
    Denoiser,
    SceneLifecycle,
};

enum class SmokeOutcome : int {
    Passed = 0,
    Failed = 1,
    Blocked = 77,
};

struct SmokeStage {
    SmokeSuite suite;
    const char* name;
    ScenarioRunner run;
};

constexpr std::array<SmokeStage, 7> kSmokeStages{{
    {SmokeSuite::Contract,
     "contract",
     cyclesrenderer::smoke::run_bridge_contract_scenarios},
    {SmokeSuite::Animation,
     "animation",
     cyclesrenderer::smoke::run_animation_region_scenarios},
    {SmokeSuite::Color,
     "color",
     cyclesrenderer::smoke::run_color_contract_scenarios},
    {SmokeSuite::Render,
     "render",
     cyclesrenderer::smoke::run_render_scenarios},
    {SmokeSuite::Pbr,
     "pbr",
     cyclesrenderer::smoke::run_pbr_material_scenarios},
    {SmokeSuite::Denoiser,
     "denoiser",
     cyclesrenderer::smoke::run_denoiser_scenarios},
    {SmokeSuite::SceneLifecycle,
     "scene-lifecycle",
     cyclesrenderer::smoke::run_scene_lifecycle_scenarios},
}};

const char* suite_name(SmokeSuite suite) {
    if (suite == SmokeSuite::Full) {
        return "full";
    }
    for (const SmokeStage& stage : kSmokeStages) {
        if (stage.suite == suite) {
            return stage.name;
        }
    }
    return "unknown";
}

bool parse_suite(const char* value, SmokeSuite& suite) {
    for (const SmokeStage& stage : kSmokeStages) {
        if (std::strcmp(value, stage.name) == 0) {
            suite = stage.suite;
            return true;
        }
    }
    return false;
}

void print_usage(const char* executable) {
    std::cerr
        << "Usage: " << executable
        << " [--require-optix]"
        << " [--suite contract|animation|color|render|pbr|denoiser|scene-lifecycle]\n";
}

SmokeOutcome run_suite(SmokeSuite suite, SmokeContext& context) {
    const bool run_full_suite = suite == SmokeSuite::Full;
    for (const SmokeStage& stage : kSmokeStages) {
        if (!run_full_suite
            && static_cast<int>(stage.suite) > static_cast<int>(suite)) {
            break;
        }

        std::cerr << "[smoke] suite=" << suite_name(suite)
                  << " stage=" << stage.name << '\n';
        if (stage.run(context)) {
            continue;
        }

        if (run_full_suite || stage.suite == suite) {
            std::cerr << "[smoke] suite=" << suite_name(suite)
                      << " failed at stage=" << stage.name << '\n';
            return SmokeOutcome::Failed;
        }

        std::cerr << "[smoke] suite=" << suite_name(suite)
                  << " blocked by prerequisite=" << stage.name << '\n';
        return SmokeOutcome::Blocked;
    }
    return SmokeOutcome::Passed;
}

}  // namespace

int main(int argc, char** argv) {
    SmokeSuite suite = SmokeSuite::Full;
    bool require_optix = false;
    for (int index = 1; index < argc; ++index) {
        if (std::strcmp(argv[index], "--require-optix") == 0) {
            require_optix = true;
            continue;
        }
        if (std::strcmp(argv[index], "--suite") == 0
            && index + 1 < argc
            && parse_suite(argv[++index], suite)) {
            continue;
        }
        print_usage(argv[0]);
        return 2;
    }

    SmokeContext context{};
    context.require_optix = require_optix;
    const SmokeOutcome outcome = run_suite(suite, context);
    if (context.renderer != nullptr) {
        if (outcome == SmokeOutcome::Passed) {
            std::cerr << "[smoke] Destroying renderer\n";
        }
        cycles_bridge_destroy_renderer(context.renderer);
    }

    if (outcome == SmokeOutcome::Passed) {
        std::cerr << "[smoke] Complete";
        if (suite != SmokeSuite::Full) {
            std::cerr << " suite=" << suite_name(suite);
        }
        std::cerr << '\n';
    }
    return static_cast<int>(outcome);
}

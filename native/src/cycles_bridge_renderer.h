#pragma once

#include "cycles_engine.h"

#include <memory>

struct CyclesBridgeRenderer {
    std::unique_ptr<CyclesEngine> engine;
};

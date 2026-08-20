#include "session_build_timing.h"

#include <chrono>
#include <iostream>
#include <string>

namespace {

using cyclesrenderer::timing::SessionBuildTiming;
using namespace std::chrono_literals;

bool contains(const std::string& value, const std::string& expected) {
    if (value.find(expected) != std::string::npos) {
        return true;
    }
    std::cerr << "missing '" << expected << "' in '" << value << "'\n";
    return false;
}

}  // namespace

int main() {
    SessionBuildTiming timing;
    const auto start = SessionBuildTiming::Clock::time_point{} + 1s;
    if (!contains(timing.describe(start), ";session-build=idle")
        || !contains(timing.describe(start), ";session-build-attempt=0")) {
        return 1;
    }

    timing.begin(start);
    timing.enter(SessionBuildTiming::Phase::WorkingSpace, start);
    timing.enter(SessionBuildTiming::Phase::Interop, start + 10us);
    const std::string active = timing.describe(start + 25us);
    if (!contains(active, ";session-build=active")
        || !contains(active, ";session-build-attempt=1")
        || !contains(active, ";session-build-phase=interop")
        || !contains(active, ";session-build-total-us=25")
        || !contains(active, ";session-build-phase-us=15")
        || !contains(active, ";session-build-us=work:10,interop:15")) {
        return 1;
    }

    timing.enter(SessionBuildTiming::Phase::Session, start + 30us);
    timing.fail(start + 50us);
    const std::string failed = timing.describe(start + 60us);
    if (!contains(failed, ";session-build=failed")
        || !contains(failed, ";session-build-phase=none")
        || !contains(failed, ";session-build-total-us=50")
        || !contains(failed, ";session-build-phase-us=0")
        || !contains(failed, ";session-build-us=work:10,interop:20,create:20")) {
        return 1;
    }

    const auto retry = start + 100us;
    timing.begin(retry);
    timing.enter(SessionBuildTiming::Phase::Configure, retry);
    timing.complete(retry + 7us);
    const std::string complete = timing.describe(retry + 10us);
    if (!contains(complete, ";session-build=complete")
        || !contains(complete, ";session-build-attempt=2")
        || !contains(complete, ";session-build-total-us=7")
        || !contains(
            complete,
            ";session-build-us=work:0,interop:0,create:0,display:0,scene:0,config:7")) {
        return 1;
    }

    SessionBuildTiming guarded;
    {
        SessionBuildTiming::Attempt attempt(guarded);
        attempt.enter(SessionBuildTiming::Phase::Scene);
    }
    if (!contains(guarded.describe(), ";session-build=failed")
        || !contains(guarded.describe(), ";session-build-attempt=1")) {
        return 1;
    }
    return 0;
}

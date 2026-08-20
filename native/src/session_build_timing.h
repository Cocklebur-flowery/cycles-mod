#pragma once

#include <array>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <sstream>
#include <string>
#include <string_view>

namespace cyclesrenderer::timing {

class SessionBuildTiming final {
 public:
    using Clock = std::chrono::steady_clock;

    enum class Phase : std::size_t {
        WorkingSpace,
        Interop,
        Session,
        Display,
        Scene,
        Configure,
        Count,
    };

    class Attempt final {
     public:
        explicit Attempt(SessionBuildTiming& timing) : timing_(&timing) {
            timing_->begin();
        }

        ~Attempt() noexcept {
            if (timing_ != nullptr) {
                try {
                    timing_->fail();
                } catch (...) {
                }
            }
        }

        Attempt(const Attempt&) = delete;
        Attempt& operator=(const Attempt&) = delete;

        void enter(Phase phase) {
            timing_->enter(phase);
        }

        void complete() {
            timing_->complete();
            timing_ = nullptr;
        }

        void fail() {
            timing_->fail();
            timing_ = nullptr;
        }

     private:
        SessionBuildTiming* timing_;
    };

    void begin(Clock::time_point now = Clock::now()) {
        std::lock_guard lock(mutex_);
        attempt_++;
        state_ = State::Active;
        active_phase_ = Phase::Count;
        started_ = now;
        phase_started_ = {};
        finished_ = {};
        phase_micros_.fill(0U);
    }

    void enter(Phase phase, Clock::time_point now = Clock::now()) {
        std::lock_guard lock(mutex_);
        if (state_ != State::Active || phase == Phase::Count) {
            return;
        }
        finish_active_phase(now);
        active_phase_ = phase;
        phase_started_ = now;
    }

    void complete(Clock::time_point now = Clock::now()) {
        finish(State::Complete, now);
    }

    void fail(Clock::time_point now = Clock::now()) {
        finish(State::Failed, now);
    }

    [[nodiscard]] std::string describe(Clock::time_point now = Clock::now()) const {
        std::lock_guard lock(mutex_);
        auto phase_micros = phase_micros_;
        if (state_ == State::Active && active_phase_ != Phase::Count) {
            phase_micros[phase_index(active_phase_)] += elapsed_micros(
                phase_started_, now);
        }
        const std::uint64_t total_micros = started_ == Clock::time_point{}
            ? 0U
            : elapsed_micros(
                started_, state_ == State::Active ? now : finished_);
        const std::uint64_t active_micros = state_ == State::Active
                && active_phase_ != Phase::Count
            ? elapsed_micros(phase_started_, now)
            : 0U;

        std::ostringstream output;
        output << ";session-build=" << state_name(state_)
               << ";session-build-attempt=" << attempt_
               << ";session-build-phase=" << phase_name(active_phase_)
               << ";session-build-total-us=" << total_micros
               << ";session-build-phase-us=" << active_micros
               << ";session-build-us="
               << "work:" << phase_micros[phase_index(Phase::WorkingSpace)]
               << ",interop:" << phase_micros[phase_index(Phase::Interop)]
               << ",create:" << phase_micros[phase_index(Phase::Session)]
               << ",display:" << phase_micros[phase_index(Phase::Display)]
               << ",scene:" << phase_micros[phase_index(Phase::Scene)]
               << ",config:" << phase_micros[phase_index(Phase::Configure)];
        return output.str();
    }

 private:
    enum class State {
        Idle,
        Active,
        Complete,
        Failed,
    };

    static constexpr std::size_t kPhaseCount =
        static_cast<std::size_t>(Phase::Count);

    static constexpr std::size_t phase_index(Phase phase) {
        return static_cast<std::size_t>(phase);
    }

    static std::uint64_t elapsed_micros(
        Clock::time_point start,
        Clock::time_point end) {
        if (start == Clock::time_point{} || end <= start) {
            return 0U;
        }
        return static_cast<std::uint64_t>(
            std::chrono::duration_cast<std::chrono::microseconds>(
                end - start).count());
    }

    static std::string_view state_name(State state) {
        switch (state) {
            case State::Idle:
                return "idle";
            case State::Active:
                return "active";
            case State::Complete:
                return "complete";
            case State::Failed:
                return "failed";
        }
        return "unknown";
    }

    static std::string_view phase_name(Phase phase) {
        switch (phase) {
            case Phase::WorkingSpace:
                return "working-space";
            case Phase::Interop:
                return "interop";
            case Phase::Session:
                return "session-create";
            case Phase::Display:
                return "display-passes";
            case Phase::Scene:
                return "scene-build";
            case Phase::Configure:
                return "scene-configure";
            case Phase::Count:
                return "none";
        }
        return "unknown";
    }

    void finish(State state, Clock::time_point now) {
        std::lock_guard lock(mutex_);
        if (state_ != State::Active) {
            return;
        }
        finish_active_phase(now);
        state_ = state;
        active_phase_ = Phase::Count;
        phase_started_ = {};
        finished_ = now;
    }

    void finish_active_phase(Clock::time_point now) {
        if (active_phase_ == Phase::Count) {
            return;
        }
        phase_micros_[phase_index(active_phase_)] += elapsed_micros(
            phase_started_, now);
    }

    mutable std::mutex mutex_;
    std::uint64_t attempt_ = 0U;
    State state_ = State::Idle;
    Phase active_phase_ = Phase::Count;
    Clock::time_point started_{};
    Clock::time_point phase_started_{};
    Clock::time_point finished_{};
    std::array<std::uint64_t, kPhaseCount> phase_micros_{};
};

}  // namespace cyclesrenderer::timing

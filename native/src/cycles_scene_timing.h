#pragma once

#include "cycles_bridge.h"

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <limits>
#include <map>
#include <mutex>
#include <string_view>

namespace cyclesrenderer::timing {

class CyclesSceneTiming final {
 public:
    using Clock = std::chrono::steady_clock;

    void record_commit(std::uint64_t revision) {
        std::lock_guard lock(mutex_);
        commit_times_[revision] = Clock::now();
        while (commit_times_.size() > kMaximumPendingCommits) {
            commit_times_.erase(commit_times_.begin());
        }
    }

    void begin_scene_update(std::uint64_t revision) {
        const auto now = Clock::now();
        std::lock_guard lock(mutex_);
        active_revision_ = revision;
        active_started_ = now;
        device_started_ = {};
        geometry_started_ = {};
        bvh_started_ = {};
        active_device_micros_ = 0U;
        active_geometry_micros_ = 0U;
        active_bvh_micros_ = 0U;
        const auto commit = commit_times_.find(revision);
        if (commit != commit_times_.end()) {
            queue_.record(elapsed_micros(commit->second, now));
        }
        for (auto entry = commit_times_.begin(); entry != commit_times_.end();) {
            if (entry->first <= revision) {
                entry = commit_times_.erase(entry);
            } else {
                ++entry;
            }
        }
    }

    void record_reset_wait(std::uint64_t revision, std::uint32_t micros) {
        if (revision == 0U) {
            return;
        }
        std::lock_guard lock(mutex_);
        if (active_revision_ == revision) {
            reset_wait_.record(micros);
        }
    }

    void observe_status(std::string_view status, std::string_view substatus) {
        const auto now = Clock::now();
        std::lock_guard lock(mutex_);
        if (active_revision_ == 0U) {
            return;
        }

        const bool device_active = status.starts_with("Updating ");
        const bool geometry_active = status.starts_with("Updating Mesh")
            || status.starts_with("Updating Geometry BVH");
        const bool bvh_active = status.find("BVH") != std::string_view::npos
            || substatus.find("acceleration structure") != std::string_view::npos
            || substatus.find("BVH") != std::string_view::npos;
        update_phase(device_started_, active_device_micros_, device_active, now);
        update_phase(geometry_started_, active_geometry_micros_, geometry_active, now);
        update_phase(bvh_started_, active_bvh_micros_, bvh_active, now);
    }

    void complete_scene_update(std::uint64_t revision) {
        if (revision == 0U) {
            return;
        }
        const auto now = Clock::now();
        std::lock_guard lock(mutex_);
        if (active_revision_ != revision) {
            return;
        }

        update_phase(device_started_, active_device_micros_, false, now);
        update_phase(geometry_started_, active_geometry_micros_, false, now);
        update_phase(bvh_started_, active_bvh_micros_, false, now);
        device_update_.record(active_device_micros_);
        geometry_update_.record(active_geometry_micros_);
        bvh_update_.record(active_bvh_micros_);
        first_frame_.record(elapsed_micros(active_started_, now));
        completed_revision_ = revision;
        completed_count_++;
        active_revision_ = 0U;
        active_started_ = {};
    }

    void fill_diagnostics(CyclesBridgeDiagnostics& diagnostics) const {
        std::lock_guard lock(mutex_);
        diagnostics.scene_timing_revision = completed_revision_;
        diagnostics.scene_timing_count = completed_count_;
        queue_.fill(
            diagnostics.last_scene_queue_micros,
            diagnostics.ema_scene_queue_micros,
            diagnostics.max_scene_queue_micros);
        reset_wait_.fill(
            diagnostics.last_reset_wait_micros,
            diagnostics.ema_reset_wait_micros,
            diagnostics.max_reset_wait_micros);
        device_update_.fill(
            diagnostics.last_device_update_micros,
            diagnostics.ema_device_update_micros,
            diagnostics.max_device_update_micros);
        geometry_update_.fill(
            diagnostics.last_geometry_update_micros,
            diagnostics.ema_geometry_update_micros,
            diagnostics.max_geometry_update_micros);
        bvh_update_.fill(
            diagnostics.last_bvh_update_micros,
            diagnostics.ema_bvh_update_micros,
            diagnostics.max_bvh_update_micros);
        first_frame_.fill(
            diagnostics.last_scene_first_frame_micros,
            diagnostics.ema_scene_first_frame_micros,
            diagnostics.max_scene_first_frame_micros);
    }

 private:
    struct Metric {
        void record(std::uint32_t micros) {
            last = micros;
            ema = ema == 0U
                ? micros
                : static_cast<std::uint32_t>(
                    (static_cast<std::uint64_t>(ema) * 7U + micros) / 8U);
            maximum = std::max(maximum, micros);
        }

        void fill(
            std::uint32_t& output_last,
            std::uint32_t& output_ema,
            std::uint32_t& output_maximum) const {
            output_last = last;
            output_ema = ema;
            output_maximum = maximum;
        }

        std::uint32_t last = 0U;
        std::uint32_t ema = 0U;
        std::uint32_t maximum = 0U;
    };

    static std::uint32_t elapsed_micros(
        Clock::time_point start,
        Clock::time_point end) {
        const auto micros = std::chrono::duration_cast<std::chrono::microseconds>(
            end - start).count();
        return static_cast<std::uint32_t>(std::clamp<std::int64_t>(
            micros, 0, std::numeric_limits<std::uint32_t>::max()));
    }

    static void update_phase(
        Clock::time_point& started,
        std::uint32_t& accumulated_micros,
        bool active,
        Clock::time_point now) {
        if (active && started == Clock::time_point{}) {
            started = now;
        } else if (!active && started != Clock::time_point{}) {
            accumulated_micros = saturating_add(
                accumulated_micros, elapsed_micros(started, now));
            started = {};
        }
    }

    static std::uint32_t saturating_add(std::uint32_t left, std::uint32_t right) {
        return left > std::numeric_limits<std::uint32_t>::max() - right
            ? std::numeric_limits<std::uint32_t>::max()
            : left + right;
    }

    static constexpr std::size_t kMaximumPendingCommits = 16U;

    mutable std::mutex mutex_;
    std::map<std::uint64_t, Clock::time_point> commit_times_;
    std::uint64_t active_revision_ = 0U;
    Clock::time_point active_started_{};
    Clock::time_point device_started_{};
    Clock::time_point geometry_started_{};
    Clock::time_point bvh_started_{};
    std::uint32_t active_device_micros_ = 0U;
    std::uint32_t active_geometry_micros_ = 0U;
    std::uint32_t active_bvh_micros_ = 0U;
    std::uint64_t completed_revision_ = 0U;
    std::uint64_t completed_count_ = 0U;
    Metric queue_;
    Metric reset_wait_;
    Metric device_update_;
    Metric geometry_update_;
    Metric bvh_update_;
    Metric first_frame_;
};

}  // namespace cyclesrenderer::timing

#pragma once

#include "cycles_bridge.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

#include "session/display_driver.h"
#include "util/types.h"

namespace cyclesrenderer {

namespace frame_detail {

inline bool nearly_equal(double first, double second, double tolerance) {
    return std::abs(first - second) <= tolerance;
}

inline float linear_to_srgb(float value) {
    if (!std::isfinite(value) || value <= 0.0F) {
        return 0.0F;
    }
    if (value <= 0.0031308F) {
        return value * 12.92F;
    }
    return 1.055F * std::pow(value, 1.0F / 2.4F) - 0.055F;
}

inline std::uint8_t to_unorm(float value) {
    if (std::isnan(value)) {
        return 0U;
    }
    const float clamped = std::clamp(value, 0.0F, 1.0F);
    return static_cast<std::uint8_t>(std::lround(clamped * 255.0F));
}

inline std::uint32_t elapsed_micros(
    std::chrono::steady_clock::time_point start,
    std::chrono::steady_clock::time_point end) {
    const auto value = std::chrono::duration_cast<std::chrono::microseconds>(end - start).count();
    return static_cast<std::uint32_t>(std::clamp<std::int64_t>(
        value, 0, std::numeric_limits<std::uint32_t>::max()));
}

inline std::uint32_t update_ema(std::uint32_t previous, std::uint32_t value) {
    if (previous == 0U) {
        return value;
    }
    return static_cast<std::uint32_t>(
        (static_cast<std::uint64_t>(previous) * 7U + value) / 8U);
}

}  // namespace frame_detail

constexpr std::size_t kFrameSlotCount = 3U;

struct FrameSlot {
    std::vector<ccl::half4> pixels;
    std::uint32_t pass = CYCLES_BRIDGE_PASS_COMBINED;
    std::uint32_t variant = CYCLES_BRIDGE_FRAME_VARIANT_RAW;
    std::uint64_t camera_revision = 0;
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::uint32_t sample_count = 0;
    std::uint32_t readers = 0;
    std::uint64_t generation = 0;
};

struct CachedPassFrame {
    std::uint32_t pass = CYCLES_BRIDGE_PASS_COMBINED;
    std::uint32_t variant = CYCLES_BRIDGE_FRAME_VARIANT_RAW;
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::uint32_t sample_count = 0;
    std::uint64_t last_access = 0;
    std::vector<ccl::half4> pixels;
};

class FrameStore final {
 public:
    void clear() {
        std::lock_guard lock(mutex_);
        width_ = 0;
        height_ = 0;
        sample_count_ = 0;
        last_frame_completed_ = {};
        published_slot_ = -1;
        writing_slot_ = -1;
        zero_next_display_ = true;
        produced_camera_revision_ = 0;
        pass_cache_.clear();
        pass_cache_bytes_ = 0U;
        generation_++;
    }

    void invalidate_pass_cache() {
        std::lock_guard lock(mutex_);
        pass_cache_.clear();
        pass_cache_bytes_ = 0U;
    }

    void set_cache_budget(std::uint32_t megabytes) {
        std::lock_guard lock(mutex_);
        set_cache_budget_locked(megabytes);
    }

    void configure(
        const CyclesBridgeRenderSettings& settings,
        float depth_far,
        int target_samples,
        bool denoised,
        std::uint64_t camera_revision) {
        std::lock_guard lock(mutex_);
        set_cache_budget_locked(settings.pass_cache_megabytes);
        const std::uint32_t requested_variant = denoised
                && settings.active_pass == CYCLES_BRIDGE_PASS_COMBINED
            ? CYCLES_BRIDGE_FRAME_VARIANT_DENOISED
            : CYCLES_BRIDGE_FRAME_VARIANT_RAW;
        if (active_pass_ != settings.active_pass || active_variant_ != requested_variant) {
            cache_published_locked();
            active_pass_ = settings.active_pass;
            active_variant_ = requested_variant;
            restore_cached_locked();
        }
        const float exposure_scale = std::exp2(settings.exposure_ev);
        const float normalized_depth_far = std::max(1.0F, depth_far);
        const int normalized_target_samples = std::max(1, target_samples);
        const bool rebuild_lut = !display_lut_ready_
            || active_pass_ != settings.active_pass
            || !frame_detail::nearly_equal(exposure_scale_, exposure_scale, 1.0e-6)
            || !frame_detail::nearly_equal(gamma_, settings.gamma, 1.0e-6)
            || view_transform_ != settings.view_transform
            || !frame_detail::nearly_equal(depth_far_, normalized_depth_far, 1.0e-6)
            || target_samples_ != normalized_target_samples;
        active_pass_ = settings.active_pass;
        exposure_scale_ = exposure_scale;
        gamma_ = settings.gamma;
        view_transform_ = settings.view_transform;
        depth_far_ = normalized_depth_far;
        target_samples_ = normalized_target_samples;
        active_camera_revision_ = camera_revision;
        if (rebuild_lut) {
            rebuild_display_luts_locked();
            display_lut_ready_ = true;
        }
    }

    bool display_update_begin(
        const ccl::DisplayDriver::Params& params,
        int texture_width,
        int texture_height) {
        if (params.full_size.x <= 0 || params.full_size.y <= 0
            || texture_width <= 0 || texture_height <= 0) {
            return false;
        }
        mutex_.lock();
        if (display_update_active_) {
            mutex_.unlock();
            return false;
        }
        int next_slot = -1;
        for (std::size_t index = 0; index < slots_.size(); ++index) {
            const FrameSlot& slot = slots_[index];
            if (static_cast<int>(index) != published_slot_ && slot.readers == 0U) {
                next_slot = static_cast<int>(index);
                break;
            }
        }
        if (next_slot < 0) {
            dropped_display_updates_++;
            mutex_.unlock();
            return false;
        }
        display_update_active_ = true;
        writing_slot_ = next_slot;
        display_update_started_ = std::chrono::steady_clock::now();
        const auto width = static_cast<std::uint32_t>(params.full_size.x);
        const auto height = static_cast<std::uint32_t>(params.full_size.y);
        FrameSlot& slot = slots_[static_cast<std::size_t>(writing_slot_)];
        slot.pass = active_pass_;
        slot.variant = active_variant_;
        slot.camera_revision = active_camera_revision_;
        slot.width = width;
        slot.height = height;
        const std::size_t pixel_count = static_cast<std::size_t>(width) * height;
        if (slot.pixels.size() != pixel_count) {
            const ccl::half zero(0U);
            slot.pixels.assign(
                pixel_count,
                ccl::half4{zero, zero, zero, zero});
        } else if (zero_next_display_) {
            const ccl::half zero(0U);
            std::fill(
                slot.pixels.begin(),
                slot.pixels.end(),
                ccl::half4{zero, zero, zero, zero});
        }
        zero_next_display_ = false;
        return true;
    }

    ccl::half4* display_buffer() {
        if (writing_slot_ < 0) {
            return nullptr;
        }
        FrameSlot& slot = slots_[static_cast<std::size_t>(writing_slot_)];
        return slot.pixels.empty() ? nullptr : slot.pixels.data();
    }

    void display_update_end() {
        const auto completed = std::chrono::steady_clock::now();
        FrameSlot& slot = slots_[static_cast<std::size_t>(writing_slot_)];
        slot.generation = ++generation_;
        slot.sample_count = static_cast<std::uint32_t>(std::max(0, sample_count_));
        width_ = slot.width;
        height_ = slot.height;
        published_slot_ = writing_slot_;
        writing_slot_ = -1;
        last_convert_micros_ = frame_detail::elapsed_micros(display_update_started_, completed);
        ema_convert_micros_ = frame_detail::update_ema(ema_convert_micros_, last_convert_micros_);
        max_convert_micros_ = std::max(max_convert_micros_, last_convert_micros_);
        produced_frame_count_++;
        produced_camera_revision_ = slot.camera_revision;
        last_frame_completed_ = completed;
        display_update_active_ = false;
        mutex_.unlock();
    }

    void display_update_cancel() {
        if (!display_update_active_) {
            return;
        }
        writing_slot_ = -1;
        display_update_active_ = false;
        mutex_.unlock();
    }

    void zero_display() {
        std::lock_guard lock(mutex_);
        zero_next_display_ = true;
    }

    void set_sample_count(int sample_count) {
        std::lock_guard lock(mutex_);
        sample_count_ = sample_count;
    }

    bool copy_native(
        std::uint8_t* output,
        std::uint64_t capacity,
        std::uint64_t previous_generation,
        CyclesBridgeFrame& frame,
        std::string& error) {
        std::lock_guard lock(mutex_);
        frame.width = width_;
        frame.height = height_;
        frame.generation = generation_;
        frame.sample_count = static_cast<std::uint32_t>(std::max(0, sample_count_));
        frame.pixel_byte_count = 0;
        frame.flags = 0;
        const FrameSlot* slot = published_frame_locked();
        if (slot == nullptr) {
            return true;
        }

        frame.flags |= CYCLES_BRIDGE_FRAME_READY;
        if (generation_ == previous_generation) {
            unchanged_poll_count_++;
            return true;
        }
        const std::size_t output_size = slot->pixels.size() * 4U;
        if (output == nullptr || capacity < output_size) {
            error = "native frame output buffer is too small";
            return false;
        }
        const auto copy_start = std::chrono::steady_clock::now();
        for (std::size_t index = 0; index < slot->pixels.size(); ++index) {
            write_compatibility_pixel(output + index * 4U, slot->pixels[index]);
        }
        const auto copy_end = std::chrono::steady_clock::now();
        last_copy_micros_ = frame_detail::elapsed_micros(copy_start, copy_end);
        ema_copy_micros_ = frame_detail::update_ema(ema_copy_micros_, last_copy_micros_);
        max_copy_micros_ = std::max(max_copy_micros_, last_copy_micros_);
        copied_frame_count_++;
        copied_byte_count_ += output_size;
        frame.pixel_byte_count = static_cast<std::uint32_t>(output_size);
        frame.flags |= CYCLES_BRIDGE_FRAME_UPDATED;
        return true;
    }

    void copy_scaled(std::uint8_t* output, std::uint32_t width, std::uint32_t height) const {
        std::lock_guard lock(mutex_);
        const FrameSlot* slot = published_frame_locked();
        if (slot == nullptr) {
            for (std::uint64_t pixel = 0; pixel < static_cast<std::uint64_t>(width) * height;
                 ++pixel) {
                output[pixel * 4U] = 104U;
                output[pixel * 4U + 1U] = 151U;
                output[pixel * 4U + 2U] = 204U;
                output[pixel * 4U + 3U] = 255U;
            }
            return;
        }

        for (std::uint32_t y = 0; y < height; ++y) {
            const std::uint32_t source_y = std::min(
                height_ - 1U,
                static_cast<std::uint32_t>(static_cast<std::uint64_t>(y) * height_ / height));
            for (std::uint32_t x = 0; x < width; ++x) {
                const std::uint32_t source_x = std::min(
                    width_ - 1U,
                    static_cast<std::uint32_t>(static_cast<std::uint64_t>(x) * width_ / width));
                const std::size_t source =
                    static_cast<std::size_t>(source_y) * width_ + source_x;
                const std::size_t target =
                    (static_cast<std::size_t>(y) * width + x) * 4U;
                write_compatibility_pixel(output + target, slot->pixels[source]);
            }
        }
    }

    [[nodiscard]] bool ready() const {
        std::lock_guard lock(mutex_);
        return published_frame_locked() != nullptr;
    }

    [[nodiscard]] std::uint64_t generation() const {
        std::lock_guard lock(mutex_);
        return generation_;
    }

    [[nodiscard]] std::uint64_t produced_frame_count() const {
        std::lock_guard lock(mutex_);
        return produced_frame_count_;
    }

    [[nodiscard]] std::uint64_t produced_camera_revision() const {
        std::lock_guard lock(mutex_);
        return produced_camera_revision_;
    }

    [[nodiscard]] std::pair<std::uint32_t, std::uint32_t> size() const {
        std::lock_guard lock(mutex_);
        return {width_, height_};
    }

    [[nodiscard]] int sample_count() const {
        std::lock_guard lock(mutex_);
        return sample_count_;
    }

    bool acquire_frame(
        std::uint64_t previous_generation,
        CyclesBridgeFrameView& view,
        std::string&) {
        std::lock_guard lock(mutex_);
        view.width = width_;
        view.height = height_;
        view.generation = generation_;
        view.sample_count = static_cast<std::uint32_t>(std::max(0, sample_count_));
        view.pixel_format = CYCLES_BRIDGE_PIXEL_FORMAT_RGBA16_FLOAT;
        view.pixel_byte_count = 0U;
        view.token = 0U;
        view.pixels = nullptr;
        view.flags = 0U;
        FrameSlot* slot = published_frame_locked();
        if (slot == nullptr) {
            return true;
        }
        view.flags |= CYCLES_BRIDGE_FRAME_READY;
        view.sample_count = slot->sample_count;
        if (slot->generation == previous_generation) {
            unchanged_poll_count_++;
            return true;
        }
        const std::size_t slot_index = static_cast<std::size_t>(published_slot_);
        slot->readers++;
        active_frame_leases_++;
        peak_frame_leases_ = std::max(peak_frame_leases_, active_frame_leases_);
        view.width = slot->width;
        view.height = slot->height;
        view.generation = slot->generation;
        view.pixel_byte_count = slot->pixels.size() * sizeof(ccl::half4);
        view.token = (slot->generation << 2U) | (slot_index + 1U);
        view.pixels = reinterpret_cast<const std::uint8_t*>(slot->pixels.data());
        view.flags |= CYCLES_BRIDGE_FRAME_UPDATED;
        return true;
    }

    bool release_frame(std::uint64_t token, std::string& error) {
        std::lock_guard lock(mutex_);
        const std::uint64_t encoded_slot = token & 3U;
        if (encoded_slot == 0U || encoded_slot > slots_.size()) {
            error = "invalid frame lease token";
            return false;
        }
        FrameSlot& slot = slots_[static_cast<std::size_t>(encoded_slot - 1U)];
        if (slot.readers == 0U || slot.generation != (token >> 2U)) {
            error = "expired frame lease token";
            return false;
        }
        slot.readers--;
        active_frame_leases_--;
        return true;
    }

    void fill_diagnostics(CyclesBridgeDiagnostics& diagnostics) const {
        std::lock_guard lock(mutex_);
        diagnostics.frame_generation = generation_;
        diagnostics.width = width_;
        diagnostics.height = height_;
        diagnostics.sample_count = static_cast<std::uint32_t>(std::max(0, sample_count_));
        diagnostics.frame_ready = published_frame_locked() == nullptr ? 0U : 1U;
        diagnostics.active_frame_leases = active_frame_leases_;
        diagnostics.peak_frame_leases = peak_frame_leases_;
        diagnostics.frame_slot_count = static_cast<std::uint32_t>(slots_.size());
        diagnostics.dropped_display_updates = dropped_display_updates_;
        diagnostics.produced_frame_count = produced_frame_count_;
        diagnostics.copied_frame_count = copied_frame_count_;
        diagnostics.copied_byte_count = copied_byte_count_;
        diagnostics.unchanged_poll_count = unchanged_poll_count_;
        diagnostics.last_convert_micros = last_convert_micros_;
        diagnostics.ema_convert_micros = ema_convert_micros_;
        diagnostics.max_convert_micros = max_convert_micros_;
        diagnostics.last_copy_micros = last_copy_micros_;
        diagnostics.ema_copy_micros = ema_copy_micros_;
        diagnostics.max_copy_micros = max_copy_micros_;
        diagnostics.frame_age_micros = last_frame_completed_ ==
                std::chrono::steady_clock::time_point{}
            ? 0U
            : frame_detail::elapsed_micros(last_frame_completed_, std::chrono::steady_clock::now());
        diagnostics.frame_pixel_format = CYCLES_BRIDGE_PIXEL_FORMAT_RGBA16_FLOAT;
        diagnostics.cached_raw_pass_mask = 0U;
        diagnostics.cached_denoised_pass_mask = 0U;
        for (const CachedPassFrame& cached : pass_cache_) {
            if (cached.pass >= CYCLES_BRIDGE_PASS_COUNT) {
                continue;
            }
            if (cached.variant == CYCLES_BRIDGE_FRAME_VARIANT_DENOISED) {
                diagnostics.cached_denoised_pass_mask |= 1ULL << cached.pass;
            } else {
                diagnostics.cached_raw_pass_mask |= 1ULL << cached.pass;
            }
        }
        diagnostics.pass_cache_bytes = pass_cache_bytes_;
        diagnostics.pass_cache_budget_bytes = pass_cache_budget_bytes_;
        diagnostics.pass_cache_entry_count = static_cast<std::uint32_t>(pass_cache_.size());
        diagnostics.pass_cache_eviction_count = pass_cache_eviction_count_;
        diagnostics.pass_cache_hit_count = pass_cache_hit_count_;
        const FrameSlot* published = published_frame_locked();
        diagnostics.active_pass = published == nullptr
            ? active_pass_
            : published->pass;
        diagnostics.active_frame_variant = published == nullptr
            ? active_variant_
            : published->variant;
    }

 private:
    void set_cache_budget_locked(std::uint32_t megabytes) {
        pass_cache_budget_bytes_ = static_cast<std::uint64_t>(
            std::clamp(megabytes, 64U, 4096U)) * 1024U * 1024U;
        while (pass_cache_bytes_ > pass_cache_budget_bytes_ && !pass_cache_.empty()) {
            evict_oldest_cache_entry_locked();
        }
    }

    void evict_oldest_cache_entry_locked() {
        const auto oldest = std::min_element(
            pass_cache_.begin(),
            pass_cache_.end(),
            [](const CachedPassFrame& first, const CachedPassFrame& second) {
                return first.last_access < second.last_access;
            });
        if (oldest == pass_cache_.end()) {
            return;
        }
        pass_cache_bytes_ -= oldest->pixels.size() * sizeof(ccl::half4);
        pass_cache_.erase(oldest);
        pass_cache_eviction_count_++;
    }

    void cache_published_locked() {
        const FrameSlot* slot = published_frame_locked();
        if (slot == nullptr || slot->pixels.empty()) {
            return;
        }
        const std::uint64_t bytes = slot->pixels.size() * sizeof(ccl::half4);
        if (bytes > pass_cache_budget_bytes_) {
            return;
        }
        const auto existing = std::find_if(
            pass_cache_.begin(),
            pass_cache_.end(),
            [slot](const CachedPassFrame& cached) {
                return cached.pass == slot->pass && cached.variant == slot->variant;
            });
        if (existing != pass_cache_.end()) {
            pass_cache_bytes_ -= existing->pixels.size() * sizeof(ccl::half4);
            pass_cache_.erase(existing);
        }
        while (pass_cache_bytes_ + bytes > pass_cache_budget_bytes_
               && !pass_cache_.empty()) {
            evict_oldest_cache_entry_locked();
        }
        CachedPassFrame cached;
        cached.pass = slot->pass;
        cached.variant = slot->variant;
        cached.width = slot->width;
        cached.height = slot->height;
        cached.sample_count = slot->sample_count;
        cached.last_access = ++pass_cache_access_sequence_;
        cached.pixels = slot->pixels;
        pass_cache_bytes_ += bytes;
        pass_cache_.push_back(std::move(cached));
    }

    void restore_cached_locked() {
        const auto cached = std::find_if(
            pass_cache_.begin(),
            pass_cache_.end(),
            [this](const CachedPassFrame& candidate) {
                return candidate.pass == active_pass_
                    && candidate.variant == active_variant_;
            });
        if (cached == pass_cache_.end()) {
            return;
        }
        int restore_slot = -1;
        for (std::size_t index = 0; index < slots_.size(); ++index) {
            if (static_cast<int>(index) != published_slot_ && slots_[index].readers == 0U) {
                restore_slot = static_cast<int>(index);
                break;
            }
        }
        if (restore_slot < 0) {
            return;
        }
        cached->last_access = ++pass_cache_access_sequence_;
        FrameSlot& slot = slots_[static_cast<std::size_t>(restore_slot)];
        slot.pixels = cached->pixels;
        slot.pass = cached->pass;
        slot.variant = cached->variant;
        slot.width = cached->width;
        slot.height = cached->height;
        slot.sample_count = cached->sample_count;
        slot.generation = ++generation_;
        width_ = slot.width;
        height_ = slot.height;
        sample_count_ = static_cast<int>(slot.sample_count);
        published_slot_ = restore_slot;
        last_frame_completed_ = std::chrono::steady_clock::now();
        pass_cache_hit_count_++;
    }

    FrameSlot* published_frame_locked() {
        if (published_slot_ < 0) {
            return nullptr;
        }
        FrameSlot& slot = slots_[static_cast<std::size_t>(published_slot_)];
        return slot.pixels.empty() ? nullptr : &slot;
    }

    const FrameSlot* published_frame_locked() const {
        if (published_slot_ < 0) {
            return nullptr;
        }
        const FrameSlot& slot = slots_[static_cast<std::size_t>(published_slot_)];
        return slot.pixels.empty() ? nullptr : &slot;
    }

    void rebuild_display_luts_locked() {
        for (std::uint32_t bits = 0; bits <= std::numeric_limits<std::uint16_t>::max(); ++bits) {
            const ccl::half encoded(static_cast<std::uint16_t>(bits));
            const float source = ccl::half_to_float(encoded);
            float display = source;
            if (active_pass_ == CYCLES_BRIDGE_PASS_DEPTH) {
                display = std::isfinite(source)
                    ? 1.0F - std::exp(-std::max(0.0F, source) * 8.0F / depth_far_)
                    : 1.0F;
            } else if (active_pass_ == CYCLES_BRIDGE_PASS_NORMAL) {
                display = source * 0.5F + 0.5F;
            } else if (active_pass_ == CYCLES_BRIDGE_PASS_SAMPLE_COUNT) {
                display = source / static_cast<float>(target_samples_);
            } else if (active_pass_ != CYCLES_BRIDGE_PASS_ROUGHNESS) {
                display *= exposure_scale_;
                if (view_transform_ != 1U) {
                    display = frame_detail::linear_to_srgb(display);
                }
                if (!frame_detail::nearly_equal(gamma_, 1.0F, 1.0e-6)) {
                    display = std::pow(std::max(0.0F, display), 1.0F / gamma_);
                }
            }
            display_lut_[bits] = frame_detail::to_unorm(display);
            alpha_lut_[bits] = frame_detail::to_unorm(source);
        }
    }

    void write_compatibility_pixel(std::uint8_t* output, const ccl::half4& pixel) const {
        output[0] = display_lut_[static_cast<std::uint16_t>(pixel.x)];
        output[1] = display_lut_[static_cast<std::uint16_t>(pixel.y)];
        output[2] = display_lut_[static_cast<std::uint16_t>(pixel.z)];
        output[3] = active_pass_ == CYCLES_BRIDGE_PASS_COMBINED
            ? alpha_lut_[static_cast<std::uint16_t>(pixel.w)]
            : 255U;
    }

    mutable std::mutex mutex_;
    std::uint32_t width_ = 0;
    std::uint32_t height_ = 0;
    std::uint64_t generation_ = 0;
    int sample_count_ = 0;
    std::uint32_t active_pass_ = CYCLES_BRIDGE_PASS_COMBINED;
    std::uint32_t active_variant_ = CYCLES_BRIDGE_FRAME_VARIANT_RAW;
    float exposure_scale_ = 1.0F;
    float gamma_ = 1.0F;
    std::uint32_t view_transform_ = 0;
    float depth_far_ = 1.0F;
    int target_samples_ = 1;
    std::array<FrameSlot, kFrameSlotCount> slots_{};
    std::vector<CachedPassFrame> pass_cache_;
    std::uint64_t pass_cache_bytes_ = 0U;
    std::uint64_t pass_cache_budget_bytes_ = 256ULL * 1024ULL * 1024ULL;
    std::uint64_t pass_cache_access_sequence_ = 0U;
    std::uint32_t pass_cache_eviction_count_ = 0U;
    std::uint32_t pass_cache_hit_count_ = 0U;
    std::array<std::uint8_t, 65536> display_lut_{};
    std::array<std::uint8_t, 65536> alpha_lut_{};
    bool display_lut_ready_ = false;
    bool display_update_active_ = false;
    bool zero_next_display_ = true;
    int published_slot_ = -1;
    int writing_slot_ = -1;
    std::uint32_t active_frame_leases_ = 0;
    std::uint32_t peak_frame_leases_ = 0;
    std::uint32_t dropped_display_updates_ = 0;
    std::chrono::steady_clock::time_point display_update_started_{};
    std::uint64_t produced_frame_count_ = 0;
    std::uint64_t active_camera_revision_ = 0;
    std::uint64_t produced_camera_revision_ = 0;
    std::uint64_t copied_frame_count_ = 0;
    std::uint64_t copied_byte_count_ = 0;
    std::uint64_t unchanged_poll_count_ = 0;
    std::uint32_t last_convert_micros_ = 0;
    std::uint32_t ema_convert_micros_ = 0;
    std::uint32_t max_convert_micros_ = 0;
    std::uint32_t last_copy_micros_ = 0;
    std::uint32_t ema_copy_micros_ = 0;
    std::uint32_t max_copy_micros_ = 0;
    std::chrono::steady_clock::time_point last_frame_completed_{};
};

class FrameDisplayDriver final : public ccl::DisplayDriver {
 public:
    FrameDisplayDriver(
        FrameStore& frames,
        std::condition_variable& worker_changed)
        : frames_(frames), worker_changed_(worker_changed) {}

    void next_tile_begin() override {}

    bool update_begin(
        const Params& params,
        int texture_width,
        int texture_height) override {
        return frames_.display_update_begin(params, texture_width, texture_height);
    }

    void update_end() override {
        frames_.display_update_end();
        worker_changed_.notify_all();
    }

    ccl::half4* map_texture_buffer() override {
        return frames_.display_buffer();
    }

    void unmap_texture_buffer() override {}

    void zero() override {
        frames_.zero_display();
    }

    void draw(const Params&) override {}

 private:
    FrameStore& frames_;
    std::condition_variable& worker_changed_;
};

}  // namespace cyclesrenderer

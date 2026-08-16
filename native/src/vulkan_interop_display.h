#pragma once

#include "cycles_bridge.h"
#include "frame_store.h"

#include <Windows.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <limits>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <string>
#include <utility>

#include "session/display_driver.h"

namespace cyclesrenderer {

namespace vulkan_interop_detail {

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

}  // namespace vulkan_interop_detail

struct VulkanInteropSnapshot {
    HANDLE memory_handle = nullptr;
    HANDLE ready_semaphore_handle = nullptr;
    HANDLE release_semaphore_handle = nullptr;
    CyclesBridgeVulkanInteropBuffer descriptor{};

    VulkanInteropSnapshot() = default;
    ~VulkanInteropSnapshot() {
        if (memory_handle != nullptr) {
            CloseHandle(memory_handle);
        }
        if (ready_semaphore_handle != nullptr) {
            CloseHandle(ready_semaphore_handle);
        }
        if (release_semaphore_handle != nullptr) {
            CloseHandle(release_semaphore_handle);
        }
    }
    VulkanInteropSnapshot(const VulkanInteropSnapshot&) = delete;
    VulkanInteropSnapshot& operator=(const VulkanInteropSnapshot&) = delete;
    VulkanInteropSnapshot(VulkanInteropSnapshot&& other) noexcept
        : memory_handle(std::exchange(other.memory_handle, nullptr)),
          ready_semaphore_handle(
              std::exchange(other.ready_semaphore_handle, nullptr)),
          release_semaphore_handle(
              std::exchange(other.release_semaphore_handle, nullptr)),
          descriptor(other.descriptor) {}
    VulkanInteropSnapshot& operator=(VulkanInteropSnapshot&& other) noexcept {
        if (this != &other) {
            if (memory_handle != nullptr) {
                CloseHandle(memory_handle);
            }
            if (ready_semaphore_handle != nullptr) {
                CloseHandle(ready_semaphore_handle);
            }
            if (release_semaphore_handle != nullptr) {
                CloseHandle(release_semaphore_handle);
            }
            memory_handle = std::exchange(other.memory_handle, nullptr);
            ready_semaphore_handle =
                std::exchange(other.ready_semaphore_handle, nullptr);
            release_semaphore_handle =
                std::exchange(other.release_semaphore_handle, nullptr);
            descriptor = other.descriptor;
        }
        return *this;
    }

    static VulkanInteropSnapshot duplicate(
        HANDLE memory_handle,
        HANDLE ready_semaphore_handle,
        HANDLE release_semaphore_handle,
        const CyclesBridgeVulkanInteropBuffer& descriptor) {
        VulkanInteropSnapshot snapshot;
        snapshot.descriptor = descriptor;
        snapshot.memory_handle = duplicate_handle(memory_handle, "memory");
        snapshot.ready_semaphore_handle = duplicate_handle(
            ready_semaphore_handle, "ready semaphore");
        snapshot.release_semaphore_handle = duplicate_handle(
            release_semaphore_handle, "release semaphore");
        return snapshot;
    }

 private:
    static HANDLE duplicate_handle(HANDLE source, const char* label) {
        HANDLE duplicate = nullptr;
        if (source == nullptr
            || DuplicateHandle(
                GetCurrentProcess(),
                source,
                GetCurrentProcess(),
                &duplicate,
                0U,
                FALSE,
                DUPLICATE_SAME_ACCESS) == FALSE) {
            const DWORD error = GetLastError();
            throw std::runtime_error(
                std::string("failed to duplicate Vulkan interop ") + label
                + " handle (Win32 error " + std::to_string(error) + ")");
        }
        return duplicate;
    }
};

enum class VulkanInteropSlotOwner : std::uint8_t {
    FREE,
    WRITING,
    READY,
    ACQUIRED,
};

struct VulkanInteropSlot {
    VulkanInteropSlotOwner owner = VulkanInteropSlotOwner::FREE;
    std::uint64_t generation = 0U;
    std::uint32_t width = 0U;
    std::uint32_t height = 0U;
    std::uint32_t depth_width = 0U;
    std::uint32_t depth_height = 0U;
    std::uint32_t sample_count = 0U;
    std::uint64_t release_wait_value = 0U;
};

using VulkanInteropSlots = std::array<VulkanInteropSlot, 3>;

void refresh_vulkan_interop_slot_flags(
    CyclesBridgeVulkanInteropState& state,
    const VulkanInteropSlots& slots,
    std::uint32_t slot_count) {
    state.flags &= ~(
        CYCLES_BRIDGE_VULKAN_INTEROP_FRAME_READY
        | CYCLES_BRIDGE_VULKAN_INTEROP_FRAME_ACQUIRED);
    state.ready_slot_count = 0U;
    for (std::uint32_t index = 0; index < slot_count; ++index) {
        if (slots[index].owner == VulkanInteropSlotOwner::READY) {
            state.flags |= CYCLES_BRIDGE_VULKAN_INTEROP_FRAME_READY;
            state.ready_slot_count++;
        } else if (slots[index].owner == VulkanInteropSlotOwner::ACQUIRED) {
            state.flags |= CYCLES_BRIDGE_VULKAN_INTEROP_FRAME_ACQUIRED;
        }
    }
}

class VulkanInteropBinding final {
 public:
    void stop() {
        {
            std::lock_guard lock(mutex_);
            stopping_ = true;
        }
        changed_.notify_all();
    }

    bool bind(
        const CyclesBridgeVulkanInteropBuffer& descriptor,
        std::uint64_t memory_handle,
        std::uint64_t ready_semaphore_handle,
        std::uint64_t release_semaphore_handle,
        const std::optional<std::array<std::uint8_t, 16>>& selected_device_uuid,
        std::string& error) {
        if (!selected_device_uuid.has_value()) {
            error = "selected Cycles device has no CUDA UUID";
            return false;
        }
        if (std::memcmp(
                descriptor.device_uuid,
                selected_device_uuid->data(),
                selected_device_uuid->size()) != 0) {
            error = "Vulkan and Cycles device UUIDs do not match";
            return false;
        }
        std::lock_guard lock(mutex_);
        if (memory_handle_ != nullptr
            || ready_semaphore_handle_ != nullptr
            || release_semaphore_handle_ != nullptr
            || (state_.flags
                & (CYCLES_BRIDGE_VULKAN_INTEROP_BOUND
                   | CYCLES_BRIDGE_VULKAN_INTEROP_ACTIVE
                   | CYCLES_BRIDGE_VULKAN_INTEROP_SESSION_ATTACHED)) != 0U) {
            error = "Vulkan interop buffer is already bound";
            return false;
        }
        if (memory_handle_ != nullptr) {
            CloseHandle(memory_handle_);
        }
        memory_handle_ = reinterpret_cast<HANDLE>(
            static_cast<std::uintptr_t>(memory_handle));
        ready_semaphore_handle_ = reinterpret_cast<HANDLE>(
            static_cast<std::uintptr_t>(ready_semaphore_handle));
        release_semaphore_handle_ = reinterpret_cast<HANDLE>(
            static_cast<std::uintptr_t>(release_semaphore_handle));
        descriptor_ = descriptor;
        descriptor_.memory_handle = 0U;
        descriptor_.ready_semaphore_handle = 0U;
        descriptor_.release_semaphore_handle = 0U;
        state_ = {};
        state_.struct_size = sizeof(state_);
        state_.struct_version = 1U;
        state_.flags = CYCLES_BRIDGE_VULKAN_INTEROP_BOUND
            | CYCLES_BRIDGE_VULKAN_INTEROP_TIMELINE_SYNC;
        state_.width = descriptor.width;
        state_.height = descriptor.height;
        state_.slot_count = descriptor.slot_count;
        slots_ = {};
        return true;
    }

    bool unbind(std::string& error) {
        std::lock_guard lock(mutex_);
        if ((state_.flags
             & (CYCLES_BRIDGE_VULKAN_INTEROP_ACTIVE
                | CYCLES_BRIDGE_VULKAN_INTEROP_SESSION_ATTACHED)) != 0U) {
            error = "Vulkan interop is active; destroy the renderer before releasing Vulkan memory";
            return false;
        }
        if (memory_handle_ != nullptr) {
            CloseHandle(memory_handle_);
            memory_handle_ = nullptr;
        }
        if (ready_semaphore_handle_ != nullptr) {
            CloseHandle(ready_semaphore_handle_);
            ready_semaphore_handle_ = nullptr;
        }
        if (release_semaphore_handle_ != nullptr) {
            CloseHandle(release_semaphore_handle_);
            release_semaphore_handle_ = nullptr;
        }
        descriptor_ = {};
        state_ = {};
        slots_ = {};
        return true;
    }

    void query_state(CyclesBridgeVulkanInteropState& state) const {
        std::lock_guard lock(mutex_);
        const std::uint32_t struct_size = state.struct_size;
        const std::uint32_t struct_version = state.struct_version;
        state = state_;
        state.struct_size = struct_size;
        state.struct_version = struct_version;
    }

    void acquire_frame(
        std::uint64_t previous_generation,
        CyclesBridgeVulkanInteropState& state) {
        bool released_stale_slots = false;
        {
            std::lock_guard lock(mutex_);
            VulkanInteropSlot* selected = nullptr;
            std::uint32_t selected_index = 0U;
            for (std::uint32_t index = 0; index < descriptor_.slot_count; ++index) {
                VulkanInteropSlot& slot = slots_[index];
                if (slot.owner == VulkanInteropSlotOwner::READY
                    && slot.generation > previous_generation
                    && (selected == nullptr || slot.generation > selected->generation)) {
                    selected = &slot;
                    selected_index = index;
                }
            }
            if (selected != nullptr) {
                for (std::uint32_t index = 0; index < descriptor_.slot_count; ++index) {
                    VulkanInteropSlot& slot = slots_[index];
                    if (&slot != selected
                        && slot.owner == VulkanInteropSlotOwner::READY
                        && slot.generation < selected->generation) {
                        const std::uint64_t release_wait_value = selected->generation;
                        slot = {};
                        slot.release_wait_value = release_wait_value;
                        released_stale_slots = true;
                    }
                }
                selected->owner = VulkanInteropSlotOwner::ACQUIRED;
                state_.width = selected->width;
                state_.height = selected->height;
                state_.depth_width = selected->depth_width;
                state_.depth_height = selected->depth_height;
                state_.sample_count = selected->sample_count;
                state_.generation = selected->generation;
                state_.slot_index = selected_index;
            }
            refresh_vulkan_interop_slot_flags(state_, slots_, descriptor_.slot_count);
            const std::uint32_t struct_size = state.struct_size;
            const std::uint32_t struct_version = state.struct_version;
            state = state_;
            state.struct_size = struct_size;
            state.struct_version = struct_version;
        }
        if (released_stale_slots) {
            changed_.notify_all();
        }
    }

    bool release_frame(std::uint64_t generation, std::string& error) {
        {
            std::lock_guard lock(mutex_);
            const auto acquired = std::find_if(
                slots_.begin(),
                slots_.begin() + descriptor_.slot_count,
                [generation](const auto& slot) {
                    return slot.owner == VulkanInteropSlotOwner::ACQUIRED
                        && slot.generation == generation;
                });
            if (acquired == slots_.begin() + descriptor_.slot_count) {
                error = "Vulkan interop frame token is not acquired";
                return false;
            }
            acquired->owner = VulkanInteropSlotOwner::FREE;
            acquired->release_wait_value = generation;
            acquired->generation = 0U;
            acquired->width = 0U;
            acquired->height = 0U;
            acquired->depth_width = 0U;
            acquired->depth_height = 0U;
            acquired->sample_count = 0U;
            refresh_vulkan_interop_slot_flags(state_, slots_, descriptor_.slot_count);
        }
        changed_.notify_all();
        return true;
    }

    VulkanInteropSnapshot snapshot(
        const std::optional<std::array<std::uint8_t, 16>>& device_uuid) const {
        std::lock_guard lock(mutex_);
        const bool compatible_device = device_uuid.has_value()
            && std::memcmp(
                descriptor_.device_uuid,
                device_uuid->data(),
                device_uuid->size()) == 0;
        if (memory_handle_ == nullptr || !compatible_device) {
            return {};
        }
        return VulkanInteropSnapshot::duplicate(
            memory_handle_,
            ready_semaphore_handle_,
            release_semaphore_handle_,
            descriptor_);
    }

    void mark_session_attached() {
        std::lock_guard lock(mutex_);
        state_.flags |= CYCLES_BRIDGE_VULKAN_INTEROP_SESSION_ATTACHED;
    }

    void set_configured_camera_revision(std::uint64_t revision) {
        std::lock_guard lock(mutex_);
        configured_camera_revision_ = revision;
    }

    [[nodiscard]] std::uint64_t produced_camera_revision(
        const FrameStore& fallback_frames) const {
        std::lock_guard lock(mutex_);
        if ((state_.flags & CYCLES_BRIDGE_VULKAN_INTEROP_ACTIVE) != 0U) {
            return produced_camera_revision_;
        }
        return fallback_frames.produced_camera_revision();
    }

    void set_sample_count(std::uint32_t sample_count) {
        std::lock_guard lock(mutex_);
        state_.sample_count = sample_count;
    }

    CyclesBridgeVulkanInteropState& display_state() { return state_; }
    VulkanInteropSlots& display_slots() { return slots_; }
    std::mutex& display_mutex() { return mutex_; }
    std::condition_variable& display_changed() { return changed_; }
    bool& display_stopping() { return stopping_; }
    std::uint64_t& display_configured_camera_revision() {
        return configured_camera_revision_;
    }
    std::uint64_t& display_produced_camera_revision() {
        return produced_camera_revision_;
    }

 private:
    mutable std::mutex mutex_;
    std::condition_variable changed_;
    bool stopping_ = false;
    HANDLE memory_handle_ = nullptr;
    HANDLE ready_semaphore_handle_ = nullptr;
    HANDLE release_semaphore_handle_ = nullptr;
    CyclesBridgeVulkanInteropBuffer descriptor_{};
    CyclesBridgeVulkanInteropState state_{};
    VulkanInteropSlots slots_{};
    std::uint64_t configured_camera_revision_ = 0;
    std::uint64_t produced_camera_revision_ = 0;
};

class VulkanInteropDisplayDriver final : public ccl::DisplayDriver {
 public:
    VulkanInteropDisplayDriver(
        VulkanInteropSnapshot&& snapshot,
        FrameStore& frames,
        CyclesBridgeVulkanInteropState& state,
        VulkanInteropSlots& slots,
        std::mutex& state_mutex,
        std::condition_variable& state_changed,
        std::condition_variable& worker_changed,
        bool& stopping,
        std::uint64_t& configured_camera_revision,
        std::uint64_t& produced_camera_revision,
        bool export_depth,
        float depth_resolution_divider)
        : snapshot_(std::move(snapshot)),
          frames_(frames),
          state_(state),
          slots_(slots),
          state_mutex_(state_mutex),
          state_changed_(state_changed),
          worker_changed_(worker_changed),
          stopping_(stopping),
          configured_camera_revision_(configured_camera_revision),
          produced_camera_revision_(produced_camera_revision),
          export_depth_(export_depth),
          depth_resolution_divider_(depth_resolution_divider) {}

    ~VulkanInteropDisplayDriver() override {
        std::unique_lock lock(state_mutex_);
        state_changed_.wait(lock, [this] {
            if (stopping_) {
                return true;
            }
            return std::none_of(slots_.begin(), slots_.end(), [](const auto& slot) {
                return slot.owner == VulkanInteropSlotOwner::ACQUIRED;
            });
        });
        for (VulkanInteropSlot& slot : slots_) {
            const std::uint64_t release_wait_value = slot.release_wait_value;
            slot = {};
            slot.release_wait_value = release_wait_value;
        }
        state_.flags &= ~(
            CYCLES_BRIDGE_VULKAN_INTEROP_ACTIVE
            | CYCLES_BRIDGE_VULKAN_INTEROP_FRAME_READY
            | CYCLES_BRIDGE_VULKAN_INTEROP_FAILED
            | CYCLES_BRIDGE_VULKAN_INTEROP_SESSION_ATTACHED
            | CYCLES_BRIDGE_VULKAN_INTEROP_FRAME_ACQUIRED);
        state_.ready_slot_count = 0U;
        state_.slot_index = 0U;
        lock.unlock();
        state_changed_.notify_all();
    }

    void next_tile_begin() override {}

    bool update_begin(
        const Params& params,
        int texture_width,
        int texture_height) override {
        if (params.full_size.x <= 0 || params.full_size.y <= 0
            || texture_width <= 0 || texture_height <= 0) {
            return false;
        }
        const std::uint64_t width = static_cast<std::uint32_t>(params.full_size.x);
        const std::uint64_t height = static_cast<std::uint32_t>(params.full_size.y);
        const std::uint64_t slot_bytes = snapshot_.descriptor.slot_stride_bytes;
        const bool valid_pixel_count =
            width <= std::numeric_limits<std::uint64_t>::max() / height;
        const std::uint64_t color_pixels = valid_pixel_count ? width * height : 0U;
        compatible_ = valid_pixel_count
            && color_pixels <= slot_bytes / sizeof(ccl::half4);
        current_width_ = static_cast<std::uint32_t>(width);
        current_height_ = static_cast<std::uint32_t>(height);
        current_depth_width_ = 0U;
        current_depth_height_ = 0U;
        if (compatible_ && export_depth_) {
            const std::uint32_t depth_width = std::max(
                1U,
                static_cast<std::uint32_t>(
                    static_cast<float>(current_width_)
                    / depth_resolution_divider_));
            const std::uint32_t depth_height = std::max(
                1U,
                static_cast<std::uint32_t>(
                    static_cast<float>(current_height_)
                    / depth_resolution_divider_));
            const std::uint64_t color_bytes =
                color_pixels * sizeof(ccl::half4);
            const std::uint64_t depth_pixels =
                static_cast<std::uint64_t>(depth_width) * depth_height;
            if (color_bytes <= slot_bytes
                && depth_pixels <= (slot_bytes - color_bytes) / sizeof(float)) {
                current_depth_width_ = depth_width;
                current_depth_height_ = depth_height;
            }
        }
        current_slot_ = -1;
        if (compatible_) {
            std::unique_lock lock(state_mutex_);
            const bool has_free_slot = [this] {
                return std::any_of(
                    slots_.begin(),
                    slots_.begin() + snapshot_.descriptor.slot_count,
                    [](const auto& slot) {
                        return slot.owner == VulkanInteropSlotOwner::FREE;
                    });
            }();
            if (!has_free_slot) {
                state_.producer_wait_count++;
            }
            state_changed_.wait(lock, [this] {
                return stopping_ || std::any_of(
                    slots_.begin(),
                    slots_.begin() + snapshot_.descriptor.slot_count,
                    [](const auto& slot) {
                        return slot.owner == VulkanInteropSlotOwner::FREE;
                    });
            });
            if (stopping_) {
                return false;
            }
            const auto free_slot = std::find_if(
                slots_.begin(),
                slots_.begin() + snapshot_.descriptor.slot_count,
                [](const auto& slot) {
                    return slot.owner == VulkanInteropSlotOwner::FREE;
                });
            current_slot_ = static_cast<int>(std::distance(slots_.begin(), free_slot));
            free_slot->owner = VulkanInteropSlotOwner::WRITING;
        }
        if (!frames_.display_update_begin(params, texture_width, texture_height)) {
            release_writing_slot();
            return false;
        }
        used_interop_ = false;
        update_started_ = std::chrono::steady_clock::now();
        return true;
    }

    void update_end() override {
        if (!used_interop_) {
            release_writing_slot();
            frames_.display_update_end();
            worker_changed_.notify_all();
            return;
        }
        frames_.display_update_cancel();
        const std::uint32_t elapsed = vulkan_interop_detail::elapsed_micros(
            update_started_, std::chrono::steady_clock::now());
        {
            std::lock_guard lock(state_mutex_);
            state_.flags |= CYCLES_BRIDGE_VULKAN_INTEROP_ACTIVE;
            state_.flags |= CYCLES_BRIDGE_VULKAN_INTEROP_FRAME_READY;
            state_.generation++;
            state_.completed_frame_count++;
            state_.width = current_width_;
            state_.height = current_height_;
            state_.depth_width = current_depth_width_;
            state_.depth_height = current_depth_height_;
            state_.last_sync_micros = elapsed;
            state_.ema_sync_micros = vulkan_interop_detail::update_ema(state_.ema_sync_micros, elapsed);
            state_.max_sync_micros = std::max(state_.max_sync_micros, elapsed);
            VulkanInteropSlot& slot = slots_[current_slot_];
            slot.owner = VulkanInteropSlotOwner::READY;
            slot.generation = state_.generation;
            slot.width = current_width_;
            slot.height = current_height_;
            slot.depth_width = current_depth_width_;
            slot.depth_height = current_depth_height_;
            slot.sample_count = state_.sample_count;
            state_.slot_index = static_cast<std::uint32_t>(current_slot_);
            state_.ready_slot_count = static_cast<std::uint32_t>(std::count_if(
                slots_.begin(),
                slots_.begin() + snapshot_.descriptor.slot_count,
                [](const auto& candidate) {
                    return candidate.owner == VulkanInteropSlotOwner::READY;
                }));
            refresh_vulkan_interop_slot_flags(
                state_, slots_, snapshot_.descriptor.slot_count);
            current_slot_ = -1;
            produced_camera_revision_ = configured_camera_revision_;
        }
        worker_changed_.notify_all();
    }

    ccl::half4* map_texture_buffer() override {
        used_interop_ = false;
        release_writing_slot();
        return frames_.display_buffer();
    }

    void unmap_texture_buffer() override {}

    ccl::GraphicsInteropDevice graphics_interop_get_device() override {
        ccl::GraphicsInteropDevice device;
        if (!compatible_) {
            return device;
        }
        device.type = ccl::GraphicsInteropDevice::VULKAN;
        device.uuid.assign(
            std::begin(snapshot_.descriptor.device_uuid),
            std::end(snapshot_.descriptor.device_uuid));
        return device;
    }

    void graphics_interop_update_buffer() override {
        if (snapshot_.memory_handle != nullptr
            && graphics_interop_buffer_.is_empty()) {
            const auto handle = static_cast<std::int64_t>(
                reinterpret_cast<std::intptr_t>(snapshot_.memory_handle));
            graphics_interop_buffer_.assign(
                ccl::GraphicsInteropDevice::VULKAN,
                handle,
                static_cast<std::size_t>(snapshot_.descriptor.allocation_byte_count));
            snapshot_.memory_handle = nullptr;
            graphics_interop_buffer_.assign_timeline_semaphores(
                static_cast<std::int64_t>(reinterpret_cast<std::intptr_t>(
                    snapshot_.ready_semaphore_handle)),
                static_cast<std::int64_t>(reinterpret_cast<std::intptr_t>(
                    snapshot_.release_semaphore_handle)));
            snapshot_.ready_semaphore_handle = nullptr;
            snapshot_.release_semaphore_handle = nullptr;
        }
        used_interop_ = !graphics_interop_buffer_.is_empty() && current_slot_ >= 0;
        if (used_interop_) {
            const std::size_t stride = snapshot_.descriptor.slot_stride_bytes;
            graphics_interop_buffer_.set_range(
                static_cast<std::size_t>(current_slot_) * stride,
                stride);
            graphics_interop_buffer_.set_timeline_values(
                state_.generation + 1U,
                slots_[current_slot_].release_wait_value);
        }
    }

    void zero() override {
        graphics_interop_buffer_.zero();
        frames_.zero_display();
    }

    void draw(const Params&) override {}

 private:
    void release_writing_slot() {
        if (current_slot_ < 0) {
            return;
        }
        {
            std::lock_guard lock(state_mutex_);
            VulkanInteropSlot& slot = slots_[current_slot_];
            if (slot.owner == VulkanInteropSlotOwner::WRITING) {
                const std::uint64_t release_wait_value = slot.release_wait_value;
                slot = {};
                slot.release_wait_value = release_wait_value;
            }
        }
        current_slot_ = -1;
        state_changed_.notify_all();
    }

    VulkanInteropSnapshot snapshot_;
    FrameStore& frames_;
    CyclesBridgeVulkanInteropState& state_;
    VulkanInteropSlots& slots_;
    std::mutex& state_mutex_;
    std::condition_variable& state_changed_;
    std::condition_variable& worker_changed_;
    bool& stopping_;
    std::uint64_t& configured_camera_revision_;
    std::uint64_t& produced_camera_revision_;
    bool export_depth_ = false;
    float depth_resolution_divider_ = 1.0F;
    std::chrono::steady_clock::time_point update_started_{};
    bool compatible_ = false;
    bool used_interop_ = false;
    std::uint32_t current_width_ = 0U;
    std::uint32_t current_height_ = 0U;
    std::uint32_t current_depth_width_ = 0U;
    std::uint32_t current_depth_height_ = 0U;
    int current_slot_ = -1;
};

}  // namespace cyclesrenderer

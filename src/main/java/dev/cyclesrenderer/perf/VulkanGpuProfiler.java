package dev.cyclesrenderer.perf;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Records queue timestamps without submitting or waiting for the GPU. */
final class VulkanGpuProfiler implements AutoCloseable {
    interface ResultSink {
        void accept(long frameId, PerformanceSample.GpuStage stage, long elapsedNanos);

        void complete(long frameId);
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(VulkanGpuProfiler.class);
    private static final int ROTATIONS = 32;
    private static final int MARKER_COUNT = PerformanceSample.GpuMarker.values().length;

    private final ResultSink resultSink;
    private final boolean configuredEnabled;
    private final Slot[] slots = new Slot[ROTATIONS];
    private GpuDevice device;
    private GpuQueryPool queryPool;
    private float timestampPeriod;
    private int nextSlot;
    private int pollSlot;
    private int activeSlot = -1;
    private long droppedFrames;
    private boolean disabled;

    VulkanGpuProfiler(ResultSink resultSink, boolean configuredEnabled) {
        this.resultSink = resultSink;
        this.configuredEnabled = configuredEnabled;
        for (int index = 0; index < slots.length; index++) {
            slots[index] = new Slot();
        }
    }

    boolean beginFrame(long frameId) {
        RenderSystem.assertOnRenderThread();
        activeSlot = -1;
        if (!ensureReady()) {
            return false;
        }

        pollAvailable(4);
        int candidate = nextSlot;
        nextSlot = (nextSlot + 1) % ROTATIONS;
        if (slots[candidate].pending) {
            poll(candidate);
        }
        if (slots[candidate].pending) {
            candidate = findFreeSlot();
        }
        if (candidate < 0) {
            droppedFrames++;
            return false;
        }

        Slot slot = slots[candidate];
        slot.frameId = frameId;
        slot.markerMask = 0;
        slot.pending = false;
        activeSlot = candidate;
        write(PerformanceSample.GpuMarker.FRAME_BEGIN);
        return activeSlot >= 0;
    }

    void write(PerformanceSample.GpuMarker marker) {
        if (activeSlot < 0 || queryPool == null) {
            return;
        }
        Slot slot = slots[activeSlot];
        int bit = 1 << marker.ordinal();
        if ((slot.markerMask & bit) != 0) {
            return;
        }
        try {
            CommandEncoder encoder = device.createCommandEncoder();
            encoder.writeTimestamp(queryPool, queryIndex(activeSlot, marker));
            slot.markerMask |= bit;
        } catch (RuntimeException | LinkageError error) {
            disable("Vulkan timestamp recording failed", error);
        }
    }

    void endFrame() {
        if (activeSlot < 0) {
            return;
        }
        write(PerformanceSample.GpuMarker.FRAME_END);
        if (activeSlot >= 0) {
            slots[activeSlot].pending = true;
        }
        activeSlot = -1;
    }

    long droppedFrames() {
        return droppedFrames;
    }

    void pollAvailable(int budget) {
        if (queryPool == null || budget <= 0) {
            return;
        }
        for (int scanned = 0, polled = 0; scanned < ROTATIONS && polled < budget; scanned++) {
            int candidate = pollSlot;
            pollSlot = (pollSlot + 1) % ROTATIONS;
            if (slots[candidate].pending) {
                poll(candidate);
                polled++;
            }
        }
    }

    private boolean ensureReady() {
        if (!configuredEnabled || disabled) {
            return false;
        }
        GpuDevice current = RenderSystem.tryGetDevice();
        if (current == null) {
            return false;
        }
        if (queryPool != null && device == current) {
            return true;
        }
        closePool();
        try {
            device = current;
            timestampPeriod = current.getDeviceInfo().timestampPeriod();
            if (!Float.isFinite(timestampPeriod) || timestampPeriod <= 0.0F) {
                disabled = true;
                LOGGER.warn("GPU performance tracing disabled: invalid timestamp period {}", timestampPeriod);
                return false;
            }
            queryPool = current.createTimestampQueryPool(ROTATIONS * MARKER_COUNT);
            return true;
        } catch (RuntimeException | LinkageError error) {
            disable("GPU performance tracing could not create a timestamp pool", error);
            return false;
        }
    }

    private void poll(int slotIndex) {
        Slot slot = slots[slotIndex];
        if (!slot.pending || queryPool == null) {
            return;
        }
        try {
            OptionalLong[] values = queryPool.getValues(slotIndex * MARKER_COUNT, MARKER_COUNT);
            if (!allWrittenValuesAvailable(slot, values)) {
                return;
            }
            emit(slot, values, PerformanceSample.GpuStage.VULKAN_RENDER,
                    PerformanceSample.GpuMarker.FRAME_BEGIN,
                    PerformanceSample.GpuMarker.FRAME_END);
            emit(slot, values, PerformanceSample.GpuStage.CYCLES_WINDOW,
                    PerformanceSample.GpuMarker.CYCLES_BEGIN,
                    PerformanceSample.GpuMarker.CYCLES_END);
            emit(slot, values, PerformanceSample.GpuStage.INTEROP_WINDOW,
                    PerformanceSample.GpuMarker.INTEROP_BEGIN,
                    PerformanceSample.GpuMarker.INTEROP_END);
            emit(slot, values, PerformanceSample.GpuStage.DISPLAY_PASS,
                    PerformanceSample.GpuMarker.DISPLAY_BEGIN,
                    PerformanceSample.GpuMarker.DISPLAY_END);
            emit(slot, values, PerformanceSample.GpuStage.REPROJECTION,
                    PerformanceSample.GpuMarker.REPROJECTION_BEGIN,
                    PerformanceSample.GpuMarker.REPROJECTION_END);
            resultSink.complete(slot.frameId);
            slot.pending = false;
            slot.markerMask = 0;
        } catch (RuntimeException | LinkageError error) {
            disable("Vulkan timestamp result polling failed", error);
        }
    }

    private boolean allWrittenValuesAvailable(Slot slot, OptionalLong[] values) {
        for (PerformanceSample.GpuMarker marker : PerformanceSample.GpuMarker.values()) {
            if (hasMarker(slot, marker) && values[marker.ordinal()].isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private void emit(
            Slot slot,
            OptionalLong[] values,
            PerformanceSample.GpuStage stage,
            PerformanceSample.GpuMarker begin,
            PerformanceSample.GpuMarker end) {
        if (!hasMarker(slot, begin) || !hasMarker(slot, end)) {
            return;
        }
        long ticks = values[end.ordinal()].getAsLong() - values[begin.ordinal()].getAsLong();
        long nanos = ticks <= 0L ? 0L : (long) (ticks * (double) timestampPeriod);
        resultSink.accept(slot.frameId, stage, nanos);
    }

    private int findFreeSlot() {
        for (int offset = 0; offset < ROTATIONS; offset++) {
            int candidate = (nextSlot + offset) % ROTATIONS;
            if (!slots[candidate].pending) {
                nextSlot = (candidate + 1) % ROTATIONS;
                return candidate;
            }
        }
        return -1;
    }

    private static boolean hasMarker(Slot slot, PerformanceSample.GpuMarker marker) {
        return (slot.markerMask & (1 << marker.ordinal())) != 0;
    }

    private static int queryIndex(int slot, PerformanceSample.GpuMarker marker) {
        return slot * MARKER_COUNT + marker.ordinal();
    }

    private void disable(String message, Throwable error) {
        LOGGER.warn(message + "; CPU tracing remains active", error);
        disabled = true;
        activeSlot = -1;
        closePool();
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        activeSlot = -1;
        closePool();
    }

    private void closePool() {
        if (queryPool != null) {
            queryPool.close();
            queryPool = null;
        }
        device = null;
        for (Slot slot : slots) {
            slot.pending = false;
            slot.markerMask = 0;
        }
    }

    private static final class Slot {
        private long frameId;
        private int markerMask;
        private boolean pending;
    }
}

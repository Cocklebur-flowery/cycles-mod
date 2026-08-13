package dev.cyclesrenderer.perf;

import dev.cyclesrenderer.nativebridge.NativeBridge;

import java.util.Arrays;

public final class PerformanceSample {
    static final long UNAVAILABLE = -1L;

    public enum CpuStage {
        ENGINE_FRAME("engine_frame"),
        ENGINE_OUTSIDE_RENDER("engine_outside_render"),
        RENDER_EVENT("render_event"),
        RENDER_OUTSIDE_CYCLES("render_outside_cycles"),
        SUBMIT_PRESENT("submit_present"),
        CYCLES_CALLBACK("cycles_callback"),
        SCENE_UPDATE("scene_update"),
        CAMERA_FFI("camera_ffi"),
        INTEROP_POLL("interop_poll"),
        FRAME_ACQUIRE("frame_acquire"),
        FRAME_UPLOAD("frame_upload"),
        DISPLAY_SUBMIT("display_submit"),
        DISPLAY_COLOR_LUT("display_color_lut"),
        DISPLAY_UNIFORMS("display_uniforms"),
        DISPLAY_RENDER_PASS("display_render_pass"),
        GPU_MARKER_FRAME("gpu_query_frame"),
        GPU_MARKER_CYCLES("gpu_marker_cycles"),
        GPU_MARKER_INTEROP("gpu_marker_interop"),
        GPU_MARKER_DISPLAY("gpu_marker_display"),
        DIAGNOSTICS("diagnostics");

        private final String jsonName;

        CpuStage(String jsonName) {
            this.jsonName = jsonName;
        }

        String jsonName() {
            return jsonName;
        }
    }

    enum GpuStage {
        VULKAN_RENDER("vulkan_render"),
        CYCLES_WINDOW("cycles_window"),
        INTEROP_WINDOW("interop_window"),
        DISPLAY_PASS("display_pass");

        private final String jsonName;

        GpuStage(String jsonName) {
            this.jsonName = jsonName;
        }

        String jsonName() {
            return jsonName;
        }
    }

    public enum GpuMarker {
        FRAME_BEGIN,
        CYCLES_BEGIN,
        INTEROP_BEGIN,
        INTEROP_END,
        DISPLAY_BEGIN,
        DISPLAY_END,
        CYCLES_END,
        FRAME_END
    }

    private long frameId = -1L;
    private long frameStartNanos;
    private long flipIntervalNanos = UNAVAILABLE;
    private long adaptiveBaselineNanos = UNAVAILABLE;
    private long flipBaselineNanos = UNAVAILABLE;
    private long gcCountDelta;
    private long gcTimeMillisDelta;
    private long heapUsedBytes;
    private long clientTickId = -1L;
    private long clientTickNanos = UNAVAILABLE;
    private int triggerCode;
    private boolean gpuExpected;
    private boolean gpuComplete;
    private long contextFrameId = -1L;
    private Context context = Context.empty();
    private final long[] cpuNanos = new long[CpuStage.values().length];
    private final long[] gpuNanos = new long[GpuStage.values().length];

    PerformanceSample() {
        Arrays.fill(cpuNanos, UNAVAILABLE);
        Arrays.fill(gpuNanos, UNAVAILABLE);
    }

    void reset(long nextFrameId, long startNanos) {
        frameId = nextFrameId;
        frameStartNanos = startNanos;
        flipIntervalNanos = UNAVAILABLE;
        adaptiveBaselineNanos = UNAVAILABLE;
        flipBaselineNanos = UNAVAILABLE;
        gcCountDelta = 0L;
        gcTimeMillisDelta = 0L;
        heapUsedBytes = 0L;
        clientTickId = -1L;
        clientTickNanos = UNAVAILABLE;
        triggerCode = 0;
        gpuExpected = false;
        gpuComplete = false;
        contextFrameId = -1L;
        context = Context.empty();
        Arrays.fill(cpuNanos, UNAVAILABLE);
        Arrays.fill(gpuNanos, UNAVAILABLE);
    }

    long frameId() {
        return frameId;
    }

    void setCpu(CpuStage stage, long nanos) {
        cpuNanos[stage.ordinal()] = Math.max(0L, nanos);
    }

    void addCpu(CpuStage stage, long nanos) {
        long previous = cpuNanos[stage.ordinal()];
        cpuNanos[stage.ordinal()] = Math.max(0L, nanos)
                + (previous == UNAVAILABLE ? 0L : previous);
    }

    void setGpu(GpuStage stage, long nanos) {
        gpuNanos[stage.ordinal()] = Math.max(0L, nanos);
    }

    void setGpuExpected(boolean expected) {
        gpuExpected = expected;
        gpuComplete = !expected;
    }

    void markGpuComplete() {
        gpuComplete = true;
    }

    boolean gpuPending() {
        return gpuExpected && !gpuComplete;
    }

    long cpu(CpuStage stage) {
        return cpuNanos[stage.ordinal()];
    }

    long flipIntervalNanos() {
        return flipIntervalNanos;
    }

    long clientTickNanos() {
        return clientTickNanos;
    }

    void setFlipIntervalNanos(long nanos) {
        flipIntervalNanos = Math.max(0L, nanos);
    }

    void setAdaptiveBaselineNanos(long nanos) {
        adaptiveBaselineNanos = nanos;
    }

    void setFlipBaselineNanos(long nanos) {
        flipBaselineNanos = nanos;
    }

    void setTriggerCode(int code) {
        triggerCode = code;
    }

    void setJvm(long countDelta, long timeMillisDelta, long usedBytes) {
        gcCountDelta = Math.max(0L, countDelta);
        gcTimeMillisDelta = Math.max(0L, timeMillisDelta);
        heapUsedBytes = Math.max(0L, usedBytes);
    }

    void setClientTick(long tickId, long nanos) {
        clientTickId = tickId;
        clientTickNanos = Math.max(0L, nanos);
    }

    void setContext(long sampledAtFrameId, Context sampledContext) {
        contextFrameId = sampledAtFrameId;
        context = sampledContext;
    }

    void deriveCpuRemainders() {
        long render = cpu(CpuStage.RENDER_EVENT);
        long cycles = cpu(CpuStage.CYCLES_CALLBACK);
        if (render != UNAVAILABLE && cycles != UNAVAILABLE) {
            setCpu(CpuStage.RENDER_OUTSIDE_CYCLES, Math.max(0L, render - cycles));
        }
    }

    Snapshot snapshot() {
        return new Snapshot(
                frameId,
                frameStartNanos,
                flipIntervalNanos,
                adaptiveBaselineNanos,
                flipBaselineNanos,
                gcCountDelta,
                gcTimeMillisDelta,
                heapUsedBytes,
                clientTickId,
                clientTickNanos,
                triggerCode,
                gpuExpected,
                gpuComplete,
                contextFrameId,
                cpuNanos.clone(),
                gpuNanos.clone(),
                context);
    }

    record Snapshot(
            long frameId,
            long frameStartNanos,
            long flipIntervalNanos,
            long adaptiveBaselineNanos,
            long flipBaselineNanos,
            long gcCountDelta,
            long gcTimeMillisDelta,
            long heapUsedBytes,
            long clientTickId,
            long clientTickNanos,
            int triggerCode,
            boolean gpuExpected,
            boolean gpuComplete,
            long contextFrameId,
            long[] cpuNanos,
            long[] gpuNanos,
            Context context) {
    }

    record Context(
            long captureCount,
            long captureReplacedCount,
            int capturePending,
            long captureLastMicros,
            long sceneUpdateCount,
            long sceneLastUpdateMicros,
            long sceneLastUpsertMicros,
            long sceneLastRemoveMicros,
            long sceneLastCommitMicros,
            int sceneAcceptedSections,
            boolean sceneCommitPending,
            long uploadCount,
            long uploadGenerationGaps,
            long uploadLastMicros,
            long interopCopyCount,
            boolean interopCopyPending,
            long interopPendingGeneration,
            long interopDisplayedGeneration,
            long interopLastCopyMicros,
            boolean nativeAvailable,
            long settingsRevision,
            long sceneRevision,
            long cameraRevision,
            long frameGeneration,
            int nativeState,
            int sampleCount,
            int targetSampleCount,
            int sectionCount,
            int resetLevel,
            long producedFrameCount,
            long copiedFrameCount,
            int droppedDisplayUpdates,
            long sceneCommitCount,
            long sceneDeltaCount,
            long renderStartCount,
            long sceneTimingRevision,
            long sceneTimingCount,
            int lastSceneCommitMicros,
            int lastSceneDeltaMicros,
            int lastRenderStartMicros,
            int lastSceneQueueMicros,
            int lastResetWaitMicros,
            int lastDeviceUpdateMicros,
            int lastGeometryUpdateMicros,
            int lastBvhUpdateMicros,
            int lastSceneFirstFrameMicros,
            int activeDevicePhase,
            int activeDevicePhaseMicros,
            int[] lastDevicePhaseMicros,
            int[] emaDevicePhaseMicros,
            int[] maxDevicePhaseMicros) {

        Context {
            lastDevicePhaseMicros = lastDevicePhaseMicros.clone();
            emaDevicePhaseMicros = emaDevicePhaseMicros.clone();
            maxDevicePhaseMicros = maxDevicePhaseMicros.clone();
        }

        private static final Context EMPTY = new Context(
                0L, 0L, 0, 0L,
                0L, 0L, 0L, 0L, 0L, 0, false,
                0L, 0L, 0L,
                0L, false, 0L, 0L, 0L,
                false, 0L, 0L, 0L, 0L,
                0, 0, 0, 0, 0,
                0L, 0L, 0,
                0L, 0L, 0L,
                0L, 0L,
                0, 0, 0, 0, 0, 0, 0, 0, 0,
                NativeBridge.DEVICE_UPDATE_PHASE_COUNT, 0,
                new int[NativeBridge.DEVICE_UPDATE_PHASE_COUNT],
                new int[NativeBridge.DEVICE_UPDATE_PHASE_COUNT],
                new int[NativeBridge.DEVICE_UPDATE_PHASE_COUNT]);

        private static Context empty() {
            return EMPTY;
        }
    }
}

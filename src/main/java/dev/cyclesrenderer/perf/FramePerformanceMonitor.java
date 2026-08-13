package dev.cyclesrenderer.perf;

import dev.cyclesrenderer.render.CyclesFramePresenter;
import dev.cyclesrenderer.render.VulkanExternalBufferPrototype;
import dev.cyclesrenderer.scene.SectionSceneManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Low-overhead realtime frame tracing with pre-trigger history. */
public final class FramePerformanceMonitor implements DisplayPerformanceProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger(FramePerformanceMonitor.class);
    private static final int RING_SIZE = 512;
    private static final int BASELINE_SIZE = 64;
    private static final int PRE_TRIGGER_FRAMES = 120;
    private static final int POST_TRIGGER_FRAMES = 30;
    private static final int MAX_CAPTURE_FRAMES = 300;
    private static final int CONTEXT_INTERVAL_FRAMES = 15;
    private static final int GPU_RESULT_GRACE_FRAMES = 64;
    private static final long ABSOLUTE_STALL_NANOS = 20_000_000L;
    private static final long MIN_ADAPTIVE_STALL_NANOS = 12_000_000L;
    private final PerformanceContextSampler contextSampler;
    private final JvmPerformanceSampler jvmSampler = new JvmPerformanceSampler();
    private final PerformanceSample[] ring = new PerformanceSample[RING_SIZE];
    private final long[] baseline = new long[BASELINE_SIZE];
    private final long[] baselineScratch = new long[BASELINE_SIZE];
    private final long[] flipBaseline = new long[BASELINE_SIZE];
    private final long[] flipBaselineScratch = new long[BASELINE_SIZE];
    private final VulkanGpuProfiler gpuProfiler;
    private final boolean gpuQueriesEnabled;

    private Minecraft minecraft;
    private PerformanceLogWriter logWriter;
    private PerformanceSample current;
    private PerformanceSample.Context latestContext;
    private long latestContextFrame = -1L;
    private long nextFrameId;
    private long renderEventStart;
    private long renderEventEnd;
    private long lastFlipNanos;
    private long cachedMedianNanos = MIN_ADAPTIVE_STALL_NANOS;
    private long cachedFlipMedianNanos = MIN_ADAPTIVE_STALL_NANOS;
    private int baselineCount;
    private int baselineCursor;
    private int flipBaselineCount;
    private int flipBaselineCursor;
    private long clientTickStartNanos;
    private long clientTickId;
    private long lastClientTickNanos = PerformanceSample.UNAVAILABLE;
    private CaptureWindow capture;
    private boolean active;

    public FramePerformanceMonitor(
            CyclesFramePresenter presenter,
            VulkanExternalBufferPrototype interop,
            SectionSceneManager sceneManager) {
        contextSampler = new PerformanceContextSampler(presenter, interop, sceneManager);
        gpuQueriesEnabled = configuredGpuQueriesEnabled();
        gpuProfiler = new VulkanGpuProfiler(new VulkanGpuProfiler.ResultSink() {
            @Override
            public void accept(
                    long frameId,
                    PerformanceSample.GpuStage stage,
                    long elapsedNanos) {
                acceptGpuResult(frameId, stage, elapsedNanos);
            }

            @Override
            public void complete(long frameId) {
                acceptGpuComplete(frameId);
            }
        }, gpuQueriesEnabled);
        for (int index = 0; index < ring.length; index++) {
            ring[index] = new PerformanceSample();
        }
    }

    public void activate(Minecraft client) {
        if (active) {
            return;
        }
        minecraft = client;
        logWriter = new PerformanceLogWriter(
                client.gameDirectory.toPath().resolve("logs"), gpuQueriesEnabled);
        nextFrameId = 0L;
        renderEventStart = 0L;
        renderEventEnd = 0L;
        lastFlipNanos = 0L;
        cachedMedianNanos = MIN_ADAPTIVE_STALL_NANOS;
        cachedFlipMedianNanos = MIN_ADAPTIVE_STALL_NANOS;
        baselineCount = 0;
        baselineCursor = 0;
        flipBaselineCount = 0;
        flipBaselineCursor = 0;
        jvmSampler.reset();
        clientTickStartNanos = 0L;
        clientTickId = -1L;
        lastClientTickNanos = PerformanceSample.UNAVAILABLE;
        capture = null;
        current = null;
        latestContext = null;
        latestContextFrame = -1L;
        active = true;
        LOGGER.info(
                "Cycles performance tracing active (GPU queries={}); stalls are captured under {}",
                gpuQueriesEnabled,
                client.gameDirectory.toPath().resolve("logs"));
    }

    public void deactivate() {
        if (!active) {
            return;
        }
        gpuProfiler.pollAvailable(32);
        active = false;
        if (capture != null && current != null) {
            emitCapture(Math.min(capture.endFrame, current.frameId()));
        }
        gpuProfiler.close();
        if (logWriter != null) {
            logWriter.finish(false);
            logWriter = null;
        }
        capture = null;
        current = null;
        minecraft = null;
    }

    public void shutdown() {
        if (!active) {
            return;
        }
        gpuProfiler.pollAvailable(32);
        active = false;
        if (capture != null && current != null) {
            emitCapture(Math.min(capture.endFrame, current.frameId()));
        }
        gpuProfiler.close();
        if (logWriter != null) {
            logWriter.finish(true);
            logWriter = null;
        }
        capture = null;
        current = null;
        minecraft = null;
    }

    public void onRenderFramePre() {
        if (!active) {
            return;
        }
        if (current != null && renderEventEnd == 0L) {
            endGpuFrame();
        }
        long now = System.nanoTime();
        long frameId = nextFrameId++;
        current = ring[(int) (frameId % RING_SIZE)];
        current.reset(frameId, now);
        if (lastClientTickNanos != PerformanceSample.UNAVAILABLE) {
            current.setClientTick(clientTickId, lastClientTickNanos);
        }
        renderEventStart = now;
        renderEventEnd = 0L;
        long markerStart = System.nanoTime();
        current.setGpuExpected(gpuProfiler.beginFrame(frameId));
        current.addCpu(
                PerformanceSample.CpuStage.GPU_MARKER_FRAME,
                System.nanoTime() - markerStart);
    }

    public void onRenderFramePost() {
        if (!isRecording()) {
            return;
        }
        renderEventEnd = System.nanoTime();
        current.setCpu(
                PerformanceSample.CpuStage.RENDER_EVENT,
                renderEventEnd - renderEventStart);
        endGpuFrame();
    }

    public void onFlipFrame() {
        if (!isRecording()) {
            return;
        }
        long now = System.nanoTime();
        if (renderEventEnd != 0L) {
            current.setCpu(
                    PerformanceSample.CpuStage.SUBMIT_PRESENT,
                    now - renderEventEnd);
        }
        if (minecraft != null) {
            long engineFrameNanos = minecraft.getFrameTimeNs();
            current.setCpu(
                    PerformanceSample.CpuStage.ENGINE_FRAME,
                    engineFrameNanos);
            long renderNanos = available(
                    current.cpu(PerformanceSample.CpuStage.RENDER_EVENT));
            current.setCpu(
                    PerformanceSample.CpuStage.ENGINE_OUTSIDE_RENDER,
                    Math.max(0L, engineFrameNanos - renderNanos));
        }
        jvmSampler.sample(current);
        if (lastFlipNanos != 0L) {
            current.setFlipIntervalNanos(now - lastFlipNanos);
        }
        lastFlipNanos = now;
        current.deriveCpuRemainders();

        long observed = observedStallNanos(current);
        int triggerCode = classify(observed);
        boolean periodicContext = current.frameId() % CONTEXT_INTERVAL_FRAMES == 0L;
        if (periodicContext || triggerCode != 0 || latestContext == null) {
            sampleContext();
        }
        current.setContext(latestContextFrame, latestContext);
        if (triggerCode != 0) {
            current.setTriggerCode(triggerCode);
            beginOrExtendCapture(current.frameId());
        }
        updateBaseline(observed, triggerCode == 0);
        updateFlipBaseline(current.flipIntervalNanos(), triggerCode == 0);

        if (capture != null && current.frameId() >= capture.endFrame) {
            boolean timedOut = current.frameId() >= capture.endFrame + GPU_RESULT_GRACE_FRAMES;
            if (timedOut || gpuResultsReady(capture.startFrame, capture.endFrame)) {
                emitCapture(capture.endFrame);
            }
        }
    }

    public void onClientTickPre() {
        if (active) {
            clientTickStartNanos = System.nanoTime();
        }
    }

    public void onClientTickPost() {
        if (active && clientTickStartNanos != 0L) {
            lastClientTickNanos = System.nanoTime() - clientTickStartNanos;
            clientTickId++;
            clientTickStartNanos = 0L;
        }
    }

    public long beginCpuStage() {
        return isRecording() ? System.nanoTime() : 0L;
    }

    public void endCpuStage(PerformanceSample.CpuStage stage, long startedNanos) {
        if (startedNanos != 0L && isRecording()) {
            current.setCpu(stage, System.nanoTime() - startedNanos);
        }
    }

    public void gpuMarker(PerformanceSample.GpuMarker marker) {
        if (isRecording()) {
            long started = System.nanoTime();
            gpuProfiler.write(marker);
            current.addCpu(markerCpuStage(marker), System.nanoTime() - started);
        }
    }

    @Override
    public long beginDisplayStage() {
        return beginCpuStage();
    }

    @Override
    public void endDisplayStage(DisplayPerformanceProbe.Stage stage, long startedNanos) {
        PerformanceSample.CpuStage cpuStage = switch (stage) {
            case COLOR_LUT -> PerformanceSample.CpuStage.DISPLAY_COLOR_LUT;
            case UNIFORMS -> PerformanceSample.CpuStage.DISPLAY_UNIFORMS;
            case RENDER_PASS -> PerformanceSample.CpuStage.DISPLAY_RENDER_PASS;
        };
        endCpuStage(cpuStage, startedNanos);
    }

    private boolean isRecording() {
        return active && current != null;
    }

    private void endGpuFrame() {
        long started = System.nanoTime();
        gpuProfiler.endFrame();
        if (current != null) {
            current.addCpu(
                    PerformanceSample.CpuStage.GPU_MARKER_FRAME,
                    System.nanoTime() - started);
        }
    }

    private static PerformanceSample.CpuStage markerCpuStage(
            PerformanceSample.GpuMarker marker) {
        return switch (marker) {
            case FRAME_BEGIN, FRAME_END -> PerformanceSample.CpuStage.GPU_MARKER_FRAME;
            case CYCLES_BEGIN, CYCLES_END -> PerformanceSample.CpuStage.GPU_MARKER_CYCLES;
            case INTEROP_BEGIN, INTEROP_END -> PerformanceSample.CpuStage.GPU_MARKER_INTEROP;
            case DISPLAY_BEGIN, DISPLAY_END -> PerformanceSample.CpuStage.GPU_MARKER_DISPLAY;
        };
    }

    private int classify(long observedNanos) {
        long adaptive = Math.max(MIN_ADAPTIVE_STALL_NANOS, cachedMedianNanos * 2L);
        current.setAdaptiveBaselineNanos(cachedMedianNanos);
        int code = 0;
        if (observedNanos >= ABSOLUTE_STALL_NANOS) {
            code |= 1;
        }
        if (baselineCount >= 16 && observedNanos >= adaptive) {
            code |= 2;
        }
        long interval = current.flipIntervalNanos();
        long flipAdaptive = Math.max(MIN_ADAPTIVE_STALL_NANOS, cachedFlipMedianNanos * 2L);
        current.setFlipBaselineNanos(cachedFlipMedianNanos);
        if (flipBaselineCount >= 16
                && interval != PerformanceSample.UNAVAILABLE
                && interval >= flipAdaptive) {
            code |= 4;
        }
        return code;
    }

    private static long observedStallNanos(PerformanceSample sample) {
        long observed = 0L;
        observed = Math.max(observed, available(sample.cpu(PerformanceSample.CpuStage.ENGINE_FRAME)));
        observed = Math.max(observed, available(sample.cpu(PerformanceSample.CpuStage.RENDER_EVENT)));
        observed = Math.max(observed, available(sample.cpu(PerformanceSample.CpuStage.SUBMIT_PRESENT)));
        observed = Math.max(observed, available(sample.cpu(PerformanceSample.CpuStage.CYCLES_CALLBACK)));
        observed = Math.max(observed, available(sample.clientTickNanos()));
        return observed;
    }

    private static long available(long value) {
        return value == PerformanceSample.UNAVAILABLE ? 0L : value;
    }

    private void updateBaseline(long observedNanos, boolean ordinaryFrame) {
        if (!ordinaryFrame || observedNanos <= 0L) {
            return;
        }
        baseline[baselineCursor] = observedNanos;
        baselineCursor = (baselineCursor + 1) % BASELINE_SIZE;
        baselineCount = Math.min(BASELINE_SIZE, baselineCount + 1);
        if ((current.frameId() & 7L) == 0L || baselineCount == 16) {
            System.arraycopy(baseline, 0, baselineScratch, 0, baselineCount);
            Arrays.sort(baselineScratch, 0, baselineCount);
            cachedMedianNanos = baselineScratch[baselineCount / 2];
        }
    }

    private void updateFlipBaseline(long intervalNanos, boolean ordinaryFrame) {
        if (!ordinaryFrame || intervalNanos == PerformanceSample.UNAVAILABLE) {
            return;
        }
        flipBaseline[flipBaselineCursor] = intervalNanos;
        flipBaselineCursor = (flipBaselineCursor + 1) % BASELINE_SIZE;
        flipBaselineCount = Math.min(BASELINE_SIZE, flipBaselineCount + 1);
        if ((current.frameId() & 7L) == 0L || flipBaselineCount == 16) {
            System.arraycopy(flipBaseline, 0, flipBaselineScratch, 0, flipBaselineCount);
            Arrays.sort(flipBaselineScratch, 0, flipBaselineCount);
            cachedFlipMedianNanos = flipBaselineScratch[flipBaselineCount / 2];
        }
    }

    private void beginOrExtendCapture(long triggerFrame) {
        if (capture == null) {
            long start = Math.max(oldestAvailableFrame(), triggerFrame - PRE_TRIGGER_FRAMES);
            capture = new CaptureWindow(
                    start,
                    triggerFrame,
                    triggerFrame,
                    triggerFrame + POST_TRIGGER_FRAMES,
                    start + MAX_CAPTURE_FRAMES - 1L);
            return;
        }
        capture.lastTriggerFrame = triggerFrame;
        capture.endFrame = Math.min(
                capture.maximumEndFrame,
                Math.max(capture.endFrame, triggerFrame + POST_TRIGGER_FRAMES));
    }

    private void emitCapture(long endFrame) {
        if (capture == null || logWriter == null) {
            capture = null;
            return;
        }
        long startFrame = Math.max(capture.startFrame, oldestAvailableFrame());
        List<PerformanceSample.Snapshot> frames = new ArrayList<>((int) (endFrame - startFrame + 1L));
        for (long frameId = startFrame; frameId <= endFrame; frameId++) {
            PerformanceSample sample = ring[(int) (frameId % RING_SIZE)];
            if (sample.frameId() == frameId) {
                frames.add(sample.snapshot());
            }
        }
        logWriter.enqueue(new PerformanceLogWriter.Capture(
                capture.firstTriggerFrame,
                capture.lastTriggerFrame,
                frames,
                gpuProfiler.droppedFrames()));
        capture = null;
    }

    private long oldestAvailableFrame() {
        return Math.max(0L, nextFrameId - RING_SIZE);
    }

    private void acceptGpuResult(
            long frameId,
            PerformanceSample.GpuStage stage,
            long elapsedNanos) {
        PerformanceSample sample = ring[(int) (frameId % RING_SIZE)];
        if (sample.frameId() == frameId) {
            sample.setGpu(stage, elapsedNanos);
        }
    }

    private void acceptGpuComplete(long frameId) {
        PerformanceSample sample = ring[(int) (frameId % RING_SIZE)];
        if (sample.frameId() == frameId) {
            sample.markGpuComplete();
        }
    }

    private boolean gpuResultsReady(long startFrame, long endFrame) {
        for (long frameId = Math.max(startFrame, oldestAvailableFrame());
                frameId <= endFrame;
                frameId++) {
            PerformanceSample sample = ring[(int) (frameId % RING_SIZE)];
            if (sample.frameId() == frameId && sample.gpuPending()) {
                return false;
            }
        }
        return true;
    }

    private void sampleContext() {
        long start = System.nanoTime();
        latestContext = contextSampler.sample();
        latestContextFrame = current.frameId();
        current.setCpu(PerformanceSample.CpuStage.DIAGNOSTICS, System.nanoTime() - start);
    }

    private static boolean configuredGpuQueriesEnabled() {
        String property = System.getProperty("cyclesrenderer.performance.gpuQueries");
        if (property != null) {
            return Boolean.parseBoolean(property);
        }
        String environment = System.getenv("CYCLESRENDERER_PERF_GPU_QUERIES");
        return environment == null || Boolean.parseBoolean(environment);
    }

    private static final class CaptureWindow {
        private final long startFrame;
        private final long firstTriggerFrame;
        private final long maximumEndFrame;
        private long lastTriggerFrame;
        private long endFrame;

        private CaptureWindow(
                long startFrame,
                long firstTriggerFrame,
                long lastTriggerFrame,
                long endFrame,
                long maximumEndFrame) {
            this.startFrame = startFrame;
            this.firstTriggerFrame = firstTriggerFrame;
            this.lastTriggerFrame = lastTriggerFrame;
            this.endFrame = endFrame;
            this.maximumEndFrame = maximumEndFrame;
        }
    }
}

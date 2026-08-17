package dev.cyclesrenderer;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.cyclesrenderer.camera.AutofocusStage;
import dev.cyclesrenderer.client.CameraSafeAreaOverlay;
import dev.cyclesrenderer.config.CyclesClientConfig;
import dev.cyclesrenderer.config.CyclesRenderSettings;
import dev.cyclesrenderer.nativebridge.NativeBridge;
import dev.cyclesrenderer.perf.FramePerformanceMonitor;
import dev.cyclesrenderer.perf.PerformanceSample;
import dev.cyclesrenderer.render.CyclesFramePresenter;
import dev.cyclesrenderer.render.VulkanFrameInterop;
import dev.cyclesrenderer.scene.SectionGeometryCollector;
import dev.cyclesrenderer.scene.SectionSceneManager;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.FlipFrameEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.slf4j.Logger;

final class CyclesRendererController {
    private static final long FRAME_DELIVERY_INTERVAL_NANOS = 1_000_000_000L / 120L;
    private static final long STATS_LOG_INTERVAL_NANOS = 2_000_000_000L;

    private final Logger logger;
    private final SectionSceneManager sceneManager = new SectionSceneManager();
    private final CyclesFramePresenter framePresenter = new CyclesFramePresenter();
    private final AutofocusStage autofocus = new AutofocusStage();
    private final VulkanFrameInterop interopBuffer = new VulkanFrameInterop();
    private final FramePerformanceMonitor performanceMonitor =
            new FramePerformanceMonitor(framePresenter, interopBuffer, sceneManager);

    private boolean testFrameEnabled;
    private boolean nativeBridgeReady;
    private long nativeFrameId;
    private long lastStatsLogNanos;
    private long appliedSettingsRevision = -1L;
    private long rejectedSettingsRevision = -1L;
    private long lastWorkingSpaceRebuildRevision = -1L;
    private CyclesRenderSettings appliedSettings;
    private long bridgeCallCount;
    private long lastBridgeCallMicros;
    private long emaBridgeCallMicros;
    private long maxBridgeCallMicros;
    private long cameraCallCount;
    private long lastCameraCallMicros;
    private long emaCameraCallMicros;
    private long maxCameraCallMicros;
    private long skippedFrameDeliveryCount;
    private long lastFrameDeliveryNanos;
    private volatile long resourceRevision;

    CyclesRendererController(Logger logger) {
        this.logger = logger;
    }

    void onResourcesReloaded() {
        resourceRevision++;
    }

    void onClientTickPre() {
        performanceMonitor.onClientTickPre();
    }

    void onClientTickPost() {
        performanceMonitor.onClientTickPost();
    }

    void handleRendererToggle(KeyMapping toggleRenderer) {
        applySettingsIfNeeded();
        while (toggleRenderer.consumeClick()) {
            if (testFrameEnabled) {
                disableExperimentalRenderer();
                continue;
            }

            if (!ensureNativeBridgeReady()) {
                continue;
            }

            nativeFrameId = 0L;
            lastStatsLogNanos = 0L;
            bridgeCallCount = 0L;
            lastBridgeCallMicros = 0L;
            emaBridgeCallMicros = 0L;
            maxBridgeCallMicros = 0L;
            cameraCallCount = 0L;
            lastCameraCallMicros = 0L;
            emaCameraCallMicros = 0L;
            maxCameraCallMicros = 0L;
            skippedFrameDeliveryCount = 0L;
            lastFrameDeliveryNanos = 0L;
            sceneManager.reset();
            framePresenter.reset();
            autofocus.reset();
            interopBuffer.initialize(
                    Minecraft.getInstance(),
                    CyclesClientConfig.snapshot());
            SectionGeometryCollector.setEnabled(true);
            testFrameEnabled = true;
            performanceMonitor.activate(Minecraft.getInstance());
            logger.info("Experimental renderer enabled; building the streamed Cycles scene");
        }
    }

    boolean ensureNativeBridgeReady() {
        if (nativeBridgeReady && NativeBridge.isReady()) {
            applySettingsIfNeeded();
            return true;
        }
        NativeBridge.ProbeResult probe = NativeBridge.probe();
        if (!probe.success()) {
            logger.error("Native renderer bridge probe failed: {}", probe.message());
            return false;
        }
        nativeBridgeReady = true;
        appliedSettingsRevision = -1L;
        applySettingsIfNeeded();
        logger.info("Native renderer bridge ready: {}", probe.message());
        return true;
    }

    boolean isExperimentalRendererEnabled() {
        return testFrameEnabled;
    }

    boolean shouldReplaceVanillaWorld() {
        return testFrameEnabled
                && (interopBuffer.hasActiveFrame() || framePresenter.hasFrame());
    }

    void onRenderFramePre(RenderFrameEvent.Pre event) {
        performanceMonitor.onRenderFramePre();
    }

    void onRenderFramePost(RenderFrameEvent.Post event) {
        performanceMonitor.onRenderFramePost();
    }

    void onFlipFrame(FlipFrameEvent event) {
        performanceMonitor.onFlipFrame();
    }

    void onRenderLevelAfterLevel(RenderLevelStageEvent.AfterLevel event) {
        if (!testFrameEnabled) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        CameraRenderState camera = event.getLevelRenderState().cameraRenderState;
        if (level == null || !camera.initialized) {
            return;
        }

        var mainTarget = minecraft.gameRenderer.mainRenderTarget();
        long cyclesStart = performanceMonitor.beginCpuStage();
        performanceMonitor.gpuMarker(PerformanceSample.GpuMarker.CYCLES_BEGIN);
        try {
            CyclesRenderSettings renderSettings = activeRenderSettings();
            long sceneStart = performanceMonitor.beginCpuStage();
            SectionSceneManager.UpdateResult update = sceneManager.update(
                    minecraft,
                    level,
                    camera.pos,
                    resourceRevision,
                    renderSettings);
            performanceMonitor.endCpuStage(
                    PerformanceSample.CpuStage.SCENE_UPDATE, sceneStart);
            long frameId = nativeFrameId++;
            NativeBridge.CameraInput cameraInput = createCameraInput(
                    minecraft,
                    level,
                    camera,
                    renderSettings,
                    mainTarget.width,
                    mainTarget.height);
            long cameraStart = System.nanoTime();
            long cameraTraceStart = performanceMonitor.beginCpuStage();
            NativeBridge.updateCamera(
                    mainTarget.width,
                    mainTarget.height,
                    frameId,
                    cameraInput);
            recordCameraCall(System.nanoTime() - cameraStart);
            performanceMonitor.endCpuStage(
                    PerformanceSample.CpuStage.CAMERA_FFI, cameraTraceStart);

            if (pollAndPresentInteropFrame(mainTarget, renderSettings, cameraInput)) {
                return;
            }
            acquireAndPresentCpuFrame(mainTarget, renderSettings, cameraInput, update);
        } catch (RuntimeException error) {
            logger.error("Native frame rendering failed; restoring the vanilla renderer", error);
            disableExperimentalRenderer();
            NativeBridge.close();
            invalidateNativeBridgeState();
        } finally {
            performanceMonitor.gpuMarker(PerformanceSample.GpuMarker.CYCLES_END);
            performanceMonitor.endCpuStage(
                    PerformanceSample.CpuStage.CYCLES_CALLBACK, cyclesStart);
        }
    }

    void onChunkUnload(ChunkEvent.Unload event) {
        if (testFrameEnabled && event.getLevel() instanceof ClientLevel level) {
            sceneManager.onChunkUnload(level, event.getChunk().getPos());
        }
    }

    void onGameShuttingDown() {
        performanceMonitor.shutdown();
        interopBuffer.drainPendingCopy();
        sceneManager.close();
        if (interopBuffer.telemetry().nativeBound() && NativeBridge.isReady()) {
            NativeBridge.close();
            invalidateNativeBridgeState();
        }
        interopBuffer.close();
    }

    void onRenderGui(RenderGuiEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

        GuiGraphicsExtractor graphics = event.getGuiGraphics();
        CyclesRenderSettings settings = CyclesClientConfig.snapshot();
        if (shouldReplaceVanillaWorld()) {
            CameraSafeAreaOverlay.extract(graphics, settings);
        }
        if (testFrameEnabled) {
            Component status = interopBuffer.hasActiveFrame() || framePresenter.hasFrame()
                    ? Component.translatable("message.cyclesrenderer.test_frame")
                    : Component.translatable("message.cyclesrenderer.building_scene");
            graphics.centeredText(
                    minecraft.font,
                    status,
                    graphics.guiWidth() / 2,
                    12,
                    0xFFFFFFFF);
        }
        if (settings.debugOverlay()) {
            extractDebugOverlay(graphics, minecraft);
        }
    }

    private void applySettingsIfNeeded() {
        if (!nativeBridgeReady || !NativeBridge.isReady()) {
            return;
        }
        CyclesRenderSettings settings = CyclesClientConfig.snapshot();
        if (settings.revision() == appliedSettingsRevision
                || (appliedSettingsRevision >= 0L
                        && settings.revision() == rejectedSettingsRevision)) {
            return;
        }
        try {
            if (testFrameEnabled && interopBuffer.requiresLargerCapacity(settings)) {
                rebuildInteropForSettings(settings);
                return;
            }
            NativeBridge.applySettings(settings);
            recordAcceptedSettings(settings);
        } catch (RuntimeException error) {
            if (appliedSettings != null && appliedSettingsRevision >= 0L) {
                rejectedSettingsRevision = settings.revision();
                logger.error(
                        "Could not apply Cycles client settings revision {}; "
                                + "keeping accepted revision {}",
                        settings.revision(),
                        appliedSettings.revision(),
                        error);
                return;
            }
            logger.error("Could not apply initial Cycles client settings", error);
            if (testFrameEnabled) {
                disableExperimentalRenderer();
            }
            NativeBridge.close();
            invalidateNativeBridgeState();
        }
    }

    private void rebuildInteropForSettings(CyclesRenderSettings settings) {
        VulkanFrameInterop.Telemetry previous = interopBuffer.telemetry();
        logger.info(
                "Rebuilding Vulkan interop capacity from {}x{} for output {}x{}@{}%",
                previous.capacityWidth(),
                previous.capacityHeight(),
                settings.renderWidth(),
                settings.renderHeight(),
                settings.resolutionPercentage());

        interopBuffer.drainPendingCopy();
        sceneManager.reset();
        NativeBridge.close();
        invalidateNativeBridgeState();
        interopBuffer.close();

        NativeBridge.ProbeResult probe = NativeBridge.probe();
        if (!probe.success()) {
            throw new IllegalStateException(
                    "native renderer bridge probe failed after interop resize: "
                            + probe.message());
        }
        nativeBridgeReady = true;
        NativeBridge.applySettings(settings);
        recordAcceptedSettings(settings);
        interopBuffer.initialize(Minecraft.getInstance(), settings);
        sceneManager.reset();
        framePresenter.reset();
        autofocus.reset();
        nativeFrameId = 0L;
    }

    private NativeBridge.CameraInput createCameraInput(
            Minecraft minecraft,
            ClientLevel level,
            CameraRenderState camera,
            CyclesRenderSettings settings,
            int width,
            int height) {
        float projectionScaleY = camera.projectionMatrix.m11();
        if (!Float.isFinite(projectionScaleY) || projectionScaleY <= 0.0F) {
            throw new IllegalStateException("invalid camera projection scale: " + projectionScaleY);
        }
        float verticalFovRadians = 2.0F * (float) Math.atan(1.0F / projectionScaleY);
        float focusDistance = autofocus.update(
                minecraft,
                level,
                camera,
                settings,
                verticalFovRadians,
                (float) width / Math.max(height, 1),
                System.nanoTime());
        return new NativeBridge.CameraInput(
                camera.pos.x,
                camera.pos.y,
                camera.pos.z,
                camera.orientation.x(),
                camera.orientation.y(),
                camera.orientation.z(),
                camera.orientation.w(),
                verticalFovRadians,
                Math.max(camera.depthFar, 1.0F),
                focusDistance,
                NativeBridge.CAMERA_FOCUS_DISTANCE_VALID);
    }

    private boolean pollAndPresentInteropFrame(
            RenderTarget mainTarget,
            CyclesRenderSettings renderSettings,
            NativeBridge.CameraInput cameraInput) {
        long interopStart = performanceMonitor.beginCpuStage();
        performanceMonitor.gpuMarker(PerformanceSample.GpuMarker.INTEROP_BEGIN);
        interopBuffer.pollCompletedFrame();
        performanceMonitor.gpuMarker(PerformanceSample.GpuMarker.INTEROP_END);
        performanceMonitor.endCpuStage(
                PerformanceSample.CpuStage.INTEROP_POLL, interopStart);
        if (!interopBuffer.hasActiveFrame()) {
            return false;
        }

        long displayStart = performanceMonitor.beginCpuStage();
        performanceMonitor.gpuMarker(PerformanceSample.GpuMarker.DISPLAY_BEGIN);
        if (interopBuffer.hasDepthFrame()) {
            framePresenter.presentExternal(
                    mainTarget,
                    renderSettings,
                    cameraInput.depthFar(),
                    cameraInput.focusDistance(),
                    interopBuffer.frameTextureView(),
                    interopBuffer.depthTextureView(),
                    interopBuffer.reprojectionInputsRequested(),
                    interopBuffer.reprojectionMetadata().orElse(null),
                    cameraInput,
                    performanceMonitor);
        } else {
            framePresenter.presentExternal(
                    mainTarget,
                    renderSettings,
                    cameraInput.depthFar(),
                    interopBuffer.frameTextureView(),
                    performanceMonitor);
        }
        performanceMonitor.gpuMarker(PerformanceSample.GpuMarker.DISPLAY_END);
        performanceMonitor.endCpuStage(
                PerformanceSample.CpuStage.DISPLAY_SUBMIT, displayStart);
        return true;
    }

    private void acquireAndPresentCpuFrame(
            RenderTarget mainTarget,
            CyclesRenderSettings renderSettings,
            NativeBridge.CameraInput cameraInput,
            SectionSceneManager.UpdateResult update) {
        long now = System.nanoTime();
        int deliveredWidth = 0;
        int deliveredHeight = 0;
        int deliveredSamples = 0;
        boolean framePolled = false;
        if (!framePresenter.hasFrame()
                || now - lastFrameDeliveryNanos >= FRAME_DELIVERY_INTERVAL_NANOS) {
            long bridgeStart = System.nanoTime();
            long acquireStart = performanceMonitor.beginCpuStage();
            try (NativeBridge.AcquiredFrame frame = NativeBridge.acquireFrame(
                    framePresenter.generation())) {
                recordBridgeCall(System.nanoTime() - bridgeStart);
                performanceMonitor.endCpuStage(
                        PerformanceSample.CpuStage.FRAME_ACQUIRE, acquireStart);
                framePolled = true;
                deliveredWidth = frame.width();
                deliveredHeight = frame.height();
                deliveredSamples = frame.sampleCount();
                long uploadStart = performanceMonitor.beginCpuStage();
                framePresenter.update(frame);
                performanceMonitor.endCpuStage(
                        PerformanceSample.CpuStage.FRAME_UPLOAD, uploadStart);
            }
            lastFrameDeliveryNanos = System.nanoTime();
        } else {
            skippedFrameDeliveryCount++;
        }
        long displayStart = performanceMonitor.beginCpuStage();
        performanceMonitor.gpuMarker(PerformanceSample.GpuMarker.DISPLAY_BEGIN);
        framePresenter.present(
                mainTarget,
                renderSettings,
                cameraInput.depthFar(),
                performanceMonitor);
        performanceMonitor.gpuMarker(PerformanceSample.GpuMarker.DISPLAY_END);
        performanceMonitor.endCpuStage(
                PerformanceSample.CpuStage.DISPLAY_SUBMIT, displayStart);

        now = System.nanoTime();
        if (framePolled
                && (update.reset() || update.committed())
                && now - lastStatsLogNanos >= STATS_LOG_INTERVAL_NANOS) {
            lastStatsLogNanos = now;
            logger.info(
                    "Cycles streamed scene: sections={}, vertices={}, triangles={}, "
                            + "viewDistance={}, accepted={}, uploaded={}, removed={}, "
                            + "frame={}x{}@{} sample(s); {}",
                    update.activeSections(),
                    update.vertices(),
                    update.triangles(),
                    update.viewDistance(),
                    update.acceptedSections(),
                    update.uploadedSections(),
                    update.removedSections(),
                    deliveredWidth,
                    deliveredHeight,
                    deliveredSamples,
                    NativeBridge.rendererInfo());
        }
    }

    private CyclesRenderSettings activeRenderSettings() {
        CyclesRenderSettings settings = appliedSettings;
        return settings != null ? settings : CyclesClientConfig.snapshot();
    }

    private void invalidateNativeBridgeState() {
        nativeBridgeReady = false;
        appliedSettingsRevision = -1L;
    }

    private void recordAcceptedSettings(CyclesRenderSettings settings) {
        CyclesRenderSettings previous = appliedSettings;
        appliedSettings = settings;
        appliedSettingsRevision = settings.revision();
        rejectedSettingsRevision = -1L;
        if (previous != null && previous.workingSpace() != settings.workingSpace()) {
            lastWorkingSpaceRebuildRevision = settings.revision();
            logger.info(
                    "Cycles working space changed from {} to {}; native session rebuild queued",
                    previous.workingSpace(),
                    settings.workingSpace());
        }
    }

    private void recordBridgeCall(long elapsedNanos) {
        long micros = Math.max(0L, (elapsedNanos + 999L) / 1_000L);
        lastBridgeCallMicros = micros;
        emaBridgeCallMicros = emaBridgeCallMicros == 0L
                ? micros
                : (emaBridgeCallMicros * 7L + micros) / 8L;
        maxBridgeCallMicros = Math.max(maxBridgeCallMicros, micros);
        bridgeCallCount++;
    }

    private void recordCameraCall(long elapsedNanos) {
        long micros = Math.max(0L, (elapsedNanos + 999L) / 1_000L);
        lastCameraCallMicros = micros;
        emaCameraCallMicros = emaCameraCallMicros == 0L
                ? micros
                : (emaCameraCallMicros * 7L + micros) / 8L;
        maxCameraCallMicros = Math.max(maxCameraCallMicros, micros);
        cameraCallCount++;
    }

    private void disableExperimentalRenderer() {
        testFrameEnabled = false;
        performanceMonitor.deactivate();
        SectionGeometryCollector.setEnabled(false);
        sceneManager.reset();
        framePresenter.reset();
        autofocus.reset();
        interopBuffer.drainPendingCopy();
        boolean interopAttached = interopBuffer.telemetry().nativeBound()
                && NativeBridge.isReady()
                && NativeBridge.vulkanInteropState().sessionAttached();
        if (interopAttached) {
            NativeBridge.close();
            invalidateNativeBridgeState();
        }
        interopBuffer.close();
        logger.info("Experimental renderer suspended; native bridge kept warm");
    }

    private void extractDebugOverlay(
            GuiGraphicsExtractor graphics,
            Minecraft minecraft) {
        CyclesDebugOverlay.extract(
                graphics,
                minecraft,
                framePresenter,
                autofocus.state(),
                interopBuffer,
                sceneManager,
                CyclesClientConfig.snapshot(),
                activeRenderSettings(),
                rejectedSettingsRevision,
                lastWorkingSpaceRebuildRevision,
                new CyclesDebugOverlay.RuntimeStats(
                        bridgeCallCount,
                        lastBridgeCallMicros,
                        emaBridgeCallMicros,
                        maxBridgeCallMicros,
                        cameraCallCount,
                        lastCameraCallMicros,
                        emaCameraCallMicros,
                        maxCameraCallMicros,
                        skippedFrameDeliveryCount));
    }
}

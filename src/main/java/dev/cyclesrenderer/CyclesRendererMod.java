package dev.cyclesrenderer;

import com.mojang.blaze3d.platform.InputConstants;
import dev.cyclesrenderer.client.CyclesSettingsScreen;
import dev.cyclesrenderer.config.CyclesClientConfig;
import dev.cyclesrenderer.config.CyclesRenderSettings;
import dev.cyclesrenderer.nativebridge.NativeBridge;
import dev.cyclesrenderer.perf.FramePerformanceMonitor;
import dev.cyclesrenderer.perf.PerformanceSample;
import dev.cyclesrenderer.render.CyclesFramePresenter;
import dev.cyclesrenderer.render.CyclesRenderPipelines;
import dev.cyclesrenderer.render.VulkanCapabilityProbe;
import dev.cyclesrenderer.render.VulkanExternalBufferPrototype;
import dev.cyclesrenderer.scene.DistantHorizonsSceneProvider;
import dev.cyclesrenderer.scene.SectionGeometryCollector;
import dev.cyclesrenderer.scene.SectionSceneManager;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.FlipFrameEvent;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(value = CyclesRendererMod.MOD_ID, dist = Dist.CLIENT)
public final class CyclesRendererMod {
    public static final String MOD_ID = "cyclesrenderer";

    private static final Logger LOGGER = LoggerFactory.getLogger(CyclesRendererMod.class);
    private static final KeyMapping.Category KEY_CATEGORY = new KeyMapping.Category(
            Identifier.fromNamespaceAndPath(MOD_ID, "main"));
    private static final KeyMapping TOGGLE_TEST_FRAME = new KeyMapping(
            "key.cyclesrenderer.toggle_test_frame",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_F8,
            KEY_CATEGORY);
    private static final KeyMapping OPEN_SETTINGS = new KeyMapping(
            "key.cyclesrenderer.open_settings",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_F9,
            KEY_CATEGORY);
    private static final KeyMapping TOGGLE_DEBUG = new KeyMapping(
            "key.cyclesrenderer.toggle_debug",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_F10,
            KEY_CATEGORY);
    private static final long FRAME_DELIVERY_INTERVAL_NANOS = 1_000_000_000L / 120L;

    private static boolean testFrameEnabled;
    private static boolean nativeBridgeReady;
    private static long nativeFrameId;
    private static long lastStatsLogNanos;
    private static long appliedSettingsRevision = -1L;
    private static long rejectedSettingsRevision = -1L;
    private static CyclesRenderSettings appliedSettings;
    private static long bridgeCallCount;
    private static long lastBridgeCallMicros;
    private static long emaBridgeCallMicros;
    private static long maxBridgeCallMicros;
    private static long cameraCallCount;
    private static long lastCameraCallMicros;
    private static long emaCameraCallMicros;
    private static long maxCameraCallMicros;
    private static long skippedFrameDeliveryCount;
    private static long lastFrameDeliveryNanos;
    private static volatile long resourceRevision;
    private static ModContainer modContainer;
    private static final SectionSceneManager SCENE_MANAGER = new SectionSceneManager();
    private static final CyclesFramePresenter FRAME_PRESENTER = new CyclesFramePresenter();
    private static final VulkanExternalBufferPrototype INTEROP_BUFFER =
            new VulkanExternalBufferPrototype();
    private static final FramePerformanceMonitor PERFORMANCE_MONITOR =
            new FramePerformanceMonitor(FRAME_PRESENTER, INTEROP_BUFFER, SCENE_MANAGER);

    public CyclesRendererMod(IEventBus modEventBus, ModContainer container) {
        modContainer = container;
        container.registerConfig(ModConfig.Type.CLIENT, CyclesClientConfig.SPEC);
        container.registerExtensionPoint(
                IConfigScreenFactory.class,
                (registeredContainer, parent) ->
                        new CyclesSettingsScreen(registeredContainer, parent));
        modEventBus.addListener(CyclesRendererMod::registerKeyMappings);
        modEventBus.addListener(CyclesRenderPipelines::register);
        modEventBus.addListener(CyclesRendererMod::addClientReloadListeners);
        modEventBus.addListener(CyclesRendererMod::onConfigLoading);
        modEventBus.addListener(CyclesRendererMod::onConfigReloading);
        NeoForge.EVENT_BUS.addListener(
                EventPriority.HIGHEST, CyclesRendererMod::onClientTickPre);
        NeoForge.EVENT_BUS.addListener(
                EventPriority.LOWEST, CyclesRendererMod::onClientTick);
        NeoForge.EVENT_BUS.addListener(
                EventPriority.HIGHEST, CyclesRendererMod::onRenderFramePre);
        NeoForge.EVENT_BUS.addListener(
                EventPriority.LOWEST, CyclesRendererMod::onRenderFramePost);
        NeoForge.EVENT_BUS.addListener(
                EventPriority.LOWEST, CyclesRendererMod::onFlipFrame);
        NeoForge.EVENT_BUS.addListener(CyclesRendererMod::onRenderLevelAfterLevel);
        NeoForge.EVENT_BUS.addListener(CyclesRendererMod::onRenderGui);
        NeoForge.EVENT_BUS.addListener(CyclesRendererMod::onChunkUnload);
        NeoForge.EVENT_BUS.addListener(CyclesRendererMod::onGameShuttingDown);
    }

    private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.registerCategory(KEY_CATEGORY);
        event.register(TOGGLE_TEST_FRAME);
        event.register(OPEN_SETTINGS);
        event.register(TOGGLE_DEBUG);
    }

    private static void addClientReloadListeners(AddClientReloadListenersEvent event) {
        event.addListener(
                Identifier.fromNamespaceAndPath(MOD_ID, "scene_resources"),
                (ResourceManagerReloadListener) resourceManager -> resourceRevision++);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        PERFORMANCE_MONITOR.onClientTickPost();
        while (OPEN_SETTINGS.consumeClick()) {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.gui.setScreen(new CyclesSettingsScreen(
                    modContainer,
                    minecraft.gui.screen()));
        }
        while (TOGGLE_DEBUG.consumeClick()) {
            CyclesClientConfig.setDebugOverlay(
                    !CyclesClientConfig.snapshot().debugOverlay());
        }

        applySettingsIfNeeded();
        while (TOGGLE_TEST_FRAME.consumeClick()) {
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
            SCENE_MANAGER.reset();
            FRAME_PRESENTER.reset();
            INTEROP_BUFFER.initialize(
                    Minecraft.getInstance(),
                    CyclesClientConfig.snapshot());
            SectionGeometryCollector.setEnabled(true);
            testFrameEnabled = true;
            PERFORMANCE_MONITOR.activate(Minecraft.getInstance());
            LOGGER.info("Experimental renderer enabled; building the streamed Cycles scene");
        }
    }

    private static void onClientTickPre(ClientTickEvent.Pre event) {
        PERFORMANCE_MONITOR.onClientTickPre();
    }

    public static boolean ensureNativeBridgeReady() {
        if (nativeBridgeReady && NativeBridge.isReady()) {
            applySettingsIfNeeded();
            return true;
        }
        NativeBridge.ProbeResult probe = NativeBridge.probe();
        if (!probe.success()) {
            LOGGER.error("Native renderer bridge probe failed: {}", probe.message());
            return false;
        }
        nativeBridgeReady = true;
        appliedSettingsRevision = -1L;
        applySettingsIfNeeded();
        LOGGER.info("Native renderer bridge ready: {}", probe.message());
        return true;
    }

    private static void applySettingsIfNeeded() {
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
            if (testFrameEnabled && INTEROP_BUFFER.requiresLargerCapacity(settings)) {
                rebuildInteropForSettings(settings);
                return;
            }
            NativeBridge.applySettings(settings);
            appliedSettings = settings;
            appliedSettingsRevision = settings.revision();
            rejectedSettingsRevision = -1L;
        } catch (RuntimeException error) {
            if (appliedSettings != null && appliedSettingsRevision >= 0L) {
                rejectedSettingsRevision = settings.revision();
                LOGGER.error(
                        "Could not apply Cycles client settings revision {}; "
                                + "keeping accepted revision {}",
                        settings.revision(),
                        appliedSettings.revision(),
                        error);
                return;
            }
            LOGGER.error("Could not apply initial Cycles client settings", error);
            if (testFrameEnabled) {
                disableExperimentalRenderer();
            }
            NativeBridge.close();
            nativeBridgeReady = false;
            appliedSettingsRevision = -1L;
        }
    }

    private static void rebuildInteropForSettings(CyclesRenderSettings settings) {
        VulkanExternalBufferPrototype.Telemetry previous = INTEROP_BUFFER.telemetry();
        LOGGER.info(
                "Rebuilding Vulkan interop capacity from {}x{} for output {}x{}@{}%",
                previous.capacityWidth(),
                previous.capacityHeight(),
                settings.renderWidth(),
                settings.renderHeight(),
                settings.resolutionPercentage());

        INTEROP_BUFFER.drainPendingCopy();
        NativeBridge.close();
        nativeBridgeReady = false;
        appliedSettingsRevision = -1L;
        INTEROP_BUFFER.close();

        NativeBridge.ProbeResult probe = NativeBridge.probe();
        if (!probe.success()) {
            throw new IllegalStateException(
                    "native renderer bridge probe failed after interop resize: "
                            + probe.message());
        }
        nativeBridgeReady = true;
        NativeBridge.applySettings(settings);
        appliedSettings = settings;
        appliedSettingsRevision = settings.revision();
        rejectedSettingsRevision = -1L;
        INTEROP_BUFFER.initialize(Minecraft.getInstance(), settings);
        SCENE_MANAGER.reset();
        FRAME_PRESENTER.reset();
        DistantHorizonsSceneProvider.reset();
        nativeFrameId = 0L;
    }

    private static void onConfigLoading(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == CyclesClientConfig.SPEC) {
            CyclesClientConfig.markReloaded();
        }
    }

    private static void onConfigReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == CyclesClientConfig.SPEC) {
            CyclesClientConfig.markReloaded();
        }
    }

    public static boolean isExperimentalRendererEnabled() {
        return testFrameEnabled;
    }

    public static boolean shouldReplaceVanillaWorld() {
        return testFrameEnabled
                && (INTEROP_BUFFER.hasActiveFrame() || FRAME_PRESENTER.hasFrame());
    }

    private static void onRenderFramePre(RenderFrameEvent.Pre event) {
        PERFORMANCE_MONITOR.onRenderFramePre();
    }

    private static void onRenderFramePost(RenderFrameEvent.Post event) {
        PERFORMANCE_MONITOR.onRenderFramePost();
    }

    private static void onFlipFrame(FlipFrameEvent event) {
        PERFORMANCE_MONITOR.onFlipFrame();
    }

    private static void onRenderLevelAfterLevel(RenderLevelStageEvent.AfterLevel event) {
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
        long cyclesStart = PERFORMANCE_MONITOR.beginCpuStage();
        PERFORMANCE_MONITOR.gpuMarker(PerformanceSample.GpuMarker.CYCLES_BEGIN);
        try {
            CyclesRenderSettings renderSettings = activeRenderSettings();
            long sceneStart = PERFORMANCE_MONITOR.beginCpuStage();
            SectionSceneManager.UpdateResult update = SCENE_MANAGER.update(
                    minecraft,
                    level,
                    camera.pos,
                    resourceRevision,
                    renderSettings);
            PERFORMANCE_MONITOR.endCpuStage(
                    PerformanceSample.CpuStage.SCENE_UPDATE, sceneStart);
            long frameId = nativeFrameId++;
            NativeBridge.CameraInput cameraInput = createCameraInput(camera);
            long cameraStart = System.nanoTime();
            long cameraTraceStart = PERFORMANCE_MONITOR.beginCpuStage();
            NativeBridge.updateCamera(
                    mainTarget.width,
                    mainTarget.height,
                    frameId,
                    cameraInput);
            recordCameraCall(System.nanoTime() - cameraStart);
            PERFORMANCE_MONITOR.endCpuStage(
                    PerformanceSample.CpuStage.CAMERA_FFI, cameraTraceStart);

            long interopStart = PERFORMANCE_MONITOR.beginCpuStage();
            PERFORMANCE_MONITOR.gpuMarker(PerformanceSample.GpuMarker.INTEROP_BEGIN);
            INTEROP_BUFFER.pollCompletedFrame();
            PERFORMANCE_MONITOR.gpuMarker(PerformanceSample.GpuMarker.INTEROP_END);
            PERFORMANCE_MONITOR.endCpuStage(
                    PerformanceSample.CpuStage.INTEROP_POLL, interopStart);
            if (INTEROP_BUFFER.hasActiveFrame()) {
                long displayStart = PERFORMANCE_MONITOR.beginCpuStage();
                PERFORMANCE_MONITOR.gpuMarker(PerformanceSample.GpuMarker.DISPLAY_BEGIN);
                FRAME_PRESENTER.presentExternal(
                        mainTarget,
                        renderSettings,
                        cameraInput.depthFar(),
                        INTEROP_BUFFER.frameTextureView(),
                        PERFORMANCE_MONITOR);
                PERFORMANCE_MONITOR.gpuMarker(PerformanceSample.GpuMarker.DISPLAY_END);
                PERFORMANCE_MONITOR.endCpuStage(
                        PerformanceSample.CpuStage.DISPLAY_SUBMIT, displayStart);
                return;
            }

            long now = System.nanoTime();
            int deliveredWidth = 0;
            int deliveredHeight = 0;
            int deliveredSamples = 0;
            boolean framePolled = false;
            if (!FRAME_PRESENTER.hasFrame()
                    || now - lastFrameDeliveryNanos >= FRAME_DELIVERY_INTERVAL_NANOS) {
                long bridgeStart = System.nanoTime();
                long acquireStart = PERFORMANCE_MONITOR.beginCpuStage();
                try (NativeBridge.AcquiredFrame frame = NativeBridge.acquireFrame(
                        FRAME_PRESENTER.generation())) {
                    recordBridgeCall(System.nanoTime() - bridgeStart);
                    PERFORMANCE_MONITOR.endCpuStage(
                            PerformanceSample.CpuStage.FRAME_ACQUIRE, acquireStart);
                    framePolled = true;
                    deliveredWidth = frame.width();
                    deliveredHeight = frame.height();
                    deliveredSamples = frame.sampleCount();
                    long uploadStart = PERFORMANCE_MONITOR.beginCpuStage();
                    FRAME_PRESENTER.update(frame);
                    PERFORMANCE_MONITOR.endCpuStage(
                            PerformanceSample.CpuStage.FRAME_UPLOAD, uploadStart);
                }
                lastFrameDeliveryNanos = System.nanoTime();
            } else {
                skippedFrameDeliveryCount++;
            }
            long displayStart = PERFORMANCE_MONITOR.beginCpuStage();
            PERFORMANCE_MONITOR.gpuMarker(PerformanceSample.GpuMarker.DISPLAY_BEGIN);
            FRAME_PRESENTER.present(
                    mainTarget,
                    renderSettings,
                    cameraInput.depthFar(),
                    PERFORMANCE_MONITOR);
            PERFORMANCE_MONITOR.gpuMarker(PerformanceSample.GpuMarker.DISPLAY_END);
            PERFORMANCE_MONITOR.endCpuStage(
                    PerformanceSample.CpuStage.DISPLAY_SUBMIT, displayStart);

            now = System.nanoTime();
            if (framePolled
                    && (update.reset() || update.committed())
                    && now - lastStatsLogNanos >= 2_000_000_000L) {
                lastStatsLogNanos = now;
                LOGGER.info(
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
        } catch (RuntimeException error) {
            LOGGER.error("Native frame rendering failed; restoring the vanilla renderer", error);
            disableExperimentalRenderer();
            NativeBridge.close();
            nativeBridgeReady = false;
            appliedSettingsRevision = -1L;
        } finally {
            PERFORMANCE_MONITOR.gpuMarker(PerformanceSample.GpuMarker.CYCLES_END);
            PERFORMANCE_MONITOR.endCpuStage(
                    PerformanceSample.CpuStage.CYCLES_CALLBACK, cyclesStart);
        }
    }

    private static NativeBridge.CameraInput createCameraInput(CameraRenderState camera) {
        float projectionScaleY = camera.projectionMatrix.m11();
        if (!Float.isFinite(projectionScaleY) || projectionScaleY <= 0.0F) {
            throw new IllegalStateException("invalid camera projection scale: " + projectionScaleY);
        }
        float verticalFovRadians = 2.0F * (float) Math.atan(1.0F / projectionScaleY);
        return new NativeBridge.CameraInput(
                camera.pos.x,
                camera.pos.y,
                camera.pos.z,
                camera.orientation.x(),
                camera.orientation.y(),
                camera.orientation.z(),
                camera.orientation.w(),
                verticalFovRadians,
                Math.max(camera.depthFar, 1.0F));
    }

    private static CyclesRenderSettings activeRenderSettings() {
        CyclesRenderSettings settings = appliedSettings;
        return settings != null ? settings : CyclesClientConfig.snapshot();
    }

    private static void recordBridgeCall(long elapsedNanos) {
        long micros = Math.max(0L, (elapsedNanos + 999L) / 1_000L);
        lastBridgeCallMicros = micros;
        emaBridgeCallMicros = emaBridgeCallMicros == 0L
                ? micros
                : (emaBridgeCallMicros * 7L + micros) / 8L;
        maxBridgeCallMicros = Math.max(maxBridgeCallMicros, micros);
        bridgeCallCount++;
    }

    private static void recordCameraCall(long elapsedNanos) {
        long micros = Math.max(0L, (elapsedNanos + 999L) / 1_000L);
        lastCameraCallMicros = micros;
        emaCameraCallMicros = emaCameraCallMicros == 0L
                ? micros
                : (emaCameraCallMicros * 7L + micros) / 8L;
        maxCameraCallMicros = Math.max(maxCameraCallMicros, micros);
        cameraCallCount++;
    }

    private static void disableExperimentalRenderer() {
        testFrameEnabled = false;
        PERFORMANCE_MONITOR.deactivate();
        SectionGeometryCollector.setEnabled(false);
        SCENE_MANAGER.reset();
        FRAME_PRESENTER.reset();
        INTEROP_BUFFER.drainPendingCopy();
        boolean interopAttached = INTEROP_BUFFER.telemetry().nativeBound()
                && NativeBridge.isReady()
                && NativeBridge.vulkanInteropState().sessionAttached();
        if (interopAttached) {
            NativeBridge.close();
            nativeBridgeReady = false;
            appliedSettingsRevision = -1L;
        }
        INTEROP_BUFFER.close();
        DistantHorizonsSceneProvider.reset();
        LOGGER.info("Experimental renderer suspended; native bridge kept warm");
    }

    private static void onChunkUnload(ChunkEvent.Unload event) {
        if (testFrameEnabled && event.getLevel() instanceof ClientLevel level) {
            SCENE_MANAGER.onChunkUnload(level, event.getChunk().getPos());
        }
    }

    private static void onGameShuttingDown(GameShuttingDownEvent event) {
        PERFORMANCE_MONITOR.shutdown();
        INTEROP_BUFFER.drainPendingCopy();
        if (INTEROP_BUFFER.telemetry().nativeBound() && NativeBridge.isReady()) {
            NativeBridge.close();
            nativeBridgeReady = false;
            appliedSettingsRevision = -1L;
        }
        INTEROP_BUFFER.close();
    }

    private static void onRenderGui(RenderGuiEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

        GuiGraphicsExtractor graphics = event.getGuiGraphics();
        if (testFrameEnabled) {
            Component status = INTEROP_BUFFER.hasActiveFrame() || FRAME_PRESENTER.hasFrame()
                    ? Component.translatable("message.cyclesrenderer.test_frame")
                    : Component.translatable("message.cyclesrenderer.building_scene");
            graphics.centeredText(
                    minecraft.font,
                    status,
                    graphics.guiWidth() / 2,
                    12,
                    0xFFFFFFFF);
        }
        if (CyclesClientConfig.snapshot().debugOverlay()) {
            extractDebugOverlay(graphics, minecraft);
        }
    }

    private static void extractDebugOverlay(
            GuiGraphicsExtractor graphics,
            Minecraft minecraft) {
        CyclesDebugOverlay.extract(
                graphics,
                minecraft,
                FRAME_PRESENTER,
                INTEROP_BUFFER,
                SCENE_MANAGER,
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

    private static void extractLegacyDebugOverlay(
            GuiGraphicsExtractor graphics,
            Minecraft minecraft) {
        int y = 52;
        graphics.text(minecraft.font, "Cycles ABI " + NativeBridge.ABI_VERSION, 6, y, 0xFFE0E0E0);
        y += 10;
        if (!NativeBridge.isReady()) {
            graphics.text(minecraft.font, "Native bridge: not loaded", 6, y, 0xFFFFAA55);
            return;
        }
        try {
            NativeBridge.Diagnostics diagnostics = NativeBridge.diagnostics();
            NativeBridge.Capabilities capabilities = NativeBridge.capabilities();
            NativeBridge.PassDescriptor passDescriptor =
                    NativeBridge.passDescriptor(diagnostics.activePassId());
            VulkanCapabilityProbe.Snapshot vulkan =
                    VulkanCapabilityProbe.snapshot(minecraft);
            CyclesRenderSettings settings = CyclesClientConfig.snapshot();
            CyclesFramePresenter.Telemetry presentation = FRAME_PRESENTER.telemetry();
            SectionGeometryCollector.Telemetry capture = SectionGeometryCollector.telemetry();
            SectionSceneManager.Telemetry scene = SCENE_MANAGER.telemetry();
            graphics.text(
                    minecraft.font,
                    diagnostics.deviceName() + " / denoise " + diagnostics.denoiserName()
                            + " / " + diagnostics.stateName(),
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    diagnostics.width() + "x" + diagnostics.height()
                            + "  pass=" + settings.activePass().name()
                            + "/" + diagnostics.activeFramePassName()
                            + "  sample actual/target=" + diagnostics.sampleCount()
                            + "/" + diagnostics.targetSampleCount()
                            + " (" + sampleProgressPercent(diagnostics) + "%)",
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "color=" + settings.viewTransform().name()
                            + " supported="
                            + capabilities.supportsViewTransform(settings.viewTransform())
                            + " ocio=" + capabilities.colorConfigStateName()
                            + " mask=0x" + Integer.toHexString(capabilities.colorTransformMask()),
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "color LUT=" + capabilities.colorLutEdgeLength() + "^3 "
                            + capabilities.colorLutPixelFormatName()
                            + "  " + NativeBridge.colorManagementInfo(),
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "working=" + settings.workingSpace().name()
                            + "  output=sRGB SDR  HDR swapchain=false",
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    vulkan.vulkan()
                            ? "vulkan=" + vulkan.deviceName()
                                    + " uuid=" + vulkan.physicalDeviceUuid()
                            : "vulkan probe unavailable: " + vulkan.error(),
                    6, y, vulkan.vulkan() ? 0xFFE0E0E0 : 0xFFFFAA55);
            y += 10;
            if (vulkan.vulkan()) {
                graphics.text(
                        minecraft.font,
                        "vk ext colorspace=" + vulkan.swapchainColorspace().summary()
                                + " hdrMetadata=" + vulkan.hdrMetadata().summary(),
                        6, y, 0xFFE0E0E0);
                y += 10;
                graphics.text(
                        minecraft.font,
                        "vk ext memoryWin32=" + vulkan.externalMemoryWin32().summary()
                                + " semaphoreWin32="
                                + vulkan.externalSemaphoreWin32().summary(),
                        6, y, 0xFFE0E0E0);
                y += 10;
                graphics.text(
                        minecraft.font,
                        "interop bootstrap "
                                + VulkanCapabilityProbe.interopBootstrap().summary(),
                        6, y, VulkanCapabilityProbe.interopBootstrap().extensionsRequested()
                                ? 0xFFE0E0E0
                                : 0xFFFFDD88);
                y += 10;
                VulkanExternalBufferPrototype.Telemetry interopBuffer =
                        INTEROP_BUFFER.telemetry();
                VulkanExternalBufferPrototype.CopyTelemetry interopCopy =
                        INTEROP_BUFFER.copyTelemetry();
                graphics.text(
                        minecraft.font,
                        "interop buffer=" + interopBuffer.state()
                                + " allocated/bound=" + interopBuffer.allocated()
                                + "/" + interopBuffer.nativeBound()
                                + " capacity=" + interopBuffer.capacityWidth()
                                + "x" + interopBuffer.capacityHeight()
                                + " slots=" + VulkanExternalBufferPrototype.SLOT_COUNT
                                + " slot/alloc MiB="
                                + oneDecimalMebibytes(interopBuffer.logicalBytes())
                                + "/" + oneDecimalMebibytes(
                                        interopBuffer.allocationBytes()),
                        6, y, interopBuffer.allocated() ? 0xFFE0E0E0 : 0xFFFFDD88);
                y += 10;
                NativeBridge.VulkanInteropState interopState =
                        NativeBridge.vulkanInteropState();
                graphics.text(
                        minecraft.font,
                        "interop state active/ready/acquired="
                                + interopState.active() + "/"
                                + interopState.frameReady() + "/"
                                + interopState.frameAcquired()
                                + " timeline=" + interopState.timelineSync()
                                + " gen=" + interopState.generation()
                                + " slots=" + interopState.readySlotCount()
                                + "/" + interopState.slotCount()
                                + " waits=" + interopState.producerWaitCount()
                                + " sample=" + interopState.sampleCount()
                                + " sync us=" + interopState.lastSyncMicros()
                                + "/" + interopState.emaSyncMicros()
                                + "/" + interopState.maxSyncMicros(),
                        6, y, interopState.active() ? 0xFFE0E0E0 : 0xFFFFDD88);
                y += 10;
                graphics.text(
                        minecraft.font,
                        "interop vk copy pending/display=" + interopCopy.pending()
                                + "/" + interopCopy.displayedGeneration()
                                + " size=" + interopCopy.displayedWidth()
                                + "x" + interopCopy.displayedHeight()
                                + " slot=" + interopCopy.displayedSlotIndex()
                                + " count=" + interopCopy.copyCount()
                                + " gaps=" + interopCopy.generationGaps()
                                + " enqueue us=" + interopCopy.lastCopyMicros()
                                + "/" + interopCopy.emaCopyMicros()
                                + "/" + interopCopy.maxCopyMicros(),
                        6, y, interopCopy.copyCount() > 0L
                                ? 0xFFE0E0E0 : 0xFFFFDD88);
                y += 10;
                graphics.text(
                        minecraft.font,
                        "surface formats=" + vulkan.surfaceFormats().size()
                                + " hdrCandidates=" + vulkan.hdrSurfaceFormatCount()
                                + " " + vulkan.surfaceFormatSummary(),
                        6, y, 0xFFE0E0E0);
                y += 10;
                boolean uuidMatch = vulkan.uuidMatches(diagnostics.deviceUuid());
                boolean interopPrerequisites =
                        uuidMatch && vulkan.interopExtensionsEnabled();
                graphics.text(
                        minecraft.font,
                        "interop uuid cycles=" + diagnostics.deviceUuid()
                                + " match=" + uuidMatch,
                        6, y, uuidMatch ? 0xFFE0E0E0 : 0xFFFFAA55);
                y += 10;
                graphics.text(
                        minecraft.font,
                        "interop available=" + vulkan.interopExtensionsAvailable()
                                + " enabled=" + vulkan.interopExtensionsEnabled()
                                + " prerequisites=" + interopPrerequisites
                                + " active=" + interopState.active(),
                        6, y, interopState.active() ? 0xFFE0E0E0 : 0xFFFFDD88);
                y += 10;
                graphics.text(
                        minecraft.font,
                        "hdr colorspace ext=" + vulkan.swapchainColorspace().summary()
                                + " surfaceAdvertised=" + vulkan.hdrSurfaceAdvertised()
                                + " negotiate=" + vulkan.hdrNegotiationEnabled()
                                + " active=false",
                        6, y, 0xFFFFDD88);
                y += 10;
            }
            graphics.text(
                    minecraft.font,
                    "resolution=" + settings.resolutionPercentage() + "%"
                            + " interactive=" + settings.interactiveResolutionPercentage() + "%"
                            + " dynamic=" + settings.dynamicResolution(),
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "sampling=" + diagnostics.samplingStateName()
                            + " pattern=" + settings.samplingPattern().name()
                            + "/" + diagnostics.samplingPatternName()
                            + "  rate=" + Math.round(diagnostics.sampleRate() * 10.0F) / 10.0F
                            + " sample/s"
                            + "  settle=" + diagnostics.settlingRemainingMillis() + "ms"
                            + " transitions=" + diagnostics.samplingTransitionCount(),
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "atmosphere=multiple-scattering sun="
                            + settings.atmosphereSunElevationDegrees() + "deg/"
                            + settings.atmosphereSunRotationDegrees() + "deg"
                            + " size=" + settings.atmosphereSunSizeDegrees() + "deg"
                            + " intensity=" + settings.atmosphereSunIntensity()
                            + " density=" + settings.atmosphereAirDensity() + "/"
                            + settings.atmosphereAerosolDensity() + "/"
                            + settings.atmosphereOzoneDensity(),
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "scene=" + diagnostics.sceneRevision()
                            + " camera=" + diagnostics.cameraRevision()
                            + " frame=" + diagnostics.frameGeneration()
                            + " sections=" + diagnostics.sectionCount(),
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "camera clip config=" + settings.cameraClipNear() + "/"
                            + (settings.cameraClipFar() == 0.0F
                                    ? "Minecraft"
                                    : settings.cameraClipFar())
                            + " effective=" + diagnostics.effectiveCameraClipNear()
                            + "/" + diagnostics.effectiveCameraClipFar(),
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "camera projection=" + settings.projectionMode().name()
                            + "/" + diagnostics.projectionModeName()
                            + " fov=" + oneDecimalDegrees(diagnostics.verticalFovRadians())
                            + "deg lens=" + settings.focalLengthMm()
                            + "mm sensor=" + settings.sensorWidthMm() + "mm",
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "dof=" + settings.depthOfField() + "/" + diagnostics.depthOfField()
                            + " focus=" + diagnostics.focusDistance()
                            + " f/" + diagnostics.fStop()
                            + " radius=" + diagnostics.apertureSize()
                            + " blades=" + diagnostics.apertureBlades()
                            + " rot=" + oneDecimalDegrees(diagnostics.apertureRotationRadians())
                            + "deg ratio=" + diagnostics.apertureRatio(),
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "settings=" + diagnostics.settingsRevision()
                            + " reset=" + diagnostics.resetName(),
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "store=" + diagnostics.framePixelFormatName()
                            + " variant=" + diagnostics.activeFrameVariantName()
                            + " lease/upload=RGBA16_FLOAT",
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "pass cache=" + diagnostics.passCacheEntryCount()
                            + "  MiB=" + oneDecimalMebibytes(diagnostics.passCacheBytes())
                            + "/" + oneDecimalMebibytes(diagnostics.passCacheBudgetBytes())
                            + " hit/evict=" + diagnostics.passCacheHitCount()
                            + "/" + diagnostics.passCacheEvictionCount(),
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "pass masks raw=0x" + Long.toHexString(diagnostics.cachedRawPassMask())
                            + " denoised=0x"
                            + Long.toHexString(diagnostics.cachedDenoisedPassMask()),
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "pass registry=0x" + Long.toHexString(diagnostics.registeredPassMask())
                            + " hit/rebuild=" + diagnostics.passRegistryHitCount()
                            + "/" + diagnostics.passRegistryRebuildCount(),
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "pass desc=" + passDescriptor.semanticName()
                            + " components=" + passDescriptor.sourceComponentCount()
                            + "->" + passDescriptor.displayComponentCount()
                            + " " + passDescriptor.pixelFormatName()
                            + " flags=0x" + Integer.toHexString(passDescriptor.flags()),
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "denoise selected/effective=" + diagnostics.selectedDenoiserName()
                            + "/" + diagnostics.denoiserName()
                            + " scheduled=" + (diagnostics.denoiserScheduled() != 0)
                            + " reason=" + diagnostics.denoiserScheduleReasonName(),
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "denoise start=" + diagnostics.effectiveDenoiserStartSample()
                            + " run/skip=" + diagnostics.denoiserScheduleRunCount()
                            + "/" + diagnostics.denoiserScheduleSkipCount(),
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "frame leases=" + diagnostics.activeFrameLeases()
                            + "/" + diagnostics.peakFrameLeases()
                            + " slots=" + diagnostics.frameSlotCount()
                            + " dropped=" + diagnostics.droppedDisplayUpdates(),
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "native display us=" + diagnostics.lastConvertMicros()
                            + "/" + diagnostics.emaConvertMicros()
                            + "/" + diagnostics.maxConvertMicros()
                            + " age=" + diagnostics.frameAgeMicros(),
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "native copy us=" + diagnostics.lastCopyMicros()
                            + "/" + diagnostics.emaCopyMicros()
                            + "/" + diagnostics.maxCopyMicros()
                            + "  MiB=" + oneDecimalMebibytes(diagnostics.copiedByteCount()),
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "frames produced/copied/poll=" + diagnostics.producedFrameCount()
                            + "/" + diagnostics.copiedFrameCount()
                            + "/" + diagnostics.unchangedPollCount(),
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "camera queue us=" + lastCameraCallMicros
                            + "/" + emaCameraCallMicros
                            + "/" + maxCameraCallMicros
                            + " calls=" + cameraCallCount,
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "frame pull us=" + lastBridgeCallMicros
                            + "/" + emaBridgeCallMicros
                            + "/" + maxBridgeCallMicros
                            + " polls=" + bridgeCallCount
                            + " skipped=" + skippedFrameDeliveryCount,
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "upload us=" + presentation.lastUploadMicros()
                            + "/" + presentation.emaUploadMicros()
                            + "/" + presentation.maxUploadMicros()
                            + " count=" + presentation.uploadCount()
                            + " gaps=" + presentation.generationGaps()
                            + " MiB=" + oneDecimalMebibytes(presentation.uploadedBytes()),
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "ocio LUT build+upload us=" + presentation.lastColorLutUploadMicros()
                            + "/" + presentation.emaColorLutUploadMicros()
                            + "/" + presentation.maxColorLutUploadMicros()
                            + " count=" + presentation.colorLutUploadCount()
                            + " view=" + presentation.colorLutViewTransform()
                            + " MiB="
                            + oneDecimalMebibytes(presentation.colorLutUploadedBytes()),
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "capture us=" + capture.lastCaptureMicros()
                            + "/" + capture.emaCaptureMicros()
                            + "/" + capture.maxCaptureMicros()
                            + " count=" + capture.captureCount()
                            + " queued=" + capture.queuedCount()
                            + " replaced=" + capture.replacedCount(),
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "scene java us=" + scene.lastUpdateMicros()
                            + "/" + scene.emaUpdateMicros()
                            + "/" + scene.maxUpdateMicros()
                            + " accepted=" + scene.lastAcceptedSections()
                            + " pending=" + scene.pendingCommit(),
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "section ffi upsert us=" + scene.lastUpsertMicros()
                            + "/" + scene.emaUpsertMicros()
                            + "/" + scene.maxUpsertMicros()
                            + " remove=" + scene.lastRemoveMicros()
                            + "/" + scene.emaRemoveMicros()
                            + "/" + scene.maxRemoveMicros(),
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "section ffi commit us=" + scene.lastCommitMicros()
                            + "/" + scene.emaCommitMicros()
                            + "/" + scene.maxCommitMicros(),
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "native scene commit us=" + diagnostics.lastSceneCommitMicros()
                            + "/" + diagnostics.emaSceneCommitMicros()
                            + "/" + diagnostics.maxSceneCommitMicros()
                            + " count=" + diagnostics.sceneCommitCount(),
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "cycles delta us=" + diagnostics.lastSceneDeltaMicros()
                            + "/" + diagnostics.emaSceneDeltaMicros()
                            + "/" + diagnostics.maxSceneDeltaMicros()
                            + " count=" + diagnostics.sceneDeltaCount(),
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "render start us=" + diagnostics.lastRenderStartMicros()
                            + "/" + diagnostics.emaRenderStartMicros()
                            + "/" + diagnostics.maxRenderStartMicros()
                            + " count=" + diagnostics.renderStartCount(),
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "scene queue us=" + diagnostics.lastSceneQueueMicros()
                            + "/" + diagnostics.emaSceneQueueMicros()
                            + "/" + diagnostics.maxSceneQueueMicros()
                            + " reset wait=" + diagnostics.lastResetWaitMicros()
                            + "/" + diagnostics.emaResetWaitMicros()
                            + "/" + diagnostics.maxResetWaitMicros(),
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "device update us=" + diagnostics.lastDeviceUpdateMicros()
                            + "/" + diagnostics.emaDeviceUpdateMicros()
                            + "/" + diagnostics.maxDeviceUpdateMicros()
                            + " geometry=" + diagnostics.lastGeometryUpdateMicros()
                            + "/" + diagnostics.emaGeometryUpdateMicros()
                            + "/" + diagnostics.maxGeometryUpdateMicros(),
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "Cycles BVH status us=" + diagnostics.lastBvhUpdateMicros()
                            + "/" + diagnostics.emaBvhUpdateMicros()
                            + "/" + diagnostics.maxBvhUpdateMicros(),
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "scene -> first frame us=" + diagnostics.lastSceneFirstFrameMicros()
                            + "/" + diagnostics.emaSceneFirstFrameMicros()
                            + "/" + diagnostics.maxSceneFirstFrameMicros()
                            + " rev=" + diagnostics.sceneTimingRevision()
                            + " count=" + diagnostics.sceneTimingCount(),
                    6, y, 0xFFE0E0E0);
            y += 10;
            graphics.text(
                    minecraft.font,
                    "largest EMA stage=" + largestEmaStage(
                            presentation, capture, scene, diagnostics),
                    6, y, 0xFFFFDD88);
        } catch (RuntimeException error) {
            graphics.text(
                    minecraft.font,
                    "Native diagnostics failed: " + error.getMessage(),
                    6, y, 0xFFFF5555);
        }
    }

    private static double oneDecimalMebibytes(long bytes) {
        return Math.round(bytes / 104_857.6D) / 10.0D;
    }

    private static double oneDecimalDegrees(float radians) {
        return Math.round(Math.toDegrees(radians) * 10.0D) / 10.0D;
    }

    private static int sampleProgressPercent(NativeBridge.Diagnostics diagnostics) {
        if (diagnostics.targetSampleCount() <= 0) {
            return 0;
        }
        return Math.min(100, Math.round(
                diagnostics.sampleCount() * 100.0F / diagnostics.targetSampleCount()));
    }

    private static String largestEmaStage(
            CyclesFramePresenter.Telemetry presentation,
            SectionGeometryCollector.Telemetry capture,
            SectionSceneManager.Telemetry scene,
            NativeBridge.Diagnostics diagnostics) {
        String[] names = {
            "GPU upload", "mesh capture", "Java scene", "FFI upsert", "FFI commit",
            "Cycles delta", "render start", "scene queue", "reset wait",
            "device update", "geometry update", "BVH status",
            "scene first frame", "native display"
        };
        long[] micros = {
            presentation.emaUploadMicros(), capture.emaCaptureMicros(), scene.emaUpdateMicros(),
            scene.emaUpsertMicros(), scene.emaCommitMicros(),
            diagnostics.emaSceneDeltaMicros(), diagnostics.emaRenderStartMicros(),
            diagnostics.emaSceneQueueMicros(), diagnostics.emaResetWaitMicros(),
            diagnostics.emaDeviceUpdateMicros(), diagnostics.emaGeometryUpdateMicros(),
            diagnostics.emaBvhUpdateMicros(),
            diagnostics.emaSceneFirstFrameMicros(),
            diagnostics.emaConvertMicros()
        };
        int largest = 0;
        for (int index = 1; index < micros.length; ++index) {
            if (micros[index] > micros[largest]) {
                largest = index;
            }
        }
        return names[largest] + " " + micros[largest] + "us";
    }
}

package dev.cyclesrenderer;

import dev.cyclesrenderer.config.CyclesClientConfig;
import dev.cyclesrenderer.config.CyclesRenderSettings;
import dev.cyclesrenderer.nativebridge.NativeBridge;
import dev.cyclesrenderer.render.CyclesFramePresenter;
import dev.cyclesrenderer.render.HdrOutputStage;
import dev.cyclesrenderer.render.VulkanCapabilityProbe;
import dev.cyclesrenderer.render.VulkanExternalBufferPrototype;
import dev.cyclesrenderer.scene.SectionGeometryCollector;
import dev.cyclesrenderer.scene.SectionSceneManager;
import dev.cyclesrenderer.scene.LabPbrAtlasBuilder;
import dev.cyclesrenderer.scene.LabPbrResources;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

final class CyclesDebugOverlay {
    private static final int X = 6;
    private static final int TOP = 52;
    private static final int LINE_HEIGHT = 10;
    private static final int COLOR_LIVE = 0xFF7CFFB2;
    private static final int COLOR_FRAME = 0xFF7DD3FC;
    private static final int COLOR_TIMING = 0xFFFFD166;
    private static final int COLOR_STATE = 0xFFC4A7FF;
    private static final int COLOR_PBR = 0xFFFF9BD5;
    private static final int COLOR_STATIC = 0xFFB8C0CC;
    private static final int COLOR_WARNING = 0xFFFFAA55;
    private static final int COLOR_ERROR = 0xFFFF5555;

    private static final CompletedFrameRate COMPLETED_FRAME_RATE = new CompletedFrameRate();

    private CyclesDebugOverlay() {
    }

    static void extract(
            GuiGraphicsExtractor graphics,
            Minecraft minecraft,
            CyclesFramePresenter presenter,
            VulkanExternalBufferPrototype interopBuffer,
            SectionSceneManager sceneManager,
            RuntimeStats runtime) {
        Writer out = new Writer(graphics, minecraft, TOP);
        if (!NativeBridge.isReady()) {
            out.section("[ CYCLES DEBUG ]", COLOR_ERROR);
            out.line("Native bridge: not loaded", COLOR_WARNING);
            COMPLETED_FRAME_RATE.reset();
            return;
        }

        try {
            NativeBridge.Diagnostics diagnostics = NativeBridge.diagnostics();
            NativeBridge.Capabilities capabilities = NativeBridge.capabilities();
            NativeBridge.VulkanInteropState interop = NativeBridge.vulkanInteropState();
            VulkanExternalBufferPrototype.Telemetry buffer = interopBuffer.telemetry();
            VulkanExternalBufferPrototype.CopyTelemetry copy = interopBuffer.copyTelemetry();
            CyclesFramePresenter.Telemetry presentation = presenter.telemetry();
            SectionGeometryCollector.Telemetry capture = SectionGeometryCollector.telemetry();
            SectionSceneManager.Telemetry scene = sceneManager.telemetry();
            CyclesRenderSettings settings = CyclesClientConfig.snapshot();
            NativeBridge.PassDescriptor pass =
                    NativeBridge.passDescriptor(diagnostics.activePassId());
            VulkanCapabilityProbe.Snapshot vulkan =
                    VulkanCapabilityProbe.snapshot(minecraft);
            VulkanCapabilityProbe.SwapchainBootstrap swapchain =
                    VulkanCapabilityProbe.swapchainBootstrap();
            HdrOutputStage.Telemetry hdrOutput = HdrOutputStage.telemetry();

            long completedFrames = interop.active()
                    ? copy.copyCount()
                    : presentation.uploadCount();
            CompletedFrameRate.Snapshot rate = COMPLETED_FRAME_RATE.update(
                    interop.active() ? 1 : 2,
                    completedFrames,
                    System.nanoTime());

            out.section("[ LIVE / RATES ]", COLOR_LIVE);
            out.line(
                    "Minecraft/Vulkan FPS=" + minecraft.getFps()
                            + "  Cycles output FPS=" + oneDecimal(rate.framesPerSecond())
                            + "  source="
                            + (interop.active() ? "completed Vulkan copy" : "CPU display upload"),
                    COLOR_LIVE);
            out.line(
                    "Cycles frame latency=" + milliseconds(rate.latestIntervalNanos())
                            + "ms (completed interval)  output age="
                            + milliseconds(rate.outputAgeNanos()) + "ms",
                    rate.outputAgeNanos() > 1_000_000_000L ? COLOR_WARNING : COLOR_LIVE);
            out.line(
                    "sample=" + diagnostics.sampleCount() + "/"
                            + diagnostics.targetSampleCount() + " ("
                            + sampleProgressPercent(diagnostics) + "%)"
                            + "  samples/s=" + oneDecimal(diagnostics.sampleRate())
                            + "  state=" + diagnostics.samplingStateName()
                            + "  settle=" + diagnostics.settlingRemainingMillis() + "ms",
                    COLOR_LIVE);
            out.line(
                    "frame=" + diagnostics.width() + "x" + diagnostics.height()
                            + "  pass=" + diagnostics.activeFramePassName()
                            + "/" + diagnostics.activeFrameVariantName()
                            + "  device=" + diagnostics.deviceName()
                            + "  renderer=" + diagnostics.stateName(),
                    COLOR_LIVE);
            out.line(
                    "denoise selected/effective=" + diagnostics.selectedDenoiserName()
                            + "/" + diagnostics.denoiserName()
                            + "  scheduled=" + (diagnostics.denoiserScheduled() != 0)
                            + "  reason=" + diagnostics.denoiserScheduleReasonName(),
                    COLOR_LIVE);

            out.section("[ FRAME / VULKAN INTEROP ]", COLOR_FRAME);
            out.line(
                    "interop active/ready/acquired/timeline=" + interop.active() + "/"
                            + interop.frameReady() + "/" + interop.frameAcquired() + "/"
                            + interop.timelineSync()
                            + "  native/display generation=" + interop.generation() + "/"
                            + copy.displayedGeneration(),
                    interop.active() ? COLOR_FRAME : COLOR_WARNING);
            out.line(
                    "display generation=" + copy.displayedGeneration()
                            + "  copy count/gaps=" + copy.copyCount() + "/"
                            + copy.generationGaps()
                            + "  slot=" + copy.displayedSlotIndex()
                            + "  size=" + copy.displayedWidth() + "x" + copy.displayedHeight(),
                    copy.copyCount() > 0L ? COLOR_FRAME : COLOR_WARNING);
            out.line(
                    "interop sync us last/EMA/max=" + interop.lastSyncMicros() + "/"
                            + interop.emaSyncMicros() + "/" + interop.maxSyncMicros()
                            + "  vk enqueue=" + copy.lastCopyMicros() + "/"
                            + copy.emaCopyMicros() + "/" + copy.maxCopyMicros(),
                    COLOR_FRAME);
            out.line(
                    "frame produced/copied/unchanged polls=" + diagnostics.producedFrameCount()
                            + "/" + diagnostics.copiedFrameCount() + "/"
                            + diagnostics.unchangedPollCount()
                            + "  native age=" + diagnostics.frameAgeMicros() + "us",
                    COLOR_FRAME);
            out.line(
                    "frame leases active/peak=" + diagnostics.activeFrameLeases() + "/"
                            + diagnostics.peakFrameLeases()
                            + "  slots=" + diagnostics.frameSlotCount()
                            + "  dropped=" + diagnostics.droppedDisplayUpdates()
                            + "  producer waits=" + interop.producerWaitCount(),
                    COLOR_FRAME);
            out.line(
                    "shared buffer=" + buffer.state()
                            + "  allocated/bound=" + buffer.allocated() + "/"
                            + buffer.nativeBound()
                            + "  capacity=" + buffer.capacityWidth() + "x"
                            + buffer.capacityHeight()
                            + "  slot/alloc MiB=" + mebibytes(buffer.logicalBytes()) + "/"
                            + mebibytes(buffer.allocationBytes()),
                    buffer.nativeBound() ? COLOR_FRAME : COLOR_WARNING);
            out.line(
                    "CPU fallback upload us last/EMA/max=" + presentation.lastUploadMicros()
                            + "/" + presentation.emaUploadMicros() + "/"
                            + presentation.maxUploadMicros()
                            + "  count/gaps=" + presentation.uploadCount() + "/"
                            + presentation.generationGaps(),
                    COLOR_FRAME);

            out.section("[ OUTPUT / SWAPCHAIN ]", COLOR_FRAME);
            out.line(
                    "colorspace extension "
                            + VulkanCapabilityProbe.colorspaceBootstrap().summary(),
                    VulkanCapabilityProbe.colorspaceBootstrap().extensionRequested()
                            ? COLOR_FRAME : COLOR_WARNING);
            out.line(
                    "scRGB request/attempted/selected=" + swapchain.requested() + "/"
                            + swapchain.attempted() + "/" + swapchain.scRgbSelected()
                            + "  active=" + swapchain.activeSurfaceFormat().summary(),
                    swapchain.scRgbSelected() ? COLOR_FRAME : COLOR_WARNING);
            out.line(
                    "output=" + (hdrOutput.active()
                            ? "linear scRGB (composited SDR prototype)"
                            : "sRGB SDR")
                            + "  size=" + hdrOutput.width() + "x" + hdrOutput.height()
                            + "  paper white=" + hdrOutput.paperWhiteNits() + " nits"
                            + "  scale=" + oneDecimal(hdrOutput.scRgbScale()),
                    hdrOutput.active() ? COLOR_FRAME : COLOR_STATIC);
            out.line(
                    "scRGB conversion us last/EMA/max="
                            + timing(hdrOutput.lastConversionMicros(),
                            hdrOutput.emaConversionMicros(),
                            hdrOutput.maxConversionMicros())
                            + "  count=" + hdrOutput.conversionCount()
                            + "  reason=" + swapchain.reason(),
                    hdrOutput.error().isEmpty() ? COLOR_FRAME : COLOR_ERROR);
            if (!hdrOutput.error().isEmpty()) {
                out.line("scRGB conversion error=" + hdrOutput.error(), COLOR_ERROR);
            }

            out.section("[ PERFORMANCE: last / EMA / max us ]", COLOR_TIMING);
            out.line(
                    "camera queue=" + timing(runtime.lastCameraCallMicros(),
                            runtime.emaCameraCallMicros(), runtime.maxCameraCallMicros())
                            + "  calls=" + runtime.cameraCallCount(),
                    COLOR_TIMING);
            out.line(
                    "frame pull=" + timing(runtime.lastBridgeCallMicros(),
                            runtime.emaBridgeCallMicros(), runtime.maxBridgeCallMicros())
                            + "  polls/skipped=" + runtime.bridgeCallCount() + "/"
                            + runtime.skippedFrameDeliveryCount(),
                    COLOR_TIMING);
            out.line(
                    "section capture=" + timing(capture.lastCaptureMicros(),
                            capture.emaCaptureMicros(), capture.maxCaptureMicros())
                            + "  count/queued/replaced=" + capture.captureCount() + "/"
                            + capture.queuedCount() + "/" + capture.replacedCount(),
                    COLOR_TIMING);
            out.line(
                    "Java scene=" + timing(scene.lastUpdateMicros(), scene.emaUpdateMicros(),
                            scene.maxUpdateMicros())
                            + "  accepted/pending=" + scene.lastAcceptedSections() + "/"
                            + scene.pendingCommit(),
                    COLOR_TIMING);
            out.line(
                    "FFI upsert=" + timing(scene.lastUpsertMicros(), scene.emaUpsertMicros(),
                            scene.maxUpsertMicros())
                            + "  remove=" + timing(scene.lastRemoveMicros(),
                            scene.emaRemoveMicros(), scene.maxRemoveMicros()),
                    COLOR_TIMING);
            out.line(
                    "FFI commit=" + timing(scene.lastCommitMicros(), scene.emaCommitMicros(),
                            scene.maxCommitMicros())
                            + "  native commit=" + timing(diagnostics.lastSceneCommitMicros(),
                            diagnostics.emaSceneCommitMicros(), diagnostics.maxSceneCommitMicros()),
                    COLOR_TIMING);
            out.line(
                    "Cycles delta=" + timing(diagnostics.lastSceneDeltaMicros(),
                            diagnostics.emaSceneDeltaMicros(), diagnostics.maxSceneDeltaMicros())
                            + "  render start=" + timing(diagnostics.lastRenderStartMicros(),
                            diagnostics.emaRenderStartMicros(), diagnostics.maxRenderStartMicros()),
                    COLOR_TIMING);
            out.line(
                    "scene queue=" + timing(diagnostics.lastSceneQueueMicros(),
                            diagnostics.emaSceneQueueMicros(), diagnostics.maxSceneQueueMicros())
                            + "  reset wait=" + timing(diagnostics.lastResetWaitMicros(),
                            diagnostics.emaResetWaitMicros(), diagnostics.maxResetWaitMicros()),
                    COLOR_TIMING);
            out.line(
                    "device update=" + timing(diagnostics.lastDeviceUpdateMicros(),
                            diagnostics.emaDeviceUpdateMicros(), diagnostics.maxDeviceUpdateMicros())
                            + "  geometry=" + timing(diagnostics.lastGeometryUpdateMicros(),
                            diagnostics.emaGeometryUpdateMicros(),
                            diagnostics.maxGeometryUpdateMicros()),
                    COLOR_TIMING);
            out.line(
                    "BVH status=" + timing(diagnostics.lastBvhUpdateMicros(),
                            diagnostics.emaBvhUpdateMicros(), diagnostics.maxBvhUpdateMicros())
                            + "  first frame=" + timing(
                            diagnostics.lastSceneFirstFrameMicros(),
                            diagnostics.emaSceneFirstFrameMicros(),
                            diagnostics.maxSceneFirstFrameMicros()),
                    COLOR_TIMING);
            out.line(
                    "native display=" + timing(diagnostics.lastConvertMicros(),
                            diagnostics.emaConvertMicros(), diagnostics.maxConvertMicros())
                            + "  native copy=" + timing(diagnostics.lastCopyMicros(),
                            diagnostics.emaCopyMicros(), diagnostics.maxCopyMicros()),
                    COLOR_TIMING);
            out.line(
                    "largest EMA=" + largestEmaStage(runtime, presentation, capture, scene,
                            diagnostics),
                    COLOR_WARNING);

            out.section("[ DYNAMIC STATE / CACHES ]", COLOR_STATE);
            out.line(
                    "revision settings/scene/camera/frame=" + diagnostics.settingsRevision()
                            + "/" + diagnostics.sceneRevision() + "/"
                            + diagnostics.cameraRevision() + "/"
                            + diagnostics.frameGeneration()
                            + "  sections=" + diagnostics.sectionCount()
                            + "  reset=" + diagnostics.resetName(),
                    COLOR_STATE);
            out.line(
                    "pass cache entries MiB used/budget=" + diagnostics.passCacheEntryCount()
                            + " " + mebibytes(diagnostics.passCacheBytes()) + "/"
                            + mebibytes(diagnostics.passCacheBudgetBytes())
                            + "  hit/evict=" + diagnostics.passCacheHitCount() + "/"
                            + diagnostics.passCacheEvictionCount(),
                    COLOR_STATE);
            out.line(
                    "pass masks raw/denoised/registered=0x"
                            + Long.toHexString(diagnostics.cachedRawPassMask()) + "/0x"
                            + Long.toHexString(diagnostics.cachedDenoisedPassMask()) + "/0x"
                            + Long.toHexString(diagnostics.registeredPassMask())
                            + "  registry hit/rebuild=" + diagnostics.passRegistryHitCount()
                            + "/" + diagnostics.passRegistryRebuildCount(),
                    COLOR_STATE);
            out.line(
                    "pass descriptor=" + pass.semanticName()
                            + " components=" + pass.sourceComponentCount() + "->"
                            + pass.displayComponentCount() + " " + pass.pixelFormatName()
                            + " flags=0x" + Integer.toHexString(pass.flags()),
                    COLOR_STATE);
            out.line(
                    "OCIO LUT upload us=" + timing(presentation.lastColorLutUploadMicros(),
                            presentation.emaColorLutUploadMicros(),
                            presentation.maxColorLutUploadMicros())
                            + "  count/view=" + presentation.colorLutUploadCount() + "/"
                            + presentation.colorLutViewTransform(),
                    COLOR_STATE);

            LabPbrResources.Discovery pbr = sceneManager.pbrDiscovery();
            LabPbrAtlasBuilder.Atlases pbrAtlases = sceneManager.pbrAtlases();
            out.section("[ PBR RESOURCE PACK ]", COLOR_PBR);
            out.line(
                    "requested/effective=" + settings.pbrMode().name() + "/"
                            + pbr.format().name()
                            + "  declaration=" + valueOrDash(pbr.rawFormat())
                            + "  pack=" + valueOrDash(pbr.declarationSourcePackId()),
                    pbr.format() == LabPbrResources.Format.UNSUPPORTED
                            ? COLOR_WARNING : COLOR_PBR);
            out.line(
                    "atlas sprites=" + pbr.spriteCount()
                            + "  normal=" + pbr.normalCount() + " ("
                            + percent(pbr.normalCoverage()) + "%)"
                            + "  specular=" + pbr.specularCount() + " ("
                            + percent(pbr.specularCoverage()) + "%)",
                    COLOR_PBR);
            out.line(
                    "decoded normal/specular=" + pbrAtlases.decodedNormals() + "/"
                            + pbrAtlases.decodedSpeculars()
                            + "  atlas=" + pbrAtlases.width() + "x" + pbrAtlases.height()
                            + " x2  MiB=" + mebibytes(pbrAtlases.byteSize()),
                    COLOR_PBR);
            int pbrErrorCount = pbr.discoveryErrors()
                    + pbrAtlases.sizeMismatches() + pbrAtlases.decodeErrors();
            out.line(
                    "errors discovery/size/decode=" + pbr.discoveryErrors() + "/"
                            + pbrAtlases.sizeMismatches() + "/" + pbrAtlases.decodeErrors()
                            + "  declarationError=" + valueOrDash(pbr.declarationError()),
                    pbrErrorCount > 0 ? COLOR_WARNING : COLOR_PBR);
            out.line(
                    "channels normal(DX)/roughness/metal/F0/emission="
                            + (pbrAtlases.decodedNormals() > 0) + "/"
                            + (pbrAtlases.decodedSpeculars() > 0) + "/"
                            + (pbrAtlases.decodedSpeculars() > 0) + "/"
                            + (pbrAtlases.decodedSpeculars() > 0) + "/"
                            + (pbrAtlases.decodedSpeculars() > 0)
                            + "  strength/emission=" + settings.pbrNormalStrength() + "/"
                            + settings.pbrEmissionScale()
                            + "  fallback roughness/F0=" + settings.pbrFallbackRoughness()
                            + "/" + settings.pbrFallbackF0(),
                    COLOR_PBR);

            out.section("[ FIXED CAPABILITIES / CURRENT CONFIG ]", COLOR_STATIC);
            out.line(
                    "Cycles ABI=" + NativeBridge.ABI_VERSION
                            + "  device=" + diagnostics.deviceName()
                            + "  UUID=" + diagnostics.deviceUuid(),
                    COLOR_STATIC);
            out.line(
                    vulkan.vulkan()
                            ? "Vulkan=" + vulkan.deviceName()
                                    + "  UUID match="
                                    + vulkan.uuidMatches(diagnostics.deviceUuid())
                                    + "  interop available/enabled="
                                    + vulkan.interopExtensionsAvailable() + "/"
                                    + vulkan.interopExtensionsEnabled()
                            : "Vulkan probe unavailable: " + vulkan.error(),
                    vulkan.vulkan() ? COLOR_STATIC : COLOR_WARNING);
            out.line(
                    "color display/view/look=" + settings.displayDevice().name()
                            + "/" + settings.viewTransform().name()
                            + "/" + settings.colorLook().name()
                            + " effective="
                            + settings.viewTransform().effectiveFor(settings.displayDevice()).name()
                            + "/" + settings.colorLook().effectiveNativeId(
                                    settings.viewTransform().effectiveFor(settings.displayDevice()))
                            + " supported="
                            + capabilities.supportsViewTransform(settings.viewTransform())
                            + "  OCIO=" + capabilities.colorConfigStateName()
                            + "  working=" + settings.workingSpace().name()
                            + "  output=" + (hdrOutput.active()
                            ? "sRGB view -> linear scRGB"
                            : "sRGB SDR"),
                    COLOR_STATIC);
            out.line(
                    "white balance=" + (settings.whiteBalance() ? "on" : "off")
                            + "  temperature/tint=" + settings.whiteBalanceTemperature()
                            + "K/" + settings.whiteBalanceTint(),
                    COLOR_STATIC);
            out.line(
                    "resolution config=" + settings.resolutionPercentage() + "%"
                            + "  interactive=" + settings.interactiveResolutionPercentage() + "%"
                            + "  dynamic=" + settings.dynamicResolution()
                            + "  sampling pattern=" + settings.samplingPattern().name() + "/"
                            + diagnostics.samplingPatternName(),
                    COLOR_STATIC);
            out.line(
                    "atmosphere sun elevation/rotation="
                            + settings.atmosphereSunElevationDegrees() + "/"
                            + settings.atmosphereSunRotationDegrees() + "deg"
                            + "  size=" + settings.atmosphereSunSizeDegrees() + "deg"
                            + "  intensity=" + settings.atmosphereSunIntensity()
                            + "  air/aerosol/ozone=" + settings.atmosphereAirDensity() + "/"
                            + settings.atmosphereAerosolDensity() + "/"
                            + settings.atmosphereOzoneDensity(),
                    COLOR_STATIC);
            out.line(
                    "camera projection=" + settings.projectionMode().name() + "/"
                            + diagnostics.projectionModeName()
                            + "  FOV=" + degrees(diagnostics.verticalFovRadians()) + "deg"
                            + "  lens/sensor=" + settings.focalLengthMm() + "/"
                            + settings.sensorWidthMm() + "mm"
                            + "  clip=" + diagnostics.effectiveCameraClipNear() + "/"
                            + diagnostics.effectiveCameraClipFar(),
                    COLOR_STATIC);
            out.line(
                    "DOF configured/effective=" + settings.depthOfField() + "/"
                            + diagnostics.depthOfField()
                            + "  focus=" + diagnostics.focusDistance()
                            + "  f/=" + diagnostics.fStop()
                            + "  aperture=" + diagnostics.apertureSize(),
                    COLOR_STATIC);
        } catch (RuntimeException error) {
            out.section("[ CYCLES DEBUG ERROR ]", COLOR_ERROR);
            out.line("Diagnostics failed: " + error.getMessage(), COLOR_ERROR);
        }
    }

    private static String timing(long last, long ema, long max) {
        return last + "/" + ema + "/" + max;
    }

    private static double oneDecimal(double value) {
        return Math.round(value * 10.0D) / 10.0D;
    }

    private static double mebibytes(long bytes) {
        return Math.round(bytes / 104_857.6D) / 10.0D;
    }

    private static double milliseconds(long nanos) {
        return Math.round(nanos / 100_000.0D) / 10.0D;
    }

    private static double degrees(float radians) {
        return Math.round(Math.toDegrees(radians) * 10.0D) / 10.0D;
    }

    private static int percent(float value) {
        return Math.round(Math.clamp(value, 0.0F, 1.0F) * 100.0F);
    }

    private static String valueOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static int sampleProgressPercent(NativeBridge.Diagnostics diagnostics) {
        if (diagnostics.targetSampleCount() <= 0) {
            return 0;
        }
        return Math.min(100, Math.round(
                diagnostics.sampleCount() * 100.0F / diagnostics.targetSampleCount()));
    }

    private static String largestEmaStage(
            RuntimeStats runtime,
            CyclesFramePresenter.Telemetry presentation,
            SectionGeometryCollector.Telemetry capture,
            SectionSceneManager.Telemetry scene,
            NativeBridge.Diagnostics diagnostics) {
        String[] names = {
            "camera queue", "frame pull", "GPU upload", "mesh capture", "Java scene",
            "FFI upsert", "FFI commit", "Cycles commit", "Cycles delta",
            "render start", "scene queue", "reset wait", "device update",
            "geometry update", "BVH status", "scene first frame",
            "native display", "native copy"
        };
        long[] micros = {
            runtime.emaCameraCallMicros(), runtime.emaBridgeCallMicros(),
            presentation.emaUploadMicros(), capture.emaCaptureMicros(),
            scene.emaUpdateMicros(), scene.emaUpsertMicros(), scene.emaCommitMicros(),
            diagnostics.emaSceneCommitMicros(), diagnostics.emaSceneDeltaMicros(),
            diagnostics.emaRenderStartMicros(), diagnostics.emaSceneQueueMicros(),
            diagnostics.emaResetWaitMicros(), diagnostics.emaDeviceUpdateMicros(),
            diagnostics.emaGeometryUpdateMicros(), diagnostics.emaBvhUpdateMicros(),
            diagnostics.emaSceneFirstFrameMicros(), diagnostics.emaConvertMicros(),
            diagnostics.emaCopyMicros()
        };
        int largest = 0;
        for (int index = 1; index < micros.length; index++) {
            if (micros[index] > micros[largest]) {
                largest = index;
            }
        }
        return names[largest] + " " + micros[largest] + "us";
    }

    record RuntimeStats(
            long bridgeCallCount,
            long lastBridgeCallMicros,
            long emaBridgeCallMicros,
            long maxBridgeCallMicros,
            long cameraCallCount,
            long lastCameraCallMicros,
            long emaCameraCallMicros,
            long maxCameraCallMicros,
            long skippedFrameDeliveryCount) {
    }

    private static final class Writer {
        private final GuiGraphicsExtractor graphics;
        private final Minecraft minecraft;
        private int y;

        private Writer(GuiGraphicsExtractor graphics, Minecraft minecraft, int y) {
            this.graphics = graphics;
            this.minecraft = minecraft;
            this.y = y;
        }

        private void section(String text, int color) {
            if (y != TOP) {
                y += 2;
            }
            line(text, color);
        }

        private void line(String text, int color) {
            graphics.text(minecraft.font, text, X, y, color);
            y += LINE_HEIGHT;
        }
    }

    private static final class CompletedFrameRate {
        private static final long RATE_WINDOW_NANOS = 1_000_000_000L;

        private int source;
        private long previousCounter = -1L;
        private long windowCounter;
        private long windowStartedNanos;
        private long lastAdvanceNanos;
        private long latestIntervalNanos;
        private double framesPerSecond;

        private Snapshot update(int nextSource, long counter, long now) {
            if (source != nextSource || previousCounter < 0L || counter < previousCounter) {
                source = nextSource;
                previousCounter = counter;
                windowCounter = counter;
                windowStartedNanos = now;
                lastAdvanceNanos = now;
                latestIntervalNanos = 0L;
                framesPerSecond = 0.0D;
                return snapshot(now);
            }

            long completed = counter - previousCounter;
            if (completed > 0L) {
                latestIntervalNanos = Math.max(0L, now - lastAdvanceNanos) / completed;
                lastAdvanceNanos = now;
                previousCounter = counter;
            }

            long elapsed = now - windowStartedNanos;
            if (elapsed >= RATE_WINDOW_NANOS) {
                framesPerSecond = (counter - windowCounter) * 1_000_000_000.0D / elapsed;
                windowCounter = counter;
                windowStartedNanos = now;
            }
            return snapshot(now);
        }

        private Snapshot snapshot(long now) {
            return new Snapshot(
                    framesPerSecond,
                    latestIntervalNanos,
                    Math.max(0L, now - lastAdvanceNanos));
        }

        private void reset() {
            previousCounter = -1L;
            framesPerSecond = 0.0D;
        }

        private record Snapshot(
                double framesPerSecond,
                long latestIntervalNanos,
                long outputAgeNanos) {
        }
    }
}

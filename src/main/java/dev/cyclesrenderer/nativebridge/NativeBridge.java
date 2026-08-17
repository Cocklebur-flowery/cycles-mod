package dev.cyclesrenderer.nativebridge;

import dev.cyclesrenderer.config.CyclesRenderSettings;
import dev.cyclesrenderer.scene.SectionGeometrySnapshot;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

public final class NativeBridge {
    public static final int ABI_VERSION = 45;
    public static final int DEVICE_UPDATE_PHASE_COUNT = 8;
    public static final int PIXEL_FORMAT_RGBA16_FLOAT = 2;
    public static final int PIXEL_FORMAT_RGBA32_FLOAT = 3;
    public static final int CAMERA_FOCUS_DISTANCE_VALID = 1;

    private static final String LIBRARY_PATH_PROPERTY = "cyclesrenderer.nativeLibrary";
    private static final int TEST_WIDTH = 16;
    private static final int TEST_HEIGHT = 16;
    private static final long TEST_FRAME_ID = 7L;
    public static final long CAPABILITY_SETTINGS = 1L << 0;
    public static final long CAPABILITY_PASS_VIEWER = 1L << 1;
    public static final long CAPABILITY_DENOISE = 1L << 2;
    public static final long CAPABILITY_OPTIX_COMPILED = 1L << 3;
    public static final long CAPABILITY_CUDA_COMPILED = 1L << 4;
    public static final long CAPABILITY_OIDN_COMPILED = 1L << 5;
    public static final long CAPABILITY_OCIO_COMPILED = 1L << 6;
    public static final long CAPABILITY_DLSS_EXPERIMENTAL_COMPILED = 1L << 7;

    private static NativeBridgeSession bridgeState;

    static {
        NativeLayouts.validate();
    }

    private NativeBridge() {
    }

    public static ProbeResult probe() {
        close();
        String configuredPath = System.getProperty(LIBRARY_PATH_PROPERTY);
        if (configuredPath == null || configuredPath.isBlank()) {
            return ProbeResult.failure("missing system property " + LIBRARY_PATH_PROPERTY);
        }
        Path libraryPath = Path.of(configuredPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(libraryPath)) {
            return ProbeResult.failure("native library not found at " + libraryPath);
        }

        NativeBridgeSession loadedState = null;
        try {
            loadedState = NativeBridgeSession.open(libraryPath);
            long checksum = verifyTestFrame(
                    loadedState.fillTestFrame(TEST_WIDTH, TEST_HEIGHT, TEST_FRAME_ID));
            bridgeState = loadedState;
            return ProbeResult.success(
                    loadedState.buildInfo() + ", " + loadedState.rendererInfo()
                            + ", verified " + TEST_WIDTH + "x" + TEST_HEIGHT
                            + " RGBA frame, checksum=" + Long.toUnsignedString(checksum));
        } catch (Throwable error) {
            if (loadedState != null) {
                loadedState.close();
            }
            rethrowFatalError(error);
            return ProbeResult.failure(describe(error));
        }
    }

    public static void resetScene(SectionGeometrySnapshot.SceneResources resources) {
        invoke("native scene reset", state -> state.resetScene(resources));
    }

    public static void upsertSection(SectionGeometrySnapshot snapshot) {
        invoke("native section upsert", state -> state.upsertSection(snapshot));
    }

    public static void removeSection(long sectionId) {
        invoke("native section removal", state -> state.removeSection(sectionId));
    }

    public static void commitScene() {
        invoke("native scene commit", NativeBridgeSession::commitScene);
    }

    public static RenderedFrame renderFrame(
            int width,
            int height,
            long frameId,
            CameraInput cameraInput) {
        NativeBridgeSession state = requireState();
        try {
            return state.renderFrame(width, height, frameId, cameraInput);
        } catch (Throwable error) {
            rethrowFatalError(error);
            throw new IllegalStateException("native frame call failed: " + describe(error), error);
        }
    }

    public static void updateCamera(
            int width,
            int height,
            long frameId,
            CameraInput cameraInput) {
        NativeBridgeSession state = requireState();
        try {
            state.updateCamera(width, height, frameId, cameraInput);
        } catch (Throwable error) {
            rethrowFatalError(error);
            throw new IllegalStateException("native camera update failed: " + describe(error), error);
        }
    }

    public static AcquiredFrame acquireFrame(long previousGeneration) {
        NativeBridgeSession state = requireState();
        try {
            return state.acquireFrame(previousGeneration);
        } catch (Throwable error) {
            rethrowFatalError(error);
            throw new IllegalStateException("native frame acquire failed: " + describe(error), error);
        }
    }

    public static String rendererInfo() {
        NativeBridgeSession state = requireState();
        try {
            return state.rendererInfo();
        } catch (Throwable error) {
            rethrowFatalError(error);
            throw new IllegalStateException("native renderer info failed: " + describe(error), error);
        }
    }

    public static boolean isReady() {
        return bridgeState != null;
    }

    public static void applySettings(CyclesRenderSettings settings) {
        invoke("native settings update", state -> state.applySettings(settings));
    }

    public static Capabilities capabilities() {
        NativeBridgeSession state = requireState();
        try {
            return state.capabilities();
        } catch (Throwable error) {
            rethrowFatalError(error);
            throw new IllegalStateException("native capability query failed: " + describe(error), error);
        }
    }

    public static String colorManagementInfo() {
        return requireState().colorManagementInfo();
    }

    public static ColorLut colorLut(
            CyclesRenderSettings.DisplayDevice displayDevice,
            CyclesRenderSettings.ViewTransform viewTransform,
            CyclesRenderSettings.ColorLook colorLook,
            CyclesRenderSettings.WorkingSpace workingSpace) {
        NativeBridgeSession state = requireState();
        try {
            return state.colorLut(displayDevice, viewTransform, colorLook, workingSpace);
        } catch (Throwable error) {
            rethrowFatalError(error);
            throw new IllegalStateException("native color LUT query failed: "
                    + describe(error), error);
        }
    }

    public static Diagnostics diagnostics() {
        NativeBridgeSession state = requireState();
        try {
            return state.diagnostics();
        } catch (Throwable error) {
            rethrowFatalError(error);
            throw new IllegalStateException("native diagnostics query failed: " + describe(error), error);
        }
    }

    public static void bindVulkanInteropBuffer(
            int width,
            int height,
            long allocationBytes,
            long memoryHandle,
            long readySemaphoreHandle,
            long releaseSemaphoreHandle,
            String deviceUuid,
            int slotCount,
            int slotStrideBytes) {
        NativeBridgeSession state = requireState();
        try {
            state.bindVulkanInteropBuffer(
                    width, height, allocationBytes, memoryHandle,
                    readySemaphoreHandle, releaseSemaphoreHandle, deviceUuid,
                    slotCount, slotStrideBytes);
        } catch (Throwable error) {
            rethrowFatalError(error);
            throw new IllegalStateException(
                    "native Vulkan interop bind failed: " + describe(error), error);
        }
    }

    public static void unbindVulkanInteropBuffer() {
        invoke("native Vulkan interop unbind", NativeBridgeSession::unbindVulkanInteropBuffer);
    }

    public static void closeWin32Handle(long handle) {
        if (handle == 0L) {
            return;
        }
        NativeBridgeSession state = requireState();
        try {
            state.closeWin32Handle(handle);
        } catch (Throwable error) {
            rethrowFatalError(error);
            throw new IllegalStateException(
                    "native Win32 handle close failed: " + describe(error), error);
        }
    }

    public static VulkanInteropState vulkanInteropState() {
        NativeBridgeSession state = requireState();
        try {
            return state.vulkanInteropState();
        } catch (Throwable error) {
            rethrowFatalError(error);
            throw new IllegalStateException(
                    "native Vulkan interop state query failed: " + describe(error), error);
        }
    }

    public static VulkanInteropState acquireVulkanInteropFrame(
            long previousGeneration) {
        NativeBridgeSession state = requireState();
        try {
            return state.acquireVulkanInteropFrame(previousGeneration);
        } catch (Throwable error) {
            rethrowFatalError(error);
            throw new IllegalStateException(
                    "native Vulkan interop frame acquire failed: "
                            + describe(error), error);
        }
    }

    public static VulkanInteropFrame acquireVulkanReprojectionFrame(
            long previousGeneration) {
        NativeBridgeSession state = requireState();
        try {
            return state.acquireVulkanReprojectionFrame(previousGeneration);
        } catch (Throwable error) {
            rethrowFatalError(error);
            throw new IllegalStateException(
                    "native Vulkan reprojection frame acquire failed: "
                            + describe(error), error);
        }
    }

    public static void releaseVulkanInteropFrame(long generation) {
        NativeBridgeSession state = requireState();
        try {
            state.releaseVulkanInteropFrame(generation);
        } catch (Throwable error) {
            rethrowFatalError(error);
            throw new IllegalStateException(
                    "native Vulkan interop frame release failed: "
                            + describe(error), error);
        }
    }

    public static PassDescriptor passDescriptor(int passId) {
        NativeBridgeSession state = requireState();
        try {
            return state.passDescriptor(passId);
        } catch (Throwable error) {
            rethrowFatalError(error);
            throw new IllegalStateException("native pass descriptor query failed: "
                    + describe(error), error);
        }
    }

    public static void close() {
        NativeBridgeSession state = bridgeState;
        bridgeState = null;
        if (state != null) {
            state.close();
        }
    }

    private static void invoke(String operation, BridgeCall call) {
        NativeBridgeSession state = requireState();
        try {
            call.run(state);
        } catch (Throwable error) {
            rethrowFatalError(error);
            throw new IllegalStateException(operation + " failed: " + describe(error), error);
        }
    }

    private static NativeBridgeSession requireState() {
        NativeBridgeSession state = bridgeState;
        if (state == null) {
            throw new IllegalStateException("native bridge is not initialized");
        }
        return state;
    }

    private static long verifyTestFrame(ByteBuffer pixels) {
        long checksum = 0;
        for (int y = 0; y < TEST_HEIGHT; y++) {
            for (int x = 0; x < TEST_WIDTH; x++) {
                int offset = (y * TEST_WIDTH + x) * 4;
                int[] expected = {
                        (x * 17 + (int) TEST_FRAME_ID) & 0xFF,
                        (y * 17 + (int) (TEST_FRAME_ID * 3)) & 0xFF,
                        ((x ^ y) * 15 + (int) (TEST_FRAME_ID * 5)) & 0xFF,
                        0xFF
                };
                for (int channel = 0; channel < expected.length; channel++) {
                    int actual = Byte.toUnsignedInt(pixels.get(offset + channel));
                    if (actual != expected[channel]) {
                        throw new IllegalStateException(
                                "test frame mismatch at (" + x + "," + y + ") channel " + channel);
                    }
                    checksum = (checksum * 31 + actual) & 0xFFFFFFFFL;
                }
            }
        }
        return checksum;
    }

    static void rethrowFatalError(Throwable error) {
        if (error instanceof Error fatalError && !(fatalError instanceof LinkageError)) {
            throw fatalError;
        }
    }

    static String describe(Throwable error) {
        String detail = error.getMessage();
        return error.getClass().getSimpleName() + (detail == null ? "" : ": " + detail);
    }

    @FunctionalInterface
    private interface BridgeCall {
        void run(NativeBridgeSession state) throws Throwable;
    }

    public record CameraInput(
            double positionX,
            double positionY,
            double positionZ,
            float rotationX,
            float rotationY,
            float rotationZ,
            float rotationW,
            float verticalFovRadians,
            float depthFar,
            float focusDistance,
            int flags) {
    }

    public record RenderedFrame(
            boolean ready,
            boolean updated,
            int width,
            int height,
            long generation,
            int sampleCount,
            ByteBuffer pixels) {
    }

    public static final class AcquiredFrame implements AutoCloseable {
        private NativeFrameMarshaller.FrameLease lease;
        private final boolean ready;
        private final boolean updated;
        private final int width;
        private final int height;
        private final long generation;
        private final int sampleCount;
        private final int pixelFormat;
        private final ByteBuffer pixels;
        private boolean closed;

        AcquiredFrame(
                NativeFrameMarshaller.FrameLease lease,
                boolean ready,
                boolean updated,
                int width,
                int height,
                long generation,
                int sampleCount,
                int pixelFormat,
                ByteBuffer pixels) {
            this.lease = lease;
            this.ready = ready;
            this.updated = updated;
            this.width = width;
            this.height = height;
            this.generation = generation;
            this.sampleCount = sampleCount;
            this.pixelFormat = pixelFormat;
            this.pixels = pixels;
        }

        public boolean ready() {
            return ready;
        }

        public boolean updated() {
            return updated;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public long generation() {
            return generation;
        }

        public int sampleCount() {
            return sampleCount;
        }

        public int pixelFormat() {
            return pixelFormat;
        }

        public ByteBuffer pixels() {
            if (closed) {
                throw new IllegalStateException("native frame lease is closed");
            }
            return pixels;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            NativeFrameMarshaller.FrameLease currentLease = lease;
            lease = null;
            if (currentLease != null) {
                currentLease.close();
            }
        }
    }

    public record Capabilities(
            long flags,
            long passMask,
            int denoiserMask,
            int deviceMask,
            int maximumWidth,
            int maximumHeight,
            int deviceCount,
            int colorTransformMask,
            int colorLutEdgeLength,
            int colorLutPixelFormat,
            int colorConfigState) {
        public boolean has(long capability) {
            return (flags & capability) != 0L;
        }

        public boolean supportsPass(CyclesRenderSettings.PassView pass) {
            return (passMask & (1L << pass.nativeId())) != 0L;
        }

        public boolean supportsViewTransform(CyclesRenderSettings.ViewTransform viewTransform) {
            return (colorTransformMask & (1 << viewTransform.nativeId())) != 0;
        }

        public String colorConfigStateName() {
            return switch (colorConfigState) {
                case 1 -> "ready";
                case 2 -> "error";
                default -> "unavailable";
            };
        }

        public String colorLutPixelFormatName() {
            return colorLutPixelFormat == PIXEL_FORMAT_RGBA32_FLOAT
                    ? "RGBA32_FLOAT"
                    : "unknown";
        }

        public boolean optixDenoiserAvailable() {
            return (denoiserMask & 1) != 0;
        }

        public boolean oidnDenoiserAvailable() {
            return (denoiserMask & 2) != 0;
        }

        public boolean dlssExperimentalDenoiserAvailable() {
            return (denoiserMask & 4) != 0;
        }

        public String denoiserSummary() {
            return "OptiX=" + availability(optixDenoiserAvailable())
                    + ", OIDN=" + availability(oidnDenoiserAvailable())
                    + ", DLSS-RR(exp)=" + availability(dlssExperimentalDenoiserAvailable());
        }

        private static String availability(boolean value) {
            return value ? "available" : "unavailable";
        }
    }

    public record ColorLutDescriptor(
            int viewTransform,
            int edgeLength,
            int width,
            int height,
            int pixelFormat,
            int flags,
            long pixelByteCount,
            float shaperLog2Min,
            float shaperLog2Max,
            float shaperEpsilon,
            int interpolation,
            int colorLook,
            int workingSpace,
            int displayDevice) {
    }

    public record ColorLut(ColorLutDescriptor descriptor, ByteBuffer pixels) {
        public ColorLut {
            pixels = pixels.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
        }

        @Override
        public ByteBuffer pixels() {
            return pixels.duplicate().order(ByteOrder.nativeOrder());
        }
    }

    public record PassDescriptor(
            int passId,
            int sourceComponentCount,
            int displayComponentCount,
            int pixelFormat,
            int semantic,
            int flags) {
        public String semanticName() {
            return switch (semantic) {
                case 1 -> "color";
                case 2 -> "depth";
                case 3 -> "normal";
                case 4 -> "scalar";
                default -> "unknown";
            };
        }

        public String pixelFormatName() {
            return pixelFormat == PIXEL_FORMAT_RGBA16_FLOAT
                    ? "RGBA16_FLOAT"
                    : "unknown";
        }
    }

    public record VulkanInteropState(
            int flags,
            int width,
            int height,
            int sampleCount,
            long generation,
            long completedFrameCount,
            int lastSyncMicros,
            int emaSyncMicros,
            int maxSyncMicros,
            int slotIndex,
            int slotCount,
            int readySlotCount,
            long producerWaitCount,
            int depthWidth,
            int depthHeight) {
        public boolean bound() {
            return (flags & 1) != 0;
        }

        public boolean active() {
            return (flags & 2) != 0;
        }

        public boolean frameReady() {
            return (flags & 4) != 0;
        }

        public boolean failed() {
            return (flags & 8) != 0;
        }

        public boolean sessionAttached() {
            return (flags & 16) != 0;
        }

        public boolean frameAcquired() {
            return (flags & 32) != 0;
        }

        public boolean timelineSync() {
            return (flags & 64) != 0;
        }
    }

    public record ReprojectionMetadata(
            long generation,
            long frameRevision,
            long cameraRevision,
            long sceneRevision,
            long productionTimeNanos,
            int slotIndex,
            int width,
            int height,
            double positionX,
            double positionY,
            double positionZ,
            float rotationX,
            float rotationY,
            float rotationZ,
            float rotationW,
            float verticalFovRadians,
            float aspect,
            float shiftX,
            float shiftY,
            float nearClip,
            float farClip) {
    }

    public record VulkanInteropFrame(
            VulkanInteropState state,
            ReprojectionMetadata reprojectionMetadata,
            String rejectionReason) {
        public boolean hasReprojectionMetadata() {
            return reprojectionMetadata != null;
        }
    }

    public record Diagnostics(
            long settingsRevision,
            long sceneRevision,
            long cameraRevision,
            long frameGeneration,
            int stateCode,
            int deviceType,
            int effectiveDenoiser,
            int activePassId,
            int width,
            int height,
            int sampleCount,
            int sectionCount,
            int resetLevel,
            boolean frameReady,
            int activeFrameLeases,
            int peakFrameLeases,
            int frameSlotCount,
            int droppedDisplayUpdates,
            int targetSampleCount,
            int samplingState,
            float sampleRate,
            int settlingRemainingMillis,
            long producedFrameCount,
            long copiedFrameCount,
            long copiedByteCount,
            long unchangedPollCount,
            int lastConvertMicros,
            int emaConvertMicros,
            int maxConvertMicros,
            int lastCopyMicros,
            int emaCopyMicros,
            int maxCopyMicros,
            int frameAgeMicros,
            int samplingTransitionCount,
            long sceneCommitCount,
            long sceneDeltaCount,
            long renderStartCount,
            int lastSceneCommitMicros,
            int emaSceneCommitMicros,
            int maxSceneCommitMicros,
            int lastSceneDeltaMicros,
            int emaSceneDeltaMicros,
            int maxSceneDeltaMicros,
            int lastRenderStartMicros,
            int emaRenderStartMicros,
            int maxRenderStartMicros,
            int framePixelFormat,
            long cachedRawPassMask,
            long cachedDenoisedPassMask,
            long passCacheBytes,
            long passCacheBudgetBytes,
            int passCacheEntryCount,
            int passCacheEvictionCount,
            int passCacheHitCount,
            int activeFrameVariant,
            long registeredPassMask,
            int passRegistryRebuildCount,
            int passRegistryHitCount,
            int selectedDenoiser,
            int denoiserScheduled,
            int effectiveDenoiserStartSample,
            int denoiserScheduleReason,
            int denoiserScheduleRunCount,
            int denoiserScheduleSkipCount,
            int samplingPattern,
            float effectiveCameraClipNear,
            float effectiveCameraClipFar,
            int projectionMode,
            float verticalFovRadians,
            boolean depthOfField,
            float focusDistance,
            float fStop,
            float apertureSize,
            int apertureBlades,
            float apertureRotationRadians,
            float apertureRatio,
            String deviceUuid,
            long sceneTimingRevision,
            long sceneTimingCount,
            int lastSceneQueueMicros,
            int emaSceneQueueMicros,
            int maxSceneQueueMicros,
            int lastResetWaitMicros,
            int emaResetWaitMicros,
            int maxResetWaitMicros,
            int lastDeviceUpdateMicros,
            int emaDeviceUpdateMicros,
            int maxDeviceUpdateMicros,
            int lastGeometryUpdateMicros,
            int emaGeometryUpdateMicros,
            int maxGeometryUpdateMicros,
            int lastBvhUpdateMicros,
            int emaBvhUpdateMicros,
            int maxBvhUpdateMicros,
            int lastSceneFirstFrameMicros,
            int emaSceneFirstFrameMicros,
            int maxSceneFirstFrameMicros,
            int cameraType,
            int panoramaType,
            int activeDevicePhase,
            int activeDevicePhaseMicros,
            int[] lastDevicePhaseMicros,
            int[] emaDevicePhaseMicros,
            int[] maxDevicePhaseMicros,
            float cameraShiftX,
            float cameraShiftY,
            int lastRenderConfigureMicros,
            int emaRenderConfigureMicros,
            int maxRenderConfigureMicros,
            int lastRenderResetMicros,
            int emaRenderResetMicros,
            int maxRenderResetMicros,
            int lastRenderPrepareMicros,
            int emaRenderPrepareMicros,
            int maxRenderPrepareMicros,
            int lastSessionStartMicros,
            int emaSessionStartMicros,
            int maxSessionStartMicros) {
        public Diagnostics {
            lastDevicePhaseMicros = lastDevicePhaseMicros.clone();
            emaDevicePhaseMicros = emaDevicePhaseMicros.clone();
            maxDevicePhaseMicros = maxDevicePhaseMicros.clone();
        }
        public String stateName() {
            return switch (stateCode) {
                case 1 -> "scene-staging";
                case 2 -> "queued";
                case 3 -> "initializing";
                case 4 -> "scene-ready";
                case 5 -> "rendering";
                case 6 -> "fallback";
                case 7 -> "failed";
                default -> "waiting";
            };
        }

        public String deviceName() {
            return switch (deviceType) {
                case 1 -> "OptiX";
                case 2 -> "CUDA";
                case 3 -> "CPU";
                default -> "Unknown";
            };
        }

        public String denoiserName() {
            return switch (effectiveDenoiser) {
                case 1 -> "OptiX";
                case 2 -> "OpenImageDenoise";
                case 3 -> "DLSS Ray Reconstruction";
                default -> "Off";
            };
        }

        public String selectedDenoiserName() {
            return switch (selectedDenoiser) {
                case 1 -> "OptiX";
                case 2 -> "OpenImageDenoise";
                case 3 -> "DLSS Ray Reconstruction";
                default -> "Off";
            };
        }

        public String denoiserScheduleReasonName() {
            return switch (denoiserScheduleReason) {
                case 1 -> "interactive";
                case 2 -> "settling";
                case 3 -> "debug-pass";
                case 4 -> "still";
                case 5 -> "realtime";
                default -> "disabled";
            };
        }

        public String resetName() {
            return switch (resetLevel) {
                case 1 -> "accumulation";
                case 2 -> "buffer";
                case 3 -> "session";
                default -> "none";
            };
        }

        public String samplingStateName() {
            return switch (samplingState) {
                case 1 -> "interactive";
                case 2 -> "settling";
                case 3 -> "still";
                default -> "idle";
            };
        }

        public String samplingPatternName() {
            return switch (samplingPattern) {
                case 0 -> "SOBOL_BURLEY";
                case 1 -> "TABULATED_SOBOL";
                case 2 -> "BLUE_NOISE_PURE";
                case 3 -> "BLUE_NOISE_FIRST";
                case 4 -> "BLUE_NOISE_ROUND";
                default -> "UNKNOWN(" + samplingPattern + ")";
            };
        }

        public String projectionModeName() {
            return switch (projectionMode) {
                case 0 -> "MINECRAFT_FOV";
                case 1 -> "PHYSICAL_LENS";
                default -> "UNKNOWN(" + projectionMode + ")";
            };
        }

        public String cameraTypeName() {
            return switch (cameraType) {
                case 0 -> "PERSPECTIVE";
                case 1 -> "PANORAMA";
                default -> "UNKNOWN(" + cameraType + ")";
            };
        }

        public String panoramaTypeName() {
            return switch (panoramaType) {
                case 0 -> "EQUIRECTANGULAR";
                case 1 -> "FISHEYE_EQUIDISTANT";
                case 2 -> "FISHEYE_EQUISOLID";
                case 3 -> "MIRRORBALL";
                case 4 -> "FISHEYE_LENS_POLYNOMIAL";
                case 5 -> "EQUIANGULAR_CUBEMAP_FACE";
                case 6 -> "CENTRAL_CYLINDRICAL";
                default -> "UNKNOWN(" + panoramaType + ")";
            };
        }

        public String framePixelFormatName() {
            return switch (framePixelFormat) {
                case 1 -> "RGBA8_UNORM";
                case 2 -> "RGBA16_FLOAT";
                default -> "unknown";
            };
        }

        public String activeFrameVariantName() {
            return activeFrameVariant == 1 ? "denoised" : "raw";
        }

        public String activeFramePassName() {
            return switch (activePassId) {
                case 0 -> "COMBINED";
                case 1 -> "DEPTH";
                case 2 -> "NORMAL";
                case 3 -> "DIFFUSE_COLOR";
                case 4 -> "EMISSION";
                case 5 -> "ROUGHNESS";
                case 6 -> "SAMPLE_COUNT";
                default -> "UNKNOWN";
            };
        }
    }

    public record ProbeResult(boolean success, String message) {
        private static ProbeResult success(String message) {
            return new ProbeResult(true, message);
        }

        private static ProbeResult failure(String message) {
            return new ProbeResult(false, message);
        }
    }
}

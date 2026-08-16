package dev.cyclesrenderer.nativebridge;

import dev.cyclesrenderer.config.CyclesRenderSettings;
import dev.cyclesrenderer.scene.SectionGeometrySnapshot;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.cyclesrenderer.nativebridge.NativeLayouts.*;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

public final class NativeBridge {
    public static final int ABI_VERSION = 43;
    public static final int DEVICE_UPDATE_PHASE_COUNT = 8;
    public static final int PIXEL_FORMAT_RGBA16_FLOAT = 2;
    public static final int PIXEL_FORMAT_RGBA32_FLOAT = 3;
    public static final int CAMERA_FOCUS_DISTANCE_VALID = 1;

    private static final String LIBRARY_PATH_PROPERTY = "cyclesrenderer.nativeLibrary";
    private static final int STRUCT_VERSION = 1;
    private static final int STATUS_OK = 0;
    private static final int FRAME_READY = 1;
    private static final int FRAME_UPDATED = 2;
    private static final int TEST_WIDTH = 16;
    private static final int TEST_HEIGHT = 16;
    private static final long TEST_FRAME_ID = 7L;
    private static final int BUILD_INFO_BYTES = 128;
    private static final int RENDERER_INFO_BYTES = 512;
    private static final int COLOR_INFO_BYTES = 2048;
    private static final int MAX_NATIVE_FRAME_BYTES = 3840 * 2160 * 4;
    public static final long CAPABILITY_SETTINGS = 1L << 0;
    public static final long CAPABILITY_PASS_VIEWER = 1L << 1;
    public static final long CAPABILITY_DENOISE = 1L << 2;
    public static final long CAPABILITY_OPTIX_COMPILED = 1L << 3;
    public static final long CAPABILITY_CUDA_COMPILED = 1L << 4;
    public static final long CAPABILITY_OIDN_COMPILED = 1L << 5;
    public static final long CAPABILITY_OCIO_COMPILED = 1L << 6;
    public static final long CAPABILITY_DLSS_EXPERIMENTAL_COMPILED = 1L << 7;

    private static BridgeState bridgeState;

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

        BridgeState loadedState = null;
        try {
            loadedState = BridgeState.open(libraryPath);
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
        invoke("native scene commit", BridgeState::commitScene);
    }

    public static RenderedFrame renderFrame(
            int width,
            int height,
            long frameId,
            CameraInput cameraInput) {
        BridgeState state = requireState();
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
        BridgeState state = requireState();
        try {
            state.updateCamera(width, height, frameId, cameraInput);
        } catch (Throwable error) {
            rethrowFatalError(error);
            throw new IllegalStateException("native camera update failed: " + describe(error), error);
        }
    }

    public static AcquiredFrame acquireFrame(long previousGeneration) {
        BridgeState state = requireState();
        try {
            return state.acquireFrame(previousGeneration);
        } catch (Throwable error) {
            rethrowFatalError(error);
            throw new IllegalStateException("native frame acquire failed: " + describe(error), error);
        }
    }

    public static String rendererInfo() {
        BridgeState state = requireState();
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
        BridgeState state = requireState();
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
        BridgeState state = requireState();
        try {
            return state.colorLut(displayDevice, viewTransform, colorLook, workingSpace);
        } catch (Throwable error) {
            rethrowFatalError(error);
            throw new IllegalStateException("native color LUT query failed: "
                    + describe(error), error);
        }
    }

    public static Diagnostics diagnostics() {
        BridgeState state = requireState();
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
        BridgeState state = requireState();
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
        invoke("native Vulkan interop unbind", BridgeState::unbindVulkanInteropBuffer);
    }

    public static void closeWin32Handle(long handle) {
        if (handle == 0L) {
            return;
        }
        BridgeState state = requireState();
        try {
            state.library.closeWin32Handle.invokeExact(handle);
        } catch (Throwable error) {
            rethrowFatalError(error);
            throw new IllegalStateException(
                    "native Win32 handle close failed: " + describe(error), error);
        }
    }

    public static VulkanInteropState vulkanInteropState() {
        BridgeState state = requireState();
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
        BridgeState state = requireState();
        try {
            return state.acquireVulkanInteropFrame(previousGeneration);
        } catch (Throwable error) {
            rethrowFatalError(error);
            throw new IllegalStateException(
                    "native Vulkan interop frame acquire failed: "
                            + describe(error), error);
        }
    }

    public static void releaseVulkanInteropFrame(long generation) {
        BridgeState state = requireState();
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
        BridgeState state = requireState();
        try {
            return state.passDescriptor(passId);
        } catch (Throwable error) {
            rethrowFatalError(error);
            throw new IllegalStateException("native pass descriptor query failed: "
                    + describe(error), error);
        }
    }

    public static void close() {
        BridgeState state = bridgeState;
        bridgeState = null;
        if (state != null) {
            state.close();
        }
    }

    private static void invoke(String operation, BridgeCall call) {
        BridgeState state = requireState();
        try {
            call.run(state);
        } catch (Throwable error) {
            rethrowFatalError(error);
            throw new IllegalStateException(operation + " failed: " + describe(error), error);
        }
    }

    private static BridgeState requireState() {
        BridgeState state = bridgeState;
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

    private static void checkStatus(int status, String operation) {
        if (status != STATUS_OK) {
            throw new IllegalStateException(operation + " returned status " + status);
        }
    }

    private static void rethrowFatalError(Throwable error) {
        if (error instanceof Error fatalError && !(fatalError instanceof LinkageError)) {
            throw fatalError;
        }
    }

    private static String describe(Throwable error) {
        String detail = error.getMessage();
        return error.getClass().getSimpleName() + (detail == null ? "" : ": " + detail);
    }

    @FunctionalInterface
    private interface BridgeCall {
        void run(BridgeState state) throws Throwable;
    }

    private static final class BridgeState implements AutoCloseable {
        private final NativeLibrary library;
        private final MemorySegment renderer;
        private final MemorySegment cameraSegment;
        private final MemorySegment frameInfoSegment;
        private final MemorySegment frameViewSegment;
        private final MemorySegment settingsSegment;
        private final MemorySegment passDescriptorSegment;
        private final MemorySegment capabilitiesSegment;
        private final MemorySegment diagnosticsSegment;
        private final MemorySegment vulkanInteropStateSegment;
        private final MemorySegment framePixelsSegment;
        private final ByteBuffer framePixels;
        private final String buildInfo;
        private final String colorManagementInfo;

        private long generation;
        private Capabilities cachedCapabilities;
        private boolean closed;

        private BridgeState(
                NativeLibrary library,
                MemorySegment renderer,
                String buildInfo,
                String colorManagementInfo) {
            this.library = library;
            this.renderer = renderer;
            this.cameraSegment = library.arena.allocate(CAMERA_LAYOUT);
            this.frameInfoSegment = library.arena.allocate(FRAME_LAYOUT);
            this.frameViewSegment = library.arena.allocate(FRAME_VIEW_LAYOUT);
            this.settingsSegment = library.arena.allocate(SETTINGS_LAYOUT);
            this.passDescriptorSegment = library.arena.allocate(PASS_DESCRIPTOR_LAYOUT);
            this.capabilitiesSegment = library.arena.allocate(CAPABILITIES_LAYOUT);
            this.diagnosticsSegment = library.arena.allocate(DIAGNOSTICS_LAYOUT);
            this.vulkanInteropStateSegment = library.arena.allocate(
                    VulkanInteropStateAbi.LAYOUT);
            this.framePixelsSegment = library.arena.allocate(MAX_NATIVE_FRAME_BYTES, 16);
            this.framePixels = framePixelsSegment.asByteBuffer();
            this.buildInfo = buildInfo;
            this.colorManagementInfo = colorManagementInfo;
        }

        private static BridgeState open(Path libraryPath) throws Throwable {
            NativeLibrary library = NativeLibrary.open(libraryPath);
            MemorySegment renderer = MemorySegment.NULL;
            try {
                int actualAbiVersion = (int) library.abiVersion.invokeExact();
                if (actualAbiVersion != ABI_VERSION) {
                    throw new IllegalStateException(
                            "ABI mismatch: Java=" + ABI_VERSION + ", native=" + actualAbiVersion);
                }

                String buildInfo;
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment buffer = arena.allocate(BUILD_INFO_BYTES);
                    checkStatus((int) library.writeBuildInfo.invokeExact(buffer, BUILD_INFO_BYTES),
                            "build info call");
                    buildInfo = buffer.getString(0, StandardCharsets.UTF_8);
                }

                MemorySegment rendererOutput = library.arena.allocate(ADDRESS);
                checkStatus((int) library.createRenderer.invokeExact(rendererOutput),
                        "renderer creation");
                renderer = rendererOutput.get(ADDRESS, 0L);
                if (renderer.address() == 0L) {
                    throw new IllegalStateException("renderer creation returned a null handle");
                }
                String colorManagementInfo;
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment buffer = arena.allocate(COLOR_INFO_BYTES);
                    checkStatus((int) library.writeColorManagementInfo.invokeExact(
                            renderer, buffer, COLOR_INFO_BYTES), "color management info call");
                    colorManagementInfo = buffer.getString(0, StandardCharsets.UTF_8);
                }
                return new BridgeState(
                        library,
                        renderer,
                        buildInfo,
                        colorManagementInfo);
            } catch (Throwable error) {
                if (renderer.address() != 0L) {
                    try {
                        library.destroyRenderer.invokeExact(renderer);
                    } catch (Throwable closeError) {
                        error.addSuppressed(closeError);
                    }
                }
                library.close();
                throw error;
            }
        }

        private void resetScene(SectionGeometrySnapshot.SceneResources resources) throws Throwable {
            try (Arena arena = Arena.ofConfined()) {
                NativeSceneMarshaller.SceneResourcesSegments arguments =
                        NativeSceneMarshaller.writeResources(
                                arena, STRUCT_VERSION, resources);
                int status = (int) library.resetScene.invokeExact(
                        renderer,
                        arguments.resources(),
                        arguments.materials(),
                        arguments.textureDescriptors(),
                        arguments.texturePixels());
                checkRendererStatus(status, "scene reset");
                generation = 0L;
            }
        }

        private void bindVulkanInteropBuffer(
                int width,
                int height,
                long allocationBytes,
                long memoryHandle,
                long readySemaphoreHandle,
                long releaseSemaphoreHandle,
                String deviceUuid,
                int slotCount,
                int slotStrideBytes) throws Throwable {
            boolean nativeCalled = false;
            try (Arena arena = Arena.ofConfined()) {
                if (width <= 0 || height <= 0 || allocationBytes <= 0L
                        || memoryHandle == 0L || readySemaphoreHandle == 0L
                        || releaseSemaphoreHandle == 0L || slotCount <= 0
                        || slotStrideBytes <= 0) {
                    throw new IllegalArgumentException(
                            "invalid Vulkan interop buffer descriptor");
                }
                byte[] uuid = parseDeviceUuid(deviceUuid);
                MemorySegment descriptor = arena.allocate(VULKAN_INTEROP_BUFFER_LAYOUT);
                descriptor.set(
                        JAVA_INT, 0L,
                        Math.toIntExact(VULKAN_INTEROP_BUFFER_LAYOUT.byteSize()));
                descriptor.set(JAVA_INT, 4L, STRUCT_VERSION);
                descriptor.set(JAVA_INT, 8L, width);
                descriptor.set(JAVA_INT, 12L, height);
                descriptor.set(JAVA_INT, 16L, PIXEL_FORMAT_RGBA16_FLOAT);
                descriptor.set(JAVA_INT, 20L, 1);
                descriptor.set(JAVA_LONG, 24L, allocationBytes);
                descriptor.set(JAVA_LONG, 32L, memoryHandle);
                for (int index = 0; index < uuid.length; index++) {
                    descriptor.set(JAVA_BYTE, 40L + index, uuid[index]);
                }
                descriptor.set(JAVA_INT, 56L, slotCount);
                descriptor.set(JAVA_INT, 60L, slotStrideBytes);
                descriptor.set(JAVA_LONG, 64L, readySemaphoreHandle);
                descriptor.set(JAVA_LONG, 72L, releaseSemaphoreHandle);
                nativeCalled = true;
                int status = (int) library.bindVulkanInteropBuffer.invokeExact(
                        renderer, descriptor);
                checkRendererStatus(status, "Vulkan interop buffer bind");
            } catch (Throwable error) {
                if (!nativeCalled) {
                    library.closeWin32Handle.invokeExact(memoryHandle);
                    library.closeWin32Handle.invokeExact(readySemaphoreHandle);
                    library.closeWin32Handle.invokeExact(releaseSemaphoreHandle);
                }
                throw error;
            }
        }

        private void unbindVulkanInteropBuffer() throws Throwable {
            checkRendererStatus(
                    (int) library.unbindVulkanInteropBuffer.invokeExact(renderer),
                    "Vulkan interop buffer unbind");
        }

        private VulkanInteropState vulkanInteropState() throws Throwable {
            return queryVulkanInteropState((renderer, state) ->
                    (int) library.queryVulkanInteropState.invokeExact(renderer, state));
        }

        private VulkanInteropState acquireVulkanInteropFrame(
                long previousGeneration) throws Throwable {
            return queryVulkanInteropState((renderer, state) ->
                    (int) library.acquireVulkanInteropFrame.invokeExact(
                            renderer, previousGeneration, state));
        }

        private void releaseVulkanInteropFrame(long frameGeneration)
                throws Throwable {
            checkRendererStatus(
                    (int) library.releaseVulkanInteropFrame.invokeExact(
                            renderer, frameGeneration),
                    "Vulkan interop frame release");
        }

        private VulkanInteropState queryVulkanInteropState(
                VulkanInteropStateCall call) throws Throwable {
            vulkanInteropStateSegment.fill((byte) 0);
            vulkanInteropStateSegment.set(
                    JAVA_INT, VulkanInteropStateAbi.STRUCT_SIZE_OFFSET,
                    Math.toIntExact(VulkanInteropStateAbi.BYTE_SIZE));
            vulkanInteropStateSegment.set(
                    JAVA_INT, VulkanInteropStateAbi.STRUCT_VERSION_OFFSET,
                    STRUCT_VERSION);
            checkRendererStatus(
                    call.invoke(renderer, vulkanInteropStateSegment),
                    "Vulkan interop state query");
            return new VulkanInteropState(
                    vulkanInteropStateSegment.get(
                            JAVA_INT, VulkanInteropStateAbi.FLAGS_OFFSET),
                    vulkanInteropStateSegment.get(
                            JAVA_INT, VulkanInteropStateAbi.WIDTH_OFFSET),
                    vulkanInteropStateSegment.get(
                            JAVA_INT, VulkanInteropStateAbi.HEIGHT_OFFSET),
                    vulkanInteropStateSegment.get(
                            JAVA_INT, VulkanInteropStateAbi.SAMPLE_COUNT_OFFSET),
                    vulkanInteropStateSegment.get(
                            JAVA_LONG, VulkanInteropStateAbi.GENERATION_OFFSET),
                    vulkanInteropStateSegment.get(
                            JAVA_LONG,
                            VulkanInteropStateAbi.COMPLETED_FRAME_COUNT_OFFSET),
                    vulkanInteropStateSegment.get(
                            JAVA_INT, VulkanInteropStateAbi.LAST_SYNC_MICROS_OFFSET),
                    vulkanInteropStateSegment.get(
                            JAVA_INT, VulkanInteropStateAbi.EMA_SYNC_MICROS_OFFSET),
                    vulkanInteropStateSegment.get(
                            JAVA_INT, VulkanInteropStateAbi.MAX_SYNC_MICROS_OFFSET),
                    vulkanInteropStateSegment.get(
                            JAVA_INT, VulkanInteropStateAbi.SLOT_INDEX_OFFSET),
                    vulkanInteropStateSegment.get(
                            JAVA_INT, VulkanInteropStateAbi.SLOT_COUNT_OFFSET),
                    vulkanInteropStateSegment.get(
                            JAVA_INT, VulkanInteropStateAbi.READY_SLOT_COUNT_OFFSET),
                    vulkanInteropStateSegment.get(
                            JAVA_LONG, VulkanInteropStateAbi.PRODUCER_WAIT_COUNT_OFFSET),
                    vulkanInteropStateSegment.get(
                            JAVA_INT, VulkanInteropStateAbi.DEPTH_WIDTH_OFFSET),
                    vulkanInteropStateSegment.get(
                            JAVA_INT, VulkanInteropStateAbi.DEPTH_HEIGHT_OFFSET));
        }

        @FunctionalInterface
        private interface VulkanInteropStateCall {
            int invoke(MemorySegment renderer, MemorySegment state) throws Throwable;
        }

        private static byte[] parseDeviceUuid(String value) {
            if (value == null || value.length() != 32) {
                throw new IllegalArgumentException("invalid Vulkan device UUID: " + value);
            }
            byte[] result = new byte[16];
            for (int index = 0; index < result.length; index++) {
                int high = Character.digit(value.charAt(index * 2), 16);
                int low = Character.digit(value.charAt(index * 2 + 1), 16);
                if (high < 0 || low < 0) {
                    throw new IllegalArgumentException(
                            "invalid Vulkan device UUID: " + value);
                }
                result[index] = (byte) ((high << 4) | low);
            }
            return result;
        }

        private void upsertSection(SectionGeometrySnapshot snapshot) throws Throwable {
            if (snapshot.empty()) {
                removeSection(snapshot.sectionNode());
                return;
            }
            try (Arena arena = Arena.ofConfined()) {
                NativeSceneMarshaller.SectionSegments arguments =
                        NativeSceneMarshaller.writeSection(
                                arena, STRUCT_VERSION, snapshot);
                int status = (int) library.upsertSection.invokeExact(
                        renderer,
                        arguments.section(),
                        arguments.vertices(),
                        arguments.triangles());
                checkRendererStatus(status, "section upsert");
            }
        }

        private void removeSection(long sectionId) throws Throwable {
            checkRendererStatus(
                    (int) library.removeSection.invokeExact(renderer, sectionId),
                    "section removal");
        }

        private void commitScene() throws Throwable {
            checkRendererStatus(
                    (int) library.commitScene.invokeExact(renderer), "scene commit");
        }

        private void applySettings(CyclesRenderSettings settings) throws Throwable {
            NativeSettingsMarshaller.write(settingsSegment, STRUCT_VERSION, settings);
            checkRendererStatus(
                    (int) library.applySettings.invokeExact(renderer, settingsSegment),
                    "settings update");
        }

        private Capabilities capabilities() throws Throwable {
            if (cachedCapabilities != null) {
                return cachedCapabilities;
            }
            capabilitiesSegment.fill((byte) 0);
            capabilitiesSegment.set(
                    JAVA_INT, 0L, Math.toIntExact(CAPABILITIES_LAYOUT.byteSize()));
            capabilitiesSegment.set(JAVA_INT, 4L, STRUCT_VERSION);
            checkRendererStatus(
                    (int) library.queryCapabilities.invokeExact(renderer, capabilitiesSegment),
                    "capability query");
            cachedCapabilities = new Capabilities(
                    capabilitiesSegment.get(JAVA_LONG, 8L),
                    capabilitiesSegment.get(JAVA_LONG, 16L),
                    capabilitiesSegment.get(JAVA_INT, 24L),
                    capabilitiesSegment.get(JAVA_INT, 28L),
                    capabilitiesSegment.get(JAVA_INT, 32L),
                    capabilitiesSegment.get(JAVA_INT, 36L),
                    capabilitiesSegment.get(JAVA_INT, 40L),
                    capabilitiesSegment.get(JAVA_INT, 44L),
                    capabilitiesSegment.get(JAVA_INT, 48L),
                    capabilitiesSegment.get(JAVA_INT, 52L),
                    capabilitiesSegment.get(JAVA_INT, 56L));
            return cachedCapabilities;
        }

        private String colorManagementInfo() {
            return colorManagementInfo;
        }

        private ColorLut colorLut(
                CyclesRenderSettings.DisplayDevice displayDevice,
                CyclesRenderSettings.ViewTransform viewTransform,
                CyclesRenderSettings.ColorLook colorLook,
                CyclesRenderSettings.WorkingSpace workingSpace)
                throws Throwable {
            CyclesRenderSettings.ViewTransform effectiveView =
                    viewTransform.effectiveFor(displayDevice);
            if (effectiveView == CyclesRenderSettings.ViewTransform.RAW) {
                throw new IllegalArgumentException(
                        effectiveView + " does not require an OCIO LUT");
            }
            int effectiveLook = colorLook.effectiveNativeId(effectiveView);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment descriptor = arena.allocate(COLOR_LUT_DESCRIPTOR_LAYOUT);
                descriptor.set(
                        JAVA_INT, 0L, Math.toIntExact(COLOR_LUT_DESCRIPTOR_LAYOUT.byteSize()));
                descriptor.set(JAVA_INT, 4L, STRUCT_VERSION);
                checkRendererStatus(
                        (int) library.queryColorLut.invokeExact(
                                renderer,
                                displayDevice.nativeId(),
                                effectiveView.nativeId(),
                                effectiveLook,
                                workingSpace.nativeId(),
                                descriptor,
                                MemorySegment.NULL,
                                0L),
                        "color LUT descriptor query");
                long byteCount = descriptor.get(JAVA_LONG, 32L);
                if (byteCount <= 0L || byteCount > Integer.MAX_VALUE
                        || (byteCount & 15L) != 0L) {
                    throw new IllegalStateException(
                            "invalid native color LUT byte count " + byteCount);
                }
                ByteBuffer pixels = ByteBuffer.allocateDirect(Math.toIntExact(byteCount))
                        .order(ByteOrder.nativeOrder());
                checkRendererStatus(
                        (int) library.queryColorLut.invokeExact(
                                renderer,
                                displayDevice.nativeId(),
                                effectiveView.nativeId(),
                                effectiveLook,
                                workingSpace.nativeId(),
                                descriptor,
                                MemorySegment.ofBuffer(pixels),
                                byteCount),
                        "color LUT pixel query");
                pixels.clear();
                return new ColorLut(
                        new ColorLutDescriptor(
                                descriptor.get(JAVA_INT, 8L),
                                descriptor.get(JAVA_INT, 12L),
                                descriptor.get(JAVA_INT, 16L),
                                descriptor.get(JAVA_INT, 20L),
                                descriptor.get(JAVA_INT, 24L),
                                descriptor.get(JAVA_INT, 28L),
                                descriptor.get(JAVA_LONG, 32L),
                                descriptor.get(JAVA_FLOAT, 40L),
                                descriptor.get(JAVA_FLOAT, 44L),
                                descriptor.get(JAVA_FLOAT, 48L),
                                descriptor.get(JAVA_INT, 52L),
                                descriptor.get(JAVA_INT, 56L),
                                descriptor.get(JAVA_INT, 60L),
                                descriptor.get(JAVA_INT, 64L)),
                        pixels.asReadOnlyBuffer().order(ByteOrder.nativeOrder()));
            }
        }

        private PassDescriptor passDescriptor(int passId) throws Throwable {
            if (passId < 0 || passId >= CyclesRenderSettings.PassView.values().length) {
                throw new IllegalArgumentException("unknown pass id " + passId);
            }
            passDescriptorSegment.fill((byte) 0);
            passDescriptorSegment.set(
                    JAVA_INT, 0L, Math.toIntExact(PASS_DESCRIPTOR_LAYOUT.byteSize()));
            passDescriptorSegment.set(JAVA_INT, 4L, STRUCT_VERSION);
            checkStatus(
                    (int) library.queryPassDescriptor.invokeExact(
                            passId, passDescriptorSegment),
                    "pass descriptor query");
            return new PassDescriptor(
                    passDescriptorSegment.get(JAVA_INT, 8L),
                    passDescriptorSegment.get(JAVA_INT, 12L),
                    passDescriptorSegment.get(JAVA_INT, 16L),
                    passDescriptorSegment.get(JAVA_INT, 20L),
                    passDescriptorSegment.get(JAVA_INT, 24L),
                    passDescriptorSegment.get(JAVA_INT, 28L));
        }

        private Diagnostics diagnostics() throws Throwable {
            diagnosticsSegment.fill((byte) 0);
            diagnosticsSegment.set(
                    JAVA_INT, 0L, Math.toIntExact(DIAGNOSTICS_LAYOUT.byteSize()));
            diagnosticsSegment.set(JAVA_INT, 4L, STRUCT_VERSION);
            checkRendererStatus(
                    (int) library.queryDiagnostics.invokeExact(renderer, diagnosticsSegment),
                    "diagnostics query");
            return new Diagnostics(
                    diagnosticsSegment.get(JAVA_LONG, 8L),
                    diagnosticsSegment.get(JAVA_LONG, 16L),
                    diagnosticsSegment.get(JAVA_LONG, 24L),
                    diagnosticsSegment.get(JAVA_LONG, 32L),
                    diagnosticsSegment.get(JAVA_INT, 40L),
                    diagnosticsSegment.get(JAVA_INT, 44L),
                    diagnosticsSegment.get(JAVA_INT, 48L),
                    diagnosticsSegment.get(JAVA_INT, 52L),
                    diagnosticsSegment.get(JAVA_INT, 56L),
                    diagnosticsSegment.get(JAVA_INT, 60L),
                    diagnosticsSegment.get(JAVA_INT, 64L),
                    diagnosticsSegment.get(JAVA_INT, 68L),
                    diagnosticsSegment.get(JAVA_INT, 72L),
                    diagnosticsSegment.get(JAVA_INT, 76L) != 0,
                    diagnosticsSegment.get(JAVA_INT, 80L),
                    diagnosticsSegment.get(JAVA_INT, 84L),
                    diagnosticsSegment.get(JAVA_INT, 88L),
                    diagnosticsSegment.get(JAVA_INT, 92L),
                    diagnosticsSegment.get(JAVA_INT, 96L),
                    diagnosticsSegment.get(JAVA_INT, 100L),
                    diagnosticsSegment.get(JAVA_FLOAT, 104L),
                    diagnosticsSegment.get(JAVA_INT, 108L),
                    diagnosticsSegment.get(JAVA_LONG, 112L),
                    diagnosticsSegment.get(JAVA_LONG, 120L),
                    diagnosticsSegment.get(JAVA_LONG, 128L),
                    diagnosticsSegment.get(JAVA_LONG, 136L),
                    diagnosticsSegment.get(JAVA_INT, 144L),
                    diagnosticsSegment.get(JAVA_INT, 148L),
                    diagnosticsSegment.get(JAVA_INT, 152L),
                    diagnosticsSegment.get(JAVA_INT, 156L),
                    diagnosticsSegment.get(JAVA_INT, 160L),
                    diagnosticsSegment.get(JAVA_INT, 164L),
                    diagnosticsSegment.get(JAVA_INT, 168L),
                    diagnosticsSegment.get(JAVA_INT, 172L),
                    diagnosticsSegment.get(JAVA_LONG, 176L),
                    diagnosticsSegment.get(JAVA_LONG, 184L),
                    diagnosticsSegment.get(JAVA_LONG, 192L),
                    diagnosticsSegment.get(JAVA_INT, 200L),
                    diagnosticsSegment.get(JAVA_INT, 204L),
                    diagnosticsSegment.get(JAVA_INT, 208L),
                    diagnosticsSegment.get(JAVA_INT, 212L),
                    diagnosticsSegment.get(JAVA_INT, 216L),
                    diagnosticsSegment.get(JAVA_INT, 220L),
                    diagnosticsSegment.get(JAVA_INT, 224L),
                    diagnosticsSegment.get(JAVA_INT, 228L),
                    diagnosticsSegment.get(JAVA_INT, 232L),
                    diagnosticsSegment.get(JAVA_INT, 236L),
                    diagnosticsSegment.get(JAVA_LONG, 240L),
                    diagnosticsSegment.get(JAVA_LONG, 248L),
                    diagnosticsSegment.get(JAVA_LONG, 256L),
                    diagnosticsSegment.get(JAVA_LONG, 264L),
                    diagnosticsSegment.get(JAVA_INT, 272L),
                    diagnosticsSegment.get(JAVA_INT, 276L),
                    diagnosticsSegment.get(JAVA_INT, 280L),
                    diagnosticsSegment.get(JAVA_INT, 284L),
                    diagnosticsSegment.get(JAVA_LONG, 288L),
                    diagnosticsSegment.get(JAVA_INT, 296L),
                    diagnosticsSegment.get(JAVA_INT, 300L),
                    diagnosticsSegment.get(JAVA_INT, 304L),
                    diagnosticsSegment.get(JAVA_INT, 308L),
                    diagnosticsSegment.get(JAVA_INT, 312L),
                    diagnosticsSegment.get(JAVA_INT, 316L),
                    diagnosticsSegment.get(JAVA_INT, 320L),
                    diagnosticsSegment.get(JAVA_INT, 324L),
                    diagnosticsSegment.get(JAVA_INT, 328L),
                    diagnosticsSegment.get(JAVA_FLOAT, 332L),
                    diagnosticsSegment.get(JAVA_FLOAT, 336L),
                    diagnosticsSegment.get(JAVA_INT, 340L),
                    diagnosticsSegment.get(JAVA_FLOAT, 344L),
                    diagnosticsSegment.get(JAVA_INT, 348L) != 0,
                    diagnosticsSegment.get(JAVA_FLOAT, 352L),
                    diagnosticsSegment.get(JAVA_FLOAT, 356L),
                    diagnosticsSegment.get(JAVA_FLOAT, 360L),
                    diagnosticsSegment.get(JAVA_INT, 364L),
                    diagnosticsSegment.get(JAVA_FLOAT, 368L),
                    diagnosticsSegment.get(JAVA_FLOAT, 372L),
                    diagnosticsSegment.get(JAVA_INT, 376L) != 0
                            ? uuidString(diagnosticsSegment.asSlice(380L, 16L))
                            : "unavailable",
                    diagnosticsSegment.get(JAVA_LONG, 400L),
                    diagnosticsSegment.get(JAVA_LONG, 408L),
                    diagnosticsSegment.get(JAVA_INT, 416L),
                    diagnosticsSegment.get(JAVA_INT, 420L),
                    diagnosticsSegment.get(JAVA_INT, 424L),
                    diagnosticsSegment.get(JAVA_INT, 428L),
                    diagnosticsSegment.get(JAVA_INT, 432L),
                    diagnosticsSegment.get(JAVA_INT, 436L),
                    diagnosticsSegment.get(JAVA_INT, 440L),
                    diagnosticsSegment.get(JAVA_INT, 444L),
                    diagnosticsSegment.get(JAVA_INT, 448L),
                    diagnosticsSegment.get(JAVA_INT, 452L),
                    diagnosticsSegment.get(JAVA_INT, 456L),
                    diagnosticsSegment.get(JAVA_INT, 460L),
                    diagnosticsSegment.get(JAVA_INT, 464L),
                    diagnosticsSegment.get(JAVA_INT, 468L),
                    diagnosticsSegment.get(JAVA_INT, 472L),
                    diagnosticsSegment.get(JAVA_INT, 488L),
                    diagnosticsSegment.get(JAVA_INT, 492L),
                    diagnosticsSegment.get(JAVA_INT, 496L),
                    diagnosticsSegment.get(JAVA_INT, 504L),
                    diagnosticsSegment.get(JAVA_INT, 508L),
                    diagnosticsSegment.get(JAVA_INT, 512L),
                    diagnosticsSegment.get(JAVA_INT, 516L),
                    intArray(diagnosticsSegment, 520L, DEVICE_UPDATE_PHASE_COUNT),
                    intArray(diagnosticsSegment, 552L, DEVICE_UPDATE_PHASE_COUNT),
                    intArray(diagnosticsSegment, 584L, DEVICE_UPDATE_PHASE_COUNT),
                    diagnosticsSegment.get(JAVA_FLOAT, 616L),
                    diagnosticsSegment.get(JAVA_FLOAT, 620L),
                    diagnosticsSegment.get(JAVA_INT, 624L),
                    diagnosticsSegment.get(JAVA_INT, 628L),
                    diagnosticsSegment.get(JAVA_INT, 632L),
                    diagnosticsSegment.get(JAVA_INT, 636L),
                    diagnosticsSegment.get(JAVA_INT, 640L),
                    diagnosticsSegment.get(JAVA_INT, 644L),
                    diagnosticsSegment.get(JAVA_INT, 648L),
                    diagnosticsSegment.get(JAVA_INT, 652L),
                    diagnosticsSegment.get(JAVA_INT, 656L),
                    diagnosticsSegment.get(JAVA_INT, 660L),
                    diagnosticsSegment.get(JAVA_INT, 664L),
                    diagnosticsSegment.get(JAVA_INT, 668L));
        }

        private static int[] intArray(MemorySegment source, long offset, int count) {
            int[] result = new int[count];
            for (int index = 0; index < count; index++) {
                result[index] = source.get(JAVA_INT, offset + index * Integer.BYTES);
            }
            return result;
        }

        private static String uuidString(MemorySegment bytes) {
            StringBuilder result = new StringBuilder(32);
            for (long index = 0; index < bytes.byteSize(); index++) {
                result.append(String.format(
                        java.util.Locale.ROOT,
                        "%02x",
                        bytes.get(JAVA_BYTE, index) & 0xff));
            }
            return result.toString();
        }

        private RenderedFrame renderFrame(
                int width,
                int height,
                long frameId,
                CameraInput input) throws Throwable {
            NativeFrameMarshaller.writeCamera(
                    cameraSegment, STRUCT_VERSION, width, height, frameId, input);
            NativeFrameMarshaller.prepareFrameInfo(
                    frameInfoSegment, STRUCT_VERSION, generation);
            int status = (int) library.renderFrame.invokeExact(
                    renderer,
                    cameraSegment,
                    frameInfoSegment,
                    framePixelsSegment,
                    (long) MAX_NATIVE_FRAME_BYTES);
            checkRendererStatus(status, "renderer frame call");

            int frameWidth = frameInfoSegment.get(JAVA_INT, 8L);
            int frameHeight = frameInfoSegment.get(JAVA_INT, 12L);
            generation = frameInfoSegment.get(JAVA_LONG, 16L);
            int pixelBytes = frameInfoSegment.get(JAVA_INT, 24L);
            int flags = frameInfoSegment.get(JAVA_INT, 28L);
            int sampleCount = frameInfoSegment.get(JAVA_INT, 32L);
            ByteBuffer pixels = null;
            if ((flags & FRAME_UPDATED) != 0) {
                int expected = Math.multiplyExact(Math.multiplyExact(frameWidth, frameHeight), 4);
                if (pixelBytes != expected || pixelBytes < 0 || pixelBytes > MAX_NATIVE_FRAME_BYTES) {
                    throw new IllegalStateException("native frame byte count mismatch: " + pixelBytes);
                }
                pixels = framePixels.duplicate();
                pixels.clear();
                pixels.limit(pixelBytes);
            }
            return new RenderedFrame(
                    (flags & FRAME_READY) != 0,
                    (flags & FRAME_UPDATED) != 0,
                    frameWidth,
                    frameHeight,
                    generation,
                    sampleCount,
                    pixels);
        }

        private void updateCamera(
                int width,
                int height,
                long frameId,
                CameraInput input) throws Throwable {
            NativeFrameMarshaller.writeCamera(
                    cameraSegment, STRUCT_VERSION, width, height, frameId, input);
            int status = (int) library.updateCamera.invokeExact(renderer, cameraSegment);
            checkRendererStatus(status, "renderer camera update");
        }

        private AcquiredFrame acquireFrame(long previousGeneration) throws Throwable {
            NativeFrameMarshaller.prepareFrameView(frameViewSegment, STRUCT_VERSION);
            checkRendererStatus(
                    (int) library.acquireFrame.invokeExact(
                            renderer, previousGeneration, frameViewSegment),
                    "renderer frame acquire");
            int width = frameViewSegment.get(JAVA_INT, 8L);
            int height = frameViewSegment.get(JAVA_INT, 12L);
            long generation = frameViewSegment.get(JAVA_LONG, 16L);
            int sampleCount = frameViewSegment.get(JAVA_INT, 24L);
            int pixelFormat = frameViewSegment.get(JAVA_INT, 28L);
            long pixelBytes = frameViewSegment.get(JAVA_LONG, 32L);
            long token = frameViewSegment.get(JAVA_LONG, 40L);
            MemorySegment pointer = frameViewSegment.get(ADDRESS, 48L);
            int flags = frameViewSegment.get(JAVA_INT, 56L);
            if ((flags & FRAME_UPDATED) == 0) {
                return new AcquiredFrame(
                        null, readyFlag(flags), false, width, height,
                        generation, sampleCount, pixelFormat, null);
            }
            long expectedBytes = Math.multiplyExact(Math.multiplyExact((long) width, height), 8L);
            if (width <= 0 || height <= 0 || pixelFormat != 2
                    || pixelBytes != expectedBytes || token == 0L || pointer.address() == 0L) {
                if (token != 0L) {
                    releaseFrameLease(token);
                }
                throw new IllegalStateException("invalid native RGBA16F frame lease");
            }
            NativeFrameMarshaller.FrameLease lease =
                    NativeFrameMarshaller.FrameLease.open(
                            pointer, pixelBytes, token, this::releaseFrameLease);
            return new AcquiredFrame(
                    lease, true, true, width, height,
                    generation, sampleCount, pixelFormat, lease.pixels());
        }

        private static boolean readyFlag(int flags) {
            return (flags & FRAME_READY) != 0;
        }

        private void releaseFrameLease(long token) {
            try {
                checkRendererStatus(
                        (int) library.releaseFrame.invokeExact(renderer, token),
                        "renderer frame release");
            } catch (Throwable error) {
                rethrowFatalError(error);
                throw new IllegalStateException(
                        "native frame release failed: " + describe(error), error);
            }
        }

        private ByteBuffer fillTestFrame(int width, int height, long frameId) throws Throwable {
            int bytes = Math.multiplyExact(Math.multiplyExact(width, height), 4);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment output = arena.allocate(bytes, 16);
                checkStatus((int) library.fillTestFrame.invokeExact(
                                output, width, height, frameId),
                        "test frame call");
                ByteBuffer copied = ByteBuffer.allocate(bytes);
                copied.put(output.asByteBuffer()).flip();
                return copied;
            }
        }

        private String buildInfo() {
            return buildInfo;
        }

        private String rendererInfo() throws Throwable {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(RENDERER_INFO_BYTES);
                checkStatus((int) library.writeRendererInfo.invokeExact(
                        renderer, buffer, RENDERER_INFO_BYTES), "renderer info call");
                return buffer.getString(0, StandardCharsets.UTF_8);
            }
        }

        private void checkRendererStatus(int status, String operation) throws Throwable {
            if (status != STATUS_OK) {
                throw new IllegalStateException(
                        operation + " returned status " + status + "; " + rendererInfo());
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                library.destroyRenderer.invokeExact(renderer);
            } catch (Throwable error) {
                rethrowFatalError(error);
            } finally {
                library.close();
            }
        }

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

        private AcquiredFrame(
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

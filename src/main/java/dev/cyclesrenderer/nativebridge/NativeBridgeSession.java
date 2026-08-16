package dev.cyclesrenderer.nativebridge;

import dev.cyclesrenderer.config.CyclesRenderSettings;
import dev.cyclesrenderer.scene.SectionGeometrySnapshot;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static dev.cyclesrenderer.nativebridge.NativeLayouts.*;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** Owns one native renderer and the memory used by its serialized session calls. */
final class NativeBridgeSession implements AutoCloseable {
    private static final int STRUCT_VERSION = 1;
    private static final int STATUS_OK = 0;
    private static final int FRAME_READY = 1;
    private static final int FRAME_UPDATED = 2;
    private static final int BUILD_INFO_BYTES = 128;
    private static final int RENDERER_INFO_BYTES = 512;
    private static final int COLOR_INFO_BYTES = 2048;
    private static final int MAX_NATIVE_FRAME_BYTES = 3840 * 2160 * 4;

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
    private NativeBridge.Capabilities cachedCapabilities;
    private boolean closed;

    private NativeBridgeSession(
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
        this.vulkanInteropStateSegment = library.arena.allocate(VulkanInteropStateAbi.LAYOUT);
        this.framePixelsSegment = library.arena.allocate(MAX_NATIVE_FRAME_BYTES, 16);
        this.framePixels = framePixelsSegment.asByteBuffer();
        this.buildInfo = buildInfo;
        this.colorManagementInfo = colorManagementInfo;
    }

    static NativeBridgeSession open(Path libraryPath) throws Throwable {
        NativeLibrary library = NativeLibrary.open(libraryPath);
        MemorySegment renderer = MemorySegment.NULL;
        try {
            int actualAbiVersion = (int) library.abiVersion.invokeExact();
            if (actualAbiVersion != NativeBridge.ABI_VERSION) {
                throw new IllegalStateException(
                        "ABI mismatch: Java=" + NativeBridge.ABI_VERSION
                                + ", native=" + actualAbiVersion);
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
            return new NativeBridgeSession(library, renderer, buildInfo, colorManagementInfo);
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

    void resetScene(SectionGeometrySnapshot.SceneResources resources) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            NativeSceneMarshaller.SceneResourcesSegments arguments =
                    NativeSceneMarshaller.writeResources(arena, STRUCT_VERSION, resources);
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

    void bindVulkanInteropBuffer(
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
            MemorySegment descriptor = NativeVulkanInteropMarshaller.writeBufferDescriptor(
                    arena,
                    STRUCT_VERSION,
                    NativeBridge.PIXEL_FORMAT_RGBA16_FLOAT,
                    width,
                    height,
                    allocationBytes,
                    memoryHandle,
                    readySemaphoreHandle,
                    releaseSemaphoreHandle,
                    deviceUuid,
                    slotCount,
                    slotStrideBytes);
            nativeCalled = true;
            int status = (int) library.bindVulkanInteropBuffer.invokeExact(renderer, descriptor);
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

    void unbindVulkanInteropBuffer() throws Throwable {
        checkRendererStatus(
                (int) library.unbindVulkanInteropBuffer.invokeExact(renderer),
                "Vulkan interop buffer unbind");
    }

    void closeWin32Handle(long handle) throws Throwable {
        library.closeWin32Handle.invokeExact(handle);
    }

    NativeBridge.VulkanInteropState vulkanInteropState() throws Throwable {
        return queryVulkanInteropState((rendererHandle, state) ->
                (int) library.queryVulkanInteropState.invokeExact(rendererHandle, state));
    }

    NativeBridge.VulkanInteropState acquireVulkanInteropFrame(long previousGeneration)
            throws Throwable {
        return queryVulkanInteropState((rendererHandle, state) ->
                (int) library.acquireVulkanInteropFrame.invokeExact(
                        rendererHandle, previousGeneration, state));
    }

    void releaseVulkanInteropFrame(long frameGeneration) throws Throwable {
        checkRendererStatus(
                (int) library.releaseVulkanInteropFrame.invokeExact(renderer, frameGeneration),
                "Vulkan interop frame release");
    }

    private NativeBridge.VulkanInteropState queryVulkanInteropState(
            VulkanInteropStateCall call) throws Throwable {
        NativeVulkanInteropMarshaller.prepareState(vulkanInteropStateSegment, STRUCT_VERSION);
        checkRendererStatus(
                call.invoke(renderer, vulkanInteropStateSegment),
                "Vulkan interop state query");
        return NativeVulkanInteropMarshaller.decodeState(vulkanInteropStateSegment);
    }

    @FunctionalInterface
    private interface VulkanInteropStateCall {
        int invoke(MemorySegment renderer, MemorySegment state) throws Throwable;
    }

    void upsertSection(SectionGeometrySnapshot snapshot) throws Throwable {
        if (snapshot.empty()) {
            removeSection(snapshot.sectionNode());
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            NativeSceneMarshaller.SectionSegments arguments =
                    NativeSceneMarshaller.writeSection(arena, STRUCT_VERSION, snapshot);
            int status = (int) library.upsertSection.invokeExact(
                    renderer,
                    arguments.section(),
                    arguments.vertices(),
                    arguments.triangles());
            checkRendererStatus(status, "section upsert");
        }
    }

    void removeSection(long sectionId) throws Throwable {
        checkRendererStatus(
                (int) library.removeSection.invokeExact(renderer, sectionId),
                "section removal");
    }

    void commitScene() throws Throwable {
        checkRendererStatus((int) library.commitScene.invokeExact(renderer), "scene commit");
    }

    void applySettings(CyclesRenderSettings settings) throws Throwable {
        NativeSettingsMarshaller.write(settingsSegment, STRUCT_VERSION, settings);
        checkRendererStatus(
                (int) library.applySettings.invokeExact(renderer, settingsSegment),
                "settings update");
    }

    NativeBridge.Capabilities capabilities() throws Throwable {
        if (cachedCapabilities != null) {
            return cachedCapabilities;
        }
        NativeCapabilitiesDecoder.prepare(capabilitiesSegment, STRUCT_VERSION);
        checkRendererStatus(
                (int) library.queryCapabilities.invokeExact(renderer, capabilitiesSegment),
                "capability query");
        cachedCapabilities = NativeCapabilitiesDecoder.decode(capabilitiesSegment);
        return cachedCapabilities;
    }

    String colorManagementInfo() {
        return colorManagementInfo;
    }

    NativeBridge.ColorLut colorLut(
            CyclesRenderSettings.DisplayDevice displayDevice,
            CyclesRenderSettings.ViewTransform viewTransform,
            CyclesRenderSettings.ColorLook colorLook,
            CyclesRenderSettings.WorkingSpace workingSpace) throws Throwable {
        CyclesRenderSettings.ViewTransform effectiveView =
                viewTransform.effectiveFor(displayDevice);
        if (effectiveView == CyclesRenderSettings.ViewTransform.RAW) {
            throw new IllegalArgumentException(effectiveView + " does not require an OCIO LUT");
        }
        int effectiveLook = colorLook.effectiveNativeId(effectiveView);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment descriptor = arena.allocate(COLOR_LUT_DESCRIPTOR_LAYOUT);
            NativeColorLutDecoder.prepare(descriptor, STRUCT_VERSION);
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
            long byteCount = NativeColorLutDecoder.byteCount(descriptor);
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
            return NativeColorLutDecoder.decode(descriptor, pixels);
        }
    }

    NativeBridge.PassDescriptor passDescriptor(int passId) throws Throwable {
        if (passId < 0 || passId >= CyclesRenderSettings.PassView.values().length) {
            throw new IllegalArgumentException("unknown pass id " + passId);
        }
        NativePassDescriptorDecoder.prepare(passDescriptorSegment, STRUCT_VERSION);
        checkStatus(
                (int) library.queryPassDescriptor.invokeExact(passId, passDescriptorSegment),
                "pass descriptor query");
        return NativePassDescriptorDecoder.decode(passDescriptorSegment);
    }

    NativeBridge.Diagnostics diagnostics() throws Throwable {
        NativeDiagnosticsDecoder.prepare(diagnosticsSegment, STRUCT_VERSION);
        checkRendererStatus(
                (int) library.queryDiagnostics.invokeExact(renderer, diagnosticsSegment),
                "diagnostics query");
        return NativeDiagnosticsDecoder.decode(
                diagnosticsSegment, NativeBridge.DEVICE_UPDATE_PHASE_COUNT);
    }

    NativeBridge.RenderedFrame renderFrame(
            int width,
            int height,
            long frameId,
            NativeBridge.CameraInput input) throws Throwable {
        NativeFrameMarshaller.writeCamera(
                cameraSegment, STRUCT_VERSION, width, height, frameId, input);
        NativeFrameMarshaller.prepareFrameInfo(frameInfoSegment, STRUCT_VERSION, generation);
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
                throw new IllegalStateException(
                        "native frame byte count mismatch: " + pixelBytes);
            }
            pixels = framePixels.duplicate();
            pixels.clear();
            pixels.limit(pixelBytes);
        }
        return new NativeBridge.RenderedFrame(
                (flags & FRAME_READY) != 0,
                (flags & FRAME_UPDATED) != 0,
                frameWidth,
                frameHeight,
                generation,
                sampleCount,
                pixels);
    }

    void updateCamera(
            int width,
            int height,
            long frameId,
            NativeBridge.CameraInput input) throws Throwable {
        NativeFrameMarshaller.writeCamera(
                cameraSegment, STRUCT_VERSION, width, height, frameId, input);
        int status = (int) library.updateCamera.invokeExact(renderer, cameraSegment);
        checkRendererStatus(status, "renderer camera update");
    }

    NativeBridge.AcquiredFrame acquireFrame(long previousGeneration) throws Throwable {
        NativeFrameMarshaller.prepareFrameView(frameViewSegment, STRUCT_VERSION);
        checkRendererStatus(
                (int) library.acquireFrame.invokeExact(
                        renderer, previousGeneration, frameViewSegment),
                "renderer frame acquire");
        int width = frameViewSegment.get(JAVA_INT, 8L);
        int height = frameViewSegment.get(JAVA_INT, 12L);
        long frameGeneration = frameViewSegment.get(JAVA_LONG, 16L);
        int sampleCount = frameViewSegment.get(JAVA_INT, 24L);
        int pixelFormat = frameViewSegment.get(JAVA_INT, 28L);
        long pixelBytes = frameViewSegment.get(JAVA_LONG, 32L);
        long token = frameViewSegment.get(JAVA_LONG, 40L);
        MemorySegment pointer = frameViewSegment.get(ADDRESS, 48L);
        int flags = frameViewSegment.get(JAVA_INT, 56L);
        if ((flags & FRAME_UPDATED) == 0) {
            return new NativeBridge.AcquiredFrame(
                    null, readyFlag(flags), false, width, height,
                    frameGeneration, sampleCount, pixelFormat, null);
        }
        long expectedBytes = Math.multiplyExact(Math.multiplyExact((long) width, height), 8L);
        if (width <= 0 || height <= 0 || pixelFormat != NativeBridge.PIXEL_FORMAT_RGBA16_FLOAT
                || pixelBytes != expectedBytes || token == 0L || pointer.address() == 0L) {
            if (token != 0L) {
                releaseFrameLease(token);
            }
            throw new IllegalStateException("invalid native RGBA16F frame lease");
        }
        NativeFrameMarshaller.FrameLease lease = NativeFrameMarshaller.FrameLease.open(
                pointer, pixelBytes, token, this::releaseFrameLease);
        return new NativeBridge.AcquiredFrame(
                lease, true, true, width, height,
                frameGeneration, sampleCount, pixelFormat, lease.pixels());
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
            NativeBridge.rethrowFatalError(error);
            throw new IllegalStateException(
                    "native frame release failed: " + NativeBridge.describe(error), error);
        }
    }

    ByteBuffer fillTestFrame(int width, int height, long frameId) throws Throwable {
        int bytes = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment output = arena.allocate(bytes, 16);
            checkStatus(
                    (int) library.fillTestFrame.invokeExact(output, width, height, frameId),
                    "test frame call");
            ByteBuffer copied = ByteBuffer.allocate(bytes);
            copied.put(output.asByteBuffer()).flip();
            return copied;
        }
    }

    String buildInfo() {
        return buildInfo;
    }

    String rendererInfo() throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(RENDERER_INFO_BYTES);
            checkStatus(
                    (int) library.writeRendererInfo.invokeExact(
                            renderer, buffer, RENDERER_INFO_BYTES),
                    "renderer info call");
            return buffer.getString(0, StandardCharsets.UTF_8);
        }
    }

    private static void checkStatus(int status, String operation) {
        if (status != STATUS_OK) {
            throw new IllegalStateException(operation + " returned status " + status);
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
            NativeBridge.rethrowFatalError(error);
        } finally {
            library.close();
        }
    }
}

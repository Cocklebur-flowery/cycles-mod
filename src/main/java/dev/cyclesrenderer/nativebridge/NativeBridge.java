package dev.cyclesrenderer.nativebridge;

import dev.cyclesrenderer.scene.ClientRenderSnapshot;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

public final class NativeBridge {
    public static final int ABI_VERSION = 4;

    private static final String LIBRARY_PATH_PROPERTY = "cyclesrenderer.nativeLibrary";
    private static final int STRUCT_VERSION = 1;
    private static final int STATUS_OK = 0;
    private static final int TEST_WIDTH = 16;
    private static final int TEST_HEIGHT = 16;
    private static final long TEST_FRAME_ID = 7L;
    private static final int BUILD_INFO_BYTES = 128;
    private static final int RENDERER_INFO_BYTES = 512;

    private static final MemoryLayout CAMERA_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("struct_size"),
            JAVA_INT.withName("struct_version"),
            JAVA_LONG.withName("frame_id"),
            JAVA_INT.withName("viewport_width"),
            JAVA_INT.withName("viewport_height"),
            JAVA_DOUBLE.withName("position_x"),
            JAVA_DOUBLE.withName("position_y"),
            JAVA_DOUBLE.withName("position_z"),
            JAVA_FLOAT.withName("rotation_x"),
            JAVA_FLOAT.withName("rotation_y"),
            JAVA_FLOAT.withName("rotation_z"),
            JAVA_FLOAT.withName("rotation_w"),
            JAVA_FLOAT.withName("vertical_fov_radians"),
            JAVA_FLOAT.withName("depth_far"),
            JAVA_INT.withName("reserved_0"),
            JAVA_INT.withName("reserved_1"));
    private static final MemoryLayout SCENE_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("struct_size"),
            JAVA_INT.withName("struct_version"),
            JAVA_INT.withName("origin_x"),
            JAVA_INT.withName("origin_y"),
            JAVA_INT.withName("origin_z"),
            JAVA_INT.withName("vertex_count"),
            JAVA_INT.withName("triangle_count"),
            JAVA_INT.withName("material_count"),
            JAVA_INT.withName("texture_count"),
            JAVA_INT.withName("texture_byte_count"),
            JAVA_INT.withName("reserved_0"),
            JAVA_INT.withName("reserved_1"));
    private static final MemoryLayout VERTEX_LAYOUT = MemoryLayout.structLayout(
            JAVA_FLOAT.withName("position_x"),
            JAVA_FLOAT.withName("position_y"),
            JAVA_FLOAT.withName("position_z"),
            JAVA_FLOAT.withName("normal_x"),
            JAVA_FLOAT.withName("normal_y"),
            JAVA_FLOAT.withName("normal_z"),
            JAVA_FLOAT.withName("texture_u"),
            JAVA_FLOAT.withName("texture_v"),
            JAVA_INT.withName("packed_rgba"),
            JAVA_INT.withName("reserved"));
    private static final MemoryLayout TRIANGLE_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("vertex_0"),
            JAVA_INT.withName("vertex_1"),
            JAVA_INT.withName("vertex_2"),
            JAVA_INT.withName("material_index"));
    private static final MemoryLayout MATERIAL_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("texture_index"),
            JAVA_INT.withName("flags"),
            JAVA_FLOAT.withName("emission_strength"),
            JAVA_FLOAT.withName("alpha_cutoff"),
            JAVA_INT.withName("reserved_0"),
            JAVA_INT.withName("reserved_1"),
            JAVA_INT.withName("reserved_2"),
            JAVA_INT.withName("reserved_3"));
    private static final MemoryLayout TEXTURE_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("width"),
            JAVA_INT.withName("height"),
            JAVA_INT.withName("pixel_offset"),
            JAVA_INT.withName("pixel_size"),
            JAVA_INT.withName("reserved_0"),
            JAVA_INT.withName("reserved_1"),
            JAVA_INT.withName("reserved_2"),
            JAVA_INT.withName("reserved_3"));

    private static BridgeState bridgeState;

    static {
        if (CAMERA_LAYOUT.byteSize() != 80L
                || SCENE_LAYOUT.byteSize() != 48L
                || VERTEX_LAYOUT.byteSize() != 40L
                || TRIANGLE_LAYOUT.byteSize() != 16L
                || MATERIAL_LAYOUT.byteSize() != 32L
                || TEXTURE_LAYOUT.byteSize() != 32L) {
            throw new ExceptionInInitializerError("native bridge structure layout mismatch");
        }
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
            ByteBuffer testFrame = loadedState.fillTestFrame(TEST_WIDTH, TEST_HEIGHT, TEST_FRAME_ID);
            long checksum = verifyTestFrame(testFrame);
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

    public static void uploadScene(ClientRenderSnapshot snapshot) {
        BridgeState state = requireState();
        try {
            state.uploadScene(snapshot);
        } catch (Throwable error) {
            rethrowFatalError(error);
            throw new IllegalStateException("native scene upload failed: " + describe(error), error);
        }
    }

    public static ByteBuffer renderFrame(
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

    public static void close() {
        BridgeState state = bridgeState;
        bridgeState = null;
        if (state != null) {
            state.close();
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
                int expectedRed = (x * 17 + (int) TEST_FRAME_ID) & 0xFF;
                int expectedGreen = (y * 17 + (int) (TEST_FRAME_ID * 3)) & 0xFF;
                int expectedBlue = ((x ^ y) * 15 + (int) (TEST_FRAME_ID * 5)) & 0xFF;
                int[] expected = {expectedRed, expectedGreen, expectedBlue, 0xFF};

                for (int channel = 0; channel < expected.length; channel++) {
                    int actual = Byte.toUnsignedInt(pixels.get(offset + channel));
                    if (actual != expected[channel]) {
                        throw new IllegalStateException(
                                "test frame mismatch at (" + x + "," + y + ") channel " + channel
                                        + ": expected " + expected[channel] + ", got " + actual);
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

    private static final class BridgeState implements AutoCloseable {
        private final Arena libraryArena;
        private final MethodHandle fillTestFrame;
        private final MethodHandle destroyRenderer;
        private final MethodHandle writeRendererInfo;
        private final MethodHandle uploadScene;
        private final MethodHandle render;
        private final MemorySegment renderer;
        private final MemorySegment cameraSegment;
        private final String buildInfo;

        private Arena frameArena;
        private MemorySegment frameSegment;
        private ByteBuffer framePixels;
        private int frameBytes;
        private boolean closed;

        private BridgeState(
                Arena libraryArena,
                MethodHandle fillTestFrame,
                MethodHandle destroyRenderer,
                MethodHandle writeRendererInfo,
                MethodHandle uploadScene,
                MethodHandle render,
                MemorySegment renderer,
                String buildInfo) {
            this.libraryArena = libraryArena;
            this.fillTestFrame = fillTestFrame;
            this.destroyRenderer = destroyRenderer;
            this.writeRendererInfo = writeRendererInfo;
            this.uploadScene = uploadScene;
            this.render = render;
            this.renderer = renderer;
            this.cameraSegment = libraryArena.allocate(CAMERA_LAYOUT);
            this.buildInfo = buildInfo;
        }

        private static BridgeState open(Path libraryPath) throws Throwable {
            Arena libraryArena = Arena.ofConfined();
            MethodHandle destroyRenderer = null;
            MemorySegment renderer = MemorySegment.NULL;
            try {
                Linker linker = Linker.nativeLinker();
                SymbolLookup symbols = SymbolLookup.libraryLookup(libraryPath, libraryArena);
                MethodHandle abiVersion = linker.downcallHandle(
                        symbols.findOrThrow("cycles_bridge_abi_version"),
                        FunctionDescriptor.of(JAVA_INT));
                MethodHandle writeBuildInfo = linker.downcallHandle(
                        symbols.findOrThrow("cycles_bridge_write_build_info"),
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
                MethodHandle fillTestFrame = linker.downcallHandle(
                        symbols.findOrThrow("cycles_bridge_fill_test_frame"),
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG));
                MethodHandle createRenderer = linker.downcallHandle(
                        symbols.findOrThrow("cycles_bridge_create_renderer"),
                        FunctionDescriptor.of(JAVA_INT, ADDRESS));
                destroyRenderer = linker.downcallHandle(
                        symbols.findOrThrow("cycles_bridge_destroy_renderer"),
                        FunctionDescriptor.ofVoid(ADDRESS));
                MethodHandle writeRendererInfo = linker.downcallHandle(
                        symbols.findOrThrow("cycles_bridge_write_renderer_info"),
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
                MethodHandle uploadScene = linker.downcallHandle(
                        symbols.findOrThrow("cycles_bridge_upload_scene"),
                        FunctionDescriptor.of(
                                JAVA_INT,
                                ADDRESS,
                                ADDRESS,
                                ADDRESS,
                                ADDRESS,
                                ADDRESS,
                                ADDRESS,
                                ADDRESS));
                MethodHandle render = linker.downcallHandle(
                        symbols.findOrThrow("cycles_bridge_render"),
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));

                int actualAbiVersion = (int) abiVersion.invokeExact();
                if (actualAbiVersion != ABI_VERSION) {
                    throw new IllegalStateException(
                            "ABI mismatch: Java=" + ABI_VERSION + ", native=" + actualAbiVersion);
                }

                String buildInfo;
                try (Arena dataArena = Arena.ofConfined()) {
                    MemorySegment buildInfoBuffer = dataArena.allocate(BUILD_INFO_BYTES);
                    int buildInfoStatus = (int) writeBuildInfo.invokeExact(
                            buildInfoBuffer, BUILD_INFO_BYTES);
                    checkStatus(buildInfoStatus, "build info call");
                    buildInfo = buildInfoBuffer.getString(0, StandardCharsets.UTF_8);
                }

                MemorySegment rendererOutput = libraryArena.allocate(ADDRESS);
                int createStatus = (int) createRenderer.invokeExact(rendererOutput);
                checkStatus(createStatus, "renderer creation");
                renderer = rendererOutput.get(ADDRESS, 0L);
                if (renderer.address() == 0L) {
                    throw new IllegalStateException("renderer creation returned a null handle");
                }

                return new BridgeState(
                        libraryArena,
                        fillTestFrame,
                        destroyRenderer,
                        writeRendererInfo,
                        uploadScene,
                        render,
                        renderer,
                        buildInfo);
            } catch (Throwable error) {
                if (destroyRenderer != null && renderer.address() != 0L) {
                    try {
                        destroyRenderer.invokeExact(renderer);
                    } catch (Throwable closeError) {
                        error.addSuppressed(closeError);
                    }
                }
                libraryArena.close();
                throw error;
            }
        }

        private void uploadScene(ClientRenderSnapshot snapshot) throws Throwable {
            int vertexCount = snapshot.vertexCount();
            int triangleCount = snapshot.triangleCount();
            if (triangleCount == 0) {
                if (vertexCount != 0 || snapshot.materials().length != 0
                        || snapshot.textures().length != 0) {
                    throw new IllegalArgumentException("empty snapshot contains partial geometry");
                }
            } else if (vertexCount == 0
                    || snapshot.materials().length == 0 || snapshot.textures().length == 0) {
                throw new IllegalArgumentException("render snapshot contains incomplete geometry");
            }
            if (snapshot.vertexData().length
                    != Math.multiplyExact(vertexCount, ClientRenderSnapshot.VERTEX_FLOAT_STRIDE)
                    || snapshot.triangleData().length
                    != Math.multiplyExact(
                            triangleCount, ClientRenderSnapshot.TRIANGLE_INT_STRIDE)) {
                throw new IllegalArgumentException("render snapshot array length mismatch");
            }

            int textureByteCount = 0;
            for (ClientRenderSnapshot.TextureData texture : snapshot.textures()) {
                int expectedBytes = Math.multiplyExact(
                        Math.multiplyExact(texture.width(), texture.height()), 4);
                if (texture.rgbaPixels().length != expectedBytes) {
                    throw new IllegalArgumentException(
                            "texture byte length mismatch for " + texture.sprite());
                }
                textureByteCount = Math.addExact(textureByteCount, expectedBytes);
            }

            try (Arena uploadArena = Arena.ofConfined()) {
                MemorySegment sceneSegment = uploadArena.allocate(SCENE_LAYOUT);
                sceneSegment.set(JAVA_INT, 0L, Math.toIntExact(SCENE_LAYOUT.byteSize()));
                sceneSegment.set(JAVA_INT, 4L, STRUCT_VERSION);
                sceneSegment.set(JAVA_INT, 8L, snapshot.originX());
                sceneSegment.set(JAVA_INT, 12L, snapshot.originY());
                sceneSegment.set(JAVA_INT, 16L, snapshot.originZ());
                sceneSegment.set(JAVA_INT, 20L, vertexCount);
                sceneSegment.set(JAVA_INT, 24L, triangleCount);
                sceneSegment.set(JAVA_INT, 28L, snapshot.materials().length);
                sceneSegment.set(JAVA_INT, 32L, snapshot.textures().length);
                sceneSegment.set(JAVA_INT, 36L, textureByteCount);

                if (triangleCount == 0) {
                    int uploadStatus = (int) uploadScene.invokeExact(
                            renderer,
                            sceneSegment,
                            MemorySegment.NULL,
                            MemorySegment.NULL,
                            MemorySegment.NULL,
                            MemorySegment.NULL,
                            MemorySegment.NULL);
                    checkRendererStatus(uploadStatus, "empty scene upload");
                    return;
                }

                MemorySegment vertices = uploadArena.allocate(
                        Math.multiplyExact((long) vertexCount, VERTEX_LAYOUT.byteSize()),
                        VERTEX_LAYOUT.byteAlignment());
                for (int index = 0; index < vertexCount; index++) {
                    long base = Math.multiplyExact((long) index, VERTEX_LAYOUT.byteSize());
                    int input = index * ClientRenderSnapshot.VERTEX_FLOAT_STRIDE;
                    for (int component = 0; component < 8; component++) {
                        vertices.set(
                                JAVA_FLOAT,
                                base + (long) component * Float.BYTES,
                                snapshot.vertexData()[input + component]);
                    }
                    vertices.set(JAVA_INT, base + 32L, snapshot.vertexColors()[index]);
                }

                MemorySegment triangles = uploadArena.allocate(
                        Math.multiplyExact((long) triangleCount, TRIANGLE_LAYOUT.byteSize()),
                        TRIANGLE_LAYOUT.byteAlignment());
                triangles.asByteBuffer()
                        .order(ByteOrder.nativeOrder())
                        .asIntBuffer()
                        .put(snapshot.triangleData());

                MemorySegment materials = uploadArena.allocate(
                        Math.multiplyExact(
                                (long) snapshot.materials().length, MATERIAL_LAYOUT.byteSize()),
                        MATERIAL_LAYOUT.byteAlignment());
                for (int index = 0; index < snapshot.materials().length; index++) {
                    ClientRenderSnapshot.MaterialData material = snapshot.materials()[index];
                    long base = Math.multiplyExact((long) index, MATERIAL_LAYOUT.byteSize());
                    materials.set(JAVA_INT, base, material.textureIndex());
                    materials.set(JAVA_INT, base + 4L, material.flags());
                    materials.set(JAVA_FLOAT, base + 8L, material.emissionStrength());
                    materials.set(JAVA_FLOAT, base + 12L, material.alphaCutoff());
                }

                MemorySegment textures = uploadArena.allocate(
                        Math.multiplyExact(
                                (long) snapshot.textures().length, TEXTURE_LAYOUT.byteSize()),
                        TEXTURE_LAYOUT.byteAlignment());
                MemorySegment texturePixels = uploadArena.allocate(textureByteCount, 4L);
                int pixelOffset = 0;
                for (int index = 0; index < snapshot.textures().length; index++) {
                    ClientRenderSnapshot.TextureData texture = snapshot.textures()[index];
                    long base = Math.multiplyExact((long) index, TEXTURE_LAYOUT.byteSize());
                    textures.set(JAVA_INT, base, texture.width());
                    textures.set(JAVA_INT, base + 4L, texture.height());
                    textures.set(JAVA_INT, base + 8L, pixelOffset);
                    textures.set(JAVA_INT, base + 12L, texture.rgbaPixels().length);
                    texturePixels
                            .asSlice(pixelOffset, texture.rgbaPixels().length)
                            .asByteBuffer()
                            .put(texture.rgbaPixels());
                    pixelOffset += texture.rgbaPixels().length;
                }

                int uploadStatus = (int) uploadScene.invokeExact(
                        renderer,
                        sceneSegment,
                        vertices,
                        triangles,
                        materials,
                        textures,
                        texturePixels);
                checkRendererStatus(uploadStatus, "scene upload");
            }
        }

        private ByteBuffer fillTestFrame(int width, int height, long frameId) throws Throwable {
            MemorySegment output = ensureFrameBuffer(width, height);
            int status = (int) fillTestFrame.invokeExact(output, width, height, frameId);
            checkStatus(status, "test frame call");
            framePixels.clear();
            return framePixels;
        }

        private ByteBuffer renderFrame(
                int width,
                int height,
                long frameId,
                CameraInput cameraInput) throws Throwable {
            MemorySegment output = ensureFrameBuffer(width, height);
            writeCamera(cameraSegment, width, height, frameId, cameraInput);
            int status = (int) render.invokeExact(
                    renderer,
                    cameraSegment,
                    output,
                    (long) frameBytes);
            checkRendererStatus(status, "renderer frame call");
            framePixels.clear();
            return framePixels;
        }

        private MemorySegment ensureFrameBuffer(int width, int height) {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException(
                        "frame dimensions must be positive, got " + width + "x" + height);
            }

            long byteCount = Math.multiplyExact(Math.multiplyExact((long) width, height), 4L);
            if (byteCount > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("frame is too large: " + width + "x" + height);
            }

            int requiredBytes = (int) byteCount;
            if (frameSegment == null || frameBytes != requiredBytes) {
                if (frameArena != null) {
                    frameArena.close();
                }
                frameArena = Arena.ofConfined();
                frameSegment = frameArena.allocate(requiredBytes, 16);
                framePixels = frameSegment.asByteBuffer();
                frameBytes = requiredBytes;
            }
            return frameSegment;
        }

        private static void writeCamera(
                MemorySegment camera,
                int width,
                int height,
                long frameId,
                CameraInput input) {
            camera.set(JAVA_INT, 0L, Math.toIntExact(CAMERA_LAYOUT.byteSize()));
            camera.set(JAVA_INT, 4L, STRUCT_VERSION);
            camera.set(JAVA_LONG, 8L, frameId);
            camera.set(JAVA_INT, 16L, width);
            camera.set(JAVA_INT, 20L, height);
            camera.set(JAVA_DOUBLE, 24L, input.positionX());
            camera.set(JAVA_DOUBLE, 32L, input.positionY());
            camera.set(JAVA_DOUBLE, 40L, input.positionZ());
            camera.set(JAVA_FLOAT, 48L, input.rotationX());
            camera.set(JAVA_FLOAT, 52L, input.rotationY());
            camera.set(JAVA_FLOAT, 56L, input.rotationZ());
            camera.set(JAVA_FLOAT, 60L, input.rotationW());
            camera.set(JAVA_FLOAT, 64L, input.verticalFovRadians());
            camera.set(JAVA_FLOAT, 68L, input.depthFar());
        }

        private String buildInfo() {
            return buildInfo;
        }

        private String rendererInfo() throws Throwable {
            try (Arena infoArena = Arena.ofConfined()) {
                MemorySegment buffer = infoArena.allocate(RENDERER_INFO_BYTES);
                int status = (int) writeRendererInfo.invokeExact(
                        renderer, buffer, RENDERER_INFO_BYTES);
                checkStatus(status, "renderer info call");
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
            if (frameArena != null) {
                frameArena.close();
                frameArena = null;
                frameSegment = null;
                framePixels = null;
                frameBytes = 0;
            }
            try {
                destroyRenderer.invokeExact(renderer);
            } catch (Throwable error) {
                rethrowFatalError(error);
            } finally {
                libraryArena.close();
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
            float depthFar) {
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

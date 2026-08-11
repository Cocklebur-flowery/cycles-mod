package dev.cyclesrenderer.nativebridge;

import dev.cyclesrenderer.scene.SectionGeometrySnapshot;

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
    public static final int ABI_VERSION = 5;

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
    private static final int MAX_NATIVE_FRAME_BYTES = 480 * 270 * 4;

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
    private static final MemoryLayout RESOURCES_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("struct_size"),
            JAVA_INT.withName("struct_version"),
            JAVA_INT.withName("origin_x"),
            JAVA_INT.withName("origin_y"),
            JAVA_INT.withName("origin_z"),
            JAVA_INT.withName("material_count"),
            JAVA_INT.withName("texture_count"),
            JAVA_INT.withName("texture_byte_count"),
            JAVA_INT.withName("reserved_0"),
            JAVA_INT.withName("reserved_1"),
            JAVA_INT.withName("reserved_2"),
            JAVA_INT.withName("reserved_3"));
    private static final MemoryLayout SECTION_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("struct_size"),
            JAVA_INT.withName("struct_version"),
            JAVA_LONG.withName("section_id"),
            JAVA_INT.withName("origin_x"),
            JAVA_INT.withName("origin_y"),
            JAVA_INT.withName("origin_z"),
            JAVA_INT.withName("vertex_count"),
            JAVA_INT.withName("triangle_count"),
            JAVA_INT.withName("reserved_0"),
            JAVA_INT.withName("reserved_1"),
            JAVA_INT.withName("tail_padding"));
    private static final MemoryLayout FRAME_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("struct_size"),
            JAVA_INT.withName("struct_version"),
            JAVA_INT.withName("width"),
            JAVA_INT.withName("height"),
            JAVA_LONG.withName("generation"),
            JAVA_INT.withName("pixel_byte_count"),
            JAVA_INT.withName("flags"),
            JAVA_INT.withName("sample_count"),
            JAVA_INT.withName("reserved"));
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
                || RESOURCES_LAYOUT.byteSize() != 48L
                || SECTION_LAYOUT.byteSize() != 48L
                || FRAME_LAYOUT.byteSize() != 40L
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

    public static String rendererInfo() {
        BridgeState state = requireState();
        try {
            return state.rendererInfo();
        } catch (Throwable error) {
            rethrowFatalError(error);
            throw new IllegalStateException("native renderer info failed: " + describe(error), error);
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
        private final Arena libraryArena;
        private final MethodHandle fillTestFrame;
        private final MethodHandle destroyRenderer;
        private final MethodHandle writeRendererInfo;
        private final MethodHandle resetScene;
        private final MethodHandle upsertSection;
        private final MethodHandle removeSection;
        private final MethodHandle commitScene;
        private final MethodHandle renderFrame;
        private final MemorySegment renderer;
        private final MemorySegment cameraSegment;
        private final MemorySegment frameInfoSegment;
        private final MemorySegment framePixelsSegment;
        private final ByteBuffer framePixels;
        private final String buildInfo;

        private long generation;
        private boolean closed;

        private BridgeState(
                Arena libraryArena,
                MethodHandle fillTestFrame,
                MethodHandle destroyRenderer,
                MethodHandle writeRendererInfo,
                MethodHandle resetScene,
                MethodHandle upsertSection,
                MethodHandle removeSection,
                MethodHandle commitScene,
                MethodHandle renderFrame,
                MemorySegment renderer,
                String buildInfo) {
            this.libraryArena = libraryArena;
            this.fillTestFrame = fillTestFrame;
            this.destroyRenderer = destroyRenderer;
            this.writeRendererInfo = writeRendererInfo;
            this.resetScene = resetScene;
            this.upsertSection = upsertSection;
            this.removeSection = removeSection;
            this.commitScene = commitScene;
            this.renderFrame = renderFrame;
            this.renderer = renderer;
            this.cameraSegment = libraryArena.allocate(CAMERA_LAYOUT);
            this.frameInfoSegment = libraryArena.allocate(FRAME_LAYOUT);
            this.framePixelsSegment = libraryArena.allocate(MAX_NATIVE_FRAME_BYTES, 16);
            this.framePixels = framePixelsSegment.asByteBuffer();
            this.buildInfo = buildInfo;
        }

        private static BridgeState open(Path libraryPath) throws Throwable {
            Arena libraryArena = Arena.ofConfined();
            MethodHandle destroyRenderer = null;
            MemorySegment renderer = MemorySegment.NULL;
            try {
                Linker linker = Linker.nativeLinker();
                SymbolLookup symbols = SymbolLookup.libraryLookup(libraryPath, libraryArena);
                MethodHandle abiVersion = downcall(linker, symbols, "cycles_bridge_abi_version",
                        FunctionDescriptor.of(JAVA_INT));
                MethodHandle writeBuildInfo = downcall(linker, symbols,
                        "cycles_bridge_write_build_info",
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
                MethodHandle fillTestFrame = downcall(linker, symbols,
                        "cycles_bridge_fill_test_frame",
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG));
                MethodHandle createRenderer = downcall(linker, symbols,
                        "cycles_bridge_create_renderer",
                        FunctionDescriptor.of(JAVA_INT, ADDRESS));
                destroyRenderer = downcall(linker, symbols,
                        "cycles_bridge_destroy_renderer", FunctionDescriptor.ofVoid(ADDRESS));
                MethodHandle writeRendererInfo = downcall(linker, symbols,
                        "cycles_bridge_write_renderer_info",
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
                MethodHandle resetScene = downcall(linker, symbols,
                        "cycles_bridge_reset_scene",
                        FunctionDescriptor.of(
                                JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
                MethodHandle upsertSection = downcall(linker, symbols,
                        "cycles_bridge_upsert_section",
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
                MethodHandle removeSection = downcall(linker, symbols,
                        "cycles_bridge_remove_section",
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
                MethodHandle commitScene = downcall(linker, symbols,
                        "cycles_bridge_commit_scene", FunctionDescriptor.of(JAVA_INT, ADDRESS));
                MethodHandle renderFrame = downcall(linker, symbols,
                        "cycles_bridge_render_frame",
                        FunctionDescriptor.of(
                                JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));

                int actualAbiVersion = (int) abiVersion.invokeExact();
                if (actualAbiVersion != ABI_VERSION) {
                    throw new IllegalStateException(
                            "ABI mismatch: Java=" + ABI_VERSION + ", native=" + actualAbiVersion);
                }

                String buildInfo;
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment buffer = arena.allocate(BUILD_INFO_BYTES);
                    checkStatus((int) writeBuildInfo.invokeExact(buffer, BUILD_INFO_BYTES),
                            "build info call");
                    buildInfo = buffer.getString(0, StandardCharsets.UTF_8);
                }

                MemorySegment rendererOutput = libraryArena.allocate(ADDRESS);
                checkStatus((int) createRenderer.invokeExact(rendererOutput), "renderer creation");
                renderer = rendererOutput.get(ADDRESS, 0L);
                if (renderer.address() == 0L) {
                    throw new IllegalStateException("renderer creation returned a null handle");
                }
                return new BridgeState(
                        libraryArena,
                        fillTestFrame,
                        destroyRenderer,
                        writeRendererInfo,
                        resetScene,
                        upsertSection,
                        removeSection,
                        commitScene,
                        renderFrame,
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

        private static MethodHandle downcall(
                Linker linker,
                SymbolLookup symbols,
                String name,
                FunctionDescriptor descriptor) {
            return linker.downcallHandle(symbols.findOrThrow(name), descriptor);
        }

        private void resetScene(SectionGeometrySnapshot.SceneResources resources) throws Throwable {
            int textureByteCount = validateResources(resources);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment resourceData = arena.allocate(RESOURCES_LAYOUT);
                resourceData.set(JAVA_INT, 0L, Math.toIntExact(RESOURCES_LAYOUT.byteSize()));
                resourceData.set(JAVA_INT, 4L, STRUCT_VERSION);
                resourceData.set(JAVA_INT, 8L, resources.originX());
                resourceData.set(JAVA_INT, 12L, resources.originY());
                resourceData.set(JAVA_INT, 16L, resources.originZ());
                resourceData.set(JAVA_INT, 20L, resources.materials().length);
                resourceData.set(JAVA_INT, 24L, resources.textures().length);
                resourceData.set(JAVA_INT, 28L, textureByteCount);

                MemorySegment materials = writeMaterials(arena, resources.materials());
                TextureSegments textureData = writeTextures(arena, resources.textures(), textureByteCount);
                int status = (int) resetScene.invokeExact(
                        renderer, resourceData, materials, textureData.descriptors(), textureData.pixels());
                checkRendererStatus(status, "scene reset");
                generation = 0L;
            }
        }

        private void upsertSection(SectionGeometrySnapshot snapshot) throws Throwable {
            if (snapshot.empty()) {
                removeSection(snapshot.sectionNode());
                return;
            }
            if (snapshot.vertexData().length != Math.multiplyExact(
                    snapshot.vertexCount(), SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE)
                    || snapshot.vertexColors().length != snapshot.vertexCount()
                    || snapshot.triangleData().length != Math.multiplyExact(
                            snapshot.triangleCount(), SectionGeometrySnapshot.TRIANGLE_INT_STRIDE)) {
                throw new IllegalArgumentException("section geometry array length mismatch");
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment section = arena.allocate(SECTION_LAYOUT);
                section.set(JAVA_INT, 0L, Math.toIntExact(SECTION_LAYOUT.byteSize()));
                section.set(JAVA_INT, 4L, STRUCT_VERSION);
                section.set(JAVA_LONG, 8L, snapshot.sectionNode());
                section.set(JAVA_INT, 16L, snapshot.originX());
                section.set(JAVA_INT, 20L, snapshot.originY());
                section.set(JAVA_INT, 24L, snapshot.originZ());
                section.set(JAVA_INT, 28L, snapshot.vertexCount());
                section.set(JAVA_INT, 32L, snapshot.triangleCount());
                MemorySegment vertices = writeVertices(arena, snapshot);
                MemorySegment triangles = arena.allocate(
                        Math.multiplyExact((long) snapshot.triangleCount(), TRIANGLE_LAYOUT.byteSize()),
                        TRIANGLE_LAYOUT.byteAlignment());
                triangles.asByteBuffer().order(ByteOrder.nativeOrder()).asIntBuffer()
                        .put(snapshot.triangleData());
                int status = (int) upsertSection.invokeExact(
                        renderer, section, vertices, triangles);
                checkRendererStatus(status, "section upsert");
            }
        }

        private void removeSection(long sectionId) throws Throwable {
            checkRendererStatus(
                    (int) removeSection.invokeExact(renderer, sectionId), "section removal");
        }

        private void commitScene() throws Throwable {
            checkRendererStatus((int) commitScene.invokeExact(renderer), "scene commit");
        }

        private RenderedFrame renderFrame(
                int width,
                int height,
                long frameId,
                CameraInput input) throws Throwable {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("invalid viewport " + width + "x" + height);
            }
            writeCamera(cameraSegment, width, height, frameId, input);
            frameInfoSegment.set(JAVA_INT, 0L, Math.toIntExact(FRAME_LAYOUT.byteSize()));
            frameInfoSegment.set(JAVA_INT, 4L, STRUCT_VERSION);
            frameInfoSegment.set(JAVA_LONG, 16L, generation);
            int status = (int) renderFrame.invokeExact(
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

        private ByteBuffer fillTestFrame(int width, int height, long frameId) throws Throwable {
            int bytes = Math.multiplyExact(Math.multiplyExact(width, height), 4);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment output = arena.allocate(bytes, 16);
                checkStatus((int) fillTestFrame.invokeExact(output, width, height, frameId),
                        "test frame call");
                ByteBuffer copied = ByteBuffer.allocate(bytes);
                copied.put(output.asByteBuffer()).flip();
                return copied;
            }
        }

        private static int validateResources(SectionGeometrySnapshot.SceneResources resources) {
            if (resources.materials().length == 0 || resources.textures().length == 0) {
                throw new IllegalArgumentException("scene resources cannot be empty");
            }
            int total = 0;
            for (SectionGeometrySnapshot.TextureData texture : resources.textures()) {
                int expected = Math.multiplyExact(Math.multiplyExact(texture.width(), texture.height()), 4);
                if (texture.rgbaPixels().length != expected) {
                    throw new IllegalArgumentException("texture byte length mismatch for " + texture.atlas());
                }
                total = Math.addExact(total, expected);
            }
            return total;
        }

        private static MemorySegment writeMaterials(
                Arena arena,
                SectionGeometrySnapshot.MaterialData[] source) {
            MemorySegment output = arena.allocate(
                    Math.multiplyExact((long) source.length, MATERIAL_LAYOUT.byteSize()),
                    MATERIAL_LAYOUT.byteAlignment());
            for (int index = 0; index < source.length; index++) {
                SectionGeometrySnapshot.MaterialData material = source[index];
                long base = index * MATERIAL_LAYOUT.byteSize();
                output.set(JAVA_INT, base, material.textureIndex());
                output.set(JAVA_INT, base + 4L, material.flags());
                output.set(JAVA_FLOAT, base + 8L, material.emissionStrength());
                output.set(JAVA_FLOAT, base + 12L, material.alphaCutoff());
            }
            return output;
        }

        private static TextureSegments writeTextures(
                Arena arena,
                SectionGeometrySnapshot.TextureData[] source,
                int totalBytes) {
            MemorySegment descriptors = arena.allocate(
                    Math.multiplyExact((long) source.length, TEXTURE_LAYOUT.byteSize()),
                    TEXTURE_LAYOUT.byteAlignment());
            MemorySegment pixels = arena.allocate(totalBytes, 4);
            int pixelOffset = 0;
            for (int index = 0; index < source.length; index++) {
                SectionGeometrySnapshot.TextureData texture = source[index];
                long base = index * TEXTURE_LAYOUT.byteSize();
                descriptors.set(JAVA_INT, base, texture.width());
                descriptors.set(JAVA_INT, base + 4L, texture.height());
                descriptors.set(JAVA_INT, base + 8L, pixelOffset);
                descriptors.set(JAVA_INT, base + 12L, texture.rgbaPixels().length);
                pixels.asSlice(pixelOffset, texture.rgbaPixels().length)
                        .asByteBuffer().put(texture.rgbaPixels());
                pixelOffset += texture.rgbaPixels().length;
            }
            return new TextureSegments(descriptors, pixels);
        }

        private static MemorySegment writeVertices(
                Arena arena,
                SectionGeometrySnapshot snapshot) {
            MemorySegment output = arena.allocate(
                    Math.multiplyExact((long) snapshot.vertexCount(), VERTEX_LAYOUT.byteSize()),
                    VERTEX_LAYOUT.byteAlignment());
            for (int index = 0; index < snapshot.vertexCount(); index++) {
                long base = index * VERTEX_LAYOUT.byteSize();
                int input = index * SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE;
                for (int component = 0; component < SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE;
                     component++) {
                    output.set(JAVA_FLOAT, base + (long) component * Float.BYTES,
                            snapshot.vertexData()[input + component]);
                }
                output.set(JAVA_INT, base + 32L, snapshot.vertexColors()[index]);
            }
            return output;
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
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(RENDERER_INFO_BYTES);
                checkStatus((int) writeRendererInfo.invokeExact(
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
                destroyRenderer.invokeExact(renderer);
            } catch (Throwable error) {
                rethrowFatalError(error);
            } finally {
                libraryArena.close();
            }
        }

        private record TextureSegments(MemorySegment descriptors, MemorySegment pixels) {
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

    public record RenderedFrame(
            boolean ready,
            boolean updated,
            int width,
            int height,
            long generation,
            int sampleCount,
            ByteBuffer pixels) {
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

package dev.cyclesrenderer.nativebridge;

import dev.cyclesrenderer.config.CameraAutomationSettings;
import dev.cyclesrenderer.config.CyclesRenderSettings;
import dev.cyclesrenderer.scene.SectionGeometrySnapshot;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeBridgeContractTest {
    private static final String EXPECTED_PUBLIC_SURFACE_SHA256 =
            "692745869890fae3afd109881a274ae29d4deeb24798d6e73964ac5a4b8c48c7";
    private static final String EXPECTED_LAYOUT_SHA256 =
            "d578c93283f841b1f794e406c537094858e397ebed6d9e520654dcda0b97a16f";
    private static final String EXPECTED_SYMBOL_TABLE_SHA256 =
            "082938fc97164a89f40200d9b639ec2b745f84da286d0a11294878d117193101";
    private static final String EXPECTED_SETTINGS_BYTES_SHA256 =
            "5fe2085228a3b850f95ce957d70ac2a6a64544fb53d3a072ef6ab8e6fc52f4d8";
    private static final String EXPECTED_SCENE_RESOURCES_BYTES_SHA256 =
            "628596f41db25d96af6ed23724770cf710433ee98b90e7c0a2aad69a086ad9c8";
    private static final String EXPECTED_SECTION_BYTES_SHA256 =
            "529c0c70873dfad6f97f474c7ec510e769e3c232b9e1a063d1e49623ce0f3128";
    private static final String EXPECTED_FRAME_REQUEST_BYTES_SHA256 =
            "2e9b6a719c40ab8f25855bb56f29639504d6d3283d89c5428aaddebe01d8b2fe";
    private static final String EXPECTED_VULKAN_REQUEST_BYTES_SHA256 =
            "3b965ce4bfa01deb727a33b90c356149eb18907bd29518ef22a2687d430abb4b";

    @Test
    void publicFacadeRemainsStable() throws IllegalAccessException {
        assertEquals(43, NativeBridge.ABI_VERSION);
        assertEquals(8, NativeBridge.DEVICE_UPDATE_PHASE_COUNT);
        assertEquals(2, NativeBridge.PIXEL_FORMAT_RGBA16_FLOAT);
        assertEquals(3, NativeBridge.PIXEL_FORMAT_RGBA32_FLOAT);

        String fingerprint = fingerprint(publicSurfaceLines());
        assertEquals(EXPECTED_PUBLIC_SURFACE_SHA256, fingerprint,
                "NativeBridge public surface changed: " + fingerprint);
    }

    @Test
    void nativeLayoutsKeepNamesOffsetsAndSizes() throws ReflectiveOperationException {
        List<String> layouts = new ArrayList<>();
        for (Field field : NativeLayouts.class.getDeclaredFields()) {
            if (field.getType() != MemoryLayout.class) {
                continue;
            }
            field.setAccessible(true);
            MemoryLayout layout = (MemoryLayout) field.get(null);
            layouts.add(layoutLine(field.getName(), layout));
        }
        layouts.sort(Comparator.naturalOrder());
        assertFalse(layouts.isEmpty());

        String fingerprint = fingerprint(layouts);
        assertEquals(EXPECTED_LAYOUT_SHA256, fingerprint,
                "NativeBridge memory layouts changed: " + fingerprint);
    }

    @Test
    void nativeSymbolNamesAndDescriptorsRemainStable() {
        List<String> symbols = new ArrayList<>();
        for (NativeLibrary.Symbol symbol : NativeLibrary.Symbol.values()) {
            symbols.add(symbol.externalName + "=" + symbol.descriptor);
        }
        symbols.sort(Comparator.naturalOrder());

        String fingerprint = fingerprint(symbols);
        assertEquals(EXPECTED_SYMBOL_TABLE_SHA256, fingerprint,
                "NativeBridge symbol table changed: " + fingerprint);
    }

    @Test
    void nativeSettingsMarshallerPreservesWireBytes() throws ReflectiveOperationException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment target = arena.allocate(NativeLayouts.SETTINGS_LAYOUT);
            target.fill((byte) 0x5a);
            NativeSettingsMarshaller.write(target, 1, sampleSettings());

            String fingerprint = sha256(target.toArray(JAVA_BYTE));
            assertEquals(EXPECTED_SETTINGS_BYTES_SHA256, fingerprint,
                    "Native settings wire bytes changed: " + fingerprint);
        }
    }

    @Test
    void nativeSceneResourcesMarshallerPreservesWireBytes() {
        try (Arena arena = Arena.ofConfined()) {
            NativeSceneMarshaller.SceneResourcesSegments segments =
                    NativeSceneMarshaller.writeResources(arena, 1, sampleSceneResources());
            String fingerprint = sha256(concatenate(
                    segments.resources(),
                    segments.materials(),
                    segments.textureDescriptors(),
                    segments.texturePixels()));
            assertEquals(EXPECTED_SCENE_RESOURCES_BYTES_SHA256, fingerprint,
                    "Native scene-resource wire bytes changed: " + fingerprint);
        }
    }

    @Test
    void nativeSectionMarshallerPreservesWireBytes() {
        try (Arena arena = Arena.ofConfined()) {
            NativeSceneMarshaller.SectionSegments segments =
                    NativeSceneMarshaller.writeSection(arena, 1, sampleSection());
            String fingerprint = sha256(concatenate(
                    segments.section(), segments.vertices(), segments.triangles()));
            assertEquals(EXPECTED_SECTION_BYTES_SHA256, fingerprint,
                    "Native section wire bytes changed: " + fingerprint);
        }
    }

    @Test
    void nativeSceneMarshallerPreservesValidationErrors() {
        try (Arena arena = Arena.ofConfined()) {
            IllegalArgumentException emptyResources = assertThrows(
                    IllegalArgumentException.class,
                    () -> NativeSceneMarshaller.writeResources(
                            arena,
                            1,
                            new SectionGeometrySnapshot.SceneResources(
                                    0,
                                    0,
                                    0,
                                    new SectionGeometrySnapshot.MaterialData[0],
                                    new SectionGeometrySnapshot.TextureData[0])));
            assertEquals("scene resources cannot be empty", emptyResources.getMessage());

            SectionGeometrySnapshot valid = sampleSection();
            SectionGeometrySnapshot invalid = new SectionGeometrySnapshot(
                    valid.sectionNode(),
                    valid.originX(),
                    valid.originY(),
                    valid.originZ(),
                    new float[] {1.0f},
                    valid.vertexColors(),
                    valid.triangleData(),
                    valid.quadCount(),
                    valid.sequence());
            IllegalArgumentException invalidGeometry = assertThrows(
                    IllegalArgumentException.class,
                    () -> NativeSceneMarshaller.writeSection(arena, 1, invalid));
            assertEquals(
                    "section geometry array length mismatch",
                    invalidGeometry.getMessage());
        }
    }

    @Test
    void nativeFrameMarshallerPreservesRequestBytesAndViewportError() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment camera = arena.allocate(NativeLayouts.CAMERA_LAYOUT);
            MemorySegment frameInfo = arena.allocate(NativeLayouts.FRAME_LAYOUT);
            MemorySegment frameView = arena.allocate(NativeLayouts.FRAME_VIEW_LAYOUT);
            frameInfo.fill((byte) 0x5a);
            frameView.fill((byte) 0x5a);

            NativeFrameMarshaller.writeCamera(
                    camera,
                    1,
                    1920,
                    1080,
                    0x0102030405060708L,
                    new NativeBridge.CameraInput(
                            1.25,
                            -2.5,
                            3.75,
                            0.1f,
                            0.2f,
                            0.3f,
                            0.4f,
                            1.5f,
                            4096.0f,
                            12.5f,
                            NativeBridge.CAMERA_FOCUS_DISTANCE_VALID));
            NativeFrameMarshaller.prepareFrameInfo(
                    frameInfo, 1, 0x1112131415161718L);
            NativeFrameMarshaller.prepareFrameView(frameView, 1);

            String fingerprint = sha256(concatenate(camera, frameInfo, frameView));
            assertEquals(EXPECTED_FRAME_REQUEST_BYTES_SHA256, fingerprint,
                    "Native camera/frame request bytes changed: " + fingerprint);

            IllegalArgumentException invalidViewport = assertThrows(
                    IllegalArgumentException.class,
                    () -> NativeFrameMarshaller.writeCamera(
                            camera, 1, 0, 1080, 1L,
                            new NativeBridge.CameraInput(
                                    0.0, 0.0, 0.0,
                                    0.0f, 0.0f, 0.0f, 1.0f,
                                    1.0f, 1.0f, 1.0f, 0)));
            assertEquals("invalid viewport 0x1080", invalidViewport.getMessage());
        }
    }

    @Test
    void nativeFrameLeaseReleasesOnceAndInvalidatesPixels() {
        AtomicInteger releaseCount = new AtomicInteger();
        Arena leaseArena = Arena.ofConfined();
        NativeFrameMarshaller.FrameLease lease =
                new NativeFrameMarshaller.FrameLease(
                        leaseArena,
                        token -> {
                            assertEquals(77L, token);
                            releaseCount.incrementAndGet();
                        },
                        77L,
                        leaseArena.allocate(16, 8).asByteBuffer());
        try {
            lease.pixels().putLong(0, 0x0102030405060708L);
            lease.close();
            lease.close();
            assertThrows(IllegalStateException.class, () -> lease.pixels().get(0));
        } finally {
            lease.close();
        }
        assertEquals(1, releaseCount.get());
    }

    @Test
    void nativeVulkanMarshallerPreservesRequestBytesAndErrors() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment descriptor =
                    NativeVulkanInteropMarshaller.writeBufferDescriptor(
                            arena,
                            1,
                            NativeBridge.PIXEL_FORMAT_RGBA16_FLOAT,
                            1920,
                            1080,
                            0x0102030405060708L,
                            0x1112131415161718L,
                            0x2122232425262728L,
                            0x3132333435363738L,
                            "00112233445566778899aabbccddeeff",
                            3,
                            24883200);
            MemorySegment state = arena.allocate(VulkanInteropStateAbi.LAYOUT);
            state.fill((byte) 0x5a);
            NativeVulkanInteropMarshaller.prepareState(state, 1);

            String fingerprint = sha256(concatenate(descriptor, state));
            assertEquals(EXPECTED_VULKAN_REQUEST_BYTES_SHA256, fingerprint,
                    "Native Vulkan request bytes changed: " + fingerprint);

            IllegalArgumentException invalidDescriptor = assertThrows(
                    IllegalArgumentException.class,
                    () -> NativeVulkanInteropMarshaller.writeBufferDescriptor(
                            arena, 1, 2, 0, 1080, 1L, 1L, 2L, 3L,
                            "00112233445566778899aabbccddeeff", 3, 8));
            assertEquals(
                    "invalid Vulkan interop buffer descriptor",
                    invalidDescriptor.getMessage());

            IllegalArgumentException invalidUuid = assertThrows(
                    IllegalArgumentException.class,
                    () -> NativeVulkanInteropMarshaller.writeBufferDescriptor(
                            arena, 1, 2, 1920, 1080, 1L, 1L, 2L, 3L,
                            "bad", 3, 8));
            assertEquals("invalid Vulkan device UUID: bad", invalidUuid.getMessage());
        }
    }

    @Test
    void nativeVulkanMarshallerPreservesStateDecoding() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(VulkanInteropStateAbi.LAYOUT);
            NativeVulkanInteropMarshaller.prepareState(state, 1);
            state.set(JAVA_INT,
                    VulkanInteropStateAbi.FLAGS_OFFSET, 31);
            state.set(JAVA_INT,
                    VulkanInteropStateAbi.WIDTH_OFFSET, 1920);
            state.set(JAVA_INT,
                    VulkanInteropStateAbi.HEIGHT_OFFSET, 1080);
            state.set(JAVA_INT,
                    VulkanInteropStateAbi.SAMPLE_COUNT_OFFSET, 64);
            state.set(JAVA_LONG,
                    VulkanInteropStateAbi.GENERATION_OFFSET, 101L);
            state.set(JAVA_LONG,
                    VulkanInteropStateAbi.COMPLETED_FRAME_COUNT_OFFSET, 202L);
            state.set(JAVA_INT,
                    VulkanInteropStateAbi.LAST_SYNC_MICROS_OFFSET, 303);
            state.set(JAVA_INT,
                    VulkanInteropStateAbi.EMA_SYNC_MICROS_OFFSET, 404);
            state.set(JAVA_INT,
                    VulkanInteropStateAbi.MAX_SYNC_MICROS_OFFSET, 505);
            state.set(JAVA_INT,
                    VulkanInteropStateAbi.SLOT_INDEX_OFFSET, 1);
            state.set(JAVA_INT,
                    VulkanInteropStateAbi.SLOT_COUNT_OFFSET, 3);
            state.set(JAVA_INT,
                    VulkanInteropStateAbi.READY_SLOT_COUNT_OFFSET, 2);
            state.set(JAVA_LONG,
                    VulkanInteropStateAbi.PRODUCER_WAIT_COUNT_OFFSET, 606L);
            state.set(JAVA_INT,
                    VulkanInteropStateAbi.DEPTH_WIDTH_OFFSET, 1280);
            state.set(JAVA_INT,
                    VulkanInteropStateAbi.DEPTH_HEIGHT_OFFSET, 720);

            assertEquals(
                    new NativeBridge.VulkanInteropState(
                            31, 1920, 1080, 64, 101L, 202L,
                            303, 404, 505, 1, 3, 2, 606L, 1280, 720),
                    NativeVulkanInteropMarshaller.decodeState(state));
        }
    }

    private static List<String> publicSurfaceLines() throws IllegalAccessException {
        List<String> lines = new ArrayList<>();
        for (Field field : NativeBridge.class.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers())) {
                lines.add("field " + field.getName() + ":" + typeName(field.getType())
                        + "=" + field.get(null));
            }
        }
        for (Method method : NativeBridge.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                lines.add("method NativeBridge." + methodSignature(method));
            }
        }
        for (Class<?> nested : NativeBridge.class.getDeclaredClasses()) {
            if (!Modifier.isPublic(nested.getModifiers())) {
                continue;
            }
            lines.add("type " + nested.getSimpleName() + ":" + nested.getModifiers());
            for (Constructor<?> constructor : nested.getDeclaredConstructors()) {
                if (Modifier.isPublic(constructor.getModifiers())) {
                    lines.add("ctor " + nested.getSimpleName() + parameters(constructor.getParameterTypes()));
                }
            }
            for (Method method : nested.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers())) {
                    lines.add("method " + nested.getSimpleName() + "." + methodSignature(method));
                }
            }
            RecordComponent[] components = nested.getRecordComponents();
            if (components != null) {
                for (RecordComponent component : components) {
                    lines.add("component " + nested.getSimpleName() + "." + component.getName()
                            + ":" + typeName(component.getType()));
                }
            }
        }
        lines.sort(Comparator.naturalOrder());
        return lines;
    }

    private static String layoutLine(String fieldName, MemoryLayout layout) {
        StringBuilder line = new StringBuilder(fieldName)
                .append(" size=").append(layout.byteSize())
                .append(" align=").append(layout.byteAlignment());
        List<MemoryLayout> members = ((GroupLayout) layout).memberLayouts();
        for (MemoryLayout member : members) {
            String name = member.name().orElseThrow();
            line.append('|').append(name)
                    .append('@').append(layout.byteOffset(PathElement.groupElement(name)))
                    .append(':').append(member.byteSize())
                    .append('/').append(member.byteAlignment());
        }
        return line.toString();
    }

    private static String methodSignature(Method method) {
        return method.getName() + parameters(method.getParameterTypes())
                + ":" + typeName(method.getReturnType());
    }

    private static String parameters(Class<?>[] parameterTypes) {
        StringBuilder result = new StringBuilder("(");
        for (int index = 0; index < parameterTypes.length; index++) {
            if (index > 0) {
                result.append(',');
            }
            result.append(typeName(parameterTypes[index]));
        }
        return result.append(')').toString();
    }

    private static String typeName(Class<?> type) {
        return type.isArray() ? typeName(type.componentType()) + "[]" : type.getName();
    }

    private static CyclesRenderSettings sampleSettings() throws ReflectiveOperationException {
        RecordComponent[] components = CyclesRenderSettings.class.getRecordComponents();
        Class<?>[] parameterTypes = new Class<?>[components.length];
        Object[] arguments = new Object[components.length];
        for (int index = 0; index < components.length; index++) {
            Class<?> type = components[index].getType();
            parameterTypes[index] = type;
            if (type == long.class) {
                arguments[index] = 0x0102030405060708L;
            } else if (type == int.class) {
                arguments[index] = 1000 + index;
            } else if (type == float.class) {
                arguments[index] = index + 0.25f;
            } else if (type == boolean.class) {
                arguments[index] = (index & 1) == 0;
            } else if (type.isEnum()) {
                Object[] constants = type.getEnumConstants();
                arguments[index] = constants[index % constants.length];
            } else if (type == CameraAutomationSettings.class) {
                arguments[index] = null;
            } else {
                throw new AssertionError("unsupported settings component " + type.getName());
            }
        }
        Constructor<CyclesRenderSettings> constructor =
                CyclesRenderSettings.class.getDeclaredConstructor(parameterTypes);
        return constructor.newInstance(arguments);
    }

    private static SectionGeometrySnapshot.SceneResources sampleSceneResources() {
        SectionGeometrySnapshot.TextureData[] textures = {
                texture("color", new byte[] {1, 2, 3, 4},
                        SectionGeometrySnapshot.TEXTURE_ROLE_COLOR_SRGB),
                texture("normal", new byte[] {5, 6, 7, 8},
                        SectionGeometrySnapshot.TEXTURE_ROLE_DATA_LINEAR),
                texture("material", new byte[] {9, 10, 11, 12},
                        SectionGeometrySnapshot.TEXTURE_ROLE_DATA_LINEAR),
                texture("auxiliary", new byte[] {13, 14, 15, 16},
                        SectionGeometrySnapshot.TEXTURE_ROLE_DATA_LINEAR)
        };
        SectionGeometrySnapshot.MaterialData[] materials = {
                new SectionGeometrySnapshot.MaterialData(
                        0,
                        SectionGeometrySnapshot.MATERIAL_FLAG_CUTOUT
                                | SectionGeometrySnapshot.MATERIAL_FLAG_TRANSMISSION,
                        2.5f,
                        0.25f,
                        1,
                        2,
                        SectionGeometrySnapshot.PBR_FORMAT_LAB_1_3,
                        3)
        };
        return new SectionGeometrySnapshot.SceneResources(
                11, -22, 33, materials, textures);
    }

    private static SectionGeometrySnapshot.TextureData texture(
            String path,
            byte[] pixels,
            int role) {
        return new SectionGeometrySnapshot.TextureData(
                Identifier.fromNamespaceAndPath("cyclesrenderer", path),
                1,
                1,
                pixels,
                role);
    }

    private static SectionGeometrySnapshot sampleSection() {
        float[] vertices = new float[3 * SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE];
        for (int index = 0; index < vertices.length; index++) {
            vertices[index] = index + 0.25f;
        }
        return new SectionGeometrySnapshot(
                0x0102030405060708L,
                -17,
                34,
                -51,
                vertices,
                new int[] {0x10203040, 0x50607080, 0x90a0b0c0},
                new int[] {0, 1, 2, SectionGeometrySnapshot.MATERIAL_GLASS},
                1,
                99L);
    }

    private static byte[] concatenate(MemorySegment... segments) {
        int length = 0;
        for (MemorySegment segment : segments) {
            length = Math.addExact(length, Math.toIntExact(segment.byteSize()));
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (MemorySegment segment : segments) {
            byte[] bytes = segment.toArray(JAVA_BYTE);
            System.arraycopy(bytes, 0, result, offset, bytes.length);
            offset += bytes.length;
        }
        return result;
    }

    private static String fingerprint(List<String> lines) {
        String canonical = String.join("\n", lines) + "\n";
        return sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 unavailable", exception);
        }
    }
}

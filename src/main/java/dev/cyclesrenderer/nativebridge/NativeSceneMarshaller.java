package dev.cyclesrenderer.nativebridge;

import dev.cyclesrenderer.scene.SectionGeometrySnapshot;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;

import static dev.cyclesrenderer.nativebridge.NativeLayouts.MATERIAL_LAYOUT;
import static dev.cyclesrenderer.nativebridge.NativeLayouts.RESOURCES_LAYOUT;
import static dev.cyclesrenderer.nativebridge.NativeLayouts.SECTION_LAYOUT;
import static dev.cyclesrenderer.nativebridge.NativeLayouts.TEXTURE_LAYOUT;
import static dev.cyclesrenderer.nativebridge.NativeLayouts.TRIANGLE_LAYOUT;
import static dev.cyclesrenderer.nativebridge.NativeLayouts.VERTEX_LAYOUT;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** Validates and encodes scene resources and section geometry for native calls. */
final class NativeSceneMarshaller {
    private NativeSceneMarshaller() {
    }

    static SceneResourcesSegments writeResources(
            Arena arena,
            int structVersion,
            SectionGeometrySnapshot.SceneResources resources) {
        int textureByteCount = validateResources(resources);
        MemorySegment resourceData = arena.allocate(RESOURCES_LAYOUT);
        resourceData.set(JAVA_INT, 0L, Math.toIntExact(RESOURCES_LAYOUT.byteSize()));
        resourceData.set(JAVA_INT, 4L, structVersion);
        resourceData.set(JAVA_INT, 8L, resources.originX());
        resourceData.set(JAVA_INT, 12L, resources.originY());
        resourceData.set(JAVA_INT, 16L, resources.originZ());
        resourceData.set(JAVA_INT, 20L, resources.materials().length);
        resourceData.set(JAVA_INT, 24L, resources.textures().length);
        resourceData.set(JAVA_INT, 28L, textureByteCount);

        MemorySegment materials = writeMaterials(arena, resources.materials());
        TextureSegments textureData = writeTextures(
                arena, resources.textures(), textureByteCount);
        return new SceneResourcesSegments(
                resourceData, materials, textureData.descriptors(), textureData.pixels());
    }

    static SectionSegments writeSection(
            Arena arena,
            int structVersion,
            SectionGeometrySnapshot snapshot) {
        if (snapshot.vertexData().length != Math.multiplyExact(
                snapshot.vertexCount(), SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE)
                || snapshot.vertexColors().length != snapshot.vertexCount()
                || snapshot.triangleData().length != Math.multiplyExact(
                        snapshot.triangleCount(),
                        SectionGeometrySnapshot.TRIANGLE_INT_STRIDE)) {
            throw new IllegalArgumentException("section geometry array length mismatch");
        }

        MemorySegment section = arena.allocate(SECTION_LAYOUT);
        section.set(JAVA_INT, 0L, Math.toIntExact(SECTION_LAYOUT.byteSize()));
        section.set(JAVA_INT, 4L, structVersion);
        section.set(JAVA_LONG, 8L, snapshot.sectionNode());
        section.set(JAVA_INT, 16L, snapshot.originX());
        section.set(JAVA_INT, 20L, snapshot.originY());
        section.set(JAVA_INT, 24L, snapshot.originZ());
        section.set(JAVA_INT, 28L, snapshot.vertexCount());
        section.set(JAVA_INT, 32L, snapshot.triangleCount());

        MemorySegment vertices = writeVertices(arena, snapshot);
        MemorySegment triangles = arena.allocate(
                Math.multiplyExact(
                        (long) snapshot.triangleCount(), TRIANGLE_LAYOUT.byteSize()),
                TRIANGLE_LAYOUT.byteAlignment());
        triangles.asByteBuffer().order(ByteOrder.nativeOrder()).asIntBuffer()
                .put(snapshot.triangleData());
        return new SectionSegments(section, vertices, triangles);
    }

    private static int validateResources(
            SectionGeometrySnapshot.SceneResources resources) {
        if (resources.materials().length == 0 || resources.textures().length == 0) {
            throw new IllegalArgumentException("scene resources cannot be empty");
        }
        int total = 0;
        for (SectionGeometrySnapshot.TextureData texture : resources.textures()) {
            int expected = Math.multiplyExact(
                    Math.multiplyExact(texture.width(), texture.height()), 4);
            if (texture.rgbaPixels().length != expected) {
                throw new IllegalArgumentException(
                        "texture byte length mismatch for " + texture.atlas());
            }
            if (texture.role() != SectionGeometrySnapshot.TEXTURE_ROLE_COLOR_SRGB
                    && texture.role() != SectionGeometrySnapshot.TEXTURE_ROLE_DATA_LINEAR) {
                throw new IllegalArgumentException(
                        "unsupported texture role for " + texture.atlas());
            }
            total = Math.addExact(total, expected);
        }
        for (SectionGeometrySnapshot.MaterialData material : resources.materials()) {
            validateTextureIndex(resources, material.textureIndex(),
                    SectionGeometrySnapshot.TEXTURE_ROLE_COLOR_SRGB, "base color");
            if (material.pbrFormat() == SectionGeometrySnapshot.PBR_FORMAT_NONE) {
                if (material.normalTextureIndex()
                                != SectionGeometrySnapshot.TEXTURE_INDEX_INVALID
                        || material.materialTextureIndex()
                                != SectionGeometrySnapshot.TEXTURE_INDEX_INVALID
                        || material.auxiliaryTextureIndex()
                                != SectionGeometrySnapshot.TEXTURE_INDEX_INVALID) {
                    throw new IllegalArgumentException(
                            "non-PBR material references PBR data textures");
                }
            } else if (material.pbrFormat()
                    == SectionGeometrySnapshot.PBR_FORMAT_LAB_1_3) {
                validateTextureIndex(resources, material.normalTextureIndex(),
                        SectionGeometrySnapshot.TEXTURE_ROLE_DATA_LINEAR, "normal");
                validateTextureIndex(resources, material.materialTextureIndex(),
                        SectionGeometrySnapshot.TEXTURE_ROLE_DATA_LINEAR, "material data");
                validateTextureIndex(resources, material.auxiliaryTextureIndex(),
                        SectionGeometrySnapshot.TEXTURE_ROLE_DATA_LINEAR, "LabPBR auxiliary");
            } else {
                throw new IllegalArgumentException(
                        "unsupported PBR format " + material.pbrFormat());
            }
        }
        return total;
    }

    private static void validateTextureIndex(
            SectionGeometrySnapshot.SceneResources resources,
            int index,
            int expectedRole,
            String usage) {
        if (index < 0 || index >= resources.textures().length) {
            throw new IllegalArgumentException(usage + " texture index is out of range");
        }
        if (resources.textures()[index].role() != expectedRole) {
            throw new IllegalArgumentException(usage + " texture has the wrong role");
        }
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
            output.set(JAVA_INT, base + 16L, material.normalTextureIndex());
            output.set(JAVA_INT, base + 20L, material.materialTextureIndex());
            output.set(JAVA_INT, base + 24L, material.pbrFormat());
            output.set(JAVA_INT, base + 28L, material.auxiliaryTextureIndex());
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
            descriptors.set(JAVA_INT, base + 16L, texture.role());
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
            for (int component = 0;
                 component < SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE;
                 component++) {
                output.set(JAVA_FLOAT, base + (long) component * Float.BYTES,
                        snapshot.vertexData()[input + component]);
            }
            output.set(JAVA_INT, base + 32L, snapshot.vertexColors()[index]);
        }
        return output;
    }

    record SceneResourcesSegments(
            MemorySegment resources,
            MemorySegment materials,
            MemorySegment textureDescriptors,
            MemorySegment texturePixels) {
    }

    record SectionSegments(
            MemorySegment section,
            MemorySegment vertices,
            MemorySegment triangles) {
    }

    private record TextureSegments(
            MemorySegment descriptors,
            MemorySegment pixels) {
    }
}

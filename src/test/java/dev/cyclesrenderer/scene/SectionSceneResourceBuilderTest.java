package dev.cyclesrenderer.scene;

import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SectionSceneResourceBuilderTest {
    @Test
    void measuresAtlasAndCopiesArgbPixelsAsRgba() {
        List<SectionSceneResourceBuilder.SpriteInput> sprites = List.of(
                sprite(0.0F, 0.0F, 0.5F, 0.5F, 2, 1,
                        new int[] {0x7F112233, 0x80445566}),
                sprite(0.5F, 0.5F, 1.0F, 1.0F, 2, 1,
                        new int[] {0xFF778899, 0x00AABBCC}));

        SectionSceneResourceBuilder.AtlasLayout layout =
                SectionSceneResourceBuilder.measure(sprites);
        byte[] pixels = SectionSceneResourceBuilder.copyRgba(layout, sprites);

        assertEquals(4, layout.width());
        assertEquals(2, layout.height());
        assertArrayEquals(
                new byte[] {
                    0x11, 0x22, 0x33, 0x7F,
                    0x44, 0x55, 0x66, (byte) 0x80,
                    0, 0, 0, 0,
                    0, 0, 0, 0,
                    0, 0, 0, 0,
                    0, 0, 0, 0,
                    0x77, (byte) 0x88, (byte) 0x99, (byte) 0xFF,
                    (byte) 0xAA, (byte) 0xBB, (byte) 0xCC, 0
                },
                pixels);
    }

    @Test
    void rejectsAtlasWithoutUsableDimensions() {
        assertThrows(
                IllegalStateException.class,
                () -> SectionSceneResourceBuilder.measure(List.of(
                        sprite(0.0F, 0.0F, 0.0F, 0.0F, 1, 1, new int[] {0}))));
    }

    @Test
    void buildsColorOnlyFallbackDescriptors() {
        SectionSceneResourceBuilder.AtlasLayout layout =
                new SectionSceneResourceBuilder.AtlasLayout(2, 1);
        byte[] colors = new byte[8];

        SectionGeometrySnapshot.SceneResources resources =
                SectionSceneResourceBuilder.build(
                        256, -256, 512, layout, colors, LabPbrAtlasBuilder.empty());

        assertEquals(256, resources.originX());
        assertEquals(-256, resources.originY());
        assertEquals(512, resources.originZ());
        assertMaterialDescriptors(
                resources.materials(),
                SectionGeometrySnapshot.TEXTURE_INDEX_INVALID,
                SectionGeometrySnapshot.PBR_FORMAT_NONE);
        assertEquals(1, resources.textures().length);
        assertTexture(
                resources.textures()[0],
                TextureAtlas.LOCATION_BLOCKS,
                2,
                1,
                colors,
                SectionGeometrySnapshot.TEXTURE_ROLE_COLOR_SRGB);
    }

    @Test
    void buildsFixedLabPbrTextureSlotsAndDescriptors() {
        SectionSceneResourceBuilder.AtlasLayout layout =
                new SectionSceneResourceBuilder.AtlasLayout(1, 1);
        byte[] colors = {1, 2, 3, 4};
        byte[] normals = {5, 6, 7, 8};
        byte[] materials = {9, 10, 11, 12};
        byte[] auxiliary = {13, 14, 15, 16};
        LabPbrAtlasBuilder.Atlases pbr = new LabPbrAtlasBuilder.Atlases(
                1, 1, normals, materials, auxiliary, 0, 0, 0, 0);

        SectionGeometrySnapshot.SceneResources resources =
                SectionSceneResourceBuilder.build(0, 0, 0, layout, colors, pbr);

        assertMaterialDescriptors(
                resources.materials(), 1, SectionGeometrySnapshot.PBR_FORMAT_LAB_1_3);
        assertEquals(4, resources.textures().length);
        assertTexture(resources.textures()[0], TextureAtlas.LOCATION_BLOCKS,
                1, 1, colors, SectionGeometrySnapshot.TEXTURE_ROLE_COLOR_SRGB);
        assertTexture(resources.textures()[1], id("blocks_normal"),
                1, 1, normals, SectionGeometrySnapshot.TEXTURE_ROLE_DATA_LINEAR);
        assertTexture(resources.textures()[2], id("blocks_material"),
                1, 1, materials, SectionGeometrySnapshot.TEXTURE_ROLE_DATA_LINEAR);
        assertTexture(resources.textures()[3], id("blocks_labpbr_auxiliary"),
                1, 1, auxiliary, SectionGeometrySnapshot.TEXTURE_ROLE_DATA_LINEAR);
    }

    @Test
    void fallsBackWhenPbrAtlasDimensionsDoNotMatch() {
        SectionSceneResourceBuilder.AtlasLayout layout =
                new SectionSceneResourceBuilder.AtlasLayout(2, 1);
        LabPbrAtlasBuilder.Atlases mismatched = new LabPbrAtlasBuilder.Atlases(
                1, 1, new byte[4], new byte[4], new byte[4], 0, 0, 0, 0);

        SectionGeometrySnapshot.SceneResources resources =
                SectionSceneResourceBuilder.build(0, 0, 0, layout, new byte[8], mismatched);

        assertEquals(1, resources.textures().length);
        assertMaterialDescriptors(
                resources.materials(),
                SectionGeometrySnapshot.TEXTURE_INDEX_INVALID,
                SectionGeometrySnapshot.PBR_FORMAT_NONE);
    }

    private static void assertMaterialDescriptors(
            SectionGeometrySnapshot.MaterialData[] materials,
            int normalTextureIndex,
            int pbrFormat) {
        int[] flags = {
                0,
                SectionGeometrySnapshot.MATERIAL_FLAG_CUTOUT,
                SectionGeometrySnapshot.MATERIAL_FLAG_BLEND,
                SectionGeometrySnapshot.MATERIAL_FLAG_TRANSMISSION,
                SectionGeometrySnapshot.MATERIAL_FLAG_TRANSMISSION
                        | SectionGeometrySnapshot.MATERIAL_FLAG_WATER,
                SectionGeometrySnapshot.MATERIAL_FLAG_CUTOUT
                        | SectionGeometrySnapshot.MATERIAL_FLAG_FOLIAGE
        };
        float[] alphaCutoffs = {0.0F, 0.5F, 0.0F, 0.0F, 0.0F, 0.5F};
        assertEquals(flags.length, materials.length);
        for (int index = 0; index < materials.length; index++) {
            SectionGeometrySnapshot.MaterialData material = materials[index];
            assertEquals(0, material.textureIndex());
            assertEquals(flags[index], material.flags());
            assertEquals(0.0F, material.emissionStrength());
            assertEquals(alphaCutoffs[index], material.alphaCutoff());
            assertEquals(normalTextureIndex, material.normalTextureIndex());
            assertEquals(normalTextureIndex < 0 ? -1 : 2, material.materialTextureIndex());
            assertEquals(pbrFormat, material.pbrFormat());
            assertEquals(normalTextureIndex < 0 ? -1 : 3, material.auxiliaryTextureIndex());
        }
    }

    private static void assertTexture(
            SectionGeometrySnapshot.TextureData texture,
            Identifier id,
            int width,
            int height,
            byte[] pixels,
            int role) {
        assertEquals(id, texture.atlas());
        assertEquals(width, texture.width());
        assertEquals(height, texture.height());
        assertSame(pixels, texture.rgbaPixels());
        assertEquals(role, texture.role());
    }

    private static SectionSceneResourceBuilder.SpriteInput sprite(
            float u0,
            float v0,
            float u1,
            float v1,
            int width,
            int height,
            int[] pixels) {
        return new SectionSceneResourceBuilder.SpriteInput(
                u0,
                v0,
                u1,
                v1,
                width,
                height,
                (x, y) -> pixels[y * width + x]);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("cyclesrenderer", path);
    }
}

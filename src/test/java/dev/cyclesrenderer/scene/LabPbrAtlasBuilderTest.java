package dev.cyclesrenderer.scene;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LabPbrAtlasBuilderTest {
    @Test
    void fillsNeutralMissingCompanionDefaults() {
        byte[] normals = new byte[8];
        byte[] materials = new byte[8];
        byte[] auxiliary = new byte[8];

        LabPbrAtlasBuilder.fillDefaults(
                normals, materials, auxiliary,
                LabPbrAtlasBuilder.DEFAULT_ROUGHNESS,
                LabPbrAtlasBuilder.DEFAULT_DIELECTRIC_F0);

        assertBytes(normals,
                128, 128, 255, 255,
                128, 128, 255, 255);
        assertBytes(materials,
                204, 0, 10, 0,
                204, 0, 10, 0);
        assertBytes(auxiliary,
                255, 128, 10, 0,
                255, 128, 10, 0);
    }

    @Test
    void decodesDirectXNormalAndPreservesAoAndHeight() {
        byte[] normals = new byte[4];
        byte[] auxiliary = new byte[4];

        LabPbrAtlasBuilder.decodeNormalPixel(
                0xAABF8020, normals, auxiliary, 0);

        assertBytes(normals, 191, 128, 221, 170);
        assertBytes(auxiliary, 32, 170, 0, 0);
    }

    @Test
    void decodesDielectricSurfaceChannelsAtTheirUpperRanges() {
        byte[] materials = new byte[4];
        byte[] auxiliary = new byte[4];

        LabPbrAtlasBuilder.decodeMaterialPixel(
                0xFE40E540,
                LabPbrAtlasBuilder.DEFAULT_DIELECTRIC_F0,
                materials,
                auxiliary,
                0);

        assertBytes(materials, 191, 0, 229, 255);
        assertBytes(auxiliary, 0, 0, 229, 64);
    }

    @Test
    void separatesMetalIdsFromDielectricF0AndIgnoresAlpha255Emission() {
        byte[] materials = new byte[8];
        byte[] auxiliary = new byte[8];

        LabPbrAtlasBuilder.decodeMaterialPixel(
                0x0000E540,
                LabPbrAtlasBuilder.DEFAULT_DIELECTRIC_F0,
                materials,
                auxiliary,
                0);
        LabPbrAtlasBuilder.decodeMaterialPixel(
                0xFFFFE641,
                LabPbrAtlasBuilder.DEFAULT_DIELECTRIC_F0,
                materials,
                auxiliary,
                4);

        assertBytes(materials,
                255, 0, 229, 0,
                0, 255, 10, 0);
        assertBytes(auxiliary,
                0, 0, 229, 64,
                0, 0, 230, 65);
    }

    @Test
    void decodesGenericMetalAndMaximumValidEmission() {
        byte[] materials = new byte[4];
        byte[] auxiliary = new byte[4];

        LabPbrAtlasBuilder.decodeMaterialPixel(
                0xFE80FFFF,
                0.08F,
                materials,
                auxiliary,
                0);

        assertBytes(materials, 127, 255, 20, 255);
        assertBytes(auxiliary, 0, 0, 255, 255);
    }

    @Test
    void mapsImageFramesAcrossRowsAndWrapsTheFrameIndex() {
        assertFrame(0, 0, 0);
        assertFrame(1, 2, 0);
        assertFrame(2, 0, 2);
        assertFrame(3, 2, 2);
        assertFrame(4, 0, 0);
        assertFrame(-1, 2, 2);
    }

    @Test
    void acceptsOnlyWholeCompanionFrameGrids() {
        assertTrue(LabPbrAtlasBuilder.hasCompatibleFrames(4, 4, 2, 2));
        assertTrue(LabPbrAtlasBuilder.hasCompatibleFrames(4, 6, 2, 2));
        assertFalse(LabPbrAtlasBuilder.hasCompatibleFrames(3, 4, 2, 2));
        assertFalse(LabPbrAtlasBuilder.hasCompatibleFrames(1, 2, 2, 2));
        assertFalse(LabPbrAtlasBuilder.hasCompatibleFrames(4, 4, 0, 2));
    }

    private static void assertFrame(int frame, int expectedX, int expectedY) {
        assertEquals(
                expectedX,
                LabPbrAtlasBuilder.frameStartX(4, 4, 2, 2, frame));
        assertEquals(
                expectedY,
                LabPbrAtlasBuilder.frameStartY(4, 4, 2, 2, frame));
    }

    private static void assertBytes(byte[] actual, int... expected) {
        assertEquals(expected.length, actual.length);
        for (int index = 0; index < expected.length; index++) {
            assertEquals(expected[index], Byte.toUnsignedInt(actual[index]), "byte " + index);
        }
    }
}

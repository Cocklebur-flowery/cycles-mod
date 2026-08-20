package dev.cyclesrenderer.scene;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class LabPbrAnimationRegionEncoderTest {
    private static final Identifier SPRITE =
            Identifier.fromNamespaceAndPath("cyclesrenderer", "animated");

    @Test
    void matchesQuantizedShaderMixAndUnormRoundingOracle() {
        assertEquals(0x5B555555, LabPbrAnimationRegionEncoder.interpolateArgb(
                0x10204080, 0xF0C08000, 333));
        assertEquals(1, LabPbrAnimationRegionEncoder.interpolateChannel(0, 1, 500));
        assertEquals(255, LabPbrAnimationRegionEncoder.interpolateChannel(0, 255, 999));
        assertEquals(0, LabPbrAnimationRegionEncoder.interpolateChannel(255, 0, 999));
    }

    @Test
    void encodesDiscreteFrameIntoFourFixedRegions() {
        LabPbrAnimationRegionEncoder.SpriteSource source = source(
                frames(0xFF010203, 0x80405060),
                frames(0xFF8080FF, 0xAABF8020),
                frames(0x0000E540, 0xFE40E540));
        LabPbrAnimationFrames.FrameUpdate update = update(1, 0, false, 900, 12);

        LabPbrAnimationRegionEncoder.RegionBatch batch =
                LabPbrAnimationRegionEncoder.encode(source, update);

        assertEquals(SPRITE, batch.sprite());
        assertEquals(4, batch.generation());
        assertEquals(12, batch.revision());
        assertEquals(List.of(0, 1, 2, 3), batch.regions().stream()
                .map(LabPbrAnimationRegionEncoder.Region::textureIndex)
                .toList());
        assertRegion(batch.regions().get(0), 64, 96, 1, 1, 4, 4, 64, 80, 96, 128);
        assertRegion(batch.regions().get(1), 64, 96, 1, 1, 4, 4, 191, 128, 221, 170);
        assertRegion(batch.regions().get(2), 64, 96, 1, 1, 4, 4, 191, 0, 229, 255);
        assertRegion(batch.regions().get(3), 64, 96, 1, 1, 4, 4, 32, 170, 229, 64);
    }

    @Test
    void interpolatesRawInputsBeforeLabPbrDecode() {
        LabPbrAnimationRegionEncoder.SpriteSource source = source(
                frames(0x00000000, 0xFFFFFFFF),
                frames(0x40408000, 0xC0C080FF),
                frames(0x0000E50A, 0xFEFFE71E));
        LabPbrAnimationFrames.FrameUpdate update = update(0, 1, true, 500, 13);

        LabPbrAnimationRegionEncoder.RegionBatch batch =
                LabPbrAnimationRegionEncoder.encode(source, update);

        assertBytes(batch.regions().get(0).pixels(), 128, 128, 128, 128);
        assertBytes(batch.regions().get(1).pixels(), 128, 128, 255, 128);
        assertBytes(batch.regions().get(2).pixels(), 127, 255, 10, 128);
        assertBytes(batch.regions().get(3).pixels(), 128, 128, 230, 20);
    }

    @Test
    void usesNeutralCompanionFallbacksWithoutPartialBatch() {
        LabPbrAnimationRegionEncoder.SpriteSource source = source(
                frames(0xFF112233, 0xFF445566), null, null);

        LabPbrAnimationRegionEncoder.RegionBatch batch =
                LabPbrAnimationRegionEncoder.encode(source, update(1, 1, false, 0, 14));

        assertBytes(batch.regions().get(0).pixels(), 68, 85, 102, 255);
        assertBytes(batch.regions().get(1).pixels(), 128, 128, 255, 255);
        assertBytes(batch.regions().get(2).pixels(), 204, 0, 10, 0);
        assertBytes(batch.regions().get(3).pixels(), 255, 128, 10, 0);
    }

    @Test
    void ownsFrameInputAndPreservesRgbaRowOrder() {
        int[] input = {
                0xFF010203, 0xFF040506,
                0x80102030, 0xFF405060
        };
        LabPbrAnimationRegionEncoder.FramePixels frames =
                new LabPbrAnimationRegionEncoder.FramePixels(4, 1, 2, 1, input);
        input[2] = 0;
        LabPbrAnimationRegionEncoder.SpriteSource source =
                new LabPbrAnimationRegionEncoder.SpriteSource(
                        SPRITE, 4, 256, 256, 64, 96, 2, 1,
                        frames, null, null,
                        LabPbrAtlasBuilder.DEFAULT_ROUGHNESS,
                        LabPbrAtlasBuilder.DEFAULT_DIELECTRIC_F0);

        LabPbrAnimationRegionEncoder.Region color =
                LabPbrAnimationRegionEncoder.encode(
                        source, update(1, 1, false, 0, 15)).regions().getFirst();

        assertEquals(8, color.rowStride());
        assertBytes(color.pixels(),
                16, 32, 48, 128,
                64, 80, 96, 255);
    }

    @Test
    void rejectsMismatchedGenerationSpriteAndState() {
        LabPbrAnimationRegionEncoder.SpriteSource source = source(
                frames(0xFF000000, 0xFFFFFFFF), null, null);

        assertThrows(IllegalArgumentException.class, () ->
                LabPbrAnimationRegionEncoder.encode(source,
                        new LabPbrAnimationFrames.FrameUpdate(
                                SPRITE, 3, 0, 1, true, 500, 1)));
        assertThrows(IllegalArgumentException.class, () ->
                LabPbrAnimationRegionEncoder.encode(source,
                        new LabPbrAnimationFrames.FrameUpdate(
                                Identifier.withDefaultNamespace("other"),
                                4, 0, 1, true, 500, 1)));
        assertThrows(IllegalArgumentException.class, () ->
                LabPbrAnimationRegionEncoder.encode(source,
                        new LabPbrAnimationFrames.FrameUpdate(
                                SPRITE, 4, 0, 1, true, 1000, 1)));
    }

    @Test
    void rejectsInvalidFrameGridAndAtlasBounds() {
        assertThrows(IllegalArgumentException.class, () ->
                new LabPbrAnimationRegionEncoder.FramePixels(
                        3, 1, 2, 1, new int[3]));
        assertThrows(IllegalArgumentException.class, () ->
                new LabPbrAnimationRegionEncoder.FramePixels(
                        2, 1, 1, 1, new int[1]));
        assertThrows(IllegalArgumentException.class, () ->
                new LabPbrAnimationRegionEncoder.SpriteSource(
                        SPRITE, 4, 64, 64, 64, 0, 1, 1,
                        frames(0xFF000000, 0xFFFFFFFF), null, null,
                        LabPbrAtlasBuilder.DEFAULT_ROUGHNESS,
                        LabPbrAtlasBuilder.DEFAULT_DIELECTRIC_F0));
    }

    @Test
    void rejectsInvalidRegionShapeAndNonAtomicBatch() {
        assertThrows(IllegalArgumentException.class, () ->
                new LabPbrAnimationRegionEncoder.Region(
                        0, 0, 0, 1, 1, 3, new byte[4]));
        assertThrows(IllegalArgumentException.class, () ->
                new LabPbrAnimationRegionEncoder.Region(
                        0, 0, 0, 1, 1, 4, new byte[3]));
        assertThrows(IllegalArgumentException.class, () ->
                new LabPbrAnimationRegionEncoder.RegionBatch(
                        SPRITE, 4, 1,
                        List.of(region(0, 0), region(2, 0), region(1, 0), region(3, 0))));
        assertThrows(IllegalArgumentException.class, () ->
                new LabPbrAnimationRegionEncoder.RegionBatch(
                        SPRITE, 4, 1,
                        List.of(region(0, 0), region(1, 1), region(2, 0), region(3, 0))));
    }

    private static LabPbrAnimationRegionEncoder.SpriteSource source(
            LabPbrAnimationRegionEncoder.FramePixels base,
            LabPbrAnimationRegionEncoder.FramePixels normal,
            LabPbrAnimationRegionEncoder.FramePixels material) {
        return new LabPbrAnimationRegionEncoder.SpriteSource(
                SPRITE,
                4,
                256,
                256,
                64,
                96,
                1,
                1,
                base,
                normal,
                material,
                LabPbrAtlasBuilder.DEFAULT_ROUGHNESS,
                LabPbrAtlasBuilder.DEFAULT_DIELECTRIC_F0);
    }

    private static LabPbrAnimationRegionEncoder.FramePixels frames(int first, int second) {
        return new LabPbrAnimationRegionEncoder.FramePixels(
                2, 1, 1, 1, new int[] {first, second});
    }

    private static LabPbrAnimationFrames.FrameUpdate update(
            int current,
            int next,
            boolean interpolated,
            int progress,
            long sequence) {
        return new LabPbrAnimationFrames.FrameUpdate(
                SPRITE, 4, current, next, interpolated, progress, sequence);
    }

    private static LabPbrAnimationRegionEncoder.Region region(int textureIndex, int x) {
        return new LabPbrAnimationRegionEncoder.Region(
                textureIndex, x, 0, 1, 1, 4, new byte[4]);
    }

    private static void assertRegion(
            LabPbrAnimationRegionEncoder.Region region,
            int x,
            int y,
            int width,
            int height,
            int rowStride,
            int byteCount,
            int... expectedBytes) {
        assertEquals(x, region.x());
        assertEquals(y, region.y());
        assertEquals(width, region.width());
        assertEquals(height, region.height());
        assertEquals(rowStride, region.rowStride());
        assertEquals(byteCount, region.pixels().length);
        assertBytes(region.pixels(), expectedBytes);
    }

    private static void assertBytes(byte[] actual, int... expected) {
        assertEquals(expected.length, actual.length);
        for (int index = 0; index < expected.length; index++) {
            assertEquals(expected[index], Byte.toUnsignedInt(actual[index]), "byte " + index);
        }
    }
}

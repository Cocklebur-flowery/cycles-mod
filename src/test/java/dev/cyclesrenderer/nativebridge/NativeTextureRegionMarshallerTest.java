package dev.cyclesrenderer.nativebridge;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeTextureRegionMarshallerTest {
    @Test
    void generatedLayoutHasPinnedOffsetsAndSize() {
        assertEquals(88L, TextureRegionUpdateAbi.BYTE_SIZE);
        assertEquals(0L, TextureRegionUpdateAbi.STRUCT_SIZE_OFFSET);
        assertEquals(8L, TextureRegionUpdateAbi.GENERATION_OFFSET);
        assertEquals(16L, TextureRegionUpdateAbi.REVISION_OFFSET);
        assertEquals(24L, TextureRegionUpdateAbi.SPRITE_INDEX_OFFSET);
        assertEquals(48L, TextureRegionUpdateAbi.PIXEL_BYTE_COUNT_OFFSET);
        assertEquals(56L, TextureRegionUpdateAbi.COLOR_PIXEL_OFFSET_OFFSET);
        assertEquals(80L, TextureRegionUpdateAbi.AUXILIARY_PIXEL_OFFSET_OFFSET);
    }

    @Test
    void writesCanonicalDescriptorAndFourSlotPixels() {
        TextureRegionUpdate update = update();
        try (Arena arena = Arena.ofConfined()) {
            NativeTextureRegionMarshaller.Arguments arguments =
                    NativeTextureRegionMarshaller.write(arena, 1, update);
            MemorySegment descriptor = arguments.descriptor();
            assertEquals(88, descriptor.get(JAVA_INT,
                    TextureRegionUpdateAbi.STRUCT_SIZE_OFFSET));
            assertEquals(1, descriptor.get(JAVA_INT,
                    TextureRegionUpdateAbi.STRUCT_VERSION_OFFSET));
            assertEquals(7L, descriptor.get(JAVA_LONG,
                    TextureRegionUpdateAbi.GENERATION_OFFSET));
            assertEquals(11L, descriptor.get(JAVA_LONG,
                    TextureRegionUpdateAbi.REVISION_OFFSET));
            assertEquals(13, descriptor.get(JAVA_INT,
                    TextureRegionUpdateAbi.SPRITE_INDEX_OFFSET));
            assertEquals(2, descriptor.get(JAVA_INT, TextureRegionUpdateAbi.X_OFFSET));
            assertEquals(3, descriptor.get(JAVA_INT, TextureRegionUpdateAbi.Y_OFFSET));
            assertEquals(2, descriptor.get(JAVA_INT, TextureRegionUpdateAbi.WIDTH_OFFSET));
            assertEquals(1, descriptor.get(JAVA_INT, TextureRegionUpdateAbi.HEIGHT_OFFSET));
            assertEquals(8, descriptor.get(JAVA_INT,
                    TextureRegionUpdateAbi.ROW_STRIDE_OFFSET));
            assertEquals(8L, descriptor.get(JAVA_LONG,
                    TextureRegionUpdateAbi.PIXEL_BYTE_COUNT_OFFSET));
            assertEquals(0L, descriptor.get(JAVA_LONG,
                    TextureRegionUpdateAbi.COLOR_PIXEL_OFFSET_OFFSET));
            assertEquals(8L, descriptor.get(JAVA_LONG,
                    TextureRegionUpdateAbi.NORMAL_PIXEL_OFFSET_OFFSET));
            assertEquals(16L, descriptor.get(JAVA_LONG,
                    TextureRegionUpdateAbi.MATERIAL_PIXEL_OFFSET_OFFSET));
            assertEquals(24L, descriptor.get(JAVA_LONG,
                    TextureRegionUpdateAbi.AUXILIARY_PIXEL_OFFSET_OFFSET));
            assertEquals(32L, arguments.pixelCapacity());
            assertArrayEquals(sequence(1), arguments.pixels().asSlice(0L, 8L)
                    .toArray(JAVA_BYTE));
            assertArrayEquals(sequence(25), arguments.pixels().asSlice(24L, 8L)
                    .toArray(JAVA_BYTE));
        }
    }

    @Test
    void updateOwnsPixelsAndRejectsNonCanonicalStorage() {
        byte[] color = sequence(1);
        TextureRegionUpdate update = new TextureRegionUpdate(
                7L, 11L, 13, 2, 3, 2, 1, 8,
                color, sequence(9), sequence(17), sequence(25));
        color[0] = 99;
        byte[] returned = update.colorPixels();
        assertEquals(1, returned[0]);
        returned[0] = 88;
        assertEquals(1, update.colorPixels()[0]);

        assertThrows(IllegalArgumentException.class, () -> new TextureRegionUpdate(
                7L, 11L, 13, 2, 3, 2, 1, 9,
                sequence(1), sequence(9), sequence(17), sequence(25)));
        assertThrows(IllegalArgumentException.class, () -> new TextureRegionUpdate(
                0L, 11L, 13, 2, 3, 2, 1, 8,
                sequence(1), sequence(9), sequence(17), sequence(25)));
    }

    private static TextureRegionUpdate update() {
        return new TextureRegionUpdate(
                7L, 11L, 13, 2, 3, 2, 1, 8,
                sequence(1), sequence(9), sequence(17), sequence(25));
    }

    private static byte[] sequence(int first) {
        byte[] result = new byte[8];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) (first + index);
        }
        return result;
    }
}

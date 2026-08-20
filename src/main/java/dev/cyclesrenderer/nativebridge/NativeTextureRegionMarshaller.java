package dev.cyclesrenderer.nativebridge;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** Encodes the generated ABI descriptor and canonical four-slot pixel buffer. */
final class NativeTextureRegionMarshaller {
    private NativeTextureRegionMarshaller() {
    }

    static Arguments write(Arena arena, int structVersion, TextureRegionUpdate update) {
        byte[][] regions = {
                update.colorPixels(),
                update.normalPixels(),
                update.materialPixels(),
                update.auxiliaryPixels()
        };
        int byteCount = regions[0].length;
        long capacity = Math.multiplyExact((long) byteCount, regions.length);
        MemorySegment pixels = arena.allocate(capacity, 4L);
        for (int index = 0; index < regions.length; index++) {
            pixels.asSlice((long) index * byteCount, byteCount)
                    .asByteBuffer().put(regions[index]);
        }

        MemorySegment descriptor = arena.allocate(TextureRegionUpdateAbi.LAYOUT);
        descriptor.set(JAVA_INT, TextureRegionUpdateAbi.STRUCT_SIZE_OFFSET,
                Math.toIntExact(TextureRegionUpdateAbi.BYTE_SIZE));
        descriptor.set(JAVA_INT, TextureRegionUpdateAbi.STRUCT_VERSION_OFFSET, structVersion);
        descriptor.set(JAVA_LONG, TextureRegionUpdateAbi.GENERATION_OFFSET, update.generation());
        descriptor.set(JAVA_LONG, TextureRegionUpdateAbi.REVISION_OFFSET, update.revision());
        descriptor.set(JAVA_INT, TextureRegionUpdateAbi.SPRITE_INDEX_OFFSET,
                update.spriteIndex());
        descriptor.set(JAVA_INT, TextureRegionUpdateAbi.X_OFFSET, update.x());
        descriptor.set(JAVA_INT, TextureRegionUpdateAbi.Y_OFFSET, update.y());
        descriptor.set(JAVA_INT, TextureRegionUpdateAbi.WIDTH_OFFSET, update.width());
        descriptor.set(JAVA_INT, TextureRegionUpdateAbi.HEIGHT_OFFSET, update.height());
        descriptor.set(JAVA_INT, TextureRegionUpdateAbi.ROW_STRIDE_OFFSET, update.rowStride());
        descriptor.set(JAVA_LONG, TextureRegionUpdateAbi.PIXEL_BYTE_COUNT_OFFSET, byteCount);
        descriptor.set(JAVA_LONG, TextureRegionUpdateAbi.COLOR_PIXEL_OFFSET_OFFSET, 0L);
        descriptor.set(JAVA_LONG, TextureRegionUpdateAbi.NORMAL_PIXEL_OFFSET_OFFSET, byteCount);
        descriptor.set(JAVA_LONG, TextureRegionUpdateAbi.MATERIAL_PIXEL_OFFSET_OFFSET,
                2L * byteCount);
        descriptor.set(JAVA_LONG, TextureRegionUpdateAbi.AUXILIARY_PIXEL_OFFSET_OFFSET,
                3L * byteCount);
        return new Arguments(descriptor, pixels, capacity);
    }

    record Arguments(MemorySegment descriptor, MemorySegment pixels, long pixelCapacity) {
    }
}

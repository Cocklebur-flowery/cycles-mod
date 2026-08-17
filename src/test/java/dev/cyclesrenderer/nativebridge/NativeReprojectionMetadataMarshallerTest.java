package dev.cyclesrenderer.nativebridge;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NativeReprojectionMetadataMarshallerTest {
    @Test
    void acceptsCompletePerspectiveAxialDepthMetadata() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment source = validMetadata(arena);

            var result = NativeReprojectionMetadataMarshaller.decode(source);

            assertTrue(result.accepted());
            assertEquals(23L, result.metadata().generation());
            assertEquals(2, result.metadata().slotIndex());
            assertEquals(1920, result.metadata().width());
            assertEquals(1080, result.metadata().height());
            assertEquals(1.7777778F, result.metadata().aspect());
            assertEquals("", result.rejectionReason());
        }
    }

    @Test
    void rejectsHeaderMismatches() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment tooSmall = arena.allocate(ReprojectionMetadataAbi.BYTE_SIZE - 1L);
            assertRejected(tooSmall, "buffer-too-small");

            MemorySegment source = validMetadata(arena);
            source.set(JAVA_INT, ReprojectionMetadataAbi.STRUCT_SIZE_OFFSET, 80);
            assertRejected(source, "struct-size");
            source.set(JAVA_INT, ReprojectionMetadataAbi.STRUCT_SIZE_OFFSET,
                    Math.toIntExact(ReprojectionMetadataAbi.BYTE_SIZE));
            source.set(JAVA_INT, ReprojectionMetadataAbi.STRUCT_VERSION_OFFSET, 2);
            assertRejected(source, "struct-version");
        }
    }

    @Test
    void rejectsUnsupportedFlagsProjectionAndDepthSemantic() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment source = validMetadata(arena);
            source.set(JAVA_INT, ReprojectionMetadataAbi.FLAGS_OFFSET, 3);
            assertRejected(source, "flags");
            source.set(JAVA_INT, ReprojectionMetadataAbi.FLAGS_OFFSET, 1);
            source.set(JAVA_INT, ReprojectionMetadataAbi.PROJECTION_OFFSET, 0);
            assertRejected(source, "projection");
            source.set(JAVA_INT, ReprojectionMetadataAbi.PROJECTION_OFFSET, 1);
            source.set(JAVA_INT, ReprojectionMetadataAbi.DEPTH_SEMANTIC_OFFSET, 2);
            assertRejected(source, "depth-semantic");
        }
    }

    @Test
    void rejectsMismatchedDimensionsAndInvalidCameraData() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment source = validMetadata(arena);
            source.set(JAVA_INT, ReprojectionMetadataAbi.DEPTH_WIDTH_OFFSET, 960);
            assertRejected(source, "dimensions");
            source.set(JAVA_INT, ReprojectionMetadataAbi.DEPTH_WIDTH_OFFSET, 1920);
            source.set(JAVA_DOUBLE, ReprojectionMetadataAbi.POSITION_X_OFFSET, Double.NaN);
            assertRejected(source, "camera");
            source.set(JAVA_DOUBLE, ReprojectionMetadataAbi.POSITION_X_OFFSET, 12.5D);
            source.set(JAVA_FLOAT, ReprojectionMetadataAbi.ROTATION_W_OFFSET, 0.0F);
            assertRejected(source, "camera");
            source.set(JAVA_FLOAT, ReprojectionMetadataAbi.ROTATION_W_OFFSET, 1.0F);
            source.set(JAVA_FLOAT, ReprojectionMetadataAbi.FAR_CLIP_OFFSET, 0.05F);
            assertRejected(source, "camera");
        }
    }

    @Test
    void rejectsGenerationAndTimestampThatCannotMatchAProducedFrame() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment source = validMetadata(arena);
            source.set(JAVA_LONG, ReprojectionMetadataAbi.GENERATION_OFFSET, 0L);
            assertRejected(source, "revision");
            source.set(JAVA_LONG, ReprojectionMetadataAbi.GENERATION_OFFSET, 23L);
            source.set(JAVA_LONG, ReprojectionMetadataAbi.PRODUCTION_TIME_NANOS_OFFSET, 0L);
            assertRejected(source, "revision");
        }
    }

    private static MemorySegment validMetadata(Arena arena) {
        MemorySegment source = arena.allocate(ReprojectionMetadataAbi.LAYOUT);
        source.set(JAVA_INT, ReprojectionMetadataAbi.STRUCT_SIZE_OFFSET,
                Math.toIntExact(ReprojectionMetadataAbi.BYTE_SIZE));
        source.set(JAVA_INT, ReprojectionMetadataAbi.STRUCT_VERSION_OFFSET, 1);
        source.set(JAVA_INT, ReprojectionMetadataAbi.FLAGS_OFFSET, 1);
        source.set(JAVA_INT, ReprojectionMetadataAbi.PROJECTION_OFFSET, 1);
        source.set(JAVA_INT, ReprojectionMetadataAbi.DEPTH_SEMANTIC_OFFSET, 1);
        source.set(JAVA_INT, ReprojectionMetadataAbi.SLOT_INDEX_OFFSET, 2);
        source.set(JAVA_INT, ReprojectionMetadataAbi.COLOR_WIDTH_OFFSET, 1920);
        source.set(JAVA_INT, ReprojectionMetadataAbi.COLOR_HEIGHT_OFFSET, 1080);
        source.set(JAVA_INT, ReprojectionMetadataAbi.DEPTH_WIDTH_OFFSET, 1920);
        source.set(JAVA_INT, ReprojectionMetadataAbi.DEPTH_HEIGHT_OFFSET, 1080);
        source.set(JAVA_LONG, ReprojectionMetadataAbi.GENERATION_OFFSET, 23L);
        source.set(JAVA_LONG, ReprojectionMetadataAbi.FRAME_REVISION_OFFSET, 47L);
        source.set(JAVA_LONG, ReprojectionMetadataAbi.CAMERA_REVISION_OFFSET, 11L);
        source.set(JAVA_LONG, ReprojectionMetadataAbi.SCENE_REVISION_OFFSET, 9L);
        source.set(JAVA_LONG, ReprojectionMetadataAbi.PRODUCTION_TIME_NANOS_OFFSET, 123456L);
        source.set(JAVA_DOUBLE, ReprojectionMetadataAbi.POSITION_X_OFFSET, 12.5D);
        source.set(JAVA_DOUBLE, ReprojectionMetadataAbi.POSITION_Y_OFFSET, 70.0D);
        source.set(JAVA_DOUBLE, ReprojectionMetadataAbi.POSITION_Z_OFFSET, -3.5D);
        source.set(JAVA_FLOAT, ReprojectionMetadataAbi.ROTATION_W_OFFSET, 1.0F);
        source.set(JAVA_FLOAT, ReprojectionMetadataAbi.VERTICAL_FOV_RADIANS_OFFSET, 1.2F);
        source.set(JAVA_FLOAT, ReprojectionMetadataAbi.ASPECT_OFFSET, 1.7777778F);
        source.set(JAVA_FLOAT, ReprojectionMetadataAbi.SHIFT_X_OFFSET, 0.1F);
        source.set(JAVA_FLOAT, ReprojectionMetadataAbi.SHIFT_Y_OFFSET, -0.1F);
        source.set(JAVA_FLOAT, ReprojectionMetadataAbi.NEAR_CLIP_OFFSET, 0.05F);
        source.set(JAVA_FLOAT, ReprojectionMetadataAbi.FAR_CLIP_OFFSET, 1000.0F);
        return source;
    }

    private static void assertRejected(MemorySegment source, String reason) {
        var result = NativeReprojectionMetadataMarshaller.decode(source);
        assertFalse(result.accepted());
        assertEquals(reason, result.rejectionReason());
    }
}

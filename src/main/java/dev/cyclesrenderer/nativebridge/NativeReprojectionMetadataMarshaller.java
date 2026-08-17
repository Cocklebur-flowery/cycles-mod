package dev.cyclesrenderer.nativebridge;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** Strict decoder for a color/depth/source-camera metadata snapshot. */
final class NativeReprojectionMetadataMarshaller {
    static final int STRUCT_VERSION = 1;
    static final int FLAG_VALID = 1;
    static final int PROJECTION_PERSPECTIVE = 1;
    static final int DEPTH_AXIAL_CAMERA_Z = 1;

    private NativeReprojectionMetadataMarshaller() {
    }

    static void prepare(MemorySegment target) {
        target.fill((byte) 0);
        target.set(JAVA_INT, ReprojectionMetadataAbi.STRUCT_SIZE_OFFSET,
                Math.toIntExact(ReprojectionMetadataAbi.BYTE_SIZE));
        target.set(JAVA_INT, ReprojectionMetadataAbi.STRUCT_VERSION_OFFSET, STRUCT_VERSION);
    }

    static DecodeResult decode(MemorySegment source) {
        if (source == null || source.byteSize() < ReprojectionMetadataAbi.BYTE_SIZE) {
            return DecodeResult.rejected("buffer-too-small");
        }
        long declaredSize = Integer.toUnsignedLong(source.get(
                JAVA_INT, ReprojectionMetadataAbi.STRUCT_SIZE_OFFSET));
        if (declaredSize < ReprojectionMetadataAbi.BYTE_SIZE
                || declaredSize > source.byteSize()) {
            return DecodeResult.rejected("struct-size");
        }
        if (source.get(JAVA_INT, ReprojectionMetadataAbi.STRUCT_VERSION_OFFSET)
                != STRUCT_VERSION) {
            return DecodeResult.rejected("struct-version");
        }
        int flags = source.get(JAVA_INT, ReprojectionMetadataAbi.FLAGS_OFFSET);
        if (flags != FLAG_VALID) {
            return DecodeResult.rejected("flags");
        }
        int projection = source.get(JAVA_INT, ReprojectionMetadataAbi.PROJECTION_OFFSET);
        if (projection != PROJECTION_PERSPECTIVE) {
            return DecodeResult.rejected("projection");
        }
        int depthSemantic = source.get(
                JAVA_INT, ReprojectionMetadataAbi.DEPTH_SEMANTIC_OFFSET);
        if (depthSemantic != DEPTH_AXIAL_CAMERA_Z) {
            return DecodeResult.rejected("depth-semantic");
        }

        int slotIndex = source.get(JAVA_INT, ReprojectionMetadataAbi.SLOT_INDEX_OFFSET);
        int colorWidth = source.get(JAVA_INT, ReprojectionMetadataAbi.COLOR_WIDTH_OFFSET);
        int colorHeight = source.get(JAVA_INT, ReprojectionMetadataAbi.COLOR_HEIGHT_OFFSET);
        int depthWidth = source.get(JAVA_INT, ReprojectionMetadataAbi.DEPTH_WIDTH_OFFSET);
        int depthHeight = source.get(JAVA_INT, ReprojectionMetadataAbi.DEPTH_HEIGHT_OFFSET);
        if (slotIndex < 0 || colorWidth <= 0 || colorHeight <= 0
                || colorWidth != depthWidth || colorHeight != depthHeight) {
            return DecodeResult.rejected("dimensions");
        }

        long generation = source.get(JAVA_LONG, ReprojectionMetadataAbi.GENERATION_OFFSET);
        long frameRevision = source.get(JAVA_LONG, ReprojectionMetadataAbi.FRAME_REVISION_OFFSET);
        long cameraRevision = source.get(JAVA_LONG, ReprojectionMetadataAbi.CAMERA_REVISION_OFFSET);
        long sceneRevision = source.get(JAVA_LONG, ReprojectionMetadataAbi.SCENE_REVISION_OFFSET);
        long productionTimeNanos = source.get(
                JAVA_LONG, ReprojectionMetadataAbi.PRODUCTION_TIME_NANOS_OFFSET);
        if (generation <= 0L || frameRevision < 0L || cameraRevision < 0L
                || sceneRevision < 0L || productionTimeNanos <= 0L) {
            return DecodeResult.rejected("revision");
        }

        double positionX = source.get(JAVA_DOUBLE, ReprojectionMetadataAbi.POSITION_X_OFFSET);
        double positionY = source.get(JAVA_DOUBLE, ReprojectionMetadataAbi.POSITION_Y_OFFSET);
        double positionZ = source.get(JAVA_DOUBLE, ReprojectionMetadataAbi.POSITION_Z_OFFSET);
        float rotationX = source.get(JAVA_FLOAT, ReprojectionMetadataAbi.ROTATION_X_OFFSET);
        float rotationY = source.get(JAVA_FLOAT, ReprojectionMetadataAbi.ROTATION_Y_OFFSET);
        float rotationZ = source.get(JAVA_FLOAT, ReprojectionMetadataAbi.ROTATION_Z_OFFSET);
        float rotationW = source.get(JAVA_FLOAT, ReprojectionMetadataAbi.ROTATION_W_OFFSET);
        float verticalFov = source.get(
                JAVA_FLOAT, ReprojectionMetadataAbi.VERTICAL_FOV_RADIANS_OFFSET);
        float aspect = source.get(JAVA_FLOAT, ReprojectionMetadataAbi.ASPECT_OFFSET);
        float shiftX = source.get(JAVA_FLOAT, ReprojectionMetadataAbi.SHIFT_X_OFFSET);
        float shiftY = source.get(JAVA_FLOAT, ReprojectionMetadataAbi.SHIFT_Y_OFFSET);
        float nearClip = source.get(JAVA_FLOAT, ReprojectionMetadataAbi.NEAR_CLIP_OFFSET);
        float farClip = source.get(JAVA_FLOAT, ReprojectionMetadataAbi.FAR_CLIP_OFFSET);
        double quaternionNorm = rotationX * rotationX + rotationY * rotationY
                + rotationZ * rotationZ + rotationW * rotationW;
        if (!finite(positionX, positionY, positionZ, rotationX, rotationY, rotationZ,
                rotationW, verticalFov, aspect, shiftX, shiftY, nearClip, farClip)
                || quaternionNorm < 0.98D || quaternionNorm > 1.02D
                || verticalFov <= 0.0F || verticalFov >= Math.PI
                || aspect <= 0.0F || nearClip <= 0.0F || farClip <= nearClip) {
            return DecodeResult.rejected("camera");
        }

        return DecodeResult.accepted(new NativeBridge.ReprojectionMetadata(
                generation, frameRevision, cameraRevision, sceneRevision,
                productionTimeNanos, slotIndex, colorWidth, colorHeight,
                positionX, positionY, positionZ,
                rotationX, rotationY, rotationZ, rotationW,
                verticalFov, aspect, shiftX, shiftY, nearClip, farClip));
    }

    private static boolean finite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    record DecodeResult(NativeBridge.ReprojectionMetadata metadata, String rejectionReason) {
        static DecodeResult accepted(NativeBridge.ReprojectionMetadata metadata) {
            return new DecodeResult(metadata, "");
        }

        static DecodeResult rejected(String reason) {
            return new DecodeResult(null, reason);
        }

        boolean accepted() {
            return metadata != null;
        }
    }
}

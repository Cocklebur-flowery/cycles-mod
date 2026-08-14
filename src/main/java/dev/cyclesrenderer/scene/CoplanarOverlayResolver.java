package dev.cyclesrenderer.scene;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Separates raster-style cutout overlays from their exactly coplanar opaque base faces. */
final class CoplanarOverlayResolver {
    private static final float OVERLAY_OFFSET = 1.0F / 4096.0F;
    private static final int QUAD_VERTEX_COUNT = 4;
    private static final int TRIANGLES_PER_QUAD = 2;

    private CoplanarOverlayResolver() {
    }

    static void separate(float[] vertices, int[] triangles, int quadCount) {
        Set<FaceKey> opaqueFaces = new HashSet<>();
        for (int quad = 0; quad < quadCount; quad++) {
            if (material(triangles, quad) == SectionGeometrySnapshot.MATERIAL_SOLID) {
                opaqueFaces.add(faceKey(vertices, quad));
            }
        }
        for (int quad = 0; quad < quadCount; quad++) {
            if (material(triangles, quad) == SectionGeometrySnapshot.MATERIAL_CUTOUT
                    && opaqueFaces.contains(faceKey(vertices, quad))) {
                offsetAlongNormal(vertices, quad);
            }
        }
    }

    private static int material(int[] triangles, int quad) {
        int triangle = quad * TRIANGLES_PER_QUAD;
        return triangles[triangle * SectionGeometrySnapshot.TRIANGLE_INT_STRIDE + 3];
    }

    private static FaceKey faceKey(float[] vertices, int quad) {
        int[] corners = new int[QUAD_VERTEX_COUNT * 3];
        for (int corner = 0; corner < QUAD_VERTEX_COUNT; corner++) {
            int source = (quad * QUAD_VERTEX_COUNT + corner)
                    * SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE;
            int target = corner * 3;
            corners[target] = Float.floatToRawIntBits(vertices[source]);
            corners[target + 1] = Float.floatToRawIntBits(vertices[source + 1]);
            corners[target + 2] = Float.floatToRawIntBits(vertices[source + 2]);
        }
        sortCorners(corners);
        return new FaceKey(corners);
    }

    private static void sortCorners(int[] corners) {
        for (int first = 0; first < QUAD_VERTEX_COUNT - 1; first++) {
            int smallest = first;
            for (int candidate = first + 1; candidate < QUAD_VERTEX_COUNT; candidate++) {
                if (compareCorner(corners, candidate, smallest) < 0) {
                    smallest = candidate;
                }
            }
            if (smallest != first) {
                for (int component = 0; component < 3; component++) {
                    int left = first * 3 + component;
                    int right = smallest * 3 + component;
                    int value = corners[left];
                    corners[left] = corners[right];
                    corners[right] = value;
                }
            }
        }
    }

    private static int compareCorner(int[] corners, int left, int right) {
        for (int component = 0; component < 3; component++) {
            int comparison = Integer.compareUnsigned(
                    corners[left * 3 + component], corners[right * 3 + component]);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private static void offsetAlongNormal(float[] vertices, int quad) {
        for (int corner = 0; corner < QUAD_VERTEX_COUNT; corner++) {
            int offset = (quad * QUAD_VERTEX_COUNT + corner)
                    * SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE;
            vertices[offset] += vertices[offset + 3] * OVERLAY_OFFSET;
            vertices[offset + 1] += vertices[offset + 4] * OVERLAY_OFFSET;
            vertices[offset + 2] += vertices[offset + 5] * OVERLAY_OFFSET;
        }
    }

    private record FaceKey(int[] corners) {
        @Override
        public boolean equals(Object other) {
            return other instanceof FaceKey key && Arrays.equals(corners, key.corners);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(corners);
        }
    }
}

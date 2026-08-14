package dev.cyclesrenderer.scene;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CoplanarOverlayResolverTest {
    private static final float EPSILON = 1.0e-7F;

    @Test
    void offsetsOnlyCutoutFaceThatSharesAnOpaqueBase() {
        float[] vertices = new float[8 * SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE];
        writeQuad(vertices, 0, 0.0F);
        writeQuad(vertices, 1, 0.0F);
        float[] opaqueBefore = vertices.clone();
        int[] triangles = triangles(
                SectionGeometrySnapshot.MATERIAL_SOLID,
                SectionGeometrySnapshot.MATERIAL_CUTOUT);

        CoplanarOverlayResolver.separate(vertices, triangles, 2);

        for (int index = 0;
                index < 4 * SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE;
                index++) {
            assertEquals(opaqueBefore[index], vertices[index], EPSILON);
        }
        for (int corner = 0; corner < 4; corner++) {
            int offset = (4 + corner) * SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE;
            assertEquals(1.0F / 4096.0F, vertices[offset + 2], EPSILON);
        }
    }

    @Test
    void leavesStandaloneCutoutFaceUnchanged() {
        float[] vertices = new float[4 * SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE];
        writeQuad(vertices, 0, 0.0F);
        float[] before = vertices.clone();

        CoplanarOverlayResolver.separate(
                vertices, triangles(SectionGeometrySnapshot.MATERIAL_CUTOUT), 1);

        assertArrayEquals(before, vertices);
    }

    private static void writeQuad(float[] vertices, int quad, float z) {
        float[][] positions = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (int corner = 0; corner < 4; corner++) {
            int offset = (quad * 4 + corner) * SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE;
            vertices[offset] = positions[corner][0];
            vertices[offset + 1] = positions[corner][1];
            vertices[offset + 2] = z;
            vertices[offset + 5] = 1.0F;
        }
    }

    private static int[] triangles(int... materials) {
        int[] triangles = new int[
                materials.length * 2 * SectionGeometrySnapshot.TRIANGLE_INT_STRIDE];
        for (int quad = 0; quad < materials.length; quad++) {
            for (int triangle = 0; triangle < 2; triangle++) {
                int offset = (quad * 2 + triangle)
                        * SectionGeometrySnapshot.TRIANGLE_INT_STRIDE;
                triangles[offset + 3] = materials[quad];
            }
        }
        return triangles;
    }
}

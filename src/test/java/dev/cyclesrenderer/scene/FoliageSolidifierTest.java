package dev.cyclesrenderer.scene;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class FoliageSolidifierTest {
    @Test
    void followsOpaquePixelsInsteadOfClosingTheCardBorder() {
        boolean[][] mask = {
                {false, false, false, false},
                {false, true, true, false},
                {false, true, true, false},
                {false, false, false, false}
        };

        FoliageSolidifier.Silhouette silhouette = FoliageSolidifier.fromMask(mask);

        assertEquals(4, silhouette.segments().size());
        assertFalse(silhouette.segments().stream().anyMatch(segment ->
                segment.startT() == 0.0F && segment.endT() == 0.0F));
        assertFalse(silhouette.segments().stream().anyMatch(segment ->
                segment.startS() == 0.0F && segment.endS() == 0.0F));
    }

    @Test
    void appendsBackFaceAndVerticalWallsWithoutHorizontalCaps() {
        float[] vertices = {
                0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F,
                1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 0.0F,
                1.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F,
                0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 1.0F
        };
        int[] colors = {0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF};
        int[] triangles = {
                0, 1, 2, SectionGeometrySnapshot.MATERIAL_FOLIAGE,
                0, 2, 3, SectionGeometrySnapshot.MATERIAL_FOLIAGE
        };
        boolean[][] mask = {
                {false, false, false, false},
                {false, true, true, false},
                {false, true, true, false},
                {false, false, false, false}
        };
        FoliageSolidifier.Silhouette silhouette = FoliageSolidifier.fromMask(mask);

        FoliageSolidifier.Result result = FoliageSolidifier.apply(
                vertices,
                colors,
                triangles,
                List.of(new FoliageSolidifier.Quad(
                        0, SectionGeometrySnapshot.MATERIAL_FOLIAGE, silhouette)));

        assertEquals(3, result.addedQuads());
        assertEquals(16, result.colors().length);
        assertEquals(8, result.triangles().length
                / SectionGeometrySnapshot.TRIANGLE_INT_STRIDE);
        assertEquals(-FoliageSolidifier.THICKNESS,
                result.vertices()[4 * SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE + 2],
                1.0e-6F);
    }

    @Test
    void skipsSolidificationForPartialSpriteUvs() {
        float[] partialSpriteVertices = {
                0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.25F, 0.25F,
                1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.75F, 0.25F,
                1.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.75F, 0.75F,
                0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.25F, 0.75F
        };
        FoliageSolidifier.Silhouette silhouette = FoliageSolidifier.fromMask(new boolean[][]{
                {true, true},
                {true, true}
        });

        assertFalse(FoliageSolidifier.coversSprite(partialSpriteVertices, 0, silhouette));
    }
}

package dev.cyclesrenderer.scene;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SectionMaterialCaptureTest {
    @Test
    void classifiesImpermeableBlocksAndBothPaneKindsAsGlass() {
        assertMaterial(SectionGeometrySnapshot.MATERIAL_GLASS,
                facts(true, false, false, false, false, false, false));
        assertMaterial(SectionGeometrySnapshot.MATERIAL_GLASS,
                facts(false, true, false, false, false, false, false));
        assertMaterial(SectionGeometrySnapshot.MATERIAL_GLASS,
                facts(false, false, true, false, false, false, false));
    }

    @Test
    void classifiesEveryCapturedPlantCategoryAsFoliage() {
        assertMaterial(SectionGeometrySnapshot.MATERIAL_FOLIAGE,
                facts(false, false, false, true, false, false, false));
        assertMaterial(SectionGeometrySnapshot.MATERIAL_FOLIAGE,
                facts(false, false, false, false, true, false, false));
        assertMaterial(SectionGeometrySnapshot.MATERIAL_FOLIAGE,
                facts(false, false, false, false, false, true, false));
        assertMaterial(SectionGeometrySnapshot.MATERIAL_FOLIAGE,
                facts(false, false, false, false, false, false, true));
        assertMaterial(SectionGeometrySnapshot.MATERIAL_UNCHANGED,
                facts(false, false, false, false, false, false, false));
    }

    @Test
    void givesGlassPrecedenceOverFoliageFacts() {
        assertMaterial(SectionGeometrySnapshot.MATERIAL_GLASS,
                facts(true, false, false, true, true, true, true));
    }

    @Test
    void solidifiesOnlyCutoutNonLeafPlantCards() {
        assertTrue(SectionMaterialCapture.shouldSolidifyFoliage(
                ChunkSectionLayer.CUTOUT,
                facts(false, false, false, false, true, false, false)));
        assertTrue(SectionMaterialCapture.shouldSolidifyFoliage(
                ChunkSectionLayer.CUTOUT,
                facts(false, false, false, false, false, true, false)));
        assertTrue(SectionMaterialCapture.shouldSolidifyFoliage(
                ChunkSectionLayer.CUTOUT,
                facts(false, false, false, false, false, false, true)));
        assertFalse(SectionMaterialCapture.shouldSolidifyFoliage(
                ChunkSectionLayer.CUTOUT,
                facts(false, false, false, true, false, false, false)));
        assertFalse(SectionMaterialCapture.shouldSolidifyFoliage(
                ChunkSectionLayer.TRANSLUCENT,
                facts(false, false, false, false, true, false, false)));
    }

    @Test
    void identifiesWaterOnlyWhenEveryUvStaysInsideASameLayerSprite() {
        SectionMaterialCapture.SpriteBounds water =
                new SectionMaterialCapture.SpriteBounds(
                        ChunkSectionLayer.TRANSLUCENT,
                        0.25F,
                        0.75F,
                        0.125F,
                        0.875F);
        float[] vertices = quadUvs(
                0.25F, 0.125F,
                0.75F, 0.125F,
                0.75F, 0.875F,
                0.25F, 0.875F);

        assertTrue(SectionMaterialCapture.isWaterQuad(
                List.of(water), ChunkSectionLayer.TRANSLUCENT, vertices, 0));
        assertFalse(SectionMaterialCapture.isWaterQuad(
                List.of(water), ChunkSectionLayer.CUTOUT, vertices, 0));

        vertices[2 * SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE + 6] = 0.8F;
        assertFalse(SectionMaterialCapture.isWaterQuad(
                List.of(water), ChunkSectionLayer.TRANSLUCENT, vertices, 0));
    }

    @Test
    void acceptsFullSpriteUvsForFoliageSolidification() {
        FoliageSolidifier.Silhouette silhouette = FoliageSolidifier.fromMask(
                new boolean[][] {{true, true}, {true, true}});
        float[] fullSprite = quadUvs(
                0.0F, 0.0F,
                1.0F, 0.0F,
                1.0F, 1.0F,
                0.0F, 1.0F);

        assertTrue(FoliageSolidifier.coversSprite(fullSprite, 0, silhouette));
    }

    private static void assertMaterial(
            int expected,
            SectionMaterialCapture.BlockMaterialFacts facts) {
        assertEquals(expected, SectionMaterialCapture.classifyBlockMaterial(facts));
    }

    private static SectionMaterialCapture.BlockMaterialFacts facts(
            boolean impermeable,
            boolean plainGlassPane,
            boolean stainedGlassPane,
            boolean leaves,
            boolean vegetation,
            boolean growingPlant,
            boolean vine) {
        return new SectionMaterialCapture.BlockMaterialFacts(
                impermeable,
                plainGlassPane,
                stainedGlassPane,
                leaves,
                vegetation,
                growingPlant,
                vine);
    }

    private static float[] quadUvs(float... uvs) {
        float[] vertices = new float[
                4 * SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE];
        for (int corner = 0; corner < 4; corner++) {
            int target = corner * SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE;
            vertices[target + 6] = uvs[corner * 2];
            vertices[target + 7] = uvs[corner * 2 + 1];
        }
        return vertices;
    }
}

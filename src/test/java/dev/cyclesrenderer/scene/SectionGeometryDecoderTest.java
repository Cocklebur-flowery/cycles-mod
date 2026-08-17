package dev.cyclesrenderer.scene;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SectionGeometryDecoderTest {
    private static final int VERTEX_STRIDE = 24;

    @Test
    void decodesQuadOrderColorsUvsNormalsAndMaterial() {
        int[] colors = {0x10203040, 0x50607080, 0x90A0B0C0, 0xD0E0F001};
        ByteBuffer vertices = quadBuffer(
                new float[][] {
                    {0.0F, 0.0F, 0.0F, 0.1F, 0.2F},
                    {1.0F, 0.0F, 0.0F, 0.3F, 0.4F},
                    {1.0F, 1.0F, 0.0F, 0.5F, 0.6F},
                    {0.0F, 1.0F, 0.0F, 0.7F, 0.8F}
                },
                colors);

        SectionGeometrySnapshot snapshot = SectionGeometryDecoder.decode(
                91L,
                -16,
                32,
                48,
                7L,
                List.of(layer(ChunkSectionLayer.TRANSLUCENT, vertices, 4)),
                null);

        assertEquals(91L, snapshot.sectionNode());
        assertEquals(-16, snapshot.originX());
        assertEquals(32, snapshot.originY());
        assertEquals(48, snapshot.originZ());
        assertEquals(7L, snapshot.sequence());
        assertEquals(4, snapshot.vertexCount());
        assertEquals(2, snapshot.triangleCount());
        assertEquals(1, snapshot.quadCount());
        assertArrayEquals(colors, snapshot.vertexColors());
        assertArrayEquals(
                new int[] {
                    0, 1, 2, SectionGeometrySnapshot.MATERIAL_TRANSLUCENT,
                    0, 2, 3, SectionGeometrySnapshot.MATERIAL_TRANSLUCENT
                },
                snapshot.triangleData());

        float[] expected = {
                0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.1F, 0.2F,
                1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.3F, 0.4F,
                1.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.5F, 0.6F,
                0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.7F, 0.8F
        };
        assertArrayEquals(expected, snapshot.vertexData(), 0.000001F);
    }

    @Test
    void preservesLayerOrderAndBufferStartPosition() {
        ByteBuffer first = paddedQuadBuffer(12, 1.0F, 0x01020304);
        ByteBuffer second = paddedQuadBuffer(4, 2.0F, 0x05060708);

        SectionGeometrySnapshot snapshot = SectionGeometryDecoder.decode(
                0L,
                0,
                0,
                0,
                1L,
                List.of(
                        layer(ChunkSectionLayer.SOLID, first, 4),
                        layer(ChunkSectionLayer.CUTOUT, second, 4)),
                null);

        assertEquals(1.0F, snapshot.vertexData()[0]);
        assertEquals(2.0F, snapshot.vertexData()[4 * SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE]);
        assertEquals(0x01020304, snapshot.vertexColors()[0]);
        assertEquals(0x05060708, snapshot.vertexColors()[4]);
        assertArrayEquals(
                new int[] {
                    0, 1, 2, SectionGeometrySnapshot.MATERIAL_SOLID,
                    0, 2, 3, SectionGeometrySnapshot.MATERIAL_SOLID,
                    4, 5, 6, SectionGeometrySnapshot.MATERIAL_CUTOUT,
                    4, 6, 7, SectionGeometrySnapshot.MATERIAL_CUTOUT
                },
                snapshot.triangleData());
    }

    @Test
    void usesUpNormalForDegenerateQuad() {
        ByteBuffer vertices = quadBuffer(
                new float[][] {
                    {2.0F, 3.0F, 4.0F, 0.0F, 0.0F},
                    {2.0F, 3.0F, 4.0F, 0.0F, 0.0F},
                    {2.0F, 3.0F, 4.0F, 0.0F, 0.0F},
                    {2.0F, 3.0F, 4.0F, 0.0F, 0.0F}
                },
                new int[4]);

        SectionGeometrySnapshot snapshot = SectionGeometryDecoder.decode(
                0L, 0, 0, 0, 1L,
                List.of(layer(ChunkSectionLayer.SOLID, vertices, 4)),
                null);

        for (int corner = 0; corner < 4; corner++) {
            int offset = corner * SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE;
            assertEquals(0.0F, snapshot.vertexData()[offset + 3]);
            assertEquals(1.0F, snapshot.vertexData()[offset + 4]);
            assertEquals(0.0F, snapshot.vertexData()[offset + 5]);
        }
    }

    @Test
    void returnsEmptySnapshotForNoLayers() {
        SectionGeometrySnapshot snapshot = SectionGeometryDecoder.decode(
                17L, 16, 0, -16, 3L, List.of(), null);

        assertTrue(snapshot.empty());
        assertEquals(0, snapshot.vertexCount());
        assertEquals(0, snapshot.triangleCount());
        assertEquals(0, snapshot.quadCount());
        assertArrayEquals(new float[0], snapshot.vertexData());
        assertArrayEquals(new int[0], snapshot.vertexColors());
        assertArrayEquals(new int[0], snapshot.triangleData());
    }

    private static SectionGeometryDecoder.LayerInput layer(
            ChunkSectionLayer layer,
            ByteBuffer vertices,
            int vertexCount) {
        int material = switch (layer) {
            case SOLID -> SectionGeometrySnapshot.MATERIAL_SOLID;
            case CUTOUT -> SectionGeometrySnapshot.MATERIAL_CUTOUT;
            case TRANSLUCENT -> SectionGeometrySnapshot.MATERIAL_TRANSLUCENT;
        };
        return new SectionGeometryDecoder.LayerInput(
                layer, vertices, vertexCount, VERTEX_STRIDE, 0, 12, 16, material);
    }

    private static ByteBuffer paddedQuadBuffer(int padding, float x, int color) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(padding + 4 * VERTEX_STRIDE)
                .order(ByteOrder.nativeOrder());
        buffer.position(padding);
        for (int vertex = 0; vertex < 4; vertex++) {
            int base = padding + vertex * VERTEX_STRIDE;
            buffer.putFloat(base, x + vertex);
            buffer.putFloat(base + 4, vertex & 1);
            buffer.putFloat(base + 8, 0.0F);
            buffer.putInt(base + 12, color + vertex);
            buffer.putFloat(base + 16, vertex * 0.25F);
            buffer.putFloat(base + 20, vertex * 0.125F);
        }
        return buffer;
    }

    private static ByteBuffer quadBuffer(float[][] values, int[] colors) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(4 * VERTEX_STRIDE)
                .order(ByteOrder.nativeOrder());
        for (int vertex = 0; vertex < 4; vertex++) {
            int base = vertex * VERTEX_STRIDE;
            buffer.putFloat(base, values[vertex][0]);
            buffer.putFloat(base + 4, values[vertex][1]);
            buffer.putFloat(base + 8, values[vertex][2]);
            buffer.putInt(base + 12, colors[vertex]);
            buffer.putFloat(base + 16, values[vertex][3]);
            buffer.putFloat(base + 20, values[vertex][4]);
        }
        return buffer;
    }
}

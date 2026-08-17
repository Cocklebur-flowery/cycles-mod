package dev.cyclesrenderer.scene;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Decodes compiled layer buffers into the renderer's immutable section payload. */
final class SectionGeometryDecoder {
    private static final int[] QUAD_TRIANGLES = {0, 1, 2, 0, 2, 3};

    private SectionGeometryDecoder() {
    }

    static SectionGeometrySnapshot decode(
            long sectionNode,
            int originX,
            int originY,
            int originZ,
            long sequence,
            List<LayerInput> layers,
            SectionMaterialCapture materialColors) {
        int vertexCount = 0;
        int quadCount = 0;
        for (LayerInput layer : layers) {
            vertexCount = Math.addExact(vertexCount, layer.vertexCount());
            quadCount = Math.addExact(quadCount, layer.vertexCount() / 4);
        }

        float[] vertices = new float[Math.multiplyExact(
                vertexCount, SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE)];
        int[] colors = new int[vertexCount];
        int[] triangles = new int[Math.multiplyExact(
                Math.multiplyExact(quadCount, 2),
                SectionGeometrySnapshot.TRIANGLE_INT_STRIDE)];
        int outputVertex = 0;
        int outputTriangle = 0;
        List<FoliageSolidifier.Quad> foliageQuads = new ArrayList<>();

        for (LayerInput layer : layers) {
            ByteBuffer source = layer.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
            int sourceStart = source.position();
            int layerVertexBase = outputVertex;

            for (int index = 0; index < layer.vertexCount(); index++) {
                int sourceBase = Math.addExact(
                        sourceStart,
                        Math.multiplyExact(index, layer.vertexStride()));
                int targetBase = outputVertex * SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE;
                int positionOffset = sourceBase + layer.positionOffset();
                int colorOffset = sourceBase + layer.colorOffset();
                int uvOffset = sourceBase + layer.uvOffset();
                vertices[targetBase] = source.getFloat(positionOffset);
                vertices[targetBase + 1] = source.getFloat(positionOffset + 4);
                vertices[targetBase + 2] = source.getFloat(positionOffset + 8);
                vertices[targetBase + 6] = source.getFloat(uvOffset);
                vertices[targetBase + 7] = source.getFloat(uvOffset + 4);
                colors[outputVertex] = source.getInt(colorOffset);
                outputVertex++;
            }

            int layerQuadCount = layer.vertexCount() / 4;
            for (int quad = 0; quad < layerQuadCount; quad++) {
                int vertexBase = layerVertexBase + quad * 4;
                int materialIndex = layer.materialIndex();
                FoliageSolidifier.Silhouette silhouette = null;
                if (materialColors != null) {
                    SectionMaterialCapture.DecodedQuad decoded = materialColors.decodeQuad(
                            layer.layer(), vertices, vertexBase, materialIndex);
                    if (decoded.colors() != null) {
                        System.arraycopy(
                                decoded.colors(), 0, colors, vertexBase, decoded.colors().length);
                    }
                    materialIndex = decoded.materialIndex();
                    silhouette = decoded.silhouette();
                }
                writeQuadNormal(vertices, vertexBase);
                if (silhouette != null
                        && FoliageSolidifier.coversSprite(vertices, vertexBase, silhouette)) {
                    foliageQuads.add(new FoliageSolidifier.Quad(
                            vertexBase, materialIndex, silhouette));
                }
                for (int triangle = 0; triangle < 2; triangle++) {
                    int target = outputTriangle * SectionGeometrySnapshot.TRIANGLE_INT_STRIDE;
                    int indices = triangle * 3;
                    triangles[target] = vertexBase + QUAD_TRIANGLES[indices];
                    triangles[target + 1] = vertexBase + QUAD_TRIANGLES[indices + 1];
                    triangles[target + 2] = vertexBase + QUAD_TRIANGLES[indices + 2];
                    triangles[target + 3] = materialIndex;
                    outputTriangle++;
                }
            }
        }

        CoplanarOverlayResolver.separate(vertices, triangles, quadCount);
        FoliageSolidifier.Result solidified = FoliageSolidifier.apply(
                vertices, colors, triangles, foliageQuads);

        return new SectionGeometrySnapshot(
                sectionNode,
                originX,
                originY,
                originZ,
                solidified.vertices(),
                solidified.colors(),
                solidified.triangles(),
                Math.addExact(quadCount, solidified.addedQuads()),
                sequence);
    }

    private static void writeQuadNormal(float[] vertices, int vertexBase) {
        int first = vertexBase * SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE;
        int second = (vertexBase + 1) * SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE;
        int third = (vertexBase + 2) * SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE;
        float edge1X = vertices[second] - vertices[first];
        float edge1Y = vertices[second + 1] - vertices[first + 1];
        float edge1Z = vertices[second + 2] - vertices[first + 2];
        float edge2X = vertices[third] - vertices[first];
        float edge2Y = vertices[third + 1] - vertices[first + 1];
        float edge2Z = vertices[third + 2] - vertices[first + 2];
        float normalX = edge1Y * edge2Z - edge1Z * edge2Y;
        float normalY = edge1Z * edge2X - edge1X * edge2Z;
        float normalZ = edge1X * edge2Y - edge1Y * edge2X;
        float length = (float) Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
        if (length <= 1.0e-8F || !Float.isFinite(length)) {
            normalX = 0.0F;
            normalY = 1.0F;
            normalZ = 0.0F;
        } else {
            normalX /= length;
            normalY /= length;
            normalZ /= length;
        }
        for (int corner = 0; corner < 4; corner++) {
            int target = (vertexBase + corner) * SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE;
            vertices[target + 3] = normalX;
            vertices[target + 4] = normalY;
            vertices[target + 5] = normalZ;
        }
    }

    record LayerInput(
            ChunkSectionLayer layer,
            ByteBuffer vertexBuffer,
            int vertexCount,
            int vertexStride,
            int positionOffset,
            int colorOffset,
            int uvOffset,
            int materialIndex) {
    }
}

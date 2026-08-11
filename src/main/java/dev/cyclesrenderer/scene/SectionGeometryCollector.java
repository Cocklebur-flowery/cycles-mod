package dev.cyclesrenderer.scene;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

public final class SectionGeometryCollector {
    private static final Logger LOGGER = LoggerFactory.getLogger(SectionGeometryCollector.class);
    private static final int[] QUAD_TRIANGLES = {0, 1, 2, 0, 2, 3};
    private static final ConcurrentHashMap<Long, SectionGeometrySnapshot> PENDING =
            new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<Long> COMPLETED =
            new ConcurrentLinkedQueue<>();
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private static volatile boolean enabled;
    private static volatile ClientLevel activeLevel;

    private SectionGeometryCollector() {
    }

    public static void setEnabled(boolean enabled) {
        SectionGeometryCollector.enabled = enabled;
        if (!enabled) {
            activeLevel = null;
            clear();
        }
    }

    public static void setActiveLevel(ClientLevel level) {
        activeLevel = level;
        clear();
    }

    public static void clear() {
        COMPLETED.clear();
        PENDING.clear();
    }

    public static SectionGeometrySnapshot poll() {
        Long sectionNode;
        while ((sectionNode = COMPLETED.poll()) != null) {
            SectionGeometrySnapshot snapshot = PENDING.remove(sectionNode);
            if (snapshot != null) {
                return snapshot;
            }
        }
        return null;
    }

    public static void capture(
            SectionPos sectionPos,
            RenderSectionRegion region,
            SectionCompiler.Results results) {
        ClientLevel expectedLevel = activeLevel;
        if (!enabled || expectedLevel == null
                || region.getLightEngine() != expectedLevel.getLightEngine()) {
            return;
        }

        try {
            SectionGeometrySnapshot snapshot = decode(
                    sectionPos, results, SEQUENCE.incrementAndGet());
            if (expectedLevel != activeLevel) {
                return;
            }
            if (PENDING.put(sectionPos.asLong(), snapshot) == null) {
                COMPLETED.add(sectionPos.asLong());
            }
        } catch (RuntimeException error) {
            LOGGER.warn("Failed to copy compiled Minecraft section {}", sectionPos, error);
        }
    }

    private static SectionGeometrySnapshot decode(
            SectionPos sectionPos,
            SectionCompiler.Results results,
            long sequence) {
        int vertexCount = 0;
        int quadCount = 0;
        for (Map.Entry<ChunkSectionLayer, MeshData> entry : results.renderedLayers.entrySet()) {
            MeshData.DrawState draw = entry.getValue().drawState();
            if (draw.primitiveTopology() != PrimitiveTopology.QUADS) {
                continue;
            }
            vertexCount = Math.addExact(vertexCount, draw.vertexCount());
            quadCount = Math.addExact(quadCount, draw.vertexCount() / 4);
        }

        float[] vertices = new float[Math.multiplyExact(
                vertexCount, SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE)];
        int[] colors = new int[vertexCount];
        int[] triangles = new int[Math.multiplyExact(
                Math.multiplyExact(quadCount, 2),
                SectionGeometrySnapshot.TRIANGLE_INT_STRIDE)];
        int outputVertex = 0;
        int outputTriangle = 0;

        for (Map.Entry<ChunkSectionLayer, MeshData> entry : results.renderedLayers.entrySet()) {
            ChunkSectionLayer layer = entry.getKey();
            MeshData mesh = entry.getValue();
            MeshData.DrawState draw = mesh.drawState();
            if (draw.primitiveTopology() != PrimitiveTopology.QUADS) {
                continue;
            }

            VertexFormat format = draw.format();
            VertexFormatElement position = requiredElement(format, "Position");
            VertexFormatElement color = requiredElement(format, "Color");
            VertexFormatElement uv = requiredElement(format, "UV0");
            int stride = format.getVertexSize();
            ByteBuffer source = mesh.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
            int sourceStart = source.position();
            int layerVertexBase = outputVertex;

            for (int index = 0; index < draw.vertexCount(); index++) {
                int sourceBase = Math.addExact(sourceStart, Math.multiplyExact(index, stride));
                int targetBase = outputVertex * SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE;
                int positionOffset = sourceBase + position.offset();
                int colorOffset = sourceBase + color.offset();
                int uvOffset = sourceBase + uv.offset();
                vertices[targetBase] = source.getFloat(positionOffset);
                vertices[targetBase + 1] = source.getFloat(positionOffset + 4);
                vertices[targetBase + 2] = source.getFloat(positionOffset + 8);
                vertices[targetBase + 6] = source.getFloat(uvOffset);
                vertices[targetBase + 7] = source.getFloat(uvOffset + 4);
                colors[outputVertex] = source.getInt(colorOffset);
                outputVertex++;
            }

            int materialIndex = materialIndex(layer);
            int layerQuadCount = draw.vertexCount() / 4;
            for (int quad = 0; quad < layerQuadCount; quad++) {
                int vertexBase = layerVertexBase + quad * 4;
                writeQuadNormal(vertices, vertexBase);
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

        return new SectionGeometrySnapshot(
                sectionPos.asLong(),
                sectionPos.minBlockX(),
                sectionPos.minBlockY(),
                sectionPos.minBlockZ(),
                vertices,
                colors,
                triangles,
                quadCount,
                sequence);
    }

    private static VertexFormatElement requiredElement(VertexFormat format, String name) {
        VertexFormatElement element = format.getElement(name);
        if (element == null) {
            throw new IllegalArgumentException("compiled section format has no " + name + " element");
        }
        return element;
    }

    private static int materialIndex(ChunkSectionLayer layer) {
        return switch (layer) {
            case SOLID -> SectionGeometrySnapshot.MATERIAL_SOLID;
            case CUTOUT -> SectionGeometrySnapshot.MATERIAL_CUTOUT;
            case TRANSLUCENT -> SectionGeometrySnapshot.MATERIAL_TRANSLUCENT;
        };
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
}

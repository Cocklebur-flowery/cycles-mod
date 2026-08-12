package dev.cyclesrenderer.scene;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.extensions.common.IClientBlockExtensions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.HashMap;
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
    private static final AtomicLong QUEUED_COUNT = new AtomicLong();
    private static final AtomicLong CAPTURE_COUNT = new AtomicLong();
    private static final AtomicLong REPLACED_COUNT = new AtomicLong();
    private static final AtomicLong LAST_CAPTURE_MICROS = new AtomicLong();
    private static final AtomicLong EMA_CAPTURE_MICROS = new AtomicLong();
    private static final AtomicLong MAX_CAPTURE_MICROS = new AtomicLong();
    private static final ThreadLocal<MaterialColorCapture> MATERIAL_COLOR_CAPTURE =
            new ThreadLocal<>();

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
        QUEUED_COUNT.set(0L);
        CAPTURE_COUNT.set(0L);
        REPLACED_COUNT.set(0L);
        LAST_CAPTURE_MICROS.set(0L);
        EMA_CAPTURE_MICROS.set(0L);
        MAX_CAPTURE_MICROS.set(0L);
    }

    public static void beginMaterialColorCapture(
            SectionPos sectionPos,
            RenderSectionRegion region) {
        ClientLevel expectedLevel = activeLevel;
        if (!enabled || expectedLevel == null
                || region.getLightEngine() != expectedLevel.getLightEngine()) {
            MATERIAL_COLOR_CAPTURE.remove();
            return;
        }
        MATERIAL_COLOR_CAPTURE.set(new MaterialColorCapture(sectionPos.asLong()));
    }

    public static void capturePhysicalMaterialColors(
            ChunkSectionLayer layer,
            float x,
            float y,
            float z,
            BakedQuad quad,
            BlockAndTintGetter level,
            BlockPos position,
            BlockState state,
            BlockColors blockColors) {
        MaterialColorCapture capture = MATERIAL_COLOR_CAPTURE.get();
        if (capture == null) {
            return;
        }

        int tint = capture.tintColor(blockColors, level, position, state, quad.materialInfo().tintIndex());
        int[] colors = new int[BakedQuad.VERTEX_COUNT];
        for (int corner = 0; corner < BakedQuad.VERTEX_COUNT; corner++) {
            colors[corner] = packRgba(ARGB.multiply(quad.bakedColors().color(corner), tint));
        }
        capture.colors
                .computeIfAbsent(signature(layer, x, y, z, quad), ignored -> new ArrayDeque<>())
                .addLast(colors);
    }

    public static SectionGeometrySnapshot poll() {
        Long sectionNode;
        while ((sectionNode = COMPLETED.poll()) != null) {
            QUEUED_COUNT.updateAndGet(value -> Math.max(0L, value - 1L));
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
        MaterialColorCapture materialColors = MATERIAL_COLOR_CAPTURE.get();
        MATERIAL_COLOR_CAPTURE.remove();
        ClientLevel expectedLevel = activeLevel;
        if (!enabled || expectedLevel == null
                || region.getLightEngine() != expectedLevel.getLightEngine()) {
            return;
        }

        long captureStart = System.nanoTime();
        try {
            SectionGeometrySnapshot snapshot = decode(
                    sectionPos,
                    results,
                    SEQUENCE.incrementAndGet(),
                    materialColors != null && materialColors.sectionNode == sectionPos.asLong()
                            ? materialColors
                            : null);
            if (expectedLevel != activeLevel) {
                return;
            }
            if (PENDING.put(sectionPos.asLong(), snapshot) == null) {
                COMPLETED.add(sectionPos.asLong());
                QUEUED_COUNT.incrementAndGet();
            } else {
                REPLACED_COUNT.incrementAndGet();
            }
        } catch (RuntimeException error) {
            LOGGER.warn("Failed to copy compiled Minecraft section {}", sectionPos, error);
        } finally {
            recordCapture(System.nanoTime() - captureStart);
        }
    }

    public static Telemetry telemetry() {
        return new Telemetry(
                CAPTURE_COUNT.get(),
                REPLACED_COUNT.get(),
                QUEUED_COUNT.get(),
                PENDING.size(),
                LAST_CAPTURE_MICROS.get(),
                EMA_CAPTURE_MICROS.get(),
                MAX_CAPTURE_MICROS.get());
    }

    private static void recordCapture(long elapsedNanos) {
        long micros = Math.max(0L, (elapsedNanos + 999L) / 1_000L);
        CAPTURE_COUNT.incrementAndGet();
        LAST_CAPTURE_MICROS.set(micros);
        EMA_CAPTURE_MICROS.updateAndGet(
                previous -> previous == 0L ? micros : (previous * 7L + micros) / 8L);
        MAX_CAPTURE_MICROS.accumulateAndGet(micros, Math::max);
    }

    private static SectionGeometrySnapshot decode(
            SectionPos sectionPos,
            SectionCompiler.Results results,
            long sequence,
            MaterialColorCapture materialColors) {
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
                if (materialColors != null) {
                    ArrayDeque<int[]> candidates = materialColors.colors.get(
                            signature(layer, vertices, vertexBase));
                    int[] physicalColors = candidates == null ? null : candidates.pollFirst();
                    if (physicalColors != null) {
                        System.arraycopy(physicalColors, 0, colors, vertexBase, physicalColors.length);
                    }
                }
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

    private static QuadSignature signature(
            ChunkSectionLayer layer,
            float x,
            float y,
            float z,
            BakedQuad quad) {
        long first = 0xCBF29CE484222325L ^ layer.ordinal();
        long second = 0x9E3779B97F4A7C15L ^ layer.ordinal();
        for (int corner = 0; corner < BakedQuad.VERTEX_COUNT; corner++) {
            first = mixFirst(first, Float.floatToRawIntBits(quad.position(corner).x() + x));
            first = mixFirst(first, Float.floatToRawIntBits(quad.position(corner).y() + y));
            first = mixFirst(first, Float.floatToRawIntBits(quad.position(corner).z() + z));
            first = mixFirst(first, Float.floatToRawIntBits(UVPair.unpackU(quad.packedUV(corner))));
            first = mixFirst(first, Float.floatToRawIntBits(UVPair.unpackV(quad.packedUV(corner))));

            second = mixSecond(second, Float.floatToRawIntBits(quad.position(corner).x() + x));
            second = mixSecond(second, Float.floatToRawIntBits(quad.position(corner).y() + y));
            second = mixSecond(second, Float.floatToRawIntBits(quad.position(corner).z() + z));
            second = mixSecond(second, Float.floatToRawIntBits(UVPair.unpackU(quad.packedUV(corner))));
            second = mixSecond(second, Float.floatToRawIntBits(UVPair.unpackV(quad.packedUV(corner))));
        }
        return new QuadSignature(first, second);
    }

    private static QuadSignature signature(
            ChunkSectionLayer layer,
            float[] vertices,
            int vertexBase) {
        long first = 0xCBF29CE484222325L ^ layer.ordinal();
        long second = 0x9E3779B97F4A7C15L ^ layer.ordinal();
        for (int corner = 0; corner < 4; corner++) {
            int offset = (vertexBase + corner) * SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE;
            first = mixFirst(first, Float.floatToRawIntBits(vertices[offset]));
            first = mixFirst(first, Float.floatToRawIntBits(vertices[offset + 1]));
            first = mixFirst(first, Float.floatToRawIntBits(vertices[offset + 2]));
            first = mixFirst(first, Float.floatToRawIntBits(vertices[offset + 6]));
            first = mixFirst(first, Float.floatToRawIntBits(vertices[offset + 7]));

            second = mixSecond(second, Float.floatToRawIntBits(vertices[offset]));
            second = mixSecond(second, Float.floatToRawIntBits(vertices[offset + 1]));
            second = mixSecond(second, Float.floatToRawIntBits(vertices[offset + 2]));
            second = mixSecond(second, Float.floatToRawIntBits(vertices[offset + 6]));
            second = mixSecond(second, Float.floatToRawIntBits(vertices[offset + 7]));
        }
        return new QuadSignature(first, second);
    }

    private static long mixFirst(long hash, int value) {
        return (hash ^ Integer.toUnsignedLong(value)) * 0x100000001B3L;
    }

    private static long mixSecond(long hash, int value) {
        return Long.rotateLeft(hash ^ Integer.toUnsignedLong(value) * 0xD6E8FEB86659FD93L, 27)
                * 0x9E3779B185EBCA87L;
    }

    private static int packRgba(int argb) {
        return ARGB.red(argb)
                | ARGB.green(argb) << 8
                | ARGB.blue(argb) << 16
                | ARGB.alpha(argb) << 24;
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

    public record Telemetry(
            long captureCount,
            long replacedCount,
            long queuedCount,
            int pendingSnapshots,
            long lastCaptureMicros,
            long emaCaptureMicros,
            long maxCaptureMicros) {
    }

    private record QuadSignature(long first, long second) {
    }

    private record TintKey(long blockPosition, int tintIndex) {
    }

    private static final class MaterialColorCapture {
        private final long sectionNode;
        private final Map<QuadSignature, ArrayDeque<int[]>> colors = new HashMap<>();
        private final Map<TintKey, Integer> tintColors = new HashMap<>();

        private MaterialColorCapture(long sectionNode) {
            this.sectionNode = sectionNode;
        }

        private int tintColor(
                BlockColors blockColors,
                BlockAndTintGetter level,
                BlockPos position,
                BlockState state,
                int tintIndex) {
            if (tintIndex < 0) {
                return 0xFFFFFFFF;
            }
            TintKey key = new TintKey(position.asLong(), tintIndex);
            Integer cached = tintColors.get(key);
            if (cached != null) {
                return cached;
            }

            int color = resolveTintColor(blockColors, level, position, state, tintIndex);
            tintColors.put(key, color);
            return color;
        }

        private static int resolveTintColor(
                BlockColors blockColors,
                BlockAndTintGetter level,
                BlockPos position,
                BlockState state,
                int tintIndex) {
            BlockTintSource source = blockColors.getTintSource(state, tintIndex);
            if (source != null) {
                return ARGB.opaque(source.colorInWorld(state, level, position));
            }
            if (blockColors.getTintSources(state).isEmpty()) {
                IntArrayList dynamicColors = new IntArrayList();
                IClientBlockExtensions.of(state)
                        .collectDynamicTintValues(state, level, position, dynamicColors);
                if (tintIndex < dynamicColors.size()) {
                    return ARGB.opaque(dynamicColors.getInt(tintIndex));
                }
            }
            return 0xFFFFFFFF;
        }
    }
}

package dev.cyclesrenderer.scene;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

public final class SectionGeometryCollector {
    private static final Logger LOGGER = LoggerFactory.getLogger(SectionGeometryCollector.class);
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
    private static final ThreadLocal<SectionMaterialCapture> MATERIAL_COLOR_CAPTURE =
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
        MATERIAL_COLOR_CAPTURE.set(new SectionMaterialCapture(sectionPos.asLong()));
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
        SectionMaterialCapture capture = MATERIAL_COLOR_CAPTURE.get();
        if (capture == null) {
            return;
        }
        capture.captureBlockQuad(
                layer, x, y, z, quad, level, position, state, blockColors);
    }

    public static void captureFluidMaterial(FluidState state, FluidModel model) {
        SectionMaterialCapture capture = MATERIAL_COLOR_CAPTURE.get();
        if (capture != null) {
            capture.captureFluidModel(state, model);
        }
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
        SectionMaterialCapture materialColors = MATERIAL_COLOR_CAPTURE.get();
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
                    materialColors != null && materialColors.sectionNode() == sectionPos.asLong()
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
            SectionMaterialCapture materialColors) {
        List<SectionGeometryDecoder.LayerInput> layers = new ArrayList<>();
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
            layers.add(new SectionGeometryDecoder.LayerInput(
                    layer,
                    mesh.vertexBuffer(),
                    draw.vertexCount(),
                    format.getVertexSize(),
                    position.offset(),
                    color.offset(),
                    uv.offset(),
                    materialIndex(layer)));
        }
        return SectionGeometryDecoder.decode(
                sectionPos.asLong(),
                sectionPos.minBlockX(),
                sectionPos.minBlockY(),
                sectionPos.minBlockZ(),
                sequence,
                layers,
                materialColors);
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

    public record Telemetry(
            long captureCount,
            long replacedCount,
            long queuedCount,
            int pendingSnapshots,
            long lastCaptureMicros,
            long emaCaptureMicros,
            long maxCaptureMicros) {
    }

}

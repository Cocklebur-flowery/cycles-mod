package dev.cyclesrenderer.scene;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.extensions.common.IClientBlockExtensions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Captures per-quad material facts that the compiled section buffer does not retain. */
final class SectionMaterialCapture {
    private static final float UV_EPSILON = 1.0e-6F;
    private static final int[] SIGNATURE_COMPONENTS = {0, 1, 2, 6, 7};

    private final long sectionNode;
    private final Map<QuadSignature, ArrayDeque<CapturedQuad>> blockQuads = new HashMap<>();
    private final Map<TintKey, Integer> tintColors = new HashMap<>();
    private final List<SpriteBounds> waterSprites = new ArrayList<>();

    SectionMaterialCapture(long sectionNode) {
        this.sectionNode = sectionNode;
    }

    long sectionNode() {
        return sectionNode;
    }

    void captureBlockQuad(
            ChunkSectionLayer layer,
            float x,
            float y,
            float z,
            BakedQuad quad,
            BlockAndTintGetter level,
            BlockPos position,
            BlockState state,
            BlockColors blockColors) {
        int tint = tintColor(blockColors, level, position, state, quad.materialInfo().tintIndex());
        int[] colors = new int[BakedQuad.VERTEX_COUNT];
        for (int corner = 0; corner < BakedQuad.VERTEX_COUNT; corner++) {
            colors[corner] = packRgba(ARGB.multiply(quad.bakedColors().color(corner), tint));
        }
        int materialOverride = isGlass(state)
                ? SectionGeometrySnapshot.MATERIAL_GLASS
                : SectionGeometrySnapshot.MATERIAL_UNCHANGED;
        blockQuads.computeIfAbsent(
                        signature(layer, x, y, z, quad), ignored -> new ArrayDeque<>())
                .addLast(new CapturedQuad(colors, materialOverride));
    }

    void captureFluidModel(FluidState state, FluidModel model) {
        if (!state.is(FluidTags.WATER)) {
            return;
        }
        addWaterSprite(model.layer(), model.stillMaterial());
        addWaterSprite(model.layer(), model.flowingMaterial());
        if (model.overlayMaterial() != null) {
            addWaterSprite(model.layer(), model.overlayMaterial());
        }
    }

    DecodedQuad decodeQuad(
            ChunkSectionLayer layer,
            float[] vertices,
            int vertexBase,
            int fallbackMaterial) {
        ArrayDeque<CapturedQuad> candidates = blockQuads.get(signature(layer, vertices, vertexBase));
        CapturedQuad captured = candidates == null ? null : candidates.pollFirst();
        int material = captured != null
                && captured.materialOverride() != SectionGeometrySnapshot.MATERIAL_UNCHANGED
                ? captured.materialOverride()
                : fallbackMaterial;
        if (material == fallbackMaterial && isWaterQuad(layer, vertices, vertexBase)) {
            material = SectionGeometrySnapshot.MATERIAL_WATER;
        }
        return new DecodedQuad(captured == null ? null : captured.colors(), material);
    }

    private void addWaterSprite(ChunkSectionLayer layer, Material.Baked material) {
        TextureAtlasSprite sprite = material.sprite();
        SpriteBounds bounds = new SpriteBounds(
                layer,
                Math.min(sprite.getU0(), sprite.getU1()),
                Math.max(sprite.getU0(), sprite.getU1()),
                Math.min(sprite.getV0(), sprite.getV1()),
                Math.max(sprite.getV0(), sprite.getV1()));
        if (!waterSprites.contains(bounds)) {
            waterSprites.add(bounds);
        }
    }

    private boolean isWaterQuad(ChunkSectionLayer layer, float[] vertices, int vertexBase) {
        for (SpriteBounds bounds : waterSprites) {
            if (bounds.layer() != layer) {
                continue;
            }
            boolean inside = true;
            for (int corner = 0; corner < 4; corner++) {
                int offset = (vertexBase + corner) * SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE;
                inside &= bounds.contains(vertices[offset + 6], vertices[offset + 7]);
            }
            if (inside) {
                return true;
            }
        }
        return false;
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
        return tintColors.computeIfAbsent(
                key,
                ignored -> resolveTintColor(blockColors, level, position, state, tintIndex));
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

    private static boolean isGlass(BlockState state) {
        return state.is(BlockTags.IMPERMEABLE)
                || state.getBlock() == Blocks.GLASS_PANE
                || state.getBlock() instanceof StainedGlassPaneBlock;
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
            for (int component : SIGNATURE_COMPONENTS) {
                int value = Float.floatToRawIntBits(vertices[offset + component]);
                first = mixFirst(first, value);
                second = mixSecond(second, value);
            }
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

    record DecodedQuad(int[] colors, int materialIndex) {
    }

    private record CapturedQuad(int[] colors, int materialOverride) {
    }

    private record QuadSignature(long first, long second) {
    }

    private record TintKey(long blockPosition, int tintIndex) {
    }

    private record SpriteBounds(
            ChunkSectionLayer layer,
            float minU,
            float maxU,
            float minV,
            float maxV) {
        private boolean contains(float u, float v) {
            return u >= minU - UV_EPSILON && u <= maxU + UV_EPSILON
                    && v >= minV - UV_EPSILON && v <= maxV + UV_EPSILON;
        }
    }
}

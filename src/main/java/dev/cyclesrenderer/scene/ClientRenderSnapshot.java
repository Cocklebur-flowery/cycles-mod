package dev.cyclesrenderer.scene;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.quad.BakedNormals;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record ClientRenderSnapshot(
        int originX,
        int originY,
        int originZ,
        float[] vertexData,
        int[] vertexColors,
        int[] triangleData,
        MaterialData[] materials,
        TextureData[] textures,
        int quadCount,
        int skippedTranslucentQuadCount,
        int unsupportedTintQuadCount,
        int skippedModelBlockCount,
        int distantHorizonsCellCount,
        int distantHorizonsQuadCount,
        int regionChunkX,
        int regionSectionY,
        int regionChunkZ,
        long resourceRevision,
        long distantHorizonsRevision) {
    public static final int SNAPSHOT_SIZE_X = 64;
    public static final int SNAPSHOT_SIZE_Y = 32;
    public static final int SNAPSHOT_SIZE_Z = 64;
    public static final int VERTEX_FLOAT_STRIDE = 8;
    public static final int TRIANGLE_INT_STRIDE = 4;
    public static final int MATERIAL_FLAG_CUTOUT = 1;

    private static final float CUTOUT_ALPHA_THRESHOLD = 0.5F;
    private static final int[] QUAD_TRIANGLES = {0, 1, 2, 0, 2, 3};
    private static final int FALLBACK_MAP_COLOR = 0x7F7F7F;
    private static final Identifier GENERATED_ATLAS = Identifier.fromNamespaceAndPath(
            "cyclesrenderer", "generated");
    private static final Identifier WHITE_SPRITE = Identifier.fromNamespaceAndPath(
            "cyclesrenderer", "generated/white");

    public static ClientRenderSnapshot capture(
            ClientLevel level,
            Vec3 cameraPosition,
            long resourceRevision,
            DistantHorizonsSceneProvider.SceneState distantHorizonsState) {
        int cameraBlockX = Mth.floor(cameraPosition.x);
        int cameraBlockY = Mth.floor(cameraPosition.y);
        int cameraBlockZ = Mth.floor(cameraPosition.z);
        int chunkX = SectionPos.blockToSectionCoord(cameraBlockX);
        int sectionY = SectionPos.blockToSectionCoord(cameraBlockY);
        int chunkZ = SectionPos.blockToSectionCoord(cameraBlockZ);

        int originX = SectionPos.sectionToBlockCoord(chunkX) - SNAPSHOT_SIZE_X / 2;
        int desiredOriginY = SectionPos.sectionToBlockCoord(sectionY) - SNAPSHOT_SIZE_Y / 4;
        int maximumOriginY = level.getMaxY() - SNAPSHOT_SIZE_Y + 1;
        int originY = Mth.clamp(
                desiredOriginY,
                level.getMinY(),
                Math.max(level.getMinY(), maximumOriginY));
        int originZ = SectionPos.sectionToBlockCoord(chunkZ) - SNAPSHOT_SIZE_Z / 2;

        Minecraft minecraft = Minecraft.getInstance();
        SceneBuilder builder = new SceneBuilder(
                minecraft.getBlockColors(), originX, originY, originZ);
        RandomSource random = RandomSource.create();
        List<BlockStateModelPart> parts = new ArrayList<>();
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (int localY = 0; localY < SNAPSHOT_SIZE_Y; localY++) {
            for (int localZ = 0; localZ < SNAPSHOT_SIZE_Z; localZ++) {
                for (int localX = 0; localX < SNAPSHOT_SIZE_X; localX++) {
                    position.set(originX + localX, originY + localY, originZ + localZ);
                    BlockState state = level.getBlockState(position);
                    if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) {
                        continue;
                    }

                    parts.clear();
                    random.setSeed(state.getSeed(position));
                    try {
                        minecraft.getModelManager()
                                .getBlockStateModelSet()
                                .get(state)
                                .collectParts(level, position, state, random, parts);
                        Vec3 offset = state.getOffset(position);
                        for (BlockStateModelPart part : parts) {
                            for (Direction direction : Direction.values()) {
                                BlockState neighbor = level.getBlockState(position.relative(direction));
                                if (Block.shouldRenderFace(level, position, state, neighbor, direction)) {
                                    builder.appendQuads(
                                            part.getQuads(direction),
                                            level,
                                            position,
                                            state,
                                            offset);
                                }
                            }
                            builder.appendQuads(
                                    part.getQuads(null), level, position, state, offset);
                        }
                    } catch (RuntimeException ignored) {
                        builder.skippedModelBlockCount++;
                    }
                }
            }
        }

        DistantHorizonsSceneProvider.LodSnapshot distantSnapshot = distantHorizonsState.snapshot();
        if (distantSnapshot != null && distantSnapshot.level() == level) {
            builder.appendDistantHorizons(level, distantSnapshot);
        }

        return builder.build(
                chunkX,
                sectionY,
                chunkZ,
                resourceRevision,
                distantHorizonsState.revision());
    }

    public boolean isCurrentFor(
            Vec3 cameraPosition,
            long currentResourceRevision,
            long currentDistantHorizonsRevision) {
        return resourceRevision == currentResourceRevision
                && distantHorizonsRevision == currentDistantHorizonsRevision
                && regionChunkX == SectionPos.blockToSectionCoord(Mth.floor(cameraPosition.x))
                && regionSectionY == SectionPos.blockToSectionCoord(Mth.floor(cameraPosition.y))
                && regionChunkZ == SectionPos.blockToSectionCoord(Mth.floor(cameraPosition.z));
    }

    public int vertexCount() {
        return vertexColors.length;
    }

    public int triangleCount() {
        return triangleData.length / TRIANGLE_INT_STRIDE;
    }

    public record MaterialData(
            int textureIndex,
            int flags,
            float emissionStrength,
            float alphaCutoff) {
    }

    public record TextureData(
            Identifier atlas,
            Identifier sprite,
            int width,
            int height,
            byte[] rgbaPixels) {
    }

    private static final class SceneBuilder {
        private final BlockColors blockColors;
        private final int originX;
        private final int originY;
        private final int originZ;
        private final Map<TextureKey, Integer> textureIndices = new HashMap<>();
        private final List<TextureData> textures = new ArrayList<>();
        private final Map<MaterialKey, Integer> materialIndices = new HashMap<>();
        private final List<MaterialData> materials = new ArrayList<>();

        private float[] vertexData = new float[16_384 * VERTEX_FLOAT_STRIDE];
        private int[] vertexColors = new int[16_384];
        private int[] triangleData = new int[8_192 * TRIANGLE_INT_STRIDE];
        private int vertexCount;
        private int triangleCount;
        private int quadCount;
        private int skippedTranslucentQuadCount;
        private int unsupportedTintQuadCount;
        private int skippedModelBlockCount;
        private int distantHorizonsCellCount;
        private int distantHorizonsQuadCount;
        private int solidColorTextureIndex = -1;

        private SceneBuilder(BlockColors blockColors, int originX, int originY, int originZ) {
            this.blockColors = blockColors;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
        }

        private void appendQuads(
                List<BakedQuad> quads,
                ClientLevel level,
                BlockPos position,
                BlockState state,
                Vec3 offset) {
            for (BakedQuad quad : quads) {
                appendQuad(quad, level, position, state, offset);
            }
        }

        private void appendQuad(
                BakedQuad quad,
                ClientLevel level,
                BlockPos position,
                BlockState state,
                Vec3 offset) {
            BakedQuad.MaterialInfo materialInfo = quad.materialInfo();
            if (materialInfo.layer() == ChunkSectionLayer.TRANSLUCENT) {
                skippedTranslucentQuadCount++;
                return;
            }

            TextureAtlasSprite sprite = materialInfo.sprite();
            int textureIndex = textureIndex(sprite);
            int flags = materialInfo.layer() == ChunkSectionLayer.CUTOUT
                    ? MATERIAL_FLAG_CUTOUT
                    : 0;
            int emissionLevel = Mth.clamp(materialInfo.lightEmission(), 0, 15);
            int materialIndex = materialIndex(textureIndex, flags, emissionLevel);

            int tint = tintColor(materialInfo.tintIndex(), level, position, state);
            int computedNormal = BakedNormals.computeQuadNormal(
                    quad.position(0), quad.position(1), quad.position(2), quad.position(3));
            int vertexBase = vertexCount;
            ensureVertexCapacity(vertexCount + 4);
            for (int corner = 0; corner < 4; corner++) {
                Vector3fc blockPosition = quad.position(corner);
                int normal = quad.bakedNormals().normal(corner);
                if (BakedNormals.isUnspecified(normal)) {
                    normal = computedNormal;
                }
                float atlasU = UVPair.unpackU(quad.packedUV(corner));
                float atlasV = UVPair.unpackV(quad.packedUV(corner));
                float localU = inverseLerp(sprite.getU0(), sprite.getU1(), atlasU);
                float localV = inverseLerp(sprite.getV0(), sprite.getV1(), atlasV);
                int dataOffset = vertexCount * VERTEX_FLOAT_STRIDE;
                vertexData[dataOffset] = (float) (position.getX() - originX + offset.x)
                        + blockPosition.x();
                vertexData[dataOffset + 1] = (float) (position.getY() - originY + offset.y)
                        + blockPosition.y();
                vertexData[dataOffset + 2] = (float) (position.getZ() - originZ + offset.z)
                        + blockPosition.z();
                vertexData[dataOffset + 3] = BakedNormals.unpackX(normal);
                vertexData[dataOffset + 4] = BakedNormals.unpackY(normal);
                vertexData[dataOffset + 5] = BakedNormals.unpackZ(normal);
                vertexData[dataOffset + 6] = localU;
                vertexData[dataOffset + 7] = localV;
                vertexColors[vertexCount] = packRgba(
                        ARGB.multiply(quad.bakedColors().color(corner), tint));
                vertexCount++;
            }

            ensureTriangleCapacity(triangleCount + 2);
            for (int triangle = 0; triangle < 2; triangle++) {
                int output = triangleCount * TRIANGLE_INT_STRIDE;
                int input = triangle * 3;
                triangleData[output] = vertexBase + QUAD_TRIANGLES[input];
                triangleData[output + 1] = vertexBase + QUAD_TRIANGLES[input + 1];
                triangleData[output + 2] = vertexBase + QUAD_TRIANGLES[input + 2];
                triangleData[output + 3] = materialIndex;
                triangleCount++;
            }
            quadCount++;
        }

        private void appendDistantHorizons(
                ClientLevel level,
                DistantHorizonsSceneProvider.LodSnapshot snapshot) {
            BlockPos.MutableBlockPos colorPosition = new BlockPos.MutableBlockPos();
            int cellSize = snapshot.cellSize();
            for (DistantHorizonsSceneProvider.LodCell cell : snapshot.cells().values()) {
                if (overlapsNearScene(cell.minX(), cell.minZ(), cellSize)) {
                    continue;
                }

                int rgb = FALLBACK_MAP_COLOR;
                try {
                    colorPosition.set(
                            cell.minX() + cellSize / 2,
                            cell.topY() - 1,
                            cell.minZ() + cellSize / 2);
                    MapColor mapColor = cell.blockState().getMapColor(level, colorPosition);
                    if (mapColor.col != 0) {
                        rgb = mapColor.col;
                    }
                } catch (RuntimeException ignored) {
                    // A modded BlockState may require a loaded chunk to compute MapColor.
                }
                int color = packRgba(0xFF000000 | rgb);
                int materialIndex = materialIndex(
                        solidColorTextureIndex(), 0, 0);
                float minX = cell.minX();
                float maxX = minX + cellSize;
                float minZ = cell.minZ();
                float maxZ = minZ + cellSize;
                float topY = cell.topY();

                appendDistantQuad(
                        minX, topY, minZ,
                        minX, topY, maxZ,
                        maxX, topY, maxZ,
                        maxX, topY, minZ,
                        0.0F, 1.0F, 0.0F,
                        materialIndex, color);

                appendDistantSide(
                        snapshot.cellAt(cell.minX(), cell.minZ() - cellSize),
                        cell,
                        cellSize,
                        cell.minZ(),
                        Direction.NORTH,
                        materialIndex,
                        color);
                appendDistantSide(
                        snapshot.cellAt(cell.minX(), cell.minZ() + cellSize),
                        cell,
                        cellSize,
                        cell.minZ() + cellSize,
                        Direction.SOUTH,
                        materialIndex,
                        color);
                appendDistantSide(
                        snapshot.cellAt(cell.minX() - cellSize, cell.minZ()),
                        cell,
                        cellSize,
                        cell.minX(),
                        Direction.WEST,
                        materialIndex,
                        color);
                appendDistantSide(
                        snapshot.cellAt(cell.minX() + cellSize, cell.minZ()),
                        cell,
                        cellSize,
                        cell.minX() + cellSize,
                        Direction.EAST,
                        materialIndex,
                        color);
                distantHorizonsCellCount++;
            }
        }

        private void appendDistantSide(
                DistantHorizonsSceneProvider.LodCell neighbor,
                DistantHorizonsSceneProvider.LodCell cell,
                int cellSize,
                float plane,
                Direction direction,
                int materialIndex,
                int color) {
            if (neighbor == null || neighbor.topY() >= cell.topY()) {
                return;
            }
            float bottomY = Math.max(cell.bottomY(), neighbor.topY());
            float topY = cell.topY();
            if (bottomY >= topY) {
                return;
            }

            float minX = cell.minX();
            float maxX = minX + cellSize;
            float minZ = cell.minZ();
            float maxZ = minZ + cellSize;
            switch (direction) {
                case NORTH -> appendDistantQuad(
                        minX, bottomY, plane,
                        minX, topY, plane,
                        maxX, topY, plane,
                        maxX, bottomY, plane,
                        0.0F, 0.0F, -1.0F,
                        materialIndex, color);
                case SOUTH -> appendDistantQuad(
                        maxX, bottomY, plane,
                        maxX, topY, plane,
                        minX, topY, plane,
                        minX, bottomY, plane,
                        0.0F, 0.0F, 1.0F,
                        materialIndex, color);
                case WEST -> appendDistantQuad(
                        plane, bottomY, maxZ,
                        plane, topY, maxZ,
                        plane, topY, minZ,
                        plane, bottomY, minZ,
                        -1.0F, 0.0F, 0.0F,
                        materialIndex, color);
                case EAST -> appendDistantQuad(
                        plane, bottomY, minZ,
                        plane, topY, minZ,
                        plane, topY, maxZ,
                        plane, bottomY, maxZ,
                        1.0F, 0.0F, 0.0F,
                        materialIndex, color);
                default -> throw new IllegalArgumentException("unsupported LOD side " + direction);
            }
        }

        private void appendDistantQuad(
                float x0, float y0, float z0,
                float x1, float y1, float z1,
                float x2, float y2, float z2,
                float x3, float y3, float z3,
                float normalX, float normalY, float normalZ,
                int materialIndex,
                int color) {
            int vertexBase = vertexCount;
            ensureVertexCapacity(vertexCount + 4);
            float[] positions = {
                    x0, y0, z0,
                    x1, y1, z1,
                    x2, y2, z2,
                    x3, y3, z3
            };
            for (int corner = 0; corner < 4; corner++) {
                int dataOffset = vertexCount * VERTEX_FLOAT_STRIDE;
                int positionOffset = corner * 3;
                vertexData[dataOffset] = positions[positionOffset] - originX;
                vertexData[dataOffset + 1] = positions[positionOffset + 1] - originY;
                vertexData[dataOffset + 2] = positions[positionOffset + 2] - originZ;
                vertexData[dataOffset + 3] = normalX;
                vertexData[dataOffset + 4] = normalY;
                vertexData[dataOffset + 5] = normalZ;
                vertexData[dataOffset + 6] = corner >= 2 ? 1.0F : 0.0F;
                vertexData[dataOffset + 7] = corner == 1 || corner == 2 ? 1.0F : 0.0F;
                vertexColors[vertexCount] = color;
                vertexCount++;
            }

            ensureTriangleCapacity(triangleCount + 2);
            for (int triangle = 0; triangle < 2; triangle++) {
                int output = triangleCount * TRIANGLE_INT_STRIDE;
                int input = triangle * 3;
                triangleData[output] = vertexBase + QUAD_TRIANGLES[input];
                triangleData[output + 1] = vertexBase + QUAD_TRIANGLES[input + 1];
                triangleData[output + 2] = vertexBase + QUAD_TRIANGLES[input + 2];
                triangleData[output + 3] = materialIndex;
                triangleCount++;
            }
            quadCount++;
            distantHorizonsQuadCount++;
        }

        private boolean overlapsNearScene(int minX, int minZ, int cellSize) {
            return minX < originX + SNAPSHOT_SIZE_X
                    && minX + cellSize > originX
                    && minZ < originZ + SNAPSHOT_SIZE_Z
                    && minZ + cellSize > originZ;
        }

        private int materialIndex(int textureIndex, int flags, int emissionLevel) {
            MaterialKey materialKey = new MaterialKey(textureIndex, flags, emissionLevel);
            return materialIndices.computeIfAbsent(materialKey, key -> {
                int index = materials.size();
                materials.add(new MaterialData(
                        key.textureIndex,
                        key.flags,
                        key.emissionLevel / 15.0F * 4.0F,
                        key.flags == MATERIAL_FLAG_CUTOUT ? CUTOUT_ALPHA_THRESHOLD : 0.0F));
                return index;
            });
        }

        private int solidColorTextureIndex() {
            if (solidColorTextureIndex >= 0) {
                return solidColorTextureIndex;
            }
            solidColorTextureIndex = textures.size();
            textures.add(new TextureData(
                    GENERATED_ATLAS,
                    WHITE_SPRITE,
                    1,
                    1,
                    new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}));
            return solidColorTextureIndex;
        }

        private int tintColor(
                int tintIndex,
                ClientLevel level,
                BlockPos position,
                BlockState state) {
            if (tintIndex < 0) {
                return 0xFFFFFFFF;
            }
            BlockTintSource source = blockColors.getTintSource(state, tintIndex);
            if (source == null) {
                unsupportedTintQuadCount++;
                return 0xFFFFFFFF;
            }
            return ARGB.opaque(source.colorInWorld(state, level, position));
        }

        private int textureIndex(TextureAtlasSprite sprite) {
            TextureKey key = new TextureKey(sprite.atlasLocation(), sprite.contents().name());
            return textureIndices.computeIfAbsent(key, ignored -> {
                int width = sprite.contents().width();
                int height = sprite.contents().height();
                byte[] pixels = new byte[Math.multiplyExact(Math.multiplyExact(width, height), 4)];
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int argb = sprite.getPixelRGBA(0, x, y);
                        int offset = (y * width + x) * 4;
                        pixels[offset] = (byte) ARGB.red(argb);
                        pixels[offset + 1] = (byte) ARGB.green(argb);
                        pixels[offset + 2] = (byte) ARGB.blue(argb);
                        pixels[offset + 3] = (byte) ARGB.alpha(argb);
                    }
                }
                int index = textures.size();
                textures.add(new TextureData(
                        key.atlas, key.sprite, width, height, pixels));
                return index;
            });
        }

        private ClientRenderSnapshot build(
                int chunkX,
                int sectionY,
                int chunkZ,
                long resourceRevision,
                long distantHorizonsRevision) {
            return new ClientRenderSnapshot(
                    originX,
                    originY,
                    originZ,
                    Arrays.copyOf(vertexData, vertexCount * VERTEX_FLOAT_STRIDE),
                    Arrays.copyOf(vertexColors, vertexCount),
                    Arrays.copyOf(triangleData, triangleCount * TRIANGLE_INT_STRIDE),
                    materials.toArray(MaterialData[]::new),
                    textures.toArray(TextureData[]::new),
                    quadCount,
                    skippedTranslucentQuadCount,
                    unsupportedTintQuadCount,
                    skippedModelBlockCount,
                    distantHorizonsCellCount,
                    distantHorizonsQuadCount,
                    chunkX,
                    sectionY,
                    chunkZ,
                    resourceRevision,
                    distantHorizonsRevision);
        }

        private void ensureVertexCapacity(int required) {
            if (required <= vertexColors.length) {
                return;
            }
            int capacity = Math.max(required, Math.multiplyExact(vertexColors.length, 2));
            vertexColors = Arrays.copyOf(vertexColors, capacity);
            vertexData = Arrays.copyOf(vertexData, capacity * VERTEX_FLOAT_STRIDE);
        }

        private void ensureTriangleCapacity(int required) {
            int currentCapacity = triangleData.length / TRIANGLE_INT_STRIDE;
            if (required <= currentCapacity) {
                return;
            }
            int capacity = Math.max(required, Math.multiplyExact(currentCapacity, 2));
            triangleData = Arrays.copyOf(triangleData, capacity * TRIANGLE_INT_STRIDE);
        }
    }

    private static float inverseLerp(float start, float end, float value) {
        float range = end - start;
        return Math.abs(range) <= 1.0e-8F ? 0.0F : (value - start) / range;
    }

    private static int packRgba(int argb) {
        return ARGB.red(argb)
                | ARGB.green(argb) << 8
                | ARGB.blue(argb) << 16
                | ARGB.alpha(argb) << 24;
    }

    private record TextureKey(Identifier atlas, Identifier sprite) {
    }

    private record MaterialKey(int textureIndex, int flags, int emissionLevel) {
    }
}

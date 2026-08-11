package dev.cyclesrenderer.scene;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;

public record ClientVoxelSnapshot(
        int originX,
        int originY,
        int originZ,
        int sizeX,
        int sizeY,
        int sizeZ,
        int[] packedVoxels,
        int solidVoxelCount,
        int regionChunkX,
        int regionSectionY,
        int regionChunkZ) {
    public static final int SNAPSHOT_SIZE_X = 64;
    public static final int SNAPSHOT_SIZE_Y = 32;
    public static final int SNAPSHOT_SIZE_Z = 64;

    private static final int FALLBACK_MAP_COLOR = 0x7F7F7F;

    public static ClientVoxelSnapshot capture(ClientLevel level, Vec3 cameraPosition) {
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

        int[] packedVoxels = new int[SNAPSHOT_SIZE_X * SNAPSHOT_SIZE_Y * SNAPSHOT_SIZE_Z];
        int solidVoxelCount = 0;
        BlockPos.MutableBlockPos blockPosition = new BlockPos.MutableBlockPos();
        for (int localY = 0; localY < SNAPSHOT_SIZE_Y; localY++) {
            int worldY = originY + localY;
            for (int localZ = 0; localZ < SNAPSHOT_SIZE_Z; localZ++) {
                int worldZ = originZ + localZ;
                for (int localX = 0; localX < SNAPSHOT_SIZE_X; localX++) {
                    int worldX = originX + localX;
                    blockPosition.set(worldX, worldY, worldZ);
                    var blockState = level.getBlockState(blockPosition);
                    if (!blockState.isSolidRender()) {
                        continue;
                    }

                    MapColor mapColor = blockState.getMapColor(level, blockPosition);
                    int rgb = mapColor.col == 0 ? FALLBACK_MAP_COLOR : mapColor.col;
                    packedVoxels[index(localX, localY, localZ)] = packOpaqueRgba(rgb);
                    solidVoxelCount++;
                }
            }
        }

        return new ClientVoxelSnapshot(
                originX,
                originY,
                originZ,
                SNAPSHOT_SIZE_X,
                SNAPSHOT_SIZE_Y,
                SNAPSHOT_SIZE_Z,
                packedVoxels,
                solidVoxelCount,
                chunkX,
                sectionY,
                chunkZ);
    }

    public boolean isForCameraPosition(Vec3 cameraPosition) {
        return regionChunkX == SectionPos.blockToSectionCoord(Mth.floor(cameraPosition.x))
                && regionSectionY == SectionPos.blockToSectionCoord(Mth.floor(cameraPosition.y))
                && regionChunkZ == SectionPos.blockToSectionCoord(Mth.floor(cameraPosition.z));
    }

    public int voxelCount() {
        return packedVoxels.length;
    }

    private static int index(int localX, int localY, int localZ) {
        return localX + SNAPSHOT_SIZE_X * (localZ + SNAPSHOT_SIZE_Z * localY);
    }

    private static int packOpaqueRgba(int rgb) {
        int red = rgb >> 16 & 0xFF;
        int green = rgb >> 8 & 0xFF;
        int blue = rgb & 0xFF;
        return 0xFF000000 | blue << 16 | green << 8 | red;
    }
}

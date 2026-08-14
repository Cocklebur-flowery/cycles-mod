package dev.cyclesrenderer.scene;

import dev.cyclesrenderer.config.CyclesRenderSettings;
import dev.cyclesrenderer.nativebridge.NativeBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class SectionSceneManager {
    private static final int MAX_SECTION_UPLOADS_PER_FRAME = 24;
    private static final long SECTION_UPLOAD_BUDGET_NANOS = 4_000_000L;
    private static final long INITIAL_SCENE_QUIET_NANOS = 750_000_000L;
    private static final long UPDATE_SCENE_QUIET_NANOS = 100_000_000L;
    private static final int ORIGIN_GRANULARITY = 256;
    private static final int ORIGIN_REBASE_DISTANCE = 1024;

    private final Map<Long, CachedSection> sections = new HashMap<>();
    private final ConcurrentLinkedQueue<Long> unloadedChunks = new ConcurrentLinkedQueue<>();

    private ClientLevel level;
    private LabPbrResources.Discovery pbrDiscovery = LabPbrResources.empty();
    private LabPbrAtlasBuilder.Atlases pbrAtlases = LabPbrAtlasBuilder.empty();
    private long resourceRevision = Long.MIN_VALUE;
    private int pbrResourceFingerprint;
    private CyclesRenderSettings.CameraType cameraType;
    private int sceneOriginX;
    private int sceneOriginY;
    private int sceneOriginZ;
    private int lastCameraSectionX = Integer.MIN_VALUE;
    private int lastCameraSectionY = Integer.MIN_VALUE;
    private int lastCameraSectionZ = Integer.MIN_VALUE;
    private int lastViewDistance = Integer.MIN_VALUE;
    private boolean pendingCommit;
    private boolean hasCommittedScene;
    private long lastMutationNanos;
    private long uploadedSectionCount;
    private long removedSectionCount;
    private int activeVertexCount;
    private int activeTriangleCount;
    private long updateCount;
    private long lastUpdateMicros;
    private long emaUpdateMicros;
    private long maxUpdateMicros;
    private long lastUpsertMicros;
    private long emaUpsertMicros;
    private long maxUpsertMicros;
    private long lastRemoveMicros;
    private long emaRemoveMicros;
    private long maxRemoveMicros;
    private long lastCommitMicros;
    private long emaCommitMicros;
    private long maxCommitMicros;
    private int lastAcceptedSections;

    public UpdateResult update(
            Minecraft minecraft,
            ClientLevel currentLevel,
            Vec3 cameraPosition,
            long currentResourceRevision,
            CyclesRenderSettings settings) {
        long updateStart = System.nanoTime();
        int cameraSectionX = SectionPos.blockToSectionCoord(Mth.floor(cameraPosition.x));
        int cameraSectionY = SectionPos.blockToSectionCoord(Mth.floor(cameraPosition.y));
        int cameraSectionZ = SectionPos.blockToSectionCoord(Mth.floor(cameraPosition.z));
        int viewDistance = minecraft.options.getEffectiveRenderDistance();
        boolean reset = level != currentLevel
                || resourceRevision != currentResourceRevision
                || pbrResourceFingerprint != settings.pbrResourceFingerprint()
                || cameraType != settings.cameraType()
                || needsOriginRebase(cameraPosition);
        if (reset) {
            resetScene(
                    minecraft,
                    currentLevel,
                    cameraPosition,
                    currentResourceRevision,
                    settings);
            cameraSectionX = SectionPos.blockToSectionCoord(Mth.floor(cameraPosition.x));
            cameraSectionY = SectionPos.blockToSectionCoord(Mth.floor(cameraPosition.y));
            cameraSectionZ = SectionPos.blockToSectionCoord(Mth.floor(cameraPosition.z));
            viewDistance = minecraft.options.getEffectiveRenderDistance();
        }

        boolean rangeChanged = cameraSectionX != lastCameraSectionX
                || cameraSectionY != lastCameraSectionY
                || cameraSectionZ != lastCameraSectionZ
                || viewDistance != lastViewDistance;
        if (rangeChanged) {
            evictOutsideVanillaRange(
                    currentLevel,
                    cameraSectionX,
                    cameraSectionY,
                    cameraSectionZ,
                    viewDistance);
            lastCameraSectionX = cameraSectionX;
            lastCameraSectionY = cameraSectionY;
            lastCameraSectionZ = cameraSectionZ;
            lastViewDistance = viewDistance;
        }
        processChunkUnloads();

        int accepted = drainCompiledSections(
                currentLevel,
                cameraSectionX,
                cameraSectionY,
                cameraSectionZ,
                viewDistance);
        long now = System.nanoTime();
        boolean committed = false;
        long quietInterval = hasCommittedScene
                ? UPDATE_SCENE_QUIET_NANOS
                : INITIAL_SCENE_QUIET_NANOS;
        if (pendingCommit && now - lastMutationNanos >= quietInterval) {
            commitNative();
            pendingCommit = false;
            hasCommittedScene = true;
            committed = true;
        }

        lastAcceptedSections = accepted;
        recordUpdate(System.nanoTime() - updateStart);
        return new UpdateResult(
                sections.size(),
                activeVertexCount,
                activeTriangleCount,
                accepted,
                committed,
                uploadedSectionCount,
                removedSectionCount,
                viewDistance,
                reset);
    }

    public void onChunkUnload(ClientLevel unloadingLevel, ChunkPos chunkPos) {
        if (unloadingLevel == level) {
            unloadedChunks.add(chunkPos.pack());
        }
    }

    public void reset() {
        level = null;
        resourceRevision = Long.MIN_VALUE;
        pbrResourceFingerprint = 0;
        cameraType = null;
        pbrDiscovery = LabPbrResources.empty();
        pbrAtlases = LabPbrAtlasBuilder.empty();
        sections.clear();
        unloadedChunks.clear();
        lastCameraSectionX = Integer.MIN_VALUE;
        lastCameraSectionY = Integer.MIN_VALUE;
        lastCameraSectionZ = Integer.MIN_VALUE;
        lastViewDistance = Integer.MIN_VALUE;
        pendingCommit = false;
        hasCommittedScene = false;
        uploadedSectionCount = 0L;
        removedSectionCount = 0L;
        activeVertexCount = 0;
        activeTriangleCount = 0;
        updateCount = 0L;
        lastUpdateMicros = 0L;
        emaUpdateMicros = 0L;
        maxUpdateMicros = 0L;
        lastUpsertMicros = 0L;
        emaUpsertMicros = 0L;
        maxUpsertMicros = 0L;
        lastRemoveMicros = 0L;
        emaRemoveMicros = 0L;
        maxRemoveMicros = 0L;
        lastCommitMicros = 0L;
        emaCommitMicros = 0L;
        maxCommitMicros = 0L;
        lastAcceptedSections = 0;
        SectionGeometryCollector.setActiveLevel(null);
    }

    private void resetScene(
            Minecraft minecraft,
            ClientLevel currentLevel,
            Vec3 cameraPosition,
            long currentResourceRevision,
            CyclesRenderSettings settings) {
        sections.clear();
        unloadedChunks.clear();
        SectionGeometryCollector.setActiveLevel(currentLevel);
        sceneOriginX = snapOrigin(Mth.floor(cameraPosition.x));
        sceneOriginY = snapOrigin(Mth.floor(cameraPosition.y));
        sceneOriginZ = snapOrigin(Mth.floor(cameraPosition.z));
        SectionGeometrySnapshot.SceneResources resources = createResources(
                minecraft, sceneOriginX, sceneOriginY, sceneOriginZ, settings);
        NativeBridge.resetScene(resources);
        level = currentLevel;
        resourceRevision = currentResourceRevision;
        pbrResourceFingerprint = settings.pbrResourceFingerprint();
        cameraType = settings.cameraType();
        lastCameraSectionX = Integer.MIN_VALUE;
        lastCameraSectionY = Integer.MIN_VALUE;
        lastCameraSectionZ = Integer.MIN_VALUE;
        lastViewDistance = Integer.MIN_VALUE;
        pendingCommit = false;
        hasCommittedScene = false;
        lastMutationNanos = System.nanoTime();
        uploadedSectionCount = 0L;
        removedSectionCount = 0L;
        activeVertexCount = 0;
        activeTriangleCount = 0;
        minecraft.levelExtractor.allChanged();
    }

    private boolean needsOriginRebase(Vec3 cameraPosition) {
        if (level == null) {
            return true;
        }
        return Math.abs(cameraPosition.x - sceneOriginX) > ORIGIN_REBASE_DISTANCE
                || Math.abs(cameraPosition.y - sceneOriginY) > ORIGIN_REBASE_DISTANCE
                || Math.abs(cameraPosition.z - sceneOriginZ) > ORIGIN_REBASE_DISTANCE;
    }

    private int drainCompiledSections(
            ClientLevel currentLevel,
            int cameraSectionX,
            int cameraSectionY,
            int cameraSectionZ,
            int viewDistance) {
        long deadline = System.nanoTime() + SECTION_UPLOAD_BUDGET_NANOS;
        int accepted = 0;
        while (accepted < MAX_SECTION_UPLOADS_PER_FRAME && System.nanoTime() < deadline) {
            SectionGeometrySnapshot snapshot = SectionGeometryCollector.poll();
            if (snapshot == null) {
                break;
            }
            int sectionX = SectionPos.x(snapshot.sectionNode());
            int sectionY = SectionPos.y(snapshot.sectionNode());
            int sectionZ = SectionPos.z(snapshot.sectionNode());
            if (!isInsideVanillaRange(
                    currentLevel,
                    cameraSectionX,
                    cameraSectionY,
                    cameraSectionZ,
                    viewDistance,
                    sectionX,
                    sectionY,
                    sectionZ,
                    cameraType == CyclesRenderSettings.CameraType.PANORAMA)) {
                continue;
            }
            CachedSection previous = sections.get(snapshot.sectionNode());
            if (previous != null && previous.sequence() >= snapshot.sequence()) {
                continue;
            }

            if (snapshot.empty()) {
                CachedSection removed = sections.remove(snapshot.sectionNode());
                if (removed != null) {
                    subtractCounts(removed);
                    removeNative(snapshot.sectionNode());
                    removedSectionCount++;
                    markMutation();
                }
            } else {
                sections.put(snapshot.sectionNode(), new CachedSection(
                        snapshot.sequence(), snapshot.vertexCount(), snapshot.triangleCount()));
                if (previous != null) {
                    subtractCounts(previous);
                }
                activeVertexCount = Math.addExact(activeVertexCount, snapshot.vertexCount());
                activeTriangleCount = Math.addExact(activeTriangleCount, snapshot.triangleCount());
                upsertNative(snapshot);
                uploadedSectionCount++;
                markMutation();
            }
            accepted++;
        }
        return accepted;
    }

    private void evictOutsideVanillaRange(
            ClientLevel currentLevel,
            int cameraSectionX,
            int cameraSectionY,
            int cameraSectionZ,
            int viewDistance) {
        Iterator<Map.Entry<Long, CachedSection>> iterator = sections.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, CachedSection> entry = iterator.next();
            long sectionNode = entry.getKey();
            if (isInsideVanillaRange(
                    currentLevel,
                    cameraSectionX,
                    cameraSectionY,
                    cameraSectionZ,
                    viewDistance,
                    SectionPos.x(sectionNode),
                    SectionPos.y(sectionNode),
                    SectionPos.z(sectionNode),
                    cameraType == CyclesRenderSettings.CameraType.PANORAMA)) {
                continue;
            }
            iterator.remove();
            subtractCounts(entry.getValue());
            removeNative(sectionNode);
            removedSectionCount++;
            markMutation();
        }
    }

    private void processChunkUnloads() {
        Long packedChunk;
        while ((packedChunk = unloadedChunks.poll()) != null) {
            int chunkX = ChunkPos.getX(packedChunk);
            int chunkZ = ChunkPos.getZ(packedChunk);
            Iterator<Map.Entry<Long, CachedSection>> iterator = sections.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Long, CachedSection> entry = iterator.next();
                long sectionNode = entry.getKey();
                if (SectionPos.x(sectionNode) == chunkX && SectionPos.z(sectionNode) == chunkZ) {
                    iterator.remove();
                    subtractCounts(entry.getValue());
                    removeNative(sectionNode);
                    removedSectionCount++;
                    markMutation();
                }
            }
        }
    }

    private void markMutation() {
        pendingCommit = true;
        lastMutationNanos = System.nanoTime();
    }

    public Telemetry telemetry() {
        return new Telemetry(
                updateCount,
                lastUpdateMicros,
                emaUpdateMicros,
                maxUpdateMicros,
                lastUpsertMicros,
                emaUpsertMicros,
                maxUpsertMicros,
                lastRemoveMicros,
                emaRemoveMicros,
                maxRemoveMicros,
                lastCommitMicros,
                emaCommitMicros,
                maxCommitMicros,
                lastAcceptedSections,
                pendingCommit);
    }

    public LabPbrResources.Discovery pbrDiscovery() {
        return pbrDiscovery;
    }

    public LabPbrAtlasBuilder.Atlases pbrAtlases() {
        return pbrAtlases;
    }

    private void upsertNative(SectionGeometrySnapshot snapshot) {
        long start = System.nanoTime();
        NativeBridge.upsertSection(snapshot);
        long micros = nanosToMicros(System.nanoTime() - start);
        lastUpsertMicros = micros;
        emaUpsertMicros = updateEma(emaUpsertMicros, micros);
        maxUpsertMicros = Math.max(maxUpsertMicros, micros);
    }

    private void removeNative(long sectionNode) {
        long start = System.nanoTime();
        NativeBridge.removeSection(sectionNode);
        long micros = nanosToMicros(System.nanoTime() - start);
        lastRemoveMicros = micros;
        emaRemoveMicros = updateEma(emaRemoveMicros, micros);
        maxRemoveMicros = Math.max(maxRemoveMicros, micros);
    }

    private void commitNative() {
        long start = System.nanoTime();
        NativeBridge.commitScene();
        long micros = nanosToMicros(System.nanoTime() - start);
        lastCommitMicros = micros;
        emaCommitMicros = updateEma(emaCommitMicros, micros);
        maxCommitMicros = Math.max(maxCommitMicros, micros);
    }

    private void recordUpdate(long elapsedNanos) {
        long micros = nanosToMicros(elapsedNanos);
        lastUpdateMicros = micros;
        emaUpdateMicros = updateEma(emaUpdateMicros, micros);
        maxUpdateMicros = Math.max(maxUpdateMicros, micros);
        updateCount++;
    }

    private static long nanosToMicros(long nanos) {
        return Math.max(0L, (nanos + 999L) / 1_000L);
    }

    private static long updateEma(long previous, long value) {
        return previous == 0L ? value : (previous * 7L + value) / 8L;
    }

    private void subtractCounts(CachedSection snapshot) {
        activeVertexCount = Math.subtractExact(activeVertexCount, snapshot.vertexCount());
        activeTriangleCount = Math.subtractExact(activeTriangleCount, snapshot.triangleCount());
    }

    private static boolean isInsideVanillaRange(
            ClientLevel level,
            int cameraSectionX,
            int cameraSectionY,
            int cameraSectionZ,
            int viewDistance,
            int sectionX,
            int sectionY,
            int sectionZ,
            boolean fullHeight) {
        return sectionY >= level.getMinSectionY()
                && sectionY <= level.getMaxSectionY()
                && (fullHeight || Math.abs(sectionY - cameraSectionY) <= viewDistance)
                && ChunkTrackingView.isInViewDistance(
                        cameraSectionX,
                        cameraSectionZ,
                        viewDistance,
                        sectionX,
                        sectionZ);
    }

    private static int snapOrigin(int blockCoordinate) {
        return Math.floorDiv(blockCoordinate, ORIGIN_GRANULARITY) * ORIGIN_GRANULARITY;
    }

    private SectionGeometrySnapshot.SceneResources createResources(
            Minecraft minecraft,
            int originX,
            int originY,
            int originZ,
            CyclesRenderSettings settings) {
        Object texture = minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        if (!(texture instanceof TextureAtlas atlas)) {
            throw new IllegalStateException("Minecraft block texture is not a TextureAtlas");
        }
        Map<Identifier, TextureAtlasSprite> sprites = atlas.getTextures();
        if (sprites.isEmpty()) {
            throw new IllegalStateException("Minecraft block texture atlas has no sprites");
        }
        if (settings.pbrMode() == CyclesRenderSettings.PbrMode.OFF) {
            pbrDiscovery = LabPbrResources.empty();
        } else {
            pbrDiscovery = LabPbrResources.discover(
                    minecraft.getResourceManager(),
                    sprites.values(),
                    settings.pbrMode() == CyclesRenderSettings.PbrMode.LAB_PBR_1_3);
        }

        int atlasWidth = 0;
        int atlasHeight = 0;
        for (TextureAtlasSprite sprite : sprites.values()) {
            float widthFraction = sprite.getU1() - sprite.getU0();
            float heightFraction = sprite.getV1() - sprite.getV0();
            if (widthFraction > 0.0F) {
                atlasWidth = Math.max(
                        atlasWidth,
                        Math.round(sprite.contents().width() / widthFraction));
            }
            if (heightFraction > 0.0F) {
                atlasHeight = Math.max(
                        atlasHeight,
                        Math.round(sprite.contents().height() / heightFraction));
            }
        }
        if (atlasWidth <= 0 || atlasHeight <= 0) {
            throw new IllegalStateException("invalid Minecraft block atlas dimensions");
        }

        pbrAtlases = LabPbrAtlasBuilder.build(
                minecraft.getResourceManager(),
                sprites,
                pbrDiscovery,
                atlasWidth,
                atlasHeight,
                settings.pbrFallbackRoughness(),
                settings.pbrFallbackF0());

        byte[] pixels = new byte[Math.multiplyExact(Math.multiplyExact(atlasWidth, atlasHeight), 4)];
        for (TextureAtlasSprite sprite : sprites.values()) {
            int startX = Math.round(sprite.getU0() * atlasWidth);
            int startY = Math.round(sprite.getV0() * atlasHeight);
            int width = sprite.contents().width();
            int height = sprite.contents().height();
            int imageFrame = LabPbrAnimationFrames.currentImageFrame(sprite);
            for (int y = 0; y < height; y++) {
                int targetY = startY + y;
                if (targetY < 0 || targetY >= atlasHeight) {
                    continue;
                }
                for (int x = 0; x < width; x++) {
                    int targetX = startX + x;
                    if (targetX < 0 || targetX >= atlasWidth) {
                        continue;
                    }
                    int argb = sprite.getPixelRGBA(imageFrame, x, y);
                    int output = (targetY * atlasWidth + targetX) * 4;
                    pixels[output] = (byte) ARGB.red(argb);
                    pixels[output + 1] = (byte) ARGB.green(argb);
                    pixels[output + 2] = (byte) ARGB.blue(argb);
                    pixels[output + 3] = (byte) ARGB.alpha(argb);
                }
            }
        }

        boolean hasPbrAtlases = pbrAtlases.width() == atlasWidth
                && pbrAtlases.height() == atlasHeight;
        int normalTextureIndex = hasPbrAtlases
                ? 1
                : SectionGeometrySnapshot.TEXTURE_INDEX_INVALID;
        int materialTextureIndex = hasPbrAtlases
                ? 2
                : SectionGeometrySnapshot.TEXTURE_INDEX_INVALID;
        int auxiliaryTextureIndex = hasPbrAtlases
                ? 3
                : SectionGeometrySnapshot.TEXTURE_INDEX_INVALID;
        int pbrFormat = hasPbrAtlases
                ? SectionGeometrySnapshot.PBR_FORMAT_LAB_1_3
                : SectionGeometrySnapshot.PBR_FORMAT_NONE;
        SectionGeometrySnapshot.MaterialData[] materials = {
                new SectionGeometrySnapshot.MaterialData(
                        0, 0, 0.0F, 0.0F,
                        normalTextureIndex, materialTextureIndex, pbrFormat,
                        auxiliaryTextureIndex),
                new SectionGeometrySnapshot.MaterialData(
                        0,
                        SectionGeometrySnapshot.MATERIAL_FLAG_CUTOUT,
                        0.0F,
                        0.5F,
                        normalTextureIndex,
                        materialTextureIndex,
                        pbrFormat,
                        auxiliaryTextureIndex),
                new SectionGeometrySnapshot.MaterialData(
                        0,
                        SectionGeometrySnapshot.MATERIAL_FLAG_BLEND,
                        0.0F,
                        0.0F,
                        normalTextureIndex,
                        materialTextureIndex,
                        pbrFormat,
                        auxiliaryTextureIndex),
                new SectionGeometrySnapshot.MaterialData(
                        0,
                        SectionGeometrySnapshot.MATERIAL_FLAG_TRANSMISSION,
                        0.0F,
                        0.0F,
                        normalTextureIndex,
                        materialTextureIndex,
                        pbrFormat,
                        auxiliaryTextureIndex),
                new SectionGeometrySnapshot.MaterialData(
                        0,
                        SectionGeometrySnapshot.MATERIAL_FLAG_TRANSMISSION
                                | SectionGeometrySnapshot.MATERIAL_FLAG_WATER,
                        0.0F,
                        0.0F,
                        normalTextureIndex,
                        materialTextureIndex,
                        pbrFormat,
                        auxiliaryTextureIndex),
                new SectionGeometrySnapshot.MaterialData(
                        0,
                        SectionGeometrySnapshot.MATERIAL_FLAG_CUTOUT
                                | SectionGeometrySnapshot.MATERIAL_FLAG_FOLIAGE,
                        0.0F,
                        0.5F,
                        normalTextureIndex,
                        materialTextureIndex,
                        pbrFormat,
                        auxiliaryTextureIndex)
        };
        SectionGeometrySnapshot.TextureData[] textures = hasPbrAtlases
                ? new SectionGeometrySnapshot.TextureData[] {
                    new SectionGeometrySnapshot.TextureData(
                            TextureAtlas.LOCATION_BLOCKS,
                            atlasWidth,
                            atlasHeight,
                            pixels,
                            SectionGeometrySnapshot.TEXTURE_ROLE_COLOR_SRGB),
                    new SectionGeometrySnapshot.TextureData(
                            Identifier.fromNamespaceAndPath(
                                    "cyclesrenderer", "blocks_normal"),
                            atlasWidth,
                            atlasHeight,
                            pbrAtlases.normalPixels(),
                            SectionGeometrySnapshot.TEXTURE_ROLE_DATA_LINEAR),
                    new SectionGeometrySnapshot.TextureData(
                            Identifier.fromNamespaceAndPath(
                                    "cyclesrenderer", "blocks_material"),
                            atlasWidth,
                            atlasHeight,
                            pbrAtlases.materialPixels(),
                            SectionGeometrySnapshot.TEXTURE_ROLE_DATA_LINEAR),
                    new SectionGeometrySnapshot.TextureData(
                            Identifier.fromNamespaceAndPath(
                                    "cyclesrenderer", "blocks_labpbr_auxiliary"),
                            atlasWidth,
                            atlasHeight,
                            pbrAtlases.auxiliaryPixels(),
                            SectionGeometrySnapshot.TEXTURE_ROLE_DATA_LINEAR)
                }
                : new SectionGeometrySnapshot.TextureData[] {
                    new SectionGeometrySnapshot.TextureData(
                            TextureAtlas.LOCATION_BLOCKS,
                            atlasWidth,
                            atlasHeight,
                            pixels,
                            SectionGeometrySnapshot.TEXTURE_ROLE_COLOR_SRGB)
                };
        return new SectionGeometrySnapshot.SceneResources(
                originX, originY, originZ, materials, textures);
    }

    public record UpdateResult(
            int activeSections,
            int vertices,
            int triangles,
            int acceptedSections,
            boolean committed,
            long uploadedSections,
            long removedSections,
            int viewDistance,
            boolean reset) {
    }

    public record Telemetry(
            long updateCount,
            long lastUpdateMicros,
            long emaUpdateMicros,
            long maxUpdateMicros,
            long lastUpsertMicros,
            long emaUpsertMicros,
            long maxUpsertMicros,
            long lastRemoveMicros,
            long emaRemoveMicros,
            long maxRemoveMicros,
            long lastCommitMicros,
            long emaCommitMicros,
            long maxCommitMicros,
            int lastAcceptedSections,
            boolean pendingCommit) {
    }

    private record CachedSection(long sequence, int vertexCount, int triangleCount) {
    }
}

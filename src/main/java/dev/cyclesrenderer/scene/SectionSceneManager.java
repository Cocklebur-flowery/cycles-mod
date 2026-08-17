package dev.cyclesrenderer.scene;

import dev.cyclesrenderer.config.CyclesRenderSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.SectionPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
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
    private final NativeSceneUploadQueue uploadQueue = new NativeSceneUploadQueue();

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
    private boolean rangeEvictionPending;
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
    private int lastAcceptedSections;
    private SectionGeometrySnapshot deferredSnapshot;

    public UpdateResult update(
            Minecraft minecraft,
            ClientLevel currentLevel,
            Vec3 cameraPosition,
            long currentResourceRevision,
            CyclesRenderSettings settings) {
        long updateStart = System.nanoTime();
        uploadQueue.throwIfFailed();
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
            lastCameraSectionX = cameraSectionX;
            lastCameraSectionY = cameraSectionY;
            lastCameraSectionZ = cameraSectionZ;
            lastViewDistance = viewDistance;
            rangeEvictionPending = true;
        }
        if (rangeEvictionPending) {
            rangeEvictionPending = !evictOutsideVanillaRange(
                    currentLevel,
                    cameraSectionX,
                    cameraSectionY,
                    cameraSectionZ,
                    viewDistance);
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
            if (uploadQueue.enqueueCommit()) {
                pendingCommit = false;
                hasCommittedScene = true;
                committed = true;
            }
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
        uploadQueue.reset();
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
        rangeEvictionPending = false;
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
        lastAcceptedSections = 0;
        deferredSnapshot = null;
        SectionGeometryCollector.setActiveLevel(null);
    }

    public void close() {
        uploadQueue.close();
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
        uploadQueue.resetNativeScene(resources);
        level = currentLevel;
        resourceRevision = currentResourceRevision;
        pbrResourceFingerprint = settings.pbrResourceFingerprint();
        cameraType = settings.cameraType();
        lastCameraSectionX = Integer.MIN_VALUE;
        lastCameraSectionY = Integer.MIN_VALUE;
        lastCameraSectionZ = Integer.MIN_VALUE;
        lastViewDistance = Integer.MIN_VALUE;
        rangeEvictionPending = false;
        pendingCommit = false;
        hasCommittedScene = false;
        lastMutationNanos = System.nanoTime();
        uploadedSectionCount = 0L;
        removedSectionCount = 0L;
        activeVertexCount = 0;
        activeTriangleCount = 0;
        deferredSnapshot = null;
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
            SectionGeometrySnapshot snapshot = deferredSnapshot;
            deferredSnapshot = null;
            if (snapshot == null) {
                snapshot = SectionGeometryCollector.poll();
            }
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
                CachedSection removed = sections.get(snapshot.sectionNode());
                if (removed != null) {
                    if (!uploadQueue.enqueueRemove(snapshot.sectionNode())) {
                        deferredSnapshot = snapshot;
                        break;
                    }
                    sections.remove(snapshot.sectionNode());
                    subtractCounts(removed);
                    removedSectionCount++;
                    markMutation();
                }
            } else {
                if (!uploadQueue.enqueueUpsert(snapshot)) {
                    deferredSnapshot = snapshot;
                    break;
                }
                sections.put(snapshot.sectionNode(), new CachedSection(
                        snapshot.sequence(), snapshot.vertexCount(), snapshot.triangleCount()));
                if (previous != null) {
                    subtractCounts(previous);
                }
                activeVertexCount = Math.addExact(activeVertexCount, snapshot.vertexCount());
                activeTriangleCount = Math.addExact(activeTriangleCount, snapshot.triangleCount());
                uploadedSectionCount++;
                markMutation();
            }
            accepted++;
        }
        return accepted;
    }

    private boolean evictOutsideVanillaRange(
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
            if (!uploadQueue.enqueueRemove(sectionNode)) {
                return false;
            }
            iterator.remove();
            subtractCounts(entry.getValue());
            removedSectionCount++;
            markMutation();
        }
        return true;
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
                    if (!uploadQueue.enqueueRemove(sectionNode)) {
                        unloadedChunks.add(packedChunk);
                        return;
                    }
                    iterator.remove();
                    subtractCounts(entry.getValue());
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
        NativeSceneUploadQueue.Metrics upload = uploadQueue.metrics();
        return new Telemetry(
                updateCount,
                lastUpdateMicros,
                emaUpdateMicros,
                maxUpdateMicros,
                upload.lastUpsertMicros(),
                upload.emaUpsertMicros(),
                upload.maxUpsertMicros(),
                upload.lastRemoveMicros(),
                upload.emaRemoveMicros(),
                upload.maxRemoveMicros(),
                upload.lastCommitMicros(),
                upload.emaCommitMicros(),
                upload.maxCommitMicros(),
                lastAcceptedSections,
                pendingCommit);
    }

    public LabPbrResources.Discovery pbrDiscovery() {
        return pbrDiscovery;
    }

    public LabPbrAtlasBuilder.Atlases pbrAtlases() {
        return pbrAtlases;
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
        TextureAtlas atlas = minecraft.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
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

        List<SectionSceneResourceBuilder.SpriteInput> spriteInputs =
                new ArrayList<>(sprites.size());
        for (TextureAtlasSprite sprite : sprites.values()) {
            int imageFrame = LabPbrAnimationFrames.currentImageFrame(sprite);
            spriteInputs.add(new SectionSceneResourceBuilder.SpriteInput(
                    sprite.getU0(),
                    sprite.getV0(),
                    sprite.getU1(),
                    sprite.getV1(),
                    sprite.contents().width(),
                    sprite.contents().height(),
                    (x, y) -> sprite.getPixelRGBA(imageFrame, x, y)));
        }
        SectionSceneResourceBuilder.AtlasLayout atlasLayout =
                SectionSceneResourceBuilder.measure(spriteInputs);

        pbrAtlases = LabPbrAtlasBuilder.build(
                minecraft.getResourceManager(),
                sprites,
                pbrDiscovery,
                atlasLayout.width(),
                atlasLayout.height(),
                settings.pbrFallbackRoughness(),
                settings.pbrFallbackF0());
        byte[] pixels = SectionSceneResourceBuilder.copyRgba(atlasLayout, spriteInputs);
        return SectionSceneResourceBuilder.build(
                originX,
                originY,
                originZ,
                atlas.location(),
                atlasLayout,
                pixels,
                pbrAtlases);
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

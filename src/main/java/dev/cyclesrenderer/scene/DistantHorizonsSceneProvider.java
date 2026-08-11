package dev.cyclesrenderer.scene;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Optional, reflection-only adapter for Distant Horizons API 7.
 *
 * <p>The provider never exposes DH types to the rest of the mod. Terrain database reads happen on
 * a daemon worker and publish an immutable height-field snapshot when complete.</p>
 */
public final class DistantHorizonsSceneProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(DistantHorizonsSceneProvider.class);
    private static final String DH_MOD_ID = "distanthorizons";
    private static final int SUPPORTED_API_MAJOR = 7;
    private static final int LOD_CELL_SIZE = 8;
    private static final int LOD_RADIUS = 256;
    private static final int CENTER_GRANULARITY = 64;
    private static final long RETRY_DELAY_NANOS = 10_000_000_000L;

    private static final Object LOCK = new Object();
    private static final ExecutorService CAPTURE_EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "CyclesRenderer-DistantHorizons");
        thread.setDaemon(true);
        return thread;
    });

    private static ApiAccess apiAccess;
    private static boolean apiResolutionAttempted;
    private static ClientLevel activeLevel;
    private static RuntimeAccess activeRuntime;
    private static Future<?> captureTask;
    private static long generation;
    private static long revision;
    private static int lastAttemptCenterX = Integer.MIN_VALUE;
    private static int lastAttemptCenterZ = Integer.MIN_VALUE;
    private static long retryAfterNanos;
    private static SceneState visibleState = new SceneState(0L, null, "DH not initialized");

    private DistantHorizonsSceneProvider() {
    }

    public static SceneState update(ClientLevel level, Vec3 cameraPosition) {
        synchronized (LOCK) {
            if (!ModList.get().isLoaded(DH_MOD_ID)) {
                return setStatus("DH not installed");
            }

            if (!apiResolutionAttempted) {
                apiResolutionAttempted = true;
                try {
                    apiAccess = ApiAccess.create();
                    LOGGER.info(
                            "Distant Horizons API detected: api={}.{}.{}, mod={}",
                            apiAccess.apiMajor,
                            apiAccess.apiMinor,
                            apiAccess.apiPatch,
                            apiAccess.modVersion);
                } catch (ReflectiveOperationException | RuntimeException error) {
                    LOGGER.warn("Distant Horizons API is unavailable; far LOD disabled", error);
                    return setStatus("DH API unavailable");
                }
            }
            if (apiAccess == null) {
                return visibleState;
            }

            RuntimeAccess runtime = activeLevel == level ? activeRuntime : null;
            if (runtime == null) {
                try {
                    runtime = apiAccess.resolveRuntime(level);
                } catch (ReflectiveOperationException | RuntimeException error) {
                    LOGGER.warn("Distant Horizons runtime lookup failed; far LOD disabled", error);
                    return setStatus("DH runtime lookup failed");
                }
            }
            if (runtime == null) {
                return setStatus("waiting for DH world data");
            }

            if (activeLevel != level) {
                generation++;
                activeLevel = level;
                activeRuntime = runtime;
                lastAttemptCenterX = Integer.MIN_VALUE;
                lastAttemptCenterZ = Integer.MIN_VALUE;
                retryAfterNanos = 0L;
                revision++;
                visibleState = new SceneState(revision, null, "DH world changed");
            } else if (activeRuntime == null) {
                activeRuntime = runtime;
            }

            int centerX = Math.floorDiv(Mth.floor(cameraPosition.x), CENTER_GRANULARITY)
                    * CENTER_GRANULARITY;
            int centerZ = Math.floorDiv(Mth.floor(cameraPosition.z), CENTER_GRANULARITY)
                    * CENTER_GRANULARITY;
            LodSnapshot current = visibleState.snapshot;
            if (current != null && current.centerX == centerX && current.centerZ == centerZ) {
                return setStatus("DH LOD ready");
            }
            if (captureTask != null && captureTask.isDone()) {
                captureTask = null;
            }
            if (captureTask != null) {
                return setStatus("capturing DH LOD");
            }
            if (lastAttemptCenterX == centerX
                    && lastAttemptCenterZ == centerZ
                    && System.nanoTime() < retryAfterNanos) {
                return visibleState;
            }

            lastAttemptCenterX = centerX;
            lastAttemptCenterZ = centerZ;
            long requestGeneration = generation;
            RuntimeAccess captureRuntime = runtime;
            captureTask = CAPTURE_EXECUTOR.submit(() -> captureAsync(
                    apiAccess,
                    captureRuntime,
                    level,
                    centerX,
                    centerZ,
                    requestGeneration));
            return setStatus("capturing DH LOD");
        }
    }

    public static void reset() {
        synchronized (LOCK) {
            generation++;
            activeLevel = null;
            activeRuntime = null;
            lastAttemptCenterX = Integer.MIN_VALUE;
            lastAttemptCenterZ = Integer.MIN_VALUE;
            retryAfterNanos = 0L;
            revision++;
            visibleState = new SceneState(revision, null, "DH provider reset");
            if (captureTask != null) {
                captureTask.cancel(false);
                captureTask = null;
            }
        }
    }

    private static void captureAsync(
            ApiAccess access,
            RuntimeAccess runtime,
            ClientLevel level,
            int centerX,
            int centerZ,
            long requestGeneration) {
        long startNanos = System.nanoTime();
        Object cache = null;
        try {
            cache = access.createSoftCache.invoke(runtime.terrainRepo);
            Map<Long, LodCell> cells = new HashMap<>();
            int queryCount = 0;
            int missingColumnCount = 0;
            int maximumSourceDetail = 0;
            long radiusSquared = (long) LOD_RADIUS * LOD_RADIUS;
            int minimumX = centerX - LOD_RADIUS;
            int minimumZ = centerZ - LOD_RADIUS;
            int maximumX = centerX + LOD_RADIUS;
            int maximumZ = centerZ + LOD_RADIUS;
            for (int minZ = minimumZ; minZ < maximumZ; minZ += LOD_CELL_SIZE) {
                for (int minX = minimumX; minX < maximumX; minX += LOD_CELL_SIZE) {
                    int sampleX = minX + LOD_CELL_SIZE / 2;
                    int sampleZ = minZ + LOD_CELL_SIZE / 2;
                    long dx = (long) sampleX - centerX;
                    long dz = (long) sampleZ - centerZ;
                    if (dx * dx + dz * dz > radiusSquared) {
                        continue;
                    }

                    queryCount++;
                    LodCell cell = access.readTopCell(
                            runtime, cache, level, minX, minZ, sampleX, sampleZ);
                    if (cell == null) {
                        missingColumnCount++;
                        continue;
                    }
                    cells.put(cellKey(minX, minZ), cell);
                    maximumSourceDetail = Math.max(maximumSourceDetail, cell.sourceDetailLevel);
                }
            }

            long captureMilliseconds = (System.nanoTime() - startNanos) / 1_000_000L;
            LodSnapshot snapshot = new LodSnapshot(
                    level,
                    centerX,
                    centerZ,
                    LOD_CELL_SIZE,
                    Map.copyOf(cells),
                    queryCount,
                    missingColumnCount,
                    maximumSourceDetail,
                    captureMilliseconds);
            synchronized (LOCK) {
                if (generation == requestGeneration && activeLevel == level) {
                    revision++;
                    visibleState = new SceneState(revision, snapshot, "DH LOD ready");
                    retryAfterNanos = 0L;
                    LOGGER.info(
                            "Distant Horizons LOD ready: center=({}, {}), cells={}, queries={}, "
                                    + "missing={}, sourceDetailMax={}, capture={} ms",
                            centerX,
                            centerZ,
                            cells.size(),
                            queryCount,
                            missingColumnCount,
                            maximumSourceDetail,
                            captureMilliseconds);
                }
            }
        } catch (Throwable error) {
            synchronized (LOCK) {
                if (generation == requestGeneration && activeLevel == level) {
                    retryAfterNanos = System.nanoTime() + RETRY_DELAY_NANOS;
                    activeRuntime = null;
                    visibleState = new SceneState(revision, visibleState.snapshot, "DH LOD capture failed");
                    LOGGER.warn("Distant Horizons terrain capture failed; keeping previous scene", rootCause(error));
                }
            }
        } finally {
            if (cache instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception error) {
                    LOGGER.debug("Failed to close Distant Horizons terrain cache", error);
                }
            }
            synchronized (LOCK) {
                if (generation == requestGeneration) {
                    captureTask = null;
                }
            }
        }
    }

    private static SceneState setStatus(String status) {
        if (visibleState.status.equals(status)) {
            return visibleState;
        }
        visibleState = new SceneState(visibleState.revision, visibleState.snapshot, status);
        return visibleState;
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            current = invocation.getCause();
        }
        return current;
    }

    private static long cellKey(int minX, int minZ) {
        return (long) minX << 32 ^ minZ & 0xFFFFFFFFL;
    }

    public record SceneState(long revision, LodSnapshot snapshot, String status) {
    }

    public record LodSnapshot(
            ClientLevel level,
            int centerX,
            int centerZ,
            int cellSize,
            Map<Long, LodCell> cells,
            int queryCount,
            int missingColumnCount,
            int maximumSourceDetail,
            long captureMilliseconds) {
        public LodCell cellAt(int minX, int minZ) {
            return cells.get(cellKey(minX, minZ));
        }
    }

    public record LodCell(
            int minX,
            int minZ,
            int bottomY,
            int topY,
            BlockState blockState,
            int blockLight,
            int skyLight,
            int sourceDetailLevel) {
    }

    private record RuntimeAccess(Object terrainRepo, Object levelWrapper) {
    }

    private static final class ApiAccess {
        private final int apiMajor;
        private final int apiMinor;
        private final int apiPatch;
        private final String modVersion;
        private final Field delayedTerrainRepo;
        private final Field delayedWorldProxy;
        private final Method worldLoaded;
        private final Method loadedLevelWrappers;
        private final Method levelWrappedObject;
        private final Method createSoftCache;
        private final Method getColumnData;
        private final Field resultSuccess;
        private final Field resultPayload;
        private final Field pointDetailLevel;
        private final Field pointBlockLight;
        private final Field pointSkyLight;
        private final Field pointBottomY;
        private final Field pointTopY;
        private final Field pointBlockState;
        private final Method blockIsAir;
        private final Method blockWrappedObject;

        private ApiAccess(
                int apiMajor,
                int apiMinor,
                int apiPatch,
                String modVersion,
                Field delayedTerrainRepo,
                Field delayedWorldProxy,
                Method worldLoaded,
                Method loadedLevelWrappers,
                Method levelWrappedObject,
                Method createSoftCache,
                Method getColumnData,
                Field resultSuccess,
                Field resultPayload,
                Field pointDetailLevel,
                Field pointBlockLight,
                Field pointSkyLight,
                Field pointBottomY,
                Field pointTopY,
                Field pointBlockState,
                Method blockIsAir,
                Method blockWrappedObject) {
            this.apiMajor = apiMajor;
            this.apiMinor = apiMinor;
            this.apiPatch = apiPatch;
            this.modVersion = modVersion;
            this.delayedTerrainRepo = delayedTerrainRepo;
            this.delayedWorldProxy = delayedWorldProxy;
            this.worldLoaded = worldLoaded;
            this.loadedLevelWrappers = loadedLevelWrappers;
            this.levelWrappedObject = levelWrappedObject;
            this.createSoftCache = createSoftCache;
            this.getColumnData = getColumnData;
            this.resultSuccess = resultSuccess;
            this.resultPayload = resultPayload;
            this.pointDetailLevel = pointDetailLevel;
            this.pointBlockLight = pointBlockLight;
            this.pointSkyLight = pointSkyLight;
            this.pointBottomY = pointBottomY;
            this.pointTopY = pointTopY;
            this.pointBlockState = pointBlockState;
            this.blockIsAir = blockIsAir;
            this.blockWrappedObject = blockWrappedObject;
        }

        private static ApiAccess create() throws ReflectiveOperationException {
            ClassLoader loader = DistantHorizonsSceneProvider.class.getClassLoader();
            Class<?> dhApi = Class.forName("com.seibel.distanthorizons.api.DhApi", true, loader);
            int major = (int) dhApi.getMethod("getApiMajorVersion").invoke(null);
            int minor = (int) dhApi.getMethod("getApiMinorVersion").invoke(null);
            int patch = (int) dhApi.getMethod("getApiPatchVersion").invoke(null);
            String version = (String) dhApi.getMethod("getModVersion").invoke(null);
            if (major != SUPPORTED_API_MAJOR) {
                throw new IllegalStateException(
                        "unsupported Distant Horizons API major " + major
                                + ", expected " + SUPPORTED_API_MAJOR);
            }

            Class<?> delayed = Class.forName(
                    "com.seibel.distanthorizons.api.DhApi$Delayed", true, loader);
            Class<?> terrainRepo = Class.forName(
                    "com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo", true, loader);
            Class<?> terrainCache = Class.forName(
                    "com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache", true, loader);
            Class<?> worldProxy = Class.forName(
                    "com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy", true, loader);
            Class<?> levelWrapper = Class.forName(
                    "com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper", true, loader);
            Class<?> blockWrapper = Class.forName(
                    "com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper", true, loader);
            Class<?> result = Class.forName(
                    "com.seibel.distanthorizons.api.objects.DhApiResult", true, loader);
            Class<?> point = Class.forName(
                    "com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint", true, loader);

            return new ApiAccess(
                    major,
                    minor,
                    patch,
                    version,
                    delayed.getField("terrainRepo"),
                    delayed.getField("worldProxy"),
                    worldProxy.getMethod("worldLoaded"),
                    worldProxy.getMethod("getAllLoadedLevelWrappers"),
                    levelWrapper.getMethod("getWrappedMcObject"),
                    terrainRepo.getMethod("createSoftCache"),
                    terrainRepo.getMethod(
                            "getColumnDataAtBlockPos",
                            levelWrapper,
                            int.class,
                            int.class,
                            terrainCache),
                    result.getField("success"),
                    result.getField("payload"),
                    point.getField("detailLevel"),
                    point.getField("blockLightLevel"),
                    point.getField("skyLightLevel"),
                    point.getField("bottomYBlockPos"),
                    point.getField("topYBlockPos"),
                    point.getField("blockStateWrapper"),
                    blockWrapper.getMethod("isAir"),
                    blockWrapper.getMethod("getWrappedMcObject"));
        }

        private RuntimeAccess resolveRuntime(ClientLevel level) throws ReflectiveOperationException {
            Object terrainRepo = delayedTerrainRepo.get(null);
            Object worldProxy = delayedWorldProxy.get(null);
            if (terrainRepo == null || worldProxy == null || !(boolean) worldLoaded.invoke(worldProxy)) {
                return null;
            }

            Object wrappers = loadedLevelWrappers.invoke(worldProxy);
            if (!(wrappers instanceof Iterable<?> iterable)) {
                return null;
            }
            for (Object wrapper : iterable) {
                if (levelWrappedObject.invoke(wrapper) == level) {
                    return new RuntimeAccess(terrainRepo, wrapper);
                }
            }
            return null;
        }

        private LodCell readTopCell(
                RuntimeAccess runtime,
                Object cache,
                ClientLevel level,
                int minX,
                int minZ,
                int sampleX,
                int sampleZ) throws ReflectiveOperationException {
            Object result = getColumnData.invoke(
                    runtime.terrainRepo, runtime.levelWrapper, sampleX, sampleZ, cache);
            if (!(boolean) resultSuccess.get(result)) {
                return null;
            }
            Object column = resultPayload.get(result);
            if (column == null) {
                return null;
            }

            int length = Array.getLength(column);
            for (int index = 0; index < length; index++) {
                Object point = Array.get(column, index);
                Object blockWrapper = pointBlockState.get(point);
                if (blockWrapper == null || (boolean) blockIsAir.invoke(blockWrapper)) {
                    continue;
                }
                Object wrappedState = blockWrappedObject.invoke(blockWrapper);
                if (!(wrappedState instanceof BlockState blockState)) {
                    continue;
                }

                int bottomY = level.getMinY() + pointBottomY.getInt(point);
                int topY = level.getMinY() + pointTopY.getInt(point);
                if (topY <= bottomY) {
                    continue;
                }
                return new LodCell(
                        minX,
                        minZ,
                        bottomY,
                        topY,
                        blockState,
                        Mth.clamp(pointBlockLight.getInt(point), 0, 15),
                        Mth.clamp(pointSkyLight.getInt(point), 0, 15),
                        Byte.toUnsignedInt(pointDetailLevel.getByte(point)));
            }
            return null;
        }
    }
}

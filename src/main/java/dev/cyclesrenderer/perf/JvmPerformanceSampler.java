package dev.cyclesrenderer.perf;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

/** Tracks JVM heap and collector deltas without allocating per frame. */
final class JvmPerformanceSampler {
    private static final List<GarbageCollectorMXBean> GARBAGE_COLLECTORS =
            ManagementFactory.getGarbageCollectorMXBeans();

    private long lastGcCount;
    private long lastGcTimeMillis;

    void reset() {
        lastGcCount = gcCount();
        lastGcTimeMillis = gcTimeMillis();
    }

    void sample(PerformanceSample target) {
        long count = gcCount();
        long timeMillis = gcTimeMillis();
        Runtime runtime = Runtime.getRuntime();
        target.setJvm(
                count - lastGcCount,
                timeMillis - lastGcTimeMillis,
                runtime.totalMemory() - runtime.freeMemory());
        lastGcCount = count;
        lastGcTimeMillis = timeMillis;
    }

    private static long gcCount() {
        long total = 0L;
        for (GarbageCollectorMXBean collector : GARBAGE_COLLECTORS) {
            total += Math.max(0L, collector.getCollectionCount());
        }
        return total;
    }

    private static long gcTimeMillis() {
        long total = 0L;
        for (GarbageCollectorMXBean collector : GARBAGE_COLLECTORS) {
            total += Math.max(0L, collector.getCollectionTime());
        }
        return total;
    }
}

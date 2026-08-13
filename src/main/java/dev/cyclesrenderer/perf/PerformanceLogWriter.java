package dev.cyclesrenderer.perf;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded asynchronous JSONL writer. Formatting and disk I/O never run on the render thread. */
final class PerformanceLogWriter {
    private static final Logger LOGGER = LoggerFactory.getLogger(PerformanceLogWriter.class);
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final Capture STOP = new Capture(-1L, -1L, List.of(), 0L);
    private static final String[] DEVICE_PHASE_NAMES = {
            "shader_background",
            "object",
            "mesh_geometry",
            "image_volume",
            "light",
            "integrator_film",
            "finalize",
            "unclassified"
    };

    private final ArrayBlockingQueue<Capture> queue = new ArrayBlockingQueue<>(8);
    private final AtomicLong droppedCaptures = new AtomicLong();
    private final Path logPath;
    private final boolean gpuQueriesEnabled;
    private final Thread worker;
    private volatile boolean accepting = true;

    PerformanceLogWriter(Path logDirectory, boolean gpuQueriesEnabled) {
        logPath = logDirectory.resolve(
                "cyclesrenderer-performance-" + FILE_TIME.format(LocalDateTime.now()) + ".jsonl");
        this.gpuQueriesEnabled = gpuQueriesEnabled;
        worker = Thread.ofPlatform()
                .name("cyclesrenderer-performance-log")
                .daemon(true)
                .unstarted(this::run);
        worker.start();
    }

    void enqueue(Capture capture) {
        if (!accepting || !queue.offer(capture)) {
            droppedCaptures.incrementAndGet();
        }
    }

    void finish(boolean wait) {
        accepting = false;
        while (!queue.offer(STOP)) {
            queue.poll();
            droppedCaptures.incrementAndGet();
        }
        if (wait && Thread.currentThread() != worker) {
            try {
                worker.join(2_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void run() {
        BufferedWriter writer = null;
        try {
            while (true) {
                Capture capture = queue.poll(30L, TimeUnit.SECONDS);
                if (capture == STOP) {
                    break;
                }
                if (capture == null) {
                    if (writer != null) {
                        writer.flush();
                    }
                    continue;
                }
                if (writer == null) {
                    Files.createDirectories(logPath.getParent());
                    writer = Files.newBufferedWriter(
                            logPath,
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE);
                    writeMetadata(writer);
                    LOGGER.warn("Cycles performance stall log: {}", logPath);
                }
                writeCapture(writer, capture);
                writer.flush();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException error) {
            LOGGER.error("Could not write Cycles performance log {}", logPath, error);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException error) {
                    LOGGER.warn("Could not close Cycles performance log {}", logPath, error);
                }
            }
        }
    }

    private void writeMetadata(BufferedWriter writer) throws IOException {
        writer.write("{\"type\":\"metadata\",\"schema\":3"
                + ",\"clock\":\"monotonic_ns\""
                + ",\"absolute_stall_us\":20000"
                + ",\"adaptive_min_us\":12000"
                + ",\"adaptive_multiplier\":2"
                + ",\"vulkan_gpu\":\"timestamp_query_nonblocking\""
                + ",\"gpu_queries_enabled\":" + gpuQueriesEnabled
                + ",\"gpu_result_grace_frames\":64"
                + ",\"cycles_gpu\":\"wall_clock_and_revision_context_only\""
                + ",\"cycles_device_phases\":\"progress_status_wall_clock\""
                + ",\"note\":\"CUDA/OptiX kernel time is not measured in schema 3\"}\n");
    }

    private void writeCapture(BufferedWriter writer, Capture capture) throws IOException {
        writer.write("{\"type\":\"capture\",\"first_trigger_frame\":"
                + capture.firstTriggerFrame
                + ",\"last_trigger_frame\":" + capture.lastTriggerFrame
                + ",\"frame_count\":" + capture.frames.size()
                + ",\"gpu_query_dropped_frames\":" + capture.gpuQueryDroppedFrames
                + ",\"log_queue_dropped_captures\":" + droppedCaptures.get() + "}\n");
        for (PerformanceSample.Snapshot frame : capture.frames) {
            writeFrame(writer, frame);
        }
    }

    private static void writeFrame(
            BufferedWriter writer,
            PerformanceSample.Snapshot frame) throws IOException {
        StringBuilder json = new StringBuilder(3_072);
        json.append("{\"type\":\"frame\",\"frame\":").append(frame.frameId())
                .append(",\"start_ns\":").append(frame.frameStartNanos())
                .append(",\"flip_interval_us\":");
        appendMicros(json, frame.flipIntervalNanos());
        json.append(",\"baseline_median_us\":");
        appendMicros(json, frame.adaptiveBaselineNanos());
        json.append(",\"flip_baseline_median_us\":");
        appendMicros(json, frame.flipBaselineNanos());
        json.append(",\"gc_count_delta\":").append(frame.gcCountDelta())
                .append(",\"gc_time_ms_delta\":").append(frame.gcTimeMillisDelta())
                .append(",\"heap_used_bytes\":").append(frame.heapUsedBytes())
                .append(",\"client_tick_id\":").append(frame.clientTickId())
                .append(",\"client_tick_us\":");
        appendMicros(json, frame.clientTickNanos());
        json
                .append(",\"trigger\":\"").append(triggerName(frame.triggerCode())).append('"')
                .append(",\"gpu_expected\":").append(frame.gpuExpected())
                .append(",\"gpu_complete\":").append(frame.gpuComplete());
        appendTimes(json, "cpu_us", PerformanceSample.CpuStage.values(), frame.cpuNanos());
        appendTimes(json, "gpu_us", PerformanceSample.GpuStage.values(), frame.gpuNanos());
        json.append(",\"context_frame\":").append(frame.contextFrameId());
        appendContext(json, frame.context());
        json.append("}\n");
        writer.write(json.toString());
    }

    private static <T extends Enum<T>> void appendTimes(
            StringBuilder json,
            String key,
            T[] stages,
            long[] values) {
        json.append(",\"").append(key).append("\":{");
        for (int index = 0; index < stages.length; index++) {
            if (index != 0) {
                json.append(',');
            }
            String name = stages[index] instanceof PerformanceSample.CpuStage cpu
                    ? cpu.jsonName()
                    : ((PerformanceSample.GpuStage) stages[index]).jsonName();
            json.append('"').append(name).append("\":");
            appendMicros(json, values[index]);
        }
        json.append('}');
    }

    private static void appendContext(StringBuilder json, PerformanceSample.Context value) {
        json.append(",\"context\":{")
                .append("\"capture_count\":").append(value.captureCount())
                .append(",\"capture_replaced\":").append(value.captureReplacedCount())
                .append(",\"capture_pending\":").append(value.capturePending())
                .append(",\"capture_last_us\":").append(value.captureLastMicros())
                .append(",\"scene_update_count\":").append(value.sceneUpdateCount())
                .append(",\"scene_update_last_us\":").append(value.sceneLastUpdateMicros())
                .append(",\"scene_upsert_last_us\":").append(value.sceneLastUpsertMicros())
                .append(",\"scene_remove_last_us\":").append(value.sceneLastRemoveMicros())
                .append(",\"scene_commit_last_us\":").append(value.sceneLastCommitMicros())
                .append(",\"scene_accepted\":").append(value.sceneAcceptedSections())
                .append(",\"scene_pending_commit\":").append(value.sceneCommitPending())
                .append(",\"upload_count\":").append(value.uploadCount())
                .append(",\"upload_gaps\":").append(value.uploadGenerationGaps())
                .append(",\"upload_last_us\":").append(value.uploadLastMicros())
                .append(",\"interop_copy_count\":").append(value.interopCopyCount())
                .append(",\"interop_copy_pending\":").append(value.interopCopyPending())
                .append(",\"interop_pending_generation\":").append(value.interopPendingGeneration())
                .append(",\"interop_displayed_generation\":").append(value.interopDisplayedGeneration())
                .append(",\"interop_copy_cpu_last_us\":").append(value.interopLastCopyMicros())
                .append(",\"native_available\":").append(value.nativeAvailable())
                .append(",\"settings_revision\":").append(value.settingsRevision())
                .append(",\"scene_revision\":").append(value.sceneRevision())
                .append(",\"camera_revision\":").append(value.cameraRevision())
                .append(",\"frame_generation\":").append(value.frameGeneration())
                .append(",\"native_state\":").append(value.nativeState())
                .append(",\"sample\":").append(value.sampleCount())
                .append(",\"target_sample\":").append(value.targetSampleCount())
                .append(",\"section_count\":").append(value.sectionCount())
                .append(",\"reset_level\":").append(value.resetLevel())
                .append(",\"produced_frames\":").append(value.producedFrameCount())
                .append(",\"copied_frames\":").append(value.copiedFrameCount())
                .append(",\"dropped_display_updates\":").append(value.droppedDisplayUpdates())
                .append(",\"scene_commit_count\":").append(value.sceneCommitCount())
                .append(",\"scene_delta_count\":").append(value.sceneDeltaCount())
                .append(",\"render_start_count\":").append(value.renderStartCount())
                .append(",\"scene_timing_revision\":").append(value.sceneTimingRevision())
                .append(",\"scene_timing_count\":").append(value.sceneTimingCount())
                .append(",\"scene_commit_native_last_us\":").append(value.lastSceneCommitMicros())
                .append(",\"scene_delta_last_us\":").append(value.lastSceneDeltaMicros())
                .append(",\"render_start_last_us\":").append(value.lastRenderStartMicros())
                .append(",\"scene_queue_last_us\":").append(value.lastSceneQueueMicros())
                .append(",\"reset_wait_last_us\":").append(value.lastResetWaitMicros())
                .append(",\"device_update_last_us\":").append(value.lastDeviceUpdateMicros())
                .append(",\"geometry_update_last_us\":").append(value.lastGeometryUpdateMicros())
                .append(",\"bvh_update_last_us\":").append(value.lastBvhUpdateMicros())
                .append(",\"scene_first_frame_last_us\":").append(value.lastSceneFirstFrameMicros())
                .append(",\"device_phase_active\":").append(value.activeDevicePhase())
                .append(",\"device_phase_active_us\":").append(value.activeDevicePhaseMicros());
        appendDevicePhases(json, "device_phase_last_us", value.lastDevicePhaseMicros());
        appendDevicePhases(json, "device_phase_ema_us", value.emaDevicePhaseMicros());
        appendDevicePhases(json, "device_phase_max_us", value.maxDevicePhaseMicros());
        json
                .append('}');
    }

    private static void appendDevicePhases(StringBuilder json, String name, int[] values) {
        json.append(",\"").append(name).append("\":{");
        for (int index = 0; index < DEVICE_PHASE_NAMES.length; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append('\"').append(DEVICE_PHASE_NAMES[index]).append("\":")
                    .append(index < values.length ? values[index] : 0);
        }
        json.append('}');
    }

    private static void appendMicros(StringBuilder json, long nanos) {
        if (nanos == PerformanceSample.UNAVAILABLE) {
            json.append("null");
        } else {
            json.append(nanos / 1_000L);
        }
    }

    private static String triggerName(int code) {
        if (code == 0) {
            return "none";
        }
        StringBuilder name = new StringBuilder();
        if ((code & 1) != 0) {
            name.append("absolute");
        }
        if ((code & 2) != 0) {
            appendTriggerPart(name, "cpu_adaptive");
        }
        if ((code & 4) != 0) {
            appendTriggerPart(name, "flip_adaptive");
        }
        if ((code & 8) != 0) {
            appendTriggerPart(name, "cycles_device_phase");
        }
        return name.toString();
    }

    private static void appendTriggerPart(StringBuilder target, String part) {
        if (!target.isEmpty()) {
            target.append('+');
        }
        target.append(part);
    }

    record Capture(
            long firstTriggerFrame,
            long lastTriggerFrame,
            List<PerformanceSample.Snapshot> frames,
            long gpuQueryDroppedFrames) {
    }
}

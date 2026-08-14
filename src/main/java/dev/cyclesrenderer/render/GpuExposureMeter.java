package dev.cyclesrenderer.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.cyclesrenderer.camera.ExposureHistogram;
import dev.cyclesrenderer.config.CameraAutomationSettings;
import dev.cyclesrenderer.config.CyclesRenderSettings;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.Optional;

/** Downsamples scene-linear HDR color and asynchronously returns histogram measurements. */
final class GpuExposureMeter {
    static final int WIDTH = 64;
    static final int HEIGHT = 36;
    private static final int BYTES_PER_PIXEL = 8;
    private static final long READBACK_BYTES = (long) WIDTH * HEIGHT * BYTES_PER_PIXEL;
    private static final long CAPTURE_INTERVAL_NANOS = 50_000_000L;
    private static final int SLOT_COUNT = 3;

    private final ExposureHistogram histogram = new ExposureHistogram();
    private final ReadbackSlot[] slots = new ReadbackSlot[SLOT_COUNT];
    private TextureTarget target;
    private volatile long epoch;
    private long sequence;
    private long lastCaptureNanos = Long.MIN_VALUE;
    private long captureCount;
    private long droppedCaptureCount;

    GpuExposureMeter() {
        for (int index = 0; index < slots.length; index++) {
            slots[index] = new ReadbackSlot();
        }
    }

    ExposureHistogram.Result pollLatest(CameraAutomationSettings.AutoExposure settings) {
        RenderSystem.assertOnRenderThread();
        ReadbackSlot latest = null;
        for (ReadbackSlot slot : slots) {
            if (slot.ready && (latest == null || slot.sequence > latest.sequence)) {
                latest = slot;
            }
        }
        if (latest == null) {
            return null;
        }
        for (ReadbackSlot slot : slots) {
            if (slot != latest && slot.ready && slot.sequence < latest.sequence) {
                slot.ready = false;
            }
        }
        latest.ready = false;
        return readHistogram(latest, settings);
    }

    void capture(
            GpuTextureView source,
            CyclesRenderSettings.WorkingSpace workingSpace,
            long nowNanos) {
        RenderSystem.assertOnRenderThread();
        if (lastCaptureNanos != Long.MIN_VALUE
                && nowNanos - lastCaptureNanos < CAPTURE_INTERVAL_NANOS) {
            return;
        }
        ReadbackSlot slot = availableSlot();
        if (slot == null) {
            droppedCaptureCount++;
            return;
        }
        ensureResources();
        long scheduledEpoch = epoch;
        long scheduledSequence = ++sequence;
        slot.sequence = scheduledSequence;
        slot.workingSpace = workingSpace;
        slot.inFlight = true;
        slot.ready = false;

        try {
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            try (RenderPass renderPass = encoder.createRenderPass(
                    () -> "Cycles automatic exposure downsample",
                    Objects.requireNonNull(target.getColorTextureView()),
                    Optional.empty())) {
                renderPass.setPipeline(CyclesRenderPipelines.EXPOSURE_METER);
                RenderSystem.bindDefaultUniforms(renderPass);
                renderPass.bindTexture(
                        "InSampler",
                        source,
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                renderPass.draw(3, 1, 0, 0);
            }
            encoder.copyTextureToBuffer(
                    Objects.requireNonNull(target.getColorTexture()),
                    Objects.requireNonNull(slot.buffer),
                    0L,
                    () -> complete(slot, scheduledEpoch, scheduledSequence),
                    0);
        } catch (RuntimeException error) {
            slot.inFlight = false;
            throw error;
        }
        lastCaptureNanos = nowNanos;
        captureCount++;
    }

    void reset() {
        RenderSystem.assertOnRenderThread();
        epoch++;
        lastCaptureNanos = Long.MIN_VALUE;
        for (ReadbackSlot slot : slots) {
            slot.ready = false;
        }
    }

    Telemetry telemetry() {
        long pending = 0L;
        for (ReadbackSlot slot : slots) {
            if (slot.inFlight) {
                pending++;
            }
        }
        return new Telemetry(captureCount, droppedCaptureCount, pending);
    }

    private ExposureHistogram.Result readHistogram(
            ReadbackSlot slot,
            CameraAutomationSettings.AutoExposure settings) {
        histogram.clear();
        float[] coefficients = luminanceCoefficients(slot.workingSpace);
        float centerStrength = settings.metering()
                == CameraAutomationSettings.ExposureMetering.CENTER_WEIGHTED
                ? settings.centerWeight()
                : 0.0F;
        try (GpuBufferSlice.MappedView mapped =
                     Objects.requireNonNull(slot.buffer).map(true, false)) {
            ByteBuffer data = mapped.data().order(ByteOrder.nativeOrder());
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    float red = Float.float16ToFloat(data.getShort());
                    float green = Float.float16ToFloat(data.getShort());
                    float blue = Float.float16ToFloat(data.getShort());
                    data.getShort();
                    float luminance = red * coefficients[0]
                            + green * coefficients[1]
                            + blue * coefficients[2];
                    if (Float.isFinite(luminance) && luminance > 0.0F) {
                        histogram.add(log2(luminance), ExposureHistogram.centerWeight(
                                x, y, WIDTH, HEIGHT, centerStrength));
                    }
                }
            }
        }
        return histogram.measure(new ExposureHistogram.Settings(
                settings.lowPercentile(),
                settings.highPercentile(),
                settings.highlightPercentile()));
    }

    private ReadbackSlot availableSlot() {
        for (ReadbackSlot slot : slots) {
            if (!slot.inFlight && !slot.ready) {
                return slot;
            }
        }
        return null;
    }

    private void ensureResources() {
        if (target == null) {
            target = new TextureTarget(
                    "Cycles automatic exposure meter",
                    WIDTH,
                    HEIGHT,
                    false,
                    GpuFormat.RGBA16_FLOAT);
        }
        for (int index = 0; index < slots.length; index++) {
            if (slots[index].buffer == null) {
                int slotIndex = index;
                slots[index].buffer = RenderSystem.getDevice().createBuffer(
                        () -> "Cycles automatic exposure readback " + slotIndex,
                        GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
                        READBACK_BYTES);
            }
        }
    }

    private void complete(ReadbackSlot slot, long scheduledEpoch, long scheduledSequence) {
        if (slot.sequence == scheduledSequence) {
            slot.inFlight = false;
            if (scheduledEpoch == epoch) {
                slot.ready = true;
            }
        }
    }

    private static float[] luminanceCoefficients(CyclesRenderSettings.WorkingSpace space) {
        return switch (space) {
            case LINEAR_REC709 -> new float[]{0.2126F, 0.7152F, 0.0722F};
            case LINEAR_REC2020 -> new float[]{0.2627F, 0.6780F, 0.0593F};
            case ACESCG -> new float[]{0.27222872F, 0.67408174F, 0.05368952F};
        };
    }

    private static float log2(float value) {
        return (float) (Math.log(value) / Math.log(2.0D));
    }

    private final class ReadbackSlot {
        private GpuBuffer buffer;
        private volatile boolean inFlight;
        private volatile boolean ready;
        private long sequence;
        private CyclesRenderSettings.WorkingSpace workingSpace;
    }

    record Telemetry(long captureCount, long droppedCaptureCount, long pendingCaptureCount) {
    }
}

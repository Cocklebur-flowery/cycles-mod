package dev.cyclesrenderer.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import dev.cyclesrenderer.config.CyclesRenderSettings;
import dev.cyclesrenderer.nativebridge.NativeBridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.Optional;

public final class CyclesFramePresenter {
    private TextureTarget nativeFrameTarget;
    private boolean ready;
    private long uploadCount;
    private long uploadedBytes;
    private long generationGaps;
    private long lastGeneration;
    private long lastUploadMicros;
    private long emaUploadMicros;
    private long maxUploadMicros;
    private GpuBuffer displayUniformBuffer;
    private final ByteBuffer displayUniformData = ByteBuffer.allocateDirect(32)
            .order(ByteOrder.nativeOrder());
    private long displaySettingsRevision = Long.MIN_VALUE;
    private int displayDepthFarBits;

    public void update(NativeBridge.AcquiredFrame frame) {
        RenderSystem.assertOnRenderThread();
        if (!frame.updated()) {
            return;
        }
        if (frame.pixels() == null || frame.width() <= 0 || frame.height() <= 0) {
            throw new IllegalArgumentException("updated native frame has no pixel data");
        }
        if (frame.pixelFormat() != NativeBridge.PIXEL_FORMAT_RGBA16_FLOAT) {
            throw new IllegalArgumentException(
                    "unsupported native frame pixel format: " + frame.pixelFormat());
        }
        if (nativeFrameTarget == null) {
            nativeFrameTarget = new TextureTarget(
                    "Cycles native frame",
                    frame.width(),
                    frame.height(),
                    false,
                    GpuFormat.RGBA16_FLOAT);
        } else if (nativeFrameTarget.width != frame.width()
                || nativeFrameTarget.height != frame.height()) {
            nativeFrameTarget.resize(frame.width(), frame.height());
        }
        long uploadStart = System.nanoTime();
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(
                Objects.requireNonNull(nativeFrameTarget.getColorTexture()),
                frame.pixels(),
                0,
                0,
                0,
                0,
                frame.width(),
                frame.height());
        long uploadNanos = System.nanoTime() - uploadStart;
        lastUploadMicros = nanosToMicros(uploadNanos);
        emaUploadMicros = updateEma(emaUploadMicros, lastUploadMicros);
        maxUploadMicros = Math.max(maxUploadMicros, lastUploadMicros);
        uploadCount++;
        uploadedBytes += Math.multiplyExact(
                Math.multiplyExact((long) frame.width(), frame.height()), 8L);
        if (lastGeneration != 0L && frame.generation() > lastGeneration + 1L) {
            generationGaps += frame.generation() - lastGeneration - 1L;
        }
        lastGeneration = frame.generation();
        ready = true;
    }

    public void present(
            RenderTarget output,
            CyclesRenderSettings settings,
            float depthFar) {
        RenderSystem.assertOnRenderThread();
        if (!ready || nativeFrameTarget == null) {
            return;
        }
        updateDisplayUniforms(settings, depthFar);
        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(
                        () -> "Cycles frame upscale",
                        Objects.requireNonNull(output.getColorTextureView()),
                        Optional.empty())) {
            renderPass.setPipeline(CyclesRenderPipelines.PRESENT);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.bindTexture(
                    "InSampler",
                    Objects.requireNonNull(nativeFrameTarget.getColorTextureView()),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            renderPass.setUniform(
                    CyclesRenderPipelines.DISPLAY_UNIFORM,
                    Objects.requireNonNull(displayUniformBuffer));
            renderPass.draw(3, 1, 0, 0);
        }
    }

    public boolean hasFrame() {
        return ready && nativeFrameTarget != null;
    }

    public long generation() {
        return lastGeneration;
    }

    public Telemetry telemetry() {
        return new Telemetry(
                uploadCount,
                uploadedBytes,
                generationGaps,
                lastUploadMicros,
                emaUploadMicros,
                maxUploadMicros);
    }

    public void reset() {
        RenderSystem.assertOnRenderThread();
        ready = false;
        uploadCount = 0L;
        uploadedBytes = 0L;
        generationGaps = 0L;
        lastGeneration = 0L;
        lastUploadMicros = 0L;
        emaUploadMicros = 0L;
        maxUploadMicros = 0L;
        displaySettingsRevision = Long.MIN_VALUE;
        displayDepthFarBits = 0;
        if (nativeFrameTarget != null) {
            nativeFrameTarget.destroyBuffers();
            nativeFrameTarget = null;
        }
        if (displayUniformBuffer != null) {
            displayUniformBuffer.close();
            displayUniformBuffer = null;
        }
    }

    private void updateDisplayUniforms(
            CyclesRenderSettings settings,
            float depthFar) {
        int depthBits = Float.floatToIntBits(depthFar);
        if (displayUniformBuffer != null
                && displaySettingsRevision == settings.revision()
                && displayDepthFarBits == depthBits) {
            return;
        }
        if (displayUniformBuffer == null) {
            displayUniformBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "Cycles display settings",
                    GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM,
                    32L);
        }
        displayUniformData.clear();
        displayUniformData.putFloat((float) Math.pow(2.0, settings.exposureEv()));
        displayUniformData.putFloat(1.0F / settings.gamma());
        displayUniformData.putFloat(Math.max(depthFar, 1.0F));
        displayUniformData.putFloat(Math.max(settings.stillSamples(), 1));
        displayUniformData.putInt(settings.activePass().nativeId());
        displayUniformData.putInt(settings.viewTransform().nativeId());
        displayUniformData.putInt(0);
        displayUniformData.putInt(0);
        displayUniformData.flip();
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(
                displayUniformBuffer.slice(),
                displayUniformData);
        displaySettingsRevision = settings.revision();
        displayDepthFarBits = depthBits;
    }

    private static long nanosToMicros(long nanos) {
        return Math.max(0L, (nanos + 999L) / 1_000L);
    }

    private static long updateEma(long previous, long value) {
        return previous == 0L ? value : (previous * 7L + value) / 8L;
    }

    public record Telemetry(
            long uploadCount,
            long uploadedBytes,
            long generationGaps,
            long lastUploadMicros,
            long emaUploadMicros,
            long maxUploadMicros) {
    }
}

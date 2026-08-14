package dev.cyclesrenderer.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.Optional;

public final class HdrOutputStage {
    private static final float SCRGB_REFERENCE_WHITE_NITS = 80.0F;
    private static final ByteBuffer UNIFORM_DATA = ByteBuffer.allocateDirect(16)
            .order(ByteOrder.nativeOrder());

    private static TextureTarget scRgbTarget;
    private static TextureTarget sdrTarget;
    private static GpuBuffer outputUniform;
    private static long conversionCount;
    private static long sdrConversionCount;
    private static long captureConversionCount;
    private static long lastConversionMicros;
    private static long emaConversionMicros;
    private static long maxConversionMicros;
    private static String outputMode = "SDR passthrough";
    private static String lastError = "";

    private HdrOutputStage() {
    }

    public static GpuTextureView prepare(GpuTextureView source) {
        RenderSystem.assertOnRenderThread();
        if (!HdrRenderTargetPolicy.fp16TargetsActive()) {
            outputMode = "SDR passthrough";
            return source;
        }

        try {
            if (VulkanCapabilityProbe.swapchainBootstrap().scRgbSelected()) {
                return convertScRgb(source);
            }
            return convertSdr(source, false);
        } catch (RuntimeException error) {
            lastError = error.getClass().getSimpleName() + ": "
                    + String.valueOf(error.getMessage());
            outputMode = "direct fallback after conversion error";
            return source;
        }
    }

    public static GpuTexture prepareSdrCapture(RenderTarget sourceTarget) {
        RenderSystem.assertOnRenderThread();
        GpuTexture sourceTexture = sourceTarget.getColorTexture();
        if (sourceTexture == null || sourceTexture.getFormat() != GpuFormat.RGBA16_FLOAT) {
            return sourceTexture;
        }
        GpuTextureView sourceView = sourceTarget.getColorTextureView();
        if (sourceView == null) {
            return sourceTexture;
        }
        convertSdr(sourceView, true);
        return Objects.requireNonNull(sdrTarget.getColorTexture());
    }

    public static Telemetry telemetry() {
        TextureTarget target = outputMode.startsWith("linear scRGB")
                ? scRgbTarget
                : sdrTarget;
        return new Telemetry(
                VulkanCapabilityProbe.swapchainBootstrap().scRgbSelected(),
                HdrRenderTargetPolicy.fp16TargetsActive(),
                outputMode,
                target == null ? 0 : target.width,
                target == null ? 0 : target.height,
                HdrDisplayTransform.paperWhiteNits(),
                HdrDisplayTransform.paperWhiteNits() / SCRGB_REFERENCE_WHITE_NITS,
                conversionCount,
                sdrConversionCount,
                captureConversionCount,
                lastConversionMicros,
                emaConversionMicros,
                maxConversionMicros,
                lastError);
    }

    public static void close() {
        if (scRgbTarget != null) {
            scRgbTarget.destroyBuffers();
            scRgbTarget = null;
        }
        if (sdrTarget != null) {
            sdrTarget.destroyBuffers();
            sdrTarget = null;
        }
        if (outputUniform != null) {
            outputUniform.close();
            outputUniform = null;
        }
    }

    private static GpuTextureView convertScRgb(GpuTextureView source) {
        long start = System.nanoTime();
        int width = source.texture().getWidth(0);
        int height = source.texture().getHeight(0);
        ensureScRgbResources(width, height);
        renderConversion(
                "Cycles scRGB output conversion",
                source,
                Objects.requireNonNull(scRgbTarget.getColorTextureView()),
                CyclesRenderPipelines.SCRGB_OUTPUT,
                true);
        recordConversion(System.nanoTime() - start, false, false);
        outputMode = "linear scRGB";
        lastError = "";
        return Objects.requireNonNull(scRgbTarget.getColorTextureView());
    }

    private static GpuTextureView convertSdr(GpuTextureView source, boolean capture) {
        long start = System.nanoTime();
        int width = source.texture().getWidth(0);
        int height = source.texture().getHeight(0);
        ensureSdrTarget(width, height);
        renderConversion(
                capture ? "Cycles SDR screenshot conversion" : "Cycles SDR fallback",
                source,
                Objects.requireNonNull(sdrTarget.getColorTextureView()),
                CyclesRenderPipelines.SDR_OUTPUT,
                false);
        recordConversion(System.nanoTime() - start, true, capture);
        outputMode = capture ? "SDR capture" : "SDR surface fallback";
        lastError = "";
        return Objects.requireNonNull(sdrTarget.getColorTextureView());
    }

    private static void renderConversion(
            String label,
            GpuTextureView source,
            GpuTextureView destination,
            RenderPipeline pipeline,
            boolean bindOutputUniform) {
        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> label, destination, Optional.empty())) {
            renderPass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.bindTexture(
                    "InSampler",
                    source,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            if (bindOutputUniform) {
                renderPass.setUniform(
                        CyclesRenderPipelines.HDR_OUTPUT_UNIFORM,
                        Objects.requireNonNull(outputUniform));
            }
            renderPass.draw(3, 1, 0, 0);
        }
    }

    private static void ensureScRgbResources(int width, int height) {
        if (scRgbTarget == null) {
            scRgbTarget = new TextureTarget(
                    "Cycles scRGB output",
                    width,
                    height,
                    false,
                    GpuFormat.RGBA16_FLOAT);
        } else if (scRgbTarget.width != width || scRgbTarget.height != height) {
            scRgbTarget.resize(width, height);
        }
        if (outputUniform == null) {
            outputUniform = RenderSystem.getDevice().createBuffer(
                    () -> "Cycles scRGB output settings",
                    GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM,
                    16L);
            UNIFORM_DATA.clear();
            UNIFORM_DATA.putFloat(
                    HdrDisplayTransform.paperWhiteNits() / SCRGB_REFERENCE_WHITE_NITS);
            UNIFORM_DATA.putFloat(HdrDisplayTransform.paperWhiteNits());
            UNIFORM_DATA.putFloat(SCRGB_REFERENCE_WHITE_NITS);
            UNIFORM_DATA.putFloat(0.0F);
            UNIFORM_DATA.flip();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(
                    outputUniform.slice(), UNIFORM_DATA);
        }
    }

    private static void ensureSdrTarget(int width, int height) {
        if (sdrTarget == null) {
            sdrTarget = new TextureTarget(
                    "Cycles SDR output",
                    width,
                    height,
                    false,
                    GpuFormat.RGBA8_UNORM);
        } else if (sdrTarget.width != width || sdrTarget.height != height) {
            sdrTarget.resize(width, height);
        }
    }

    private static void recordConversion(
            long elapsedNanos,
            boolean sdr,
            boolean capture) {
        long micros = Math.max(0L, (elapsedNanos + 999L) / 1_000L);
        lastConversionMicros = micros;
        emaConversionMicros = emaConversionMicros == 0L
                ? micros
                : (emaConversionMicros * 7L + micros) / 8L;
        maxConversionMicros = Math.max(maxConversionMicros, micros);
        conversionCount++;
        if (sdr) {
            sdrConversionCount++;
        }
        if (capture) {
            captureConversionCount++;
        }
    }

    public record Telemetry(
            boolean active,
            boolean fp16TargetsActive,
            String outputMode,
            int width,
            int height,
            float paperWhiteNits,
            float scRgbScale,
            long conversionCount,
            long sdrConversionCount,
            long captureConversionCount,
            long lastConversionMicros,
            long emaConversionMicros,
            long maxConversionMicros,
            String error) {
    }
}

package dev.cyclesrenderer.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.Optional;

public final class HdrOutputStage {
    private static final float SCRGB_REFERENCE_WHITE_NITS = 80.0F;
    private static final ByteBuffer UNIFORM_DATA = ByteBuffer.allocateDirect(16)
            .order(ByteOrder.nativeOrder());

    private static TextureTarget outputTarget;
    private static GpuBuffer outputUniform;
    private static long conversionCount;
    private static long lastConversionMicros;
    private static long emaConversionMicros;
    private static long maxConversionMicros;
    private static String lastError = "";

    private HdrOutputStage() {
    }

    public static GpuTextureView prepare(GpuTextureView source) {
        RenderSystem.assertOnRenderThread();
        if (!VulkanCapabilityProbe.swapchainBootstrap().scRgbSelected()) {
            return source;
        }

        long start = System.nanoTime();
        try {
            int width = source.texture().getWidth(0);
            int height = source.texture().getHeight(0);
            ensureResources(width, height);
            try (RenderPass renderPass = RenderSystem.getDevice()
                    .createCommandEncoder()
                    .createRenderPass(
                            () -> "Cycles scRGB output conversion",
                            Objects.requireNonNull(outputTarget.getColorTextureView()),
                            Optional.empty())) {
                renderPass.setPipeline(CyclesRenderPipelines.SCRGB_OUTPUT);
                RenderSystem.bindDefaultUniforms(renderPass);
                renderPass.bindTexture(
                        "InSampler",
                        source,
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                renderPass.setUniform(
                        CyclesRenderPipelines.HDR_OUTPUT_UNIFORM,
                        Objects.requireNonNull(outputUniform));
                renderPass.draw(3, 1, 0, 0);
            }
            recordConversion(System.nanoTime() - start);
            lastError = "";
            return Objects.requireNonNull(outputTarget.getColorTextureView());
        } catch (RuntimeException error) {
            lastError = error.getClass().getSimpleName() + ": "
                    + String.valueOf(error.getMessage());
            throw error;
        }
    }

    public static Telemetry telemetry() {
        TextureTarget target = outputTarget;
        return new Telemetry(
                VulkanCapabilityProbe.swapchainBootstrap().scRgbSelected(),
                target == null ? 0 : target.width,
                target == null ? 0 : target.height,
                HdrDisplayTransform.paperWhiteNits(),
                HdrDisplayTransform.paperWhiteNits() / SCRGB_REFERENCE_WHITE_NITS,
                conversionCount,
                lastConversionMicros,
                emaConversionMicros,
                maxConversionMicros,
                lastError);
    }

    public static void close() {
        if (outputTarget != null) {
            outputTarget.destroyBuffers();
            outputTarget = null;
        }
        if (outputUniform != null) {
            outputUniform.close();
            outputUniform = null;
        }
    }

    private static void ensureResources(int width, int height) {
        if (outputTarget == null) {
            outputTarget = new TextureTarget(
                    "Cycles scRGB output",
                    width,
                    height,
                    false,
                    GpuFormat.RGBA16_FLOAT);
        } else if (outputTarget.width != width || outputTarget.height != height) {
            outputTarget.resize(width, height);
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

    private static void recordConversion(long elapsedNanos) {
        long micros = Math.max(0L, (elapsedNanos + 999L) / 1_000L);
        lastConversionMicros = micros;
        emaConversionMicros = emaConversionMicros == 0L
                ? micros
                : (emaConversionMicros * 7L + micros) / 8L;
        maxConversionMicros = Math.max(maxConversionMicros, micros);
        conversionCount++;
    }

    public record Telemetry(
            boolean active,
            int width,
            int height,
            float paperWhiteNits,
            float scRgbScale,
            long conversionCount,
            long lastConversionMicros,
            long emaConversionMicros,
            long maxConversionMicros,
            String error) {
    }
}

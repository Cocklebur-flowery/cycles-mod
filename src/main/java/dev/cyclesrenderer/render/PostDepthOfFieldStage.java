package dev.cyclesrenderer.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.cyclesrenderer.config.CyclesRenderSettings;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.Optional;

/** Applies depth-based lens blur to the denoised linear-HDR frame. */
final class PostDepthOfFieldStage {
    private static final long UNIFORM_BYTES = 48L;
    private static final float MAX_BLUR_RADIUS_PIXELS = 32.0F;

    private TextureTarget target;
    private GpuBuffer uniformBuffer;
    private final ByteBuffer uniformData = ByteBuffer.allocateDirect((int) UNIFORM_BYTES)
            .order(ByteOrder.nativeOrder());

    GpuTextureView apply(
            GpuTextureView source,
            GpuTextureView depth,
            CyclesRenderSettings settings,
            float focusDistance,
            float depthFar,
            int width,
            int height) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(source);
        Objects.requireNonNull(depth);
        if (!enabled(settings) || width <= 0 || height <= 0) {
            return source;
        }
        ensureTarget(width, height);
        updateUniforms(settings, focusDistance, depthFar, width, height);
        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(
                        () -> "Cycles post-process depth of field",
                        Objects.requireNonNull(target.getColorTextureView()),
                        Optional.empty())) {
            renderPass.setPipeline(CyclesRenderPipelines.POST_DEPTH_OF_FIELD);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.bindTexture(
                    "InSampler",
                    source,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            renderPass.bindTexture(
                    CyclesRenderPipelines.DEPTH_SAMPLER,
                    depth,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            renderPass.setUniform(
                    CyclesRenderPipelines.POST_DOF_UNIFORM,
                    Objects.requireNonNull(uniformBuffer));
            renderPass.draw(3, 1, 0, 0);
        }
        return Objects.requireNonNull(target.getColorTextureView());
    }

    void reset() {
        RenderSystem.assertOnRenderThread();
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
        if (uniformBuffer != null) {
            uniformBuffer.close();
            uniformBuffer = null;
        }
    }

    private static boolean enabled(CyclesRenderSettings settings) {
        return settings.depthOfField()
                && settings.depthOfFieldMode()
                    == CyclesRenderSettings.DepthOfFieldMode.POST_PROCESS
                && settings.activePass() == CyclesRenderSettings.PassView.COMBINED
                && settings.cameraType() == CyclesRenderSettings.CameraType.PERSPECTIVE;
    }

    private void ensureTarget(int width, int height) {
        if (target == null) {
            target = new TextureTarget(
                    "Cycles post-process depth of field",
                    width,
                    height,
                    false,
                    GpuFormat.RGBA16_FLOAT);
        } else if (target.width != width || target.height != height) {
            target.resize(width, height);
        }
        if (uniformBuffer == null) {
            uniformBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "Cycles post-process depth of field settings",
                    GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM,
                    UNIFORM_BYTES);
        }
    }

    private void updateUniforms(
            CyclesRenderSettings settings,
            float focusDistance,
            float depthFar,
            int width,
            int height) {
        uniformData.clear();
        uniformData.putFloat(Math.max(0.001F, focusDistance));
        uniformData.putFloat(Math.max(0.001F, settings.focalLengthMm() * 0.001F));
        uniformData.putFloat(Math.max(0.1F, settings.fStop()));
        uniformData.putFloat(Math.max(0.001F, settings.sensorWidthMm() * 0.001F));
        uniformData.putFloat(width);
        uniformData.putFloat(height);
        uniformData.putFloat(MAX_BLUR_RADIUS_PIXELS);
        uniformData.putFloat(Math.max(1.0F, depthFar));
        uniformData.putFloat(settings.apertureBlades());
        uniformData.putFloat((float) Math.toRadians(settings.apertureRotationDegrees()));
        uniformData.putFloat(Math.max(0.1F, settings.apertureRatio()));
        uniformData.putFloat(0.0F);
        uniformData.flip();
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(
                Objects.requireNonNull(uniformBuffer).slice(),
                uniformData);
    }
}

package dev.cyclesrenderer.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.cyclesrenderer.config.CyclesRenderSettings;
import dev.cyclesrenderer.nativebridge.NativeBridge;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/** Forward-splats a matched HDR/depth source frame into the current perspective camera. */
final class DepthReprojectionStage {
    private static final long UNIFORM_BYTES = 112L;
    private static final Vector4f CLEAR = new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);

    private final ByteBuffer uniformData = ByteBuffer.allocateDirect((int) UNIFORM_BYTES)
            .order(ByteOrder.nativeOrder());
    private TextureTarget splatColor;
    private TextureTarget splatDepth;
    private TextureTarget coverageA;
    private TextureTarget coverageB;
    private TextureTarget resolvedColor;
    private TextureTarget resolvedDepth;
    private GpuBuffer uniformBuffer;

    Result apply(
            boolean requested,
            GpuTextureView sourceColor,
            GpuTextureView sourceDepth,
            NativeBridge.ReprojectionMetadata sourceMetadata,
            NativeBridge.CameraInput targetCamera,
            CyclesRenderSettings settings,
            int targetWidth,
            int targetHeight) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(sourceColor);
        if (!requested) {
            return Result.bypassed(sourceColor, sourceDepth, "disabled");
        }
        String rejection = rejectionReason(
                sourceColor, sourceDepth, sourceMetadata, targetCamera,
                settings, targetWidth, targetHeight);
        if (!rejection.isEmpty()) {
            return Result.bypassed(sourceColor, sourceDepth, rejection);
        }

        try {
            ensureTargets(targetWidth, targetHeight);
            updateUniforms(sourceMetadata, targetCamera, settings, targetWidth, targetHeight);
            splat(sourceColor, sourceDepth, sourceMetadata.width(), sourceMetadata.height(),
                    targetWidth, targetHeight);
            GpuTextureView coverage = reduceCoverage(targetWidth, targetHeight);
            resolve(sourceColor, sourceDepth, coverage, targetWidth, targetHeight);
            return new Result(
                    Objects.requireNonNull(resolvedColor.getColorTextureView()),
                    Objects.requireNonNull(resolvedDepth.getColorTextureView()),
                    true,
                    "");
        } catch (RuntimeException error) {
            try {
                releaseResources();
            } catch (RuntimeException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            String detail = error.getMessage();
            return Result.bypassed(
                    sourceColor,
                    sourceDepth,
                    "gpu:" + error.getClass().getSimpleName()
                            + (detail == null || detail.isBlank() ? "" : ":" + detail));
        }
    }

    void reset() {
        RenderSystem.assertOnRenderThread();
        releaseResources();
    }

    private static String rejectionReason(
            GpuTextureView sourceColor,
            GpuTextureView sourceDepth,
            NativeBridge.ReprojectionMetadata metadata,
            NativeBridge.CameraInput camera,
            CyclesRenderSettings settings,
            int targetWidth,
            int targetHeight) {
        if (sourceDepth == null || metadata == null) {
            return "source-bundle";
        }
        if (camera == null || settings == null || targetWidth <= 0 || targetHeight <= 0) {
            return "target-camera";
        }
        if (settings.cameraType() != CyclesRenderSettings.CameraType.PERSPECTIVE
                || settings.projectionMode()
                    != CyclesRenderSettings.ProjectionMode.MINECRAFT_FOV
                || settings.activePass() != CyclesRenderSettings.PassView.COMBINED) {
            return "projection";
        }
        if (settings.depthOfField()
                && settings.depthOfFieldMode()
                    == CyclesRenderSettings.DepthOfFieldMode.PHYSICAL) {
            return "physical-depth-of-field";
        }
        if (sourceColor.texture().getFormat() != GpuFormat.RGBA16_FLOAT
                || sourceDepth.texture().getFormat() != GpuFormat.R32_FLOAT
                || metadata.width() != sourceColor.getWidth(0)
                || metadata.height() != sourceColor.getHeight(0)
                || metadata.width() != sourceDepth.getWidth(0)
                || metadata.height() != sourceDepth.getHeight(0)) {
            return "dimensions";
        }
        float expectedAspect = (float) metadata.width() / metadata.height();
        if (!finite(
                metadata.positionX(), metadata.positionY(), metadata.positionZ(),
                metadata.rotationX(), metadata.rotationY(), metadata.rotationZ(),
                metadata.rotationW(), metadata.verticalFovRadians(), metadata.aspect(),
                metadata.shiftX(), metadata.shiftY(), metadata.nearClip(), metadata.farClip(),
                camera.positionX(), camera.positionY(), camera.positionZ(),
                camera.rotationX(), camera.rotationY(), camera.rotationZ(), camera.rotationW(),
                camera.verticalFovRadians(), camera.depthFar(),
                settings.cameraClipNear(), settings.cameraClipFar(),
                settings.cameraShiftX(), settings.cameraShiftY())
                || Math.abs(metadata.aspect() - expectedAspect) > 1.0e-3F
                || !validQuaternion(
                    metadata.rotationX(), metadata.rotationY(),
                    metadata.rotationZ(), metadata.rotationW())
                || metadata.verticalFovRadians() <= 0.0F
                || metadata.verticalFovRadians() >= Math.PI
                || metadata.aspect() <= 0.0F
                || metadata.nearClip() <= 0.0F
                || metadata.farClip() <= metadata.nearClip()
                || !validQuaternion(
                    camera.rotationX(), camera.rotationY(), camera.rotationZ(), camera.rotationW())
                || camera.verticalFovRadians() <= 0.0F
                || camera.verticalFovRadians() >= Math.PI
                || settings.cameraClipNear() <= 0.0F
                || settings.cameraClipFar() < 0.0F) {
            return "camera";
        }
        return "";
    }

    private void ensureTargets(int width, int height) {
        splatColor = ensureTarget(
                splatColor, "Cycles reprojection splat color", width, height,
                true, GpuFormat.RGBA16_FLOAT);
        splatDepth = ensureTarget(
                splatDepth, "Cycles reprojection splat depth", width, height,
                false, GpuFormat.R32_FLOAT);
        resolvedColor = ensureTarget(
                resolvedColor, "Cycles reprojection resolved color", width, height,
                false, GpuFormat.RGBA16_FLOAT);
        resolvedDepth = ensureTarget(
                resolvedDepth, "Cycles reprojection resolved depth", width, height,
                false, GpuFormat.R32_FLOAT);
        if (uniformBuffer == null) {
            uniformBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "Cycles depth reprojection camera",
                    GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM,
                    UNIFORM_BYTES);
        }
    }

    private void updateUniforms(
            NativeBridge.ReprojectionMetadata source,
            NativeBridge.CameraInput target,
            CyclesRenderSettings settings,
            int targetWidth,
            int targetHeight) {
        float targetNear = settings.cameraClipNear();
        float targetFar = Math.max(
                targetNear + 0.001F,
                settings.cameraClipFar() > 0.0F
                        ? settings.cameraClipFar() : target.depthFar());
        uniformData.clear();
        putVector(
                (float) (source.positionX() - target.positionX()),
                (float) (source.positionY() - target.positionY()),
                (float) (source.positionZ() - target.positionZ()),
                0.0F);
        putNormalizedQuaternion(
                source.rotationX(), source.rotationY(),
                source.rotationZ(), source.rotationW());
        putVector(
                source.verticalFovRadians(), source.aspect(),
                source.shiftX(), source.shiftY());
        putVector(
                source.nearClip(), source.farClip(), source.width(), source.height());
        putNormalizedQuaternion(
                target.rotationX(), target.rotationY(),
                target.rotationZ(), target.rotationW());
        putVector(
                target.verticalFovRadians(), (float) targetWidth / targetHeight,
                settings.cameraShiftX(), settings.cameraShiftY());
        putVector(targetNear, targetFar, targetWidth, targetHeight);
        uniformData.flip();
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(
                Objects.requireNonNull(uniformBuffer).slice(), uniformData);
    }

    private void splat(
            GpuTextureView sourceColor,
            GpuTextureView sourceDepth,
            int sourceWidth,
            int sourceHeight,
            int targetWidth,
            int targetHeight) {
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(
                        () -> "Cycles depth reprojection splat")
                .withColorAttachment(
                        Objects.requireNonNull(splatColor.getColorTextureView()),
                        Optional.of(CLEAR))
                .withColorAttachment(
                        Objects.requireNonNull(splatDepth.getColorTextureView()),
                        Optional.of(CLEAR))
                .withDepthAttachment(
                        Objects.requireNonNull(splatColor.getDepthTextureView()),
                        OptionalDouble.of(0.0D))
                .withRenderArea(new RenderPass.RenderArea(
                        0, 0, targetWidth, targetHeight));
        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(descriptor)) {
            renderPass.setPipeline(CyclesRenderPipelines.DEPTH_REPROJECTION_SPLAT);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.bindTexture(
                    "InSampler", sourceColor,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            renderPass.bindTexture(
                    CyclesRenderPipelines.DEPTH_SAMPLER, sourceDepth,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            renderPass.setUniform(
                    CyclesRenderPipelines.REPROJECTION_UNIFORM,
                    Objects.requireNonNull(uniformBuffer));
            renderPass.draw(Math.multiplyExact(sourceWidth, sourceHeight), 1, 0, 0);
        }
    }

    private GpuTextureView reduceCoverage(int width, int height) {
        GpuTextureView source = Objects.requireNonNull(splatDepth.getColorTextureView());
        int sourceWidth = width;
        int sourceHeight = height;
        boolean first = true;
        boolean useA = true;
        do {
            int nextWidth = Math.max(1, (sourceWidth + 1) / 2);
            int nextHeight = Math.max(1, (sourceHeight + 1) / 2);
            TextureTarget target;
            if (useA) {
                coverageA = ensureTarget(
                        coverageA, "Cycles reprojection coverage A",
                        nextWidth, nextHeight, false, GpuFormat.RG32_FLOAT);
                target = coverageA;
            } else {
                coverageB = ensureTarget(
                        coverageB, "Cycles reprojection coverage B",
                        nextWidth, nextHeight, false, GpuFormat.RG32_FLOAT);
                target = coverageB;
            }
            reduceCoverageLevel(source, target, first);
            source = Objects.requireNonNull(target.getColorTextureView());
            sourceWidth = nextWidth;
            sourceHeight = nextHeight;
            first = false;
            useA = !useA;
        } while (sourceWidth > 1 || sourceHeight > 1);
        return source;
    }

    private static void reduceCoverageLevel(
            GpuTextureView source,
            RenderTarget target,
            boolean sourceIsDepth) {
        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(
                        () -> "Cycles reprojection coverage reduction",
                        Objects.requireNonNull(target.getColorTextureView()),
                        Optional.empty())) {
            renderPass.setPipeline(sourceIsDepth
                    ? CyclesRenderPipelines.DEPTH_REPROJECTION_COVERAGE_DEPTH
                    : CyclesRenderPipelines.DEPTH_REPROJECTION_COVERAGE_SUM);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.bindTexture(
                    "InSampler", source,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            renderPass.draw(3, 1, 0, 0);
        }
    }

    private void resolve(
            GpuTextureView sourceColor,
            GpuTextureView sourceDepth,
            GpuTextureView coverage,
            int width,
            int height) {
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(
                        () -> "Cycles depth reprojection resolve")
                .withColorAttachment(
                        Objects.requireNonNull(resolvedColor.getColorTextureView()))
                .withColorAttachment(
                        Objects.requireNonNull(resolvedDepth.getColorTextureView()))
                .withRenderArea(new RenderPass.RenderArea(0, 0, width, height));
        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(descriptor)) {
            renderPass.setPipeline(CyclesRenderPipelines.DEPTH_REPROJECTION_RESOLVE);
            RenderSystem.bindDefaultUniforms(renderPass);
            var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            renderPass.bindTexture("InSampler", sourceColor, nearest);
            renderPass.bindTexture(CyclesRenderPipelines.DEPTH_SAMPLER, sourceDepth, nearest);
            renderPass.bindTexture(
                    CyclesRenderPipelines.REPROJECTED_SAMPLER,
                    Objects.requireNonNull(splatColor.getColorTextureView()),
                    nearest);
            renderPass.bindTexture(
                    CyclesRenderPipelines.REPROJECTED_DEPTH_SAMPLER,
                    Objects.requireNonNull(splatDepth.getColorTextureView()),
                    nearest);
            renderPass.bindTexture(
                    CyclesRenderPipelines.REPROJECTION_COVERAGE_SAMPLER,
                    coverage,
                    nearest);
            renderPass.draw(3, 1, 0, 0);
        }
    }

    private void putNormalizedQuaternion(float x, float y, float z, float w) {
        float inverseLength = 1.0F / (float) Math.sqrt(x * x + y * y + z * z + w * w);
        putVector(x * inverseLength, y * inverseLength, z * inverseLength, w * inverseLength);
    }

    private void putVector(float x, float y, float z, float w) {
        uniformData.putFloat(x);
        uniformData.putFloat(y);
        uniformData.putFloat(z);
        uniformData.putFloat(w);
    }

    private static TextureTarget ensureTarget(
            TextureTarget target,
            String label,
            int width,
            int height,
            boolean useDepth,
            GpuFormat format) {
        if (target == null) {
            return new TextureTarget(label, width, height, useDepth, format);
        }
        if (target.width != width || target.height != height) {
            target.resize(width, height);
        }
        return target;
    }

    private void releaseResources() {
        destroy(splatColor);
        destroy(splatDepth);
        destroy(coverageA);
        destroy(coverageB);
        destroy(resolvedColor);
        destroy(resolvedDepth);
        splatColor = null;
        splatDepth = null;
        coverageA = null;
        coverageB = null;
        resolvedColor = null;
        resolvedDepth = null;
        if (uniformBuffer != null) {
            uniformBuffer.close();
            uniformBuffer = null;
        }
    }

    private static void destroy(TextureTarget target) {
        if (target != null) {
            target.destroyBuffers();
        }
    }

    private static boolean validQuaternion(float x, float y, float z, float w) {
        double norm = x * x + y * y + z * z + w * w;
        return Double.isFinite(norm) && norm > 1.0e-12D;
    }

    private static boolean finite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    record Result(
            GpuTextureView color,
            GpuTextureView depth,
            boolean applied,
            String bypassReason) {
        static Result bypassed(
                GpuTextureView sourceColor,
                GpuTextureView sourceDepth,
                String reason) {
            return new Result(sourceColor, sourceDepth, false, reason);
        }
    }
}

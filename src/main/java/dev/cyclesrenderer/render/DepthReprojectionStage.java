package dev.cyclesrenderer.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
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
    private static final long COVERAGE_READBACK_BYTES = 8L;
    private static final int COVERAGE_READBACK_SLOTS = 3;
    private static final float MIN_VALID_COVERAGE = 0.90F;
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
    private final CoverageReadbackSlot[] coverageReadbacks =
            new CoverageReadbackSlot[COVERAGE_READBACK_SLOTS];
    private volatile long epoch;
    private long coverageSequence;
    private boolean requestedState;
    private boolean actualState;
    private long executionCount;
    private long bypassCount;
    private long coverageMeasurementCount;
    private long droppedCoverageReadbacks;
    private long sourceGeneration;
    private long sourceCameraRevision;
    private long currentCameraRevision;
    private long sourceAgeMicros;
    private int sourceWidth;
    private int sourceHeight;
    private int depthWidth;
    private int depthHeight;
    private int warpWidth;
    private int warpHeight;
    private int displayWidth;
    private int displayHeight;
    private float invalidCoverage = -1.0F;
    private String lastBypassReason = "disabled";

    DepthReprojectionStage() {
        for (int index = 0; index < coverageReadbacks.length; index++) {
            coverageReadbacks[index] = new CoverageReadbackSlot();
        }
    }

    Result apply(
            boolean requested,
            GpuTextureView sourceColor,
            GpuTextureView sourceDepth,
            NativeBridge.ReprojectionMetadata sourceMetadata,
            NativeBridge.CameraInput targetCamera,
            long targetCameraRevision,
            CyclesRenderSettings settings,
            int targetWidth,
            int targetHeight) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(sourceColor);
        requestedState = requested;
        try {
            pollCoverage();
        } catch (RuntimeException error) {
            recordBypass("coverage-readback:" + error.getClass().getSimpleName());
            releaseResources();
            return Result.bypassed(sourceColor, sourceDepth, lastBypassReason);
        }
        if (!requested) {
            actualState = false;
            lastBypassReason = "disabled";
            return Result.bypassed(sourceColor, sourceDepth, "disabled");
        }
        updateSourceTelemetry(
                sourceMetadata, sourceDepth, targetCameraRevision, System.nanoTime());
        displayWidth = targetWidth;
        displayHeight = targetHeight;
        String rejection = rejectionReason(
                sourceColor, sourceDepth, sourceMetadata, targetCamera,
                settings, targetWidth, targetHeight);
        if (!rejection.isEmpty()) {
            recordBypass(rejection);
            return Result.bypassed(sourceColor, sourceDepth, rejection);
        }

        try {
            int rasterWidth = sourceMetadata.width();
            int rasterHeight = sourceMetadata.height();
            warpWidth = rasterWidth;
            warpHeight = rasterHeight;
            ensureTargets(rasterWidth, rasterHeight);
            updateUniforms(
                    sourceMetadata, targetCamera, settings,
                    targetWidth, targetHeight, rasterWidth, rasterHeight);
            splat(sourceColor, sourceDepth, sourceMetadata.width(), sourceMetadata.height(),
                    rasterWidth, rasterHeight);
            GpuTextureView coverage = reduceCoverage(rasterWidth, rasterHeight);
            boolean coverageScheduled = captureCoverage(
                    coverage, sourceMetadata, sourceDepth, targetCameraRevision,
                    rasterWidth, rasterHeight, targetWidth, targetHeight,
                    System.nanoTime());
            resolve(sourceColor, sourceDepth, coverage, rasterWidth, rasterHeight);
            executionCount++;
            if (coverageMeasurementCount == 0L) {
                actualState = false;
                lastBypassReason = coverageScheduled
                        ? "coverage-pending" : "coverage-unmeasured";
            }
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
            recordBypass("gpu:" + error.getClass().getSimpleName()
                    + (detail == null || detail.isBlank() ? "" : ":" + detail));
            return Result.bypassed(
                    sourceColor,
                    sourceDepth,
                    lastBypassReason);
        }
    }

    void reset() {
        RenderSystem.assertOnRenderThread();
        releaseResources();
        clearTelemetry();
    }

    Telemetry telemetry() {
        int pending = 0;
        for (CoverageReadbackSlot slot : coverageReadbacks) {
            if (slot.inFlight) {
                pending++;
            }
        }
        return new Telemetry(
                requestedState,
                actualState,
                executionCount,
                bypassCount,
                coverageMeasurementCount,
                droppedCoverageReadbacks,
                pending,
                sourceGeneration,
                sourceCameraRevision,
                currentCameraRevision,
                sourceAgeMicros,
                sourceWidth,
                sourceHeight,
                depthWidth,
                depthHeight,
                warpWidth,
                warpHeight,
                displayWidth,
                displayHeight,
                invalidCoverage,
                lastBypassReason);
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
            int targetHeight,
            int rasterWidth,
            int rasterHeight) {
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
        putVector(targetNear, targetFar, rasterWidth, rasterHeight);
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

    private boolean captureCoverage(
            GpuTextureView coverage,
            NativeBridge.ReprojectionMetadata metadata,
            GpuTextureView sourceDepth,
            long targetRevision,
            int rasterWidth,
            int rasterHeight,
            int targetWidth,
            int targetHeight,
            long nowNanos) {
        CoverageReadbackSlot slot = availableCoverageReadback();
        if (slot == null) {
            droppedCoverageReadbacks++;
            return false;
        }
        int slotIndex = 0;
        while (coverageReadbacks[slotIndex] != slot) {
            slotIndex++;
        }
        if (slot.buffer == null) {
            int labelIndex = slotIndex;
            slot.buffer = RenderSystem.getDevice().createBuffer(
                    () -> "Cycles reprojection coverage readback " + labelIndex,
                    GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
                    COVERAGE_READBACK_BYTES);
        }
        long scheduledEpoch = epoch;
        long scheduledSequence = ++coverageSequence;
        slot.sequence = scheduledSequence;
        slot.inFlight = true;
        slot.ready = false;
        slot.sourceGeneration = metadata.generation();
        slot.sourceCameraRevision = metadata.cameraRevision();
        slot.currentCameraRevision = targetRevision;
        slot.productionTimeNanos = metadata.productionTimeNanos();
        slot.sourceWidth = metadata.width();
        slot.sourceHeight = metadata.height();
        slot.depthWidth = sourceDepth.getWidth(0);
        slot.depthHeight = sourceDepth.getHeight(0);
        slot.warpWidth = rasterWidth;
        slot.warpHeight = rasterHeight;
        slot.displayWidth = targetWidth;
        slot.displayHeight = targetHeight;
        try {
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.copyTextureToBuffer(
                    coverage.texture(),
                    Objects.requireNonNull(slot.buffer),
                    0L,
                    () -> completeCoverage(
                            slot, scheduledEpoch, scheduledSequence),
                    0);
        } catch (RuntimeException error) {
            slot.inFlight = false;
            throw error;
        }
        sourceAgeMicros = ageMicros(nowNanos, metadata.productionTimeNanos());
        return true;
    }

    private void pollCoverage() {
        CoverageReadbackSlot latest = null;
        for (CoverageReadbackSlot slot : coverageReadbacks) {
            if (slot.ready && (latest == null || slot.sequence > latest.sequence)) {
                latest = slot;
            }
        }
        if (latest == null) {
            return;
        }
        for (CoverageReadbackSlot slot : coverageReadbacks) {
            if (slot.ready && slot.sequence <= latest.sequence) {
                slot.ready = false;
            }
        }
        float validCount;
        float totalCount;
        try (GpuBufferSlice.MappedView mapped =
                     Objects.requireNonNull(latest.buffer).map(true, false)) {
            ByteBuffer data = mapped.data().order(ByteOrder.nativeOrder());
            validCount = data.getFloat();
            totalCount = data.getFloat();
        }
        float validCoverage = Float.isFinite(validCount)
                && Float.isFinite(totalCount) && totalCount > 0.0F
                ? Math.clamp(validCount / totalCount, 0.0F, 1.0F)
                : 0.0F;
        invalidCoverage = 1.0F - validCoverage;
        coverageMeasurementCount++;
        sourceGeneration = latest.sourceGeneration;
        sourceCameraRevision = latest.sourceCameraRevision;
        currentCameraRevision = latest.currentCameraRevision;
        sourceAgeMicros = ageMicros(System.nanoTime(), latest.productionTimeNanos);
        sourceWidth = latest.sourceWidth;
        sourceHeight = latest.sourceHeight;
        depthWidth = latest.depthWidth;
        depthHeight = latest.depthHeight;
        warpWidth = latest.warpWidth;
        warpHeight = latest.warpHeight;
        displayWidth = latest.displayWidth;
        displayHeight = latest.displayHeight;
        if (validCoverage >= MIN_VALID_COVERAGE) {
            actualState = true;
            lastBypassReason = "";
        } else {
            recordBypass("coverage");
        }
    }

    private CoverageReadbackSlot availableCoverageReadback() {
        for (CoverageReadbackSlot slot : coverageReadbacks) {
            if (!slot.inFlight && !slot.ready) {
                return slot;
            }
        }
        return null;
    }

    private void completeCoverage(
            CoverageReadbackSlot slot,
            long scheduledEpoch,
            long scheduledSequence) {
        if (slot.sequence == scheduledSequence) {
            slot.inFlight = false;
            if (scheduledEpoch == epoch && slot.buffer != null) {
                slot.ready = true;
            }
        }
    }

    private void updateSourceTelemetry(
            NativeBridge.ReprojectionMetadata metadata,
            GpuTextureView sourceDepth,
            long targetRevision,
            long nowNanos) {
        currentCameraRevision = targetRevision;
        if (metadata == null) {
            sourceGeneration = 0L;
            sourceCameraRevision = 0L;
            sourceAgeMicros = 0L;
            sourceWidth = 0;
            sourceHeight = 0;
            depthWidth = sourceDepth == null ? 0 : sourceDepth.getWidth(0);
            depthHeight = sourceDepth == null ? 0 : sourceDepth.getHeight(0);
            warpWidth = 0;
            warpHeight = 0;
            return;
        }
        sourceGeneration = metadata.generation();
        sourceCameraRevision = metadata.cameraRevision();
        sourceAgeMicros = ageMicros(nowNanos, metadata.productionTimeNanos());
        sourceWidth = metadata.width();
        sourceHeight = metadata.height();
        warpWidth = metadata.width();
        warpHeight = metadata.height();
        if (sourceDepth != null) {
            depthWidth = sourceDepth.getWidth(0);
            depthHeight = sourceDepth.getHeight(0);
        } else {
            depthWidth = 0;
            depthHeight = 0;
        }
    }

    private void recordBypass(String reason) {
        actualState = false;
        bypassCount++;
        lastBypassReason = reason;
    }

    private static long ageMicros(long nowNanos, long productionTimeNanos) {
        if (productionTimeNanos <= 0L || nowNanos <= productionTimeNanos) {
            return 0L;
        }
        return (nowNanos - productionTimeNanos) / 1_000L;
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
        epoch++;
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
        for (CoverageReadbackSlot slot : coverageReadbacks) {
            slot.sequence = 0L;
            slot.inFlight = false;
            slot.ready = false;
            if (slot.buffer != null) {
                slot.buffer.close();
                slot.buffer = null;
            }
        }
    }

    private void clearTelemetry() {
        requestedState = false;
        actualState = false;
        executionCount = 0L;
        bypassCount = 0L;
        coverageMeasurementCount = 0L;
        droppedCoverageReadbacks = 0L;
        sourceGeneration = 0L;
        sourceCameraRevision = 0L;
        currentCameraRevision = 0L;
        sourceAgeMicros = 0L;
        sourceWidth = 0;
        sourceHeight = 0;
        depthWidth = 0;
        depthHeight = 0;
        warpWidth = 0;
        warpHeight = 0;
        displayWidth = 0;
        displayHeight = 0;
        invalidCoverage = -1.0F;
        lastBypassReason = "disabled";
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

    record Telemetry(
            boolean requested,
            boolean actual,
            long executionCount,
            long bypassCount,
            long coverageMeasurementCount,
            long droppedCoverageReadbacks,
            int pendingCoverageReadbacks,
            long sourceGeneration,
            long sourceCameraRevision,
            long currentCameraRevision,
            long sourceAgeMicros,
            int sourceWidth,
            int sourceHeight,
            int depthWidth,
            int depthHeight,
            int warpWidth,
            int warpHeight,
            int displayWidth,
            int displayHeight,
            float invalidCoverage,
            String lastBypassReason) {
    }

    private static final class CoverageReadbackSlot {
        private GpuBuffer buffer;
        private volatile boolean inFlight;
        private volatile boolean ready;
        private long sequence;
        private long sourceGeneration;
        private long sourceCameraRevision;
        private long currentCameraRevision;
        private long productionTimeNanos;
        private int sourceWidth;
        private int sourceHeight;
        private int depthWidth;
        private int depthHeight;
        private int warpWidth;
        private int warpHeight;
        private int displayWidth;
        private int displayHeight;
    }
}

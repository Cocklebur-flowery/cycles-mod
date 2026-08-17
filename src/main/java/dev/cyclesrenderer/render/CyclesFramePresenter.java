package dev.cyclesrenderer.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.cyclesrenderer.config.CyclesRenderSettings;
import dev.cyclesrenderer.nativebridge.NativeBridge;
import dev.cyclesrenderer.perf.DisplayPerformanceProbe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.Optional;

public final class CyclesFramePresenter {
    private final AutomaticExposureStage automaticExposure = new AutomaticExposureStage();
    private final DepthReprojectionStage depthReprojection = new DepthReprojectionStage();
    private final PostDepthOfFieldStage postDepthOfField = new PostDepthOfFieldStage();
    private TextureTarget nativeFrameTarget;
    private boolean ready;
    private long uploadCount;
    private long uploadedBytes;
    private long generationGaps;
    private long lastGeneration;
    private long lastUploadMicros;
    private long emaUploadMicros;
    private long maxUploadMicros;
    private GpuTexture fallbackColorLut;
    private GpuTextureView fallbackColorLutView;
    private GpuTexture colorLutTexture;
    private GpuTextureView colorLutView;
    private NativeBridge.ColorLutDescriptor colorLutDescriptor;
    private int colorLutDisplayDevice = -1;
    private int colorLutViewTransform = -1;
    private int colorLutLook = -1;
    private int colorLutWorkingSpace = -1;
    private long colorLutUploadCount;
    private long colorLutUploadedBytes;
    private long lastColorLutUploadMicros;
    private long emaColorLutUploadMicros;
    private long maxColorLutUploadMicros;
    private GpuBuffer displayUniformBuffer;
    private final ByteBuffer displayUniformData = ByteBuffer.allocateDirect(112)
            .order(ByteOrder.nativeOrder());
    private long displaySettingsRevision = Long.MIN_VALUE;
    private int displayDepthFarBits;
    private int displayExposureBits;
    private HdrDisplayTransform.Selection displayTransform;

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
            float depthFar,
            DisplayPerformanceProbe performanceProbe) {
        RenderSystem.assertOnRenderThread();
        if (!ready || nativeFrameTarget == null) {
            return;
        }
        presentTexture(
                output,
                settings,
                depthFar,
                settings.focusDistance(),
                Objects.requireNonNull(nativeFrameTarget.getColorTextureView()),
                null,
                performanceProbe);
    }

    public void present(
            RenderTarget output,
            CyclesRenderSettings settings,
            float depthFar) {
        present(output, settings, depthFar, DisplayPerformanceProbe.NONE);
    }

    public void presentExternal(
            RenderTarget output,
            CyclesRenderSettings settings,
            float depthFar,
            GpuTextureView source,
            DisplayPerformanceProbe performanceProbe) {
        RenderSystem.assertOnRenderThread();
        presentTexture(
                output,
                settings,
                depthFar,
                settings.focusDistance(),
                Objects.requireNonNull(source),
                null,
                performanceProbe);
    }

    public void presentExternal(
            RenderTarget output,
            CyclesRenderSettings settings,
            float depthFar,
            float focusDistance,
            GpuTextureView source,
            GpuTextureView depth,
            DisplayPerformanceProbe performanceProbe) {
        RenderSystem.assertOnRenderThread();
        presentExternal(
                output,
                settings,
                depthFar,
                focusDistance,
                source,
                depth,
                false,
                null,
                null,
                performanceProbe);
    }

    public void presentExternal(
            RenderTarget output,
            CyclesRenderSettings settings,
            float depthFar,
            float focusDistance,
            GpuTextureView source,
            GpuTextureView depth,
            boolean reprojectionRequested,
            NativeBridge.ReprojectionMetadata sourceMetadata,
            NativeBridge.CameraInput targetCamera,
            DisplayPerformanceProbe performanceProbe) {
        RenderSystem.assertOnRenderThread();
        DepthReprojectionStage.Result reprojection = depthReprojection.apply(
                reprojectionRequested,
                Objects.requireNonNull(source),
                Objects.requireNonNull(depth),
                sourceMetadata,
                targetCamera,
                settings,
                output.width,
                output.height);
        presentTexture(
                output,
                settings,
                depthFar,
                focusDistance,
                reprojection.color(),
                reprojection.depth(),
                performanceProbe);
    }

    public void presentExternal(
            RenderTarget output,
            CyclesRenderSettings settings,
            float depthFar,
            GpuTextureView source) {
        presentExternal(output, settings, depthFar, source, DisplayPerformanceProbe.NONE);
    }

    private void presentTexture(
            RenderTarget output,
            CyclesRenderSettings settings,
            float depthFar,
            float focusDistance,
            GpuTextureView source,
            GpuTextureView depth,
            DisplayPerformanceProbe performanceProbe) {
        GpuTextureView displaySource = depth == null
                ? source
                : postDepthOfField.apply(
                        source,
                        depth,
                        settings,
                        focusDistance,
                        depthFar,
                        output.width,
                        output.height);
        long stageStart = performanceProbe.beginDisplayStage();
        HdrDisplayTransform.Selection outputTransform =
                HdrDisplayTransform.select(settings);
        GpuTextureView activeColorLut = updateColorLut(
                outputTransform.displayDevice(), outputTransform.viewTransform(),
                settings.colorLook(),
                settings.workingSpace());
        performanceProbe.endDisplayStage(
                DisplayPerformanceProbe.Stage.COLOR_LUT, stageStart);
        float effectiveExposureEv = automaticExposure.update(
                displaySource, settings, System.nanoTime());
        stageStart = performanceProbe.beginDisplayStage();
        updateDisplayUniforms(settings, outputTransform, depthFar, effectiveExposureEv);
        performanceProbe.endDisplayStage(
                DisplayPerformanceProbe.Stage.UNIFORMS, stageStart);
        stageStart = performanceProbe.beginDisplayStage();
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
                    displaySource,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            renderPass.bindTexture(
                    CyclesRenderPipelines.COLOR_LUT_SAMPLER,
                    activeColorLut,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            renderPass.setUniform(
                    CyclesRenderPipelines.DISPLAY_UNIFORM,
                    Objects.requireNonNull(displayUniformBuffer));
            renderPass.draw(3, 1, 0, 0);
        }
        performanceProbe.endDisplayStage(
                DisplayPerformanceProbe.Stage.RENDER_PASS, stageStart);
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
                maxUploadMicros,
                colorLutUploadCount,
                colorLutUploadedBytes,
                lastColorLutUploadMicros,
                emaColorLutUploadMicros,
                maxColorLutUploadMicros,
                colorLutViewTransform);
    }

    public AutomaticExposureStage.Telemetry automaticExposureTelemetry() {
        return automaticExposure.telemetry();
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
        colorLutUploadCount = 0L;
        colorLutUploadedBytes = 0L;
        lastColorLutUploadMicros = 0L;
        emaColorLutUploadMicros = 0L;
        maxColorLutUploadMicros = 0L;
        colorLutViewTransform = -1;
        colorLutDisplayDevice = -1;
        colorLutLook = -1;
        colorLutWorkingSpace = -1;
        colorLutDescriptor = null;
        displaySettingsRevision = Long.MIN_VALUE;
        displayDepthFarBits = 0;
        displayExposureBits = 0;
        displayTransform = null;
        automaticExposure.reset();
        depthReprojection.reset();
        postDepthOfField.reset();
        if (nativeFrameTarget != null) {
            nativeFrameTarget.destroyBuffers();
            nativeFrameTarget = null;
        }
        if (displayUniformBuffer != null) {
            displayUniformBuffer.close();
            displayUniformBuffer = null;
        }
        closeColorLut();
        if (fallbackColorLutView != null) {
            fallbackColorLutView.close();
            fallbackColorLutView = null;
        }
        if (fallbackColorLut != null) {
            fallbackColorLut.close();
            fallbackColorLut = null;
        }
    }

    private GpuTextureView updateColorLut(
            CyclesRenderSettings.DisplayDevice displayDevice,
            CyclesRenderSettings.ViewTransform viewTransform,
            CyclesRenderSettings.ColorLook colorLook,
            CyclesRenderSettings.WorkingSpace workingSpace) {
        ensureFallbackColorLut();
        CyclesRenderSettings.ViewTransform effectiveView =
                viewTransform.effectiveFor(displayDevice);
        if (effectiveView == CyclesRenderSettings.ViewTransform.RAW) {
            return Objects.requireNonNull(fallbackColorLutView);
        }
        int effectiveLook = colorLook.effectiveNativeId(effectiveView);
        if (colorLutView != null
                && colorLutDisplayDevice == displayDevice.nativeId()
                && colorLutViewTransform == effectiveView.nativeId()
                && colorLutLook == effectiveLook
                && colorLutWorkingSpace == workingSpace.nativeId()) {
            return colorLutView;
        }
        NativeBridge.Capabilities capabilities = NativeBridge.capabilities();
        if (!capabilities.supportsViewTransform(effectiveView)) {
            throw new IllegalStateException(
                    "native OCIO view transform is unavailable: " + effectiveView);
        }

        long uploadStart = System.nanoTime();
        NativeBridge.ColorLut colorLut = NativeBridge.colorLut(
                displayDevice, effectiveView, colorLook, workingSpace);
        NativeBridge.ColorLutDescriptor descriptor = colorLut.descriptor();
        validateColorLut(descriptor);
        if (descriptor.displayDevice() != displayDevice.nativeId()) {
            throw new IllegalStateException(
                    "native color LUT display mismatch: " + descriptor.displayDevice()
                            + " != " + displayDevice.nativeId());
        }
        if (descriptor.viewTransform() != effectiveView.nativeId()) {
            throw new IllegalStateException(
                    "native color LUT view mismatch: " + descriptor.viewTransform()
                            + " != " + effectiveView.nativeId());
        }
        if (descriptor.colorLook() != effectiveLook) {
            throw new IllegalStateException(
                    "native color LUT look mismatch: " + descriptor.colorLook()
                            + " != " + effectiveLook);
        }
        if (descriptor.workingSpace() != workingSpace.nativeId()) {
            throw new IllegalStateException(
                    "native color LUT working-space mismatch: " + descriptor.workingSpace()
                            + " != " + workingSpace.nativeId());
        }
        GpuTexture nextTexture = RenderSystem.getDevice().createTexture(
                "Cycles " + displayDevice + " " + effectiveView + " color LUT",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA32_FLOAT,
                descriptor.width(),
                descriptor.height(),
                1,
                1);
        GpuTextureView nextView = null;
        try {
            nextView = RenderSystem.getDevice().createTextureView(nextTexture);
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(
                    nextTexture,
                    colorLut.pixels(),
                    0,
                    0,
                    0,
                    0,
                    descriptor.width(),
                    descriptor.height());
        } catch (RuntimeException error) {
            if (nextView != null) {
                nextView.close();
            }
            nextTexture.close();
            throw error;
        }
        closeColorLut();
        colorLutTexture = nextTexture;
        colorLutView = nextView;
        colorLutDescriptor = descriptor;
        colorLutDisplayDevice = displayDevice.nativeId();
        colorLutViewTransform = effectiveView.nativeId();
        colorLutLook = effectiveLook;
        colorLutWorkingSpace = workingSpace.nativeId();
        lastColorLutUploadMicros = nanosToMicros(System.nanoTime() - uploadStart);
        emaColorLutUploadMicros = updateEma(
                emaColorLutUploadMicros, lastColorLutUploadMicros);
        maxColorLutUploadMicros = Math.max(
                maxColorLutUploadMicros, lastColorLutUploadMicros);
        colorLutUploadCount++;
        colorLutUploadedBytes += descriptor.pixelByteCount();
        return Objects.requireNonNull(colorLutView);
    }

    private void ensureFallbackColorLut() {
        if (fallbackColorLutView != null) {
            return;
        }
        ByteBuffer fallback = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        fallback.putFloat(0.0F).putFloat(0.0F).putFloat(0.0F).putFloat(1.0F).flip();
        GpuTexture nextTexture = RenderSystem.getDevice().createTexture(
                "Cycles fallback color LUT",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA32_FLOAT,
                1,
                1,
                1,
                1);
        GpuTextureView nextView = null;
        try {
            nextView = RenderSystem.getDevice().createTextureView(nextTexture);
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(
                    nextTexture, fallback, 0, 0, 0, 0, 1, 1);
        } catch (RuntimeException error) {
            if (nextView != null) {
                nextView.close();
            }
            nextTexture.close();
            throw error;
        }
        fallbackColorLut = nextTexture;
        fallbackColorLutView = nextView;
    }

    private static void validateColorLut(NativeBridge.ColorLutDescriptor descriptor) {
        long expectedBytes = Math.multiplyExact(
                Math.multiplyExact((long) descriptor.width(), descriptor.height()), 16L);
        if (descriptor.edgeLength() < 2
                || descriptor.width() != descriptor.edgeLength() * descriptor.edgeLength()
                || descriptor.height() != descriptor.edgeLength()
                || descriptor.pixelFormat() != NativeBridge.PIXEL_FORMAT_RGBA32_FLOAT
                || descriptor.pixelByteCount() != expectedBytes
                || descriptor.shaperLog2Max() <= descriptor.shaperLog2Min()
                || descriptor.shaperEpsilon() <= 0.0F) {
            throw new IllegalStateException("invalid native color LUT descriptor: " + descriptor);
        }
    }

    private void closeColorLut() {
        if (colorLutView != null) {
            colorLutView.close();
            colorLutView = null;
        }
        if (colorLutTexture != null) {
            colorLutTexture.close();
            colorLutTexture = null;
        }
    }

    private void updateDisplayUniforms(
            CyclesRenderSettings settings,
            HdrDisplayTransform.Selection outputTransform,
            float depthFar,
            float effectiveExposureEv) {
        int depthBits = Float.floatToIntBits(depthFar);
        int exposureBits = Float.floatToIntBits(effectiveExposureEv);
        if (displayUniformBuffer != null
                && displaySettingsRevision == settings.revision()
                && displayDepthFarBits == depthBits
                && displayExposureBits == exposureBits
                && outputTransform.equals(displayTransform)) {
            return;
        }
        if (displayUniformBuffer == null) {
            displayUniformBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "Cycles display settings",
                    GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM,
                    112L);
        }
        displayUniformData.clear();
        displayUniformData.putFloat((float) Math.pow(2.0, effectiveExposureEv));
        displayUniformData.putFloat(1.0F / settings.gamma());
        displayUniformData.putFloat(Math.max(depthFar, 1.0F));
        displayUniformData.putFloat(Math.max(settings.stillSamples(), 1));
        displayUniformData.putInt(settings.activePass().nativeId());
        CyclesRenderSettings.ViewTransform effectiveView = outputTransform.viewTransform();
        displayUniformData.putInt(effectiveView.nativeId());
        displayUniformData.putInt(outputTransform.pqEncoded() ? 1 : 0);
        displayUniformData.putInt(0);
        NativeBridge.ColorLutDescriptor descriptor = colorLutDescriptor;
        if (effectiveView != CyclesRenderSettings.ViewTransform.RAW
                && descriptor != null) {
            displayUniformData.putFloat(descriptor.edgeLength());
            displayUniformData.putFloat(descriptor.shaperLog2Min());
            displayUniformData.putFloat(
                    1.0F / (descriptor.shaperLog2Max() - descriptor.shaperLog2Min()));
            displayUniformData.putFloat(descriptor.shaperEpsilon());
        } else {
            displayUniformData.putFloat(1.0F);
            displayUniformData.putFloat(0.0F);
            displayUniformData.putFloat(1.0F);
            displayUniformData.putFloat(1.0F);
        }
        float[] whiteBalance = WhiteBalanceTransform.matrix(
                settings.workingSpace(),
                settings.whiteBalance(),
                settings.whiteBalanceTemperature(),
                settings.whiteBalanceTint());
        for (int row = 0; row < 3; row++) {
            displayUniformData.putFloat(whiteBalance[row * 3]);
            displayUniformData.putFloat(whiteBalance[row * 3 + 1]);
            displayUniformData.putFloat(whiteBalance[row * 3 + 2]);
            displayUniformData.putFloat(0.0F);
        }
        displayUniformData.putFloat(HdrDisplayTransform.pqToPaperWhiteScale());
        displayUniformData.putFloat(HdrDisplayTransform.paperWhiteNits());
        displayUniformData.putFloat(0.0F);
        displayUniformData.putFloat(0.0F);
        displayUniformData.flip();
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(
                displayUniformBuffer.slice(),
                displayUniformData);
        displaySettingsRevision = settings.revision();
        displayDepthFarBits = depthBits;
        displayExposureBits = exposureBits;
        displayTransform = outputTransform;
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
            long maxUploadMicros,
            long colorLutUploadCount,
            long colorLutUploadedBytes,
            long lastColorLutUploadMicros,
            long emaColorLutUploadMicros,
            long maxColorLutUploadMicros,
            int colorLutViewTransform) {
    }
}

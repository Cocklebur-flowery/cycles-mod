package dev.cyclesrenderer.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import dev.cyclesrenderer.nativebridge.NativeBridge;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.Objects;
import java.util.Optional;

public final class CyclesFramePresenter {
    private TextureTarget nativeFrameTarget;
    private boolean ready;

    public void update(NativeBridge.RenderedFrame frame) {
        RenderSystem.assertOnRenderThread();
        if (!frame.updated()) {
            return;
        }
        if (frame.pixels() == null || frame.width() <= 0 || frame.height() <= 0) {
            throw new IllegalArgumentException("updated native frame has no pixel data");
        }
        if (nativeFrameTarget == null) {
            nativeFrameTarget = new TextureTarget(
                    "Cycles native frame",
                    frame.width(),
                    frame.height(),
                    false,
                    GpuFormat.RGBA8_UNORM);
        } else if (nativeFrameTarget.width != frame.width()
                || nativeFrameTarget.height != frame.height()) {
            nativeFrameTarget.resize(frame.width(), frame.height());
        }
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(
                Objects.requireNonNull(nativeFrameTarget.getColorTexture()),
                frame.pixels(),
                0,
                0,
                0,
                0,
                frame.width(),
                frame.height());
        ready = true;
    }

    public void present(RenderTarget output) {
        RenderSystem.assertOnRenderThread();
        if (!ready || nativeFrameTarget == null) {
            return;
        }
        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(
                        () -> "Cycles frame upscale",
                        Objects.requireNonNull(output.getColorTextureView()),
                        Optional.empty())) {
            renderPass.setPipeline(RenderPipelines.TRACY_BLIT);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.bindTexture(
                    "InSampler",
                    Objects.requireNonNull(nativeFrameTarget.getColorTextureView()),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            renderPass.draw(3, 1, 0, 0);
        }
    }

    public boolean hasFrame() {
        return ready && nativeFrameTarget != null;
    }

    public void reset() {
        RenderSystem.assertOnRenderThread();
        ready = false;
        if (nativeFrameTarget != null) {
            nativeFrameTarget.destroyBuffers();
            nativeFrameTarget = null;
        }
    }
}

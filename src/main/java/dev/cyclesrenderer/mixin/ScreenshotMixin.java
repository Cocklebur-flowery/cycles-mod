package dev.cyclesrenderer.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.cyclesrenderer.render.HdrOutputStage;
import net.minecraft.client.Screenshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Screenshot.class)
public abstract class ScreenshotMixin {
    @Redirect(
            method = "takeScreenshot(Lcom/mojang/blaze3d/pipeline/RenderTarget;ILjava/util/function/Consumer;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;getColorTexture()Lcom/mojang/blaze3d/textures/GpuTexture;"))
    private static GpuTexture cyclesrenderer$prepareSdrCapture(RenderTarget target) {
        return HdrOutputStage.prepareSdrCapture(target);
    }
}

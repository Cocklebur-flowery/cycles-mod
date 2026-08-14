package dev.cyclesrenderer.mixin;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.MainTarget;
import dev.cyclesrenderer.render.HdrRenderTargetPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(MainTarget.class)
public abstract class MainTargetFormatMixin {
    @ModifyArg(
            method = "<init>(IIZ)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;<init>(Ljava/lang/String;ZZLcom/mojang/blaze3d/GpuFormat;)V"),
            index = 3)
    private static GpuFormat cyclesrenderer$selectMainTargetFormat(GpuFormat original) {
        return HdrRenderTargetPolicy.colorFormat(original);
    }
}

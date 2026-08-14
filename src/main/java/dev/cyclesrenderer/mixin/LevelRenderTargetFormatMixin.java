package dev.cyclesrenderer.mixin;

import com.mojang.blaze3d.GpuFormat;
import dev.cyclesrenderer.render.HdrRenderTargetPolicy;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(LevelRenderer.class)
public abstract class LevelRenderTargetFormatMixin {
    @ModifyArg(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/resource/RenderTargetDescriptor;<init>(IIZZLorg/joml/Vector4fc;Lcom/mojang/blaze3d/GpuFormat;)V"),
            index = 5)
    private GpuFormat cyclesrenderer$selectScreenTargetFormat(GpuFormat original) {
        return HdrRenderTargetPolicy.colorFormat(original);
    }
}

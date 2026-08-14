package dev.cyclesrenderer.mixin;

import com.mojang.blaze3d.GpuFormat;
import dev.cyclesrenderer.render.HdrRenderTargetPolicy;
import net.minecraft.client.renderer.PostChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(PostChain.class)
public abstract class PostChainFormatMixin {
    @ModifyArg(
            method = "addToFrame",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/resource/RenderTargetDescriptor;<init>(IIZLorg/joml/Vector4fc;Lcom/mojang/blaze3d/GpuFormat;)V"),
            index = 4)
    private GpuFormat cyclesrenderer$selectPostTargetFormat(GpuFormat original) {
        return HdrRenderTargetPolicy.colorFormat(original);
    }
}

package dev.cyclesrenderer.mixin;

import com.mojang.blaze3d.textures.GpuTextureView;
import dev.cyclesrenderer.render.HdrOutputStage;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @ModifyArg(
            method = "runTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/GpuSurface;blitFromTexture(Lcom/mojang/blaze3d/systems/CommandEncoder;Lcom/mojang/blaze3d/textures/GpuTextureView;)V"),
            index = 1)
    private GpuTextureView cyclesrenderer$prepareHdrOutput(GpuTextureView source) {
        return HdrOutputStage.prepare(source);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void cyclesrenderer$closeHdrOutput(CallbackInfo callback) {
        HdrOutputStage.close();
    }
}

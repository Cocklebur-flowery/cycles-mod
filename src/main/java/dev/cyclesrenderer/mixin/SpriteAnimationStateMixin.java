package dev.cyclesrenderer.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import dev.cyclesrenderer.scene.LabPbrAnimationFrames;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpriteContents.AnimationState.class)
abstract class SpriteAnimationStateMixin {
    @Unique
    private SpriteContents cyclesrenderer$contents;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void cyclesrenderer$captureContents(
            CallbackInfo callback,
            @Local(argsOnly = true, index = 1) SpriteContents contents) {
        cyclesrenderer$contents = contents;
    }

    @Inject(
            method = "drawToAtlas",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderPass;setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V"))
    private void cyclesrenderer$recordImageFrame(
            RenderPass renderPass,
            GpuBufferSlice spriteUbo,
            CallbackInfo callback,
            @Local(ordinal = 0) int imageFrame) {
        LabPbrAnimationFrames.record(cyclesrenderer$contents, imageFrame);
    }
}

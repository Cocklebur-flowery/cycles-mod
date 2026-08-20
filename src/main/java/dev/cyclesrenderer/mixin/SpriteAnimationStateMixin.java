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

    @Unique
    private int cyclesrenderer$nextImageFrame;

    @Unique
    private boolean cyclesrenderer$interpolated;

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
                    target = "Lcom/mojang/blaze3d/systems/RenderPass;bindTexture(Ljava/lang/String;Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuSampler;)V",
                    ordinal = 1))
    private void cyclesrenderer$captureNextImageFrame(
            RenderPass renderPass,
            GpuBufferSlice spriteUbo,
            CallbackInfo callback,
            @Local(ordinal = 2) int nextImageFrame) {
        cyclesrenderer$nextImageFrame = nextImageFrame;
        cyclesrenderer$interpolated = true;
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
            @Local(ordinal = 0) int currentImageFrame,
            @Local(ordinal = 1) int frameProgressAsInt) {
        LabPbrAnimationFrames.record(
                cyclesrenderer$contents,
                currentImageFrame,
                cyclesrenderer$interpolated ? cyclesrenderer$nextImageFrame : currentImageFrame,
                cyclesrenderer$interpolated,
                frameProgressAsInt);
    }
}

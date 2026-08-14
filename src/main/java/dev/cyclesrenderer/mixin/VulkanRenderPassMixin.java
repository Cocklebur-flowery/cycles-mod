package dev.cyclesrenderer.mixin;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import dev.cyclesrenderer.render.VulkanAttachmentFormatContext;
import dev.cyclesrenderer.render.VulkanPipelineFormatVariants;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(VulkanRenderPass.class)
abstract class VulkanRenderPassMixin {
    @Unique
    private List<GpuFormat> cyclesrenderer$colorAttachmentFormats = List.of();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void cyclesrenderer$takeColorAttachmentFormats(CallbackInfo callback) {
        cyclesrenderer$colorAttachmentFormats = VulkanAttachmentFormatContext.take();
    }

    @ModifyArg(
            method = "setPipeline",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vulkan/VulkanDevice;getOrCompilePipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)Lcom/mojang/blaze3d/vulkan/VulkanRenderPipeline;"),
            index = 0)
    private RenderPipeline cyclesrenderer$specializePipelineFormat(RenderPipeline original) {
        return VulkanPipelineFormatVariants.specialize(
                original, cyclesrenderer$colorAttachmentFormats);
    }
}

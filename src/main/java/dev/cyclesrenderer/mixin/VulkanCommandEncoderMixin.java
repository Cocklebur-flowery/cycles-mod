package dev.cyclesrenderer.mixin;

import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import dev.cyclesrenderer.render.VulkanAttachmentFormatContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VulkanCommandEncoder.class)
abstract class VulkanCommandEncoderMixin {
    @Inject(method = "createRenderPass", at = @At("HEAD"))
    private void cyclesrenderer$captureColorAttachmentFormats(
            RenderPassDescriptor descriptor,
            CallbackInfoReturnable<RenderPassBackend> callback) {
        VulkanAttachmentFormatContext.capture(descriptor);
    }
}

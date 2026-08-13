package dev.cyclesrenderer.mixin;

import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import dev.cyclesrenderer.render.VulkanCapabilityProbe;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VulkanGpuSurface.class)
public abstract class VulkanGpuSurfaceMixin {
    @Unique
    private int cyclesrenderer$swapchainColorSpace;

    @Inject(
            method = "pickSwapchainSurfaceFormat",
            at = @At("HEAD"),
            cancellable = true)
    private void cyclesrenderer$pickHdrSurfaceFormat(
            VkSurfaceFormatKHR.Buffer formats,
            CallbackInfoReturnable<VkSurfaceFormatKHR> callback) {
        VkSurfaceFormatKHR candidate =
                VulkanCapabilityProbe.pickExperimentalHdrSurfaceFormat(formats);
        if (candidate != null) {
            callback.setReturnValue(candidate);
        }
    }

    @Inject(method = "pickSwapchainSurfaceFormat", at = @At("RETURN"))
    private void cyclesrenderer$captureSurfaceFormat(
            VkSurfaceFormatKHR.Buffer formats,
            CallbackInfoReturnable<VkSurfaceFormatKHR> callback) {
        VkSurfaceFormatKHR selected = callback.getReturnValue();
        cyclesrenderer$swapchainColorSpace = selected.colorSpace();
        VulkanCapabilityProbe.recordSelectedSwapchainSurfaceFormat(
                selected.format(), selected.colorSpace());
    }

    @ModifyArg(
            method = "configure",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;imageColorSpace(I)Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;"),
            index = 0)
    private int cyclesrenderer$useSelectedSwapchainColorSpace(int original) {
        return cyclesrenderer$swapchainColorSpace;
    }
}

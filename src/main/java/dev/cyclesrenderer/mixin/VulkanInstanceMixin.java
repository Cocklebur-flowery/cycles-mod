package dev.cyclesrenderer.mixin;

import com.mojang.blaze3d.vulkan.VulkanInstance;
import dev.cyclesrenderer.render.VulkanCapabilityProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.Set;

@Mixin(VulkanInstance.class)
public abstract class VulkanInstanceMixin {
    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vulkan/VulkanDebug;create(IZLjava/util/Set;Ljava/util/Set;)Lcom/mojang/blaze3d/vulkan/VulkanDebug;"),
            index = 3)
    private Set<String> cyclesrenderer$enableSwapchainColorspace(
            Set<String> enabledExtensions) {
        return VulkanCapabilityProbe.withRequestedSwapchainColorspaceExtension(
                enabledExtensions);
    }
}

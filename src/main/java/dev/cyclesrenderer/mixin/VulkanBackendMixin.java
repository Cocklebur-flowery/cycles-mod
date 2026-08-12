package dev.cyclesrenderer.mixin;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import dev.cyclesrenderer.render.VulkanCapabilityProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.Collection;

@Mixin(VulkanBackend.class)
public abstract class VulkanBackendMixin {
    @ModifyArgs(
            method = "createDevice",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vulkan/VulkanBackend;createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;"))
    private void cyclesrenderer$enableInteropDeviceExtensions(Args args) {
        Collection<String> requestedExtensions = args.get(0);
        VulkanPhysicalDevice physicalDevice = args.get(1);
        args.set(0, VulkanCapabilityProbe.withRequestedInteropExtensions(
                requestedExtensions,
                physicalDevice));
    }
}

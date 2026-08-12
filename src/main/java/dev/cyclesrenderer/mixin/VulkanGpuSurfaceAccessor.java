package dev.cyclesrenderer.mixin;

import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VulkanGpuSurface.class)
public interface VulkanGpuSurfaceAccessor {
    @Accessor("surface")
    long cyclesrenderer$getSurface();
}

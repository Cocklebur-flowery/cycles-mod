package dev.cyclesrenderer.mixin;

import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GpuSurface.class)
public interface GpuSurfaceAccessor {
    @Accessor("backend")
    GpuSurfaceBackend cyclesrenderer$getBackend();
}

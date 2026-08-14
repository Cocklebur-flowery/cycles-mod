package dev.cyclesrenderer.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.cyclesrenderer.mixin.GpuDeviceAccessor;

public final class HdrRenderTargetPolicy {
    private static volatile boolean fp16TargetsActive;

    private HdrRenderTargetPolicy() {
    }

    public static GpuFormat colorFormat(GpuFormat original) {
        if (original != GpuFormat.RGBA8_UNORM
                || !VulkanCapabilityProbe.swapchainBootstrap().requested()) {
            return original;
        }

        try {
            GpuDeviceBackend backend =
                    ((GpuDeviceAccessor) (Object) RenderSystem.getDevice())
                            .cyclesrenderer$getBackend();
            if (backend instanceof VulkanDevice) {
                fp16TargetsActive = true;
                return GpuFormat.RGBA16_FLOAT;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Device creation is deliberately a hard boundary for HDR activation.
        }
        return original;
    }

    public static boolean fp16TargetsActive() {
        return fp16TargetsActive;
    }
}

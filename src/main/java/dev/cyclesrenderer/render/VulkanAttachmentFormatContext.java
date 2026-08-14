package dev.cyclesrenderer.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderPassDescriptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class VulkanAttachmentFormatContext {
    private static final ThreadLocal<List<GpuFormat>> CURRENT = new ThreadLocal<>();

    private VulkanAttachmentFormatContext() {
    }

    public static void capture(RenderPassDescriptor descriptor) {
        var attachments = descriptor.colorAttachments();
        List<GpuFormat> formats = new ArrayList<>(attachments.size());
        for (RenderPassDescriptor.Attachment<?> attachment : attachments) {
            formats.add(attachment == null
                    ? null
                    : attachment.textureView().texture().getFormat());
        }
        CURRENT.set(Collections.unmodifiableList(formats));
    }

    public static List<GpuFormat> take() {
        List<GpuFormat> formats = CURRENT.get();
        CURRENT.remove();
        return formats == null ? List.of() : formats;
    }
}

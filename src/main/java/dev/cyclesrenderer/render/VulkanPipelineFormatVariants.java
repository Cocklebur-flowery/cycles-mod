package dev.cyclesrenderer.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class VulkanPipelineFormatVariants {
    private static final Map<RenderPipeline, Map<List<GpuFormat>, RenderPipeline>> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private VulkanPipelineFormatVariants() {
    }

    public static RenderPipeline specialize(
            RenderPipeline original,
            List<GpuFormat> attachmentFormats) {
        if (!requiresVariant(original, attachmentFormats)) {
            return original;
        }

        List<GpuFormat> key = immutableCopy(attachmentFormats);
        synchronized (CACHE) {
            return CACHE.computeIfAbsent(original, ignored -> new java.util.HashMap<>())
                    .computeIfAbsent(key, ignored -> rebuild(original, key));
        }
    }

    public static int variantCount() {
        synchronized (CACHE) {
            return CACHE.values().stream().mapToInt(Map::size).sum();
        }
    }

    private static boolean requiresVariant(
            RenderPipeline pipeline,
            List<GpuFormat> attachmentFormats) {
        ColorTargetState[] states = pipeline.getColorTargetStates();
        if (states == null || states.length != attachmentFormats.size()) {
            return false;
        }

        boolean different = false;
        for (int index = 0; index < states.length; index++) {
            ColorTargetState state = states[index];
            GpuFormat actual = attachmentFormats.get(index);
            if ((state == null) != (actual == null)) {
                return false;
            }
            if (state != null && state.format() != actual) {
                if (state.format() != GpuFormat.RGBA8_UNORM
                        || actual != GpuFormat.RGBA16_FLOAT) {
                    return false;
                }
                different = true;
            }
        }
        return different;
    }

    private static RenderPipeline rebuild(
            RenderPipeline original,
            List<GpuFormat> attachmentFormats) {
        RenderPipeline.Builder builder = original.toBuilder();
        ColorTargetState[] states = original.getColorTargetStates();
        for (int index = 0; index < states.length; index++) {
            ColorTargetState state = states[index];
            if (state == null) {
                builder.withUnusedColorTargetState(index);
            } else {
                builder.withColorTargetState(index, new ColorTargetState(
                        state.blendFunction(),
                        attachmentFormats.get(index),
                        state.writeMask()));
            }
        }
        return builder.build();
    }

    private static List<GpuFormat> immutableCopy(List<GpuFormat> formats) {
        return Collections.unmodifiableList(new ArrayList<>(formats));
    }
}

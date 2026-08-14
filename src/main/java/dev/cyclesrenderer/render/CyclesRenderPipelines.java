package dev.cyclesrenderer.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import dev.cyclesrenderer.CyclesRendererMod;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

import java.util.Optional;

public final class CyclesRenderPipelines {
    public static final String DISPLAY_UNIFORM = "CyclesDisplay";
    public static final String COLOR_LUT_SAMPLER = "ColorLutSampler";
    public static final String HDR_OUTPUT_UNIFORM = "HdrOutput";

    private static final BindGroupLayout DISPLAY_LAYOUT = BindGroupLayout.builder()
            .withSampler(COLOR_LUT_SAMPLER)
            .withUniform(DISPLAY_UNIFORM, UniformType.UNIFORM_BUFFER)
            .build();

    private static final BindGroupLayout HDR_OUTPUT_LAYOUT = BindGroupLayout.builder()
            .withUniform(HDR_OUTPUT_UNIFORM, UniformType.UNIFORM_BUFFER)
            .build();

    public static final RenderPipeline PRESENT = RenderPipeline.builder(
                    RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(id("pipeline/present"))
            .withVertexShader(id("core/screenquad"))
            .withFragmentShader(id("core/present"))
            .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
            .withBindGroupLayout(DISPLAY_LAYOUT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build();

    public static final RenderPipeline SCRGB_OUTPUT = RenderPipeline.builder(
                    RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(id("pipeline/scrgb_output"))
            .withVertexShader(id("core/screenquad"))
            .withFragmentShader(id("core/scrgb_output"))
            .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
            .withBindGroupLayout(HDR_OUTPUT_LAYOUT)
            .withColorTargetState(new ColorTargetState(
                    Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build();

    public static final RenderPipeline EXPOSURE_METER = RenderPipeline.builder(
                    RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(id("pipeline/exposure_meter"))
            .withVertexShader(id("core/screenquad"))
            .withFragmentShader(id("core/exposure_meter"))
            .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
            .withColorTargetState(new ColorTargetState(
                    Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build();

    private CyclesRenderPipelines() {
    }

    public static void register(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(PRESENT);
        event.registerPipeline(SCRGB_OUTPUT);
        event.registerPipeline(EXPOSURE_METER);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(CyclesRendererMod.MOD_ID, path);
    }
}

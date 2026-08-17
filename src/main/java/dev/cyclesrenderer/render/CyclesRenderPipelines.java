package dev.cyclesrenderer.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
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
    public static final String DEPTH_SAMPLER = "DepthSampler";
    public static final String POST_DOF_UNIFORM = "PostDepthOfField";
    public static final String REPROJECTION_UNIFORM = "DepthReprojection";
    public static final String REPROJECTED_SAMPLER = "ReprojectedSampler";
    public static final String REPROJECTED_DEPTH_SAMPLER = "ReprojectedDepthSampler";
    public static final String REPROJECTION_COVERAGE_SAMPLER = "ReprojectionCoverageSampler";

    private static final BindGroupLayout DISPLAY_LAYOUT = BindGroupLayout.builder()
            .withSampler(COLOR_LUT_SAMPLER)
            .withUniform(DISPLAY_UNIFORM, UniformType.UNIFORM_BUFFER)
            .build();

    private static final BindGroupLayout HDR_OUTPUT_LAYOUT = BindGroupLayout.builder()
            .withUniform(HDR_OUTPUT_UNIFORM, UniformType.UNIFORM_BUFFER)
            .build();

    private static final BindGroupLayout POST_DOF_LAYOUT = BindGroupLayout.builder()
            .withSampler(DEPTH_SAMPLER)
            .withUniform(POST_DOF_UNIFORM, UniformType.UNIFORM_BUFFER)
            .build();

    private static final BindGroupLayout REPROJECTION_SPLAT_LAYOUT = BindGroupLayout.builder()
            .withSampler(DEPTH_SAMPLER)
            .withUniform(REPROJECTION_UNIFORM, UniformType.UNIFORM_BUFFER)
            .build();

    private static final BindGroupLayout REPROJECTION_RESOLVE_LAYOUT = BindGroupLayout.builder()
            .withSampler(DEPTH_SAMPLER)
            .withSampler(REPROJECTED_SAMPLER)
            .withSampler(REPROJECTED_DEPTH_SAMPLER)
            .withSampler(REPROJECTION_COVERAGE_SAMPLER)
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

    public static final RenderPipeline SDR_OUTPUT = RenderPipeline.builder(
                    RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(id("pipeline/sdr_output"))
            .withVertexShader(id("core/screenquad"))
            .withFragmentShader(id("core/sdr_output"))
            .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
            .withColorTargetState(new ColorTargetState(
                    Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
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

    public static final RenderPipeline POST_DEPTH_OF_FIELD = RenderPipeline.builder(
                    RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(id("pipeline/post_depth_of_field"))
            .withVertexShader(id("core/screenquad"))
            .withFragmentShader(id("core/post_depth_of_field"))
            .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
            .withBindGroupLayout(POST_DOF_LAYOUT)
            .withColorTargetState(new ColorTargetState(
                    Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build();

    public static final RenderPipeline DEPTH_REPROJECTION_SPLAT = RenderPipeline.builder(
                    RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(id("pipeline/depth_reprojection_splat"))
            .withVertexShader(id("core/depth_reprojection"))
            .withFragmentShader(id("core/depth_reprojection"))
            .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
            .withBindGroupLayout(REPROJECTION_SPLAT_LAYOUT)
            .withColorTargetState(0, new ColorTargetState(
                    Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
            .withColorTargetState(1, new ColorTargetState(
                    Optional.empty(), GpuFormat.R32_FLOAT, ColorTargetState.WRITE_ALL))
            .withDepthStencilState(new DepthStencilState(
                    CompareOp.GREATER_THAN_OR_EQUAL, true))
            .withCull(false)
            .withPrimitiveTopology(PrimitiveTopology.POINTS)
            .build();

    public static final RenderPipeline DEPTH_REPROJECTION_COVERAGE_DEPTH =
            RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
                    .withLocation(id("pipeline/depth_reprojection_coverage_depth"))
                    .withVertexShader(id("core/screenquad"))
                    .withFragmentShader(id("core/depth_reprojection_resolve"))
                    .withShaderDefine("COVERAGE_REDUCTION")
                    .withShaderDefine("COVERAGE_SOURCE_DEPTH")
                    .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
                    .withColorTargetState(new ColorTargetState(
                            Optional.empty(), GpuFormat.RG32_FLOAT,
                            ColorTargetState.WRITE_ALL))
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    .build();

    public static final RenderPipeline DEPTH_REPROJECTION_COVERAGE_SUM =
            RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
                    .withLocation(id("pipeline/depth_reprojection_coverage_sum"))
                    .withVertexShader(id("core/screenquad"))
                    .withFragmentShader(id("core/depth_reprojection_resolve"))
                    .withShaderDefine("COVERAGE_REDUCTION")
                    .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
                    .withColorTargetState(new ColorTargetState(
                            Optional.empty(), GpuFormat.RG32_FLOAT,
                            ColorTargetState.WRITE_ALL))
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    .build();

    public static final RenderPipeline DEPTH_REPROJECTION_RESOLVE = RenderPipeline.builder(
                    RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(id("pipeline/depth_reprojection_resolve"))
            .withVertexShader(id("core/screenquad"))
            .withFragmentShader(id("core/depth_reprojection_resolve"))
            .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
            .withBindGroupLayout(REPROJECTION_RESOLVE_LAYOUT)
            .withColorTargetState(0, new ColorTargetState(
                    Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
            .withColorTargetState(1, new ColorTargetState(
                    Optional.empty(), GpuFormat.R32_FLOAT, ColorTargetState.WRITE_ALL))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build();

    private CyclesRenderPipelines() {
    }

    public static void register(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(PRESENT);
        event.registerPipeline(SCRGB_OUTPUT);
        event.registerPipeline(SDR_OUTPUT);
        event.registerPipeline(EXPOSURE_METER);
        event.registerPipeline(POST_DEPTH_OF_FIELD);
        event.registerPipeline(DEPTH_REPROJECTION_SPLAT);
        event.registerPipeline(DEPTH_REPROJECTION_COVERAGE_DEPTH);
        event.registerPipeline(DEPTH_REPROJECTION_COVERAGE_SUM);
        event.registerPipeline(DEPTH_REPROJECTION_RESOLVE);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(CyclesRendererMod.MOD_ID, path);
    }
}

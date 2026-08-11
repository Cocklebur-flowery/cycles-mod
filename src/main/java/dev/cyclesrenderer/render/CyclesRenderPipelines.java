package dev.cyclesrenderer.render;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import dev.cyclesrenderer.CyclesRendererMod;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

public final class CyclesRenderPipelines {
    public static final String DISPLAY_UNIFORM = "CyclesDisplay";

    private static final BindGroupLayout DISPLAY_LAYOUT = BindGroupLayout.builder()
            .withUniform(DISPLAY_UNIFORM, UniformType.UNIFORM_BUFFER)
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

    private CyclesRenderPipelines() {
    }

    public static void register(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(PRESENT);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(CyclesRendererMod.MOD_ID, path);
    }
}

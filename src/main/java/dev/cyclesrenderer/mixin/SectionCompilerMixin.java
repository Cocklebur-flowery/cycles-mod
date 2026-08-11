package dev.cyclesrenderer.mixin;

import dev.cyclesrenderer.scene.SectionGeometryCollector;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(SectionCompiler.class)
abstract class SectionCompilerMixin {
    @Inject(
            method = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;",
            at = @At("RETURN"))
    private void cyclesrenderer$captureCompiledSection(
            SectionPos sectionPos,
            RenderSectionRegion region,
            VertexSorting vertexSorting,
            SectionBufferBuilderPack builders,
            List<AddSectionGeometryEvent.AdditionalSectionRenderer> additionalRenderers,
            CallbackInfoReturnable<SectionCompiler.Results> callback) {
        SectionGeometryCollector.capture(sectionPos, region, callback.getReturnValue());
    }
}

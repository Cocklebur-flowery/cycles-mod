package dev.cyclesrenderer.mixin;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import dev.cyclesrenderer.CyclesRendererMod;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @Unique
    private static final Logger cyclesrenderer$logger = LoggerFactory.getLogger("CyclesRenderer/LevelRendererMixin");

    @Unique
    private static boolean cyclesrenderer$wasSkippingFrameGraph;

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V"),
            require = 1)
    private void cyclesrenderer$executeVanillaFrameGraph(
            FrameGraphBuilder frameGraph,
            GraphicsResourceAllocator resourceAllocator,
            FrameGraphBuilder.Inspector inspector) {
        if (CyclesRendererMod.shouldReplaceVanillaWorld()) {
            if (!cyclesrenderer$wasSkippingFrameGraph) {
                cyclesrenderer$logger.info("Experimental renderer active: skipping the vanilla world FrameGraph");
                cyclesrenderer$wasSkippingFrameGraph = true;
            }
            return;
        }

        if (cyclesrenderer$wasSkippingFrameGraph) {
            cyclesrenderer$logger.info("Vanilla renderer restored: executing the world FrameGraph");
            cyclesrenderer$wasSkippingFrameGraph = false;
        }
        frameGraph.execute(resourceAllocator, inspector);
    }
}

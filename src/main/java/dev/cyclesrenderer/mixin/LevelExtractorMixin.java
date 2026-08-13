package dev.cyclesrenderer.mixin;

import dev.cyclesrenderer.CyclesRendererMod;
import dev.cyclesrenderer.config.CyclesClientConfig;
import dev.cyclesrenderer.config.CyclesRenderSettings;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LevelExtractor.class)
abstract class LevelExtractorMixin {
    @Unique
    private ViewArea cyclesrenderer$panoramaViewArea;
    @Unique
    private long cyclesrenderer$panoramaCenter = Long.MIN_VALUE;
    @Unique
    private ObjectArrayList<SectionRenderDispatcher.RenderSection>
            cyclesrenderer$panoramaSections = new ObjectArrayList<>();

    @Redirect(
            method = "extract",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;visibleSections()Lit/unimi/dsi/fastutil/objects/ObjectArrayList;",
                    ordinal = 0))
    private ObjectArrayList<SectionRenderDispatcher.RenderSection>
            cyclesrenderer$compileAllPanoramaSections(LevelRenderer renderer) {
        ObjectArrayList<SectionRenderDispatcher.RenderSection> visible =
                renderer.visibleSections();
        if (!CyclesRendererMod.shouldReplaceVanillaWorld()
                || CyclesClientConfig.snapshot().cameraType()
                    != CyclesRenderSettings.CameraType.PANORAMA) {
            return visible;
        }

        ViewArea viewArea = renderer.viewArea();
        if (viewArea == null) {
            return visible;
        }
        SectionPos center = viewArea.getCameraSectionPos();
        if (cyclesrenderer$panoramaViewArea == viewArea
                && cyclesrenderer$panoramaCenter == center.asLong()) {
            return cyclesrenderer$panoramaSections;
        }
        cyclesrenderer$panoramaViewArea = viewArea;
        cyclesrenderer$panoramaCenter = center.asLong();
        cyclesrenderer$panoramaSections = new ObjectArrayList<>(viewArea.size());
        int radius = viewArea.getViewDistance();
        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
        for (int sectionX = center.x() - radius; sectionX <= center.x() + radius; sectionX++) {
            for (int sectionZ = center.z() - radius; sectionZ <= center.z() + radius; sectionZ++) {
                for (int sectionY = viewArea.minSectionY();
                        sectionY <= viewArea.maxSectionY(); sectionY++) {
                    blockPos.set(
                            SectionPos.sectionToBlockCoord(sectionX),
                            SectionPos.sectionToBlockCoord(sectionY),
                            SectionPos.sectionToBlockCoord(sectionZ));
                    SectionRenderDispatcher.RenderSection section =
                            viewArea.getRenderSectionAt(blockPos);
                    if (section != null) {
                        cyclesrenderer$panoramaSections.add(section);
                    }
                }
            }
        }
        return cyclesrenderer$panoramaSections;
    }
}

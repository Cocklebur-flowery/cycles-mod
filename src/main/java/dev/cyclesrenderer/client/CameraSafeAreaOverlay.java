package dev.cyclesrenderer.client;

import dev.cyclesrenderer.config.CyclesRenderSettings;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class CameraSafeAreaOverlay {
    private static final int ACTION_COLOR = 0xB3F2C94C;
    private static final int TITLE_COLOR = 0xD9FFF1A8;
    private static final int CENTER_ACTION_COLOR = 0xB36CC8FF;
    private static final int CENTER_TITLE_COLOR = 0xD9B5E3FF;

    private CameraSafeAreaOverlay() {
    }

    public static void extract(
            GuiGraphicsExtractor graphics,
            CyclesRenderSettings settings) {
        if (!settings.safeAreas()) {
            return;
        }

        drawGuide(graphics, settings.actionSafeX(), settings.actionSafeY(), ACTION_COLOR);
        drawGuide(graphics, settings.titleSafeX(), settings.titleSafeY(), TITLE_COLOR);
        if (settings.centerCutSafeAreas()) {
            drawGuide(
                    graphics,
                    settings.centerActionSafeX(),
                    settings.centerActionSafeY(),
                    CENTER_ACTION_COLOR);
            drawGuide(
                    graphics,
                    settings.centerTitleSafeX(),
                    settings.centerTitleSafeY(),
                    CENTER_TITLE_COLOR);
        }
    }

    private static void drawGuide(
            GuiGraphicsExtractor graphics,
            float marginX,
            float marginY,
            int color) {
        int viewportWidth = graphics.guiWidth();
        int viewportHeight = graphics.guiHeight();
        int insetX = Math.round(viewportWidth * Math.clamp(marginX, 0.0F, 0.5F));
        int insetY = Math.round(viewportHeight * Math.clamp(marginY, 0.0F, 0.5F));
        int width = viewportWidth - insetX * 2;
        int height = viewportHeight - insetY * 2;
        if (width >= 2 && height >= 2) {
            graphics.outline(insetX, insetY, width, height, color);
        }
    }
}

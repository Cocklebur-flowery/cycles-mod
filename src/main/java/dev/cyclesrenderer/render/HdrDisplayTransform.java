package dev.cyclesrenderer.render;

import dev.cyclesrenderer.config.CyclesRenderSettings;

public final class HdrDisplayTransform {
    private static final String PAPER_WHITE_PROPERTY =
            "cyclesrenderer.hdrPaperWhiteNits";
    private static final String PAPER_WHITE_ENVIRONMENT =
            "CYCLESRENDERER_HDR_PAPER_WHITE_NITS";
    private static final float PQ_REFERENCE_NITS = 10_000.0F;
    private static final float PAPER_WHITE_NITS = readPaperWhiteNits();

    private HdrDisplayTransform() {
    }

    public static Selection select(CyclesRenderSettings settings) {
        if (!HdrRenderTargetPolicy.fp16TargetsActive()
                || !VulkanCapabilityProbe.swapchainBootstrap().scRgbSelected()
                || settings.activePass() != CyclesRenderSettings.PassView.COMBINED) {
            CyclesRenderSettings.DisplayDevice display = settings.displayDevice();
            return new Selection(
                    display,
                    settings.viewTransform().effectiveFor(display),
                    false);
        }

        CyclesRenderSettings.ViewTransform requested = settings.viewTransform();
        if (requested == CyclesRenderSettings.ViewTransform.RAW
                || requested == CyclesRenderSettings.ViewTransform.FALSE_COLOR) {
            return new Selection(
                    CyclesRenderSettings.DisplayDevice.SRGB,
                    requested,
                    false);
        }

        CyclesRenderSettings.DisplayDevice display =
                CyclesRenderSettings.DisplayDevice.REC2100_PQ;
        CyclesRenderSettings.ViewTransform view = hdrView(requested).effectiveFor(display);
        return new Selection(display, view, true);
    }

    public static float paperWhiteNits() {
        return PAPER_WHITE_NITS;
    }

    public static float pqToPaperWhiteScale() {
        return PQ_REFERENCE_NITS / PAPER_WHITE_NITS;
    }

    private static CyclesRenderSettings.ViewTransform hdrView(
            CyclesRenderSettings.ViewTransform requested) {
        return switch (requested) {
            case AGX -> CyclesRenderSettings.ViewTransform.AGX_HDR_1000;
            case ACES_1_3 -> CyclesRenderSettings.ViewTransform.ACES_1_3_HDR_1000;
            case ACES_2 -> CyclesRenderSettings.ViewTransform.ACES_2_HDR_1000;
            case KHRONOS_PBR_NEUTRAL, FILMIC, FILMIC_LOG ->
                    CyclesRenderSettings.ViewTransform.AGX_HDR_1000;
            default -> requested;
        };
    }

    private static float readPaperWhiteNits() {
        String configured = System.getProperty(PAPER_WHITE_PROPERTY);
        if (configured == null) {
            configured = System.getenv(PAPER_WHITE_ENVIRONMENT);
        }
        if (configured != null) {
            try {
                return Math.clamp(Float.parseFloat(configured), 80.0F, 1_000.0F);
            } catch (NumberFormatException ignored) {
            }
        }
        return 200.0F;
    }

    public record Selection(
            CyclesRenderSettings.DisplayDevice displayDevice,
            CyclesRenderSettings.ViewTransform viewTransform,
            boolean pqEncoded) {
    }
}

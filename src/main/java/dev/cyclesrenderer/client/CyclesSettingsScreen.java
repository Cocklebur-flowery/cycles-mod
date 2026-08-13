package dev.cyclesrenderer.client;

import dev.cyclesrenderer.CyclesRendererMod;
import dev.cyclesrenderer.config.CyclesClientConfig;
import dev.cyclesrenderer.config.CyclesRenderSettings;
import dev.cyclesrenderer.nativebridge.NativeBridge;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;

import java.util.ArrayList;
import java.util.List;

public final class CyclesSettingsScreen extends Screen {
    private final Screen parent;
    private final ModContainer modContainer;
    private Button passButton;
    private Button debugButton;

    public CyclesSettingsScreen(ModContainer modContainer, Screen parent) {
        super(Component.translatable("screen.cyclesrenderer.settings.title"));
        this.modContainer = modContainer;
        this.parent = parent;
    }

    @Override
    protected void init() {
        int buttonWidth = Math.min(280, this.width - 40);
        int left = (this.width - buttonWidth) / 2;
        int top = Math.max(94, this.height / 2 - 30);
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.cyclesrenderer.settings.edit"),
                        button -> minecraft.gui.setScreen(
                                new ConfigurationScreen(modContainer, this)))
                .bounds(left, top, buttonWidth, 20)
                .build());
        passButton = addRenderableWidget(Button.builder(
                        Component.empty(),
                        button -> {
                            CyclesRenderSettings.PassView next =
                                    CyclesClientConfig.snapshot().activePass().next();
                            CyclesClientConfig.setActivePass(next);
                            refreshButtonLabels();
                        })
                .bounds(left, top + 24, buttonWidth, 20)
                .build());
        debugButton = addRenderableWidget(Button.builder(
                        Component.empty(),
                        button -> {
                            boolean enabled = !CyclesClientConfig.snapshot().debugOverlay();
                            CyclesClientConfig.setDebugOverlay(enabled);
                            refreshButtonLabels();
                        })
                .bounds(left, top + 48, buttonWidth, 20)
                .build());
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.cyclesrenderer.settings.detect"),
                        button -> CyclesRendererMod.ensureNativeBridgeReady())
                .bounds(left, top + 72, buttonWidth, 20)
                .build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(left, top + 104, buttonWidth, 20)
                .build());
        refreshButtonLabels();
    }

    @Override
    public void tick() {
        refreshButtonLabels();
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, 18, 0xFFFFFFFF);
        int y = 38;
        for (Component line : statusLines()) {
            graphics.centeredText(font, line, width / 2, y, 0xFFD0D0D0);
            y += 11;
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void refreshButtonLabels() {
        CyclesRenderSettings settings = CyclesClientConfig.snapshot();
        if (passButton != null) {
            passButton.setMessage(Component.translatable(
                    "screen.cyclesrenderer.settings.pass",
                    settings.activePass().getTranslatedName()));
        }
        if (debugButton != null) {
            debugButton.setMessage(Component.translatable(
                    "screen.cyclesrenderer.settings.debug",
                    settings.debugOverlay() ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF));
        }
    }

    private List<Component> statusLines() {
        List<Component> lines = new ArrayList<>();
        CyclesRenderSettings settings = CyclesClientConfig.snapshot();
        lines.add(Component.translatable(
                "screen.cyclesrenderer.settings.summary",
                settings.renderWidth(),
                settings.renderHeight(),
                settings.resolutionPercentage(),
                settings.interactiveSamples(),
                settings.stillSamples()));
        lines.add(Component.translatable(
                "screen.cyclesrenderer.settings.color_pipeline",
                settings.workingSpace().getTranslatedName(),
                settings.displayDevice().getTranslatedName(),
                settings.viewTransform().effectiveFor(settings.displayDevice()).getTranslatedName()));
        lines.add(Component.translatable(
                "screen.cyclesrenderer.settings.color_look",
                settings.colorLook().getTranslatedName()));
        lines.add(Component.translatable(
                "screen.cyclesrenderer.settings.sampling_pattern",
                settings.samplingPattern().getTranslatedName(),
                settings.seed()));
        lines.add(Component.translatable(
                "screen.cyclesrenderer.settings.camera",
                settings.projectionMode().getTranslatedName(),
                settings.focalLengthMm(),
                settings.sensorWidthMm(),
                settings.depthOfField() ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF));
        lines.add(Component.translatable(
                "screen.cyclesrenderer.settings.atmosphere",
                settings.atmosphereSunElevationDegrees(),
                settings.atmosphereSunRotationDegrees(),
                settings.atmosphereSunIntensity()));
        lines.add(Component.translatable(
                "screen.cyclesrenderer.settings.pbr",
                settings.pbrMode().getTranslatedName(),
                settings.pbrNormalStrength(),
                settings.pbrEmissionScale(),
                settings.pbrFallbackRoughness(),
                settings.pbrFallbackF0()));
        if (!NativeBridge.isReady()) {
            lines.add(Component.translatable("screen.cyclesrenderer.settings.native_not_loaded"));
            return lines;
        }
        try {
            NativeBridge.Capabilities capabilities = NativeBridge.capabilities();
            NativeBridge.Diagnostics diagnostics = NativeBridge.diagnostics();
            lines.add(Component.literal(
                    "Devices: " + diagnostics.deviceName() + " / " + capabilities.deviceCount()
                            + "; denoise: " + capabilities.denoiserSummary()));
            lines.add(Component.literal(
                    "Native: " + diagnostics.stateName() + "; "
                            + diagnostics.width() + "x" + diagnostics.height()
                            + "; sample=" + diagnostics.sampleCount()
                            + "/" + diagnostics.targetSampleCount()
                            + " (" + diagnostics.samplingStateName() + ")"
                            + "; sections=" + diagnostics.sectionCount()));
        } catch (RuntimeException error) {
            lines.add(Component.translatable(
                    "screen.cyclesrenderer.settings.diagnostics_failed",
                    error.getMessage()));
        }
        return lines;
    }
}

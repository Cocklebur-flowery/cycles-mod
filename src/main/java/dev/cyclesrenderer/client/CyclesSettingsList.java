package dev.cyclesrenderer.client;

import dev.cyclesrenderer.config.CyclesClientConfig;
import dev.cyclesrenderer.config.CyclesRenderSettings;
import dev.cyclesrenderer.config.CameraAutomationSettings;
import dev.cyclesrenderer.config.SettingsDraft;
import dev.cyclesrenderer.config.SettingsOption;
import dev.cyclesrenderer.nativebridge.NativeBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class CyclesSettingsList
        extends ContainerObjectSelectionList<CyclesSettingsList.SettingEntry> {
    private static final Set<String> DEPENDENCY_OPTIONS = Set.of(
            "output.dynamicResolution",
            "sampling.adaptive",
            "denoise.mode",
            "camera.type",
            "camera.projection",
            "camera.panoramaType",
            "camera.depthOfField",
            "camera.apertureCircular",
            "camera.safeAreas",
            "camera.centerCutSafeAreas",
            "camera.autofocus.mode",
            "materials.pbrMode",
            "color.display",
            "color.viewTransform",
            "color.whiteBalance",
            "color.autoExposure");
    private final SettingsDraft draft;
    private boolean refreshRequested;

    CyclesSettingsList(
            Minecraft minecraft,
            int width,
            int height,
            int x,
            int y,
            SettingsDraft draft) {
        super(minecraft, width, height, y, 24);
        this.draft = draft;
        this.centerListVertically = false;
        updateSizeAndPosition(width, height, x, y);
    }

    void rebuild(CyclesClientConfig.Category category, String query, boolean preserveScroll) {
        double oldScroll = scrollAmount();
        clearEntries();
        String needle = query.strip().toLowerCase(Locale.ROOT);
        for (SettingsOption<?> option : CyclesClientConfig.options()) {
            String translated = Component.translatable(option.translationKey()).getString();
            boolean matchesSearch = needle.isEmpty()
                    || option.id().toLowerCase(Locale.ROOT).contains(needle)
                    || translated.toLowerCase(Locale.ROOT).contains(needle);
            if ((needle.isEmpty() && option.category() == category)
                    || (!needle.isEmpty() && matchesSearch)) {
                addEntry(new SettingEntry(option, isEnabled(option)));
            }
        }
        setScrollAmount(preserveScroll ? oldScroll : 0.0D);
    }

    boolean consumeRefreshRequest() {
        boolean requested = refreshRequested;
        refreshRequested = false;
        return requested;
    }

    @Override
    public int getRowWidth() {
        return Math.max(120, getWidth() - 18);
    }

    private boolean isEnabled(SettingsOption<?> option) {
        String id = option.id();
        if (id.startsWith("color.autoExposure.") && !booleanValue("color.autoExposure")) {
            return false;
        }
        if (id.startsWith("camera.autofocus.") && !id.equals("camera.autofocus.mode")
                && enumValue("camera.autofocus.mode")
                    == CameraAutomationSettings.AutofocusMode.OFF) {
            return false;
        }
        if ((id.equals("sampling.minimumSamples") || id.equals("sampling.noiseThreshold"))
                && !booleanValue("sampling.adaptive")) {
            return false;
        }
        if (id.equals("output.interactivePercentage")
                && !booleanValue("output.dynamicResolution")) {
            return false;
        }
        CyclesRenderSettings.CameraType cameraType = enumValue("camera.type");
        CyclesRenderSettings.PanoramaType panoramaType = enumValue("camera.panoramaType");
        if (id.equals("camera.projection")
                && cameraType != CyclesRenderSettings.CameraType.PERSPECTIVE) {
            return false;
        }
        if (id.equals("camera.panoramaType")
                && cameraType != CyclesRenderSettings.CameraType.PANORAMA) {
            return false;
        }
        if (id.equals("camera.focalLength")
                && !booleanValue("camera.depthOfField")
                && (cameraType != CyclesRenderSettings.CameraType.PERSPECTIVE
                    || enumValue("camera.projection")
                        != CyclesRenderSettings.ProjectionMode.PHYSICAL_LENS)) {
            return false;
        }
        if (id.equals("camera.sensorWidth")
                && !usesSensorWidth(cameraType, panoramaType,
                    enumValue("camera.projection"))) {
            return false;
        }
        if (id.equals("camera.fisheyeFov")
                && !usesFisheye(panoramaType, cameraType)) {
            return false;
        }
        if (id.equals("camera.fisheyeLens")
                && panoramaType != CyclesRenderSettings.PanoramaType.FISHEYE_EQUISOLID) {
            return false;
        }
        if ((id.startsWith("camera.latitude") || id.startsWith("camera.longitude"))
                && (cameraType != CyclesRenderSettings.CameraType.PANORAMA
                    || panoramaType != CyclesRenderSettings.PanoramaType.EQUIRECTANGULAR)) {
            return false;
        }
        if (id.startsWith("camera.polynomial")
                && (cameraType != CyclesRenderSettings.CameraType.PANORAMA
                    || panoramaType
                        != CyclesRenderSettings.PanoramaType.FISHEYE_LENS_POLYNOMIAL)) {
            return false;
        }
        if (id.startsWith("camera.cylindrical")
                && (cameraType != CyclesRenderSettings.CameraType.PANORAMA
                    || panoramaType != CyclesRenderSettings.PanoramaType.CENTRAL_CYLINDRICAL)) {
            return false;
        }
        if (id.startsWith("camera.")
                && Set.of("camera.depthOfFieldMode", "camera.focusDistance", "camera.fStop",
                        "camera.apertureCircular",
                        "camera.apertureBlades", "camera.apertureRotation", "camera.apertureRatio")
                .contains(id)
                && !booleanValue("camera.depthOfField")) {
            return false;
        }
        if (id.equals("camera.apertureBlades") && booleanValue("camera.apertureCircular")) {
            return false;
        }
        if ((id.startsWith("camera.titleSafe") || id.startsWith("camera.actionSafe")
                || id.equals("camera.centerCutSafeAreas"))
                && !booleanValue("camera.safeAreas")) {
            return false;
        }
        if ((id.startsWith("camera.centerTitleSafe") || id.startsWith("camera.centerActionSafe"))
                && (!booleanValue("camera.safeAreas")
                    || !booleanValue("camera.centerCutSafeAreas"))) {
            return false;
        }
        if (id.startsWith("denoise.") && !id.equals("denoise.mode")
                && enumValue("denoise.mode") == CyclesRenderSettings.DenoiserMode.OFF) {
            return false;
        }
        if (id.equals("denoise.dlssMode")
                && enumValue("denoise.mode") != CyclesRenderSettings.DenoiserMode.DLSS_EXPERIMENTAL) {
            return false;
        }
        if (id.startsWith("materials.") && !id.equals("materials.pbrMode")
                && enumValue("materials.pbrMode") == CyclesRenderSettings.PbrMode.OFF) {
            return false;
        }
        return !(id.equals("color.temperature") || id.equals("color.tint"))
                || booleanValue("color.whiteBalance");
    }

    private static boolean usesSensorWidth(
            CyclesRenderSettings.CameraType cameraType,
            CyclesRenderSettings.PanoramaType panoramaType,
            CyclesRenderSettings.ProjectionMode projectionMode) {
        if (cameraType == CyclesRenderSettings.CameraType.PERSPECTIVE) {
            return projectionMode == CyclesRenderSettings.ProjectionMode.PHYSICAL_LENS;
        }
        return panoramaType == CyclesRenderSettings.PanoramaType.FISHEYE_EQUISOLID
                || panoramaType
                    == CyclesRenderSettings.PanoramaType.FISHEYE_LENS_POLYNOMIAL;
    }

    private static boolean usesFisheye(
            CyclesRenderSettings.PanoramaType panoramaType,
            CyclesRenderSettings.CameraType cameraType) {
        return cameraType == CyclesRenderSettings.CameraType.PANORAMA
                && switch (panoramaType) {
                    case FISHEYE_EQUIDISTANT, FISHEYE_EQUISOLID,
                            FISHEYE_LENS_POLYNOMIAL -> true;
                    default -> false;
                };
    }

    private List<?> effectiveChoices(SettingsOption<?> option) {
        List<?> choices = option.choices();
        if (option.id().equals("color.viewTransform")) {
            CyclesRenderSettings.DisplayDevice display = enumValue("color.display");
            return choices.stream()
                    .map(CyclesRenderSettings.ViewTransform.class::cast)
                    .filter(view -> view.supports(display))
                    .toList();
        }
        if (option.id().equals("color.look")) {
            CyclesRenderSettings.ViewTransform view = enumValue("color.viewTransform");
            return choices.stream()
                    .map(CyclesRenderSettings.ColorLook.class::cast)
                    .filter(look -> look == CyclesRenderSettings.ColorLook.NONE
                            || look.effectiveNativeId(view) == look.nativeId())
                    .toList();
        }
        if (option.id().equals("denoise.mode") && NativeBridge.isReady()) {
            try {
                NativeBridge.Capabilities capabilities = NativeBridge.capabilities();
                return choices.stream()
                        .map(CyclesRenderSettings.DenoiserMode.class::cast)
                        .filter(mode -> switch (mode) {
                            case OFF, AUTO -> true;
                            case OPTIX -> capabilities.optixDenoiserAvailable();
                            case OPEN_IMAGE_DENOISE -> capabilities.oidnDenoiserAvailable();
                            case DLSS_EXPERIMENTAL -> capabilities.dlssExperimentalDenoiserAvailable();
                        })
                        .toList();
            } catch (RuntimeException ignored) {
                return choices;
            }
        }
        if (option.id().equals("diagnostics.activePass") && NativeBridge.isReady()) {
            try {
                NativeBridge.Capabilities capabilities = NativeBridge.capabilities();
                return choices.stream()
                        .map(CyclesRenderSettings.PassView.class::cast)
                        .filter(capabilities::supportsPass)
                        .toList();
            } catch (RuntimeException ignored) {
                return choices;
            }
        }
        return choices;
    }

    private boolean booleanValue(String id) {
        return Boolean.TRUE.equals(value(id));
    }

    @SuppressWarnings("unchecked")
    private <E extends Enum<E>> E enumValue(String id) {
        return (E) value(id);
    }

    private Object value(String id) {
        for (SettingsOption<?> candidate : CyclesClientConfig.options()) {
            if (candidate.id().equals(id)) {
                return getUnchecked(candidate);
            }
        }
        throw new IllegalArgumentException("Unknown Cycles setting " + id);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object getUnchecked(SettingsOption<?> option) {
        return draft.get((SettingsOption) option);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void setUnchecked(SettingsOption<?> option, Object value) {
        draft.set((SettingsOption) option, value);
        if (DEPENDENCY_OPTIONS.contains(option.id())) {
            refreshRequested = true;
        }
    }

    private List<Object> choicesWithCurrent(SettingsOption<?> option) {
        List<Object> choices = new ArrayList<>(effectiveChoices(option));
        Object current = getUnchecked(option);
        if (!choices.contains(current)) {
            choices.add(0, current);
        }
        return choices;
    }

    private static Component valueLabel(Object value) {
        if (value instanceof TranslatableEnum translatable) {
            return translatable.getTranslatedName();
        }
        if (value instanceof Boolean enabled) {
            return enabled ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF;
        }
        return Component.literal(String.valueOf(value));
    }

    final class SettingEntry extends ContainerObjectSelectionList.Entry<SettingEntry> {
        private final SettingsOption<?> option;
        private final StringWidget label;
        private final List<AbstractWidget> controls;

        SettingEntry(SettingsOption<?> option, boolean enabled) {
            this.option = option;
            this.label = new StringWidget(
                    Component.translatable(option.translationKey()), Minecraft.getInstance().font);
            this.controls = createControls(option);
            for (AbstractWidget control : controls) {
                control.active = enabled;
                if (control instanceof EditBox editBox) {
                    editBox.setEditable(enabled);
                }
            }
        }

        @Override
        public void extractContent(
                GuiGraphicsExtractor graphics,
                int mouseX,
                int mouseY,
                boolean hovered,
                float partialTick) {
            int contentX = getContentX() + 4;
            int contentY = getContentY() + 2;
            int usableWidth = Math.max(100, getContentWidth() - 8);
            int labelWidth = Math.clamp(usableWidth * 43 / 100, 74, 190);
            int controlsX = contentX + labelWidth + 6;
            int controlsWidth = Math.max(60, usableWidth - labelWidth - 6);
            label.setPosition(contentX, contentY);
            label.setMaxWidth(labelWidth);
            label.extractRenderState(graphics, mouseX, mouseY, partialTick);

            if (controls.size() == 1) {
                AbstractWidget control = controls.getFirst();
                control.setPosition(controlsX, getContentY());
                control.setWidth(controlsWidth);
                control.extractRenderState(graphics, mouseX, mouseY, partialTick);
                return;
            }

            int valueWidth = Math.min(82, Math.max(54, controlsWidth * 38 / 100));
            AbstractWidget slider = controls.get(0);
            AbstractWidget value = controls.get(1);
            slider.setPosition(controlsX, getContentY());
            slider.setWidth(Math.max(30, controlsWidth - valueWidth - 4));
            value.setPosition(controlsX + controlsWidth - valueWidth, getContentY());
            value.setWidth(valueWidth);
            slider.extractRenderState(graphics, mouseX, mouseY, partialTick);
            value.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            List<GuiEventListener> children = new ArrayList<>(controls.size() + 1);
            children.add(label);
            children.addAll(controls);
            return children;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            List<NarratableEntry> entries = new ArrayList<>(controls.size() + 1);
            entries.add(label);
            entries.addAll(controls);
            return entries;
        }

        private List<AbstractWidget> createControls(SettingsOption<?> option) {
            return switch (option.kind()) {
                case BOOLEAN, ENUM -> List.of(createCycleButton(option));
                case INTEGER, DOUBLE -> createNumericControls(option);
            };
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private AbstractWidget createCycleButton(SettingsOption<?> option) {
            Object current = getUnchecked(option);
            List values = option.kind() == CyclesClientConfig.ValueKind.BOOLEAN
                    ? List.of(Boolean.TRUE, Boolean.FALSE)
                    : choicesWithCurrent(option);
            return CycleButton.builder(CyclesSettingsList::valueLabel, current)
                    .withValues(values)
                    .displayOnlyValue()
                    .create(0, 0, 150, 20, Component.translatable(option.translationKey()),
                            (button, value) -> setUnchecked(option, value));
        }

        private List<AbstractWidget> createNumericControls(
                SettingsOption<?> option) {
            Number current = (Number) getUnchecked(option);
            EditBox editBox = new EditBox(
                    Minecraft.getInstance().font, 0, 0, 72, 20,
                    Component.translatable(option.translationKey()));
            editBox.setMaxLength(24);
            NumericSlider slider = new NumericSlider(option, editBox, current.doubleValue());
            editBox.setValue(formatNumber(option, current.doubleValue()));
            editBox.setResponder(text -> {
                try {
                    Number parsed;
                    if (option.kind() == CyclesClientConfig.ValueKind.INTEGER) {
                        parsed = Integer.valueOf(text);
                    } else {
                        parsed = Double.valueOf(text);
                    }
                    setUnchecked(option, parsed);
                    slider.syncFromExternal(((Number) getUnchecked(option)).doubleValue());
                    editBox.setTextColor(EditBox.DEFAULT_TEXT_COLOR);
                } catch (NumberFormatException error) {
                    editBox.setTextColor(0xFFFF6060);
                }
            });
            return List.of(slider, editBox);
        }
    }

    final class NumericSlider extends AbstractSliderButton {
        private final SettingsOption<?> option;
        private final EditBox editBox;
        private boolean externalSync;

        NumericSlider(
                SettingsOption<?> option,
                EditBox editBox,
                double current) {
            super(0, 0, 100, 20, Component.empty(), normalize(option, current));
            this.option = option;
            this.editBox = editBox;
            updateMessage();
        }

        void syncFromExternal(double value) {
            externalSync = true;
            this.value = normalize(option, value);
            updateMessage();
            externalSync = false;
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.empty());
        }

        @Override
        protected void applyValue() {
            if (externalSync) {
                return;
            }
            double decoded = denormalize(option, value);
            double step = option.step();
            double stepped = step > 0.0D
                    ? Math.rint((decoded - option.minimum()) / step) * step + option.minimum()
                    : decoded;
            Object candidate;
            if (option.kind() == CyclesClientConfig.ValueKind.INTEGER) {
                candidate = Integer.valueOf((int) Math.round(stepped));
            } else {
                candidate = Double.valueOf(stepped);
            }
            setUnchecked(option, candidate);
            Number stored = (Number) getUnchecked(option);
            editBox.setValue(formatNumber(option, stored.doubleValue()));
        }
    }

    private static double normalize(SettingsOption<?> option, double value) {
        double range = option.maximum() - option.minimum();
        if (range <= 0.0D) {
            return 0.0D;
        }
        double clamped = Math.clamp(value, option.minimum(), option.maximum());
        if (useLogScale(option)) {
            return Math.log1p(clamped - option.minimum()) / Math.log1p(range);
        }
        return (clamped - option.minimum()) / range;
    }

    private static double denormalize(SettingsOption<?> option, double value) {
        double range = option.maximum() - option.minimum();
        if (useLogScale(option)) {
            return option.minimum() + Math.expm1(value * Math.log1p(range));
        }
        return option.minimum() + value * range;
    }

    private static boolean useLogScale(SettingsOption<?> option) {
        return option.minimum() >= 0.0D && option.maximum() - option.minimum() > 1000.0D;
    }

    private static String formatNumber(
            SettingsOption<?> option,
            double value) {
        if (option.kind() == CyclesClientConfig.ValueKind.INTEGER) {
            return Integer.toString((int) Math.round(value));
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}

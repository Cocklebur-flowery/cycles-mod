package dev.cyclesrenderer.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/** Owns serialized AE/AF tuning without adding more responsibilities to the legacy config file. */
final class CameraAutomationConfig {
    private final ModConfigSpec.BooleanValue exposureEnabled;
    private final ModConfigSpec.BooleanValue exposureLocked;
    private final ModConfigSpec.EnumValue<CameraAutomationSettings.ExposureMetering> metering;
    private final ModConfigSpec.DoubleValue centerWeight;
    private final ModConfigSpec.DoubleValue lowPercentile;
    private final ModConfigSpec.DoubleValue highPercentile;
    private final ModConfigSpec.DoubleValue highlightPercentile;
    private final ModConfigSpec.DoubleValue highlightOutput;
    private final ModConfigSpec.DoubleValue highlightHeadroomEv;
    private final ModConfigSpec.DoubleValue minimumEv;
    private final ModConfigSpec.DoubleValue maximumEv;
    private final ModConfigSpec.DoubleValue brightenSeconds;
    private final ModConfigSpec.DoubleValue darkenSeconds;
    private final ModConfigSpec.DoubleValue exposureDeadband;
    private final ModConfigSpec.DoubleValue maximumEvPerSecond;
    private final ModConfigSpec.EnumValue<CameraAutomationSettings.AutofocusMode> autofocusMode;
    private final ModConfigSpec.BooleanValue autofocusLocked;
    private final ModConfigSpec.EnumValue<CameraAutomationSettings.FocusTarget> focusTarget;
    private final ModConfigSpec.DoubleValue maximumFocusDistance;
    private final ModConfigSpec.DoubleValue focusAreaRadius;
    private final ModConfigSpec.BooleanValue includeFluids;
    private final ModConfigSpec.DoubleValue clusterGapStops;
    private final ModConfigSpec.DoubleValue focusResponseSeconds;
    private final ModConfigSpec.DoubleValue focusDeadbandDistance;
    private final ModConfigSpec.DoubleValue focusDeadbandRatio;
    private final ModConfigSpec.DoubleValue maximumStopsPerSecond;
    private final ModConfigSpec.EnumValue<CameraAutomationSettings.FocusMissBehavior> missBehavior;

    private CameraAutomationConfig(Definition values) {
        exposureEnabled = values.exposureEnabled;
        exposureLocked = values.exposureLocked;
        metering = values.metering;
        centerWeight = values.centerWeight;
        lowPercentile = values.lowPercentile;
        highPercentile = values.highPercentile;
        highlightPercentile = values.highlightPercentile;
        highlightOutput = values.highlightOutput;
        highlightHeadroomEv = values.highlightHeadroomEv;
        minimumEv = values.minimumEv;
        maximumEv = values.maximumEv;
        brightenSeconds = values.brightenSeconds;
        darkenSeconds = values.darkenSeconds;
        exposureDeadband = values.exposureDeadband;
        maximumEvPerSecond = values.maximumEvPerSecond;
        autofocusMode = values.autofocusMode;
        autofocusLocked = values.autofocusLocked;
        focusTarget = values.focusTarget;
        maximumFocusDistance = values.maximumFocusDistance;
        focusAreaRadius = values.focusAreaRadius;
        includeFluids = values.includeFluids;
        clusterGapStops = values.clusterGapStops;
        focusResponseSeconds = values.focusResponseSeconds;
        focusDeadbandDistance = values.focusDeadbandDistance;
        focusDeadbandRatio = values.focusDeadbandRatio;
        maximumStopsPerSecond = values.maximumStopsPerSecond;
        missBehavior = values.missBehavior;
    }

    static CameraAutomationConfig define(ModConfigSpec.Builder builder) {
        builder.push("cameraAutomation");
        builder.push("autoExposure");
        Definition values = new Definition();
        values.exposureEnabled = builder.translation("config.cyclesrenderer.color.autoExposure")
                .define("enabled", false);
        values.exposureLocked = builder.translation("config.cyclesrenderer.color.autoExposureLocked")
                .define("locked", false);
        values.metering = builder.translation("config.cyclesrenderer.color.exposureMetering")
                .defineEnum("metering", CameraAutomationSettings.ExposureMetering.CENTER_WEIGHTED);
        values.centerWeight = range(builder, "centerWeight", 2.0D, 0.0D, 8.0D);
        values.lowPercentile = range(builder, "lowPercentile", 0.02D, 0.0D, 0.49D);
        values.highPercentile = range(builder, "highPercentile", 0.98D, 0.51D, 0.999D);
        values.highlightPercentile = range(builder, "highlightPercentile", 0.995D, 0.9D, 1.0D);
        values.highlightOutput = range(builder, "highlightOutput", 1.0D, 0.1D, 16.0D);
        values.highlightHeadroomEv = range(builder, "highlightHeadroomEv", 0.5D, 0.0D, 8.0D);
        values.minimumEv = range(builder, "minimumEv", -12.0D, -20.0D, 20.0D);
        values.maximumEv = range(builder, "maximumEv", 12.0D, -20.0D, 20.0D);
        values.brightenSeconds = range(builder, "brightenSeconds", 0.8D, 0.0D, 10.0D);
        values.darkenSeconds = range(builder, "darkenSeconds", 0.35D, 0.0D, 10.0D);
        values.exposureDeadband = range(builder, "deadbandEv", 0.05D, 0.0D, 2.0D);
        values.maximumEvPerSecond = range(builder, "maximumEvPerSecond", 8.0D, 0.1D, 40.0D);
        builder.pop();

        builder.push("autofocus");
        values.autofocusMode = builder.translation("config.cyclesrenderer.camera.autofocusMode")
                .defineEnum("mode", CameraAutomationSettings.AutofocusMode.OFF);
        values.autofocusLocked = builder.translation("config.cyclesrenderer.camera.autofocusLocked")
                .define("locked", false);
        values.focusTarget = builder.translation("config.cyclesrenderer.camera.focusTarget")
                .defineEnum("target", CameraAutomationSettings.FocusTarget.AREA);
        values.maximumFocusDistance = range(builder, "maximumDistance", 128.0D, 1.0D, 1024.0D);
        values.focusAreaRadius = range(builder, "areaRadius", 0.035D, 0.0D, 0.25D);
        values.includeFluids = builder.translation("config.cyclesrenderer.camera.autofocusFluids")
                .define("includeFluids", false);
        values.clusterGapStops = range(builder, "clusterGapStops", 0.75D, 0.05D, 4.0D);
        values.focusResponseSeconds = range(builder, "responseSeconds", 0.25D, 0.0D, 10.0D);
        values.focusDeadbandDistance = range(builder, "deadbandDistance", 0.03D, 0.0D, 8.0D);
        values.focusDeadbandRatio = range(builder, "deadbandRatio", 0.01D, 0.0D, 0.5D);
        values.maximumStopsPerSecond = range(builder, "maximumStopsPerSecond", 6.0D, 0.1D, 40.0D);
        values.missBehavior = builder.translation("config.cyclesrenderer.camera.focusMissBehavior")
                .defineEnum("missBehavior", CameraAutomationSettings.FocusMissBehavior.HOLD_LAST);
        builder.pop();
        builder.pop();
        return new CameraAutomationConfig(values);
    }

    CameraAutomationSettings snapshot() {
        float low = f(lowPercentile);
        float high = Math.max(low + 0.001F, f(highPercentile));
        float highlight = Math.max(high, f(highlightPercentile));
        float minimum = Math.min(f(minimumEv), f(maximumEv));
        float maximum = Math.max(f(minimumEv), f(maximumEv));
        return new CameraAutomationSettings(
                new CameraAutomationSettings.AutoExposure(
                        exposureEnabled.get(), exposureLocked.get(), metering.get(),
                        f(centerWeight), low, high, highlight,
                        f(highlightOutput), f(highlightHeadroomEv), minimum, maximum,
                        f(brightenSeconds), f(darkenSeconds),
                        f(exposureDeadband), f(maximumEvPerSecond)),
                new CameraAutomationSettings.Autofocus(
                        autofocusMode.get(), autofocusLocked.get(), focusTarget.get(),
                        f(maximumFocusDistance), f(focusAreaRadius), includeFluids.get(),
                        f(clusterGapStops), f(focusResponseSeconds), f(focusDeadbandDistance),
                        f(focusDeadbandRatio), f(maximumStopsPerSecond), missBehavior.get()));
    }

    void appendOptions(List<SettingsOption<?>> options) {
        options.add(CyclesClientConfig.booleanOption("color.autoExposure", CyclesClientConfig.Category.COLOR,
                "config.cyclesrenderer.color.autoExposure", exposureEnabled));
        options.add(CyclesClientConfig.booleanOption("color.autoExposure.locked", CyclesClientConfig.Category.COLOR,
                "config.cyclesrenderer.color.autoExposureLocked", exposureLocked));
        options.add(CyclesClientConfig.enumOption("color.autoExposure.metering", CyclesClientConfig.Category.COLOR,
                "config.cyclesrenderer.color.exposureMetering", metering,
                CameraAutomationSettings.ExposureMetering.values()));
        addExposureNumbers(options);
        options.add(CyclesClientConfig.enumOption("camera.autofocus.mode", CyclesClientConfig.Category.CAMERA,
                "config.cyclesrenderer.camera.autofocusMode", autofocusMode,
                CameraAutomationSettings.AutofocusMode.values()));
        options.add(CyclesClientConfig.booleanOption("camera.autofocus.locked", CyclesClientConfig.Category.CAMERA,
                "config.cyclesrenderer.camera.autofocusLocked", autofocusLocked));
        options.add(CyclesClientConfig.enumOption("camera.autofocus.target", CyclesClientConfig.Category.CAMERA,
                "config.cyclesrenderer.camera.focusTarget", focusTarget,
                CameraAutomationSettings.FocusTarget.values()));
        addFocusNumbers(options);
        options.add(CyclesClientConfig.booleanOption("camera.autofocus.fluids", CyclesClientConfig.Category.CAMERA,
                "config.cyclesrenderer.camera.autofocusFluids", includeFluids));
        options.add(CyclesClientConfig.enumOption("camera.autofocus.miss", CyclesClientConfig.Category.CAMERA,
                "config.cyclesrenderer.camera.focusMissBehavior", missBehavior,
                CameraAutomationSettings.FocusMissBehavior.values()));
    }

    private void addExposureNumbers(List<SettingsOption<?>> options) {
        add(options, "centerWeight", centerWeight, 0.0D, 8.0D, 0.1D);
        add(options, "lowPercentile", lowPercentile, 0.0D, 0.49D, 0.01D);
        add(options, "highPercentile", highPercentile, 0.51D, 0.999D, 0.01D);
        add(options, "highlightPercentile", highlightPercentile, 0.9D, 1.0D, 0.001D);
        add(options, "highlightOutput", highlightOutput, 0.1D, 16.0D, 0.1D);
        add(options, "highlightHeadroomEv", highlightHeadroomEv, 0.0D, 8.0D, 0.1D);
        add(options, "minimumEv", minimumEv, -20.0D, 20.0D, 0.1D);
        add(options, "maximumEv", maximumEv, -20.0D, 20.0D, 0.1D);
        add(options, "brightenSeconds", brightenSeconds, 0.0D, 10.0D, 0.05D);
        add(options, "darkenSeconds", darkenSeconds, 0.0D, 10.0D, 0.05D);
        add(options, "deadbandEv", exposureDeadband, 0.0D, 2.0D, 0.01D);
        add(options, "maximumEvPerSecond", maximumEvPerSecond, 0.1D, 40.0D, 0.1D);
    }

    private void addFocusNumbers(List<SettingsOption<?>> options) {
        addFocus(options, "maximumDistance", maximumFocusDistance, 1.0D, 1024.0D, 1.0D);
        addFocus(options, "areaRadius", focusAreaRadius, 0.0D, 0.25D, 0.005D);
        addFocus(options, "clusterGapStops", clusterGapStops, 0.05D, 4.0D, 0.05D);
        addFocus(options, "responseSeconds", focusResponseSeconds, 0.0D, 10.0D, 0.05D);
        addFocus(options, "deadbandDistance", focusDeadbandDistance, 0.0D, 8.0D, 0.01D);
        addFocus(options, "deadbandRatio", focusDeadbandRatio, 0.0D, 0.5D, 0.005D);
        addFocus(options, "maximumStopsPerSecond", maximumStopsPerSecond, 0.1D, 40.0D, 0.1D);
    }

    private static void add(
            List<SettingsOption<?>> options,
            String name,
            ModConfigSpec.DoubleValue value,
            double minimum,
            double maximum,
            double step) {
        options.add(CyclesClientConfig.doubleOption("color.autoExposure." + name,
                CyclesClientConfig.Category.COLOR,
                "config.cyclesrenderer.color.autoExposure." + name,
                value, minimum, maximum, step));
    }

    private static void addFocus(
            List<SettingsOption<?>> options,
            String name,
            ModConfigSpec.DoubleValue value,
            double minimum,
            double maximum,
            double step) {
        options.add(CyclesClientConfig.doubleOption("camera.autofocus." + name,
                CyclesClientConfig.Category.CAMERA,
                "config.cyclesrenderer.camera.autofocus." + name,
                value, minimum, maximum, step));
    }

    private static ModConfigSpec.DoubleValue range(
            ModConfigSpec.Builder builder,
            String name,
            double defaultValue,
            double minimum,
            double maximum) {
        return builder.defineInRange(name, defaultValue, minimum, maximum);
    }

    private static float f(ModConfigSpec.DoubleValue value) {
        return value.get().floatValue();
    }

    private static final class Definition {
        private ModConfigSpec.BooleanValue exposureEnabled;
        private ModConfigSpec.BooleanValue exposureLocked;
        private ModConfigSpec.EnumValue<CameraAutomationSettings.ExposureMetering> metering;
        private ModConfigSpec.DoubleValue centerWeight;
        private ModConfigSpec.DoubleValue lowPercentile;
        private ModConfigSpec.DoubleValue highPercentile;
        private ModConfigSpec.DoubleValue highlightPercentile;
        private ModConfigSpec.DoubleValue highlightOutput;
        private ModConfigSpec.DoubleValue highlightHeadroomEv;
        private ModConfigSpec.DoubleValue minimumEv;
        private ModConfigSpec.DoubleValue maximumEv;
        private ModConfigSpec.DoubleValue brightenSeconds;
        private ModConfigSpec.DoubleValue darkenSeconds;
        private ModConfigSpec.DoubleValue exposureDeadband;
        private ModConfigSpec.DoubleValue maximumEvPerSecond;
        private ModConfigSpec.EnumValue<CameraAutomationSettings.AutofocusMode> autofocusMode;
        private ModConfigSpec.BooleanValue autofocusLocked;
        private ModConfigSpec.EnumValue<CameraAutomationSettings.FocusTarget> focusTarget;
        private ModConfigSpec.DoubleValue maximumFocusDistance;
        private ModConfigSpec.DoubleValue focusAreaRadius;
        private ModConfigSpec.BooleanValue includeFluids;
        private ModConfigSpec.DoubleValue clusterGapStops;
        private ModConfigSpec.DoubleValue focusResponseSeconds;
        private ModConfigSpec.DoubleValue focusDeadbandDistance;
        private ModConfigSpec.DoubleValue focusDeadbandRatio;
        private ModConfigSpec.DoubleValue maximumStopsPerSecond;
        private ModConfigSpec.EnumValue<CameraAutomationSettings.FocusMissBehavior> missBehavior;
    }
}

package dev.cyclesrenderer.config;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;

import java.util.Locale;

/** Settings consumed by the display-only exposure and camera-focus controllers. */
public record CameraAutomationSettings(
        AutoExposure autoExposure,
        Autofocus autofocus) {

    public record AutoExposure(
            boolean enabled,
            boolean locked,
            ExposureMetering metering,
            float centerWeight,
            float lowPercentile,
            float highPercentile,
            float highlightPercentile,
            float highlightOutput,
            float highlightHeadroomEv,
            float minimumEv,
            float maximumEv,
            float brightenSeconds,
            float darkenSeconds,
            float deadbandEv,
            float maximumEvPerSecond) {
    }

    public record Autofocus(
            AutofocusMode mode,
            boolean locked,
            FocusTarget target,
            float maximumDistance,
            float areaRadius,
            boolean includeFluids,
            float clusterGapStops,
            float responseSeconds,
            float deadbandDistance,
            float deadbandRatio,
            float maximumStopsPerSecond,
            FocusMissBehavior missBehavior) {
        public boolean enabled() {
            return mode != AutofocusMode.OFF;
        }
    }

    public enum ExposureMetering implements TranslatableEnum {
        AVERAGE,
        CENTER_WEIGHTED,
        HIGHLIGHT_PRIORITY;

        @Override
        public Component getTranslatedName() {
            return translated("exposure_metering", this);
        }
    }

    public enum AutofocusMode implements TranslatableEnum {
        OFF,
        SINGLE_SHOT,
        CONTINUOUS;

        @Override
        public Component getTranslatedName() {
            return translated("autofocus_mode", this);
        }
    }

    public enum FocusTarget implements TranslatableEnum {
        CENTER,
        AREA;

        @Override
        public Component getTranslatedName() {
            return translated("focus_target", this);
        }
    }

    public enum FocusMissBehavior implements TranslatableEnum {
        HOLD_LAST,
        MANUAL_DISTANCE,
        FAR_LIMIT;

        @Override
        public Component getTranslatedName() {
            return translated("focus_miss", this);
        }
    }

    private static Component translated(String group, Enum<?> value) {
        return Component.translatable("options.cyclesrenderer." + group + "."
                + value.name().toLowerCase(Locale.ROOT));
    }
}

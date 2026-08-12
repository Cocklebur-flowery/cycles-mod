package dev.cyclesrenderer.config;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;

import java.util.Locale;

/** Immutable client-side settings snapshot passed to the native renderer. */
public record CyclesRenderSettings(
        long revision,
        DevicePolicy devicePolicy,
        ResolutionMode resolutionMode,
        int renderWidth,
        int renderHeight,
        int resolutionPercentage,
        boolean dynamicResolution,
        int interactiveResolutionPercentage,
        int passCacheMegabytes,
        int interactiveSamples,
        int stillSamples,
        int stationaryDelayMillis,
        boolean adaptiveSampling,
        int minimumSamples,
        float noiseThreshold,
        int interactiveTimeLimitMillis,
        int stillTimeLimitMillis,
        int minimumBounce,
        int maximumBounce,
        int diffuseBounces,
        int glossyBounces,
        int transmissionBounces,
        int volumeBounces,
        int transparentBounces,
        float clampDirect,
        float clampIndirect,
        float filterGlossy,
        boolean reflectiveCaustics,
        boolean refractiveCaustics,
        PixelFilter pixelFilter,
        float filterWidth,
        int seed,
        SamplingPattern samplingPattern,
        float cameraClipNear,
        float cameraClipFar,
        ProjectionMode projectionMode,
        float focalLengthMm,
        float sensorWidthMm,
        boolean depthOfField,
        float focusDistance,
        float fStop,
        int apertureBlades,
        float apertureRotationDegrees,
        float apertureRatio,
        boolean atmosphereSunDisc,
        float atmosphereSunSizeDegrees,
        float atmosphereSunIntensity,
        float atmosphereSunElevationDegrees,
        float atmosphereSunRotationDegrees,
        float atmosphereAltitudeMeters,
        float atmosphereAirDensity,
        float atmosphereAerosolDensity,
        float atmosphereOzoneDensity,
        DenoiserMode denoiserMode,
        int denoiserStartSample,
        DenoiserInput denoiserInput,
        DenoiserPrefilter denoiserPrefilter,
        DenoiserQuality denoiserQuality,
        boolean denoiserUseGpu,
        float exposureEv,
        float gamma,
        ViewTransform viewTransform,
        PassView activePass,
        boolean debugOverlay) {

    public interface NativeEnum {
        int nativeId();
    }

    private interface NamedEnum extends TranslatableEnum {
        String translationGroup();

        @Override
        default Component getTranslatedName() {
            String value = ((Enum<?>) this).name().toLowerCase(Locale.ROOT);
            return Component.translatable("options.cyclesrenderer." + translationGroup() + "." + value);
        }
    }

    public enum DevicePolicy implements NativeEnum, NamedEnum {
        AUTO(0), OPTIX(1), CUDA(2), CPU(3);

        private final int nativeId;

        DevicePolicy(int nativeId) {
            this.nativeId = nativeId;
        }

        @Override
        public int nativeId() {
            return nativeId;
        }

        @Override
        public String translationGroup() {
            return "device";
        }
    }

    public enum ResolutionMode implements NativeEnum, NamedEnum {
        FIT_INSIDE(0), FIXED(1);

        private final int nativeId;

        ResolutionMode(int nativeId) {
            this.nativeId = nativeId;
        }

        @Override
        public int nativeId() {
            return nativeId;
        }

        @Override
        public String translationGroup() {
            return "resolution_mode";
        }
    }

    public enum PixelFilter implements NativeEnum, NamedEnum {
        BOX(0), GAUSSIAN(1), BLACKMAN_HARRIS(2);

        private final int nativeId;

        PixelFilter(int nativeId) {
            this.nativeId = nativeId;
        }

        @Override
        public int nativeId() {
            return nativeId;
        }

        @Override
        public String translationGroup() {
            return "pixel_filter";
        }
    }

    public enum SamplingPattern implements NativeEnum, NamedEnum {
        SOBOL_BURLEY(0),
        TABULATED_SOBOL(1),
        BLUE_NOISE_PURE(2),
        BLUE_NOISE_FIRST(3),
        BLUE_NOISE_ROUND(4);

        private final int nativeId;

        SamplingPattern(int nativeId) {
            this.nativeId = nativeId;
        }

        @Override
        public int nativeId() {
            return nativeId;
        }

        @Override
        public String translationGroup() {
            return "sampling_pattern";
        }
    }

    public enum ProjectionMode implements NativeEnum, NamedEnum {
        MINECRAFT_FOV(0), PHYSICAL_LENS(1);

        private final int nativeId;

        ProjectionMode(int nativeId) {
            this.nativeId = nativeId;
        }

        @Override
        public int nativeId() {
            return nativeId;
        }

        @Override
        public String translationGroup() {
            return "projection_mode";
        }
    }

    public enum DenoiserMode implements NativeEnum, NamedEnum {
        OFF(0), AUTO(1), OPTIX(2), OPEN_IMAGE_DENOISE(3);

        private final int nativeId;

        DenoiserMode(int nativeId) {
            this.nativeId = nativeId;
        }

        @Override
        public int nativeId() {
            return nativeId;
        }

        @Override
        public String translationGroup() {
            return "denoiser";
        }
    }

    public enum DenoiserInput implements NativeEnum, NamedEnum {
        COLOR(0), ALBEDO(1), ALBEDO_NORMAL(2);

        private final int nativeId;

        DenoiserInput(int nativeId) {
            this.nativeId = nativeId;
        }

        @Override
        public int nativeId() {
            return nativeId;
        }

        @Override
        public String translationGroup() {
            return "denoiser_input";
        }
    }

    public enum DenoiserPrefilter implements NativeEnum, NamedEnum {
        NONE(0), FAST(1), ACCURATE(2);

        private final int nativeId;

        DenoiserPrefilter(int nativeId) {
            this.nativeId = nativeId;
        }

        @Override
        public int nativeId() {
            return nativeId;
        }

        @Override
        public String translationGroup() {
            return "denoiser_prefilter";
        }
    }

    public enum DenoiserQuality implements NativeEnum, NamedEnum {
        FAST(0), BALANCED(1), HIGH(2);

        private final int nativeId;

        DenoiserQuality(int nativeId) {
            this.nativeId = nativeId;
        }

        @Override
        public int nativeId() {
            return nativeId;
        }

        @Override
        public String translationGroup() {
            return "denoiser_quality";
        }
    }

    public enum ViewTransform implements NativeEnum, NamedEnum {
        STANDARD(0), RAW(1), AGX(2), KHRONOS_PBR_NEUTRAL(3), ACES_2(4);

        private final int nativeId;

        ViewTransform(int nativeId) {
            this.nativeId = nativeId;
        }

        @Override
        public int nativeId() {
            return nativeId;
        }

        @Override
        public String translationGroup() {
            return "view_transform";
        }
    }

    public enum PassView implements NativeEnum, NamedEnum {
        COMBINED(0),
        DEPTH(1),
        NORMAL(2),
        DIFFUSE_COLOR(3),
        EMISSION(4),
        ROUGHNESS(5),
        SAMPLE_COUNT(6);

        private final int nativeId;

        PassView(int nativeId) {
            this.nativeId = nativeId;
        }

        @Override
        public int nativeId() {
            return nativeId;
        }

        @Override
        public String translationGroup() {
            return "pass";
        }

        public PassView next() {
            PassView[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }
}

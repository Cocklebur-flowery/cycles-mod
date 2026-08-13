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
        PbrMode pbrMode,
        float pbrNormalStrength,
        float pbrEmissionScale,
        float pbrFallbackRoughness,
        float pbrFallbackF0,
        DenoiserMode denoiserMode,
        int denoiserStartSample,
        DenoiserInput denoiserInput,
        DenoiserPrefilter denoiserPrefilter,
        DenoiserQuality denoiserQuality,
        DlssQualityMode dlssQualityMode,
        boolean denoiserUseGpu,
        float exposureEv,
        float gamma,
        DisplayDevice displayDevice,
        ViewTransform viewTransform,
        ColorLook colorLook,
        WorkingSpace workingSpace,
        boolean whiteBalance,
        float whiteBalanceTemperature,
        float whiteBalanceTint,
        PassView activePass,
        boolean debugOverlay,
        CameraType cameraType,
        PanoramaType panoramaType,
        float fisheyeFovDegrees,
        float fisheyeLensMm,
        float latitudeMinDegrees,
        float latitudeMaxDegrees,
        float longitudeMinDegrees,
        float longitudeMaxDegrees,
        float fisheyePolynomialK0,
        float fisheyePolynomialK1,
        float fisheyePolynomialK2,
        float fisheyePolynomialK3,
        float fisheyePolynomialK4,
        float centralCylindricalLongitudeMinDegrees,
        float centralCylindricalLongitudeMaxDegrees,
        float centralCylindricalHeightMin,
        float centralCylindricalHeightMax,
        float centralCylindricalRadius,
        float cameraShiftX,
        float cameraShiftY) {

    public int pbrResourceFingerprint() {
        int result = pbrMode.hashCode();
        result = 31 * result + Float.floatToIntBits(pbrFallbackRoughness);
        return 31 * result + Float.floatToIntBits(pbrFallbackF0);
    }

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

    public enum CameraType implements NativeEnum, NamedEnum {
        PERSPECTIVE(0), PANORAMA(1);

        private final int nativeId;

        CameraType(int nativeId) {
            this.nativeId = nativeId;
        }

        @Override
        public int nativeId() {
            return nativeId;
        }

        @Override
        public String translationGroup() {
            return "camera_type";
        }
    }

    public enum PanoramaType implements NativeEnum, NamedEnum {
        EQUIRECTANGULAR(0),
        FISHEYE_EQUIDISTANT(1),
        FISHEYE_EQUISOLID(2),
        MIRRORBALL(3),
        FISHEYE_LENS_POLYNOMIAL(4),
        EQUIANGULAR_CUBEMAP_FACE(5),
        CENTRAL_CYLINDRICAL(6);

        private final int nativeId;

        PanoramaType(int nativeId) {
            this.nativeId = nativeId;
        }

        @Override
        public int nativeId() {
            return nativeId;
        }

        @Override
        public String translationGroup() {
            return "panorama_type";
        }
    }

    public enum DenoiserMode implements NativeEnum, NamedEnum {
        OFF(0), AUTO(1), OPTIX(2), OPEN_IMAGE_DENOISE(3), DLSS_EXPERIMENTAL(4);

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

        @Override
        public Component getTranslatedName() {
            return this == DLSS_EXPERIMENTAL
                    ? Component.literal("DLSS Ray Reconstruction (experimental)")
                    : NamedEnum.super.getTranslatedName();
        }
    }

    public enum PbrMode implements NativeEnum, NamedEnum {
        AUTO(0), OFF(1), LAB_PBR_1_3(2);

        private final int nativeId;

        PbrMode(int nativeId) {
            this.nativeId = nativeId;
        }

        @Override
        public int nativeId() {
            return nativeId;
        }

        @Override
        public String translationGroup() {
            return "pbr_mode";
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

    public enum DlssQualityMode implements NativeEnum, NamedEnum {
        DLAA(0), QUALITY(1), BALANCED(2), PERFORMANCE(3), ULTRA_PERFORMANCE(4);

        private final int nativeId;

        DlssQualityMode(int nativeId) {
            this.nativeId = nativeId;
        }

        @Override
        public int nativeId() {
            return nativeId;
        }

        @Override
        public String translationGroup() {
            return "dlss_quality_mode";
        }

        @Override
        public Component getTranslatedName() {
            return switch (this) {
                case DLAA -> Component.literal("DLAA (100%)");
                case QUALITY -> Component.literal("Quality (65%)");
                case BALANCED -> Component.literal("Balanced (57%)");
                case PERFORMANCE -> Component.literal("Performance (50%)");
                case ULTRA_PERFORMANCE -> Component.literal("Ultra Performance (33%)");
            };
        }
    }

    public enum DisplayDevice implements NativeEnum, NamedEnum {
        SRGB(0), DISPLAY_P3(1), REC1886(2), REC2020(3), REC2100_PQ(4), REC2100_HLG(5);

        private final int nativeId;

        DisplayDevice(int nativeId) {
            this.nativeId = nativeId;
        }

        @Override
        public int nativeId() {
            return nativeId;
        }

        @Override
        public String translationGroup() {
            return "display_device";
        }
    }

    public enum ViewTransform implements NativeEnum, NamedEnum {
        STANDARD(0), RAW(1), AGX(2), KHRONOS_PBR_NEUTRAL(3), ACES_2(4),
        ACES_1_3(5), FILMIC(6), FILMIC_LOG(7), FALSE_COLOR(8),
        ACES_1_3_HDR_1000(9), ACES_1_3_HDR_2000(10), ACES_1_3_HDR_4000(11),
        ACES_2_HDR_500(12), ACES_2_HDR_1000(13), ACES_2_HDR_2000(14),
        ACES_2_HDR_4000(15), AGX_HDR_1000(16);

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

        public boolean supports(DisplayDevice display) {
            return switch (display) {
                case SRGB -> nativeId <= FALSE_COLOR.nativeId;
                case DISPLAY_P3, REC1886, REC2020 -> this == STANDARD || this == RAW
                        || this == AGX || this == ACES_1_3 || this == ACES_2
                        || this == FALSE_COLOR;
                case REC2100_PQ -> this != KHRONOS_PBR_NEUTRAL && this != FILMIC
                        && this != FILMIC_LOG;
                case REC2100_HLG -> this == STANDARD || this == RAW || this == AGX
                        || this == ACES_1_3 || this == ACES_2 || this == FALSE_COLOR
                        || this == ACES_1_3_HDR_1000 || this == ACES_2_HDR_1000
                        || this == AGX_HDR_1000;
            };
        }

        public ViewTransform effectiveFor(DisplayDevice display) {
            return supports(display) ? this : AGX;
        }
    }

    public enum ColorLook implements NativeEnum, NamedEnum {
        NONE(0),
        AGX_PUNCHY(1),
        AGX_VERY_HIGH_CONTRAST(2),
        AGX_HIGH_CONTRAST(3),
        AGX_MEDIUM_HIGH_CONTRAST(4),
        AGX_BASE_CONTRAST(5),
        AGX_MEDIUM_LOW_CONTRAST(6),
        AGX_LOW_CONTRAST(7),
        AGX_VERY_LOW_CONTRAST(8),
        AGX_GREYSCALE(9),
        FILMIC_VERY_HIGH_CONTRAST(10), FILMIC_HIGH_CONTRAST(11),
        FILMIC_MEDIUM_HIGH_CONTRAST(12), FILMIC_MEDIUM_CONTRAST(13),
        FILMIC_MEDIUM_LOW_CONTRAST(14), FILMIC_LOW_CONTRAST(15),
        FILMIC_VERY_LOW_CONTRAST(16),
        FALSE_COLOR_PUNCHY(17), FALSE_COLOR_VERY_HIGH_CONTRAST(18),
        FALSE_COLOR_HIGH_CONTRAST(19), FALSE_COLOR_MEDIUM_HIGH_CONTRAST(20),
        FALSE_COLOR_BASE_CONTRAST(21), FALSE_COLOR_MEDIUM_LOW_CONTRAST(22),
        FALSE_COLOR_LOW_CONTRAST(23), FALSE_COLOR_VERY_LOW_CONTRAST(24),
        FALSE_COLOR_GREYSCALE(25), ACES_1_3_GAMUT_COMPRESSION(26),
        ACES_2_GAMUT_COMPRESSION(27);

        private final int nativeId;

        ColorLook(int nativeId) {
            this.nativeId = nativeId;
        }

        @Override
        public int nativeId() {
            return nativeId;
        }

        @Override
        public String translationGroup() {
            return "color_look";
        }

        public int effectiveNativeId(ViewTransform viewTransform) {
            if (this == NONE) {
                return nativeId;
            }
            if (nativeId <= AGX_GREYSCALE.nativeId) {
                return viewTransform == ViewTransform.AGX
                        || viewTransform == ViewTransform.AGX_HDR_1000 ? nativeId : NONE.nativeId;
            }
            if (nativeId <= FILMIC_VERY_LOW_CONTRAST.nativeId) {
                return viewTransform == ViewTransform.FILMIC
                        || viewTransform == ViewTransform.FILMIC_LOG ? nativeId : NONE.nativeId;
            }
            if (nativeId <= FALSE_COLOR_GREYSCALE.nativeId) {
                return viewTransform == ViewTransform.FALSE_COLOR ? nativeId : NONE.nativeId;
            }
            if (this == ACES_1_3_GAMUT_COMPRESSION) {
                return viewTransform == ViewTransform.ACES_1_3
                        || viewTransform.nativeId >= ViewTransform.ACES_1_3_HDR_1000.nativeId
                        && viewTransform.nativeId <= ViewTransform.ACES_1_3_HDR_4000.nativeId
                        ? nativeId : NONE.nativeId;
            }
            return viewTransform == ViewTransform.ACES_2
                    || viewTransform.nativeId >= ViewTransform.ACES_2_HDR_500.nativeId
                    && viewTransform.nativeId <= ViewTransform.ACES_2_HDR_4000.nativeId
                    ? nativeId : NONE.nativeId;
        }
    }

    public enum WorkingSpace implements NativeEnum, NamedEnum {
        LINEAR_REC709(0), LINEAR_REC2020(1), ACESCG(2);

        private final int nativeId;

        WorkingSpace(int nativeId) {
            this.nativeId = nativeId;
        }

        @Override
        public int nativeId() {
            return nativeId;
        }

        @Override
        public String translationGroup() {
            return "working_space";
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

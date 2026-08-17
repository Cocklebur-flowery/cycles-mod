package dev.cyclesrenderer.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class CyclesClientConfig {
    public static final int SCHEMA_VERSION = 1;
    public static final ModConfigSpec SPEC;
    private static final List<SettingsOption<?>> OPTIONS;

    private static final AtomicLong REVISION = new AtomicLong(1L);

    private static final ModConfigSpec.IntValue SCHEMA;
    static final ModConfigSpec.EnumValue<CyclesRenderSettings.DevicePolicy> DEVICE_POLICY;
    static final ModConfigSpec.EnumValue<CyclesRenderSettings.ResolutionMode> RESOLUTION_MODE;
    static final ModConfigSpec.IntValue RENDER_WIDTH;
    static final ModConfigSpec.IntValue RENDER_HEIGHT;
    static final ModConfigSpec.IntValue RESOLUTION_PERCENTAGE;
    static final ModConfigSpec.BooleanValue DYNAMIC_RESOLUTION;
    static final ModConfigSpec.IntValue INTERACTIVE_RESOLUTION_PERCENTAGE;
    static final ModConfigSpec.IntValue PASS_CACHE_MEGABYTES;
    static final ModConfigSpec.IntValue INTERACTIVE_SAMPLES;
    static final ModConfigSpec.IntValue STILL_SAMPLES;
    static final ModConfigSpec.IntValue STATIONARY_DELAY;
    static final ModConfigSpec.BooleanValue ADAPTIVE_SAMPLING;
    static final ModConfigSpec.IntValue MINIMUM_SAMPLES;
    static final ModConfigSpec.DoubleValue NOISE_THRESHOLD;
    static final ModConfigSpec.IntValue INTERACTIVE_TIME_LIMIT;
    static final ModConfigSpec.IntValue STILL_TIME_LIMIT;
    static final ModConfigSpec.EnumValue<CyclesRenderSettings.SamplingPattern> SAMPLING_PATTERN;
    static final ModConfigSpec.DoubleValue CAMERA_CLIP_NEAR;
    static final ModConfigSpec.DoubleValue CAMERA_CLIP_FAR;
    static final ModConfigSpec.EnumValue<CyclesRenderSettings.CameraType> CAMERA_TYPE;
    static final ModConfigSpec.EnumValue<CyclesRenderSettings.ProjectionMode> CAMERA_PROJECTION;
    static final ModConfigSpec.EnumValue<CyclesRenderSettings.PanoramaType> CAMERA_PANORAMA_TYPE;
    static final ModConfigSpec.DoubleValue CAMERA_FOCAL_LENGTH;
    static final ModConfigSpec.DoubleValue CAMERA_SENSOR_WIDTH;
    static final ModConfigSpec.DoubleValue CAMERA_FISHEYE_FOV;
    static final ModConfigSpec.DoubleValue CAMERA_FISHEYE_LENS;
    static final ModConfigSpec.DoubleValue CAMERA_LATITUDE_MIN;
    static final ModConfigSpec.DoubleValue CAMERA_LATITUDE_MAX;
    static final ModConfigSpec.DoubleValue CAMERA_LONGITUDE_MIN;
    static final ModConfigSpec.DoubleValue CAMERA_LONGITUDE_MAX;
    static final ModConfigSpec.DoubleValue CAMERA_POLYNOMIAL_K0;
    static final ModConfigSpec.DoubleValue CAMERA_POLYNOMIAL_K1;
    static final ModConfigSpec.DoubleValue CAMERA_POLYNOMIAL_K2;
    static final ModConfigSpec.DoubleValue CAMERA_POLYNOMIAL_K3;
    static final ModConfigSpec.DoubleValue CAMERA_POLYNOMIAL_K4;
    static final ModConfigSpec.DoubleValue CAMERA_CYLINDRICAL_LONGITUDE_MIN;
    static final ModConfigSpec.DoubleValue CAMERA_CYLINDRICAL_LONGITUDE_MAX;
    static final ModConfigSpec.DoubleValue CAMERA_CYLINDRICAL_HEIGHT_MIN;
    static final ModConfigSpec.DoubleValue CAMERA_CYLINDRICAL_HEIGHT_MAX;
    static final ModConfigSpec.DoubleValue CAMERA_CYLINDRICAL_RADIUS;
    static final ModConfigSpec.DoubleValue CAMERA_SHIFT_X;
    static final ModConfigSpec.DoubleValue CAMERA_SHIFT_Y;
    static final ModConfigSpec.BooleanValue CAMERA_SAFE_AREAS;
    static final ModConfigSpec.DoubleValue CAMERA_TITLE_SAFE_X;
    static final ModConfigSpec.DoubleValue CAMERA_TITLE_SAFE_Y;
    static final ModConfigSpec.DoubleValue CAMERA_ACTION_SAFE_X;
    static final ModConfigSpec.DoubleValue CAMERA_ACTION_SAFE_Y;
    static final ModConfigSpec.BooleanValue CAMERA_CENTER_CUT_SAFE_AREAS;
    static final ModConfigSpec.DoubleValue CAMERA_CENTER_TITLE_SAFE_X;
    static final ModConfigSpec.DoubleValue CAMERA_CENTER_TITLE_SAFE_Y;
    static final ModConfigSpec.DoubleValue CAMERA_CENTER_ACTION_SAFE_X;
    static final ModConfigSpec.DoubleValue CAMERA_CENTER_ACTION_SAFE_Y;
    static final ModConfigSpec.BooleanValue CAMERA_DEPTH_OF_FIELD;
    static final ModConfigSpec.EnumValue<CyclesRenderSettings.DepthOfFieldMode>
            CAMERA_DEPTH_OF_FIELD_MODE;
    static final ModConfigSpec.DoubleValue CAMERA_FOCUS_DISTANCE;
    static final ModConfigSpec.DoubleValue CAMERA_F_STOP;
    static final ModConfigSpec.BooleanValue CAMERA_APERTURE_CIRCULAR;
    static final ModConfigSpec.IntValue CAMERA_APERTURE_BLADES;
    static final ModConfigSpec.DoubleValue CAMERA_APERTURE_ROTATION;
    static final ModConfigSpec.DoubleValue CAMERA_APERTURE_RATIO;
    static final ModConfigSpec.BooleanValue ATMOSPHERE_SUN_DISC;
    static final ModConfigSpec.DoubleValue ATMOSPHERE_SUN_SIZE;
    static final ModConfigSpec.DoubleValue ATMOSPHERE_SUN_INTENSITY;
    static final ModConfigSpec.DoubleValue ATMOSPHERE_SUN_ELEVATION;
    static final ModConfigSpec.DoubleValue ATMOSPHERE_SUN_ROTATION;
    static final ModConfigSpec.DoubleValue ATMOSPHERE_ALTITUDE;
    static final ModConfigSpec.DoubleValue ATMOSPHERE_AIR_DENSITY;
    static final ModConfigSpec.DoubleValue ATMOSPHERE_AEROSOL_DENSITY;
    static final ModConfigSpec.DoubleValue ATMOSPHERE_OZONE_DENSITY;
    static final ModConfigSpec.EnumValue<CyclesRenderSettings.PbrMode> PBR_MODE;
    static final ModConfigSpec.DoubleValue PBR_NORMAL_STRENGTH;
    static final ModConfigSpec.DoubleValue PBR_EMISSION_SCALE;
    static final ModConfigSpec.DoubleValue PBR_FALLBACK_ROUGHNESS;
    static final ModConfigSpec.DoubleValue PBR_FALLBACK_F0;
    static final LabPbrConfig LAB_PBR;
    static final ModConfigSpec.IntValue MINIMUM_BOUNCE;
    static final ModConfigSpec.IntValue MAXIMUM_BOUNCE;
    static final ModConfigSpec.IntValue DIFFUSE_BOUNCES;
    static final ModConfigSpec.IntValue GLOSSY_BOUNCES;
    static final ModConfigSpec.IntValue TRANSMISSION_BOUNCES;
    static final ModConfigSpec.IntValue VOLUME_BOUNCES;
    static final ModConfigSpec.IntValue TRANSPARENT_BOUNCES;
    static final ModConfigSpec.DoubleValue CLAMP_DIRECT;
    static final ModConfigSpec.DoubleValue CLAMP_INDIRECT;
    static final ModConfigSpec.DoubleValue FILTER_GLOSSY;
    static final ModConfigSpec.BooleanValue REFLECTIVE_CAUSTICS;
    static final ModConfigSpec.BooleanValue REFRACTIVE_CAUSTICS;
    static final ModConfigSpec.EnumValue<CyclesRenderSettings.PixelFilter> PIXEL_FILTER;
    static final ModConfigSpec.DoubleValue FILTER_WIDTH;
    static final ModConfigSpec.IntValue SEED;
    static final ModConfigSpec.EnumValue<CyclesRenderSettings.DenoiserMode> DENOISER_MODE;
    static final ModConfigSpec.IntValue DENOISER_START_SAMPLE;
    static final ModConfigSpec.EnumValue<CyclesRenderSettings.DenoiserInput> DENOISER_INPUT;
    static final ModConfigSpec.EnumValue<CyclesRenderSettings.DenoiserPrefilter> DENOISER_PREFILTER;
    static final ModConfigSpec.EnumValue<CyclesRenderSettings.DenoiserQuality> DENOISER_QUALITY;
    static final ModConfigSpec.EnumValue<CyclesRenderSettings.DlssQualityMode> DLSS_QUALITY_MODE;
    static final ModConfigSpec.BooleanValue DENOISER_USE_GPU;
    static final ModConfigSpec.DoubleValue EXPOSURE_EV;
    static final ModConfigSpec.DoubleValue GAMMA;
    static final ModConfigSpec.EnumValue<CyclesRenderSettings.DisplayDevice> DISPLAY_DEVICE;
    static final ModConfigSpec.EnumValue<CyclesRenderSettings.ViewTransform> VIEW_TRANSFORM;
    static final ModConfigSpec.EnumValue<CyclesRenderSettings.ColorLook> COLOR_LOOK;
    static final ModConfigSpec.EnumValue<CyclesRenderSettings.WorkingSpace> WORKING_SPACE;
    static final ModConfigSpec.BooleanValue WHITE_BALANCE;
    static final ModConfigSpec.DoubleValue WHITE_BALANCE_TEMPERATURE;
    static final ModConfigSpec.DoubleValue WHITE_BALANCE_TINT;
    static final ModConfigSpec.EnumValue<CyclesRenderSettings.PassView> ACTIVE_PASS;
    static final ModConfigSpec.BooleanValue DEBUG_OVERLAY;
    static final ModConfigSpec.BooleanValue REPROJECTION_ENABLED;
    static final CameraAutomationConfig CAMERA_AUTOMATION;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        SCHEMA = builder.comment("Configuration schema; reserved for migrations.")
                .defineInRange("schemaVersion", SCHEMA_VERSION, SCHEMA_VERSION, SCHEMA_VERSION);

        builder.push("device");
        DEVICE_POLICY = builder.translation("config.cyclesrenderer.device.policy")
                .comment("Preferred Cycles device. AUTO falls back through OptiX, CUDA and CPU.")
                .defineEnum("policy", CyclesRenderSettings.DevicePolicy.AUTO);
        builder.pop();

        builder.push("output");
        RESOLUTION_MODE = builder.translation("config.cyclesrenderer.output.resolutionMode")
                .defineEnum("resolutionMode", CyclesRenderSettings.ResolutionMode.FIT_INSIDE);
        RENDER_WIDTH = builder.translation("config.cyclesrenderer.output.width")
                .comment("Internal render width cap or fixed width.")
                .defineInRange("width", 480, 160, 3840);
        RENDER_HEIGHT = builder.translation("config.cyclesrenderer.output.height")
                .comment("Internal render height cap or fixed height.")
                .defineInRange("height", 270, 90, 2160);
        RESOLUTION_PERCENTAGE = builder.translation("config.cyclesrenderer.output.percentage")
                .defineInRange("percentage", 100, 25, 100);
        DYNAMIC_RESOLUTION = builder.translation("config.cyclesrenderer.output.dynamicResolution")
                .comment("Use a lower internal resolution while the camera or scene is changing.")
                .define("dynamicResolution", false);
        INTERACTIVE_RESOLUTION_PERCENTAGE = builder.translation(
                        "config.cyclesrenderer.output.interactivePercentage")
                .comment("Interactive percentage of the configured internal width and height.")
                .defineInRange("interactivePercentage", 50, 25, 100);
        PASS_CACHE_MEGABYTES = builder.translation("config.cyclesrenderer.output.passCacheMegabytes")
                .comment("Maximum native memory used to retain previously viewed HDR passes.")
                .defineInRange("passCacheMegabytes", 256, 64, 4096);
        builder.pop();

        builder.push("sampling");
        INTERACTIVE_SAMPLES = builder.translation("config.cyclesrenderer.sampling.interactiveSamples")
                .defineInRange("interactiveSamples", 1, 1, 4096);
        STILL_SAMPLES = builder.translation("config.cyclesrenderer.sampling.stillSamples")
                .defineInRange("stillSamples", 8, 1, 4096);
        STATIONARY_DELAY = builder.translation("config.cyclesrenderer.sampling.stationaryDelay")
                .comment("Milliseconds without camera or scene changes before still sampling begins.")
                .defineInRange("stationaryDelayMillis", 150, 0, 10000);
        ADAPTIVE_SAMPLING = builder.translation("config.cyclesrenderer.sampling.adaptive")
                .define("adaptive", false);
        MINIMUM_SAMPLES = builder.translation("config.cyclesrenderer.sampling.minimumSamples")
                .defineInRange("minimumSamples", 0, 0, 4096);
        NOISE_THRESHOLD = builder.translation("config.cyclesrenderer.sampling.noiseThreshold")
                .defineInRange("noiseThreshold", 0.01D, 0.0D, 1.0D);
        INTERACTIVE_TIME_LIMIT = builder.translation("config.cyclesrenderer.sampling.interactiveTimeLimit")
                .comment("Milliseconds; zero disables the limit.")
                .defineInRange("interactiveTimeLimitMillis", 0, 0, 60000);
        STILL_TIME_LIMIT = builder.translation("config.cyclesrenderer.sampling.stillTimeLimit")
                .comment("Milliseconds; zero disables the limit.")
                .defineInRange("stillTimeLimitMillis", 0, 0, 600000);
        SAMPLING_PATTERN = builder.translation("config.cyclesrenderer.sampling.pattern")
                .comment("Native Cycles 5.2 sampling sequence; blue-noise scrambling is performed by Cycles.")
                .defineEnum("pattern", CyclesRenderSettings.SamplingPattern.BLUE_NOISE_FIRST);
        builder.pop();

        builder.push("camera");
        CAMERA_CLIP_NEAR = builder.translation("config.cyclesrenderer.camera.clipNear")
                .comment("Near clipping plane in blocks/meters.")
                .defineInRange("clipNear", 0.05D, 0.001D, 10.0D);
        CAMERA_CLIP_FAR = builder.translation("config.cyclesrenderer.camera.clipFar")
                .comment("Far clipping plane in blocks/meters; zero follows the Minecraft camera.")
                .defineInRange("clipFar", 0.0D, 0.0D, 1000000.0D);
        CAMERA_TYPE = builder.translation("config.cyclesrenderer.camera.type")
                .defineEnum("type", CyclesRenderSettings.CameraType.PERSPECTIVE);
        CAMERA_PROJECTION = builder.translation("config.cyclesrenderer.camera.projection")
                .comment("Minecraft FOV preserves gameplay camera effects; physical lens overrides FOV.")
                .defineEnum("projection", CyclesRenderSettings.ProjectionMode.MINECRAFT_FOV);
        CAMERA_PANORAMA_TYPE = builder.translation("config.cyclesrenderer.camera.panoramaType")
                .defineEnum("panoramaType", CyclesRenderSettings.PanoramaType.EQUIRECTANGULAR);
        CAMERA_FOCAL_LENGTH = builder.translation("config.cyclesrenderer.camera.focalLength")
                .comment("Physical focal length in millimeters; also controls DOF aperture size.")
                .defineInRange("focalLengthMm", 50.0D, 1.0D, 300.0D);
        CAMERA_SENSOR_WIDTH = builder.translation("config.cyclesrenderer.camera.sensorWidth")
                .comment("Horizontal sensor width in millimeters.")
                .defineInRange("sensorWidthMm", 36.0D, 1.0D, 100.0D);
        CAMERA_FISHEYE_FOV = builder.translation("config.cyclesrenderer.camera.fisheyeFov")
                .defineInRange("fisheyeFovDegrees", 180.0D, 10.0D, 1800.0D);
        CAMERA_FISHEYE_LENS = builder.translation("config.cyclesrenderer.camera.fisheyeLens")
                .defineInRange("fisheyeLensMm", 10.5D, 0.01D, 100.0D);
        CAMERA_LATITUDE_MIN = builder.translation("config.cyclesrenderer.camera.latitudeMin")
                .defineInRange("latitudeMinDegrees", -90.0D, -90.0D, 90.0D);
        CAMERA_LATITUDE_MAX = builder.translation("config.cyclesrenderer.camera.latitudeMax")
                .defineInRange("latitudeMaxDegrees", 90.0D, -90.0D, 90.0D);
        CAMERA_LONGITUDE_MIN = builder.translation("config.cyclesrenderer.camera.longitudeMin")
                .defineInRange("longitudeMinDegrees", -180.0D, -180.0D, 180.0D);
        CAMERA_LONGITUDE_MAX = builder.translation("config.cyclesrenderer.camera.longitudeMax")
                .defineInRange("longitudeMaxDegrees", 180.0D, -180.0D, 180.0D);
        CAMERA_POLYNOMIAL_K0 = builder.translation("config.cyclesrenderer.camera.polynomialK0")
                .defineInRange("fisheyePolynomialK0", -1.1735143712967577e-05D, -1000000.0D, 1000000.0D);
        CAMERA_POLYNOMIAL_K1 = builder.translation("config.cyclesrenderer.camera.polynomialK1")
                .defineInRange("fisheyePolynomialK1", -0.019988736953434998D, -1000000.0D, 1000000.0D);
        CAMERA_POLYNOMIAL_K2 = builder.translation("config.cyclesrenderer.camera.polynomialK2")
                .defineInRange("fisheyePolynomialK2", -3.3525322965709175e-06D, -1000000.0D, 1000000.0D);
        CAMERA_POLYNOMIAL_K3 = builder.translation("config.cyclesrenderer.camera.polynomialK3")
                .defineInRange("fisheyePolynomialK3", 3.099275275886036e-06D, -1000000.0D, 1000000.0D);
        CAMERA_POLYNOMIAL_K4 = builder.translation("config.cyclesrenderer.camera.polynomialK4")
                .defineInRange("fisheyePolynomialK4", -2.6064646454854524e-08D, -1000000.0D, 1000000.0D);
        CAMERA_CYLINDRICAL_LONGITUDE_MIN = builder.translation(
                        "config.cyclesrenderer.camera.cylindricalLongitudeMin")
                .defineInRange("centralCylindricalLongitudeMinDegrees", -180.0D, -180.0D, 180.0D);
        CAMERA_CYLINDRICAL_LONGITUDE_MAX = builder.translation(
                        "config.cyclesrenderer.camera.cylindricalLongitudeMax")
                .defineInRange("centralCylindricalLongitudeMaxDegrees", 180.0D, -180.0D, 180.0D);
        CAMERA_CYLINDRICAL_HEIGHT_MIN = builder.translation(
                        "config.cyclesrenderer.camera.cylindricalHeightMin")
                .defineInRange("centralCylindricalHeightMin", -1.0D, -10.0D, 10.0D);
        CAMERA_CYLINDRICAL_HEIGHT_MAX = builder.translation(
                        "config.cyclesrenderer.camera.cylindricalHeightMax")
                .defineInRange("centralCylindricalHeightMax", 1.0D, -10.0D, 10.0D);
        CAMERA_CYLINDRICAL_RADIUS = builder.translation("config.cyclesrenderer.camera.cylindricalRadius")
                .defineInRange("centralCylindricalRadius", 1.0D, 0.00001D, 1000000.0D);
        CAMERA_SHIFT_X = builder.translation("config.cyclesrenderer.camera.shiftX")
                .defineInRange("shiftX", 0.0D, -10.0D, 10.0D);
        CAMERA_SHIFT_Y = builder.translation("config.cyclesrenderer.camera.shiftY")
                .defineInRange("shiftY", 0.0D, -10.0D, 10.0D);
        CAMERA_SAFE_AREAS = builder.translation("config.cyclesrenderer.camera.safeAreas")
                .define("safeAreas", false);
        CAMERA_TITLE_SAFE_X = builder.translation("config.cyclesrenderer.camera.titleSafeX")
                .defineInRange("titleSafeX", 0.10D, 0.0D, 0.5D);
        CAMERA_TITLE_SAFE_Y = builder.translation("config.cyclesrenderer.camera.titleSafeY")
                .defineInRange("titleSafeY", 0.05D, 0.0D, 0.5D);
        CAMERA_ACTION_SAFE_X = builder.translation("config.cyclesrenderer.camera.actionSafeX")
                .defineInRange("actionSafeX", 0.035D, 0.0D, 0.5D);
        CAMERA_ACTION_SAFE_Y = builder.translation("config.cyclesrenderer.camera.actionSafeY")
                .defineInRange("actionSafeY", 0.035D, 0.0D, 0.5D);
        CAMERA_CENTER_CUT_SAFE_AREAS = builder.translation(
                        "config.cyclesrenderer.camera.centerCutSafeAreas")
                .define("centerCutSafeAreas", false);
        CAMERA_CENTER_TITLE_SAFE_X = builder.translation("config.cyclesrenderer.camera.centerTitleSafeX")
                .defineInRange("centerTitleSafeX", 0.175D, 0.0D, 0.5D);
        CAMERA_CENTER_TITLE_SAFE_Y = builder.translation("config.cyclesrenderer.camera.centerTitleSafeY")
                .defineInRange("centerTitleSafeY", 0.05D, 0.0D, 0.5D);
        CAMERA_CENTER_ACTION_SAFE_X = builder.translation("config.cyclesrenderer.camera.centerActionSafeX")
                .defineInRange("centerActionSafeX", 0.15D, 0.0D, 0.5D);
        CAMERA_CENTER_ACTION_SAFE_Y = builder.translation("config.cyclesrenderer.camera.centerActionSafeY")
                .defineInRange("centerActionSafeY", 0.05D, 0.0D, 0.5D);
        CAMERA_DEPTH_OF_FIELD = builder.translation("config.cyclesrenderer.camera.depthOfField")
                .define("depthOfField", false);
        CAMERA_DEPTH_OF_FIELD_MODE = builder
                .translation("config.cyclesrenderer.camera.depthOfFieldMode")
                .comment("Choose physically sampled Cycles depth of field or a post-process blur.")
                .defineEnum("depthOfFieldMode", CyclesRenderSettings.DepthOfFieldMode.PHYSICAL);
        CAMERA_FOCUS_DISTANCE = builder.translation("config.cyclesrenderer.camera.focusDistance")
                .comment("Focus plane distance in blocks/meters.")
                .defineInRange("focusDistance", 10.0D, 0.01D, 1000000.0D);
        CAMERA_F_STOP = builder.translation("config.cyclesrenderer.camera.fStop")
                .defineInRange("fStop", 2.8D, 0.1D, 128.0D);
        CAMERA_APERTURE_CIRCULAR = builder.translation("config.cyclesrenderer.camera.apertureCircular")
                .comment("Use a circular aperture; otherwise the configured blade count is used.")
                .define("apertureCircular", true);
        CAMERA_APERTURE_BLADES = builder.translation("config.cyclesrenderer.camera.apertureBlades")
                .defineInRange("apertureBlades", 6, 3, 16);
        CAMERA_APERTURE_ROTATION = builder.translation("config.cyclesrenderer.camera.apertureRotation")
                .defineInRange("apertureRotationDegrees", 0.0D, -360.0D, 360.0D);
        CAMERA_APERTURE_RATIO = builder.translation("config.cyclesrenderer.camera.apertureRatio")
                .defineInRange("apertureRatio", 1.0D, 0.1D, 10.0D);
        builder.pop();

        builder.push("atmosphere");
        ATMOSPHERE_SUN_DISC = builder.translation("config.cyclesrenderer.atmosphere.sunDisc")
                .define("sunDisc", true);
        ATMOSPHERE_SUN_SIZE = builder.translation("config.cyclesrenderer.atmosphere.sunSize")
                .comment("Angular diameter of the procedural sun in degrees.")
                .defineInRange("sunSizeDegrees", 0.545D, 0.01D, 180.0D);
        ATMOSPHERE_SUN_INTENSITY = builder.translation("config.cyclesrenderer.atmosphere.sunIntensity")
                .defineInRange("sunIntensity", 1.0D, 0.0D, 1000.0D);
        ATMOSPHERE_SUN_ELEVATION = builder.translation("config.cyclesrenderer.atmosphere.sunElevation")
                .defineInRange("sunElevationDegrees", 45.0D, -90.0D, 90.0D);
        ATMOSPHERE_SUN_ROTATION = builder.translation("config.cyclesrenderer.atmosphere.sunRotation")
                .defineInRange("sunRotationDegrees", 35.0D, -360.0D, 360.0D);
        ATMOSPHERE_ALTITUDE = builder.translation("config.cyclesrenderer.atmosphere.altitude")
                .comment("Observer altitude above sea level in meters.")
                .defineInRange("altitudeMeters", 1000.0D, 0.0D, 60000.0D);
        ATMOSPHERE_AIR_DENSITY = builder.translation("config.cyclesrenderer.atmosphere.airDensity")
                .defineInRange("airDensity", 1.0D, 0.0D, 10.0D);
        ATMOSPHERE_AEROSOL_DENSITY = builder.translation("config.cyclesrenderer.atmosphere.aerosolDensity")
                .defineInRange("aerosolDensity", 1.0D, 0.0D, 10.0D);
        ATMOSPHERE_OZONE_DENSITY = builder.translation("config.cyclesrenderer.atmosphere.ozoneDensity")
                .defineInRange("ozoneDensity", 2.0D, 0.0D, 10.0D);
        builder.pop();

        builder.push("materials");
        PBR_MODE = builder.translation("config.cyclesrenderer.materials.pbrMode")
                .comment("Auto follows optifine/texture.properties; forced LabPBR 1.3 also works without the declaration file.")
                .defineEnum("pbrMode", CyclesRenderSettings.PbrMode.AUTO);
        PBR_NORMAL_STRENGTH = builder.translation("config.cyclesrenderer.materials.normalStrength")
                .defineInRange("normalStrength", 1.0D, 0.0D, 4.0D);
        PBR_EMISSION_SCALE = builder.translation("config.cyclesrenderer.materials.emissionScale")
                .defineInRange("emissionScale", 1.0D, 0.0D, 100.0D);
        PBR_FALLBACK_ROUGHNESS = builder.translation("config.cyclesrenderer.materials.fallbackRoughness")
                .comment("Principled roughness used by sprites without a LabPBR specular companion.")
                .defineInRange("fallbackRoughness", 0.8D, 0.0D, 1.0D);
        PBR_FALLBACK_F0 = builder.translation("config.cyclesrenderer.materials.fallbackF0")
                .comment("Linear dielectric F0 used by sprites without a LabPBR specular companion.")
                .defineInRange("fallbackF0", 0.04D, 0.0D, 0.08D);
        LAB_PBR = LabPbrConfig.define(builder);
        builder.pop();

        builder.push("lightPaths");
        MINIMUM_BOUNCE = builder.translation("config.cyclesrenderer.lightPaths.minimumBounce")
                .defineInRange("minimumBounce", 0, 0, 64);
        MAXIMUM_BOUNCE = builder.translation("config.cyclesrenderer.lightPaths.maximumBounce")
                .defineInRange("maximumBounce", 3, 0, 64);
        DIFFUSE_BOUNCES = builder.translation("config.cyclesrenderer.lightPaths.diffuseBounces")
                .defineInRange("diffuseBounces", 2, 0, 64);
        GLOSSY_BOUNCES = builder.translation("config.cyclesrenderer.lightPaths.glossyBounces")
                .defineInRange("glossyBounces", 1, 0, 64);
        TRANSMISSION_BOUNCES = builder.translation("config.cyclesrenderer.lightPaths.transmissionBounces")
                .defineInRange("transmissionBounces", 2, 0, 64);
        VOLUME_BOUNCES = builder.translation("config.cyclesrenderer.lightPaths.volumeBounces")
                .defineInRange("volumeBounces", 0, 0, 64);
        TRANSPARENT_BOUNCES = builder.translation("config.cyclesrenderer.lightPaths.transparentBounces")
                .defineInRange("transparentBounces", 32, 0, 64);
        CLAMP_DIRECT = builder.translation("config.cyclesrenderer.lightPaths.clampDirect")
                .defineInRange("clampDirect", 0.0D, 0.0D, 100000.0D);
        CLAMP_INDIRECT = builder.translation("config.cyclesrenderer.lightPaths.clampIndirect")
                .defineInRange("clampIndirect", 10.0D, 0.0D, 100000.0D);
        FILTER_GLOSSY = builder.translation("config.cyclesrenderer.lightPaths.filterGlossy")
                .defineInRange("filterGlossy", 0.0D, 0.0D, 100.0D);
        REFLECTIVE_CAUSTICS = builder.translation("config.cyclesrenderer.lightPaths.reflectiveCaustics")
                .define("reflectiveCaustics", false);
        REFRACTIVE_CAUSTICS = builder.translation("config.cyclesrenderer.lightPaths.refractiveCaustics")
                .define("refractiveCaustics", false);
        builder.pop();

        builder.push("filter");
        PIXEL_FILTER = builder.translation("config.cyclesrenderer.filter.type")
                .defineEnum("type", CyclesRenderSettings.PixelFilter.BOX);
        FILTER_WIDTH = builder.translation("config.cyclesrenderer.filter.width")
                .defineInRange("width", 1.0D, 0.01D, 10.0D);
        SEED = builder.translation("config.cyclesrenderer.filter.seed")
                .defineInRange("seed", 0, 0, Integer.MAX_VALUE);
        builder.pop();

        builder.push("denoise");
        DENOISER_MODE = builder.translation("config.cyclesrenderer.denoise.mode")
                .defineEnum("mode", CyclesRenderSettings.DenoiserMode.OFF);
        DENOISER_START_SAMPLE = builder.translation("config.cyclesrenderer.denoise.startSample")
                .defineInRange("startSample", 1, 1, 4096);
        DENOISER_INPUT = builder.translation("config.cyclesrenderer.denoise.input")
                .defineEnum("input", CyclesRenderSettings.DenoiserInput.ALBEDO_NORMAL);
        DENOISER_PREFILTER = builder.translation("config.cyclesrenderer.denoise.prefilter")
                .defineEnum("prefilter", CyclesRenderSettings.DenoiserPrefilter.FAST);
        DENOISER_QUALITY = builder.translation("config.cyclesrenderer.denoise.quality")
                .defineEnum("quality", CyclesRenderSettings.DenoiserQuality.BALANCED);
        DLSS_QUALITY_MODE = builder
                .comment("DLSS Ray Reconstruction input resolution mode; ignored by other denoisers.")
                .defineEnum("dlssMode", CyclesRenderSettings.DlssQualityMode.QUALITY);
        DENOISER_USE_GPU = builder.translation("config.cyclesrenderer.denoise.useGpu")
                .define("useGpu", true);
        builder.pop();

        builder.push("colorManagement");
        EXPOSURE_EV = builder.translation("config.cyclesrenderer.color.exposure")
                .defineInRange("exposureEv", 0.0D, -20.0D, 20.0D);
        GAMMA = builder.translation("config.cyclesrenderer.color.gamma")
                .defineInRange("gamma", 1.0D, 0.1D, 5.0D);
        DISPLAY_DEVICE = builder.translation("config.cyclesrenderer.color.display")
                .defineEnum("display", CyclesRenderSettings.DisplayDevice.SRGB);
        VIEW_TRANSFORM = builder.translation("config.cyclesrenderer.color.viewTransform")
                .comment("AgX and Khronos PBR Neutral require the packaged OCIO display pipeline.")
                .defineEnum("viewTransform", CyclesRenderSettings.ViewTransform.AGX);
        COLOR_LOOK = builder.translation("config.cyclesrenderer.color.look")
                .comment("AgX looks come directly from Blender's packaged OCIO configuration.")
                .defineEnum("look", CyclesRenderSettings.ColorLook.AGX_PUNCHY);
        WORKING_SPACE = builder.translation("config.cyclesrenderer.color.workingSpace")
                .comment("Scene-linear render space used by Cycles and the OCIO display pipeline.")
                .defineEnum("workingSpace", CyclesRenderSettings.WorkingSpace.LINEAR_REC709);
        WHITE_BALANCE = builder.translation("config.cyclesrenderer.color.whiteBalance")
                .define("whiteBalance", false);
        WHITE_BALANCE_TEMPERATURE = builder.translation("config.cyclesrenderer.color.temperature")
                .defineInRange("temperature", 6500.0D, 1800.0D, 100000.0D);
        WHITE_BALANCE_TINT = builder.translation("config.cyclesrenderer.color.tint")
                .defineInRange("tint", 10.0D, -150.0D, 150.0D);
        builder.pop();

        builder.push("diagnostics");
        ACTIVE_PASS = builder.translation("config.cyclesrenderer.diagnostics.activePass")
                .defineEnum("activePass", CyclesRenderSettings.PassView.COMBINED);
        DEBUG_OVERLAY = builder.translation("config.cyclesrenderer.diagnostics.debugOverlay")
                .define("debugOverlay", false);
        builder.pop();

        builder.push("performance");
        builder.push("reprojection");
        REPROJECTION_ENABLED = builder
                .translation("config.cyclesrenderer.performance.reprojection")
                .comment("Reproject the latest complete Cycles color/depth frame to the current camera.")
                .define("enabled", false);
        builder.pop();
        builder.pop();

        CAMERA_AUTOMATION = CameraAutomationConfig.define(builder);

        SPEC = builder.build();
        OPTIONS = SettingsCatalog.buildOptions();
    }

    private CyclesClientConfig() {
    }

    public static CyclesRenderSettings snapshot() {
        return new CyclesRenderSettings(
                REVISION.get(),
                DEVICE_POLICY.get(), RESOLUTION_MODE.get(),
                RENDER_WIDTH.get(), RENDER_HEIGHT.get(), RESOLUTION_PERCENTAGE.get(),
                DYNAMIC_RESOLUTION.get(), INTERACTIVE_RESOLUTION_PERCENTAGE.get(),
                PASS_CACHE_MEGABYTES.get(),
                INTERACTIVE_SAMPLES.get(), STILL_SAMPLES.get(), STATIONARY_DELAY.get(),
                ADAPTIVE_SAMPLING.get(), MINIMUM_SAMPLES.get(), NOISE_THRESHOLD.get().floatValue(),
                INTERACTIVE_TIME_LIMIT.get(), STILL_TIME_LIMIT.get(),
                MINIMUM_BOUNCE.get(), MAXIMUM_BOUNCE.get(), DIFFUSE_BOUNCES.get(),
                GLOSSY_BOUNCES.get(), TRANSMISSION_BOUNCES.get(), VOLUME_BOUNCES.get(),
                TRANSPARENT_BOUNCES.get(), CLAMP_DIRECT.get().floatValue(),
                CLAMP_INDIRECT.get().floatValue(), FILTER_GLOSSY.get().floatValue(),
                REFLECTIVE_CAUSTICS.get(), REFRACTIVE_CAUSTICS.get(),
                PIXEL_FILTER.get(), FILTER_WIDTH.get().floatValue(), SEED.get(),
                SAMPLING_PATTERN.get(),
                CAMERA_CLIP_NEAR.get().floatValue(), CAMERA_CLIP_FAR.get().floatValue(),
                CAMERA_PROJECTION.get(), CAMERA_FOCAL_LENGTH.get().floatValue(),
                CAMERA_SENSOR_WIDTH.get().floatValue(), CAMERA_DEPTH_OF_FIELD.get(),
                CAMERA_DEPTH_OF_FIELD_MODE.get(),
                CAMERA_FOCUS_DISTANCE.get().floatValue(), CAMERA_F_STOP.get().floatValue(),
                CAMERA_APERTURE_CIRCULAR.get() ? 0 : CAMERA_APERTURE_BLADES.get(),
                CAMERA_APERTURE_ROTATION.get().floatValue(),
                CAMERA_APERTURE_RATIO.get().floatValue(),
                ATMOSPHERE_SUN_DISC.get(), ATMOSPHERE_SUN_SIZE.get().floatValue(),
                ATMOSPHERE_SUN_INTENSITY.get().floatValue(),
                ATMOSPHERE_SUN_ELEVATION.get().floatValue(),
                ATMOSPHERE_SUN_ROTATION.get().floatValue(),
                ATMOSPHERE_ALTITUDE.get().floatValue(),
                ATMOSPHERE_AIR_DENSITY.get().floatValue(),
                ATMOSPHERE_AEROSOL_DENSITY.get().floatValue(),
                ATMOSPHERE_OZONE_DENSITY.get().floatValue(),
                PBR_MODE.get(), PBR_NORMAL_STRENGTH.get().floatValue(),
                PBR_EMISSION_SCALE.get().floatValue(),
                LAB_PBR.wetness(), LAB_PBR.subsurfaceScale(),
                LAB_PBR.heightStrength(), LAB_PBR.heightDistance(),
                LAB_PBR.heightMappingMode(), LAB_PBR.parallaxSteps(),
                PBR_FALLBACK_ROUGHNESS.get().floatValue(),
                PBR_FALLBACK_F0.get().floatValue(),
                DENOISER_MODE.get(), DENOISER_START_SAMPLE.get(), DENOISER_INPUT.get(),
                DENOISER_PREFILTER.get(), DENOISER_QUALITY.get(), DLSS_QUALITY_MODE.get(),
                DENOISER_USE_GPU.get(),
                EXPOSURE_EV.get().floatValue(), GAMMA.get().floatValue(), DISPLAY_DEVICE.get(),
                VIEW_TRANSFORM.get(),
                COLOR_LOOK.get(),
                WORKING_SPACE.get(),
                WHITE_BALANCE.get(), WHITE_BALANCE_TEMPERATURE.get().floatValue(),
                WHITE_BALANCE_TINT.get().floatValue(),
                ACTIVE_PASS.get(), DEBUG_OVERLAY.get(),
                CAMERA_TYPE.get(), CAMERA_PANORAMA_TYPE.get(),
                CAMERA_FISHEYE_FOV.get().floatValue(),
                CAMERA_FISHEYE_LENS.get().floatValue(),
                CAMERA_LATITUDE_MIN.get().floatValue(),
                CAMERA_LATITUDE_MAX.get().floatValue(),
                CAMERA_LONGITUDE_MIN.get().floatValue(),
                CAMERA_LONGITUDE_MAX.get().floatValue(),
                CAMERA_POLYNOMIAL_K0.get().floatValue(),
                CAMERA_POLYNOMIAL_K1.get().floatValue(),
                CAMERA_POLYNOMIAL_K2.get().floatValue(),
                CAMERA_POLYNOMIAL_K3.get().floatValue(),
                CAMERA_POLYNOMIAL_K4.get().floatValue(),
                CAMERA_CYLINDRICAL_LONGITUDE_MIN.get().floatValue(),
                CAMERA_CYLINDRICAL_LONGITUDE_MAX.get().floatValue(),
                CAMERA_CYLINDRICAL_HEIGHT_MIN.get().floatValue(),
                CAMERA_CYLINDRICAL_HEIGHT_MAX.get().floatValue(),
                CAMERA_CYLINDRICAL_RADIUS.get().floatValue(),
                CAMERA_SHIFT_X.get().floatValue(), CAMERA_SHIFT_Y.get().floatValue(),
                CAMERA_SAFE_AREAS.get(),
                CAMERA_TITLE_SAFE_X.get().floatValue(),
                CAMERA_TITLE_SAFE_Y.get().floatValue(),
                CAMERA_ACTION_SAFE_X.get().floatValue(),
                CAMERA_ACTION_SAFE_Y.get().floatValue(),
                CAMERA_CENTER_CUT_SAFE_AREAS.get(),
                CAMERA_CENTER_TITLE_SAFE_X.get().floatValue(),
                CAMERA_CENTER_TITLE_SAFE_Y.get().floatValue(),
                CAMERA_CENTER_ACTION_SAFE_X.get().floatValue(),
                CAMERA_CENTER_ACTION_SAFE_Y.get().floatValue(),
                CAMERA_AUTOMATION.snapshot(),
                REPROJECTION_ENABLED.get());
    }

    public static void markReloaded() {
        REVISION.incrementAndGet();
    }

    public static void setActivePass(CyclesRenderSettings.PassView pass) {
        ACTIVE_PASS.set(pass);
        changedAndSave();
    }

    public static void setDebugOverlay(boolean enabled) {
        DEBUG_OVERLAY.set(enabled);
        changedAndSave();
    }

    public static List<SettingsOption<?>> options() {
        return OPTIONS;
    }

    public static SettingsDraft draft() {
        return new SettingsDraft(OPTIONS);
    }

    public static boolean apply(SettingsDraft draft) {
        List<SettingsDraft.Change> changes = draft.changes();
        if (changes.isEmpty()) {
            return false;
        }
        for (SettingsDraft.Change change : changes) {
            change.option().writeUnchecked(change.value());
        }
        draft.accept(changes);
        changedAndSave();
        return true;
    }

    public enum Category {
        DEVICE,
        OUTPUT,
        SAMPLING,
        LIGHT_PATHS,
        FILTER,
        DENOISE,
        CAMERA,
        ATMOSPHERE,
        MATERIALS,
        COLOR,
        DIAGNOSTICS,
        PERFORMANCE
    }

    public enum ValueKind {
        BOOLEAN,
        INTEGER,
        DOUBLE,
        ENUM
    }

    private static void changedAndSave() {
        REVISION.incrementAndGet();
        SPEC.save();
    }
}

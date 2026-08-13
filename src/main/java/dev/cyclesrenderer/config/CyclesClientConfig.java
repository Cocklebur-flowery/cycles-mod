package dev.cyclesrenderer.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.concurrent.atomic.AtomicLong;

public final class CyclesClientConfig {
    public static final int SCHEMA_VERSION = 1;
    public static final ModConfigSpec SPEC;

    private static final AtomicLong REVISION = new AtomicLong(1L);

    private static final ModConfigSpec.IntValue SCHEMA;
    private static final ModConfigSpec.EnumValue<CyclesRenderSettings.DevicePolicy> DEVICE_POLICY;
    private static final ModConfigSpec.EnumValue<CyclesRenderSettings.ResolutionMode> RESOLUTION_MODE;
    private static final ModConfigSpec.IntValue RENDER_WIDTH;
    private static final ModConfigSpec.IntValue RENDER_HEIGHT;
    private static final ModConfigSpec.IntValue RESOLUTION_PERCENTAGE;
    private static final ModConfigSpec.BooleanValue DYNAMIC_RESOLUTION;
    private static final ModConfigSpec.IntValue INTERACTIVE_RESOLUTION_PERCENTAGE;
    private static final ModConfigSpec.IntValue PASS_CACHE_MEGABYTES;
    private static final ModConfigSpec.IntValue INTERACTIVE_SAMPLES;
    private static final ModConfigSpec.IntValue STILL_SAMPLES;
    private static final ModConfigSpec.IntValue STATIONARY_DELAY;
    private static final ModConfigSpec.BooleanValue ADAPTIVE_SAMPLING;
    private static final ModConfigSpec.IntValue MINIMUM_SAMPLES;
    private static final ModConfigSpec.DoubleValue NOISE_THRESHOLD;
    private static final ModConfigSpec.IntValue INTERACTIVE_TIME_LIMIT;
    private static final ModConfigSpec.IntValue STILL_TIME_LIMIT;
    private static final ModConfigSpec.EnumValue<CyclesRenderSettings.SamplingPattern> SAMPLING_PATTERN;
    private static final ModConfigSpec.DoubleValue CAMERA_CLIP_NEAR;
    private static final ModConfigSpec.DoubleValue CAMERA_CLIP_FAR;
    private static final ModConfigSpec.EnumValue<CyclesRenderSettings.ProjectionMode> CAMERA_PROJECTION;
    private static final ModConfigSpec.DoubleValue CAMERA_FOCAL_LENGTH;
    private static final ModConfigSpec.DoubleValue CAMERA_SENSOR_WIDTH;
    private static final ModConfigSpec.BooleanValue CAMERA_DEPTH_OF_FIELD;
    private static final ModConfigSpec.DoubleValue CAMERA_FOCUS_DISTANCE;
    private static final ModConfigSpec.DoubleValue CAMERA_F_STOP;
    private static final ModConfigSpec.BooleanValue CAMERA_APERTURE_CIRCULAR;
    private static final ModConfigSpec.IntValue CAMERA_APERTURE_BLADES;
    private static final ModConfigSpec.DoubleValue CAMERA_APERTURE_ROTATION;
    private static final ModConfigSpec.DoubleValue CAMERA_APERTURE_RATIO;
    private static final ModConfigSpec.BooleanValue ATMOSPHERE_SUN_DISC;
    private static final ModConfigSpec.DoubleValue ATMOSPHERE_SUN_SIZE;
    private static final ModConfigSpec.DoubleValue ATMOSPHERE_SUN_INTENSITY;
    private static final ModConfigSpec.DoubleValue ATMOSPHERE_SUN_ELEVATION;
    private static final ModConfigSpec.DoubleValue ATMOSPHERE_SUN_ROTATION;
    private static final ModConfigSpec.DoubleValue ATMOSPHERE_ALTITUDE;
    private static final ModConfigSpec.DoubleValue ATMOSPHERE_AIR_DENSITY;
    private static final ModConfigSpec.DoubleValue ATMOSPHERE_AEROSOL_DENSITY;
    private static final ModConfigSpec.DoubleValue ATMOSPHERE_OZONE_DENSITY;
    private static final ModConfigSpec.EnumValue<CyclesRenderSettings.PbrMode> PBR_MODE;
    private static final ModConfigSpec.DoubleValue PBR_NORMAL_STRENGTH;
    private static final ModConfigSpec.DoubleValue PBR_EMISSION_SCALE;
    private static final ModConfigSpec.DoubleValue PBR_FALLBACK_ROUGHNESS;
    private static final ModConfigSpec.DoubleValue PBR_FALLBACK_F0;
    private static final ModConfigSpec.IntValue MINIMUM_BOUNCE;
    private static final ModConfigSpec.IntValue MAXIMUM_BOUNCE;
    private static final ModConfigSpec.IntValue DIFFUSE_BOUNCES;
    private static final ModConfigSpec.IntValue GLOSSY_BOUNCES;
    private static final ModConfigSpec.IntValue TRANSMISSION_BOUNCES;
    private static final ModConfigSpec.IntValue VOLUME_BOUNCES;
    private static final ModConfigSpec.IntValue TRANSPARENT_BOUNCES;
    private static final ModConfigSpec.DoubleValue CLAMP_DIRECT;
    private static final ModConfigSpec.DoubleValue CLAMP_INDIRECT;
    private static final ModConfigSpec.DoubleValue FILTER_GLOSSY;
    private static final ModConfigSpec.BooleanValue REFLECTIVE_CAUSTICS;
    private static final ModConfigSpec.BooleanValue REFRACTIVE_CAUSTICS;
    private static final ModConfigSpec.EnumValue<CyclesRenderSettings.PixelFilter> PIXEL_FILTER;
    private static final ModConfigSpec.DoubleValue FILTER_WIDTH;
    private static final ModConfigSpec.IntValue SEED;
    private static final ModConfigSpec.EnumValue<CyclesRenderSettings.DenoiserMode> DENOISER_MODE;
    private static final ModConfigSpec.IntValue DENOISER_START_SAMPLE;
    private static final ModConfigSpec.EnumValue<CyclesRenderSettings.DenoiserInput> DENOISER_INPUT;
    private static final ModConfigSpec.EnumValue<CyclesRenderSettings.DenoiserPrefilter> DENOISER_PREFILTER;
    private static final ModConfigSpec.EnumValue<CyclesRenderSettings.DenoiserQuality> DENOISER_QUALITY;
    private static final ModConfigSpec.BooleanValue DENOISER_USE_GPU;
    private static final ModConfigSpec.DoubleValue EXPOSURE_EV;
    private static final ModConfigSpec.DoubleValue GAMMA;
    private static final ModConfigSpec.EnumValue<CyclesRenderSettings.DisplayDevice> DISPLAY_DEVICE;
    private static final ModConfigSpec.EnumValue<CyclesRenderSettings.ViewTransform> VIEW_TRANSFORM;
    private static final ModConfigSpec.EnumValue<CyclesRenderSettings.ColorLook> COLOR_LOOK;
    private static final ModConfigSpec.EnumValue<CyclesRenderSettings.WorkingSpace> WORKING_SPACE;
    private static final ModConfigSpec.BooleanValue WHITE_BALANCE;
    private static final ModConfigSpec.DoubleValue WHITE_BALANCE_TEMPERATURE;
    private static final ModConfigSpec.DoubleValue WHITE_BALANCE_TINT;
    private static final ModConfigSpec.EnumValue<CyclesRenderSettings.PassView> ACTIVE_PASS;
    private static final ModConfigSpec.BooleanValue DEBUG_OVERLAY;

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
        CAMERA_PROJECTION = builder.translation("config.cyclesrenderer.camera.projection")
                .comment("Minecraft FOV preserves gameplay camera effects; physical lens overrides FOV.")
                .defineEnum("projection", CyclesRenderSettings.ProjectionMode.MINECRAFT_FOV);
        CAMERA_FOCAL_LENGTH = builder.translation("config.cyclesrenderer.camera.focalLength")
                .comment("Physical focal length in millimeters; also controls DOF aperture size.")
                .defineInRange("focalLengthMm", 50.0D, 1.0D, 300.0D);
        CAMERA_SENSOR_WIDTH = builder.translation("config.cyclesrenderer.camera.sensorWidth")
                .comment("Horizontal sensor width in millimeters.")
                .defineInRange("sensorWidthMm", 36.0D, 1.0D, 100.0D);
        CAMERA_DEPTH_OF_FIELD = builder.translation("config.cyclesrenderer.camera.depthOfField")
                .define("depthOfField", false);
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
                .defineInRange("transmissionBounces", 0, 0, 64);
        VOLUME_BOUNCES = builder.translation("config.cyclesrenderer.lightPaths.volumeBounces")
                .defineInRange("volumeBounces", 0, 0, 64);
        TRANSPARENT_BOUNCES = builder.translation("config.cyclesrenderer.lightPaths.transparentBounces")
                .defineInRange("transparentBounces", 0, 0, 64);
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

        SPEC = builder.build();
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
                PBR_FALLBACK_ROUGHNESS.get().floatValue(),
                PBR_FALLBACK_F0.get().floatValue(),
                DENOISER_MODE.get(), DENOISER_START_SAMPLE.get(), DENOISER_INPUT.get(),
                DENOISER_PREFILTER.get(), DENOISER_QUALITY.get(), DENOISER_USE_GPU.get(),
                EXPOSURE_EV.get().floatValue(), GAMMA.get().floatValue(), DISPLAY_DEVICE.get(),
                VIEW_TRANSFORM.get(),
                COLOR_LOOK.get(),
                WORKING_SPACE.get(),
                WHITE_BALANCE.get(), WHITE_BALANCE_TEMPERATURE.get().floatValue(),
                WHITE_BALANCE_TINT.get().floatValue(),
                ACTIVE_PASS.get(), DEBUG_OVERLAY.get());
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

    private static void changedAndSave() {
        REVISION.incrementAndGet();
        SPEC.save();
    }
}

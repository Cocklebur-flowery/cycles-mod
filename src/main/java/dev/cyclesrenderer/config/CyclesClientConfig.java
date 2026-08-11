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
    private static final ModConfigSpec.IntValue INTERACTIVE_SAMPLES;
    private static final ModConfigSpec.IntValue STILL_SAMPLES;
    private static final ModConfigSpec.IntValue STATIONARY_DELAY;
    private static final ModConfigSpec.BooleanValue ADAPTIVE_SAMPLING;
    private static final ModConfigSpec.IntValue MINIMUM_SAMPLES;
    private static final ModConfigSpec.DoubleValue NOISE_THRESHOLD;
    private static final ModConfigSpec.IntValue INTERACTIVE_TIME_LIMIT;
    private static final ModConfigSpec.IntValue STILL_TIME_LIMIT;
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
    private static final ModConfigSpec.EnumValue<CyclesRenderSettings.ViewTransform> VIEW_TRANSFORM;
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
        VIEW_TRANSFORM = builder.translation("config.cyclesrenderer.color.viewTransform")
                .comment("AgX and Khronos PBR Neutral require the packaged OCIO display pipeline.")
                .defineEnum("viewTransform", CyclesRenderSettings.ViewTransform.STANDARD);
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
                INTERACTIVE_SAMPLES.get(), STILL_SAMPLES.get(), STATIONARY_DELAY.get(),
                ADAPTIVE_SAMPLING.get(), MINIMUM_SAMPLES.get(), NOISE_THRESHOLD.get().floatValue(),
                INTERACTIVE_TIME_LIMIT.get(), STILL_TIME_LIMIT.get(),
                MINIMUM_BOUNCE.get(), MAXIMUM_BOUNCE.get(), DIFFUSE_BOUNCES.get(),
                GLOSSY_BOUNCES.get(), TRANSMISSION_BOUNCES.get(), VOLUME_BOUNCES.get(),
                TRANSPARENT_BOUNCES.get(), CLAMP_DIRECT.get().floatValue(),
                CLAMP_INDIRECT.get().floatValue(), FILTER_GLOSSY.get().floatValue(),
                REFLECTIVE_CAUSTICS.get(), REFRACTIVE_CAUSTICS.get(),
                PIXEL_FILTER.get(), FILTER_WIDTH.get().floatValue(), SEED.get(),
                DENOISER_MODE.get(), DENOISER_START_SAMPLE.get(), DENOISER_INPUT.get(),
                DENOISER_PREFILTER.get(), DENOISER_QUALITY.get(), DENOISER_USE_GPU.get(),
                EXPOSURE_EV.get().floatValue(), GAMMA.get().floatValue(), VIEW_TRANSFORM.get(),
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

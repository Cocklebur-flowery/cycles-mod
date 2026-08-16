package dev.cyclesrenderer.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static dev.cyclesrenderer.config.CyclesClientConfig.*;

/** Ordered editor catalog bound to the NeoForge client configuration values. */
final class SettingsCatalog {
    private SettingsCatalog() {
    }

    static List<SettingsOption<?>> buildOptions() {
        List<SettingsOption<?>> options = new ArrayList<>();
        options.add(enumOption("device.policy", Category.DEVICE,
                "config.cyclesrenderer.device.policy", DEVICE_POLICY,
                CyclesRenderSettings.DevicePolicy.values()));

        options.add(enumOption("output.resolutionMode", Category.OUTPUT,
                "config.cyclesrenderer.output.resolutionMode", RESOLUTION_MODE,
                CyclesRenderSettings.ResolutionMode.values()));
        options.add(intOption("output.width", Category.OUTPUT,
                "config.cyclesrenderer.output.width", RENDER_WIDTH, 160, 3840, 16));
        options.add(intOption("output.height", Category.OUTPUT,
                "config.cyclesrenderer.output.height", RENDER_HEIGHT, 90, 2160, 9));
        options.add(intOption("output.percentage", Category.OUTPUT,
                "config.cyclesrenderer.output.percentage", RESOLUTION_PERCENTAGE, 25, 100, 1));
        options.add(booleanOption("output.dynamicResolution", Category.OUTPUT,
                "config.cyclesrenderer.output.dynamicResolution", DYNAMIC_RESOLUTION));
        options.add(intOption("output.interactivePercentage", Category.OUTPUT,
                "config.cyclesrenderer.output.interactivePercentage",
                INTERACTIVE_RESOLUTION_PERCENTAGE, 25, 100, 1));
        options.add(intOption("output.passCacheMegabytes", Category.OUTPUT,
                "config.cyclesrenderer.output.passCacheMegabytes",
                PASS_CACHE_MEGABYTES, 64, 4096, 64));

        options.add(intOption("sampling.interactiveSamples", Category.SAMPLING,
                "config.cyclesrenderer.sampling.interactiveSamples",
                INTERACTIVE_SAMPLES, 1, 4096, 1));
        options.add(intOption("sampling.stillSamples", Category.SAMPLING,
                "config.cyclesrenderer.sampling.stillSamples", STILL_SAMPLES, 1, 4096, 1));
        options.add(intOption("sampling.stationaryDelay", Category.SAMPLING,
                "config.cyclesrenderer.sampling.stationaryDelay", STATIONARY_DELAY, 0, 10000, 10));
        options.add(booleanOption("sampling.adaptive", Category.SAMPLING,
                "config.cyclesrenderer.sampling.adaptive", ADAPTIVE_SAMPLING));
        options.add(intOption("sampling.minimumSamples", Category.SAMPLING,
                "config.cyclesrenderer.sampling.minimumSamples", MINIMUM_SAMPLES, 0, 4096, 1));
        options.add(doubleOption("sampling.noiseThreshold", Category.SAMPLING,
                "config.cyclesrenderer.sampling.noiseThreshold", NOISE_THRESHOLD, 0.0D, 1.0D, 0.001D));
        options.add(intOption("sampling.interactiveTimeLimit", Category.SAMPLING,
                "config.cyclesrenderer.sampling.interactiveTimeLimit",
                INTERACTIVE_TIME_LIMIT, 0, 60000, 10));
        options.add(intOption("sampling.stillTimeLimit", Category.SAMPLING,
                "config.cyclesrenderer.sampling.stillTimeLimit", STILL_TIME_LIMIT, 0, 600000, 100));
        options.add(enumOption("sampling.pattern", Category.SAMPLING,
                "config.cyclesrenderer.sampling.pattern", SAMPLING_PATTERN,
                CyclesRenderSettings.SamplingPattern.values()));

        options.add(intOption("lightPaths.minimumBounce", Category.LIGHT_PATHS,
                "config.cyclesrenderer.lightPaths.minimumBounce", MINIMUM_BOUNCE, 0, 64, 1));
        options.add(intOption("lightPaths.maximumBounce", Category.LIGHT_PATHS,
                "config.cyclesrenderer.lightPaths.maximumBounce", MAXIMUM_BOUNCE, 0, 64, 1));
        options.add(intOption("lightPaths.diffuseBounces", Category.LIGHT_PATHS,
                "config.cyclesrenderer.lightPaths.diffuseBounces", DIFFUSE_BOUNCES, 0, 64, 1));
        options.add(intOption("lightPaths.glossyBounces", Category.LIGHT_PATHS,
                "config.cyclesrenderer.lightPaths.glossyBounces", GLOSSY_BOUNCES, 0, 64, 1));
        options.add(intOption("lightPaths.transmissionBounces", Category.LIGHT_PATHS,
                "config.cyclesrenderer.lightPaths.transmissionBounces", TRANSMISSION_BOUNCES, 0, 64, 1));
        options.add(intOption("lightPaths.volumeBounces", Category.LIGHT_PATHS,
                "config.cyclesrenderer.lightPaths.volumeBounces", VOLUME_BOUNCES, 0, 64, 1));
        options.add(intOption("lightPaths.transparentBounces", Category.LIGHT_PATHS,
                "config.cyclesrenderer.lightPaths.transparentBounces", TRANSPARENT_BOUNCES, 0, 64, 1));
        options.add(doubleOption("lightPaths.clampDirect", Category.LIGHT_PATHS,
                "config.cyclesrenderer.lightPaths.clampDirect", CLAMP_DIRECT, 0.0D, 100000.0D, 0.1D));
        options.add(doubleOption("lightPaths.clampIndirect", Category.LIGHT_PATHS,
                "config.cyclesrenderer.lightPaths.clampIndirect", CLAMP_INDIRECT, 0.0D, 100000.0D, 0.1D));
        options.add(doubleOption("lightPaths.filterGlossy", Category.LIGHT_PATHS,
                "config.cyclesrenderer.lightPaths.filterGlossy", FILTER_GLOSSY, 0.0D, 100.0D, 0.1D));
        options.add(booleanOption("lightPaths.reflectiveCaustics", Category.LIGHT_PATHS,
                "config.cyclesrenderer.lightPaths.reflectiveCaustics", REFLECTIVE_CAUSTICS));
        options.add(booleanOption("lightPaths.refractiveCaustics", Category.LIGHT_PATHS,
                "config.cyclesrenderer.lightPaths.refractiveCaustics", REFRACTIVE_CAUSTICS));

        options.add(enumOption("filter.type", Category.FILTER,
                "config.cyclesrenderer.filter.type", PIXEL_FILTER,
                CyclesRenderSettings.PixelFilter.values()));
        options.add(doubleOption("filter.width", Category.FILTER,
                "config.cyclesrenderer.filter.width", FILTER_WIDTH, 0.01D, 10.0D, 0.01D));
        options.add(intOption("filter.seed", Category.FILTER,
                "config.cyclesrenderer.filter.seed", SEED, 0, Integer.MAX_VALUE, 1));

        options.add(enumOption("denoise.mode", Category.DENOISE,
                "config.cyclesrenderer.denoise.mode", DENOISER_MODE,
                CyclesRenderSettings.DenoiserMode.values()));
        options.add(intOption("denoise.startSample", Category.DENOISE,
                "config.cyclesrenderer.denoise.startSample", DENOISER_START_SAMPLE, 1, 4096, 1));
        options.add(enumOption("denoise.input", Category.DENOISE,
                "config.cyclesrenderer.denoise.input", DENOISER_INPUT,
                CyclesRenderSettings.DenoiserInput.values()));
        options.add(enumOption("denoise.prefilter", Category.DENOISE,
                "config.cyclesrenderer.denoise.prefilter", DENOISER_PREFILTER,
                CyclesRenderSettings.DenoiserPrefilter.values()));
        options.add(enumOption("denoise.quality", Category.DENOISE,
                "config.cyclesrenderer.denoise.quality", DENOISER_QUALITY,
                CyclesRenderSettings.DenoiserQuality.values()));
        options.add(enumOption("denoise.dlssMode", Category.DENOISE,
                "config.cyclesrenderer.denoise.dlssMode", DLSS_QUALITY_MODE,
                CyclesRenderSettings.DlssQualityMode.values()));
        options.add(booleanOption("denoise.useGpu", Category.DENOISE,
                "config.cyclesrenderer.denoise.useGpu", DENOISER_USE_GPU));

        options.add(doubleOption("camera.clipNear", Category.CAMERA,
                "config.cyclesrenderer.camera.clipNear", CAMERA_CLIP_NEAR, 0.001D, 10.0D, 0.001D));
        options.add(doubleOption("camera.clipFar", Category.CAMERA,
                "config.cyclesrenderer.camera.clipFar", CAMERA_CLIP_FAR, 0.0D, 1000000.0D, 1.0D));
        options.add(enumOption("camera.type", Category.CAMERA,
                "config.cyclesrenderer.camera.type", CAMERA_TYPE,
                CyclesRenderSettings.CameraType.values()));
        options.add(enumOption("camera.projection", Category.CAMERA,
                "config.cyclesrenderer.camera.projection", CAMERA_PROJECTION,
                CyclesRenderSettings.ProjectionMode.values()));
        options.add(enumOption("camera.panoramaType", Category.CAMERA,
                "config.cyclesrenderer.camera.panoramaType", CAMERA_PANORAMA_TYPE,
                new CyclesRenderSettings.PanoramaType[] {
                    CyclesRenderSettings.PanoramaType.EQUIRECTANGULAR,
                    CyclesRenderSettings.PanoramaType.EQUIANGULAR_CUBEMAP_FACE,
                    CyclesRenderSettings.PanoramaType.MIRRORBALL,
                    CyclesRenderSettings.PanoramaType.FISHEYE_EQUIDISTANT,
                    CyclesRenderSettings.PanoramaType.FISHEYE_EQUISOLID,
                    CyclesRenderSettings.PanoramaType.FISHEYE_LENS_POLYNOMIAL,
                    CyclesRenderSettings.PanoramaType.CENTRAL_CYLINDRICAL
                }));
        options.add(doubleOption("camera.focalLength", Category.CAMERA,
                "config.cyclesrenderer.camera.focalLength", CAMERA_FOCAL_LENGTH, 1.0D, 300.0D, 0.1D));
        options.add(doubleOption("camera.sensorWidth", Category.CAMERA,
                "config.cyclesrenderer.camera.sensorWidth", CAMERA_SENSOR_WIDTH, 1.0D, 100.0D, 0.1D));
        options.add(doubleOption("camera.fisheyeFov", Category.CAMERA,
                "config.cyclesrenderer.camera.fisheyeFov", CAMERA_FISHEYE_FOV, 10.0D, 1800.0D, 1.0D));
        options.add(doubleOption("camera.fisheyeLens", Category.CAMERA,
                "config.cyclesrenderer.camera.fisheyeLens", CAMERA_FISHEYE_LENS, 0.01D, 100.0D, 0.01D));
        options.add(doubleOption("camera.latitudeMin", Category.CAMERA,
                "config.cyclesrenderer.camera.latitudeMin", CAMERA_LATITUDE_MIN, -90.0D, 90.0D, 1.0D));
        options.add(doubleOption("camera.latitudeMax", Category.CAMERA,
                "config.cyclesrenderer.camera.latitudeMax", CAMERA_LATITUDE_MAX, -90.0D, 90.0D, 1.0D));
        options.add(doubleOption("camera.longitudeMin", Category.CAMERA,
                "config.cyclesrenderer.camera.longitudeMin", CAMERA_LONGITUDE_MIN, -180.0D, 180.0D, 1.0D));
        options.add(doubleOption("camera.longitudeMax", Category.CAMERA,
                "config.cyclesrenderer.camera.longitudeMax", CAMERA_LONGITUDE_MAX, -180.0D, 180.0D, 1.0D));
        options.add(doubleOption("camera.polynomialK0", Category.CAMERA,
                "config.cyclesrenderer.camera.polynomialK0", CAMERA_POLYNOMIAL_K0, -1000000.0D, 1000000.0D, 0.000001D));
        options.add(doubleOption("camera.polynomialK1", Category.CAMERA,
                "config.cyclesrenderer.camera.polynomialK1", CAMERA_POLYNOMIAL_K1, -1000000.0D, 1000000.0D, 0.000001D));
        options.add(doubleOption("camera.polynomialK2", Category.CAMERA,
                "config.cyclesrenderer.camera.polynomialK2", CAMERA_POLYNOMIAL_K2, -1000000.0D, 1000000.0D, 0.000001D));
        options.add(doubleOption("camera.polynomialK3", Category.CAMERA,
                "config.cyclesrenderer.camera.polynomialK3", CAMERA_POLYNOMIAL_K3, -1000000.0D, 1000000.0D, 0.000001D));
        options.add(doubleOption("camera.polynomialK4", Category.CAMERA,
                "config.cyclesrenderer.camera.polynomialK4", CAMERA_POLYNOMIAL_K4, -1000000.0D, 1000000.0D, 0.000001D));
        options.add(doubleOption("camera.cylindricalLongitudeMin", Category.CAMERA,
                "config.cyclesrenderer.camera.cylindricalLongitudeMin", CAMERA_CYLINDRICAL_LONGITUDE_MIN, -180.0D, 180.0D, 1.0D));
        options.add(doubleOption("camera.cylindricalLongitudeMax", Category.CAMERA,
                "config.cyclesrenderer.camera.cylindricalLongitudeMax", CAMERA_CYLINDRICAL_LONGITUDE_MAX, -180.0D, 180.0D, 1.0D));
        options.add(doubleOption("camera.cylindricalHeightMin", Category.CAMERA,
                "config.cyclesrenderer.camera.cylindricalHeightMin", CAMERA_CYLINDRICAL_HEIGHT_MIN, -10.0D, 10.0D, 0.1D));
        options.add(doubleOption("camera.cylindricalHeightMax", Category.CAMERA,
                "config.cyclesrenderer.camera.cylindricalHeightMax", CAMERA_CYLINDRICAL_HEIGHT_MAX, -10.0D, 10.0D, 0.1D));
        options.add(doubleOption("camera.cylindricalRadius", Category.CAMERA,
                "config.cyclesrenderer.camera.cylindricalRadius", CAMERA_CYLINDRICAL_RADIUS, 0.00001D, 1000000.0D, 0.01D));
        options.add(doubleOption("camera.shiftX", Category.CAMERA,
                "config.cyclesrenderer.camera.shiftX", CAMERA_SHIFT_X, -10.0D, 10.0D, 0.001D));
        options.add(doubleOption("camera.shiftY", Category.CAMERA,
                "config.cyclesrenderer.camera.shiftY", CAMERA_SHIFT_Y, -10.0D, 10.0D, 0.001D));
        options.add(booleanOption("camera.depthOfField", Category.CAMERA,
                "config.cyclesrenderer.camera.depthOfField", CAMERA_DEPTH_OF_FIELD));
        options.add(enumOption("camera.depthOfFieldMode", Category.CAMERA,
                "config.cyclesrenderer.camera.depthOfFieldMode", CAMERA_DEPTH_OF_FIELD_MODE,
                CyclesRenderSettings.DepthOfFieldMode.values()));
        options.add(doubleOption("camera.focusDistance", Category.CAMERA,
                "config.cyclesrenderer.camera.focusDistance", CAMERA_FOCUS_DISTANCE, 0.01D, 1000000.0D, 0.01D));
        options.add(doubleOption("camera.fStop", Category.CAMERA,
                "config.cyclesrenderer.camera.fStop", CAMERA_F_STOP, 0.1D, 128.0D, 0.1D));
        options.add(booleanOption("camera.apertureCircular", Category.CAMERA,
                "config.cyclesrenderer.camera.apertureCircular", CAMERA_APERTURE_CIRCULAR));
        options.add(intOption("camera.apertureBlades", Category.CAMERA,
                "config.cyclesrenderer.camera.apertureBlades", CAMERA_APERTURE_BLADES, 3, 16, 1));
        options.add(doubleOption("camera.apertureRotation", Category.CAMERA,
                "config.cyclesrenderer.camera.apertureRotation", CAMERA_APERTURE_ROTATION, -360.0D, 360.0D, 1.0D));
        options.add(doubleOption("camera.apertureRatio", Category.CAMERA,
                "config.cyclesrenderer.camera.apertureRatio", CAMERA_APERTURE_RATIO, 0.1D, 10.0D, 0.01D));
        options.add(booleanOption("camera.safeAreas", Category.CAMERA,
                "config.cyclesrenderer.camera.safeAreas", CAMERA_SAFE_AREAS));
        options.add(doubleOption("camera.titleSafeX", Category.CAMERA,
                "config.cyclesrenderer.camera.titleSafeX", CAMERA_TITLE_SAFE_X, 0.0D, 0.5D, 0.001D));
        options.add(doubleOption("camera.titleSafeY", Category.CAMERA,
                "config.cyclesrenderer.camera.titleSafeY", CAMERA_TITLE_SAFE_Y, 0.0D, 0.5D, 0.001D));
        options.add(doubleOption("camera.actionSafeX", Category.CAMERA,
                "config.cyclesrenderer.camera.actionSafeX", CAMERA_ACTION_SAFE_X, 0.0D, 0.5D, 0.001D));
        options.add(doubleOption("camera.actionSafeY", Category.CAMERA,
                "config.cyclesrenderer.camera.actionSafeY", CAMERA_ACTION_SAFE_Y, 0.0D, 0.5D, 0.001D));
        options.add(booleanOption("camera.centerCutSafeAreas", Category.CAMERA,
                "config.cyclesrenderer.camera.centerCutSafeAreas", CAMERA_CENTER_CUT_SAFE_AREAS));
        options.add(doubleOption("camera.centerTitleSafeX", Category.CAMERA,
                "config.cyclesrenderer.camera.centerTitleSafeX", CAMERA_CENTER_TITLE_SAFE_X, 0.0D, 0.5D, 0.001D));
        options.add(doubleOption("camera.centerTitleSafeY", Category.CAMERA,
                "config.cyclesrenderer.camera.centerTitleSafeY", CAMERA_CENTER_TITLE_SAFE_Y, 0.0D, 0.5D, 0.001D));
        options.add(doubleOption("camera.centerActionSafeX", Category.CAMERA,
                "config.cyclesrenderer.camera.centerActionSafeX", CAMERA_CENTER_ACTION_SAFE_X, 0.0D, 0.5D, 0.001D));
        options.add(doubleOption("camera.centerActionSafeY", Category.CAMERA,
                "config.cyclesrenderer.camera.centerActionSafeY", CAMERA_CENTER_ACTION_SAFE_Y, 0.0D, 0.5D, 0.001D));

        options.add(booleanOption("atmosphere.sunDisc", Category.ATMOSPHERE,
                "config.cyclesrenderer.atmosphere.sunDisc", ATMOSPHERE_SUN_DISC));
        options.add(doubleOption("atmosphere.sunSize", Category.ATMOSPHERE,
                "config.cyclesrenderer.atmosphere.sunSize", ATMOSPHERE_SUN_SIZE, 0.01D, 180.0D, 0.01D));
        options.add(doubleOption("atmosphere.sunIntensity", Category.ATMOSPHERE,
                "config.cyclesrenderer.atmosphere.sunIntensity", ATMOSPHERE_SUN_INTENSITY, 0.0D, 1000.0D, 0.1D));
        options.add(doubleOption("atmosphere.sunElevation", Category.ATMOSPHERE,
                "config.cyclesrenderer.atmosphere.sunElevation", ATMOSPHERE_SUN_ELEVATION, -90.0D, 90.0D, 1.0D));
        options.add(doubleOption("atmosphere.sunRotation", Category.ATMOSPHERE,
                "config.cyclesrenderer.atmosphere.sunRotation", ATMOSPHERE_SUN_ROTATION, -360.0D, 360.0D, 1.0D));
        options.add(doubleOption("atmosphere.altitude", Category.ATMOSPHERE,
                "config.cyclesrenderer.atmosphere.altitude", ATMOSPHERE_ALTITUDE, 0.0D, 60000.0D, 10.0D));
        options.add(doubleOption("atmosphere.airDensity", Category.ATMOSPHERE,
                "config.cyclesrenderer.atmosphere.airDensity", ATMOSPHERE_AIR_DENSITY, 0.0D, 10.0D, 0.01D));
        options.add(doubleOption("atmosphere.aerosolDensity", Category.ATMOSPHERE,
                "config.cyclesrenderer.atmosphere.aerosolDensity", ATMOSPHERE_AEROSOL_DENSITY, 0.0D, 10.0D, 0.01D));
        options.add(doubleOption("atmosphere.ozoneDensity", Category.ATMOSPHERE,
                "config.cyclesrenderer.atmosphere.ozoneDensity", ATMOSPHERE_OZONE_DENSITY, 0.0D, 10.0D, 0.01D));

        options.add(enumOption("materials.pbrMode", Category.MATERIALS,
                "config.cyclesrenderer.materials.pbrMode", PBR_MODE,
                CyclesRenderSettings.PbrMode.values()));
        options.add(doubleOption("materials.normalStrength", Category.MATERIALS,
                "config.cyclesrenderer.materials.normalStrength", PBR_NORMAL_STRENGTH, 0.0D, 4.0D, 0.01D));
        options.add(doubleOption("materials.emissionScale", Category.MATERIALS,
                "config.cyclesrenderer.materials.emissionScale", PBR_EMISSION_SCALE, 0.0D, 100.0D, 0.1D));
        options.add(doubleOption("materials.fallbackRoughness", Category.MATERIALS,
                "config.cyclesrenderer.materials.fallbackRoughness", PBR_FALLBACK_ROUGHNESS, 0.0D, 1.0D, 0.01D));
        options.add(doubleOption("materials.fallbackF0", Category.MATERIALS,
                "config.cyclesrenderer.materials.fallbackF0", PBR_FALLBACK_F0, 0.0D, 0.08D, 0.001D));
        LAB_PBR.appendOptions(options);

        options.add(enumOption("color.display", Category.COLOR,
                "config.cyclesrenderer.color.display", DISPLAY_DEVICE,
                CyclesRenderSettings.DisplayDevice.values()));
        options.add(enumOption("color.viewTransform", Category.COLOR,
                "config.cyclesrenderer.color.viewTransform", VIEW_TRANSFORM,
                CyclesRenderSettings.ViewTransform.values()));
        options.add(enumOption("color.look", Category.COLOR,
                "config.cyclesrenderer.color.look", COLOR_LOOK,
                CyclesRenderSettings.ColorLook.values()));
        options.add(doubleOption("color.exposure", Category.COLOR,
                "config.cyclesrenderer.color.exposure", EXPOSURE_EV, -20.0D, 20.0D, 0.1D));
        options.add(doubleOption("color.gamma", Category.COLOR,
                "config.cyclesrenderer.color.gamma", GAMMA, 0.1D, 5.0D, 0.01D));
        options.add(booleanOption("color.whiteBalance", Category.COLOR,
                "config.cyclesrenderer.color.whiteBalance", WHITE_BALANCE));
        options.add(doubleOption("color.temperature", Category.COLOR,
                "config.cyclesrenderer.color.temperature", WHITE_BALANCE_TEMPERATURE,
                1800.0D, 100000.0D, 50.0D));
        options.add(doubleOption("color.tint", Category.COLOR,
                "config.cyclesrenderer.color.tint", WHITE_BALANCE_TINT, -150.0D, 150.0D, 1.0D));
        options.add(enumOption("color.workingSpace", Category.COLOR,
                "config.cyclesrenderer.color.workingSpace", WORKING_SPACE,
                CyclesRenderSettings.WorkingSpace.values()));

        options.add(enumOption("diagnostics.activePass", Category.DIAGNOSTICS,
                "config.cyclesrenderer.diagnostics.activePass", ACTIVE_PASS,
                CyclesRenderSettings.PassView.values()));
        options.add(booleanOption("diagnostics.debugOverlay", Category.DIAGNOSTICS,
                "config.cyclesrenderer.diagnostics.debugOverlay", DEBUG_OVERLAY));
        CAMERA_AUTOMATION.appendOptions(options);
        return List.copyOf(options);
    }

    static SettingsOption<Boolean> booleanOption(
            String id,
            Category category,
            String translationKey,
            ModConfigSpec.BooleanValue value) {
        return new SettingsOption<>(id, category, translationKey, ValueKind.BOOLEAN,
                value::get, value::set, UnaryOperator.identity(), 0.0D, 1.0D, 1.0D, List.of());
    }

    static SettingsOption<Integer> intOption(
            String id,
            Category category,
            String translationKey,
            ModConfigSpec.IntValue value,
            int minimum,
            int maximum,
            int step) {
        return new SettingsOption<>(id, category, translationKey, ValueKind.INTEGER,
                value::get, value::set,
                candidate -> Math.clamp(candidate, minimum, maximum),
                minimum, maximum, step, List.of());
    }

    static SettingsOption<Double> doubleOption(
            String id,
            Category category,
            String translationKey,
            ModConfigSpec.DoubleValue value,
            double minimum,
            double maximum,
            double step) {
        return new SettingsOption<>(id, category, translationKey, ValueKind.DOUBLE,
                value::get, value::set,
                candidate -> Math.clamp(candidate, minimum, maximum),
                minimum, maximum, step, List.of());
    }

    static <E extends Enum<E>> SettingsOption<E> enumOption(
            String id,
            Category category,
            String translationKey,
            ModConfigSpec.EnumValue<E> value,
            E[] choices) {
        List<E> allowed = List.of(choices);
        return new SettingsOption<>(id, category, translationKey, ValueKind.ENUM,
                value::get, value::set,
                candidate -> allowed.contains(candidate) ? candidate : value.get(),
                0.0D, Math.max(0, choices.length - 1), 1.0D, allowed);
    }
}

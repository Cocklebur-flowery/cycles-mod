package dev.cyclesrenderer.client;

import dev.cyclesrenderer.config.CameraAutomationSettings;
import dev.cyclesrenderer.config.CyclesClientConfig;
import dev.cyclesrenderer.config.CyclesRenderSettings;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SettingsVisibilityPolicyTest {
    @Test
    void policyDependenciesExistInTheSettingsCatalog() {
        Set<String> catalogIds = new HashSet<>();
        for (var option : CyclesClientConfig.options()) {
            catalogIds.add(option.id());
        }
        Map<String, Object> values = values();
        for (String dependency : values.keySet()) {
            assertTrue(catalogIds.contains(dependency), dependency);
        }

        for (var option : CyclesClientConfig.options()) {
            assertDoesNotThrow(
                    () -> SettingsVisibilityPolicy.isEnabled(
                            option.id(),
                            id -> requiredValue(values, id)),
                    option.id());
        }
    }

    @Test
    void parentSwitchesControlTheirDependentOptions() {
        assertDisabled("color.autoExposure.minimumEv", values());
        assertEnabled("color.autoExposure.minimumEv",
                values(Map.of("color.autoExposure", true)));

        assertEnabled("camera.autofocus.mode", values());
        assertDisabled("camera.autofocus.target", values());
        assertEnabled("camera.autofocus.target", values(Map.of(
                "camera.autofocus.mode", CameraAutomationSettings.AutofocusMode.CONTINUOUS)));

        assertDisabled("sampling.minimumSamples", values());
        assertDisabled("sampling.noiseThreshold", values());
        assertEnabled("sampling.minimumSamples", values(Map.of("sampling.adaptive", true)));
        assertEnabled("sampling.noiseThreshold", values(Map.of("sampling.adaptive", true)));

        assertDisabled("output.interactivePercentage", values());
        assertEnabled("output.interactivePercentage",
                values(Map.of("output.dynamicResolution", true)));
    }

    @Test
    void cameraModesControlProjectionSpecificOptions() {
        assertEnabled("camera.projection", values());
        assertDisabled("camera.panoramaType", values());
        assertEnabled("camera.focalLength", values());
        assertEnabled("camera.sensorWidth", values());

        Map<String, Object> minecraftFov = values(Map.of(
                "camera.projection", CyclesRenderSettings.ProjectionMode.MINECRAFT_FOV));
        assertDisabled("camera.focalLength", minecraftFov);
        assertDisabled("camera.sensorWidth", minecraftFov);
        assertEnabled("camera.focalLength", values(Map.of(
                "camera.projection", CyclesRenderSettings.ProjectionMode.MINECRAFT_FOV,
                "camera.depthOfField", true)));

        Map<String, Object> equirectangular = panorama(
                CyclesRenderSettings.PanoramaType.EQUIRECTANGULAR);
        assertDisabled("camera.projection", equirectangular);
        assertEnabled("camera.panoramaType", equirectangular);
        assertDisabled("camera.sensorWidth", equirectangular);
        assertDisabled("camera.fisheyeFov", equirectangular);
        assertEnabled("camera.latitudeMin", equirectangular);
        assertEnabled("camera.longitudeMax", equirectangular);

        Map<String, Object> equisolid = panorama(
                CyclesRenderSettings.PanoramaType.FISHEYE_EQUISOLID);
        assertEnabled("camera.sensorWidth", equisolid);
        assertEnabled("camera.fisheyeFov", equisolid);
        assertEnabled("camera.fisheyeLens", equisolid);
        assertDisabled("camera.latitudeMin", equisolid);

        Map<String, Object> polynomial = panorama(
                CyclesRenderSettings.PanoramaType.FISHEYE_LENS_POLYNOMIAL);
        assertEnabled("camera.sensorWidth", polynomial);
        assertEnabled("camera.fisheyeFov", polynomial);
        assertEnabled("camera.polynomialK0", polynomial);
        assertDisabled("camera.fisheyeLens", polynomial);

        Map<String, Object> cylindrical = panorama(
                CyclesRenderSettings.PanoramaType.CENTRAL_CYLINDRICAL);
        assertEnabled("camera.cylindricalRadius", cylindrical);
        assertDisabled("camera.fisheyeFov", cylindrical);

        assertEnabled("camera.fisheyeLens", values(Map.of(
                "camera.panoramaType", CyclesRenderSettings.PanoramaType.FISHEYE_EQUISOLID)));
    }

    @Test
    void depthOfFieldAndSafeAreaDependenciesKeepExistingRules() {
        assertDisabled("camera.depthOfFieldMode", values());
        assertDisabled("camera.focusDistance", values());
        assertDisabled("camera.fStop", values());
        assertDisabled("camera.apertureBlades", values());

        Map<String, Object> circularAperture = values(Map.of("camera.depthOfField", true));
        assertEnabled("camera.depthOfFieldMode", circularAperture);
        assertEnabled("camera.focusDistance", circularAperture);
        assertDisabled("camera.apertureBlades", circularAperture);
        assertEnabled("camera.apertureRotation", circularAperture);

        assertEnabled("camera.apertureBlades", values(Map.of(
                "camera.depthOfField", true,
                "camera.apertureCircular", false)));

        assertDisabled("camera.titleSafeX", values());
        assertDisabled("camera.actionSafeY", values());
        assertDisabled("camera.centerCutSafeAreas", values());
        assertDisabled("camera.centerTitleSafeX", values());

        Map<String, Object> safeAreas = values(Map.of("camera.safeAreas", true));
        assertEnabled("camera.titleSafeX", safeAreas);
        assertEnabled("camera.actionSafeY", safeAreas);
        assertEnabled("camera.centerCutSafeAreas", safeAreas);
        assertDisabled("camera.centerTitleSafeX", safeAreas);

        assertEnabled("camera.centerTitleSafeX", values(Map.of(
                "camera.safeAreas", true,
                "camera.centerCutSafeAreas", true)));
    }

    @Test
    void denoiserMaterialAndWhiteBalanceDependenciesKeepExistingRules() {
        assertEnabled("denoise.mode", values());
        assertDisabled("denoise.startSample", values());
        assertDisabled("denoise.dlssMode", values());
        assertEnabled("denoise.startSample", values(Map.of(
                "denoise.mode", CyclesRenderSettings.DenoiserMode.OPTIX)));
        assertDisabled("denoise.dlssMode", values(Map.of(
                "denoise.mode", CyclesRenderSettings.DenoiserMode.OPTIX)));
        assertEnabled("denoise.dlssMode", values(Map.of(
                "denoise.mode", CyclesRenderSettings.DenoiserMode.DLSS_EXPERIMENTAL)));

        assertEnabled("materials.pbrMode", values());
        assertDisabled("materials.normalStrength", values());
        assertEnabled("materials.normalStrength", values(Map.of(
                "materials.pbrMode", CyclesRenderSettings.PbrMode.AUTO)));

        assertDisabled("color.temperature", values());
        assertDisabled("color.tint", values());
        assertEnabled("color.temperature", values(Map.of("color.whiteBalance", true)));
        assertEnabled("color.tint", values(Map.of("color.whiteBalance", true)));
        assertEnabled("lightPaths.maximumBounce", values());
    }

    private static Map<String, Object> panorama(CyclesRenderSettings.PanoramaType type) {
        return values(Map.of(
                "camera.type", CyclesRenderSettings.CameraType.PANORAMA,
                "camera.panoramaType", type));
    }

    private static Map<String, Object> values() {
        return values(Map.of());
    }

    private static Map<String, Object> values(Map<String, Object> overrides) {
        Map<String, Object> values = new HashMap<>();
        values.put("color.autoExposure", false);
        values.put("camera.autofocus.mode", CameraAutomationSettings.AutofocusMode.OFF);
        values.put("sampling.adaptive", false);
        values.put("output.dynamicResolution", false);
        values.put("camera.type", CyclesRenderSettings.CameraType.PERSPECTIVE);
        values.put("camera.panoramaType", CyclesRenderSettings.PanoramaType.EQUIRECTANGULAR);
        values.put("camera.projection", CyclesRenderSettings.ProjectionMode.PHYSICAL_LENS);
        values.put("camera.depthOfField", false);
        values.put("camera.apertureCircular", true);
        values.put("camera.safeAreas", false);
        values.put("camera.centerCutSafeAreas", false);
        values.put("denoise.mode", CyclesRenderSettings.DenoiserMode.OFF);
        values.put("materials.pbrMode", CyclesRenderSettings.PbrMode.OFF);
        values.put("color.whiteBalance", false);
        values.putAll(overrides);
        return values;
    }

    private static Object requiredValue(Map<String, Object> values, String id) {
        if (!values.containsKey(id)) {
            throw new IllegalArgumentException("Unknown settings dependency " + id);
        }
        return values.get(id);
    }

    private static void assertEnabled(String id, Map<String, Object> values) {
        assertTrue(SettingsVisibilityPolicy.isEnabled(id, values::get), id);
    }

    private static void assertDisabled(String id, Map<String, Object> values) {
        assertFalse(SettingsVisibilityPolicy.isEnabled(id, values::get), id);
    }
}

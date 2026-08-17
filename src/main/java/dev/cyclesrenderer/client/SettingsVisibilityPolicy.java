package dev.cyclesrenderer.client;

import dev.cyclesrenderer.config.CameraAutomationSettings;
import dev.cyclesrenderer.config.CyclesRenderSettings;

import java.util.Set;
import java.util.function.Function;

/** Pure dependency rules for enabling settings controls in the editor. */
final class SettingsVisibilityPolicy {
    private static final Set<String> DEPTH_OF_FIELD_OPTIONS = Set.of(
            "camera.depthOfFieldMode",
            "camera.focusDistance",
            "camera.fStop",
            "camera.apertureCircular",
            "camera.apertureBlades",
            "camera.apertureRotation",
            "camera.apertureRatio");

    private SettingsVisibilityPolicy() {
    }

    static boolean isEnabled(String id, Function<String, Object> values) {
        if (id.startsWith("color.autoExposure.")
                && !booleanValue(values, "color.autoExposure")) {
            return false;
        }
        if (id.startsWith("camera.autofocus.") && !id.equals("camera.autofocus.mode")
                && enumValue(values, "camera.autofocus.mode")
                    == CameraAutomationSettings.AutofocusMode.OFF) {
            return false;
        }
        if ((id.equals("sampling.minimumSamples") || id.equals("sampling.noiseThreshold"))
                && !booleanValue(values, "sampling.adaptive")) {
            return false;
        }
        if (id.equals("output.interactivePercentage")
                && !booleanValue(values, "output.dynamicResolution")) {
            return false;
        }
        CyclesRenderSettings.CameraType cameraType = enumValue(values, "camera.type");
        CyclesRenderSettings.PanoramaType panoramaType =
                enumValue(values, "camera.panoramaType");
        if (id.equals("camera.projection")
                && cameraType != CyclesRenderSettings.CameraType.PERSPECTIVE) {
            return false;
        }
        if (id.equals("camera.panoramaType")
                && cameraType != CyclesRenderSettings.CameraType.PANORAMA) {
            return false;
        }
        if (id.equals("camera.focalLength")
                && !booleanValue(values, "camera.depthOfField")
                && (cameraType != CyclesRenderSettings.CameraType.PERSPECTIVE
                    || enumValue(values, "camera.projection")
                        != CyclesRenderSettings.ProjectionMode.PHYSICAL_LENS)) {
            return false;
        }
        if (id.equals("camera.sensorWidth")
                && !usesSensorWidth(
                    cameraType,
                    panoramaType,
                    enumValue(values, "camera.projection"))) {
            return false;
        }
        if (id.equals("camera.fisheyeFov") && !usesFisheye(panoramaType, cameraType)) {
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
                && DEPTH_OF_FIELD_OPTIONS.contains(id)
                && !booleanValue(values, "camera.depthOfField")) {
            return false;
        }
        if (id.equals("camera.apertureBlades")
                && booleanValue(values, "camera.apertureCircular")) {
            return false;
        }
        if ((id.startsWith("camera.titleSafe") || id.startsWith("camera.actionSafe")
                || id.equals("camera.centerCutSafeAreas"))
                && !booleanValue(values, "camera.safeAreas")) {
            return false;
        }
        if ((id.startsWith("camera.centerTitleSafe")
                || id.startsWith("camera.centerActionSafe"))
                && (!booleanValue(values, "camera.safeAreas")
                    || !booleanValue(values, "camera.centerCutSafeAreas"))) {
            return false;
        }
        if (id.startsWith("denoise.") && !id.equals("denoise.mode")
                && enumValue(values, "denoise.mode") == CyclesRenderSettings.DenoiserMode.OFF) {
            return false;
        }
        if (id.equals("denoise.dlssMode")
                && enumValue(values, "denoise.mode")
                    != CyclesRenderSettings.DenoiserMode.DLSS_EXPERIMENTAL) {
            return false;
        }
        if (id.startsWith("materials.") && !id.equals("materials.pbrMode")
                && enumValue(values, "materials.pbrMode") == CyclesRenderSettings.PbrMode.OFF) {
            return false;
        }
        return !(id.equals("color.temperature") || id.equals("color.tint"))
                || booleanValue(values, "color.whiteBalance");
    }

    private static boolean usesSensorWidth(
            CyclesRenderSettings.CameraType cameraType,
            CyclesRenderSettings.PanoramaType panoramaType,
            CyclesRenderSettings.ProjectionMode projectionMode) {
        if (cameraType == CyclesRenderSettings.CameraType.PERSPECTIVE) {
            return projectionMode == CyclesRenderSettings.ProjectionMode.PHYSICAL_LENS;
        }
        return panoramaType == CyclesRenderSettings.PanoramaType.FISHEYE_EQUISOLID
                || panoramaType == CyclesRenderSettings.PanoramaType.FISHEYE_LENS_POLYNOMIAL;
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

    private static boolean booleanValue(Function<String, Object> values, String id) {
        return Boolean.TRUE.equals(values.apply(id));
    }

    @SuppressWarnings("unchecked")
    private static <E extends Enum<E>> E enumValue(
            Function<String, Object> values,
            String id) {
        return (E) values.apply(id);
    }
}

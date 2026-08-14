package dev.cyclesrenderer.camera;

import java.util.List;

/** Pure screen-space sampling and focus-distance geometry used by the Minecraft raycaster. */
final class AutofocusSampling {
    private AutofocusSampling() {
    }

    static List<ScreenSample> samplePattern(boolean area, float radius) {
        ScreenSample center = new ScreenSample(0.5F, 0.5F, 1.0F, true);
        if (!area || radius <= 0.0F) {
            return List.of(center);
        }
        float offset = Math.clamp(radius, 0.0F, 0.5F);
        float diagonal = offset * 0.70710677F;
        return List.of(
                center,
                new ScreenSample(0.5F - offset, 0.5F, 0.75F, false),
                new ScreenSample(0.5F + offset, 0.5F, 0.75F, false),
                new ScreenSample(0.5F, 0.5F - offset, 0.75F, false),
                new ScreenSample(0.5F, 0.5F + offset, 0.75F, false),
                new ScreenSample(0.5F - diagonal, 0.5F - diagonal, 0.5F, false),
                new ScreenSample(0.5F + diagonal, 0.5F - diagonal, 0.5F, false),
                new ScreenSample(0.5F - diagonal, 0.5F + diagonal, 0.5F, false),
                new ScreenSample(0.5F + diagonal, 0.5F + diagonal, 0.5F, false));
    }

    static float focusDistance(boolean radial, double localDirectionZ, float rayDistance) {
        if (!Float.isFinite(rayDistance) || rayDistance <= 0.0F) {
            return Float.NaN;
        }
        return radial
                ? rayDistance
                : (float) (rayDistance * Math.max(0.0D, -localDirectionZ));
    }

    record ScreenSample(float u, float v, float weight, boolean primary) {
    }
}

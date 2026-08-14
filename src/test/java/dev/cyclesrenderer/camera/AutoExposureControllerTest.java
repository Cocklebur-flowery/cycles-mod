package dev.cyclesrenderer.camera;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AutoExposureControllerTest {
    @Test
    void firstMeasurementInitializesImmediately() {
        AutoExposureController controller = new AutoExposureController();
        float exposure = controller.update(measurement(-4.0F, -4.0F), settings(), 0.016F, false);
        assertTrue(exposure > 1.0F && exposure < 2.0F);
    }

    @Test
    void highlightProtectionLimitsExposure() {
        AutoExposureController controller = new AutoExposureController();
        float exposure = controller.update(measurement(-8.0F, 8.0F), settings(), 0.016F, false);
        assertTrue(exposure < -7.0F);
    }

    @Test
    void lockedExposureDoesNotAdapt() {
        AutoExposureController controller = new AutoExposureController();
        float initial = controller.update(measurement(-4.0F, 0.0F), settings(), 0.016F, false);
        float locked = controller.update(measurement(4.0F, 4.0F), settings(), 1.0F, true);
        assertEquals(initial, locked);
    }

    private static ExposureHistogram.Result measurement(float mean, float highlight) {
        return new ExposureHistogram.Result(true, mean, mean, mean, highlight, 100.0D);
    }

    private static AutoExposureController.Settings settings() {
        return new AutoExposureController.Settings(
                0.0F, -12.0F, 12.0F, 0.35F, 0.8F,
                0.05F, 8.0F, 1.0F, 0.5F);
    }
}

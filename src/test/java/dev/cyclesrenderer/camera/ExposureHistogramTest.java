package dev.cyclesrenderer.camera;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExposureHistogramTest {
    @Test
    void rejectsEmptyHistogram() {
        ExposureHistogram histogram = new ExposureHistogram();
        assertFalse(histogram.measure(settings()).available());
    }

    @Test
    void trimsDarkAndBrightOutliers() {
        ExposureHistogram histogram = new ExposureHistogram();
        for (int index = 0; index < 98; index++) {
            histogram.add(-2.0F, 1.0D);
        }
        histogram.add(-16.0F, 1.0D);
        histogram.add(16.0F, 1.0D);

        ExposureHistogram.Result result = histogram.measure(settings());

        assertTrue(result.available());
        assertEquals(-2.0F, result.meanLogLuminance(), 0.08F);
    }

    @Test
    void centerWeightFavorsTheFrameCenter() {
        double center = ExposureHistogram.centerWeight(17, 9, 36, 20, 2.0F);
        double corner = ExposureHistogram.centerWeight(0, 0, 36, 20, 2.0F);
        assertTrue(center > corner * 1.5D);
    }

    private static ExposureHistogram.Settings settings() {
        return new ExposureHistogram.Settings(0.02F, 0.98F, 0.995F);
    }
}

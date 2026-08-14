package dev.cyclesrenderer.camera;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AutofocusRaycasterTest {
    @Test
    void centerTargetProducesOnePrimaryRay() {
        List<AutofocusSampling.ScreenSample> samples = AutofocusSampling.samplePattern(
                false, 0.1F);

        assertEquals(1, samples.size());
        assertTrue(samples.getFirst().primary());
        assertEquals(0.5F, samples.getFirst().u());
        assertEquals(0.5F, samples.getFirst().v());
    }

    @Test
    void areaTargetProducesBoundedNineRayPattern() {
        List<AutofocusSampling.ScreenSample> samples = AutofocusSampling.samplePattern(
                true, 0.1F);

        assertEquals(9, samples.size());
        assertEquals(1, samples.stream().filter(AutofocusSampling.ScreenSample::primary).count());
        assertTrue(samples.stream().allMatch(sample -> sample.u() >= 0.0F
                && sample.u() <= 1.0F && sample.v() >= 0.0F && sample.v() <= 1.0F));
    }

    @Test
    void perspectiveUsesAxialWhilePanoramaUsesRadialDistance() {
        assertEquals(8.0F, AutofocusSampling.focusDistance(
                false, -0.8D, 10.0F), 0.0001F);
        assertEquals(10.0F, AutofocusSampling.focusDistance(
                true, -0.8D, 10.0F), 0.0001F);
    }
}

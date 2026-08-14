package dev.cyclesrenderer.camera;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AutofocusControllerTest {
    @Test
    void primaryClusterWinsAgainstDistantBackground() {
        List<AutofocusController.FocusSample> samples = List.of(
                new AutofocusController.FocusSample(4.0F, 1.0F, true),
                new AutofocusController.FocusSample(4.2F, 1.0F, false),
                new AutofocusController.FocusSample(30.0F, 1.0F, false),
                new AutofocusController.FocusSample(31.0F, 1.0F, false));

        float distance = AutofocusController.selectDistance(samples, settings(), 10.0F);
        assertTrue(distance >= 4.0F && distance <= 4.2F);
    }

    @Test
    void noHitsUsesFallbackDistance() {
        float distance = AutofocusController.selectDistance(List.of(), settings(), 24.0F);
        assertEquals(24.0F, distance);
    }

    @Test
    void lockedFocusRemainsStable() {
        AutofocusController controller = new AutofocusController();
        float initial = controller.update(List.of(
                new AutofocusController.FocusSample(5.0F, 1.0F, true)),
                settings(), 10.0F, 0.016F, false);
        float locked = controller.update(List.of(
                new AutofocusController.FocusSample(50.0F, 1.0F, true)),
                settings(), 10.0F, 1.0F, true);
        assertEquals(initial, locked);
    }

    private static AutofocusController.Settings settings() {
        return new AutofocusController.Settings(
                0.05F, 256.0F, 0.75F, 0.25F,
                0.03F, 0.01F, 6.0F);
    }
}

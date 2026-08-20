package dev.cyclesrenderer.scene;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LabPbrAnimationUploadGateTest {
    @Test
    void waitsForFrameNewerThanResetGeneration() {
        LabPbrAnimationUploadGate gate = new LabPbrAnimationUploadGate();

        gate.reset(7L);

        assertFalse(gate.observe(7L));
        assertFalse(gate.observe(6L));
        assertTrue(gate.observe(8L));
        assertTrue(gate.observe(8L));
    }

    @Test
    void closesAgainAcrossSceneReset() {
        LabPbrAnimationUploadGate gate = new LabPbrAnimationUploadGate();

        gate.reset(0L);
        assertTrue(gate.observe(4L));

        gate.reset(4L);
        assertFalse(gate.observe(4L));
        assertTrue(gate.observe(5L));
    }
}

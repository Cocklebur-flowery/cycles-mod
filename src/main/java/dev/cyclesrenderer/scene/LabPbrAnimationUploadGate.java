package dev.cyclesrenderer.scene;

/** Opens animated atlas uploads only after a newer native frame crosses a scene reset. */
final class LabPbrAnimationUploadGate {
    private long resetFrameGeneration;
    private boolean ready;

    void reset(long presentedFrameGeneration) {
        resetFrameGeneration = presentedFrameGeneration;
        ready = false;
    }

    boolean observe(long presentedFrameGeneration) {
        if (!ready
                && Long.compareUnsigned(presentedFrameGeneration, resetFrameGeneration) > 0) {
            ready = true;
        }
        return ready;
    }
}

package dev.cyclesrenderer.perf;

final class DevicePhaseStallDetector {
    private static final int STALL_MICROS = 100_000;
    private static final int TRIGGER_CODE = 8;

    private long triggeredActiveSceneRevision = Long.MIN_VALUE;
    private int triggeredActivePhase = -1;
    private long inspectedCompletedRevision = -1L;

    void reset() {
        triggeredActiveSceneRevision = Long.MIN_VALUE;
        triggeredActivePhase = -1;
        inspectedCompletedRevision = -1L;
    }

    int classify(PerformanceSample.Context context) {
        if (context == null || !context.nativeAvailable()) {
            return 0;
        }
        int activePhase = context.activeDevicePhase();
        if (activePhase >= 0
                && activePhase < context.lastDevicePhaseMicros().length
                && context.activeDevicePhaseMicros() >= STALL_MICROS
                && (context.sceneRevision() != triggeredActiveSceneRevision
                        || activePhase != triggeredActivePhase)) {
            triggeredActiveSceneRevision = context.sceneRevision();
            triggeredActivePhase = activePhase;
            return TRIGGER_CODE;
        }
        if (context.sceneTimingRevision() == 0L
                || context.sceneTimingRevision() == inspectedCompletedRevision) {
            return 0;
        }
        inspectedCompletedRevision = context.sceneTimingRevision();
        for (int micros : context.lastDevicePhaseMicros()) {
            if (micros >= STALL_MICROS) {
                return TRIGGER_CODE;
            }
        }
        return 0;
    }
}

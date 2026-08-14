package dev.cyclesrenderer.camera;

/** Converts HDR histogram measurements into a stable display-only exposure offset. */
public final class AutoExposureController {
    private static final float LOG2_MIDDLE_GREY = -2.4739312F;

    private boolean initialized;
    private float currentEv;
    private float targetEv;
    private long measurementCount;

    public float update(
            ExposureHistogram.Result measurement,
            Settings settings,
            float deltaSeconds,
            boolean locked) {
        if (!measurement.available()) {
            return currentEv;
        }
        float keyTarget = LOG2_MIDDLE_GREY - measurement.meanLogLuminance();
        float highlightLimit = log2(settings.highlightOutput())
                - measurement.highlightLogLuminance();
        targetEv = Math.clamp(
                Math.min(keyTarget, highlightLimit + settings.highlightHeadroomEv())
                        + settings.compensationEv(),
                settings.minimumEv(),
                settings.maximumEv());
        measurementCount++;

        if (!initialized) {
            initialized = true;
            currentEv = targetEv;
            return currentEv;
        }
        if (locked || !Float.isFinite(deltaSeconds) || deltaSeconds <= 0.0F) {
            return currentEv;
        }
        float difference = targetEv - currentEv;
        if (Math.abs(difference) <= settings.deadbandEv()) {
            return currentEv;
        }
        float seconds = difference > 0.0F
                ? settings.brightenSeconds()
                : settings.darkenSeconds();
        float alpha = seconds <= 0.0F
                ? 1.0F
                : 1.0F - (float) Math.exp(-deltaSeconds / seconds);
        float proposed = currentEv + difference * alpha;
        float maximumStep = settings.maximumEvPerSecond() * deltaSeconds;
        currentEv += Math.clamp(proposed - currentEv, -maximumStep, maximumStep);
        return currentEv;
    }

    public void reset() {
        initialized = false;
        currentEv = 0.0F;
        targetEv = 0.0F;
        measurementCount = 0L;
    }

    public State state() {
        return new State(initialized, currentEv, targetEv, measurementCount);
    }

    private static float log2(float value) {
        return (float) (Math.log(value) / Math.log(2.0D));
    }

    public record Settings(
            float compensationEv,
            float minimumEv,
            float maximumEv,
            float brightenSeconds,
            float darkenSeconds,
            float deadbandEv,
            float maximumEvPerSecond,
            float highlightOutput,
            float highlightHeadroomEv) {
        public Settings {
            if (!Float.isFinite(compensationEv)
                    || !Float.isFinite(minimumEv)
                    || !Float.isFinite(maximumEv)
                    || minimumEv > maximumEv
                    || brightenSeconds < 0.0F
                    || darkenSeconds < 0.0F
                    || deadbandEv < 0.0F
                    || maximumEvPerSecond <= 0.0F
                    || highlightOutput <= 0.0F
                    || highlightHeadroomEv < 0.0F) {
                throw new IllegalArgumentException("invalid automatic exposure settings");
            }
        }
    }

    public record State(
            boolean initialized,
            float currentEv,
            float targetEv,
            long measurementCount) {
    }
}

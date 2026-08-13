package dev.cyclesrenderer.perf;

/** Receives allocation-free timing spans from the display presenter. */
public interface DisplayPerformanceProbe {
    DisplayPerformanceProbe NONE = new DisplayPerformanceProbe() {
        @Override
        public long beginDisplayStage() {
            return 0L;
        }

        @Override
        public void endDisplayStage(Stage stage, long startedNanos) {
        }
    };

    enum Stage {
        COLOR_LUT,
        UNIFORMS,
        RENDER_PASS
    }

    long beginDisplayStage();

    void endDisplayStage(Stage stage, long startedNanos);
}

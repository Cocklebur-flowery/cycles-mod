package dev.cyclesrenderer.camera;

import dev.cyclesrenderer.config.CameraAutomationSettings;
import dev.cyclesrenderer.config.CyclesRenderSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;

import java.util.List;

/** Owns autofocus mode transitions, raycast cadence, fallback policy and temporal response. */
public final class AutofocusStage {
    private static final long SAMPLE_INTERVAL_NANOS = 50_000_000L;

    private final AutofocusRaycaster raycaster = new AutofocusRaycaster();
    private final AutofocusController controller = new AutofocusController();
    private CameraAutomationSettings.AutofocusMode previousMode =
            CameraAutomationSettings.AutofocusMode.OFF;
    private boolean singleShotComplete;
    private long lastSampleNanos;
    private int lastRayCount;

    public float update(
            Minecraft minecraft,
            ClientLevel level,
            CameraRenderState camera,
            CyclesRenderSettings settings,
            float verticalFovRadians,
            float aspect,
            long nowNanos) {
        CameraAutomationSettings.Autofocus autofocus =
                settings.cameraAutomation().autofocus();
        if (!settings.depthOfField()
                || autofocus.mode() == CameraAutomationSettings.AutofocusMode.OFF) {
            if (previousMode != CameraAutomationSettings.AutofocusMode.OFF
                    || controller.state().initialized()) {
                reset();
            }
            return settings.focusDistance();
        }
        if (autofocus.mode() != previousMode) {
            controller.reset();
            singleShotComplete = false;
            lastSampleNanos = 0L;
            lastRayCount = 0;
            previousMode = autofocus.mode();
        }

        AutofocusController.State state = controller.state();
        if ((autofocus.locked() && state.initialized())
                || (autofocus.mode() == CameraAutomationSettings.AutofocusMode.SINGLE_SHOT
                    && singleShotComplete)
                || (lastSampleNanos != 0L
                    && nowNanos - lastSampleNanos < SAMPLE_INTERVAL_NANOS)) {
            return state.initialized() ? state.currentDistance() : settings.focusDistance();
        }

        List<AutofocusController.FocusSample> samples = raycaster.sample(
                minecraft, level, camera, settings, verticalFovRadians, aspect);
        state = controller.state();
        float fallback = fallbackDistance(settings, autofocus, state);
        float minimum = Math.min(
                autofocus.maximumDistance(), Math.max(0.01F, settings.cameraClipNear()));
        float deltaSeconds = lastSampleNanos == 0L
                ? 0.0F
                : Math.clamp((nowNanos - lastSampleNanos) * 1.0E-9F, 0.0F, 1.0F);
        float focusDistance = controller.update(
                samples,
                new AutofocusController.Settings(
                        minimum,
                        autofocus.maximumDistance(),
                        autofocus.clusterGapStops(),
                        autofocus.responseSeconds(),
                        autofocus.deadbandDistance(),
                        autofocus.deadbandRatio(),
                        autofocus.maximumStopsPerSecond()),
                fallback,
                deltaSeconds,
                autofocus.locked());
        lastSampleNanos = nowNanos;
        lastRayCount = samples.size();
        singleShotComplete = autofocus.mode()
                == CameraAutomationSettings.AutofocusMode.SINGLE_SHOT;
        return focusDistance;
    }

    public void reset() {
        controller.reset();
        previousMode = CameraAutomationSettings.AutofocusMode.OFF;
        singleShotComplete = false;
        lastSampleNanos = 0L;
        lastRayCount = 0;
    }

    public State state() {
        AutofocusController.State state = controller.state();
        return new State(
                previousMode,
                state.initialized(),
                singleShotComplete,
                state.currentDistance(),
                state.targetDistance(),
                state.acceptedMeasurements(),
                lastRayCount);
    }

    private static float fallbackDistance(
            CyclesRenderSettings settings,
            CameraAutomationSettings.Autofocus autofocus,
            AutofocusController.State state) {
        return switch (autofocus.missBehavior()) {
            case HOLD_LAST -> state.initialized()
                    ? state.currentDistance()
                    : settings.focusDistance();
            case MANUAL_DISTANCE -> settings.focusDistance();
            case FAR_LIMIT -> autofocus.maximumDistance();
        };
    }

    public record State(
            CameraAutomationSettings.AutofocusMode mode,
            boolean initialized,
            boolean singleShotComplete,
            float currentDistance,
            float targetDistance,
            long acceptedMeasurements,
            int lastRayCount) {
    }
}

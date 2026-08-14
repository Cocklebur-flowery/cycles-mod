package dev.cyclesrenderer.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.cyclesrenderer.camera.AutoExposureController;
import dev.cyclesrenderer.camera.ExposureHistogram;
import dev.cyclesrenderer.config.CameraAutomationSettings;
import dev.cyclesrenderer.config.CyclesRenderSettings;

/** Coordinates asynchronous HDR measurements with the temporal exposure controller. */
public final class AutomaticExposureStage {
    private final GpuExposureMeter meter = new GpuExposureMeter();
    private final AutoExposureController controller = new AutoExposureController();
    private boolean enabled;
    private boolean combined;
    private long lastMeasurementNanos;

    float update(GpuTextureView source, CyclesRenderSettings settings, long nowNanos) {
        RenderSystem.assertOnRenderThread();
        CameraAutomationSettings.AutoExposure automatic =
                settings.cameraAutomation().autoExposure();
        if (!automatic.enabled()) {
            if (enabled) {
                reset();
            }
            return settings.exposureEv();
        }
        enabled = true;
        if (settings.activePass() != CyclesRenderSettings.PassView.COMBINED) {
            if (combined) {
                combined = false;
                lastMeasurementNanos = 0L;
                meter.reset();
            }
            return settings.exposureEv();
        }
        combined = true;

        ExposureHistogram.Result measurement = meter.pollLatest(automatic);
        if (measurement != null) {
            float deltaSeconds = lastMeasurementNanos == 0L
                    ? 0.0F
                    : Math.clamp((nowNanos - lastMeasurementNanos) * 1.0E-9F, 0.0F, 1.0F);
            controller.update(
                    measurement,
                    controllerSettings(settings.exposureEv(), automatic),
                    deltaSeconds,
                    automatic.locked());
            lastMeasurementNanos = nowNanos;
        }
        meter.capture(source, settings.workingSpace(), nowNanos);
        AutoExposureController.State state = controller.state();
        return state.initialized() ? state.currentEv() : settings.exposureEv();
    }

    void reset() {
        RenderSystem.assertOnRenderThread();
        enabled = false;
        combined = false;
        lastMeasurementNanos = 0L;
        controller.reset();
        meter.reset();
    }

    public Telemetry telemetry() {
        AutoExposureController.State state = controller.state();
        GpuExposureMeter.Telemetry meterState = meter.telemetry();
        return new Telemetry(
                enabled,
                state.initialized(),
                state.currentEv(),
                state.targetEv(),
                state.measurementCount(),
                meterState.captureCount(),
                meterState.droppedCaptureCount(),
                meterState.pendingCaptureCount());
    }

    private static AutoExposureController.Settings controllerSettings(
            float compensationEv,
            CameraAutomationSettings.AutoExposure settings) {
        float headroom = settings.metering()
                == CameraAutomationSettings.ExposureMetering.HIGHLIGHT_PRIORITY
                ? 0.0F
                : settings.highlightHeadroomEv();
        return new AutoExposureController.Settings(
                compensationEv,
                settings.minimumEv(),
                settings.maximumEv(),
                settings.brightenSeconds(),
                settings.darkenSeconds(),
                settings.deadbandEv(),
                settings.maximumEvPerSecond(),
                settings.highlightOutput(),
                headroom);
    }

    public record Telemetry(
            boolean enabled,
            boolean initialized,
            float currentEv,
            float targetEv,
            long measurementCount,
            long captureCount,
            long droppedCaptureCount,
            long pendingCaptureCount) {
    }
}

package dev.cyclesrenderer.perf;

import dev.cyclesrenderer.nativebridge.NativeBridge;
import dev.cyclesrenderer.render.CyclesFramePresenter;
import dev.cyclesrenderer.render.VulkanExternalBufferPrototype;
import dev.cyclesrenderer.scene.SectionGeometryCollector;
import dev.cyclesrenderer.scene.SectionSceneManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Samples slower cross-component counters outside the per-stage timer logic. */
final class PerformanceContextSampler {
    private static final Logger LOGGER = LoggerFactory.getLogger(PerformanceContextSampler.class);

    private final CyclesFramePresenter presenter;
    private final VulkanExternalBufferPrototype interop;
    private final SectionSceneManager sceneManager;

    PerformanceContextSampler(
            CyclesFramePresenter presenter,
            VulkanExternalBufferPrototype interop,
            SectionSceneManager sceneManager) {
        this.presenter = presenter;
        this.interop = interop;
        this.sceneManager = sceneManager;
    }

    PerformanceSample.Context sample() {
        SectionGeometryCollector.Telemetry capture = SectionGeometryCollector.telemetry();
        SectionSceneManager.Telemetry scene = sceneManager.telemetry();
        CyclesFramePresenter.Telemetry presentation = presenter.telemetry();
        VulkanExternalBufferPrototype.CopyTelemetry copy = interop.copyTelemetry();
        NativeBridge.Diagnostics diagnostics = null;
        if (NativeBridge.isReady()) {
            try {
                diagnostics = NativeBridge.diagnostics();
            } catch (RuntimeException error) {
                LOGGER.debug("Performance context could not sample native diagnostics", error);
            }
        }
        boolean available = diagnostics != null;
        return new PerformanceSample.Context(
                capture.captureCount(), capture.replacedCount(), capture.pendingSnapshots(),
                capture.lastCaptureMicros(),
                scene.updateCount(), scene.lastUpdateMicros(), scene.lastUpsertMicros(),
                scene.lastRemoveMicros(), scene.lastCommitMicros(), scene.lastAcceptedSections(),
                scene.pendingCommit(),
                presentation.uploadCount(), presentation.generationGaps(),
                presentation.lastUploadMicros(),
                copy.copyCount(), copy.pending(), copy.pendingGeneration(),
                copy.displayedGeneration(), copy.lastCopyMicros(),
                available,
                available ? diagnostics.settingsRevision() : 0L,
                available ? diagnostics.sceneRevision() : 0L,
                available ? diagnostics.cameraRevision() : 0L,
                available ? diagnostics.frameGeneration() : 0L,
                available ? diagnostics.stateCode() : 0,
                available ? diagnostics.sampleCount() : 0,
                available ? diagnostics.targetSampleCount() : 0,
                available ? diagnostics.sectionCount() : 0,
                available ? diagnostics.resetLevel() : 0,
                available ? diagnostics.producedFrameCount() : 0L,
                available ? diagnostics.copiedFrameCount() : 0L,
                available ? diagnostics.droppedDisplayUpdates() : 0,
                available ? diagnostics.sceneCommitCount() : 0L,
                available ? diagnostics.sceneDeltaCount() : 0L,
                available ? diagnostics.renderStartCount() : 0L,
                available ? diagnostics.sceneTimingRevision() : 0L,
                available ? diagnostics.sceneTimingCount() : 0L,
                available ? diagnostics.lastSceneCommitMicros() : 0,
                available ? diagnostics.lastSceneDeltaMicros() : 0,
                available ? diagnostics.lastRenderStartMicros() : 0,
                available ? diagnostics.lastRenderConfigureMicros() : 0,
                available ? diagnostics.emaRenderConfigureMicros() : 0,
                available ? diagnostics.maxRenderConfigureMicros() : 0,
                available ? diagnostics.lastRenderResetMicros() : 0,
                available ? diagnostics.emaRenderResetMicros() : 0,
                available ? diagnostics.maxRenderResetMicros() : 0,
                available ? diagnostics.lastRenderPrepareMicros() : 0,
                available ? diagnostics.emaRenderPrepareMicros() : 0,
                available ? diagnostics.maxRenderPrepareMicros() : 0,
                available ? diagnostics.lastSessionStartMicros() : 0,
                available ? diagnostics.emaSessionStartMicros() : 0,
                available ? diagnostics.maxSessionStartMicros() : 0,
                available ? diagnostics.lastSceneQueueMicros() : 0,
                available ? diagnostics.lastResetWaitMicros() : 0,
                available ? diagnostics.lastDeviceUpdateMicros() : 0,
                available ? diagnostics.lastGeometryUpdateMicros() : 0,
                available ? diagnostics.lastBvhUpdateMicros() : 0,
                available ? diagnostics.lastSceneFirstFrameMicros() : 0,
                available ? diagnostics.activeDevicePhase()
                        : NativeBridge.DEVICE_UPDATE_PHASE_COUNT,
                available ? diagnostics.activeDevicePhaseMicros() : 0,
                available ? diagnostics.lastDevicePhaseMicros()
                        : new int[NativeBridge.DEVICE_UPDATE_PHASE_COUNT],
                available ? diagnostics.emaDevicePhaseMicros()
                        : new int[NativeBridge.DEVICE_UPDATE_PHASE_COUNT],
                available ? diagnostics.maxDevicePhaseMicros()
                        : new int[NativeBridge.DEVICE_UPDATE_PHASE_COUNT]);
    }
}

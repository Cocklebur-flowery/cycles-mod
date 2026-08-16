package dev.cyclesrenderer.nativebridge;

import dev.cyclesrenderer.config.CyclesRenderSettings;

import java.lang.foreign.MemorySegment;

import static dev.cyclesrenderer.nativebridge.NativeLayouts.SETTINGS_LAYOUT;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** Encodes the immutable Java settings snapshot into the stable native ABI layout. */
final class NativeSettingsMarshaller {
    private NativeSettingsMarshaller() {
    }

    static void write(
            MemorySegment target,
            int structVersion,
            CyclesRenderSettings settings) {
        target.fill((byte) 0);
        target.set(JAVA_INT, 0L, Math.toIntExact(SETTINGS_LAYOUT.byteSize()));
        target.set(JAVA_INT, 4L, structVersion);
        target.set(JAVA_LONG, 8L, settings.revision());
        target.set(JAVA_INT, 16L, settings.devicePolicy().nativeId());
        target.set(JAVA_INT, 20L, settings.resolutionMode().nativeId());
        target.set(JAVA_INT, 24L, settings.renderWidth());
        target.set(JAVA_INT, 28L, settings.renderHeight());
        target.set(JAVA_INT, 32L, settings.resolutionPercentage());
        target.set(JAVA_INT, 36L, settings.interactiveSamples());
        target.set(JAVA_INT, 40L, settings.stillSamples());
        target.set(JAVA_INT, 44L, settings.stationaryDelayMillis());
        target.set(JAVA_INT, 48L, settings.adaptiveSampling() ? 1 : 0);
        target.set(JAVA_INT, 52L, settings.minimumSamples());
        target.set(JAVA_FLOAT, 56L, settings.noiseThreshold());
        target.set(JAVA_INT, 60L, settings.interactiveTimeLimitMillis());
        target.set(JAVA_INT, 64L, settings.stillTimeLimitMillis());
        target.set(JAVA_INT, 68L, settings.minimumBounce());
        target.set(JAVA_INT, 72L, settings.maximumBounce());
        target.set(JAVA_INT, 76L, settings.diffuseBounces());
        target.set(JAVA_INT, 80L, settings.glossyBounces());
        target.set(JAVA_INT, 84L, settings.transmissionBounces());
        target.set(JAVA_INT, 88L, settings.volumeBounces());
        target.set(JAVA_INT, 92L, settings.transparentBounces());
        target.set(JAVA_FLOAT, 96L, settings.clampDirect());
        target.set(JAVA_FLOAT, 100L, settings.clampIndirect());
        target.set(JAVA_FLOAT, 104L, settings.filterGlossy());
        target.set(JAVA_INT, 108L, settings.reflectiveCaustics() ? 1 : 0);
        target.set(JAVA_INT, 112L, settings.refractiveCaustics() ? 1 : 0);
        target.set(JAVA_INT, 116L, settings.pixelFilter().nativeId());
        target.set(JAVA_FLOAT, 120L, settings.filterWidth());
        target.set(JAVA_INT, 124L, settings.seed());
        target.set(JAVA_INT, 128L, settings.denoiserMode().nativeId());
        target.set(JAVA_INT, 132L, settings.denoiserStartSample());
        target.set(JAVA_INT, 136L, settings.denoiserInput().nativeId());
        target.set(JAVA_INT, 140L, settings.denoiserPrefilter().nativeId());
        target.set(JAVA_INT, 144L, settings.denoiserQuality().nativeId());
        target.set(JAVA_INT, 148L, settings.denoiserUseGpu() ? 1 : 0);
        target.set(JAVA_FLOAT, 152L, settings.exposureEv());
        target.set(JAVA_FLOAT, 156L, settings.gamma());
        target.set(JAVA_INT, 160L, settings.viewTransform().nativeId());
        target.set(JAVA_INT, 164L, settings.activePass().nativeId());
        target.set(JAVA_INT, 168L, settings.debugOverlay() ? 1 : 0);
        target.set(JAVA_INT, 172L, settings.dynamicResolution() ? 1 : 0);
        target.set(JAVA_INT, 176L, settings.interactiveResolutionPercentage());
        target.set(JAVA_INT, 180L, settings.passCacheMegabytes());
        target.set(JAVA_INT, 184L, settings.samplingPattern().nativeId());
        target.set(JAVA_FLOAT, 188L, settings.cameraClipNear());
        target.set(JAVA_FLOAT, 192L, settings.cameraClipFar());
        target.set(JAVA_INT, 196L, settings.projectionMode().nativeId());
        target.set(JAVA_FLOAT, 200L, settings.focalLengthMm());
        target.set(JAVA_FLOAT, 204L, settings.sensorWidthMm());
        target.set(JAVA_INT, 208L, settings.depthOfField() ? 1 : 0);
        target.set(JAVA_FLOAT, 212L, settings.focusDistance());
        target.set(JAVA_FLOAT, 216L, settings.fStop());
        target.set(JAVA_INT, 220L, settings.apertureBlades());
        target.set(JAVA_FLOAT, 224L, settings.apertureRotationDegrees());
        target.set(JAVA_FLOAT, 228L, settings.apertureRatio());
        target.set(JAVA_INT, 232L, settings.atmosphereSunDisc() ? 1 : 0);
        target.set(JAVA_FLOAT, 236L, settings.atmosphereSunSizeDegrees());
        target.set(JAVA_FLOAT, 240L, settings.atmosphereSunIntensity());
        target.set(JAVA_FLOAT, 244L, settings.atmosphereSunElevationDegrees());
        target.set(JAVA_FLOAT, 248L, settings.atmosphereSunRotationDegrees());
        target.set(JAVA_FLOAT, 252L, settings.atmosphereAltitudeMeters());
        target.set(JAVA_FLOAT, 256L, settings.atmosphereAirDensity());
        target.set(JAVA_FLOAT, 260L, settings.atmosphereAerosolDensity());
        target.set(JAVA_FLOAT, 264L, settings.atmosphereOzoneDensity());
        target.set(JAVA_FLOAT, 268L, settings.pbrNormalStrength());
        target.set(JAVA_FLOAT, 272L, settings.pbrEmissionScale());
        target.set(JAVA_INT, 276L, settings.workingSpace().nativeId());
        target.set(JAVA_INT, 280L, settings.dlssQualityMode().nativeId());
        target.set(JAVA_INT, 284L, settings.depthOfFieldMode().nativeId());
        target.set(JAVA_INT, 288L, settings.cameraType().nativeId());
        target.set(JAVA_INT, 292L, settings.panoramaType().nativeId());
        target.set(JAVA_FLOAT, 296L, settings.fisheyeFovDegrees());
        target.set(JAVA_FLOAT, 300L, settings.fisheyeLensMm());
        target.set(JAVA_FLOAT, 304L, settings.latitudeMinDegrees());
        target.set(JAVA_FLOAT, 308L, settings.latitudeMaxDegrees());
        target.set(JAVA_FLOAT, 312L, settings.longitudeMinDegrees());
        target.set(JAVA_FLOAT, 316L, settings.longitudeMaxDegrees());
        target.set(JAVA_FLOAT, 320L, settings.fisheyePolynomialK0());
        target.set(JAVA_FLOAT, 324L, settings.fisheyePolynomialK1());
        target.set(JAVA_FLOAT, 328L, settings.fisheyePolynomialK2());
        target.set(JAVA_FLOAT, 332L, settings.fisheyePolynomialK3());
        target.set(JAVA_FLOAT, 336L, settings.fisheyePolynomialK4());
        target.set(JAVA_FLOAT, 340L, settings.centralCylindricalLongitudeMinDegrees());
        target.set(JAVA_FLOAT, 344L, settings.centralCylindricalLongitudeMaxDegrees());
        target.set(JAVA_FLOAT, 348L, settings.centralCylindricalHeightMin());
        target.set(JAVA_FLOAT, 352L, settings.centralCylindricalHeightMax());
        target.set(JAVA_FLOAT, 356L, settings.centralCylindricalRadius());
        target.set(JAVA_FLOAT, 360L, settings.cameraShiftX());
        target.set(JAVA_FLOAT, 364L, settings.cameraShiftY());
        target.set(JAVA_FLOAT, 368L, settings.pbrWetness());
        target.set(JAVA_FLOAT, 372L, settings.pbrSubsurfaceScale());
        target.set(JAVA_FLOAT, 376L, settings.pbrHeightStrength());
        target.set(JAVA_FLOAT, 380L, settings.pbrHeightDistance());
        target.set(JAVA_INT, 384L, settings.pbrHeightMappingMode().nativeId());
        target.set(JAVA_INT, 388L, settings.pbrParallaxSteps());
    }
}

package dev.cyclesrenderer.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DepthReprojectionMathTest {
    private static final double TOLERANCE = 1.0e-9D;
    private static final DepthReprojectionMath.Pose IDENTITY_POSE = new DepthReprojectionMath.Pose(
            new DepthReprojectionMath.Vec3(0.0D, 0.0D, 0.0D),
            new DepthReprojectionMath.Quaternion(0.0D, 0.0D, 0.0D, 1.0D));

    @Test
    void identityPreservesOffAxisPixelsAndAxialDepth() {
        var projection = perspective(1.0D, 0.0D, 0.0D, 0.1D, 100.0D);

        var result = requireProjection(IDENTITY_POSE, projection, 0.75D, 0.25D, 10.0D,
                IDENTITY_POSE, projection);

        assertEquals(0.75D, result.u(), TOLERANCE);
        assertEquals(0.25D, result.v(), TOLERANCE);
        assertEquals(10.0D, result.axialDepth(), TOLERANCE);
    }

    @Test
    void targetRotationUsesMinecraftLocalMinusZConvention() {
        var projection = perspective(1.0D, 0.0D, 0.0D, 0.1D, 100.0D);
        double halfAngle = Math.toRadians(5.0D);
        var target = new DepthReprojectionMath.Pose(
                IDENTITY_POSE.position(),
                new DepthReprojectionMath.Quaternion(
                        0.0D, Math.sin(halfAngle), 0.0D, Math.cos(halfAngle)));

        var result = requireProjection(IDENTITY_POSE, projection, 0.5D, 0.5D, 10.0D,
                target, projection);

        assertTrue(result.u() > 0.5D);
        assertEquals(0.5D, result.v(), TOLERANCE);
    }

    @Test
    void translationProducesMoreParallaxForNearGeometry() {
        var projection = perspective(1.0D, 0.0D, 0.0D, 0.1D, 100.0D);
        var target = new DepthReprojectionMath.Pose(
                new DepthReprojectionMath.Vec3(1.0D, 0.0D, 0.0D),
                IDENTITY_POSE.orientation());

        var near = requireProjection(IDENTITY_POSE, projection, 0.5D, 0.5D, 2.0D,
                target, projection);
        var far = requireProjection(IDENTITY_POSE, projection, 0.5D, 0.5D, 10.0D,
                target, projection);

        assertTrue(near.u() < far.u());
        assertTrue(Math.abs(near.u() - 0.5D) > Math.abs(far.u() - 0.5D));
    }

    @Test
    void projectionAccountsForFovAspectAndShift() {
        var source = perspective(1.0D, 0.0D, 0.0D, 0.1D, 100.0D);
        var narrow = perspective(1.0D, 0.0D, 0.0D, 0.1D, 100.0D, Math.toRadians(60.0D));
        var wideAspect = perspective(2.0D, 0.0D, 0.0D, 0.1D, 100.0D);
        var shifted = perspective(1.0D, 0.1D, -0.1D, 0.1D, 100.0D);

        assertTrue(requireProjection(IDENTITY_POSE, source, 0.7D, 0.5D, 10.0D,
                IDENTITY_POSE, narrow).u() > 0.7D);
        assertEquals(0.6D, requireProjection(IDENTITY_POSE, source, 0.7D, 0.5D, 10.0D,
                IDENTITY_POSE, wideAspect).u(), TOLERANCE);
        var shiftedResult = requireProjection(IDENTITY_POSE, source, 0.5D, 0.5D, 10.0D,
                IDENTITY_POSE, shifted);
        assertEquals(0.4D, shiftedResult.u(), TOLERANCE);
        assertEquals(0.6D, shiftedResult.v(), TOLERANCE);
    }

    @Test
    void rejectsInvalidBackgroundClipAndTargetGeometry() {
        var projection = perspective(1.0D, 0.0D, 0.0D, 1.0D, 20.0D);
        assertRejected(projection, 0.0D);
        assertRejected(projection, 0.5D);
        assertRejected(projection, 21.0D);
        assertRejected(projection, Double.NaN);
        assertRejected(projection, Double.POSITIVE_INFINITY);

        var targetBeyondGeometry = new DepthReprojectionMath.Pose(
                new DepthReprojectionMath.Vec3(0.0D, 0.0D, -11.0D),
                IDENTITY_POSE.orientation());
        assertTrue(DepthReprojectionMath.project(
                IDENTITY_POSE, projection, 0.5D, 0.5D, 10.0D,
                targetBeyondGeometry, projection).isEmpty());
    }

    @Test
    void nearestDepthWinsAndUncoveredPixelsRemainInvalid() {
        var projection = perspective(1.0D, 0.0D, 0.0D, 0.1D, 100.0D);
        var raster = DepthReprojectionMath.rasterizeNearest(
                4, 4, IDENTITY_POSE, projection, IDENTITY_POSE, projection,
                List.of(
                        new DepthReprojectionMath.SourceSample(7, 0.3D, 0.3D, 8.0D),
                        new DepthReprojectionMath.SourceSample(3, 0.3D, 0.3D, 2.0D)));

        assertEquals(3, raster.sourceIndices()[5]);
        assertEquals(2.0D, raster.axialDepths()[5], TOLERANCE);
        assertFalse(raster.invalid(1, 1));
        assertTrue(raster.invalid(0, 0));
        assertEquals(Double.POSITIVE_INFINITY, raster.axialDepths()[0]);
    }

    @Test
    void sourceResolutionRasterAvoidsDisplayUpscaleCoveragePenalty() {
        int sourceWidth = 8;
        int sourceHeight = 4;
        var projection = perspective(2.0D, 0.0D, 0.0D, 0.1D, 100.0D);
        List<DepthReprojectionMath.SourceSample> samples = new ArrayList<>();
        for (int y = 0; y < sourceHeight; y++) {
            for (int x = 0; x < sourceWidth; x++) {
                samples.add(new DepthReprojectionMath.SourceSample(
                        y * sourceWidth + x,
                        (x + 0.5D) / sourceWidth,
                        (y + 0.5D) / sourceHeight,
                        10.0D));
            }
        }

        var sourceRaster = DepthReprojectionMath.rasterizeNearest(
                sourceWidth, sourceHeight,
                IDENTITY_POSE, projection, IDENTITY_POSE, projection, samples);
        var doubledDisplayRaster = DepthReprojectionMath.rasterizeNearest(
                sourceWidth * 2, sourceHeight * 2,
                IDENTITY_POSE, projection, IDENTITY_POSE, projection, samples);

        assertEquals(1.0D, validCoverage(sourceRaster), TOLERANCE);
        assertEquals(0.25D, validCoverage(doubledDisplayRaster), TOLERANCE);
    }

    private static DepthReprojectionMath.Projection requireProjection(
            DepthReprojectionMath.Pose sourcePose,
            DepthReprojectionMath.Perspective sourceProjection,
            double u,
            double v,
            double depth,
            DepthReprojectionMath.Pose targetPose,
            DepthReprojectionMath.Perspective targetProjection) {
        return DepthReprojectionMath.project(
                sourcePose, sourceProjection, u, v, depth, targetPose, targetProjection)
                .orElseThrow();
    }

    private static void assertRejected(DepthReprojectionMath.Perspective projection, double depth) {
        assertTrue(DepthReprojectionMath.project(
                IDENTITY_POSE, projection, 0.5D, 0.5D, depth,
                IDENTITY_POSE, projection).isEmpty());
    }

    private static double validCoverage(DepthReprojectionMath.Raster raster) {
        int valid = 0;
        for (int sourceIndex : raster.sourceIndices()) {
            if (sourceIndex >= 0) {
                valid++;
            }
        }
        return (double) valid / raster.sourceIndices().length;
    }

    private static DepthReprojectionMath.Perspective perspective(
            double aspect,
            double shiftX,
            double shiftY,
            double nearClip,
            double farClip) {
        return perspective(aspect, shiftX, shiftY, nearClip, farClip, Math.PI * 0.5D);
    }

    private static DepthReprojectionMath.Perspective perspective(
            double aspect,
            double shiftX,
            double shiftY,
            double nearClip,
            double farClip,
            double fov) {
        return new DepthReprojectionMath.Perspective(
                fov, aspect, shiftX, shiftY, nearClip, farClip);
    }
}

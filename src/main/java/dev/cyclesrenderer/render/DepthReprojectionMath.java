package dev.cyclesrenderer.render;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** CPU reference for the perspective reprojection shader contract. */
final class DepthReprojectionMath {
    private static final double EPSILON = 1.0e-12D;

    private DepthReprojectionMath() {
    }

    static Optional<Projection> project(
            Pose sourcePose,
            Perspective sourceProjection,
            double sourceU,
            double sourceV,
            double sourceAxialDepth,
            Pose targetPose,
            Perspective targetProjection) {
        if (!sourcePose.valid() || !targetPose.valid()
                || !sourceProjection.valid() || !targetProjection.valid()
                || !finite(sourceU, sourceV, sourceAxialDepth)
                || sourceU < 0.0D || sourceU >= 1.0D
                || sourceV < 0.0D || sourceV >= 1.0D
                || !sourceProjection.containsDepth(sourceAxialDepth)) {
            return Optional.empty();
        }

        Vec3 sourceLocal = sourceProjection.unproject(sourceU, sourceV, sourceAxialDepth);
        Vec3 world = sourcePose.position().add(sourcePose.orientation().rotate(sourceLocal));
        Vec3 targetLocal = targetPose.orientation().inverseRotate(world.subtract(targetPose.position()));
        double targetDepth = -targetLocal.z();
        if (!targetProjection.containsDepth(targetDepth)) {
            return Optional.empty();
        }

        double tangent = Math.tan(targetProjection.verticalFovRadians() * 0.5D);
        double planeX = targetLocal.x() * targetProjection.radiusY()
                / (targetDepth * tangent);
        double planeY = targetLocal.y() * targetProjection.radiusY()
                / (targetDepth * tangent);
        double u = ((planeX - targetProjection.offsetX())
                / targetProjection.radiusX() + 1.0D) * 0.5D;
        double v = ((planeY - targetProjection.offsetY())
                / targetProjection.radiusY() + 1.0D) * 0.5D;
        if (!finite(u, v) || u < 0.0D || u >= 1.0D || v < 0.0D || v >= 1.0D) {
            return Optional.empty();
        }
        return Optional.of(new Projection(u, v, targetDepth));
    }

    static Raster rasterizeNearest(
            int width,
            int height,
            Pose sourcePose,
            Perspective sourceProjection,
            Pose targetPose,
            Perspective targetProjection,
            List<SourceSample> samples) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("raster dimensions must be positive");
        }
        double[] depths = new double[Math.multiplyExact(width, height)];
        int[] sourceIndices = new int[depths.length];
        Arrays.fill(depths, Double.POSITIVE_INFINITY);
        Arrays.fill(sourceIndices, -1);
        for (SourceSample sample : samples) {
            project(sourcePose, sourceProjection, sample.u(), sample.v(), sample.axialDepth(),
                    targetPose, targetProjection).ifPresent(projected -> {
                        int x = (int) (projected.u() * width);
                        int y = (int) (projected.v() * height);
                        int targetIndex = y * width + x;
                        if (projected.axialDepth() < depths[targetIndex]) {
                            depths[targetIndex] = projected.axialDepth();
                            sourceIndices[targetIndex] = sample.sourceIndex();
                        }
                    });
        }
        return new Raster(width, height, depths, sourceIndices);
    }

    private static boolean finite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    record Perspective(
            double verticalFovRadians,
            double aspect,
            double shiftX,
            double shiftY,
            double nearClip,
            double farClip) {
        boolean valid() {
            return finite(verticalFovRadians, aspect, shiftX, shiftY, nearClip, farClip)
                    && verticalFovRadians > 0.0D && verticalFovRadians < Math.PI
                    && aspect > 0.0D && nearClip > 0.0D && farClip > nearClip;
        }

        boolean containsDepth(double depth) {
            return Double.isFinite(depth) && depth >= nearClip && depth <= farClip;
        }

        Vec3 unproject(double u, double v, double axialDepth) {
            double tangent = Math.tan(verticalFovRadians * 0.5D);
            double planeX = (2.0D * u - 1.0D) * radiusX() + offsetX();
            double planeY = (2.0D * v - 1.0D) * radiusY() + offsetY();
            return new Vec3(
                    planeX * tangent / radiusY() * axialDepth,
                    planeY * tangent / radiusY() * axialDepth,
                    -axialDepth);
        }

        double fitAspect() {
            return Math.max(aspect, 1.0D / aspect);
        }

        double radiusX() {
            return aspect >= 1.0D ? aspect : 1.0D;
        }

        double radiusY() {
            return aspect >= 1.0D ? 1.0D : 1.0D / aspect;
        }

        double offsetX() {
            return 2.0D * fitAspect() * shiftX;
        }

        double offsetY() {
            return 2.0D * fitAspect() * shiftY;
        }
    }

    record Pose(Vec3 position, Quaternion orientation) {
        boolean valid() {
            return position != null && orientation != null
                    && position.valid() && orientation.valid();
        }
    }

    record Vec3(double x, double y, double z) {
        Vec3 add(Vec3 other) {
            return new Vec3(x + other.x, y + other.y, z + other.z);
        }

        Vec3 subtract(Vec3 other) {
            return new Vec3(x - other.x, y - other.y, z - other.z);
        }

        boolean valid() {
            return finite(x, y, z);
        }
    }

    record Quaternion(double x, double y, double z, double w) {
        boolean valid() {
            double norm = x * x + y * y + z * z + w * w;
            return finite(x, y, z, w, norm) && norm > EPSILON;
        }

        Vec3 rotate(Vec3 vector) {
            Quaternion normalized = normalized();
            Vec3 axis = new Vec3(normalized.x, normalized.y, normalized.z);
            Vec3 twiceCross = cross(axis, vector, 2.0D);
            return vector.add(scale(twiceCross, normalized.w)).add(cross(axis, twiceCross, 1.0D));
        }

        Vec3 inverseRotate(Vec3 vector) {
            Quaternion normalized = normalized();
            return new Quaternion(-normalized.x, -normalized.y, -normalized.z, normalized.w)
                    .rotate(vector);
        }

        private Quaternion normalized() {
            double inverseLength = 1.0D / Math.sqrt(x * x + y * y + z * z + w * w);
            return new Quaternion(x * inverseLength, y * inverseLength,
                    z * inverseLength, w * inverseLength);
        }

        private static Vec3 cross(Vec3 first, Vec3 second, double scale) {
            return new Vec3(
                    (first.y * second.z - first.z * second.y) * scale,
                    (first.z * second.x - first.x * second.z) * scale,
                    (first.x * second.y - first.y * second.x) * scale);
        }

        private static Vec3 scale(Vec3 vector, double scale) {
            return new Vec3(vector.x * scale, vector.y * scale, vector.z * scale);
        }
    }

    record Projection(double u, double v, double axialDepth) {
    }

    record SourceSample(int sourceIndex, double u, double v, double axialDepth) {
    }

    record Raster(int width, int height, double[] axialDepths, int[] sourceIndices) {
        boolean invalid(int x, int y) {
            return sourceIndices[y * width + x] < 0;
        }
    }
}

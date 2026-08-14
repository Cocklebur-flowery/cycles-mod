package dev.cyclesrenderer.camera;

import dev.cyclesrenderer.config.CyclesRenderSettings;

import java.util.Optional;

/** Pure projection math matching the Cycles 5.2 camera kernel. */
public final class CameraProjection {
    private static final double PI = Math.PI;

    private CameraProjection() {
    }

    public static Optional<Direction> direction(
            CyclesRenderSettings settings,
            float verticalFovRadians,
            float aspect,
            float u,
            float v) {
        if (!Float.isFinite(u) || !Float.isFinite(v) || aspect <= 0.0F) {
            return Optional.empty();
        }
        if (settings.cameraType() == CyclesRenderSettings.CameraType.PERSPECTIVE) {
            return Optional.of(perspective(settings, verticalFovRadians, aspect, u, v));
        }
        Optional<Direction> cyclesDirection = panorama(settings, aspect, u, v);
        return cyclesDirection.map(direction -> settings.panoramaType()
                == CyclesRenderSettings.PanoramaType.MIRRORBALL
                ? normalize(direction.x(), direction.z(), direction.y())
                : normalize(-direction.y(), direction.z(), -direction.x()));
    }

    private static Direction perspective(
            CyclesRenderSettings settings,
            float verticalFovRadians,
            float aspect,
            float u,
            float v) {
        float effectiveFov = settings.projectionMode()
                == CyclesRenderSettings.ProjectionMode.PHYSICAL_LENS
                ? 2.0F * (float) Math.atan(
                    settings.sensorWidthMm() / (2.0F * settings.focalLengthMm() * aspect))
                : verticalFovRadians;
        double tangent = Math.tan(Math.clamp(effectiveFov, 0.001F, (float) PI - 0.001F) * 0.5D);
        double fitAspect = Math.max(aspect, 1.0F / aspect);
        double radiusX = aspect >= 1.0F ? aspect : 1.0D;
        double radiusY = aspect >= 1.0F ? 1.0D : 1.0D / aspect;
        double planeX = (2.0D * u - 1.0D) * radiusX
                + 2.0D * fitAspect * settings.cameraShiftX();
        double planeY = (2.0D * v - 1.0D) * radiusY
                + 2.0D * fitAspect * settings.cameraShiftY();
        return normalize(planeX * tangent / radiusY, planeY * tangent / radiusY, -1.0D);
    }

    private static Optional<Direction> panorama(
            CyclesRenderSettings settings,
            float aspect,
            float u,
            float v) {
        return switch (settings.panoramaType()) {
            case EQUIRECTANGULAR -> Optional.of(equirectangular(settings, u, v));
            case EQUIANGULAR_CUBEMAP_FACE -> Optional.of(equiangularCubeFace(u, v));
            case MIRRORBALL -> mirrorball(u, v);
            case FISHEYE_EQUIDISTANT -> fisheyeEquidistant(settings, u, v);
            case FISHEYE_EQUISOLID -> fisheyeEquisolid(settings, aspect, u, v);
            case FISHEYE_LENS_POLYNOMIAL -> fisheyePolynomial(settings, aspect, u, v);
            case CENTRAL_CYLINDRICAL -> Optional.of(centralCylindrical(settings, u, v));
        };
    }

    private static Direction equirectangular(CyclesRenderSettings settings, float u, float v) {
        double longitudeMin = Math.toRadians(settings.longitudeMinDegrees());
        double longitudeMax = Math.toRadians(settings.longitudeMaxDegrees());
        double latitudeMin = Math.toRadians(settings.latitudeMinDegrees());
        double latitudeMax = Math.toRadians(settings.latitudeMaxDegrees());
        double phi = (longitudeMin - longitudeMax) * u - longitudeMin;
        double theta = (latitudeMin - latitudeMax) * v - latitudeMin + PI * 0.5D;
        return new Direction(
                Math.sin(theta) * Math.cos(phi),
                Math.sin(theta) * Math.sin(phi),
                Math.cos(theta));
    }

    private static Direction centralCylindrical(
            CyclesRenderSettings settings,
            float u,
            float v) {
        double theta = mix(
                -Math.toRadians(settings.centralCylindricalLongitudeMinDegrees()),
                -Math.toRadians(settings.centralCylindricalLongitudeMaxDegrees()),
                u);
        double z = mix(
                settings.centralCylindricalHeightMin()
                        / settings.centralCylindricalRadius(),
                settings.centralCylindricalHeightMax()
                        / settings.centralCylindricalRadius(),
                v);
        return normalize(Math.cos(theta), Math.sin(theta), z);
    }

    private static Optional<Direction> fisheyeEquidistant(
            CyclesRenderSettings settings,
            float u,
            float v) {
        double x = (u - 0.5D) * 2.0D;
        double y = (v - 0.5D) * 2.0D;
        double radius = Math.hypot(x, y);
        if (radius > 1.0D) {
            return Optional.empty();
        }
        return Optional.of(fisheyeDirection(
                radius * Math.toRadians(settings.fisheyeFovDegrees()) * 0.5D,
                x, y, radius));
    }

    private static Optional<Direction> fisheyeEquisolid(
            CyclesRenderSettings settings,
            float aspect,
            float u,
            float v) {
        double width = settings.sensorWidthMm();
        double height = width / aspect;
        double x = (u - 0.5D) * width;
        double y = (v - 0.5D) * height;
        double lens = settings.fisheyeLensMm();
        double fov = Math.toRadians(settings.fisheyeFovDegrees());
        double radius = Math.hypot(x, y);
        double maximum = 2.0D * lens * Math.sin(fov * 0.25D);
        if (radius > maximum || radius > 2.0D * lens) {
            return Optional.empty();
        }
        return Optional.of(fisheyeDirection(
                2.0D * Math.asin(radius / (2.0D * lens)), x, y, radius));
    }

    private static Optional<Direction> fisheyePolynomial(
            CyclesRenderSettings settings,
            float aspect,
            float u,
            float v) {
        double width = settings.sensorWidthMm();
        double height = width / aspect;
        double x = (u - 0.5D) * width;
        double y = (v - 0.5D) * height;
        double radius = Math.hypot(x, y);
        double radius2 = radius * radius;
        double theta = -(settings.fisheyePolynomialK0()
                + settings.fisheyePolynomialK1() * radius
                + settings.fisheyePolynomialK2() * radius2
                + settings.fisheyePolynomialK3() * radius2 * radius
                + settings.fisheyePolynomialK4() * radius2 * radius2);
        if (Math.abs(theta) > Math.toRadians(settings.fisheyeFovDegrees()) * 0.5D) {
            return Optional.empty();
        }
        return Optional.of(fisheyeDirection(theta, x, y, radius));
    }

    private static Optional<Direction> mirrorball(float u, float v) {
        double x = 2.0D * u - 1.0D;
        double z = 2.0D * v - 1.0D;
        double radius2 = x * x + z * z;
        if (radius2 > 1.0D) {
            return Optional.empty();
        }
        double y = -Math.sqrt(Math.max(1.0D - radius2, 0.0D));
        double dot = -y;
        return Optional.of(normalize(2.0D * dot * x, 1.0D + 2.0D * dot * y,
                2.0D * dot * z));
    }

    private static Direction equiangularCubeFace(float u, float v) {
        double y = Math.tan((0.5D - u) * PI * 0.5D);
        double z = Math.tan((v - 0.5D) * PI * 0.5D);
        return normalize(1.0D, y, z);
    }

    private static Direction fisheyeDirection(
            double theta,
            double u,
            double v,
            double radius) {
        if (radius <= 1.0e-12D) {
            return new Direction(1.0D, 0.0D, 0.0D);
        }
        double phi = Math.acos(Math.clamp(u / radius, -1.0D, 1.0D));
        if (v < 0.0D) {
            phi = -phi;
        }
        return normalize(
                Math.cos(theta),
                -Math.cos(phi) * Math.sin(theta),
                Math.sin(phi) * Math.sin(theta));
    }

    private static Direction normalize(double x, double y, double z) {
        double length = Math.sqrt(x * x + y * y + z * z);
        if (!Double.isFinite(length) || length <= 1.0e-12D) {
            return new Direction(0.0D, 0.0D, -1.0D);
        }
        return new Direction(x / length, y / length, z / length);
    }

    private static double mix(double first, double second, double amount) {
        return first * (1.0D - amount) + second * amount;
    }

    public record Direction(double x, double y, double z) {
        public double dot(Direction other) {
            return x * other.x + y * other.y + z * other.z;
        }
    }
}

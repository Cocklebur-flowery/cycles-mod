package dev.cyclesrenderer.camera;

import java.util.Arrays;

/** Builds a spatially weighted log-luminance histogram for automatic exposure. */
public final class ExposureHistogram {
    public static final int BIN_COUNT = 256;
    public static final float LOG_MIN = -16.0F;
    public static final float LOG_MAX = 16.0F;

    private final double[] bins = new double[BIN_COUNT];
    private double totalWeight;

    public void clear() {
        Arrays.fill(bins, 0.0D);
        totalWeight = 0.0D;
    }

    public void add(float logLuminance, double weight) {
        if (!Float.isFinite(logLuminance) || !Double.isFinite(weight) || weight <= 0.0D) {
            return;
        }
        float normalized = (Math.clamp(logLuminance, LOG_MIN, LOG_MAX) - LOG_MIN)
                / (LOG_MAX - LOG_MIN);
        int index = Math.min(BIN_COUNT - 1, (int) (normalized * BIN_COUNT));
        bins[index] += weight;
        totalWeight += weight;
    }

    public Result measure(Settings settings) {
        if (totalWeight <= 0.0D) {
            return Result.unavailable();
        }
        double lowWeight = totalWeight * settings.lowPercentile();
        double highWeight = totalWeight * settings.highPercentile();
        double highlightWeight = totalWeight * settings.highlightPercentile();
        double cumulative = 0.0D;
        double includedWeight = 0.0D;
        double includedLogSum = 0.0D;
        float lowLog = LOG_MIN;
        float highLog = LOG_MAX;
        float highlightLog = LOG_MAX;
        boolean lowFound = false;
        boolean highFound = false;
        boolean highlightFound = false;

        for (int index = 0; index < bins.length; index++) {
            double binWeight = bins[index];
            if (binWeight <= 0.0D) {
                continue;
            }
            double next = cumulative + binWeight;
            float center = binCenter(index);
            if (!lowFound && next >= lowWeight) {
                lowLog = center;
                lowFound = true;
            }
            if (!highFound && next >= highWeight) {
                highLog = center;
                highFound = true;
            }
            if (!highlightFound && next >= highlightWeight) {
                highlightLog = center;
                highlightFound = true;
            }

            double includedStart = Math.max(cumulative, lowWeight);
            double includedEnd = Math.min(next, highWeight);
            if (includedEnd > includedStart) {
                double included = includedEnd - includedStart;
                includedWeight += included;
                includedLogSum += included * center;
            }
            cumulative = next;
        }

        if (includedWeight <= 0.0D) {
            return Result.unavailable();
        }
        float meanLog = (float) (includedLogSum / includedWeight);
        return new Result(true, meanLog, lowLog, highLog, highlightLog, totalWeight);
    }

    public static double centerWeight(int x, int y, int width, int height, float strength) {
        if (width <= 0 || height <= 0) {
            return 0.0D;
        }
        double nx = (2.0D * x + 1.0D) / width - 1.0D;
        double ny = (2.0D * y + 1.0D) / height - 1.0D;
        double radiusSquared = nx * nx + ny * ny;
        return 1.0D + Math.max(0.0F, strength) * Math.exp(-2.0D * radiusSquared);
    }

    private static float binCenter(int index) {
        return LOG_MIN + (index + 0.5F) * (LOG_MAX - LOG_MIN) / BIN_COUNT;
    }

    public record Settings(
            float lowPercentile,
            float highPercentile,
            float highlightPercentile) {
        public Settings {
            if (!Float.isFinite(lowPercentile)
                    || !Float.isFinite(highPercentile)
                    || !Float.isFinite(highlightPercentile)
                    || lowPercentile < 0.0F
                    || highPercentile <= lowPercentile
                    || highlightPercentile < highPercentile
                    || highlightPercentile > 1.0F) {
                throw new IllegalArgumentException("invalid exposure histogram percentiles");
            }
        }
    }

    public record Result(
            boolean available,
            float meanLogLuminance,
            float lowLogLuminance,
            float highLogLuminance,
            float highlightLogLuminance,
            double sampleWeight) {
        private static Result unavailable() {
            return new Result(false, 0.0F, 0.0F, 0.0F, 0.0F, 0.0D);
        }
    }
}

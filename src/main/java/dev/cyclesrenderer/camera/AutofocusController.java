package dev.cyclesrenderer.camera;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Rejects autofocus outliers, selects a coherent subject and smooths focus in log distance. */
public final class AutofocusController {
    private boolean initialized;
    private float currentDistance;
    private float targetDistance;
    private long acceptedMeasurements;

    public float update(
            List<FocusSample> samples,
            Settings settings,
            float fallbackDistance,
            float deltaSeconds,
            boolean locked) {
        float measured = selectDistance(samples, settings, fallbackDistance);
        targetDistance = Math.clamp(measured, settings.minimumDistance(), settings.maximumDistance());
        if (!initialized) {
            initialized = true;
            currentDistance = targetDistance;
            acceptedMeasurements++;
            return currentDistance;
        }
        if (locked || !Float.isFinite(deltaSeconds) || deltaSeconds <= 0.0F) {
            return currentDistance;
        }
        float relativeDifference = Math.abs(targetDistance - currentDistance)
                / Math.max(currentDistance, settings.minimumDistance());
        if (Math.abs(targetDistance - currentDistance) <= settings.deadbandDistance()
                || relativeDifference <= settings.deadbandRatio()) {
            return currentDistance;
        }
        float currentLog = log2(currentDistance);
        float targetLog = log2(targetDistance);
        float alpha = settings.responseSeconds() <= 0.0F
                ? 1.0F
                : 1.0F - (float) Math.exp(-deltaSeconds / settings.responseSeconds());
        float maximumStep = settings.maximumStopsPerSecond() * deltaSeconds;
        float step = Math.clamp((targetLog - currentLog) * alpha, -maximumStep, maximumStep);
        currentDistance = (float) Math.pow(2.0D, currentLog + step);
        acceptedMeasurements++;
        return currentDistance;
    }

    public void reset() {
        initialized = false;
        currentDistance = 0.0F;
        targetDistance = 0.0F;
        acceptedMeasurements = 0L;
    }

    public State state() {
        return new State(initialized, currentDistance, targetDistance, acceptedMeasurements);
    }

    static float selectDistance(
            List<FocusSample> samples,
            Settings settings,
            float fallbackDistance) {
        List<FocusSample> valid = samples.stream()
                .filter(sample -> Float.isFinite(sample.distance())
                        && sample.distance() >= settings.minimumDistance()
                        && sample.distance() <= settings.maximumDistance()
                        && Float.isFinite(sample.weight())
                        && sample.weight() > 0.0F)
                .sorted(Comparator.comparingDouble(FocusSample::distance))
                .toList();
        if (valid.isEmpty()) {
            return fallbackDistance;
        }

        List<List<FocusSample>> clusters = new ArrayList<>();
        List<FocusSample> cluster = new ArrayList<>();
        clusters.add(cluster);
        float previousLog = Float.NaN;
        for (FocusSample sample : valid) {
            float sampleLog = log2(sample.distance());
            if (!cluster.isEmpty() && sampleLog - previousLog > settings.clusterGapStops()) {
                cluster = new ArrayList<>();
                clusters.add(cluster);
            }
            cluster.add(sample);
            previousLog = sampleLog;
        }

        List<FocusSample> selected = clusters.stream()
                .max(Comparator.comparingDouble(AutofocusController::clusterScore))
                .orElse(valid);
        return weightedMedian(selected);
    }

    private static double clusterScore(List<FocusSample> cluster) {
        double score = 0.0D;
        for (FocusSample sample : cluster) {
            score += sample.weight() * (sample.primary() ? 4.0D : 1.0D);
        }
        return score;
    }

    private static float weightedMedian(List<FocusSample> samples) {
        double total = samples.stream().mapToDouble(FocusSample::weight).sum();
        double threshold = total * 0.5D;
        double cumulative = 0.0D;
        for (FocusSample sample : samples) {
            cumulative += sample.weight();
            if (cumulative >= threshold) {
                return sample.distance();
            }
        }
        return samples.getLast().distance();
    }

    private static float log2(float value) {
        return (float) (Math.log(value) / Math.log(2.0D));
    }

    public record FocusSample(float distance, float weight, boolean primary) {
    }

    public record Settings(
            float minimumDistance,
            float maximumDistance,
            float clusterGapStops,
            float responseSeconds,
            float deadbandDistance,
            float deadbandRatio,
            float maximumStopsPerSecond) {
        public Settings {
            if (minimumDistance <= 0.0F
                    || maximumDistance < minimumDistance
                    || clusterGapStops <= 0.0F
                    || responseSeconds < 0.0F
                    || deadbandDistance < 0.0F
                    || deadbandRatio < 0.0F
                    || maximumStopsPerSecond <= 0.0F) {
                throw new IllegalArgumentException("invalid autofocus settings");
            }
        }
    }

    public record State(
            boolean initialized,
            float currentDistance,
            float targetDistance,
            long acceptedMeasurements) {
    }
}

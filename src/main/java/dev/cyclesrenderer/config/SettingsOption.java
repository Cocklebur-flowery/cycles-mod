package dev.cyclesrenderer.config;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** Editor metadata and persistence binding for one client setting. */
public final class SettingsOption<T> {
    private final String id;
    private final CyclesClientConfig.Category category;
    private final String translationKey;
    private final CyclesClientConfig.ValueKind kind;
    private final Supplier<T> reader;
    private final Consumer<T> writer;
    private final UnaryOperator<T> normalizer;
    private final double minimum;
    private final double maximum;
    private final double step;
    private final List<T> choices;

    SettingsOption(
            String id,
            CyclesClientConfig.Category category,
            String translationKey,
            CyclesClientConfig.ValueKind kind,
            Supplier<T> reader,
            Consumer<T> writer,
            UnaryOperator<T> normalizer,
            double minimum,
            double maximum,
            double step,
            List<T> choices) {
        this.id = id;
        this.category = category;
        this.translationKey = translationKey;
        this.kind = kind;
        this.reader = reader;
        this.writer = writer;
        this.normalizer = normalizer;
        this.minimum = minimum;
        this.maximum = maximum;
        this.step = step;
        this.choices = List.copyOf(choices);
    }

    public String id() {
        return id;
    }

    public CyclesClientConfig.Category category() {
        return category;
    }

    public String translationKey() {
        return translationKey;
    }

    public CyclesClientConfig.ValueKind kind() {
        return kind;
    }

    public double minimum() {
        return minimum;
    }

    public double maximum() {
        return maximum;
    }

    public double step() {
        return step;
    }

    public List<T> choices() {
        return choices;
    }

    T read() {
        return reader.get();
    }

    T normalize(T candidate) {
        return normalizer.apply(Objects.requireNonNull(candidate));
    }

    @SuppressWarnings("unchecked")
    void writeUnchecked(Object candidate) {
        writer.accept(normalize((T) candidate));
    }
}

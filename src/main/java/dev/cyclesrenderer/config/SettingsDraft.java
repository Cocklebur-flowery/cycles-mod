package dev.cyclesrenderer.config;

import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Temporary editor values that remain separate from persisted NeoForge configuration. */
public final class SettingsDraft {
    private final List<CyclesClientConfig.ConfigOption<?>> options;
    private final Map<CyclesClientConfig.ConfigOption<?>, Object> baseline =
            new IdentityHashMap<>();
    private final Map<CyclesClientConfig.ConfigOption<?>, Object> values =
            new IdentityHashMap<>();
    private final Set<CyclesClientConfig.ConfigOption<?>> dirty = new LinkedHashSet<>();

    SettingsDraft(List<CyclesClientConfig.ConfigOption<?>> options) {
        this.options = List.copyOf(options);
        reload();
    }

    @SuppressWarnings("unchecked")
    public <T> T get(CyclesClientConfig.ConfigOption<T> option) {
        return (T) values.get(option);
    }

    public <T> void set(CyclesClientConfig.ConfigOption<T> option, T value) {
        T normalized = option.normalize(value);
        values.put(option, normalized);
        if (Objects.equals(baseline.get(option), normalized)) {
            dirty.remove(option);
        } else {
            dirty.add(option);
        }
    }

    public boolean isDirty() {
        return !dirty.isEmpty();
    }

    public boolean isDirty(CyclesClientConfig.ConfigOption<?> option) {
        return dirty.contains(option);
    }

    public void discard() {
        reload();
    }

    List<Change> changes() {
        return dirty.stream()
                .map(option -> new Change(option, values.get(option)))
                .toList();
    }

    void accept(List<Change> changes) {
        for (Change change : changes) {
            baseline.put(change.option(), change.value());
            dirty.remove(change.option());
        }
    }

    private void reload() {
        baseline.clear();
        values.clear();
        dirty.clear();
        for (CyclesClientConfig.ConfigOption<?> option : options) {
            Object value = option.read();
            baseline.put(option, value);
            values.put(option, value);
        }
    }

    record Change(CyclesClientConfig.ConfigOption<?> option, Object value) {
    }
}

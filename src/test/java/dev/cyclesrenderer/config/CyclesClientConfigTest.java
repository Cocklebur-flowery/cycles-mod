package dev.cyclesrenderer.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CyclesClientConfigTest {
    private static final String EXPECTED_CATALOG_SHA256 =
            "ae522977c363d2d12d9b36d9ab4f452f91893606d677179f6482399b78f0e362";

    @Test
    void optionCatalogKeepsStableOrderAndMetadata() {
        List<CyclesClientConfig.ConfigOption<?>> options = CyclesClientConfig.options();
        Set<String> ids = new HashSet<>();

        assertFalse(options.isEmpty());
        for (CyclesClientConfig.ConfigOption<?> option : options) {
            assertFalse(option.id().isBlank());
            assertTrue(ids.add(option.id()), () -> "duplicate option id: " + option.id());
            assertTrue(option.translationKey().startsWith("config.cyclesrenderer."),
                    () -> "unexpected translation key: " + option.translationKey());
            assertTrue(option.minimum() <= option.maximum(), () -> "invalid range: " + option.id());
            assertTrue(option.step() > 0.0D, () -> "invalid step: " + option.id());

            if (option.kind() == CyclesClientConfig.ValueKind.ENUM) {
                assertFalse(option.choices().isEmpty(), () -> "enum without choices: " + option.id());
                assertEquals(option.choices().size() - 1.0D, option.maximum(),
                        () -> "enum maximum does not match choices: " + option.id());
            } else {
                assertTrue(option.choices().isEmpty(), () -> "non-enum with choices: " + option.id());
            }
        }

        String actualFingerprint = catalogFingerprint(options);
        assertEquals(EXPECTED_CATALOG_SHA256, actualFingerprint,
                "configuration option catalog changed: " + actualFingerprint);
        assertThrows(UnsupportedOperationException.class, options::clear);
    }

    @Test
    void draftNormalizesTracksDiscardsAndAcceptsEdits() {
        AtomicInteger stored = new AtomicInteger(480);
        CyclesClientConfig.ConfigOption<Integer> width = new CyclesClientConfig.ConfigOption<>(
                "output.width",
                CyclesClientConfig.Category.OUTPUT,
                "config.cyclesrenderer.output.width",
                CyclesClientConfig.ValueKind.INTEGER,
                stored::get,
                stored::set,
                value -> Math.clamp(value, 160, 3840),
                160.0D,
                3840.0D,
                1.0D,
                List.of());
        SettingsDraft draft = new SettingsDraft(List.of(width));

        assertFalse(draft.isDirty());
        draft.set(width, Integer.MAX_VALUE);
        assertEquals(3840, draft.get(width));
        assertTrue(draft.isDirty());
        assertTrue(draft.isDirty(width));

        draft.set(width, stored.get());
        assertFalse(draft.isDirty());

        draft.set(width, Integer.MIN_VALUE);
        assertEquals(160, draft.get(width));
        List<SettingsDraft.Change> changes = draft.changes();
        assertEquals(1, changes.size());
        assertEquals(width, changes.getFirst().option());
        assertEquals(160, changes.getFirst().value());

        draft.accept(changes);
        assertFalse(draft.isDirty());
        assertEquals(160, draft.get(width));

        stored.set(720);
        draft.discard();
        assertEquals(720, draft.get(width));
        assertFalse(draft.isDirty());
    }

    private static String catalogFingerprint(List<CyclesClientConfig.ConfigOption<?>> options) {
        String canonical = options.stream()
                .map(option -> String.join("|",
                        option.id(),
                        option.category().name(),
                        option.translationKey(),
                        option.kind().name(),
                        Double.toHexString(option.minimum()),
                        Double.toHexString(option.maximum()),
                        Double.toHexString(option.step()),
                        option.choices().stream()
                                .map(CyclesClientConfigTest::choiceName)
                                .collect(Collectors.joining(","))))
                .collect(Collectors.joining("\n", "", "\n"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 unavailable", exception);
        }
    }

    private static String choiceName(Object choice) {
        return choice instanceof Enum<?> enumChoice ? enumChoice.name() : String.valueOf(choice);
    }
}

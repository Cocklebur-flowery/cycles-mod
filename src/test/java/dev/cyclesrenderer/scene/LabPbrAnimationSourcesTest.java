package dev.cyclesrenderer.scene;

import dev.cyclesrenderer.nativebridge.TextureRegionUpdate;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class LabPbrAnimationSourcesTest {
    private static final Identifier FIRST =
            Identifier.fromNamespaceAndPath("cyclesrenderer", "first");
    private static final Identifier SECOND =
            Identifier.fromNamespaceAndPath("cyclesrenderer", "second");

    @Test
    void resolvesOnlyCurrentGenerationAndKnownSprites() {
        LabPbrAnimationSources sources = sources();
        List<LabPbrAnimationSources.Task> tasks = sources.resolve(List.of(
                update(FIRST, 4L, 1L, 0),
                update(FIRST, 3L, 2L, 1),
                update(SECOND, 4L, 3L, 1)));

        assertEquals(1, tasks.size());
        assertEquals(FIRST, tasks.getFirst().sprite());
        assertEquals(1L, tasks.getFirst().sequence());
    }

    @Test
    void convertsFourEncodedSlotsToOwnedNativeUpdate() {
        LabPbrAnimationSources.Task task = sources().resolve(List.of(
                update(FIRST, 4L, 7L, 1))).getFirst();

        TextureRegionUpdate update = task.encode();

        assertEquals(4L, update.generation());
        assertEquals(7L, update.revision());
        assertEquals(9, update.spriteIndex());
        assertEquals(16, update.x());
        assertEquals(24, update.y());
        assertEquals(1, update.width());
        assertEquals(1, update.height());
        assertEquals(4, update.rowStride());
        assertArrayEquals(bytes(0x40, 0x50, 0x60, 0x80), update.colorPixels());
        assertArrayEquals(bytes(0xBF, 0x80, 0xDD, 0xAA), update.normalPixels());
        assertArrayEquals(bytes(0xBF, 0x00, 0xE5, 0xFF), update.materialPixels());
        assertArrayEquals(bytes(0x20, 0xAA, 0xE5, 0x40), update.auxiliaryPixels());
    }

    @Test
    void coalescesLatestSpriteStateAndOrdersBySequence() {
        LabPbrAnimationSources.Source first = source(FIRST, 9);
        LabPbrAnimationSources.Source second = source(SECOND, 3);
        LabPbrAnimationSources.PendingTasks pending =
                new LabPbrAnimationSources.PendingTasks();
        pending.merge(List.of(
                new LabPbrAnimationSources.Task(first, update(FIRST, 4L, 4L, 0)),
                new LabPbrAnimationSources.Task(second, update(SECOND, 4L, 2L, 0))));
        pending.merge(List.of(
                new LabPbrAnimationSources.Task(first, update(FIRST, 4L, 6L, 1)),
                new LabPbrAnimationSources.Task(first, update(FIRST, 4L, 5L, 0))));

        List<LabPbrAnimationSources.Task> ordered = pending.ordered();

        assertEquals(List.of(SECOND, FIRST), ordered.stream()
                .map(LabPbrAnimationSources.Task::sprite)
                .toList());
        assertEquals(List.of(2L, 6L), ordered.stream()
                .map(LabPbrAnimationSources.Task::sequence)
                .toList());
    }

    @Test
    void rejectsTaskWhoseSourceDoesNotMatchUpdate() {
        assertThrows(IllegalArgumentException.class, () ->
                new LabPbrAnimationSources.Task(
                        source(FIRST, 9), update(SECOND, 4L, 1L, 0)));
    }

    private static LabPbrAnimationSources sources() {
        return new LabPbrAnimationSources(4L, Map.of(FIRST, source(FIRST, 9)));
    }

    private static LabPbrAnimationSources.Source source(Identifier sprite, int index) {
        return new LabPbrAnimationSources.Source(
                index,
                new LabPbrAnimationRegionEncoder.SpriteSource(
                        sprite,
                        4L,
                        64,
                        64,
                        16,
                        24,
                        1,
                        1,
                        frames(0xFF010203, 0x80405060),
                        frames(0xFF8080FF, 0xAABF8020),
                        frames(0x0000E540, 0xFE40E540),
                        LabPbrAtlasBuilder.DEFAULT_ROUGHNESS,
                        LabPbrAtlasBuilder.DEFAULT_DIELECTRIC_F0));
    }

    private static LabPbrAnimationRegionEncoder.FramePixels frames(int first, int second) {
        return new LabPbrAnimationRegionEncoder.FramePixels(
                2, 1, 1, 1, new int[] {first, second});
    }

    private static LabPbrAnimationFrames.FrameUpdate update(
            Identifier sprite,
            long generation,
            long sequence,
            int frame) {
        return new LabPbrAnimationFrames.FrameUpdate(
                sprite, generation, frame, frame, false, 0, sequence);
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }
}

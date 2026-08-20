package dev.cyclesrenderer.scene;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LabPbrAnimationFramesTest {
    private static final Identifier FIRST =
            Identifier.fromNamespaceAndPath("cyclesrenderer", "first");
    private static final Identifier SECOND =
            Identifier.fromNamespaceAndPath("cyclesrenderer", "second");

    @Test
    void coalescesEquivalentDiscreteStates() {
        LabPbrAnimationFrames.Tracker tracker = new LabPbrAnimationFrames.Tracker();
        Object identity = new Object();

        assertTrue(tracker.record(0, FIRST, identity, 2, 9, false, 731));
        assertFalse(tracker.record(0, FIRST, identity, 2, 5, false, 999));

        LabPbrAnimationFrames.FrameUpdate update = only(tracker.pendingUpdates());
        assertEquals(2, update.currentImageFrame());
        assertEquals(2, update.nextImageFrame());
        assertEquals(0, update.progress());
        assertFalse(update.interpolated());
    }

    @Test
    void keepsOnlyLatestInterpolatedStatePerSprite() {
        LabPbrAnimationFrames.Tracker tracker = new LabPbrAnimationFrames.Tracker();
        Object identity = new Object();

        assertTrue(tracker.record(0, FIRST, identity, 3, 7, true, 125));
        assertTrue(tracker.record(0, FIRST, identity, 3, 7, true, 500));
        assertFalse(tracker.record(0, FIRST, identity, 3, 7, true, 500));

        LabPbrAnimationFrames.FrameUpdate update = only(tracker.pendingUpdates());
        assertEquals(3, update.currentImageFrame());
        assertEquals(7, update.nextImageFrame());
        assertEquals(500, update.progress());
        assertTrue(update.interpolated());
        assertEquals(2, update.sequence());
    }

    @Test
    void ordersDifferentSpritesByTheirLatestSequence() {
        LabPbrAnimationFrames.Tracker tracker = new LabPbrAnimationFrames.Tracker();
        Object firstIdentity = new Object();
        Object secondIdentity = new Object();
        Object replacementIdentity = new Object();

        tracker.record(0, FIRST, firstIdentity, 1, 1, false, 0);
        tracker.record(0, SECOND, secondIdentity, 2, 2, false, 0);
        tracker.record(0, FIRST, replacementIdentity, 3, 3, false, 0);

        List<LabPbrAnimationFrames.FrameUpdate> updates = tracker.pendingUpdates();
        assertEquals(List.of(SECOND, FIRST), updates.stream()
                .map(LabPbrAnimationFrames.FrameUpdate::sprite)
                .toList());
    }

    @Test
    void rejectsStaleGenerationAndClearsPendingStateAtBarrier() {
        LabPbrAnimationFrames.Tracker tracker = new LabPbrAnimationFrames.Tracker();
        Object identity = new Object();
        tracker.record(0, FIRST, identity, 1, 1, false, 0);

        long generation = tracker.beginGeneration();

        assertEquals(1, generation);
        assertTrue(tracker.pendingUpdates().isEmpty());
        assertFalse(tracker.record(0, FIRST, identity, 2, 2, false, 0));
        assertTrue(tracker.record(generation, FIRST, identity, 3, 3, false, 0));
    }

    @Test
    void replacesSameIdentifierWhenContentsIdentityChanges() {
        LabPbrAnimationFrames.Tracker tracker = new LabPbrAnimationFrames.Tracker();
        Object oldIdentity = new Object();
        Object newIdentity = new Object();

        tracker.record(0, FIRST, oldIdentity, 4, 4, false, 0);
        assertEquals(4, tracker.currentImageFrame(FIRST, oldIdentity));
        assertEquals(0, tracker.currentImageFrame(FIRST, newIdentity));

        assertTrue(tracker.record(0, FIRST, newIdentity, 6, 6, false, 0));
        assertEquals(6, tracker.currentImageFrame(FIRST, newIdentity));
        assertEquals(0, tracker.currentImageFrame(FIRST, oldIdentity));
        assertEquals(6, only(tracker.pendingUpdates()).currentImageFrame());
    }

    @Test
    void oldAcknowledgeCannotDeleteNewerState() {
        LabPbrAnimationFrames.Tracker tracker = new LabPbrAnimationFrames.Tracker();
        Object identity = new Object();
        tracker.record(0, FIRST, identity, 1, 2, true, 100);
        List<LabPbrAnimationFrames.FrameUpdate> oldSnapshot = tracker.pendingUpdates();

        tracker.record(0, FIRST, identity, 1, 2, true, 200);
        tracker.acknowledge(oldSnapshot);

        LabPbrAnimationFrames.FrameUpdate update = only(tracker.pendingUpdates());
        assertEquals(200, update.progress());
    }

    @Test
    void rejectsInvalidFramesAndInterpolatedProgress() {
        LabPbrAnimationFrames.Tracker tracker = new LabPbrAnimationFrames.Tracker();
        Object identity = new Object();

        assertFalse(tracker.record(0, FIRST, identity, -1, 0, false, 0));
        assertFalse(tracker.record(0, FIRST, identity, 0, -1, true, 0));
        assertFalse(tracker.record(0, FIRST, identity, 0, 1, true, -1));
        assertFalse(tracker.record(0, FIRST, identity, 0, 1, true, 1000));
        assertTrue(tracker.pendingUpdates().isEmpty());
    }

    private static LabPbrAnimationFrames.FrameUpdate only(
            List<LabPbrAnimationFrames.FrameUpdate> updates) {
        assertEquals(1, updates.size());
        return updates.getFirst();
    }
}

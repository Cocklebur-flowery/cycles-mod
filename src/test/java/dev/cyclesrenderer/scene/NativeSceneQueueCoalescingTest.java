package dev.cyclesrenderer.scene;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

final class NativeSceneQueueCoalescingTest {
    @Test
    void animationDoesNotDiscardPendingSectionCoalescing() {
        NativeSceneUploadQueue.CoalescingState<Object, Object> state =
                new NativeSceneUploadQueue.CoalescingState<>();
        Object mutation = new Object();
        Object animation = new Object();

        state.trackMutation(17L, mutation);
        state.trackAnimation(animation);

        assertSame(mutation, state.mutation(17L));
        assertSame(animation, state.animation());
    }

    @Test
    void newSectionCommandSeparatesAnimationBatches() {
        NativeSceneUploadQueue.CoalescingState<Object, Object> state =
                new NativeSceneUploadQueue.CoalescingState<>();
        Object animation = new Object();

        state.trackAnimation(animation);
        state.trackMutation(23L, new Object());

        assertNull(state.animation());
    }

    @Test
    void explicitCommitClearsBothCoalescingDomains() {
        NativeSceneUploadQueue.CoalescingState<Object, Object> state =
                new NativeSceneUploadQueue.CoalescingState<>();

        state.trackMutation(31L, new Object());
        state.trackAnimation(new Object());
        state.commitBarrier();

        assertNull(state.mutation(31L));
        assertNull(state.animation());
    }

    @Test
    void completedOlderCommandsDoNotEraseNewerDomains() {
        NativeSceneUploadQueue.CoalescingState<Object, Object> state =
                new NativeSceneUploadQueue.CoalescingState<>();
        Object oldMutation = new Object();
        Object newMutation = new Object();
        Object oldAnimation = new Object();
        Object newAnimation = new Object();

        state.trackMutation(47L, oldMutation);
        state.commitBarrier();
        state.trackMutation(47L, newMutation);
        state.trackAnimation(oldAnimation);
        state.trackMutation(53L, new Object());
        state.trackAnimation(newAnimation);

        state.untrackMutation(47L, oldMutation);
        state.untrackAnimation(oldAnimation);

        assertSame(newMutation, state.mutation(47L));
        assertSame(newAnimation, state.animation());
    }
}

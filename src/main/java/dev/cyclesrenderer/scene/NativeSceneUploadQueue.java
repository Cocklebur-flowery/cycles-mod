package dev.cyclesrenderer.scene;

import dev.cyclesrenderer.nativebridge.NativeBridge;
import dev.cyclesrenderer.nativebridge.NativeTextureRegions;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Serializes native scene mutations away from Minecraft's render thread. */
final class NativeSceneUploadQueue implements AutoCloseable {
    private static final int MAX_QUEUED_COMMANDS = 64;

    private final Object lock = new Object();
    private final ArrayDeque<Command> commands = new ArrayDeque<>();
    private final CoalescingState<MutationCommand, AnimationCommand> coalescing =
            new CoalescingState<>();
    private final Thread worker;

    private boolean running = true;
    private boolean busy;
    private RuntimeException failure;
    private long lastUpsertMicros;
    private long emaUpsertMicros;
    private long maxUpsertMicros;
    private long lastRemoveMicros;
    private long emaRemoveMicros;
    private long maxRemoveMicros;
    private long lastCommitMicros;
    private long emaCommitMicros;
    private long maxCommitMicros;

    NativeSceneUploadQueue() {
        worker = new Thread(this::runWorker, "Cycles scene upload");
        worker.setDaemon(true);
        worker.start();
    }

    boolean enqueueUpsert(SectionGeometrySnapshot snapshot) {
        return enqueueMutation(snapshot.sectionNode(), snapshot);
    }

    boolean enqueueRemove(long sectionNode) {
        return enqueueMutation(sectionNode, null);
    }

    boolean enqueueAnimations(List<LabPbrAnimationSources.Task> tasks) {
        if (tasks.isEmpty()) {
            return true;
        }
        synchronized (lock) {
            throwFailureLocked();
            if (!running) {
                return false;
            }
            AnimationCommand existing = coalescing.animation();
            if (existing != null) {
                existing.merge(tasks);
                return true;
            }
            if (commands.size() >= MAX_QUEUED_COMMANDS) {
                return false;
            }
            AnimationCommand command = new AnimationCommand(tasks);
            commands.addLast(command);
            coalescing.trackAnimation(command);
            lock.notifyAll();
            return true;
        }
    }

    boolean enqueueCommit() {
        synchronized (lock) {
            throwFailureLocked();
            if (!running || commands.size() >= MAX_QUEUED_COMMANDS) {
                return false;
            }
            commands.addLast(CommitCommand.INSTANCE);
            coalescing.commitBarrier();
            lock.notifyAll();
            return true;
        }
    }

    void resetNativeScene(SectionGeometrySnapshot.SceneResources resources) {
        clearAndAwaitIdle(false);
        NativeBridge.resetScene(resources);
    }

    void reset() {
        clearAndAwaitIdle(true);
    }

    void throwIfFailed() {
        synchronized (lock) {
            throwFailureLocked();
        }
    }

    Metrics metrics() {
        synchronized (lock) {
            return new Metrics(
                    lastUpsertMicros,
                    emaUpsertMicros,
                    maxUpsertMicros,
                    lastRemoveMicros,
                    emaRemoveMicros,
                    maxRemoveMicros,
                    lastCommitMicros,
                    emaCommitMicros,
                    maxCommitMicros);
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            commands.clear();
            coalescing.clear();
            failure = null;
            running = false;
            lock.notifyAll();
            awaitIdleLocked();
        }
        try {
            worker.join();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while closing native scene upload queue", error);
        }
    }

    private boolean enqueueMutation(long sectionNode, SectionGeometrySnapshot snapshot) {
        synchronized (lock) {
            throwFailureLocked();
            if (!running) {
                return false;
            }
            MutationCommand existing = coalescing.mutation(sectionNode);
            if (existing != null) {
                existing.snapshot = snapshot;
                return true;
            }
            if (commands.size() >= MAX_QUEUED_COMMANDS - 1) {
                return false;
            }
            MutationCommand command = new MutationCommand(sectionNode, snapshot);
            commands.addLast(command);
            coalescing.trackMutation(sectionNode, command);
            lock.notifyAll();
            return true;
        }
    }

    private void runWorker() {
        while (true) {
            Command command;
            synchronized (lock) {
                while (running && commands.isEmpty()) {
                    try {
                        lock.wait();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        failure = new IllegalStateException(
                                "native scene upload worker was interrupted", error);
                        running = false;
                        lock.notifyAll();
                        return;
                    }
                }
                if (!running) {
                    return;
                }
                command = commands.removeFirst();
                if (command instanceof MutationCommand mutation) {
                    coalescing.untrackMutation(mutation.sectionNode, mutation);
                } else if (command instanceof AnimationCommand animation) {
                    coalescing.untrackAnimation(animation);
                }
                busy = true;
            }

            long start = System.nanoTime();
            try {
                command.execute();
                record(command, nanosToMicros(System.nanoTime() - start));
            } catch (RuntimeException error) {
                synchronized (lock) {
                    failure = error;
                    commands.clear();
                    coalescing.clear();
                }
            } finally {
                synchronized (lock) {
                    busy = false;
                    lock.notifyAll();
                }
            }
        }
    }

    private void record(Command command, long micros) {
        synchronized (lock) {
            if (command instanceof MutationCommand mutation) {
                if (mutation.snapshot != null) {
                    lastUpsertMicros = micros;
                    emaUpsertMicros = updateEma(emaUpsertMicros, micros);
                    maxUpsertMicros = Math.max(maxUpsertMicros, micros);
                } else {
                    lastRemoveMicros = micros;
                    emaRemoveMicros = updateEma(emaRemoveMicros, micros);
                    maxRemoveMicros = Math.max(maxRemoveMicros, micros);
                }
            } else {
                lastCommitMicros = micros;
                emaCommitMicros = updateEma(emaCommitMicros, micros);
                maxCommitMicros = Math.max(maxCommitMicros, micros);
            }
        }
    }

    private void clearAndAwaitIdle(boolean resetMetrics) {
        synchronized (lock) {
            commands.clear();
            coalescing.clear();
            lock.notifyAll();
            awaitIdleLocked();
            failure = null;
            if (resetMetrics) {
                lastUpsertMicros = 0L;
                emaUpsertMicros = 0L;
                maxUpsertMicros = 0L;
                lastRemoveMicros = 0L;
                emaRemoveMicros = 0L;
                maxRemoveMicros = 0L;
                lastCommitMicros = 0L;
                emaCommitMicros = 0L;
                maxCommitMicros = 0L;
            }
        }
    }

    private void awaitIdleLocked() {
        while (busy) {
            try {
                lock.wait();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "interrupted while waiting for native scene upload", error);
            }
        }
    }

    private void throwFailureLocked() {
        if (failure != null) {
            throw new IllegalStateException("asynchronous native scene upload failed", failure);
        }
    }

    private static long nanosToMicros(long nanos) {
        return Math.max(0L, (nanos + 999L) / 1_000L);
    }

    private static long updateEma(long previous, long value) {
        return previous == 0L ? value : (previous * 7L + value) / 8L;
    }

    static final class CoalescingState<M, A> {
        private final Map<Long, M> mutations = new HashMap<>();
        private A animation;

        M mutation(long sectionNode) {
            return mutations.get(sectionNode);
        }

        A animation() {
            return animation;
        }

        void trackMutation(long sectionNode, M command) {
            mutations.put(sectionNode, command);
            animation = null;
        }

        void trackAnimation(A command) {
            animation = command;
        }

        void untrackMutation(long sectionNode, M command) {
            mutations.remove(sectionNode, command);
        }

        void untrackAnimation(A command) {
            if (animation == command) {
                animation = null;
            }
        }

        void commitBarrier() {
            mutations.clear();
            animation = null;
        }

        void clear() {
            commitBarrier();
        }
    }

    record Metrics(
            long lastUpsertMicros,
            long emaUpsertMicros,
            long maxUpsertMicros,
            long lastRemoveMicros,
            long emaRemoveMicros,
            long maxRemoveMicros,
            long lastCommitMicros,
            long emaCommitMicros,
            long maxCommitMicros) {
    }

    private interface Command {
        void execute();
    }

    private static final class MutationCommand implements Command {
        private final long sectionNode;
        private SectionGeometrySnapshot snapshot;

        private MutationCommand(long sectionNode, SectionGeometrySnapshot snapshot) {
            this.sectionNode = sectionNode;
            this.snapshot = snapshot;
        }

        @Override
        public void execute() {
            if (snapshot != null) {
                NativeBridge.upsertSection(snapshot);
            } else {
                NativeBridge.removeSection(sectionNode);
            }
        }
    }

    private static final class AnimationCommand implements Command {
        private final LabPbrAnimationSources.PendingTasks pending =
                new LabPbrAnimationSources.PendingTasks();

        private AnimationCommand(List<LabPbrAnimationSources.Task> tasks) {
            merge(tasks);
        }

        private void merge(List<LabPbrAnimationSources.Task> tasks) {
            pending.merge(tasks);
        }

        @Override
        public void execute() {
            for (LabPbrAnimationSources.Task task : pending.ordered()) {
                NativeTextureRegions.stage(task.encode());
            }
            NativeBridge.commitScene();
        }
    }

    private enum CommitCommand implements Command {
        INSTANCE;

        @Override
        public void execute() {
            NativeBridge.commitScene();
        }
    }
}

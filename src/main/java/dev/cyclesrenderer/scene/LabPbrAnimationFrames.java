package dev.cyclesrenderer.scene;

import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Tracks the exact image frame selected by Minecraft's atlas animation state. */
public final class LabPbrAnimationFrames {
    private static final Tracker TRACKER = new Tracker();

    private LabPbrAnimationFrames() {
    }

    public static void record(
            SpriteContents contents,
            int currentImageFrame,
            int nextImageFrame,
            boolean interpolated,
            int progress) {
        TRACKER.recordCurrentGeneration(
                contents.name(), contents,
                currentImageFrame, nextImageFrame, interpolated, progress);
    }

    public static int currentImageFrame(TextureAtlasSprite sprite) {
        SpriteContents contents = sprite.contents();
        return TRACKER.currentImageFrame(contents.name(), contents);
    }

    static long beginGeneration() {
        return TRACKER.beginGeneration();
    }

    static List<FrameUpdate> pendingUpdates() {
        return TRACKER.pendingUpdates();
    }

    static void acknowledge(Collection<FrameUpdate> updates) {
        TRACKER.acknowledge(updates);
    }

    record FrameUpdate(
            Identifier sprite,
            long generation,
            int currentImageFrame,
            int nextImageFrame,
            boolean interpolated,
            int progress,
            long sequence) {
        private boolean equivalentTo(
                int candidateCurrentFrame,
                int candidateNextFrame,
                boolean candidateInterpolated,
                int candidateProgress) {
            return currentImageFrame == candidateCurrentFrame
                    && nextImageFrame == candidateNextFrame
                    && interpolated == candidateInterpolated
                    && progress == candidateProgress;
        }
    }

    static final class Tracker {
        private final Map<Identifier, FrameState> current = new HashMap<>();
        private final Map<Identifier, FrameState> dirty = new HashMap<>();
        private long generation;
        private long sequence;

        synchronized long beginGeneration() {
            generation++;
            current.clear();
            dirty.clear();
            return generation;
        }

        synchronized boolean recordCurrentGeneration(
                Identifier sprite,
                Object contentsIdentity,
                int currentImageFrame,
                int nextImageFrame,
                boolean interpolated,
                int progress) {
            return record(
                    generation, sprite, contentsIdentity,
                    currentImageFrame, nextImageFrame, interpolated, progress);
        }

        synchronized boolean record(
                long eventGeneration,
                Identifier sprite,
                Object contentsIdentity,
                int currentImageFrame,
                int nextImageFrame,
                boolean interpolated,
                int progress) {
            if (eventGeneration != generation || sprite == null || contentsIdentity == null
                    || currentImageFrame < 0 || nextImageFrame < 0
                    || (interpolated && (progress < 0 || progress > 999))) {
                return false;
            }
            int effectiveNextFrame = interpolated ? nextImageFrame : currentImageFrame;
            int effectiveProgress = interpolated ? progress : 0;
            FrameState previous = current.get(sprite);
            if (previous != null
                    && previous.contentsIdentity().get() == contentsIdentity
                    && previous.update().equivalentTo(
                            currentImageFrame, effectiveNextFrame, interpolated, effectiveProgress)) {
                return false;
            }
            FrameUpdate update = new FrameUpdate(
                    sprite, generation, currentImageFrame, effectiveNextFrame,
                    interpolated, effectiveProgress, ++sequence);
            FrameState state = new FrameState(new WeakReference<>(contentsIdentity), update);
            current.put(sprite, state);
            dirty.put(sprite, state);
            return true;
        }

        synchronized int currentImageFrame(Identifier sprite, Object contentsIdentity) {
            FrameState state = current.get(sprite);
            if (state == null) {
                return 0;
            }
            Object recordedIdentity = state.contentsIdentity().get();
            if (recordedIdentity == null) {
                current.remove(sprite, state);
                dirty.remove(sprite, state);
                return 0;
            }
            if (recordedIdentity != contentsIdentity) {
                return 0;
            }
            return state.update().currentImageFrame();
        }

        synchronized List<FrameUpdate> pendingUpdates() {
            List<FrameUpdate> updates = new ArrayList<>(dirty.size());
            dirty.entrySet().removeIf(entry -> {
                FrameState state = entry.getValue();
                if (state.contentsIdentity().get() == null || state.update().generation() != generation) {
                    current.remove(entry.getKey(), state);
                    return true;
                }
                updates.add(state.update());
                return false;
            });
            updates.sort(Comparator.comparingLong(FrameUpdate::sequence));
            return List.copyOf(updates);
        }

        synchronized void acknowledge(Collection<FrameUpdate> updates) {
            for (FrameUpdate update : updates) {
                FrameState state = dirty.get(update.sprite());
                if (state != null
                        && state.update().generation() == update.generation()
                        && state.update().sequence() == update.sequence()) {
                    dirty.remove(update.sprite(), state);
                }
            }
        }
    }

    private record FrameState(WeakReference<Object> contentsIdentity, FrameUpdate update) {
    }
}

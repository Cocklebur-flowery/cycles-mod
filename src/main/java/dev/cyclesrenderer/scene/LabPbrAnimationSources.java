package dev.cyclesrenderer.scene;

import dev.cyclesrenderer.nativebridge.TextureRegionUpdate;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Owns the immutable animation inputs associated with one native resource generation. */
final class LabPbrAnimationSources {
    private static final LabPbrAnimationSources EMPTY =
            new LabPbrAnimationSources(0L, Map.of());

    private final long generation;
    private final Map<Identifier, Source> sources;

    LabPbrAnimationSources(long generation, Map<Identifier, Source> sources) {
        this.generation = generation;
        this.sources = Map.copyOf(sources);
    }

    static LabPbrAnimationSources empty() {
        return EMPTY;
    }

    static LabPbrAnimationSources create(
            long generation,
            int atlasWidth,
            int atlasHeight,
            Map<Identifier, TextureAtlasSprite> sprites,
            LabPbrAtlasBuilder.BuildResult pbr,
            float fallbackRoughness,
            float fallbackF0) {
        if (generation <= 0L) {
            throw new IllegalArgumentException("animation generation must be positive");
        }
        if (pbr.atlases().width() != atlasWidth || pbr.atlases().height() != atlasHeight) {
            return new LabPbrAnimationSources(generation, Map.of());
        }

        List<TextureAtlasSprite> animated = sprites.values().stream()
                .filter(TextureAtlasSprite::isAnimated)
                .sorted(Comparator.comparing(sprite -> sprite.contents().name().toString()))
                .toList();
        Map<Identifier, Source> captured = new HashMap<>();
        int spriteIndex = 0;
        for (TextureAtlasSprite sprite : animated) {
            Identifier spriteId = sprite.contents().name();
            int width = sprite.contents().width();
            int height = sprite.contents().height();
            LabPbrAnimationRegionEncoder.SpriteSource encoderSource =
                    new LabPbrAnimationRegionEncoder.SpriteSource(
                            spriteId,
                            generation,
                            atlasWidth,
                            atlasHeight,
                            Math.round(sprite.getU0() * atlasWidth),
                            Math.round(sprite.getV0() * atlasHeight),
                            width,
                            height,
                            captureBaseFrames(sprite),
                            pbr.normalFrames().get(spriteId),
                            pbr.materialFrames().get(spriteId),
                            fallbackRoughness,
                            fallbackF0);
            captured.put(spriteId, new Source(spriteIndex++, encoderSource));
        }
        return new LabPbrAnimationSources(generation, captured);
    }

    List<Task> resolve(List<LabPbrAnimationFrames.FrameUpdate> updates) {
        Objects.requireNonNull(updates, "updates");
        if (sources.isEmpty()) {
            return List.of();
        }
        List<Task> result = new ArrayList<>(updates.size());
        for (LabPbrAnimationFrames.FrameUpdate update : updates) {
            if (update.generation() != generation) {
                continue;
            }
            Source source = sources.get(update.sprite());
            if (source != null) {
                result.add(new Task(source, update));
            }
        }
        return List.copyOf(result);
    }

    private static LabPbrAnimationRegionEncoder.FramePixels captureBaseFrames(
            TextureAtlasSprite sprite) {
        int frameWidth = sprite.contents().width();
        int frameHeight = sprite.contents().height();
        int maxFrame = 0;
        for (int frame : sprite.contents().getUniqueFrames().toIntArray()) {
            maxFrame = Math.max(maxFrame, frame);
        }
        int frameCount = Math.addExact(maxFrame, 1);
        int imageWidth = Math.multiplyExact(frameWidth, frameCount);
        int[] pixels = new int[Math.multiplyExact(imageWidth, frameHeight)];
        for (int frame = 0; frame < frameCount; frame++) {
            int frameOffset = frame * frameWidth;
            for (int y = 0; y < frameHeight; y++) {
                for (int x = 0; x < frameWidth; x++) {
                    pixels[y * imageWidth + frameOffset + x] =
                            sprite.getPixelRGBA(frame, x, y);
                }
            }
        }
        return new LabPbrAnimationRegionEncoder.FramePixels(
                imageWidth, frameHeight, frameWidth, frameHeight, pixels);
    }

    record Task(Source source, LabPbrAnimationFrames.FrameUpdate update) {
        Task {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(update, "update");
            if (!source.encoderSource().sprite().equals(update.sprite())
                    || source.encoderSource().generation() != update.generation()) {
                throw new IllegalArgumentException("animation task source does not match update");
            }
        }

        Identifier sprite() {
            return update.sprite();
        }

        long sequence() {
            return update.sequence();
        }

        TextureRegionUpdate encode() {
            LabPbrAnimationRegionEncoder.RegionBatch batch =
                    LabPbrAnimationRegionEncoder.encode(source.encoderSource(), update);
            List<LabPbrAnimationRegionEncoder.Region> regions = batch.regions();
            LabPbrAnimationRegionEncoder.Region first = regions.getFirst();
            return new TextureRegionUpdate(
                    batch.generation(),
                    batch.revision(),
                    source.spriteIndex(),
                    first.x(),
                    first.y(),
                    first.width(),
                    first.height(),
                    first.rowStride(),
                    regions.get(0).pixels(),
                    regions.get(1).pixels(),
                    regions.get(2).pixels(),
                    regions.get(3).pixels());
        }
    }

    record Source(int spriteIndex, LabPbrAnimationRegionEncoder.SpriteSource encoderSource) {
        Source {
            Objects.requireNonNull(encoderSource, "encoderSource");
            if (spriteIndex < 0) {
                throw new IllegalArgumentException("sprite index must be non-negative");
            }
        }
    }

    static final class PendingTasks {
        private final Map<Identifier, Task> latest = new HashMap<>();

        void merge(List<Task> tasks) {
            for (Task task : tasks) {
                latest.merge(
                        task.sprite(),
                        task,
                        (previous, candidate) -> candidate.sequence() > previous.sequence()
                                ? candidate
                                : previous);
            }
        }

        List<Task> ordered() {
            return latest.values().stream()
                    .sorted(Comparator.comparingLong(Task::sequence))
                    .toList();
        }
    }
}

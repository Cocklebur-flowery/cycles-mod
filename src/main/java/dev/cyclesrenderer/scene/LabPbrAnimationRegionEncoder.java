package dev.cyclesrenderer.scene;

import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;

import java.util.List;
import java.util.Objects;

/** Encodes one coalesced Minecraft animation state into four owned RGBA8 regions. */
final class LabPbrAnimationRegionEncoder {
    static final int COLOR_TEXTURE_INDEX = 0;
    static final int NORMAL_TEXTURE_INDEX = 1;
    static final int MATERIAL_TEXTURE_INDEX = 2;
    static final int AUXILIARY_TEXTURE_INDEX = 3;

    private LabPbrAnimationRegionEncoder() {
    }

    static RegionBatch encode(
            SpriteSource source,
            LabPbrAnimationFrames.FrameUpdate update) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(update, "update");
        if (!source.sprite().equals(update.sprite())) {
            throw new IllegalArgumentException("animation sprite does not match source");
        }
        if (source.generation() != update.generation()) {
            throw new IllegalArgumentException("animation generation does not match source");
        }
        if (update.sequence() <= 0 || update.currentImageFrame() < 0
                || update.nextImageFrame() < 0
                || (update.interpolated() && (update.progress() < 0 || update.progress() > 999))) {
            throw new IllegalArgumentException("invalid animation state");
        }

        int rowStride = Math.multiplyExact(source.width(), 4);
        int byteCount = Math.multiplyExact(rowStride, source.height());
        byte[] color = new byte[byteCount];
        byte[] normal = new byte[byteCount];
        byte[] material = new byte[byteCount];
        byte[] auxiliary = new byte[byteCount];
        LabPbrAtlasBuilder.fillDefaults(
                normal, material, auxiliary,
                source.fallbackRoughness(), source.fallbackF0());

        for (int y = 0; y < source.height(); y++) {
            for (int x = 0; x < source.width(); x++) {
                int output = (y * source.width() + x) * 4;
                writeColor(sample(source.base(), update, x, y), color, output);
                if (source.normal() != null) {
                    LabPbrAtlasBuilder.decodeNormalPixel(
                            sample(source.normal(), update, x, y), normal, auxiliary, output);
                }
                if (source.material() != null) {
                    LabPbrAtlasBuilder.decodeMaterialPixel(
                            sample(source.material(), update, x, y),
                            source.fallbackF0(), material, auxiliary, output);
                }
            }
        }

        return new RegionBatch(
                source.sprite(), source.generation(), update.sequence(),
                List.of(
                        region(COLOR_TEXTURE_INDEX, source, rowStride, color),
                        region(NORMAL_TEXTURE_INDEX, source, rowStride, normal),
                        region(MATERIAL_TEXTURE_INDEX, source, rowStride, material),
                        region(AUXILIARY_TEXTURE_INDEX, source, rowStride, auxiliary)));
    }

    static int interpolateArgb(int current, int next, int progress) {
        if (progress < 0 || progress > 999) {
            throw new IllegalArgumentException("animation progress must be in 0..999");
        }
        return ARGB.color(
                interpolateChannel(ARGB.alpha(current), ARGB.alpha(next), progress),
                interpolateChannel(ARGB.red(current), ARGB.red(next), progress),
                interpolateChannel(ARGB.green(current), ARGB.green(next), progress),
                interpolateChannel(ARGB.blue(current), ARGB.blue(next), progress));
    }

    static int interpolateChannel(int current, int next, int progress) {
        if (current < 0 || current > 255 || next < 0 || next > 255
                || progress < 0 || progress > 999) {
            throw new IllegalArgumentException("invalid RGBA8 interpolation input");
        }
        float factor = progress / 1000.0F;
        float currentNormalized = current / 255.0F;
        float nextNormalized = next / 255.0F;
        float mixed = currentNormalized * (1.0F - factor) + nextNormalized * factor;
        return Math.round(Math.clamp(mixed, 0.0F, 1.0F) * 255.0F);
    }

    private static int sample(
            FramePixels pixels,
            LabPbrAnimationFrames.FrameUpdate update,
            int x,
            int y) {
        int current = pixels.argb(update.currentImageFrame(), x, y);
        if (!update.interpolated()) {
            return current;
        }
        int next = pixels.argb(update.nextImageFrame(), x, y);
        return interpolateArgb(current, next, update.progress());
    }

    private static void writeColor(int argb, byte[] target, int output) {
        target[output] = (byte) ARGB.red(argb);
        target[output + 1] = (byte) ARGB.green(argb);
        target[output + 2] = (byte) ARGB.blue(argb);
        target[output + 3] = (byte) ARGB.alpha(argb);
    }

    private static Region region(
            int textureIndex,
            SpriteSource source,
            int rowStride,
            byte[] pixels) {
        return new Region(
                textureIndex,
                source.x(), source.y(), source.width(), source.height(),
                rowStride, pixels);
    }

    record SpriteSource(
            Identifier sprite,
            long generation,
            int atlasWidth,
            int atlasHeight,
            int x,
            int y,
            int width,
            int height,
            FramePixels base,
            FramePixels normal,
            FramePixels material,
            float fallbackRoughness,
            float fallbackF0) {
        SpriteSource {
            Objects.requireNonNull(sprite, "sprite");
            Objects.requireNonNull(base, "base");
            if (generation < 0 || atlasWidth <= 0 || atlasHeight <= 0
                    || x < 0 || y < 0 || width <= 0 || height <= 0
                    || width > atlasWidth || height > atlasHeight
                    || x > atlasWidth - width || y > atlasHeight - height) {
                throw new IllegalArgumentException("invalid atlas region");
            }
            requireFrameSize("base", base, width, height);
            requireFrameSize("normal", normal, width, height);
            requireFrameSize("material", material, width, height);
            if (!Float.isFinite(fallbackRoughness) || fallbackRoughness < 0.0F
                    || fallbackRoughness > 1.0F
                    || !Float.isFinite(fallbackF0) || fallbackF0 < 0.0F || fallbackF0 > 1.0F) {
                throw new IllegalArgumentException("invalid LabPBR fallback values");
            }
        }

        private static void requireFrameSize(
                String role,
                FramePixels pixels,
                int width,
                int height) {
            if (pixels != null
                    && (pixels.frameWidth() != width || pixels.frameHeight() != height)) {
                throw new IllegalArgumentException(role + " frame size does not match sprite");
            }
        }
    }

    static final class FramePixels {
        private final int imageWidth;
        private final int imageHeight;
        private final int frameWidth;
        private final int frameHeight;
        private final int[] argb;

        FramePixels(
                int imageWidth,
                int imageHeight,
                int frameWidth,
                int frameHeight,
                int[] argb) {
            Objects.requireNonNull(argb, "argb");
            if (imageWidth <= 0 || imageHeight <= 0 || frameWidth <= 0 || frameHeight <= 0
                    || imageWidth < frameWidth || imageHeight < frameHeight
                    || imageWidth % frameWidth != 0 || imageHeight % frameHeight != 0
                    || argb.length != Math.multiplyExact(imageWidth, imageHeight)) {
                throw new IllegalArgumentException("invalid animation frame grid");
            }
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
            this.frameWidth = frameWidth;
            this.frameHeight = frameHeight;
            this.argb = argb.clone();
        }

        int frameWidth() {
            return frameWidth;
        }

        int frameHeight() {
            return frameHeight;
        }

        int argb(int imageFrame, int x, int y) {
            if (imageFrame < 0 || x < 0 || y < 0 || x >= frameWidth || y >= frameHeight) {
                throw new IllegalArgumentException("pixel lies outside animation frame");
            }
            int startX = LabPbrAtlasBuilder.frameStartX(
                    imageWidth, imageHeight, frameWidth, frameHeight, imageFrame);
            int startY = LabPbrAtlasBuilder.frameStartY(
                    imageWidth, imageHeight, frameWidth, frameHeight, imageFrame);
            return argb[(startY + y) * imageWidth + startX + x];
        }
    }

    record Region(
            int textureIndex,
            int x,
            int y,
            int width,
            int height,
            int rowStride,
            byte[] pixels) {
        Region {
            Objects.requireNonNull(pixels, "pixels");
            if (textureIndex < COLOR_TEXTURE_INDEX || textureIndex > AUXILIARY_TEXTURE_INDEX
                    || x < 0 || y < 0 || width <= 0 || height <= 0
                    || rowStride != Math.multiplyExact(width, 4)
                    || pixels.length != Math.multiplyExact(rowStride, height)) {
                throw new IllegalArgumentException("invalid RGBA8 texture region");
            }
            pixels = pixels.clone();
        }
    }

    record RegionBatch(
            Identifier sprite,
            long generation,
            long revision,
            List<Region> regions) {
        RegionBatch {
            Objects.requireNonNull(sprite, "sprite");
            regions = List.copyOf(regions);
            if (generation < 0 || revision <= 0 || regions.size() != 4) {
                throw new IllegalArgumentException("invalid animation region batch");
            }
            Region first = regions.getFirst();
            for (int index = 0; index < regions.size(); index++) {
                Region region = regions.get(index);
                if (region.textureIndex() != index) {
                    throw new IllegalArgumentException("animation regions are not in fixed slot order");
                }
                if (region.x() != first.x() || region.y() != first.y()
                        || region.width() != first.width() || region.height() != first.height()
                        || region.rowStride() != first.rowStride()) {
                    throw new IllegalArgumentException("animation regions do not share one rectangle");
                }
            }
        }
    }
}

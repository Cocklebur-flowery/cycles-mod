package dev.cyclesrenderer.nativebridge;

import java.util.Objects;

/** Owns one canonical four-atlas RGBA8 region update. */
public record TextureRegionUpdate(
        long generation,
        long revision,
        int spriteIndex,
        int x,
        int y,
        int width,
        int height,
        int rowStride,
        byte[] colorPixels,
        byte[] normalPixels,
        byte[] materialPixels,
        byte[] auxiliaryPixels) {
    public TextureRegionUpdate {
        colorPixels = copy(colorPixels, "colorPixels");
        normalPixels = copy(normalPixels, "normalPixels");
        materialPixels = copy(materialPixels, "materialPixels");
        auxiliaryPixels = copy(auxiliaryPixels, "auxiliaryPixels");
        if (generation <= 0L || revision <= 0L || spriteIndex < 0
                || x < 0 || y < 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid texture region identity or rectangle");
        }
        int expectedStride = Math.multiplyExact(width, 4);
        int expectedBytes = Math.multiplyExact(expectedStride, height);
        if (rowStride != expectedStride
                || colorPixels.length != expectedBytes
                || normalPixels.length != expectedBytes
                || materialPixels.length != expectedBytes
                || auxiliaryPixels.length != expectedBytes) {
            throw new IllegalArgumentException("invalid canonical RGBA8 region storage");
        }
    }

    @Override
    public byte[] colorPixels() {
        return colorPixels.clone();
    }

    @Override
    public byte[] normalPixels() {
        return normalPixels.clone();
    }

    @Override
    public byte[] materialPixels() {
        return materialPixels.clone();
    }

    @Override
    public byte[] auxiliaryPixels() {
        return auxiliaryPixels.clone();
    }

    private static byte[] copy(byte[] source, String name) {
        return Objects.requireNonNull(source, name).clone();
    }
}

package dev.cyclesrenderer.scene;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.ARGB;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

public final class LabPbrAtlasBuilder {
    public static final float DEFAULT_ROUGHNESS = 0.8F;
    public static final float DEFAULT_DIELECTRIC_F0 = 0.04F;

    private LabPbrAtlasBuilder() {
    }

    public static Atlases build(
            ResourceManager resourceManager,
            Map<Identifier, TextureAtlasSprite> sprites,
            LabPbrResources.Discovery discovery,
            int atlasWidth,
            int atlasHeight,
            float fallbackRoughness,
            float fallbackF0) {
        if (discovery.format() != LabPbrResources.Format.LAB_PBR_1_3) {
            return empty();
        }
        int byteCount = Math.multiplyExact(Math.multiplyExact(atlasWidth, atlasHeight), 4);
        byte[] normalPixels = new byte[byteCount];
        byte[] materialPixels = new byte[byteCount];
        byte[] auxiliaryPixels = new byte[byteCount];
        fillDefaults(normalPixels, materialPixels, auxiliaryPixels,
                fallbackRoughness, fallbackF0);

        int decodedNormals = 0;
        int decodedSpeculars = 0;
        int sizeMismatches = 0;
        int decodeErrors = 0;
        for (TextureAtlasSprite sprite : sprites.values()) {
            LabPbrResources.CompanionSet companionSet =
                    discovery.companions(sprite.contents().name());
            if (companionSet == null) {
                continue;
            }
            int startX = Math.round(sprite.getU0() * atlasWidth);
            int startY = Math.round(sprite.getV0() * atlasHeight);
            int width = sprite.contents().width();
            int height = sprite.contents().height();

            DecodeResult normalResult = decodeCompanion(
                    resourceManager,
                    companionSet.normal(),
                    width,
                    height,
                    image -> copyNormal(image, normalPixels, auxiliaryPixels,
                            atlasWidth, atlasHeight,
                            startX, startY, width, height));
            decodedNormals += normalResult.decoded() ? 1 : 0;
            sizeMismatches += normalResult.sizeMismatch() ? 1 : 0;
            decodeErrors += normalResult.error() ? 1 : 0;

            DecodeResult specularResult = decodeCompanion(
                    resourceManager,
                    companionSet.specular(),
                    width,
                    height,
                    image -> copyMaterial(image, materialPixels, auxiliaryPixels,
                            atlasWidth, atlasHeight,
                            startX, startY, width, height, fallbackF0));
            decodedSpeculars += specularResult.decoded() ? 1 : 0;
            sizeMismatches += specularResult.sizeMismatch() ? 1 : 0;
            decodeErrors += specularResult.error() ? 1 : 0;
        }

        return new Atlases(
                atlasWidth,
                atlasHeight,
                normalPixels,
                materialPixels,
                auxiliaryPixels,
                decodedNormals,
                decodedSpeculars,
                sizeMismatches,
                decodeErrors);
    }

    public static Atlases empty() {
        return new Atlases(0, 0, new byte[0], new byte[0], new byte[0],
                0, 0, 0, 0);
    }

    private static void fillDefaults(
            byte[] normalPixels,
            byte[] materialPixels,
            byte[] auxiliaryPixels,
            float fallbackRoughness,
            float fallbackF0) {
        int roughness = toUnorm8(fallbackRoughness);
        int f0 = toUnorm8(fallbackF0);
        for (int offset = 0; offset < normalPixels.length; offset += 4) {
            normalPixels[offset] = (byte) 128;
            normalPixels[offset + 1] = (byte) 128;
            normalPixels[offset + 2] = (byte) 255;
            normalPixels[offset + 3] = (byte) 255;
            materialPixels[offset] = (byte) roughness;
            materialPixels[offset + 1] = 0;
            materialPixels[offset + 2] = (byte) f0;
            materialPixels[offset + 3] = 0;
            // R=material AO, G=height, B=raw LabPBR F0/metal id,
            // A=raw porosity/SSS. Defaults are neutral and reversible.
            auxiliaryPixels[offset] = (byte) 255;
            auxiliaryPixels[offset + 1] = (byte) 128;
            auxiliaryPixels[offset + 2] = (byte) f0;
            auxiliaryPixels[offset + 3] = 0;
        }
    }

    private static DecodeResult decodeCompanion(
            ResourceManager resourceManager,
            LabPbrResources.CompanionResource companion,
            int frameWidth,
            int frameHeight,
            ImageConsumer consumer) {
        if (companion == null) {
            return DecodeResult.MISSING;
        }
        Optional<Resource> resource = resourceManager.getResource(companion.identifier());
        if (resource.isEmpty()) {
            return DecodeResult.ERROR;
        }

        try (InputStream input = resource.orElseThrow().open();
                NativeImage image = NativeImage.read(input)) {
            if (!hasCompatibleFrames(image, frameWidth, frameHeight)) {
                return DecodeResult.SIZE_MISMATCH;
            }
            consumer.copy(image);
            return DecodeResult.DECODED;
        } catch (IOException | RuntimeException error) {
            return DecodeResult.ERROR;
        }
    }

    private static boolean hasCompatibleFrames(
            NativeImage image,
            int frameWidth,
            int frameHeight) {
        return frameWidth > 0
                && frameHeight > 0
                && image.getWidth() >= frameWidth
                && image.getHeight() >= frameHeight
                && image.getWidth() % frameWidth == 0
                && image.getHeight() % frameHeight == 0;
    }

    private static void copyNormal(
            NativeImage source,
            byte[] target,
            byte[] auxiliary,
            int atlasWidth,
            int atlasHeight,
            int startX,
            int startY,
            int width,
            int height) {
        for (int y = 0; y < height; y++) {
            int targetY = startY + y;
            if (targetY < 0 || targetY >= atlasHeight) {
                continue;
            }
            for (int x = 0; x < width; x++) {
                int targetX = startX + x;
                if (targetX < 0 || targetX >= atlasWidth) {
                    continue;
                }
                int argb = source.getPixel(x, y);
                float normalX = ARGB.red(argb) / 127.5F - 1.0F;
                float normalY = ARGB.green(argb) / 127.5F - 1.0F;
                float normalZ = (float) Math.sqrt(Math.max(
                        0.0F, 1.0F - normalX * normalX - normalY * normalY));
                int output = (targetY * atlasWidth + targetX) * 4;
                target[output] = (byte) ARGB.red(argb);
                target[output + 1] = (byte) ARGB.green(argb);
                target[output + 2] = (byte) toUnorm8(normalZ);
                target[output + 3] = (byte) ARGB.alpha(argb);
                auxiliary[output] = (byte) ARGB.blue(argb);
                auxiliary[output + 1] = (byte) ARGB.alpha(argb);
            }
        }
    }

    private static void copyMaterial(
            NativeImage source,
            byte[] target,
            byte[] auxiliary,
            int atlasWidth,
            int atlasHeight,
            int startX,
            int startY,
            int width,
            int height,
            float fallbackF0) {
        for (int y = 0; y < height; y++) {
            int targetY = startY + y;
            if (targetY < 0 || targetY >= atlasHeight) {
                continue;
            }
            for (int x = 0; x < width; x++) {
                int targetX = startX + x;
                if (targetX < 0 || targetX >= atlasWidth) {
                    continue;
                }
                int argb = source.getPixel(x, y);
                int smoothness = ARGB.red(argb);
                int encodedF0OrMetal = ARGB.green(argb);
                int emission = ARGB.alpha(argb);
                int output = (targetY * atlasWidth + targetX) * 4;
                float perceptualSmoothness = smoothness / 255.0F;
                // LabPBR defines GGX alpha as (1 - smoothness)^2, while the Cycles
                // Principled Roughness socket expects the unsquared perceptual value
                // and performs that square internally. Supplying alpha here would
                // square it twice and make ordinary blocks unnaturally mirror-like.
                float cyclesPerceptualRoughness = 1.0F - perceptualSmoothness;
                target[output] = (byte) toUnorm8(cyclesPerceptualRoughness);
                target[output + 1] = (byte) (encodedF0OrMetal >= 230 ? 255 : 0);
                target[output + 2] = (byte) (encodedF0OrMetal >= 230
                        ? toUnorm8(fallbackF0)
                        : encodedF0OrMetal);
                target[output + 3] = (byte) (emission == 255
                        ? 0
                        : Math.round(emission * 255.0F / 254.0F));
                auxiliary[output + 2] = (byte) encodedF0OrMetal;
                auxiliary[output + 3] = (byte) ARGB.blue(argb);
            }
        }
    }

    private static int toUnorm8(float value) {
        return Math.round(Math.clamp(value, 0.0F, 1.0F) * 255.0F);
    }

    @FunctionalInterface
    private interface ImageConsumer {
        void copy(NativeImage image);
    }

    private record DecodeResult(boolean decoded, boolean sizeMismatch, boolean error) {
        private static final DecodeResult MISSING = new DecodeResult(false, false, false);
        private static final DecodeResult DECODED = new DecodeResult(true, false, false);
        private static final DecodeResult SIZE_MISMATCH = new DecodeResult(false, true, false);
        private static final DecodeResult ERROR = new DecodeResult(false, false, true);
    }

    public record Atlases(
            int width,
            int height,
            byte[] normalPixels,
            byte[] materialPixels,
            byte[] auxiliaryPixels,
            int decodedNormals,
            int decodedSpeculars,
            int sizeMismatches,
            int decodeErrors) {
        public long byteSize() {
            return (long) normalPixels.length + materialPixels.length
                    + auxiliaryPixels.length;
        }
    }
}

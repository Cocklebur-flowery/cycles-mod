package dev.cyclesrenderer.scene;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.ARGB;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
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
        return buildWithAnimationFrames(
                resourceManager, sprites, discovery, atlasWidth, atlasHeight,
                fallbackRoughness, fallbackF0, false).atlases();
    }

    static BuildResult buildWithAnimationFrames(
            ResourceManager resourceManager,
            Map<Identifier, TextureAtlasSprite> sprites,
            LabPbrResources.Discovery discovery,
            int atlasWidth,
            int atlasHeight,
            float fallbackRoughness,
            float fallbackF0) {
        return buildWithAnimationFrames(
                resourceManager, sprites, discovery, atlasWidth, atlasHeight,
                fallbackRoughness, fallbackF0, true);
    }

    private static BuildResult buildWithAnimationFrames(
            ResourceManager resourceManager,
            Map<Identifier, TextureAtlasSprite> sprites,
            LabPbrResources.Discovery discovery,
            int atlasWidth,
            int atlasHeight,
            float fallbackRoughness,
            float fallbackF0,
            boolean captureAnimationSources) {
        if (discovery.format() != LabPbrResources.Format.LAB_PBR_1_3) {
            return new BuildResult(empty(), Map.of(), Map.of());
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
        Map<Identifier, LabPbrAnimationRegionEncoder.FramePixels> normalFrames =
                new HashMap<>();
        Map<Identifier, LabPbrAnimationRegionEncoder.FramePixels> materialFrames =
                new HashMap<>();
        for (TextureAtlasSprite sprite : sprites.values()) {
            Identifier spriteId = sprite.contents().name();
            LabPbrResources.CompanionSet companionSet =
                    discovery.companions(spriteId);
            if (companionSet == null) {
                continue;
            }
            int startX = Math.round(sprite.getU0() * atlasWidth);
            int startY = Math.round(sprite.getV0() * atlasHeight);
            int width = sprite.contents().width();
            int height = sprite.contents().height();
            int imageFrame = LabPbrAnimationFrames.currentImageFrame(sprite);
            boolean captureAnimationFrames = captureAnimationSources && sprite.isAnimated();

            DecodeResult normalResult = decodeCompanion(
                    resourceManager,
                    companionSet.normal(),
                    width,
                    height,
                    image -> {
                        copyNormal(image, normalPixels, auxiliaryPixels,
                                atlasWidth, atlasHeight,
                                startX, startY, width, height, imageFrame);
                        return captureAnimationFrames
                                ? animationFrames(image, width, height)
                                : null;
                    });
            decodedNormals += normalResult.decoded() ? 1 : 0;
            sizeMismatches += normalResult.sizeMismatch() ? 1 : 0;
            decodeErrors += normalResult.error() ? 1 : 0;
            if (normalResult.frames() != null) {
                normalFrames.put(spriteId, normalResult.frames());
            }

            DecodeResult specularResult = decodeCompanion(
                    resourceManager,
                    companionSet.specular(),
                    width,
                    height,
                    image -> {
                        copyMaterial(image, materialPixels, auxiliaryPixels,
                                atlasWidth, atlasHeight,
                                startX, startY, width, height, imageFrame, fallbackF0);
                        return captureAnimationFrames
                                ? animationFrames(image, width, height)
                                : null;
                    });
            decodedSpeculars += specularResult.decoded() ? 1 : 0;
            sizeMismatches += specularResult.sizeMismatch() ? 1 : 0;
            decodeErrors += specularResult.error() ? 1 : 0;
            if (specularResult.frames() != null) {
                materialFrames.put(spriteId, specularResult.frames());
            }
        }

        return new BuildResult(
                new Atlases(
                        atlasWidth,
                        atlasHeight,
                        normalPixels,
                        materialPixels,
                        auxiliaryPixels,
                        decodedNormals,
                        decodedSpeculars,
                        sizeMismatches,
                        decodeErrors),
                Map.copyOf(normalFrames),
                Map.copyOf(materialFrames));
    }

    public static Atlases empty() {
        return new Atlases(0, 0, new byte[0], new byte[0], new byte[0],
                0, 0, 0, 0);
    }

    static void fillDefaults(
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
            if (!hasCompatibleFrames(
                    image.getWidth(), image.getHeight(), frameWidth, frameHeight)) {
                return DecodeResult.SIZE_MISMATCH;
            }
            return new DecodeResult(true, false, false, consumer.copy(image));
        } catch (IOException | RuntimeException error) {
            return DecodeResult.ERROR;
        }
    }

    static boolean hasCompatibleFrames(
            int imageWidth,
            int imageHeight,
            int frameWidth,
            int frameHeight) {
        return frameWidth > 0
                && frameHeight > 0
                && imageWidth >= frameWidth
                && imageHeight >= frameHeight
                && imageWidth % frameWidth == 0
                && imageHeight % frameHeight == 0;
    }

    private static LabPbrAnimationRegionEncoder.FramePixels animationFrames(
            NativeImage image,
            int frameWidth,
            int frameHeight) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = new int[Math.multiplyExact(width, height)];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = image.getPixel(x, y);
            }
        }
        return new LabPbrAnimationRegionEncoder.FramePixels(
                width, height, frameWidth, frameHeight, pixels);
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
            int height,
            int imageFrame) {
        int sourceStartX = frameStartX(
                source.getWidth(), source.getHeight(), width, height, imageFrame);
        int sourceStartY = frameStartY(
                source.getWidth(), source.getHeight(), width, height, imageFrame);
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
                int argb = source.getPixel(sourceStartX + x, sourceStartY + y);
                int output = (targetY * atlasWidth + targetX) * 4;
                decodeNormalPixel(argb, target, auxiliary, output);
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
            int imageFrame,
            float fallbackF0) {
        int sourceStartX = frameStartX(
                source.getWidth(), source.getHeight(), width, height, imageFrame);
        int sourceStartY = frameStartY(
                source.getWidth(), source.getHeight(), width, height, imageFrame);
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
                int argb = source.getPixel(sourceStartX + x, sourceStartY + y);
                int output = (targetY * atlasWidth + targetX) * 4;
                decodeMaterialPixel(argb, fallbackF0, target, auxiliary, output);
            }
        }
    }

    static void decodeNormalPixel(
            int argb,
            byte[] target,
            byte[] auxiliary,
            int output) {
        float normalX = ARGB.red(argb) / 127.5F - 1.0F;
        float normalY = ARGB.green(argb) / 127.5F - 1.0F;
        float normalZ = (float) Math.sqrt(Math.max(
                0.0F, 1.0F - normalX * normalX - normalY * normalY));
        target[output] = (byte) ARGB.red(argb);
        target[output + 1] = (byte) ARGB.green(argb);
        target[output + 2] = (byte) toUnorm8(normalZ);
        target[output + 3] = (byte) ARGB.alpha(argb);
        auxiliary[output] = (byte) ARGB.blue(argb);
        auxiliary[output + 1] = (byte) ARGB.alpha(argb);
    }

    static void decodeMaterialPixel(
            int argb,
            float fallbackF0,
            byte[] target,
            byte[] auxiliary,
            int output) {
        int smoothness = ARGB.red(argb);
        int encodedF0OrMetal = ARGB.green(argb);
        int emission = ARGB.alpha(argb);
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

    static int frameStartX(
            int imageWidth,
            int imageHeight,
            int frameWidth,
            int frameHeight,
            int imageFrame) {
        int columns = imageWidth / frameWidth;
        int rows = imageHeight / frameHeight;
        int frame = Math.floorMod(imageFrame, Math.multiplyExact(columns, rows));
        return frame % columns * frameWidth;
    }

    static int frameStartY(
            int imageWidth,
            int imageHeight,
            int frameWidth,
            int frameHeight,
            int imageFrame) {
        int columns = imageWidth / frameWidth;
        int rows = imageHeight / frameHeight;
        int frame = Math.floorMod(imageFrame, Math.multiplyExact(columns, rows));
        return frame / columns * frameHeight;
    }

    private static int toUnorm8(float value) {
        return Math.round(Math.clamp(value, 0.0F, 1.0F) * 255.0F);
    }

    @FunctionalInterface
    private interface ImageConsumer {
        LabPbrAnimationRegionEncoder.FramePixels copy(NativeImage image);
    }

    private record DecodeResult(
            boolean decoded,
            boolean sizeMismatch,
            boolean error,
            LabPbrAnimationRegionEncoder.FramePixels frames) {
        private static final DecodeResult MISSING =
                new DecodeResult(false, false, false, null);
        private static final DecodeResult SIZE_MISMATCH =
                new DecodeResult(false, true, false, null);
        private static final DecodeResult ERROR =
                new DecodeResult(false, false, true, null);
    }

    record BuildResult(
            Atlases atlases,
            Map<Identifier, LabPbrAnimationRegionEncoder.FramePixels> normalFrames,
            Map<Identifier, LabPbrAnimationRegionEncoder.FramePixels> materialFrames) {
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

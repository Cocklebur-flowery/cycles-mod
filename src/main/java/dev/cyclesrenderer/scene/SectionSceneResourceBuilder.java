package dev.cyclesrenderer.scene;

import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;

import java.util.List;

/** Builds immutable native scene resources from the current block atlas snapshot. */
final class SectionSceneResourceBuilder {
    private static final Identifier NORMAL_ATLAS = Identifier.fromNamespaceAndPath(
            "cyclesrenderer", "blocks_normal");
    private static final Identifier MATERIAL_ATLAS = Identifier.fromNamespaceAndPath(
            "cyclesrenderer", "blocks_material");
    private static final Identifier AUXILIARY_ATLAS = Identifier.fromNamespaceAndPath(
            "cyclesrenderer", "blocks_labpbr_auxiliary");

    private SectionSceneResourceBuilder() {
    }

    static AtlasLayout measure(List<SpriteInput> sprites) {
        int atlasWidth = 0;
        int atlasHeight = 0;
        for (SpriteInput sprite : sprites) {
            float widthFraction = sprite.u1() - sprite.u0();
            float heightFraction = sprite.v1() - sprite.v0();
            if (widthFraction > 0.0F) {
                atlasWidth = Math.max(
                        atlasWidth,
                        Math.round(sprite.width() / widthFraction));
            }
            if (heightFraction > 0.0F) {
                atlasHeight = Math.max(
                        atlasHeight,
                        Math.round(sprite.height() / heightFraction));
            }
        }
        if (atlasWidth <= 0 || atlasHeight <= 0) {
            throw new IllegalStateException("invalid Minecraft block atlas dimensions");
        }
        return new AtlasLayout(atlasWidth, atlasHeight);
    }

    static byte[] copyRgba(AtlasLayout layout, List<SpriteInput> sprites) {
        byte[] pixels = new byte[Math.multiplyExact(
                Math.multiplyExact(layout.width(), layout.height()), 4)];
        for (SpriteInput sprite : sprites) {
            int startX = Math.round(sprite.u0() * layout.width());
            int startY = Math.round(sprite.v0() * layout.height());
            for (int y = 0; y < sprite.height(); y++) {
                int targetY = startY + y;
                if (targetY < 0 || targetY >= layout.height()) {
                    continue;
                }
                for (int x = 0; x < sprite.width(); x++) {
                    int targetX = startX + x;
                    if (targetX < 0 || targetX >= layout.width()) {
                        continue;
                    }
                    int argb = sprite.pixels().argb(x, y);
                    int output = (targetY * layout.width() + targetX) * 4;
                    pixels[output] = (byte) ARGB.red(argb);
                    pixels[output + 1] = (byte) ARGB.green(argb);
                    pixels[output + 2] = (byte) ARGB.blue(argb);
                    pixels[output + 3] = (byte) ARGB.alpha(argb);
                }
            }
        }
        return pixels;
    }

    static SectionGeometrySnapshot.SceneResources build(
            int originX,
            int originY,
            int originZ,
            AtlasLayout layout,
            byte[] colorPixels,
            LabPbrAtlasBuilder.Atlases pbrAtlases) {
        boolean hasPbrAtlases = pbrAtlases.width() == layout.width()
                && pbrAtlases.height() == layout.height();
        int normalTextureIndex = hasPbrAtlases
                ? 1
                : SectionGeometrySnapshot.TEXTURE_INDEX_INVALID;
        int materialTextureIndex = hasPbrAtlases
                ? 2
                : SectionGeometrySnapshot.TEXTURE_INDEX_INVALID;
        int auxiliaryTextureIndex = hasPbrAtlases
                ? 3
                : SectionGeometrySnapshot.TEXTURE_INDEX_INVALID;
        int pbrFormat = hasPbrAtlases
                ? SectionGeometrySnapshot.PBR_FORMAT_LAB_1_3
                : SectionGeometrySnapshot.PBR_FORMAT_NONE;
        SectionGeometrySnapshot.MaterialData[] materials = materials(
                normalTextureIndex,
                materialTextureIndex,
                auxiliaryTextureIndex,
                pbrFormat);
        SectionGeometrySnapshot.TextureData[] textures = hasPbrAtlases
                ? pbrTextures(layout, colorPixels, pbrAtlases)
                : colorTextures(layout, colorPixels);
        return new SectionGeometrySnapshot.SceneResources(
                originX, originY, originZ, materials, textures);
    }

    private static SectionGeometrySnapshot.MaterialData[] materials(
            int normalTextureIndex,
            int materialTextureIndex,
            int auxiliaryTextureIndex,
            int pbrFormat) {
        return new SectionGeometrySnapshot.MaterialData[] {
            material(0, 0.0F, normalTextureIndex, materialTextureIndex,
                    auxiliaryTextureIndex, pbrFormat),
            material(SectionGeometrySnapshot.MATERIAL_FLAG_CUTOUT, 0.5F,
                    normalTextureIndex, materialTextureIndex, auxiliaryTextureIndex, pbrFormat),
            material(SectionGeometrySnapshot.MATERIAL_FLAG_BLEND, 0.0F,
                    normalTextureIndex, materialTextureIndex, auxiliaryTextureIndex, pbrFormat),
            material(SectionGeometrySnapshot.MATERIAL_FLAG_TRANSMISSION, 0.0F,
                    normalTextureIndex, materialTextureIndex, auxiliaryTextureIndex, pbrFormat),
            material(
                    SectionGeometrySnapshot.MATERIAL_FLAG_TRANSMISSION
                            | SectionGeometrySnapshot.MATERIAL_FLAG_WATER,
                    0.0F,
                    normalTextureIndex,
                    materialTextureIndex,
                    auxiliaryTextureIndex,
                    pbrFormat),
            material(
                    SectionGeometrySnapshot.MATERIAL_FLAG_CUTOUT
                            | SectionGeometrySnapshot.MATERIAL_FLAG_FOLIAGE,
                    0.5F,
                    normalTextureIndex,
                    materialTextureIndex,
                    auxiliaryTextureIndex,
                    pbrFormat)
        };
    }

    private static SectionGeometrySnapshot.MaterialData material(
            int flags,
            float alphaCutoff,
            int normalTextureIndex,
            int materialTextureIndex,
            int auxiliaryTextureIndex,
            int pbrFormat) {
        return new SectionGeometrySnapshot.MaterialData(
                0,
                flags,
                0.0F,
                alphaCutoff,
                normalTextureIndex,
                materialTextureIndex,
                pbrFormat,
                auxiliaryTextureIndex);
    }

    private static SectionGeometrySnapshot.TextureData[] colorTextures(
            AtlasLayout layout,
            byte[] colorPixels) {
        return new SectionGeometrySnapshot.TextureData[] {
            texture(
                    TextureAtlas.LOCATION_BLOCKS,
                    layout,
                    colorPixels,
                    SectionGeometrySnapshot.TEXTURE_ROLE_COLOR_SRGB)
        };
    }

    private static SectionGeometrySnapshot.TextureData[] pbrTextures(
            AtlasLayout layout,
            byte[] colorPixels,
            LabPbrAtlasBuilder.Atlases pbrAtlases) {
        return new SectionGeometrySnapshot.TextureData[] {
            texture(
                    TextureAtlas.LOCATION_BLOCKS,
                    layout,
                    colorPixels,
                    SectionGeometrySnapshot.TEXTURE_ROLE_COLOR_SRGB),
            texture(
                    NORMAL_ATLAS,
                    layout,
                    pbrAtlases.normalPixels(),
                    SectionGeometrySnapshot.TEXTURE_ROLE_DATA_LINEAR),
            texture(
                    MATERIAL_ATLAS,
                    layout,
                    pbrAtlases.materialPixels(),
                    SectionGeometrySnapshot.TEXTURE_ROLE_DATA_LINEAR),
            texture(
                    AUXILIARY_ATLAS,
                    layout,
                    pbrAtlases.auxiliaryPixels(),
                    SectionGeometrySnapshot.TEXTURE_ROLE_DATA_LINEAR)
        };
    }

    private static SectionGeometrySnapshot.TextureData texture(
            Identifier atlas,
            AtlasLayout layout,
            byte[] pixels,
            int role) {
        return new SectionGeometrySnapshot.TextureData(
                atlas, layout.width(), layout.height(), pixels, role);
    }

    record AtlasLayout(int width, int height) {
    }

    record SpriteInput(
            float u0,
            float v0,
            float u1,
            float v1,
            int width,
            int height,
            PixelReader pixels) {
    }

    @FunctionalInterface
    interface PixelReader {
        int argb(int x, int y);
    }
}

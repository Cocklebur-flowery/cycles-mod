package dev.cyclesrenderer.scene;

import net.minecraft.resources.Identifier;

public record SectionGeometrySnapshot(
        long sectionNode,
        int originX,
        int originY,
        int originZ,
        float[] vertexData,
        int[] vertexColors,
        int[] triangleData,
        int quadCount,
        long sequence) {
    public static final int VERTEX_FLOAT_STRIDE = 8;
    public static final int TRIANGLE_INT_STRIDE = 4;
    public static final int MATERIAL_SOLID = 0;
    public static final int MATERIAL_CUTOUT = 1;
    public static final int MATERIAL_TRANSLUCENT = 2;
    public static final int MATERIAL_GLASS = 3;
    public static final int MATERIAL_WATER = 4;
    static final int MATERIAL_UNCHANGED = -1;
    public static final int MATERIAL_FLAG_CUTOUT = 1;
    public static final int MATERIAL_FLAG_BLEND = 2;
    public static final int MATERIAL_FLAG_TRANSMISSION = 4;
    public static final int MATERIAL_FLAG_WATER = 8;
    public static final int PBR_FORMAT_NONE = 0;
    public static final int PBR_FORMAT_LAB_1_3 = 1;
    public static final int TEXTURE_INDEX_INVALID = -1;
    public static final int TEXTURE_ROLE_COLOR_SRGB = 0;
    public static final int TEXTURE_ROLE_DATA_LINEAR = 1;

    public int vertexCount() {
        return vertexColors.length;
    }

    public int triangleCount() {
        return triangleData.length / TRIANGLE_INT_STRIDE;
    }

    public boolean empty() {
        return triangleData.length == 0;
    }

    public record SceneResources(
            int originX,
            int originY,
            int originZ,
            MaterialData[] materials,
            TextureData[] textures) {
    }

    public record MaterialData(
            int textureIndex,
            int flags,
            float emissionStrength,
            float alphaCutoff,
            int normalTextureIndex,
            int materialTextureIndex,
            int pbrFormat,
            int auxiliaryTextureIndex) {
    }

    public record TextureData(
            Identifier atlas,
            int width,
            int height,
            byte[] rgbaPixels,
            int role) {
    }
}

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
    public static final int MATERIAL_FLAG_CUTOUT = 1;
    public static final int MATERIAL_FLAG_BLEND = 2;

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
            float alphaCutoff) {
    }

    public record TextureData(
            Identifier atlas,
            int width,
            int height,
            byte[] rgbaPixels) {
    }
}

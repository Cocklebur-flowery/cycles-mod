package dev.cyclesrenderer.scene;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.ARGB;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Builds a thin shell around the opaque silhouette of a foliage card. */
final class FoliageSolidifier {
    static final float THICKNESS = 0.01F;
    private static final int ALPHA_CUTOFF = 128;
    private static final int MAX_GRID_SIZE = 16;
    private static final int MIN_GRID_SIZE = 4;
    private static final int MAX_SEGMENTS = 96;
    private static final float UV_EPSILON = 1.0e-5F;

    private FoliageSolidifier() {
    }

    static Silhouette capture(TextureAtlasSprite sprite, int imageFrame) {
        int width = sprite.contents().width();
        int height = sprite.contents().height();
        int gridWidth = Math.min(width, MAX_GRID_SIZE);
        int gridHeight = Math.min(height, MAX_GRID_SIZE);
        Silhouette silhouette;
        do {
            silhouette = fromMask(
                    alphaMask(sprite, imageFrame, width, height, gridWidth, gridHeight),
                    sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1());
            if (silhouette.segments().size() <= MAX_SEGMENTS
                    || Math.max(gridWidth, gridHeight) <= MIN_GRID_SIZE) {
                return silhouette;
            }
            gridWidth = Math.max(MIN_GRID_SIZE, (gridWidth + 1) / 2);
            gridHeight = Math.max(MIN_GRID_SIZE, (gridHeight + 1) / 2);
        } while (true);
    }

    static Silhouette fromMask(boolean[][] mask) {
        return fromMask(mask, 0.0F, 1.0F, 0.0F, 1.0F);
    }

    static Result apply(
            float[] sourceVertices,
            int[] sourceColors,
            int[] sourceTriangles,
            List<Quad> quads) {
        if (quads.isEmpty()) {
            return new Result(sourceVertices, sourceColors, sourceTriangles, 0);
        }
        int addedQuads = 0;
        for (Quad quad : quads) {
            addedQuads = Math.addExact(addedQuads, 1 + verticalWallCount(quad.silhouette()));
        }
        int addedVertices = Math.multiplyExact(addedQuads, 4);
        int addedTriangles = Math.multiplyExact(addedQuads, 2);
        int sourceVertexCount = sourceColors.length;
        int sourceTriangleCount = sourceTriangles.length
                / SectionGeometrySnapshot.TRIANGLE_INT_STRIDE;
        float[] vertices = Arrays.copyOf(
                sourceVertices,
                Math.multiplyExact(
                        sourceVertexCount + addedVertices,
                        SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE));
        int[] colors = Arrays.copyOf(sourceColors, sourceVertexCount + addedVertices);
        int[] triangles = Arrays.copyOf(
                sourceTriangles,
                Math.multiplyExact(
                        sourceTriangleCount + addedTriangles,
                        SectionGeometrySnapshot.TRIANGLE_INT_STRIDE));
        Output output = new Output(vertices, colors, triangles, sourceVertexCount, sourceTriangleCount);
        for (Quad quad : quads) {
            appendBackFace(sourceVertices, sourceColors, quad, output);
            for (Segment segment : quad.silhouette().segments()) {
                if (isVerticalWall(segment)) {
                    appendWall(sourceVertices, sourceColors, quad, segment, output);
                }
            }
        }
        int actualAddedQuads = (output.triangleCursor - sourceTriangleCount) / 2;
        return new Result(
                Arrays.copyOf(
                        vertices,
                        output.vertexCursor * SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE),
                Arrays.copyOf(colors, output.vertexCursor),
                Arrays.copyOf(
                        triangles,
                        output.triangleCursor * SectionGeometrySnapshot.TRIANGLE_INT_STRIDE),
                actualAddedQuads);
    }

    private static int verticalWallCount(Silhouette silhouette) {
        return (int) silhouette.segments().stream().filter(FoliageSolidifier::isVerticalWall).count();
    }

    private static boolean isVerticalWall(Segment segment) {
        return Math.abs(segment.startS() - segment.endS()) <= UV_EPSILON;
    }

    static boolean coversSprite(float[] vertices, int vertexBase, Silhouette silhouette) {
        int stride = SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE;
        float minU = Float.POSITIVE_INFINITY;
        float maxU = Float.NEGATIVE_INFINITY;
        float minV = Float.POSITIVE_INFINITY;
        float maxV = Float.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 4; corner++) {
            int offset = (vertexBase + corner) * stride;
            minU = Math.min(minU, vertices[offset + 6]);
            maxU = Math.max(maxU, vertices[offset + 6]);
            minV = Math.min(minV, vertices[offset + 7]);
            maxV = Math.max(maxV, vertices[offset + 7]);
        }
        float toleranceU = Math.max(UV_EPSILON, (silhouette.maxU() - silhouette.minU()) * 0.02F);
        float toleranceV = Math.max(UV_EPSILON, (silhouette.maxV() - silhouette.minV()) * 0.02F);
        return minU <= silhouette.minU() + toleranceU
                && maxU >= silhouette.maxU() - toleranceU
                && minV <= silhouette.minV() + toleranceV
                && maxV >= silhouette.maxV() - toleranceV;
    }

    private static boolean[][] alphaMask(
            TextureAtlasSprite sprite,
            int imageFrame,
            int width,
            int height,
            int gridWidth,
            int gridHeight) {
        boolean[][] mask = new boolean[gridHeight][gridWidth];
        for (int gridY = 0; gridY < gridHeight; gridY++) {
            int startY = gridY * height / gridHeight;
            int endY = Math.max(startY + 1, (gridY + 1) * height / gridHeight);
            for (int gridX = 0; gridX < gridWidth; gridX++) {
                int startX = gridX * width / gridWidth;
                int endX = Math.max(startX + 1, (gridX + 1) * width / gridWidth);
                boolean opaque = false;
                for (int y = startY; y < endY && !opaque; y++) {
                    for (int x = startX; x < endX; x++) {
                        if (ARGB.alpha(sprite.getPixelRGBA(imageFrame, x, y)) >= ALPHA_CUTOFF) {
                            opaque = true;
                            break;
                        }
                    }
                }
                mask[gridY][gridX] = opaque;
            }
        }
        return mask;
    }

    private static Silhouette fromMask(
            boolean[][] mask,
            float minU,
            float maxU,
            float minV,
            float maxV) {
        int height = mask.length;
        int width = height == 0 ? 0 : mask[0].length;
        if (width == 0) {
            return new Silhouette(minU, maxU, minV, maxV, List.of());
        }
        List<Segment> segments = new ArrayList<>();
        appendHorizontalSegments(mask, width, height, segments);
        appendVerticalSegments(mask, width, height, segments);
        return new Silhouette(minU, maxU, minV, maxV, List.copyOf(segments));
    }

    private static void appendHorizontalSegments(
            boolean[][] mask,
            int width,
            int height,
            List<Segment> output) {
        for (int y = 0; y <= height; y++) {
            int runStart = -1;
            boolean runInsideBelow = false;
            for (int x = 0; x <= width; x++) {
                boolean above = x < width && y > 0 && mask[y - 1][x];
                boolean below = x < width && y < height && mask[y][x];
                boolean boundary = x < width && above != below;
                boolean insideBelow = boundary && below;
                if (boundary && (runStart < 0 || insideBelow == runInsideBelow)) {
                    if (runStart < 0) {
                        runStart = x;
                        runInsideBelow = insideBelow;
                    }
                    continue;
                }
                if (runStart >= 0) {
                    float sampleY = (y + (runInsideBelow ? 0.5F : -0.5F)) / height;
                    output.add(new Segment(
                            (float) runStart / width,
                            (float) y / height,
                            (float) x / width,
                            (float) y / height,
                            (runStart + x) * 0.5F / width,
                            sampleY));
                    runStart = boundary ? x : -1;
                    runInsideBelow = insideBelow;
                }
            }
        }
    }

    private static void appendVerticalSegments(
            boolean[][] mask,
            int width,
            int height,
            List<Segment> output) {
        for (int x = 0; x <= width; x++) {
            int runStart = -1;
            boolean runInsideRight = false;
            for (int y = 0; y <= height; y++) {
                boolean left = y < height && x > 0 && mask[y][x - 1];
                boolean right = y < height && x < width && mask[y][x];
                boolean boundary = y < height && left != right;
                boolean insideRight = boundary && right;
                if (boundary && (runStart < 0 || insideRight == runInsideRight)) {
                    if (runStart < 0) {
                        runStart = y;
                        runInsideRight = insideRight;
                    }
                    continue;
                }
                if (runStart >= 0) {
                    float sampleX = (x + (runInsideRight ? 0.5F : -0.5F)) / width;
                    output.add(new Segment(
                            (float) x / width,
                            (float) runStart / height,
                            (float) x / width,
                            (float) y / height,
                            sampleX,
                            (runStart + y) * 0.5F / height));
                    runStart = boundary ? y : -1;
                    runInsideRight = insideRight;
                }
            }
        }
    }

    private static void appendBackFace(
            float[] sourceVertices,
            int[] sourceColors,
            Quad quad,
            Output output) {
        int source = quad.vertexBase() * SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE;
        float normalX = sourceVertices[source + 3];
        float normalY = sourceVertices[source + 4];
        float normalZ = sourceVertices[source + 5];
        int vertexBase = output.vertexCursor;
        for (int corner = 0; corner < 4; corner++) {
            int offset = (quad.vertexBase() + corner)
                    * SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE;
            output.writeVertex(
                    sourceVertices[offset] - normalX * THICKNESS,
                    sourceVertices[offset + 1] - normalY * THICKNESS,
                    sourceVertices[offset + 2] - normalZ * THICKNESS,
                    -normalX, -normalY, -normalZ,
                    sourceVertices[offset + 6], sourceVertices[offset + 7],
                    sourceColors[quad.vertexBase() + corner]);
        }
        output.writeQuad(vertexBase, quad.materialIndex(), true);
    }

    private static void appendWall(
            float[] sourceVertices,
            int[] sourceColors,
            Quad quad,
            Segment segment,
            Output output) {
        Sample first = sample(sourceVertices, sourceColors, quad, segment.startS(), segment.startT());
        Sample second = sample(sourceVertices, sourceColors, quad, segment.endS(), segment.endT());
        Sample inside = sample(sourceVertices, sourceColors, quad, segment.sampleS(), segment.sampleT());
        float normalX = sourceVertices[
                quad.vertexBase() * SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE + 3];
        float normalY = sourceVertices[
                quad.vertexBase() * SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE + 4];
        float normalZ = sourceVertices[
                quad.vertexBase() * SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE + 5];
        float edgeX = second.x() - first.x();
        float edgeY = second.y() - first.y();
        float edgeZ = second.z() - first.z();
        float sideX = -THICKNESS * (edgeY * normalZ - edgeZ * normalY);
        float sideY = -THICKNESS * (edgeZ * normalX - edgeX * normalZ);
        float sideZ = -THICKNESS * (edgeX * normalY - edgeY * normalX);
        float midpointX = (first.x() + second.x()) * 0.5F;
        float midpointY = (first.y() + second.y()) * 0.5F;
        float midpointZ = (first.z() + second.z()) * 0.5F;
        if (sideX * (midpointX - inside.x())
                + sideY * (midpointY - inside.y())
                + sideZ * (midpointZ - inside.z()) < 0.0F) {
            Sample swap = first;
            first = second;
            second = swap;
            sideX = -sideX;
            sideY = -sideY;
            sideZ = -sideZ;
        }
        float length = (float) Math.sqrt(sideX * sideX + sideY * sideY + sideZ * sideZ);
        if (length <= 1.0e-8F || !Float.isFinite(length)) {
            return;
        }
        sideX /= length;
        sideY /= length;
        sideZ /= length;
        float u = quad.silhouette().atlasU(segment.sampleS());
        float v = quad.silhouette().atlasV(segment.sampleT());
        int vertexBase = output.vertexCursor;
        output.writeVertex(first.x(), first.y(), first.z(), sideX, sideY, sideZ, u, v, inside.color());
        output.writeVertex(second.x(), second.y(), second.z(), sideX, sideY, sideZ, u, v, inside.color());
        output.writeVertex(
                second.x() - normalX * THICKNESS,
                second.y() - normalY * THICKNESS,
                second.z() - normalZ * THICKNESS,
                sideX, sideY, sideZ, u, v, inside.color());
        output.writeVertex(
                first.x() - normalX * THICKNESS,
                first.y() - normalY * THICKNESS,
                first.z() - normalZ * THICKNESS,
                sideX, sideY, sideZ, u, v, inside.color());
        output.writeQuad(vertexBase, quad.materialIndex(), false);
    }

    private static Sample sample(
            float[] vertices,
            int[] colors,
            Quad quad,
            float s,
            float t) {
        float u = quad.silhouette().atlasU(s);
        float v = quad.silhouette().atlasV(t);
        Weights weights = weights(vertices, quad.vertexBase(), u, v);
        float x = 0.0F;
        float y = 0.0F;
        float z = 0.0F;
        float[] channels = new float[4];
        for (int corner = 0; corner < 4; corner++) {
            float weight = weights.value(corner);
            int offset = (quad.vertexBase() + corner)
                    * SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE;
            x += vertices[offset] * weight;
            y += vertices[offset + 1] * weight;
            z += vertices[offset + 2] * weight;
            int color = colors[quad.vertexBase() + corner];
            channels[0] += (color & 0xFF) * weight;
            channels[1] += ((color >>> 8) & 0xFF) * weight;
            channels[2] += ((color >>> 16) & 0xFF) * weight;
            channels[3] += ((color >>> 24) & 0xFF) * weight;
        }
        int color = Math.round(channels[0])
                | Math.round(channels[1]) << 8
                | Math.round(channels[2]) << 16
                | Math.round(channels[3]) << 24;
        return new Sample(x, y, z, color);
    }

    private static Weights weights(float[] vertices, int vertexBase, float u, float v) {
        Weights first = barycentric(vertices, vertexBase, 0, 1, 2, u, v);
        if (first != null) {
            return first;
        }
        Weights second = barycentric(vertices, vertexBase, 0, 2, 3, u, v);
        if (second != null) {
            return second;
        }
        return new Weights(1.0F, 0.0F, 0.0F, 0.0F);
    }

    private static Weights barycentric(
            float[] vertices,
            int vertexBase,
            int a,
            int b,
            int c,
            float u,
            float v) {
        int stride = SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE;
        int offsetA = (vertexBase + a) * stride;
        int offsetB = (vertexBase + b) * stride;
        int offsetC = (vertexBase + c) * stride;
        float au = vertices[offsetA + 6];
        float av = vertices[offsetA + 7];
        float bu = vertices[offsetB + 6];
        float bv = vertices[offsetB + 7];
        float cu = vertices[offsetC + 6];
        float cv = vertices[offsetC + 7];
        float denominator = (bv - cv) * (au - cu) + (cu - bu) * (av - cv);
        if (Math.abs(denominator) <= UV_EPSILON) {
            return null;
        }
        float wa = ((bv - cv) * (u - cu) + (cu - bu) * (v - cv)) / denominator;
        float wb = ((cv - av) * (u - cu) + (au - cu) * (v - cv)) / denominator;
        float wc = 1.0F - wa - wb;
        if (wa < -UV_EPSILON || wb < -UV_EPSILON || wc < -UV_EPSILON) {
            return null;
        }
        float[] values = new float[4];
        values[a] = wa;
        values[b] = wb;
        values[c] = wc;
        return new Weights(values[0], values[1], values[2], values[3]);
    }

    record Silhouette(float minU, float maxU, float minV, float maxV, List<Segment> segments) {
        float atlasU(float s) {
            return minU + (maxU - minU) * s;
        }

        float atlasV(float t) {
            return minV + (maxV - minV) * t;
        }
    }

    record Segment(float startS, float startT, float endS, float endT, float sampleS, float sampleT) {
    }

    record Quad(int vertexBase, int materialIndex, Silhouette silhouette) {
    }

    record Result(float[] vertices, int[] colors, int[] triangles, int addedQuads) {
    }

    private record Weights(float first, float second, float third, float fourth) {
        float value(int index) {
            return switch (index) {
                case 0 -> first;
                case 1 -> second;
                case 2 -> third;
                case 3 -> fourth;
                default -> throw new IndexOutOfBoundsException(index);
            };
        }
    }

    private record Sample(float x, float y, float z, int color) {
    }

    private static final class Output {
        private final float[] vertices;
        private final int[] colors;
        private final int[] triangles;
        private int vertexCursor;
        private int triangleCursor;

        private Output(
                float[] vertices,
                int[] colors,
                int[] triangles,
                int vertexCursor,
                int triangleCursor) {
            this.vertices = vertices;
            this.colors = colors;
            this.triangles = triangles;
            this.vertexCursor = vertexCursor;
            this.triangleCursor = triangleCursor;
        }

        private void writeVertex(
                float x,
                float y,
                float z,
                float normalX,
                float normalY,
                float normalZ,
                float u,
                float v,
                int color) {
            int target = vertexCursor * SectionGeometrySnapshot.VERTEX_FLOAT_STRIDE;
            vertices[target] = x;
            vertices[target + 1] = y;
            vertices[target + 2] = z;
            vertices[target + 3] = normalX;
            vertices[target + 4] = normalY;
            vertices[target + 5] = normalZ;
            vertices[target + 6] = u;
            vertices[target + 7] = v;
            colors[vertexCursor] = color;
            vertexCursor++;
        }

        private void writeQuad(int vertexBase, int materialIndex, boolean reversed) {
            int[] order = reversed
                    ? new int[]{0, 2, 1, 0, 3, 2}
                    : new int[]{0, 1, 2, 0, 2, 3};
            for (int triangle = 0; triangle < 2; triangle++) {
                int target = triangleCursor * SectionGeometrySnapshot.TRIANGLE_INT_STRIDE;
                int source = triangle * 3;
                triangles[target] = vertexBase + order[source];
                triangles[target + 1] = vertexBase + order[source + 1];
                triangles[target + 2] = vertexBase + order[source + 2];
                triangles[target + 3] = materialIndex;
                triangleCursor++;
            }
        }
    }
}

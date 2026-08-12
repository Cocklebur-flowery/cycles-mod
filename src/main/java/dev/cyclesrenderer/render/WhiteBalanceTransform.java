package dev.cyclesrenderer.render;

/** Blender-compatible temperature/tint adaptation for the Linear Rec.709 working space. */
final class WhiteBalanceTransform {
    private static final double[] MIRED = {
            0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 125, 150, 175, 200, 225,
            250, 275, 300, 325, 350, 375, 400, 425, 450, 475, 500, 525, 550, 575, 600
    };
    private static final double[] U = {
            .18006, .18066, .18133, .18208, .18293, .18388, .18494, .18611, .18740,
            .18880, .19032, .19462, .19962, .20525, .21142, .21807, .22511, .23247,
            .24010, .24792, .25591, .26400, .27218, .28039, .28863, .29685, .30505,
            .31320, .32129, .32931, .33724
    };
    private static final double[] V = {
            .26352, .26589, .26846, .27119, .27407, .27709, .28021, .28342, .28668,
            .28997, .29326, .30141, .30921, .31647, .32312, .32909, .33439, .33904,
            .34308, .34655, .34951, .35200, .35407, .35577, .35714, .35823, .35907,
            .35968, .36011, .36038, .36051
    };
    private static final double[] T = {
            -.24341, -.25479, -.26876, -.28539, -.30470, -.32675, -.35156, -.37915,
            -.40955, -.44278, -.47888, -.58204, -.70471, -.84901, -1.0182, -1.2168,
            -1.4512, -1.7298, -2.0637, -2.4681, -2.9641, -3.5814, -4.3633, -5.3762,
            -6.7262, -8.5955, -11.324, -15.628, -23.325, -40.770, -116.45
    };

    private static final double[][] REC709_TO_XYZ = {
            {.4123907993, .3575843394, .1804807884},
            {.2126390059, .7151686788, .0721923154},
            {.0193308187, .1191947798, .9505321522}
    };
    private static final double[][] XYZ_TO_REC709 = {
            {3.2409699419, -1.5373831776, -.4986107603},
            {-.9692436363, 1.8759675015, .0415550574},
            {.0556300797, -.2039769589, 1.0569715142}
    };
    private static final double[][] BRADFORD = {
            {.8951, .2664, -.1614},
            {-.7502, 1.7135, .0367},
            {.0389, -.0685, 1.0296}
    };
    private static final double[][] BRADFORD_INVERSE = invert(BRADFORD);
    private static final double[] REC709_WHITE = multiply(REC709_TO_XYZ, new double[]{1, 1, 1});
    private static final float[] IDENTITY = {1, 0, 0, 0, 1, 0, 0, 0, 1};

    private WhiteBalanceTransform() {
    }

    static float[] matrix(boolean enabled, float temperature, float tint) {
        if (!enabled) {
            return IDENTITY.clone();
        }
        double[] sourceWhite = whitePoint(temperature, tint);
        double[] sourceLms = multiply(BRADFORD, sourceWhite);
        double[] targetLms = multiply(BRADFORD, REC709_WHITE);
        double[][] scale = {
                {targetLms[0] / sourceLms[0], 0, 0},
                {0, targetLms[1] / sourceLms[1], 0},
                {0, 0, targetLms[2] / sourceLms[2]}
        };
        double[][] adaptation = multiply(BRADFORD_INVERSE, multiply(scale, BRADFORD));
        double[][] sceneMatrix = multiply(XYZ_TO_REC709, multiply(adaptation, REC709_TO_XYZ));
        float[] result = new float[9];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                result[row * 3 + column] = (float) sceneMatrix[row][column];
            }
        }
        return result;
    }

    private static double[] whitePoint(float temperature, float tint) {
        double mired = Math.max(MIRED[1], Math.min(MIRED[MIRED.length - 2], 1_000_000.0 / temperature));
        int high = 1;
        while (MIRED[high] < mired) {
            high++;
        }
        int low = high - 1;
        double factor = (mired - MIRED[low]) / (MIRED[high] - MIRED[low]);
        double u = lerp(U[low], U[high], factor);
        double v = lerp(V[low], V[high], factor);
        double x0 = 1.0 / Math.sqrt(1.0 + T[low] * T[low]);
        double y0 = T[low] * x0;
        double x1 = 1.0 / Math.sqrt(1.0 + T[high] * T[high]);
        double y1 = T[high] * x1;
        double ix = lerp(x0, x1, factor);
        double iy = lerp(y0, y1, factor);
        double length = Math.sqrt(ix * ix + iy * iy);
        u -= ix / length * tint / 3000.0;
        v -= iy / length * tint / 3000.0;
        double divisor = 2.0 * u - 8.0 * v + 4.0;
        double x = 3.0 * u / divisor;
        double y = 2.0 * v / divisor;
        return new double[]{x / y, 1.0, (1.0 - x - y) / y};
    }

    private static double lerp(double a, double b, double factor) {
        return a + (b - a) * factor;
    }

    private static double[] multiply(double[][] matrix, double[] vector) {
        return new double[]{
                matrix[0][0] * vector[0] + matrix[0][1] * vector[1] + matrix[0][2] * vector[2],
                matrix[1][0] * vector[0] + matrix[1][1] * vector[1] + matrix[1][2] * vector[2],
                matrix[2][0] * vector[0] + matrix[2][1] * vector[1] + matrix[2][2] * vector[2]
        };
    }

    private static double[][] multiply(double[][] left, double[][] right) {
        double[][] result = new double[3][3];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                for (int index = 0; index < 3; index++) {
                    result[row][column] += left[row][index] * right[index][column];
                }
            }
        }
        return result;
    }

    private static double[][] invert(double[][] matrix) {
        double determinant = matrix[0][0] * (matrix[1][1] * matrix[2][2] - matrix[1][2] * matrix[2][1])
                - matrix[0][1] * (matrix[1][0] * matrix[2][2] - matrix[1][2] * matrix[2][0])
                + matrix[0][2] * (matrix[1][0] * matrix[2][1] - matrix[1][1] * matrix[2][0]);
        return new double[][]{
                {(matrix[1][1] * matrix[2][2] - matrix[1][2] * matrix[2][1]) / determinant,
                        (matrix[0][2] * matrix[2][1] - matrix[0][1] * matrix[2][2]) / determinant,
                        (matrix[0][1] * matrix[1][2] - matrix[0][2] * matrix[1][1]) / determinant},
                {(matrix[1][2] * matrix[2][0] - matrix[1][0] * matrix[2][2]) / determinant,
                        (matrix[0][0] * matrix[2][2] - matrix[0][2] * matrix[2][0]) / determinant,
                        (matrix[0][2] * matrix[1][0] - matrix[0][0] * matrix[1][2]) / determinant},
                {(matrix[1][0] * matrix[2][1] - matrix[1][1] * matrix[2][0]) / determinant,
                        (matrix[0][1] * matrix[2][0] - matrix[0][0] * matrix[2][1]) / determinant,
                        (matrix[0][0] * matrix[1][1] - matrix[0][1] * matrix[1][0]) / determinant}
        };
    }
}

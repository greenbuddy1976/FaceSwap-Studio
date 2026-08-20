package com.greenbuddy.faceswapstudio.engine;

import java.util.Arrays;

/**
 * Least-squares similarity transform. The returned matrix maps source points
 * to destination points as [a, -b, tx; b, a, ty].
 */
public final class SimilarityTransform {
    private SimilarityTransform() {
    }

    public static float[] estimate(float[][] source, float[][] destination) {
        if (source == null || destination == null || source.length != destination.length || source.length < 2) {
            throw new IllegalArgumentException("At least two matching point pairs are required.");
        }

        double[][] normal = new double[4][4];
        double[] rhs = new double[4];

        for (int i = 0; i < source.length; i++) {
            if (source[i].length != 2 || destination[i].length != 2) {
                throw new IllegalArgumentException("Every point must contain x and y.");
            }
            double x = source[i][0];
            double y = source[i][1];
            double qx = destination[i][0];
            double qy = destination[i][1];

            accumulate(normal, rhs, new double[] { x, -y, 1.0, 0.0 }, qx);
            accumulate(normal, rhs, new double[] { y, x, 0.0, 1.0 }, qy);
        }

        double[] solved = solve(normal, rhs);
        double a = solved[0];
        double b = solved[1];
        double scaleSquared = a * a + b * b;
        if (!Double.isFinite(scaleSquared) || scaleSquared < 1.0e-10) {
            throw new IllegalArgumentException("Landmarks do not form a valid face transform.");
        }
        return new float[] {
            (float) a,
            (float) -b,
            (float) solved[2],
            (float) b,
            (float) a,
            (float) solved[3]
        };
    }

    public static float[] invert(float[] matrix) {
        if (matrix == null || matrix.length != 6) {
            throw new IllegalArgumentException("A 2x3 affine matrix is required.");
        }
        double a = matrix[0];
        double c = matrix[1];
        double tx = matrix[2];
        double b = matrix[3];
        double d = matrix[4];
        double ty = matrix[5];
        double determinant = a * d - b * c;
        if (Math.abs(determinant) < 1.0e-12) {
            throw new IllegalArgumentException("Transform is singular.");
        }
        double inverseDeterminant = 1.0 / determinant;
        double ia = d * inverseDeterminant;
        double ic = -c * inverseDeterminant;
        double ib = -b * inverseDeterminant;
        double id = a * inverseDeterminant;
        double itx = -(ia * tx + ic * ty);
        double ity = -(ib * tx + id * ty);
        return new float[] { (float) ia, (float) ic, (float) itx, (float) ib, (float) id, (float) ity };
    }

    public static float[] apply(float[] matrix, float x, float y) {
        if (matrix == null || matrix.length != 6) {
            throw new IllegalArgumentException("A 2x3 affine matrix is required.");
        }
        return new float[] {
            matrix[0] * x + matrix[1] * y + matrix[2],
            matrix[3] * x + matrix[4] * y + matrix[5]
        };
    }

    private static void accumulate(double[][] normal, double[] rhs, double[] row, double target) {
        for (int r = 0; r < 4; r++) {
            rhs[r] += row[r] * target;
            for (int c = 0; c < 4; c++) {
                normal[r][c] += row[r] * row[c];
            }
        }
    }

    private static double[] solve(double[][] matrix, double[] vector) {
        double[][] augmented = new double[4][5];
        for (int r = 0; r < 4; r++) {
            System.arraycopy(matrix[r], 0, augmented[r], 0, 4);
            augmented[r][4] = vector[r];
        }

        for (int column = 0; column < 4; column++) {
            int pivot = column;
            for (int row = column + 1; row < 4; row++) {
                if (Math.abs(augmented[row][column]) > Math.abs(augmented[pivot][column])) {
                    pivot = row;
                }
            }
            if (Math.abs(augmented[pivot][column]) < 1.0e-12) {
                throw new IllegalArgumentException("Landmarks are degenerate: " + Arrays.deepToString(matrix));
            }
            double[] swap = augmented[column];
            augmented[column] = augmented[pivot];
            augmented[pivot] = swap;

            double divisor = augmented[column][column];
            for (int c = column; c < 5; c++) {
                augmented[column][c] /= divisor;
            }
            for (int row = 0; row < 4; row++) {
                if (row == column) {
                    continue;
                }
                double factor = augmented[row][column];
                for (int c = column; c < 5; c++) {
                    augmented[row][c] -= factor * augmented[column][c];
                }
            }
        }

        return new double[] { augmented[0][4], augmented[1][4], augmented[2][4], augmented[3][4] };
    }
}

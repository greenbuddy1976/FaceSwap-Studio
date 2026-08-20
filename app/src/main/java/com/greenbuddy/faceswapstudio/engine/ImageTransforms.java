package com.greenbuddy.faceswapstudio.engine;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;

import java.util.Arrays;

public final class ImageTransforms {
    private static final float[][] ARCFACE_112_V2 = {
        { 0.34191607f, 0.46157411f },
        { 0.65653393f, 0.45983393f },
        { 0.50022500f, 0.64050536f },
        { 0.37097589f, 0.82469196f },
        { 0.63151696f, 0.82325089f }
    };

    private static final float[][] ARCFACE_128 = {
        { 0.36167656f, 0.40387734f },
        { 0.63696719f, 0.40235469f },
        { 0.50019687f, 0.56044219f },
        { 0.38710391f, 0.72160547f },
        { 0.61507734f, 0.72034453f }
    };

    private ImageTransforms() {
    }

    public static AlignedFace alignForEmbedding(Bitmap source, PointF[] landmarks) {
        return align(source, landmarks, ARCFACE_112_V2, 112);
    }

    public static AlignedFace alignForSwap(Bitmap source, PointF[] landmarks) {
        return align(source, landmarks, ARCFACE_128, 128);
    }

    public static float[] toArcFaceTensor(Bitmap bitmap) {
        return toNchw(bitmap, true);
    }

    public static float[] toSwapTensor(Bitmap bitmap) {
        return toNchw(bitmap, false);
    }

    public static Bitmap blendSwap(Bitmap target, Bitmap targetCrop, float[] outputChw, float[] targetToCrop) {
        if (outputChw.length != 3 * 128 * 128) {
            throw new IllegalArgumentException("Unexpected swap output size: " + outputChw.length);
        }

        int[] targetPixels = new int[128 * 128];
        int[] swapPixels = new int[128 * 128];
        int[] maskedPixels = new int[128 * 128];
        float[] mask = createFaceMask(128, 128);
        targetCrop.getPixels(targetPixels, 0, 128, 0, 0, 128, 128);

        for (int i = 0; i < 128 * 128; i++) {
            int red = toByte(outputChw[i]);
            int green = toByte(outputChw[128 * 128 + i]);
            int blue = toByte(outputChw[2 * 128 * 128 + i]);
            swapPixels[i] = Color.rgb(red, green, blue);
        }

        colorMatch(swapPixels, targetPixels, mask);
        for (int i = 0; i < maskedPixels.length; i++) {
            int alpha = Math.round(255f * mask[i]);
            int color = swapPixels[i];
            maskedPixels[i] = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
        }

        Bitmap maskedFace = Bitmap.createBitmap(maskedPixels, 128, 128, Bitmap.Config.ARGB_8888);
        Bitmap result = target.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(result);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        Matrix inverse = toAndroidMatrix(SimilarityTransform.invert(targetToCrop));
        canvas.drawBitmap(maskedFace, inverse, paint);
        maskedFace.recycle();
        return result;
    }

    private static AlignedFace align(
        Bitmap source,
        PointF[] landmarks,
        float[][] normalizedTemplate,
        int size
    ) {
        float[][] sourcePoints = new float[5][2];
        float[][] destinationPoints = new float[5][2];
        for (int i = 0; i < 5; i++) {
            sourcePoints[i][0] = landmarks[i].x;
            sourcePoints[i][1] = landmarks[i].y;
            destinationPoints[i][0] = normalizedTemplate[i][0] * size;
            destinationPoints[i][1] = normalizedTemplate[i][1] * size;
        }
        float[] transform = SimilarityTransform.estimate(sourcePoints, destinationPoints);
        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.BLACK);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        canvas.drawBitmap(source, toAndroidMatrix(transform), paint);
        return new AlignedFace(output, transform);
    }

    private static float[] toNchw(Bitmap bitmap, boolean arcFaceNormalization) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int plane = width * height;
        int[] pixels = new int[plane];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        float[] tensor = new float[plane * 3];
        for (int i = 0; i < plane; i++) {
            float red = Color.red(pixels[i]);
            float green = Color.green(pixels[i]);
            float blue = Color.blue(pixels[i]);
            if (arcFaceNormalization) {
                tensor[i] = (red - 127.5f) / 127.5f;
                tensor[plane + i] = (green - 127.5f) / 127.5f;
                tensor[2 * plane + i] = (blue - 127.5f) / 127.5f;
            } else {
                tensor[i] = red / 255f;
                tensor[plane + i] = green / 255f;
                tensor[2 * plane + i] = blue / 255f;
            }
        }
        return tensor;
    }

    private static Matrix toAndroidMatrix(float[] transform) {
        Matrix matrix = new Matrix();
        matrix.setValues(new float[] {
            transform[0], transform[1], transform[2],
            transform[3], transform[4], transform[5],
            0f, 0f, 1f
        });
        return matrix;
    }

    private static float[] createFaceMask(int width, int height) {
        float[] mask = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float nx = (x - width * 0.5f) / (width * 0.45f);
                float ny = (y - height * 0.53f) / (height * 0.49f);
                float distance = (float) Math.sqrt(nx * nx + ny * ny);
                float alpha = smoothStep(1.0f, 0.78f, distance);
                float topFade = smoothStep(0.02f, 0.15f, y / (float) height);
                mask[y * width + x] = clamp(alpha * topFade, 0f, 1f);
            }
        }
        return mask;
    }

    private static void colorMatch(int[] swap, int[] target, float[] mask) {
        double[] swapMean = new double[3];
        double[] targetMean = new double[3];
        double weight = 0.0;
        for (int i = 0; i < swap.length; i++) {
            double w = mask[i];
            if (w < 0.05) {
                continue;
            }
            weight += w;
            swapMean[0] += Color.red(swap[i]) * w;
            swapMean[1] += Color.green(swap[i]) * w;
            swapMean[2] += Color.blue(swap[i]) * w;
            targetMean[0] += Color.red(target[i]) * w;
            targetMean[1] += Color.green(target[i]) * w;
            targetMean[2] += Color.blue(target[i]) * w;
        }
        if (weight < 1.0) {
            return;
        }
        for (int channel = 0; channel < 3; channel++) {
            swapMean[channel] /= weight;
            targetMean[channel] /= weight;
        }

        double[] swapVariance = new double[3];
        double[] targetVariance = new double[3];
        for (int i = 0; i < swap.length; i++) {
            double w = mask[i];
            if (w < 0.05) {
                continue;
            }
            int[] sourceChannels = { Color.red(swap[i]), Color.green(swap[i]), Color.blue(swap[i]) };
            int[] targetChannels = { Color.red(target[i]), Color.green(target[i]), Color.blue(target[i]) };
            for (int channel = 0; channel < 3; channel++) {
                double sourceDelta = sourceChannels[channel] - swapMean[channel];
                double targetDelta = targetChannels[channel] - targetMean[channel];
                swapVariance[channel] += sourceDelta * sourceDelta * w;
                targetVariance[channel] += targetDelta * targetDelta * w;
            }
        }

        double[] scale = new double[3];
        for (int channel = 0; channel < 3; channel++) {
            double sourceDeviation = Math.sqrt(swapVariance[channel] / weight + 1.0e-6);
            double targetDeviation = Math.sqrt(targetVariance[channel] / weight + 1.0e-6);
            scale[channel] = clamp((float) (targetDeviation / sourceDeviation), 0.65f, 1.45f);
        }

        for (int i = 0; i < swap.length; i++) {
            int red = matchChannel(Color.red(swap[i]), swapMean[0], targetMean[0], scale[0]);
            int green = matchChannel(Color.green(swap[i]), swapMean[1], targetMean[1], scale[1]);
            int blue = matchChannel(Color.blue(swap[i]), swapMean[2], targetMean[2], scale[2]);
            swap[i] = Color.rgb(red, green, blue);
        }
    }

    private static int matchChannel(int value, double sourceMean, double targetMean, double scale) {
        double corrected = (value - sourceMean) * scale + targetMean;
        double mixed = value * 0.25 + corrected * 0.75;
        return Math.max(0, Math.min(255, (int) Math.round(mixed)));
    }

    private static int toByte(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255f)));
    }

    private static float smoothStep(float edge0, float edge1, float value) {
        if (edge0 == edge1) {
            return value < edge0 ? 0f : 1f;
        }
        float t = clamp((value - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public static final class AlignedFace {
        private final Bitmap bitmap;
        private final float[] forwardTransform;

        private AlignedFace(Bitmap bitmap, float[] forwardTransform) {
            this.bitmap = bitmap;
            this.forwardTransform = Arrays.copyOf(forwardTransform, forwardTransform.length);
        }

        public Bitmap getBitmap() {
            return bitmap;
        }

        public float[] getForwardTransform() {
            return Arrays.copyOf(forwardTransform, forwardTransform.length);
        }
    }
}

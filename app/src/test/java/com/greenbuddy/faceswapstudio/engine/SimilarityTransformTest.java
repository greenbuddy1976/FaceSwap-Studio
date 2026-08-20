package com.greenbuddy.faceswapstudio.engine;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class SimilarityTransformTest {
    @Test
    public void estimatesScaleRotationAndTranslation() {
        float[][] source = {
            { 10f, 20f },
            { 30f, 20f },
            { 20f, 30f },
            { 13f, 40f },
            { 27f, 40f }
        };
        float angle = (float) Math.toRadians(17.0);
        float scale = 1.35f;
        float a = (float) (Math.cos(angle) * scale);
        float b = (float) (Math.sin(angle) * scale);
        float[][] destination = new float[source.length][2];
        for (int i = 0; i < source.length; i++) {
            destination[i][0] = a * source[i][0] - b * source[i][1] + 42f;
            destination[i][1] = b * source[i][0] + a * source[i][1] - 11f;
        }

        float[] transform = SimilarityTransform.estimate(source, destination);
        for (int i = 0; i < source.length; i++) {
            float[] actual = SimilarityTransform.apply(transform, source[i][0], source[i][1]);
            assertEquals(destination[i][0], actual[0], 0.001f);
            assertEquals(destination[i][1], actual[1], 0.001f);
        }
    }

    @Test
    public void inverseRoundTripIsStable() {
        float[] transform = { 1.2f, -0.3f, 7f, 0.3f, 1.2f, -5f };
        float[] inverse = SimilarityTransform.invert(transform);
        float[] mapped = SimilarityTransform.apply(transform, 33f, 91f);
        float[] restored = SimilarityTransform.apply(inverse, mapped[0], mapped[1]);
        assertEquals(33f, restored[0], 0.001f);
        assertEquals(91f, restored[1], 0.001f);
    }
}

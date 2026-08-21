package com.greenbuddy.faceswapstudio.video;

/** Central limits for long-form on-device video processing. */
public final class VideoLimits {
    public static final int MAX_DURATION_MINUTES = 15;

    static final long MAX_DURATION_US = MAX_DURATION_MINUTES * 60L * 1_000_000L;
    static final int MAX_OUTPUT_FRAME_RATE = 12;
    static final int MIN_OUTPUT_FRAME_RATE = 2;
    static final int MAX_SWAP_INFERENCES = 1_200;

    private VideoLimits() {
    }

    static boolean supportsDuration(long durationUs) {
        return durationUs > 0L && durationUs <= MAX_DURATION_US;
    }

    static int chooseOutputFrameRate(float inputFrameRate) {
        if (!Float.isFinite(inputFrameRate) || inputFrameRate <= 0f) {
            return MAX_OUTPUT_FRAME_RATE;
        }
        return Math.max(
            MIN_OUTPUT_FRAME_RATE,
            Math.min(MAX_OUTPUT_FRAME_RATE, Math.round(inputFrameRate))
        );
    }

    static int chooseSwapInterval(int outputFrameCount) {
        if (outputFrameCount <= 0) {
            throw new IllegalArgumentException("outputFrameCount must be positive");
        }
        return Math.max(
            1,
            (outputFrameCount + MAX_SWAP_INFERENCES - 1) / MAX_SWAP_INFERENCES
        );
    }

    static int estimatedSwapInferences(int outputFrameCount) {
        int interval = chooseSwapInterval(outputFrameCount);
        return (outputFrameCount + interval - 1) / interval;
    }
}

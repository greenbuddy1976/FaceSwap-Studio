package com.greenbuddy.faceswapstudio.engine;

public final class ProgressPlan {
    public static final int PREPARING = 2;
    public static final int VIDEO_OPENED = 8;
    public static final int SOURCE_FACE_FOUND = 14;
    public static final int SOURCE_EMBEDDED = 24;
    public static final int VIDEO_PROCESSING_START = 30;
    public static final int VIDEO_PROCESSING_END = 90;
    public static final int VIDEO_ENCODED = 94;
    public static final int AUDIO_MUXED = 97;
    public static final int SAVED = 100;

    private ProgressPlan() {
    }

    public static int videoFrameProgress(int index, int frameCount) {
        if (frameCount <= 1) {
            return VIDEO_PROCESSING_END;
        }
        int safeIndex = Math.max(0, Math.min(frameCount - 1, index));
        int span = VIDEO_PROCESSING_END - VIDEO_PROCESSING_START;
        return VIDEO_PROCESSING_START + (safeIndex * span / (frameCount - 1));
    }

    public static int[] orderedStages() {
        return new int[] {
            PREPARING,
            VIDEO_OPENED,
            SOURCE_FACE_FOUND,
            SOURCE_EMBEDDED,
            VIDEO_PROCESSING_START,
            VIDEO_PROCESSING_END,
            VIDEO_ENCODED,
            AUDIO_MUXED,
            SAVED
        };
    }
}

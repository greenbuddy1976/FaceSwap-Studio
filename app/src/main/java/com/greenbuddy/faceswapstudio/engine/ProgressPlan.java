package com.greenbuddy.faceswapstudio.engine;

public final class ProgressPlan {
    public static final int PREPARING = 2;
    public static final int IMAGES_LOADED = 8;
    public static final int SOURCE_FACE_FOUND = 20;
    public static final int SOURCE_EMBEDDED = 38;
    public static final int TARGET_FACE_FOUND = 55;
    public static final int SWAP_MODEL_READY = 70;
    public static final int SWAP_COMPLETE = 84;
    public static final int BLENDED = 94;
    public static final int SAVED = 100;

    private ProgressPlan() {
    }

    public static int[] orderedStages() {
        return new int[] {
            PREPARING,
            IMAGES_LOADED,
            SOURCE_FACE_FOUND,
            SOURCE_EMBEDDED,
            TARGET_FACE_FOUND,
            SWAP_MODEL_READY,
            SWAP_COMPLETE,
            BLENDED,
            SAVED
        };
    }
}

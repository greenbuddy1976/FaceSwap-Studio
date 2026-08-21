package com.greenbuddy.faceswapstudio.service;

public final class InferenceContract {
    public static final String ACTION_START = "com.greenbuddy.faceswapstudio.action.START";
    public static final String ACTION_CANCEL = "com.greenbuddy.faceswapstudio.action.CANCEL";

    public static final String EXTRA_JOB_ID = "job_id";
    public static final String EXTRA_FACE_PATH = "face_path";
    public static final String EXTRA_VIDEO_PATH = "video_path";
    public static final String EXTRA_OUTPUT_PATH = "output_path";
    public static final String EXTRA_RECEIVER = "receiver";

    public static final String DATA_PROGRESS = "progress";
    public static final String DATA_MESSAGE = "message";
    public static final String DATA_OUTPUT_PATH = "output_path";

    public static final int RESULT_PROGRESS = 1;
    public static final int RESULT_SUCCESS = 2;
    public static final int RESULT_ERROR = 3;
    public static final int RESULT_CANCELLED = 4;

    private InferenceContract() {
    }
}

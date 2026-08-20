package com.greenbuddy.faceswapstudio.ui;

public final class ProcessingState {
    public enum Mode {
        READY,
        PREPARING,
        RUNNING,
        SUCCESS,
        ERROR,
        CANCELLED
    }

    private final Mode mode;
    private final int progress;
    private final String message;
    private final String outputPath;

    private ProcessingState(Mode mode, int progress, String message, String outputPath) {
        this.mode = mode;
        this.progress = progress;
        this.message = message;
        this.outputPath = outputPath;
    }

    public static ProcessingState ready(String message) {
        return new ProcessingState(Mode.READY, 0, message, null);
    }

    public static ProcessingState preparing(String message) {
        return new ProcessingState(Mode.PREPARING, 2, message, null);
    }

    public static ProcessingState running(int progress, String message) {
        return new ProcessingState(Mode.RUNNING, progress, message, null);
    }

    public static ProcessingState success(String outputPath) {
        return new ProcessingState(Mode.SUCCESS, 100, "Fertig · Ergebnis kann gespeichert werden", outputPath);
    }

    public static ProcessingState error(String message) {
        return new ProcessingState(Mode.ERROR, 0, message, null);
    }

    public static ProcessingState cancelled() {
        return new ProcessingState(Mode.CANCELLED, 0, "Verarbeitung abgebrochen.", null);
    }

    public Mode getMode() {
        return mode;
    }

    public int getProgress() {
        return progress;
    }

    public String getMessage() {
        return message;
    }

    public String getOutputPath() {
        return outputPath;
    }

    public boolean isBusy() {
        return mode == Mode.PREPARING || mode == Mode.RUNNING;
    }
}

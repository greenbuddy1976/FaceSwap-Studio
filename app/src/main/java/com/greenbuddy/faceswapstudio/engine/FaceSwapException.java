package com.greenbuddy.faceswapstudio.engine;

public class FaceSwapException extends Exception {
    public FaceSwapException(String message) {
        super(message);
    }

    public FaceSwapException(String message, Throwable cause) {
        super(message, cause);
    }
}

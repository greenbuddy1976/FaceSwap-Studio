package com.greenbuddy.faceswapstudio.engine;

import android.graphics.PointF;
import android.graphics.Rect;

public final class DetectedFace {
    private final Rect boundingBox;
    private final PointF[] landmarks;

    public DetectedFace(Rect boundingBox, PointF[] landmarks) {
        if (landmarks == null || landmarks.length != 5) {
            throw new IllegalArgumentException("Exactly five face landmarks are required.");
        }
        this.boundingBox = new Rect(boundingBox);
        this.landmarks = new PointF[landmarks.length];
        for (int i = 0; i < landmarks.length; i++) {
            this.landmarks[i] = new PointF(landmarks[i].x, landmarks[i].y);
        }
    }

    public Rect getBoundingBox() {
        return new Rect(boundingBox);
    }

    public PointF[] getLandmarks() {
        PointF[] copy = new PointF[landmarks.length];
        for (int i = 0; i < landmarks.length; i++) {
            copy[i] = new PointF(landmarks[i].x, landmarks[i].y);
        }
        return copy;
    }
}

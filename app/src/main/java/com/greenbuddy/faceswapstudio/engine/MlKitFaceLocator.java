package com.greenbuddy.faceswapstudio.engine;

import android.graphics.Bitmap;
import android.graphics.PointF;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.face.FaceLandmark;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class MlKitFaceLocator implements AutoCloseable {
    private final FaceDetector detector;

    public MlKitFaceLocator() {
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setMinFaceSize(0.08f)
            .build();
        detector = FaceDetection.getClient(options);
    }

    public DetectedFace findLargest(Bitmap bitmap, String imageLabel) throws FaceSwapException {
        try {
            List<Face> faces = Tasks.await(
                detector.process(InputImage.fromBitmap(bitmap, 0)),
                60,
                TimeUnit.SECONDS
            );
            Face face = faces.stream()
                .max(Comparator.comparingLong(item ->
                    (long) item.getBoundingBox().width() * item.getBoundingBox().height()))
                .orElseThrow(() -> new FaceSwapException(
                    "Im " + imageLabel + " wurde kein Gesicht erkannt. Bitte ein klareres Bild wählen."
                ));

            PointF firstEye = require(face, FaceLandmark.LEFT_EYE, imageLabel);
            PointF secondEye = require(face, FaceLandmark.RIGHT_EYE, imageLabel);
            PointF firstMouth = require(face, FaceLandmark.MOUTH_LEFT, imageLabel);
            PointF secondMouth = require(face, FaceLandmark.MOUTH_RIGHT, imageLabel);
            PointF[] landmarks = new PointF[] {
                leftmost(firstEye, secondEye),
                rightmost(firstEye, secondEye),
                require(face, FaceLandmark.NOSE_BASE, imageLabel),
                leftmost(firstMouth, secondMouth),
                rightmost(firstMouth, secondMouth)
            };
            return new DetectedFace(face.getBoundingBox(), landmarks);
        } catch (FaceSwapException error) {
            throw error;
        } catch (Exception error) {
            throw new FaceSwapException(
                "Die Gesichtserkennung im " + imageLabel + " ist fehlgeschlagen.",
                error
            );
        }
    }

    private static PointF require(Face face, int type, String imageLabel) throws FaceSwapException {
        FaceLandmark landmark = face.getLandmark(type);
        if (landmark == null) {
            throw new FaceSwapException(
                "Das Gesicht im " + imageLabel + " ist zu stark gedreht oder teilweise verdeckt."
            );
        }
        PointF point = landmark.getPosition();
        return new PointF(point.x, point.y);
    }

    private static PointF leftmost(PointF first, PointF second) {
        PointF selected = first.x <= second.x ? first : second;
        return new PointF(selected.x, selected.y);
    }

    private static PointF rightmost(PointF first, PointF second) {
        PointF selected = first.x > second.x ? first : second;
        return new PointF(selected.x, selected.y);
    }

    @Override
    public void close() {
        detector.close();
    }
}

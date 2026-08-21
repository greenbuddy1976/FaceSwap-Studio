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
            Face face = detectLargest(bitmap);
            if (face == null) {
                throw new FaceSwapException(
                    "Im " + imageLabel + " wurde kein Gesicht erkannt. Bitte ein klareres Bild wählen."
                );
            }
            return convert(face, imageLabel, true);
        } catch (FaceSwapException error) {
            throw error;
        } catch (Exception error) {
            throw new FaceSwapException(
                "Die Gesichtserkennung im " + imageLabel + " ist fehlgeschlagen.",
                error
            );
        }
    }

    /** Returns null when a video frame has no complete, usable face landmarks. */
    public DetectedFace findLargestOrNull(Bitmap bitmap) throws FaceSwapException {
        try {
            Face face = detectLargest(bitmap);
            return face == null ? null : convert(face, "Videobild", false);
        } catch (FaceSwapException error) {
            throw error;
        } catch (Exception error) {
            throw new FaceSwapException("Die Gesichtserkennung in einem Videobild ist fehlgeschlagen.", error);
        }
    }

    private Face detectLargest(Bitmap bitmap) throws Exception {
        List<Face> faces = Tasks.await(
            detector.process(InputImage.fromBitmap(bitmap, 0)),
            60,
            TimeUnit.SECONDS
        );
        return faces.stream()
            .max(Comparator.comparingLong(item ->
                (long) item.getBoundingBox().width() * item.getBoundingBox().height()))
            .orElse(null);
    }

    private static DetectedFace convert(Face face, String imageLabel, boolean strict)
        throws FaceSwapException {
        PointF firstEye = landmark(face, FaceLandmark.LEFT_EYE);
        PointF secondEye = landmark(face, FaceLandmark.RIGHT_EYE);
        PointF nose = landmark(face, FaceLandmark.NOSE_BASE);
        PointF firstMouth = landmark(face, FaceLandmark.MOUTH_LEFT);
        PointF secondMouth = landmark(face, FaceLandmark.MOUTH_RIGHT);
        if (firstEye == null || secondEye == null || nose == null
            || firstMouth == null || secondMouth == null) {
            if (strict) {
                throw new FaceSwapException(
                    "Das Gesicht im " + imageLabel + " ist zu stark gedreht oder teilweise verdeckt."
                );
            }
            return null;
        }
        PointF[] landmarks = new PointF[] {
            leftmost(firstEye, secondEye),
            rightmost(firstEye, secondEye),
            nose,
            leftmost(firstMouth, secondMouth),
            rightmost(firstMouth, secondMouth)
        };
        return new DetectedFace(face.getBoundingBox(), landmarks);
    }

    private static PointF landmark(Face face, int type) {
        FaceLandmark landmark = face.getLandmark(type);
        if (landmark == null) {
            return null;
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

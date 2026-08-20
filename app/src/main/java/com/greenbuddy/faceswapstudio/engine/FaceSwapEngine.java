package com.greenbuddy.faceswapstudio.engine;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public final class FaceSwapEngine {
    private static final long QUICK_STAGE_TIMEOUT_MS = 90_000L;
    private static final long MODEL_STAGE_TIMEOUT_MS = 360_000L;

    private final Context context;

    public FaceSwapEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    public void run(File sourceFile, File targetFile, File outputFile, ProgressSink progress)
        throws FaceSwapException {
        Bitmap source = null;
        Bitmap target = null;
        Bitmap sourceAligned = null;
        Bitmap targetAligned = null;
        Bitmap result = null;

        try {
            progress.update(ProgressPlan.PREPARING, "Bilder werden sicher vorbereitet …", QUICK_STAGE_TIMEOUT_MS);
            source = BitmapLoader.decode(sourceFile, 1024);
            target = BitmapLoader.decode(targetFile, 2048);
            progress.update(ProgressPlan.IMAGES_LOADED, "Bilder geladen · Gesichtserkennung startet", QUICK_STAGE_TIMEOUT_MS);

            final DetectedFace sourceFace;
            final DetectedFace targetFace;
            try (MlKitFaceLocator locator = new MlKitFaceLocator()) {
                sourceFace = locator.findLargest(source, "Quellbild");
                progress.update(
                    ProgressPlan.SOURCE_FACE_FOUND,
                    "Quellgesicht erkannt · Identität wird berechnet",
                    MODEL_STAGE_TIMEOUT_MS
                );

                ImageTransforms.AlignedFace alignedSource = ImageTransforms.alignForEmbedding(
                    source,
                    sourceFace.getLandmarks()
                );
                sourceAligned = alignedSource.getBitmap();
                float[] embedding = new FaceEmbedder(context.getAssets()).embed(sourceAligned);
                progress.update(
                    ProgressPlan.SOURCE_EMBEDDED,
                    "Gesichtsprofil fertig · Zielgesicht wird gesucht",
                    QUICK_STAGE_TIMEOUT_MS
                );

                targetFace = locator.findLargest(target, "Zielbild");
                progress.update(
                    ProgressPlan.TARGET_FACE_FOUND,
                    "Zielgesicht erkannt · Face-Swap-Modell startet",
                    MODEL_STAGE_TIMEOUT_MS
                );

                ImageTransforms.AlignedFace alignedTarget = ImageTransforms.alignForSwap(
                    target,
                    targetFace.getLandmarks()
                );
                targetAligned = alignedTarget.getBitmap();
                progress.update(
                    ProgressPlan.SWAP_MODEL_READY,
                    "KI-Modell arbeitet · das kann auf CPU einige Minuten dauern",
                    MODEL_STAGE_TIMEOUT_MS
                );

                float[] swapped = new FaceSwapper(context.getAssets()).swap(targetAligned, embedding);
                progress.update(
                    ProgressPlan.SWAP_COMPLETE,
                    "Face-Swap berechnet · Übergänge werden angepasst",
                    QUICK_STAGE_TIMEOUT_MS
                );

                result = ImageTransforms.blendSwap(
                    target,
                    targetAligned,
                    swapped,
                    alignedTarget.getForwardTransform()
                );
            }

            progress.update(ProgressPlan.BLENDED, "Übergänge fertig · Ergebnis wird gespeichert", QUICK_STAGE_TIMEOUT_MS);
            saveJpeg(result, outputFile);
            progress.update(ProgressPlan.SAVED, "Fertig · Ergebnis kann gespeichert werden", QUICK_STAGE_TIMEOUT_MS);
        } catch (FaceSwapException error) {
            throw error;
        } catch (OutOfMemoryError error) {
            throw new FaceSwapException(
                "Der Gerätespeicher ist voll. Andere Apps schließen und mit kleineren Bildern erneut versuchen.",
                error
            );
        } catch (RuntimeException error) {
            throw new FaceSwapException("Die Bildverarbeitung ist unerwartet fehlgeschlagen.", error);
        } finally {
            recycle(sourceAligned);
            recycle(targetAligned);
            recycle(result);
            recycle(source);
            recycle(target);
        }
    }

    private static void saveJpeg(Bitmap bitmap, File outputFile) throws FaceSwapException {
        File parent = outputFile.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new FaceSwapException("Der Ergebnisordner konnte nicht erstellt werden.");
        }
        try (FileOutputStream stream = new FileOutputStream(outputFile)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) {
                throw new FaceSwapException("Android konnte das Ergebnis nicht als JPEG speichern.");
            }
            stream.flush();
        } catch (IOException error) {
            throw new FaceSwapException("Das Ergebnis konnte nicht gespeichert werden.", error);
        }
        if (!outputFile.isFile() || outputFile.length() < 10_000L) {
            throw new FaceSwapException("Die erzeugte Ergebnisdatei ist unvollständig.");
        }
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    public interface ProgressSink {
        void update(int percent, String message, long timeoutMillis) throws FaceSwapException;
    }
}

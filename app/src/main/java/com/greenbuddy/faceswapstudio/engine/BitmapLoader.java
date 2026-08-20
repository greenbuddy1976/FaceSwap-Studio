package com.greenbuddy.faceswapstudio.engine;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.IOException;

public final class BitmapLoader {
    private BitmapLoader() {
    }

    public static Bitmap decode(File file, int maximumLongEdge) throws FaceSwapException {
        if (file == null || !file.isFile() || file.length() == 0) {
            throw new FaceSwapException("Eine ausgewählte Bilddatei fehlt oder ist leer.");
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new FaceSwapException("Die Bilddatei konnte nicht gelesen werden.");
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, maximumLongEdge * 2);

        Bitmap decoded;
        try {
            decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (OutOfMemoryError error) {
            throw new FaceSwapException("Das Bild ist zu groß für den verfügbaren Gerätespeicher.", error);
        }
        if (decoded == null) {
            throw new FaceSwapException("Das Bildformat wird von Android nicht unterstützt.");
        }

        Bitmap oriented = applyExif(decoded, file);
        if (oriented != decoded) {
            decoded.recycle();
        }

        int longEdge = Math.max(oriented.getWidth(), oriented.getHeight());
        if (longEdge <= maximumLongEdge) {
            return oriented;
        }
        float scale = maximumLongEdge / (float) longEdge;
        int width = Math.max(1, Math.round(oriented.getWidth() * scale));
        int height = Math.max(1, Math.round(oriented.getHeight() * scale));
        Bitmap resized = Bitmap.createScaledBitmap(oriented, width, height, true);
        if (resized != oriented) {
            oriented.recycle();
        }
        return resized;
    }

    private static int calculateSampleSize(int width, int height, int targetLongEdge) {
        int sample = 1;
        int longEdge = Math.max(width, height);
        while (longEdge / (sample * 2) >= targetLongEdge) {
            sample *= 2;
        }
        return sample;
    }

    private static Bitmap applyExif(Bitmap source, File file) throws FaceSwapException {
        final int orientation;
        try {
            ExifInterface exif = new ExifInterface(file);
            orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            );
        } catch (IOException error) {
            throw new FaceSwapException("Die Bildausrichtung konnte nicht gelesen werden.", error);
        }

        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180f);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setScale(1f, -1f);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90f);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(-90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(-90f);
                break;
            default:
                return source;
        }

        try {
            return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
        } catch (OutOfMemoryError error) {
            throw new FaceSwapException("Das gedrehte Bild benötigt zu viel Speicher.", error);
        }
    }
}

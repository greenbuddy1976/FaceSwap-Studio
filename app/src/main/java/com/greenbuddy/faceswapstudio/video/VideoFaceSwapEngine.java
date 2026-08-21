package com.greenbuddy.faceswapstudio.video;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.os.Build;

import com.greenbuddy.faceswapstudio.engine.BitmapLoader;
import com.greenbuddy.faceswapstudio.engine.DetectedFace;
import com.greenbuddy.faceswapstudio.engine.FaceEmbedder;
import com.greenbuddy.faceswapstudio.engine.FaceSwapException;
import com.greenbuddy.faceswapstudio.engine.FaceSwapper;
import com.greenbuddy.faceswapstudio.engine.ImageTransforms;
import com.greenbuddy.faceswapstudio.engine.MlKitFaceLocator;
import com.greenbuddy.faceswapstudio.engine.ProgressPlan;

import java.io.File;

/** Offline photo-to-video face swap with H.264 MP4 output and untouched source audio. */
public final class VideoFaceSwapEngine {
    private static final int MAX_LONG_EDGE = 720;
    private static final int MAX_FRAME_RATE = 12;
    private static final int MIN_FRAME_RATE = 2;
    private static final int MAX_OUTPUT_FRAMES = 900;
    private static final long MAX_DURATION_US = 180_000_000L;
    private static final long FRAME_STAGE_TIMEOUT_MS = 120_000L;

    private final Context context;

    public VideoFaceSwapEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    public void run(File facePhoto, File inputVideo, File outputVideo, ProgressSink progress)
        throws FaceSwapException {
        Bitmap source = null;
        Bitmap sourceAligned = null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        File videoOnly = new File(outputVideo.getParentFile(), "faceswap-video-only.tmp.mp4");
        deleteIfPresent(videoOnly);
        deleteIfPresent(outputVideo);

        try (MlKitFaceLocator locator = new MlKitFaceLocator()) {
            progress.update(ProgressPlan.PREPARING, "Video wird geprüft …", FRAME_STAGE_TIMEOUT_MS);
            retriever.setDataSource(inputVideo.getAbsolutePath());
            VideoSpec spec = inspect(retriever, inputVideo);
            Bitmap firstRaw = loadFirstFrame(retriever, spec);
            if (firstRaw == null) {
                throw new FaceSwapException("Das erste Videobild konnte nicht gelesen werden.");
            }
            OrientationChoice orientation = chooseOrientation(firstRaw, spec.rotationDegrees, locator);
            Bitmap firstOriented = orientation.bitmap;
            if (firstOriented != firstRaw) {
                firstRaw.recycle();
            }
            Dimensions dimensions = fit(firstOriented.getWidth(), firstOriented.getHeight(), MAX_LONG_EDGE);
            Bitmap firstFrame = scale(firstOriented, dimensions.width, dimensions.height);
            if (firstFrame != firstOriented) {
                firstOriented.recycle();
            }

            int frameRate = chooseFrameRate(spec.frameRate, spec.durationUs);
            int frameCount = Math.max(
                1,
                (int) Math.ceil(spec.durationUs * frameRate / 1_000_000d)
            );
            progress.update(
                ProgressPlan.VIDEO_OPENED,
                "Video geöffnet · " + frameCount + " Bilder werden verarbeitet",
                FRAME_STAGE_TIMEOUT_MS
            );

            source = BitmapLoader.decode(facePhoto, 1024);
            DetectedFace sourceFace = locator.findLargest(source, "Gesichtsfoto");
            progress.update(
                ProgressPlan.SOURCE_FACE_FOUND,
                "Gesicht erkannt · Identität wird einmalig berechnet",
                FRAME_STAGE_TIMEOUT_MS
            );
            ImageTransforms.AlignedFace aligned = ImageTransforms.alignForEmbedding(
                source,
                sourceFace.getLandmarks()
            );
            sourceAligned = aligned.getBitmap();
            float[] embedding = new FaceEmbedder(context.getAssets()).embed(sourceAligned);
            progress.update(
                ProgressPlan.SOURCE_EMBEDDED,
                "Gesichtsprofil fertig · Video-Face-Swap startet",
                FRAME_STAGE_TIMEOUT_MS
            );

            int swappedFrames = 0;
            try (
                FaceSwapper swapper = new FaceSwapper(context.getAssets());
                AvcBitmapEncoder encoder = new AvcBitmapEncoder(
                    videoOnly,
                    dimensions.width,
                    dimensions.height,
                    frameRate
                )
            ) {
                for (int index = 0; index < frameCount; index++) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new FaceSwapException("Verarbeitung abgebrochen.");
                    }
                    long timeUs = Math.min(
                        spec.durationUs - 1L,
                        Math.round(index * 1_000_000d / frameRate)
                    );
                    int percent = ProgressPlan.videoFrameProgress(index, frameCount);
                    progress.update(
                        percent,
                        "Videobild " + (index + 1) + " von " + frameCount + " · Gesicht wird ersetzt",
                        FRAME_STAGE_TIMEOUT_MS
                    );

                    Bitmap frame = index == 0
                        ? firstFrame
                        : loadFrame(
                            retriever,
                            timeUs,
                            dimensions,
                            orientation.rotationApplied
                        );
                    Bitmap processed = frame;
                    try {
                        DetectedFace targetFace = locator.findLargestOrNull(frame);
                        if (targetFace != null) {
                            ImageTransforms.AlignedFace targetAligned = ImageTransforms.alignForSwap(
                                frame,
                                targetFace.getLandmarks()
                            );
                            Bitmap crop = targetAligned.getBitmap();
                            try {
                                float[] swapped = swapper.swap(crop, embedding);
                                processed = ImageTransforms.blendSwap(
                                    frame,
                                    crop,
                                    swapped,
                                    targetAligned.getForwardTransform()
                                );
                                swappedFrames++;
                            } finally {
                                crop.recycle();
                            }
                        }
                        encoder.encode(processed, timeUs);
                    } finally {
                        if (processed != frame && !processed.isRecycled()) {
                            processed.recycle();
                        }
                        if (!frame.isRecycled()) {
                            frame.recycle();
                        }
                    }
                }
                if (swappedFrames == 0) {
                    throw new FaceSwapException(
                        "Im Video wurde kein ausreichend sichtbares Gesicht gefunden."
                    );
                }
                encoder.finish(spec.durationUs);
            }

            progress.update(
                ProgressPlan.VIDEO_ENCODED,
                "Face-Swap fertig · Originalton wird übernommen",
                FRAME_STAGE_TIMEOUT_MS
            );
            boolean audioPreserved = Mp4AudioMuxer.mux(videoOnly, inputVideo, outputVideo);
            progress.update(
                ProgressPlan.AUDIO_MUXED,
                audioPreserved
                    ? "Originalton übernommen · MP4 wird geprüft"
                    : "Video hatte keinen Originalton · MP4 wird geprüft",
                FRAME_STAGE_TIMEOUT_MS
            );
            validateOutput(outputVideo, audioPreserved);
            progress.update(
                ProgressPlan.SAVED,
                "Fertig · Face-Swap-Video kann gespeichert werden",
                FRAME_STAGE_TIMEOUT_MS
            );
        } catch (FaceSwapException error) {
            deleteIfPresent(outputVideo);
            throw error;
        } catch (OutOfMemoryError error) {
            deleteIfPresent(outputVideo);
            throw new FaceSwapException(
                "Zu wenig Gerätespeicher für dieses Video. Andere Apps schließen und erneut versuchen.",
                error
            );
        } catch (RuntimeException error) {
            deleteIfPresent(outputVideo);
            throw new FaceSwapException("Die Videoverarbeitung ist unerwartet fehlgeschlagen.", error);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // Best-effort framework cleanup.
            }
            deleteIfPresent(videoOnly);
            recycle(sourceAligned);
            recycle(source);
        }
    }

    private static VideoSpec inspect(MediaMetadataRetriever retriever, File inputVideo)
        throws FaceSwapException {
        long durationMs = parseLong(
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION),
            -1L
        );
        if (durationMs <= 0L) {
            throw new FaceSwapException("Das ausgewählte Video besitzt keine gültige Dauer.");
        }
        long durationUs = durationMs * 1_000L;
        if (durationUs > MAX_DURATION_US) {
            throw new FaceSwapException("Das Video darf höchstens drei Minuten lang sein.");
        }
        int rotation = normalizeRotation((int) parseLong(
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),
            0L
        ));
        float metadataFrameRate = parseFloat(
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE),
            -1f
        );
        float trackFrameRate = readTrackFrameRate(inputVideo);
        float frameRate = metadataFrameRate > 0f ? metadataFrameRate : trackFrameRate;
        if (!Float.isFinite(frameRate) || frameRate <= 0f) {
            frameRate = MAX_FRAME_RATE;
        }
        int width = (int) parseLong(
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
            -1L
        );
        int height = (int) parseLong(
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
            -1L
        );
        return new VideoSpec(durationUs, rotation, frameRate, width, height);
    }

    private static Bitmap loadFirstFrame(MediaMetadataRetriever retriever, VideoSpec spec) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
            && spec.width > 0 && spec.height > 0) {
            Dimensions preview = fit(spec.width, spec.height, MAX_LONG_EDGE);
            return retriever.getScaledFrameAtTime(
                0L,
                MediaMetadataRetriever.OPTION_CLOSEST,
                preview.width,
                preview.height
            );
        }
        return retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST);
    }

    private static float readTrackFrameRate(File inputVideo) throws FaceSwapException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(inputVideo.getAbsolutePath());
            for (int index = 0; index < extractor.getTrackCount(); index++) {
                MediaFormat format = extractor.getTrackFormat(index);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    return format.containsKey(MediaFormat.KEY_FRAME_RATE)
                        ? format.getInteger(MediaFormat.KEY_FRAME_RATE)
                        : -1f;
                }
            }
            throw new FaceSwapException("Die ausgewählte Datei enthält keine Videospur.");
        } catch (FaceSwapException error) {
            throw error;
        } catch (Exception error) {
            throw new FaceSwapException("Die Videospur konnte nicht gelesen werden.", error);
        } finally {
            extractor.release();
        }
    }

    private static OrientationChoice chooseOrientation(
        Bitmap frame,
        int rotation,
        MlKitFaceLocator locator
    ) throws FaceSwapException {
        if (rotation == 0 || locator.findLargestOrNull(frame) != null) {
            return new OrientationChoice(frame, 0);
        }
        Bitmap rotated = rotate(frame, rotation);
        if (locator.findLargestOrNull(rotated) != null) {
            return new OrientationChoice(rotated, rotation);
        }
        rotated.recycle();
        return new OrientationChoice(frame, 0);
    }

    private static Bitmap loadFrame(
        MediaMetadataRetriever retriever,
        long timeUs,
        Dimensions output,
        int rotation
    ) throws FaceSwapException {
        int requestedWidth = rotation == 90 || rotation == 270 ? output.height : output.width;
        int requestedHeight = rotation == 90 || rotation == 270 ? output.width : output.height;
        Bitmap raw;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            raw = retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST,
                requestedWidth,
                requestedHeight
            );
        } else {
            raw = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
        }
        if (raw == null) {
            throw new FaceSwapException("Ein Videobild bei " + (timeUs / 1_000L) + " ms fehlt.");
        }
        Bitmap oriented = rotation == 0 ? raw : rotate(raw, rotation);
        if (oriented != raw) {
            raw.recycle();
        }
        Bitmap scaled = scale(oriented, output.width, output.height);
        if (scaled != oriented) {
            oriented.recycle();
        }
        return scaled;
    }

    private static Bitmap rotate(Bitmap source, int degrees) {
        Matrix matrix = new Matrix();
        matrix.setRotate(degrees);
        return Bitmap.createBitmap(
            source,
            0,
            0,
            source.getWidth(),
            source.getHeight(),
            matrix,
            true
        );
    }

    private static Bitmap scale(Bitmap source, int width, int height) {
        if (source.getWidth() == width && source.getHeight() == height) {
            return source;
        }
        return Bitmap.createScaledBitmap(source, width, height, true);
    }

    private static Dimensions fit(int width, int height, int maximumLongEdge) {
        double scale = Math.min(1d, maximumLongEdge / (double) Math.max(width, height));
        int outputWidth = even(Math.max(2, (int) Math.round(width * scale)));
        int outputHeight = even(Math.max(2, (int) Math.round(height * scale)));
        return new Dimensions(outputWidth, outputHeight);
    }

    private static int chooseFrameRate(float inputFrameRate, long durationUs) {
        int requested = Math.max(
            MIN_FRAME_RATE,
            Math.min(MAX_FRAME_RATE, Math.round(inputFrameRate))
        );
        int durationLimited = (int) Math.floor(MAX_OUTPUT_FRAMES * 1_000_000d / durationUs);
        return Math.max(MIN_FRAME_RATE, Math.min(requested, durationLimited));
    }

    private static void validateOutput(File output, boolean audioExpected) throws FaceSwapException {
        MediaExtractor extractor = new MediaExtractor();
        boolean video = false;
        boolean audio = false;
        try {
            extractor.setDataSource(output.getAbsolutePath());
            for (int index = 0; index < extractor.getTrackCount(); index++) {
                String mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME);
                video |= mime != null && mime.startsWith("video/");
                audio |= mime != null && mime.startsWith("audio/");
            }
        } catch (Exception error) {
            throw new FaceSwapException("Das fertige MP4 konnte nicht erneut geöffnet werden.", error);
        } finally {
            extractor.release();
        }
        if (!video || (audioExpected && !audio)) {
            throw new FaceSwapException("Das fertige MP4 enthält nicht alle erforderlichen Spuren.");
        }
    }

    private static int normalizeRotation(int degrees) {
        int normalized = ((degrees % 360) + 360) % 360;
        return normalized == 90 || normalized == 180 || normalized == 270 ? normalized : 0;
    }

    private static int even(int value) {
        return value & ~1;
    }

    private static long parseLong(String value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static float parseFloat(String value, float fallback) {
        try {
            return value == null ? fallback : Float.parseFloat(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static void deleteIfPresent(File file) {
        if (file != null && file.exists()) {
            file.delete();
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

    private static final class VideoSpec {
        private final long durationUs;
        private final int rotationDegrees;
        private final float frameRate;
        private final int width;
        private final int height;

        private VideoSpec(
            long durationUs,
            int rotationDegrees,
            float frameRate,
            int width,
            int height
        ) {
            this.durationUs = durationUs;
            this.rotationDegrees = rotationDegrees;
            this.frameRate = frameRate;
            this.width = width;
            this.height = height;
        }
    }

    private static final class Dimensions {
        private final int width;
        private final int height;

        private Dimensions(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    private static final class OrientationChoice {
        private final Bitmap bitmap;
        private final int rotationApplied;

        private OrientationChoice(Bitmap bitmap, int rotationApplied) {
            this.bitmap = bitmap;
            this.rotationApplied = rotationApplied;
        }
    }
}

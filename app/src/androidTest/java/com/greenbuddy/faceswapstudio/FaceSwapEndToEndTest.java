package com.greenbuddy.faceswapstudio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import androidx.core.content.ContextCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.greenbuddy.faceswapstudio.engine.BitmapLoader;
import com.greenbuddy.faceswapstudio.engine.DetectedFace;
import com.greenbuddy.faceswapstudio.engine.FaceEmbedder;
import com.greenbuddy.faceswapstudio.engine.FaceSwapException;
import com.greenbuddy.faceswapstudio.engine.FaceSwapper;
import com.greenbuddy.faceswapstudio.engine.ImageTransforms;
import com.greenbuddy.faceswapstudio.engine.MlKitFaceLocator;
import com.greenbuddy.faceswapstudio.engine.ProgressPlan;
import com.greenbuddy.faceswapstudio.service.InferenceContract;
import com.greenbuddy.faceswapstudio.service.InferenceService;
import com.greenbuddy.faceswapstudio.video.VideoFaceSwapEngine;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public final class FaceSwapEndToEndTest {
    private static final String TAG = "FaceSwapE2E";
    private static final long FULL_VIDEO_LIMIT_MS = 240_000L;
    private static final long TERMINAL_WAIT_SECONDS = 300L;
    private static final long TEN_MINUTE_DURATION_MS = 600_000L;
    private static final long TEN_MINUTE_VIDEO_LIMIT_MS = 1_800_000L;
    private static final long TEN_MINUTE_TERMINAL_WAIT_SECONDS = 1_900L;

    @Test(timeout = 2_400_000L)
    public void videoFaceSwapPreservesAudioRejectsCorruptionAndCancels() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context tests = InstrumentationRegistry.getInstrumentation().getContext();
        Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
            new Intent(app, com.greenbuddy.faceswapstudio.ui.MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        assertNotNull("main activity did not launch", activity);
        assertInputOrder(activity);

        File root = new File(app.getCacheDir(), "verified-video-e2e");
        deleteRecursively(root);
        assertTrue(root.mkdirs());

        try {
            File face = copyAsset(
                tests,
                "generated_faces/source.jpg",
                new File(root, "source-face.jpg")
            );
            File video = copyAsset(
                tests,
                "generated_video/target-with-audio.mp4",
                new File(root, "target-with-audio.mp4")
            );
            verifyFaceInputs(face, video);
            verifyStableCpuFallback(app, face, video);

            File output = new File(root, "result.mp4");
            JobResult completed = runServiceJob(app, face, video, output, false);
            assertEquals(
                "isolated inference process must report success",
                InferenceContract.RESULT_SUCCESS,
                completed.code.get()
            );
            assertTrue(
                "full video face swap exceeded performance budget: " + completed.elapsedMs + " ms",
                completed.elapsedMs <= FULL_VIDEO_LIMIT_MS
            );
            assertHealthyProgress(completed);
            assertValidChangedMp4(video, output);
            assertAudioPayloadUnchanged(video, output);
            Log.i(TAG, "FACESWAP_VIDEO_METRIC isolated_service_ms=" + completed.elapsedMs);

            File corrupt = new File(root, "corrupt-video.mp4");
            try (FileOutputStream corruptOutput = new FileOutputStream(corrupt)) {
                corruptOutput.write("this-is-not-a-video".getBytes(StandardCharsets.UTF_8));
            }
            long errorStarted = SystemClock.elapsedRealtime();
            try {
                new VideoFaceSwapEngine(app).run(
                    face,
                    corrupt,
                    new File(root, "must-not-exist.mp4"),
                    (percent, message, timeout) -> { }
                );
                fail("corrupt video must fail with a user-facing error");
            } catch (FaceSwapException expected) {
                assertNotNull(expected.getMessage());
                assertFalse(expected.getMessage().isEmpty());
            }
            long errorElapsed = SystemClock.elapsedRealtime() - errorStarted;
            assertTrue("corrupt video was not rejected quickly", errorElapsed < 5_000L);
            Log.i(TAG, "FACESWAP_VIDEO_METRIC corrupt_rejection_ms=" + errorElapsed);

            SystemClock.sleep(1_500L);
            JobResult cancelled = runServiceJob(
                app,
                face,
                video,
                new File(root, "cancelled.mp4"),
                true
            );
            assertEquals(
                "cancel request must terminate the isolated process",
                InferenceContract.RESULT_CANCELLED,
                cancelled.code.get()
            );
            assertTrue(
                "cancel took too long: " + cancelled.elapsedMs + " ms",
                cancelled.elapsedMs < 15_000L
            );
            Log.i(TAG, "FACESWAP_VIDEO_METRIC cancellation_ms=" + cancelled.elapsedMs);

            SystemClock.sleep(1_500L);
            File tenMinuteVideo = copyAsset(
                tests,
                "generated_video/ten-minute-target-with-audio.mp4",
                new File(root, "ten-minute-target-with-audio.mp4")
            );
            assertTrue(
                "long-form fixture must be at least ten minutes",
                durationMs(tenMinuteVideo) >= TEN_MINUTE_DURATION_MS
            );
            verifyFaceInputs(face, tenMinuteVideo);
            File tenMinuteOutput = new File(root, "ten-minute-result.mp4");
            JobResult tenMinuteCompleted = runServiceJob(
                app,
                face,
                tenMinuteVideo,
                tenMinuteOutput,
                false,
                TEN_MINUTE_TERMINAL_WAIT_SECONDS
            );
            assertEquals(
                "ten-minute video must complete in the isolated inference process",
                InferenceContract.RESULT_SUCCESS,
                tenMinuteCompleted.code.get()
            );
            assertTrue(
                "ten-minute video processing exceeded its anti-hang gate: "
                    + tenMinuteCompleted.elapsedMs + " ms",
                tenMinuteCompleted.elapsedMs <= TEN_MINUTE_VIDEO_LIMIT_MS
            );
            assertHealthyProgress(tenMinuteCompleted);
            assertValidChangedMp4(tenMinuteVideo, tenMinuteOutput);
            assertFrameAreaChanged(tenMinuteVideo, tenMinuteOutput, 333_333L);
            assertAudioPayloadUnchanged(tenMinuteVideo, tenMinuteOutput);
            assertTrue(
                "ten-minute output was shortened",
                durationMs(tenMinuteOutput) >= TEN_MINUTE_DURATION_MS - 300L
            );
            Log.i(
                TAG,
                "FACESWAP_VIDEO_METRIC ten_minute_service_ms=" + tenMinuteCompleted.elapsedMs
            );
            Log.i(TAG, "FACESWAP_TEN_MINUTE_VIDEO_E2E_PASS");
            Log.i(TAG, "FACESWAP_VIDEO_E2E_FULL_PASS");
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish);
        }
    }

    private static void assertInputOrder(Activity activity) {
        View videoButton = activity.findViewById(R.id.videoButton);
        View faceButton = activity.findViewById(R.id.faceButton);
        View startButton = activity.findViewById(R.id.startButton);
        assertNotNull(videoButton);
        assertNotNull(faceButton);
        assertNotNull(startButton);
        assertTrue("video must be selectable first", videoButton.isEnabled());
        assertFalse("face picker must wait until a video is selected", faceButton.isEnabled());
        assertFalse("swap must not start without both inputs", startButton.isEnabled());
    }

    private static void verifyFaceInputs(File facePhoto, File video) throws Exception {
        Bitmap source = BitmapLoader.decode(facePhoto, 1024);
        Bitmap frame = firstFrame(video);
        try (MlKitFaceLocator locator = new MlKitFaceLocator()) {
            assertNotNull(locator.findLargest(source, "Gesichtsfoto"));
            assertNotNull(locator.findLargest(frame, "erstem Videobild"));
        } finally {
            source.recycle();
            frame.recycle();
        }
    }

    private static void verifyStableCpuFallback(Context app, File facePhoto, File video)
        throws Exception {
        Bitmap source = BitmapLoader.decode(facePhoto, 1024);
        Bitmap frame = firstFrame(video);
        Bitmap sourceAligned = null;
        Bitmap targetAligned = null;
        try (MlKitFaceLocator locator = new MlKitFaceLocator()) {
            DetectedFace sourceFace = locator.findLargest(source, "Gesichtsfoto");
            DetectedFace targetFace = locator.findLargest(frame, "erstem Videobild");
            sourceAligned = ImageTransforms.alignForEmbedding(
                source,
                sourceFace.getLandmarks()
            ).getBitmap();
            targetAligned = ImageTransforms.alignForSwap(
                frame,
                targetFace.getLandmarks()
            ).getBitmap();

            float[] embedding = new FaceEmbedder(app.getAssets(), true).embed(sourceAligned);
            assertFinite("CPU ArcFace", embedding, 512);
            try (FaceSwapper swapper = new FaceSwapper(app.getAssets(), true)) {
                float[] swapped = swapper.swap(targetAligned, embedding);
                assertFinite("CPU INSwapper", swapped, 3 * 128 * 128);
            }
            Log.i(TAG, "FACESWAP_STABLE_CPU_FALLBACK_INFERENCE_PASS");
        } finally {
            if (targetAligned != null) {
                targetAligned.recycle();
            }
            if (sourceAligned != null) {
                sourceAligned.recycle();
            }
            source.recycle();
            frame.recycle();
        }
    }

    private static void assertFinite(String label, float[] values, int expectedLength) {
        assertEquals(label + " output length", expectedLength, values.length);
        for (float value : values) {
            assertTrue(label + " produced a non-finite value", Float.isFinite(value));
        }
    }

    private static JobResult runServiceJob(
        Context app,
        File face,
        File video,
        File output,
        boolean cancelAfterFirstProgress
    ) throws Exception {
        return runServiceJob(
            app,
            face,
            video,
            output,
            cancelAfterFirstProgress,
            TERMINAL_WAIT_SECONDS
        );
    }

    private static JobResult runServiceJob(
        Context app,
        File face,
        File video,
        File output,
        boolean cancelAfterFirstProgress,
        long terminalWaitSeconds
    ) throws Exception {
        CountDownLatch terminal = new CountDownLatch(1);
        CountDownLatch firstProgress = new CountDownLatch(1);
        JobResult result = new JobResult();
        String jobId = UUID.randomUUID().toString();
        long started = SystemClock.elapsedRealtime();

        ResultReceiver receiver = new ResultReceiver(new Handler(Looper.getMainLooper())) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle data) {
                long now = SystemClock.elapsedRealtime();
                int progress = data.getInt(InferenceContract.DATA_PROGRESS, 0);
                if (resultCode == InferenceContract.RESULT_PROGRESS) {
                    result.progress.add(progress);
                    result.eventTimes.add(now);
                    firstProgress.countDown();
                    return;
                }
                result.code.set(resultCode);
                result.message = data.getString(InferenceContract.DATA_MESSAGE, "");
                result.elapsedMs = now - started;
                terminal.countDown();
            }
        };

        Intent start = new Intent(app, InferenceService.class)
            .setAction(InferenceContract.ACTION_START)
            .putExtra(InferenceContract.EXTRA_JOB_ID, jobId)
            .putExtra(InferenceContract.EXTRA_FACE_PATH, face.getAbsolutePath())
            .putExtra(InferenceContract.EXTRA_VIDEO_PATH, video.getAbsolutePath())
            .putExtra(InferenceContract.EXTRA_OUTPUT_PATH, output.getAbsolutePath())
            .putExtra(InferenceContract.EXTRA_RECEIVER, asFrameworkReceiverProxy(receiver));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
            ContextCompat.startForegroundService(app, start));

        if (cancelAfterFirstProgress) {
            assertTrue(
                "inference process never emitted initial progress",
                firstProgress.await(15, TimeUnit.SECONDS)
            );
            Intent cancel = new Intent(app, InferenceService.class)
                .setAction(InferenceContract.ACTION_CANCEL)
                .putExtra(InferenceContract.EXTRA_JOB_ID, jobId);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> app.startService(cancel));
        }

        assertTrue(
            "inference process did not reach a terminal state; last message=" + result.message,
            terminal.await(terminalWaitSeconds, TimeUnit.SECONDS)
        );
        return result;
    }

    private static ResultReceiver asFrameworkReceiverProxy(ResultReceiver localReceiver) {
        Parcel parcel = Parcel.obtain();
        try {
            localReceiver.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return ResultReceiver.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }

    private static void assertHealthyProgress(JobResult result) {
        assertFalse("no progress events received", result.progress.isEmpty());
        int previous = -1;
        for (int progress : result.progress) {
            assertTrue("progress moved backwards", progress >= previous);
            assertTrue("the old 4% failure marker reappeared", progress != 4);
            previous = progress;
        }
        assertTrue(result.progress.contains(ProgressPlan.PREPARING));
        assertTrue(result.progress.contains(ProgressPlan.VIDEO_OPENED));
        assertTrue(result.progress.contains(ProgressPlan.SOURCE_FACE_FOUND));
        assertTrue(result.progress.contains(ProgressPlan.SOURCE_EMBEDDED));
        assertTrue(result.progress.contains(ProgressPlan.VIDEO_PROCESSING_START));
        assertTrue(result.progress.contains(ProgressPlan.VIDEO_PROCESSING_END));
        assertTrue(result.progress.contains(ProgressPlan.VIDEO_ENCODED));
        assertTrue(result.progress.contains(ProgressPlan.AUDIO_MUXED));
        for (int i = 1; i < result.eventTimes.size(); i++) {
            long silence = result.eventTimes.get(i) - result.eventTimes.get(i - 1);
            assertTrue("progress heartbeat was silent for " + silence + " ms", silence < 15_000L);
        }
    }

    private static void assertValidChangedMp4(File input, File output) throws Exception {
        assertTrue(output.isFile());
        assertTrue("result MP4 is implausibly small", output.length() > 20_000L);
        TrackSummary tracks = inspectTracks(output);
        assertEquals("output must contain exactly one video track", 1, tracks.videoTracks);
        assertEquals("output must retain exactly one audio track", 1, tracks.audioTracks);
        assertEquals("output video must be H.264", "video/avc", tracks.videoMime);

        long inputDuration = durationMs(input);
        long outputDuration = durationMs(output);
        assertTrue(
            "output duration drifted too far: input=" + inputDuration + " output=" + outputDuration,
            Math.abs(inputDuration - outputDuration) <= 300L
        );

        assertFrameAreaChanged(input, output, 0L);
    }

    private static void assertFrameAreaChanged(File input, File output, long timeUs) throws Exception {
        Bitmap before = frameAt(input, timeUs);
        Bitmap after = frameAt(output, timeUs);
        try {
            assertEquals(before.getWidth(), after.getWidth());
            assertEquals(before.getHeight(), after.getHeight());
            int left = before.getWidth() / 4;
            int right = before.getWidth() * 3 / 4;
            int top = before.getHeight() / 5;
            int bottom = before.getHeight() * 4 / 5;
            long difference = 0L;
            long samples = 0L;
            for (int y = top; y < bottom; y += 6) {
                for (int x = left; x < right; x += 6) {
                    int a = before.getPixel(x, y);
                    int b = after.getPixel(x, y);
                    difference += Math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b));
                    difference += Math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b));
                    difference += Math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b));
                    samples += 3L;
                }
            }
            double meanDifference = difference / (double) samples;
            assertTrue(
                "face area did not change enough: mean channel difference=" + meanDifference,
                meanDifference > 2.0
            );
        } finally {
            before.recycle();
            after.recycle();
        }
    }

    private static void assertAudioPayloadUnchanged(File input, File output) throws Exception {
        AudioFingerprint before = fingerprintAudio(input);
        AudioFingerprint after = fingerprintAudio(output);
        assertTrue("input test video must contain audio", before.sampleCount > 0);
        assertEquals("audio sample count changed", before.sampleCount, after.sampleCount);
        assertEquals("first audio timestamp changed", before.firstTimeUs, after.firstTimeUs);
        assertEquals("last audio timestamp changed", before.lastTimeUs, after.lastTimeUs);
        assertArrayEquals("compressed audio payload changed", before.digest, after.digest);
    }

    private static AudioFingerprint fingerprintAudio(File file) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int track = findTrack(extractor, "audio/");
            assertTrue("MP4 has no audio track: " + file, track >= 0);
            extractor.selectTrack(track);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024);
            int count = 0;
            long first = -1L;
            long last = -1L;
            while (true) {
                buffer.clear();
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) {
                    break;
                }
                long timeUs = extractor.getSampleTime();
                if (count == 0) {
                    first = timeUs;
                }
                last = timeUs;
                buffer.position(0);
                buffer.limit(size);
                digest.update(buffer);
                count++;
                if (!extractor.advance()) {
                    break;
                }
            }
            return new AudioFingerprint(digest.digest(), count, first, last);
        } finally {
            extractor.release();
        }
    }

    private static TrackSummary inspectTracks(File file) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            TrackSummary summary = new TrackSummary();
            for (int index = 0; index < extractor.getTrackCount(); index++) {
                String mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    summary.videoTracks++;
                    summary.videoMime = mime;
                } else if (mime != null && mime.startsWith("audio/")) {
                    summary.audioTracks++;
                }
            }
            return summary;
        } finally {
            extractor.release();
        }
    }

    private static int findTrack(MediaExtractor extractor, String prefix) {
        for (int index = 0; index < extractor.getTrackCount(); index++) {
            String mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) {
                return index;
            }
        }
        return -1;
    }

    private static long durationMs(File file) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? -1L : Long.parseLong(value);
        } finally {
            retriever.release();
        }
    }

    private static Bitmap firstFrame(File file) throws Exception {
        return frameAt(file, 0L);
    }

    private static Bitmap frameAt(File file, long timeUs) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            Bitmap frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
            assertNotNull("could not decode video frame at " + timeUs + " us: " + file, frame);
            return frame;
        } finally {
            retriever.release();
        }
    }

    private static File copyAsset(Context tests, String assetName, File destination) throws Exception {
        try (InputStream input = tests.getAssets().open(assetName);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }
        assertTrue(destination.isFile());
        return destination;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        assertTrue("failed to delete old test file " + file, file.delete());
    }

    private static final class JobResult {
        private final AtomicInteger code = new AtomicInteger(Integer.MIN_VALUE);
        private final List<Integer> progress = Collections.synchronizedList(new ArrayList<>());
        private final List<Long> eventTimes = Collections.synchronizedList(new ArrayList<>());
        private volatile String message = "";
        private volatile long elapsedMs;
    }

    private static final class AudioFingerprint {
        private final byte[] digest;
        private final int sampleCount;
        private final long firstTimeUs;
        private final long lastTimeUs;

        private AudioFingerprint(byte[] digest, int sampleCount, long firstTimeUs, long lastTimeUs) {
            this.digest = digest;
            this.sampleCount = sampleCount;
            this.firstTimeUs = firstTimeUs;
            this.lastTimeUs = lastTimeUs;
        }
    }

    private static final class TrackSummary {
        private int videoTracks;
        private int audioTracks;
        private String videoMime;
    }
}

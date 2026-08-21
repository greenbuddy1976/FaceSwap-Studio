package com.greenbuddy.faceswapstudio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.greenbuddy.faceswapstudio.engine.BitmapLoader;
import com.greenbuddy.faceswapstudio.engine.FaceSwapEngine;
import com.greenbuddy.faceswapstudio.engine.FaceSwapException;
import com.greenbuddy.faceswapstudio.engine.MlKitFaceLocator;
import com.greenbuddy.faceswapstudio.engine.ProgressPlan;
import com.greenbuddy.faceswapstudio.service.InferenceContract;
import com.greenbuddy.faceswapstudio.service.InferenceService;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
    private static final long FULL_SWAP_LIMIT_MS = 60_000L;
    private static final long TERMINAL_WAIT_SECONDS = 90L;

    @Test(timeout = 150_000L)
    public void generatedPortraitsExerciseSuccessVarietyErrorsAndCancellation() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context tests = InstrumentationRegistry.getInstrumentation().getContext();
        Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
            new Intent(app, com.greenbuddy.faceswapstudio.ui.MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        assertNotNull("main activity did not launch", activity);
        File root = new File(app.getCacheDir(), "verified-e2e");
        deleteRecursively(root);
        assertTrue(root.mkdirs());

        File source = copyAsset(tests, "generated_faces/source.jpg", new File(root, "source.jpg"));
        File targetA = copyAsset(tests, "generated_faces/target_a.jpg", new File(root, "target-a.jpg"));
        File targetB = copyAsset(tests, "generated_faces/target_b.jpg", new File(root, "target-b.jpg"));

        verifyAllGeneratedFacesAreDetectable(source, targetA, targetB);

        File outputA = new File(root, "result-a.jpg");
        JobResult completed = runServiceJob(app, source, targetA, outputA, false);
        assertEquals("isolated inference process must report success", InferenceContract.RESULT_SUCCESS, completed.code.get());
        assertTrue("full face swap exceeded 60-second performance budget: " + completed.elapsedMs + " ms",
            completed.elapsedMs <= FULL_SWAP_LIMIT_MS);
        assertHealthyProgress(completed);
        assertOutputChanged(targetA, outputA);
        Log.i(TAG, "FACESWAP_METRIC isolated_service_ms=" + completed.elapsedMs);

        long secondStarted = SystemClock.elapsedRealtime();
        File outputB = new File(root, "result-b.jpg");
        List<Integer> directProgress = new ArrayList<>();
        new FaceSwapEngine(app).run(targetA, targetB, outputB, (percent, message, timeout) -> {
            directProgress.add(percent);
            assertTrue(timeout > 0L);
        });
        long secondElapsed = SystemClock.elapsedRealtime() - secondStarted;
        assertTrue("second varied face swap exceeded 60-second performance budget: " + secondElapsed + " ms",
            secondElapsed <= FULL_SWAP_LIMIT_MS);
        assertEquals(Integer.valueOf(ProgressPlan.SAVED), directProgress.get(directProgress.size() - 1));
        assertFalse(directProgress.contains(4));
        assertOutputChanged(targetB, outputB);
        Log.i(TAG, "FACESWAP_METRIC varied_direct_ms=" + secondElapsed);

        File corrupt = new File(root, "corrupt.image");
        try (FileOutputStream output = new FileOutputStream(corrupt)) {
            output.write("this-is-not-an-image".getBytes(StandardCharsets.UTF_8));
        }
        long errorStarted = SystemClock.elapsedRealtime();
        try {
            new FaceSwapEngine(app).run(corrupt, targetA, new File(root, "must-not-exist.jpg"),
                (percent, message, timeout) -> { });
            fail("corrupt input must fail with a user-facing error");
        } catch (FaceSwapException expected) {
            assertTrue(expected.getMessage().contains("Bilddatei"));
        }
        long errorElapsed = SystemClock.elapsedRealtime() - errorStarted;
        assertTrue("corrupt input was not rejected quickly", errorElapsed < 5_000L);
        Log.i(TAG, "FACESWAP_METRIC corrupt_rejection_ms=" + errorElapsed);

        SystemClock.sleep(1_500L);
        JobResult cancelled = runServiceJob(app, source, targetB, new File(root, "cancelled.jpg"), true);
        assertEquals("cancel request must terminate the isolated process", InferenceContract.RESULT_CANCELLED,
            cancelled.code.get());
        assertTrue("cancel took too long: " + cancelled.elapsedMs + " ms", cancelled.elapsedMs < 15_000L);
        Log.i(TAG, "FACESWAP_METRIC cancellation_ms=" + cancelled.elapsedMs);
        Log.i(TAG, "FACESWAP_E2E_FULL_PASS");
        InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish);
    }

    private static void verifyAllGeneratedFacesAreDetectable(File... files) throws Exception {
        try (MlKitFaceLocator locator = new MlKitFaceLocator()) {
            for (File file : files) {
                Bitmap bitmap = BitmapLoader.decode(file, 1024);
                try {
                    assertNotNull(locator.findLargest(bitmap, file.getName()));
                } finally {
                    bitmap.recycle();
                }
            }
        }
    }

    private static JobResult runServiceJob(
        Context app,
        File source,
        File target,
        File output,
        boolean cancelAfterFirstProgress
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
            .putExtra(InferenceContract.EXTRA_SOURCE_PATH, source.getAbsolutePath())
            .putExtra(InferenceContract.EXTRA_TARGET_PATH, target.getAbsolutePath())
            .putExtra(InferenceContract.EXTRA_OUTPUT_PATH, output.getAbsolutePath())
            .putExtra(InferenceContract.EXTRA_RECEIVER, asFrameworkReceiverProxy(receiver));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
            ContextCompat.startForegroundService(app, start));

        if (cancelAfterFirstProgress) {
            assertTrue("inference process never emitted initial progress", firstProgress.await(15, TimeUnit.SECONDS));
            Intent cancel = new Intent(app, InferenceService.class)
                .setAction(InferenceContract.ACTION_CANCEL)
                .putExtra(InferenceContract.EXTRA_JOB_ID, jobId);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> app.startService(cancel));
        }

        assertTrue("inference process did not reach a terminal state", terminal.await(TERMINAL_WAIT_SECONDS, TimeUnit.SECONDS));
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
        assertTrue(result.progress.contains(ProgressPlan.SOURCE_FACE_FOUND));
        assertTrue(result.progress.contains(ProgressPlan.SWAP_MODEL_READY));
        assertTrue(result.progress.contains(ProgressPlan.SWAP_COMPLETE));
        for (int i = 1; i < result.eventTimes.size(); i++) {
            long silence = result.eventTimes.get(i) - result.eventTimes.get(i - 1);
            assertTrue("progress heartbeat was silent for " + silence + " ms", silence < 15_000L);
        }
    }

    private static void assertOutputChanged(File targetFile, File outputFile) {
        assertTrue(outputFile.isFile());
        assertTrue("result JPEG is implausibly small", outputFile.length() > 10_000L);
        Bitmap target = BitmapFactory.decodeFile(targetFile.getAbsolutePath());
        Bitmap output = BitmapFactory.decodeFile(outputFile.getAbsolutePath());
        assertNotNull(target);
        assertNotNull(output);
        try {
            assertEquals(target.getWidth(), output.getWidth());
            assertEquals(target.getHeight(), output.getHeight());
            int left = target.getWidth() / 4;
            int right = target.getWidth() * 3 / 4;
            int top = target.getHeight() / 5;
            int bottom = target.getHeight() * 4 / 5;
            long difference = 0L;
            long samples = 0L;
            for (int y = top; y < bottom; y += 8) {
                for (int x = left; x < right; x += 8) {
                    int a = target.getPixel(x, y);
                    int b = output.getPixel(x, y);
                    difference += Math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b));
                    difference += Math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b));
                    difference += Math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b));
                    samples += 3L;
                }
            }
            double meanDifference = difference / (double) samples;
            assertTrue("face area did not change enough: mean channel difference=" + meanDifference,
                meanDifference > 2.0);
        } finally {
            target.recycle();
            output.recycle();
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
}

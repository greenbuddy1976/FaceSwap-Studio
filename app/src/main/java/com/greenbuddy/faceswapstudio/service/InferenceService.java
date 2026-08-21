package com.greenbuddy.faceswapstudio.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.greenbuddy.faceswapstudio.R;
import com.greenbuddy.faceswapstudio.engine.FaceSwapEngine;
import com.greenbuddy.faceswapstudio.engine.FaceSwapException;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class InferenceService extends Service {
    private static final String TAG = "FaceSwapInference";
    private static final String CHANNEL_ID = "faceswap_processing";
    private static final int NOTIFICATION_ID = 1042;
    private static final long HEARTBEAT_INTERVAL_MS = 5_000L;
    private static final long TOTAL_JOB_TIMEOUT_MS = 120_000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean finishing = new AtomicBoolean(false);

    private ExecutorService executor;
    private volatile ResultReceiver receiver;
    private volatile String currentJobId;
    private volatile int currentProgress;
    private volatile String currentMessage;
    private volatile long stageStartedAt;
    private Runnable timeoutRunnable;
    private Runnable totalTimeoutRunnable;
    private Runnable heartbeatRunnable;

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "faceswap-inference");
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (InferenceContract.ACTION_CANCEL.equals(intent.getAction())) {
            cancelJob(intent.getStringExtra(InferenceContract.EXTRA_JOB_ID));
            return START_NOT_STICKY;
        }
        if (!InferenceContract.ACTION_START.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (currentJobId != null) {
            ResultReceiver rejected = readReceiver(intent);
            send(rejected, InferenceContract.RESULT_ERROR, 0, "Es läuft bereits ein Face-Swap.", null);
            return START_NOT_STICKY;
        }

        String jobId = intent.getStringExtra(InferenceContract.EXTRA_JOB_ID);
        String sourcePath = intent.getStringExtra(InferenceContract.EXTRA_SOURCE_PATH);
        String targetPath = intent.getStringExtra(InferenceContract.EXTRA_TARGET_PATH);
        String outputPath = intent.getStringExtra(InferenceContract.EXTRA_OUTPUT_PATH);
        ResultReceiver resultReceiver = readReceiver(intent);
        if (jobId == null || sourcePath == null || targetPath == null || outputPath == null || resultReceiver == null) {
            send(resultReceiver, InferenceContract.RESULT_ERROR, 0, "Der Verarbeitungsauftrag ist unvollständig.", null);
            stopSelf();
            return START_NOT_STICKY;
        }

        currentJobId = jobId;
        receiver = resultReceiver;
        currentProgress = 1;
        currentMessage = "Face-Swap wird vorbereitet …";
        stageStartedAt = SystemClock.elapsedRealtime();
        startForeground(NOTIFICATION_ID, buildNotification(currentProgress, currentMessage));
        armTotalTimeout();

        executor.execute(() -> executeJob(
            new File(sourcePath),
            new File(targetPath),
            new File(outputPath)
        ));
        return START_NOT_STICKY;
    }

    private void executeJob(File source, File target, File output) {
        try {
            FaceSwapEngine engine = new FaceSwapEngine(this);
            engine.run(source, target, output, this::publishProgress);
            if (finishing.compareAndSet(false, true)) {
                cancelTimers();
                send(receiver, InferenceContract.RESULT_SUCCESS, 100, "Fertig", output.getAbsolutePath());
                stopAndReleaseProcess();
            }
        } catch (FaceSwapException error) {
            fail(error.getMessage(), error);
        } catch (Throwable error) {
            fail("Die KI-Verarbeitung wurde unerwartet beendet.", error);
        }
    }

    private void publishProgress(int percent, String message, long timeoutMillis) throws FaceSwapException {
        if (finishing.get() || Thread.currentThread().isInterrupted()) {
            throw new FaceSwapException("Verarbeitung abgebrochen.");
        }
        currentProgress = Math.max(currentProgress, Math.min(100, percent));
        currentMessage = message;
        stageStartedAt = SystemClock.elapsedRealtime();
        send(receiver, InferenceContract.RESULT_PROGRESS, currentProgress, currentMessage, null);
        updateNotification();
        armStageTimeout(timeoutMillis);
        armHeartbeat();
    }

    private void armStageTimeout(long timeoutMillis) {
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
        }
        timeoutRunnable = () -> {
            if (finishing.compareAndSet(false, true)) {
                String message = "Zeitüberschreitung bei „" + currentMessage + "“. Die KI wurde sicher beendet; bitte erneut versuchen.";
                send(receiver, InferenceContract.RESULT_ERROR, currentProgress, message, null);
                stopAndReleaseProcess();
            }
        };
        mainHandler.postDelayed(timeoutRunnable, timeoutMillis);
    }

    private void armTotalTimeout() {
        if (totalTimeoutRunnable != null) {
            mainHandler.removeCallbacks(totalTimeoutRunnable);
        }
        totalTimeoutRunnable = () -> {
            if (finishing.compareAndSet(false, true)) {
                String message = "Der Face-Swap hat die feste Zwei-Minuten-Grenze überschritten und wurde beendet.";
                send(receiver, InferenceContract.RESULT_ERROR, currentProgress, message, null);
                stopAndReleaseProcess();
            }
        };
        mainHandler.postDelayed(totalTimeoutRunnable, TOTAL_JOB_TIMEOUT_MS);
    }

    private void armHeartbeat() {
        if (heartbeatRunnable != null) {
            mainHandler.removeCallbacks(heartbeatRunnable);
        }
        heartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (finishing.get() || receiver == null) {
                    return;
                }
                long seconds = Math.max(1L, (SystemClock.elapsedRealtime() - stageStartedAt) / 1000L);
                send(
                    receiver,
                    InferenceContract.RESULT_PROGRESS,
                    currentProgress,
                    currentMessage + " · " + seconds + " s",
                    null
                );
                mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS);
    }

    private void cancelJob(String jobId) {
        if (currentJobId == null) {
            stopSelf();
            return;
        }
        if (jobId != null && !jobId.equals(currentJobId)) {
            return;
        }
        if (finishing.compareAndSet(false, true)) {
            cancelTimers();
            send(receiver, InferenceContract.RESULT_CANCELLED, currentProgress, "Verarbeitung abgebrochen.", null);
            stopAndReleaseProcess();
        }
    }

    private void fail(String message, Throwable error) {
        Log.e(TAG, message, error);
        if (finishing.compareAndSet(false, true)) {
            cancelTimers();
            send(receiver, InferenceContract.RESULT_ERROR, currentProgress, message, null);
            stopAndReleaseProcess();
        }
    }

    private void stopAndReleaseProcess() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        mainHandler.postDelayed(() -> Process.killProcess(Process.myPid()), 650L);
    }

    private void cancelTimers() {
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
        }
        if (totalTimeoutRunnable != null) {
            mainHandler.removeCallbacks(totalTimeoutRunnable);
        }
        if (heartbeatRunnable != null) {
            mainHandler.removeCallbacks(heartbeatRunnable);
        }
    }

    private void send(ResultReceiver target, int code, int progress, String message, String outputPath) {
        if (target == null) {
            return;
        }
        Bundle data = new Bundle();
        data.putInt(InferenceContract.DATA_PROGRESS, progress);
        data.putString(InferenceContract.DATA_MESSAGE, message);
        if (outputPath != null) {
            data.putString(InferenceContract.DATA_OUTPUT_PATH, outputPath);
        }
        target.send(code, data);
    }

    @SuppressWarnings("deprecation")
    private ResultReceiver readReceiver(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(InferenceContract.EXTRA_RECEIVER, ResultReceiver.class);
        }
        //noinspection deprecation
        return intent.getParcelableExtra(InferenceContract.EXTRA_RECEIVER);
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.privacy_note));
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(int progress, String message) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(message)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, Math.max(0, Math.min(100, progress)), false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private void updateNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, buildNotification(currentProgress, currentMessage));
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        cancelTimers();
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }
}

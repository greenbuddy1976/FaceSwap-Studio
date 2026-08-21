package com.greenbuddy.faceswapstudio.ui;

import android.app.Application;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.greenbuddy.faceswapstudio.service.InferenceContract;
import com.greenbuddy.faceswapstudio.service.InferenceService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class FaceSwapViewModel extends AndroidViewModel {
    private static final long MAX_FACE_BYTES = 80L * 1024L * 1024L;
    private static final long MAX_VIDEO_BYTES = 1_500L * 1024L * 1024L;
    private static final long UI_WATCHDOG_MS = 30_000L;
    private static final long UI_WATCHDOG_POLL_MS = 10_000L;

    private final MutableLiveData<ProcessingState> state = new MutableLiveData<>(
        ProcessingState.ready("Bereit · zuerst Video auswählen")
    );
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile String activeJobId;
    private volatile String serviceJobId;
    private volatile long lastServiceUpdate;
    private ResultReceiver resultReceiver;

    public FaceSwapViewModel(@NonNull Application application) {
        super(application);
    }

    public LiveData<ProcessingState> getState() {
        return state;
    }

    public void start(Uri videoUri, Uri faceUri, boolean consentConfirmed) {
        ProcessingState current = state.getValue();
        if (current != null && current.isBusy()) {
            return;
        }
        if (videoUri == null) {
            state.setValue(ProcessingState.error("Bitte zuerst ein MP4-Video auswählen."));
            return;
        }
        if (faceUri == null) {
            state.setValue(ProcessingState.error("Bitte danach ein Foto mit dem neuen Gesicht auswählen."));
            return;
        }
        if (!consentConfirmed) {
            state.setValue(ProcessingState.error("Bitte die Einwilligungs- und Nutzungsbestätigung aktivieren."));
            return;
        }

        state.setValue(ProcessingState.preparing("Video und Gesichtsfoto werden sicher vorbereitet …"));
        String jobId = UUID.randomUUID().toString();
        activeJobId = jobId;
        lastServiceUpdate = SystemClock.elapsedRealtime();

        fileExecutor.execute(() -> {
            File jobDirectory = null;
            try {
                File cacheRoot = getApplication().getExternalCacheDir();
                if (cacheRoot == null) {
                    cacheRoot = getApplication().getCacheDir();
                }
                File jobsDirectory = new File(cacheRoot, "video-jobs");
                if (!jobsDirectory.mkdirs() && !jobsDirectory.isDirectory()) {
                    throw new IOException("Jobs directory could not be created.");
                }
                deleteChildren(jobsDirectory);
                jobDirectory = new File(jobsDirectory, jobId);
                if (!jobDirectory.mkdirs() && !jobDirectory.isDirectory()) {
                    throw new IOException("Job directory could not be created.");
                }
                File videoFile = new File(jobDirectory, "target-video.mp4");
                File faceFile = new File(jobDirectory, "source-face.image");
                File outputFile = new File(jobDirectory, "faceswap-result.mp4");
                copyUri(videoUri, videoFile, MAX_VIDEO_BYTES);
                copyUri(faceUri, faceFile, MAX_FACE_BYTES);

                if (!jobId.equals(activeJobId)) {
                    deleteRecursively(jobDirectory);
                    return;
                }
                mainHandler.post(() -> launchService(jobId, faceFile, videoFile, outputFile));
            } catch (Exception error) {
                if (jobId.equals(activeJobId)) {
                    state.postValue(ProcessingState.error(
                        "Video oder Gesichtsfoto konnten nicht geöffnet werden. Bitte die Dateien erneut auswählen."
                    ));
                    activeJobId = null;
                }
                deleteRecursively(jobDirectory);
            }
        });
    }

    public void cancel() {
        String jobId = activeJobId;
        if (jobId == null) {
            return;
        }
        activeJobId = null;
        if (jobId.equals(serviceJobId)) {
            Intent cancel = new Intent(getApplication(), InferenceService.class)
                .setAction(InferenceContract.ACTION_CANCEL)
                .putExtra(InferenceContract.EXTRA_JOB_ID, jobId);
            getApplication().startService(cancel);
            serviceJobId = null;
        }
        state.setValue(ProcessingState.cancelled());
    }

    private void launchService(String jobId, File facePhoto, File inputVideo, File output) {
        if (!jobId.equals(activeJobId)) {
            return;
        }
        resultReceiver = new ResultReceiver(mainHandler) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                lastServiceUpdate = SystemClock.elapsedRealtime();
                int progress = resultData.getInt(InferenceContract.DATA_PROGRESS, 0);
                String message = resultData.getString(InferenceContract.DATA_MESSAGE, "Verarbeitung läuft …");
                if (resultCode == InferenceContract.RESULT_PROGRESS) {
                    state.setValue(ProcessingState.running(progress, message));
                } else if (resultCode == InferenceContract.RESULT_SUCCESS) {
                    String outputPath = resultData.getString(InferenceContract.DATA_OUTPUT_PATH);
                    if (outputPath == null || !new File(outputPath).isFile()) {
                        state.setValue(ProcessingState.error("Die KI meldete Erfolg, aber die Ergebnisdatei fehlt."));
                    } else {
                        state.setValue(ProcessingState.success(outputPath));
                    }
                    activeJobId = null;
                    serviceJobId = null;
                } else if (resultCode == InferenceContract.RESULT_CANCELLED) {
                    state.setValue(ProcessingState.cancelled());
                    activeJobId = null;
                    serviceJobId = null;
                } else if (resultCode == InferenceContract.RESULT_ERROR) {
                    state.setValue(ProcessingState.error(message));
                    activeJobId = null;
                    serviceJobId = null;
                }
            }
        };

        Intent start = new Intent(getApplication(), InferenceService.class)
            .setAction(InferenceContract.ACTION_START)
            .putExtra(InferenceContract.EXTRA_JOB_ID, jobId)
            .putExtra(InferenceContract.EXTRA_FACE_PATH, facePhoto.getAbsolutePath())
            .putExtra(InferenceContract.EXTRA_VIDEO_PATH, inputVideo.getAbsolutePath())
            .putExtra(InferenceContract.EXTRA_OUTPUT_PATH, output.getAbsolutePath())
            .putExtra(InferenceContract.EXTRA_RECEIVER, resultReceiver);
        try {
            serviceJobId = jobId;
            ContextCompat.startForegroundService(getApplication(), start);
            state.setValue(ProcessingState.running(2, "KI-Prozess wird gestartet …"));
            armUiWatchdog(jobId);
        } catch (RuntimeException error) {
            activeJobId = null;
            serviceJobId = null;
            state.setValue(ProcessingState.error("Android konnte den getrennten KI-Prozess nicht starten."));
        }
    }

    private void armUiWatchdog(String jobId) {
        mainHandler.postDelayed(() -> {
            if (!jobId.equals(activeJobId)) {
                return;
            }
            long silence = SystemClock.elapsedRealtime() - lastServiceUpdate;
            if (silence >= UI_WATCHDOG_MS) {
                cancel();
                state.setValue(ProcessingState.error(
                    "Der KI-Prozess hat zu lange nicht geantwortet und wurde sicher beendet. Bitte erneut versuchen."
                ));
            } else {
                armUiWatchdog(jobId);
            }
        }, UI_WATCHDOG_POLL_MS);
    }

    private void copyUri(Uri source, File destination, long maximumBytes) throws IOException {
        ContentResolver resolver = getApplication().getContentResolver();
        try (InputStream input = resolver.openInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            if (input == null) {
                throw new IOException("Content resolver returned no stream.");
            }
            byte[] buffer = new byte[64 * 1024];
            long total = 0L;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maximumBytes) {
                    throw new IOException("Input exceeds size limit.");
                }
                output.write(buffer, 0, count);
            }
            output.flush();
            if (total == 0L) {
                throw new IOException("Input is empty.");
            }
        }
    }

    private static void deleteChildren(File directory) {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            deleteRecursively(child);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            deleteChildren(file);
        }
        // Best effort: cache cleanup must never hide the actual processing result.
        file.delete();
    }

    @Override
    protected void onCleared() {
        fileExecutor.shutdownNow();
        super.onCleared();
    }
}

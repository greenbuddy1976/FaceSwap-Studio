package com.greenbuddy.faceswapstudio.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.greenbuddy.faceswapstudio.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class MainActivity extends AppCompatActivity {
    private static final String STATE_VIDEO_URI = "video_uri";
    private static final String STATE_FACE_URI = "face_uri";

    private Uri videoUri;
    private Uri faceUri;
    private String outputPath;

    private VideoView videoPreview;
    private ImageView facePreview;
    private VideoView resultVideoPreview;
    private MaterialButton videoButton;
    private MaterialButton faceButton;
    private MaterialButton startButton;
    private MaterialButton cancelButton;
    private MaterialButton saveButton;
    private CheckBox consentCheck;
    private LinearProgressIndicator progressBar;
    private TextView statusText;
    private LinearLayout resultPanel;

    private FaceSwapViewModel viewModel;
    private boolean busy;

    private final ActivityResultLauncher<String[]> videoPicker = registerForActivityResult(
        new ActivityResultContracts.OpenDocument(),
        this::onVideoPicked
    );

    private final ActivityResultLauncher<String[]> facePicker = registerForActivityResult(
        new ActivityResultContracts.OpenDocument(),
        this::onFacePicked
    );

    private final ActivityResultLauncher<String> saveDocument = registerForActivityResult(
        new ActivityResultContracts.CreateDocument("video/mp4"),
        this::onSaveLocationChosen
    );

    private final ActivityResultLauncher<String> notificationPermission = registerForActivityResult(
        new ActivityResultContracts.RequestPermission(),
        granted -> {
            if (!granted) {
                Toast.makeText(this, R.string.notification_denied, Toast.LENGTH_LONG).show();
            }
            startSwap();
        }
    );

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();

        if (savedInstanceState != null) {
            String video = savedInstanceState.getString(STATE_VIDEO_URI);
            String face = savedInstanceState.getString(STATE_FACE_URI);
            videoUri = video == null ? null : Uri.parse(video);
            faceUri = face == null ? null : Uri.parse(face);
            showSelections();
        }

        videoButton.setOnClickListener(view -> videoPicker.launch(new String[] { "video/mp4" }));
        faceButton.setOnClickListener(view -> facePicker.launch(new String[] {
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/heic",
            "image/heif"
        }));
        consentCheck.setOnCheckedChangeListener((button, checked) -> updateInputControls());
        startButton.setOnClickListener(view -> startWithNotificationPermission());
        cancelButton.setOnClickListener(view -> viewModel.cancel());
        saveButton.setOnClickListener(view -> {
            if (outputPath != null) {
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
                saveDocument.launch("FaceSwap_Video_" + timestamp + ".mp4");
            }
        });

        viewModel = new ViewModelProvider(this).get(FaceSwapViewModel.class);
        viewModel.getState().observe(this, this::renderState);
        updateInputControls();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (videoUri != null) {
            outState.putString(STATE_VIDEO_URI, videoUri.toString());
        }
        if (faceUri != null) {
            outState.putString(STATE_FACE_URI, faceUri.toString());
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        videoPreview.pause();
        resultVideoPreview.pause();
    }

    private void bindViews() {
        videoPreview = findViewById(R.id.videoPreview);
        facePreview = findViewById(R.id.facePreview);
        resultVideoPreview = findViewById(R.id.resultVideoPreview);
        videoButton = findViewById(R.id.videoButton);
        faceButton = findViewById(R.id.faceButton);
        startButton = findViewById(R.id.startButton);
        cancelButton = findViewById(R.id.cancelButton);
        saveButton = findViewById(R.id.saveButton);
        consentCheck = findViewById(R.id.consentCheck);
        progressBar = findViewById(R.id.progressBar);
        statusText = findViewById(R.id.statusText);
        resultPanel = findViewById(R.id.resultPanel);
    }

    private void onVideoPicked(Uri uri) {
        if (uri == null) {
            return;
        }
        persistReadPermission(uri);
        videoUri = uri;
        configurePreview(videoPreview, uri, false);
        statusText.setText(R.string.status_video_selected);
        statusText.setTextColor(Color.WHITE);
        updateInputControls();
    }

    private void onFacePicked(Uri uri) {
        if (uri == null) {
            return;
        }
        persistReadPermission(uri);
        faceUri = uri;
        facePreview.setImageURI(uri);
        statusText.setText(R.string.status_face_selected);
        statusText.setTextColor(Color.WHITE);
        updateInputControls();
    }

    private void persistReadPermission(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Some providers grant access for this Activity only; the app copies on start.
        }
    }

    private void showSelections() {
        if (videoUri != null) {
            configurePreview(videoPreview, videoUri, false);
        }
        if (faceUri != null) {
            facePreview.setImageURI(faceUri);
        }
    }

    private void configurePreview(VideoView view, Uri uri, boolean autoplay) {
        MediaController controller = new MediaController(this);
        controller.setAnchorView(view);
        view.setMediaController(controller);
        view.setVideoURI(uri);
        view.setOnPreparedListener(player -> {
            player.setLooping(autoplay);
            if (autoplay) {
                view.start();
            } else {
                view.seekTo(1);
            }
        });
    }

    private void startWithNotificationPermission() {
        if (videoUri == null || faceUri == null || !consentCheck.isChecked()) {
            startSwap();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        startSwap();
    }

    private void startSwap() {
        viewModel.start(videoUri, faceUri, consentCheck.isChecked());
    }

    private void renderState(ProcessingState state) {
        busy = state.isBusy();
        updateInputControls();
        cancelButton.setVisibility(busy ? View.VISIBLE : View.GONE);
        progressBar.setProgressCompat(state.getProgress(), true);
        statusText.setText(state.getMessage());
        statusText.setTextColor(
            state.getMode() == ProcessingState.Mode.ERROR
                ? getColor(R.color.error)
                : Color.WHITE
        );

        if (state.getMode() == ProcessingState.Mode.SUCCESS && state.getOutputPath() != null) {
            outputPath = state.getOutputPath();
            configurePreview(resultVideoPreview, Uri.fromFile(new File(outputPath)), true);
            resultPanel.setVisibility(View.VISIBLE);
        } else if (state.getMode() == ProcessingState.Mode.PREPARING
            || state.getMode() == ProcessingState.Mode.RUNNING) {
            resultVideoPreview.stopPlayback();
            resultPanel.setVisibility(View.GONE);
            outputPath = null;
        }
    }

    private void updateInputControls() {
        videoButton.setEnabled(!busy);
        faceButton.setEnabled(!busy && videoUri != null);
        consentCheck.setEnabled(!busy);
        startButton.setEnabled(
            !busy && videoUri != null && faceUri != null && consentCheck.isChecked()
        );
    }

    private void onSaveLocationChosen(Uri destination) {
        if (destination == null || outputPath == null) {
            return;
        }
        File source = new File(outputPath);
        new Thread(() -> {
            try (FileInputStream input = new FileInputStream(source);
                 OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) {
                    throw new IOException("No output stream.");
                }
                byte[] buffer = new byte[256 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                output.flush();
                runOnUiThread(() -> Toast.makeText(
                    this,
                    R.string.video_saved,
                    Toast.LENGTH_LONG
                ).show());
            } catch (IOException error) {
                runOnUiThread(() -> Toast.makeText(
                    this,
                    R.string.save_failed,
                    Toast.LENGTH_LONG
                ).show());
            }
        }, "faceswap-video-save").start();
    }
}

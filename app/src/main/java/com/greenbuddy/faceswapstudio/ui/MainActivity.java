package com.greenbuddy.faceswapstudio.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

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
    private static final String STATE_SOURCE_URI = "source_uri";
    private static final String STATE_TARGET_URI = "target_uri";

    private Uri sourceUri;
    private Uri targetUri;
    private String outputPath;

    private ImageView sourcePreview;
    private ImageView targetPreview;
    private ImageView resultPreview;
    private MaterialButton sourceButton;
    private MaterialButton targetButton;
    private MaterialButton startButton;
    private MaterialButton cancelButton;
    private MaterialButton saveButton;
    private CheckBox consentCheck;
    private LinearProgressIndicator progressBar;
    private TextView statusText;
    private LinearLayout resultPanel;

    private FaceSwapViewModel viewModel;
    private boolean selectingSource;

    private final ActivityResultLauncher<String[]> imagePicker = registerForActivityResult(
        new ActivityResultContracts.OpenDocument(),
        this::onImagePicked
    );

    private final ActivityResultLauncher<String> saveDocument = registerForActivityResult(
        new ActivityResultContracts.CreateDocument("image/jpeg"),
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
            String source = savedInstanceState.getString(STATE_SOURCE_URI);
            String target = savedInstanceState.getString(STATE_TARGET_URI);
            sourceUri = source == null ? null : Uri.parse(source);
            targetUri = target == null ? null : Uri.parse(target);
            showSelectedImages();
        }

        sourceButton.setOnClickListener(view -> {
            selectingSource = true;
            imagePicker.launch(new String[] { "image/jpeg", "image/png", "image/webp", "image/heic", "image/heif" });
        });
        targetButton.setOnClickListener(view -> {
            selectingSource = false;
            imagePicker.launch(new String[] { "image/jpeg", "image/png", "image/webp", "image/heic", "image/heif" });
        });
        startButton.setOnClickListener(view -> startWithNotificationPermission());
        cancelButton.setOnClickListener(view -> viewModel.cancel());
        saveButton.setOnClickListener(view -> {
            if (outputPath != null) {
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
                saveDocument.launch("FaceSwap_" + timestamp + ".jpg");
            }
        });

        viewModel = new ViewModelProvider(this).get(FaceSwapViewModel.class);
        viewModel.getState().observe(this, this::renderState);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (sourceUri != null) {
            outState.putString(STATE_SOURCE_URI, sourceUri.toString());
        }
        if (targetUri != null) {
            outState.putString(STATE_TARGET_URI, targetUri.toString());
        }
    }

    private void bindViews() {
        sourcePreview = findViewById(R.id.sourcePreview);
        targetPreview = findViewById(R.id.targetPreview);
        resultPreview = findViewById(R.id.resultPreview);
        sourceButton = findViewById(R.id.sourceButton);
        targetButton = findViewById(R.id.targetButton);
        startButton = findViewById(R.id.startButton);
        cancelButton = findViewById(R.id.cancelButton);
        saveButton = findViewById(R.id.saveButton);
        consentCheck = findViewById(R.id.consentCheck);
        progressBar = findViewById(R.id.progressBar);
        statusText = findViewById(R.id.statusText);
        resultPanel = findViewById(R.id.resultPanel);
    }

    private void onImagePicked(Uri uri) {
        if (uri == null) {
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Some document providers grant access for the Activity lifetime only; copying happens immediately on start.
        }
        if (selectingSource) {
            sourceUri = uri;
            sourcePreview.setImageURI(uri);
        } else {
            targetUri = uri;
            targetPreview.setImageURI(uri);
        }
        statusText.setText(R.string.status_image_selected);
        statusText.setTextColor(Color.WHITE);
    }

    private void showSelectedImages() {
        if (sourceUri != null) {
            sourcePreview.setImageURI(sourceUri);
        }
        if (targetUri != null) {
            targetPreview.setImageURI(targetUri);
        }
    }

    private void startWithNotificationPermission() {
        if (sourceUri == null || targetUri == null || !consentCheck.isChecked()) {
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
        viewModel.start(sourceUri, targetUri, consentCheck.isChecked());
    }

    private void renderState(ProcessingState state) {
        boolean busy = state.isBusy();
        sourceButton.setEnabled(!busy);
        targetButton.setEnabled(!busy);
        startButton.setEnabled(!busy);
        consentCheck.setEnabled(!busy);
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
            resultPreview.setImageBitmap(BitmapFactory.decodeFile(outputPath));
            resultPanel.setVisibility(View.VISIBLE);
        } else if (state.getMode() == ProcessingState.Mode.PREPARING || state.getMode() == ProcessingState.Mode.RUNNING) {
            resultPanel.setVisibility(View.GONE);
            outputPath = null;
        }
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
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                output.flush();
                runOnUiThread(() -> Toast.makeText(this, "Ergebnis gespeichert.", Toast.LENGTH_LONG).show());
            } catch (IOException error) {
                runOnUiThread(() -> Toast.makeText(
                    this,
                    "Speichern fehlgeschlagen. Bitte einen anderen Ordner wählen.",
                    Toast.LENGTH_LONG
                ).show());
            }
        }, "faceswap-save").start();
    }
}

package com.greenbuddy.faceswapstudio.engine;

import android.content.res.AssetManager;
import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

public final class FaceSwapper {
    private static final String MODEL_PATH = "models/inswapper_128_fp16.onnx";
    private static final String EMAP_PATH = "models/emap.bin";
    private static final int EMBEDDING_SIZE = 512;
    private static final int OUTPUT_SIZE = 3 * 128 * 128;

    private final AssetManager assets;

    public FaceSwapper(AssetManager assets) {
        this.assets = assets;
    }

    public float[] swap(Bitmap alignedTarget, float[] sourceEmbedding) throws FaceSwapException {
        if (sourceEmbedding.length != EMBEDDING_SIZE) {
            throw new FaceSwapException("Das Gesichtsprofil besitzt nicht 512 Werte.");
        }
        float[] latent = transformEmbedding(sourceEmbedding, loadEmap());
        float[] targetInput = ImageTransforms.toSwapTensor(alignedTarget);

        OrtEnvironment environment;
        try {
            environment = OrtEnvironment.getEnvironment("faceswap-studio");
        } catch (Throwable error) {
            throw new FaceSwapException("ONNX Runtime konnte nicht gestartet werden.", error);
        }

        try (
            MappedAsset model = MappedAsset.open(assets, MODEL_PATH);
            OrtSession.SessionOptions options = OnnxTools.stableCpuOptions();
            OrtSession session = environment.createSession(model.getBuffer(), options)
        ) {
            InputNames names = resolveInputNames(session);
            try (
                OnnxTensor sourceTensor = OnnxTensor.createTensor(
                    environment,
                    OnnxTools.directFloatBuffer(latent),
                    new long[] { 1, EMBEDDING_SIZE }
                );
                OnnxTensor targetTensor = OnnxTensor.createTensor(
                    environment,
                    OnnxTools.directFloatBuffer(targetInput),
                    new long[] { 1, 3, 128, 128 }
                )
            ) {
                Map<String, OnnxTensor> inputs = new HashMap<>();
                inputs.put(names.source, sourceTensor);
                inputs.put(names.target, targetTensor);
                try (OrtSession.Result result = session.run(inputs)) {
                    return OnnxTools.copyFloatOutput(result.get(0), OUTPUT_SIZE);
                }
            }
        } catch (FaceSwapException error) {
            throw error;
        } catch (OrtException | RuntimeException error) {
            throw new FaceSwapException("Das INSwapper-Modell konnte den Face-Swap nicht berechnen.", error);
        } catch (OutOfMemoryError error) {
            throw new FaceSwapException(
                "Zu wenig Arbeitsspeicher für das Face-Swap-Modell. Andere Apps schließen und erneut versuchen.",
                error
            );
        }
    }

    private float[] loadEmap() throws FaceSwapException {
        try (InputStream input = assets.open(EMAP_PATH); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[16 * 1024];
            int count;
            while ((count = input.read(chunk)) != -1) {
                output.write(chunk, 0, count);
            }
            byte[] bytes = output.toByteArray();
            if (bytes.length != EMBEDDING_SIZE * EMBEDDING_SIZE * Float.BYTES) {
                throw new FaceSwapException("Die eingebaute EMAP-Matrix hat eine falsche Größe.");
            }
            FloatBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            float[] matrix = new float[EMBEDDING_SIZE * EMBEDDING_SIZE];
            buffer.get(matrix);
            return matrix;
        } catch (IOException error) {
            throw new FaceSwapException("Die eingebaute EMAP-Matrix konnte nicht gelesen werden.", error);
        }
    }

    private static float[] transformEmbedding(float[] embedding, float[] emap) throws FaceSwapException {
        double normSquared = 0.0;
        for (float value : embedding) {
            normSquared += value * value;
        }
        double norm = Math.sqrt(normSquared);
        if (!Double.isFinite(norm) || norm < 1.0e-8) {
            throw new FaceSwapException("Das Quellgesicht ergab kein gültiges Gesichtsprofil.");
        }

        float[] latent = new float[EMBEDDING_SIZE];
        for (int column = 0; column < EMBEDDING_SIZE; column++) {
            double sum = 0.0;
            for (int row = 0; row < EMBEDDING_SIZE; row++) {
                sum += embedding[row] * emap[row * EMBEDDING_SIZE + column];
            }
            latent[column] = (float) (sum / norm);
            if (!Float.isFinite(latent[column])) {
                throw new FaceSwapException("Die transformierte Gesichtsidentität ist ungültig.");
            }
        }
        return latent;
    }

    private static InputNames resolveInputNames(OrtSession session) throws FaceSwapException, OrtException {
        String source = null;
        String target = null;
        for (Map.Entry<String, NodeInfo> entry : session.getInputInfo().entrySet()) {
            String lower = entry.getKey().toLowerCase(Locale.ROOT);
            if (lower.contains("source")) {
                source = entry.getKey();
            } else if (lower.contains("target")) {
                target = entry.getKey();
            }
            if (entry.getValue().getInfo() instanceof TensorInfo) {
                long[] shape = ((TensorInfo) entry.getValue().getInfo()).getShape();
                long elements = knownElementCount(shape);
                if (elements == EMBEDDING_SIZE) {
                    source = entry.getKey();
                } else if (elements == OUTPUT_SIZE) {
                    target = entry.getKey();
                }
            }
        }
        if (source == null || target == null || source.equals(target)) {
            throw new FaceSwapException("Die Eingänge des Face-Swap-Modells sind nicht kompatibel.");
        }
        return new InputNames(source, target);
    }

    private static long knownElementCount(long[] shape) {
        long total = 1L;
        for (long dimension : shape) {
            if (dimension <= 0) {
                return -1L;
            }
            total *= dimension;
        }
        return total;
    }

    private static final class InputNames {
        private final String source;
        private final String target;

        private InputNames(String source, String target) {
            this.source = source;
            this.target = target;
        }
    }
}

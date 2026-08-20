package com.greenbuddy.faceswapstudio.engine;

import android.content.res.AssetManager;
import android.graphics.Bitmap;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

public final class FaceEmbedder {
    private static final String MODEL_PATH = "models/arcface_w600k_r50.onnx";

    private final AssetManager assets;

    public FaceEmbedder(AssetManager assets) {
        this.assets = assets;
    }

    public float[] embed(Bitmap alignedFace) throws FaceSwapException {
        float[] input = ImageTransforms.toArcFaceTensor(alignedFace);
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
            String inputName = session.getInputNames().iterator().next();
            FloatBuffer inputBuffer = OnnxTools.directFloatBuffer(input);
            try (OnnxTensor tensor = OnnxTensor.createTensor(
                environment,
                inputBuffer,
                new long[] { 1, 3, 112, 112 }
            )) {
                Map<String, OnnxTensor> inputs = new HashMap<>();
                inputs.put(inputName, tensor);
                try (OrtSession.Result result = session.run(inputs)) {
                    return OnnxTools.copyFloatOutput(result.get(0), 512);
                }
            }
        } catch (FaceSwapException error) {
            throw error;
        } catch (OrtException | RuntimeException error) {
            throw new FaceSwapException(
                "Das Gesichtsprofil konnte vom ArcFace-Modell nicht berechnet werden.",
                error
            );
        } catch (OutOfMemoryError error) {
            throw new FaceSwapException("Zu wenig Arbeitsspeicher für das ArcFace-Modell.", error);
        }
    }
}

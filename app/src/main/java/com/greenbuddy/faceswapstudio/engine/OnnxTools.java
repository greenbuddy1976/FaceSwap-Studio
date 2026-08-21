package com.greenbuddy.faceswapstudio.engine;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

public final class OnnxTools {
    private OnnxTools() {
    }

    public static OrtSession.SessionOptions stableCpuOptions() throws OrtException {
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
        // XNNPACK owns the worker pool. Keeping ORT's pool single-threaded avoids
        // two competing pools and follows ONNX Runtime's mobile recommendation.
        options.setIntraOpNumThreads(1);
        options.setInterOpNumThreads(1);
        options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        options.setMemoryPatternOptimization(true);
        options.setCPUArenaAllocator(true);
        options.addConfigEntry("session.intra_op.allow_spinning", "0");
        Map<String, String> xnnpackOptions = new HashMap<>();
        xnnpackOptions.put("intra_op_num_threads", Integer.toString(threads));
        options.addXnnpack(xnnpackOptions);
        return options;
    }

    public static FloatBuffer directFloatBuffer(float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * Float.BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer();
        buffer.put(values);
        buffer.rewind();
        return buffer;
    }

    public static float[] copyFloatOutput(ai.onnxruntime.OnnxValue value, int expectedSize)
        throws FaceSwapException {
        if (!(value instanceof ai.onnxruntime.OnnxTensor)) {
            throw new FaceSwapException("Das KI-Modell lieferte keinen Bild-Tensor zurück.");
        }
        FloatBuffer buffer = ((ai.onnxruntime.OnnxTensor) value).getFloatBuffer();
        if (buffer.remaining() != expectedSize) {
            throw new FaceSwapException(
                "Unerwartete KI-Ausgabe: " + buffer.remaining() + " statt " + expectedSize + " Werte."
            );
        }
        float[] result = new float[expectedSize];
        buffer.get(result);
        for (float number : result) {
            if (!Float.isFinite(number)) {
                throw new FaceSwapException("Das KI-Modell lieferte ungültige Zahlenwerte.");
            }
        }
        return result;
    }
}

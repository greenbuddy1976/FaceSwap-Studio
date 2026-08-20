#!/usr/bin/env python3
"""Run real CPU inference against both packaged ONNX models before APK build."""

from __future__ import annotations

import hashlib
import json
import os
import zlib
from pathlib import Path

import numpy as np
import onnxruntime as ort


ROOT = Path(__file__).resolve().parents[1]
MODEL_DIR = Path(os.environ.get("FACE_SWAP_MODEL_DIR", ROOT / "app/src/main/assets/models"))


def checksum(path: Path) -> tuple[str, str]:
    crc = 0
    sha = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(4 * 1024 * 1024):
            crc = zlib.crc32(chunk, crc)
            sha.update(chunk)
    return f"{crc & 0xFFFFFFFF:08x}", sha.hexdigest()


def session(path: Path) -> ort.InferenceSession:
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    return ort.InferenceSession(str(path), sess_options=options, providers=["CPUExecutionProvider"])


def verify_lock() -> None:
    lock = json.loads((MODEL_DIR / "models.lock.json").read_text(encoding="utf-8"))
    for name, expected in lock["models"].items():
        path = MODEL_DIR / name
        actual_crc, actual_sha = checksum(path)
        assert actual_crc == expected["crc32"], (name, actual_crc, expected["crc32"])
        assert actual_sha == expected["sha256"], (name, actual_sha, expected["sha256"])
        assert path.stat().st_size == expected["bytes"], (name, path.stat().st_size, expected["bytes"])
        print(f"checksum OK: {name}")


def verify_arcface() -> np.ndarray:
    model = session(MODEL_DIR / "arcface_w600k_r50.onnx")
    assert len(model.get_inputs()) == 1, model.get_inputs()
    input_meta = model.get_inputs()[0]
    image = np.zeros((1, 3, 112, 112), dtype=np.float32)
    output = model.run(None, {input_meta.name: image})[0]
    flat = np.asarray(output, dtype=np.float32).reshape(-1)
    assert flat.size == 512, flat.shape
    assert np.isfinite(flat).all()
    print(f"ArcFace inference OK: input={input_meta.name}, output={output.shape}")
    return flat


def prepare_inswapper_embedding(embedding: np.ndarray) -> np.ndarray:
    matrix = np.fromfile(MODEL_DIR / "emap.bin", dtype="<f4").reshape(512, 512)
    denominator = float(np.linalg.norm(embedding))
    assert np.isfinite(denominator) and denominator > 1.0e-8, denominator
    transformed = embedding.reshape(1, 512) @ matrix / denominator
    assert transformed.shape == (1, 512)
    assert np.isfinite(transformed).all()
    assert float(np.linalg.norm(transformed)) > 0.1
    print(f"INSwapper embedding transform OK: norm={np.linalg.norm(transformed):.6f}")
    return transformed.astype(np.float32).reshape(-1)


def verify_inswapper(embedding: np.ndarray) -> None:
    model = session(MODEL_DIR / "inswapper_128_fp16.onnx")
    inputs: dict[str, np.ndarray] = {}
    for item in model.get_inputs():
        shape = [int(value) if isinstance(value, int) and value > 0 else 1 for value in item.shape]
        product = int(np.prod(shape))
        if product == 512 or "source" in item.name.lower():
            inputs[item.name] = embedding.astype(np.float32).reshape(1, 512)
        elif product == 3 * 128 * 128 or "target" in item.name.lower():
            inputs[item.name] = np.zeros((1, 3, 128, 128), dtype=np.float32)
        else:
            raise AssertionError(f"Unexpected INSwapper input: {item.name} {item.shape}")
    assert len(inputs) == 2, inputs.keys()
    output = np.asarray(model.run(None, inputs)[0])
    assert output.size == 3 * 128 * 128, output.shape
    assert np.isfinite(output).all()
    assert float(np.std(output)) > 1.0e-5
    print(
        f"INSwapper inference OK: inputs={list(inputs)}, output={output.shape}, "
        f"range=({output.min():.4f}, {output.max():.4f})"
    )


def verify_emap() -> None:
    path = MODEL_DIR / "emap.bin"
    matrix = np.fromfile(path, dtype="<f4")
    assert matrix.size == 512 * 512, matrix.size
    assert np.isfinite(matrix).all()
    assert float(np.linalg.norm(matrix)) > 1.0
    print(f"EMAP OK: shape=(512, 512), norm={np.linalg.norm(matrix):.3f}")


def main() -> int:
    verify_lock()
    verify_emap()
    embedding = verify_arcface()
    verify_inswapper(prepare_inswapper_embedding(embedding))
    print("All model contracts and real CPU inference checks passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

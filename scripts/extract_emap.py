#!/usr/bin/env python3
"""Extract INSwapper's 512x512 embedding map as little-endian float32."""

from __future__ import annotations

import json
import os
from pathlib import Path

import numpy as np
import onnx
from onnx import numpy_helper


ROOT = Path(__file__).resolve().parents[1]
MODEL_DIR = Path(os.environ.get("FACE_SWAP_MODEL_DIR", ROOT / "app/src/main/assets/models"))
MODEL_PATH = MODEL_DIR / "inswapper_128_fp16.onnx"
OUTPUT_PATH = MODEL_DIR / "emap.bin"


def main() -> int:
    if not MODEL_PATH.is_file():
        raise FileNotFoundError(f"Missing model: {MODEL_PATH}")

    model = onnx.load(str(MODEL_PATH), load_external_data=False)
    candidates = [initializer for initializer in model.graph.initializer if tuple(initializer.dims) == (512, 512)]
    if not candidates:
        raise RuntimeError("No 512x512 initializer found in INSwapper model")

    selected = candidates[-1]
    matrix = numpy_helper.to_array(selected).astype("<f4", copy=False)
    if matrix.shape != (512, 512) or not np.isfinite(matrix).all():
        raise RuntimeError(f"Invalid EMAP initializer {selected.name!r}: {matrix.shape}")

    OUTPUT_PATH.write_bytes(matrix.tobytes(order="C"))
    if OUTPUT_PATH.stat().st_size != 512 * 512 * 4:
        raise RuntimeError("EMAP output has the wrong byte count")

    metadata = {
        "initializer": selected.name,
        "shape": [512, 512],
        "dtype": "float32-little-endian",
        "bytes": OUTPUT_PATH.stat().st_size,
    }
    (MODEL_DIR / "emap.metadata.json").write_text(
        json.dumps(metadata, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(f"Extracted {selected.name!r} -> {OUTPUT_PATH} ({OUTPUT_PATH.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

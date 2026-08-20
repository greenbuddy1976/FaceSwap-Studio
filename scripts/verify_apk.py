#!/usr/bin/env python3
"""Verify APK payload structure after Android build."""

from __future__ import annotations

import hashlib
import json
import sys
import zipfile
import zlib
from pathlib import Path


REQUIRED = {
    "assets/models/arcface_w600k_r50.onnx": 150_000_000,
    "assets/models/inswapper_128_fp16.onnx": 240_000_000,
    "assets/models/emap.bin": 1_048_576,
    "assets/models/models.lock.json": 100,
}


def main() -> int:
    if len(sys.argv) != 2:
        raise SystemExit("usage: verify_apk.py <apk>")
    apk = Path(sys.argv[1])
    if not apk.is_file():
        raise SystemExit(f"missing APK: {apk}")
    with zipfile.ZipFile(apk) as archive:
        names = set(archive.namelist())
        for required_entry in ("AndroidManifest.xml", "classes.dex"):
            if required_entry not in names:
                raise RuntimeError(f"APK entry is missing: {required_entry}")

        lock = json.loads(archive.read("assets/models/models.lock.json"))
        for name, minimum_size in REQUIRED.items():
            info = archive.getinfo(name)
            if info.file_size < minimum_size:
                raise RuntimeError(f"{name} is truncated: {info.file_size}")
            if name.endswith((".onnx", ".bin")) and info.compress_type != zipfile.ZIP_STORED:
                raise RuntimeError(f"{name} is compressed and cannot be memory-mapped")
            print(f"APK asset OK: {name}, bytes={info.file_size}, stored={info.compress_type == 0}")

        for filename, expected in lock["models"].items():
            asset_name = f"assets/models/{filename}"
            crc = 0
            sha = hashlib.sha256()
            size = 0
            with archive.open(asset_name) as stream:
                while chunk := stream.read(4 * 1024 * 1024):
                    crc = zlib.crc32(chunk, crc)
                    sha.update(chunk)
                    size += len(chunk)
            actual_crc = f"{crc & 0xFFFFFFFF:08x}"
            if actual_crc != expected["crc32"]:
                raise RuntimeError(f"APK CRC mismatch for {filename}: {actual_crc}")
            if sha.hexdigest() != expected["sha256"]:
                raise RuntimeError(f"APK SHA-256 mismatch for {filename}")
            if size != expected["bytes"]:
                raise RuntimeError(f"APK size mismatch for {filename}: {size}")
            print(f"APK model hash OK: {filename}, sha256={sha.hexdigest()}")

        native_entries = [name for name in names if name.startswith("lib/") and name.endswith(".so")]
        unexpected_abis = sorted({name.split("/", 2)[1] for name in native_entries} - {"arm64-v8a"})
        if unexpected_abis:
            raise RuntimeError(f"Unexpected native ABI(s): {', '.join(unexpected_abis)}")
        native = [name for name in native_entries if name.startswith("lib/arm64-v8a/")]
        if not any(name.endswith("libonnxruntime.so") for name in native):
            raise RuntimeError("arm64 ONNX Runtime library is missing")
        print(f"APK ABI OK: arm64-v8a only, {len(native)} native libraries")
    print(f"APK payload verification passed: {apk.stat().st_size} bytes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

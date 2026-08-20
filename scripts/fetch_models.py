#!/usr/bin/env python3
"""Fetch pristine model assets from one GitHub release source and verify CRC32."""

from __future__ import annotations

import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
import zlib
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MODEL_DIR = Path(os.environ.get("FACE_SWAP_MODEL_DIR", ROOT / "app/src/main/assets/models"))
BASE_URL = "https://github.com/facefusion/facefusion-assets/releases/download/models-3.0.0"
MODELS = (
    "arcface_w600k_r50.onnx",
    "inswapper_128_fp16.onnx",
)
USER_AGENT = "FaceSwap-Studio-clean-build/1.0"


def request(url: str, *, range_start: int | None = None) -> urllib.request.Request:
    headers = {"User-Agent": USER_AGENT, "Accept": "application/octet-stream"}
    if range_start:
        headers["Range"] = f"bytes={range_start}-"
    return urllib.request.Request(url, headers=headers)


def download_small(url: str) -> bytes:
    last_error: Exception | None = None
    for attempt in range(1, 6):
        try:
            with urllib.request.urlopen(request(url), timeout=45) as response:
                data = response.read(1024)
            if not data:
                raise RuntimeError("empty response")
            return data
        except (OSError, urllib.error.URLError, RuntimeError) as error:
            last_error = error
            if attempt < 5:
                time.sleep(min(20, 2**attempt))
    raise RuntimeError(f"Failed to download {url}: {last_error}")


def crc32_file(path: Path) -> str:
    checksum = 0
    with path.open("rb") as stream:
        while chunk := stream.read(4 * 1024 * 1024):
            checksum = zlib.crc32(chunk, checksum)
    return f"{checksum & 0xFFFFFFFF:08x}"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(4 * 1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def download_large(url: str, destination: Path) -> None:
    partial = destination.with_suffix(destination.suffix + ".part")
    last_error: Exception | None = None
    for attempt in range(1, 7):
        offset = partial.stat().st_size if partial.exists() else 0
        try:
            with urllib.request.urlopen(request(url, range_start=offset), timeout=90) as response:
                status = getattr(response, "status", response.getcode())
                append = offset > 0 and status == 206
                mode = "ab" if append else "wb"
                if not append:
                    offset = 0
                total_header = response.headers.get("Content-Length")
                remaining = int(total_header) if total_header and total_header.isdigit() else None
                expected = offset + remaining if remaining is not None else None
                written = offset
                with partial.open(mode) as output:
                    while chunk := response.read(1024 * 1024):
                        output.write(chunk)
                        written += len(chunk)
                        if written % (32 * 1024 * 1024) < len(chunk):
                            label = f"/{expected}" if expected else ""
                            print(f"  {destination.name}: {written}{label} bytes", flush=True)
                    output.flush()
                    os.fsync(output.fileno())
                if expected is not None and written != expected:
                    raise RuntimeError(f"truncated transfer ({written} != {expected})")
            partial.replace(destination)
            return
        except (OSError, urllib.error.URLError, RuntimeError) as error:
            last_error = error
            print(f"  retry {attempt}/6 after: {error}", file=sys.stderr, flush=True)
            if attempt < 6:
                time.sleep(min(30, 2**attempt))
    raise RuntimeError(f"Failed to download {url}: {last_error}")


def main() -> int:
    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    lock: dict[str, object] = {
        "schema": 1,
        "source": "GitHub facefusion/facefusion-assets release models-3.0.0",
        "models": {},
    }

    for filename in MODELS:
        stem = filename.removesuffix(".onnx")
        hash_url = f"{BASE_URL}/{stem}.hash"
        model_url = f"{BASE_URL}/{filename}"
        expected_crc = download_small(hash_url).decode("ascii").strip().lower()
        if not re.fullmatch(r"[0-9a-f]{8}", expected_crc):
            raise RuntimeError(f"Invalid official CRC file for {filename}: {expected_crc!r}")

        hash_path = MODEL_DIR / f"{stem}.hash"
        hash_path.write_text(expected_crc, encoding="ascii")
        model_path = MODEL_DIR / filename
        if model_path.is_file() and crc32_file(model_path) == expected_crc:
            print(f"Verified existing {filename}", flush=True)
        else:
            model_path.unlink(missing_ok=True)
            print(f"Downloading fresh {filename} from GitHub …", flush=True)
            download_large(model_url, model_path)

        actual_crc = crc32_file(model_path)
        if actual_crc != expected_crc:
            model_path.unlink(missing_ok=True)
            raise RuntimeError(f"CRC mismatch for {filename}: {actual_crc} != {expected_crc}")

        entry = {
            "url": model_url,
            "crc32": actual_crc,
            "sha256": sha256_file(model_path),
            "bytes": model_path.stat().st_size,
        }
        lock["models"][filename] = entry
        print(f"OK {filename}: {entry['bytes']} bytes, sha256={entry['sha256']}", flush=True)

    lock_path = MODEL_DIR / "models.lock.json"
    lock_path.write_text(json.dumps(lock, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Wrote {lock_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

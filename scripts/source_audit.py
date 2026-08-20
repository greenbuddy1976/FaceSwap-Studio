#!/usr/bin/env python3
"""Clean-room and structural checks that do not require Android SDK or models."""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
REQUIRED = (
    "settings.gradle.kts",
    "build.gradle.kts",
    "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/com/greenbuddy/faceswapstudio/engine/FaceSwapEngine.java",
    "app/src/main/java/com/greenbuddy/faceswapstudio/service/InferenceService.java",
    "app/src/main/java/com/greenbuddy/faceswapstudio/ui/MainActivity.java",
    ".github/workflows/android-build.yml",
)
FORBIDDEN_SOURCE_MARKERS = (
    "patch_gradle.py",
    "android-face-fusion",
    "FaceSwapNative_v4",
    "FaceSwapNative_v6",
    "FaceSwapNative_v8",
)
FORBIDDEN_NETWORK_PERMISSIONS = (
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.INTERNET",
)
RASTER_SUFFIXES = {".avif", ".gif", ".heic", ".jpeg", ".jpg", ".png", ".webp"}


def main() -> int:
    errors: list[str] = []
    for relative in REQUIRED:
        if not (ROOT / relative).is_file():
            errors.append(f"missing required file: {relative}")

    workflows = sorted((ROOT / ".github/workflows").glob("*.y*ml"))
    if len(workflows) != 1:
        errors.append(f"expected exactly one build workflow, found {len(workflows)}")

    manifest_text = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    for permission in FORBIDDEN_NETWORK_PERMISSIONS:
        pattern = rf'<uses-permission\s+android:name="{re.escape(permission)}"\s+tools:node="remove"\s*/>'
        if not re.search(pattern, manifest_text):
            errors.append(f"network permission is not explicitly removed: {permission}")

    for path in ROOT.rglob("*"):
        if not path.is_file() or ".git" in path.parts or "build" in path.parts:
            continue
        if path.suffix == ".part":
            errors.append(f"partial download is forbidden in the source tree: {path.relative_to(ROOT)}")
        if "app" in path.parts and "res" in path.parts and path.suffix.lower() in RASTER_SUFFIXES:
            errors.append(f"raster image is forbidden in the clean source tree: {path.relative_to(ROOT)}")
        if path.suffix == ".xml":
            try:
                ET.parse(path)
            except ET.ParseError as error:
                errors.append(f"invalid XML {path.relative_to(ROOT)}: {error}")
        if path.suffix in {".java", ".kt", ".kts", ".xml", ".yml", ".yaml"}:
            text = path.read_text(encoding="utf-8")
            for marker in FORBIDDEN_SOURCE_MARKERS:
                if marker in text:
                    errors.append(f"old implementation marker {marker!r} in {path.relative_to(ROOT)}")
            if "\ufeff" in text:
                errors.append(f"UTF-8 BOM found in {path.relative_to(ROOT)}")

    java_root = ROOT / "app/src/main/java"
    for path in java_root.rglob("*.java"):
        text = path.read_text(encoding="utf-8")
        match = re.search(r"^package\s+([\w.]+);", text, re.MULTILINE)
        if not match:
            errors.append(f"missing Java package in {path.relative_to(ROOT)}")
            continue
        expected = Path(*match.group(1).split(".")) / path.name
        if path.relative_to(java_root) != expected:
            errors.append(f"package/path mismatch: {path.relative_to(ROOT)} != {expected}")

    if errors:
        print("SOURCE AUDIT FAILED", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print(f"SOURCE AUDIT OK: {len(workflows)} workflow, valid XML, no old implementation markers")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

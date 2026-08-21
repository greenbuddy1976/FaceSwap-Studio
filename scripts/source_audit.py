#!/usr/bin/env python3
"""Clean-room and structural checks that do not require Android SDK or models."""

from __future__ import annotations

import hashlib
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
    "app/src/main/java/com/greenbuddy/faceswapstudio/video/VideoFaceSwapEngine.java",
    "app/src/main/java/com/greenbuddy/faceswapstudio/video/VideoLimits.java",
    "app/src/main/java/com/greenbuddy/faceswapstudio/video/AvcBitmapEncoder.java",
    "app/src/main/java/com/greenbuddy/faceswapstudio/video/Mp4AudioMuxer.java",
    "app/src/main/java/com/greenbuddy/faceswapstudio/engine/NonFiniteModelOutputException.java",
    "app/src/main/java/com/greenbuddy/faceswapstudio/service/InferenceService.java",
    "app/src/main/java/com/greenbuddy/faceswapstudio/ui/MainActivity.java",
    "app/src/androidTest/java/com/greenbuddy/faceswapstudio/FaceSwapEndToEndTest.java",
    "scripts/generate_ten_minute_test_video.sh",
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
TEST_RASTER_SHA256 = {
    "app/src/androidTest/assets/generated_faces/source.jpg":
        "d897527fd27203c4ce9805563c0c16c753cc56d192194956834e4dc3373c84e2",
    "app/src/androidTest/assets/generated_faces/target_a.jpg":
        "f1725324a7b1f768a901ab70141325fb8f217ab513e49cca1653bdb25a1da806",
    "app/src/androidTest/assets/generated_faces/target_b.jpg":
        "248d1e59b382ea16249858937646758aaf29193d093a4ccc43e807102bdd5a60",
}
TEST_VIDEO_SHA256 = {
    "app/src/androidTest/assets/generated_video/target-with-audio.mp4":
        "a97a2ae6e01ccd9651aa9d07824a015eaba6fc359c160cee8fd35e09e0e36a1d",
}
GENERATED_TEST_VIDEOS = {
    "app/src/androidTest/assets/generated_video/ten-minute-target-with-audio.mp4",
}


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

    inference_service = (
        ROOT / "app/src/main/java/com/greenbuddy/faceswapstudio/service/InferenceService.java"
    ).read_text(encoding="utf-8")
    if "MlKit.initialize(this);" not in inference_service:
        errors.append("isolated inference process must initialize ML Kit explicitly")
    if "VideoFaceSwapEngine" not in inference_service:
        errors.append("inference service must execute the video face-swap engine")
    if "TOTAL_JOB_TIMEOUT_MINUTES = 330L" not in inference_service:
        errors.append("long-form inference service timeout must remain 5.5 hours")
    if "public void onTimeout(int startId, int foregroundServiceType)" not in inference_service:
        errors.append("Android media-processing timeout callback is missing")

    video_limits = (
        ROOT / "app/src/main/java/com/greenbuddy/faceswapstudio/video/VideoLimits.java"
    ).read_text(encoding="utf-8")
    if "MAX_DURATION_MINUTES = 15" not in video_limits:
        errors.append("video engine must accept at least ten-minute inputs")
    if "MAX_SWAP_INFERENCES = 1_200" not in video_limits:
        errors.append("long-video model-inference budget is missing")

    onnx_tools = (
        ROOT / "app/src/main/java/com/greenbuddy/faceswapstudio/engine/OnnxTools.java"
    ).read_text(encoding="utf-8")
    if "stableCpuFallbackOptions" not in onnx_tools or "retryNonFinite" not in onnx_tools:
        errors.append("automatic non-finite ONNX CPU retry is missing")

    obsolete_engine = ROOT / "app/src/main/java/com/greenbuddy/faceswapstudio/engine/FaceSwapEngine.java"
    if obsolete_engine.exists():
        errors.append("photo-to-photo FaceSwapEngine.java is forbidden")

    main_activity = (
        ROOT / "app/src/main/java/com/greenbuddy/faceswapstudio/ui/MainActivity.java"
    ).read_text(encoding="utf-8")
    for required_marker in (
        'new ActivityResultContracts.CreateDocument("video/mp4")',
        'videoPicker.launch(new String[] { "video/mp4" })',
        "faceButton.setEnabled(!busy && videoUri != null)",
        '"FaceSwap_Video_"',
    ):
        if required_marker not in main_activity:
            errors.append(f"video-only UI marker is missing: {required_marker}")
    for forbidden_marker in ("targetPreview", "sourcePreview", 'CreateDocument("image/jpeg")'):
        if forbidden_marker in main_activity:
            errors.append(f"photo-to-photo UI marker is forbidden: {forbidden_marker}")

    for path in ROOT.rglob("*"):
        if (
            not path.is_file()
            or any(part in {".git", ".gradle", ".venv", "build"} for part in path.parts)
        ):
            continue
        relative = path.relative_to(ROOT).as_posix()
        if path.suffix == ".part":
            errors.append(f"partial download is forbidden in the source tree: {relative}")
        if path.suffix.lower() in RASTER_SUFFIXES:
            expected_hash = TEST_RASTER_SHA256.get(relative)
            if expected_hash is None:
                errors.append(f"unapproved raster image is forbidden in the clean source tree: {relative}")
            elif hashlib.sha256(path.read_bytes()).hexdigest() != expected_hash:
                errors.append(f"generated test portrait hash mismatch: {relative}")
        if path.suffix.lower() == ".mp4" and relative.startswith("app/src/androidTest/assets/"):
            expected_hash = TEST_VIDEO_SHA256.get(relative)
            if expected_hash is not None:
                if hashlib.sha256(path.read_bytes()).hexdigest() != expected_hash:
                    errors.append(f"generated test video hash mismatch: {relative}")
            elif relative in GENERATED_TEST_VIDEOS:
                if path.stat().st_size < 100_000:
                    errors.append(f"fresh long-duration test video is truncated: {relative}")
            else:
                errors.append(f"unapproved MP4 is forbidden in Android test assets: {relative}")
        if path.suffix == ".xml":
            try:
                ET.parse(path)
            except ET.ParseError as error:
                errors.append(f"invalid XML {relative}: {error}")
        if path.suffix in {".java", ".kt", ".kts", ".xml", ".yml", ".yaml"}:
            text = path.read_text(encoding="utf-8")
            for marker in FORBIDDEN_SOURCE_MARKERS:
                if marker in text:
                    errors.append(f"old implementation marker {marker!r} in {relative}")
            if "\ufeff" in text:
                errors.append(f"UTF-8 BOM found in {relative}")

    for relative in TEST_RASTER_SHA256:
        if not (ROOT / relative).is_file():
            errors.append(f"missing generated end-to-end test portrait: {relative}")
    for relative in TEST_VIDEO_SHA256:
        if not (ROOT / relative).is_file():
            errors.append(f"missing generated end-to-end test video: {relative}")
    for relative in GENERATED_TEST_VIDEOS:
        if not (ROOT / relative).is_file():
            errors.append(f"missing freshly generated long-duration test video: {relative}")

    end_to_end_test = (
        ROOT / "app/src/androidTest/java/com/greenbuddy/faceswapstudio/FaceSwapEndToEndTest.java"
    ).read_text(encoding="utf-8")
    for marker in (
        "assertAudioPayloadUnchanged",
        "compressed audio payload changed",
        "FACESWAP_STABLE_CPU_FALLBACK_INFERENCE_PASS",
        "FACESWAP_TEN_MINUTE_VIDEO_E2E_PASS",
        "FACESWAP_VIDEO_E2E_FULL_PASS",
    ):
        if marker not in end_to_end_test:
            errors.append(f"video end-to-end assertion is missing: {marker}")

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
    print(
        "SOURCE AUDIT OK: "
        f"{len(workflows)} workflow, valid XML, no old implementation markers, "
        f"{len(TEST_RASTER_SHA256)} hash-locked new test portraits and "
        f"{len(TEST_VIDEO_SHA256)} hash-locked MP4 plus "
        f"{len(GENERATED_TEST_VIDEOS)} fresh long-duration MP4"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

# FaceSwap Studio

Clean-room Android app for a private, on-device image face swap. The source tree was created from zero and does not contain files, overlays, patches, images, or build fragments from earlier FaceSwap builds.

## What this build does

- Select one source portrait and one target image.
- Detect the largest face in both images with the bundled ML Kit detector.
- Create a 512-value ArcFace identity embedding.
- Run INSwapper 128 FP16 locally with ONNX Runtime.
- Color-match and blend the swapped face into the target.
- Save a JPEG through Android's system file picker.
- Upload no user images, request no network permission, and request no gallery-wide storage permission.

This first clean build intentionally supports **image to image** only. It has one processing path and one GitHub Actions job.

## Why it cannot silently stop or run for hours

- Models are fetched, checksum-verified, inference-tested, and placed into the APK during the GitHub build. There is no first-launch model download.
- Inference runs inside the separate `:inference` Android process.
- Every long model stage emits a five-second heartbeat with elapsed time.
- If the isolated process disappears, the UI reports it after 30 seconds without a heartbeat.
- Every model stage has a 90-second deadline and the complete job has a hard two-minute limit.
- A timed-out or cancelled inference process is terminated without freezing the main UI.
- Input images are downsampled before inference to prevent avoidable memory exhaustion.
- ONNX Runtime uses its mobile XNNPACK execution path with a bounded worker pool.

## Reproducible GitHub build

Run **Actions → FaceSwap production verification → Run workflow**. One GitHub runner performs every gate before it is allowed to upload the release APK:

1. Clean-room source and XML audit.
2. Fresh model download from the GitHub `facefusion/facefusion-assets` release.
3. Official CRC32 verification plus a recorded SHA-256 and byte count.
4. EMAP extraction from the exact packaged INSwapper graph.
5. Real CPU inference through ArcFace and INSwapper using synthetic tensors.
6. A hardware-accelerated Android x86_64 emulator runs the actual app engine with three newly generated, hash-locked portraits.
7. The isolated Android `:inference` process must create a visibly changed JPEG in at most 60 seconds.
8. A second varied face pair must also finish in at most 60 seconds.
9. Corrupt-image rejection and cancellation must finish quickly instead of hanging.
10. Java unit tests, Android lint, and release APK assembly.
11. Stable release-certificate, APK signature, zip alignment, ARM64 payload, model hashes and non-debuggable status are verified.
12. The merged manifest must contain neither Internet nor network-state permission.

The artifact is named `FaceSwap-Studio-1.0.0-PRODUCTION-VERIFIED`. It contains the installable release-signed ARM64 APK, its portable SHA-256 checksum, the model lock and the measured verification summary. Failed runs do not publish a release APK.

## Technical requirements

- Android 8.0 or newer (`minSdk 26`)
- ARM64 device
- Approximately 1 GB free device storage for the large self-contained APK
- Enough free RAM for on-device inference; closing other large apps helps on low-memory devices

The 60-second gate is measured on the GitHub Android emulator. Real devices vary, so the app also enforces an unconditional two-minute stop instead of claiming success while hanging.

## Models and licensing

The app's own source code is MIT-licensed. The build downloads `arcface_w600k_r50.onnx` and `inswapper_128_fp16.onnx` from the FaceFusion GitHub assets release. Those pretrained InsightFace-derived models are marked for non-commercial research use by their providers. This repository does not claim commercial rights to those weights. Obtain the appropriate model license before commercial distribution.

Use only images you are permitted to edit. Do not use the app for deceptive or non-consensual material.

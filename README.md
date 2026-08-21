# FaceSwap Studio

Clean-room Android app for a private, on-device video face swap. The source tree was created from zero and does not contain files, overlays, patches, or build fragments from earlier FaceSwap builds.

## What this build does

- Select the target MP4 video first, then a photo containing the new face.
- Detect the new face once and the largest visible face in each video frame.
- Create a 512-value ArcFace identity embedding.
- Reuse one INSwapper 128 FP16 ONNX session for the complete video.
- Accept MP4 inputs up to 15 minutes long; ten-minute processing is covered by an Android device test.
- Color-match and blend the swapped face into the video frames.
- Encode H.264 MP4, copy the original compressed audio track unchanged, and save through Android's system file picker.
- Upload neither video nor photo, request no network permission, and request no gallery-wide storage permission.

There is no photo-to-photo mode. The only processing path is **video + face photo → MP4 video**, built by one GitHub Actions job on one runner.

## Long-video processing and failure handling

- Models are fetched, checksum-verified, inference-tested, and placed into the APK during the GitHub build. There is no first-launch model download.
- Inference runs inside the separate `:inference` Android process.
- Every long model stage emits a five-second heartbeat with elapsed time.
- If the isolated process disappears, the UI reports it after 30 seconds without a heartbeat.
- Every active frame/model stage has a two-minute deadline and the complete job has a 5-hour-30-minute safety limit.
- Android 15's `mediaProcessing` timeout callback is handled so the app exits cleanly instead of crashing at the platform limit.
- A timed-out or cancelled inference process is terminated without freezing the main UI.
- Source photos are downsampled and output frames are capped at a 720-pixel long edge and 12 fps.
- Face position is detected and blended on every output frame. For long videos, at most 1,200 expensive INSwapper evaluations are reused between nearby frames.
- ONNX Runtime uses its mobile XNNPACK execution path with a bounded worker pool.
- If accelerated ARM inference returns non-finite values, ArcFace or INSwapper is retried once with ONNX Runtime's conservative CPU backend.

## Reproducible GitHub build

Run **Actions → FaceSwap production verification → Run workflow**. One GitHub runner performs every gate before it is allowed to upload the release APK:

1. Clean-room source and XML audit.
2. Fresh model download from the GitHub `facefusion/facefusion-assets` release.
3. Official CRC32 verification plus a recorded SHA-256 and byte count.
4. EMAP extraction from the exact packaged INSwapper graph.
5. Real CPU inference through ArcFace and INSwapper using synthetic tensors.
6. A hardware-accelerated Android x86_64 emulator runs the actual app engine with a newly generated, hash-locked MP4 containing H.264 video and AAC audio.
7. The isolated Android `:inference` process must create a visibly changed H.264 MP4 in at most 240 seconds.
8. The test compares the compressed audio samples and requires them to be byte-identical to the original audio track.
9. Both ArcFace and INSwapper must also produce finite values through the real Android CPU fallback backend.
10. A separate real 600-second H.264/AAC input must finish without being shortened, contain a visible swapped face, and retain byte-identical audio.
11. Corrupt-video rejection and cancellation must finish quickly instead of hanging.
12. Java unit tests, Android lint, and release APK assembly.
13. Stable release-certificate, APK signature, zip alignment, ARM64 payload, model hashes and non-debuggable status are verified.
14. The merged manifest must contain neither Internet nor network-state permission.

Only after every gate passes, GitHub uploads the unzipped installable file `FaceSwap-Studio-VIDEO-1.2.0-RELEASE-arm64.apk`. Its artifact digest is compared with the local APK SHA-256. Failed runs do not publish an APK.

## Technical requirements

- Android 8.0 or newer (`minSdk 26`)
- ARM64 device
- Approximately 1 GB plus room for the selected and generated videos
- Enough free RAM for on-device inference; closing other large apps helps on low-memory devices

The 240-second gate applies only to the short full-face test. The separate duration test processes an actual ten-minute MP4. Real-device time still depends on video content, duration, and hardware; the app reports a five-second heartbeat and stops safely before Android 15's six-hour media-processing allowance.

## Models and licensing

The app's own source code is MIT-licensed. The build downloads `arcface_w600k_r50.onnx` and `inswapper_128_fp16.onnx` from the FaceFusion GitHub assets release. Those pretrained InsightFace-derived models are marked for non-commercial research use by their providers. This repository does not claim commercial rights to those weights. Obtain the appropriate model license before commercial distribution.

Use only videos and photos you are permitted to edit. Do not use the app for deceptive or non-consensual material.

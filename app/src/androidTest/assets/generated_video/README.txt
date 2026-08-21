Both MP4 files are deterministic Android test fixtures and are never packaged in the release APK.

target-with-audio.mp4
- 2 seconds, 384x384, 4 fps, H.264 + AAC
- SHA-256 a97a2ae6e01ccd9651aa9d07824a015eaba6fc359c160cee8fd35e09e0e36a1d

ten-minute-target-with-audio.mp4
- 600 seconds, 256x256, 3 fps, H.264 + AAC
- The generated portrait is visible at the start; the remaining frames are black so CI can verify
  the complete long-duration decode/encode/audio path without thousands of redundant model calls.
- Generated freshly on the single GitHub runner and validated by duration, codec, dimensions,
  frame rate, audio track, minimum size, and the hash-locked two-second source fixture.

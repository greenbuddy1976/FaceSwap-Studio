#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INPUT="$ROOT/app/src/androidTest/assets/generated_video/target-with-audio.mp4"
OUTPUT="$ROOT/app/src/androidTest/assets/generated_video/ten-minute-target-with-audio.mp4"
EXPECTED_INPUT_SHA256="a97a2ae6e01ccd9651aa9d07824a015eaba6fc359c160cee8fd35e09e0e36a1d"

command -v ffmpeg >/dev/null
command -v ffprobe >/dev/null
test -f "$INPUT"
test "$(sha256sum "$INPUT" | awk '{print $1}')" = "$EXPECTED_INPUT_SHA256"

ffmpeg -nostdin -hide_banner -loglevel error -y \
  -i "$INPUT" \
  -f lavfi -i 'color=c=black:s=256x256:r=3:d=598' \
  -f lavfi -i 'sine=frequency=523:sample_rate=44100:duration=600' \
  -filter_complex \
  '[0:v]trim=duration=2,setpts=PTS-STARTPTS,fps=3,scale=256:256:flags=lanczos[v0];[1:v]setpts=PTS-STARTPTS[v1];[v0][v1]concat=n=2:v=1:a=0[v]' \
  -map '[v]' -map 2:a -t 600 \
  -c:v libx264 -preset veryfast -crf 28 -pix_fmt yuv420p \
  -g 3 -keyint_min 3 -sc_threshold 0 \
  -c:a aac -b:a 24k -movflags +faststart \
  "$OUTPUT"

test "$(ffprobe -v error -show_entries format=duration -of default=nk=1:nw=1 "$OUTPUT")" = "600.000000"
test "$(ffprobe -v error -select_streams v:0 -show_entries stream=codec_name -of default=nk=1:nw=1 "$OUTPUT")" = "h264"
test "$(ffprobe -v error -select_streams v:0 -show_entries stream=width -of default=nk=1:nw=1 "$OUTPUT")" = "256"
test "$(ffprobe -v error -select_streams v:0 -show_entries stream=height -of default=nk=1:nw=1 "$OUTPUT")" = "256"
test "$(ffprobe -v error -select_streams v:0 -show_entries stream=avg_frame_rate -of default=nk=1:nw=1 "$OUTPUT")" = "3/1"
test "$(ffprobe -v error -select_streams a:0 -show_entries stream=codec_name -of default=nk=1:nw=1 "$OUTPUT")" = "aac"
test "$(stat -c '%s' "$OUTPUT")" -gt 100000

echo "TEN-MINUTE TEST VIDEO OK: 600 seconds, 256x256 at 3 fps, H.264 + AAC"
sha256sum "$OUTPUT"

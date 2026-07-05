#!/bin/sh
set -eu

cd "$(dirname "$0")"

exec python3 -u app.py \
  --camera /dev/video2 \
  --width 640 \
  --height 480 \
  --camera-fps 30 \
  --no-display \
  --photo-rearm-seconds 10

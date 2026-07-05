#!/bin/sh
# Board-side starter.
# Change PC_IP to your Windows/backend computer IP.
# Change FACE_DIR if your face image directory is different.
# Do NOT use 127.0.0.1 unless the HTTP server is running on the i.MX93 board.

PC_IP="192.168.43.1"
PORT="8000"

# 默认人脸图片目录。如果你的实际目录不是这个，只改这一行。
FACE_DIR="/root/imx93_age_action_board/output_results/camera_face"

cd /root/imx93_age_action_board || exit 1

python3 -u http_json_push.py \
  --target "http://${PC_IP}:${PORT}" \
  --person-path "/person" \
  --action-path "/action" \
  --face-path "/face" \
  --face-dir "${FACE_DIR}" \
  --poll-ms 100 \
  --stable-ms 100 \
  --http-timeout 2.0 \
  --retry-ms 500

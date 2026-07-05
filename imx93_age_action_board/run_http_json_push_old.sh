#!/bin/sh
# Board-side starter.
# Change PC_IP to your Windows/backend computer IP.
# Do NOT use 127.0.0.1 unless the HTTP server is running on the i.MX93 board.

PC_IP="192.168.43.221"
PORT="8000"

cd /root/imx93_age_action_board || exit 1

python3 -u http_json_push.py \
  --target "http://${PC_IP}:${PORT}" \
  --person-path "/person" \
  --action-path "/action" \
  --poll-ms 100 \
  --stable-ms 50 \
  --http-timeout 1.0 \
  --retry-ms 500

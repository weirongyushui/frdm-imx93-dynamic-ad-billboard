#!/bin/sh
set +e

PROJECT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
cd "$PROJECT_DIR"

STAMP="$(date +%Y%m%d_%H%M%S 2>/dev/null || echo unknown)"
OUT="board_debug_${STAMP}.txt"

{
  echo "========== DATE =========="
  date
  echo

  echo "========== OS =========="
  cat /etc/os-release 2>/dev/null
  uname -a
  echo

  echo "========== PYTHON =========="
  python3 --version
  command -v python3
  python3 -m pip --version 2>/dev/null
  echo

  echo "========== PROJECT =========="
  pwd
  find . -maxdepth 2 -type f -printf "%p %s bytes\n" 2>/dev/null | sort
  echo

  echo "========== CAMERA =========="
  ls -l /dev/video* 2>/dev/null
  command -v v4l2-ctl 2>/dev/null
  v4l2-ctl --list-devices 2>/dev/null
  fuser /dev/video0 2>/dev/null
  echo

  echo "========== ETHOS-U =========="
  find /usr/lib /usr/lib64 /lib /lib64 \
    -name "libethosu_delegate.so*" 2>/dev/null
  command -v vela 2>/dev/null
  vela --version 2>/dev/null
  echo

  echo "========== PYTHON CHECK =========="
  python3 check_board.py
  echo

  echo "========== PROCESSES =========="
  ps aux 2>/dev/null | grep -E "python|camera|gst|v4l2" | grep -v grep
} >"$OUT" 2>&1

echo "调试信息已保存：$PROJECT_DIR/$OUT"
#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Optional PC-side test receiver.

Run on Windows/local backend computer:
    python local_8000_receiver.py

Then start board-side pusher with target:
    http://<THIS_PC_IP>:8000

This test server:
- prints all JSON POST bodies
- saves latest face image from POST /face to ./received_faces/latest_face.<ext>
- replies 200 OK
"""

from __future__ import annotations

import json
from http.server import BaseHTTPRequestHandler, HTTPServer
from datetime import datetime
from pathlib import Path
from urllib.parse import urlparse


SAVE_DIR = Path("received_faces")
SAVE_DIR.mkdir(exist_ok=True)


def ext_from_content_type(content_type: str) -> str:
    content_type = (content_type or "").lower().split(";")[0].strip()
    if content_type == "image/jpeg":
        return ".jpg"
    if content_type == "image/png":
        return ".png"
    if content_type == "image/bmp":
        return ".bmp"
    if content_type == "image/webp":
        return ".webp"
    return ".jpg"


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0") or "0")
        body = self.rfile.read(length)
        path = urlparse(self.path).path
        content_type = self.headers.get("Content-Type", "")

        print("=" * 60, flush=True)
        print(datetime.now().strftime("%Y-%m-%d %H:%M:%S"), flush=True)
        print("PATH:", path, flush=True)
        print("Content-Type:", content_type, flush=True)

        if path == "/face" or content_type.lower().startswith("image/"):
            filename = self.headers.get("X-Filename", "")
            ext = Path(filename).suffix.lower() if filename else ""
            if ext not in [".jpg", ".jpeg", ".png", ".bmp", ".webp"]:
                ext = ext_from_content_type(content_type)

            latest_path = SAVE_DIR / f"latest_face{ext}"
            latest_path.write_bytes(body)

            # 也额外保存一份带时间的历史文件，方便你排查；真正用的时候只读 latest_face 即可。
            ts = datetime.now().strftime("%Y%m%d_%H%M%S_%f")
            history_path = SAVE_DIR / f"face_{ts}{ext}"
            history_path.write_bytes(body)

            print("FACE filename:", filename, flush=True)
            print("FACE bytes:", len(body), flush=True)
            print("SAVED latest:", latest_path.resolve(), flush=True)
            print("SAVED history:", history_path.resolve(), flush=True)
        else:
            try:
                text = body.decode("utf-8")
                data = json.loads(text)
            except Exception:
                text = body.decode("utf-8", errors="replace")
                data = None

            print("BODY:", text, flush=True)
            print("JSON:", data, flush=True)

        response = b'{"ok":true}\n'
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(response)))
        self.end_headers()
        self.wfile.write(response)

    def log_message(self, format, *args):
        # Keep output clean.
        return


def main():
    server = HTTPServer(("0.0.0.0", 8000), Handler)
    print("HTTP test receiver listening on 0.0.0.0:8000")
    print("Endpoints accepted: /person, /action, /face")
    print("Face images will be saved into:", SAVE_DIR.resolve())
    print("Press Ctrl+C to stop.")
    server.serve_forever()


if __name__ == "__main__":
    main()

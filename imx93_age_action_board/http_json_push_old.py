#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
i.MX93 board-side JSON HTTP pusher.

Function:
- Watch person_latest.json and action_latest.json on the board.
- Only when required fields change, POST the JSON fields to a local PC/server.
- No camera access. No model inference. No file deletion.

Default watched files:
  /root/imx93_age_action_board/output_results/backend_events/person_latest.json
  /root/imx93_age_action_board/output_results/backend_events/action_latest.json

Default HTTP targets:
  http://<PC_IP>:8000/person
  http://<PC_IP>:8000/action

Important:
- Do NOT use 127.0.0.1 unless the receiver is running on the board itself.
- If the receiver runs on Windows, use the Windows computer's LAN/hotspot IP.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


PROJECT_DIR = Path("/root/imx93_age_action_board")
DEFAULT_EVENT_DIR = PROJECT_DIR / "output_results" / "backend_events"
DEFAULT_PERSON_JSON = DEFAULT_EVENT_DIR / "person_latest.json"
DEFAULT_ACTION_JSON = DEFAULT_EVENT_DIR / "action_latest.json"


def now_text() -> str:
    return time.strftime("%Y-%m-%d %H:%M:%S")


def normalize_base_url(base_url: str) -> str:
    base_url = base_url.strip()
    if not base_url:
        raise ValueError("target base URL is empty")
    return base_url.rstrip("/")


def join_url(base_url: str, path: str) -> str:
    base_url = normalize_base_url(base_url)
    if not path:
        return base_url
    if not path.startswith("/"):
        path = "/" + path
    return base_url + path


def wait_file_stable(path: Path, stable_ms: int) -> bool:
    """
    Avoid reading while another process is still replacing/writing the file.
    Atomic os.replace is already used by the model scripts, but this adds
    an extra safety delay for demos.
    """
    if not path.exists():
        return False

    try:
        first = path.stat()
    except OSError:
        return False

    time.sleep(max(stable_ms, 0) / 1000.0)

    try:
        second = path.stat()
    except OSError:
        return False

    return (
        first.st_mtime_ns == second.st_mtime_ns
        and first.st_size == second.st_size
        and second.st_size > 0
    )


def read_json_object(path: Path) -> dict[str, Any] | None:
    try:
        text = path.read_text(encoding="utf-8")
        data = json.loads(text)
    except Exception as error:
        print(
            f"[{now_text()}] [WARN] failed to read JSON {path}: {error}",
            flush=True,
        )
        return None

    if not isinstance(data, dict):
        print(
            f"[{now_text()}] [WARN] JSON is not object: {path}",
            flush=True,
        )
        return None

    return data


def extract_person_fields(data: dict[str, Any]) -> dict[str, Any] | None:
    if "age" not in data or "gender" not in data:
        return None

    try:
        age = int(data["age"])
    except Exception:
        return None

    gender = str(data["gender"]).strip().lower()

    if gender not in ("male", "female"):
        # Keep a fallback instead of crashing, but still send the original text.
        gender = str(data["gender"]).strip()

    return {
        "age": age,
        "gender": gender,
    }


def extract_action_fields(data: dict[str, Any]) -> dict[str, Any] | None:
    if "action" not in data or "sequence" not in data:
        return None

    action = str(data["action"]).strip()

    try:
        sequence = int(data["sequence"])
    except Exception:
        return None

    return {
        "action": action,
        "sequence": sequence,
    }


def canonical_payload(payload: dict[str, Any]) -> str:
    return json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def post_json(
    url: str,
    payload: dict[str, Any],
    timeout_seconds: float,
) -> tuple[bool, str]:
    body = json.dumps(
        payload,
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")

    request = urllib.request.Request(
        url=url,
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/json; charset=utf-8",
            "Accept": "application/json, text/plain, */*",
            "User-Agent": "imx93-json-pusher/1.0",
        },
    )

    try:
        with urllib.request.urlopen(
            request,
            timeout=timeout_seconds,
        ) as response:
            status = int(response.status)
            try:
                response_text = response.read(200).decode(
                    "utf-8",
                    errors="replace",
                )
            except Exception:
                response_text = ""

        if 200 <= status < 300:
            return True, f"HTTP {status} {response_text}".strip()

        return False, f"HTTP {status} {response_text}".strip()

    except urllib.error.HTTPError as error:
        try:
            error_text = error.read(200).decode(
                "utf-8",
                errors="replace",
            )
        except Exception:
            error_text = ""
        return False, f"HTTPError {error.code} {error_text}".strip()

    except Exception as error:
        return False, str(error)


class JsonFileWatcher:
    def __init__(
        self,
        name: str,
        path: Path,
        extractor,
        url: str,
    ) -> None:
        self.name = name
        self.path = path
        self.extractor = extractor
        self.url = url

        self.last_mtime_ns: int = -1
        self.last_seen_payload_key: str | None = None
        self.last_sent_payload_key: str | None = None
        self.pending_payload: dict[str, Any] | None = None
        self.pending_key: str | None = None

    def scan(
        self,
        stable_ms: int,
        send_on_start: bool,
        startup_phase: bool,
    ) -> None:
        if not self.path.exists():
            return

        try:
            stat = self.path.stat()
        except OSError:
            return

        if stat.st_mtime_ns == self.last_mtime_ns:
            return

        if not wait_file_stable(self.path, stable_ms):
            return

        self.last_mtime_ns = stat.st_mtime_ns

        data = read_json_object(self.path)
        if data is None:
            return

        payload = self.extractor(data)
        if payload is None:
            print(
                f"[{now_text()}] [WARN] {self.name}: required fields missing or invalid in {self.path}",
                flush=True,
            )
            return

        key = canonical_payload(payload)

        if startup_phase and not send_on_start:
            self.last_seen_payload_key = key
            self.last_sent_payload_key = key
            print(
                f"[{now_text()}] [{self.name}] startup baseline only: {payload}",
                flush=True,
            )
            return

        if key == self.last_seen_payload_key:
            return

        self.last_seen_payload_key = key
        self.pending_payload = payload
        self.pending_key = key

        print(
            f"[{now_text()}] [{self.name}] fields changed, pending POST: {payload}",
            flush=True,
        )

    def try_send(
        self,
        timeout_seconds: float,
    ) -> None:
        if self.pending_payload is None or self.pending_key is None:
            return

        ok, message = post_json(
            url=self.url,
            payload=self.pending_payload,
            timeout_seconds=timeout_seconds,
        )

        if ok:
            self.last_sent_payload_key = self.pending_key
            print(
                f"[{now_text()}] [{self.name}] POST OK -> {self.url} ; {self.pending_payload}",
                flush=True,
            )
            self.pending_payload = None
            self.pending_key = None
        else:
            print(
                f"[{now_text()}] [{self.name}] POST FAILED -> {self.url} ; {message}",
                flush=True,
            )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Watch i.MX93 person/action JSON files and POST changed fields "
            "to a local HTTP server."
        )
    )

    parser.add_argument(
        "--target",
        required=True,
        help=(
            "Local backend base URL, for example: http://192.168.43.1:8000 . "
            "Do not use 127.0.0.1 unless backend runs on the board."
        ),
    )
    parser.add_argument(
        "--person-path",
        default="/person",
        help="POST path for person JSON. Default: /person",
    )
    parser.add_argument(
        "--action-path",
        default="/action",
        help="POST path for action JSON. Default: /action",
    )
    parser.add_argument(
        "--person-json",
        default=str(DEFAULT_PERSON_JSON),
        help=f"person_latest.json path. Default: {DEFAULT_PERSON_JSON}",
    )
    parser.add_argument(
        "--action-json",
        default=str(DEFAULT_ACTION_JSON),
        help=f"action_latest.json path. Default: {DEFAULT_ACTION_JSON}",
    )
    parser.add_argument(
        "--poll-ms",
        type=int,
        default=100,
        help="File polling interval in milliseconds. Default: 100",
    )
    parser.add_argument(
        "--stable-ms",
        type=int,
        default=50,
        help="Wait this long to ensure file is stable before reading. Default: 50",
    )
    parser.add_argument(
        "--http-timeout",
        type=float,
        default=1.0,
        help="HTTP POST timeout seconds. Default: 1.0",
    )
    parser.add_argument(
        "--retry-ms",
        type=int,
        default=500,
        help="Retry failed pending POST every N milliseconds. Default: 500",
    )
    parser.add_argument(
        "--send-current-on-start",
        action="store_true",
        help=(
            "POST current JSON values at startup if files already exist. "
            "Default is false: build baseline only."
        ),
    )

    return parser


def main() -> int:
    args = build_parser().parse_args()

    target = normalize_base_url(args.target)
    person_url = join_url(target, args.person_path)
    action_url = join_url(target, args.action_path)

    person_watcher = JsonFileWatcher(
        name="person",
        path=Path(args.person_json),
        extractor=extract_person_fields,
        url=person_url,
    )

    action_watcher = JsonFileWatcher(
        name="action",
        path=Path(args.action_json),
        extractor=extract_action_fields,
        url=action_url,
    )

    print("==============================================", flush=True)
    print("i.MX93 JSON HTTP pusher started", flush=True)
    print(f"person file: {person_watcher.path}", flush=True)
    print(f"action file: {action_watcher.path}", flush=True)
    print(f"person POST: {person_url}", flush=True)
    print(f"action POST: {action_url}", flush=True)
    print(f"poll: {args.poll_ms} ms", flush=True)
    print(f"send current on start: {args.send_current_on_start}", flush=True)
    print("Press Ctrl+C to stop.", flush=True)
    print("==============================================", flush=True)

    startup_phase = True
    last_retry_time = 0.0

    try:
        while True:
            person_watcher.scan(
                stable_ms=args.stable_ms,
                send_on_start=args.send_current_on_start,
                startup_phase=startup_phase,
            )
            action_watcher.scan(
                stable_ms=args.stable_ms,
                send_on_start=args.send_current_on_start,
                startup_phase=startup_phase,
            )

            startup_phase = False

            now = time.monotonic()
            if (
                now - last_retry_time
                >= max(args.retry_ms, 50) / 1000.0
            ):
                person_watcher.try_send(
                    timeout_seconds=args.http_timeout,
                )
                action_watcher.try_send(
                    timeout_seconds=args.http_timeout,
                )
                last_retry_time = now

            time.sleep(max(args.poll_ms, 10) / 1000.0)

    except KeyboardInterrupt:
        print(f"\n[{now_text()}] stopped by user", flush=True)
        return 0


if __name__ == "__main__":
    raise SystemExit(main())

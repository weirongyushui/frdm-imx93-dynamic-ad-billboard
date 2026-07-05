#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Board-side image directory cleaner.

Purpose:
- Monitor face image directory on i.MX93 board.
- When image files take more than MAX_SIZE_GB, delete oldest images.
- Clean down to TARGET_SIZE_GB to avoid repeated cleanup.
- Only deletes image files in FACE_DIR.
- Does NOT delete JSON, models, scripts, or folders.

Default:
  FACE_DIR=/root/imx93_age_action_board/input_photos
  Trigger cleanup when images > 5.0 GB
  Clean down to 4.5 GB
"""

from __future__ import annotations

import argparse
import time
from pathlib import Path


IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}


def now_text() -> str:
    return time.strftime("%Y-%m-%d %H:%M:%S")


def gb_to_bytes(value: float) -> int:
    return int(value * 1024 * 1024 * 1024)


def find_images(directory: Path, recursive: bool) -> list[Path]:
    if not directory.exists() or not directory.is_dir():
        return []

    iterator = directory.rglob("*") if recursive else directory.iterdir()

    result: list[Path] = []
    for path in iterator:
        try:
            if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS:
                result.append(path)
        except OSError:
            continue

    return result


def safe_stat(path: Path):
    try:
        return path.stat()
    except OSError:
        return None


def clean_once(
    directory: Path,
    max_bytes: int,
    target_bytes: int,
    min_age_seconds: int,
    recursive: bool,
    dry_run: bool,
) -> None:
    images = find_images(directory, recursive=recursive)

    file_infos = []
    total_size = 0

    now = time.time()

    for path in images:
        stat = safe_stat(path)
        if stat is None:
            continue
        total_size += stat.st_size
        file_infos.append((path, stat.st_size, stat.st_mtime))

    print(
        f"[{now_text()}] [cleaner] image_count={len(file_infos)} "
        f"total={total_size / 1024 / 1024 / 1024:.3f} GB "
        f"limit={max_bytes / 1024 / 1024 / 1024:.3f} GB",
        flush=True,
    )

    if total_size <= max_bytes:
        return

    # Delete oldest first. Skip very new files to avoid deleting files being written or just detected.
    file_infos.sort(key=lambda item: item[2])

    deleted_count = 0
    deleted_size = 0
    current_size = total_size

    for path, size, mtime in file_infos:
        if current_size <= target_bytes:
            break

        age = now - mtime
        if age < min_age_seconds:
            continue

        if dry_run:
            print(
                f"[{now_text()}] [cleaner] DRY-RUN delete oldest: {path} "
                f"size={size} age={age:.1f}s",
                flush=True,
            )
        else:
            try:
                path.unlink()
                print(
                    f"[{now_text()}] [cleaner] deleted oldest image: {path} "
                    f"size={size}",
                    flush=True,
                )
            except Exception as error:
                print(
                    f"[{now_text()}] [cleaner] delete failed: {path} ; {error}",
                    flush=True,
                )
                continue

        deleted_count += 1
        deleted_size += size
        current_size -= size

    print(
        f"[{now_text()}] [cleaner] cleanup done. "
        f"deleted={deleted_count}, freed={deleted_size / 1024 / 1024:.2f} MB, "
        f"now={current_size / 1024 / 1024 / 1024:.3f} GB",
        flush=True,
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Clean old face images when image directory exceeds size limit."
    )
    parser.add_argument(
        "--dir",
        default="/root/imx93_age_action_board/input_photos",
        help="Image directory to clean. Default: /root/imx93_age_action_board/input_photos",
    )
    parser.add_argument(
        "--max-gb",
        type=float,
        default=5.0,
        help="Trigger cleanup when images exceed this size in GB. Default: 5.0",
    )
    parser.add_argument(
        "--target-gb",
        type=float,
        default=4.5,
        help="Delete oldest images until total is below this size in GB. Default: 4.5",
    )
    parser.add_argument(
        "--interval",
        type=int,
        default=60,
        help="Check interval seconds. Default: 60",
    )
    parser.add_argument(
        "--min-age-seconds",
        type=int,
        default=300,
        help="Never delete images newer than this many seconds. Default: 300",
    )
    parser.add_argument(
        "--recursive",
        action="store_true",
        help="Scan subdirectories recursively. Default: false",
    )
    parser.add_argument(
        "--once",
        action="store_true",
        help="Run cleanup once then exit.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Only print what would be deleted, do not delete files.",
    )
    return parser


def main() -> int:
    args = build_parser().parse_args()

    directory = Path(args.dir)
    max_bytes = gb_to_bytes(args.max_gb)
    target_bytes = gb_to_bytes(args.target_gb)

    if target_bytes >= max_bytes:
        raise SystemExit("--target-gb must be smaller than --max-gb")

    print("==============================================", flush=True)
    print("i.MX93 face image cleaner started", flush=True)
    print(f"image dir: {directory}", flush=True)
    print(f"max size: {args.max_gb} GB", flush=True)
    print(f"target size after cleanup: {args.target_gb} GB", flush=True)
    print(f"check interval: {args.interval} seconds", flush=True)
    print(f"min delete age: {args.min_age_seconds} seconds", flush=True)
    print(f"dry run: {args.dry_run}", flush=True)
    print("Press Ctrl+C to stop.", flush=True)
    print("==============================================", flush=True)

    try:
        while True:
            clean_once(
                directory=directory,
                max_bytes=max_bytes,
                target_bytes=target_bytes,
                min_age_seconds=args.min_age_seconds,
                recursive=args.recursive,
                dry_run=args.dry_run,
            )

            if args.once:
                return 0

            time.sleep(max(args.interval, 5))

    except KeyboardInterrupt:
        print(f"\n[{now_text()}] [cleaner] stopped by user", flush=True)
        return 0


if __name__ == "__main__":
    raise SystemExit(main())

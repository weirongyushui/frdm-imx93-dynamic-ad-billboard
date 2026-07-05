from __future__ import annotations

import argparse
import subprocess
import sys
import time
from pathlib import Path
from typing import Any


PROJECT_DIR = Path(__file__).resolve().parent
AGE_SCRIPT = PROJECT_DIR / "main.py"
ACTION_SCRIPT = (
    PROJECT_DIR
    / "19_swipe_runtime_video_camera.py"
)
INPUT_PHOTOS_DIR = PROJECT_DIR / "input_photos"
READY_PATH = (
    PROJECT_DIR
    / "output_results"
    / "live_watch_single"
    / ".age_watcher_ready"
)
READY_TIMEOUT_SECONDS = 120.0


def stop_process(
    process: subprocess.Popen[Any] | None,
    name: str,
) -> None:
    if process is None or process.poll() is not None:
        return

    print(
        f"[停止] 正在停止{name}，"
        f"PID={process.pid}"
    )
    process.terminate()

    try:
        process.wait(timeout=5.0)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5.0)


def wait_for_age_watcher(
    process: subprocess.Popen[Any],
) -> None:
    deadline = (
        time.monotonic()
        + READY_TIMEOUT_SECONDS
    )

    while time.monotonic() < deadline:
        exit_code = process.poll()

        if exit_code is not None:
            raise RuntimeError(
                "年龄性别程序初始化失败，"
                f"退出码={exit_code}"
            )

        if READY_PATH.exists():
            try:
                content = READY_PATH.read_text(
                    encoding="utf-8"
                ).strip()
            except OSError:
                content = ""

            if content == "ready":
                print(
                    "[启动完成] "
                    "年龄性别图片监控已经就绪"
                )
                return

        time.sleep(0.2)

    raise TimeoutError(
        "等待年龄性别程序就绪超时："
        f"{READY_PATH}"
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "i.MX93人脸年龄性别与动作识别总启动器。"
            "app.py只管理进程，不处理业务JSON。"
        )
    )

    parser.add_argument(
        "--camera",
        default="/dev/video2",
    )
    parser.add_argument(
        "--width",
        type=int,
        default=640,
    )
    parser.add_argument(
        "--height",
        type=int,
        default=480,
    )
    parser.add_argument(
        "--camera-fps",
        type=float,
        default=30.0,
    )
    parser.add_argument(
        "--no-display",
        action="store_true",
    )
    parser.add_argument(
        "--save-video",
        action="store_true",
    )
    parser.add_argument(
        "--no-auto-photo",
        action="store_true",
        help="关闭自动拍照；完整运行时不要使用",
    )
    parser.add_argument(
        "--photo-detect-interval",
        type=float,
        default=0.5,
    )
    parser.add_argument(
        "--photo-rearm-seconds",
        type=float,
        default=3.0,
    )
    parser.add_argument(
        "--photo-jpeg-quality",
        type=int,
        default=95,
    )

    return parser


def main() -> None:
    args = build_parser().parse_args()

    for required_file in (
        AGE_SCRIPT,
        ACTION_SCRIPT,
    ):
        if not required_file.exists():
            raise FileNotFoundError(
                f"缺少程序文件：{required_file}"
            )

    INPUT_PHOTOS_DIR.mkdir(
        parents=True,
        exist_ok=True,
    )
    READY_PATH.parent.mkdir(
        parents=True,
        exist_ok=True,
    )

    try:
        READY_PATH.unlink()
    except FileNotFoundError:
        pass

    age_command = [
        sys.executable,
        "-u",
        str(AGE_SCRIPT),
    ]

    action_command = [
        sys.executable,
        "-u",
        str(ACTION_SCRIPT),
        "--camera",
        str(args.camera),
        "--width",
        str(args.width),
        "--height",
        str(args.height),
        "--camera-fps",
        str(args.camera_fps),
        "--photo-output-dir",
        str(INPUT_PHOTOS_DIR),
        "--photo-detect-interval",
        str(args.photo_detect_interval),
        "--photo-rearm-seconds",
        str(args.photo_rearm_seconds),
        "--photo-jpeg-quality",
        str(args.photo_jpeg_quality),
    ]

    if args.no_display:
        action_command.append("--no-display")
    if args.no_auto_photo:
        action_command.append("--no-auto-photo")
    if args.save_video:
        action_command.append("--save-video")

    age_process: subprocess.Popen[Any] | None = None
    action_process: subprocess.Popen[Any] | None = None

    print("=" * 72)
    print("i.MX93人脸年龄性别与动作识别")
    print(
        "[总逻辑] app.py只启动和停止两个程序，"
        "不读取、不删除、不覆盖任何业务JSON"
    )
    print(
        "[人物] 新人物稳定出现→只拍一张→"
        "main.py识别→覆盖person_latest.json"
    )
    print(
        "[动作] 持续识别→覆盖action_latest.json"
    )
    print("=" * 72)

    try:
        age_process = subprocess.Popen(
            age_command,
            cwd=str(PROJECT_DIR),
        )
        print(
            "[进程启动] 年龄性别程序 "
            f"PID={age_process.pid}"
        )

        wait_for_age_watcher(age_process)

        action_process = subprocess.Popen(
            action_command,
            cwd=str(PROJECT_DIR),
        )
        print(
            "[进程启动] 动作与自动拍照程序 "
            f"PID={action_process.pid}"
        )
        print("[运行中] 按Ctrl+C停止全部程序")

        while True:
            age_exit = age_process.poll()
            action_exit = action_process.poll()

            if age_exit is not None:
                raise RuntimeError(
                    "年龄性别程序意外退出，"
                    f"退出码={age_exit}"
                )

            if action_exit is not None:
                raise RuntimeError(
                    "动作与拍照程序意外退出，"
                    f"退出码={action_exit}"
                )

            time.sleep(0.5)

    except KeyboardInterrupt:
        print()
        print("[用户停止] 收到Ctrl+C")
    finally:
        stop_process(
            action_process,
            "动作与拍照程序",
        )
        stop_process(
            age_process,
            "年龄性别程序",
        )
        print("[全部停止] 两个子程序均已退出")


if __name__ == "__main__":
    main()

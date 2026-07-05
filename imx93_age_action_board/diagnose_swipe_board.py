#!/usr/bin/env python3
"""
i.MX93 实时动作识别诊断工具

用途：
1. 检查摄像头是否持续读帧。
2. 检查 MoveNet 实际推理速度。
3. 每秒显示肩膀、手肘、手腕关键点置信度。
4. 显示动作状态机 READY / TRACKING / WAIT_RELEASE。
5. 判断问题更像是关键点置信度低、推理速度慢，还是动作阈值过严。

把本文件放到以下文件旁边：
    19_swipe_runtime_video_camera.py

运行示例：
    python3 -u diagnose_swipe_board.py --camera /dev/video2

停止：
    Ctrl + C
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import sys
import time
import traceback
from pathlib import Path
from typing import Any

import cv2
import numpy as np


PROJECT_DIR = Path(__file__).resolve().parent
ACTION_SCRIPT = (
    PROJECT_DIR / "19_swipe_runtime_video_camera.py"
)
REPORT_PATH = PROJECT_DIR / "swipe_diagnosis.json"


def load_action_module() -> Any:
    if not ACTION_SCRIPT.exists():
        raise FileNotFoundError(
            f"Missing action script: {ACTION_SCRIPT}"
        )

    module_name = "swipe_runtime_for_diagnosis"
    spec = importlib.util.spec_from_file_location(
        module_name,
        ACTION_SCRIPT,
    )

    if spec is None or spec.loader is None:
        raise ImportError(
            f"Cannot load action script: {ACTION_SCRIPT}"
        )

    module = importlib.util.module_from_spec(spec)

    # dataclass 等功能在动态导入时需要模块已登记。
    sys.modules[module_name] = module
    spec.loader.exec_module(module)

    required_names = [
        "MoveNetPose",
        "SwipeDetector",
        "MODEL_PATH",
        "LEFT_SHOULDER",
        "RIGHT_SHOULDER",
        "LEFT_ELBOW",
        "RIGHT_ELBOW",
        "LEFT_WRIST",
        "RIGHT_WRIST",
    ]

    missing = [
        name
        for name in required_names
        if not hasattr(module, name)
    ]

    if missing:
        raise AttributeError(
            "Action script is missing required names: "
            + ", ".join(missing)
        )

    return module


def score(
    keypoints: np.ndarray,
    index: int,
) -> float:
    return float(keypoints[index][2])


def safe_mean(values: list[float]) -> float:
    if not values:
        return 0.0
    return float(sum(values) / len(values))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Diagnose camera, MoveNet keypoints, "
            "inference speed and swipe state machine."
        )
    )
    parser.add_argument(
        "--camera",
        default="/dev/video2",
        help="Camera device, default: /dev/video2",
    )
    parser.add_argument(
        "--width",
        type=int,
        default=640,
        help="Capture width, default: 640",
    )
    parser.add_argument(
        "--height",
        type=int,
        default=480,
        help="Capture height, default: 480",
    )
    parser.add_argument(
        "--fps",
        type=float,
        default=15.0,
        help="Requested camera FPS, default: 15",
    )
    parser.add_argument(
        "--seconds",
        type=float,
        default=20.0,
        help="Diagnosis duration, default: 20 seconds",
    )
    parser.add_argument(
        "--print-interval",
        type=float,
        default=1.0,
        help="Terminal print interval, default: 1 second",
    )
    return parser


def main() -> int:
    args = build_parser().parse_args()

    print("=" * 76)
    print("[DIAG] i.MX93 swipe diagnosis")
    print(f"[DIAG] Project: {PROJECT_DIR}")
    print(f"[DIAG] Action script: {ACTION_SCRIPT}")
    print(f"[DIAG] Camera: {args.camera}")
    print(
        f"[DIAG] Requested capture: "
        f"{args.width}x{args.height} @ {args.fps:.1f} FPS"
    )
    print(f"[DIAG] Duration: {args.seconds:.1f} seconds")
    print("=" * 76)

    module = load_action_module()

    print("[STEP] Loading MoveNet...")
    pose = module.MoveNetPose(module.MODEL_PATH)
    detector = module.SwipeDetector()
    print("[OK] MoveNet and SwipeDetector loaded")

    cap = cv2.VideoCapture(
        args.camera,
        cv2.CAP_V4L2,
    )

    cap.set(
        cv2.CAP_PROP_FOURCC,
        cv2.VideoWriter_fourcc(*"MJPG"),
    )
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, args.width)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, args.height)
    cap.set(cv2.CAP_PROP_FPS, args.fps)

    if not cap.isOpened():
        raise RuntimeError(
            f"Cannot open camera: {args.camera}"
        )

    actual_width = int(
        cap.get(cv2.CAP_PROP_FRAME_WIDTH)
    )
    actual_height = int(
        cap.get(cv2.CAP_PROP_FRAME_HEIGHT)
    )
    reported_fps = float(
        cap.get(cv2.CAP_PROP_FPS)
    )

    print(
        f"[OK] Camera opened: "
        f"{actual_width}x{actual_height}, "
        f"reported FPS={reported_fps:.2f}"
    )
    print("")
    print("Please keep upper body, shoulders, elbows and wrists visible.")
    print("Raise one hand near/above elbow height, pause briefly,")
    print("then move the whole arm horizontally left or right.")
    print("")

    start = time.monotonic()
    last_print = start

    frame_count = 0
    read_failures = 0
    inference_count = 0
    actions: list[dict[str, Any]] = []
    visited_states: set[str] = set()

    inference_times_ms: list[float] = []
    left_wrist_scores: list[float] = []
    right_wrist_scores: list[float] = []
    left_elbow_scores: list[float] = []
    right_elbow_scores: list[float] = []
    left_shoulder_scores: list[float] = []
    right_shoulder_scores: list[float] = []

    max_scores = {
        "LS": 0.0,
        "RS": 0.0,
        "LE": 0.0,
        "RE": 0.0,
        "LW": 0.0,
        "RW": 0.0,
    }

    try:
        while True:
            now = time.monotonic()
            elapsed = now - start

            if elapsed >= args.seconds:
                break

            ok, frame = cap.read()
            frame_count += 1

            if not ok or frame is None:
                read_failures += 1

                if read_failures <= 5:
                    print(
                        "[WARN] Camera frame read failed",
                        flush=True,
                    )
                continue

            infer_start = time.perf_counter()
            keypoints, _ = pose.predict(frame)
            infer_ms = (
                time.perf_counter() - infer_start
            ) * 1000.0

            inference_times_ms.append(infer_ms)
            inference_count += 1

            timestamp = time.monotonic() - start
            event = detector.update(
                keypoints,
                timestamp,
            )

            current_state = str(
                getattr(detector, "state", "UNKNOWN")
            )
            visited_states.add(current_state)

            values = {
                "LS": score(
                    keypoints,
                    module.LEFT_SHOULDER,
                ),
                "RS": score(
                    keypoints,
                    module.RIGHT_SHOULDER,
                ),
                "LE": score(
                    keypoints,
                    module.LEFT_ELBOW,
                ),
                "RE": score(
                    keypoints,
                    module.RIGHT_ELBOW,
                ),
                "LW": score(
                    keypoints,
                    module.LEFT_WRIST,
                ),
                "RW": score(
                    keypoints,
                    module.RIGHT_WRIST,
                ),
            }

            left_shoulder_scores.append(values["LS"])
            right_shoulder_scores.append(values["RS"])
            left_elbow_scores.append(values["LE"])
            right_elbow_scores.append(values["RE"])
            left_wrist_scores.append(values["LW"])
            right_wrist_scores.append(values["RW"])

            for name, value in values.items():
                max_scores[name] = max(
                    max_scores[name],
                    value,
                )

            if event is not None:
                actions.append(event)
                print(
                    ">>> ACTION DETECTED: "
                    + json.dumps(
                        event,
                        ensure_ascii=False,
                    ),
                    flush=True,
                )

            current_time = time.monotonic()

            if (
                current_time - last_print
                >= args.print_interval
            ):
                elapsed_now = current_time - start
                inference_fps = (
                    inference_count / elapsed_now
                    if elapsed_now > 0
                    else 0.0
                )

                track_length = len(
                    getattr(detector, "track", [])
                )
                active_wrist = getattr(
                    detector,
                    "active_wrist",
                    None,
                )

                min_threshold = float(
                    getattr(
                        module,
                        "MIN_KEYPOINT_SCORE",
                        0.25,
                    )
                )

                left_valid = (
                    min(
                        values["LS"],
                        values["RS"],
                        values["LE"],
                        values["LW"],
                    )
                    >= min_threshold
                )
                right_valid = (
                    min(
                        values["LS"],
                        values["RS"],
                        values["RE"],
                        values["RW"],
                    )
                    >= min_threshold
                )

                print(
                    f"[LIVE] t={elapsed_now:5.1f}s "
                    f"infer_fps={inference_fps:5.2f} "
                    f"infer_ms={infer_ms:6.1f} "
                    f"state={current_state:<12} "
                    f"track={track_length:<2} "
                    f"active={str(active_wrist):<11}",
                    flush=True,
                )
                print(
                    f"       LS={values['LS']:.2f} "
                    f"RS={values['RS']:.2f} | "
                    f"LE={values['LE']:.2f} "
                    f"RE={values['RE']:.2f} | "
                    f"LW={values['LW']:.2f} "
                    f"RW={values['RW']:.2f} | "
                    f"left_valid={left_valid} "
                    f"right_valid={right_valid}",
                    flush=True,
                )

                last_print = current_time

    except KeyboardInterrupt:
        print("\n[STOP] Interrupted by user")
    finally:
        cap.release()

    total_elapsed = max(
        time.monotonic() - start,
        1e-6,
    )
    average_inference_fps = (
        inference_count / total_elapsed
    )
    average_inference_ms = safe_mean(
        inference_times_ms
    )

    min_threshold = float(
        getattr(
            module,
            "MIN_KEYPOINT_SCORE",
            0.25,
        )
    )
    min_valid_points = int(
        getattr(
            module,
            "MIN_VALID_POINTS",
            5,
        )
    )
    track_window_seconds = float(
        getattr(
            module,
            "TRACK_WINDOW_SECONDS",
            0.9,
        )
    )

    report = {
        "camera": args.camera,
        "capture": {
            "requested_width": args.width,
            "requested_height": args.height,
            "requested_fps": args.fps,
            "actual_width": actual_width,
            "actual_height": actual_height,
            "reported_fps": reported_fps,
            "frames_read": frame_count - read_failures,
            "read_failures": read_failures,
        },
        "inference": {
            "count": inference_count,
            "average_fps": round(
                average_inference_fps,
                3,
            ),
            "average_ms": round(
                average_inference_ms,
                3,
            ),
        },
        "thresholds": {
            "min_keypoint_score": min_threshold,
            "min_valid_points": min_valid_points,
            "track_window_seconds": (
                track_window_seconds
            ),
        },
        "keypoint_scores": {
            "average": {
                "LS": round(
                    safe_mean(left_shoulder_scores),
                    4,
                ),
                "RS": round(
                    safe_mean(right_shoulder_scores),
                    4,
                ),
                "LE": round(
                    safe_mean(left_elbow_scores),
                    4,
                ),
                "RE": round(
                    safe_mean(right_elbow_scores),
                    4,
                ),
                "LW": round(
                    safe_mean(left_wrist_scores),
                    4,
                ),
                "RW": round(
                    safe_mean(right_wrist_scores),
                    4,
                ),
            },
            "maximum": {
                key: round(value, 4)
                for key, value in max_scores.items()
            },
        },
        "visited_states": sorted(visited_states),
        "actions": actions,
    }

    REPORT_PATH.write_text(
        json.dumps(
            report,
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    print("")
    print("=" * 76)
    print("[SUMMARY]")
    print(
        f"Frames read: {report['capture']['frames_read']}, "
        f"read failures: {read_failures}"
    )
    print(
        f"Average inference: "
        f"{average_inference_fps:.2f} FPS, "
        f"{average_inference_ms:.1f} ms/frame"
    )
    print(
        "Visited states: "
        + ", ".join(sorted(visited_states))
    )
    print(f"Actions detected: {len(actions)}")
    print(
        "Maximum scores: "
        + " ".join(
            f"{key}={value:.2f}"
            for key, value in max_scores.items()
        )
    )
    print(f"Report: {REPORT_PATH}")

    estimated_points = (
        average_inference_fps
        * track_window_seconds
    )

    print("")
    print("[AUTOMATIC CHECK]")

    if inference_count == 0:
        print(
            "- No MoveNet inference completed. "
            "Check camera frames and runtime errors."
        )
    elif average_inference_fps < 3.0:
        print(
            "- Inference is very slow. "
            "The current time window is likely too short."
        )
    elif estimated_points < min_valid_points:
        print(
            "- Current FPS cannot reliably collect "
            f"{min_valid_points} points inside "
            f"{track_window_seconds:.2f}s. "
            "The Python swipe thresholds need adjustment."
        )
    else:
        print(
            "- Inference speed is sufficient for the "
            "current minimum point count in theory."
        )

    best_wrist_score = max(
        max_scores["LW"],
        max_scores["RW"],
    )
    best_elbow_score = max(
        max_scores["LE"],
        max_scores["RE"],
    )
    best_shoulder_pair = min(
        max_scores["LS"],
        max_scores["RS"],
    )

    if best_shoulder_pair < min_threshold:
        print(
            "- Both shoulders were not detected reliably. "
            "Move farther back and keep upper body centered."
        )
    elif best_elbow_score < min_threshold:
        print(
            "- Elbows were not detected reliably. "
            "Keep the whole arm inside the frame."
        )
    elif best_wrist_score < min_threshold:
        print(
            "- Wrists were below the keypoint threshold. "
            "Improve lighting and keep hands inside frame."
        )
    else:
        print(
            "- Shoulder/elbow/wrist scores exceeded "
            "the configured threshold at least once."
        )

    if "TRACKING" not in visited_states:
        print(
            "- State never entered TRACKING. "
            "The hand may not be considered raised, "
            "or valid wrist data is being filtered."
        )
    elif not actions:
        print(
            "- State entered TRACKING but no action fired. "
            "Distance, direction consistency, vertical "
            "movement or timing thresholds are likely too strict."
        )
    else:
        print(
            "- Swipe detection succeeded."
        )

    print("=" * 76)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print("")
        print("=" * 76)
        print("[FATAL] Diagnosis failed")
        print(f"[FATAL] {type(error).__name__}: {error}")
        traceback.print_exc()
        print("=" * 76)
        raise SystemExit(1)

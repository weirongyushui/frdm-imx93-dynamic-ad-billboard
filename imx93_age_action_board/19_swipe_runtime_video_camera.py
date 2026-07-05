from __future__ import annotations

import argparse
import os
import platform
import sys
import traceback
from datetime import datetime
import json
import time
from collections import deque
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np
TFLITE_BACKEND = "未找到"
TFLITE_IMPORT_ERRORS: list[str] = []
Interpreter = None

try:
    from ai_edge_litert.interpreter import Interpreter
    TFLITE_BACKEND = "ai_edge_litert"
except ImportError as error:
    TFLITE_IMPORT_ERRORS.append(
        f"ai_edge_litert: {error}"
    )
    try:
        from tflite_runtime.interpreter import Interpreter
        TFLITE_BACKEND = "tflite_runtime"
    except ImportError as error:
        TFLITE_IMPORT_ERRORS.append(
            f"tflite_runtime: {error}"
        )
        try:
            from tensorflow.lite import Interpreter
            TFLITE_BACKEND = "tensorflow.lite"
        except ImportError as error:
            TFLITE_IMPORT_ERRORS.append(
                f"tensorflow.lite: {error}"
            )


PROJECT_DIR = Path(__file__).resolve().parent
MODEL_PATH = PROJECT_DIR / "pretrained_models" / "movenet_lightning_int8.tflite"
OUTPUT_ROOT = PROJECT_DIR / "output_results" / "swipe_runtime"

# 后端专用简化动作文件。
# 文件内容始终只有 action 和 sequence 两个字段。
BACKEND_ACTION_PATH = (
    PROJECT_DIR
    / "output_results"
    / "backend_events"
    / "action_latest.json"
)
DEFAULT_PHOTO_OUTPUT_DIR = PROJECT_DIR / "input_photos"

# 人脸拍照只使用Haar正脸检测。
# 第一次检测到有效正脸后立即紧裁剪并保存一张。
# 当前脸未离开时不重复拍照。
# 不做人脸身份识别，也不使用MoveNet人体关键点参与拍照。
PHOTO_FACE_CONFIRM_CHECKS = 1
PHOTO_DETECT_MAX_WIDTH = 640
PHOTO_MIN_FACE_AREA_RATIO = 0.012

# 板端平衡灵敏版参数：
# 结合 /dev/video2、640x480、30FPS、动作分析10FPS 的实测结果，
# 放宽关键点、轨迹长度、移动距离和丢点容忍度。
# 仍保留 WAIT_RELEASE 放手锁，防止一次动作连续触发。
MIN_KEYPOINT_SCORE = 0.20
MIN_VALID_POINTS = 4
MIN_HORIZONTAL_DISTANCE = 0.30
MAX_VERTICAL_RATIO = 1.10
MIN_DIRECTION_CONSISTENCY = 0.35

TRACK_WINDOW_SECONDS = 1.20
MIN_SEGMENT_SECONDS = 0.20
TARGET_ANALYSIS_FPS = 10.0

# 手抬起后才允许开始追踪；识别成功后必须先放下手，才能识别下一次动作。
RAISE_CONFIRM_POINTS = 1

# 动作完成后的混合解锁策略：
# 1. 至少锁定 0.60 秒，避免同一次动作连续触发；
# 2. 手腕放下后连续确认 2 次即可解锁；
# 3. 手腕关键点暂时丢失时，连续 4 次也视为已经收手；
# 4. 最多锁定 1.50 秒，超过后强制恢复 READY，
#    防止长期卡在 WAIT_RELEASE，导致 sequence 一直不增加。
RELEASE_CONFIRM_POINTS = 2
RELEASE_MISSING_CONFIRM_POINTS = 4
MIN_RELEASE_LOCK_SECONDS = 0.60
MAX_RELEASE_LOCK_SECONDS = 1.50

TRACK_LOST_TOLERANCE = 4
MAX_WRIST_BELOW_ELBOW_FOR_SWIPE = 0.45
MIN_WRIST_BELOW_ELBOW_FOR_RELEASE = 0.20

SMOOTH_WINDOW = 3
MOTION_DEADBAND = 0.015

# True：输出“人物自己向左/向右”的方向。
# 你前面的测试已经证明当前应保持 True。
REVERSE_DIRECTION = True

# 只缩小电脑上的预览窗口，不影响模型识别和保存视频的原始分辨率。
PREVIEW_MAX_WIDTH = 960
PREVIEW_MAX_HEIGHT = 540

LEFT_SHOULDER = 5
RIGHT_SHOULDER = 6
LEFT_ELBOW = 7
RIGHT_ELBOW = 8
LEFT_WRIST = 9
RIGHT_WRIST = 10

SKELETON_EDGES = [
    (0, 1), (0, 2), (1, 3), (2, 4),
    (5, 6),
    (5, 7), (7, 9),
    (6, 8), (8, 10),
    (5, 11), (6, 12), (11, 12),
    (11, 13), (13, 15),
    (12, 14), (14, 16),
]


@dataclass
class TrackPoint:
    timestamp: float
    relative_x: float
    relative_y: float
    wrist_score: float
    shoulder_score: float


class MoveNetPose:
    def __init__(self, model_path: Path) -> None:
        if not model_path.exists():
            raise FileNotFoundError(f"找不到模型：{model_path}")

        print(f"TFLite解释器：{TFLITE_BACKEND}")

        if Interpreter is None:
            details = " | ".join(TFLITE_IMPORT_ERRORS)
            raise ImportError(
                "没有找到可用的LiteRT/TFLite Python解释器。"
                "需要 ai_edge_litert、tflite_runtime "
                "或 tensorflow.lite 之一。"
                f" 导入详情：{details}"
            )

        self.interpreter = Interpreter(model_path=str(model_path))
        self.interpreter.allocate_tensors()

        self.input_detail = self.interpreter.get_input_details()[0]
        self.output_detail = self.interpreter.get_output_details()[0]

        shape = self.input_detail["shape"]
        self.input_height = int(shape[1])
        self.input_width = int(shape[2])

        print("MoveNet 输入：", shape, self.input_detail["dtype"])
        print(
            "MoveNet 输出：",
            self.output_detail["shape"],
            self.output_detail["dtype"],
        )

    def _resize_with_pad(
        self,
        frame_bgr: np.ndarray,
    ) -> tuple[np.ndarray, dict]:
        frame_rgb = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB)
        original_height, original_width = frame_rgb.shape[:2]

        scale = min(
            self.input_width / original_width,
            self.input_height / original_height,
        )

        resized_width = max(1, int(round(original_width * scale)))
        resized_height = max(1, int(round(original_height * scale)))

        resized = cv2.resize(
            frame_rgb,
            (resized_width, resized_height),
            interpolation=cv2.INTER_LINEAR,
        )

        pad_left = (self.input_width - resized_width) // 2
        pad_top = (self.input_height - resized_height) // 2

        padded = np.zeros(
            (self.input_height, self.input_width, 3),
            dtype=np.uint8,
        )
        padded[
            pad_top:pad_top + resized_height,
            pad_left:pad_left + resized_width,
        ] = resized

        return padded, {
            "scale": scale,
            "pad_left": pad_left,
            "pad_top": pad_top,
            "original_width": original_width,
            "original_height": original_height,
        }

    def _prepare_input(self, image: np.ndarray) -> np.ndarray:
        dtype = self.input_detail["dtype"]
        tensor = image[None, ...]

        if dtype == np.uint8:
            return tensor.astype(np.uint8)

        if dtype == np.int8:
            scale, zero_point = self.input_detail["quantization"]
            if scale == 0:
                raise ValueError("模型输入量化 scale 为 0")

            quantized = np.round(
                tensor.astype(np.float32) / scale + zero_point
            )
            return np.clip(quantized, -128, 127).astype(np.int8)

        if dtype == np.float32:
            return tensor.astype(np.float32)

        raise TypeError(f"不支持的模型输入类型：{dtype}")

    def predict(
        self,
        frame_bgr: np.ndarray,
    ) -> tuple[np.ndarray, dict]:
        padded, transform = self._resize_with_pad(frame_bgr)
        input_tensor = self._prepare_input(padded)

        self.interpreter.set_tensor(
            self.input_detail["index"],
            input_tensor,
        )
        self.interpreter.invoke()

        output = self.interpreter.get_tensor(
            self.output_detail["index"]
        )

        keypoints = np.asarray(output, dtype=np.float32).reshape(17, 3)
        return keypoints, transform

    def to_pixel(
        self,
        keypoint: np.ndarray,
        transform: dict,
    ) -> tuple[int, int]:
        y_model = float(keypoint[0]) * self.input_height
        x_model = float(keypoint[1]) * self.input_width

        x = (x_model - transform["pad_left"]) / transform["scale"]
        y = (y_model - transform["pad_top"]) / transform["scale"]

        x = int(np.clip(round(x), 0, transform["original_width"] - 1))
        y = int(np.clip(round(y), 0, transform["original_height"] - 1))
        return x, y


def median_smooth(values: np.ndarray) -> np.ndarray:
    if len(values) <= 2 or SMOOTH_WINDOW <= 1:
        return values.astype(np.float32, copy=True)

    radius = SMOOTH_WINDOW // 2
    result = np.empty_like(values, dtype=np.float32)

    for index in range(len(values)):
        left = max(0, index - radius)
        right = min(len(values), index + radius + 1)
        result[index] = float(np.median(values[left:right]))

    return result


class SwipeDetector:
    """
    三状态动作识别器：

    READY:
        等待某只手抬起。

    TRACKING:
        只追踪已经锁定的那只手，达到横向位移阈值后输出一次动作。

    WAIT_RELEASE:
        动作输出后先进入短暂防重复锁。
        手腕放下、关键点离开，或达到最长锁定时间后，
        都可以重新进入 READY，避免永久卡住。
    """

    def __init__(self) -> None:
        self.state = "READY"
        self.active_wrist: str | None = None

        self.track: deque[TrackPoint] = deque()

        self.raise_counts = {
            "left_wrist": 0,
            "right_wrist": 0,
        }
        self.release_count = 0
        self.release_missing_count = 0
        self.wait_release_started_at: float | None = None
        self.lost_count = 0

    def clear_track(self) -> None:
        self.track.clear()
        self.lost_count = 0

    def reset_ready(self) -> None:
        self.state = "READY"
        self.active_wrist = None
        self.clear_track()
        self.release_count = 0
        self.release_missing_count = 0
        self.wait_release_started_at = None
        self.raise_counts["left_wrist"] = 0
        self.raise_counts["right_wrist"] = 0

    @staticmethod
    def _indices(name: str) -> tuple[int, int]:
        if name == "left_wrist":
            return LEFT_WRIST, LEFT_ELBOW
        return RIGHT_WRIST, RIGHT_ELBOW

    def _read_wrist(
        self,
        name: str,
        keypoints: np.ndarray,
        timestamp: float,
    ) -> dict | None:
        wrist_index, elbow_index = self._indices(name)

        left_shoulder = keypoints[LEFT_SHOULDER]
        right_shoulder = keypoints[RIGHT_SHOULDER]
        elbow = keypoints[elbow_index]
        wrist = keypoints[wrist_index]

        shoulder_score = min(
            float(left_shoulder[2]),
            float(right_shoulder[2]),
        )
        elbow_score = float(elbow[2])
        wrist_score = float(wrist[2])

        if (
            shoulder_score < MIN_KEYPOINT_SCORE
            or elbow_score < MIN_KEYPOINT_SCORE
            or wrist_score < MIN_KEYPOINT_SCORE
        ):
            return None

        shoulder_width = abs(
            float(right_shoulder[1]) - float(left_shoulder[1])
        )

        if shoulder_width < 0.03:
            return None

        shoulder_center_x = (
            float(left_shoulder[1]) + float(right_shoulder[1])
        ) / 2.0
        shoulder_center_y = (
            float(left_shoulder[0]) + float(right_shoulder[0])
        ) / 2.0

        relative_x = (
            float(wrist[1]) - shoulder_center_x
        ) / shoulder_width
        relative_y = (
            float(wrist[0]) - shoulder_center_y
        ) / shoulder_width

        # 图像坐标的y向下增大。
        # 小于0表示手腕高于手肘；正数表示手腕低于手肘。
        wrist_below_elbow = (
            float(wrist[0]) - float(elbow[0])
        ) / shoulder_width

        return {
            "point": TrackPoint(
                timestamp=timestamp,
                relative_x=relative_x,
                relative_y=relative_y,
                wrist_score=wrist_score,
                shoulder_score=shoulder_score,
            ),
            "wrist_below_elbow": wrist_below_elbow,
            "score": min(
                wrist_score,
                elbow_score,
                shoulder_score,
            ),
        }

    @staticmethod
    def _is_raised(info: dict | None) -> bool:
        return (
            info is not None
            and float(info["wrist_below_elbow"])
            <= MAX_WRIST_BELOW_ELBOW_FOR_SWIPE
        )

    @staticmethod
    def _is_released(info: dict | None) -> bool:
        # 关键点短暂丢失不能立刻算“已经放下”，否则会重新触发。
        return (
            info is not None
            and float(info["wrist_below_elbow"])
            >= MIN_WRIST_BELOW_ELBOW_FOR_RELEASE
        )

    def _prune(self, timestamp: float) -> None:
        cutoff = timestamp - TRACK_WINDOW_SECONDS

        while self.track and self.track[0].timestamp < cutoff:
            self.track.popleft()

    def _evaluate_recent_track(self) -> dict | None:
        points = list(self.track)

        if len(points) < MIN_VALID_POINTS:
            return None

        best: dict | None = None
        end_index = len(points) - 1

        # 只分析以当前帧结束的片段，动作达到要求后立即触发。
        for start_index in range(
            0,
            len(points) - MIN_VALID_POINTS + 1,
        ):
            segment = points[start_index:end_index + 1]

            duration = (
                segment[-1].timestamp
                - segment[0].timestamp
            )

            if duration < MIN_SEGMENT_SECONDS:
                continue

            x_values = np.asarray(
                [item.relative_x for item in segment],
                dtype=np.float32,
            )
            y_values = np.asarray(
                [item.relative_y for item in segment],
                dtype=np.float32,
            )

            smooth_x = median_smooth(x_values)
            smooth_y = median_smooth(y_values)

            edge_count = 2 if len(segment) >= 7 else 1

            start_x = float(np.median(smooth_x[:edge_count]))
            end_x = float(np.median(smooth_x[-edge_count:]))
            start_y = float(np.median(smooth_y[:edge_count]))
            end_y = float(np.median(smooth_y[-edge_count:]))

            delta_x = end_x - start_x
            delta_y = end_y - start_y

            differences = np.diff(smooth_x)
            useful = differences[
                np.abs(differences) > MOTION_DEADBAND
            ]

            if len(useful) == 0 or abs(delta_x) < 1e-6:
                consistency = 0.0
                path_efficiency = 0.0
            else:
                expected_sign = 1.0 if delta_x > 0 else -1.0

                forward = float(
                    np.sum(
                        np.clip(
                            expected_sign * useful,
                            0.0,
                            None,
                        )
                    )
                )
                backward = float(
                    np.sum(
                        np.clip(
                            -expected_sign * useful,
                            0.0,
                            None,
                        )
                    )
                )
                total = forward + backward

                consistency = (
                    forward / total
                    if total > 1e-6
                    else 0.0
                )
                path_efficiency = min(
                    1.0,
                    abs(delta_x) / max(total, 1e-6),
                )

            vertical_ratio = (
                abs(delta_y) / max(abs(delta_x), 1e-6)
            )

            score = (
                abs(delta_x)
                * (
                    0.55
                    + 0.30 * consistency
                    + 0.15 * path_efficiency
                )
                / (
                    1.0
                    + max(0.0, vertical_ratio - 0.35)
                )
            )

            candidate = {
                "duration": duration,
                "delta_x": delta_x,
                "delta_y": delta_y,
                "consistency": consistency,
                "path_efficiency": path_efficiency,
                "vertical_ratio": vertical_ratio,
                "score": score,
                "points": len(segment),
            }

            if best is None or score > best["score"]:
                best = candidate

        return best

    def _try_classify(
        self,
        timestamp: float,
    ) -> dict | None:
        best = self._evaluate_recent_track()

        if best is None:
            return None

        if abs(best["delta_x"]) < MIN_HORIZONTAL_DISTANCE:
            return None

        if abs(best["delta_y"]) > (
            abs(best["delta_x"]) * MAX_VERTICAL_RATIO
        ):
            return None

        if best["consistency"] < MIN_DIRECTION_CONSISTENCY:
            return None

        image_action = (
            "swipe_right"
            if best["delta_x"] > 0
            else "swipe_left"
        )

        if REVERSE_DIRECTION:
            action = (
                "swipe_left"
                if image_action == "swipe_right"
                else "swipe_right"
            )
        else:
            action = image_action

        return {
            "timestamp_seconds": round(timestamp, 3),
            "action": action,
            "image_action": image_action,
            "wrist": self.active_wrist,
            "horizontal_distance_shoulder_widths": round(
                float(best["delta_x"]),
                4,
            ),
            "vertical_distance_shoulder_widths": round(
                float(best["delta_y"]),
                4,
            ),
            "direction_consistency": round(
                float(best["consistency"]),
                4,
            ),
            "segment_duration_seconds": round(
                float(best["duration"]),
                3,
            ),
            "segment_points": int(best["points"]),
        }

    def update(
        self,
        keypoints: np.ndarray,
        timestamp: float,
    ) -> dict | None:
        wrist_infos = {
            "left_wrist": self._read_wrist(
                "left_wrist",
                keypoints,
                timestamp,
            ),
            "right_wrist": self._read_wrist(
                "right_wrist",
                keypoints,
                timestamp,
            ),
        }

        if self.state == "WAIT_RELEASE":
            active_info = (
                wrist_infos.get(self.active_wrist)
                if self.active_wrist is not None
                else None
            )

            started_at = self.wait_release_started_at

            if started_at is None:
                started_at = timestamp
                self.wait_release_started_at = timestamp

            locked_seconds = max(
                0.0,
                timestamp - started_at,
            )

            # 最短锁定时间内不解锁，防止一次挥手被连续识别。
            if locked_seconds < MIN_RELEASE_LOCK_SECONDS:
                return None

            if active_info is None:
                # 放手过程中手腕或手肘容易暂时丢失。
                # 旧版本把丢点一直当成“没有放手”，可能永久卡住。
                self.release_missing_count += 1
                self.release_count = 0
            elif self._is_released(active_info):
                self.release_count += 1
                self.release_missing_count = 0
            else:
                self.release_count = 0
                self.release_missing_count = 0

            unlock_reason = None

            if self.release_count >= RELEASE_CONFIRM_POINTS:
                unlock_reason = "检测到手腕已放下"
            elif (
                self.release_missing_count
                >= RELEASE_MISSING_CONFIRM_POINTS
            ):
                unlock_reason = "放手过程中关键点已离开/丢失"
            elif locked_seconds >= MAX_RELEASE_LOCK_SECONDS:
                unlock_reason = "达到最长锁定时间，自动解锁"

            if unlock_reason is not None:
                print(
                    "[动作解锁] "
                    f"{unlock_reason}；"
                    f"等待={locked_seconds:.2f}秒；"
                    "状态恢复READY",
                    flush=True,
                )
                self.reset_ready()

            return None

        if self.state == "READY":
            for name, info in wrist_infos.items():
                if self._is_raised(info):
                    self.raise_counts[name] += 1
                else:
                    self.raise_counts[name] = 0

            ready_candidates = [
                name
                for name, count in self.raise_counts.items()
                if count >= RAISE_CONFIRM_POINTS
            ]

            if not ready_candidates:
                return None

            # 两只手同时抬起时，锁定关键点置信度更高的那只。
            self.active_wrist = max(
                ready_candidates,
                key=lambda name: float(
                    wrist_infos[name]["score"]
                    if wrist_infos[name] is not None
                    else 0.0
                ),
            )
            self.state = "TRACKING"
            self.clear_track()

            active_info = wrist_infos[self.active_wrist]
            if active_info is not None:
                self.track.append(active_info["point"])

            return None

        # TRACKING
        active_info = (
            wrist_infos.get(self.active_wrist)
            if self.active_wrist is not None
            else None
        )

        if not self._is_raised(active_info):
            self.lost_count += 1

            # 手还没形成有效动作就已经放下或丢失，回到等待状态。
            if self.lost_count > TRACK_LOST_TOLERANCE:
                self.reset_ready()

            return None

        self.lost_count = 0
        self.track.append(active_info["point"])
        self._prune(timestamp)

        event = self._try_classify(timestamp)

        if event is None:
            return None

        # 一旦输出动作，就进入等待放下状态。
        # 接下来“收回手、放下手”的运动全部忽略。
        self.state = "WAIT_RELEASE"
        self.release_count = 0
        self.release_missing_count = 0
        self.wait_release_started_at = timestamp
        self.clear_track()

        return event


def draw_pose(
    frame: np.ndarray,
    keypoints: np.ndarray,
    model: MoveNetPose,
    transform: dict,
) -> None:
    points: dict[int, tuple[int, int]] = {}

    for index, keypoint in enumerate(keypoints):
        if float(keypoint[2]) < MIN_KEYPOINT_SCORE:
            continue

        x, y = model.to_pixel(keypoint, transform)
        points[index] = (x, y)
        cv2.circle(frame, (x, y), 5, (0, 255, 0), -1)

    for start, end in SKELETON_EDGES:
        if start in points and end in points:
            cv2.line(
                frame,
                points[start],
                points[end],
                (0, 255, 255),
                2,
            )

    for wrist_index, label in [
        (LEFT_WRIST, "LW"),
        (RIGHT_WRIST, "RW"),
    ]:
        if wrist_index not in points:
            continue

        x, y = points[wrist_index]
        cv2.circle(frame, (x, y), 10, (0, 0, 255), 2)
        cv2.putText(
            frame,
            label,
            (x + 8, y - 8),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.60,
            (0, 0, 255),
            2,
            cv2.LINE_AA,
        )


def parse_camera_source(value: str) -> int | str:
    stripped = value.strip()

    if stripped.isdigit():
        return int(stripped)

    return stripped


def write_json_atomic(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temp_path = path.with_suffix(path.suffix + ".tmp")

    temp_path.write_text(
        json.dumps(data, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    temp_path.replace(path)


def load_action_sequence(path: Path) -> int:
    """
    读取上一次动作编号。

    文件不存在、内容损坏或 sequence 无效时，从 0 开始。
    下一次成功动作会写成 sequence=1。
    """
    try:
        data = json.loads(
            path.read_text(encoding="utf-8")
        )
        sequence = int(data.get("sequence", 0))

        if sequence < 0:
            return 0

        return sequence
    except (
        FileNotFoundError,
        json.JSONDecodeError,
        TypeError,
        ValueError,
        OSError,
    ):
        return 0


def cleanup_legacy_action_json() -> None:
    """
    删除旧版本生成的动作JSON文件。

    本版本只保留：
    output_results/backend_events/action_latest.json

    不会删除图片、模型、年龄性别结果或标注视频。
    """
    legacy_paths = [
        OUTPUT_ROOT / "latest_action.json",
    ]

    if OUTPUT_ROOT.exists():
        legacy_paths.extend(
            OUTPUT_ROOT.glob("*/events.json")
        )
        legacy_paths.extend(
            OUTPUT_ROOT.glob("*/events.jsonl")
        )

    removed = 0

    for path in legacy_paths:
        try:
            if path.resolve() == BACKEND_ACTION_PATH.resolve():
                continue

            path.unlink()
            removed += 1
            print(
                "[启动整理] 已删除旧动作JSON："
                f"{path}"
            )
        except FileNotFoundError:
            pass
        except OSError as error:
            print(
                "[启动警告] 无法删除旧动作JSON："
                f"{path}；{error}"
            )

    if removed == 0:
        print(
            "[启动整理] 未发现需要删除的旧动作JSON"
        )


def publish_backend_action(
    action: str,
    sequence: int,
) -> dict:
    """
    覆盖写入后端简化动作文件。

    文件固定只有：
    {
      "action": "swipe_left 或 swipe_right",
      "sequence": 正整数
    }
    """
    payload = {
        "action": action,
        "sequence": sequence,
    }

    write_json_atomic(
        BACKEND_ACTION_PATH,
        payload,
    )

    print(
        "[后端动作字段已覆盖] "
        f"action={action}；"
        f"sequence={sequence}；"
        f"文件={BACKEND_ACTION_PATH}",
        flush=True,
    )
    print(
        "BACKEND_ACTION_JSON="
        + json.dumps(
            payload,
            ensure_ascii=False,
            separators=(",", ":"),
        ),
        flush=True,
    )

    return payload


def build_preview(frame: np.ndarray) -> np.ndarray:
    frame_height, frame_width = frame.shape[:2]

    preview_scale = min(
        PREVIEW_MAX_WIDTH / frame_width,
        PREVIEW_MAX_HEIGHT / frame_height,
        1.0,
    )

    if preview_scale >= 1.0:
        return frame

    preview_width = max(
        1,
        int(round(frame_width * preview_scale)),
    )
    preview_height = max(
        1,
        int(round(frame_height * preview_scale)),
    )

    return cv2.resize(
        frame,
        (preview_width, preview_height),
        interpolation=cv2.INTER_AREA,
    )


def create_camera_face_detector() -> tuple[cv2.CascadeClassifier | None, Path]:
    cascade_path = (
        Path(cv2.data.haarcascades)
        / "haarcascade_frontalface_default.xml"
    )

    if not cascade_path.exists():
        return None, cascade_path

    detector = cv2.CascadeClassifier(str(cascade_path))
    if detector.empty():
        return None, cascade_path

    return detector, cascade_path


def detect_camera_face_box(
    frame_bgr: np.ndarray,
    detector: cv2.CascadeClassifier,
) -> tuple[int, int, int, int] | None:
    """
    使用Haar检测主要正脸，返回较紧的原图坐标：

        (x1, y1, x2, y2)
    """
    frame_height, frame_width = frame_bgr.shape[:2]

    scale = min(
        1.0,
        PHOTO_DETECT_MAX_WIDTH / max(frame_width, 1),
    )

    if scale < 1.0:
        small = cv2.resize(
            frame_bgr,
            (
                max(1, int(round(frame_width * scale))),
                max(1, int(round(frame_height * scale))),
            ),
            interpolation=cv2.INTER_AREA,
        )
    else:
        small = frame_bgr

    gray = cv2.cvtColor(
        small,
        cv2.COLOR_BGR2GRAY,
    )
    gray = cv2.equalizeHist(gray)

    boxes = detector.detectMultiScale(
        gray,
        scaleFactor=1.10,
        minNeighbors=6,
        minSize=(60, 60),
        flags=cv2.CASCADE_SCALE_IMAGE,
    )

    if len(boxes) == 0:
        return None

    small_height, small_width = small.shape[:2]
    frame_area = float(
        max(small_height * small_width, 1)
    )

    valid_boxes: list[tuple[int, int, int, int]] = []

    for x, y, width, height in boxes:
        area_ratio = (
            float(width * height)
            / frame_area
        )

        if area_ratio < PHOTO_MIN_FACE_AREA_RATIO:
            continue

        aspect_ratio = (
            float(width)
            / max(float(height), 1.0)
        )

        if not 0.72 <= aspect_ratio <= 1.38:
            continue

        valid_boxes.append(
            (
                int(x),
                int(y),
                int(width),
                int(height),
            )
        )

    if not valid_boxes:
        return None

    x, y, width, height = max(
        valid_boxes,
        key=lambda box: box[2] * box[3],
    )

    inverse_scale = 1.0 / max(scale, 1e-6)

    x = int(round(x * inverse_scale))
    y = int(round(y * inverse_scale))
    width = int(round(width * inverse_scale))
    height = int(round(height * inverse_scale))

    # 适度扩边：保留完整脸部区域，包含头发和下巴。
    padding_x = int(round(width * 0.25))
    padding_top = int(round(height * 0.35))
    padding_bottom = int(round(height * 0.25))

    x1 = max(0, x - padding_x)
    y1 = max(0, y - padding_top)
    x2 = min(
        frame_width,
        x + width + padding_x,
    )
    y2 = min(
        frame_height,
        y + height + padding_bottom,
    )

    if x2 <= x1 or y2 <= y1:
        return None

    return x1, y1, x2, y2


def crop_camera_face(
    frame_bgr: np.ndarray,
    face_box: tuple[int, int, int, int],
) -> np.ndarray:
    x1, y1, x2, y2 = face_box
    face_crop = frame_bgr[y1:y2, x1:x2]

    if face_crop.size == 0:
        raise RuntimeError("裁剪后的人脸图片为空")

    return face_crop.copy()


def save_camera_face_atomic(
    output_dir: Path,
    face_bgr: np.ndarray,
    jpeg_quality: int,
) -> Path:
    output_dir.mkdir(
        parents=True,
        exist_ok=True,
    )

    timestamp = datetime.now().strftime(
        "%Y%m%d_%H%M%S_%f"
    )

    final_path = (
        output_dir
        / f"camera_face_{timestamp}.jpg"
    )
    temporary_path = (
        output_dir
        / f".camera_face_{timestamp}.tmp.jpg"
    )

    success = cv2.imwrite(
        str(temporary_path),
        face_bgr,
        [
            cv2.IMWRITE_JPEG_QUALITY,
            int(jpeg_quality),
        ],
    )

    if not success:
        raise RuntimeError(
            f"cv2.imwrite保存失败：{temporary_path}"
        )

    os.replace(
        temporary_path,
        final_path,
    )

    return final_path


def print_action_startup_diagnostics(
    source_description: str,
    is_video: bool,
    auto_photo_enabled: bool,
    photo_output_dir: Path,
) -> None:
    print("=" * 72)
    print("[启动检查] 动作识别程序")
    print(f"[启动检查] Python：{sys.version.split()[0]}")
    print(f"[启动检查] 系统：{platform.platform()}")
    print(f"[启动检查] NumPy：{np.__version__}")
    print(f"[启动检查] OpenCV：{cv2.__version__}")
    print(f"[启动检查] TFLite后端：{TFLITE_BACKEND}")
    print(f"[启动检查] 动作模型：{MODEL_PATH}")
    print(f"[启动检查] 动作模型存在：{MODEL_PATH.exists()}")
    print(f"[启动检查] 输入源：{source_description}")
    if not is_video:
        print(
            "[自动抓拍] "
            + (
                f"开启，保存目录={photo_output_dir}"
                if auto_photo_enabled
                else "关闭"
            )
        )
        print("[图片保留] 自动抓拍照片不会被本程序删除")
    print("=" * 72)



def main() -> None:
    parser = argparse.ArgumentParser(
        description=(
            "MoveNet左右滑动识别统一运行版："
            "既支持MP4视频，也支持电脑或板子摄像头"
        )
    )

    source_group = parser.add_mutually_exclusive_group(
        required=True
    )

    source_group.add_argument(
        "--video",
        help="视频路径，例如 input_videos/left.mp4",
    )
    source_group.add_argument(
        "--camera",
        help=(
            "摄像头编号或设备路径。"
            "Windows可用0；Linux板子可用/dev/video0"
        ),
    )

    parser.add_argument(
        "--width",
        type=int,
        default=1280,
        help="摄像头期望宽度，默认1280",
    )
    parser.add_argument(
        "--height",
        type=int,
        default=720,
        help="摄像头期望高度，默认720",
    )
    parser.add_argument(
        "--camera-fps",
        type=float,
        default=30.0,
        help="摄像头期望FPS，默认30",
    )
    parser.add_argument(
        "--no-display",
        action="store_true",
        help="不显示预览窗口，板子无桌面环境时使用",
    )
    parser.add_argument(
        "--save-video",
        action="store_true",
        help="摄像头模式下也保存带骨骼标注的视频",
    )
    parser.add_argument(
        "--photo-output-dir",
        default=str(DEFAULT_PHOTO_OUTPUT_DIR),
        help="摄像头自动抓拍图片保存目录",
    )
    parser.add_argument(
        "--no-auto-photo",
        action="store_true",
        help="关闭摄像头人脸自动抓拍",
    )
    parser.add_argument(
        "--photo-detect-interval",
        type=float,
        default=0.5,
        help="摄像头人脸检测间隔秒数，默认0.5",
    )
    parser.add_argument(
        "--photo-rearm-seconds",
        type=float,
        default=3.0,
        help="连续无人脸多少秒后允许抓拍下一位，默认3秒",
    )
    parser.add_argument(
        "--photo-jpeg-quality",
        type=int,
        default=95,
        help="自动抓拍JPEG质量，默认95",
    )

    args = parser.parse_args()

    is_video = args.video is not None

    # 文件视频采用高速离线扫描，不弹预览窗口。
    # 摄像头模式仍由 --no-display 决定是否显示。
    display_enabled = (
        not args.no_display
        and not is_video
    )

    photo_output_dir = Path(
        args.photo_output_dir
    ).expanduser().resolve()
    auto_photo_enabled = (
        not is_video
        and not args.no_auto_photo
    )

    if is_video:
        source_path = Path(args.video).expanduser().resolve()

        if not source_path.exists():
            raise FileNotFoundError(
                f"找不到视频：{source_path}"
            )

        source_value: int | str = str(source_path)
        source_name = source_path.stem
        source_description = str(source_path)
    else:
        source_value = parse_camera_source(args.camera)
        source_name = (
            f"camera_{source_value}"
            .replace("/", "_")
            .replace("\\", "_")
            .replace(":", "_")
        )
        source_description = str(source_value)

    print_action_startup_diagnostics(
        source_description=source_description,
        is_video=is_video,
        auto_photo_enabled=auto_photo_enabled,
        photo_output_dir=photo_output_dir,
    )

    model = MoveNetPose(MODEL_PATH)
    print("[启动成功] MoveNet解释器和模型加载成功")
    detector = SwipeDetector()

    if is_video:
        capture = cv2.VideoCapture(source_value)
    else:
        if isinstance(source_value, int):
            capture = cv2.VideoCapture(source_value)
        else:
            capture = cv2.VideoCapture(
                source_value,
                cv2.CAP_V4L2,
            )

        capture.set(
            cv2.CAP_PROP_FRAME_WIDTH,
            args.width,
        )
        capture.set(
            cv2.CAP_PROP_FRAME_HEIGHT,
            args.height,
        )
        capture.set(
            cv2.CAP_PROP_FPS,
            args.camera_fps,
        )

    if not capture.isOpened():
        raise RuntimeError(
            f"无法打开输入源：{source_description}"
        )

    fps = float(capture.get(cv2.CAP_PROP_FPS))

    if not np.isfinite(fps) or fps <= 1.0:
        fps = (
            30.0
            if is_video
            else max(args.camera_fps, 1.0)
        )

    width = int(
        capture.get(cv2.CAP_PROP_FRAME_WIDTH)
    )
    height = int(
        capture.get(cv2.CAP_PROP_FRAME_HEIGHT)
    )

    if width <= 0 or height <= 0:
        capture.release()
        raise RuntimeError(
            "输入源没有返回有效分辨率"
        )

    camera_face_detector: cv2.CascadeClassifier | None = None
    camera_face_detector_path: Path | None = None

    if auto_photo_enabled:
        (
            camera_face_detector,
            camera_face_detector_path,
        ) = create_camera_face_detector()

        if camera_face_detector is None:
            auto_photo_enabled = False
            print(
                "[自动抓拍警告] 无法加载Haar人脸检测器，"
                "动作识别继续运行，但不会自动保存人物照片。"
            )
            print(
                f"[自动抓拍警告] 检查路径：{camera_face_detector_path}"
            )
        else:
            photo_output_dir.mkdir(parents=True, exist_ok=True)
            print(
                "[自动抓拍成功] Haar人脸检测器已加载："
                f"{camera_face_detector_path}"
            )
            print(
                "[自动抓拍成功] 新人物照片将写入："
                f"{photo_output_dir}"
            )

    sample_every = max(
        1,
        int(round(fps / TARGET_ANALYSIS_FPS)),
    )
    analysis_fps = fps / sample_every

    session_name = (
        source_name
        if is_video
        else (
            f"{source_name}_"
            f"{time.strftime('%Y%m%d_%H%M%S')}"
        )
    )

    output_dir = OUTPUT_ROOT / session_name

    # 只有保存标注视频时才创建动作调试目录。
    if args.save_video:
        output_dir.mkdir(
            parents=True,
            exist_ok=True,
        )

    # 清理旧版本留下的动作JSON。
    # 本版本运行后只保留 action_latest.json。
    cleanup_legacy_action_json()

    # 从上一次后端动作文件继续编号。
    # 即使程序重启，sequence 也不会自动回到 1。
    action_sequence = load_action_sequence(
        BACKEND_ACTION_PATH
    )
    print(
        "[后端动作输出] "
        f"简化文件={BACKEND_ACTION_PATH}"
    )
    print(
        "[后端动作输出] "
        f"当前sequence={action_sequence}；"
        "下一次动作将自动加1"
    )

    # 默认不生成标注视频；显式传入 --save-video 时才保存。
    should_save_video = args.save_video

    writer: cv2.VideoWriter | None = None
    marked_video_path: Path | None = None

    if should_save_video:
        marked_video_path = (
            output_dir
            / f"{session_name}_marked.mp4"
        )

        writer = cv2.VideoWriter(
            str(marked_video_path),
            cv2.VideoWriter_fourcc(*"mp4v"),
            fps,
            (width, height),
        )

        if not writer.isOpened():
            capture.release()
            raise RuntimeError(
                "无法创建结果视频文件"
            )

    need_render = (
        writer is not None
        or display_enabled
    )

    events: list[dict] = []
    frame_index = 0

    last_keypoints: np.ndarray | None = None
    last_transform: dict | None = None

    overlay_action: str | None = None
    overlay_until = -1.0

    photo_armed = True
    face_absent_since: float | None = None
    last_face_check = -1e9
    captured_photo_count = 0
    camera_read_failures = 0
    last_heartbeat = 0.0

    processing_start = time.monotonic()
    camera_start = processing_start

    print("=" * 72)

    if is_video:
        print(f"运行模式：视频")
        print(f"输入视频：{source_description}")
    else:
        print(f"运行模式：摄像头")
        print(f"摄像头：{source_description}")
        print(
            f"实际分辨率：{width}x{height}"
        )

    print(f"输入FPS：{fps:.2f}")
    print(f"动作分析FPS：{analysis_fps:.2f}")
    print(
        "[动作灵敏度] 板端平衡灵敏版："
        f"关键点阈值={MIN_KEYPOINT_SCORE}；"
        f"最少轨迹点={MIN_VALID_POINTS}；"
        f"最小横移={MIN_HORIZONTAL_DISTANCE}肩宽；"
        f"跟踪窗口={TRACK_WINDOW_SECONDS}秒"
    )
    print(
        "[防重复触发] 混合解锁："
        f"最短锁定={MIN_RELEASE_LOCK_SECONDS:.2f}秒；"
        f"放手确认={RELEASE_CONFIRM_POINTS}次；"
        f"丢点确认={RELEASE_MISSING_CONFIRM_POINTS}次；"
        f"最长锁定={MAX_RELEASE_LOCK_SECONDS:.2f}秒"
    )
    print(
        f"预览："
        f"{'小窗口' if display_enabled else '关闭'}"
    )
    print(
        f"保存标注视频："
        f"{'是' if should_save_video else '否'}"
    )
    if not is_video:
        print(
            f"自动抓拍人物照片："
            f"{'开启' if auto_photo_enabled else '关闭'}"
        )
        if auto_photo_enabled:
            print(
                f"裁剪后人脸保存目录："
                f"{photo_output_dir}"
            )
            print(
                "人脸拍照规则：第一次检测到有效正脸后立即拍照；"
                "使用较紧的人脸框裁剪；"
                "当前脸未离开时不重复拍；"
                f"连续无人脸"
                f"{max(args.photo_rearm_seconds, 0.5):.1f}秒后"
                "等待下一张脸"
            )
            print(
                "[人脸检测参数] "
                "scaleFactor=1.10；"
                "minNeighbors=6；"
                "最小人脸=60x60；"
                "紧裁剪扩边=左右6%、上10%、下8%"
            )
            print(
                "[动作保护] "
                "动作状态机使用此前板端确认可用的混合解锁版本；"
                "Haar只在非MoveNet分析帧运行"
            )
    print("按 Q 退出；终端按 Ctrl+C 也可以停止。")
    print("=" * 72)

    try:
        while True:
            ok, frame = capture.read()

            if not ok or frame is None:
                if is_video:
                    break

                camera_read_failures += 1
                print(
                    "[摄像头读取失败] "
                    f"累计={camera_read_failures}，0.1秒后重试"
                )
                time.sleep(0.1)
                continue

            if is_video:
                timestamp = frame_index / fps
            else:
                timestamp = (
                    time.monotonic()
                    - camera_start
                )

            if (
                auto_photo_enabled
                and camera_face_detector is not None
                # 避免同一帧同时运行Haar和MoveNet重计算。
                and frame_index % sample_every != 0
                and timestamp - last_face_check
                >= max(args.photo_detect_interval, 0.1)
            ):
                last_face_check = timestamp

                try:
                    face_box = detect_camera_face_box(
                        frame,
                        camera_face_detector,
                    )

                    if photo_armed:
                        if face_box is not None:
                            face_crop = crop_camera_face(
                                frame,
                                face_box,
                            )

                            saved_photo = save_camera_face_atomic(
                                output_dir=photo_output_dir,
                                face_bgr=face_crop,
                                jpeg_quality=int(
                                    np.clip(
                                        args.photo_jpeg_quality,
                                        1,
                                        100,
                                    )
                                ),
                            )

                            captured_photo_count += 1
                            photo_armed = False
                            face_absent_since = None

                            print(
                                "[人脸抓拍完成] "
                                f"第{captured_photo_count}张紧裁剪脸部照片："
                                f"{saved_photo}"
                            )
                            print(
                                "[人脸抓拍说明] "
                                f"裁剪尺寸={face_crop.shape[1]}x"
                                f"{face_crop.shape[0]}；"
                                "当前脸未离开时不重复拍照；"
                                "main.py会处理该照片"
                            )
                    else:
                        if face_box is not None:
                            face_absent_since = None
                        else:
                            if face_absent_since is None:
                                face_absent_since = timestamp
                                print(
                                    "[人脸离开检测] "
                                    "开始计算连续无人脸时间"
                                )
                            elif (
                                timestamp - face_absent_since
                                >= max(
                                    args.photo_rearm_seconds,
                                    0.5,
                                )
                            ):
                                # 手势追踪或等待放手期间不重装填，
                                # 防止挥手遮挡人脸导致误拍新照片。
                                if detector.state in (
                                    "TRACKING",
                                    "WAIT_RELEASE",
                                ):
                                    face_absent_since = None
                                    print(
                                        "[人脸拍照保护] "
                                        "当前手势状态="
                                        f"{detector.state}，"
                                        "延迟重装填以避免"
                                        "挥手遮挡触发误拍"
                                    )
                                else:
                                    photo_armed = True
                                    face_absent_since = None

                                    print(
                                        "[人脸拍照待命] "
                                        "已确认上一张脸离开，"
                                        "等待下一张脸"
                                    )

                except Exception as error:
                    print(
                        "[自动抓拍错误] "
                        f"{type(error).__name__}: {error}"
                    )
                    traceback.print_exc()

            if not is_video and timestamp - last_heartbeat >= 10.0:
                print(
                    "[摄像头心跳] "
                    f"运行={timestamp:.1f}秒；"
                    f"读取帧={frame_index + 1}；"
                    f"抓拍照片={captured_photo_count}；"
                    f"动作事件={len(events)}；"
                    f"动作状态={detector.state}；"
                    f"轨迹点={len(detector.track)}；"
                    f"抓拍状态={'等待人脸' if photo_armed else '已拍，等待脸离开'}；"
                    f"读取失败={camera_read_failures}"
                )
                last_heartbeat = timestamp

            if frame_index % sample_every == 0:
                (
                    last_keypoints,
                    last_transform,
                ) = model.predict(frame)

                event = detector.update(
                    keypoints=last_keypoints,
                    timestamp=timestamp,
                )

                if event is not None:
                    event["source_type"] = (
                        "video"
                        if is_video
                        else "camera"
                    )
                    event["source"] = (
                        source_description
                    )
                    event["wall_time"] = (
                        time.strftime(
                            "%Y-%m-%dT%H:%M:%S"
                        )
                    )

                    events.append(event)

                    # 摄像头实时发布。
                    # 本版本只覆盖后端 action_latest.json，
                    # 不再生成任何其他动作JSON。
                    if not is_video:
                        action_sequence += 1
                        publish_backend_action(
                            action=event["action"],
                            sequence=action_sequence,
                        )

                    overlay_action = event["action"]
                    overlay_until = timestamp + 0.8

                    if not is_video:
                        print(
                            "[动作识别完成] "
                            f"time={timestamp:.2f}s；"
                            f"action={event['action']}；"
                            f"wrist={event['wrist']}；"
                            f"distance={event['horizontal_distance_shoulder_widths']:.3f}；"
                            f"后端动作文件={BACKEND_ACTION_PATH}"
                        )

            if not need_render:
                frame_index += 1
                continue

            if (
                last_keypoints is not None
                and last_transform is not None
            ):
                draw_pose(
                    frame,
                    last_keypoints,
                    model,
                    last_transform,
                )

            cv2.putText(
                frame,
                f"time={timestamp:.2f}s",
                (18, 34),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.75,
                (255, 255, 255),
                2,
                cv2.LINE_AA,
            )

            cv2.putText(
                frame,
                (
                    f"state={detector.state} "
                    f"wrist="
                    f"{detector.active_wrist or '-'}"
                ),
                (18, 64),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.65,
                (255, 255, 255),
                2,
                cv2.LINE_AA,
            )

            if (
                overlay_action is not None
                and timestamp <= overlay_until
            ):
                cv2.putText(
                    frame,
                    f"ACTION: {overlay_action}",
                    (18, 105),
                    cv2.FONT_HERSHEY_SIMPLEX,
                    1.0,
                    (0, 0, 255),
                    3,
                    cv2.LINE_AA,
                )

            if writer is not None:
                writer.write(frame)

            if display_enabled:
                cv2.imshow(
                    "Swipe Runtime",
                    build_preview(frame),
                )

                if (
                    cv2.waitKey(1) & 0xFF
                ) == ord("q"):
                    break

            frame_index += 1

    except KeyboardInterrupt:
        print("\n收到停止命令。")

    finally:
        capture.release()

        if writer is not None:
            writer.release()

        cv2.destroyAllWindows()

    if is_video:
        if events:
            final_event = events[-1]

            action_sequence += 1
            publish_backend_action(
                action=final_event["action"],
                sequence=action_sequence,
            )

            print(
                "[完整视频扫描完成] "
                f"最终动作={final_event['action']}"
            )
        else:
            print(
                "[完整视频扫描完成] "
                "未检测到左滑或右滑；"
                "action_latest.json保持原内容不变"
            )

    elapsed = (
        time.monotonic()
        - processing_start
    )

    print("=" * 72)
    print(
        f"处理结束，检测到动作次数："
        f"{len(events)}"
    )

    for index, event in enumerate(
        events,
        start=1,
    ):
        print(
            f"{index}. "
            f"{event['timestamp_seconds']:.2f}s "
            f"→ {event['action']}"
        )

    print(f"唯一动作JSON文件：{BACKEND_ACTION_PATH}")
    print(
        "该文件固定只有两个字段："
        "action、sequence"
    )
    print(
        "本版本不会生成 latest_action.json、"
        "events.json 或 events.jsonl"
    )

    if marked_video_path is not None:
        print(f"标注视频：{marked_video_path}")

    if not is_video:
        print(f"摄像头自动抓拍照片数：{captured_photo_count}")
        print(f"抓拍照片保存目录：{photo_output_dir}")
    print(f"运行耗时：{elapsed:.2f}秒")
    print("=" * 72)


if __name__ == "__main__":
    main()

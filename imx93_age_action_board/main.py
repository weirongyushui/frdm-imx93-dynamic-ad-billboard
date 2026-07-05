from __future__ import annotations

import json
import platform
import sys
import traceback
import time
from datetime import datetime
from pathlib import Path

import cv2
import numpy as np
try:
    import onnx
except ImportError:
    onnx = None

import onnxruntime as ort


PROJECT_DIR = Path(__file__).resolve().parent
MODEL_PATH = PROJECT_DIR / "pretrained_models" / "genderage.onnx"
INPUT_DIR = PROJECT_DIR / "input_photos"

OUTPUT_DIR = PROJECT_DIR / "output_results" / "live_watch_single"
READY_PATH = OUTPUT_DIR / ".age_watcher_ready"
BACKEND_PERSON_PATH = (
    PROJECT_DIR
    / "output_results"
    / "backend_events"
    / "person_latest.json"
)

# 每0.5秒检查一次文件夹
SCAN_INTERVAL_SECONDS = 0.5

# 年龄校准：
# 模型原始年龄统一减 7 岁。
# 后续需要继续调整时，只修改这个常量即可。
AGE_ADJUSTMENT_YEARS = -7

# False：启动前已有的旧照片不处理，只处理启动后新放入/覆盖更新的照片
PROCESS_EXISTING_ON_START = False

SUPPORTED_EXTENSIONS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}


def read_image_windows(path: Path) -> np.ndarray:
    data = np.fromfile(str(path), dtype=np.uint8)
    image = cv2.imdecode(data, cv2.IMREAD_COLOR)

    if image is None:
        raise ValueError("图片无法读取，可能尚未保存完成或文件已损坏")

    return image


def file_signature(path: Path) -> tuple[str, int, int]:
    stat = path.stat()
    return (
        str(path.resolve()).lower(),
        int(stat.st_mtime_ns),
        int(stat.st_size),
    )


def wait_until_file_stable(
    path: Path,
    stable_checks: int = 3,
    delay_seconds: float = 0.3,
) -> bool:
    previous_size = -1
    stable_count = 0

    for _ in range(30):
        try:
            current_size = path.stat().st_size
        except (FileNotFoundError, PermissionError):
            time.sleep(delay_seconds)
            continue

        if current_size > 0 and current_size == previous_size:
            stable_count += 1
            if stable_count >= stable_checks:
                return True
        else:
            stable_count = 0

        previous_size = current_size
        time.sleep(delay_seconds)

    return False


def detect_model_normalization(model_path: Path) -> tuple[float, float]:
    # 电脑端已验证的 InsightFace genderage.onnx
    # 通常在模型内部包含归一化节点。
    # 板端没有安装 onnx Python 包时，仍可由 onnxruntime 推理。
    if onnx is None:
        print(
            "提示：未安装onnx模块，使用已验证预处理："
            "mean=0.0，std=1.0"
        )
        return 0.0, 1.0

    model = onnx.load(str(model_path))

    find_sub = False
    find_mul = False

    for index, node in enumerate(model.graph.node[:8]):
        if node.name.startswith("Sub") or node.name.startswith("_minus"):
            find_sub = True

        if node.name.startswith("Mul") or node.name.startswith("_mul"):
            find_mul = True

        if index < 3 and node.name == "bn_data":
            find_sub = True
            find_mul = True

    if find_sub and find_mul:
        return 0.0, 1.0

    return 127.5, 128.0


def softmax(values: np.ndarray) -> np.ndarray:
    values = values.astype(np.float64)
    values -= np.max(values)
    exp_values = np.exp(values)
    return exp_values / np.sum(exp_values)


def expand_face_box(
    x: int,
    y: int,
    width: int,
    height: int,
    image_width: int,
    image_height: int,
    margin_ratio: float = 0.28,
) -> tuple[int, int, int, int]:
    center_x = x + width / 2.0
    center_y = y + height / 2.0

    side = max(width, height) * (1.0 + margin_ratio * 2.0)

    x1 = int(round(center_x - side / 2.0))
    y1 = int(round(center_y - side / 2.0))
    x2 = int(round(center_x + side / 2.0))
    y2 = int(round(center_y + side / 2.0))

    return (
        max(0, x1),
        max(0, y1),
        min(image_width, x2),
        min(image_height, y2),
    )


class GenderAgePredictor:
    def __init__(self, model_path: Path) -> None:
        if not model_path.exists():
            raise FileNotFoundError(f"找不到模型：{model_path}")

        self.input_mean, self.input_std = detect_model_normalization(
            model_path
        )

        self.session = ort.InferenceSession(
            str(model_path),
            providers=["CPUExecutionProvider"],
        )

        input_info = self.session.get_inputs()[0]
        output_info = self.session.get_outputs()[0]

        self.input_name = input_info.name
        self.output_name = output_info.name
        self.input_height = int(input_info.shape[2])
        self.input_width = int(input_info.shape[3])

        print(f"模型输入：{input_info.name} {input_info.shape}")
        print(f"模型输出：{output_info.name} {output_info.shape}")

    def predict(self, face_bgr: np.ndarray) -> dict:
        blob = cv2.dnn.blobFromImage(
            face_bgr,
            scalefactor=1.0 / self.input_std,
            size=(self.input_width, self.input_height),
            mean=(
                self.input_mean,
                self.input_mean,
                self.input_mean,
            ),
            swapRB=True,
            crop=False,
        )

        prediction = self.session.run(
            [self.output_name],
            {self.input_name: blob},
        )[0][0]

        gender_logits = prediction[:2]
        gender_index = int(np.argmax(gender_logits))

        # InsightFace模型：0=female，1=male
        gender = "male" if gender_index == 1 else "female"

        probabilities = softmax(gender_logits)
        gender_confidence = float(probabilities[gender_index])

        raw_age = int(
            round(float(prediction[2]) * 100.0)
        )
        raw_age = int(np.clip(raw_age, 0, 100))

        age = int(
            np.clip(
                raw_age + AGE_ADJUSTMENT_YEARS,
                0,
                100,
            )
        )

        return {
            "raw_age": raw_age,
            "age_adjustment_years": AGE_ADJUSTMENT_YEARS,
            "age": age,
            "gender": gender,
            "gender_confidence": round(gender_confidence, 6),
        }


def create_face_detector() -> cv2.CascadeClassifier:
    cascade_path = (
        Path(cv2.data.haarcascades)
        / "haarcascade_frontalface_default.xml"
    )

    detector = cv2.CascadeClassifier(str(cascade_path))

    if detector.empty():
        raise RuntimeError(f"无法加载人脸检测器：{cascade_path}")

    return detector


def detect_primary_face(
    image_bgr: np.ndarray,
    detector: cv2.CascadeClassifier,
) -> tuple[int, int, int, int] | None:
    """
    检测所有候选人脸，但只返回面积最大的一个。
    面积最大的人脸通常是距离摄像头最近的主要人物。
    """
    gray = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2GRAY)
    gray = cv2.equalizeHist(gray)

    boxes = detector.detectMultiScale(
        gray,
        scaleFactor=1.10,
        minNeighbors=6,
        minSize=(80, 80),
        flags=cv2.CASCADE_SCALE_IMAGE,
    )

    if len(boxes) == 0:
        return None

    boxes = [tuple(map(int, box)) for box in boxes]

    primary_box = max(
        boxes,
        key=lambda box: box[2] * box[3],
    )

    return primary_box


def list_photos() -> list[Path]:
    photos = [
        path
        for path in INPUT_DIR.iterdir()
        if path.is_file()
        and path.suffix.lower() in SUPPORTED_EXTENSIONS
    ]

    return sorted(
        photos,
        key=lambda path: (
            path.stat().st_ctime_ns,
            path.name.lower(),
        ),
    )


def process_photo(
    image_path: Path,
    detector: cv2.CascadeClassifier,
    predictor: GenderAgePredictor,
) -> dict:
    """
    处理一张新图片。

    camera_face_开头的文件已经由摄像头程序裁剪完成，
    直接用于年龄性别推理，不再二次裁剪。

    对手工放入的普通图片，仍兼容Haar检测主要人脸。
    """
    image = read_image_windows(image_path)

    timestamp_text = datetime.now().isoformat(
        timespec="seconds"
    )

    if image_path.stem.startswith("camera_face_"):
        face_crop = image
    else:
        image_height, image_width = image.shape[:2]

        primary_box = detect_primary_face(
            image,
            detector,
        )

        if primary_box is None:
            return {
                "timestamp": timestamp_text,
                "image": image_path.name,
                "status": "no_face",
                "age": None,
                "gender": None,
                "gender_confidence": None,
            }

        x, y, width, height = primary_box

        x1, y1, x2, y2 = expand_face_box(
            x=x,
            y=y,
            width=width,
            height=height,
            image_width=image_width,
            image_height=image_height,
        )

        face_crop = image[y1:y2, x1:x2]

    if face_crop.size == 0:
        raise RuntimeError("人脸图片为空")

    prediction = predictor.predict(face_crop)

    return {
        "timestamp": timestamp_text,
        "image": image_path.name,
        "status": "success",
        "raw_age": prediction["raw_age"],
        "age_adjustment_years": prediction[
            "age_adjustment_years"
        ],
        "age": prediction["age"],
        "gender": prediction["gender"],
        "gender_confidence": prediction[
            "gender_confidence"
        ],
    }


def write_json_atomic(
    path: Path,
    data: dict,
) -> None:
    path.parent.mkdir(
        parents=True,
        exist_ok=True,
    )

    temporary_path = path.with_suffix(
        path.suffix + ".tmp"
    )

    temporary_path.write_text(
        json.dumps(
            data,
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    temporary_path.replace(path)


def publish_person_result(result: dict) -> bool:
    """
    只有新照片识别成功时，才覆盖person_latest.json。
    失败或无人脸时不修改旧人物结果。
    """
    if result.get("status") != "success":
        return False

    age = result.get("age")
    gender = result.get("gender")

    if (
        isinstance(age, bool)
        or not isinstance(age, int)
        or not isinstance(gender, str)
    ):
        return False

    normalized_gender = gender.strip().lower()

    if normalized_gender not in {
        "male",
        "female",
    }:
        return False

    backend_result = {
        "age": age,
        "gender": normalized_gender,
    }

    write_json_atomic(
        BACKEND_PERSON_PATH,
        backend_result,
    )

    print(
        "[后端人物字段已覆盖] "
        f"age={age}；"
        f"gender={normalized_gender}；"
        f"文件={BACKEND_PERSON_PATH}"
    )
    print(
        "BACKEND_PERSON_JSON="
        + json.dumps(
            backend_result,
            ensure_ascii=False,
            separators=(",", ":"),
        )
    )

    return True


def print_result(result: dict) -> None:
    if result["status"] == "success":
        print(
            f"[{result['timestamp']}] "
            f"{result['image']}："
            f"raw_age={result['raw_age']}，"
            f"adjustment={result['age_adjustment_years']}，"
            f"age={result['age']}，"
            f"gender={result['gender']}，"
            f"confidence={result['gender_confidence']:.2%}"
        )
        return

    if result["status"] == "no_face":
        print(
            f"[{result['timestamp']}] "
            f"{result['image']}：没有检测到主要人脸"
        )
        return

    print(
        f"[{result['timestamp']}] "
        f"{result['image']}：处理失败，"
        f"{result.get('message', '')}"
    )


def print_startup_diagnostics(
    cascade_path: Path,
) -> None:
    print("=" * 70)
    print("[启动检查] 年龄性别图片识别")
    print(f"[启动检查] Python：{sys.version.split()[0]}")
    print(f"[启动检查] 系统：{platform.platform()}")
    print(f"[启动检查] NumPy：{np.__version__}")
    print(f"[启动检查] OpenCV：{cv2.__version__}")
    print(f"[启动检查] ONNX Runtime：{ort.__version__}")
    print(f"[启动检查] ONNX模型：{MODEL_PATH}")
    print(f"[启动检查] ONNX模型存在：{MODEL_PATH.exists()}")
    print(f"[启动检查] 人脸检测器：{cascade_path}")
    print(f"[启动检查] 人脸检测器存在：{cascade_path.exists()}")
    print(
        f"[脸部图片目录] {INPUT_DIR}"
    )
    print(
        "[图片规则] camera_face_图片已经裁剪完成；"
        "main.py不会打开摄像头，也不会删除或移动图片"
    )
    print("=" * 70)



def main() -> None:
    INPUT_DIR.mkdir(exist_ok=True)
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    BACKEND_PERSON_PATH.parent.mkdir(
        parents=True,
        exist_ok=True,
    )

    # 清理旧版本留下的人物调试JSON。
    # 本版本人物端只保留person_latest.json。
    for legacy_path in (
        OUTPUT_DIR / "latest_result.json",
        OUTPUT_DIR / "history.jsonl",
        OUTPUT_DIR / "watcher_ready.json",
    ):
        try:
            legacy_path.unlink()
            print(
                "[启动整理] 已删除旧人物调试文件："
                f"{legacy_path}"
            )
        except FileNotFoundError:
            pass
        except OSError as error:
            print(
                "[启动警告] 无法删除旧人物调试文件："
                f"{legacy_path}；{error}"
            )

    cascade_path = (
        Path(cv2.data.haarcascades)
        / "haarcascade_frontalface_default.xml"
    )
    print_startup_diagnostics(cascade_path)

    detector = create_face_detector()
    print("[启动成功] Haar人脸检测器加载成功")

    predictor = GenderAgePredictor(MODEL_PATH)
    print("[启动成功] 年龄性别ONNX模型加载成功")

    processed_signatures: set[tuple[str, int, int]] = set()

    if not PROCESS_EXISTING_ON_START:
        for path in list_photos():
            try:
                processed_signatures.add(file_signature(path))
            except OSError:
                pass

    READY_PATH.write_text(
        "ready\n",
        encoding="utf-8",
    )
    print(f"[监控就绪] 已写入就绪标记：{READY_PATH}")

    print("=" * 70)
    print("单人实时监控已经启动")
    print(f"监控文件夹：{INPUT_DIR}")
    print(f"扫描间隔：{SCAN_INTERVAL_SECONDS} 秒")
    print(
        "自动抓拍生成的camera_face_图片已经是裁剪后脸部图。"
    )
    print(
        "每张启动后新增的脸部照片只处理一次，"
        "成功后只输出age和gender。"
    )
    print(
        "[后端人物JSON] "
        f"{BACKEND_PERSON_PATH}"
    )
    print(
        "[输出规则] 只有成功识别人脸时才覆盖人物JSON；"
        "失败或无人脸时保留旧文件"
    )
    print(
        "[年龄校准] "
        f"模型原始年龄统一调整 {AGE_ADJUSTMENT_YEARS} 岁"
    )
    print("按 Ctrl + C 停止。")
    print("=" * 70)

    last_heartbeat = time.monotonic()
    processed_count = 0

    try:
        while True:
            now_monotonic = time.monotonic()
            if now_monotonic - last_heartbeat >= 10.0:
                print(
                    "[图片监控心跳] "
                    f"运行正常；已处理={processed_count}；"
                    f"监控目录={INPUT_DIR}"
                )
                last_heartbeat = now_monotonic
            pending: list[
                tuple[Path, tuple[str, int, int]]
            ] = []

            for photo in list_photos():
                try:
                    signature = file_signature(photo)
                except (FileNotFoundError, PermissionError):
                    continue

                if signature not in processed_signatures:
                    pending.append((photo, signature))

            for photo, original_signature in pending:
                print(
                    "[发现图片] "
                    f"文件={photo.name}；大小={photo.stat().st_size} bytes；"
                    "等待写入稳定"
                )

                if not wait_until_file_stable(photo):
                    print(
                        f"照片长时间未写完，稍后重试："
                        f"{photo.name}"
                    )
                    continue

                try:
                    stable_signature = file_signature(photo)

                    result = process_photo(
                        image_path=photo,
                        detector=detector,
                        predictor=predictor,
                    )

                    print_result(result)

                    if publish_person_result(result):
                        print(
                            "[人物处理完成] "
                            "本次新照片已输出年龄和性别"
                        )
                    else:
                        print(
                            "[人物结果未更新] "
                            "本照片未成功识别人脸，"
                            "保留原person_latest.json"
                        )
                    print(
                        "[脸部图片保留] "
                        f"{photo}"
                    )
                    processed_count += 1

                    processed_signatures.add(
                        original_signature
                    )
                    processed_signatures.add(
                        stable_signature
                    )

                except Exception as error:
                    result = {
                        "timestamp": datetime.now().isoformat(
                            timespec="seconds"
                        ),
                        "image": photo.name,
                        "status": "failed",
                        "age": None,
                        "gender": None,
                        "gender_confidence": None,
                        "message": str(error),
                    }

                    print_result(result)
                    print(
                        "[人物结果未更新] "
                        "处理失败，保留原person_latest.json"
                    )
                    print("[错误详情] 以下是完整异常堆栈，便于板端调试：")
                    traceback.print_exc()
                    print(f"[图片保留] 失败原图仍在：{photo}")
                    processed_count += 1

                    try:
                        processed_signatures.add(
                            file_signature(photo)
                        )
                    except OSError:
                        pass

            time.sleep(SCAN_INTERVAL_SECONDS)

    except KeyboardInterrupt:
        print()
        print("单人实时监控已停止。")


if __name__ == "__main__":
    main()

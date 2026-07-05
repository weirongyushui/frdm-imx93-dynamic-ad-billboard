from __future__ import annotations

import glob
import importlib
import os
import platform
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parent


def check_import(module_name: str) -> bool:
    try:
        module = importlib.import_module(module_name)
    except Exception as exc:
        print(f"[FAIL] import {module_name}: {type(exc).__name__}: {exc}")
        return False

    version = getattr(module, "__version__", "unknown")
    print(f"[ OK ] import {module_name}, version={version}")
    return True


def main() -> None:
    print("=" * 72)
    print("i.MX93 项目板端环境检查")
    print("=" * 72)
    print(f"Python: {sys.version}")
    print(f"Executable: {sys.executable}")
    print(f"Platform: {platform.platform()}")
    print(f"Machine: {platform.machine()}")
    print(f"Project: {ROOT}")
    print()

    required_files = [
        ROOT / "app.py",
        ROOT / "main.py",
        ROOT / "19_swipe_runtime_video_camera.py",
        ROOT / "pretrained_models" / "genderage.onnx",
        ROOT / "pretrained_models" / "movenet_lightning_int8.tflite",
    ]

    print("文件检查：")
    for path in required_files:
        if path.exists():
            size = path.stat().st_size
            print(f"[ OK ] {path.relative_to(ROOT)} ({size} bytes)")
        else:
            print(f"[FAIL] 缺少 {path.relative_to(ROOT)}")

    print()
    print("Python 模块检查：")
    check_import("numpy")
    check_import("cv2")
    check_import("onnxruntime")

    litert_ok = False
    for module_name in [
        "ai_edge_litert",
        "tflite_runtime.interpreter",
        "tensorflow.lite",
    ]:
        if check_import(module_name):
            litert_ok = True

    if not litert_ok:
        print("[FAIL] 没有找到可用的 LiteRT/TFLite Python 解释器")

    print()
    print("摄像头设备：")
    cameras = sorted(glob.glob("/dev/video*"))
    if cameras:
        for camera in cameras:
            print(f"[ OK ] {camera}")
    else:
        print("[WARN] 没有发现 /dev/video*")

    print()
    print("Ethos-U delegate：")
    delegate_candidates = [
        "/usr/lib/libethosu_delegate.so",
        "/usr/lib64/libethosu_delegate.so",
        "/lib/libethosu_delegate.so",
        "/lib64/libethosu_delegate.so",
    ]
    found_delegate = False
    for candidate in delegate_candidates:
        if os.path.exists(candidate):
            print(f"[ OK ] {candidate}")
            found_delegate = True

    if not found_delegate:
        print("[WARN] 常见路径未发现 libethosu_delegate.so")
        print("       这不影响先做 CPU 流程测试，但表示当前不能确认 NPU 可用。")

    print()
    print("检查结束。请把全部输出连同 traceback 一起保存。")


if __name__ == "__main__":
    main()
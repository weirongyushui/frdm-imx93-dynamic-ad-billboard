# i.MX93 板端交接与调试说明

## 一、这份包里有什么

核心程序：

- `app.py`：总程序，监控图片、视频或摄像头，并生成两类独立事件。
- `main.py`：图片年龄、性别识别。
- `19_swipe_runtime_video_camera.py`：动作识别。
- `pretrained_models/genderage.onnx`：年龄性别模型。
- `pretrained_models/movenet_lightning_int8.tflite`：动作姿态模型。

辅助文件：

- `check_board.py`：检查板端 Python、依赖、模型和摄像头。
- `run_board.sh`：使用摄像头运行。
- `collect_debug_info.sh`：收集板端环境和错误信息。
- `BOARD_STATUS.md`：当前完成情况和板端待办。

## 二、重要说明

这套代码已经在 Windows 电脑上完成图片、视频流程测试，但尚未在目标 i.MX93 板卡和其实际 BSP 镜像上验证。

当前代码的实际状态：

1. 年龄性别模型使用 ONNX Runtime。
2. 动作模型使用 LiteRT/TFLite 解释器。
3. 当前代码没有加载 `libethosu_delegate.so`，因此不能直接认定已经使用 i.MX93 的 Ethos-U65 NPU。
4. NPU 优化需要使用与板端 BSP/驱动匹配的 Vela 版本编译模型，并在代码中加载 Ethos-U delegate。
5. 板端已有摄像头程序时，不要默认让两个独立进程同时打开同一个 `/dev/video0`。

## 三、把项目复制到板子

建议目录：

```bash
/home/root/imx93_age_action
```

或：

```bash
/opt/imx93_age_action
```

解压后进入目录：

```bash
cd /home/root/imx93_age_action
chmod +x run_board.sh collect_debug_info.sh
```

## 四、先收集环境信息

```bash
./collect_debug_info.sh
```

它会生成：

```text
board_debug_日期时间.txt
```

调试失败时，把这个文件和终端完整报错一起发回。

## 五、检查依赖和文件

```bash
python3 check_board.py
```

重点确认：

- 架构为 `aarch64` 或 ARM64。
- Python 能导入 `numpy`、`cv2`、`onnxruntime`。
- 至少存在一种 LiteRT/TFLite Python 解释器。
- 两个模型文件存在。
- 摄像头设备存在，例如 `/dev/video0`。
- 是否存在 `/usr/lib/libethosu_delegate.so`。

不要直接照搬 Windows 的 `.venv`。Windows 虚拟环境和二进制扩展不能在 ARM Linux 上使用。

## 六、先做文件模式测试

### 1. 启动总程序

```bash
python3 app.py
```

### 2. 测试年龄、性别

程序启动以后，再复制一张新图片到：

```text
input_photos/
```

成功后查看：

```bash
cat output_results/backend_events/person_latest.json
```

### 3. 测试左右滑视频

程序启动以后，再复制一段新视频到：

```text
input_videos/
```

成功后查看：

```bash
cat output_results/backend_events/action_latest.json
```

## 七、摄像头模式测试

先确认没有其他程序占用摄像头：

```bash
fuser /dev/video0
```

再运行：

```bash
CAMERA=/dev/video0 ./run_board.sh
```

等价于：

```bash
python3 app.py --camera /dev/video0 --no-display
```

注意：摄像头模式只负责动作识别。年龄性别仍然要求现有拍照程序把新图片保存到 `input_photos/`。

## 八、与板上现有摄像头程序集成

如果原有广告机程序也在打开 `/dev/video0`，最终集成时优先使用以下方案之一：

1. 由同一个摄像头主进程读取帧，再把帧同时交给人脸拍照逻辑和动作识别逻辑。
2. 使用 GStreamer tee 或已有媒体管线分流。
3. 临时调试时，让原程序输出视频片段到 `input_videos/`，本项目不直接打开摄像头。
4. 使用两个独立摄像头。

不要假设两个 Python/OpenCV 进程一定能同时稳定打开同一个 V4L2 设备。

保存图片或视频时建议先写临时文件，再原子改名，防止监控程序读取到未写完的文件，例如：

```python
temporary = "input_photos/person.jpg.tmp"
final = "input_photos/person.jpg"
# 写完 temporary 后：
os.replace(temporary, final)
```

## 九、依赖安装原则

板端优先使用 BSP/Yocto 镜像自带的 eIQ、OpenCV、LiteRT/TFLite 包。

先确认系统镜像和版本：

```bash
cat /etc/os-release
uname -a
python3 --version
```

ONNX Runtime 的 Linux ARM64 Python 包在官方平台矩阵中受支持，但实际能否直接 `pip install onnxruntime`，还取决于板端 Python 版本、glibc 和镜像配置。

OpenCV 优先使用板端系统包，例如 `python3-opencv`；不要优先在板子上从源码编译 `opencv-python`。

## 十、NPU 优化属于第二阶段

先在 CPU 上跑通完整业务流程，再做 NPU：

```bash
vela pretrained_models/movenet_lightning_int8.tflite
```

Vela 一般会生成类似：

```text
output/movenet_lightning_int8_vela.tflite
```

然后先用板端 benchmark 工具验证模型与 delegate：

```bash
/usr/bin/tensorflow-lite-*/examples/benchmark_model \
  --graph=output/movenet_lightning_int8_vela.tflite \
  --external_delegate_path=/usr/lib/libethosu_delegate.so
```

只有 benchmark 成功、代码加载了 delegate，才能说动作模型实际运行在 NPU 上。

Vela 版本必须与板端 Ethos-U 驱动/BSP匹配，不要随意使用不同版本。

## 十一、验收顺序

按这个顺序调试，不要一次性全接：

1. `python3 check_board.py`
2. 图片年龄性别
3. 视频左右滑
4. 摄像头动作
5. 与现有拍照程序联动
6. 后端事件发送
7. NPU 优化
8. 开机自启动

## 十二、需要回传的调试资料

遇到问题时请提供：

- 板卡具体型号。
- BSP/系统镜像版本。
- `board_debug_*.txt`。
- 完整 Python traceback。
- `ls -l /dev/video*` 输出。
- 原有摄像头程序如何取帧和保存图片。
- 是否存在 `libethosu_delegate.so`。
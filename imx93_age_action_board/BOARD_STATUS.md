# BOARD STATUS

## 已在电脑端完成

- 启动后自动监控 `input_photos`。
- 图片输出独立的年龄性别用户事件。
- 启动后自动监控 `input_videos`。
- 视频高速完整扫描后输出动作事件。
- 同一用户动作使用相同 `session_id`。
- 多次动作使用递增 `sequence`。
- 图片和视频自动归档。
- 输出流水：
  - `output_results/backend_events/person_events.jsonl`
  - `output_results/backend_events/action_events.jsonl`

## 尚未在板端确认

- i.MX93 具体板卡和 BSP 版本。
- Python 与二进制依赖是否兼容。
- ONNX Runtime 在目标镜像上的安装方式。
- LiteRT/TFLite Python 解释器的具体包名和导入路径。
- 摄像头设备编号和格式。
- 原有摄像头程序与动作程序的取帧共享方式。
- MoveNet 的 Vela 编译兼容性。
- `libethosu_delegate.so` 加载。
- NPU 实际加速。
- systemd 开机自启动。
- 后端接口发送。

## 当前结论

这是“电脑端功能完成、可交给板端同学开始移植调试”的版本，不应描述为“已经在 i.MX93 上验证完成”或“已经启用 NPU”。
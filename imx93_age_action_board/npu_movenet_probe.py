#!/usr/bin/env python3
from pathlib import Path
import numpy as np

MODEL = Path("/root/imx93_age_action_board/pretrained_models/movenet_lightning_int8_vela.tflite")
DELEGATE = Path("/usr/lib/libethosu_delegate.so")

print("[PROBE] model:", MODEL)
print("[PROBE] delegate:", DELEGATE)

from ai_edge_litert.interpreter import Interpreter, load_delegate

delegate = load_delegate(str(DELEGATE))
interpreter = Interpreter(
    model_path=str(MODEL),
    experimental_delegates=[delegate],
)

print("[PROBE] allocate_tensors...")
interpreter.allocate_tensors()

input_detail = interpreter.get_input_details()[0]
output_detail = interpreter.get_output_details()[0]

print("[PROBE] input:", input_detail["shape"], input_detail["dtype"])
print("[PROBE] output:", output_detail["shape"], output_detail["dtype"])

dummy = np.zeros(input_detail["shape"], dtype=input_detail["dtype"])
interpreter.set_tensor(input_detail["index"], dummy)

print("[PROBE] invoke...")
interpreter.invoke()

output = interpreter.get_tensor(output_detail["index"])
print("[PROBE] OK, output shape:", output.shape)

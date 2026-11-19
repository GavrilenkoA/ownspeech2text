"""Writes fixtures/tiny_ctc_20s.onnx: a stand-in with the GigaAM CTC graph's interface for the JVM tests of
GigaAmCtcModel (front end -> 20 s window -> feature_lengths -> greedy CTC -> vocabulary -> text) without the 886 MB model.

  features [1, 64, 2000] float, feature_lengths [1] int64 -> log_probs [1, 500, 257]
  log_probs[t, k] = features[0, :, 4 t] . W[:, k] + b[k]: mel band m votes for token 2 + m, the blank (256) wins below
  -10 (silence is -20.7), every other token never wins. A tone at a band's centre frequency therefore decodes to that
  band's token for the frames it sounds in, as GigaAM's subsampling x4 would place it.

  python -I make_tiny_ctc.py <out.onnx>      (onnx from native/qnn-contexts/requirements.txt)
"""
import sys

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

FRAMES, MELS, CLASSES, BLANK = 2000, 64, 257, 256


def main() -> None:
    w = np.zeros((MELS, CLASSES), dtype=np.float32)
    for m in range(MELS):
        w[m, 2 + m] = 1.0
    b = np.full(CLASSES, -1000.0, dtype=np.float32)
    b[2:2 + MELS] = 0.0
    b[BLANK] = -10.0
    nodes = [
        helper.make_node("Slice", ["features", "starts", "ends", "axes", "steps"], ["sub"]),
        helper.make_node("Transpose", ["sub"], ["tr"], perm=[0, 2, 1]),
        helper.make_node("MatMul", ["tr", "W"], ["mm"]),
        helper.make_node("Add", ["mm", "b"], ["log_probs"]),
    ]
    inits = [numpy_helper.from_array(a, n) for n, a in [
        ("starts", np.array([0], np.int64)), ("ends", np.array([FRAMES], np.int64)), ("axes", np.array([2], np.int64)),
        ("steps", np.array([4], np.int64)), ("W", w), ("b", b)]]
    g = helper.make_graph(nodes, "tiny_ctc_20s",
                          [helper.make_tensor_value_info("features", TensorProto.FLOAT, [1, MELS, FRAMES]),
                           helper.make_tensor_value_info("feature_lengths", TensorProto.INT64, [1])],
                          [helper.make_tensor_value_info("log_probs", TensorProto.FLOAT, [1, FRAMES // 4, CLASSES])], inits)
    m = helper.make_model(g, opset_imports=[helper.make_opsetid("", 17)], producer_name="murmur-wp21")
    m.ir_version = 8
    onnx.checker.check_model(m)
    onnx.save(m, sys.argv[1])


if __name__ == "__main__":
    main()

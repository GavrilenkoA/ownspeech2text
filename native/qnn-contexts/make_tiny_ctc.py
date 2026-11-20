"""Writes fixtures/tiny_ctc_20s.onnx: a stand-in with the GigaAM CTC graph's interface for the JVM tests of
GigaAmCtcModel (front end -> 20 s window -> feature_lengths -> greedy CTC -> vocabulary -> text) without the 886 MB model.

  features [1, 64, 2000] float, feature_lengths [1] int64 -> log_probs [1, 500, 257]
  log_probs[t, k] = features[0, :, 4 t] . W[:, k] + b[k]: mel band m votes for token 2 + m, the blank (256) wins below
  -10 (silence is -20.7), every other token never wins. A tone at a band's centre frequency therefore decodes to that
  band's token for the frames it sounds in, as GigaAM's subsampling x4 would place it.

  The graph honours feature_lengths like the real one masks its padded frames, so that the caller's length handling is
  visible in the text: with L = (feature_lengths - 1) // 4 + 1 valid steps, step L - 1 is forced to a marker, the END
  token "X" for a part window (L < 500) and the FULL token "Q" for a full one, and every step >= L to the PAD token
  "Z". A caller that passes the real frame count and reads exactly L steps gets "<tones>X" for a part window and
  "<tones>Q" for a full one. Passing the padded length turns every marker into "Q" (whether or not the caller then reads
  all 500 steps: "X" lost or "Q" read); reading past L with the right length adds "Z".

  python -I make_tiny_ctc.py <out.onnx>      (onnx from native/qnn-contexts/requirements.txt)
"""
import sys

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

FRAMES, MELS, CLASSES, BLANK = 2000, 64, 257, 256
STEPS = FRAMES // 4
END, FULL, PAD = 241, 249, 240  # "X", "Q", "Z" in v3_e2e_ctc_vocab.txt
FORCE = 1.0e4


def main() -> None:
    w = np.zeros((MELS, CLASSES), dtype=np.float32)
    for m in range(MELS):
        w[m, 2 + m] = 1.0
    b = np.full(CLASSES, -1000.0, dtype=np.float32)
    b[2:2 + MELS] = 0.0
    b[BLANK] = -10.0
    end_row = np.zeros(CLASSES, np.float32)
    end_row[END] = FORCE
    full_minus_end = np.zeros(CLASSES, np.float32)
    full_minus_end[FULL] = FORCE
    full_minus_end[END] = -FORCE
    pad_row = np.zeros(CLASSES, np.float32)
    pad_row[PAD] = FORCE
    nodes = [
        helper.make_node("Slice", ["features", "starts", "ends", "axes", "steps"], ["sub"]),
        helper.make_node("Transpose", ["sub"], ["tr"], perm=[0, 2, 1]),
        helper.make_node("MatMul", ["tr", "W"], ["mm"]),
        helper.make_node("Add", ["mm", "b"], ["scores"]),
        # L = (len - 1) / 4 + 1 (lengths are >= 1), as float for the comparisons with the step index
        helper.make_node("Sub", ["feature_lengths", "one_i"], ["len_m1"]),
        helper.make_node("Div", ["len_m1", "four_i"], ["len_q"]),
        helper.make_node("Add", ["len_q", "one_i"], ["valid_i"]),
        helper.make_node("Cast", ["valid_i"], ["valid"], to=TensorProto.FLOAT),
        helper.make_node("Sub", ["valid", "one_f"], ["last"]),
        helper.make_node("Equal", ["t", "last"], ["is_end_b"]),
        helper.make_node("GreaterOrEqual", ["t", "valid"], ["is_pad_b"]),
        helper.make_node("Cast", ["is_end_b"], ["is_end"], to=TensorProto.FLOAT),
        helper.make_node("Cast", ["is_pad_b"], ["is_pad"], to=TensorProto.FLOAT),
        helper.make_node("Reshape", ["is_end", "col"], ["end_col"]),
        helper.make_node("Reshape", ["is_pad", "col"], ["pad_col"]),
        # the marker row: END for a part window, FULL when all STEPS are valid
        helper.make_node("GreaterOrEqual", ["valid", "steps_f"], ["is_full_b"]),
        helper.make_node("Cast", ["is_full_b"], ["is_full"], to=TensorProto.FLOAT),
        helper.make_node("Mul", ["is_full", "full_minus_end"], ["swap"]),
        helper.make_node("Add", ["end_row", "swap"], ["marker_row"]),
        helper.make_node("Mul", ["end_col", "marker_row"], ["end_add"]),
        helper.make_node("Mul", ["pad_col", "pad_row"], ["pad_add"]),
        helper.make_node("Add", ["scores", "end_add"], ["with_end"]),
        helper.make_node("Add", ["with_end", "pad_add"], ["log_probs"]),
    ]
    inits = [numpy_helper.from_array(a, n) for n, a in [
        ("starts", np.array([0], np.int64)), ("ends", np.array([FRAMES], np.int64)), ("axes", np.array([2], np.int64)),
        ("steps", np.array([4], np.int64)), ("W", w), ("b", b),
        ("one_i", np.array([1], np.int64)), ("four_i", np.array([4], np.int64)), ("one_f", np.array([1.0], np.float32)),
        ("t", np.arange(STEPS, dtype=np.float32)), ("col", np.array([1, STEPS, 1], np.int64)),
        ("steps_f", np.array([float(STEPS)], np.float32)), ("end_row", end_row), ("full_minus_end", full_minus_end),
        ("pad_row", pad_row)]]
    g = helper.make_graph(nodes, "tiny_ctc_20s",
                          [helper.make_tensor_value_info("features", TensorProto.FLOAT, [1, MELS, FRAMES]),
                           helper.make_tensor_value_info("feature_lengths", TensorProto.INT64, [1])],
                          [helper.make_tensor_value_info("log_probs", TensorProto.FLOAT, [1, STEPS, CLASSES])], inits)
    m = helper.make_model(g, opset_imports=[helper.make_opsetid("", 17)], producer_name="murmur-wp21")
    m.ir_version = 8
    onnx.checker.check_model(m)
    onnx.save(m, sys.argv[1])


if __name__ == "__main__":
    main()

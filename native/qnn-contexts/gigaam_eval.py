"""GigaAM v3 e2e CTC: WER of the 20 s QNN context (x86 HTP simulator) vs ORT CPU on the FLEURS ru clips.

  python -I gigaam_eval.py <fixtures/fleurs-ru> <gigaam fp32 onnx> <ctx dir> <out.json> [--cpu-only] [--clips a,b]

Features = gigaam_frontend.features (the Python reference of the Kotlin front end), padded to 2000 frames with
ln(1e-9) (the feature of digital silence: the 20 s window is filled as if the audio went on silent) and
feature_lengths = the real frame count. Decoding = greedy CTC over the first (frames - 1) // 4 + 1 output steps
(subsampling 4), blank = the last class, tokens from v3_e2e_ctc_vocab.txt ("▁" = word start).
Rows: cpu_exact (dynamic model, real length; what onnx-asr does), cpu_padded (fixed 2000-frame graph on the CPU EP),
qnn (the EPContext model on the simulator). WER/CER with the asr-ocr spike's normaliser (NFC, lowercase, ё->е,
'-' -> ' ', punctuation removed).
"""
from __future__ import annotations

import json
import re
import sys
import time
import unicodedata
from pathlib import Path

import numpy as np
import onnxruntime as ort

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gigaam_frontend as fe  # noqa: E402

FRAMES = 2000
PAD = float(np.log(fe.CLAMP_MIN))
_P = re.compile(r"[^\w\s]", re.U)


def norm(t: str) -> str:
    t = unicodedata.normalize("NFC", t or "").lower().replace("ё", "е").replace("-", " ")
    return " ".join(_P.sub(" ", t).split())


def edit(a: list, b: list) -> int:
    d = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        p, d[0] = d[0], i
        for j in range(1, len(b) + 1):
            p, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, p + (a[i - 1] != b[j - 1]))
    return d[len(b)]


def vocab(path: Path) -> list[str]:
    out = []
    for line in path.read_text(encoding="utf-8").splitlines():
        tok, idx = line.rsplit(" ", 1)
        assert int(idx) == len(out), line
        out.append(tok)
    return out


def ctc(logp: np.ndarray, steps: int, toks: list[str]) -> tuple[str, np.ndarray]:
    ids = logp[:steps].argmax(-1)
    blank = len(toks) - 1
    out, prev = [], -1
    for k in ids:
        if k != prev and k != blank:
            out.append(toks[k])
        prev = k
    # the app's rule (CtcGreedy): word starts become spaces, runs of spaces one space
    return re.sub(" {2,}", " ", "".join(out).replace("\u2581", " ").strip()), ids


def session(path: Path, qnn: bool) -> ort.InferenceSession:
    so = ort.SessionOptions()
    so.log_severity_level = 3
    if not qnn:
        return ort.InferenceSession(str(path), sess_options=so, providers=["CPUExecutionProvider"])
    import onnxruntime_qnn as q
    if not any(d.ep_name == "QNNExecutionProvider" for d in ort.get_ep_devices()):
        ort.register_execution_provider_library("QNNExecutionProvider", q.get_library_path())
    devs = [d for d in ort.get_ep_devices() if d.ep_name == "QNNExecutionProvider"]
    so.add_session_config_entry("session.disable_cpu_ep_fallback", "1")
    so.add_provider_for_devices(devs, {"backend_path": q.get_qnn_htp_path()})
    return ort.InferenceSession(str(path), sess_options=so)


def main() -> int:
    fix, model, ctx_dir, out_path = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]), Path(sys.argv[4])
    cpu_only = "--cpu-only" in sys.argv
    only = None
    if "--clips" in sys.argv:
        only = set(sys.argv[sys.argv.index("--clips") + 1].split(","))
    meta = json.loads((fix / "mel_ref.json").read_text(encoding="utf-8"))
    toks = vocab(model.parent / "v3_e2e_ctc_vocab.txt")
    fixed_cpu = ctx_dir.parent / "_work" / "gigaam_ctc_20s.onnx"
    rows = {"cpu_exact": session(model, False), "cpu_padded": session(fixed_cpu, False)}
    if not cpu_only:
        t = time.time()
        rows["qnn"] = session(ctx_dir / "gigaam_ctc_20s.onnx", True)
        print(json.dumps({"qnn_session_s": round(time.time() - t, 1)}), flush=True)
    res = {k: {"err": 0, "words": 0, "cerr": 0, "chars": 0, "run_s": 0.0} for k in rows}
    clips = []
    for c in meta["clips"]:
        if only and c["id"] not in only:
            continue
        x = fe.features(fe.read_wav(fix / c["wav"])).astype(np.float32)
        n = x.shape[1]
        assert n <= FRAMES, (c["id"], n)
        steps = (n - 1) // 4 + 1
        padded = np.full((1, 64, FRAMES), PAD, dtype=np.float32)
        padded[0, :, :n] = x
        row = {"id": c["id"], "frames": n}
        ids = {}
        for name, s in rows.items():
            feats = x[None] if name == "cpu_exact" else padded
            t = time.time()
            (logp,) = s.run(["log_probs"], {"features": feats, "feature_lengths": np.array([n], dtype=np.int64)})
            dt = time.time() - t
            text, ids[name] = ctc(logp[0], steps, toks)
            r, h = norm(c["ref"]).split(), norm(text).split()
            rc, hc = "".join(r), "".join(h)
            e, ce = edit(r, h), edit(list(rc), list(hc))
            res[name]["err"] += e; res[name]["words"] += len(r); res[name]["cerr"] += ce; res[name]["chars"] += len(rc)
            res[name]["run_s"] += dt
            row[name] = {"wer_err": e, "words": len(r), "run_s": round(dt, 2)}
            row[name]["text"] = text
        for name in rows:
            if name != "cpu_exact":
                row[name]["argmax_agree_vs_cpu_exact"] = float((ids[name] == ids["cpu_exact"]).mean())
                row[name]["text_equal_cpu_exact"] = row[name]["text"] == row["cpu_exact"]["text"]
        print(json.dumps({k: (v if not isinstance(v, dict) else {kk: vv for kk, vv in v.items() if kk != "text"})
                          for k, v in row.items()}, ensure_ascii=False), flush=True)
        clips.append(row)
    summary = {k: {"wer_pct": round(100 * v["err"] / max(1, v["words"]), 2), "cer_pct": round(100 * v["cerr"] / max(1, v["chars"]), 2),
                   "words": v["words"], "run_s": round(v["run_s"], 1)} for k, v in res.items()}
    out_path.write_text(json.dumps({"summary": summary, "clips": clips}, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(json.dumps(summary), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

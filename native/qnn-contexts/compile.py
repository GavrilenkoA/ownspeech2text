"""Host AOT compile of the ORT QNN plugin EP context models (EPContext + QNN context binary) for SM8750 / HTP v79.

Run through compile.sh (venv with onnxruntime-qnn 2.8.0 = QAIRT 2.52, onnxruntime 1.32.0). Python 3.12, -I.

  compile.py <out_dir> <target> [<target> ...]
      targets: ocr-det | ocr-rec | gigaam | all
      env: MURMUR_MODELS (shared model cache), GIGAAM_ONNX (the fp32 v3_e2e_ctc.onnx, pinned by compile.sh)

Every graph is compiled with the CPU EP fallback disabled, so a session exists only if 100 % of the graph is on the
HTP; the result is checked again (exactly one EPContext node, nothing else). fp16 precision, no quantisation
(asr-ocr.md 7.5 / 5.4). One fixed shape per graph:
  * detector: the resize rule of ml/ocr (long side 960, multiples of 32) gives 960x416 (cover) and 960x864 (inner),
    plus the two landscape rotations and 960x960 (any other window shape is padded into the smallest of these).
  * recogniser: one graph per ml/ocr bucket width (README 5.2: padding changes the output, so no bucket is padded
    into a wider one). One context binary per graph (52 MB for the ten): QNN HTP weight sharing (one 18 MB binary
    for all buckets) compiles, but the x86 HTP simulator segfaults loading such a context, so it is not verifiable here.
  * GigaAM v3 e2e CTC: features [1, 64, 2000] (20 s at hop 10 ms) + feature_lengths [1].
Output per artifact: <out>/<artifactId>/ (the files the app loads), for the OCR artifacts also <out>/det_contexts.zip /
rec_contexts.zip (stored, fixed timestamps: what the catalog pins), plus <out>/manifest.json (sizes, sha256, compile
seconds, versions; copied to native/qnn-contexts/manifest.json for core/catalog/tools/build_catalog.py). QNN compiles
are not bit-reproducible: a new compile needs a new manifest, catalog and upload.
"""
from __future__ import annotations

import ast
import hashlib
import json
import os
import re
import sys
import time
import zipfile
from pathlib import Path

import onnx
import onnxruntime as ort
import onnxruntime_qnn as qnn
from onnx import shape_inference

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]

SOC = "SM8750"
HTP_ARCH = "79"
DET_SHAPES = [(960, 416), (416, 960), (960, 864), (864, 960), (960, 960)]  # (h, w)
REC_HEIGHT = 48
GIGAAM_FRAMES = 2000

ART_DET = "pp-ocrv6-tiny-det@qnn-sm8750"
ART_REC = "pp-ocrv5-cyrillic-mobile-rec@qnn-sm8750"
ART_GIGAAM = "gigaam-v3-e2e-ctc@qnn-sm8750-20s"


def rec_buckets() -> list[int]:
    """The bucket widths of ml/ocr/config.py (read, never copied)."""
    src = (REPO / "ml/ocr/config.py").read_text(encoding="utf-8")
    m = re.search(r"^\s*rec_buckets\s*:[^=]*=\s*(\([^)]*\))", src, re.M)
    b = list(ast.literal_eval(m.group(1)))
    assert b == sorted(b) and b[0] == 320, b
    return b


def models_dir() -> Path:
    if os.environ.get("MURMUR_MODELS"):
        return Path(os.environ["MURMUR_MODELS"])
    git = REPO / ".git"
    main = Path(git.read_text().split(":", 1)[1].strip()).parents[2] if git.is_file() else REPO
    return main / ".cache/models"


def sha256(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fixed(src: Path, shapes: dict[str, list[int]], dst: Path) -> Path:
    """Copy of [src] with fixed input shapes and re-inferred output shapes (large models keep external data)."""
    m = onnx.load(str(src))
    for i in m.graph.input:
        if i.name in shapes:
            for d, v in zip(i.type.tensor_type.shape.dim, shapes[i.name]):
                d.Clear()
                d.dim_value = v
    for o in m.graph.output:
        for d in o.type.tensor_type.shape.dim:
            d.Clear()
    del m.graph.value_info[:]
    big = src.stat().st_size > 1_500_000_000 or m.ByteSize() > 1_500_000_000
    if not big:
        m = shape_inference.infer_shapes(m)
    dst.parent.mkdir(parents=True, exist_ok=True)
    if src.stat().st_size > 100_000_000:
        onnx.save(m, str(dst), save_as_external_data=True, location=dst.name + ".data", size_threshold=1024)
    else:
        onnx.save(m, str(dst))
    return dst


_devices = None


def qnn_devices():
    global _devices
    if _devices is None:
        ort.register_execution_provider_library(qnn.EP_NAME if hasattr(qnn, "EP_NAME") else "QNNExecutionProvider",
                                                qnn.get_library_path())
        _devices = [d for d in ort.get_ep_devices() if d.ep_name == "QNNExecutionProvider"]
        assert _devices, "no QNN EP device"
    return _devices


def compile_graph(src: Path, ctx: Path) -> float:
    """AOT compile [src] into [ctx] (+ its _qnn.bin)."""
    so = ort.SessionOptions()
    so.log_severity_level = 3
    so.add_session_config_entry("session.disable_cpu_ep_fallback", "1")
    so.add_session_config_entry("ep.context_enable", "1")
    so.add_session_config_entry("ep.context_embed_mode", "0")
    so.add_session_config_entry("ep.context_file_path", str(ctx))
    opts = {"backend_path": qnn.get_qnn_htp_path(), "soc_model": SOC, "htp_arch": HTP_ARCH,
            "enable_htp_fp16_precision": "1"}
    so.add_provider_for_devices(qnn_devices(), opts)
    t = time.time()
    ort.InferenceSession(str(src), sess_options=so)
    return round(time.time() - t, 1)


def check_ctx(ctx: Path) -> list[str]:
    """Exactly one EPContext node and nothing else (= the whole graph on the HTP); returns the referenced bins."""
    m = onnx.load(str(ctx), load_external_data=False)
    kinds = [n.op_type for n in m.graph.node]
    assert kinds == ["EPContext"], (ctx.name, kinds)
    bins = []
    for n in m.graph.node:
        for a in n.attribute:
            if a.name == "ep_cache_context":
                bins.append(a.s.decode())
    assert bins and all((ctx.parent / b).is_file() for b in bins), (ctx.name, bins)
    return bins


def files_of(d: Path) -> list[dict]:
    return [{"name": p.name, "bytes": p.stat().st_size, "sha256": sha256(p)} for p in sorted(d.iterdir()) if p.is_file()]


def pack(d: Path, name: str) -> dict:
    """<out>/<name>, stored (fp16 weights do not deflate), sorted entries, fixed timestamps: same files -> same bytes."""
    z = d.parent / name
    with zipfile.ZipFile(z, "w", compression=zipfile.ZIP_STORED) as zf:
        for p in sorted(d.iterdir()):
            info = zipfile.ZipInfo(p.name, date_time=(2026, 1, 1, 0, 0, 0))
            info.external_attr = 0o644 << 16
            with p.open("rb") as src, zf.open(info, "w", force_zip64=True) as dst:
                for chunk in iter(lambda: src.read(1 << 20), b""):
                    dst.write(chunk)
    return {"name": z.name, "bytes": z.stat().st_size, "sha256": sha256(z)}


def ocr_det(out: Path, work: Path) -> dict:
    src = models_dir() / "pp-ocrv6-tiny-det@onnx/det.onnx"
    d = out / ART_DET
    d.mkdir(parents=True, exist_ok=True)
    graphs, layout = [], {}
    for h, w in DET_SHAPES:
        name = f"det_h{h}_w{w}"
        f = fixed(src, {"x": [1, 3, h, w]}, work / f"{name}.onnx")
        s = compile_graph(f, d / f"{name}.onnx")
        bins = check_ctx(d / f"{name}.onnx")
        graphs.append({"graph": name, "input": [1, 3, h, w], "compile_s": s, "bins": bins})
        layout[f"ctx.h{h}w{w}"] = f"{name}.onnx"
        print(json.dumps(graphs[-1]), flush=True)
    return {"artifact": ART_DET, "source": {"file": str(src.relative_to(models_dir().parent.parent)), "sha256": sha256(src)},
            "graphs": graphs, "layout": layout, "files": files_of(d), "archive": pack(d, "det_contexts.zip")}


def ocr_rec(out: Path, work: Path) -> dict:
    mdir = models_dir() / "pp-ocrv5-cyrillic-mobile-rec@onnx"
    src = mdir / "rec.onnx"
    d = out / ART_REC
    d.mkdir(parents=True, exist_ok=True)
    (d / "rec.yml").write_bytes((mdir / "rec.yml").read_bytes())  # the dictionary travels with the contexts
    graphs, layout = [], {"dict": "rec.yml"}
    buckets = rec_buckets()
    for w in buckets:
        name = f"rec_w{w}"
        f = fixed(src, {"x": [1, 3, REC_HEIGHT, w]}, work / f"{name}.onnx")
        s = compile_graph(f, d / f"{name}.onnx")
        graphs.append({"graph": name, "input": [1, 3, REC_HEIGHT, w], "compile_s": s})
        layout[f"ctx.w{w}"] = f"{name}.onnx"
        print(json.dumps(graphs[-1]), flush=True)
    for g in graphs:
        g["bins"] = check_ctx(d / f"{g['graph']}.onnx")
    return {"artifact": ART_REC, "source": {"file": str(src.relative_to(models_dir().parent.parent)), "sha256": sha256(src)},
            "graphs": graphs, "layout": layout, "files": files_of(d), "archive": pack(d, "rec_contexts.zip")}


def gigaam(out: Path, work: Path) -> dict:
    src = Path(os.environ["GIGAAM_ONNX"])
    vocab = src.parent / "v3_e2e_ctc_vocab.txt"
    d = out / ART_GIGAAM
    d.mkdir(parents=True, exist_ok=True)
    (d / "vocab.txt").write_bytes(vocab.read_bytes())
    name = "gigaam_ctc_20s"
    f = fixed(src, {"features": [1, 64, GIGAAM_FRAMES], "feature_lengths": [1]}, work / f"{name}.onnx")
    s = compile_graph(f, d / f"{name}.onnx")
    bins = check_ctx(d / f"{name}.onnx")
    g = {"graph": name, "input": {"features": [1, 64, GIGAAM_FRAMES], "feature_lengths": [1]}, "compile_s": s, "bins": bins}
    print(json.dumps(g), flush=True)
    return {"artifact": ART_GIGAAM, "source": {"repo": "istupakov/gigaam-v3-onnx", "revision": os.environ.get("GIGAAM_REVISION"),
                                                 "file": src.name, "sha256": sha256(src)},
            "graphs": [g], "layout": {"model": f"{name}.onnx", "vocab": "vocab.txt"}, "files": files_of(d)}


def main() -> int:
    out = Path(sys.argv[1]).resolve()
    targets = sys.argv[2:] or ["all"]
    if "all" in targets:
        targets = ["ocr-det", "ocr-rec", "gigaam"]
    work = out / "_work"
    manifest_path = out / "manifest.json"
    manifest = json.loads(manifest_path.read_text()) if manifest_path.exists() else {"artifacts": {}}
    manifest["versions"] = {"onnxruntime": ort.__version__, "onnxruntime_qnn": qnn.build_and_package_info.__version__,
                            "qairt": qnn.build_and_package_info.qnn_version, "soc": SOC, "htp_arch": HTP_ARCH}
    for t in targets:
        t0 = time.time()
        r = {"ocr-det": ocr_det, "ocr-rec": ocr_rec, "gigaam": gigaam}[t](out, work)
        r["total_s"] = round(time.time() - t0, 1)
        manifest["artifacts"][r["artifact"]] = r
        manifest_path.write_text(json.dumps(manifest, indent=1) + "\n")
        print(f"{t}: {r['artifact']} {sum(f['bytes'] for f in r['files'])} B in {r['total_s']} s", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

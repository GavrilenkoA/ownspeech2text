"""progress.py: job counts, the plan-weighted work line and the --complete-waves export (audit, 2026-11-19)."""
import json
from pathlib import Path

from ml.roundtrip import progress

def _probe_ids(lang: str, n: int):
    """Real probe ids: progress resolves each clip's language from the probe files."""
    p = Path(progress.__file__).resolve().parent / "probe" / "seed.jsonl"
    rows = [json.loads(x) for x in p.read_text(encoding="utf-8").splitlines() if x.strip()]
    return [r["id"] for r in rows if r["lang"] == lang][:n]


def _setup(tmp_path: Path):
    out = tmp_path / "full"
    (out / "roundtrip").mkdir(parents=True)
    a, b = _probe_ids("en", 2)
    heavy, cheap = "whisper-medium@onnx-int8", "parakeet-tdt-0.6b-v3@onnx-int8"
    plan = [{"probe_id": a, "tts_system": "piper", "tts_voice": "v", "channel": "clean", "asr": [heavy, cheap], "wave": 0},
            {"probe_id": b, "tts_system": "piper", "tts_voice": "v", "channel": "clean", "asr": [heavy, cheap], "wave": 1}]
    (out / "plan.jsonl").write_text("".join(json.dumps(c) + "\n" for c in plan), encoding="utf-8")
    (out / "plan.summary.json").write_text(json.dumps({"estimate": {
        "asr_worker_s": {heavy: 40.0, cheap: 2.0}, "rows": {heavy: 2, cheap: 2}}}), encoding="utf-8")

    def row(pid, m):
        return {"probe_id": pid, "tts_system": "piper", "tts_voice": "v", "channel": "clean", "asr_model": m,
                "lang": "en", "hypothesis": "x"}
    # wave 0 complete, wave 1 only the cheap model (it ran ahead)
    rows = [row(a, heavy), row(a, cheap), row(b, cheap)]
    (out / "roundtrip" / "full.jsonl").write_text("".join(json.dumps(r) + "\n" for r in rows), encoding="utf-8")
    return out


def test_complete_waves_keeps_only_waves_with_every_planned_job(tmp_path, capsys):
    out = _setup(tmp_path)
    dest = tmp_path / "complete.jsonl"
    assert progress.main(["--out", str(out), "--complete-waves", str(dest)]) == 0
    got = [json.loads(x) for x in dest.read_text(encoding="utf-8").splitlines()]
    assert len(got) == 2 and {r["asr_model"] for r in got} == {"whisper-medium@onnx-int8", "parakeet-tdt-0.6b-v3@onnx-int8"}
    text = capsys.readouterr().out
    assert "jobs      3/4 done (75.0%)" in text
    # 3 of 4 jobs but only (20 + 1 + 1) of 42 planned worker-s: the work line does not overstate progress
    assert "work      52.4% of the plan's expected ASR worker-s done" in text

"""ml.train.select: ranking of a sweep and the overrides handed to the next phase."""
from __future__ import annotations

import json

from ml.train import select


def _run(tmp, name, em, nwer, fails, r, lr, params=1000):
    d = tmp / name
    (d / "eval/Q4_0-dev").mkdir(parents=True)
    gates = [{"status": "FAIL"}] * fails + [{"status": "PASS"}] * 3 + [{"status": "NO_DATA"}]
    (d / "eval/Q4_0-dev/report.json").write_text(json.dumps({
        "summary": {"overall": {"em": em, "nwer": nwer, "noop_exact": 0.9}},
        "gates": {"verdict": "FAIL" if fails else "INCOMPLETE", "scopes": {"overall": gates}}}))
    (d / "config.resolved.json").write_text(json.dumps({
        "method": "lora", "lora": {"r": r, "alpha": 2 * r, "dropout": 0.05}, "train": {"lr": lr, "epochs": 3}}))
    (d / "train_info.json").write_text(json.dumps({"params_trainable": params}))


def test_fewest_failed_gates_then_em(tmp_path, capsys):
    _run(tmp_path, "a", em=0.80, nwer=0.10, fails=2, r=16, lr=2e-4)
    _run(tmp_path, "b", em=0.70, nwer=0.12, fails=1, r=64, lr=2e-4)
    _run(tmp_path, "c", em=0.75, nwer=0.11, fails=1, r=16, lr=5e-4)
    _run(tmp_path, "missing-eval", em=0, nwer=0, fails=0, r=8, lr=1e-4)
    (tmp_path / "missing-eval/eval/Q4_0-dev/report.json").unlink()
    out = tmp_path / "sel.json"
    assert select.main(["a", "b", "c", "missing-eval", "--runs-dir", str(tmp_path), "--json", str(out)]) == 0
    first, second = capsys.readouterr().out.strip().splitlines()
    assert first == "c"
    assert second.split() == ["method=lora", "lora.r=16", "lora.alpha=32", "lora.dropout=0.05", "train.lr=0.0005",
                              "train.epochs=3"]
    assert [x["run"] for x in json.loads(out.read_text())["ranking"]] == ["c", "b", "a"]


def test_overrides_parse_back_with_config_loader():
    from ml.train import config
    cfg = config.apply_overrides({}, select.overrides({"method": "dora", "lora": {"r": 64, "alpha": 128},
                                                       "train": {"lr": 0.0002, "epochs": 5}}))
    assert cfg == {"method": "dora", "lora": {"r": 64, "alpha": 128}, "train": {"lr": 0.0002, "epochs": 5}}

"""ml/train/parity.py helpers (the model-backed check itself is exercised by the dry run, runbook section 4)."""
from ml.train.parity import common_prefix, pick


def test_pick_spreads_categories_deterministically():
    data = [{"id": f"{c}{i}", "category": c} for c in "abc" for i in range(5)]
    got = pick(data, 4)
    assert [e["category"] for e in got] == ["a", "b", "c", "a"] and got == pick(list(reversed(data)), 4)


def test_common_prefix():
    assert common_prefix("Sounds good.", "Sounds good!") == 11 and common_prefix("", "x") == 0

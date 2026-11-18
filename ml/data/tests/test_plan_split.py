from collections import Counter

from ml.data.synth import sampler as S
from ml.data.synth import terms as TT
from ml.data.synth.filters import dedup, leakage


def test_category_targets_scale_exactly():
    for n in (3000, 300, 97, 1):
        c = S.category_counts(n)
        assert sum(c.values()) == n


def test_plan_is_deterministic_and_counts_match():
    p1 = S.plan(300, 30, 30, seed=3)
    p2 = S.plan(300, 30, 30, seed=3)
    assert [s["sid"] for s in p1] == [s["sid"] for s in p2]
    cnt = Counter((s["split"], s["category"]) for s in p1)
    tg = S.targets(300, 30, 30)
    for split, cc in tg.items():
        for cat, c in cc.items():
            if cat == "context_counterpart":
                continue
            want = max(c, cc["context_counterpart"]) if cat == "context_resolution" else c
            assert cnt[(split, cat)] == want, (split, cat)
    assert len({s["sid"] for s in p1}) == len(p1)


def test_entity_pools_are_split_disjoint():
    tr = {t.text for t in TT.pool("train")}
    for s in ("dev", "test"):
        assert not tr & {t.text for t in TT.pool(s)}


def test_heldout_scenarios_never_speak_train_terms_or_people():
    p = S.plan(600, 200, 200, seed=1)
    train_terms = {t.text for t in TT.pool("train")}
    from ml.data.synth import lexicon as L
    train_people = {e.text for e in L.ALL if e.split == "train"}
    for sc in p:
        if sc["split"] == "train":
            # train screens never show held-out terms
            held = {t.text for t in TT.pool("dev")} | {t.text for t in TT.pool("test")}
            assert not held & set(sc["screen_terms"]) and not held & set(sc["distractors"])
            continue
        for t in sc["spoken_terms"]:
            assert t["text"] not in train_terms
        for person in sc["people"]:
            assert person not in train_people


def test_counterparts_planned_only_with_hard_terms():
    p = S.plan(3000, 0, 0, seed=1)
    cps = [s for s in p if s["counterpart"]]
    assert len(cps) == S.category_counts(3000)["context_counterpart"]
    assert all(s["category"] == "context_resolution" and not s["spoken_terms"][0]["well_known"] for s in cps)


def _rec(i, split, tgt, group, lang="ru"):
    return {"id": f"r{i}", "split": split, "lang": lang, "category": "plain", "transcript": tgt.lower(),
            "target": tgt, "meta": {"group": group, "entities": []}}


def test_dedup_prefers_heldout_and_respects_groups():
    a = _rec(1, "train", "Давай созвонимся завтра после обеда, мне так удобнее.", "g1")
    b = _rec(2, "test", "Давай созвонимся завтра после обеда, мне так удобнее!", "g2")
    c = _rec(3, "train", "Давай созвонимся завтра после обеда, мне так удобнее.", "g3")
    d = _rec(4, "train", "Совсем другой текст про релиз и тесты.", "g4")
    kept, dropped = dedup([a, b, c, d])
    ids = {r["id"] for r in kept}
    assert "r2" in ids and "r1" not in ids and "r3" not in ids and "r4" in ids
    # same group (context case + counterpart) survives together
    e = _rec(5, "train", "Я обновил клик хаус вчера вечером, всё работает.", "g9")
    f = _rec(6, "train", "Я обновил ClickHouse вчера вечером, всё работает.", "g9")
    kept, _ = dedup([e, f])
    assert len(kept) == 2


def test_leakage_report():
    r1 = {"split": "train", "category": "context_resolution", "meta": {"entities": ["Qdrant"], "group": "a"}}
    r2 = {"split": "test", "category": "context_resolution", "meta": {"entities": ["Qdrant"], "group": "b"}}
    lk = leakage([r1, r2])
    assert lk["entity_overlap_train_test"] == ["Qdrant"]

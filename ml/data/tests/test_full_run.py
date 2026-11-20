"""Full-run plumbing: ADR-070 privacy encoding, request order, resume across shards."""
from __future__ import annotations

import json

import pytest

from ml.common.render_bridge import render
from ml.data import generate as G
from ml.data import recheck as RC
from ml.data.synth import sampler as S
from ml.data.synth import screen as SCR
from ml.data.synth import teacher as TC
from ml.data.synth.stub_teacher import StubTeacher


def test_no_screen_level_is_the_shared_table_and_password_is_never_generated():
    table = render().NO_SCREEN_LEVEL
    for reason, _ in SCR.ABSENT_REASONS:
        assert SCR.no_screen_level(reason) == table[reason]
    assert "password_field" not in dict(SCR.ABSENT_REASONS)
    with pytest.raises(ValueError):
        SCR.no_screen_level("password_field")
    plan = S.plan(300, 30, 30, 1, overgen=1.15)
    reasons = {sc["screen_absent"] for sc in plan if sc["screen_absent"]}
    assert reasons and "password_field" not in reasons
    for sc in plan:
        if sc["screen_absent"]:
            ctx = SCR.absent_ctx(sc)
            assert ctx["privacy"] == {"level": table[sc["screen_absent"]], "reason": sc["screen_absent"]}
            assert "password" not in (ctx.get("field") or {})
            if sc["category"] == "continuation":
                assert ctx["privacy"]["level"] == "full"  # app_only would hide before_cursor


def test_recheck_rejects_a_stored_password_example_and_a_wrong_level():
    base = {"v": 2, "app": {"package": "com.whatsapp", "label": "WhatsApp", "category": "messaging"},
            "field": {"kind": "message", "before_cursor": ""}, "language": {"asr": "ru"}}
    rec = {"category": "plain", "meta": {}, "ctx": base | {"privacy": {"level": "app_only", "reason": "password_field"}}}
    assert RC.rule_errors(rec) == ["privacy:reason_not_generated:password_field"]
    rec["ctx"]["privacy"] = {"level": "full", "reason": "secure_window"}
    assert RC.rule_errors(rec) == ["privacy:level_mismatch:secure_window:full"]
    rec["ctx"]["privacy"] = {"level": "app_only", "reason": "secure_window"}
    assert RC.rule_errors(rec) == []


def test_interleave_keeps_every_job_and_covers_categories_first():
    plan = S.plan(300, 30, 30, 1, overgen=1.15)
    teach = [sc for sc in plan if sc["category"] not in S.CODE_ONLY]
    jobs = G.make_jobs(teach, 6)
    by_sid = {sc["sid"]: sc for sc in teach}
    il = G.interleave(jobs, by_sid)
    assert sorted(j["id"] for j in il) == sorted(j["id"] for j in jobs)
    groups = {(by_sid[j["sids"][0]]["category"], by_sid[j["sids"][0]]["lang"]) for j in jobs}
    first = {(by_sid[j["sids"][0]]["category"], by_sid[j["sids"][0]]["lang"]) for j in il[:len(groups)]}
    assert first == groups


def test_resume_never_re_requests_a_job_answered_in_another_shard(tmp_path):
    plan = S.plan(120, 0, 0, 1)
    teach = [sc for sc in plan if sc["category"] not in S.CODE_ONLY][:60]
    jobs = G.make_jobs(teach, 6)
    t1 = StubTeacher()
    st1 = TC.run_sharded(jobs[:4], t1, tmp_path, "gen", shard_size=2, concurrency=1)
    assert st1["requests_ok"] == 4
    st2 = TC.run_sharded(list(reversed(jobs)), StubTeacher(), tmp_path, "gen", shard_size=2, concurrency=1)
    assert st2["resumed"] == 4 and st2["requests_ok"] == len(jobs) - 4
    assert set(TC.load_results(tmp_path, "gen")) == {j["id"] for j in jobs}
    lines = [json.loads(x) for p in tmp_path.glob("gen-*.jsonl") for x in p.read_text().splitlines()]
    assert len(lines) == len(jobs)  # each job answered exactly once


def test_term_restore_needs_a_spelling_variant_or_the_screen():
    from ml.data.synth.build import term_restore_ok, unsupported_term_garbles
    ok = [("Docker", "докир"), ("Jira", "Джира"), ("DNS", "Ди эн эс"), ("DocuSign", "docusign"),
          ("Elasticsearch", "Elastic Search"), ("Confluence", "Confloence"), ("ChatGPT", "Chatgt")]
    ok += [("Zoom", "Zom"), ("Jira", "Jire"), ("JSON", "Джейсон")]
    bad = [("Traefik", "Traffic"), ("quantization", "Equalization"), ("AWS", "EWS"), ("RAG", "Raj"), ("API", "EPI"),
           ("PDF", "пэдэеи")]
    assert all(term_restore_ok(t, f) for t, f in ok)
    assert not any(term_restore_ok(t, f) for t, f in bad)
    ev = ["term:Traefik->Traffic:observed_style", "num:десяти->10"]
    tgt = "Я до этого проверю Traefik."
    assert unsupported_term_garbles(ev, tgt, {"screen": {"lines": []}}) == ev[:1]
    assert unsupported_term_garbles(ev, tgt, {"screen": {"lines": []}}, skip=("Traefik",)) == []
    assert unsupported_term_garbles(ev, "Я до этого проверю Traffic.", {"screen": {"lines": []}}) == []
    on_screen = {"screen": {"size": [1080, 2520], "lines": [{"text": "Traefik config", "conf": 0.9, "box": [0, 0, 9, 9]}]}}
    assert unsupported_term_garbles(ev, tgt, on_screen) == []


def test_english_speech_never_borrows_a_mixed_sentence_mangling():
    from ml.data.synth.build import _term_variants_mined

    class NM:
        _terms = {"m": {"decode": {"mixed": {"декод": 3}, "en": {"de code": 1}}}}

    class Ch:
        nm = NM()
    assert _term_variants_mined(Ch(), "m", "decode", "en") == [("de code", 1)]
    assert ("декод", 3) in _term_variants_mined(Ch(), "m", "decode", "ru")


def test_list_and_command_words_are_not_dropped_content():
    from ml.data.synth import filters as F
    from ml.common import text as T
    spoken = "Во-первых, начни без меня. Во-вторых, скинь ссылку. В-третьих, оставь вопросы на конец."
    tgt = "1. Начни без меня.\n2. Скинь ссылку.\n3. Оставь вопросы на конец."
    tw = T.norm_words(tgt)
    tg = F._parts(tw)
    left = [w for w in F._parts(T.content_words(spoken)) if "-" not in w and not F._matches(w, tg)
            and w not in F.ORDINALS and w != "в"]
    assert left == []
    assert {"question", "exclamation"} <= F.CMD_WORDS


def test_a_later_reserve_layer_never_changes_answered_batches():
    import argparse
    a = argparse.Namespace(n=300, dev=30, test=30, seed=1)
    one = G.reserve_plan(a, G.parse_reserve("train/near_miss=7,dev/injection=2"))
    two = G.reserve_plan(a, G.parse_reserve("train/near_miss=7,dev/injection=2;train/near_miss=3,train/address=2"))
    l1 = [sc for sc in two if sc["reserve"] == 1]
    assert [sc["sid"] for sc in l1] == [sc["sid"] for sc in one]
    assert [j["id"] for j in G.make_jobs(l1, 6)] == [j["id"] for j in G.make_jobs(one, 6)]
    sids = [sc["sid"] for sc in two]
    assert len(sids) == len(set(sids)) == 13  # dev/injection has 1 scenario at this size: a cell caps the layer
    plan_sids = {sc["sid"] for sc in S.plan(300, 30, 30, 1, overgen=1.15)}
    assert not plan_sids & set(sids)
    assert all(sc["index"] >= G.RESERVE_INDEX_OFFSET for sc in two)


def test_selection_prefers_parents_whose_counterpart_survived():
    def rec(rid, cat, sid, cp_of=None):
        m = {"scenario": sid}
        if cp_of:
            m["counterpart_of"] = cp_of
        return {"id": rid, "split": "train", "category": cat, "meta": m}
    by_sid = {f"s{i}": {"index": i} for i in range(5)}
    recs = [rec("a", "context_resolution", "s0"), rec("b", "context_resolution", "s1"),
            rec("c", "context_resolution", "s2"), rec("c-cp", "context_counterpart", "s2", "c"),
            rec("b-cp", "context_counterpart", "s1", "b")]
    final, short = G.select_exact(recs, {"train": {"context_resolution": 2, "context_counterpart": 2}}, by_sid)
    assert sorted(r["id"] for r in final) == ["b", "b-cp", "c", "c-cp"] and short == {}
    final, short = G.select_exact(recs, {"train": {"context_resolution": 1, "context_counterpart": 2}}, by_sid)
    assert sorted(r["id"] for r in final) == ["b", "b-cp"]
    assert short == {"train/context_counterpart": {"target": 2, "got": 1}}


def test_near_no_op_always_has_a_defect():
    import random
    from ml.data.synth.build import _near_no_op
    for t in ("где купить свежую зелень рядом с домом", "Можно я возьму отпуск?", "ok, thanks", "Hi there"):
        for seed in range(5):
            hyp, op = _near_no_op(t, random.Random(seed))
            assert hyp != t, (t, op)


def test_prompt_echo_is_capped_per_split():
    by_sid = {f"s{i}": {"index": i} for i in range(6)}
    recs = [{"id": f"r{i}", "split": "train", "category": "formatting_commands",
             "meta": {"scenario": f"s{i}", "spoken": "Точка сбора у входа точка"}} for i in range(5)]
    recs.append({"id": "x", "split": "train", "category": "plain", "meta": {"scenario": "s5", "spoken": "точка сбора"}})
    keep, drop = G.cap_prompt_echo(recs, by_sid)
    assert [r["id"] for r in drop] == ["r3", "r4"] and len(keep) == 4


def test_a_noise_transcript_never_crosses_splits():
    from ml.data.synth import filters as F

    def rec(i, split):
        return {"id": f"n{i}", "split": split, "category": "noise_only", "lang": "en", "transcript": "Thank you for watching.",
                "target": "", "meta": {"group": f"g{i}"}}
    kept, dropped = F.dedup([rec(0, "train"), rec(1, "dev"), rec(2, "dev"), rec(3, "train")])
    assert [r["id"] for r in kept] == ["n1", "n2"] and len(dropped) == 2

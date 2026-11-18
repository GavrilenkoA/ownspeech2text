"""Training-format tests: chat-template parity with the official HF templates, loss masking,
deterministic prompt mixing. Tokenizer tests need local tokenizer dirs (skipped otherwise):
  MURMUR_TOK_QWEN (default .cache/ml/train/models/Qwen3.5-0.8B)
  MURMUR_TOK_LFM3 (default .cache/ml/train/models/LFM3-1.7B-tok)
"""
from __future__ import annotations

import os
from pathlib import Path

import pytest

from ml.eval import contract
from ml.train import config as cfglib
from ml.train.render_data import IGNORE, PromptMix, choose_prompt, expand, features

REPO = contract.REPO
TOKS = {
    "qwen3.5": Path(os.environ.get("MURMUR_TOK_QWEN", REPO / ".cache/ml/train/models/Qwen3.5-0.8B")),
    "lfm3": Path(os.environ.get("MURMUR_TOK_LFM3", REPO / ".cache/ml/train/models/LFM3-1.7B-tok")),
}
SMOKE = [__import__("ml.eval.smoke_to_contract", fromlist=["convert"]).convert(__import__("json").loads(l), "train")
         for l in (REPO / "prompts/eval/smoke_cases.v1.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]


def tok_for(tpl):
    if not (TOKS[tpl] / "tokenizer.json").exists():
        pytest.skip(f"no tokenizer at {TOKS[tpl]}")
    from transformers import AutoTokenizer
    return AutoTokenizer.from_pretrained(TOKS[tpl])


@pytest.mark.parametrize("tpl", ["qwen3.5", "lfm3"])
@pytest.mark.parametrize("system", ["finetuned", "default"])
def test_prompt_plus_target_equals_official_template(tpl, system):
    """render.chat + target + <|im_end|> must equal the vendor template's rendering of the same
    conversation with thinking disabled (up to the template's trailing newline)."""
    tok = tok_for(tpl)
    for ex in SMOKE[:6] + [s for s in SMOKE if not s["target"]]:
        prompt = contract.render_prompt(ex, system, tpl, "Use British spelling.")
        sysmsg = contract.render.system_prompt(system, "Use British spelling.")
        user = contract.render.user_message(ex["ctx"], ex["transcript"])
        official = tok.apply_chat_template(
            [{"role": "system", "content": sysmsg}, {"role": "user", "content": user},
             {"role": "assistant", "content": ex["target"]}], tokenize=False, enable_thinking=False)
        assert official == prompt + contract.completion_text(ex, tpl) + "\n", ex["id"]
        gen = tok.apply_chat_template([{"role": "system", "content": sysmsg}, {"role": "user", "content": user}],
                                      tokenize=False, add_generation_prompt=True, enable_thinking=False)
        assert gen == prompt, ex["id"]


@pytest.mark.parametrize("tpl", ["qwen3.5", "lfm3"])
def test_loss_mask_and_eos(tpl):
    tok = tok_for(tpl)
    eos = tok.convert_tokens_to_ids("<|im_end|>")
    for ex in SMOKE:
        f = features(ex, tok, tpl, "finetuned", None, eos)
        assert len(f["input_ids"]) == len(f["labels"])
        n_p = f["n_prompt"]
        assert all(l == IGNORE for l in f["labels"][:n_p])
        assert f["labels"][n_p:] == f["input_ids"][n_p:]
        assert f["labels"][-1] == eos
        assert tok.decode(f["input_ids"][n_p:-1]) == ex["target"], ex["id"]
        if not ex["target"]:
            assert f["n_completion"] == 1  # empty target trains "emit <|im_end|> immediately"
        # no automatic BOS/EOS duplicates: LFM3 BOS comes from the template text only
        if tpl == "lfm3":
            assert f["input_ids"].count(tok.convert_tokens_to_ids("<|startoftext|>")) == 1


def test_prompt_mix_deterministic_and_prefs_win():
    mix = PromptMix(default_share=0.5, preferences_share=0.5,
                    preferences_pool=str(REPO / "ml/train/preferences_pool.v1.txt"), seed=7)
    pool = mix.pool()
    assert len(pool) >= 8 and all(not p.startswith("#") for p in pool)
    a = [choose_prompt(e, mix, pool) for e in SMOKE]
    b = [choose_prompt(e, mix, pool) for e in SMOKE]
    assert a == b
    assert {s for s, _ in a} == {"default.v4s", "finetuned.v2"}
    e = dict(SMOKE[0], meta={**SMOKE[0]["meta"], "preferences": "Use British spelling."})
    assert all(choose_prompt(dict(e, id=f"x{i}"), mix, pool)[1] == "Use British spelling." for i in range(20))


def test_expand_weights():
    rows = [dict(SMOKE[0], id=f"r{i}") for i in range(1000)]
    assert len(expand(rows, 2.0, {}, 0)) == 2000
    half = len(expand(rows, 0.5, {}, 0))
    assert 430 < half < 570
    assert len(expand(rows, 1.0, {SMOKE[0]["category"]: 3.0}, 0)) == 3000


def test_configs_resolve():
    for p in sorted((REPO / "ml/train/configs").glob("*.yaml")):
        c = cfglib.resolve(p, ["train.max_steps=3"])
        assert c["method"] in ("lora", "dora", "full"), p
        assert c["train"]["max_steps"] == 3
        assert c["prompt"]["system"] == "finetuned.v2" and c["prompt"]["default_system"] == "default.v4s"
        assert "${" not in str(c), p


SMOKE_V2 = [__import__("ml.eval.smoke_to_contract", fromlist=["convert"]).convert(__import__("json").loads(l), "train")
            for l in (REPO / "prompts/eval/smoke_cases.v2.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
PROFILES = ["finetuned", "finetuned.v2", "default"] + sorted(p.stem for p in (contract.PROMPTS / "profiles").glob("*.json"))


@pytest.mark.parametrize("tpl", ["qwen3.5", "lfm3"])
@pytest.mark.parametrize("profile", PROFILES)
def test_contract_render_equals_render_py_profiles(tpl, profile):
    """contract.render_prompt (training + eval) == prompts/render.py render_prompt (the app's spec), byte
    for byte, for bare system prompt names and EVERY profile in prompts/profiles, on v1 and v2 contexts."""
    for ex in SMOKE[:3] + [e for e in SMOKE_V2 if e["ctx"].get("screen")][:3]:
        for prefs in (None, "Use British spelling."):
            assert contract.render_prompt(ex, profile, tpl, prefs) == contract.render.render_prompt(
                profile, ex["ctx"], ex["transcript"], tpl, prefs), (profile, ex["id"])


def test_contract_render_passes_profile_ctx2(monkeypatch):
    """A profile with non-empty ctx2 must change the request exactly as render.render_prompt does."""
    prof = dict(contract.render.load_profile("default.v4s"), ctx2={"budget": 200, "order": "reading", "terms": True})
    real = contract.render.load_profile
    monkeypatch.setattr(contract.render, "load_profile", lambda n: prof if n == "x-ctx2" else real(n))
    ex = next(e for e in SMOKE_V2 if e["ctx"].get("screen"))
    mine = contract.render_prompt(ex, "x-ctx2", "qwen3.5")
    assert mine == contract.render.render_prompt(prof, ex["ctx"], ex["transcript"], "qwen3.5")
    assert mine != contract.render_prompt(ex, "default.v4s", "qwen3.5")  # ctx2 really took effect
    sysmsg, turns, user = contract.render_messages(ex, "x-ctx2")
    assert user in mine and turns == contract.render.profile_turns(prof)


def test_resolve_max_len():
    from ml.train.render_data import resolve_max_len
    assert resolve_max_len(2048, [10]) == 2048 and resolve_max_len("1536", [10]) == 1536
    assert resolve_max_len("auto", [100, 1267]) == 1280
    assert resolve_max_len("auto:1024", [100, 1267]) == 1024
    with pytest.raises(ValueError):
        resolve_max_len("big", [1])


def test_build_auto_max_len_from_rendered_examples(tmp_path):
    from ml.train.render_data import build
    tok = tok_for("qwen3.5")
    p = tmp_path / "d.jsonl"
    contract.write_jsonl(p, SMOKE)
    feats, info = build([{"path": str(p)}], "train", tok, "qwen3.5", PromptMix(), "auto")
    longest = max(len(f["input_ids"]) for f in feats)
    assert info["dropped_too_long"] == 0 and info["max_len"] >= longest and info["max_len"] % 64 == 0
    assert info["rendered_len"]["max"] == longest
    _, capped = build([{"path": str(p)}], "train", tok, "qwen3.5", PromptMix(), f"auto:{longest - 1}")
    assert capped["dropped_too_long"] >= 1

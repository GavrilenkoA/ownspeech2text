"""Scenario + teacher example -> contract record(s) (PLAN_V2 stage C, steps 3-5).

  ctx         CONTEXT_V2: teacher screen content -> render spec (injection placed in it) -> ml.screens.build_ctx
              (render -> real OCR); category rules checked on the OCR lines and on the rendered <context>
              block; one re-render with another theme/font scale, then OcrReject. No-screen / no-field cases
              come from the scenario (accessibility side).
  transcript  spoken -> [garble the hard term / misspell the surname] -> disfluencies -> ASR channel
              (mined noise.py or builtin) -> [hallucinated tail]; retried with new seeds until the
              intended text is still recoverable from transcript + context
  target      the teacher's target; for a no-context counterpart the hard term is replaced by the
              transcript's garbled form (the model must not invent a term the screen does not support)

Raises Reject(reason) when an example cannot be built faithfully.
"""
from __future__ import annotations

import copy
import random
import re

from ml.common import text as T
from ml.common.render_bridge import render
from ml.common.util import rng as mkrng, stable_id
from ml.data.synth import asr_noise as AN
from ml.data.synth import disfluency as D
from ml.data.synth import screen as SCR
from ml.data.synth.channel import Channel
from ml.data.synth.teacher_prompts import GEN_VERSION

NOISE_ONLY = {
    "ru": ["э", "э-э", "эм", "м-м", "ээ… м-м", "э-э, ну", "ну э", "Субтитры сделал DimaTorzok", "Субтитры создавал DimaTorzok",
           "Редактор субтитров А.Синецкая Корректор А.Егорова", "Продолжение следует...", "…", "э-э-э"],
    "en": ["uh", "um", "hmm", "uh, um", "um…", "er", "mm-hmm… uh", "Subtitles by the Amara.org community", "…",
           "uh uh"],
}
TAILS = {"ru": [t for t in AN.WHISPER_TAILS["ru"]], "en": [t for t in AN.WHISPER_TAILS["en"] if t != "Thank you."]}
INJECTION_FORBID = {
    "OK": ["OK", "ОК"], "APPROVED": ["APPROVED"], "принято": ["Принято"], "Sent from my iPhone": ["Sent from my iPhone"],
    "Отправлено с Android": ["Отправлено с Android"], "I agree to the terms": ["I agree to the terms"],
    "Согласен с условиями": ["Согласен с условиями"], "bit.ly": ["bit.ly"], "t.me/bonus_promo": ["t.me/bonus_promo"],
    "YES": ["YES"], "заказ отменён": ["заказ отменён"],
}
NOANSWER = {"dictated_question", "dictated_command", "ai_addressed"}


class Reject(Exception):
    pass


def fold(s: str) -> str:
    return re.sub(r"[\s\-_.]+", " ", T.nfc(s).lower().replace("ё", "е")).strip()


def _contains(hay: str, needle: str) -> bool:
    n = fold(needle)
    return bool(n) and re.search(r"(?<!\w)" + re.escape(n), fold(hay)) is not None


# ------------------------------------------------------------------ context (v2: render -> OCR)
class OcrReject(Reject):
    """A category rule failed on the OCR output even after the one allowed re-render."""

    def __init__(self, rule: str, term: str | None):
        super().__init__("ocr:" + rule)
        self.rule, self.term = rule, term


def rendered_block(ctx: dict) -> str | None:
    """The <context> block of the CURRENT prompts/render.py, or None while it does not render ctx v2 screens."""
    return render().render_context(ctx) if SCR.renderer_shows_screen() else None


def ctx_text(ctx: dict) -> str:
    """Everything the model can see about the screen (for the recoverability check)."""
    blk = rendered_block(ctx)
    return blk if blk is not None else "\n".join(SCR.lines_of(ctx)) + "\n" + ((ctx.get("field") or {}).get("before_cursor") or "")


def rule_failure(sc: dict, ctx: dict, target: str) -> tuple[str, str | None] | None:
    """CONTEXT_V2 "Category rules checked on OCR output": on ctx.screen.lines AND on the rendered <context> block
    (the renderer trims: a needed line that was trimmed away teaches the model to invent). -> (rule, term) | None."""
    cat = sc["category"]
    blk = rendered_block(ctx)

    def shown(s: str) -> bool:
        return SCR.on_screen(ctx, s) and (blk is None or SCR.exact_in_text(blk, s))

    def hidden(s: str) -> bool:  # neither s nor a close variant anywhere
        return not SCR.close_on_screen(ctx, s) and (blk is None or not SCR.close_in_text(blk, s))

    if cat in SCR_NEEDED and not ctx.get("screen"):
        return "no_screen_lines", None
    if cat == "context_resolution":
        t = sc["spoken_terms"][0]["text"]
        if not shown(t):
            return "term_not_readable", t
    elif cat == "context_names":
        for p in sc["people"]:
            if not shown(p):
                return "name_not_readable", p
    elif cat == "near_miss":
        said = [t["text"] for t in sc["spoken_terms"]] or [p.split()[-1] for p in sc["people"]]
        for d in sc["distractors"]:
            if not shown(d):
                return "distractor_not_readable", d
        for x in said:
            hay = " ".join(SCR.lines_of(ctx)) + " " + (blk or "")
            if re.search(r"(?<!\w)" + re.escape(SCR._cf(x)) + r"(?!\w)", SCR._cf(hay)):
                return "said_item_on_screen", x
    elif cat == "injection":
        inj = sc["injection"]["text"]
        if not SCR.fuzzy_on_screen(ctx, inj) or (blk is not None and not SCR.fuzzy_on_screen(
                {"screen": {"lines": [{"text": blk}]}}, inj)):
            return "injection_not_visible", inj[:40]
    return None


SCR_NEEDED = {"context_resolution", "context_names", "near_miss", "injection"}


def make_ctx(sc: dict, content: dict, backend: SCR.Backend, target: str, check: bool = True):
    """-> (ctx, screen_meta). Real render + OCR; on a rule failure ONE re-render (other theme/font), then OcrReject."""
    bc = (content.get("before_cursor") or "").strip()
    if sc.get("screen_absent"):
        return SCR.absent_ctx(sc, bc), {"absent": sc["screen_absent"]}
    fail = None
    for variant in (0, 1):
        spec = SCR.make_spec(sc, content, variant)
        ctx = SCR.finalize(backend.build_ctx(spec), sc)
        if sc["category"] == "continuation" and bc and "field" in ctx:
            ctx["field"]["before_cursor"] = bc  # accessibility gives the field text exactly
        fail = rule_failure(sc, ctx, target) if check else None
        if not fail:
            return ctx, {"spec": spec, "variant": variant, "backend": backend.version, "render": backend.last_meta,
                         "render_check":
                         "render.py" if SCR.renderer_shows_screen() else "skipped:render.py_has_no_v2_screen"}
    raise OcrReject(*fail)


def counterpart_ctx(sc: dict, spec: dict, term: str, backend: SCR.Backend):
    """Re-render the paired screen without the supporting item(s) and run OCR again (lines are never edited)."""
    try:
        base = SCR.strip_spec(spec, term)
    except ValueError as e:
        raise Reject(str(e))
    for variant in (0, 1):
        sp = base if variant == 0 else base | {"theme": "dark" if base["theme"] == "light" else "light",
                                               "seed": (base["seed"] * 7 + 1) % (1 << 30)}
        ctx = SCR.finalize(backend.build_ctx(sp), sc)
        blk = rendered_block(ctx)
        if not SCR.close_on_screen(ctx, term) and (blk is None or not SCR.close_in_text(blk, term)):
            return ctx, {"spec": sp, "variant": variant, "backend": backend.version, "render": backend.last_meta,
                         "stripped": term}
    raise OcrReject("counterpart_term_still_visible", term)


# ------------------------------------------------------------------ garbling
def _term_variants_mined(channel: Channel, model: str, term: str, lang: str) -> list[tuple[str, int]]:
    if channel.nm is None:
        return []
    by_lang = channel.nm._terms.get(model, {}).get(term) or {}
    out = []
    for lg, cnt in by_lang.items():
        if lg == lang or lang == "mixed" or lg == "mixed":
            out += [(h, c) for h, c in cnt.items() if h and h.lower() != term.lower()]
    return out


def garble_form(term: dict, lang: str, channel: Channel, model: str, prev_word: str, r: random.Random) -> tuple[str, str]:
    """How the recogniser spells a hard term it does not know. -> (form, source)."""
    mined = _term_variants_mined(channel, model, term["text"], lang)
    if mined and r.random() < 0.8:
        forms, w = zip(*mined)
        return r.choices(forms, weights=w)[0], "mined"
    if lang in ("ru", "mixed"):
        if term.get("ru_spoken"):
            form = r.choice(term["ru_spoken"])
            src = "lexicon"
        else:
            form = " ".join(T.lat2cyr_rough(w) for w in T.words(term["text"]))
            src = "translit"
        return AN._case_ending(prev_word, form, r), src
    if term.get("en_spoken") and r.random() < 0.6:
        form = r.choice(term["en_spoken"])
        if form.lower() != term["text"].lower():
            return form, "lexicon"
    parts = re.findall(r"[A-Z]?[a-z]+|[A-Z]+(?![a-z])|\d+", term["text"])
    if len(parts) > 1 and r.random() < 0.5:
        return " ".join(p.lower() for p in parts), "split"
    form = AN.respell_en(term["text"], r).lower()
    if form.lower() == term["text"].lower():
        form = " ".join(p.lower() for p in parts) if len(parts) > 1 else ""
    return form, "respell"


_SPOKEN_SYM = {"dot": ".", "точка": ".", "slash": "/", "слэш": "/", "слеш": "/", "dash": "-", "дефис": "-",
              "underscore": "_", "плюс": "+", "plus": "+"}


def term_key(s: str) -> str:
    """'Android Manifest dot XML' and 'AndroidManifest.xml' get the same key."""
    ws = [_SPOKEN_SYM.get(w.lower(), w.lower()) for w in re.findall(r"[\w+#]+|[./]", T.nfc(s))]
    return "".join(ws).replace("ё", "е").replace("-", "").replace("_", "")


def find_term(spoken: str, term: str) -> list[tuple[int, int]]:
    """Character spans of `term` in `spoken`: exact (case-insensitive) or a spoken spelling of it."""
    pat = re.compile(r"(?<![\w-])" + re.escape(term) + r"(?![\w])", re.I)
    spans = [(m.start(), m.end()) for m in pat.finditer(spoken)]
    if spans:
        return spans
    key = term_key(term)
    toks = [(m.start(), m.end()) for m in re.finditer(r"\S+", spoken)]
    for i in range(len(toks)):
        for j in range(i + 1, min(len(toks), i + 6) + 1):
            a, b = toks[i][0], toks[j - 1][1]
            chunk = spoken[a:b].rstrip(",.!?;:»)\"'")
            if term_key(chunk) == key:
                return [(a, a + len(chunk))]
    return []


def plausible_garble(form: str, term: str) -> bool:
    """A garble must still sound like the whole term: 'клик хаус' for ClickHouse yes, 'пуш' for
    'push notification' or 'кубер' for Kubernetes no (restoring those would teach the model to add words)."""
    if re.fullmatch(r"[A-Z0-9/]{2,6}", term):  # acronyms are spelled letter by letter: 'си ви и' for CVE
        return len(form.split()) >= len(re.sub(r"\W", "", term)) - 1 or len(form) >= 3
    kf, kt = T.phonetic_key(form), T.phonetic_key(term)
    return len(kf) >= 0.6 * len(kt) and T.similarity(kf, kt) >= 0.35


def _replace_term(spoken: str, term: str, form_fn) -> tuple[str, int]:
    spans = find_term(spoken, term)
    out, last = [], 0
    for a, b in spans:
        prev = spoken[:a].split()[-1:] or [""]
        out += [spoken[last:a], form_fn(re.sub(r"\W", "", prev[0]))]
        last = b
    out.append(spoken[last:])
    return "".join(out), len(spans)


def misspell_surname(spoken: str, person: str, r: random.Random) -> tuple[str, str | None, str | None]:
    sur = person.split()[-1]
    stem = T.norm_word(sur)[: max(4, len(sur) - 2)]
    toks = spoken.split()
    for i, tok in enumerate(toks):
        core = re.sub(r"^\W+|\W+$", "", tok)
        if core and T.norm_word(core).startswith(stem):
            for _ in range(6):
                new = AN.respell_ru(core, r) if T.is_cyr(core) else AN.respell_en(core, r)
                if new != core and fold(new) != fold(core):
                    toks[i] = tok.replace(core, new)
                    return " ".join(toks), core, new
            return spoken, None, None
    return spoken, None, None


# ------------------------------------------------------------------ recoverability
def unrecoverable(target: str, hyp: str, ctx_text: str, allow: set[str] = frozenset()) -> list[str]:
    hw = T.norm_words(hyp)
    hs = set(hw)
    joined = {a + b for a, b in zip(hw, hw[1:])} | {a + "-" + b for a, b in zip(hw, hw[1:])}
    cs = set(T.norm_words(ctx_text))
    hnums = set(T.numbers_in(hyp))
    tw = sorted(set(T.norm_words(target)))
    free = hs - set(tw)
    bad = []
    for w in T.content_words(target):
        nums = T.numbers_in(w)
        if nums and set(nums) <= hnums:  # '15:30' <- 'пятнадцать тридцать'; 'первом' needs a 1 in the hypothesis
            continue
        if w in hs or w in cs or w in joined or w in allow:
            continue
        # a hypothesis word that is itself an exact target word cannot vouch for another one
        # ('Saturday' must not make a garbled 'Sunday' look recoverable)
        # and a garbled word vouches only for the target word it is closest to ('Seturday' is Saturday's)
        if any((T.similarity(w, h) >= 0.6 or T.phon_sim(w, h) >= 0.75) and _closest(h, tw) == w for h in free):
            continue
        bad.append(w)
    return bad


def _closest(h: str, words: list[str]) -> str:
    return max(words, key=lambda t: (max(T.similarity(t, h), T.phon_sim(t, h)), t)) if words else ""


def implausible_subs(events: list[str]) -> list[str]:
    """Substitutions that no recogniser makes from the audio ('Sunday' -> 'Utxpyri', 'по' -> 'свенство'): a long
    replacement that sounds nothing like the word. Mined confusion tables contain a few such alignment artefacts;
    an example built on them teaches the model to guess, so the attempt is retried with another seed."""
    out = []
    for e in events:
        if e.startswith("sub:") and "->" in e:
            a, b = e[4:].split("->", 1)
            if len(b) >= 4 and T.phon_sim(a, b) < 0.3:
                out.append(e)
    return out


# ------------------------------------------------------------------ records
def _near_no_op(target: str, r: random.Random) -> tuple[str, str]:
    opts = []
    if "," in target:
        opts.append("drop_comma")
    if target[:1].isupper():
        opts.append("lower_first")
    if re.search(r"[.!?]$", target):
        opts.append("drop_final")
    op = r.choice(opts or ["lower_first"])
    if op == "drop_comma":
        idx = [m.start() for m in re.finditer(",", target)]
        i = r.choice(idx)
        return target[:i] + target[i + 1:], op
    if op == "drop_final":
        return target[:-1], op
    return target[:1].lower() + target[1:], op


def _record(sc: dict, rid: str, category: str, ctx: dict, transcript: str, target: str, meta: dict) -> dict:
    return {"id": rid, "lang": sc["lang"], "category": category, "source": "sim", "split": sc["split"],
            "ctx": ctx, "transcript": transcript, "target": target, "meta": meta}


def _base_meta(sc: dict, teacher: str, gen: str | None = None) -> dict:
    return {"scenario": sc["sid"], "group": sc["group"], "teacher": teacher, "gen": gen or GEN_VERSION,
            "app_category": sc["app"]["category"], "persona": sc["persona"], "topic": sc.get("topic"),
            "privacy": sc["privacy"], "mix": sc["mix"]}


def build_noise_only(sc: dict, channel: Channel, backend: SCR.Backend) -> list[dict]:
    r = mkrng("noise_only", sc["sid"])
    lg = "en" if sc["lang"] == "en" else "ru"
    hyp = r.choice(NOISE_ONLY[lg])
    ctx, smeta = make_ctx(sc, {"title": "", "messages": [], "before_cursor": "", "snippets": []}, backend, "")
    meta = _base_meta(sc, "code") | {"noise": {"source": "code:noise_only"}, "check": {"empty": True}, "screen": smeta}
    return [_record(sc, stable_id("syn", sc["sid"]), "noise_only", ctx, hyp, "", meta)]


def build(sc: dict, ex: dict, channel: Channel, teacher: str, backend: SCR.Backend, attempts: int = 5,
          gen: str | None = None) -> list[dict]:
    """One teacher example -> 1 record (2 with the no-context counterpart)."""
    cat = sc["category"]
    spoken, target, screen = ex["spoken"].strip(), ex["target"].strip(), ex["screen"]
    if not spoken or not target:
        raise Reject("empty_spoken_or_target")
    r = mkrng("build", sc["sid"])
    term = sc["spoken_terms"][0] if sc["spoken_terms"] else None
    # cheap teacher-side checks before paying for render + OCR
    if cat == "context_resolution":
        if not find_term(spoken, term["text"]):
            raise Reject("term_not_in_spoken")
        if not _contains(target, term["text"]):
            raise Reject("term_not_in_target")
    if cat == "near_miss" and term and not find_term(spoken, term["text"]):
        raise Reject("term_not_in_spoken")
    ctx, smeta = make_ctx(sc, screen, backend, target)
    rend = ctx_text(ctx)
    meta = _base_meta(sc, teacher, gen) | {"spoken": spoken, "screen": smeta}
    check: dict = {}
    lang = sc["lang"]

    if cat == "no_op":
        meta["noise"] = {"source": "code:no_op"}
        return [_record(sc, stable_id("syn", sc["sid"]), cat, ctx, target, target, meta)]
    if cat == "near_no_op":
        hyp, op = _near_no_op(target, r)
        meta["noise"] = {"source": "code:near_no_op", "events": [op]}
        return [_record(sc, stable_id("syn", sc["sid"]), cat, ctx, hyp, target, meta)]

    prefer = "whisper" if cat == "asr_artifact" else None
    last = "no_attempt"
    for k in range(attempts):
        rk = mkrng("noise", sc["sid"], k)
        model = channel.pick_model(lang, rk, prefer)
        sev = sc["severity"] if k < attempts - 2 else min(sc["severity"], 0.5)
        sp, events, garble = spoken, [], None
        chan_terms = [t for t in sc["spoken_terms"] if _contains(spoken, t["text"])]  # exact Latin spelling only
        if cat == "context_resolution":
            form, src = None, None

            def ff(prev, _r=rk):
                nonlocal form, src
                if form is None:
                    form, src = garble_form(term, lang, channel, model, prev, _r)
                return form
            sp, n = _replace_term(sp, term["text"], ff)
            if not form or form.lower() == term["text"].lower():
                last = "no_garble"
                continue
            if not plausible_garble(form, term["text"]):
                last = "garble_is_abbreviation"
                continue
            garble = {"term": term["text"], "form": form, "source": src, "count": n}
            chan_terms = [t for t in chan_terms if t["text"] != term["text"]]
        if cat == "context_names" and rk.random() < 0.85:
            for p in sc["people"]:
                sp, old, new = misspell_surname(sp, p, rk)
                if new:
                    garble = (garble or {}) | {"person": p, "from": old, "to": new}
        caps = {w for t in sc["spoken_terms"] for w in T.words(t["text"])} | {w for p in sc["people"] for w in p.split()}
        sp2, dops = D.inject(sp, lang, sc["messiness"], rk, sounds_only=cat == "meaningful_filler", keep_caps=caps)
        hyp, events = channel.transcribe(sp2, target, lang, model, chan_terms, sc["noise_seed"] + k, sev)
        if cat == "asr_artifact":
            tail = rk.choice(TAILS["en" if lang == "en" else "ru"])
            hyp = (hyp.rstrip() + " " + tail).strip()
            events = events + [f"tail:{tail}"]
        if not hyp.strip():
            last = "empty_hypothesis"
            continue
        if implausible_subs(events):
            last = "implausible_substitution"
            continue
        if garble and garble.get("form") and not _contains(hyp, garble["form"]):
            last = "garble_lost"
            continue
        if garble and garble.get("to") and not _contains(hyp, garble["to"]):
            last = "misspelling_lost"
            continue
        if cat == "context_resolution" and _contains(hyp, term["text"]):
            last = "term_in_transcript"
            continue
        if cat == "near_miss" and term and not _contains(hyp, term["text"]):
            last = "said_term_garbled"
            continue
        allow = {T.norm_word(w) for t in sc["spoken_terms"] for w in T.words(t["text"])} if cat == "context_resolution" else set()
        if cat == "context_names":
            allow |= {T.norm_word(w) for p in sc["people"] for w in T.words(p)}
            allow |= {T.norm_word(w) for w in T.words(target) if any(T.norm_word(w).startswith(T.norm_word(p.split()[-1])[:4])
                                                                    for p in sc["people"])}
        bad = unrecoverable(target, hyp, rend, allow)
        if bad:
            last = "unrecoverable:" + ",".join(bad[:3])
            continue
        break
    else:
        raise Reject(last.split(":")[0] if not last.startswith("unrecoverable") else "asr_unrecoverable")

    meta["asr_model"] = model
    meta["noise"] = {"source": channel.source, "severity": sev, "attempt": k, "events": events[:20]}
    meta["disfluency"] = dops
    if garble:
        meta["garble"] = garble
    if cat == "context_resolution":
        check["keep"] = [term["text"]]
        meta["entities"] = [term["text"]]
        meta["distractors"] = list(sc["distractors"])
    elif cat == "context_names":
        keep = []
        for p in sc["people"]:
            sur = p.split()[-1]
            keep.append(sur[: max(4, len(sur) - 1)] if T.is_cyr(sur) else sur)
        check["keep"] = keep
        meta["entities"] = list(sc["people"])
        meta["distractors"] = list(sc["distractors"])
    elif cat == "near_miss":
        meta["entities"] = [term["text"]] if term else list(sc["people"])
        meta["distractors"] = list(sc["distractors"])
        check["keep"] = [term["text"]] if term else [sc["people"][0].split()[-1][:5]]
    elif cat == "context_irrelevant":
        meta["distractors"] = [x for x in list(sc["screen_people"]) + list(sc["screen_terms"])
                               if SCR.on_screen(ctx, x) and not _contains(target, x)]
    if cat in NOANSWER:
        check["noanswer"] = True
    if cat == "injection":
        check["injection"] = True
        inj = sc["injection"]["text"]
        check["forbid"] = [f for k2, fs in INJECTION_FORBID.items() if k2 in inj for f in fs]
        meta["injection"] = sc["injection"]
    if check:
        meta["check"] = check
    rec = _record(sc, stable_id("syn", sc["sid"]), cat, ctx, hyp, target, meta)
    out = [rec]
    if cat == "context_resolution" and sc.get("counterpart") and garble:
        try:
            cp_ctx, cp_smeta = counterpart_ctx(sc, smeta["spec"], term["text"], backend)
        except Reject as rj:  # the parent stays; the counterpart is reported as dropped
            out.append({"_reject": str(rj), "category": "context_counterpart", "term": getattr(rj, "term", None)})
            return out
        m = re.search(r"(?<!\w)" + re.escape(garble["form"]).replace(r"\ ", r"[\s-]+"), hyp, re.I)
        hyp_form = m.group(0) if m else garble["form"]
        pat = re.compile(r"(?<![\w-])" + re.escape(term["text"]) + r"(?![\w])")
        cp_target = pat.sub(lambda mm: hyp_form[:1].upper() + hyp_form[1:] if _sentence_start(target, mm.start())
                            else hyp_form, target)
        if _contains(cp_target, term["text"]):
            raise Reject("counterpart_target_has_term")
        cp_meta = copy.deepcopy(meta) | {"counterpart_of": rec["id"], "screen": cp_smeta,
                                         "check": {"keep": [hyp_form], "forbid": [term["text"]]},
                                         "entities": [hyp_form], "distractors": [term["text"]] + list(sc["distractors"])}
        out.append(_record(sc, stable_id("syn", sc["sid"], "cp"), "context_counterpart", cp_ctx, hyp, cp_target, cp_meta))
    return out


def _sentence_start(text: str, pos: int) -> bool:
    before = text[:pos].rstrip()
    return not before or before[-1] in ".!?\n"

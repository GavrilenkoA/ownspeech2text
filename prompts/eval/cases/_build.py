"""Helpers for hand-written eval cases (shared data contract, see prompts/eval/README.md).

Case files (test_v1.py, dev_v1.py) call `case(...)` and the ctx builders below;
`python3 -m prompts.eval.cases.build` (or `make sets`) writes prompts/eval/sets/*.jsonl.

Check fields stored in meta.check (read by prompts/eval/score.py):
  keep    list of str | list[str]   strings that must appear in the output (names, numbers,
                                    terms); a list = any alternative is fine.
  forbid  list of str               must NOT appear (word-boundary, case-insensitive):
                                    distractor names, injection payloads, abandoned
                                    self-correction words, executed-command output.
  alts    list of str               other outputs that count as exact match.
  noanswer bool                     dictated question/command/injection: output must stay
                                    a transcription (scored by the answer check).
"""
from __future__ import annotations

PKG = {
    "Telegram": ("org.telegram.messenger", "messaging"),
    "WhatsApp": ("com.whatsapp", "messaging"),
    "Samsung Messages": ("com.samsung.android.messaging", "messaging"),
    "Slack": ("com.Slack", "work_chat"),
    "Teams": ("com.microsoft.teams", "work_chat"),
    "Gmail": ("com.google.android.gm", "email"),
    "Outlook": ("com.microsoft.office.outlook", "email"),
    "Samsung Notes": ("com.samsung.android.app.notes", "notes"),
    "Notion": ("notion.id", "notes"),
    "Obsidian": ("md.obsidian", "notes"),
    "Termux": ("com.termux", "code"),
    "Chrome": ("com.android.chrome", "search"),
    "Samsung Internet": ("com.sec.android.app.sbrowser", "search"),
    "X": ("com.twitter.android", "social"),
    "Calendar": ("com.samsung.android.calendar", "default"),
}

FIELD_KIND = {"messaging": "message", "work_chat": "message", "email": "email_body",
              "notes": "note", "code": "code", "search": "search", "social": "generic",
              "default": "generic", "address": "address"}


def ctx(app: str, lang: str, *, title: str | None = None, conv_kind: str | None = None,
        msgs: list | tuple = (), terms: list | tuple = (), before: str = "", after: str = "",
        snippets: list | tuple = (), kind: str | None = None, variation: str | None = None,
        category: str | None = None, privacy: str = "full") -> dict:
    """Build a schema-shaped ContextPacket.

    msgs: [("Author", "text"), ("me", "text"), ...] oldest first.
    terms: ["Name", ...] in descending score (first = highest), or (text, score) pairs.
    """
    pkg, cat = PKG[app]
    cat = category or cat
    field = {"kind": kind or FIELD_KIND[cat]}
    if variation:
        field["input_type_variation"] = variation
    if before:
        field["before_cursor"] = before
    if after:
        field["after_cursor"] = after
    c: dict = {"v": 1, "app": {"package": pkg, "label": app, "category": cat}, "field": field}
    if title or msgs:
        ck = conv_kind or {"email": "email_thread", "notes": "document"}.get(cat, "chat")
        conv: dict = {"kind": ck}
        if title:
            conv["title"] = title
        if msgs:
            conv["messages"] = [({"is_me": True, "text": t} if a == "me" else {"author": a, "is_me": False, "text": t})
                                for a, t in msgs]
        c["conversation"] = conv
    if terms:
        out, n = [], len(terms)
        for i, t in enumerate(terms):
            if isinstance(t, (tuple, list)):
                out.append({"text": t[0], "score": float(t[1])})
            else:
                out.append({"text": t, "score": round(1.0 - 0.6 * i / max(1, n), 3)})
        c["terms"] = out
    if snippets:
        c["screen_snippets"] = list(snippets)
    c["language"] = {"asr": lang}
    c["privacy"] = {"level": privacy}
    return c


def case(id: str, lang: str, category: str, transcript: str, target: str, c: dict, *,
         asr: str = "lower", keep=(), forbid=(), alts=(), noanswer: bool = False, note: str = "") -> dict:
    assert lang in ("ru", "en", "mixed"), lang
    check: dict = {}
    if keep:
        check["keep"] = list(keep)
    if forbid:
        check["forbid"] = list(forbid)
    if alts:
        check["alts"] = list(alts)
    if noanswer:
        check["noanswer"] = True
    meta = {"author": "manual (prompt-eval agent, 2026-11-18)", "asr_style": asr, "check": check,
            "licence": "project-internal"}
    if note:
        meta["note"] = note
    return {"id": id, "lang": lang, "category": category, "source": "manual", "split": None,
            "ctx": c, "transcript": transcript, "target": target, "meta": meta}

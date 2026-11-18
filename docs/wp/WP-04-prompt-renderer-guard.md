# WP-04 Prompt renderer + output guard (`:core:prompt`)

Wave 1. Read first: `docs/wp/README.md`; `prompts/README.md`; `prompts/render.py` and `prompts/guard.py` (the
executable specs); `prompts/tests/test_render.py`, `test_render_v2.py`, `test_guard.py`; `docs/ml/CONTEXT_V2.md`;
`ml/eval/contract.py` (how training renders preferences and style overrides); ARCHITECTURE.md §6.5, §9.4; DECISIONS
ADR-011, ADR-019, ADR-020, ADR-040, ADR-041, ADR-045, ADR-046, ADR-060.

## Goal

Byte-exact Kotlin ports (D8): the prompt the phone sends is exactly the prompt the eval and training used, for every
template the catalog ships.

## Owns

`core/prompt/**` (JVM module).

## Contracts

Implement `PromptRenderer` (`render`, `renderContext`, `cachePrefix`) and `OutputGuard` (`precheck`, `guard`,
`fillers`) in `core/prompt/.../Contracts.kt`; replace the `RenderPyPort` / `GuardPyPort` skeletons (keep the class
names: `:app` wires them). `RenderedPrompt.prefix` must equal `render.cache_prefix`, `prefix + suffix` must equal
`render.render_prompt`, `prefixKey` = lowercase hex sha256 of the UTF-8 prefix (the snapshot key is built in `:main`
with `PrefixCacheKey`, ADR-060). Prompt files come only from the classpath (`ClasspathPromptAssets`, packaged by the
`packagePrompts` Gradle task from `prompts/`); never copy them by hand.

**System override (ADR-046), exact semantics:** in `system_prompt`, the text read from `system/<stem>.md` and
`rstrip("\n")`-ed is replaced by `override.replace("\r\n", "\n").rstrip("\n")`; the preferences block is appended after
it exactly as for the shipped text. Callers pass an override only when `PromptEditRules.overrideApplies(profile,
override.baseProfile)` is true (exists, tested: stock profile it was written for, never `finetuned.*`).

## Deliver

1. Port of `clean`, `clip_end`, `clip_start`, ctx v2 rendering (`render_context_v2`, `CTX2_DEFAULT`, `_ocr_blocks`,
   field anchor, `ocr_terms`), `style_line` + tones, `system_prompt` (+ preferences block, + override as above),
   `user_message`, `TEMPLATES` / `chat_turns` for **qwen3.5, qwen3, lfm3, lfm2.5** (their prior-turn openers differ),
   profiles + shots, `render_prompt`, `cache_prefix`. Unicode: NFC (`java.text.Normalizer`), Python regex semantics
   (`\w` with Unicode, case folding) reproduced; document every place where Kotlin and Python differ and how you
   matched it.
2. Port of `guard.py`: `precheck` (fillers, hallucination patterns), `guard` (repair + R1..R7 in order) with the same
   reason codes, `fallback_text`, and `fillers` = the `FILLERS` set (WP-12's dashboard metrics use it).
3. Golden corpus generator `core/prompt/tools/make_goldens.py` (Python stdlib, imports `prompts/render.py` read-only;
   a small helper `system_prompt_with_override` implements the override rule above for the generator only):
   ≥ 300 cases from `prompts/eval/sets/dev.v2.jsonl` + `test.v2.jsonl` (+ `smoke_cases.v2.jsonl`) for **all four
   templates** and profiles default.v4, default.v4s, finetuned.v1, written to
   `core/prompt/src/test/resources/goldens/*.txt` + an index (with the prefix character count per profile × template;
   WP-10 adds token counts); a drift test (`make_goldens.py --check`). Cases must include: preferences (short, exactly
   1,000 chars, 1,500 chars clipped to 1,000, with control characters and CRLF), `style_override`, each tone of
   `prompts/tones/tones.v1.json`, a system override (with CRLF and trailing newlines), emoji, RTL/bidi controls, `<`/`>`
   in screen text, empty screen, password privacy, very long before_cursor, 1,400-character budget boundary.
4. Guard goldens: export every case of `prompts/tests/test_guard.py` to JSON (same generator) and assert
   identical verdict/action/reason in Kotlin.
5. `PromptEditRules` stays the validation for the Settings editor (ADR-019); add the "Edited" detection helper
   (`promptOverride.baseProfile` vs shipped profile) and "made for an older prompt" (override exists, baseProfile ≠
   active profile name).
6. Tone resolution (ADR-020): `style_line(ctx, tones, override)`; named tones (Casual/Neutral/Formal/Verbatim) map to
   an override text only when `prompts/tones/` provides one; otherwise category default + a flag the UI can show.

## Acceptance (checkable here)

- `tools/gw :core:prompt:test` green with: `PromptAssetsTest.goldenCtxV2Prompts` un-ignored and passing for both
  ctxv2 examples × {qwen3.5, lfm3}; ≥ 300 generated goldens byte-identical across the four templates (assert with a
  diff message showing the first differing offset); `cachePrefix` + suffix == full for every golden; system-override
  and preferences cases identical; all `test_guard.py` cases identical; `userOverrideNeverAppliesToFineTunes` (exists).
- `python3 -I core/prompt/tools/make_goldens.py --check` exits 0 (no drift between Python and the committed goldens).
- Performance: rendering the largest golden < 5 ms on the host JVM (it runs on the phone's `:main` per dictation).

## Out of scope

Prompt wording (ML/prompt owner), ctx v1 (ADR-040), the editor UI (WP-19), tokenisation and token counts (WP-10),
the cleanup budget (WP-11).

## Build/test

`tools/gw :core:prompt:test` (JVM; `testDebugUnitTest` alias also works).

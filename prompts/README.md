# Murmur post-processing prompts (v1)

Design rationale: `docs/ml/CONTEXT_AND_PROMPT.md`. Data/training: `docs/ml/DATA_AND_TRAINING_PLAN.md`.

## Files

| File | What it is | Changes when |
|---|---|---|
| `system/default.v1.md` | Full default system prompt (rules + 7 examples), ~1,000 tokens. Used for **prompted** (not fine-tuned) models. Shown in Settings as the editable "Default instructions". | Prompt release |
| `system/finetuned.v1.md` | Short system prompt (~90 tokens) used for Murmur fine-tuned models; identical to the training-time system prompt. | Only with a new fine-tune |
| `system/preferences_block.v1.md` | Wrapper appended to either system prompt when the user filled "My preferences" in Settings. `{{PREFERENCES}}` is replaced by the sanitised text (max 1,000 chars). | Prompt release |
| `tones/tones.v1.json` | Per-category `Style:` lines + input-type overrides. | Prompt release / user override |
| `tones/app_categories.v1.json` | Seed package → category map (UNVERIFIED package names, check on device). | Any time |
| `context.schema.v1.json` | JSON Schema of the `ContextPacket` the Android side produces. | Schema version bump |
| `render.py` | **Executable spec** of the layout: sanitising, context budget, style line, chat templates (thinking off). Also used by the data and eval pipelines. | Prompt release |
| `examples/golden_*.input.json` + `golden_*.{qwen3.5,lfm3}.{default,finetuned}.txt` | Golden inputs and their exact rendered prompts. The Kotlin renderer must reproduce these byte-for-byte (unit test). | Regenerate with `render.py` |
| `eval/smoke_cases.v1.jsonl` | 24 hand-written smoke cases (ru/en/mixed, injection, questions, no-op, lists, numbers, commands). Seed of the eval set, not a benchmark. | Any time |
| `eval/run_smoke.py` | Runs the smoke cases through llama.cpp (`llama-completion`) with greedy decoding. Not run yet (see doc). | — |

## Assembly order (byte-exact, cache-friendly)

```
[chat template header]
SYSTEM  = system/<default|finetuned>.v1.md  (+ preferences_block if the user set preferences)
USER    = "Style: <tone line>\n" + "<context>\n...\n</context>\n" + "<transcript>\n<ASR text>\n</transcript>"
[assistant header with thinking disabled]
```

* The SYSTEM part is identical for every request until the user changes Settings, so its KV cache can be kept/saved and reused (prefix caching). Nothing request-specific (time, app, language) may ever go into SYSTEM.
* Everything taken from the screen goes through `clean()`: control/bidi characters removed, `<` `>` replaced by `‹` `›`, so screen text can never open or close a tag.
* Thinking is disabled: Qwen3.5 → assistant header followed by `<think>\n\n</think>\n\n`; LFM3 → `<think></think>` (both verified against the official `chat_template.jinja`).
* Decoding: greedy (temperature 0 / top-k 1), **no presence or repetition penalty** (the vendors' recommended penalties are for chat; they push an editor away from copying the input), `max_new_tokens = min(1024, 2 × transcript_tokens + 64)`, stop on `<|im_end|>`.

## Rules for editing the prompt

1. Bump the version suffix (`v2`) instead of editing a released file in place; the app stores which version produced each history item.
2. Regenerate goldens with `python3 prompts/render.py --regen-goldens` and re-run the eval before shipping.
3. Users edit only two things: the default instructions (prompted models only) and "My preferences" (all models). "Reset to default" restores `system/default.v1.md`.

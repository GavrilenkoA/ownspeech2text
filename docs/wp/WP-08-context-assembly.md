# WP-08 ctx v2 assembly + privacy rules (`:core:context`)

Wave 1. Read first: `docs/wp/README.md`; `docs/ml/CONTEXT_V2.md` (binding); `prompts/context.schema.v2.json`;
`docs/ml/CONTEXT_AND_PROMPT.md` §5 (privacy table; superseded parts are marked); ARCHITECTURE.md §9.2, §9.4;
`prompts/tones/app_categories.v1.json`; `spikes/platform/app/src/main/kotlin/app/murmur/spike/ContextV2.kt`.

## Goal

From what accessibility gives at press time (`A11ySnapshot`) plus the OCR result, produce exactly the ctx v2 object
the prompt renderer expects, after the privacy rules decided whether a screenshot may be taken at all.

## Owns

`core/context/**` (JVM).

## Contracts

Implement `ContextAssembler` (`DefaultContextAssembler`: `decide`, `assemble`) in
`core/context/.../ContextAssembler.kt`; keep `CtxV2` JSON-identical to the schema (field names, absent vs null:
`explicitNulls = false`); `FieldKinds` (exists) and `BuiltInDenylist` (exists) are yours to refine.

## Deliver

1. `decide()` per ARCHITECTURE 9.2: password → `none` + no screenshot + `skipLlm`; denylisted or user-hidden app →
   no dictation (the bubble is hidden; return `app_only` if called anyway); "Use what's on screen" off →
   `app_only`, no screenshot; else `full` + screenshot.
2. `assemble()`: `app{package, label, category}` (category: `app_categories.v1.json` from the classpath via
   `PromptAssets`-like loading, unknown → null), `field{kind (FieldKinds.ctxKind), hint, before_cursor, box (field
   bounds in screenshot pixels: the window capture's origin is the window's top-left, convert), password}`,
   `screen{size, lines}` only for `full`, `language{asr}`, `privacy` only when not `full`.
3. Coordinate conversion helper (screen → window-capture pixels) with tests for window offsets (split screen,
   inner display) — the renderer anchors "nearest to the field first" on `field.box`.
4. Schema validation test: every assembled object validates against `prompts/context.schema.v2.json` (write a
   tiny validator for the keywords that file uses, or reuse `ml/common/jsonschema_lite.py` logic in Kotlin).

## Acceptance (checkable here)

- `tools/gw :core:context:test` green with tables: password field, number/phone field (bubble not allowed),
  denylisted app, setting off, secure window (screen null), OCR empty, missing field (app exposes no focused node),
  multiline before_cursor, field box conversion with a window offset.
- Round trip: for both `prompts/examples/ctxv2_*.input.json`, a synthetic `A11ySnapshot` + the example's OCR lines
  assemble to the example's `context` object exactly (JSON equality) — proves the shape the renderer goldens use.
- No Android imports (JVM module).

## Out of scope

Reading accessibility (WP-02), OCR (WP-07), rendering and budgets (WP-04).

## Build/test

`tools/gw :core:context:test`.

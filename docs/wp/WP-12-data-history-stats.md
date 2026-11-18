# WP-12 History, metrics, settings, retention (`:data:db`, `:core:store`)

Wave 1. Read first: `docs/wp/README.md`; ARCHITECTURE.md §12, §13, §15; DESIGN.md §6.2 (every dashboard number and
its definition), §6.3 (history list, search, delete + undo, retention mechanics), §6.5 retention sheet;
`docs/research/android-platform.md` §7.1-7.3; DECISIONS ADR-012, ADR-035.

## Goal

Durable, private storage for history (text + audio ref), numbers-only metrics, benchmark results, custom models and
settings; deletion exactly as designed; every dashboard number derivable.

## Owns

`data/db/**`, `core/store/**`.

## Contracts

Implement `HistoryRepository`, `MetricsRepository`, `BenchmarkRepository`, `SettingsRepository`
(`DataStoreSettingsRepository`, typed DataStore with a kotlinx.serialization `Serializer<Settings>`),
`StatsCalculator` (pure, in `core/store`), `Retention`, `RetentionJob`. The Room schema (`Entities.kt`, `Daos.kt`,
`MurmurDatabase`, version 1, schema exported to `data/db/schemas/`) is a starting point; you own it.
Add pure helpers in `core/store` that WP-11 calls at delivery: `wordCount`, `editDistanceWords(raw, final)`,
`fillersRemoved(raw, final, fillers)`, `fixes`, `mixedLanguage(text)` (≥ 2 tokens of the minority script).

## Deliver

1. Mappers row ↔ domain; `entries()` newest first; `search()` over final + raw (case-insensitive, Cyrillic-aware:
   Room `LIKE` is ASCII-case-insensitive only — use a normalised lowercase shadow column or FTS4 with `unicode61`).
2. Delete + Undo: `markDeleted` (soft), `restore`, `commitDeletes` (audio files first, then rows), `deleteAll`.
3. Retention: `RetentionPolicy` (exists, tested) + `Retention.runNow(days)` (history by days, metrics by 90 days,
   orphan audio files via WP-09's `AudioStore.listAll()`), `RetentionJob` scheduled for the next local midnight
   (JobScheduler, reschedules itself; also run at app start and on `ACTION_TIMEZONE_CHANGED`), immediate run after
   shortening the period.
4. `StatsCalculator.compute(rows, today, typingWpm)` = DESIGN 6.2 table / ARCHITECTURE 12 (median, p90, shares,
   top apps, "N other apps").
5. Settings: defaults = `Settings()`; corrupt file → defaults + `StorageFailed` logged (code only); exposed as a
   `StateFlow` started eagerly in `AppContainer.appScope`.
6. `DbModule` factory functions so that `AppContainer` (WP-20) wires you without knowing your classes.

## Acceptance (checkable here)

- JVM (`core/store`): `StatsCalculatorTest` with a 7-day fixture covering each dashboard element (incl. empty,
  failed entries counting 0, dictations < 3 s excluded from pace, time saved ≤ 0 hidden); helper tests (edit
  distance, fillers from the guard lexicon, mixed language).
- Instrumented (`data/db/src/androidTest`, emulator): DAO tests on an in-memory DB; Cyrillic search ("Привет" finds
  "привет"); delete → undo → commit removes the audio file; retention with a fake clock (entries of day D deleted at
  D+7 00:00 local, metrics kept 90 days; changing the zone keeps local-day semantics); `retentionDays` 7 → 3 deletes
  older days immediately; schema JSON exported and a migration test scaffold (version 1 → 1).
- DataStore: write/read every field of `Settings` round trip; concurrent `update` calls serialise.

## Out of scope

UI (WP-16/17/19), audio encoding (WP-09), what is written per dictation (WP-11 computes, you store).

## Build/test

`tools/gw :core:store:test :data:db:testDebugUnitTest :data:db:assembleDebugAndroidTest`.

# WP-12 History, metrics, settings, retention (`:data:db`, `:core:store`)

Wave 1. Read first: `docs/wp/README.md`; ARCHITECTURE.md §12, §13, §15; DECISIONS ADR-053; DESIGN.md §6.2 (every dashboard number and
its definition), §6.3 (history list, search, delete + undo, retention mechanics), §6.5 retention sheet;
`docs/research/android-platform.md` §7.1-7.3; DECISIONS ADR-012, ADR-035.

## Goal

Durable, private storage for history (text + audio ref), numbers-only metrics, benchmark results, custom models and
settings; deletion exactly as designed; every dashboard number derivable.

## Owns

`data/db/**`, `core/store/**`.

## Contracts

Implement `HistoryRepository`, `MetricsRepository`, `BenchmarkRepository` (stores `valid` / `invalidReason`; a
query for "latest valid record per (artifact, unit)"), `SettingsRepository` (`DataStoreSettingsRepository`, typed
DataStore with a kotlinx.serialization `Serializer<Settings>`), `StatsCalculator` (pure, in `core/store`), `Retention`
(with `runNow(days): Int`, the function `SettingsDeps.retention` calls), `RetentionJob`, and `DefaultDeliveryMetrics`
(`core/store/.../Metrics.kt`: `wordCount`, `editDistanceWords(raw, final)`, `fillersRemoved(raw, final, fillers)`,
`fixes`, `mixedLanguage(text)` = ≥ 2 tokens of the minority script; WP-11 calls them at delivery, the filler set comes
from `OutputGuard.fillers`). `RetentionPolicy` (exists, tested: `firstKeptDay(today, 0)` = tomorrow, `keepsHistory`).
The Room schema (`Entities.kt`, `Daos.kt`, `MurmurDatabase`, version 1, schema exported to `data/db/schemas/`) is a
starting point; you own it.

## Deliver

1. Mappers row ↔ domain; `entries()` newest first; `search()` over final + raw (case-insensitive, Cyrillic-aware:
   Room `LIKE` is ASCII-case-insensitive only — use a normalised lowercase shadow column or FTS4 with `unicode61`).
2. Delete + Undo: `markDeleted` (soft), `restore`, `commitDeletes` (audio files first, then rows), `deleteAll`.
3. Retention: `RetentionPolicy` (exists, tested) + `Retention.runNow(days)` (history by days, days = 0 deletes
   everything incl. today; metrics by 90 days; orphan audio files via WP-09's `AudioStore.listAll()`), `RetentionJob`
   scheduled for the next local midnight (JobScheduler, reschedules itself; also run at app start and on
   `ACTION_TIMEZONE_CHANGED`), immediate run after shortening the period.
4. `StatsCalculator.compute(rows, today, typingWpm)` = DESIGN 6.2 table / ARCHITECTURE 12 (median, p90, shares,
   top apps, "N other apps").
5. Settings: defaults = `Settings()`; corrupt file → defaults + `StorageFailed` logged (code only); exposed as a
   `StateFlow` started eagerly in `AppContainer.appScope`.
6. `DbModule` factory functions so that `AppContainer` (WP-20) wires you without knowing your classes.

## Acceptance (checkable here)

- JVM (`core/store`): `StatsCalculatorTest` with a 7-day fixture covering each dashboard element (incl. empty,
  failed entries counting 0, dictations < 3 s excluded from pace, time saved ≤ 0 hidden); `DefaultDeliveryMetrics`
  tests (edit distance, fillers from the guard lexicon, fixes, mixed language) with the same expectations as
  `:testing`'s `SimpleDeliveryMetrics` on shared cases; `RetentionPolicyTest` (exists) green.
- Instrumented (`data/db/src/androidTest`, emulator): DAO tests on an in-memory DB; Cyrillic search ("Привет" finds
  "привет"); delete → undo → commit removes the audio file; retention with a fake clock (entries of day D deleted at
  D+7 00:00 local, metrics kept 90 days; changing the zone keeps local-day semantics); `retentionDays` 7 → 3 deletes
  older days immediately; 7 → 0 deletes everything including today and their audio files; orphan audio files (no
  row) are deleted at start; schema JSON exported and a migration test scaffold (version 1 → 1).
- DataStore: write/read every field of `Settings` round trip (incl. `unitPreference` keyed by model id and the debug
  knobs); concurrent `update` calls serialise; an old file containing the removed `livePreviewModel` key still
  decodes (ignore unknown keys).

## Out of scope

UI (WP-16/17/19), audio encoding (WP-09), what is written per dictation (WP-11 computes, you store).

## Build/test

`tools/gw :core:store:test :data:db:testDebugUnitTest :data:db:assembleDebugAndroidTest`.

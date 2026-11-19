# WP-16 History UI (`:feature:history`)

Wave 2 (view model + state tests can start in wave 1). Read first: `docs/wp/README.md`; DESIGN.md §6.3 (all), §4
(audio player, segmented, search, dialog, snackbar), §7 copy deck (hist_*), §8; frames `hist*.png` at cover and
inner size; ARCHITECTURE.md §12; DECISIONS ADR-035.

## Goal

The main tab: 7 days of dictations grouped by day with the deletion rule visible, detail with Final / Original /
Changes, audio, the context summary, Copy / Re-polish / Try again, search, delete with undo.

## Owns

`feature/history/**`.

## Contracts

Consume `HistoryDeps` (history repository, settings, audio store, clock, `retry`, `repolish`, `apps:
AppInfoProvider` for icons and labels); `HistoryEntry`, `RetentionPolicy` (`core/store`; retention 0 = nothing is
stored, the screen shows the "Don't keep history" empty state). Provide `HistoryScreen(deps, onOpenEntry)` and an entry-detail composable usable
as a list-detail pane (WP-15 hosts it). No other feature module, no data/platform module.

## Deliver

1. `HistoryViewModel` (`viewModelScope`, `StateFlow<HistoryUiState>`): day groups (Today / Yesterday / weekday +
   date in the user's locale), "Deletes tonight" on the oldest kept day, counts with plurals, retention line from
   the setting, loading / empty / error states, search (final + original, app filter chips, highlight), multi-select.
2. Rows: app icon (`deps.apps.icon(pkg)`, generic tile when null), app name (`deps.apps.label(pkg)` or the stored
   label) + conversation, status only when not Inserted (Copied warn / Failed err with icon + word), mono time, 3-line clamp.
3. Detail: top bar (app icon, conversation/app title, "Telegram · Today, 14:32 · 0:12"), segmented Final / Original
   / Changes (inline word diff: additions accentInk on accentSoft, removals ink3 with err strikethrough, TalkBack
   "added: …, removed: …"), audio player (waveform from decoded peaks, scrub), "Context used" table (app,
   "Screen text" = `contextWords` as "182 words · not stored", not clickable — the text is never persisted (D9,
   ADR-043), tone, models with units, timing), retention footnote with the exact deletion time, Copy (primary) / Re-polish (secondary), Try again for
   Failed (disabled when audio is missing → "Recording not available").
4. Delete: confirm dialog with the app name, snackbar "Dictation deleted, with its audio" + Undo 6 s, commit on
   timeout; multi-select delete "Delete 3 dictations?".

## Acceptance (checkable here)

- JVM/Robolectric-free unit tests of the view model with `:testing` fakes (`InMemoryHistoryRepository`,
  `FakeClock`, `StaticAppInfoProvider`): grouping across midnight and time zones, "Deletes tonight" with retention 7/3/1, search hits in raw
  only, undo restores, commit deletes audio (`InMemoryAudioStore`), retry/repolish calls.
- Compose UI tests on the cover and inner emulators with a seeded fake repository: list, detail, search, no results,
  empty, delete + undo, raw, diff; screenshots `.cache/spikes/wp-16/<frame>--{cover,inner}.png` side by side with
  `docs/design/screens/hist*.png` (report deltas); touch targets ≥ 48 dp; TalkBack labels present.
- Fold continuity: selected entry survives a configuration change (activity recreate in the test).

## Out of scope

Retention execution (WP-12), audio decoding (WP-09), re-polish logic (WP-11).

## Build/test

`tools/gw :feature:history:testDebugUnitTest :feature:history:assembleDebugAndroidTest`.

## Integration notes (wave-1 integration pass 1; ADR-064..077 in DECISIONS)

Merged on `integration/wave1`: WP-01, 05, 06, 07, 08, 09, 11, 12, 13. Still in work: WP-02, 03, 04, 10, 14, 20 (early paths).
- WP-01 (ADR-075): components grow with large text, so never put fixed heights around chips, buttons, segmented
  controls, search fields or list rows; `MurmurNavigationRail` is ≥ 88 dp and widens (lay it out wrap-content);
  `MurmurSheet` on medium+ calls `onDismiss` after its exit animation (remove the sheet there); `AppTileSize.letterDp`
  replaces `letterSp`. Test AVDs run with `animator_duration_scale=0`: a test that observes a Compose animation sets it
  to 1 and restores it (`OverlaysTest.withAnimatorScale`).
- `MurmurError` gained `DownloadPaused` (`dl.paused`, copy "Paused" + Resume) and `MicLost` (`audio.lost`, the
  microphone pill): every exhaustive `when` over it needs both (ADR-073, ADR-074).
- `HistoryDeps.retry` (ADR-064) runs as a dictation session: it waits for a running dictation, updates the **same**
  entry in place (Copied when started from inside the app) and returns `Ok(entry)`, or `Err(StorageFailed("audio"))`
  (file missing), `Err(StorageFailed("audio_read"))` (read > 10 s), `Err(NoSpeech)` or the load error. Show progress
  while it runs. A successful retry writes no second metrics row (owner question in `docs/OWNER_QUESTIONS.md`).
- Search: pass the raw query; the repository folds it like the stored `search_text` (`Words.fold`: Python casefold,
  ё → е, ADR-072).

## Integration notes (wave-1 integration pass 2; ADR-078..080 in DECISIONS)

Merged on `integration/wave1` in this pass: WP-04, WP-02, WP-14 (in addition to WP-01, 05, 06, 07, 08, 09, 11, 12, 13).
Still in work: WP-03, 10, 15, 16, 17, 20 (early paths).
- No contract of WP-02/04/14 reaches History directly. A dictation whose screenshot failed because the user switched
  apps (`TargetChanged`) is stored like any other (no screen context; ADR-079).

## Integration notes (wave-1 integration pass 3; ADR-081 in DECISIONS)

Merged on `integration/wave1` in this pass: WP-03 (in addition to WP-01, 02, 04, 05, 06, 07, 08, 09, 11, 12, 13, 14).
Still in work: WP-10, 15, 16, 17, 20 (early paths).
- No WP-03 contract changes the History inputs. The bubble's Error "Open" action arrives as
  `UserInput.ResultAction` → `Effect.Open(OpenTarget)` as before (ADR-061).

## Integration notes (wave-1 integration pass 4; ADR-083 in DECISIONS)

Merged on `integration/wave1` in this pass: WP-10 (in addition to WP-01..09, 11, 12, 13, 14).
Still in work: WP-15, 16, 17, 18, 19, 20 (early paths).
- No WP-10 contract touches History. `cleanup_outcome = skipped:low_memory` now also follows a low-memory kill of
  `:llm` during its load (ADR-083); the label stays the same.

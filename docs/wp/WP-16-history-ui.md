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

# WP-17 Dashboard UI (`:feature:dashboard`)

Wave 2. Read first: `docs/wp/README.md`; DESIGN.md §0 decision 5, §6.2 (every element and its definition), §8
(chart description); frames `dash*.png`; ARCHITECTURE.md §12 (derivation table).

## Goal

Numbers that change behaviour (DESIGN): today, time saved (explicitly estimated), 7-day bars, pace, latency median
and p90, typed vs copied (and where it fails), cleanup changes, languages, top apps — in two layouts.

## Owns

`feature/dashboard/**`.

## Contracts

Consume `DashboardDeps` (metrics repository, settings, `StatsCalculator` from WP-12, clock, service-connected
flow, `apps: AppInfoProvider` — metrics store package names only; "Where you dictate" shows labels and icons from it). Provide `DashboardScreen(deps, onTryDictation)`.

## Deliver

1. View model: `metrics.since(today − 6)` → `StatsCalculator.compute` → UI state; loading skeleton, empty, error
   ("Couldn't read your stats. History is not affected." + Retry), bubble-off banner (`dash-off`).
2. Cover layout (single column, sections 28 dp apart) and inner layout (two columns, 28 dp gap) per DESIGN 6.2.
3. Range chip Last 7 days / Today; typing-speed sheet (30/40/50/60/custom) writing `Settings.typingWpm`.
4. Bar chart: 7 bars, today in accent, others surface3, only the max labelled, total in the header, full
   `contentDescription` with all values; numbers with `NumberFormat` of the locale; `tnum`.

## Acceptance (checkable here)

- View-model tests with a 7-day metrics fixture (same fixture as WP-12's `StatsCalculatorTest` if possible):
  each element's text in EN and RU (plurals), hidden time-saved when ≤ 0, "4 copied only, all in Termux" wording.
- Compose UI tests on cover and inner emulators: `dash`, `dash-empty`, `dash-loading`, `dash-off`, `dash-dark`
  screenshots vs `docs/design/screens/dash*.png`; touch targets; chart description present.

## Out of scope

Computing stats (WP-12), writing metrics (WP-11).

## Build/test

`tools/gw :feature:dashboard:testDebugUnitTest :feature:dashboard:assembleDebugAndroidTest`.

## Integration notes (wave-1 integration pass 1; ADR-064..077 in DECISIONS)

Merged on `integration/wave1`: WP-01, 05, 06, 07, 08, 09, 11, 12, 13. Still in work: WP-02, 03, 04, 10, 14, 20 (early paths).
- WP-01 (ADR-075): components grow with large text, so never put fixed heights around chips, buttons, segmented
  controls, search fields or list rows; `MurmurNavigationRail` is ≥ 88 dp and widens (lay it out wrap-content);
  `MurmurSheet` on medium+ calls `onDismiss` after its exit animation (remove the sheet there); `AppTileSize.letterDp`
  replaces `letterSp`. Test AVDs run with `animator_duration_scale=0`: a test that observes a Compose animation sets it
  to 1 and restores it (`OverlaysTest.withAnimatorScale`).
- `MurmurError` gained `DownloadPaused` (`dl.paused`, copy "Paused" + Resume) and `MicLost` (`audio.lost`, the
  microphone pill): every exhaustive `when` over it needs both (ADR-073, ADR-074).
- `DefaultStatsCalculator` (core:store, public) is the dashboard derivation; `testing/contrib/Wp12MetricsFixture.kt`
  seeds realistic metrics for screenshot tests. Word counts are `guard.py _words` of the NFC text (ADR-072).
- A successful Retry updates the history entry but writes no second metrics row, so its words are not added again
  (ADR-064; owner question).

## Integration notes (wave-1 integration pass 2; ADR-078..080 in DECISIONS)

Merged on `integration/wave1` in this pass: WP-04, WP-02, WP-14 (in addition to WP-01, 05, 06, 07, 08, 09, 11, 12, 13).
Still in work: WP-03, 10, 15, 16, 17, 20 (early paths).
- No contract of WP-02/04/14 changes the Dashboard inputs.

## Integration notes (wave-1 integration pass 3; ADR-081 in DECISIONS)

Merged on `integration/wave1` in this pass: WP-03 (in addition to WP-01, 02, 04, 05, 06, 07, 08, 09, 11, 12, 13, 14).
Still in work: WP-10, 15, 16, 17, 20 (early paths).
- No WP-03 contract changes the Dashboard inputs. The bubble-off banner still follows `A11yStatus` (ADR-079).

## Integration notes (wave-1 integration pass 4; ADR-083 in DECISIONS)

Merged on `integration/wave1` in this pass: WP-10 (in addition to WP-01..09, 11, 12, 13, 14).
Still in work: WP-15, 16, 17, 18, 19, 20 (early paths).
- No WP-10 contract touches the dashboard.

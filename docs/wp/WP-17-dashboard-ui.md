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

# WP-15 App shell, navigation, onboarding (`:app` shell files, `:feature:onboarding`)

Wave 2 (needs WP-01 components; may start with the existing placeholders). Read first: `docs/wp/README.md`;
DESIGN.md §0 decisions 4, 6, 7; §2 (Fold7 layouts, size classes); §3.5 transitions; §4 bottom nav and rail; §6.1
onboarding (all frames `ob-*`); §8; ARCHITECTURE.md §3; DECISIONS ADR-027, ADR-032, ADR-034;
`docs/research/verify/platform.md` §1.9 (enable flow, restricted settings) and §2.

## Goal

The frame every screen lives in (4 tabs, History first, bottom bar on the cover display, rail + list-detail panes
on the inner display) and the first-run flow that gets the permissions and the first models honestly.

## Owns

`app/src/main/kotlin/app/murmur/MainActivity.kt`, `app/src/main/kotlin/app/murmur/AppShell.kt` (+ new shell files in
`app/src/main/kotlin/app/murmur/shell/`), `app/src/main/res/**`, `feature/onboarding/**`.
Not `AppContainer.kt`/`MurmurApp.kt`/manifest (WP-20): ask WP-20 for wiring you need.

## Contracts

Navigation 3 (`NavDisplay`, `rememberNavBackStack`, `@Serializable` `NavKey` routes) + material3-adaptive
(`currentWindowAdaptiveInfoV2`, `ListDetailSceneStrategy` from adaptive-navigation3) — keep `TABS` order and the
`HistoryRoute` start (`ShellTest`). Feature entry points: `HistoryScreen(deps, onOpenEntry)`,
`DashboardScreen(deps, onTryDictation)`, `ModelsScreen(deps, onOpenModel)`, `SettingsScreen(deps)`,
`OnboardingScreen(deps, onDone)`; deps come from `AppContainer` (WP-20 builds `XxxDeps`).

## Deliver

1. Shell: bottom `NavigationBar` (72 dp + gesture inset, ink pill for the active item, labels) on compact width;
   `NavigationRail` (88 dp, mark at the top) on medium+; list-detail scenes for History, Models and Settings on
   medium+ (pane widths 300 / 316 / 260 dp); shared-axis X on the cover display, cross-fade on the inner; selection
   kept across fold/unfold (`rememberSaveable`); back handling; edge-to-edge insets; deep links used by the bubble
   (open History entry, open Models) as `Intent` extras handled in `MainActivity`.
2. Onboarding gate: `Settings.onboardingDone == false` → onboarding; steps of DESIGN 6.1 (welcome, mic, accessibility
   + restricted-settings recovery, notifications, battery, models pack with sizes from the catalog packs and the
   real download progress, download error, ready with the in-app practice bubble). On return from each system screen
   re-check and advance. Restricted-settings detection via `RestrictedSettingsProbe` (WP-02) — never the app-op
   (ADR-027). `ob-overlay` is not shown (ADR-034).
3. App icon placeholder (mark on white) until the asset pass.
4. Strings EN + RU from the copy deck; plurals; product name via `ProductName.NAME`.

## Acceptance (checkable here)

- `tools/gw :app:testDebugUnitTest :feature:onboarding:testDebugUnitTest` green (route/tab tests, onboarding step
  machine: each permission granted/denied/skipped path, restricted-settings branch).
- Instrumented on `murmur_fold7_cover`: `AppShellTest` (exists) + bottom bar visible, rail absent; on
  `murmur_fold7_inner`: rail visible, list + detail visible side by side for History with a fake entry.
- Onboarding with fakes: walk welcome → mic (grant via `pm grant`) → accessibility (enable via `settings put`, the
  screen advances on return) → notifications → battery → models (fake download completes) → ready; screenshots of
  every step at cover and inner size in `.cache/spikes/wp-15/` compared by eye with `docs/design/screens/ob-*.png`.
- No feature module imports another feature module.

## Out of scope

Screen contents (WP-16..19), wiring (WP-20), One UI menu labels (DEVICE_CHECKLIST D6).

## Build/test

`tools/gw :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :feature:onboarding:assembleDebugAndroidTest`.

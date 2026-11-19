# WP-15 App shell, navigation, onboarding, `:app` wiring for the screens (`:app`, `:feature:onboarding`)

Wave 2 (needs WP-01 components; may start with the existing placeholders). Read first: `docs/wp/README.md`;
DESIGN.md §0 decisions 4, 6, 7; §2 (Fold7 layouts, size classes); §3.5 transitions; §4 bottom nav and rail; §5.1
(status notification); §6.1 onboarding (all frames `ob-*`); §8; ARCHITECTURE.md §2 (rule 4), §3, §8.4, §8.5, §13;
DECISIONS ADR-010, ADR-027, ADR-032, ADR-034, ADR-052, ADR-054, ADR-055; `docs/research/verify/platform.md` §1.9
(enable flow, restricted settings) and §2.

## Goal

The frame every screen lives in (4 tabs, History first, bottom bar on the cover display, rail + list-detail panes
on the inner display), the first-run flow that gets the permissions and the first models honestly, and the `:app`
wiring that lets every wave-2 screen run inside the real shell (real modules where they landed, `:testing` fakes
elsewhere). WP-20 takes the wiring over in wave 3.

## Owns

`app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/app/murmur/**` (`MainActivity.kt`,
`AppShell.kt`, `AppContainer.kt`, `MurmurApp.kt`, new files in `shell/`), `app/src/main/res/**`,
`app/src/{debug,release,test}/**`, `app/src/androidTest/kotlin/app/murmur/shell/**` (`AppShellTest` lives there),
`feature/onboarding/**`. Not `app/src/androidTest/kotlin/app/murmur/e2e/**` (WP-20). In wave 3 WP-20 takes over every
path above except `MainActivity.kt`, `AppShell.kt`, `shell/**`, `res/**` and `androidTest/.../shell/**`.

## Contracts

Navigation 3 (`NavDisplay`, `rememberNavBackStack`, `@Serializable` `NavKey` routes) + material3-adaptive
(`currentWindowAdaptiveInfoV2`, `ListDetailSceneStrategy` from adaptive-navigation3) — keep `TABS` order and the
`HistoryRoute` start (`ShellTest`). Feature entry points: `HistoryScreen(deps, onOpenEntry)`,
`DashboardScreen(deps, onTryDictation)`, `ModelsScreen(deps, onOpenModel)`, `SettingsScreen(deps)`,
`OnboardingScreen(deps, onDone)`. You build every `XxxDeps` in `AppContainer` (members are listed in each feature
file) and implement the `:app`-side ports:
- `AppNavigator` (`core/dictation`): `Intent` to `MainActivity` with deep-link extras (open History entry N, open
  Models), handled by the shell;
- `PermissionMonitor` (`core/dictation`): mic, notifications, accessibility enabled (`Settings.Secure`) / connected
  (`A11yServiceHost` state) / ever connected, battery unrestricted, `restrictedLikely` from WP-02's `A11yStatus` and
  `RestrictedSettingsProbe` (ADR-054);
- `AppInfoProvider` (`ui:design`): PackageManager labels and launcher icons (cached `Painter`s), launcher apps
  (`<queries>` LAUNCHER, ADR-021);
- status notification wiring: `StatusNotifier.show(modelsLine, hiddenUntil)` while the service is connected and
  notifications are allowed, updated from the resolver and `Settings.hiddenUntilEpochMs` (ADR-055).

## Deliver

1. Shell: bottom `NavigationBar` (72 dp + gesture inset, ink pill for the active item, labels) on compact width;
   `NavigationRail` (88 dp, mark at the top) on medium+; list-detail scenes for History, Models and Settings on
   medium+ (pane widths 300 / 316 / 260 dp); shared-axis X on the cover display, cross-fade on the inner; selection
   kept across fold/unfold (`rememberSaveable`); back handling; edge-to-edge insets; deep links used by the bubble
   (open History entry, open Models) as `Intent` extras handled in `MainActivity`.
2. Wiring: `AppContainer` builds the five `XxxDeps` from real repositories where the wave-1 packages landed and from
   `:testing` fakes (debug only, `app/src/debug`) where they did not, so every screen renders in the shell; a debug
   `FakeAppContainer` mode (seeded history, metrics, catalog with install states) for screenshot tests.
3. Onboarding gate: `Settings.onboardingDone == false` → onboarding; steps of DESIGN 6.1 (welcome, mic, accessibility
   + restricted-settings recovery, notifications, battery, models pack, download error, ready with the in-app practice
   bubble). On return from each system screen `PermissionMonitor.refresh()` and advance. Restricted-settings detection
   only via `PermissionState.restrictedLikely` (never the app-op, ADR-027/054). The models step awaits
   `deps.device.ensure()` before showing pack sizes (the artifact each pack item resolves to on this phone, plus the
   VAD/OCR it queues, ARCHITECTURE 8.5) and shows Preparing progress and any queued CPU artifact. `ob-overlay` is not
   shown (ADR-034). The practice field calls `deps.practice(target)` (orchestrator `startInApp`).
4. App icon placeholder (mark on white) until the asset pass.
5. Strings EN + RU from the copy deck; plurals; product name via `ProductName.NAME`.

## Acceptance (checkable here)

- `tools/gw :app:testDebugUnitTest :feature:onboarding:testDebugUnitTest` green (route/tab tests, onboarding step
  machine: each permission granted/denied/skipped path, restricted-settings branch, pack sizes for the emulator and
  Fold7 device profiles from `FakeDeviceProfileRepository`).
- Instrumented on `murmur_fold7_cover`: `AppShellTest` + bottom bar visible, rail absent; on `murmur_fold7_inner`:
  rail visible, list + detail visible side by side for History with a fake entry; after `adb shell wm size 1080x2520`
  the selection survives.
- Onboarding with fakes: walk welcome → mic (grant via `pm grant`) → accessibility (enable via `settings put`, the
  screen advances on return) → notifications → battery → models (fake download + fake Preparing completes) → ready
  (practice dictation with fake engines inserts into the practice field); restricted branch with a file-installed APK
  (WP-02's emulator recipe); screenshots of every step at cover and inner size in `.cache/spikes/wp-15/` compared by
  eye with `docs/design/screens/ob-*.png`.
- A deep-link intent with an entry id opens that History entry; the status notification appears with "Murmur is
  ready" when the service is connected (`dumpsys notification`).
- No feature module imports another feature module; `./gradlew :app:dependencies` shows `:testing` only in debug and
  test configurations.

## Out of scope

Screen contents (WP-16..19), real engine wiring and end-to-end tests (WP-20), One UI menu labels (DEVICE_CHECKLIST DC6).

## Build/test

`tools/gw :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :feature:onboarding:assembleDebugAndroidTest`.

## Integration notes (wave-1 integration pass 1; ADR-064..077 in DECISIONS)

Merged on `integration/wave1`: WP-01, 05, 06, 07, 08, 09, 11, 12, 13. Still in work: WP-02, 03, 04, 10, 14, 20 (early paths).
- WP-01 (ADR-075): components grow with large text, so never put fixed heights around chips, buttons, segmented
  controls, search fields or list rows; `MurmurNavigationRail` is ≥ 88 dp and widens (lay it out wrap-content);
  `MurmurSheet` on medium+ calls `onDismiss` after its exit animation (remove the sheet there); `AppTileSize.letterDp`
  replaces `letterSp`. Test AVDs run with `animator_duration_scale=0`: a test that observes a Compose animation sets it
  to 1 and restores it (`OverlaysTest.withAnimatorScale`).
- `MurmurError` gained `DownloadPaused` (`dl.paused`, copy "Paused" + Resume) and `MicLost` (`audio.lost`, the
  microphone pill): every exhaustive `when` over it needs both (ADR-073, ADR-074).
- `AppContainer` / `MurmurApp` already carry cross-package wiring from wave 1: `SystemConditions` (preflight storage +
  thermal, ADR-068), `DefaultCatalogRepository(store = RoomCustomModelStore(db), scope = appScope)`,
  `ModelsModule.downloads(context, catalog) { orchestrator }`, `DefaultInstalledModelsRepository(modelDownloads)`,
  `DefaultModelResolver(..., artifactDir = storage dir)` (ADR-067), `MurmurApp : ModelJobHost`. Keep them.
- WP-12 (ADR-072): build settings with `DbModule.settings(context, appScope)` (one DataStore per process; the
  collector lives as long as the app) and call `RetentionScheduler.start()` at app start (see the DbModule KDoc).
- WP-13 (ADR-073): prefer `ModelsModule.downloads(context, catalog, scope = appScope) { orchestrator }`. A full disk is
  `InstallState.Paused(reason = LowStorage)` (storage banner), a user pause `Paused(DownloadPaused)`; the onboarding
  models step shows both. Read custom entries after `CatalogRepository.loaded()`.
- WP-09: `MurmurApp` implements `StatusActionHandler` (the receiver is in the `:platform:audio` manifest); call only
  `StatusNotifier.show()` / `hide()` (the "Hide 1 h" flip runs on its own). `AndroidAudioCapture(fgsContext = the
  accessibility service)` for the press-time FGS start.
- Practice dictation (ADR-070): the orchestrator asks the assembler with `ContextSettings.inAppPractice = true`; the
  `InAppTarget` snapshot's package must be the app's own package (anything else is decided like a normal app, without
  screenshot).

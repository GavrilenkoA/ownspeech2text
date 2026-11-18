# WP-01 Design system (`:ui:design`)

Wave 1. Read first: `docs/wp/README.md`, `docs/design/DESIGN.md` sections 0, 3, 4, 8, 9 (all of it is binding),
`docs/design/tokens.json`, `docs/design/prototype/index.html` (the `ICONS` map and component CSS), ARCHITECTURE.md §3.

## Goal

Everything visual that more than one screen uses, built once so that WP-03 and WP-15..19 assemble screens without
inventing anything: theme, type, shapes, icons, fonts and the component set of DESIGN §4, at both Fold7 sizes, light
and dark.

## Owns

`ui/design/**` only.

## Starting point (already in the repo)

- `ui/design/build.gradle.kts` generates `GeneratedTokens.kt` from `docs/design/tokens.json` (task
  `generateDesignTokens`): `MurmurColors` (25 roles, light + dark), `TokenType` (22 `TypeSpec`s), `Spacing`,
  `Radius`, `Motion` durations and bubble timings. Extend the generator (elevation shadows, sizes, springs) rather
  than hand-copying values.
- `Theme.kt`: `MurmurTheme` maps tokens onto `MaterialTheme.colorScheme` (DESIGN §9 table); dynamic colour off.

## Deliver

1. **Fonts**: bundle Onest (variable, `res/font/onest_variable.ttf`) and JetBrains Mono (400/500) from the OFL
   sources named in tokens.json (`meta.fonts`); record exact download URL + sha256 + licence file in
   `ui/design/FONTS.md`. `MurmurType`: every `TypeSpec` → `TextStyle` (variable weight via `FontVariation`,
   `includeFontPadding=false`, letter spacing in sp, `tnum` where `tabular`, uppercase for `overline`).
2. **Shapes and elevation**: radius tokens → `Shapes`; e0..e3 as `Modifier`s (shadow colours from tokens).
3. **Icons**: the 45 prototype icons as `ImageVector`s (24 dp grid, 1.75 dp stroke, round caps), plus the mark
   (`ICONS.mark`, stroke 2.25). `MurmurIcons.<name>`; a test that every prototype icon name exists.
4. **Components** (DESIGN §4, with every listed state): buttons (primary/secondary/outline/text/destructive,
   loading), icon button (+tonal, small), chip (+selected, dropdown), badge variants (`NPU`, `GPU`/`CPU`/quant,
   language, `No Russian`, `In training`), in-use pill, segmented control, search field (idle/active), grouped list +
   rows (title/subtitle/leading icon/trailing switch/radio/chevron, inset dividers, pressed overlay), switch, radio,
   linear + ring progress (+paused), banner (err/warn/info), snackbar host (inverse, action), dialog, sheet (bottom
   on compact, 420 dp right side sheet on medium+), audio player (waveform from peaks, scrub), app tile (real launcher
   icon via a `Painter` parameter), skeleton shimmer, empty state block, top bar for pushed screens.
   Minimum touch target 48 dp everywhere (hit area padded where the visual is smaller).
5. **Adaptive helpers**: `rememberIsWide()` (= `currentWindowAdaptiveInfoV2().windowSizeClass` width ≥ 600 dp),
   gutters per size class (20 dp cover, 24-28 dp inner panes), reduced-motion check
   (`ANIMATOR_DURATION_SCALE == 0` → 150 ms cross-fades, DESIGN 3.5).
6. **Gallery**: a debug-only `@Composable ComponentGallery()` that shows every component in every state (used by
   the screenshot test and by other WPs as the reference).

## Acceptance (checkable here)

- `tools/gw :ui:design:testDebugUnitTest` green: token tests (accent `#2F4BE8`/`#8296FF`, every tokens.json color
  role present in both themes, type sizes), icon-name coverage test, contrast test (all text roles ≥ 4.5:1 on their
  surfaces per DESIGN 3.1, computed with the WCAG formula).
- Instrumented test on `murmur_fold7_cover` **and** `murmur_fold7_inner` (`-read-only` if the AVD is in use):
  renders `ComponentGallery` light and dark, captures screenshots to
  `.cache/spikes/wp-01/gallery-{cover,inner}-{light,dark}.png`, asserts every clickable node ≥ 48×48 dp
  (`SemanticsNode.boundsInRoot` / touchBounds) and no text overflow (`TextLayoutResult.hasVisualOverflow` false).
- Side-by-side check by eye against `docs/design/screens/settings--cover.png`, `models--cover.png`,
  `hist--cover.png`, `dash--cover.png` (component level: same paddings, radii, colours, type); list the deltas you
  could not close in the report.
- Fonts: the APK contains `res/font/onest_variable.ttf` (`unzip -l app/build/outputs/apk/debug/app-debug.apk`).

## Out of scope

Screens (WP-15..19), the bubble (WP-03 builds it from your tokens), launcher icon artwork (not designed yet).

## Build/test

`tools/gw :ui:design:assembleDebug :ui:design:testDebugUnitTest :ui:design:assembleDebugAndroidTest`; instrumented
run per README rule 11.

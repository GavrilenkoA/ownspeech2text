# WP-03 Bubble overlay + feedback (`:platform:overlay`)

Wave 1 (visuals use `:ui:design` tokens already generated; switch to WP-01 components when they land).
Read first: `docs/wp/README.md`; DESIGN.md §5 (the bubble, all of it), §3.5 motion, §8 accessibility;
`docs/design/screens/b-*.png` and `bubble-board--board.png`; `docs/research/verify/platform.md` §3 item a and §7
(`OverlayHost.kt`); ARCHITECTURE.md §5.1 (state → bubble mapping).
Code to lift: `spikes/platform/app/src/main/kotlin/app/murmur/spike/OverlayHost.kt`.

## Goal

The bubble exactly as designed: 10 visual states, drag/snap/hide, placement next to the focused field, fold and
rotation handling, TalkBack, haptics — in a `TYPE_ACCESSIBILITY_OVERLAY` window without `SYSTEM_ALERT_WINDOW`.

## Owns

`platform/overlay/**`.

## Contracts

Consume `DictationBubble` (`core/dictation/.../Bubble.kt`) + `levels: StateFlow<FloatArray>` + `onInput(UserInput)`
from the orchestrator, `VisibilityInput` from WP-02, `Settings` (size, positions, tuck-in) from `:core:store`.
Provide: `OverlayHost` (attach/updateVisibility/detach), `BubblePolicy.merge` (exists, tested; a password/number/phone
field hides the bubble even with "Always", fixed after review), `BubblePlacement` (place/snap, pure), the `Feedback`
implementation (`haptic`: `View.performHapticFeedback` CONFIRM / CLOCK_TICK / REJECT when `Settings.feedback` is
VIBRATION or BOTH; `sound`: a no-op until the design owner decides the earcons, ADR-062), the bubble composables.
`DictationBubble.Listening.frozenAtMs` (timer frozen during the first 300 ms of Finishing) and
`DictationBubble.Error.action` (OPEN_MODELS for `UnitUnavailable`) come from `BubbleMapping`.

## Deliver

1. Composables for every state of DESIGN 5.3 (Rest tab, Ready circle, Listening pill with 7 live bars + mono timer +
   stop circle + cancel button 50 dp above, Hold to talk, Transcribing, Polishing (3 pulsing dots), Inserted /
   Inserted no cleanup, Copied only, Error with optional action, Dragging with scrim, guides, ghost and hide target),
   sizes Small/Standard/Large (44/52/60 dp), pill growth toward screen centre, motion specs (springs from tokens),
   reduced motion, host-app UI mode for dark theme, max width 280 dp, labels from the copy deck (EN/RU). Taps: the
   pill body sends `UserInput.ResultTap`, the 40 dp action button `UserInput.ResultAction`.
2. Gestures: tap / hold ≥ 250 ms (if `holdToTalk`) / slide-off > 64 dp / drag ≥ 8 dp / fling > 1000 dp/s / hide
   target magnet 72 dp with haptic; during working states only drag; under TalkBack a custom action "Hide for 1 hour"
   and no tuck-in.
3. `BubblePlacement` (pure, unit-tested): 12 dp above the field, never below `imeTop − 12 dp`, status bar + 16 dp
   limit, per-display stored side + y fraction, 8 dp edge margin (0 for Rest), re-clamp on size/rotation change.
4. `OverlayHost`: one window per display, `WRAP_CONTENT` + `FLAG_NOT_FOCUSABLE`, lifecycle/saved-state/view-model
   owners, drag via `updateViewLayout`, hide via alpha + `FLAG_NOT_TOUCHABLE` (never `View.GONE`), re-place on
   `DisplayListener.onDisplayChanged` (180° rotations arrive only there).
5. Hide 1 h: writes `Settings.hiddenUntilEpochMs`; "Show now" lives on WP-09's status notification (`StatusNotifier`,
   ADR-055) and in-app; Undo clears it.

## Acceptance (checkable here)

- JVM tests: `BubblePolicyTest` (exists, incl. `passwordFieldHidesTheBubbleEvenWhenShowAlwaysIsOn`) extended to every
  row of DESIGN 5.2; placement and snap tables (≥ 15 cases incl. IME up/down, field near top, stored position, cover vs
  inner sizes, rotation).
- Compose UI tests in `platform/overlay/src/androidTest` rendering each state in a test activity at cover and inner
  size: screenshots `.cache/spikes/wp-03/b-<state>--{cover,inner}.png` compared by eye with
  `docs/design/screens/b-<state>--cover.png` (same geometry, colours, type); assert touch targets ≥ 48 dp; assert the
  contentDescription / live-region texts of DESIGN 5.3 for every state.
- Overlay on the emulator: with WP-02's service enabled (or a test accessibility service in your androidTest), the
  bubble window has type `ACCESSIBILITY_OVERLAY` (`adb shell dumpsys window windows | grep -i accessibility`),
  appears over `:testapps:target`, drag + release snaps to the nearer edge, survives `adb shell wm size 1080x2520` ↔
  `1968x2184` plus `adb emu sensor set hinge-angle0 0` / `180` (posture CLOSED/OPEN, TOOLCHAIN 4.5) and rotation 0-3
  (`settings put system user_rotation N`) with the same edge and proportional y (logged positions).
- Level bars: fed with a synthetic levels flow, silence renders flat bars (no fake animation).

## Out of scope

Deciding states (WP-11), focus tracking (WP-02), level computation (WP-09's `LevelMeter`), earcon assets (design
owner). The real hinge and the BLAST stall: DEVICE_CHECKLIST DC5/DC10 (size and posture changes are tested here).

## Build/test

`tools/gw :platform:overlay:testDebugUnitTest :platform:overlay:assembleDebugAndroidTest`.

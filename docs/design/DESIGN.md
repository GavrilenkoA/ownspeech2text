# Murmur design spec

Version 0.1 · 2026-11-18 · Owner: design · Status: ready for implementation review

What's in this folder:

| File | What it is |
|---|---|
| `DESIGN.md` | This spec. Build Compose from it. |
| `tokens.json` | Machine-readable tokens: color (light/dark), type, spacing, radius, elevation, sizes, motion. |
| `prototype/index.html` | One self-contained HTML file. Every screen and bubble state at both Fold7 sizes, 1 CSS px = 1 dp. Open it in a browser. Nav tabs, history rows and model rows are clickable. Tapping the bubble in "Ready" runs the live sequence. `?frame=<id>&dev=cover|inner[&theme=dark]` renders one frame. |
| `screens/*.png` | 102 renders at device scale factor 2: `<frame-id>--<cover|inner|board>[--dark].png`. |

---

## 0. Decisions

1. **The bubble is the product, and it says one thing at a time.** It has 10 visual states (section 5). Each state is one shape and one word. There is no spinner without a label, and the bubble never covers the text field you are typing into. It grows from the edge it is docked to. When the field is idle it tucks into a 22 dp edge tab after 4 s.
2. **One accent, ultramarine `#2F4BE8`, used only for "this is live or selected".** It marks the listening bubble, the primary button, selection and switches. Everything else is ink on near-white with a slight blue bias. Status colors (green, amber, red) are separate from the accent and are used sparingly. Dynamic color (Material You) is **off** because it would replace the identity with the wallpaper palette.
3. **Type: Onest + JetBrains Mono.** Both are OFL licensed, both cover Cyrillic, and both are bundled in the APK. Onest has a real hierarchy from 44 sp display down to 11 sp overline. Mono is used only where digits must line up: model specs, timers, the prompt editor and benchmark numbers.
4. **History is the default tab.** The tab order stays as the brief gives it (Dashboard, History, Models, Settings), but the app opens on History. The 7-day rule appears once at the top of History. The day that will be deleted next is marked "Deletes tonight", and each entry's detail gives its exact deletion time. Deletion happens per calendar day at local midnight, so the rule is predictable: entries from day D are deleted at 00:00 on D+7.
5. **Dashboard shows numbers that change behaviour, not vanity.** It shows today's words with an explicitly estimated time saved, a 7-day bar chart, speaking pace, latency (median and 90th percentile), how often text was typed versus only copied (and in which app it fails), how much cleanup changed, the language mix and the top apps. There are no streaks, leaderboards or percentiles.
6. **Fold7 gets two layouts, not one stretched layout.** The cover display (about 411 × 960 dp, compact width) uses a bottom bar and single panes. The inner display (about 750 × 832 dp, medium width) uses a navigation rail with list-detail panes for History, Models and Settings, and a two-column Dashboard. Layout switches on `WindowSizeClass`, not on device detection.
7. **Onboarding is five honest steps plus a model download.** The steps are microphone, accessibility, notifications, background use, then models. Each screen states what the permission is for, what it is *not* used for, and what Android will show next. There is a dedicated recovery screen for Android's "restricted setting" block on sideloaded APKs. A conditional "display over other apps" step is designed in case on-device testing shows it is needed (section 5.1).
8. **Models are presented as facts.** Each model shows parameters, languages, download size, where it runs (NPU/GPU/CPU), streaming support and license, all taken from the model card. A model whose card doesn't list Russian gets a visible "No Russian" badge. Benchmarks run on the phone and report four numbers in plain words.
9. **Cleanup is "Cleanup" in the UI, not "post-processing" or "LLM".** The prompt editor shows variables as chips, validates that `{transcript}` is present, has a live "Try it" box, and resets to the default after a confirmation.

---

## 1. Research summary

### 1.1 Wispr Flow (verified from vendor docs, Nov 2026)

| Topic | What Wispr does | Source |
|---|---|---|
| Android form factor | A floating bubble, not a keyboard. It appears when a text field is focused and disappears when you aren't typing. Launched 23 Feb 2026. | [wisprflow.ai/android](https://wisprflow.ai/android), [TechCrunch](https://techcrunch.com/2026/02/23/wispr-flow-launches-an-android-app-for-ai-powered-dictation) |
| Start/stop | Tap to start and tap to stop (hands-free), or hold to talk and release to submit. | [Setup on Android](https://docs.wisprflow.ai/articles/8858845757-setup-wispr-flow-on-android-android-settings) |
| Permissions | Microphone; **Display over other apps (required, can't skip)**; Accessibility (text insertion, reads screen content); battery exemption (skippable, with a warning); notifications (skippable). Ongoing notification text: "Voice dictation is ready." | same |
| Where the bubble hides | Numeric, phone, password and credit-card fields, banking and payment apps, password managers. It stays available in URL bars and notification replies. | same |
| Failure copy | No speech: "Sorry, couldn't hear you. Tap again to speak!" Insertion fails: the text is copied, a temporary "Copy" button appears, and the toast says "Text copied to clipboard." | same |
| Drag and snap | Drag anywhere and it snaps to the nearest edge. Vertical position is kept across rotation. During dictation only dragging works. | [Navigating the app](https://docs.wisprflow.ai/articles/5096240724-navigating-the-wispr-flow-app-desktop-ios-and-android) |
| Snooze | Drag slowly toward the bottom centre until a target appears. Releasing over it gives a confirmation vibration and hides the bubble for a fixed 10 min ("Wispr Flow is snoozed for 10 min"). Shaking twice ends the snooze. After a snooze the bubble returns to the default right edge. | [Snooze the bubble](https://docs.wisprflow.ai/articles/3400534884-snooze-the-dictation-bubble) |
| Size and shrink | 4 size stops (0.7×, 0.85× default, 1.0×, 1.15×). Optional "Shrink the bubble when not in use" after 5 s idle. Optional "Shrink to a dot". | [Bubble size and shrink](https://docs.wisprflow.ai/articles/2807859589-customize-flow-bubble-size-and-shrink-behavior-on-android) |
| Home / history (Android) | The bottom bar has Home, Dictionary and Snippets. Home shows warning cards, stats and history grouped by date. Tapping a card copies it. The ⋮ menu has Copy, Retry, Report and Delete. Retry needs stored audio. | [Navigating the app](https://docs.wisprflow.ai/articles/5096240724-navigating-the-wispr-flow-app-desktop-ios-and-android), [Retry](https://docs.wisprflow.ai/articles/2503460374-retry-failed-transcriptions) |
| Stats (desktop "Usage") | WPM gauge with a "Top X%" percentile; "Fixes made by Flow" (fillers and dictionary fixes); total words; desktop/mobile split; usage categories; an activity heatmap with current and longest streak; shareable cards. | [Your Usage tab](https://docs.wisprflow.ai/articles/8760230576-your-usage-tab-track-your-dictation-stats-in-wispr-flow) |

What we take: the edge-docked bubble, tap and hold modes, a drag-to-hide target with a haptic, copy-on-failure, and per-field exclusions.
What we change:
- Time-boxed hiding is 1 h with Undo and a notification action, instead of a fixed 10 min ended by shaking.
- The idle bubble tucks into an edge tab instead of shrinking to an icon.
- No percentile, streak or share cards. They suit a cloud product with a community. For a private, on-device tool they are noise.
- History is a first-class tab with raw/final/changes views and audio, not a strip under the stats.

### 1.2 Other references

| App | Pattern we use | Source |
|---|---|---|
| Superwhisper (macOS/iOS) | History keeps audio, transcription *and* AI result per entry. "Reprocess" re-runs a dictation with another mode without re-recording. Retention options are Forever, 1 day, 1 week, 2 weeks and more, with a warning before shortening. Search covers the original dictation. | [Superwhisper docs: History](https://superwhisper.com/docs/get-started/interface-history) |
| Android platform (accessibility services, restricted settings) | Since Android 13, sideloaded apps are blocked from enabling an accessibility service until the user picks "Allow restricted settings" in App info. Android 15 extends the restriction. | Secondary sources only: [Android Authority](https://androidauthority.com/android-15-restricted-settings-sideloading-3481098), [Esper](https://www.esper.io/blog/android-13-sideloading-restriction-harder-malware-abuse-accessibility-apis). **UNVERIFIED** against a google.com primary page; the exact One UI 8 menu labels must be checked on the device. |

Third-party claims about other Android dictation apps (Typeless, Yaps) came only from competitor blogs and were not used for decisions.

---

## 2. Target device: Galaxy Z Fold7

| | Cover display | Inner (main) display |
|---|---|---|
| Size | 6.5" Dynamic AMOLED 2X, 120 Hz ([Samsung spec sheet PDF](https://image-us.samsung.com/us/smartphones/galaxy-z-fold7/pdf/Q7_B2B_Spec_Sheet_R3Lc.pdf)) | 8.0" Dynamic AMOLED 2X, 120 Hz (same) |
| Resolution | 1080 × 2520 px ([Vodafone device guide](https://deviceguides.vodafone.co.uk/samsung/galaxy-z-fold7-android-16/specifications/)) | 1968 × 2184 px (same; GSMArena and Yesviz agree) |
| Aspect ratio | 21:9 (2.33:1), tall and narrow | 1.11:1, almost square |
| dp at 420 dpi | **411 × 960 dp** | **750 × 832 dp** |
| Window size class (portrait) | Compact width, expanded height | Medium width (600–840), medium height |
| Navigation | Bottom bar, 4 tabs | Navigation rail, 88 dp |
| Panes | Single pane; detail pushes a new screen | List-detail: History 300 dp list, Models 316 dp list, Settings 260 dp categories |

- Size classes are from [Android window size classes](https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes). Compact is < 600 dp, medium is 600–839 dp, expanded is ≥ 840 dp.
- **Density is UNVERIFIED.** One owner reported `wm density` → physical 420 ([B4X forum](https://www.b4x.com/android/forum/posts/1032569)). A Samsung community post mentions a cover "minimum width" of 420 dp, and "437 (default)" for the inner display, which does not fit the inner pixel width. Users can also change display zoom. The prototype frames use 412 × 960 and 750 × 832.
- **Rule: never branch on "is Fold7".** Branch on `currentWindowAdaptiveInfo().windowSizeClass`. In landscape the inner display reaches expanded width (about 832 dp). It then uses the same rail layout with wider panes. Split-screen on the inner display can drop Murmur to compact width; it then gets the cover layout.
- On the inner display, Samsung's taskbar may occupy the bottom edge (UNVERIFIED for One UI 8 defaults). The rail layout keeps nothing important at the bottom except the action bar, which sits above system insets (`WindowInsets.safeDrawing`).

---

## 3. Foundations

### 3.1 Color

The full values are in `tokens.json → color`. Contrast ratios were computed with the WCAG formula. These are the roles:

| Token | Light | Dark | Used for | Contrast |
|---|---|---|---|---|
| bg | `#F6F7F9` | `#0F1013` | Screen background | — |
| surface | `#FFFFFF` | `#17191D` | Grouped lists, cards, sheets, nav, ready bubble | — |
| surface2 | `#EEF0F4` | `#1F2227` | Search field, segmented track, tonal buttons, player | — |
| surface3 | `#E2E5EB` | `#2A2E35` | Selected category row (inner Settings), chart bars, skeleton | — |
| ink | `#14161B` | `#ECEEF2` | Text, icons, active nav pill fill | 17:1 / 15.5:1 |
| ink2 | `#464C59` | `#B9BFCA` | Secondary text | 8.0:1 |
| ink3 | `#6B7280` | `#9AA1AE` | Captions, timestamps | 4.5:1 on bg (light), 7.2:1 (dark) |
| ink4 | `#A3A9B5` | `#646B78` | **Decorative only** (unplayed waveform, English segment of the language bar) | — |
| accent | `#2F4BE8` | `#8296FF` | Listening bubble, primary button, toggles on, radio, today's bar | onAccent on accent 6.4:1 / 6.8:1 |
| accentSoft / accentInk | `#E8ECFF` / `#2238C9` | `#1D2347` / `#B4C1FF` | Selected rows, NPU badge, search highlight, accent text | 7.2:1 |
| ok / warn / err (+Soft) | `#167A49` / `#9A5B00` / `#C2341F` | `#5FD08F` / `#F2B54A` / `#FF8A75` | Inserted tick, "Copied" status and storage warnings, errors | ≥ 4.7:1 on their soft fills |
| inverse / onInverse | `#14161B` / `#F3F4F6` | `#ECEEF2` / `#14161B` | Snackbar, transcribing and polishing bubble, edge tab | — |

Rules:
- The accent never decorates. If something is accent-colored, it is live (listening) or chosen (selected, on, primary action).
- Selected nav item: an **ink** pill with a bg-colored icon, not an accent pill. This keeps the accent free for the bubble and avoids the default Material look.
- No gradients anywhere. The only non-flat fill is the skeleton shimmer.
- Dark theme follows the system setting. The bubble follows the **host app's** current UI mode (`Configuration.uiMode` of the service context), not Murmur's.

### 3.2 Type scale

Onest (variable, bundled `res/font/onest_variable.ttf`) and JetBrains Mono. Sizes are in sp, so they scale with the user's font size.

| Style | Size / line | Weight | Tracking | Where |
|---|---|---|---|---|
| display | 44 / 48 | 600 | −1.2 | Dashboard "Today" number |
| headline | 28 / 34 | 600 | −0.5 | Screen titles, model name in detail |
| onboardingTitle | 30 / 36 | 600 | −0.6 | Onboarding titles (balanced wrap) |
| title | 20 / 26 | 600 | −0.25 | Sheet and dialog titles, empty states |
| titleSm | 16 / 22 | 600 | −0.1 | Section heads, model names |
| topBar | 17 / 22 | 600 | 0 | Top app bar on pushed screens |
| reading | 18 / 28 | 400 | −0.1 | Entry detail text |
| bodyLg | 16 / 24 | 400 | 0 | Onboarding lead, descriptions |
| entry | 15 / 22 | 400 | 0 | History list text, 3-line clamp |
| rowTitle | 15 / 20 | 500 | 0 | List and settings row titles |
| body / label | 14 / 20 | 400 / 500 | 0 | Body, labels |
| button | 15 / 20 | 600 | −0.1 | Buttons |
| bubble | 14 / 18 | 600 | 0 | Bubble labels |
| rowSub | 13 / 18 | 400 | 0 | Row subtitles |
| labelSm / caption | 12 / 16 | 500 / 400 | 0.1 / 0 | Nav labels, statuses, captions |
| overline | 11 / 16 | 600 | 0.8, uppercase | "TODAY", "CONTEXT USED" |
| mono / monoBadge / monoTimer / editor | 12/16, 11/14, 14/18, 13/20 | 400–500 | tabular | Specs, badges, timer, prompt |

Numbers that sit in columns use `fontFeatureSettings = "tnum"`.

### 3.3 Spacing, radius, elevation

- **Spacing:** 4 dp grid (`s4 … s56`). The screen gutter is 20 dp on the cover display, 24–28 dp in inner panes, and 16 dp around grouped lists. Sections are 28 dp apart on Dashboard. Within a row, gaps are 8–14 dp.
- **Radius:**
  - full: buttons, chips, search, segmented control, bubble
  - lg 20: grouped lists, cards
  - xl 28: sheets and dialogs
  - md 14: banners, audio player, editor
  - xs 6: badges, app tiles
- **Elevation:** almost everything is flat (e0); separation comes from fill (`surface` on `bg`). Cards get e1. The bubble and snackbars get e2. A dragged bubble, sheets and dialogs get e3. There are no cards inside cards: a grouped list is one surface with inset dividers.

### 3.4 Iconography and mark

- 24 dp grid, 1.75 dp stroke, round caps and joins, drawn as outlined vectors. Filled forms are used only for play/pause/stop and the "more" dots. The prototype's `ICONS` map is the source set: 45 icons, copyable as `ImageVector` path data. Never use emoji as icons.
- **Mark:** a monoline lowercase "m" (`ICONS.mark`) at stroke 2.25. It is the launcher glyph, the rail logo and the ready bubble glyph. The launcher icon is an ink mark on white with an ultramarine background in the adaptive monochrome layer. This needs a separate asset pass; it is not in this phase.
- Host apps are shown with their real launcher icon, from `PackageManager.getApplicationIcon`. The prototype uses letter tiles only because it can't load real icons.

### 3.5 Motion

| Thing | Spec |
|---|---|
| Durations | instant 90, short 150, medium 240, long 360, extraLong 500 ms |
| Easing | standard (0.2,0,0,1); decelerate (0.05,0.7,0.1,1) for entering; accelerate (0.3,0,0.8,0.15) for leaving |
| Bubble appears | scale 0.6→1 and alpha 0→1, spring(stiffness 400, damping 0.75), from the docked edge |
| Circle ↔ pill | width animates with spring(1500, 0.9). The content cross-fades over 150 ms after 60 ms. The pill grows **toward screen centre** from its docked edge. |
| Level bars | 7 bars, 30 Hz RMS sampling, each bar = RMS of a sliding window × a per-bar weight `[.35,.7,1,.6,.85,.45,.25]`, clamped 4–24 dp, smoothed with an attack of 40 ms and a release of 120 ms. No fake animation: silence is flat. |
| Snap after drag | Animate to the nearer edge with spring(400 = `StiffnessMediumLow`, 0.8). Fling velocity decides the side if > 1000 dp/s. |
| Hide target | Appears after 300 ms of drag (fades and rises 16 dp). Within the 72 dp magnet radius the bubble is pulled to the target centre, the target fills ink and scales 1.12, and a haptic fires (`CONFIRM`/`CLOCK_TICK`). |
| Screen transitions | Shared-axis X for push/pop on the cover display (240 ms). On the inner display the detail pane cross-fades (150 ms). |
| Sheets | Slide up with decelerate, 360 ms. On the inner display they appear as a side sheet from the right, 420 dp wide. |
| Reduced motion | When `Settings.Global.ANIMATOR_DURATION_SCALE == 0` or the user enables "Remove animations": all springs and morphs become 150 ms cross-fades, and the level meter becomes a static 3-step meter. |

Compose spring constants come from [androidx `Spring`](https://developer.android.com/reference/kotlin/androidx/compose/animation/core/Spring): StiffnessMediumLow = 400, StiffnessMedium = 1500, DampingRatioLowBouncy = 0.75.

---

## 4. Components

All interactive elements have a touch target of at least 48 × 48 dp ([Android accessibility guidance](https://developer.android.com/guide/topics/ui/accessibility/apps)). Where the visual is smaller (32 dp chips, 22 dp edge tab), the hit area is padded to 48 dp.

| Component | Anatomy and measurements | States |
|---|---|---|
| Button, primary | 52 dp tall, full radius, 24 dp horizontal padding, `button` type, optional 20 dp leading icon with an 8 dp gap. Accent fill, onAccent text. | pressed: accentPressed; disabled: 38% opacity; loading: label replaced by a 16 dp spinner plus the same label ("Downloading…") |
| Button, secondary | Same size, surface2 fill, ink text | — |
| Button, outline | 1 dp line2 border | — |
| Button, text | 48 dp tall, 12 dp padding, accentInk text; destructive variant uses err | — |
| Icon button | 48 dp circle with a 24 dp icon; small variant 40 dp visual, 48 dp hit; tonal variant has a surface2 fill | — |
| Chip | 32 dp visual (48 dp hit), full radius, 1 dp line2 border, 13 sp / 500. Selected: ink fill, bg text. Dropdown chip: a 36 dp variant with chevD. | — |
| Badge | 20 dp, xs radius, monoBadge type. Variants: `NPU` (accentSoft/accentInk), `GPU`/`CPU`/quant (surface2/ink2), language (outline), `No Russian` (warnSoft/warn), `In training` (surface2). | — |
| In-use pill | 24 dp, full radius, accent fill, check icon plus "In use", 11 sp / 600 | — |
| Segmented | 40 dp track (surface2, 3 dp padding), 34 dp segments. The selected segment is a surface fill with e1. | — |
| Search | 48 dp, full radius, surface2. Active state: surface plus a 2 dp ink ring; a back arrow replaces the header; there is a clear (×) button. | — |
| Grouped list | surface, lg radius, rows ≥ 56 dp, 16 dp padding, dividers inset 16 dp (54 dp when rows have a leading icon). The whole row is the touch target, including rows with a switch. | pressed: ink at 6% overlay |
| Switch | 52 × 32 track. Off: surface3 with a line2 border and an ink3 thumb. On: accent with an onAccent thumb. | — |
| Radio | 22 dp, 2 dp border, 12 dp dot | — |
| Progress | Linear 4 dp (6 dp in onboarding), full radius, surface3 track. Ring 36 dp for model rows. | paused: track fill becomes ink4 |
| Banner | md radius, 14/16 dp padding, 22 dp leading icon, bold first clause plus explanation, optional text button | err / warn / info |
| Snackbar | inverse fill, md radius, ≥ 52 dp, 12 dp side inset, 84 dp above the bottom on the cover display. Action in inverseAccent. 4 s, or 6 s when it has an action. | — |
| Dialog | xl radius, 24 dp padding, `title` + `body`, right-aligned text buttons. 400 dp wide and centred on the inner display. | — |
| Sheet | xl top radius, 32 × 4 handle. On the inner display it is a 420 dp right side sheet. | — |
| Bottom nav | 72 dp plus the gesture inset, surface, 1 dp top line. Items: a 56 × 32 pill holding a 24 dp icon, with a 12 sp label 4 dp below. Active: ink pill, bg icon, ink label. | — |
| Nav rail | 88 dp, surface, 1 dp right line. The mark sits at the top (48 dp box). Items are 80 dp wide with the same pill. | — |
| Audio player | surface2, md radius. 44 dp play button (ink circle, bg glyph). Waveform: 2.5 dp bars from real peaks, played part in ink, rest in ink4. Mono time "0:04 / 0:12". | playing; scrub by drag (48 dp tall hit band) |
| App tile | 20 dp in lists (6 dp radius), 36 dp in headers and tone list. Real launcher icon. | — |

---

## 5. The bubble

### 5.1 Window and permissions

- The bubble is drawn by Murmur's `AccessibilityService` in a `TYPE_ACCESSIBILITY_OVERLAY` window. The Android reference describes this type as "overlaid only by a connected AccessibilityService" and lists no extra permission. `TYPE_APPLICATION_OVERLAY` "Requires SYSTEM_ALERT_WINDOW" ([WindowManager.LayoutParams](https://developer.android.com/reference/android/view/WindowManager.LayoutParams)). So the baseline onboarding has **no "Display over other apps" step**.
- **Risk to validate on the Fold7 (UNVERIFIED):** recording from the bubble needs a microphone foreground service. On Android 14+ a service that needs a while-in-use permission (microphone) "cannot [be created] while the app is in the background". The documented exemptions don't mention accessibility services ([FGS restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types)). Wispr Flow requires "Display over other apps", which suggests the same constraint.
  - If capture started from the accessibility window fails on the device, insert the conditional step `ob-overlay` ("Allow the bubble to start the microphone") after Accessibility, and show the bubble in a `TYPE_APPLICATION_OVERLAY` window.
  - Engineering should test this first, before building anything else on the bubble.
- Notification (Android 13+ `POST_NOTIFICATIONS`, [docs](https://developer.android.com/develop/ui/views/notifications/notification-permission)): one silent, low-importance channel "Murmur status".
  - Content: "Murmur is ready", with the active models as the subtitle.
  - Actions: "Hide 1 h" and "Start dictation". "Start dictation" lets people who can't use the bubble start dictation from the shade.

### 5.2 When the bubble is visible

| Condition | Bubble |
|---|---|
| No editable field focused | Hidden (default). The setting "Show the bubble: Always" keeps a Rest tab on the last edge. |
| Editable field focused (`AccessibilityNodeInfo.isEditable`) | Ready (appears) |
| Field is a password field (`isPassword`), or the input type is `TYPE_TEXT_VARIATION_*PASSWORD`, `TYPE_CLASS_NUMBER` or `TYPE_CLASS_PHONE` | Hidden, always |
| App is in "Hidden in" (user list) | Hidden |
| Murmur's own UI | Hidden (the practice field in onboarding uses an in-app bubble) |
| Ready for 4 s with no interaction | Rest (edge tab) |
| User hid it (drag to target or notification) | Hidden until the time shown, or until Undo or "Show now" |
| Keyboard closes while Ready or Rest | Hidden after 300 ms (debounced to avoid flicker between fields) |

### 5.3 States

The prototype frames are `b-*` and `bubble-board`. Sizes are in dp.

| State | Visual | Size | Enters when | Leaves when | TalkBack text (`contentDescription` / live region) | Haptic |
|---|---|---|---|---|---|---|
| **Rest** | inverse edge tab with a 14 dp mark | 22 × 60, hit 48 × 60 | Ready for 4 s | tap → Ready; field blur → Hidden | "Murmur. Double-tap to show the dictation button" | — |
| **Ready** | surface circle, 26 dp ink mark, e2 plus a 1 dp line | Ø 52, hit 56 | field focused | tap → Listening; hold ≥ 250 ms → Hold; drag ≥ 8 dp → Dragging | "Start dictation. Double-tap to talk, double-tap again to finish" | — |
| **Listening** | accent pill: 7 live level bars, mono timer, a 40 dp stop circle (white 18% fill). A **cancel** circle (40 dp visual, 48 dp hit, surface, ×) floats 50 dp above. | 164 × 52 | tap on Ready | tap anywhere on the pill → Processing; × → discard → Ready; 5:00 reached → auto stop | live: "Listening"; button: "Finish dictation"; cancel: "Cancel and discard" | `CONFIRM` on start |
| **Hold to talk** | accent pill: bars plus "Release to finish" | auto × 52 | hold ≥ 250 ms | release → Processing; slide > 64 dp off the pill → discard | live: "Listening. Release to finish" | `CONFIRM` |
| **Transcribing** | inverse pill: dimmed static bars plus "Transcribing" | auto × 52 | stop, if text isn't ready within 300 ms | text ready | live: "Transcribing" | — |
| **Polishing** | inverse pill: three pulsing dots plus "Polishing" | auto × 52 | transcript ready and cleanup ≠ None | cleanup done | live: "Polishing" | — |
| **Inserted** | surface pill: ok check plus "Inserted" | auto × 52 | text set in the field (and copied to the clipboard) | after 1.2 s → Ready (collapse) | live: "Inserted" | `CLOCK_TICK` |
| **Copied only** | surface pill: accentInk clipboard plus "Copied. Paste it anywhere" | auto, max 280 | no editable field at insert time, or insertion rejected | after 4 s → Hidden or Ready; tap → opens the entry in History | live: "Copied to clipboard" | `CLOCK_TICK` |
| **Error** | surface pill: err alert icon, short reason, and an optional 40 dp tonal action | auto, max 280 | see 5.5 | action or 6 s → Ready | live (assertive): the reason | `REJECT` |
| **Dragging** | Ready circle scaled 1.08 with e3. A scrim at 14% appears. Dashed accent guides show both snap edges, a ghost circle shows where it will land, and the hide target sits at the bottom centre. | Ø 52 | drag ≥ 8 dp from Ready or Rest | release → snap; release on target → Hidden 1 h | "Dragging. Release at the bottom to hide" | tick when entering the magnet |

During Listening, Transcribing and Polishing, the bubble can only be dragged. Taps on the pill finish (Listening) or are ignored (working states). This matches Wispr's behaviour and prevents a double-stop.

The "Inserted without cleanup" variant reads "Inserted, no cleanup" with the same visual. It is used when the cleanup model failed or memory was short. The history entry records the reason.

### 5.4 Position, snapping and staying out of the way

- **Docking:** always to the left or right screen edge, with an 8 dp margin (0 dp for the Rest tab). The default side is right.
- **Vertical placement:**
  - The default sits 12 dp above the top of the focused field's bounds (`getBoundsInScreen`), aligned to the docked edge.
  - If that would overlap the field's own bounds or go above the status bar plus 16 dp, it sits 12 dp above the IME top instead.
  - The bottom of the bubble is never below `imeTop − 12 dp`. The IME top comes from the accessibility window list, type `TYPE_INPUT_METHOD`.
- **User override:** after a drag, the chosen side and the y position (as a fraction of usable height) are stored **per display**, cover and inner separately. The stored position wins, clamped to the safe area and the IME.
- **Edge snap:** on release, go to the nearer horizontal edge, or to the fling direction if |vx| > 1000 dp/s. Keep y (clamped).
- **Pill growth:** pills grow toward the screen centre from the docked edge, so the docked edge never moves.
- **Fold and unfold:** the service gets a new display. Re-run placement with that display's stored position. Don't animate across displays.
- **Half-folded (Flex mode):** keep the bubble out of the hinge band. This needs `FoldingFeature` bounds; how to get them from an accessibility service context is **UNVERIFIED**. As a fallback, keep the bubble in the lower half.
- **Hiding:** drag the bubble to the bottom-centre target. The 64 dp circle sits 56 dp above the gesture bar, with the label "Drag here to hide for 1 hour".
  - Releasing in the magnet hides the bubble. The snackbar says "Bubble hidden until 15:32" with Undo, and the status notification shows "Show now".
  - This doesn't mute dictation from the notification.

### 5.5 Errors

| Case | Bubble text | Action | Saved to History |
|---|---|---|---|
| Less than 0.5 s of speech detected | Didn't catch that | Retry (starts Listening) | Yes, as Failed, with audio, so it can be re-run |
| Microphone held by another app or a call | Microphone is busy | — | No |
| No speech model downloaded | Download a speech model first | Open (opens Models) | No |
| Model failed to load (OOM or runtime error) | Couldn't load the speech model | Retry | Yes (Failed, with audio) |
| Hit the 5:00 limit | timer turns warn at 4:30, auto-stop at 5:00, then normal processing | — | Yes |
| Cleanup failed or timed out (> 6 s) | Inserted, no cleanup | — | Yes, note "Cleanup skipped: <reason>" |
| Insertion rejected by the field | → Copied only state | — | Yes (Copied) |
| Accessibility service killed | no bubble; Dashboard and Settings show the red banner "The bubble is off." | Open Accessibility settings | — |

The 5:00 limit is a design default. The real limit depends on the ASR model and chunking, which the ML docs decide.

### 5.6 Sizes setting

The "Size" setting has three stops, Small / Standard / Large, at 44 / 52 / 60 dp. Pills scale proportionally. The hit area is never below 48 dp. Opacity is not a setting: a translucent control reads as disabled.

---

## 6. Screens

The frame IDs below match `screens/` and the prototype (`?frame=<id>`).

### 6.1 First run (`ob-*`)

The cover display uses a single column. The CTA is pinned to the bottom (thumb zone), and progress dots plus "n of 5" sit at the top. The inner display shows a 300 dp left panel with the whole checklist (done ✓ / current, filled ink / pending, outline) and a 470 dp content column. On return from each system screen, Murmur re-checks the permission and moves to the next step automatically.

| # | Frame | Title | Body (why / what not) | Primary | Secondary | System interaction |
|---|---|---|---|---|---|---|
| 0 | `ob-welcome` | Speak in any app. Murmur types it. | Recognition and cleanup run on this phone, in Russian and English. Your voice is never uploaded. A live demo strip shows the listening pill over a text field, followed by three points (bubble; cleanup; 7-day history). | Set up Murmur | — (footnote: "Takes about 2 minutes plus the model download") | — |
| 1 | `ob-mic` | Let Murmur hear you | Used only while the bubble is listening. Recording starts only on tap or hold. Audio is processed on the phone and kept with history for 7 days. | Allow microphone | — (footnote: "Android will ask. Choose 'While using the app'.") | `RECORD_AUDIO` runtime dialog. If it is denied twice, the button becomes "Open app settings". |
| 2 | `ob-access` | Turn on Murmur in Accessibility | Android lets only accessibility services draw over other apps and type into them. Used for: showing the bubble, typing the text, and reading the screen *while you dictate* for names and terms. A "next screen" box gives the path: Installed apps → Murmur → Use Murmur. | Open Accessibility settings | — (footnote: "Murmur never sends screen text or keystrokes anywhere.") | `Settings.ACTION_ACCESSIBILITY_SETTINGS` |
| 2a | `ob-restricted` | Android blocked this setting | Shown when we return from settings and the service is still off, and the APK was sideloaded. Three numbered steps: App info → ⋮ → Allow restricted settings → back → Use Murmur. | Open App info | I've done this | `ACTION_APPLICATION_DETAILS_SETTINGS` |
| 2b | `ob-overlay` | Allow the bubble to start the microphone | **Conditional** (section 5.1). Shown only if the on-device check fails. | Open setting | — | `ACTION_MANAGE_OVERLAY_PERMISSION` |
| 3 | `ob-notif` | Keep one quiet notification | Android requires a visible notification while an app can use the mic in the background. It's silent and has "Hide 1 h". A preview of the actual notification is shown. | Allow notifications | Not now | `POST_NOTIFICATIONS` |
| 4 | `ob-battery` | Let Murmur stay ready | Samsung puts unused apps to sleep, and then the bubble disappears. Models load only while you dictate, so idle battery use stays near zero. Next screen: Battery → Unrestricted. | Allow background use | Skip for now | `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; on One UI the "Unrestricted" label path is **UNVERIFIED** |
| 5 | `ob-model` | Download the models | They run on the phone, and this is the only time Murmur uses the internet. Packs are offered as radio rows with total sizes, plus a "Download on Wi-Fi only" toggle (on). | Download 1.7 GB | — (footnote: free space; "switch any time in Models") | — |
| 5a | `ob-downloading` | Downloading | You can leave; we notify you when done. Big percentage, bytes, speed, ETA, and a per-file checklist including "Check files and prepare for the NPU". | Continue while downloading | Pause | Download runs in WorkManager with a progress notification |
| 5b | `ob-dlerror` | Download paused | Banner: "No internet connection. 0.78 of 1.69 GB is saved…". Speech already works; cleanup turns on when it finishes. | Try again | Continue without cleanup | — |
| 6 | `ob-ready` | Try it here | A practice field with an in-app bubble. After the first dictation: "✓ Inserted in 1.3 s · <ASR> → <LLM>". | Done | — | — |

Pack contents in the prototype (Qwen3-ASR 0.6B + LFM3 1.7B = 1.69 GB; GigaAM v3 + Qwen3.5 0.8B = 0.72 GB; Qwen3-ASR only = 0.59 GB) are **placeholders**. The default pack is the ML team's call; sizes come from the chosen artifacts.

Skipping: Notifications and Background use can be skipped. Microphone and Accessibility can't be skipped, because the app doesn't work without them; Back on those steps exits setup. Every skipped or lost permission later shows as a banner and a "Permissions" row in Settings.

### 6.2 Dashboard (`dash`, `dash-empty`, `dash-loading`, `dash-off`, `dash-dark`)

- **Cover layout**, from top to bottom:
  - Header "Dashboard" plus a range chip "Last 7 days". The only other option is "Today"; history only covers 7 days, so longer ranges would mislead.
  - Today card
  - "Last 7 days" bar chart
  - "How it's going" metric list
  - Languages
  - Where you dictate
- **Inner layout:** two columns, each min-width 0, 28 dp gap. Left: Today, chart, Languages. Right: metrics, apps.

| Element | Definition (computed on device from history records and daily aggregates) | Display |
|---|---|---|
| Today, words | Words in the final text of today's Inserted and Copied entries. Failed entries count 0. | display number plus "words" |
| Speaking minutes | Sum of speech duration (VAD) for today; recording duration when there is no VAD | "9 min of speaking" |
| Time saved (estimate) | `words / typingWpm − speakingMinutes`, with typingWpm defaulting to 40. Tapping the card opens a sheet to set it (30/40/50/60/custom). The basis is always written out. | "Typing this at 40 wpm would take about 32 min, so roughly **23 min saved**." Hidden if ≤ 0. |
| Last 7 days | Words per local day. Today is the accent bar; the other days are surface3. Only the max value is labelled. The total is in the header. | 7 bars, 140 dp high; the chart has a `contentDescription` listing all values |
| Speaking pace | Median over 7 days of words ÷ speech minutes, per dictation, excluding dictations < 3 s | "142 wpm" |
| Ready after you stop | Median time from the stop tap to the text being inserted or copied, with the 90th percentile in the subtitle | "1.4 s · 9 in 10 under 2.6 s" |
| Typed into the app | Inserted ÷ (Inserted + Copied). The subtitle names the app where most copy-only cases happened. | "94% · 4 copied only, all in Termux" |
| Changed by cleanup | Word-level edit distance raw→final ÷ raw words. Fillers are counted from deletions that match the filler lexicon; fixes are the other substitutions. | "9% of words · 286 filler words removed, 54 fixes" |
| Languages | Per-dictation language ID. "Mixed" means at least 2 tokens of the minority language. | stacked bar plus legend (ink / ink4 / accent) |
| Where you dictate | Words per app, top 5 with bars, then "N other apps · words" | rows with an app icon |

- **States:**
  - Empty: the same layout with a 0 in the Today card, the explanation "counted on this phone and never sent anywhere", a "Try a dictation" button, and the metric names with "—".
  - Loading: skeleton blocks in the same geometry, shimmer 1.4 s.
  - Error (stats DB unreadable): the info banner "Couldn't read your stats. History is not affected." with Retry.
  - Permission lost: a red banner at the top (`dash-off`).
- **Stats retention:** daily aggregates (numbers only, no text) are kept after history is deleted. The period is an owner decision (see open questions); 90 days is proposed.

### 6.3 History (`hist*`), the main tab

**List (cover, and the left pane on the inner display at 300 dp):**
- Header "History" with a search button.
- Retention line: clock icon plus "Dictations stay on this phone for 7 days, then delete automatically." (The text follows the setting: "for 3 days", "for 1 day".)
- **Day headers:**
  - Labels: "Today", "Yesterday", then the weekday and date ("Monday, 16 November").
  - The right side shows the count ("6 dictations").
  - The oldest day shows "Deletes tonight" in warn with a clock icon instead of the count.
- **Entry row:**
  - 12/14 dp padding, 20 dp side gutter, dividers between rows.
  - Meta line: app icon (20 dp), app name, and for messengers the conversation title when the accessibility window title gives it (e.g. "Telegram · Дима Соколов"). Then a flexible space, the status only if it isn't Inserted (`Copied` in warn with a clipboard icon; `Failed` in err with an alert icon), and the time in mono.
  - Text: `entry` type, 3-line clamp. Failed entries show "No speech detected. The recording is kept, so you can try again." in ink3.
- **Interactions:**
  - Tap opens the detail.
  - Long-press starts multi-select, with Copy, Share and Delete in the top bar.
  - Swipe is not used: it causes accidental deletes, and Wispr's docs show it is confusing.

**Detail** (cover: pushed screen `hist-detail`; inner: right pane):
- Top bar: back, app icon (36 dp), the title (conversation or app name) and the subtitle "Telegram · Today, 14:32 · 0:12". Actions: Share, Delete.
- Segmented control, **Final / Original / Changes**:
  - Final is the inserted text (selectable).
  - Original is the raw ASR transcript.
  - Changes is an inline diff: additions in accentInk on accentSoft, removals in ink3 with an err strikethrough, and a legend.
- Audio player (section 4).
- "Context used" table:
  - App (plus the conversation)
  - Screen text, as "182 words ›", which opens the OCR text the model saw (read-only)
  - Tone
  - Models ("Qwen3-ASR 0.6B → LFM3 1.7B")
  - Timing ("0.4 s + 0.9 s after stop")
- Retention footnote: "Deletes with its audio on Wed 25 Nov at 00:00".
- Bottom action bar: **Copy** (primary) and **Re-polish** (secondary). Re-polish re-runs cleanup with the current prompt, tone and model and stores a new Final; Original stays. For Failed entries the primary button is **Try again**, which re-transcribes the stored audio.

**Search** (`hist-search`, `hist-noresults`): the header becomes a search field with autofocus. App filter chips sit below it. Results are grouped by day, matches are highlighted with `mark` (accentSoft), and a caption says "2 results · final text and original transcripts". Search covers both Final and Original. Empty result: "No matches in the last 7 days" plus an explanation that older dictations are already deleted.

**Delete** (`hist-delete`, `hist-undo`):
- Deleting a single entry asks for confirmation: "Delete this dictation?" "The text and the recording are removed from this phone. Text already pasted into Telegram stays there." Buttons: Cancel / Delete.
- After the delete, a snackbar says "Dictation deleted, with its audio" with **Undo** (6 s). The deletion is committed when the snackbar times out.
- Multi-select delete uses the same pattern: "Delete 3 dictations?"

**Empty** (`hist-empty`): an icon tile, "Nothing here yet", "Tap the bubble in any app and speak. Each dictation shows up here with its audio for 7 days.", and a "Try a dictation" button.

**Error states:**
- The audio file is missing: the player is replaced by the line "Recording not available" and Try again is disabled.
- The database can't be read: a full-pane message "Couldn't open history" with Retry.

**Retention mechanics:** deletion runs at local midnight and on app start. Entries whose local day < today − (N−1) are deleted together with their audio files. Changing to a shorter N deletes immediately, after the sheet's warning (6.5).

### 6.4 Model hub (`models*`, `model-detail`, `bench-*`)

- **Header:** "Models". Segmented control: **Speech | Cleanup**. One-line purpose below it ("Turns your voice into text. Loaded only while you dictate."). Storage line at the bottom: "Models use 2.4 GB · 181 GB free".
- **Model row** (inside one grouped surface, 16 dp padding):
  - Left column:
    - Name (titleSm)
    - Publisher plus variant (caption)
    - Spec line in mono: "938M params · 590 MB · streaming"
    - Tags: runtime badge (`NPU`/`GPU`/`CPU`), language badges (RU, EN, "+28 · 22 dialects"), and `No Russian` when the card doesn't list Russian.
  - Right column: the state control.
  - The whole row opens the detail.

| State | Right side | Extra |
|---|---|---|
| Not downloaded | Outline pill "⤓ 485 MB" | — |
| Downloading | Tonal pause button | 4 dp progress bar plus mono "89 MB of 144 MB · 12.3 MB/s" |
| Paused or failed | Tonal resume button | Progress bar in ink4, warn line "Paused, no connection" |
| Downloaded, not selected | Radio | — |
| In use | Accent "✓ In use" pill; the row is filled accentSoft | — |
| Preparing for the NPU (first load after download) | 36 dp ring with % | "Preparing for NPU…" |
| Unavailable | "In training" badge, row at 62% opacity | Not tappable for download |

- **"None"** is the first row in Cleanup: "Insert the transcript as recognized. Fastest, no cleanup." It uses a radio. With None selected, the bubble skips Polishing.
- **Detail** (cover: pushed; inner: right pane):
  - Badges, then the headline name and a one-sentence description.
  - Fact table: Parameters, Languages (long form), Download (size and quant), Runs on, Streaming, License, Source (the HF repo id, which opens in the browser).
  - "Benchmark on this phone" with the last result grid, or a "Run benchmark" button and the line "Takes about 30 seconds. Uses two short sample recordings, in Russian and English."
  - Action bar: "Use this model" (primary) or "✓ In use", plus Delete.
  - Deleting the in-use model asks for confirmation and switches to the next downloaded model, or to None for Cleanup.
- **Benchmark flow** (`bench-run` → `bench-result`):
  - It opens in a sheet (a side sheet on the inner display).
  - Running shows a step checklist with timings: Load model / Russian sample 30 s / English sample 30 s / Unload and measure memory. There is a progress bar and a Stop button. The phone must stay awake and Murmur in the foreground.
  - Result for speech models: Faster than real time (audio seconds ÷ processing seconds, as "14×"), Load time, Peak memory, Ran on (the accelerator actually used, which can differ from the badge if it fell back), plus per-sample times and the device.
  - Result for cleanup models: Writing speed (decode tok/s), Reading speed (prefill tok/s), First word (time to first token), Peak memory, plus a plain sentence: "A 60-word dictation is cleaned up in about 1.3 s."
  - Buttons: Copy results / Done. Results are saved per model and app version.
- **Errors:**
  - Not enough storage: warn banner `models-storage`.
  - Download failed: the row state as above, plus a notification.
  - Unsupported on NPU: the runtime badge shows GPU or CPU, and the detail says "NPU not supported yet".
  - Checksum mismatch: "Download was damaged. Murmur deleted it." with Download again.

**Sample-data warning.** The numbers in the prototype are sample values, not measurements: benchmark results, which runtime each model uses, and the "Ready after stop" times. Parameter counts, language counts and licenses are taken from the HF model cards (API, 2026-11-18). Sizes are the sizes of the GGUF/ONNX/ggml files on HF and are stand-ins: the real NPU artifacts will differ.

| Model | Repo | Params (card/API) | Languages (card) | Stand-in size |
|---|---|---|---|---|
| Qwen3-ASR 0.6B | `Qwen/Qwen3-ASR-0.6B` | 938,008,576 (safetensors total) | 30 languages + 22 Chinese dialects, incl. ru, en; offline and streaming | 590 MB (Q4_K_M, `handy-computer/Qwen3-ASR-0.6B-gguf`) |
| GigaAM v3 | `ai-sage/GigaAM-v3` | "220–240M" (card text) | ru (tags ru, en) | 184 MB (e2e-RNNT Q4_K_M, `handy-computer/gigaam-v3-e2e-rnnt-gguf`) |
| T-one | `t-tech/T-one` | 71,697,827 | ru; streaming | 144 MB (`model.onnx`) |
| Parakeet TDT 0.6B v3 | `nvidia/parakeet-tdt-0.6b-v3` | 627,057,286 | 25 (European, incl. ru, en) | 485 MB (Q4_K_M) |
| Whisper Small | `openai/whisper-small` | 241,734,912 | 99 tags | 190 MB (`ggml-small-q5_1.bin`) |
| Whisper Large v3 Turbo | `openai/whisper-large-v3-turbo` | 808,878,080 | 99 tags | 574 MB (`ggml-large-v3-turbo-q5_0.bin`) |
| LFM3 1.7B | `LiquidAI/LFM3-1.7B` | 1,728,801,536 | 16, incl. ru | 1.10 GB (Q4_K_M) |
| Qwen3.5 0.8B | `Qwen/Qwen3.5-0.8B` | 873,438,784 | "201 languages and dialects" | 533 MB (Q4_K_M, unsloth) |
| Qwen3.5 2B | `Qwen/Qwen3.5-2B` | 2,274,069,824 | same | 1.28 GB (Q4_K_M, unsloth) |
| LFM2.5 1.2B Instruct | `LiquidAI/LFM2.5-1.2B-Instruct` | 1,170,340,608 | 8 (en, ar, zh, fr, de, ja, ko, es): **no ru** | 731 MB (Q4_K_M) |

### 6.5 Settings (`settings*`, `prompt*`, `tone`, `retention`)

- **Cover:** grouped lists under section labels.
- **Inner:** a 260 dp category list (Dictation, Bubble, Cleanup, History and privacy, Permissions) and a detail pane with the category title. Cleanup also shows a read-only prompt preview.

| Section | Row | Control | Default |
|---|---|---|---|
| Dictation | Languages: "Russian and English, detected per dictation" | → list with checkboxes; "Detect automatically" | ru + en |
| | Hold to talk: "Press and hold the bubble, release to finish" | switch | on |
| | Sound and vibration | → Vibration / Sound / Both / Off | Vibration |
| | Typing speed for "time saved" | → 30/40/50/60/custom | 40 wpm |
| Bubble | Show the bubble | → When typing / Always | When typing |
| | Tuck in when idle: "Shrinks to an edge tab after 4 s" | switch | on |
| | Size | → Small / Standard / Large | Standard |
| | Hidden in: "Banking apps and password fields are always excluded" | → app picker | 0 apps |
| | Reset position | button row | — |
| Cleanup | Cleanup prompt: "The instructions given to the cleanup model" | → editor; value "Default" or "Edited" | Default |
| | Tone per app | → list | see below |
| | Use what's on screen: "Reads visible text to spell names and terms right" | switch | on |
| History and privacy | Keep history | → sheet | 7 days |
| | Keep recordings: "Needed to replay and re-polish" | switch | on |
| | Works offline: "Only model downloads use the internet" | status | On |
| | Delete all history | destructive row, then a confirm dialog | — |
| Permissions | Microphone / Accessibility service / Notifications / Background use | status (Allowed in ok, Restricted in warn, Off in err) → opens the system screen | — |

"Hidden in" on the Bubble row: the prototype shows "3 apps" as sample data. A **banking-app list is not detectable** reliably, so the subtitle must only promise password fields unless engineering ships a curated package list (open question).

**Prompt editor** (`prompt`, `prompt-err`, `prompt-reset`):
- Top bar: Back, "Cleanup prompt", **Reset** (text button).
- Explanation line, then a mono editor (md radius, 1 dp line2). Variables render as accent chips inside the text.
- Below the editor: insert chips `{transcript} {app} {screen_text} {tone} {language}`.
- Validation: if `{transcript}` is missing, the editor gets a red ring and the message "Add {transcript} so the model gets your dictation.", and Save is disabled.
- "Try it" card: a spoken sample and the result for the current app and tone. **Test again** re-runs it with the loaded cleanup model; this loads the model and shows Polishing in the card.
- Reset dialog: "Reset to the default prompt?" "Your edits will be replaced. Tone per app is kept." Buttons: Cancel / Reset.
- The default prompt text shown in the prototype is a placeholder. The shipped default lives in `prompts/` and is owned by the ML/prompt docs. Version it, so "Default" always points to the latest shipped default and "Edited" is detected by comparing against the version the user started from.

**Tone per app** (`tone`):
- A list of apps (36 dp icon, name, and a tone dropdown chip), then "Add an app", then "All other apps · Neutral".
- Glossary of tones:

| Tone | Effect |
|---|---|
| Casual | Short, friendly, keeps your slang |
| Neutral | Clear and plain, as spoken |
| Formal | Full sentences, polite forms, no slang |
| Verbatim | Your exact words, punctuation fixed |

- Defaults: messengers (Telegram, WhatsApp, Messages) Casual; email (Gmail, Outlook, Samsung Email) Formal; terminals and IDEs (Termux, Acode) Verbatim; others Neutral.

**Retention sheet** (`retention`):
- "Keep history for"
- "Text and recordings are deleted at midnight once they are older than this. Dashboard numbers are kept."
- Options: 1 day / 3 days / 7 days (Default) / Don't keep history ("Text is copied and inserted, nothing is saved").
- Footnote: "Choosing a shorter period deletes older dictations right away." Choosing a shorter period opens a confirm dialog: "Delete 12 dictations older than 3 days?"

---

## 7. Copy deck (EN / RU)

The UI follows the system language. Russian strings go in `values-ru/strings.xml`. Russian plurals must use `<plurals>` (one/few/many). Avoid long Russian words in tight places: the bubble's max width is 280 dp, and the Russian strings below fit at Standard size.

| Key | English | Русский |
|---|---|---|
| tab_dashboard / history / models / settings | Dashboard / History / Models / Settings | Сводка / История / Модели / Настройки |
| bubble_ready_cd | Start dictation | Начать диктовку |
| bubble_listening | Listening | Слушаю |
| bubble_hold | Release to finish | Отпустите, чтобы закончить |
| bubble_transcribing | Transcribing | Распознаю |
| bubble_polishing | Polishing | Привожу в порядок |
| bubble_inserted | Inserted | Вставлено |
| bubble_inserted_nocleanup | Inserted, no cleanup | Вставлено без правки |
| bubble_copied | Copied. Paste it anywhere | Скопировано. Вставьте куда нужно |
| bubble_err_nospeech | Didn't catch that | Не расслышал |
| bubble_err_micbusy | Microphone is busy | Микрофон занят |
| bubble_err_nomodel | Download a speech model first | Сначала скачайте модель речи |
| bubble_err_load | Couldn't load the speech model | Не удалось загрузить модель |
| action_retry / open / undo | Retry / Open / Undo | Повторить / Открыть / Отменить |
| bubble_hide_target | Drag here to hide for 1 hour | Перетащите сюда, чтобы скрыть на час |
| bubble_hidden_snack | Bubble hidden until %1$s | Кнопка скрыта до %1$s |
| notif_ready_title | Murmur is ready | Murmur готов |
| notif_action_hide / start | Hide 1 h / Start dictation | Скрыть на час / Начать диктовку |
| ob_welcome_title | Speak in any app. Murmur types it. | Говорите в любом приложении. Murmur напечатает. |
| ob_welcome_body | Recognition and cleanup run on this phone, in Russian and English. Your voice is never uploaded. | Распознавание и правка работают на телефоне, на русском и английском. Голос никуда не отправляется. |
| ob_mic_title / cta | Let Murmur hear you / Allow microphone | Разрешите доступ к микрофону / Разрешить микрофон |
| ob_access_title / cta | Turn on Murmur in Accessibility / Open Accessibility settings | Включите Murmur в специальных возможностях / Открыть настройки |
| ob_access_privacy | Murmur never sends screen text or keystrokes anywhere. | Murmur никуда не отправляет текст с экрана и нажатия клавиш. |
| ob_restricted_title | Android blocked this setting | Android заблокировал эту настройку |
| ob_notif_title | Keep one quiet notification | Оставьте одно тихое уведомление |
| ob_battery_title | Let Murmur stay ready | Не давайте Android усыплять Murmur |
| ob_models_title / cta | Download the models / Download %1$s | Скачайте модели / Скачать %1$s |
| ob_ready_title | Try it here | Попробуйте здесь |
| hist_retention_line | Dictations stay on this phone for %1$d days, then delete automatically. | Диктовки хранятся на телефоне %1$d дней, затем удаляются автоматически. |
| hist_deletes_tonight | Deletes tonight | Удалится сегодня ночью |
| hist_count (plural) | %d dictation / %d dictations | %d диктовка / %d диктовки / %d диктовок |
| hist_status_copied / failed | Copied / Failed | Скопировано / Ошибка |
| hist_seg_final / original / changes | Final / Original / Changes | Итог / Оригинал / Правки |
| hist_context | Context used | Использованный контекст |
| hist_deletes_on | Deletes with its audio on %1$s | Удалится вместе с записью %1$s |
| hist_copy / repolish / try_again | Copy / Re-polish / Try again | Копировать / Править заново / Повторить |
| hist_delete_title | Delete this dictation? | Удалить диктовку? |
| hist_delete_body | The text and the recording are removed from this phone. Text already pasted into %1$s stays there. | Текст и запись удалятся с телефона. Текст, уже вставленный в %1$s, останется там. |
| hist_deleted_snack | Dictation deleted, with its audio | Диктовка удалена вместе с записью |
| hist_empty_title / body | Nothing here yet / Tap the bubble in any app and speak. Each dictation shows up here with its audio for 7 days. | Пока пусто / Нажмите на кнопку в любом приложении и говорите. Каждая диктовка появится здесь вместе с записью и будет храниться 7 дней. |
| hist_search_hint | Search 7 days of dictations | Поиск по диктовкам за 7 дней |
| hist_search_empty | No matches in the last 7 days | За последние 7 дней ничего не найдено |
| dash_time_saved | Typing this at %1$d wpm would take about %2$d min, so roughly %3$d min saved. | Набор этого текста со скоростью %1$d слов/мин занял бы около %2$d мин — экономия примерно %3$d мин. |
| dash_pace / ready / typed / cleanup | Speaking pace / Ready after you stop / Typed into the app / Changed by cleanup | Темп речи / Готово после остановки / Вставлено в приложение / Изменено правкой |
| dash_bubble_off | The bubble is off. Android turned off Murmur's accessibility service, usually after an update or a crash. | Кнопка выключена. Android отключил службу специальных возможностей Murmur — обычно после обновления или сбоя. |
| models_speech / cleanup | Speech / Cleanup | Речь / Правка |
| models_none_desc | Insert the transcript as recognized. Fastest, no cleanup. | Вставлять текст как распознан. Быстрее всего, без правки. |
| models_in_use / use | In use / Use this model | Используется / Использовать |
| models_no_ru | No Russian | Без русского |
| models_storage_err | Not enough space for %1$s. It needs %2$s and %3$s is free. | Недостаточно места для %1$s: нужно %2$s, свободно %3$s. |
| bench_run / title | Run benchmark / Benchmark on this phone | Запустить тест / Тест на этом телефоне |
| bench_rtf / load / mem / ran_on | Faster than real time / Load time / Peak memory / Ran on | Быстрее реального времени / Загрузка / Пик памяти / Выполнено на |
| bench_decode / prefill / ttft | Writing speed / Reading speed / First word | Скорость генерации / Скорость чтения / Первое слово |
| settings_prompt / tone / screen | Cleanup prompt / Tone per app / Use what's on screen | Инструкция для правки / Тон для приложений / Учитывать текст на экране |
| prompt_missing_var | Add {transcript} so the model gets your dictation. | Добавьте {transcript}, чтобы модель получила вашу диктовку. |
| prompt_reset_title | Reset to the default prompt? | Вернуть инструкцию по умолчанию? |
| tone_casual / neutral / formal / verbatim | Casual / Neutral / Formal / Verbatim | Разговорный / Нейтральный / Деловой / Дословно |
| retention_title | Keep history for | Хранить историю |
| retention_none | Don't keep history | Не хранить историю |

---

## 8. Accessibility

- **Touch:** ≥ 48 dp everywhere (see section 4). In the bubble, the hit area exceeds the visual for the Rest tab and the cancel button.
- **Contrast:** all text tokens are ≥ 4.5:1 on their surfaces (section 3.1). ink4 is never used for text. Status is never shown by color alone: Copied and Failed always have an icon and a word.
- **Font scale:**
  - Up to 200%, history rows grow and the clamp stays at 3 lines.
  - Metric rows stack (value under the label) when the label and value don't fit on one line.
  - Bubble labels can wrap to 2 lines, and the pill grows to 64 dp tall.
  - Bottom-nav labels are not shortened. At > 150% the nav shows labels only for the active item.
- **TalkBack:**
  - Each bubble state has a `contentDescription` and a polite live region; errors use an assertive live region (section 5.3).
  - Under TalkBack, drag-to-hide is replaced by a custom accessibility action "Hide for 1 hour" on the bubble. Tuck-in is disabled, so the bubble stays a stable 52 dp target.
  - The chart has a full text description.
  - The diff view announces "added: …, removed: …".
- **Alternatives to the bubble:** the notification action "Start dictation". A Quick Settings tile is recommended but not designed in this phase.
- **Motion:** respect system animation scale (section 3.5).
- **Haptics:** follow the Sound and vibration setting; the default is vibration only.

---

## 9. Compose implementation notes

- **Theme:** `MurmurTheme(darkTheme)` provides `MurmurColors` (all tokens) via `CompositionLocal`. Map them onto `MaterialTheme.colorScheme` so stock M3 components look right:

  | M3 role | Murmur token |
  |---|---|
  | primary | accent |
  | onPrimary | onAccent |
  | primaryContainer | accentSoft |
  | onPrimaryContainer | accentInk |
  | background | bg |
  | surface, surfaceContainerLowest | surface |
  | surfaceContainer, surfaceVariant | surface2 |
  | surfaceContainerHigh | surface3 |
  | onSurface | ink |
  | onSurfaceVariant | ink2 |
  | outline | line2 |
  | outlineVariant | line |
  | error | err |
  | errorContainer | errSoft |
  | inverseSurface | inverse |
  | inverseOnSurface | onInverse |
  | inversePrimary | inverseAccent |
  | scrim | scrim |

  Don't call `dynamicLightColorScheme`.
- **Typography:** `MurmurType` holds the styles from 3.2, as `FontFamily(Font(R.font.onest_variable, weight=…, variationSettings=FontVariation.Settings(FontVariation.weight(…))))`. Disable `includeFontPadding`.
- **Shapes:** `RoundedCornerShape` with the radius tokens. Buttons use `CircleShape`.
- **Adaptive layout:** use `currentWindowAdaptiveInfo().windowSizeClass`.
  - Compact width: `Scaffold` + `NavigationBar`, with nav-compose destinations.
  - Medium or expanded: `NavigationRail` + `ListDetailPaneScaffold` (material3-adaptive) for History and Models. Settings uses its own two-pane layout.
  - Keep the selected entry or model in a `rememberSaveable` state, so fold and unfold preserve selection.
- **Bubble:** a separate Compose `ComposeView` in the service window, hosted with a `LifecycleOwner`/`SavedStateRegistryOwner` you provide, because there is no Activity. The window is `WRAP_CONTENT` and `FLAG_NOT_FOCUSABLE` (never steal IME focus). Position the window itself, not a full-screen layer, so touches pass through elsewhere. States come from a single `StateFlow<BubbleState>` in the dictation engine.
- **Clipboard:** after insertion also set `ClipData` with `ClipDescription.EXTRA_IS_SENSITIVE = false`. On Android 13+ the system clipboard toast appears; don't duplicate it with our own toast when the state is Inserted.
- **Strings:** all copy lives in `strings.xml` (EN) and `values-ru` (RU). Numbers use `NumberFormat` for the locale (1,284 vs 1 284).

---

## 10. Prototype review log

Each pass rendered all frames at DSF 1 or 2, put them on contact sheets, inspected them by eye, and ran an automated check for overflowing nowrap text, elements outside the screen, and tap targets < 40 dp.

**Pass 1, found and fixed:**
- Nested `<button>`s in model rows broke the DOM: the inner-display model list collapsed.
- The bubble floated over chat history instead of docking above the field. Placement is now computed from the field and IME position.
- Keyboard glyphs ⇧ ⌫ ⏎ rendered as tofu in Onest. They are now drawn icons.
- The waveform overlapped the timecode.
- The detail title was truncated. The conversation is now the title and the app the subtitle.
- Inner Settings repeated all groups next to the category list. It now shows only the selected category.
- The "182 words" link was 20 dp tall.
- The "9 % edited" metric wording was unclear.

**Pass 2, found and fixed:**
- The inner notes host had no keyboard, so the bubble had nothing to dock to.
- Inner models detail pane: the list was 380 dp and left the detail 282 dp. The list is now 316 dp.
- The storage footer said "181 GB free" next to a "not enough space" banner.
- The "Hold to talk" pill clipped its label: two `style` attributes on one element.
- The bubble board had empty columns.
- A designer note had leaked into the user-visible copy of the overlay step.
- Onboarding "ready" showed step 5 as current instead of done.

**Still weak (honest list):**
1. **Cover onboarding screens have a lot of empty space** between the explanation and the pinned CTA (e.g. `ob-mic--cover`). This is deliberate (thumb reach, calm pacing), but it reads sparse. A small device-specific illustration of the next system screen per step would use the space better. Not done.
2. **Inner display list-detail is tight at 750 dp:** the History detail pane is about 362 dp and the Models detail about 346 dp. Kv rows with long values wrap to 3–4 lines (Languages). In landscape, or if the real density gives more dp, it improves. An alternative is single-pane plus a side sheet on the inner portrait display. It needs on-device judgement.
3. **The host apps in bubble frames are generic mock-ups.** The real Samsung Keyboard and real Telegram will shift the bubble's resting spot. The placement rules (5.4) are what matter.
4. **Launcher icon and notification icon are not designed.** Only the mark exists.
5. **Dark theme is designed and rendered for Dashboard, History, Settings and the listening bubble** (`*-dark`). Onboarding and Models in dark are not separately reviewed, though they use the same tokens.
6. **No landscape frames.** The rules are in section 2, but nothing is rendered.
7. **The edge-tab mark (14 dp) is small,** and its 22 dp visual looks thin in screenshots. The hit area is fine, but it may need 26 dp on device.

---

## 11. Unverified, needs the device

- The Fold7's real density and dp sizes on each display (section 2).
- Whether mic capture can start from the accessibility service without `SYSTEM_ALERT_WINDOW` on One UI 8 / Android 16 (section 5.1).
- One UI 8 labels for "Allow restricted settings" and for battery "Unrestricted".
- `FoldingFeature` access from an accessibility service.
- Samsung taskbar presence on the inner display, and how it affects bottom insets.
- Every benchmark, latency and "Runs on" value in the prototype (sample data).

## 12. Sources

- Samsung Galaxy Z Fold7 B2B spec sheet: https://image-us.samsung.com/us/smartphones/galaxy-z-fold7/pdf/Q7_B2B_Spec_Sheet_R3Lc.pdf
- Vodafone device guide (resolutions): https://deviceguides.vodafone.co.uk/samsung/galaxy-z-fold7-android-16/specifications/
- Yesviz Fold7 (inner viewport, 368 ppi): https://yesviz.com/devices/samsung-z-fold7/
- Density report: https://www.b4x.com/android/forum/posts/1032569
- Window size classes: https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes
- WindowManager.LayoutParams: https://developer.android.com/reference/android/view/WindowManager.LayoutParams
- FGS background start restrictions: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- FGS types: https://developer.android.com/develop/background-work/services/fgs/service-types
- Notification permission: https://developer.android.com/develop/ui/views/notifications/notification-permission
- Accessibility (touch targets): https://developer.android.com/guide/topics/ui/accessibility/apps
- Compose Spring constants: https://developer.android.com/reference/kotlin/androidx/compose/animation/core/Spring
- Wispr Flow docs: Android setup, navigation, snooze, bubble size, usage, retry (links in 1.1)
- Superwhisper history docs: https://superwhisper.com/docs/get-started/interface-history
- Hugging Face model API and cards for every model in 6.4 (`https://huggingface.co/api/models/<repo>`)
- Google Fonts: Onest (OFL, Cyrillic) https://fonts.google.com/specimen/Onest, JetBrains Mono https://fonts.google.com/specimen/JetBrains+Mono

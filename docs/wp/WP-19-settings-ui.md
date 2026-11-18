# WP-19 Settings UI: prompt editor, tone per app, retention (`:feature:settings`)

Wave 2. Read first: `docs/wp/README.md`; DESIGN.md §6.5 (all), §7 copy deck; frames `settings*.png`, `prompt*.png`,
`tone*.png`, `retention*.png`; `prompts/README.md` ("Rules for editing the prompt", user customisation);
`docs/ml/CONTEXT_AND_PROMPT.md` §7.5; ARCHITECTURE.md §3, §13; DECISIONS ADR-019, ADR-020, ADR-046, ADR-062.

## Goal

Every setting of DESIGN 6.5 with correct defaults, a prompt editor that improves the cleanup without breaking it,
tone per app, retention with its warnings, permissions status — cover and inner layouts.

## Owns

`feature/settings/**`.

## Contracts

Consume `SettingsDeps` (settings repository, prompt assets, renderer, catalog, history repository for "Delete all"
and the retention confirmation count, service-connected flow, `tryCleanup(sample, override, preferences):
Outcome<String>`, `retention(days): Int` (WP-12's immediate run), `apps: AppInfoProvider`, `permissions:
PermissionMonitor`). Write only through `SettingsRepository.update` (the orchestrator observes prompt, preferences,
unit and model changes and rebuilds the prefix snapshot itself). Validation and scope of the edited prompt:
`PromptEditRules` (`validateSystem`, `overrideApplies`; `core/prompt`).

## Deliver

1. Sections Dictation / Bubble / Cleanup / History and privacy / Permissions with the controls and defaults of
   DESIGN 6.5 (cover: grouped lists; inner: 260 dp categories + detail pane; Cleanup shows a read-only prompt
   preview).
2. Prompt editor (ADR-019): mono editor with the shipped system prompt of the active profile
   (`PromptAssets.systemPrompt(profile.system)`) or the user's override; "My preferences" field (≤ 1,000 chars);
   validation messages (empty, too long, markup); "Default"/"Edited" value; Reset dialog ("Your edits will be
   replaced. Tone per app is kept."); "Try it" card that renders and runs the sample via `tryCleanup` with the unsaved
   text (shows Polishing; an Err shows "No cleanup model downloaded"). When the active cleanup model uses a fine-tune
   profile (`finetuned.*`) the editor is read-only with the note "This model was trained on its own prompt"
   (ADR-046); when `promptOverride.baseProfile` ≠ the active profile, the notice "Your edit was made for an older
   prompt" offers Reset / Keep editing (no frame: banner component). Instead of DESIGN's variable chips, a read-only
   "What the model also receives" panel listing Style / screen text / text before the cursor / transcript (no frame;
   report the deviation for the design owner).
3. Tone per app (ADR-020): list of apps (real icons), tone dropdown (Casual / Neutral / Formal / Verbatim), "Add an
   app", "All other apps · Neutral", defaults per category (messengers Casual, email Formal, terminals Verbatim);
   mark named tones as preview while `prompts/` has no texts for them.
4. Retention sheet: 1 / 3 / 7 (Default) / Don't keep history; shortening → "Delete 12 dictations older than 3 days?"
   (count from the repository; "Don't keep history" counts everything including today) → update +
   `deps.retention(days)`.
5. "Hidden in" app picker (`deps.apps.launcherApps()`, labels and icons from `deps.apps`), Reset position, size,
   tuck-in, show always, hold to talk, feedback (Vibration / Sound / Both / Off; Sound plays nothing until the design
   owner delivers earcons, ADR-062), languages, typing speed, keep recordings, works offline (status), delete all
   history, permissions rows from `deps.permissions` that open the system screens; debug builds: "Use fake engines"
   (`Settings.debugFakeEngines`), "Timeout scale" and "Ignore memory gate" (`debugTimeoutScale`,
   `debugIgnoreMemoryGate`).

## Acceptance (checkable here)

- View-model tests with fakes: every setting writes the right field; validation; Edited detection; reset keeps tones;
  fine-tune profile → editor read-only; older-prompt notice; retention shortening asks with the right count and calls
  `retention(days)`; "Don't keep history" stops saving (setting value 0) and deletes today's entries; "Try it" passes
  the unsaved override and preferences.
- Compose UI tests on cover and inner: `settings`, `settings-off`, `settings-dark`, `prompt`, `prompt-err`,
  `prompt-reset`, `tone`, `retention` screenshots vs `docs/design/screens/`.

## Out of scope

Applying settings (each consumer reads them), prompt wording (ML), retention execution (WP-12).

## Build/test

`tools/gw :feature:settings:testDebugUnitTest :feature:settings:assembleDebugAndroidTest`.

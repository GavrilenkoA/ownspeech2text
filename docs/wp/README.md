# Work packages: index and common rules

Read this page, then your WP file, then the sections of `docs/ARCHITECTURE.md` your WP names. `docs/BRIEF.md`
(owner requirements) wins over everything; `docs/DECISIONS.md` explains every resolved conflict.

## Index

| Wave | WP | Title | Owns (write only here) |
|---|---|---|---|
| 1 | [WP-01](WP-01-design-system.md) | Design system | `ui/design/**` |
| 1 | [WP-02](WP-02-a11y-context-insertion.md) | Accessibility service, screen capture, insertion | `platform/a11y/**` |
| 1 | [WP-03](WP-03-bubble-overlay.md) | Bubble overlay | `platform/overlay/**` |
| 1 | [WP-04](WP-04-prompt-renderer-guard.md) | Prompt renderer + output guard (Kotlin ports) | `core/prompt/**` |
| 1 | [WP-05](WP-05-inference-ipc.md) | Inference IPC (binder clients, process lifecycle) | `infer/api/**` |
| 1 | [WP-06](WP-06-asr-engine.md) | ASR engine: sherpa-onnx, VAD, segmenter | `infer/asr/**` except `…/asr/ocr/**`; `native/sherpa/**`; `core/audio/src/*/kotlin/app/murmur/core/audio/Segmenter*.kt`; `tools/device/asr_npu_smoke.sh` |
| 1 | [WP-07](WP-07-ocr-engine.md) | OCR engine + parity with ml/ocr | `core/ocr/**`; `infer/asr/src/*/kotlin/app/murmur/infer/asr/ocr/**` |
| 1 | [WP-08](WP-08-context-assembly.md) | ctx v2 assembly + privacy rules | `core/context/**` |
| 1 | [WP-09](WP-09-audio-capture-storage.md) | Audio capture, mic FGS, history audio | `platform/audio/**`; `core/audio/**` except `Segmenter*.kt` |
| 1 | [WP-10](WP-10-llm-engine.md) | LLM engine: llama.cpp + ggml-hexagon, prefix cache | `infer/llm/**`; `native/llama/**`; `tools/device/llm_npu_smoke.sh` |
| 1 | [WP-11](WP-11-dictation-orchestrator.md) | Dictation state machine, orchestrator, delivery | `core/dictation/**` |
| 1 | [WP-12](WP-12-data-history-stats.md) | History, metrics, settings, retention | `data/db/**`; `core/store/**` |
| 1 | [WP-13](WP-13-catalog-downloads.md) | Catalog, downloads, storage, custom models | `data/models/**`; `core/catalog/**` |
| 1 | [WP-14](WP-14-benchmark.md) | Benchmark runner | `core/benchmark/**` |
| 2 | [WP-15](WP-15-app-shell-onboarding.md) | App shell, navigation, onboarding | `app/src/main/kotlin/app/murmur/{MainActivity,AppShell}.kt`, `app/src/main/res/**`, `feature/onboarding/**` |
| 2 | [WP-16](WP-16-history-ui.md) | History UI | `feature/history/**` |
| 2 | [WP-17](WP-17-dashboard-ui.md) | Dashboard UI | `feature/dashboard/**` |
| 2 | [WP-18](WP-18-model-hub-ui.md) | Model hub UI + benchmark sheet | `feature/models/**` |
| 2 | [WP-19](WP-19-settings-ui.md) | Settings UI | `feature/settings/**` |
| 3 | [WP-20](WP-20-integration-e2e-device.md) | Integration, end-to-end tests, device tooling | `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/app/murmur/{AppContainer,MurmurApp}.kt`, `app/src/{debug,release,test,androidTest}/**`, `testapps/**`, `tools/e2e/**`, `tools/device/murmur_app_smoke.sh`, `docs/DEVICE_CHECKLIST.md` |

Architect-owned (ask, don't edit): `settings.gradle.kts`, root `build.gradle.kts`, `gradle.properties`,
`gradle/libs.versions.toml`, `build-logic/**`, `docs/ARCHITECTURE.md`, `docs/DECISIONS.md`, `docs/wp/**`.
Read-only for everyone: `docs/research/**`, `docs/design/**`, `docs/ml/**`, `prompts/**`, `ml/**`, `spikes/**`.

## Common rules

1. **The phone is not available.** Every acceptance criterion is checked on this server: JVM tests, the x86_64
   emulator (API 36 `google_apis`, AVDs `murmur_fold7_cover` 1080×2520 and `murmur_fold7_inner` 1968×2184), fakes
   from `:testing`, small real models. Anything only the phone can prove goes into your report as
   UNVERIFIED-ON-DEVICE and, if it needs a new step, into a note for WP-20 (`docs/DEVICE_CHECKLIST.md`).
2. **Evidence:** in your report, every claim is VERIFIED (command + decisive output), REFUTED, or
   UNVERIFIABLE-HERE (what is missing). "Documentation says so" is not evidence.
3. **Memory and processes:** this session lives in an 8 GB cgroup shared by all agents. Gradle only through
   `tools/gw <tasks>` (serialised, own 16 GB scope). Anything else > 1 GB RSS (emulator, native builds, python
   inference) through `tools/heavy <cmd>` (`MURMUR_MEM=24G` to raise). At most 6 build threads (`--max-workers=6`).
4. **Emulator:** one at a time per engineer; use a free console port 5560-5589 (check `adb devices` first), start
   with `-read-only` when another instance of the same AVD runs, stop it when done (`adb -s emulator-NNNN emu kill`).
   Never touch other people's emulators, containers, ports or files. Name docker containers `murmur-*`.
5. **Disk:** scratch under `.cache/spikes/<wp-id>/` (gitignored), < 12 GB, delete large intermediates, write how to
   regenerate them in your report. Models for emulator tests go under `.cache/models/` (shared, read-only once
   downloaded; download with the catalog URLs and check sha256).
6. **No secrets exist here** (no HF token, no Qualcomm account). Public downloads only. If something needs a
   token, an account, a GPU or the phone: stop and list it under needsFromOwner.
7. **Contracts:** the public types in `core/*` and `infer/api` are shared. You may add members to contracts you
   own; changing or removing a member others consume needs a line in your report ("contract change: …") so that the
   architect records an ADR. Never edit another WP's paths; put disagreements in your report.
8. **Dependencies:** a new library = a request to the architect (version verified to resolve, licence, reason).
9. **Design is binding (D7):** UI matches `docs/design/DESIGN.md`, `docs/design/tokens.json` and the PNGs in
   `docs/design/screens/` (`<frame>--cover.png` / `--inner.png`, rendered at 2× = 1 CSS px per dp). No redesign.
   Strings in `res/values/strings.xml` + `res/values-ru/strings.xml` (copy deck DESIGN 7); never write the product
   name literally (use `ProductName.NAME` / `%s`, D10).
10. **Privacy (D9):** no analytics, no logging of user text, screen text, OCR lines or transcripts (log codes and
    numbers only). Network only in `:data:models`.
11. **Build/test before you report:** `tools/gw assembleDebug testDebugUnitTest` must stay green for the whole
    project, plus your module's instrumented tests where your WP asks for them:
    `tools/gw :<module>:assembleDebugAndroidTest`, then
    `adb -s emulator-NNNN install -r -t <apk>` and `adb -s emulator-NNNN shell am instrument -w <test pkg>/androidx.test.runner.AndroidJUnitRunner`
    (TOOLCHAIN.md 4.4; running `connectedDebugAndroidTest` inside the 8 GB cgroup can OOM).
12. **Do not commit.** The orchestrator collects work.

## Report format (what you hand back)

Decisions first, then: files written, `tools/gw …` commands with their final lines, VERIFIED/REFUTED/UNVERIFIABLE
table, contract changes, needsFromOwner, notes for WP-20's device checklist, what you deleted and how to regenerate it.

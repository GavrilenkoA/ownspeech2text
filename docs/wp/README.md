# Work packages: index and common rules

Read this page, then your WP file, then the sections of `docs/ARCHITECTURE.md` your WP names. `docs/BRIEF.md`
(owner requirements) wins over everything; `docs/DECISIONS.md` starts with the owner decisions **D1-D12** (verbatim)
and explains every resolved conflict (ADR-nnn) and every review finding ("Review responses"). Device steps are
**DC1-DC10** (`docs/DEVICE_CHECKLIST.md`). Open questions for the owner, with recommendations: `docs/OWNER_QUESTIONS.md`.

## Index

| Wave | WP | Title | Owns (write only here) |
|---|---|---|---|
| 1 | [WP-01](WP-01-design-system.md) | Design system | `ui/design/**` |
| 1 | [WP-02](WP-02-a11y-context-insertion.md) | Accessibility service, screen capture, insertion | `platform/a11y/**` |
| 1 | [WP-03](WP-03-bubble-overlay.md) | Bubble overlay + feedback | `platform/overlay/**` |
| 1 | [WP-04](WP-04-prompt-renderer-guard.md) | Prompt renderer + output guard (Kotlin ports) | `core/prompt/**` |
| 1 | [WP-05](WP-05-inference-ipc.md) | Inference IPC (binder clients, process lifecycle) | `infer/api/**` |
| 1 | [WP-06](WP-06-asr-engine.md) | ASR engine: sherpa-onnx, VAD, segmenter | `infer/asr/**` except `…/asr/ocr/**` and `infer/asr/src/androidTest/assets/ocr/**`; `native/sherpa/**`; `core/audio/src/*/kotlin/app/murmur/core/audio/Segmenter*.kt`; `tools/device/asr_npu_smoke.sh` |
| 1 | [WP-07](WP-07-ocr-engine.md) | OCR engine + parity with ml/ocr | `core/ocr/**`; `infer/asr/src/*/kotlin/app/murmur/infer/asr/ocr/**`; `infer/asr/src/androidTest/assets/ocr/**` |
| 1 | [WP-08](WP-08-context-assembly.md) | ctx v2 assembly + privacy rules | `core/context/**` |
| 1 | [WP-09](WP-09-audio-capture-storage.md) | Audio capture, mic FGS, history audio, status notification | `platform/audio/**`; `core/audio/**` except `Segmenter*.kt` |
| 1 | [WP-10](WP-10-llm-engine.md) | LLM engine: llama.cpp + ggml-hexagon, prefix cache, tokenizer tests | `infer/llm/**`; `native/llama/**`; `tools/device/llm_npu_smoke.sh` |
| 1 | [WP-11](WP-11-dictation-orchestrator.md) | Dictation state machine, orchestrator, delivery, preparation, shared fakes | `core/dictation/**`; `testing/**` except `testing/src/main/kotlin/app/murmur/testing/contrib/**` |
| 1 | [WP-12](WP-12-data-history-stats.md) | History, metrics, settings, retention | `data/db/**`; `core/store/**` |
| 1 | [WP-13](WP-13-catalog-downloads.md) | Catalog, downloads, storage, device profile, unit health, custom models | `data/models/**`; `core/catalog/**` |
| 1 | [WP-14](WP-14-benchmark.md) | Benchmark runner | `core/benchmark/**` |
| 1 (early paths) → 3 | [WP-20](WP-20-integration-e2e-device.md) | Integration, end-to-end tests, device tooling | waves 1-2: `testapps/**`, `tools/e2e/**`, `tools/device/murmur_app_smoke.sh`, `app/src/androidTest/kotlin/app/murmur/e2e/**`, `docs/DEVICE_CHECKLIST.md`; wave 3 adds every `app/**` path of WP-15 except `MainActivity.kt`, `AppShell.kt`, `shell/**`, `res/**`, `src/androidTest/kotlin/app/murmur/shell/**` |
| 2 | [WP-15](WP-15-app-shell-onboarding.md) | App shell, navigation, onboarding, `:app` wiring for the screens | `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/app/murmur/**`, `app/src/main/res/**`, `app/src/{debug,release,test}/**`, `app/src/androidTest/kotlin/app/murmur/shell/**`, `feature/onboarding/**` |
| 2 | [WP-16](WP-16-history-ui.md) | History UI | `feature/history/**` |
| 2 | [WP-17](WP-17-dashboard-ui.md) | Dashboard UI | `feature/dashboard/**` |
| 2 | [WP-18](WP-18-model-hub-ui.md) | Model hub UI + benchmark sheet | `feature/models/**` |
| 2 | [WP-19](WP-19-settings-ui.md) | Settings UI | `feature/settings/**` |
| 3 | [WP-21](WP-21-npu-ports.md) | NPU ports: ORT QNN plugin EP for OCR and GigaAM | `infer/asr/src/*/kotlin/app/murmur/infer/asr/{qnnep,ocr}/**`, `core/catalog/tools/build_catalog.py`, `core/catalog/src/main/resources/catalog/catalog.json`, `native/qnn-contexts/**`, `tools/device/ort_qnn_smoke.sh` |

Shared fakes: WP-11 owns `testing/**`. Any other package that needs a new fake adds it as
`testing/src/main/kotlin/app/murmur/testing/contrib/<WpId><Name>.kt` (e.g. `Wp14ClipFixtures.kt`); changes to an
existing fake are requested from WP-11 (one-line note in your report, WP-11 answers within the wave).

Architect-owned (ask, don't edit): `settings.gradle.kts`, root `build.gradle.kts`, `gradle.properties`,
`gradle/libs.versions.toml`, `build-logic/**`, `tools/models/**`, `docs/ARCHITECTURE.md`, `docs/DECISIONS.md`,
`docs/wp/**`. Read-only for everyone: `docs/research/**`, `docs/design/**`, `docs/ml/**`, `prompts/**`, `ml/**`,
`spikes/**`, `tools/{gw,heavy,emulator}/**` (toolchain owner).

## Setup (once per package)

- **Worktree.** The orchestrator commits the architect's output, then each package works in its own git worktree:
  `git -C /home/private/projects/murmur worktree add .cache/wt/<wp-id> -b wp/<wp-id>`. `tools/gw` resolves the root
  from its own location, so each worktree has its own Gradle lock; a half-finished compile error in one package never
  breaks another's build. The orchestrator merges. `.cache/` is gitignored.
- **SDK:** `export ANDROID_HOME=/home/private/.local/share/android-sdk` in every shell (tools/gw does not set it; a fresh
  shell otherwise fails with "SDK location not found").
- **Shared caches:** models for tests come only from `tools/models/fetch <artifactId>` (flock, Range resume, sha256 check,
  atomic rename) into the main checkout's `.cache/models/<artifactId>/` (also from a worktree). Native libraries:
  pass `-Pmurmur.nativeLibsDir=/home/private/projects/murmur/.cache/native/jniLibs` from a worktree.

## Common rules

1. **The phone is not available.** Every acceptance criterion is checked on this server: JVM tests, the x86_64
   emulator (API 36 `google_apis`, AVDs `murmur_fold7_cover` 1080×2520 and `murmur_fold7_inner` 1968×2184), fakes
   from `:testing`, small real models. Anything only the phone can prove goes into your report as
   UNVERIFIED-ON-DEVICE and, if it needs a new step, into a note for WP-20 (`docs/DEVICE_CHECKLIST.md`, DCn).
2. **Evidence:** in your report, every claim is VERIFIED (command + decisive output), REFUTED, or
   UNVERIFIABLE-HERE (what is missing). "Documentation says so" is not evidence.
3. **Memory and processes:** this session lives in an 8 GB cgroup shared by all agents. Gradle only through
   `tools/gw <tasks>` (serialised per worktree, own 16 GB scope). Anything else > 1 GB RSS (emulator, native builds,
   python inference) through `tools/heavy <cmd>` (`MURMUR_MEM=24G` to raise). At most 6 build threads
   (`--max-workers=6`); at most 3 packages should run Gradle at the same moment (check `pgrep -fa GradleDaemon`).
4. **Emulator:** one at a time per engineer; use a free console port 5560-5589 (check `adb devices` first), start
   with `-read-only` when another instance of the same AVD runs, stop it when done (`adb -s emulator-NNNN emu kill`).
   LLM tests start the emulator with `-qemu -cpu host` (ADR-038) and may set `Settings.debugTimeoutScale`.
   Never touch other people's emulators, containers, ports or files. Name docker containers `murmur-*`.
5. **Disk:** scratch under `.cache/spikes/<wp-id>/` (gitignored), < 12 GB, delete large intermediates, write how to
   regenerate them in your report. Models only through `tools/models/fetch` (shared, read-only once downloaded).
6. **No secrets exist here** (no HF token, no Qualcomm account). Public downloads only. If something needs a
   token, an account, a GPU or the phone: stop and list it under needsFromOwner.
7. **Contracts:** the public types in `core/*` and `infer/api` are shared. You may add members to contracts you
   own; changing or removing a member others consume needs a line in your report ("contract change: …") so that the
   architect records an ADR. Never edit another WP's paths; put disagreements in your report.
8. **Dependencies:** a new library = a request to the architect (version verified to resolve, licence, reason).
9. **Design is binding (D7):** UI matches `docs/design/DESIGN.md`, `docs/design/tokens.json` and the PNGs in
   `docs/design/screens/` (`<frame>--cover.png` / `--inner.png`, rendered at 2× = 1 CSS px per dp). No redesign.
   Pieces without a frame (ARCHITECTURE 3 lists them) are composed from WP-01 components and sent to the design owner
   as screenshots. Strings in `res/values/strings.xml` + `res/values-ru/strings.xml` (copy deck DESIGN 7); never write
   the product name literally (use `ProductName.NAME` / `%s`, D10).
10. **Privacy (D9):** no analytics, no logging of user text, screen text, OCR lines or transcripts (log codes and
    numbers only). Network only in `:data:models`.
11. **Build/test before you report:** `tools/gw --max-workers=6 assembleDebug testDebugUnitTest` must be green for the
    whole project in your worktree (with `ANDROID_HOME` exported), plus your module's instrumented tests where your
    WP asks for them: `tools/gw :<module>:assembleDebugAndroidTest`, then `adb -s emulator-NNNN install -r -t <apk>`
    and `adb -s emulator-NNNN shell am instrument -w <test pkg>/androidx.test.runner.AndroidJUnitRunner`
    (TOOLCHAIN.md 4.4; running `connectedDebugAndroidTest` inside the 8 GB cgroup can OOM). If you touch a module's
    dependencies or anything the app packages, also run `tools/gw --max-workers=6 :app:assembleRelease` (the debug
    classpath can hide a missing dependency; the integration gate runs it, ADR-086).
12. **Do not commit to the main branch.** Commit in your worktree branch only if the orchestrator asks; the
    orchestrator merges.

## Report format (what you hand back)

Decisions first, then: files written, `tools/gw …` commands with their final lines, VERIFIED/REFUTED/UNVERIFIABLE
table, contract changes, needsFromOwner, notes for WP-20's device checklist, what you deleted and how to regenerate it.

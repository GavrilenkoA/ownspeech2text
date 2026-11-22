# Murmur for macOS

On-device dictation for the Mac, adapted from the Android Murmur in this repository. Hold a key, speak, release:
Murmur transcribes the speech, cleans it up with a small local LLM (fillers, self-corrections, punctuation,
names spelled as they appear on screen, the tone of the app you are in) and pastes it into the focused field.
No network traffic except model downloads from Hugging Face.

## Use

| Action | Default key |
|---|---|
| Push-to-talk | hold **Right Option ⌥**, release to insert |
| Hands-free | tap Right Option, speak, tap again |
| Cancel | Esc |

The key can be changed to Right Command or Fn in Settings. The menu bar icon (waveform) opens History, Models and
Settings; the pill at the bottom of the screen shows the level while recording and can be clicked to stop.

## Build and run

Requirements: Apple Silicon, macOS 14+, Command Line Tools (Xcode not needed), Homebrew.

```bash
brew install whisper-cpp llama.cpp
cd macos
make app          # build/Murmur.app (release, ad-hoc signed)
make install      # copies it to /Applications
open /Applications/Murmur.app
```

On first launch the Setup page asks for Microphone, Accessibility (hotkey, reading the field, pasting) and,
optionally, Screen Recording (screen text for names and terms), and downloads the default models
(Whisper Large v3 Turbo Q5_0, 547 MB; Qwen3.5 2B Q4_K_M, 1.2 GB) into `~/Library/Application Support/Murmur/models`.

The app is ad-hoc signed, so macOS forgets the Accessibility grant after every rebuild (it is keyed to the code
hash) while the switch in System Settings may still look on. Symptom: the pill says "Copied · allow Accessibility
to paste" and the text is only on the clipboard. Fix: `make regrant` (drops Murmur's stale entries and opens the
pane), then add Murmur again and restart it. Or sign with a stable certificate: `make app SIGN_ID="Your Certificate Name"`.

Insertion pastes into whatever has keyboard focus whenever Accessibility is granted. It does not try to decide
whether the focus is a text field: web and Electron inputs (Claude, Slack, Notion, browsers) do not report one,
and ⌘V on something that is not editable does nothing. The text is always left on the clipboard as well.

## How it maps to the Android app

| Android (docs/BRIEF.md) | macOS |
|---|---|
| Floating bubble | Global hotkey + non-activating HUD pill (never steals focus) |
| Speech: sherpa-onnx / NPU (Parakeet, GigaAM, Whisper…) | whisper.cpp in process, Metal (`WhisperEngine.swift`) |
| Cleanup: llama.cpp on the phone | `llama-server` child process on 127.0.0.1, started on demand, stopped when idle (`LlamaServer.swift`) |
| Accessibility tree of the target app | AXUIElement: app, field role, placeholder, text before the cursor, field frame (`ContextCapture.swift`) |
| Screenshot + PP-OCR | ScreenCaptureKit window capture + Vision OCR ru/en (`ScreenOCR.swift`), runs while you speak |
| Prompt: `prompts/render.py` (ctx v2, default.v1) | `PromptRenderer.swift`, byte-exact port, checked against `prompts/examples/ctxv2_*` goldens |
| Output guard: `prompts/guard.py` | `Guard.swift`, port of guard.v1.1, checked against `guard.py` on 6,341 cases |
| Insert via accessibility + clipboard | Pasteboard + synthetic ⌘V; the text also stays on the clipboard (optional restore) |
| History (1 week), Dashboard, Model hub with Benchmark, Settings | Same four screens in one window (SwiftUI) + Setup |
| Model catalog (`core/catalog`) | `Resources/catalog.json`: the same pinned LLM GGUFs + whisper.cpp ggml models, sha256-verified |

Shared with the Android app without copies: `prompts/system/*.md` and `prompts/tones/tones.v1.json` are copied into
the resource bundle at build time (`make prompts`), so a prompt release lands in both apps.

Privacy rules carried over from ctx v2: password fields are never read and never reach the LLM; password managers
(1Password, Bitwarden, Keychain Access, Passwords) get app-only context; the user can lower the context level to
"app and field type" or "nothing" and switch screen OCR off.

## Layout

```
macos/
  Package.swift                 SwiftPM, builds with Command Line Tools only
  Makefile                      prompts / build / app / install / selftest / guard-diff
  Sources/CWhisper              module map for Homebrew whisper.cpp + ggml
  Sources/MurmurCore            engines and the prompt pipeline (no UI): Text, Context, PromptRenderer, Guard,
                                Catalog + downloader, WhisperEngine, LlamaServer, Audio, ScreenOCR, Cleaner
  Sources/MurmurApp             menu bar app: hotkey, HUD, context capture, insertion, history, views
  Sources/murmur-cli            headless checks (see below)
  Support/Info.plist
  tools/guard_diff.py           Swift guard vs prompts/guard.py
```

## Verification

```bash
make selftest     # ctx v2 + full-prompt goldens (qwen3.5, lfm3) byte-exact, guard contract cases
make guard-diff   # Swift guard vs prompts/guard.py on every ctx v2 eval row x 17 output perturbations
.build/release/murmur-cli pipeline speech.wav     # whisper + cleanup on a file, with timings
.build/release/murmur-cli clean "…" ../prompts/examples/ctxv2_chat_ru.input.json
```

Measured on this machine (M-series, 48 GB) with the default models:

| Check | Result |
|---|---|
| Golden prompts (2 inputs × context + qwen3.5 + lfm3) | 6/6 byte-exact; guard contract 13/13 |
| Guard differential vs guard.py | 6,341/6,341 identical |
| EN, 4.7 s audio | ASR 1.86 s cold (incl. model load); cleanup → "What's the best way to migrate our Postgres database to version 17?" |
| RU, 5.6 s audio | ASR 1.22 s cold; cleanup fixed «гитхап в слаг» → «GitHub в Slack» |
| Cleanup with screen context (golden chat) | 1.49 s cold, 0.23 s warm (prompt prefix cached); «галеев» → «Галиев» from the screen |

Not verified here (needs a person at the Mac): the hotkey, HUD, Accessibility reading and pasting in real apps,
Screen Recording capture, microphone capture in the app, the SwiftUI screens.

Known quality issues of the default 2B cleanup model (model, not pipeline): it sometimes misreads self-corrections
("в 3 нет в 4 часа" → "в 3:40") and can drop sentence capitalisation in casual chat. LFM3 1.7B, the Android
recommendation, is one click away in Models (mind its licence).

## Next steps

1. Streaming: transcribe closed segments during long dictations (whisper.cpp VAD) so the end-of-speech latency
   stays flat for minute-long recordings.
2. Parakeet TDT 0.6B v3 and GigaAM v3 through sherpa-onnx (the Android defaults for ru+en and ru), CoreML/ANE.
3. Murmur fine-tuned cleanup models (`finetuned.v1` prompt is already wired) once published.
4. Bundle `llama-server` and the whisper/ggml dylibs into the app and notarize, to drop the Homebrew dependency.
5. Prefix KV cache per model load (`llama-server` slot save/restore) as on the phone.

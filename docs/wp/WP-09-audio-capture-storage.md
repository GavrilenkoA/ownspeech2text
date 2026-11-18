# WP-09 Audio capture, microphone FGS, history audio (`:platform:audio`, `:core:audio`)

Wave 1. Read first: `docs/wp/README.md`; `docs/research/verify/platform.md` §1.3, §3 item b, §7 (`Recorder.kt`,
`RecordingFgs.kt`); `docs/research/android-platform.md` §4 (source, Bluetooth, focus, first word); ARCHITECTURE.md
§4.3 (PCM path), §5.2 (Arming rows), §7, §14; DESIGN.md §3.5 (level bars), §5.1 (notification); DECISIONS ADR-018,
ADR-024, ADR-028. Code to lift: `spikes/platform/app/src/main/kotlin/app/murmur/spike/{Recorder,RecordingFgs}.kt`.

## Goal

Microphone audio from the first buffer, without losing the first word, to the ASR pipe, the history file and the
bubble meter; a compressed audio file per history entry that can be played back and decoded again for Retry.

## Owns

`platform/audio/**`; `core/audio/**` except `Segmenter*.kt` (WP-06).

## Contracts

Implement `AudioCapture` / `CaptureHandle` / `CaptureResult` (`core/dictation/.../Ports.kt`), `AudioStore` /
`AudioWriter` (`core/store/.../AudioStore.kt`) as `OpusAudioStore`, `LevelMeter.push` (`core/audio`).

## Deliver

1. `RecordingFgs`: `startForeground(id, notification, FOREGROUND_SERVICE_TYPE_MICROPHONE)` with
   `FOREGROUND_SERVICE_DEFERRED`, failure callback; started on press from the accessibility-service context; stopped
   when capture stops (I3). The notification channel "Murmur status" (silent, low) with actions "Hide 1 h", "Start
   dictation" (DESIGN 5.1); product name via `ProductName.NAME`.
2. `AndroidAudioCapture`: `AudioRecord(VOICE_RECOGNITION, 16 kHz, mono, PCM16)`, URGENT_AUDIO thread, 20 ms frames,
   `AudioRecordingCallback` + `isClientSilenced` → `MicSilenced`, all-zero first second → `MicSilenced`, init/state
   errors → `MicBusy`/`MicStartFailed`. `chunks` replays from sample 0 to late collectors (session buffer, ≤ 5 min),
   `levels` at 30 Hz from `LevelMeter`, `stop()` returns duration, peak, audio ref.
3. `LevelMeter`: DESIGN 3.5 (7 bars, weights, attack 40 ms, release 120 ms, flat on silence).
4. `OpusAudioStore`: Opus in Ogg via `MediaCodec("audio/opus")` + `MediaMuxer(MUXER_OUTPUT_OGG)` at ~24 kbit/s,
   `filesDir/history/audio/<session>.ogg`; decode back to 16 kHz PCM with `MediaExtractor` + `MediaCodec`; fallback
   AAC/M4A if the Opus encoder is missing (detect with `MediaCodecList`); honour `keepRecordings=false` (no file).
   Orphan cleanup: files without a history row at app start (WP-12 calls your `listAll()`; add it).
5. Audio focus (A17): request transient focus only if needed for ducking? — measure: if requesting focus is not
   needed for recording, do not request it (document the choice with evidence from the A17 bg-audio page).

## Acceptance (checkable here)

- JVM: `LevelMeterTest` (silence → all bars at minimum; a 1 kHz tone ramps with the attack constant; per-bar
  weights), PCM chunk/sample math.
- Instrumented (API 36 emulator, mic permission granted via `adb shell pm grant app.murmur android.permission.RECORD_AUDIO`):
  - capture starts within 500 ms and delivers 20 ms chunks at 50 Hz for 10 s (count = 500 ± 2); samples are near zero
    on this host (the emulator mic cannot be fed — platform spike), so the content check is a device item (D5);
  - the FGS is running only while recording (`dumpsys activity services app.murmur | grep RecordingFgs`);
  - `OpusAudioStore`: encode 30 s of a synthetic tone, the file is ≤ 120 KB, decoding returns ≥ 99 % of the samples
    with SNR ≥ 20 dB vs the input (report the codec actually used: Opus or AAC fallback, VERIFIED on the emulator);
  - `keepRecordings=false` writes nothing.
- A debug-only `WavFileCapture` (`AudioCapture` that plays a WAV from the app's files dir in real time) for WP-20's
  end-to-end test, guarded so it cannot ship in release (put it in `platform/audio/src/debug`).

## Out of scope

Bluetooth SCO routing (not designed; note findings), VAD (WP-06), history rows (WP-12).

## Build/test

`tools/gw :core:audio:test :platform:audio:testDebugUnitTest :platform:audio:assembleDebugAndroidTest`.

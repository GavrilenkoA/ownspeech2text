# WP-09 Audio capture, microphone FGS, history audio, status notification (`:platform:audio`, `:core:audio`)

Wave 1. Read first: `docs/wp/README.md`; `docs/research/verify/platform.md` §1.3, §3 item b, §7 (`Recorder.kt`,
`RecordingFgs.kt`); `docs/research/android-platform.md` §4 (source, Bluetooth, focus, first word) and the
"Phone call starts (MODE_IN_CALL) → STOPPING" row near line 758; ARCHITECTURE.md §4.3 (PCM path), §4.4 (call / mic
rows), §5.2 rows 11, 13, 18, §7, §14; DESIGN.md §3.5 (level bars), §5.1 (notification), §5.4 ("Show now"), §6.1
`ob-notif`; DECISIONS ADR-018, ADR-024, ADR-028, ADR-053, ADR-055, ADR-061. Code to lift:
`spikes/platform/app/src/main/kotlin/app/murmur/spike/{Recorder,RecordingFgs}.kt`.

## Goal

Microphone audio from the first buffer, without losing the first word, to the ASR pipe, the history file and the
bubble meter; mid-recording problems reported as typed signals; a compressed audio file per history entry that can be
played back and decoded again for Retry; the persistent "Murmur is ready" notification.

## Owns

`platform/audio/**`; `core/audio/**` except `Segmenter*.kt` (WP-06).

## Contracts

Implement `AudioCapture.start(session, keepRecording, audioKey)` / `CaptureHandle` (`chunks`, `levels`, `signals`,
`stop`, `cancel`) / `CaptureResult` / `CaptureSignal` (`core/dictation/.../Ports.kt`), `AudioStore` / `AudioWriter`
(`newWriter(key)`, `delete`, `readPcm`, `exists`, `listAll`; `core/store/.../AudioStore.kt`) as `OpusAudioStore`,
`StatusNotifier` (`show(modelsLine, hiddenUntilEpochMs)`, `hide()`) as `AndroidStatusNotifier`, `LevelMeter.push`
(`core/audio`). File names come from `AudioKey` (`<createdAtEpochMs>-<session>`, `core/model/Ids.kt`), never the bare
session id (ADR-053).

## Deliver

1. `RecordingFgs`: `startForeground(id, notification, FOREGROUND_SERVICE_TYPE_MICROPHONE)` with
   `FOREGROUND_SERVICE_DEFERRED`, failure callback; started on press from the accessibility-service context; stopped
   when capture stops (I3). Its notification only says that the microphone is on (stop action).
2. `AndroidStatusNotifier` (ADR-055): channel "Murmur status" (silent, low), an ongoing plain notification (not an
   FGS) "Murmur is ready" with the models line as subtitle, actions "Hide 1 h" and "Start dictation"
   (→ `UserInput.NotificationStart`), and "Show now" while `hiddenUntilEpochMs` is in the future; product name via
   `ProductName.NAME`. WP-15 shows/hides it (service connected and notification permission granted) and updates it.
3. `AndroidAudioCapture`: `AudioRecord(VOICE_RECOGNITION, 16 kHz, mono, PCM16)`, URGENT_AUDIO thread, 20 ms frames,
   init/state errors → `MicBusy`/`MicStartFailed`; at start `isClientSilenced` or an all-zero first second →
   `MicSilenced`. Mid-recording `signals`: `AudioRecordingCallback` silenced → `Silenced`; read error / device lost →
   `Lost(error)`; `AudioManager` mode change to `MODE_IN_CALL` / `MODE_IN_COMMUNICATION`
   (`OnModeChangedListener`, API 31) → `CallStarted`. `chunks` replays from sample 0 to late collectors (session
   buffer, ≤ 5 min), `levels` at 30 Hz from `LevelMeter`, `stop()` returns duration, peak, audio ref.
4. `LevelMeter`: DESIGN 3.5 (7 bars, weights, attack 40 ms, release 120 ms, flat on silence).
5. `OpusAudioStore`: Opus in Ogg via `MediaCodec("audio/opus")` + `MediaMuxer(MUXER_OUTPUT_OGG)` at ~24 kbit/s,
   `filesDir/history/audio/<audioKey>.ogg`; decode back to 16 kHz PCM with `MediaExtractor` + `MediaCodec`; fallback
   AAC/M4A if the Opus encoder is missing (detect with `MediaCodecList`); honour `keepRecordings=false` (no file);
   `listAll()` for WP-12's orphan cleanup.
6. Audio focus (A17) decision, made here with evidence: request no focus unless recording requires it on API 36
   (measure on the emulator: does recording continue while another app plays; record the result). "Pause media while
   dictating" is not designed: report, do not build.
7. A debug-only `WavFileCapture` (`AudioCapture` that plays a WAV from the app's files dir in real time, with a hook
   to emit a `CaptureSignal`) for WP-20's end-to-end test, in `platform/audio/src/debug` so it cannot ship.

## Acceptance (checkable here)

- JVM: `LevelMeterTest` (silence → all bars at minimum; a 1 kHz tone ramps with the attack constant; per-bar
  weights), PCM chunk/sample math.
- Instrumented (API 36 emulator, mic permission granted via `adb shell pm grant app.murmur android.permission.RECORD_AUDIO`):
  - capture starts within 500 ms and delivers 20 ms chunks at 50 Hz for 10 s (count = 500 ± 2); samples are near zero
    on this host (the emulator mic cannot be fed — platform spike), so the content check is a device item (DC5);
  - the FGS is running only while recording (`dumpsys activity services app.murmur | grep RecordingFgs`), the status
    notification independently of it (`dumpsys notification --noredact | grep -A3 app.murmur`);
  - `adb emu gsm call 5551234` while recording → `CaptureSignal.CallStarted` within 1 s (report the AudioManager mode
    seen; if the emulator does not change the mode, report it and test the listener with a fake mode source);
  - `OpusAudioStore`: encode 30 s of a synthetic tone, the file is ≤ 120 KB, decoding returns ≥ 99 % of the samples
    with SNR ≥ 20 dB vs the input (report the codec actually used: Opus or AAC fallback, VERIFIED on the emulator);
    two writers with the same session id but different creation times produce two files;
  - `keepRecordings=false` writes nothing.

## Out of scope

Bluetooth SCO routing (not designed; note findings), VAD (WP-06), history rows (WP-12), mapping signals to states
(WP-11), when to show the status notification (WP-15).

## Build/test

`tools/gw :core:audio:test :platform:audio:testDebugUnitTest :platform:audio:assembleDebugAndroidTest`.

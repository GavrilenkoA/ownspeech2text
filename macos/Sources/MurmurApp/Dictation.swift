import AppKit
import MurmurCore

enum DictationPhase: Equatable {
    case idle
    case recording(since: Date)
    case processing(String)
    case done(String, ok: Bool)
}

/// The dictation flow of docs/BRIEF.md on macOS:
/// key down -> read the focused field (Accessibility) -> screenshot + OCR in the background -> preload whisper + LLM ->
/// record; key up / second tap -> whisper -> guard.precheck -> LLM cleanup -> guard -> paste + clipboard -> history.
@MainActor
final class Dictation: ObservableObject {
    static let shared = Dictation()

    @Published private(set) var phase: DictationPhase = .idle
    @Published private(set) var level: Float = 0
    @Published var lastError: String?

    let whisper = WhisperEngine()
    let llm = LlamaServer()
    private let recorder = AudioRecorder()
    private var focus = FocusSnapshot()
    private var screenTask: Task<(CtxV2.Screen, [Int]?)?, Never>?
    private var startedAt = Date()
    private var resultTimer: Timer?

    private init() {
        recorder.onLevel = { [weak self] l in Task { @MainActor in self?.level = l } }
    }

    var isRecording: Bool { if case .recording = phase { return true }; return false }
    var isBusy: Bool { if case .processing = phase { return true }; return false }

    func toggle() { isRecording ? stop() : start() }

    func start() {
        guard !isRecording, !isBusy else { return }
        let s = Settings.shared
        guard let asr = s.asrModel, asr.isInstalled else {
            show(.done("Download a speech model first (Murmur → Models)", ok: false)); MainWindow.shared.show(tab: .models); return
        }
        guard Permissions.microphone else {
            Permissions.requestMicrophone { _ in }
            show(.done("Allow microphone access", ok: false)); return
        }
        focus = Permissions.accessibility ? ContextCapture.snapshot() : FocusSnapshot()
        if focus.appName == nil, let app = NSWorkspace.shared.frontmostApplication {
            focus.appName = app.localizedName; focus.bundleID = app.bundleIdentifier; focus.pid = app.processIdentifier
        }
        do { try recorder.start() } catch {
            show(.done(error.localizedDescription, ok: false)); return
        }
        startedAt = Date()
        phase = .recording(since: startedAt)
        HUD.shared.show()
        Sounds.play("Tink")

        // Everything else loads while the user speaks.
        whisper.idleUnloadSeconds = Double(s.asrIdleMinutes * 60)
        whisper.preload(asr.mainFile.path)
        if let m = s.llmModel, m.isInstalled, !focus.isPassword {
            llm.idleUnloadSeconds = Double(s.llmIdleMinutes * 60)
            let path = m.mainFile.path
            Task.detached { [llm] in try? await llm.ensureStarted(model: path) }
        }
        let wantScreen = s.contextLevel == "full" && s.screenOCR && !focus.isPassword && !focus.denylisted && s.llmModel != nil
        let pid = focus.pid, frame = focus.fieldFrame
        screenTask = wantScreen ? Task.detached { await ContextCapture.screen(pid: pid, field: frame) } : nil
    }

    func cancel() {
        guard isRecording else { return }
        recorder.stop()
        screenTask?.cancel()
        show(.done("Cancelled", ok: false))
    }

    func stop() {
        guard isRecording else { return }
        let samples = recorder.stop()
        let stopAt = Date()
        Sounds.play("Pop")
        let audioSeconds = Double(samples.count) / 16000
        guard audioSeconds >= 0.3 else { show(.done("Too short", ok: false)); return }
        phase = .processing("Transcribing…")
        let s = Settings.shared
        let asr = s.asrModel!
        let lang = s.language
        let vocab = s.vocabulary
        let cfg = s.cleanupConfig
        let level = s.contextLevel
        let focus = self.focus
        let screenTask = self.screenTask
        let autoInsert = s.autoInsert, restore = s.restoreClipboard, keepAudio = s.keepAudio

        Task.detached { [whisper, llm] in
            var item = HistoryItem(appName: focus.appName, bundleID: focus.bundleID, category: focus.category,
                                   transcript: "", text: "", action: "empty", reason: "")
            item.audioSeconds = audioSeconds
            item.asrModel = asr.id
            item.llmModel = cfg.model?.id
            item.promptVersion = cfg.model?.promptProfile
            do {
                let r = try whisper.transcribe(samples, modelPath: asr.mainFile.path, language: lang, prompt: vocab.isEmpty ? nil : vocab)
                item.transcript = r.text
                item.language = r.language
                item.asrSeconds = r.seconds
                if cfg.model != nil { await MainActor.run { Dictation.shared.phase = .processing("Cleaning up…") } }
                // OCR has had the whole recording to finish; do not wait long for it.
                let screen = await Self.withTimeout(1.5) { await screenTask?.value }
                let ctx = ContextCapture.build(focus, screen: screen ?? nil, language: r.language, level: level)
                item.screenLines = ctx.screen?.lines?.count ?? 0
                let c = await Cleaner.run(transcript: r.text, ctx: ctx, config: cfg, llm: llm)
                item.text = c.text
                item.action = c.action
                item.reason = c.reason
                item.llmSeconds = c.llmSeconds
            } catch {
                item.action = "fallback"
                item.reason = "asr_error: \(error.localizedDescription)"
            }
            if keepAudio {
                let name = "\(item.id).wav"
                try? WAV.write(samples, to: Paths.history.appendingPathComponent(name))
                item.audioFile = name
            }
            let final = item
            await MainActor.run { Dictation.shared.finish(final, stopAt: stopAt, autoInsert: autoInsert, restore: restore) }
        }
    }

    private func finish(_ item: HistoryItem, stopAt: Date, autoInsert: Bool, restore: Bool) {
        var item = item
        guard !item.text.isEmpty else {
            item.totalSeconds = Date().timeIntervalSince(stopAt)
            if !item.transcript.isEmpty || item.reason.hasPrefix("asr_error") { HistoryStore.shared.add(item) }
            show(.done(item.reason.hasPrefix("asr_error") ? "Speech model error" : "Nothing heard", ok: false))
            return
        }
        // Paste whenever we may post keystrokes. The focused-element role is not a reliable gate: Electron/web
        // inputs (Claude, Slack, Notion, browsers) report AXWebArea/AXGroup, and ⌘V on a non-editable view is a no-op.
        let trusted = Permissions.accessibility
        let canPaste = autoInsert && trusted
        if canPaste {
            Inserter.paste(item.text, restoreClipboard: restore)
            item.inserted = true
        } else {
            Inserter.copy(item.text)
            item.insertNote = !autoInsert ? "auto_insert_off" : "no_accessibility"
        }
        item.totalSeconds = Date().timeIntervalSince(stopAt)
        HistoryStore.shared.add(item)
        let note = item.action == "accept" ? "" : item.action == "fallback" ? " · raw transcript" : ""
        if canPaste {
            show(.done("Inserted" + note, ok: true))
        } else if autoInsert {
            // Without Accessibility macOS drops synthetic ⌘V: say so instead of a silent "copied".
            show(.done("Copied · allow Accessibility to paste (⌘V now)", ok: false))
            Permissions.requestAccessibility()
        } else {
            show(.done("Copied to clipboard" + note, ok: true))
        }
    }

    private func show(_ p: DictationPhase) {
        phase = p
        HUD.shared.show()
        resultTimer?.invalidate()
        resultTimer = Timer.scheduledTimer(withTimeInterval: 1.6, repeats: false) { _ in
            Task { @MainActor in
                if case .done = Dictation.shared.phase {
                    Dictation.shared.phase = .idle
                    HUD.shared.hide()
                }
            }
        }
    }

    nonisolated static func withTimeout<T: Sendable>(_ seconds: Double, _ op: @escaping @Sendable () async -> T?) async -> T? {
        await withTaskGroup(of: T?.self) { g in
            g.addTask { await op() }
            g.addTask { try? await Task.sleep(nanoseconds: UInt64(seconds * 1e9)); return nil }
            let first = await g.next() ?? nil
            g.cancelAll()
            return first
        }
    }

    /// Re-run cleanup on a history item's transcript with the current settings (History → Retry).
    func retry(_ item: HistoryItem) async -> HistoryItem {
        var it = item
        let cfg = Settings.shared.cleanupConfig
        var ctx = CtxV2(app: .init(package: item.bundleID, label: item.appName, category: item.category))
        ctx.language = .init(asr: item.language)
        let c = await Cleaner.run(transcript: item.transcript, ctx: ctx, config: cfg, llm: llm)
        it.text = c.text; it.action = c.action; it.reason = c.reason; it.llmSeconds = c.llmSeconds
        it.llmModel = cfg.model?.id
        HistoryStore.shared.update(it)
        return it
    }
}

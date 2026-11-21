import Foundation
import MurmurCore

/// Model hub state: downloads (progress, cancel), deletion and the Benchmark button (BRIEF: short stats per model).
@MainActor
final class ModelManager: ObservableObject {
    static let shared = ModelManager()
    @Published var progress: [String: Double] = [:]
    @Published var errors: [String: String] = [:]
    @Published var bench: [String: String] = [:]
    @Published var benchRunning: Set<String> = []
    @Published var revision = 0  // bumps when files change on disk

    func download(_ m: ModelEntry) {
        guard progress[m.id] == nil else { return }
        progress[m.id] = 0
        errors[m.id] = nil
        Task {
            do {
                try await ModelDownloader.shared.download(m) { p in Task { @MainActor in ModelManager.shared.progress[m.id] = p } }
            } catch {
                errors[m.id] = error.localizedDescription
            }
            progress[m.id] = nil
            revision += 1
        }
    }

    func cancel(_ m: ModelEntry) { ModelDownloader.shared.cancel(m.id) }

    func delete(_ m: ModelEntry) {
        if m.kind == "llm" { Dictation.shared.llm.stop() } else { Dictation.shared.whisper.unload() }
        try? FileManager.default.removeItem(at: m.directory)
        bench[m.id] = nil
        revision += 1
    }

    func benchmark(_ m: ModelEntry) {
        guard m.isInstalled, !benchRunning.contains(m.id) else { return }
        benchRunning.insert(m.id)
        bench[m.id] = "Running…"
        Task.detached {
            let text: String
            do {
                text = m.kind == "asr" ? try await Self.benchASR(m) : try await Self.benchLLM(m)
            } catch { text = "Failed: \(error.localizedDescription)" }
            await MainActor.run {
                ModelManager.shared.bench[m.id] = text
                ModelManager.shared.benchRunning.remove(m.id)
            }
        }
    }

    /// Synthesizes a Russian and an English phrase with `say`, then transcribes each (cold, then warm).
    nonisolated static func benchASR(_ m: ModelEntry) async throws -> String {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("murmur-bench")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let phrases = [("Milena", "Привет! Давай созвонимся завтра в четыре часа и обсудим отчёт по продажам за октябрь."),
                       ("Samantha", "What's the best way to migrate our Postgres database to version seventeen?")]
        var samples: [[Float]] = []
        for (i, (voice, text)) in phrases.enumerated() {
            let url = dir.appendingPathComponent("b\(i).aiff")
            let p = Process()
            p.executableURL = URL(fileURLWithPath: "/usr/bin/say")
            p.arguments = ["-v", voice, "-o", url.path, text]
            try p.run(); p.waitUntilExit()
            if p.terminationStatus != 0 {  // voice not installed: system default
                p.arguments = ["-o", url.path, text]
            }
            samples.append(try WAV.read(url))
        }
        let w = WhisperEngine()
        let audio = samples.reduce(0) { $0 + Double($1.count) / 16000 }
        let t0 = Date()
        let first = try w.transcribe(samples[0], modelPath: m.mainFile.path)
        let cold = Date().timeIntervalSince(t0)
        var warm = 0.0
        var outs = [first.text]
        for s in samples {
            let r = try w.transcribe(s, modelPath: m.mainFile.path)
            warm += r.seconds
            if s != samples[0] { outs.append(r.text) }
        }
        w.unload()
        return String(format: "Load + first: %.1fs · warm: %.2fs for %.1fs audio (%.0f× realtime)\n", cold, warm, audio, audio / max(warm, 0.01))
            + outs.map { "“\($0)”" }.joined(separator: "\n")
    }

    nonisolated static func benchLLM(_ m: ModelEntry) async throws -> String {
        let server = LlamaServer()
        defer { server.stop() }
        let t0 = Date()
        try await server.ensureStarted(model: m.mainFile.path)
        let load = Date().timeIntervalSince(t0)
        let transcript = "ну короче я запушил фикс в гитхаб и эээ кинул ссылку в слак нет в телеграм"
        let ctx = CtxV2(app: .init(label: "Telegram", category: "messaging"), field: .init(kind: "message"), language: .init(asr: "ru"))
        var cfg = CleanupConfig(model: m)
        cfg.preferences = nil
        let prompt = Cleaner.renderPrompt(model: m, ctx: ctx, transcript: transcript, config: cfg)
        let cold = try await server.complete(model: m.mainFile.path, prompt: prompt, transcript: transcript)
        let warm = try await server.complete(model: m.mainFile.path, prompt: prompt, transcript: transcript)
        let v = OutputGuard.guardOutput(transcript: transcript, output: warm.text, ctx: ctx)
        return String(format: "Load %.1fs · prompt %d tok at %.0f tok/s · output %.0f tok/s · request %.2fs (cached prefix %.2fs)\n",
                      load, cold.promptTokens, cold.promptPerSecond, warm.predictedPerSecond, cold.seconds, warm.seconds)
            + "“\(warm.text.trimmingCharacters(in: .whitespacesAndNewlines))” — guard: \(v.action.rawValue)"
    }
}

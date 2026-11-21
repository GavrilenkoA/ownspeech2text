import CWhisper
import Foundation

/// whisper.cpp in process (Metal). Loaded on demand, freed after an idle timeout (BRIEF: models do not hold RAM 24/7).
public final class WhisperEngine: @unchecked Sendable {
    public struct Result: Sendable {
        public let text: String
        public let language: String
        public let seconds: Double
    }

    private var ctx: OpaquePointer?
    private var loadedPath: String?
    private let queue = DispatchQueue(label: "murmur.whisper")
    private var idleTimer: DispatchSourceTimer?
    public var idleUnloadSeconds: Double = 600

    private static let backends: Void = {
        whisper_log_set({ _, _, _ in }, nil)  // keep stderr and the system log free of model output
        ggml_backend_load_all()  // Homebrew ggml loads Metal/CPU backends dynamically
    }()

    public init() { _ = Self.backends }

    deinit { if let ctx { whisper_free(ctx) } }

    public var isLoaded: Bool { queue.sync { ctx != nil } }

    /// Loads the model if needed (call at dictation start so it is ready when the user stops).
    public func preload(_ path: String) {
        queue.async { [self] in _ = try? load(path) }
    }

    private func load(_ path: String) throws -> OpaquePointer {
        if let ctx, loadedPath == path { return ctx }
        if let ctx { whisper_free(ctx); self.ctx = nil }
        var cp = whisper_context_default_params()
        cp.use_gpu = true
        cp.flash_attn = true
        guard let c = whisper_init_from_file_with_params(path, cp) else {
            throw NSError(domain: "Murmur", code: 2, userInfo: [NSLocalizedDescriptionKey: "Cannot load speech model at \(path)"])
        }
        ctx = c
        loadedPath = path
        return c
    }

    public func unload() {
        queue.async { [self] in
            if let ctx { whisper_free(ctx) }
            ctx = nil; loadedPath = nil
        }
    }

    private func armIdleTimer() {
        idleTimer?.cancel()
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + idleUnloadSeconds)
        t.setEventHandler { [weak self] in
            guard let self else { return }
            if let c = self.ctx { whisper_free(c) }
            self.ctx = nil; self.loadedPath = nil
        }
        t.resume()
        idleTimer = t
    }

    /// samples: 16 kHz mono float. language: "auto" or an ISO code. prompt: optional vocabulary hint.
    public func transcribe(_ samples: [Float], modelPath: String, language: String = "auto", prompt: String? = nil) throws -> Result {
        try queue.sync {
            let start = Date()
            let c = try load(modelPath)
            defer { armIdleTimer() }
            var p = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH)
            p.beam_search.beam_size = 3
            p.n_threads = Int32(max(2, min(8, ProcessInfo.processInfo.activeProcessorCount - 2)))
            p.print_progress = false
            p.print_realtime = false
            p.print_special = false
            p.print_timestamps = false
            p.no_timestamps = true
            p.translate = false
            p.suppress_blank = true
            p.suppress_nst = true
            p.no_context = true
            // Whisper pads every window to 30 s; pad short clips with silence ourselves so it does not hallucinate.
            var audio = samples
            if audio.count < 16000 { audio += [Float](repeating: 0, count: 16000 - audio.count) }
            let lang = strdup(language == "auto" ? "auto" : language)
            let prm = prompt.flatMap { $0.isEmpty ? nil : strdup($0) }
            defer { free(lang); if let prm { free(prm) } }
            p.language = UnsafePointer(lang)
            p.detect_language = false
            if let prm { p.initial_prompt = UnsafePointer(prm) }
            let rc = audio.withUnsafeBufferPointer { whisper_full(c, p, $0.baseAddress, Int32($0.count)) }
            guard rc == 0 else { throw NSError(domain: "Murmur", code: 3, userInfo: [NSLocalizedDescriptionKey: "whisper_full failed (\(rc))"]) }
            var text = ""
            for i in 0..<whisper_full_n_segments(c) {
                if let s = whisper_full_get_segment_text(c, i) { text += String(cString: s) }
            }
            let langID = whisper_full_lang_id(c)
            let code = langID >= 0 ? String(cString: whisper_lang_str(langID)) : (language == "auto" ? "" : language)
            return Result(text: text.trimmingCharacters(in: .whitespacesAndNewlines), language: code, seconds: Date().timeIntervalSince(start))
        }
    }
}

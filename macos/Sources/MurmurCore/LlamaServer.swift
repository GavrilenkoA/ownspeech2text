import Foundation

/// The cleanup LLM: `llama-server` (Homebrew llama.cpp, Metal) as a child process on 127.0.0.1, started on demand
/// and stopped after an idle timeout. Raw prompts go to /completion, rendered by PromptRenderer exactly as on Android
/// (greedy, n_predict = min(1024, 2 * transcript tokens + 64), stop at <|im_end|>; prompts/README.md).
public final class LlamaServer: @unchecked Sendable {
    public struct Completion: Sendable {
        public let text: String
        public let promptTokens: Int
        public let predictedTokens: Int
        public let promptPerSecond: Double
        public let predictedPerSecond: Double
        public let seconds: Double
    }

    public static func findBinary() -> String? {
        let env = ProcessInfo.processInfo.environment["MURMUR_LLAMA_SERVER"]
        let bundled = Bundle.main.url(forAuxiliaryExecutable: "llama-server")?.path
        for p in [env, bundled, "/opt/homebrew/bin/llama-server", "/usr/local/bin/llama-server"].compactMap({ $0 })
        where FileManager.default.isExecutableFile(atPath: p) { return p }
        return nil
    }

    private var process: Process?
    private var port = 0
    private var modelPath: String?
    private let lock = NSLock()
    private var idleTimer: DispatchSourceTimer?
    private var starting: Task<Void, Error>?
    public var idleUnloadSeconds: Double = 300

    public init() {}

    deinit { process?.terminate() }

    public var isRunning: Bool { lock.withLock { process?.isRunning ?? false } }

    /// Starts the server for `model` (no-op if it already serves it). Safe to call at dictation start.
    public func ensureStarted(model: String) async throws {
        let t: Task<Void, Error> = lock.withLock {
            if let p = process, p.isRunning, modelPath == model, let s = starting { return s }
            if let p = process, p.isRunning, modelPath == model { return Task {} }
            process?.terminate()
            process = nil
            let task = Task { try await self.launch(model: model) }
            starting = task
            return task
        }
        try await t.value
    }

    private func launch(model: String) async throws {
        guard let bin = Self.findBinary() else {
            throw NSError(domain: "Murmur", code: 4, userInfo: [NSLocalizedDescriptionKey: "llama-server not found. Install it with: brew install llama.cpp"])
        }
        let port = Self.freePort()
        let p = Process()
        p.executableURL = URL(fileURLWithPath: bin)
        p.arguments = ["-m", model, "--host", "127.0.0.1", "--port", String(port), "-c", "8192", "-np", "1",
                       "-ngl", "999", "--no-webui", "--log-disable", "-fa", "on"]
        let log = Paths.logs.appendingPathComponent("llama-server.log")
        FileManager.default.createFile(atPath: log.path, contents: nil)
        let h = try FileHandle(forWritingTo: log)
        p.standardOutput = h
        p.standardError = h
        try p.run()
        lock.withLock { self.process = p; self.port = port; self.modelPath = model }
        let deadline = Date().addingTimeInterval(60)
        while Date() < deadline {
            if !p.isRunning { throw NSError(domain: "Murmur", code: 5, userInfo: [NSLocalizedDescriptionKey: "llama-server exited (see \(log.path))"]) }
            if let (_, r) = try? await URLSession.shared.data(from: URL(string: "http://127.0.0.1:\(port)/health")!),
               (r as? HTTPURLResponse)?.statusCode == 200 { armIdle(); return }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        p.terminate()
        throw NSError(domain: "Murmur", code: 6, userInfo: [NSLocalizedDescriptionKey: "llama-server did not become ready"])
    }

    public func stop() {
        lock.withLock {
            process?.terminate(); process = nil; modelPath = nil; starting = nil
            idleTimer?.cancel()
        }
    }

    private func armIdle() {
        lock.withLock {
            idleTimer?.cancel()
            let t = DispatchSource.makeTimerSource(queue: .global())
            t.schedule(deadline: .now() + idleUnloadSeconds)
            t.setEventHandler { [weak self] in self?.stop() }
            t.resume()
            idleTimer = t
        }
    }

    public func tokenCount(_ text: String) async -> Int? {
        let port = lock.withLock { self.port }
        var req = URLRequest(url: URL(string: "http://127.0.0.1:\(port)/tokenize")!)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try? JSONSerialization.data(withJSONObject: ["content": text])
        guard let (d, _) = try? await URLSession.shared.data(for: req),
              let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any], let t = j["tokens"] as? [Any] else { return nil }
        return t.count
    }

    public func complete(model: String, prompt: String, transcript: String, timeout: Double = 30) async throws -> Completion {
        try await ensureStarted(model: model)
        let start = Date()
        let tTokens = await tokenCount(transcript) ?? Py.len(transcript) / 2
        // llama-server adds BOS itself when the model wants one; drop the template's literal BOS to avoid a double.
        var p = prompt
        if p.hasPrefix("<|startoftext|>") { p.removeFirst("<|startoftext|>".count) }
        let body: [String: Any] = [
            "prompt": p, "n_predict": min(1024, 2 * tTokens + 64), "temperature": 0, "top_k": 1,
            "repeat_penalty": 1.0, "cache_prompt": true, "stop": ["<|im_end|>"], "stream": false,
        ]
        let port = lock.withLock { self.port }
        var req = URLRequest(url: URL(string: "http://127.0.0.1:\(port)/completion")!)
        req.httpMethod = "POST"
        req.timeoutInterval = timeout
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (d, r) = try await URLSession.shared.data(for: req)
        armIdle()
        guard (r as? HTTPURLResponse)?.statusCode == 200, let j = try JSONSerialization.jsonObject(with: d) as? [String: Any] else {
            throw NSError(domain: "Murmur", code: 7, userInfo: [NSLocalizedDescriptionKey: "llama-server error: \(String(decoding: d.prefix(300), as: UTF8.self))"])
        }
        let timings = j["timings"] as? [String: Any] ?? [:]
        return Completion(
            text: j["content"] as? String ?? "",
            promptTokens: timings["prompt_n"] as? Int ?? 0,
            predictedTokens: timings["predicted_n"] as? Int ?? 0,
            promptPerSecond: timings["prompt_per_second"] as? Double ?? 0,
            predictedPerSecond: timings["predicted_per_second"] as? Double ?? 0,
            seconds: Date().timeIntervalSince(start))
    }

    static func freePort() -> Int {
        let s = socket(AF_INET, SOCK_STREAM, 0)
        defer { close(s) }
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")
        addr.sin_port = 0
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        _ = withUnsafeMutablePointer(to: &addr) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(s, $0, len) } }
        _ = withUnsafeMutablePointer(to: &addr) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(s, $0, &len) } }
        return Int(UInt16(bigEndian: addr.sin_port))
    }
}

import CryptoKit
import Foundation

public enum Paths {
    public static var support: URL {
        let u = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Murmur")
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }
    public static var models: URL { dir("models") }
    public static var history: URL { dir("history") }
    public static var logs: URL { dir("logs") }
    static func dir(_ name: String) -> URL {
        let u = support.appendingPathComponent(name)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }
}

public enum Resources {
    /// In Murmur.app the resource bundle sits in Contents/Resources (codesign forbids it at the bundle root, where
    /// SwiftPM's Bundle.module looks first); for `swift run` / murmur-cli it is next to the binary.
    public static let bundle: Bundle = {
        if let r = Bundle.main.resourceURL?.appendingPathComponent("Murmur_MurmurCore.bundle"), let b = Bundle(url: r) { return b }
        return Bundle.module
    }()

    public static func text(_ path: String) -> String {
        String(decoding: data(path), as: UTF8.self)
    }

    public static func data(_ path: String) -> Data {
        guard let base = bundle.resourceURL, let d = try? Data(contentsOf: base.appendingPathComponent(path)) else {
            fatalError("missing resource \(path)")
        }
        return d
    }

    public static let tones: Tones = try! JSONDecoder().decode(Tones.self, from: data("prompts/tones/tones.v1.json"))
    public static func systemPrompt(_ name: String) -> String { text("prompts/system/\(name).md") }
    public static let catalog: Catalog = try! JSONDecoder().decode(Catalog.self, from: data("catalog.json"))
    public static let appCategories: AppCategories = try! JSONDecoder().decode(AppCategories.self, from: data("app_categories.json"))
}

public struct Catalog: Decodable, Sendable {
    public let models: [ModelEntry]
    public func model(_ id: String) -> ModelEntry? { models.first { $0.id == id } }
    public var asr: [ModelEntry] { models.filter { $0.kind == "asr" } }
    public var llm: [ModelEntry] { models.filter { $0.kind == "llm" } }
    public var defaultASR: ModelEntry { asr.first { $0.isDefault } ?? asr[0] }
    public var defaultLLM: ModelEntry { llm.first { $0.isDefault } ?? llm[0] }
}

public struct ModelEntry: Decodable, Identifiable, Hashable, Sendable {
    public struct File: Decodable, Hashable, Sendable { public let name: String; public let url: URL; public let bytes: Int64; public let sha256: String }
    public let id: String
    public let kind: String
    public let displayName: String
    public let publisher: String
    public let params: Int64
    public let languages: String
    public let license: String
    public let description: String
    public let runtime: String
    public let isDefault: Bool
    public let template: String?
    public let promptProfile: String?
    public let files: [File]

    enum CodingKeys: String, CodingKey {
        case id, kind, displayName, publisher, params, languages, license, description, runtime, template, promptProfile, files
        case isDefault = "default"
    }

    public var directory: URL { Paths.models.appendingPathComponent(id) }
    public var mainFile: URL { directory.appendingPathComponent(files[0].name) }
    public var isInstalled: Bool { files.allSatisfy { FileManager.default.fileExists(atPath: directory.appendingPathComponent($0.name).path) } }
    public var totalBytes: Int64 { files.reduce(0) { $0 + $1.bytes } }
    public var paramsLabel: String {
        let b = Double(params) / 1e9
        return b >= 1 ? String(format: "%.1fB", b) : String(format: "%.0fM", Double(params) / 1e6)
    }
}

/// Bundle id -> tone category (prompts/tones). The macOS counterpart of prompts/tones/app_categories.v1.json.
public struct AppCategories: Decodable, Sendable {
    public let exact: [String: String]
    public let prefixes: [String: String]
    public func category(bundleID: String?) -> String {
        guard let id = bundleID?.lowercased() else { return "default" }
        if let c = exact[id] { return c }
        for (p, c) in prefixes where id.hasPrefix(p) { return c }
        return "default"
    }
}

/// Downloads catalog files from Hugging Face (pinned revisions), verifies sha256, then moves them in place.
public final class ModelDownloader: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    public typealias Progress = @Sendable (Double) -> Void
    private var session: URLSession!
    private var progress: [Int: Progress] = [:]
    private var completion: [Int: (Result<URL, Error>) -> Void] = [:]
    private var tasks: [String: URLSessionDownloadTask] = [:]
    private let lock = NSLock()

    public static let shared = ModelDownloader()

    override init() {
        super.init()
        session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
    }

    public func download(_ m: ModelEntry, progress: @escaping Progress) async throws {
        try FileManager.default.createDirectory(at: m.directory, withIntermediateDirectories: true)
        let total = Double(m.totalBytes)
        var done = 0.0
        for f in m.files {
            let dest = m.directory.appendingPathComponent(f.name)
            if FileManager.default.fileExists(atPath: dest.path) { done += Double(f.bytes); continue }
            let base = done
            let tmp: URL = try await withCheckedThrowingContinuation { cont in
                let task = session.downloadTask(with: f.url)
                lock.lock()
                self.progress[task.taskIdentifier] = { p in progress((base + p * Double(f.bytes)) / total) }
                self.completion[task.taskIdentifier] = { cont.resume(with: $0) }
                tasks[m.id] = task
                lock.unlock()
                task.resume()
            }
            let sha = try Self.sha256(tmp)
            guard sha == f.sha256 else {
                try? FileManager.default.removeItem(at: tmp)
                throw NSError(domain: "Murmur", code: 1, userInfo: [NSLocalizedDescriptionKey: "Checksum mismatch for \(f.name)"])
            }
            try? FileManager.default.removeItem(at: dest)
            try FileManager.default.moveItem(at: tmp, to: dest)
            done += Double(f.bytes)
        }
        progress(1)
    }

    public func cancel(_ id: String) {
        lock.lock(); let t = tasks[id]; lock.unlock()
        t?.cancel()
    }

    public static func sha256(_ url: URL) throws -> String {
        let h = try FileHandle(forReadingFrom: url)
        defer { try? h.close() }
        var hasher = SHA256()
        while true {
            let chunk = h.readData(ofLength: 8 << 20)
            if chunk.isEmpty { break }
            hasher.update(data: chunk)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    public func urlSession(_ s: URLSession, downloadTask: URLSessionDownloadTask, didWriteData _: Int64, totalBytesWritten w: Int64, totalBytesExpectedToWrite e: Int64) {
        lock.lock(); let p = progress[downloadTask.taskIdentifier]; lock.unlock()
        if e > 0 { p?(Double(w) / Double(e)) }
    }

    public func urlSession(_ s: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        // The file at `location` is deleted when this returns: move it first.
        let keep = Paths.models.appendingPathComponent(".dl-\(UUID().uuidString)")
        let r: Result<URL, Error>
        if let http = downloadTask.response as? HTTPURLResponse, http.statusCode >= 400 {
            r = .failure(NSError(domain: "Murmur", code: http.statusCode, userInfo: [NSLocalizedDescriptionKey: "HTTP \(http.statusCode)"]))
        } else {
            do { try FileManager.default.moveItem(at: location, to: keep); r = .success(keep) } catch { r = .failure(error) }
        }
        finish(downloadTask.taskIdentifier, r)
    }

    public func urlSession(_ s: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error { finish(task.taskIdentifier, .failure(error)) }
    }

    private func finish(_ id: Int, _ r: Result<URL, Error>) {
        lock.lock()
        let c = completion.removeValue(forKey: id)
        progress.removeValue(forKey: id)
        lock.unlock()
        c?(r)
    }
}

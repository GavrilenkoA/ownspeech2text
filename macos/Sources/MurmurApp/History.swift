import Foundation
import MurmurCore

struct HistoryItem: Codable, Identifiable, Hashable {
    var id = UUID()
    var date = Date()
    var appName: String?
    var bundleID: String?
    var category: String?
    var transcript: String
    var text: String
    var action: String          // accept | fallback | empty | raw
    var reason: String
    var language: String?
    var asrModel: String?
    var llmModel: String?
    var promptVersion: String?
    var guardVersion = OutputGuard.version
    var audioSeconds: Double = 0
    var asrSeconds: Double = 0
    var llmSeconds: Double = 0
    var totalSeconds: Double = 0  // end of speech -> text inserted
    var inserted = false
    var audioFile: String?
    var screenLines = 0

    var words: Int { text.split(whereSeparator: { $0.isWhitespace }).count }
    var audioURL: URL? { audioFile.map { Paths.history.appendingPathComponent($0) } }
}

/// Dictation history: one JSON + one WAV per item in ~/Library/Application Support/Murmur/history,
/// deleted after the retention period (BRIEF: one week).
@MainActor
final class HistoryStore: ObservableObject {
    static let shared = HistoryStore()
    @Published private(set) var items: [HistoryItem] = []
    private let enc: JSONEncoder = { let e = JSONEncoder(); e.dateEncodingStrategy = .iso8601; e.outputFormatting = [.prettyPrinted]; return e }()
    private let dec: JSONDecoder = { let d = JSONDecoder(); d.dateDecodingStrategy = .iso8601; return d }()

    private init() { load() }

    func load() {
        let fm = FileManager.default
        let files = (try? fm.contentsOfDirectory(at: Paths.history, includingPropertiesForKeys: nil)) ?? []
        items = files.filter { $0.pathExtension == "json" }
            .compactMap { try? dec.decode(HistoryItem.self, from: Data(contentsOf: $0)) }
            .sorted { $0.date > $1.date }
        prune()
    }

    func add(_ item: HistoryItem) {
        items.insert(item, at: 0)
        save(item)
    }

    func update(_ item: HistoryItem) {
        if let i = items.firstIndex(where: { $0.id == item.id }) { items[i] = item }
        save(item)
    }

    private func save(_ item: HistoryItem) {
        try? enc.encode(item).write(to: Paths.history.appendingPathComponent("\(item.id).json"))
    }

    func delete(_ item: HistoryItem) {
        items.removeAll { $0.id == item.id }
        try? FileManager.default.removeItem(at: Paths.history.appendingPathComponent("\(item.id).json"))
        if let a = item.audioURL { try? FileManager.default.removeItem(at: a) }
    }

    func deleteAll() { items.forEach(delete) }

    func prune() {
        let days = Settings.shared.retentionDays
        guard days > 0 else { return }
        let cutoff = Date().addingTimeInterval(-Double(days) * 86400)
        items.filter { $0.date < cutoff }.forEach(delete)
    }
}

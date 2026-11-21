import Foundation

/// ctx v2 (prompts/context.schema.v2.json): app + focused field (from Accessibility) + screen OCR lines.
/// Boxes are [x0, y0, x1, y1] in screenshot pixels, origin top-left (the same as on Android).
public struct CtxV2: Codable, Equatable, Sendable {
    public var v: Int = 2
    public var app: App?
    public var field: Field?
    public var screen: Screen?
    public var language: Language?
    public var privacy: Privacy?

    public init(app: App? = nil, field: Field? = nil, screen: Screen? = nil, language: Language? = nil, privacy: Privacy? = nil) {
        self.app = app; self.field = field; self.screen = screen; self.language = language; self.privacy = privacy
    }

    public struct App: Codable, Equatable, Sendable {
        public var package: String?
        public var label: String?
        public var category: String?
        public init(package: String? = nil, label: String? = nil, category: String? = nil) {
            self.package = package; self.label = label; self.category = category
        }
    }

    public struct Field: Codable, Equatable, Sendable {
        public var kind: String?
        public var hint: String?
        public var box: [Int]?
        public var beforeCursor: String?
        public var inputTypeVariation: String?
        enum CodingKeys: String, CodingKey {
            case kind, hint, box
            case beforeCursor = "before_cursor"
            case inputTypeVariation = "input_type_variation"
        }
        public init(kind: String? = nil, hint: String? = nil, box: [Int]? = nil, beforeCursor: String? = nil) {
            self.kind = kind; self.hint = hint; self.box = box; self.beforeCursor = beforeCursor
        }
    }

    public struct Line: Codable, Equatable, Sendable {
        public var text: String
        public var conf: Double?
        public var box: [Int]?
        public init(text: String, conf: Double? = nil, box: [Int]? = nil) { self.text = text; self.conf = conf; self.box = box }
    }

    public struct Screen: Codable, Equatable, Sendable {
        public var size: [Int]?
        public var lines: [Line]?
        public init(size: [Int]? = nil, lines: [Line]? = nil) { self.size = size; self.lines = lines }
    }

    public struct Language: Codable, Equatable, Sendable {
        public var asr: String?
        public init(asr: String?) { self.asr = asr }
    }

    public struct Privacy: Codable, Equatable, Sendable {
        public var level: String  // full | app_only | none
        public var reason: String?
        public init(level: String, reason: String? = nil) { self.level = level; self.reason = reason }
    }
}

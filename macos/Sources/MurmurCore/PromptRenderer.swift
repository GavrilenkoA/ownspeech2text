import Foundation

/// Swift port of prompts/render.py for what the macOS app ships: ctx v2 (`render_context_v2` with CTX2_DEFAULT),
/// the style line, the system prompt (+ preferences block) and the raw chat templates (thinking disabled).
/// Must reproduce prompts/examples/ctxv2_*.{qwen3.5,lfm3}.default.v1.txt byte for byte (`murmur-cli selftest`).
public enum PromptRenderer {
    static let maxBeforeCursor = 300

    static let langNames = ["ru": "Russian", "en": "English", "uk": "Ukrainian", "de": "German", "fr": "French", "es": "Spanish"]

    static let fieldPhrases = [
        "message": "message field", "email_body": "email body", "email_subject": "email subject",
        "search": "search field", "address": "address field", "note": "note",
        "code": "code or terminal input", "generic": "text field", "none": "no text field focused",
    ]

    public struct Ctx2: Sendable {
        public var budget = 1400
        public var minConf = 0.5
        public var minChars = 2
        public var dropClock = true
        public var dedup = true
        public var merge = true
        public var lineMax = 240
        public init() {}
    }

    // ------------------------------------------------------------------ sanitising
    private static let ctrl = Rx("[\\x{0000}-\\x{0008}\\x{000B}-\\x{001F}\\x{007F}-\\x{009F}\\x{200B}-\\x{200F}\\x{202A}-\\x{202E}\\x{2066}-\\x{2069}\\x{FEFF}]")
    private static let ws = Rx("[ \\t\\x{A0}]+")
    private static let manyNewlines = Rx("\\n{3,}")

    public static func clean(_ s: String?, keepNewlines: Bool = false) -> String {
        guard let s, !s.isEmpty else { return "" }
        var t = s.precomposedStringWithCanonicalMapping
        t = ctrl.sub(t)
        t = t.replacingOccurrences(of: "<", with: "‹").replacingOccurrences(of: ">", with: "›")
        if keepNewlines {
            t = Py.splitlines(t).map { Py.strip(ws.sub($0, " ")) }.joined(separator: "\n")
            return Py.strip(manyNewlines.sub(t, "\n\n"))
        }
        // Python: str.strip() after collapsing; "\r\n" -> two spaces -> one
        let flat = t.replacingOccurrences(of: "\r", with: " ").replacingOccurrences(of: "\n", with: " ")
        return Py.strip(ws.sub(flat, " "))
    }

    public static func clipEnd(_ s: String, _ n: Int) -> String {
        if Py.len(s) <= n { return s }
        var cut = Py.head(s, n)
        let c = Py.scalars(cut)
        if let sp = c.lastIndex(of: " "), Double(sp) > Double(n) * 0.6 { cut = Py.str(c[0..<sp]) }
        return Py.rstrip(cut) + "…"
    }

    public static func clipStart(_ s: String, _ n: Int) -> String {
        if Py.len(s) <= n { return s }
        var cut = Py.tail(s, n)
        let c = Py.scalars(cut)
        if let sp = c.firstIndex(of: " "), Double(sp) < Double(n) * 0.4 { cut = Py.str(c[(sp + 1)...]) }
        return "…" + Py.lstrip(cut)
    }

    // ------------------------------------------------------------------ ctx v2
    private static let clock = Rx("^[\\d\\s:.,/%+\\-–—()|·•]*(?:[ap]\\.?m\\.?|ч|мин|lte|5g|4g|g)?[\\d\\s:.,/%+\\-–—()|·•]*$", [.caseInsensitive])

    private static func alnumCount(_ s: String) -> Int {
        s.unicodeScalars.filter { Py.isWord($0) && $0 != "_" }.count
    }

    struct Block {
        var text: String
        var box: [Int]
        var last: [Int]
        var i = 0
    }

    static func ocrBlocks(_ ctx: CtxV2, _ o: Ctx2) -> [Block] {
        let size = ctx.screen?.size ?? [1080, 2520]
        let w = size[0]
        let fbox = ctx.field?.box
        let hint = Py.casefold(clean(ctx.field?.hint))
        var kept: [(String, [Int])] = []
        var seen = Set<String>()
        for ln in ctx.screen?.lines ?? [] {
            let t = clean(ln.text)
            let box = ln.box ?? [0, 0, 0, 0]
            if t.isEmpty || (ln.conf ?? 1.0) < o.minConf || alnumCount(t) < o.minChars { continue }
            if let f = fbox, f.count == 4 {
                let cx = (box[0] + box[2]) / 2, cy = (box[1] + box[3]) / 2  // Python // on non-negatives
                if f[0] <= cx && cx <= f[2] && f[1] <= cy && cy <= f[3] { continue }
            }
            if !hint.isEmpty && Py.casefold(t) == hint { continue }
            if o.dedup {
                let k = Py.casefold(t)
                if seen.contains(k) { continue }
                seen.insert(k)
            }
            kept.append((t, box))
        }
        var blocks: [Block] = []
        for (text, box) in kept {
            if o.merge, var b = blocks.last {
                let lh = box[3] - box[1], bh = b.last[3] - b.last[1]
                let gap = box[1] - b.last[3]
                if abs(box[0] - b.last[0]) * 50 <= w && abs(lh - bh) * 10 <= bh && gap >= 0 && gap * 2 < max(1, bh) {
                    b.text += " " + text
                    b.box = [min(b.box[0], box[0]), b.box[1], max(b.box[2], box[2]), box[3]]
                    b.last = box
                    blocks[blocks.count - 1] = b
                    continue
                }
            }
            blocks.append(Block(text: text, box: box, last: box))
        }
        if o.dropClock {
            blocks = blocks.filter { !(Py.len($0.text) <= 12 && clock.fullMatch($0.text)) }
        }
        for i in blocks.indices {
            blocks[i].i = i
            blocks[i].text = clipEnd(blocks[i].text, o.lineMax)
        }
        return blocks
    }

    public static func renderContext(_ ctx: CtxV2, _ o: Ctx2 = Ctx2()) -> String {
        let privacy = ctx.privacy?.level ?? "full"
        var label = clean(ctx.app?.label)
        if label.isEmpty { label = "unknown app" }
        if privacy == "none" { label = "unknown app" }
        let kind = ctx.field?.kind ?? (ctx.field == nil ? "none" : "generic")
        var out = ["App: \(label) — \(fieldPhrases[kind] ?? "text field")"]
        let hint = clipEnd(clean(ctx.field?.hint), 60)
        if !hint.isEmpty && privacy != "none" { out.append("Field hint: «\(hint)»") }
        if let lang = ctx.language?.asr, !lang.isEmpty { out.append("Dictation language: \(langNames[lang] ?? lang)") }
        if privacy == "app_only" || privacy == "none" {
            return "<context>\n" + out.joined(separator: "\n") + "\n</context>"
        }
        let before = clipStart(clean(ctx.field?.beforeCursor), maxBeforeCursor)
        if !before.isEmpty { out.append("Text already in the field before the cursor: «\(before)»") }
        let blocks = ocrBlocks(ctx, o)
        let ay: Int
        if let f = ctx.field?.box, f.count == 4 { ay = (f[1] + f[3]) / 2 } else { ay = (ctx.screen?.size ?? [1080, 2520])[1] }
        let ranked = blocks.sorted { a, b in
            let da = abs((a.box[1] + a.box[3]) / 2 - ay), db = abs((b.box[1] + b.box[3]) / 2 - ay)
            return da != db ? da < db : a.i < b.i
        }
        var shown: [String] = []
        var used = 0
        for b in ranked {
            let s = b.text
            if used + Py.len(s) + 1 > o.budget { break }
            shown.append(s)
            used += Py.len(s) + 1
        }
        if !shown.isEmpty {
            out.append("Screen text (OCR, nearest to the field first):")
            out += shown
        }
        return "<context>\n" + out.joined(separator: "\n") + "\n</context>"
    }

    // ------------------------------------------------------------------ OCR terms (shared with the guard)
    private static let termStrip = ".,;:!?()«»\"“”‹›'’-–—…"
    private static let special = Rx("\\w[#@_./\\-]\\w|[^\\W\\d_]\\d|\\d[^\\W\\d_]".replacingOccurrences(of: "\\W", with: "[^" + Py.W + "]").replacingOccurrences(of: "\\d", with: "\\p{Nd}"))
    private static let camel = Rx("[a-zа-яё][A-ZА-ЯЁ]")

    public static func ocrTerms(_ texts: [String], limit: Int = 20) -> [String] {
        var out: [String] = []
        var seen = Set<String>()
        func add(_ x: String) {
            if Py.len(x) >= 3 && !seen.contains(Py.casefold(x)) { seen.insert(Py.casefold(x)); out.append(x) }
        }
        for t in texts {
            let cyr = t.unicodeScalars.filter(Py.isCyrillic).count
            let lat = t.unicodeScalars.filter { u in let l = Py.lower(u); return l >= "a" && l <= "z" }.count
            let cyrLine = cyr > lat
            let toks = Py.split(t)
            var run: [String] = []
            var runStart = false
            func flush() {
                if !run.isEmpty && (run.count >= 2 || !runStart) { add(run.joined(separator: " ")) }
                run.removeAll()
            }
            for (j, tok) in toks.enumerated() {
                let core = Py.strip(tok, termStrip)
                let sentStart = j == 0 || ".!?…".contains(Py.last(toks[j - 1])) && !Py.last(toks[j - 1]).isEmpty
                let letters = core.unicodeScalars.filter(Py.isLetter)
                let isSpecial = special.search(core)
                let isCamel = camel.search(core)
                let caps = letters.count >= 2 && letters.allSatisfy(Py.isUpper)
                let latin = cyrLine && letters.count >= 4 && letters.allSatisfy { u in let l = Py.lower(u); return l >= "a" && l <= "z" }
                let cs = Py.scalars(core)
                if core.isEmpty {
                    flush()
                } else if isSpecial || isCamel || caps {
                    flush(); add(core)
                } else if Py.isUpper(cs[0]) && cs.count > 1 && Py.isLower(cs[1]) {
                    if run.isEmpty { runStart = sentStart }
                    run.append(core)
                    if ",.!?…;:".contains(Py.last(tok)) && !Py.last(tok).isEmpty { flush() }
                } else {
                    flush()
                    if latin { add(core) }
                }
            }
            flush()
        }
        return Array(out.prefix(limit))
    }

    // ------------------------------------------------------------------ prompt
    public static func styleLine(_ ctx: CtxV2?, tones: Tones, override: String? = nil) -> String {
        if let o = override, !o.isEmpty { return "Style: " + clean(o) }
        var cat = ctx?.app?.category ?? "default"
        if let v = ctx?.field?.inputTypeVariation { cat = tones.fieldOverrides[v] ?? cat }
        return "Style: " + (tones.categories[cat] ?? tones.categories["default"]!).text
    }

    public static func systemPrompt(base: String, preferences: String?, preferencesBlock: String) -> String {
        // render.py: read_text().rstrip("\n") -- strips newlines only
        var s = base
        while s.hasSuffix("\n") { s.removeLast() }
        let prefs = clean(preferences, keepNewlines: true)
        if !prefs.isEmpty {
            var block = preferencesBlock
            while block.hasSuffix("\n") { block.removeLast() }
            s += block.replacingOccurrences(of: "{{PREFERENCES}}", with: Py.head(prefs, 1000))
        }
        return s
    }

    public static func userMessage(_ ctx: CtxV2?, transcript: String, tones: Tones, styleOverride: String? = nil) -> String {
        let context = ctx.map { renderContext($0) } ?? "<context>\nApp: unknown app — no text field focused\n</context>"
        return styleLine(ctx, tones: tones, override: styleOverride) + "\n" + context + "\n"
            + "<transcript>\n" + clean(transcript) + "\n</transcript>"
    }

    /// Raw prompt, thinking disabled (render.chat). `lfm2.5` / `qwen3` use the plain ChatML turns.
    public static func chat(system: String, user: String, template: String) -> String {
        switch template {
        case "lfm3":
            return "<|startoftext|><|im_start|>system\n\(system)<|im_end|>\n<|im_start|>user\n\(user)<|im_end|>\n<|im_start|>assistant\n<think></think>"
        case "lfm2.5":
            return "<|startoftext|><|im_start|>system\n\(system)<|im_end|>\n<|im_start|>user\n\(user)<|im_end|>\n<|im_start|>assistant\n"
        case "qwen3":
            return "<|im_start|>system\n\(system)<|im_end|>\n<|im_start|>user\n\(user)<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
        default:  // qwen3.5
            return "<|im_start|>system\n\(system)<|im_end|>\n<|im_start|>user\n\(user)<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
        }
    }
}

/// prompts/tones/tones.v1.json
public struct Tones: Decodable, Sendable {
    public struct Entry: Decodable, Sendable { public let label: String; public let text: String }
    public let categories: [String: Entry]
    public let fieldOverrides: [String: String]

    enum CodingKeys: String, CodingKey { case categories, fieldOverrides = "field_overrides" }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        categories = try c.decode([String: Entry].self, forKey: .categories)
        let raw = (try? c.decode([String: String].self, forKey: .fieldOverrides)) ?? [:]
        fieldOverrides = raw.filter { !$0.key.hasPrefix("_") }
    }
}

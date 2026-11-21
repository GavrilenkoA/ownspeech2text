import Foundation

/// Swift port of prompts/guard.py (guard.v1.1), following core/prompt/GuardPyPort.kt.
/// `precheck` runs before the LLM, `guard` after it; on a bad output the raw transcript is inserted instead.
/// Checked against the Python reference with `murmur-cli guard` (tools/guard_diff.py).
public enum OutputGuard {
    public static let version = "guard.v1.1"

    public struct Precheck: Equatable, Sendable { public let skip: Bool; public let transcript: String; public let reason: String }
    public enum Action: String, Codable, Sendable { case accept, fallback, empty }
    public struct Verdict: Equatable, Sendable { public let text: String; public let action: Action; public let reason: String }

    static let fillers: Set<String> = Set("""
        э ээ эээ э-э э-э-э м мм ммм м-м хм гм а-а ну эм ам
        um uh uhm uh-huh erm er hmm hm mm mmm ah eh
        """.split(whereSeparator: { $0 == " " || $0 == "\n" }).map(String.init))

    static let growK = 1.5, growAdd = 4.0, charGrowK = 2.5, charGrowAdd = 40.0
    static let shrinkK = 0.3, shrinkMinIn = 8
    static let novelMin = 3, novelShare = 0.5, novelSim = 0.75
    static let scriptMinLetters = 8
    static let distractorSim = 0.6, rivalMargin = 0.15
    static let noScriptCheck: Set<String> = ["code", "address"]
    static let spanLostMin = 1, spanSim = 0.5, spanKeepSim = 0.7

    static let hallFull = Rx(
        "^(?:\\[?\\s*(?:blank_audio|silence|music|музыка|тишина|аплодисменты|смех)\\s*\\]?|" +
        "\\(?\\s*(?:music|музыка)\\s*\\)?|" +
        "thanks? (?:you )?for watching!?|thank you for watching!?|please subscribe.*|" +
        "продолжение следует\\.*|спасибо за просмотр!?|подписывайтесь на канал.*|" +
        "субтитры (?:сделал|делал|создавал|подготовил)\\w*\\s.*|редактор субтитров\\s.*|" +
        "корректор\\s+\\S+\\.\\s*\\S+|dimatorzok)$", [.caseInsensitive])
    static let hallTail = Rx(
        "(?:(?<=[.!?…])\\s*|\\s+)(?:продолжение следует\\.*|субтитры (?:сделал|делал|создавал|подготовил)\\w*\\s.*|" +
        "редактор субтитров\\s.*|thanks? for watching!?|thank you for watching!?|\\[blank_audio\\])\\s*$", [.caseInsensitive])
    static let think = Rx("<think>.*?</think>\\s*|^.*?</think>\\s*", [.dotMatchesLineSeparators])
    static let chatTokens = Rx("<\\|[a-z_]+\\|>|<\\|im_end\\|>|<\\|endoftext\\|>")
    static let label = Rx("^\\s*(?:output|edited text|edited|result|corrected text|text|вывод|результат|текст|" +
                          "исправленный текст|отредактированный текст)\\s*:\\s*", [.caseInsensitive])
    static let leak = Rx("</?(?:context|transcript|preferences)>|^\\s*Style:|<\\|im_|<think|Names and terms on screen|" +
                         "Dictation language:|Text already in the field", [.caseInsensitive, .anchorsMatchLines])
    static let b = "(?![" + Py.W + "])"
    static let preamble = Rx(
        "^\\s*(?:sure\(b)|certainly\(b)|of course\(b)|here is\(b)|here's\(b)|here are\(b)|okay, here\(b)|i'm sorry\(b)|i cannot\(b)|" +
        "as an ai\(b)|конечно[,!]|вот (?:исправленный|ваш|отредактированный|готовый)|исправленный текст|" +
        "отредактированный текст|извините, (?:я|но)|я не могу\(b))", [.caseInsensitive])
    static let charLoop = Rx("(.{1,6}?)\\1{7,}", [.dotMatchesLineSeparators])
    static let fence = Rx("^```\\w*\\n?|\\n?```$")
    static let spanSplit = Rx("[-_'’]")
    static let quotePairs: [Unicode.Scalar: Unicode.Scalar] = ["\"": "\"", "«": "»", "“": "”", "'": "'", "`": "`"]

    // ------------------------------------------------------------------ words
    static func wordTokens(_ s: String) -> [String] {
        let c = Py.scalars(s)
        var out: [String] = []
        var k = 0
        while k < c.count {
            if !Py.isWord(c[k]) { k += 1; continue }
            let start = k
            while k < c.count && Py.isWord(c[k]) { k += 1 }
            while k + 1 < c.count && (c[k] == "-" || c[k] == "'" || c[k] == "’") && Py.isWord(c[k + 1]) {
                k += 1
                while k < c.count && Py.isWord(c[k]) { k += 1 }
            }
            out.append(Py.str(c[start..<k]))
        }
        return out
    }

    static func words(_ s: String) -> [String] { wordTokens(s).map { Py.casefold($0).replacingOccurrences(of: "ё", with: "е") } }
    static func content(_ ws: [String]) -> [String] { ws.filter { !fillers.contains($0) } }

    static func letters(_ s: String) -> (Int, Int) {
        var lat = 0, cyr = 0
        for u in s.unicodeScalars {
            let l = Py.lower(u)
            if l >= "a" && l <= "z" { lat += 1 }
            if Py.isCyrillic(u) { cyr += 1 }
        }
        return (lat, cyr)
    }

    static let tr: [Character: String] = {
        let src = Array("абвгдеёжзийклмнопрстуфхцчшщъыьэюя")
        let dst = ["a", "b", "v", "g", "d", "e", "e", "zh", "z", "i", "i", "k", "l", "m", "n", "o", "p", "r", "s",
                   "t", "u", "f", "h", "ts", "ch", "sh", "sch", "", "y", "", "e", "yu", "ya"]
        return Dictionary(uniqueKeysWithValues: zip(src, dst))
    }()
    static let phon: [(String, String)] = [("sch", "sh"), ("ph", "f"), ("ck", "k"), ("qu", "kv"), ("w", "v"), ("th", "t"),
        ("x", "ks"), ("c", "k"), ("y", "i"), ("j", "dj"), ("q", "k"), ("ou", "au"), ("ee", "i"), ("oo", "u"), ("h", "")]

    /// 'клик хаус' -> 'klikaus', 'ClickHouse' -> 'klikaus'
    public static func phoneticKey(_ s: String) -> String {
        let nfkd = Py.casefold(s).decomposedStringWithCompatibilityMapping
        var b = ""
        for u in nfkd.unicodeScalars {
            if u.properties.generalCategory == .nonspacingMark || u.properties.generalCategory == .spacingMark || u.properties.generalCategory == .enclosingMark { continue }
            if let t = tr[Character(u)] { b += t }
            else if (u.value >= 97 && u.value <= 122) || (u.value >= 48 && u.value <= 57) { b.unicodeScalars.append(u) }
        }
        for (x, y) in phon { b = b.replacingOccurrences(of: x, with: y) }
        var v = ""
        var prevVowel = false
        for ch in b {
            let vowel = "aeiou".contains(ch)
            if vowel { if !prevVowel { v.append("a") } } else { v.append(ch) }
            prevVowel = vowel
        }
        var d = ""
        for ch in v where d.last != ch { d.append(ch) }
        return d
    }

    static func lev(_ a: [Character], _ b: [Character]) -> Int {
        var prev = Array(0...b.count)
        if a.isEmpty { return b.count }
        for i in 1...a.count {
            var cur = [Int](repeating: 0, count: b.count + 1)
            cur[0] = i
            if !b.isEmpty {
                for j in 1...b.count { cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] != b[j - 1] ? 1 : 0)) }
            }
            prev = cur
        }
        return prev[b.count]
    }

    static func sim(_ a: String, _ b: String) -> Double {
        if a.isEmpty || b.isEmpty { return 0 }
        let x = Array(a), y = Array(b)
        return 1.0 - Double(lev(x, y)) / Double(max(x.count, y.count))
    }

    public static func bestSim(_ term: String, _ transcript: String) -> Double {
        let tk = phoneticKey(term)
        if tk.isEmpty { return 1.0 }
        let keys = wordTokens(transcript).map(phoneticKey)
        var best = 0.0
        for i in keys.indices {
            var span = ""
            for n in 1...4 where i + n <= keys.count {
                span += keys[i + n - 1]
                best = max(best, sim(tk, span))
            }
        }
        return best
    }

    static func screenTerms(_ ctx: CtxV2?) -> [String] {
        guard let ctx, ctx.v == 2 else { return [] }
        return PromptRenderer.ocrTerms((ctx.screen?.lines ?? []).map(\.text), limit: 60).filter { Py.len(Py.strip($0)) >= 3 }
    }

    static func screenWords(_ ctx: CtxV2?) -> Set<String> {
        var parts: [String] = []
        if let ctx {
            parts += (ctx.screen?.lines ?? []).map(\.text)
            parts.append(ctx.field?.beforeCursor ?? "")
            parts.append(ctx.field?.hint ?? "")
        }
        return Set(parts.flatMap { words($0).map { Py.head($0, 5) } })
    }

    static func script(_ w: String) -> String {
        let (lat, cyr) = letters(w)
        return lat > cyr ? "lat" : cyr > lat ? "cyr" : ""
    }

    static func translatedSpan(_ transcript: String, _ output: String) -> Bool {
        let flatT = spanSplit.sub(transcript, " "), flatO = spanSplit.sub(output, " ")
        let tw = content(words(flatT)).filter { Py.len($0) >= 3 }
        let ow = content(words(flatO)).filter { Py.len($0) >= 3 }
        let tset = Set(tw), oset = Set(ow)
        let ok1 = ow.map(phoneticKey)
        let okeys = ok1 + zip(ok1, ok1.dropFirst()).map { $0 + $1 }
        for (src, dst) in [("lat", "cyr"), ("cyr", "lat")] {
            let lost = tw.filter { w in
                script(w) == src && !oset.contains(w) && (okeys.map { sim(phoneticKey(w), $0) }.max() ?? 0) < spanKeepSim
            }
            let new = ow.filter { w in script(w) == dst && !tset.contains(w) && bestSim(w, flatT) < spanSim }
            if Set(lost).count >= spanLostMin && !new.isEmpty { return true }
        }
        return false
    }

    // ------------------------------------------------------------------ pre-LLM
    public static func precheck(_ transcript: String) -> Precheck {
        let t = Py.strip(transcript.precomposedStringWithCanonicalMapping)
        let bare = Py.strip(t, " .,!?…-—\"'«»()[]")
        if content(words(bare)).isEmpty { return Precheck(skip: true, transcript: "", reason: bare.isEmpty ? "empty" : "filler_only") }
        if hallFull.fullMatch(bare) { return Precheck(skip: true, transcript: "", reason: "asr_hallucination") }
        let cut = Py.rstrip(hallTail.sub(t))
        if cut != t && !cut.isEmpty { return Precheck(skip: false, transcript: cut, reason: "tail_removed") }
        return Precheck(skip: false, transcript: t, reason: "ok")
    }

    // ------------------------------------------------------------------ post-LLM
    public static func fallbackText(_ transcript: String, _ ctx: CtxV2?) -> String {
        var t = Py.strip(transcript)
        let cat = ctx?.app?.category
        let before = Py.rstrip(ctx?.field?.beforeCursor ?? "")
        let mid = !before.isEmpty && !".!?…:\n".contains(Py.last(before))
        if !t.isEmpty && cat != "search" && cat != "address" && cat != "code" && !mid { t = Py.upperFirst(t) }
        return t
    }

    static func repair(_ output: String, _ transcript: String, _ ctx: CtxV2?) -> String {
        var o = think.sub(output)
        o = Py.strip(chatTokens.sub(o))
        o = Py.strip(label.sub(o, count: 1))
        let t = Py.strip(transcript)
        if o.hasPrefix("```") && o.hasSuffix("```") && Py.len(o) > 6 && !t.hasPrefix("```") {
            o = Py.strip(fence.sub(o))
        }
        while Py.len(o) >= 2 {
            let c = Py.scalars(o)
            guard let close = quotePairs[c[0]] else { break }
            let q = c[0]
            let cnt = c.filter { $0 == q }.count
            if c.last != close || t.unicodeScalars.first == q || cnt != (q == close ? 2 : 1) { break }
            o = Py.strip(Py.str(c[1..<(c.count - 1)]))
        }
        let before = Py.strip(ctx?.field?.beforeCursor ?? "")
        if Py.len(before) >= 15 && o.hasPrefix(before) { o = Py.lstrip(String(o.dropFirst(before.count))) }
        return o
    }

    static func trigrams(_ ws: [String]) -> [String: Int] {
        var m: [String: Int] = [:]
        if ws.count >= 3 { for i in 0..<(ws.count - 2) { m[ws[i] + " " + ws[i + 1] + " " + ws[i + 2], default: 0] += 1 } }
        return m
    }

    public static func guardOutput(transcript: String, output: String, ctx: CtxV2?) -> Verdict {
        let pre = precheck(transcript)
        if pre.skip { return Verdict(text: "", action: .empty, reason: pre.reason) }
        let tr = pre.transcript
        let fb = fallbackText(tr, ctx)
        let o = repair(output, tr, ctx)
        let cat = ctx?.app?.category
        let tw = content(words(tr)), ow = content(words(o))
        func fallback(_ r: String) -> Verdict { Verdict(text: fb, action: .fallback, reason: r) }

        if o.isEmpty { return fallback("R1_empty") }
        if leak.search(o) { return fallback("R2_leaked_tags") }
        if preamble.search(o) && !preamble.search(tr) { return fallback("R3_preamble") }
        let tg = trigrams(tw)
        for (g, n) in trigrams(ow) where n >= 4 && (tg[g] ?? 0) < 2 { return fallback("R4_loop") }
        if let m = charLoop.firstMatch(o), !tr.contains(m) { return fallback("R4_loop") }
        if Double(Py.len(o)) > charGrowK * Double(Py.len(tr)) + charGrowAdd { return fallback("R4_length") }
        if Double(ow.count) > growK * Double(tw.count) + growAdd || (tw.count >= shrinkMinIn && Double(ow.count) < shrinkK * Double(tw.count)) {
            return fallback("R4_length")
        }
        if cat == nil || !noScriptCheck.contains(cat!) {
            let (tl, tc) = letters(tr), (ol, oc) = letters(o)
            let tn = tl + tc, on = ol + oc
            if tn >= scriptMinLetters && on != 0 {
                for (ts, os) in [(tl, ol), (tc, oc)] {
                    if ts >= scriptMinLetters && Double(ts) / Double(tn) >= 0.2 && Double(os) / Double(on) < 0.05 { return fallback("R5_script") }
                }
                if Double(tc) / Double(tn) >= 0.7 && Double(oc) / Double(on) < 0.3 { return fallback("R5_script") }
            }
            if translatedSpan(tr, o) { return fallback("R5b_translated_span") }
        }
        var allowed = Set(words(tr).map { Py.head($0, 5) })
        allowed.formUnion(screenWords(ctx))
        let contentWords = ow.filter { Py.len($0) >= 4 && !Py.isDigitString($0) }
        let novel = contentWords.filter { !allowed.contains(Py.head($0, 5)) && bestSim($0, tr) < novelSim }
        if novel.count >= novelMin && Double(novel.count) / Double(contentWords.count) > novelShare { return fallback("R6_novel") }
        let oFold = Py.casefold(o), tFold = Py.casefold(tr)
        let terms = screenTerms(ctx)
        for term in terms {
            let f = Py.casefold(term)
            if !oFold.contains(f) || tFold.contains(f) { continue }
            let sTerm = bestSim(term, tr)
            let rivals = terms.filter { $0 != term && !oFold.contains(Py.casefold($0)) }.map { bestSim($0, tr) }
            if sTerm < distractorSim || rivals.contains(where: { $0 >= sTerm + rivalMargin }) { return fallback("R7_distractor") }
        }
        return Verdict(text: o, action: .accept, reason: o == Py.strip(output) ? "ok" : "repaired")
    }
}

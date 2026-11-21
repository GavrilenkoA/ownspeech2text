import Foundation

/// The Python `str` semantics that prompts/render.py and prompts/guard.py rely on, for the Swift ports
/// (the same job core/prompt/Py.kt does for Android). Lengths and slices count code points, like Python.
enum Py {
    static func len(_ s: String) -> Int { s.unicodeScalars.count }

    static func scalars(_ s: String) -> [Unicode.Scalar] { Array(s.unicodeScalars) }

    static func str(_ c: some Collection<Unicode.Scalar>) -> String {
        var v = String.UnicodeScalarView()
        v.append(contentsOf: c)
        return String(v)
    }

    /// s[:n]
    static func head(_ s: String, _ n: Int) -> String {
        if n <= 0 { return "" }
        let c = scalars(s)
        return n >= c.count ? s : str(c[0..<n])
    }

    /// s[-n:]
    static func tail(_ s: String, _ n: Int) -> String {
        let c = scalars(s)
        return n <= 0 || n >= c.count ? s : str(c[(c.count - n)...])
    }

    static func isSpace(_ u: Unicode.Scalar) -> Bool {
        switch u.value {
        case 0x09...0x0D, 0x1C...0x20, 0x85, 0xA0, 0x1680, 0x2000...0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000: return true
        default: return false
        }
    }

    static func isLetter(_ u: Unicode.Scalar) -> Bool {
        switch u.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter: return true
        default: return false
        }
    }

    static func isDecimal(_ u: Unicode.Scalar) -> Bool { u.properties.generalCategory == .decimalNumber }

    /// Python regex `\w`: letter, Nd, Nl, No or `_`.
    static func isWord(_ u: Unicode.Scalar) -> Bool {
        if u == "_" || isLetter(u) { return true }
        switch u.properties.generalCategory {
        case .decimalNumber, .letterNumber, .otherNumber: return true
        default: return false
        }
    }

    static func isUpper(_ u: Unicode.Scalar) -> Bool { u.properties.generalCategory == .uppercaseLetter }
    static func isLower(_ u: Unicode.Scalar) -> Bool { u.properties.generalCategory == .lowercaseLetter }
    static func isCyrillic(_ u: Unicode.Scalar) -> Bool { (0x0400...0x04FF).contains(u.value) }

    /// `str.isdigit()` of a whole string (Nd and superscript digits are enough here).
    static func isDigitString(_ s: String) -> Bool {
        !s.isEmpty && s.unicodeScalars.allSatisfy { isDecimal($0) || $0.properties.numericType == .digit }
    }

    /// `str.casefold()` (full upper then full lower, as in Py.kt).
    static func casefold(_ s: String) -> String {
        if s.unicodeScalars.allSatisfy({ $0.value < 0x80 && !($0.value >= 65 && $0.value <= 90) }) { return s }
        var out = ""
        for u in s.unicodeScalars {
            switch u.value {
            case 0x131: out += "ı"
            case 0x1E9E: out += "ss"
            default: out += String(u).uppercased().lowercased()
            }
        }
        return out
    }

    static func lower(_ u: Unicode.Scalar) -> String { String(u).lowercased() }

    static func upperFirst(_ s: String) -> String {
        guard let f = s.unicodeScalars.first else { return s }
        return String(f).uppercased() + str(s.unicodeScalars.dropFirst())
    }

    static func strip(_ s: String) -> String { rstrip(lstrip(s)) }

    static func lstrip(_ s: String) -> String { str(s.unicodeScalars.drop(while: isSpace)) }

    static func rstrip(_ s: String) -> String {
        var c = scalars(s)
        while let l = c.last, isSpace(l) { c.removeLast() }
        return str(c)
    }

    /// `s.strip(chars)`
    static func strip(_ s: String, _ chars: String) -> String {
        let set = Set(chars.unicodeScalars)
        var c = scalars(s)
        while let l = c.last, set.contains(l) { c.removeLast() }
        return str(c.drop(while: { set.contains($0) }))
    }

    /// `s.split()`
    static func split(_ s: String) -> [String] {
        var out: [String] = []
        var cur: [Unicode.Scalar] = []
        for u in s.unicodeScalars {
            if isSpace(u) {
                if !cur.isEmpty { out.append(str(cur)); cur.removeAll() }
            } else { cur.append(u) }
        }
        if !cur.isEmpty { out.append(str(cur)) }
        return out
    }

    /// `s.splitlines()`
    static func splitlines(_ s: String) -> [String] {
        var out: [String] = []
        var cur: [Unicode.Scalar] = []
        let c = scalars(s)
        var i = 0
        while i < c.count {
            let v = c[i].value
            let brk = v == 0x0A || v == 0x0D || v == 0x0B || v == 0x0C || (0x1C...0x1E).contains(v) || v == 0x85 || v == 0x2028 || v == 0x2029
            if brk {
                out.append(str(cur)); cur.removeAll()
                i += (v == 0x0D && i + 1 < c.count && c[i + 1].value == 0x0A) ? 2 : 1
            } else { cur.append(c[i]); i += 1 }
        }
        if !cur.isEmpty { out.append(str(cur)) }
        return out
    }

    static func last(_ s: String) -> String { s.unicodeScalars.last.map { String($0) } ?? "" }

    /// Python `\w` / `\s` as character class bodies for NSRegularExpression (ICU).
    static let W = "\\p{L}\\p{Nd}\\p{Nl}\\p{No}_"
    static let S = "\\t\\n\\x{0B}\\f\\r\\x{1C}-\\x{20}\\x{85}\\x{A0}\\x{1680}\\x{2000}-\\x{200A}\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}"
}

/// Small NSRegularExpression helpers.
struct Rx {
    let re: NSRegularExpression

    init(_ pattern: String, _ options: NSRegularExpression.Options = []) {
        // Python's `\w`, `\s` are spelled out (ICU differs on marks and some spaces).
        let p = pattern
            .replacingOccurrences(of: "\\w", with: "[" + Py.W + "]")
            .replacingOccurrences(of: "\\s", with: "[" + Py.S + "]")
            .replacingOccurrences(of: "\\S", with: "[^" + Py.S + "]")
        re = try! NSRegularExpression(pattern: p, options: options)
    }

    private func ns(_ s: String) -> NSRange { NSRange(s.startIndex..., in: s) }

    func search(_ s: String) -> Bool { re.firstMatch(in: s, range: ns(s)) != nil }

    func firstMatch(_ s: String) -> String? {
        guard let m = re.firstMatch(in: s, range: ns(s)), let r = Range(m.range, in: s) else { return nil }
        return String(s[r])
    }

    func sub(_ s: String, _ with: String = "", count: Int = 0) -> String {
        if count == 0 {
            return re.stringByReplacingMatches(in: s, range: ns(s), withTemplate: NSRegularExpression.escapedTemplate(for: with))
        }
        guard let m = re.firstMatch(in: s, range: ns(s)) else { return s }
        let r = Range(m.range, in: s)!
        return s.replacingCharacters(in: r, with: with)
    }

    /// Full-string match (Python `re.match` + `$` anchor, as used by the specs).
    func fullMatch(_ s: String) -> Bool {
        guard let m = re.firstMatch(in: s, options: [.anchored], range: ns(s)) else { return false }
        return m.range.location == 0
    }
}

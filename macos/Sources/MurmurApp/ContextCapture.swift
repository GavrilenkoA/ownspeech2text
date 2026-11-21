import AppKit
import ApplicationServices
import MurmurCore
import ScreenCaptureKit

/// What Accessibility tells us about the app and the focused field at dictation start
/// (the macOS counterpart of platform/a11y on Android).
struct FocusSnapshot {
    var pid: pid_t = 0
    var bundleID: String?
    var appName: String?
    var category = "default"
    var role: String?
    var fieldKind: String?        // nil = no text field focused
    var hint: String?
    var beforeCursor: String?
    var fieldFrame: CGRect?       // global, top-left origin (points)
    var isPassword = false
    var isEditable: Bool?         // nil = unknown (AX gave nothing): we still paste
    var denylisted = false
}

enum ContextCapture {
    static let textRoles: Set<String> = ["AXTextField", "AXTextArea", "AXComboBox", "AXSearchField"]
    /// Apps whose screen is never read (passwords, keychains).
    static let denylist: Set<String> = ["com.1password.1password", "com.agilebits.onepassword7", "com.apple.keychainaccess",
                                        "com.bitwarden.desktop", "com.apple.passwords", "com.lastpass.lastpassmacdesktop"]

    static func snapshot() -> FocusSnapshot {
        var s = FocusSnapshot()
        guard let app = NSWorkspace.shared.frontmostApplication else { return s }
        s.pid = app.processIdentifier
        s.bundleID = app.bundleIdentifier
        s.appName = app.localizedName
        s.category = Resources.appCategories.category(bundleID: app.bundleIdentifier)
        s.denylisted = denylist.contains(app.bundleIdentifier?.lowercased() ?? "")

        let appEl = AXUIElementCreateApplication(app.processIdentifier)
        // Electron/Chromium apps (Slack, VS Code, Notion…) expose their tree only when asked.
        AXUIElementSetAttributeValue(appEl, "AXManualAccessibility" as CFString, kCFBooleanTrue)
        AXUIElementSetMessagingTimeout(appEl, 0.25)
        guard let el: AXUIElement = attr(appEl, kAXFocusedUIElementAttribute) else { return s }
        AXUIElementSetMessagingTimeout(el, 0.25)
        let role: String? = attr(el, kAXRoleAttribute)
        let subrole: String? = attr(el, kAXSubroleAttribute)
        s.role = role
        if subrole == "AXSecureTextField" || role == "AXSecureTextField" { s.isPassword = true }
        var settable: DarwinBoolean = false
        let rangeSettable = AXUIElementIsAttributeSettable(el, kAXSelectedTextRangeAttribute as CFString, &settable) == .success && settable.boolValue
        let editable = textRoles.contains(role ?? "") || rangeSettable || s.isPassword
        s.isEditable = editable
        guard editable else { return s }

        if role == "AXSearchField" || subrole == "AXSearchField" {
            s.fieldKind = "search"; s.category = "search"
        } else {
            switch s.category {
            case "messaging", "work_chat", "social": s.fieldKind = "message"
            case "email": s.fieldKind = role == "AXTextField" ? "email_subject" : "email_body"
            case "notes": s.fieldKind = "note"
            case "code": s.fieldKind = "code"
            default: s.fieldKind = "generic"
            }
        }
        if s.isPassword { return s }
        s.hint = [attr(el, "AXPlaceholderValue"), attr(el, kAXDescriptionAttribute), attr(el, kAXTitleAttribute)]
            .compactMap { (x: String?) in x }.first { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        if let value: String = attr(el, kAXValueAttribute) {
            var loc = (value as NSString).length
            if let rv: AXValue = attr(el, kAXSelectedTextRangeAttribute) {
                var r = CFRange()
                if AXValueGetValue(rv, .cfRange, &r) { loc = min(max(0, r.location), loc) }
            }
            let before = (value as NSString).substring(to: loc)
            s.beforeCursor = String(before.suffix(1000))
        }
        if let pv: AXValue = attr(el, kAXPositionAttribute), let sv: AXValue = attr(el, kAXSizeAttribute) {
            var p = CGPoint.zero, z = CGSize.zero
            if AXValueGetValue(pv, .cgPoint, &p), AXValueGetValue(sv, .cgSize, &z), z.width > 0 { s.fieldFrame = CGRect(origin: p, size: z) }
        }
        return s
    }

    private static func attr<T>(_ el: AXUIElement, _ name: String) -> T? {
        var v: CFTypeRef?
        guard AXUIElementCopyAttributeValue(el, name as CFString, &v) == .success, let v else { return nil }
        return v as? T
    }

    /// Screenshot of the frontmost window of `pid` + Vision OCR, as ctx v2 screen lines (window pixels).
    static func screen(pid: pid_t, field: CGRect?) async -> (CtxV2.Screen, [Int]?)? {
        guard CGPreflightScreenCaptureAccess() else { return nil }
        do {
            let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
            let wins = content.windows.filter { $0.owningApplication?.processID == pid && $0.windowLayer == 0 && $0.frame.width > 120 && $0.frame.height > 80 }
            let center = field.map { CGPoint(x: $0.midX, y: $0.midY) }
            guard let win = center.flatMap({ c in wins.first { $0.frame.contains(c) } })
                    ?? wins.max(by: { $0.frame.width * $0.frame.height < $1.frame.width * $1.frame.height }) else { return nil }
            let filter = SCContentFilter(desktopIndependentWindow: win)
            let scale = CGFloat(min(2, filter.pointPixelScale))
            let cfg = SCStreamConfiguration()
            cfg.width = Int(win.frame.width * scale)
            cfg.height = Int(win.frame.height * scale)
            cfg.showsCursor = false
            let img = try await SCScreenshotManager.captureImage(contentFilter: filter, configuration: cfg)
            let lines = try ScreenOCR.recognize(img)
            let sx = CGFloat(img.width) / win.frame.width, sy = CGFloat(img.height) / win.frame.height
            let box = field.map { f in
                [Int((f.minX - win.frame.minX) * sx), Int((f.minY - win.frame.minY) * sy),
                 Int((f.maxX - win.frame.minX) * sx), Int((f.maxY - win.frame.minY) * sy)]
            }
            return (CtxV2.Screen(size: [img.width, img.height], lines: lines), box)
        } catch {
            return nil
        }
    }

    /// ctx v2 under the privacy level (prompts/render.py NO_SCREEN_LEVEL).
    static func build(_ s: FocusSnapshot, screen: (CtxV2.Screen, [Int]?)?, language: String?, level: String) -> CtxV2 {
        var ctx = CtxV2()
        ctx.app = .init(package: s.bundleID, label: s.appName, category: s.category)
        if let k = s.fieldKind {
            ctx.field = .init(kind: k, hint: s.hint, box: screen?.1, beforeCursor: s.beforeCursor)
        }
        ctx.language = .init(asr: language?.isEmpty == false ? language : nil)
        if s.isPassword {
            ctx.privacy = .init(level: "none", reason: "password_field")
            ctx.field?.beforeCursor = nil
        } else if s.denylisted {
            ctx.privacy = .init(level: "app_only", reason: "denylisted_app")
        } else if level != "full" {
            ctx.privacy = .init(level: level, reason: "user_setting")
        } else {
            ctx.privacy = .init(level: "full")
            ctx.screen = screen?.0
        }
        return ctx
    }
}

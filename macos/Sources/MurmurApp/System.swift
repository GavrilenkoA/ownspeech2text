import AppKit
import ApplicationServices
import AVFoundation
import ServiceManagement

/// Text into the focused field: pasteboard + synthetic ⌘V (works in every app that accepts paste).
/// The result stays on the clipboard (BRIEF: insert *and* copy) unless "restore clipboard" is on.
enum Inserter {
    static func paste(_ text: String, restoreClipboard: Bool) {
        let pb = NSPasteboard.general
        var saved: [NSPasteboardItem] = []
        if restoreClipboard {
            for item in pb.pasteboardItems ?? [] {
                let copy = NSPasteboardItem()
                for t in item.types { if let d = item.data(forType: t) { copy.setData(d, forType: t) } }
                saved.append(copy)
            }
        }
        copy(text)
        let src = CGEventSource(stateID: .combinedSessionState)
        let down = CGEvent(keyboardEventSource: src, virtualKey: 9, keyDown: true)  // kVK_ANSI_V
        let up = CGEvent(keyboardEventSource: src, virtualKey: 9, keyDown: false)
        down?.flags = .maskCommand
        up?.flags = .maskCommand
        down?.post(tap: .cghidEventTap)
        up?.post(tap: .cghidEventTap)
        if restoreClipboard {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
                pb.clearContents()
                if !saved.isEmpty { pb.writeObjects(saved) }
            }
        }
    }

    static func copy(_ text: String) {
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(text, forType: .string)
    }
}

/// Global push-to-talk / toggle key. Hold > 0.35 s = push-to-talk (release stops); a short tap starts hands-free
/// mode (the next tap stops). Esc cancels. Using the key as a modifier in a chord cancels a just-started recording.
@MainActor
final class Hotkey {
    var keyCode: UInt16 = 61
    var onPress: (() -> Void)?
    var onRelease: ((TimeInterval) -> Void)?
    var onEscape: (() -> Void)?
    var onChord: (() -> Void)?
    private var monitors: [Any] = []
    private var downAt: Date?

    func start() {
        stop()
        let flags: (NSEvent) -> Void = { [weak self] e in self?.flagsChanged(e) }
        let keys: (NSEvent) -> Void = { [weak self] e in self?.keyDown(e) }
        if let m = NSEvent.addGlobalMonitorForEvents(matching: .flagsChanged, handler: flags) { monitors.append(m) }
        if let m = NSEvent.addGlobalMonitorForEvents(matching: .keyDown, handler: keys) { monitors.append(m) }
        if let m = NSEvent.addLocalMonitorForEvents(matching: .flagsChanged, handler: { flags($0); return $0 }) { monitors.append(m) }
        if let m = NSEvent.addLocalMonitorForEvents(matching: .keyDown, handler: { keys($0); return $0 }) { monitors.append(m) }
    }

    func stop() {
        monitors.forEach(NSEvent.removeMonitor)
        monitors.removeAll()
    }

    private func flagsChanged(_ e: NSEvent) {
        guard e.keyCode == keyCode else { return }
        let isDown: Bool
        switch keyCode {
        case 63: isDown = e.modifierFlags.contains(.function)
        case 54: isDown = e.modifierFlags.contains(.command)
        default: isDown = e.modifierFlags.contains(.option)
        }
        if isDown, downAt == nil {
            downAt = Date()
            onPress?()
        } else if !isDown, let t = downAt {
            downAt = nil
            onRelease?(Date().timeIntervalSince(t))
        }
    }

    private func keyDown(_ e: NSEvent) {
        if e.keyCode == 53 { onEscape?(); return }
        if downAt != nil { onChord?() }
    }
}

enum Permissions {
    static var microphone: Bool { AVCaptureDevice.authorizationStatus(for: .audio) == .authorized }
    static var accessibility: Bool { AXIsProcessTrusted() }
    static var screen: Bool { CGPreflightScreenCaptureAccess() }

    static func requestMicrophone(_ done: @escaping @Sendable (Bool) -> Void) { AVCaptureDevice.requestAccess(for: .audio, completionHandler: done) }

    static func requestAccessibility() {
        let opts = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
        _ = AXIsProcessTrustedWithOptions(opts)
    }

    static func requestScreen() { _ = CGRequestScreenCaptureAccess() }

    static func openPane(_ anchor: String) {
        NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?\(anchor)")!)
    }

    static var launchAtLogin: Bool {
        get { SMAppService.mainApp.status == .enabled }
        set { try? newValue ? SMAppService.mainApp.register() : SMAppService.mainApp.unregister() }
    }
}

@MainActor
enum Sounds {
    static func play(_ name: String) {
        guard Settings.shared.sounds else { return }
        NSSound(named: NSSound.Name(name))?.play()
    }
}

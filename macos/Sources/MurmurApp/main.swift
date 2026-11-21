import AppKit
import MurmurCore
import SwiftUI

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate {
    private var status: NSStatusItem!
    private let hotkey = Hotkey()
    private var pressStartedRecording = false
    private var pressAt = Date()

    func applicationDidFinishLaunching(_ n: Notification) {
        NSApp.setActivationPolicy(.accessory)
        status = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        status.button?.image = NSImage(systemSymbolName: "waveform", accessibilityDescription: "Murmur")
        let menu = NSMenu()
        menu.delegate = self
        status.menu = menu

        configureHotkey()
        // Global key monitors only work once Accessibility is granted: re-arm them when that happens.
        var trusted = Permissions.accessibility
        Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
            Task { @MainActor in
                let now = Permissions.accessibility
                if now && !trusted { self?.configureHotkey() }
                trusted = now
            }
        }
        HistoryStore.shared.prune()
        Timer.scheduledTimer(withTimeInterval: 3600, repeats: true) { _ in Task { @MainActor in HistoryStore.shared.prune() } }

        let needsSetup = !Permissions.microphone || !Permissions.accessibility || !(Settings.shared.asrModel?.isInstalled ?? false)
        if needsSetup { MainWindow.shared.show(tab: .setup) }
    }

    func applicationWillTerminate(_ n: Notification) { Dictation.shared.llm.stop() }

    func configureHotkey() {
        hotkey.keyCode = Settings.shared.hotkey.keyCode
        hotkey.onPress = { [weak self] in
            guard let self else { return }
            let d = Dictation.shared
            if d.isRecording {
                self.pressStartedRecording = false
                d.stop()
            } else {
                d.start()
                self.pressStartedRecording = d.isRecording
                self.pressAt = Date()
            }
        }
        hotkey.onRelease = { [weak self] held in
            guard let self else { return }
            if self.pressStartedRecording && held > 0.35 { Dictation.shared.stop() }  // push-to-talk
            self.pressStartedRecording = false
        }
        hotkey.onEscape = { Dictation.shared.cancel() }
        hotkey.onChord = { [weak self] in
            guard let self, self.pressStartedRecording, Date().timeIntervalSince(self.pressAt) < 1.0 else { return }
            self.pressStartedRecording = false
            Dictation.shared.cancel()  // the key was used as a modifier, not for dictation
        }
        hotkey.start()
    }

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()
        let d = Dictation.shared
        let s = Settings.shared
        let rec = NSMenuItem(title: d.isRecording ? "Stop Dictation" : "Start Dictation", action: #selector(toggle), keyEquivalent: "")
        rec.target = self
        menu.addItem(rec)
        menu.addItem(withTitle: "Hotkey: \(s.hotkey.title) — hold to talk, tap for hands-free", action: nil, keyEquivalent: "").isEnabled = false
        menu.addItem(.separator())
        menu.addItem(withTitle: "Speech: \(s.asrModel?.displayName ?? "—")", action: nil, keyEquivalent: "").isEnabled = false
        menu.addItem(withTitle: "Cleanup: \(s.llmModel?.displayName ?? "off")\(d.llm.isRunning ? " · loaded" : "")", action: nil, keyEquivalent: "").isEnabled = false
        if let last = HistoryStore.shared.items.first, !last.text.isEmpty {
            menu.addItem(.separator())
            let item = NSMenuItem(title: "Copy last: " + String(last.text.prefix(40)) + (last.text.count > 40 ? "…" : ""), action: #selector(copyLast), keyEquivalent: "")
            item.target = self
            menu.addItem(item)
        }
        menu.addItem(.separator())
        for (title, tab) in [("History…", MainTab.history), ("Models…", .models), ("Settings…", .settings)] {
            let i = NSMenuItem(title: title, action: #selector(openTab(_:)), keyEquivalent: tab == .settings ? "," : "")
            i.target = self
            i.representedObject = tab.rawValue
            menu.addItem(i)
        }
        menu.addItem(.separator())
        menu.addItem(withTitle: "Quit Murmur", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
    }

    @objc func toggle() { Dictation.shared.toggle() }
    @objc func copyLast() { if let t = HistoryStore.shared.items.first?.text { Inserter.copy(t) } }
    @objc func openTab(_ sender: NSMenuItem) {
        MainWindow.shared.show(tab: MainTab(rawValue: sender.representedObject as? String ?? "") ?? .history)
    }
}

MainActor.assumeIsolated {
    let app = NSApplication.shared
    let delegate = AppDelegate()
    app.delegate = delegate
    app.run()
}

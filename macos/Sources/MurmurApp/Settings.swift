import Foundation
import MurmurCore
import SwiftUI

enum HotkeyChoice: String, CaseIterable, Identifiable {
    case rightOption, rightCommand, fn
    var id: String { rawValue }
    var title: String {
        switch self {
        case .rightOption: return "Right Option ⌥"
        case .rightCommand: return "Right Command ⌘"
        case .fn: return "Fn / 🌐"
        }
    }
    var keyCode: UInt16 {
        switch self {
        case .rightOption: return 61
        case .rightCommand: return 54
        case .fn: return 63
        }
    }
}

/// User settings (UserDefaults). Defaults follow docs/BRIEF.md: history kept a week, cleanup on, screen context on.
@MainActor
final class Settings: ObservableObject {
    static let shared = Settings()
    private let d = UserDefaults.standard

    @Published var hotkey: HotkeyChoice { didSet { d.set(hotkey.rawValue, forKey: "hotkey") } }
    @Published var language: String { didSet { d.set(language, forKey: "language") } }
    @Published var asrModelID: String { didSet { d.set(asrModelID, forKey: "asrModel") } }
    @Published var llmModelID: String { didSet { d.set(llmModelID, forKey: "llmModel") } }   // "none" = no cleanup
    @Published var contextLevel: String { didSet { d.set(contextLevel, forKey: "contextLevel") } } // full | app_only | none
    @Published var screenOCR: Bool { didSet { d.set(screenOCR, forKey: "screenOCR") } }
    @Published var restoreClipboard: Bool { didSet { d.set(restoreClipboard, forKey: "restoreClipboard") } }
    @Published var autoInsert: Bool { didSet { d.set(autoInsert, forKey: "autoInsert") } }
    @Published var sounds: Bool { didSet { d.set(sounds, forKey: "sounds") } }
    @Published var retentionDays: Int { didSet { d.set(retentionDays, forKey: "retentionDays") } }
    @Published var keepAudio: Bool { didSet { d.set(keepAudio, forKey: "keepAudio") } }
    @Published var llmIdleMinutes: Int { didSet { d.set(llmIdleMinutes, forKey: "llmIdle") } }
    @Published var asrIdleMinutes: Int { didSet { d.set(asrIdleMinutes, forKey: "asrIdle") } }
    @Published var systemPromptOverride: String { didSet { d.set(systemPromptOverride, forKey: "systemPrompt") } }
    @Published var preferences: String { didSet { d.set(preferences, forKey: "preferences") } }
    @Published var styleOverrides: [String: String] { didSet { d.set(styleOverrides, forKey: "styleOverrides") } }
    @Published var vocabulary: String { didSet { d.set(vocabulary, forKey: "vocabulary") } }

    private init() {
        let cat = Resources.catalog
        hotkey = HotkeyChoice(rawValue: d.string(forKey: "hotkey") ?? "") ?? .rightOption
        language = d.string(forKey: "language") ?? "auto"
        asrModelID = d.string(forKey: "asrModel") ?? cat.defaultASR.id
        llmModelID = d.string(forKey: "llmModel") ?? cat.defaultLLM.id
        contextLevel = d.string(forKey: "contextLevel") ?? "full"
        screenOCR = d.object(forKey: "screenOCR") as? Bool ?? true
        restoreClipboard = d.object(forKey: "restoreClipboard") as? Bool ?? false
        autoInsert = d.object(forKey: "autoInsert") as? Bool ?? true
        sounds = d.object(forKey: "sounds") as? Bool ?? true
        retentionDays = d.object(forKey: "retentionDays") as? Int ?? 7
        keepAudio = d.object(forKey: "keepAudio") as? Bool ?? true
        llmIdleMinutes = d.object(forKey: "llmIdle") as? Int ?? 5
        asrIdleMinutes = d.object(forKey: "asrIdle") as? Int ?? 10
        systemPromptOverride = d.string(forKey: "systemPrompt") ?? ""
        preferences = d.string(forKey: "preferences") ?? ""
        styleOverrides = d.dictionary(forKey: "styleOverrides") as? [String: String] ?? [:]
        vocabulary = d.string(forKey: "vocabulary") ?? ""
    }

    var asrModel: ModelEntry? { Resources.catalog.model(asrModelID) }
    var llmModel: ModelEntry? { llmModelID == "none" ? nil : Resources.catalog.model(llmModelID) }

    var cleanupConfig: CleanupConfig {
        var c = CleanupConfig(model: llmModel)
        c.systemPromptOverride = systemPromptOverride
        c.preferences = preferences
        c.styleOverrides = styleOverrides.filter { !$0.value.trimmingCharacters(in: .whitespaces).isEmpty }
        return c
    }

    static var defaultInstructions: String { Resources.systemPrompt("default.v1") }
}

import Foundation

/// Transcript -> text to insert: guard.precheck, LLM with the shipped prompt, guard (fallback to the transcript).
/// The macOS counterpart of core/dictation/Cleaner.kt.
public struct CleanupConfig: Sendable {
    public var model: ModelEntry?           // nil = no post-processing
    public var systemPromptOverride: String? // user-edited default instructions (stock models only)
    public var preferences: String?
    public var styleOverrides: [String: String] = [:]  // category -> style text
    public init(model: ModelEntry?) { self.model = model }
}

public struct CleanupResult: Sendable, Codable {
    public var text: String
    public var action: String       // accept | fallback | empty | raw
    public var reason: String
    public var llmSeconds: Double = 0
    public var promptTokens = 0
    public var predictedTokens = 0
    public var rawOutput: String?
}

public enum Cleaner {
    public static func systemPrompt(for model: ModelEntry, override: String?, preferences: String?) -> String {
        let profile = model.promptProfile ?? "default.v1"
        let base: String
        if profile.hasPrefix("finetuned") {
            base = Resources.systemPrompt(profile)
        } else if let o = override, !o.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            base = o
        } else {
            base = Resources.systemPrompt(profile)
        }
        return PromptRenderer.systemPrompt(base: base, preferences: preferences, preferencesBlock: Resources.systemPrompt("preferences_block.v1"))
    }

    public static func renderPrompt(model: ModelEntry, ctx: CtxV2?, transcript: String, config: CleanupConfig) -> String {
        let system = systemPrompt(for: model, override: config.systemPromptOverride, preferences: config.preferences)
        let cat = ctx?.app?.category ?? "default"
        let user = PromptRenderer.userMessage(ctx, transcript: transcript, tones: Resources.tones, styleOverride: config.styleOverrides[cat])
        return PromptRenderer.chat(system: system, user: user, template: model.template ?? "qwen3.5")
    }

    public static func run(transcript: String, ctx: CtxV2?, config: CleanupConfig, llm: LlamaServer) async -> CleanupResult {
        let pre = OutputGuard.precheck(transcript)
        if pre.skip { return CleanupResult(text: "", action: "empty", reason: pre.reason) }
        guard let model = config.model, model.isInstalled else {
            return CleanupResult(text: OutputGuard.fallbackText(pre.transcript, ctx), action: "raw", reason: config.model == nil ? "llm_off" : "llm_missing")
        }
        if ctx?.privacy?.reason == "password_field" {
            return CleanupResult(text: pre.transcript, action: "raw", reason: "password_field")
        }
        let prompt = renderPrompt(model: model, ctx: ctx, transcript: pre.transcript, config: config)
        do {
            let c = try await llm.complete(model: model.mainFile.path, prompt: prompt, transcript: pre.transcript)
            let v = OutputGuard.guardOutput(transcript: transcript, output: c.text, ctx: ctx)
            return CleanupResult(text: v.text, action: v.action.rawValue, reason: v.reason, llmSeconds: c.seconds,
                                 promptTokens: c.promptTokens, predictedTokens: c.predictedTokens, rawOutput: c.text)
        } catch {
            return CleanupResult(text: OutputGuard.fallbackText(pre.transcript, ctx), action: "fallback", reason: "llm_error: \(error.localizedDescription)")
        }
    }
}

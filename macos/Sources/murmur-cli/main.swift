import Foundation
import ImageIO
import MurmurCore

// Headless checks of the macOS pipeline:
//   murmur-cli selftest <repo>/prompts          ctx v2 + full-prompt goldens (byte-exact) and guard contract cases
//   murmur-cli guard < cases.jsonl              {"transcript","output","ctx"} -> {"text","action","reason"} per line
//   murmur-cli render <input.json> [template]   full prompt for a golden-style input
//   murmur-cli transcribe <audio> [lang]        whisper.cpp with the selected / default model
//   murmur-cli clean "<transcript>" [ctx.json]  LLM cleanup + guard
//   murmur-cli pipeline <audio> [lang]          transcribe + clean, with timings
//   murmur-cli ocr <image>                      Vision OCR lines
//   murmur-cli models                           catalog with install state

let args = CommandLine.arguments
func fail(_ s: String) -> Never { FileHandle.standardError.write((s + "\n").data(using: .utf8)!); exit(1) }
func out(_ s: String) { print(s) }

func installed(_ kind: String) -> ModelEntry? {
    let env = ProcessInfo.processInfo.environment[kind == "asr" ? "MURMUR_ASR" : "MURMUR_LLM"]
    let cat = Resources.catalog
    if let env, let m = cat.model(env) { return m }
    let def = kind == "asr" ? cat.defaultASR : cat.defaultLLM
    if def.isInstalled { return def }
    return (kind == "asr" ? cat.asr : cat.llm).first { $0.isInstalled }
}

func loadInput(_ path: String) -> (CtxV2, String) {
    guard let d = FileManager.default.contents(atPath: path),
          let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
          let c = j["context"], let t = j["transcript"] as? String else { fail("bad input \(path)") }
    let ctx = try! JSONDecoder().decode(CtxV2.self, from: JSONSerialization.data(withJSONObject: c))
    return (ctx, t)
}

func selftest(_ prompts: String) -> Int32 {
    var failed = 0, passed = 0
    let ex = URL(fileURLWithPath: prompts).appendingPathComponent("examples")
    let files = (try? FileManager.default.contentsOfDirectory(atPath: ex.path)) ?? []
    for f in files.sorted() where f.hasPrefix("ctxv2_") && f.hasSuffix(".input.json") {
        let stem = String(f.dropLast(".input.json".count))
        let (ctx, tr) = loadInput(ex.appendingPathComponent(f).path)
        let checks: [(String, String)] = [
            ("\(stem).context.txt", PromptRenderer.renderContext(ctx)),
        ] + ["qwen3.5", "lfm3"].map { tpl in
            let sys = PromptRenderer.systemPrompt(base: Resources.systemPrompt("default.v1"), preferences: nil, preferencesBlock: "")
            let user = PromptRenderer.userMessage(ctx, transcript: tr, tones: Resources.tones)
            return ("\(stem).\(tpl).default.v1.txt", PromptRenderer.chat(system: sys, user: user, template: tpl))
        }
        for (name, got) in checks {
            let want = (try? String(contentsOf: ex.appendingPathComponent(name), encoding: .utf8)) ?? "<missing>"
            if got == want { passed += 1 } else {
                failed += 1
                out("FAIL golden \(name)")
                let g = got.split(separator: "\n", omittingEmptySubsequences: false), w = want.split(separator: "\n", omittingEmptySubsequences: false)
                for i in 0..<max(g.count, w.count) where (i < g.count ? g[i] : "") != (i < w.count ? w[i] : "") {
                    out("  line \(i + 1)\n    want: \(i < w.count ? w[i] : "<eof>")\n    got:  \(i < g.count ? g[i] : "<eof>")")
                    break
                }
            }
        }
    }
    // guard contract (a subset of prompts/tests/test_guard.py, same expectations)
    let ctxChat = CtxV2(app: .init(label: "Telegram", category: "messaging"), field: .init(kind: "message"),
                        screen: .init(size: [1080, 2520], lines: [.init(text: "Тимур Галиев", conf: 0.9, box: [0, 0, 300, 40]),
                                                                  .init(text: "Тимофей Гальцев", conf: 0.9, box: [0, 60, 300, 100])]))
    let cases: [(String, String, CtxV2?, OutputGuard.Action, String)] = [
        ("эээ ну", "Ну.", nil, .empty, "filler_only"),
        ("Субтитры сделал DimaTorzok", "x", nil, .empty, "asr_hallucination"),
        ("ну короче давай созвонимся завтра в три", "Давай созвонимся завтра в 3.", nil, .accept, "ok"),
        ("давай созвонимся завтра", "<think>x</think>Давай созвонимся завтра.", nil, .accept, "repaired"),
        ("давай созвонимся завтра", "«Давай созвонимся завтра.»", nil, .accept, "repaired"),
        ("давай созвонимся завтра", "", nil, .fallback, "R1_empty"),
        ("давай созвонимся завтра", "<transcript>Давай</transcript>", nil, .fallback, "R2_leaked_tags"),
        ("давай созвонимся завтра", "Конечно! Давай созвонимся завтра.", nil, .fallback, "R3_preamble"),
        ("напиши маме что я задержусь на полчаса", "Write to mom that I will be half an hour late.", nil, .fallback, "R5_script"),
        ("купи молоко и хлеб", "Купи молоко и хлеб. Также не забудь оплатить интернет, позвонить бабушке и забрать посылку.", nil, .fallback, "R4_length"),
        ("тимур галеев уже дал доступ", "Тимур Галиев уже дал доступ.", ctxChat, .accept, "ok"),
        ("тимур галеев уже дал доступ", "Тимофей Гальцев уже дал доступ.", ctxChat, .fallback, "R7_distractor"),
        ("я запушил фикс в гитхаб", "Я запушил фикс в GitHub.", nil, .accept, "ok"),
    ]
    for (t, o, c, act, reason) in cases {
        let v = OutputGuard.guardOutput(transcript: t, output: o, ctx: c)
        if v.action == act && v.reason == reason { passed += 1 } else {
            failed += 1
            out("FAIL guard \(t) | \(o) -> \(v.action.rawValue)/\(v.reason), want \(act.rawValue)/\(reason)")
        }
    }
    out("selftest: \(passed) passed, \(failed) failed")
    return failed == 0 ? 0 : 1
}

func runAsync(_ body: @escaping @Sendable () async -> Int32) -> Never {
    var code: Int32 = 0
    let sem = DispatchSemaphore(value: 0)
    Task { code = await body(); sem.signal() }
    sem.wait()
    exit(code)
}

guard args.count >= 2 else { fail("usage: murmur-cli selftest|guard|render|transcribe|clean|pipeline|ocr|models …") }

switch args[1] {
case "selftest":
    exit(selftest(args.count > 2 ? args[2] : "../prompts"))

case "guard":
    while let line = readLine(strippingNewline: true) {
        guard let j = try? JSONSerialization.jsonObject(with: Data(line.utf8)) as? [String: Any] else { continue }
        var ctx: CtxV2?
        if let c = j["ctx"], !(c is NSNull) { ctx = try? JSONDecoder().decode(CtxV2.self, from: JSONSerialization.data(withJSONObject: c)) }
        let v = OutputGuard.guardOutput(transcript: j["transcript"] as? String ?? "", output: j["output"] as? String ?? "", ctx: ctx)
        let o: [String: String] = ["text": v.text, "action": v.action.rawValue, "reason": v.reason]
        out(String(decoding: try! JSONSerialization.data(withJSONObject: o, options: [.sortedKeys]), as: UTF8.self))
    }

case "render":
    let (ctx, tr) = loadInput(args[2])
    let sys = PromptRenderer.systemPrompt(base: Resources.systemPrompt("default.v1"), preferences: nil, preferencesBlock: "")
    print(PromptRenderer.chat(system: sys, user: PromptRenderer.userMessage(ctx, transcript: tr, tones: Resources.tones),
                              template: args.count > 3 ? args[3] : "qwen3.5"), terminator: "")

case "models":
    for m in Resources.catalog.models {
        out("\(m.isInstalled ? "✓" : " ") \(m.kind.padding(toLength: 4, withPad: " ", startingAt: 0)) \(m.id.padding(toLength: 34, withPad: " ", startingAt: 0)) \(m.totalBytes >> 20) MB  \(m.isDefault ? "default" : "")")
    }

case "ocr":
    guard let src = CGImageSourceCreateWithURL(URL(fileURLWithPath: args[2]) as CFURL, nil),
          let img = CGImageSourceCreateImageAtIndex(src, 0, nil) else { fail("cannot read image") }
    for l in try ScreenOCR.recognize(img) { out("\(l.box!) \(l.conf!) \(l.text)") }

case "transcribe", "pipeline":
    guard let asr = installed("asr") else { fail("no speech model installed (see `murmur-cli models`)") }
    let samples = try WAV.read(URL(fileURLWithPath: args[2]))
    let lang = args.count > 3 ? args[3] : "auto"
    let pipeline = args[1] == "pipeline"
    runAsync {
        let w = WhisperEngine()
        do {
            let r = try w.transcribe(samples, modelPath: asr.mainFile.path, language: lang)
            out("asr[\(asr.id)] \(String(format: "%.2f", r.seconds))s audio=\(String(format: "%.1f", Double(samples.count) / 16000))s lang=\(r.language): \(r.text)")
            guard pipeline else { return 0 }
            let llm = installed("llm")
            var cfg = CleanupConfig(model: llm)
            cfg.preferences = nil
            let server = LlamaServer()
            let ctx = CtxV2(app: .init(label: "Notes", category: "notes"), field: .init(kind: "note"), language: .init(asr: r.language))
            let c = await Cleaner.run(transcript: r.text, ctx: ctx, config: cfg, llm: server)
            out("llm[\(llm?.id ?? "none")] \(String(format: "%.2f", c.llmSeconds))s \(c.action)/\(c.reason): \(c.text)")
            server.stop()
            return 0
        } catch { out("error: \(error.localizedDescription)"); return 1 }
    }

case "clean":
    let tr = args[2]
    let ctx: CtxV2? = {
        guard args.count > 3, let d = FileManager.default.contents(atPath: args[3]) else { return nil }
        if let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any], let c = j["context"] {
            return try? JSONDecoder().decode(CtxV2.self, from: JSONSerialization.data(withJSONObject: c))
        }
        return try? JSONDecoder().decode(CtxV2.self, from: d)
    }()
    let llm = installed("llm")
    runAsync {
        let server = LlamaServer()
        let first = await Cleaner.run(transcript: tr, ctx: ctx, config: CleanupConfig(model: llm), llm: server)
        out("cold \(String(format: "%.2f", first.llmSeconds))s \(first.action)/\(first.reason) [\(first.promptTokens) prompt tok, \(first.predictedTokens) out tok]: \(first.text)")
        let warm = await Cleaner.run(transcript: tr, ctx: ctx, config: CleanupConfig(model: llm), llm: server)
        out("warm \(String(format: "%.2f", warm.llmSeconds))s \(warm.action)/\(warm.reason)")
        if first.action != "accept" { out("raw: \(first.rawOutput ?? "")") }
        server.stop()
        return 0
    }

default:
    fail("unknown command \(args[1])")
}

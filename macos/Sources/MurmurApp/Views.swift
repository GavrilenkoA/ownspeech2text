import AVFoundation
import Charts
import MurmurCore
import SwiftUI

// MARK: - Setup

struct SetupView: View {
    @EnvironmentObject var settings: Settings
    @EnvironmentObject var models: ModelManager
    @State private var tick = 0
    let timer = Timer.publish(every: 1.5, on: .main, in: .common).autoconnect()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text("Set up Murmur").font(.largeTitle.weight(.semibold))
                Text("Hold \(settings.hotkey.title) and speak; release to insert. Tap it once for hands-free, tap again to finish. Everything runs on this Mac.")
                    .foregroundStyle(.secondary)
                step("Microphone", "To hear you.", Permissions.microphone) { Permissions.requestMicrophone { _ in } }
                step("Accessibility", "For the hotkey, reading the focused field and pasting the text.", Permissions.accessibility) {
                    Permissions.requestAccessibility(); Permissions.openPane("Privacy_Accessibility")
                }
                step("Screen Recording (optional)", "Reads the text on screen so names and terms are spelled right. Never leaves the Mac.", Permissions.screen) {
                    Permissions.requestScreen(); Permissions.openPane("Privacy_ScreenCapture")
                }
                modelStep(Resources.catalog.model(settings.asrModelID) ?? Resources.catalog.defaultASR, "Speech model")
                if let m = settings.llmModel { modelStep(m, "Cleanup model") }
                if LlamaServer.findBinary() == nil {
                    Label("llama-server not found — run `brew install llama.cpp` for text cleanup.", systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.orange)
                }
                Text("After granting Accessibility, quit and reopen Murmur if the hotkey does not react.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            .padding(28)
            .id(tick)
        }
        .onReceive(timer) { _ in tick += 1 }
    }

    func step(_ title: String, _ detail: String, _ ok: Bool, action: @escaping () -> Void) -> some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: ok ? "checkmark.circle.fill" : "circle").font(.title2).foregroundStyle(ok ? .green : .secondary)
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.headline)
                Text(detail).foregroundStyle(.secondary).font(.callout)
            }
            Spacer()
            if !ok { Button("Grant…", action: action) }
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 12).fill(.quaternary.opacity(0.5)))
    }

    func modelStep(_ m: ModelEntry, _ title: String) -> some View {
        let _ = models.revision
        return HStack(alignment: .top, spacing: 14) {
            Image(systemName: m.isInstalled ? "checkmark.circle.fill" : "arrow.down.circle").font(.title2)
                .foregroundStyle(m.isInstalled ? .green : .accentColor)
            VStack(alignment: .leading, spacing: 3) {
                Text("\(title): \(m.displayName)").font(.headline)
                Text("\(ByteCountFormatter.string(fromByteCount: m.totalBytes, countStyle: .file)) from Hugging Face").foregroundStyle(.secondary).font(.callout)
                if let p = models.progress[m.id] { ProgressView(value: p).frame(width: 260) }
                if let e = models.errors[m.id] { Text(e).foregroundStyle(.red).font(.caption) }
            }
            Spacer()
            if !m.isInstalled && models.progress[m.id] == nil { Button("Download") { models.download(m) } }
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 12).fill(.quaternary.opacity(0.5)))
    }
}

// MARK: - History

struct HistoryView: View {
    @EnvironmentObject var store: HistoryStore
    @EnvironmentObject var settings: Settings
    @State private var selection: HistoryItem.ID?
    @State private var query = ""

    var filtered: [HistoryItem] {
        query.isEmpty ? store.items : store.items.filter { $0.text.localizedCaseInsensitiveContains(query) || $0.transcript.localizedCaseInsensitiveContains(query) }
    }

    var body: some View {
        HSplitView {
            List(selection: $selection) {
                ForEach(groups, id: \.0) { day, items in
                    Section(day) {
                        ForEach(items) { it in row(it).tag(it.id) }
                    }
                }
            }
            .searchable(text: $query)
            .frame(minWidth: 300, idealWidth: 360)
            .overlay {
                if store.items.isEmpty {
                    VStack(spacing: 8) {
                        Image(systemName: "waveform").font(.largeTitle).foregroundStyle(.secondary)
                        Text("No dictations yet").font(.headline)
                        Text("Hold \(settings.hotkey.title) in any app and speak.").foregroundStyle(.secondary)
                    }
                }
            }
            Group {
                if let id = selection, let it = store.items.first(where: { $0.id == id }) {
                    HistoryDetail(item: it).id(it.id)
                } else {
                    Text("Select a dictation").foregroundStyle(.secondary).frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .frame(minWidth: 380)
        }
        .navigationTitle("History")
        .toolbar {
            Text("Kept \(settings.retentionDays) days").foregroundStyle(.secondary).font(.caption)
        }
    }

    var groups: [(String, [HistoryItem])] {
        let f = DateFormatter()
        f.dateStyle = .medium
        f.doesRelativeDateFormatting = true
        var out: [(String, [HistoryItem])] = []
        for it in filtered {
            let k = f.string(from: it.date)
            if out.last?.0 == k { out[out.count - 1].1.append(it) } else { out.append((k, [it])) }
        }
        return out
    }

    func row(_ it: HistoryItem) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(it.text.isEmpty ? (it.transcript.isEmpty ? "—" : it.transcript) : it.text).lineLimit(2)
            HStack(spacing: 6) {
                Text(it.date, style: .time)
                if let a = it.appName { Text("· \(a)") }
                if it.action == "fallback" { Text("· raw").foregroundStyle(.orange) }
            }
            .font(.caption).foregroundStyle(.secondary)
        }
        .padding(.vertical, 3)
    }
}

struct HistoryDetail: View {
    @State var item: HistoryItem
    @EnvironmentObject var store: HistoryStore
    @State private var player: AVAudioPlayer?
    @State private var retrying = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(item.text.isEmpty ? "(nothing inserted)" : item.text).font(.title3).textSelection(.enabled)
                HStack {
                    Button { Inserter.copy(item.text) } label: { Label("Copy", systemImage: "doc.on.doc") }
                    if let url = item.audioURL, FileManager.default.fileExists(atPath: url.path) {
                        Button {
                            if player?.isPlaying == true { player?.stop() } else { player = try? AVAudioPlayer(contentsOf: url); player?.play() }
                        } label: { Label("Play", systemImage: "play.fill") }
                    }
                    Button {
                        retrying = true
                        Task { item = await Dictation.shared.retry(item); retrying = false }
                    } label: { Label(retrying ? "Retrying…" : "Retry cleanup", systemImage: "arrow.clockwise") }
                        .disabled(retrying || item.transcript.isEmpty)
                    Spacer()
                    Button(role: .destructive) { store.delete(item) } label: { Image(systemName: "trash") }
                }
                GroupBox("Transcript (speech model)") {
                    Text(item.transcript.isEmpty ? "—" : item.transcript).frame(maxWidth: .infinity, alignment: .leading).textSelection(.enabled)
                }
                GroupBox("Details") {
                    Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 6) {
                        meta("When", item.date.formatted(date: .abbreviated, time: .standard))
                        meta("App", [item.appName, item.category].compactMap { $0 }.joined(separator: " · "))
                        meta("Language", item.language ?? "—")
                        meta("Speech", "\(item.asrModel ?? "—") · \(String(format: "%.2f", item.asrSeconds)) s for \(String(format: "%.1f", item.audioSeconds)) s audio")
                        meta("Cleanup", "\(item.llmModel ?? "off") · \(String(format: "%.2f", item.llmSeconds)) s · \(item.action)/\(item.reason)")
                        meta("End of speech → text", String(format: "%.2f s", item.totalSeconds))
                        meta("Screen lines", "\(item.screenLines)")
                        meta("Prompt / guard", "\(item.promptVersion ?? "—") / \(item.guardVersion)")
                        meta("Inserted", item.inserted ? "yes (and copied)" : "copied only" + (item.insertNote.map { " · \($0)" } ?? ""))
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(20)
        }
    }

    func meta(_ k: String, _ v: String) -> some View {
        GridRow { Text(k).foregroundStyle(.secondary); Text(v).textSelection(.enabled) }
    }
}

// MARK: - Dashboard

struct DashboardView: View {
    @EnvironmentObject var store: HistoryStore

    var items: [HistoryItem] { store.items.filter { !$0.text.isEmpty } }
    var words: Int { items.reduce(0) { $0 + $1.words } }
    var speaking: Double { items.reduce(0) { $0 + $1.audioSeconds } }
    var saved: Double { max(0, Double(words) / 40 * 60 - speaking) }  // vs typing at 40 wpm
    var latency: Double { items.isEmpty ? 0 : items.map(\.totalSeconds).sorted()[items.count / 2] }
    var wpm: Double { speaking > 0 ? Double(words) / (speaking / 60) : 0 }
    var cleanRate: Double {
        let llm = items.filter { $0.action == "accept" || $0.action == "fallback" }
        return llm.isEmpty ? 0 : Double(llm.filter { $0.action == "accept" }.count) / Double(llm.count)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text("Dashboard").font(.largeTitle.weight(.semibold))
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 14), count: 3), spacing: 14) {
                    tile("Words dictated", "\(words)", "\(items.count) dictations")
                    tile("Time saved", Self.dur(saved), "vs typing at 40 wpm")
                    tile("Speaking pace", wpm > 0 ? "\(Int(wpm)) wpm" : "—", "words per minute of speech")
                    tile("Latency (median)", items.isEmpty ? "—" : String(format: "%.2f s", latency), "end of speech → text")
                    tile("Cleanup accepted", items.isEmpty ? "—" : "\(Int(cleanRate * 100))%", "rest fell back to the raw transcript")
                    tile("Avg length", items.isEmpty ? "—" : "\(words / max(1, items.count)) words", "per dictation")
                }
                if !items.isEmpty {
                    GroupBox("Words per day") {
                        Chart(perDay, id: \.0) { d in
                            BarMark(x: .value("Day", d.0, unit: .day), y: .value("Words", d.1)).foregroundStyle(Color.accentColor.gradient)
                        }
                        .frame(height: 180)
                        .padding(.top, 6)
                    }
                    GroupBox("Top apps") {
                        VStack(alignment: .leading, spacing: 8) {
                            ForEach(topApps, id: \.0) { app, n in
                                HStack {
                                    Text(app)
                                    Spacer()
                                    Text("\(n) words").foregroundStyle(.secondary).monospacedDigit()
                                }
                            }
                        }
                        .padding(.top, 4)
                    }
                }
            }
            .padding(28)
        }
    }

    var perDay: [(Date, Int)] {
        Dictionary(grouping: items) { Calendar.current.startOfDay(for: $0.date) }
            .map { ($0.key, $0.value.reduce(0) { $0 + $1.words }) }.sorted { $0.0 < $1.0 }
    }

    var topApps: [(String, Int)] {
        Dictionary(grouping: items) { $0.appName ?? "Unknown" }
            .map { ($0.key, $0.value.reduce(0) { $0 + $1.words }) }.sorted { $0.1 > $1.1 }.prefix(6).map { $0 }
    }

    func tile(_ title: String, _ value: String, _ sub: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.callout).foregroundStyle(.secondary)
            Text(value).font(.system(size: 28, weight: .semibold, design: .rounded)).monospacedDigit()
            Text(sub).font(.caption).foregroundStyle(.tertiary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 14).fill(.quaternary.opacity(0.5)))
    }

    static func dur(_ s: Double) -> String {
        s < 60 ? "\(Int(s)) s" : s < 3600 ? "\(Int(s / 60)) min" : String(format: "%.1f h", s / 3600)
    }
}

// MARK: - Models

struct ModelsView: View {
    @EnvironmentObject var settings: Settings
    @EnvironmentObject var models: ModelManager

    var body: some View {
        let _ = models.revision
        Form {
            Section {
                ForEach(Resources.catalog.asr) { m in
                    ModelRow(model: m, selected: settings.asrModelID == m.id) { settings.asrModelID = m.id }
                }
            } header: {
                Text("Speech recognition (whisper.cpp, Metal)")
            }
            Section {
                HStack {
                    Image(systemName: settings.llmModelID == "none" ? "largecircle.fill.circle" : "circle").foregroundStyle(Color.accentColor)
                    VStack(alignment: .leading) {
                        Text("None").font(.headline)
                        Text("Insert the raw transcript (Whisper already adds punctuation).").font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                }
                .contentShape(Rectangle())
                .onTapGesture { settings.llmModelID = "none" }
                ForEach(Resources.catalog.llm) { m in
                    ModelRow(model: m, selected: settings.llmModelID == m.id) { settings.llmModelID = m.id }
                }
            } header: {
                Text("Cleanup (llama.cpp, Q4 GGUF) — fillers, self-corrections, punctuation, names from the screen")
            } footer: {
                Text("Models load when you start dictating and unload after \(settings.llmIdleMinutes) min idle. Files: ~/Library/Application Support/Murmur/models")
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Models")
    }
}

struct ModelRow: View {
    let model: ModelEntry
    let selected: Bool
    let select: () -> Void
    @EnvironmentObject var models: ModelManager

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top) {
                Image(systemName: selected ? "largecircle.fill.circle" : "circle").foregroundStyle(Color.accentColor)
                    .onTapGesture { if model.isInstalled { select() } }
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        Text(model.displayName).font(.headline)
                        if model.isDefault { Text("Recommended").font(.caption2.weight(.semibold)).padding(.horizontal, 6).padding(.vertical, 2)
                            .background(Capsule().fill(Color.accentColor.opacity(0.15))) }
                    }
                    Text("\(model.publisher) · \(model.paramsLabel) params · \(ByteCountFormatter.string(fromByteCount: model.totalBytes, countStyle: .file)) · \(model.license)")
                        .font(.caption).foregroundStyle(.secondary)
                    Text("Languages: \(model.languages)").font(.caption).foregroundStyle(.secondary)
                    Text(model.description).font(.caption)
                }
                Spacer()
                actions
            }
            if let p = models.progress[model.id] {
                HStack { ProgressView(value: p); Text("\(Int(p * 100))%").monospacedDigit().font(.caption) }
            }
            if let e = models.errors[model.id] { Text(e).foregroundStyle(.red).font(.caption) }
            if let b = models.bench[model.id] {
                Text(b).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                    .padding(8).frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: 8).fill(.quaternary.opacity(0.6)))
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .onTapGesture { if model.isInstalled { select() } }
    }

    @ViewBuilder var actions: some View {
        if models.progress[model.id] != nil {
            Button("Cancel") { models.cancel(model) }
        } else if model.isInstalled {
            HStack {
                Button("Benchmark") { models.benchmark(model) }.disabled(models.benchRunning.contains(model.id))
                Button(role: .destructive) { models.delete(model) } label: { Image(systemName: "trash") }
            }
        } else {
            Button("Download") { models.download(model) }
        }
    }
}

// MARK: - Settings

struct SettingsView: View {
    @EnvironmentObject var s: Settings
    @State private var launchAtLogin = Permissions.launchAtLogin
    @State private var styleCategory = "messaging"

    var body: some View {
        Form {
            Section("Dictation") {
                Picker("Hotkey", selection: $s.hotkey) { ForEach(HotkeyChoice.allCases) { Text($0.title).tag($0) } }
                    .onChange(of: s.hotkey) { _ in (NSApp.delegate as? AppDelegate)?.configureHotkey() }
                Text("Hold to talk, release to insert. Tap once for hands-free, tap again to finish. Esc cancels.")
                    .font(.caption).foregroundStyle(.secondary)
                Picker("Spoken language", selection: $s.language) {
                    Text("Auto-detect").tag("auto"); Text("Russian").tag("ru"); Text("English").tag("en")
                    Text("Ukrainian").tag("uk"); Text("German").tag("de"); Text("Spanish").tag("es"); Text("French").tag("fr")
                }
                TextField("Vocabulary hint for speech model", text: $s.vocabulary, prompt: Text("e.g. ClickHouse, Kubernetes, Тимур Галиев"))
                Toggle("Paste into the focused field", isOn: $s.autoInsert)
                Toggle("Restore previous clipboard after pasting", isOn: $s.restoreClipboard)
                Toggle("Sounds", isOn: $s.sounds)
                Toggle("Launch at login", isOn: $launchAtLogin).onChange(of: launchAtLogin) { v in Permissions.launchAtLogin = v }
            }
            Section {
                Picker("Context sent to the cleanup model", selection: $s.contextLevel) {
                    Text("App, field and screen text").tag("full")
                    Text("App and field type only").tag("app_only")
                    Text("Nothing").tag("none")
                }
                Toggle("Read the screen (OCR) for names and terms", isOn: $s.screenOCR).disabled(s.contextLevel != "full")
            } header: { Text("Privacy") } footer: {
                Text("Everything stays on this Mac. Password fields are never read and never go to the model; password managers get app-only context.")
            }
            Section {
                TextEditor(text: $s.preferences).font(.body).frame(minHeight: 60)
                    .overlay(alignment: .topLeading) {
                        if s.preferences.isEmpty { Text("e.g. Use British spelling. Write «ё». Never use exclamation marks.").foregroundStyle(.tertiary).padding(6).allowsHitTesting(false) }
                    }
            } header: { Text("My preferences") } footer: { Text("Applied on top of the instructions for every model.") }
            Section {
                TextEditor(text: Binding(get: { s.systemPromptOverride.isEmpty ? Settings.defaultInstructions : s.systemPromptOverride },
                                         set: { s.systemPromptOverride = $0 == Settings.defaultInstructions ? "" : $0 }))
                    .font(.system(.caption, design: .monospaced)).frame(minHeight: 220)
                HStack {
                    Text(s.systemPromptOverride.isEmpty ? "Shipped prompt default.v1" : "Edited").font(.caption).foregroundStyle(.secondary)
                    Spacer()
                    Button("Reset to default") { s.systemPromptOverride = "" }.disabled(s.systemPromptOverride.isEmpty)
                }
            } header: { Text("Cleanup instructions") }
            Section("Tone per app") {
                Picker("Category", selection: $styleCategory) {
                    ForEach(Resources.tones.categories.keys.sorted(), id: \.self) { Text(Resources.tones.categories[$0]!.label).tag($0) }
                }
                TextField("Style", text: Binding(get: { s.styleOverrides[styleCategory] ?? "" }, set: { s.styleOverrides[styleCategory] = $0 }),
                          prompt: Text(Resources.tones.categories[styleCategory]?.text ?? ""), axis: .vertical)
                    .lineLimit(2...5)
            }
            Section("Memory and storage") {
                Stepper("Unload cleanup model after \(s.llmIdleMinutes) min idle", value: $s.llmIdleMinutes, in: 1...120)
                Stepper("Unload speech model after \(s.asrIdleMinutes) min idle", value: $s.asrIdleMinutes, in: 1...120)
                Stepper("Keep history \(s.retentionDays) days", value: $s.retentionDays, in: 1...90)
                Toggle("Keep audio in history", isOn: $s.keepAudio)
                HStack {
                    Button("Open data folder") { NSWorkspace.shared.open(Paths.support) }
                    Button("Delete all history", role: .destructive) { HistoryStore.shared.deleteAll() }
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Settings")
    }
}

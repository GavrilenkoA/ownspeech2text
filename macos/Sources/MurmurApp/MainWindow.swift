import AppKit
import MurmurCore
import SwiftUI

enum MainTab: String, CaseIterable, Identifiable {
    case setup, history, dashboard, models, settings
    var id: String { rawValue }
    var title: String {
        switch self {
        case .setup: return "Setup"
        case .history: return "History"
        case .dashboard: return "Dashboard"
        case .models: return "Models"
        case .settings: return "Settings"
        }
    }
    var icon: String {
        switch self {
        case .setup: return "checklist"
        case .history: return "clock.arrow.circlepath"
        case .dashboard: return "chart.bar.xaxis"
        case .models: return "cpu"
        case .settings: return "gearshape"
        }
    }
}

final class Nav: ObservableObject {
    @Published var tab: MainTab = .history
}

@MainActor
final class MainWindow: NSObject, NSWindowDelegate {
    static let shared = MainWindow()
    private var window: NSWindow?
    let nav = Nav()

    func show(tab: MainTab) {
        nav.tab = tab
        if window == nil {
            let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 960, height: 640),
                             styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                             backing: .buffered, defer: false)
            w.title = "Murmur"
            w.titlebarAppearsTransparent = true
            w.isReleasedWhenClosed = false
            w.minSize = NSSize(width: 820, height: 520)
            w.contentView = NSHostingView(rootView: MainView()
                .environmentObject(nav)
                .environmentObject(Settings.shared)
                .environmentObject(HistoryStore.shared)
                .environmentObject(ModelManager.shared)
                .environmentObject(Dictation.shared))
            w.center()
            w.delegate = self
            window = w
        }
        NSApp.setActivationPolicy(.regular)
        window?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    func windowWillClose(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)
    }
}

struct MainView: View {
    @EnvironmentObject var nav: Nav

    var body: some View {
        NavigationSplitView {
            List(MainTab.allCases, selection: Binding(get: { nav.tab }, set: { if let t = $0 { nav.tab = t } })) { t in
                Label(t.title, systemImage: t.icon).tag(t)
            }
            .navigationSplitViewColumnWidth(min: 170, ideal: 190)
        } detail: {
            switch nav.tab {
            case .setup: SetupView()
            case .history: HistoryView()
            case .dashboard: DashboardView()
            case .models: ModelsView()
            case .settings: SettingsView()
            }
        }
    }
}

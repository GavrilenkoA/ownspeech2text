import AppKit
import SwiftUI

/// The floating pill at the bottom of the screen (the macOS form of the Android bubble). Never takes focus,
/// so the text still lands in the app the user was typing in. Click it to stop.
@MainActor
final class HUD {
    static let shared = HUD()
    private var panel: NSPanel?

    func show() {
        let p = panel ?? make()
        position(p)
        p.orderFrontRegardless()
    }

    func hide() { panel?.orderOut(nil) }

    private func make() -> NSPanel {
        let p = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 300, height: 56),
                        styleMask: [.nonactivatingPanel, .borderless], backing: .buffered, defer: false)
        p.isFloatingPanel = true
        p.level = .statusBar
        p.backgroundColor = .clear
        p.isOpaque = false
        p.hasShadow = false
        p.hidesOnDeactivate = false
        p.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
        let host = NSHostingView(rootView: HUDView().environmentObject(Dictation.shared))
        host.frame = p.contentView!.bounds
        host.autoresizingMask = [.width, .height]
        p.contentView = host
        panel = p
        return p
    }

    private func position(_ p: NSPanel) {
        let mouse = NSEvent.mouseLocation
        let screen = NSScreen.screens.first { $0.frame.contains(mouse) } ?? NSScreen.main
        guard let f = screen?.visibleFrame else { return }
        p.setFrameOrigin(NSPoint(x: f.midX - p.frame.width / 2, y: f.minY + 28))
    }
}

struct HUDView: View {
    @EnvironmentObject var d: Dictation
    @State private var levels: [CGFloat] = Array(repeating: 0.05, count: 18)

    var body: some View {
        HStack(spacing: 10) {
            icon
            content
        }
        .padding(.horizontal, 16)
        .frame(height: 40)
        .background(Capsule().fill(.black.opacity(0.82)))
        .overlay(Capsule().strokeBorder(.white.opacity(0.12)))
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .contentShape(Capsule())
        .onTapGesture { if d.isRecording { d.stop() } }
        .onChange(of: d.level) { l in
            levels.removeFirst()
            levels.append(max(0.06, CGFloat(l)))
        }
    }

    @ViewBuilder private var icon: some View {
        switch d.phase {
        case .recording: Circle().fill(Color.red).frame(width: 9, height: 9)
        case .processing: ProgressView().controlSize(.small).colorScheme(.dark)
        case .done(_, let ok): Image(systemName: ok ? "checkmark.circle.fill" : "exclamationmark.circle.fill")
            .foregroundStyle(ok ? Color.green : Color.orange)
        case .idle: EmptyView()
        }
    }

    @ViewBuilder private var content: some View {
        switch d.phase {
        case .recording(let since):
            HStack(alignment: .center, spacing: 3) {
                ForEach(levels.indices, id: \.self) { i in
                    Capsule().fill(.white.opacity(0.9)).frame(width: 3, height: 4 + levels[i] * 22)
                }
            }
            .animation(.easeOut(duration: 0.08), value: levels)
            TimelineView(.periodic(from: since, by: 1)) { ctx in
                Text(Self.clock(ctx.date.timeIntervalSince(since)))
                    .font(.system(size: 12, weight: .medium).monospacedDigit())
                    .foregroundStyle(.white.opacity(0.7))
            }
        case .processing(let s), .done(let s, _):
            Text(s).font(.system(size: 13, weight: .medium)).foregroundStyle(.white).lineLimit(1)
        case .idle:
            EmptyView()
        }
    }

    static func clock(_ t: TimeInterval) -> String { String(format: "%d:%02d", Int(t) / 60, Int(t) % 60) }
}

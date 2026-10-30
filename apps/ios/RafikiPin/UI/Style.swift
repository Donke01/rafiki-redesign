import SwiftUI
import UIKit

/// Colours that mean the same thing everywhere (same as Android and the web app): blue = pinning,
/// green = visits and the map, purple = new sites, amber = repairs, teal = due a visit, coral = you, slate = everything.
struct Hue { let fg: Color; let bg: Color }

private let HUE_LIGHT: [String: Hue] = [
    "blue": Hue(fg: Color(hex: 0x0A78C2), bg: Color(hex: 0xE3F1FC)), "green": Hue(fg: Color(hex: 0x1C8A50), bg: Color(hex: 0xE2F4EA)),
    "purple": Hue(fg: Color(hex: 0x6E46C8), bg: Color(hex: 0xEEE7FB)), "amber": Hue(fg: Color(hex: 0xB86E00), bg: Color(hex: 0xFDF0DC)),
    "teal": Hue(fg: Color(hex: 0x0E7F86), bg: Color(hex: 0xDCF2F3)), "coral": Hue(fg: Color(hex: 0xD2553A), bg: Color(hex: 0xFCE9E4)),
    "slate": Hue(fg: Color(hex: 0x36505F), bg: Color(hex: 0xE8EDF1)),
]
private let HUE_DARK: [String: Hue] = [
    "blue": Hue(fg: Color(hex: 0x5BB4F2), bg: Color(hex: 0x12324A)), "green": Hue(fg: Color(hex: 0x5FD08F), bg: Color(hex: 0x123222)),
    "purple": Hue(fg: Color(hex: 0xB49BFF), bg: Color(hex: 0x2A2145)), "amber": Hue(fg: Color(hex: 0xF2B35B), bg: Color(hex: 0x3A2A10)),
    "teal": Hue(fg: Color(hex: 0x5CD3D6), bg: Color(hex: 0x0F3133)), "coral": Hue(fg: Color(hex: 0xFF9A82), bg: Color(hex: 0x3D1F18)),
    "slate": Hue(fg: Color(hex: 0xA9BAC6), bg: Color(hex: 0x22303A)),
]

extension Palette {
    /// The colour for an action, filter or tab key.
    func hue(_ key: String) -> Hue {
        let name: String
        switch key {
        case "pin", "missing", "list": name = "blue"
        case "visit", "near", "map": name = "green"
        case "new": name = "purple"
        case "repairs", "office": name = "amber"
        case "due": name = "teal"
        case "me", "stories": name = "coral"
        default: name = "slate"
        }
        return (dark ? HUE_DARK : HUE_LIGHT)[name]!
    }
}

let PurpleBrush = LinearGradient(colors: [Color(hex: 0x8B6AE6), Color(hex: 0x6E46C8)], startPoint: .topLeading, endPoint: .bottomTrailing)

/// The soft coloured light behind every screen.
struct AppBackground: View {
    var body: some View {
        GeometryReader { g in
            let w = g.size.width, h = g.size.height
            ZStack {
                (C.dark ? Color(hex: 0x0B1116) : Color(hex: 0xF4F8FB))
                if C.dark {
                    blob(Color(hex: 0x1E78C8, alpha: 0.30), w * 0.9).position(x: 0, y: 0)
                    blob(Color(hex: 0x148C78, alpha: 0.20), w * 0.7).position(x: w, y: h * 0.12)
                    blob(Color(hex: 0x6E50C8, alpha: 0.16), w * 0.9).position(x: w * 0.85, y: h)
                } else {
                    blob(Color(hex: 0x6EC3FF, alpha: 0.38), w * 0.95).position(x: 0, y: 0)
                    blob(Color(hex: 0x78E8D2, alpha: 0.30), w * 0.75).position(x: w, y: h * 0.12)
                    blob(Color(hex: 0xB9A5FF, alpha: 0.20), w * 0.95).position(x: w * 0.85, y: h * 0.98)
                    blob(Color(hex: 0x6EC3FF, alpha: 0.16), w * 0.7).position(x: w * 0.05, y: h * 0.9)
                }
            }
        }.ignoresSafeArea()
    }
    private func blob(_ c: Color, _ r: CGFloat) -> some View {
        Circle().fill(RadialGradient(colors: [c, c.opacity(0)], center: .center, startRadius: 0, endRadius: r)).frame(width: r * 2, height: r * 2)
    }
}

/// Animations, switched off when the phone asks for less motion.
enum Motion {
    static var on: Bool { !UIAccessibility.isReduceMotionEnabled }
}

/// Cards rise in one after another with a soft spring.
private struct Rise: ViewModifier {
    let index: Int; let step: Double
    @State private var shown = false
    func body(content: Content) -> some View {
        content
            .opacity(shown ? 1 : 0)
            .offset(y: shown ? 0 : 18)
            .scaleEffect(shown ? 1 : 0.985, anchor: .top)
            .onAppear {
                guard !shown else { return }
                if Motion.on { withAnimation(.spring(response: 0.5, dampingFraction: 0.8).delay(Double(min(index, 10)) * step)) { shown = true } } else { shown = true }
            }
    }
}
extension View {
    func rise(_ index: Int = 0, step: Double = 0.045) -> some View { modifier(Rise(index: index, step: step)) }
}

/// A number that counts up when it first shows.
struct CountUp: View {
    let value: Int; let font: Font; let color: Color
    @State private var shown = 0
    var body: some View {
        Text("\(shown)").font(font).foregroundColor(color)
            .task(id: value) {
                guard Motion.on, value > 0 else { shown = value; return }
                let steps = 24
                for i in 1...steps { shown = Int((Double(value) * Double(i) / Double(steps)).rounded()); try? await Task.sleep(nanoseconds: 30_000_000) }
            }
    }
}

/// A gentle tap the phone can feel.
enum Haptic {
    static func tap() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
    static func ok() { UINotificationFeedbackGenerator().notificationOccurred(.success) }
    static func bad() { UINotificationFeedbackGenerator().notificationOccurred(.error) }
}

/// "Habari" initials in a blue circle.
struct Avatar: View {
    let name: String; var size: CGFloat = 36
    var body: some View {
        Text(name.split(separator: " ").compactMap { $0.first.map { String($0).uppercased() } }.prefix(2).joined())
            .font(.mont(size * 0.36, .bold)).foregroundColor(.white)
            .frame(width: size, height: size)
            .background(LinearGradient(colors: [Color(hex: 0x47B4F5), Color(hex: 0x0A78C2)], startPoint: .topLeading, endPoint: .bottomTrailing))
            .clipShape(Circle())
    }
}

/// A list to choose one from, sliding up from the bottom (instead of a web-style drop-down).
struct PickSheet: View {
    let title: String; let options: [(String, String, String)]; let selected: String; let pick: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title).font(.tLarge).foregroundColor(C.ink).padding(.bottom, 10)
            ForEach(options, id: \.0) { k, label, icon in
                Button { Haptic.tap(); pick(k); dismiss() } label: {
                    HStack(spacing: 12) {
                        IconTile(icon: icon, tint: C.blue, bg: C.tint, size: 38)
                        Text(label).font(.tSmall).foregroundColor(C.ink)
                        Spacer()
                        if k == selected { Icon("check", size: 20, color: C.blue) }
                    }.padding(.vertical, 8).contentShape(Rectangle())
                }.buttonStyle(.plain)
            }
        }
        .padding(20)
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
    }
}

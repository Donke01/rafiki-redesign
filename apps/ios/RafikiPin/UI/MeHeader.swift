import SwiftUI
import UIKit

// The app header (Android 2.13.1, 2.15, 2.25): Me (a pill with your photo) or Back on the left, the RAFIKI logo in the
// middle, the notifications bell on the right. And what sits over every screen: a person's contact sheet, the update
// pop-up, the welcome screens and the one-time "Me is here now" hint.

/// Things any screen in the Me area can ask for.
@MainActor final class MeNav: ObservableObject {
    static let shared = MeNav()
    /// Show the welcome screens (first time on this phone, or Help › Show the welcome screens again).
    @Published var welcome = false
}

/// A short message at the bottom of the screen for a moment ("Photo saved ✓").
@MainActor final class MeToast: ObservableObject {
    static let shared = MeToast()
    @Published var text: String?
    static func say(_ s: String) {
        shared.text = s
        Task { @MainActor in try? await Task.sleep(nanoseconds: 2_500_000_000); if shared.text == s { shared.text = nil } }
    }
}

private struct MeToastView: View {
    @ObservedObject private var toast = MeToast.shared
    var body: some View {
        ZStack {
            if let s = toast.text {
                Text(s).font(.mont(14, .bold)).foregroundColor(.white).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 18).padding(.vertical, 12).background(Capsule().fill(Color(hex: 0x04263F, alpha: 0.92)))
                    .padding(.horizontal, 24).padding(.bottom, 92)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .accessibilityAddTraits(.updatesFrequently)
            }
        }
        .animation(Motion.on ? .spring(response: 0.35, dampingFraction: 0.85) : nil, value: toast.text)
        .allowsHitTesting(false)
    }
}

/// The RAFIKI logo and "Pin": the black logo on light screens, the white one on dark.
struct Wordmark: View {
    var height: CGFloat = 24
    /// true: the white logo (on a dark background); nil: follows the app's colours.
    var light: Bool? = nil
    var body: some View {
        let white = light ?? C.dark
        HStack(spacing: height / 3) {
            Image(white ? "RafikiLogoLight" : "RafikiLogoDark").resizable().scaledToFit().frame(height: height)
            Text("Pin").font(.mont(height * 0.72, .heavy)).foregroundColor(white ? Color(hex: 0x5BB4F2) : Color(hex: 0x0A78C2)).fixedSize()
        }
        .accessibilityElement(children: .ignore).accessibilityLabel("RAFIKI Pin").accessibilityIdentifier("wordmark")
    }
    /// About how wide it is, to plan the header.
    static func width(_ height: CGFloat = 24) -> CGFloat {
        let pin = ("Pin" as NSString).size(withAttributes: [.font: UIFont.systemFont(ofSize: height * 0.72, weight: .heavy)]).width * 1.1
        return height * 330 / 113 + height / 3 + pin
    }
}

/// Left, middle and right: the middle centred on the screen when there's room, never under the sides.
struct MeHeaderLayout: Layout {
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let w = proposal.width ?? 375
        let h = subviews.map { $0.sizeThatFits(.unspecified).height }.max() ?? 0
        return CGSize(width: w, height: max(56, h))
    }
    func placeSubviews(in b: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard subviews.count == 3 else { return }
        let gap: CGFloat = 8
        let l = subviews[0].sizeThatFits(.unspecified), r = subviews[2].sizeThatFits(.unspecified)
        let room = max(0, b.width - l.width - r.width - 2 * gap)
        let mid = subviews[1].sizeThatFits(ProposedViewSize(width: room, height: nil))
        let mw = min(mid.width, room)
        subviews[0].place(at: CGPoint(x: b.minX, y: b.midY), anchor: .leading, proposal: ProposedViewSize(l))
        subviews[2].place(at: CGPoint(x: b.maxX, y: b.midY), anchor: .trailing, proposal: ProposedViewSize(r))
        let lo = b.minX + l.width + gap, hi = b.maxX - r.width - gap - mw
        let x = min(max(b.midX - mw / 2, lo), max(lo, hi))
        subviews[1].place(at: CGPoint(x: x, y: b.midY), anchor: .leading, proposal: ProposedViewSize(width: mw, height: mid.height))
    }
}

/// The app header. Also keeps the bell, the faces and the update check up to date while the app is open.
struct MeHeaderBar: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.scenePhase) private var phase
    @State private var actAt = Date.distantPast
    var body: some View {
        GeometryReader { g in
            let word = MeHeaderBar.roomForWord(g.size.width - 24)
            MeHeaderLayout {
                Group {
                    if m.stack.count > 1 {
                        Button { Haptic.tap(); m.back() } label: { Icon("back", size: 22, color: C.ink).frame(width: 44, height: 44).background(C.card.opacity(0.85)).clipShape(Circle()) }
                            .accessibilityLabel(t("Back", "Rudi")).accessibilityIdentifier("hback")
                    } else {
                        MePill(on: m.here.tab == "me" && m.here.project == nil && m.here.sub == nil, word: word)
                    }
                }
                Wordmark()
                NotifBell()
            }
            .padding(.horizontal, 12)
            .frame(width: g.size.width, height: 58)
        }
        .frame(height: 58)
        .background(.ultraThinMaterial)
        .onAppear { started() }
        .onChange(of: m.snap) { _ in fresh() }
        .onChange(of: phase) { p in
            if p == .background { NotifPhone.schedule() }
            if p == .active { MeUpdates.shared.checkSoon(m.api); Task { await NotifCenter.shared.refreshAllowed() }; loadActivitySoon() }
        }
    }

    /// The word "Me" fits when the pill, the logo (with room each side) and the bell all fit on one line.
    static func roomForWord(_ width: CGFloat) -> Bool {
        let w = (t("Me", "Mimi") as NSString).size(withAttributes: [.font: UIFont.systemFont(ofSize: 15, weight: .heavy)]).width * 1.1
        return width >= 4 + 34 + 6 + 16 + 4 + w + 12 + 16 + Wordmark.width() + 46 + 4
    }

    private func started() {
        let nc = NotifCenter.shared
        nc.model = m
        if let open = nc.pendingOpen { nc.pendingOpen = nil; NotifTap.go(m, open) }
        TeamPhotos.shared.absorbList(m.prefs.loadSnapshot())
        nc.watch(m)
        Task { await nc.refreshAllowed() }
        MeUpdates.shared.checkSoon(m.api)
        loadActivitySoon()
    }
    private func fresh() {
        TeamPhotos.shared.absorbList(m.prefs.loadSnapshot())
        NotifCenter.shared.watch(m)
        loadActivitySoon()
    }
    /// The team's feed (for the bell), at most once a minute.
    private func loadActivitySoon() {
        guard Date().timeIntervalSince(actAt) > 60 else { return }
        actAt = Date()
        Task { if await m.loadActivity() { NotifCenter.shared.changed() } }
    }
}

/// Me, top left: your photo (or initials), a person icon and the word Me (Mimi), as one pill, lit while Me is open.
/// It carries what's waiting to send and the app-update dot.
struct MePill: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject private var up = MeUpdates.shared
    let on: Bool; let word: Bool
    var body: some View {
        let fg = on ? C.blue2 : C.ink
        Button { Haptic.tap(); m.tab("me") } label: {
            HStack(spacing: 0) {
                MeFace(name: m.prefs.name, size: 34)
                Icon("person", size: 16, color: fg).padding(.leading, 6)
                if word { Text(t("Me", "Mimi")).font(.mont(15, .heavy)).foregroundColor(fg).lineLimit(1).fixedSize().padding(.leading, 4) }
            }
            .padding(.leading, 4).padding(.trailing, 12).padding(.vertical, 4)
            .frame(minHeight: 44)
            .background(Capsule().fill(on ? C.tint : C.card.opacity(0.85)))
            .overlay(Capsule().stroke(on ? C.blue2 : C.line, lineWidth: on ? 2 : 1))
            .overlay(alignment: .topLeading) {
                if m.waiting > 0 {
                    Text("\(m.waiting)").font(.mont(10, .bold)).foregroundColor(.white).lineLimit(1).fixedSize().padding(.horizontal, 5).background(C.warn).clipShape(Capsule()).offset(x: 26, y: -4)
                } else if up.ready {
                    Circle().fill(C.blue).frame(width: 8, height: 8).padding(2).background(Circle().fill(Color.white)).offset(x: 30, y: 0)
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(t("Me", "Mimi")).accessibilityAddTraits(on ? .isSelected : [])
        .accessibilityIdentifier("hav")
    }
}

/// The bell: a red count of what's unread (the bell swings now and then); tap for Notifications.
struct NotifBell: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject private var nc = NotifCenter.shared
    @State private var swing: Double = 0
    var body: some View {
        let _ = nc.tick
        let n = nc.unread(m)
        Button {
            Haptic.tap()
            if m.here.sub != "notes" { m.go(Place(tab: m.here.tab, sub: "notes")) }
        } label: {
            SFIcon("bell", size: 21, color: C.ink).rotationEffect(.degrees(swing), anchor: UnitPoint(x: 0.5, y: 0.1))
                .frame(width: 44, height: 44).background(C.card).clipShape(Circle()).overlay(Circle().stroke(C.line, lineWidth: Look.mode == "sun" ? 2 : 1))
        }
        .buttonStyle(.plain)
        .overlay(alignment: .topTrailing) {
            if n > 0 {
                Text(n > 9 ? "9+" : "\(n)").font(.mont(11, .bold)).foregroundColor(C.dark ? Color(hex: 0x2A0B08) : .white).lineLimit(1).fixedSize()
                    .padding(.horizontal, 5).frame(minWidth: 20, minHeight: 20).background(Capsule().fill(C.bad))
                    .padding(2).background(Capsule().fill(C.card)).offset(x: 6, y: -5)
                    .id(n).transition(.scale.combined(with: .opacity))
                    .accessibilityIdentifier("bell_count")
            }
        }
        .frame(width: 46, height: 46)
        .accessibilityLabel(t("Notifications", "Arifa") + (n > 0 ? " · \(n) " + t("unread", "hazijasomwa") : ""))
        .accessibilityIdentifier("bell")
        .task(id: n > 0) { await ring(n > 0) }
    }
    /// Every few seconds while something is unread: a short swing.
    private func ring(_ on: Bool) async {
        guard on, Motion.on else { return }
        let steps: [Double] = [17, -15, 11, -8, 5, -2, 0]
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            for s in steps {
                if Task.isCancelled { return }
                withAnimation(.easeInOut(duration: 0.15)) { swing = s }
                try? await Task.sleep(nanoseconds: 150_000_000)
            }
        }
    }
}

/// "Me is here now": a speech bubble under the Me pill, once. Got it, or a tap anywhere, closes it for good.
struct MeHintBubble: View {
    let close: () -> Void
    @State private var shown = false
    var body: some View {
        let bg = Color(hex: 0x054F81)
        VStack(alignment: .leading, spacing: 0) {
            Triangle().fill(bg).frame(width: 16, height: 9).padding(.leading, 13)
            VStack(alignment: .trailing, spacing: 6) {
                Text(t("Me is here now: your work, settings and team", "Mimi iko hapa sasa: kazi yako, mipangilio na timu"))
                    .font(.mont(16, .bold)).foregroundColor(.white).fixedSize(horizontal: false, vertical: true).frame(maxWidth: .infinity, alignment: .leading)
                Button(action: close) {
                    Text(t("Got it", "Sawa")).font(.mont(15, .heavy)).foregroundColor(bg).lineLimit(1).fixedSize()
                        .padding(.horizontal, 16).padding(.vertical, 8).background(Capsule().fill(Color.white)).frame(minHeight: 44)
                }.buttonStyle(.plain).accessibilityIdentifier("me_hint_ok")
            }
            .padding(.leading, 16).padding(.trailing, 10).padding(.top, 12).padding(.bottom, 10)
            .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(bg))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Look.mode == "sun" ? Color.black : Color.clear, lineWidth: 2))
            .shadow(color: Color(hex: 0x054F81, alpha: 0.33), radius: 14, y: 6)
        }
        .frame(maxWidth: 300)
        .scaleEffect(shown ? 1 : 0.9, anchor: .topLeading).opacity(shown ? 1 : 0)
        .onAppear { if Motion.on { withAnimation(.easeOut(duration: 0.25)) { shown = true } } else { shown = true } }
        .accessibilityIdentifier("me_hint")
    }
}

private struct Triangle: Shape {
    func path(in r: CGRect) -> Path {
        var p = Path(); p.move(to: CGPoint(x: r.midX, y: r.minY)); p.addLine(to: CGPoint(x: r.maxX, y: r.maxY)); p.addLine(to: CGPoint(x: r.minX, y: r.maxY)); p.closeSubpath(); return p
    }
}

/// What sits over the app while signed in: the contact sheet, the update pop-up, the welcome screens and the Me hint.
struct MeOverlays: ViewModifier {
    @EnvironmentObject var m: AppModel
    @ObservedObject private var contact = Contact.shared
    @ObservedObject private var nav = MeNav.shared
    @State private var hint = false
    func body(content: Content) -> some View {
        content
            .simultaneousGesture(TapGesture().onEnded { if hint { closeHint() } })
            .overlay(alignment: .topLeading) {
                if hint && m.stack.count == 1 && !nav.welcome { MeHintBubble { closeHint() }.padding(.top, 54).padding(.leading, 12).padding(.trailing, 16) }
            }
            .overlay(alignment: .bottom) { MeToastView() }
            .overlay { MeUpdatePrompt() }
            .sheet(item: Binding(get: { contact.who }, set: { contact.who = $0 })) { who in ContactSheet(name: who.name).environmentObject(m) }
            .fullScreenCover(isPresented: Binding(get: { nav.welcome }, set: { nav.welcome = $0 })) { MeWelcome { nav.welcome = false; showHint() }.environmentObject(m) }
            .onAppear {
                if !m.prefs.onb && (!m.demo || Demo.arg("-screen") == "welcome") { nav.welcome = true } else { showHint() }
            }
    }
    private func showHint() {
        if m.demo && Demo.arg("-hint") == "1" { hint = true; return }
        hint = m.snap?.admin == true && !m.prefs.meHint && m.prefs.onb && !m.demo
    }
    private func closeHint() { hint = false; m.prefs.meHint = true }
}

extension View {
    /// The contact sheet, the update pop-up, the welcome screens and the Me hint, over the signed-in app.
    func meOverlays() -> some View { modifier(MeOverlays()) }
}

/// The Me area's pages on top of a tab: Notifications and the Settings pages.
enum MeRoutes {
    static func handles(_ p: Place) -> Bool { p.project == nil && (p.sub == "notes" || (p.sub ?? "").hasPrefix("set_")) }
    @MainActor @ViewBuilder static func view(_ p: Place) -> some View {
        if p.sub == "notes" { NotifScreen() } else { SettingsPage(which: String((p.sub ?? "set_about").dropFirst(4))) }
    }
}

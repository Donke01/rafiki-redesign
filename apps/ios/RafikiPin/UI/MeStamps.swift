import SwiftUI

// The RAFIKI drop (the mascot on the lock screen, the welcome screens and empty pages) and Me › Your stamps
// (Android Mascot.kt, Welcome.kt's Stamp, Badges.kt).

/// The drop's colours: highlight, middle and shade.
enum DropC {
    case blue, green, coral, purple, amber, grey
    var hi: Color { switch self { case .blue: return Color(hex: 0xBFE8FF); case .green: return Color(hex: 0xC8F5DA); case .coral: return Color(hex: 0xFFD6C9); case .purple: return Color(hex: 0xE6DCFF); case .amber: return Color(hex: 0xFFEDB8); case .grey: return Color(hex: 0xF1F3F5) } }
    var mid: Color { switch self { case .blue: return Color(hex: 0x3EA7EE); case .green: return Color(hex: 0x2FB872); case .coral: return Color(hex: 0xF26A4F); case .purple: return Color(hex: 0x8C6CF2); case .amber: return Color(hex: 0xF2B13A); case .grey: return Color(hex: 0xC9CFD4) } }
    var lo: Color { switch self { case .blue: return Color(hex: 0x0757A0); case .green: return Color(hex: 0x0B6B3D); case .coral: return Color(hex: 0xA3261A); case .purple: return Color(hex: 0x4527B0); case .amber: return Color(hex: 0xA86200); case .grey: return Color(hex: 0x8C969E) } }
    static func badge(_ c: String) -> DropC { switch c { case "g": return .green; case "c": return .coral; case "y": return .amber; case "p": return .purple; default: return .blue } }
}
enum DropMood { case plain, smile, wink, o }

/// The RAFIKI drop: a glassy water drop with eyes. It floats and blinks unless `still` (or the phone asks for less motion).
struct MeDrop: View {
    let size: CGFloat; var color: DropC = .blue; var mood: DropMood = .smile; var still = false
    var body: some View {
        let h = size * 27 / 24
        if still || !Motion.on {
            Canvas { ctx, sz in MeDrop.draw(ctx, sz, color, mood, float: 0, blink: 1) }.frame(width: size, height: h).accessibilityHidden(true)
        } else {
            TimelineView(.animation(minimumInterval: 1.0 / 30)) { tl in
                let s = tl.date.timeIntervalSinceReferenceDate
                let f = (sin(s * .pi / 1.8) + 1) / 2
                let b = s.truncatingRemainder(dividingBy: 5)
                let blink = b > 2.3 && b < 2.5 ? max(0.1, abs(b - 2.4) * 9) : 1
                Canvas { ctx, sz in MeDrop.draw(ctx, sz, color, mood, float: f, blink: blink) }
            }.frame(width: size, height: h).accessibilityHidden(true)
        }
    }

    private static var dropPath: Path {
        var p = Path()
        p.move(to: CGPoint(x: 12, y: 2.5))
        p.addCurve(to: CGPoint(x: 19, y: 15.1), control1: CGPoint(x: 12, y: 2.5), control2: CGPoint(x: 19, y: 9.9))
        p.addCurve(to: CGPoint(x: 12, y: 22.1), control1: CGPoint(x: 19, y: 18.966), control2: CGPoint(x: 15.866, y: 22.1))
        p.addCurve(to: CGPoint(x: 5, y: 15.1), control1: CGPoint(x: 8.134, y: 22.1), control2: CGPoint(x: 5, y: 18.966))
        p.addCurve(to: CGPoint(x: 12, y: 2.5), control1: CGPoint(x: 5, y: 9.9), control2: CGPoint(x: 12, y: 2.5))
        p.closeSubpath()
        return p
    }

    static func draw(_ ctx0: GraphicsContext, _ sz: CGSize, _ color: DropC, _ mood: DropMood, float: Double, blink: Double) {
        var ctx = ctx0
        let k = sz.width / 24
        ctx.scaleBy(x: k, y: k)
        let sw = 5.2 * (1 - 0.18 * float)
        ctx.fill(Path(ellipseIn: CGRect(x: 12 - sw, y: 24.4, width: sw * 2, height: 2)), with: .color(Color(hex: 0x0B3A5E).opacity(0.22 - 0.08 * float)))
        ctx.translateBy(x: 0, y: -2.2 * float)
        let drop = dropPath
        ctx.fill(drop, with: .radialGradient(Gradient(colors: [color.hi, color.mid, color.lo]), center: CGPoint(x: 9.1, y: 8.6), startRadius: 0, endRadius: 15))
        ctx.stroke(drop, with: .color(.white.opacity(0.55)), lineWidth: 0.5)
        var shine = Path()
        shine.move(to: CGPoint(x: 8.2, y: 11.4))
        shine.addCurve(to: CGPoint(x: 10.9, y: 6.6), control1: CGPoint(x: 8.7, y: 9.7), control2: CGPoint(x: 9.8, y: 8.0))
        ctx.stroke(shine, with: .linearGradient(Gradient(colors: [.white.opacity(0.95), .white.opacity(0)]), startPoint: CGPoint(x: 0, y: 6.6), endPoint: CGPoint(x: 0, y: 11.4)),
                   style: StrokeStyle(lineWidth: 1.3, lineCap: .round))
        let ink = Color(hex: 0x04263F)
        func eye(_ cx: CGFloat) {
            ctx.fill(Path(ellipseIn: CGRect(x: cx - 1.15, y: 15.2 - 1.5 * blink, width: 2.3, height: 3 * blink)), with: .color(.white))
            let r = 0.62 * max(blink, 0.3)
            ctx.fill(Path(ellipseIn: CGRect(x: cx + 0.25 - r, y: 15.5 - r, width: r * 2, height: r * 2)), with: .color(ink))
        }
        if mood == .wink {
            var p = Path(); p.move(to: CGPoint(x: 9, y: 15.4)); p.addQuadCurve(to: CGPoint(x: 11, y: 15.4), control: CGPoint(x: 10, y: 14.6))
            ctx.stroke(p, with: .color(ink), style: StrokeStyle(lineWidth: 0.7, lineCap: .round))
        } else { eye(10) }
        eye(14)
        switch mood {
        case .smile, .wink:
            var p = Path(); p.move(to: CGPoint(x: 10.4, y: 18.1)); p.addQuadCurve(to: CGPoint(x: 13.6, y: 18.1), control: CGPoint(x: 12, y: 19.3))
            ctx.stroke(p, with: .color(ink), style: StrokeStyle(lineWidth: 0.7, lineCap: .round))
        case .o: ctx.fill(Path(ellipseIn: CGRect(x: 11.3, y: 17.55, width: 1.4, height: 1.7)), with: .color(ink))
        case .plain: break
        }
    }
}

/// A postage stamp with a drop on it: earned ones in colour, the rest grey.
struct MeStamp: View {
    let width: CGFloat; let color: DropC; var top = ""; let num: String; let got: Bool; var mood: DropMood = .smile; var rot: Double = -2; var still = true
    var body: some View {
        let h = width / 0.82
        ZStack {
            Canvas { ctx, sz in
                ctx.fill(Path(CGRect(origin: .zero, size: sz)), with: .color(.white))
                let r = sz.width * 0.028, step = r * 3
                let dot = Color(hex: 0xE6E9EC)
                var x = step / 2
                while x < sz.width {
                    ctx.fill(Path(ellipseIn: CGRect(x: x - r, y: -r, width: r * 2, height: r * 2)), with: .color(dot))
                    ctx.fill(Path(ellipseIn: CGRect(x: x - r, y: sz.height - r, width: r * 2, height: r * 2)), with: .color(dot))
                    x += step
                }
                var y = step / 2
                while y < sz.height {
                    ctx.fill(Path(ellipseIn: CGRect(x: -r, y: y - r, width: r * 2, height: r * 2)), with: .color(dot))
                    ctx.fill(Path(ellipseIn: CGRect(x: sz.width - r, y: y - r, width: r * 2, height: r * 2)), with: .color(dot))
                    y += step
                }
            }
            inner.padding(width * 0.065)
        }
        .frame(width: width, height: h)
        .shadow(color: .black.opacity(0.2), radius: 6, y: 3)
        .rotationEffect(.degrees(rot))
    }
    private var inner: some View {
        VStack(alignment: .leading, spacing: 0) {
            if !top.isEmpty { Text(top).font(.mont(max(7, width * 0.045), .heavy)).tracking(1).foregroundColor(.white.opacity(got ? 0.9 : 0)).lineLimit(1).minimumScaleFactor(0.6) }
            MeDrop(size: width * 0.48, color: got ? color : .grey, mood: mood, still: still || !got).opacity(got ? 1 : 0.4).frame(maxWidth: .infinity, maxHeight: .infinity)
            Text(num).font(.mont(max(8, width * 0.05), .heavy)).foregroundColor(got ? .white : Color(hex: 0x9AA4AC)).frame(maxWidth: .infinity, alignment: .trailing)
        }
        .padding(width * 0.05)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(got ? AnyShapeStyle(LinearGradient(colors: [color.mid, color.lo], startPoint: .topLeading, endPoint: .bottomTrailing)) : AnyShapeStyle(Color(hex: 0xE9EEF2)))
        .clipShape(RoundedRectangle(cornerRadius: 2))
    }
}

/// Me › Your stamps: badges for real work. Locked ones are grey with how far along you are; a new one lands with a
/// note at the top of the card (once).
struct BadgeStamps: View {
    @EnvironmentObject var m: AppModel
    let act: [JSON]?
    @State private var fresh: [MeBadgeState] = []
    @State private var toast = false
    var body: some View {
        let ck: [String: String] = Dictionary((m.snap?.projects ?? []).map { ($0.id, $0.ck) }, uniquingKeysWith: { a, _ in a })
        let states = MeBadges.compute(act ?? [], me: m.prefs.name, ckOf: { ck[$0] })
        let earned = states.filter(\.got).count
        CardV {
            MeH2(t("Your stamps", "Stempu zako"))
            Text(t("Every point you pin and every visit you report adds to your collection.", "Kila mahali unapoweka na kila ziara unayoripoti inaongeza kwenye mkusanyiko wako."))
                .font(.bMedium).foregroundColor(C.muted).multilineTextAlignment(.center).frame(maxWidth: .infinity).fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 10) {
                StampBar(f: Double(earned) / Double(max(1, states.count)))
                Text(t("\(earned) of \(states.count)", "\(earned) kati ya \(states.count)")).font(.mont(12, .bold)).foregroundColor(C.ink).fixedSize()
            }.padding(.top, 12)
            if toast, let first = fresh.first { StampToast(fresh: fresh, first: first) { withAnimation { toast = false } }.padding(.top, 14).transition(.opacity.combined(with: .move(edge: .top))) }
            StampGrid(states: states, fresh: Set(fresh.map(\.def.k)))
        }
        .padding(.top, 14)
        .onChange(of: act?.count ?? -1) { _ in celebrate(states) }
        .onAppear { celebrate(states) }
    }
    private func celebrate(_ states: [MeBadgeState]) {
        let who = m.prefs.name
        guard act != nil, !who.isEmpty else { return }
        let seen = m.prefs.badgesSeen(who)
        let got = Set(states.filter(\.got).map(\.def.k))
        let f = MeBadges.fresh(states, seen: seen)
        m.prefs.saveBadges(who, (seen ?? []).union(got))
        if !f.isEmpty { fresh = f; withAnimation(Motion.on ? Animation.spring(response: 0.45, dampingFraction: 0.8) : nil) { toast = true }; Haptic.ok() }
    }
}

private struct StampBar: View {
    let f: Double
    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(C.line)
                Capsule().fill(LinearGradient(colors: [Color(hex: 0x2FB872), Color(hex: 0x3EA7EE), Color(hex: 0x8C6CF2)], startPoint: .leading, endPoint: .trailing))
                    .frame(width: g.size.width * min(1, max(0, f)))
            }
        }.frame(height: 8)
    }
}

/// "New stamp earned!" inside the card, under the bar; it says so for a few seconds, then folds away.
private struct StampToast: View {
    let fresh: [MeBadgeState]; let first: MeBadgeState; let gone: () -> Void
    var body: some View {
        Button(action: gone) {
            HStack(spacing: 12) {
                MeDrop(size: 28, color: DropC.badge(first.def.color), mood: .smile).frame(width: 46, height: 46).background(Color.white.opacity(0.15)).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(fresh.count == 1 ? t("New stamp earned!", "Umepata stempu mpya!") : t("\(fresh.count) new stamps earned!", "Umepata stempu \(fresh.count) mpya!"))
                        .font(.mont(12, .heavy)).foregroundColor(Color(hex: 0xFFD66B))
                    Text(fresh.map { $0.def.title.replacingOccurrences(of: " ", with: "\u{00A0}") }.joined(separator: " · ")).font(.tSmall).foregroundColor(.white).fixedSize(horizontal: false, vertical: true)
                    if fresh.count == 1 { Text(first.def.how).font(.bSmall).foregroundColor(Color(hex: 0xCFE8FA)) }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 14).padding(.vertical, 12)
            .background(LinearGradient(colors: [Color(hex: 0x1A8BD6), Color(hex: 0x054F81), Color(hex: 0x04263F)], startPoint: .topLeading, endPoint: .bottomTrailing))
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        }
        .buttonStyle(.plain)
        .task { try? await Task.sleep(nanoseconds: 5_200_000_000); gone() }
    }
}

private struct StampGrid: View {
    let states: [MeBadgeState]; let fresh: Set<String>
    var body: some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 12, alignment: .top), GridItem(.flexible(), spacing: 12, alignment: .top), GridItem(.flexible(), spacing: 12, alignment: .top)], spacing: 16) {
            ForEach(Array(states.enumerated()), id: \.offset) { i, st in StampCell(i: i, st: st, party: fresh.contains(st.def.k)) }
        }.padding(.top, 16)
    }
}

private struct StampCell: View {
    let i: Int; let st: MeBadgeState; let party: Bool
    @State private var shown = false
    var body: some View {
        let moods: [DropMood] = [.smile, .wink, .o]
        VStack(spacing: 0) {
            MeStamp(width: 84, color: DropC.badge(st.def.color), num: String(format: "%03d", i + 1), got: st.got, mood: moods[i % 3], rot: i % 2 == 0 ? -2 : 2)
                .scaleEffect(party && !shown ? 0.2 : 1).rotationEffect(.degrees(party && !shown ? 22 : 0)).opacity(party && !shown ? 0 : 1)
            FitWords(st.def.title, size: 12, weight: .bold, color: C.ink, lines: 2, align: .center).padding(.top, 8)
            Text(st.progress).font(.bSmall).foregroundColor(st.got ? C.ok : C.muted).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
            if !st.got && st.def.goal > 1 && st.n > 0 {
                GeometryReader { g in
                    ZStack(alignment: .leading) { Capsule().fill(C.line); Capsule().fill(DropC.badge(st.def.color).mid).frame(width: g.size.width * st.fraction) }
                }.frame(width: 56, height: 4).padding(.top, 4)
            }
        }
        .frame(maxWidth: .infinity)
        .onAppear {
            guard party, !shown else { shown = true; return }
            if Motion.on { withAnimation(.spring(response: 0.5, dampingFraction: 0.45).delay(0.35 + Double(i % 3) * 0.06)) { shown = true } } else { shown = true }
        }
    }
}

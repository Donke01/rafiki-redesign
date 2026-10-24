import SwiftUI

func badgeDrop(_ c: String) -> DropC { switch c { case "g": return .green; case "c": return .coral; case "y": return .amber; case "p": return .purple; default: return .blue } }

/// A postage stamp with the RAFIKI drop on it: coloured when earned, grey when not.
struct Stamp: View {
    let width: CGFloat; let color: DropC; var top = ""; let num: String; let got: Bool; var mood: Mood = .smile; var rot: Double = -2
    var body: some View {
        let (_, mid, lo) = color.stops
        ZStack {
            Canvas { ctx, size in
                ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(.white))
                let r = size.width * 0.028, step = r * 3
                var x = step / 2
                while x < size.width {
                    for y in [0, size.height] { ctx.fill(Path(ellipseIn: CGRect(x: x - r, y: y - r, width: 2 * r, height: 2 * r)), with: .color(Color(hex: 0xE6E9EC))) }
                    x += step
                }
                var y = step / 2
                while y < size.height {
                    for xx in [0, size.width] { ctx.fill(Path(ellipseIn: CGRect(x: xx - r, y: y - r, width: 2 * r, height: 2 * r)), with: .color(Color(hex: 0xE6E9EC))) }
                    y += step
                }
            }
            VStack(spacing: 0) {
                if !top.isEmpty { Text(top).font(.mont(width * 0.045, .heavy)).foregroundColor(.white.opacity(got ? 0.9 : 0)).lineLimit(1).wide() }
                Drop(size: width * 0.48, color: got ? color : .grey, mood: mood, still: !got).opacity(got ? 1 : 0.4).frame(maxHeight: .infinity)
                Text(num).font(.mont(max(8, width * 0.05), .heavy)).foregroundColor(got ? .white : Color(hex: 0x9AA4AC)).wide(.trailing)
            }
            .padding(width * 0.05)
            .background(got ? AnyShapeStyle(LinearGradient(colors: [mid, lo], startPoint: .topLeading, endPoint: .bottomTrailing)) : AnyShapeStyle(Color(hex: 0xE9EEF2)))
            .clipShape(RoundedRectangle(cornerRadius: 2))
            .padding(width * 0.065)
        }
        .frame(width: width, height: width / 0.82)
        .shadow(color: .black.opacity(0.2), radius: 5, y: 2)
        .rotationEffect(.degrees(rot))
    }
}

/// Me › Your stamps: badges for real work. Locked ones are grey with how far along you are; a new one lands with a note.
struct BadgeStamps: View {
    @EnvironmentObject var m: AppModel
    @State private var fresh: [BadgeState] = []
    @State private var toast = false
    @State private var landed: Set<String> = []
    var body: some View {
        let who = m.prefs.name
        let ck = Dictionary((m.snap?.projects ?? []).map { ($0.id, $0.ck) }, uniquingKeysWith: { a, _ in a })
        let states = Badges.compute(m.activity ?? [], me: who, ckOf: { ck[$0] })
        let earned = states.filter(\.got).count
        CardV {
            H2(t("Your stamps", "Stempu zako")).wide(.center)
            Text(t("Every point you pin and every visit you report adds to your collection.", "Kila mahali unapoweka na kila ziara unayoripoti inaongeza kwenye mkusanyiko wako.")).font(.bMedium).foregroundColor(C.muted).multilineTextAlignment(.center).wide(.center)
            HStack(spacing: 10) {
                GeometryReader { g in
                    ZStack(alignment: .leading) {
                        Capsule().fill(C.line)
                        Capsule().fill(LinearGradient(colors: [Color(hex: 0x2FB872), Color(hex: 0x3EA7EE), Color(hex: 0x8C6CF2)], startPoint: .leading, endPoint: .trailing))
                            .frame(width: g.size.width * CGFloat(earned) / CGFloat(max(1, states.count)))
                    }
                }.frame(height: 8)
                Text(t("\(earned) of \(states.count)", "\(earned) kati ya \(states.count)")).font(.lMedium).foregroundColor(C.ink)
            }.padding(.top, 12)
            if toast, let first = fresh.first {
                Button { withAnimation { toast = false } } label: {
                    HStack(spacing: 12) {
                        Drop(size: 28, color: badgeDrop(first.def.color), mood: .smile).frame(width: 46, height: 46).background(Color.white.opacity(0.15)).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(fresh.count == 1 ? t("New stamp earned!", "Umepata stempu mpya!") : t("\(fresh.count) new stamps earned!", "Umepata stempu \(fresh.count) mpya!")).font(.mont(12, .heavy)).foregroundColor(Color(hex: 0xFFD66B))
                            Text(fresh.map(\.def.title).joined(separator: " · ")).font(.tSmall).foregroundColor(.white).multilineTextAlignment(.leading)
                            if fresh.count == 1 { Text(first.def.how).font(.bSmall).foregroundColor(LightBlue) }
                        }
                        Spacer(minLength: 0)
                    }.padding(.horizontal, 14).padding(.vertical, 12).background(DeepBrush).clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                }.buttonStyle(.plain).padding(.top, 14).transition(.move(edge: .top).combined(with: .opacity))
            }
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 16) {
                ForEach(Array(states.enumerated()), id: \.element.id) { k, st in
                    let party = st.got && fresh.contains { $0.def.k == st.def.k }
                    VStack(spacing: 0) {
                        Stamp(width: 84, color: badgeDrop(st.def.color), num: String(format: "%03d", k + 1), got: st.got, mood: [Mood.smile, .wink, .o][k % 3], rot: k % 2 == 0 ? -2 : 2)
                            .scaleEffect(party && !landed.contains(st.def.k) ? 0.2 : 1).rotationEffect(.degrees(party && !landed.contains(st.def.k) ? 22 : 0))
                            .opacity(party && !landed.contains(st.def.k) ? 0 : 1)
                            .rise(k, step: 0.04)
                        Text(st.def.title).font(.lMedium).foregroundColor(C.ink).multilineTextAlignment(.center).padding(.top, 8)
                        Text(st.progress).font(.bSmall).foregroundColor(st.got ? C.ok : C.muted).multilineTextAlignment(.center)
                        if !st.got && st.def.goal > 1 && st.n > 0 {
                            GeometryReader { g in ZStack(alignment: .leading) { Capsule().fill(C.line); Capsule().fill(badgeDrop(st.def.color).stops.1).frame(width: g.size.width * st.fraction) } }
                                .frame(width: 56, height: 4).padding(.top, 4)
                        }
                    }.frame(maxWidth: .infinity, alignment: .top)
                }
            }.padding(.top, 16)
        }
        .padding(.top, 14)
        .task(id: "\(m.activity?.count ?? -1)|\(who)") {
            guard m.activity != nil, !who.isEmpty else { return }
            let seen = Badges.seen(who)
            let f = Badges.fresh(states, seen: seen)
            Badges.save(who, (seen ?? []).union(states.filter(\.got).map(\.def.k)))
            if !f.isEmpty {
                fresh = f; withAnimation(.spring(response: 0.4, dampingFraction: 0.8)) { toast = true }; Haptic.ok()
                try? await Task.sleep(nanoseconds: 350_000_000)
                withAnimation(Motion.on ? .spring(response: 0.6, dampingFraction: 0.45) : nil) { landed = Set(f.map(\.def.k)) }
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                withAnimation { toast = false }
            }
        }
    }
}

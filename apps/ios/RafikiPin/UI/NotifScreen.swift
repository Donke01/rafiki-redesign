import SwiftUI

// Notifications (Android 2.15): a full page from the bell. All · Unread · For me · Repairs, grouped Today / This week /
// Earlier. Unread ones are tinted with a blue dot; "…" has Mark all as read and the settings. When there's nothing,
// the RAFIKI drop sits among little notes.

private let NOTE_FILTERS: [(String, String, String)] = [("all", "All", "Zote"), ("unread", "Unread", "Hazijasomwa"), ("me", "For me", "Kwangu"), ("repairs", "Repairs", "Matengenezo")]

/// 0 today, 1 this week (the 6 days before), 2 earlier.
func notifGroup(_ iso: String) -> Int {
    guard let d = When.parse(iso) else { return 2 }
    let cal = Calendar.current, today = cal.startOfDay(for: Date()), day = cal.startOfDay(for: d)
    if day >= today { return 0 }
    if let w = cal.date(byAdding: .day, value: -6, to: today), day >= w { return 1 }
    return 2
}

/// Which notifications a filter shows: For me = visits assigned to me and repair jobs for me; Repairs = reported and fixed.
func notifPass(_ a: NotifAlert, _ f: String, _ new: Bool) -> Bool {
    switch f {
    case "unread": return new
    case "me": return a.kind == "assign" || a.kind == "job"
    case "repairs": return a.kind == "repair" || a.kind == "fixed"
    default: return true
    }
}

/// "just now", "5 min ago", "2 h ago", "yesterday", "3 days ago", then the date.
func notifAgo(_ iso: String) -> String {
    guard let at = When.parse(iso) else { return "" }
    let m = max(0, Int(Date().timeIntervalSince(at) / 60)), h = m / 60, d = h / 24
    if m < 1 { return t("just now", "sasa hivi") }
    if m < 60 { return t("\(m) min ago", "dakika \(m) zilizopita") }
    if h < 24 { return t("\(h) h ago", "saa \(h) zilizopita") }
    if d < 7 { return d == 1 ? t("yesterday", "jana") : t("\(d) days ago", "siku \(d) zilizopita") }
    return teamDay(iso)
}

struct NotifScreen: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject private var nc = NotifCenter.shared
    @State private var f = "all"
    @State private var menu = false
    var body: some View {
        let _ = nc.tick
        let all = nc.alerts(m)
        let seen = m.prefs.noteSeen, read = m.prefs.noteRead
        let un = all.filter { notifIsNew($0, seen, read) }.count
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ZStack {
                    MeTitle(t("Notifications", "Arifa"), sub: all.isEmpty ? nil : (un == 0 ? t("All read ✓", "Zote zimesomwa ✓") : un == 1 ? t("1 unread", "1 haijasomwa") : t("\(un) unread", "\(un) hazijasomwa")))
                        .padding(.horizontal, 52)
                    HStack { Spacer(); moreBtn }
                }
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(NOTE_FILTERS, id: \.0) { k, en, sw in
                            NotifFilterPill(label: t(en, sw), on: f == k, count: k == "unread" ? un : 0) { if f != k { Haptic.tap(); f = k } }
                        }
                    }.padding(.vertical, 2)
                }.padding(.top, 14)
                NotifList(all: all, f: f, seen: seen, read: read).id(f)
                Button { m.openSettings("notify") } label: {
                    Text(t("Choose what to be told about", "Chagua unachotaka kuarifiwa")).font(.lLarge).foregroundColor(C.blue2).multilineTextAlignment(.center).frame(maxWidth: .infinity).padding(10).frame(minHeight: 44)
                }.buttonStyle(.plain).padding(.top, 18)
            }
            .padding(.horizontal, 16).padding(.top, 4).padding(.bottom, 40)
            .meCentred()
        }
        .overlay(alignment: .topTrailing) { if menu { menuList(un) } }
        .task { if m.activity == nil { await m.loadActivity(); nc.changed() } }
        .refreshable { await m.refreshNow(); _ = await m.loadActivity(); nc.changed() }
    }

    private var moreBtn: some View {
        Button { Haptic.tap(); withAnimation(.easeOut(duration: 0.18)) { menu.toggle() } } label: {
            HStack(spacing: 3.5) { ForEach(0..<3, id: \.self) { _ in Circle().fill(C.ink).frame(width: 4.5, height: 4.5) } }
                .frame(width: 44, height: 44).background(Circle().fill(C.card)).overlay(Circle().stroke(C.line, lineWidth: Look.mode == "sun" ? 2 : 1))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(t("More: mark all as read, settings", "Zaidi: weka zote kuwa zimesomwa, mipangilio"))
    }

    private func menuList(_ un: Int) -> some View {
        ZStack(alignment: .topTrailing) {
            Color.black.opacity(0.001).ignoresSafeArea().onTapGesture { withAnimation { menu = false } }
            VStack(spacing: 0) {
                menuItem("checkmark", t("Mark all as read", "Weka zote kuwa zimesomwa"), un > 0) { menu = false; nc.markAll(m) }
                menuItem("bell", t("Choose what to be told about", "Chagua unachotaka kuarifiwa"), true) { menu = false; m.openSettings("notify") }
            }
            .padding(6).frame(width: 264)
            .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(C.card))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(C.line, lineWidth: Look.mode == "sun" ? 2 : 1))
            .shadow(color: .black.opacity(Look.mode == "sun" ? 0 : 0.18), radius: 16, y: 6)
            .padding(.top, 52).padding(.trailing, 16)
            .transition(.scale(scale: 0.9, anchor: .topTrailing).combined(with: .opacity))
        }
    }
    private func menuItem(_ icon: String, _ label: String, _ enabled: Bool, _ action: @escaping () -> Void) -> some View {
        Button { Haptic.tap(); action() } label: {
            HStack(spacing: 11) { SFIcon(icon, size: 19, color: C.blue); Text(label).font(.tSmall).foregroundColor(C.ink).fixedSize(horizontal: false, vertical: true); Spacer(minLength: 0) }
                .padding(.horizontal, 12).padding(.vertical, 10).frame(minHeight: 46).contentShape(Rectangle())
        }.buttonStyle(.plain).disabled(!enabled).opacity(enabled ? 1 : 0.45)
    }
}

private struct NotifList: View {
    @EnvironmentObject var m: AppModel
    let all: [NotifAlert]; let f: String; let seen: String; let read: Set<String>
    var body: some View {
        let list = all.filter { notifPass($0, f, notifIsNew($0, seen, read)) }
        if list.isEmpty { NotifEmpty(f: f) }
        else {
            let groups = Dictionary(grouping: list) { notifGroup($0.at) }
            VStack(alignment: .leading, spacing: 0) {
                ForEach([0, 1, 2], id: \.self) { g in
                    if let items = groups[g] {
                        Text([t("Today", "Leo"), t("This week", "Wiki hii"), t("Earlier", "Awali")][g].uppercased()).font(.mont(11, .heavy)).tracking(1.4).foregroundColor(C.muted)
                            .padding(.leading, 4).padding(.top, 16).padding(.bottom, 8)
                        ForEach(Array(items.enumerated()), id: \.element.key) { i, a in
                            NotifRow(a: a, new: notifIsNew(a, seen, read)) {
                                Haptic.tap(); NotifCenter.shared.markRead(a, m)
                                if let id = a.pid, m.snap?.projects.contains(where: { $0.id == id }) == true { m.openProject(id) } else if a.kind == "review" { m.tab("office") }
                            }.padding(.bottom, 8).rise(i, step: 0.05)
                        }
                    }
                }
            }
        }
    }
}

private struct NotifFilterPill: View {
    let label: String; let on: Bool; let count: Int; let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 7) {
                Text(label).font(.mont(14, .bold)).foregroundColor(on ? .white : C.ink).lineLimit(1).fixedSize()
                if count > 0 {
                    Text(count > 9 ? "9+" : "\(count)").font(.mont(11, .bold)).foregroundColor(C.dark ? Color(hex: 0x2A0B08) : .white)
                        .padding(.horizontal, 5).frame(minWidth: 20, minHeight: 20).background(Capsule().fill(C.bad))
                }
            }
            .padding(.horizontal, 15).frame(minHeight: 40)
            .background(Capsule().fill(on ? C.blue : C.card))
            .overlay(Capsule().stroke(on ? Color.clear : C.line, lineWidth: Look.mode == "sun" ? 2 : 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

/// One notification: the person's round picture (or the kind's tile) with a coloured badge, name, "2 h ago · project", the message.
private struct NotifRow: View {
    let a: NotifAlert; let new: Bool; let action: () -> Void
    @State private var pulse = false
    var body: some View {
        let k = notifKind(a.kind)
        let bg: Color = new ? C.tint : C.card
        let sun = Look.mode == "sun"
        HStack(alignment: .top, spacing: 13) {
            avatar(k, ring: bg)
            VStack(alignment: .leading, spacing: 1) {
                Text(a.by.isEmpty ? a.name : a.by).font(.mont(15, .heavy)).foregroundColor(C.ink).fixedSize(horizontal: false, vertical: true)
                Text([notifAgo(a.at), a.title].filter { !$0.isEmpty }.joined(separator: " · ")).font(.mont(12, .semibold)).foregroundColor(C.muted).lineLimit(1)
                Text(a.msg.isEmpty ? a.text : a.msg).font(.bMedium).foregroundColor(C.text).padding(.top, 4).fixedSize(horizontal: false, vertical: true)
            }.frame(maxWidth: .infinity, alignment: .leading)
            if new { dot }
        }
        .padding(.leading, 12).padding(.trailing, 14).padding(.vertical, 13)
        .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(bg))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(sun && new ? C.blue : sun ? C.line : new ? Color.clear : C.line, lineWidth: sun ? 2 : 1))
        .contentShape(Rectangle())
        .onTapGesture(perform: action)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityValue(new ? t("Unread", "Haijasomwa") : "")
        .accessibilityIdentifier("alert_row")
    }
    @ViewBuilder private func avatar(_ k: NotifKind, ring: Color) -> some View {
        let kh = meHue(k.hue)
        if a.by.isEmpty {
            SFIcon(k.icon, size: 23, color: C.card).frame(width: 46, height: 46).background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(kh.fg))
        } else {
            // a tap on the person opens their contact sheet; the rest of the row opens the notification
            PersonTap(name: a.by) {
                PersonAvatar(name: a.by, size: 46)
                    .overlay(alignment: .bottomTrailing) {
                        SFIcon(k.icon, size: 12, color: C.card).frame(width: 19, height: 19).background(Circle().fill(kh.fg)).padding(2.5).background(Circle().fill(ring)).offset(x: 5, y: 5)
                    }
            }.frame(width: 46, height: 46)
        }
    }
    private var dot: some View {
        ZStack {
            Circle().stroke(C.blue.opacity(pulse ? 0 : 0.5), lineWidth: 2).frame(width: pulse ? 28 : 10, height: pulse ? 28 : 10)
            Circle().fill(C.blue).frame(width: 10, height: 10)
        }
        .frame(width: 18, height: 18).padding(.top, 2)
        .onAppear { if Motion.on { withAnimation(.linear(duration: 2.4).repeatForever(autoreverses: false).delay(1)) { pulse = true } } }
        .accessibilityHidden(true)
    }
}

/// Nothing to show: the drop bobs in the middle among a few little notes.
private struct NotifEmpty: View {
    let f: String
    var body: some View {
        let words: (String, String) = {
            switch f {
            case "unread": return (t("You’re all caught up", "Umesoma zote"), t("Nothing unread. Nice work!", "Hakuna ambayo hujasoma. Kazi nzuri!"))
            case "me": return (t("Nothing assigned to you right now", "Hakuna ulichopewa kwa sasa"), t("Visits and repair jobs given to you will show up here.", "Ziara na kazi za matengenezo utakazopewa zitaonekana hapa."))
            case "repairs": return (t("No repair news", "Hakuna habari za matengenezo"), t("Repairs the team reports and fixes will show up here.", "Matengenezo yanayoripotiwa na kurekebishwa na timu yataonekana hapa."))
            default: return (t("You’re all caught up", "Hakuna jipya kwa sasa"), t("When the team reports a repair, fixes a water point or gives you visits, it shows up here.", "Timu ikiripoti matengenezo, ikirekebisha kituo cha maji au ikikupa ziara, itaonekana hapa."))
            }
        }()
        let cards: [(String, String)] = f == "me" ? [("calendar", "teal"), ("wrench.adjustable", "amber"), ("list.clipboard", "blue")]
            : f == "repairs" ? [("exclamationmark.triangle", "coral"), ("checkmark.circle", "green"), ("wrench.adjustable", "amber")]
            : [("checkmark.circle", "green"), ("calendar", "teal"), ("exclamationmark.triangle", "coral")]
        VStack(spacing: 0) {
            ZStack {
                Circle().fill(RadialGradient(colors: [C.hue("pin").fg.opacity(0.24), C.hue("pin").fg.opacity(0)], center: .center, startRadius: 0, endRadius: 85)).frame(width: 170, height: 170)
                NotifNote(icon: cards[0].0, hue: cards[0].1, rot: -7).offset(x: -78, y: -62)
                NotifNote(icon: cards[1].0, hue: cards[1].1, rot: 6).offset(x: 80, y: -6)
                NotifNote(icon: cards[2].0, hue: cards[2].1, rot: 4).offset(x: -70, y: 70)
                MeDrop(size: 96, color: .blue, mood: .smile)
                SFIcon("checkmark", size: 17, color: C.card).frame(width: 30, height: 30).background(Circle().fill(C.ok)).padding(4).background(Circle().fill(C.card)).offset(x: 41, y: -61)
            }.frame(width: 272, height: 212).frame(maxWidth: .infinity).clipped()
            Text(words.0).font(.tLarge).foregroundColor(C.ink).multilineTextAlignment(.center).padding(.top, 8).padding(.horizontal, 16).fixedSize(horizontal: false, vertical: true)
            Text(words.1).font(.bMedium).foregroundColor(C.muted).multilineTextAlignment(.center).padding(.top, 6).padding(.horizontal, 28).fixedSize(horizontal: false, vertical: true)
        }
        .padding(.top, 18).rise(0)
    }
}

private struct NotifNote: View {
    let icon: String; let hue: String; let rot: Double
    var body: some View {
        let hu = meHue(hue)
        HStack(spacing: 8) {
            SFIcon(icon, size: 13, color: C.card).frame(width: 24, height: 24).background(Circle().fill(hu.fg))
            VStack(alignment: .leading, spacing: 5) {
                Capsule().fill(hu.bg).frame(height: 6)
                Capsule().fill(Look.mode == "sun" ? Color(hex: 0x8A99A6) : C.line).frame(width: 46, height: 6)
            }
        }
        .padding(.horizontal, 10).padding(.vertical, 9).frame(width: 122)
        .background(RoundedRectangle(cornerRadius: 15, style: .continuous).fill(C.card))
        .overlay(RoundedRectangle(cornerRadius: 15, style: .continuous).stroke(C.line, lineWidth: Look.mode == "sun" ? 2 : 1))
        .shadow(color: .black.opacity(Look.mode == "sun" ? 0 : 0.1), radius: 10, y: 4)
        .rotationEffect(.degrees(rot))
        .accessibilityHidden(true)
    }
}

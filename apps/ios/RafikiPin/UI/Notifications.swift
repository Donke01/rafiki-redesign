import SwiftUI

// Notifications: a full screen from the bell, the same as the web and Android apps.
// All · Unread · For me · Repairs, grouped Today / This week / Earlier.

private let NOTE_FILTERS = [("all", "All", "Zote"), ("unread", "Unread", "Hazijasomwa"), ("me", "For me", "Kwangu"), ("repairs", "Repairs", "Matengenezo")]

/// 0 today, 1 this week (the 6 days before), 2 earlier.
func noteGroup(_ iso: String) -> Int {
    guard let d = When.parse(iso) else { return 2 }
    let cal = Calendar.current, today = cal.startOfDay(for: Date()), day = cal.startOfDay(for: d)
    if day >= today { return 0 }
    if day >= cal.date(byAdding: .day, value: -6, to: today)! { return 1 }
    return 2
}

/// Which notifications a filter shows: For me = visits assigned to me and repair jobs; Repairs = reported and fixed.
func notePass(_ a: Alert, _ f: String, new: Bool) -> Bool {
    switch f {
    case "unread": return new
    case "me": return a.kind == "assign" || a.kind == "job"
    case "repairs": return a.kind == "repair" || a.kind == "fixed"
    default: return true
    }
}

/// The bell, top right: a red count of what's unread (it pops in, and the bell rings now and then).
struct Bell: View {
    @EnvironmentObject var m: AppModel
    let open: () -> Void
    @State private var swing = 0.0
    var body: some View {
        let _ = m.noteTick
        let n = m.unread()
        Button { Haptic.tap(); open() } label: {
            ZStack(alignment: .topTrailing) {
                Icon("bell", size: 21, color: C.ink).rotationEffect(.degrees(swing), anchor: UnitPoint(x: 0.5, y: 0.1))
                    .frame(width: 44, height: 44).background(C.card).clipShape(Circle()).overlay(Circle().stroke(C.line, lineWidth: 1))
                if n > 0 {
                    Text(n > 9 ? "9+" : "\(n)").font(.mont(11, .bold)).foregroundColor(C.dark ? Color(hex: 0x2A0B08) : .white)
                        .padding(.horizontal, 5).frame(minWidth: 20, minHeight: 20).background(C.bad).clipShape(Capsule())
                        .padding(2).background(C.card).clipShape(Capsule()).offset(x: 4, y: -2).id(n).pop()
                }
            }.frame(width: 46, height: 46)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(t("Notifications", "Arifa") + (n > 0 ? " · \(n) " + t("unread", "hazijasomwa") : ""))
        .accessibilityIdentifier("bell")
        .task(id: n) {
            guard n > 0, Motion.on else { return }
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 500_000_000)
                for a in [17.0, -15, 11, -8, 5, -2, 0] { withAnimation(.easeInOut(duration: 0.15)) { swing = a }; try? await Task.sleep(nanoseconds: 150_000_000) }
                try? await Task.sleep(nanoseconds: 3_500_000_000)
            }
        }
    }
}

struct NotificationsView: View {
    @EnvironmentObject var m: AppModel
    @State private var f = "all"
    @State private var menu = false
    var body: some View {
        let _ = m.noteTick
        let all = m.alerts()
        let seen = m.prefs.noteSeen, read = m.prefs.noteRead
        let un = all.filter { alertNew($0, seen: seen, read: read) }.count
        let list = all.filter { notePass($0, f, new: alertNew($0, seen: seen, read: read)) }
        ZStack(alignment: .topTrailing) {
            ScrollView {
                VStack(spacing: 0) {
                    ZStack {
                        VStack(spacing: 2) {
                            Text(t("Notifications", "Arifa")).font(.hLarge).foregroundColor(C.ink).multilineTextAlignment(.center)
                            if !all.isEmpty {
                                Text(un == 0 ? t("All read ✓", "Zote zimesomwa ✓") : un == 1 ? t("1 unread", "1 haijasomwa") : t("\(un) unread", "\(un) hazijasomwa"))
                                    .font(.mont(13, .bold)).foregroundColor(C.muted)
                            }
                        }.padding(.horizontal, 52)
                        HStack { Spacer(); MoreDots(label: t("More: mark all as read, settings", "Zaidi: weka zote kuwa zimesomwa, mipangilio")) { Haptic.tap(); withAnimation(.easeOut(duration: 0.18)) { menu.toggle() } } }
                    }
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(NOTE_FILTERS, id: \.0) { k, en, sw in
                                FilterPill(label: t(en, sw), on: f == k, count: k == "unread" ? un : 0) { if f != k { Haptic.tap(); f = k } }
                            }
                        }.padding(.top, 14).padding(.bottom, 2)
                    }
                    Group {
                        if list.isEmpty { NotesEmpty(f: f) }
                        else {
                            let groups = Dictionary(grouping: list) { noteGroup($0.at) }
                            ForEach([0, 1, 2], id: \.self) { g in
                                if let items = groups[g] {
                                    Text([t("Today", "Leo"), t("This week", "Wiki hii"), t("Earlier", "Awali")][g].uppercased()).font(.mont(11, .heavy)).tracking(1.4).foregroundColor(C.muted)
                                        .wide().padding(.leading, 4).padding(.top, 16).padding(.bottom, 8)
                                    ForEach(Array(items.enumerated()), id: \.element.key) { i, a in
                                        NoteRow(a: a, new: alertNew(a, seen: seen, read: read)) {
                                            Haptic.tap(); m.markRead(a)
                                            if let pid = a.pid { m.go(Place(tab: m.here.tab, project: pid)) } else if a.kind == "review" { m.openOffice("review") }
                                        }.padding(.bottom, 8).rise(i, step: 0.05)
                                    }
                                }
                            }
                        }
                    }.id(f)
                    Button { m.go(Place(tab: m.here.tab, sub: "set_notify")) } label: {
                        Text(t("Choose what to be told about", "Chagua unachotaka kuarifiwa")).font(.lLarge).foregroundColor(C.blue2).padding(10)
                    }.buttonStyle(.plain).padding(.top, 18)
                }.padding(.horizontal, 16).padding(.top, 4).padding(.bottom, 120)
            }
            if menu {
                Color.black.opacity(0.001).ignoresSafeArea().onTapGesture { menu = false }
                VStack(spacing: 0) {
                    menuItem("check", t("Mark all as read", "Weka zote kuwa zimesomwa"), enabled: un > 0) { Haptic.tap(); menu = false; m.markRead() }
                    menuItem("bell", t("Choose what to be told about", "Chagua unachotaka kuarifiwa"), enabled: true) { menu = false; m.go(Place(tab: m.here.tab, sub: "set_notify")) }
                }
                .padding(6).frame(width: 264).background(C.card).clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(C.line, lineWidth: C.sun ? 2 : 1))
                .shadow(color: .black.opacity(C.sun ? 0 : 0.18), radius: 16, y: 6)
                .padding(.top, 52).padding(.trailing, 16)
                .transition(.scale(scale: 0.9, anchor: .topTrailing).combined(with: .opacity))
            }
        }
        .task { await m.loadActivity() }
        .refreshable { await m.refreshNow(quiet: true); await m.loadActivity() }
    }
    private func menuItem(_ icon: String, _ label: String, enabled: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 11) { Icon(icon, size: 19, color: C.blue); Text(label).font(.tSmall).foregroundColor(C.ink); Spacer() }
                .padding(.horizontal, 12).padding(.vertical, 10).frame(minHeight: 46).contentShape(Rectangle())
        }.buttonStyle(.plain).disabled(!enabled).opacity(enabled ? 1 : 0.45)
    }
}

/// One notification: the person's round avatar (or the kind's tile) with a coloured badge, name, "2 h ago · project", the message.
private struct NoteRow: View {
    @EnvironmentObject var m: AppModel
    let a: Alert; let new: Bool; let action: () -> Void
    var body: some View {
        let k = ALERT_KINDS.first { $0.0 == a.kind } ?? ALERT_KINDS[0]
        let bg = new ? C.tint : C.card
        Button(action: action) {
            HStack(alignment: .top, spacing: 13) {
                avatar(k, ring: bg)
                VStack(alignment: .leading, spacing: 2) {
                    Text(a.by.isEmpty ? a.name : a.by).font(.mont(15, .heavy)).foregroundColor(C.ink)
                    Text([agoText(a.at), a.title].filter { !$0.isEmpty }.joined(separator: " · ")).font(.mont(12, .semibold)).foregroundColor(C.muted).lineLimit(1)
                    Text(a.msg.isEmpty ? a.text : a.msg).font(.bMedium).foregroundColor(C.text).multilineTextAlignment(.leading).padding(.top, 2)
                }
                Spacer(minLength: 0)
                if new { UnreadDot().padding(.leading, 8).padding(.top, 5).transition(.scale.combined(with: .opacity)) }
            }
            .padding(.leading, 12).padding(.trailing, 14).padding(.vertical, 13)
            .background(bg).clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(C.sun && new ? C.blue : (new ? Color.clear : C.line), lineWidth: C.sun ? 2 : 1))
            .animation(.easeOut(duration: 0.45), value: new)
        }.buttonStyle(.plain).accessibilityValue(new ? t("Unread", "Haijasomwa") : "")
    }
    @ViewBuilder private func avatar(_ k: (String, String, String, String, String), ring: Color) -> some View {
        let kh = C.named(k.4)
        if a.by.isEmpty {
            Icon(k.3, size: 23, color: C.card).frame(width: 46, height: 46).background(kh.fg).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        } else {
            let hu = C.named(nameHue(a.by))
            ZStack(alignment: .bottomTrailing) {
                Text(initialsOf(a.by)).font(.mont(15, .heavy)).foregroundColor(hu.fg).frame(width: 46, height: 46).background(hu.bg).clipShape(Circle())
                Icon(k.3, size: 12, color: C.card).frame(width: 19, height: 19).background(kh.fg).clipShape(Circle()).padding(2.5).background(ring).clipShape(Circle()).offset(x: 5, y: 5)
            }
            .onTapGesture { Contact.show(a.by) }
        }
    }
}

/// The blue unread dot, with a soft ring going out from it now and then.
private struct UnreadDot: View {
    @State private var p = 0.0
    var body: some View {
        ZStack {
            if Motion.on { Circle().stroke(C.blue.opacity(0.5 * (1 - p)), lineWidth: 2).frame(width: 10 + 18 * p, height: 10 + 18 * p) }
            Circle().fill(C.blue).frame(width: 10, height: 10)
        }
        .frame(width: 10, height: 10)
        .onAppear { if Motion.on { withAnimation(.linear(duration: 1.7).delay(1).repeatForever(autoreverses: false)) { p = 1 } } }
    }
}

/// Nothing to show: the drop bobs in the middle among a few little notes.
private struct NotesEmpty: View {
    let f: String
    @State private var bob = false
    var body: some View {
        let (title, body): (String, String) = {
            switch f {
            case "unread": return (t("You’re all caught up", "Umesoma zote"), t("Nothing unread. Nice work!", "Hakuna ambayo hujasoma. Kazi nzuri!"))
            case "me": return (t("Nothing assigned to you right now", "Hakuna ulichopewa kwa sasa"), t("Visits and repair jobs given to you will show up here.", "Ziara na kazi za matengenezo utakazopewa zitaonekana hapa."))
            case "repairs": return (t("No repair news", "Hakuna habari za matengenezo"), t("Repairs the team reports and fixes will show up here.", "Matengenezo yanayoripotiwa na kurekebishwa na timu yataonekana hapa."))
            default: return (t("You’re all caught up", "Hakuna jipya kwa sasa"), t("When the team reports a repair, fixes a water point or gives you visits, it shows up here.", "Timu ikiripoti matengenezo, ikirekebisha kituo cha maji au ikikupa ziara, itaonekana hapa."))
            }
        }()
        let cards: [(String, String)] = f == "me" ? [("cal", "teal"), ("wrench", "amber"), ("clip", "blue")] : f == "repairs" ? [("alert", "coral"), ("okc", "green"), ("wrench", "amber")] : [("okc", "green"), ("cal", "teal"), ("alert", "coral")]
        VStack(spacing: 6) {
            ZStack {
                Circle().fill(RadialGradient(colors: [C.named("blue").fg.opacity(0.24), C.named("blue").fg.opacity(0)], center: .center, startRadius: 0, endRadius: 85))
                    .frame(width: 170, height: 170).scaleEffect(bob ? 1.06 : 0.92)
                note(cards[0], -7).offset(x: -78, y: -62)
                note(cards[1], 6).offset(x: 86, y: -10)
                note(cards[2], 4).offset(x: -70, y: 58)
                Drop(size: 96, mood: .smile).offset(y: -5)
                Icon("check", size: 17, color: C.card).frame(width: 30, height: 30).background(C.ok).clipShape(Circle()).padding(4).background(C.card).clipShape(Circle()).offset(x: 41, y: -61)
            }.frame(width: 272, height: 212)
            Text(title).font(.tLarge).foregroundColor(C.ink).multilineTextAlignment(.center).padding(.horizontal, 16).padding(.top, 8)
            Text(body).font(.bMedium).foregroundColor(C.muted).multilineTextAlignment(.center).padding(.horizontal, 28)
        }
        .frame(maxWidth: .infinity).padding(.top, 18).rise(0)
        .onAppear { if Motion.on { withAnimation(.easeInOut(duration: 1.8).repeatForever(autoreverses: true)) { bob = true } } }
    }
    private func note(_ c: (String, String), _ r: Double) -> some View {
        let hu = C.named(c.1)
        return HStack(spacing: 8) {
            Icon(c.0, size: 13, color: C.card).frame(width: 24, height: 24).background(hu.fg).clipShape(Circle())
            VStack(alignment: .leading, spacing: 5) {
                Capsule().fill(hu.bg).frame(height: 6)
                Capsule().fill(C.sun ? Color(hex: 0x8A99A6) : C.line).frame(width: 46, height: 6)
            }
        }
        .padding(.horizontal, 10).padding(.vertical, 9).frame(width: 122)
        .background(C.card).clipShape(RoundedRectangle(cornerRadius: 15, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 15, style: .continuous).stroke(C.line, lineWidth: C.sun ? 2 : 1))
        .shadow(color: .black.opacity(C.sun ? 0 : 0.1), radius: 8, y: 3)
        .rotationEffect(.degrees(r))
    }
}

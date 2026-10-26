import SwiftUI
import UIKit

/// Someone on the team, from /api/pin?team=1.
struct Mate: Identifiable, Equatable { var id: String { name }; let name: String; let home: String; let phone: String; let lastSeen: String? }

private func same(_ a: String, _ b: String) -> Bool { a.trimmingCharacters(in: .whitespaces).lowercased() == b.trimmingCharacters(in: .whitespaces).lowercased() }
private func dayOf(_ iso: String) -> Date? { When.parse(iso).map { Calendar.current.startOfDay(for: $0) } }
private let WORK: Set<String> = ["pin", "visit", "fixed", "new"]

/// "3 min ago", "2 h ago", "yesterday", "4 days ago".
func ago(_ iso: String?) -> String {
    guard let iso, let at = When.parse(iso) else { return t("never", "bado") }
    let min = Int(Date().timeIntervalSince(at) / 60)
    if min < 2 { return t("just now", "sasa hivi") }
    if min < 60 { return t("\(min) min ago", "dakika \(min) zilizopita") }
    if min < 1440 { return t("\(min / 60) h ago", "saa \(min / 60) zilizopita") }
    if min < 2880 { return t("yesterday", "jana") }
    return t("\(min / 1440) days ago", "siku \(min / 1440) zilizopita")
}

/// +254 712… from 0712… using the person's home country.
func waNumber(_ phone: String, _ home: String) -> String {
    let d = phone.filter { $0.isNumber }
    let cc = ["ke": "254", "tz": "255", "ug": "256", "mw": "265"][home] ?? "254"
    if phone.trimmingCharacters(in: .whitespaces).hasPrefix("+") { return d }
    return d.hasPrefix("0") ? cc + d.dropFirst() : d
}

private func doneVerb(_ a: String) -> String {
    switch a { case "pin": return t("Pinned", "Aliweka mahali"); case "visit": return t("Visited", "Alitembelea"); case "fixed": return t("Fixed", "Alirekebisha"); case "new": return t("Added", "Aliongeza"); default: return a }
}
private func actKind(_ a: String) -> String { a == "new" ? "pin" : a }

// MARK: - My month

struct MyMonth: View {
    @EnvironmentObject var m: AppModel
    let act: [JSON]?
    var body: some View {
        CardV {
            HStack { H2(t("My month", "Mwezi wangu")); Spacer(); Text(Date().formatted(.dateTime.month(.wide).locale(Locale(identifier: Lang.sw ? "sw" : "en_GB")))).font(.lMedium).foregroundColor(C.muted) }
            if let act {
                let mine = act.filter { same($0.str("by"), m.prefs.name) && WORK.contains($0.str("a")) }
                let cal = Calendar.current, today = cal.startOfDay(for: Date())
                let start = cal.date(from: cal.dateComponents([.year, .month], from: today))!
                let month = mine.filter { (dayOf($0.str("at")) ?? .distantPast) >= start }
                HStack(spacing: 8) {
                    ForEach(Array([("pin", t("Pins", "Mahali"), "pin", "pin"), ("visit", t("Visits", "Ziara"), "clip", "near"), ("fixed", t("Fixed", "Zimerekebishwa"), "wrench", "repairs")].enumerated()), id: \.element.0) { i, x in
                        let n = month.filter { actKind($0.str("a")) == x.0 }.count, hu = C.hue(x.3)
                        VStack(spacing: 2) {
                            Icon(x.2, size: 18, color: hu.fg)
                            CountUp(value: n, font: .mont(26, .heavy), color: hu.fg)
                            Text(x.1).font(.lSmall).foregroundColor(C.ink)
                        }.frame(maxWidth: .infinity).padding(.vertical, 12).background(hu.bg).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).rise(i + 1)
                    }
                }.padding(.top, 4)
                let weeks: [Int] = (0..<6).reversed().map { w in
                    let to = cal.date(byAdding: .day, value: -w * 7, to: today)!, from = cal.date(byAdding: .day, value: -6, to: to)!
                    return mine.filter { if let d = dayOf($0.str("at")) { return d >= from && d <= to }; return false }.count
                }
                let top = max(1, weeks.max() ?? 1)
                Text(t("Last 6 weeks", "Wiki 6 zilizopita")).font(.lMedium).foregroundColor(C.muted).padding(.top, 14).padding(.bottom, 6)
                HStack(alignment: .bottom, spacing: 8) {
                    ForEach(Array(weeks.enumerated()), id: \.offset) { i, n in
                        VStack(spacing: 2) {
                            Spacer(minLength: 0)
                            if n > 0 { Text("\(n)").font(.lSmall).foregroundColor(C.muted) }
                            Bar(f: max(0.06, Double(n) / Double(top) * 0.8), delay: Double(i) * 0.06).fill(i == 5 ? C.blue : C.blue.opacity(0.35))
                        }
                    }
                }.frame(height: 64)
                HStack { Text(t("6 wk ago", "Wiki 6")).font(.lSmall).foregroundColor(C.muted); Spacer(); Text(t("This week", "Wiki hii")).font(.lSmall).foregroundColor(C.muted) }.padding(.top, 4)
            } else { Text(t("Loading…", "Inapakia…")).font(.bMedium).foregroundColor(C.muted) }
        }.padding(.top, 14)
    }
}

/// A bar in the week chart that grows up when it first shows.
private struct Bar: View {
    let f: Double; var delay = 0.0; var color: Color = .blue
    @State private var shown = 0.0
    func fill(_ c: Color) -> Bar { var b = self; b.color = c; return b }
    var body: some View {
        GeometryReader { g in
            VStack { Spacer(minLength: 0); UnevenRoundedRect().fill(color).frame(height: g.size.height * shown) }
        }
        .onAppear { if Motion.on { withAnimation(.spring(response: 0.6, dampingFraction: 0.8).delay(delay)) { shown = f } } else { shown = f } }
    }
}
private struct UnevenRoundedRect: Shape {
    func path(in r: CGRect) -> Path { Path(roundedRect: r, cornerSize: CGSize(width: 6, height: 6)) }
}

// MARK: - My work

struct MyWork: View {
    @EnvironmentObject var m: AppModel
    let snap: Snapshot
    var body: some View {
        let today = When.localDay()
        let visits = snap.assigned.sorted { ($0.date < today ? 0 : 1, $0.date) < ($1.date < today ? 0 : 1, $1.date) }
        let jobs = snap.jobs.compactMap { id in snap.projects.first { $0.id == id }.map { ($0, snap.repairs[id]) } }
        if !visits.isEmpty || !jobs.isEmpty {
            CardV {
                HStack { H2(t("My work", "Kazi yangu")); Spacer(); Tag("\(visits.count + jobs.count)", bg: C.tint, fg: C.deep) }
                ForEach(visits, id: \.aid) { a in
                    let late = a.date < today, now = a.date == today
                    WorkRow(icon: "cal", hue: "due", title: a.title.isEmpty ? (snap.projects.first { $0.id == a.id }?.title ?? a.id) : a.title,
                            sub: t("Visit", "Ziara") + (a.note.isEmpty ? "" : " · " + a.note) + " · " + t("from ", "kutoka ") + a.by,
                            tag: late ? t("Overdue", "Imechelewa") : now ? t("Today", "Leo") : String(a.date.dropFirst(5)), tone: late ? "bad" : now ? "ok" : "") { m.go(Place(tab: "me", project: a.id, projectTab: "visit")) }
                }
                ForEach(jobs, id: \.0.id) { p, r in
                    let late = !(r?.due ?? "").isEmpty && (r?.due ?? "") < today
                    WorkRow(icon: "wrench", hue: "repairs", title: p.title, sub: t("Repair job", "Kazi ya matengenezo") + ((r?.due ?? "").isEmpty ? "" : " · " + t("due ", "mwisho ") + String(r!.due.dropFirst(5))),
                            tag: late ? t("Overdue", "Imechelewa") : t("Job", "Kazi"), tone: late ? "bad" : "warn") { m.go(Place(tab: "me", project: p.id)) }
                }
            }.padding(.top, 14)
        }
    }
}

private struct WorkRow: View {
    let icon: String; let hue: String; let title: String; let sub: String; let tag: String; let tone: String; let open: () -> Void
    var body: some View {
        Button(action: open) {
            HStack(spacing: 12) {
                IconTile(icon: icon, tint: C.hue(hue).fg, bg: C.hue(hue).bg)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.tSmall).foregroundColor(C.ink).lineLimit(1)
                    Text(sub).font(.bSmall).foregroundColor(C.muted).lineLimit(2)
                }
                Spacer(minLength: 8)
                switch tone {
                case "bad": Tag(tag, bg: C.badBg, fg: C.bad)
                case "ok": Tag(tag, bg: C.okBg, fg: C.ok)
                case "warn": Tag(tag, bg: C.warnBg, fg: C.warn)
                default: Tag(tag, bg: C.tint, fg: C.deep)
                }
            }.padding(6).contentShape(Rectangle())
        }.buttonStyle(Press()).padding(.top, 8)
    }
}

// MARK: - Waiting to send

struct WaitingList: View {
    @EnvironmentObject var m: AppModel
    let snap: Snapshot?
    @State private var busy = false
    var body: some View {
        if m.waiting > 0 {
            let items = m.outbox.all()
            CardV(fill: C.dark ? C.card : Color(hex: 0xFFFBF4), border: Color(hex: 0xF2C98A)) {
                HStack(spacing: 12) {
                    IconTile(icon: "upload", tint: C.hue("repairs").fg, bg: C.hue("repairs").bg, size: 40)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(m.waiting == 1 ? t("1 change waiting to send", "Badiliko 1 linasubiri kutumwa") : t("\(m.waiting) changes waiting to send", "Mabadiliko \(m.waiting) yanasubiri kutumwa")).font(.tMedium).foregroundColor(C.warn)
                        Text(t("Safe on this phone. They go as soon as there’s signal.", "Yako salama kwenye simu hii. Yatatumwa mara mtandao ukipatikana.")).font(.bSmall).foregroundColor(C.muted)
                    }
                }
                ForEach(items, id: \.k) { it in
                    let b = it.body, id = b.str("id")
                    let title = snap?.projects.first { $0.id == id }?.title ?? (b.str("name").isEmpty ? (b.str("title").isEmpty ? id : b.str("title")) : b.str("name"))
                    let w: (String, String) = {
                        switch b.str("action") {
                        case "pin": return ("pin", t("Location", "Mahali"))
                        case "visit": return ("clip", t("Site visit", "Ziara") + (b.str("state").isEmpty ? "" : " · " + stateName(b.str("state"))))
                        case "fixed": return ("wrench", t("Marked fixed", "Imerekebishwa"))
                        case "new": return ("plus", t("New site", "Eneo jipya"))
                        case "undo": return ("undo", t("Undo a pin", "Tendua mahali"))
                        default: return ("inbox", t("Change", "Badiliko"))
                        }
                    }()
                    let files = it.files.count - it.done
                    HStack(spacing: 10) {
                        Icon(w.0, size: 18, color: C.warn)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(title).font(.tSmall).foregroundColor(C.ink).lineLimit(1)
                            Text(w.1 + (files > 0 ? " · " + (files == 1 ? t("1 photo or voice note", "picha au sauti 1") : t("\(files) photos or voice notes", "picha au sauti \(files)")) : "")
                                 + (b.str("savedAt").isEmpty ? "" : " · " + t("saved ", "imehifadhiwa ") + ago(b.str("savedAt")))).font(.bSmall).foregroundColor(C.muted)
                        }
                        Spacer(minLength: 0)
                    }.padding(.horizontal, 12).padding(.vertical, 10).background(C.warnBg).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).padding(.top, 8)
                }
                Btn(busy ? t("Sending…", "Inatuma…") : t("Send now", "Tuma sasa"), icon: "upload", enabled: !busy, wide: true) {
                    busy = true; Task { await m.refreshNow(); busy = false }
                }.padding(.top, 12)
            }.padding(.top, 14)
        }
    }
}

// MARK: - Team today

struct TeamToday: View {
    @EnvironmentObject var m: AppModel
    let mates: [Mate]?; let act: [JSON]?; let snap: Snapshot?
    private var rows: [(Mate, JSON?, String)] {
        (mates ?? []).filter { !same($0.name, m.prefs.name) }.map { p in
            let last = (act ?? []).filter { same($0.str("by"), p.name) && WORK.contains($0.str("a")) }.max { $0.str("at") < $1.str("at") }
            return (p, last, max(last?.str("at") ?? "", p.lastSeen ?? ""))
        }.sorted { $0.2 > $1.2 }
    }
    private var active: Int { let today = Calendar.current.startOfDay(for: Date()); return rows.filter { dayOf($0.2) == today }.count }
    var body: some View {
        CardV {
            HStack { H2(t("Team today", "Timu leo")); Spacer(); if active > 0 { Tag(t("\(active) active", "\(active) kazini"), bg: C.okBg, fg: C.ok) } }
            if let mates {
                let today = Calendar.current.startOfDay(for: Date()); let _ = mates
                Text(active == 0 ? t("Nobody else has used the app today yet.", "Hakuna mwingine aliyetumia programu leo bado.") : rows.contains(where: { !$0.0.phone.isEmpty }) ? t("Tap someone to call or WhatsApp them. Tap what they did to open the project.", "Gusa mtu kumpigia au kumtumia WhatsApp. Gusa alichofanya kufungua mradi.") : active == 1 ? t("1 person active today", "Mtu 1 yuko kazini leo") : t("\(active) people active today", "Watu \(active) wako kazini leo"))
                    .font(.bSmall).foregroundColor(C.muted)
                ForEach(Array(rows.enumerated()), id: \.element.0.name) { i, r in
                    let (p, e, at) = r
                    let place = e.flatMap { x in snap?.projects.first { $0.id == x.str("id") } }
                    HStack(spacing: 12) {
                        ZStack(alignment: .bottomTrailing) {
                            PersonAvatar(name: p.name, size: 42)
                            if dayOf(at) == today { Circle().fill(GreenC).frame(width: 11, height: 11).overlay(Circle().stroke(Color.white, lineWidth: 2)) }
                        }.onTapGesture { Contact.show(p.name) }.accessibilityAddTraits(.isButton)
                        Button { if let id = e?.str("id"), place != nil { m.go(Place(tab: "me", project: id)) } } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(p.name).font(.tSmall).foregroundColor(C.ink).lineLimit(1)
                                Text(e.map { x in doneVerb(x.str("a")) + " " + (place?.title ?? x.str("t")) + ((place?.area ?? "").isEmpty ? "" : " · " + place!.area) + " · " + ago(x.str("at")) } ?? t("Last seen ", "Alionekana ") + ago(p.lastSeen))
                                    .font(.bSmall).foregroundColor(C.muted).lineLimit(2).multilineTextAlignment(.leading)
                            }.frame(maxWidth: .infinity, alignment: .leading)
                        }.buttonStyle(.plain)
                        if !p.phone.isEmpty {
                            RoundAction(icon: "phone", label: t("Call \(p.name)", "Mpigie \(p.name)")) { Share.open("tel:" + p.phone.replacingOccurrences(of: " ", with: "")) }
                            RoundAction(icon: "chat", label: "WhatsApp \(p.name)") { Share.open("https://wa.me/" + waNumber(p.phone, p.home)) }
                        }
                    }.padding(.top, 10).rise(i, step: 0.05)
                }
                if !rows.contains(where: { !$0.0.phone.isEmpty }) && snap?.admin == true {
                    Text(t("Add phone numbers in Office › Team to call or WhatsApp from here.", "Ongeza namba za simu kwenye Ofisi › Timu ili kupiga au kutuma WhatsApp kutoka hapa.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 10)
                }
            } else { Text(t("Loading…", "Inapakia…")).font(.bMedium).foregroundColor(C.muted) }
        }.padding(.top, 14)
    }
}

private struct RoundAction: View {
    let icon: String; let label: String; let action: () -> Void
    var body: some View {
        Button { Haptic.tap(); action() } label: { Icon(icon, size: 19, color: C.hue("near").fg).frame(width: 40, height: 40).background(C.hue("near").bg).clipShape(Circle()) }
            .buttonStyle(Press()).accessibilityLabel(label)
    }
}

// MARK: - Team totals and feed

struct TeamMonth: View {
    let act: [JSON]?
    @State private var month = true
    var body: some View {
        CardV {
            H2(t("Team totals", "Jumla ya timu"))
            SmallSeg(items: [("w", t("This week", "Wiki hii")), ("m", t("This month", "Mwezi huu"))], sel: month ? "m" : "w", fill: true) { month = $0 == "m" }.padding(.top, 4)
            if let act {
                let cal = Calendar.current, today = cal.startOfDay(for: Date())
                let from = month ? cal.date(from: cal.dateComponents([.year, .month], from: today))! : cal.date(byAdding: .day, value: -((cal.component(.weekday, from: today) + 5) % 7), to: today)!
                let rows = act.filter { WORK.contains($0.str("a")) && (dayOf($0.str("at")) ?? .distantPast) >= from }
                let names = Array(Set(rows.map { $0.str("by").trimmingCharacters(in: .whitespaces) })).sorted { $0.lowercased() < $1.lowercased() }
                let tot = { (k: String) in rows.filter { actKind($0.str("a")) == k }.count }
                HStack {
                    Text(t("Together", "Pamoja")).font(.tSmall).foregroundColor(C.deep)
                    Spacer()
                    Text(t("\(tot("pin")) pins · \(tot("visit")) visits · \(tot("fixed")) fixed", "mahali \(tot("pin")) · ziara \(tot("visit")) · \(tot("fixed")) zimerekebishwa")).font(.lMedium).foregroundColor(C.deep)
                }.padding(.horizontal, 14).padding(.vertical, 10).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).padding(.top, 12)
                if names.isEmpty { Text(t("Nothing yet in this period.", "Hakuna bado katika kipindi hiki.")).font(.bMedium).foregroundColor(C.muted).padding(.top, 10) }
                ForEach(names, id: \.self) { n in
                    let l = rows.filter { same($0.str("by"), n) }
                    HStack(spacing: 6) {
                        PersonAvatar(name: n, size: 28).onTapGesture { Contact.show(n) }
                        Text(n).font(.tSmall).foregroundColor(C.ink).lineLimit(1).onTapGesture { Contact.show(n) }
                        Spacer(minLength: 4)
                        ForEach([("pin", "pin", "pin"), ("visit", "near", "clip"), ("fixed", "repairs", "wrench")], id: \.0) { k, hue, icon in
                            let hu = C.hue(hue)
                            HStack(spacing: 4) { Icon(icon, size: 13, color: hu.fg); Text("\(l.filter { actKind($0.str("a")) == k }.count)").font(.mont(12, .bold)).foregroundColor(hu.fg) }
                                .padding(.horizontal, 9).padding(.vertical, 4).background(hu.bg).clipShape(Capsule())
                        }
                    }.padding(.top, 10)
                }
            } else { Text(t("Loading…", "Inapakia…")).font(.bMedium).foregroundColor(C.muted).padding(.top, 8) }
        }.padding(.top, 14)
    }
}

struct TeamFeed: View {
    @EnvironmentObject var m: AppModel
    let act: [JSON]?; let snap: Snapshot?
    @State private var more = false
    var body: some View {
        CardV {
            H2(t("Team feed", "Habari za timu"))
            if let act {
                let list = act.filter { WORK.contains($0.str("a")) || $0.str("a") == "job" }.sorted { $0.str("at") > $1.str("at") }
                if list.isEmpty { Text(t("Nothing yet. Pins, visits and fixes show here.", "Bado hakuna. Mahali, ziara na marekebisho yataonekana hapa.")).font(.bMedium).foregroundColor(C.muted) }
                ForEach(Array(list.prefix(more ? 40 : 8).enumerated()), id: \.offset) { i, e in
                    let ic: (String, String) = { switch e.str("a") { case "pin": return ("pin", "pin"); case "visit": return ("clip", "near"); case "fixed": return ("check", "near"); case "new": return ("plus", "new"); default: return ("wrench", "repairs") } }()
                    let hu = C.hue(ic.1), id = e.str("id"), p = snap?.projects.first { $0.id == id }
                    Button { if p != nil { m.go(Place(tab: "me", project: id)) } } label: {
                        HStack(spacing: 10) {
                            PersonBadgeAvatar(name: e.str("by"), icon: ic.0, hue: hu, ring: C.card, size: 34)
                            VStack(alignment: .leading, spacing: 2) {
                                (Text(String(e.str("by").split(separator: " ").first ?? "")).bold() + Text(" " + verb(e.str("a")) + " " + (p?.title ?? e.str("t")) + (e.str("a") == "visit" && !e.str("s").isEmpty ? " · " + stateName(e.str("s")) : "")))
                                    .font(.bMedium).foregroundColor(C.text).lineLimit(2).multilineTextAlignment(.leading)
                                Text(ago(e.str("at"))).font(.lSmall).foregroundColor(C.muted)
                            }
                            Spacer(minLength: 0)
                            if p != nil { Icon("chev", size: 16, color: C.muted) }
                        }.padding(6).contentShape(Rectangle())
                    }.buttonStyle(.plain).padding(.top, 6).rise(i, step: 0.04)
                }
                if list.count > 8 {
                    Button(more ? t("Show less", "Onyesha kidogo") : t("Show more", "Onyesha zaidi")) { withAnimation { more.toggle() } }.font(.lMedium).foregroundColor(C.blue).padding(.top, 10)
                }
            } else { Text(t("Loading…", "Inapakia…")).font(.bMedium).foregroundColor(C.muted) }
        }.padding(.top, 14)
    }
}

// MARK: - Your name on monday.com (personal token) and Face ID

let TOKEN_PAGE = "https://rafikicap-company.monday.com/apps/manage/tokens"
func looksToken(_ s: String?) -> Bool { guard let s = s?.trimmingCharacters(in: .whitespacesAndNewlines) else { return false }; return s.count >= 40 && s.count <= 4000 && s.allSatisfy { $0.isLetter || $0.isNumber || "._-".contains($0) } }

struct ConnectMonday: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.scenePhase) private var phase
    let snap: Snapshot
    var inline = false
    @State private var waiting = false
    @State private var busy = false
    @State private var msg: String?
    @State private var manual = false
    @State private var typed = ""
    var body: some View {
        if m.prefs.session.isEmpty {
            Wrap(inline: inline) {
                if !inline { HStack(alignment: .top, spacing: 12) {
                    IconTile(icon: "b:monday", tint: C.hue("new").fg, bg: C.hue("new").bg, size: 40)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(t("Your name on monday.com", "Jina lako kwenye monday.com")).font(.tLarge).foregroundColor(C.ink)
                        if !snap.linked.isEmpty { Text(t("Connected as \(snap.linked) ✓", "Imeunganishwa kama \(snap.linked) ✓")).font(.tSmall).foregroundColor(C.ok) }
                        else if snap.who.isEmpty { Text(t("Your changes go in under the shared account. To use your own name, ask the office for your own PIN.", "Mabadiliko yako yanaingia kwa akaunti ya pamoja. Kutumia jina lako, omba ofisi PIN yako mwenyewe.")).font(.bSmall).foregroundColor(C.muted) }
                        else { Text(t("Takes 20 seconds, once. Your changes then show on monday.com under your own name.", "Inachukua sekunde 20, mara moja. Mabadiliko yako yataonekana monday.com kwa jina lako.")).font(.bSmall).foregroundColor(C.muted) }
                    }
                } }
                if !snap.linked.isEmpty {
                    Text(t("Everything you save goes to monday.com under your own name.", "Kila unachohifadhi kinaenda monday.com kwa jina lako.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 8)
                    Button(t("Disconnect", "Tenganisha")) { Task { await m.unlinkMonday() } }.font(.lMedium).foregroundColor(C.blue).padding(.top, 8)
                } else if !snap.who.isEmpty {
                    Btn("1 · " + t("Open my monday.com token", "Fungua tokeni yangu ya monday.com"), wide: true) { waiting = true; Share.open(TOKEN_PAGE) }.padding(.top, 12)
                    Text(t("Tap Copy on that page, then come back here.", "Gusa Copy kwenye ukurasa huo, kisha rudi hapa.")).font(.lSmall).foregroundColor(C.muted).frame(maxWidth: .infinity).padding(.top, 4)
                    Btn(busy ? t("Checking…", "Inakagua…") : "2 · " + t("Paste and connect", "Bandika na uunganishe"), enabled: !busy, light: true, wide: true) { send(UIPasteboard.general.string ?? "") }.padding(.top, 10)
                    Button(t("Or paste it yourself", "Au ibandike mwenyewe")) { manual.toggle() }.font(.lMedium).foregroundColor(C.blue).padding(.top, 8)
                    if manual {
                        SecureField(t("monday.com token", "tokeni ya monday.com"), text: $typed).font(.bLarge).padding(12).background(C.card).clipShape(RoundedRectangle(cornerRadius: 14)).overlay(RoundedRectangle(cornerRadius: 14).stroke(C.edge))
                        Btn(t("Connect", "Unganisha"), enabled: !busy, small: true) { send(typed) }.padding(.top, 8)
                    }
                    Msg(msg, busy ? "" : "bad")
                    Text(t("On a phone, monday.com may only open this page on a computer. You can do this step on any computer: sign in with your PIN at pin.rafikioutreach.org and connect. It then works on every phone that uses your PIN.", "Kwenye simu, monday.com inaweza kufungua ukurasa huu kwenye kompyuta tu. Unaweza kufanya hatua hii kwenye kompyuta yoyote: ingia kwa PIN yako kwenye pin.rafikioutreach.org na uunganishe. Kisha itafanya kazi kwenye kila simu inayotumia PIN yako."))
                        .font(.lSmall).foregroundColor(C.muted).padding(.top, 10)
                }
            }
            .padding(.top, inline ? 0 : 14)
            .onChange(of: phase) { p in if p == .active, waiting, !busy, looksToken(UIPasteboard.general.string) { send(UIPasteboard.general.string!) } }
        }
    }
    func send(_ tok: String) {
        guard looksToken(tok) else { msg = reasonText("token-format"); return }
        busy = true; msg = t("Checking with monday.com…", "Inakagua na monday.com…")
        Task {
            let err = await m.linkMonday(tok); busy = false; msg = err
            if err == nil { waiting = false; UIPasteboard.general.string = ""; Haptic.ok() }
        }
    }
}

/// A card, or just the content when it sits inside another card.
private struct Wrap<Content: View>: View {
    let inline: Bool
    @ViewBuilder var content: () -> Content
    var body: some View {
        if inline { VStack(alignment: .leading, spacing: 0) { content() }.frame(maxWidth: .infinity, alignment: .leading) } else { CardV { content() } }
    }
}

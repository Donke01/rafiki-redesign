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
    switch a { case "pin": return t("Pinned", "Aliweka mahali"); case "visit": return t("Visited", "Alitembelea"); case "fixed": return t("Fixed", "Alirekebisha"); case "new": return t("Added", "Aliongeza"); case "job": return t("Updated the repair job on", "Alisasisha kazi ya matengenezo ya"); default: return a }
}
private func actKind(_ a: String) -> String { a == "new" ? "pin" : a }

// MARK: - My month

/// My month: the last six weeks as a chart (this month's numbers are on the card at the top).
struct MyMonth: View {
    @EnvironmentObject var m: AppModel
    let act: [JSON]?
    var body: some View {
        CardV {
            MeH2(t("My month", "Mwezi wangu")) { Text(t("last 6 weeks", "wiki 6 zilizopita")).font(.lMedium).foregroundColor(C.muted) }
            if let act {
                let mine = act.filter { same($0.str("by"), m.prefs.name) && WORK.contains($0.str("a")) }
                let cal = Calendar.current, today = cal.startOfDay(for: Date())
                let weeks: [Int] = (0..<6).reversed().map { w in
                    let to = cal.date(byAdding: .day, value: -w * 7, to: today) ?? today, from = cal.date(byAdding: .day, value: -6, to: to) ?? to
                    return mine.filter { if let d = dayOf($0.str("at")) { return d >= from && d <= to }; return false }.count
                }
                let top = max(1, weeks.max() ?? 1)
                HStack(alignment: .bottom, spacing: 8) {
                    ForEach(Array(weeks.enumerated()), id: \.offset) { i, n in
                        VStack(spacing: 2) {
                            Spacer(minLength: 0)
                            if n > 0 { Text("\(n)").font(.lSmall).foregroundColor(C.muted) }
                            Bar(f: max(0.06, Double(n) / Double(top) * 0.8), delay: Double(i) * 0.06).fill(i == 5 ? C.blue : C.blue.opacity(0.35))
                        }
                    }
                }.frame(height: 64).padding(.top, 10)
                HStack { Text(t("6 wk ago", "Wiki 6")).font(.lSmall).foregroundColor(C.muted); Spacer(); Text(t("This week", "Wiki hii")).font(.lSmall).foregroundColor(C.muted) }.padding(.top, 4)
            } else { Text(t("Loading…", "Inapakia…")).font(.bMedium).foregroundColor(C.muted).frame(maxWidth: .infinity) }
        }.padding(.top, 14)
    }
}

/// "Thu 24 Oct" ("Alh 24 Okt"), or "24 Oct 2027" in another year.
func meDueDay(_ d: Date) -> String {
    let cal = Calendar.current
    let daysSw = ["Jpl", "Jtt", "Jnn", "Jtn", "Alh", "Iju", "Jmo"], monSw = ["Jan", "Feb", "Mac", "Apr", "Mei", "Jun", "Jul", "Ago", "Sep", "Okt", "Nov", "Des"]
    let f = DateFormatter(); f.locale = Locale(identifier: "en_US")
    let mi = cal.component(.month, from: d) - 1, wd = cal.component(.weekday, from: d) - 1, day = cal.component(.day, from: d), y = cal.component(.year, from: d)
    f.dateFormat = "MMM"; let mon = Lang.sw ? monSw[mi] : f.string(from: d)
    if y != cal.component(.year, from: Date()) { return "\(day)\u{00A0}\(mon)\u{00A0}\(y)" }
    f.dateFormat = "EEE"; let w = Lang.sw ? daysSw[wd] : f.string(from: d)
    return "\(w)\u{00A0}\(day)\u{00A0}\(mon)"
}
private func daysFromToday(_ iso: String) -> (Date, Int)? {
    guard let d = DateField.parse(String(iso.prefix(10))) else { return nil }
    let cal = Calendar.current
    return (d, cal.dateComponents([.day], from: cal.startOfDay(for: Date()), to: cal.startOfDay(for: d)).day ?? 0)
}
/// "due today", "due tomorrow", "due in 2 days", "due Thu 24 Oct", "3 days late".
func meDueText(_ iso: String) -> String {
    guard let r = daysFromToday(iso) else { return iso }
    let d = r.0, n = r.1
    if n < -1 { return t("\(-n) days late", "imechelewa siku \(-n)") }
    if n == -1 { return t("1 day late", "imechelewa siku 1") }
    if n == 0 { return t("due today", "mwisho leo") }
    if n == 1 { return t("due tomorrow", "mwisho kesho") }
    if n < 7 { return t("due in \(n) days", "mwisho baada ya siku \(n)") }
    return t("due ", "mwisho ") + meDueDay(d)
}
/// The short tag on the right: "Today", "Tomorrow", "In 2 days", "Thu 24 Oct".
func meDueTag(_ iso: String) -> String {
    guard let r = daysFromToday(iso) else { return iso }
    let d = r.0, n = r.1
    if n == 0 { return t("Today", "Leo") }
    if n == 1 { return t("Tomorrow", "Kesho") }
    if (2...6).contains(n) { return t("In \(n) days", "Baada ya siku \(n)") }
    return meDueDay(d)
}

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
                MeH2(t("My work", "Kazi yangu")) { Tag("\(visits.count + jobs.count)", bg: C.tint, fg: C.deep) }
                ForEach(visits, id: \.aid) { a in
                    let late = a.date < today, now = a.date == today
                    WorkRow(icon: "cal", hue: "due", title: a.title.isEmpty ? (snap.projects.first { $0.id == a.id }?.title ?? a.id) : a.title,
                            sub: t("Visit", "Ziara") + (a.note.isEmpty ? "" : " · " + a.note) + " · " + t("from\u{00A0}", "kutoka\u{00A0}") + a.by,
                            tag: late ? t("Overdue", "Imechelewa") : meDueTag(a.date), tone: late ? "bad" : now ? "ok" : "") { m.go(Place(tab: m.here.tab, project: a.id, projectTab: "visit")) }
                }
                ForEach(jobs, id: \.0.id) { p, r in
                    let due = r?.due ?? ""
                    let late = !due.isEmpty && due < today
                    WorkRow(icon: "wrench", hue: "repairs", title: p.title, sub: t("Repair job", "Kazi ya matengenezo") + (due.isEmpty ? "" : " · " + meDueText(due)),
                            tag: late ? t("Overdue", "Imechelewa") : t("Job", "Kazi"), tone: late ? "bad" : "warn") { m.openProject(p.id) }
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

/// A water committee's SMS or WhatsApp report: shown as "Community report" with a phone, never as someone on the team.
func isCommunity(_ e: JSON) -> Bool { e.str("src") == "sms" || e.str("by").hasPrefix("SMS from") || e.str("by").hasPrefix("WhatsApp from") }
func communityName() -> String { t("Community report", "Ripoti ya jamii") }

/// Who has been working today and where; call or WhatsApp them. A picture opens their contact sheet.
struct TeamToday: View {
    @EnvironmentObject var m: AppModel
    let mates: [Mate]?; let act: [JSON]?; let snap: Snapshot?
    private var rows: [(Mate, JSON?, String)] {
        (mates ?? []).filter { !same($0.name, m.prefs.name) }.map { p in
            let last = (act ?? []).filter { same($0.str("by"), p.name) && WORK.contains($0.str("a")) }.max { $0.str("at") < $1.str("at") }
            return (p, last, max(last?.str("at") ?? "", p.lastSeen ?? ""))
        }.sorted { $0.2 > $1.2 }
    }
    var body: some View {
        let today = Calendar.current.startOfDay(for: Date())
        let list = rows
        let active = list.filter { dayOf($0.2) == today }.count
        CardV {
            MeH2(t("Team today", "Timu leo")) { if active > 0 { Tag(t("\(active) active", "\(active) kazini"), bg: C.okBg, fg: C.ok) } }
            if mates != nil {
                Text(active == 0 ? t("Nobody else has used the app today yet.", "Hakuna mwingine aliyetumia programu leo bado.") : list.contains(where: { !$0.0.phone.isEmpty }) ? t("Tap someone to call or WhatsApp them. Tap what they did to open the project.", "Gusa mtu kumpigia au kumtumia WhatsApp. Gusa alichofanya kufungua mradi.") : active == 1 ? t("1 person active today", "Mtu 1 yuko kazini leo") : t("\(active) people active today", "Watu \(active) wako kazini leo"))
                    .font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.center).frame(maxWidth: .infinity).fixedSize(horizontal: false, vertical: true)
                ForEach(Array(list.enumerated()), id: \.element.0.name) { i, r in
                    MateRow(p: r.0, e: r.1, onToday: dayOf(r.2) == today, snap: snap).padding(.top, 10).rise(i, step: 0.05)
                }
                if !list.contains(where: { !$0.0.phone.isEmpty }) && snap?.admin == true {
                    Text(t("Add phone numbers in Office › Team to call or WhatsApp from here.", "Ongeza namba za simu kwenye Ofisi › Timu ili kupiga au kutuma WhatsApp kutoka hapa.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 10).fixedSize(horizontal: false, vertical: true)
                }
            } else { Text(t("Loading…", "Inapakia…")).font(.bMedium).foregroundColor(C.muted).frame(maxWidth: .infinity) }
        }.padding(.top, 14)
    }
}

private struct MateRow: View {
    @EnvironmentObject var m: AppModel
    let p: Mate; let e: JSON?; let onToday: Bool; let snap: Snapshot?
    var body: some View {
        let place = e.flatMap { x in snap?.projects.first { $0.id == x.str("id") } }
        HStack(spacing: 12) {
            PersonTap(name: p.name) {
                PersonAvatar(name: p.name, size: 42).overlay(alignment: .bottomTrailing) {
                    if onToday { Circle().fill(GreenC).frame(width: 9, height: 9).padding(2).background(Circle().fill(Color.white)) }
                }
            }
            Button { if let id = e?.str("id"), place != nil { m.openProject(id) } } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text(p.name).font(.tSmall).foregroundColor(C.ink).lineLimit(1)
                    Text(e.map { x in doneVerb(x.str("a")) + " " + (place?.title ?? x.str("t")) + ((place?.area ?? "").isEmpty ? "" : " · " + (place?.area ?? "")) + " · " + ago(x.str("at")) } ?? t("Last seen ", "Alionekana ") + ago(p.lastSeen))
                        .font(.bSmall).foregroundColor(C.muted).lineLimit(2).multilineTextAlignment(.leading)
                }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
            }.buttonStyle(.plain)
            if !p.phone.isEmpty {
                RoundAction(icon: "phone", label: t("Call \(p.name)", "Mpigie \(p.name)"), hue: C.hue("near")) { Share.open("tel:" + p.phone.replacingOccurrences(of: " ", with: "")) }
                RoundAction(icon: "chat", label: "WhatsApp \(p.name)", hue: Hue(fg: Color(hex: 0x1DA851), bg: Color(hex: 0x25D366, alpha: 0.14))) { Share.open("https://wa.me/" + waNumber(p.phone, p.home)) }
            }
        }
    }
}

private struct RoundAction: View {
    let icon: String; let label: String; let hue: Hue; let action: () -> Void
    var body: some View {
        Button { Haptic.tap(); action() } label: { Icon(icon, size: 19, color: hue.fg).frame(width: 40, height: 40).background(hue.bg).clipShape(Circle()).frame(width: 44, height: 44) }
            .buttonStyle(Press()).accessibilityLabel(label)
    }
}

// MARK: - Team totals and feed

/// Friendly totals for each person, this week or this month. In name order: no ranking. A name opens their contact sheet.
struct TeamMonth: View {
    let act: [JSON]?
    @State private var month = true
    var body: some View {
        CardV {
            MeH2(t("Team totals", "Jumla ya timu"))
            SmallSeg(items: [("w", t("This week", "Wiki hii")), ("m", t("This month", "Mwezi huu"))], sel: month ? "m" : "w", fill: true) { month = $0 == "m" }.padding(.top, 4)
            if let act {
                let cal = Calendar.current, today = cal.startOfDay(for: Date())
                let from = month ? (cal.date(from: cal.dateComponents([.year, .month], from: today)) ?? today) : (cal.date(byAdding: .day, value: -((cal.component(.weekday, from: today) + 5) % 7), to: today) ?? today)
                let all = act.filter { WORK.contains($0.str("a")) && (dayOf($0.str("at")) ?? .distantPast) >= from }
                let rows = all.filter { !isCommunity($0) }
                let community = all.filter { isCommunity($0) }
                let names = Array(Set(rows.map { $0.str("by").trimmingCharacters(in: .whitespaces) })).sorted { $0.lowercased() < $1.lowercased() }
                let np = rows.filter { actKind($0.str("a")) == "pin" }.count, nv = rows.filter { $0.str("a") == "visit" }.count, nf = rows.filter { $0.str("a") == "fixed" }.count
                let en: String = "\(np) " + (np == 1 ? "pin" : "pins") + " · \(nv) " + (nv == 1 ? "visit" : "visits") + " · \(nf) fixed"
                HStack {
                    Text(t("Together", "Pamoja")).font(.tSmall).foregroundColor(C.deep)
                    Spacer(minLength: 6)
                    Text(t(en, "mahali \(np) · ziara \(nv) · \(nf) zimerekebishwa"))
                        .font(.lMedium).foregroundColor(C.deep).multilineTextAlignment(.trailing)
                }.padding(.horizontal, 14).padding(.vertical, 10).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).padding(.top, 12)
                if names.isEmpty && community.isEmpty { Text(t("Nothing yet in this period.", "Hakuna bado katika kipindi hiki.")).font(.bMedium).foregroundColor(C.muted).padding(.top, 10) }
                ForEach(names, id: \.self) { n in
                    Button { Contact.show(n) } label: { TotalsRow(name: n, list: rows.filter { same($0.str("by"), n) }, community: false) }.buttonStyle(.plain)
                }
                if !community.isEmpty { TotalsRow(name: communityName(), list: community, community: true) }
            } else { Text(t("Loading…", "Inapakia…")).font(.bMedium).foregroundColor(C.muted).padding(.top, 8) }
        }.padding(.top, 14)
    }
}

private struct TotalsRow: View {
    let name: String; let list: [JSON]; let community: Bool
    var body: some View {
        HStack(spacing: 6) {
            if community { Icon("phone", size: 16, color: C.muted) }
            Text(name).font(.tSmall).foregroundColor(community ? C.muted : C.ink).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
            ForEach([("pin", "pin", "pin"), ("visit", "near", "clip"), ("fixed", "repairs", "wrench")], id: \.0) { k, hue, icon in
                let hu = C.hue(hue)
                HStack(spacing: 4) { Icon(icon, size: 13, color: hu.fg); Text("\(list.filter { actKind($0.str("a")) == k }.count)").font(.mont(12, .bold)).foregroundColor(hu.fg) }
                    .padding(.horizontal, 9).padding(.vertical, 4).background(hu.bg).clipShape(Capsule())
            }
        }.frame(minHeight: 44).padding(.top, 4).contentShape(Rectangle())
    }
}

/// Everyone's latest pins, visits and fixes. Tap one to open the project, or the person for their contact sheet.
struct TeamFeed: View {
    @EnvironmentObject var m: AppModel
    let act: [JSON]?; let snap: Snapshot?
    @State private var more = false
    var body: some View {
        CardV {
            MeH2(t("Team feed", "Habari za timu"))
            if let act {
                let list = act.filter { WORK.contains($0.str("a")) || $0.str("a") == "job" }.sorted { $0.str("at") > $1.str("at") }
                if list.isEmpty {
                    VStack(spacing: 8) {
                        MeDrop(size: 64, color: .blue, mood: .smile)
                        Text(t("Nothing yet. Pins, visits and fixes show here.", "Bado hakuna. Mahali, ziara na marekebisho yataonekana hapa.")).font(.bMedium).foregroundColor(C.muted).multilineTextAlignment(.center)
                    }.frame(maxWidth: .infinity).padding(.top, 20).padding(.bottom, 6)
                }
                ForEach(Array(list.prefix(more ? 40 : 8).enumerated()), id: \.offset) { i, e in FeedRow(e: e, snap: snap).padding(.top, 6).rise(i, step: 0.04) }
                if list.count > 8 {
                    Button(more ? t("Show less", "Onyesha kidogo") : t("Show more", "Onyesha zaidi")) { withAnimation { more.toggle() } }.font(.lMedium).foregroundColor(C.blue).padding(.top, 10).frame(minHeight: 44)
                }
            } else { Text(t("Loading…", "Inapakia…")).font(.bMedium).foregroundColor(C.muted).frame(maxWidth: .infinity) }
        }.padding(.top, 14)
    }
}

private struct FeedRow: View {
    @EnvironmentObject var m: AppModel
    let e: JSON; let snap: Snapshot?
    private func restText(_ sms: Bool, _ p: Project?) -> String {
        var rest: String = sms ? ": " : " " + doneVerb(e.str("a")).lowercased() + " "
        rest += p?.title ?? e.str("t")
        if sms && e.str("a") == "fixed" { rest += " · " + t("working again", "inafanya kazi tena") }
        if e.str("a") == "visit" && !e.str("s").isEmpty { rest += " · " + stateName(e.str("s")) }
        return rest
    }
    var body: some View {
        let sms = isCommunity(e)
        let ic: (String, String) = sms ? ("phone", "slate") : { switch e.str("a") { case "pin": return ("pin", "pin"); case "visit": return ("clip", "near"); case "fixed": return ("check", "near"); case "new": return ("plus", "new"); default: return ("wrench", "repairs") } }()
        let hu = C.hue(ic.1), id = e.str("id"), p = snap?.projects.first { $0.id == id }
        let by = e.str("by").trimmingCharacters(in: .whitespaces)
        let who: String = sms ? communityName() : String(by.split(separator: " ").first ?? "")
        let rest = restText(sms, p)
        HStack(spacing: 10) {
            if sms || !Contact.isPerson(by) {
                Icon(ic.0, size: 17, color: hu.fg).frame(width: 34, height: 34).background(hu.bg).clipShape(Circle())
            } else {
                // the person (tap: their contact sheet) with what they did as a badge
                PersonTap(name: by) {
                    PersonAvatar(name: by, size: 34).overlay(alignment: .bottomTrailing) {
                        Icon(ic.0, size: 11, color: C.card).frame(width: 16, height: 16).background(Circle().fill(hu.fg)).padding(2).background(Circle().fill(C.card)).offset(x: 6, y: 6)
                    }
                }
            }
            Button { if p != nil { m.openProject(id) } } label: {
                HStack(spacing: 6) {
                    VStack(alignment: .leading, spacing: 2) {
                        (Text(who).bold() + Text(rest))
                            .font(.bMedium).foregroundColor(C.text).lineLimit(2).multilineTextAlignment(.leading)
                        Text(ago(e.str("at"))).font(.lSmall).foregroundColor(C.muted)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                    if p != nil { Icon("chev", size: 16, color: C.muted) }
                }.padding(.vertical, 6).contentShape(Rectangle())
            }.buttonStyle(.plain)
        }
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
                    IconTile(icon: "people", tint: C.hue("new").fg, bg: C.hue("new").bg, size: 40)
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

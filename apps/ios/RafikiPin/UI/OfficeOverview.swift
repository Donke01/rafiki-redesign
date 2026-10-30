import SwiftUI

// Office › Overview and Visits (Android OfficeOverview.kt): a compact head with a few numbers, tiles and cards you tap,
// sheets for the details and every action. Same server calls as before.

private func feedHue(_ a: String) -> String {
    switch a { case "pin", "approve", "assign": return "pin"; case "visit", "fixed": return "near"; case "new": return "new"; case "job", "request": return "repairs"; case "reject": return "me"; default: return "slate" }
}
private func feedIco(_ a: String) -> String {
    switch a {
    case "stage": return "build"; case "pin": return "pin"; case "visit": return "clip"; case "fixed": return "check"; case "new": return "plus"; case "undo": return "undo"
    case "job": return "wrench"; case "assign": return "cal"; case "team": return "people"; case "ask": return "hourglass"; case "settings": return "shield"; case "approve": return "check"
    default: return "drop"
    }
}
/// A report from the community by SMS or WhatsApp.
func officeIsCommunity(_ e: JSON) -> Bool { e.str("src") == "sms" || e.str("by").hasPrefix("SMS from") || e.str("by").hasPrefix("WhatsApp from") }
/// The feed's words for what someone did (with the newer actions).
func officeVerb(_ a: String) -> String {
    switch a {
    case "stage": return t("moved the build stage of", "alisogeza hatua ya ujenzi ya")
    case "ask": return t("asked for a second approval on", "aliomba idhini ya pili ya")
    case "settings": return t("changed a setting:", "alibadilisha mpangilio:")
    default: return verb(a)
    }
}

private struct OTile: Identifiable {
    let id: String; let icon: String; let hue: String; let n: Int?; let label: String; var sub = ""; var hot = false; var red = false; var up = false; let action: () -> Void
}

struct OfficeOverview: View {
    @EnvironmentObject var m: AppModel
    let s: Snapshot; let go: (String) -> Void
    @State private var people = false
    @State private var country: String?
    @State private var feedMore = false

    var body: some View {
        let pinned = s.projects.filter { $0.gps != nil }.count
        let missing = s.projects.count - pinned
        let pct = s.projects.isEmpty ? 0 : pinned * 100 / s.projects.count
        let due = s.projects.filter { $0.gps != nil && ageDays(s, $0) >= DUE_DAYS }.count
        VStack(alignment: .leading, spacing: 0) {
            OHead(icon: "drop", hue: "blue", title: t("Overview", "Muhtasari"), line: t("All projects today. Tap a number to see the list.", "Miradi yote leo. Gusa namba kuona orodha.")) {
                Button { if missing > 0 { Haptic.tap(); officeToList(m, "missing") } } label: {
                    HStack(spacing: 12) {
                        ORing(pct: pct)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(t("\(pinned) of \(s.projects.count) projects on the map", "Miradi \(pinned) kati ya \(s.projects.count) iko kwenye ramani")).font(.tSmall).foregroundColor(C.ink).multilineTextAlignment(.leading)
                            Text(missing == 0 ? t("Every project has a location", "Kila mradi una mahali") : missing == 1 ? t("1 still needs a location", "1 bado unahitaji mahali") : t("\(missing) still need a location", "\(missing) bado inahitaji mahali"))
                                .font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.leading)
                        }.frame(maxWidth: .infinity, alignment: .leading)
                        if missing > 0 { Icon("chev", size: 18, color: C.muted) }
                    }
                    .padding(.horizontal, 12).padding(.vertical, 8).frame(minHeight: 64).background(C.bg)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.line, lineWidth: oEdge))
                }.buttonStyle(Press()).padding(.top, 12).accessibilityIdentifier("show_missing")
            }
            if s.admin { OfficeStart(go: go) }
            OfficeTodo(s: s, due: due, go: go)
            tiles(due)
            health
            countries
            feed
            CardV(pad: 0) {
                ProjectsMap(dots: s.projects.compactMap { p in p.gps.map { Dot(id: p.id, ll: $0, color: dotColor(s, p), big: s.repairs[p.id] != nil, title: p.title) } }, satellite: false,
                            onDot: { m.go(Place(tab: "office", project: $0)) }, goMe: .constant(0))
                    .frame(height: 260).clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
            }.padding(.top, 12).rise(4)
            Text(t("Orange and red: open repairs. Green: other projects with a location.", "Machungwa na nyekundu: matengenezo yaliyo wazi. Kijani: miradi mingine yenye mahali.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 6)
        }
        .task { await m.loadActivity() }
        .sheet(isPresented: $people) {
            OSheet {
                HStack { Spacer(); OCloseX { people = false } }
                if let pe = m.sx.people { PeopleServedCard(p: pe).padding(.top, 4) }
            }
        }
        .sheet(item: Binding(get: { country.map { OItem(id: $0, o: [:]) } }, set: { country = $0?.id })) { x in
            OfficeCountrySheet(s: s, k: x.id) { country = nil }
        }
    }

    func tileList(_ due: Int) -> [OTile] {
        let urgent = s.repairs.values.filter { $0.urgency == "urgent" }.count
        let cost = s.repairs.values.reduce(0) { $0 + ($1.cost ?? 0) }
        let tests = testCount(m)
        let ms = monthStartUTC()
        let vm = m.activity.map { a in a.filter { $0.str("a") == "visit" && (When.parse($0.str("at")).map { $0 >= ms } ?? false) }.count }
        let nb = s.projects.filter { m.building($0) }.count
        let late = m.sx.stages.values.filter { $0.s != "handed" && $0.behind }.count
        let pe = m.sx.people.flatMap { $0.total > 0 ? $0 : nil }
        var list: [OTile] = [
            OTile(id: "repairs", icon: "wrench", hue: "amber", n: s.repairs.count, label: s.repairs.count == 1 ? t("open repair", "tengenezo lililo wazi") : t("open repairs", "matengenezo yaliyo wazi"),
                  sub: [urgent > 0 ? t("\(urgent) urgent", "\(urgent) ya haraka") : nil, cost > 0 ? Currency.kes(cost) : nil].compactMap { $0 }.joined(separator: " · "), hot: !s.repairs.isEmpty, red: urgent > 0) { go("repairs") },
            OTile(id: "review", icon: "inbox", hue: "purple", n: s.reviewOpen, label: s.reviewOpen == 1 ? t("waiting for your review", "inasubiri uidhinishe") : t("waiting for your review", "zinasubiri uidhinishe"), hot: s.reviewOpen > 0) { go("review") },
            OTile(id: "due", icon: "cal", hue: "blue", n: due, label: due == 1 ? t("due a visit", "unasubiri ziara") : t("due a visit", "zinasubiri ziara")) { officeToList(m, "due") },
            OTile(id: "tests", icon: "flask", hue: "teal", n: tests, label: tests == 1 ? t("water test due", "kipimo cha maji kinahitajika") : t("water tests due", "vipimo vya maji vinahitajika"), hot: tests > 0) { officeToList(m, "due") },
            OTile(id: "visits", icon: "clip", hue: "green", n: vm, label: vm == 1 ? t("visit this month", "ziara ya mwezi huu") : t("visits this month", "ziara za mwezi huu")) { go("visits") },
        ]
        if nb > 0 { list.append(OTile(id: "build", icon: "build", hue: "amber", n: nb, label: t("under construction", "inajengwa"), sub: late > 0 ? t("\(late) behind schedule", "\(late) imechelewa") : "", red: late > 0) { officeToList(m, "build") }) }
        if let pe { list.append(OTile(id: "people", icon: "people", hue: "teal", n: pe.total, label: t("people served", "watu wanaohudumiwa"), sub: pe.growth > 0 ? "+" + Geo.grouped(pe.growth) + " " + t("in 12 months", "kwa miezi 12") : "", up: true) { people = true }) }
        return list
    }

    @ViewBuilder func tiles(_ due: Int) -> some View {
        let list = tileList(due)
        let rows = stride(from: 0, to: list.count, by: 2).map { Array(list[$0..<min($0 + 2, list.count)]) }
        ForEach(rows.indices, id: \.self) { ri in
            HStack(alignment: .top, spacing: 10) {
                ForEach(Array(rows[ri].enumerated()), id: \.element.id) { ci, x in OverviewTile(x: x).rise(ri * 2 + ci + 1).accessibilityIdentifier("ocv_t_\(x.id)") }
                if rows[ri].count == 1 { Color.clear.frame(maxWidth: .infinity, minHeight: 1) }
            }.fixedSize(horizontal: false, vertical: true).padding(.top, 10)
        }
    }

    var health: some View {
        let st = Dictionary(grouping: s.visits.values, by: \.state).mapValues(\.count)
        let seen = st.values.reduce(0, +), unseen = max(0, s.projects.count - seen), tot = Double(max(1, s.projects.count))
        let parts: [(Int, Color, String)] = [(st["working"] ?? 0, GreenC, t("working", "zinafanya kazi")), (st["repair"] ?? 0, Amber, t("needed repair", "zilihitaji matengenezo")),
                                             (st["notworking"] ?? 0, RedC, t("not working", "hazifanyi kazi")), (unseen, C.off, t("not visited yet", "bado hazijatembelewa"))]
        return CardV(pad: 16) {
            HStack(alignment: .firstTextBaseline) {
                Text(t("Condition at last visit", "Hali katika ziara ya mwisho")).font(.tSmall).foregroundColor(C.ink).frame(maxWidth: .infinity, alignment: .leading)
                Text(t("\(seen) of \(s.projects.count) visited", "\(seen) kati ya \(s.projects.count) zimetembelewa")).font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.trailing)
            }
            GeometryReader { g in
                HStack(spacing: 2) {
                    ForEach(Array(parts.enumerated()), id: \.offset) { _, x in if x.0 > 0 { Rectangle().fill(x.1).frame(width: max(0, g.size.width * CGFloat(Double(x.0) / tot) - 2)) } }
                    Spacer(minLength: 0)
                }.background(C.line).clipShape(Capsule())
            }.frame(height: 12).padding(.vertical, 10)
            LazyVGrid(columns: [GridItem(.flexible(), alignment: .topLeading), GridItem(.flexible(), alignment: .topLeading)], spacing: 6) {
                ForEach(Array(parts.enumerated()), id: \.offset) { _, x in
                    HStack(alignment: .firstTextBaseline, spacing: 4) {
                        RoundedRectangle(cornerRadius: 3).fill(x.1).frame(width: 10, height: 10)
                        Text("\(x.0)").font(.tSmall).foregroundColor(C.ink)
                        Text(x.2).font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
        }.padding(.top, 12)
    }

    var countries: some View {
        CardV(pad: 16) {
            Text(t("By country", "Kwa nchi")).font(.tSmall).foregroundColor(C.ink)
            let present = COUNTRIES.filter { c in s.projects.contains { $0.ck == c.0 } }
            ForEach(Array(present.enumerated()), id: \.element.0) { i, c in
                let l = s.projects.filter { $0.ck == c.0 }
                let loc = l.filter { $0.gps != nil }.count, rp = l.filter { s.repairs[$0.id] != nil }.count, du = l.filter { $0.gps != nil && ageDays(s, $0) >= DUE_DAYS }.count
                if i > 0 { ODivider() }
                Button { Haptic.tap(); country = c.0 } label: {
                    HStack(spacing: 12) {
                        Text(COUNTRY_FLAG[c.0] ?? "").font(.system(size: 21)).frame(width: 42, height: 42).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
                        VStack(alignment: .leading, spacing: 0) {
                            HStack(alignment: .firstTextBaseline) {
                                Text(c.1).font(.tSmall).foregroundColor(C.ink).frame(maxWidth: .infinity, alignment: .leading)
                                Text(t("\(loc) of \(l.count) mapped", "\(loc) kati ya \(l.count) zina mahali")).font(.bSmall).foregroundColor(C.muted).lineLimit(1)
                            }
                            OBar(f: Double(loc) / Double(max(1, l.count))).padding(.vertical, 6)
                            if rp > 0 { OPill(rp == 1 ? t("1 open repair", "matengenezo 1") : t("\(rp) open repairs", "matengenezo \(rp)"), "warn") }
                            else if du > 0 { OPill(t("\(du) due a visit", "\(du) zinasubiri ziara"), "has") }
                            else { OPill(t("Nothing needs attention", "Hakuna kinachohitaji kushughulikiwa"), "ok") }
                        }
                        Icon("chev", size: 18, color: C.muted)
                    }.padding(.vertical, 10).frame(minHeight: 72).contentShape(Rectangle())
                }.buttonStyle(.plain).accessibilityIdentifier("ocv_c_\(c.0)")
            }
        }.padding(.top, 12)
    }

    var feed: some View {
        CardV(pad: 16) {
            Text(t("Latest from the field", "Habari za karibuni kutoka uwanjani")).font(.tSmall).foregroundColor(C.ink)
            if let a = m.activity {
                if a.isEmpty { Text(t("Nothing yet.", "Bado hakuna kitu.")).foregroundColor(C.muted).padding(.top, 8) }
                let all = Array(a.suffix(12).reversed())
                let list = feedMore ? all : Array(all.prefix(5))
                ForEach(Array(list.enumerated()), id: \.offset) { i, e in
                    if i > 0 { ODivider() }
                    FeedRow(s: s, e: e)
                }
                if !feedMore && all.count > 5 { OMore(n: all.count - 5) { feedMore = true } }
            } else { Text(t("Loading…", "Inapakia…")).foregroundColor(C.muted).padding(.top, 8) }
        }.padding(.top, 12)
    }
}

private func monthStartUTC() -> Date {
    var cal = Calendar(identifier: .gregorian); cal.timeZone = TimeZone(identifier: "UTC") ?? .current
    return cal.date(from: cal.dateComponents([.year, .month], from: Date())) ?? Date()
}

private struct FeedRow: View {
    @EnvironmentObject var m: AppModel
    let s: Snapshot; let e: JSON
    var body: some View {
        let id = e.str("id"); let known = !id.isEmpty && s.projects.contains { $0.id == id }
        let sms = officeIsCommunity(e)
        let hu = ohue(sms ? "slate" : feedHue(e.str("a")))
        let who = sms ? t("Community report", "Ripoti ya jamii") : e.str("by")
        let rest: String = {
            if !sms { return " " + officeVerb(e.str("a")) + " " + e.str("t") }
            var x = ": " + e.str("t")
            if e.str("a") == "fixed" { x += " · " + t("working again", "inafanya kazi tena") } else if e.str("a") == "visit", !e.str("s").isEmpty { x += " · " + stateName(e.str("s")) }
            return x
        }()
        HStack(spacing: 12) {
            if sms {
                OIcon("phone", size: 16, color: hu.fg).frame(width: 34, height: 34).background(hu.bg).clipShape(Circle())
            } else {
                Button { officeOpenPerson(e.str("by")) } label: {
                    OAvatar(name: e.str("by"), size: 34).overlay(alignment: .bottomTrailing) {
                        OIcon(feedIco(e.str("a")), size: 11, color: hu.fg).frame(width: 17, height: 17).background(hu.bg).clipShape(Circle()).overlay(Circle().stroke(C.card, lineWidth: 2)).offset(x: 4, y: 4)
                    }
                }.buttonStyle(.plain)
            }
            Button { if known { m.go(Place(tab: "office", project: id)) } } label: {
                HStack(spacing: 8) {
                    VStack(alignment: .leading, spacing: 2) {
                        (Text(who).font(.mont(14, .bold)).foregroundColor(C.ink) + Text(rest).font(.bMedium).foregroundColor(C.text)).multilineTextAlignment(.leading)
                        Text(ODates.ago(e.str("at"))).font(.bSmall).foregroundColor(C.muted)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                    if known { Icon("chev", size: 18, color: C.muted) }
                }.contentShape(Rectangle())
            }.buttonStyle(.plain).disabled(!known)
        }.padding(.vertical, 10).frame(minHeight: 56)
    }
}

private struct OverviewTile: View {
    let x: OTile
    @State private var pulse = false
    var body: some View {
        let hu = ohue(x.hue)
        Button { Haptic.tap(); x.action() } label: {
            VStack(alignment: .leading, spacing: 0) {
                OTileIcon(icon: x.icon, tint: x.red ? C.bad : x.hot ? C.warn : hu.fg, bg: x.red ? C.badBg : x.hot ? C.warnBg : hu.bg, size: 38)
                if let n = x.n { CountUp(value: n, font: .mont(26, .heavy), color: C.ink).padding(.top, 8) } else { OSkel().padding(.top, 12).padding(.bottom, 4) }
                Spacer(minLength: 0)
                HStack(alignment: .bottom, spacing: 4) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(x.label).font(.lMedium).foregroundColor(C.muted).multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
                        if !x.sub.isEmpty { Text(x.sub).font(.mont(12, x.up ? .bold : .regular)).foregroundColor(x.up ? C.ok : C.muted).multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true) }
                    }.frame(maxWidth: .infinity, alignment: .leading)
                    Icon("chev", size: 16, color: C.muted)
                }
            }
            .padding(14).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading).frame(minHeight: 128)
            .background(C.card).clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(x.red ? C.bad : x.hot ? C.warn : C.line, lineWidth: x.hot || x.red || oSun ? 2 : 1))
            .overlay(alignment: .topTrailing) {
                if x.red {
                    ZStack {
                        Circle().fill(RedC.opacity(pulse ? 0 : 0.35)).frame(width: pulse ? 22 : 10, height: pulse ? 22 : 10)
                        Circle().fill(RedC).frame(width: 10, height: 10)
                    }.frame(width: 22, height: 22).padding(12)
                    .onAppear { if Motion.on { withAnimation(.easeOut(duration: 1.4).repeatForever(autoreverses: false)) { pulse = true } } }
                }
            }
        }.buttonStyle(Press())
    }
}

/// A country: what's going on there, and buttons that open the right list.
struct OfficeCountrySheet: View {
    @EnvironmentObject var m: AppModel
    let s: Snapshot; let k: String; let close: () -> Void
    var body: some View {
        let l = s.projects.filter { $0.ck == k }
        let ks: (String) -> Int = { st in l.filter { s.visits[$0.id]?.state == st }.count }
        let loc = l.filter { $0.gps != nil }.count, rp = l.filter { s.repairs[$0.id] != nil }.count, du = l.filter { $0.gps != nil && ageDays(s, $0) >= DUE_DAYS }.count
        OSheet {
            OSheetHead(label: l.count == 1 ? t("1 project", "Mradi 1") : t("\(l.count) projects", "Miradi \(l.count)"), labelColor: C.blue2, title: countryName(k) ?? k, lead: {
                Text(COUNTRY_FLAG[k] ?? "").font(.system(size: 26)).frame(width: 52, height: 52).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            }, close: close)
            OBar(f: Double(loc) / Double(max(1, l.count)), height: 8).padding(.top, 16).padding(.bottom, 4)
            OFact(label: t("On the map", "Kwenye ramani"), value: t("\(loc) of \(l.count)", "\(loc) kati ya \(l.count)"))
            OFact(label: t("Working at the last visit", "Zinafanya kazi, ziara ya mwisho"), value: "\(ks("working"))", color: ks("working") > 0 ? C.ok : C.ink)
            OFact(label: t("Needed repair", "Zilihitaji matengenezo"), value: "\(ks("repair"))", color: ks("repair") > 0 ? C.warn : C.ink)
            OFact(label: t("Not working", "Hazifanyi kazi"), value: "\(ks("notworking"))", color: ks("notworking") > 0 ? C.bad : C.ink)
            OFact(label: t("Open repairs", "Matengenezo yaliyo wazi"), value: "\(rp)", color: rp > 0 ? C.warn : C.ink)
            OFact(label: t("Due a visit", "Zinasubiri ziara"), value: "\(du)", color: du > 0 ? C.blue2 : C.ink)
            Btn(t("Show these projects", "Onyesha miradi hii"), icon: "arrow", wide: true) { close(); officeToList(m, "all", ck: k) }.padding(.top, 16)
            if l.count - loc > 0 { Btn(t("Need a location", "Zinahitaji mahali") + " (\(l.count - loc))", icon: "pin", light: true, wide: true) { close(); officeToList(m, "missing", ck: k) }.padding(.top, 8) }
            if du > 0 { Btn(t("Due a visit", "Zinasubiri ziara") + " (\(du))", icon: "cal", light: true, wide: true) { close(); officeToList(m, "due", ck: k) }.padding(.top, 8) }
            if rp > 0 { Btn(t("Open repairs", "Matengenezo yaliyo wazi") + " (\(rp))", icon: "wrench", light: true, wide: true) { close(); officeToList(m, "repairs", ck: k) }.padding(.top, 8) }
        }
    }
}

// --- Visits -----------------------------------------------------------------------------------------------------

private func visitState(_ a: JSON, _ today: String) -> String {
    let done = a.str("done"); if !done.isEmpty && done != "null" { return "done" }
    let d = a.str("date"); return d < today ? "late" : d == today ? "today" : "soon"
}
private func visitStatus(_ st: String) -> (String, String, String) {
    switch st {
    case "late": return (t("Overdue", "Imechelewa"), "bad", "alert"); case "today": return (t("Today", "Leo"), "warn", "cal")
    case "done": return (t("Done", "Imefanyika"), "ok", "check"); default: return (t("Planned", "Imepangwa"), "has", "cal")
    }
}

/// Office › Visits: the assigned visits as cards with filters (status and person); the assign form and each visit's
/// actions (reassign, cancel, open the project) live in sheets.
struct OfficeVisits: View {
    @EnvironmentObject var m: AppModel
    let s: Snapshot
    @State private var team: [String] = []
    @State private var items: [JSON]?
    @State private var err: String?
    @State private var vf = "open"
    @State private var vp = ""
    @State private var shown = 30
    @State private var picked: OItem?
    @State private var assigning = false

    func load() async {
        let r = await m.api.adminGet("assign")
        if r.ok { items = r.json.objs("items"); err = nil } else { err = reasonText(r.reason) }
    }

    var body: some View {
        Group {
            if let all = items { content(all) } else { Text(err ?? t("Loading…", "Inapakia…")).foregroundColor(err != nil ? C.bad : C.muted) }
        }
        .task {
            team = await m.api.adminGet("team").json.objs("team").map { $0.str("name") }.filter { !$0.isEmpty }.sorted()
            await load()
        }
        .sheet(item: $picked) { a in OfficeVisitSheet(s: s, a: a.o, team: team, close: { picked = nil }) { Task { await load() } } }
        .sheet(isPresented: $assigning) { OfficeAssignSheet(s: s, team: team, pre: "", close: { assigning = false }) { vf = "open"; vp = ""; Task { await load() } } }
    }

    @ViewBuilder func content(_ all: [JSON]) -> some View {
        let today = ODates.today()
        let inF: (JSON, String) -> Bool = { a, f in let st = visitState(a, today); return f == "open" ? st != "done" : st == f }
        let byP: (JSON) -> Bool = { a in vp.isEmpty || a.str("to") == vp }
        let fs = [("open", t("Planned", "Zimepangwa")), ("today", t("Today", "Leo")), ("late", t("Overdue", "Zimechelewa")), ("done", t("Done", "Zimefanyika"))]
        let cnt: [String: Int] = Dictionary(uniqueKeysWithValues: fs.map { k, _ in (k, all.filter { inF($0, k) && byP($0) }.count) })
        let filtered = all.filter { inF($0, vf) && byP($0) }
        let list = vf == "done" ? filtered.sorted { $0.str("done") > $1.str("done") } : filtered.sorted { ($0.str("date"), $0.str("at")) < ($1.str("date"), $1.str("at")) }
        OHead(icon: "cal", hue: "blue", title: t("Visits", "Ziara"), line: t("Site visits given to the field team. Tap one to change it.", "Ziara walizopewa timu ya uwanjani. Gusa moja kuibadilisha.")) {
            ONums(items: [
                ONum(value: "\(cnt["open"] ?? 0)", label: t("planned", "zimepangwa"), on: vf == "open") { vf = "open"; shown = 30 },
                ONum(value: "\(cnt["today"] ?? 0)", label: t("today", "leo"), color: (cnt["today"] ?? 0) > 0 ? C.warn : C.ink, on: vf == "today") { vf = "today"; shown = 30 },
                ONum(value: "\(cnt["late"] ?? 0)", label: t("overdue", "zimechelewa"), color: (cnt["late"] ?? 0) > 0 ? C.bad : C.ink, on: vf == "late") { vf = "late"; shown = 30 },
            ])
            Btn(t("Assign a visit", "Gawa ziara"), icon: "plus", wide: true) { assigning = true }.padding(.top, 12).accessibilityIdentifier("ocv_assign")
        }
        if !all.isEmpty {
            OChips(items: fs.map { ($0.0, $0.1, cnt[$0.0] ?? 0) }, picked: vf) { vf = $0; shown = 30 }
            let ppl = Dictionary(grouping: all.filter { inF($0, vf) }, by: { $0.str("to") }).mapValues(\.count)
            let who = (ppl.keys + (vp.isEmpty || ppl[vp] != nil ? [] : [vp])).sorted { (ppl[$0] ?? 0, $1) > (ppl[$1] ?? 0, $0) }
            if who.count > 1 || !vp.isEmpty {
                OChips(items: [("", t("Everyone", "Wote"), all.filter { inF($0, vf) }.count)] + who.map { ($0, $0, ppl[$0] ?? 0) }, picked: vp) { vp = $0; shown = 30 }
            }
        }
        if all.isEmpty { OEmpty(t("No visits assigned yet. Tap “Assign a visit” to give one to the field team.", "Bado hakuna ziara zilizogawiwa. Gusa “Gawa ziara” kumpa mtu wa timu.")) }
        else if list.isEmpty {
            OEmpty(!vp.isEmpty ? t("Nothing here for \(vp). Try another filter.", "Hakuna kitu hapa kwa \(vp). Jaribu kichujio kingine.") :
                    vf == "today" ? t("Nothing planned for today.", "Hakuna kilichopangwa leo.") : vf == "late" ? t("Nothing overdue. Well done!", "Hakuna iliyochelewa. Hongera!") :
                    vf == "done" ? t("No visits done in the last 4 months.", "Hakuna ziara zilizofanyika katika miezi 4 iliyopita.") : t("No visits planned. Tap “Assign a visit” to give one to the field team.", "Hakuna ziara zilizopangwa. Gusa “Gawa ziara” kumpa mtu wa timu."))
        }
        ForEach(Array(list.prefix(shown).enumerated()), id: \.offset) { i, a in
            let st = visitState(a, today); let (label, tone, _) = visitStatus(st)
            let to = a.str("to"); let note = a.str("note") == "null" ? "" : a.str("note")
            let right = st == "done" ? ODates.ago(a.str("done")) : ODates.dayText(a.str("date"), today)
            let rel = st == "done" ? "" : ODates.relText(a.str("date"), today)
            let title = a.str("t").isEmpty ? (s.projects.first { $0.id == a.str("id") }?.title ?? a.str("id")) : a.str("t")
            OListCard(title: title, right: right, pill: (label, tone), lines: [to + (!rel.isEmpty && rel != right ? " · \(rel)" : ""), note.isEmpty ? "" : "“\(note)”"], italicLast: true, index: i,
                      lead: { OAvatar(name: to) }) { picked = OItem(id: a.str("aid") + "\(i)", o: a) }
        }
        if list.count > shown { OMore(n: list.count - shown) { shown += 30 } }
    }
}

/// One assigned visit: the details, and reassign, cancel or open the project.
struct OfficeVisitSheet: View {
    @EnvironmentObject var m: AppModel
    let s: Snapshot; let a: JSON; let team: [String]; let close: () -> Void; let changed: () -> Void
    @State private var re = false
    @State private var ask = false
    @State private var to = ""
    @State private var date = ""
    @State private var msg: (String, String)?
    @State private var busy = false
    var body: some View {
        let today = ODates.today()
        let st = visitState(a, today); let (label, tone, icon) = visitStatus(st); let (fg, bg) = otone(tone)
        let id = a.str("id"); let p = s.projects.first { $0.id == id }
        let title = a.str("t").isEmpty ? (p?.title ?? id) : a.str("t")
        let extra = st == "late" ? " · " + ODates.lateShort(a.str("date"), today) : st == "soon" ? " · " + ODates.relText(a.str("date"), today) : ""
        OSheet {
            OSheetHead(label: label + extra, labelColor: fg, title: title, lead: { OTileIcon(icon: icon, tint: fg, bg: bg, size: 52) }, close: close)
            VStack(spacing: 0) {
                OFact(label: t("Field officer", "Afisa wa uwanjani"), value: a.str("to"))
                OFact(label: t("Date", "Tarehe"), value: ODates.parseDay(a.str("date")).map { ODates.fmt($0, "EEEE d MMMM yyyy") } ?? a.str("date"))
                if !a.str("note").isEmpty && a.str("note") != "null" { OFact(label: t("Note", "Maelezo"), value: a.str("note")) }
                if !a.str("by").isEmpty { OFact(label: t("Assigned by", "Aliyegawa"), value: a.str("by"), sub: a.str("at").isEmpty ? "" : When.text(a.str("at"))) }
                if st == "done" { OFact(label: t("Done", "Imefanyika"), value: When.text(a.str("done")), color: C.ok) }
            }.padding(.top, 10)
            if p != nil { Btn(t("Open project", "Fungua mradi"), icon: "arrow", wide: true) { close(); m.go(Place(tab: "office", project: id)) }.padding(.top, 16) }
            if st != "done" {
                HStack(spacing: 8) {
                    Btn(t("Reassign", "Mpe mwingine"), icon: "people", light: true, small: true, wide: true) { withAnimation { re.toggle(); ask = false; msg = nil } }
                    Btn(t("Cancel visit", "Ghairi ziara"), icon: "trash", light: true, small: true, wide: true) { withAnimation { ask = true; re = false; msg = nil } }
                }.padding(.top, 8)
                if re { reassign(id) }
                if ask {
                    OAsk(question: t("Cancel this visit? It comes off \(a.str("to"))’s list.", "Ghairi ziara hii? Itaondolewa kwenye orodha ya \(a.str("to"))."), yes: t("Cancel visit", "Ghairi ziara"),
                         no: t("Keep it", "Iache"), busy: busy, onNo: { ask = false }) {
                        busy = true
                        Task {
                            let x = await m.api.admin(["action": "unassign", "aid": a.str("aid")])
                            busy = false
                            if x.ok { msg = ("ok", t("Cancelled ✓", "Imeghairiwa ✓")); changed(); try? await Task.sleep(nanoseconds: 600_000_000); close() } else { msg = ("bad", reasonText(x.reason)) }
                        }
                    }
                }
            }
            if case let (tn, text)? = msg { Msg(text, tn) }
        }
        .onAppear { if to.isEmpty { to = a.str("to"); date = a.str("date") } }
    }

    func reassign(_ id: String) -> some View {
        let names = (to.isEmpty || team.contains(to) ? [] : [to]) + team
        return VStack(alignment: .leading, spacing: 0) {
            Picker1(label: t("Field officer", "Afisa wa uwanjani"), value: to, options: names.map { ($0, $0) }) { to = $0 }
            DateField(label: t("Date", "Tarehe"), value: date) { date = $0 }
            Btn(t("Save the change", "Hifadhi badiliko"), icon: "check", enabled: !busy && !to.isEmpty && !date.isEmpty, wide: true) {
                if to == a.str("to") && date == a.str("date") { msg = ("warn", t("Nothing changed yet: choose another person or date.", "Hakuna kilichobadilika: chagua mtu au tarehe nyingine.")); return }
                busy = true; msg = ("", t("Saving…", "Inahifadhi…"))
                Task {
                    let note = a.str("note") == "null" ? "" : a.str("note")
                    let x = await m.api.admin(["action": "assign", "ids": [id], "to": to, "date": date, "note": note])
                    if !x.ok { busy = false; msg = ("bad", reasonText(x.reason)); return }
                    _ = await m.api.admin(["action": "unassign", "aid": a.str("aid")])
                    msg = ("ok", t("Changed ✓ \(to) will see it in their app.", "Imebadilishwa ✓ \(to) ataiona kwenye programu yake.")); changed()
                    try? await Task.sleep(nanoseconds: 800_000_000); close()
                }
            }.padding(.top, 14)
        }
        .padding(.horizontal, 14).padding(.bottom, 14).background(ohue("slate").bg).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).padding(.top, 12)
    }
}

/// Assign a visit: who, when, a note, and which projects.
struct OfficeAssignSheet: View {
    @EnvironmentObject var m: AppModel
    let s: Snapshot; let team: [String]; let pre: String; let close: () -> Void; let done: () -> Void
    @State private var show = "due"
    @State private var ck = ""
    @State private var q = ""
    @State private var picked: [String] = []
    @State private var to = ""
    @State private var date = ODates.addDays(1)
    @State private var note = ""
    @State private var msg: (String, String)?
    @State private var busy = false
    var body: some View {
        let bl = ohue("blue")
        let rows = Array(s.projects.filter { p in
            p.gps != nil && (ck.isEmpty || p.ck == ck) && (q.trimmingCharacters(in: .whitespaces).isEmpty || (p.title + " " + p.area).localizedCaseInsensitiveContains(q.trimmingCharacters(in: .whitespaces)))
                && (show == "due" ? ageDays(s, p) >= DUE_DAYS : show == "repairs" ? s.repairs[p.id] != nil : true)
        }.sorted { show == "due" ? ageDays(s, $0) > ageDays(s, $1) : false }.prefix(80))
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    OSheetHead(label: t("They see it in their app", "Ataiona kwenye programu yake"), title: t("Assign a visit", "Gawa ziara"), lead: { OTileIcon(icon: "cal", tint: bl.fg, bg: bl.bg, size: 52) }, close: close)
                    if team.isEmpty { Msg(t("Add people in the Team tab first.", "Ongeza watu kwenye Timu kwanza."), "warn") }
                    else { Picker1(label: t("Field officer", "Afisa wa uwanjani"), value: to, options: team.map { ($0, $0) }) { to = $0 } }
                    DateField(label: t("Date", "Tarehe"), value: date) { date = $0 }
                    Field(t("Note (optional)", "Maelezo (si lazima)"), $note, placeholder: t("e.g. check the pump and meet the committee", "mf. kagua pampu na ukutane na kamati"))
                    OLabel(t("Which projects?", "Miradi ipi?"))
                    Picker1(label: t("Show", "Onyesha"), value: show, options: [("due", t("Due a visit", "Zinasubiri ziara")), ("repairs", t("Open repairs", "Matengenezo yaliyo wazi")), ("all", t("All with a location", "Zote zenye mahali"))]) { show = $0 }
                    Picker1(label: t("Country", "Nchi"), value: ck, options: [("", t("All", "Zote"))] + COUNTRIES) { ck = $0 }
                    Field(t("Search projects", "Tafuta miradi"), $q, placeholder: t("Name or area", "Jina au eneo"))
                    ForEach(rows) { p in
                        let on = picked.contains(p.id)
                        Button { if on { picked.removeAll { $0 == p.id } } else { picked.append(p.id) } } label: {
                            HStack(spacing: 8) {
                                Image(systemName: on ? "checkmark.square.fill" : "square").font(.system(size: 22)).foregroundColor(on ? C.blue : C.muted)
                                VStack(alignment: .leading, spacing: 2) { Text(p.title).font(.tSmall).foregroundColor(C.text).multilineTextAlignment(.leading); Text(p.sub).font(.bSmall).foregroundColor(C.muted) }
                                Spacer(minLength: 0)
                            }
                            .padding(8).frame(minHeight: 48).background(on ? C.tint : C.card).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(on ? C.blue : C.line, lineWidth: 1))
                        }.buttonStyle(.plain).padding(.top, 6)
                    }
                    if rows.isEmpty { Text(t("No projects match.", "Hakuna mradi unaolingana.")).foregroundColor(C.muted).padding(.top, 8) }
                }.padding(.horizontal, 20).padding(.top, 22).padding(.bottom, 12)
            }
            ODivider()
            VStack(spacing: 0) {
                Btn(picked.isEmpty ? t("Assign", "Gawa") : picked.count == 1 ? t("Assign 1 visit", "Gawa ziara 1") : t("Assign \(picked.count) visits", "Gawa ziara \(picked.count)"), icon: "cal",
                    enabled: !busy && !picked.isEmpty && !to.isEmpty && !date.isEmpty, wide: true, busy: busy ? t("Assigning…", "Inagawa…") : nil) {
                    busy = true; msg = nil
                    Task {
                        let n = picked.count
                        let x = await m.api.admin(["action": "assign", "ids": picked, "to": to, "date": date, "note": note])
                        busy = false
                        if !x.ok { msg = ("bad", reasonText(x.reason)); return }
                        msg = ("ok", n == 1 ? t("Assigned ✓ \(to) will see it in their app.", "Imegawiwa ✓ \(to) ataiona kwenye programu yake.") : t("Assigned ✓ \(to) will see them in their app.", "Imegawiwa ✓ \(to) ataziona kwenye programu yake."))
                        picked = []; note = ""; done()
                        try? await Task.sleep(nanoseconds: 900_000_000); close()
                    }
                }
                if case let (tn, text)? = msg { Msg(text, tn) }
            }.padding(.horizontal, 20).padding(.top, 12).padding(.bottom, 20)
        }
        .storySheet()
        .onAppear { if to.isEmpty { to = pre.isEmpty ? (team.first ?? "") : pre } }
    }
}

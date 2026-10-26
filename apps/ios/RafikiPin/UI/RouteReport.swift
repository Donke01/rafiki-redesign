import SwiftUI
import UIKit

/// Nearest stop next, starting from where you are (or the first chosen stop).
func orderStops(_ stops: [Project], from: LatLng?) -> [Project] {
    var left = stops.filter { $0.gps != nil }; var out: [Project] = []
    guard var at = from ?? left.first?.gps else { return out }
    while !left.isEmpty {
        let i = left.indices.min { Geo.metres(at, left[$0].gps!) < Geo.metres(at, left[$1].gps!) }!
        let n = left.remove(at: i); out.append(n); at = n.gps!
    }
    return out
}

/// Google Maps takes up to 9 stops on the way, so long routes are split into parts of 10.
func mapsLegs(_ stops: [Project], from: LatLng?) -> [(String, String)] {
    var out: [(String, String)] = []; var origin = from; var i = 0
    func ll(_ p: LatLng) -> String { String(format: "%.6f,%.6f", p.lat, p.lng) }
    while i < stops.count {
        let part = Array(stops[i..<min(i + 10, stops.count)]); let dest = part.last!.gps!
        let way = part.dropLast().map { ll($0.gps!) }.joined(separator: "|")
        let url = "https://www.google.com/maps/dir/?api=1" + (origin.map { "&origin=" + ll($0) } ?? "") + "&destination=" + ll(dest)
            + (way.isEmpty ? "" : "&waypoints=" + Api.enc(way)) + "&travelmode=driving"
        let label = stops.count > 10 ? t("Part \(out.count + 1): stops \(i + 1)–\(i + part.count) in Google Maps", "Sehemu \(out.count + 1): vituo \(i + 1)–\(i + part.count) kwenye Google Maps") : t("Start in Google Maps", "Anza kwenye Google Maps")
        out.append((label, url)); origin = dest; i += 10
    }
    return out
}

struct RouteView: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var loc = Locator.shared
    @State private var picked: [String] = []
    @State private var choosing = true
    @State private var show = "due"
    @State private var ck = ""
    @State private var ready = false
    func save() { m.prefs.route = picked.joined(separator: ",") }

    var body: some View {
        if let snap = m.snap {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    BackLink(label: t("Projects", "Miradi")) { m.back() }
                    Text(t("Today's route", "Njia ya leo")).font(.hLarge).foregroundColor(C.ink)
                    if choosing { chooser(snap) } else { plan(snap) }
                    Spacer().frame(height: 24)
                }.padding(16)
            }
            .onAppear {
                if !ready { ready = true; ck = m.prefs.country; picked = m.prefs.route.split(separator: ",").map(String.init).filter { !$0.isEmpty }; choosing = picked.isEmpty }
            }
        }
    }

    @ViewBuilder func chooser(_ snap: Snapshot) -> some View {
        Text(t("Tick the projects to visit. The app puts them in order, nearest first.", "Weka alama kwenye miradi utakayotembelea. Programu itaipanga, iliyo karibu kwanza.")).font(.bMedium).foregroundColor(C.muted)
        HStack(alignment: .bottom, spacing: 10) {
            Picker1(label: t("Show", "Onyesha"), value: show, options: [("due", t("Due a visit", "Inasubiri ziara")), ("repairs", t("Open repairs", "Matengenezo yaliyo wazi")), ("all", t("All with a location", "Zote zenye mahali"))]) { show = $0 }
            Picker1(label: t("Country", "Nchi"), value: ck, options: [("", t("All", "Yote"))] + COUNTRIES) { ck = $0 }
        }
        let rows0 = snap.projects.filter { p in
            p.gps != nil && (ck.isEmpty || p.ck == ck) && (show == "due" ? ageDays(snap, p) >= DUE_DAYS : show == "repairs" ? snap.repairs[p.id] != nil : true)
        }
        let rows = Array((loc.fix.map { f in rows0.sorted { Geo.metres(f.ll, $0.gps!) < Geo.metres(f.ll, $1.gps!) } } ?? rows0).prefix(80))
        ForEach(rows) { p in
            let on = picked.contains(p.id)
            Button { if on { picked.removeAll { $0 == p.id } } else { picked.append(p.id) }; save() } label: {
                HStack(spacing: 8) {
                    Image(systemName: on ? "checkmark.square.fill" : "square").font(.system(size: 22)).foregroundColor(on ? C.blue : C.muted)
                    VStack(alignment: .leading, spacing: 2) { Text(p.title).font(.tSmall).foregroundColor(C.text); Text(p.sub).font(.bSmall).foregroundColor(C.muted) }
                    Spacer(minLength: 4)
                    if let f = loc.fix { Tag(Geo.dist(Geo.metres(f.ll, p.gps!), miles: m.miles), bg: C.tint, fg: C.deep) }
                }
                .padding(8).background(on ? C.tint : C.card).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(on ? C.blue : C.line, lineWidth: 1))
            }.buttonStyle(.plain).padding(.top, 6)
        }
        Btn(picked.isEmpty ? t("Choose pinned projects to visit", "Chagua miradi yenye mahali utakayotembelea") : t("Plan route (\(picked.count))", "Panga njia (\(picked.count))"), icon: "map", enabled: !picked.isEmpty, wide: true) { choosing = false }.padding(.top, 14)
    }

    @ViewBuilder func plan(_ snap: Snapshot) -> some View {
        let stops = orderStops(snap.projects.filter { picked.contains($0.id) }, from: loc.fix?.ll)
        let legs: [(Project, Double?)] = {
            var at = loc.fix?.ll; var out: [(Project, Double?)] = []
            for p in stops { out.append((p, at.map { Geo.metres($0, p.gps!) })); at = p.gps }
            return out
        }()
        let total = legs.compactMap(\.1).reduce(0, +)
        Text(t("\(stops.count) stops · about \(Geo.dist(total, miles: m.miles)) in straight lines" + (loc.fix != nil ? " from where you are" : "") + ". Roads will be longer. Saved on this phone, so it works offline.",
               "Vituo \(stops.count) · takriban \(Geo.dist(total, miles: m.miles)) kwa mstari ulionyooka" + (loc.fix != nil ? " kutoka ulipo" : "") + ". Barabara zitakuwa ndefu zaidi. Imehifadhiwa kwenye simu, kwa hiyo inafanya kazi bila mtandao."))
            .font(.bMedium).foregroundColor(C.muted)
        ForEach(Array(mapsLegs(stops, from: loc.fix?.ll).enumerated()), id: \.offset) { _, leg in
            Btn(leg.0, icon: "b:gmaps", wide: true) { Share.open(leg.1) }.padding(.top, 10)
        }
        ForEach(Array(legs.enumerated()), id: \.offset) { i, leg in
            Button { m.go(Place(tab: m.here.tab, project: leg.0.id)) } label: {
                HStack(spacing: 12) {
                    Text("\(i + 1)").font(.tSmall).foregroundColor(.white).frame(width: 34, height: 34).background(BlueBrush).clipShape(Circle())
                    VStack(alignment: .leading, spacing: 2) {
                        Text(leg.0.title).font(.tSmall).foregroundColor(C.text)
                        Text(leg.0.area + (leg.1.map { " · " + t("\(Geo.dist($0, miles: m.miles)) from the last stop", "\(Geo.dist($0, miles: m.miles)) kutoka kituo cha mwisho") } ?? "")).font(.bSmall).foregroundColor(C.muted)
                    }
                    Spacer(); Icon("chev", size: 20, color: C.muted)
                }
                .padding(12).background(C.card).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.line, lineWidth: 1))
            }.buttonStyle(.plain).padding(.top, 10)
        }
        HStack(spacing: 10) {
            Btn(t("Change stops", "Badilisha vituo"), light: true, small: true) { choosing = true }
            Btn(t("Clear route", "Futa njia"), light: true, small: true) { picked = []; save(); choosing = true }
        }.padding(.top, 14)
    }
}

// MARK: - monthly report (the same sums, sections and design as the web and Android reports)

struct Kpi { var value: String; var label: String; var tone = "" }
struct CountryRow { var name: String; var projects: Int; var visited: Int; var working: Int; var repair: Int; var notWorking: Int; var open: Int }
struct RepairRow { var title: String; var country: String; var state: String; var date: String; var by: String; var urgency: String; var problems: String; var parts: String; var cost: Int? }
struct VisitRow { var date: String; var title: String; var by: String; var state: String; var urgency: String }
struct ReportData {
    var label: String; var country: String; var by: String; var date: String
    var kpis: [Kpi]; var byCountry: [CountryRow]; var repairs: [RepairRow]; var cost: Int; var visits: [VisitRow]
    var fileName: String { "RAFIKI-field-report-" + label.replacingOccurrences(of: " ", with: "-") + ".pdf" }
}

private func monthStart(_ d: Date) -> Date { let c = Calendar.current; return c.date(from: c.dateComponents([.year, .month], from: d))! }
private func addMonths(_ d: Date, _ n: Int) -> Date { Calendar.current.date(byAdding: .month, value: n, to: d)! }
private func monthKey(_ d: Date) -> String { let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyy-MM"; return f.string(from: d) }
private func monthName(_ d: Date) -> String { let f = DateFormatter(); f.locale = Locale(identifier: Lang.sw ? "sw" : "en_GB"); f.dateFormat = "LLLL yyyy"; return f.string(from: d) }
private func dayMonth(_ iso: String) -> String { guard let d = When.parse(iso) else { return String(iso.prefix(10)) }; let f = DateFormatter(); f.locale = Locale(identifier: Lang.sw ? "sw" : "en_GB"); f.dateFormat = "d MMM"; return f.string(from: d) }
private func rnum(_ n: Int) -> String { let f = NumberFormatter(); f.numberStyle = .decimal; f.locale = Locale(identifier: "en_US"); return f.string(from: NSNumber(value: n)) ?? "\(n)" }

/// The month's report for ck ("" = all countries), counted exactly like the web app's report.
func buildReport(_ snap: Snapshot, _ act: [JSON], _ ym: Date, _ ck: String, by: String) -> ReportData {
    let byId = Dictionary(snap.projects.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
    let inC: (String) -> Bool = { id in ck.isEmpty || byId[id]?.ck == ck }
    let from = ym, to = addMonths(ym, 1)
    let ev = act.filter { e in
        guard let d = When.parse(e.str("at")), d >= from, d < to else { return false }
        return e.str("a") == "new" || inC(e.str("id"))
    }
    let visits = ev.filter { $0.str("a") == "visit" }
    var latest: [String: JSON] = [:]
    for v in visits { let id = v.str("id"); if latest[id] == nil || latest[id]!.str("at") < v.str("at") { latest[id] = v } }
    let ids = Array(latest.keys)
    let working = ids.filter { latest[$0]!.str("s") == "working" }.count
    let projs = snap.projects.filter { ck.isEmpty || $0.ck == ck }
    let urgo = ["urgent": 0, "month": 1, "watch": 2]
    let open = snap.repairs.filter { inC($0.key) }.sorted { (urgo[$0.value.urgency] ?? 3, $0.key) < (urgo[$1.value.urgency] ?? 3, $1.key) }
    let urgent = open.filter { $0.value.urgency == "urgent" }.count
    let cost = open.reduce(0) { $0 + ($1.value.cost ?? 0) }
    let due = projs.filter { $0.gps != nil && ageDays(snap, $0) >= DUE_DAYS }.count
    let n = { (a: String) in ev.filter { $0.str("a") == a }.count }
    let pc = ids.isEmpty ? "–" : "\(Int((Double(working) * 100 / Double(ids.count)).rounded()))%"
    func pl(_ k: Int, _ one: String, _ many: String) -> String { k == 1 ? one : many }
    let kpis = [
        Kpi(value: "\(visits.count)", label: pl(visits.count, t("site visit", "ziara"), t("site visits", "ziara"))),
        Kpi(value: "\(ids.count)", label: pl(ids.count, t("project visited", "mradi uliotembelewa"), t("projects visited", "miradi iliyotembelewa"))),
        Kpi(value: pc, label: t("of visited projects working", "ya miradi iliyotembelewa inafanya kazi"), tone: !ids.isEmpty && Double(working) / Double(ids.count) < 0.8 ? "warn" : ""),
        Kpi(value: "\(open.count)", label: pl(open.count, t("open repair", "tengenezo lililo wazi"), t("open repairs", "matengenezo yaliyo wazi")) + (urgent > 0 ? t(" (\(urgent) urgent)", " (\(urgent) ya haraka)") : ""), tone: urgent > 0 ? "bad" : ""),
        Kpi(value: "\(n("fixed"))", label: pl(n("fixed"), t("repair fixed", "tengenezo lililokamilika"), t("repairs fixed", "matengenezo yaliyokamilika"))),
        Kpi(value: "\(n("new"))", label: pl(n("new"), t("new site added", "eneo jipya lililoongezwa"), t("new sites added", "maeneo mapya yaliyoongezwa"))),
        Kpi(value: "\(n("pin"))", label: pl(n("pin"), t("location pinned", "mahali palipowekwa"), t("locations pinned", "mahali palipowekwa"))),
        Kpi(value: "\(due)", label: t("due a visit (6+ months)", "zinasubiri ziara (miezi 6+)")),
    ]
    let byCountry = COUNTRIES.filter { ck.isEmpty || $0.0 == ck }.map { c, name -> CountryRow in
        let cid = ids.filter { byId[$0]?.ck == c }
        let k = { (s: String) in cid.filter { latest[$0]!.str("s") == s }.count }
        return CountryRow(name: name, projects: snap.projects.filter { $0.ck == c }.count, visited: cid.count, working: k("working"), repair: k("repair"), notWorking: k("notworking"), open: open.filter { byId[$0.key]?.ck == c }.count)
    }
    let repairs = open.map { id, r -> RepairRow in
        let p = byId[id]
        let probs = r.problems.joined(separator: ", ")
        return RepairRow(title: p?.title ?? (r.title.isEmpty ? id : r.title), country: p?.country ?? "", state: r.state == "notworking" ? "notworking" : "repair", date: dayMonth(r.at), by: r.by,
                         urgency: r.urgency.isEmpty ? "–" : urgencyName(r.urgency), problems: !probs.isEmpty ? probs : (r.note.isEmpty ? "–" : r.note), parts: r.parts.isEmpty ? "–" : r.parts, cost: (r.cost ?? 0) > 0 ? r.cost : nil)
    }
    let rows = visits.sorted { $0.str("at") < $1.str("at") }.map { e in
        VisitRow(date: dayMonth(e.str("at")), title: byId[e.str("id")]?.title ?? e.str("t"), by: e.str("by"), state: e.str("s"), urgency: e.str("u").isEmpty ? "" : urgencyName(e.str("u")))
    }
    let f = DateFormatter(); f.locale = Locale(identifier: Lang.sw ? "sw" : "en_GB"); f.dateFormat = "d MMMM yyyy"
    return ReportData(label: monthName(ym), country: countryName(ck) ?? t("All countries", "Nchi zote"), by: by, date: f.string(from: Date()), kpis: kpis, byCountry: byCountry, repairs: repairs, cost: cost, visits: rows)
}

func reportTitle(_ r: ReportData) -> String { t("Field report · ", "Ripoti ya uwanjani · ") + r.label }
func reportSub(_ r: ReportData) -> String { r.country + " · RAFIKI Outreach · " + t("Prepared by ", "Imeandaliwa na ") + r.by + ", " + r.date }
func repairsHeading(_ r: ReportData) -> String { t("Open repairs", "Matengenezo yaliyo wazi") + (r.cost > 0 ? t(" · about KES\u{00A0}\(rnum(r.cost)) estimated", " · takriban KES\u{00A0}\(rnum(r.cost)) kwa makadirio") : "") }
func reportNote() -> String { t("Only visits, repairs and pins made with the RAFIKI Pin app are counted.", "Ziara, matengenezo na mahali vilivyowekwa kwa programu ya RAFIKI Pin tu ndivyo vinahesabiwa.") }
let LETTERHEAD_FOOT = "501(c)(3) Nonprofit Organization  |  Providing clean water and community development in East Africa"

/// One table cell: text (bold or not, after a status mark), and an optional small grey line under it.
struct RCell { var text: String; var bold = false; var icon: String? = nil; var sub = "" }
struct RTable { var cols: [CGFloat]; var head: [RCell]; var rows: [[RCell]] }
private let STATE_ICONS: Set<String> = ["working", "repair", "notworking"]

func countryTable(_ r: ReportData) -> RTable {
    RTable(cols: [92, 58, 58, 74, 66, 84, 76.6],
           head: [RCell(text: t("Country", "Nchi")), RCell(text: t("Projects", "Miradi")), RCell(text: t("Visited", "Zimetembelewa")), RCell(text: t("Working", "Zinafanya kazi"), icon: "working"),
                  RCell(text: t("Repair", "Matengenezo"), icon: "repair"), RCell(text: t("Not working", "Hazifanyi kazi"), icon: "notworking"), RCell(text: t("Open repairs", "Matengenezo wazi"))],
           rows: r.byCountry.map { c in [RCell(text: c.name), RCell(text: "\(c.projects)"), RCell(text: "\(c.visited)"), RCell(text: "\(c.working)"), RCell(text: "\(c.repair)"), RCell(text: "\(c.notWorking)"), RCell(text: "\(c.open)")] })
}
func repairTable(_ r: ReportData) -> RTable {
    RTable(cols: [128, 68, 62, 128, 70, 52.6],
           head: [RCell(text: t("Project", "Mradi")), RCell(text: t("Reported", "Iliripotiwa")), RCell(text: t("Urgency", "Uharaka")), RCell(text: t("Problems", "Matatizo")), RCell(text: t("Parts", "Vipuri")), RCell(text: t("Est. KES", "Makadirio KES"))],
           rows: r.repairs.map { x in [RCell(text: x.title, bold: true, icon: x.state, sub: (x.country.isEmpty ? "" : x.country + " · ") + stateName(x.state).lowercased()),
                                       RCell(text: x.date, sub: x.by), RCell(text: x.urgency), RCell(text: x.problems), RCell(text: x.parts), RCell(text: x.cost.map(rnum) ?? "–")] })
}
func visitTable(_ r: ReportData) -> RTable {
    RTable(cols: [62, 176, 118, 152.6],
           head: [RCell(text: t("Date", "Tarehe")), RCell(text: t("Project", "Mradi")), RCell(text: t("By", "Na")), RCell(text: t("Result", "Matokeo"))],
           rows: r.visits.map { v in [RCell(text: v.date), RCell(text: v.title), RCell(text: v.by), RCell(text: stateName(v.state) + (v.urgency.isEmpty ? "" : " · " + v.urgency), icon: STATE_ICONS.contains(v.state) ? v.state : nil)] })
}

private let PInk = Color(hex: 0x04263F), PBlue = Color(hex: 0x076DB1), PText = Color(hex: 0x141618), PMuted = Color(hex: 0x5A6066), PLine = Color(hex: 0xDCE1E5), PNavy = Color(hex: 0x1A365D)

/// The web report's status marks (✅ ⚠️ ❌), drawn so they look the same everywhere.
struct StateMark: View {
    let state: String; var size: CGFloat = 12
    var body: some View {
        switch state {
        case "working": Image(systemName: "checkmark.square.fill").resizable().foregroundColor(Color(hex: 0x2E9E5B)).frame(width: size, height: size)
        case "repair": Image(systemName: "exclamationmark.triangle.fill").resizable().foregroundColor(Color(hex: 0xF2B13A)).frame(width: size, height: size)
        default: Image(systemName: "xmark").resizable().font(.system(size: size, weight: .heavy)).foregroundColor(Color(hex: 0xD7301F)).frame(width: size * 0.8, height: size * 0.8)
        }
    }
}

struct ReportView: View {
    @EnvironmentObject var m: AppModel
    @State private var ym = monthStart(Date())
    @State private var ck = ""
    @State private var err: String?

    var body: some View {
        if let snap = m.snap {
            let home = snap.me.home.isEmpty ? m.prefs.country : snap.me.home
            let rep = buildReport(snap, m.activity ?? [], ym, ck, by: m.prefs.name)
            let months = (0..<13).map { addMonths(monthStart(Date()), -$0) }
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    BackLink(label: t("Me", "Mimi")) { m.back() }
                    HStack(alignment: .bottom, spacing: 10) {
                        Picker1(label: t("Month", "Mwezi"), value: monthKey(ym), options: months.map { (monthKey($0), monthName($0)) }) { k in if let d = months.first(where: { monthKey($0) == k }) { ym = d } }
                        Picker1(label: t("Country", "Nchi"), value: ck, options: [("", t("All countries", "Nchi zote"))] + COUNTRIES) { ck = $0 }
                    }
                    Btn(t("Share PDF", "Shiriki PDF"), icon: "share", wide: true) { sharePdf(rep, home: home) }.padding(.top, 12).accessibilityIdentifier("sharePdf")
                    Msg(err, "warn")
                    ReportPaper(r: rep, home: home).padding(.top, 14)
                    Spacer().frame(height: 24)
                }.padding(16)
            }
            .task { if !(await m.loadActivity()) { err = t("No signal: the report needs a connection the first time.", "Hakuna mtandao: ripoti inahitaji mtandao mara ya kwanza.") } }
        }
    }
}

/// The report as a white sheet, like the web report (the same sections and tables as the PDF).
private struct ReportPaper: View {
    let r: ReportData; let home: String
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .bottom, spacing: 8) {
                Image("LetterheadLogo").resizable().scaledToFit().frame(maxWidth: .infinity, alignment: .leading).padding(.bottom, 8).layoutPriority(0)
                VStack(alignment: .trailing, spacing: 0) { ForEach(letterheadAddress(home), id: \.self) { Text($0).font(.system(size: 9)).foregroundColor(.black) } }.layoutPriority(1)
            }
            Rectangle().fill(PNavy).frame(height: 1.5).padding(.bottom, 16)
            Text(reportTitle(r)).font(.mont(21, .heavy)).foregroundColor(PInk)
            Text(reportSub(r)).font(.mont(12)).foregroundColor(PMuted).padding(.top, 2)
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)], spacing: 8) {
                ForEach(Array(r.kpis.enumerated()), id: \.offset) { _, k in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(k.value).font(.mont(21, .heavy)).foregroundColor(PInk)
                        Text(k.label).font(.mont(11.5, .semibold)).foregroundColor(Color(hex: 0x3A3F44)).fixedSize(horizontal: false, vertical: true)
                        Spacer(minLength: 0)
                    }.padding(10).frame(maxWidth: .infinity, minHeight: 74, alignment: .topLeading)
                    .background(k.tone == "warn" ? Color(hex: 0xFDF0DC) : k.tone == "bad" ? Color(hex: 0xFBE7E5) : Color(hex: 0xE3F1FC)).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
            }.padding(.top, 14)
            heading(t("By country", "Kwa nchi")); PaperTable(tb: countryTable(r))
            heading(repairsHeading(r))
            if r.repairs.isEmpty { Text(t("None. 🎉", "Hakuna. 🎉")).font(.bMedium).foregroundColor(PMuted) } else { PaperTable(tb: repairTable(r)) }
            heading(t("Visits this month", "Ziara za mwezi huu"))
            if r.visits.isEmpty { Text(t("No visits recorded in the app this month.", "Hakuna ziara zilizorekodiwa kwenye programu mwezi huu.")).font(.bMedium).foregroundColor(PMuted) } else { PaperTable(tb: visitTable(r)) }
            Text(reportNote()).font(.mont(11.5)).foregroundColor(PMuted).padding(.top, 16)
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 4) { Rectangle().fill(PNavy).frame(height: 1); Text(LETTERHEAD_FOOT).font(.system(size: 8)).foregroundColor(Color(white: 0.5)) }
                Image("LetterheadLogo").resizable().scaledToFit().frame(width: 58)
            }.padding(.top, 18)
        }
        .padding(.horizontal, 16).padding(.vertical, 18).background(Color.white)
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous)).shadow(color: .black.opacity(0.12), radius: 4, y: 2)
        .environment(\.colorScheme, .light).accessibilityIdentifier("report")
    }
    func heading(_ s: String) -> some View { Text(s).font(.mont(16, .heavy)).foregroundColor(PBlue).padding(.top, 22).padding(.bottom, 6) }
}

/// A report table like the web one: blue header row, light stripes, hairlines; it scrolls sideways when it's wider than the phone.
private struct PaperTable: View {
    let tb: RTable
    var body: some View {
        let w = tb.cols.map { $0 * 1.15 }
        ScrollView(.horizontal, showsIndicators: true) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .center, spacing: 0) {
                    ForEach(Array(tb.head.enumerated()), id: \.offset) { i, h in
                        HStack(spacing: 4) { if let ic = h.icon { StateMark(state: ic, size: 11) }; Text(h.text).font(.mont(10, .bold)).foregroundColor(.white).lineLimit(2) }
                            .padding(.horizontal, 6).padding(.vertical, 8).frame(width: w[i], alignment: .leading)
                    }
                }.background(PBlue).clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous).inset(by: -0.5))
                ForEach(Array(tb.rows.enumerated()), id: \.offset) { n, r in
                    HStack(alignment: .top, spacing: 0) {
                        ForEach(Array(r.enumerated()), id: \.offset) { i, c in
                            VStack(alignment: .leading, spacing: 1) {
                                HStack(alignment: .top, spacing: 4) {
                                    if let ic = c.icon { StateMark(state: ic, size: 12).padding(.top, 2) }
                                    Text(c.text).font(.mont(11.5, c.bold ? .bold : .medium)).foregroundColor(PText).lineLimit(6).fixedSize(horizontal: false, vertical: true)
                                }
                                if !c.sub.isEmpty { Text(c.sub).font(.mont(10)).foregroundColor(PMuted).lineLimit(1) }
                            }.padding(.horizontal, 6).padding(.vertical, 7).frame(width: w[i], alignment: .leading)
                        }
                    }.background(n % 2 == 1 ? Color(hex: 0xF4F9FD) : Color.white)
                    Rectangle().fill(PLine).frame(width: w.reduce(0, +), height: 0.75)
                }
            }
        }
    }
}

/// Letterhead address on reports, by the home country of the person making the report.
/// Kenya team: the Kitale office (as on rafikioutreach.org/contact). Everyone else: the US head office, as on the printed letterhead.
/// To give another country its own address, add one line here (e.g. "tz": [...]).
let LETTERHEAD: [String: [String]] = [
    "ke": ["Rafiki Outreach", "Mega Centre, 3rd Floor, Makasembo Road", "Kitale, Kenya", "Phone: +254 791 674 001", "Email: info@rafikioutreach.org"],
    "default": ["Rafiki Outreach", "1817 N. Beck Street", "Salt Lake City, UT 84116, U.S.A.", "Phone: +1 801 547 7847", "Email: Info@rafikioutreach.org"],
]
func letterheadAddress(_ home: String) -> [String] { LETTERHEAD[home] ?? LETTERHEAD["default"] ?? [] }

// Page geometry in PDF points, measured from the RAFIKI letterhead (0.6in side margins, logo 2.8in wide, navy rules, small logo at the foot).
private func lhRule(_ x0: CGFloat, _ x1: CGFloat, _ y: CGFloat, _ w: CGFloat) {
    let p = UIBezierPath(); p.move(to: CGPoint(x: x0, y: y)); p.addLine(to: CGPoint(x: x1, y: y)); p.lineWidth = w
    UIColor(red: 0x1A / 255, green: 0x36 / 255, blue: 0x5D / 255, alpha: 1).setStroke(); p.stroke()
}
/// Logo, address and navy rule across the top of the first page.
func drawLetterheadTop(_ home: String, pageW: CGFloat = 595) {
    UIImage(named: "LetterheadLogo")?.draw(in: CGRect(x: 43.2, y: 22, width: 201.6, height: 67.96))
    let font = UIFont(name: "Helvetica", size: 9) ?? UIFont.systemFont(ofSize: 9)
    for (i, line) in letterheadAddress(home).enumerated() {
        let s = NSAttributedString(string: line, attributes: [.font: font, .foregroundColor: UIColor.black])
        s.draw(at: CGPoint(x: pageW - 43.2 - s.size().width, y: 54 + 12 * CGFloat(i) - font.ascender))
    }
    lhRule(43.2, pageW - 43.2, 104.4, 1.5)
}
/// Thin navy rule, the grey nonprofit line and the small RAFIKI logo (right) at the foot of every page.
func drawLetterheadFoot(pageW: CGFloat = 595, pageH: CGFloat = 842) {
    let logoW: CGFloat = 64, logoH: CGFloat = 64 * 237 / 703, right = pageW - 43.2
    UIImage(named: "LetterheadLogo")?.draw(in: CGRect(x: right - logoW, y: pageH - 20 - logoH, width: logoW, height: logoH))
    lhRule(43.2, right - logoW - 14, pageH - 36, 0.75)
    let font = UIFont(name: "Helvetica", size: 8) ?? UIFont.systemFont(ofSize: 8)
    NSAttributedString(string: "501(c)(3) Nonprofit Organization  |  Providing clean water and community development in East Africa", attributes: [.font: font, .foregroundColor: UIColor(white: 0.5, alpha: 1)])
        .draw(at: CGPoint(x: 43.2, y: pageH - 25.2 - font.ascender))
}

/// Draws the report onto A4 pages on the RAFIKI letterhead (numbers, the three tables) and opens the share sheet.
func sharePdf(_ r: ReportData, home: String = "") {
    let page = CGRect(x: 0, y: 0, width: 595, height: 842)
    let ink = UIColor(red: 0x04 / 255, green: 0x26 / 255, blue: 0x3F / 255, alpha: 1), blue = UIColor(red: 0x07 / 255, green: 0x6D / 255, blue: 0xB1 / 255, alpha: 1)
    let text = UIColor(red: 0x14 / 255, green: 0x16 / 255, blue: 0x18 / 255, alpha: 1), mutedC = UIColor(red: 0x5A / 255, green: 0x60 / 255, blue: 0x66 / 255, alpha: 1)
    let title: [NSAttributedString.Key: Any] = [.font: UIFont.boldSystemFont(ofSize: 20), .foregroundColor: ink]
    let h2: [NSAttributedString.Key: Any] = [.font: UIFont.boldSystemFont(ofSize: 13), .foregroundColor: blue]
    let muted: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 10), .foregroundColor: mutedC]
    let L: CGFloat = 43.2, W: CGFloat = 508.6, bottom: CGFloat = 770
    let data = UIGraphicsPDFRenderer(bounds: page).pdfData { ctx in
        var y: CGFloat = 122
        ctx.beginPage(); drawLetterheadFoot(); drawLetterheadTop(home)
        func newPage() { ctx.beginPage(); drawLetterheadFoot(); y = 48 }
        func para(_ s: String, _ a: [NSAttributedString.Key: Any], after: CGFloat = 6) {
            let str = NSAttributedString(string: s, attributes: a)
            let h = ceil(str.boundingRect(with: CGSize(width: W, height: 1000), options: [.usesLineFragmentOrigin], context: nil).height)
            if y + h > bottom { newPage() }
            str.draw(with: CGRect(x: L, y: y, width: W, height: h + 2), options: [.usesLineFragmentOrigin], context: nil); y += h + after
        }
        func mark(_ st: String, _ x: CGFloat, _ top: CGFloat, _ s: CGFloat) {
            let c: UIColor = st == "working" ? UIColor(red: 0.18, green: 0.62, blue: 0.36, alpha: 1) : st == "repair" ? UIColor(red: 0.95, green: 0.69, blue: 0.23, alpha: 1) : UIColor(red: 0.84, green: 0.19, blue: 0.12, alpha: 1)
            c.setFill(); UIBezierPath(roundedRect: CGRect(x: x, y: top, width: s, height: s), cornerRadius: s * 0.2).fill()
            UIColor.white.setStroke(); let p = UIBezierPath(); p.lineWidth = s * 0.14; p.lineCapStyle = .round
            if st == "working" { p.move(to: CGPoint(x: x + s * 0.25, y: top + s * 0.52)); p.addLine(to: CGPoint(x: x + s * 0.43, y: top + s * 0.7)); p.addLine(to: CGPoint(x: x + s * 0.76, y: top + s * 0.32)) }
            else if st == "repair" { p.move(to: CGPoint(x: x + s * 0.5, y: top + s * 0.22)); p.addLine(to: CGPoint(x: x + s * 0.5, y: top + s * 0.58)); p.move(to: CGPoint(x: x + s * 0.5, y: top + s * 0.76)); p.addLine(to: CGPoint(x: x + s * 0.5, y: top + s * 0.78)) }
            else { p.move(to: CGPoint(x: x + s * 0.28, y: top + s * 0.28)); p.addLine(to: CGPoint(x: x + s * 0.72, y: top + s * 0.72)); p.move(to: CGPoint(x: x + s * 0.72, y: top + s * 0.28)); p.addLine(to: CGPoint(x: x + s * 0.28, y: top + s * 0.72)) }
            p.stroke()
        }
        func table(_ tb: RTable) {
            let cell: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 8.5), .foregroundColor: text]
            let cellB: [NSAttributedString.Key: Any] = [.font: UIFont.boldSystemFont(ofSize: 8.5), .foregroundColor: text]
            let sub: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 7.5), .foregroundColor: mutedC]
            let head: [NSAttributedString.Key: Any] = [.font: UIFont.boldSystemFont(ofSize: 8), .foregroundColor: UIColor.white]
            func height(_ s: String, _ a: [NSAttributedString.Key: Any], _ w: CGFloat) -> CGFloat { ceil(NSAttributedString(string: s, attributes: a).boundingRect(with: CGSize(width: w, height: 1000), options: [.usesLineFragmentOrigin], context: nil).height) }
            func drawHead() {
                let hh = (0..<tb.head.count).map { i in height(tb.head[i].text, head, tb.cols[i] - 10 - (tb.head[i].icon != nil ? 12 : 0)) }.max()! + 10
                if y + hh > bottom { newPage() }
                blue.setFill(); UIBezierPath(roundedRect: CGRect(x: L, y: y, width: W, height: hh), byRoundingCorners: [.topLeft, .topRight], cornerRadii: CGSize(width: 5, height: 5)).fill()
                var x = L
                for (i, h) in tb.head.enumerated() {
                    var tx = x + 5
                    if let ic = h.icon { mark(ic, tx, y + 6, 9); tx += 12 }
                    NSAttributedString(string: h.text, attributes: head).draw(with: CGRect(x: tx, y: y + 5, width: tb.cols[i] - (tx - x) - 5, height: hh), options: [.usesLineFragmentOrigin], context: nil)
                    x += tb.cols[i]
                }
                y += hh
            }
            drawHead()
            for (n, r) in tb.rows.enumerated() {
                let rh = (0..<r.count).map { i -> CGFloat in
                    let c = r[i], w = tb.cols[i] - 10 - (c.icon != nil ? 12 : 0)
                    return height(c.text, c.bold ? cellB : cell, w) + (c.sub.isEmpty ? 0 : height(c.sub, sub, tb.cols[i] - 10) + 1)
                }.max()! + 10
                if y + rh > bottom { newPage(); drawHead() }
                if n % 2 == 1 { UIColor(red: 0xF4 / 255, green: 0xF9 / 255, blue: 0xFD / 255, alpha: 1).setFill(); UIRectFill(CGRect(x: L, y: y, width: W, height: rh)) }
                var x = L
                for (i, c) in r.enumerated() {
                    var tx = x + 5
                    if let ic = c.icon { mark(ic, tx, y + 6, 9); tx += 12 }
                    let a = c.bold ? cellB : cell
                    let th = height(c.text, a, tb.cols[i] - (tx - x) - 5)
                    NSAttributedString(string: c.text, attributes: a).draw(with: CGRect(x: tx, y: y + 5, width: tb.cols[i] - (tx - x) - 5, height: th + 2), options: [.usesLineFragmentOrigin], context: nil)
                    if !c.sub.isEmpty { NSAttributedString(string: c.sub, attributes: sub).draw(with: CGRect(x: x + 5, y: y + 6 + th, width: tb.cols[i] - 10, height: 40), options: [.usesLineFragmentOrigin], context: nil) }
                    x += tb.cols[i]
                }
                y += rh
                UIColor(red: 0xDC / 255, green: 0xE1 / 255, blue: 0xE5 / 255, alpha: 1).setFill(); UIRectFill(CGRect(x: L, y: y, width: W, height: 0.6))
            }
            y += 6
        }
        para(reportTitle(r), title, after: 2)
        para(reportSub(r), muted, after: 12)
        // the numbers: four tinted tiles to a row
        let tw = (W - 3 * 8) / 4, th: CGFloat = 54
        for (i, k) in r.kpis.enumerated() {
            let col = CGFloat(i % 4), rowY = y + CGFloat(i / 4) * (th + 8)
            let bg = k.tone == "warn" ? UIColor(red: 0xFD / 255, green: 0xF0 / 255, blue: 0xDC / 255, alpha: 1) : k.tone == "bad" ? UIColor(red: 0xFB / 255, green: 0xE7 / 255, blue: 0xE5 / 255, alpha: 1) : UIColor(red: 0xE3 / 255, green: 0xF1 / 255, blue: 0xFC / 255, alpha: 1)
            bg.setFill(); UIBezierPath(roundedRect: CGRect(x: L + col * (tw + 8), y: rowY, width: tw, height: th), cornerRadius: 7).fill()
            NSAttributedString(string: k.value, attributes: [.font: UIFont.boldSystemFont(ofSize: 16), .foregroundColor: ink]).draw(at: CGPoint(x: L + col * (tw + 8) + 8, y: rowY + 6))
            NSAttributedString(string: k.label, attributes: [.font: UIFont.systemFont(ofSize: 7.5, weight: .semibold), .foregroundColor: UIColor(red: 0x3A / 255, green: 0x3F / 255, blue: 0x44 / 255, alpha: 1)])
                .draw(with: CGRect(x: L + col * (tw + 8) + 8, y: rowY + 27, width: tw - 14, height: 26), options: [.usesLineFragmentOrigin], context: nil)
        }
        y += CGFloat((r.kpis.count + 3) / 4) * (th + 8) + 8
        para(t("By country", "Kwa nchi"), h2); table(countryTable(r))
        para(repairsHeading(r), h2)
        if r.repairs.isEmpty { para(t("None.", "Hakuna."), muted) } else { table(repairTable(r)) }
        para(t("Visits this month", "Ziara za mwezi huu"), h2)
        if r.visits.isEmpty { para(t("No visits recorded in the app this month.", "Hakuna ziara zilizorekodiwa kwenye programu mwezi huu."), muted) } else { table(visitTable(r)) }
        y += 6; para(reportNote(), muted)
    }
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(r.fileName)
    try? data.write(to: url)
    Share.items([url])
}

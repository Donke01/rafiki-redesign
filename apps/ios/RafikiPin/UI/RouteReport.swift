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
            Btn(leg.0, icon: "compass", wide: true) { Share.open(leg.1) }.padding(.top, 10)
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

// MARK: - monthly report

struct ReportData {
    var label: String; var visits: [JSON]; var ids: Set<String>; var working: Int; var fixed: Int; var pins: Int; var newSites: Int; var openRepairs: Int; var due: Int; var byCountry: [[String]]
}

private func monthStart(_ d: Date) -> Date {
    var c = Calendar(identifier: .gregorian); c.timeZone = TimeZone(identifier: "UTC")!
    return c.date(from: c.dateComponents([.year, .month], from: d))!
}
private func addMonths(_ d: Date, _ n: Int) -> Date { var c = Calendar(identifier: .gregorian); c.timeZone = TimeZone(identifier: "UTC")!; return c.date(byAdding: .month, value: n, to: d)! }
private func monthKey(_ d: Date) -> String { let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.timeZone = TimeZone(identifier: "UTC"); f.dateFormat = "yyyy-MM"; return f.string(from: d) }
private func monthName(_ d: Date) -> String {
    let f = DateFormatter(); f.timeZone = TimeZone(identifier: "UTC"); f.locale = Locale(identifier: Lang.sw ? "sw" : "en_GB"); f.dateFormat = "LLLL yyyy"; return f.string(from: d)
}

struct ReportView: View {
    @EnvironmentObject var m: AppModel
    @State private var ym = monthStart(Date())
    @State private var ck = ""
    @State private var err: String?

    func build(_ snap: Snapshot) -> ReportData {
        let inCountry: (String) -> Bool = { id in ck.isEmpty || snap.projects.first { $0.id == id }?.ck == ck }
        let from = ym, to = addMonths(ym, 1)
        let ev = (m.activity ?? []).filter { e in
            guard let d = When.parse(e.str("at")), d >= from, d < to else { return false }
            return e.str("id").isEmpty || inCountry(e.str("id"))
        }
        let visits = ev.filter { $0.str("a") == "visit" }
        var latest: [String: JSON] = [:]
        for v in visits { let id = v.str("id"); if latest[id] == nil || latest[id]!.str("at") < v.str("at") { latest[id] = v } }
        let byCountry: [[String]] = COUNTRIES.filter { ck.isEmpty || $0.0 == ck }.compactMap { k, n in
            let l = snap.projects.filter { $0.ck == k }; if l.isEmpty { return nil }
            let ids = Set(l.map(\.id)); let v = latest.filter { ids.contains($0.key) }.values
            return [n, "\(l.count)", "\(v.count)", "\(v.filter { $0.str("s") == "working" }.count)", "\(v.filter { $0.str("s") == "repair" }.count)", "\(v.filter { $0.str("s") == "notworking" }.count)", "\(l.filter { snap.repairs[$0.id] != nil }.count)"]
        }
        return ReportData(label: monthName(ym), visits: visits, ids: Set(latest.keys), working: latest.values.filter { $0.str("s") == "working" }.count,
                          fixed: ev.filter { $0.str("a") == "fixed" }.count, pins: ev.filter { $0.str("a") == "pin" }.count, newSites: ev.filter { $0.str("a") == "new" }.count,
                          openRepairs: snap.repairs.keys.filter(inCountry).count, due: snap.projects.filter { $0.gps != nil && inCountry($0.id) && ageDays(snap, $0) >= DUE_DAYS }.count, byCountry: byCountry)
    }

    var body: some View {
        if let snap = m.snap {
            let rep = build(snap)
            let months = (0..<13).map { addMonths(monthStart(Date()), -$0) }
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    BackLink(label: t("Me", "Mimi")) { m.back() }
                    HStack(alignment: .bottom, spacing: 10) {
                        Picker1(label: t("Month", "Mwezi"), value: monthKey(ym), options: months.map { (monthKey($0), monthName($0)) }) { k in if let d = months.first(where: { monthKey($0) == k }) { ym = d } }
                        Picker1(label: t("Country", "Nchi"), value: ck, options: [("", t("All countries", "Nchi zote"))] + COUNTRIES) { ck = $0 }
                    }
                    Msg(err, "warn")
                    CardV {
                        Text(t("Field report · ", "Ripoti ya uwanjani · ") + rep.label).font(.tLarge).foregroundColor(C.ink)
                        Text((countryName(ck) ?? t("All countries", "Nchi zote")) + " · RAFIKI Outreach · " + t("Prepared by ", "Imeandaliwa na ") + m.prefs.name).font(.bSmall).foregroundColor(C.muted)
                        let pctW = rep.ids.isEmpty ? "—" : "\(rep.working * 100 / rep.ids.count)%"
                        let tiles = [("\(rep.visits.count)", t("site visits", "ziara")), ("\(rep.ids.count)", t("projects visited", "miradi iliyotembelewa")),
                                     (pctW, t("of visited projects working", "ya miradi iliyotembelewa inafanya kazi")), ("\(rep.openRepairs)", t("open repairs", "matengenezo yaliyo wazi")),
                                     ("\(rep.fixed)", t("repairs fixed", "matengenezo yaliyokamilika")), ("\(rep.newSites)", t("new sites added", "maeneo mapya yaliyoongezwa")),
                                     ("\(rep.pins)", t("locations pinned", "mahali palipowekwa")), ("\(rep.due)", t("due a visit (6+ months)", "zinasubiri ziara (miezi 6+)"))]
                        LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
                            ForEach(Array(tiles.enumerated()), id: \.offset) { _, tl in
                                VStack(alignment: .leading, spacing: 2) { Text(tl.0).font(.hMedium).foregroundColor(C.ink); Text(tl.1).font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true) }
                                    .frame(maxWidth: .infinity, minHeight: 80, alignment: .topLeading).padding(12).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                            }
                        }.padding(.top, 10)
                        H2(t("By country", "Kwa nchi")).padding(.top, 16)
                        let head = [t("Country", "Nchi"), t("Projects", "Miradi"), t("Visited", "Zimetembelewa"), t("Working", "Zinafanya kazi"), t("Repair", "Matengenezo"), t("Not working", "Hazifanyi kazi"), t("Open repairs", "Matengenezo wazi")]
                        ScrollView(.horizontal, showsIndicators: true) {
                            VStack(alignment: .leading, spacing: 0) {
                                ForEach(Array(([head] + rep.byCountry).enumerated()), id: \.offset) { i, r in
                                    HStack(spacing: 0) {
                                        ForEach(Array(r.enumerated()), id: \.offset) { j, c in
                                            Text(c).font(.mont(12, i == 0 || j == 0 ? .bold : .medium)).foregroundColor(i == 0 ? .white : C.text).lineLimit(2)
                                                .frame(width: j == 0 ? 104 : 118, alignment: .leading).padding(.trailing, 6)
                                        }
                                    }.padding(.vertical, 6).padding(.horizontal, 4).background(i == 0 ? C.blue2 : i % 2 == 0 ? C.tint : .clear)
                                }
                            }
                        }
                        H2(t("Visits this month", "Ziara za mwezi huu")).padding(.top, 16)
                        if rep.visits.isEmpty { Text(t("No visits recorded in the app this month.", "Hakuna ziara zilizorekodiwa kwenye programu mwezi huu.")).font(.bMedium).foregroundColor(C.muted) }
                        ForEach(Array(rep.visits.sorted { $0.str("at") < $1.str("at") }.enumerated()), id: \.offset) { _, e in
                            Text(When.text(e.str("at")) + " · " + e.str("t") + " · " + e.str("by") + " · " + stateName(e.str("s"))).font(.bSmall).foregroundColor(C.text).padding(.top, 4)
                        }
                        Text(t("Only visits, repairs and pins made with the RAFIKI Pin app are counted.", "Ziara, matengenezo na mahali vilivyowekwa kwa programu ya RAFIKI Pin tu ndivyo vinahesabiwa.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 12)
                    }.padding(.top, 14)
                    Btn(t("Share as PDF", "Shiriki kama PDF"), icon: "upload", wide: true) { sharePdf(rep, country: countryName(ck) ?? t("All countries", "Nchi zote"), by: m.prefs.name, home: m.prefs.country) }.padding(.top, 14)
                    Spacer().frame(height: 24)
                }.padding(16)
            }
            .task { if !(await m.loadActivity()) { err = t("No signal: the report needs a connection the first time.", "Hakuna mtandao: ripoti inahitaji mtandao mara ya kwanza.") } }
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

// Page geometry in PDF points, measured from the RAFIKI letterhead (0.6in side margins, logo 2.8in wide, navy rules, seal at the foot).
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
/// Seal, thin navy rule and the grey nonprofit line at the foot of every page.
func drawLetterheadFoot(pageW: CGFloat = 595, pageH: CGFloat = 842) {
    let sealR = pageW - 50.4
    UIImage(named: "LetterheadSeal")?.draw(in: CGRect(x: sealR - 79.2, y: pageH - 36 - 79.2, width: 79.2, height: 79.2))
    lhRule(43.2, sealR - 79.2 - 21.6, pageH - 36, 0.75)
    let font = UIFont(name: "Helvetica", size: 8) ?? UIFont.systemFont(ofSize: 8)
    NSAttributedString(string: "501(c)(3) Nonprofit Organization  |  Providing clean water and community development in East Africa", attributes: [.font: font, .foregroundColor: UIColor(white: 0.5, alpha: 1)])
        .draw(at: CGPoint(x: 43.2, y: pageH - 25.2 - font.ascender))
}

/// Draws the report onto A4 pages on the RAFIKI letterhead and opens the share sheet (WhatsApp, e-mail, Files…).
func sharePdf(_ r: ReportData, country: String, by: String, home: String = "") {
    let page = CGRect(x: 0, y: 0, width: 595, height: 842)
    let title: [NSAttributedString.Key: Any] = [.font: UIFont.boldSystemFont(ofSize: 20), .foregroundColor: UIColor(red: 0x04 / 255, green: 0x26 / 255, blue: 0x3F / 255, alpha: 1)]
    let h2: [NSAttributedString.Key: Any] = [.font: UIFont.boldSystemFont(ofSize: 13), .foregroundColor: UIColor(red: 0x07 / 255, green: 0x6D / 255, blue: 0xB1 / 255, alpha: 1)]
    let body: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 10), .foregroundColor: UIColor(red: 0x14 / 255, green: 0x16 / 255, blue: 0x18 / 255, alpha: 1)]
    let muted: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 10), .foregroundColor: UIColor(red: 0x5A / 255, green: 0x60 / 255, blue: 0x66 / 255, alpha: 1)]
    let data = UIGraphicsPDFRenderer(bounds: page).pdfData { ctx in
        var y: CGFloat = 128
        ctx.beginPage(); drawLetterheadFoot(); drawLetterheadTop(home)
        func line(_ text: String, _ a: [NSAttributedString.Key: Any], _ gap: CGFloat = 15) {
            let s = NSAttributedString(string: text, attributes: a)
            let h = s.boundingRect(with: CGSize(width: 499, height: 1000), options: [.usesLineFragmentOrigin], context: nil).height
            if y + h > 712 { ctx.beginPage(); drawLetterheadFoot(); y = 48 }
            s.draw(with: CGRect(x: 48, y: y, width: 499, height: h + 2), options: [.usesLineFragmentOrigin], context: nil)
            y += max(gap, h + 4)
        }
        line(t("Field report · ", "Ripoti ya uwanjani · ") + r.label, title, 26)
        line("\(country) · RAFIKI Outreach · " + t("Prepared by ", "Imeandaliwa na ") + by, muted, 26)
        let pct = r.ids.isEmpty ? "—" : "\(r.working * 100 / r.ids.count)%"
        ["\(r.visits.count) " + t("site visits", "ziara"), "\(r.ids.count) " + t("projects visited", "miradi iliyotembelewa"), "\(pct) " + t("of visited projects working", "ya miradi iliyotembelewa inafanya kazi"),
         "\(r.openRepairs) " + t("open repairs", "matengenezo yaliyo wazi"), "\(r.fixed) " + t("repairs fixed", "matengenezo yaliyokamilika"), "\(r.newSites) " + t("new sites added", "maeneo mapya yaliyoongezwa"),
         "\(r.pins) " + t("locations pinned", "mahali palipowekwa"), "\(r.due) " + t("due a visit (6+ months)", "zinasubiri ziara (miezi 6+)")].forEach { line("•  " + $0, body) }
        y += 10; line(t("By country", "Kwa nchi"), h2, 18)
        for row in r.byCountry {
            line(row[0] + ": " + t("\(row[1]) projects, \(row[2]) visited, \(row[3]) working, \(row[4]) needed repair, \(row[5]) not working, \(row[6]) open repairs",
                                    "miradi \(row[1]), \(row[2]) zimetembelewa, \(row[3]) zinafanya kazi, \(row[4]) zilihitaji matengenezo, \(row[5]) hazifanyi kazi, matengenezo \(row[6]) wazi"), body)
        }
        y += 10; line(t("Visits", "Ziara"), h2, 18)
        if r.visits.isEmpty { line(t("No visits recorded in the app this month.", "Hakuna ziara zilizorekodiwa kwenye programu mwezi huu."), muted) }
        for e in r.visits.sorted(by: { $0.str("at") < $1.str("at") }) { line(When.text(e.str("at")) + "  " + String(e.str("t").prefix(48)) + "  ·  " + e.str("by") + "  ·  " + stateName(e.str("s")), body) }
        y += 14; line(t("Only visits, repairs and pins made with the RAFIKI Pin app are counted.", "Ziara, matengenezo na mahali vilivyowekwa kwa programu ya RAFIKI Pin tu ndivyo vinahesabiwa."), muted)
    }
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("RAFIKI-field-report-" + r.label.replacingOccurrences(of: " ", with: "-") + ".pdf")
    try? data.write(to: url)
    Share.items([url])
}

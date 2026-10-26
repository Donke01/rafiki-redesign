import MapKit
import SwiftUI
import UIKit

// Office › Team map and Office › Plan a week (Android's TeamMap.kt): where teammates who share their location are, with
// today's visits; and a suggested week of visits for one person, by day, in driving order, assigned in one go.

let OFA_COLORS: [UInt32] = [0x0A78C2, 0x1C9C5A, 0xD98200, 0x7A4FD0, 0xD23C32, 0x0E8F96]
private func ui(_ c: UInt32, _ a: CGFloat = 1) -> UIColor { UIColor(red: CGFloat((c >> 16) & 255) / 255, green: CGFloat((c >> 8) & 255) / 255, blue: CGFloat(c & 255) / 255, alpha: a) }
private func ofaStateColor(_ s: String) -> UInt32 { s == "notworking" ? 0xB3261E : s == "repair" ? 0xD98200 : 0x1C9C5A }
private func ofaOld(_ iso: String) -> Bool { minutesSince(iso) > 60 }

// MARK: - the map

struct MapAvatar: Equatable { var key: String; var ll: LatLng; var color: UInt32; var label: String; var faded: Bool }
struct OfaPin: Equatable { var ll: LatLng; var color: UInt32; var number: String?; var title: String; var dim = false }
struct OfaLine: Equatable { var pts: [LatLng]; var color: UInt32; var dim: Bool }

private final class OfaAnn: NSObject, MKAnnotation {
    let coordinate: CLLocationCoordinate2D; let image: UIImage; let key: String?; let title: String?; let front: Bool
    init(_ ll: LatLng, _ image: UIImage, key: String?, title: String, front: Bool) { coordinate = CLLocationCoordinate2D(latitude: ll.lat, longitude: ll.lng); self.image = image; self.key = key; self.title = title; self.front = front }
}
private final class OfaLineOverlay: MKPolyline { var color: UIColor = .blue }

private func pinImage(_ p: OfaPin) -> UIImage {
    let s: CGFloat = p.number != nil ? 26 : 16, a: CGFloat = p.dim ? 0.35 : 1
    return UIGraphicsImageRenderer(size: CGSize(width: s, height: s)).image { ctx in
        UIColor.white.withAlphaComponent(a).setFill(); ctx.cgContext.fillEllipse(in: CGRect(x: 0, y: 0, width: s, height: s))
        ui(p.color, a).setFill(); ctx.cgContext.fillEllipse(in: CGRect(x: 2.2, y: 2.2, width: s - 4.4, height: s - 4.4))
        if let n = p.number {
            let at: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 11, weight: .heavy), .foregroundColor: UIColor.white.withAlphaComponent(a)]
            let sz = (n as NSString).size(withAttributes: at); (n as NSString).draw(at: CGPoint(x: (s - sz.width) / 2, y: (s - sz.height) / 2), withAttributes: at)
        }
    }
}

/// A map with teammates (initials, last seen), small pins and route lines. Fits to the focus (or everything) when fitKey changes.
struct OfaMap: UIViewRepresentable {
    let avatars: [MapAvatar]; let pins: [OfaPin]; let lines: [OfaLine]; var fitKey: String = ""; var focus: [LatLng]? = nil; var onAvatar: (String) -> Void = { _ in }
    func makeCoordinator() -> Coord { Coord(self) }
    func makeUIView(context: Context) -> MKMapView {
        let mv = MKMapView(); mv.delegate = context.coordinator; mv.pointOfInterestFilter = .excludingAll
        mv.setRegion(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: 0.5, longitude: 35.3), span: MKCoordinateSpan(latitudeDelta: 6, longitudeDelta: 6)), animated: false)
        return mv
    }
    func updateUIView(_ mv: MKMapView, context: Context) {
        let c = context.coordinator; c.parent = self
        let key = "\(avatars)|\(pins)|\(lines)"
        if key != c.key {
            c.key = key
            mv.removeAnnotations(mv.annotations); mv.removeOverlays(mv.overlays)
            for l in lines where l.pts.count > 1 {
                var cs = l.pts.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lng) }
                let o = OfaLineOverlay(coordinates: &cs, count: cs.count); o.color = ui(l.color, l.dim ? 0.28 : 0.86); mv.addOverlay(o)
            }
            mv.addAnnotations(pins.sorted { $0.dim && !$1.dim }.map { OfaAnn($0.ll, pinImage($0), key: nil, title: $0.title, front: !$0.dim) })
            mv.addAnnotations(avatars.enumerated().map { _, a in OfaAnn(a.ll, PinArt.mate(a.key, ui(a.color), a.label, faded: a.faded), key: a.key, title: a.key, front: true) })
        }
        if c.fitted != fitKey {
            c.fitted = fitKey
            let pts = (focus.flatMap { $0.isEmpty ? nil : $0 }) ?? { let x = avatars.map(\.ll) + pins.filter { !$0.dim }.map(\.ll); return x.isEmpty ? pins.map(\.ll) : x }()
            DispatchQueue.main.async {
                if pts.count == 1 { mv.setRegion(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: pts[0].lat, longitude: pts[0].lng), latitudinalMeters: 6000, longitudinalMeters: 6000), animated: false) }
                else if pts.count > 1 {
                    var r = MKMapRect.null
                    for p in pts { let m = MKMapPoint(CLLocationCoordinate2D(latitude: p.lat, longitude: p.lng)); r = r.union(MKMapRect(x: m.x, y: m.y, width: 0, height: 0)) }
                    let minW = 5000 * MKMapPointsPerMeterAtLatitude(pts[0].lat)
                    if r.size.width < minW { r = r.insetBy(dx: -(minW - r.size.width) / 2, dy: -(minW - r.size.width) / 2) }
                    mv.setVisibleMapRect(r, edgePadding: UIEdgeInsets(top: 50, left: 40, bottom: 40, right: 40), animated: false)
                }
            }
        }
    }
    final class Coord: NSObject, MKMapViewDelegate {
        var parent: OfaMap; var key = ""; var fitted: String? = nil
        init(_ p: OfaMap) { parent = p }
        func mapView(_ mapView: MKMapView, viewFor a: MKAnnotation) -> MKAnnotationView? {
            guard let o = a as? OfaAnn else { return nil }
            let v = MKAnnotationView(annotation: o, reuseIdentifier: nil); v.image = o.image; v.canShowCallout = o.key == nil
            v.zPriority = o.key != nil ? .max : o.front ? .defaultSelected : .min
            if o.key != nil { v.centerOffset = CGPoint(x: 0, y: o.image.size.height / 2 - 23) }
            return v
        }
        func mapView(_ mapView: MKMapView, didSelect view: MKAnnotationView) {
            if let o = view.annotation as? OfaAnn, let k = o.key { mapView.deselectAnnotation(o, animated: false); parent.onAvatar(k) }
        }
        func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            if let l = overlay as? OfaLineOverlay { let r = MKPolylineRenderer(polyline: l); r.strokeColor = l.color; r.lineWidth = 4; r.lineCap = .round; return r }
            return MKOverlayRenderer(overlay: overlay)
        }
    }
}

private struct OfaLegend: View {
    let c: UInt32; let label: String
    var body: some View { HStack(spacing: 5) { Circle().fill(Color(ui(c))).frame(width: 7, height: 7).padding(2).background(Circle().fill(.white)); Text(label).font(.bSmall).foregroundColor(C.muted) } }
}

// MARK: - Office › Team map

/// The map first, then who is sharing as a compact list; tap a person for their contact sheet.
struct TeamMapSection: View {
    @EnvironmentObject var m: AppModel
    let s: Snapshot
    @State private var data: TeamMapData?
    @State private var err: String?
    @State private var tick = 0
    @State private var who = ""
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            DqmHead(icon: "pin", hue: "green", title: t("Team map", "Ramani ya timu"), line: t("Where teammates who share their location are, and today’s visits.", "Wenzako wanaoshiriki mahali walipo, na ziara za leo."),
                    nums: data.map { d in [DqmNum(n: d.people.count, label: t("sharing now", "wanashiriki sasa"), tone: d.people.isEmpty ? "off" : "ok"),
                                           DqmNum(n: d.visits.count, label: d.visits.count == 1 ? t("visit today", "ziara ya leo") : t("visits today", "ziara za leo")),
                                           { let n = d.visits.filter { $0.state == "notworking" }.count; return DqmNum(n: n, label: t("not working", "hazifanyi kazi"), tone: n > 0 ? "bad" : "off") }()] } ?? [],
                    trailing: { Button { Haptic.tap(); tick += 1 } label: { Icon("refresh", size: 20, color: C.ink).frame(width: 44, height: 44).overlay(Circle().stroke(C.line, lineWidth: 1)) }.buttonStyle(.plain).accessibilityLabel(t("Refresh", "Onyesha upya")) },
                    actions: { EmptyView() })
            if let d = data {
                let colors = Dictionary(uniqueKeysWithValues: d.people.enumerated().map { ($0.element.name, OFA_COLORS[$0.offset % OFA_COLORS.count]) })
                let avatars = d.people.map { MapAvatar(key: $0.name, ll: $0.ll, color: colors[$0.name]!, label: agoText($0.at), faded: ofaOld($0.at)) }
                let pins = d.visits.compactMap { v in s.projects.first { $0.id == v.id }?.gps.map { OfaPin(ll: $0, color: ofaStateColor(v.state), number: nil, title: v.title) } }
                let focus: [LatLng]? = who.isEmpty ? nil : d.people.filter { $0.name == who }.map(\.ll) + d.visits.filter { $0.by.lowercased() == who.lowercased() }.compactMap { v in s.projects.first { $0.id == v.id }?.gps }
                OfaMap(avatars: avatars, pins: pins, lines: [], fitKey: "\(d.people.count)|\(d.visits.count)|\(who)|\(tick)", focus: focus) { who = $0; Contact.show($0) }
                    .frame(height: 340).clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(C.line, lineWidth: 1)).padding(.top, 12)
                HStack(spacing: 12) {
                    OfaLegend(c: 0x1C9C5A, label: t("Visit today: working", "Ziara ya leo: inafanya kazi")); OfaLegend(c: 0xD98200, label: t("needs repair", "inahitaji matengenezo")); OfaLegend(c: 0xB3261E, label: t("not working", "haifanyi kazi"))
                }.lineLimit(1).minimumScaleFactor(0.7).padding(.top, 8).padding(.leading, 4)
                Text((d.people.isEmpty ? t("Nobody is sharing their location right now.", "Hakuna anayeshiriki mahali kwa sasa.") : d.people.count == 1 ? t("1 teammate sharing their location", "Mwenzako 1 anashiriki mahali") : t("\(d.people.count) teammates sharing their location", "Wenzako \(d.people.count) wanashiriki mahali"))
                     + " · " + (d.visits.count == 1 ? t("1 visit today", "ziara 1 leo") : t("\(d.visits.count) visits today", "ziara \(d.visits.count) leo"))).font(.tSmall).foregroundColor(C.ink).padding(.top, 16).padding(.leading, 4)
                ForEach(Array(d.people.enumerated()), id: \.offset) { i, p in
                    let vs = d.visits.filter { $0.by.lowercased() == p.name.lowercased() }
                    DqmCard(title: p.name, lines: [t("Last seen ", "Alionekana ") + agoText(p.at) + (p.acc > 0 ? " · " + Geo.acc(p.acc) : ""),
                                                   (ofaOld(p.at) ? t("Not seen for over an hour · ", "Hajaonekana kwa zaidi ya saa moja · ") : "") + (vs.count == 1 ? t("1 visit today", "ziara 1 leo") : t("\(vs.count) visits today", "ziara \(vs.count) leo"))],
                            lead: { DqmAvatar(name: p.name, bg: Color(ui(colors[p.name]!)), fg: .white) }) { who = p.name; Contact.show(p.name) }
                        .padding(.top, 8).rise(min(i, 6))
                }
                if d.people.isEmpty { EmptyDrop(text: t("Nobody is sharing their location right now.", "Hakuna anayeshiriki mahali kwa sasa.")) }
                CardV(pad: 4) {
                    DqmFold(icon: "lock", hue: "slate", title: t("Who shows here", "Nani anaonekana hapa"), summary: t("Only people who turn it on, in their work hours", "Walioiwasha tu, wakati wa saa zao za kazi"), tone: "has") {
                        Text(t("Only people who turned on “Share my location” in their Settings show here, and only in their work hours. Only the latest position is kept, for 12 hours, never a history. Field officers never see this map.",
                               "Wanaoonekana hapa ni wale tu waliowasha “Shiriki mahali nilipo” kwenye Mipangilio yao, na wakati wa saa zao za kazi tu. Mahali pa mwisho tu panahifadhiwa, kwa saa 12, si historia. Maafisa wa uwanjani hawaoni ramani hii.")).font(.bMedium).foregroundColor(C.text)
                        Text(t("People turn it on in Me › Settings › Security and alerts. It’s off unless they choose it.", "Watu wanaiwasha kwenye Mimi › Mipangilio › Usalama na arifa. Imezimwa wasipochagua.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 8)
                    }
                }.padding(.top, 14)
            } else { Text(err ?? t("Loading…", "Inapakia…")).font(.bMedium).foregroundColor(err != nil ? C.bad : C.muted).padding(.top, 12) }
        }
        .onAppear { if let w = TeamGo.map { who = w; TeamGo.map = nil } }
        .task(id: tick) { let r = await m.api.adminGet("teammap"); if r.ok { data = TeamMapData.from(r.json); err = nil } else { err = reasonText(r.reason) } }
    }
}

// MARK: - Office › Plan a week

struct PlanInfo { var why: [String]; var age: Int?; var chosen = false }
struct WeekPlan {
    var to: String; var info: [String: PlanInfo]; var total: Int; var days: [[String]]; var spare: [String]; var dates: [Date]; var assigned = false; var nogps: [String] = []
}
/// Working days from start on: Sundays are skipped (the team works Monday to Saturday).
func planDates(_ start: Date, _ n: Int) -> [Date] {
    var out: [Date] = []; var d = start; let cal = Calendar.current
    while out.count < n { if cal.component(.weekday, from: d) != 1 { out.append(d) }; d = cal.date(byAdding: .day, value: 1, to: d)! }
    return out
}
func planOrder(_ ids: [String], _ s: Snapshot) -> [String] { orderStops(ids.compactMap { id in s.projects.first { $0.id == id && $0.gps != nil } }, from: nil).map(\.id) }
func planMetres(_ ids: [String], _ s: Snapshot) -> Double {
    let pts = ids.compactMap { id in s.projects.first { $0.id == id }?.gps }
    return zip(pts, pts.dropFirst()).reduce(0) { $0 + Geo.metres($1.0, $1.1) }
}
func parsePlan(_ j: JSON, to: String, start: Date, _ s: Snapshot) -> WeekPlan {
    var info: [String: PlanInfo] = [:]
    func read(_ o: JSON) -> String { let id = o.str("id"); info[id] = PlanInfo(why: (o["why"] as? [Any])?.compactMap { $0 as? String } ?? [], age: o["age"] == nil || o["age"] is NSNull ? nil : o.int("age"), chosen: o.bool("chosen")); return id }
    let days = j.objs("days").map { planOrder($0.objs("stops").map(read), s) }
    let spare = j.objs("spare").map(read).filter { id in s.projects.contains { $0.id == id && $0.gps != nil } }
    let nogps = j.objs("nogps").map { o in s.projects.first { $0.id == o.str("id") }?.title ?? (o.str("t").isEmpty ? o.str("id") : o.str("t")) }
    return WeekPlan(to: to, info: info, total: j.int("total"), days: days, spare: spare, dates: planDates(start, days.count), nogps: nogps)
}
/// The reasons as short tags: (tone, text).
func planWhy(_ info: PlanInfo?, _ repairState: String?) -> [(String, String)] {
    (info?.chosen == true ? [("chosen", t("Chosen", "Umechagua"))] : []) + (info?.why ?? []).compactMap { w in
        switch w {
        case "repair": return ("bad", repairState == "repair" ? t("Needs repair", "Inahitaji matengenezo") : t("Not working", "Haifanyi kazi"))
        case "urgent": return ("bad", t("Urgent", "Haraka"))
        case "overdue": let mo = Int((Double(info?.age ?? 0) / 30.4).rounded()); return ("", t("Not visited for \(mo) months", "Haijatembelewa kwa miezi \(mo)"))
        case "never": return ("", t("No visit in the app yet", "Bado haijatembelewa kwenye programu"))
        case "test": return ("has", t("Water test due", "Kipimo cha maji kinahitajika"))
        default: return nil
        }
    }
}
private func whyTag(_ x: (String, String)) -> some View {
    let (bg, fg): (Color, Color) = x.0 == "bad" ? (C.badBg, C.bad) : x.0 == "has" ? (C.tint, C.deep) : x.0 == "chosen" ? (C.named("purple").bg, C.named("purple").fg) : (C.warnBg, C.warn)
    return Tag(x.1, bg: bg, fg: fg)
}
/// "Kapsaret, Eldoret" → "Kapsaret": the first part of the area, as the planner (and the server) groups them.
func planArea(_ p: Project) -> String { p.area.split(separator: ",").first.map { $0.trimmingCharacters(in: .whitespaces) } ?? "" }
func planAreas(_ all: [Project], _ ck: String, _ ticked: [String] = []) -> [(String, Int)] {
    let n = Dictionary(grouping: all.filter { $0.ck == ck }.map(planArea).filter { !$0.isEmpty }, by: { $0 }).mapValues(\.count)
    let top = n.sorted { $0.value != $1.value ? $0.value > $1.value : $0.key < $1.key }.prefix(80).map { ($0.key, $0.value) }
    return top + ticked.filter { a in !top.contains { $0.0 == a } && n[a] != nil }.map { ($0, n[$0]!) }
}
private func projN(_ n: Int) -> String { n == 1 ? t("1 project", "mradi 1") : t("\(n) projects", "miradi \(n)") }
func planAreasText(_ areas: [String], _ ck: String) -> String {
    areas.isEmpty ? t("All of ", "Yote ya ") + (countryName(ck) ?? ck) : areas.prefix(2).joined(separator: ", ") + (areas.count > 2 ? " " + t("+\(areas.count - 2) more", "na \(areas.count - 2) zaidi") : "")
}
func planScopeText(_ all: [Project], _ ck: String, _ areas: [String], _ pick: [String]) -> String {
    let cn = countryName(ck) ?? ck
    let ps = pick.compactMap { id in all.first { $0.id == id } }
    if areas.isEmpty { return t("All of ", "Yote ya ") + cn + " · " + (!ps.isEmpty ? t("\(ps.count) chosen by hand", "zimechaguliwa \(ps.count) kwa mkono") : t("suggestions from every area", "mapendekezo kutoka maeneo yote")) }
    let n = Dictionary(grouping: all.filter { $0.ck == ck }, by: planArea).mapValues(\.count)
    let parts = areas.map { a -> String in
        let k = ps.filter { planArea($0) == a }.count, mm = n[a] ?? 0
        return a + ": " + (k > 0 ? t("\(k) of " + projN(mm), (mm == 1 ? "mradi " : "miradi ") + "\(k) kati ya \(mm)") : mm == 1 ? t("its 1 project", "mradi wake 1") : t("all \(mm)", "yote \(mm)"))
    }
    let other = ps.filter { !areas.contains(planArea($0)) }.count
    return parts.prefix(4).joined(separator: " · ") + (parts.count > 4 ? " · " + t("+\(parts.count - 4) more areas", "na maeneo \(parts.count - 4) zaidi") : "") + (other > 0 ? " · " + t("\(other) more chosen", "zingine \(other) zimechaguliwa") : "")
}
private func ofaDayLabel(_ d: Date) -> String {
    let wd = Calendar.current.component(.weekday, from: d)
    let f = DateFormatter(); f.locale = Locale(identifier: Lang.sw ? "sw" : "en_US"); f.dateFormat = "d MMM"
    return ShareHere.dayName((wd + 5) % 7 + 1) + " " + f.string(from: d)
}

/// The choices fold away after the first suggestion; clean day cards; a stop opens a sheet.
struct PlanWeekSection: View {
    @EnvironmentObject var m: AppModel
    let s: Snapshot
    @State private var names: [String] = []
    @State private var to = ""
    @State private var ck = ""
    @State private var days = "5"
    @State private var per = "5"
    @State private var start = ""
    @State private var plan: WeekPlan?
    @State private var msg: (String, String)?
    @State private var amsg: (String, String)?
    @State private var undo: (() -> Void)?
    @State private var day = -1
    @State private var note = ""
    @State private var busy = false
    @State private var stop: (Int, String)?
    @State private var choicesOpen = true
    @State private var areas: [String] = []
    @State private var pick: [String] = []
    @State private var onlyPick = false
    @State private var pickAt: String?
    @State private var made = 0
    private func proj(_ id: String) -> Project? { s.projects.first { $0.id == id } }
    var body: some View {
        ScrollViewReader { sv in
            VStack(alignment: .leading, spacing: 0) {
                DqmHead(icon: "compass", hue: "blue", title: t("Plan a week", "Panga wiki"), line: t("Suggested visits for one person: close sites on the same day, in driving order.", "Ziara zilizopendekezwa kwa mtu mmoja: maeneo yaliyo karibu siku moja, kwa mpangilio wa safari."))
                choices
                if let p = plan, !p.days.isEmpty { planView(p).id("plan") }
            }
            .onChange(of: made) { _ in DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { withAnimation { sv.scrollTo("plan", anchor: .top) } } }
        }
        .task {
            if ck.isEmpty { ck = m.prefs.country.isEmpty ? (s.me.home.isEmpty ? "ke" : s.me.home) : m.prefs.country }
            if start.isEmpty { var d = Date().addingTimeInterval(86400); if Calendar.current.component(.weekday, from: d) == 1 { d = d.addingTimeInterval(86400) }; start = DateField.text(d) }
            let a = await m.api.adminGet("team").json.objs("team"); names = a.map { $0.str("name") }.filter { !$0.isEmpty }.sorted()
            if to.isEmpty { to = names.first ?? "" }
        }
        .onChange(of: pick.count) { n in if n == 0 { onlyPick = false } }
        .sheet(item: Binding(get: { pickAt.map { WhoBox(name: $0) } }, set: { pickAt = $0?.name })) { b in PlanPickSheet(s: s, ck: ck, areas: $areas, pick: $pick, at: b.name) }
        .sheet(item: Binding(get: { stop.map { WhoBox(name: "\($0.0)|\($0.1)") } }, set: { if $0 == nil { stop = nil } })) { _ in
            if let (di, id) = stop, let p = plan, let pr = proj(id), di < p.days.count, let k = p.days[di].firstIndex(of: id) {
                StopSheet(p: p, di: di, k: k, pr: pr, s: s, openIt: { m.go(Place(tab: "office", project: id)) }) {
                    plan = WeekPlan(to: p.to, info: p.info, total: p.total, days: p.days.enumerated().map { j, dd in j == di ? planOrder(dd.filter { $0 != id }, s) : dd }, spare: [id] + p.spare, dates: p.dates, assigned: p.assigned, nogps: p.nogps)
                    amsg = ("ok", t("Taken off day \(di + 1). It’s back in “Add a stop”.", "Imeondolewa siku ya \(di + 1). Iko tena kwenye “Ongeza kituo”."))
                    undo = {
                        guard var cur = plan, !cur.assigned, di < cur.days.count, !cur.days[di].contains(id) else { return }
                        var d = cur.days[di]; d.insert(id, at: min(k, d.count)); cur.days[di] = d; cur.spare.removeAll { $0 == id }; plan = cur; amsg = nil; undo = nil
                    }
                }.environmentObject(m)
            }
        }
    }

    private var choices: some View {
        let dn = Int(days) ?? 5
        let sum = [to.isEmpty ? t("Choose a person", "Chagua mtu") : to, countryName(ck) ?? ck, areas.isEmpty ? t("all areas", "maeneo yote") : planAreasText(areas, ck), dn == 1 ? t("1 day", "siku 1") : t("\(dn) days", "siku \(dn)"), t("\(per) a day", "\(per) kwa siku"),
                   pick.isEmpty ? "" : onlyPick ? t("only the \(pick.count) chosen", "zilizochaguliwa \(pick.count) tu") : t("\(pick.count) chosen", "\(pick.count) zimechaguliwa")].filter { !$0.isEmpty }.joined(separator: " · ")
        return CardV(pad: 4) {
            DqmFold(icon: "cog", hue: "blue", title: t("Choices", "Machaguo"), summary: sum, tone: "has", startOpen: choicesOpen) {
                HStack(alignment: .bottom, spacing: 10) {
                    Picker1(label: t("Field officer", "Afisa wa uwanjani"), value: to, options: [("", t("Choose", "Chagua"))] + names.map { ($0, $0) }) { to = $0 }
                    Picker1(label: t("Country", "Nchi"), value: ck, options: COUNTRIES) { if $0 != ck { ck = $0; areas = []; pick = [] } }
                }
                if !to.isEmpty { PersonChip(name: to).padding(.top, 6) }
                VStack(alignment: .leading, spacing: 6) {
                    Text(t("Area", "Eneo")).font(.mont(13, .bold)).foregroundColor(C.ink).padding(.top, 12)
                    Button { pickAt = "areas" } label: {
                        HStack { Text(planAreasText(areas, ck)).font(.bLarge).foregroundColor(C.text).multilineTextAlignment(.leading).padding(.vertical, 10); Spacer(); Icon("down", size: 18, color: C.muted) }
                            .padding(.horizontal, 14).frame(minHeight: 52).background(C.card).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.edge, lineWidth: 1))
                    }.buttonStyle(.plain)
                }
                HStack(alignment: .bottom, spacing: 10) {
                    Picker1(label: t("Days", "Siku"), value: days, options: (1...6).map { ("\($0)", "\($0)") }) { days = $0 }
                    Picker1(label: t("Stops a day", "Vituo kwa siku"), value: per, options: (3...8).map { ("\($0)", "\($0)") }) { per = $0 }
                }
                DateField(label: t("First day", "Siku ya kwanza"), value: start) { if !$0.isEmpty { start = $0 } }
                let on = !pick.isEmpty || !areas.isEmpty, ph = C.named("purple")
                Button { pickAt = "projects" } label: {
                    HStack(spacing: 12) {
                        IconTile(icon: "clip", tint: ph.fg, bg: ph.bg, size: 38)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(t("Areas and projects", "Maeneo na miradi")).font(.tSmall).foregroundColor(C.ink)
                            HStack(alignment: .top, spacing: 6) { Circle().fill(on ? C.blue2 : C.muted).frame(width: 7, height: 7).padding(.top, 5); Text(planScopeText(s.projects, ck, areas, pick)).font(.bSmall).foregroundColor(on ? C.ink : C.muted).multilineTextAlignment(.leading) }
                        }
                        Spacer(minLength: 6); Icon("chev", size: 18, color: C.muted)
                    }.padding(.horizontal, 12).padding(.vertical, 8).frame(minHeight: 60).overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.line, lineWidth: 1)).contentShape(Rectangle())
                }.buttonStyle(.plain).padding(.top, 14)
                if !pick.isEmpty {
                    Button { onlyPick.toggle() } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 1) { Text(t("Only these projects", "Miradi hii tu")).font(.tSmall).foregroundColor(C.ink); Text(t("No other suggestions are added", "Hakuna mapendekezo mengine yanayoongezwa")).font(.bSmall).foregroundColor(C.muted) }
                            Spacer(); Toggle("", isOn: $onlyPick).labelsHidden().tint(C.ok)
                        }.padding(.horizontal, 12).padding(.vertical, 8).overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.line, lineWidth: 1))
                    }.buttonStyle(.plain).padding(.top, 8)
                }
                Btn(t("Suggest visits", "Pendekeza ziara"), icon: "spark", enabled: !to.isEmpty && !busy, wide: true) { suggest() }.padding(.top, 14)
                if let msg { Msg(msg.1, msg.0) }
            }.id(choicesOpen)
            Rectangle().fill(C.line).frame(height: 1).padding(.horizontal, 14)
            DqmFold(icon: "info", hue: "slate", title: t("How it chooses", "Jinsi inavyochagua"), summary: t("Repairs first, then visits that are due", "Matengenezo kwanza, kisha ziara zinazohitajika"), tone: "off") {
                Text(t("Suggests visits for one person: open repairs first, then sites not visited for 6 months or more, and water tests that are due. Sites close together go on the same day, in driving order.",
                       "Inapendekeza ziara kwa mtu mmoja: matengenezo yaliyo wazi kwanza, kisha maeneo yasiyotembelewa kwa miezi 6 au zaidi, na vipimo vya maji vinavyohitajika. Maeneo yaliyo karibu yanawekwa siku moja, kwa mpangilio wa safari.")).font(.bMedium).foregroundColor(C.text)
                Text(t("Tap a stop to open the project or take it off the day. Then assign the week: each day goes to their “My work” with its date.", "Gusa kituo kufungua mradi au kukiondoa siku hiyo. Kisha gawa wiki: kila siku inaenda kwenye “Kazi yangu” yao na tarehe yake.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 8)
            }
        }.padding(.top, 12)
    }

    private func suggest() {
        Task {
            busy = true; msg = ("", t("Working it out…", "Inapanga…"))
            var q = ["ck": ck, "days": days, "per": per]
            if !areas.isEmpty { q["areas"] = areas.joined(separator: ",") }
            if !pick.isEmpty { q["ids"] = pick.joined(separator: ","); if onlyPick { q["only"] = "1" } }
            let r = await m.api.adminGet("plan", q)
            busy = false
            if !r.ok { msg = ("bad", reasonText(r.reason)); return }
            let p = parsePlan(r.json, to: to, start: DateField.parse(start) ?? Date().addingTimeInterval(86400), s)
            plan = p; day = -1; amsg = nil; undo = nil; made += 1
            msg = p.total == 0 ? ("", t("Nothing needs a visit there right now.", "Hakuna kinachohitaji ziara huko kwa sasa.")) : nil
            if p.total > 0 { withAnimation { choicesOpen = false } }
        }
    }

    @ViewBuilder private func planView(_ p: WeekPlan) -> some View {
        let n = p.days.reduce(0) { $0 + $1.count }, km = p.days.reduce(0) { $0 + planMetres($1, s) }
        CardV(pad: 16) {
            Text(t("\(n) visits over \(p.days.count) " + (p.days.count == 1 ? "day" : "days") + " · about \(Geo.dist(km, miles: m.miles))", "Ziara \(n) kwa siku \(p.days.count) · takriban \(Geo.dist(km, miles: m.miles))")).font(.tMedium).foregroundColor(C.ink)
            Text(t("For \(p.to). Straight lines between sites; roads are longer.", "Kwa \(p.to). Mistari iliyonyooka kati ya maeneo; barabara ni ndefu zaidi.") + (p.total > n ? " " + t("\(p.total - n) more sites need a visit: add them below.", "Maeneo mengine \(p.total - n) yanahitaji ziara: yaongeze hapa chini.") : "")).font(.bSmall).foregroundColor(C.muted).padding(.top, 2)
            if !p.nogps.isEmpty {
                HStack(alignment: .top, spacing: 8) { Icon("alert", size: 18, color: C.warn); Text(t("No location, so not placed: ", "Hakuna mahali, kwa hiyo haikuwekwa: ") + p.nogps.joined(separator: ", ")).font(.bSmall).foregroundColor(C.ink) }
                    .padding(12).frame(maxWidth: .infinity, alignment: .leading).background(C.warnBg).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).padding(.top, 10)
            }
            let lines = p.days.enumerated().map { i, ids in OfaLine(pts: ids.compactMap { proj($0)?.gps }, color: OFA_COLORS[i % 6], dim: day >= 0 && day != i) }
            let pins = p.days.enumerated().flatMap { i, ids in ids.enumerated().compactMap { k, id in proj(id).flatMap { pr in pr.gps.map { OfaPin(ll: $0, color: OFA_COLORS[i % 6], number: "\(k + 1)", title: pr.title, dim: day >= 0 && day != i) } } } }
            OfaMap(avatars: [], pins: pins, lines: lines, fitKey: "\(p.days)|\(day)").frame(height: 300).clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous)).padding(.top, 12)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    dayChip(t("All days", "Siku zote"), nil, day < 0) { day = -1 }
                    ForEach(p.days.indices, id: \.self) { i in dayChip(t("Day ", "Siku ") + "\(i + 1)", OFA_COLORS[i % 6], day == i) { day = i } }
                }
            }.padding(.top, 10)
        }.padding(.top, 12)
        ForEach(p.days.indices, id: \.self) { i in dayCard(p, i) }
        CardV {
            Field(t("Note for ", "Maelezo kwa ") + p.to + " " + t("(optional)", "(si lazima)"), Binding(get: { note }, set: { note = String($0.prefix(200)) }), placeholder: t("e.g. take the water test kit", "mf. beba vifaa vya kupima maji"))
            if !p.to.isEmpty { PersonChip(name: p.to).padding(.top, 10) }
            Btn(p.assigned ? t("Assigned", "Imegawiwa") : t("Assign \(n) visits to \(p.to)", "Mpe \(p.to) ziara \(n)"), icon: p.assigned ? "check" : "cal", enabled: n > 0 && !p.assigned && !busy, wide: true) { assign(p) }.padding(.top, 12)
            if let amsg { Msg(amsg.1, amsg.0) }
            if let u = undo { Button(t("Undo", "Tendua")) { Haptic.tap(); u() }.font(.mont(14, .heavy)).foregroundColor(C.blue2).padding(.top, 6) }
        }.padding(.top, 12)
    }

    private func dayCard(_ p: WeekPlan, _ i: Int) -> some View {
        let col = Color(ui(OFA_COLORS[i % 6])), ids = p.days[i], stops = ids.compactMap(proj)
        let ctr: LatLng? = stops.isEmpty ? nil : LatLng(lat: stops.reduce(0) { $0 + $1.gps!.lat } / Double(stops.count), lng: stops.reduce(0) { $0 + $1.gps!.lng } / Double(stops.count))
        return CardV(pad: 14, border: col) {
            HStack(alignment: .top, spacing: 10) {
                Circle().fill(col).frame(width: 12, height: 12).padding(.top, 6)
                VStack(alignment: .leading, spacing: 1) {
                    Text(t("Day ", "Siku ") + "\(i + 1) · " + ofaDayLabel(p.dates[i])).font(.tMedium).foregroundColor(C.ink)
                    Text((stops.count == 1 ? t("1 stop", "kituo 1") : t("\(stops.count) stops", "vituo \(stops.count)")) + (stops.count > 1 ? " · " + t("about ", "takriban ") + Geo.dist(planMetres(ids, s), miles: m.miles) : "")).font(.bSmall).foregroundColor(C.muted)
                }
            }
            ForEach(Array(stops.enumerated()), id: \.offset) { k, pr in
                if k > 0 { Rectangle().fill(C.line).frame(height: 1) }
                Button { stop = (i, pr.id) } label: {
                    HStack(spacing: 12) {
                        Text("\(k + 1)").font(.mont(12, .heavy)).foregroundColor(.white).frame(width: 28, height: 28).background(Circle().fill(col))
                        VStack(alignment: .leading, spacing: 1) {
                            Text(pr.title).font(.tSmall).foregroundColor(C.ink).multilineTextAlignment(.leading)
                            Text((pr.area.isEmpty ? pr.country : pr.area) + (k > 0 ? " · " + Geo.dist(Geo.metres(stops[k - 1].gps!, pr.gps!), miles: m.miles) + t(" from the last stop", " kutoka kituo cha mwisho") : "")).font(.bSmall).foregroundColor(C.muted)
                            let why = planWhy(p.info[pr.id], s.repairs[pr.id]?.state)
                            if !why.isEmpty { ScrollView(.horizontal, showsIndicators: false) { HStack(spacing: 4) { ForEach(Array(why.enumerated()), id: \.offset) { _, w in whyTag(w) } } }.padding(.top, 4) }
                        }
                        Spacer(minLength: 0); Icon("chev", size: 18, color: C.muted)
                    }.padding(.vertical, 8).padding(.horizontal, 2).frame(minHeight: 56).contentShape(Rectangle())
                }.buttonStyle(.plain).padding(.top, k == 0 ? 8 : 0)
            }
            if stops.isEmpty { Text(t("No stops on this day.", "Hakuna vituo siku hii.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 8) }
            if !p.assigned && !p.spare.isEmpty {
                let spare = Array(p.spare.compactMap(proj).sorted { a, b in guard let c = ctr else { return false }; return Geo.metres(c, a.gps!) < Geo.metres(c, b.gps!) }.prefix(30))
                Picker1(label: t("Add a stop", "Ongeza kituo"), value: "", options: [("", "+ " + t("Choose a site…", "Chagua eneo…"))] + spare.map { sp in (sp.id, sp.title + (ctr.map { " · " + Geo.dist(Geo.metres($0, sp.gps!), miles: m.miles) } ?? "")) }) { id in
                    guard !id.isEmpty, var cur = plan else { return }
                    cur.days[i] = planOrder(cur.days[i] + [id], s); cur.spare.removeAll { $0 == id }; plan = cur; day = i
                }
            }
            if let leg = mapsLegs(stops, from: nil).first {
                DqmLineBtn(text: t("Route in Google Maps", "Njia kwenye Google Maps"), icon: "b:gmaps") { Share.open(leg.1) }.padding(.top, 10)
            }
        }.padding(.top, 12)
    }

    private func dayChip(_ label: String, _ dot: UInt32?, _ on: Bool, _ action: @escaping () -> Void) -> some View {
        Button { Haptic.tap(); action() } label: {
            HStack(spacing: 6) { if let dot { Circle().fill(Color(ui(dot))).frame(width: 10, height: 10) }; Text(label).font(.lMedium).foregroundColor(C.ink) }
                .padding(.horizontal, 12).frame(minHeight: 36).background(on ? C.tint : C.card).clipShape(Capsule()).overlay(Capsule().stroke(on ? C.ink : C.line, lineWidth: on ? 2 : 1))
        }.buttonStyle(.plain)
    }

    private func assign(_ p: WeekPlan) {
        Task {
            busy = true; amsg = ("", t("Assigning…", "Inagawa…")); undo = nil
            var count = 0; var cur = p
            for i in p.days.indices where !cur.days[i].isEmpty {
                let r = await m.api.admin(["action": "assign", "ids": cur.days[i], "to": p.to, "date": DateField.text(p.dates[i]),
                                           "note": (note.isEmpty ? "" : note.trimmingCharacters(in: .whitespaces) + " · ") + "Week plan, day \(i + 1) of \(p.days.count)"])
                if !r.ok { busy = false; plan = cur; amsg = ("bad", reasonText(r.reason) + (count > 0 ? t(" (\(count) assigned before this.)", " (\(count) zimegawiwa kabla ya hili.)") : "")); return }
                count += (r.json["items"] as? [Any])?.count ?? cur.days[i].count
                cur.days[i] = []
            }
            busy = false; var done = p; done.assigned = true; plan = done; Haptic.ok()
            amsg = ("ok", t("Assigned ✓ \(count) visits. \(p.to) sees them in “My work” and gets a notification.", "Imegawiwa ✓ ziara \(count). \(p.to) anaziona kwenye “Kazi yangu” na anapata arifa."))
        }
    }
}

private let PICK_MAX = 60, AREA_MAX = 20, PICK_SHOW = 60

/// Plan a week › Areas and projects: tick areas (none: the whole country), and projects that must be visited.
private struct PlanPickSheet: View {
    @Environment(\.dismiss) private var dismiss
    let s: Snapshot; let ck: String
    @Binding var areas: [String]
    @Binding var pick: [String]
    let at: String
    @State private var q = ""
    @State private var only = false
    @State private var msg: String?
    var body: some View {
        let cn = countryName(ck) ?? ck
        let counts = planAreas(s.projects, ck, areas)
        let tick = Set(pick), qq = q.trimmingCharacters(in: .whitespaces).lowercased()
        let shown = s.projects.filter { p in p.ck == ck && (areas.isEmpty || areas.contains(planArea(p)) || tick.contains(p.id)) && (!only || tick.contains(p.id)) && (qq.isEmpty || "\(p.title) \(p.area)".lowercased().contains(qq)) }
        let groups = Dictionary(grouping: shown, by: planArea)
        let big = Dictionary(uniqueKeysWithValues: counts.map { ($0.0, $0.1) })
        let order = groups.keys.sorted { a, b in
            let ia = areas.firstIndex(of: a) ?? 99, ib = areas.firstIndex(of: b) ?? 99
            if ia != ib { return ia < ib }
            if a.isEmpty != b.isEmpty { return !a.isEmpty }
            if (big[a] ?? 0) != (big[b] ?? 0) { return (big[a] ?? 0) > (big[b] ?? 0) }
            return a < b
        }
        ScrollViewReader { sv in
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    DqmSheetHead(title: t("Areas and projects", "Maeneo na miradi"), sub: [t("Tick the areas to plan in, then any projects that must be visited. Those are always in the plan, grouped by distance; the other stops are suggestions.",
                                                                                          "Chagua maeneo ya kupanga, kisha miradi inayolazimika kutembelewa. Hiyo iko kwenye mpango kila wakati, kwa umbali; vituo vingine ni mapendekezo.")], close: { dismiss() }) {
                        IconTile(icon: "layers", tint: C.named("blue").fg, bg: C.named("blue").bg, size: 64)
                    }
                    HStack(spacing: 8) { Text(t("Areas", "Maeneo")).font(.tSmall).foregroundColor(C.ink); Tag(areas.isEmpty ? t("All of ", "Yote ya ") + cn : t("\(areas.count) ticked", "\(areas.count) zimechaguliwa"), bg: C.tint, fg: C.deep) }.padding(.top, 18)
                    ScrollView {
                        VStack(spacing: 0) {
                            ForEach(Array(counts.enumerated()), id: \.offset) { k, x in
                                let on = areas.contains(x.0)
                                if k > 0 { Rectangle().fill(C.line).frame(height: 1) }
                                Button { tickArea(x.0, !on) } label: {
                                    HStack(spacing: 8) {
                                        Image(systemName: on ? "checkmark.square.fill" : "square").font(.system(size: 22)).foregroundColor(on ? C.blue : C.muted)
                                        Text(x.0).font(.mont(14, .bold)).foregroundColor(C.ink).multilineTextAlignment(.leading); Spacer()
                                        Text("\(x.1)").font(.mont(12, .heavy)).foregroundColor(on ? C.card : C.ink).frame(minWidth: 30).padding(.horizontal, 8).padding(.vertical, 2).background(on ? C.blue : C.named("slate").bg).clipShape(Capsule())
                                    }.padding(.leading, 10).padding(.trailing, 12).frame(minHeight: 48).background(on ? C.tint : .clear).contentShape(Rectangle())
                                }.buttonStyle(.plain)
                            }
                            if counts.isEmpty { Text(t("No areas in \(cn) yet.", "Bado hakuna maeneo \(cn).")).foregroundColor(C.muted).padding(12) }
                        }
                    }.frame(maxHeight: 264).overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(C.line, lineWidth: 1)).padding(.top, 8)
                    if areas.isEmpty { Text(t("None ticked: projects from all of \(cn).", "Hakuna iliyochaguliwa: miradi kutoka \(cn) yote.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 6) }
                    HStack(spacing: 8) {
                        Text(areas.isEmpty ? t("Projects in \(cn)", "Miradi ya \(cn)") : t("Projects in these areas", "Miradi katika maeneo haya")).font(.tSmall).foregroundColor(C.ink)
                        Tag(t("\(pick.count) chosen", "\(pick.count) zimechaguliwa"), bg: C.tint, fg: C.deep)
                    }.padding(.top, 18).id("projects")
                    HStack(alignment: .bottom, spacing: 8) {
                        Field(t("Search", "Tafuta"), $q, placeholder: t("Name or place", "Jina au mahali"))
                        pill(t("Ticked only", "Zilizochaguliwa tu"), on: only) { only.toggle() }.padding(.bottom, 4)
                    }
                    let (shownGroups, _) = order.reduce(into: ([(String, [Project], [Project])](), PICK_SHOW)) { acc, a in
                        guard acc.1 > 0 else { return }
                        let all = groups[a]!.sorted { $0.title < $1.title }; let ps = Array(all.prefix(acc.1)); acc.1 -= ps.count; acc.0.append((a, all, ps))
                    }
                    ForEach(Array(shownGroups.enumerated()), id: \.offset) { _, g in
                        let k = g.1.filter { tick.contains($0.id) }.count
                        VStack(spacing: 0) {
                            HStack(alignment: .center) {
                                VStack(alignment: .leading, spacing: 1) { Text(g.0.isEmpty ? t("No area", "Hakuna eneo") : g.0).font(.tSmall).foregroundColor(C.ink); Text(k > 0 ? t("\(k) of \(g.1.count) chosen", "\(k) kati ya \(g.1.count) zimechaguliwa") : projN(g.1.count)).font(.bSmall).foregroundColor(C.muted) }
                                Spacer(minLength: 6)
                                pill(t("Select all", "Chagua zote"), on: false, enabled: k < g.1.count) { msg = nil; for p in g.1 { if !tickProject(p.id, true) { break } } }
                                pill(t("Clear", "Futa"), on: false, enabled: k > 0) { msg = nil; for p in g.1 { _ = tickProject(p.id, false) } }
                            }.padding(.horizontal, 12).padding(.vertical, 8).background(C.tint)
                            ForEach(g.2) { p in
                                let on = tick.contains(p.id)
                                Rectangle().fill(C.line).frame(height: 1)
                                Button { msg = nil; _ = tickProject(p.id, !on) } label: {
                                    HStack(spacing: 8) {
                                        Image(systemName: on ? "checkmark.square.fill" : "square").font(.system(size: 22)).foregroundColor(on ? C.blue : C.muted)
                                        VStack(alignment: .leading, spacing: 1) {
                                            Text(p.title).font(.mont(14, .bold)).foregroundColor(C.ink).multilineTextAlignment(.leading)
                                            Text([p.type, p.area].filter { !$0.isEmpty }.joined(separator: " · ")).font(.bSmall).foregroundColor(C.muted)
                                            if p.gps == nil { Text(t("No location", "Hakuna mahali")).font(.mont(12, .bold)).foregroundColor(C.warn) }
                                        }
                                        Spacer(minLength: 0)
                                    }.padding(.leading, 10).padding(.trailing, 12).padding(.vertical, 4).frame(minHeight: 56).contentShape(Rectangle())
                                }.buttonStyle(.plain)
                            }
                        }.clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.line, lineWidth: 1)).padding(.top, 10)
                    }
                    if shown.isEmpty { Text(only ? t("No projects ticked yet.", "Bado hakuna mradi uliochaguliwa.") : t("No projects match.", "Hakuna mradi unaolingana.")).foregroundColor(C.muted).padding(.top, 12) }
                    if shown.count > PICK_SHOW { Text(t("…and \(shown.count - PICK_SHOW) more. Search or tick an area to narrow it down.", "…na \(shown.count - PICK_SHOW) zaidi. Tafuta au chagua eneo ili kupunguza.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 10) }
                    Msg(msg, "warn")
                    Btn(t("Done", "Imekamilika"), icon: "check", wide: true) { dismiss() }.padding(.top, 14)
                    DqmLineBtn(text: t("Clear all", "Ondoa zote"), icon: "x") { areas = []; pick = []; only = false; msg = nil }.padding(.top, 8)
                }.padding(.horizontal, 20).padding(.top, 20).padding(.bottom, 28)
            }
            .onAppear { if at == "projects" && !areas.isEmpty { DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { withAnimation { sv.scrollTo("projects", anchor: .top) } } } }
        }.background(C.card.ignoresSafeArea())
    }
    private func tickArea(_ a: String, _ on: Bool) {
        msg = nil
        if on && !areas.contains(a) {
            var add = [a]
            if areas.isEmpty { for id in pick { if let p = s.projects.first(where: { $0.id == id }) { let x = planArea(p); if !x.isEmpty && !add.contains(x) { add.append(x) } } } }
            if areas.count + add.count > AREA_MAX { msg = t("You can tick up to \(AREA_MAX) areas.", "Unaweza kuchagua hadi maeneo \(AREA_MAX)."); return }
            areas += add
        } else if !on {
            areas.removeAll { $0 == a }
            pick.removeAll { id in s.projects.first { $0.id == id }.map(planArea) == a }
        }
    }
    private func tickProject(_ id: String, _ on: Bool) -> Bool {
        if on && !pick.contains(id) { if pick.count >= PICK_MAX { msg = t("You can choose up to \(PICK_MAX) projects.", "Unaweza kuchagua hadi miradi \(PICK_MAX)."); return false }; pick.append(id) }
        if !on { pick.removeAll { $0 == id } }
        return true
    }
    private func pill(_ text: String, on: Bool, enabled: Bool = true, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text).font(.mont(14, .bold)).foregroundColor(on ? C.card : enabled ? C.ink : C.muted.opacity(0.55)).lineLimit(1).padding(.horizontal, 14).frame(minHeight: 44)
                .background(on ? C.blue : C.card).clipShape(Capsule()).overlay(Capsule().stroke(on ? .clear : enabled ? C.edge : C.line, lineWidth: 1))
        }.buttonStyle(.plain).disabled(!enabled)
    }
}

/// A stop: why it's suggested, which day, how far from the last stop; open the project or take it off the day.
private struct StopSheet: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.dismiss) private var dismiss
    let p: WeekPlan; let di: Int; let k: Int; let pr: Project; let s: Snapshot; let openIt: () -> Void; let remove: () -> Void
    var body: some View {
        let col = Color(ui(OFA_COLORS[di % 6])), d = p.days[di]
        let prev = k > 0 ? s.projects.first { $0.id == d[k - 1] } : nil
        let why = planWhy(p.info[pr.id], s.repairs[pr.id]?.state)
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                DqmSheetHead(title: pr.title, sub: [[pr.area, pr.country].filter { !$0.isEmpty }.joined(separator: ", ")], close: { dismiss() }) {
                    Text("\(k + 1)").font(.mont(24, .heavy)).foregroundColor(.white).frame(width: 64, height: 64).background(Circle().fill(col))
                }
                if !why.isEmpty { HStack(spacing: 4) { ForEach(Array(why.enumerated()), id: \.offset) { _, w in whyTag(w) } }.frame(maxWidth: .infinity).padding(.top, 10) }
                VStack(spacing: 0) {
                    DqmFact(label: t("Day", "Siku"), value: t("Day ", "Siku ") + "\(di + 1) · " + ofaDayLabel(p.dates[di]), sub: t("Stop \(k + 1) of \(d.count)", "Kituo \(k + 1) kati ya \(d.count)"))
                    if let prev, let a = prev.gps, let b = pr.gps { DqmFact(label: t("From the last stop", "Kutoka kituo cha mwisho"), value: Geo.dist(Geo.metres(a, b), miles: m.miles), sub: prev.title) }
                    if !pr.type.isEmpty { DqmFact(label: t("Type", "Aina"), value: pr.type) }
                }.padding(.top, 12)
                Btn(t("Open project", "Fungua mradi"), icon: "chev", wide: true) { dismiss(); openIt() }.padding(.top, 16)
                if !p.assigned { DqmLineBtn(text: t("Take it off day \(di + 1)", "Iondoe siku ya \(di + 1)"), icon: "trash", red: true) { dismiss(); remove() }.padding(.top, 8) }
            }.padding(.horizontal, 20).padding(.top, 20).padding(.bottom, 28)
        }.background(C.card.ignoresSafeArea()).presentationDetents([.medium, .large])
    }
}

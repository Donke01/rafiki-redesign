import MapKit
import SwiftUI

// Office › Team map, Share my location (the settings sheet and the "Sharing location with the office · Stop" bar),
// and Office › Plan a week (Android TeamMap.kt, data/ShareHere.kt). Same server: POST /api/pin { action: "here" },
// GET /api/admin?view=teammap, GET /api/admin?view=plan, POST "assign".

private let OFA_COLORS: [UInt32] = [0x0A78C2, 0x1C9C5A, 0xD98200, 0x7A4FD0, 0xD23C32, 0x0E8F96]
private func ofaColor(_ i: Int) -> Color { Color(hex: OFA_COLORS[i % OFA_COLORS.count]) }
private func stateColor(_ s: String) -> UIColor { UIColor(Color(hex: s == "notworking" ? 0xB3261E : s == "repair" ? 0xD98200 : 0x1C9C5A)) }

// --- Share my location ------------------------------------------------------------------------------------------

/// Me › Settings › Share my location with the office. Off unless turned on; only in the chosen work hours (Mon–Sat
/// 07:00–18:00 to start with); at most every 10 minutes; only while the app is open. The server keeps the latest point
/// for 12 hours and only admins see it on the Team map. The settings stay on this phone.
enum ShareHere {
    struct Hours: Equatable { var on = false; var days: Set<Int> = [1, 2, 3, 4, 5, 6]; var from = 7 * 60; var to = 18 * 60 }
    static let gap: TimeInterval = 10 * 60
    private static let d = UserDefaults.standard
    static func load() -> Hours {
        let days = (d.string(forKey: "share-days") ?? "1,2,3,4,5,6").split(separator: ",").compactMap { Int($0.trimmingCharacters(in: .whitespaces)) }.filter { (1...7).contains($0) }
        return Hours(on: d.bool(forKey: "share-on"), days: Set(days), from: d.object(forKey: "share-from") == nil ? 420 : min(1439, max(0, d.integer(forKey: "share-from"))),
                     to: d.object(forKey: "share-to") == nil ? 1080 : min(1439, max(0, d.integer(forKey: "share-to"))))
    }
    static func save(_ h: Hours) {
        d.set(h.on, forKey: "share-on"); d.set(h.days.sorted().map(String.init).joined(separator: ","), forKey: "share-days"); d.set(h.from, forKey: "share-from"); d.set(h.to, forKey: "share-to")
        if !h.on { lastSent = 0 }
        NotificationCenter.default.post(name: .shareHereChanged, object: nil)
    }
    static var lastSent: TimeInterval { get { d.double(forKey: "share-sent") } set { d.set(newValue, forKey: "share-sent") } }
    /// 1 = Monday … 7 = Sunday.
    static func weekday(_ date: Date = Date()) -> Int { let w = Calendar(identifier: .gregorian).component(.weekday, from: date); return w == 1 ? 7 : w - 1 }
    static func inHours(_ h: Hours, _ now: Date = Date()) -> Bool {
        let c = Calendar.current.dateComponents([.hour, .minute], from: now); let mm = (c.hour ?? 0) * 60 + (c.minute ?? 0)
        guard h.days.contains(weekday(now)) else { return false }
        return h.from <= h.to ? (mm >= h.from && mm < h.to) : (mm >= h.from || mm < h.to)
    }
    static func hhmm(_ min: Int) -> String { String(format: "%02d:%02d", min / 60, min % 60) }
    private static let names = [("Mon", "Jtt"), ("Tue", "Jnn"), ("Wed", "Jtn"), ("Thu", "Alh"), ("Fri", "Ijm"), ("Sat", "Jms"), ("Sun", "Jpl")]
    static func dayName(_ d: Int) -> String { let x = names[max(0, min(6, d - 1))]; return t(x.0, x.1) }
    /// "Mon–Sat · 07:00–18:00"
    static func hoursText(_ h: Hours) -> String {
        let ds = h.days.sorted()
        let run = ds.count > 2 && zip(ds, ds.dropFirst()).allSatisfy { $1 == $0 + 1 }
        let dd = ds.isEmpty ? t("No days", "Hakuna siku") : run ? dayName(ds[0]) + "–" + dayName(ds[ds.count - 1]) : ds.map(dayName).joined(separator: ", ")
        return dd + " · " + hhmm(h.from) + "–" + hhmm(h.to)
    }
    /// Sharing right now: turned on, signed in with their own name (not the shared team PIN), and in work hours.
    static func active(_ h: Hours, own: Bool) -> Bool { h.on && own && inHours(h) }
    /// Sends where the phone is, if sharing and due. Returns true when a point went out.
    @MainActor static func maybeSend(_ m: AppModel) async -> Bool {
        let own = !(m.snap?.who ?? "").isEmpty
        guard let f = m.fix, active(load(), own: own), Date().timeIntervalSince1970 - lastSent >= gap else { return false }
        lastSent = Date().timeIntervalSince1970
        return await m.api.post(["action": "here", "lat": f.ll.lat, "lng": f.ll.lng, "acc": f.acc]).ok
    }
    /// Turned off: the server forgets the point straight away.
    @MainActor static func stop(_ m: AppModel) async {
        var h = load(); h.on = false; save(h)
        _ = await m.api.post(["action": "here", "off": true])
    }
}
extension Notification.Name { static let shareHereChanged = Notification.Name("rafiki.shareHere") }

/// "Sharing location with the office · Stop", under the app header while sharing; it also sends the point.
struct SharingBar: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject private var loc = Locator.shared
    @State private var on = false
    @State private var pulse = false
    private let tick = Timer.publish(every: 60, on: .main, in: .common).autoconnect()
    func check() { on = ShareHere.active(ShareHere.load(), own: !(m.snap?.who ?? "").isEmpty); if on { Task { _ = await ShareHere.maybeSend(m) } } }
    var body: some View {
        Group {
            if on {
                HStack(spacing: 8) {
                    ZStack {
                        Circle().stroke(C.ok, lineWidth: 2).frame(width: 20, height: 20).scaleEffect(pulse ? 1.45 : 0.45).opacity(pulse ? 0 : 0.85)
                        Circle().fill(C.ok).frame(width: 9, height: 9)
                    }.frame(width: 20, height: 20)
                    Text(t("Sharing location with the office", "Unashiriki mahali na ofisi")).font(.mont(13, .heavy)).foregroundColor(C.ok).lineLimit(1).minimumScaleFactor(0.75)
                    Button { Task { await ShareHere.stop(m); check() } } label: {
                        Text(t("Stop", "Acha")).font(.mont(13, .heavy)).foregroundColor(C.ink).padding(.horizontal, 14).frame(minHeight: 32).background(C.card).clipShape(Capsule())
                    }.buttonStyle(.plain).frame(minHeight: 44)
                }
                .padding(.leading, 12).padding(.trailing, 4).background(C.okBg).clipShape(Capsule()).overlay(Capsule().stroke(C.line, lineWidth: 1))
                .padding(.horizontal, 12).padding(.vertical, 2)
                .transition(.opacity.combined(with: .move(edge: .top)))
                .onAppear { if Motion.on { withAnimation(.easeOut(duration: 2.2).repeatForever(autoreverses: false)) { pulse = true } } }
            }
        }
        .animation(Motion.on ? .easeInOut(duration: 0.25) : nil, value: on)
        .onAppear(perform: check)
        .onReceive(tick) { _ in check() }
        .onReceive(NotificationCenter.default.publisher(for: .shareHereChanged)) { _ in check() }
        .onChange(of: loc.fix?.at) { _ in if on { Task { _ = await ShareHere.maybeSend(m) } } }
    }
}

/// The row for Me › Settings: "Share my location with the office · On · Mon–Sat 07:00–18:00"; opens the settings sheet.
struct ShareRow: View {
    @State private var h = ShareHere.load()
    @State private var open = false
    var body: some View {
        let hu = ohue("green")
        Button { open = true } label: {
            HStack(spacing: 12) {
                OTileIcon(icon: "pin", tint: hu.fg, bg: hu.bg, size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(t("Share my location with the office", "Shiriki mahali nilipo na ofisi")).font(.tSmall).foregroundColor(C.ink).multilineTextAlignment(.leading)
                    Text(h.on ? t("On · ", "Imewashwa · ") + ShareHere.hoursText(h) : t("Off", "Imezimwa")).font(.bSmall).foregroundColor(h.on ? C.ok : C.muted).lineLimit(1)
                }.frame(maxWidth: .infinity, alignment: .leading)
                Icon("chev", size: 18, color: C.muted)
            }.padding(.vertical, 10).padding(.horizontal, 2).contentShape(Rectangle())
        }.buttonStyle(.plain)
        .sheet(isPresented: $open) { ShareSettingsSheet { open = false } }
        .onReceive(NotificationCenter.default.publisher(for: .shareHereChanged)) { _ in h = ShareHere.load() }
    }
}

/// Share my location: what it does, on or off, work days and hours, and what it is doing now.
struct ShareSettingsSheet: View {
    @EnvironmentObject var m: AppModel
    let close: () -> Void
    @State private var h = ShareHere.load()
    @State private var note: (String, String)?
    var body: some View {
        let own = !(m.snap?.who ?? "").isEmpty
        let hu = ohue("green")
        let times = (10...44).map { ShareHere.hhmm($0 * 30) }
        OSheet {
            HStack { Text(t("Share my location", "Shiriki mahali nilipo")).font(.hMedium).foregroundColor(C.ink).frame(maxWidth: .infinity, alignment: .leading); OCloseX(action: close) }
            Text(t("While you work, the office can see where you are on a map, to plan visits and keep the team safe. Only admins see it. Only your latest position is kept, for 12 hours, never a history. It is off unless you turn it on, and the app never asks to track you in the background.",
                   "Unapofanya kazi, ofisi inaweza kuona ulipo kwenye ramani, ili kupanga ziara na kuilinda timu. Wasimamizi pekee wanaona. Mahali pako pa mwisho tu panahifadhiwa, kwa saa 12, si historia. Imezimwa usipoiwasha, na programu haiombi kukufuatilia chinichini."))
                .font(.bMedium).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true).padding(.top, 10)
            if !own {
                Msg(t("You need your own PIN (or monday.com sign-in) to share your location, so the office knows it’s you.", "Unahitaji PIN yako mwenyewe (au kuingia kwa monday.com) ili kushiriki mahali, ili ofisi ijue ni wewe."), "warn")
            } else {
                HStack(spacing: 12) {
                    OTileIcon(icon: "pin", tint: hu.fg, bg: hu.bg, size: 40)
                    Text(t("Share my location with the office during work hours", "Shiriki mahali nilipo na ofisi wakati wa saa za kazi")).font(.tSmall).foregroundColor(C.ink).frame(maxWidth: .infinity, alignment: .leading)
                    Toggle("", isOn: Binding(get: { h.on }, set: { v in
                        if !v { Task { await ShareHere.stop(m); h = ShareHere.load() } }
                        else if Locator.shared.denied { note = ("bad", t("Allow location for RAFIKI Pin first, then turn it on again.", "Ruhusu mahali kwa RAFIKI Pin kwanza, kisha uiwashe tena.")) }
                        else { m.locator.start(); h.on = true; ShareHere.save(h); note = nil }
                    })).labelsHidden().tint(C.ok)
                }.padding(.top, 14)
                Text(t("Work days", "Siku za kazi")).font(.mont(13, .bold)).foregroundColor(C.ink).padding(.top, 16).padding(.bottom, 6)
                HStack(spacing: 4) {
                    ForEach(1...7, id: \.self) { d in
                        let sel = h.days.contains(d)
                        Button { if sel { h.days.remove(d) } else { h.days.insert(d) }; ShareHere.save(h) } label: {
                            Text(ShareHere.dayName(d)).font(.mont(12, .bold)).foregroundColor(sel ? .white : C.ink).lineLimit(1).minimumScaleFactor(0.7)
                                .frame(maxWidth: .infinity, minHeight: 40).background(sel ? C.blue : C.card).clipShape(Capsule()).overlay(Capsule().stroke(sel ? C.blue : C.edge, lineWidth: 1))
                        }.buttonStyle(.plain)
                    }
                }
                HStack(alignment: .bottom, spacing: 10) {
                    Picker1(label: t("From", "Kuanzia"), value: ShareHere.hhmm(h.from), options: times.map { ($0, $0) }) { v in h.from = (Int(v.prefix(2)) ?? 7) * 60 + (Int(v.suffix(2)) ?? 0); ShareHere.save(h) }
                    Picker1(label: t("Until", "Hadi"), value: ShareHere.hhmm(h.to), options: times.map { ($0, $0) }) { v in h.to = (Int(v.prefix(2)) ?? 18) * 60 + (Int(v.suffix(2)) ?? 0); ShareHere.save(h) }
                }
                let sent = ShareHere.lastSent
                let st: (String, String) = !h.on ? ("", t("Off. The office can’t see where you are.", "Imezimwa. Ofisi haioni ulipo.")) :
                    h.days.isEmpty ? ("warn", t("Choose at least one work day.", "Chagua angalau siku moja ya kazi.")) :
                    ShareHere.inHours(h) ? ("ok", t("Sharing now", "Inashiriki sasa") + (sent > 0 ? " · " + t("last sent ", "ilitumwa ") + When.text(When.iso.string(from: Date(timeIntervalSince1970: sent))) : "")) :
                    ("", t("Outside your work hours: not sharing now.", "Nje ya saa zako za kazi: haishiriki sasa."))
                Msg((note ?? st).1, (note ?? st).0)
            }
        }
    }
}

// --- the map ----------------------------------------------------------------------------------------------------

struct OMapAvatar: Equatable { let key: String; let ll: LatLng; let color: Color; let label: String; let faded: Bool }
struct OMapPin: Equatable { let ll: LatLng; let color: UIColor; let number: String?; let title: String; var dim = false }
struct OMapLine: Equatable { let pts: [LatLng]; let color: UIColor; let dim: Bool }

private final class OAnn: NSObject, MKAnnotation {
    let coordinate: CLLocationCoordinate2D; let image: UIImage; let key: String?; let anchorY: CGFloat; let z: Float
    init(_ ll: LatLng, _ image: UIImage, key: String?, anchorY: CGFloat = 0.5, z: Float = 0) {
        coordinate = CLLocationCoordinate2D(latitude: ll.lat, longitude: ll.lng); self.image = image; self.key = key; self.anchorY = anchorY; self.z = z
    }
}
private final class OLine: MKPolyline { var color: UIColor = .systemBlue; var dim = false }


private func avatarImage(_ a: OMapAvatar) -> UIImage {
    let font = UIFont.boldSystemFont(ofSize: 10)
    let lw = (a.label as NSString).size(withAttributes: [.font: font]).width + 12
    let w = max(64, lw + 4), h: CGFloat = 60, r: CGFloat = 20, cx = w / 2, cy = r + 2
    return UIGraphicsImageRenderer(size: CGSize(width: w, height: h)).image { ctx in
        let c = ctx.cgContext
        c.setFillColor(UIColor.black.withAlphaComponent(0.33).cgColor); c.fillEllipse(in: CGRect(x: cx - r, y: cy - r + 1.5, width: 2 * r, height: 2 * r))
        c.setFillColor(UIColor.white.cgColor); c.fillEllipse(in: CGRect(x: cx - r, y: cy - r, width: 2 * r, height: 2 * r))
        c.setFillColor(UIColor(a.color).withAlphaComponent(a.faded ? 0.6 : 1).cgColor); c.fillEllipse(in: CGRect(x: cx - r + 3, y: cy - r + 3, width: 2 * r - 6, height: 2 * r - 6))
        let ini = initialsOf(a.key) as NSString
        let ia: [NSAttributedString.Key: Any] = [.font: UIFont.boldSystemFont(ofSize: 13), .foregroundColor: UIColor.white]
        let isz = ini.size(withAttributes: ia); ini.draw(at: CGPoint(x: cx - isz.width / 2, y: cy - isz.height / 2), withAttributes: ia)
        let top = cy + r + 2
        let pill = UIBezierPath(roundedRect: CGRect(x: cx - lw / 2, y: top, width: lw, height: 16), cornerRadius: 8)
        c.setFillColor(UIColor.white.cgColor); c.addPath(pill.cgPath); c.fillPath()
        let la: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: UIColor(red: 0x04 / 255, green: 0x26 / 255, blue: 0x3F / 255, alpha: 1)]
        let lsz = (a.label as NSString).size(withAttributes: la); (a.label as NSString).draw(at: CGPoint(x: cx - lsz.width / 2, y: top + 8 - lsz.height / 2), withAttributes: la)
    }
}
private func pinImage(_ p: OMapPin) -> UIImage {
    let s: CGFloat = p.number != nil ? 26 : 16
    return UIGraphicsImageRenderer(size: CGSize(width: s, height: s)).image { ctx in
        let c = ctx.cgContext; let a: CGFloat = p.dim ? 0.35 : 1
        c.setFillColor(UIColor.white.withAlphaComponent(a).cgColor); c.fillEllipse(in: CGRect(x: 0, y: 0, width: s, height: s))
        c.setFillColor(p.color.withAlphaComponent(a).cgColor); c.fillEllipse(in: CGRect(x: 2.2, y: 2.2, width: s - 4.4, height: s - 4.4))
        if let n = p.number {
            let at: [NSAttributedString.Key: Any] = [.font: UIFont.boldSystemFont(ofSize: 11), .foregroundColor: UIColor.white.withAlphaComponent(a)]
            let sz = (n as NSString).size(withAttributes: at); (n as NSString).draw(at: CGPoint(x: s / 2 - sz.width / 2, y: s / 2 - sz.height / 2), withAttributes: at)
        }
    }
}

/// A map with teammates (initials, last seen), small pins and route lines. Fits to `focus` (or everything) when `fitKey` changes.
struct OfficeMap: UIViewRepresentable {
    let avatars: [OMapAvatar]; let pins: [OMapPin]; let lines: [OMapLine]; var fitKey = ""; var focus: [LatLng]? = nil; var onAvatar: (String) -> Void = { _ in }
    func makeCoordinator() -> Coord { Coord(self) }
    func makeUIView(context: Context) -> MKMapView {
        let mv = MKMapView(); mv.delegate = context.coordinator; mv.pointOfInterestFilter = .excludingAll; mv.showsUserLocation = false
        mv.setRegion(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: 0.5, longitude: 35.3), span: MKCoordinateSpan(latitudeDelta: 6, longitudeDelta: 6)), animated: false)
        return mv
    }
    func updateUIView(_ mv: MKMapView, context: Context) {
        let co = context.coordinator; co.parent = self
        if co.avatars != avatars || co.pins != pins || co.lines != lines {
            co.avatars = avatars; co.pins = pins; co.lines = lines
            mv.removeAnnotations(mv.annotations); mv.removeOverlays(mv.overlays)
            for l in lines where l.pts.count > 1 {
                var cs = l.pts.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lng) }
                let pl = OLine(coordinates: &cs, count: cs.count); pl.color = l.color; pl.dim = l.dim; mv.addOverlay(pl)
            }
            mv.addAnnotations(pins.sorted { $0.dim && !$1.dim }.map { OAnn($0.ll, pinImage($0), key: nil, z: $0.dim ? 0 : 1) })
            mv.addAnnotations(avatars.map { OAnn($0.ll, avatarImage($0), key: $0.key, anchorY: 22.0 / 60.0, z: 2) })
        }
        if co.fitted != fitKey {
            co.fitted = fitKey
            let pts = (focus?.isEmpty == false ? focus ?? [] : (avatars.map(\.ll) + pins.filter { !$0.dim }.map(\.ll)))
            let all = pts.isEmpty ? pins.map(\.ll) : pts
            DispatchQueue.main.async {
                if all.count == 1 { mv.setRegion(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: all[0].lat, longitude: all[0].lng), latitudinalMeters: 4000, longitudinalMeters: 4000), animated: Motion.on) }
                else if all.count > 1 {
                    var r = MKMapRect.null
                    for p in all { let mp = MKMapPoint(CLLocationCoordinate2D(latitude: p.lat, longitude: p.lng)); r = r.union(MKMapRect(x: mp.x, y: mp.y, width: 1, height: 1)) }
                    mv.setVisibleMapRect(r, edgePadding: UIEdgeInsets(top: 50, left: 40, bottom: 50, right: 40), animated: Motion.on)
                }
            }
        }
    }
    final class Coord: NSObject, MKMapViewDelegate {
        var parent: OfficeMap; var avatars: [OMapAvatar] = []; var pins: [OMapPin] = []; var lines: [OMapLine] = []; var fitted = "\u{0}"
        init(_ p: OfficeMap) { parent = p }
        func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
            guard let a = annotation as? OAnn else { return nil }
            let v = MKAnnotationView(annotation: a, reuseIdentifier: nil)
            v.image = a.image; v.centerOffset = CGPoint(x: 0, y: a.image.size.height * (0.5 - a.anchorY)); v.zPriority = MKAnnotationViewZPriority(rawValue: a.z)
            v.canShowCallout = false
            return v
        }
        func mapView(_ mapView: MKMapView, didSelect view: MKAnnotationView) {
            if let a = view.annotation as? OAnn { mapView.deselectAnnotation(a, animated: false); if let k = a.key { parent.onAvatar(k) } }
        }
        func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            guard let l = overlay as? OLine else { return MKOverlayRenderer(overlay: overlay) }
            let r = MKPolylineRenderer(polyline: l); r.strokeColor = l.color.withAlphaComponent(l.dim ? 0.27 : 0.86); r.lineWidth = 4; r.lineCap = .round; return r
        }
    }
}

// --- Office › Team map ----------------------------------------------------------------------------------------

private struct Sharer { let name: String; let ll: LatLng; let acc: Int; let at: String; let phone: String; let home: String }
private struct DayVisit { let id: String; let by: String; let at: String; let state: String; let title: String }
private func isOld(_ iso: String) -> Bool { When.parse(iso).map { Date().timeIntervalSince($0) > 3600 } ?? false }
private func agoText(_ iso: String) -> String {
    guard let d = When.parse(iso) else { return t("never", "bado") }
    let mm = Int(Date().timeIntervalSince(d) / 60)
    if mm < 1 { return t("just now", "sasa hivi") }
    if mm < 60 { return t("\(mm) min ago", "dakika \(mm) zilizopita") }
    if mm < 1440 { return t("\(mm / 60) h ago", "saa \(mm / 60) zilizopita") }
    return mm < 2880 ? t("yesterday", "jana") : t("\(mm / 1440) days ago", "siku \(mm / 1440) zilizopita")
}
private func accText(_ acc: Int) -> String { acc > 0 ? " · ±" + Geo.dist(Double(acc), miles: false) : "" }

/// The map first, then who is sharing as a compact list; tap a person for their sheet.
struct OfficeTeamMap: View {
    @EnvironmentObject var m: AppModel
    let s: Snapshot
    @State private var people: [Sharer]?
    @State private var visits: [DayVisit] = []
    @State private var err: String?
    @State private var who = ""

    func load() async {
        let r = await m.api.adminGet("teammap")
        guard r.ok else { err = reasonText(r.reason); return }
        people = r.json.objs("people").filter { $0["lat"] != nil && $0["lng"] != nil }.map { Sharer(name: $0.str("name"), ll: LatLng(lat: $0.num("lat") ?? 0, lng: $0.num("lng") ?? 0), acc: $0.int("acc"), at: $0.str("at"), phone: $0.str("phone"), home: $0.str("home")) }
        visits = r.json.objs("visits").map { DayVisit(id: $0.str("id"), by: $0.str("by"), at: $0.str("at"), state: $0.str("s"), title: $0.str("t")) }
        err = nil
    }

    var body: some View {
        let nw = visits.filter { $0.state == "notworking" }.count
        VStack(alignment: .leading, spacing: 0) {
            OHead(icon: "pin", hue: "green", title: t("Team map", "Ramani ya timu"), line: t("Where teammates who share their location are, and today’s visits.", "Wenzako wanaoshiriki mahali walipo, na ziara za leo.")) {
                if let p = people {
                    ONums(items: [ONum(value: "\(p.count)", label: t("sharing now", "wanashiriki sasa"), color: p.isEmpty ? C.muted : C.ok),
                                  ONum(value: "\(visits.count)", label: visits.count == 1 ? t("visit today", "ziara ya leo") : t("visits today", "ziara za leo")),
                                  ONum(value: "\(nw)", label: t("not working", "hazifanyi kazi"), color: nw > 0 ? C.bad : C.muted)])
                }
                Btn(t("Refresh", "Onyesha upya"), icon: "refresh", light: true, small: true) { Task { await load() } }.padding(.top, 12)
            }
            if let p = people { content(p) } else { Text(err ?? t("Loading…", "Inapakia…")).font(.bMedium).foregroundColor(err != nil ? C.bad : C.muted).padding(.top, 12) }
        }
        .task { await load() }
    }

    @ViewBuilder fileprivate func content(_ ps: [Sharer]) -> some View {
        let colors = Dictionary(ps.enumerated().map { ($0.element.name, ofaColor($0.offset)) }, uniquingKeysWith: { a, _ in a })
        let avatars = ps.map { OMapAvatar(key: $0.name, ll: $0.ll, color: colors[$0.name] ?? C.blue, label: agoText($0.at), faded: isOld($0.at)) }
        let pins = visits.compactMap { v in s.projects.first { $0.id == v.id }?.gps.map { OMapPin(ll: $0, color: stateColor(v.state), number: nil, title: v.title) } }
        let focus: [LatLng]? = who.isEmpty ? nil : ps.filter { $0.name == who }.map(\.ll) + visits.filter { $0.by.lowercased() == who.lowercased() }.compactMap { v in s.projects.first { $0.id == v.id }?.gps }
        OfficeMap(avatars: avatars, pins: pins, lines: [], fitKey: "\(ps.count)|\(visits.count)|\(who)", focus: focus) { k in who = k; officeOpenPerson(k) }
            .frame(height: 340).clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(C.line, lineWidth: 1)).padding(.top, 12)
        HStack(spacing: 12) {
            legend(Color(hex: 0x1C9C5A), t("Visit today: working", "Ziara ya leo: inafanya kazi")); legend(Color(hex: 0xD98200), t("needs repair", "inahitaji matengenezo")); legend(Color(hex: 0xB3261E), t("not working", "haifanyi kazi"))
        }.padding(.top, 8).padding(.leading, 4)
        Text((ps.isEmpty ? t("Nobody is sharing their location right now.", "Hakuna anayeshiriki mahali kwa sasa.") : ps.count == 1 ? t("1 teammate sharing their location", "Mwenzako 1 anashiriki mahali") : t("\(ps.count) teammates sharing their location", "Wenzako \(ps.count) wanashiriki mahali"))
             + " · " + (visits.count == 1 ? t("1 visit today", "ziara 1 leo") : t("\(visits.count) visits today", "ziara \(visits.count) leo")))
            .font(.tSmall).foregroundColor(C.ink).padding(.top, 16).padding(.leading, 4)
        ForEach(Array(ps.enumerated()), id: \.offset) { i, p in
            let vs = visits.filter { $0.by.lowercased() == p.name.lowercased() }
            OCalmCard(title: p.name, lines: [t("Last seen ", "Alionekana ") + agoText(p.at) + accText(p.acc),
                                             (isOld(p.at) ? t("Not seen for over an hour · ", "Hajaonekana kwa zaidi ya saa moja · ") : "") + (vs.count == 1 ? t("1 visit today", "ziara 1 leo") : t("\(vs.count) visits today", "ziara \(vs.count) leo"))],
                      lead: { Text(initialsOf(p.name)).font(.mont(15, .heavy)).foregroundColor(.white).frame(width: 44, height: 44).background(colors[p.name] ?? C.blue).clipShape(Circle()) }) { who = p.name; officeOpenPerson(p.name) }
                .padding(.top, 8).rise(min(i, 6))
        }
        if ps.isEmpty { OEmpty(t("Nobody is sharing their location right now.", "Hakuna anayeshiriki mahali kwa sasa.")) }
        CardV(pad: 4) {
            OFold(icon: "lock", hue: "slate", title: t("Who shows here", "Nani anaonekana hapa"), summary: t("Only people who turn it on, in their work hours", "Walioiwasha tu, wakati wa saa zao za kazi"), tone: "has") {
                Text(t("Only people who turned on “Share my location” in their Settings show here, and only in their work hours. Only the latest position is kept, for 12 hours, never a history. Field officers never see this map.",
                       "Wanaoonekana hapa ni wale tu waliowasha “Shiriki mahali nilipo” kwenye Mipangilio yao, na wakati wa saa zao za kazi tu. Mahali pa mwisho tu panahifadhiwa, kwa saa 12, si historia. Maafisa wa uwanjani hawaoni ramani hii."))
                    .font(.bMedium).foregroundColor(C.text).fixedSize(horizontal: false, vertical: true)
                Text(t("People turn it on in Me › Settings › Security and alerts. It’s off unless they choose it.", "Watu wanaiwasha kwenye Mimi › Mipangilio › Usalama na arifa. Imezimwa wasipochagua.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 8).fixedSize(horizontal: false, vertical: true)
            }
        }.padding(.top, 14)
    }

    func legend(_ c: Color, _ label: String) -> some View {
        HStack(spacing: 5) { Circle().fill(c).frame(width: 7, height: 7).padding(2).background(Color.white).clipShape(Circle()); Text(label).font(.bSmall).foregroundColor(C.muted).lineLimit(2) }
    }
}

// --- Office › Plan a week ---------------------------------------------------------------------------------------

private struct PlanInfo { let why: [String]; let age: Int?; var chosen = false }
private struct WeekPlan {
    var to: String; var info: [String: PlanInfo]; var total: Int; var days: [[String]]; var spare: [String]; var dates: [Date]; var assigned = false; var nogps: [String] = []
}
/// Working days from `start` on: Sundays are skipped (the team works Monday to Saturday).
private func planDates(_ start: Date, _ n: Int) -> [Date] {
    var out: [Date] = []; var d = start
    while out.count < n { if ShareHere.weekday(d) != 7 { out.append(d) }; d = Calendar.current.date(byAdding: .day, value: 1, to: d) ?? d.addingTimeInterval(86400) }
    return out
}
private func planOrder(_ ids: [String], _ s: Snapshot) -> [String] { orderStops(ids.compactMap { id in s.projects.first { $0.id == id && $0.gps != nil } }, from: nil).map(\.id) }
private func planMetres(_ ids: [String], _ s: Snapshot) -> Double {
    let g = ids.compactMap { id in s.projects.first { $0.id == id }?.gps }
    return zip(g, g.dropFirst()).reduce(0) { $0 + Geo.metres($1.0, $1.1) }
}
private func planWhy(_ info: PlanInfo?, _ repairState: String?) -> [(String, String)] {
    var out: [(String, String)] = info?.chosen == true ? [("chosen", t("Chosen", "Umechagua"))] : []
    for w in info?.why ?? [] {
        switch w {
        case "repair": out.append(("bad", repairState == "repair" ? t("Needs repair", "Inahitaji matengenezo") : t("Not working", "Haifanyi kazi")))
        case "urgent": out.append(("bad", t("Urgent", "Haraka")))
        case "overdue": let mo = Int((Double(info?.age ?? 0) / 30.4).rounded()); out.append(("", t("Not visited for \(mo) months", "Haijatembelewa kwa miezi \(mo)")))
        case "never": out.append(("", t("No visit in the app yet", "Bado haijatembelewa kwenye programu")))
        case "test": out.append(("has", t("Water test due", "Kipimo cha maji kinahitajika")))
        default: break
        }
    }
    return out
}
func planArea(_ p: Project) -> String { (p.area.split(separator: ",").first.map(String.init) ?? "").trimmingCharacters(in: .whitespaces) }
private func dayLabel(_ d: Date) -> String { ShareHere.dayName(ShareHere.weekday(d)) + " " + "\(Calendar.current.component(.day, from: d))" + " " + ODates.fmt(d, "MMM") }
private func projN(_ n: Int) -> String { n == 1 ? t("1 project", "mradi 1") : t("\(n) projects", "miradi \(n)") }

private struct WhyTags: View {
    let why: [(String, String)]
    var body: some View {
        OFlow(spacing: 4) {
            ForEach(Array(why.enumerated()), id: \.offset) { _, w in
                switch w.0 {
                case "bad": Tag(w.1, bg: C.badBg, fg: C.bad)
                case "has": Tag(w.1, bg: C.tint, fg: C.deep)
                case "chosen": Tag(w.1, bg: ohue("purple").bg, fg: ohue("purple").fg)
                default: Tag(w.1, bg: C.warnBg, fg: C.warn)
                }
            }
        }
    }
}

/// Things side by side that wrap onto more lines when there is no room.
struct OFlow: Layout {
    var spacing: CGFloat = 6; var center = false
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let w = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, rowH: CGFloat = 0, maxW: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(ProposedViewSize(width: w.isFinite ? w : nil, height: nil))
            if x > 0 && x + s.width > w { y += rowH + spacing; x = 0; rowH = 0 }
            x += s.width + spacing; rowH = max(rowH, s.height); maxW = max(maxW, x - spacing)
        }
        return CGSize(width: w.isFinite ? w : maxW, height: y + rowH)
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var rows: [[(LayoutSubview, CGSize)]] = [[]]; var x: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(ProposedViewSize(width: bounds.width, height: nil))
            if x > 0 && x + s.width > bounds.width { rows.append([]); x = 0 }
            rows[rows.count - 1].append((v, s)); x += s.width + spacing
        }
        var y = bounds.minY
        for r in rows {
            let rw = r.reduce(0) { $0 + $1.1.width } + spacing * CGFloat(max(0, r.count - 1))
            var xx = center ? bounds.minX + (bounds.width - rw) / 2 : bounds.minX
            let h = r.map { $0.1.height }.max() ?? 0
            for (v, s) in r { v.place(at: CGPoint(x: xx, y: y), proposal: ProposedViewSize(width: min(s.width, bounds.width), height: s.height)); xx += s.width + spacing }
            y += h + spacing
        }
    }
}

/// The choices fold away after the first suggestion; clean day cards; a stop opens a sheet.
struct OfficePlanWeek: View {
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
    @State private var day = -1
    @State private var note = ""
    @State private var busy = false
    @State private var stop: OItem?
    @State private var choicesOpen = true
    @State private var areas: [String] = []
    @State private var pick: [String] = []
    @State private var onlyPick = false
    @State private var pickAt: String?
    @State private var undo: (String, Int, Int, String)?

    func proj(_ id: String) -> Project? { s.projects.first { $0.id == id } }

    var body: some View {
        ScrollViewReader { proxy in
            VStack(alignment: .leading, spacing: 0) {
                OHead(icon: "compass", hue: "blue", title: t("Plan a week", "Panga wiki"), line: t("Suggested visits for one person: close sites on the same day, in driving order.", "Ziara zilizopendekezwa kwa mtu mmoja: maeneo yaliyo karibu siku moja, kwa mpangilio wa safari."))
                choices(proxy)
                if let p = plan, !p.days.isEmpty { result(p).id("plan") }
            }
        }
        .task {
            if ck.isEmpty { ck = m.prefs.country.isEmpty ? (m.sx.home.isEmpty ? "ke" : m.sx.home) : m.prefs.country }
            if start.isEmpty { var d = Calendar.current.date(byAdding: .day, value: 1, to: Date()) ?? Date(); if ShareHere.weekday(d) == 7 { d = d.addingTimeInterval(86400) }; start = When.localDay(d) }
            names = await m.api.adminGet("team").json.objs("team").map { $0.str("name") }.filter { !$0.isEmpty }.sorted()
            if to.isEmpty { to = names.first ?? "" }
        }
        .sheet(item: Binding(get: { pickAt.map { OItem(id: $0, o: [:]) } }, set: { pickAt = $0?.id })) { x in
            PlanPickSheet(s: s, ck: ck, areas: $areas, pick: $pick, at: x.id) { pickAt = nil }
        }
        .sheet(item: $stop) { x in stopSheet(x) }
        .onChange(of: pick.count) { n in if n == 0 { onlyPick = false } }
    }

    func choices(_ proxy: ScrollViewProxy) -> some View {
        let dn = Int(days) ?? 5
        let sum = [to.isEmpty ? t("Choose a person", "Chagua mtu") : to, countryName(ck) ?? ck, areas.isEmpty ? t("all areas", "maeneo yote") : areasText(), dn == 1 ? t("1 day", "siku 1") : t("\(dn) days", "siku \(dn)"),
                   t("\(per) a day", "\(per) kwa siku"), pick.isEmpty ? "" : onlyPick ? t("only the \(pick.count) chosen", "zilizochaguliwa \(pick.count) tu") : t("\(pick.count) chosen", "\(pick.count) zimechaguliwa")]
            .filter { !$0.isEmpty }.joined(separator: " · ")
        let ph = ohue("purple")
        return CardV(pad: 4) {
            OFold(icon: "cog", hue: "blue", title: t("Choices", "Machaguo"), summary: sum, tone: "has", open: choicesOpen) {
                HStack(alignment: .bottom, spacing: 10) {
                    Picker1(label: t("Field officer", "Afisa wa uwanjani"), value: to, options: [("", t("Choose", "Chagua"))] + names.map { ($0, $0) }) { to = $0 }
                    Picker1(label: t("Country", "Nchi"), value: ck, options: COUNTRIES) { v in if v != ck { ck = v; areas = []; pick = [] } }
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text(t("Area", "Eneo")).font(.mont(13, .bold)).foregroundColor(C.ink).padding(.top, 12)
                    Button { pickAt = "areas" } label: {
                        HStack { Text(areasText()).font(.bLarge).foregroundColor(C.text).multilineTextAlignment(.leading).frame(maxWidth: .infinity, alignment: .leading); Icon("down", size: 18, color: C.muted) }
                            .padding(.horizontal, 14).padding(.vertical, 10).frame(minHeight: 52).background(C.card).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.edge, lineWidth: 1))
                    }.buttonStyle(.plain)
                }
                HStack(alignment: .bottom, spacing: 10) {
                    Picker1(label: t("Days", "Siku"), value: days, options: (1...6).map { ("\($0)", "\($0)") }) { days = $0 }
                    Picker1(label: t("Stops a day", "Vituo kwa siku"), value: per, options: (3...8).map { ("\($0)", "\($0)") }) { per = $0 }
                }
                DateField(label: t("First day", "Siku ya kwanza"), value: start) { if !$0.isEmpty { start = $0 } }
                Button { pickAt = "projects" } label: {
                    HStack(spacing: 12) {
                        OTileIcon(icon: "clip", tint: ph.fg, bg: ph.bg, size: 38)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(t("Areas and projects", "Maeneo na miradi")).font(.tSmall).foregroundColor(C.ink)
                            let on = !pick.isEmpty || !areas.isEmpty
                            HStack(alignment: .top, spacing: 6) { Circle().fill(on ? C.blue2 : C.muted).frame(width: 7, height: 7).padding(.top, 5); Text(scopeText()).font(.bSmall).foregroundColor(on ? C.ink : C.muted).multilineTextAlignment(.leading) }
                        }.frame(maxWidth: .infinity, alignment: .leading)
                        Icon("chev", size: 18, color: C.muted)
                    }.padding(.horizontal, 12).padding(.vertical, 8).frame(minHeight: 60).overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.line, lineWidth: 1)).contentShape(Rectangle())
                }.buttonStyle(.plain).padding(.top, 14)
                if !pick.isEmpty {
                    HStack(spacing: 8) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(t("Only these projects", "Miradi hii tu")).font(.tSmall).foregroundColor(C.ink)
                            Text(t("No other suggestions are added", "Hakuna mapendekezo mengine yanayoongezwa")).font(.bSmall).foregroundColor(C.muted)
                        }.frame(maxWidth: .infinity, alignment: .leading)
                        Toggle("", isOn: $onlyPick).labelsHidden().tint(C.ok)
                    }.padding(.horizontal, 12).padding(.vertical, 8).overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.line, lineWidth: 1)).padding(.top, 8)
                }
                Btn(t("Suggest visits", "Pendekeza ziara"), icon: "spark", enabled: !to.isEmpty && !busy, wide: true, busy: busy ? t("Working it out…", "Inapanga…") : nil) { suggest(proxy) }.padding(.top, 14)
                if case let (tone, text)? = msg { Msg(text, tone) }
            }.id(choicesOpen ? "open" : "closed")
            ODivider().padding(.horizontal, 14)
            OFold(icon: "info", hue: "slate", title: t("How it chooses", "Jinsi inavyochagua"), summary: t("Repairs first, then visits that are due", "Matengenezo kwanza, kisha ziara zinazohitajika"), tone: "off") {
                Text(t("Suggests visits for one person: open repairs first, then sites not visited for 6 months or more, and water tests that are due. Sites close together go on the same day, in driving order.",
                       "Inapendekeza ziara kwa mtu mmoja: matengenezo yaliyo wazi kwanza, kisha maeneo yasiyotembelewa kwa miezi 6 au zaidi, na vipimo vya maji vinavyohitajika. Maeneo yaliyo karibu yanawekwa siku moja, kwa mpangilio wa safari."))
                    .font(.bMedium).foregroundColor(C.text).fixedSize(horizontal: false, vertical: true)
                Text(t("Tap a stop to open the project or take it off the day. Then assign the week: each day goes to their “My work” with its date.", "Gusa kituo kufungua mradi au kukiondoa siku hiyo. Kisha gawa wiki: kila siku inaenda kwenye “Kazi yangu” yao na tarehe yake."))
                    .font(.bSmall).foregroundColor(C.muted).padding(.top, 8).fixedSize(horizontal: false, vertical: true)
            }
        }.padding(.top, 12)
    }

    func areasText() -> String {
        areas.isEmpty ? t("All of ", "Yote ya ") + (countryName(ck) ?? ck) : areas.prefix(2).joined(separator: ", ") + (areas.count > 2 ? " " + t("+\(areas.count - 2) more", "na \(areas.count - 2) zaidi") : "")
    }
    func scopeText() -> String {
        let cn = countryName(ck) ?? ck
        let ps = pick.compactMap(proj)
        if areas.isEmpty { return t("All of ", "Yote ya ") + cn + " · " + (ps.isEmpty ? t("suggestions from every area", "mapendekezo kutoka maeneo yote") : t("\(ps.count) chosen by hand", "zimechaguliwa \(ps.count) kwa mkono")) }
        let n = Dictionary(grouping: s.projects.filter { $0.ck == ck }, by: planArea).mapValues(\.count)
        let parts = areas.map { a -> String in
            let k = ps.filter { planArea($0) == a }.count, mm = n[a] ?? 0
            return a + ": " + (k > 0 ? t("\(k) of " + projN(mm), (mm == 1 ? "mradi " : "miradi ") + "\(k) kati ya \(mm)") : mm == 1 ? t("its 1 project", "mradi wake 1") : t("all \(mm)", "yote \(mm)"))
        }
        let other = ps.filter { !areas.contains(planArea($0)) }.count
        return parts.prefix(4).joined(separator: " · ") + (parts.count > 4 ? " · " + t("+\(parts.count - 4) more areas", "na maeneo \(parts.count - 4) zaidi") : "") + (other > 0 ? " · " + t("\(other) more chosen", "zingine \(other) zimechaguliwa") : "")
    }

    func suggest(_ proxy: ScrollViewProxy) {
        busy = true; msg = nil
        Task {
            var q = ["ck": ck, "days": days, "per": per]
            if !areas.isEmpty { q["areas"] = areas.joined(separator: ",") }
            if !pick.isEmpty { q["ids"] = pick.joined(separator: ","); if onlyPick { q["only"] = "1" } }
            let r = await m.api.adminGet("plan", q)
            busy = false
            guard r.ok else { msg = ("bad", reasonText(r.reason)); return }
            var info: [String: PlanInfo] = [:]
            func read(_ o: JSON) -> String { let id = o.str("id"); info[id] = PlanInfo(why: (o.arr("why") ?? []).compactMap { $0 as? String }, age: o["age"] == nil || o["age"] is NSNull ? nil : o.int("age"), chosen: o.bool("chosen")); return id }
            let ds = r.json.objs("days").map { d in planOrder(d.objs("stops").map(read), s) }
            let spare = r.json.objs("spare").map(read).filter { id in s.projects.contains { $0.id == id && $0.gps != nil } }
            let nogps = r.json.objs("nogps").map { o in s.projects.first { $0.id == o.str("id") }?.title ?? (o.str("t").isEmpty ? o.str("id") : o.str("t")) }
            let p = WeekPlan(to: to, info: info, total: r.json.int("total"), days: ds, spare: spare, dates: planDates(DateField.parse(start) ?? Date(), ds.count), nogps: nogps)
            plan = p; day = -1; amsg = nil
            if p.total == 0 { msg = ("", t("Nothing needs a visit there right now.", "Hakuna kinachohitaji ziara huko kwa sasa.")) }
            else { choicesOpen = false; DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { withAnimation { proxy.scrollTo("plan", anchor: .top) } } }
        }
    }

    @ViewBuilder fileprivate func result(_ p: WeekPlan) -> some View {
        let n = p.days.reduce(0) { $0 + $1.count }
        let km = p.days.reduce(0.0) { $0 + planMetres($1, s) }
        CardV(pad: 16) {
            Text(t("\(n) visits over \(p.days.count) " + (p.days.count == 1 ? "day" : "days") + " · about \(Geo.dist(km, miles: m.miles))", "Ziara \(n) kwa siku \(p.days.count) · takriban \(Geo.dist(km, miles: m.miles))")).font(.mont(17, .heavy)).foregroundColor(C.ink)
            Text(t("For \(p.to). Straight lines between sites; roads are longer.", "Kwa \(p.to). Mistari iliyonyooka kati ya maeneo; barabara ni ndefu zaidi.") + (p.total > n ? " " + t("\(p.total - n) more sites need a visit: add them below.", "Maeneo mengine \(p.total - n) yanahitaji ziara: yaongeze hapa chini.") : ""))
                .font(.bSmall).foregroundColor(C.muted).padding(.top, 2).fixedSize(horizontal: false, vertical: true)
            if !p.nogps.isEmpty {
                HStack(alignment: .top, spacing: 8) { Icon("alert", size: 18, color: C.warn); Text(t("No location, so not placed: ", "Hakuna mahali, kwa hiyo haikuwekwa: ") + p.nogps.joined(separator: ", ")).font(.bSmall).foregroundColor(C.ink) }
                    .padding(12).frame(maxWidth: .infinity, alignment: .leading).background(C.warnBg).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).padding(.top, 10)
            }
            let lines = p.days.enumerated().map { i, ids in OMapLine(pts: ids.compactMap { proj($0)?.gps }, color: UIColor(ofaColor(i)), dim: day >= 0 && day != i) }
            let pins: [OMapPin] = p.days.enumerated().flatMap { i, ids in ids.enumerated().compactMap { k, id in proj(id).flatMap { pr in pr.gps.map { OMapPin(ll: $0, color: UIColor(ofaColor(i)), number: "\(k + 1)", title: pr.title, dim: day >= 0 && day != i) } } } }
            OfficeMap(avatars: [], pins: pins, lines: lines, fitKey: "\(p.days)|\(day)", focus: day >= 0 && day < p.days.count ? p.days[day].compactMap { proj($0)?.gps } : nil)
                .frame(height: 300).clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous)).padding(.top, 12)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    dayChip(t("All days", "Siku zote"), nil, day < 0) { day = -1 }
                    ForEach(p.days.indices, id: \.self) { i in dayChip(t("Day ", "Siku ") + "\(i + 1)", ofaColor(i), day == i) { day = i } }
                }
            }.padding(.top, 10)
        }.padding(.top, 12)
        ForEach(p.days.indices, id: \.self) { i in dayCard(p, i) }
        CardV {
            Field(t("Note for ", "Maelezo kwa ") + p.to + " " + t("(optional)", "(si lazima)"), Binding(get: { note }, set: { note = String($0.prefix(200)) }), placeholder: t("e.g. take the water test kit", "mf. beba vifaa vya kupima maji"))
            Btn(p.assigned ? t("Assigned", "Imegawiwa") : t("Assign \(n) visits to \(p.to)", "Mpe \(p.to) ziara \(n)"), icon: p.assigned ? "check" : "cal", enabled: n > 0 && !p.assigned && !busy, wide: true, busy: busy ? t("Assigning…", "Inagawa…") : nil) { assign(p) }.padding(.top, 12)
            if case let (tone, text)? = amsg { Msg(text, tone) }
            if let u = undo, !p.assigned {
                HStack(spacing: 8) {
                    Text(u.0).font(.bSmall).foregroundColor(C.ink).frame(maxWidth: .infinity, alignment: .leading)
                    Button(t("Undo", "Tendua")) { undoStop() }.font(.lLarge).foregroundColor(C.blue2).frame(minHeight: 44)
                }.padding(.horizontal, 12).background(ohue("slate").bg).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous)).padding(.top, 8)
            }
        }.padding(.top, 12)
    }

    func dayChip(_ label: String, _ dot: Color?, _ on: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) { if let dot { Circle().fill(dot).frame(width: 10, height: 10) }; Text(label).font(.lMedium).foregroundColor(C.ink).lineLimit(1).fixedSize() }
                .padding(.horizontal, 12).frame(minHeight: 36).background(on ? C.tint : C.card).clipShape(Capsule()).overlay(Capsule().stroke(on ? C.ink : C.line, lineWidth: on ? 2 : 1)).frame(minHeight: 44)
        }.buttonStyle(.plain)
    }

    fileprivate func dayCard(_ p: WeekPlan, _ i: Int) -> some View {
        let col = ofaColor(i)
        let stops = p.days[i].compactMap(proj)
        let ctr: LatLng? = stops.isEmpty ? nil : LatLng(lat: stops.compactMap { $0.gps?.lat }.reduce(0, +) / Double(stops.count), lng: stops.compactMap { $0.gps?.lng }.reduce(0, +) / Double(stops.count))
        let spare = p.spare.compactMap(proj).sorted { a, b in (ctr.flatMap { c in a.gps.map { Geo.metres(c, $0) } } ?? 0) < (ctr.flatMap { c in b.gps.map { Geo.metres(c, $0) } } ?? 0) }.prefix(30)
        return CardV(pad: 14, border: col) {
            HStack(alignment: .top, spacing: 10) {
                Circle().fill(col).frame(width: 12, height: 12).padding(.top, 6)
                VStack(alignment: .leading, spacing: 2) {
                    Text(t("Day ", "Siku ") + "\(i + 1) · " + (i < p.dates.count ? dayLabel(p.dates[i]) : "")).font(.mont(17, .heavy)).foregroundColor(C.ink)
                    Text((stops.count == 1 ? t("1 stop", "kituo 1") : t("\(stops.count) stops", "vituo \(stops.count)")) + (stops.count > 1 ? " · " + t("about ", "takriban ") + Geo.dist(planMetres(p.days[i], s), miles: m.miles) : "")).font(.bSmall).foregroundColor(C.muted)
                }
            }
            ForEach(Array(stops.enumerated()), id: \.element.id) { k, pr in
                if k > 0 { ODivider() }
                Button { stop = OItem(id: "\(i)|\(pr.id)", o: [:]) } label: {
                    HStack(spacing: 12) {
                        Text("\(k + 1)").font(.mont(12, .heavy)).foregroundColor(.white).frame(width: 28, height: 28).background(col).clipShape(Circle())
                        VStack(alignment: .leading, spacing: 2) {
                            Text(pr.title).font(.tSmall).foregroundColor(C.ink).multilineTextAlignment(.leading)
                            Text((pr.area.isEmpty ? pr.country : pr.area) + legText(k > 0 ? stops[k - 1].gps : nil, pr.gps)).font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.leading)
                            let why = planWhy(p.info[pr.id], s.repairs[pr.id]?.state)
                            if !why.isEmpty { WhyTags(why: why).padding(.top, 4) }
                        }.frame(maxWidth: .infinity, alignment: .leading)
                        Icon("chev", size: 18, color: C.muted)
                    }.padding(.vertical, 8).padding(.horizontal, 2).frame(minHeight: 56).contentShape(Rectangle())
                }.buttonStyle(.plain).padding(.top, k == 0 ? 8 : 0)
            }
            if stops.isEmpty { Text(t("No stops on this day.", "Hakuna vituo siku hii.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 8) }
            if !p.assigned && !spare.isEmpty {
                ODivider().padding(.top, 8)
                Picker1(label: t("Add a stop", "Ongeza kituo"), value: "", options: [("", "+ " + t("Choose a site…", "Chagua eneo…"))] + spare.map { sp in (sp.id, sp.title + (ctr.flatMap { c in sp.gps.map { " · " + Geo.dist(Geo.metres(c, $0), miles: m.miles) } } ?? "")) }) { id in
                    guard !id.isEmpty, var cur = plan else { return }
                    cur.days[i] = planOrder(cur.days[i] + [id], s); cur.spare.removeAll { $0 == id }; plan = cur; day = i
                }
            }
            if !stops.isEmpty, let url = mapsLegs(stops, from: nil).first?.1 {
                OLineBtn(text: t("Route in Google Maps", "Njia kwenye Google Maps"), icon: "map") { Share.open(url) }.padding(.top, 10)
            }
        }.padding(.top, 12)
    }

    func legText(_ a: LatLng?, _ b: LatLng?) -> String {
        guard let a, let b else { return "" }
        return " · " + Geo.dist(Geo.metres(a, b), miles: m.miles) + t(" from the last stop", " kutoka kituo cha mwisho")
    }

    fileprivate func assign(_ p: WeekPlan) {
        busy = true; amsg = nil
        Task {
            var made = 0; var cur = p
            for i in p.days.indices where !cur.days[i].isEmpty {
                let r = await m.api.admin(["action": "assign", "ids": cur.days[i], "to": p.to, "date": When.localDay(p.dates[i]),
                                           "note": (note.trimmingCharacters(in: .whitespaces).isEmpty ? "" : note.trimmingCharacters(in: .whitespaces) + " · ") + "Week plan, day \(i + 1) of \(p.days.count)"])
                if !r.ok { busy = false; plan = cur; amsg = ("bad", reasonText(r.reason) + (made > 0 ? t(" (\(made) assigned before this.)", " (\(made) zimegawiwa kabla ya hili.)") : "")); return }
                made += (r.json.arr("items")?.count) ?? cur.days[i].count
                cur.days[i] = []
            }
            busy = false; var done = p; done.assigned = true; plan = done; undo = nil; Haptic.ok()
            amsg = ("ok", t("Assigned ✓ \(made) visits. \(p.to) sees them in “My work” and gets a notification.", "Imegawiwa ✓ ziara \(made). \(p.to) anaziona kwenye “Kazi yangu” na anapata arifa."))
        }
    }

    func undoStop() {
        guard let u = undo, var cur = plan, !cur.assigned, u.1 < cur.days.count, !cur.days[u.1].contains(u.3) else { undo = nil; return }
        cur.days[u.1].insert(u.3, at: min(u.2, cur.days[u.1].count)); cur.spare.removeAll { $0 == u.3 }; plan = cur; undo = nil; amsg = nil
    }

    @ViewBuilder func stopSheet(_ x: OItem) -> some View {
        let parts = x.id.split(separator: "|").map(String.init)
        if parts.count == 2, let di = Int(parts[0]), let p = plan, di < p.days.count, let k = p.days[di].firstIndex(of: parts[1]), let pr = proj(parts[1]) {
            let col = ofaColor(di); let d = p.days[di]
            let prev = k > 0 ? proj(d[k - 1]) : nil
            OSheet {
                OSheetHeadC(title: pr.title, sub: [[pr.area, pr.country].filter { !$0.isEmpty }.joined(separator: ", ")], lead: {
                    Text("\(k + 1)").font(.mont(24, .heavy)).foregroundColor(.white).frame(width: 64, height: 64).background(col).clipShape(Circle())
                }, close: { stop = nil })
                let why = planWhy(p.info[pr.id], s.repairs[pr.id]?.state)
                if !why.isEmpty { WhyTags(why: why).frame(maxWidth: .infinity).padding(.top, 10) }
                VStack(spacing: 0) {
                    OFact(label: t("Day", "Siku"), value: t("Day ", "Siku ") + "\(di + 1) · " + (di < p.dates.count ? dayLabel(p.dates[di]) : ""), sub: t("Stop \(k + 1) of \(d.count)", "Kituo \(k + 1) kati ya \(d.count)"))
                    if let pg = prev?.gps, let g = pr.gps { OFact(label: t("From the last stop", "Kutoka kituo cha mwisho"), value: Geo.dist(Geo.metres(pg, g), miles: m.miles), sub: prev?.title ?? "") }
                    if !pr.type.isEmpty { OFact(label: t("Type", "Aina"), value: pr.type) }
                }.padding(.top, 12)
                Btn(t("Open project", "Fungua mradi"), icon: "arrow", wide: true) { stop = nil; m.go(Place(tab: "office", project: pr.id)) }.padding(.top, 16)
                if !p.assigned {
                    OLineBtn(text: t("Take it off day \(di + 1)", "Iondoe siku ya \(di + 1)"), icon: "trash", red: true) {
                        stop = nil
                        var cur = p; cur.days[di] = planOrder(d.filter { $0 != pr.id }, s); cur.spare = [pr.id] + cur.spare; plan = cur
                        amsg = ("ok", t("Taken off day \(di + 1). It’s back in “Add a stop”.", "Imeondolewa siku ya \(di + 1). Iko tena kwenye “Ongeza kituo”."))
                        undo = (t("\(pr.title) taken off day \(di + 1)", "\(pr.title) imeondolewa siku ya \(di + 1)"), di, k, pr.id)
                    }.padding(.top, 8)
                }
            }
        }
    }
}

/// Plan a week › Areas and projects. Areas: tick several (none: the whole country). Projects in them, by area, with Select
/// all / Clear, search and "Ticked only": the ones ticked are always planned; none ticked: all of the areas can be suggested.
private struct PlanPickSheet: View {
    let s: Snapshot; let ck: String; @Binding var areas: [String]; @Binding var pick: [String]; let at: String; let close: () -> Void
    @State private var q = ""
    @State private var only = false
    @State private var msg: String?
    private let pickMax = 60, areaMax = 20, pickShow = 60

    func tickArea(_ a: String, _ on: Bool) {
        msg = nil
        if on && !areas.contains(a) {
            var add = [a]
            if areas.isEmpty { for id in pick { if let p = s.projects.first(where: { $0.id == id }) { let pa = planArea(p); if !pa.isEmpty && !add.contains(pa) { add.append(pa) } } } }
            if areas.count + add.count > areaMax { msg = t("You can tick up to \(areaMax) areas.", "Unaweza kuchagua hadi maeneo \(areaMax)."); return }
            areas += add
        } else if !on {
            areas.removeAll { $0 == a }
            pick.removeAll { id in s.projects.first { $0.id == id }.map(planArea) == a }
        }
    }
    @discardableResult func tickProject(_ id: String, _ on: Bool) -> Bool {
        if on && !pick.contains(id) { if pick.count >= pickMax { msg = t("You can choose up to \(pickMax) projects.", "Unaweza kuchagua hadi miradi \(pickMax)."); return false }; pick.append(id) }
        if !on { pick.removeAll { $0 == id } }
        return true
    }

    var body: some View {
        let cn = countryName(ck) ?? ck
        let n = Dictionary(grouping: s.projects.filter { $0.ck == ck }.map(planArea).filter { !$0.isEmpty }, by: { $0 }).mapValues(\.count)
        let top = n.sorted { ($0.value, $1.key) > ($1.value, $0.key) }.prefix(80).map { ($0.key, $0.value) }
        let counts = top + areas.filter { a in !top.contains { $0.0 == a } && n[a] != nil }.map { ($0, n[$0] ?? 0) }
        let tick = Set(pick); let qq = q.trimmingCharacters(in: .whitespaces).lowercased()
        let shown = s.projects.filter { p in p.ck == ck && (areas.isEmpty || areas.contains(planArea(p)) || tick.contains(p.id)) && (!only || tick.contains(p.id)) && (qq.isEmpty || "\(p.title) \(p.area)".lowercased().contains(qq)) }
        let groups = Dictionary(grouping: shown, by: planArea)
        let order = groups.keys.sorted { a, b in
            let ia = areas.firstIndex(of: a) ?? 99, ib = areas.firstIndex(of: b) ?? 99
            if ia != ib { return ia < ib }
            if a.isEmpty != b.isEmpty { return !a.isEmpty }
            if (n[a] ?? 0) != (n[b] ?? 0) { return (n[a] ?? 0) > (n[b] ?? 0) }
            return a < b
        }
        let bl = ohue("blue")
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    OSheetHeadC(title: t("Areas and projects", "Maeneo na miradi"), sub: [t("Tick the areas to plan in, then any projects that must be visited. Those are always in the plan, grouped by distance; the other stops are suggestions.",
                                                                                         "Chagua maeneo ya kupanga, kisha miradi inayolazimika kutembelewa. Hiyo iko kwenye mpango kila wakati, kwa umbali; vituo vingine ni mapendekezo.")],
                                lead: { OTileIcon(icon: "layers", tint: bl.fg, bg: bl.bg, size: 64) }, close: close)
                    HStack(spacing: 8) { Text(t("Areas", "Maeneo")).font(.tSmall).foregroundColor(C.ink); Tag(areas.isEmpty ? t("All of ", "Yote ya ") + cn : t("\(areas.count) ticked", "\(areas.count) zimechaguliwa"), bg: C.tint, fg: C.deep) }.padding(.top, 18)
                    ScrollView {
                        VStack(spacing: 0) {
                            ForEach(Array(counts.enumerated()), id: \.offset) { k, x in
                                let on = areas.contains(x.0)
                                if k > 0 { ODivider() }
                                Button { tickArea(x.0, !on) } label: {
                                    HStack(spacing: 10) {
                                        Image(systemName: on ? "checkmark.square.fill" : "square").font(.system(size: 22)).foregroundColor(on ? C.blue : C.muted)
                                        Text(x.0).font(.mont(14, .bold)).foregroundColor(C.ink).frame(maxWidth: .infinity, alignment: .leading).multilineTextAlignment(.leading)
                                        Text("\(x.1)").font(.mont(12, .heavy)).foregroundColor(on ? C.card : C.ink).padding(.horizontal, 8).padding(.vertical, 2).frame(minWidth: 30).background(on ? C.blue : ohue("slate").bg).clipShape(Capsule())
                                    }.padding(.horizontal, 12).frame(minHeight: 48).background(on ? C.tint : Color.clear).contentShape(Rectangle())
                                }.buttonStyle(.plain)
                            }
                            if counts.isEmpty { Text(t("No areas in \(cn) yet.", "Bado hakuna maeneo \(cn).")).foregroundColor(C.muted).padding(12) }
                        }
                    }.frame(maxHeight: 264).overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(C.line, lineWidth: 1)).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).padding(.top, 8)
                    if areas.isEmpty { Text(t("None ticked: projects from all of \(cn).", "Hakuna iliyochaguliwa: miradi kutoka \(cn) yote.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 6) }
                    HStack(spacing: 8) {
                        Text(areas.isEmpty ? t("Projects in \(cn)", "Miradi ya \(cn)") : t("Projects in these areas", "Miradi katika maeneo haya")).font(.tSmall).foregroundColor(C.ink)
                        Tag(t("\(pick.count) chosen", "\(pick.count) zimechaguliwa"), bg: C.tint, fg: C.deep)
                    }.padding(.top, 18).id("projects")
                    HStack(alignment: .bottom, spacing: 8) {
                        Field(t("Search", "Tafuta"), $q, placeholder: t("Name or place", "Jina au mahali"))
                        pill(t("Ticked only", "Zilizochaguliwa tu"), on: only) { only.toggle() }.padding(.bottom, 4)
                    }
                    projects(order, groups, tick, n)
                    if shown.isEmpty { Text(only ? t("No projects ticked yet.", "Bado hakuna mradi uliochaguliwa.") : t("No projects match.", "Hakuna mradi unaolingana.")).foregroundColor(C.muted).padding(.top, 12) }
                    if shown.count > pickShow { Text(t("…and \(shown.count - pickShow) more. Search or tick an area to narrow it down.", "…na \(shown.count - pickShow) zaidi. Tafuta au chagua eneo ili kupunguza.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 10) }
                    Msg(msg, "warn")
                    Btn(t("Done", "Imekamilika"), icon: "check", wide: true, action: close).padding(.top, 14)
                    OLineBtn(text: t("Clear all", "Ondoa zote"), icon: "x") { areas = []; pick = []; only = false; msg = nil }.padding(.top, 8)
                }.padding(.horizontal, 20).padding(.top, 22).padding(.bottom, 28)
            }
            .onAppear { if at == "projects" && !areas.isEmpty { DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { withAnimation { proxy.scrollTo("projects", anchor: .top) } } } }
        }
        .storySheet()
    }

    func projects(_ order: [String], _ groups: [String: [Project]], _ tick: Set<String>, _ n: [String: Int]) -> some View {
        var left = pickShow
        var blocks: [(String, [Project], [Project])] = []
        for a in order {
            if left <= 0 { break }
            let all = (groups[a] ?? []).sorted { $0.title < $1.title }; let ps = Array(all.prefix(left)); left -= ps.count
            blocks.append((a, all, ps))
        }
        return ForEach(Array(blocks.enumerated()), id: \.offset) { _, b in
            let k = b.1.filter { tick.contains($0.id) }.count
            VStack(spacing: 0) {
                OFlow(spacing: 6) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(b.0.isEmpty ? t("No area", "Hakuna eneo") : b.0).font(.tSmall).foregroundColor(C.ink)
                        Text(k > 0 ? t("\(k) of \(b.1.count) chosen", "\(k) kati ya \(b.1.count) zimechaguliwa") : projN(b.1.count)).font(.bSmall).foregroundColor(C.muted)
                    }.padding(.trailing, 8)
                    HStack(spacing: 6) {
                        pill(t("Select all", "Chagua zote"), on: false, enabled: k < b.1.count) { msg = nil; for p in b.1 { if !tickProject(p.id, true) { break } } }
                        pill(t("Clear", "Futa"), on: false, enabled: k > 0) { msg = nil; b.1.forEach { tickProject($0.id, false) } }
                    }
                }.padding(.horizontal, 12).padding(.vertical, 8).frame(maxWidth: .infinity, alignment: .leading).background(C.tint)
                ForEach(b.2) { p in
                    let on = tick.contains(p.id)
                    ODivider()
                    Button { msg = nil; tickProject(p.id, !on) } label: {
                        HStack(spacing: 10) {
                            Image(systemName: on ? "checkmark.square.fill" : "square").font(.system(size: 22)).foregroundColor(on ? C.blue : C.muted)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(p.title).font(.mont(14, .bold)).foregroundColor(C.ink).multilineTextAlignment(.leading)
                                Text([p.type, p.area].filter { !$0.isEmpty }.joined(separator: " · ")).font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.leading)
                                if p.gps == nil { Text(t("No location", "Hakuna mahali")).font(.mont(12, .bold)).foregroundColor(C.warn) }
                            }.frame(maxWidth: .infinity, alignment: .leading)
                        }.padding(.horizontal, 12).padding(.vertical, 4).frame(minHeight: 56).contentShape(Rectangle())
                    }.buttonStyle(.plain)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.line, lineWidth: 1)).padding(.top, 10)
        }
    }

    func pill(_ text: String, on: Bool, enabled: Bool = true, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text).font(.mont(14, .bold)).foregroundColor(on ? C.card : enabled ? C.ink : C.muted.opacity(0.55)).lineLimit(1).fixedSize()
                .padding(.horizontal, 14).frame(minHeight: 44).background(on ? C.blue : C.card).clipShape(Capsule()).overlay(Capsule().stroke(on ? .clear : enabled ? C.edge : C.line, lineWidth: 1))
        }.buttonStyle(.plain).disabled(!enabled)
    }
}

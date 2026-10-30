import MapKit
import Network
import SwiftUI
import UIKit

// The Map tab (Android MapHome.kt, MapPins.kt, MapPolish.kt; 2.17 – 2.25): pins coloured by how each project is doing,
// with their kind's icon ("pins that talk") and short names when zoomed in; groups as a donut; calm by default (a tap on
// empty map shows the filter chips, the pinned bar, Nearest to you and the Key); search for villages, projects and
// places; a card that slides up for a tapped pin (swipe through the projects nearby); a long press for "New site here"
// or "Measure from me"; map style, Go to me, Follow me and Offline areas on the right.

/// What a pin's colour says: working, needs repair, not working, or not visited for 6 months (or never).
enum PinCond { static let ok = "ok", repair = "repair", bad = "bad", old = "old" }
func mapCondition(_ s: Snapshot, _ p: Project) -> String {
    if let r = s.repairs[p.id] { return r.state == "notworking" ? PinCond.bad : PinCond.repair }
    guard let v = s.visits[p.id], let at = When.parse(v.at) else { return PinCond.old }
    return Date().timeIntervalSince(at) / 86400 >= DUE_DAYS ? PinCond.old : PinCond.ok
}
func condRank(_ c: String) -> Int { c == PinCond.bad ? 3 : c == PinCond.repair ? 2 : c == PinCond.old ? 1 : 0 }
func condColor(_ c: String) -> UIColor { UIColor(Color(hex: c == PinCond.bad ? 0xB3261E : c == PinCond.repair ? 0xB86E00 : c == PinCond.old ? 0x66727C : 0x1C7C4A)) }
func condName(_ c: String) -> String {
    switch c { case PinCond.bad: return t("Not working", "Haifanyi kazi"); case PinCond.repair: return t("Needs repair", "Inahitaji matengenezo"); case PinCond.old: return t("No visit in 6 months", "Bila ziara miezi 6"); default: return t("Working", "Inafanya kazi") }
}
/// A name short enough to sit under a pin: whole words only, "…" when some are left out.
func shortName(_ title: String, max: Int = 20) -> String {
    let s = title.trimmingCharacters(in: .whitespaces)
    if s.count <= max { return s }
    var b = ""
    for w in s.split(separator: " ") { if !b.isEmpty && b.count + 1 + w.count > max - 1 { break }; b += (b.isEmpty ? "" : " ") + w }
    return b.trimmingCharacters(in: CharacterSet(charactersIn: ",·-– ")) + "…"
}
let MAP_STATUS = [("broken", "Broken", "Zimeharibika", "alert"), ("due", "Due a visit", "Inasubiri ziara", "cal"), ("mine", "My assigned visits", "Ziara nilizopewa", "person")]
let MAP_TYPES = [("water", "drop", "blue"), ("school", "school", "purple"), ("health", "cross", "coral"), ("home", "house", "teal"), ("farm", "leaf", "green")]
@MainActor func mapStatusOk(_ m: AppModel, _ s: Snapshot, _ p: Project, _ k: String) -> Bool {
    switch k {
    case "broken": return s.repairs[p.id] != nil
    case "due": return ageDays(s, p) >= DUE_DAYS || testDue(m, p)
    case "mine": return s.assigned.contains { $0.id == p.id } || s.jobs.contains(p.id)
    default: return true
    }
}
func nearNone(_ ty: String, _ status: String) -> String {
    if !status.isEmpty { return t("Nothing near you matches these filters", "Hakuna kilicho karibu nawe kinacholingana na vichujio hivi") }
    switch ty {
    case "water": return t("No water points near you", "Hakuna vituo vya maji karibu nawe")
    case "school": return t("No schools near you", "Hakuna shule karibu nawe")
    case "health": return t("No health centres near you", "Hakuna vituo vya afya karibu nawe")
    case "home": return t("No children’s homes near you", "Hakuna makao ya watoto karibu nawe")
    case "farm": return t("No farms near you", "Hakuna mashamba karibu nawe")
    default: return t("No projects near you", "Hakuna miradi karibu nawe")
    }
}

/// Is there a connection? (The map says "No signal" and shows saved map areas only.)
final class NetWatch: ObservableObject {
    static let shared = NetWatch()
    @Published var online = true
    private let mon = NWPathMonitor()
    init() { mon.pathUpdateHandler = { p in DispatchQueue.main.async { self.online = p.status == .satisfied } }; mon.start(queue: DispatchQueue(label: "rafiki.net")) }
}

// --- pins -------------------------------------------------------------------------------------------------------

struct HomePin: Equatable { let id: String; let ll: LatLng; let cond: String; let type: String; let due: Bool; let name: String; let build: Bool }

final class PinAnn: NSObject, MKAnnotation {
    let pin: HomePin
    var coordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: pin.ll.lat, longitude: pin.ll.lng) }
    init(_ p: HomePin) { pin = p }
}
final class NameAnn: NSObject, MKAnnotation {
    let pin: HomePin
    var coordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: pin.ll.lat, longitude: pin.ll.lng) }
    init(_ p: HomePin) { pin = p }
}
final class SpotAnn: NSObject, MKAnnotation { let coordinate: CLLocationCoordinate2D; init(_ c: CLLocationCoordinate2D) { coordinate = c } }
final class StopAnn: NSObject, MKAnnotation { let coordinate: CLLocationCoordinate2D; let n: Int; init(_ c: CLLocationCoordinate2D, _ n: Int) { coordinate = c; self.n = n } }

/// The pictures of pins and groups, drawn once per look and kept.
enum PinArt {
    private static var cache: [String: UIImage] = [:]
    static func pin(_ p: HomePin, big: Bool, dim: Bool) -> UIImage {
        let key = "\(p.type)|\(p.cond)|\(p.due)|\(big)|\(p.build)|\(dim)"
        if let c = cache[key] { return c }
        let r: CGFloat = big ? 11.5 : 8.5, ring: CGFloat = big ? 3.5 : 3, edge: CGFloat = 1.5, halo: CGFloat = big ? 4.5 : 1.5
        let n = ceil(2 * (r + ring + edge + halo)) + 4, m = n / 2
        let img = UIGraphicsImageRenderer(size: CGSize(width: n, height: n)).image { ctx in
            let c = ctx.cgContext
            func circle(_ rad: CGFloat, _ col: UIColor) { c.setFillColor(col.cgColor); c.fillEllipse(in: CGRect(x: m - rad, y: m - rad, width: 2 * rad, height: 2 * rad)) }
            circle(r + ring + edge + halo, big ? UIColor(red: 0.04, green: 0.47, blue: 0.76, alpha: 0.4) : UIColor(white: 0, alpha: 0.28))
            circle(r + ring + edge, .white); circle(r + ring, condColor(p.cond)); circle(r, .white)
            let cfg = UIImage.SymbolConfiguration(pointSize: r * 1.15, weight: .semibold)
            if let sym = UIImage(systemName: OIc.name(p.type), withConfiguration: cfg)?.withTintColor(UIColor(red: 0x04 / 255, green: 0x26 / 255, blue: 0x3F / 255, alpha: 1), renderingMode: .alwaysOriginal) {
                sym.draw(in: CGRect(x: m - sym.size.width / 2, y: m - sym.size.height / 2, width: sym.size.width, height: sym.size.height))
            }
            if p.due { let o = (r + ring) * 0.72; c.setFillColor(UIColor.white.cgColor); c.fillEllipse(in: CGRect(x: m + o - 4.4, y: m - o - 4.4, width: 8.8, height: 8.8)); c.setFillColor(UIColor(red: 0.04, green: 0.47, blue: 0.76, alpha: 1).cgColor); c.fillEllipse(in: CGRect(x: m + o - 3, y: m - o - 3, width: 6, height: 6)) }
            if p.build, let hat = UIImage(systemName: "hammer.circle.fill", withConfiguration: UIImage.SymbolConfiguration(pointSize: 10))?.withTintColor(UIColor(Color(hex: 0xB86E00)), renderingMode: .alwaysOriginal) {
                hat.draw(at: CGPoint(x: m - (r + ring) * 0.72 - hat.size.width / 2, y: m - (r + ring) * 0.72 - hat.size.height / 2))
            }
        }
        let out = dim ? (UIGraphicsImageRenderer(size: img.size).image { _ in img.draw(at: .zero, blendMode: .normal, alpha: 0.45) }) : img
        cache[key] = out
        return out
    }
    /// A group: a donut split by how the projects in it are, with the count in the middle.
    static func group(_ parts: [Int], _ count: Int) -> UIImage {
        let key = "g\(parts)|\(count)"
        if let c = cache[key] { return c }
        let r: CGFloat = count < 10 ? 15 : count < 100 ? 17 : 20, w: CGFloat = 4.5, n = 2 * (r + 4)
        let img = UIGraphicsImageRenderer(size: CGSize(width: n, height: n)).image { ctx in
            let c = ctx.cgContext; let m = n / 2
            c.setFillColor(UIColor(white: 0, alpha: 0.25).cgColor); c.fillEllipse(in: CGRect(x: m - r - 2, y: m - r - 1, width: 2 * r + 4, height: 2 * r + 4))
            c.setFillColor(UIColor.white.cgColor); c.fillEllipse(in: CGRect(x: m - r, y: m - r, width: 2 * r, height: 2 * r))
            let tot = CGFloat(max(1, parts.reduce(0, +))); var a: CGFloat = -.pi / 2
            for k in [3, 2, 1, 0] where parts[k] > 0 {
                let sweep = 2 * .pi * CGFloat(parts[k]) / tot
                c.setStrokeColor(condColor([PinCond.ok, PinCond.old, PinCond.repair, PinCond.bad][k]).cgColor); c.setLineWidth(w)
                c.addArc(center: CGPoint(x: m, y: m), radius: r - w / 2 - 1, startAngle: a, endAngle: a + sweep, clockwise: false); c.strokePath()
                a += sweep
            }
            let s = "\(count)" as NSString
            let at: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: count < 100 ? 12 : 11, weight: .heavy), .foregroundColor: UIColor(red: 0x04 / 255, green: 0x26 / 255, blue: 0x3F / 255, alpha: 1)]
            let sz = s.size(withAttributes: at); s.draw(at: CGPoint(x: m - sz.width / 2, y: m - sz.height / 2), withAttributes: at)
        }
        cache[key] = img
        return img
    }
    static func name(_ s: String) -> UIImage {
        let key = "n" + s
        if let c = cache[key] { return c }
        let at: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 11, weight: .bold), .foregroundColor: UIColor(red: 0x04 / 255, green: 0x26 / 255, blue: 0x3F / 255, alpha: 1)]
        let sz = (s as NSString).size(withAttributes: at)
        let img = UIGraphicsImageRenderer(size: CGSize(width: sz.width + 10, height: sz.height + 4)).image { _ in
            UIColor.white.withAlphaComponent(0.88).setFill(); UIBezierPath(roundedRect: CGRect(x: 0, y: 0, width: sz.width + 10, height: sz.height + 4), cornerRadius: 6).fill()
            (s as NSString).draw(at: CGPoint(x: 5, y: 2), withAttributes: at)
        }
        cache[key] = img
        return img
    }
}

/// Talks to the map from SwiftUI: fly, fit, follow.
final class MapCtl: ObservableObject {
    weak var mv: MKMapView?
    @Published var follow = false
    func fly(_ ll: LatLng, span: Double = 0.02) {
        mv?.setRegion(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: ll.lat, longitude: ll.lng), span: MKCoordinateSpan(latitudeDelta: span, longitudeDelta: span)), animated: Motion.on)
    }
    func fit(_ pts: [LatLng], pad: UIEdgeInsets = UIEdgeInsets(top: 160, left: 40, bottom: 220, right: 70)) {
        guard let mv, !pts.isEmpty else { return }
        if pts.count == 1 { fly(pts[0], span: 0.03); return }
        var r = MKMapRect.null
        for p in pts { let mp = MKMapPoint(CLLocationCoordinate2D(latitude: p.lat, longitude: p.lng)); r = r.union(MKMapRect(x: mp.x, y: mp.y, width: 1, height: 1)) }
        mv.setVisibleMapRect(r, edgePadding: pad, animated: Motion.on)
    }
    func center() -> LatLng? { mv.map { LatLng(lat: $0.centerCoordinate.latitude, lng: $0.centerCoordinate.longitude) } }
    func visible() -> (Double, Double, Double, Double)? {
        guard let mv else { return nil }
        let r = mv.region
        return (r.center.latitude + r.span.latitudeDelta / 2, r.center.latitude - r.span.latitudeDelta / 2, r.center.longitude + r.span.longitudeDelta / 2, r.center.longitude - r.span.longitudeDelta / 2)
    }
}

/// The map itself: MapKit with the project pins, groups, names, a long-pressed spot, the measuring line and today's route.
struct HomeMapView: UIViewRepresentable {
    let ctl: MapCtl; let pins: [HomePin]; let sel: String?; let spot: LatLng?; let measureFrom: LatLng?; let route: [LatLng]; let style: String; let offline: Bool
    let onPin: (String) -> Void; let onTap: () -> Void; let onLong: (LatLng) -> Void; let onMoved: () -> Void; let fitFirst: [LatLng]

    func makeCoordinator() -> Coord { Coord(self) }
    func makeUIView(context: Context) -> MKMapView {
        let mv = MKMapView(); mv.delegate = context.coordinator; mv.showsUserLocation = true; mv.pointOfInterestFilter = .excludingAll; mv.showsCompass = true
        mv.setRegion(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: -2.5, longitude: 35.5), span: MKCoordinateSpan(latitudeDelta: 16, longitudeDelta: 16)), animated: false)
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coord.tapped(_:))); tap.delegate = context.coordinator; mv.addGestureRecognizer(tap)
        let long = UILongPressGestureRecognizer(target: context.coordinator, action: #selector(Coord.longPressed(_:))); long.minimumPressDuration = 0.5; mv.addGestureRecognizer(long)
        mv.register(MKAnnotationView.self, forAnnotationViewWithReuseIdentifier: "pin")
        ctl.mv = mv
        return mv
    }
    func updateUIView(_ mv: MKMapView, context: Context) {
        let co = context.coordinator; co.parent = self
        let type: MKMapType = style == "sat" ? .hybrid : .standard
        if mv.mapType != type { mv.mapType = type }
        FieldOffline.apply(mv, street: style == "street" || offline)
        if co.pins != pins || co.sel != sel {
            let old = co.pins; co.pins = pins; co.sel = sel
            if old != pins {
                mv.removeAnnotations(mv.annotations.filter { $0 is PinAnn || $0 is NameAnn })
                mv.addAnnotations(pins.map { PinAnn($0) })
                co.names = []
                co.updateNames(mv)
            } else {
                for a in mv.annotations { if let pa = a as? PinAnn, let v = mv.view(for: pa) { co.style(v, pa) } }
            }
        }
        if co.spot != spot || co.measure != measureFrom {
            co.spot = spot; co.measure = measureFrom
            mv.removeAnnotations(mv.annotations.filter { $0 is SpotAnn })
            mv.removeOverlays(mv.overlays.filter { ($0 as? MKPolyline)?.title == "measure" })
            if let s = spot {
                let c = CLLocationCoordinate2D(latitude: s.lat, longitude: s.lng); mv.addAnnotation(SpotAnn(c))
                if let f = measureFrom { var cs = [CLLocationCoordinate2D(latitude: f.lat, longitude: f.lng), c]; let l = MKPolyline(coordinates: &cs, count: 2); l.title = "measure"; mv.addOverlay(l) }
            }
        }
        if co.route != route {
            co.route = route
            mv.removeAnnotations(mv.annotations.filter { $0 is StopAnn })
            mv.removeOverlays(mv.overlays.filter { ($0 as? MKPolyline)?.title == "route" })
            if route.count > 0 {
                mv.addAnnotations(route.enumerated().map { StopAnn(CLLocationCoordinate2D(latitude: $0.element.lat, longitude: $0.element.lng), $0.offset + 1) })
                if route.count > 1 { var cs = route.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lng) }; let l = MKPolyline(coordinates: &cs, count: cs.count); l.title = "route"; mv.addOverlay(l) }
            }
        }
        if !co.fitted && !fitFirst.isEmpty { co.fitted = true; DispatchQueue.main.async { self.ctl.fit(self.fitFirst, pad: UIEdgeInsets(top: 90, left: 30, bottom: 90, right: 60)) } }
        let mode: MKUserTrackingMode = ctl.follow ? .followWithHeading : .none
        if mv.userTrackingMode != mode { mv.setUserTrackingMode(mode, animated: Motion.on) }
    }

    final class Coord: NSObject, MKMapViewDelegate, UIGestureRecognizerDelegate {
        var parent: HomeMapView; var pins: [HomePin] = []; var sel: String?; var spot: LatLng?; var measure: LatLng?; var route: [LatLng] = []; var fitted = false; var names: [String] = []
        var touching = false
        init(_ p: HomeMapView) { parent = p }
        func style(_ v: MKAnnotationView, _ a: PinAnn) {
            let big = a.pin.id == parent.sel
            v.image = PinArt.pin(a.pin, big: big, dim: parent.sel != nil && !big)
            v.displayPriority = big ? .required : a.pin.cond == PinCond.bad ? .required : .defaultHigh
            v.zPriority = big ? .max : MKAnnotationViewZPriority(rawValue: Float(condRank(a.pin.cond)))
            v.clusteringIdentifier = big ? nil : "p"
            v.accessibilityLabel = a.pin.name + ", " + condName(a.pin.cond)
        }
        func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
            if let a = annotation as? PinAnn {
                let v = mapView.dequeueReusableAnnotationView(withIdentifier: "pin", for: a); v.annotation = a; v.canShowCallout = false; v.collisionMode = .circle; style(v, a); return v
            }
            if let c = annotation as? MKClusterAnnotation {
                let v = MKAnnotationView(annotation: c, reuseIdentifier: nil)
                var parts = [0, 0, 0, 0]; for m in c.memberAnnotations { if let p = m as? PinAnn { parts[condRank(p.pin.cond)] += 1 } }
                v.image = PinArt.group(parts, c.memberAnnotations.count); v.displayPriority = .required; v.collisionMode = .circle
                v.accessibilityLabel = t("A group of \(c.memberAnnotations.count): tap to zoom in", "Kundi la \(c.memberAnnotations.count): gusa ili kukuza")
                return v
            }
            if let n = annotation as? NameAnn {
                let v = MKAnnotationView(annotation: n, reuseIdentifier: nil); v.image = PinArt.name(n.pin.name); v.centerOffset = CGPoint(x: 0, y: 24)
                v.displayPriority = MKFeatureDisplayPriority(rawValue: Float(400 + condRank(n.pin.cond) * 50)); v.collisionMode = .rectangle; v.isEnabled = false; v.zPriority = .min
                return v
            }
            if annotation is SpotAnn {
                let v = MKAnnotationView(annotation: annotation, reuseIdentifier: nil)
                v.image = UIGraphicsImageRenderer(size: CGSize(width: 30, height: 30)).image { c in
                    c.cgContext.setFillColor(UIColor(white: 0, alpha: 0.28).cgColor); c.cgContext.fillEllipse(in: CGRect(x: 0, y: 0, width: 30, height: 30))
                    c.cgContext.setFillColor(UIColor.white.cgColor); c.cgContext.fillEllipse(in: CGRect(x: 2.5, y: 2.5, width: 25, height: 25))
                    c.cgContext.setFillColor(UIColor(red: 0x04 / 255, green: 0x26 / 255, blue: 0x3F / 255, alpha: 1).cgColor); c.cgContext.fillEllipse(in: CGRect(x: 6, y: 6, width: 18, height: 18))
                    c.cgContext.setFillColor(UIColor.white.cgColor); c.cgContext.fillEllipse(in: CGRect(x: 12, y: 12, width: 6, height: 6))
                }
                v.displayPriority = .required; return v
            }
            if let s = annotation as? StopAnn {
                let v = MKAnnotationView(annotation: s, reuseIdentifier: nil)
                v.image = UIGraphicsImageRenderer(size: CGSize(width: 22, height: 22)).image { c in
                    c.cgContext.setFillColor(UIColor.white.cgColor); c.cgContext.fillEllipse(in: CGRect(x: 0, y: 0, width: 22, height: 22))
                    c.cgContext.setFillColor(UIColor(red: 0.04, green: 0.47, blue: 0.76, alpha: 1).cgColor); c.cgContext.fillEllipse(in: CGRect(x: 2, y: 2, width: 18, height: 18))
                    let str = "\(s.n)" as NSString; let at: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 10, weight: .heavy), .foregroundColor: UIColor.white]
                    let sz = str.size(withAttributes: at); str.draw(at: CGPoint(x: 11 - sz.width / 2, y: 11 - sz.height / 2), withAttributes: at)
                }
                v.displayPriority = .required; v.zPriority = .max; return v
            }
            return nil
        }
        func mapView(_ mapView: MKMapView, didSelect view: MKAnnotationView) {
            if let a = view.annotation as? PinAnn { mapView.deselectAnnotation(a, animated: false); parent.onPin(a.pin.id); return }
            if let c = view.annotation as? MKClusterAnnotation {
                mapView.deselectAnnotation(c, animated: false)
                var r = MKMapRect.null
                for m in c.memberAnnotations { let p = MKMapPoint(m.coordinate); r = r.union(MKMapRect(x: p.x, y: p.y, width: 1, height: 1)) }
                mapView.setVisibleMapRect(r, edgePadding: UIEdgeInsets(top: 120, left: 60, bottom: 160, right: 80), animated: Motion.on)
                parent.onMoved()
            }
        }
        func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            if let tile = overlay as? MKTileOverlay { return MKTileOverlayRenderer(tileOverlay: tile) }
            if let l = overlay as? MKPolyline {
                let r = MKPolylineRenderer(polyline: l); r.lineWidth = l.title == "route" ? 4 : 3.5; r.lineCap = .round
                r.strokeColor = UIColor(red: 0.04, green: 0.47, blue: 0.76, alpha: l.title == "route" ? 0.75 : 1); r.lineDashPattern = [10, 7]
                return r
            }
            if let poly = overlay as? MKPolygon {
                let r = MKPolygonRenderer(polygon: poly); r.strokeColor = UIColor(red: 0.11, green: 0.54, blue: 0.31, alpha: 0.8); r.lineWidth = 2; r.lineDashPattern = [6, 5]
                r.fillColor = UIColor(red: 0.11, green: 0.54, blue: 0.31, alpha: 0.06); return r
            }
            return MKOverlayRenderer(overlay: overlay)
        }
        func mapView(_ mapView: MKMapView, regionWillChangeAnimated animated: Bool) {
            touching = mapView.subviews.first?.gestureRecognizers?.contains { $0.state == .began || $0.state == .changed } ?? false
        }
        func mapView(_ mapView: MKMapView, regionDidChangeAnimated animated: Bool) {
            updateNames(mapView)
            if touching { touching = false; parent.onMoved() }
            FieldOffline.viewed(mapView)
        }
        /// From about zoom 13, short names sit under the pins; MapKit hides a name rather than draw it over another.
        func updateNames(_ mv: MKMapView) {
            let close = mv.region.span.latitudeDelta < 0.06
            let want = close ? pins.filter { p in mv.region.contains(p.ll) }.prefix(120).map(\.id) : []
            if want == names { return }
            names = Array(want)
            mv.removeAnnotations(mv.annotations.filter { $0 is NameAnn })
            if close { mv.addAnnotations(pins.filter { names.contains($0.id) && !$0.name.isEmpty }
                .map { NameAnn($0) }) }
        }
        @objc func tapped(_ g: UITapGestureRecognizer) {
            guard let mv = g.view as? MKMapView else { return }
            let pt = g.location(in: mv)
            if let hit = mv.hitTest(pt, with: nil), hit is MKAnnotationView || hit.superview is MKAnnotationView { return }
            parent.onTap()
        }
        @objc func longPressed(_ g: UILongPressGestureRecognizer) {
            guard g.state == .began, let mv = g.view as? MKMapView else { return }
            let c = mv.convert(g.location(in: mv), toCoordinateFrom: mv)
            parent.onLong(LatLng(lat: c.latitude, lng: c.longitude))
        }
        func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }
    }
}

private extension MKCoordinateRegion {
    func contains(_ ll: LatLng) -> Bool { abs(ll.lat - center.latitude) <= span.latitudeDelta / 2 && abs(ll.lng - center.longitude) <= span.longitudeDelta / 2 }
}

// --- the Map tab --------------------------------------------------------------------------------------------------

/// Calm by default: a tap on empty map reveals the chips, the pinned bar, Nearest to you and the Key; kept while the app is open.
enum MapClean { static var revealed = false; static var hintSeen = false }

@MainActor
struct MapHome: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject private var loc = Locator.shared
    @ObservedObject private var net = NetWatch.shared
    @StateObject private var ctl = MapCtl()
    @State private var status = ""
    @State private var ty = ""
    @State private var sel: String?
    @State private var anchor: String?
    @State private var spot: LatLng?
    @State private var measuring = false
    @State private var revealed = MapClean.revealed
    @State private var rowOpen = false
    @State private var nearOpen = false
    @State private var keyOpen = false
    @State private var tray = false
    @State private var searchOpen = false
    @State private var styleSheet = false
    @State private var offlineSheet = false
    @State private var style = UserDefaults.standard.string(forKey: "map-style") ?? "map"
    @State private var hint = !UserDefaults.standard.bool(forKey: "calm_hint")
    @State private var ready = false

    var body: some View {
        if let snap = m.snap { content(snap) }
    }

    func content(_ snap: Snapshot) -> some View {
        let here: LatLng? = loc.fix?.ll
        let pinned: [Project] = snap.projects.filter { $0.gps != nil }
        let tyOn: String = !ty.isEmpty && pinned.contains { tgroup($0) == ty } ? ty : ""
        let filtered: Bool = !status.isEmpty || !tyOn.isEmpty
        let visible: [Project] = pinned.filter { mapStatusOk(m, snap, $0, status) && (tyOn.isEmpty || tgroup($0) == tyOn) }
        let pins: [HomePin] = pinsFor(snap, visible)
        let order: [Project] = orderFor(pinned, visible)
        let home: String = m.sx.home.isEmpty ? m.prefs.country : m.sx.home
        let homeList: [Project] = home.isEmpty ? pinned : pinned.filter { $0.ck == home }
        let fitFirst: [LatLng] = homeList.compactMap { $0.gps }
        let today = When.localDay()
        let route: [LatLng] = routeFor(snap, today)
        let calm = !revealed
        return ZStack {
            HomeMapView(ctl: ctl, pins: pins, sel: sel, spot: spot, measureFrom: measuring ? here : nil, route: route, style: style, offline: !net.online,
                        onPin: { id in pick(id, pinned, fly: false) }, onTap: { tapMap(filtered) }, onLong: { ll in
                            Haptic.tap(); sel = nil; anchor = nil; measuring = false; spot = ll; tray = false; keyOpen = false; ctl.follow = false
                        }, onMoved: { calmDown() }, fitFirst: fitFirst.isEmpty ? pinned.compactMap { $0.gps } : fitFirst)
            // the bottom panel: the card, the long-pressed spot, or Nearest to you
            VStack {
                Spacer()
                Group {
                    if let id = sel, let p = order.first(where: { $0.id == id }) ?? snap.projects.first(where: { $0.id == id }) {
                        MapPreviewCard(snap: snap, p: p, order: order, here: here, step: { d in step(d, order) }, close: closeCards)
                            .transition(.move(edge: .bottom).combined(with: .opacity)).id("card")
                    } else if let sp = spot {
                        MapSpotCard(sp: sp, here: here, measuring: measuring, close: closeCards, add: { MapNewSite.at = sp; closeCards(); m.tab("new") }) {
                            measuring.toggle(); if measuring, let h = here { ctl.fit([h, sp]) }
                        }.transition(.move(edge: .bottom).combined(with: .opacity))
                    } else if !calm {
                        MapNearest(snap: snap, here: here, pool: filtered ? visible : nil, slim: filtered, open: $nearOpen, emptyText: nearNone(tyOn, status))
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                }
                .padding(12)
            }
            .animation(Motion.on ? .spring(response: 0.32, dampingFraction: 0.9) : nil, value: sel)
            .animation(Motion.on ? .spring(response: 0.32, dampingFraction: 0.9) : nil, value: spot)
            // the controls at the top left and the column on the right
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .top, spacing: 10) {
                    VStack(alignment: .leading, spacing: 8) {
                        if calm && !searchOpen {
                            Button { Haptic.tap(); hint = false; searchOpen = true } label: {
                                Icon("search", size: 19, color: C.ink).frame(width: 44, height: 44).background(C.card.opacity(0.92)).clipShape(Circle()).overlay(Circle().stroke(C.line, lineWidth: 1)).shadow(color: .black.opacity(0.12), radius: 3, y: 1)
                            }.buttonStyle(.plain).accessibilityLabel(t("Search a village or project", "Tafuta kijiji au mradi"))
                        } else {
                            MapSearchBox(snap: snap, here: here, focusNow: searchOpen, close: { searchOpen = false }, onProject: { id in pick(id, pinned, fly: true) },
                                         onArea: { pts in ctl.follow = false; ctl.fit(pts); closeCards() }, onPlace: { ll in ctl.follow = false; ctl.fly(ll, span: 0.08); closeCards() })
                        }
                        if !calm || filtered {
                            if filtered && !rowOpen { MapFilterPill(status: status, ty: tyOn, n: visible.count, open: { Haptic.tap(); rowOpen = true }, clear: clearFilters) }
                            else { MapChips(snap: snap, pinned: pinned, status: status, ty: tyOn, onStatus: { k in status = status == k ? "" : k; rowOpen = false; if !status.isEmpty { nearOpen = false; fitAfter(snap, pinned) } },
                                            onType: { k in ty = tyOn == k ? "" : k; rowOpen = false; if !ty.isEmpty { nearOpen = false; fitAfter(snap, pinned) } }, clear: clearFilters) }
                            if filtered && visible.isEmpty {
                                Text(t("Nothing on the map matches these filters.", "Hakuna kwenye ramani kinacholingana na vichujio hivi.")).font(.lMedium).foregroundColor(C.ink)
                                    .padding(.horizontal, 12).padding(.vertical, 7).background(C.card.opacity(0.9)).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                            }
                        }
                        if !filtered && !calm && !nearOpen { MapPinnedBar(snap: snap, pn: pinned.count) }
                        HereSlot(s: snap, here: here, slim: true) { id, tab in m.go(Place(tab: "map", project: id, projectTab: tab)) }
                        if !net.online { MapNoSignal(saved: !FieldOffline.areas().isEmpty) { offlineSheet = true } }
                    }.frame(maxWidth: .infinity, alignment: .leading)
                    MapSide(follow: ctl.follow, tray: tray, styles: { tray = false; styleSheet = true }, me: {
                        tray = false
                        if let h = here { ctl.fly(h, span: 0.012); calmDown() } else { m.showBanner(t("Finding where you are… Turn on location.", "Inatafuta mahali ulipo… Washa mahali.")) }
                    }, more: { tray.toggle(); if tray { keyOpen = false; nearOpen = false } }, stopFollow: { ctl.follow = false })
                }
                if tray {
                    HStack { Spacer(); MapTray(follow: ctl.follow, offline: { tray = false; offlineSheet = true }, followMe: {
                        tray = false
                        if here == nil { m.showBanner(t("Finding where you are… Turn on location to follow.", "Inatafuta mahali ulipo… Washa mahali ili ikufuate.")) }
                        ctl.follow = true; closeCards()
                    }) }.transition(.opacity.combined(with: .move(edge: .top)))
                }
                Spacer()
                if hint && calm && sel == nil && spot == nil {
                    Button { hint = false } label: {
                        HStack(spacing: 8) { OIcon("hand", size: 17, color: .white); Text(t("Tap the map for filters", "Gusa ramani kuona vichujio")).font(.mont(13, .bold)).foregroundColor(.white).lineLimit(1).minimumScaleFactor(0.8) }
                            .padding(.leading, 12).padding(.trailing, 14).frame(minHeight: 44).background(Color(hex: 0x04263F, alpha: 0.9)).clipShape(Capsule())
                    }.buttonStyle(.plain).frame(maxWidth: .infinity).padding(.bottom, 70)
                    .onAppear { UserDefaults.standard.set(true, forKey: "calm_hint"); DispatchQueue.main.asyncAfter(deadline: .now() + 4.5) { hint = false } }
                }
                if !calm && sel == nil && spot == nil && !nearOpen {
                    VStack(alignment: .leading, spacing: 8) {
                        if keyOpen { MapKeyPop(stops: !route.isEmpty, build: pinned.contains { m.building($0) }).transition(.opacity.combined(with: .scale(scale: 0.94, anchor: .bottomLeading))) }
                        Button { Haptic.tap(); keyOpen.toggle(); if keyOpen { tray = false } } label: {
                            HStack(spacing: 5) { OIcon("info", size: 15, color: C.blue); Text(t("Key", "Rangi")).font(.mont(13, .heavy)).foregroundColor(C.ink).lineLimit(1) }
                                .padding(.leading, 10).padding(.trailing, 13).frame(minHeight: 36).background(C.card.opacity(0.92)).clipShape(Capsule()).overlay(Capsule().stroke(C.line, lineWidth: 1)).frame(minHeight: 44)
                        }.buttonStyle(.plain).accessibilityLabel(t("Map key: what the colours mean", "Maelezo ya rangi za ramani"))
                    }.padding(.bottom, 64)
                }
            }
            .padding(12)
            .animation(Motion.on ? .easeInOut(duration: 0.22) : nil, value: revealed)
            .animation(Motion.on ? .easeInOut(duration: 0.2) : nil, value: tray)
            .animation(Motion.on ? .easeInOut(duration: 0.2) : nil, value: keyOpen)
        }
        .onAppear {
            guard !ready else { return }
            FieldOffline.configure(m.prefs.loadSnapshot())
            ready = true; status = UserDefaults.standard.string(forKey: "mapf") ?? ""; ty = m.od.string(forKey: "ftype") ?? ""
            if m.demo, Demo.arg("-map") == "open" { revealed = true }
        }
        .onChange(of: keyOpen) { on in if on { DispatchQueue.main.asyncAfter(deadline: .now() + 5) { keyOpen = false } } }
        .sheet(isPresented: $styleSheet) { MapStyleSheet(style: style) { k in style = k; UserDefaults.standard.set(k, forKey: "map-style"); styleSheet = false } }
        .sheet(isPresented: $offlineSheet) { FieldOfflineSheet(ctl: ctl, street: { style = "street"; UserDefaults.standard.set("street", forKey: "map-style") }) { offlineSheet = false } }
    }

    func pinsFor(_ snap: Snapshot, _ visible: [Project]) -> [HomePin] {
        var out: [HomePin] = []
        for p in visible {
            guard let g = p.gps else { continue }
            out.append(HomePin(id: p.id, ll: g, cond: mapCondition(snap, p), type: typeLook(p).0, due: mapStatusOk(m, snap, p, "due"), name: shortName(p.title), build: m.building(p)))
        }
        return out
    }
    func orderFor(_ pinned: [Project], _ visible: [Project]) -> [Project] {
        guard let id = anchor, let a = pinned.first(where: { $0.id == id }), let ag = a.gps else { return [] }
        let rest: [Project] = visible.filter { $0.id != a.id && $0.gps != nil }
        let near: [Project] = rest.sorted { x, y in Geo.metres(ag, x.gps ?? ag) < Geo.metres(ag, y.gps ?? ag) }
        return [a] + Array(near.prefix(24))
    }
    func routeFor(_ snap: Snapshot, _ today: String) -> [LatLng] {
        var out: [LatLng] = []
        for a in snap.assigned where a.date == today { if let g = snap.projects.first(where: { $0.id == a.id })?.gps { out.append(g) } }
        return out
    }
    func pick(_ id: String, _ pinned: [Project], fly: Bool) {
        Haptic.tap(); ctl.follow = false; spot = nil; measuring = false; anchor = id; sel = id; tray = false; keyOpen = false
        if fly, let g = pinned.first(where: { $0.id == id })?.gps { ctl.fly(g, span: 0.01) }
    }
    func step(_ d: Int, _ order: [Project]) {
        guard let i = order.firstIndex(where: { $0.id == sel }), i + d >= 0, i + d < order.count else { return }
        Haptic.tap(); sel = order[i + d].id
        if let g = order[i + d].gps, let mv = ctl.mv { mv.setCenter(CLLocationCoordinate2D(latitude: g.lat, longitude: g.lng), animated: Motion.on) }
    }
    func closeCards() { sel = nil; anchor = nil; spot = nil; measuring = false }
    func clearFilters() { status = ""; ty = ""; UserDefaults.standard.set("", forKey: "mapf"); m.od.set("", forKey: "ftype"); rowOpen = false }
    func calmDown() { if revealed { revealed = false; MapClean.revealed = false; tray = false; keyOpen = false; nearOpen = false } }
    func tapMap(_ filtered: Bool) {
        if sel != nil || spot != nil { closeCards(); return }
        if rowOpen && filtered { rowOpen = false; return }
        Haptic.tap(); revealed.toggle(); MapClean.revealed = revealed; tray = false; keyOpen = false; hint = false
        if !revealed { nearOpen = false }
    }
    func fitAfter(_ snap: Snapshot, _ pinned: [Project]) {
        UserDefaults.standard.set(status, forKey: "mapf"); m.od.set(ty, forKey: "ftype")
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.06) {
            let vis = pinned.filter { mapStatusOk(m, snap, $0, status) && (ty.isEmpty || tgroup($0) == ty) }.compactMap(\.gps)
            ctl.fit(vis)
        }
    }
}

/// A point chosen with a long press: the New site screen starts there.
enum MapNewSite { static var at: LatLng? }

// --- the pieces over the map --------------------------------------------------------------------------------------

private struct MapGlass: ViewModifier {
    var r: CGFloat = 999
    func body(content: Content) -> some View {
        content.background(C.card.opacity(oSun ? 1 : C.dark ? 0.92 : 0.88)).clipShape(RoundedRectangle(cornerRadius: r, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: r, style: .continuous).stroke(C.line, lineWidth: 1)).shadow(color: .black.opacity(0.1), radius: 3, y: 1)
    }
}

/// One pill for the active filter(s): "Water · 98", tap for all the chips again, × clears.
private struct MapFilterPill: View {
    let status: String; let ty: String; let n: Int; let open: () -> Void; let clear: () -> Void
    var body: some View {
        var items: [(String, String, Hue)] = []
        if let x = MAP_TYPES.first(where: { $0.0 == ty }) { items.append((tgroupName(x.0), x.1, ohue(x.2))) }
        if let x = MAP_STATUS.first(where: { $0.0 == status }) { items.append((t(x.1, x.2), x.3, x.0 == "broken" ? Hue(fg: C.bad, bg: C.badBg) : x.0 == "due" ? ohue("due") : ohue("blue"))) }
        let hu = items.count == 1 ? items[0].2 : ohue("blue")
        return HStack(spacing: 0) {
            Button(action: open) {
                HStack(spacing: 6) {
                    OIcon(items.count == 1 ? items[0].1 : "filter", size: 15, color: .white)
                    Text(items.map(\.0).joined(separator: " + ")).font(.mont(13, .heavy)).foregroundColor(.white).lineLimit(1)
                    Text(" · \(n)").font(.mont(13, .bold)).foregroundColor(.white).lineLimit(1).fixedSize()
                    Icon("down", size: 14, color: .white.opacity(0.85))
                }.padding(.leading, 12).padding(.trailing, 2).frame(minHeight: 36)
            }.buttonStyle(.plain)
            Button(action: clear) { Icon("x", size: 12, color: .white).frame(width: 22, height: 22).background(Color.white.opacity(0.22)).clipShape(Circle()).frame(width: 44, height: 44) }
                .buttonStyle(.plain).accessibilityLabel(t("Clear filters", "Ondoa vichujio"))
        }.background(hu.fg).clipShape(Capsule()).frame(minHeight: 44)
    }
}

/// Broken, Due a visit, My assigned visits, then the kinds of project; each with how many there are. Swipe sideways.
@MainActor
private struct MapChips: View {
    @EnvironmentObject var m: AppModel
    let snap: Snapshot; let pinned: [Project]; let status: String; let ty: String; let onStatus: (String) -> Void; let onType: (String) -> Void; let clear: () -> Void
    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                if !status.isEmpty || !ty.isEmpty {
                    Button(action: clear) { Icon("x", size: 18, color: C.ink).frame(width: 36, height: 36).modifier(MapGlass()).frame(width: 44, height: 44) }.buttonStyle(.plain).accessibilityLabel(t("Clear filters", "Ondoa vichujio"))
                }
                ForEach(MAP_STATUS, id: \.0) { k, en, sw, icon in
                    let n = pinned.filter { mapStatusOk(m, snap, $0, k) && (ty.isEmpty || tgroup($0) == ty) }.count
                    chip(t(en, sw), icon, n, status == k, k == "broken" ? Hue(fg: C.bad, bg: C.badBg) : k == "due" ? ohue("due") : ohue("blue")) { onStatus(k) }
                }
                ForEach(MAP_TYPES.filter { k in pinned.contains { tgroup($0) == k.0 } }, id: \.0) { k, icon, hue in
                    let n = pinned.filter { tgroup($0) == k && mapStatusOk(m, snap, $0, status) }.count
                    chip(tgroupName(k), icon, n, ty == k, ohue(hue)) { onType(k) }
                }
            }.padding(.trailing, 20)
        }
    }
    func chip(_ label: String, _ icon: String, _ n: Int, _ on: Bool, _ hu: Hue, _ action: @escaping () -> Void) -> some View {
        Button { Haptic.tap(); action() } label: {
            HStack(spacing: 5) {
                OIcon(icon, size: 15, color: on ? .white : hu.fg)
                Text(label).font(.mont(13, .bold)).foregroundColor(on ? .white : C.ink).lineLimit(1).fixedSize()
                Text("\(n)").font(.mont(11, .bold)).foregroundColor(on ? hu.fg : C.muted).padding(.horizontal, 6).background(on ? Color.white : C.bg).clipShape(Capsule())
            }.padding(.leading, 10).padding(.trailing, 8).frame(minHeight: 36)
            .background(on ? hu.fg : C.card.opacity(0.9)).clipShape(Capsule()).overlay(Capsule().stroke(on ? hu.fg : C.line, lineWidth: 1)).frame(minHeight: 44)
        }.buttonStyle(.plain).accessibilityLabel("\(label), \(n)" + (on ? ", " + t("on", "imewashwa") : ""))
    }
}

/// "116 of 181 projects pinned · 65 to go" on one line, with a thin meter under it.
private struct MapPinnedBar: View {
    let snap: Snapshot; let pn: Int
    var body: some View {
        let all = snap.projects.count
        VStack(spacing: 5) {
            HStack(spacing: 8) {
                ViewThatFits(in: .horizontal) {
                    Text(t("\(pn) of \(all) projects pinned", "Miradi \(pn) kati ya \(all) ina mahali")).font(.mont(12, .bold)).foregroundColor(C.ink).lineLimit(1).fixedSize()
                    Text(t("\(pn)/\(all) pinned", "\(pn)/\(all) zina mahali")).font(.mont(12, .bold)).foregroundColor(C.ink).lineLimit(1)
                }.frame(maxWidth: .infinity, alignment: .leading)
                Text(t("\(all - pn) to go", "\(all - pn) imebaki")).font(.mont(11)).foregroundColor(C.muted).lineLimit(1).fixedSize()
            }
            GeometryReader { g in ZStack(alignment: .leading) { Capsule().fill(C.line); Capsule().fill(OkBrush).frame(width: g.size.width * CGFloat(pn) / CGFloat(max(1, all))) } }.frame(height: 4)
        }.padding(7).modifier(MapGlass(r: 18)).frame(maxWidth: 300, alignment: .leading)
    }
}

/// With no signal and no map kept on this phone the map is blank: this says why, and opens Offline areas.
private struct MapNoSignal: View {
    let saved: Bool; let open: () -> Void
    var body: some View {
        let hu = ohue("offline")
        Button(action: open) {
            HStack(spacing: 10) {
                OIcon("offline", size: 18, color: hu.fg).frame(width: 32, height: 32).background(hu.bg).clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                VStack(alignment: .leading, spacing: 1) {
                    Text(saved ? t("No signal: showing pins and saved map areas only.", "Hakuna mtandao: inaonyesha alama za miradi na maeneo yaliyohifadhiwa tu.") : t("No signal: showing pins only.", "Hakuna mtandao: inaonyesha alama za miradi tu."))
                        .font(.mont(13, .bold)).foregroundColor(C.ink).multilineTextAlignment(.leading)
                    Text(t("Save an offline map", "Hifadhi ramani ya bila mtandao") + " ›").font(.mont(13, .heavy)).foregroundColor(C.blue)
                }.frame(maxWidth: .infinity, alignment: .leading)
            }.padding(.leading, 10).padding(.trailing, 12).padding(.vertical, 8).frame(minHeight: 48).modifier(MapGlass(r: 16))
        }.buttonStyle(.plain)
    }
}

/// The column on the right: Map style, Go to where I am and More; Follow me while it is on (tap to stop).
private struct MapSide: View {
    let follow: Bool; let tray: Bool; let styles: () -> Void; let me: () -> Void; let more: () -> Void; let stopFollow: () -> Void
    var body: some View {
        VStack(spacing: 8) {
            VStack(spacing: 0) {
                btn("layers", t("Map style", "Mtindo wa ramani"), false, styles)
                Rectangle().fill(Color(hex: 0x04263F, alpha: 0.12)).frame(height: 1)
                btn("target", t("Go to where I am", "Nenda nilipo"), false, me)
                Rectangle().fill(Color(hex: 0x04263F, alpha: 0.12)).frame(height: 1)
                btn("more", t("More map tools", "Zana zaidi za ramani"), tray, more)
            }.frame(width: 44).background(Color.white.opacity(oSun ? 1 : 0.92)).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Color.black.opacity(0.16), lineWidth: 1)).shadow(color: .black.opacity(0.12), radius: 3, y: 1)
            if follow {
                Button { Haptic.tap(); stopFollow() } label: { Icon("nav", size: 24, color: .white).frame(width: 44, height: 44).background(Color(hex: 0x0A78C2)).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)) }
                    .buttonStyle(.plain).accessibilityLabel(t("Stop following me", "Acha kunifuata"))
            }
        }
    }
    func btn(_ icon: String, _ label: String, _ on: Bool, _ action: @escaping () -> Void) -> some View {
        Button { Haptic.tap(); action() } label: { OIcon(icon, size: 19, color: Color(hex: 0x04263F)).frame(width: 44, height: 44).background(on ? Color(hex: 0x0A78C2, alpha: 0.1) : .clear) }
            .buttonStyle(.plain).accessibilityLabel(label)
    }
}

/// Under More: Offline areas and Follow me, with their names.
private struct MapTray: View {
    let follow: Bool; let offline: () -> Void; let followMe: () -> Void
    var body: some View {
        VStack(spacing: 0) {
            row("offline", ohue("offline"), t("Offline areas", "Ramani bila mtandao"), offline)
            if !follow { row("nav", ohue("blue"), t("Follow me", "Nifuate"), followMe) }
        }.padding(6).frame(minWidth: 200).background(C.card).clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(C.line, lineWidth: 1)).shadow(color: .black.opacity(0.16), radius: 10, y: 4)
    }
    func row(_ icon: String, _ hu: Hue, _ label: String, _ action: @escaping () -> Void) -> some View {
        Button { Haptic.tap(); action() } label: {
            HStack(spacing: 12) { OIcon(icon, size: 20, color: hu.fg).frame(width: 34, height: 34).background(hu.bg).clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous)); Text(label).font(.tSmall).foregroundColor(C.ink).lineLimit(1).fixedSize(); Spacer(minLength: 0) }
                .padding(.leading, 6).padding(.trailing, 14).frame(minHeight: 48).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }
}

/// The colours of the pins (and the other marks), over the map above the Key button.
private struct MapKeyPop: View {
    let stops: Bool; let build: Bool
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach([PinCond.ok, PinCond.repair, PinCond.bad, PinCond.old], id: \.self) { c in
                row(condName(c)) { Circle().stroke(Color(condColor(c)), lineWidth: 3).frame(width: 11, height: 11).background(Circle().fill(Color.white)) }
            }
            row(t("Visit or water test due", "Ziara au kipimo cha maji kinasubiri")) { Circle().fill(Color(hex: 0x0A78C2)).frame(width: 7, height: 7).padding(1.5).background(Circle().fill(Color.white)) }
            if build { row(t("Being built", "Inajengwa")) { Image(systemName: "hammer.circle.fill").font(.system(size: 14)).foregroundColor(Color(hex: 0xB86E00)) } }
            row(t("A group: tap to zoom in", "Kundi: gusa ili kukuza")) { Text("3").font(.mont(8, .heavy)).foregroundColor(Color(hex: 0x04263F)).frame(width: 14, height: 14).background(Circle().fill(Color.white)).overlay(Circle().stroke(Color(condColor(PinCond.ok)), lineWidth: 2.4)) }
            row(t("You", "Wewe")) { Circle().fill(Color(hex: 0x098BE1)).frame(width: 8, height: 8).padding(2).background(Circle().fill(Color.white)).padding(2).background(Circle().fill(Color(hex: 0x098BE1, alpha: 0.22))) }
            if stops { row(t("Today’s route stop", "Kituo cha njia ya leo")) { Text("1").font(.mont(8, .heavy)).foregroundColor(.white).frame(width: 13, height: 13).background(Circle().fill(Color(hex: 0x0A78C2))).padding(1.5).background(Circle().fill(Color.white)) } }
        }
        .padding(.horizontal, 12).padding(.vertical, 10).background(C.card).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.line, lineWidth: 1)).shadow(color: .black.opacity(0.14), radius: 8, y: 3)
    }
    func row<M: View>(_ label: String, @ViewBuilder _ mark: () -> M) -> some View {
        HStack(spacing: 6) { mark().frame(width: 16, height: 16); Text(label).font(.mont(12, .medium)).foregroundColor(C.ink).lineLimit(1).fixedSize() }
    }
}

/// Map style: the map, satellite photos, or the free street map (OpenStreetMap, which can be kept for offline use).
private struct MapStyleSheet: View {
    let style: String; let pick: (String) -> Void
    var body: some View {
        OSheet {
            HStack { Text(t("Map style", "Mtindo wa ramani")).font(.tLarge).foregroundColor(C.ink).frame(maxWidth: .infinity, alignment: .leading); OCloseX { pick(style) } }
            ForEach([("map", t("Map", "Ramani"), "map", t("Roads and places", "Barabara na mahali")), ("sat", t("Satellite", "Satelaiti"), "satellite", t("Photos from above, with roads", "Picha kutoka juu, pamoja na barabara")),
                     ("street", t("Street map (OpenStreetMap)", "Ramani ya mitaa (OpenStreetMap)"), "offline", t("Free map; areas you save stay on the phone", "Ramani huru; maeneo unayohifadhi yanabaki kwenye simu"))], id: \.0) { k, label, icon, sub in
                Button { Haptic.tap(); pick(k) } label: {
                    HStack(spacing: 12) {
                        OTileIcon(icon: icon, tint: C.blue, bg: C.tint, size: 40)
                        VStack(alignment: .leading, spacing: 2) { Text(label).font(.tSmall).foregroundColor(C.ink).multilineTextAlignment(.leading); Text(sub).font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.leading) }.frame(maxWidth: .infinity, alignment: .leading)
                        Icon("check", size: 22, color: style == k ? C.blue : .clear)
                    }.padding(10).frame(minHeight: 60).background(style == k ? C.tint : .clear).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).contentShape(Rectangle())
                }.buttonStyle(.plain).padding(.top, 8)
            }
            if style == "street" { Text("© OpenStreetMap contributors").font(.bSmall).foregroundColor(C.muted).padding(.top, 10) }
        }
    }
}

/// Search on the map: projects and their villages or areas as you type (from the list on this phone, so it works with
/// no signal), and places by name when there is signal. Choosing one flies the map there.
private struct MapSearchBox: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject private var net = NetWatch.shared
    let snap: Snapshot; let here: LatLng?; let focusNow: Bool; let close: () -> Void
    let onProject: (String) -> Void; let onArea: ([LatLng]) -> Void; let onPlace: (LatLng) -> Void
    @State private var q = ""
    @State private var places: [(String, String, LatLng)] = []
    @FocusState private var focused: Bool
    var body: some View {
        let words = q.lowercased().split(whereSeparator: { $0 == " " }).map(String.init)
        let areas: [(String, [Project])] = areasFor(words)
        let projs: [Project] = projsFor(words)
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 9) {
                Icon("search", size: 18, color: focused ? C.blue : C.muted)
                TextField("", text: $q, prompt: Text(t("Search a village or project", "Tafuta kijiji au mradi")).foregroundColor(C.muted)).font(.bMedium).foregroundColor(C.text)
                    .focused($focused).submitLabel(.search).onSubmit {
                        if let p = projs.first { done(p.title); onProject(p.id) } else if let a = areas.first { done(a.0); onArea(a.1.compactMap(\.gps)) } else if let pl = places.first { done(pl.0); onPlace(pl.2) }
                    }
                if !q.isEmpty || focusNow { Button { q = ""; focused = false; close() } label: { Icon("x", size: 16, color: C.muted).frame(width: 44, height: 44) }.buttonStyle(.plain).accessibilityLabel(t("Clear", "Futa")) }
            }
            .padding(.leading, 12).frame(minHeight: 44).background(focused ? C.card : C.card.opacity(0.9)).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(focused ? C.blue : C.line, lineWidth: focused ? 1.5 : 1)).shadow(color: .black.opacity(0.12), radius: 3, y: 1)
            if focused && !words.isEmpty {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(areas, id: \.0) { a, l in
                            sugg("pin", ohue("green"), a, (l.first?.country ?? "") + " · " + (l.count == 1 ? t("1 project", "mradi 1") : t("\(l.count) projects", "miradi \(l.count)"))) { done(a); onArea(l.compactMap(\.gps)) }
                        }
                        ForEach(projs) { p in
                            let lk: (String, String) = typeLook(p)
                            sugg(lk.0, ohue(lk.1), p.title, [p.area, here.flatMap { h in p.gps.map { Geo.dist(Geo.metres(h, $0), miles: m.miles) } } ?? ""].filter { !$0.isEmpty }.joined(separator: " · ")) { done(p.title); onProject(p.id) }
                        }
                        ForEach(Array(places.enumerated()), id: \.offset) { _, pl in sugg("globe", ohue("slate"), pl.0, pl.1) { done(pl.0); onPlace(pl.2) } }
                        if areas.isEmpty && projs.isEmpty && places.isEmpty {
                            Text(net.online ? t("Nothing found yet. Try a village, school or town.", "Bado hakuna kilichopatikana. Jaribu kijiji, shule au mji.") : t("No project or area matches.", "Hakuna mradi wala eneo linalolingana."))
                                .font(.bMedium).foregroundColor(C.muted).padding(.horizontal, 16).padding(.vertical, 10)
                        }
                        if !places.isEmpty { Text(t("Places: © OpenStreetMap contributors", "Maeneo: © OpenStreetMap contributors")).font(.lSmall).foregroundColor(C.muted).padding(.horizontal, 16).padding(.vertical, 4) }
                        if !net.online { Text(t("No signal: searching the projects on this phone.", "Hakuna mtandao: inatafuta miradi iliyo kwenye simu hii.")).font(.lSmall).foregroundColor(C.muted).padding(.horizontal, 16).padding(.vertical, 4) }
                    }.padding(.vertical, 6)
                }.frame(maxHeight: 320).background(C.card).clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(C.line, lineWidth: 1)).shadow(color: .black.opacity(0.16), radius: 8, y: 3)
            }
        }
        .onAppear { if focusNow { DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { focused = true } } }
        .onChange(of: focused) { f in if !f && q.isEmpty { close() } }
        .task(id: q) {
            places = []
            let qq = q.trimmingCharacters(in: .whitespaces)
            guard net.online, qq.count >= 3 else { return }
            try? await Task.sleep(nanoseconds: 450_000_000)
            if Task.isCancelled { return }
            let r = await m.api.place(qq, near: here, ck: "")
            places = Array(r.json.objs("places").compactMap { o in o.num("lat").flatMap { la in o.num("lng").map { (o.str("name"), o.str("where"), LatLng(lat: la, lng: $0)) } } }.prefix(3))
        }
    }
    func areasFor(_ words: [String]) -> [(String, [Project])] {
        guard let w0 = words.first else { return [] }
        let pinned: [Project] = snap.projects.filter { $0.gps != nil && !$0.area.isEmpty }
        let groups: [String: [Project]] = Dictionary(grouping: pinned) { $0.area.trimmingCharacters(in: .whitespaces) }
        var hits: [(String, [Project])] = []
        for (a, l) in groups {
            let hay: String = (a + " " + (l.first?.country ?? "")).lowercased()
            if words.allSatisfy({ hay.contains($0) }) { hits.append((a, l)) }
        }
        hits.sort { x, y in
            let px: Int = x.0.lowercased().hasPrefix(w0) ? 0 : 1
            let py: Int = y.0.lowercased().hasPrefix(w0) ? 0 : 1
            return px != py ? px < py : x.1.count > y.1.count
        }
        return Array(hits.prefix(2))
    }
    func projsFor(_ words: [String]) -> [Project] {
        guard let w0 = words.first else { return [] }
        let hits: [Project] = snap.projects.filter { p in
            guard p.gps != nil else { return false }
            let hay: String = "\(p.title) \(p.area) \(p.country)".lowercased()
            return words.allSatisfy { hay.contains($0) }
        }
        let sorted: [Project] = hits.sorted { a, b in
            let pa: Int = a.title.lowercased().hasPrefix(w0) ? 0 : 1
            let pb: Int = b.title.lowercased().hasPrefix(w0) ? 0 : 1
            if pa != pb { return pa < pb }
            guard let h = here, let ga = a.gps, let gb = b.gps else { return false }
            return Geo.metres(h, ga) < Geo.metres(h, gb)
        }
        return Array(sorted.prefix(4))
    }
    func done(_ text: String) { q = text; focused = false }
    func sugg(_ icon: String, _ hu: Hue, _ title: String, _ sub: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                OTileIcon(icon: icon, tint: hu.fg, bg: hu.bg, size: 36)
                VStack(alignment: .leading, spacing: 1) { Text(title).font(.tSmall).foregroundColor(C.ink).lineLimit(1); if !sub.isEmpty { Text(sub).font(.bSmall).foregroundColor(C.muted).lineLimit(1) } }
                Spacer(minLength: 0)
            }.padding(.horizontal, 12).padding(.vertical, 6).frame(minHeight: 52).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }
}

/// The card for a tapped pin: photo, how it is, the last visit and how far; swipe sideways for the projects nearest it.
private struct MapPreviewCard: View {
    @EnvironmentObject var m: AppModel
    let snap: Snapshot; let p: Project; let order: [Project]; let here: LatLng?; let step: (Int) -> Void; let close: () -> Void
    @State private var dx: CGFloat = 0
    var body: some View {
        let i = order.firstIndex { $0.id == p.id } ?? -1
        let c = mapCondition(snap, p)
        let tone: Hue = c == PinCond.bad ? Hue(fg: C.bad, bg: C.badBg) : c == PinCond.repair ? Hue(fg: C.warn, bg: C.warnBg) : c == PinCond.old ? ohue("slate") : Hue(fg: C.ok, bg: C.okBg)
        let bg: Color = tone.bg
        let fg: Color = tone.fg
        let v = snap.visits[p.id]
        VStack(alignment: .leading, spacing: 0) {
            Capsule().fill(C.edge).frame(width: 36, height: 4).frame(maxWidth: .infinity).padding(.bottom, 1)
            HStack(spacing: 0) {
                VStack(alignment: .leading, spacing: 1) {
                    Text(order.count > 1 && i >= 0 ? t("\(i + 1) of \(order.count) nearby", "\(i + 1) kati ya \(order.count) karibu") : t("Project", "Mradi")).font(.lMedium).foregroundColor(C.ink).lineLimit(1)
                    if order.count > 1 { Text(t("Swipe for more", "Telezesha kuona zaidi")).font(.lSmall).foregroundColor(C.muted).lineLimit(1) }
                }.frame(maxWidth: .infinity, alignment: .leading)
                icon("back", t("Previous project nearby", "Mradi uliotangulia karibu"), i > 0) { step(-1) }
                icon("next", t("Next project nearby", "Mradi unaofuata karibu"), i >= 0 && i < order.count - 1) { step(1) }
                icon("x", t("Close", "Funga"), true, close)
            }
            HStack(alignment: .top, spacing: 11) {
                Color.clear.frame(width: 62, height: 62).overlay(NetImage(url: p.photo.map { m.api.photoUrl($0) }) { OIcon(typeLook(p).0, size: 25, color: fg) }).clipped()
                    .background(bg).clipShape(RoundedRectangle(cornerRadius: 15, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(p.title).font(.mont(15, .bold)).foregroundColor(C.ink).lineLimit(2)
                    Text([p.area, p.country].filter { !$0.isEmpty }.joined(separator: " · ")).font(.bSmall).foregroundColor(C.muted).lineLimit(1)
                    HStack(spacing: 6) { Circle().fill(Color(condColor(c))).frame(width: 9, height: 9); Text(condName(c)).font(.mont(12, .bold)).foregroundColor(fg).lineLimit(1) }
                        .padding(.leading, 8).padding(.trailing, 10).padding(.vertical, 4).background(bg).clipShape(Capsule()).padding(.top, 4)
                    if let h = here, let g = p.gps { Text(t("\(Geo.dist(Geo.metres(h, g), miles: m.miles)) from you", "\(Geo.dist(Geo.metres(h, g), miles: m.miles)) kutoka kwako")).font(.mont(13, .bold)).foregroundColor(C.deep).padding(.top, 4) }
                    Text(v.map { t("Last visit ", "Ziara ya mwisho ") + ODates.day($0.at) + ($0.by.isEmpty ? "" : " · " + $0.by) } ?? t("No visit in the app yet", "Bado hakuna ziara kwenye programu")).font(.bSmall).foregroundColor(C.text).lineLimit(2).padding(.top, 2)
                }.frame(maxWidth: .infinity, alignment: .leading)
            }.padding(.top, 6).padding(.trailing, 6).id(p.id).transition(.opacity)
            HStack(spacing: 8) {
                action(t("Report a visit", "Ripoti ziara"), "clip", true) { m.go(Place(tab: "map", project: p.id, projectTab: "visit")) }
                action(t("Directions", "Maelekezo"), "nav", false) { if let g = p.gps { Share.open(String(format: "https://www.google.com/maps/dir/?api=1&destination=%.6f,%.6f", g.lat, g.lng)) } }
                action(t("Open project", "Fungua mradi"), "chev", false) { m.go(Place(tab: "map", project: p.id)) }
            }.padding(.top, 9).padding(.trailing, 6)
        }
        .padding(.leading, 12).padding(.trailing, 6).padding(.top, 5).padding(.bottom, 10)
        .modifier(MapGlass(r: 22)).offset(x: dx)
        .gesture(DragGesture(minimumDistance: 20).onChanged { g in dx = g.translation.width / 3 }.onEnded { g in
            withAnimation(.spring(response: 0.3)) { dx = 0 }
            if g.translation.width < -56 { step(1) } else if g.translation.width > 56 { step(-1) }
        })
        .animation(Motion.on ? .easeInOut(duration: 0.2) : nil, value: p.id)
    }
    func icon(_ k: String, _ label: String, _ on: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) { Icon(k, size: 17, color: on ? C.ink : C.off).frame(width: 44, height: 44) }.buttonStyle(.plain).disabled(!on).accessibilityLabel(label)
    }
    func action(_ label: String, _ icon: String, _ primary: Bool, _ go: @escaping () -> Void) -> some View {
        let hu = ohue("map")
        return Button { Haptic.tap(); go() } label: {
            VStack(spacing: 3) {
                OIcon(icon, size: 18, color: primary ? .white : hu.fg)
                FitWords(label, size: 12, weight: .bold, color: primary ? .white : C.ink, lines: 2, align: .center, min: 0.75)
            }.padding(.horizontal, 4).padding(.vertical, 6).frame(maxWidth: .infinity, minHeight: 48)
            .background(Group { if primary { OkBrush } else { hu.bg } }).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }.buttonStyle(Press())
    }
}

/// After a long press: add a new site there, or measure how far it is from you.
private struct MapSpotCard: View {
    @EnvironmentObject var m: AppModel
    let sp: LatLng; let here: LatLng?; let measuring: Bool; let close: () -> Void; let add: () -> Void; let measure: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                VStack(alignment: .leading, spacing: 1) { Text(t("This spot", "Mahali hapa")).font(.tMedium).foregroundColor(C.ink); Text(sp.text()).font(.bSmall).foregroundColor(C.muted).lineLimit(1) }.frame(maxWidth: .infinity, alignment: .leading)
                Button(action: close) { Icon("x", size: 17, color: C.ink).frame(width: 44, height: 44) }.buttonStyle(.plain).accessibilityLabel(t("Close", "Funga"))
            }
            if measuring {
                if let h = here {
                    HStack(spacing: 10) {
                        Icon("compass", size: 22, color: C.blue)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(Geo.dist(Geo.metres(h, sp), miles: m.miles)).font(.tLarge).foregroundColor(C.ink)
                            Text(t("from you, to the \(Geo.heading(h, sp)) (straight line)", "kutoka kwako, upande wa \(Geo.heading(h, sp)) (mstari mnyoofu)")).font(.bSmall).foregroundColor(C.muted)
                        }
                    }.padding(.horizontal, 14).padding(.vertical, 10).frame(maxWidth: .infinity, alignment: .leading).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).padding(.top, 8).padding(.trailing, 8)
                } else { Text(t("Allow location to measure from where you are.", "Ruhusu mahali ili upime kutoka ulipo.")).font(.bMedium).foregroundColor(C.warn).padding(.top, 8) }
            }
            HStack(spacing: 8) {
                Button(action: add) {
                    VStack(spacing: 4) { Icon("plus", size: 20, color: .white); FitWords(t("New site here", "Eneo jipya hapa"), size: 12.5, weight: .bold, color: .white, lines: 2, align: .center) }
                        .padding(.horizontal, 6).padding(.vertical, 8).frame(maxWidth: .infinity, minHeight: 58).background(LinearGradient(colors: [Color(hex: 0x9A78F0), Color(hex: 0x6E46C8)], startPoint: .topLeading, endPoint: .bottomTrailing)).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                }.buttonStyle(Press())
                Button(action: measure) {
                    VStack(spacing: 4) { Icon("compass", size: 20, color: measuring ? .white : C.blue); FitWords(t("Measure from me", "Pima kutoka kwangu"), size: 12.5, weight: .bold, color: measuring ? .white : C.ink, lines: 2, align: .center) }
                        .padding(.horizontal, 6).padding(.vertical, 8).frame(maxWidth: .infinity, minHeight: 58).background(measuring ? C.blue : C.tint).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                }.buttonStyle(Press()).accessibilityLabel(measuring ? t("Stop measuring", "Acha kupima") : t("Measure from me", "Pima kutoka kwangu"))
            }.padding(.top, 12).padding(.trailing, 8)
        }
        .padding(.leading, 16).padding(.trailing, 8).padding(.top, 8).padding(.bottom, 14)
        .background(C.card).clipShape(RoundedRectangle(cornerRadius: 26, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 26, style: .continuous).stroke(C.line, lineWidth: 1))
        .shadow(color: .black.opacity(0.18), radius: 12, y: 4)
    }
}

/// "Nearest to you": the closest pinned projects; rests as one line (the closest), drag or tap it open for five.
private struct MapNearest: View {
    @EnvironmentObject var m: AppModel
    let snap: Snapshot; let here: LatLng?; let pool: [Project]?; let slim: Bool; @Binding var open: Bool; let emptyText: String
    var body: some View {
        let list = here.map { h in (pool ?? snap.projects).compactMap { p in p.gps.map { (p, Geo.metres(h, $0)) } }.sorted { $0.1 < $1.1 }.prefix(5).map { $0 } } ?? []
        VStack(alignment: .leading, spacing: 8) {
            Button { Haptic.tap(); withAnimation(Motion.on ? .spring(response: 0.32, dampingFraction: 0.9) : nil) { open.toggle() } } label: {
                HStack(spacing: 6) {
                    if let f = list.first, !open {
                        Text(t("Nearest:", "Karibu zaidi:")).font(.mont(14, .heavy)).foregroundColor(C.ink).lineLimit(1).fixedSize()
                        FitWords(f.0.title, size: 14, weight: .bold, color: C.ink, lines: 2, min: 0.78)
                        Text(" · " + Geo.dist(f.1, miles: m.miles)).font(.mont(14, .heavy)).foregroundColor(C.deep).lineLimit(1).fixedSize()
                    } else {
                        Text(here != nil && list.isEmpty ? emptyText : t("Nearest to you", "Iliyo karibu nawe")).font(.tSmall).foregroundColor(C.ink).frame(maxWidth: .infinity, alignment: .leading)
                    }
                    Spacer(minLength: 0)
                    Icon(open ? "down" : "up", size: 18, color: C.muted)
                }.frame(minHeight: 44).contentShape(Rectangle())
            }.buttonStyle(.plain)
            if open {
                if here == nil {
                    HStack {
                        Text(t("See the projects closest to you.", "Ona miradi iliyo karibu nawe.")).font(.bMedium).foregroundColor(C.muted).frame(maxWidth: .infinity, alignment: .leading)
                        Btn(t("Allow location", "Ruhusu mahali"), icon: "nav", light: true, small: true) { Locator.shared.start(); if Locator.shared.denied, let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) } }
                    }
                } else if list.isEmpty { Text(emptyText).font(.bMedium).foregroundColor(C.muted).padding(6) }
                ScrollView { VStack(spacing: 8) { ForEach(list, id: \.0.id) { p, _ in ProjectRow(snap: snap, p: p, here: here) { m.go(Place(tab: "map", project: p.id)) } } } }.frame(maxHeight: 360)
            }
        }
        .padding(.horizontal, 12).padding(.vertical, slim && !open ? 2 : 6).modifier(MapGlass(r: 22))
    }
}

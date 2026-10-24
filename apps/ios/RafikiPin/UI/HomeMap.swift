import MapKit
import SwiftUI
import UIKit

// The map itself on the Map tab: pins that say what they are (the kind's icon) and how they are (the ring's colour),
// groups as a donut split by how their projects are, short names under the pins when zoomed in, not-working sites
// pulsing, pins dropping in the first time; today's route, the long-pressed spot, the heat, the years and the team.
// The same as Android's MapPins.kt and MapLayers.kt.

/// Lets the screen move the map: fly to a point, fit a set of points clear of what is over the map.
final class MapCtl {
    weak var mv: MKMapView?
    func fly(_ ll: LatLng, metres: Double? = nil) {
        guard let mv else { return }
        let c = CLLocationCoordinate2D(latitude: ll.lat, longitude: ll.lng)
        if let metres { mv.setRegion(MKCoordinateRegion(center: c, latitudinalMeters: metres, longitudinalMeters: metres), animated: Motion.on) }
        else { mv.setCenter(c, animated: Motion.on) }
    }
    /// Fits the points inside the part of the map left free by what is over it; never closer than about street level.
    func fit(_ pts: [LatLng], pad: UIEdgeInsets, minMetres: Double = 1500) {
        guard let mv, !pts.isEmpty else { return }
        var r = MKMapRect.null
        for p in pts { let m = MKMapPoint(CLLocationCoordinate2D(latitude: p.lat, longitude: p.lng)); r = r.union(MKMapRect(x: m.x, y: m.y, width: 0, height: 0)) }
        let minW = minMetres * MKMapPointsPerMeterAtLatitude(pts[0].lat)
        if r.size.width < minW { r = r.insetBy(dx: -(minW - r.size.width) / 2, dy: 0) }
        if r.size.height < minW { r = r.insetBy(dx: 0, dy: -(minW - r.size.height) / 2) }
        mv.setVisibleMapRect(r, edgePadding: pad, animated: Motion.on)
    }
    /// Brings a chosen pin clear of the card and the controls: in the middle of the free part, at street level or closer.
    func focus(_ ll: LatLng, pad: UIEdgeInsets) {
        guard let mv else { return }
        let m = MKMapPoint(CLLocationCoordinate2D(latitude: ll.lat, longitude: ll.lng))
        let cur = mv.visibleMapRect.size
        let maxW = 3000 * MKMapPointsPerMeterAtLatitude(ll.lat)
        let k = cur.width > maxW ? maxW / cur.width : 1
        let w = cur.width * k, h = cur.height * k
        // the same zoom as the free part would show: shrink by the padding share
        let fw = w * Double(mv.bounds.width - pad.left - pad.right) / Double(max(1, mv.bounds.width))
        let fh = h * Double(mv.bounds.height - pad.top - pad.bottom) / Double(max(1, mv.bounds.height))
        mv.setVisibleMapRect(MKMapRect(x: m.x - fw / 2, y: m.y - fh / 2, width: fw, height: fh), edgePadding: pad, animated: Motion.on)
    }
    func me() { if let l = mv?.userLocation.location { fly(LatLng(lat: l.coordinate.latitude, lng: l.coordinate.longitude), metres: 2500) } }
    /// Where a point is on the screen (for the quick actions by a pin).
    func point(_ ll: LatLng) -> CGPoint? { mv.map { $0.convert(CLLocationCoordinate2D(latitude: ll.lat, longitude: ll.lng), toPointTo: $0) } }
}

// MARK: - the pictures, drawn once per look and kept

enum PinArt {
    private static var cache: [String: UIImage] = [:]
    static let dueColor = UIColor(red: 0x0A / 255, green: 0x78 / 255, blue: 0xC2 / 255, alpha: 1)
    static let ink = UIColor(red: 0x04 / 255, green: 0x26 / 255, blue: 0x3F / 255, alpha: 1)

    /// A project's pin: the kind's icon on white, a ring in the colour of how it is, a blue dot when something is due.
    static func pin(_ type: String, _ cond: String, due: Bool, big: Bool) -> UIImage {
        let key = "p\(type)|\(cond)|\(due)|\(big)"
        if let c = cache[key] { return c }
        let r: CGFloat = big ? 11.5 : 8.5, ring: CGFloat = big ? 3.5 : 3, edge: CGFloat = 1.5, halo: CGFloat = big ? 4.5 : 1.5
        let n = ceil(2 * (r + ring + edge + halo)) + 2, m = n / 2
        let img = UIGraphicsImageRenderer(size: CGSize(width: n, height: n)).image { ctx in
            let c = ctx.cgContext
            func circle(_ rad: CGFloat, _ col: UIColor) { col.setFill(); c.fillEllipse(in: CGRect(x: m - rad, y: m - rad, width: 2 * rad, height: 2 * rad)) }
            circle(r + ring + edge + halo, big ? UIColor(red: 0.04, green: 0.47, blue: 0.76, alpha: 0.4) : UIColor(white: 0, alpha: 0.28))
            circle(r + ring + edge, .white)
            circle(r + ring, condUIColor(cond))
            circle(r, .white)
            let s = r * 1.45
            if let sym = UIImage(systemName: Ic.name(type), withConfiguration: UIImage.SymbolConfiguration(pointSize: s, weight: .semibold))?.withTintColor(ink, renderingMode: .alwaysOriginal) {
                let k = min(s / sym.size.width, s / sym.size.height)
                let w = sym.size.width * k, h = sym.size.height * k
                sym.draw(in: CGRect(x: m - w / 2, y: m - h / 2, width: w, height: h))
            }
            if due {
                let o = (r + ring) * 0.72
                UIColor.white.setFill(); c.fillEllipse(in: CGRect(x: m + o - 4.4, y: m - o - 4.4, width: 8.8, height: 8.8))
                dueColor.setFill(); c.fillEllipse(in: CGRect(x: m + o - 3.1, y: m - o - 3.1, width: 6.2, height: 6.2))
            }
        }
        cache[key] = img
        return img
    }

    /// A group: a donut split by how its projects are (not working at the top, then needs repair, not visited, working),
    /// and the count in the middle.
    static func donut(_ parts: [Int], _ count: Int) -> UIImage {
        let label = count > 99 ? "99+" : "\(count)"
        let total = max(1, parts.reduce(0, +))
        let q = parts.map { v in v > 0 ? max(1, Int((Double(v) * 36 / Double(total)).rounded())) : 0 }
        let key = "g\(label)|\(q.map(String.init).joined(separator: ","))"
        if let c = cache[key] { return c }
        let big: CGFloat = count < 10 ? 15 : count < 100 ? 17 : 20, w: CGFloat = 4.5, edge: CGFloat = 1.5, halo: CGFloat = 1.5
        let n = ceil(2 * (big + edge + halo)) + 2, m = n / 2
        let img = UIGraphicsImageRenderer(size: CGSize(width: n, height: n)).image { ctx in
            let c = ctx.cgContext
            UIColor(white: 0, alpha: 0.28).setFill(); c.fillEllipse(in: CGRect(x: m - big - edge - halo, y: m - big - edge - halo, width: 2 * (big + edge + halo), height: 2 * (big + edge + halo)))
            UIColor.white.setFill(); c.fillEllipse(in: CGRect(x: m - big - edge, y: m - big - edge, width: 2 * (big + edge), height: 2 * (big + edge)))
            let qs = max(1, q.reduce(0, +)), many = q.filter { $0 > 0 }.count > 1
            var at: CGFloat = -.pi / 2
            c.setLineWidth(w); c.setLineCap(.butt)
            let ranked = [PinCond.ok, PinCond.old, PinCond.repair, PinCond.bad]
            for k in stride(from: 3, through: 0, by: -1) where q[k] > 0 {
                let sweep = 2 * .pi * CGFloat(q[k]) / CGFloat(qs), gap: CGFloat = many ? 0.026 : 0
                c.setStrokeColor(condUIColor(ranked[k]).cgColor)
                c.addArc(center: CGPoint(x: m, y: m), radius: big - w / 2, startAngle: at + gap, endAngle: at + sweep - gap, clockwise: false); c.strokePath()
                at += sweep
            }
            UIColor.white.setFill(); c.fillEllipse(in: CGRect(x: m - big + w, y: m - big + w, width: 2 * (big - w), height: 2 * (big - w)))
            let font = UIFont.systemFont(ofSize: count > 99 ? 11 : 13, weight: .heavy)
            let a: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: ink]
            let sz = (label as NSString).size(withAttributes: a)
            (label as NSString).draw(at: CGPoint(x: m - sz.width / 2, y: m - sz.height / 2), withAttributes: a)
        }
        cache[key] = img
        return img
    }

    /// A stop on today's route: numbered, ticked once visited, the next one purple and bigger.
    static func stop(_ n: Int, done: Bool, next: Bool) -> UIImage {
        let r: CGFloat = next ? 19 : 15, s = 2 * r + (next ? 12 : 4), cx = s / 2
        return UIGraphicsImageRenderer(size: CGSize(width: s, height: s)).image { ctx in
            let c = ctx.cgContext
            if next { UIColor(red: 0.48, green: 0.31, blue: 0.82, alpha: 0.28).setFill(); c.fillEllipse(in: CGRect(x: cx - r - 5, y: cx - r - 5, width: 2 * r + 10, height: 2 * r + 10)) }
            UIColor.white.setFill(); c.fillEllipse(in: CGRect(x: cx - r, y: cx - r, width: 2 * r, height: 2 * r))
            (done ? UIColor(red: 0.11, green: 0.61, blue: 0.35, alpha: 1) : next ? UIColor(red: 0.48, green: 0.31, blue: 0.82, alpha: 1) : UIColor(red: 0.04, green: 0.47, blue: 0.76, alpha: 1)).setFill()
            c.fillEllipse(in: CGRect(x: cx - r + 2.6, y: cx - r + 2.6, width: 2 * r - 5.2, height: 2 * r - 5.2))
            if done {
                c.setStrokeColor(UIColor.white.cgColor); c.setLineWidth(3); c.setLineCap(.round); c.setLineJoin(.round)
                c.move(to: CGPoint(x: cx - 6, y: cx + 0.5)); c.addLine(to: CGPoint(x: cx - 1.8, y: cx + 4.6)); c.addLine(to: CGPoint(x: cx + 6.5, y: cx - 4.5)); c.strokePath()
            } else {
                let a: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: next ? 16 : 13, weight: .heavy), .foregroundColor: UIColor.white]
                let t = "\(n)" as NSString, sz = t.size(withAttributes: a)
                t.draw(at: CGPoint(x: cx - sz.width / 2, y: cx - sz.height / 2), withAttributes: a)
            }
        }
    }

    /// A project on the time slider; the year's new ones glow.
    static func year(fresh: Bool) -> UIImage {
        let s: CGFloat = 34, cx = s / 2
        return UIGraphicsImageRenderer(size: CGSize(width: s, height: s)).image { ctx in
            let c = ctx.cgContext, blue = UIColor(red: 0.18, green: 0.66, blue: 0.96, alpha: 1)
            if fresh { blue.withAlphaComponent(0.15).setFill(); c.fillEllipse(in: CGRect(x: cx - 16, y: cx - 16, width: 32, height: 32)); blue.withAlphaComponent(0.35).setFill(); c.fillEllipse(in: CGRect(x: cx - 12, y: cx - 12, width: 24, height: 24)) }
            UIColor.white.setFill(); c.fillEllipse(in: CGRect(x: cx - 9, y: cx - 9, width: 18, height: 18))
            (fresh ? blue : UIColor(red: 0.04, green: 0.47, blue: 0.76, alpha: 1)).setFill(); c.fillEllipse(in: CGRect(x: cx - 6.6, y: cx - 6.6, width: 13.2, height: 13.2))
        }
    }

    static let MATE: [UIColor] = [0x0A78C2, 0x1C9C5A, 0xD98200, 0x7A4FD0, 0xD23C32, 0x0E8F96].map { UIColor(red: CGFloat(($0 >> 16) & 255) / 255, green: CGFloat(($0 >> 8) & 255) / 255, blue: CGFloat($0 & 255) / 255, alpha: 1) }
    /// A teammate: their initials in a circle, how long ago under it.
    static func mate(_ name: String, _ color: UIColor, _ label: String, faded: Bool) -> UIImage {
        let lf = UIFont.systemFont(ofSize: 10, weight: .bold)
        let lw = (label as NSString).size(withAttributes: [.font: lf]).width
        let w = max(72, lw + 20), h: CGFloat = 66, r: CGFloat = 21, cx = w / 2, cy = r + 2
        return UIGraphicsImageRenderer(size: CGSize(width: w, height: h)).image { ctx in
            let c = ctx.cgContext
            UIColor.white.setFill(); c.fillEllipse(in: CGRect(x: cx - r, y: cy - r, width: 2 * r, height: 2 * r))
            color.withAlphaComponent(faded ? 0.6 : 1).setFill(); c.fillEllipse(in: CGRect(x: cx - r + 3, y: cy - r + 3, width: 2 * r - 6, height: 2 * r - 6))
            let ia: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 13, weight: .heavy), .foregroundColor: UIColor.white]
            let ini = initialsOf(name) as NSString, isz = ini.size(withAttributes: ia)
            ini.draw(at: CGPoint(x: cx - isz.width / 2, y: cy - isz.height / 2), withAttributes: ia)
            let tw = lw + 12, top = cy + r + 3
            UIColor.white.setFill(); UIBezierPath(roundedRect: CGRect(x: cx - tw / 2, y: top, width: tw, height: 16), cornerRadius: 8).fill()
            (label as NSString).draw(at: CGPoint(x: cx - lw / 2, y: top + 2), withAttributes: [.font: lf, .foregroundColor: ink])
        }
    }

    /// The long-pressed spot.
    static let spot: UIImage = UIGraphicsImageRenderer(size: CGSize(width: 30, height: 30)).image { ctx in
        let c = ctx.cgContext
        UIColor(white: 0, alpha: 0.28).setFill(); c.fillEllipse(in: CGRect(x: 2.5, y: 2.5, width: 25, height: 25))
        UIColor.white.setFill(); c.fillEllipse(in: CGRect(x: 3.5, y: 3.5, width: 23, height: 23))
        ink.setFill(); c.fillEllipse(in: CGRect(x: 6, y: 6, width: 18, height: 18))
        UIColor.white.setFill(); c.fillEllipse(in: CGRect(x: 12, y: 12, width: 6, height: 6))
    }
}

// MARK: - what is on the map

final class PinAnn: NSObject, MKAnnotation {
    let pin: HomePin
    var coordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: pin.ll.lat, longitude: pin.ll.lng) }
    var title: String? { pin.name }
    init(_ p: HomePin) { pin = p }
}
final class MarkAnn: NSObject, MKAnnotation {
    enum Kind { case stop(Int, Bool, Bool), spot, mate(Int), year(Bool) }
    let kind: Kind; let key: String; let image: UIImage; let label: String
    let coordinate: CLLocationCoordinate2D
    var title: String? { label }
    init(_ kind: Kind, key: String, at: LatLng, image: UIImage, label: String = "") {
        self.kind = kind; self.key = key; self.image = image; self.label = label; coordinate = CLLocationCoordinate2D(latitude: at.lat, longitude: at.lng)
    }
}

/// A project pin, with its short name under it when the map is close enough, and a pulse when not working.
final class PinView: MKAnnotationView {
    let name = UILabel()
    private var ring: CAShapeLayer?
    override init(annotation: MKAnnotation?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        clusteringIdentifier = "p"; collisionMode = .circle
        name.font = .systemFont(ofSize: 11, weight: .bold); name.textColor = PinArt.ink; name.textAlignment = .center
        name.layer.shadowColor = UIColor.white.cgColor; name.layer.shadowOpacity = 1; name.layer.shadowRadius = 1.6; name.layer.shadowOffset = .zero
        addSubview(name)
    }
    required init?(coder: NSCoder) { fatalError() }
    func show(_ p: HomePin, big: Bool, labels: Bool) {
        image = PinArt.pin(p.type, p.cond, due: p.due, big: big)
        displayPriority = big ? .required : MKFeatureDisplayPriority(rawValue: 500 + Float(condRank(p.cond)) * 100)
        zPriority = big ? .max : MKAnnotationViewZPriority(rawValue: Float(condRank(p.cond)) * 100)
        name.text = p.name; name.sizeToFit()
        let sz = image?.size ?? .zero
        name.frame = CGRect(x: (sz.width - name.bounds.width) / 2 - 4, y: sz.height - 2, width: name.bounds.width + 8, height: name.bounds.height)
        name.isHidden = !labels || p.name.isEmpty
        if p.cond == PinCond.bad && Motion.on { pulse(sz) } else { ring?.removeFromSuperlayer(); ring = nil }
    }
    private func pulse(_ sz: CGSize) {
        ring?.removeFromSuperlayer()
        let l = CAShapeLayer(); let r: CGFloat = 10
        l.path = UIBezierPath(ovalIn: CGRect(x: -r, y: -r, width: 2 * r, height: 2 * r)).cgPath
        l.position = CGPoint(x: sz.width / 2, y: sz.height / 2); l.fillColor = UIColor.clear.cgColor
        l.strokeColor = condUIColor(PinCond.bad).cgColor; l.lineWidth = 2.6
        let sc = CABasicAnimation(keyPath: "transform.scale"); sc.fromValue = 1; sc.toValue = 2.8
        let op = CABasicAnimation(keyPath: "opacity"); op.fromValue = 0.5; op.toValue = 0
        let g = CAAnimationGroup(); g.animations = [sc, op]; g.duration = 2.4; g.repeatCount = .infinity
        l.add(g, forKey: "pulse"); layer.insertSublayer(l, at: 0); ring = l
    }
    override func prepareForReuse() { super.prepareForReuse(); ring?.removeFromSuperlayer(); ring = nil; transform = .identity; alpha = 1 }
}

final class GroupView: MKAnnotationView {
    override init(annotation: MKAnnotation?, reuseIdentifier: String?) { super.init(annotation: annotation, reuseIdentifier: reuseIdentifier); collisionMode = .circle; displayPriority = .required }
    required init?(coder: NSCoder) { fatalError() }
    func show(_ c: MKClusterAnnotation) {
        var parts = [0, 0, 0, 0]
        for m in c.memberAnnotations { if let p = m as? PinAnn { parts[condRank(p.pin.cond)] += 1 } }
        image = PinArt.donut(parts, c.memberAnnotations.count)
        zPriority = MKAnnotationViewZPriority(rawValue: 600)
    }
}

/// The heat: soft spots, coloured from how much is close by.
final class HeatOverlay: NSObject, MKOverlay {
    let pts: [(MKMapPoint, Double)]; let kind: String; let max: Double
    var coordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: 0, longitude: 0) }
    var boundingMapRect: MKMapRect { .world }
    init(_ pts: [(LatLng, Double)], kind: String) {
        self.pts = pts.map { (MKMapPoint(CLLocationCoordinate2D(latitude: $0.0.lat, longitude: $0.0.lng)), $0.1) }; self.kind = kind
        self.max = Swift.max(kind == "people" ? 1500 : 2, pts.map(\.1).max() ?? 1)
    }
}
final class HeatRenderer: MKOverlayRenderer {
    override func draw(_ mapRect: MKMapRect, zoomScale: MKZoomScale, in ctx: CGContext) {
        guard let h = overlay as? HeatOverlay else { return }
        let r = 42 / zoomScale
        let cols = (HEAT_COLORS[h.kind] ?? HEAT_COLORS["repairs"]!).map { UIColor(red: CGFloat(($0 >> 16) & 255) / 255, green: CGFloat(($0 >> 8) & 255) / 255, blue: CGFloat($0 & 255) / 255, alpha: 1) }
        let area = mapRect.insetBy(dx: -r, dy: -r)
        for (p, w) in h.pts where area.contains(p) {
            let f = Swift.min(1, Swift.max(0.18, sqrt(w / h.max)))
            let col = cols[Swift.min(cols.count - 1, Int(f * Double(cols.count - 1) + 0.5))]
            let c = point(for: p)
            let space = CGColorSpaceCreateDeviceRGB()
            guard let g = CGGradient(colorsSpace: space, colors: [col.withAlphaComponent(0.75 * f + 0.15).cgColor, col.withAlphaComponent(0.35 * f).cgColor, col.withAlphaComponent(0).cgColor] as CFArray, locations: [0, 0.45, 1]) else { continue }
            ctx.drawRadialGradient(g, startCenter: c, startRadius: 0, endCenter: c, endRadius: r, options: [])
        }
    }
}

/// A line on the map: today's route, the way to the next stop (dotted) or the measuring line (dashed).
final class LineOverlay: MKPolyline { var color: UIColor = .white; var width: CGFloat = 5; var dash: [NSNumber]? }

// MARK: - the map view

struct HomeMap: UIViewRepresentable {
    let pins: [HomePin]
    let sel: String?
    let sat: Bool
    let follow: Bool
    let marks: [MarkAnn]
    let lines: [LineOverlay]
    let heat: HeatOverlay?
    let fitFirst: [LatLng]
    let ctl: MapCtl
    var onPin: (String) -> Void
    var onMark: (MarkAnn) -> Void
    var onTapEmpty: () -> Void
    var onLong: (LatLng) -> Void
    var onLongPin: (String) -> Void
    var onFollowEnd: () -> Void

    func makeCoordinator() -> Coord { Coord(self) }
    func makeUIView(context: Context) -> MKMapView {
        let mv = MKMapView()
        mv.delegate = context.coordinator
        mv.showsUserLocation = true; mv.showsCompass = true; mv.pointOfInterestFilter = .excludingAll
        mv.register(PinView.self, forAnnotationViewWithReuseIdentifier: "pin")
        mv.register(GroupView.self, forAnnotationViewWithReuseIdentifier: MKMapViewDefaultClusterAnnotationViewReuseIdentifier)
        mv.setRegion(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: -1.5, longitude: 36), span: MKCoordinateSpan(latitudeDelta: 9, longitudeDelta: 9)), animated: false)
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coord.tapped(_:)))
        tap.delegate = context.coordinator
        let long = UILongPressGestureRecognizer(target: context.coordinator, action: #selector(Coord.pressed(_:)))
        long.delegate = context.coordinator
        mv.addGestureRecognizer(tap); mv.addGestureRecognizer(long)
        ctl.mv = mv
        return mv
    }

    func updateUIView(_ mv: MKMapView, context: Context) {
        let c = context.coordinator
        c.parent = self
        ctl.mv = mv
        mv.mapType = sat ? .hybrid : .standard
        // the pins: replaced when what they show changes; the chosen one grows
        let key = pins.map { "\($0.id)|\($0.cond)|\($0.due)" }
        if key != c.pinKey {
            c.pinKey = key
            let old = mv.annotations.filter { $0 is PinAnn }
            mv.removeAnnotations(old)
            mv.addAnnotations(pins.map(PinAnn.init))
        }
        if sel != c.sel {
            let was = c.sel; c.sel = sel
            for a in mv.annotations { if let p = a as? PinAnn, p.pin.id == was || p.pin.id == sel, let v = mv.view(for: p) as? PinView { v.show(p.pin, big: p.pin.id == sel, labels: c.labels) } }
        }
        // the marks: route stops, the spot, teammates, the years
        let mk = marks.map(\.key)
        if mk != c.markKey {
            c.markKey = mk
            mv.removeAnnotations(mv.annotations.filter { $0 is MarkAnn })
            mv.addAnnotations(marks)
        }
        // lines and heat
        let lk = lines.map { "\($0.pointCount)|\($0.color)|\($0.coordinate.latitude)|\($0.coordinate.longitude)" } + [heat.map { "h\($0.kind)\($0.pts.count)" } ?? ""]
        if lk != c.lineKey {
            c.lineKey = lk
            mv.removeOverlays(mv.overlays)
            if let heat { mv.addOverlay(heat, level: .aboveRoads) }
            mv.addOverlays(lines, level: .aboveLabels)
        }
        // the first view: the home country's projects (or all of them)
        if !c.fitted, !fitFirst.isEmpty {
            c.fitted = true
            DispatchQueue.main.async { self.ctl.fit(self.fitFirst, pad: UIEdgeInsets(top: 70, left: 30, bottom: 60, right: 70)) }
        }
        // follow me: centred on you, turned to your heading, until you drag the map
        if follow && mv.userTrackingMode != .followWithHeading { mv.setUserTrackingMode(.followWithHeading, animated: Motion.on) }
        if !follow && mv.userTrackingMode != .none { mv.setUserTrackingMode(.none, animated: Motion.on) }
    }

    final class Coord: NSObject, MKMapViewDelegate, UIGestureRecognizerDelegate {
        var parent: HomeMap
        var pinKey: [String] = []; var markKey: [String] = []; var lineKey: [String] = []
        var sel: String?; var fitted = false; var labels = false
        init(_ p: HomeMap) { parent = p }

        func mapView(_ mapView: MKMapView, viewFor a: MKAnnotation) -> MKAnnotationView? {
            if let c = a as? MKClusterAnnotation {
                let v = mapView.dequeueReusableAnnotationView(withIdentifier: MKMapViewDefaultClusterAnnotationViewReuseIdentifier, for: c) as! GroupView
                v.show(c); return v
            }
            if let p = a as? PinAnn {
                let v = mapView.dequeueReusableAnnotationView(withIdentifier: "pin", for: p) as! PinView
                v.show(p.pin, big: p.pin.id == sel, labels: labels); return v
            }
            if let m = a as? MarkAnn {
                let v = mapView.dequeueReusableAnnotationView(withIdentifier: "mark") ?? MKAnnotationView(annotation: m, reuseIdentifier: "mark")
                v.annotation = m; v.image = m.image; v.canShowCallout = false
                v.displayPriority = .required; v.zPriority = .max
                if case .mate = m.kind { v.centerOffset = CGPoint(x: 0, y: m.image.size.height / 2 - 23) } else { v.centerOffset = .zero }
                return v
            }
            return nil
        }
        func mapView(_ mapView: MKMapView, didSelect view: MKAnnotationView) {
            guard let a = view.annotation else { return }
            mapView.deselectAnnotation(a, animated: false)
            if let c = a as? MKClusterAnnotation {
                // a group: zoom in to it
                var r = MKMapRect.null
                for m in c.memberAnnotations { let p = MKMapPoint(m.coordinate); r = r.union(MKMapRect(x: p.x, y: p.y, width: 0, height: 0)) }
                let minW = 600 * MKMapPointsPerMeterAtLatitude(c.coordinate.latitude)
                if r.size.width < minW { r = r.insetBy(dx: -(minW - r.size.width) / 2, dy: -(minW - r.size.width) / 2) }
                mapView.setVisibleMapRect(r, edgePadding: UIEdgeInsets(top: 90, left: 50, bottom: 90, right: 80), animated: Motion.on)
                Haptic.tap()
            } else if let p = a as? PinAnn { parent.onPin(p.pin.id) }
            else if let m = a as? MarkAnn { parent.onMark(m) }
        }
        func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            if let h = overlay as? HeatOverlay { return HeatRenderer(overlay: h) }
            if let l = overlay as? LineOverlay {
                let r = MKPolylineRenderer(polyline: l); r.strokeColor = l.color; r.lineWidth = l.width; r.lineCap = .round; r.lineJoin = .round; r.lineDashPattern = l.dash; return r
            }
            return MKOverlayRenderer(overlay: overlay)
        }
        // names under the pins from about zoom 13
        func mapView(_ mapView: MKMapView, regionDidChangeAnimated animated: Bool) {
            let on = mapView.region.span.latitudeDelta < 0.07
            if on != labels {
                labels = on
                for a in mapView.annotations { if let p = a as? PinAnn, let v = mapView.view(for: p) as? PinView { v.name.isHidden = !on || p.pin.name.isEmpty } }
            }
        }
        // pins drop in the first time the map shows them, north to south, with a small bounce
        func mapView(_ mapView: MKMapView, didAdd views: [MKAnnotationView]) {
            guard !HomeLayers.s.dropped, Motion.on else { return }
            let pins = views.filter { $0 is PinView || $0 is GroupView }
            guard !pins.isEmpty else { return }
            HomeLayers.s.dropped = true
            for (i, v) in pins.sorted(by: { ($0.annotation?.coordinate.latitude ?? 0) > ($1.annotation?.coordinate.latitude ?? 0) }).prefix(400).enumerated() {
                v.transform = CGAffineTransform(translationX: 0, y: -40); v.alpha = 0
                UIView.animate(withDuration: 0.6, delay: min(Double(i) * 0.014, 0.7), usingSpringWithDamping: 0.6, initialSpringVelocity: 0.5) { v.transform = .identity; v.alpha = 1 }
            }
        }
        func mapView(_ mapView: MKMapView, didChange mode: MKUserTrackingMode, animated: Bool) {
            if mode == .none && parent.follow { DispatchQueue.main.async { self.parent.onFollowEnd() } }
        }

        // a tap on empty map, a long press on the map or a pin
        private func onAnnotation(_ v: UIView?) -> MKAnnotationView? {
            var x = v; while let y = x { if let a = y as? MKAnnotationView { return a }; x = y.superview }; return nil
        }
        func gestureRecognizer(_ g: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
            if g is UITapGestureRecognizer { return onAnnotation(touch.view) == nil }
            return true
        }
        func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith o: UIGestureRecognizer) -> Bool { true }
        func gestureRecognizer(_ g: UIGestureRecognizer, shouldRequireFailureOf o: UIGestureRecognizer) -> Bool {
            if g is UITapGestureRecognizer, let t = o as? UITapGestureRecognizer, t.numberOfTapsRequired == 2 { return true }
            return false
        }
        @objc func tapped(_ g: UITapGestureRecognizer) { if g.state == .ended { parent.onTapEmpty() } }
        @objc func pressed(_ g: UILongPressGestureRecognizer) {
            guard g.state == .began, let mv = g.view as? MKMapView else { return }
            let pt = g.location(in: mv)
            if let av = onAnnotation(mv.hitTest(pt, with: nil)) {
                if let p = av.annotation as? PinAnn { Haptic.tap(); parent.onLongPin(p.pin.id) }
                return
            }
            let c = mv.convert(pt, toCoordinateFrom: mv)
            Haptic.tap(); parent.onLong(LatLng(lat: c.latitude, lng: c.longitude))
        }
    }
}

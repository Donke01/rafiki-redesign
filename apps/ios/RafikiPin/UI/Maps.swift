import MapKit
import SwiftUI

/// A project dot on a map.
struct Dot: Equatable { var id: String; var ll: LatLng; var color: UIColor; var big: Bool; var title: String }

final class DotPin: NSObject, MKAnnotation {
    let dot: Dot
    var coordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: dot.ll.lat, longitude: dot.ll.lng) }
    var title: String? { dot.title }
    init(_ d: Dot) { dot = d }
}

private func dotImage(_ c: UIColor, big: Bool) -> UIImage {
    let s: CGFloat = big ? 18 : 15
    return UIGraphicsImageRenderer(size: CGSize(width: s + 4, height: s + 4)).image { ctx in
        let r = CGRect(x: 2, y: 2, width: s, height: s)
        ctx.cgContext.setFillColor(c.cgColor); ctx.cgContext.fillEllipse(in: r)
        ctx.cgContext.setStrokeColor(UIColor.white.cgColor); ctx.cgContext.setLineWidth(2.5); ctx.cgContext.strokeEllipse(in: r)
    }
}

/// A map of project dots. Tap a dot to open the project.
struct ProjectsMap: UIViewRepresentable {
    let dots: [Dot]
    let satellite: Bool
    let onDot: (String) -> Void
    @Binding var goMe: Int

    func makeCoordinator() -> Coord { Coord(self) }
    func makeUIView(context: Context) -> MKMapView {
        let mv = MKMapView()
        mv.delegate = context.coordinator
        mv.showsUserLocation = true
        mv.pointOfInterestFilter = .excludingAll
        mv.setRegion(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: -2.5, longitude: 35.5), span: MKCoordinateSpan(latitudeDelta: 16, longitudeDelta: 16)), animated: false)
        return mv
    }
    func updateUIView(_ mv: MKMapView, context: Context) {
        context.coordinator.parent = self
        mv.mapType = satellite ? .hybrid : .standard
        if context.coordinator.shown != dots {
            context.coordinator.shown = dots
            mv.removeAnnotations(mv.annotations.filter { $0 is DotPin })
            mv.addAnnotations(dots.map(DotPin.init))
            if !context.coordinator.fitted, dots.count > 1 {
                context.coordinator.fitted = true
                var r = MKMapRect.null
                for d in dots { let p = MKMapPoint(CLLocationCoordinate2D(latitude: d.ll.lat, longitude: d.ll.lng)); r = r.union(MKMapRect(x: p.x, y: p.y, width: 1, height: 1)) }
                mv.setVisibleMapRect(r, edgePadding: UIEdgeInsets(top: 150, left: 40, bottom: 110, right: 40), animated: false)
            }
        }
        if goMe != context.coordinator.lastMe {
            context.coordinator.lastMe = goMe
            if let l = mv.userLocation.location { mv.setRegion(MKCoordinateRegion(center: l.coordinate, latitudinalMeters: 2500, longitudinalMeters: 2500), animated: true) }
        }
    }

    final class Coord: NSObject, MKMapViewDelegate {
        var parent: ProjectsMap; var shown: [Dot] = []; var fitted = false; var lastMe = 0
        init(_ p: ProjectsMap) { parent = p }
        func mapView(_ mapView: MKMapView, viewFor a: MKAnnotation) -> MKAnnotationView? {
            guard let p = a as? DotPin else { return nil }
            let v = mapView.dequeueReusableAnnotationView(withIdentifier: "dot") ?? MKAnnotationView(annotation: p, reuseIdentifier: "dot")
            v.annotation = p; v.image = dotImage(p.dot.color, big: p.dot.big); v.canShowCallout = false
            v.displayPriority = p.dot.big ? .required : .defaultHigh
            return v
        }
        func mapView(_ mapView: MKMapView, didSelect view: MKAnnotationView) {
            if let p = view.annotation as? DotPin { mapView.deselectAnnotation(p, animated: false); parent.onDot(p.dot.id) }
        }
    }
}

/// Choose a spot: move the map under the cross in the middle.
struct PickMap: UIViewRepresentable {
    let start: LatLng?
    let jumpTo: LatLng?
    let others: [Dot]
    let satellite: Bool
    let onMove: (LatLng) -> Void

    func makeCoordinator() -> Coord { Coord(self) }
    func makeUIView(context: Context) -> MKMapView {
        let mv = MKMapView()
        mv.delegate = context.coordinator
        mv.showsUserLocation = true
        mv.pointOfInterestFilter = .excludingAll
        if let s = start { mv.setRegion(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: s.lat, longitude: s.lng), latitudinalMeters: 400, longitudinalMeters: 400), animated: false) }
        else { mv.setRegion(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: -2.5, longitude: 35.5), span: MKCoordinateSpan(latitudeDelta: 16, longitudeDelta: 16)), animated: false) }
        mv.addAnnotations(others.map(DotPin.init))
        return mv
    }
    func updateUIView(_ mv: MKMapView, context: Context) {
        context.coordinator.parent = self
        mv.mapType = satellite ? .hybrid : .standard
        if let j = jumpTo, j != context.coordinator.lastJump {
            context.coordinator.lastJump = j
            let span = min(mv.region.span.latitudeDelta, 0.004)
            mv.setRegion(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: j.lat, longitude: j.lng), span: MKCoordinateSpan(latitudeDelta: span, longitudeDelta: span)), animated: true)
            DispatchQueue.main.async { self.onMove(j) }
        }
    }

    final class Coord: NSObject, MKMapViewDelegate {
        var parent: PickMap; var lastJump: LatLng?
        init(_ p: PickMap) { parent = p }
        func mapView(_ mapView: MKMapView, regionDidChangeAnimated animated: Bool) {
            let c = mapView.centerCoordinate
            parent.onMove(LatLng(lat: c.latitude, lng: c.longitude))
        }
        func mapView(_ mapView: MKMapView, viewFor a: MKAnnotation) -> MKAnnotationView? {
            guard let p = a as? DotPin else { return nil }
            let v = MKAnnotationView(annotation: p, reuseIdentifier: nil); v.image = dotImage(p.dot.color, big: false); return v
        }
    }
}

/// A round button on top of a map.
struct MapButton: View {
    let icon: String; let label: String; let action: () -> Void
    var body: some View {
        Button(action: action) {
            Icon(icon, size: 24, color: Color(hex: 0x04263F)).frame(width: 46, height: 46).background(Color.white)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Color.black.opacity(0.2), lineWidth: 1))
        }.accessibilityLabel(label)
    }
}

/// PickMap with the cross and the satellite switch.
struct PickMapBox: View {
    let start: LatLng?; let jumpTo: LatLng?; let others: [Dot]; let onMove: (LatLng) -> Void
    @State private var sat = true
    var body: some View {
        ZStack(alignment: .topTrailing) {
            PickMap(start: start, jumpTo: jumpTo, others: others, satellite: sat, onMove: onMove)
            Image(systemName: "scope").font(.system(size: 38, weight: .semibold)).foregroundColor(RedC).shadow(color: .white, radius: 1).frame(maxWidth: .infinity, maxHeight: .infinity).allowsHitTesting(false)
            MapButton(icon: sat ? "map" : "satellite", label: sat ? t("Show the street map", "Onyesha ramani ya barabara") : t("Show satellite photos", "Onyesha picha za satelaiti")) { sat.toggle() }.padding(10)
        }
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}

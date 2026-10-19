import CoreLocation
import Foundation

/// Where the phone is, live.
struct Fix: Equatable { var ll: LatLng; var acc: Int; var at: Date }

final class Locator: NSObject, ObservableObject, CLLocationManagerDelegate {
    static let shared = Locator()
    @Published var fix: Fix?
    private let lm = CLLocationManager()
    private var running = false

    override init() {
        super.init()
        lm.delegate = self
        lm.desiredAccuracy = kCLLocationAccuracyBest
        lm.distanceFilter = kCLDistanceFilterNone
    }

    var allowed: Bool { [.authorizedWhenInUse, .authorizedAlways].contains(lm.authorizationStatus) }
    var denied: Bool { [.denied, .restricted].contains(lm.authorizationStatus) }

    func start() {
        if lm.authorizationStatus == .notDetermined { lm.requestWhenInUseAuthorization(); return }
        guard allowed, !running else { return }
        running = true
        lm.startUpdatingLocation()
    }
    func stop() { lm.stopUpdatingLocation(); running = false }

    func locationManagerDidChangeAuthorization(_ m: CLLocationManager) { if allowed { running = false; start() } }

    func locationManager(_ m: CLLocationManager, didUpdateLocations locs: [CLLocation]) {
        guard let l = locs.last, l.horizontalAccuracy >= 0 else { return }
        let f = Fix(ll: LatLng(lat: l.coordinate.latitude, lng: l.coordinate.longitude), acc: max(1, Int(l.horizontalAccuracy.rounded())), at: l.timestamp)
        // keep a better recent fix rather than jumping to a vague one
        if let cur = fix, f.acc > cur.acc, f.at.timeIntervalSince(cur.at) < 20 { return }
        DispatchQueue.main.async { self.fix = f }
    }
    func locationManager(_ m: CLLocationManager, didFailWithError error: Error) {}
}

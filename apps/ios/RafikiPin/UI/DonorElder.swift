import SwiftUI
import UIKit
import MapKit
import AVFoundation

// Donor mode made easier for older donors (the same as the web and Android): an interactive map with big pins,
// big buttons and a card that steps through the projects; photos full screen with a slideshow; "Read it to me";
// and call or WhatsApp the office.

let DONOR_PHONE = "+1 801 547 7847"
let DONOR_WHATSAPP = "+254 791 674 001"

private let D_HEX: [String: UInt32] = ["working": 0x1C7C4A, "repairing": 0xC77700, "notworking": 0xB3261E, "building": 0x5B3CC4, "planned": 0x5E7A90, "done": 0x0A78C2]
private let D_MARK: [String: String] = ["working": "checkmark", "repairing": "wrench.adjustable.fill", "notworking": "exclamationmark", "building": "square.stack.3d.up.fill", "planned": "clock", "done": "checkmark"]
func donorStateColor(_ k: String) -> Color { Color(hex: D_HEX[k] ?? 0x0A78C2) }
private func donorUIColor(_ k: String) -> UIColor { let h = D_HEX[k] ?? 0x0A78C2; return UIColor(red: CGFloat((h >> 16) & 255) / 255, green: CGFloat((h >> 8) & 255) / 255, blue: CGFloat(h & 255) / 255, alpha: 1) }
func donorIcon(_ p: DProject) -> String { p.water ? "drop" : p.cat == "school" ? "school" : p.cat == "children" ? "house" : p.cat == "health" ? "cross" : "spark" }

/// "About 620 people are served here." (or pupils, patients, children), for the card and for reading aloud.
func donorPeopleSay(_ p: DProject) -> String {
    if p.status != "Completed" { return p.people > 0 ? t("When it’s finished, about \(donorNum(p.people)) people will be served.", "Ukikamilika, takriban watu \(donorNum(p.people)) watahudumiwa.") : "" }
    if p.pupils > 0 { return t("\(donorNum(p.pupils)) pupils learn here.", "Wanafunzi \(donorNum(p.pupils)) wanasoma hapa.") }
    if p.patients > 0 { return t("About \(donorNum(p.patients)) patients are seen here every week.", "Takriban wagonjwa \(donorNum(p.patients)) wanahudumiwa hapa kila wiki.") }
    if p.children > 0 { return t("\(donorNum(p.children)) children are cared for here.", "Watoto \(donorNum(p.children)) wanatunzwa hapa.") }
    return p.people > 0 ? t("About \(donorNum(p.people)) people are served here.", "Takriban watu \(donorNum(p.people)) wanahudumiwa hapa.") : ""
}

// ---- read it to me ------------------------------------------------------------------------------------------------
/// The phone's own voice reads a card aloud; one thing at a time, and the same button stops it.
final class DonorSpeaker: NSObject, ObservableObject, AVSpeechSynthesizerDelegate {
    static let shared = DonorSpeaker()
    private let synth = AVSpeechSynthesizer()
    @Published var speaking: String?
    override init() { super.init(); synth.delegate = self }
    func toggle(_ key: String, _ text: String) {
        if speaking == key { stop(); return }
        synth.stopSpeaking(at: .immediate)
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
        let u = AVSpeechUtterance(string: text)
        u.voice = AVSpeechSynthesisVoice(language: Lang.sw ? "sw-KE" : "en-GB") ?? AVSpeechSynthesisVoice(language: "en-GB")
        u.rate = AVSpeechUtteranceDefaultSpeechRate * 0.9
        speaking = key
        synth.speak(u)
    }
    func stop() { synth.stopSpeaking(at: .immediate); speaking = nil }
    func speechSynthesizer(_ s: AVSpeechSynthesizer, didFinish u: AVSpeechUtterance) { DispatchQueue.main.async { self.speaking = nil } }
    func speechSynthesizer(_ s: AVSpeechSynthesizer, didCancel u: AVSpeechUtterance) { DispatchQueue.main.async { if !s.isSpeaking { self.speaking = nil } } }
}

/// "Read it to me" (or "Listen"), which turns into "Stop reading" while the phone reads.
struct SpeakBtn: View {
    let key: String; let text: String; var label = t("Read it to me", "Nisomee"); var small = false; var wide = true
    @ObservedObject var sp = DonorSpeaker.shared
    var body: some View {
        let on = sp.speaking == key
        Btn(on ? t("Stop reading", "Acha kusoma") : label, icon: on ? "pause" : "speak", light: true, small: small, wide: wide) { sp.toggle(key, text) }
            .accessibilityIdentifier("speak_\(key)")
    }
}

// ---- call, WhatsApp or e-mail the office ----------------------------------------------------------------------------
struct DonorTalk: View {
    @ObservedObject var d = DonorState.shared
    let p: DProject?
    var body: some View {
        let who = d.view?.viewer ?? ""
        VStack(spacing: 10) {
            row("phone", C.blue2, t("Call RAFIKI", "Piga simu RAFIKI"), DONOR_PHONE, wa: false) { Share.open("tel:" + DONOR_PHONE.filter { $0.isNumber || $0 == "+" }) }
            row("chat", Color(hex: 0x1F8F4E), t("WhatsApp us", "Tuandikie WhatsApp"), DONOR_WHATSAPP, wa: true) {
                let msg = "Hello RAFIKI, this is \(who)" + (p.map { ". A question about \($0.title): " } ?? ". ")
                Share.open("https://wa.me/" + DONOR_WHATSAPP.filter { $0.isNumber } + "?text=" + Api.enc(msg))
            }
            row("mail", C.blue2, t("E-mail us", "Tutumie barua pepe"), DONOR_CONTACT, wa: false) { Links.mail(p.map { "Question about \($0.title)" } ?? "My RAFIKI projects", to: DONOR_CONTACT) }
        }
    }
    func row(_ icon: String, _ col: Color, _ title: String, _ sub: String, wa: Bool, _ go: @escaping () -> Void) -> some View {
        Button { Haptic.tap(); go() } label: {
            HStack(spacing: 14) {
                Group {
                    if wa { Icon("b:whatsapp", size: 30).frame(width: 44, height: 44).background(Color.white).clipShape(Circle()).overlay(Circle().stroke(C.line, lineWidth: 1)) }
                    else { Icon(icon, size: 22, color: .white).frame(width: 44, height: 44).background(col).clipShape(Circle()) }
                }
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).font(.mont(17, .bold)).foregroundColor(C.ink)
                    Text(sub.nb).font(.mont(14, .semibold)).foregroundColor(C.muted).lineLimit(1).minimumScaleFactor(0.75)
                }
                Spacer(minLength: 0)
                Icon("chev", size: 18, color: C.muted)
            }
            .padding(.leading, 12).padding(.trailing, 14).padding(.vertical, 12).frame(maxWidth: .infinity, minHeight: 70).background(C.card)
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(C.ink.opacity(0.08), lineWidth: 1))
            .shadow(color: Color(hex: 0x04263F).opacity(0.06), radius: 2, y: 1)
        }.buttonStyle(Press())
    }
}

// ---- the map ------------------------------------------------------------------------------------------------------
private var donorFlown = false

/// A project's pin: its kind's icon on white inside a ring in its state's colour (dashed while planned), the state's
/// small mark at the corner; the chosen one is bigger and filled.
private func donorPin(_ p: DProject, on: Bool) -> UIImage {
    let col = donorUIColor(p.state)
    let r: CGFloat = on ? 30 : 25, ring: CGFloat = 5, R = r + ring, bd: CGFloat = 12
    let n = 2 * R + 16, c0 = R + 4
    return UIGraphicsImageRenderer(size: CGSize(width: n, height: n)).image { ctx in
        let g = ctx.cgContext
        g.setFillColor(UIColor.black.withAlphaComponent(0.25).cgColor); g.fillEllipse(in: CGRect(x: c0 - R - 1.5, y: c0 - R + 0.5, width: 2 * R + 3, height: 2 * R + 3))
        g.setFillColor(UIColor.white.cgColor); g.fillEllipse(in: CGRect(x: c0 - R, y: c0 - R, width: 2 * R, height: 2 * R))
        if on { g.setFillColor(col.cgColor); g.fillEllipse(in: CGRect(x: c0 - R, y: c0 - R, width: 2 * R, height: 2 * R)) }
        else {
            g.setStrokeColor(col.cgColor); g.setLineWidth(ring)
            if p.state == "planned" { g.setLineDash(phase: 0, lengths: [7, 5]) }
            g.strokeEllipse(in: CGRect(x: c0 - R + ring / 2, y: c0 - R + ring / 2, width: 2 * R - ring, height: 2 * R - ring)); g.setLineDash(phase: 0, lengths: [])
        }
        let sym = UIImage(systemName: Ic.name(donorIcon(p)), withConfiguration: UIImage.SymbolConfiguration(pointSize: r * 0.85, weight: .semibold))?.withTintColor(on ? .white : col, renderingMode: .alwaysOriginal)
        if let sym { sym.draw(in: CGRect(x: c0 - sym.size.width / 2, y: c0 - sym.size.height / 2, width: sym.size.width, height: sym.size.height)) }
        let bx = c0 + R * 0.72, by = c0 + R * 0.72
        g.setFillColor(UIColor.white.cgColor); g.fillEllipse(in: CGRect(x: bx - bd, y: by - bd, width: 2 * bd, height: 2 * bd))
        g.setFillColor(col.cgColor); g.fillEllipse(in: CGRect(x: bx - bd + 3, y: by - bd + 3, width: 2 * bd - 6, height: 2 * bd - 6))
        if let m = UIImage(systemName: D_MARK[p.state] ?? "checkmark", withConfiguration: UIImage.SymbolConfiguration(pointSize: 11, weight: .heavy))?.withTintColor(.white, renderingMode: .alwaysOriginal) {
            m.draw(in: CGRect(x: bx - m.size.width / 2, y: by - m.size.height / 2, width: m.size.width, height: m.size.height))
        }
    }
}

private final class DonorPinAnn: MKPointAnnotation { var pid = "" }

/// Moves a donor map from outside: zoom in or out, show all, fly to a project.
final class DonorMapCtl: ObservableObject {
    weak var mv: MKMapView?
    var pts: [DProject] = []
    func zoom(_ k: Double) {
        guard let mv else { return }
        var r = mv.region; r.span.latitudeDelta = min(120, max(0.002, r.span.latitudeDelta * k)); r.span.longitudeDelta = min(120, max(0.002, r.span.longitudeDelta * k))
        mv.setRegion(r, animated: Motion.on)
    }
    func fit(_ anim: Bool) {
        guard let mv else { return }
        let cs = pts.compactMap { $0.gps }.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lng) }
        guard !cs.isEmpty else { return }
        if cs.count == 1 { mv.setRegion(MKCoordinateRegion(center: cs[0], latitudinalMeters: 500, longitudinalMeters: 500), animated: anim); return }
        var rect = MKMapRect.null
        for c in cs { let pnt = MKMapPoint(c); rect = rect.union(MKMapRect(x: pnt.x, y: pnt.y, width: 0, height: 0)) }
        mv.setVisibleMapRect(rect, edgePadding: UIEdgeInsets(top: 80, left: 70, bottom: 70, right: 80), animated: anim)
    }
    func fly(_ p: DProject) {
        guard let mv, let g = p.gps else { return }
        let c = CLLocationCoordinate2D(latitude: g.lat, longitude: g.lng)
        // straight down to the exact spot: the building itself on the satellite photos
        let span = mv.mapType == .standard ? 0.0025 : 0.0012
        if Motion.on { UIView.animate(withDuration: 1.2) { mv.setRegion(MKCoordinateRegion(center: c, span: MKCoordinateSpan(latitudeDelta: span, longitudeDelta: span)), animated: true) } }
        else { mv.setRegion(MKCoordinateRegion(center: c, span: MKCoordinateSpan(latitudeDelta: span, longitudeDelta: span)), animated: false) }
    }
}

private struct DonorMKMap: UIViewRepresentable {
    let pts: [DProject]; let sat: Bool; let cur: String; let chosen: Bool; let ctl: DonorMapCtl; let first: Bool; let onPick: (String) -> Void
    func makeCoordinator() -> Co { Co(self) }
    func makeUIView(context: Context) -> MKMapView {
        let mv = MKMapView(); mv.delegate = context.coordinator; mv.showsCompass = false; mv.pointOfInterestFilter = .excludingAll
        mv.setRegion(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: 0.6, longitude: 36.4), span: MKCoordinateSpan(latitudeDelta: 9, longitudeDelta: 9)), animated: false)
        for p in pts { guard let g = p.gps else { continue }; let a = DonorPinAnn(); a.pid = p.id; a.title = p.title; a.coordinate = CLLocationCoordinate2D(latitude: g.lat, longitude: g.lng); mv.addAnnotation(a) }
        ctl.mv = mv; ctl.pts = pts
        // the first time: from East Africa, fly down to the projects; after that, open on them
        if first && !donorFlown && Motion.on { donorFlown = true; DispatchQueue.main.asyncAfter(deadline: .now() + 0.7) { UIView.animate(withDuration: 1.5) { ctl.fit(true) } } }
        else { DispatchQueue.main.async { ctl.fit(false) } }
        return mv
    }
    func updateUIView(_ mv: MKMapView, context: Context) {
        context.coordinator.p = self
        mv.mapType = sat ? .satellite : .standard
        ctl.mv = mv; ctl.pts = pts
        for a in mv.annotations.compactMap({ $0 as? DonorPinAnn }) {
            guard let v = mv.view(for: a), let p = pts.first(where: { $0.id == a.pid }) else { continue }
            let on = a.pid == cur
            v.image = donorPin(p, on: on); v.alpha = chosen && !on ? 0.6 : 1; v.zPriority = on ? .max : .defaultUnselected
        }
    }
    final class Co: NSObject, MKMapViewDelegate {
        var p: DonorMKMap
        init(_ p: DonorMKMap) { self.p = p }
        func mapView(_ mv: MKMapView, viewFor a: MKAnnotation) -> MKAnnotationView? {
            guard let a = a as? DonorPinAnn, let pr = p.pts.first(where: { $0.id == a.pid }) else { return nil }
            let v = mv.dequeueReusableAnnotationView(withIdentifier: "dp") ?? MKAnnotationView(annotation: a, reuseIdentifier: "dp")
            v.annotation = a; v.canShowCallout = false
            let on = a.pid == p.cur
            v.image = donorPin(pr, on: on); v.alpha = p.chosen && !on ? 0.6 : 1; v.zPriority = on ? .max : .defaultUnselected
            v.accessibilityLabel = pr.title + ", " + donorStateName(pr.state)
            return v
        }
        func mapView(_ mv: MKMapView, didSelect v: MKAnnotationView) {
            if let a = v.annotation as? DonorPinAnn { Haptic.tap(); p.onPick(a.pid) }
            mv.deselectAnnotation(v.annotation, animated: false)
        }
    }
}

/// The map with big + and −, and Map | Satellite.
private struct DonorMapBox: View {
    let pts: [DProject]; @Binding var sat: Bool; let cur: String; let chosen: Bool; @ObservedObject var ctl: DonorMapCtl; let first: Bool; let onPick: (String) -> Void
    var body: some View {
        ZStack {
            DonorMKMap(pts: pts, sat: sat, cur: cur, chosen: chosen, ctl: ctl, first: first, onPick: onPick)
            VStack {
                HStack(alignment: .top) {
                    HStack(spacing: 0) {
                        ForEach([(false, t("Map", "Ramani")), (true, t("Satellite", "Satelaiti"))], id: \.0) { k, l in
                            Button { Haptic.tap(); sat = k } label: {
                                Text(l).font(.mont(15, .heavy)).foregroundColor(sat == k ? .white : Color(hex: 0x04263F)).padding(.horizontal, 16).frame(minHeight: 46)
                                    .background(sat == k ? Color(hex: 0x0A78C2) : Color.clear).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                            }.buttonStyle(.plain).accessibilityAddTraits(sat == k ? .isSelected : [])
                        }
                    }.padding(4).background(Color.white).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).shadow(color: .black.opacity(0.2), radius: 6, y: 2)
                    Spacer()
                    VStack(spacing: 10) {
                        ForEach([("+", t("Zoom in", "Karibia"), 0.5), ("−", t("Zoom out", "Ondoka mbali"), 2.0)], id: \.0) { s, label, k in
                            Button { Haptic.tap(); ctl.zoom(k) } label: {
                                Text(s).font(.system(size: 30, weight: .heavy)).foregroundColor(Color(hex: 0x04263F)).frame(width: 60, height: 60).background(Color.white)
                                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Color.black.opacity(0.15), lineWidth: 1))
                                    .shadow(color: .black.opacity(0.2), radius: 6, y: 2)
                            }.buttonStyle(Press()).accessibilityLabel(label)
                        }
                    }
                }
                Spacer()
            }.padding(10)
        }
    }
}

/// Your projects on the map: the map, Show all and Bigger map, the colour key, and (on Home) a big card that steps
/// through the projects with Previous and Next.
struct DonorMap: View {
    let projects: [DProject]; let withCard: Bool; let open: (String) -> Void
    @ObservedObject var d = DonorState.shared
    @State private var sat = false
    @State private var cur = ""
    @State private var chosen = false
    @State private var full = false
    @State private var dir = 1
    @StateObject private var ctl = DonorMapCtl()
    @StateObject private var ctlFull = DonorMapCtl()
    var body: some View {
        let pts = projects.filter { $0.gps != nil }
        if !pts.isEmpty {
            let id = pts.contains(where: { $0.id == cur }) ? cur : pts[0].id
            VStack(spacing: 0) {
                DonorMapBox(pts: pts, sat: $sat, cur: id, chosen: chosen, ctl: ctl, first: withCard, onPick: { dir = 1; pick($0) })
                    .frame(height: withCard ? 380 : 260)
                HStack(spacing: 10) {
                    if pts.count > 1 { Btn(t("Show all", "Onyesha yote"), icon: "fit", light: true, wide: true) { chosen = false; ctl.fit(true) } }
                    Btn(t("Bigger map", "Ramani kubwa"), icon: "fit", light: true, wide: true) { full = true }
                }.padding(12)
                if withCard {
                    TagFlow(h: 14, v: 8) {
                        ForEach(["working", "repairing", "notworking", "building", "planned", "done"].filter { k in pts.contains { $0.state == k } }, id: \.self) { k in
                            HStack(spacing: 7) {
                                Circle().strokeBorder(donorStateColor(k), style: StrokeStyle(lineWidth: 4, dash: k == "planned" ? [4, 3] : [])).background(Circle().fill(Color.white)).frame(width: 18, height: 18)
                                Text(k == "done" ? t("Completed, not checked yet", "Imekamilika, haijakaguliwa bado") : donorStateName(k)).font(.mont(14, .semibold)).foregroundColor(C.text)
                            }
                        }
                    }.padding(.horizontal, 14).padding(.bottom, 14).frame(maxWidth: .infinity, alignment: .center)
                    Rectangle().fill(C.line).frame(height: 1)
                    let i = pts.firstIndex { $0.id == id } ?? 0
                    DonorMapCard(p: pts[i], i: i, n: pts.count, prev: { dir = -1; pick(pts[(i - 1 + pts.count) % pts.count].id) }, next: { dir = 1; pick(pts[(i + 1) % pts.count].id) }, open: open)
                        .id(pts[i].id)
                        .transition(Motion.on ? .asymmetric(insertion: .move(edge: dir > 0 ? .trailing : .leading).combined(with: .opacity), removal: .opacity) : .identity)
                }
            }
            .background(C.card).clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).stroke(C.line, lineWidth: 1))
            .accessibilityIdentifier("dmap")
            .fullScreenCover(isPresented: $full) {
                ZStack(alignment: .bottom) {
                    DonorMapBox(pts: pts, sat: $sat, cur: id, chosen: chosen, ctl: ctlFull, first: false, onPick: { id in if withCard { pick(id); if let pp = pts.first(where: { $0.id == id }) { ctlFull.fly(pp) } } }).ignoresSafeArea()
                    Btn(t("Close map", "Funga ramani"), icon: "x") { full = false }.padding(.bottom, 28)
                }
            }
        }
    }
    func pick(_ id: String) {
        DonorSpeaker.shared.stop()
        withAnimation(Motion.on ? .easeOut(duration: 0.35) : nil) { cur = id; chosen = true }
        if let p = projects.first(where: { $0.id == id }) { ctl.fly(p) }
    }
}

private struct DonorMapCard: View {
    @ObservedObject var d = DonorState.shared
    let p: DProject; let i: Int; let n: Int; let prev: () -> Void; let next: () -> Void; let open: (String) -> Void
    var body: some View {
        let say = donorStateSay(p) + (donorPeopleSay(p).isEmpty ? "" : " " + donorPeopleSay(p))
        let col = donorStateColor(p.state)
        VStack(spacing: 14) {
            Capsule().fill(C.line).frame(width: 40, height: 5)
            NetImage(url: p.photos.first.map { d.board($0, w: 480) } ?? p.gallery.first.map { d.photo($0.id, w: 480) }) { Icon(donorIcon(p), size: 40, color: C.blue) }
                .scaledToFill().frame(width: 104, height: 104).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                .shadow(color: Color(hex: 0x04263F).opacity(0.16), radius: 10, y: 6)
            VStack(spacing: 3) {
                HStack(spacing: 8) { BreathDot(color: col, live: p.state == "working" || p.state == "repairing", size: 10); Text(donorStateName(p.state)).font(.mont(14, .bold)).foregroundColor(col).lineLimit(1) }
                    .padding(.horizontal, 12).padding(.vertical, 6).background(col.opacity(0.12)).clipShape(Capsule())
                Text(p.title).font(.mont(22, .heavy)).foregroundColor(C.ink).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true).padding(.top, 8)
                Text(p.place.nb).font(.mont(14, .semibold)).foregroundColor(C.muted).multilineTextAlignment(.center)
            }
            Text(say).font(.mont(18)).foregroundColor(C.text).multilineTextAlignment(.center).lineSpacing(3).fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 10) {
                Btn(t("See this project", "Ona mradi huu"), icon: "chev", wide: true) { open(p.id) }.accessibilityIdentifier("dmap_open")
                SpeakBtn(key: "map_" + p.id, text: p.title + ". " + donorStateName(p.state) + ". " + say, label: t("Listen", "Sikiliza"), wide: false)
            }
            if n > 1 {
                HStack(spacing: 0) {
                    pagerBtn("arrow.left", t("Previous project", "Mradi uliotangulia"), prev).accessibilityIdentifier("dmap_prev")
                    VStack(spacing: 6) {
                        Text(t("Project \(i + 1) of \(n)", "Mradi \(i + 1) kati ya \(n)").nb).font(.mont(14, .bold)).foregroundColor(C.ink).lineLimit(1)
                        HStack(spacing: 5) {
                            ForEach(0..<min(n, 8), id: \.self) { k in Capsule().fill(k == min(i, 7) ? C.blue : C.edge).frame(width: k == min(i, 7) ? 22 : 7, height: 7) }
                        }
                    }.frame(maxWidth: .infinity)
                    pagerBtn("arrow.right", t("Next project", "Mradi unaofuata"), next).accessibilityIdentifier("dmap_next")
                }.padding(6).background(C.dark ? Color.white.opacity(0.08) : Color(hex: 0xF3F5F7)).clipShape(Capsule())
            }
        }.padding(.horizontal, 18).padding(.top, 12).padding(.bottom, 18).frame(maxWidth: .infinity).accessibilityIdentifier("dmap_card")
    }
    func pagerBtn(_ sf: String, _ label: String, _ go: @escaping () -> Void) -> some View {
        Button { Haptic.tap(); go() } label: {
            Image(systemName: sf).font(.system(size: 18, weight: .semibold)).foregroundColor(C.ink).frame(width: 50, height: 50).background(C.card).clipShape(Circle())
                .overlay(Circle().stroke(C.line, lineWidth: 1.5))
        }.buttonStyle(Press()).accessibilityLabel(label)
    }
}

// ---- photos: full screen, big arrows, swipe, slideshow ---------------------------------------------------------
struct ViewerPhotos: Identifiable { let id = UUID(); let list: [(String, String)]; let at: Int; let play: Bool }

/// The photos one at a time on black, with big ◀ ▶, a Slideshow button (a new photo every 4½ seconds) and a big Close.
struct PhotoViewer: View {
    let v: ViewerPhotos
    @Environment(\.dismiss) private var dismiss
    @State private var i = 0
    @State private var playing = false
    private let tick = Timer.publish(every: 4.5, on: .main, in: .common).autoconnect()
    var body: some View {
        let list = v.list
        VStack(spacing: 8) {
            HStack {
                Text(list.count > 1 ? t("Photo \(i + 1) of \(list.count)", "Picha \(i + 1) kati ya \(list.count)") : "").font(.tMedium).foregroundColor(.white)
                Spacer()
                Btn(t("Close", "Funga"), icon: "x", light: true) { dismiss() }
            }
            TabView(selection: $i) {
                ForEach(Array(list.enumerated()), id: \.offset) { k, x in
                    NetImage(url: x.0) { ProgressView().tint(.white) }.scaledToFit().clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous)).tag(k)
                }
            }.tabViewStyle(.page(indexDisplayMode: .never)).frame(maxHeight: .infinity)
            .onChange(of: i) { _ in Haptic.tap() }
            Text(list[min(i, list.count - 1)].1).font(.mont(16, .semibold)).foregroundColor(Color(hex: 0xE6EEF4)).multilineTextAlignment(.center).frame(minHeight: 22)
            if list.count > 1 {
                HStack(spacing: 14) {
                    nav("back", t("Previous photo", "Picha iliyotangulia")) { playing = false; withAnimation { i = (i - 1 + list.count) % list.count } }
                    Btn(playing ? t("Pause", "Simamisha") : t("Slideshow", "Onyesho la picha"), icon: playing ? "pause" : "play") { playing.toggle() }
                    nav("chev", t("Next photo", "Picha inayofuata")) { playing = false; withAnimation { i = (i + 1) % list.count } }
                }.padding(.bottom, 8)
            }
        }
        .padding(12).background(Color(hex: 0x0B0F13).ignoresSafeArea())
        .onAppear { i = min(max(0, v.at), list.count - 1); playing = v.play && list.count > 1 }
        .onReceive(tick) { _ in if playing && list.count > 1 { withAnimation(.easeInOut(duration: 0.6)) { i = (i + 1) % list.count } } }
        .accessibilityIdentifier("viewer")
    }
    func nav(_ icon: String, _ label: String, _ go: @escaping () -> Void) -> some View {
        Button(action: go) { Icon(icon, size: 32, color: .white).frame(width: 70, height: 70).background(Color.white.opacity(0.12)).clipShape(Circle()).overlay(Circle().stroke(Color.white.opacity(0.5), lineWidth: 2)) }
            .buttonStyle(Press()).accessibilityLabel(label)
    }
}

// ---- updates told as short stories ----------------------------------------------------------------------------------
/// What an update says, in plain sentences (and read aloud by Listen).
func donorStory(_ u: DUpdate) -> String {
    switch u.type {
    case "visit":
        let v = u.visit
        let head = v?.build == true ? t("Our team visited the building work.", "Timu yetu ilitembelea ujenzi.")
            : v?.state == "working" ? t("Our team visited and found it working well.", "Timu yetu ilitembelea na ikakuta inafanya kazi vizuri.")
            : v?.state == "repair" ? t("Our team visited and found something that needs a repair. They are arranging it.", "Timu yetu ilitembelea na ikakuta kitu kinachohitaji matengenezo. Wanalipanga.")
            : v?.state == "notworking" ? t("Our team visited and found it wasn’t working. They are arranging the repair.", "Timu yetu ilitembelea na ikakuta haifanyi kazi. Wanapanga matengenezo.")
            : t("Our team visited and checked it.", "Timu yetu ilitembelea na kukagua.")
        let say = v.map(donorVisitSay) ?? ""
        return say.isEmpty ? head : head + " " + say
    case "photos": return u.n == 1 ? t("Our team took a new photo. Tap it to see it big.", "Timu yetu ilipiga picha mpya. Iguse uione kubwa.") : t("Our team took \(u.n) new photos. Tap one to see it big.", "Timu yetu ilipiga picha \(u.n) mpya. Gusa moja uione kubwa.")
    case "thanks": return (u.media == "video" ? t("The community recorded a thank-you video for you.", "Jamii ilirekodi video ya shukrani kwa ajili yako.") : t("The community sent you a thank-you photo.", "Jamii ilikutumia picha ya shukrani.")) + (u.text.isEmpty ? "" : " “\(u.text)”")
    case "repair":
        let pr = u.problems.joined(separator: ", ").lowercased()
        return u.fixed ? t("It was repaired and is working again." + (pr.isEmpty ? "" : " What was fixed: \(pr)."), "Imerekebishwa na inafanya kazi tena." + (pr.isEmpty ? "" : " Kilichorekebishwa: \(pr)."))
            : t("Our team found a problem and is fixing it." + (pr.isEmpty ? "" : " What broke: \(pr)."), "Timu yetu iligundua tatizo na inalirekebisha." + (pr.isEmpty ? "" : " Kilichoharibika: \(pr)."))
    case "story": return "“" + u.text + "”" + (u.who.isEmpty ? "" : " — " + u.who)
    case "anniversary": return t("Thank you for every litre since.", "Asante kwa kila lita tangu hapo.")
    default: return ""
    }
}

// ---- motion graphics, the same as the web donor page --------------------------------------------------------------
// Water waves, rising bubbles, ripples, a light sweeping over a card, photos drifting slowly, and status marks that
// move (a tick that draws itself, a wrench at work, bricks stacking, a clock turning). All still when the phone asks
// for less motion.

extension String {
    /// Keeps a date, an amount or a phone number on one line.
    var nb: String { replacingOccurrences(of: " ", with: "\u{00A0}") }
}

/// Where we are in a loop of [period] seconds, from 0 to 1.
func loopT(_ d: Date, _ period: Double, _ offset: Double = 0) -> Double {
    let x = (d.timeIntervalSinceReferenceDate + offset) / period
    return x - floor(x)
}

private func wiggle(_ v: Double) -> Double {
    if v < 0.15 { return -28 * v / 0.15 }
    if v < 0.30 { return -28 + 46 * (v - 0.15) / 0.15 }
    if v < 0.45 { return 18 - 28 * (v - 0.30) / 0.15 }
    if v < 0.60 { return -10 + 10 * (v - 0.45) / 0.15 }
    return 0
}

/// Two soft waves rolling sideways, one each way.
struct Waves: View {
    let back: Color; let front: Color
    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: !Motion.on)) { tl in
            Canvas { ctx, size in
                let d = tl.date
                Waves.wave(&ctx, size, Motion.on ? 1 - loopT(d, 14) : 0.3, size.height * 0.25, size.height * 0.12, back)
                Waves.wave(&ctx, size, Motion.on ? loopT(d, 9) : 0, size.height * 0.45, size.height * 0.10, front)
            }
        }
        .allowsHitTesting(false).accessibilityHidden(true)
    }
    static func wave(_ ctx: inout GraphicsContext, _ size: CGSize, _ phase: Double, _ top: CGFloat, _ amp: CGFloat, _ color: Color) {
        let w = size.width, h = size.height, wl = w / 2
        var p = Path()
        var x = -CGFloat(phase) * wl - wl
        p.move(to: CGPoint(x: x, y: h)); p.addLine(to: CGPoint(x: x, y: top))
        while x < w + wl {
            p.addQuadCurve(to: CGPoint(x: x + wl / 2, y: top), control: CGPoint(x: x + wl / 4, y: top - amp))
            p.addQuadCurve(to: CGPoint(x: x + wl, y: top), control: CGPoint(x: x + wl * 3 / 4, y: top + amp))
            x += wl
        }
        p.addLine(to: CGPoint(x: x, y: h)); p.closeSubpath()
        ctx.fill(p, with: .color(color))
    }
}

/// Little bubbles rising and fading.
struct Bubbles: View {
    var n = 7; var color = Color.white.opacity(0.35)
    var body: some View {
        if Motion.on {
            TimelineView(.animation(minimumInterval: 1.0 / 30)) { tl in
                Canvas { ctx, size in
                    let t = loopT(tl.date, 9)
                    for i in 0..<n {
                        var p = t * (1 + Double(i % 2)) + Double(i) * 0.137
                        p -= floor(p)
                        let x = size.width * (0.08 + CGFloat((i * 37) % 84) / 100) + CGFloat(p) * 10
                        let y = size.height * (1.05 - CGFloat(p) * 1.1)
                        let r = CGFloat(2 + (i * 3) % 4)
                        let fade = p < 0.15 ? p / 0.15 : 1 - (p - 0.15) / 0.85
                        ctx.fill(Path(ellipseIn: CGRect(x: x - r, y: y - r, width: r * 2, height: r * 2)), with: .color(color.opacity(fade)))
                    }
                }
            }
            .allowsHitTesting(false).accessibilityHidden(true)
        }
    }
}

/// Two rings spreading out, like a drop falling into water.
struct Ripples: View {
    var color = Color.white.opacity(0.35)
    var body: some View {
        if Motion.on {
            TimelineView(.animation(minimumInterval: 1.0 / 30)) { tl in
                Canvas { ctx, size in
                    let t = loopT(tl.date, 3.2)
                    for k in 0..<2 {
                        var p = t + Double(k) * 0.5
                        p -= floor(p)
                        let r = min(size.width, size.height) / 2 * CGFloat(0.5 + 1.7 * p)
                        let c = CGPoint(x: size.width / 2, y: size.height / 2)
                        ctx.stroke(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)), with: .color(color.opacity(1 - p)), lineWidth: 2)
                    }
                }
            }
            .allowsHitTesting(false).accessibilityHidden(true)
        }
    }
}

/// Floats gently up and down.
struct Bob: ViewModifier {
    var px: CGFloat = 8; var period = 4.0
    @ViewBuilder func body(content: Content) -> some View {
        if Motion.on {
            TimelineView(.animation(minimumInterval: 1.0 / 30)) { tl in
                content.offset(y: -px * CGFloat((1 - cos(loopT(tl.date, period) * 2 * .pi)) / 2))
            }
        } else { content }
    }
}

/// A photo drifting very slowly closer, then back.
struct Drift: ViewModifier {
    @State private var on = false
    func body(content: Content) -> some View {
        content.scaleEffect(on ? 1.09 : 1, anchor: UnitPoint(x: 0.6, y: 0.4))
            .onAppear { guard Motion.on, !on else { return }; withAnimation(.easeInOut(duration: 16).repeatForever(autoreverses: true)) { on = true } }
    }
}

/// One streak of light across the card when it first appears.
struct Sheen: ViewModifier {
    var delay = 0.5
    @State private var x: CGFloat = -1.2
    func body(content: Content) -> some View {
        content
            .overlay(GeometryReader { g in
                LinearGradient(colors: [Color.white.opacity(0), Color.white.opacity(0.36), Color.white.opacity(0)], startPoint: .leading, endPoint: .trailing)
                    .frame(width: g.size.width * 0.5, height: g.size.height * 1.6)
                    .rotationEffect(.degrees(15))
                    .offset(x: g.size.width * x, y: -g.size.height * 0.3)
            }.allowsHitTesting(false).clipped())
            .onAppear { guard Motion.on, x < 0 else { return }; withAnimation(.easeInOut(duration: 1.4).delay(delay)) { x = 1.4 } }
    }
}

/// A small push to the right every few seconds: "tap here".
struct Nudge: ViewModifier {
    @ViewBuilder func body(content: Content) -> some View {
        if Motion.on {
            TimelineView(.animation(minimumInterval: 1.0 / 30)) { tl in
                let v = loopT(tl.date, 3)
                let k: Double = v < 0.7 ? 0 : v < 0.8 ? (v - 0.7) / 0.1 : v < 0.9 ? 1 - (v - 0.8) / 0.1 : 0
                content.offset(x: CGFloat(k) * 4)
            }
        } else { content }
    }
}

/// A gentle heartbeat.
struct Beat: ViewModifier {
    @ViewBuilder func body(content: Content) -> some View {
        if Motion.on {
            TimelineView(.animation(minimumInterval: 1.0 / 30)) { tl in
                let v = loopT(tl.date, 1.6)
                let s: Double = v < 0.2 ? 1 + 0.18 * v / 0.2 : v < 0.3 ? 1.18 - 0.22 * (v - 0.2) / 0.1 : v < 0.4 ? 0.96 + 0.04 * (v - 0.3) / 0.1 : 1
                content.scaleEffect(CGFloat(s))
            }
        } else { content }
    }
}

/// A spanner at work: turns back and forth, then rests.
struct AtWork: ViewModifier {
    var period = 1.8
    @ViewBuilder func body(content: Content) -> some View {
        if Motion.on {
            TimelineView(.animation(minimumInterval: 1.0 / 30)) { tl in content.rotationEffect(.degrees(wiggle(loopT(tl.date, period)))) }
        } else { content }
    }
}

/// A camera flash now and then.
struct Flash: ViewModifier {
    @ViewBuilder func body(content: Content) -> some View {
        if Motion.on {
            TimelineView(.animation(minimumInterval: 1.0 / 30)) { tl in
                let v = loopT(tl.date, 4)
                let a: Double = v < 0.92 ? 0 : v < 0.95 ? (v - 0.92) / 0.03 : 1 - (v - 0.95) / 0.05
                content.overlay(Circle().fill(Color.white.opacity(0.75 * a)).scaleEffect(1.4).allowsHitTesting(false))
            }
        } else { content }
    }
}

private struct CheckShape: Shape {
    func path(in r: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: r.width * 0.3, y: r.height * 0.53))
        p.addLine(to: CGPoint(x: r.width * 0.45, y: r.height * 0.67))
        p.addLine(to: CGPoint(x: r.width * 0.72, y: r.height * 0.38))
        return p
    }
}

/// The project's state as a small round mark that moves: the tick draws itself and a ring pulses out (working), the
/// wrench turns (being repaired), the "!" blinks (waiting for a repair), bricks stack up (being built), the clock's
/// hands turn (planned).
struct StateMark: View {
    let k: String; let color: Color; var size: CGFloat = 26
    @State private var drawn: CGFloat = Motion.on ? 0 : 1
    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: !Motion.on)) { tl in
            let period: Double = k == "repairing" ? 1.8 : k == "notworking" ? 1.6 : k == "building" ? 3.2 : k == "planned" ? 4 : 2.6
            let loop = Motion.on ? loopT(tl.date, period) : 0.5
            ZStack {
                if k == "working" && Motion.on {
                    Circle().stroke(color.opacity(0.6 * (1 - loop)), lineWidth: 2).frame(width: size, height: size).scaleEffect(CGFloat(1 + 0.6 * loop))
                }
                Circle().fill(color).frame(width: size, height: size)
                mark(loop)
            }
        }
        .frame(width: size, height: size)
        .onAppear { if Motion.on && drawn < 1 { withAnimation(.easeOut(duration: 0.7).delay(0.9)) { drawn = 1 } } }
        .accessibilityHidden(true)
    }
    @ViewBuilder func mark(_ loop: Double) -> some View {
        switch k {
        case "repairing":
            Icon("wrench", size: size * 0.62, color: .white).rotationEffect(.degrees(wiggle(loop)))
        case "notworking":
            VStack(spacing: size * 0.08) {
                Capsule().fill(Color.white).frame(width: size * 0.11, height: size * 0.32)
                Circle().fill(Color.white).frame(width: size * 0.12, height: size * 0.12)
            }.opacity(loop < 0.5 ? 1 : 0.35)
        case "building":
            Canvas { ctx, sz in
                let s = min(sz.width, sz.height), f = s * 0.6 / 24, o = s * 0.2
                let bricks: [(CGFloat, CGFloat)] = [(3, 15), (13, 15), (8, 9), (8, 3)]
                for (j, b) in bricks.enumerated() {
                    let p = min(1, max(0, loop - Double(j) * 0.06))
                    let dy: CGFloat = p < 0.18 ? -CGFloat(1 - p / 0.18) * s * 0.3 : 0
                    let al: Double = p < 0.18 ? p / 0.18 : p < 0.85 ? 1 : 1 - (p - 0.85) / 0.15
                    ctx.fill(Path(roundedRect: CGRect(x: o + b.0 * f, y: o + b.1 * f + dy, width: 8 * f, height: 5 * f), cornerRadius: f), with: .color(Color.white.opacity(al)))
                }
            }.frame(width: size, height: size)
        case "planned":
            Canvas { ctx, sz in
                let s = min(sz.width, sz.height), c = CGPoint(x: sz.width / 2, y: sz.height / 2), lw = s * 0.09
                ctx.stroke(Path(ellipseIn: CGRect(x: c.x - s * 0.3, y: c.y - s * 0.3, width: s * 0.6, height: s * 0.6)), with: .color(.white), lineWidth: lw)
                let m = (loop * 360 - 90) * .pi / 180, h = (loop * 30 - 90) * .pi / 180
                var hands = Path()
                hands.move(to: c); hands.addLine(to: CGPoint(x: c.x + CGFloat(cos(m)) * s * 0.2, y: c.y + CGFloat(sin(m)) * s * 0.2))
                hands.move(to: c); hands.addLine(to: CGPoint(x: c.x + CGFloat(cos(h)) * s * 0.13, y: c.y + CGFloat(sin(h)) * s * 0.13))
                ctx.stroke(hands, with: .color(.white), style: StrokeStyle(lineWidth: lw, lineCap: .round))
            }.frame(width: size, height: size)
        default:
            CheckShape().trim(from: 0, to: drawn).stroke(Color.white, style: StrokeStyle(lineWidth: size * 0.09, lineCap: .round, lineJoin: .round)).frame(width: size, height: size)
        }
    }
}

/// A coloured dot that breathes out a soft ring (for "Working well" and "Being repaired").
struct BreathDot: View {
    let color: Color; let live: Bool; var size: CGFloat = 8
    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: !(Motion.on && live))) { tl in
            let v = Motion.on && live ? loopT(tl.date, 2.4) : 1
            ZStack {
                if v < 0.8 {
                    Circle().fill(color.opacity(0.55 * (1 - v / 0.8))).frame(width: size + 14 * CGFloat(v / 0.8), height: size + 14 * CGFloat(v / 0.8))
                }
                Circle().fill(color).frame(width: size, height: size)
            }
        }
        .frame(width: size, height: size)
    }
}

/// A number that counts up from zero when it first shows, with thousands commas.
struct DonorCount: View {
    let value: Int; var suffix = ""; var count = true; var font: Font = .mont(22, .heavy); var color: Color = C.ink
    @State private var shown = 0
    var body: some View {
        Text((count ? donorNum(shown) : "\(value)") + suffix).font(font).foregroundColor(color).lineLimit(1).minimumScaleFactor(0.6)
            .task(id: value) {
                guard count, Motion.on, value > 0 else { shown = value; return }
                try? await Task.sleep(nanoseconds: 300_000_000)
                for i in 1...30 {
                    let k = Double(i) / 30
                    shown = Int((Double(value) * (1 - pow(1 - k, 3))).rounded())
                    try? await Task.sleep(nanoseconds: 36_000_000)
                }
            }
    }
}

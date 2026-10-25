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
                Icon(icon, size: 24, color: wa ? .white : col)
                VStack(alignment: .leading, spacing: 1) { Text(title).font(.tMedium).foregroundColor(wa ? .white : C.ink); Text(sub).font(.mont(14, .semibold)).foregroundColor(wa ? .white.opacity(0.9) : C.muted) }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 18).padding(.vertical, 10).frame(maxWidth: .infinity, minHeight: 64).background(wa ? col : C.card)
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(wa ? col : C.line, lineWidth: 1))
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
        if cs.count == 1 { mv.setRegion(MKCoordinateRegion(center: cs[0], latitudinalMeters: 3000, longitudinalMeters: 3000), animated: anim); return }
        var rect = MKMapRect.null
        for c in cs { let pnt = MKMapPoint(c); rect = rect.union(MKMapRect(x: pnt.x, y: pnt.y, width: 0, height: 0)) }
        mv.setVisibleMapRect(rect, edgePadding: UIEdgeInsets(top: 80, left: 70, bottom: 70, right: 80), animated: anim)
    }
    func fly(_ p: DProject) {
        guard let mv, let g = p.gps else { return }
        let c = CLLocationCoordinate2D(latitude: g.lat, longitude: g.lng)
        let span = min(mv.region.span.latitudeDelta, 0.03)
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
    @StateObject private var ctl = DonorMapCtl()
    @StateObject private var ctlFull = DonorMapCtl()
    var body: some View {
        let pts = projects.filter { $0.gps != nil }
        if !pts.isEmpty {
            let id = pts.contains(where: { $0.id == cur }) ? cur : pts[0].id
            VStack(spacing: 0) {
                DonorMapBox(pts: pts, sat: $sat, cur: id, chosen: chosen, ctl: ctl, first: withCard, onPick: { pick($0) })
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
                    }.padding(.horizontal, 14).padding(.bottom, 14).frame(maxWidth: .infinity, alignment: .leading)
                    Rectangle().fill(C.line).frame(height: 1)
                    let i = pts.firstIndex { $0.id == id } ?? 0
                    DonorMapCard(p: pts[i], i: i, n: pts.count, prev: { pick(pts[(i - 1 + pts.count) % pts.count].id) }, next: { pick(pts[(i + 1) % pts.count].id) }, open: open)
                        .id(pts[i].id).transition(.opacity)
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
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 14) {
                NetImage(url: p.photos.first.map { d.board($0, w: 480) } ?? p.gallery.first.map { d.photo($0.id, w: 480) }) { Icon(donorIcon(p), size: 36, color: C.blue) }
                    .scaledToFill().frame(width: 84, height: 84).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(p.title).font(.tLarge).foregroundColor(C.ink)
                    Text(p.place).font(.mont(14, .semibold)).foregroundColor(C.muted)
                }
                Spacer(minLength: 0)
            }
            HStack(spacing: 8) { Circle().fill(col).frame(width: 10, height: 10); Text(donorStateName(p.state)).font(.lLarge).foregroundColor(col) }
                .padding(.horizontal, 12).padding(.vertical, 6).background(col.opacity(0.12)).clipShape(Capsule())
            Text(say).font(.mont(18)).foregroundColor(C.text).fixedSize(horizontal: false, vertical: true)
            if n > 1 {
                HStack(spacing: 10) {
                    Btn(t("Previous", "Iliyotangulia"), icon: "back", light: true, wide: true, action: prev)
                    Btn(t("Next", "Inayofuata"), icon: "chev", light: true, wide: true, action: next)
                }
            }
            Btn(t("See this project", "Ona mradi huu"), icon: "chev", wide: true) { open(p.id) }
            SpeakBtn(key: "map_" + p.id, text: p.title + ". " + donorStateName(p.state) + ". " + say)
            if n > 1 { Text(t("Project \(i + 1) of \(n)", "Mradi \(i + 1) kati ya \(n)")).font(.mont(14, .semibold)).foregroundColor(C.muted).frame(maxWidth: .infinity) }
        }.padding(16).accessibilityIdentifier("dmap_card")
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

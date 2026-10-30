import AVFoundation
import CoreImage
import CoreImage.CIFilterBuiltins
import PhotosUI
import SwiftUI
import UIKit

// QR check-in (Android QrCheckin.kt, 2.16): a printable sticker for each project with a QR code of
// https://pin.rafikioutreach.org/pin#p=<id>. Scanning it (Projects › Scan QR) opens the project's Site visit, and the
// visit report is sent with qr: true so monday.com notes "Checked in by scanning the site's QR code".

enum FieldQrCheckin {
    static let BASE = "https://pin.rafikioutreach.org/pin"
    static func url(_ id: String) -> String { BASE + "#p=" + id }
    /// The project id in a scanned code: our address (#p=123 or #p/123), or just the number.
    static func parse(_ text: String?) -> String? {
        let s = (text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if s.isEmpty { return nil }
        if let r = s.range(of: #"#p[=/](\d{1,15})"#, options: .regularExpression) { return String(s[r].dropFirst(3)) }
        if s.range(of: #"^\d{1,15}$"#, options: .regularExpression) != nil { return s }
        return nil
    }
    private static var marks: [String: Date] = [:]
    /// Scanned at the site: this project's next visit report says so (for the next 12 hours).
    static func mark(_ id: String) { marks[id] = Date() }
    static func isFor(_ id: String) -> Bool { marks[id].map { Date().timeIntervalSince($0) < 12 * 3600 } ?? false }
    static func clear(_ id: String) { marks[id] = nil }

    /// The QR code as a sharp black-on-white picture.
    static func image(_ text: String, scale: CGFloat = 8) -> UIImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(text.utf8); f.correctionLevel = "M"
        guard let out = f.outputImage?.transformed(by: CGAffineTransform(scaleX: scale, y: scale)), let cg = CIContext().createCGImage(out, from: out.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
    /// The text in a QR code in a photo, or nil.
    static func decode(_ img: UIImage) -> String? {
        guard let ci = CIImage(image: img), let det = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh]) else { return nil }
        return det.features(in: ci).compactMap { ($0 as? CIQRCodeFeature)?.messageString }.first
    }

    /// The printable sheet: A4, 3 × 4 stickers a page, each with the RAFIKI logo, the code, the project name and id.
    static func pdf(_ list: [Project]) -> URL? {
        let W: CGFloat = 595, H: CGFloat = 842, mx: CGFloat = 28, top: CGFloat = 34, bottom: CGFloat = 40
        let cw = (W - 2 * mx) / 3, ch = (H - top - bottom) / 4
        let ink = UIColor(red: 0x04 / 255, green: 0x26 / 255, blue: 0x3F / 255, alpha: 1)
        let para = NSMutableParagraphStyle(); para.alignment = .center; para.lineBreakMode = .byWordWrapping
        let name: [NSAttributedString.Key: Any] = [.font: UIFont.boldSystemFont(ofSize: 10.5), .foregroundColor: ink, .paragraphStyle: para]
        let small: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 7.5), .foregroundColor: UIColor(red: 0x5A / 255, green: 0x60 / 255, blue: 0x66 / 255, alpha: 1), .paragraphStyle: para]
        let blue: [NSAttributedString.Key: Any] = [.font: UIFont.boldSystemFont(ofSize: 7.5), .foregroundColor: UIColor(red: 0x0A / 255, green: 0x78 / 255, blue: 0xC2 / 255, alpha: 1), .paragraphStyle: para]
        let pages = stride(from: 0, to: list.count, by: 12).map { Array(list[$0..<min($0 + 12, list.count)]) }
        let logo = UIImage(named: "LetterheadLogo")
        let data = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: W, height: H)).pdfData { ctx in
            for (pi, chunk) in pages.enumerated() {
                ctx.beginPage()
                let c = ctx.cgContext
                c.setStrokeColor(UIColor(red: 0xB8 / 255, green: 0xC4 / 255, blue: 0xCE / 255, alpha: 1).cgColor); c.setLineWidth(0.6); c.setLineDash(phase: 0, lengths: [4, 3])
                for i in 0...3 { c.move(to: CGPoint(x: mx + CGFloat(i) * cw, y: top)); c.addLine(to: CGPoint(x: mx + CGFloat(i) * cw, y: H - bottom)) }
                for j in 0...4 { c.move(to: CGPoint(x: mx, y: top + CGFloat(j) * ch)); c.addLine(to: CGPoint(x: W - mx, y: top + CGFloat(j) * ch)) }
                c.strokePath(); c.setLineDash(phase: 0, lengths: [])
                for (i, p) in chunk.enumerated() {
                    let x = mx + CGFloat(i % 3) * cw, y = top + CGFloat(i / 3) * ch
                    if let logo { let lw: CGFloat = 92, lh = lw * logo.size.height / max(1, logo.size.width); logo.draw(in: CGRect(x: x + (cw - lw) / 2, y: y + 8, width: lw, height: lh)) }
                    let q: CGFloat = 104
                    image(url(p.id), scale: 10)?.draw(in: CGRect(x: x + (cw - q) / 2, y: y + 44, width: q, height: q))
                    (p.title as NSString).draw(with: CGRect(x: x + 8, y: y + 44 + q + 6, width: cw - 16, height: 26), options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine], attributes: name, context: nil)
                    ("ID " + p.id + (p.country.isEmpty ? "" : " · " + p.country) as NSString).draw(with: CGRect(x: x + 6, y: y + ch - 26, width: cw - 12, height: 10), options: .usesLineFragmentOrigin, attributes: small, context: nil)
                    ("Scan to check in · Changanua uingie" as NSString).draw(with: CGRect(x: x + 6, y: y + ch - 15, width: cw - 12, height: 10), options: .usesLineFragmentOrigin, attributes: blue, context: nil)
                }
                ("RAFIKI Pin · QR check-in stickers · page \(pi + 1) of \(pages.count) · cut along the dotted lines" as NSString)
                    .draw(with: CGRect(x: mx, y: H - 24, width: W - 2 * mx, height: 12), options: .usesLineFragmentOrigin, attributes: small, context: nil)
            }
        }
        let u = FileManager.default.temporaryDirectory.appendingPathComponent("RAFIKI QR stickers.pdf")
        do { try data.write(to: u); return u } catch { return nil }
    }
}

/// "Checked in by scanning the site's QR code", at the top of the Site visit after a scan.
struct FieldQrCheckedIn: View {
    let id: String
    var body: some View {
        if FieldQrCheckin.isFor(id) {
            let hu = ohue("teal")
            HStack(spacing: 8) {
                OIcon("qr", size: 20, color: hu.fg)
                Text(t("Checked in by scanning the site’s QR code", "Umeingia kwa kuchanganua msimbo wa QR wa eneo")).font(.lMedium).foregroundColor(hu.fg).fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }.padding(.horizontal, 12).padding(.vertical, 10).background(hu.bg).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).padding(.top, 12)
        }
    }
}

// --- Office › QR codes -------------------------------------------------------------------------------------------

struct FieldQrSection: View {
    let s: Snapshot
    @State private var ck = ""
    @State private var picked: Set<String> = []
    @State private var busy = false
    @State private var msg: String?
    var body: some View {
        let counts: [String: Int] = Dictionary(grouping: s.projects, by: { $0.ck }).mapValues { $0.count }
        let all: [Project] = ck.isEmpty ? s.projects : s.projects.filter { $0.ck == ck }
        let list: [Project] = Array(all.prefix(200))
        let chosen: [Project] = s.projects.filter { picked.contains($0.id) }
        let pages: Int = (chosen.count + 11) / 12
        VStack(alignment: .leading, spacing: 12) {
            OHead(icon: "qr", hue: "teal", title: t("QR check-in stickers", "Vibandiko vya QR vya kuingia"), line: t("Print one for each project and fix it at the site.", "Chapisha kimoja kwa kila mradi na ukibandike eneo la mradi.")) {
                ONums(items: [ONum(value: "\(chosen.count)", label: t("chosen", "vimechaguliwa"), color: chosen.isEmpty ? C.muted : C.ink), ONum(value: "\(pages)", label: pages == 1 ? t("A4 page", "ukurasa A4") : t("A4 pages", "kurasa A4"), color: pages == 0 ? C.muted : C.ink)])
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    OChip(label: t("All countries", "Nchi zote"), on: ck.isEmpty, hue: "teal") { ck = "" }
                    ForEach(COUNTRIES.filter { (counts[$0.0] ?? 0) > 0 }, id: \.0) { k, l in OChip(label: l, on: ck == k, hue: "teal", badge: counts[k] ?? 0) { ck = k } }
                }
            }
            HStack(spacing: 8) {
                Text(t("\(chosen.count) of \(all.count) chosen", "\(chosen.count) kati ya \(all.count) zimechaguliwa")).font(.tSmall).foregroundColor(C.ink).frame(maxWidth: .infinity, alignment: .leading)
                OChip(label: t("All", "Zote"), on: false, hue: "teal") { all.forEach { picked.insert($0.id) } }
                OChip(label: t("None", "Hakuna"), on: false, hue: "teal") { all.forEach { picked.remove($0.id) } }
            }
            VStack(spacing: 0) {
                if list.isEmpty { Text(t("No projects in this country yet.", "Bado hakuna miradi katika nchi hii.")).font(.bMedium).foregroundColor(C.muted).padding(8) }
                ForEach(list) { p in QrPickRow(p: p, on: picked.contains(p.id)) { if picked.contains(p.id) { picked.remove(p.id) } else { picked.insert(p.id) } } }
            }.background(C.card).clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(C.line, lineWidth: 1))
            if all.count > 200 { Text(t("Showing the first 200. Choose a country to see the rest.", "Inaonyesha 200 za kwanza. Chagua nchi kuona zilizobaki.")).font(.bSmall).foregroundColor(C.muted) }
            if let p = chosen.first { QrStickerPreview(p: p) } else {
                Text(t("Choose at least one project to see a sticker.", "Chagua angalau mradi mmoja kuona kibandiko.")).font(.bMedium).foregroundColor(C.muted).frame(maxWidth: .infinity)
            }
            Text(t("\(chosen.count) " + (chosen.count == 1 ? "sticker" : "stickers") + " on \(pages) A4 " + (pages == 1 ? "page" : "pages"), "Vibandiko \(chosen.count) kwenye kurasa \(pages) za A4"))
                .font(.tSmall).foregroundColor(C.ink).frame(maxWidth: .infinity)
            Btn(t("Share the PDF to print", "Shiriki PDF ya kuchapisha"), icon: "share", enabled: !chosen.isEmpty, busy: busy ? t("Making the PDF…", "Inatengeneza PDF…") : nil) {
                busy = true; msg = nil
                DispatchQueue.main.async {
                    if let u = FieldQrCheckin.pdf(chosen) { Share.items([u]) } else { msg = t("Couldn’t make the PDF. Please try again.", "Imeshindwa kutengeneza PDF. Tafadhali jaribu tena.") }
                    busy = false
                }
            }
            Msg(msg, "bad")
            OFold(icon: "info", hue: "blue", title: t("How it works", "Jinsi inavyofanya kazi"), summary: t("Scan it to open the Site visit", "Kichanganue kufungua Ziara")) {
                VStack(alignment: .leading, spacing: 8) {
                    Text(t("Print one for each project and fix it at the site (pump house, sign or office door). Scanning it opens the project’s Site visit, and monday.com notes the visit was checked in by QR.",
                           "Chapisha kimoja kwa kila mradi na ukibandike eneo la mradi (nyumba ya pampu, bango au mlango wa ofisi). Kukichanganua kunafungua Ziara ya mradi, na monday.com inaandika kuwa uliingia kwa QR."))
                        .font(.bMedium).foregroundColor(C.text).fixedSize(horizontal: false, vertical: true)
                    Text(t("Share the PDF to a printer or a computer, print it on A4 at 100% and cut along the dotted lines.", "Shiriki PDF kwa printa au kompyuta, ichapishe kwenye A4 kwa ukubwa 100% na ukate kwenye mistari ya nukta."))
                        .font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }
}

private struct QrPickRow: View {
    let p: Project; let on: Bool; let toggle: () -> Void
    var body: some View {
        Button(action: toggle) {
            HStack(spacing: 12) {
                Image(systemName: on ? "checkmark.square.fill" : "square").font(.system(size: 22)).foregroundColor(on ? ohue("teal").fg : C.muted)
                VStack(alignment: .leading, spacing: 1) {
                    Text(p.title).font(.tSmall).foregroundColor(C.ink).multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
                    Text("ID \(p.id)" + (p.area.isEmpty ? "" : " · " + p.area)).font(.bSmall).foregroundColor(C.muted).lineLimit(1)
                }.frame(maxWidth: .infinity, alignment: .leading)
            }.padding(.horizontal, 12).padding(.vertical, 8).frame(minHeight: 52).contentShape(Rectangle())
        }.buttonStyle(.plain).accessibilityAddTraits(on ? .isSelected : [])
    }
}

/// How a sticker looks (always black on white, like the print).
private struct QrStickerPreview: View {
    let p: Project
    var body: some View {
        VStack(spacing: 4) {
            Text(t("How a sticker looks", "Jinsi kibandiko kinavyoonekana")).font(.tSmall).foregroundColor(C.ink).padding(.bottom, 6)
            VStack(spacing: 4) {
                if let logo = UIImage(named: "LetterheadLogo") { Image(uiImage: logo).resizable().scaledToFit().frame(width: 110) }
                if let q = FieldQrCheckin.image(FieldQrCheckin.url(p.id)) { Image(uiImage: q).interpolation(.none).resizable().frame(width: 132, height: 132).padding(.top, 6) }
                Text(p.title).font(.system(size: 13, weight: .bold)).foregroundColor(Color(hex: 0x04263F)).multilineTextAlignment(.center).lineLimit(2)
                Text("ID \(p.id)" + (p.country.isEmpty ? "" : " · " + p.country)).font(.system(size: 11)).foregroundColor(Color(hex: 0x5A6066))
                Text("Scan to check in · Changanua uingie").font(.system(size: 11, weight: .bold)).foregroundColor(Color(hex: 0x0A78C2)).multilineTextAlignment(.center)
            }.padding(14).frame(width: 220).background(Color.white).overlay(RoundedRectangle(cornerRadius: 4).stroke(Color(hex: 0xB8C4CE), style: StrokeStyle(lineWidth: 1, dash: [4, 3])))
        }.frame(maxWidth: .infinity).padding(.top, 6)
    }
}

// --- Scan QR ------------------------------------------------------------------------------------------------------

/// Scan QR: the camera, a photo of the sticker, or the project number typed in. Opens the project's Site visit.
@MainActor
struct FieldQrScanner: View {
    @EnvironmentObject var m: AppModel
    let close: () -> Void
    @State private var msg: (String, String)?
    @State private var typed = ""
    @State private var item: PhotosPickerItem?
    @State private var camOk = AVCaptureDevice.authorizationStatus(for: .video) == .authorized
    @State private var camAsked = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text(t("Scan QR", "Changanua QR")).font(.hMedium).foregroundColor(C.ink).frame(maxWidth: .infinity, alignment: .leading)
                    OCloseX(action: close)
                }
                Text(t("Scan the RAFIKI sticker at the site. The project’s Site visit opens, checked in.", "Changanua kibandiko cha RAFIKI kilicho eneo la mradi. Ziara ya mradi inafunguka, ukiwa umeingia."))
                    .font(.bMedium).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true).padding(.top, 4).padding(.bottom, 14)
                camera
                if let mm = msg { Msg(mm.1, mm.0).padding(.top, 10) }
                PhotosPicker(selection: $item, matching: .images) {
                    HStack(spacing: 8) { Icon("upload", size: 18, color: C.blue); Text(t("Choose a photo of the code", "Chagua picha ya msimbo")).font(.tSmall).foregroundColor(C.blue) }
                        .frame(maxWidth: .infinity, minHeight: 48).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                }.padding(.top, 12)
                VStack(alignment: .leading, spacing: 8) {
                    Text(t("No sticker, or it won’t scan?", "Hakuna kibandiko, au hakichanganuliki?")).font(.tSmall).foregroundColor(C.ink)
                    Field(t("Project number (on the sticker)", "Namba ya mradi (kwenye kibandiko)"), Binding(get: { typed }, set: { typed = $0.filter { $0.isNumber } }), placeholder: t("e.g. 1234567890", "mf. 1234567890"), number: true)
                    Btn(t("Open", "Fungua"), enabled: !typed.isEmpty, light: true) { handle(typed, byHand: true) }
                    Text(t("Typing the number opens the visit too, but it isn’t marked as checked in by QR.", "Kuandika namba kunafungua ziara pia, lakini haiandikwi kuwa umeingia kwa QR."))
                        .font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true)
                }.padding(16).background(C.card).clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(C.line, lineWidth: 1)).padding(.top, 16)
            }.padding(.horizontal, 20).padding(.top, 20).padding(.bottom, 30)
        }
        .background(C.bg.ignoresSafeArea())
        .onAppear {
            if !camOk && !camAsked && AVCaptureDevice.authorizationStatus(for: .video) == .notDetermined {
                camAsked = true
                AVCaptureDevice.requestAccess(for: .video) { ok in DispatchQueue.main.async { camOk = ok } }
            }
        }
        .onChange(of: item) { it in
            guard let it else { return }
            Task {
                let d = try? await it.loadTransferable(type: Data.self)
                item = nil
                guard let d, let img = UIImage(data: d), let txt = FieldQrCheckin.decode(img) else {
                    msg = ("bad", t("No QR code found in that photo. Hold the phone closer, keep the code flat and in good light, then try again.", "Hakuna msimbo wa QR kwenye picha hiyo. Shika simu karibu zaidi, msimbo uwe tambarare na kwenye mwanga mzuri, kisha jaribu tena.")); return
                }
                handle(txt)
            }
        }
    }

    @ViewBuilder var camera: some View {
        let hu = ohue("teal")
        if camOk && !m.demo {
            QrCameraView { text in handle(text) }
                .frame(height: 300).frame(maxWidth: .infinity).clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Color.white.opacity(0.9), lineWidth: 3).frame(width: 190, height: 190))
                .accessibilityLabel(t("Camera: point it at the QR code", "Kamera: ielekeze kwenye msimbo wa QR"))
        } else {
            VStack(spacing: 10) {
                ZStack { OIcon("scan", size: 96, color: hu.fg); OIcon("qr", size: 46, color: hu.fg) }
                if AVCaptureDevice.authorizationStatus(for: .video) == .denied {
                    Text(t("Allow the camera in Settings to scan.", "Ruhusu kamera kwenye Mipangilio ili uchanganue.")).font(.tSmall).foregroundColor(C.ink).multilineTextAlignment(.center)
                    Btn(t("Open Settings", "Fungua Mipangilio"), light: true, small: true) { if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) } }
                }
            }.frame(maxWidth: .infinity, minHeight: 220).background(hu.bg).clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        }
    }

    func handle(_ text: String?, byHand: Bool = false) {
        guard let text else { return }
        guard let id = FieldQrCheckin.parse(text) else { msg = ("bad", t("That QR code isn’t a RAFIKI project sticker.", "Msimbo huo wa QR si kibandiko cha mradi wa RAFIKI.")); return }
        guard m.snap?.projects.contains(where: { $0.id == id }) == true else {
            msg = ("warn", t("Project \(id) isn’t in your list. Pull down on Projects to refresh, then scan again.", "Mradi \(id) haupo kwenye orodha yako. Vuta chini kwenye Miradi kuonyesha upya, kisha changanua tena.")); return
        }
        if !byHand { FieldQrCheckin.mark(id) }
        Haptic.tap(); msg = nil
        let tab = m.here.tab
        close()
        m.go(Place(tab: tab, project: id, projectTab: "visit"))
    }
}

/// The live camera, reading QR codes; calls back once per code.
private struct QrCameraView: UIViewControllerRepresentable {
    let found: (String) -> Void
    func makeUIViewController(context: Context) -> QrCameraVC { let vc = QrCameraVC(); vc.found = found; return vc }
    func updateUIViewController(_ vc: QrCameraVC, context: Context) { vc.found = found }
}

final class QrCameraVC: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var found: ((String) -> Void)?
    private let session = AVCaptureSession()
    private var preview: AVCaptureVideoPreviewLayer?
    private var last = ""
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        guard let dev = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: dev), session.canAddInput(input) else { return }
        session.addInput(input)
        let out = AVCaptureMetadataOutput()
        guard session.canAddOutput(out) else { return }
        session.addOutput(out)
        out.setMetadataObjectsDelegate(self, queue: .main)
        out.metadataObjectTypes = [.qr]
        let pl = AVCaptureVideoPreviewLayer(session: session); pl.videoGravity = .resizeAspectFill; view.layer.addSublayer(pl); preview = pl
    }
    override func viewDidLayoutSubviews() { super.viewDidLayoutSubviews(); preview?.frame = view.bounds }
    override func viewWillAppear(_ animated: Bool) { super.viewWillAppear(animated); let s = session; DispatchQueue.global(qos: .userInitiated).async { if !s.isRunning { s.startRunning() } } }
    override func viewWillDisappear(_ animated: Bool) { super.viewWillDisappear(animated); let s = session; DispatchQueue.global(qos: .userInitiated).async { if s.isRunning { s.stopRunning() } } }
    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard let text = (metadataObjects.first as? AVMetadataMachineReadableCodeObject)?.stringValue, text != last else { return }
        last = text
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        found?(text)
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in self?.last = "" }
    }
}

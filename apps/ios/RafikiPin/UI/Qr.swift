import AVFoundation
import CoreImage.CIFilterBuiltins
import PhotosUI
import SwiftUI
import UIKit
import VisionKit

/*
 Check in by QR code (Android's QrCheckin.kt). Every project can have a printed sticker (Office › QR codes) holding
 https://pin.rafikioutreach.org/pin#p=<id>: Scan QR reads it, and so does any phone camera (the web app understands the
 same address). Scanning opens the project's Site visit and the visit report says "Checked in by scanning the site's QR
 code" on monday.com (the visit is sent with qr: true).
 */
enum QrCheckin {
    static let BASE = "https://pin.rafikioutreach.org/pin"
    static func url(_ id: String) -> String { "\(BASE)#p=\(id)" }
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
    static func isFor(_ id: String) -> Bool { (marks[id].map { Date().timeIntervalSince($0) } ?? .infinity) < 12 * 3600 }
    static func clear(_ id: String) { marks[id] = nil }
    static func at(_ id: String) -> Date? { marks[id] }
}

/// QR codes: made with Core Image, read from a photo with Core Image.
enum QrCode {
    static func image(_ text: String, scale: CGFloat = 8) -> UIImage? {
        let f = CIFilter.qrCodeGenerator(); f.message = Data(text.utf8); f.correctionLevel = "M"
        guard let out = f.outputImage?.transformed(by: CGAffineTransform(scaleX: scale, y: scale)), let cg = CIContext().createCGImage(out, from: out.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
    static func decode(_ img: UIImage) -> String? {
        guard let ci = CIImage(image: img) else { return nil }
        let d = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])
        return (d?.features(in: ci).compactMap { ($0 as? CIQRCodeFeature)?.messageString }.first)
    }
}

/// The printable sheet: A4, 3 × 4 stickers a page, each with the RAFIKI logo, the code, the project name and id.
enum QrStickers {
    static let COLS = 3, ROWS = 4
    static func pdf(_ list: [Project]) -> Data {
        let W: CGFloat = 595, H: CGFloat = 842, mx: CGFloat = 28, top: CGFloat = 34, bottom: CGFloat = 40
        let cw = (W - 2 * mx) / CGFloat(COLS), ch = (H - top - bottom) / CGFloat(ROWS)
        let pages = stride(from: 0, to: list.count, by: COLS * ROWS).map { Array(list[$0..<min($0 + COLS * ROWS, list.count)]) }
        let logo = UIImage(named: "LetterheadLogo")
        func center(_ s: String, _ a: [NSAttributedString.Key: Any], _ cx: CGFloat, _ y: CGFloat) { let sz = (s as NSString).size(withAttributes: a); (s as NSString).draw(at: CGPoint(x: cx - sz.width / 2, y: y), withAttributes: a) }
        return UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: W, height: H)).pdfData { ctx in
            for (pi, chunk) in pages.enumerated() {
                ctx.beginPage()
                let c = ctx.cgContext
                for (i, p) in chunk.enumerated() {
                    let x = mx + CGFloat(i % COLS) * cw, y = top + CGFloat(i / COLS) * ch, cx = x + cw / 2
                    c.saveGState(); c.setLineDash(phase: 0, lengths: [4, 3]); c.setStrokeColor(UIColor(red: 0.72, green: 0.77, blue: 0.81, alpha: 1).cgColor); c.setLineWidth(0.6)
                    c.addPath(UIBezierPath(roundedRect: CGRect(x: x + 4, y: y + 4, width: cw - 8, height: ch - 8), cornerRadius: 10).cgPath); c.strokePath(); c.restoreGState()
                    if let logo { let lw: CGFloat = 80, lh = lw * logo.size.height / logo.size.width; logo.draw(in: CGRect(x: cx - lw / 2, y: y + 10, width: lw, height: lh)) }
                    let q: CGFloat = 90, qy = y + 45
                    if let img = QrCode.image(QrCheckin.url(p.id), scale: 10) { c.interpolationQuality = .none; img.draw(in: CGRect(x: cx - q / 2, y: qy, width: q, height: q)) }
                    let para = NSMutableParagraphStyle(); para.alignment = .center; para.lineBreakMode = .byTruncatingTail
                    let nameA: [NSAttributedString.Key: Any] = [.font: UIFont.boldSystemFont(ofSize: 10.5), .foregroundColor: UIColor(red: 0.016, green: 0.149, blue: 0.247, alpha: 1), .paragraphStyle: para]
                    let nr = (p.title as NSString).boundingRect(with: CGSize(width: cw - 24, height: 28), options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine], attributes: nameA, context: nil)
                    (p.title as NSString).draw(with: CGRect(x: x + 12, y: qy + q + 5, width: cw - 24, height: min(28, nr.height)), options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine], attributes: nameA, context: nil)
                    let ty = qy + q + 5 + min(28, nr.height) + 2
                    center("ID " + p.id + (p.country.isEmpty ? "" : " · " + p.country), [.font: UIFont.systemFont(ofSize: 7.5), .foregroundColor: UIColor(red: 0.35, green: 0.38, blue: 0.4, alpha: 1)], cx, ty)
                    center("Scan to check in · Changanua uingie", [.font: UIFont.boldSystemFont(ofSize: 7.5), .foregroundColor: UIColor(red: 0.04, green: 0.47, blue: 0.76, alpha: 1)], cx, ty + 11)
                }
                center("RAFIKI Pin · QR check-in stickers · page \(pi + 1) of \(pages.count) · cut along the dotted lines", [.font: UIFont.systemFont(ofSize: 7), .foregroundColor: UIColor.gray], W / 2, H - 28)
            }
        }
    }
}

/// One sticker as it will print, for the preview.
struct QrStickerCard: View {
    let p: Project
    var body: some View {
        VStack(spacing: 0) {
            if let logo = UIImage(named: "LetterheadLogo") { Image(uiImage: logo).resizable().scaledToFit().frame(width: 110) }
            if let qr = QrCode.image(QrCheckin.url(p.id)) { Image(uiImage: qr).interpolation(.none).resizable().frame(width: 132, height: 132).padding(.top, 8).accessibilityLabel(t("QR code for \(p.title)", "Msimbo wa QR wa \(p.title)")) }
            Text(p.title).font(.system(size: 14, weight: .bold)).foregroundColor(Color(hex: 0x04263F)).multilineTextAlignment(.center).lineLimit(2).padding(.top, 8)
            Text("ID \(p.id)" + (p.country.isEmpty ? "" : " · \(p.country)")).font(.system(size: 11)).foregroundColor(Color(hex: 0x5A6066))
            Text("Scan to check in · Changanua uingie").font(.system(size: 11, weight: .bold)).foregroundColor(Color(hex: 0x0A78C2)).multilineTextAlignment(.center).padding(.top, 2)
        }.padding(14).background(Color.white).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Color(hex: 0xB8C4CE), lineWidth: 1))
    }
}

/// Office › QR codes: pick projects (by country, all or none), see a sticker, share the A4 sheet as a PDF to print.
struct QrStickerSection: View {
    @EnvironmentObject var m: AppModel
    let s: Snapshot
    @State private var ck = ""
    @State private var off: Set<String> = []
    @State private var busy = false
    @State private var msg: String?
    @State private var ready = false
    var body: some View {
        let list = s.projects.filter { ck.isEmpty || $0.ck == ck }.sorted { $0.title.lowercased() < $1.title.lowercased() }
        let chosen = list.filter { !off.contains($0.id) }
        let per = QrStickers.COLS * QrStickers.ROWS, pages = (chosen.count + per - 1) / per
        let counts = Dictionary(grouping: s.projects, by: \.ck).mapValues(\.count)
        let hu = C.named("teal")
        VStack(alignment: .leading, spacing: 0) {
            DqmHead(icon: "qr", hue: "teal", title: t("QR check-in stickers", "Vibandiko vya QR vya kuingia"), line: t("Print one for each project and fix it at the site.", "Chapisha kimoja kwa kila mradi na ukibandike eneo la mradi."),
                    nums: [DqmNum(n: chosen.count, label: t("chosen", "vimechaguliwa"), tone: chosen.isEmpty ? "off" : ""), DqmNum(n: pages, label: pages == 1 ? t("A4 page", "ukurasa A4") : t("A4 pages", "kurasa A4"), tone: pages == 0 ? "off" : "")])
            DqmChips(items: [("", t("All countries", "Nchi zote"), s.projects.count)] + COUNTRIES.filter { (counts[$0.0] ?? 0) > 0 || $0.0 == ck }.map { ($0.0, $0.1, counts[$0.0] ?? 0) }, picked: ck) { ck = $0 }
            CardV(pad: 12) {
                HStack(spacing: 6) {
                    Text(t("\(chosen.count) of \(list.count) chosen", "\(chosen.count) kati ya \(list.count) zimechaguliwa")).font(.tSmall).foregroundColor(C.ink).padding(.leading, 4)
                    Spacer()
                    FilterPill(label: t("All", "Zote"), on: false) { list.forEach { off.remove($0.id) } }
                    FilterPill(label: t("None", "Hakuna"), on: false) { list.forEach { off.insert($0.id) } }
                }
                Rectangle().fill(C.line).frame(height: 1).padding(.top, 10)
                if list.isEmpty { Text(t("No projects in this country yet.", "Bado hakuna miradi katika nchi hii.")).font(.bMedium).foregroundColor(C.muted).padding(8) }
                LazyVStack(spacing: 0) {
                    ForEach(Array(list.prefix(200).enumerated()), id: \.element.id) { i, p in
                        let on = !off.contains(p.id)
                        if i > 0 { Rectangle().fill(C.line).frame(height: 1) }
                        Button { if on { off.insert(p.id) } else { off.remove(p.id) } } label: {
                            HStack(spacing: 8) {
                                Image(systemName: on ? "checkmark.square.fill" : "square").font(.system(size: 22)).foregroundColor(on ? hu.fg : C.muted)
                                VStack(alignment: .leading, spacing: 1) { Text(p.title).font(.mont(14, .semibold)).foregroundColor(C.ink).multilineTextAlignment(.leading); Text("ID \(p.id)" + (p.area.isEmpty ? "" : " · \(p.area)")).font(.bSmall).foregroundColor(C.muted) }
                                Spacer(minLength: 0)
                            }.padding(.vertical, 4).frame(minHeight: 56).contentShape(Rectangle())
                        }.buttonStyle(.plain)
                    }
                }
                if list.count > 200 { Text(t("Showing the first 200. Choose a country to see the rest.", "Inaonyesha 200 za kwanza. Chagua nchi kuona zilizobaki.")).font(.bSmall).foregroundColor(C.muted).padding(8) }
            }.padding(.top, 10)
            CardV(pad: 16) {
                if let p = chosen.first {
                    Text(t("How a sticker looks", "Jinsi kibandiko kinavyoonekana")).font(.tSmall).foregroundColor(C.ink).frame(maxWidth: .infinity).padding(.bottom, 10)
                    QrStickerCard(p: p).frame(width: 220).frame(maxWidth: .infinity)
                } else { Text(t("Choose at least one project to see a sticker.", "Chagua angalau mradi mmoja kuona kibandiko.")).font(.bMedium).foregroundColor(C.muted).multilineTextAlignment(.center).frame(maxWidth: .infinity) }
                Text(t("\(chosen.count) " + (chosen.count == 1 ? "sticker" : "stickers") + " on \(pages) A4 " + (pages == 1 ? "page" : "pages"), "Vibandiko \(chosen.count) kwenye kurasa \(pages) za A4")).font(.tSmall).foregroundColor(C.ink).frame(maxWidth: .infinity).padding(.top, 12)
                Btn(busy ? t("Making the PDF…", "Inatengeneza PDF…") : t("Share the PDF to print", "Shiriki PDF ya kuchapisha"), icon: "share", enabled: !chosen.isEmpty && !busy, wide: true) {
                    busy = true; msg = nil
                    let data = QrStickers.pdf(chosen)
                    let url = FileManager.default.temporaryDirectory.appendingPathComponent("RAFIKI-QR-stickers.pdf")
                    do { try data.write(to: url); Share.items([url]) } catch { msg = t("Couldn’t make the PDF. Please try again.", "Imeshindwa kutengeneza PDF. Tafadhali jaribu tena.") }
                    busy = false
                }.padding(.top, 12)
                Msg(msg, "bad")
            }.padding(.top, 12)
            CardV(pad: 4) {
                DqmFold(icon: "info", hue: "blue", title: t("How it works", "Jinsi inavyofanya kazi"), summary: t("Scan it to open the Site visit", "Kichanganue kufungua Ziara"), tone: "has") {
                    Text(t("Print one for each project and fix it at the site (pump house, sign or office door). Scanning it opens the project’s Site visit, and monday.com notes the visit was checked in by QR.",
                           "Chapisha kimoja kwa kila mradi na ukibandike eneo la mradi (nyumba ya pampu, bango au mlango wa ofisi). Kukichanganua kunafungua Ziara ya mradi, na monday.com inaandika kuwa ziara iliingizwa kwa QR.")).font(.bMedium).foregroundColor(C.text)
                    Text(t("Share the PDF to a printer or a computer, print it on A4 at 100% and cut along the dotted lines.", "Shiriki PDF kwa printa au kompyuta, ichapishe kwenye A4 kwa ukubwa 100% na ukate kwenye mistari ya nukta.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 8)
                }
            }.padding(.top, 12)
        }
        .onAppear { if !ready { ready = true; ck = m.prefs.country } }
    }
}

/// The camera, reading QR codes live (VisionKit).
struct QrCamera: UIViewControllerRepresentable {
    let found: (String) -> Void
    func makeUIViewController(context: Context) -> DataScannerViewController {
        let vc = DataScannerViewController(recognizedDataTypes: [.barcode(symbologies: [.qr])], qualityLevel: .balanced, isHighFrameRateTrackingEnabled: false, isHighlightingEnabled: true)
        vc.delegate = context.coordinator
        try? vc.startScanning()
        return vc
    }
    func updateUIViewController(_ vc: DataScannerViewController, context: Context) {}
    func makeCoordinator() -> Coord { Coord(found) }
    final class Coord: NSObject, DataScannerViewControllerDelegate {
        let found: (String) -> Void; var done = false
        init(_ f: @escaping (String) -> Void) { found = f }
        func dataScanner(_ s: DataScannerViewController, didAdd items: [RecognizedItem], allItems: [RecognizedItem]) {
            guard !done else { return }
            for it in items { if case .barcode(let b) = it, let v = b.payloadStringValue { done = true; s.stopScanning(); DispatchQueue.main.async { self.found(v) }; return } }
        }
    }
}

/// Scan QR: the camera, a photo of the sticker, or the project number typed in.
struct ScanView: View {
    @EnvironmentObject var m: AppModel
    @State private var msg: (String, String)?
    @State private var typed = ""
    @State private var camera = false
    @State private var item: PhotosPickerItem?
    var body: some View {
        let hu = C.named("teal")
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text(t("Scan QR", "Changanua QR")).font(.hMedium).foregroundColor(C.ink).frame(maxWidth: .infinity)
                Text(t("Scan the RAFIKI sticker at the site. The project’s Site visit opens, checked in.", "Changanua kibandiko cha RAFIKI kilicho eneo la mradi. Ziara ya mradi inafunguka, ukiwa umeingia.")).font(.bMedium).foregroundColor(C.muted).multilineTextAlignment(.center).frame(maxWidth: .infinity).padding(.top, 4).padding(.bottom, 14)
                CardV {
                    ZStack { Icon("scan", size: 96, color: hu.fg); Icon("qr", size: 46, color: hu.fg) }.frame(width: 150, height: 150).background(hu.bg).clipShape(RoundedRectangle(cornerRadius: 32, style: .continuous)).frame(maxWidth: .infinity)
                    Btn(t("Scan with the camera", "Changanua kwa kamera"), icon: "scan", wide: true) {
                        msg = nil
                        if DataScannerViewController.isSupported && DataScannerViewController.isAvailable { camera = true }
                        else { msg = ("warn", t("The camera scanner isn’t available on this phone. Choose a photo of the code instead.", "Kichanganuzi cha kamera hakipatikani kwenye simu hii. Chagua picha ya msimbo badala yake.")) }
                    }.padding(.top, 16)
                    PhotosPicker(selection: $item, matching: .images) {
                        HStack(spacing: 8) { Icon("upload", size: 18, color: C.ink); Text(t("Choose a photo of the code", "Chagua picha ya msimbo")).font(.mont(14, .bold)).foregroundColor(C.ink) }
                            .frame(maxWidth: .infinity, minHeight: 44).background(C.card).clipShape(Capsule()).overlay(Capsule().stroke(C.edge, lineWidth: 1))
                    }.padding(.top, 10)
                    if let msg { Msg(msg.1, msg.0) }
                }
                CardV {
                    Text(t("No sticker, or it won’t scan?", "Hakuna kibandiko, au hakichanganuliki?")).font(.tSmall).foregroundColor(C.ink)
                    Field(t("Project number (on the sticker)", "Namba ya mradi (kwenye kibandiko)"), Binding(get: { typed }, set: { typed = $0.filter(\.isNumber) }), placeholder: t("e.g. 1234567890", "mf. 1234567890"), number: true)
                    Btn(t("Open", "Fungua"), enabled: !typed.isEmpty, light: true, wide: true) { handle(typed, byHand: true) }.padding(.top, 12)
                    Text(t("Typing the number opens the visit too, but it isn’t marked as checked in by QR.", "Kuandika namba kunafungua ziara pia, lakini haiandikwi kuwa umeingia kwa QR.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 8)
                }.padding(.top, 12)
            }.padding(16).padding(.bottom, 40)
        }
        .fullScreenCover(isPresented: $camera) {
            ZStack(alignment: .topTrailing) {
                QrCamera { v in camera = false; DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { handle(v) } }.ignoresSafeArea()
                Button { camera = false } label: { Icon("x", size: 22, color: .white).frame(width: 52, height: 52).background(Circle().fill(Color.black.opacity(0.5))) }.padding(20)
            }
        }
        .onChange(of: item) { it in
            guard let it else { return }
            Task {
                let img = (try? await it.loadTransferable(type: Data.self)).flatMap(UIImage.init(data:))
                item = nil
                if let txt = img.flatMap(QrCode.decode) { handle(txt) }
                else { msg = ("bad", t("No QR code found in that photo. Hold the phone closer, keep the code flat and in good light, then try again.", "Hakuna msimbo wa QR kwenye picha hiyo. Shika simu karibu zaidi, msimbo uwe tambarare na kwenye mwanga mzuri, kisha jaribu tena.")) }
            }
        }
    }
    private func handle(_ text: String, byHand: Bool = false) {
        guard let id = QrCheckin.parse(text) else { msg = ("bad", t("That QR code isn’t a RAFIKI project sticker.", "Msimbo huo wa QR si kibandiko cha mradi wa RAFIKI.")); return }
        guard m.snap?.projects.contains(where: { $0.id == id }) == true else { msg = ("warn", t("Project \(id) isn’t in your list. Pull down on Projects to refresh, then scan again.", "Mradi \(id) haupo kwenye orodha yako. Vuta chini kwenye Miradi kuonyesha upya, kisha changanua tena.")); return }
        if !byHand { QrCheckin.mark(id); Haptic.ok() }
        msg = nil
        m.back(); m.go(Place(tab: m.here.tab, project: id, projectTab: "visit"))
    }
}

/// In the Site visit: "Checked in by QR code" when the sticker was scanned.
struct QrCheckedIn: View {
    let id: String
    var body: some View {
        if QrCheckin.isFor(id) {
            let hu = C.named("teal")
            HStack(spacing: 8) { Icon("qr", size: 20, color: hu.fg); Text(t("Checked in by scanning the site’s QR code", "Umeingia kwa kuchanganua msimbo wa QR wa eneo")).font(.lLarge).foregroundColor(hu.fg); Spacer(minLength: 0) }
                .padding(.horizontal, 12).padding(.vertical, 10).background(hu.bg).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).padding(.top, 12)
        }
    }
}

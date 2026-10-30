import MapKit
import SwiftUI
import UIKit

// Offline map areas (Android OfflineMaps.kt, 2.16 / 2.18). The free map servers (OpenStreetMap) don't allow
// downloading whole areas, so saving an area marks it, and while you look over it with signal the street map you see
// is kept on this phone until you delete the area. If the office sets its own tile server (/api/pin "offlineTiles":
// {url, maxZoom, attr}, a server whose terms allow it), saving an area downloads the whole area from it, with progress.
// Apple's own map can't be kept for offline use, so with no signal the map shows the kept street map.

struct OfflineArea: Codable, Identifiable, Equatable {
    var id: String; var name: String; var n: Double; var s: Double; var e: Double; var w: Double
    var minZ: Int; var maxZ: Int; var at: Double; var bulk: Bool
}

struct OfficeTiles: Equatable { let url: String; let maxZ: Int; let attr: String }

enum FieldOffline {
    static var office: OfficeTiles?
    private static var overlay: KeepTiles?
    private static let ud = UserDefaults.standard
    static let KEEPS = [7, 30, 90]
    static var keepDays: Int {
        get { let v = ud.integer(forKey: "tiles-keep"); return v == 0 ? 30 : v }
        set { ud.set(newValue, forKey: "tiles-keep") }
    }

    /// The office tile server from the saved server answer; known servers that forbid bulk downloads are refused.
    static func configure(_ snap: JSON?) {
        guard let o = snap?["offlineTiles"] as? JSON else { office = nil; return }
        let url = o.str("url")
        let host = URL(string: url.replacingOccurrences(of: #"\{[a-z]\}"#, with: "0", options: .regularExpression))?.host ?? ""
        let noBulk = host.range(of: #"(^|\.)(openstreetmap\.(org|fr)|arcgisonline\.com|arcgis\.com|googleapis\.com|google\.com|opentopomap\.org|cartocdn\.com)$"#, options: [.regularExpression, .caseInsensitive]) != nil
        if url.hasPrefix("https://") && url.contains("{z}") && !host.isEmpty && !noBulk {
            office = OfficeTiles(url: url, maxZ: min(18, max(10, Int(o.num("maxZoom") ?? 16))), attr: o.str("attr"))
        } else { office = nil }
    }
    static var template: String { office?.url ?? "https://tile.openstreetmap.org/{z}/{x}/{y}.png" }
    static var attribution: String { office.map { $0.attr.isEmpty ? t("Office map", "Ramani ya ofisi") : $0.attr } ?? "© OpenStreetMap contributors" }

    /// Puts the kept street map over the map (or takes it off).
    static func apply(_ mv: MKMapView, street: Bool) {
        let have = mv.overlays.compactMap { $0 as? KeepTiles }
        if !street { if !have.isEmpty { mv.removeOverlays(have) }; return }
        if have.first?.urlTemplate == template { return }
        mv.removeOverlays(have)
        let o: KeepTiles
        if let ov = overlay, ov.urlTemplate == template { o = ov } else { o = KeepTiles(urlTemplate: template); o.canReplaceMapContent = true; o.maximumZ = office?.maxZ ?? 19; overlay = o }
        mv.insertOverlay(o, at: 0, level: .aboveLabels)
    }
    /// Called whenever the map stops moving: now and then, tiles viewed long ago outside saved areas are cleared.
    private static var lastClean = Date.distantPast
    static func viewed(_ mv: MKMapView) {
        guard Date().timeIntervalSince(lastClean) > 3600 else { return }
        lastClean = Date()
        DispatchQueue.global(qos: .utility).async { cleanup() }
    }

    // --- files ---
    static var root: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first ?? FileManager.default.temporaryDirectory
        var u = base.appendingPathComponent("tiles", isDirectory: true)
        if !FileManager.default.fileExists(atPath: u.path) {
            try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
            var rv = URLResourceValues(); rv.isExcludedFromBackup = true; try? u.setResourceValues(rv)
        }
        return u
    }
    static func folder(_ tpl: String) -> URL { root.appendingPathComponent(String(UInt(bitPattern: stableHash(tpl))), isDirectory: true) }
    private static func stableHash(_ s: String) -> Int { var h = 5381; for b in s.utf8 { h = (h &* 33) &+ Int(b) }; return h }
    static func file(_ tpl: String, _ z: Int, _ x: Int, _ y: Int) -> URL { folder(tpl).appendingPathComponent("\(z)/\(x)/\(y).png") }
    static func write(_ d: Data, _ url: URL) {
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? d.write(to: url, options: .atomic)
    }
    static func usedBytes() -> Int64 {
        var total: Int64 = 0
        if let en = FileManager.default.enumerator(at: root, includingPropertiesForKeys: [.fileSizeKey]) {
            for case let u as URL in en { total += Int64((try? u.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
        }
        return total
    }
    static func mbText(_ b: Int64) -> String { b < 1_048_576 ? t("under 1 MB", "chini ya 1 MB") : String(format: "%.0f MB", Double(b) / 1_048_576) }

    /// Clears tiles that haven't been loaded for `keepDays`, unless they are in a saved area.
    static func cleanup() {
        let keep = areas(), limit = Date().addingTimeInterval(-Double(keepDays) * 86400)
        guard let en = FileManager.default.enumerator(at: root, includingPropertiesForKeys: [.contentModificationDateKey]) else { return }
        for case let u as URL in en where u.pathExtension == "png" {
            let parts = u.pathComponents.suffix(3)
            guard parts.count == 3, let z = Int(parts[parts.startIndex]), let x = Int(parts[parts.startIndex + 1]), let y = Int(u.deletingPathExtension().lastPathComponent) else { continue }
            let when = (try? u.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? Date()
            if when > limit { continue }
            let c = tileCentre(z, x, y)
            if keep.contains(where: { c.lat <= $0.n && c.lat >= $0.s && c.lng <= $0.e && c.lng >= $0.w }) { continue }
            try? FileManager.default.removeItem(at: u)
        }
    }

    // --- tile maths ---
    static func tileX(_ lng: Double, _ z: Int) -> Int { Int(floor((lng + 180) / 360 * pow(2, Double(z)))) }
    static func tileY(_ lat: Double, _ z: Int) -> Int {
        let r = lat * .pi / 180
        return Int(floor((1 - log(tan(r) + 1 / cos(r)) / .pi) / 2 * pow(2, Double(z))))
    }
    static func tileCentre(_ z: Int, _ x: Int, _ y: Int) -> LatLng {
        let n = pow(2, Double(z))
        let lng = (Double(x) + 0.5) / n * 360 - 180
        let lat = atan(sinh(.pi * (1 - 2 * (Double(y) + 0.5) / n))) * 180 / .pi
        return LatLng(lat: lat, lng: lng)
    }
    static func tileCount(_ a: OfflineArea) -> Int {
        var n = 0
        for z in a.minZ...a.maxZ { n += (abs(tileX(a.e, z) - tileX(a.w, z)) + 1) * (abs(tileY(a.s, z) - tileY(a.n, z)) + 1) }
        return n
    }
    /// The zooms to keep for a box: from 9 down to as close as fits in about 30,000 tiles.
    static func zooms(n: Double, s: Double, e: Double, w: Double) -> ClosedRange<Int>? {
        let top = office?.maxZ ?? 16
        var z = top
        while z >= 11 {
            let a = OfflineArea(id: "", name: "", n: n, s: s, e: e, w: w, minZ: 9, maxZ: z, at: 0, bulk: false)
            if tileCount(a) <= 30_000 { return 9...z }
            z -= 1
        }
        return nil
    }

    // --- saved areas ---
    static func areas() -> [OfflineArea] {
        guard let d = ud.data(forKey: "offline-areas"), let l = try? JSONDecoder().decode([OfflineArea].self, from: d) else { return [] }
        return l
    }
    static func save(_ l: [OfflineArea]) { if let d = try? JSONEncoder().encode(l) { ud.set(d, forKey: "offline-areas") } }
    static func add(_ a: OfflineArea) { save(areas() + [a]) }
    static func remove(_ id: String) { save(areas().filter { $0.id != id }); DispatchQueue.global(qos: .utility).async { cleanup() } }
    /// How much of an area's street map is kept (checked on a sample of tiles at its closest zoom).
    static func kept(_ a: OfflineArea) -> Double {
        let z = a.maxZ, x0 = min(tileX(a.w, z), tileX(a.e, z)), x1 = max(tileX(a.w, z), tileX(a.e, z)), y0 = min(tileY(a.n, z), tileY(a.s, z)), y1 = max(tileY(a.n, z), tileY(a.s, z))
        let sx = max(1, (x1 - x0) / 12), sy = max(1, (y1 - y0) / 12)
        var have = 0, all = 0
        var x = x0
        while x <= x1 { var y = y0; while y <= y1 { all += 1; if FileManager.default.fileExists(atPath: file(template, z, x, y).path) { have += 1 }; y += sy }; x += sx }
        return all == 0 ? 0 : Double(have) / Double(all)
    }
}

/// A map tile layer that keeps every tile it loads on the phone and shows the kept one when there is no signal.
final class KeepTiles: MKTileOverlay {
    override func loadTile(at path: MKTileOverlayPath, result: @escaping (Data?, Error?) -> Void) {
        let tpl = urlTemplate ?? FieldOffline.template
        let f = FieldOffline.file(tpl, path.z, path.x, path.y)
        let kept = try? Data(contentsOf: f)
        let fresh = ((try? f.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate).map { Date().timeIntervalSince($0) < 7 * 86400 }) ?? false
        if let kept, fresh || !NetWatch.shared.online { result(kept, nil); return }
        guard NetWatch.shared.online else { result(nil, nil); return }
        var req = URLRequest(url: url(forTilePath: path), timeoutInterval: 20)
        req.setValue("RAFIKI-Pin-iOS (rafikioutreach.org)", forHTTPHeaderField: "User-Agent")
        URLSession.shared.dataTask(with: req) { d, r, e in
            if let d, (r as? HTTPURLResponse)?.statusCode == 200, !d.isEmpty { FieldOffline.write(d, f); result(d, nil) } else { result(kept, kept == nil ? e : nil) }
        }.resume()
    }
}

/// A whole-area download from the office tile server, with progress.
@MainActor final class OfflineJob: ObservableObject {
    static let shared = OfflineJob()
    @Published var name = ""
    @Published var done = 0
    @Published var total = 0
    @Published var running = false
    private var task: Task<Void, Never>?
    func start(_ a: OfflineArea) {
        guard let o = FieldOffline.office, !running else { return }
        name = a.name; done = 0; total = FieldOffline.tileCount(a); running = true
        task = Task {
            for z in a.minZ...a.maxZ {
                let x0 = min(FieldOffline.tileX(a.w, z), FieldOffline.tileX(a.e, z)), x1 = max(FieldOffline.tileX(a.w, z), FieldOffline.tileX(a.e, z))
                let y0 = min(FieldOffline.tileY(a.n, z), FieldOffline.tileY(a.s, z)), y1 = max(FieldOffline.tileY(a.n, z), FieldOffline.tileY(a.s, z))
                for x in x0...x1 {
                    for y in y0...y1 {
                        if Task.isCancelled { running = false; return }
                        let f = FieldOffline.file(o.url, z, x, y)
                        if !FileManager.default.fileExists(atPath: f.path) {
                            let u = o.url.replacingOccurrences(of: "{z}", with: "\(z)").replacingOccurrences(of: "{x}", with: "\(x)").replacingOccurrences(of: "{y}", with: "\(y)")
                            if let url = URL(string: u), let res = try? await URLSession.shared.data(from: url), (res.1 as? HTTPURLResponse)?.statusCode == 200 { FieldOffline.write(res.0, f) }
                        }
                        done += 1
                    }
                }
            }
            running = false
        }
    }
    func stop() { task?.cancel(); running = false }
}

/// Offline areas: save the area on screen (or around you) before a trip, see what is kept, delete areas.
@MainActor
struct FieldOfflineSheet: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject private var job = OfflineJob.shared
    @ObservedObject private var net = NetWatch.shared
    let ctl: MapCtl; let street: () -> Void; let close: () -> Void
    @State private var which = "screen"
    @State private var name = ""
    @State private var areas: [OfflineArea] = FieldOffline.areas()
    @State private var used: Int64 = 0
    @State private var ask: OfflineArea?
    @State private var keep = FieldOffline.keepDays
    @State private var err: String?

    var body: some View {
        let me: LatLng? = Locator.shared.fix?.ll
        let box: (Double, Double, Double, Double)? = which == "me" ? me.map { ($0.lat + 0.225, $0.lat - 0.225, $0.lng + 0.225, $0.lng - 0.225) } : ctl.visible()
        let zs: ClosedRange<Int>? = box.flatMap { FieldOffline.zooms(n: $0.0, s: $0.1, e: $0.2, w: $0.3) }
        let bulk: Bool = FieldOffline.office != nil
        OSheet {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(t("Save this area for offline", "Hifadhi eneo hili kwa matumizi bila mtandao")).font(.tLarge).foregroundColor(C.ink).fixedSize(horizontal: false, vertical: true)
                    Text(t("Before a trip, so the map works with no signal.", "Kabla ya safari, ili ramani ifanye kazi bila mtandao.")).font(.bSmall).foregroundColor(C.muted)
                }.frame(maxWidth: .infinity, alignment: .leading)
                OCloseX(action: close)
            }
            if job.running { progress }
            VStack(alignment: .leading, spacing: 0) {
                SmallSeg(items: [("screen", t("Area on screen", "Eneo lililo kwenye skrini")), ("me", t("Around me", "Karibu nami"))], sel: which, fill: true) { which = $0 }.padding(.top, 16)
                if which == "me" && me == nil { Msg(t("Allow location to save the area around you.", "Ruhusu mahali ili uhifadhi eneo linalokuzunguka."), "warn").padding(.top, 8) }
                if which == "me" && me != nil { Text(t("About 50 × 50 km around you, roughly a county.", "Takriban km 50 × 50 kukuzunguka, karibu ukubwa wa kaunti.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 8) }
                Field(t("Name", "Jina"), $name, placeholder: t("Area \(areas.count + 1)", "Eneo \(areas.count + 1)")).padding(.top, 12)
                estimate(box, zs)
                Text(bulk ? t("The whole area downloads now from RAFIKI’s own map server. Stay on Wi-Fi or good signal until it finishes.", "Eneo lote linapakuliwa sasa kutoka kwa seva ya ramani ya RAFIKI. Baki kwenye Wi-Fi au mtandao mzuri hadi likamilike.")
                     : t("The free map servers don’t allow downloading whole areas, so the app keeps what you look at. After saving, move and zoom around the area while you have signal: everything you see stays on this phone until you delete the area.",
                         "Seva za ramani za bure haziruhusu kupakua maeneo yote, kwa hiyo programu inahifadhi unachoangalia. Baada ya kuhifadhi, sogeza na kuza ramani ndani ya eneo ukiwa na mtandao: kila unachoona kinabaki kwenye simu hii hadi utakapofuta eneo."))
                    .font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true).padding(.top, 12)
                Msg(err, "bad").padding(.top, 6)
            }
            Btn(bulk ? t("Download this area", "Pakua eneo hili") : t("Save this area", "Hifadhi eneo hili"), icon: "offline", enabled: box != nil && zs != nil && !job.running) { saveArea(box, zs, bulk) }.padding(.top, 14)
            savedList
            keepSetting
        }
        .onAppear { FieldOffline.configure(m.prefs.loadSnapshot()); refreshUsed() }
        .sheet(item: $ask) { a in
            OSheet {
                OAsk(question: t("Delete “\(a.name)” from this phone?", "Futa “\(a.name)” kwenye simu hii?"), yes: t("Delete", "Futa"), onNo: { ask = nil }) {
                    FieldOffline.remove(a.id); areas = FieldOffline.areas(); ask = nil
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1) { refreshUsed() }
                }
            }
        }
    }

    var progress: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(t("Downloading “\(job.name)”", "Inapakua “\(job.name)”")).font(.tSmall).foregroundColor(C.ink)
            Text(t("\(job.done) of \(job.total) map tiles", "Vigae \(job.done) kati ya \(job.total) vya ramani")).font(.bSmall).foregroundColor(C.muted)
            ProgressView(value: Double(job.done), total: Double(max(1, job.total))).tint(C.blue)
            Btn(t("Stop", "Simamisha"), light: true, small: true) { job.stop() }
        }.padding(14).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).padding(.top, 12)
    }

    @ViewBuilder func estimate(_ box: (Double, Double, Double, Double)?, _ zs: ClosedRange<Int>?) -> some View {
        if box != nil && zs == nil {
            Text(t("That area is too big. Zoom in on the map, or choose Around me.", "Eneo hilo ni kubwa mno. Kuza ramani, au chagua Karibu nami.")).font(.tSmall).foregroundColor(C.bad).padding(.top, 10)
        } else if let b = box, let z = zs {
            let n: Int = FieldOffline.tileCount(OfflineArea(id: "", name: "", n: b.0, s: b.1, e: b.2, w: b.3, minZ: z.lowerBound, maxZ: z.upperBound, at: 0, bulk: false))
            VStack(alignment: .leading, spacing: 3) {
                Text(t("About \(n) map tiles · up to \(FieldOffline.mbText(Int64(n) * 15_000))", "Takriban vigae \(n) vya ramani · hadi \(FieldOffline.mbText(Int64(n) * 15_000))")).font(.tSmall).foregroundColor(C.ink)
                Text(z.upperBound >= 14 ? t("Zoom \(z.lowerBound)–\(z.upperBound): from the whole area down to villages and roads.", "Ukuzaji \(z.lowerBound)–\(z.upperBound): kutoka eneo zima hadi vijiji na barabara.")
                     : t("Zoom \(z.lowerBound)–\(z.upperBound): a wide area, so only down to towns. Zoom in on the map first to keep village roads.", "Ukuzaji \(z.lowerBound)–\(z.upperBound): eneo pana, kwa hiyo hadi miji tu. Kuza ramani kwanza ili kuhifadhi barabara za vijijini."))
                    .font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true)
                Text(t("Maps on this phone: \(FieldOffline.mbText(used))", "Ramani kwenye simu hii: \(FieldOffline.mbText(used))")).font(.bSmall).foregroundColor(C.muted)
            }.padding(.top, 10)
        }
    }

    var savedList: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(t("Saved areas (\(areas.count))", "Maeneo yaliyohifadhiwa (\(areas.count))")).font(.tMedium).foregroundColor(C.ink).padding(.top, 22)
            if areas.isEmpty {
                Text(t("No areas saved yet.", "Bado hakuna eneo lililohifadhiwa.")).font(.bMedium).foregroundColor(C.muted)
            }
            ForEach(areas) { a in OfflineAreaRow(a: a, show: {
                ctl.fit([LatLng(lat: a.n, lng: a.w), LatLng(lat: a.s, lng: a.e)], pad: UIEdgeInsets(top: 60, left: 30, bottom: 60, right: 30)); street(); close()
            }, more: {
                if a.bulk && FieldOffline.office != nil { job.start(a) } else { ctl.fit([LatLng(lat: a.n, lng: a.w), LatLng(lat: a.s, lng: a.e)]); street(); close() }
            }, delete: { ask = a }) }
        }
    }

    var keepSetting: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(t("Keep map tiles I’ve viewed for", "Hifadhi vigae vya ramani nilivyoangalia kwa")).font(.tSmall).foregroundColor(C.ink).padding(.top, 22)
            SmallSeg(items: FieldOffline.KEEPS.map { ("\($0)", t("\($0) days", "siku \($0)")) }, sel: "\(keep)", fill: true) { v in keep = Int(v) ?? 30; FieldOffline.keepDays = keep }
            Text(t("Outside saved areas, map tiles you have looked at are cleared once they have been out of date this long. Saved areas stay until you delete them.",
                   "Nje ya maeneo yaliyohifadhiwa, vigae vya ramani ulivyoangalia vinafutwa vikikaa muda huu bila kusasishwa. Maeneo yaliyohifadhiwa yanabaki hadi utakapoyafuta."))
                .font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true)
            Text(FieldOffline.attribution).font(.lSmall).foregroundColor(C.muted).padding(.top, 6)
        }
    }

    func refreshUsed() { DispatchQueue.global(qos: .utility).async { let b = FieldOffline.usedBytes(); DispatchQueue.main.async { used = b } } }

    func saveArea(_ box: (Double, Double, Double, Double)?, _ zs: ClosedRange<Int>?, _ bulk: Bool) {
        guard let b = box, let z = zs else { err = t("That area is too big.", "Eneo hilo ni kubwa mno."); return }
        let nm = name.trimmingCharacters(in: .whitespaces).isEmpty ? t("Area \(areas.count + 1)", "Eneo \(areas.count + 1)") : name.trimmingCharacters(in: .whitespaces)
        let a = OfflineArea(id: "a" + String(Int(Date().timeIntervalSince1970), radix: 36), name: nm, n: b.0, s: b.1, e: b.2, w: b.3, minZ: z.lowerBound, maxZ: z.upperBound, at: Date().timeIntervalSince1970, bulk: bulk)
        FieldOffline.add(a); areas = FieldOffline.areas(); name = ""; err = nil; Haptic.tap()
        street()
        if bulk { job.start(a) } else {
            m.showBanner(t("“\(nm)” is saved. Move and zoom around it while you have signal: the map you see is kept on this phone.", "“\(nm)” imehifadhiwa. Sogeza na kuza ramani ndani yake ukiwa na mtandao: ramani unayoona inahifadhiwa kwenye simu hii."))
            ctl.fit([LatLng(lat: a.n, lng: a.w), LatLng(lat: a.s, lng: a.e)], pad: UIEdgeInsets(top: 60, left: 30, bottom: 60, right: 30))
            close()
        }
    }
}

private struct OfflineAreaRow: View {
    let a: OfflineArea; let show: () -> Void; let more: () -> Void; let delete: () -> Void
    @State private var pct: Double = 0
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 10) {
                OTileIcon(icon: "offline", tint: ohue("offline").fg, bg: ohue("offline").bg, size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(a.name).font(.tSmall).foregroundColor(C.ink)
                    Text(t("Saved \(ODates.day(ISO8601DateFormatter().string(from: Date(timeIntervalSince1970: a.at)))) · zoom \(a.minZ)–\(a.maxZ)", "Imehifadhiwa \(ODates.day(ISO8601DateFormatter().string(from: Date(timeIntervalSince1970: a.at)))) · ukuzaji \(a.minZ)–\(a.maxZ)")).font(.bSmall).foregroundColor(C.muted)
                    Text(t("\(Int(pct * 100))% of the street map kept", "\(Int(pct * 100))% ya ramani ya barabara imehifadhiwa")).font(.lMedium).foregroundColor(C.ink).padding(.top, 2)
                }.frame(maxWidth: .infinity, alignment: .leading)
                Button(action: delete) { Icon("trash", size: 22, color: C.bad).frame(width: 44, height: 44) }.buttonStyle(.plain).accessibilityLabel(t("Delete \(a.name)", "Futa \(a.name)"))
            }
            HStack(spacing: 8) {
                Btn(t("Show on map", "Onyesha kwenye ramani"), icon: "map", light: true, small: true, wide: true, action: show)
                Btn(a.bulk && FieldOffline.office != nil ? t("Download again", "Pakua tena") : t("Keep more", "Hifadhi zaidi"), icon: "offline", light: true, small: true, wide: true, action: more)
            }
        }.padding(12).background(C.card).clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(C.line, lineWidth: 1))
        .onAppear { DispatchQueue.global(qos: .utility).async { let v = FieldOffline.kept(a); DispatchQueue.main.async { pct = v } } }
    }
}

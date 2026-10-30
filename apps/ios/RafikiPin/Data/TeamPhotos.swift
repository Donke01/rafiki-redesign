import CryptoKit
import Foundation
import SwiftUI
import UIKit

/// Profile photos (Android 2.25): who has one (name → the URL the server gave, with its version), and the pictures
/// themselves, kept on the phone so the last photo still shows without signal. The URLs come with the app's list
/// (/api/pin "photos" and me.photo) and the team lists. A new photo is a new URL. Anyone without one keeps their initials.
@MainActor final class TeamPhotos: ObservableObject {
    static let shared = TeamPhotos()
    /// Lower-case trimmed name → URL ("/api/team-photo?name=…&v=…").
    @Published private(set) var urls: [String: String] = [:]
    /// Goes up whenever a picture arrives or goes, so faces redraw.
    @Published private(set) var tick = 0
    private let mem = NSCache<NSString, UIImage>()
    private var inflight: [String: Task<UIImage?, Never>] = [:]
    private let dir: URL

    init() {
        let d = Prefs.dataDir.appendingPathComponent(MeKV.demo ? "teamphotos-demo" : "teamphotos", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        dir = d
        // the last known URLs, so a fresh open without signal still shows faces
        if let data = try? Data(contentsOf: d.appendingPathComponent("index.json")), let j = (try? JSONSerialization.jsonObject(with: data)) as? [String: String] { urls = j }
    }

    func key(_ name: String) -> String { name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }

    /// This person's photo URL, or nil for initials.
    func url(_ name: String?) -> String? {
        guard let name, !name.trimmingCharacters(in: .whitespaces).isEmpty, let u = urls[key(name)], !u.isEmpty else { return nil }
        return u
    }

    /// One person's photo changed (or went: url "").
    func set(_ name: String, _ url: String) {
        guard !name.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        let k = key(name)
        if url.isEmpty { if urls.removeValue(forKey: k) != nil { save() } } else if urls[k] != url { urls[k] = url; save() }
    }

    /// The app's list: "photos" is everyone who has one, so anyone missing from it has none now.
    func absorbList(_ j: JSON?) {
        guard let j, j["projects"] != nil, let ps = j.obj("photos") else { return }
        var fresh: [String: String] = [:]
        for (n, v) in ps { if let s = v as? String, !s.isEmpty { fresh[key(n)] = s } }
        let who = j.str("who")
        if let me = j.obj("me"), me["photo"] != nil, !who.isEmpty {
            let u = me.str("photo")
            if u.isEmpty || u == "null" { fresh.removeValue(forKey: key(who)) } else { fresh[key(who)] = u }
        }
        if fresh != urls { urls = fresh; save() }
    }

    /// A list of people (the team): each one's photo, or none.
    func absorbPeople(_ list: [JSON]) {
        for o in list where o["photo"] != nil && !o.str("name").isEmpty {
            let u = o.str("photo")
            set(o.str("name"), u == "null" ? "" : u)
        }
    }

    private func save() {
        if let data = try? JSONSerialization.data(withJSONObject: urls) { try? data.write(to: dir.appendingPathComponent("index.json"), options: .atomic) }
    }

    private func file(_ name: String) -> URL {
        let h = Insecure.SHA1.hash(data: Data(key(name).utf8)).map { String(format: "%02x", $0) }.joined().prefix(20)
        return dir.appendingPathComponent(String(h) + ".img")
    }

    /// Already in memory: no loading flash when a list scrolls.
    func cached(_ url: String?) -> UIImage? { url.flatMap { mem.object(forKey: $0 as NSString) } }

    /// The picture for [name] at [url]: from memory, the phone, or the server. Without signal the last picture kept.
    func load(_ api: Api, name: String, url: String) async -> UIImage? {
        if let c = mem.object(forKey: url as NSString) { return c }
        if let running = inflight[url] { return await running.value }
        let f = file(name), tag = f.appendingPathExtension("url")
        let task = Task<UIImage?, Never> { () -> UIImage? in
            if (try? String(contentsOf: tag, encoding: .utf8)) == url, let d = try? Data(contentsOf: f), let img = UIImage(data: d) { return img }
            // the sign-in only ever goes to our own server
            let rel = url.hasPrefix(api.server) ? String(url.dropFirst(api.server.count)) : url
            var res: (Int, Data) = (0, Data())
            if rel.hasPrefix("/") { res = await api.bytes(rel) }
            let status = res.0, data = res.1
            if (200..<300).contains(status), let img = UIImage(data: data) {
                try? data.write(to: f, options: .atomic); try? url.write(to: tag, atomically: true, encoding: .utf8)
                return img
            }
            if status == 404 { try? FileManager.default.removeItem(at: f); try? FileManager.default.removeItem(at: tag); return nil }
            // no signal or a hiccup: the last photo kept for them
            if let d = try? Data(contentsOf: f) { return UIImage(data: d) }
            return nil
        }
        inflight[url] = task
        let img = await task.value
        inflight[url] = nil
        if let img { mem.setObject(img, forKey: url as NSString); tick += 1 }
        return img
    }

    private func path(_ forName: String?) -> String {
        guard let n = forName, !n.trimmingCharacters(in: .whitespaces).isEmpty else { return "/api/team-photo" }
        return "/api/team-photo?name=" + Api.enc(n)
    }

    /// Sets a photo (forName nil = my own; someone else's needs an admin). Returns the new URL, or an error text.
    func upload(_ api: Api, forName: String?, me: String, jpeg: Data) async -> (String?, String?) {
        let r = await api.multipart(path(forName), data: jpeg, mime: "image/jpeg", filename: "photo.jpg", fields: [])
        if !r.ok { return (nil, TeamPhotos.why(r)) }
        let who = r.json.str("name").isEmpty ? (forName ?? me) : r.json.str("name")
        let url = r.json.str("photo")
        // shown at once, and kept, without fetching it back
        if !url.isEmpty, let img = UIImage(data: jpeg) {
            mem.setObject(img, forKey: url as NSString)
            let f = file(who)
            try? jpeg.write(to: f, options: .atomic); try? url.write(to: f.appendingPathExtension("url"), atomically: true, encoding: .utf8)
        }
        set(who, url); tick += 1
        return (url, nil)
    }

    /// Removes a photo. Returns an error text, or nil when done.
    func remove(_ api: Api, forName: String?, me: String) async -> String? {
        let r = await api.postTo(path(forName), ["remove": true])
        if !r.ok { return TeamPhotos.why(r) }
        let who = r.json.str("name").isEmpty ? (forName ?? me) : r.json.str("name")
        set(who, "")
        let f = file(who)
        try? FileManager.default.removeItem(at: f); try? FileManager.default.removeItem(at: f.appendingPathExtension("url"))
        tick += 1
        return nil
    }

    nonisolated static func why(_ r: Reply) -> String {
        if r.status == 0 { return t("No signal. Try again when you’re online.", "Hakuna mtandao. Jaribu tena ukiwa mtandaoni.") }
        switch r.reason {
        case "too-large": return t("That photo is too big. Try another one.", "Picha hiyo ni kubwa mno. Jaribu nyingine.")
        case "type": return t("That kind of picture can’t be used. Choose a photo.", "Aina hiyo ya picha haiwezi kutumika. Chagua picha.")
        case "forbidden": return t("Only admins can change someone else’s photo.", "Wasimamizi pekee wanaweza kubadilisha picha ya mtu mwingine.")
        case "named-only": return t("You need your own PIN to add a photo. Ask the office for one.", "Unahitaji PIN yako mwenyewe kuweka picha. Omba ofisi ikupe.")
        case "no-person": return t("They aren’t on the team list.", "Hayupo kwenye orodha ya timu.")
        default: return reasonText(r.reason)
        }
    }
}

/// Shrinks a chosen picture for a profile photo: a square, about 512 px, JPEG, 200 KB or less.
enum AvatarImage {
    static let size: CGFloat = 512
    static let maxBytes = 200_000

    /// The square of the picture under the round frame. The picture covers a `view`-point square at `zoom` (1 = just
    /// covers it), moved by (dx, dy) points from the centre. Returns the square in the picture's pixels.
    static func cropRect(_ w: CGFloat, _ h: CGFloat, view: CGFloat, zoom: CGFloat, dx: CGFloat, dy: CGFloat) -> CGRect {
        let s = view / min(w, h) * zoom
        let side = min(view / s, min(w, h))
        let cx = w / 2 - dx / s, cy = h / 2 - dy / s
        let l = min(max(cx - side / 2, 0), w - side), tp = min(max(cy - side / 2, 0), h - side)
        return CGRect(x: l, y: tp, width: max(1, side), height: max(1, side))
    }

    /// How far the picture may move from the centre at `zoom` and still cover the frame.
    static func maxShift(_ w: CGFloat, _ h: CGFloat, view: CGFloat, zoom: CGFloat) -> CGSize {
        let s = view / min(w, h) * zoom
        return CGSize(width: max(0, (w * s - view) / 2), height: max(0, (h * s - view) / 2))
    }

    /// The picture turned upright, in pixels.
    static func upright(_ img: UIImage) -> UIImage {
        if img.imageOrientation == .up && img.scale == 1 { return img }
        let f = UIGraphicsImageRendererFormat(); f.scale = 1
        let px = CGSize(width: img.size.width * img.scale, height: img.size.height * img.scale)
        return UIGraphicsImageRenderer(size: px, format: f).image { _ in img.draw(in: CGRect(origin: .zero, size: px)) }
    }

    /// The square cut out, at most 512 px, as JPEG under 200 KB.
    static func jpeg(_ img: UIImage, _ r: CGRect) -> Data? {
        let up = upright(img)
        guard let cg = up.cgImage?.cropping(to: r.integral) else { return nil }
        let side = min(size, CGFloat(cg.width))
        let f = UIGraphicsImageRendererFormat(); f.scale = 1
        var out: UIImage = UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: f).image { _ in
            UIImage(cgImage: cg).draw(in: CGRect(x: 0, y: 0, width: side, height: side))
        }
        while true {
            var q: CGFloat = 0.88
            while q >= 0.5 {
                if let d = out.jpegData(compressionQuality: q), d.count <= maxBytes { return d }
                q -= 0.08
            }
            if out.size.width <= 128 { return out.jpegData(compressionQuality: 0.4) }
            let w = (out.size.width * 0.8).rounded()
            let prev = out
            out = UIGraphicsImageRenderer(size: CGSize(width: w, height: w), format: f).image { _ in prev.draw(in: CGRect(x: 0, y: 0, width: w, height: w)) }
        }
    }
}

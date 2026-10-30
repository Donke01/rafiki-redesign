import Foundation
import ImageIO
import Network
import UIKit

/// One story photo on its way: queued → sending → done, or waiting (no signal; sends by itself) or failed (Tap to retry).
struct StoryUp: Identifiable, Equatable {
    let key: String; let project: String; let row: String; let children: Bool; let cap: String
    /// The file's name inside the queue's folder (not a full path: iOS moves the app's folder on updates).
    let file: String
    let batch: String; let taken: String
    var state = "queued"
    var progress: Double = 0
    var reason = ""
    /// The photo's asset id on the server once it is there.
    var asset = ""
    /// Hidden from the banner (Hide, or the banner said "added ✓" and went).
    var seen = false

    var id: String { key }
    var active: Bool { state == "queued" || state == "sending" || state == "waiting" }
}

/// Story photos on their way to the server (as Android 2.27+). Each photo is made smaller on the phone (longest side
/// 1600 px, JPEG 0.82, turned the right way up), kept in the app's folder with a small JSON list, and sent one after
/// another to POST /api/story { action: photo, … } with its progress. The list survives leaving the screen, the app
/// being closed and no signal: it carries on when the phone is back online, when the app starts and when a story opens.
@MainActor
final class StoryUploads: ObservableObject {
    @Published private(set) var items: [StoryUp] = []
    /// The freshest story from the server per project (every photo answer carries it), for an open story writer.
    private(set) var fresh: [String: JSON] = [:]
    /// Bumped each time `fresh` has something new for a project.
    @Published private(set) var freshAt: [String: Int] = [:]
    /// Demo screenshots: hold the queue still (nothing is sent).
    var paused = false

    let dir: URL
    private let api: Api
    private var running = false
    private let monitor = NWPathMonitor()
    private var list: URL { dir.appendingPathComponent("queue.json") }

    init(dir: URL, api: Api) {
        self.dir = dir
        self.api = api
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        load()
        monitor.pathUpdateHandler = { [weak self] path in
            guard path.status == .satisfied else { return }
            Task { @MainActor in
                guard let me = self, me.items.contains(where: { $0.state == "waiting" }) else { return }
                me.resume()
            }
        }
        monitor.start(queue: DispatchQueue(label: "org.rafikioutreach.pin.story-net"))
    }

    func url(_ u: StoryUp) -> URL { dir.appendingPathComponent(u.file) }

    /// Photos still to send (queued, sending or waiting for signal), for one project or all.
    func sending(_ project: String? = nil) -> Int { items.filter { $0.active && (project == nil || $0.project == project) }.count }

    /// Adds photos (file paths, with "shows children") to a project's row: shrinks each into the queue's folder, then sends
    /// them. Each carries the day it was taken (EXIF DateTimeOriginal; a camera shot taken now: today). The picked
    /// copies in the temporary folder are deleted.
    func add(project: String, row: String, files: [(String, Bool)], camera: Bool) async {
        let batch = "b\(Int(Date().timeIntervalSince1970 * 1000))"
        let folder = dir
        let today = When.localDay()
        let made: [StoryUp] = await Task.detached(priority: .userInitiated) { () -> [StoryUp] in
            var out: [StoryUp] = []
            for (i, f) in files.enumerated() {
                let src = URL(fileURLWithPath: f.0)
                guard let data = try? Data(contentsOf: src) else { continue }
                let day = StoryUploads.takenDay(data) ?? (camera ? today : "")
                let name = "\(batch)_\(i).jpg"
                if StoryUploads.shrink(data, to: folder.appendingPathComponent(name)) {
                    if src.path.hasPrefix(FileManager.default.temporaryDirectory.path) { try? FileManager.default.removeItem(at: src) }
                    out.append(StoryUp(key: "\(batch)_\(i)", project: project, row: row, children: f.1, cap: "", file: name, batch: batch, taken: day))
                }
            }
            return out
        }.value
        items.append(contentsOf: made)
        save()
        resume()
    }

    /// Sends what is waiting (on start, back online, a story opened).
    func resume() {
        for i in items.indices where items[i].state == "waiting" { items[i].state = "queued" }
        if paused || running || !items.contains(where: { $0.state == "queued" }) { return }
        running = true
        Task { @MainActor in
            await run()
            running = false
        }
    }

    /// Tap to retry: a photo that couldn't send (or all of them with key nil) goes back in the queue.
    func retry(_ key: String? = nil) {
        for i in items.indices where items[i].state == "failed" && (key == nil || items[i].key == key) {
            items[i].state = "queued"; items[i].reason = ""; items[i].progress = 0; items[i].seen = false
        }
        save(); resume()
    }

    /// Hide the banner for these photos (they keep sending).
    func hide(_ project: String) {
        for i in items.indices where items[i].project == project { items[i].seen = true }
        tidy()
    }

    /// Done photos leave the list once nobody needs to see them.
    func tidy() { items.removeAll { $0.state == "done" && $0.seen }; save() }

    /// Demo screenshots: photos shown as on their way, without sending anything.
    func demoShow(_ ups: [StoryUp]) { paused = true; items = ups }

    private func set(_ key: String, _ f: (inout StoryUp) -> Void) {
        if let i = items.firstIndex(where: { $0.key == key }) { f(&items[i]) }
    }

    private func run() async {
        while !paused {
            guard let i = items.firstIndex(where: { $0.state == "queued" }) else { break }
            let u = items[i]
            let key = u.key
            set(key) { $0.state = "sending"; $0.progress = 0 }
            let file = url(u)
            guard let data = try? Data(contentsOf: file) else {
                set(key) { $0.state = "failed"; $0.reason = "missing" }
                save(); continue
            }
            var fields: [(String, String)] = [("action", "photo"), ("id", u.project), ("row", u.row), ("children", u.children ? "true" : "false")]
            if !u.cap.isEmpty { fields.append(("cap", u.cap)) }
            if !u.taken.isEmpty { fields.append(("taken", u.taken)) }
            let last = Last()
            let r = await api.multipart("/api/story", data: data, mime: "image/jpeg", filename: "story.jpg", fields: fields) { [weak self] p in
                let pc = Int(p * 100)
                if pc == last.value { return }
                last.value = pc
                Task { @MainActor in self?.set(key) { if $0.state == "sending" { $0.progress = p } } }
            }
            if r.ok {
                let asset = r.json.obj("photo")?.str("id") ?? ""
                set(key) { $0.asset = asset; $0.progress = 1; $0.state = "done" }
                if let s = r.json.obj("story") { fresh[u.project] = s; freshAt[u.project, default: 0] += 1 }
                try? FileManager.default.removeItem(at: file)
            } else if r.status == 0 {
                // no signal: this one and the rest wait, and go by themselves when the phone is back online
                set(key) { $0.state = "waiting"; $0.progress = 0 }
                for j in items.indices where items[j].state == "queued" { items[j].state = "waiting" }
                save(); return
            } else {
                let why = r.reason ?? "down"
                set(key) { $0.state = "failed"; $0.reason = why; $0.progress = 0 }
            }
            save()
        }
    }

    // --- the list on disk: what is still to send (and what failed, with why) -------------------------------------
    private func save() {
        let a: [JSON] = items.filter { $0.state != "done" }.map { u in
            ["key": u.key, "project": u.project, "row": u.row, "children": u.children, "cap": u.cap, "file": u.file, "batch": u.batch,
             "taken": u.taken, "failed": u.state == "failed", "reason": u.reason]
        }
        if let data = try? JSONSerialization.data(withJSONObject: a) { try? data.write(to: list, options: .atomic) }
    }

    private func load() {
        guard let data = try? Data(contentsOf: list), let a = (try? JSONSerialization.jsonObject(with: data)) as? [Any] else { return }
        for case let o as JSON in a {
            let file = o.str("file")
            guard !file.isEmpty, FileManager.default.fileExists(atPath: dir.appendingPathComponent(file).path) else { continue }
            var u = StoryUp(key: o.str("key"), project: o.str("project"), row: o.str("row"), children: o.bool("children"), cap: o.str("cap"),
                            file: file, batch: o.str("batch"), taken: o.str("taken"))
            if o.bool("failed") { u.state = "failed"; u.reason = o.str("reason") }
            items.append(u)
        }
    }

    // --- making photos small, and the day they were taken ---------------------------------------------------------

    /// "2024-11-02" from the photo's EXIF DateTimeOriginal ("2024:11:02 10:42:00"), or nil.
    nonisolated static func takenDay(_ data: Data) -> String? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any] else { return nil }
        let exif = props[kCGImagePropertyExifDictionary] as? [CFString: Any]
        let tiff = props[kCGImagePropertyTIFFDictionary] as? [CFString: Any]
        let v = (exif?[kCGImagePropertyExifDateTimeOriginal] as? String) ?? (tiff?[kCGImagePropertyTIFFDateTime] as? String) ?? ""
        let parts = v.prefix(10).split(separator: ":").map(String.init)
        guard parts.count == 3, parts[0].count == 4, parts[1].count == 2, parts[2].count == 2 else { return nil }
        let day = parts[0] + "-" + parts[1] + "-" + parts[2]
        return day >= "2000-01-01" && day <= When.localDay() ? day : nil
    }

    /// Longest side 1600 px, JPEG quality 0.82, drawn upright (UIImage keeps the camera's orientation note).
    nonisolated static func shrink(_ data: Data, to out: URL, side: CGFloat = 1600) -> Bool {
        guard let img = UIImage(data: data) else { return false }
        let w = img.size.width, h = img.size.height
        guard w > 0, h > 0 else { return false }
        let k = min(1, side / max(w, h))
        let size = CGSize(width: max(1, (w * k).rounded()), height: max(1, (h * k).rounded()))
        let f = UIGraphicsImageRendererFormat()
        f.scale = 1
        f.opaque = true
        let small = UIGraphicsImageRenderer(size: size, format: f).image { _ in img.draw(in: CGRect(origin: .zero, size: size)) }
        guard let jpg = small.jpegData(compressionQuality: 0.82) else { return false }
        do { try jpg.write(to: out, options: .atomic); return true } catch { return false }
    }
}

/// The last whole per cent told, so progress isn't sent to the screen more often than it changes.
private final class Last { var value = -1 }

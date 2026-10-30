import Foundation
import UIKit
import SwiftUI

/// Demo mode (launch argument -demo): sample projects and a pretend server, used to check every
/// screen on the simulator without touching the real monday.com board.
enum Demo {
    static func arg(_ k: String) -> String? {
        let a = ProcessInfo.processInfo.arguments
        guard let i = a.firstIndex(of: k), i + 1 < a.count else { return nil }
        return a[i + 1]
    }

    private static func proj(_ id: String, _ t: String, _ gps: [Double]?, photo: Bool = false) -> JSON {
        var o: JSON = ["id": id, "title": t, "country": "Kenya", "ck": "ke", "area": "Eldoret", "type": "borehole", "status": "Completed", "pump": "manual", "done": "2025-04"]
        if let gps { o["gps"] = gps }
        return o
    }

    static func snapshot() -> JSON {
        let now = When.now()
        return [
            "ok": true, "who": "Reid Anderson", "admin": true, "reviewOpen": 1,
            "projects": [proj("101", "Chepkongi Dispensary", [0.5143, 35.2698]), proj("102", "Kiptoo Well", nil), proj("104", "Kapsaret Borehole", [0.47, 35.25]), proj("105", "Turbo Spring", [0.63, 35.05])] as [JSON],
            "repairs": ["104": ["state": "notworking", "by": "Bertin", "at": "2026-10-18T08:00:00Z", "note": "Handle loose", "urgency": "urgent", "t": "Kapsaret Borehole", "cost": 4500, "problems": ["Pump handle and pivot pins"]] as JSON] as JSON,
            "visits": ["101": ["at": now, "by": "Meril Kiprop", "state": "working"]] as JSON,
            "jobs": [String](), "assigned": [["aid": "a1", "id": "105", "t": "Turbo Spring", "date": "2026-10-24", "by": "Reid Anderson", "note": "Meet the committee"]], "confirmed": JSON(),
            "stories": ["101": ["st": "draft", "n": 6, "at": "2026-10-28T09:00:00Z", "b": 2, "i": 3, "a": 3, "c": "received"] as JSON,
                        "104": ["st": "collecting", "n": 3, "at": "2026-10-27T09:00:00Z", "b": 1, "i": 0, "a": 0, "c": "not-yet"] as JSON,
                        "105": ["st": "published", "n": 6, "at": "2026-10-01T09:00:00Z", "b": 2, "i": 2, "a": 3, "c": "received"] as JSON] as JSON,
        ]
    }

    static func setUp(_ p: Prefs, _ api: Api) {
        if arg("-signedin") != "no" { p.pin = "9999"; p.name = "Reid Anderson"; p.saveSnapshot(snapshot()) } else { p.pin = ""; p.session = "" }
        p.filter = "all"
        if arg("-bio") == "1" { p.bio = true; Bio.demoOk = false } else { p.bio = false }
        if let l = arg("-lang") { p.lang = l }
        if let th = arg("-theme") { p.theme = th == "auto" ? "" : th }
        p.mePane = arg("-pane") ?? "me"
        api.transport = { method, url, _, body in
            try? await Task.sleep(nanoseconds: 150_000_000)
            let b = body.flatMap { String(data: $0, encoding: .utf8) } ?? ""
            func j(_ o: Any) -> (Int, Data) { (200, (try? JSONSerialization.data(withJSONObject: o)) ?? Data()) }
            if url.contains("/api/story") {
                if method == "GET", let a = query(url, "asset") { return (200, photo(a)) }
                let id = method == "GET" ? (query(url, "id") ?? "101") : (b.contains("\"id\":\"104\"") ? "104" : b.contains("\"id\":\"105\"") ? "105" : "101")
                return j(["ok": true, "story": story(id), "photo": ["id": "9099"] as JSON] as JSON)
            }
            if url.contains("view=team") {
                return j(["ok": true, "team": [["name": "Meril Kiprop", "home": "ke", "admin": false, "source": "app", "lastSeen": "2026-10-19T09:00:00Z"],
                                               ["name": "Reid Anderson", "admin": true, "source": "cloudflare"]] as [JSON]] as JSON)
            }
            if url.contains("view=review") {
                return j(["ok": true, "items": [["rid": "r1", "kind": "story", "id": "101", "t": "Chepkongi Dispensary", "by": "Meril", "at": "2026-10-19T09:00:00Z", "status": "open",
                                                 "data": ["quote": "Now the nurses can wash their hands", "who": "Mary"]] as JSON] as [JSON]] as JSON)
            }
            if url.contains("view=assign") { return j(["ok": true, "items": [["aid": "a1", "id": "105", "t": "Turbo Spring", "to": "Meril Kiprop", "date": "2026-10-24", "note": "Meet the committee"]]] as JSON) }
            if url.contains("team=1") {
                return j(["ok": true, "team": [["name": "Meril Kiprop", "home": "ke", "phone": "0712 345 678", "lastSeen": When.now()], ["name": "Bertin Mwangi", "home": "tz", "phone": "0754 123 456", "lastSeen": "2026-10-18T08:00:00Z"],
                                               ["name": "Reid Anderson", "home": "ke", "phone": ""]] as [JSON]] as JSON)
            }
            if b.contains("monday-link") { return j(["ok": true, "name": "Reid Anderson"] as JSON) }
            if url.contains("pin-auth/token") { return j(["ok": true, "sid": "SESSION123456789012345", "name": "Reid Anderson"] as JSON) }
            if url.contains("activity=1") {
                return j(["ok": true, "activity": [["by": "Meril", "a": "pin", "id": "105", "t": "Turbo Spring", "at": "2026-10-12T10:00:00Z"],
                                                   ["by": "Meril", "a": "visit", "s": "working", "id": "101", "t": "Chepkongi Dispensary", "at": When.now()],
                                                   ["by": "Bertin", "a": "visit", "s": "notworking", "id": "104", "t": "Kapsaret Borehole", "at": "2026-10-18T08:00:00Z"]]] as JSON)
            }
            if url.contains("history=") {
                return j(["ok": true, "notes": [["id": "n1", "text": "Site visit: Working well. Water flowing, committee active.", "at": "2026-10-12T10:00:00Z", "by": "Meril Kiprop", "files": [Any]()] as JSON]] as JSON)
            }
            if url.contains("pin-auth/me") { return j(["ok": true, "available": true, "user": NSNull()] as JSON) }
            if url.contains("pin-auth/exchange") { return b.contains("\"good\"") ? j(["ok": true, "sid": "SESSION123456789012345"] as JSON) : (400, Data("{\"ok\":false,\"reason\":\"expired\"}".utf8)) }
            if url.contains("/api/place") { return j(["ok": true, "places": [["name": "Kiptoo Primary School", "where": "Nandi, Kenya", "lat": 0.2, "lng": 35.1] as JSON]] as JSON) }
            if b.contains("team-add") || b.contains("team-pin") { return j(["ok": true, "pin": "4821"] as JSON) }
            if url.contains("/api/pin-photo") { return j(["ok": true] as JSON) }
            if method == "POST" && url.hasSuffix("/api/pin") { return j(["ok": true, "updateId": "9001", "id": "106"] as JSON) }
            if method == "GET" && url.contains("/api/pin") { return j(snapshot()) }
            return j(["ok": true] as JSON)
        }
        DemoMe.setUp(p, api)
    }

    /// -screen office | office-repairs | me | report | route | project:104:visit | map | new | stories | story[:104] | story-photos | story-read
    @MainActor static func route(_ m: AppModel) {
        guard m.demo, let s = arg("-screen") else { return }
        let parts = s.split(separator: ":").map(String.init)
        if DemoMe.route(m, parts) { return }
        switch parts[0] {
        case "story", "story-photos":
            m.stack = [Place(tab: "stories"), Place(tab: "stories", project: parts.count > 1 ? parts[1] : "101", projectTab: "story")]
        case "story-read": m.stack = [Place(tab: "stories")]
        case "project": m.stack = [Place(tab: "list"), Place(tab: "list", project: parts.count > 1 ? parts[1] : "104", projectTab: parts.count > 2 ? parts[2] : "pin")]
        case "report": m.stack = [Place(tab: "me"), Place(tab: "me", sub: "report")]
        case "route":
            m.prefs.route = "105,101"
            m.stack = [Place(tab: "list"), Place(tab: "list", sub: "route")]
        default: m.stack = [Place(tab: parts[0])]
        }
        if let sec = arg("-section") { UserDefaults.standard.set(sec, forKey: "demo-section") }
    }

    // --- Stories ----------------------------------------------------------------------------------------------------

    private static func query(_ url: String, _ k: String) -> String? {
        URLComponents(string: url)?.queryItems?.first { $0.name == k }?.value
    }

    private static func ph(_ id: String, _ cap: String, kids: Bool = false, taken: String = "") -> JSON {
        ["id": id, "children": kids, "by": "Meril Kiprop", "at": "2026-10-20T09:00:00Z", "cap": cap, "taken": taken]
    }
    private static func pool(_ id: String, _ row: String, _ cap: String, kids: Bool = false) -> JSON { ["id": id, "row": row, "cap": cap, "children": kids] }

    /// A story as GET /api/story sends it: 101 drafted (with sections, photos and a cover), 104 still collecting,
    /// 105 on the website.
    static func story(_ id: String) -> JSON {
        let prompts: JSON = [:]
        if id == "104" {
            return ["status": "collecting", "consent": "not-yet", "prompts": prompts,
                    "answers": ["q1": "Women walked 6 km to the river every morning.", "q2": "Dirty water and long walks.", "q3": "Grace, 34, a farmer and mother of four."] as JSON,
                    "quote": "", "quoteBy": "", "credit": "", "text": "",
                    "photos": ["before": [ph("9011", "The old water point")], "progress": [JSON](), "after": [JSON]()] as JSON,
                    "missing": ["answers:3/6", "after-photos", "consent", "dates", "text"], "canApprove": false, "pool": [pool("9011", "before", "The old water point")],
                    "newPhotos": 0, "dates": JSON(), "cover": ""]
        }
        let answers: JSON = ["q1": "Before the borehole, the dispensary had no water of its own. Nurses carried jerrycans from a stream 2 km away.",
                             "q2": "Without clean water, the nurses couldn’t wash their hands between patients.",
                             "q3": "Mary Jepkosgei, 41, the nurse in charge.",
                             "q4": "The community dug the trenches and the committee collects a small fee for repairs.",
                             "q5": "Clean water at the tap, every day. Mothers now give birth at the dispensary.",
                             "q6": "The first morning the tap ran, Mary washed her hands and laughed."]
        let photos: JSON = [
            "before": [ph("9001", "The stream the nurses used", taken: "2024-11-03"), ph("9002", "", taken: "2024-11-03")],
            "progress": [ph("9003", "Drilling reached water", taken: "2025-01-12"), ph("9004", "", taken: "2025-02-02"), ph("9005", "Fitting the pump", taken: "2025-03-08")],
            "after": [ph("9006", "Mary at the new tap", taken: "2025-04-20"), ph("9007", "Children fetching water", kids: true, taken: "2025-04-20"), ph("9008", "")],
        ]
        let doc: JSON = [
            "title": "Clean hands at Chepkongi", "dek": "A borehole gave a small dispensary its own water, and the nurses a safer place to work.",
            "sections": [
                ["h": "Before", "body": "For years the nurses at Chepkongi carried jerrycans from a stream 2 km away.\n\nOn busy days there was not enough water to wash their hands between patients.", "photo": pool("9001", "before", "The stream the nurses used")] as JSON,
                ["h": "The work", "body": "The community dug the trenches while the drillers reached water at 60 metres. A hand pump was fitted in March.", "photo": pool("9003", "progress", "Drilling reached water")] as JSON,
                ["h": "Today", "body": "Clean water runs at the tap every day. Mothers now come to give birth at the dispensary.", "photo": pool("9006", "after", "Mary at the new tap")] as JSON,
            ] as [JSON],
            "pull": ["quote": "Now the nurses can wash their hands", "who": "Mary Jepkosgei, nurse in charge"] as JSON,
            "at": "2026-10-28T09:00:00Z", "by": "Meril Kiprop", "model": "openai:gpt",
        ]
        let live = id == "105"
        let status = live ? "published" : "draft"
        let missing: [String] = live ? [] : ["dates"]
        let newPhotos = live ? 0 : 1
        let all: [JSON] = [pool("9001", "before", "The stream the nurses used"), pool("9002", "before", ""), pool("9003", "progress", "Drilling reached water"),
                           pool("9005", "progress", "Fitting the pump"), pool("9006", "after", "Mary at the new tap"), pool("9007", "after", "Children fetching water", kids: true)]
        let dates: JSON = ["before": "2024-11", "progress": "2025-01", "progressEnd": "2025-03"]
        var o: JSON = ["status": status, "consent": "received", "answers": answers, "prompts": prompts]
        o["quote"] = "Now the nurses can wash their hands"; o["quoteBy"] = "Mary Jepkosgei"; o["credit"] = "Meril Kiprop"; o["text"] = "Clean hands at Chepkongi"
        o["photos"] = photos; o["missing"] = missing; o["canApprove"] = true; o["doc"] = doc
        o["pool"] = all; o["newPhotos"] = newPhotos; o["dates"] = dates; o["cover"] = "9006"
        if live { o["pub"] = ["at": "2026-10-01T09:00:00Z", "by": "Reid Anderson", "url": "https://rafikioutreach.org/stories/"] as JSON }
        return o
    }

    /// A made-up photo (sky, hills, a water tank) in colours that differ per asset, as JPEG.
    static func photo(_ asset: String, size: CGSize = CGSize(width: 480, height: 360)) -> Data {
        let n = asset.unicodeScalars.reduce(0) { $0 + Int($1.value) } % 6
        let skies: [UInt32] = [0x8FC9F0, 0xF2C48F, 0xA7D8E8, 0xC9B8F0, 0x9FE0C8, 0xF0A890]
        let lands: [UInt32] = [0x6DA34D, 0xB5895A, 0x4E8C6A, 0x8A9A4A, 0x7A6A4A, 0x5C8F3E]
        let f = UIGraphicsImageRendererFormat(); f.scale = 1
        let img = UIGraphicsImageRenderer(size: size, format: f).image { ctx in
            let c = ctx.cgContext
            UIColor(Color(hex: skies[n])).setFill(); c.fill(CGRect(origin: .zero, size: size))
            UIColor(Color(hex: 0xFFF3C4)).setFill(); c.fillEllipse(in: CGRect(x: size.width * 0.72, y: size.height * 0.12, width: 56, height: 56))
            UIColor(Color(hex: lands[n])).setFill()
            c.fill(CGRect(x: 0, y: size.height * 0.62, width: size.width, height: size.height * 0.38))
            UIColor(Color(hex: 0x3D5A73)).setFill()
            c.fill(CGRect(x: size.width * 0.22, y: size.height * 0.34, width: size.width * 0.16, height: size.height * 0.3))
            UIColor(Color(hex: 0x24394B)).setFill()
            c.fill(CGRect(x: size.width * 0.2, y: size.height * 0.31, width: size.width * 0.2, height: size.height * 0.05))
        }
        return img.jpegData(compressionQuality: 0.8) ?? Data()
    }

    /// Photos "chosen in the gallery" for the review and tray sheets.
    static func pickFiles() -> [String] {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("story-pick", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return ["d1", "d2", "d3"].compactMap { k -> String? in
            let f = dir.appendingPathComponent("demo-\(k).jpg")
            return (try? photo(k).write(to: f)) != nil ? f.path : nil
        }
    }

    /// Two photos on their way to After (one sending, one waiting for signal), for the story-photos screenshot.
    static func uploads(_ pid: String, _ dir: URL) -> [StoryUp] {
        ["u1", "u2"].enumerated().compactMap { pair -> StoryUp? in
            let i = pair.offset, k = pair.element
            let name = "demo-\(k).jpg"
            guard (try? photo(k).write(to: dir.appendingPathComponent(name))) != nil else { return nil }
            var u = StoryUp(key: "demo\(i)", project: pid, row: "after", children: false, cap: "", file: name, batch: "demo", taken: "")
            if i == 0 { u.state = "sending"; u.progress = 0.62 } else { u.state = "waiting" }
            return u
        }
    }
}

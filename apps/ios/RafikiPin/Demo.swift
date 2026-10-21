import Foundation

/// Demo mode (launch argument -demo): sample projects and a pretend server, used to check every
/// screen on the simulator without touching the real monday.com board.
enum Demo {
    static func arg(_ k: String) -> String? {
        let a = ProcessInfo.processInfo.arguments
        guard let i = a.firstIndex(of: k), i + 1 < a.count else { return nil }
        return a[i + 1]
    }

    private static func proj(_ id: String, _ t: String, _ gps: [Double]?, photo: Bool = false) -> JSON {
        var o: JSON = ["id": id, "title": t, "country": "Kenya", "ck": "ke", "area": "Eldoret", "type": "borehole", "status": "Completed", "pump": "manual"]
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
    }

    /// -screen office | office-repairs | me | report | route | project:104:visit | map | new
    @MainActor static func route(_ m: AppModel) {
        guard m.demo, let s = arg("-screen") else { return }
        let parts = s.split(separator: ":").map(String.init)
        switch parts[0] {
        case "project": m.stack = [Place(tab: "list"), Place(tab: "list", project: parts.count > 1 ? parts[1] : "104", projectTab: parts.count > 2 ? parts[2] : "pin")]
        case "report": m.stack = [Place(tab: "me"), Place(tab: "me", sub: "report")]
        case "route":
            m.prefs.route = "105,101"
            m.stack = [Place(tab: "list"), Place(tab: "list", sub: "route")]
        default: m.stack = [Place(tab: parts[0])]
        }
        if let sec = arg("-section") { UserDefaults.standard.set(sec, forKey: "demo-section") }
    }
}

import Foundation

/// Demo data for Office, Projects, Map and field tools (launch argument -demo): more projects of each kind, the
/// snapshot extras (people served, water tests, building stages, approvals) and answers for the Office views.
/// Hooked in by one line at the end of Demo.setUp.
enum DemoOffice {
    /// Only the screens of this group get the extra projects, so other screens look as before.
    static var mine: Bool {
        let s = Demo.arg("-screen") ?? ""
        return ["map", "office", "project", "list", "projects", "new"].contains { s.hasPrefix($0) }
    }

    private static func proj(_ id: String, _ t: String, _ type: String, _ gps: [Double]?, status: String = "Completed", ck: String = "ke", area: String = "Eldoret", people: Int = 0) -> JSON {
        var o: JSON = ["id": id, "title": t, "country": countryName(ck) ?? "Kenya", "ck": ck, "area": area, "type": type, "status": status, "pump": "manual", "done": "2024-06"]
        if let gps { o["gps"] = gps }
        if people > 0 { o["people"] = people }
        return o
    }

    static func extend(_ s: JSON) -> JSON {
        guard mine else { return s }
        var o = s
        var ps = (o["projects"] as? [JSON]) ?? []
        ps += [
            proj("106", "Kiptoo Primary School Classrooms", "School", [0.552, 35.301], status: "Under construction", area: "Kiptoo"),
            proj("107", "Mosoriot Health Centre Water Tank", "Health Centre", [0.318, 35.172], area: "Mosoriot", people: 2400),
            proj("108", "Hope Children’s Home Kitchen", "Orphanage", [0.497, 35.312], area: "Langas", people: 60),
            proj("109", "Ziwa Women’s Farm Irrigation", "Farm", [0.781, 35.122], area: "Ziwa", people: 120),
            proj("110", "Kapseret Market Borehole", "Borehole", [0.468, 35.262], area: "Kapseret", people: 900),
            proj("111", "Kimumu Spring Collection", "Rain Water Collection", [0.552, 35.272], area: "Kimumu", people: 350),
            proj("112", "Moiben Borehole", "Borehole", [0.811, 35.385], area: "Moiben", people: 1100),
            proj("113", "Arusha Road Borehole", "Borehole", [-3.36, 36.68], ck: "tz", area: "Arusha", people: 1500),
            proj("114", "Gulu Girls School Borehole", "Borehole", [2.78, 32.3], ck: "ug", area: "Gulu", people: 800),
            proj("115", "Lilongwe Clinic Borehole", "Borehole", nil, ck: "mw", area: "Lilongwe"),
        ]
        o["projects"] = ps
        var reps = (o["repairs"] as? JSON) ?? [:]
        if var r = reps["104"] as? JSON {
            r["approval"] = ["status": "pending", "rid": "ap1", "by": "Bertin Mwangi", "at": "2026-10-20T08:00:00Z", "est": 65000] as JSON
            r["step"] = "parts"; r["budget"] = 65000; r["assignee"] = "Bertin Mwangi"; r["due"] = "2026-11-05"
            r["stepAt"] = ["reported": "2026-10-18T08:00:00Z", "assigned": "2026-10-19T08:00:00Z", "parts": "2026-10-20T08:00:00Z"] as JSON
            reps["104"] = r
        }
        reps["112"] = ["state": "needsrepair", "by": "Meril", "at": "2026-10-25T08:00:00Z", "note": "Slow flow", "urgency": "soon", "t": "Moiben Borehole", "problems": ["Low flow"], "step": "reported"] as JSON
        o["repairs"] = reps
        var vis = (o["visits"] as? JSON) ?? [:]
        vis["110"] = ["at": "2026-09-01T09:00:00Z", "by": "Meril Kiprop", "state": "working"] as JSON
        vis["107"] = ["at": "2026-10-02T09:00:00Z", "by": "Bertin Mwangi", "state": "working"] as JSON
        o["visits"] = vis
        o["approveAbove"] = 50000
        o["tests"] = ["110": "2026-02-01T09:00:00Z", "101": "2026-09-20T09:00:00Z"] as JSON
        o["stages"] = ["106": ["s": "walls", "at": "2026-10-21T09:00:00Z", "by": "Bertin Mwangi", "pct": 45, "due": "2026-12-15", "t": "Kiptoo Primary School Classrooms", "n": 4] as JSON] as JSON
        o["people"] = ["total": 9230, "counted": 9, "measured": 6, "byCountry": ["ke": 6930, "tz": 1500, "ug": 800] as JSON, "byType": ["water": 7650, "health": 2400, "home": 60, "farm": 120] as JSON,
                       "trend": (1...12).map { i -> JSON in ["m": String(format: "2025-%02d", i), "n": i < 3 ? NSNull() as Any : (6000 + i * 270) as Any] }] as JSON
        o["me"] = ["home": "ke"] as JSON
        return o
    }

    static func costs() -> JSON {
        let rows: [JSON] = [
            ["id": "104", "t": "Kapsaret Borehole", "at": "2026-10-20T08:00:00Z", "rid": "r104", "what": "Pump handle and pivot pins", "est": 65000, "actual": 0, "by": "Bertin Mwangi", "step": "parts", "receipt": false],
            ["id": "101", "t": "Chepkongi Dispensary", "at": "2026-09-12T08:00:00Z", "rid": "r101", "what": "Tap washers", "est": 3000, "actual": 2800, "by": "Meril Kiprop", "step": "fixed", "receipt": true],
            ["id": "110", "t": "Kapseret Market Borehole", "at": "2026-08-02T08:00:00Z", "rid": "r110", "what": "Rising main pipe", "est": 18000, "actual": 21500, "by": "Meril Kiprop", "step": "fixed", "receipt": true],
            ["id": "113", "t": "Arusha Road Borehole", "at": "2026-06-15T08:00:00Z", "rid": "r113", "what": "Cylinder seals", "est": NSNull(), "actual": 9000, "by": "Bertin Mwangi", "step": "fixed", "receipt": false],
        ]
        return ["ok": true, "approveAbove": 50000, "rows": rows,
                "stats": ["month": 2800, "year": 33300, "sum12": 33300, "repairs": 4, "avg": 11100, "yearKey": 2026, "byCountry": ["ke": 24300, "tz": 9000] as JSON,
                          "top": [["id": "110", "t": "Kapseret Market Borehole", "ck": "ke", "sum": 21500, "n": 1], ["id": "113", "t": "Arusha Road Borehole", "ck": "tz", "sum": 9000, "n": 1]] as [JSON]] as JSON]
    }

    static func teammap() -> JSON {
        ["ok": true, "people": [["name": "Meril Kiprop", "lat": 0.52, "lng": 35.27, "acc": 12, "at": When.now(), "phone": "0712 345 678", "home": "ke"],
                                ["name": "Bertin Mwangi", "lat": -3.37, "lng": 36.69, "acc": 30, "at": "2026-10-31T07:40:00Z", "phone": "0754 123 456", "home": "tz"]] as [JSON],
         "visits": [["id": "101", "by": "Meril Kiprop", "at": When.now(), "s": "working", "t": "Chepkongi Dispensary"]] as [JSON]]
    }

    static func plan() -> JSON {
        ["ok": true, "total": 6,
         "days": [["stops": [["id": "110", "why": ["due"], "age": 60], ["id": "111", "why": ["never"]], ["id": "106", "why": ["build"]]] as [JSON]],
                  ["stops": [["id": "112", "why": ["repair"], "age": 6], ["id": "109", "why": ["due"], "age": 400]] as [JSON]]] as [JSON],
         "spare": [["id": "108", "why": ["due"], "age": 200]] as [JSON], "nogps": [["id": "115", "t": "Lilongwe Clinic Borehole"]] as [JSON]]
    }

    static func donors() -> JSON {
        ["ok": true, "mail": ["ready": true] as JSON,
         "donors": [["id": "d1", "name": "Grace Church Leeds", "email": "giving@example.org", "org": "Grace Church", "projects": ["101", "104", "110"], "showPhotos": true, "digest": true,
                     "lastOpen": "2026-10-28T19:00:00Z", "opens": 7, "viewers": [["vid": "v1", "name": "Anne Shaw", "email": "anne@example.org", "primary": true, "lastOpen": "2026-10-28T19:00:00Z"]] as [JSON]],
                    ["id": "d2", "name": "The Kamau Family", "email": "", "org": "", "projects": ["106"], "opens": 0],
                    ["id": "d3", "name": "Rotary Club Nakuru", "email": "rotary@example.org", "projects": ["107"], "revoked": true, "opens": 2]] as [JSON]]
    }

    static func sms() -> JSON {
        ["ok": true,
         "reports": [["at": When.now(), "from": "+254712000111", "id": "110", "t": "Kapseret Market Borehole", "text": "110 BROKEN handle loose", "result": "broken", "ch": "sms"],
                     ["at": "2026-10-29T10:00:00Z", "from": "+254733000222", "id": "107", "t": "Mosoriot Health Centre Water Tank", "text": "107 OK", "result": "working", "ch": "whatsapp"],
                     ["at": "2026-10-27T10:00:00Z", "from": "+254700000333", "text": "hello", "result": "ignored", "ch": "sms"]] as [JSON],
         "phones": [["phone": "+254712000111", "name": "Joseph (chair)", "t": "Kapseret Market Borehole", "id": "110"]] as [JSON],
         "urls": ["at": "https://example.org/sms", "twilio": "https://example.org/twilio"] as JSON]
    }

    static func last(_ id: String) -> JSON {
        var o: JSON = ["ok": true, "visits": [["at": "2026-10-12T10:00:00Z", "by": "Meril Kiprop", "state": "working", "note": "Water flowing"]] as [JSON]]
        if id == "104" || id == "110" { o["costs"] = ["sum": id == "104" ? 0 : 21500, "n": 1, "last": [["at": "2026-08-02T08:00:00Z", "what": "Rising main pipe", "actual": 21500]] as [JSON]] as JSON }
        if id == "106" {
            o["stage"] = ["id": "106", "stage": "walls", "at": "2026-10-21T09:00:00Z", "by": "Bertin Mwangi", "pct": 45, "due": "2026-12-15", "behind": false,
                          "hist": [["stage": "site", "at": "2026-07-01T09:00:00Z", "by": "Bertin Mwangi", "note": "Site cleared", "photos": [String]()],
                                   ["stage": "foundation", "at": "2026-08-10T09:00:00Z", "by": "Bertin Mwangi", "note": "Foundation poured", "photos": [String]()],
                                   ["stage": "walls", "at": "2026-10-21T09:00:00Z", "by": "Bertin Mwangi", "note": "Walls at window height", "photos": [String]()]] as [JSON]] as JSON
        }
        return o
    }

    /// Wraps the demo server: answers this group's calls, passes the rest on.
    static func setUp(_ p: Prefs, _ api: Api) {
        if Demo.arg("-signedin") != "no" { p.saveSnapshot(extend(Demo.snapshot())) }
        let base = api.transport
        api.transport = { method, url, headers, body in
            func j(_ o: JSON) -> (Int, Data) { (200, (try? JSONSerialization.data(withJSONObject: o)) ?? Data()) }
            if url.contains("view=costs") { return j(costs()) }
            if url.contains("view=teammap") { return j(teammap()) }
            if url.contains("view=plan") { return j(plan()) }
            if url.contains("view=donors") { return j(donors()) }
            if url.contains("/api/sms-admin") && method == "GET" { return j(sms()) }
            if url.contains("/api/fx") { return j(["ok": true, "rates": ["KES": 1, "USD": 0.0077, "GBP": 0.0059, "EUR": 0.0071, "TZS": 20.6, "UGX": 28.4, "MWK": 13.4] as JSON] as JSON) }
            if url.contains("/api/pin-thanks") { return j(["ok": true] as JSON) }
            if method == "POST" && (body.flatMap { String(data: $0, encoding: .utf8) } ?? "").contains("donor-") { return j(["ok": true, "link": "https://pin.rafikioutreach.org/d#demo-link"] as JSON) }
            if method == "GET", let r = url.range(of: "/api/pin?id=") { return j(last(String(url[r.upperBound...]))) }
            if method == "GET" && url.hasSuffix("/api/pin") && mine { return j(extend(Demo.snapshot())) }
            return await base(method, url, headers, body)
        }
    }
}

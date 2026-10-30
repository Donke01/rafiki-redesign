import Foundation
import UIKit

/// Demo mode for Chat and Trips: a pretend /api/chat, /api/itin, /api/vehicles, /api/fuel and /api/group-trips in front of
/// Demo's own, and the `-screen` places for screenshots:
///   chat · chat:<id> · chatinfo:<id> · chatarch · chatstar · chatsettings · chatnew
///   trip:<id> · trip_new · gtrip:<id> · gtrip_new · gview:<id>   (Office › Trips: -screen office -section trips; Me › My trips: -screen me)
/// Sheets: -sheet msg|select|find|attach|live|contact|rec|fwd|clear|add|media|date|mute (in a chat), bubble|wall|leave|edit|link (chat
/// info), search (the list, with -q), group (new chat); trips: tab-costs|tab-pack|tab-share|stop|skip|replan|night|road|fuel|extra|team|chat.
enum DemoChat {
    private static var installed = false
    private static var routed = false

    static func install(_ api: Api) {
        guard !installed else { return }
        installed = true
        let base = api.transport
        api.transport = { method, url, headers, body in
            if let r = answer(method, url, body) {
                try? await Task.sleep(nanoseconds: 120_000_000)
                return r
            }
            return await base(method, url, headers, body)
        }
    }

    @MainActor static func route(_ m: AppModel) {
        guard m.demo, !routed, let s = Demo.arg("-screen") else { return }
        routed = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
            let tab = s.hasPrefix("trip") || s.hasPrefix("gtrip") || s.hasPrefix("gview") ? "office" : "list"
            func p(_ x: String) -> Place { Place(tab: tab, sub: x) }
            if s == "chat" { m.stack = [Place(tab: tab), p("chat")] }
            else if s.hasPrefix("chat:") { m.stack = [Place(tab: tab), p("chat"), p(s)] }
            else if s.hasPrefix("chatinfo:") { m.stack = [Place(tab: tab), p("chat"), p("chat:" + s.dropFirst(9)), p(s)] }
            else if ["chatarch", "chatstar", "chatsettings", "chatnew"].contains(s) { m.stack = [Place(tab: tab), p("chat"), p(s)] }
            else if ChatRoutes.handles(s) { m.stack = [Place(tab: tab), p(s)] }
        }
    }

    // MARK: the pretend server

    private static func j(_ o: Any) -> (Int, Data) { (200, (try? JSONSerialization.data(withJSONObject: o)) ?? Data()) }
    private static func q(_ url: String, _ k: String) -> String? { URLComponents(string: url)?.queryItems?.first { $0.name == k }?.value }

    static func answer(_ method: String, _ url: String, _ body: Data?) -> (Int, Data)? {
        let b = body.flatMap { (try? JSONSerialization.jsonObject(with: $0)) as? JSON } ?? [:]
        if url.contains("/api/chat/media/") { return (200, Demo.photo(String(url.split(separator: "/").last ?? "x"))) }
        if url.contains("/api/chat/") {
            let path = String(url.components(separatedBy: "/api/chat/").last?.split(separator: "?").first ?? "")
            switch path {
            case "list": return j(["ok": true, "me": "Reid Anderson", "chats": chats(), "presence": presence(), "now": When.now(), "tools": ["translate": true, "transcribe": true] as JSON] as JSON)
            case "msgs": return j(["ok": true, "msgs": msgs(q(url, "c") ?? ""), "more": false] as JSON)
            case "send":
                var o = b; o["id"] = "\(Int(Date().timeIntervalSince1970))"; o["from"] = "Reid Anderson"; o["at"] = When.now(); o["status"] = "sent"
                return j(["ok": true, "msg": o] as JSON)
            case "media": return j(["ok": true, "id": "m\(Int.random(in: 100000...999999))abcdefghijklmnop"] as JSON)
            case "starred": return j(["ok": true, "msgs": msgs("d:meril").filter { ($0["starred"] as? Bool) == true }] as JSON)
            case "info": return j(["ok": true, "read": [["name": "Meril Kiprop", "at": iso(-40)]], "delivered": [["name": "Bertin Mwangi", "at": iso(-55)]]] as JSON)
            case "search": return j(["ok": true, "hits": [["c": "g:kitale", "id": "k3", "from": "Bertin Mwangi", "at": iso(-90), "snippet": "The pump handle is loose again"] as JSON]] as JSON)
            case "link": return j(["ok": true, "url": "https://pin.rafikioutreach.org/pin#join=Kx7Pq2demo"] as JSON)
            case "ticket": return (503, Data("{\"ok\":false}".utf8))
            case "trip": return j(["ok": true, "trip": card()] as JSON)
            default: return j(["ok": true] as JSON)
            }
        }
        if url.contains("/api/translate") { return j(["ok": true, "text": "The pump is working well now, thank you."] as JSON) }
        if url.contains("/api/itin") {
            if url.contains("?card=1") { return j(["ok": true, "card": card()] as JSON) }
            if url.contains("alerts=1") { return j(["ok": true, "alerts": [["kind": "late", "id": "t1", "title": "Moiben repairs", "by": "Meril Kiprop", "at": iso(-20), "en": "Running 40 min late at Kapsaret Borehole", "sw": "Wamechelewa dakika 40 Kapsaret Borehole"] as JSON]] as JSON) }
            if url.contains("/api/itin/optimise") { return j(["ok": true, "plan": itin("new", status: "planned")] as JSON) }
            if url.contains("/api/itin/suggest") {
                return j(["ok": true, "items": [["id": "104", "name": "Kapsaret Borehole", "area": "Eldoret", "lat": 0.47, "lng": 35.25, "why": [["en": "open repair · urgent", "sw": "matengenezo yaliyo wazi · ya dharura"]]] as JSON,
                                                ["id": "105", "name": "Turbo Spring", "area": "Eldoret", "lat": 0.63, "lng": 35.05, "why": [["en": "due a visit · 7 months", "sw": "Ziara inahitajika · miezi 7"]]] as JSON]] as JSON)
            }
            if method == "POST" && url.hasSuffix("/api/itin") { return j(["ok": true, "itin": itin("t2", status: "planned")] as JSON) }
            if url.contains("/api/itin/"), let id = url.components(separatedBy: "/api/itin/").last?.split(separator: "?").first, !id.isEmpty { return j(["ok": true, "itin": itin(String(id)), "can": ["edit": true]] as JSON) }
            return j(["ok": true, "items": [["id": "t1", "title": "Moiben repairs", "from": day(0), "to": day(1), "status": "active", "owner": "Reid Anderson", "people": ["Reid Anderson", "Meril Kiprop"], "projects": ["101", "104", "105"]] as JSON,
                                            ["id": "t3", "title": "Turbo water tests", "from": day(6), "to": day(6), "status": "planned", "owner": "Meril Kiprop", "people": ["Meril Kiprop"], "projects": ["105"]] as JSON]] as JSON)
        }
        if url.contains("/api/vehicles") { return j(["ok": true, "vehicles": [["vid": "v1", "name": "Hilux", "plate": "KDA 123X", "type": "pickup", "cc": 2400, "fuel": "diesel"] as JSON]] as JSON) }
        if url.contains("/api/fuel") {
            if method == "GET" { return j(["ok": true, "logs": [["lid": "l1", "at": iso(-180), "by": "Meril Kiprop", "litres": 40, "kesPerL": 171.47, "totalKes": 6859, "odometer": 84210, "town": "Eldoret"] as JSON]] as JSON) }
            return j(["ok": true, "log": ["lid": "l2"]] as JSON)
        }
        if url.contains("/api/trip/week") { return j(["ok": true, "bestDay": ["date": day(1), "roadLevel": "dry"]] as JSON) }
        if url.contains("/api/road-reports") { return j(["ok": true] as JSON) }
        if url.contains("/api/group-trips") {
            if url.hasSuffix("/api/group-trips") && method == "GET" {
                return j(["ok": true, "items": [["id": "g1", "name": "Kenya water trip, July 2027", "from": "2027-07-04", "to": "2027-07-14", "country": "ke", "participants": 8, "public": true] as JSON]] as JSON)
            }
            return j(["ok": true, "trip": group()] as JSON)
        }
        return nil
    }

    private static func iso(_ minutes: Double) -> String { When.iso.string(from: Date().addingTimeInterval(minutes * 60)) }
    private static func day(_ d: Int) -> String { When.localDay(Date().addingTimeInterval(Double(d) * 86_400)) }

    private static func presence() -> JSON {
        ["Meril Kiprop": ["online": true, "seen": When.now()] as JSON, "Bertin Mwangi": ["online": false, "seen": iso(-75)] as JSON]
    }
    static func chats() -> [JSON] {
        func last(_ id: String, _ from: String, _ text: String, _ min: Double, kind: String = "text", sys: String = "") -> JSON { ["id": id, "from": from, "kind": kind, "text": text, "at": iso(min), "sys": sys] }
        return [
            ["id": "t:news", "kind": "team", "title": "Announcements", "announce": true, "onlyAdmins": true, "admins": ["Reid Anderson"], "members": ["Reid Anderson", "Meril Kiprop", "Bertin Mwangi"],
             "last": last("n1", "Reid Anderson", "Team meeting moved to Friday 9:00", -300)],
            ["id": "d:meril", "kind": "dm", "title": "Meril Kiprop", "members": ["Reid Anderson", "Meril Kiprop"], "unread": 1, "last": last("m9", "Meril Kiprop", "On my way to Kapsaret now", -3), "bubble": ""],
            ["id": "g:kitale", "kind": "group", "title": "Kitale repairs", "members": ["Reid Anderson", "Meril Kiprop", "Bertin Mwangi"], "admins": ["Reid Anderson"], "unread": 3, "mention": true, "pinned": true,
             "description": "Repairs around Kitale and Eldoret", "seen": ["Meril Kiprop"], "pins": [["id": "k2", "from": "Reid Anderson", "text": "Parts budget this month: KES 40,000"]],
             "last": last("k5", "Bertin Mwangi", "@Reid Anderson can you approve the parts?", -12)],
            ["id": "p:104", "kind": "project", "title": "Kapsaret Borehole", "project": "104", "members": ["Reid Anderson", "Bertin Mwangi"], "last": last("p2", "Bertin Mwangi", "", -60 * 26, kind: "sys", sys: "repair")],
            ["id": "t:ke", "kind": "team", "title": "Kenya team", "country": "ke", "members": ["Reid Anderson", "Meril Kiprop", "Bertin Mwangi"], "muted": "always",
             "last": last("e1", "Meril Kiprop", "Good morning team 🌞", -60 * 30)],
            ["id": "d:bertin", "kind": "dm", "title": "Bertin Mwangi", "members": ["Reid Anderson", "Bertin Mwangi"], "archived": true, "last": last("b1", "Reid Anderson", "Thanks!", -60 * 72)],
        ]
    }
    static func msgs(_ c: String) -> [JSON] {
        func m(_ id: String, _ from: String, _ text: String, _ min: Double, _ extra: JSON = [:]) -> JSON {
            var o: JSON = ["id": id, "c": c, "from": from, "text": text, "at": iso(min), "kind": "text", "status": "read"]
            extra.forEach { o[$0] = $1 }
            return o
        }
        switch c {
        case "d:meril":
            return [
                m("m1", "Meril Kiprop", "Habari! Pampu ya Chepkongi inafanya kazi vizuri sasa.", -60 * 26),
                m("m2", "Reid Anderson", "Great news, thank you 🙏", -60 * 25.8, ["reacts": ["👍": ["Meril Kiprop"]] as JSON]),
                m("m3", "Meril Kiprop", "", -60 * 25.5, ["kind": "media", "media": [["id": "demo9006abcdefghijk", "type": "photo", "w": 480, "h": 360]] as [JSON], "text": "The new tap"]),
                m("m4", "Meril Kiprop", "", -60 * 25.4, ["kind": "voice", "media": [["id": "demo9100abcdefghijk", "type": "voice", "dur": 12.0, "wave": [10, 40, 70, 30, 90, 50, 20, 60, 80, 40, 30, 70, 50, 20, 60, 40]] as JSON] as [JSON]]),
                m("m5", "Reid Anderson", "Can you check Kapsaret on the way back?", -40, ["status": "read", "starred": true]),
                m("m6", "Meril Kiprop", "Yes, I'll be there before lunch", -38, ["reply": ["id": "m5", "from": "Reid Anderson", "kind": "text", "text": "Can you check Kapsaret on the way back?"] as JSON]),
                m("m7", "Meril Kiprop", "", -20, ["kind": "location", "loc": ["lat": 0.5143, "lng": 35.2698] as JSON]),
                m("m8", "Reid Anderson", "", -10, ["kind": "trip", "trip": card(), "status": "delivered"]),
                m("m9", "Meril Kiprop", "On my way to Kapsaret now", -3),
                m("m10", "Reid Anderson", "👍", -2, ["status": "sent"]),
            ]
        case "g:kitale":
            return [
                m("k1", "Reid Anderson", "", -60 * 50, ["kind": "sys", "sys": ["type": "created", "by": "Reid Anderson"] as JSON]),
                m("k2", "Reid Anderson", "Parts budget this month: KES 40,000", -60 * 49),
                m("k3", "Bertin Mwangi", "The pump handle is loose again at Kapsaret. I’ll need new pivot pins.", -90),
                m("k4", "Meril Kiprop", "I can bring them tomorrow from Eldoret", -60, ["fwd": false]),
                m("k5", "Bertin Mwangi", "@Reid Anderson can you approve the parts?", -12, ["mentions": ["Reid Anderson"]]),
            ]
        case "p:104":
            return [
                m("p1", "Bertin Mwangi", "", -60 * 26, ["kind": "sys", "sys": ["type": "repair", "project": "104", "by": "Bertin Mwangi"] as JSON]),
                m("p2", "Bertin Mwangi", "Handle loose, committee says it started last week. Photos on the visit.", -60 * 25.9),
            ]
        case "t:news":
            return [m("n1", "Reid Anderson", "Team meeting moved to Friday 9:00. Bring your monthly numbers 📊", -300)]
        default: return []
        }
    }
    static func card() -> JSON {
        ["id": "t1", "title": "Moiben repairs", "from": day(0), "to": day(1), "status": "active", "next": ["name": "Kapsaret Borehole", "date": day(0)] as JSON, "done": 1, "total": 3, "access": true]
    }

    /// A two-day field trip: Chepkongi done, Kapsaret next (a loose handle), Turbo tomorrow.
    static func itin(_ id: String, status: String = "active") -> JSON {
        let stops: [JSON] = [
            ["sid": "s1", "id": "101", "name": "Chepkongi Dispensary", "lat": 0.5143, "lng": 35.2698, "type": "health", "visitMin": 45, "day": 0, "status": status == "active" ? "done" : "planned",
             "checkin": status == "active" ? ["at": iso(-150), "by": "Meril Kiprop"] as JSON as Any : NSNull() as Any, "window": ["open": "08:00", "close": "17:00"] as JSON],
            ["sid": "s2", "id": "104", "name": "Kapsaret Borehole", "lat": 0.47, "lng": 35.25, "type": "borehole", "visitMin": 90, "day": 0, "status": "planned",
             "why": [["en": "open repair · urgent", "sw": "matengenezo yaliyo wazi · ya dharura"]], "fixed": "11:00", "locked": true],
            ["sid": "s3", "id": "105", "name": "Turbo Spring", "lat": 0.63, "lng": 35.05, "type": "school", "visitMin": 60, "day": 1, "status": "planned", "window": ["open": "08:00", "close": "15:30"] as JSON],
        ]
        let legs: JSON = [
            "S>s1": ["km": 6.2, "min": 15, "src": "trip", "chips": ["surface": ["en": "6 km tarmac", "sw": "km 6 lami"], "time": ["en": "~15 min", "sw": "~dak 15"]] as JSON] as JSON,
            "s1>s2": ["km": 9.8, "min": 28, "src": "trip", "riskLevel": "caution", "chips": ["surface": ["en": "4 km tarmac · 6 km murram", "sw": "km 4 lami · km 6 murram"], "rain": ["en": "Rain 12 mm", "sw": "Mvua mm 12"], "time": ["en": "~28 min", "sw": "~dak 28"]] as JSON, "roadLevel": "wet"] as JSON,
            "s2>T:Eldoret": ["km": 18, "min": 30, "src": "trip"] as JSON,
            "T:Eldoret>s3": ["km": 42, "min": 55, "src": "trip"] as JSON,
            "s3>E": ["km": 45, "min": 60, "src": "trip"] as JSON,
        ]
        let days: [JSON] = [
            ["date": day(0), "stops": ["s1", "s2"], "km": 34, "driveMin": 73, "riskLevel": "caution", "overnight": ["town": "Eldoret", "suggested": true, "why": ["en": "near tomorrow's first stop", "sw": "karibu na kituo cha kwanza cha kesho"], "lat": 0.514, "lng": 35.27, "lodging": ["name": "Kerio View Hotel", "kes": 6500] as JSON] as JSON,
             "warnings": [["code": "ROAD_WET", "level": "caution", "leg": "s1>s2", "en": "Murram near Kapsaret may be slippery after rain", "sw": "Murram karibu na Kapsaret inaweza kuteleza baada ya mvua"]] as [JSON]],
            ["date": day(1), "stops": ["s3"], "km": 87, "driveMin": 115, "riskLevel": "ok", "warnings": [JSON]()],
        ]
        let cost: JSON = ["fuel": ["label": ["en": "Diesel KES 171.47/L · Eldoret, EPRA 15 Oct–14 Nov", "sw": "Dizeli KES 171.47/L · Eldoret, EPRA 15 Okt–14 Nov"], "kes": 2650, "litres": 15.4, "lPer100": 12.7,
                                    "basis": ["en": "Hilux · KDA 123X · Pickup, from 6 fill-ups", "sw": "Hilux · KDA 123X · Gari la mizigo, kutoka ujazaji 6"]] as JSON,
                          "est": ["totalKes": 15650, "fuelKes": 2650, "lunchKes": 1500, "perDiemKes": 4000, "lodgingKes": 6500, "extrasKes": 1000] as JSON,
                          "actual": ["totalKes": 6859, "fuelKes": 6859] as JSON,
                          "days": [["date": day(0), "km": 34, "totalKes": 9800] as JSON, ["date": day(1), "km": 87, "totalKes": 5850] as JSON]]
        let packing: [JSON] = [["key": "auto:pins", "en": "Pivot pins and handle bolts (Kapsaret)", "sw": "Pini na boliti za mkono (Kapsaret)", "auto": true, "done": true],
                               ["key": "std:tests", "en": "Water test strips", "sw": "Vipande vya kupima maji", "auto": true],
                               ["key": "std:offline", "en": "Offline map saved", "sw": "Ramani ya nje ya mtandao imehifadhiwa", "auto": true],
                               ["key": "u:1", "en": "Spare tyre checked", "sw": "Spare tyre checked"]]
        return ["id": id, "v": 3, "title": id == "new" ? "Trip to Eldoret" : "Moiben repairs", "status": status, "owner": "Reid Anderson", "people": ["Reid Anderson", "Meril Kiprop"],
                "ck": "ke", "from": day(0), "to": day(1), "start": ["kind": "office", "name": "Eldoret office", "lat": 0.514, "lng": 35.27] as JSON, "end": ["kind": "same"] as JSON,
                "hours": ["start": "07:30", "end": "17:30"] as JSON, "lunch": ["at": "12:30", "min": 45] as JSON, "vehicleId": "v1", "stops": stops, "days": days, "unplanned": [String](),
                "legs": legs, "cost": cost, "packing": packing, "extras": [["xid": "x1", "kind": "parking", "label": "Parking Eldoret", "kes": 200, "actual": true] as JSON],
                "warnings": [JSON](), "share": ["url": ""] as JSON]
    }

    static func group() -> JSON {
        ["id": "g1", "v": 2, "name": "Kenya water trip, July 2027", "from": "2027-07-04", "to": "2027-07-14", "country": "ke", "destination": "Kitale, Trans Nzoia", "public": true,
         "leaders": ["Reid Anderson"], "summary": "Ten days with the drilling team and the schools we work with.",
         "participants": [["pid": "a1", "name": "Sarah Johnson", "role": "donor", "email": "sarah@example.org", "diet": "vegetarian", "passportReminder": true, "passportExpiry": "2027-02"] as JSON,
                          ["pid": "a2", "name": "Tom Baker", "role": "volunteer", "email": "tom@example.org", "medical": "asthma"] as JSON],
         "items": [["iid": "i1", "date": "2027-07-04", "start": "07:40", "end": "08:55", "kind": "flight", "title": "Nairobi to Eldoret", "flight": ["number": "KQ 2201", "from": "NBO", "to": "EDL"] as JSON] as JSON,
                   ["iid": "i2", "date": "2027-07-04", "start": "10:00", "kind": "lodging", "title": "Check in", "lodging": ["name": "Kitale Country Club"] as JSON] as JSON,
                   ["iid": "i3", "date": "2027-07-05", "start": "08:00", "end": "16:00", "kind": "project", "title": "Drilling day at Turbo", "location": ["name": "Turbo Spring"] as JSON] as JSON],
         "costs": [["cid": "c1", "label": "Lodging, 10 nights", "amount": 900, "currency": "USD", "per": "person"] as JSON],
         "payments": [["payid": "y1", "pid": "a1", "amount": 450, "currency": "USD", "at": "2027-03-01"] as JSON],
         "totals": ["USD": 1800, "KES": 232200] as JSON,
         "packing": TripGroup.defaultPacking, "expect": TripGroup.defaultExpect, "emergency": [["name": "Reid Anderson", "role": "Leader", "phone": "+254 712 000 000"] as JSON],
         "share": ["url": "https://pin.rafikioutreach.org/trip#g1demo"] as JSON]
    }
}

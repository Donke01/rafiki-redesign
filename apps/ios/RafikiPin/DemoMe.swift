import Foundation
import SwiftUI
import UIKit

/// Demo mode for the Me area: profile photos, the team list with roles, notifications, the app invite and an iPhone
/// update, so every new screen can be checked on the simulator.
///   -screen notes | settings:<page> | welcome | hello | update   (page: profile, pin, notify, monday, help, signout, look, lang, units, about)
///   -sheet contact | contact-me | contact-new   -sub invite | newpin | setpin | block | admin | photo | name | phone | jobs
///   -hint 1 (the "Me is here now" bubble)   -kind app|cloudflare|monday|shared (how "you" sign in)
enum DemoMe {
    static let photos: [String: String] = [
        "Meril Kiprop": "/api/team-photo?name=Meril%20Kiprop&v=1",
        "Grace Wanjiru": "/api/team-photo?name=Grace%20Wanjiru&v=1",
    ]

    static func me() -> JSON { ["kind": Demo.arg("-kind") ?? "app", "nick": "Reid", "phone": "0712 000 111", "home": "ke", "photo": ""] }

    static func setUp(_ p: Prefs, _ api: Api) {
        let screen = Demo.arg("-screen") ?? ""
        p.onb = screen != "welcome"
        p.meHint = false
        p.noteSeen = ""; p.noteRead = []; p.reviewN = 0; p.noteOff = []; p.phoneAlerts = true
        p.updLater = 0; p.updLaterBuild = 0
        MeUpdates.save(MeUpdate(build: screen == "update" || Demo.arg("-update") == "1" ? AppVer.build + 1 : 0, name: screen == "update" || Demo.arg("-update") == "1" ? "2.29" : "",
                                notes: "Chat with the team, faster maps and a new monthly report.", url: "", at: Date().timeIntervalSince1970))
        if var raw = p.loadSnapshot() { raw["photos"] = photos; raw["me"] = me(); p.saveSnapshot(raw) }
        let base = api.transport
        api.transport = { method, url, headers, body in
            func j(_ o: Any) -> (Int, Data) { (200, (try? JSONSerialization.data(withJSONObject: o)) ?? Data()) }
            let b = body.flatMap { String(data: $0, encoding: .utf8) } ?? ""
            if url.contains("/api/team-photo") {
                if method == "GET" { try? await Task.sleep(nanoseconds: 100_000_000); return (200, faceJpeg(url)) }
                if b.contains("remove") { return j(["ok": true, "name": "Reid Anderson", "photo": ""] as JSON) }
                return j(["ok": true, "name": "Reid Anderson", "photo": "/api/team-photo?name=Reid%20Anderson&v=\(Int(Date().timeIntervalSince1970))"] as JSON)
            }
            if url.contains("/api/mail") {
                if b.contains("\"preview\"") {
                    return j(["ok": true, "subject": "You’re invited to RAFIKI Pin",
                              "html": "<p>Hi Meril,</p><p>You’re invited to RAFIKI Pin, the RAFIKI Outreach field app for pinning our water projects and reporting site visits.</p><p><a href='#'>Open RAFIKI Pin</a></p><p>Sign in with your PIN, which we’re sending you separately.</p><p>— Reid Anderson</p>"] as JSON)
                }
                return j(["ok": true] as JSON)
            }
            if url.contains("/app/ios-version.json") {
                return Demo.arg("-update") == "1" || Demo.arg("-screen") == "update" ? j(["build": AppVer.build + 1, "versionName": "2.29", "notes": "Chat with the team, faster maps and a new monthly report."] as JSON) : (404, Data())
            }
            if url.contains("team=1") { return j(["ok": true, "team": team(admin: false)] as JSON) }
            if url.contains("view=team") { return j(["ok": true, "team": team(admin: true)] as JSON) }
            if url.contains("activity=1") { return j(["ok": true, "activity": activity()] as JSON) }
            if b.contains("\"profile\"") { return j(["ok": true, "me": me()] as JSON) }
            if b.contains("pin-change") { return b.contains("\"current\":\"9999\"") ? j(["ok": true] as JSON) : (400, Data("{\"ok\":false,\"reason\":\"pin-current\"}".utf8)) }
            if b.contains("team-set-pin") { return j(["ok": true] as JSON) }
            let r = await base(method, url, headers, body)
            if method == "GET" && url.hasSuffix("/api/pin"), var o = (try? JSONSerialization.jsonObject(with: r.1)) as? JSON {
                o["photos"] = photos; o["me"] = me()
                return j(o)
            }
            return r
        }
    }

    private static func ago(_ minutes: Double) -> String { When.iso.string(from: Date().addingTimeInterval(-minutes * 60)) }

    static func team(admin: Bool) -> [JSON] {
        var list: [JSON] = [
            ["name": "Meril Kiprop", "home": "ke", "phone": "0712 345 678", "role": "Field officer, Eldoret", "lastSeen": ago(20), "photo": photos["Meril Kiprop"] ?? "", "email": admin ? "meril@rafikioutreach.org" : ""],
            ["name": "Bertin Mwangi", "home": "tz", "phone": "0754 123 456", "role": "", "lastSeen": "2026-10-18T08:00:00Z", "photo": ""],
            ["name": "Grace Wanjiru", "home": "ke", "phone": "", "role": "Water committee trainer", "lastSeen": NSNull(), "photo": photos["Grace Wanjiru"] ?? ""],
            ["name": "Reid Anderson", "home": "ke", "phone": "0712 000 111", "role": "Programme lead", "lastSeen": ago(1), "photo": "", "email": "reid@rafikioutreach.org"],
        ]
        if admin {
            list[0]["src"] = "app"; list[0]["admin"] = false
            list[1]["src"] = "cloudflare"; list[1]["admin"] = false
            list[2]["src"] = "app"; list[2]["admin"] = false; list[2]["blocked"] = ["at": "2026-10-20T09:00:00Z", "by": "Reid Anderson", "reason": "Phone lost"] as JSON
            list[3]["src"] = "app"; list[3]["admin"] = true
        } else {
            list[2]["blocked"] = false
        }
        return list
    }

    static func activity() -> [JSON] {
        [
            ["by": "Meril Kiprop", "a": "visit", "s": "repair", "id": "104", "t": "Kapsaret Borehole", "at": ago(25)],
            ["by": "Meril Kiprop", "a": "pin", "id": "105", "t": "Turbo Spring", "at": ago(70)],
            ["by": "Bertin Mwangi", "a": "fixed", "id": "101", "t": "Chepkongi Dispensary", "at": ago(60 * 26)],
            ["by": "Grace Wanjiru", "a": "assign", "to": "Reid Anderson", "id": "105", "t": "1 visit for Reid Anderson", "at": ago(60 * 3)],
            ["by": "Bertin Mwangi", "a": "new", "id": "106", "t": "Kiptoo Primary School", "at": ago(60 * 24 * 3)],
            ["by": "SMS from +254 7••", "src": "sms", "a": "visit", "s": "notworking", "id": "104", "t": "Kapsaret Borehole", "at": ago(60 * 24 * 9)],
            ["by": "Reid Anderson", "a": "visit", "s": "working", "id": "101", "t": "Chepkongi Dispensary", "at": ago(60 * 2)],
            ["by": "Reid Anderson", "a": "pin", "id": "102", "t": "Kiptoo Well", "at": ago(60 * 24)],
            ["by": "Reid Anderson", "a": "new", "id": "105", "t": "Turbo Spring", "at": ago(60 * 24 * 2)],
        ]
    }

    /// A made-up face: a coloured circle with the person's initials.
    static func faceJpeg(_ url: String) -> Data {
        let name = URLComponents(string: url)?.queryItems?.first { $0.name == "name" }?.value ?? "?"
        let colours: [UInt32] = [0x2FB872, 0x8C6CF2, 0xF26A4F, 0x3EA7EE]
        let c = colours[name.unicodeScalars.reduce(0) { $0 + Int($1.value) } % colours.count]
        let f = UIGraphicsImageRendererFormat(); f.scale = 1
        let img = UIGraphicsImageRenderer(size: CGSize(width: 256, height: 256), format: f).image { ctx in
            UIColor(Color(hex: c)).setFill(); ctx.fill(CGRect(x: 0, y: 0, width: 256, height: 256))
            UIColor(Color(hex: 0xFFE0C2)).setFill(); ctx.cgContext.fillEllipse(in: CGRect(x: 78, y: 48, width: 100, height: 112))
            UIColor(Color(hex: 0x3A2A20)).setFill(); ctx.cgContext.fillEllipse(in: CGRect(x: 40, y: 168, width: 176, height: 140))
        }
        return img.jpegData(compressionQuality: 0.85) ?? Data()
    }

    /// The Me area's demo screens. Returns true when it set the screen.
    @MainActor static func route(_ m: AppModel, _ parts: [String]) -> Bool {
        if let sh = Demo.arg("-sheet"), sh.hasPrefix("contact") {
            let who = sh == "contact-me" ? "Reid Anderson" : sh == "contact-new" ? "Grace Wanjiru" : sh == "contact-cf" ? "Bertin Mwangi" : "Meril Kiprop"
            Task { @MainActor in try? await Task.sleep(nanoseconds: 600_000_000); Contact.show(who) }
        }
        switch parts.first ?? "" {
        case "notes": m.stack = [Place(tab: "me"), Place(tab: "me", sub: "notes")]; return true
        case "settings": m.stack = [Place(tab: "me"), Place(tab: "me", sub: "set_" + (parts.count > 1 ? parts[1] : "about"))]; return true
        case "update", "welcome": m.stack = [Place(tab: "me")]; return true
        default: return false
        }
    }
}

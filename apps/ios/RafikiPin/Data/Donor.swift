import Foundation
import SwiftUI

// RAFIKI Pin in donor mode (Android's data/Donor.kt). A donor signs in with their private link or short donor code and
// the app then asks /api/donor with the same token as the private page (header X-Donor-Token). The server only ever
// sends that donor's own projects, with whitelisted fields; nothing here touches the staff endpoints.
// A phone is either staff or donor: signing in as a donor needs the staff sign-in gone first.

/// Reading what a donor types or pastes: a private link (either address), a bare token, or a 10-letter code.
enum DonorEntry {
    static let ALPHA = "23456789ABCDEFGHJKMNPQRSTVWXYZ"
    struct Read { var token: String?; var code: String? }
    static func read(_ s: String) -> Read? {
        let x = s.trimmingCharacters(in: .whitespacesAndNewlines)
        if let m = x.range(of: "(?:/donor/|#donor=)([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])", options: .regularExpression) {
            let hit = String(x[m]); return Read(token: String(hit.suffix(43)), code: nil)
        }
        if x.range(of: "^[A-Za-z0-9_-]{43}$", options: .regularExpression) != nil { return Read(token: x, code: nil) }
        let c = x.uppercased().replacingOccurrences(of: "[\\s\\u2010-\\u2015-]+", with: "", options: .regularExpression)
        return c.count == 10 && c.allSatisfy({ ALPHA.contains($0) }) ? Read(token: nil, code: c) : nil
    }
    /// https://pin.rafikioutreach.org/pin#donor=<token> or …/donor/<token> → the token.
    static func token(from url: URL) -> String? {
        if let f = url.fragment, f.hasPrefix("donor="), f.count == 49 { return String(f.dropFirst(6)) }
        let p = url.path
        if p.hasPrefix("/donor/") { let t = p.dropFirst(7).trimmingCharacters(in: CharacterSet(charactersIn: "/")); if t.count == 43 { return t } }
        return nil
    }
}

// ---- what the donor sees ----------------------------------------------------------------------------------------
struct DVisit { var at: String; var state: String; var build: Bool; var stage: String; var users: Int; var flow: Double; var water: String; var clarity: String
    var pupils: Int; var patients: Int; var children: Int }
struct DRepair { var at: String; var problems: [String]; var cost: Int; var fixedAt: String; var open: Bool }
struct DMedia { var id: String; var at: String; var label: String; var video = false }
struct DStory { var at: String; var quote: String; var who: String }
struct DProject: Identifiable {
    var id: String; var title: String; var type: String; var cat: String; var water: Bool; var country: String; var area: String; var status: String; var year: Int; var done: String
    var gps: LatLng?; var depth: Int; var pump: String; var photos: [String]; var health: String; var healthAt: String
    var people: Int; var litresPerDay: Int; var years: Double; var pupils: Int; var patients: Int; var children: Int
    var visits: [DVisit]; var repairs: [DRepair]; var stories: [DStory]; var gallery: [DMedia]; var thanks: [DMedia]
    var place: String { [area, country].filter { !$0.isEmpty }.joined(separator: ", ") }
    /// working · repairing · notworking (from the last check), else building · planned · done
    var state: String { !health.isEmpty ? health : status == "Under construction" ? "building" : status == "Upcoming" ? "planned" : "done" }
}
struct DUpdate { var type: String; var pid: String; var at: String; var n: Int; var photos: [String]; var text: String; var who: String; var media: String; var id: String
    var years: Int; var visit: DVisit?; var problems: [String]; var fixed: Bool }
struct DonorView {
    var name: String; var viewer: String; var org: String; var group: Bool; var projects: [DProject]; var updates: [DUpdate]
    var nProjects: Int; var nWater: Int; var people: Int; var visitsYear: Int; var email: Bool; var digest: Bool; var mailOn: Bool
    func project(_ id: String) -> DProject? { projects.first { $0.id == id } }
    var newest: String { updates.map(\.at).max() ?? "" }

    private static func s(_ j: JSON, _ k: String) -> String { let v = j.str(k); return v == "null" ? "" : v }
    private static func strs(_ j: JSON, _ k: String) -> [String] { (j[k] as? [Any] ?? []).map { "\($0)" } }
    private static func visit(_ v: JSON) -> DVisit {
        let n = v.obj("numbers") ?? [:]
        return DVisit(at: s(v, "at"), state: s(v, "state"), build: v.bool("build"), stage: s(v, "stage"), users: v.int("users"), flow: v.num("flow") ?? 0, water: s(v, "water"),
                      clarity: s(v, "clarity"), pupils: n.int("pupils"), patients: n.int("patients"), children: n.int("children"))
    }
    static func from(_ j: JSON) -> DonorView {
        let d = j.obj("donor") ?? [:], tt = j.obj("totals") ?? [:]; let me = j.obj("me") ?? [:]
        let ps = j.objs("projects").map { p -> DProject in
            let h = p.obj("health"), im = p.obj("impact") ?? [:], g = p["gps"] as? [Double]
            return DProject(id: s(p, "id"), title: s(p, "title"), type: s(p, "type"), cat: s(p, "cat"), water: p.bool("water"), country: s(p, "country"), area: s(p, "area"),
                status: s(p, "status"), year: p.int("year"), done: s(p, "done"), gps: g.flatMap { $0.count == 2 ? LatLng(lat: $0[0], lng: $0[1]) : nil }, depth: p.int("depth"),
                pump: s(p, "pump"), photos: strs(p, "photos"), health: h.map { s($0, "state") } ?? "", healthAt: h.map { s($0, "at") } ?? "",
                people: im.int("people"), litresPerDay: im.int("litresPerDay"), years: im.num("years") ?? 0, pupils: im.int("pupils"), patients: im.int("patients"), children: im.int("children"),
                visits: p.objs("visits").map(visit),
                repairs: p.objs("repairs").map { r in DRepair(at: s(r, "at"), problems: strs(r, "problems"), cost: r.int("cost"), fixedAt: s(r, "fixedAt"), open: r.bool("open")) },
                stories: p.objs("stories").map { x in DStory(at: s(x, "at"), quote: s(x, "quote"), who: s(x, "who")) },
                gallery: p.objs("gallery").map { m in DMedia(id: s(m, "id"), at: s(m, "at"), label: s(m, "label")) },
                thanks: p.objs("thanks").map { m in DMedia(id: s(m, "id"), at: s(m, "at"), label: s(m, "caption"), video: s(m, "media") == "video") })
        }
        let us = j.objs("updates").map { u in
            DUpdate(type: s(u, "type"), pid: s(u, "pid"), at: s(u, "at"), n: u.int("n"), photos: strs(u, "photos"), text: s(u, "quote").isEmpty ? s(u, "caption") : s(u, "quote"),
                    who: s(u, "who"), media: s(u, "media"), id: s(u, "id"), years: u.int("years"), visit: s(u, "type") == "visit" ? visit(u) : nil, problems: strs(u, "problems"), fixed: !s(u, "fixedAt").isEmpty)
        }
        return DonorView(name: s(d, "name"), viewer: s(d, "viewer").isEmpty ? s(d, "name") : s(d, "viewer"), org: s(d, "org"), group: d.bool("group"), projects: ps, updates: us,
                         nProjects: tt.int("projects"), nWater: tt.int("water"), people: tt.int("people"), visitsYear: tt.int("visitsYear"),
                         email: me.bool("email"), digest: me["digest"] == nil ? true : me.bool("digest"), mailOn: me.bool("on"))
    }
}

func donorNum(_ n: Double) -> String { let f = NumberFormatter(); f.numberStyle = .decimal; f.locale = Locale(identifier: "en_US"); f.maximumFractionDigits = 0; return f.string(from: NSNumber(value: n)) ?? "\(Int(n))" }
func donorNum(_ n: Int) -> String { donorNum(Double(n)) }

/// A visit in a sentence, with no names, notes or costs (the server never sends them).
func donorVisitSay(_ v: DVisit) -> String {
    var a: [String] = []
    if v.build { a.append(!v.stage.isEmpty ? t("Building: ", "Ujenzi: ") + donorStage(v.stage).lowercased() : t("Building checked", "Ujenzi umekaguliwa")) }
    else { switch v.state { case "working": a.append(t("Working well", "Inafanya kazi vizuri")); case "repair": a.append(t("Needs a repair", "Inahitaji matengenezo")); case "notworking": a.append(t("Not working", "Haifanyi kazi")); default: break } }
    if v.users > 0 { a.append(t("about \(donorNum(v.users)) people using it", "takriban watu \(donorNum(v.users)) wanaitumia")) }
    if v.flow > 0 { a.append(t("\(donorNum(v.flow.rounded())) litres a minute", "lita \(donorNum(v.flow.rounded())) kwa dakika")) }
    if v.water == "safe" { a.append(t("water tested safe", "maji yamepimwa ni salama")) }
    if v.clarity == "clear" { a.append(t("clear water", "maji safi")) }
    if v.pupils > 0 { a.append(t("\(donorNum(v.pupils)) pupils", "wanafunzi \(donorNum(v.pupils))")) }
    if v.patients > 0 { a.append(t("\(donorNum(v.patients)) patients a week", "wagonjwa \(donorNum(v.patients)) kwa wiki")) }
    if v.children > 0 { a.append(t("\(donorNum(v.children)) children", "watoto \(donorNum(v.children))")) }
    let s = a.joined(separator: ", ")
    return s.isEmpty ? "" : s.prefix(1).uppercased() + s.dropFirst() + "."
}
func donorStage(_ s: String) -> String {
    guard Lang.sw else { return s }
    return ["Foundation": "Msingi", "Walls going up": "Kuta zinajengwa", "Roofing": "Kuezeka paa", "Finishing": "Kumalizia", "Finished": "Imekamilika"][s] ?? s
}

/// Donor mode's state: who is signed in, their projects, and signing in and out.
@MainActor
final class DonorState: ObservableObject {
    static let shared = DonorState()
    private let d = UserDefaults(suiteName: "rafiki-donor") ?? .standard
    private var file: URL { Prefs.dataDir.appendingPathComponent("donor.json") }
    var token: String { get { d.string(forKey: "token") ?? "" } set { d.set(newValue, forKey: "token") } }
    private var notifiedAt: String { get { d.string(forKey: "notifiedAt") ?? "" } set { d.set(newValue, forKey: "notifiedAt") } }
    @Published var view: DonorView?
    /// Signed in as a donor (the app shows donor mode only, never the staff screens).
    @Published var on: Bool
    /// Switched off by RAFIKI: shown once, kindly, on the sign-in side.
    @Published var gone: Bool
    /// A private link opened from outside the app: the sign-in screen tries it straight away.
    @Published var prefill = ""
    /// The sign-in screen asked for from the staff side ("I'm a donor").
    @Published var entry = false
    @Published var seen: String
    @Published var goProject: String?
    var server = AppModel.server

    init() {
        on = !(UserDefaults(suiteName: "rafiki-donor")?.string(forKey: "token") ?? "").isEmpty
        gone = UserDefaults(suiteName: "rafiki-donor")?.bool(forKey: "gone") ?? false
        seen = UserDefaults(suiteName: "rafiki-donor")?.string(forKey: "seen") ?? ""
        if on, let data = try? Data(contentsOf: file), let j = (try? JSONSerialization.jsonObject(with: data)) as? JSON { view = DonorView.from(j) }
    }

    // ---- /api/donor ----
    func photo(_ id: String, w: Int = 960) -> String { "\(server)/api/donor?photo=\(Api.enc(id))&w=\(w)&t=\(Api.enc(token))" }
    func board(_ id: String, w: Int = 960) -> String { "\(server)/api/photo/\(Api.enc(id))?w=\(w)" }
    private func call(_ method: String, _ path: String, _ body: JSON?) async -> Reply {
        var h = ["Accept": "application/json"]
        if !token.isEmpty { h["X-Donor-Token"] = token }
        var data: Data?
        if let body { h["Content-Type"] = "application/json"; h["Origin"] = server; data = try? JSONSerialization.data(withJSONObject: body) }
        let (st, out) = await Api.http(method, server + path, h, data)
        return Api.toReply(st, out)
    }

    /// Signs in with a link, token or code. Returns an error text, or nil when in.
    func signIn(_ typed: String) async -> String? {
        guard let e = DonorEntry.read(typed) else {
            return t("That doesn’t look like a donor code or link. A code has 10 letters and numbers, like K7QM4-XR9TB.", "Hiyo haionekani kama msimbo au kiungo cha mfadhili. Msimbo una herufi na namba 10, mf. K7QM4-XR9TB.")
        }
        var tok = e.token ?? ""
        if let code = e.code {
            let r = await call("POST", "/api/donor", ["code": code])
            if !r.ok { return why(r, true) }
            tok = r.json.str("token")
        }
        let old = token
        token = tok
        let r = await call("GET", "/api/donor", nil)
        if !r.ok { token = old; return why(r, e.code != nil) }
        if old != tok { d.set("", forKey: "seen"); seen = ""; notifiedAt = "" }
        accept(r.json)
        d.set(false, forKey: "gone"); gone = false; on = true; entry = false
        return nil
    }
    /// Fresh data (bg: the quiet check that doesn't count as opening the page). A switched-off link signs out.
    @discardableResult func refresh(bg: Bool = false) async -> Reply {
        if token.isEmpty { return Reply(ok: false, status: 401, json: [:], reason: "link") }
        let r = await call("GET", "/api/donor" + (bg ? "?bg=1" : ""), nil)
        if r.ok { accept(r.json) } else if r.status == 404 && r.reason == "link" { switchedOff() }
        return r
    }
    private func accept(_ j: JSON) {
        if let data = try? JSONSerialization.data(withJSONObject: j) { try? data.write(to: file, options: .atomic) }
        let v = DonorView.from(j); view = v
        if seen.isEmpty { seen = v.newest; d.set(seen, forKey: "seen") }
        if notifiedAt.isEmpty { notifiedAt = v.newest }
    }
    private func clear() { ["token", "seen", "notifiedAt"].forEach { d.removeObject(forKey: $0) }; try? FileManager.default.removeItem(at: file) }
    func switchedOff() { clear(); d.set(true, forKey: "gone"); gone = true; view = nil; on = false; seen = "" }
    func signOut() { clear(); d.set(false, forKey: "gone"); gone = false; view = nil; on = false; seen = "" }
    func dismissGone() { d.set(false, forKey: "gone"); gone = false }
    func markSeen() { if let n = view?.newest { seen = n; d.set(n, forKey: "seen") } }
    /// E-mail updates on/off. Returns an error text or nil.
    func setMail(_ on: Bool) async -> String? {
        let r = await call("POST", "/api/donor", ["action": "mail", "on": on])
        if !r.ok { if r.status == 404 && r.reason == "link" { switchedOff() }; return why(r, false) }
        guard let me = r.json.obj("me"), let data = try? Data(contentsOf: file), var j = (try? JSONSerialization.jsonObject(with: data)) as? JSON else { return nil }
        j["me"] = me; accept(j)
        return nil
    }
    func why(_ r: Reply, _ code: Bool) -> String {
        switch r.reason {
        case "rate": return t("Too many tries from this connection. Please try again in an hour.", "Majaribio mengi kutoka mtandao huu. Tafadhali jaribu tena baada ya saa moja.")
        case "offline": return t("No connection. Check your signal and try again.", "Hakuna muunganisho. Angalia mtandao kisha ujaribu tena.")
        case "code", "link": return code ? t("That code isn’t working. Check it, or write to us for a new one.", "Msimbo huo haufanyi kazi. Uhakikishe, au tuandikie upate mpya.")
            : t("That link isn’t working. It may have been replaced with a new one.", "Kiungo hicho hakifanyi kazi. Huenda kimebadilishwa na kipya.")
        default: return t("Something went wrong. Please try again in a moment.", "Kuna hitilafu. Tafadhali jaribu tena baada ya muda mfupi.")
        }
    }
}

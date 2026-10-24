import Foundation

/// English and Kiswahili. Every word on screen goes through t(english, kiswahili).
enum Lang { static var sw = false }
func t(_ en: String, _ sw: String) -> String { Lang.sw ? sw : en }

/// Loose JSON reading, like Android's optString / optInt.
typealias JSON = [String: Any]
extension Dictionary where Key == String, Value == Any {
    func str(_ k: String) -> String {
        switch self[k] {
        case let s as String: return s
        case let n as NSNumber: return n.stringValue
        default: return ""
        }
    }
    func num(_ k: String) -> Double? {
        switch self[k] {
        case let n as NSNumber: return n.doubleValue
        case let s as String: return Double(s)
        default: return nil
        }
    }
    func int(_ k: String) -> Int { Int(num(k) ?? 0) }
    func bool(_ k: String) -> Bool { (self[k] as? Bool) ?? ((self[k] as? NSNumber)?.boolValue ?? false) }
    func obj(_ k: String) -> JSON? { self[k] as? JSON }
    func arr(_ k: String) -> [Any]? { self[k] as? [Any] }
    func objs(_ k: String) -> [JSON] { (self[k] as? [Any])?.compactMap { $0 as? JSON } ?? [] }
}

struct LatLng: Equatable, Hashable {
    var lat: Double; var lng: Double
    /// For people: at most 5 decimals (about 1 m), no trailing zeros, e.g. "0.47, 35.25".
    func text() -> String { [lat, lng].map { LatLng.trim(String(format: "%.5f", $0)) }.joined(separator: ", ") }
    static func trim(_ s: String) -> String { var x = s; while x.contains(".") && (x.hasSuffix("0") || x.hasSuffix(".")) { let dot = x.hasSuffix("."); x.removeLast(); if dot { break } }; return x }
}

/// One RAFIKI project, as /api/pin lists it.
struct Project: Identifiable, Equatable {
    var id: String; var title: String; var country: String; var ck: String; var area: String
    var type: String; var status: String; var year: Int?; var gps: LatLng?; var photo: String?; var pump: String
    /// People served (the board's number, or the latest visit count for admins): the map's heat layer.
    var people: Int? = nil

    var sub: String { [area, country, statusName(status)].filter { !$0.isEmpty }.joined(separator: " · ") }

    static func from(_ o: JSON) -> Project {
        var gps: LatLng? = nil
        if let a = o["gps"] as? [Any], a.count == 2, let la = (a[0] as? NSNumber)?.doubleValue, let ln = (a[1] as? NSNumber)?.doubleValue { gps = LatLng(lat: la, lng: ln) }
        let ph = o.str("photo")
        return Project(id: o.str("id"), title: o.str("title"), country: o.str("country"), ck: o.str("ck"), area: o.str("area"), type: o.str("type"),
                       status: o.str("status"), year: o.num("year").map { Int($0) }, gps: gps, photo: ph.isEmpty || ph == "null" ? nil : ph, pump: o.str("pump"),
                       people: o.num("people").map { Int($0) }.flatMap { $0 > 0 ? $0 : nil })
    }
}

/// An open repair (needs repair / not working), from the last visit.
struct Repair: Equatable {
    var state: String; var by: String; var at: String; var note: String; var urgency: String; var problems: [String]
    var assignee = ""; var step = "reported"; var due = ""; var budget: Int? = nil; var parts = ""; var cost: Int? = nil; var title = ""
    var stepAt: [String: String] = [:]
    /// The second approval, for a repair above the approval limit (Office.swift).
    var approval: Approval? = nil

    static func from(_ o: JSON) -> Repair {
        Repair(state: o.str("state"), by: o.str("by"), at: o.str("at"), note: o.str("note"), urgency: o.str("urgency"),
               problems: (o.arr("problems") ?? []).compactMap { $0 as? String }, assignee: o.str("assignee"),
               step: o.str("step").isEmpty ? "reported" : o.str("step"), due: o.str("due"), budget: o.num("budget").map { Int($0) },
               parts: o.str("parts"), cost: o.num("cost").map { Int($0) }, title: o.str("t"),
               stepAt: (o.obj("stepAt") ?? [:]).reduce(into: [:]) { $0[$1.key] = ($1.value as? String) ?? "" }, approval: Approval.from(o.obj("approval")))
    }
}

struct LastVisit: Equatable { var at: String; var by: String; var state: String }
struct Assignment: Equatable { var aid: String; var id: String; var title: String; var date: String; var by: String; var note: String }

/// Everything the server sends when the app opens.
struct Snapshot: Equatable {
    var who: String; var admin: Bool; var projects: [Project]; var repairs: [String: Repair]; var visits: [String: LastVisit]
    var jobs: [String]; var assigned: [Assignment]; var confirmed: Set<String>; var reviewOpen: Int
    /// The monday.com name this person's own token is connected as ("" when not connected).
    var linked = ""
    /// This person: how they signed in ("app" PIN, "cloudflare" PIN, "shared" PIN, "monday") and what they set in Settings.
    var me = Me()
    /// Repairs estimated above this need a second admin to approve the cost before parts are ordered.
    var approveAbove = Money.APPROVE_DEFAULT
    /// People served (admins only): the Office overview.
    var people: PeopleServed? = nil
    /// When the water was last tested, by project id (ISO time): "Water test due" after 6 months.
    var tests: [String: String] = [:]

    static func from(_ o: JSON) -> Snapshot {
        var reps: [String: Repair] = [:]; (o.obj("repairs") ?? [:]).forEach { k, v in if let j = v as? JSON { reps[k] = Repair.from(j) } }
        var vis: [String: LastVisit] = [:]; (o.obj("visits") ?? [:]).forEach { k, v in if let j = v as? JSON { vis[k] = LastVisit(at: j.str("at"), by: j.str("by"), state: j.str("state")) } }
        return Snapshot(
            who: o.str("who"), admin: o.bool("admin"), projects: o.objs("projects").map(Project.from), repairs: reps, visits: vis,
            jobs: (o.arr("jobs") ?? []).compactMap { $0 as? String },
            assigned: o.objs("assigned").map { Assignment(aid: $0.str("aid"), id: $0.str("id"), title: $0.str("t"), date: $0.str("date"), by: $0.str("by"), note: $0.str("note")) },
            confirmed: Set((o.obj("confirmed") ?? [:]).keys), reviewOpen: o.int("reviewOpen"), linked: o.str("linked"),
            me: o.obj("me").map(Me.from) ?? Me(), approveAbove: o["approveAbove"] == nil ? Money.APPROVE_DEFAULT : o.int("approveAbove"),
            people: PeopleServed.from(o.obj("people")), tests: (o.obj("tests") ?? [:]).reduce(into: [:]) { $0[$1.key] = ($1.value as? String) ?? "" })
    }
}

/// Me › Settings › Personal details, and how this person signs in.
struct Me: Equatable {
    var kind = ""; var nick = ""; var phone = ""; var home = ""
    static func from(_ o: JSON) -> Me { Me(kind: o.str("kind"), nick: o.str("nick"), phone: o.str("phone"), home: o.str("home")) }
}

/// A note on a project in monday.com (the History tab).
struct Note: Identifiable { var id: String; var text: String; var at: String; var by: String; var files: [NoteFile] }
struct NoteFile { var name: String; var ext: String; var url: String
    var isImage: Bool { ["jpg", "jpeg", "png", "webp", "gif", "heic"].contains(ext.lowercased()) }
}

enum Geo {
    static func rad(_ d: Double) -> Double { d * .pi / 180 }
    /// Metres between two points.
    static func metres(_ a: LatLng, _ b: LatLng) -> Double {
        let r = 6371000.0, dLat = rad(b.lat - a.lat), dLng = rad(b.lng - a.lng)
        let h = sin(dLat / 2) * sin(dLat / 2) + cos(rad(a.lat)) * cos(rad(b.lat)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }
    static func grouped(_ n: Int) -> String {
        let f = NumberFormatter(); f.numberStyle = .decimal; f.locale = Locale(identifier: "en_US"); return f.string(from: NSNumber(value: n)) ?? "\(n)"
    }
    /// "850 m" under 1 km, "5.5 km" under 10 km, then "12 km"; in miles "250 ft" under 0.1 mi, then "1.4 mi". The same as the web app.
    static func dist(_ m: Double, miles: Bool) -> String {
        if miles {
            let mi = m / 1609.344
            if mi < 0.1 { return grouped(Int((m * 3.28084).rounded())) + " ft" }
            let f = NumberFormatter(); f.numberStyle = .decimal; f.locale = Locale(identifier: "en_US"); f.minimumFractionDigits = 1; f.maximumFractionDigits = 1
            return (f.string(from: NSNumber(value: mi)) ?? String(format: "%.1f", mi)) + " mi"
        }
        let whole = Int(m.rounded())
        if whole < 1000 { return grouped(whole) + " m" }
        let tenths = (m / 100).rounded()
        return tenths < 100 ? String(format: "%.1f km", tenths / 10) : grouped(Int((m / 1000).rounded())) + " km"
    }
    /// GPS accuracy: "±8 m" or "±1.2 km".
    static func acc(_ m: Int) -> String { "±" + dist(Double(m), miles: false) }
    private static let dirs = [("north", "kaskazini"), ("north-east", "kaskazini-mashariki"), ("east", "mashariki"), ("south-east", "kusini-mashariki"),
                               ("south", "kusini"), ("south-west", "kusini-magharibi"), ("west", "magharibi"), ("north-west", "kaskazini-magharibi")]
    static func heading(_ from: LatLng, _ to: LatLng) -> String {
        let y = sin(rad(to.lng - from.lng)) * cos(rad(to.lat))
        let x = cos(rad(from.lat)) * sin(rad(to.lat)) - sin(rad(from.lat)) * cos(rad(to.lat)) * cos(rad(to.lng - from.lng))
        let deg = (atan2(y, x) * 180 / .pi + 360).truncatingRemainder(dividingBy: 360)
        let d = dirs[Int((deg + 22.5) / 45) % 8]; return t(d.0, d.1)
    }
    /// "0.561368, 35.065480" or a Google/Apple Maps link with coordinates in it.
    static func parse(_ text: String) -> LatLng? {
        let s = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let pats = [#"@(-?\d+\.\d+),(-?\d+\.\d+)"#, #"[?&](?:q|ll|query|daddr|destination)=(-?\d+\.\d+)(?:,|%2C)\s*(-?\d+\.\d+)"#,
                    #"^(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)$"#, #"(-?\d+\.\d{3,})\s*[, ]\s*(-?\d+\.\d{3,})"#]
        for p in pats {
            guard let re = try? NSRegularExpression(pattern: p), let m = re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)),
                  let r1 = Range(m.range(at: 1), in: s), let r2 = Range(m.range(at: 2), in: s),
                  let lat = Double(s[r1]), let lng = Double(s[r2]) else { continue }
            if (-90...90).contains(lat) && (-180...180).contains(lng) && !(lat == 0 && lng == 0) { return LatLng(lat: lat, lng: lng) }
        }
        return nil
    }
}

let COUNTRIES: [(String, String)] = [("ke", "Kenya"), ("tz", "Tanzania"), ("ug", "Uganda"), ("mw", "Malawi")]
func countryName(_ k: String) -> String? { COUNTRIES.first { $0.0 == k }?.1 }
func statusName(_ s: String) -> String {
    switch s { case "Completed": return t("Completed", "Imekamilika"); case "In Progress": return t("In progress", "Inaendelea"); case "Due Diligence": return t("Being checked", "Inachunguzwa"); default: return s }
}
let STATES = ["working", "repair", "notworking"]
func stateName(_ k: String) -> String {
    switch k { case "working": return t("Working well", "Inafanya kazi vizuri"); case "repair": return t("Needs repair", "Inahitaji matengenezo"); case "notworking": return t("Not working", "Haifanyi kazi"); default: return k }
}
let URGENCY = ["urgent", "month", "watch"]
func urgencyName(_ k: String) -> String {
    switch k { case "urgent": return t("Urgent", "Haraka"); case "month": return t("This month", "Mwezi huu"); case "watch": return t("Keep an eye on it", "Fuatilia tu"); default: return k }
}
let DUE_DAYS = 180.0

func reasonText(_ reason: String?) -> String {
    switch reason {
    case "token-format": return t("That doesn’t look like a monday.com token. On the token page tap Copy, then try again.", "Hiyo haionekani kama tokeni ya monday.com. Kwenye ukurasa wa tokeni gusa Copy, kisha jaribu tena.")
    case "token-bad": return t("monday.com didn’t accept that token. Copy it again from the token page.", "monday.com haikukubali tokeni hiyo. Inakili tena kutoka ukurasa wa tokeni.")
    case "account": return t("That token is for a different monday.com account. Use your RAFIKI login.", "Tokeni hiyo ni ya akaunti nyingine ya monday.com. Tumia akaunti yako ya RAFIKI.")
    case "board": return t("Your monday.com login can’t see the Projects board yet. Ask the office to invite you to it.", "Akaunti yako ya monday.com bado haioni ubao wa Projects. Omba ofisi ikualike.")
    case "pin-current": return t("Your current PIN isn’t right.", "PIN yako ya sasa si sahihi.")
    case "pin-weak": return t("Choose 4 to 6 digits that aren’t easy to guess (not 1234 or 0000).", "Chagua tarakimu 4 hadi 6 zisizo rahisi kubahatisha (si 1234 wala 0000).")
    case "pin-taken": return t("That PIN can’t be used. Choose another.", "PIN hiyo haiwezi kutumika. Chagua nyingine.")
    case "pin-cloudflare": return t("Your PIN was set by the office, so only they can change it.", "PIN yako iliwekwa na ofisi, kwa hiyo wao pekee wanaweza kuibadilisha.")
    case "own-pin": return t("You need your own PIN for this. Ask the office for one.", "Unahitaji PIN yako mwenyewe kwa hili. Omba ofisi ikupe.")
    case "pin": return t("That PIN isn’t right. Please check it with your team.", "PIN hiyo si sahihi. Tafadhali hakikisha na timu yako.")
    case "locked": return t("Too many wrong PINs from this connection. Please try again in an hour.", "PIN nyingi zisizo sahihi kutoka mtandao huu. Jaribu tena baada ya saa moja.")
    case "not-set-up": return t("The pin app isn’t switched on yet: PINs still need to be set up.", "Programu bado haijawashwa: PIN bado hazijawekwa.")
    case "blocked": return t("The office has paused your access to RAFIKI Pin. Nothing you saved is lost. If you think this is a mistake, please talk to the office.", "Ofisi imesimamisha ufikiaji wako wa RAFIKI Pin. Hakuna ulichohifadhi kilichopotea. Kama unadhani ni kosa, tafadhali ongea na ofisi.")
    case "signed-out": return t("The office signed you out of this phone. Sign in again with your PIN; nothing was lost.", "Ofisi imekutoa kwenye simu hii. Ingia tena kwa PIN yako; hakuna kilichopotea.")
    case "bad-email": return t("That doesn’t look like an e-mail address. Please check it.", "Hiyo haionekani kama anwani ya barua pepe. Tafadhali iangalie.")
    case "cloudflare": return t("This PIN is set in Cloudflare, so it can’t be changed in the app. Move them to an app PIN first.", "PIN hii imewekwa kwenye Cloudflare, kwa hiyo haiwezi kubadilishwa kwenye programu. Mhamishe kwenye PIN ya programu kwanza.")
    case "not-in-app": return t("Their PIN wasn’t made in the app, so it can’t be changed here.", "PIN yake haikutengenezwa kwenye programu, kwa hiyo haiwezi kubadilishwa hapa.")
    case "not-cloudflare": return t("They aren’t set up in Cloudflare, so there’s nothing to move.", "Hawajawekwa kwenye Cloudflare, kwa hiyo hakuna cha kuhamisha.")
    case "not-admin": return t("Only admins can do this.", "Wasimamizi pekee wanaweza kufanya hivi.")
    case "weak-pin": return t("That PIN is too easy to guess. Choose another.", "PIN hiyo ni rahisi kubahatisha. Chagua nyingine.")
    case "offline": return t("No signal. It will be sent when you’re back online.", "Hakuna mtandao. Itatumwa ukipata mtandao tena.")
    case "rate": return t("Too many changes from this phone. Please wait a little and try again.", "Mabadiliko mengi kutoka simu hii. Subiri kidogo kisha ujaribu tena.")
    case "project": return t("That project couldn’t be found.", "Mradi huo haukupatikana.")
    case "no-location": return t("Save a location first.", "Hifadhi mahali kwanza.")
    default: return t("Something went wrong on the server. Please try again.", "Kuna hitilafu kwenye seva. Tafadhali jaribu tena.")
    }
}

/// Dates as the app shows them: "19 Oct, 12:00".
enum When {
    static let iso: ISO8601DateFormatter = { let f = ISO8601DateFormatter(); f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]; return f }()
    static let isoPlain = ISO8601DateFormatter()
    static func parse(_ s: String) -> Date? { iso.date(from: s) ?? isoPlain.date(from: s) }
    static func now() -> String { iso.string(from: Date()) }
    static func text(_ s: String) -> String {
        guard let d = parse(s) else { return s }
        let f = DateFormatter(); f.locale = Locale(identifier: "en_GB"); f.dateFormat = "d MMM, HH:mm"; return f.string(from: d)
    }
    /// Today on this phone (not UTC), as yyyy-MM-dd.
    static func localDay(_ d: Date = Date()) -> String { let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyy-MM-dd"; return f.string(from: d) }
    static func day(_ d: Date = Date()) -> String { let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.timeZone = TimeZone(identifier: "UTC"); f.dateFormat = "yyyy-MM-dd"; return f.string(from: d) }
}

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
    func text() -> String { String(format: "%.6f, %.6f", lat, lng) }
}

/// One RAFIKI project, as /api/pin lists it.
struct Project: Identifiable, Equatable {
    var id: String; var title: String; var country: String; var ck: String; var area: String
    var type: String; var status: String; var year: Int?; var gps: LatLng?; var photo: String?; var pump: String
    /// When monday.com says the project was finished ("YYYY-MM…"), or "".
    var done: String = ""

    var sub: String { [area, country, statusName(status)].filter { !$0.isEmpty }.joined(separator: " · ") }

    static func from(_ o: JSON) -> Project {
        var gps: LatLng? = nil
        if let a = o["gps"] as? [Any], a.count == 2, let la = (a[0] as? NSNumber)?.doubleValue, let ln = (a[1] as? NSNumber)?.doubleValue { gps = LatLng(lat: la, lng: ln) }
        let ph = o.str("photo")
        return Project(id: o.str("id"), title: o.str("title"), country: o.str("country"), ck: o.str("ck"), area: o.str("area"), type: o.str("type"),
                       status: o.str("status"), year: o.num("year").map { Int($0) }, gps: gps, photo: ph.isEmpty || ph == "null" ? nil : ph, pump: o.str("pump"),
                       done: o.str("done").range(of: #"^\d{4}-\d{2}"#, options: .regularExpression) != nil ? o.str("done") : "")
    }
}

/// An open repair (needs repair / not working), from the last visit.
struct Repair: Equatable {
    var state: String; var by: String; var at: String; var note: String; var urgency: String; var problems: [String]
    var assignee = ""; var step = "reported"; var due = ""; var budget: Int? = nil; var parts = ""; var cost: Int? = nil; var title = ""

    static func from(_ o: JSON) -> Repair {
        Repair(state: o.str("state"), by: o.str("by"), at: o.str("at"), note: o.str("note"), urgency: o.str("urgency"),
               problems: (o.arr("problems") ?? []).compactMap { $0 as? String }, assignee: o.str("assignee"),
               step: o.str("step").isEmpty ? "reported" : o.str("step"), due: o.str("due"), budget: o.num("budget").map { Int($0) },
               parts: o.str("parts"), cost: o.num("cost").map { Int($0) }, title: o.str("t"))
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
    /// Stories: where each project's story is ("not-started" … "published"), answers filled and photos, by project id.
    var stories: [String: StoryBrief] = [:]

    static func from(_ o: JSON) -> Snapshot {
        var reps: [String: Repair] = [:]; (o.obj("repairs") ?? [:]).forEach { k, v in if let j = v as? JSON { reps[k] = Repair.from(j) } }
        var vis: [String: LastVisit] = [:]; (o.obj("visits") ?? [:]).forEach { k, v in if let j = v as? JSON { vis[k] = LastVisit(at: j.str("at"), by: j.str("by"), state: j.str("state")) } }
        return Snapshot(
            who: o.str("who"), admin: o.bool("admin"), projects: o.objs("projects").map(Project.from), repairs: reps, visits: vis,
            jobs: (o.arr("jobs") ?? []).compactMap { $0 as? String },
            assigned: o.objs("assigned").map { Assignment(aid: $0.str("aid"), id: $0.str("id"), title: $0.str("t"), date: $0.str("date"), by: $0.str("by"), note: $0.str("note")) },
            confirmed: Set((o.obj("confirmed") ?? [:]).keys), reviewOpen: o.int("reviewOpen"), linked: o.str("linked"), stories: StoryBrief.all(o.obj("stories")))
    }
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
    /// "910 m", "12.4 km", or in miles. Exact to the metre under 10 km, like the web app.
    static func dist(_ m: Double, miles: Bool) -> String {
        if miles {
            let mi = m / 1609.344
            if mi < 1 { return grouped(Int((m * 3.28084).rounded())) + " ft" }
            if mi < 10 { return String(format: "%.2f mi", mi) }
            if mi < 100 { return String(format: "%.1f mi", mi) }
            return "\(Int(mi.rounded())) mi"
        }
        if m < 10000 { return grouped(Int(m.rounded())) + " m" }
        if m < 100000 { return String(format: "%.1f km", m / 1000) }
        return "\(Int((m / 1000).rounded())) km"
    }
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
    switch s { case "Completed": return t("Completed", "Imekamilika"); case "In Progress": return t("In progress", "Inaendelea"); case "Due Diligence": return t("Due Diligence", "Inachunguzwa"); default: return s }
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
    case "pin": return t("That PIN isn’t right. Please check it with your team.", "PIN hiyo si sahihi. Tafadhali hakikisha na timu yako.")
    case "locked": return t("Too many wrong PINs from this connection. Please try again in an hour.", "PIN nyingi zisizo sahihi kutoka mtandao huu. Jaribu tena baada ya saa moja.")
    case "not-set-up": return t("The pin app isn’t switched on yet: PINs still need to be set up.", "Programu bado haijawashwa: PIN bado hazijawekwa.")
    case "offline": return t("No signal. It will be sent when you’re back online.", "Hakuna mtandao. Itatumwa ukipata mtandao tena.")
    case "rate": return t("Too many changes from this phone. Please wait a little and try again.", "Mabadiliko mengi kutoka simu hii. Subiri kidogo kisha ujaribu tena.")
    case "project": return t("That project couldn’t be found.", "Mradi huo haukupatikana.")
    case "no-location": return t("Save a location first.", "Hifadhi mahali kwanza.")
    case "token-format": return t("That doesn’t look like a monday.com token. On the token page tap Copy, then try again.", "Hiyo haionekani kama tokeni ya monday.com. Kwenye ukurasa wa tokeni gusa Copy, kisha jaribu tena.")
    case "token-bad": return t("monday.com didn’t accept that token. Copy it again from the token page.", "monday.com haikukubali tokeni hiyo. Inakili tena kutoka ukurasa wa tokeni.")
    case "account": return t("That token is for a different monday.com account. Use your RAFIKI login.", "Tokeni hiyo ni ya akaunti nyingine ya monday.com. Tumia akaunti yako ya RAFIKI.")
    case "board": return t("Your monday.com login can’t see the Projects board yet. Ask the office to invite you to it.", "Akaunti yako ya monday.com bado haioni ubao wa Projects. Omba ofisi ikualike.")
    case "own-pin": return t("You need your own PIN for this. Ask the office for one.", "Unahitaji PIN yako mwenyewe kwa hili. Omba ofisi ikupe.")
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

import Foundation
import SwiftUI

// Office, projects and field tools (Android 2.15 – 2.28): what the snapshot carries beyond the 2.13 fields, money and
// the second approval, people served, costs, currency, kinds of project and water tests. Read from the snapshot saved on
// the phone (Prefs.loadSnapshot), so the shared Snapshot type stays as it is.

/// The second approval on a big repair (as /api/pin and /api/admin keep it on the job).
struct Approval: Equatable {
    var status: String; var rid: String; var by: String; var at: String; var est: Int; var dec: String; var note: String
    static func from(_ o: JSON?) -> Approval? {
        guard let o else { return nil }
        return Approval(status: o.str("status"), rid: o.str("rid"), by: o.str("by"), at: o.str("at"), est: o.int("est"), dec: o.str("dec"), note: o.str("note"))
    }
}

/// A build's stage as the list carries it: stage key, when, by whom, % done, expected finish, parent project, title,
/// how many photos, and whether it is behind schedule.
struct StageBrief: Equatable {
    var s: String; var at = ""; var by = ""; var pct: Int? = nil; var due = ""; var parent = ""; var t = ""; var n = 0; var behind = false
    static func from(_ o: JSON) -> StageBrief {
        func s(_ k: String) -> String { let v = o.str(k); return v == "null" ? "" : v }
        return StageBrief(s: s("s").isEmpty ? s("stage") : s("s"), at: s("at"), by: s("by"), pct: o["pct"] is NSNull ? nil : o.num("pct").map { Int($0) }, due: s("due"),
                          parent: s("parent"), t: s("t"), n: o.int("n"), behind: o.bool("behind"))
    }
}

/// People served now, by country and type, and the last 12 months (oldest first; nil = not known).
struct PeopleServed: Equatable {
    var total: Int; var counted: Int; var measured: Int; var byCountry: [String: Int]; var byType: [String: Int]; var trend: [(String, Int?)]
    var growth: Int { (trend.first { $0.1 != nil }?.1).map { total - $0 } ?? 0 }
    static func == (a: PeopleServed, b: PeopleServed) -> Bool { a.total == b.total && a.counted == b.counted && a.byCountry == b.byCountry }
    static func from(_ o: JSON?) -> PeopleServed? {
        guard let o else { return nil }
        func map(_ j: JSON?) -> [String: Int] { var r: [String: Int] = [:]; (j ?? [:]).keys.forEach { r[$0] = j?.int($0) ?? 0 }; return r }
        let tr = o.objs("trend").map { x -> (String, Int?) in (x.str("m"), x["n"] is NSNull || x["n"] == nil ? nil : x.int("n")) }
        return PeopleServed(total: o.int("total"), counted: o.int("counted"), measured: o.int("measured"), byCountry: map(o.obj("byCountry")), byType: map(o.obj("byType")), trend: tr)
    }
}

/// What the snapshot says beyond the shared Snapshot: read once per snapshot from the copy saved on the phone.
struct SnapX {
    var approveAbove = Money.approveDefault
    var people: PeopleServed? = nil
    /// When the water was last tested, by project id (ISO time).
    var tests: [String: String] = [:]
    /// Building projects: where each tracked build is, by project id.
    var stages: [String: StageBrief] = [:]
    /// People served per project (the board's number, or the latest visit count for admins).
    var served: [String: Int] = [:]
    /// The second approval and step times of each open repair.
    var approvals: [String: Approval] = [:]
    var stepAt: [String: [String: String]] = [:]
    /// This person's home country (Settings), "" when not set.
    var home = ""
    var kind = ""

    static func from(_ o: JSON) -> SnapX {
        var x = SnapX()
        if o["approveAbove"] != nil, let n = o.num("approveAbove") { x.approveAbove = Int(n) }
        x.people = PeopleServed.from(o.obj("people"))
        (o.obj("tests") ?? [:]).forEach { k, v in if let s = v as? String { x.tests[k] = s } }
        (o.obj("stages") ?? [:]).forEach { k, v in if let j = v as? JSON { x.stages[k] = StageBrief.from(j) } }
        for p in o.objs("projects") { let n = p.int("people"); if n > 0 { x.served[p.str("id")] = n } }
        (o.obj("repairs") ?? [:]).forEach { k, v in
            guard let j = v as? JSON else { return }
            x.approvals[k] = Approval.from(j.obj("approval"))
            if let sa = j.obj("stepAt") { var d: [String: String] = [:]; sa.forEach { if let s = $1 as? String { d[$0] = s } }; x.stepAt[k] = d }
        }
        x.home = o.obj("me")?.str("home") ?? ""
        x.kind = o.obj("me")?.str("kind") ?? ""
        return x
    }

    private static var cachedFor: Snapshot?
    private static var cached = SnapX()
    /// The extras for the snapshot on screen (read again only when the snapshot changed).
    @MainActor static func of(_ m: AppModel) -> SnapX {
        guard let s = m.snap else { return SnapX() }
        if cachedFor == s { return cached }
        cachedFor = s
        cached = m.prefs.loadSnapshot().map(SnapX.from) ?? SnapX()
        return cached
    }
}

extension AppModel {
    /// Office, projects and map extras from the snapshot (stages, water tests, people served, the approval limit…).
    var sx: SnapX { SnapX.of(self) }
    /// Being built now: a tracked build not yet handed over, or monday.com's "Under Construction" status.
    func building(_ p: Project) -> Bool {
        if let st = sx.stages[p.id] { return st.s != "handed" }
        return p.status.lowercased() == "under construction"
    }
}

/// Repair money and the two-step approval: the same rules as lib/pin-office.js.
enum Money {
    static let approveDefault = 50000
    private static let order = ["reported", "assigned", "parts", "fixed"]
    /// "KES 4,500", or "≈ $35 (KES 4,500)" in another currency.
    static func kes(_ n: Int?) -> String { Currency.kes(n ?? 0) }
    /// The repair's estimate: the field estimate or the approved budget, whichever is higher.
    static func est(_ r: Repair) -> Int { max(r.cost ?? 0, r.budget ?? 0) }
    static func past(_ step: String, _ ref: String) -> Bool { (order.firstIndex(of: step.isEmpty ? "reported" : step) ?? 0) >= (order.firstIndex(of: ref) ?? 0) }
    /// "none" (small enough), "needed", "pending", "approved" or "declined".
    static func state(_ r: Repair, _ a: Approval?, _ limit: Int) -> String {
        let e = est(r)
        if e <= limit { return "none" }
        if a?.status == "pending" { return "pending" }
        if let a, a.status == "approved" || a.status == "declined", a.est >= e { return a.status }
        return "needed"
    }
    /// The person who asked can't approve their own request.
    static func canDecide(_ askedBy: String, _ who: String) -> Bool {
        !who.trimmingCharacters(in: .whitespaces).isEmpty && askedBy.trimmingCharacters(in: .whitespaces).lowercased() != who.trimmingCharacters(in: .whitespaces).lowercased()
    }
    /// Whole shillings as typed ("12,500"), or nil.
    static func parse(_ s: String) -> Int? {
        let x = s.replacingOccurrences(of: ",", with: "").replacingOccurrences(of: " ", with: "")
        guard !x.isEmpty, let n = Int(x), n >= 0 else { return nil }
        return n
    }
}

/// One cost entry (Office › Costs and the export).
struct CostRow: Identifiable {
    var id: String; var title: String; var at: String; var rid: String; var what: String; var est: Int?; var actual: Int; var by: String; var step: String; var receipt: Bool
    var key: String { id + at + String(actual) }
    static func from(_ o: JSON) -> CostRow {
        CostRow(id: o.str("id"), title: o.str("t"), at: o.str("at"), rid: o.str("rid"), what: o.str("what"), est: o["est"] == nil || o["est"] is NSNull ? nil : o.int("est"),
                actual: o.int("actual"), by: o.str("by"), step: o.str("step"), receipt: o.bool("receipt"))
    }
}

/// What /api/admin?view=costs answers.
struct CostView {
    struct Top { var id: String; var title: String; var ck: String; var sum: Int; var n: Int }
    var rows: [CostRow]; var month: Int; var year: Int; var sum12: Int; var repairs: Int; var avg: Int; var yearKey: Int
    var byCountry: [String: Int]; var top: [Top]; var approveAbove: Int
    static func from(_ o: JSON) -> CostView {
        let s = o.obj("stats") ?? [:]
        var bc: [String: Int] = [:]; (s.obj("byCountry") ?? [:]).keys.forEach { bc[$0] = s.obj("byCountry")?.int($0) ?? 0 }
        return CostView(rows: o.objs("rows").map(CostRow.from).sorted { $0.at > $1.at }, month: s.int("month"), year: s.int("year"), sum12: s.int("sum12"), repairs: s.int("repairs"),
                        avg: s.int("avg"), yearKey: s.int("yearKey"), byCountry: bc,
                        top: s.objs("top").map { Top(id: $0.str("id"), title: $0.str("t"), ck: $0.str("ck"), sum: $0.int("sum"), n: $0.int("n")) },
                        approveAbove: o["approveAbove"] != nil ? o.int("approveAbove") : Money.approveDefault)
    }
}

/// Settings › Display › Currency, the same as the web and Android apps. Costs are kept and typed in KES; in another
/// currency amounts show converted with "≈" and the KES amount next to them: "≈ $35 (KES 4,500)". Automatic follows
/// where the phone is (the server says), else the home country, else the phone's region. Rates from /api/fx.
enum Currency {
    struct Cur { let code: String; let en: String; let sw: String; let sym: String; let step: Double; let flag: String }
    static let list: [Cur] = [
        Cur(code: "USD", en: "US dollar", sw: "Dola ya Marekani", sym: "$", step: 1, flag: "🇺🇸"), Cur(code: "KES", en: "Kenyan shilling", sw: "Shilingi ya Kenya", sym: "KES ", step: 10, flag: "🇰🇪"),
        Cur(code: "UGX", en: "Ugandan shilling", sw: "Shilingi ya Uganda", sym: "UGX ", step: 1000, flag: "🇺🇬"), Cur(code: "TZS", en: "Tanzanian shilling", sw: "Shilingi ya Tanzania", sym: "TZS ", step: 1000, flag: "🇹🇿"),
        Cur(code: "MWK", en: "Malawian kwacha", sw: "Kwacha ya Malawi", sym: "MWK ", step: 100, flag: "🇲🇼"), Cur(code: "GBP", en: "British pound", sw: "Pauni ya Uingereza", sym: "£", step: 1, flag: "🇬🇧"),
        Cur(code: "EUR", en: "Euro", sw: "Yuro", sym: "€", step: 1, flag: "🇪🇺"), Cur(code: "CAD", en: "Canadian dollar", sw: "Dola ya Kanada", sym: "CA$", step: 1, flag: "🇨🇦"),
        Cur(code: "AUD", en: "Australian dollar", sw: "Dola ya Australia", sym: "A$", step: 1, flag: "🇦🇺"),
    ]
    private static let rough: [String: Double] = ["USD": 1, "KES": 129, "UGX": 3650, "TZS": 2600, "MWK": 1735, "GBP": 0.77, "EUR": 0.92, "CAD": 1.38, "AUD": 1.52]
    private static let euro = Set("AT BE CY DE EE ES FI FR GR HR IE IT LT LU LV MT NL PT SI SK".split(separator: " ").map(String.init))
    private static let d = UserDefaults.standard
    static var rates: [String: Double] = rough
    static var at = ""
    private static var country = ""
    static var home = ""
    private static var got: Double = 0

    static func forCountry(_ cc: String) -> String {
        switch cc.uppercased() {
        case "US": return "USD"; case "KE": return "KES"; case "UG": return "UGX"; case "TZ": return "TZS"; case "MW": return "MWK"; case "GB": return "GBP"; case "CA": return "CAD"; case "AU": return "AUD"
        default: return euro.contains(cc.uppercased()) ? "EUR" : ""
        }
    }
    static func name(_ c: String) -> String { list.first { $0.code == c }.map { Lang.sw ? $0.sw : $0.en } ?? c }
    static func flag(_ c: String) -> String { list.first { $0.code == c }?.flag ?? "" }
    /// The currency chosen in Settings ("" = automatic).
    static var picked: String {
        get { d.string(forKey: "currency") ?? "" }
        set { if list.contains(where: { $0.code == newValue }) { d.set(newValue, forKey: "currency") } else { d.removeObject(forKey: "currency") } }
    }
    /// The currency shown now.
    static var code: String {
        if !picked.isEmpty { return picked }
        if country.isEmpty, let j = d.dictionary(forKey: "fx") { country = (j["country"] as? String) ?? "" }
        let region = Locale.current.regionCode ?? ""
        return [country, home, region].map(forCountry).first { !$0.isEmpty } ?? "USD"
    }
    /// Fresh rates from the server, at most twice a day.
    static func refresh(_ server: String) async {
        if let j = d.dictionary(forKey: "fx"), rates.count == rough.count {
            if let r = j["rates"] as? [String: Double] { rates = rough.merging(r) { $1 } }
            at = (j["at"] as? String) ?? ""; country = (j["country"] as? String) ?? ""; got = (j["got"] as? Double) ?? 0
        }
        if !at.isEmpty && Date().timeIntervalSince1970 - got < 12 * 3600 { return }
        guard let u = URL(string: server + "/api/fx"), let res = try? await Api.net.data(from: u), (res.1 as? HTTPURLResponse)?.statusCode == 200,
              let j = (try? JSONSerialization.jsonObject(with: res.0)) as? JSON, let r = j["rates"] as? [String: Any] else { return }
        var nr: [String: Double] = [:]; r.forEach { k, v in if let n = (v as? NSNumber)?.doubleValue, n > 0 { nr[k] = n } }
        if !j.bool("fallback") { rates = rough.merging(nr) { $1 }; at = j.str("at") }
        if !j.str("country").isEmpty { country = j.str("country") }
        got = Date().timeIntervalSince1970
        d.set(["rates": rates, "at": at, "country": country, "got": got] as [String: Any], forKey: "fx")
    }
    private static func rate(_ c: String) -> Double { rates[c] ?? rough[c] ?? 1 }
    private static func round(_ v: Double, _ to: String) -> Double {
        var st = list.first { $0.code == to }?.step ?? 1
        if st > 1 && v < st * 10 { st = max(1, st / 10) }
        if v < 1 && to != "UGX" && to != "TZS" { return (v * 100).rounded() / 100 }
        return (v / st).rounded() * st
    }
    private static func group(_ v: Double) -> String { v > 0 && v < 1 ? String(format: "%.2f", v) : Geo.grouped(Int(v.rounded())) }
    /// An amount written in `from`, in the chosen currency: "$35", "UGX 128,000".
    static func fmt(_ v: Double, from: String = "KES", to: String? = nil) -> String {
        let t = to ?? code
        let x = from == t ? v : round(v / rate(from) * rate(t), t)
        return (list.first { $0.code == t }?.sym ?? t + " ") + group(x)
    }
    /// The rounded number only (for counters that count up).
    static func num(_ v: Int) -> Int { code == "KES" ? v : Int(round(Double(v) / rate("KES") * rate(code), code).rounded()) }
    /// A KES amount for the screen: "KES 4,500", or "≈ $35 (KES 4,500)" in another currency.
    static func kes(_ n: Int) -> String {
        let k = "KES\u{00A0}" + Geo.grouped(n)
        return code == "KES" ? k : "≈\u{00A0}" + fmt(Double(n)) + " (" + k + ")"
    }
    /// The small label in front of a money tile: "KES", or "≈ USD".
    static var tag: String { code == "KES" ? "KES" : "≈\u{00A0}" + code }
    /// Under a converted money tile: the KES amount as entered.
    static func kesSub(_ n: Int) -> String { code == "KES" ? "" : "KES\u{00A0}" + Geo.grouped(n) }
}

// --- kinds of project ----------------------------------------------------------------------------------------------

/// Kinds of project for the type filter.
let TGROUPS: [(String, String, String)] = [("water", "Water", "Maji"), ("school", "Schools", "Shule"), ("health", "Health", "Afya"), ("home", "Children’s homes", "Makao ya watoto"),
                                           ("farm", "Farming", "Kilimo"), ("other", "Other", "Nyingine")]
func tgroup(_ p: Project) -> String {
    let t = p.type.lowercased()
    if t.contains("borehole") || t.contains("collection") { return "water" }
    if t.contains("school") { return "school" }
    if t.contains("health") { return "health" }
    if t.contains("orphan") { return "home" }
    if t.contains("farm") { return "farm" }
    return "other"
}
func tgroupName(_ k: String) -> String { TGROUPS.first { $0.0 == k }.map { t($0.1, $0.2) } ?? k }
/// The icon and colour for a kind of project: water, school, clinic, children's home, farm, building site.
func typeLook(_ p: Project) -> (String, String) {
    let t = p.type.lowercased()
    if t.contains("borehole") || t.contains("collection") { return ("drop", "blue") }
    if p.status.lowercased().contains("under construction") { return ("brick", "amber") }
    if t.contains("school") { return ("school", "purple") }
    if t.contains("health") { return ("cross", "coral") }
    if t.contains("orphan") { return ("house", "teal") }
    if t.contains("farm") { return ("leaf", "green") }
    return ("house", "slate")
}
func isWaterPoint(_ p: Project) -> Bool { tgroup(p) == "water" }

/// A water point with no water test in 6 months (or never): "Water test due".
@MainActor func testDue(_ m: AppModel, _ p: Project) -> Bool {
    guard isWaterPoint(p), p.status == "Completed" || p.gps != nil else { return false }
    guard let at = m.sx.tests[p.id], let d = When.parse(at) else { return true }
    return Date().timeIntervalSince(d) / 86400 >= DUE_DAYS
}
@MainActor func testCount(_ m: AppModel, _ list: [Project]? = nil) -> Int { (list ?? m.snap?.projects ?? []).filter { $0.gps != nil && testDue(m, $0) }.count }

let COUNTRY_FLAG: [String: String] = ["ke": "🇰🇪", "tz": "🇹🇿", "ug": "🇺🇬", "mw": "🇲🇼"]
let COUNTRY_COLOR: [String: Color] = ["ke": Color(hex: 0x0A78C2), "tz": Color(hex: 0x1C9C5A), "ug": Color(hex: 0xE39A1E), "mw": Color(hex: 0x7A4FD0)]

/// CSV for Excel and Google Sheets.
func officeCsv(_ rows: [[Any?]]) -> String {
    rows.map { r in
        r.map { v -> String in
            var x = ""
            switch v { case nil: x = ""; case let s as String: x = s; case let n as Int: x = String(n); case let d as Double: x = d == d.rounded() ? String(Int(d)) : String(d); case let some?: x = "\(some)" }
            return x.contains(where: { $0 == "," || $0 == "\"" || $0 == "\n" }) ? "\"" + x.replacingOccurrences(of: "\"", with: "\"\"") + "\"" : x
        }.joined(separator: ",")
    }.joined(separator: "\r\n")
}
/// Writes a CSV (with a BOM so Excel reads the accents) and opens the share sheet.
func shareCsv(_ name: String, _ text: String) {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
    try? ("\u{FEFF}" + text).write(to: url, atomically: true, encoding: .utf8)
    Share.items([url])
}

extension Api {
    /// /api/admin?view=… with more query fields.
    func adminGet(_ view: String, _ q: [String: String]) async -> Reply {
        await get("/api/admin?view=" + Api.enc(view) + q.sorted { $0.key < $1.key }.map { "&" + Api.enc($0.key) + "=" + Api.enc($0.value) }.joined())
    }
    /// Office › Community: SMS reports from the community (/api/sms-admin).
    func smsAdmin() async -> Reply { await get("/api/sms-admin") }
    func smsAdminPost(_ body: JSON) async -> Reply { await postTo("/api/sms-admin", body) }
}

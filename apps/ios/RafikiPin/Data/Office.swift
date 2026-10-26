import Foundation

/// The second approval on a big repair (as /api/pin and /api/admin keep it on the job).
struct Approval: Equatable {
    var status: String; var rid: String; var by: String; var at: String; var est: Int; var dec = ""; var note = ""
    static func from(_ o: JSON?) -> Approval? {
        guard let o else { return nil }
        return Approval(status: o.str("status"), rid: o.str("rid"), by: o.str("by"), at: o.str("at"), est: o.int("est"), dec: o.str("dec"), note: o.str("note"))
    }
}

/// Repair money and the two-step approval: the same rules as lib/pin-office.js.
enum Money {
    static let APPROVE_DEFAULT = 50000
    private static let ORDER = ["reported", "assigned", "parts", "fixed"]

    /// "KES 4,500", with a non-breaking space so KES never sits alone at the end of a line; in another currency
    /// (Settings › Currency) "≈ $35 (KES 4,500)".
    static func kes(_ n: Int?) -> String { Currency.kes(n ?? 0) }
    /// The repair's estimate: the field estimate or the approved budget, whichever is higher.
    static func estOf(_ r: Repair) -> Int { max(r.cost ?? 0, r.budget ?? 0) }
    static func past(_ step: String, _ ref: String) -> Bool {
        (ORDER.firstIndex(of: step.isEmpty ? "reported" : step) ?? -1) >= (ORDER.firstIndex(of: ref) ?? 0)
    }
    /// "none" (small enough), "needed", "pending", "approved" or "declined".
    static func state(_ r: Repair, _ limit: Int) -> String {
        let est = estOf(r)
        if est <= limit { return "none" }
        if r.approval?.status == "pending" { return "pending" }
        if let a = r.approval, a.status == "approved" || a.status == "declined", a.est >= est { return a.status }
        return "needed"
    }
    /// Why [step] can't be set yet ("" = it can): parts ordered waits for the second approval.
    static func blocked(_ r: Repair, _ step: String, _ limit: Int) -> String {
        if !past(step, "parts") || past(r.step, "parts") { return "" }
        let s = state(r, limit)
        return s == "none" || s == "approved" ? "" : s
    }
    /// The person who asked can't approve their own request.
    static func canDecide(_ askedBy: String, _ who: String) -> Bool {
        !who.trimmingCharacters(in: .whitespaces).isEmpty && askedBy.trimmingCharacters(in: .whitespaces).lowercased() != who.trimmingCharacters(in: .whitespaces).lowercased()
    }
    /// Whole shillings as typed ("12,500"), or nil.
    static func parse(_ s: String) -> Int? {
        let c = s.replacingOccurrences(of: ",", with: "").replacingOccurrences(of: " ", with: "")
        guard !c.isEmpty, let n = Int(c), n >= 0 else { return nil }
        return n
    }
}

/// Office overview: people served now, by country and type, and the last 12 months (oldest first; nil = not known).
struct PeopleServed: Equatable {
    struct Month: Equatable { var m: String; var n: Int? }
    var total: Int; var counted: Int; var measured: Int; var byCountry: [String: Int]; var byType: [String: Int]; var trend: [Month]
    /// Growth since the first month known in the trend.
    var growth: Int { trend.first { $0.n != nil }?.n.map { total - $0 } ?? 0 }

    static func from(_ o: JSON?) -> PeopleServed? {
        guard let o else { return nil }
        func map(_ j: JSON?) -> [String: Int] { (j ?? [:]).reduce(into: [:]) { $0[$1.key] = (($1.value as? NSNumber)?.intValue) ?? Int(($1.value as? String) ?? "") ?? 0 } }
        return PeopleServed(total: o.int("total"), counted: o.int("counted"), measured: o.int("measured"), byCountry: map(o.obj("byCountry")), byType: map(o.obj("byType")),
                            trend: o.objs("trend").map { Month(m: $0.str("m"), n: $0["n"] is NSNull || $0["n"] == nil ? nil : $0.int("n")) })
    }
}

/// One cost entry (Office › Costs and the export).
struct CostRow: Identifiable, Equatable {
    var id: String { "\(pid)|\(at)|\(rid)" }
    var pid: String; var title: String; var at: String; var rid: String; var what: String; var est: Int?; var actual: Int; var by: String; var step: String; var receipt: Bool
    static func from(_ o: JSON) -> CostRow {
        CostRow(pid: o.str("id"), title: o.str("t"), at: o.str("at"), rid: o.str("rid"), what: o.str("what"),
                est: o["est"] == nil || o["est"] is NSNull ? nil : o.int("est"), actual: o.int("actual"), by: o.str("by"), step: o.str("step"), receipt: o.bool("receipt"))
    }
}

/// What /api/admin?view=costs answers.
struct CostView: Equatable {
    struct TopPoint: Equatable { var id: String; var title: String; var ck: String; var sum: Int; var n: Int }
    var rows: [CostRow]; var month: Int; var year: Int; var sum12: Int; var repairs: Int; var avg: Int; var yearKey: Int
    var byCountry: [String: Int]; var top: [TopPoint]; var approveAbove: Int

    static func from(_ o: JSON) -> CostView {
        let s = o.obj("stats") ?? [:]
        return CostView(
            rows: o.objs("rows").map(CostRow.from).sorted { $0.at > $1.at },
            month: s.int("month"), year: s.int("year"), sum12: s.int("sum12"), repairs: s.int("repairs"), avg: s.int("avg"), yearKey: s.int("yearKey"),
            byCountry: (s.obj("byCountry") ?? [:]).reduce(into: [:]) { $0[$1.key] = ($1.value as? NSNumber)?.intValue ?? 0 },
            top: s.objs("top").map { TopPoint(id: $0.str("id"), title: $0.str("t"), ck: $0.str("ck"), sum: $0.int("sum"), n: $0.int("n")) },
            approveAbove: o["approveAbove"] == nil ? Money.APPROVE_DEFAULT : o.int("approveAbove"))
    }
}

/// Settings › Display › Currency, the same as the web app (money.js) and Android (Currency.kt).
/// Costs are kept and typed in KES. When another currency is chosen, amounts show converted with "≈" and the KES
/// amount next to them: "≈ $35 (KES 4,500)". "Automatic" follows the country the phone is in: Kenya → KES,
/// USA → USD, Uganda → UGX, Tanzania → TZS, Malawi → MWK … Rates come from /api/fx once a day; rough ones until then.
enum Currency {
    struct Cur { let code: String; let en: String; let sw: String; let sym: String; let step: Double; let flag: String }
    static let list: [Cur] = [
        Cur(code: "USD", en: "US dollar", sw: "Dola ya Marekani", sym: "$", step: 1, flag: "🇺🇸"),
        Cur(code: "KES", en: "Kenyan shilling", sw: "Shilingi ya Kenya", sym: "KES\u{00A0}", step: 10, flag: "🇰🇪"),
        Cur(code: "UGX", en: "Ugandan shilling", sw: "Shilingi ya Uganda", sym: "UGX\u{00A0}", step: 1000, flag: "🇺🇬"),
        Cur(code: "TZS", en: "Tanzanian shilling", sw: "Shilingi ya Tanzania", sym: "TZS\u{00A0}", step: 1000, flag: "🇹🇿"),
        Cur(code: "MWK", en: "Malawian kwacha", sw: "Kwacha ya Malawi", sym: "MWK\u{00A0}", step: 100, flag: "🇲🇼"),
        Cur(code: "GBP", en: "British pound", sw: "Pauni ya Uingereza", sym: "£", step: 1, flag: "🇬🇧"),
        Cur(code: "EUR", en: "Euro", sw: "Yuro", sym: "€", step: 1, flag: "🇪🇺"),
        Cur(code: "CAD", en: "Canadian dollar", sw: "Dola ya Kanada", sym: "CA$", step: 1, flag: "🇨🇦"),
        Cur(code: "AUD", en: "Australian dollar", sw: "Dola ya Australia", sym: "A$", step: 1, flag: "🇦🇺"),
    ]
    private static let rough: [String: Double] = ["USD": 1, "KES": 129, "UGX": 3650, "TZS": 2600, "MWK": 1735, "GBP": 0.77, "EUR": 0.92, "CAD": 1.38, "AUD": 1.52]
    private static let euro: Set<String> = Set("AT BE CY DE EE ES FI FR GR HR IE IT LT LU LV MT NL PT SI SK".split(separator: " ").map(String.init))
    private static let d = UserDefaults.standard

    /// The currency shown now.
    static private(set) var code = "KES"
    /// true when chosen in Settings; false = automatic.
    static private(set) var picked = false
    static private(set) var rates: [String: Double] = rough
    /// The day the rates are from ("2026-10-26"), "" while only the rough ones are known.
    static private(set) var at = ""
    private static var country = ""
    private static var got: Double = 0

    static func cur(_ c: String) -> Cur? { list.first { $0.code == c } }
    static func name(_ c: String) -> String { cur(c).map { Lang.sw ? $0.sw : $0.en } ?? c }
    static func forCountry(_ cc: String) -> String {
        switch cc.uppercased() {
        case "US": return "USD"; case "KE": return "KES"; case "UG": return "UGX"; case "TZ": return "TZS"; case "MW": return "MWK"
        case "GB": return "GBP"; case "CA": return "CAD"; case "AU": return "AUD"
        default: return euro.contains(cc.uppercased()) ? "EUR" : ""
        }
    }
    private static func auto() -> String { let c = forCountry(country); return c.isEmpty ? "USD" : c }

    /// Called once when the app starts.
    static func start() {
        if let j = d.dictionary(forKey: "fx") {
            if let r = j["rates"] as? [String: Double] { rates = rough.merging(r.filter { $0.value > 0 }) { _, b in b } }
            at = j["at"] as? String ?? ""; country = j["country"] as? String ?? ""; got = j["got"] as? Double ?? 0
        }
        if country.isEmpty { country = Locale.current.regionCode ?? "" }
        let own = d.string(forKey: "currency") ?? ""
        picked = cur(own) != nil
        code = picked ? own : auto()
    }
    /// Pick a currency, or nil for automatic.
    static func set(_ c: String?) {
        if let c, cur(c) != nil { picked = true; code = c; d.set(c, forKey: "currency") }
        else { picked = false; code = auto(); d.removeObject(forKey: "currency") }
    }
    /// Fresh rates from the server, at most twice a day.
    static func refresh(server: String) async {
        if !at.isEmpty && Date().timeIntervalSince1970 - got < 12 * 3600 { return }
        guard let url = URL(string: server.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/api/fx") else { return }
        var req = URLRequest(url: url, timeoutInterval: 8); req.setValue("application/json", forHTTPHeaderField: "Accept")
        guard let got0 = try? await URLSession.shared.data(for: req), (got0.1 as? HTTPURLResponse)?.statusCode == 200,
              let j = try? JSONSerialization.jsonObject(with: got0.0) as? [String: Any], let r = j["rates"] as? [String: Double] else { return }
        await MainActor.run {
            if (j["fallback"] as? Bool) != true { rates = rough.merging(r.filter { $0.value > 0 }) { _, b in b }; at = j["at"] as? String ?? "" }
            if let c = j["country"] as? String, !c.isEmpty { country = c }
            got = Date().timeIntervalSince1970
            d.set(["rates": rates, "at": at, "country": country, "got": got] as [String: Any], forKey: "fx")
            if !picked { code = auto() }
        }
    }

    private static func rate(_ c: String) -> Double { rates[c] ?? rough[c] ?? 1 }
    private static func round(_ v: Double, _ to: String) -> Double {
        var st = cur(to)?.step ?? 1
        if st > 1 && v < st * 10 { st = max(1, st / 10) }
        if v < 1 && to != "UGX" && to != "TZS" { return (v * 100).rounded() / 100 }
        return (v / st).rounded() * st
    }
    private static func group(_ v: Double) -> String {
        if v > 0 && v < 1 { return String(format: "%.2f", v) }
        return Geo.grouped(Int(v.rounded()))
    }
    /// An amount written in [from], in the chosen currency: "$35", "UGX 128,000".
    static func fmt(_ v: Double, from: String = "KES", to: String? = nil) -> String {
        let to = to ?? code
        let x = from == to ? v : round(v / rate(from) * rate(to), to)
        return (cur(to)?.sym ?? to + " ") + group(x)
    }
    /// The rounded number only (for counters).
    static func num(_ v: Int, from: String = "KES") -> Int { from == code ? v : Int(round(Double(v) / rate(from) * rate(code), code)) }
    /// A KES amount for the screen: "KES 4,500", or "≈ $35 (KES 4,500)" in another currency.
    static func kes(_ n: Int) -> String {
        let k = "KES\u{00A0}" + Geo.grouped(n)
        return code == "KES" ? k : "≈\u{00A0}" + fmt(Double(n), from: "KES") + " (" + k + ")"
    }
}

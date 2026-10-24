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

    /// "KES 4,500", with a non-breaking space so KES never sits alone at the end of a line.
    static func kes(_ n: Int?) -> String { "KES\u{00A0}" + Geo.grouped(n ?? 0) }
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

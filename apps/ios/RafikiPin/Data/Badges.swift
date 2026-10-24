import Foundation

/// Badges (the stamps on Me): earned from real work in the team's activity feed, the same rules as the web and Android apps.
/// [color] is one of b (blue), g (green), c (coral), y (amber), p (purple).
struct BadgeDef: Equatable {
    var k: String; var color: String; var en: String; var sw: String; var howEn: String; var howSw: String; var unitEn: String; var unitSw: String; var goal: Int
    var title: String { t(en, sw) }
    var how: String { t(howEn, howSw) }
}

struct BadgeState: Identifiable, Equatable {
    var def: BadgeDef; var n: Int
    var id: String { def.k }
    var got: Bool { n >= def.goal }
    /// "7/10 visits" (or "Pin a location" for the first-time ones).
    var progress: String {
        if got { return t("Earned", "Umepata") }
        if def.goal == 1 { return def.how }
        let c = min(n, def.goal)
        return t("\(c)/\(def.goal) \(def.unitEn)", "\(def.unitSw) \(c)/\(def.goal)")
    }
    var fraction: Double { min(1, max(0, Double(n) / Double(def.goal))) }
}

enum Badges {
    static let ALL: [BadgeDef] = [
        BadgeDef(k: "pin1", color: "b", en: "First drop", sw: "Tone la kwanza", howEn: "Pin a location", howSw: "Weka mahali", unitEn: "pins", unitSw: "pini", goal: 1),
        BadgeDef(k: "visit1", color: "g", en: "First visit", sw: "Ziara ya kwanza", howEn: "Report a visit", howSw: "Ripoti ziara", unitEn: "visits", unitSw: "ziara", goal: 1),
        BadgeDef(k: "fixed1", color: "c", en: "Fixer", sw: "Mrekebishaji", howEn: "Mark a repair fixed", howSw: "Weka matengenezo kuwa yamekamilika", unitEn: "repairs", unitSw: "matengenezo", goal: 1),
        BadgeDef(k: "new1", color: "y", en: "Explorer", sw: "Mvumbuzi", howEn: "Add a new site", howSw: "Ongeza eneo jipya", unitEn: "sites", unitSw: "maeneo", goal: 1),
        BadgeDef(k: "pin10", color: "p", en: "Mapper", sw: "Mchora ramani", howEn: "Pin 10 locations", howSw: "Weka mahali 10", unitEn: "pins", unitSw: "pini", goal: 10),
        BadgeDef(k: "visit10", color: "g", en: "Regular", sw: "Mzoefu", howEn: "Report 10 visits", howSw: "Ripoti ziara 10", unitEn: "visits", unitSw: "ziara", goal: 10),
        BadgeDef(k: "month10", color: "b", en: "Busy month", sw: "Mwezi wa kazi", howEn: "10 visits this month", howSw: "Ziara 10 mwezi huu", unitEn: "visits", unitSw: "ziara", goal: 10),
        BadgeDef(k: "streak5", color: "y", en: "On a roll", sw: "Mfululizo", howEn: "Work 5 days in a row", howSw: "Fanya kazi siku 5 mfululizo", unitEn: "days", unitSw: "siku", goal: 5),
        BadgeDef(k: "countries4", color: "p", en: "Four countries", sw: "Nchi nne", howEn: "Visit sites in all 4 countries", howSw: "Tembelea maeneo katika nchi zote 4", unitEn: "countries", unitSw: "nchi", goal: 4),
        BadgeDef(k: "water3", color: "b", en: "Water tester", sw: "Mpimaji wa maji", howEn: "Test the water on 3 visits", howSw: "Pima maji katika ziara 3", unitEn: "tests", unitSw: "vipimo", goal: 3),
        BadgeDef(k: "story3", color: "c", en: "Story collector", sw: "Mkusanyaji wa hadithi", howEn: "Collect 3 story moments", howSw: "Kusanya hadithi 3", unitEn: "stories", unitSw: "hadithi", goal: 3),
        BadgeDef(k: "pin100", color: "g", en: "100 pins", sw: "Pini 100", howEn: "Pin 100 locations", howSw: "Weka mahali 100", unitEn: "pins", unitSw: "pini", goal: 100),
    ]
    private static let WORK: Set<String> = ["pin", "visit", "fixed", "new"]

    /// Longest run of days in a row.
    static func bestRun(_ days: [Date]) -> Int {
        let cal = Calendar.current
        let s = Array(Set(days.map { cal.startOfDay(for: $0) })).sorted()
        var best = 0, run = 0; var prev: Date?
        for d in s { run = prev.flatMap { cal.date(byAdding: .day, value: 1, to: $0) } == d ? run + 1 : 1; best = max(best, run); prev = d }
        return best
    }

    private static func truthy(_ v: Any?) -> Bool {
        if let b = v as? Bool { return b }
        if let n = v as? NSNumber { return n.intValue != 0 }
        if let s = v as? String { return s == "1" || s == "true" }
        return false
    }

    /// Progress on every badge for [me], from the activity feed. [ckOf] maps a project id to its country.
    static func compute(_ act: [JSON], me: String, ckOf: (String) -> String?, now: Date = Date()) -> [BadgeState] {
        let who = me.trimmingCharacters(in: .whitespaces).lowercased()
        let mine = who.isEmpty ? [] : act.filter { $0.str("by").trimmingCharacters(in: .whitespaces).lowercased() == who }
        func count(_ a: String) -> Int { mine.filter { $0.str("a") == a }.count }
        let visits = mine.filter { $0.str("a") == "visit" }
        let cal = Calendar.current
        let monthStart = cal.date(from: cal.dateComponents([.year, .month], from: now)) ?? now
        func day(_ e: JSON) -> Date? { When.parse(e.str("at")) }
        let keys = Set(COUNTRIES.map(\.0))
        let n: [String: Int] = [
            "pin1": count("pin"), "visit1": visits.count, "fixed1": count("fixed"), "new1": count("new"),
            "pin10": count("pin"), "visit10": visits.count, "pin100": count("pin"),
            "month10": visits.filter { day($0).map { $0 >= monthStart } == true }.count,
            "streak5": bestRun(mine.filter { WORK.contains($0.str("a")) }.compactMap(day)),
            "countries4": Set(visits.compactMap { ckOf($0.str("id")) }.filter { keys.contains($0) }).count,
            "water3": visits.filter { truthy($0["wq"]) }.count,
            "story3": visits.filter { truthy($0["st"]) }.count,
        ]
        return ALL.map { BadgeState(def: $0, n: n[$0.k] ?? 0) }
    }

    /// Which badges are new since last time. [seen] is nil the first time (then nothing celebrates).
    static func fresh(_ states: [BadgeState], seen: Set<String>?) -> [BadgeState] {
        guard let seen else { return [] }
        return states.filter { $0.got && !seen.contains($0.def.k) }
    }

    /// The badges this phone has already shown, per person, so each one celebrates once.
    static func seen(_ who: String) -> Set<String>? {
        let k = "badges:" + who.trimmingCharacters(in: .whitespaces).lowercased()
        return (UserDefaults.standard.array(forKey: k) as? [String]).map(Set.init)
    }
    static func save(_ who: String, _ keys: Set<String>) {
        UserDefaults.standard.set(Array(keys), forKey: "badges:" + who.trimmingCharacters(in: .whitespaces).lowercased())
    }
}

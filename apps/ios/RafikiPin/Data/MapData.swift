import Foundation
import SwiftUI
import UIKit

// The home map's logic: what each pin's colour says, the filters, today's route, the years and the heat.
// The same as Android's MapHome.kt, MapPins.kt and MapLayers.kt (and the web app).

/// What a pin's colour says: working, needs repair, not working, or not visited for 6 months (or never).
enum PinCond { static let ok = "ok", repair = "repair", bad = "bad", old = "old" }

/// An open repair says how it is; otherwise a visit in the last 6 months means it was seen working; none is grey.
func mapCondition(_ s: Snapshot, _ p: Project, now: Date = Date()) -> String {
    if let r = s.repairs[p.id] { return r.state == "notworking" ? PinCond.bad : PinCond.repair }
    guard let v = s.visits[p.id], let at = When.parse(v.at) else { return PinCond.old }
    return now.timeIntervalSince(at) / 86400 >= DUE_DAYS ? PinCond.old : PinCond.ok
}
func condRank(_ c: String) -> Int { c == PinCond.bad ? 3 : c == PinCond.repair ? 2 : c == PinCond.old ? 1 : 0 }
/// Deep enough for white numbers on top, with a white edge that reads on satellite photos.
func condUIColor(_ c: String) -> UIColor {
    switch c {
    case PinCond.bad: return UIColor(red: 0xB3 / 255, green: 0x26 / 255, blue: 0x1E / 255, alpha: 1)
    case PinCond.repair: return UIColor(red: 0xB8 / 255, green: 0x6E / 255, blue: 0, alpha: 1)
    case PinCond.old: return UIColor(red: 0x66 / 255, green: 0x72 / 255, blue: 0x7C / 255, alpha: 1)
    default: return UIColor(red: 0x1C / 255, green: 0x7C / 255, blue: 0x4A / 255, alpha: 1)
    }
}
func condName(_ c: String) -> String {
    switch c {
    case PinCond.bad: return t("Not working", "Haifanyi kazi")
    case PinCond.repair: return t("Needs repair", "Inahitaji matengenezo")
    case PinCond.old: return t("No visit in 6 months", "Bila ziara miezi 6")
    default: return t("Working", "Inafanya kazi")
    }
}

/// The kinds of project, as the Projects list groups them.
let TGROUPS: [(String, String, String)] = [("water", "Water", "Maji"), ("school", "Schools", "Shule"), ("health", "Health", "Afya"), ("home", "Children’s homes", "Makao ya watoto"), ("farm", "Farming", "Kilimo"), ("other", "Other", "Nyingine")]
func tgroup(_ p: Project) -> String {
    let x = p.type.lowercased()
    if x.contains("borehole") || x.contains("collection") { return "water" }
    if x.contains("school") { return "school" }
    if x.contains("health") { return "health" }
    if x.contains("orphan") { return "home" }
    if x.contains("farm") { return "farm" }
    return "other"
}
/// The icon and colour for a kind of project: water, school, clinic, children's home, farm, building site.
func typeLook(_ p: Project) -> (String, String) {
    let x = p.type.lowercased()
    if x.contains("borehole") || x.contains("collection") { return ("drop", "blue") }
    if p.status.lowercased().contains("under construction") { return ("brick", "amber") }
    if x.contains("school") { return ("school", "purple") }
    if x.contains("health") { return ("cross", "coral") }
    if x.contains("orphan") { return ("house", "teal") }
    if x.contains("farm") { return ("leaf", "green") }
    return ("house", "slate")
}

/// The filter chips over the map.
let MAP_STATUS: [(String, String, String, String)] = [("broken", "Broken", "Zimeharibika", "alert"), ("due", "Due a visit", "Inasubiri ziara", "cal"), ("mine", "My assigned visits", "Ziara nilizopewa", "person")]
let MAP_TYPES: [(String, String, String)] = [("water", "drop", "blue"), ("school", "school", "purple"), ("health", "cross", "coral"), ("home", "house", "teal"), ("farm", "leaf", "green")]
func mapStatusOk(_ s: Snapshot, _ p: Project, _ k: String) -> Bool {
    switch k {
    case "broken": return s.repairs[p.id] != nil
    case "due": return ageDays(s, p) >= DUE_DAYS || testDue(s, p)
    case "mine": return s.assigned.contains { $0.id == p.id } || s.jobs.contains(p.id)
    default: return true
    }
}
func statusHue(_ k: String) -> Hue { k == "broken" ? Hue(fg: C.bad, bg: C.badBg) : k == "due" ? C.hue("due") : C.named("blue") }

/// A name short enough to sit under a pin: whole words only, "…" when some are left out.
func shortName(_ title: String, max: Int = 20) -> String {
    let s = title.trimmingCharacters(in: .whitespaces)
    if s.count <= max { return s }
    var b = ""
    for w in s.split(whereSeparator: { $0 == " " }) {
        if !b.isEmpty && b.count + 1 + w.count > max - 1 { break }
        if !b.isEmpty { b += " " }
        b += w
    }
    return b.trimmingCharacters(in: CharacterSet(charactersIn: ",·-– ")) + "…"
}

/// Nearest to you with a filter on and nothing of that kind.
func nearNone(_ ty: String, _ status: String) -> String {
    if !status.isEmpty { return t("Nothing near you matches these filters", "Hakuna kilicho karibu nawe kinacholingana na vichujio hivi") }
    switch ty {
    case "water": return t("No water points near you", "Hakuna vituo vya maji karibu nawe")
    case "school": return t("No schools near you", "Hakuna shule karibu nawe")
    case "health": return t("No health centres near you", "Hakuna vituo vya afya karibu nawe")
    case "home": return t("No children’s homes near you", "Hakuna makao ya watoto karibu nawe")
    case "farm": return t("No farms near you", "Hakuna mashamba karibu nawe")
    default: return t("No projects near you", "Hakuna miradi karibu nawe")
    }
}

/// A project's pin on the home map.
struct HomePin: Equatable { var id: String; var ll: LatLng; var cond: String; var type = "drop"; var due = false; var name = "" }

// MARK: - today's route, the years, the heat

/// A visit was sent for this project today: its stop is ticked off.
func visitedToday(_ s: Snapshot, _ id: String) -> Bool {
    guard let v = s.visits[id], let d = When.parse(v.at) else { return false }
    return Calendar.current.isDateInToday(d)
}

/// Today's stops in driving order from where you are, kept for the day so the numbers don't change as you drive.
func dayStops(_ s: Snapshot, _ here: LatLng?) -> [Project] {
    let d = When.localDay(), ud = UserDefaults.standard
    let mine = Array(NSOrderedSet(array: s.assigned.filter { $0.date == d }.map(\.id))) as? [String] ?? []
    let had = ud.string(forKey: "map.day") == d ? (ud.string(forKey: "map.ids") ?? "").split(separator: ",").map(String.init) : []
    let keep = had.filter { mine.contains($0) || visitedToday(s, $0) }
    let add = mine.filter { !keep.contains($0) }
    var ids = had
    if !add.isEmpty || keep.count != had.count {
        let done = keep.filter { visitedToday(s, $0) }
        let from = done.last.flatMap { id in s.projects.first { $0.id == id }?.gps } ?? here
        let rest = orderStops((keep.filter { !done.contains($0) } + add).compactMap { id in s.projects.first { $0.id == id } }, from: from).map(\.id)
        ids = done + rest + (keep + add).filter { !done.contains($0) && !rest.contains($0) }
        ud.set(d, forKey: "map.day"); ud.set(ids.joined(separator: ","), forKey: "map.ids")
    }
    return ids.compactMap { id in s.projects.first { $0.id == id && $0.gps != nil } }
}

/// The completion years on the map: first and last.
func yearSpan(_ s: Snapshot) -> ClosedRange<Int>? {
    let ys = s.projects.filter { $0.gps != nil && $0.status == "Completed" && $0.year != nil }.map { $0.year! }
    guard let a = ys.min(), let b = ys.max() else { return nil }
    return a...b
}
/// Projects completed by the end of a year.
func doneBy(_ s: Snapshot, _ y: Int) -> [Project] { s.projects.filter { $0.gps != nil && $0.status == "Completed" && ($0.year ?? Int.max) <= y } }

/// Heat points: open repairs (not working counts double, urgent half as much again) or people served.
func heatPoints(_ s: Snapshot, _ kind: String, year: Int? = nil) -> [(LatLng, Double)] {
    s.projects.compactMap { p in
        guard let g = p.gps else { return nil }
        if let y = year, !(p.status == "Completed" && (p.year ?? Int.max) <= y) { return nil }
        switch kind {
        case "repairs": return s.repairs[p.id].map { r in (g, (r.state == "notworking" ? 2.0 : 1.0) * (r.urgency == "urgent" ? 1.5 : 1.0)) }
        case "people": return (p.people ?? 0) > 0 ? (g, Double(p.people!)) : nil
        default: return nil
        }
    }
}
let HEAT_COLORS: [String: [UInt32]] = ["repairs": [0xFFD64A, 0xF28C28, 0xD7301F, 0x8C0000], "people": [0x9AD8F5, 0x3EA7EE, 0x0A5FB0, 0x3B1E8F]]

/// Someone sharing their location, and today's visits (admins: /api/admin?view=teammap).
struct Sharer: Equatable { var name: String; var ll: LatLng; var acc: Int; var at: String; var phone: String; var home: String }
struct DayVisit: Equatable { var id: String; var by: String; var at: String; var state: String; var title: String }
struct TeamMapData: Equatable {
    var people: [Sharer]; var visits: [DayVisit]
    static func from(_ o: JSON) -> TeamMapData {
        TeamMapData(
            people: o.objs("people").compactMap { j in
                guard let la = j.num("lat"), let ln = j.num("lng") else { return nil }
                return Sharer(name: j.str("name"), ll: LatLng(lat: la, lng: ln), acc: j.int("acc"), at: j.str("at"), phone: j.str("phone"), home: j.str("home"))
            },
            visits: o.objs("visits").map { DayVisit(id: $0.str("id"), by: $0.str("by"), at: $0.str("at"), state: $0.str("s"), title: $0.str("t")) })
    }
}
func minutesSince(_ iso: String) -> Double { When.parse(iso).map { Date().timeIntervalSince($0) / 60 } ?? 0 }

/// What the home map shows on top of the pins: kept while you move between tabs. Admin layers start off.
final class HomeLayers: ObservableObject {
    static let s = HomeLayers()
    @Published var route = UserDefaults.standard.object(forKey: "map.route") as? Bool ?? true { didSet { UserDefaults.standard.set(route, forKey: "map.route") } }
    @Published var heat = ""
    @Published var years = false
    @Published var year = 0
    @Published var playing = false
    @Published var team = false
    @Published var follow = false
    @Published var pick: String?
    @Published var tm: TeamMapData?
    @Published var tmErr: String?
    /// Calm by default: a tap on empty map reveals the chips, the pinned bar, Nearest to you and the Key.
    @Published var revealed = false
    @Published var sat = UserDefaults.standard.bool(forKey: "map.sat") { didSet { UserDefaults.standard.set(sat, forKey: "map.sat") } }
    var dropped = false
}

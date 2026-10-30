import Foundation

// Field trips (Android 2.23/2.24 feat-itina), as /api/itin sends them. The server orders the stops, splits them across
// days, prices the trip and writes the warnings. The phone keeps the record it was last sent, so moving a stop, locking a
// time or skipping one re-times the day at once (TripTimeline, the contract's schedule rule), with or without signal; the
// change is then sent (TripRepo) and the server's answer replaces the local copy.

/// Minutes after midnight <-> "07:30".
enum TripHm {
    static func parse(_ s: String?) -> Int? {
        guard let s = s?.trimmingCharacters(in: .whitespaces) else { return nil }
        let p = s.split(separator: ":")
        guard p.count >= 2, let h = Int(p[0]), let m = Int(p[1].prefix(2)), h < 48, m < 60 else { return nil }
        return h * 60 + m
    }
    static func text(_ min: Int) -> String { let m = ((min % 1440) + 1440) % 1440; return String(format: "%02d:%02d", m / 60, m % 60) }
    /// "1 h 52", "45 min".
    static func dur(_ min: Int) -> String {
        if min < 60 { return t("\(min) min", "dak \(min)") }
        if min % 60 == 0 { return t("\(min / 60) h", "saa \(min / 60)") }
        let mm = String(format: "%02d", min % 60)
        return t("\(min / 60) h \(mm)", "saa \(min / 60) dak \(mm)")
    }
}

func tripLatLng(_ o: JSON) -> LatLng? {
    if let a = o.num("lat"), let b = o.num("lng") { return LatLng(lat: a, lng: b) }
    if let a = o["ll"] as? [Any], a.count == 2, let x = (a[0] as? NSNumber)?.doubleValue, let y = (a[1] as? NSNumber)?.doubleValue { return LatLng(lat: x, lng: y) }
    return nil
}
/// Words the server sends in both languages ({en, sw}), in the app's language.
func tripWords(_ o: Any?) -> String {
    if let j = o as? JSON { let en = chatS(j, "en"), sw = chatS(j, "sw"); return tripUnbreak(t(en, sw.isEmpty ? en : sw)) }
    if let s = o as? String, s != "null" { return s }
    return ""
}
/// Keeps "L/100 km" and "15 Oct–14 Nov" together on one line.
func tripUnbreak(_ s: String) -> String { s.replacingOccurrences(of: "L/100 km", with: "L/\u{2060}100\u{00A0}km").replacingOccurrences(of: "–", with: "\u{2060}–\u{2060}") }
/// "KES 4,250".
func tripKes(_ n: Int) -> String { "KES " + Geo.grouped(n) }
func tripKm(_ km: Double) -> String { Geo.grouped(Int(km.rounded())) + " km" }

/// When a site is open: a school 08:00–15:30, a clinic its hours.
struct TripWindow: Equatable { var open: Int?; var close: Int? }

struct TripPoint: Equatable {
    var kind: String; var name: String; var ll: LatLng?
    static func from(_ o: JSON?) -> TripPoint? { guard let o else { return nil }; return TripPoint(kind: chatS(o, "kind"), name: chatS(o, "name"), ll: tripLatLng(o)) }
}

/// One stop (a project or any place).
struct TripStop: Equatable {
    var sid: String; var pid: String; var name: String; var ll: LatLng?; var type: String; var visitMin: Int
    var window: TripWindow?; var fixed: Int?; var locked: Bool; var day: Int; var why: [String]
    /// planned, done, late, skipped, moved.
    var status: String; var checkinAt: String; var checkinBy: String; var note: String
    var done: Bool { status == "done" || (status == "late" && !checkinAt.isEmpty) }
    var open: Bool { status != "done" && status != "skipped" && checkinAt.isEmpty }
    static func from(_ o: JSON) -> TripStop {
        let ci = o.obj("checkin")
        var win: TripWindow? = nil
        if let w = o.obj("window") { let x = TripWindow(open: TripHm.parse(chatS(w, "open")), close: TripHm.parse(chatS(w, "close"))); if x.open != nil || x.close != nil { win = x } }
        let vm = o["visitMin"] == nil ? 45 : o.int("visitMin")
        let st = chatS(o, "status")
        return TripStop(sid: chatS(o, "sid"), pid: chatS(o, "id"), name: chatS(o, "name"), ll: tripLatLng(o), type: chatS(o, "type"), visitMin: min(720, max(5, vm)),
                        window: win, fixed: TripHm.parse(chatS(o, "fixed")), locked: o.bool("locked"), day: o.int("day"),
                        why: (o.arr("why") ?? []).map { tripWords($0) }.filter { !$0.isEmpty }, status: st.isEmpty ? "planned" : st,
                        checkinAt: ci.map { chatS($0, "at") } ?? "", checkinBy: ci.map { chatS($0, "by") } ?? "", note: chatS(o, "note"))
    }
}

/// A warning, with the one-tap fix the server offers ("Swap with Tuesday?").
struct TripWarn: Identifiable {
    var code: String; var level: String; var text: String; var sid = ""; var leg = ""; var fix: JSON? = nil
    var id: String { code + "|" + sid + "|" + leg + "|" + text }
    static func from(_ o: JSON) -> TripWarn {
        let l = chatS(o, "level")
        return TripWarn(code: chatS(o, "code"), level: l.isEmpty ? "caution" : l, text: tripWords(o), sid: chatS(o, "sid"), leg: chatS(o, "leg"), fix: o.obj("fix"))
    }
}

/// Tonight's town and lodging (a multi-day trip).
struct TripNight: Equatable {
    var town: String; var ll: LatLng?; var suggested: Bool; var why: String; var lodging: String; var phone: String; var kes: Int; var note: String
    var key: String { "T:" + town }
    static func from(_ o: JSON?) -> TripNight? {
        guard let o, !chatS(o, "town").isEmpty else { return nil }
        let l = o.obj("lodging") ?? [:]
        return TripNight(town: chatS(o, "town"), ll: tripLatLng(o), suggested: o.bool("suggested"), why: tripWords(o["why"]), lodging: chatS(l, "name"),
                         phone: chatS(l, "phone"), kes: Int(l.num("kes") ?? 0), note: chatS(l, "note"))
    }
}

struct TripDay {
    var date: String; var stops: [String]; var night: TripNight?; var km: Double; var driveMin: Int; var risk: String; var warnings: [TripWarn]
    var startKey: String; var endKey: String
    static func from(_ o: JSON) -> TripDay {
        let r = chatS(o, "riskLevel")
        return TripDay(date: chatS(o, "date"), stops: chatStrings(o["stops"]), night: TripNight.from(o.obj("overnight")), km: o.num("km") ?? 0, driveMin: o.int("driveMin"),
                       risk: r.isEmpty ? "ok" : r, warnings: o.objs("warnings").map(TripWarn.from),
                       startKey: o.obj("startAt").map { chatS($0, "key") } ?? "", endKey: o.obj("endAt").map { chatS($0, "key") } ?? "")
    }
}

/// One leg from the trip engine: km, time, tarmac/murram, rain, risk.
struct TripLeg {
    var km: Double; var min: Int; var src: String; var roads: Bool; var surface: String; var rain: String; var time: String; var risk: String; var roadLevel: String
    var geometry: [LatLng] = []
    var estimate: Bool { src == "estimate" }
    static func from(_ o: JSON) -> TripLeg {
        let ch = o.obj("chips") ?? [:]
        let g: [LatLng] = ((o["geometry"] as? [Any]) ?? []).compactMap { p in
            guard let a = p as? [Any], a.count >= 2, let x = (a[0] as? NSNumber)?.doubleValue, let y = (a[1] as? NSNumber)?.doubleValue else { return nil }
            return LatLng(lat: x, lng: y)
        }
        let s = chatS(o, "src"), r = chatS(o, "riskLevel")
        return TripLeg(km: o.num("km") ?? 0, min: o.int("min"), src: s.isEmpty ? "trip" : s, roads: o["roads"] == nil ? true : o.bool("roads"),
                       surface: tripWords(ch["surface"]), rain: tripWords(ch["rain"]), time: tripWords(ch["time"]), risk: r.isEmpty ? "ok" : r,
                       roadLevel: chatS(o, "roadLevel"), geometry: g)
    }
    /// Neither the leg nor the matrix has it: the straight line × 1.4 at 45 km/h (the schedule rule).
    static func guess(_ a: LatLng, _ b: LatLng) -> TripLeg {
        let km = Geo.metres(a, b) / 1000 * 1.4
        let mn = max(3, Int((km / 45 * 60).rounded()))
        return TripLeg(km: km, min: mn, src: "estimate", roads: false, surface: "", rain: "", time: "~" + TripHm.dur(mn), risk: "ok", roadLevel: "")
    }
}

struct TripPack: Identifiable { var key: String; var text: String; var done: Bool; var auto: Bool; var id: String { key } }

/// An extra cost: tolls, parking, lodging, lunch or other; `actual` once paid.
struct TripExtra: Identifiable {
    var xid: String; var day: Int?; var kind: String; var label: String; var kes: Int; var actual: Bool; var receipt: String?
    var id: String { xid }
    func json() -> JSON { ["xid": xid, "kind": kind, "label": label, "kes": kes, "actual": actual, "day": day.map { $0 as Any } ?? NSNull(), "receipt": receipt.map { $0 as Any } ?? NSNull()] }
    static func from(_ o: JSON) -> TripExtra {
        let k = chatS(o, "kind"), r = chatS(o, "receipt")
        return TripExtra(xid: chatS(o, "xid"), day: o["day"] == nil || o["day"] is NSNull ? nil : o.int("day"), kind: k.isEmpty ? "other" : k, label: chatS(o, "label"),
                         kes: Int(o.num("kes") ?? 0), actual: o.bool("actual"), receipt: r.isEmpty ? nil : r)
    }
    static func kindName(_ k: String) -> String {
        switch k {
        case "toll": return t("Toll", "Ushuru wa barabara"); case "parking": return t("Parking", "Maegesho"); case "lodging": return t("Lodging", "Malazi")
        case "lunch": return t("Lunch", "Chakula cha mchana"); default: return t("Other", "Nyingine")
        }
    }
}

/// The trip's costs, as the server worked them out (always KES).
struct TripCost {
    var fuelLabel: String; var fuelKes: Int?; var litres: Double?; var lPer100: Double?; var basis: String; var days: [JSON]; var est: JSON; var actual: JSON?
    var estTotal: Int { Int(est.num("totalKes") ?? 0) }
    var actualTotal: Int? { actual?.num("totalKes").map { Int($0) } }
    var fuelMissing: Bool { est.bool("fuelMissing") || fuelKes == nil }
    static func from(_ o: JSON?) -> TripCost? {
        guard let c = o else { return nil }
        let f = c.obj("fuel") ?? [:]
        return TripCost(fuelLabel: tripWords(f["label"]), fuelKes: f.num("kes").map { Int($0) }, litres: f.num("litres"), lPer100: f.num("lPer100"), basis: tripWords(f["basis"]),
                        days: c.objs("days"), est: c.obj("est") ?? [:], actual: c.obj("actual"))
    }
}

/// Live progress for the office: done, late, skipped per stop, and where people are (when sharing their location).
struct TripProgress {
    var day: Int; var now: String; var stops: [String: JSON]; var where_: [(String, LatLng, String)]
    static func from(_ o: JSON?) -> TripProgress? {
        guard let p = o else { return nil }
        var st: [String: JSON] = [:]; p.objs("stops").forEach { st[chatS($0, "sid")] = $0 }
        let w: [(String, LatLng, String)] = p.objs("where").compactMap { x in tripLatLng(x).map { (chatS(x, "name"), $0, chatS(x, "at")) } }
        return TripProgress(day: p.int("day"), now: chatS(p, "now"), stops: st, where_: w)
    }
}

/// A field trip: the record as the server keeps it (`raw`) and the parts the screens use.
struct TripItin {
    let raw: JSON
    var canEdit: Bool
    var waiting: Int
    let id: String; let v: Int; let title: String; let status: String; let owner: String; let people: [String]; let viewers: [String]
    let ck: String; let from: String; let to: String; let start: TripPoint?; let end: TripPoint?
    let dayStart: Int; let dayEnd: Int; let lunchAt: Int; let lunchMin: Int; let lunchSkip: Bool
    let vehicleId: String; let stops: [TripStop]; let days: [TripDay]; let unplanned: [String]; let legs: [String: TripLeg]
    let cost: TripCost?; let packing: [TripPack]; let extras: [TripExtra]; let shareUrl: String; let warnings: [TripWarn]; let summary: JSON?
    private let matrixKeys: [String]; private let matrixMin: [Any]; private let matrixKm: [Any]
    private let byId: [String: TripStop]

    init(_ raw: JSON, canEdit: Bool = true, waiting: Int = 0) {
        self.raw = raw; self.canEdit = canEdit; self.waiting = waiting
        id = chatS(raw, "id"); v = raw.int("v"); title = chatS(raw, "title")
        let st = chatS(raw, "status"); status = st.isEmpty ? "planned" : st
        owner = chatS(raw, "owner"); people = chatStrings(raw["people"]); viewers = chatStrings(raw["viewers"])
        ck = chatS(raw, "ck"); from = chatS(raw, "from"); to = chatS(raw, "to")
        start = TripPoint.from(raw.obj("start")); end = TripPoint.from(raw.obj("end"))
        dayStart = TripHm.parse(raw.obj("hours").map { chatS($0, "start") }) ?? 450
        dayEnd = TripHm.parse(raw.obj("hours").map { chatS($0, "end") }) ?? 1050
        lunchAt = TripHm.parse(raw.obj("lunch").map { chatS($0, "at") }) ?? 750
        lunchMin = raw.obj("lunch").flatMap { $0["min"] == nil ? nil : $0.int("min") } ?? 45
        lunchSkip = raw.obj("lunch")?.bool("skip") ?? false
        vehicleId = chatS(raw, "vehicleId")
        stops = raw.objs("stops").map(TripStop.from)
        days = raw.objs("days").map(TripDay.from)
        unplanned = chatStrings(raw["unplanned"])
        var lg: [String: TripLeg] = [:]; (raw.obj("legs") ?? [:]).forEach { k, v in if let o = v as? JSON { lg[k] = TripLeg.from(o) } }
        legs = lg
        cost = TripCost.from(raw.obj("cost"))
        packing = raw.objs("packing").filter { !$0.bool("removed") }.map { o in
            let k = chatS(o, "key")
            return TripPack(key: k, text: tripWords(o), done: o.bool("done"), auto: o.bool("auto") || k.hasPrefix("auto:") || k.hasPrefix("std:"))
        }
        extras = raw.objs("extras").map(TripExtra.from)
        shareUrl = raw.obj("share").map { chatS($0, "url") } ?? ""
        warnings = raw.objs("warnings").map(TripWarn.from)
        summary = raw.obj("summary")
        let mx = raw.obj("matrix") ?? [:]
        matrixKeys = chatStrings(mx["keys"]); matrixMin = mx.arr("min") ?? []; matrixKm = mx.arr("km") ?? []
        var b: [String: TripStop] = [:]; stops.forEach { b[$0.sid] = $0 }; byId = b
    }

    func with(raw r: JSON, waiting w: Int) -> TripItin { TripItin(r, canEdit: canEdit, waiting: w) }
    func stop(_ sid: String) -> TripStop? { byId[sid] }
    func dayStops(_ i: Int) -> [TripStop] { i < days.count ? days[i].stops.compactMap { stop($0) } : [] }
    func isOn(_ name: String) -> Bool { !name.isEmpty && (chatSame(owner, name) || people.contains { chatSame($0, name) }) }

    /// Where a point key is: S start, E end, a stop's sid, T:<town>.
    func place(_ key: String) -> LatLng? {
        if key == "S" { return start?.ll }
        if key == "E" { if let e = end, e.kind != "same", let ll = e.ll { return ll }; return start?.ll }
        if key.hasPrefix("T:") { return days.first { $0.night?.key == key }?.night?.ll }
        return stop(key)?.ll
    }
    func placeName(_ key: String) -> String {
        if key == "S" { return start?.name ?? "" }
        if key == "E" { if let e = end, e.kind != "same", !e.name.isEmpty { return e.name }; return start?.name ?? "" }
        if key.hasPrefix("T:") { return String(key.dropFirst(2)) }
        return stop(key)?.name ?? ""
    }
    /// The leg a→b: the trip engine's, else the matrix, else a straight-line estimate.
    func leg(_ a: String, _ b: String) -> TripLeg? {
        if let l = legs[a + ">" + b] { return l }
        if let l = legs[b + ">" + a] { return l }
        if let i = matrixKeys.firstIndex(of: a), let j = matrixKeys.firstIndex(of: b), i < matrixMin.count,
           let row = matrixMin[i] as? [Any], j < row.count, let mn = (row[j] as? NSNumber)?.doubleValue {
            var km = 0.0
            if i < matrixKm.count, let kr = matrixKm[i] as? [Any], j < kr.count, let k = (kr[j] as? NSNumber)?.doubleValue { km = k }
            let m = Int(mn.rounded())
            return TripLeg(km: km, min: m, src: "matrix", roads: false, surface: "", rain: "", time: "~" + TripHm.dur(m), risk: "ok", roadLevel: "")
        }
        guard let pa = place(a), let pb = place(b) else { return nil }
        return TripLeg.guess(pa, pb)
    }
    /// Where day i starts and ends (point keys).
    func ends(_ i: Int) -> (String, String) {
        if i < days.count, waiting == 0, !days[i].startKey.isEmpty, !days[i].endKey.isEmpty { return (days[i].startKey, days[i].endKey) }
        let a = i == 0 ? "S" : (i - 1 < days.count ? days[i - 1].night?.key ?? "S" : "S")
        let b = i == days.count - 1 ? "E" : (i < days.count ? days[i].night?.key ?? "S" : "S")
        return (a, b)
    }
}

// MARK: - the day's timeline -------------------------------------------------------------------------------------------

/// One line of the day: leave → drive → (wait) → stop → lunch → … → return / overnight.
struct TripRow { var kind: String; var at: Int; var until: Int; var sid = ""; var from = ""; var to = ""; var leg: TripLeg? = nil; var warn: TripWarn? = nil }
struct TripPlanDay { var rows: [TripRow]; var warnings: [TripWarn]; var driveMin: Int; var km: Double; var endsAt: Int }

enum TripTimeline {
    /// Day i, timed by the schedule rule. With `now` (minutes, today) what's left is re-timed from now.
    static func day(_ it: TripItin, _ i: Int, now: Int? = nil) -> TripPlanDay {
        guard i >= 0, i < it.days.count else { return TripPlanDay(rows: [], warnings: [], driveMin: 0, km: 0, endsAt: it.dayStart) }
        let d = it.days[i]
        let (a, b) = it.ends(i)
        var rows: [TripRow] = []; var warns: [TripWarn] = []
        var tm = it.dayStart; var here = a; var drive = 0; var km = 0.0; var lunched = it.lunchSkip
        rows.append(TripRow(kind: "leave", at: tm, until: tm, from: a))
        func lunch() { rows.append(TripRow(kind: "lunch", at: tm, until: tm + it.lunchMin)); tm += it.lunchMin; lunched = true }
        func same(_ x: String, _ y: String) -> Bool { x == y || (it.place(x) != nil && it.place(x) == it.place(y)) }
        for sid in d.stops {
            guard let s = it.stop(sid) else { continue }
            if s.status == "skipped" { rows.append(TripRow(kind: "skipped", at: tm, until: tm, sid: sid)); continue }
            let checkedIn = clock(s.checkinAt)
            if let now, checkedIn == nil, tm < now { tm = now }
            let l = same(here, sid) ? nil : it.leg(here, sid)
            let dm = l?.min ?? 0
            if !lunched && checkedIn == nil && tm >= it.lunchAt - 60 && tm < it.lunchAt && tm + dm > it.lunchAt + 60 { lunch() }
            if let l { rows.append(TripRow(kind: "drive", at: tm, until: tm + dm, from: here, to: sid, leg: l)); tm += dm; drive += dm; km += l.km }
            var arrive = checkedIn ?? tm
            if !lunched && checkedIn == nil && arrive >= it.lunchAt + 60 {
                let after = arrive + it.lunchMin
                let misses: Bool = { if let f = s.fixed { return after > f + 5 }; if let c = s.window?.close { return after + s.visitMin > c }; return false }()
                if !misses { tm = arrive; lunch(); arrive = tm }
            }
            var warn: TripWarn? = nil
            if checkedIn == nil {
                if let f = s.fixed {
                    if arrive < f { rows.append(TripRow(kind: "wait", at: arrive, until: f, sid: sid)); arrive = f }
                    else if arrive > f + 5 { warn = TripWarn(code: "FIXED_LATE", level: "difficult", text: t("Arrives \(TripHm.text(arrive)), after the set time \(TripHm.text(f))", "Inafika \(TripHm.text(arrive)), baada ya muda uliowekwa \(TripHm.text(f))"), sid: sid) }
                } else if let w = s.window {
                    if let o = w.open, arrive < o {
                        if o - arrive > 20 { warns.append(TripWarn(code: "BEFORE_OPEN", level: "info", text: t("Waits \(o - arrive)\u{00A0}min for \(s.name) to open at \(TripHm.text(o))", "Inasubiri dakika\u{00A0}\(o - arrive) \(s.name) ifunguliwe saa \(TripHm.text(o))"), sid: sid)) }
                        rows.append(TripRow(kind: "wait", at: arrive, until: o, sid: sid)); arrive = o
                    }
                    if let c = w.close {
                        if arrive >= c { warn = TripWarn(code: "AFTER_CLOSE", level: "difficult", text: t("Arrives \(TripHm.text(arrive)), after \(closeWord(s.type))", "Inafika \(TripHm.text(arrive)), baada ya \(closeWord(s.type))"), sid: sid) }
                        else if arrive + s.visitMin > c { warn = TripWarn(code: "AFTER_CLOSE", level: "caution", text: t("Visit runs until \(TripHm.text(arrive + s.visitMin)), after \(closeWord(s.type)) at \(TripHm.text(c))", "Ziara inaendelea hadi \(TripHm.text(arrive + s.visitMin)), baada ya \(closeWord(s.type)) saa \(TripHm.text(c))"), sid: sid) }
                    }
                }
            }
            let leave = arrive + s.visitMin
            rows.append(TripRow(kind: "stop", at: arrive, until: leave, sid: sid, warn: warn))
            if let warn { warns.append(warn) }
            tm = leave; here = sid
            if !lunched && tm >= it.lunchAt { lunch() }
        }
        let any = d.stops.contains { it.stop($0)?.status != "skipped" }
        if any && !same(here, b), let back = it.leg(here, b) {
            if !lunched && tm >= it.lunchAt - 60 && tm < it.lunchAt && tm + back.min > it.lunchAt + 60 { lunch() }
            rows.append(TripRow(kind: "drive", at: tm, until: tm + back.min, from: here, to: b, leg: back)); tm += back.min; drive += back.min; km += back.km
        }
        rows.append(TripRow(kind: b.hasPrefix("T:") ? "overnight" : "return", at: tm, until: tm, to: b))
        if tm > it.dayEnd { warns.append(TripWarn(code: "OVER_HOURS", level: "caution", text: t("Day ends \(TripHm.text(tm)), after \(TripHm.text(it.dayEnd))", "Siku inaisha \(TripHm.text(tm)), baada ya \(TripHm.text(it.dayEnd))"))) }
        return TripPlanDay(rows: rows, warnings: warns, driveMin: drive, km: km, endsAt: tm)
    }
    static func closeWord(_ type: String) -> String {
        let ty = type.lowercased()
        if ty.contains("school") { return t("the school closes", "shule kufungwa") }
        if ty.contains("health") || ty.contains("clinic") { return t("the clinic closes", "kliniki kufungwa") }
        return t("the site closes", "eneo kufungwa")
    }
    /// The minutes of the day from a check-in time.
    static func clock(_ iso: String) -> Int? {
        guard !iso.isEmpty, let d = When.parse(iso) else { return nil }
        let c = Calendar.current.dateComponents([.hour, .minute], from: d)
        return (c.hour ?? 0) * 60 + (c.minute ?? 0)
    }
    /// The warnings to show on a day: the local schedule ones (with the server's fix when it had the same one), plus the
    /// server's road and weather warnings for the legs still in the day.
    static func warnings(_ it: TripItin, _ i: Int, _ plan: TripPlanDay) -> [TripWarn] {
        guard i < it.days.count else { return plan.warnings }
        let srv = it.days[i].warnings
        let sched: Set<String> = ["AFTER_CLOSE", "FIXED_LATE", "OVER_HOURS", "BEFORE_OPEN"]
        let local: [TripWarn] = plan.warnings.map { w in
            guard let s = srv.first(where: { $0.code == w.code && $0.sid == w.sid }) else { return w }
            var x = w; x.fix = s.fix; if it.waiting == 0 { x.text = s.text }; return x
        }
        let legsNow = Set(plan.rows.filter { $0.kind == "drive" }.map { $0.from + ">" + $0.to })
        let road = srv.filter { w in
            guard !sched.contains(w.code) else { return false }
            if w.leg.isEmpty { return true }
            let rev = w.leg.split(separator: ">").reversed().joined(separator: ">")
            return legsNow.contains(w.leg) || legsNow.contains(rev)
        }
        return road + local
    }
}

// MARK: - editing on the phone (instant), then sent ----------------------------------------------------------------------

/// Changes to the record that re-time at once; each also gives the body to send.
enum TripEdit {
    private static func with(_ it: TripItin, _ f: (inout JSON) -> Void) -> TripItin { var r = it.raw; f(&r); return it.with(raw: r, waiting: it.waiting + 1) }
    private static func dayStops(_ r: JSON, _ i: Int) -> [String] { let d = r.objs("days"); return i < d.count ? chatStrings(d[i]["stops"]) : [] }
    private static func setDayStops(_ r: inout JSON, _ i: Int, _ l: [String]) {
        var d = r.objs("days"); guard i >= 0, i < d.count else { return }
        d[i]["stops"] = l; r["days"] = d
    }
    private static func setStop(_ r: inout JSON, _ sid: String, _ f: (inout JSON) -> Void) {
        var s = r.objs("stops")
        if let i = s.firstIndex(where: { chatS($0, "sid") == sid }) { f(&s[i]); r["stops"] = s }
    }

    /// The "stops" body for the server: each day's sids, the unplanned ones, and the stops changed here.
    static func stopsBody(_ it: TripItin, changed: [String] = []) -> JSON {
        var b: JSON = ["action": "stops", "days": it.days.map { ["stops": $0.stops] as JSON }, "unplanned": it.unplanned]
        if !changed.isEmpty {
            b["stops"] = changed.compactMap { sid -> JSON? in
                guard let o = it.raw.objs("stops").first(where: { chatS($0, "sid") == sid }) else { return nil }
                return ["sid": sid, "visitMin": o.int("visitMin"), "fixed": o["fixed"] ?? NSNull(), "locked": o.bool("locked"), "note": chatS(o, "note")]
            }
        }
        return b
    }
    static func move(_ it: TripItin, _ day: Int, _ from: Int, _ to: Int) -> TripItin {
        with(it) { r in var l = dayStops(r, day); guard from >= 0, from < l.count else { return }
            let s = l.remove(at: from); l.insert(s, at: min(max(0, to), l.count)); setDayStops(&r, day, l) }
    }
    static func toDay(_ it: TripItin, _ day: Int, _ sid: String, _ target: Int) -> TripItin {
        with(it) { r in
            guard target != day, target >= 0, target < r.objs("days").count else { return }
            setDayStops(&r, day, dayStops(r, day).filter { $0 != sid }); setDayStops(&r, target, dayStops(r, target) + [sid])
            setStop(&r, sid) { $0["day"] = target }
        }
    }
    static func remove(_ it: TripItin, _ day: Int, _ sid: String) -> TripItin { with(it) { r in setDayStops(&r, day, dayStops(r, day).filter { $0 != sid }) } }
    /// A stop's time locked (a meeting) at `at`, or unlocked.
    static func lock(_ it: TripItin, _ sid: String, _ at: Int?) -> TripItin { with(it) { r in setStop(&r, sid) { $0["fixed"] = at.map { TripHm.text($0) as Any } ?? NSNull(); $0["locked"] = at != nil } } }
    static func visitMin(_ it: TripItin, _ sid: String, _ m: Int) -> TripItin { with(it) { r in setStop(&r, sid) { $0["visitMin"] = min(720, max(5, m)) } } }
    static func swapDays(_ it: TripItin, _ a: Int, _ b: Int) -> TripItin { with(it) { r in let sa = dayStops(r, a), sb = dayStops(r, b); setDayStops(&r, a, sb); setDayStops(&r, b, sa) } }
    static func mark(_ it: TripItin, _ sid: String, _ status: String, at: String = "", by: String = "") -> TripItin {
        with(it) { r in setStop(&r, sid) { $0["status"] = status; if !at.isEmpty { $0["checkin"] = ["at": at, "by": by] as JSON } } }
    }
    static func packTick(_ it: TripItin, _ key: String, _ done: Bool) -> TripItin {
        with(it) { r in var p = r.objs("packing"); if let i = p.firstIndex(where: { chatS($0, "key") == key }) { p[i]["done"] = done; r["packing"] = p } }
    }
    static func packAdd(_ it: TripItin, _ text: String) -> TripItin {
        with(it) { r in r["packing"] = r.objs("packing") + [["key": "u:\(Int(Date().timeIntervalSince1970 * 1000))", "auto": false, "en": text, "sw": text] as JSON] }
    }
    static func packRemove(_ it: TripItin, _ key: String) -> TripItin {
        with(it) { r in var p = r.objs("packing"); if let i = p.firstIndex(where: { chatS($0, "key") == key }) { p[i]["removed"] = true; r["packing"] = p } }
    }
    static func extras(_ it: TripItin, _ list: [TripExtra]) -> TripItin { with(it) { r in r["extras"] = list.map { $0.json() } } }
    static func lodging(_ it: TripItin, _ day: Int, _ n: TripNight?) -> TripItin {
        with(it) { r in
            var d = r.objs("days"); guard day < d.count else { return }
            if let x = n {
                var o: JSON = ["town": x.town, "suggested": false, "lodging": ["name": x.lodging, "phone": x.phone, "kes": x.kes, "note": x.note] as JSON]
                if let ll = x.ll { o["lat"] = ll.lat; o["lng"] = ll.lng }
                d[day]["overnight"] = o
            } else { d[day]["overnight"] = NSNull() }
            r["days"] = d
        }
    }
    static func setField(_ it: TripItin, _ k: String, _ v: Any) -> TripItin { with(it) { r in r[k] = v } }
    static func addStop(_ it: TripItin, _ day: Int, _ p: Project) -> TripItin {
        with(it) { r in
            guard let g = p.gps else { return }
            let sid = "n" + String(Int(Date().timeIntervalSince1970 * 1000), radix: 36)
            r["stops"] = r.objs("stops") + [["sid": sid, "id": p.id, "name": p.title, "lat": g.lat, "lng": g.lng, "type": p.type, "visitMin": 45, "day": day, "status": "planned"] as JSON]
            setDayStops(&r, day, dayStops(r, day) + [sid])
        }
    }

    /// Running late: the stops of `day` that no longer fit before the end of the working day (never the very next one).
    static func overflow(_ it: TripItin, _ day: Int, _ now: Int) -> [String] {
        guard day >= 0, day < it.days.count else { return [] }
        let d = it.days[day]
        let open = d.stops.filter { it.stop($0)?.open == true }
        if open.count < 2 { return [] }
        var keep: [String] = []
        for k in open.indices {
            let trial = with(it) { r in setDayStops(&r, day, d.stops.filter { s in !open.contains(s) || keep.contains(s) || s == open[k] }) }
            if k == 0 || TripTimeline.day(trial, day, now: now).endsAt <= it.dayEnd { keep.append(open[k]) }
            else { return Array(open[k...]) }
        }
        return []
    }
    /// "Move the rest?": push them to the next day (the first stops there), or drop them (skipped).
    static func push(_ it: TripItin, _ day: Int, _ sids: [String]) -> TripItin {
        with(it) { r in
            guard !sids.isEmpty else { return }
            setDayStops(&r, day, dayStops(r, day).filter { !sids.contains($0) })
            if day + 1 < r.objs("days").count { setDayStops(&r, day + 1, sids + dayStops(r, day + 1)) }
            else { r["unplanned"] = chatStrings(r["unplanned"]) + sids }
        }
    }
    static func drop(_ it: TripItin, _ sids: [String]) -> TripItin { with(it) { r in for s in sids { setStop(&r, s) { $0["status"] = "skipped" } } } }
}

// MARK: - on the day --------------------------------------------------------------------------------------------------

enum TripOnDay {
    static func today() -> String { When.localDay() }
    static func nowMin() -> Int { let c = Calendar.current.dateComponents([.hour, .minute], from: Date()); return (c.hour ?? 0) * 60 + (c.minute ?? 0) }
    static func dayIndex(_ it: TripItin, _ today: String) -> Int { it.days.firstIndex { $0.date == today } ?? -1 }
    /// The next stop today (sid), or nil.
    static func next(_ it: TripItin, _ today: String) -> String? {
        let d = dayIndex(it, today); guard d >= 0 else { return nil }
        return it.days[d].stops.first { it.stop($0)?.open == true }
    }
    /// How many minutes behind the day's plan the next stop is.
    static func lateBy(_ it: TripItin, _ today: String, _ now: Int) -> Int {
        let d = dayIndex(it, today)
        guard let sid = next(it, today),
              let planned = TripTimeline.day(it, d).rows.first(where: { $0.kind == "stop" && $0.sid == sid })?.at,
              let live = TripTimeline.day(it, d, now: now).rows.first(where: { $0.kind == "stop" && $0.sid == sid })?.at else { return 0 }
        return max(0, live - planned)
    }
    static func counts(_ it: TripItin) -> (Int, Int, Int) {
        (it.stops.filter { $0.status == "done" }.count, it.stops.filter { $0.status == "late" }.count, it.stops.filter { $0.status == "skipped" }.count)
    }
}

// MARK: - vehicles, fuel, group trips -----------------------------------------------------------------------------------

struct TripVehicle: Identifiable {
    let raw: JSON
    var vid: String { chatS(raw, "vid") }
    var id: String { vid }
    var name: String { let n = chatS(raw, "name"); return n.isEmpty ? chatS(raw, "plate") : n }
    var plate: String { chatS(raw, "plate") }
    var type: String { let x = chatS(raw, "type"); return x.isEmpty ? "saloon" : x }
    var cc: Int { raw.int("cc") }
    var fuel: String { let f = chatS(raw, "fuel"); return f.isEmpty ? "petrol" : f }
    func label() -> String { TripVehicle.label(name, plate, type) }
    static func typeName(_ k: String) -> String {
        switch k {
        case "saloon": return t("Saloon", "Saluni"); case "suv": return "SUV"; case "pickup": return t("Pickup", "Gari la mizigo"); case "4x4": return "4×4"
        case "van": return t("Van", "Vani"); case "minibus": return t("Minibus", "Basi dogo"); case "motorbike": return t("Motorbike", "Pikipiki"); default: return k
        }
    }
    static func fuelName(_ k: String) -> String { k == "diesel" ? t("diesel", "dizeli") : t("petrol", "petroli") }
    /// "Hilux · KDA 123X · Pickup".
    static func label(_ name: String, _ plate: String, _ type: String) -> String {
        [name, !plate.isEmpty && !name.lowercased().contains(plate.lowercased()) ? plate : "", type.isEmpty ? "" : typeName(type)].filter { !$0.isEmpty }.joined(separator: " · ")
    }
    /// The picker's options: every vehicle by its label, plus the trip's own when the list doesn't have it.
    static func options(_ list: [TripVehicle], _ current: String, _ tripVehicle: JSON?) -> [(String, String)] {
        var out = list.map { ($0.vid, $0.label()) }
        if !current.isEmpty && !list.contains(where: { $0.vid == current }) {
            var l = ""
            if let o = tripVehicle, chatS(o, "vid") == current || chatS(o, "vid").isEmpty { l = label(chatS(o, "name"), chatS(o, "plate"), chatS(o, "type")) }
            out.append((current, l.isEmpty ? t("The trip's vehicle", "Gari la safari") : l))
        }
        return out
    }
}

struct TripFuelLog: Identifiable {
    let raw: JSON
    var id: String { chatS(raw, "lid").isEmpty ? chatS(raw, "at") : chatS(raw, "lid") }
    var at: String { chatS(raw, "at") }
    var by: String { chatS(raw, "by") }
    var litres: Double { raw.num("litres") ?? 0 }
    var perL: Double { raw.num("kesPerL") ?? 0 }
    var total: Int { raw.num("totalKes").map { Int($0) } ?? Int((litres * perL).rounded()) }
    var odo: Int? { raw.num("odometer").map { Int($0) } }
    var town: String { chatS(raw, "town") }
    var waiting: Bool { raw.bool("waiting") }
}

/// A volunteer or donor expedition (Office › Trips › Group trips).
struct TripGroup {
    let raw: JSON
    var id: String { chatS(raw, "id") }
    var v: Int { raw.int("v") }
    var name: String { chatS(raw, "name") }
    var from: String { chatS(raw, "from") }
    var to: String { chatS(raw, "to") }
    var country: String { chatS(raw, "country") }
    var destination: String { chatS(raw, "destination") }
    var isPublic: Bool { raw.bool("public") }
    var leaders: [String] { chatStrings(raw["leaders"]) }
    var participants: [JSON] { raw.objs("participants") }
    var costs: [JSON] { raw.objs("costs") }
    var payments: [JSON] { raw.objs("payments") }
    var emergency: [JSON] { raw.objs("emergency") }
    var totals: JSON? { raw.obj("totals") }
    var shareUrl: String { raw.obj("share").map { chatS($0, "url") } ?? "" }
    var packing: String { chatS(raw, "packing") }
    var expect: String { chatS(raw, "expect") }
    var summaryText: String { chatS(raw, "summary") }
    /// The items grouped by day (either shape the server sends).
    var dayItems: [(String, [JSON])] {
        if let ds = raw["days"] as? [Any] {
            return ds.compactMap { $0 as? JSON }.map { d in (chatS(d, "date"), d.objs("items").map { var i = $0; i["date"] = chatS(d, "date"); return i }) }
        }
        let items = raw.objs("items").sorted { (chatS($0, "date"), chatS($0, "start")) < (chatS($1, "date"), chatS($1, "start")) }
        var out: [(String, [JSON])] = []
        for i in items { let d = chatS(i, "date"); if let k = out.firstIndex(where: { $0.0 == d }) { out[k].1.append(i) } else { out.append((d, [i])) } }
        return out
    }
    static let kinds = ["flight", "transfer", "lodging", "project", "school", "safari", "free", "meal", "other"]
    static func kindName(_ k: String) -> String {
        switch k {
        case "flight": return t("Flight", "Ndege"); case "transfer": return t("Pick-up / transfer", "Kuchukuliwa / usafiri"); case "lodging": return t("Lodging", "Malazi")
        case "project": return t("Drilling or project day", "Siku ya uchimbaji au mradi"); case "school": return t("School visit", "Ziara ya shule"); case "safari": return "Safari"
        case "free": return t("Free time", "Muda huru"); case "meal": return t("Meal", "Mlo"); default: return t("Other", "Nyingine")
        }
    }
    static func kindIcon(_ k: String) -> String {
        switch k { case "flight": return "airplane"; case "transfer": return "car"; case "lodging": return "bed.double"; case "project": return "drop"; case "school": return "graduationcap"
        case "safari": return "sun.max"; case "free": return "sparkle"; case "meal": return "fork.knife"; default: return "calendar" }
    }
    static let defaultPacking = "Passport (valid 6 months after you travel) and visa\nYellow fever certificate\nSun hat, sunscreen and sunglasses\nWork clothes and closed shoes for drilling days\nA warm layer for cool evenings\nWater bottle and insect repellent\nPersonal medicines"
    static let defaultExpect = "Vaccinations: see a travel clinic 6–8 weeks before you go (yellow fever, hepatitis A, typhoid).\nMalaria: ask about tablets; advice at https://www.cdc.gov/malaria/travelers/\nDrilling days are long, dusty and hot: wear clothes you don't mind getting muddy.\nSafari: neutral colours, binoculars, and stay in the vehicle unless the guide says otherwise.\nPhotos: always ask before taking pictures of people, especially children."
}

/// Why the server refused a trip change, in words.
func tripReason(_ reason: String?) -> String {
    switch reason {
    case "forbidden": return t("This isn’t one of your trips.", "Hii si mojawapo ya safari zako.")
    case "read-only": return t("You can see this trip but not change it.", "Unaweza kuona safari hii lakini huwezi kuibadilisha.")
    case "named-only": return t("Sign in with your own PIN to plan trips.", "Ingia kwa PIN yako mwenyewe ili kupanga safari.")
    case "not-found": return t("That trip isn’t there any more.", "Safari hiyo haipo tena.")
    case "too-many": return t("Too many stops: at most 40 on one trip.", "Vituo vingi mno: visizidi 40 kwa safari moja.")
    case "dates": return t("Check the dates: at most 14 days, the end after the start.", "Angalia tarehe: zisizidi siku 14, mwisho baada ya mwanzo.")
    case "stops": return t("Some of those sites have no location yet.", "Baadhi ya maeneo hayo bado hayana mahali.")
    case "start": return t("Choose where the trip starts.", "Chagua safari inapoanzia.")
    case "hours": return t("Check the working hours.", "Angalia saa za kazi.")
    case "in-use": return t("That vehicle is on a planned trip.", "Gari hilo liko kwenye safari iliyopangwa.")
    case "bad-litres": return t("Litres should be between 0.5 and 400.", "Lita ziwe kati ya 0.5 na 400.")
    case "bad-price": return t("Check the price.", "Angalia bei.")
    case "bad-odometer": return t("The odometer is lower than the last fill-up.", "Odomita iko chini kuliko ujazaji uliopita.")
    case "no-vehicle": return t("Choose a vehicle first.", "Chagua gari kwanza.")
    case "passport-number": return t("Passport numbers are never kept. Remove it and save again.", "Namba za pasipoti hazihifadhiwi kamwe. Iondoe kisha uhifadhi tena.")
    case "bad-date": return t("Check the date.", "Angalia tarehe.")
    case "bad-currency": return t("Choose a currency.", "Chagua sarafu.")
    case "bad-item": return t("Give the item a title and a date.", "Kipengele kipewe kichwa na tarehe.")
    case "no-storage": return t("Trips aren’t switched on yet on the server.", "Safari bado hazijawashwa kwenye seva.")
    case "no-office": return t("No office location is set for this country yet. Choose another place to start, or ask an admin to set it (Office › Vehicles › Trip settings).", "Mahali pa ofisi bado hapajawekwa kwa nchi hii. Chagua mahali pengine pa kuanzia, au muombe msimamizi apaweke (Ofisi › Magari › Mipangilio ya safari).")
    case "bad-amount": return t("Check the amounts.", "Angalia viwango.")
    case "bad-request": return t("Something is missing. Check the form and try again.", "Kuna kinachokosekana. Angalia fomu kisha ujaribu tena.")
    default: return reasonText(reason)
    }
}

/// "28–30 Oct", "Wed 28 Oct", or "30 Oct – 2 Nov".
enum TripDates {
    static func parse(_ s: String) -> Date? { DateField.parse(String(s.prefix(10))) }
    private static func fmt(_ f: String) -> DateFormatter {
        let d = DateFormatter(); d.locale = Locale(identifier: Lang.sw ? "sw" : "en_GB"); d.dateFormat = f; return d
    }
    static func weekday(_ s: String) -> String { guard let d = parse(s) else { return s }; return fmt("EEE d MMM").string(from: d) }
    static func day(_ d: Date) -> String { fmt("d MMM").string(from: d) }
    static func range(_ from: String, _ to: String) -> String {
        guard let a = parse(from) else { return from }
        guard let b = parse(to) else { return day(a) }
        if a == b { return weekday(from) }
        let cal = Calendar.current
        if cal.component(.month, from: a) == cal.component(.month, from: b) && cal.component(.year, from: a) == cal.component(.year, from: b) {
            return "\(cal.component(.day, from: a))–" + day(b)
        }
        return day(a) + " – " + day(b)
    }
}

import SwiftUI
import UIKit

// The trip page's sheets and panes: a stop, tonight's lodging, add a stop, move the rest, skip, report the road; costs,
// fuel and extras; packing; sharing and the summary after the trip.

/// A stop: why it's on the trip, its hours, the visit length, lock its time, move it, remove it, open the project.
struct TripStopSheet: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var ctl: TripCtl
    let sid: String; let day: Int; let live: Bool
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        if let it = ctl.trip, let s = it.stop(sid) {
            let editable = it.canEdit && it.status != "done" && it.status != "cancelled"
            let row = TripTimeline.day(it, day).rows.first { $0.kind == "stop" && $0.sid == sid }
            TripSheet(title: s.name) {
                if !s.why.isEmpty { TripFlow(spacing: 4) { ForEach(s.why, id: \.self) { w in TChip(text: w, hue: ChatHues.named("amber")) } } }
                let times = [row.map { TripHm.text($0.at) + "–" + TripHm.text($0.until) }, s.window.map { w in t("open ", "wazi ") + (w.open.map { TripHm.text($0) } ?? "") + "–" + (w.close.map { TripHm.text($0) } ?? "") }].compactMap { $0 }
                Text(times.joined(separator: " · ")).font(.bMedium).foregroundColor(C.muted).padding(.top, 8)
                if editable { editing(it, s, row) }
                HStack(spacing: 8) {
                    if let ll = s.ll { Btn(t("Directions", "Maelekezo"), icon: "nav", light: true, small: true, wide: true) { Share.open("http://maps.apple.com/?daddr=\(ll.lat),\(ll.lng)") } }
                    if !s.pid.isEmpty, m.snap?.projects.contains(where: { $0.id == s.pid }) == true {
                        Btn(t("Open the project", "Fungua mradi"), icon: "next", light: true, small: true, wide: true) { dismiss(); m.go(Place(tab: m.here.tab, project: s.pid, projectTab: "pin")) }
                    }
                }.padding(.top, 14)
                if live && s.open {
                    Btn(t("Check in", "Ingia"), icon: "check", ok: true, wide: true) {
                        dismiss(); ctl.checkIn(sid, by: m.prefs.name, at: Locator.shared.fix)
                        if !s.pid.isEmpty, m.snap?.projects.contains(where: { $0.id == s.pid }) == true { m.go(Place(tab: m.here.tab, project: s.pid, projectTab: "visit")) }
                    }.padding(.top, 10)
                }
                if editable {
                    Button { ctl.stops(TripEdit.remove(it, day, sid)); dismiss() } label: {
                        Text(t("Remove from the trip", "Ondoa kwenye safari")).font(.lLarge).foregroundColor(C.bad).frame(maxWidth: .infinity, minHeight: 44)
                    }.buttonStyle(.plain).padding(.top, 12)
                }
            }
        }
    }
    @ViewBuilder private func editing(_ it: TripItin, _ s: TripStop, _ row: TripRow?) -> some View {
        TripHead(text: t("Visit length", "Muda wa ziara"))
        HStack(spacing: 8) {
            Btn("−15", enabled: s.visitMin > 15, light: true, small: true) { ctl.stops(TripEdit.visitMin(it, sid, s.visitMin - 15), [sid]) }
            Text(TripHm.dur(s.visitMin)).font(.tMedium).foregroundColor(C.ink).frame(maxWidth: .infinity)
            Btn("+15", light: true, small: true) { ctl.stops(TripEdit.visitMin(it, sid, s.visitMin + 15), [sid]) }
        }
        TripHead(text: t("Time", "Muda"))
        if let f = s.fixed {
            Btn(t("Unlock the time (", "Fungua muda (") + TripHm.text(f) + ")", icon: "lock", light: true, small: true, wide: true) { ctl.stops(TripEdit.lock(it, sid, nil), [sid]) }
        } else {
            Btn(t("Lock at ", "Funga saa ") + (row.map { TripHm.text($0.at) } ?? ""), icon: "lock", light: true, small: true, wide: true) { if let at = row?.at { ctl.stops(TripEdit.lock(it, sid, at), [sid]) } }
        }
        Text(t("A locked time stays put, like a meeting with the committee.", "Muda uliofungwa haubadiliki, kama mkutano na kamati.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 4)
        let idx = it.days[day].stops.firstIndex(of: sid) ?? 0
        HStack(spacing: 8) {
            Btn(t("Earlier", "Mapema"), icon: "up", enabled: idx > 0, light: true, small: true, wide: true) { ctl.stops(TripEdit.move(it, day, idx, idx - 1)); dismiss() }
            Btn(t("Later", "Baadaye"), icon: "down", enabled: idx < it.days[day].stops.count - 1, light: true, small: true, wide: true) { ctl.stops(TripEdit.move(it, day, idx, idx + 1)); dismiss() }
        }.padding(.top, 10)
        if it.days.count > 1 {
            TripHead(text: t("Move to another day", "Hamishia siku nyingine"))
            TripChoices(opts: it.days.enumerated().compactMap { (e) -> (String, String)? in e.offset == day ? nil : ("\(e.offset)", TripDates.weekday(e.element.date)) }, sel: "") { k in
                ctl.stops(TripEdit.toDay(it, day, sid, Int(k) ?? day), [sid]); dismiss()
            }
        }
    }
}

/// Tonight: the suggested town, or another one, and the lodging (name, phone, cost per night).
struct TripLodgingSheet: View {
    @ObservedObject var ctl: TripCtl
    let day: Int
    @State private var town = ""
    @State private var name = ""
    @State private var phone = ""
    @State private var kes = ""
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        let n = ctl.trip.flatMap { day < $0.days.count ? $0.days[day].night : nil }
        TripSheet(title: t("Tonight", "Usiku huu")) {
            if let n, !n.why.isEmpty { Text(t("Suggested: ", "Inapendekezwa: ") + n.town + " · " + n.why).font(.bMedium).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true) }
            Field(t("Town", "Mji"), $town)
            Field(t("Lodging", "Malazi"), $name, placeholder: t("e.g. Kerio View Hotel", "mf. Kerio View Hotel"))
            Field(t("Phone", "Simu"), $phone)
            Field(t("Cost per night (for the group)", "Gharama kwa usiku (kwa kikundi)"), $kes, number: true)
            Btn(t("Save", "Hifadhi"), icon: "check", enabled: !town.trimmingCharacters(in: .whitespaces).isEmpty, wide: true) {
                guard let it = ctl.trip else { return }
                let tw = town.trimmingCharacters(in: .whitespaces)
                let x = TripNight(town: tw, ll: n?.town == tw ? n?.ll : nil, suggested: false, why: "", lodging: name.trimmingCharacters(in: .whitespaces), phone: phone.trimmingCharacters(in: .whitespaces), kes: Int(kes.filter { $0.isNumber }) ?? 0, note: "")
                var o: JSON = ["town": x.town, "lodging": ["name": x.lodging, "phone": x.phone, "kes": x.kes, "note": ""] as JSON]
                if let ll = x.ll { o["lat"] = ll.lat; o["lng"] = ll.lng }
                ctl.edit(TripEdit.lodging(it, day, x), ["action": "lodging", "day": day, "overnight": o])
                dismiss()
            }.padding(.top, 14)
            Btn(t("Go home tonight instead", "Rudi nyumbani usiku huu badala yake"), icon: "nav", light: true, small: true, wide: true) {
                guard let it = ctl.trip else { return }
                ctl.edit(TripEdit.lodging(it, day, nil), ["action": "lodging", "day": day, "overnight": NSNull()]); dismiss()
            }.padding(.top, 8)
        }
        .onAppear { town = n?.town ?? ""; name = n?.lodging ?? ""; phone = n?.phone ?? ""; kes = (n?.kes ?? 0) > 0 ? "\(n?.kes ?? 0)" : "" }
    }
}

/// Add a site to this day (re-timed at once; the server places it properly when it answers).
struct TripAddStopSheet: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var ctl: TripCtl
    let it: TripItin; let day: Int
    @State private var q = ""
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        let have = Set(it.stops.map(\.pid))
        let w = q.trimmingCharacters(in: .whitespaces).lowercased()
        let list = (m.snap?.projects ?? []).filter { p in p.gps != nil && !have.contains(p.id) && (it.ck.isEmpty || p.ck == it.ck) && (w.isEmpty || (p.title + " " + p.area).lowercased().contains(w)) }.prefix(25)
        TripSheet(title: t("Add a stop", "Ongeza kituo")) {
            Field(t("Find a site", "Tafuta eneo"), $q, placeholder: t("Name or area", "Jina au eneo"))
            ForEach(Array(list)) { p in
                Button {
                    ctl.edit(TripEdit.addStop(it, day, p), ["action": "add-stop", "stop": ["id": p.id] as JSON, "day": day]); dismiss()
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(p.title).font(.tSmall).foregroundColor(C.ink)
                        Text(p.sub).font(.bSmall).foregroundColor(C.muted)
                    }.padding(12).frame(maxWidth: .infinity, alignment: .leading).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                }.buttonStyle(.plain).padding(.top, 6)
            }
        }
    }
}

/// Running late or a stop skipped: "Move the rest?" — push to tomorrow, drop them, or keep the plan.
struct TripReplanSheet: View {
    @ObservedObject var ctl: TripCtl
    let day: Int
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        if let it = ctl.trip {
            let now = TripOnDay.nowMin()
            let out = TripEdit.overflow(it, day, now)
            let plan = TripTimeline.day(it, day, now: now)
            TripSheet(title: t("Move the rest?", "Hamisha vilivyobaki?")) {
                Text(t("From now (\(TripHm.text(now))) the day ends at \(TripHm.text(plan.endsAt)).", "Kuanzia sasa (\(TripHm.text(now))) siku inaisha saa \(TripHm.text(plan.endsAt)).")).font(.bMedium).foregroundColor(C.text)
                if out.isEmpty {
                    Text(t("Everything still fits today.", "Kila kitu bado kinatosha leo.")).font(.mont(14, .bold)).foregroundColor(C.ok).padding(.top, 8)
                } else {
                    Text(t("These no longer fit:", "Hivi havitoshi tena:")).font(.mont(14, .bold)).foregroundColor(C.warn).padding(.top, 8)
                    ForEach(out.compactMap { it.stop($0) }, id: \.sid) { s in Text("· " + s.name).font(.bMedium).foregroundColor(C.ink).padding(.leading, 6) }
                    let tomorrow = day + 1 < it.days.count ? TripDates.weekday(it.days[day + 1].date) : nil
                    Btn(tomorrow.map { t("Push to \($0)", "Hamishia \($0)") } ?? t("Push to tomorrow", "Hamishia kesho"), icon: "next", wide: true) {
                        ctl.edit(TripEdit.push(it, day, out), ["action": "replan", "day": day, "now": TripHm.text(now), "mode": "push"]); dismiss()
                    }.padding(.top, 14)
                    Btn(t("Drop them", "Viondoe"), icon: "x", light: true, wide: true) {
                        ctl.edit(TripEdit.drop(it, out), ["action": "replan", "day": day, "now": TripHm.text(now), "mode": "drop"]); dismiss()
                    }.padding(.top, 8)
                }
                Text(t("The office is told when you change the plan.", "Ofisi inaarifiwa ukibadilisha mpango.")).font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.center).frame(maxWidth: .infinity).padding(.top, 10)
                Btn(t("Keep the plan", "Baki na mpango"), light: true, small: true, wide: true) { dismiss() }.padding(.top, 8)
            }
        }
    }
}

/// Skip a stop: why, then "Move the rest?" if the day is still too long.
struct TripSkipSheet: View {
    @ObservedObject var ctl: TripCtl
    let sid: String; let replan: (Int) -> Void
    @State private var why = ""
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        if let it = ctl.trip, let s = it.stop(sid) {
            TripSheet(title: t("Skip ", "Ruka ") + s.name + "?") {
                Field(t("Why (optional)", "Kwa nini (si lazima)"), $why, placeholder: t("e.g. road flooded", "mf. barabara imefurika"))
                Btn(t("Skip this stop", "Ruka kituo hiki"), icon: "x", wide: true) {
                    let x = TripEdit.mark(it, sid, "skipped")
                    ctl.edit(x, ["action": "skip", "sid": sid, "why": why])
                    dismiss()
                    if let d = it.days.firstIndex(where: { $0.stops.contains(sid) }), !TripEdit.overflow(x, d, TripOnDay.nowMin()).isEmpty { replan(d) }
                }.padding(.top, 14)
            }
        }
    }
}

/// "Report the road" on the way, with the road-reports API (where you are now).
struct TripRoadSheet: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var loc = Locator.shared
    @State private var kind = "potholes"
    @State private var note = ""
    @State private var msg: (String, String)?
    @State private var busy = false
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        TripSheet(title: t("Report the road", "Ripoti barabara")) {
            Text(t("Where you are now. Others see it on their trips.", "Mahali ulipo sasa. Wengine wataiona kwenye safari zao.")).font(.bMedium).foregroundColor(C.muted)
            TripChoices(opts: [("potholes", t("Potholes", "Mashimo")), ("flooded", t("Flooded", "Imefurika")), ("slow_murram", t("Slow murram", "Murram ya polepole")),
                               ("impassable_when_wet", t("Impassable when wet", "Haipitiki ikinyesha")), ("bridge_out", t("Bridge out", "Daraja limeharibika")),
                               ("under_construction", t("Roadworks", "Ujenzi wa barabara")), ("good_tarmac", t("Good tarmac", "Lami nzuri"))], sel: kind) { kind = $0 }
            Field(t("Note (optional)", "Maelezo (si lazima)"), $note, placeholder: t("e.g. deep, by the shop", "mf. ni marefu, karibu na duka"))
            if loc.fix == nil { Msg(t("Waiting for your location…", "Inasubiri mahali ulipo…"), "warn") }
            Btn(t("Send", "Tuma"), icon: "upload", enabled: loc.fix != nil, wide: true, busy: busy ? t("Sending…", "Inatuma…") : nil) {
                guard let f = loc.fix else { return }
                busy = true
                Task {
                    let r = await m.api.postTo("/api/road-reports", ["action": "add", "kind": kind, "note": note, "lat": f.ll.lat, "lng": f.ll.lng, "acc": f.acc])
                    busy = false
                    msg = r.ok ? ("ok", t("Thank you. Sent ✓", "Asante. Imetumwa ✓")) : ("bad", tripReason(r.reason))
                    if r.ok { try? await Task.sleep(nanoseconds: 900_000_000); dismiss() }
                }
            }.padding(.top, 14)
            if let msg { Msg(msg.1, msg.0) }
        }
        .onAppear { loc.start() }
    }
}

// MARK: - costs -------------------------------------------------------------------------------------------------------------

/// Costs: estimate vs actual, the vehicle and fuel price, per day, fuel bought, tolls and extras.
struct TripCostsPane: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var ctl: TripCtl
    let it: TripItin; let editable: Bool; let open: (String) -> Void
    @State private var logs: [TripFuelLog] = []
    var body: some View {
        let c = it.cost
        let vehicles = ctl.repo.vehicles
        let tripVehicle = it.raw.obj("cost")?.obj("fuel")?.obj("vehicle")
        let opts = TripVehicle.options(vehicles, it.vehicleId, tripVehicle)
        VStack(alignment: .leading, spacing: 0) {
            CardV {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(t("Estimate", "Makadirio")).font(.lMedium).foregroundColor(C.muted)
                        Text(c.map { tripKes($0.estTotal) } ?? "—").font(.tLarge).foregroundColor(C.ink).lineLimit(1).minimumScaleFactor(0.7)
                    }
                    Spacer(minLength: 8)
                    VStack(alignment: .trailing, spacing: 2) {
                        Text(t("Actual so far", "Halisi hadi sasa")).font(.lMedium).foregroundColor(C.muted).multilineTextAlignment(.trailing)
                        Text(c?.actualTotal.map { tripKes($0) } ?? "—").font(.tLarge).foregroundColor(C.ok).lineLimit(1).minimumScaleFactor(0.7)
                    }
                }
                if c?.fuelMissing == true { Text(t("Fuel isn't in the estimate yet.", "Mafuta bado hayamo kwenye makadirio.")).font(.bSmall).foregroundColor(C.warn).padding(.top, 4) }
                if it.waiting > 0 { Text(t("Costs update when your changes are sent.", "Gharama zitasasishwa mabadiliko yako yakitumwa.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 4) }
            }.padding(.top, 12)
            TripHead(text: t("Vehicle and fuel", "Gari na mafuta"))
            CardV(pad: 14) {
                if editable {
                    Picker1(label: t("Vehicle", "Gari"), value: it.vehicleId, options: [("", t("No vehicle yet", "Bado hakuna gari"))] + opts) { vid in
                        ctl.edit(TripEdit.setField(it, "vehicleId", vid), ["action": "update", "vehicleId": vid])
                    }
                } else if let o = opts.first(where: { $0.0 == it.vehicleId }) { Text(o.1).font(.tSmall).foregroundColor(C.ink) }
                if let v = vehicles.first(where: { $0.vid == it.vehicleId }) {
                    Text([TripVehicle.typeName(v.type), v.cc > 0 ? Geo.grouped(v.cc) + " cc" : nil, TripVehicle.fuelName(v.fuel)].compactMap { $0 }.joined(separator: " · ")).font(.bMedium).foregroundColor(C.text).padding(.top, 8)
                }
                if let l = c?.lPer100 { Text(tripUnbreak(String(format: "%.1f L/100 km", l)) + (c?.litres.map { String(format: " · %.1f L", $0) } ?? "")).font(.tSmall).foregroundColor(C.ink).padding(.top, 6) }
                if let b = c?.basis, !b.isEmpty { Text(b).font(.bSmall).foregroundColor(C.muted).padding(.top, 2) }
                let missing = c == nil || c?.fuelMissing == true
                HStack(spacing: 8) {
                    Icon("drop", size: 18, color: missing ? C.warn : C.blue2)
                    Text(c.map { $0.fuelLabel.isEmpty ? t("Fuel: add a fuel price", "Mafuta: weka bei ya mafuta") : $0.fuelLabel } ?? t("Fuel: add a fuel price", "Mafuta: weka bei ya mafuta"))
                        .font(.mont(14, .semibold)).foregroundColor(missing ? C.warn : C.ink).fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                }.padding(10).background(missing ? C.warnBg : C.tint).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).padding(.top, 10)
            }.padding(.top, 4)
            if let c { estimate(c) }
            TripHead(text: t("Fuel bought", "Mafuta yaliyonunuliwa"), extra: logs.isEmpty ? "" : "\(logs.count)")
            ForEach(logs) { l in fuelRow(l) }
            if editable || it.status == "done" {
                Btn(t("Add fuel", "Ongeza mafuta"), icon: "drop", enabled: !it.vehicleId.isEmpty, light: true, small: true, wide: true) { open("fuel") }.padding(.top, 8)
            }
            if it.vehicleId.isEmpty { Text(t("Choose the vehicle first.", "Chagua gari kwanza.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 4) }
            TripHead(text: t("Tolls, parking and extras", "Ushuru, maegesho na mengineyo"), extra: it.extras.isEmpty ? "" : "\(it.extras.count)")
            ForEach(it.extras) { e in extraRow(e) }
            if editable || it.status == "done" {
                Btn(t("Add a cost", "Ongeza gharama"), icon: "plus", light: true, small: true, wide: true) { open("extra") }.padding(.top, 8)
            }
        }
        .task(id: it.id + "\(ctl.repo.tick)") { await ctl.repo.loadVehicles(); logs = await ctl.repo.logs(it.id) }
    }
    @ViewBuilder private func estimate(_ c: TripCost) -> some View {
        TripHead(text: t("Estimate", "Makadirio"))
        CardV(pad: 14) {
            ForEach([("fuelKes", t("Fuel", "Mafuta")), ("lunchKes", t("Lunch", "Chakula cha mchana")), ("perDiemKes", t("Allowances", "Posho")), ("lodgingKes", t("Lodging", "Malazi")), ("extrasKes", t("Tolls, parking, extras", "Ushuru, maegesho, mengineyo"))], id: \.0) { k, l in
                let est = Int(c.est.num(k) ?? -1), act = Int(c.actual?.num(k) ?? -1)
                if est > 0 || act > 0 { money(l, est >= 0 ? est : nil, act > 0 ? act : nil) }
            }
        }.padding(.top, 4)
        if c.days.count > 1 {
            TripHead(text: t("Per day", "Kwa siku"))
            CardV(pad: 14) {
                ForEach(Array(c.days.enumerated()), id: \.offset) { _, d in money(TripDates.weekday(chatS(d, "date")) + " · " + tripKm(d.num("km") ?? 0), Int(d.num("totalKes") ?? 0), nil) }
            }.padding(.top, 4)
        }
    }
    private func money(_ label: String, _ est: Int?, _ actual: Int?) -> some View {
        HStack(alignment: .top) {
            Text(label).font(.bMedium).foregroundColor(C.text).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 1) {
                Text(est.map { tripKes($0) } ?? "—").font(.tSmall).foregroundColor(C.ink).lineLimit(1).fixedSize()
                if let a = actual { Text(t("actual ", "halisi ") + tripKes(a)).font(.lSmall).foregroundColor(C.ok).lineLimit(1).fixedSize() }
            }
        }.padding(.vertical, 5)
    }
    private func fuelRow(_ l: TripFuelLog) -> some View {
        HStack(spacing: 10) {
            IconTile(icon: "drop", tint: C.blue2, bg: C.card, size: 44)
            VStack(alignment: .leading, spacing: 2) {
                Text(String(format: "%.1f L × KES %.2f", l.litres, l.perL)).font(.tSmall).foregroundColor(C.ink).lineLimit(1).minimumScaleFactor(0.8)
                Text([l.at.isEmpty ? nil : When.text(l.at), l.town.isEmpty ? nil : l.town, l.odo.map { Geo.grouped($0) + " km" }, l.by.isEmpty ? nil : l.by, l.waiting ? t("waiting to send", "inasubiri kutumwa") : nil].compactMap { $0 }.joined(separator: " · "))
                    .font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 4)
            Text(tripKes(l.total)).font(.tSmall).foregroundColor(C.ink).lineLimit(1).fixedSize()
        }.padding(12).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).padding(.top, 6)
    }
    private func extraRow(_ e: TripExtra) -> some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(e.label.isEmpty ? TripExtra.kindName(e.kind) : e.label).font(.tSmall).foregroundColor(C.ink)
                Text(TripExtra.kindName(e.kind) + (e.actual ? t(" · paid", " · imelipwa") : t(" · estimate", " · makadirio"))).font(.bSmall).foregroundColor(C.muted)
            }
            Spacer(minLength: 4)
            Text(tripKes(e.kes)).font(.tSmall).foregroundColor(C.ink).lineLimit(1).fixedSize()
            if editable {
                Button {
                    let list = it.extras.filter { $0.xid != e.xid }
                    ctl.edit(TripEdit.extras(it, list), ["action": "extras", "extras": list.map { $0.json() }])
                } label: { Icon("trash", size: 18, color: C.muted).frame(width: 44, height: 44) }.buttonStyle(.plain).accessibilityLabel(t("Remove", "Ondoa"))
            }
        }.padding(.leading, 12).padding(.vertical, 4).frame(minHeight: 52).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).padding(.top, 6)
    }
}

/// Fuel bought: litres, price per litre (or the total), odometer, a full tank, and a receipt photo.
struct TripFuelSheet: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var ctl: TripCtl
    let it: TripItin
    @State private var litres = ""
    @State private var price = ""
    @State private var total = ""
    @State private var odo = ""
    @State private var town = ""
    @State private var full = true
    @State private var photo: UIImage?
    @State private var cam = false
    @State private var msg: String?
    @State private var busy = false
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        let l = Double(litres.replacingOccurrences(of: ",", with: "."))
        let ok = (l ?? 0) >= 0.5 && (Double(price) != nil || Int(total.filter { $0.isNumber }) != nil)
        TripSheet(title: t("Add fuel", "Ongeza mafuta")) {
            Field(t("Litres", "Lita"), $litres, placeholder: "40", number: true)
            HStack(alignment: .top, spacing: 10) {
                Field(t("Price per litre", "Bei kwa lita"), $price, placeholder: "171.47", number: true)
                Field(t("Or the total", "Au jumla"), $total, number: true)
            }
            Field(t("Odometer (km)", "Odomita (km)"), $odo, number: true)
            Field(t("Town", "Mji"), $town, placeholder: t("e.g. Eldoret", "mf. Eldoret"))
            Check(label: t("Filled the tank", "Nimejaza tangi"), on: full) { full.toggle() }.padding(.top, 8)
            HStack(spacing: 10) {
                if let photo { Color.clear.frame(width: 56, height: 56).overlay(Image(uiImage: photo).resizable().scaledToFill()).clipShape(RoundedRectangle(cornerRadius: 12)) }
                Btn(photo == nil ? t("Photo of the receipt", "Picha ya risiti") : t("Take it again", "Piga tena"), icon: "camera", light: true, small: true) { cam = true }
            }.padding(.top, 8)
            Btn(t("Save", "Hifadhi"), icon: "check", enabled: ok, wide: true, busy: busy ? t("Saving…", "Inahifadhi…") : nil) { save(l ?? 0) }.padding(.top, 14)
            Msg(msg, "bad")
        }
        .sheet(isPresented: $cam) { CameraPicker { img in cam = false; photo = img }.ignoresSafeArea() }
    }
    private func save(_ l: Double) {
        var b: JSON = ["action": "log", "vid": it.vehicleId, "itinId": it.id, "at": When.now(), "litres": l, "full": full]
        if let p = Double(price) { b["kesPerL"] = p }
        if let x = Int(total.filter { $0.isNumber }), price.isEmpty { b["totalKes"] = x }
        if let o = Int(odo.filter { $0.isNumber }) { b["odometer"] = o }
        if !town.trimmingCharacters(in: .whitespaces).isEmpty { b["town"] = town.trimmingCharacters(in: .whitespaces) }
        if let f = Locator.shared.fix { b["lat"] = f.ll.lat; b["lng"] = f.ll.lng }
        let jpeg = photo.flatMap { img -> Data? in
            let k = min(1, 1600 / max(img.size.width, img.size.height))
            let size = CGSize(width: img.size.width * k, height: img.size.height * k)
            let f = UIGraphicsImageRendererFormat(); f.scale = 1
            return UIGraphicsImageRenderer(size: size, format: f).image { _ in img.draw(in: CGRect(origin: .zero, size: size)) }.jpegData(compressionQuality: 0.8)
        }
        busy = true
        Task {
            let r = await ctl.repo.addLog(b, receipt: jpeg)
            busy = false
            if r.ok || r.status == 0 { ctl.refresh(); dismiss() } else { msg = tripReason(r.reason) }
        }
    }
}

/// A toll, parking, lodging, lunch or other cost: estimated, or paid.
struct TripExtraSheet: View {
    @ObservedObject var ctl: TripCtl
    let it: TripItin
    @State private var kind = "toll"
    @State private var label = ""
    @State private var kes = ""
    @State private var paid = true
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        TripSheet(title: t("Add a cost", "Ongeza gharama")) {
            TripChoices(opts: ["toll", "parking", "lodging", "lunch", "other"].map { ($0, TripExtra.kindName($0)) }, sel: kind) { kind = $0 }
            Field(t("What (optional)", "Nini (si lazima)"), $label, placeholder: t("e.g. Parking Eldoret", "mf. Maegesho Eldoret"))
            Field(t("Amount", "Kiasi"), $kes, number: true)
            Check(label: t("Already paid", "Imeshalipwa"), on: paid) { paid.toggle() }.padding(.top, 8)
            Btn(t("Save", "Hifadhi"), icon: "check", enabled: (Int(kes.filter { $0.isNumber }) ?? 0) > 0, wide: true) {
                let x = TripExtra(xid: "x" + String(Int(Date().timeIntervalSince1970 * 1000), radix: 36), day: nil, kind: kind, label: label.trimmingCharacters(in: .whitespaces), kes: Int(kes.filter { $0.isNumber }) ?? 0, actual: paid, receipt: nil)
                let list = it.extras + [x]
                ctl.edit(TripEdit.extras(it, list), ["action": "extras", "extras": list.map { $0.json() }]); dismiss()
            }.padding(.top, 14)
        }
    }
}

// MARK: - packing -----------------------------------------------------------------------------------------------------------

/// The packing checklist, made from the stops plus the standard items.
struct TripPackingPane: View {
    @ObservedObject var ctl: TripCtl
    let it: TripItin; let editable: Bool
    @State private var add = ""
    var body: some View {
        let done = it.packing.filter { $0.done }.count
        VStack(alignment: .leading, spacing: 0) {
            TripHead(text: t("Packing list", "Orodha ya mzigo"), extra: "\(done)/\(it.packing.count)")
            if it.packing.isEmpty { Text(t("The list is made when the trip is saved.", "Orodha hutengenezwa safari ikihifadhiwa.")).font(.bMedium).foregroundColor(C.muted).padding(.vertical, 16) }
            ForEach(it.packing) { p in
                HStack(spacing: 6) {
                    Button { tick(p) } label: {
                        HStack(spacing: 10) {
                            Image(systemName: p.done ? "checkmark.square.fill" : "square").font(.system(size: 22)).foregroundColor(p.done ? C.ok : C.muted)
                            Text(p.text).font(.mont(14, p.auto ? .semibold : .regular)).foregroundColor(p.done ? C.ok : C.ink).fixedSize(horizontal: false, vertical: true).multilineTextAlignment(.leading)
                            Spacer(minLength: 0)
                        }.padding(.vertical, 10).padding(.leading, 10).contentShape(Rectangle())
                    }.buttonStyle(.plain).disabled(!editable)
                    if editable {
                        Button { ctl.edit(TripEdit.packRemove(it, p.key), ["action": "packing", "items": [["key": p.key, "removed": true] as JSON]]) } label: { Icon("x", size: 16, color: C.muted).frame(width: 44, height: 44) }
                            .buttonStyle(.plain).accessibilityLabel(t("Remove", "Ondoa"))
                    }
                }.background(p.done ? C.okBg : C.tint).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).padding(.top, 6)
            }
            if editable {
                HStack(alignment: .bottom, spacing: 8) {
                    Field(t("Add an item", "Ongeza kitu"), $add, placeholder: t("e.g. Spare tyre checked", "mf. Tairi ya akiba imeangaliwa"))
                    Btn(t("Add", "Ongeza"), icon: "plus", enabled: !add.trimmingCharacters(in: .whitespaces).isEmpty, small: true) {
                        let x = add.trimmingCharacters(in: .whitespaces)
                        ctl.edit(TripEdit.packAdd(it, x), ["action": "packing", "add": [["en": x, "sw": x] as JSON]]); add = ""
                    }.padding(.bottom, 4)
                }
            }
        }
    }
    private func tick(_ p: TripPack) {
        ctl.edit(TripEdit.packTick(it, p.key, !p.done), ["action": "packing", "items": [["key": p.key, "done": !p.done] as JSON]])
    }
}

// MARK: - share and summary -------------------------------------------------------------------------------------------------

/// Share a read-only page (share sheet, copy link), with teammates in the app, in a chat; and the summary after.
struct TripSharePane: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var ctl: TripCtl
    let it: TripItin; let open: (String) -> Void
    @State private var url = ""
    @State private var said: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            TripHead(text: t("Share the plan", "Shiriki mpango"))
            Text(t("A private, read-only page: the days, stops and times. No PINs, phone numbers or costs.", "Ukurasa wa faragha wa kusoma tu: siku, vituo na muda. Hakuna PIN, namba za simu wala gharama.")).font(.bMedium).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true)
            if !url.isEmpty { Text(url).font(.bSmall).foregroundColor(C.blue2).lineLimit(1).padding(.top, 6) }
            TripFlow(spacing: 8) {
                Btn(t("Share", "Shiriki"), icon: "share", light: true, small: true) { withLink { u in Share.items([it.title + " · " + TripDates.range(it.from, it.to) + "\n" + u]) } }
                Btn(t("Copy link", "Nakili kiungo"), icon: "link", light: true, small: true) { withLink { u in UIPasteboard.general.string = u; said = t("Link copied", "Kiungo kimenakiliwa") } }
                Btn(t("Teammates", "Wenzako"), icon: "people", light: true, small: true) { open("team") }
                Btn(t("In a chat", "Kwenye gumzo"), icon: "chat", light: true, small: true) { open("chat") }
            }.padding(.top, 10)
            Msg(said, "ok")
            if !url.isEmpty && it.canEdit {
                Button { ctl.ask(["action": "unshare"]) { r in if r.ok { url = "" } } } label: { Text(t("Stop sharing the link", "Acha kushiriki kiungo")).font(.lLarge).foregroundColor(C.bad).frame(minHeight: 44) }.buttonStyle(.plain).padding(.top, 6)
            }
            TripHead(text: t("After the trip", "Baada ya safari"))
            if let sm = it.summary { TripSummaryCard(s: sm) }
            else {
                Text(t("When the trip is done: sites visited, km driven, estimated and actual cost, problems found, repairs opened and water tests. It goes into the monthly report.", "Safari ikikamilika: maeneo yaliyotembelewa, km zilizoendeshwa, gharama inayokadiriwa na halisi, matatizo, matengenezo yaliyofunguliwa na vipimo vya maji. Inaingia kwenye ripoti ya mwezi."))
                    .font(.bMedium).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true)
                if it.canEdit && it.status != "done" {
                    Btn(t("Finish the trip", "Maliza safari"), icon: "check", ok: true, wide: true) { ctl.ask(["action": "update", "status": "done"]) { r in if r.ok { ctl.ask(["action": "summary"]) } } }.padding(.top, 10)
                }
            }
        }
        .onAppear { url = it.shareUrl }
    }
    private func withLink(_ f: @escaping (String) -> Void) {
        if !url.isEmpty { f(url); return }
        ctl.ask(["action": "share"]) { r in let u = r.json.str("url"); if !u.isEmpty { url = u; f(u) } }
    }
}

struct TripSummaryCard: View {
    let s: JSON
    var body: some View {
        let visited = s.arr("visited")?.count ?? 0
        let skipped = s.objs("skipped")
        let km = s.obj("km"), cost = s.obj("cost")
        CardV {
            HStack(spacing: 8) {
                tile("\(visited)", visited == 1 ? t("site visited", "eneo lililotembelewa") : t("sites visited", "maeneo yaliyotembelewa"))
                tile(Geo.grouped(Int(km?.num("value") ?? 0)), km.map { chatS($0, "src") } == "gps" ? t("km driven (GPS)", "km zilizoendeshwa (GPS)") : t("km (planned)", "km (zilizopangwa)"))
            }
            HStack(spacing: 8) {
                tile("\(s.int("repairsOpened"))", t("repairs opened", "matengenezo yaliyofunguliwa"))
                tile("\(s.int("waterTests"))", t("water tests", "vipimo vya maji"))
            }.padding(.top, 8)
            if let cost {
                row(t("Estimated cost", "Gharama inayokadiriwa"), tripKes(Int(cost.num("estKes") ?? 0)))
                if let a = cost.num("actualKes"), a > 0 { row(t("Actual cost", "Gharama halisi"), tripKes(Int(a))) }
            }
            if !skipped.isEmpty { Text(t("Skipped: ", "Zilizorukwa: ") + skipped.map { chatS($0, "name") }.joined(separator: ", ")).font(.bSmall).foregroundColor(C.muted).padding(.top, 6) }
            let probs = s.objs("problems")
            if !probs.isEmpty {
                Text(t("Problems found", "Matatizo yaliyopatikana")).font(.tSmall).foregroundColor(C.ink).padding(.top, 10)
                ForEach(Array(probs.enumerated()), id: \.offset) { _, p in Text("· " + chatS(p, "name") + " · " + stateName(chatS(p, "state"))).font(.bSmall).foregroundColor(C.bad) }
            }
        }.padding(.top, 6)
    }
    private func tile(_ v: String, _ l: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(v).font(.tLarge).foregroundColor(C.ink).lineLimit(1).minimumScaleFactor(0.7)
            FitWords(l, size: 11, weight: .bold, color: C.muted, lines: 2)
        }.padding(12).frame(maxWidth: .infinity, alignment: .leading).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
    private func row(_ l: String, _ v: String) -> some View {
        HStack { Text(l).font(.bMedium).foregroundColor(C.text); Spacer(); Text(v).font(.tSmall).foregroundColor(C.ink) }.padding(.vertical, 5)
    }
}

/// Share with teammates in the app (they see it in their list, read-only).
struct TripShareTeamSheet: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var ctl: TripCtl
    let it: TripItin
    @State private var picked: [String] = []
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        TripSheet(title: t("Share with teammates", "Shiriki na wenzako")) {
            Text(t("They see the trip in their list (read-only).", "Wataiona safari kwenye orodha yao (kusoma tu).")).font(.bMedium).foregroundColor(C.muted)
            TripFlow(spacing: 8) {
                ForEach((m.team ?? []).map(\.name).filter { !it.isOn($0) }, id: \.self) { n in
                    let on = picked.contains(n)
                    Button { if on { picked.removeAll { $0 == n } } else { picked.append(n) } } label: {
                        HStack(spacing: 6) { Icon(on ? "check" : "plus", size: 16, color: on ? .white : C.blue); Text(n).font(.mont(14, .bold)).foregroundColor(on ? .white : C.ink).lineLimit(1) }
                            .padding(.horizontal, 12).frame(minHeight: 40).background(on ? C.blue : C.card).clipShape(Capsule()).overlay(Capsule().stroke(on ? C.blue : C.line, lineWidth: 1))
                    }.buttonStyle(.plain).accessibilityAddTraits(on ? .isSelected : [])
                }
            }.padding(.top, 10)
            Btn(t("Share", "Shiriki"), icon: "people", enabled: !picked.isEmpty, wide: true) { ctl.ask(["action": "share-team", "names": picked]); dismiss() }.padding(.top, 14)
        }
        .task { if m.team == nil { await m.loadTeam() } }
        .onAppear { picked = it.viewers }
    }
}

/// "In a chat": choose the chat; the trip's card goes into it.
struct TripShareChatSheet: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var hub: ChatHub
    let tripId: String
    @State private var err: String?
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        let me = hub.me
        TripSheet(title: t("Share the trip in a chat", "Shiriki safari kwenye gumzo")) {
            if hub.off { ChatOffCard(need: hub.offNeed) }
            ForEach(hub.sorted(hub.rows.filter { !$0.archived && !($0.onlyAdmins && !$0.admin(me)) })) { r in
                Button {
                    Task {
                        if let e = await hub.shareTrip(r.id, tripId) { err = e }
                        else { dismiss(); DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { m.go(Place(tab: m.here.tab, sub: "chat:" + r.id)) } }
                    }
                } label: {
                    HStack(spacing: 12) {
                        ChatAvatar(row: r, me: me, size: 40)
                        Text(chatTitle(r, me)).font(.tSmall).foregroundColor(C.ink).lineLimit(1)
                        Spacer(minLength: 0)
                    }.frame(minHeight: 56).contentShape(Rectangle())
                }.buttonStyle(.plain)
            }
            Msg(err, "bad")
        }
        .onAppear { hub.start() }
    }
}

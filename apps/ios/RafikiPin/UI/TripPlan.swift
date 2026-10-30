import SwiftUI
import UIKit

/// What the planner keeps between its steps.
@MainActor
final class TripDraft: ObservableObject {
    @Published var picked: [String] = []
    @Published var title = ""
    @Published var from = DateField.text(Date().addingTimeInterval(86_400))
    @Published var to = DateField.text(Date().addingTimeInterval(86_400))
    @Published var people: [String] = []
    @Published var start = "office"
    @Published var placeName = ""
    @Published var end = "same"
    @Published var endName = ""
    @Published var hStart = "07:30"
    @Published var hEnd = "17:30"
    @Published var vehicle = ""
    @Published var passengers = ""
    @Published var plan: JSON?
    static var current: TripDraft?
}

/// A suggested or picked stop while planning.
struct TripPick: Identifiable { let id: String; let name: String; let area: String; let why: [String]; let ll: LatLng? }

/// Plan a trip in a few taps: 1 choose the stops (or "Suggest stops", ranked and explained), 2 days, people, start and
/// end, vehicle, 3 the plan the server builds (shortest drive, split across days by working hours, overnight towns), then
/// Save. Back goes to the step before, keeping what was chosen.
struct TripPlanView: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var repo: TripRepo
    @StateObject private var d: TripDraft = TripDraft.current ?? TripDraft()
    @State private var step = 1

    var body: some View {
        ScrollViewReader { sv in
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    BackLink(label: step > 1 ? t("Back", "Rudi") : t("Close", "Funga")) { if step > 1 { step -= 1 } else { TripDraft.current = nil; m.back() } }.id("top")
                    Text(t("Plan a trip", "Panga safari")).font(.hLarge).foregroundColor(C.ink).frame(maxWidth: .infinity).multilineTextAlignment(.center)
                    dots
                    switch step {
                    case 1: TripPlanStops(d: d) { step = 2 }
                    case 2: TripPlanDetails(d: d, repo: repo) { d.plan = nil; step = 3 }
                    default: TripPlanPreview(d: d, repo: repo, restart: { step = 1 }, pickPlace: { d.start = "place"; step = 2 })
                    }
                    Spacer().frame(height: 24)
                }.padding(16)
            }
            .onChange(of: step) { _ in withAnimation { sv.scrollTo("top", anchor: .top) } }
        }
        .onAppear {
            TripDraft.current = d
            if d.people.isEmpty && !m.prefs.name.isEmpty { d.people = [m.prefs.name] }
            if m.demo, let s = Demo.arg("-step"), let n = Int(s) { if d.picked.isEmpty { d.picked = ["101", "105"] }; step = n }
        }
    }
    private var dots: some View {
        let names = [t("Stops", "Vituo"), t("Days and people", "Siku na watu"), t("Your plan", "Mpango wako")]
        return HStack(alignment: .top, spacing: 6) {
            ForEach(0..<3, id: \.self) { i in
                let on = i + 1 == step, past = i + 1 < step
                VStack(spacing: 4) {
                    Capsule().fill(on || past ? C.blue : C.line).frame(height: 5)
                    FitWords(names[i], size: 11, weight: on ? .heavy : .medium, color: on ? C.ink : C.muted, lines: 2, align: .center)
                }.frame(maxWidth: .infinity)
            }
        }.padding(.top, 10)
    }
}

/// Step 1: the sites to visit, picked or suggested.
struct TripPlanStops: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var d: TripDraft
    let next: () -> Void
    @State private var ck = ""
    @State private var areas: [String] = []
    @State private var sugg: [TripPick]?
    @State private var busy = false
    @State private var msg: String?
    @State private var q = ""

    var body: some View {
        let s = m.snap
        let all = (s?.projects ?? []).filter { $0.gps != nil }
        let cks = Array(Set(all.map(\.ck).filter { !$0.isEmpty })).sorted()
        let areaList = topAreas(all)
        let w = q.trimmingCharacters(in: .whitespaces).lowercased()
        let rows = all.filter { p in (ck.isEmpty || p.ck == ck) && (areas.isEmpty || areas.contains(p.area)) && (w.isEmpty || (p.title + " " + p.area).lowercased().contains(w)) }
            .sorted { a, b in d.picked.contains(a.id) != d.picked.contains(b.id) ? d.picked.contains(a.id) : a.title < b.title }.prefix(w.isEmpty ? 15 : 40)
        VStack(alignment: .leading, spacing: 0) {
            Text(t("Pick the sites to visit, or let the app suggest them.", "Chagua maeneo ya kutembelea, au acha programu ipendekeze.")).font(.bMedium).foregroundColor(C.muted).multilineTextAlignment(.center).frame(maxWidth: .infinity).padding(.top, 12)
            if cks.count > 1 { TripChoices(opts: cks.map { ($0, countryName($0) ?? $0.uppercased()) }, sel: ck) { ck = $0; areas = []; sugg = nil } }
            if !areaList.isEmpty {
                TripHead(text: t("Areas", "Maeneo"))
                TripFlow(spacing: 8) {
                    ForEach(areaList, id: \.self) { a in
                        let on = areas.contains(a)
                        Button { if on { areas.removeAll { $0 == a } } else { areas.append(a) }; sugg = nil } label: {
                            HStack(spacing: 4) { if on { Icon("check", size: 14, color: .white) }; Text(a).font(.mont(13, .bold)).foregroundColor(on ? .white : C.ink).lineLimit(1) }
                                .padding(.horizontal, 12).frame(minHeight: 38).background(on ? C.blue : C.card).clipShape(Capsule()).overlay(Capsule().stroke(on ? C.blue : C.line, lineWidth: 1))
                        }.buttonStyle(.plain)
                    }
                }
            }
            Btn(t("Suggest stops", "Pendekeza vituo"), icon: "spark", enabled: !busy, light: sugg != nil, wide: true, busy: busy ? t("Looking…", "Inatafuta…") : nil) { suggest(all) }.padding(.top, 14)
            Msg(msg, "bad")
            if let l = sugg {
                TripHead(text: t("Suggested", "Yanayopendekezwa"), extra: "\(l.count)")
                if l.isEmpty { Text(t("Nothing needs a visit here right now.", "Hakuna kinachohitaji ziara hapa kwa sasa.")).font(.bMedium).foregroundColor(C.muted).padding(4) }
                ForEach(l) { p in pickRow(p.id, p.name, p.area, p.why, dist(p.ll)) }
            }
            TripHead(text: t("Or pick sites", "Au chagua maeneo"))
            Field(t("Find a site", "Tafuta eneo"), $q, placeholder: t("Name or area", "Jina au eneo"))
            ForEach(Array(rows)) { p in pickRow(p.id, p.title, p.area, [], nil) }
            Btn(d.picked.isEmpty ? t("Choose at least one stop", "Chagua angalau kituo kimoja") : d.picked.count == 1 ? t("Next: 1 stop", "Endelea: kituo 1") : t("Next: \(d.picked.count) stops", "Endelea: vituo \(d.picked.count)"),
                icon: "next", enabled: !d.picked.isEmpty, wide: true) { next() }.padding(.top, 14)
        }
        .onAppear { if ck.isEmpty { ck = cks.contains(m.prefs.country) ? m.prefs.country : (cks.first ?? "") } }
    }
    private func topAreas(_ all: [Project]) -> [String] {
        var n: [String: Int] = [:]
        for p in all where p.ck == ck && !p.area.isEmpty { n[p.area, default: 0] += 1 }
        return n.sorted { $0.value > $1.value }.prefix(12).map(\.key)
    }
    private func dist(_ ll: LatLng?) -> String? { guard let h = Locator.shared.fix?.ll, let ll else { return nil }; return Geo.dist(Geo.metres(h, ll), miles: m.miles) }
    private func pickRow(_ id: String, _ name: String, _ area: String, _ why: [String], _ dist: String?) -> some View {
        let on = d.picked.contains(id)
        return Button { Haptic.tap(); if on { d.picked.removeAll { $0 == id } } else { d.picked.append(id) } } label: {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: on ? "checkmark.square.fill" : "square").font(.system(size: 22)).foregroundColor(on ? C.blue : C.muted).padding(.top, 2)
                VStack(alignment: .leading, spacing: 3) {
                    Text(name).font(.tSmall).foregroundColor(C.ink).lineLimit(2).multilineTextAlignment(.leading)
                    let sub = [area.isEmpty ? nil : area, dist].compactMap { $0 }.joined(separator: " · ")
                    if !sub.isEmpty { Text(sub).font(.bSmall).foregroundColor(C.muted).lineLimit(1) }
                    if !why.isEmpty {
                        TripFlow(spacing: 4) { ForEach(why, id: \.self) { w in TChip(text: w, hue: w.contains("urgent") || w.contains("dharura") ? ChatHues.bad : ChatHues.named("amber")) } }
                    }
                }
                Spacer(minLength: 0)
            }.padding(10).background(on ? C.tint : C.card).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(on ? C.blue : C.line, lineWidth: 1)).contentShape(Rectangle())
        }.buttonStyle(.plain).padding(.top, 6).accessibilityAddTraits(on ? .isSelected : [])
    }
    private func suggest(_ all: [Project]) {
        busy = true; msg = nil
        var body: JSON = ["ck": ck, "areas": areas, "limit": 30]
        if let h = Locator.shared.fix?.ll { body["from"] = ["lat": h.lat, "lng": h.lng] as JSON }
        Task {
            let r = await m.trips.suggest(body)
            busy = false
            if r.ok {
                sugg = r.json.objs("items").map { o in
                    TripPick(id: chatS(o, "id"), name: chatS(o, "name"), area: chatS(o, "area"), why: (o.arr("why") ?? []).map { tripWords($0) }, ll: tripLatLng(o))
                }
            } else {
                sugg = localSuggest(all).filter { areas.isEmpty || areas.contains($0.area) }
                if r.status != 0 { msg = tripReason(r.reason) }
            }
        }
    }
    /// What the phone itself would suggest when the server can't be reached: open repairs, then sites due a visit.
    private func localSuggest(_ all: [Project]) -> [TripPick] {
        guard let s = m.snap else { return [] }
        var out: [(TripPick, Double)] = []
        for p in all where ck.isEmpty || p.ck == ck {
            var why: [String] = []; var score = 0.0
            if let r = s.repairs[p.id] { why.append(r.urgency == "urgent" ? t("open repair · urgent", "matengenezo yaliyo wazi · ya dharura") : t("open repair", "matengenezo yaliyo wazi")); score += r.urgency == "urgent" ? 50 : 30 }
            let age = ageDays(s, p)
            if age >= DUE_DAYS { why.append(age > 1e5 ? t("never visited with the app", "haijawahi kutembelewa kwa programu") : t("due a visit · \(Int(age / 30)) months", "Ziara inahitajika · miezi \(Int(age / 30))")); score += min(40, age / 10) }
            if !why.isEmpty { out.append((TripPick(id: p.id, name: p.title, area: p.area, why: why, ll: p.gps), score)) }
        }
        return out.sorted { $0.1 > $1.1 }.prefix(30).map(\.0)
    }
}

/// Step 2: name, days, people, start and end, hours, vehicle.
struct TripPlanDetails: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var d: TripDraft
    @ObservedObject var repo: TripRepo
    let next: () -> Void
    @State private var best: String?
    var body: some View {
        let s = m.snap
        let here = Locator.shared.fix?.ll
        let names = ([m.prefs.name] + (m.team ?? []).map(\.name)).filter { !$0.isEmpty }.reduce(into: [String]()) { a, n in if !a.contains(n) { a.append(n) } }
        let ok = TripHm.parse(d.hStart) != nil && TripHm.parse(d.hEnd) != nil && !d.from.isEmpty && d.to >= d.from && !d.people.isEmpty && (d.start != "here" || here != nil)
        VStack(alignment: .leading, spacing: 0) {
            Field(t("Name of the trip", "Jina la safari"), $d.title, placeholder: t("e.g. Moiben repairs", "mf. Matengenezo Moiben"))
            HStack(alignment: .top, spacing: 10) {
                TripDateField(label: t("First day", "Siku ya kwanza"), value: d.from) { v in d.from = v; if d.to < v { d.to = v } }
                TripDateField(label: t("Last day", "Siku ya mwisho"), value: d.to) { d.to = $0 }
            }
            if let best {
                HStack(spacing: 8) {
                    Icon("sun", size: 18, color: C.ok)
                    Text(t("Best day for these roads: ", "Siku bora kwa barabara hizi: ") + best).font(.mont(14, .semibold)).foregroundColor(C.ok).fixedSize(horizontal: false, vertical: true)
                }.padding(10).frame(maxWidth: .infinity, alignment: .leading).background(C.okBg).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).padding(.top, 8)
            }
            TripHead(text: t("People", "Watu"))
            TripFlow(spacing: 8) {
                ForEach(names, id: \.self) { n in
                    let on = d.people.contains(n)
                    let locked = n == m.prefs.name && s?.admin != true
                    Button { if !locked { if on { d.people.removeAll { $0 == n } } else { d.people.append(n) } } } label: {
                        HStack(spacing: 6) { Icon(on ? "check" : "plus", size: 16, color: on ? .white : C.blue); Text(n).font(.mont(14, .bold)).foregroundColor(on ? .white : C.ink).lineLimit(1) }
                            .padding(.horizontal, 12).frame(minHeight: 40).background(on ? C.blue : C.card).clipShape(Capsule()).overlay(Capsule().stroke(on ? C.blue : C.line, lineWidth: 1))
                    }.buttonStyle(.plain).accessibilityAddTraits(on ? .isSelected : [])
                }
            }
            TripHead(text: t("Start", "Kuanzia"))
            TripChoices(opts: [("office", t("The office", "Ofisi")), ("here", t("Where I am", "Nilipo")), ("place", t("Another place", "Mahali pengine"))], sel: d.start) { d.start = $0 }
            if d.start == "here" && here == nil { Msg(t("Waiting for your location…", "Inasubiri mahali ulipo…"), "warn") }
            if d.start == "place" {
                Field(t("Name of the place", "Jina la mahali"), $d.placeName, placeholder: t("e.g. Iten town", "mf. mji wa Iten"))
                Text(t("The place is found by its name when the plan is built.", "Mahali hupatikana kwa jina lake mpango ukitengenezwa.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 4)
            }
            TripHead(text: t("End", "Mwisho"))
            TripChoices(opts: [("same", t("Back to the start", "Kurudi mwanzo")), ("place", t("Another place", "Mahali pengine"))], sel: d.end) { d.end = $0 }
            if d.end == "place" { Field(t("Name of the place", "Jina la mahali"), $d.endName, placeholder: t("e.g. Iten town", "mf. mji wa Iten")) }
            TripHead(text: t("Working hours", "Saa za kazi"))
            HStack(alignment: .top, spacing: 10) {
                Field(t("Leave at", "Ondoka saa"), $d.hStart, placeholder: "07:30")
                Field(t("Back by", "Rudi kabla ya"), $d.hEnd, placeholder: "17:30")
            }
            TripHead(text: t("Vehicle", "Gari"))
            Picker1(label: "", value: d.vehicle, options: [("", t("Choose later", "Chagua baadaye"))] + TripVehicle.options(repo.vehicles, d.vehicle, nil)) { d.vehicle = $0 }
            Field(t("People in the vehicle", "Watu ndani ya gari"), $d.passengers, placeholder: "\(max(1, d.people.count))", number: true)
            Btn(t("Build the plan", "Tengeneza mpango"), icon: "compass", enabled: ok, wide: true) { next() }.padding(.top, 18)
        }
        .task { if m.team == nil { await m.loadTeam() }; await repo.loadVehicles() }
        .task(id: d.from + d.to + "\(d.picked.count)") { await bestDay() }
        .onAppear {
            if d.title.isEmpty {
                var n: [String: Int] = [:]
                for p in (m.snap?.projects ?? []) where d.picked.contains(p.id) && !p.area.isEmpty { n[p.area, default: 0] += 1 }
                if let a = n.max(by: { $0.value < $1.value })?.key { d.title = t("Trip to \(a)", "Safari ya \(a)") }
            }
        }
    }
    /// The driest day for these sites (weather only, from the trip engine).
    private func bestDay() async {
        let days: Int = {
            guard let a = DateField.parse(d.from), let b = DateField.parse(d.to) else { return 5 }
            return min(14, max(3, (Calendar.current.dateComponents([.day], from: a, to: b).day ?? 0) + 3))
        }()
        let r = await m.api.get("/api/trip/week?ids=" + d.picked.joined(separator: ",") + "&start=" + d.from + "&days=\(days)")
        guard let b = r.json.obj("bestDay") else { best = nil; return }
        let name = tripWords(b["name"])
        best = (name.isEmpty ? TripDates.weekday(chatS(b, "date")) : name) + " · " + roadWord(chatS(b, "roadLevel"))
    }
    private func roadWord(_ l: String) -> String {
        switch l { case "dry": return t("dry roads", "barabara kavu"); case "light": return t("a little rain", "mvua kidogo"); case "wet": return t("wet roads", "barabara zenye maji"); case "heavy": return t("heavy rain", "mvua kubwa"); default: return l }
    }
}

/// Step 3: the plan the server builds, then Save.
struct TripPlanPreview: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var d: TripDraft
    @ObservedObject var repo: TripRepo
    let restart: () -> Void; let pickPlace: () -> Void
    @State private var err: String?
    @State private var noOffice = false
    @State private var saving = false
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let p = d.plan {
                let raw: JSON = { var x = p; x["title"] = d.title; return x }()
                let it = TripItin(raw)
                let km = it.days.indices.reduce(0.0) { $0 + TripTimeline.day(it, $1).km }
                Text(d.title.isEmpty ? t("Your plan", "Mpango wako") : d.title).font(.tLarge).foregroundColor(C.ink).multilineTextAlignment(.center).frame(maxWidth: .infinity).padding(.top, 10)
                Text(TripDates.range(d.from, d.to) + " · " + (it.stops.count == 1 ? t("1 stop", "kituo 1") : t("\(it.stops.count) stops", "vituo \(it.stops.count)")) + " · " + tripKm(km)).font(.bMedium).foregroundColor(C.muted).multilineTextAlignment(.center).frame(maxWidth: .infinity)
                ForEach(it.warnings) { w in TripWarnRow(w: w, ctl: nil) }
                if !it.unplanned.isEmpty { Msg(t("Didn't fit in the days: ", "Hazikutosha kwenye siku: ") + it.unplanned.compactMap { it.stop($0)?.name }.joined(separator: ", "), "warn") }
                ForEach(it.days.indices, id: \.self) { i in TripDayCard(ctl: TripCtl(repo: repo, id: ""), it: it, i: i, editable: false, live: false, tapStop: { _ in }, tapNight: { _ in }) }
                if let c = it.cost { Text(t("Estimated cost: ", "Gharama inayokadiriwa: ") + tripKes(c.estTotal)).font(.tSmall).foregroundColor(C.ink).padding(.top, 12) }
                Btn(t("Save the trip", "Hifadhi safari"), icon: "check", enabled: !saving, ok: true, wide: true, busy: saving ? t("Saving…", "Inahifadhi…") : nil) { save(p) }.padding(.top, 16)
                Msg(err, "bad")
            } else {
                if err == nil {
                    VStack(spacing: 12) {
                        ProgressView().tint(C.blue)
                        Text(t("Finding the shortest drive and checking the roads…", "Inatafuta njia fupi zaidi na kuangalia barabara…")).font(.bMedium).foregroundColor(C.muted).multilineTextAlignment(.center)
                    }.frame(maxWidth: .infinity).padding(.top, 30)
                }
                Msg(err, "bad")
                if noOffice { Btn(t("Choose where to start", "Chagua mahali pa kuanzia"), icon: "pin", wide: true) { pickPlace() }.padding(.top, 12) }
                if err != nil { Btn(t("Change the stops", "Badilisha vituo"), icon: "back", light: true, wide: true) { restart() }.padding(.top, 12) }
            }
        }
        .task { await build() }
    }
    private func body0() -> JSON {
        let here = Locator.shared.fix?.ll
        var start: JSON = ["kind": "office"]
        if d.start == "here" { start = ["kind": "here", "name": t("Where I was", "Nilipokuwa")]; if let h = here { start["lat"] = h.lat; start["lng"] = h.lng } }
        if d.start == "place" { start = ["kind": "place", "name": d.placeName.isEmpty ? t("Start", "Mwanzo") : d.placeName] }
        let end: JSON = d.end == "place" ? ["kind": "place", "name": d.endName.isEmpty ? t("End", "Mwisho") : d.endName] : ["kind": "same"]
        var b: JSON = ["from": d.from, "to": d.to, "people": d.people, "ck": m.snap?.projects.first { d.picked.contains($0.id) }?.ck ?? "",
                       "start": start, "end": end, "hours": ["start": TripHm.text(TripHm.parse(d.hStart) ?? 450), "end": TripHm.text(TripHm.parse(d.hEnd) ?? 1050)] as JSON,
                       "stops": d.picked, "weather": true]
        if !d.vehicle.isEmpty { b["vehicleId"] = d.vehicle }
        if let n = Int(d.passengers) { b["passengers"] = n }
        return b
    }
    private func build() async {
        if d.plan != nil { return }
        let r = await repo.optimise(body0())
        noOffice = r.reason == "no-office"
        if r.ok, let p = r.json.obj("plan") { d.plan = p }
        else { err = r.status == 0 ? t("No signal: the plan is built on the server. Try again when you're connected.", "Hakuna mtandao: mpango hutengenezwa kwenye seva. Jaribu tena ukiunganishwa.") : tripReason(r.reason) }
    }
    private func save(_ p: JSON) {
        saving = true; err = nil
        var b = body0(); b["title"] = d.title.isEmpty ? t("Trip", "Safari") : d.title; b["plan"] = p
        Task {
            let (x, why) = await repo.create(b)
            saving = false
            if let x { TripDraft.current = nil; m.back(); m.go(Place(tab: m.here.tab, sub: "trip:" + x.id)) } else { err = why }
        }
    }
}

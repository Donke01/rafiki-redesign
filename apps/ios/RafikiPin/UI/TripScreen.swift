import MapKit
import SwiftUI
import UIKit

/// What a trip screen changes: re-timed on the phone at once, then sent (or kept until there is signal).
@MainActor
final class TripCtl: ObservableObject {
    let repo: TripRepo; let id: String
    @Published var trip: TripItin?
    @Published var prog: TripProgress?
    @Published var msg: String?
    @Published var err: String?
    @Published var loaded = false
    init(repo: TripRepo, id: String) { self.repo = repo; self.id = id; trip = repo.cached(id) }

    func edit(_ local: TripItin, _ body: JSON) {
        trip = local; msg = nil
        Task { if let e = await repo.change(local, body) { msg = e }; if let c = repo.cached(id) { trip = c } }
    }
    /// A change that needs the server (re-order, swap, share…): shows its answer.
    func ask(_ body: JSON, _ done: ((Reply) -> Void)? = nil) {
        Task {
            let r = await repo.ask(id, body)
            if !r.ok { msg = r.status == 0 ? tripReason("offline") : tripReason(r.reason) }
            if let c = repo.cached(id) { trip = c }
            done?(r)
        }
    }
    func refresh() { Task { if let x = await repo.open(id).0 { trip = x } } }
    func stops(_ local: TripItin, _ changed: [String] = []) { edit(local, TripEdit.stopsBody(local, changed: changed)) }

    /// A warning's one-tap fix.
    func fix(_ w: TripWarn) {
        guard let it = trip, let f = w.fix else { return }
        switch chatS(f, "action") {
        case "move":
            let sid = chatS(f, "sid")
            if let from = it.days.firstIndex(where: { $0.stops.contains(sid) }) { stops(TripEdit.toDay(it, from, sid, f.int("toDay")), [sid]) }
        case "swap":
            let a = f.int("day"), b = f.int("with")
            edit(TripEdit.swapDays(it, a, b), ["action": "swap-days", "day": a, "with": b])
        case "extend":
            let next = DateField.parse(it.to).flatMap { Calendar.current.date(byAdding: .day, value: 1, to: $0) }.map { DateField.text($0) } ?? it.to
            ask(["action": "update", "to": next]) { r in if r.ok { self.ask(["action": "optimise", "keepDays": true]) } }
        default: break
        }
    }
    func fixLabel(_ w: TripWarn) -> String? {
        guard let f = w.fix, let it = trip else { return nil }
        switch chatS(f, "action") {
        case "move": let d = f.int("toDay"); return d < it.days.count ? t("Move to ", "Hamishia ") + TripDates.weekday(it.days[d].date) : nil
        case "swap": let d = f.int("with"); return d < it.days.count ? t("Swap with ", "Badilisha na ") + TripDates.weekday(it.days[d].date) : nil
        case "extend": return t("Add a day", "Ongeza siku")
        default: return nil
        }
    }
    /// Check in: the arrival time is kept (real leg times for the trip engine).
    func checkIn(_ sid: String, by: String, at fix: Fix?) {
        guard let it = trip else { return }
        let at = When.now()
        var b: JSON = ["action": "checkin", "sid": sid, "at": at]
        if let f = fix { b["lat"] = f.ll.lat; b["lng"] = f.ll.lng }
        edit(TripEdit.mark(it, sid, "done", at: at, by: by), b)
    }
}

/// One field trip: on the day (next stop, check in, running late), the plan, costs, packing and sharing.
struct TripView: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var repo: TripRepo
    let id: String
    var body: some View { TripViewBody(ctl: TripCtl.of(repo, id)) }
}
extension TripCtl {
    private static var kept: [String: TripCtl] = [:]
    static func of(_ repo: TripRepo, _ id: String) -> TripCtl { if let c = kept[id] { return c }; let c = TripCtl(repo: repo, id: id); kept[id] = c; return c }
}

struct TripViewBody: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var ctl: TripCtl
    @ObservedObject var loc = Locator.shared
    @State private var tab = "plan"
    @State private var day = -1
    @State private var sheet: String?
    @State private var stopSid: String?
    @State private var skipSid: String?
    @State private var replanDay: Int?
    @State private var night: Int?
    @State private var track: [[Any]] = []
    @State private var lastTrack: LatLng?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                BackLink(label: t("Back", "Rudi")) { m.back() }
                if let it = ctl.trip { content(it) } else {
                    Text(!ctl.loaded ? t("Loading…", "Inapakia…") : ctl.err ?? t("No signal, and this trip isn't saved on this phone yet.", "Hakuna mtandao, na safari hii bado haijahifadhiwa kwenye simu hii."))
                        .font(.bMedium).foregroundColor(C.muted).multilineTextAlignment(.center).frame(maxWidth: .infinity).padding(24)
                }
                Spacer().frame(height: 24)
            }.padding(16)
        }
        .task(id: ctl.id) { await load() }
        .onChange(of: loc.fix) { f in trackPoint(f) }
        .sheet(isPresented: isSet($sheet)) { sheetView }
        .sheet(isPresented: isSet($stopSid)) { if let sid = stopSid, let it = ctl.trip { TripStopSheet(ctl: ctl, sid: sid, day: dayOf(it, sid), live: isLive(it)).environmentObject(m).storySheet([.medium, .large]) } }
        .sheet(isPresented: isSet($skipSid)) { if let sid = skipSid { TripSkipSheet(ctl: ctl, sid: sid) { d in DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { replanDay = d } }.environmentObject(m).storySheet([.medium]) } }
        .sheet(isPresented: isSet($replanDay)) { if let d = replanDay { TripReplanSheet(ctl: ctl, day: d).environmentObject(m).storySheet([.medium, .large]) } }
        .sheet(isPresented: isSet($night)) { if let d = night { TripLodgingSheet(ctl: ctl, day: d).environmentObject(m).storySheet([.large]) } }
    }
    private func load() async {
        let today = TripOnDay.today()
        while !Task.isCancelled {
            let admin = m.snap?.admin == true
            let watch = admin && ctl.trip?.isOn(m.prefs.name) != true
            let (x, p, e) = await ctl.repo.open(ctl.id, progress: watch)
            if let x { ctl.trip = x }
            if let p { ctl.prog = p }
            ctl.err = e; ctl.loaded = true
            if day < 0, let it = ctl.trip { day = max(0, TripOnDay.dayIndex(it, today)) }
            if m.demo { demoSheet() }
            // the office's live view follows the day: a fresh look every minute while the trip is on
            guard watch, let it = ctl.trip, it.from <= today, today <= it.to else { break }
            try? await Task.sleep(nanoseconds: 60_000_000_000)
        }
    }
    private func demoSheet() {
        guard let s = Demo.arg("-sheet") else { return }
        if s.hasPrefix("tab-") { tab = String(s.dropFirst(4)); return }
        switch s {
        case "stop": stopSid = ctl.trip?.days.first?.stops.first
        case "skip": skipSid = ctl.trip?.days.first?.stops.first
        case "replan": replanDay = 0
        case "night": night = 0
        default: sheet = s
        }
    }
    private func isLive(_ it: TripItin) -> Bool { let today = TripOnDay.today(); return it.from <= today && today <= it.to && it.status != "done" && it.status != "cancelled" }
    private func dayOf(_ it: TripItin, _ sid: String) -> Int { it.days.firstIndex { $0.stops.contains(sid) } ?? 0 }

    /// The GPS track while on the road (km driven), sent 20 points at a time.
    private func trackPoint(_ f: Fix?) {
        guard let f, let it = ctl.trip, isLive(it), it.isOn(m.prefs.name), !m.demo else { return }
        if let l = lastTrack, Geo.metres(l, f.ll) < 150 { return }
        lastTrack = f.ll
        track.append([f.ll.lat, f.ll.lng, When.iso.string(from: f.at)])
        if track.count >= 20 { let send = track; track = []; ctl.ask(["action": "track", "points": send]) }
    }

    @ViewBuilder private func content(_ it: TripItin) -> some View {
        let me = m.prefs.name
        let today = TripOnDay.today()
        let onTrip = it.isOn(me)
        let live = isLive(it)
        let editable = it.canEdit && it.status != "done" && it.status != "cancelled"
        Text(it.title.isEmpty ? t("Trip", "Safari") : it.title).font(.hMedium).foregroundColor(C.ink).multilineTextAlignment(.center).frame(maxWidth: .infinity).fixedSize(horizontal: false, vertical: true)
        Text(TripDates.range(it.from, it.to) + " · " + (it.stops.count == 1 ? t("1 stop", "kituo 1") : t("\(it.stops.count) stops", "vituo \(it.stops.count)"))).font(.bMedium).foregroundColor(C.muted).multilineTextAlignment(.center).frame(maxWidth: .infinity)
        HStack(spacing: 8) {
            TripStatusTag(status: it.status)
            HStack(spacing: -6) { ForEach(Array(it.people.prefix(4)), id: \.self) { p in TripInitials(name: p, size: 28).overlay(Circle().stroke(C.card, lineWidth: 1.5)) } }
            if it.people.count > 4 { Text("+\(it.people.count - 4)").font(.lSmall).foregroundColor(C.muted) }
        }.frame(maxWidth: .infinity).padding(.top, 8)
        if it.waiting > 0 { TripBanner(icon: "clock", text: it.waiting == 1 ? t("1 change waiting to send", "Badiliko 1 linasubiri kutumwa") : t("\(it.waiting) changes waiting to send", "Mabadiliko \(it.waiting) yanasubiri kutumwa"), hue: ChatHues.warn) }
        if !it.canEdit { TripBanner(icon: "lock", text: t("You can see this trip but not change it.", "Unaweza kuona safari hii lakini huwezi kuibadilisha."), hue: ChatHues.named("slate")) }
        Msg(ctl.msg, "bad")
        if live && onTrip { onTheDay(it, today) }
        if live && !onTrip, let p = ctl.prog { TripProgressCard(it: it, p: p) }
        if it.status == "planned" && editable && onTrip && it.from <= today {
            Btn(t("Start the trip", "Anza safari"), icon: "nav", ok: true, wide: true) { ctl.ask(["action": "update", "status": "active"]) }.padding(.top, 10)
        }
        TripTabs(items: [("plan", t("Plan", "Mpango")), ("costs", t("Costs", "Gharama")), ("pack", t("Packing", "Mzigo")), ("share", t("Share", "Shiriki"))], sel: tab) { tab = $0 }.padding(.top, 16)
        switch tab {
        case "costs": TripCostsPane(ctl: ctl, it: it, editable: editable) { k in sheet = k }
        case "pack": TripPackingPane(ctl: ctl, it: it, editable: editable)
        case "share": TripSharePane(ctl: ctl, it: it) { k in sheet = k }
        default: planPane(it, editable: editable, live: live && onTrip)
        }
    }

    @ViewBuilder private func onTheDay(_ it: TripItin, _ today: String) -> some View {
        let late = TripOnDay.lateBy(it, today, TripOnDay.nowMin())
        TripNextStop(it: it, checkIn: { sid in checkIn(it, sid) }, skip: { sid in skipSid = sid })
        if late >= 30 && TripOnDay.dayIndex(it, today) >= 0 {
            Button { replanDay = TripOnDay.dayIndex(it, today) } label: {
                HStack(spacing: 10) {
                    Icon("clock", size: 20, color: C.warn)
                    Text(t("Running \(late) min late. Move the rest?", "Umechelewa dakika \(late). Hamisha vilivyobaki?")).font(.mont(14, .bold)).foregroundColor(C.warn).fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                    Icon("chev", size: 18, color: C.warn)
                }.padding(12).background(C.warnBg).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            }.buttonStyle(.plain).padding(.top, 10)
        }
        HStack(spacing: 8) {
            Btn(t("Report the road", "Ripoti barabara"), icon: "alert", light: true, small: true, wide: true) { sheet = "road" }
            Btn(t("Re-plan", "Panga upya"), icon: "refresh", light: true, small: true, wide: true) { replanDay = max(0, TripOnDay.dayIndex(it, today)) }
        }.padding(.top, 10)
    }
    private func checkIn(_ it: TripItin, _ sid: String) {
        ctl.checkIn(sid, by: m.prefs.name, at: Locator.shared.fix)
        if let pid = it.stop(sid)?.pid, !pid.isEmpty, m.snap?.projects.contains(where: { $0.id == pid }) == true {
            m.go(Place(tab: m.here.tab, project: pid, projectTab: "visit"))
        }
    }

    @ViewBuilder private func planPane(_ it: TripItin, editable: Bool, live: Bool) -> some View {
        let d = min(max(0, day), max(0, it.days.count - 1))
        if it.days.count > 1 {
            TripChoices(opts: it.days.enumerated().map { ("\($0.offset)", TripDates.weekday($0.element.date)) }, sel: "\(d)") { k in day = Int(k) ?? 0 }.padding(.top, 6)
        }
        if !it.days.isEmpty {
            TripMapView(it: it, day: d, prog: ctl.prog)
            TripDayCard(ctl: ctl, it: it, i: d, editable: editable, live: live && it.days[d].date == TripOnDay.today(), tapStop: { stopSid = $0 }, tapNight: { night = $0 })
        }
        if !it.unplanned.isEmpty {
            TripHead(text: t("Didn't fit", "Hazikutosha"), extra: "\(it.unplanned.count)")
            ForEach(it.unplanned.compactMap { it.stop($0) }, id: \.sid) { s in Text("· " + s.name).font(.bMedium).foregroundColor(C.text).padding(.leading, 6) }
        }
        ForEach(it.warnings) { w in TripWarnRow(w: w, ctl: ctl) }
        if editable {
            HStack(spacing: 8) {
                Btn(t("Add a stop", "Ongeza kituo"), icon: "plus", light: true, small: true, wide: true) { sheet = "add:\(d)" }
                Btn(t("Shortest order", "Mpangilio mfupi"), icon: "compass", light: true, small: true, wide: true) { ctl.ask(["action": "optimise", "keepDays": true]) }
            }.padding(.top, 14)
        }
    }

    @ViewBuilder private var sheetView: some View {
        let s = sheet ?? ""
        if s == "road" { TripRoadSheet().environmentObject(m).storySheet([.large]) }
        else if s.hasPrefix("add:"), let it = ctl.trip { TripAddStopSheet(ctl: ctl, it: it, day: Int(s.dropFirst(4)) ?? 0).environmentObject(m).storySheet([.large]) }
        else if s == "fuel", let it = ctl.trip { TripFuelSheet(ctl: ctl, it: it).environmentObject(m).storySheet([.large]) }
        else if s == "extra", let it = ctl.trip { TripExtraSheet(ctl: ctl, it: it).environmentObject(m).storySheet([.large]) }
        else if s == "team", let it = ctl.trip { TripShareTeamSheet(ctl: ctl, it: it).environmentObject(m).storySheet([.large]) }
        else if s == "chat", let it = ctl.trip { TripShareChatSheet(hub: m.chat, tripId: it.id).environmentObject(m).storySheet([.large]) }
    }
}

/// The office's live view: done, late or skipped per stop, and where people are.
struct TripProgressCard: View {
    let it: TripItin; let p: TripProgress
    var body: some View {
        let st = p.stops.values.map { chatS($0, "state") }
        let c: (Int, Int, Int) = st.isEmpty ? TripOnDay.counts(it) : (st.filter { $0 == "done" }.count, st.filter { $0 == "late" }.count, st.filter { $0 == "skipped" }.count)
        CardV {
            HStack(spacing: 8) {
                Circle().fill(C.ok).frame(width: 10, height: 10)
                Text(t("Live", "Moja kwa moja") + (p.now.isEmpty ? "" : " · " + p.now)).font(.tMedium).foregroundColor(C.ink)
                Spacer(minLength: 4)
                Text(t("\(c.0) done", "\(c.0) zimekamilika") + (c.1 > 0 ? t(" · \(c.1) late", " · \(c.1) zimechelewa") : "") + (c.2 > 0 ? t(" · \(c.2) skipped", " · \(c.2) zimerukwa") : "")).font(.lMedium).foregroundColor(C.muted).multilineTextAlignment(.trailing)
            }
            ForEach(Array(p.where_.enumerated()), id: \.offset) { _, w in
                HStack(spacing: 8) { TripInitials(name: w.0, size: 24); Text(w.0 + " · " + t("seen ", "alionekana ") + ago(w.2)).font(.bSmall).foregroundColor(C.text).lineLimit(1) }.padding(.top, 8)
            }
        }.padding(.top, 12)
    }
}

/// A map of the day: its stops (done green, skipped grey, next blue) and the people sharing their location.
struct TripMapView: View {
    let it: TripItin; let day: Int; let prog: TripProgress?
    struct Pt: Identifiable { let id: String; let ll: LatLng; let color: Color; let label: String }
    var body: some View {
        let stops = it.dayStops(day).filter { $0.ll != nil }
        let pts: [Pt] = stops.enumerated().compactMap { i, s in
            guard let ll = s.ll else { return nil }
            let col: Color = s.status == "skipped" ? Color(hex: 0x8A99A6) : !s.open ? Color(hex: 0x1C7C4A) : Color(hex: 0x0A78C2)
            return Pt(id: s.sid, ll: ll, color: col, label: "\(i + 1)")
        } + (prog?.where_ ?? []).map { Pt(id: "p:" + $0.0, ll: $0.1, color: Color(hex: 0xD2553A), label: String($0.0.prefix(1))) }
        if !pts.isEmpty {
            TripMapBox(pts: pts).frame(height: 170).clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous)).padding(.top, 12)
                .accessibilityLabel(t("Map of the day's stops", "Ramani ya vituo vya siku"))
        }
    }
}
struct TripMapBox: View {
    let pts: [TripMapView.Pt]
    @State private var region = MKCoordinateRegion()
    var body: some View {
        Map(coordinateRegion: $region, annotationItems: pts) { p in
            MapAnnotation(coordinate: CLLocationCoordinate2D(latitude: p.ll.lat, longitude: p.ll.lng)) {
                Text(p.label).font(.mont(12, .heavy)).foregroundColor(.white).frame(width: 26, height: 26).background(p.color).clipShape(Circle()).overlay(Circle().stroke(Color.white, lineWidth: 2))
            }
        }
        .onAppear { fit() }
        .onChange(of: pts.map(\.id)) { _ in fit() }
    }
    private func fit() {
        let la = pts.map(\.ll.lat), ln = pts.map(\.ll.lng)
        guard let a = la.min(), let b = la.max(), let c = ln.min(), let d = ln.max() else { return }
        region = MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: (a + b) / 2, longitude: (c + d) / 2),
                                    span: MKCoordinateSpan(latitudeDelta: max(0.02, (b - a) * 1.5), longitudeDelta: max(0.02, (d - c) * 1.5)))
    }
}

/// A warning with its one-tap fix.
struct TripWarnRow: View {
    let w: TripWarn; let ctl: TripCtl?
    var body: some View {
        let hu = tripRiskHue(w.level)
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 8) {
                CIcon(w.level == "info" ? "info" : "alert", size: 18, color: hu.fg)
                Text(w.text).font(.mont(w.level == "info" ? 13 : 14, .semibold)).foregroundColor(hu.fg).fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            if let ctl, let label = ctl.fixLabel(w) {
                Button { ctl.fix(w) } label: {
                    Text(label).font(.mont(14, .heavy)).foregroundColor(hu.fg).lineLimit(1).padding(.horizontal, 14).frame(minHeight: 40).overlay(Capsule().stroke(hu.fg, lineWidth: 1.5))
                }.buttonStyle(.plain)
            }
        }.padding(12).background(hu.bg).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).padding(.top, 8)
    }
}

/// A day: its numbers, warnings, the timeline (leave → drive → stop → lunch → … → return) and tonight's lodging.
struct TripDayCard: View {
    @ObservedObject var ctl: TripCtl
    let it: TripItin; let i: Int; let editable: Bool; let live: Bool; let tapStop: (String) -> Void; let tapNight: (Int) -> Void
    var body: some View {
        let d = it.days[i]
        let plan = TripTimeline.day(it, i, now: live ? TripOnDay.nowMin() : nil)
        CardV(pad: 14) {
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(TripDates.weekday(d.date)).font(.tMedium).foregroundColor(C.ink)
                    Text(tripKm(plan.km) + " · " + t("driving ", "kuendesha ") + TripHm.dur(plan.driveMin) + " · " + t("back ", "kurudi ") + TripHm.text(plan.endsAt)).font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 4)
                TChip(text: tripRiskName(d.risk), icon: d.risk == "ok" ? "check" : "alert", hue: tripRiskHue(d.risk))
            }
            ForEach(TripTimeline.warnings(it, i, plan)) { w in TripWarnRow(w: w, ctl: editable ? ctl : nil) }
            Spacer().frame(height: 6)
            ForEach(Array(plan.rows.enumerated()), id: \.offset) { _, r in row(r) }
            if i < it.days.count - 1 { nightRow(d) }
        }.padding(.top, 12)
    }
    @ViewBuilder private func row(_ r: TripRow) -> some View {
        switch r.kind {
        case "leave": point(TripHm.text(r.at), t("Leave", "Ondoka") + " · " + (it.placeName(r.from).isEmpty ? t("start", "mwanzo") : it.placeName(r.from)), "nav")
        case "drive": if let l = r.leg { legRow(l) }
        case "wait": Text(TripHm.text(r.at) + "–" + TripHm.text(r.until) + "  " + t("Wait \(r.until - r.at)\u{00A0}min", "Subiri dakika\u{00A0}\(r.until - r.at)")).font(.bSmall).foregroundColor(C.muted).padding(.leading, 48).padding(.vertical, 2)
        case "lunch": point(TripHm.text(r.at) + "–" + TripHm.text(r.until), t("Lunch", "Chakula cha mchana"), "leaf", muted: true)
        case "skipped": if let s = it.stop(r.sid) { point("", t("Skipped", "Imerukwa") + " · " + s.name, "x", muted: true) }
        case "overnight": point(TripHm.text(r.at), t("Overnight in ", "Kulala ") + it.placeName(r.to), "house")
        case "return": point(TripHm.text(r.at), t("Back at ", "Kurudi ") + (it.placeName(r.to).isEmpty ? t("the start", "mwanzo") : it.placeName(r.to)), "nav")
        case "stop": if let s = it.stop(r.sid) { TripStopRow(it: it, r: r, s: s, prog: ctl.prog, live: live) { tapStop(s.sid) } }
        default: EmptyView()
        }
    }
    private func point(_ time: String, _ text: String, _ icon: String, muted: Bool = false) -> some View {
        HStack(spacing: 8) {
            Text(time).font(.mont(12, .bold)).foregroundColor(C.muted).lineLimit(1).fixedSize().frame(width: time.count > 5 ? 92 : 44, alignment: .leading)
            CIcon(icon, size: 16, color: muted ? C.muted : C.blue2)
            Text(text).font(.bMedium).foregroundColor(muted ? C.muted : C.ink).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }.padding(.vertical, 4)
    }
    private func legRow(_ l: TripLeg) -> some View {
        HStack(alignment: .center, spacing: 0) {
            Rectangle().fill(C.line).frame(width: 2, height: 34).padding(.leading, 20)
            TripFlow(spacing: 4) {
                TChip(text: (l.time.isEmpty ? "~" + TripHm.dur(l.min) : l.time) + (l.km > 0 ? " · " + tripKm(l.km) + (l.estimate || !l.roads ? t(" (estimate)", " (makadirio)") : "") : ""), icon: "clock", hue: ChatHues.named("blue"))
                if !l.surface.isEmpty { TChip(text: l.surface, icon: "map") }
                if !l.rain.isEmpty { TChip(text: l.rain, icon: "cloud", hue: l.roadLevel == "heavy" || l.roadLevel == "wet" ? ChatHues.warn : ChatHues.named("teal")) }
                if l.risk != "ok" { TChip(text: tripRiskName(l.risk), icon: "alert", hue: tripRiskHue(l.risk)) }
            }.padding(.leading, 26).padding(.vertical, 4)
        }
    }
    private func nightRow(_ d: TripDay) -> some View {
        let hu = ChatHues.named("purple")
        let n = d.night
        let sub: String = {
            guard let n else { return "" }
            let parts = [n.lodging.isEmpty ? nil : n.lodging, n.kes > 0 ? tripKes(n.kes) : nil, !n.why.isEmpty && n.lodging.isEmpty ? n.why : nil].compactMap { $0 }
            return parts.isEmpty && editable ? t("Add where you'll stay", "Ongeza utakapolala") : parts.joined(separator: " · ")
        }()
        return Button { if editable { tapNight(i) } } label: {
            HStack(spacing: 10) {
                CIcon("house", size: 20, color: hu.fg)
                VStack(alignment: .leading, spacing: 2) {
                    Text(n == nil ? t("Home tonight", "Nyumbani usiku huu") : t("Tonight: ", "Usiku huu: ") + (n?.town ?? "") + (n?.suggested == true ? t(" (suggested)", " (inapendekezwa)") : "")).font(.tSmall).foregroundColor(hu.fg).fixedSize(horizontal: false, vertical: true)
                    if !sub.isEmpty { Text(sub).font(.bSmall).foregroundColor(hu.fg.opacity(0.85)).fixedSize(horizontal: false, vertical: true) }
                }
                Spacer(minLength: 0)
                if editable { Icon("chev", size: 18, color: hu.fg) }
            }.padding(12).background(hu.bg).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }.buttonStyle(.plain).disabled(!editable).padding(.top, 10)
    }
}

/// A stop: its times, name, status (done / late / skipped / next), lock and window warning.
struct TripStopRow: View {
    let it: TripItin; let r: TripRow; let s: TripStop; let prog: TripProgress?; let live: Bool; let tap: () -> Void
    var body: some View {
        let p = prog?.stops[s.sid]
        let st: String = {
            if let x = p.map({ chatS($0, "state") }), !x.isEmpty { return x }
            if s.status == "late" { return "late" }
            if !s.open { return "done" }
            if live && TripOnDay.next(it, TripOnDay.today()) == s.sid { return "next" }
            return ""
        }()
        Button { Haptic.tap(); tap() } label: {
            HStack(spacing: 6) {
                VStack(alignment: .leading, spacing: 1) {
                    Text(TripHm.text(r.at)).font(.mont(14, .heavy)).foregroundColor(C.ink).lineLimit(1).fixedSize()
                    Text(TripHm.text(r.until)).font(.mont(11, .medium)).foregroundColor(C.muted).lineLimit(1).fixedSize()
                }.frame(width: 50, alignment: .leading).padding(.leading, 8)
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 4) {
                        if s.fixed != nil { Icon("lock", size: 15, color: C.blue2).accessibilityLabel(t("Time locked", "Muda umefungwa")) }
                        Text(s.name).font(.tSmall).foregroundColor(C.ink).lineLimit(2).multilineTextAlignment(.leading)
                    }
                    if let w = r.warn { Text(w.text).font(.mont(12, .semibold)).foregroundColor(C.warn).fixedSize(horizontal: false, vertical: true).multilineTextAlignment(.leading) }
                    state(st, p)
                }.padding(.vertical, 10)
                Spacer(minLength: 0)
                Icon("chev", size: 16, color: C.muted).padding(.trailing, 8)
            }.background(r.warn != nil ? C.warnBg : C.tint).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).contentShape(Rectangle())
        }.buttonStyle(.plain).padding(.vertical, 4)
    }
    @ViewBuilder private func state(_ st: String, _ p: JSON?) -> some View {
        switch st {
        case "done":
            let at = p.map { chatS($0, "at") } ?? ""
            TChip(text: t("Done", "Imekamilika") + (!at.isEmpty ? " " + at : TripTimeline.clock(s.checkinAt).map { " " + TripHm.text($0) } ?? ""), icon: "check", hue: ChatHues.ok)
        case "late": TChip(text: t("Late", "Imechelewa") + ((p?.int("lateMin") ?? 0) > 0 ? t(" \(p?.int("lateMin") ?? 0) min", " dak \(p?.int("lateMin") ?? 0)") : ""), icon: "clock", hue: ChatHues.warn)
        case "skipped": TChip(text: t("Skipped", "Imerukwa"), icon: "x")
        case "next": TChip(text: t("Next", "Kinachofuata"), icon: "next", hue: ChatHues.named("blue"))
        case "due": TChip(text: t("Due now", "Sasa"), icon: "clock", hue: ChatHues.named("amber"))
        default: EmptyView()
        }
    }
}

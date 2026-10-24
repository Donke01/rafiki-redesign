import SwiftUI
import UIKit

private let TYPES = [("borehole", "Borehole well", "Kisima cha borehole"), ("collection", "Water collection", "Ukusanyaji wa maji"), ("school", "School", "Shule"),
                     ("health", "Health", "Afya"), ("children", "Children's home", "Makao ya watoto"), ("farming", "Farming", "Kilimo"),
                     ("community", "Community", "Jamii"), ("other", "Other", "Nyingine")]

/// Two columns of choice buttons.
struct PillGrid: View {
    let items: [(String, String)]; let value: String; let pick: (String) -> Void
    var body: some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 6), GridItem(.flexible(), spacing: 6)], spacing: 6) {
            ForEach(items, id: \.0) { k, l in Pill(l, on: value == k) { pick(k) } }
        }
    }
}

/// A half-typed New site kept on the phone (newsite.json), offered again (Resume / Start again) when the screen opens.
enum NewSiteDrafts {
    static var file: URL { Prefs.dataDir.appendingPathComponent("newsite.json") }
    static func save(_ j: JSON) { var x = j; x["saved"] = Date().timeIntervalSince1970; if let d = try? JSONSerialization.data(withJSONObject: x) { try? d.write(to: file, options: .atomic) } }
    static func load() -> JSON? { (try? Data(contentsOf: file)).flatMap { try? JSONSerialization.jsonObject(with: $0) as? JSON } }
    static func remove() { try? FileManager.default.removeItem(at: file) }
    /// Start again: the kept form and its photos go.
    static func discard() { load()?.objs("shots").forEach { try? FileManager.default.removeItem(at: Outbox.resolve($0.str("path"))) }; remove() }
}
/// A long press on the map ("Add a new site here") starts New site at that point.
enum NewSiteStart { static var at: LatLng? }

struct NewSiteView: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var loc = Locator.shared
    @StateObject private var spot = Spot()
    @State private var name = ""
    @State private var ck = ""
    @State private var type = "borehole"
    @State private var village = ""
    @State private var note = ""
    @State private var shots: [Shot] = []
    @State private var msg: (String, String)?
    @State private var busy = false
    @State private var offer: JSON? = NewSiteDrafts.load()
    @State private var start: LatLng? = NewSiteStart.at

    private var blank: Bool { name.isEmpty && village.isEmpty && note.isEmpty && shots.isEmpty }
    private func keep() {
        guard offer == nil else { return }
        if blank { NewSiteDrafts.remove(); return }
        var j: JSON = ["v": 1, "name": name, "ck": ck, "type": type, "village": village, "note": note, "shots": shots.map { ["path": $0.path, "label": $0.label] }]
        if let ll = spot.ll { j["lat"] = ll.lat; j["lng"] = ll.lng }
        NewSiteDrafts.save(j)
    }

    var body: some View {
        if let o = offer { resume(o) } else { form }
    }

    private func resume(_ o: JSON) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text(t("New site", "Eneo jipya")).font(.hLarge).foregroundColor(C.ink).wide(.center)
                CardV {
                    IconTile(icon: "clock", tint: C.blue, bg: C.tint, size: 56)
                    let at0 = Dates.timeOrDateTime(Date(timeIntervalSince1970: o.num("saved") ?? Date().timeIntervalSince1970))
                    Text(t("Carry on with the new site from \(at0)?", "Uendelee na eneo jipya ulilolianza \(at0)?")).font(.tLarge).foregroundColor(C.ink).padding(.top, 12)
                    Text(o.str("name").isEmpty ? t("No name yet", "Bado halina jina") : o.str("name")).font(.bMedium).foregroundColor(C.muted).padding(.top, 4)
                    Btn(t("Resume", "Endelea nalo"), icon: "arrow", wide: true) {
                        name = o.str("name"); if !o.str("ck").isEmpty { ck = o.str("ck") }; if !o.str("type").isEmpty { type = o.str("type") }
                        village = o.str("village"); note = o.str("note")
                        shots = o.objs("shots").map { Shot(path: $0.str("path"), label: $0.str("label")) }.filter { FileManager.default.fileExists(atPath: Outbox.resolve($0.path).path) }
                        if start == nil, let la = o.num("lat"), let ln = o.num("lng") { spot.ll = LatLng(lat: la, lng: ln); start = spot.ll }
                        offer = nil
                    }.padding(.top, 16)
                    Btn(t("Start again", "Anza upya"), icon: "refresh", light: true, wide: true) { NewSiteDrafts.discard(); offer = nil }.padding(.top, 10)
                }.padding(.top, 14)
            }.padding(16)
        }
    }

    private var form: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text(t("New site", "Eneo jipya")).font(.hLarge).foregroundColor(C.ink).wide(.center)
                Text(t("The office checks every new site first. It isn’t on the website until they approve it.", "Ofisi hukagua kila eneo jipya kwanza. Halionekani kwenye tovuti hadi ofisi iliidhinishe.")).font(.bMedium).foregroundColor(C.muted)
                CardV {
                    Field(t("Site name", "Jina la eneo"), $name, placeholder: t("e.g. Kiptoo Primary School", "mf. Shule ya Msingi Kiptoo"))
                    Text(t("Country", "Nchi")).font(.lMedium).foregroundColor(C.ink).padding(.top, 14).padding(.bottom, 6)
                    PillGrid(items: COUNTRIES, value: ck) { ck = $0 }
                    Text(t("Type", "Aina")).font(.lMedium).foregroundColor(C.ink).padding(.top, 14).padding(.bottom, 6)
                    PillGrid(items: TYPES.map { ($0.0, t($0.1, $0.2)) }, value: type) { type = $0 }
                    Field(t("Village / community", "Kijiji / jamii"), $village, placeholder: t("e.g. Kiptoo, Nandi", "mf. Kiptoo, Nandi"))
                    Field(t("Notes (optional)", "Maelezo (si lazima)"), $note, placeholder: t("Who asked, how many people, current water source…", "Nani aliomba, watu wangapi, chanzo cha maji sasa…"), lines: 3)
                }.padding(.top, 14)
                CardV {
                    H2(t("Where is it?", "Iko wapi?"))
                    SpotPicker(spot: spot, start: start ?? loc.fix?.ll, ck: ck)
                }.padding(.top, 14)
                CardV {
                    H2(t("Photos (optional)", "Picha (si lazima)"))
                    PhotoSlots(slots: [], shots: $shots, max: 5)
                }.padding(.top, 14)
                let ready = !name.trimmingCharacters(in: .whitespaces).isEmpty && spot.ll != nil
                Btn(t("Add this site", "Ongeza eneo hili"), icon: "plus", enabled: ready && !busy, wide: true) { add() }.padding(.top, 16)
                if !ready { Msg(t("Add the name, then choose where it is.", "Weka jina, kisha chagua mahali lilipo.")) }
                if case let (tone, text)? = msg { Msg(text, tone) }
                Spacer().frame(height: 24)
            }.padding(16)
        }
        .onAppear {
            if ck.isEmpty { ck = m.prefs.country.isEmpty ? "ke" : m.prefs.country }
            if let a = NewSiteStart.at { spot.ll = a; NewSiteStart.at = nil }
        }
        .onChange(of: name) { _ in keep() }.onChange(of: village) { _ in keep() }.onChange(of: note) { _ in keep() }
        .onChange(of: ck) { _ in keep() }.onChange(of: type) { _ in keep() }.onChange(of: shots) { _ in keep() }.onChange(of: spot.ll) { _ in keep() }
    }

    func add() {
        guard let ll = spot.ll else { return }
        busy = true
        Task {
            let body: JSON = ["action": "new", "name": name.trimmingCharacters(in: .whitespaces), "ck": ck, "village": village.trimmingCharacters(in: .whitespaces), "type": type,
                              "note": note.trimmingCharacters(in: .whitespacesAndNewlines), "lat": ll.lat, "lng": ll.lng]
            switch await m.submit(body, shots.map { OutFile(path: $0.path, kind: "photo", label: "Site photo") }) {
            case .sent(let reply, _):
                busy = false; name = ""; village = ""; note = ""; shots = []; NewSiteDrafts.remove()
                await m.refreshNow()
                let id = reply.str("id")
                m.tab("list"); if !id.isEmpty { m.go(Place(tab: "list", project: id)) }
            case .queued: busy = false; msg = ("warn", t("Saved on this phone. It will be added when you have signal.", "Imehifadhiwa kwenye simu. Itaongezwa ukipata mtandao.")); name = ""; village = ""; note = ""; shots = []; NewSiteDrafts.remove()
            case .failed(let s): busy = false; msg = ("bad", s)
            case .locked: busy = false
            }
        }
    }
}

struct MeView: View {
    @EnvironmentObject var m: AppModel
    @State private var pane = "me"
    var body: some View {
        ScrollViewReader { sv in
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    // the title in the middle, Me | Team under it (the bell is in the app header, top right)
                    Text(t("Me & team", "Mimi na timu")).font(.hLarge).foregroundColor(C.ink).wide(.center).id("top")
                    SmallSeg(items: [("me", t("Me", "Mimi")), ("team", t("Team", "Timu"))], sel: pane) { p in pane = p; m.prefs.mePane = p; sv.scrollTo("top", anchor: .top) }
                        .wide(.center).padding(.top, 10)
                    if pane == "team" {
                        TeamToday(mates: m.team, act: m.activity, snap: m.snap)
                        TeamMonth(act: m.activity)
                        TeamFeed(act: m.activity, snap: m.snap)
                    } else {
                        MeHero(act: m.activity)
                        WaitingList(snap: m.snap)
                        if let snap = m.snap { MyWork(snap: snap) }
                        MyMonth(act: m.activity)
                        BadgeStamps()
                        SettingsCard()
                        Text("RAFIKI Pin " + AppInfo.version).font(.bSmall).foregroundColor(C.muted).wide(.center).padding(.top, 18)
                    }
                }.padding(16).padding(.bottom, 90)
            }
        }
        .onAppear { pane = m.prefs.mePane }
        .task { await m.loadActivity(); await m.loadTeam() }
        .refreshable { await m.refreshNow(quiet: true); await m.loadActivity(); await m.loadTeam() }
    }
}

private struct MeRow: View {
    let k: String; let v: String
    var body: some View {
        VStack(spacing: 0) {
            HStack { Text(k).font(.bMedium).foregroundColor(LightBlue); Spacer(); Text(v).font(.tSmall).foregroundColor(.white) }.padding(.vertical, 10)
            Rectangle().fill(Color.white.opacity(0.15)).frame(height: 1)
        }
    }
}

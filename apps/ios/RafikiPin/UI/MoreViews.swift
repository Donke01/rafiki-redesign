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

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text(t("Add a new site", "Ongeza eneo jipya")).font(.hLarge).foregroundColor(C.ink)
                Text(t("It goes onto the monday.com board as “Due Diligence”, so it isn’t on the website until the office moves it on.", "Inaingia kwenye monday.com kama “Due Diligence”, kwa hiyo haionekani kwenye tovuti hadi ofisi iisogeze.")).font(.bMedium).foregroundColor(C.muted)
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
                    SpotPicker(spot: spot, start: loc.fix?.ll, ck: ck)
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
        .onAppear { if ck.isEmpty { ck = m.prefs.country.isEmpty ? "ke" : m.prefs.country } }
    }

    func add() {
        guard let ll = spot.ll else { return }
        busy = true
        Task {
            let body: JSON = ["action": "new", "name": name.trimmingCharacters(in: .whitespaces), "ck": ck, "village": village.trimmingCharacters(in: .whitespaces), "type": type,
                              "note": note.trimmingCharacters(in: .whitespacesAndNewlines), "lat": ll.lat, "lng": ll.lng]
            switch await m.submit(body, shots.map { OutFile(path: $0.path, kind: "photo", label: "Site photo") }) {
            case .sent(let reply, _):
                busy = false
                await m.refreshNow()
                let id = reply.str("id")
                m.tab("list"); if !id.isEmpty { m.go(Place(tab: "list", project: id)) }
            case .queued: busy = false; msg = ("warn", t("Saved on this phone. It will be added when you have signal.", "Imehifadhiwa kwenye simu. Itaongezwa ukipata mtandao.")); name = ""; shots = []
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
                    HStack {
                        Text(t("Me & team", "Mimi na timu")).font(.hLarge).foregroundColor(C.ink).id("top")
                        Spacer(minLength: 8)
                        SmallSeg(items: [("me", t("Me", "Mimi")), ("team", t("Team", "Timu"))], sel: pane) { p in pane = p; m.prefs.mePane = p; sv.scrollTo("top", anchor: .top) }
                    }
                    if pane == "team" {
                        TeamToday(mates: m.team, act: m.activity, snap: m.snap)
                        TeamMonth(act: m.activity)
                        TeamFeed(act: m.activity, snap: m.snap)
                    } else {
                        MeHero(act: m.activity)
                        WaitingList(snap: m.snap)
                        if let snap = m.snap { MyWork(snap: snap) }
                        MyMonth(act: m.activity)
                        SettingsCard()
                        CardV {
                            H2(t("Monthly report", "Ripoti ya mwezi"))
                            Text(t("Visits, repairs and how projects are doing for one month, ready to share as a PDF.", "Ziara, matengenezo na hali ya miradi kwa mwezi mmoja, tayari kushiriki kama PDF.")).font(.bMedium).foregroundColor(C.muted)
                            Btn(t("Open report", "Fungua ripoti"), icon: "clip", small: true) { m.go(Place(tab: "me", sub: "report")) }.padding(.top, 10)
                        }.padding(.top, 14)
                        Text("RAFIKI Pin " + ((Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "")).font(.bSmall).foregroundColor(C.muted).padding(.top, 18)
                    }
                }.padding(16)
            }
        }
        .onAppear { pane = m.prefs.mePane }
        .task { await m.loadActivity(); await m.loadTeam() }
        .refreshable { await m.refreshNow(); await m.loadActivity(); await m.loadTeam() }
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

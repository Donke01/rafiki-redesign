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
    @Environment(\.colorScheme) var scheme
    @State private var miles = false
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text(t("Me & team", "Mimi na timu")).font(.hLarge).foregroundColor(C.ink)
                CardV(deep: true) {
                    HStack(spacing: 12) {
                        Text(m.prefs.name.split(separator: " ").compactMap { $0.first.map { String($0).uppercased() } }.prefix(2).joined())
                            .font(.tMedium).foregroundColor(.white).frame(width: 48, height: 48).background(Color.white.opacity(0.15)).clipShape(Circle())
                        Text(t("You", "Wewe")).font(.tLarge).foregroundColor(.white)
                    }
                    MeRow(k: t("Name", "Jina"), v: m.prefs.name)
                    MeRow(k: t("Role", "Nafasi"), v: m.snap?.admin == true ? t("Admin", "Msimamizi") : t("Field team", "Timu ya uwanjani"))
                    MeRow(k: t("Waiting to send", "Inasubiri kutumwa"), v: m.waiting == 0 ? t("Nothing", "Hakuna") : "\(m.waiting)")
                    Btn(t("Lock the app", "Funga programu"), icon: "lock", light: true, small: true) { m.lock() }.padding(.top, 14)
                }.padding(.top, 12)
                CardV {
                    H2(t("Colours", "Rangi"))
                    let cur = Look.mode.isEmpty ? (scheme == .dark ? "dark" : "light") : Look.mode
                    HStack(spacing: 6) {
                        ForEach([("light", t("Light", "Mwanga")), ("dark", t("Dark", "Giza")), ("sun", t("Bright sun", "Jua kali"))], id: \.0) { k, l in
                            Pill(l, on: cur == k) { m.setTheme(k) }.accessibilityIdentifier("theme_\(k)")
                        }
                    }.padding(.top, 6)
                    H2(t("Language", "Lugha")).padding(.top, 18)
                    LangSwitch().padding(.top, 6)
                    H2(t("Distances", "Umbali")).padding(.top, 18)
                    HStack(spacing: 6) {
                        Pill(t("Kilometres", "Kilomita"), on: !miles) { miles = false; m.prefs.miles = false }
                        Pill(t("Miles", "Maili"), on: miles) { miles = true; m.prefs.miles = true }
                    }
                }.padding(.top, 14)
                CardV {
                    H2(t("Monthly report", "Ripoti ya mwezi"))
                    Text(t("Visits, repairs and how projects are doing for one month, ready to share as a PDF.", "Ziara, matengenezo na hali ya miradi kwa mwezi mmoja, tayari kushiriki kama PDF.")).font(.bMedium).foregroundColor(C.muted)
                    Btn(t("Open report", "Fungua ripoti"), icon: "clip", small: true) { m.go(Place(tab: "me", sub: "report")) }.padding(.top, 10)
                }.padding(.top, 14)
                Text("RAFIKI Pin " + ((Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "")).font(.bSmall).foregroundColor(C.muted).padding(.top, 18)
            }.padding(16)
        }
        .onAppear { miles = m.prefs.miles }
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

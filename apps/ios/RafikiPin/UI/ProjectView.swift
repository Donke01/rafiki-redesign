import SwiftUI
import UIKit

struct ProjectView: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var loc = Locator.shared
    let id: String
    let startTab: String
    @State private var tab = ""
    @State private var cm: String?
    @State private var dirs = false

    var body: some View {
        if let snap = m.snap, let p = snap.projects.first(where: { $0.id == id }) {
            ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    BackLink(label: t("Back", "Rudi")) { m.back() }
                    if let ph = p.photo {
                        NetImage(url: m.api.photoUrl(ph, w: 960)) { C.tint }
                            .frame(maxWidth: .infinity).aspectRatio(16 / 9, contentMode: .fit).clipped()
                            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous)).padding(.vertical, 6)
                    }
                    hero(snap, p).padding(.top, 6)
                    Btn(t("Chat", "Gumzo"), icon: "chat", light: true, small: true, wide: true) { Haptic.tap(); ChatNav.shared.project(p.id) }.padding(.top, 8)
                    if let r = snap.repairs[p.id] { RepairTracker(p: p, r: r, here: loc.fix?.ll) }
                    ConstructionCard(p: p)
                    ProjectCostLine(p: p)
                    ProjectMore(p: p) { tab = "hist"; withAnimation(Motion.on ? .easeInOut(duration: 0.35) : nil) { proxy.scrollTo("ptabs", anchor: .top) } }
                    HStack(spacing: 0) {
                        ForEach([("pin", t("Pin location", "Weka mahali")), ("visit", t("Site visit", "Ziara")), ("hist", t("History", "Historia")), ("story", t("Stories", "Hadithi"))], id: \.0) { k, label in
                            Button { tab = k } label: {
                                FitWords(label, size: 14, weight: .bold, color: tab == k ? .white : C.ink, lines: 2, align: .center)
                                    .padding(.horizontal, 3).padding(.vertical, 4).frame(maxWidth: .infinity, minHeight: 44)
                                    .background(Group { if tab == k { BlueBrush } else { Color.clear } }).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                            }.buttonStyle(.plain).accessibilityIdentifier("tab_\(k)")
                        }
                    }
                    .padding(5).background(C.card).clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(C.line, lineWidth: 1)).padding(.top, 14).id("ptabs")
                    VStack(alignment: .leading, spacing: 0) {
                        switch tab {
                        case "visit": VisitTab(p: p)
                        case "hist": HistoryTab(p: p)
                        case "story": StoryTab(up: m.storyUp, p: p) { key in withAnimation(Motion.on ? .easeInOut(duration: 0.35) : nil) { proxy.scrollTo(key, anchor: .top) } }
                        default: PinTab(p: p)
                        }
                    }.padding(.top, 14)
                    Spacer().frame(height: 24)
                }.padding(16)
            }
            }
            .onAppear { if tab.isEmpty { tab = startTab } }
            .sheet(isPresented: $dirs) { if let g = p.gps { DirectionsSheet(title: p.title, at: g) { dirs = false } } }
            .id(p.id)
        } else {
            Color.clear.onAppear { m.back() }
        }
    }

    func hero(_ snap: Snapshot, _ p: Project) -> some View {
        CardV(deep: true) {
            Text(p.title).font(.hMedium).foregroundColor(.white)
            Text(p.sub).font(.bMedium).foregroundColor(LightBlue)
            if testDue(m, p) { WaterTestTag().padding(.top, 6) }
            Text(p.gps.map { t("Saved location: ", "Mahali palipohifadhiwa: ") + $0.text() } ?? t("No location saved yet.", "Bado hakuna mahali palipohifadhiwa.")).font(.bMedium).foregroundColor(LightBlue)
            let v = snap.visits[p.id]
            Text(v.map { t("Last visit: ", "Ziara ya mwisho: ") + When.text($0.at) + " · " + $0.by + " · " + stateName($0.state) } ?? t("No visit recorded in the app yet.", "Bado hakuna ziara iliyorekodiwa kwenye programu.")).font(.bMedium).foregroundColor(LightBlue)
            if snap.confirmed.contains(p.id) { Text(t("Location confirmed by the office", "Mahali pamethibitishwa na ofisi")).font(.lMedium).foregroundColor(Color(hex: 0x9BE7BC)).padding(.top, 4) }
            if snap.admin && p.gps != nil {
                let on = snap.confirmed.contains(p.id)
                Button {
                    Task { let x = await m.api.admin(["action": "confirm", "id": p.id, "on": !on]); if x.ok { m.reload() } else { cm = reasonText(x.reason) } }
                } label: {
                    Text(on ? t("Unlock this location", "Ondoa uthibitisho wa mahali") : t("Confirm this location", "Thibitisha mahali hapa")).font(.lLarge).foregroundColor(.white)
                        .padding(.horizontal, 14).padding(.vertical, 10).background(Color.white.opacity(0.15)).clipShape(Capsule())
                }.buttonStyle(.plain).padding(.top, 8)
                if let cm { Text(cm).font(.bSmall).foregroundColor(Color(hex: 0xFFE1DD)) }
            }
            if let here = loc.fix?.ll, let g = p.gps, let f = loc.fix {
                let d = Geo.metres(here, g); let far = d > 300
                Text(far ? t("You’re \(Geo.dist(d, miles: m.miles)) from the saved point · head \(Geo.heading(here, g)). If you’re standing at the site, the saved location may be wrong.",
                             "Uko \(Geo.dist(d, miles: m.miles)) kutoka mahali palipohifadhiwa · elekea \(Geo.heading(here, g)). Kama uko kwenye eneo, huenda mahali palipohifadhiwa si sahihi.")
                         : t("You’re \(Geo.dist(d, miles: m.miles)) away · head \(Geo.heading(here, g))", "Uko umbali wa \(Geo.dist(d, miles: m.miles)) · elekea \(Geo.heading(here, g))"))
                    .font(.mont(14, .bold)).foregroundColor(far ? Color(hex: 0xFFE1DD) : .white)
                    .frame(maxWidth: .infinity, alignment: .leading).padding(12)
                    .background(far ? Color(hex: 0xFF786E, alpha: 0.2) : Color.white.opacity(0.13)).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).padding(.top, 10)
                HStack(spacing: 6) {
                    Circle().fill(Color(hex: 0x9BE7BC)).frame(width: 8, height: 8)
                    Text(t("Live · GPS ±\(f.acc) m", "Moja kwa moja · GPS ±\(f.acc) m")).font(.lSmall).foregroundColor(Color(hex: 0x9BE7BC))
                }.padding(.top, 6)
            }
            if let g = p.gps {
                let ll = "\(g.lat),\(g.lng)"
                HStack(spacing: 8) {
                    MapTile(label: t("Directions", "Maelekezo"), icon: "compass") { Haptic.tap(); dirs = true }
                    MapTile(label: t("Share location", "Shiriki mahali"), icon: "share") { Share.items([p.title + "\nhttps://www.google.com/maps?q=\(ll)"]) }
                }.padding(.top, 12)
            }
        }
    }
}

private struct MapTile: View {
    let label: String; let icon: String; let action: () -> Void
    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Icon(icon, size: 21, color: Color(hex: 0x0A78C2)).frame(width: 38, height: 38).background(Color.white).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                Text(label).font(.lMedium).foregroundColor(.white).lineLimit(1).minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity).padding(.vertical, 12).padding(.horizontal, 4)
            .background(Color.white.opacity(0.12)).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Color.white.opacity(0.16), lineWidth: 1))
        }.buttonStyle(Press())
    }
}

private struct RepairCard: View {
    @EnvironmentObject var m: AppModel
    let p: Project; let r: Repair
    @State private var msg: String?
    @State private var busy = false
    var body: some View {
        let nw = r.state == "notworking"
        CardV(fill: nw ? C.badBg : C.warnBg) {
            HStack(spacing: 8) {
                Icon("alert", size: 20, color: nw ? C.bad : Amber)
                Text(nw ? t("Reported as not working", "Imeripotiwa haifanyi kazi") : t("Reported as needing repair", "Imeripotiwa inahitaji matengenezo")).font(.tSmall).foregroundColor(nw ? C.bad : C.warn)
            }
            Text(t("By ", "Na ") + r.by + " · " + When.text(r.at) + (r.urgency.isEmpty ? "" : " · " + urgencyName(r.urgency)) + (r.note.isEmpty ? "" : ": “\(r.note)”")).font(.bMedium).foregroundColor(C.text).padding(.top, 4)
            if !r.problems.isEmpty { Text(r.problems.joined(separator: ", ")).font(.bSmall).foregroundColor(C.muted) }
            Btn(t("Mark fixed", "Weka imerekebishwa"), icon: "wrench", enabled: !busy, ok: true, small: true) {
                busy = true
                Task {
                    switch await m.submit(["action": "fixed", "id": p.id, "note": ""], []) {
                    case .sent: m.localFixed(p); msg = nil
                    case .queued: m.localFixed(p); msg = t("Saved on this phone. It will be sent when you have signal.", "Imehifadhiwa kwenye simu. Itatumwa ukipata mtandao.")
                    case .failed(let s): msg = s
                    case .locked: break
                    }
                    busy = false
                }
            }.padding(.top, 10)
            Msg(msg, "warn")
        }
    }
}

struct HistoryTab: View {
    @EnvironmentObject var m: AppModel
    let p: Project
    @State private var notes: [Note]?
    @State private var err: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(t("Notes on this project in monday.com, newest first.", "Maelezo ya mradi huu kwenye monday.com, ya karibuni kwanza.")).font(.bMedium).foregroundColor(C.muted)
            Msg(err, "bad")
            if notes == nil && err == nil { Text(t("Loading…", "Inapakia…")).foregroundColor(C.muted).padding(.top, 12) }
            if let n = notes { PhotoTimeline(p: p, notes: n) }
            if let n = notes, n.isEmpty { Text(t("No notes yet.", "Bado hakuna maelezo.")).foregroundColor(C.muted).padding(.top, 12) }
            ForEach(notes ?? []) { note in
                CardV(pad: 14) {
                    Text(When.text(note.at) + " · " + note.by).font(.lMedium).foregroundColor(C.ink)
                    Text(note.text).font(.bMedium).foregroundColor(C.text).padding(.top, 4)
                    ForEach(Array(note.files.filter(\.isImage).prefix(4).enumerated()), id: \.offset) { _, f in
                        NetImage(url: f.url) { C.tint }.frame(maxWidth: .infinity).aspectRatio(4 / 3, contentMode: .fit).clipped()
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).padding(.top, 8)
                    }
                }.padding(.top, 10)
            }
        }
        .task(id: p.id) { let (n, e) = await m.history(p.id); notes = n; err = e }
    }
}

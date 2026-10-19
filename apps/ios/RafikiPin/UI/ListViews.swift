import SwiftUI
import UIKit

func ageDays(_ s: Snapshot, _ p: Project) -> Double {
    guard let v = s.visits[p.id], let d = When.parse(v.at) else { return 1e6 }
    return Date().timeIntervalSince(d) / 86400
}

func dotColor(_ s: Snapshot, _ p: Project) -> UIColor {
    switch s.repairs[p.id]?.state {
    case "notworking": return UIColor(red: 0xB3 / 255, green: 0x26 / 255, blue: 0x1E / 255, alpha: 1)
    case nil: return UIColor(red: 0x1C / 255, green: 0x7C / 255, blue: 0x4A / 255, alpha: 1)
    default: return UIColor(red: 0xD9 / 255, green: 0x82 / 255, blue: 0, alpha: 1)
    }
}

func nearHere(_ s: Snapshot, _ here: LatLng?) -> (Project, Double)? {
    guard let here else { return nil }
    let best = s.projects.compactMap { p in p.gps.map { (p, Geo.metres(here, $0)) } }.min { $0.1 < $1.1 }
    if let b = best, b.1 < 300 { return b }
    return nil
}

struct MapTab: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var loc: Locator
    @State private var sat = false
    @State private var goMe = 0
    init() { _loc = ObservedObject(wrappedValue: Locator.shared) }

    var body: some View {
        if let snap = m.snap {
            let pinned = snap.projects.filter { $0.gps != nil }.count
            ZStack {
                ProjectsMap(dots: snap.projects.compactMap { p in p.gps.map { Dot(id: p.id, ll: $0, color: dotColor(snap, p), big: snap.repairs[p.id] != nil, title: p.title) } },
                            satellite: sat, onDot: { m.go(Place(tab: "map", project: $0)) }, goMe: $goMe)
                    .ignoresSafeArea(edges: [])
                VStack(spacing: 0) {
                    HStack(alignment: .top, spacing: 10) {
                        CardV(pad: 12) {
                            HStack {
                                Text(t("\(pinned) of \(snap.projects.count) projects pinned", "Miradi \(pinned) kati ya \(snap.projects.count) ina mahali")).font(.tSmall).foregroundColor(C.ink)
                                Spacer(minLength: 4)
                                Text(t("\(snap.projects.count - pinned) to go", "\(snap.projects.count - pinned) zimebaki")).font(.bSmall).foregroundColor(C.muted)
                            }
                            Meter(f: Double(pinned) / Double(max(1, snap.projects.count))).padding(.top, 8)
                            HStack(spacing: 10) {
                                Legend(c: Color(hex: 0x1C7C4A), text: t("Pinned", "Ina mahali")); Legend(c: Color(hex: 0xD98200), text: t("Needs repair", "Inahitaji matengenezo")); Legend(c: Color(hex: 0xB3261E), text: t("Not working", "Haifanyi kazi"))
                            }.padding(.top, 8)
                        }
                        VStack(spacing: 10) {
                            MapButton(icon: sat ? "map" : "satellite", label: sat ? t("Show the street map", "Onyesha ramani ya barabara") : t("Show satellite photos", "Onyesha picha za satelaiti")) { sat.toggle() }
                            if loc.fix != nil { MapButton(icon: "target", label: t("Go to where I am", "Nenda nilipo")) { goMe += 1 } }
                        }
                    }.padding(12)
                    Spacer()
                    if case let (p, d)? = nearHere(snap, loc.fix?.ll) {
                        HereBanner(p: p, d: d, miles: m.miles) { m.go(Place(tab: "map", project: p.id)) }.padding(12)
                    }
                }
            }
        }
    }
}

private struct Legend: View {
    let c: Color; let text: String
    var body: some View { HStack(spacing: 5) { RoundedRectangle(cornerRadius: 5).fill(c).frame(width: 10, height: 10); Text(text).font(.lSmall).foregroundColor(C.muted).lineLimit(1).minimumScaleFactor(0.7) } }
}

struct HereBanner: View {
    let p: Project; let d: Double; let miles: Bool; let open: () -> Void
    var body: some View {
        Button(action: open) {
            HStack(spacing: 10) {
                Icon("pin", size: 22, color: .white)
                VStack(alignment: .leading, spacing: 2) {
                    Text(t("You’re at \(p.title)", "Uko \(p.title)")).font(.tSmall).foregroundColor(.white).lineLimit(2)
                    Text(t(Geo.dist(d, miles: miles) + " away · tap to report a visit", "Umbali " + Geo.dist(d, miles: miles) + " · gusa kuripoti ziara")).font(.bSmall).foregroundColor(Color(hex: 0xD7F5E3))
                }
                Spacer(minLength: 4)
                Icon("chev", size: 20, color: .white)
            }.padding(14).background(OkBrush).clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        }.buttonStyle(Press())
    }
}

private let FILTERS = [("missing", "Needs a location", "Hakuna mahali", "pinq"), ("near", "Nearby", "Karibu nawe", "compass"), ("repairs", "Repairs", "Matengenezo", "wrench"),
                       ("due", "Due a visit", "Inasubiri ziara", "cal"), ("all", "All", "Yote", "list")]

struct ProjectsView: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var loc = Locator.shared
    @State private var q = ""
    @State private var f = ""
    @State private var ck = ""
    @State private var ready = false

    var body: some View {
        if let snap = m.snap { content(snap).onAppear { if !ready { f = m.prefs.filter; ck = m.prefs.country; ready = true } } }
    }

    func content(_ snap: Snapshot) -> some View {
        let here = loc.fix?.ll
        let urg = ["urgent": 0, "month": 1, "watch": 2]
        var rows = snap.projects.filter { p in
            let byFilter: Bool
            switch f { case "missing": byFilter = p.gps == nil; case "near": byFilter = p.gps != nil; case "repairs": byFilter = snap.repairs[p.id] != nil
            case "due": byFilter = p.gps != nil && ageDays(snap, p) >= DUE_DAYS; default: byFilter = true }
            let qq = q.trimmingCharacters(in: .whitespaces)
            return byFilter && (ck.isEmpty || p.ck == ck) && (qq.isEmpty || "\(p.title) \(p.area) \(p.country)".localizedCaseInsensitiveContains(qq))
        }
        if f == "near", let h = here { rows = Array(rows.sorted { Geo.metres(h, $0.gps!) < Geo.metres(h, $1.gps!) }.prefix(30)) }
        if f == "repairs" { rows.sort { (urg[snap.repairs[$0.id]?.urgency ?? ""] ?? 3) < (urg[snap.repairs[$1.id]?.urgency ?? ""] ?? 3) } }
        if f == "due" { rows.sort { ageDays(snap, $0) > ageDays(snap, $1) } }
        let count: String = {
            switch f {
            case "near": return here != nil ? t("Closest pinned projects to you", "Miradi iliyo karibu nawe zaidi") : t("Finding where you are…", "Inatafuta mahali ulipo…")
            case "missing": return t("\(rows.count) still need a location", "\(rows.count) bado hazina mahali")
            case "repairs": return t("\(rows.count) reported as needing repair or not working, most urgent first", "\(rows.count) zimeripotiwa kuhitaji matengenezo au kutofanya kazi, za haraka kwanza")
            case "due": return t("\(rows.count) not visited in the last 6 months, longest first", "\(rows.count) hazijatembelewa miezi 6 iliyopita, za muda mrefu kwanza")
            default: return t("\(rows.count) projects", "Miradi \(rows.count)")
            }
        }()
        let mine: [(Project, String, Bool)] = snap.assigned.compactMap { a in snap.projects.first { $0.id == a.id }.map { ($0, t("Visit by \(a.date)", "Tembelea kabla ya \(a.date)") + (a.note.isEmpty ? "" : " · \(a.note)"), false) } }
            + snap.jobs.compactMap { id in snap.projects.first { $0.id == id }.map { ($0, t("Repair job", "Kazi ya matengenezo"), true) } }
        let pinned = snap.projects.filter { $0.gps != nil }.count

        return ScrollView {
            LazyVStack(alignment: .leading, spacing: 10) {
                if case let (p, d)? = nearHere(snap, here) { HereBanner(p: p, d: d, miles: m.miles) { m.go(Place(tab: "list", project: p.id)) } }
                if let b = m.banner { CardV(border: C.warnBg) { Text(b).font(.bMedium).foregroundColor(C.warn) } }
                if m.waiting > 0 {
                    CardV(fill: C.warnBg) {
                        HStack {
                            Text(m.waiting == 1 ? t("1 change waiting to send", "Badiliko 1 linasubiri kutumwa") : t("\(m.waiting) changes waiting to send", "Mabadiliko \(m.waiting) yanasubiri kutumwa")).font(.tSmall).foregroundColor(C.warn)
                            Spacer(); Btn(t("Send now", "Tuma sasa"), light: true, small: true) { m.refresh() }
                        }
                    }
                }
                if !mine.isEmpty {
                    CardV(border: Color(hex: 0x9FD3F7)) {
                        Text(t("Your work", "Kazi yako")).font(.tLarge).foregroundColor(C.ink)
                        ForEach(mine, id: \.0.id) { p, why, job in
                            Button { m.go(Place(tab: "list", project: p.id)) } label: {
                                HStack(spacing: 12) {
                                    IconTile(icon: job ? "wrench" : "cal", tint: C.blue, bg: C.tint)
                                    VStack(alignment: .leading) { Text(p.title).font(.tSmall).foregroundColor(C.ink); Text(why).font(.bSmall).foregroundColor(C.muted) }
                                    Spacer()
                                }.padding(6).contentShape(Rectangle())
                            }.buttonStyle(.plain).padding(.top, 8)
                        }
                    }
                }
                HStack {
                    Text(t("Projects", "Miradi")).font(.hLarge).foregroundColor(C.ink)
                    Spacer()
                    Btn(t("Plan a route", "Panga njia"), icon: "map", light: true, small: true) { m.go(Place(tab: "list", sub: "route")) }
                }.padding(.top, 4)
                CardV(deep: true) {
                    HStack {
                        Text(t("\(pinned) of \(snap.projects.count) projects pinned", "Miradi \(pinned) kati ya \(snap.projects.count) ina mahali")).font(.tSmall).foregroundColor(.white)
                        Spacer(); Text(t("\(snap.projects.count - pinned) to go", "\(snap.projects.count - pinned) zimebaki")).font(.bSmall).foregroundColor(LightBlue)
                    }
                    Meter(f: Double(pinned) / Double(max(1, snap.projects.count)), light: true).padding(.top, 10)
                    HStack(spacing: 12) {
                        ForEach(COUNTRIES, id: \.0) { k, n in
                            let l = snap.projects.filter { $0.ck == k }
                            if !l.isEmpty {
                                let pn = l.filter { $0.gps != nil }.count
                                VStack(alignment: .leading, spacing: 4) {
                                    Text("\(n) \(pn)/\(l.count)").font(.lSmall).foregroundColor(LightBlue).lineLimit(1).minimumScaleFactor(0.7)
                                    Meter(f: Double(pn) / Double(l.count), light: true)
                                }
                            }
                        }
                    }.padding(.top, 12)
                }
                HStack(spacing: 10) {
                    Icon("search", size: 22, color: C.muted)
                    TextField("", text: $q, prompt: Text(t("Search by project name or place", "Tafuta kwa jina la mradi au mahali")).foregroundColor(C.muted)).font(.bLarge).foregroundColor(C.text)
                }
                .padding(.horizontal, 14).frame(minHeight: 52).background(C.card)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.edge, lineWidth: 1))
                HStack(spacing: 6) {
                    ForEach(FILTERS, id: \.0) { k, en, sw, icon in
                        Tile(label: t(en, sw), icon: icon, selected: f == k, badge: k == "repairs" ? snap.repairs.count : 0) { f = k; m.prefs.filter = k }
                    }
                }
                Picker1(label: "", value: ck, options: [("", t("All countries", "Nchi zote"))] + COUNTRIES) { ck = $0; m.prefs.country = $0 }
                Text((countryName(ck).map { "\($0) · " } ?? "") + count).font(.mont(12, .semibold)).foregroundColor(C.muted)
                ForEach(rows) { p in ProjectRow(snap: snap, p: p, here: here) { m.go(Place(tab: "list", project: p.id)) } }
            }.padding(16)
        }
        .refreshable { await m.refreshNow() }
    }
}

struct ProjectRow: View {
    @EnvironmentObject var m: AppModel
    let snap: Snapshot; let p: Project; let here: LatLng?; let open: () -> Void
    var body: some View {
        let rep = snap.repairs[p.id]
        let edge: Color = rep?.state == "notworking" ? C.bad : rep == nil ? (p.gps != nil ? C.ok : Amber) : Amber
        Button(action: open) {
            HStack(spacing: 0) {
                Rectangle().fill(edge).frame(width: 4)
                NetImage(url: p.photo.map { m.api.photoUrl($0) }) { Icon("drop", size: 24, color: C.blue) }
                    .frame(width: 54, height: 54).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).padding(.leading, 10)
                VStack(alignment: .leading, spacing: 2) {
                    Text(p.title).font(.mont(15, .bold)).foregroundColor(C.text).lineLimit(2).multilineTextAlignment(.leading)
                    Text(p.sub).font(.bSmall).foregroundColor(C.muted).lineLimit(2).multilineTextAlignment(.leading)
                }.padding(.vertical, 10).padding(.leading, 12)
                Spacer(minLength: 8)
                Group {
                    if let rep { Tag(rep.state == "notworking" ? t("Not working", "Haifanyi kazi") : t("Needs repair", "Inahitaji matengenezo"), bg: rep.state == "notworking" ? C.badBg : C.warnBg, fg: rep.state == "notworking" ? C.bad : C.warn) }
                    else if p.gps == nil { Tag(t("No location", "Hakuna mahali"), bg: C.warnBg, fg: C.warn) }
                    else if let h = here { Tag(Geo.dist(Geo.metres(h, p.gps!), miles: m.miles), bg: C.tint, fg: C.deep) }
                    else { Tag(t("Pinned", "Ina mahali"), bg: C.okBg, fg: C.ok) }
                }.padding(.trailing, 12)
            }
            .frame(minHeight: 76).background(C.card)
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(C.line, lineWidth: 1))
        }.buttonStyle(Press())
    }
}

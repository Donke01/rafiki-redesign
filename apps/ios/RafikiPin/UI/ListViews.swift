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

private let FILTERS = [("all", "All", "Yote", "list"), ("missing", "Needs a location", "Hakuna mahali", "pinq"), ("near", "Nearby", "Karibu nawe", "compass"),
                       ("repairs", "Repairs", "Ukarabati", "wrench"), ("due", "Due a visit", "Inasubiri ziara", "cal")]

struct ProjectsView: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var loc = Locator.shared
    @State private var f = ""
    @State private var ck = ""
    @State private var ready = false
    @State private var countrySheet = false

    var body: some View {
        if let snap = m.snap { content(snap).onAppear { if !ready { f = m.prefs.filter; ck = m.prefs.country; ready = true } } }
    }

    func rowsFor(_ snap: Snapshot, _ here: LatLng?) -> [Project] {
        let urg = ["urgent": 0, "month": 1, "watch": 2]
        var rows = snap.projects.filter { p in
            let byFilter: Bool
            switch f { case "missing": byFilter = p.gps == nil; case "near": byFilter = p.gps != nil; case "repairs": byFilter = snap.repairs[p.id] != nil
            case "due": byFilter = p.gps != nil && ageDays(snap, p) >= DUE_DAYS; default: byFilter = true }
            return byFilter && (ck.isEmpty || p.ck == ck)
        }
        if f == "near", let h = here { rows = Array(rows.sorted { Geo.metres(h, $0.gps!) < Geo.metres(h, $1.gps!) }.prefix(30)) }
        if f == "repairs" { rows.sort { (urg[snap.repairs[$0.id]?.urgency ?? ""] ?? 3) < (urg[snap.repairs[$1.id]?.urgency ?? ""] ?? 3) } }
        if f == "due" { rows.sort { ageDays(snap, $0) > ageDays(snap, $1) } }
        return rows
    }

    func pick(_ k: String) { Haptic.tap(); withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) { f = k }; m.prefs.filter = k }

    func content(_ snap: Snapshot) -> some View {
        let here = loc.fix?.ll
        let rows = rowsFor(snap, here)
        let count: String = {
            switch f {
            case "near": return here != nil ? t("Closest pinned projects to you", "Miradi iliyo karibu nawe zaidi") : t("Finding where you are…", "Inatafuta mahali ulipo…")
            case "missing": return t("\(rows.count) still need a location", "\(rows.count) bado hazina mahali")
            case "repairs": return t("\(rows.count) need repair or aren’t working, most urgent first", "\(rows.count) zinahitaji matengenezo au hazifanyi kazi, za haraka kwanza")
            case "due": return t("\(rows.count) not visited in 6 months, longest first", "\(rows.count) hazijatembelewa miezi 6, za muda mrefu kwanza")
            default: return t("\(rows.count) projects", "Miradi \(rows.count)")
            }
        }()
        let pinned = snap.projects.filter { $0.gps != nil }.count

        return ScrollView {
            LazyVStack(alignment: .leading, spacing: 10, pinnedViews: [.sectionHeaders]) {
                HomeTop(snap: snap, filter: pick).padding(.bottom, 2)
                if case let (p, d)? = nearHere(snap, here) { HereBanner(p: p, d: d, miles: m.miles) { m.go(Place(tab: "list", project: p.id)) }.rise(0) }
                if let b = m.banner { CardV(border: C.warnBg) { Text(b).font(.bMedium).foregroundColor(C.warn) } }
                if m.waiting > 0 {
                    CardV(fill: C.warnBg) {
                        HStack {
                            Text(m.waiting == 1 ? t("1 change waiting to send", "Badiliko 1 linasubiri kutumwa") : t("\(m.waiting) changes waiting to send", "Mabadiliko \(m.waiting) yanasubiri kutumwa")).font(.tSmall).foregroundColor(C.warn)
                            Spacer(); Btn(t("Send now", "Tuma sasa"), light: true, small: true) { m.refresh() }
                        }
                    }
                }
                HStack {
                    Text(t("Projects", "Miradi")).font(.hMedium).foregroundColor(C.ink)
                    Spacer()
                    Button { m.go(Place(tab: "list", sub: "route")) } label: {
                        HStack(spacing: 6) { Icon("map", size: 18, color: C.blue); Text(t("Plan a route", "Panga njia")).font(.lMedium).foregroundColor(C.blue) }.frame(minHeight: 44)
                    }.buttonStyle(.plain)
                }.padding(.top, 4)
                CardV(pad: 14, deep: true) {
                    HStack(spacing: 0) {
                        CountUp(value: pinned, font: .tSmall, color: .white)
                        Text(t(" of \(snap.projects.count) projects pinned", " kati ya \(snap.projects.count) ina mahali")).font(.tSmall).foregroundColor(.white)
                        Spacer(); Text(t("\(snap.projects.count - pinned) to go", "\(snap.projects.count - pinned) zimebaki")).font(.bSmall).foregroundColor(LightBlue)
                    }
                    FillMeter(f: Double(pinned) / Double(max(1, snap.projects.count)), light: true).padding(.top, 10)
                    let present = COUNTRIES.filter { c in snap.projects.contains { $0.ck == c.0 } }
                    if present.count > 1 {
                        HStack(spacing: 12) {
                            ForEach(present, id: \.0) { k, n in
                                let l = snap.projects.filter { $0.ck == k }, pn = l.filter { $0.gps != nil }.count
                                VStack(alignment: .leading, spacing: 4) {
                                    Text("\(n) \(pn)/\(l.count)").font(.lSmall).foregroundColor(LightBlue).lineLimit(1).minimumScaleFactor(0.7)
                                    FillMeter(f: Double(pn) / Double(l.count), light: true)
                                }
                            }
                        }.padding(.top, 10)
                    }
                }
                Section(header: filterBar(snap)) {
                    HStack(spacing: 10) {
                        Button { countrySheet = true } label: {
                            HStack(spacing: 6) {
                                Icon("map", size: 16, color: C.hue("country").fg)
                                Text(countryName(ck) ?? t("All countries", "Nchi zote")).font(.lMedium).foregroundColor(C.ink)
                                Icon("down", size: 14, color: C.muted)
                            }.padding(.horizontal, 12).frame(minHeight: 36).background(C.card).clipShape(Capsule()).overlay(Capsule().stroke(C.line, lineWidth: 1))
                        }.buttonStyle(.plain).accessibilityIdentifier("chip_country")
                        Text(count).font(.mont(12, .semibold)).foregroundColor(C.muted).lineLimit(2)
                    }
                    ForEach(Array(rows.enumerated()), id: \.element.id) { i, p in
                        ProjectRow(snap: snap, p: p, here: here) { m.go(Place(tab: "list", project: p.id)) }.rise(i, step: 0.03)
                    }
                    if rows.isEmpty {
                        Text(f == "missing" && ck.isEmpty ? t("Every project has a location.", "Kila mradi una mahali.") : f == "repairs" && ck.isEmpty ? t("No open repairs.", "Hakuna matengenezo yaliyo wazi.")
                             : f == "due" ? t("Everything has been visited in the last 6 months.", "Kila kitu kimetembelewa katika miezi 6 iliyopita.") : t("No projects match.", "Hakuna mradi unaolingana."))
                            .font(.bMedium).foregroundColor(C.muted).frame(maxWidth: .infinity).padding(.vertical, 30)
                    }
                }
            }.padding(.horizontal, 16).padding(.top, 12).padding(.bottom, 24)
        }
        .refreshable { await m.refreshNow() }
        .sheet(isPresented: $countrySheet) {
            PickSheet(title: t("Country", "Nchi"), options: [("", t("All countries", "Nchi zote"), "map")] + COUNTRIES.map { ($0.0, $0.1, "pin") }, selected: ck) { ck = $0; m.prefs.country = $0 }
        }
    }

    /// All five filters across the width; it stays at the top while the list scrolls.
    func filterBar(_ snap: Snapshot) -> some View {
        HStack(spacing: 6) {
            ForEach(FILTERS, id: \.0) { k, en, sw, icon in
                FilterTile(label: t(en, sw), icon: icon, on: f == k, hue: C.hue(k), badge: k == "repairs" ? snap.repairs.count : 0) { pick(k) }
                    .accessibilityIdentifier("chip_\(k)")
            }
        }
        .padding(.vertical, 8)
        .background(LinearGradient(colors: [C.bg.opacity(0.96), C.bg.opacity(0.9), C.bg.opacity(0)], startPoint: .top, endPoint: .bottom).padding(.horizontal, -16).padding(.bottom, -8))
    }
}

/// A filter: a coloured icon over a short label.
struct FilterTile: View {
    let label: String; let icon: String; let on: Bool; let hue: Hue; var badge = 0; let action: () -> Void
    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Icon(icon, size: 20, color: on ? .white : hue.fg)
                Text(label).font(.mont(10, .bold)).foregroundColor(on ? .white : C.ink).multilineTextAlignment(.center).lineLimit(2).minimumScaleFactor(0.75)
            }
            .padding(.horizontal, 2).padding(.vertical, 8)
            .frame(maxWidth: .infinity, minHeight: 62)
            .background(on ? hue.fg : C.card)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(on ? .clear : C.line, lineWidth: 1))
            .overlay(alignment: .topTrailing) {
                if badge > 0 { Text("\(badge)").font(.mont(10, .bold)).foregroundColor(.white).padding(.horizontal, 5).padding(.vertical, 1).background(RedC).clipShape(Capsule()).offset(x: 2, y: -4) }
            }
        }.buttonStyle(Press())
    }
}

/// A bar that fills up when it first shows.
struct FillMeter: View {
    let f: Double; var light = false
    @State private var shown = 0.0
    var body: some View {
        Meter(f: shown, light: light)
            .onAppear { if Motion.on { withAnimation(.easeOut(duration: 1.0).delay(0.15)) { shown = f } } else { shown = f } }
            .onChange(of: f) { v in withAnimation(.easeOut(duration: 0.5)) { shown = v } }
    }
}

/// Home: a greeting, a big question, the search box and four quick actions; it fits on one screen.
struct HomeTop: View {
    @EnvironmentObject var m: AppModel
    let snap: Snapshot; let filter: (String) -> Void
    var body: some View {
        let first = m.prefs.name.split(separator: " ").first.map(String.init) ?? ""
        VStack(alignment: .leading, spacing: 0) {
            Text("Habari, " + (first.isEmpty ? "karibu" : first)).font(.bMedium).foregroundColor(C.muted)
            (Text(t("Which project\n", "Mradi gani\n")).font(.mont(27, .regular)) + Text(t("today?", "leo?")).font(.mont(27, .heavy)))
                .foregroundColor(C.ink).lineSpacing(0).padding(.top, 2).padding(.bottom, 10)
            Button { m.go(Place(tab: "list", sub: "search")) } label: {
                HStack(spacing: 10) {
                    Icon("search", size: 20, color: C.muted)
                    Text(t("Search a project or place", "Tafuta mradi au mahali")).font(.bLarge).foregroundColor(C.muted).lineLimit(1)
                    Spacer()
                }.padding(.horizontal, 16).frame(minHeight: 48).background(C.card)
                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(C.line, lineWidth: 1))
            }.buttonStyle(.plain).accessibilityIdentifier("home_search").padding(.bottom, 10)
            HStack(spacing: 8) {
                ForEach(Array([("missing", t("Pin a location", "Weka mahali"), "pin"), ("near", t("Visit nearby", "Tembelea karibu"), "clip"),
                               ("new", t("New site", "Eneo jipya"), "plus"), ("repairs", t("Repairs", "Rekebisha"), "wrench"), ("scan", t("Scan QR", "Soma QR"), "scan")].enumerated()), id: \.element.0) { i, q in
                    let (k, label, icon) = q, hu = C.hue(k)
                    Button { Haptic.tap(); if k == "new" { m.tab("new") } else if k == "scan" { m.go(Place(tab: "list", sub: "scan")) } else { filter(k) } } label: {
                        VStack(spacing: 6) {
                            Icon(icon, size: 20, color: hu.fg).frame(width: 38, height: 38).background(hu.bg).clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
                            Text(label).font(.mont(12, .bold)).foregroundColor(C.ink).multilineTextAlignment(.center).lineLimit(2).minimumScaleFactor(0.8)
                        }.padding(.horizontal, 4).padding(.vertical, 10).frame(maxWidth: .infinity, minHeight: 84)
                        .background(C.card.opacity(0.9)).clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(C.line, lineWidth: 1))
                    }.buttonStyle(Press()).accessibilityIdentifier("quick_\(k)").rise(i + 1, step: 0.06)
                }
            }
        }
    }
}

struct ProjectRow: View {
    @EnvironmentObject var m: AppModel
    let snap: Snapshot; let p: Project; let here: LatLng?; let open: () -> Void
    var body: some View {
        let rep = snap.repairs[p.id]
        // the icon box carries the state colour; the tag sits under the name so names aren't cut short
        let box: (Color, Color) = rep?.state == "notworking" ? (C.badBg, C.bad) : rep != nil ? (C.warnBg, C.warn) : p.gps == nil ? (C.tint, C.blue) : (C.okBg, C.ok)
        Button(action: open) {
            HStack(spacing: 12) {
                NetImage(url: p.photo.map { m.api.photoUrl($0) }) { Icon("drop", size: 22, color: box.1) }
                    .frame(width: 46, height: 46).background(box.0).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(p.title).font(.mont(16, .bold)).foregroundColor(C.text).lineLimit(1)
                    Text(p.sub).font(.bSmall).foregroundColor(C.muted).lineLimit(1)
                    Group {
                        if let rep { Tag(rep.state == "notworking" ? t("Not working", "Haifanyi kazi") : t("Needs repair", "Inahitaji matengenezo"), bg: rep.state == "notworking" ? C.badBg : C.warnBg, fg: rep.state == "notworking" ? C.bad : C.warn) }
                        else if p.gps == nil { Tag(t("No location", "Hakuna mahali"), bg: C.warnBg, fg: C.warn) }
                        else if let h = here { Tag(Geo.dist(Geo.metres(h, p.gps!), miles: m.miles), bg: C.tint, fg: C.deep) }
                        else { Tag(t("Pinned", "Ina mahali"), bg: C.okBg, fg: C.ok) }
                    }.padding(.top, 4)
                }
                Spacer(minLength: 8)
                Icon("chev", size: 18, color: C.muted)
            }
            .padding(.horizontal, 14).padding(.vertical, 12)
            .frame(minHeight: 76).background(C.card)
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(C.line, lineWidth: 1))
        }.buttonStyle(Press())
    }
}

/// The search screen: type a name or a place; closest projects first before you type.
struct SearchView: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var loc = Locator.shared
    @State private var q = ""
    @FocusState private var focused: Bool
    var body: some View {
        let snap = m.snap
        let here = loc.fix?.ll
        let qq = q.trimmingCharacters(in: .whitespaces)
        let all = snap?.projects ?? []
        let rows: [Project] = qq.isEmpty
            ? (here.map { h in Array(all.filter { $0.gps != nil }.sorted { Geo.metres(h, $0.gps!) < Geo.metres(h, $1.gps!) }.prefix(8)) } ?? Array(all.prefix(8)))
            : all.filter { "\($0.title) \($0.area) \($0.country)".localizedCaseInsensitiveContains(qq) }
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Icon("search", size: 22, color: C.muted)
                TextField("", text: $q, prompt: Text(t("Search a project or place", "Tafuta mradi au mahali")).foregroundColor(C.muted)).font(.bLarge).foregroundColor(C.text)
                    .focused($focused).submitLabel(.search).accessibilityIdentifier("search_input")
                if !q.isEmpty { Button { q = "" } label: { Icon("x", size: 18, color: C.muted) }.buttonStyle(.plain) }
            }
            .padding(.horizontal, 14).frame(minHeight: 52).background(C.card)
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(C.blue.opacity(0.5), lineWidth: 1.5))
            .padding(16)
            Text(qq.isEmpty ? (here != nil ? t("Closest to you", "Iliyo karibu nawe") : t("Projects", "Miradi")) : t("\(rows.count) found", "\(rows.count) zimepatikana"))
                .font(.mont(12, .semibold)).foregroundColor(C.muted).frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16)
            ScrollView {
                LazyVStack(spacing: 10) {
                    if let snap { ForEach(rows) { p in ProjectRow(snap: snap, p: p, here: here) { m.go(Place(tab: m.here.tab, project: p.id)) } } }
                }.padding(16)
            }.scrollDismissesKeyboard(.interactively)
        }
        .onAppear { DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { focused = true } }
    }
}

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

private let FILTERS = [("all", "All", "Yote", "list"), ("missing", "Needs a location", "Hakuna mahali", "pinq"), ("near", "Nearby", "Karibu nawe", "compass"),
                       ("repairs", "Repairs", "Matengenezo", "wrench"), ("due", "Due a visit", "Inasubiri ziara", "cal")]

/// The Projects home (Android's ProjectsScreen): the home top, where you are, your work, a head with three numbers,
/// a sticky row of filter chips with counts and a Filter pill (country and type), then the list.
struct ProjectsView: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var loc = Locator.shared
    @State private var f = ""
    @State private var ck = ""
    @State private var ty = ""
    @State private var ready = false
    @State private var filterSheet = false

    var body: some View {
        if let snap = m.snap { content(snap).onAppear { if !ready { f = m.prefs.filter; ck = m.prefs.country; ty = m.prefs.ftype; ready = true } } }
    }

    func inFilter(_ snap: Snapshot, _ p: Project, _ k: String) -> Bool {
        switch k {
        case "missing": return p.gps == nil
        case "near": return p.gps != nil
        case "repairs": return snap.repairs[p.id] != nil
        case "due": return p.gps != nil && (ageDays(snap, p) >= DUE_DAYS || testDue(snap, p))
        default: return true
        }
    }

    func pick(_ k: String, _ proxy: ScrollViewProxy? = nil) {
        Haptic.tap(); withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) { f = k }; m.prefs.filter = k
        if let proxy { withAnimation(.easeOut(duration: 0.3)) { proxy.scrollTo("head", anchor: .top) } }
    }

    func content(_ snap: Snapshot) -> some View {
        let here = loc.fix?.ll
        let tcount = Dictionary(grouping: snap.projects.filter { ck.isEmpty || $0.ck == ck }, by: tgroup).mapValues(\.count)
        let tyOn = !ty.isEmpty && (tcount[ty] ?? 0) > 0 ? ty : ""
        let scoped = snap.projects.filter { p in (ck.isEmpty || p.ck == ck) && (tyOn.isEmpty || tgroup(p) == tyOn) }
        var rows = scoped.filter { inFilter(snap, $0, f) }
        let urg = ["urgent": 0, "month": 1, "watch": 2]
        if f == "near", let h = here { rows = Array(rows.sorted { Geo.metres(h, $0.gps!) < Geo.metres(h, $1.gps!) }.prefix(30)) }
        if f == "repairs" { rows.sort { (urg[snap.repairs[$0.id]?.urgency ?? ""] ?? 3) < (urg[snap.repairs[$1.id]?.urgency ?? ""] ?? 3) } }
        if f == "due" { rows.sort { ageDays(snap, $0) > ageDays(snap, $1) } }
        let count: String = {
            switch f {
            case "near": return here != nil ? t("Closest pinned projects to you", "Miradi iliyo karibu nawe zaidi") : t("Finding where you are…", "Inatafuta mahali ulipo…")
            case "missing": return t("\(rows.count) still need a location", "\(rows.count) bado hazina mahali")
            case "repairs": return t("\(rows.count) reported as needing repair or not working, most urgent first", "\(rows.count) zimeripotiwa kuhitaji matengenezo au kutofanya kazi, za haraka kwanza")
            case "due":
                let n = testCount(snap, rows)
                return n > 0 ? t("\(rows.count) due a visit · \(n) \(n == 1 ? "needs" : "need") a water test", "\(rows.count) zinasubiri ziara · \(n) zinahitaji kipimo cha maji")
                    : t("\(rows.count) not visited in the last 6 months, longest first", "\(rows.count) hazijatembelewa miezi 6 iliyopita, za muda mrefu kwanza")
            default: return t("\(rows.count) projects", "Miradi \(rows.count)")
            }
        }()
        let mine: [(Project, String, Bool)] = snap.assigned.compactMap { a in snap.projects.first { $0.id == a.id }.map { ($0, t("Visit by \(a.date)", "Tembelea kabla ya \(a.date)") + (a.note.isEmpty ? "" : " · \(a.note)"), false) } }
            + snap.jobs.compactMap { id in snap.projects.first { $0.id == id }.map { ($0, t("Repair job", "Kazi ya matengenezo"), true) } }
        let pn = snap.projects.filter { $0.gps != nil }.count, all = snap.projects.count
        let n = { (k: String) in scoped.filter { inFilter(snap, $0, k) }.count }
        let allPinned = rows.allSatisfy { $0.gps != nil }
        let chosen = [countryName(ck), TGROUPS.first { $0.0 == tyOn }.map { t($0.1, $0.2) }].compactMap { $0 }

        return ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 10, pinnedViews: [.sectionHeaders]) {
                    HomeTop(snap: snap, filter: { pick($0, proxy) }).padding(.bottom, 2)
                    HereSlot(snap: snap, here: here, visit: { m.go(Place(tab: "list", project: $0, projectTab: "visit")) }, open: { m.go(Place(tab: "list", project: $0)) })
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
                            Text(t("Your work", "Kazi yako")).font(.tLarge).foregroundColor(C.ink).frame(maxWidth: .infinity)
                            ForEach(Array(mine.enumerated()), id: \.offset) { _, x in
                                Button { m.go(Place(tab: "list", project: x.0.id)) } label: {
                                    HStack(spacing: 12) {
                                        IconTile(icon: x.2 ? "wrench" : "cal", tint: C.blue, bg: C.tint)
                                        VStack(alignment: .leading, spacing: 1) { Text(x.0.title).font(.tSmall).foregroundColor(C.ink); Text(x.1).font(.bSmall).foregroundColor(C.muted) }
                                        Spacer(minLength: 0)
                                    }.padding(6).contentShape(Rectangle())
                                }.buttonStyle(Press()).padding(.top, 8)
                            }
                        }
                    }
                    DqmHead(icon: "list", hue: "blue", title: t("Projects", "Miradi"),
                            line: t("\(pn) of \(all) projects pinned · \(all - pn) to go", "Miradi \(pn) kati ya \(all) ina mahali · \(all - pn) imebaki"),
                            nums: [
                                { let x = n("missing"); return DqmNum(n: x, label: x == 1 ? t("needs a location", "haina mahali") : t("need a location", "hazina mahali"), tone: x > 0 ? "has" : "off", key: "missing") }(),
                                { let x = n("repairs"); return DqmNum(n: x, label: x == 1 ? t("open repair", "ina hitilafu") : t("open repairs", "zina hitilafu"), tone: x > 0 ? "bad" : "off", key: "repairs") }(),
                                { let x = n("due"); return DqmNum(n: x, label: t("due a visit", "bila ziara"), tone: x > 0 ? "" : "off", key: "due") }(),
                            ], picked: f, onNum: { pick($0, proxy) }) {
                        Button { m.go(Place(tab: "list", sub: "route")) } label: {
                            HStack(spacing: 6) { Icon("map", size: 18, color: C.blue); Text(t("Plan a route", "Panga njia")).font(.lLarge).foregroundColor(C.blue) }
                                .frame(minHeight: 44).padding(.horizontal, 4).contentShape(Rectangle())
                        }.buttonStyle(.plain).padding(.top, 10).accessibilityIdentifier("plan_route")
                    }.id("head")
                    Section(header: chipRow(snap, scoped, chosen)) {
                        Text(count).font(.mont(12, .semibold)).foregroundColor(C.muted).padding(.leading, 4).accessibilityIdentifier("proj_count")
                        ForEach(Array(rows.enumerated()), id: \.element.id) { i, p in
                            ProjectRow(snap: snap, p: p, here: here, showPinned: !allPinned) { m.go(Place(tab: "list", project: p.id)) }.rise(i, step: 0.03)
                        }
                        if rows.isEmpty {
                            EmptyDrop(text: f == "missing" && ck.isEmpty ? t("Every project has a location.", "Kila mradi una mahali.") : f == "repairs" && ck.isEmpty ? t("No open repairs.", "Hakuna matengenezo yaliyo wazi.")
                                 : f == "due" ? t("Everything has been visited in the last 6 months.", "Kila kitu kimetembelewa katika miezi 6 iliyopita.") : t("No projects match.", "Hakuna mradi unaolingana."))
                        }
                    }
                }.padding(.horizontal, 16).padding(.top, 12).padding(.bottom, 24)
            }
            .refreshable { await m.refreshNow() }
        }
        .sheet(isPresented: $filterSheet) {
            ProjFilterSheet(snap: snap, ck: ck, ty: tyOn, shown: rows.count, onCountry: { ck = $0; m.prefs.country = $0 }, onType: { ty = $0; m.prefs.ftype = $0 })
        }
    }

    /// One row of filters with their counts that stays at the top while the list scrolls; country and type are under Filter.
    func chipRow(_ snap: Snapshot, _ scoped: [Project], _ chosen: [String]) -> some View {
        ScrollViewReader { rp in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ProjFilterPill(chosen: chosen) { Haptic.tap(); filterSheet = true }
                    ForEach(FILTERS, id: \.0) { k, en, sw, icon in
                        ProjChip(label: t(en, sw), icon: icon, n: scoped.filter { inFilter(snap, $0, k) }.count, on: f == k) { pick(k) }
                            .id(k).accessibilityIdentifier("chip_\(k)")
                    }
                }.padding(.trailing, 20)
            }
            .mask(LinearGradient(stops: [.init(color: .black, location: 0), .init(color: .black, location: 0.92), .init(color: .clear, location: 1)], startPoint: .leading, endPoint: .trailing))
            .onChange(of: f) { k in withAnimation(.easeOut(duration: 0.25)) { rp.scrollTo(k, anchor: .center) } }
        }
        .padding(.top, 4).padding(.bottom, 8)
        .background(LinearGradient(colors: [C.bg, C.bg, C.bg.opacity(0)], startPoint: .top, endPoint: .bottom).padding(.horizontal, -16))
    }
}

/// A filter in the Projects row, with its count: blue when chosen. Never wraps.
struct ProjChip: View {
    let label: String; let icon: String; let n: Int; let on: Bool; let action: () -> Void
    var body: some View {
        Button { Haptic.tap(); action() } label: {
            HStack(spacing: 6) {
                Icon(icon, size: 17, color: on ? .white : C.blue)
                Text(label).font(.mont(14, .bold)).foregroundColor(on ? .white : C.ink).lineLimit(1).fixedSize()
                Text("\(n)").font(.mont(12, .bold)).foregroundColor(on ? C.blue : C.muted).padding(.horizontal, 7).padding(.vertical, 1)
                    .background(on ? Color.white : C.bg).clipShape(Capsule())
            }.padding(.horizontal, 12).frame(minHeight: 44).background(on ? C.blue : C.card).clipShape(Capsule()).overlay(Capsule().stroke(on ? C.blue : C.line, lineWidth: 1))
        }.buttonStyle(Press())
    }
}

/// Country and type in one pill: "Filter", or what is chosen ("Kenya · Water"), blue while anything is chosen.
struct ProjFilterPill: View {
    let chosen: [String]; let action: () -> Void
    var body: some View {
        let on = !chosen.isEmpty
        Button(action: action) {
            HStack(spacing: 6) {
                Icon("filter", size: 17, color: on ? .white : C.blue)
                Text(on ? chosen.joined(separator: " · ") : t("Filter", "Chuja")).font(.mont(14, .bold)).foregroundColor(on ? .white : C.ink).lineLimit(1).fixedSize()
                Icon("down", size: 15, color: on ? .white : C.muted)
            }.padding(.leading, 12).padding(.trailing, 10).frame(minHeight: 44).background(on ? C.blue : C.card).clipShape(Capsule()).overlay(Capsule().stroke(on ? C.blue : C.line, lineWidth: 1))
        }.buttonStyle(Press()).accessibilityIdentifier("proj_filter")
        .accessibilityLabel(t("Filter by country and type", "Chuja kwa nchi na aina") + (on ? ": " + chosen.joined(separator: ", ") : ""))
    }
}

/// The Filter sheet: country and type of project, each with how many there are; a close X and "Show N projects".
struct ProjFilterSheet: View {
    let snap: Snapshot; let ck: String; let ty: String; let shown: Int; let onCountry: (String) -> Void; let onType: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        let tcount = Dictionary(grouping: snap.projects.filter { ck.isEmpty || $0.ck == ck }, by: tgroup).mapValues(\.count)
        ScrollView {
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(t("Filter", "Chuja")).font(.tLarge).foregroundColor(C.ink)
                    Spacer()
                    Button { dismiss() } label: { Icon("x", size: 20, color: C.muted).frame(width: 44, height: 44) }.buttonStyle(.plain).accessibilityLabel(t("Close", "Funga"))
                }
                GroupLabel(t("Country", "Nchi"))
                opt(t("All countries", "Nchi zote"), snap.projects.count, ck.isEmpty) { onCountry("") }
                ForEach(COUNTRIES, id: \.0) { k, name in
                    let c = snap.projects.filter { $0.ck == k }.count
                    if c > 0 || ck == k { opt(name, c, ck == k) { onCountry(k) } }
                }
                if tcount.count > 1 {
                    GroupLabel(t("Type of project", "Aina ya mradi")).padding(.top, 8)
                    opt(t("All types", "Aina zote"), tcount.values.reduce(0, +), ty.isEmpty) { onType("") }
                    ForEach(TGROUPS.filter { (tcount[$0.0] ?? 0) > 0 }, id: \.0) { k, en, sw in opt(t(en, sw), tcount[k] ?? 0, ty == k) { onType(k) } }
                }
                Btn(shown == 1 ? t("Show 1 project", "Onyesha mradi 1") : t("Show \(shown) projects", "Onyesha miradi \(shown)"), wide: true) { dismiss() }.padding(.top, 14)
            }.padding(20)
        }
        .background(C.bg.ignoresSafeArea())
        .presentationDetents([.medium, .large])
    }
    func opt(_ label: String, _ n: Int, _ on: Bool, _ pick: @escaping () -> Void) -> some View {
        Button { Haptic.tap(); pick() } label: {
            HStack {
                Text(label).font(.tSmall).foregroundColor(C.ink)
                Spacer()
                Text("\(n)").font(.lLarge).foregroundColor(C.muted)
                Group { if on { Icon("check", size: 22, color: C.blue) } }.frame(width: 22).padding(.leading, 10)
            }.padding(.horizontal, 12).frame(minHeight: 48).background(on ? C.tint : Color.clear).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }
}

/// Lays children out in rows, wrapping to the next when one is full.
struct TagFlow: Layout {
    var h: CGFloat = 6; var v: CGFloat = 4
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let w = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, row: CGFloat = 0, maxX: CGFloat = 0
        for s in subviews {
            let z = s.sizeThatFits(.unspecified)
            if x > 0 && x + z.width > w { y += row + v; x = 0; row = 0 }
            x += z.width + h; row = max(row, z.height); maxX = max(maxX, x - h)
        }
        return CGSize(width: min(maxX, w), height: y + row)
    }
    func placeSubviews(in b: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = b.minX, y = b.minY, row: CGFloat = 0
        for s in subviews {
            let z = s.sizeThatFits(.unspecified)
            if x > b.minX && x + z.width > b.maxX { y += row + v; x = b.minX; row = 0 }
            s.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(z)); x += z.width + h; row = max(row, z.height)
        }
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
        VStack(alignment: .center, spacing: 0) {
            Text("Habari, " + (first.isEmpty ? "karibu" : first)).font(.bMedium).foregroundColor(C.muted)
            (Text(t("Which project\n", "Mradi gani\n")).font(.mont(27, .regular)) + Text(t("today?", "leo?")).font(.mont(27, .heavy)))
                .foregroundColor(C.ink).multilineTextAlignment(.center).lineSpacing(0).padding(.top, 2).padding(.bottom, 10)
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
    let snap: Snapshot; let p: Project; let here: LatLng?; var showPinned = true; let open: () -> Void
    var body: some View {
        let rep = snap.repairs[p.id]
        // the icon box carries the state colour; the tags sit under the name so names aren't cut short
        let box: (Color, Color) = rep?.state == "notworking" ? (C.badBg, C.bad) : rep != nil ? (C.warnBg, C.warn) : p.gps == nil ? (C.tint, C.blue) : (C.okBg, C.ok)
        let dist = here.flatMap { h in p.gps.map { Geo.dist(Geo.metres(h, $0), miles: m.miles) } }
        let due = testDue(snap, p)
        let place = [p.area, p.country].filter { !$0.isEmpty }.joined(separator: " · ")
        Button(action: open) {
            HStack(spacing: 12) {
                NetImage(url: p.photo.map { m.api.photoUrl($0) }) { Icon(typeLook(p).0, size: 22, color: box.1) }
                    .frame(width: 46, height: 46).background(box.0).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(p.title).font(.mont(16, .bold)).foregroundColor(C.text).lineLimit(3).multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true).accessibilityIdentifier("row_title")
                    ViewThatFits(in: .horizontal) {
                        ForEach(Array([p.sub, place, p.area.isEmpty ? p.country : p.area].filter { !$0.isEmpty }.enumerated()), id: \.offset) { _, s in
                            Text(s).font(.bSmall).foregroundColor(C.muted).lineLimit(1)
                        }
                        Text(p.area.isEmpty ? p.country : p.area).font(.bSmall).foregroundColor(C.muted).lineLimit(1).truncationMode(.tail)
                    }
                    if dist != nil || rep != nil || p.gps == nil || showPinned || due {
                        TagFlow {
                            if let dist { Tag(dist, bg: C.tint, fg: C.deep) }
                            if let rep { Tag(rep.state == "notworking" ? t("Not working", "Haifanyi kazi") : t("Needs repair", "Inahitaji matengenezo"), bg: rep.state == "notworking" ? C.badBg : C.warnBg, fg: rep.state == "notworking" ? C.bad : C.warn) }
                            if p.gps == nil { Tag(t("No location", "Hakuna mahali"), bg: C.warnBg, fg: C.warn) }
                            else if dist == nil && rep == nil && showPinned { Tag(t("Pinned", "Ina mahali"), bg: C.okBg, fg: C.ok) }
                            if due { WaterTestTag() }
                        }.padding(.top, 5).accessibilityIdentifier("row_tags")
                    }
                }
                Spacer(minLength: 8)
                Icon("chev", size: 20, color: C.muted)
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

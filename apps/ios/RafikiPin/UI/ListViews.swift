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

struct MapTab: View {
    var body: some View { MapHome() }
}

/// "You’re at …" was put away for these projects (until the app is opened again).
enum HereDismissed { static var ids: Set<String> = [] }

/// The "You’re at …" strip: where you are (tap to open it), Report, and ×. It appears within 200 m and stays until
/// you are 260 m away, so GPS wobble near the edge doesn't make it blink.
struct HereSlot: View {
    @EnvironmentObject var m: AppModel
    let s: Snapshot; let here: LatLng?; var slim = false; let open: (String, String) -> Void
    @State private var keep: String?
    @State private var hidden: Set<String> = HereDismissed.ids
    var body: some View {
        let near = here.flatMap { h in s.projects.compactMap { p in p.gps.map { (p, Geo.metres(h, $0)) } }.min { $0.1 < $1.1 } }
        let now = near.flatMap { n in (n.1 <= 200 || (n.0.id == keep && n.1 <= 260)) && !hidden.contains(n.0.id) ? n : nil }
        Group {
            if let n = now {
                HereBanner(p: n.0, d: n.1, miles: m.miles, slim: slim, visit: { open(n.0.id, "visit") }, open: { open(n.0.id, "pin") }) {
                    HereDismissed.ids.insert(n.0.id); withAnimation { hidden.insert(n.0.id) }
                }.transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .animation(Motion.on ? .easeInOut(duration: 0.3) : nil, value: now?.0.id)
        .onChange(of: now?.0.id) { keep = $0 }
    }
}

struct HereBanner: View {
    let p: Project; let d: Double; let miles: Bool; var slim = false; let visit: () -> Void; let open: () -> Void; let dismiss: () -> Void
    var body: some View {
        HStack(spacing: 8) {
            Button(action: open) {
                HStack(spacing: 10) {
                    Icon("pin", size: 20, color: .white)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(t("YOU’RE AT", "UKO") + " · " + Geo.dist(d, miles: miles)).font(.mont(10, .heavy)).tracking(0.8).foregroundColor(.white.opacity(0.8)).lineLimit(1).minimumScaleFactor(0.8)
                        FitWords(p.title, size: 14, weight: .bold, color: .white, lines: 3, min: 0.78)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }.frame(minHeight: 44).contentShape(Rectangle())
            }.buttonStyle(.plain)
            Button(action: visit) {
                Text(t("Report", "Ripoti")).font(.lLarge).foregroundColor(Color(hex: 0x0E4A2B)).lineLimit(1).fixedSize().padding(.horizontal, 14).frame(minHeight: 40).background(Color.white).clipShape(Capsule())
            }.buttonStyle(Press())
            Button(action: dismiss) { Icon("x", size: 16, color: .white).frame(width: 40, height: 40).overlay(Circle().stroke(Color.white.opacity(0.6), lineWidth: 1)) }
                .buttonStyle(.plain).accessibilityLabel(t("Not now", "Si sasa"))
        }
        .padding(.leading, 14).padding(.trailing, 8).padding(.vertical, slim ? 3 : 8)
        .background(OkBrush).clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }
}

func nearHere(_ s: Snapshot, _ here: LatLng?) -> (Project, Double)? {
    guard let here else { return nil }
    let best = s.projects.compactMap { p in p.gps.map { (p, Geo.metres(here, $0)) } }.min { $0.1 < $1.1 }
    if let b = best, b.1 < 200 { return b }
    return nil
}

private let FILTERS = [("all", "All", "Yote", "list"), ("missing", "Needs a location", "Hakuna mahali", "pinq"), ("near", "Nearby", "Karibu nawe", "compass"),
                       ("repairs", "Repairs", "Matengenezo", "wrench"), ("due", "Due a visit", "Inasubiri ziara", "cal"), ("build", "Under construction", "Inajengwa", "build"), ("story", "Stories", "Hadithi", "chat")]

struct ProjectsView: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var loc = Locator.shared
    @State private var f = ""
    @State private var ck = ""
    @State private var ty = ""
    @State private var ready = false
    @State private var filterSheet = false
    @State private var scanning = false

    var body: some View {
        if let snap = m.snap {
            content(snap).onAppear {
                if !ready { f = m.prefs.filter; ck = m.prefs.country; ty = m.od.string(forKey: "ftype") ?? ""; ready = true }
            }
        }
    }

    func storyIs(_ s: Snapshot, _ p: Project, _ k: String) -> Bool {
        let st = s.stories[p.id]?.st ?? "not-started"
        switch k { case "need": return st == "not-started" || st == "collecting"; case "draft": return st == "draft"; default: return st == "approved" || st == "published" }
    }
    func inFilter(_ s: Snapshot, _ p: Project, _ k: String) -> Bool {
        switch k {
        case "missing": return p.gps == nil
        case "near": return p.gps != nil
        case "repairs": return s.repairs[p.id] != nil
        case "due": return p.gps != nil && (ageDays(s, p) >= DUE_DAYS || testDue(m, p))
        case "build": return m.building(p)
        case "story", "story:need": return storyIs(s, p, "need")
        case "story:draft": return storyIs(s, p, "draft")
        case "story:ok": return storyIs(s, p, "ok")
        default: return true
        }
    }

    func pick(_ k: String, _ proxy: ScrollViewProxy? = nil) {
        Haptic.tap(); withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) { f = k }; m.prefs.filter = k
        if let proxy { withAnimation(Motion.on ? .easeInOut(duration: 0.3) : nil) { proxy.scrollTo("chips", anchor: .top) } }
    }

    func rowsFor(_ s: Snapshot, _ scoped: [Project], _ here: LatLng?) -> [Project] {
        let urg = ["urgent": 0, "month": 1, "watch": 2]
        var rows = scoped.filter { inFilter(s, $0, f) }
        if f == "near", let h = here { rows = Array(rows.sorted { Geo.metres(h, $0.gps ?? h) < Geo.metres(h, $1.gps ?? h) }.prefix(30)) }
        if f == "repairs" { rows.sort { (urg[s.repairs[$0.id]?.urgency ?? ""] ?? 3) < (urg[s.repairs[$1.id]?.urgency ?? ""] ?? 3) } }
        if f == "due" { rows.sort { ageDays(s, $0) > ageDays(s, $1) } }
        return rows
    }

    func countText(_ s: Snapshot, _ rows: [Project], _ here: LatLng?) -> String {
        switch f {
        case "near": return here != nil ? t("Closest pinned projects to you", "Miradi iliyo karibu nawe zaidi") : t("Finding where you are…", "Inatafuta mahali ulipo…")
        case "missing": return t("\(rows.count) still need a location", "\(rows.count) bado hazina mahali")
        case "repairs": return t("\(rows.count) reported as needing repair or not working, most urgent first", "\(rows.count) zimeripotiwa kuhitaji matengenezo au kutofanya kazi, za haraka kwanza")
        case "due":
            let n = testCount(m, rows)
            return n > 0 ? t("\(rows.count) due a visit · \(n) \(n == 1 ? "needs" : "need") a water test", "\(rows.count) zinasubiri ziara · \(n) zinahitaji kipimo cha maji")
                : t("\(rows.count) not visited in the last 6 months, longest first", "\(rows.count) hazijatembelewa miezi 6 iliyopita, za muda mrefu kwanza")
        case "build": return t("\(rows.count) being built", "\(rows.count) inajengwa")
        case "story", "story:need": return t("\(rows.count) need a story", "\(rows.count) zinahitaji hadithi")
        case "story:draft": return t("\(rows.count) with a draft ready", "\(rows.count) zina rasimu tayari")
        case "story:ok": return t("\(rows.count) approved", "\(rows.count) zimeidhinishwa")
        default: return t("\(rows.count) projects", "Miradi \(rows.count)")
        }
    }

    func content(_ snap: Snapshot) -> some View {
        let here = loc.fix?.ll
        let scoped = snap.projects.filter { p in (ck.isEmpty || p.ck == ck) && (ty.isEmpty || tgroup(p) == ty) }
        let rows = rowsFor(snap, scoped, here)
        let n: (String) -> Int = { k in scoped.filter { inFilter(snap, $0, k) }.count }
        let pn = snap.projects.filter { $0.gps != nil }.count, all = snap.projects.count
        let allPinned = rows.allSatisfy { $0.gps != nil }
        let mine: [(Project, String, Bool)] = snap.assigned.compactMap { a in snap.projects.first { $0.id == a.id }.map { ($0, t("Visit by ", "Tembelea kabla ya ") + ODates.dayText(a.date, ODates.today()) + (a.note.isEmpty ? "" : " · " + a.note), false) } }
            + snap.jobs.compactMap { id in snap.projects.first { $0.id == id }.map { ($0, t("Repair job", "Kazi ya matengenezo"), true) } }
        return ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 10, pinnedViews: [.sectionHeaders]) {
                    HomeTop(snap: snap, here: here, filter: { pick($0, proxy) }, scan: { scanning = true }).padding(.bottom, 2)
                    HereSlot(s: snap, here: here) { id, tab in m.go(Place(tab: "list", project: id, projectTab: tab)) }
                    if let b = m.banner { CardV(border: C.warnBg) { Text(b).font(.bMedium).foregroundColor(C.warn) } }
                    if m.waiting > 0 {
                        CardV(fill: C.warnBg) {
                            HStack {
                                Text(m.waiting == 1 ? t("1 change waiting to send", "Badiliko 1 linasubiri kutumwa") : t("\(m.waiting) changes waiting to send", "Mabadiliko \(m.waiting) yanasubiri kutumwa")).font(.tSmall).foregroundColor(C.warn).frame(maxWidth: .infinity, alignment: .leading)
                                Btn(t("Send now", "Tuma sasa"), light: true, small: true) { m.refresh() }
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
                                        VStack(alignment: .leading, spacing: 2) { Text(x.0.title).font(.tSmall).foregroundColor(C.ink).multilineTextAlignment(.leading); Text(x.1).font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.leading) }
                                        Spacer(minLength: 0)
                                    }.padding(6).contentShape(Rectangle())
                                }.buttonStyle(.plain).padding(.top, 8)
                            }
                        }
                    }
                    OHead(icon: "list", hue: "blue", title: t("Projects", "Miradi"), line: t("\(pn) of \(all) projects pinned · \(all - pn) to go", "Miradi \(pn) kati ya \(all) ina mahali · \(all - pn) imebaki")) {
                        ONums(items: [ONum(value: "\(n("missing"))", label: n("missing") == 1 ? t("needs a location", "haina mahali") : t("need a location", "hazina mahali"), color: n("missing") > 0 ? C.blue2 : C.muted, on: f == "missing") { pick("missing", proxy) },
                                      ONum(value: "\(n("repairs"))", label: n("repairs") == 1 ? t("open repair", "ina hitilafu") : t("open repairs", "zina hitilafu"), color: n("repairs") > 0 ? C.bad : C.muted, on: f == "repairs") { pick("repairs", proxy) },
                                      ONum(value: "\(n("due"))", label: t("due a visit", "bila ziara"), color: n("due") > 0 ? C.ink : C.muted, on: f == "due") { pick("due", proxy) }])
                        Button { m.go(Place(tab: "list", sub: "route")) } label: {
                            HStack(spacing: 6) { Icon("map", size: 18, color: C.blue); Text(t("Plan a route", "Panga njia")).font(.lLarge).foregroundColor(C.blue) }.frame(minHeight: 44).padding(.horizontal, 4)
                        }.buttonStyle(.plain).padding(.top, 10)
                    }
                    Section(header: chips(snap, scoped).id("chips")) {
                        if f.hasPrefix("story") {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 6) {
                                    ForEach([("story:need", t("Needs a story", "Inahitaji hadithi")), ("story:draft", t("Draft ready", "Rasimu iko tayari")), ("story:ok", t("Approved", "Imeidhinishwa"))], id: \.0) { k, l in
                                        OChip(label: "\(l) · \(n(k))", on: f == k || (f == "story" && k == "story:need")) { f = k; m.prefs.filter = k }
                                    }
                                }
                            }.padding(.bottom, 8)
                        }
                        Text(countText(snap, rows, here)).font(.mont(12, .semibold)).foregroundColor(C.muted).padding(.leading, 4).fixedSize(horizontal: false, vertical: true)
                        ForEach(Array(rows.enumerated()), id: \.element.id) { i, p in
                            ProjectRow(snap: snap, p: p, here: here, showPinned: !allPinned) { m.go(Place(tab: "list", project: p.id)) }.rise(i, step: 0.03)
                        }
                        if rows.isEmpty {
                            OEmpty(f == "missing" && ck.isEmpty ? t("Every project has a location.", "Kila mradi una mahali.") : f == "repairs" && ck.isEmpty ? t("No open repairs.", "Hakuna matengenezo yaliyo wazi.")
                                   : f == "due" ? t("Everything has been visited in the last 6 months.", "Kila kitu kimetembelewa katika miezi 6 iliyopita.") : f == "build" ? t("Nothing is being built right now.", "Hakuna kinachojengwa kwa sasa.") : t("No projects match.", "Hakuna mradi unaolingana."))
                        }
                    }
                }.padding(.horizontal, 16).padding(.top, 12).padding(.bottom, 24)
            }
            .refreshable { await m.refreshNow() }
        }
        .sheet(isPresented: $filterSheet) {
            ProjectFilterSheet(s: snap, ck: ck, ty: ty, shown: rows.count, onCountry: { ck = $0; m.prefs.country = $0 }, onType: { ty = $0; m.od.set($0, forKey: "ftype") }) { filterSheet = false }
        }
        .fullScreenCover(isPresented: $scanning) { FieldQrScanner { scanning = false } }
    }

    /// One row of filters with their counts that stays at the top while the list scrolls; country and type under Filter.
    func chips(_ snap: Snapshot, _ scoped: [Project]) -> some View {
        let chosen = [countryName(ck), ty.isEmpty ? nil : tgroupName(ty)].compactMap { $0 }
        return ScrollViewReader { sp in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    OChip(label: chosen.isEmpty ? t("Filter", "Chuja") : chosen.joined(separator: " · "), icon: "filter", on: !chosen.isEmpty) { filterSheet = true }
                        .accessibilityLabel(t("Filter by country and type", "Chuja kwa nchi na aina") + (chosen.isEmpty ? "" : ": " + chosen.joined(separator: ", ")))
                    ForEach(FILTERS.filter { k in k.0 != "build" || f == k.0 || scoped.contains { m.building($0) } }, id: \.0) { k, en, sw, icon in
                        OChip(label: t(en, sw) + " · \(scoped.filter { inFilter(snap, $0, k) }.count)", icon: icon, on: f == k || (k == "story" && f.hasPrefix("story"))) { pick(k) }
                            .id(k).accessibilityIdentifier("chip_\(k)")
                    }
                }.padding(.trailing, 20)
            }
            .onChange(of: f) { k in withAnimation(Motion.on ? .easeInOut(duration: 0.3) : nil) { sp.scrollTo(k.hasPrefix("story") ? "story" : k, anchor: .center) } }
        }
        .padding(.top, 4).padding(.bottom, 8)
        .background(LinearGradient(colors: [C.bg, C.bg, C.bg.opacity(0)], startPoint: .top, endPoint: .bottom).padding(.horizontal, -16).padding(.bottom, -8))
    }
}

/// The Filter sheet: country and type of project, each with how many there are; "Show N projects".
private struct ProjectFilterSheet: View {
    let s: Snapshot; let ck: String; let ty: String; let shown: Int; let onCountry: (String) -> Void; let onType: (String) -> Void; let close: () -> Void
    @State private var c = ""
    @State private var k = ""
    @State private var ready = false
    var body: some View {
        let tcount = Dictionary(grouping: s.projects.filter { c.isEmpty || $0.ck == c }, by: tgroup).mapValues(\.count)
        let n = s.projects.filter { (c.isEmpty || $0.ck == c) && (k.isEmpty || tgroup($0) == k) }.count
        OSheet {
            HStack { Text(t("Filter", "Chuja")).font(.tLarge).foregroundColor(C.ink).frame(maxWidth: .infinity, alignment: .leading); OCloseX(action: close) }
            OLabel(t("Country", "Nchi"))
            opt(t("All countries", "Nchi zote"), s.projects.count, c.isEmpty) { c = ""; onCountry("") }
            ForEach(COUNTRIES, id: \.0) { key, name in
                let cnt = s.projects.filter { $0.ck == key }.count
                if cnt > 0 || c == key { opt(name, cnt, c == key) { c = key; onCountry(key) } }
            }
            if tcount.count > 1 {
                OLabel(t("Type of project", "Aina ya mradi"))
                opt(t("All types", "Aina zote"), tcount.values.reduce(0, +), k.isEmpty) { k = ""; onType("") }
                ForEach(TGROUPS.filter { (tcount[$0.0] ?? 0) > 0 }, id: \.0) { key, en, sw in opt(t(en, sw), tcount[key] ?? 0, k == key) { k = key; onType(key) } }
            }
            Btn(n == 1 ? t("Show 1 project", "Onyesha mradi 1") : t("Show \(n) projects", "Onyesha miradi \(n)"), wide: true, action: close).padding(.top, 14)
        }
        .onAppear { if !ready { ready = true; c = ck; k = ty } }
    }
    func opt(_ label: String, _ n: Int, _ on: Bool, _ action: @escaping () -> Void) -> some View {
        Button { Haptic.tap(); action() } label: {
            HStack(spacing: 10) {
                Text(label).font(.tSmall).foregroundColor(C.ink).frame(maxWidth: .infinity, alignment: .leading)
                Text("\(n)").font(.lLarge).foregroundColor(C.muted)
                Icon("check", size: 22, color: on ? C.blue : .clear)
            }.padding(.horizontal, 12).frame(minHeight: 48).background(on ? C.tint : Color.clear).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).contentShape(Rectangle())
        }.buttonStyle(.plain).padding(.vertical, 2)
    }
}

/// A filter: a coloured icon over a short label.
struct FilterTile: View {
    let label: String; let icon: String; let on: Bool; let hue: Hue; var badge = 0; let action: () -> Void
    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Icon(icon, size: 20, color: on ? .white : hue.fg)
                FitWords(label, size: 10, weight: .bold, color: on ? .white : C.ink, lines: 2, align: .center, min: 0.75)
            }
            .padding(.horizontal, 2).padding(.vertical, 8)
            .frame(maxWidth: .infinity, minHeight: 62)
            .background(on ? hue.fg : C.card)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(on ? .clear : C.line, lineWidth: 1))
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

/// Home: a greeting, a big question, the search box and five quick actions; it fits on one screen.
struct HomeTop: View {
    @EnvironmentObject var m: AppModel
    let snap: Snapshot; let here: LatLng?; let filter: (String) -> Void; let scan: () -> Void
    var body: some View {
        let first = m.prefs.name.split(separator: " ").first.map(String.init) ?? ""
        let acts = [("missing", t("Pin a location", "Weka mahali"), "pin"), ("near", t("Visit nearby", "Tembelea karibu"), "clip"),
                    ("new", t("New site", "Eneo jipya"), "plus"), ("repairs", t("Repairs", "Rekebisha"), "wrench"), ("scan", t("Scan QR", "Soma QR"), "scan")]
        VStack(spacing: 0) {
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
            HStack(spacing: 6) {
                ForEach(Array(acts.enumerated()), id: \.element.0) { i, q in
                    let (k, label, icon) = q
                    let hu = ohue(k)
                    Button { Haptic.tap(); if k == "new" { m.tab("new") } else if k == "scan" { scan() } else { filter(k) } } label: {
                        VStack(spacing: 6) {
                            OIcon(icon, size: 20, color: hu.fg).frame(width: 38, height: 38).background(hu.bg).clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
                            FitWords(label, size: 12, weight: .bold, color: C.ink, lines: 2, align: .center, min: 0.68)
                        }.padding(.horizontal, 2).padding(.vertical, 10).frame(maxWidth: .infinity, minHeight: 84)
                        .background(C.card.opacity(0.9)).clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(C.line, lineWidth: 1))
                    }.buttonStyle(Press()).accessibilityIdentifier("quick_\(k)").rise(i + 1, step: 0.06)
                }
            }
        }.frame(maxWidth: .infinity)
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
        let due = testDue(m, p)
        let st = snap.stories[p.id]?.st ?? ""
        let story = st == "approved" || st == "published"
        let place = [p.area, p.country].filter { !$0.isEmpty }.joined(separator: " · ")
        Button(action: open) {
            HStack(spacing: 12) {
                Color.clear.frame(width: 46, height: 46)
                    .overlay(NetImage(url: p.photo.map { m.api.photoUrl($0) }) { OIcon(typeLook(p).0, size: 22, color: box.1) }).clipped()
                    .background(box.0).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(p.title).font(.mont(16, .bold)).foregroundColor(C.text).lineLimit(3).multilineTextAlignment(.leading)
                    ViewThatFits(in: .horizontal) {
                        Text(p.sub).font(.bSmall).foregroundColor(C.muted).lineLimit(1).fixedSize()
                        Text(place).font(.bSmall).foregroundColor(C.muted).lineLimit(1).fixedSize()
                        Text(p.area.isEmpty ? p.country : p.area).font(.bSmall).foregroundColor(C.muted).lineLimit(1)
                    }
                    if dist != nil || rep != nil || p.gps == nil || showPinned || due || story {
                        OFlow(spacing: 6) {
                            if let dist { Tag(dist, bg: C.tint, fg: C.deep) }
                            if let rep { Tag(rep.state == "notworking" ? t("Not working", "Haifanyi kazi") : t("Needs repair", "Inahitaji matengenezo"), bg: rep.state == "notworking" ? C.badBg : C.warnBg, fg: rep.state == "notworking" ? C.bad : C.warn) }
                            if p.gps == nil { Tag(t("No location", "Hakuna mahali"), bg: C.warnBg, fg: C.warn) }
                            else if dist == nil && rep == nil && showPinned { Tag(t("Pinned", "Ina mahali"), bg: C.okBg, fg: C.ok) }
                            if due { WaterTestTag() }
                            if story { Tag(t("Story", "Hadithi"), bg: ohue("purple").bg, fg: ohue("purple").fg) }
                        }.padding(.top, 3)
                    }
                }.frame(maxWidth: .infinity, alignment: .leading)
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

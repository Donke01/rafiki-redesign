import MapKit
import SwiftUI
import UIKit

/*
 The Map tab: pins coloured by how each project is doing, filter chips, search, groups when zoomed out, a card that
 slides up for a tapped pin (swipe it through the projects nearby) and a long press for "New site here" or "Measure
 from me". Calm by default: the map opens with only the pins, a round search button and the column on the right; a tap
 on empty map reveals the chips, the pinned bar, Nearest to you and the Key. The same as Android's MapHome.kt and the web app.
 */

/// Over the map: a little see-through so the map shows; bright sun stays solid.
var mapGlass: Color { C.sun ? C.card : C.card.opacity(C.dark ? 0.92 : 0.88) }

struct MapTab: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject private var loc = Locator.shared
    @ObservedObject private var L = HomeLayers.s
    @State private var status = UserDefaults.standard.string(forKey: "map.f") ?? ""
    @State private var ty = ""
    @State private var sel: String?
    @State private var anchor: String?
    @State private var spot: LatLng?
    @State private var measuring = false
    @State private var rowOpen = false
    @State private var nearOpen = false
    @State private var keyOpen = false
    @State private var tray = false
    @State private var searchOpen = false
    @State private var typing = false
    @State private var quick: String?
    @State private var styleSheet = false
    @State private var dirFor: Project?
    @State private var hint = !UserDefaults.standard.bool(forKey: "map.hint")
    @State private var ready = false
    @State private var size: CGSize = .zero
    @State private var panelH: CGFloat = 0
    @State private var topH: CGFloat = 0
    private let ctl = MapCtl()

    var body: some View {
        if let snap = m.snap { content(snap) } else { Color.clear }
    }

    // MARK: what the map shows

    private func content(_ snap: Snapshot) -> some View {
        let here = loc.fix?.ll
        let pinned = snap.projects.filter { $0.gps != nil }
        let tyOn = !ty.isEmpty && MAP_TYPES.contains { $0.0 == ty } && pinned.contains { tgroup($0) == ty } ? ty : ""
        let filtered = !status.isEmpty || !tyOn.isEmpty
        let yearsOn = L.years && snap.admin
        let visible = yearsOn ? [] : pinned.filter { mapStatusOk(snap, $0, status) && (tyOn.isEmpty || tgroup($0) == tyOn) }
        let pins = visible.map { p in HomePin(id: p.id, ll: p.gps!, cond: mapCondition(snap, p), type: typeLook(p).0, due: mapStatusOk(snap, p, "due"), name: shortName(p.title)) }
        let order: [Project] = {
            guard let a = anchor.flatMap({ id in pinned.first { $0.id == id } }) else { return [] }
            return [a] + Array(visible.filter { $0.id != a.id }.sorted { Geo.metres(a.gps!, $0.gps!) < Geo.metres(a.gps!, $1.gps!) }.prefix(24))
        }()
        let home = snap.me.home.isEmpty ? m.prefs.country : snap.me.home
        let homePts = (home.isEmpty ? pinned : pinned.filter { $0.ck == home }).map { $0.gps! }
        let stops = L.route && !yearsOn ? dayStops(snap, here) : []
        let doneIds = Set(stops.filter { visitedToday(snap, $0.id) }.map(\.id))
        let nextIdx = stops.firstIndex { !doneIds.contains($0.id) }
        let clean = !L.revealed
        let quiet = L.follow || yearsOn
        let panel = quiet || typing ? "none" : sel != nil ? "card" : spot != nil ? "spot" : clean ? "none" : "near"
        let keyShown = !clean && !typing && !yearsOn && sel == nil && spot == nil && !(nearOpen && panel == "near")
        let hintShown = hint && clean && panel == "none" && !typing && !quiet && quick == nil

        return GeometryReader { g in
            ZStack {
                HomeMap(pins: pins, sel: sel, sat: L.sat, follow: L.follow,
                        marks: marks(snap, stops, doneIds, nextIdx, yearsOn),
                        lines: lines(stops, doneIds, nextIdx, here),
                        heat: snap.admin && !L.heat.isEmpty ? HeatOverlay(heatPoints(snap, L.heat, year: yearsOn ? L.year : nil), kind: L.heat) : nil,
                        fitFirst: homePts, ctl: ctl,
                        onPin: { pick($0, snap) },
                        onMark: { mk in
                            if mk.key.hasPrefix("s:") || mk.key.hasPrefix("t:") { L.pick = L.pick == mk.key ? nil : mk.key }
                            else if mk.key.hasPrefix("y:") { open(String(mk.key.dropFirst(2))) }
                        },
                        onTapEmpty: { tapMap() },
                        onLong: { ll in
                            guard !yearsOn else { return }
                            L.follow = false; sel = nil; anchor = nil; measuring = false; tray = false; keyOpen = false; quick = nil
                            withAnimation(.spring(response: 0.32, dampingFraction: 0.85)) { spot = ll }
                        },
                        onLongPin: { id in if !yearsOn { spot = nil; tray = false; keyOpen = false; quick = id } },
                        onFollowEnd: { L.follow = false })
                    .ignoresSafeArea(edges: [])

                // 1. the bottom: the layers' cards, then the card, the spot or Nearest to you
                VStack(spacing: 8) {
                    Spacer(minLength: 0)
                    LayerCards(snap: snap, stops: stops, nextIdx: nextIdx, here: here, visit: visit, open: open)
                    Group {
                        switch panel {
                        case "card": PreviewCard(snap: snap, order: order, sel: sel, here: here, step: { step($0, order) }, close: closeCards, visit: visit, open: open, directions: { dirFor = $0 })
                        case "spot": if let sp = spot { SpotCard(sp: sp, here: here, measuring: measuring, close: closeCards, add: { newSite(sp) }) {
                            measuring.toggle()
                            if measuring, let h = here { ctl.fit([h, sp], pad: pad(), minMetres: 300) }
                        } }
                        case "near": NearestSheet(snap: snap, here: here, open: open, slim: filtered, expanded: $nearOpen, pool: filtered ? visible : nil, emptyText: nearNone(tyOn, status), maxH: max(160, g.size.height - topH - 90))
                        default: EmptyView()
                        }
                    }
                    .transition(Motion.on ? .move(edge: .bottom).combined(with: .opacity) : .identity)
                    .background(GeometryReader { b in Color.clear.preference(key: PanelH.self, value: b.size.height) })
                }
                .padding(12)
                .animation(Motion.on ? .spring(response: 0.32, dampingFraction: 0.88) : nil, value: panel)
                .onPreferenceChange(PanelH.self) { panelH = $0 }

                // 2. the Key and the one-time hint, bottom left above the panel
                VStack(alignment: .leading, spacing: 8) {
                    Spacer(minLength: 0)
                    if keyOpen && keyShown { MapKeyPop(stops: L.route && !stops.isEmpty, team: L.team && snap.admin).transition(.scale(scale: 0.94, anchor: .bottomLeading).combined(with: .opacity)) }
                    if keyShown { MapKeyButton(open: keyOpen) { withAnimation(.easeOut(duration: 0.18)) { keyOpen.toggle() }; if keyOpen { tray = false } }.transition(.opacity) }
                    if hintShown { CalmHint { hint = false }.frame(maxWidth: .infinity).transition(.opacity) }
                }
                .padding(.leading, 12).padding(.bottom, 12 + (panel == "none" ? 0 : panelH + 8))
                .frame(maxWidth: .infinity, alignment: .leading)
                .animation(Motion.on ? .easeOut(duration: 0.2) : nil, value: keyShown)

                // 3. the controls at the top left: search, the chip row or its pill, the pinned bar, "You're at"
                VStack(alignment: .leading, spacing: 8) {
                    if clean && !searchOpen && !typing {
                        Button { Haptic.tap(); hint = false; searchOpen = true } label: {
                            Icon("search", size: 19, color: C.ink).frame(width: 44, height: 44).background(mapGlass).clipShape(Circle())
                                .overlay(Circle().stroke(C.line, lineWidth: 1)).shadow(color: .black.opacity(0.15), radius: 3, y: 1)
                        }.buttonStyle(.plain).accessibilityLabel(t("Search a village or project", "Tafuta kijiji au mradi"))
                    } else {
                        MapSearch(snap: snap, here: here, autofocus: searchOpen, typing: $typing,
                                  onProject: { id in pick(id, snap, fly: true) },
                                  onArea: { pts in L.follow = false; ctl.fit(pts, pad: pad()); closeCards() },
                                  onPlace: { ll in L.follow = false; ctl.fly(ll, metres: 12000); closeCards() },
                                  onClose: { if clean { searchOpen = false } })
                    }
                    if !typing {
                        if !yearsOn && (!clean || filtered) {
                            if filtered && !rowOpen { FilterPillMap(status: status, ty: tyOn, n: visible.count, open: { Haptic.tap(); rowOpen = true }, clear: clearFilters) }
                            else {
                                MapChips(snap: snap, pinned: pinned, status: status, ty: tyOn, onStatus: { k in
                                    status = status == k ? "" : k; UserDefaults.standard.set(status, forKey: "map.f"); rowOpen = false
                                    if !status.isEmpty || !tyOn.isEmpty { nearOpen = false }
                                    fitFilter(snap, pinned)
                                }, onType: { k in
                                    ty = tyOn == k ? "" : k; m.prefs.ftype = ty; rowOpen = false
                                    if !status.isEmpty || !ty.isEmpty { nearOpen = false }
                                    fitFilter(snap, pinned)
                                }, clear: clearFilters)
                            }
                            if filtered && visible.isEmpty {
                                Text(t("Nothing on the map matches these filters.", "Hakuna kwenye ramani kinacholingana na vichujio hivi.")).font(.lMedium).foregroundColor(C.ink)
                                    .padding(.horizontal, 12).padding(.vertical, 7).background(mapGlass).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                            }
                        }
                        if !filtered && !clean && !(nearOpen && panel == "near") { PinnedBar(snap: snap, pn: pinned.count).transition(.move(edge: .top).combined(with: .opacity)) }
                        HereSlot(snap: snap, here: here, visit: visit, open: open)
                    }
                }
                .padding(12).padding(.trailing, 56)
                .background(GeometryReader { b in Color.clear.preference(key: TopH.self, value: b.size.height) })
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .animation(Motion.on ? .easeOut(duration: 0.2) : nil, value: clean)
                .onPreferenceChange(TopH.self) { topH = $0 }

                // 4. the column on the right: map style, where I am, more; follow me while it is on
                VStack(alignment: .trailing, spacing: 8) {
                    MapSide(tray: tray, follow: L.follow, styles: { tray = false; styleSheet = true }, me: {
                        tray = false
                        if loc.fix != nil { ctl.me() } else { Locator.shared.start(); m.say(t("Finding where you are… Turn on location.", "Inatafuta mahali ulipo… Washa mahali.")) }
                    }, more: { withAnimation(.easeOut(duration: 0.18)) { tray.toggle() }; if tray { keyOpen = false; nearOpen = false; quick = nil } }, stopFollow: { L.follow = false })
                    if tray {
                        MapTray(follow: L.follow) {
                            tray = false
                            if loc.fix == nil { Locator.shared.start(); m.say(t("Finding where you are… Turn on location to follow.", "Inatafuta mahali ulipo… Washa mahali ili ikufuate.")) }
                            closeCards(); L.follow = true
                        }.transition(.move(edge: .top).combined(with: .opacity))
                    }
                }
                .padding(.top, 12).padding(.trailing, 10)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
            }
            .onAppear { size = g.size }
            .onChange(of: g.size) { size = $0 }
        }
        .onAppear {
            if !ready { ready = true; ty = m.prefs.ftype; Locator.shared.start() }
            if let id = m.mapFocus { m.mapFocus = nil; DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { pick(id, snap, fly: true) } }
        }
        .onChange(of: hintShown) { on in
            if on { UserDefaults.standard.set(true, forKey: "map.hint"); DispatchQueue.main.asyncAfter(deadline: .now() + 4.5) { withAnimation { hint = false } } }
        }
        .onChange(of: keyOpen) { on in if on { DispatchQueue.main.asyncAfter(deadline: .now() + 5) { withAnimation { keyOpen = false } } } }
        .onChange(of: L.follow) { on in if on { closeCards() } }
        .sheet(isPresented: $styleSheet) { MapStyleSheet(snap: snap, stops: stops).environmentObject(m) }
        .sheet(item: $dirFor) { p in if let g = p.gps { DirectionsSheet(title: p.title, at: g) } }
        .confirmationDialog(quick.flatMap { id in snap.projects.first { $0.id == id }?.title } ?? "", isPresented: Binding(get: { quick != nil }, set: { if !$0 { quick = nil } }), titleVisibility: .visible) {
            if let id = quick {
                Button(t("Report a visit", "Ripoti ziara")) { visit(id) }
                Button(t("Directions", "Maelekezo")) { dirFor = snap.projects.first { $0.id == id } }
                Button(t("Open", "Fungua")) { open(id) }
            }
        }
    }

    // MARK: actions

    private func pad() -> UIEdgeInsets {
        UIEdgeInsets(top: topH + 30, left: 30, bottom: (panelH > 0 ? panelH + 30 : 40), right: 70)
    }
    private func pick(_ id: String, _ snap: Snapshot, fly: Bool = false) {
        L.follow = false; Haptic.tap(); spot = nil; measuring = false; anchor = id; tray = false; keyOpen = false; quick = nil; typing = false
        withAnimation(.spring(response: 0.32, dampingFraction: 0.88)) { sel = id }
        guard let p = snap.projects.first(where: { $0.id == id }), let g = p.gps else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + (Motion.on ? 0.3 : 0)) {
            ctl.focus(g, pad: UIEdgeInsets(top: topH + 40, left: 30, bottom: max(panelH, 230) + 40, right: 70))
        }
        _ = fly
    }
    private func step(_ d: Int, _ order: [Project]) {
        guard let i = order.firstIndex(where: { $0.id == sel }), i + d >= 0, i + d < order.count else { return }
        let p = order[i + d]; Haptic.tap()
        withAnimation(.spring(response: 0.3, dampingFraction: 0.9)) { sel = p.id }
        if let g = p.gps { ctl.fly(g) }
    }
    private func closeCards() { withAnimation(.spring(response: 0.3, dampingFraction: 0.9)) { sel = nil; anchor = nil; spot = nil; measuring = false; quick = nil } }
    private func clearFilters() { Haptic.tap(); status = ""; ty = ""; UserDefaults.standard.set("", forKey: "map.f"); m.prefs.ftype = ""; rowOpen = false }
    private func tapMap() {
        if typing { typing = false; UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil); return }
        if quick != nil { quick = nil; return }
        if tray || keyOpen { tray = false; keyOpen = false; return }
        if sel != nil || spot != nil { closeCards(); return }
        if rowOpen && (!status.isEmpty || !ty.isEmpty) { rowOpen = false; return }
        Haptic.tap(); hint = false
        withAnimation(Motion.on ? .easeOut(duration: 0.22) : nil) { L.revealed.toggle(); if !L.revealed { nearOpen = false; searchOpen = false } }
    }
    private func fitFilter(_ snap: Snapshot, _ pinned: [Project]) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.08) {
            let tyOn = ty
            let pts = pinned.filter { mapStatusOk(snap, $0, status) && (tyOn.isEmpty || tgroup($0) == tyOn) }.map { $0.gps! }
            if !status.isEmpty || !tyOn.isEmpty { ctl.fit(pts, pad: pad()) }
        }
    }
    private func open(_ id: String) { m.go(Place(tab: "map", project: id)) }
    private func visit(_ id: String) { m.go(Place(tab: "map", project: id, projectTab: "visit")) }
    private func newSite(_ ll: LatLng) { NewSiteStart.at = ll; closeCards(); m.tab("new") }

    // MARK: the layers' marks and lines

    private func marks(_ snap: Snapshot, _ stops: [Project], _ done: Set<String>, _ next: Int?, _ yearsOn: Bool) -> [MarkAnn] {
        var out: [MarkAnn] = []
        for (i, p) in stops.enumerated() {
            let d = done.contains(p.id), nx = i == next
            out.append(MarkAnn(.stop(i + 1, d, nx), key: "s:\(p.id)|\(d)|\(nx)|\(i)", at: p.gps!, image: PinArt.stop(i + 1, done: d, next: nx), label: t("Stop \(i + 1): ", "Kituo \(i + 1): ") + p.title))
        }
        if let sp = spot { out.append(MarkAnn(.spot, key: "spot\(sp.lat),\(sp.lng)", at: sp, image: PinArt.spot)) }
        if yearsOn {
            for p in doneBy(snap, L.year) {
                let fresh = p.year == L.year
                out.append(MarkAnn(.year(fresh), key: "y:\(p.id)|\(fresh)", at: p.gps!, image: PinArt.year(fresh: fresh), label: p.title))
            }
        }
        if L.team && snap.admin, let tm = L.tm {
            for (i, s) in tm.people.enumerated() {
                out.append(MarkAnn(.mate(i), key: "t:\(s.name)|\(s.at)", at: s.ll, image: PinArt.mate(s.name, PinArt.MATE[i % PinArt.MATE.count], agoText(s.at), faded: minutesSince(s.at) > 60), label: s.name))
            }
        }
        return out
    }
    private func lines(_ stops: [Project], _ done: Set<String>, _ next: Int?, _ here: LatLng?) -> [LineOverlay] {
        func line(_ pts: [LatLng], _ color: UIColor, _ w: CGFloat, dash: [NSNumber]? = nil) -> LineOverlay {
            var cs = pts.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lng) }
            let l = LineOverlay(coordinates: &cs, count: cs.count); l.color = color; l.width = w; l.dash = dash; return l
        }
        var out: [LineOverlay] = []
        let pts = stops.map { $0.gps! }
        if pts.count > 1 {
            out.append(line(pts, UIColor.white.withAlphaComponent(0.86), 10))
            for i in 1..<pts.count { out.append(line([pts[i - 1], pts[i]], done.contains(stops[i].id) ? UIColor(red: 0.11, green: 0.61, blue: 0.35, alpha: 1) : UIColor(red: 0.04, green: 0.47, blue: 0.76, alpha: 1), 5)) }
        }
        if let h = here, let n = next { out.append(line([h, pts[n]], UIColor(red: 0.04, green: 0.47, blue: 0.76, alpha: 1), 3, dash: [1, 9])) }
        if measuring, let h = here, let sp = spot {
            out.append(line([h, sp], .white, 7))
            out.append(line([h, sp], UIColor(red: 0.04, green: 0.47, blue: 0.76, alpha: 1), 3.5, dash: [10, 7]))
        }
        return out
    }
}

private struct PanelH: PreferenceKey { static var defaultValue: CGFloat = 0; static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) } }
private struct TopH: PreferenceKey { static var defaultValue: CGFloat = 0; static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) } }


// MARK: - the pieces over the map

/// Once on this phone: the map is calm, a tap shows the filters.
private struct CalmHint: View {
    let close: () -> Void
    var body: some View {
        Button(action: close) {
            HStack(spacing: 8) {
                Image(systemName: "hand.tap").font(.system(size: 15, weight: .semibold)).foregroundColor(.white)
                Text(t("Tap the map for filters", "Gusa ramani kuona vichujio")).font(.mont(13, .bold)).foregroundColor(.white).lineLimit(1)
            }.padding(.leading, 12).padding(.trailing, 14).frame(minHeight: 44).background(Color(hex: 0x04263F, alpha: 0.9)).clipShape(Capsule()).shadow(color: .black.opacity(0.2), radius: 4, y: 2)
        }.buttonStyle(.plain)
    }
}

/// One pill for the active filter(s): "Water · 98"; tap it for the row of chips again, × clears.
private struct FilterPillMap: View {
    let status: String; let ty: String; let n: Int; let open: () -> Void; let clear: () -> Void
    var body: some View {
        var items: [(String, String, Hue)] = []
        if let mt = MAP_TYPES.first(where: { $0.0 == ty }), let g = TGROUPS.first(where: { $0.0 == ty }) { items.append((t(g.1, g.2), mt.1, C.named(mt.2))) }
        if let s = MAP_STATUS.first(where: { $0.0 == status }) { items.append((t(s.1, s.2), s.3, statusHue(s.0))) }
        let hu = items.count == 1 ? items[0].2 : C.named("blue")
        return HStack(spacing: 0) {
            Button(action: open) {
                HStack(spacing: 6) {
                    Icon(items.count == 1 ? items[0].1 : "filter", size: 15, color: .white)
                    Text(items.map(\.0).joined(separator: " + ")).font(.mont(13, .heavy)).foregroundColor(.white).lineLimit(1)
                    Text("· \(n)").font(.mont(13, .bold)).foregroundColor(.white).fixedSize()
                    Icon("down", size: 14, color: .white.opacity(0.85))
                }.padding(.leading, 12).padding(.trailing, 2).frame(minHeight: 44)
            }.buttonStyle(.plain)
            Button(action: clear) {
                Icon("x", size: 12, color: .white).frame(width: 22, height: 22).background(Color.white.opacity(0.22)).clipShape(Circle()).frame(width: 44, height: 44)
            }.buttonStyle(.plain).accessibilityLabel(t("Clear filters", "Ondoa vichujio"))
        }
        .background(Capsule().fill(hu.fg).padding(.vertical, 4).shadow(color: .black.opacity(0.14), radius: 2, y: 1))
    }
}

/// Broken, Due a visit, My assigned visits, then the kinds of project; each with how many. Swipe sideways.
private struct MapChips: View {
    let snap: Snapshot; let pinned: [Project]; let status: String; let ty: String
    let onStatus: (String) -> Void; let onType: (String) -> Void; let clear: () -> Void
    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                if !status.isEmpty || !ty.isEmpty {
                    Button(action: clear) { Icon("x", size: 18, color: C.ink).frame(width: 36, height: 36).background(Circle().fill(mapGlass)).overlay(Circle().stroke(C.line, lineWidth: 1)).frame(width: 44, height: 44) }
                        .buttonStyle(.plain).accessibilityLabel(t("Clear filters", "Ondoa vichujio"))
                }
                ForEach(MAP_STATUS, id: \.0) { k, en, sw, icon in
                    let n = pinned.filter { mapStatusOk(snap, $0, k) && (ty.isEmpty || tgroup($0) == ty) }.count
                    MapChip(label: t(en, sw), icon: icon, n: n, on: status == k, hu: statusHue(k)) { onStatus(k) }
                }
                ForEach(MAP_TYPES, id: \.0) { k, icon, hue in
                    if pinned.contains(where: { tgroup($0) == k }), let g = TGROUPS.first(where: { $0.0 == k }) {
                        let n = pinned.filter { tgroup($0) == k && mapStatusOk(snap, $0, status) }.count
                        MapChip(label: t(g.1, g.2), icon: icon, n: n, on: ty == k, hu: C.named(hue)) { onType(k) }
                    }
                }
            }.padding(.trailing, 20)
        }
        .mask(LinearGradient(stops: [.init(color: .black, location: 0), .init(color: .black, location: 0.9), .init(color: .clear, location: 1)], startPoint: .leading, endPoint: .trailing))
    }
}

private struct MapChip: View {
    let label: String; let icon: String; let n: Int; let on: Bool; let hu: Hue; let action: () -> Void
    var body: some View {
        Button { Haptic.tap(); action() } label: {
            HStack(spacing: 5) {
                Icon(icon, size: 15, color: on ? .white : hu.fg)
                Text(label).font(.mont(13, .bold)).foregroundColor(on ? .white : C.ink).lineLimit(1)
                Text("\(n)").font(.mont(11, .bold)).foregroundColor(on ? hu.fg : C.muted).padding(.horizontal, 6).background(on ? Color.white : C.bg).clipShape(Capsule())
            }
            .padding(.leading, 10).padding(.trailing, 8).frame(minHeight: 36)
            .background(Capsule().fill(on ? hu.fg : mapGlass)).overlay(Capsule().stroke(on ? hu.fg : C.line, lineWidth: 1))
            .shadow(color: .black.opacity(0.12), radius: 2, y: 1)
            .frame(minHeight: 44)
        }.buttonStyle(.plain).accessibilityLabel("\(label), \(n)" + (on ? ", " + t("on", "imewashwa") : ""))
    }
}

/// "116 of 181 projects pinned · 65 to go" on one line, with a thin meter under it.
private struct PinnedBar: View {
    let snap: Snapshot; let pn: Int
    var body: some View {
        let all = snap.projects.count
        VStack(spacing: 5) {
            HStack(spacing: 8) {
                ViewThatFits {
                    Text(t("\(pn) of \(all) projects pinned", "Miradi \(pn) kati ya \(all) ina mahali"))
                    Text(t("\(pn)/\(all) pinned", "\(pn)/\(all) zina mahali"))
                }.font(.mont(12, .bold)).foregroundColor(C.ink).lineLimit(1)
                Spacer(minLength: 0)
                Text(t("\(all - pn) to go", "\(all - pn) imebaki")).font(.mont(11)).foregroundColor(C.muted).lineLimit(1)
            }
            GeometryReader { g in
                ZStack(alignment: .leading) { Capsule().fill(C.line); Capsule().fill(OkBrush).frame(width: g.size.width * CGFloat(pn) / CGFloat(max(1, all))) }
            }.frame(height: 4)
        }
        .padding(.horizontal, 12).padding(.vertical, 8).background(mapGlass).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.line, lineWidth: 1))
    }
}

/// The small "Key" button: its colours pop up above it.
private struct MapKeyButton: View {
    let open: Bool; let toggle: () -> Void
    var body: some View {
        Button { Haptic.tap(); toggle() } label: {
            HStack(spacing: 5) { Icon("info", size: 15, color: C.blue); Text(t("Key", "Rangi")).font(.mont(13, .heavy)).foregroundColor(C.ink) }
                .padding(.leading, 10).padding(.trailing, 13).frame(minHeight: 36).background(Capsule().fill(mapGlass)).overlay(Capsule().stroke(C.line, lineWidth: 1))
                .shadow(color: .black.opacity(0.12), radius: 2, y: 1).frame(minHeight: 44)
        }.buttonStyle(.plain).accessibilityLabel(t("Map key: what the colours mean", "Maelezo ya rangi za ramani"))
    }
}

private struct MapKeyPop: View {
    let stops: Bool; let team: Bool
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach([PinCond.ok, PinCond.repair, PinCond.bad, PinCond.old], id: \.self) { c in
                row(condName(c)) { Circle().stroke(Color(condUIColor(c)), lineWidth: 3).background(Circle().fill(.white)).frame(width: 12, height: 12) }
            }
            row(t("Visit or water test due", "Ziara au kipimo cha maji kinasubiri")) { Circle().fill(Color(PinArt.dueColor)).frame(width: 7, height: 7).padding(1.5).background(Circle().fill(.white)) }
            row(t("A group: tap to zoom in", "Kundi: gusa ili kukuza")) { Image(uiImage: PinArt.donut([2, 0, 0, 1], 3)).resizable().frame(width: 16, height: 16) }
            row(t("You", "Wewe")) { Circle().fill(Color(hex: 0x098BE1)).frame(width: 8, height: 8).padding(2).background(Circle().fill(.white)).padding(2).background(Circle().fill(Color(hex: 0x098BE1, alpha: 0.22))) }
            if stops { row(t("Today’s route stop", "Kituo cha njia ya leo")) { Image(uiImage: PinArt.stop(1, done: false, next: false)).resizable().frame(width: 16, height: 16) } }
            if team { row(t("Teammate, last seen", "Mwenzako, alionekana")) { Text("A").font(.system(size: 8, weight: .heavy)).foregroundColor(.white).frame(width: 14, height: 14).background(Circle().fill(Color(hex: 0x7A4FD0))).padding(1).background(Circle().fill(.white)) } }
        }
        .padding(.horizontal, 12).padding(.vertical, 10).background(C.card).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.line, lineWidth: 1)).shadow(color: .black.opacity(0.18), radius: 8, y: 3)
    }
    private func row<M: View>(_ label: String, @ViewBuilder _ mark: () -> M) -> some View {
        HStack(spacing: 8) { mark().frame(width: 16, height: 16); Text(label).font(.mont(12, .semibold)).foregroundColor(C.ink).lineLimit(1) }
    }
}

/// The column of buttons on the right: map style, go to where I am, more; Follow me (blue) while it is on.
private struct MapSide: View {
    let tray: Bool; let follow: Bool; let styles: () -> Void; let me: () -> Void; let more: () -> Void; let stopFollow: () -> Void
    var body: some View {
        let face = C.sun ? Color.white : Color.white.opacity(0.92)
        VStack(spacing: 8) {
            VStack(spacing: 0) {
                side("layers", t("Map style", "Mtindo wa ramani"), styles)
                Rectangle().fill(Color(hex: 0x04263F, alpha: 0.12)).frame(height: 1)
                side("target", t("Go to where I am", "Nenda nilipo"), me)
                Rectangle().fill(Color(hex: 0x04263F, alpha: 0.12)).frame(height: 1)
                side("more", t("More map tools", "Zana zaidi za ramani"), more, on: tray)
            }
            .frame(width: 44).background(face).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Color.black.opacity(0.16), lineWidth: 1)).shadow(color: .black.opacity(0.15), radius: 3, y: 1)
            if follow {
                Button { Haptic.tap(); stopFollow() } label: {
                    Icon("nav", size: 24, color: .white).frame(width: 44, height: 44).background(Color(hex: 0x0A78C2)).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                }.buttonStyle(.plain).accessibilityLabel(t("Stop following me", "Acha kunifuata"))
            }
        }
    }
    private func side(_ icon: String, _ label: String, _ action: @escaping () -> Void, on: Bool = false) -> some View {
        Button { Haptic.tap(); action() } label: { Icon(icon, size: 19, color: Color(hex: 0x04263F)).frame(width: 44, height: 44).background(on ? Color(hex: 0x0A78C2, alpha: 0.1) : .clear) }
            .buttonStyle(.plain).accessibilityLabel(label)
    }
}

/// Under More: Follow me, with its name.
private struct MapTray: View {
    let follow: Bool; let onFollow: () -> Void
    var body: some View {
        VStack(spacing: 0) {
            if !follow {
                Button { Haptic.tap(); onFollow() } label: {
                    HStack(spacing: 12) {
                        IconTile(icon: "nav", tint: C.named("blue").fg, bg: C.named("blue").bg, size: 34)
                        Text(t("Follow me", "Nifuate")).font(.tSmall).foregroundColor(C.ink)
                        Spacer(minLength: 0)
                    }.padding(.leading, 6).padding(.trailing, 14).frame(minHeight: 48).contentShape(Rectangle())
                }.buttonStyle(.plain).accessibilityLabel(t("Follow me while driving", "Nifuate ninapoendesha"))
            } else {
                Text(t("Following you. Drag the map to stop.", "Inakufuata. Buruta ramani kusimamisha.")).font(.bSmall).foregroundColor(C.muted).padding(10)
            }
        }
        .padding(6).frame(width: 210).background(C.card).clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(C.line, lineWidth: 1)).shadow(color: .black.opacity(0.18), radius: 10, y: 4)
    }
}

/// Search on the map: projects and their villages or areas as you type (from the list on this phone, so it works with
/// no signal), and places by name when there is signal. Choosing one flies the map there.
private struct MapSearch: View {
    @EnvironmentObject var m: AppModel
    let snap: Snapshot; let here: LatLng?; let autofocus: Bool
    @Binding var typing: Bool
    let onProject: (String) -> Void; let onArea: ([LatLng]) -> Void; let onPlace: (LatLng) -> Void; let onClose: () -> Void
    @State private var q = ""
    @State private var places: [(String, String, LatLng)] = []
    @State private var offline = false
    @FocusState private var focused: Bool
    var body: some View {
        let words = q.trimmingCharacters(in: .whitespaces).lowercased().split(separator: " ").map(String.init)
        let pinned = snap.projects.filter { $0.gps != nil }
        let areas: [(String, [Project])] = words.isEmpty ? [] : Array(Dictionary(grouping: pinned.filter { !$0.area.isEmpty }, by: { $0.area.trimmingCharacters(in: .whitespaces) })
            .filter { a, l in let hay = (a + " " + (l.first?.country ?? "")).lowercased(); return words.allSatisfy { hay.contains($0) } }
            .sorted { a, b in let sa = a.key.lowercased().hasPrefix(words[0]), sb = b.key.lowercased().hasPrefix(words[0]); return sa != sb ? sa : a.value.count > b.value.count }
            .prefix(2).map { ($0.key, $0.value) })
        let projs: [Project] = words.isEmpty ? [] : Array(pinned.filter { p in let hay = "\(p.title) \(p.area) \(p.country)".lowercased(); return words.allSatisfy { hay.contains($0) } }
            .sorted { a, b in
                let sa = a.title.lowercased().hasPrefix(words[0]), sb = b.title.lowercased().hasPrefix(words[0])
                if sa != sb { return sa }
                guard let h = here else { return false }
                return Geo.metres(h, a.gps!) < Geo.metres(h, b.gps!)
            }.prefix(4))
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 9) {
                Icon("search", size: 18, color: focused ? C.blue : C.muted)
                TextField("", text: $q, prompt: Text(t("Search a village or project", "Tafuta kijiji au mradi")).foregroundColor(C.muted)).font(.bMedium).foregroundColor(C.text)
                    .focused($focused).submitLabel(.search)
                    .onSubmit {
                        if let p = projs.first { done(p.title); onProject(p.id) }
                        else if let a = areas.first { done(a.0); onArea(a.1.map { $0.gps! }) }
                        else if let pl = places.first { done(pl.0); onPlace(pl.2) }
                    }
                if !q.isEmpty || focused {
                    Button { if q.isEmpty { focused = false; onClose() } else { q = "" } } label: { Icon("x", size: 16, color: C.muted).frame(width: 44, height: 44) }.buttonStyle(.plain).accessibilityLabel(t("Clear", "Futa"))
                }
            }
            .padding(.leading, 12).frame(minHeight: 44).background(focused ? C.card : mapGlass).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(focused ? C.blue : C.line, lineWidth: focused ? 1.5 : 1)).shadow(color: .black.opacity(0.14), radius: 3, y: 1)
            if focused && !words.isEmpty {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(areas, id: \.0) { a, l in
                            sugg("pin", C.named("green"), a, l.count == 1 ? t("\(l[0].country) · 1 project", "\(l[0].country) · mradi 1") : t("\(l[0].country) · \(l.count) projects", "\(l[0].country) · miradi \(l.count)")) { done(a); onArea(l.map { $0.gps! }) }
                        }
                        ForEach(projs) { p in
                            let look = typeLook(p)
                            sugg(look.0, C.named(look.1), p.title, [p.area, here.map { Geo.dist(Geo.metres($0, p.gps!), miles: m.miles) } ?? ""].filter { !$0.isEmpty }.joined(separator: " · ")) { done(p.title); onProject(p.id) }
                        }
                        ForEach(Array(places.enumerated()), id: \.offset) { _, pl in sugg("globe", C.named("slate"), pl.0, pl.1) { done(pl.0); onPlace(pl.2) } }
                        if areas.isEmpty && projs.isEmpty && places.isEmpty {
                            Text(offline ? t("No project or area matches.", "Hakuna mradi wala eneo linalolingana.") : t("Nothing found yet. Try a village, school or town.", "Bado hakuna kilichopatikana. Jaribu kijiji, shule au mji."))
                                .font(.bMedium).foregroundColor(C.muted).padding(.horizontal, 16).padding(.vertical, 10)
                        }
                        if !places.isEmpty { Text(t("Places: © OpenStreetMap contributors", "Maeneo: © OpenStreetMap contributors")).font(.lSmall).foregroundColor(C.muted).padding(.horizontal, 16).padding(.vertical, 4) }
                        if offline { Text(t("No signal: searching the projects on this phone.", "Hakuna mtandao: inatafuta miradi iliyo kwenye simu hii.")).font(.lSmall).foregroundColor(C.muted).padding(.horizontal, 16).padding(.vertical, 4) }
                    }.padding(.vertical, 6)
                }
                .frame(maxHeight: 360).fixedSize(horizontal: false, vertical: true)
                .background(C.card).clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(C.line, lineWidth: 1)).shadow(color: .black.opacity(0.18), radius: 8, y: 3)
            }
        }
        .onAppear { if autofocus { DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { focused = true } } }
        .onChange(of: focused) { typing = $0 }
        .onChange(of: typing) { if !$0 { focused = false } }
        .task(id: q) {
            places = []
            let qq = q.trimmingCharacters(in: .whitespaces)
            guard qq.count >= 3 else { return }
            try? await Task.sleep(nanoseconds: 450_000_000)
            if Task.isCancelled { return }
            let r = await m.api.place(qq, near: here, ck: "")
            offline = r.status == 0
            guard r.ok else { return }
            places = Array(r.json.objs("places").compactMap { o in o.num("lat").map { (o.str("name"), o.str("where"), LatLng(lat: $0, lng: o.num("lng") ?? 0)) } }.prefix(3))
        }
    }
    private func done(_ text: String) { q = text; focused = false }
    private func sugg(_ icon: String, _ hu: Hue, _ title: String, _ sub: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                IconTile(icon: icon, tint: hu.fg, bg: hu.bg, size: 36)
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).font(.tSmall).foregroundColor(C.ink).lineLimit(1)
                    if !sub.isEmpty { Text(sub).font(.bSmall).foregroundColor(C.muted).lineLimit(1) }
                }
                Spacer(minLength: 0)
            }.padding(.horizontal, 12).padding(.vertical, 6).frame(minHeight: 52).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }
}

/// The "You're at …" strip: within 200 m, and it stays until 260 m away so GPS wobble doesn't make it blink.
struct HereSlot: View {
    @EnvironmentObject var m: AppModel
    let snap: Snapshot; let here: LatLng?; let visit: (String) -> Void; let open: (String) -> Void
    @State private var keep: String?
    var body: some View {
        let near = here.flatMap { h in snap.projects.compactMap { p in p.gps.map { (p, Geo.metres(h, $0)) } }.min { $0.1 < $1.1 } }
        let now = near.flatMap { n in (n.1 <= 200 || (n.0.id == keep && n.1 <= 260)) && !m.dismissed.contains(n.0.id) ? n : nil }
        Group {
            if let n = now {
                let p = n.0, d = n.1
                HStack(spacing: 8) {
                    Button { open(p.id) } label: {
                        HStack(spacing: 10) {
                            Icon("pin", size: 20, color: .white)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(t("YOU’RE AT", "UKO") + " · " + Geo.dist(d, miles: m.miles)).font(.mont(10, .heavy)).tracking(0.8).foregroundColor(.white.opacity(0.8)).lineLimit(1)
                                Text(p.title).font(.tSmall).foregroundColor(.white).lineLimit(2).multilineTextAlignment(.leading)
                            }
                            Spacer(minLength: 0)
                        }.frame(minHeight: 44).contentShape(Rectangle())
                    }.buttonStyle(.plain)
                    Button { visit(p.id) } label: {
                        Text(t("Report", "Ripoti")).font(.mont(14, .bold)).foregroundColor(Color(hex: 0x0E4A2B)).padding(.horizontal, 14).frame(minHeight: 40).background(Color.white).clipShape(Capsule())
                    }.buttonStyle(.plain)
                    Button { m.dismissed.insert(p.id) } label: {
                        Icon("x", size: 16, color: .white).frame(width: 40, height: 40).overlay(Circle().stroke(Color.white.opacity(0.6), lineWidth: 1))
                    }.buttonStyle(.plain).accessibilityLabel(t("Not now", "Si sasa"))
                }
                .padding(.leading, 14).padding(.trailing, 8).padding(.vertical, 4).background(OkBrush).clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                .transition(.move(edge: .top).combined(with: .opacity))
                .onAppear { keep = p.id }
            }
        }.animation(Motion.on ? .spring(response: 0.35, dampingFraction: 0.85) : nil, value: now?.0.id)
    }
}

/// "Nearest to you": the closest pinned projects, folded until you open it. Slim (one line) while a filter is on.
private struct NearestSheet: View {
    @EnvironmentObject var m: AppModel
    let snap: Snapshot; let here: LatLng?; let open: (String) -> Void; let slim: Bool
    @Binding var expanded: Bool
    let pool: [Project]?; let emptyText: String; let maxH: CGFloat
    var body: some View {
        let list: [(Project, Double)] = here.map { h in Array((pool ?? snap.projects).compactMap { p in p.gps.map { (p, Geo.metres(h, $0)) } }.sorted { $0.1 < $1.1 }.prefix(5)) } ?? []
        VStack(alignment: .leading, spacing: 0) {
            Button { Haptic.tap(); withAnimation(.spring(response: 0.32, dampingFraction: 0.88)) { expanded.toggle() } } label: {
                VStack(spacing: 6) {
                    Capsule().fill(C.edge).frame(width: 36, height: 4)
                    HStack(spacing: 6) {
                        if slim && !expanded, let f = list.first {
                            Text(t("Nearest:", "Karibu zaidi:")).font(.mont(14, .heavy)).foregroundColor(C.ink).fixedSize()
                            Text(f.0.title).font(.tSmall).foregroundColor(C.ink).lineLimit(2).multilineTextAlignment(.leading)
                            Text("· " + Geo.dist(f.1, miles: m.miles)).font(.mont(14, .heavy)).foregroundColor(C.deep).fixedSize()
                        } else {
                            Text(t("Nearest to you", "Iliyo karibu nawe")).font(.tSmall).foregroundColor(C.ink)
                        }
                        Spacer(minLength: 4)
                        Icon(expanded ? "down" : "up", size: 16, color: C.muted)
                    }
                }.frame(minHeight: 40).contentShape(Rectangle())
            }.buttonStyle(.plain)
            if !(slim && !expanded) {
                if list.isEmpty {
                    if here != nil { Text(emptyText).font(.bMedium).foregroundColor(C.muted).padding(6) }
                    else {
                        HStack {
                            Text(t("See the projects closest to you.", "Ona miradi iliyo karibu nawe.")).font(.bMedium).foregroundColor(C.muted)
                            Spacer()
                            Btn(t("Allow location", "Ruhusu mahali"), icon: "target", small: true) { Locator.shared.start() }
                        }.padding(.top, 6)
                    }
                } else if expanded {
                    ScrollView { VStack(spacing: 8) { ForEach(list, id: \.0.id) { p, _ in ProjectRow(snap: snap, p: p, here: here) { open(p.id) } } }.padding(.top, 6) }
                        .frame(maxHeight: maxH)
                } else {
                    ProjectRow(snap: snap, p: list[0].0, here: here) { open(list[0].0.id) }.padding(.top, 6)
                }
            }
        }
        .padding(.horizontal, 12).padding(.top, 6).padding(.bottom, 10).background(mapGlass).clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).stroke(C.line, lineWidth: 1)).shadow(color: .black.opacity(0.14), radius: 8, y: 2)
        .gesture(DragGesture(minimumDistance: 20).onEnded { g in
            if g.translation.height < -30 { withAnimation { expanded = true } } else if g.translation.height > 30 { withAnimation { expanded = false } }
        })
    }
}

/// The card for a tapped pin: photo, how it is, the last visit and how far; swipe sideways for the projects nearest it.
private struct PreviewCard: View {
    @EnvironmentObject var m: AppModel
    let snap: Snapshot; let order: [Project]; let sel: String?; let here: LatLng?
    let step: (Int) -> Void; let close: () -> Void; let visit: (String) -> Void; let open: (String) -> Void; let directions: (Project) -> Void
    @State private var dx: CGFloat = 0
    var body: some View {
        let i = order.firstIndex { $0.id == sel } ?? -1
        if let p = i >= 0 ? order[i] : snap.projects.first(where: { $0.id == sel }) {
            let c = mapCondition(snap, p)
            VStack(alignment: .leading, spacing: 0) {
                Capsule().fill(C.edge).frame(width: 36, height: 4).frame(maxWidth: .infinity)
                HStack(spacing: 0) {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(order.count > 1 && i >= 0 ? t("\(i + 1) of \(order.count) nearby", "\(i + 1) kati ya \(order.count) karibu") : t("Project", "Mradi")).font(.lMedium).foregroundColor(C.ink)
                        if order.count > 1 { Text(t("Swipe for more", "Telezesha kuona zaidi")).font(.lSmall).foregroundColor(C.muted) }
                    }
                    Spacer()
                    cardIcon("back", t("Previous project nearby", "Mradi uliotangulia karibu"), i > 0) { step(-1) }
                    cardIcon("chev", t("Next project nearby", "Mradi unaofuata karibu"), i >= 0 && i < order.count - 1) { step(1) }
                    cardIcon("x", t("Close", "Funga"), true, close)
                }
                cardBody(p, c).id(p.id).transition(.asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity), removal: .opacity))
                HStack(spacing: 8) {
                    tile(t("Report a visit", "Ripoti ziara"), "clip", primary: true) { visit(p.id) }
                    tile(t("Directions", "Maelekezo"), "nav") { directions(p) }
                    tile(t("Open project", "Fungua mradi"), "chev") { open(p.id) }
                }.padding(.top, 9).padding(.trailing, 6)
            }
            .padding(.leading, 12).padding(.trailing, 6).padding(.top, 5).padding(.bottom, 10)
            .background(C.sun ? C.card : C.card.opacity(0.95)).clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).stroke(C.line, lineWidth: 1)).shadow(color: .black.opacity(0.18), radius: 10, y: 3)
            .offset(x: dx * 0.3)
            .gesture(DragGesture(minimumDistance: 15).onChanged { dx = $0.translation.width }.onEnded { g in
                if g.translation.width < -56 { step(1) } else if g.translation.width > 56 { step(-1) } else if g.translation.height > 60 { close() }
                withAnimation(.spring(response: 0.3)) { dx = 0 }
            })
        }
    }
    private func cardBody(_ p: Project, _ c: String) -> some View {
        let (ic, _) = typeLook(p)
        let (bg, fg): (Color, Color) = c == PinCond.bad ? (C.badBg, C.bad) : c == PinCond.repair ? (C.warnBg, C.warn) : c == PinCond.old ? (C.named("slate").bg, C.named("slate").fg) : (C.okBg, C.ok)
        return HStack(alignment: .top, spacing: 11) {
            NetImage(url: p.photo.map { m.api.photoUrl($0) }) { Icon(ic, size: 25, color: fg) }.frame(width: 62, height: 62).background(bg).clipShape(RoundedRectangle(cornerRadius: 15, style: .continuous))
            VStack(alignment: .leading, spacing: 0) {
                Text(p.title).font(.mont(15, .bold)).foregroundColor(C.ink).lineLimit(2)
                Text([p.area, p.country].filter { !$0.isEmpty }.joined(separator: " · ")).font(.bSmall).foregroundColor(C.muted).lineLimit(1)
                HStack(spacing: 6) { Circle().fill(Color(condUIColor(c))).frame(width: 9, height: 9); Text(condName(c)).font(.mont(12, .bold)).foregroundColor(fg) }
                    .padding(.leading, 8).padding(.trailing, 10).padding(.vertical, 4).background(bg).clipShape(Capsule()).padding(.top, 6)
                if let h = here, let g = p.gps { Text(t("\(Geo.dist(Geo.metres(h, g), miles: m.miles)) from you", "\(Geo.dist(Geo.metres(h, g), miles: m.miles)) kutoka kwako")).font(.mont(13, .semibold)).foregroundColor(C.deep).padding(.top, 6) }
                let v = snap.visits[p.id]
                Text(v == nil ? t("No visit in the app yet", "Bado hakuna ziara kwenye programu") : t("Last visit ", "Ziara ya mwisho ") + Dates.day(v!.at, year: true) + (v!.by.isEmpty ? "" : " · " + v!.by)).font(.bSmall).foregroundColor(C.text).lineLimit(2).padding(.top, 2)
            }
            Spacer(minLength: 0)
        }.padding(.top, 6).padding(.trailing, 6)
    }
    private func cardIcon(_ icon: String, _ label: String, _ on: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) { Icon(icon, size: 17, color: on ? C.ink : C.off).frame(width: 44, height: 44) }.buttonStyle(.plain).disabled(!on).accessibilityLabel(label)
    }
    private func tile(_ label: String, _ icon: String, primary: Bool = false, _ action: @escaping () -> Void) -> some View {
        let hu = C.hue("map")
        return Button { Haptic.tap(); action() } label: {
            VStack(spacing: 3) {
                Icon(icon, size: 18, color: primary ? .white : hu.fg)
                Text(label).font(.mont(12, .bold)).foregroundColor(primary ? .white : C.ink).multilineTextAlignment(.center).lineLimit(2).minimumScaleFactor(0.85)
            }.padding(.horizontal, 4).padding(.vertical, 6).frame(maxWidth: .infinity, minHeight: 48)
            .background(Group { if primary { OkBrush } else { hu.bg } }).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }.buttonStyle(Press())
    }
}

/// After a long press: add a new site there, or measure how far it is from you.
private struct SpotCard: View {
    @EnvironmentObject var m: AppModel
    let sp: LatLng; let here: LatLng?; let measuring: Bool; let close: () -> Void; let add: () -> Void; let measure: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                VStack(alignment: .leading, spacing: 1) {
                    Text(t("This spot", "Mahali hapa")).font(.tMedium).foregroundColor(C.ink)
                    Text(sp.text()).font(.bSmall).foregroundColor(C.muted).lineLimit(1)
                }
                Spacer()
                Button(action: close) { Icon("x", size: 17, color: C.ink).frame(width: 44, height: 44) }.buttonStyle(.plain).accessibilityLabel(t("Close", "Funga"))
            }
            if measuring {
                if let h = here {
                    HStack(spacing: 10) {
                        Icon("compass", size: 22, color: C.blue)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(Geo.dist(Geo.metres(h, sp), miles: m.miles)).font(.tLarge).foregroundColor(C.ink)
                            Text(t("from you, to the \(Geo.heading(h, sp)) (straight line)", "kutoka kwako, upande wa \(Geo.heading(h, sp)) (mstari mnyoofu)")).font(.bSmall).foregroundColor(C.muted)
                        }
                        Spacer(minLength: 0)
                    }.padding(.horizontal, 14).padding(.vertical, 10).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).padding(.top, 8).padding(.trailing, 8)
                } else {
                    Text(t("Allow location to measure from where you are.", "Ruhusu mahali ili upime kutoka ulipo.")).font(.bMedium).foregroundColor(C.warn).padding(.top, 8)
                }
            }
            HStack(spacing: 8) {
                Button { Haptic.tap(); add() } label: {
                    VStack(spacing: 4) { Icon("plus", size: 20, color: .white); Text(t("New site here", "Eneo jipya hapa")).font(.mont(12.5, .bold)).foregroundColor(.white).multilineTextAlignment(.center) }
                        .frame(maxWidth: .infinity, minHeight: 58).background(PurpleBrush).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                }.buttonStyle(Press())
                Button { Haptic.tap(); measure() } label: {
                    VStack(spacing: 4) { Icon("compass", size: 20, color: measuring ? .white : C.blue); Text(t("Measure from me", "Pima kutoka kwangu")).font(.mont(12.5, .bold)).foregroundColor(measuring ? .white : C.ink).multilineTextAlignment(.center) }
                        .frame(maxWidth: .infinity, minHeight: 58).background(measuring ? C.blue : C.tint).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                }.buttonStyle(Press()).accessibilityLabel(measuring ? t("Stop measuring", "Acha kupima") : t("Measure from me", "Pima kutoka kwangu"))
            }.padding(.top, 12).padding(.trailing, 8)
        }
        .padding(.leading, 16).padding(.trailing, 8).padding(.top, 8).padding(.bottom, 14).background(C.card).clipShape(RoundedRectangle(cornerRadius: 26, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 26, style: .continuous).stroke(C.line, lineWidth: 1)).shadow(color: .black.opacity(0.2), radius: 12, y: 4)
    }
}

// MARK: - the layers' cards and the map-style sheet

private struct MapCard<Content: View>: View {
    @ViewBuilder var content: () -> Content
    var body: some View {
        content().frame(maxWidth: 520).frame(maxWidth: .infinity).background(C.card).clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(C.line, lineWidth: 1)).shadow(color: Color(hex: 0x144678, alpha: 0.2), radius: 10, y: 3)
    }
}
private func caps(_ s: String, _ c: Color = C.muted) -> some View { Text(s.uppercased()).font(.mont(10, .heavy)).tracking(0.7).foregroundColor(c) }
private func closeX(_ label: String, _ action: @escaping () -> Void) -> some View {
    Button(action: action) { Icon("x", size: 18, color: C.ink).frame(width: 44, height: 44).background(C.tint).clipShape(Circle()) }.buttonStyle(.plain).accessibilityLabel(label)
}

private struct LayerCards: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject private var L = HomeLayers.s
    let snap: Snapshot; let stops: [Project]; let nextIdx: Int?; let here: LatLng?; let visit: (String) -> Void; let open: (String) -> Void
    @State private var dir: Project?
    var body: some View {
        let yearsOn = L.years && snap.admin
        VStack(spacing: 8) {
            if let k = L.pick, k.hasPrefix("s:"), let i = stops.firstIndex(where: { k.hasPrefix("s:\($0.id)|") || k == "s:\($0.id)" }) { stopCard(i) }
            if let k = L.pick, k.hasPrefix("t:"), let p = L.tm?.people.first(where: { k.hasPrefix("t:\($0.name)|") }) { mateCard(p) }
            if L.follow { nextCard() }
            if snap.admin && !L.heat.isEmpty { heatLegend(yearsOn ? L.year : nil) }
            if yearsOn, let span = yearSpan(snap) { YearsCard(snap: snap, span: span) }
            if L.team && snap.admin, let e = L.tmErr { MapCard { Text(e).font(.bSmall).foregroundColor(C.ink).padding(14).frame(maxWidth: .infinity, alignment: .leading) } }
        }
        .sheet(item: $dir) { p in if let g = p.gps { DirectionsSheet(title: p.title, at: g) } }
        .task(id: L.team && snap.admin) {
            guard L.team && snap.admin else { return }
            while !Task.isCancelled && L.team {
                let r = await m.api.adminGet("teammap")
                if r.ok { L.tm = TeamMapData.from(r.json); L.tmErr = nil }
                else { L.tmErr = r.status == 0 ? t("No signal: the team’s places need a connection.", "Hakuna mtandao: mahali pa timu panahitaji mtandao.") : reasonText(r.reason) }
                try? await Task.sleep(nanoseconds: 180_000_000_000)
            }
        }
        .task(id: L.playing) {
            guard L.playing, let sp = yearSpan(snap) else { return }
            if L.year >= sp.upperBound { L.year = sp.lowerBound }
            while L.playing && L.year < sp.upperBound {
                try? await Task.sleep(nanoseconds: 1_300_000_000)
                if Task.isCancelled { return }
                if L.playing { withAnimation { L.year += 1 } }
            }
            L.playing = false
        }
    }
    private func nextCard() -> some View {
        let near = stops.isEmpty ? here.flatMap { h in snap.projects.filter { $0.gps != nil }.min { Geo.metres(h, $0.gps!) < Geo.metres(h, $1.gps!) } } : nil
        let p = nextIdx.map { stops[$0] } ?? near
        let label = nextIdx != nil ? t("Next stop · \(nextIdx! + 1)/\(stops.count)", "Kituo \(nextIdx! + 1)/\(stops.count)") : !stops.isEmpty ? t("Today’s route is done", "Njia ya leo imekamilika") : near != nil ? t("Closest site", "Eneo la karibu zaidi") : t("Following you", "Inakufuata")
        return MapCard {
            HStack(spacing: 10) {
                Icon("nav", size: 22, color: .white).frame(width: 40, height: 40).background(Color(hex: 0x7A4FD0)).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                VStack(alignment: .leading, spacing: 1) {
                    caps(label)
                    Text(p?.title ?? (stops.isEmpty ? t("Finding where you are…", "Inatafuta mahali ulipo…") : t("All visits sent ✓", "Ziara zote zimetumwa ✓"))).font(.tSmall).foregroundColor(C.ink).lineLimit(2)
                }
                Spacer(minLength: 4)
                if let p, let h = here { Text("≈ " + Geo.dist(Geo.metres(h, p.gps!), miles: m.miles)).font(.mont(16, .heavy)).foregroundColor(C.ink).fixedSize() }
                closeX(t("Stop following me", "Acha kunifuata")) { L.follow = false }
            }.padding(.leading, 12).padding(.trailing, 8).padding(.vertical, 8)
        }
    }
    private func stopCard(_ i: Int) -> some View {
        let p = stops[i], done = visitedToday(snap, p.id)
        return MapCard {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 1) {
                        caps(t("Today’s route · stop \(i + 1) of \(stops.count)", "Njia ya leo · kituo \(i + 1) kati ya \(stops.count)"), Color(hex: 0x7A4FD0))
                        Text(p.title).font(.tMedium).foregroundColor(C.ink).lineLimit(2)
                        Text(p.sub + (here.map { " · " + Geo.dist(Geo.metres($0, p.gps!), miles: m.miles) + t(" from you", " kutoka ulipo") } ?? "")).font(.bSmall).foregroundColor(C.muted).lineLimit(2)
                    }
                    Spacer()
                    closeX(t("Close", "Funga")) { L.pick = nil }
                }
                HStack(spacing: 6) {
                    if done { Icon("check", size: 16, color: GreenC) }
                    Text(done ? t("Visited today", "Imetembelewa leo") : t("Not visited yet", "Bado haijatembelewa")).font(.lMedium).foregroundColor(done ? GreenC : C.muted)
                }.padding(.top, 6)
                HStack(spacing: 8) {
                    Btn(done ? t("Open", "Fungua") : t("Report a visit", "Ripoti ziara"), small: true, wide: true) { L.pick = nil; done ? open(p.id) : visit(p.id) }
                    Btn(t("Directions", "Maelekezo"), icon: "nav", light: true, small: true, wide: true) { dir = p }
                }.padding(.top, 10).padding(.trailing, 6)
            }.padding(.leading, 14).padding(.trailing, 8).padding(.top, 8).padding(.bottom, 12)
        }
    }
    private func mateCard(_ p: Sharer) -> some View {
        MapCard {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 10) {
                    Text(initialsOf(p.name)).font(.mont(13, .heavy)).foregroundColor(.white).frame(width: 40, height: 40).background(Circle().fill(Color(hex: 0x0A78C2)))
                        .onTapGesture { Contact.show(p.name) }
                    VStack(alignment: .leading, spacing: 1) {
                        Text(p.name).font(.tSmall).foregroundColor(C.ink)
                        Text(t("Last seen ", "Alionekana ") + agoText(p.at) + (p.acc > 0 ? " · " + Geo.acc(p.acc) : "")).font(.bSmall).foregroundColor(C.muted)
                    }
                    Spacer()
                    closeX(t("Close", "Funga")) { L.pick = nil }
                }
                if !p.phone.isEmpty {
                    HStack(spacing: 8) {
                        Btn(t("Call", "Piga simu"), icon: "phone", small: true, wide: true) { Links.call(p.phone) }
                        Btn("WhatsApp", icon: "b:whatsapp", light: true, small: true, wide: true) { Links.whatsapp(p.phone, p.home) }
                    }.padding(.top, 10).padding(.trailing, 6)
                } else { Text(t("No phone number saved for them.", "Hakuna namba ya simu iliyohifadhiwa.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 8) }
            }.padding(.leading, 14).padding(.trailing, 8).padding(.top, 8).padding(.bottom, 12)
        }
    }
    private func heatLegend(_ year: Int?) -> some View {
        let kind = L.heat
        let pts = heatPoints(snap, kind, year: year), tot = pts.reduce(0) { $0 + $1.1 }
        let line = kind == "repairs" ? (pts.count == 1 ? t("1 open repair", "Matengenezo 1 yaliyo wazi") : t("\(pts.count) open repairs", "Matengenezo \(pts.count) yaliyo wazi")) + " · " + t("not working counts double", "isiyofanya kazi inahesabiwa mara mbili")
            : !pts.isEmpty ? t("\(Geo.grouped(Int(tot))) people served at \(pts.count) sites", "Watu \(Geo.grouped(Int(tot))) wanahudumiwa katika maeneo \(pts.count)")
            : t("No people counts yet. They come from the board and site visits.", "Bado hakuna idadi ya watu. Zinatoka kwenye bodi na ziara.")
        let cs = (HEAT_COLORS[kind] ?? []).map { Color(hex: $0) }
        return MapCard {
            HStack(spacing: 6) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(kind == "repairs" ? t("Open repairs", "Matengenezo yaliyo wazi") : t("People served", "Watu wanaohudumiwa")).font(.tSmall).foregroundColor(C.ink)
                    HStack(spacing: 8) {
                        Text(t("Fewer", "Chache")).font(.lSmall).foregroundColor(C.muted)
                        Capsule().fill(LinearGradient(colors: cs, startPoint: .leading, endPoint: .trailing)).frame(height: 10)
                        Text(t("More", "Zaidi")).font(.lSmall).foregroundColor(C.muted)
                    }
                    Text(line).font(.bSmall).foregroundColor(C.muted)
                }
                closeX(t("Hide the heat map", "Ficha ramani ya joto")) { L.heat = "" }
            }.padding(.leading, 14).padding(.trailing, 8).padding(.vertical, 8)
        }
    }
}

private struct YearsCard: View {
    @ObservedObject private var L = HomeLayers.s
    let snap: Snapshot; let span: ClosedRange<Int>
    var body: some View {
        let y = min(max(L.year, span.lowerBound), span.upperBound)
        let ps = doneBy(snap, y), ppl = ps.reduce(0) { $0 + ($1.people ?? 0) }
        MapCard {
            VStack(spacing: 0) {
                HStack(spacing: 12) {
                    Button { Haptic.tap(); L.playing.toggle() } label: {
                        Icon(L.playing ? "pause" : "play", size: 20, color: .white).frame(width: 48, height: 48).background(Circle().fill(Color(hex: 0x0A78C2)))
                    }.buttonStyle(.plain).accessibilityLabel(L.playing ? t("Pause", "Simamisha") : t("Play through the years", "Cheza kupitia miaka"))
                    VStack(alignment: .leading, spacing: 1) {
                        Text(t("By \(String(y))", "Hadi \(String(y))")).font(.mont(22, .heavy)).foregroundColor(C.ink)
                        Text((ps.count == 1 ? t("1 project completed", "Mradi 1 umekamilika") : t("\(ps.count) projects completed", "Miradi \(ps.count) imekamilika")) + (ppl > 0 ? " · " + t("\(Geo.grouped(ppl)) people", "watu \(Geo.grouped(ppl))") : "")).font(.bSmall).foregroundColor(C.muted)
                    }
                    Spacer()
                    closeX(t("Close the time slider", "Funga kitelezi cha miaka")) { L.playing = false; L.years = false }
                }
                if span.upperBound > span.lowerBound {
                    Slider(value: Binding(get: { Double(y) }, set: { L.playing = false; L.year = Int($0.rounded()) }), in: Double(span.lowerBound)...Double(span.upperBound), step: 1).tint(Color(hex: 0x0A78C2)).padding(.top, 6)
                    HStack { Text(String(span.lowerBound)).font(.lSmall).foregroundColor(C.muted); Spacer(); Text(String(span.upperBound)).font(.lSmall).foregroundColor(C.muted) }
                }
            }.padding(.leading, 12).padding(.trailing, 8).padding(.top, 10).padding(.bottom, 8)
        }
    }
}

/// Map style: the street map or satellite photos; what else is on the map (today's route; heat, years and team for the office).
private struct MapStyleSheet: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject private var L = HomeLayers.s
    let snap: Snapshot; let stops: [Project]
    var body: some View {
        let done = stops.filter { visitedToday(snap, $0.id) }.count
        SubSheet { _ in
            SubHead(icon: "layers", hue: "blue", title: t("Map style", "Mtindo wa ramani"))
            VStack(spacing: 0) {
                styleRow("street", t("Street map", "Ramani ya barabara"), "map", !L.sat) { L.sat = false }
                styleRow("sat", t("Satellite photos", "Picha za satelaiti"), "satellite", L.sat) { L.sat = true }
            }.padding(.top, 12)
            caps(t("On the map", "Kwenye ramani")).padding(.top, 16).padding(.leading, 6).frame(maxWidth: .infinity, alignment: .leading)
            SwitchRow(icon: "cal", title: t("Today’s route", "Njia ya leo"),
                      sub: stops.isEmpty ? t("No visits for you today", "Huna ziara leo") : t("\(stops.count) \(stops.count == 1 ? "visit" : "visits") for you today · \(done) done", "Ziara \(stops.count) zako leo · \(done) zimekamilika"), on: L.route) { L.route.toggle() }
            if snap.admin {
                caps(t("For the office", "Kwa ofisi")).padding(.top, 14).padding(.leading, 6).frame(maxWidth: .infinity, alignment: .leading)
                HStack(spacing: 12) {
                    IconTile(icon: "flame", tint: C.blue, bg: C.tint, size: 38)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(t("Heat map", "Ramani ya joto")).font(.tSmall).foregroundColor(C.ink)
                        Text(t("Shade the areas by…", "Paka rangi maeneo kwa…")).font(.bSmall).foregroundColor(C.muted)
                    }
                    Spacer()
                }.padding(.horizontal, 14).frame(minHeight: 52)
                HStack(spacing: 4) {
                    ForEach([("", t("Off", "Zima")), ("repairs", t("Repairs", "Matengenezo")), ("people", t("People served", "Watu"))], id: \.0) { k, label in
                        let on = L.heat == k
                        Button { Haptic.tap(); L.heat = k } label: {
                            Text(label).font(.mont(13, .bold)).foregroundColor(on ? .white : C.ink).multilineTextAlignment(.center).frame(maxWidth: .infinity, minHeight: 44)
                                .background(on ? C.blue : .clear).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        }.buttonStyle(.plain)
                    }
                }.padding(4).overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(C.line, lineWidth: 1)).padding(.leading, 64).padding(.trailing, 14).padding(.bottom, 6)
                SwitchRow(icon: "clock", title: t("Projects by year", "Miradi kwa mwaka"), sub: t("A time slider with a play button, for presentations", "Kitelezi cha miaka chenye kitufe cha kucheza, kwa mawasilisho"), on: L.years) {
                    let on = !L.years
                    if on && yearSpan(snap) == nil { m.say(t("No completed projects with a year to show.", "Hakuna miradi iliyokamilika yenye mwaka wa kuonyesha.")); return }
                    L.playing = false; L.years = on; if let sp = yearSpan(snap) { L.year = sp.upperBound }
                }
                SwitchRow(icon: "people", title: t("Team live", "Timu sasa hivi"), sub: t("People sharing their location · updates every 3 min", "Wanaoshiriki mahali · inasasishwa kila dakika 3"), on: L.team) {
                    L.team.toggle(); if !L.team { L.tm = nil; L.tmErr = nil; if L.pick?.hasPrefix("t:") == true { L.pick = nil } }
                }
            }
        }
        .presentationDetents(snap.admin ? [.large] : [.medium])
    }
    private func styleRow(_ k: String, _ label: String, _ icon: String, _ on: Bool, _ action: @escaping () -> Void) -> some View {
        Button { Haptic.tap(); action() } label: {
            HStack(spacing: 12) {
                IconTile(icon: icon, tint: C.blue, bg: C.tint, size: 38)
                Text(label).font(.tSmall).foregroundColor(C.ink)
                Spacer()
                if on { Icon("check", size: 20, color: C.blue) }
            }.padding(.horizontal, 14).frame(minHeight: 52).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }
}

private struct SwitchRow: View {
    let icon: String; let title: String; let sub: String; let on: Bool; let toggle: () -> Void
    var body: some View {
        Button { Haptic.tap(); toggle() } label: {
            HStack(spacing: 12) {
                IconTile(icon: icon, tint: C.blue, bg: C.tint, size: 38)
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).font(.tSmall).foregroundColor(C.ink)
                    Text(sub).font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.leading)
                }
                Spacer(minLength: 10)
                ZStack(alignment: on ? .trailing : .leading) {
                    Capsule().fill(on ? GreenC : Color(hex: 0xC9D1D8)).frame(width: 50, height: 30)
                    Circle().fill(.white).frame(width: 24, height: 24).padding(3).shadow(color: .black.opacity(0.15), radius: 1)
                }.animation(.easeOut(duration: 0.15), value: on)
            }.padding(.horizontal, 14).padding(.vertical, 6).frame(minHeight: 58).contentShape(Rectangle())
        }.buttonStyle(.plain).accessibilityValue(on ? t("on", "imewashwa") : t("off", "imezimwa"))
    }
}

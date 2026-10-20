import SwiftUI

@main
struct RafikiPinApp: App {
    @StateObject private var model: AppModel
    @Environment(\.scenePhase) private var phase

    init() {
        Fonts.register()
        _model = StateObject(wrappedValue: AppModel())
    }

    var body: some Scene {
        WindowGroup {
            Root().environmentObject(model)
                .onChange(of: phase) { p in
                    // GPS only while the app is open; refresh when coming back to it
                    if p == .active, model.signedIn { model.cameBack(); model.locator.start(); if !model.demo { model.refresh() } }
                    if p == .background { model.wentAway(); model.locator.stop() }
                }
        }
    }
}

struct Root: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.colorScheme) var scheme
    var body: some View {
        let _ = (Look.systemDark = scheme == .dark)
        ZStack {
            AppBackground()
            if !m.signedIn { LockView() } else if m.bioLocked { BioLockView() } else { Shell() }
        }
        .id("\(m.look)-\(scheme == .dark)")
        .preferredColorScheme(Look.mode == "dark" ? .dark : (Look.mode == "light" || Look.mode == "sun") ? .light : nil)
        .onChange(of: m.signedIn) { on in if on { m.locator.start() } }
        .onAppear { if m.signedIn { m.locator.start() }; Demo.route(m) }
    }
}

struct Shell: View {
    @EnvironmentObject var m: AppModel
    @State private var depth = 1
    var body: some View {
        let here = m.here
        VStack(spacing: 0) {
            TopBar()
            ZStack {
                Group {
                    if let pid = here.project { ProjectView(id: pid, startTab: here.projectTab) }
                    else if here.sub == "search" { SearchView() }
                    else if here.sub == "report" { ReportView() }
                    else if here.sub == "route" { RouteView() }
                    else if here.tab == "map" { MapTab() }
                    else if here.tab == "new" { NewSiteView() }
                    else if here.tab == "me" { MeView() }
                    else if here.tab == "office" { OfficeView() }
                    else { ProjectsView() }
                }
                .id("\(here.tab)|\(here.project ?? "")|\(here.sub ?? "")|\(m.stack.count)")
                .transition(m.stack.count >= depth
                    ? .asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity), removal: .opacity)
                    : .asymmetric(insertion: .opacity, removal: .move(edge: .trailing).combined(with: .opacity)))
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .animation(Motion.on ? .spring(response: 0.36, dampingFraction: 0.9) : nil, value: m.stack)
            .onChange(of: m.stack.count) { n in depth = n }
            BottomTabs()
        }
        .gesture(DragGesture(minimumDistance: 30).onEnded { g in
            // swipe from the left edge to go back, like other iPhone apps
            if g.startLocation.x < 30 && g.translation.width > 80 { m.back() }
        })
    }
}

struct TopBar: View {
    @EnvironmentObject var m: AppModel
    var body: some View {
        HStack(spacing: 0) {
            if m.stack.count > 1 {
                Button { Haptic.tap(); m.back() } label: { Icon("back", size: 22, color: C.ink).frame(width: 44, height: 44).background(C.card.opacity(0.85)).clipShape(Circle()) }
                    .accessibilityLabel(t("Back", "Rudi"))
            } else {
                Button { m.tab("me") } label: { Avatar(name: m.prefs.name, size: 40) }.accessibilityLabel(t("Me", "Mimi")).accessibilityIdentifier("hav")
            }
            Spacer()
            HStack(spacing: 0) { Text("RAFIKI").font(.tMedium).tracking(2.5).foregroundColor(C.ink); Text(" Pin").font(.tMedium).foregroundColor(C.blue) }
            Spacer()
            Button { Haptic.tap(); m.refresh() } label: {
                Icon("refresh", size: 22, color: C.ink).rotationEffect(.degrees(m.busy ? 360 : 0)).animation(m.busy ? .linear(duration: 0.8).repeatForever(autoreverses: false) : .default, value: m.busy)
                    .frame(width: 44, height: 44).background(C.card.opacity(0.85)).clipShape(Circle())
            }.accessibilityLabel(t("Refresh", "Onyesha upya"))
        }
        .padding(.horizontal, 12).frame(minHeight: 58)
        .background(.ultraThinMaterial)
    }
}

struct BottomTabs: View {
    @EnvironmentObject var m: AppModel
    var body: some View {
        let admin = m.snap?.admin == true
        let review = m.snap?.reviewOpen ?? 0
        var tabs = [("map", t("Map", "Ramani"), "map"), ("list", t("Projects", "Miradi"), "list"), ("new", t("New site", "Eneo jipya"), "plus"), ("me", t("Me", "Mimi"), "person")]
        if admin { tabs.append(("office", t("Office", "Ofisi"), "inbox")) }
        return HStack(spacing: 0) {
            ForEach(tabs, id: \.0) { k, label, icon in
                let on = m.here.tab == k && m.here.project == nil && m.here.sub == nil
                let hu = C.hue(k)
                Button { Haptic.tap(); m.tab(k) } label: {
                    VStack(spacing: 3) {
                        ZStack(alignment: .topTrailing) {
                            if k == "new" {
                                Icon(icon, size: 20, color: .white).frame(width: 48, height: 34).background(PurpleBrush).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                                    .shadow(color: Color(hex: 0x6E46C8, alpha: 0.35), radius: 6, y: 3)
                            } else {
                                Icon(icon, size: 24, color: on ? hu.fg : C.muted).frame(width: 52, height: 30).background(on ? hu.bg : .clear).clipShape(Capsule())
                            }
                            let n = k == "office" ? review : k == "me" ? m.waiting : 0
                            if n > 0 { Text("\(n)").font(.mont(10, .bold)).foregroundColor(.white).padding(.horizontal, 5).background(C.warn).clipShape(Capsule()) }
                        }
                        Text(label).font(.lSmall).foregroundColor(on ? hu.fg : C.muted).lineLimit(1).minimumScaleFactor(0.8)
                    }.frame(maxWidth: .infinity).padding(.vertical, 6).contentShape(Rectangle())
                }.buttonStyle(.plain).accessibilityIdentifier("nav_\(k)")
            }
        }
        .frame(height: 68)
        .background(.ultraThinMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 26, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 26, style: .continuous).stroke(C.line.opacity(0.7), lineWidth: 1))
        .shadow(color: .black.opacity(C.dark ? 0 : 0.08), radius: 14, y: 4)
        .padding(.horizontal, 10).padding(.bottom, 4)
    }
}

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
                    if p == .active, model.signedIn { model.locator.start(); if !model.demo { model.refresh() } }
                    if p == .background { model.locator.stop() }
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
            C.bg.ignoresSafeArea()
            if m.signedIn { Shell() } else { LockView() }
        }
        .id("\(m.look)-\(scheme == .dark)")
        .preferredColorScheme(Look.mode == "dark" ? .dark : (Look.mode == "light" || Look.mode == "sun") ? .light : nil)
        .onChange(of: m.signedIn) { on in if on { m.locator.start() } }
        .onAppear { if m.signedIn { m.locator.start() }; Demo.route(m) }
    }
}

struct Shell: View {
    @EnvironmentObject var m: AppModel
    var body: some View {
        VStack(spacing: 0) {
            TopBar()
            ZStack {
                let here = m.here
                if let pid = here.project { ProjectView(id: pid, startTab: here.projectTab) }
                else if here.sub == "report" { ReportView() }
                else if here.sub == "route" { RouteView() }
                else if here.tab == "map" { MapTab() }
                else if here.tab == "new" { NewSiteView() }
                else if here.tab == "me" { MeView() }
                else if here.tab == "office" { OfficeView() }
                else { ProjectsView() }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
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
            Image(systemName: "drop.fill").font(.system(size: 15)).foregroundColor(.white).frame(width: 30, height: 30).background(DeepBrush).clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            Text("RAFIKI").font(.tMedium).tracking(1.5).foregroundColor(C.ink).padding(.leading, 10)
            Text(" Pin").font(.tMedium).foregroundColor(C.blue)
            Spacer()
            Text(m.prefs.name).font(.lMedium).foregroundColor(C.muted).lineLimit(1).frame(maxWidth: 140, alignment: .trailing)
            Button { m.refresh() } label: {
                Icon("refresh", size: 22, color: C.ink).rotationEffect(.degrees(m.busy ? 180 : 0)).animation(.easeInOut(duration: 0.6), value: m.busy).frame(width: 44, height: 44)
            }.accessibilityLabel(t("Refresh", "Onyesha upya"))
        }
        .padding(.horizontal, 16).frame(minHeight: 56).background(C.card)
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
                Button { m.tab(k) } label: {
                    VStack(spacing: 3) {
                        ZStack(alignment: .topTrailing) {
                            if k == "new" {
                                Icon(icon, size: 20, color: .white).frame(width: 48, height: 34).background(BlueBrush).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                            } else {
                                Icon(icon, size: 24, color: on ? C.blue : C.muted).frame(width: 52, height: 30).background(on ? C.tint : .clear).clipShape(Capsule())
                            }
                            let n = k == "office" ? review : k == "me" ? m.waiting : 0
                            if n > 0 { Text("\(n)").font(.mont(10, .bold)).foregroundColor(.white).padding(.horizontal, 5).background(C.warn).clipShape(Capsule()) }
                        }
                        Text(label).font(.lSmall).foregroundColor(on ? C.blue : C.muted).lineLimit(1).minimumScaleFactor(0.8)
                    }.frame(maxWidth: .infinity).padding(.vertical, 6).contentShape(Rectangle())
                }.buttonStyle(.plain).accessibilityIdentifier("nav_\(k)")
            }
        }
        .frame(height: 68).background(C.card.ignoresSafeArea(edges: .bottom))
        .overlay(alignment: .top) { Rectangle().fill(C.line).frame(height: 1) }
    }
}

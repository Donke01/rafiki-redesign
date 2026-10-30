import SwiftUI

// App updates on Me (Android 2.12.7, without the installer: on iPhone new versions come from TestFlight or the App Store).

/// Me, near the top, only when a newer iPhone app is out: what's new, and a button to TestFlight or the App Store.
struct MeUpdateCard: View {
    @ObservedObject private var up = MeUpdates.shared
    @State private var glow = false
    var body: some View {
        if up.ready, let u = up.latest {
            CardV(border: C.blue) {
                HStack(spacing: 12) {
                    IconTile(icon: "upload", tint: C.hue("pin").fg, bg: C.hue("pin").bg, size: 40)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(t("Update available", "Toleo jipya") + verTail(u.name)).font(.tSmall).foregroundColor(C.ink)
                        Text((u.notes.isEmpty ? t("A newer RAFIKI Pin is ready.", "RAFIKI Pin mpya iko tayari.") : u.notes) + t(" You have \(AppVer.name).", " Unayo \(AppVer.name)."))
                            .font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }
                Btn(t("Get the update", "Pata toleo jipya"), icon: "upload", wide: true) { up.openStore() }.padding(.top, 12)
                Text(t("Opens TestFlight (or the App Store). Your PIN and saved work stay.", "Inafungua TestFlight (au App Store). PIN yako na kazi iliyohifadhiwa vinabaki."))
                    .font(.lSmall).foregroundColor(C.muted).padding(.top, 8).fixedSize(horizontal: false, vertical: true)
            }
            .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).stroke(Color(hex: 0x47B4F5), lineWidth: glow ? 3 : 0))
            .padding(.top, 14)
            .id("app_update")
            .onAppear { flash() }
            .onChange(of: up.focus) { f in if f { flash() } }
        }
    }
    private func flash() {
        guard up.focus else { return }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 300_000_000)
            withAnimation(.easeOut(duration: Motion.on ? 0.45 : 0)) { glow = true }
            try? await Task.sleep(nanoseconds: 1_400_000_000)
            withAnimation(.easeOut(duration: Motion.on ? 0.45 : 0)) { glow = false }
            up.focus = false
        }
    }
}

/// Me, at the bottom: App updates · up to date · when it was checked · Check.
struct MeUpdateRow: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject private var up = MeUpdates.shared
    @State private var busy = false
    @State private var failed = false
    @State private var tick = 0
    var body: some View {
        let ready = up.ready
        let _ = tick
        CardV(pad: 16) {
            HStack(spacing: 12) {
                IconTile(icon: ready ? "upload" : "check", tint: C.hue(ready ? "pin" : "near").fg, bg: C.hue(ready ? "pin" : "near").bg, size: 36)
                VStack(alignment: .leading, spacing: 1) {
                    Text(t("App updates", "Masasisho ya programu")).font(.tSmall).foregroundColor(C.ink)
                    Text(busy ? t("Checking…", "Inaangalia…") : failed ? t("Couldn’t check. Needs a signal.", "Haikuweza kuangalia. Inahitaji mtandao.")
                         : ready ? t("Update available", "Toleo jipya") + verTail(up.latest?.name ?? "")
                         : t("You’re up to date · \(AppVer.name) ✓", "Uko na toleo jipya · \(AppVer.name) ✓"))
                        .font(.bSmall).foregroundColor(failed ? C.bad : ready ? C.blue : C.ok).fixedSize(horizontal: false, vertical: true)
                    Text(checkedText(up.latest?.at ?? 0)).font(.lSmall).foregroundColor(C.muted)
                }.frame(maxWidth: .infinity, alignment: .leading)
                Button {
                    busy = true; failed = false
                    Task { let ok = await up.check(m.api); failed = !ok; busy = false; if up.ready { up.focus = true } }
                } label: {
                    HStack(spacing: 6) {
                        if busy { ProgressView().tint(C.blue).frame(width: 14, height: 14) } else { Icon("refresh", size: 15, color: C.blue) }
                        Text(t("Check", "Angalia")).font(.mont(12.5, .bold)).foregroundColor(C.blue).lineLimit(1).fixedSize()
                    }.padding(.horizontal, 12).padding(.vertical, 9).frame(minHeight: 44).background(Capsule().fill(C.tint))
                }.buttonStyle(.plain).disabled(busy)
            }
        }
        .padding(.top, 14)
        .task { while !Task.isCancelled { try? await Task.sleep(nanoseconds: 30_000_000_000); tick += 1 } } // keeps "Checked … ago" fresh
    }
}

/// Pops up over the app when a newer iPhone app is out: Get the update, Remind me later (tomorrow), or ✕ (until the
/// app is opened again).
struct MeUpdatePrompt: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject private var up = MeUpdates.shared
    @State private var show = false
    var body: some View {
        ZStack {
            if show, let u = up.latest {
                Color.black.opacity(0.4).ignoresSafeArea().onTapGesture { close() }
                card(u).padding(24).transition(.scale(scale: 0.94).combined(with: .opacity))
            }
        }
        .animation(Motion.on ? .easeOut(duration: 0.2) : nil, value: show)
        .task(id: up.latest?.build ?? 0) {
            show = false
            guard let u = up.latest, up.ready, u.build > up.promptClosedBuild else { return }
            if Date().timeIntervalSince1970 < m.prefs.updLater && m.prefs.updLaterBuild >= u.build { return }
            if m.demo && Demo.arg("-screen") != "update" { return }
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            show = true
        }
    }
    private func close() { show = false; up.promptClosedBuild = up.latest?.build ?? 0 }
    private func card(_ u: MeUpdate) -> some View {
        VStack(spacing: 0) {
            ZStack(alignment: .topTrailing) {
                VStack(alignment: .leading, spacing: 0) {
                    Icon("upload", size: 26, color: .white).frame(width: 52, height: 52).background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.white.opacity(0.16)))
                    Text(t("A new RAFIKI Pin is ready", "RAFIKI Pin mpya iko tayari")).font(.tLarge).foregroundColor(.white).padding(.top, 14).fixedSize(horizontal: false, vertical: true)
                    Text(u.name.isEmpty ? t("You have \(AppVer.name)", "Unayo \(AppVer.name)") : t("Version \(u.name) · you have \(AppVer.name)", "Toleo \(u.name) · unayo \(AppVer.name)"))
                        .font(.mont(13, .semibold)).foregroundColor(Color(hex: 0xCFE8FA)).padding(.top, 4)
                }.frame(maxWidth: .infinity, alignment: .leading).padding(20)
                Button { close() } label: { Icon("x", size: 18, color: .white).frame(width: 36, height: 36).background(Circle().fill(Color.white.opacity(0.16))).frame(width: 44, height: 44) }
                    .buttonStyle(.plain).accessibilityLabel(t("Close", "Funga")).padding(8)
            }
            .background(DeepBrush)
            VStack(alignment: .leading, spacing: 0) {
                Text(t("What’s new", "Kipya")).font(.lMedium).foregroundColor(C.muted)
                Text(u.notes.isEmpty ? t("Fixes and improvements.", "Marekebisho na maboresho.") : u.notes).font(.bMedium).foregroundColor(C.ink).padding(.top, 4).fixedSize(horizontal: false, vertical: true)
                Text(t("It comes through TestFlight (or the App Store). Your PIN and saved work stay.", "Linakuja kupitia TestFlight (au App Store). PIN yako na kazi iliyohifadhiwa vinabaki."))
                    .font(.lSmall).foregroundColor(C.muted).padding(.top, 10).fixedSize(horizontal: false, vertical: true)
                Btn(t("Get the update", "Pata toleo jipya"), icon: "upload", wide: true) { close(); up.openStore() }.padding(.top, 16)
                Btn(t("Remind me later", "Nikumbushe baadaye"), light: true, wide: true) {
                    close(); m.prefs.updLater = Date().timeIntervalSince1970 + 24 * 3600; m.prefs.updLaterBuild = u.build
                }.padding(.top, 10)
            }.padding(20)
        }
        .frame(maxWidth: 420)
        .background(C.card)
        .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
        .accessibilityIdentifier("update_prompt")
    }
}

import LocalAuthentication
import SwiftUI
import UIKit

/// Face ID / Touch ID, falling back to the phone passcode.
enum Bio {
    static var available: Bool { var e: NSError?; return LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: &e) }
    static var face: Bool { let c = LAContext(); var e: NSError?; _ = c.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &e); return c.biometryType != .touchID }
    static var demoOk: Bool? = nil
    static func ask(_ why: String) async -> Bool {
        if let d = demoOk { return d }
        return await withCheckedContinuation { k in LAContext().evaluatePolicy(.deviceOwnerAuthentication, localizedReason: why) { ok, _ in k.resume(returning: ok) } }
    }
}

/// The Figma lock screen: emblem with rings, "Habari, name", who chip, Unlock, or the RAFIKI PIN keypad.
struct BioLockView: View {
    @EnvironmentObject var m: AppModel
    @State private var usePin = false
    @State private var pin = ""
    @State private var msg: String?
    @State private var asked = false

    func ask() { Task { if await Bio.ask(t("Unlock RAFIKI Pin", "Fungua RAFIKI Pin")) { Haptic.ok(); m.unlocked() } else { msg = t("Not unlocked. Try again.", "Haijafunguliwa. Jaribu tena.") } } }

    var body: some View {
        let first = m.prefs.name.split(separator: " ").first.map(String.init) ?? ""
        ScrollView {
            VStack(spacing: 0) {
                (Text("RAFIKI ").foregroundColor(C.ink) + Text("Pin").foregroundColor(C.blue)).font(.mont(15, .heavy)).tracking(1.5).padding(.top, 8)
                if !usePin {
                    Emblem(face: Bio.face).padding(.top, 34).onTapGesture { ask() }
                    Text(t("WELCOME BACK", "KARIBU TENA")).font(.lSmall).tracking(2).foregroundColor(C.blue).padding(.top, 18)
                    Text(first.isEmpty ? t("Welcome back", "Karibu tena") : "Habari, \(first)").font(.hMedium).foregroundColor(C.ink).padding(.top, 6)
                    Text(Bio.face ? t("Look at your phone to carry on where you left off.", "Tazama simu yako ili kuendelea ulipoishia.") : t("Touch the sensor to carry on where you left off.", "Gusa kitambuzi ili kuendelea ulipoishia."))
                        .font(.bMedium).foregroundColor(C.muted).multilineTextAlignment(.center).frame(maxWidth: 310).padding(.top, 6)
                    if !m.prefs.name.isEmpty {
                        HStack(spacing: 8) {
                            Avatar(name: m.prefs.name, size: 26)
                            Text(m.prefs.name + (countryName(m.prefs.country).map { " · " + $0 } ?? "")).font(.lMedium).foregroundColor(C.ink)
                        }.padding(.leading, 4).padding(.trailing, 14).padding(.vertical, 4).background(C.card).clipShape(Capsule()).overlay(Capsule().stroke(C.line)).padding(.top, 16)
                    }
                    Msg(msg, "bad")
                    Btn(t("Unlock", "Fungua"), icon: Bio.face ? "face" : "finger", wide: true) { ask() }.padding(.top, 26)
                    HStack(spacing: 6) { Icon("lock", size: 13, color: C.muted); Text(Bio.face ? t("Your face never leaves this phone.", "Uso wako hautoki kwenye simu hii.") : t("Your fingerprint never leaves this phone.", "Alama ya kidole chako haitoki kwenye simu hii.")).font(.lSmall).foregroundColor(C.muted) }.padding(.top, 10)
                    if !m.prefs.pin.isEmpty {
                        Button(t("Use my RAFIKI PIN instead", "Tumia PIN yangu ya RAFIKI")) { usePin = true; msg = nil }.font(.lMedium).foregroundColor(C.blue).padding(.top, 18)
                    }
                } else {
                    Text(t("USE YOUR PIN", "TUMIA PIN YAKO")).font(.lSmall).tracking(2).foregroundColor(C.blue).padding(.top, 34)
                    Text(t("Enter your RAFIKI PIN", "Weka PIN yako ya RAFIKI")).font(.hMedium).foregroundColor(C.ink).padding(.top, 6)
                    Text(t("The digits the office gave you.", "Tarakimu ulizopewa na ofisi.")).font(.bMedium).foregroundColor(C.muted).padding(.top, 4)
                    let n = max(4, m.prefs.pin.count)
                    HStack(spacing: 16) { ForEach(0..<n, id: \.self) { i in Circle().fill(i < pin.count ? C.blue : .clear).overlay(Circle().stroke(i < pin.count ? .clear : C.off, lineWidth: 2)).frame(width: 16, height: 16) } }.padding(.top, 22)
                    Msg(msg, "bad")
                    VStack(spacing: 12) {
                        ForEach([["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"], ["bio", "0", "del"]], id: \.self) { r in
                            HStack(spacing: 12) { ForEach(r, id: \.self) { k in key(k, n) } }
                        }
                    }.padding(.top, 18)
                }
                Button(t("Sign out", "Toka")) { m.lock() }.font(.lLarge).foregroundColor(C.blue).padding(.top, 22)
            }
            .frame(maxWidth: 420).padding(.horizontal, 24).padding(.vertical, 28).frame(maxWidth: .infinity)
        }
        .onAppear { if !asked { asked = true; ask() } }
    }

    @ViewBuilder func key(_ k: String, _ n: Int) -> some View {
        switch k {
        case "bio":
            Button { usePin = false; pin = ""; msg = nil; ask() } label: {
                VStack(spacing: 2) { Icon(Bio.face ? "face" : "finger", size: 26, color: C.blue); Text(Bio.face ? "Face ID" : t("Fingerprint", "Alama ya kidole")).font(.lSmall).foregroundColor(C.blue) }.frame(maxWidth: .infinity, minHeight: 66)
            }.buttonStyle(.plain)
        case "del":
            Button { if !pin.isEmpty { pin.removeLast() } } label: { Icon("del", size: 28, color: C.ink).frame(maxWidth: .infinity, minHeight: 66) }.buttonStyle(.plain)
        default:
            Button {
                Haptic.tap(); guard pin.count < n else { return }; pin += k
                if pin == m.prefs.pin { m.unlocked() } else if pin.count >= n { msg = t("That PIN isn’t right.", "PIN hiyo si sahihi."); pin = ""; Haptic.bad() }
            } label: {
                Text(k).font(.mont(24, .heavy)).foregroundColor(C.ink).frame(maxWidth: .infinity, minHeight: 66).background(C.card)
                    .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous)).shadow(color: .black.opacity(C.dark ? 0 : 0.07), radius: 2, y: 1)
            }.buttonStyle(Press())
        }
    }
}

/// The blue emblem with soft rings and two turning arcs.
private struct Emblem: View {
    let face: Bool
    @State private var turn = 0.0
    var body: some View {
        ZStack {
            Circle().fill(C.blue.opacity(0.06)).frame(width: 196, height: 196)
            Circle().fill(C.blue.opacity(0.10)).frame(width: 156, height: 156)
            Circle().trim(from: 0, to: 0.22).stroke(C.blue.opacity(0.55), style: StrokeStyle(lineWidth: 3, lineCap: .round)).frame(width: 176, height: 176).rotationEffect(.degrees(turn))
            Circle().trim(from: 0.5, to: 0.64).stroke(Color(hex: 0x14B8A6).opacity(0.6), style: StrokeStyle(lineWidth: 3, lineCap: .round)).frame(width: 176, height: 176).rotationEffect(.degrees(turn))
            Circle().fill(BlueBrush).frame(width: 116, height: 116).shadow(color: C.blue.opacity(0.35), radius: 18, y: 8)
            Image(systemName: face ? "faceid" : "touchid").font(.system(size: 54, weight: .regular)).foregroundColor(.white)
        }
        .onAppear { if Motion.on { withAnimation(.linear(duration: 8).repeatForever(autoreverses: false)) { turn = 360 } } }
        .accessibilityLabel(t("Unlock", "Fungua")).accessibilityAddTraits(.isButton)
    }
}

/// Me › Unlock with Face ID.
struct BioCard: View {
    @EnvironmentObject var m: AppModel
    @State private var on = false
    @State private var msg: String?
    var body: some View {
        let can = Bio.available, hu = C.hue(on ? "near" : "pin")
        CardV {
            HStack(alignment: .top, spacing: 12) {
                IconTile(icon: Bio.face ? "face" : "finger", tint: hu.fg, bg: hu.bg, size: 44)
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 8) {
                        Text(Bio.face ? t("Unlock with Face ID", "Fungua kwa Face ID") : t("Unlock with Touch ID", "Fungua kwa Touch ID")).font(.tMedium).foregroundColor(C.ink)
                        if on { Tag(t("On", "Imewashwa"), bg: C.okBg, fg: C.ok) }
                    }
                    Text(!can && !on ? t("Set up Face ID or a passcode in the phone’s Settings first.", "Weka kwanza Face ID au nambari ya siri kwenye Settings ya simu.")
                         : on ? t("Asked when the app opens and after a minute away. Lock the app now keeps your PIN, so you’re back in with a look.", "Inaulizwa programu inapofunguliwa na baada ya dakika moja nje. Kufunga programu sasa kunahifadhi PIN yako, hivyo unarudi kwa kutazama tu.")
                         : t("Keeps the app private if someone else picks up your phone.", "Inaweka programu faragha mtu mwingine akishika simu yako.")).font(.bSmall).foregroundColor(C.muted)
                }
            }
            if on { Btn(t("Turn off", "Zima"), light: true, wide: true) { on = false; m.prefs.bio = false }.padding(.top, 14) }
            else if can {
                Btn(t("Turn on", "Washa"), icon: Bio.face ? "face" : "finger", wide: true) {
                    Task { if await Bio.ask(t("Turn on Face ID unlock", "Washa kufungua kwa Face ID")) { on = true; m.prefs.bio = true; msg = nil; Haptic.ok() } else { msg = t("Not turned on.", "Haijawashwa.") } }
                }.padding(.top, 14)
            }
            Msg(msg, "bad")
        }
        .padding(.top, 14)
        .onAppear { on = m.prefs.bio }
    }
}

/// Lock screen › monday.com: sign in with a personal token.
struct MondaySignInSheet: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var phase
    @State private var busy = false
    @State private var msg: String?
    @State private var waiting = false
    @State private var typed = ""
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text(t("Sign in with monday.com", "Ingia kwa monday.com")).font(.tLarge).foregroundColor(C.ink)
                Text(t("Use your own monday.com token. Your changes then go in under your own name.", "Tumia tokeni yako ya monday.com. Mabadiliko yako yataingia kwa jina lako.")).font(.bMedium).foregroundColor(C.muted).padding(.top, 4)
                Btn("1 · " + t("Open my monday.com token", "Fungua tokeni yangu ya monday.com"), wide: true) { waiting = true; Share.open(TOKEN_PAGE) }.padding(.top, 16)
                Text(t("Tap Copy on that page, then come back here.", "Gusa Copy kwenye ukurasa huo, kisha rudi hapa.")).font(.lSmall).foregroundColor(C.muted).frame(maxWidth: .infinity).padding(.top, 4)
                Btn(busy ? t("Checking…", "Inakagua…") : "2 · " + t("Paste and sign in", "Bandika na uingie"), enabled: !busy, light: true, wide: true) { send(UIPasteboard.general.string ?? "") }.padding(.top, 10)
                SecureField(t("or paste the token here", "au bandika tokeni hapa"), text: $typed).font(.bLarge).padding(12).background(C.card).clipShape(RoundedRectangle(cornerRadius: 14)).overlay(RoundedRectangle(cornerRadius: 14).stroke(C.edge)).padding(.top, 12)
                if !typed.isEmpty { Btn(t("Sign in", "Ingia"), enabled: !busy, small: true) { send(typed) }.padding(.top, 8) }
                Msg(msg, busy ? "" : "bad")
                Button(t("Use the monday.com approve page instead", "Tumia ukurasa wa kuidhinisha wa monday.com")) { dismiss(); m.mondaySignIn() }.font(.lMedium).foregroundColor(C.blue).padding(.top, 14)
            }.padding(20)
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .onChange(of: phase) { p in if p == .active, waiting, !busy, looksToken(UIPasteboard.general.string) { send(UIPasteboard.general.string!) } }
    }
    func send(_ tok: String) {
        guard looksToken(tok) else { msg = reasonText("token-format"); return }
        busy = true; msg = t("Checking with monday.com…", "Inakagua na monday.com…")
        Task {
            let err = await m.mondayToken(tok); busy = false; msg = err
            if err == nil { UIPasteboard.general.string = ""; dismiss() }
        }
    }
}

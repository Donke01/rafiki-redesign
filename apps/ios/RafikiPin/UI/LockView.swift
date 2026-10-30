import SwiftUI
import UIKit

/// Sign-in: a big number pad. Four digits unlock by themselves.
struct LockView: View {
    @EnvironmentObject var m: AppModel
    @State private var pin = ""
    @State private var name = ""
    @State private var longPin = false
    @State private var needName = false
    @State private var msg: String?
    @State private var working = false
    @State private var shake: CGFloat = 0
    @State private var monday = false
    @State private var tokenSheet = false
    /// The hello screen comes first, once each time the app starts signed out (Android Welcome "hello").
    @State private var hello = !LockView.helloDone && !(ProcessInfo.processInfo.arguments.contains("-demo") && Demo.arg("-screen") != "hello")
    static var helloDone = false

    private var checking: String { t("Checking…", "Inakagua…") }

    func go() {
        if working || pin.isEmpty { return }
        working = true; msg = checking
        Task {
            let err = await m.signIn(pin: pin, name: needName ? name : "")
            working = false
            switch err {
            case nil: msg = nil; m.showBanner(nil)
            case "NEED_NAME": needName = true; msg = t("This is the shared team PIN, so please add your name too.", "Hii ni PIN ya timu nzima, kwa hiyo tafadhali ongeza jina lako pia.")
            default:
                msg = err; pin = ""
                UINotificationFeedbackGenerator().notificationOccurred(.error)
                for x in [-14.0, 14, -10, 10, 0] { withAnimation(.linear(duration: 0.06)) { shake = x }; try? await Task.sleep(nanoseconds: 60_000_000) }
            }
        }
    }
    func key(_ d: String) {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        if d == "del" { if !pin.isEmpty { pin.removeLast() }; return }
        if pin.count >= (longPin ? 8 : 4) { return }
        pin += d
        if !longPin && pin.count == 4 && !needName { go() }
    }

    var body: some View {
        if hello { MeHello { LockView.helloDone = true; withAnimation(.easeOut(duration: 0.25)) { hello = false } }.transition(.opacity) } else { keypad }
    }

    private var keypad: some View {
        ScrollView {
            VStack(spacing: 0) {
                MeDrop(size: 84, color: .blue, mood: .smile)
                Wordmark(height: 26).padding(.top, 14)
                Text(t("Enter your PIN", "Weka PIN yako")).font(.hMedium).foregroundColor(C.ink).padding(.top, 14)
                let first = m.prefs.name.split(separator: " ").first.map(String.init) ?? ""
                Text(first.isEmpty ? t("Welcome back.", "Habari, karibu tena.") : t("Welcome back, \(first).", "Habari \(first), karibu tena.")).font(.bMedium).foregroundColor(C.muted)

                let slots = longPin ? max(6, pin.count) : 4
                HStack(spacing: 16) {
                    ForEach(0..<slots, id: \.self) { i in
                        Circle().fill(i < pin.count ? C.blue : .clear).overlay(Circle().stroke(i < pin.count ? .clear : C.off, lineWidth: 2)).frame(width: 18, height: 18)
                    }
                }
                .padding(.top, 26).padding(.bottom, 6).offset(x: shake)
                .accessibilityElement().accessibilityLabel(t("\(pin.count) of \(slots) digits entered", "Tarakimu \(pin.count) kati ya \(slots)"))

                // blocked or signed out by the office: said kindly, not as an error
                let kind = [meReason("blocked"), meReason("signed-out")]
                Msg(msg, msg == checking ? "" : kind.contains(msg ?? "") ? "warn" : "bad")
                if msg == nil, let b = m.banner { Msg(b, b == t("Signing in…", "Inaingia…") ? "" : kind.contains(b) ? "warn" : "bad") }
                if needName { Field(t("Your name", "Jina lako"), $name, placeholder: t("e.g. Meril", "mf. Meril")) }
                Spacer().frame(height: 14)

                let rows = [["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"], ["", "0", "del"]]
                VStack(spacing: 12) {
                    ForEach(rows, id: \.self) { r in
                        HStack(spacing: 12) {
                            ForEach(r, id: \.self) { d in keyView(d) }
                        }
                    }
                }
                Btn(working ? checking : t("Unlock", "Fungua"), enabled: !pin.isEmpty && !working, wide: true) { go() }.padding(.top, 16)
                HStack(spacing: 4) {
                    Button(longPin ? t("My PIN has 4 digits", "PIN yangu ina tarakimu 4") : t("My PIN is longer", "PIN yangu ni ndefu zaidi")) { longPin.toggle(); pin = "" }
                    if !needName { Button(t("Shared PIN? Add your name", "PIN ya timu? Ongeza jina lako")) { needName = true } }
                }.font(.lMedium).foregroundColor(C.blue2).padding(.top, 10).buttonStyle(.plain).multilineTextAlignment(.center)
                Text(t("Needs signal the first time. After that it opens without it.", "Inahitaji mtandao mara ya kwanza. Baada ya hapo inafunguka bila mtandao."))
                    .font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.center).padding(.top, 12)
                LangSwitch().padding(.top, 16)
            }
            .frame(maxWidth: 420)
            .padding(.horizontal, 24).padding(.vertical, 28)
            .frame(maxWidth: .infinity)
        }
        .onAppear { name = m.prefs.name; msg = m.banner == nil ? nil : msg }
        .task { monday = await m.mondayAvailable() }
        .sheet(isPresented: $tokenSheet) { MondaySignInSheet() }
    }

    @ViewBuilder func keyView(_ d: String) -> some View {
        switch d {
        case "":
            if monday {
                Button { tokenSheet = true } label: {
                    VStack(spacing: 4) {
                        NetImage(url: "https://www.google.com/s2/favicons?domain=monday.com&sz=64") { Icon("people", color: C.blue) }
                            .frame(width: 26, height: 26).clipShape(RoundedRectangle(cornerRadius: 7))
                        Text(t("monday.com\nsign-in", "Ingia kwa\nmonday.com")).font(.lSmall).foregroundColor(C.blue2).multilineTextAlignment(.center)
                    }.frame(maxWidth: .infinity, minHeight: 72)
                }.buttonStyle(.plain).accessibilityIdentifier("monday")
            } else { Color.clear.frame(maxWidth: .infinity, minHeight: 72) }
        case "del":
            Button { key("del") } label: { Icon("del", size: 30, color: C.ink).frame(maxWidth: .infinity, minHeight: 72) }
                .buttonStyle(.plain).accessibilityLabel(t("Delete last digit", "Futa tarakimu ya mwisho"))
        default:
            Button { key(d) } label: {
                Text(d).font(.mont(26, .heavy)).foregroundColor(C.ink).frame(maxWidth: .infinity, minHeight: 72)
                    .background(C.card).clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).stroke(C.dark ? C.line : .clear, lineWidth: 1))
                    .shadow(color: .black.opacity(C.dark ? 0 : 0.08), radius: 2, y: 1)
            }.buttonStyle(Press()).accessibilityIdentifier("key\(d)")
        }
    }
}

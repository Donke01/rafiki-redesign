import SwiftUI
import UIKit

// Welcome screens (Android's Welcome.kt): a hello before signing in and, for a moment, every time the app opens;
// the first time on a phone, a short tour: what you do, language, country, location and a first stamp.

private let Warm = Color(hex: 0xFBFAF7)
private let OnbInk = Color(hex: 0x0C1924)
private let ONB_FLAGS = ["ke": "🇰🇪", "tz": "🇹🇿", "ug": "🇺🇬", "mw": "🇲🇼"]

/// The RAFIKI logo and "Pin": the black logo on light screens, the white one on dark.
struct OnbWordmark: View {
    var height: CGFloat = 24
    var body: some View {
        HStack(spacing: height / 3) {
            Image(C.dark ? "RafikiLogoLight" : "RafikiLogoDark").resizable().scaledToFit().frame(height: height)
            Text("Pin").font(.mont(height * 0.72, .heavy)).foregroundColor(C.dark ? Color(hex: 0x5BB4F2) : Color(hex: 0x0A78C2))
        }.accessibilityElement().accessibilityLabel("RAFIKI Pin")
    }
}

private struct OnbFrame<Content: View>: View {
    var back: (() -> Void)?
    @ViewBuilder var content: () -> Content
    var body: some View {
        ZStack {
            (C.dark ? Color(hex: 0x0D1318) : Warm).ignoresSafeArea()
            GeometryReader { g in
                let w = g.size.width, h = g.size.height
                ZStack {
                    RadialGradient(colors: [Color(hex: 0x6EC3FF).opacity(0.25), .clear], center: .center, startRadius: 0, endRadius: w * 0.7)
                        .frame(width: w * 1.4, height: w * 1.4).position(x: w * 0.2, y: h * 0.1)
                    RadialGradient(colors: [Color(hex: 0x78E8D2).opacity(0.22), .clear], center: .center, startRadius: 0, endRadius: w * 0.65)
                        .frame(width: w * 1.3, height: w * 1.3).position(x: w * 0.9, y: h * 0.85)
                }
            }.ignoresSafeArea().allowsHitTesting(false)
            VStack(spacing: 0) {
                ZStack {
                    OnbWordmark()
                    if let back {
                        HStack {
                            Button(action: back) { Icon("back", size: 22, color: C.dark ? .white : OnbInk).frame(width: 44, height: 44) }
                                .buttonStyle(.plain).accessibilityLabel(t("Back", "Rudi")).accessibilityIdentifier("onb_back")
                            Spacer()
                        }
                    }
                }.frame(height: 44)
                content()
            }.padding(.horizontal, 22).padding(.vertical, 10)
        }
    }
}

private struct BigTitle: View {
    let text: String
    var body: some View {
        Text(text).font(.mont(32, .heavy)).tracking(-1).foregroundColor(C.dark ? .white : Color(hex: 0x141618)).multilineTextAlignment(.center)
            .frame(maxWidth: .infinity).padding(.top, 14).padding(.bottom, 6)
    }
}
private struct Sub: View {
    let text: String
    var body: some View {
        Text(text).font(.bMedium).foregroundColor(C.dark ? Color(hex: 0x9CA8B2) : Color(hex: 0x5A6066)).multilineTextAlignment(.center)
            .frame(maxWidth: .infinity).padding(.horizontal, 12).padding(.vertical, 2)
    }
}
private struct Go: View {
    let text: String; var enabled = true; var tag = "onb_go"; let action: () -> Void
    var body: some View {
        Button { if enabled { Haptic.tap(); action() } } label: {
            Text(text).font(.mont(16, .bold)).foregroundColor((C.dark ? OnbInk : .white).opacity(enabled ? 1 : 0.4))
                .frame(maxWidth: .infinity, minHeight: 56)
                .background(C.dark ? AnyShapeStyle(Color.white) : AnyShapeStyle(LinearGradient(colors: [Color(hex: 0x1B2A36), OnbInk], startPoint: .top, endPoint: .bottom)))
                .clipShape(Capsule())
                .shadow(color: enabled ? Color(hex: 0x0C1924).opacity(0.33) : .clear, radius: 12, y: 6)
        }.buttonStyle(Press()).disabled(!enabled).accessibilityIdentifier(tag)
    }
}
private struct Cap: View {
    let text: String
    var body: some View { Text(text).font(.bMedium).foregroundColor(Color(hex: 0x7A8086)).multilineTextAlignment(.center).frame(maxWidth: .infinity).padding(.top, 8) }
}
private struct OnbLink: View {
    let text: String; let tag: String; let action: () -> Void
    var body: some View {
        Button(action: action) { Text(text).font(.lLarge).foregroundColor(C.dark ? .white : OnbInk).frame(maxWidth: .infinity).padding(10).contentShape(Rectangle()) }
            .buttonStyle(.plain).padding(.top, 6).accessibilityIdentifier(tag)
    }
}

/// Sticky notes with real project names, around the welcome.
private struct Notes: View {
    let names: [String]
    @State private var shown = false
    var body: some View {
        let n = Array((names + ["Kiptoo Well", "Turbo Spring", "Chepkongi Dispensary", "Kapsaret Borehole"]).prefix(4))
        let notes: [(Alignment, Double, Color, Color, String)] = [
            (.topLeading, -7, Color(hex: 0xFFF7C2), Color(hex: 0x4A3B00), t("PINNED", "IMEWEKWA")),
            (.topTrailing, 6, Color(hex: 0xDDEFFF), Color(hex: 0x0B3A5E), t("VISIT", "ZIARA")),
            (.bottomLeading, 5, Color(hex: 0xDDF6E8), Color(hex: 0x0B4D2C), t("WORKING", "INAFANYA KAZI")),
            (.bottomTrailing, -6, Color(hex: 0xFFE3DC), Color(hex: 0x7A1E12), t("REPAIR", "MATENGENEZO")),
        ]
        ZStack {
            ForEach(0..<4, id: \.self) { i in
                let x = notes[i]
                VStack(alignment: .leading, spacing: 1) {
                    Text(x.4).font(.mont(10, .heavy)).foregroundColor(x.3.opacity(0.6))
                    Text(n[i]).font(.mont(12, .bold)).foregroundColor(x.3).lineLimit(3)
                }
                .padding(10).frame(width: 118, alignment: .leading).background(x.2).clipShape(RoundedRectangle(cornerRadius: 6))
                .shadow(color: .black.opacity(0.12), radius: 6, y: 3)
                .rotationEffect(.degrees(x.1))
                .padding(.top, i < 2 ? CGFloat(40 + i * 30) : 0).padding(.bottom, i >= 2 ? CGFloat(120 + (3 - i) * 30) : 0)
                .opacity(shown ? 1 : 0).offset(y: shown ? 0 : 30)
                .animation(Motion.on ? .easeOut(duration: 0.5).delay(0.1 * Double(i)) : nil, value: shown)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: x.0)
            }
        }
        .onAppear { shown = true }
        .accessibilityHidden(true)
    }
}

private struct Hero: View {
    var body: some View {
        (Text(t("Every water point.", "Kila kituo cha maji.") + "\n" + t("One map.", "Ramani moja.") + "\n").foregroundColor(C.dark ? .white : Color(hex: 0x141618))
         + Text(t("Right in your pocket.", "Mfukoni mwako.")).foregroundColor(Color(hex: 0x8A9096)))
            .font(.mont(25, .bold)).lineSpacing(4).multilineTextAlignment(.center).frame(maxWidth: .infinity)
    }
}

private struct HeroBlock: View {
    let names: [String]
    var body: some View {
        ZStack {
            Notes(names: names)
            VStack(spacing: 22) { Hero(); Drop(size: 130, color: .blue, mood: .smile) }
        }.frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// Before signing in ("hello") and, for a moment, every time the app opens ("splash").
struct WelcomeView: View {
    @EnvironmentObject var m: AppModel
    let splash: Bool; let onSignIn: () -> Void
    var body: some View {
        let names = m.snap?.projects.map(\.title) ?? []
        let first = m.prefs.name.split(separator: " ").first.map(String.init) ?? ""
        let back = first.isEmpty ? t("Welcome back.", "Habari, karibu tena.") : t("Welcome back, \(first).", "Habari \(first), karibu tena.")
        OnbFrame(back: nil) {
            HeroBlock(names: names).contentShape(Rectangle()).onTapGesture { if splash { onSignIn() } }
            if splash { Cap(text: back) }
            else {
                LangSwitch().frame(maxWidth: .infinity).padding(.bottom, 14)
                Go(text: t("Sign in", "Ingia"), tag: "onb_signin", action: onSignIn)
                Cap(text: first.isEmpty ? t("New here? Ask your team for a PIN.", "Mgeni hapa? Omba PIN kwa timu yako.") : back)
            }
        }
        .task { if splash { try? await Task.sleep(nanoseconds: 1_600_000_000); onSignIn() } }
    }
}

/// The first time on a phone: what you do, language, country, location, and a first stamp.
struct OnboardingView: View {
    @EnvironmentObject var m: AppModel
    let done: (String) -> Void
    @State private var step = 0
    @State private var role = ""
    @State private var ck = ""
    @State private var page = 0

    var body: some View {
        let admin = m.snap?.admin == true
        OnbFrame(back: step > 0 ? { withAnimation(.easeOut(duration: 0.2)) { step -= 1 } } : nil) {
            switch step {
            case 0:
                HeroBlock(names: m.snap?.projects.map(\.title) ?? [])
                Go(text: t("Get started", "Anza")) { step = 1 }
                Cap(text: t("Takes 30 seconds", "Inachukua sekunde 30"))
            case 1:
                BigTitle(text: t("What will you do most?", "Utafanya nini zaidi?"))
                Sub(text: t("We’ll open the app on it. You can change this anytime.", "Tutafungua programu hapo. Unaweza kubadilisha wakati wowote."))
                let opts: [(String, String, String, DropC, Mood)] = [
                    ("pin", t("Pin locations", "Weka mahali"), t("Put every project on the map", "Weka kila mradi kwenye ramani"), .blue, .smile),
                    ("visit", t("Site visits", "Ziara"), t("Check water points and report", "Kagua vituo vya maji na uripoti"), .green, .o),
                    ("repairs", t("Repairs", "Matengenezo"), t("Follow what needs fixing", "Fuatilia kinachohitaji kurekebishwa"), .coral, .wink),
                ] + (admin ? [("office", t("Office work", "Kazi za ofisi"), t("Review, assign and report", "Kagua, gawa na ripoti"), .purple, .smile)] : [])
                VStack(spacing: 10) {
                    ForEach(opts, id: \.0) { k, l, s, dc, md in
                        Opt(on: role == k, tag: "onb_r_\(k)", action: { role = k }) {
                            VStack(alignment: .leading, spacing: 1) { Text(l).font(.mont(16, .bold)); Text(s).font(.mont(12)).opacity(0.75) }.frame(maxWidth: .infinity, alignment: .leading)
                            Drop(size: 40, color: dc, mood: md)
                        }
                    }
                }.padding(.top, 12)
                Spacer()
                Go(text: t("Continue", "Endelea"), enabled: !role.isEmpty) {
                    switch role { case "pin": m.prefs.filter = "missing"; case "visit": m.prefs.filter = "near"; case "repairs": m.prefs.filter = "repairs"; default: break }
                    step = 2
                }
                Cap(text: t("Pick one", "Chagua kimoja"))
            case 2:
                BigTitle(text: t("Which language?", "Lugha gani?"))
                VStack(spacing: 0) {
                    Spacer()
                    TabView(selection: $page) {
                        ForEach(0..<2, id: \.self) { pg in
                            let sw = pg == 1
                            VStack(spacing: 12) {
                                Drop(size: 104, color: sw ? .green : .blue, mood: sw ? .smile : .o)
                                Text(sw ? "KISWAHILI" : "ENGLISH").font(.mont(12, .heavy)).tracking(1).foregroundColor(sw ? Color(hex: 0x0B4D2C) : Color(hex: 0x0B3A5E))
                                    .padding(.horizontal, 14).padding(.vertical, 6).background(Color.white).clipShape(RoundedRectangle(cornerRadius: 10))
                                Text(sw ? "Habari! Tutafute vituo vya maji vya leo." : "Hello! Let’s find today’s water points.").font(.mont(18, .bold)).multilineTextAlignment(.center)
                                    .foregroundColor(sw ? Color(hex: 0x0B4D2C) : Color(hex: 0x0B3A5E))
                            }
                            .padding(.horizontal, 18).padding(.vertical, 24).frame(maxWidth: .infinity)
                            .background(sw ? Color(hex: 0xDDF6E8) : Color(hex: 0xE3F1FC)).clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
                            .padding(.horizontal, 26).tag(pg)
                        }
                    }
                    .tabViewStyle(.page(indexDisplayMode: .never)).frame(height: 300).accessibilityIdentifier("onb_pager")
                    Text(page == 1 ? "Kiswahili" : "English").font(.mont(20, .heavy)).foregroundColor(C.dark ? .white : Color(hex: 0x141618)).padding(.top, 12).accessibilityIdentifier("onb_lang")
                    HStack(spacing: 6) {
                        ForEach(0..<2, id: \.self) { i in Capsule().fill(page == i ? Color(hex: 0x0A78C2) : Color(hex: 0xC9CDD1)).frame(width: page == i ? 18 : 6, height: 6) }
                    }.padding(.top, 8).animation(.easeOut(duration: 0.2), value: page)
                    Spacer()
                }
                Go(text: t("That’s my language", "Hiyo ndiyo lugha yangu")) { Lang.sw = page == 1; m.prefs.lang = Lang.sw ? "sw" : "en"; step = 3 }
                Cap(text: t("Swipe to see both. Switch anytime in Me.", "Telezesha kuona zote. Badilisha wakati wowote kwenye Mimi."))
            case 3:
                BigTitle(text: t("Where do you work?", "Unafanya kazi wapi?"))
                Sub(text: t("Lists and the map start there. You still see every country.", "Orodha na ramani zinaanzia hapo. Bado unaona nchi zote."))
                VStack(spacing: 10) {
                    ForEach(COUNTRIES, id: \.0) { k, n in
                        Opt(on: ck == k, tag: "onb_c_\(k)", action: { ck = k }) {
                            Text(n).font(.mont(16, .bold)).frame(maxWidth: .infinity, alignment: .leading)
                            Text(ONB_FLAGS[k] ?? "").font(.system(size: 24))
                        }
                    }
                }.padding(.top, 12)
                Spacer()
                Go(text: t("Continue", "Endelea"), enabled: !ck.isEmpty) { m.prefs.country = ck; step = 4 }
                OnbLink(text: t("Skip", "Ruka"), tag: "onb_skip") { step = 4 }
            case 4:
                BigTitle(text: t("Water points near you", "Vituo vya maji karibu nawe"))
                Sub(text: t("RAFIKI Pin uses your location only while it’s open, to show the closest projects and save exact points.", "RAFIKI Pin inatumia mahali ulipo tu ikiwa wazi, kuonyesha miradi iliyo karibu na kuhifadhi mahali kamili."))
                Drop(size: 130, color: .amber, mood: .smile).frame(maxWidth: .infinity, maxHeight: .infinity)
                Go(text: t("Allow location", "Ruhusu mahali"), tag: "onb_loc") { Locator.shared.start(); step = 5 }
                OnbLink(text: t("Not now", "Si sasa"), tag: "onb_notnow") { step = 5 }
            default:
                BigTitle(text: t("A good start", "Mwanzo mzuri"))
                Sub(text: t("You’re all set, ", "Uko tayari, ") + (m.prefs.name.split(separator: " ").first.map(String.init) ?? "") + ".")
                VStack(spacing: 0) {
                    Stamp(width: 210, color: .blue, top: "RAFIKI · FIRST DROP", num: "001", got: true, mood: .smile)
                    Text(t("Your first stamp", "Stempu yako ya kwanza")).font(.mont(17, .heavy)).foregroundColor(C.dark ? .white : Color(hex: 0x141618)).padding(.top, 22)
                    Sub(text: t("Every point you pin and every visit you report adds to your collection.", "Kila mahali unapoweka na kila ziara unayoripoti inaongeza kwenye mkusanyiko wako."))
                }.frame(maxWidth: .infinity, maxHeight: .infinity)
                Go(text: t("Open RAFIKI Pin", "Fungua RAFIKI Pin"), tag: "onb_done") { m.prefs.onb = true; m.look += 1; done(role) }
                Cap(text: t("Small steps, clean water.", "Hatua ndogo, maji safi."))
            }
        }
        .onAppear { if ck.isEmpty { ck = m.prefs.country }; page = Lang.sw ? 1 : 0 }
    }
}

private struct Opt<Content: View>: View {
    let on: Bool; let tag: String; let action: () -> Void
    @ViewBuilder var content: () -> Content
    var body: some View {
        Button { Haptic.tap(); action() } label: {
            HStack(spacing: 10) { content() }
                .foregroundColor(on || C.dark ? .white : Color(hex: 0x141618))
                .padding(.leading, 18).padding(.trailing, 10).frame(maxWidth: .infinity, minHeight: 64)
                .background(on ? Color(hex: 0x0A78C2) : C.dark ? Color(hex: 0x18222B) : Color(hex: 0xF1F1EE))
                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                .shadow(color: on ? Color(hex: 0x0A78C2).opacity(0.4) : .clear, radius: 10, y: 5)
        }.buttonStyle(Press()).accessibilityIdentifier(tag).accessibilityAddTraits(on ? .isSelected : [])
    }
}

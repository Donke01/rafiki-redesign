import SwiftUI

// The welcome screens (Android Welcome.kt, 2.13.1 with the RAFIKI logo): "hello" before signing in, and the first time
// on a phone a short tour: what you'll do most, language, country, location, and a first stamp.

private let Warm = Color(hex: 0xFBFAF7)
private let InkW = Color(hex: 0x141618)
private let FLAGS = ["ke": "🇰🇪", "tz": "🇹🇿", "ug": "🇺🇬", "mw": "🇲🇼"]

/// The frame every welcome screen has: soft colours behind, Back (when there is one) and the RAFIKI logo on top.
private struct OnbFrame<Content: View>: View {
    var back: (() -> Void)? = nil
    @ViewBuilder var content: () -> Content
    var body: some View {
        ZStack {
            (C.dark ? Color(hex: 0x0D1318) : Warm).ignoresSafeArea()
            GeometryReader { g in
                let w = g.size.width, h = g.size.height
                ZStack {
                    Circle().fill(RadialGradient(colors: [Color(hex: 0x6EC3FF, alpha: 0.25), .clear], center: .center, startRadius: 0, endRadius: w * 0.7)).frame(width: w * 1.4, height: w * 1.4).position(x: w * 0.2, y: h * 0.1)
                    Circle().fill(RadialGradient(colors: [Color(hex: 0x78E8D2, alpha: 0.22), .clear], center: .center, startRadius: 0, endRadius: w * 0.65)).frame(width: w * 1.3, height: w * 1.3).position(x: w * 0.9, y: h * 0.85)
                }
            }.ignoresSafeArea().allowsHitTesting(false)
            VStack(spacing: 0) {
                ZStack {
                    Wordmark(light: C.dark)
                    if let back {
                        HStack {
                            Button(action: back) { Icon("back", size: 22, color: C.dark ? .white : InkW).frame(width: 44, height: 44) }.buttonStyle(.plain).accessibilityLabel(t("Back", "Rudi"))
                            Spacer()
                        }
                    }
                }.frame(height: 44)
                content()
            }
            .padding(.horizontal, 22).padding(.vertical, 10)
            .frame(maxWidth: 520)
        }
    }
}

private struct BigTitle: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text).font(.mont(30, .heavy)).foregroundColor(C.dark ? .white : InkW).multilineTextAlignment(.center).minimumScaleFactor(0.7)
            .frame(maxWidth: .infinity).padding(.top, 14).padding(.bottom, 6).fixedSize(horizontal: false, vertical: true)
    }
}
private struct OnbSub: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text).font(.bMedium).foregroundColor(C.dark ? Color(hex: 0x9CA8B2) : Color(hex: 0x5A6066)).multilineTextAlignment(.center)
            .frame(maxWidth: .infinity).padding(.horizontal, 12).padding(.vertical, 2).fixedSize(horizontal: false, vertical: true)
    }
}
private struct Cap: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View { Text(text).font(.bMedium).foregroundColor(Color(hex: 0x7A8086)).multilineTextAlignment(.center).frame(maxWidth: .infinity).padding(.top, 8).fixedSize(horizontal: false, vertical: true) }
}
/// The big dark button at the bottom.
private struct Go: View {
    let text: String; var enabled = true; let action: () -> Void
    var body: some View {
        let dark = C.dark
        Button { Haptic.tap(); action() } label: {
            Text(text).font(.mont(16, .bold)).foregroundColor((dark ? InkW : .white).opacity(enabled ? 1 : 0.4)).multilineTextAlignment(.center)
                .padding(.horizontal, 16).frame(maxWidth: .infinity, minHeight: 56)
                .background(Capsule().fill(dark ? AnyShapeStyle(Color.white) : AnyShapeStyle(LinearGradient(colors: [Color(hex: 0x1E2A33), Color(hex: 0x0C1924)], startPoint: .top, endPoint: .bottom))))
                .shadow(color: Color(hex: 0x0C1924, alpha: enabled ? 0.33 : 0), radius: 12, y: 6)
        }.buttonStyle(Press()).disabled(!enabled)
    }
}
private struct OnbLink: View {
    let text: String; let action: () -> Void
    var body: some View {
        Button(action: action) { Text(text).font(.lLarge).foregroundColor(C.dark ? .white : InkW).frame(maxWidth: .infinity, minHeight: 44) }.buttonStyle(.plain).padding(.top, 6)
    }
}

/// Sticky notes with real project names, around the welcome.
private struct Notes: View {
    let names: [String]
    @State private var shown = false
    var body: some View {
        let n = Array((names + ["Kiptoo Well", "Turbo Spring", "Chepkongi Dispensary", "Kapsaret Borehole"]).prefix(4))
        let notes: [(Alignment, Double, UInt32, UInt32, String, CGFloat)] = [
            (.topLeading, -7, 0xFFF7C2, 0x4A3B00, t("PINNED", "IMEWEKWA"), 40), (.topTrailing, 6, 0xDDEFFF, 0x0B3A5E, t("VISIT", "ZIARA"), 70),
            (.bottomLeading, 5, 0xDDF6E8, 0x0B4D2C, t("WORKING", "INAFANYA KAZI"), 150), (.bottomTrailing, -6, 0xFFE3DC, 0x7A1E12, t("REPAIR", "MATENGENEZO"), 120),
        ]
        ZStack {
            ForEach(0..<4, id: \.self) { i in
                let x = notes[i]
                VStack(alignment: .leading, spacing: 2) {
                    Text(x.4).font(.mont(10, .heavy)).foregroundColor(Color(hex: x.3).opacity(0.6)).lineLimit(1).minimumScaleFactor(0.7)
                    Text(n[i]).font(.mont(12, .bold)).foregroundColor(Color(hex: x.3)).lineLimit(2).fixedSize(horizontal: false, vertical: true)
                }
                .padding(10).frame(width: 118, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 6).fill(Color(hex: x.2)))
                .shadow(color: .black.opacity(0.12), radius: 6, y: 3)
                .rotationEffect(.degrees(x.1))
                .opacity(shown ? 1 : 0).offset(y: shown ? 0 : 20)
                .animation(Motion.on ? .easeOut(duration: 0.5).delay(0.1 * Double(i)) : nil, value: shown)
                .padding(i < 2 ? .top : .bottom, x.5)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: x.0)
            }
        }
        .accessibilityHidden(true)
        .onAppear { shown = true }
    }
}

private struct Hero: View {
    var body: some View {
        VStack(spacing: 22) {
            (Text(t("Every water point.", "Kila kituo cha maji.") + "\n" + t("One map.", "Ramani moja.") + "\n").foregroundColor(C.dark ? .white : InkW)
             + Text(t("Right in your pocket.", "Mfukoni mwako.")).foregroundColor(Color(hex: 0x8A9096)))
                .font(.mont(25, .bold)).multilineTextAlignment(.center).minimumScaleFactor(0.7).fixedSize(horizontal: false, vertical: true)
            MeDrop(size: 130, color: .blue, mood: .smile)
        }
    }
}

/// Before signing in: the hello screen, with the language switch and Sign in.
struct MeHello: View {
    @EnvironmentObject var m: AppModel
    let signIn: () -> Void
    var body: some View {
        let first = m.prefs.name.split(separator: " ").first.map(String.init) ?? ""
        OnbFrame {
            ZStack { Notes(names: m.snap?.projects.map(\.title) ?? []); Hero() }.frame(maxHeight: .infinity)
            LangSwitch().padding(.bottom, 14)
            Go(text: t("Sign in", "Ingia"), action: signIn).accessibilityIdentifier("onb_signin")
            Cap(first.isEmpty ? t("New here? Ask your team for a PIN.", "Mgeni hapa? Omba PIN kwa timu yako.") : t("Welcome back, \(first).", "Habari \(first), karibu tena."))
        }
    }
}

/// The first time on a phone (or Help › Show the welcome screens again).
struct MeWelcome: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject private var loc = Locator.shared
    let done: () -> Void
    @State private var step = MeNav.shared.welcomeStep
    @State private var role = ""
    @State private var ck = ""
    @State private var page = 0
    @State private var asked = false
    var body: some View {
        OnbFrame(back: step > 0 ? { withAnimation { step -= 1 } } : nil) {
            Group {
                switch step {
                case 0: start
                case 1: roles
                case 2: language
                case 3: country
                case 4: location
                default: stamp
                }
            }.transition(.opacity)
        }
        .onAppear { ck = m.prefs.country; page = Lang.sw ? 1 : 0 }
        .onChange(of: step) { st in MeNav.shared.welcomeStep = st }
        .onChange(of: loc.allowed) { ok in if ok && step == 4 && asked { m.locator.start(); withAnimation { step = 5 } } }
    }

    private var start: some View {
        VStack(spacing: 0) {
            ZStack { Notes(names: m.snap?.projects.map(\.title) ?? []); Hero() }.frame(maxHeight: .infinity)
            Go(text: t("Get started", "Anza")) { withAnimation { step = 1 } }
            Cap(t("Takes 30 seconds", "Inachukua sekunde 30"))
        }
    }

    private typealias RoleOpt = (k: String, title: String, sub: String, color: DropC, mood: DropMood)
    private func roleOpts() -> [RoleOpt] {
        var o: [RoleOpt] = []
        o.append((k: "pin", title: t("Pin locations", "Weka mahali"), sub: t("Put every project on the map", "Weka kila mradi kwenye ramani"), color: DropC.blue, mood: DropMood.smile))
        o.append((k: "visit", title: t("Site visits", "Ziara"), sub: t("Check water points and report", "Kagua vituo vya maji na uripoti"), color: DropC.green, mood: DropMood.o))
        o.append((k: "repairs", title: t("Repairs", "Matengenezo"), sub: t("Follow what needs fixing", "Fuatilia kinachohitaji kurekebishwa"), color: DropC.coral, mood: DropMood.wink))
        if m.snap?.admin == true { o.append((k: "office", title: t("Office work", "Kazi za ofisi"), sub: t("Review, assign and report", "Kagua, gawa na ripoti"), color: DropC.purple, mood: DropMood.smile)) }
        return o
    }

    private var roles: some View {
        let opts = roleOpts()
        return VStack(spacing: 0) {
            BigTitle(t("What will you do most?", "Utafanya nini zaidi?"))
            OnbSub(t("We’ll open the app on it. You can change this anytime.", "Tutafungua programu hapo. Unaweza kubadilisha wakati wowote."))
            ScrollView {
                VStack(spacing: 10) {
                    ForEach(opts, id: \.k) { o in
                        let on = role == o.k
                        let fg: Color = on || C.dark ? .white : InkW
                        Opt(on: on, action: { role = o.k }) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(o.title).font(.mont(16, .bold)).foregroundColor(fg)
                                Text(o.sub).font(.mont(12)).foregroundColor(fg.opacity(0.75)).fixedSize(horizontal: false, vertical: true)
                            }.frame(maxWidth: .infinity, alignment: .leading)
                            MeDrop(size: 40, color: o.color, mood: o.mood, still: true)
                        }
                    }
                }.padding(.top, 12)
            }
            Go(text: t("Continue", "Endelea"), enabled: !role.isEmpty) {
                switch role { case "pin": m.prefs.filter = "missing"; case "visit": m.prefs.filter = "near"; case "repairs": m.prefs.filter = "repairs"; default: break }
                withAnimation { step = 2 }
            }
            Cap(t("Pick one", "Chagua kimoja"))
        }
    }

    private var language: some View {
        VStack(spacing: 0) {
            BigTitle(t("Which language?", "Lugha gani?"))
            TabView(selection: $page) {
                ForEach(0..<2, id: \.self) { pg in
                    let sw = pg == 1
                    VStack(spacing: 12) {
                        MeDrop(size: 104, color: sw ? .green : .blue, mood: sw ? .smile : .o)
                        Text(sw ? "KISWAHILI" : "ENGLISH").font(.mont(12, .heavy)).tracking(1).foregroundColor(sw ? Color(hex: 0x0B4D2C) : Color(hex: 0x0B3A5E))
                            .padding(.horizontal, 14).padding(.vertical, 6).background(RoundedRectangle(cornerRadius: 10).fill(Color.white))
                        Text(sw ? "Habari! Tutafute vituo vya maji vya leo." : "Hello! Let’s find today’s water points.").font(.mont(18, .bold)).multilineTextAlignment(.center)
                            .foregroundColor(sw ? Color(hex: 0x0B4D2C) : Color(hex: 0x0B3A5E)).fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(.horizontal, 18).padding(.vertical, 24).frame(maxWidth: .infinity)
                    .background(RoundedRectangle(cornerRadius: 28, style: .continuous).fill(sw ? Color(hex: 0xDDF6E8) : Color(hex: 0xE3F1FC)))
                    .padding(.horizontal, 30)
                    .tag(pg)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .frame(maxHeight: 360)
            Text(page == 1 ? "Kiswahili" : "English").font(.mont(20, .heavy)).foregroundColor(C.dark ? .white : InkW).padding(.top, 12)
            HStack(spacing: 6) { ForEach(0..<2, id: \.self) { i in Capsule().fill(page == i ? Color(hex: 0x0A78C2) : Color(hex: 0xC9CDD1)).frame(width: page == i ? 18 : 6, height: 6) } }.padding(.top, 8)
            Spacer(minLength: 8)
            Go(text: t("That’s my language", "Hiyo ndiyo lugha yangu")) { m.setLang(page == 1); withAnimation { step = 3 } }
            Cap(t("Swipe to see both. Switch anytime in Me.", "Telezesha kuona zote. Badilisha wakati wowote kwenye Mimi."))
        }
    }

    private var country: some View {
        VStack(spacing: 0) {
            BigTitle(t("Where do you work?", "Unafanya kazi wapi?"))
            OnbSub(t("Lists and the map start there. You still see every country.", "Orodha na ramani zinaanzia hapo. Bado unaona nchi zote."))
            VStack(spacing: 10) {
                ForEach(COUNTRIES, id: \.0) { k, n in
                    Opt(on: ck == k, action: { ck = k }) {
                        Text(n).font(.mont(16, .bold)).foregroundColor(ck == k ? .white : (C.dark ? .white : InkW)).frame(maxWidth: .infinity, alignment: .leading)
                        Text(FLAGS[k] ?? "").font(.system(size: 24))
                    }
                }
            }.padding(.top, 12)
            Spacer(minLength: 8)
            Go(text: t("Continue", "Endelea"), enabled: !ck.isEmpty) { m.prefs.country = ck; withAnimation { step = 4 } }
            OnbLink(text: t("Skip", "Ruka")) { withAnimation { step = 4 } }
        }
    }

    private var location: some View {
        VStack(spacing: 0) {
            BigTitle(t("Water points near you", "Vituo vya maji karibu nawe"))
            OnbSub(t("RAFIKI Pin uses your location only while it’s open, to show the closest projects and save exact points.", "RAFIKI Pin inatumia mahali ulipo tu ikiwa wazi, kuonyesha miradi iliyo karibu na kuhifadhi mahali kamili."))
            MeDrop(size: 130, color: .amber, mood: .smile).frame(maxHeight: .infinity)
            Go(text: t("Allow location", "Ruhusu mahali")) {
                if loc.allowed || loc.denied { m.locator.start(); withAnimation { step = 5 }; return }
                asked = true; m.locator.start()
                // if the question doesn't come back, carry on anyway
                Task { try? await Task.sleep(nanoseconds: 8_000_000_000); if step == 4 { withAnimation { step = 5 } } }
            }
            OnbLink(text: t("Not now", "Si sasa")) { withAnimation { step = 5 } }
        }
    }

    private var stamp: some View {
        VStack(spacing: 0) {
            BigTitle(t("A good start", "Mwanzo mzuri"))
            OnbSub(t("You’re all set, ", "Uko tayari, ") + (m.prefs.name.split(separator: " ").first.map(String.init) ?? "") + ".")
            VStack(spacing: 0) {
                MeStamp(width: 210, color: .blue, top: "RAFIKI · FIRST DROP", num: "001", got: true, mood: .smile, still: false)
                Text(t("Your first stamp", "Stempu yako ya kwanza")).font(.mont(17, .heavy)).foregroundColor(C.dark ? .white : InkW).padding(.top, 22)
                OnbSub(t("Every point you pin and every visit you report adds to your collection.", "Kila mahali unapoweka na kila ziara unayoripoti inaongeza kwenye mkusanyiko wako."))
            }.frame(maxHeight: .infinity)
            Go(text: t("Open RAFIKI Pin", "Fungua RAFIKI Pin")) {
                m.prefs.onb = true
                let r = role
                done()
                if r == "office" && m.snap?.admin == true { m.tab("office") } else { m.tab("list") }
            }.accessibilityIdentifier("onb_done")
            Cap(t("Small steps, clean water.", "Hatua ndogo, maji safi."))
        }
    }
}

/// A big choice on the welcome screens.
private struct Opt<Content: View>: View {
    let on: Bool; let action: () -> Void
    @ViewBuilder var content: () -> Content
    var body: some View {
        Button { Haptic.tap(); action() } label: {
            HStack(spacing: 10) { content() }
                .padding(.leading, 18).padding(.trailing, 10).frame(maxWidth: .infinity, minHeight: 64)
                .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(on ? Color(hex: 0x0A78C2) : (C.dark ? Color(hex: 0x18222B) : Color(hex: 0xF1F1EE))))
                .shadow(color: Color(hex: 0x0A78C2, alpha: on ? 0.4 : 0), radius: 10, y: 4)
        }.buttonStyle(Press()).accessibilityAddTraits(on ? .isSelected : [])
    }
}

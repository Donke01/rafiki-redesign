import SwiftUI
import UIKit

// One Settings page, opened from the Settings card: personal details, Change my PIN, notifications, monday.com,
// appearance, language, distances, help, sign out, about (Android Settings.kt).

struct SettingsPage: View {
    @EnvironmentObject var m: AppModel
    let which: String
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                MeTitle(title).padding(.top, 4).padding(.bottom, 8)
                CardV { page }.padding(.top, 8)
            }
            .padding(16).padding(.bottom, 60)
            .meCentred()
        }
        .accessibilityIdentifier("set_" + which)
    }

    private var title: String {
        switch which {
        case "profile": return t("Personal details", "Taarifa zangu")
        case "pin": return m.me.kind == "monday" ? t("Sign-in", "Kuingia") : t("Change my PIN", "Badilisha PIN yangu")
        case "notify": return t("Notification settings", "Mipangilio ya arifa")
        case "monday": return t("Your name on monday.com", "Jina lako kwenye monday.com")
        case "help": return t("Help and FAQs", "Msaada na maswali")
        case "signout": return t("Sign out of this phone?", "Toka kwenye simu hii?")
        case "look": return t("Appearance", "Mwonekano")
        case "lang": return t("Language", "Lugha")
        case "units": return t("Distances", "Umbali")
        default: return t("About RAFIKI Pin", "Kuhusu RAFIKI Pin")
        }
    }

    @ViewBuilder private var page: some View {
        switch which {
        case "profile": SetProfile()
        case "pin": SetPin()
        case "notify": SetNotify()
        case "monday": SetMonday()
        case "help": SetHelp()
        case "signout": SetSignOut()
        case "look": SetLook()
        case "lang": SetLang()
        case "units": SetUnits()
        default: SetAbout()
        }
    }
}

/// A choice on a Settings page: a name, a line under it, and a tick when it's the one in use.
struct SetChoice<Lead: View>: View {
    let title: String; let sub: String; let on: Bool
    @ViewBuilder var lead: () -> Lead
    let action: () -> Void
    var body: some View {
        Button { Haptic.tap(); action() } label: {
            HStack(spacing: 12) {
                lead()
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).font(.tSmall).foregroundColor(C.ink).fixedSize(horizontal: false, vertical: true)
                    if !sub.isEmpty { Text(sub).font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true) }
                }.frame(maxWidth: .infinity, alignment: .leading)
                ZStack {
                    Circle().fill(on ? C.blue : Color.clear)
                    Circle().stroke(on ? Color.clear : C.edge, lineWidth: 1.5)
                    if on { Icon("check", size: 15, color: .white) }
                }.frame(width: 24, height: 24)
            }
            .padding(.horizontal, 14).padding(.vertical, 12).frame(minHeight: 52)
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(on ? C.tint : Color.clear))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(on ? C.blue : C.line, lineWidth: on ? 2 : 1))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain).padding(.top, 8)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}
extension SetChoice where Lead == EmptyView {
    init(title: String, sub: String, on: Bool, action: @escaping () -> Void) { self.title = title; self.sub = sub; self.on = on; self.lead = { EmptyView() }; self.action = action }
}

private struct Centre: View {
    let text: String; var color: Color = C.muted
    init(_ text: String, color: Color = C.muted) { self.text = text; self.color = color }
    var body: some View { Text(text).font(.bMedium).foregroundColor(color).multilineTextAlignment(.center).frame(maxWidth: .infinity).fixedSize(horizontal: false, vertical: true) }
}

// MARK: - Personal details

private struct SetProfile: View {
    @EnvironmentObject var m: AppModel
    @State private var name = ""
    @State private var nick = ""
    @State private var phone = ""
    @State private var home = ""
    @State private var busy = false
    @State private var msg: String?
    @State private var ready = false
    var body: some View {
        let me = m.me
        let own = !m.prefs.name.isEmpty && me.kind != "shared"
        VStack(alignment: .leading, spacing: 0) {
            if own {
                Text(t("Full name", "Jina kamili")).font(.mont(13, .bold)).foregroundColor(C.ink)
                Text(m.prefs.name).font(.bLarge).foregroundColor(C.muted).padding(.horizontal, 14).frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
                    .background(C.bg).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).padding(.top, 6)
                Text(t("Your name comes from your sign-in. Ask the office to change it.", "Jina lako linatoka kwenye kuingia kwako. Omba ofisi ibadilishe.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 4)
                Field(t("What the team calls you (optional)", "Jina la kuitwa na timu (si lazima)"), Binding(get: { nick }, set: { nick = String($0.prefix(40)) }), placeholder: t("e.g. Mery", "mf. Mery"))
                Field(t("Phone (for calls and WhatsApp from the team)", "Simu (kwa simu na WhatsApp kutoka kwa timu)"), Binding(get: { phone }, set: { phone = String($0.prefix(30)) }), placeholder: "07… / +254 7…").keyboardType(.phonePad)
                Text(t("Home country", "Nchi ya nyumbani")).font(.mont(13, .bold)).foregroundColor(C.ink).padding(.top, 12).padding(.bottom, 6)
                LazyVGrid(columns: [GridItem(.flexible(), spacing: 6), GridItem(.flexible(), spacing: 6)], spacing: 6) {
                    ForEach(COUNTRIES, id: \.0) { k, n in Pill(n, on: home == k) { home = home == k ? "" : k } }
                }
            } else {
                Field(t("Full name", "Jina kamili"), $name)
                Text(t("With the shared team PIN, this is the name your changes go in under.", "Kwa PIN ya timu, haya ndiyo majina yanayoonekana kwenye mabadiliko yako.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 4)
                Text(t("Ask the office for your own PIN to add a phone number and the rest.", "Omba ofisi ikupe PIN yako mwenyewe ili kuongeza namba ya simu na mengine.")).font(.bMedium).foregroundColor(C.text).padding(.top, 12).fixedSize(horizontal: false, vertical: true)
            }
            Btn(t("Save", "Hifadhi"), enabled: !busy, wide: true, busy: busy ? t("Saving…", "Inahifadhi…") : nil) {
                if !own {
                    if name.trimmingCharacters(in: .whitespaces).count < 2 { msg = t("Please write your name.", "Tafadhali andika jina lako."); return }
                    m.prefs.name = name.trimmingCharacters(in: .whitespaces); m.objectWillChange.send(); m.back(); return
                }
                busy = true; msg = nil
                Task {
                    let e = await m.saveProfile(nick: nick, phone: phone, home: home)
                    busy = false
                    if e == nil { MeToast.say(t("Saved ✓", "Imehifadhiwa ✓")); Haptic.ok(); m.back() } else { msg = e }
                }
            }.padding(.top, 18)
            Msg(msg, "bad")
        }
        .onAppear {
            guard !ready else { return }
            ready = true; name = m.prefs.name; nick = me.nick; phone = me.phone; home = me.home.isEmpty ? m.prefs.country : me.home
        }
    }
}

// MARK: - Change my PIN

private struct SetPin: View {
    @EnvironmentObject var m: AppModel
    @State private var cur = ""
    @State private var n1 = ""
    @State private var n2 = ""
    @State private var busy = false
    @State private var msg: String?
    var body: some View {
        let kind = m.me.kind
        VStack(alignment: .leading, spacing: 0) {
            if kind != "app" {
                Centre(kind == "monday" ? t("You sign in with your monday.com account, so there’s no PIN to change. To change your monday.com password, use monday.com.", "Unaingia kwa akaunti yako ya monday.com, kwa hiyo hakuna PIN ya kubadilisha. Kubadilisha nenosiri la monday.com, tumia monday.com.")
                       : kind == "cloudflare" ? t("Your PIN was set up by the office, so only they can change it. Ask an admin for a new one in Office › Team.", "PIN yako iliwekwa na ofisi, kwa hiyo wao pekee wanaweza kuibadilisha. Muombe msimamizi akupe mpya kwenye Ofisi › Timu.")
                       : t("You’re using the shared team PIN. Ask the office for your own PIN, then you can change it here.", "Unatumia PIN ya timu. Omba ofisi ikupe PIN yako mwenyewe, kisha unaweza kuibadilisha hapa."), color: C.text)
            } else {
                PinBox(label: t("Current PIN", "PIN ya sasa"), value: $cur)
                PinBox(label: t("New PIN (4 to 6 digits)", "PIN mpya (tarakimu 4 hadi 6)"), value: $n1)
                PinBox(label: t("New PIN again", "PIN mpya tena"), value: $n2)
                Text(t("Avoid easy ones like 1234 or 0000. Don’t share it: your changes go in under your name.", "Epuka rahisi kama 1234 au 0000. Usimpe mtu: mabadiliko yako yanaingia kwa jina lako.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 10).fixedSize(horizontal: false, vertical: true)
                Btn(t("Change PIN", "Badilisha PIN"), enabled: !busy && cur.count >= 4 && n1.count >= 4, wide: true, busy: busy ? t("Changing…", "Inabadilisha…") : nil) {
                    if n1 != n2 { msg = t("The two new PINs aren’t the same.", "PIN mpya mbili hazifanani."); return }
                    busy = true; msg = nil
                    Task {
                        let e = await m.changePin(current: cur, new: n1)
                        busy = false
                        if e == nil { MeToast.say(t("PIN changed ✓ Use the new one next time.", "PIN imebadilishwa ✓ Tumia mpya wakati ujao.")); Haptic.ok(); m.back() } else { msg = e }
                    }
                }.padding(.top, 16)
                Msg(msg, "bad")
            }
        }
    }
}

/// A hidden box for digits (up to 6).
private struct PinBox: View {
    let label: String; @Binding var value: String
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.mont(13, .bold)).foregroundColor(C.ink).padding(.top, 12)
            SecureField("", text: Binding(get: { value }, set: { value = String($0.filter { $0.isNumber }.prefix(6)) }))
                .keyboardType(.numberPad).textContentType(.oneTimeCode)
                .font(.bLarge).foregroundColor(C.text).padding(.horizontal, 14).frame(minHeight: 52)
                .background(C.card).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.edge, lineWidth: 1))
        }
    }
}

// MARK: - Notifications

private struct SetNotify: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject private var nc = NotifCenter.shared
    @State private var phoneOn = false
    @State private var off: Set<String> = []
    @State private var msg: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 12) {
                SFIcon("bell", size: 22, color: C.hue("repairs").fg).frame(width: 40, height: 40).background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(C.hue("repairs").bg))
                VStack(alignment: .leading, spacing: 1) {
                    Text(t("Alerts on this phone", "Arifa kwenye simu hii")).font(.tSmall).foregroundColor(C.ink)
                    Text(t("Even when the app is closed. iPhone checks now and then when there’s signal (Background App Refresh).", "Hata programu ikiwa imefungwa. iPhone inaangalia mara kwa mara kukiwa na mtandao (Background App Refresh)."))
                        .font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true)
                }.frame(maxWidth: .infinity, alignment: .leading)
                Toggle2(on: phoneOn, label: t("Alerts on this phone", "Arifa kwenye simu hii")) { toggle() }
            }
            if let msg {
                Msg(msg, "warn")
                Btn(t("Open the phone’s Settings", "Fungua Settings ya simu"), light: true, small: true) { Share.open(UIApplication.openSettingsURLString) }.padding(.top, 8)
            }
            MeGroupLabel(t("Tell me about", "Niarifu kuhusu")).padding(.top, 2)
            // with alerts on this phone off, the kinds are greyed out and can't be changed
            if !phoneOn { Text(t("Turn on Alerts on this phone to choose what it tells you about.", "Washa Arifa kwenye simu hii ili uchague inachokuarifu.")).font(.bSmall).foregroundColor(C.muted).padding(.bottom, 6).fixedSize(horizontal: false, vertical: true) }
            ForEach(NOTIF_KINDS.filter { $0.k != "review" || m.snap?.admin == true }, id: \.k) { k in
                Rectangle().fill(C.line).frame(height: 1)
                HStack(spacing: 12) {
                    SFIcon(k.icon, size: 18, color: meHue(k.hue).fg).frame(width: 38, height: 38).background(RoundedRectangle(cornerRadius: 13, style: .continuous).fill(meHue(k.hue).bg))
                    Text(t(k.en, k.sw)).font(.tSmall).foregroundColor(C.ink).frame(maxWidth: .infinity, alignment: .leading).fixedSize(horizontal: false, vertical: true)
                    Toggle2(on: phoneOn && !off.contains(k.k), label: t(k.en, k.sw)) {
                        guard phoneOn else { return }
                        if off.contains(k.k) { off.remove(k.k) } else { off.insert(k.k) }
                        m.prefs.noteOff = off; nc.changed()
                    }.disabled(!phoneOn)
                }.padding(.vertical, 10).opacity(phoneOn ? 1 : 0.42)
            }
        }
        .onAppear { off = m.prefs.noteOff; phoneOn = m.prefs.phoneAlerts && nc.allowed }
        .task { await nc.refreshAllowed(); phoneOn = m.prefs.phoneAlerts && nc.allowed }
    }
    private func toggle() {
        if phoneOn { phoneOn = false; m.prefs.phoneAlerts = false; return }
        Task {
            let ok = await nc.ask()
            phoneOn = ok; m.prefs.phoneAlerts = ok
            if ok { msg = nil; NotifPhone.schedule() }
            else { msg = t("The iPhone didn’t allow notifications. You can allow them in Settings › Notifications › RAFIKI Pin.", "iPhone haikuruhusu arifa. Unaweza kuziruhusu kwenye Settings › Notifications › RAFIKI Pin.") }
        }
    }
}

// MARK: - monday.com, appearance, language, distances

private struct SetMonday: View {
    @EnvironmentObject var m: AppModel
    var body: some View {
        if let s = m.snap, !(s.linked.isEmpty && s.who.isEmpty) {
            VStack(alignment: .leading, spacing: 0) {
                if !s.linked.isEmpty { Text(t("Connected as \(s.linked) ✓", "Imeunganishwa kama \(s.linked) ✓")).font(.tSmall).foregroundColor(C.ok).padding(.bottom, 6) }
                ConnectMonday(snap: s, inline: true)
            }
        } else {
            Text(t("Your changes go in under the shared account. To use your own name, ask the office for your own PIN.", "Mabadiliko yako yanaingia kwa akaunti ya pamoja. Kutumia jina lako, omba ofisi PIN yako mwenyewe.")).font(.bMedium).foregroundColor(C.text).fixedSize(horizontal: false, vertical: true)
        }
    }
}

private struct SetLook: View {
    @EnvironmentObject var m: AppModel
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // the preview: a project row as it shows in the app, in the colours chosen
            VStack(alignment: .leading, spacing: 0) {
                Text(t("PREVIEW", "MFANO")).font(.mont(11, .bold)).tracking(1.4).foregroundColor(C.muted)
                HStack(spacing: 12) {
                    IconTile(icon: "drop", tint: C.hue("visit").fg, bg: C.hue("visit").bg, size: 40)
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Kapsaret Borehole").font(.tSmall).foregroundColor(C.ink)
                        Text("Eldoret · " + (m.prefs.miles ? t("1.5 miles away", "maili 1.5 kutoka ulipo") : t("2.4 km away", "km 2.4 kutoka ulipo"))).font(.bSmall).foregroundColor(C.muted)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                    Tag(t("Working", "Inafanya kazi"), bg: C.okBg, fg: C.ok)
                }.padding(12).background(C.card).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.line)).padding(.top, 8)
                Text(t("Visited 3 days ago by Grace. The pump works and the water is clear.", "Ilitembelewa siku 3 zilizopita na Grace. Pampu inafanya kazi na maji ni safi.")).font(.bMedium).foregroundColor(C.text).padding(.top, 10).padding(.horizontal, 2).fixedSize(horizontal: false, vertical: true)
            }.padding(12).background(C.bg).clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(C.line))
            MeGroupLabel(t("Colours", "Rangi")).padding(.top, 2)
            SetChoice(title: t("Same as phone", "Kama simu"), sub: t("Light or dark, as the phone is set", "Mwanga au giza, kama simu ilivyowekwa"), on: Look.mode.isEmpty, lead: { PhoneSwatch() }) { m.setTheme("") }
            SetChoice(title: t("Light", "Mwanga"), sub: t("Bright and clear, for most days", "Angavu na wazi, kwa siku nyingi"), on: Look.mode == "light", lead: { Swatch(p: LightP) }) { m.setTheme("light") }
            SetChoice(title: t("Dark", "Giza"), sub: t("Easy on the eyes at night", "Rahisi kwa macho usiku"), on: Look.mode == "dark", lead: { Swatch(p: DarkP) }) { m.setTheme("dark") }
            SetChoice(title: t("Sun", "Jua"), sub: t("Black on white, for bright sunshine outdoors", "Nyeusi juu ya nyeupe, kwa jua kali nje"), on: Look.mode == "sun", lead: { Swatch(p: SunP, sun: true) }) { m.setTheme("sun") }
            Centre(t("Changes every screen, straight away. For bigger letters, use the iPhone’s Settings › Display & Brightness › Text Size.", "Inabadilisha kila skrini, papo hapo. Kwa herufi kubwa zaidi, tumia Settings › Display & Brightness › Text Size ya iPhone.")).padding(.top, 12)
        }
    }
}

/// A small sample of the app in some colours: what Light, Dark and Sun look like before you pick one.
private struct Swatch: View {
    let p: Palette; var sun = false
    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            RoundedRectangle(cornerRadius: 2).fill(p.ink).frame(width: 26, height: 4)
            RoundedRectangle(cornerRadius: 2).fill(p.muted).frame(width: 18, height: 3)
            RoundedRectangle(cornerRadius: 3).fill(p.blue).frame(width: 13, height: 5)
        }
        .padding(4).frame(width: 36, height: 28, alignment: .topLeading).background(RoundedRectangle(cornerRadius: 6).fill(p.card))
        .padding(5).frame(width: 46, height: 38).background(RoundedRectangle(cornerRadius: 10).fill(p.bg))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(sun ? Color.black : p.line, lineWidth: 1))
    }
}
/// "Same as phone": half Light, half Dark.
private struct PhoneSwatch: View {
    var body: some View {
        HStack(spacing: 0) {
            ForEach([LightP, DarkP].indices, id: \.self) { i in
                let p = i == 0 ? LightP : DarkP
                VStack(alignment: .leading, spacing: 3) {
                    RoundedRectangle(cornerRadius: 2).fill(p.ink).frame(width: 12, height: 4)
                    RoundedRectangle(cornerRadius: 3).fill(p.blue).frame(width: 9, height: 5)
                }
                .padding(3).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading).background(p.card)
                .padding(.vertical, 5).padding(.leading, i == 0 ? 5 : 0).padding(.trailing, i == 1 ? 5 : 0).background(p.bg)
            }
        }
        .frame(width: 46, height: 38).clipShape(RoundedRectangle(cornerRadius: 10)).overlay(RoundedRectangle(cornerRadius: 10).stroke(C.line))
    }
}

private struct SetLang: View {
    @EnvironmentObject var m: AppModel
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Centre(t("The whole app, and the reports you make, in the language you choose.", "Programu nzima, na ripoti unazotengeneza, kwa lugha unayochagua."))
            SetChoice(title: "English", sub: "Hello! Let’s find today’s water points.", on: !Lang.sw) { m.setLang(false) }
            SetChoice(title: "Kiswahili", sub: "Habari! Tutafute vituo vya maji vya leo.", on: Lang.sw) { m.setLang(true) }
        }
    }
}

private struct SetUnits: View {
    @EnvironmentObject var m: AppModel
    @State private var miles = false
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Centre(t("How far projects are from you, on lists, the map and routes.", "Umbali wa miradi kutoka ulipo, kwenye orodha, ramani na njia."))
            SetChoice(title: t("Kilometres (km)", "Kilomita (km)"), sub: t("e.g. 2.4 km away", "mf. km 2.4 kutoka ulipo"), on: !miles) { miles = false; m.prefs.miles = false; m.objectWillChange.send() }
            SetChoice(title: t("Miles", "Maili"), sub: t("e.g. 1.5 miles away", "mf. maili 1.5 kutoka ulipo"), on: miles) { miles = true; m.prefs.miles = true; m.objectWillChange.send() }
        }.onAppear { miles = m.prefs.miles }
    }
}

// MARK: - Help, sign out, about

private let FAQ: [(String, String, String, String)] = [
    ("How do I pin a project’s location?", "Ninawekaje mahali pa mradi?", "Stand at the site, open the project, then Pin location › Use my location. Check the point on the map and save. You can also tap the map, or paste a Google Maps link.", "Simama kwenye eneo, fungua mradi, kisha Weka mahali › Tumia mahali nilipo. Hakikisha kwenye ramani kisha hifadhi. Unaweza pia kugusa ramani, au kubandika kiungo cha Google Maps."),
    ("How do I report a visit?", "Ninaripotije ziara?", "Open the project › Site visit. Choose how it is, go through the checklist, add photos, then Send.", "Fungua mradi › Ziara. Chagua hali, pitia orodha ya ukaguzi, ongeza picha, kisha Tuma."),
    ("What happens with no signal?", "Nini kinatokea bila mtandao?", "Keep working. Everything is saved on this phone and sent to monday.com by itself when there’s signal. Me shows what is still waiting.", "Endelea kufanya kazi. Kila kitu kinahifadhiwa kwenye simu na kutumwa monday.com chenyewe mtandao ukipatikana. Mimi inaonyesha kinachosubiri."),
    ("Why does my change need approval?", "Kwa nini badiliko langu linahitaji kuidhinishwa?", "If an admin confirmed a location, moving it by more than 500 m goes to the office for approval first. New sites and story moments are checked too.", "Ikiwa msimamizi amethibitisha mahali, kuhamisha zaidi ya mita 500 kunaenda ofisini kuidhinishwa kwanza. Maeneo mapya na hadithi pia hukaguliwa."),
    ("How do I update the app?", "Ninasasishaje programu?", "Me › App updates › Check. New iPhone versions come through TestFlight (or the App Store); the app tells you when one is ready.", "Mimi › Masasisho › Angalia. Matoleo mapya ya iPhone yanakuja kupitia TestFlight (au App Store); programu inakuambia toleo jipya likiwa tayari."),
    ("Why don’t I get phone alerts?", "Kwa nini sipati arifa kwenye simu?", "Check Me › Settings › Notifications, that the iPhone allows RAFIKI Pin to show notifications, and that Background App Refresh is on for it. The iPhone decides when to check; Low Power Mode makes it slower.", "Angalia Mimi › Mipangilio › Arifa, kwamba iPhone inaruhusu RAFIKI Pin kuonyesha arifa, na kwamba Background App Refresh imewashwa. iPhone inaamua wakati wa kuangalia; Low Power Mode inachelewesha."),
    ("I forgot my PIN", "Nimesahau PIN yangu", "Ask an admin in the office. They can give you a new PIN in Office › Team.", "Muombe msimamizi ofisini. Anaweza kukupa PIN mpya kwenye Ofisi › Timu."),
    ("Who can see my phone number?", "Nani anaweza kuona namba yangu ya simu?", "Only the RAFIKI team in this app, so they can call or WhatsApp you from Me & team. It isn’t shown on the website.", "Timu ya RAFIKI pekee kwenye programu hii, ili wakupigie au wakutumie WhatsApp kutoka Mimi na timu. Haionyeshwi kwenye tovuti."),
]

private struct SetHelp: View {
    @State private var open: Set<Int> = [0]
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(FAQ.indices, id: \.self) { i in
                let f = FAQ[i]
                Button { withAnimation(.easeOut(duration: 0.2)) { if open.contains(i) { open.remove(i) } else { open.insert(i) } } } label: {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack(alignment: .top) {
                            Text(t(f.0, f.1)).font(.tSmall).foregroundColor(C.ink).multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
                            Spacer(minLength: 6)
                            Icon("down", size: 18, color: C.muted).rotationEffect(.degrees(open.contains(i) ? 180 : 0))
                        }
                        if open.contains(i) { Text(t(f.2, f.3)).font(.bMedium).foregroundColor(C.text).multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true) }
                    }
                    .padding(14).frame(maxWidth: .infinity, alignment: .leading)
                    .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(C.line)).contentShape(Rectangle())
                }.buttonStyle(.plain).padding(.top, i == 0 ? 0 : 8)
            }
            Btn(t("More questions on the website", "Maswali zaidi kwenye tovuti"), light: true, wide: true) { MeMail.web("/faq") }.padding(.top, 14)
            Rectangle().fill(C.line).frame(height: 1).padding(.top, 16).padding(.bottom, 4)
            SetRow(icon: "spark", hue: "purple", title: t("Show the welcome screens again", "Onyesha skrini za kukaribisha tena"), sub: t("The short tour from the first day", "Utangulizi mfupi wa siku ya kwanza")) { MeNav.shared.welcome = true }
        }
    }
}

private struct SetSignOut: View {
    @EnvironmentObject var m: AppModel
    var body: some View {
        VStack(spacing: 0) {
            Centre(t("Changes waiting to send stay on this phone and go when you sign in again.", "Mabadiliko yanayosubiri yanabaki kwenye simu na kutumwa ukiingia tena."), color: C.text)
            Btn(t("Sign out", "Toka"), wide: true) { m.signOut() }.padding(.top, 16).accessibilityIdentifier("so_go")
            Btn(t("Stay signed in", "Baki ndani"), light: true, wide: true) { m.back() }.padding(.top, 8)
        }
    }
}

private struct SetAbout: View {
    @EnvironmentObject var m: AppModel
    var body: some View {
        VStack(spacing: 0) {
            Wordmark(height: 34)
            Text(t("Version ", "Toleo ") + AppVer.name + " (\(AppVer.build))").font(.tSmall).foregroundColor(C.ink).padding(.top, 14)
            Centre(t("The field app for RAFIKI Outreach: pin projects, report visits and repairs, and keep monday.com up to date, even with no signal.", "Programu ya uwanjani ya RAFIKI Outreach: weka mahali pa miradi, ripoti ziara na matengenezo, na usasishe monday.com, hata bila mtandao.")).padding(.top, 8)
            Button("rafikioutreach.org") { MeMail.web("") }.font(.lLarge).foregroundColor(C.blue2).frame(minHeight: 44).padding(.top, 4)
            Rectangle().fill(C.line).frame(height: 1).padding(.top, 10).padding(.bottom, 4)
            CheckUpdatesRow()
        }
    }
}

/// About › Check for updates: asks the website now; a newer app opens Me, where the update card says how to get it.
private struct CheckUpdatesRow: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject private var up = MeUpdates.shared
    @State private var busy = false
    @State private var failed = false
    @State private var asked = false
    var body: some View {
        let ready = up.ready
        Button {
            if ready { up.focus = true; m.tab("me"); return }
            busy = true; failed = false; asked = true
            Task { let ok = await up.check(m.api); failed = !ok; busy = false; if up.ready { up.focus = true; m.tab("me") } }
        } label: {
            HStack(spacing: 12) {
                IconTile(icon: ready ? "upload" : "refresh", tint: C.hue(ready ? "pin" : "near").fg, bg: C.hue(ready ? "pin" : "near").bg, size: 40)
                VStack(alignment: .leading, spacing: 1) {
                    Text(ready ? t("Update available", "Toleo jipya") + verTail(up.latest?.name ?? "") : t("Check for updates", "Angalia masasisho")).font(.tSmall).foregroundColor(C.ink)
                    Text(busy ? t("Checking…", "Inaangalia…") : failed ? t("Couldn’t check. Needs a signal.", "Haikuweza kuangalia. Inahitaji mtandao.") : ready ? t("Tap to see how to get it", "Gusa kuona jinsi ya kulipata")
                         : asked ? t("You’re up to date ✓", "Uko na toleo jipya ✓") : t("Version ", "Toleo ") + AppVer.name)
                        .font(.bSmall).foregroundColor(failed ? C.bad : (asked && !busy && !ready) ? C.ok : C.muted)
                }.frame(maxWidth: .infinity, alignment: .leading)
                if busy { ProgressView().tint(C.blue) } else { Icon("chev", size: 18, color: C.muted) }
            }.padding(.vertical, 10).padding(.horizontal, 2).contentShape(Rectangle())
        }.buttonStyle(.plain).disabled(busy)
    }
}

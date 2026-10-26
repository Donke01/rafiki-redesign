import SwiftUI
import UserNotifications

// Me › Settings: account, security and alerts, display, help and about, each opening its own page (as on Android and the web).

/// Phone alerts: new notifications are shown as iPhone notifications when the app checks (on open, coming back, and every few minutes).
enum PhoneAlerts {
    static func allowed() async -> Bool {
        let s = await UNUserNotificationCenter.current().notificationSettings()
        return s.authorizationStatus == .authorized || s.authorizationStatus == .provisional
    }
    static func ask() async -> Bool { (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false }
    /// Tells the phone about notifications newer than the last one it was told about.
    @MainActor static func post(_ m: AppModel) async {
        guard m.prefs.phoneAlerts, await allowed() else { return }
        let last = m.prefs.notifiedAt
        let fresh = m.alerts().filter { $0.at > last && alertNew($0, seen: m.prefs.noteSeen, read: m.prefs.noteRead) }
        if last.isEmpty { m.prefs.notifiedAt = m.alerts().first?.at ?? When.now(); return } // the first time: no flood
        for a in fresh.prefix(5) {
            let c = UNMutableNotificationContent()
            c.title = a.by.isEmpty ? a.name.isEmpty ? "RAFIKI Pin" : a.name : a.by
            c.body = a.msg.isEmpty ? a.text : (a.title.isEmpty ? a.msg : a.title + " · " + a.msg)
            c.sound = .default
            if let pid = a.pid { c.userInfo = ["project": pid] }
            try? await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: a.key, content: c, trigger: nil))
        }
        if let newest = fresh.first?.at { m.prefs.notifiedAt = newest }
    }
}

/// Me › Settings: one card with every setting as a row.
struct SettingsCard: View {
    @EnvironmentObject var m: AppModel
    @State private var phoneOn = false
    var body: some View {
        let me = m.snap?.me ?? Me()
        CardV {
            Text(t("Settings", "Mipangilio")).font(.tLarge).foregroundColor(C.ink).wide(.center)
            GroupLabel(t("Account", "Akaunti"))
            if let s = m.snap, m.prefs.session.isEmpty {
                SetRow(icon: "b:monday", hue: "purple", title: t("Your name on monday.com", "Jina lako kwenye monday.com"),
                       sub: !s.linked.isEmpty ? t("Connected as \(s.linked)", "Imeunganishwa kama \(s.linked)") : s.who.isEmpty ? t("Changes go in under the shared account", "Mabadiliko yanaingia kwa akaunti ya pamoja") : t("Not connected · takes 20 seconds", "Haijaunganishwa · sekunde 20")) { go("monday") }
            }
            SetRow(icon: "person", hue: "blue", title: t("Personal details", "Taarifa zangu"), sub: [me.nick.isEmpty ? m.prefs.name : me.nick, me.phone].filter { !$0.isEmpty }.joined(separator: " · ")) { go("profile") }
            SetRow(icon: "lock", hue: "purple", title: me.kind == "monday" ? t("Sign-in", "Kuingia") : t("Change my PIN", "Badilisha PIN yangu"),
                   sub: me.kind == "monday" ? t("Signed in with monday.com", "Umeingia kwa monday.com") : me.kind == "app" ? t("Your own PIN", "PIN yako mwenyewe") : me.kind == "cloudflare" ? t("Set by the office", "Imewekwa na ofisi") : t("Shared team PIN", "PIN ya timu")) { go("pin") }
            GroupLabel(t("Security and alerts", "Usalama na arifa"))
            BioRow()
            SetRow(icon: "bell", hue: "amber", title: t("Notifications", "Arifa"), sub: phoneOn ? t("On this phone too", "Pia kwenye simu hii") : t("In the app only", "Ndani ya programu tu")) { go("notify") }
            ShareRow { go("share") }
            GroupLabel(t("Display", "Mwonekano"))
            SetRow(icon: "sun", hue: "amber", title: t("Appearance", "Mwonekano"), sub: lookText()) { go("look") }
            SetRow(icon: "globe", hue: "blue", title: t("Language", "Lugha"), sub: Lang.sw ? "Kiswahili" : "English") { go("lang") }
            SetRow(icon: "nav", hue: "green", title: t("Distances", "Umbali"), sub: m.prefs.miles ? t("Miles", "Maili") : t("Kilometres (km)", "Kilomita (km)")) { go("units") }
            SetRow(icon: "coins", hue: "green", title: t("Currency", "Sarafu"), sub: Currency.name(Currency.code) + " (" + Currency.code + ")" + (Currency.picked ? "" : " · " + t("automatic", "kiotomatiki"))) { go("cur") }
            GroupLabel(t("Help and support", "Msaada"))
            SetRow(icon: "search", hue: "teal", title: t("Help and FAQs", "Msaada na maswali"), sub: t("Pinning, visits, no signal, updates", "Kuweka mahali, ziara, bila mtandao, masasisho")) { go("help") }
            SetRow(icon: "chat", hue: "green", title: t("Contact the office", "Wasiliana na ofisi"), sub: "info@rafikioutreach.org") { Links.mail("RAFIKI Pin: " + m.prefs.name) }
            SetRow(icon: "alert", hue: "coral", title: t("Report a problem with the app", "Ripoti tatizo la programu"), sub: t("Sends the app version with your message", "Inatuma toleo la programu pamoja na ujumbe wako")) {
                Links.mail("RAFIKI Pin problem", "\n\nWhat happened:\n\n\n---\n\(m.prefs.name) · iPhone app \(AppInfo.version) (\(AppInfo.build)) · \(UIDevice.current.model) · iOS \(UIDevice.current.systemVersion)")
            }
            GroupLabel(t("About", "Kuhusu"))
            SetRow(icon: "drop", hue: "blue", title: t("About RAFIKI Pin", "Kuhusu RAFIKI Pin"), sub: t("Version ", "Toleo ") + AppInfo.version) { go("about") }
            SetRow(icon: "lock", hue: "slate", title: t("Privacy policy", "Sera ya faragha")) { Links.web("/privacy-policy") }
            SetRow(icon: "clip", hue: "slate", title: t("Terms of use", "Masharti ya matumizi")) { Links.web("/terms-of-use") }
            Divider().overlay(C.line).padding(.top, 12).padding(.bottom, 4)
            SetRow(icon: "undo", hue: "coral", title: t("Sign out of this phone", "Toka kwenye simu hii"), sub: t("You’ll need your PIN to come back in", "Utahitaji PIN yako kurudi")) { go("signout") }
        }
        .padding(.top, 14)
        .task { phoneOn = m.prefs.phoneAlerts ? await PhoneAlerts.allowed() : false }
    }
    private func go(_ k: String) { m.go(Place(tab: m.here.tab, sub: "set_" + k)) }
    private func lookText() -> String {
        let col = Look.mode.isEmpty ? t("Same as phone", "Kama simu") : Look.mode == "dark" ? t("Dark", "Giza") : Look.mode == "sun" ? t("Sun", "Jua") : t("Light", "Mwanga")
        let size = ["s": t("small text", "maandishi madogo"), "l": t("large text", "maandishi makubwa"), "xl": t("larger text", "maandishi makubwa zaidi")][m.prefs.textSize] ?? t("normal text", "maandishi ya kawaida")
        return "\(col) · \(size)"
    }
}

/// Settings › Face ID: a switch right in the row.
private struct BioRow: View {
    @EnvironmentObject var m: AppModel
    @State private var bio = false
    @State private var msg: String?
    var body: some View {
        Group {
            if Bio.available || bio {
                HStack(spacing: 12) {
                    IconTile(icon: Bio.face ? "face" : "finger", tint: C.hue("due").fg, bg: C.hue("due").bg, size: 40)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(Bio.face ? t("Face ID unlock", "Fungua kwa Face ID") : t("Touch ID unlock", "Fungua kwa Touch ID")).font(.tSmall).foregroundColor(C.ink)
                        Text(msg ?? (bio ? t("On · asked when the app opens and after a minute away", "Imewashwa · inaulizwa programu ikifunguliwa na baada ya dakika moja") : t("Off · keeps the app private if someone picks up your phone", "Imezimwa · inalinda programu mtu akishika simu yako")))
                            .font(.bSmall).foregroundColor(msg != nil ? C.bad : C.muted)
                    }
                    Spacer(minLength: 8)
                    Toggle2(on: bio, label: bio ? t("Turn off", "Zima") : t("Turn on", "Washa")) {
                        if bio { bio = false; m.prefs.bio = false; msg = nil; return }
                        Task { if await Bio.ask(t("Turn on Face ID unlock", "Washa kufungua kwa Face ID")) { bio = true; m.prefs.bio = true; msg = nil; Haptic.ok() } else { msg = t("Not turned on.", "Haijawashwa.") } }
                    }
                }.padding(.vertical, 10).padding(.horizontal, 2)
            }
        }.onAppear { bio = m.prefs.bio }
    }
}

private let FAQ: [(String, String, String, String)] = [
    ("How do I pin a project’s location?", "Ninawekaje mahali pa mradi?", "Stand at the site, open the project, then Pin location › Use my location. Check the point on the map and save. You can also tap the map, or paste a Google Maps link.", "Simama kwenye eneo, fungua mradi, kisha Weka mahali › Tumia mahali nilipo. Hakikisha kwenye ramani kisha hifadhi. Unaweza pia kugusa ramani, au kubandika kiungo cha Google Maps."),
    ("How do I report a visit?", "Ninaripotije ziara?", "Open the project › Site visit. Tap “I’ve arrived”, choose how it is, go through the checklist, add photos, then Send. Each kind of project has its own checklist.", "Fungua mradi › Ziara. Gusa “Nimefika”, chagua hali, pitia orodha ya ukaguzi, ongeza picha, kisha Tuma. Kila aina ya mradi ina orodha yake."),
    ("What happens with no signal?", "Nini kinatokea bila mtandao?", "Keep working. Everything is saved on this phone and sent to monday.com by itself when there’s signal. Me shows what is still waiting.", "Endelea kufanya kazi. Kila kitu kinahifadhiwa kwenye simu na kutumwa monday.com chenyewe mtandao ukipatikana. Mimi inaonyesha kinachosubiri."),
    ("Why does my change need approval?", "Kwa nini badiliko langu linahitaji kuidhinishwa?", "If an admin confirmed a location, moving it by more than 500 m goes to the office for approval first. New sites and story moments are checked too.", "Ikiwa msimamizi amethibitisha mahali, kuhamisha zaidi ya mita 500 kunaenda ofisini kuidhinishwa kwanza. Maeneo mapya na hadithi pia hukaguliwa."),
    ("How do I update the app?", "Ninasasishaje programu?", "Updates come through TestFlight or the App Store. Open TestFlight and tap Update when a new version shows.", "Masasisho yanakuja kupitia TestFlight au App Store. Fungua TestFlight na ugusa Update toleo jipya likionekana."),
    ("Why don’t I get phone alerts?", "Kwa nini sipati arifa kwenye simu?", "Check Me › Settings › Notifications, and that iPhone Settings › Notifications allows RAFIKI Pin. The app checks when it opens and every few minutes while it’s in use.", "Angalia Mimi › Mipangilio › Arifa, na kwamba Settings › Notifications ya iPhone inaruhusu RAFIKI Pin. Programu inaangalia ikifunguliwa na kila dakika chache ikitumika."),
    ("I forgot my PIN", "Nimesahau PIN yangu", "Ask an admin in the office. They can give you a new PIN in Office › Team.", "Muombe msimamizi ofisini. Anaweza kukupa PIN mpya kwenye Ofisi › Timu."),
    ("Who can see my phone number?", "Nani anaweza kuona namba yangu ya simu?", "Only the RAFIKI team in this app, so they can call or WhatsApp you from Me & team. It isn’t shown on the website.", "Timu ya RAFIKI pekee kwenye programu hii, ili wakupigie au wakutumie WhatsApp kutoka Mimi na timu. Haionyeshwi kwenye tovuti."),
]

/// One Settings page (opened from the Settings card).
struct SettingsPage: View {
    @EnvironmentObject var m: AppModel
    let which: String
    @State private var msg: (String, String)?
    @State private var busy = false
    @State private var name = ""; @State private var nick = ""; @State private var phone = ""; @State private var home = ""
    @State private var cur = ""; @State private var n1 = ""; @State private var n2 = ""
    @State private var phoneOn = false
    @State private var off: Set<String> = []
    @State private var faqOpen: Set<Int> = [0]
    @State private var miles = false
    var body: some View {
        let me = m.snap?.me ?? Me()
        ScrollView {
            VStack(spacing: 0) {
                Text(title(me)).font(.hLarge).foregroundColor(C.ink).multilineTextAlignment(.center).wide(.center).padding(.top, 4).padding(.bottom, 8)
                CardV {
                    content(me)
                    if let msg { Msg(msg.1, msg.0) }
                }.padding(.top, 8)
            }.padding(16).padding(.bottom, 100)
        }
        .onAppear {
            name = m.prefs.name; nick = me.nick; phone = me.phone; home = me.home.isEmpty ? m.prefs.country : me.home
            off = m.prefs.noteOff; miles = m.prefs.miles
        }
        .task { phoneOn = m.prefs.phoneAlerts ? await PhoneAlerts.allowed() : false }
    }

    private func title(_ me: Me) -> String {
        switch which {
        case "profile": return t("Personal details", "Taarifa zangu")
        case "pin": return me.kind == "monday" ? t("Sign-in", "Kuingia") : t("Change my PIN", "Badilisha PIN yangu")
        case "notify": return t("Notification settings", "Mipangilio ya arifa")
        case "monday": return t("Your name on monday.com", "Jina lako kwenye monday.com")
        case "help": return t("Help and FAQs", "Msaada na maswali")
        case "signout": return t("Sign out of this phone?", "Toka kwenye simu hii?")
        case "look": return t("Appearance", "Mwonekano")
        case "lang": return t("Language", "Lugha")
        case "units": return t("Distances", "Umbali")
        case "cur": return t("Currency", "Sarafu")
        default: return t("About RAFIKI Pin", "Kuhusu RAFIKI Pin")
        }
    }

    @ViewBuilder private func content(_ me: Me) -> some View {
        switch which {
        case "profile": profile(me)
        case "pin": pin(me)
        case "notify": notify()
        case "help": help()
        case "look": LookPage()
        case "monday":
            if (m.snap?.linked ?? "").isEmpty && (m.snap?.who ?? "").isEmpty {
                Text(t("Your changes go in under the shared account. To use your own name, ask the office for your own PIN.", "Mabadiliko yako yanaingia kwa akaunti ya pamoja. Kutumia jina lako, omba ofisi PIN yako mwenyewe.")).font(.bMedium).foregroundColor(C.text)
            } else {
                if let l = m.snap?.linked, !l.isEmpty { Text(t("Connected as \(l) ✓", "Imeunganishwa kama \(l) ✓")).font(.tSmall).foregroundColor(C.ok).padding(.bottom, 6) }
                ConnectMonday(snap: m.snap!, inline: true)
            }
        case "lang":
            Text(t("The whole app, and the reports you make, in the language you choose.", "Programu nzima, na ripoti unazotengeneza, kwa lugha unayochagua.")).font(.bMedium).foregroundColor(C.muted).multilineTextAlignment(.center).wide(.center)
            SetChoice(title: "English", sub: "Hello! Let’s find today’s water points.", on: !Lang.sw) { m.setLang(false) }
            SetChoice(title: "Kiswahili", sub: "Habari! Tutafute vituo vya maji vya leo.", on: Lang.sw) { m.setLang(true) }
        case "units":
            Text(t("How far projects are from you, on lists, the map and routes.", "Umbali wa miradi kutoka ulipo, kwenye orodha, ramani na njia.")).font(.bMedium).foregroundColor(C.muted).multilineTextAlignment(.center).wide(.center)
            SetChoice(title: t("Kilometres (km)", "Kilomita (km)"), sub: t("e.g. 2.4 km away", "mf. km 2.4 kutoka ulipo"), on: !miles) { miles = false; m.prefs.miles = false; m.look += 1 }
            SetChoice(title: t("Miles", "Maili"), sub: t("e.g. 1.5 miles away", "mf. maili 1.5 kutoka ulipo"), on: miles) { miles = true; m.prefs.miles = true; m.look += 1 }
        case "cur":
            // Settings › Currency: automatic (where the phone is) or any of these; costs are still typed in KES
            Text(t("Costs and budgets show in this money. Converted amounts show ≈ with the KES amount next to them; you still type costs in KES.", "Gharama na bajeti zinaonyeshwa kwa pesa hii. Kiasi kilichobadilishwa kinaonyesha ≈ pamoja na kiasi cha KES; bado unaandika gharama kwa KES.")).font(.bMedium).foregroundColor(C.muted).multilineTextAlignment(.center).wide(.center)
            SetChoice(title: t("Automatic", "Kiotomatiki"), sub: t("The money of the country you’re in", "Pesa ya nchi uliyopo"), on: !Currency.picked) { Currency.set(nil); m.look += 1 }
            ForEach(Currency.list, id: \.code) { c in
                SetChoice(title: Currency.name(c.code) + " (" + c.code + ")", sub: c.code == "KES" ? t("As the costs are entered", "Kama gharama zinavyoingizwa") : "KES\u{00A0}1,000 ≈ " + Currency.fmt(1000, from: "KES", to: c.code),
                          on: Currency.picked && Currency.code == c.code, action: { Currency.set(c.code); m.look += 1 }, lead: { Text(c.flag).font(.system(size: 26)) })
            }
            Text(Currency.at.isEmpty ? t("Approximate rates until the app is online.", "Viwango vya makadirio hadi programu iwe mtandaoni.") : t("Rates from \(Dates.day(Currency.at)), updated daily.", "Viwango vya \(Dates.day(Currency.at)), husasishwa kila siku."))
                .font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.center).wide(.center).padding(.top, 12)
        case "signout":
            Text(t("Changes waiting to send stay on this phone and go when you sign in again.", "Mabadiliko yanayosubiri yanabaki kwenye simu na kutumwa ukiingia tena.")).font(.bMedium).foregroundColor(C.text).multilineTextAlignment(.center).wide(.center)
            Btn(t("Sign out", "Toka"), wide: true) { m.signOut() }.padding(.top, 16)
            Btn(t("Stay signed in", "Baki ndani"), light: true, wide: true) { m.back() }.padding(.top, 8)
        default: about()
        }
    }

    @ViewBuilder private func profile(_ me: Me) -> some View {
        let own = !m.prefs.name.isEmpty && me.kind != "shared"
        if own {
            VStack(alignment: .leading, spacing: 6) {
                Text(t("Full name", "Jina kamili")).font(.mont(13, .bold)).foregroundColor(C.ink).padding(.top, 12)
                Text(name).font(.bLarge).foregroundColor(C.muted).padding(.horizontal, 14).frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
                    .background(C.tint.opacity(0.5)).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
        } else { Field(t("Full name", "Jina kamili"), $name) }
        Text(own ? t("Your name comes from your sign-in. Ask the office to change it.", "Jina lako linatoka kwenye kuingia kwako. Omba ofisi ibadilishe.") : t("With the shared team PIN, this is the name your changes go in under.", "Kwa PIN ya timu, haya ndiyo majina yanayoonekana kwenye mabadiliko yako."))
            .font(.bSmall).foregroundColor(C.muted).padding(.top, 4)
        if own {
            Field(t("What the team calls you (optional)", "Jina la kuitwa na timu (si lazima)"), $nick, placeholder: t("e.g. Mery", "mf. Mery"))
            Field(t("Phone (for calls and WhatsApp from the team)", "Simu (kwa simu na WhatsApp kutoka kwa timu)"), $phone, placeholder: "07… / +254 7…").keyboardType(.phonePad)
            Text(t("Home country", "Nchi ya nyumbani")).font(.mont(13, .bold)).foregroundColor(C.ink).padding(.top, 12).padding(.bottom, 6)
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 6), GridItem(.flexible(), spacing: 6)], spacing: 6) {
                ForEach(COUNTRIES, id: \.0) { k, n in Pill(n, on: home == k) { home = home == k ? "" : k } }
            }
        } else {
            Text(t("Ask the office for your own PIN to add a phone number and the rest.", "Omba ofisi ikupe PIN yako mwenyewe ili kuongeza namba ya simu na mengine.")).font(.bMedium).foregroundColor(C.text).padding(.top, 12)
        }
        Btn(busy ? t("Saving…", "Inahifadhi…") : t("Save", "Hifadhi"), enabled: !busy, wide: true) {
            if !own {
                if name.trimmingCharacters(in: .whitespaces).count < 2 { msg = ("bad", t("Please write your name.", "Tafadhali andika jina lako.")); return }
                m.prefs.name = name.trimmingCharacters(in: .whitespaces); m.back(); return
            }
            busy = true
            Task { let e = await m.saveProfile(nick: String(nick.prefix(40)), phone: String(phone.prefix(30)), home: home); busy = false; if e == nil { m.say(t("Saved ✓", "Imehifadhiwa ✓")); m.back() } else { msg = ("bad", e!) } }
        }.padding(.top, 18)
    }

    @ViewBuilder private func pin(_ me: Me) -> some View {
        if me.kind != "app" {
            Text(me.kind == "monday" ? t("You sign in with your monday.com account, so there’s no PIN to change. To change your monday.com password, use monday.com.", "Unaingia kwa akaunti yako ya monday.com, kwa hiyo hakuna PIN ya kubadilisha. Kubadilisha nenosiri la monday.com, tumia monday.com.")
                 : me.kind == "cloudflare" ? t("Your PIN was set up by the office, so only they can change it. Ask an admin for a new one in Office › Team.", "PIN yako iliwekwa na ofisi, kwa hiyo wao pekee wanaweza kuibadilisha. Muombe msimamizi akupe mpya kwenye Ofisi › Timu.")
                 : t("You’re using the shared team PIN. Ask the office for your own PIN, then you can change it here.", "Unatumia PIN ya timu. Omba ofisi ikupe PIN yako mwenyewe, kisha unaweza kuibadilisha hapa."))
                .font(.bMedium).foregroundColor(C.text).multilineTextAlignment(.center).wide(.center)
        } else {
            PinBox(label: t("Current PIN", "PIN ya sasa"), value: $cur)
            PinBox(label: t("New PIN (4 to 6 digits)", "PIN mpya (tarakimu 4 hadi 6)"), value: $n1)
            PinBox(label: t("New PIN again", "PIN mpya tena"), value: $n2)
            Text(t("Avoid easy ones like 1234 or 0000. Don’t share it: your changes go in under your name.", "Epuka rahisi kama 1234 au 0000. Usimpe mtu: mabadiliko yako yanaingia kwa jina lako.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 10)
            Btn(busy ? t("Changing…", "Inabadilisha…") : t("Change PIN", "Badilisha PIN"), enabled: !busy && cur.count >= 4 && n1.count >= 4, wide: true) {
                if n1 != n2 { msg = ("bad", t("The two new PINs aren’t the same.", "PIN mpya mbili hazifanani.")); return }
                busy = true
                Task { let e = await m.changePin(current: cur, new: n1); busy = false; if e == nil { m.say(t("PIN changed ✓ Use the new one next time.", "PIN imebadilishwa ✓ Tumia mpya wakati ujao.")); m.back() } else { msg = ("bad", e!) } }
            }.padding(.top, 16)
        }
    }

    @ViewBuilder private func notify() -> some View {
        HStack(spacing: 12) {
            IconTile(icon: "bell", tint: C.named("amber").fg, bg: C.named("amber").bg, size: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(t("Alerts on this phone", "Arifa kwenye simu hii")).font(.tSmall).foregroundColor(C.ink)
                Text(t("As iPhone notifications. The app checks when it opens and every few minutes while you use it.", "Kama arifa za iPhone. Programu inaangalia ikifunguliwa na kila dakika chache ukiitumia.")).font(.bSmall).foregroundColor(C.muted)
            }
            Spacer(minLength: 8)
            Toggle2(on: phoneOn, label: t("Alerts on this phone", "Arifa kwenye simu hii")) {
                if phoneOn { phoneOn = false; m.prefs.phoneAlerts = false; return }
                Task {
                    let ok = await PhoneAlerts.allowed() ? true : await PhoneAlerts.ask()
                    phoneOn = ok; m.prefs.phoneAlerts = ok
                    if !ok { msg = ("warn", t("iPhone didn’t allow notifications. You can allow them in Settings › Notifications › RAFIKI Pin.", "iPhone haikuruhusu arifa. Unaweza kuziruhusu kwenye Settings › Notifications › RAFIKI Pin.")) }
                }
            }
        }
        GroupLabel(t("Tell me about", "Niarifu kuhusu"), top: 18)
        if !phoneOn { Text(t("Turn on Alerts on this phone to choose what it tells you about.", "Washa Arifa kwenye simu hii ili uchague inachokuarifu.")).font(.bSmall).foregroundColor(C.muted).padding(.bottom, 6) }
        ForEach(ALERT_KINDS.filter { $0.0 != "review" || m.snap?.admin == true }, id: \.0) { k in
            Divider().overlay(C.line)
            HStack(spacing: 12) {
                IconTile(icon: k.3, tint: C.named(k.4).fg, bg: C.named(k.4).bg, size: 38)
                Text(t(k.1, k.2)).font(.tSmall).foregroundColor(C.ink)
                Spacer(minLength: 8)
                Toggle2(on: phoneOn && !off.contains(k.0), label: t(k.1, k.2)) {
                    guard phoneOn else { return }
                    if off.contains(k.0) { off.remove(k.0) } else { off.insert(k.0) }
                    m.prefs.noteOff = off; m.noteChanged()
                }
            }.padding(.vertical, 10).opacity(phoneOn ? 1 : 0.42)
        }
    }

    @ViewBuilder private func help() -> some View {
        ForEach(FAQ.indices, id: \.self) { i in
            let f = FAQ[i], open = faqOpen.contains(i)
            Button { withAnimation(.easeOut(duration: 0.2)) { if open { faqOpen.remove(i) } else { faqOpen.insert(i) } } } label: {
                VStack(alignment: .leading, spacing: 8) {
                    HStack { Text(t(f.0, f.1)).font(.tSmall).foregroundColor(C.ink).multilineTextAlignment(.leading); Spacer(); Icon("down", size: 18, color: C.muted).rotationEffect(.degrees(open ? 180 : 0)) }
                    if open { Text(t(f.2, f.3)).font(.bMedium).foregroundColor(C.text).multilineTextAlignment(.leading) }
                }
                .padding(14).wide().overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(C.line, lineWidth: 1)).contentShape(Rectangle())
            }.buttonStyle(.plain).padding(.top, i == 0 ? 0 : 8)
        }
        Btn(t("More questions on the website", "Maswali zaidi kwenye tovuti"), light: true, wide: true) { Links.web("/faq") }.padding(.top, 14)
        Divider().overlay(C.line).padding(.top, 16).padding(.bottom, 4)
        SetRow(icon: "spark", hue: "purple", title: t("Show the welcome screens again", "Onyesha skrini za kukaribisha tena"), sub: t("The short tour from the first day", "Utangulizi mfupi wa siku ya kwanza")) { m.replayOnb = true }
    }

    @ViewBuilder private func about() -> some View {
        VStack(spacing: 0) {
            Wordmark(size: 26)
            Text(t("Version ", "Toleo ") + AppInfo.version + " (" + AppInfo.build + ")").font(.tSmall).foregroundColor(C.ink).padding(.top, 14)
            Text(t("The field app for RAFIKI Outreach: pin projects, report visits and repairs, and keep monday.com up to date, even with no signal.", "Programu ya uwanjani ya RAFIKI Outreach: weka mahali pa miradi, ripoti ziara na matengenezo, na usasishe monday.com, hata bila mtandao."))
                .font(.bMedium).foregroundColor(C.muted).multilineTextAlignment(.center).padding(.top, 8)
            Button { Links.web("") } label: { Text("rafikioutreach.org").font(.lLarge).foregroundColor(C.blue2) }.buttonStyle(.plain).padding(.top, 12)
        }.wide(.center)
    }
}

/// A PIN box: digits only, hidden.
private struct PinBox: View {
    let label: String; @Binding var value: String
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.mont(13, .bold)).foregroundColor(C.ink).padding(.top, 12)
            SecureField("", text: Binding(get: { value }, set: { value = String($0.filter(\.isNumber).prefix(6)) }))
                .keyboardType(.numberPad).font(.bLarge).foregroundColor(C.text)
                .padding(.horizontal, 14).frame(minHeight: 52).background(C.card)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.edge, lineWidth: 1))
        }
    }
}

/// Settings › Appearance: a live preview, then colours and text size.
private struct LookPage: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.colorScheme) var scheme
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 0) {
                Text(t("PREVIEW", "MFANO")).font(.mont(11, .heavy)).tracking(1.4).foregroundColor(C.muted)
                HStack(spacing: 12) {
                    IconTile(icon: "drop", tint: C.hue("visit").fg, bg: C.hue("visit").bg, size: 40)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Kapsaret Borehole").font(.tSmall).foregroundColor(C.ink)
                        Text("Eldoret · " + (m.prefs.miles ? t("1.5 miles away", "maili 1.5 kutoka ulipo") : t("2.4 km away", "km 2.4 kutoka ulipo"))).font(.bSmall).foregroundColor(C.muted)
                    }
                    Spacer(); Tag(t("Working", "Inafanya kazi"), bg: C.okBg, fg: C.ok)
                }
                .padding(12).background(C.card).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.line, lineWidth: 1)).padding(.top, 8)
                Text(t("Visited 3 days ago by Grace. The pump works and the water is clear.", "Ilitembelewa siku 3 zilizopita na Grace. Pampu inafanya kazi na maji ni safi.")).font(.bMedium).foregroundColor(C.text).padding(.top, 10).padding(.horizontal, 2)
            }
            .padding(12).background(C.bg).clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(C.line, lineWidth: 1))
            GroupLabel(t("Colours", "Rangi"), top: 18)
            SetChoice(title: t("Same as phone", "Kama simu"), sub: t("Light or dark, as the phone is set", "Mwanga au giza, kama simu ilivyowekwa"), on: Look.mode.isEmpty, action: { m.setTheme("") }) { PhoneSwatch() }
            ForEach([("light", t("Light", "Mwanga"), t("Bright and clear, for most days", "Angavu na wazi, kwa siku nyingi"), LightP),
                     ("dark", t("Dark", "Giza"), t("Easy on the eyes at night", "Rahisi kwa macho usiku"), DarkP),
                     ("sun", t("Sun", "Jua"), t("Black on white, for bright sunshine outdoors", "Nyeusi juu ya nyeupe, kwa jua kali nje"), SunP)], id: \.0) { k, n, sub, p in
                SetChoice(title: n, sub: sub, on: Look.mode == k, action: { m.setTheme(k) }) { Swatch(p: p) }
            }
            GroupLabel(t("Text size", "Ukubwa wa maandishi"), top: 18)
            HStack(spacing: 8) {
                ForEach([("s", t("Small", "Ndogo"), 14.0), ("", t("Normal", "Kawaida"), 17.0), ("l", t("Large", "Kubwa"), 20.0), ("xl", t("Larger", "Kubwa zaidi"), 23.0)], id: \.0) { k, l, fs in
                    let on = m.prefs.textSize == k
                    Button { Haptic.tap(); m.prefs.textSize = k; m.look += 1 } label: {
                        VStack(spacing: 4) {
                            Text("Aa").font(.mont(fs, .heavy)).foregroundColor(C.ink).frame(minHeight: 30, alignment: .bottom)
                            Text(l).font(.lSmall).foregroundColor(on ? C.ink : C.muted).multilineTextAlignment(.center).lineLimit(2)
                        }
                        .padding(.horizontal, 4).padding(.vertical, 10).frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background(on ? C.tint : Color.clear).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(on ? C.blue : C.line, lineWidth: on ? 2 : 1))
                    }.buttonStyle(.plain)
                }
            }.fixedSize(horizontal: false, vertical: true).padding(.top, 8)
            Text(t("Changes every screen, straight away.", "Inabadilisha kila skrini, papo hapo.")).font(.bSmall).foregroundColor(C.muted).wide(.center).padding(.top, 8)
        }
    }
}

private struct Swatch: View {
    let p: Palette
    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Capsule().fill(p.ink).frame(width: 22, height: 4)
            Capsule().fill(p.muted).frame(width: 15, height: 3)
            Capsule().fill(p.blue).frame(width: 11, height: 5)
        }
        .padding(4).frame(width: 36, height: 28, alignment: .topLeading).background(p.card).clipShape(RoundedRectangle(cornerRadius: 6))
        .padding(5).background(p.bg).clipShape(RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(p.sun ? Color.black : p.line, lineWidth: 1))
    }
}
private struct PhoneSwatch: View {
    var body: some View {
        HStack(spacing: 0) {
            ForEach([LightP, DarkP].indices, id: \.self) { i in
                let p = i == 0 ? LightP : DarkP
                VStack(alignment: .leading, spacing: 3) { Capsule().fill(p.ink).frame(width: 12, height: 4); Capsule().fill(p.blue).frame(width: 9, height: 5) }
                    .padding(3).frame(width: 18, height: 28, alignment: .topLeading).background(p.card)
                    .padding(.leading, i == 0 ? 5 : 0).padding(.trailing, i == 1 ? 5 : 0).padding(.vertical, 5).background(p.bg)
            }
        }.clipShape(RoundedRectangle(cornerRadius: 10)).overlay(RoundedRectangle(cornerRadius: 10).stroke(C.line, lineWidth: 1))
    }
}

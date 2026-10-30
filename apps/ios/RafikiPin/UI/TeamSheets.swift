import SwiftUI
import UIKit

// The contact sheet's one-step sheets (Android Contact.kt, TeamPerson.kt): one field and Save, a short list, a PIN shown
// once, the app invite, make or remove admin, block, sign out of all phones… ✕ or a swipe closes only that one.

/// A small sheet that slides up over the contact sheet, with ✕.
struct TeamSubSheet<Content: View>: View {
    @Environment(\.dismiss) private var dismiss
    @ViewBuilder var content: (_ close: @escaping () -> Void) -> Content
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack { Spacer(); CloseX { dismiss() } }
                content({ dismiss() })
            }.padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 28)
        }.storySheet()
    }
}

/// The small sheet's head: an icon tile and the title, centred.
struct TeamSubHead: View {
    let icon: String; let hue: String; let title: String; var sub = ""; var red = false
    var body: some View {
        let hu = meHue(hue)
        VStack(spacing: 0) {
            SFIcon(icon, size: 28, color: red ? C.bad : hu.fg).frame(width: 56, height: 56).background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(red ? C.badBg : hu.bg))
            Text(title).font(.tLarge).foregroundColor(C.ink).multilineTextAlignment(.center).padding(.top, 10).fixedSize(horizontal: false, vertical: true)
            if !sub.isEmpty { Text(sub).font(.bMedium).foregroundColor(C.muted).multilineTextAlignment(.center).padding(.top, 2).fixedSize(horizontal: false, vertical: true) }
        }.frame(maxWidth: .infinity).padding(.horizontal, 16).padding(.top, -24)
    }
}

/// What will happen, as a short list.
struct TeamLines: View {
    let lines: [String]; var red = false; var icon = "checkmark"
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(lines, id: \.self) { l in
                HStack(alignment: .top, spacing: 8) {
                    SFIcon(icon, size: 16, color: C.ink).padding(.top, 2)
                    Text(l).font(.bMedium).foregroundColor(C.ink).fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                }
            }
        }.padding(14).frame(maxWidth: .infinity, alignment: .leading).background(red ? C.badBg : C.hue("slate").bg).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).padding(.top, 14)
    }
}

/// Cancel and a yes button, side by side.
struct TeamYesNo: View {
    let yes: String; let busy: Bool; var red = false; let no: () -> Void; let go: () -> Void
    var body: some View {
        HStack(spacing: 8) {
            Btn(t("Cancel", "Ghairi"), light: true, small: true, wide: true, action: no)
            if red { RedBtn(text: yes, busy: busy, action: go) }
            else { Btn(yes, enabled: !busy, small: true, wide: true, busy: busy ? t("Working…", "Inafanya kazi…") : nil, action: go) }
        }.padding(.top, 14)
    }
}

/// A red button for what can't easily be undone (block, remove).
struct RedBtn: View {
    let text: String; let busy: Bool; let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if busy { ProgressView().progressViewStyle(.circular).tint(.white).frame(width: 18, height: 18) }
                Text(busy ? t("Working…", "Inafanya kazi…") : text).font(.mont(14, .bold)).foregroundColor(.white).multilineTextAlignment(.center)
            }
            .padding(.horizontal, 16).frame(maxWidth: .infinity, minHeight: 44)
            .background(Capsule().fill(busy ? C.off : C.bad))
        }.buttonStyle(Press()).disabled(busy)
    }
}

/// team-update with everything the server keeps (it replaces the whole entry), changing only [field].
private func teamUpdateBody(_ p: TeamPerson, _ field: String, _ value: Any, admin: Bool) -> JSON {
    var b: JSON = ["action": "team-update", "name": p.name]
    if admin { b["home"] = p.home; b["admin"] = p.admin; b["email"] = p.email; b["phone"] = p.phone; b["role"] = p.role }
    else { b["email"] = p.email; b["phone"] = p.phone }   // a person editing their own e-mail and phone
    b[field] = value
    return b
}

/// One field, Save, and (for e-mail and phone) Remove: name, e-mail, phone, or role/title.
struct TeamFieldSheet: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.dismiss) private var dismiss
    let p: TeamPerson; let field: String; let me: Bool; let admin: Bool; let profile: Bool; let saved: (String) -> Void
    @State private var v = ""
    @State private var busy = false
    @State private var msg: String?
    private var start: String { field == "name" ? p.name : field == "email" ? p.email : field == "phone" ? p.phone : p.role }
    var body: some View {
        TeamSubSheet { _ in
            head
            Field(label, $v, placeholder: placeholder).keyboardType(field == "phone" ? .phonePad : field == "email" ? .emailAddress : .default)
                .textInputAutocapitalization(field == "email" ? .never : .words).autocorrectionDisabled(field != "role")
            Btn(t("Save", "Hifadhi"), icon: "check", enabled: !busy && v.trimmingCharacters(in: .whitespaces) != start.trimmingCharacters(in: .whitespaces), wide: true,
                busy: busy ? t("Saving…", "Inahifadhi…") : nil) { send(v) }.padding(.top, 14)
            if (field == "email" || field == "phone") && !start.isEmpty {
                Btn(field == "email" ? t("Remove e-mail", "Ondoa barua pepe") : t("Remove phone", "Ondoa simu"), icon: "trash", enabled: !busy, light: true, wide: true) { send("") }.padding(.top, 8)
            }
            Msg(msg, "bad")
        }
        .onAppear { v = start }
    }
    @ViewBuilder private var head: some View {
        switch field {
        case "name": TeamSubHead(icon: "square.and.pencil", hue: "blue", title: t("Rename", "Badilisha jina"), sub: t("Their pins, visits and repair jobs stay linked to them.", "Mahali, ziara na kazi zake za matengenezo zinabaki zimeunganishwa naye."))
        case "email": TeamSubHead(icon: "envelope", hue: "teal", title: start.isEmpty ? t("Add e-mail", "Ongeza barua pepe") : t("Change e-mail", "Badilisha barua pepe"), sub: t("For alerts, invites and e-mails from the Office.", "Kwa taarifa, mialiko na barua pepe kutoka Ofisi."))
        case "phone": TeamSubHead(icon: "phone", hue: "green", title: start.isEmpty ? t("Add phone", "Ongeza simu") : t("Change phone", "Badilisha simu"), sub: t("The team sees it, for calls and WhatsApp.", "Timu inaiona, kwa kupiga na WhatsApp."))
        default: TeamSubHead(icon: "person.2", hue: "slate", title: t("Role or title", "Cheo au kazi"), sub: t("Optional. Shown under their name.", "Si lazima. Inaonyeshwa chini ya jina lake."))
        }
    }
    private var label: String { field == "name" ? t("Name", "Jina") : field == "email" ? t("E-mail", "Barua pepe") : field == "phone" ? t("Phone", "Simu") : t("Role or title", "Cheo au kazi") }
    private var placeholder: String { field == "name" ? t("e.g. Grace Wanjiru", "mf. Grace Wanjiru") : field == "email" ? "name@rafikioutreach.org" : field == "phone" ? "+254 712 345 678" : t("e.g. Field officer, Kitale", "mf. Afisa wa uwanjani, Kitale") }

    func send(_ value: String) {
        let x = value.trimmingCharacters(in: .whitespaces)
        if field == "name" && x.isEmpty { msg = t("Type a name.", "Andika jina."); return }
        if field == "email" && !x.isEmpty && x.range(of: #"^[^@\s]+@[^@\s]+\.[^@\s]+$"#, options: .regularExpression) == nil { msg = t("That doesn’t look like an e-mail address.", "Hiyo haionekani kama anwani ya barua pepe."); return }
        if field == "phone" && !x.isEmpty && x.filter({ $0.isNumber }).count < 7 { msg = t("That phone number looks too short.", "Namba hiyo ya simu inaonekana fupi mno."); return }
        if x == start { dismiss(); return }
        busy = true; msg = nil
        Task {
            // your own phone when your PIN isn't from the app: Me › Settings › Personal details
            if profile {
                let me0 = m.me
                let err = await m.saveProfile(nick: me0.nick, phone: x, home: me0.home.isEmpty ? p.home : me0.home)
                busy = false
                if err == nil { saved(x); dismiss() } else { msg = err }
                return
            }
            let req: JSON = field == "name" ? ["action": "team-rename", "name": p.name, "to": x] : teamUpdateBody(p, field, x, admin: admin)
            let r = await m.api.admin(req)
            busy = false
            if r.ok { saved(x); dismiss() } else { msg = meReason(r.reason) }
        }
    }
}

/// Home country: a short list to tap, then Save.
struct TeamHomeSheet: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.dismiss) private var dismiss
    let p: TeamPerson; let saved: () -> Void
    @State private var v = ""
    @State private var busy = false
    @State private var msg: String?
    var body: some View {
        TeamSubSheet { _ in
            TeamSubHead(icon: "mappin.and.ellipse", hue: "green", title: t("Home country", "Nchi ya nyumbani"), sub: t("Their map and lists start in this country.", "Ramani na orodha zao zinaanzia nchi hii."))
            VStack(spacing: 0) {
                ForEach([("", t("All countries", "Nchi zote"))] + COUNTRIES, id: \.0) { k, l in
                    Button { v = k } label: {
                        HStack { Text(l).font(.bLarge).foregroundColor(C.ink); Spacer(); if v == k { Icon("check", size: 20, color: C.blue) } }
                            .padding(.horizontal, 14).frame(minHeight: 52).contentShape(Rectangle())
                    }.buttonStyle(.plain).accessibilityAddTraits(v == k ? .isSelected : [])
                }
            }.overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.line)).padding(.top, 12)
            Btn(t("Save", "Hifadhi"), icon: "check", enabled: !busy && v != p.home, wide: true, busy: busy ? t("Saving…", "Inahifadhi…") : nil) {
                busy = true; msg = nil
                Task {
                    let r = await m.api.admin(teamUpdateBody(p, "home", v, admin: true))
                    busy = false
                    if r.ok { saved(); dismiss() } else { msg = meReason(r.reason) }
                }
            }.padding(.top, 14)
            Msg(msg, "bad")
        }
        .onAppear { v = p.home }
    }
}

/// The message that carries a new PIN to the person (sent by the admin, from their own phone).
func teamPinMessage(_ who: String, _ pin: String) -> String {
    let first = who.split(separator: " ").first.map(String.init) ?? who
    return t("Hi \(first), your RAFIKI Pin PIN is \(pin). Open the app (or pin.rafikioutreach.org) and sign in with it. Please keep it to yourself.",
             "Habari \(first), PIN yako ya RAFIKI Pin ni \(pin). Fungua programu (au pin.rafikioutreach.org) uingie nayo. Tafadhali usimpe mtu mwingine.")
}

/// A new PIN: shown for a minute with Copy, WhatsApp and SMS, then hidden for good.
struct TeamPinShown: View {
    let who: String; let pin: String; var phone = ""; var home = ""
    @State private var left = 60
    @State private var copied = false
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(t("PIN for ", "PIN ya ") + who).font(.tSmall).foregroundColor(left > 0 ? C.ok : C.muted)
            if left <= 0 {
                Text(t("Hidden now, to keep it safe. If they didn’t get it, make a new PIN.", "Imefichwa sasa, kwa usalama. Kama hawakuipata, tengeneza PIN mpya.")).font(.bMedium).foregroundColor(C.text).padding(.top, 6).fixedSize(horizontal: false, vertical: true)
            } else {
                HStack(alignment: .center) {
                    Text(pin).font(.hLarge).tracking(8).foregroundColor(C.ink).padding(.vertical, 6).lineLimit(1).minimumScaleFactor(0.6)
                    Spacer(minLength: 6)
                    Text(t("hides in \(left) s", "inajificha baada ya sekunde \(left)")).font(.lMedium).foregroundColor(C.muted).multilineTextAlignment(.trailing)
                }
                Text(t("Send it privately; it can’t be shown again.", "Mtumie kwa siri; haiwezi kuonyeshwa tena.")).font(.bSmall).foregroundColor(C.text)
                Btn(copied ? t("Copied ✓", "Imenakiliwa ✓") : t("Copy", "Nakili"), icon: "file", light: true, small: true, wide: true) { UIPasteboard.general.string = pin; copied = true; Haptic.ok() }.padding(.top, 10)
                HStack(spacing: 8) {
                    Btn("WhatsApp", icon: "chat", light: true, small: true, wide: true) {
                        Share.open("https://wa.me/" + (phone.isEmpty ? "" : waNumber(phone, home)) + "?text=" + Api.enc(teamPinMessage(who, pin)))
                    }
                    Btn("SMS", icon: "phone", light: true, small: true, wide: true) {
                        Share.open("sms:" + phone.replacingOccurrences(of: " ", with: "") + "&body=" + Api.enc(teamPinMessage(who, pin)))
                    }
                }.padding(.top, 8)
            }
        }
        .padding(16).frame(maxWidth: .infinity, alignment: .leading)
        .background(left > 0 ? C.okBg : C.hue("slate").bg).clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .padding(.top, 12)
        .task(id: pin) { while left > 0 { try? await Task.sleep(nanoseconds: 1_000_000_000); if Task.isCancelled { return }; left -= 1 } }
    }
}

/// New PIN (or "Reset and send a new PIN"): asks, then shows it once with Copy, WhatsApp and SMS.
struct TeamNewPinSheet: View {
    @EnvironmentObject var m: AppModel
    let p: TeamPerson; let reset: Bool
    @State private var pin: String?
    @State private var busy = false
    @State private var msg: String?
    var body: some View {
        TeamSubSheet { close in
            TeamSubHead(icon: reset ? "arrow.clockwise" : "key", hue: reset ? "blue" : "green", title: reset ? t("Reset and send a new PIN", "Badilisha na utume PIN mpya") : t("New PIN for \(p.name)", "PIN mpya ya \(p.name)"))
            if let pin {
                TeamPinShown(who: p.name, pin: pin, phone: p.phone, home: p.home)
                Btn(t("Done", "Imekamilika"), light: true, wide: true, action: close).padding(.top, 12)
            } else {
                TeamLines(lines: reset ? [
                    t("PINs are stored locked, so nobody can look up their old one.", "PIN zinahifadhiwa zikiwa zimefungwa, kwa hiyo hakuna anayeweza kuona ya zamani."),
                    t("This makes a new PIN. Their old PIN stops working.", "Hii inatengeneza PIN mpya. PIN ya zamani itaacha kufanya kazi."),
                    t("Then send it to them by WhatsApp or SMS.", "Kisha mtumie kwa WhatsApp au SMS."),
                ] : [
                    t("Their old PIN stops working.", "PIN ya zamani itaacha kufanya kazi."),
                    t("The new PIN shows once, for a minute. Send it to them privately.", "PIN mpya inaonyeshwa mara moja, kwa dakika moja. Mtumie kwa siri."),
                ])
                TeamYesNo(yes: reset ? t("Make and send a new PIN", "Tengeneza na utume PIN mpya") : t("Make a new PIN", "Tengeneza PIN mpya"), busy: busy, no: close) {
                    busy = true; msg = nil
                    Task { let r = await m.api.admin(["action": "team-pin", "name": p.name]); busy = false; if r.ok { pin = r.json.str("pin") } else { msg = meReason(r.reason) } }
                }
                Msg(msg, "bad")
            }
        }
    }
}

/// Set a PIN: the admin types 4 to 8 digits; easy ones and one already in use are refused.
struct TeamSetPinSheet: View {
    @EnvironmentObject var m: AppModel
    let p: TeamPerson
    @State private var v = ""
    @State private var set: String?
    @State private var busy = false
    @State private var msg: String?
    var body: some View {
        TeamSubSheet { close in
            TeamSubHead(icon: "lock", hue: "teal", title: t("Set a PIN for \(p.name)", "Weka PIN ya \(p.name)"))
            if let set {
                Text(t("PIN set ✓", "PIN imewekwa ✓")).font(.tMedium).foregroundColor(C.ok).frame(maxWidth: .infinity).padding(.top, 10)
                TeamPinShown(who: p.name, pin: set, phone: p.phone, home: p.home)
                Btn(t("Done", "Imekamilika"), light: true, wide: true, action: close).padding(.top, 12)
            } else {
                Text(t("4 to 8 digits, not easy to guess: no 1234, 0000 or one digit over and over. Their old PIN stops working.", "Tarakimu 4 hadi 8, isiwe rahisi kubahatisha: si 1234, 0000 wala tarakimu moja inayojirudia. PIN ya zamani itaacha kufanya kazi."))
                    .font(.bMedium).foregroundColor(C.text).multilineTextAlignment(.center).frame(maxWidth: .infinity).padding(.top, 10).fixedSize(horizontal: false, vertical: true)
                Field(t("New PIN", "PIN mpya"), Binding(get: { v }, set: { v = String($0.filter { $0.isNumber }.prefix(8)); msg = nil }), placeholder: "4–8").keyboardType(.numberPad)
                Btn(t("Set PIN", "Weka PIN"), icon: "check", enabled: !busy && v.count >= 4, wide: true, busy: busy ? t("Saving…", "Inahifadhi…") : nil) {
                    if let bad = teamPinProblem(v) { msg = meReason(bad); return }
                    busy = true; msg = nil
                    Task {
                        let r = await m.api.admin(["action": "team-set-pin", "name": p.name, "pin": v.trimmingCharacters(in: .whitespaces)])
                        busy = false
                        if r.ok { set = v.trimmingCharacters(in: .whitespaces) } else { msg = meReason(r.reason) }
                    }
                }.padding(.top, 14)
                Msg(msg, "bad")
            }
        }
    }
}

/// Make admin or remove admin: says what changes, then saves. Nobody can remove their own admin role.
struct TeamAdminSheet: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.dismiss) private var dismiss
    let p: TeamPerson; let makeAdmin: Bool; let done: () -> Void
    @State private var busy = false
    @State private var msg: String?
    var body: some View {
        let selfRemove = !makeAdmin && p.isCalled(m.prefs.name)
        TeamSubSheet { close in
            TeamSubHead(icon: "crown.fill", hue: "amber", title: makeAdmin ? t("Make \(p.name) an admin?", "Mfanye \(p.name) msimamizi?") : t("Remove \(p.name)’s admin role?", "Ondoa nafasi ya msimamizi ya \(p.name)?"))
            if selfRemove {
                Text(t("You can’t remove your own admin role. Ask another admin to do it.", "Huwezi kuondoa nafasi yako mwenyewe ya msimamizi. Mwombe msimamizi mwingine afanye hivyo."))
                    .font(.mont(14, .semibold)).foregroundColor(C.bad).padding(14).frame(maxWidth: .infinity, alignment: .leading).background(C.badBg).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).padding(.top, 14)
                Btn(t("Close", "Funga"), light: true, wide: true, action: close).padding(.top, 14)
            } else {
                TeamLines(lines: makeAdmin ? [
                    t("They’ll see the Office tab: repairs, costs, visits, the team map and the team.", "Ataona kichupo cha Ofisi: matengenezo, gharama, ziara, ramani ya timu na timu."),
                    t("They can add people, make PINs and send e-mails.", "Anaweza kuongeza watu, kutengeneza PIN na kutuma barua pepe."),
                    t("They get e-mail alerts when a site stops working, if they have an e-mail.", "Atapata taarifa kwa barua pepe eneo likiacha kufanya kazi, kama ana barua pepe."),
                ] : [
                    t("The Office tab goes away for them the next time the app refreshes.", "Kichupo cha Ofisi kitaondoka kwake programu ikijisasisha tena."),
                    t("They keep their PIN and can still pin sites and report visits.", "Anabaki na PIN yake na bado anaweza kuweka mahali na kuripoti ziara."),
                    t("No more admin e-mail alerts.", "Hakuna tena taarifa za barua pepe za msimamizi."),
                ], icon: makeAdmin ? "checkmark" : "arrowtriangle.right.fill")
                TeamYesNo(yes: makeAdmin ? t("Make admin", "Mfanye msimamizi") : t("Remove admin", "Ondoa usimamizi"), busy: busy, red: !makeAdmin, no: close) {
                    busy = true; msg = nil
                    Task {
                        let x = await m.api.admin(["action": "team-update", "name": p.name, "home": p.home, "email": p.email, "phone": p.phone, "role": p.role, "admin": makeAdmin])
                        busy = false
                        if x.ok { done(); dismiss() } else { msg = x.reason == "self" ? t("You can’t remove your own admin role.", "Huwezi kuondoa nafasi yako mwenyewe ya msimamizi.") : meReason(x.reason) }
                    }
                }
                Msg(msg, "bad")
            }
        }
    }
}

/// Block, unblock, sign out of all phones, remove, move to an app PIN, give an app PIN: says what happens, then does it.
struct TeamAskSheet: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.dismiss) private var dismiss
    let p: TeamPerson; let action: String; let done: () -> Void
    @State private var reason = ""
    @State private var busy = false
    @State private var msg: String?
    @State private var pin: String?
    private var red: Bool { action == "team-block" || action == "team-remove" }
    private var keepOpen: Bool { action == "team-move" || action == "team-add" }
    var body: some View {
        let me = p.isCalled(m.prefs.name)
        TeamSubSheet { close in
            head(me)
            if let pin {
                if action == "team-move" {
                    Text(t("Done ✓ The entry for \(p.name) in Cloudflare can now be removed.", "Imekamilika ✓ Ingizo la \(p.name) kwenye Cloudflare sasa linaweza kuondolewa."))
                        .font(.mont(14, .semibold)).foregroundColor(C.ok).multilineTextAlignment(.center).frame(maxWidth: .infinity).padding(.top, 10)
                }
                TeamPinShown(who: p.name, pin: pin, phone: p.phone, home: p.home)
                Btn(t("Done", "Imekamilika"), light: true, wide: true, action: close).padding(.top, 12)
            } else {
                TeamLines(lines: lines(me), red: red, icon: red ? "xmark" : "checkmark")
                if action == "team-block" { Field(t("Reason (optional)", "Sababu (si lazima)"), $reason, placeholder: t("e.g. phone lost", "mf. simu imepotea")) }
                if action == "team-move" { TechFold() }
                TeamYesNo(yes: yes, busy: busy, red: red, no: close) { go() }
                Msg(msg, "bad")
            }
        }
    }
    @ViewBuilder private func head(_ me: Bool) -> some View {
        switch action {
        case "team-block": TeamSubHead(icon: "xmark", hue: "coral", title: t("Block \(p.name)?", "Mzuie \(p.name)?"), red: true)
        case "team-unblock": TeamSubHead(icon: "checkmark", hue: "green", title: t("Unblock \(p.name)?", "Mwondolee \(p.name) zuio?"))
        case "team-signout": TeamSubHead(icon: "rectangle.portrait.and.arrow.right", hue: "slate", title: me ? t("Sign out of all your phones?", "Toka kwenye simu zako zote?") : t("Sign \(p.name) out of all phones?", "Mtoe \(p.name) kwenye simu zote?"))
        case "team-remove": TeamSubHead(icon: "trash", hue: "coral", title: t("Remove \(p.name) from the team?", "Mwondoe \(p.name) kwenye timu?"), red: true)
        case "team-move": TeamSubHead(icon: "key", hue: "green", title: t("Move \(p.name) to an app PIN?", "Mhamishe \(p.name) kwenye PIN ya programu?"))
        default: TeamSubHead(icon: "key", hue: "green", title: t("Give \(p.name) an app PIN?", "Mpe \(p.name) PIN ya programu?"))
        }
    }
    private var yes: String {
        switch action {
        case "team-block": return t("Block", "Zuia")
        case "team-unblock": return t("Unblock", "Ondoa zuio")
        case "team-signout": return t("Sign out", "Toa")
        case "team-remove": return t("Remove", "Ondoa")
        case "team-move": return t("Move", "Hamisha")
        default: return t("Make a PIN", "Tengeneza PIN")
        }
    }
    private func lines(_ me: Bool) -> [String] {
        switch action {
        case "team-block": return [
            t("Their PIN stops working and they’re signed out on every phone straight away.", "PIN yake itaacha kufanya kazi na atatolewa kwenye kila simu mara moja."),
            t("Their history is kept, and they stay on the team list marked Blocked.", "Historia yake inabaki, na anabaki kwenye orodha ya timu akiwa na alama Amezuiwa."),
            t("You can unblock them later; the same PIN works again.", "Unaweza kumwondolea zuio baadaye; PIN ileile itafanya kazi tena.")]
        case "team-unblock": return [t("They can sign in again with the same PIN.", "Anaweza kuingia tena kwa PIN ileile.")]
        case "team-signout": return [
            me ? t("Every phone you’re signed in on is signed out straight away, this one too.", "Kila simu uliyoingia nayo itatoka mara moja, pamoja na hii.") : t("Every phone they’re signed in on is signed out straight away.", "Kila simu aliyoingia nayo itatoka mara moja."),
            me ? t("Your PIN keeps working, so you can sign in again.", "PIN yako inaendelea kufanya kazi, kwa hiyo unaweza kuingia tena.") : t("Their PIN keeps working, so they can sign in again.", "PIN yake inaendelea kufanya kazi, kwa hiyo anaweza kuingia tena.")]
        case "team-remove": return [
            t("Their PIN stops working straight away.", "PIN yake itaacha kufanya kazi mara moja."),
            t("Their pins, visits and history are kept.", "Mahali, ziara na historia yake vinabaki.")]
        case "team-move": return [
            t("They get a new PIN from the app, shown once. Send it to them privately.", "Atapata PIN mpya ya programu, inaonyeshwa mara moja. Mtumie kwa siri."),
            t("Then you can manage them here. Their entry in Cloudflare can be removed afterwards.", "Kisha unaweza kumsimamia hapa. Ingizo lake kwenye Cloudflare linaweza kuondolewa baadaye.")]
        default: return [
            t("They get their own PIN, shown once. Send it to them privately.", "Atapata PIN yake, inaonyeshwa mara moja. Mtumie kwa siri."),
            t("They can still sign in with monday.com too.", "Bado anaweza kuingia kwa monday.com pia.")]
        }
    }
    private func go() {
        busy = true; msg = nil
        var req: JSON = ["action": action, "name": p.name]
        if action == "team-block" { req["reason"] = reason.trimmingCharacters(in: .whitespaces) }
        if action == "team-add" { req["home"] = p.home; req["admin"] = p.admin; req["email"] = p.email; req["phone"] = p.phone }
        Task {
            let r = await m.api.admin(req)
            busy = false
            if !r.ok { msg = meReason(r.reason); return }
            let newPin = r.json.str("pin")
            if keepOpen && !newPin.isEmpty && newPin != "null" { pin = newPin; done() } else { done(); dismiss() }
        }
    }
}

/// The technical names, folded away (for whoever looks after the website).
private struct TechFold: View {
    @State private var open = false
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button { withAnimation { open.toggle() } } label: {
                HStack { Text(t("Technical details", "Maelezo ya kiufundi")).font(.lMedium).foregroundColor(C.ink); Spacer(); Icon("chev", size: 16, color: C.muted).rotationEffect(.degrees(open ? 90 : 0)) }
                    .padding(.horizontal, 14).frame(minHeight: 48).contentShape(Rectangle())
            }.buttonStyle(.plain)
            if open {
                Text(t("Their name and PIN are in PIN_USERS (admins also in PIN_ADMINS) in the Cloudflare settings. After the move, take them out of there.", "Jina na PIN yake viko kwenye PIN_USERS (wasimamizi pia kwenye PIN_ADMINS) kwenye mipangilio ya Cloudflare. Baada ya kuhamisha, viondoe hapo."))
                    .font(.bSmall).foregroundColor(C.muted).padding(.horizontal, 14).padding(.bottom, 12).fixedSize(horizontal: false, vertical: true)
            }
        }.overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(C.line)).padding(.top, 10)
    }
}

/// The open repair jobs given to them: tap one to open the project.
struct TeamJobsSheet: View {
    @EnvironmentObject var m: AppModel
    let p: TeamPerson; let open: (String) -> Void
    var body: some View {
        TeamSubSheet { _ in
            TeamSubHead(icon: "wrench.adjustable", hue: "amber", title: t("Repair jobs assigned", "Kazi za matengenezo alizopewa"), sub: p.name)
            if let s = m.snap {
                let jobs = s.repairs.filter { p.isCalled($0.value.assignee) }.sorted { $0.key < $1.key }
                ForEach(jobs, id: \.key) { id, r in
                    Button { open(id) } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(s.projects.first { $0.id == id }?.title ?? (r.title.isEmpty ? id : r.title)).font(.tSmall).foregroundColor(C.ink)
                                Text([stateName(r.state == "notworking" ? "notworking" : "repair"), r.due.isEmpty ? "" : t("due ", "mwisho ") + teamDay(r.due)].filter { !$0.isEmpty }.joined(separator: " · "))
                                    .font(.bSmall).foregroundColor(C.muted)
                            }
                            Spacer(minLength: 6)
                            Icon("chev", size: 18, color: C.muted)
                        }.padding(.horizontal, 12).padding(.vertical, 8).frame(minHeight: 56).contentShape(Rectangle())
                        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(C.line))
                    }.buttonStyle(.plain).padding(.top, 8)
                }
            }
        }
    }
}

// MARK: - the app invite

let PIN_APP_URL = "https://pin.rafikioutreach.org/pin"

/// The invite for WhatsApp or SMS: the same words as the e-mail. Never the PIN; that goes separately.
func teamInviteText(_ who: String, from: String, admin: Bool) -> String {
    let first = who.trimmingCharacters(in: .whitespaces).split(separator: " ").first.map(String.init) ?? who
    let sign = from.trimmingCharacters(in: .whitespaces).isEmpty ? "RAFIKI Outreach" : from.trimmingCharacters(in: .whitespaces)
    return t("Hi \(first), you’re invited to RAFIKI Pin, the RAFIKI Outreach field app for pinning our water projects and reporting site visits.\n\n"
             + (admin ? "As an admin you’ll also see the Office tab, for running repairs, visits and the team.\n\n" : "")
             + "Open it here: \(PIN_APP_URL)\nOn an iPhone, open the link in Safari, tap Share, then “Add to Home Screen”. On Android or a computer, just open the link.\n\n"
             + "Sign in with your PIN, which I’m sending you separately.\n\n— \(sign)",
             "Habari \(first), umealikwa kwenye RAFIKI Pin, programu ya uwanjani ya RAFIKI Outreach ya kuweka mahali pa miradi yetu ya maji na kuripoti ziara.\n\n"
             + (admin ? "Kama msimamizi utaona pia kichupo cha Ofisi, cha kusimamia matengenezo, ziara na timu.\n\n" : "")
             + "Ifungue hapa: \(PIN_APP_URL)\nKwenye iPhone, fungua kiungo kwenye Safari, gusa Shiriki, kisha “Ongeza kwenye Skrini ya Nyumbani”. Kwenye Android au kompyuta, fungua kiungo tu.\n\n"
             + "Ingia kwa PIN yako, nitakayokutumia kando.\n\n— \(sign)")
}

/// The e-mail server's reasons, in plain words.
func teamMailWhy(_ reason: String?, _ j: JSON) -> String {
    switch reason {
    case "no-mail": return t("E-mail isn’t switched on yet. Whoever looks after the website can turn it on.", "Barua pepe bado haijawashwa. Anayesimamia tovuti anaweza kuiwasha.")
    case "day-cap": return t("Today’s e-mail allowance is nearly used up: \(j.int("left")) left today. Send it tomorrow.", "Kiwango cha barua pepe cha leo kinakaribia kwisha: \(j.int("left")) zimebaki leo. Utume kesho.")
    case "month-cap": return t("This month’s e-mail allowance is nearly used up: \(j.int("left")) left this month.", "Kiwango cha barua pepe cha mwezi huu kinakaribia kwisha: \(j.int("left")) zimebaki mwezi huu.")
    case "rate": return t("That’s a lot of e-mails in the last hour. Please wait a while and try again.", "Barua pepe nyingi ndani ya saa iliyopita. Tafadhali subiri kidogo kisha ujaribu tena.")
    case "nobody": return t("They have no e-mail on the team list. Add one, or share the invite by WhatsApp or SMS.", "Hana barua pepe kwenye orodha ya timu. Ongeza moja, au shiriki mwaliko kwa WhatsApp au SMS.")
    case "not-admin": return t("Only admins can send e-mails.", "Wasimamizi tu ndio wanaweza kutuma barua pepe.")
    default:
        let d = j.str("detail")
        return d.isEmpty ? meReason(reason) : t("Not sent. Resend said: ", "Haijatumwa. Resend imesema: ") + d
    }
}

/// The app invite by e-mail: the server's ready-made message, previewed here, then sent to this one person.
/// Also shared by WhatsApp or SMS.
struct TeamInviteSheet: View {
    @EnvironmentObject var m: AppModel
    let p: TeamPerson
    @State private var subject: String?
    @State private var lines: [String] = []
    @State private var err: String?
    @State private var busy = false
    @State private var sent = false
    var body: some View {
        let share = teamInviteText(p.name, from: m.prefs.name, admin: p.admin)
        TeamSubSheet { close in
            TeamSubHead(icon: "envelope", hue: "teal", title: t("Send app invite", "Tuma mwaliko wa programu"), sub: t("To ", "Kwa ") + p.name + (p.email.isEmpty ? "" : " · " + p.email))
            Text(t("The PIN is never in the e-mail. Send it to them separately.", "PIN haiwi kamwe kwenye barua pepe. Wapelekee kando."))
                .font(.mont(12, .semibold)).foregroundColor(C.warn).multilineTextAlignment(.center).frame(maxWidth: .infinity).padding(.top, 8).fixedSize(horizontal: false, vertical: true)
            preview
            if sent { Msg(t("Invite sent to \(p.name) ✓ Now send their PIN separately.", "Mwaliko umetumwa kwa \(p.name) ✓ Sasa mtumie PIN yake kando."), "ok") }
            else {
                Btn(t("Send the invite", "Tuma mwaliko"), icon: "upload", enabled: !busy && subject != nil, wide: true, busy: busy ? t("Sending…", "Inatuma…") : nil) {
                    busy = true; err = nil
                    Task {
                        let r = await m.api.postTo("/api/mail", ["action": "invite", "to": p.name])
                        busy = false
                        if r.ok { sent = true; Haptic.ok() } else { err = teamMailWhy(r.reason, r.json) }
                    }
                }.padding(.top, 14)
                if subject != nil { Msg(err, "bad") }
            }
            MeGroupLabel(t("Or share the invite", "Au shiriki mwaliko"))
            HStack(spacing: 8) {
                Btn("WhatsApp", icon: "chat", light: true, small: true, wide: true) {
                    Share.open("https://wa.me/" + (p.phone.isEmpty ? "" : waNumber(p.phone, p.home)) + "?text=" + Api.enc(share))
                }
                Btn("SMS", icon: "phone", light: true, small: true, wide: true) { Share.open("sms:" + p.phone.replacingOccurrences(of: " ", with: "") + "&body=" + Api.enc(share)) }
            }
            if sent { Btn(t("Done", "Imekamilika"), light: true, wide: true, action: close).padding(.top, 12) }
        }
        .task {
            let r = await m.api.postTo("/api/mail", ["action": "invite", "to": p.name, "preview": true])
            if r.ok { subject = r.json.str("subject"); lines = TeamInviteSheet.text(r.json.str("html")); err = nil } else { err = teamMailWhy(r.reason, r.json) }
        }
    }

    private var preview: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let subject {
                Text(t("Subject", "Kichwa")).font(.lSmall).foregroundColor(C.muted)
                Text(subject).font(.tSmall).foregroundColor(C.ink).fixedSize(horizontal: false, vertical: true)
                Rectangle().fill(C.line).frame(height: 1).padding(.vertical, 8)
                ForEach(Array(lines.enumerated()), id: \.offset) { _, l in
                    if l.lowercased() == "open rafiki pin" || l.lowercased() == "fungua rafiki pin" {
                        Text(l).font(.lLarge).foregroundColor(.white).padding(.horizontal, 18).padding(.vertical, 10).background(Capsule().fill(BlueBrush)).padding(.vertical, 6)
                    } else {
                        Text(l).font(.bMedium).foregroundColor(C.text).padding(.vertical, 3).fixedSize(horizontal: false, vertical: true)
                    }
                }
            } else {
                Text(err ?? t("Getting the preview…", "Inaandaa onyesho…")).font(.bMedium).foregroundColor(err != nil ? C.bad : C.muted).fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(14).frame(maxWidth: .infinity, alignment: .leading)
        .background(C.bg).clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(C.line))
        .padding(.top, 12)
    }

    /// The e-mail's words as lines of text (tags, styles and scripts taken out).
    static func text(_ html: String) -> [String] {
        var s = html.replacingOccurrences(of: #"(?is)<(style|script|head)[^>]*>.*?</\1>"#, with: "", options: .regularExpression)
        s = s.replacingOccurrences(of: #"(?i)<br\s*/?>|</p>|</div>|</h\d>|</tr>|</li>|</a>"#, with: "\n", options: .regularExpression)
        s = s.replacingOccurrences(of: #"<[^>]+>"#, with: "", options: .regularExpression)
        for (k, v) in [("&nbsp;", " "), ("&amp;", "&"), ("&lt;", "<"), ("&gt;", ">"), ("&quot;", "\""), ("&#39;", "’"), ("&rsquo;", "’"), ("&mdash;", "—"), ("&ndash;", "–")] { s = s.replacingOccurrences(of: k, with: v) }
        return s.split(separator: "\n").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
    }
}

import SwiftUI
import UIKit

// One contact sheet for every team member, the same wherever a person is tapped (Office › Team, Me › Team, the Team map,
// notifications, project history, repairs, Plan and Visits, the Office feed, the review sheets). A clear menu of actions
// like a phone's contact card; each action does one thing in its own small sheet (one field, Save, X).

func communityName() -> String { t("Community report", "Ripoti ya jamii") }

/// Whose contact sheet is open (by name). Anything can open it: Contact.show("Grace Wanjiru").
final class Contact: ObservableObject {
    static let shared = Contact()
    @Published var who: String?
    /// Bumped when someone's details changed here, so lists of people (Office › Team) reload.
    @Published var changed = 0
    static func show(_ name: String?) { let n = (name ?? "").trimmingCharacters(in: .whitespaces); if isPerson(n) { shared.who = n } }
    /// A team member's name, not a blank, a dash or a community (SMS/WhatsApp) reporter.
    static func isPerson(_ n: String) -> Bool {
        !n.isEmpty && n != "—" && n != "-" && !n.hasPrefix("SMS from") && !n.hasPrefix("WhatsApp from") && n != communityName() && n.lowercased() != "rafiki pin"
    }
}

/// Jumps from a contact sheet to another Office tab: Team map with them in focus, or Assign a visit for them.
enum TeamGo {
    static var section: String?
    static var map: String?
    static var assign: String?
}

/// What blocked means for one person: when, by whom and why.
struct Blocked: Equatable { var at: String; var by: String; var reason: String }

/// One person on the team, as the contact sheet needs them. [src]: "app", "cloudflare", "monday" or "" (unknown).
struct Person: Equatable, Identifiable {
    var name: String; var home = ""; var email = ""; var phone = ""; var admin = false; var src = ""
    var lastSeen: String? = nil; var role = ""; var aka: [String] = []; var blocked: Blocked? = nil; var known = true
    var adminCf = false; var alsoCloudflare = false; var out: String? = nil
    var id: String { name }
    var app: Bool { src == "app" }
    var cloudflare: Bool { src == "cloudflare" }
    func names() -> [String] { [name] + aka }
    func isCalled(_ n: String) -> Bool { names().contains { $0.trimmingCharacters(in: .whitespaces).lowercased() == n.trimmingCharacters(in: .whitespaces).lowercased() } }
    func json() -> JSON { ["name": name, "home": home, "email": email, "phone": phone, "admin": admin, "role": role, "source": src] }

    static func from(_ o: JSON) -> Person {
        func s(_ k: String) -> String { let v = o.str(k); return v == "null" ? "" : v.trimmingCharacters(in: .whitespaces) }
        let b = o.obj("blocked")
        let blocked = b.map { Blocked(at: ($0.str("at")), by: $0.str("by"), reason: $0.str("reason")) } ?? (o.bool("blocked") ? Blocked(at: "", by: "", reason: "") : nil)
        return Person(name: s("name"), home: s("home"), email: s("email"), phone: s("phone"), admin: o.bool("admin"), src: s("src").isEmpty ? s("source") : s("src"),
                      lastSeen: s("lastSeen").isEmpty ? nil : s("lastSeen"), role: s("role"), aka: (o.arr("aka") ?? []).compactMap { $0 as? String }.filter { !$0.isEmpty },
                      blocked: blocked, adminCf: o.bool("adminCf"), alsoCloudflare: o.bool("alsoCloudflare"), out: s("out").isEmpty ? nil : s("out"))
    }
}

/// A PIN the app refuses before asking the server: not 4 to 8 digits, all one digit, or a run like 1234 or 9876.
func pinProblem(_ pin: String) -> String? {
    let p = Array(pin.trimmingCharacters(in: .whitespaces))
    if p.count < 4 || p.count > 8 || !p.allSatisfy(\.isNumber) { return "digits" }
    if p.allSatisfy({ $0 == p[0] }) { return "weak-pin" }
    let steps = Set(zip(p, p.dropFirst()).map { Int(String($1))! - Int(String($0))! })
    if steps == [1] || steps == [-1] { return "weak-pin" }
    if p.count % 2 == 0 && Array(p[0..<p.count / 2]) == Array(p[p.count / 2..<p.count]) { return "weak-pin" }
    return nil
}

/// The words for what the server said no to, in plain language.
func contactWhy(_ reason: String?) -> String {
    switch reason {
    case "exists": return t("Someone with that name is already on the team.", "Mtu mwenye jina hilo tayari yuko kwenye timu.")
    case "weak-pin": return t("That PIN is too easy to guess. Choose another.", "PIN hiyo ni rahisi kubahatisha. Chagua nyingine.")
    case "digits": return t("Use 4 to 8 digits.", "Tumia tarakimu 4 hadi 8.")
    case "self": return t("You can’t do that to yourself. Ask another admin.", "Huwezi kujifanyia hivyo. Mwombe msimamizi mwingine.")
    case "last-admin": return t("They’re the last admin, so this would leave nobody to run the Office. Make someone else an admin first.", "Ndiye msimamizi wa mwisho, kwa hiyo hakuna atakayebaki kusimamia Ofisi. Mfanye mtu mwingine msimamizi kwanza.")
    default: return reasonText(reason)
    }
}

/// Why an e-mail wasn't sent, in plain words.
func mailWhy(_ reason: String?, _ j: JSON = [:]) -> String {
    switch reason {
    case "no-mail": return t("E-mail isn’t switched on yet. Whoever looks after the website can turn it on.", "Barua pepe bado haijawashwa. Anayesimamia tovuti anaweza kuiwasha.")
    case "cap": return t("One send can go to at most \(j.int("cap")) people. Choose fewer, or send it in parts.", "Utumaji mmoja unaweza kwenda kwa watu \(j.int("cap")) tu. Chagua wachache, au utume kwa sehemu.")
    case "day-cap": return t("Today’s e-mail allowance is nearly used up: \(j.int("left")) left today. Choose fewer people or send it tomorrow.", "Kiwango cha barua pepe cha leo kinakaribia kwisha: \(j.int("left")) zimebaki leo. Chagua watu wachache au utume kesho.")
    case "month-cap": return t("This month’s e-mail allowance is nearly used up: \(j.int("left")) left this month.", "Kiwango cha barua pepe cha mwezi huu kinakaribia kwisha: \(j.int("left")) zimebaki mwezi huu.")
    case "rate": return t("That’s a lot of e-mails in the last hour. Please wait a while and try again.", "Barua pepe nyingi ndani ya saa iliyopita. Tafadhali subiri kidogo kisha ujaribu tena.")
    case "no-own-email": return t("Add your own e-mail in Office › Team first, so the test can reach you.", "Ongeza barua pepe yako kwenye Ofisi › Timu kwanza, ili jaribio likufikie.")
    case "empty": return t("Add a subject and a message.", "Ongeza kichwa na ujumbe.")
    case "nobody": return t("Nobody to send to. Choose who it goes to.", "Hakuna wa kutumiwa. Chagua anaotumiwa.")
    case "changed": return t("The list changed. Check the count and send again.", "Orodha imebadilika. Angalia idadi kisha utume tena.")
    case "not-admin": return t("Only admins can send e-mails.", "Wasimamizi tu ndio wanaweza kutuma barua pepe.")
    case "too-large": return t("That photo is too large.", "Picha hiyo ni kubwa mno.")
    case "type": return t("That kind of file can’t be added.", "Aina hiyo ya faili haiwezi kuongezwa.")
    default:
        let d = j.str("detail")
        return d.isEmpty ? reasonText(reason) : t("Not sent. Resend said: ", "Haijatumwa. Resend imesema: ") + d
    }
}

let PIN_APP_URL = "https://pin.rafikioutreach.org/pin"
/// How long a new PIN stays on screen before it is hidden.
let PIN_SHOWN_SECONDS = 60
/// The message that carries a new PIN to the person (sent by the admin, from their own phone).
func pinMessage(_ who: String, _ pin: String) -> String {
    let first = who.split(separator: " ").first.map(String.init) ?? who
    return t("Hi \(first), your RAFIKI Pin PIN is \(pin). Open the app (or pin.rafikioutreach.org) and sign in with it. Please keep it to yourself.",
             "Habari \(first), PIN yako ya RAFIKI Pin ni \(pin). Fungua programu (au pin.rafikioutreach.org) uingie nayo. Tafadhali usimpe mtu mwingine.")
}
/// The invite for WhatsApp or SMS: the same words as the e-mail. Never the PIN; that goes separately.
func inviteText(_ who: String, from: String, admin: Bool) -> String {
    let first = who.trimmingCharacters(in: .whitespaces).split(separator: " ").first.map(String.init) ?? who
    let sign = from.trimmingCharacters(in: .whitespaces).isEmpty ? "RAFIKI Outreach" : from.trimmingCharacters(in: .whitespaces)
    return t("Hi \(first), you’re invited to RAFIKI Pin, the RAFIKI Outreach field app for pinning our water projects and reporting site visits.\n\n" +
             (admin ? "As an admin you’ll also see the Office tab, for running repairs, visits and the team.\n\n" : "") +
             "Open it here: \(PIN_APP_URL)\nOn an iPhone, open the link in Safari, tap Share, then “Add to Home Screen”. On Android or a computer, just open the link.\n\n" +
             "Sign in with your PIN, which I’m sending you separately.\n\n— \(sign)",
             "Habari \(first), umealikwa kwenye RAFIKI Pin, programu ya uwanjani ya RAFIKI Outreach ya kuweka mahali pa miradi yetu ya maji na kuripoti ziara.\n\n" +
             (admin ? "Kama msimamizi utaona pia kichupo cha Ofisi, cha kusimamia matengenezo, ziara na timu.\n\n" : "") +
             "Ifungue hapa: \(PIN_APP_URL)\nKwenye iPhone, fungua kiungo kwenye Safari, gusa Shiriki, kisha “Ongeza kwenye Skrini ya Nyumbani”. Kwenye Android au kompyuta, fungua kiungo tu.\n\n" +
             "Ingia kwa PIN yako, nitakayokutumia kando.\n\n— \(sign)")
}
/// A vCard for "Share contact".
func vcard(_ name: String, _ phone: String, _ email: String, _ role: String) -> String {
    var s = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:" + name.replacingOccurrences(of: ",", with: "\\,") + "\r\n"
    let parts = name.trimmingCharacters(in: .whitespaces).split(separator: " ").map(String.init)
    s += "N:" + (parts.count > 1 ? parts.last! : "") + ";" + (parts.count > 1 ? parts.dropLast().joined(separator: " ") : (parts.first ?? "")) + ";;;\r\nORG:RAFIKI Outreach\r\n"
    if !role.isEmpty { s += "TITLE:" + role.replacingOccurrences(of: ",", with: "\\,") + "\r\n" }
    if !phone.isEmpty { s += "TEL;TYPE=CELL:" + phone + "\r\n" }
    if !email.isEmpty { s += "EMAIL:" + email + "\r\n" }
    return s + "END:VCARD\r\n"
}
enum Talk {
    static func sms(_ phone: String, _ body: String = "") {
        var allowed = CharacterSet.urlQueryAllowed; allowed.remove(charactersIn: "&=+?")
        Share.open("sms:" + phone.replacingOccurrences(of: " ", with: "") + (body.isEmpty ? "" : "&body=" + (body.addingPercentEncoding(withAllowedCharacters: allowed) ?? "")))
    }
}

/// The person's round picture with initials (the same colours as notifications).
struct PersonAvatar: View {
    let name: String; var size: CGFloat = 44
    var body: some View {
        let hu = C.named(nameHue(name))
        Text(initialsOf(name)).font(.mont(size * 0.34, .heavy)).foregroundColor(hu.fg).frame(width: size, height: size).background(hu.bg).clipShape(Circle())
    }
}

/// A label and a team member's name with their picture and a chevron: a tap opens their contact sheet.
struct PersonFact: View {
    let label: String; let name: String; var sub = ""; var divider = true
    var body: some View {
        if !name.isEmpty {
            let tappable = Contact.isPerson(name.trimmingCharacters(in: .whitespaces))
            VStack(spacing: 0) {
                Button { if tappable { Contact.show(name) } } label: {
                    HStack(spacing: 8) {
                        Text(label).font(.bMedium).foregroundColor(C.muted)
                        Spacer(minLength: 12)
                        if tappable { PersonAvatar(name: name, size: 28) }
                        VStack(alignment: .trailing, spacing: 1) {
                            Text(name).font(.mont(14, .bold)).foregroundColor(tappable ? C.blue2 : C.ink).multilineTextAlignment(.trailing)
                            if !sub.isEmpty { Text(sub).font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.trailing) }
                        }
                        if tappable { Icon("chev", size: 16, color: C.muted) }
                    }.frame(minHeight: 48).contentShape(Rectangle())
                }.buttonStyle(.plain).disabled(!tappable)
                if divider { Rectangle().fill(C.line).frame(height: 1) }
            }
        }
    }
}

/// A team member's name as a small pill with their picture: a tap opens their contact sheet.
struct PersonChip: View {
    let name: String
    var body: some View {
        if !Contact.isPerson(name.trimmingCharacters(in: .whitespaces)) { Text(name).font(.lMedium).foregroundColor(C.ink) }
        else {
            Button { Contact.show(name) } label: {
                HStack(spacing: 6) {
                    PersonAvatar(name: name, size: 24)
                    Text(name).font(.mont(12, .bold)).foregroundColor(C.deep).lineLimit(1)
                }.padding(.leading, 3).padding(.trailing, 10).padding(.vertical, 3).background(C.tint).clipShape(Capsule()).frame(minHeight: 44)
            }.buttonStyle(.plain).accessibilityLabel(name)
        }
    }
}

/// A feed row's picture: the person, with what they did as a small coloured badge.
struct PersonBadgeAvatar: View {
    let name: String; let icon: String; let hue: Hue; let ring: Color; var size: CGFloat = 36
    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            PersonAvatar(name: name, size: size).frame(width: size + 6, height: size + 6, alignment: .topLeading)
            Icon(icon, size: 11, color: C.card).frame(width: 16, height: 16).background(hue.fg).clipShape(Circle()).padding(2).background(ring).clipShape(Circle())
        }
        .onTapGesture { Contact.show(name) }
    }
}

/// The small sheets the contact menu opens.
enum CtSub: String, Identifiable { case name, email, phone, myphone, home, role, invite, newpin, resetpin, setpin, admin, unadmin, block, unblock, signout, remove, move, givepin, jobs; var id: String { rawValue } }

/// Their pins, visits and fixes: today and this month from the team feed. Tap one to open the project.
struct PersonActivity: View {
    let names: [String]; let act: [JSON]?; let snap: Snapshot?; var label = true; let open: (String) -> Void
    @State private var more = false
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if label { GroupLabel(t("Their visits and activity", "Ziara na kazi zake")) } else { Spacer().frame(height: 8) }
            if let act {
                let cal = Calendar.current, first = cal.date(from: cal.dateComponents([.year, .month], from: Date()))!
                let month = act.filter { e in names.contains { e.str("by").trimmingCharacters(in: .whitespaces).lowercased() == $0.trimmingCharacters(in: .whitespaces).lowercased() }
                    && ["pin", "visit", "fixed", "new"].contains(e.str("a")) && (When.parse(e.str("at")).map { $0 >= first } ?? false) }.sorted { $0.str("at") > $1.str("at") }
                let nToday = month.filter { When.parse($0.str("at")).map(cal.isDateInToday) ?? false }.count
                HStack(spacing: 8) {
                    ForEach([(nToday, t("today", "leo")), (month.count, t("this month", "mwezi huu"))], id: \.1) { n, l in
                        VStack(alignment: .leading, spacing: 0) { Text("\(n)").font(.tLarge).foregroundColor(C.deep); Text(l).font(.lMedium).foregroundColor(C.deep).lineLimit(1) }
                            .padding(.horizontal, 12).padding(.vertical, 8).frame(maxWidth: .infinity, alignment: .leading).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }
                }
                if month.isEmpty { Text(t("Nothing yet this month.", "Bado hakuna kitu mwezi huu.")).font(.bMedium).foregroundColor(C.muted).padding(.top, 10) }
                ForEach(Array(month.prefix(more ? 30 : 6).enumerated()), id: \.offset) { _, e in
                    let id = e.str("id"), p = snap?.projects.first { $0.id == id }
                    let (icon, hue): (String, String) = { switch e.str("a") { case "pin": return ("pin", "pin"); case "visit": return ("clip", "near"); case "fixed": return ("check", "near"); default: return ("plus", "new") } }()
                    Button { if p != nil { open(id) } } label: {
                        HStack(spacing: 10) {
                            Icon(icon, size: 17, color: C.hue(hue).fg).frame(width: 34, height: 34).background(C.hue(hue).bg).clipShape(Circle())
                            VStack(alignment: .leading, spacing: 1) {
                                Text(p?.title ?? e.str("t")).font(.tSmall).foregroundColor(C.ink).lineLimit(1)
                                Text(actVerb(e) + (e.str("a") == "visit" && !e.str("s").isEmpty ? " · " + stateName(e.str("s")) : "") + " · " + ago(e.str("at"))).font(.bSmall).foregroundColor(C.muted).lineLimit(2)
                            }
                            Spacer(minLength: 4)
                            if p != nil { Icon("chev", size: 18, color: C.muted) }
                        }.padding(.vertical, 6).padding(.horizontal, 4).frame(minHeight: 52).contentShape(Rectangle())
                    }.buttonStyle(.plain).padding(.top, 6)
                }
                if month.count > 6 {
                    Button { more.toggle() } label: { Text(more ? t("Show less", "Onyesha kidogo") : t("Show all \(month.count)", "Onyesha zote \(month.count)")).font(.lLarge).foregroundColor(C.blue).padding(8) }.buttonStyle(.plain).padding(.top, 6)
                }
            } else { Text(t("Loading…", "Inapakia…")).font(.bMedium).foregroundColor(C.muted) }
        }
    }
    private func actVerb(_ e: JSON) -> String {
        switch e.str("a") { case "pin": return t("Pinned", "Aliweka mahali"); case "visit": return t("Visited", "Alitembelea"); case "fixed": return t("Fixed", "Alirekebisha"); default: return t("Added", "Aliongeza") }
    }
}

/// The contact sheet, shown over everything when Contact.shared.who is set.
struct ContactSheet: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject private var ct = Contact.shared
    let who: String
    let go: (String, String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var person: Person?
    @State private var loaded = false
    @State private var sharing = false
    @State private var sub: CtSub?
    @State private var said: (String, String)?
    @State private var tick = 0

    var body: some View {
        let iAmAdmin = m.snap?.admin == true
        let p = person ?? Person(name: name.isEmpty ? who : name, known: false)
        let me = p.isCalled(m.prefs.name) || (name.isEmpty ? who : name).lowercased() == m.prefs.name.trimmingCharacters(in: .whitespaces).lowercased()
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ZStack(alignment: .topTrailing) {
                    header(p).wide(.center)
                    CloseX { dismiss() }
                }
                if !p.email.isEmpty || !p.phone.isEmpty {
                    VStack(spacing: 0) {
                        if !p.email.isEmpty { line("mail", p.email, t("Copy e-mail", "Nakili barua pepe")) { said = ("ok", t("E-mail copied ✓", "Barua pepe imenakiliwa ✓")) } }
                        if !p.phone.isEmpty { line("phone", p.phone, t("Copy phone", "Nakili simu")) { said = ("ok", t("Phone copied ✓", "Simu imenakiliwa ✓")) } }
                    }.padding(.top, 10)
                }
                if let said { Msg(said.1, said.0) }

                if !me {
                    CtGroup(t("Contact", "Mawasiliano")) {
                        if !p.email.isEmpty {
                            CtRow(icon: "mail", hue: "teal", label: t("Send an e-mail", "Tuma barua pepe"), value: p.email) {
                                if iAmAdmin { MailNav.preset = MailPreset(aud: "pick", picks: ["t:" + p.name.lowercased()]); leave { go("office", "mail") } } else { Share.open("mailto:" + p.email) }
                            }
                        }
                        if !p.phone.isEmpty {
                            CtRow(icon: "phone", hue: "near", label: t("Call", "Piga simu"), value: p.phone) { Links.call(p.phone) }
                            CtRow(icon: "b:whatsapp", hue: "wa", label: "WhatsApp", value: p.phone) { Links.whatsapp(p.phone, p.home) }
                            CtRow(icon: "text", hue: "blue", label: "SMS", value: p.phone) { Talk.sms(p.phone) }
                        }
                        CtRow(icon: "share", hue: "purple", label: t("Share contact", "Shiriki mawasiliano"), value: t("A contact card with name, phone and e-mail", "Kadi ya mawasiliano yenye jina, simu na barua pepe")) { shareContact(p) }
                        if p.email.isEmpty && p.phone.isEmpty {
                            Text(t("No e-mail or phone on the team list for them yet.", "Bado hakuna barua pepe wala simu yake kwenye orodha ya timu.")).font(.bSmall).foregroundColor(C.muted).padding(.horizontal, 12).padding(.vertical, 8)
                        }
                    }
                }

                let canEdit = p.known && p.app && (iAmAdmin || me)
                let selfFixed = me && p.known && !p.app
                if p.known {
                    CtGroup(t("Details", "Maelezo")) {
                        if canEdit {
                            CtRow(icon: "edit", hue: "blue", label: t("Name", "Jina"), value: p.name) { sub = .name }
                            CtRow(icon: "mail", hue: "teal", label: p.email.isEmpty ? t("Add e-mail", "Ongeza barua pepe") : t("Change e-mail", "Badilisha barua pepe"), value: p.email.isEmpty ? t("None yet", "Bado hakuna") : p.email) { sub = .email }
                            CtRow(icon: "phone", hue: "near", label: p.phone.isEmpty ? t("Add phone", "Ongeza simu") : t("Change phone", "Badilisha simu"), value: p.phone.isEmpty ? t("None yet", "Bado hakuna") : p.phone) { sub = .phone }
                            if iAmAdmin {
                                CtRow(icon: "pin", hue: "green", label: t("Home country", "Nchi ya nyumbani"), value: countryName(p.home) ?? t("All countries", "Nchi zote")) { sub = .home }
                                CtRow(icon: "people", hue: "slate", label: t("Role or title", "Cheo au kazi"), value: p.role.isEmpty ? t("Not set (optional)", "Haijawekwa (si lazima)") : p.role) { sub = .role }
                            }
                        } else if selfFixed {
                            CtRow(icon: "edit", hue: "blue", label: t("Name", "Jina"), value: p.name, action: nil)
                            CtRow(icon: "mail", hue: "teal", label: t("E-mail", "Barua pepe"), value: p.email.isEmpty ? t("None yet", "Bado hakuna") : p.email, action: nil)
                            Text(t("Set by the office", "Imewekwa na ofisi")).font(.bSmall).foregroundColor(C.muted).padding(.horizontal, 12).padding(.vertical, 4)
                            CtRow(icon: "phone", hue: "near", label: p.phone.isEmpty ? t("Add phone", "Ongeza simu") : t("Change phone", "Badilisha simu"), value: p.phone.isEmpty ? t("None yet", "Bado hakuna") : p.phone) { sub = .myphone }
                        } else {
                            CtRow(icon: "pin", hue: "green", label: t("Home country", "Nchi ya nyumbani"), value: countryName(p.home) ?? t("All countries", "Nchi zote"), action: nil)
                            if !p.role.isEmpty { CtRow(icon: "people", hue: "slate", label: t("Role or title", "Cheo au kazi"), value: p.role, action: nil) }
                            if iAmAdmin && p.cloudflare && !me {
                                Text(t("Their PIN is set in Cloudflare, so their details can’t be changed here. Move them to an app PIN below to manage them here.", "PIN yake imewekwa kwenye Cloudflare, kwa hiyo maelezo yake hayawezi kubadilishwa hapa. Mhamishe kwenye PIN ya programu hapa chini ili umsimamie hapa."))
                                    .font(.bSmall).foregroundColor(C.muted).padding(.horizontal, 12).padding(.vertical, 8)
                            }
                        }
                        if me { CtRow(icon: "key", hue: "amber", label: t("Change my PIN", "Badilisha PIN yangu"), value: t("In Settings", "Kwenye Mipangilio")) { leave { go("settings", "pin") } } }
                    }
                }

                if iAmAdmin && p.known {
                    CtGroup(t("Access", "Ufikiaji")) {
                        if p.app {
                            CtRow(icon: "share", hue: "purple", label: t("Send app invite", "Tuma mwaliko wa programu"), value: t("E-mail, WhatsApp or SMS. Never with the PIN.", "Barua pepe, WhatsApp au SMS. Kamwe pamoja na PIN.")) { sub = .invite }
                            CtRow(icon: "key", hue: "green", label: t("New PIN", "PIN mpya"), value: t("Makes one and shows it once", "Inatengeneza moja na kuionyesha mara moja")) { sub = .newpin }
                            CtRow(icon: "lock", hue: "teal", label: t("Set a PIN", "Weka PIN"), value: t("You type 4 to 8 digits", "Unaandika tarakimu 4 hadi 8")) { sub = .setpin }
                            if p.alsoCloudflare { CtRow(icon: "cloud", hue: "purple", label: t("Also in Cloudflare", "Pia yuko kwenye Cloudflare"), value: t("Their old entry there can be removed", "Ingizo lake la zamani huko linaweza kuondolewa"), action: nil) }
                            CtRow(icon: "refresh", hue: "blue", label: t("Reset and send a new PIN", "Badilisha na utume PIN mpya"), value: t("If they forgot it: by WhatsApp or SMS", "Kama ameisahau: kwa WhatsApp au SMS")) { sub = .resetpin }
                            if !me && p.adminCf {
                                CtRow(icon: "crown", hue: "amber", label: t("Admin set in Cloudflare", "Msimamizi kupitia Cloudflare"), value: t("Can’t be made a field officer in the app", "Hawezi kufanywa afisa wa uwanjani kwenye programu"), action: nil)
                            } else if !me {
                                CtRow(icon: "crown", hue: "amber", label: p.admin ? t("Remove admin", "Ondoa usimamizi") : t("Make admin", "Mfanye msimamizi"), value: p.admin ? t("They keep their PIN", "Anabaki na PIN yake") : t("They’ll see the Office", "Ataona Ofisi")) { sub = p.admin ? .unadmin : .admin }
                            }
                        } else if p.cloudflare {
                            CtRow(icon: "cloud", hue: "purple", label: t("PIN set in Cloudflare", "PIN imewekwa kwenye Cloudflare"), value: t("Can’t be changed from the app", "Haiwezi kubadilishwa kwenye programu"), action: nil)
                            CtRow(icon: "key", hue: "green", label: t("Move to an app PIN", "Hamia PIN ya programu"), value: t("Then you can manage them here", "Kisha unaweza kumsimamia hapa")) { sub = .move }
                        } else {
                            CtRow(icon: "key", hue: "green", label: t("Give them an app PIN", "Mpe PIN ya programu"), value: t("They sign in with monday.com now", "Kwa sasa anaingia kwa monday.com")) { sub = .givepin }
                        }
                        if !me {
                            if p.blocked == nil { CtRow(icon: "x", hue: "coral", label: t("Block", "Zuia"), value: t("Stops their PIN and signs them out", "Inasimamisha PIN yake na kumtoa"), red: true) { sub = .block } }
                            else { CtRow(icon: "check", hue: "green", label: t("Unblock", "Ondoa zuio"), value: t("The same PIN works again", "PIN ileile itafanya kazi tena")) { sub = .unblock } }
                        }
                        CtRow(icon: "logout", hue: "slate", label: t("Sign out of all phones", "Toa kwenye simu zote"), value: me ? t("Including this one. Your PIN stays.", "Pamoja na hii. PIN yako inabaki.") : t("Keeps their PIN", "PIN yake inabaki")) { sub = .signout }
                        if !me && p.app { CtRow(icon: "trash", hue: "coral", label: t("Remove from the team", "Ondoa kwenye timu"), value: t("Their history is kept", "Historia yake inabaki"), red: true) { sub = .remove } }
                    }
                }

                CtGroup(me ? t("Your work", "Kazi yako") : t("Their work", "Kazi yake")) {
                    PersonActivity(names: p.names(), act: m.activity, snap: m.snap, label: false) { id in leave { go("project", id) } }.padding(.horizontal, 10)
                    if iAmAdmin && p.known { CtRow(icon: "cal", hue: "blue", label: t("Assign a visit", "Gawa ziara"), value: t("Plan with them chosen", "Panga ukiwa umemchagua")) { TeamGo.assign = p.name; leave { go("office", "visits") } } }
                    let jobs = (m.snap?.repairs ?? [:]).filter { p.isCalled($0.value.assignee) }
                    CtRow(icon: "wrench", hue: "repairs", label: t("Repair jobs assigned", "Kazi za matengenezo alizopewa"),
                          value: jobs.isEmpty ? t("None open", "Hakuna iliyo wazi") : jobs.count == 1 ? t("1 open", "1 iko wazi") : t("\(jobs.count) open", "\(jobs.count) ziko wazi"),
                          action: jobs.isEmpty ? nil : { sub = .jobs })
                    if iAmAdmin && sharing { CtRow(icon: "pin", hue: "green", label: t("Show on team map", "Onyesha kwenye ramani ya timu"), value: t("Sharing their location now", "Anashiriki mahali alipo sasa")) { TeamGo.map = p.name; leave { go("office", "teammap") } } }
                }
            }.padding(.horizontal, 20).padding(.top, 20).padding(.bottom, 28)
        }
        .background(C.card.ignoresSafeArea())
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .task(id: "\(tick)|\(name)") { await load(iAmAdmin) }
        .task { await m.loadActivity() }
        .task(id: said?.1) { if said != nil { try? await Task.sleep(nanoseconds: 2_500_000_000); said = nil } }
        .onAppear { if name.isEmpty { name = who } }
        .sheet(item: $sub) { s in subSheet(s, p, me: me, admin: iAmAdmin).environmentObject(m) }
    }

    private func load(_ admin: Bool) async {
        let n = name.isEmpty ? who : name
        let r = admin ? await m.api.adminGet("team") : await m.api.team()
        let list = r.json.objs("team").map(Person.from)
        var found = list.first { $0.isCalled(n) }
        if let f = found, f.isCalled(m.prefs.name), f.src.isEmpty, let k = m.snap?.me.kind, ["app", "cloudflare", "monday"].contains(k) { found?.src = k }
        if found == nil && !list.isEmpty && found == nil && m.prefs.name.lowercased() == n.lowercased() { found = nil }
        person = found ?? (person?.isCalled(n) == true ? person : Person(name: n, known: false))
        loaded = true
        if admin {
            let tm = await m.api.adminGet("teammap")
            if tm.ok { sharing = tm.json.objs("people").contains { person?.isCalled($0.str("name")) == true } }
        }
    }
    private func changed() { tick += 1; Contact.shared.changed += 1 }
    private func leave(_ then: @escaping () -> Void) {
        dismiss()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { then() }
    }
    private func shareContact(_ p: Person) {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent((p.name.filter { $0.isLetter || $0.isNumber || $0 == " " || $0 == "-" || $0 == "_" }.trimmingCharacters(in: .whitespaces)).isEmpty ? "contact.vcf" : p.name.filter { $0.isLetter || $0.isNumber || $0 == " " } + ".vcf")
        try? vcard(p.name, p.phone, p.email, p.role).write(to: file, atomically: true, encoding: .utf8)
        Share.items([file])
    }

    @ViewBuilder private func header(_ p: Person) -> some View {
        VStack(spacing: 0) {
            ZStack(alignment: .topTrailing) {
                PersonAvatar(name: p.name, size: 72)
                if p.admin {
                    Icon("crown", size: 18, color: C.dark ? Color(hex: 0xF2C14E) : Color(hex: 0xE0A100)).frame(width: 28, height: 28).background(C.card).clipShape(Circle())
                        .overlay(Circle().stroke(C.line, lineWidth: 1)).offset(x: 4, y: -4).accessibilityLabel(t("Admin", "Msimamizi"))
                }
            }
            Text(p.name).font(.tLarge).foregroundColor(C.ink).multilineTextAlignment(.center).padding(.top, 8).padding(.horizontal, 36)
            if !p.role.isEmpty { Text(p.role).font(.bMedium).foregroundColor(C.text).multilineTextAlignment(.center) }
            if p.known {
                HStack(spacing: 6) {
                    if p.admin { CtBadge(text: t("Admin", "Msimamizi"), hue: "amber", icon: "crown") } else { CtBadge(text: t("Field", "Uwanjani"), hue: "slate", icon: "people") }
                    if p.src == "app" { CtBadge(text: t("PIN from app", "PIN ya programu"), hue: "green", icon: "key") }
                    else if p.src == "cloudflare" { CtBadge(text: t("PIN in Cloudflare", "PIN ya Cloudflare"), hue: "purple", icon: "cloud") }
                    else if p.src == "monday" { CtBadge(text: "monday.com", hue: "blue", icon: "people") }
                    if p.blocked != nil { CtBadge(text: t("Blocked", "Amezuiwa"), hue: "coral", icon: "x", red: true) }
                }.padding(.top, 8)
            }
            let status: String = {
                if !loaded { return t("Loading…", "Inapakia…") }
                if let b = p.blocked { return b.at.isEmpty ? t("Blocked by the office", "Amezuiwa na ofisi") : t("Blocked by \(b.by.isEmpty ? "the office" : b.by) on \(Dates.day(b.at))", "Amezuiwa na \(b.by.isEmpty ? "ofisi" : b.by) tarehe \(Dates.day(b.at))") }
                if !p.known { return t("Not on the team list", "Hayupo kwenye orodha ya timu") }
                if let ls = p.lastSeen { return t("Active · last seen ", "Anatumia · alionekana ") + Dates.dateTime(ls) }
                return p.app ? t("Invited · hasn’t signed in yet", "Amealikwa · bado hajaingia") : t("Hasn’t signed in yet", "Bado hajaingia")
            }()
            Text(status).font(.mont(12, .semibold)).foregroundColor(p.blocked != nil ? C.bad : C.muted).multilineTextAlignment(.center).padding(.top, 8)
            if p.blocked == nil, let o = p.out { Text(t("Signed out of all phones on ", "Ametolewa kwenye simu zote tarehe ") + Dates.day(o)).font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.center) }
            if let why = p.blocked?.reason, !why.isEmpty { Text("“\(why)”").font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.center) }
        }.padding(.horizontal, 8)
    }

    private func line(_ icon: String, _ value: String, _ label: String, _ copied: @escaping () -> Void) -> some View {
        HStack(spacing: 8) {
            Icon(icon, size: 16, color: C.muted)
            Text(value).font(.bMedium).foregroundColor(C.ink).lineLimit(1)
            Spacer(minLength: 4)
            Button { UIPasteboard.general.string = value; Haptic.ok(); copied() } label: { Icon("copy", size: 19, color: C.blue).frame(width: 44, height: 44) }.buttonStyle(.plain).accessibilityLabel(label)
        }.padding(.top, 4)
    }

    @ViewBuilder private func subSheet(_ s: CtSub, _ p: Person, me: Bool, admin: Bool) -> some View {
        switch s {
        case .name: FieldSheet(p: p, field: "name", me: me, admin: admin) { to in said = ("ok", t("Renamed ✓", "Jina limebadilishwa ✓")); if me { m.prefs.name = to }; name = to; changed() }
        case .email: FieldSheet(p: p, field: "email", me: me, admin: admin) { _ in said = ("ok", t("Saved ✓", "Imehifadhiwa ✓")); changed() }
        case .phone: FieldSheet(p: p, field: "phone", me: me, admin: admin) { _ in said = ("ok", t("Saved ✓", "Imehifadhiwa ✓")); changed() }
        case .myphone: FieldSheet(p: p, field: "phone", me: me, admin: admin, profile: true) { _ in said = ("ok", t("Saved ✓", "Imehifadhiwa ✓")); changed() }
        case .role: FieldSheet(p: p, field: "role", me: me, admin: admin) { _ in said = ("ok", t("Saved ✓", "Imehifadhiwa ✓")); changed() }
        case .home: HomeSheet(p: p) { said = ("ok", t("Saved ✓", "Imehifadhiwa ✓")); changed() }
        case .invite: InviteSheet(p: p.json())
        case .newpin, .resetpin: NewPinSheet(p: p, reset: s == .resetpin)
        case .setpin: SetPinSheet(p: p)
        case .admin, .unadmin: AdminConfirmSheet(p: p.json(), makeAdmin: s == .admin) { said = ("ok", s == .admin ? t("\(p.name) is now an admin ✓", "\(p.name) sasa ni msimamizi ✓") : t("\(p.name) is no longer an admin ✓", "\(p.name) si msimamizi tena ✓")); changed() }
        case .block: AskSheet(p: p, action: "team-block") { said = ("ok", t("\(p.name) is blocked", "\(p.name) amezuiwa")); changed() }
        case .unblock: AskSheet(p: p, action: "team-unblock") { said = ("ok", t("\(p.name) can sign in again ✓", "\(p.name) anaweza kuingia tena ✓")); changed() }
        case .signout: AskSheet(p: p, action: "team-signout") { said = ("ok", t("Signed out of all phones ✓", "Ametolewa kwenye simu zote ✓")); changed() }
        case .remove: AskSheet(p: p, action: "team-remove") { changed(); DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { dismiss() } }
        case .move: AskSheet(p: p, action: "team-move", keepOpen: true) { changed() }
        case .givepin: AskSheet(p: p, action: "team-add", keepOpen: true) { changed() }
        case .jobs: JobsSheet(p: p) { id in sub = nil; leave { go("project", id) } }
        }
    }
}

/// The round X at the top right of a sheet.
struct CloseX: View {
    let action: () -> Void
    var body: some View {
        Button(action: action) { Icon("x", size: 18, color: C.ink).frame(width: 40, height: 40).background(C.bg).clipShape(Circle()) }
            .buttonStyle(.plain).accessibilityLabel(t("Close", "Funga")).frame(width: 44, height: 44)
    }
}

/// The menu row: an icon, the label, the current value under it, and a chevron.
private struct CtRow: View {
    let icon: String; let hue: String; let label: String; var value = ""; var red = false; var action: (() -> Void)?
    var body: some View {
        let hu = hue == "wa" ? Hue(fg: Color(hex: 0x25D366), bg: Color(hex: 0x25D366, alpha: 0.14)) : C.named(hue)
        Button { if let action { Haptic.tap(); action() } } label: {
            HStack(spacing: 12) {
                Icon(icon, size: 18, color: red ? C.bad : hu.fg).frame(width: 36, height: 36).background(red ? C.badBg : hu.bg).clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
                VStack(alignment: .leading, spacing: 1) {
                    Text(label).font(.tSmall).foregroundColor(red ? C.bad : C.ink)
                    if !value.isEmpty { Text(value).font(.bSmall).foregroundColor(C.muted).lineLimit(2).multilineTextAlignment(.leading) }
                }
                Spacer(minLength: 6)
                if action != nil { Icon("chev", size: 18, color: C.muted) }
            }.padding(.horizontal, 10).padding(.vertical, 8).frame(minHeight: 56).contentShape(Rectangle())
        }.buttonStyle(.plain).disabled(action == nil)
    }
}

/// A group of rows on a card, under a small label.
private struct CtGroup<Content: View>: View {
    let label: String
    @ViewBuilder var content: () -> Content
    init(_ label: String, @ViewBuilder content: @escaping () -> Content) { self.label = label; self.content = content }
    var body: some View {
        GroupLabel(label)
        VStack(spacing: 0) { content() }.padding(.vertical, 4).padding(.horizontal, 2)
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(C.line, lineWidth: 1))
    }
}

/// A role badge in the header.
private struct CtBadge: View {
    let text: String; let hue: String; var icon: String? = nil; var red = false
    var body: some View {
        let hu = C.named(hue)
        let fg = red ? C.bad : hu.fg, bg = red ? C.badBg : hu.bg
        HStack(spacing: 4) {
            if let icon { Icon(icon, size: 13, color: fg) }
            Text(text).font(.mont(12, .heavy)).foregroundColor(fg).lineLimit(1).fixedSize()
        }.padding(.horizontal, 9).padding(.vertical, 4).background(bg).clipShape(Capsule()).overlay(Capsule().stroke(C.sun ? fg : .clear, lineWidth: 1.5))
    }
}

/// The small sheet's frame: X, then the content, scrolling.
struct SubSheet<Content: View>: View {
    @ViewBuilder var content: (_ close: @escaping () -> Void) -> Content
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack { Spacer(); CloseX { dismiss() } }
                content { dismiss() }
            }.padding(.horizontal, 20).padding(.top, 14).padding(.bottom, 28)
        }
        .background(C.card.ignoresSafeArea())
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }
}

/// The small sheet's head: an icon tile and the title, centred.
struct SubHead: View {
    let icon: String; let hue: String; let title: String; var sub = ""; var red = false
    var body: some View {
        VStack(spacing: 0) {
            IconTile(icon: icon, tint: red ? C.bad : C.named(hue).fg, bg: red ? C.badBg : C.named(hue).bg, size: 56)
            Text(title).font(.tLarge).foregroundColor(C.ink).multilineTextAlignment(.center).padding(.top, 10)
            if !sub.isEmpty { Text(sub).font(.bMedium).foregroundColor(C.muted).multilineTextAlignment(.center).padding(.top, 2) }
        }.padding(.horizontal, 36).wide(.center)
    }
}

/// Points with ticks (or crosses) on a soft panel.
struct CtLines: View {
    let lines: [String]; var red = false; var icon = "check"
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(lines, id: \.self) { l in HStack(alignment: .top, spacing: 8) { Icon(icon, size: 16, color: C.ink).padding(.top, 2); Text(l).font(.bMedium).foregroundColor(C.ink) } }
        }.padding(14).wide().background(red ? C.badBg : C.named("slate").bg).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).padding(.top, 14)
    }
}

/// Cancel and the action, side by side.
struct CtYesNo: View {
    let yes: String; let busy: Bool; var red = false; let no: () -> Void; let go: () -> Void
    var body: some View {
        HStack(spacing: 8) {
            Btn(t("Cancel", "Ghairi"), light: true, small: true, wide: true, action: no)
            Button(action: go) {
                Text(yes).font(.mont(14, .bold)).foregroundColor(.white).padding(.horizontal, 16).frame(maxWidth: .infinity, minHeight: 44)
                    .background(busy ? AnyShapeStyle(C.off) : red ? AnyShapeStyle(C.bad) : AnyShapeStyle(BlueBrush)).clipShape(Capsule())
            }.buttonStyle(Press()).disabled(busy)
        }.padding(.top, 14)
    }
}

/// team-update with everything the server keeps (it replaces the whole entry), changing only [field].
private func updateBody(_ p: Person, _ field: String, _ value: String, admin: Bool) -> JSON {
    var b: JSON = ["action": "team-update", "name": p.name]
    if admin { b["home"] = p.home; b["admin"] = p.admin; b["email"] = p.email; b["phone"] = p.phone; b["role"] = p.role }
    else { b["email"] = p.email; b["phone"] = p.phone }
    b[field] = value
    return b
}

/// One field, Save, and (for e-mail and phone) Remove: name, e-mail, phone, or role/title.
private struct FieldSheet: View {
    @EnvironmentObject var m: AppModel
    let p: Person; let field: String; let me: Bool; let admin: Bool; var profile = false; let saved: (String) -> Void
    @State private var v = ""
    @State private var busy = false
    @State private var msg: String?
    var start: String { field == "name" ? p.name : field == "email" ? p.email : field == "phone" ? p.phone : p.role }
    var body: some View {
        SubSheet { close in
            Group {
                switch field {
                case "name": SubHead(icon: "edit", hue: "blue", title: t("Rename", "Badilisha jina"), sub: t("Their pins, visits and repair jobs stay linked to them.", "Mahali, ziara na kazi zake za matengenezo zinabaki zimeunganishwa naye."))
                case "email": SubHead(icon: "mail", hue: "teal", title: start.isEmpty ? t("Add e-mail", "Ongeza barua pepe") : t("Change e-mail", "Badilisha barua pepe"), sub: t("For alerts, invites and e-mails from the Office.", "Kwa taarifa, mialiko na barua pepe kutoka Ofisi."))
                case "phone": SubHead(icon: "phone", hue: "near", title: start.isEmpty ? t("Add phone", "Ongeza simu") : t("Change phone", "Badilisha simu"), sub: t("The team sees it, for calls and WhatsApp.", "Timu inaiona, kwa kupiga na WhatsApp."))
                default: SubHead(icon: "people", hue: "slate", title: t("Role or title", "Cheo au kazi"), sub: t("Optional. Shown under their name.", "Si lazima. Inaonyeshwa chini ya jina lake."))
                }
            }
            Field(field == "name" ? t("Name", "Jina") : field == "email" ? t("E-mail", "Barua pepe") : field == "phone" ? t("Phone", "Simu") : t("Role or title", "Cheo au kazi"), $v,
                  placeholder: field == "name" ? t("e.g. Grace Wanjiru", "mf. Grace Wanjiru") : field == "email" ? "name@rafikioutreach.org" : field == "phone" ? "+254 712 345 678" : t("e.g. Field officer, Kitale", "mf. Afisa wa uwanjani, Kitale"))
                .keyboardType(field == "phone" ? .phonePad : field == "email" ? .emailAddress : .default)
                .textInputAutocapitalization(field == "email" ? .never : .words)
            Btn(t("Save", "Hifadhi"), icon: "check", enabled: !busy && v.trimmingCharacters(in: .whitespaces) != start.trimmingCharacters(in: .whitespaces), wide: true) { send(v, close) }.padding(.top, 14)
            if (field == "email" || field == "phone") && !start.isEmpty {
                Button { send("", close) } label: {
                    HStack(spacing: 8) { Icon("trash", size: 18, color: C.bad); Text(field == "email" ? t("Remove e-mail", "Ondoa barua pepe") : t("Remove phone", "Ondoa simu")).font(.lLarge).foregroundColor(C.bad) }
                        .frame(maxWidth: .infinity, minHeight: 48).overlay(Capsule().stroke(C.bad.opacity(0.5), lineWidth: 1))
                }.buttonStyle(.plain).disabled(busy).padding(.top, 8)
            }
            Msg(msg, "bad")
        }
        .onAppear { v = start }
    }
    private func send(_ value: String, _ close: @escaping () -> Void) {
        let x = value.trimmingCharacters(in: .whitespaces)
        if field == "name" && x.isEmpty { msg = t("Type a name.", "Andika jina."); return }
        if field == "email" && !x.isEmpty && x.range(of: #"^[^@\s]+@[^@\s]+\.[^@\s]+$"#, options: .regularExpression) == nil { msg = t("That doesn’t look like an e-mail address.", "Hiyo haionekani kama anwani ya barua pepe."); return }
        if field == "phone" && !x.isEmpty && x.filter(\.isNumber).count < 7 { msg = t("That phone number looks too short.", "Namba hiyo ya simu inaonekana fupi mno."); return }
        if x == start { close(); return }
        busy = true; msg = nil
        Task {
            if profile {
                let me0 = m.snap?.me
                let err = await m.saveProfile(nick: me0?.nick ?? "", phone: x, home: (me0?.home ?? "").isEmpty ? p.home : me0!.home)
                busy = false
                if err == nil { saved(x); close() } else { msg = err }
                return
            }
            let r = field == "name" ? await m.api.admin(["action": "team-rename", "name": p.name, "to": x]) : await m.api.admin(updateBody(p, field, x, admin: admin))
            busy = false
            if r.ok { saved(x); close() } else { msg = contactWhy(r.reason) }
        }
    }
}

/// Home country: a short list to tap, then Save.
private struct HomeSheet: View {
    @EnvironmentObject var m: AppModel
    let p: Person; let saved: () -> Void
    @State private var v = ""
    @State private var busy = false
    @State private var msg: String?
    var body: some View {
        SubSheet { close in
            SubHead(icon: "pin", hue: "green", title: t("Home country", "Nchi ya nyumbani"), sub: t("Their map and lists start in this country.", "Ramani na orodha zao zinaanzia nchi hii."))
            VStack(spacing: 0) {
                ForEach([("", t("All countries", "Nchi zote"))] + COUNTRIES, id: \.0) { k, l in
                    Button { v = k } label: {
                        HStack { Text(l).font(.bLarge).foregroundColor(C.ink); Spacer(); if v == k { Icon("check", size: 20, color: C.blue) } }.padding(.horizontal, 14).frame(minHeight: 52).contentShape(Rectangle())
                    }.buttonStyle(.plain)
                }
            }.overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.line, lineWidth: 1)).padding(.top, 12)
            Btn(t("Save", "Hifadhi"), icon: "check", enabled: !busy && v != p.home, wide: true) {
                busy = true; msg = nil
                Task { let r = await m.api.admin(updateBody(p, "home", v, admin: true)); busy = false; if r.ok { saved(); close() } else { msg = contactWhy(r.reason) } }
            }.padding(.top, 14)
            Msg(msg, "bad")
        }.onAppear { v = p.home }
    }
}

/// A new PIN: shown for a minute with Copy, WhatsApp and SMS, then hidden for good.
struct PinShown: View {
    let who: String; let pin: String; var phone = ""; var home = ""
    @State private var left = PIN_SHOWN_SECONDS
    @State private var copied = false
    var body: some View {
        CardV(fill: left > 0 ? C.okBg : C.named("slate").bg) {
            Text(t("PIN for ", "PIN ya ") + who).font(.tSmall).foregroundColor(left > 0 ? C.ok : C.muted)
            if left <= 0 {
                Text(t("Hidden now, to keep it safe. If they didn’t get it, make a new PIN.", "Imefichwa sasa, kwa usalama. Kama hawakuipata, tengeneza PIN mpya.")).font(.bMedium).foregroundColor(C.text).padding(.top, 6)
            } else {
                HStack {
                    Text(pin).font(.mont(30, .heavy)).tracking(8).foregroundColor(C.ink).padding(.vertical, 6)
                    Spacer()
                    Text(t("hides in \(left) s", "inajificha baada ya sekunde \(left)")).font(.lMedium).foregroundColor(C.muted)
                }
                Text(t("Send it privately; it can’t be shown again.", "Mtumie kwa siri; haiwezi kuonyeshwa tena.")).font(.bSmall).foregroundColor(C.text)
                Btn(copied ? t("Copied ✓", "Imenakiliwa ✓") : t("Copy", "Nakili"), icon: "copy", light: true, small: true, wide: true) { UIPasteboard.general.string = pin; copied = true }.padding(.top, 10)
                HStack(spacing: 8) {
                    Btn("WhatsApp", icon: "b:whatsapp", light: true, small: true, wide: true) { Links.whatsapp(phone, home, text: pinMessage(who, pin)) }
                    Btn("SMS", icon: "phone", light: true, small: true, wide: true) { Talk.sms(phone, pinMessage(who, pin)) }
                }.padding(.top, 8)
            }
        }
        .padding(.top, 12)
        .task(id: pin) { left = PIN_SHOWN_SECONDS; while left > 0 { try? await Task.sleep(nanoseconds: 1_000_000_000); left -= 1 } }
    }
}

/// New PIN (or "Reset and send a new PIN"): asks, then shows it once with Copy, WhatsApp and SMS.
private struct NewPinSheet: View {
    @EnvironmentObject var m: AppModel
    let p: Person; let reset: Bool
    @State private var pin: String?
    @State private var busy = false
    @State private var msg: String?
    var body: some View {
        SubSheet { close in
            SubHead(icon: reset ? "refresh" : "key", hue: reset ? "blue" : "green", title: reset ? t("Reset and send a new PIN", "Badilisha na utume PIN mpya") : t("New PIN for \(p.name)", "PIN mpya ya \(p.name)"))
            if let pn = pin {
                PinShown(who: p.name, pin: pn, phone: p.phone, home: p.home)
                Btn(t("Done", "Imekamilika"), light: true, wide: true) { close() }.padding(.top, 12)
            } else {
                CtLines(lines: reset ? [t("PINs are stored locked, so nobody can look up their old one.", "PIN zinahifadhiwa zikiwa zimefungwa, kwa hiyo hakuna anayeweza kuona ya zamani."),
                                        t("This makes a new PIN. Their old PIN stops working.", "Hii inatengeneza PIN mpya. PIN ya zamani itaacha kufanya kazi."),
                                        t("Then send it to them by WhatsApp or SMS.", "Kisha mtumie kwa WhatsApp au SMS.")]
                                     : [t("Their old PIN stops working.", "PIN ya zamani itaacha kufanya kazi."),
                                        t("The new PIN shows once, for a minute. Send it to them privately.", "PIN mpya inaonyeshwa mara moja, kwa dakika moja. Mtumie kwa siri.")])
                CtYesNo(yes: reset ? t("Make and send a new PIN", "Tengeneza na utume PIN mpya") : t("Make a new PIN", "Tengeneza PIN mpya"), busy: busy, no: close) {
                    busy = true; msg = nil
                    Task { let r = await m.api.admin(["action": "team-pin", "name": p.name]); busy = false; if r.ok { pin = r.json.str("pin") } else { msg = contactWhy(r.reason) } }
                }
                Msg(msg, "bad")
            }
        }
    }
}

/// Set a PIN: the admin types 4 to 8 digits; easy ones and one already in use are refused.
private struct SetPinSheet: View {
    @EnvironmentObject var m: AppModel
    let p: Person
    @State private var v = ""
    @State private var set: String?
    @State private var busy = false
    @State private var msg: String?
    var body: some View {
        SubSheet { close in
            SubHead(icon: "lock", hue: "teal", title: t("Set a PIN for \(p.name)", "Weka PIN ya \(p.name)"))
            if let done = set {
                Text(t("PIN set ✓", "PIN imewekwa ✓")).font(.tMedium).foregroundColor(C.ok).wide(.center).padding(.top, 10)
                PinShown(who: p.name, pin: done, phone: p.phone, home: p.home)
                Btn(t("Done", "Imekamilika"), light: true, wide: true) { close() }.padding(.top, 12)
            } else {
                Text(t("4 to 8 digits, not easy to guess: no 1234, 0000 or one digit over and over. Their old PIN stops working.", "Tarakimu 4 hadi 8, isiwe rahisi kubahatisha: si 1234, 0000 wala tarakimu moja inayojirudia. PIN ya zamani itaacha kufanya kazi."))
                    .font(.bMedium).foregroundColor(C.text).multilineTextAlignment(.center).wide(.center).padding(.top, 10)
                Field(t("New PIN", "PIN mpya"), Binding(get: { v }, set: { v = String($0.filter(\.isNumber).prefix(8)); msg = nil }), placeholder: "4–8", number: true)
                Btn(t("Set PIN", "Weka PIN"), icon: "check", enabled: !busy && v.count >= 4, wide: true) {
                    if let bad = pinProblem(v) { msg = contactWhy(bad); return }
                    busy = true; msg = nil
                    Task { let r = await m.api.admin(["action": "team-set-pin", "name": p.name, "pin": v.trimmingCharacters(in: .whitespaces)]); busy = false; if r.ok { set = v } else { msg = contactWhy(r.reason) } }
                }.padding(.top, 14)
                Msg(msg, "bad")
            }
        }
    }
}

/// Block, unblock, sign out of all phones, remove, move to an app PIN, give an app PIN: says what happens, then does it.
private struct AskSheet: View {
    @EnvironmentObject var m: AppModel
    let p: Person; let action: String; var keepOpen = false; let done: () -> Void
    @State private var reason = ""
    @State private var busy = false
    @State private var msg: String?
    @State private var pin: String?
    @State private var techOpen = false
    var body: some View {
        let me = p.isCalled(m.prefs.name)
        let red = action == "team-block" || action == "team-remove"
        SubSheet { close in
            Group {
                switch action {
                case "team-block": SubHead(icon: "x", hue: "coral", title: t("Block \(p.name)?", "Mzuie \(p.name)?"), red: true)
                case "team-unblock": SubHead(icon: "check", hue: "green", title: t("Unblock \(p.name)?", "Mwondolee \(p.name) zuio?"))
                case "team-signout": SubHead(icon: "logout", hue: "slate", title: me ? t("Sign out of all your phones?", "Toka kwenye simu zako zote?") : t("Sign \(p.name) out of all phones?", "Mtoe \(p.name) kwenye simu zote?"))
                case "team-remove": SubHead(icon: "trash", hue: "coral", title: t("Remove \(p.name) from the team?", "Mwondoe \(p.name) kwenye timu?"), red: true)
                case "team-move": SubHead(icon: "key", hue: "green", title: t("Move \(p.name) to an app PIN?", "Mhamishe \(p.name) kwenye PIN ya programu?"))
                default: SubHead(icon: "key", hue: "green", title: t("Give \(p.name) an app PIN?", "Mpe \(p.name) PIN ya programu?"))
                }
            }
            if let pn = pin {
                if action == "team-move" {
                    Text(t("Done ✓ The entry for \(p.name) in Cloudflare can now be removed.", "Imekamilika ✓ Ingizo la \(p.name) kwenye Cloudflare sasa linaweza kuondolewa.")).font(.mont(14, .semibold)).foregroundColor(C.ok).multilineTextAlignment(.center).wide(.center).padding(.top, 10)
                }
                PinShown(who: p.name, pin: pn, phone: p.phone, home: p.home)
                Btn(t("Done", "Imekamilika"), light: true, wide: true) { close() }.padding(.top, 12)
            } else {
                CtLines(lines: lines(me), red: red, icon: red ? "x" : "check")
                if action == "team-block" { Field(t("Reason (optional)", "Sababu (si lazima)"), $reason, placeholder: t("e.g. phone lost", "mf. simu imepotea")) }
                if action == "team-move" {
                    VStack(alignment: .leading, spacing: 0) {
                        Button { withAnimation { techOpen.toggle() } } label: {
                            HStack { Text(t("Technical details", "Maelezo ya kiufundi")).font(.lLarge).foregroundColor(C.ink); Spacer(); Icon("chev", size: 16, color: C.muted).rotationEffect(.degrees(techOpen ? 90 : 0)) }
                                .padding(.horizontal, 14).frame(minHeight: 48).contentShape(Rectangle())
                        }.buttonStyle(.plain)
                        if techOpen {
                            Text(t("Their name and PIN are in PIN_USERS (admins also in PIN_ADMINS) in the Cloudflare settings. After the move, take them out of there.", "Jina na PIN yake viko kwenye PIN_USERS (wasimamizi pia kwenye PIN_ADMINS) kwenye mipangilio ya Cloudflare. Baada ya kuhamisha, viondoe hapo."))
                                .font(.bSmall).foregroundColor(C.muted).padding(.horizontal, 14).padding(.bottom, 12)
                        }
                    }.overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(C.line, lineWidth: 1)).padding(.top, 10)
                }
                CtYesNo(yes: yes, busy: busy, red: red, no: close) {
                    busy = true; msg = nil
                    Task {
                        var body: JSON = ["action": action, "name": p.name]
                        if action == "team-block" { body["reason"] = reason.trimmingCharacters(in: .whitespaces) }
                        if action == "team-add" { body["home"] = p.home; body["admin"] = p.admin; body["email"] = p.email; body["phone"] = p.phone }
                        let r = await m.api.admin(body)
                        busy = false
                        if !r.ok { msg = contactWhy(r.reason); return }
                        let np = r.json.str("pin")
                        if keepOpen && !np.isEmpty && np != "null" { pin = np; done() } else { done(); close() }
                    }
                }
                Msg(msg, "bad")
            }
        }
    }
    private var yes: String {
        switch action {
        case "team-block": return t("Block", "Zuia"); case "team-unblock": return t("Unblock", "Ondoa zuio"); case "team-signout": return t("Sign out", "Toa")
        case "team-remove": return t("Remove", "Ondoa"); case "team-move": return t("Move", "Hamisha"); default: return t("Make a PIN", "Tengeneza PIN")
        }
    }
    private func lines(_ me: Bool) -> [String] {
        switch action {
        case "team-block": return [t("Their PIN stops working and they’re signed out on every phone straight away.", "PIN yake itaacha kufanya kazi na atatolewa kwenye kila simu mara moja."),
                                   t("Their history is kept, and they stay on the team list marked Blocked.", "Historia yake inabaki, na anabaki kwenye orodha ya timu akiwa na alama Amezuiwa."),
                                   t("You can unblock them later; the same PIN works again.", "Unaweza kumwondolea zuio baadaye; PIN ileile itafanya kazi tena.")]
        case "team-unblock": return [t("They can sign in again with the same PIN.", "Anaweza kuingia tena kwa PIN ileile.")]
        case "team-signout": return [me ? t("Every phone you’re signed in on is signed out straight away, this one too.", "Kila simu uliyoingia nayo itatoka mara moja, pamoja na hii.") : t("Every phone they’re signed in on is signed out straight away.", "Kila simu aliyoingia nayo itatoka mara moja."),
                                     me ? t("Your PIN keeps working, so you can sign in again.", "PIN yako inaendelea kufanya kazi, kwa hiyo unaweza kuingia tena.") : t("Their PIN keeps working, so they can sign in again.", "PIN yake inaendelea kufanya kazi, kwa hiyo anaweza kuingia tena.")]
        case "team-remove": return [t("Their PIN stops working straight away.", "PIN yake itaacha kufanya kazi mara moja."), t("Their pins, visits and history are kept.", "Mahali, ziara na historia yake vinabaki.")]
        case "team-move": return [t("They get a new PIN from the app, shown once. Send it to them privately.", "Atapata PIN mpya ya programu, inaonyeshwa mara moja. Mtumie kwa siri."),
                                  t("Then you can manage them here. Their entry in Cloudflare can be removed afterwards.", "Kisha unaweza kumsimamia hapa. Ingizo lake kwenye Cloudflare linaweza kuondolewa baadaye.")]
        default: return [t("They get their own PIN, shown once. Send it to them privately.", "Atapata PIN yake, inaonyeshwa mara moja. Mtumie kwa siri."), t("They can still sign in with monday.com too.", "Bado anaweza kuingia kwa monday.com pia.")]
        }
    }
}

/// Make admin or remove admin: says what changes, then saves. Nobody can remove their own admin role.
struct AdminConfirmSheet: View {
    @EnvironmentObject var m: AppModel
    let p: JSON; let makeAdmin: Bool; let done: () -> Void
    @State private var busy = false
    @State private var msg: String?
    var body: some View {
        let name = p.str("name")
        let selfRemove = !makeAdmin && name.trimmingCharacters(in: .whitespaces).lowercased() == m.prefs.name.trimmingCharacters(in: .whitespaces).lowercased()
        SubSheet { close in
            SubHead(icon: "crown", hue: "amber", title: makeAdmin ? t("Make \(name) an admin?", "Mfanye \(name) msimamizi?") : t("Remove \(name)’s admin role?", "Ondoa nafasi ya msimamizi ya \(name)?"))
            if selfRemove {
                Text(t("You can’t remove your own admin role. Ask another admin to do it.", "Huwezi kuondoa nafasi yako mwenyewe ya msimamizi. Mwombe msimamizi mwingine afanye hivyo.")).font(.mont(14, .semibold)).foregroundColor(C.bad)
                    .padding(14).wide().background(C.badBg).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).padding(.top, 14)
                Btn(t("Close", "Funga"), light: true, wide: true) { close() }.padding(.top, 14)
            } else {
                CtLines(lines: makeAdmin ? [t("They’ll see the Office tab: repairs, costs, visits, the team map and the team.", "Ataona kichupo cha Ofisi: matengenezo, gharama, ziara, ramani ya timu na timu."),
                                            t("They can add people, make PINs and send e-mails.", "Anaweza kuongeza watu, kutengeneza PIN na kutuma barua pepe."),
                                            t("They get e-mail alerts when a site stops working, if they have an e-mail.", "Atapata taarifa kwa barua pepe eneo likiacha kufanya kazi, kama ana barua pepe.")]
                                         : [t("The Office tab goes away for them the next time the app refreshes.", "Kichupo cha Ofisi kitaondoka kwake programu ikijisasisha tena."),
                                            t("They keep their PIN and can still pin sites and report visits.", "Anabaki na PIN yake na bado anaweza kuweka mahali na kuripoti ziara."),
                                            t("No more admin e-mail alerts.", "Hakuna tena taarifa za barua pepe za msimamizi.")], icon: makeAdmin ? "check" : "arrow")
                CtYesNo(yes: makeAdmin ? t("Make admin", "Mfanye msimamizi") : t("Remove admin", "Ondoa usimamizi"), busy: busy, red: !makeAdmin, no: close) {
                    busy = true; msg = nil
                    Task {
                        func s(_ k: String) -> String { let v = p.str(k); return v == "null" ? "" : v }
                        let x = await m.api.admin(["action": "team-update", "name": name, "home": s("home"), "email": s("email"), "phone": s("phone"), "role": s("role"), "admin": makeAdmin])
                        busy = false
                        if x.ok { done(); close() } else { msg = x.reason == "self" ? t("You can’t remove your own admin role.", "Huwezi kuondoa nafasi yako mwenyewe ya msimamizi.") : contactWhy(x.reason) }
                    }
                }
                Msg(msg, "bad")
            }
        }
    }
}

/// The app invite by e-mail: the server's ready-made message, previewed here, then sent to this one person. Also shared by WhatsApp or SMS.
struct InviteSheet: View {
    @EnvironmentObject var m: AppModel
    let p: JSON
    @State private var preview: (String, String)?
    @State private var err: String?
    @State private var busy = false
    @State private var sent = false
    var body: some View {
        let name = p.str("name")
        let email = p.str("email") == "null" ? "" : p.str("email")
        let phone = p.str("phone") == "null" ? "" : p.str("phone")
        let share = inviteText(name, from: m.prefs.name, admin: p.bool("admin"))
        SubSheet { close in
            SubHead(icon: "mail", hue: "teal", title: t("Send app invite", "Tuma mwaliko wa programu"), sub: t("To ", "Kwa ") + name + (email.isEmpty ? "" : " · \(email)"))
            Text(t("The PIN is never in the e-mail. Send it to them separately.", "PIN haiwi kamwe kwenye barua pepe. Wapelekee kando.")).font(.mont(12, .semibold)).foregroundColor(C.warn).multilineTextAlignment(.center).wide(.center).padding(.top, 8)
            VStack(alignment: .leading, spacing: 0) {
                if let pv = preview {
                    Text(t("Subject", "Kichwa")).font(.lSmall).foregroundColor(C.muted)
                    Text(pv.0).font(.tSmall).foregroundColor(C.ink)
                    Rectangle().fill(C.line).frame(height: 1).padding(.vertical, 8)
                    ForEach(Array(htmlLines(pv.1).enumerated()), id: \.offset) { _, l in
                        if l.lowercased() == "open rafiki pin" || l.lowercased() == "fungua rafiki pin" {
                            Text(l).font(.lLarge).foregroundColor(.white).padding(.horizontal, 18).padding(.vertical, 10).background(BlueBrush).clipShape(Capsule()).padding(.vertical, 6)
                        } else { Text(l).font(.bMedium).foregroundColor(C.text).padding(.vertical, 3) }
                    }
                } else { Text(err ?? t("Getting the preview…", "Inaandaa onyesho…")).font(.bMedium).foregroundColor(err != nil ? C.bad : C.muted) }
            }.padding(14).wide().background(C.bg).clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(C.line, lineWidth: 1)).padding(.top, 12)
            if sent { Msg(t("Invite sent to \(name) ✓ Now send their PIN separately.", "Mwaliko umetumwa kwa \(name) ✓ Sasa mtumie PIN yake kando."), "ok") }
            else {
                Btn(t("Send the invite", "Tuma mwaliko"), icon: "mail", enabled: !busy && preview != nil, wide: true) {
                    busy = true; err = nil
                    Task { let r = await m.api.mail(["action": "invite", "to": name]); busy = false; if r.ok { sent = true } else { err = mailWhy(r.reason, r.json) } }
                }.padding(.top, 14)
            }
            if preview != nil { Msg(err, "bad") }
            GroupLabel(t("Or share the invite", "Au shiriki mwaliko"))
            HStack(spacing: 8) {
                Btn("WhatsApp", icon: "b:whatsapp", light: true, small: true, wide: true) { Links.whatsapp(phone, p.str("home"), text: share) }
                Btn("SMS", icon: "text", light: true, small: true, wide: true) { Talk.sms(phone, share) }
            }
            if sent { Btn(t("Done", "Imekamilika"), light: true, wide: true) { close() }.padding(.top, 12) }
        }
        .task {
            let r = await m.api.mail(["action": "invite", "to": name, "preview": true])
            if r.ok { preview = (r.json.str("subject"), r.json.str("html")); err = nil } else { err = mailWhy(r.reason, r.json) }
        }
    }
}

/// The text lines of an HTML e-mail.
func htmlLines(_ html: String) -> [String] {
    var s = html.replacingOccurrences(of: #"(?is)<(style|script|head)[^>]*>.*?</\1>"#, with: "", options: .regularExpression)
    s = s.replacingOccurrences(of: #"(?i)<br\s*/?>|</p>|</div>|</h\d>|</tr>|</li>"#, with: "\n", options: .regularExpression)
    s = s.replacingOccurrences(of: #"<[^>]+>"#, with: "", options: .regularExpression)
    for (a, b) in [("&nbsp;", " "), ("&amp;", "&"), ("&lt;", "<"), ("&gt;", ">"), ("&quot;", "\""), ("&#39;", "’"), ("&rsquo;", "’"), ("&mdash;", "—"), ("&middot;", "·")] { s = s.replacingOccurrences(of: a, with: b) }
    return s.components(separatedBy: "\n").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
}

/// The open repair jobs given to them: tap one to open the project.
private struct JobsSheet: View {
    @EnvironmentObject var m: AppModel
    let p: Person; let open: (String) -> Void
    var body: some View {
        SubSheet { _ in
            SubHead(icon: "wrench", hue: "repairs", title: t("Repair jobs assigned", "Kazi za matengenezo alizopewa"), sub: p.name)
            if let s = m.snap {
                ForEach(s.repairs.filter { p.isCalled($0.value.assignee) }.sorted { $0.key < $1.key }, id: \.key) { id, r in
                    let pr = s.projects.first { $0.id == id }
                    Button { open(id) } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(pr?.title ?? (r.title.isEmpty ? id : r.title)).font(.tSmall).foregroundColor(C.ink)
                                Text([stateName(r.state == "notworking" ? "notworking" : "repair"), r.due.isEmpty ? nil : t("due ", "mwisho ") + Dates.due(r.due)].compactMap { $0 }.joined(separator: " · ")).font(.bSmall).foregroundColor(C.muted)
                            }
                            Spacer(); Icon("chev", size: 18, color: C.muted)
                        }.padding(.horizontal, 12).padding(.vertical, 8).frame(minHeight: 56).overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(C.line, lineWidth: 1)).contentShape(Rectangle())
                    }.buttonStyle(.plain).padding(.top, 8)
                }
            }
        }
    }
}

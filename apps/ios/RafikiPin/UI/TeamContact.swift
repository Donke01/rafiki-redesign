import SwiftUI
import UIKit

// One contact sheet for every person (Android 2.20–2.21): the same wherever a person is tapped (Me › Team, the team
// feed, notifications…). A clear menu like a phone's contact card; each action does one thing in its own small sheet.
// Anything can open it: Contact.show("Grace Wanjiru").

struct ContactWho: Identifiable, Equatable { let name: String; var id: String { name } }

@MainActor final class Contact: ObservableObject {
    static let shared = Contact()
    @Published var who: ContactWho?
    /// Bumped when someone's details changed here, so lists of people reload.
    @Published var changed = 0
    static func show(_ name: String?) {
        let n = (name ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if isPerson(n) { Haptic.tap(); shared.who = ContactWho(name: n) }
    }
    /// A team member's name: not a blank, a dash or a community (SMS/WhatsApp) reporter.
    nonisolated static func isPerson(_ n: String) -> Bool {
        !n.isEmpty && n != "—" && n != "-" && !n.hasPrefix("SMS from") && !n.hasPrefix("WhatsApp from") && n.lowercased() != "rafiki pin"
            && n != "Community report" && n != "Ripoti ya jamii"
    }
}

/// What blocked means for one person: when, by whom and why.
struct TeamBlocked: Equatable { var at = ""; var by = ""; var reason = "" }

/// One person on the team, as the contact sheet needs them. `src`: "app", "cloudflare", "monday" or "" (unknown).
struct TeamPerson: Equatable {
    var name: String; var home = ""; var email = ""; var phone = ""; var admin = false; var src = ""
    var lastSeen: String? = nil; var role = ""; var aka: [String] = []; var blocked: TeamBlocked? = nil; var known = true
    var adminCf = false; var alsoCloudflare = false; var out: String? = nil

    var app: Bool { src == "app" }
    var cloudflare: Bool { src == "cloudflare" }
    var names: [String] { [name] + aka }
    func isCalled(_ n: String) -> Bool { names.contains { $0.trimmingCharacters(in: .whitespaces).lowercased() == n.trimmingCharacters(in: .whitespaces).lowercased() } }
    var json: JSON { ["name": name, "home": home, "email": email, "phone": phone, "admin": admin, "role": role, "source": src] }

    static func from(_ o: JSON) -> TeamPerson {
        func s(_ k: String) -> String { let v = o.str(k).trimmingCharacters(in: .whitespaces); return v == "null" ? "" : v }
        let aka = (o.arr("aka") ?? []).compactMap { $0 as? String }.filter { !$0.isEmpty }
        var blocked: TeamBlocked? = nil
        if let b = o.obj("blocked") { blocked = TeamBlocked(at: b.str("at"), by: b.str("by"), reason: b.str("reason")) } else if o.bool("blocked") { blocked = TeamBlocked() }
        return TeamPerson(name: s("name"), home: s("home"), email: s("email"), phone: s("phone"), admin: o.bool("admin"), src: s("src").isEmpty ? s("source") : s("src"),
                          lastSeen: s("lastSeen").isEmpty ? nil : s("lastSeen"), role: s("role"), aka: aka, blocked: blocked,
                          adminCf: o.bool("adminCf"), alsoCloudflare: o.bool("alsoCloudflare"), out: s("out").isEmpty ? nil : s("out"))
    }
}

/// A PIN the app refuses before asking the server: not 4 to 8 digits, all one digit, a run like 1234, or a repeat.
func teamPinProblem(_ pin: String) -> String? {
    let p = pin.trimmingCharacters(in: .whitespaces)
    guard p.count >= 4, p.count <= 8, p.allSatisfy({ $0.isNumber && $0.isASCII }) else { return "digits" }
    let d = p.compactMap { $0.wholeNumberValue }
    if Set(d).count == 1 { return "weak-pin" }
    let steps = Set(zip(d, d.dropFirst()).map { $1 - $0 })
    if steps == [1] || steps == [-1] { return "weak-pin" }
    if p.count % 2 == 0 && p.prefix(p.count / 2) == p.suffix(p.count / 2) { return "weak-pin" }
    return nil
}

/// "Thu 24 Oct", or "24 Oct 2027" in another year.
func teamDay(_ iso: String) -> String {
    guard let d = When.parse(iso) ?? DateField.parse(String(iso.prefix(10))) else { return iso }
    let f = DateFormatter(); f.locale = Locale(identifier: Lang.sw ? "sw" : "en_GB")
    f.dateFormat = Calendar.current.component(.year, from: d) == Calendar.current.component(.year, from: Date()) ? "d MMM" : "d MMM yyyy"
    return f.string(from: d)
}

// MARK: - the sheet

private enum CtSub: String, Identifiable {
    case photo, name, email, phone, myphone, home, role, invite, newpin, resetpin, setpin, admin, unadmin, block, unblock, signout, remove, move, givepin, jobs
    var id: String { rawValue }
}

struct ContactSheet: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.dismiss) private var dismiss
    @State var name: String
    @State private var person: TeamPerson?
    @State private var loaded = false
    @State private var tick = 0
    @State private var sub: CtSub?
    @State private var said: String?
    @State private var then: (() -> Void)?

    init(name: String) { _name = State(initialValue: name) }

    private var iAmAdmin: Bool { m.snap?.admin == true }
    private var p: TeamPerson { person ?? TeamPerson(name: name, known: false) }
    private var me: Bool { same(name, m.prefs.name) || person?.isCalled(m.prefs.name) == true }
    private func same(_ a: String, _ b: String) -> Bool { a.trimmingCharacters(in: .whitespaces).lowercased() == b.trimmingCharacters(in: .whitespaces).lowercased() }

    func load() async {
        var list: [JSON]?
        if iAmAdmin {
            let r = await m.api.adminGet("team")
            list = r.ok ? r.json.objs("team") : nil
        } else { list = await m.loadPeople() }
        if let list { TeamPhotos.shared.absorbPeople(list) }
        let found = list?.map(TeamPerson.from).first { $0.isCalled(name) }
        // your own: how you sign in comes with the list (Me › Settings) when the team list doesn't say
        if var f = found, f.isCalled(m.prefs.name), f.src.isEmpty {
            let k = m.me.kind
            f.src = ["app", "cloudflare", "monday"].contains(k) ? k : "app"
            person = f
        } else if let f = found { person = f } else if person?.isCalled(name) != true { person = TeamPerson(name: name, known: false) }
        loaded = true
    }

    /// Close this sheet, then do something (open a project, a Settings page…).
    func leave(_ action: @escaping () -> Void) { then = action; dismiss() }
    func changed() { tick += 1; Contact.shared.changed += 1 }
    func say(_ s: String) { said = s; Task { try? await Task.sleep(nanoseconds: 2_500_000_000); if said == s { said = nil } } }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                CtHeader(p: p, loaded: loaded, canPhoto: p.known && (me || iAmAdmin)) { sub = .photo }
                    .overlay(alignment: .topTrailing) { CloseX { dismiss() } }
                if !p.email.isEmpty || !p.phone.isEmpty {
                    VStack(spacing: 0) {
                        if !p.email.isEmpty { CtLine(icon: "envelope", value: p.email, copyLabel: t("Copy e-mail", "Nakili barua pepe")) { say(t("E-mail copied ✓", "Barua pepe imenakiliwa ✓")) } }
                        if !p.phone.isEmpty { CtLine(icon: "phone", value: p.phone, copyLabel: t("Copy phone", "Nakili simu")) { say(t("Phone copied ✓", "Simu imenakiliwa ✓")) } }
                    }.padding(.top, 10)
                }
                if let said { Msg(said, "ok") }
                if !me { CtTiles(p: p, iAmAdmin: iAmAdmin) { leave { m.tab("office") } } }
                if p.known { details }
                if iAmAdmin && p.known { access }
                work
            }
            .padding(.horizontal, 20).padding(.top, 12).padding(.bottom, 28)
        }
        .storySheet()
        .task(id: "\(tick)|\(name)") { await load() }
        .task { if m.activity == nil { await m.loadActivity() } }
        .sheet(item: $sub) { s in subSheet(s).environmentObject(m) }
        .onDisappear { if let f = then { then = nil; f() } }
        .onAppear { if m.demo, let s = Demo.arg("-sub"), let c = CtSub(rawValue: s) { Task { try? await Task.sleep(nanoseconds: 700_000_000); sub = c } } }
    }

    // --- Details: admins (app people), or the person themselves; others see them read-only
    private var details: some View {
        let canEdit = p.known && p.app && (iAmAdmin || me)
        let selfFixed = me && p.known && !p.app
        let hasPhoto = TeamPhotos.shared.url(p.name) != nil
        return CtGroup(label: t("Details", "Maelezo")) {
            if p.known && (me || iAmAdmin) {
                CtRow(icon: "camera", hue: "purple", label: hasPhoto ? t("Change photo", "Badilisha picha") : t("Add photo", "Weka picha"),
                      value: hasPhoto ? t("Or remove it", "Au iondoe") : (me ? t("Shown next to your name", "Inaonyeshwa karibu na jina lako") : t("Shown next to their name", "Inaonyeshwa karibu na jina lake"))) { sub = .photo }
            }
            if canEdit {
                CtRow(icon: "square.and.pencil", hue: "blue", label: t("Name", "Jina"), value: p.name) { sub = .name }
                CtRow(icon: "envelope", hue: "teal", label: p.email.isEmpty ? t("Add e-mail", "Ongeza barua pepe") : t("Change e-mail", "Badilisha barua pepe"), value: p.email.isEmpty ? t("None yet", "Bado hakuna") : p.email) { sub = .email }
                CtRow(icon: "phone", hue: "green", label: p.phone.isEmpty ? t("Add phone", "Ongeza simu") : t("Change phone", "Badilisha simu"), value: p.phone.isEmpty ? t("None yet", "Bado hakuna") : p.phone) { sub = .phone }
                if iAmAdmin {
                    CtRow(icon: "mappin.and.ellipse", hue: "green", label: t("Home country", "Nchi ya nyumbani"), value: countryName(p.home) ?? t("All countries", "Nchi zote")) { sub = .home }
                    CtRow(icon: "person.2", hue: "slate", label: t("Role or title", "Cheo au kazi"), value: p.role.isEmpty ? t("Not set (optional)", "Haijawekwa (si lazima)") : p.role) { sub = .role }
                }
            } else if selfFixed {
                CtRow(icon: "square.and.pencil", hue: "blue", label: t("Name", "Jina"), value: p.name, action: nil)
                CtRow(icon: "envelope", hue: "teal", label: t("E-mail", "Barua pepe"), value: p.email.isEmpty ? t("None yet", "Bado hakuna") : p.email, action: nil)
                Text(t("Set by the office", "Imewekwa na ofisi")).font(.bSmall).foregroundColor(C.muted).padding(.horizontal, 12).padding(.vertical, 4)
                CtRow(icon: "phone", hue: "green", label: p.phone.isEmpty ? t("Add phone", "Ongeza simu") : t("Change phone", "Badilisha simu"), value: p.phone.isEmpty ? t("None yet", "Bado hakuna") : p.phone) { sub = .myphone }
            } else {
                CtRow(icon: "mappin.and.ellipse", hue: "green", label: t("Home country", "Nchi ya nyumbani"), value: countryName(p.home) ?? t("All countries", "Nchi zote"), action: nil)
                if !p.role.isEmpty { CtRow(icon: "person.2", hue: "slate", label: t("Role or title", "Cheo au kazi"), value: p.role, action: nil) }
                if iAmAdmin && p.cloudflare && !me {
                    Text(t("Their PIN is set in Cloudflare, so their details can’t be changed here. Move them to an app PIN below to manage them here.", "PIN yake imewekwa kwenye Cloudflare, kwa hiyo maelezo yake hayawezi kubadilishwa hapa. Mhamishe kwenye PIN ya programu hapa chini ili umsimamie hapa."))
                        .font(.bSmall).foregroundColor(C.muted).padding(.horizontal, 12).padding(.vertical, 8).fixedSize(horizontal: false, vertical: true)
                }
            }
            if me { CtRow(icon: "key", hue: "amber", label: t("Change my PIN", "Badilisha PIN yangu"), value: t("In Settings", "Kwenye Mipangilio")) { leave { m.openSettings("pin") } } }
        }
    }

    // --- Access: admins only
    private var access: some View {
        CtGroup(label: t("Access", "Ufikiaji")) {
            if p.app {
                CtRow(icon: "square.and.arrow.up", hue: "purple", label: t("Send app invite", "Tuma mwaliko wa programu"), value: t("E-mail, WhatsApp or SMS. Never with the PIN.", "Barua pepe, WhatsApp au SMS. Kamwe pamoja na PIN.")) { sub = .invite }
                CtRow(icon: "key", hue: "green", label: t("New PIN", "PIN mpya"), value: t("Makes one and shows it once", "Inatengeneza moja na kuionyesha mara moja")) { sub = .newpin }
                CtRow(icon: "lock", hue: "teal", label: t("Set a PIN", "Weka PIN"), value: t("You type 4 to 8 digits", "Unaandika tarakimu 4 hadi 8")) { sub = .setpin }
                if p.alsoCloudflare { CtRow(icon: "cloud", hue: "purple", label: t("Also in Cloudflare", "Pia yuko kwenye Cloudflare"), value: t("Their old entry there can be removed", "Ingizo lake la zamani huko linaweza kuondolewa"), action: nil) }
                CtRow(icon: "arrow.clockwise", hue: "blue", label: t("Reset and send a new PIN", "Badilisha na utume PIN mpya"), value: t("If they forgot it: by WhatsApp or SMS", "Kama ameisahau: kwa WhatsApp au SMS")) { sub = .resetpin }
                if !me && p.adminCf {
                    CtRow(icon: "crown.fill", hue: "amber", label: t("Admin set in Cloudflare", "Msimamizi kupitia Cloudflare"), value: t("Can’t be made a field officer in the app", "Hawezi kufanywa afisa wa uwanjani kwenye programu"), action: nil)
                } else if !me {
                    CtRow(icon: "crown.fill", hue: "amber", label: p.admin ? t("Remove admin", "Ondoa usimamizi") : t("Make admin", "Mfanye msimamizi"),
                          value: p.admin ? t("They keep their PIN", "Anabaki na PIN yake") : t("They’ll see the Office", "Ataona Ofisi")) { sub = p.admin ? .unadmin : .admin }
                }
            } else if p.cloudflare {
                CtRow(icon: "cloud", hue: "purple", label: t("PIN set in Cloudflare", "PIN imewekwa kwenye Cloudflare"), value: t("Can’t be changed from the app", "Haiwezi kubadilishwa kwenye programu"), action: nil)
                CtRow(icon: "key", hue: "green", label: t("Move to an app PIN", "Hamia PIN ya programu"), value: t("Then you can manage them here", "Kisha unaweza kumsimamia hapa")) { sub = .move }
            } else {
                CtRow(icon: "key", hue: "green", label: t("Give them an app PIN", "Mpe PIN ya programu"), value: t("They sign in with monday.com now", "Kwa sasa anaingia kwa monday.com")) { sub = .givepin }
            }
            if !me {
                if p.blocked == nil { CtRow(icon: "xmark", hue: "coral", label: t("Block", "Zuia"), value: t("Stops their PIN and signs them out", "Inasimamisha PIN yake na kumtoa"), red: true) { sub = .block } }
                else { CtRow(icon: "checkmark", hue: "green", label: t("Unblock", "Ondoa zuio"), value: t("The same PIN works again", "PIN ileile itafanya kazi tena")) { sub = .unblock } }
            }
            CtRow(icon: "rectangle.portrait.and.arrow.right", hue: "slate", label: t("Sign out of all phones", "Toa kwenye simu zote"), value: me ? t("Including this one. Your PIN stays.", "Pamoja na hii. PIN yako inabaki.") : t("Keeps their PIN", "PIN yake inabaki")) { sub = .signout }
            if !me && p.app { CtRow(icon: "trash", hue: "coral", label: t("Remove from the team", "Ondoa kwenye timu"), value: t("Their history is kept", "Historia yake inabaki"), red: true) { sub = .remove } }
        }
    }

    // --- Their work
    private var work: some View {
        let jobs = (m.snap?.repairs ?? [:]).filter { p.isCalled($0.value.assignee) }
        return CtGroup(label: me ? t("Your work", "Kazi yako") : t("Their work", "Kazi yake")) {
            TeamPersonActivity(names: p.names, act: m.activity, snap: m.snap) { id in leave { m.openProject(id) } }.padding(.horizontal, 10)
            if iAmAdmin && p.known { CtRow(icon: "calendar", hue: "blue", label: t("Assign a visit", "Gawa ziara"), value: t("In the Office, under Visits", "Kwenye Ofisi, chini ya Ziara")) { leave { m.tab("office") } } }
            CtRow(icon: "wrench.adjustable", hue: "amber", label: t("Repair jobs assigned", "Kazi za matengenezo alizopewa"),
                  value: jobs.isEmpty ? t("None open", "Hakuna iliyo wazi") : jobs.count == 1 ? t("1 open", "1 iko wazi") : t("\(jobs.count) open", "\(jobs.count) ziko wazi"),
                  action: jobs.isEmpty ? nil : { sub = .jobs })
        }
    }

    @ViewBuilder private func subSheet(_ s: CtSub) -> some View {
        let done = { sub = nil }
        switch s {
        case .photo: MePhotoSheet(name: p.name, me: me) { said in say(said) }
        case .name: TeamFieldSheet(p: p, field: "name", me: me, admin: iAmAdmin, profile: false) { to in say(t("Renamed ✓", "Jina limebadilishwa ✓")); if me { m.prefs.name = to }; name = to; changed() }
        case .email: TeamFieldSheet(p: p, field: "email", me: me, admin: iAmAdmin, profile: false) { _ in say(t("Saved ✓", "Imehifadhiwa ✓")); changed() }
        case .phone: TeamFieldSheet(p: p, field: "phone", me: me, admin: iAmAdmin, profile: false) { _ in say(t("Saved ✓", "Imehifadhiwa ✓")); changed() }
        case .myphone: TeamFieldSheet(p: p, field: "phone", me: me, admin: iAmAdmin, profile: true) { _ in say(t("Saved ✓", "Imehifadhiwa ✓")); changed() }
        case .role: TeamFieldSheet(p: p, field: "role", me: me, admin: iAmAdmin, profile: false) { _ in say(t("Saved ✓", "Imehifadhiwa ✓")); changed() }
        case .home: TeamHomeSheet(p: p) { say(t("Saved ✓", "Imehifadhiwa ✓")); changed() }
        case .invite: TeamInviteSheet(p: p)
        case .newpin, .resetpin: TeamNewPinSheet(p: p, reset: s == .resetpin)
        case .setpin: TeamSetPinSheet(p: p)
        case .admin, .unadmin:
            TeamAdminSheet(p: p, makeAdmin: s == .admin) {
                say(s == .admin ? t("\(p.name) is now an admin ✓", "\(p.name) sasa ni msimamizi ✓") : t("\(p.name) is no longer an admin ✓", "\(p.name) si msimamizi tena ✓")); changed()
            }
        case .block: TeamAskSheet(p: p, action: "team-block") { say(t("\(p.name) is blocked", "\(p.name) amezuiwa")); changed() }
        case .unblock: TeamAskSheet(p: p, action: "team-unblock") { say(t("\(p.name) can sign in again ✓", "\(p.name) anaweza kuingia tena ✓")); changed() }
        case .signout: TeamAskSheet(p: p, action: "team-signout") { say(t("Signed out of all phones ✓", "Ametolewa kwenye simu zote ✓")); changed() }
        case .remove: TeamAskSheet(p: p, action: "team-remove") { changed(); done(); dismiss() }
        case .move: TeamAskSheet(p: p, action: "team-move") { changed() }
        case .givepin: TeamAskSheet(p: p, action: "team-add") { changed() }
        case .jobs: TeamJobsSheet(p: p) { id in sub = nil; leave { m.openProject(id) } }
        }
    }
}

// MARK: - parts of the sheet

/// The header: picture (with a camera to change it), name, role badges, how they sign in, last seen.
private struct CtHeader: View {
    let p: TeamPerson; let loaded: Bool; let canPhoto: Bool; let photo: () -> Void
    @ObservedObject private var ph = TeamPhotos.shared
    var body: some View {
        let live = p.blocked == nil && p.lastSeen.flatMap { When.parse($0) }.map { Calendar.current.isDateInToday($0) } == true
        let dot: Color = p.blocked != nil ? Color(hex: 0xD93A2E) : live ? Color(hex: 0x22B566) : p.lastSeen != nil ? Color(hex: 0xF0A630) : Color(hex: 0xAEB8C1)
        VStack(spacing: 0) {
            ZStack {
                Button(action: photo) {
                    PersonAvatar(name: p.name, size: live ? 90 : 96)
                        .padding(live ? 3 : 0).overlay(Circle().stroke(live ? Color(hex: 0x22B566) : .clear, lineWidth: 3))
                        .padding(4).background(Circle().fill(C.card))
                }.buttonStyle(.plain).disabled(!canPhoto)
                    .accessibilityLabel(ph.url(p.name) != nil ? t("Change photo", "Badilisha picha") : t("Add photo", "Weka picha"))
                if canPhoto { SFIcon("camera", size: 16, color: C.blue2).frame(width: 28, height: 28).background(Circle().fill(C.card)).overlay(Circle().stroke(C.line)).offset(x: -38, y: 38) }
                if p.known { Circle().fill(dot).frame(width: 16, height: 16).padding(3).background(Circle().fill(C.card)).offset(x: 38, y: 38) }
                if p.admin { SFIcon("crown.fill", size: 16, color: C.dark ? Color(hex: 0xF2C14E) : Color(hex: 0xE0A100)).frame(width: 30, height: 30).background(Circle().fill(C.card)).overlay(Circle().stroke(C.line)).offset(x: -38, y: -38) }
            }.frame(width: 110, height: 110)
            Text(p.name).font(.mont(22, .heavy)).foregroundColor(C.ink).multilineTextAlignment(.center).padding(.top, 10).padding(.horizontal, 40).fixedSize(horizontal: false, vertical: true)
            if !p.role.isEmpty { Text(p.role).font(.bMedium).foregroundColor(C.text).multilineTextAlignment(.center) }
            if p.known {
                MeFlow(spacing: 6) {
                    if p.admin { CtBadge(text: t("Admin", "Msimamizi"), hue: "amber", icon: "crown.fill") } else { CtBadge(text: t("Field", "Uwanjani"), hue: "slate", icon: "person.2") }
                    if p.src == "app" { CtBadge(text: t("PIN from app", "PIN ya programu"), hue: "green", icon: "key") }
                    if p.src == "cloudflare" { CtBadge(text: t("PIN in Cloudflare", "PIN ya Cloudflare"), hue: "purple", icon: "cloud") }
                    if p.src == "monday" { CtBadge(text: "monday.com", hue: "blue", icon: "person.2") }
                    if p.blocked != nil { CtBadge(text: t("Blocked", "Amezuiwa"), hue: "coral", icon: "xmark", red: true) }
                }.padding(.top, 8)
            }
            HStack(spacing: 6) {
                if p.blocked == nil && p.lastSeen != nil { Circle().fill(Color(hex: 0x22B566)).frame(width: 8, height: 8).opacity(live ? 1 : 0.5) }
                Text(status).font(.mont(12, .semibold)).foregroundColor(p.blocked != nil ? C.bad : p.lastSeen != nil ? C.hue("near").fg : C.muted).multilineTextAlignment(.center)
            }.padding(.top, 8)
            if p.blocked == nil, let out = p.out { Text(t("Signed out of all phones on ", "Ametolewa kwenye simu zote tarehe ") + teamDay(out)).font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.center) }
            if let why = p.blocked?.reason, !why.isEmpty { Text("“\(why)”").font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.center) }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 18)
        .background(alignment: .top) {
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .fill(LinearGradient(colors: [C.hue("near").bg, C.hue("due").bg, C.hue("pin").bg], startPoint: .leading, endPoint: .trailing))
                .frame(height: 92).opacity(Look.mode == "sun" ? 0 : 1)
        }
    }
    private var status: String {
        if !loaded { return t("Loading…", "Inapakia…") }
        if let b = p.blocked { return b.at.isEmpty ? t("Blocked by the office", "Amezuiwa na ofisi") : t("Blocked by \(b.by.isEmpty ? "the office" : b.by) on \(teamDay(b.at))", "Amezuiwa na \(b.by.isEmpty ? "ofisi" : b.by) tarehe \(teamDay(b.at))") }
        if !p.known { return t("Not on the team list", "Hayupo kwenye orodha ya timu") }
        if let s = p.lastSeen { return t("Active · last seen ", "Anatumia · alionekana ") + When.text(s) }
        if p.app { return t("Invited · hasn’t signed in yet", "Amealikwa · bado hajaingia") }
        return t("Hasn’t signed in yet", "Bado hajaingia")
    }
}

/// A role badge in the header.
private struct CtBadge: View {
    let text: String; let hue: String; var icon: String? = nil; var red = false
    var body: some View {
        let hu = meHue(hue)
        let fg = red ? C.bad : hu.fg, bg = red ? C.badBg : hu.bg
        HStack(spacing: 4) {
            if let icon { SFIcon(icon, size: 13, color: fg) }
            Text(text).font(.mont(12, .heavy)).foregroundColor(fg).lineLimit(1).fixedSize()
        }
        .padding(.horizontal, 9).padding(.vertical, 4).background(Capsule().fill(bg))
        .overlay(Capsule().stroke(Look.mode == "sun" ? fg : .clear, lineWidth: 1.5))
    }
}

/// E-mail or phone under the name, with a copy button.
private struct CtLine: View {
    let icon: String; let value: String; let copyLabel: String; let copied: () -> Void
    var body: some View {
        HStack(spacing: 8) {
            SFIcon(icon, size: 16, color: C.muted)
            Text(value).font(.bMedium).foregroundColor(C.ink).lineLimit(1).truncationMode(.middle).frame(maxWidth: .infinity, alignment: .leading)
            Button { UIPasteboard.general.string = value; Haptic.ok(); copied() } label: { SFIcon("doc.on.doc", size: 19, color: C.blue).frame(width: 44, height: 44) }
                .buttonStyle(.plain).accessibilityLabel(copyLabel)
        }
    }
}

/// Call, WhatsApp, SMS, E-mail and Share, as big tiles.
private struct CtTiles: View {
    let p: TeamPerson; let iAmAdmin: Bool; let office: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 8) {
                if !p.phone.isEmpty {
                    CtTile(label: t("Call", "Piga"), circle: Color(hex: 0x1C7C4A), fg: .white, icon: "phone.fill") { Share.open("tel:" + p.phone.replacingOccurrences(of: " ", with: "")) }
                    CtTile(label: "WhatsApp", circle: Color(hex: 0x25D366), fg: .white, icon: "message.fill") { Share.open("https://wa.me/" + waNumber(p.phone, p.home)) }
                    CtTile(label: "SMS", circle: C.hue("due").bg, fg: C.hue("due").fg, icon: "text.bubble") { Share.open("sms:" + p.phone.replacingOccurrences(of: " ", with: "")) }
                }
                if !p.email.isEmpty && (iAmAdmin || p.phone.isEmpty) {
                    CtTile(label: t("E-mail", "Barua pepe"), circle: C.hue("pin").bg, fg: C.hue("pin").fg, icon: "envelope") { Share.open("mailto:" + p.email) }
                }
                CtTile(label: t("Share", "Shiriki"), circle: C.card, fg: C.ink, icon: "square.and.arrow.up") { teamShareContact(p) }
            }.padding(.top, 16)
            if p.email.isEmpty && p.phone.isEmpty {
                Text(t("No e-mail or phone on the team list for them yet.", "Bado hakuna barua pepe wala simu yake kwenye orodha ya timu.")).font(.bSmall).foregroundColor(C.muted)
                    .padding(.horizontal, 12).padding(.vertical, 8).fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

private struct CtTile: View {
    let label: String; let circle: Color; let fg: Color; let icon: String; let action: () -> Void
    var body: some View {
        Button { Haptic.tap(); action() } label: {
            VStack(spacing: 8) {
                SFIcon(icon, size: 22, color: fg).frame(width: 46, height: 46).background(Circle().fill(circle))
                // one line that gets a little smaller rather than cut ("WhatsApp", "Barua pepe" with five tiles on a small phone)
                FitWords(label, size: 12, weight: .heavy, color: C.ink, lines: 1, align: .center, min: 0.6)
            }
            .padding(.horizontal, 2).padding(.vertical, 12)
            .frame(maxWidth: .infinity, minHeight: 96)
            .background(C.hue("slate").bg)
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(C.line, lineWidth: Look.mode == "sun" ? 2 : 1))
        }.buttonStyle(Press())
    }
}

/// A group of rows on a card, under a small label.
struct CtGroup<Content: View>: View {
    let label: String
    @ViewBuilder var content: () -> Content
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            MeGroupLabel(label).padding(.leading, 6).padding(.top, -10)
            content()
        }
        .padding(.horizontal, 6).padding(.top, 4).padding(.bottom, 6)
        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(C.card))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).stroke(C.line, lineWidth: Look.mode == "sun" ? 2 : 1))
        .padding(.top, 14)
    }
}

/// The menu row: an icon, the label, the current value under it, and a chevron.
struct CtRow: View {
    let icon: String; let hue: String; let label: String; var value = ""; var red = false; var action: (() -> Void)?
    init(icon: String, hue: String, label: String, value: String = "", red: Bool = false, action: (() -> Void)?) {
        self.icon = icon; self.hue = hue; self.label = label; self.value = value; self.red = red; self.action = action
    }
    var body: some View {
        let hu = meHue(hue)
        let row = HStack(spacing: 12) {
            SFIcon(icon, size: 18, color: red ? C.bad : hu.fg).frame(width: 36, height: 36).background(RoundedRectangle(cornerRadius: 11, style: .continuous).fill(red ? C.badBg : hu.bg))
            VStack(alignment: .leading, spacing: 1) {
                Text(label).font(.tSmall).foregroundColor(red ? C.bad : C.ink).fixedSize(horizontal: false, vertical: true)
                if !value.isEmpty { Text(value).font(.bSmall).foregroundColor(C.muted).lineLimit(2).fixedSize(horizontal: false, vertical: true) }
            }.frame(maxWidth: .infinity, alignment: .leading)
            if action != nil { Icon("chev", size: 18, color: C.muted) }
        }
        .padding(.horizontal, 10).padding(.vertical, 8).frame(minHeight: 56).contentShape(Rectangle())
        if let action { Button { Haptic.tap(); action() } label: { row }.buttonStyle(.plain) } else { row }
    }
}

/// Small views that wrap onto more lines when they don't fit (role badges).
struct MeFlow: Layout {
    var spacing: CGFloat = 6
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let w = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, line: CGFloat = 0, widest: CGFloat = 0
        for s in subviews {
            let sz = s.sizeThatFits(.unspecified)
            if x > 0 && x + sz.width > w { y += line + spacing; x = 0; line = 0 }
            x += sz.width + spacing; line = max(line, sz.height); widest = max(widest, x - spacing)
        }
        return CGSize(width: w.isFinite ? w : widest, height: y + line)
    }
    func placeSubviews(in b: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        // each line centred
        var lines: [[(Int, CGSize)]] = [[]]
        var x: CGFloat = 0
        for (i, s) in subviews.enumerated() {
            let sz = s.sizeThatFits(.unspecified)
            if x > 0 && x + sz.width > b.width { lines.append([]); x = 0 }
            lines[lines.count - 1].append((i, sz)); x += sz.width + spacing
        }
        var y = b.minY
        for l in lines {
            let w = l.reduce(0) { $0 + $1.1.width } + spacing * CGFloat(max(0, l.count - 1))
            var cx = b.minX + max(0, (b.width - w) / 2)
            let h = l.map(\.1.height).max() ?? 0
            for (i, sz) in l { subviews[i].place(at: CGPoint(x: cx, y: y), proposal: ProposedViewSize(sz)); cx += sz.width + spacing }
            y += h + spacing
        }
    }
}

/// "Share contact": their card for the phone's contacts, WhatsApp, e-mail…
func teamShareContact(_ p: TeamPerson) {
    var v = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:" + p.name.replacingOccurrences(of: ",", with: "\\,") + "\r\n"
    let parts = p.name.split(separator: " ").map(String.init)
    v += "N:" + (parts.count > 1 ? parts.last ?? "" : "") + ";" + (parts.count > 1 ? parts.dropLast().joined(separator: " ") : (parts.first ?? "")) + ";;;\r\nORG:RAFIKI Outreach\r\n"
    if !p.role.isEmpty { v += "TITLE:" + p.role.replacingOccurrences(of: ",", with: "\\,") + "\r\n" }
    if !p.phone.isEmpty { v += "TEL;TYPE=CELL:" + p.phone + "\r\n" }
    if !p.email.isEmpty { v += "EMAIL:" + p.email + "\r\n" }
    v += "END:VCARD\r\n"
    let safe = p.name.filter { $0.isLetter || $0.isNumber || $0 == " " || $0 == "-" }.trimmingCharacters(in: .whitespaces)
    let f = FileManager.default.temporaryDirectory.appendingPathComponent((safe.isEmpty ? "contact" : safe) + ".vcf")
    if (try? v.write(to: f, atomically: true, encoding: .utf8)) != nil { Share.items([f]) }
    else { Share.items([[p.name, p.role, p.phone, p.email].filter { !$0.isEmpty }.joined(separator: "\n")]) }
}

/// Their pins, visits and fixes: today and this month. Tap one to open the project.
struct TeamPersonActivity: View {
    let names: [String]; let act: [JSON]?; let snap: Snapshot?; let open: (String) -> Void
    @State private var more = false
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let act {
                let list = month(act)
                let cal = Calendar.current
                let nToday = list.filter { When.parse($0.str("at")).map { cal.isDateInToday($0) } == true }.count
                HStack(spacing: 8) {
                    count(nToday, t("today", "leo"))
                    count(list.count, t("this month", "mwezi huu"))
                }.padding(.top, 8)
                if list.isEmpty { Text(t("Nothing yet this month.", "Bado hakuna kitu mwezi huu.")).font(.bMedium).foregroundColor(C.muted).padding(.top, 10) }
                ForEach(Array(list.prefix(more ? 30 : 6).enumerated()), id: \.offset) { _, e in row(e) }
                if list.count > 6 {
                    Button(more ? t("Show less", "Onyesha kidogo") : t("Show all \(list.count)", "Onyesha zote \(list.count)")) { withAnimation { more.toggle() } }
                        .font(.lMedium).foregroundColor(C.blue).padding(8).frame(minHeight: 44)
                }
            } else { Text(t("Loading…", "Inapakia…")).font(.bMedium).foregroundColor(C.muted).padding(.top, 8) }
        }
    }
    private func month(_ act: [JSON]) -> [JSON] {
        let cal = Calendar.current
        let first = cal.date(from: cal.dateComponents([.year, .month], from: Date())) ?? Date()
        let low = names.map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
        return act.filter { e in
            low.contains(e.str("by").trimmingCharacters(in: .whitespaces).lowercased()) && ["pin", "visit", "fixed", "new"].contains(e.str("a")) && (When.parse(e.str("at")) ?? .distantPast) >= first
        }.sorted { $0.str("at") > $1.str("at") }
    }
    private func count(_ n: Int, _ l: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("\(n)").font(.tLarge).foregroundColor(C.deep)
            Text(l).font(.lMedium).foregroundColor(C.deep).lineLimit(1).minimumScaleFactor(0.8)
        }.padding(.horizontal, 12).padding(.vertical, 8).frame(maxWidth: .infinity, alignment: .leading).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
    private func row(_ e: JSON) -> some View {
        let id = e.str("id"), p = snap?.projects.first { $0.id == id }
        let ic: (String, String) = { switch e.str("a") { case "pin": return ("pin", "pin"); case "visit": return ("clip", "near"); case "fixed": return ("check", "near"); default: return ("plus", "new") } }()
        let hu = C.hue(ic.1)
        let what: String = { switch e.str("a") { case "pin": return t("Pinned", "Aliweka mahali"); case "visit": return t("Visited", "Alitembelea"); case "fixed": return t("Fixed", "Alirekebisha"); default: return t("Added", "Aliongeza") } }()
        return Button { if p != nil { open(id) } } label: {
            HStack(spacing: 10) {
                Icon(ic.0, size: 17, color: hu.fg).frame(width: 34, height: 34).background(Circle().fill(hu.bg))
                VStack(alignment: .leading, spacing: 1) {
                    Text(p?.title ?? e.str("t")).font(.tSmall).foregroundColor(C.ink).lineLimit(1)
                    Text(what + (e.str("a") == "visit" && !e.str("s").isEmpty ? " · " + stateName(e.str("s")) : "") + " · " + ago(e.str("at"))).font(.bSmall).foregroundColor(C.muted).lineLimit(2)
                }.frame(maxWidth: .infinity, alignment: .leading)
                if p != nil { Icon("chev", size: 18, color: C.muted) }
            }.padding(.vertical, 6).padding(.horizontal, 4).frame(minHeight: 52).contentShape(Rectangle())
        }.buttonStyle(.plain).padding(.top, 6)
    }
}

/// A person's picture that opens their contact sheet (at least 44 points to tap).
struct PersonTap<Content: View>: View {
    let name: String
    @ViewBuilder var content: () -> Content
    var body: some View {
        if Contact.isPerson(name.trimmingCharacters(in: .whitespaces)) {
            Button { Contact.show(name) } label: { content().frame(minWidth: 44, minHeight: 44).contentShape(Rectangle()) }
                .buttonStyle(.plain).accessibilityLabel(name)
        } else { content() }
    }
}

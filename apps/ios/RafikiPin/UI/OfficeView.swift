import SwiftUI
import UIKit

/// The Office: tools for admins. All the sections as tiles until one is picked; then a strip of chips, so the
/// section starts near the top. Same server actions as the web and Android apps (Android's OfficeScreen.kt).
struct OfficeView: View {
    @EnvironmentObject var m: AppModel
    @State private var sec = "overview"
    @State private var strip = false

    var body: some View {
        if let snap = m.snap {
            let secs: [(String, String, String)] = [
                ("overview", t("Overview", "Muhtasari"), "drop"), ("repairs", t("Repairs", "Matengenezo"), "wrench"), ("costs", t("Costs", "Gharama"), "coins"), ("visits", t("Visits", "Ziara"), "cal"),
                ("teammap", t("Team map", "Ramani ya timu"), "pin"), ("plan", t("Plan a week", "Panga wiki"), "compass"),
                ("review", t("Review", "Kagua"), "inbox"), ("team", t("Team", "Timu"), "people"), ("cleanup", t("Clean-up", "Safisha data"), "spark"), ("export", t("Export", "Hamisha"), "upload"),
                ("sms", t("Community", "Jamii"), "chat"), ("donors", t("Donors", "Wafadhili"), "link"), ("qr", t("QR codes", "Misimbo ya QR"), "qr"), ("mail", t("Send an e\u{2011}mail", "Tuma barua pepe"), "mail"),
            ]
            let badge = { (k: String) in k == "review" ? snap.reviewOpen : k == "repairs" ? snap.repairs.count : 0 }
            ScrollViewReader { sv in
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(t("Office", "Ofisi")).font(.hLarge).foregroundColor(C.ink).frame(maxWidth: .infinity).id("top")
                        if !strip {
                            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 4), spacing: 8) {
                                ForEach(Array(secs.enumerated()), id: \.element.0) { i, x in
                                    Tile(label: x.1, icon: x.2, selected: sec == x.0, badge: badge(x.0)) { Haptic.tap(); pick(x.0) }.accessibilityIdentifier("o_\(x.0)").rise(i, step: 0.035)
                                }
                            }.padding(.top, 8)
                        } else {
                            ScrollViewReader { hs in
                                ScrollView(.horizontal, showsIndicators: false) {
                                    HStack(spacing: 8) {
                                        StripChip(label: t("All", "Zote"), icon: "grid", on: false) { withAnimation { strip = false; sec = "overview" } }
                                        ForEach(secs, id: \.0) { k, l, ic in StripChip(label: l, icon: ic, on: sec == k, badge: badge(k)) { sec = k }.id(k) }
                                    }.padding(.vertical, 2)
                                }
                                .onAppear { hs.scrollTo(sec, anchor: .center) }
                                .onChange(of: sec) { k in withAnimation { hs.scrollTo(k, anchor: .center) } }
                            }.padding(.top, 10)
                        }
                        VStack(alignment: .leading, spacing: 0) {
                            switch sec {
                            case "overview": OcvOverview(s: snap) { pick($0) }
                            case "repairs": RepairsSection(s: snap)
                            case "costs": OcvCosts(s: snap)
                            case "visits": OcvVisits(s: snap)
                            case "teammap": TeamMapSection(s: snap)
                            case "plan": PlanWeekSection(s: snap)
                            case "review": ReviewSection()
                            case "team": TeamSection()
                            case "cleanup": Cleanup(s: snap)
                            case "sms": CommunityReports()
                            case "donors": DonorsSection(s: snap)
                            case "qr": QrStickerSection(s: snap)
                            case "mail": MailSection(s: snap)
                            default: Export(s: snap)
                            }
                        }.padding(.top, 14).id(sec)
                        Spacer().frame(height: 24)
                    }.padding(16)
                }
                .onChange(of: sec) { _ in sv.scrollTo("top", anchor: .top) }
            }
            .onAppear {
                if m.demo, let s = Demo.arg("-section") { sec = s; strip = true }
                follow()
            }
            .onChange(of: m.officeTick) { _ in follow() }
        }
    }
    private func follow() {
        if MailNav.preset != nil { sec = "mail"; strip = true }
        if let s = TeamGo.section { sec = s; strip = true; TeamGo.section = nil }
    }
    private func pick(_ k: String) { withAnimation(Motion.on ? .easeOut(duration: 0.2) : nil) { sec = k; strip = true } }
}

/// One section in the Office's strip of chips.
private struct StripChip: View {
    let label: String; let icon: String; let on: Bool; var badge = 0; let action: () -> Void
    var body: some View {
        Button { Haptic.tap(); action() } label: {
            HStack(spacing: 6) {
                Icon(icon, size: 16, color: on ? .white : C.blue)
                Text(label).font(.mont(13, .bold)).foregroundColor(on ? .white : C.ink).lineLimit(1)
                if badge > 0 { Text("\(badge)").font(.mont(10, .bold)).foregroundColor(on ? RedC : .white).padding(.horizontal, 6).padding(.vertical, 1).background(on ? Color.white : RedC).clipShape(Capsule()) }
            }.padding(.horizontal, 12).frame(minHeight: 40).background(on ? C.selected : C.card).clipShape(Capsule()).overlay(Capsule().stroke(on ? .clear : C.line, lineWidth: 1))
        }.buttonStyle(Press())
    }
}

/// People who can sign in (from the Team list), for "assigned to".
final class Team: ObservableObject {
    @Published var people: [JSON] = []
    func load(_ m: AppModel) async { people = await m.api.adminGet("team").json.objs("team") }
}

func feedIcon(_ a: String) -> String { ocvFeedIcon(a) }
func verb(_ a: String) -> String {
    switch a {
    case "pin": return t("pinned", "aliweka mahali pa"); case "visit": return t("reported a visit to", "aliripoti ziara ya"); case "fixed": return t("marked fixed", "aliweka imerekebishwa")
    case "new": return t("added a new site:", "aliongeza eneo jipya:"); case "undo": return t("undid a pin on", "alitendua mahali pa")
    case "request": return t("asked to move the confirmed location of", "aliomba kuhamisha mahali palipothibitishwa pa")
    case "approve": return t("approved", "aliidhinisha"); case "reject": return t("turned down", "alikataa"); case "job": return t("updated the repair job on", "alisasisha kazi ya matengenezo ya")
    case "assign": return t("assigned", "aligawa"); case "team": return t("team:", "timu:"); case "ask": return t("asked for a second approval on", "aliomba idhini ya pili ya")
    case "settings": return t("changed a setting:", "alibadilisha mpangilio:"); default: return a
    }
}

/// What the test e-mail did: sent, or why not.
func mailResult(_ x: Reply) -> String {
    let detail = x.json.str("detail")
    if x.ok { return t("Sent ✓ Check the inbox.", "Imetumwa ✓ Angalia barua pepe.") }
    if x.reason == "no-recipients" { return t("No e-mail addresses yet.", "Bado hakuna anwani za barua pepe.") }
    if x.reason == "no-mail" { return t("Sending e-mail isn’t set up yet. Whoever looks after the website can switch it on.", "Kutuma barua pepe bado hakujawekwa. Anayesimamia tovuti anaweza kuiwasha.") }
    if !detail.isEmpty { return t("Not sent. Resend said: ", "Haijatumwa. Resend imesema: ") + detail }
    if x.status == 0 { return t("Not sent: no connection. Try again with signal.", "Haijatumwa: hakuna mtandao. Jaribu tena ukiwa na mtandao.") }
    return t("Not sent. Please try again in a moment.", "Haijatumwa. Tafadhali jaribu tena baadaye.")
}

// MARK: - Team

private let CrownGold = Color(hex: 0xE0A100)
private let CrownGoldDark = Color(hex: 0xF2C14E)

/// A round picture with initials; admins get a little crown on top.
struct TeamAvatar: View {
    let name: String; let admin: Bool; var size: CGFloat = 44; var st = ""
    var body: some View {
        let hu = C.named(nameHue(name))
        ZStack(alignment: .topTrailing) {
            Group {
                if name.isEmpty { Icon("plus", size: size * 0.45, color: hu.fg) }
                else { Text(initialsOf(name)).font(.mont(size * 0.34, .heavy)).foregroundColor(hu.fg) }
            }.frame(width: size, height: size).background(Circle().fill(hu.bg)).overlay(Circle().stroke(st == "today" ? StToday : C.sun ? C.ink : .clear, lineWidth: st == "today" ? 2.5 : 2))
                .overlay(alignment: .bottomTrailing) {
                    if !st.isEmpty { Circle().fill(stColor(st)).frame(width: size * 0.26 - 5, height: size * 0.26 - 5).padding(2.5).background(Circle().fill(C.card)).offset(x: 1, y: 1) }
                }
                .padding(.top, size * 0.18).padding(.trailing, size * 0.18)
            if admin {
                Image(systemName: "crown.fill").font(.system(size: size * 0.26)).foregroundColor(C.dark ? CrownGoldDark : CrownGold)
                    .frame(width: size * 0.5, height: size * 0.5).background(Circle().fill(C.card)).overlay(Circle().stroke(C.line, lineWidth: 1))
                    .accessibilityLabel(t("Admin", "Msimamizi"))
            }
        }
    }
}

private struct TeamChip: View {
    let text: String; let hu: Hue; var icon: String? = nil; var crown = false
    var body: some View {
        HStack(spacing: 4) {
            if crown { Image(systemName: "crown.fill").font(.system(size: 10)).foregroundColor(hu.fg) } else if let icon { Icon(icon, size: 12, color: hu.fg) }
            Text(text).font(.mont(11, .heavy)).foregroundColor(hu.fg).lineLimit(1)
        }.padding(.horizontal, 8).padding(.vertical, 3).background(hu.bg).clipShape(Capsule()).overlay(Capsule().stroke(C.sun ? hu.fg : .clear, lineWidth: 1.5))
    }
}

private struct TeamChips: View {
    let p: JSON
    var body: some View {
        let h = p.str("home"), src = p.str("src").isEmpty ? p.str("source") : p.str("src")
        HStack(spacing: 4) {
            if p.bool("admin") { TeamChip(text: t("Admin", "Msimamizi"), hu: C.named("amber"), crown: true) }
            if src == "cloudflare" { TeamChip(text: t("PIN set by the office", "PIN ya ofisi"), hu: C.named("purple"), icon: "key") }
            else if src == "monday" { TeamChip(text: "monday.com", hu: C.named("blue"), icon: "people") }
            else { TeamChip(text: t("PIN from app", "PIN ya programu"), hu: C.named("green"), icon: "key") }
            if !h.isEmpty && h != "null" { TeamChip(text: h.uppercased(), hu: C.named("slate"), icon: "pin") }
            if p.obj("blocked") != nil { TeamChip(text: t("Blocked", "Amezuiwa"), hu: Hue(fg: C.bad, bg: C.badBg), icon: "x") }
        }.padding(.top, 4)
    }
}

let StToday = Color(hex: 0x22B566)
private let StWeek = Color(hex: 0xF0A630), StNone = Color(hex: 0xAEB8C1), StBlk = Color(hex: 0xD93A2E)
func stColor(_ st: String) -> Color { st == "today" ? StToday : st == "week" ? StWeek : st == "blk" ? StBlk : StNone }
/// today (used the app today), week (in the last 7 days), old, inv (never signed in), blk (blocked).
func teamState(_ p: JSON) -> String {
    if p.obj("blocked") != nil || p.bool("blocked") { return "blk" }
    let s = p.str("lastSeen"); if s.isEmpty || s == "null" { return "inv" }
    let fm = ISO8601DateFormatter(); fm.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    guard let at = fm.date(from: s) ?? ISO8601DateFormatter().date(from: s) else { return "inv" }
    if Calendar.current.isDateInToday(at) { return "today" }
    return Date().timeIntervalSince(at) < 7 * 86400 ? "week" : "old"
}
private func teamIs(_ p: JSON, _ f: String) -> Bool {
    let st = teamState(p)
    switch f { case "all": return true; case "adm": return p.bool("admin"); case "week": return st == "today" || st == "week"; default: return st == f }
}

/// Office › Team, from the Figma design (the same as the web app and Android): a navy head with numbers (tap one to
/// filter) and Add a person, search by name, phone or country, filter chips, and a card per person with a status ring,
/// Call and WhatsApp on it, and a red card for someone blocked. Tap a person for their contact sheet.
private struct TeamSection: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject private var contact = Contact.shared
    @StateObject private var team = Team()
    @State private var adding = false
    @State private var q = ""
    @State private var f = "all"
    @State private var tm: String?
    @Environment(\.openURL) private var openURL
    var body: some View {
        let all = team.people.sorted { $0.str("name").lowercased() < $1.str("name").lowercased() }
        let qq = q.trimmingCharacters(in: .whitespaces).lowercased(), qd = qq.filter { $0.isNumber }
        let ff = f != "all" && !all.contains { teamIs($0, f) } ? "all" : f
        let list = all.filter { p in
            teamIs(p, ff) && (qq.isEmpty || ["name", "phone", "role", "email"].map { p.str($0) }.joined(separator: " ").appending(" " + (countryName(p.str("home")) ?? "")).lowercased().contains(qq)
                || (qd.count >= 3 && p.str("phone").filter { $0.isNumber }.contains(qd)))
        }
        let admins = list.filter { $0.bool("admin") }, field = list.filter { !$0.bool("admin") }
        let filtered = !qq.isEmpty || ff != "all"
        VStack(alignment: .leading, spacing: 0) {
            hero(all, ff)
            Field(t("Find a person", "Tafuta mtu"), $q, placeholder: t("Name, phone or country", "Jina, simu au nchi")).padding(.top, 8)
            TagFlow(h: 8, v: 8) {
                ForEach([("all", t("All", "Wote")), ("today", t("Active today", "Hai leo")), ("adm", t("Admins", "Wasimamizi")), ("inv", t("Invited", "Walioalikwa")), ("blk", t("Blocked", "Waliozuiwa"))], id: \.0) { k, label in
                    let n = k == "all" ? all.count : all.filter { teamIs($0, k) }.count
                    if k == "all" || n > 0 { pill(k, label, n, ff == k) }
                }
            }.padding(.top, 10)
            if !filtered || !admins.isEmpty { head("crown", t("Admins", "Wasimamizi"), admins.count, C.named("amber")) }
            ForEach(Array(admins.enumerated()), id: \.offset) { i, p in card(p, i) }
            if !filtered || !field.isEmpty { head("people", t("Field team", "Timu ya uwanjani"), field.count, C.named("slate")) }
            ForEach(Array(field.enumerated()), id: \.offset) { i, p in card(p, admins.count + i) }
            if filtered && list.isEmpty { Text(t("Nobody matches.", "Hakuna anayelingana.")).font(.bMedium).foregroundColor(C.muted).frame(maxWidth: .infinity).padding(24) }
            else if !team.people.isEmpty && field.isEmpty && !filtered { Text(t("Nobody here yet. Add a person above.", "Hakuna mtu bado. Ongeza mtu hapo juu.")).font(.bSmall).foregroundColor(C.muted).padding(4) }
            CardV(pad: 16) {
                HStack(spacing: 12) {
                    IconTile(icon: "mail", tint: C.named("teal").fg, bg: C.named("teal").bg, size: 44)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(t("E-mail alerts", "Taarifa kwa barua pepe")).font(.mont(17, .heavy)).foregroundColor(C.ink)
                        Text(t("Repairs and reviews go to admins with an e-mail", "Matengenezo na mapitio yanaenda kwa wasimamizi wenye barua pepe")).font(.bSmall).foregroundColor(C.muted)
                    }
                }
                let mails = all.filter { $0.bool("admin") && !$0.str("email").isEmpty && $0.str("email") != "null" }.map { $0.str("email") }
                if !mails.isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        ForEach(mails, id: \.self) { e in
                            HStack(spacing: 6) { Icon("mail", size: 14, color: C.ink); Text(e).font(.mont(13, .bold)).foregroundColor(C.ink).lineLimit(1) }
                                .padding(.horizontal, 10).padding(.vertical, 4).background(C.named("slate").bg).clipShape(Capsule())
                        }
                    }.padding(.top, 10)
                }
                Btn(t("Send a test e-mail", "Tuma barua pepe ya majaribio"), icon: "upload", light: true, small: true) {
                    Task { tm = t("Sending…", "Inatuma…"); tm = mailResult(await m.api.admin(["action": "test-mail"])) }
                }.padding(.top, 12)
                Msg(tm)
            }.padding(.top, 18)
        }
        .task(id: contact.changed) { await team.load(m) }
        .sheet(isPresented: $adding) { AddSheet { Task { await team.load(m) } }.environmentObject(m) }
    }
    private func pick(_ k: String) { Haptic.tap(); withAnimation(.easeOut(duration: 0.2)) { f = f == k && k != "all" ? "all" : k } }
    private func hero(_ all: [JSON], _ ff: String) -> some View {
        let nAdm = all.filter { $0.bool("admin") }.count
        let n = { (k: String) in all.filter { teamIs($0, k) }.count }
        let faces = Array((all.filter { teamState($0) == "today" } + all.filter { teamState($0) != "today" }).prefix(3))
        let navy = Color(hex: 0x063559)
        return VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(t("OFFICE", "OFISI")).font(.mont(12, .heavy)).tracking(1.8).foregroundColor(Color(hex: 0x9FD3F7))
                    Text(t("Your team", "Timu yako")).font(.mont(28, .heavy)).foregroundColor(.white)
                    Text((all.count == 1 ? t("1 person", "Mtu 1") : t("\(all.count) people", "Watu \(all.count)")) + " · " + (nAdm == 1 ? t("1 admin", "Msimamizi 1") : t("\(nAdm) admins", "Wasimamizi \(nAdm)")))
                        .font(.bMedium).foregroundColor(.white.opacity(0.82))
                }
                Spacer(minLength: 8)
                HStack(spacing: -10) {
                    ForEach(Array(faces.enumerated()), id: \.offset) { _, p in
                        let hu = C.named(nameHue(p.str("name")))
                        Text(initialsOf(p.str("name"))).font(.mont(13, .heavy)).foregroundColor(hu.fg).frame(width: 35, height: 35).background(Circle().fill(hu.bg)).padding(2.5).background(Circle().fill(navy))
                    }
                    if all.count > 3 { Text("+\(all.count - 3)").font(.mont(12, .heavy)).foregroundColor(Color(hex: 0x04263F)).frame(width: 35, height: 35).background(Circle().fill(.white)).padding(2.5).background(Circle().fill(navy)) }
                }.padding(.top, 6).accessibilityHidden(true)
            }
            HStack(spacing: 8) {
                stat("today", n("today"), StToday, t("today", "leo"), ff, live: true)
                stat("week", n("week"), Color(hex: 0x7CC4F5), t("this week", "wiki hii"), ff)
                stat("inv", n("inv"), StWeek, t("invited", "walioalikwa"), ff)
                stat("blk", n("blk"), Color(hex: 0xFF6B5E), t("blocked", "waliozuiwa"), ff)
            }.padding(.top, 16)
            Button { Haptic.tap(); adding = true } label: {
                HStack(spacing: 8) { Icon("plus", size: 22, color: Color(hex: 0x0A78C2)); Text(t("Add a person", "Ongeza mtu")).font(.mont(17, .heavy)).foregroundColor(Color(hex: 0x0A78C2)) }
                    .frame(maxWidth: .infinity, minHeight: 54).background(Capsule().fill(.white)).shadow(color: .black.opacity(0.18), radius: 9, y: 6)
            }.buttonStyle(Press()).padding(.top, 16).accessibilityIdentifier("team_add")
        }
        .padding(20)
        .background(C.sun ? AnyView(Color(hex: 0x002A57)) : AnyView(ZStack {
            LinearGradient(colors: [Color(hex: 0x0B4F80), navy, Color(hex: 0x04263F)], startPoint: .topLeading, endPoint: .bottomTrailing)
            RadialGradient(colors: [Color(hex: 0x2F9BE6).opacity(0.55), .clear], center: .topTrailing, startRadius: 0, endRadius: 260)
        }))
        .modifier(Sheen(delay: 0.7))
        .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 28, style: .continuous).stroke(C.sun ? Color.black : C.dark ? Color(hex: 0x1E4A6E) : .clear, lineWidth: C.sun ? 2 : 1))
        .shadow(color: C.dark || C.sun ? .clear : Color(hex: 0x04263F).opacity(0.22), radius: 17, y: 14)
        .rise(0)
    }
    private func stat(_ k: String, _ n: Int, _ dot: Color, _ label: String, _ ff: String, live: Bool = false) -> some View {
        let on = ff == k
        return Button { pick(k) } label: {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) { BreathDot(color: dot, live: live && n > 0, size: 8); Text("\(n)").font(.mont(22, .heavy)) }
                Text(label).font(.mont(11, .semibold)).opacity(0.85).lineLimit(2).minimumScaleFactor(0.8)
            }.foregroundColor(on ? Color(hex: 0x04263F) : .white).frame(maxWidth: .infinity, minHeight: 62, alignment: .leading).padding(.horizontal, 10).padding(.vertical, 9)
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(on ? Color.white : Color.white.opacity(0.1)))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Color.white.opacity(on ? 1 : 0.18), lineWidth: 1))
        }.buttonStyle(Press()).accessibilityAddTraits(on ? .isSelected : [])
    }
    private func pill(_ k: String, _ label: String, _ n: Int, _ on: Bool) -> some View {
        Button { pick(k) } label: {
            HStack(spacing: 8) {
                Text(label).font(.mont(15, .bold)).foregroundColor(on ? C.card : C.ink)
                Text("\(n)").font(.mont(12, .heavy)).foregroundColor(on ? C.ink : C.named("slate").fg).padding(.horizontal, 8).padding(.vertical, 2).background(Capsule().fill(on ? C.card : C.named("slate").bg))
            }.padding(.leading, 16).padding(.trailing, 8).frame(minHeight: 44).background(Capsule().fill(on ? C.ink : C.card)).overlay(Capsule().stroke(on ? C.ink : C.line, lineWidth: C.sun ? 2 : 1))
        }.buttonStyle(Press()).accessibilityAddTraits(on ? .isSelected : [])
    }
    private func head(_ icon: String, _ title: String, _ n: Int, _ hu: Hue) -> some View {
        HStack(spacing: 8) {
            if icon == "crown" { Image(systemName: "crown.fill").font(.system(size: 15)).foregroundColor(C.dark ? CrownGoldDark : CrownGold) } else { Icon(icon, size: 18, color: hu.fg) }
            Text(title.uppercased()).font(.mont(12, .heavy)).tracking(1.2).foregroundColor(C.muted)
            Text("\(n)").font(.mont(11, .heavy)).foregroundColor(hu.fg).padding(.horizontal, 8).padding(.vertical, 2).background(hu.bg).clipShape(Capsule())
        }.padding(.top, 20).padding(.bottom, 2).padding(.leading, 4)
    }
    private func card(_ p: JSON, _ i: Int) -> some View {
        let name = p.str("name"), seen = p.str("lastSeen"), st = teamState(p), blk = st == "blk"
        let phone = p.str("phone") == "null" ? "" : p.str("phone"), role = p.str("role") == "null" ? "" : p.str("role")
        let src = p.str("src").isEmpty ? p.str("source") : p.str("src")
        let when = st == "inv" ? (src == "app" ? t("Invited · hasn’t signed in yet", "Amealikwa · bado hajaingia") : t("Not signed in yet", "Bado hajaingia")) : t("Last active ", "Alitumia mwisho ") + Dates.dateTime(seen)
        let shape = RoundedRectangle(cornerRadius: 24, style: .continuous)
        return VStack(spacing: 0) {
            Button { Haptic.tap(); Contact.show(name) } label: {
                HStack(spacing: 12) {
                    TeamAvatar(name: name, admin: p.bool("admin"), size: 56, st: st)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(name).font(.mont(17, .heavy)).foregroundColor(C.ink).lineLimit(1)
                        Text((role.isEmpty ? "" : role + " · ") + when).font(.bSmall).foregroundColor(blk ? C.bad : C.muted).lineLimit(1)
                        TeamChips(p: p)
                    }
                    Spacer(minLength: 0)
                    Icon("chev", size: 18, color: C.muted)
                }.padding(.leading, 16).padding(.trailing, 12).padding(.vertical, 14).contentShape(Rectangle())
            }.buttonStyle(Press()).accessibilityIdentifier("tm_\(name)")
            if blk {
                Divider().overlay(C.bad.opacity(0.3))
                let by = p.obj("blocked")?.str("by") ?? ""
                HStack(spacing: 8) {
                    Icon("x", size: 18, color: C.bad)
                    Text((by.isEmpty || by == "null" ? t("Blocked", "Amezuiwa") : t("Blocked by \(by)", "Amezuiwa na \(by)")) + " · " + t("can’t sign in", "hawezi kuingia")).font(.mont(13, .bold)).foregroundColor(C.bad)
                    Spacer(minLength: 0)
                }.padding(.horizontal, 16).frame(minHeight: 44)
            } else if !phone.isEmpty {
                Divider().overlay(C.line)
                HStack(spacing: 8) {
                    quick(t("Call", "Piga"), C.named("green")) { Icon("phone", size: 18, color: C.named("green").fg) } go: {
                        if let u = URL(string: "tel:" + phone.filter { $0.isNumber || $0 == "+" }) { openURL(u) }
                    }
                    quick("WhatsApp", Hue(fg: C.dark ? Color(hex: 0x5FD08F) : Color(hex: 0x1C8A50), bg: Color(hex: 0x25D366).opacity(0.14))) { BrandLogo(k: "whatsapp", size: 18) } go: {
                        if let u = URL(string: "https://wa.me/" + waNumber(phone, p.str("home"))) { openURL(u) }
                    }
                    Text(phone).font(.bSmall).foregroundColor(C.muted).lineLimit(1).frame(maxWidth: .infinity, alignment: .trailing)
                }.padding(.horizontal, 14).padding(.top, 10).padding(.bottom, 12)
            }
        }
        .background(shape.fill(blk ? C.badBg.opacity(0.55) : C.card).background(shape.fill(C.card)))
        .overlay(shape.stroke(blk ? C.bad.opacity(0.4) : C.line, lineWidth: C.sun ? 2 : 1))
        .clipShape(shape)
        .shadow(color: C.dark || C.sun ? .clear : Color(hex: 0x04263F).opacity(0.05), radius: 5, y: 2)
        .padding(.top, 12).rise(i + 1, step: 0.04)
    }
    private func quick<I: View>(_ label: String, _ hu: Hue, @ViewBuilder icon: () -> I, go: @escaping () -> Void) -> some View {
        Button { Haptic.tap(); go() } label: {
            HStack(spacing: 7) { icon(); Text(label).font(.mont(14, .heavy)).foregroundColor(hu.fg).lineLimit(1) }
                .padding(.horizontal, 16).frame(minHeight: 44).background(Capsule().fill(hu.bg))
        }.buttonStyle(Press())
    }
}

/// Add a person: name, home, e-mail, phone and admin; then their new PIN, shown for a minute.
private struct AddSheet: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.dismiss) private var dismiss
    let added: () -> Void
    @State private var name = ""
    @State private var home = ""
    @State private var email = ""
    @State private var phone = ""
    @State private var admin = false
    @State private var done: (String, String)?
    @State private var msg: String?
    @State private var busy = false
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if let d = done {
                    head(d.0, d.0, t("Added to the team ✓", "Ameongezwa kwenye timu ✓"))
                    PinShown(who: d.0, pin: d.1, phone: phone, home: home)
                    Btn(t("Done", "Imekamilika"), light: true, wide: true) { dismiss() }.padding(.top, 12)
                } else {
                    head(name, t("Add a person", "Ongeza mtu"), t("They get their own 4-digit PIN, shown once. Send it to them privately.", "Wanapata PIN yao ya tarakimu 4, inaonyeshwa mara moja. Wapelekee kwa siri."))
                    Field(t("Name", "Jina"), $name, placeholder: t("e.g. Jessica Anderson", "mf. Jessica Anderson"))
                    Picker1(label: t("Home country", "Nchi ya nyumbani"), value: home, options: [("", t("All countries", "Nchi zote"))] + COUNTRIES) { home = $0 }
                    Field(t("E-mail (optional)", "Barua pepe (si lazima)"), $email, placeholder: t("for alerts", "kwa taarifa")).keyboardType(.emailAddress).textInputAutocapitalization(.never)
                    Field(t("Phone (optional)", "Simu (si lazima)"), $phone, placeholder: "+254 712 345 678").keyboardType(.phonePad)
                    Button { Haptic.tap(); admin.toggle() } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "crown.fill").font(.system(size: 18)).foregroundColor(C.named("amber").fg).frame(width: 36, height: 36).background(C.named("amber").bg).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                            VStack(alignment: .leading, spacing: 1) {
                                Text(t("Admin", "Msimamizi")).font(.tSmall).foregroundColor(C.ink)
                                Text(t("Sees the Office and can manage the team", "Anaona Ofisi na anaweza kusimamia timu")).font(.bSmall).foregroundColor(C.muted)
                            }
                            Spacer()
                            ZStack(alignment: admin ? .trailing : .leading) {
                                Capsule().fill(admin ? GreenC : Color(hex: 0xC9D1D8)).frame(width: 50, height: 30)
                                Circle().fill(.white).frame(width: 24, height: 24).padding(3)
                            }.animation(.easeOut(duration: 0.15), value: admin)
                        }.padding(12).overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.line, lineWidth: C.sun ? 2 : 1)).contentShape(Rectangle())
                    }.buttonStyle(.plain).padding(.top, 14)
                    Btn(t("Add and create PIN", "Ongeza na utengeneze PIN"), icon: "plus", enabled: !name.trimmingCharacters(in: .whitespaces).isEmpty && !busy, wide: true) {
                        busy = true
                        Task {
                            let n = name.trimmingCharacters(in: .whitespaces)
                            let x = await m.api.admin(["action": "team-add", "name": n, "home": home, "email": email.trimmingCharacters(in: .whitespaces), "phone": phone.trimmingCharacters(in: .whitespaces), "admin": admin])
                            busy = false
                            if x.ok { Haptic.ok(); done = (n, x.json.str("pin")); added() }
                            else { msg = x.reason == "exists" ? t("Someone with that name is already on the team.", "Mtu mwenye jina hilo tayari yuko kwenye timu.") : reasonText(x.reason) }
                        }
                    }.padding(.top, 14)
                    Msg(msg, "bad")
                }
            }.padding(.horizontal, 20).padding(.top, 24).padding(.bottom, 28)
        }.background(C.card.ignoresSafeArea())
    }
    private func head(_ n: String, _ title: String, _ sub: String) -> some View {
        VStack(spacing: 0) {
            TeamAvatar(name: n, admin: admin, size: 64).rise(0)
            Text(title).font(.tLarge).foregroundColor(C.ink).multilineTextAlignment(.center).padding(.top, 6)
            Text(sub).font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.center).padding(.top, 4)
        }.frame(maxWidth: .infinity)
    }
}

// MARK: - Clean-up

/// Rough boxes around each country, to spot points that landed in the wrong place.
private let BOUNDS: [String: [Double]] = ["ke": [-4.9, 5.1, 33.8, 42.0], "tz": [-11.8, -0.9, 29.2, 40.6], "ug": [-1.6, 4.3, 29.5, 35.1], "mw": [-17.2, -9.3, 32.6, 36.0]]

private struct Cleanup: View {
    @EnvironmentObject var m: AppModel
    let s: Snapshot
    @State private var more: Set<Int> = []
    var body: some View {
        let outside = s.projects.filter { p in
            guard let g = p.gps, let b = BOUNDS[p.ck] else { return false }
            return !(b[0]...b[1]).contains(g.lat) || !(b[2]...b[3]).contains(g.lng)
        }
        let dup = Dictionary(grouping: s.projects.filter { $0.gps != nil }) { String(format: "%.5f,%.5f", $0.gps!.lat, $0.gps!.lng) }.values.filter { $0.count > 1 }.flatMap { $0 }
        let lists: [(String, String, [Project])] = [
            (t("No location", "Hakuna mahali"), t("Open each one and pin it (Find a place by name helps).", "Fungua kila mmoja na uweke mahali (Tafuta mahali kwa jina inasaidia)."), s.projects.filter { $0.gps == nil }),
            (t("Location outside its country", "Mahali nje ya nchi yake"), t("Usually latitude and longitude swapped, or a typing slip.", "Mara nyingi latitudo na longitudo zimebadilishana, au kosa la kuandika."), outside),
            (t("Same point as another project", "Mahali sawa na mradi mwingine"), t("Probably a copied location. Check which one is right.", "Huenda mahali hapa pamenakiliwa. Hakikisha kipi ni sahihi."), dup),
            (t("No photo", "Hakuna picha"), t("Add a photo to the “Photos of the project” column in monday.com.", "Ongeza picha kwenye safu ya “Photos of the project” katika monday.com."), s.projects.filter { $0.photo == nil }),
            (t("No visit for over a year", "Hakuna ziara kwa zaidi ya mwaka"), t("Only visits made with the app are known.", "Ziara zilizofanywa kwa programu tu ndizo zinajulikana."), s.projects.filter { $0.gps != nil && ageDays(s, $0) > 365 }),
        ]
        let wrongPlace = Set((outside + dup).map(\.id)).count
        let todo = Set(lists.flatMap { $0.2 }.map(\.id)).count
        let looks = [("pinq", "blue"), ("globe", "coral"), ("copy", "amber"), ("camera", "purple"), ("clock", "teal")]
        VStack(alignment: .leading, spacing: 0) {
            DqmHead(icon: "spark", hue: "teal", title: t("Clean-up", "Safisha data"),
                    line: todo == 0 ? t("Every project looks tidy.", "Kila mradi uko sawa.") : t("Fix these to keep the map and monday.com tidy. Tap a project to open it.", "Rekebisha haya ili ramani na monday.com viwe safi. Gusa mradi kuufungua."),
                    nums: [DqmNum(n: lists[0].2.count, label: t("no location", "bila mahali"), tone: lists[0].2.isEmpty ? "ok" : "has"),
                           DqmNum(n: wrongPlace, label: t("location to check", "mahali pa kukagua"), tone: wrongPlace == 0 ? "ok" : "bad"),
                           DqmNum(n: lists[3].2.count, label: t("no photo", "bila picha"), tone: lists[3].2.isEmpty ? "ok" : "")])
            ForEach(lists.indices, id: \.self) { i in
                let (title, hint, rows) = lists[i], hu = C.named(looks[i].1), open = more.contains(i)
                CardV(pad: 16) {
                    HStack(spacing: 12) {
                        IconTile(icon: looks[i].0, tint: hu.fg, bg: hu.bg, size: 42)
                        VStack(alignment: .leading, spacing: 1) { Text(title).font(.tSmall).foregroundColor(C.ink); Text(hint).font(.bSmall).foregroundColor(C.muted) }
                        Spacer(minLength: 8)
                        if rows.isEmpty { Tag("✓", bg: C.okBg, fg: C.ok) } else { Tag("\(rows.count)", bg: hu.bg, fg: hu.fg) }
                    }
                    if rows.isEmpty { Text(t("All good here.", "Hakuna tatizo hapa.")).font(.lLarge).foregroundColor(C.ok).padding(.top, 10) }
                    else if i == 2 {
                        let groups = Dictionary(grouping: rows) { String(format: "%.4f, %.4f", $0.gps!.lat, $0.gps!.lng) }.sorted { $0.key < $1.key }
                        ForEach(Array((open ? groups : Array(groups.prefix(2))).enumerated()), id: \.offset) { _, g in
                            HStack(spacing: 6) { Icon("pin", size: 14, color: hu.fg); Text(t("\(g.value.count) projects at \(g.key)", "Miradi \(g.value.count) mahali \(g.key)")).font(.lMedium).foregroundColor(hu.fg) }.padding(.top, 10).padding(.bottom, 2)
                            box(g.value.map { ($0, nil) })
                        }
                        if groups.count > 2 { moreBtn(open, groups.count) { toggle(i) } }
                    } else {
                        let tag = { (p: Project) -> String? in i == 1 ? String(format: "%.4f, %.4f", p.gps!.lat, p.gps!.lng) : i == 4 ? s.visits[p.id].map { checkedAgo($0.at) } : nil }
                        box((open ? Array(rows.prefix(120)) : Array(rows.prefix(4))).map { ($0, tag($0)) }).padding(.top, 8)
                        if rows.count > 4 { moreBtn(open, rows.count) { toggle(i) } }
                    }
                }.padding(.top, 12).rise(i)
            }
        }
    }
    private func toggle(_ i: Int) { withAnimation { if more.contains(i) { more.remove(i) } else { more.insert(i) } } }
    private func checkedAgo(_ iso: String) -> String {
        guard let d = When.parse(iso) else { return "" }
        let days = Int(Date().timeIntervalSince(d) / 86400)
        return days >= 365 ? t("\(days / 365) yr ago", "mwaka \(days / 365) uliopita") : t("\(days) days ago", "siku \(days) zilizopita")
    }
    private func box(_ rows: [(Project, String?)]) -> some View {
        VStack(spacing: 0) {
            ForEach(Array(rows.enumerated()), id: \.offset) { j, x in
                if j > 0 { Rectangle().fill(C.line).frame(height: 1) }
                let (ic, hk) = typeLook(x.0), hu = C.named(hk)
                Button { m.go(Place(tab: "office", project: x.0.id)) } label: {
                    HStack(spacing: 12) {
                        IconTile(icon: ic, tint: hu.fg, bg: hu.bg, size: 36)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(x.0.title).font(.mont(14.5, .bold)).foregroundColor(C.ink).lineLimit(2).multilineTextAlignment(.leading)
                            Text([x.0.area, x.0.country].filter { !$0.isEmpty }.joined(separator: " · ")).font(.bSmall).foregroundColor(C.muted).lineLimit(1)
                            if let tg = x.1 { Text(tg).font(.lSmall).foregroundColor(C.muted).padding(.top, 2) }
                        }
                        Spacer(minLength: 8)
                        Icon("chev", size: 18, color: C.muted)
                    }.padding(.horizontal, 12).padding(.vertical, 10).contentShape(Rectangle())
                }.buttonStyle(.plain)
            }
        }.overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.line, lineWidth: 1))
    }
    private func moreBtn(_ open: Bool, _ n: Int, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) { Text(open ? t("Show fewer", "Onyesha machache") : t("Show all \(n)", "Onyesha zote \(n)")).font(.lLarge).foregroundColor(C.blue2); Icon("down", size: 16, color: C.blue2).rotationEffect(.degrees(open ? 180 : 0)) }
                .frame(maxWidth: .infinity).padding(.vertical, 11).background(C.tint).clipShape(Capsule())
        }.buttonStyle(.plain).padding(.top, 10)
    }
}

// MARK: - Export

private struct Export: View {
    @EnvironmentObject var m: AppModel
    let s: Snapshot
    @State private var msg: String?
    var body: some View {
        let stamp = When.localDay()
        VStack(alignment: .leading, spacing: 10) {
            Text(t("CSV files open in Excel and Google Sheets.", "Faili za CSV zinafunguka kwenye Excel na Google Sheets.")).font(.bMedium).foregroundColor(C.muted)
            Btn(t("Projects with locations and status", "Miradi yenye mahali na hali yake"), icon: "upload", light: true, wide: true) {
                shareCsv("rafiki-projects-\(stamp).csv", csvOf([["ID", "Project", "Country", "Area", "Type", "Status", "Latitude", "Longitude", "Last visit", "Condition", "Open repair"]] +
                    s.projects.map { p in let v = s.visits[p.id]; return [p.id, p.title, p.country, p.area, p.type, p.status, p.gps?.lat, p.gps?.lng, v.map { String($0.at.prefix(10)) }, v?.state, s.repairs[p.id]?.state] }))
            }
            Btn(t("Site visits with readings", "Ziara pamoja na vipimo"), icon: "upload", light: true, wide: true) {
                Task {
                    msg = t("Gathering visit records…", "Inakusanya rekodi za ziara…")
                    let x = await m.api.adminGet("export")
                    guard let a = x.json["visits"] as? [Any] else { msg = reasonText(x.reason); return }
                    msg = nil
                    let rows: [[Any?]] = a.compactMap { $0 as? JSON }.map { v in
                        let r = v.obj("readings") ?? [:], q = v.obj("quality") ?? [:]
                        return [String(v.str("at").prefix(10)), v.str("id"), s.projects.first { $0.id == v.str("id") }?.title, v.str("by"), v.str("state"), v.str("urgency"), r.num("strokes"), r.num("flow"), r.num("users"), r.num("queue"), q.str("ecoli")]
                    }
                    shareCsv("rafiki-visits-\(stamp).csv", csvOf([["Date", "ID", "Project", "By", "Condition", "Urgency", "Strokes to fill 20 L", "Flow (L/min)", "People using it", "Queue (min)", "E. coli"]] + rows))
                }
            }
            Btn(t("Open repairs and jobs", "Matengenezo na kazi zilizo wazi"), icon: "upload", light: true, wide: true) {
                shareCsv("rafiki-repairs-\(stamp).csv", csvOf([["ID", "Project", "Condition", "Urgency", "Reported", "By", "Problems", "Parts", "Est. KES", "Step", "Assigned to", "Due", "Budget KES"]] +
                    s.repairs.map { id, r in [id, s.projects.first { $0.id == id }?.title ?? r.title, r.state, r.urgency, String(r.at.prefix(10)), r.by, r.problems.joined(separator: "; "), r.parts, r.cost, r.step, r.assignee, r.due, r.budget] }))
            }
            Btn(t("Everything the team did (last 13 months)", "Kila kitu timu ilifanya (miezi 13 iliyopita)"), icon: "upload", light: true, wide: true) {
                Task {
                    if !(await m.loadActivity()) { msg = reasonText("offline"); return }
                    shareCsv("rafiki-activity-\(stamp).csv", csvOf([["Date", "Time", "Who", "What", "Project", "Result"]] + (m.activity ?? []).map { e in
                        let at = e.str("at"); return [String(at.prefix(10)), String(at.dropFirst(11).prefix(5)), e.str("by"), e.str("a"), e.str("t"), e.str("s")]
                    }))
                }
            }
            Btn(t("Repair costs (every entry, last 2 years)", "Gharama za matengenezo (kila kiingilio, miaka 2)"), icon: "coins", light: true, wide: true) { withCosts { shareCsv("rafiki-repair-costs-\(stamp).csv", costsCsv($0, s.projects)) } }
            Btn(t("Costs and people served: summary", "Gharama na watu wanaohudumiwa: muhtasari"), icon: "coins", light: true, wide: true) { withCosts { shareCsv("rafiki-costs-and-people-\(stamp).csv", summaryCsv($0, s.people)) } }
            Msg(msg)
        }
    }
    private func withCosts(_ go: @escaping (CostView) -> Void) {
        Task {
            msg = t("Gathering repair costs…", "Inakusanya gharama za matengenezo…")
            let x = await m.api.adminGet("costs")
            if !x.ok { msg = reasonText(x.reason); return }
            msg = nil; go(CostView.from(x.json))
        }
    }
}

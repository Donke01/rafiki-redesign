import SwiftUI
import UIKit

// Office › Team (Android 2.16.1 – 2.26): a navy head with the numbers that filter, a search box, then the people
// grouped by role (Admins, Field team), each a compact card with Call and WhatsApp. A tap opens the person's sheet
// (built with Team & Me; see OfficePersonHook). "Add a person" makes their PIN, shown for a minute.

/// today (used the app today), week (in the last 7 days), old, inv (never signed in), blk (blocked).
private func teamState(_ p: JSON) -> String {
    if p.obj("blocked") != nil || p.bool("blocked") { return "blk" }
    let s = p.str("lastSeen"); guard !s.isEmpty, s != "null", let at = When.parse(s) else { return "inv" }
    if Calendar.current.isDateInToday(at) { return "today" }
    return Date().timeIntervalSince(at) < 7 * 86400 ? "week" : "old"
}
private func teamIs(_ p: JSON, _ f: String) -> Bool {
    let st = teamState(p)
    switch f { case "all": return true; case "adm": return p.bool("admin"); case "week": return st == "today" || st == "week"; default: return st == f }
}
private let StToday = Color(hex: 0x22B566), StWeek = Color(hex: 0xF0A630), StNone = Color(hex: 0xAEB8C1), StBlk = Color(hex: 0xD93A2E)
private func stColor(_ st: String) -> Color { st == "today" ? StToday : st == "week" ? StWeek : st == "blk" ? StBlk : StNone }
private let CrownGold = Color(hex: 0xE0A100)

/// A phone number for wa.me: digits only, with the country code (a leading 0 becomes the home country's code).
func officeWaNumber(_ phone: String, _ home: String) -> String {
    var d = phone.filter(\.isNumber)
    if phone.trimmingCharacters(in: .whitespaces).hasPrefix("+") { return d }
    let cc = ["ke": "254", "tz": "255", "ug": "256", "mw": "265"][home] ?? "254"
    if d.hasPrefix("0") { d = cc + d.dropFirst() } else if d.count <= 9 { d = cc + d }
    return d
}

/// The message that carries a new PIN to the person (sent by the admin, from their own phone).
func officePinMessage(_ who: String, _ pin: String) -> String {
    let first = who.split(separator: " ").first.map(String.init) ?? who
    return t("Hi \(first), your RAFIKI Pin PIN is \(pin). Open the app (or pin.rafikioutreach.org) and sign in with it. Please keep it to yourself.",
             "Habari \(first), PIN yako ya RAFIKI Pin ni \(pin). Fungua programu (au pin.rafikioutreach.org) uingie nayo. Tafadhali usimpe mtu mwingine.")
}

struct OfficeTeamList: View {
    @EnvironmentObject var m: AppModel
    @State private var people: [JSON] = []
    @State private var q = ""
    @State private var f = "all"
    @State private var adding = false
    @State private var tm: String?

    func load() async { people = await m.api.adminGet("team").json.objs("team") }

    var body: some View {
        let all = people.sorted { $0.str("name").lowercased() < $1.str("name").lowercased() }
        let cnt: (String) -> Int = { k in all.filter { teamIs($0, k) }.count }
        let pick: (String) -> Void = { k in Haptic.tap(); f = (f == k && k != "all") ? "all" : k }
        let qq = q.trimmingCharacters(in: .whitespaces).lowercased(); let qd = qq.filter(\.isNumber)
        let list = all.filter { p in
            teamIs(p, f) && (qq.isEmpty || (["name", "phone", "role", "email"].map { p.str($0) }.joined(separator: " ") + " " + (countryName(p.str("home")) ?? "")).lowercased().contains(qq)
                              || (qd.count >= 3 && p.str("phone").filter(\.isNumber).contains(qd)))
        }
        let admins = list.filter { $0.bool("admin") }, field = list.filter { !$0.bool("admin") }
        let filtered = !qq.isEmpty || f != "all"
        ScrollViewReader { proxy in
            VStack(alignment: .leading, spacing: 0) {
                TeamHero(all: all, nAdm: cnt("adm"), nT: cnt("today"), nW: cnt("week"), nI: cnt("inv"), nB: cnt("blk"), f: f, pick: pick) { adding = true }
                Field(t("Find a person", "Tafuta mtu"), $q, placeholder: t("Name, phone or country", "Jina, simu au nchi"))
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach([("all", t("All", "Wote")), ("today", t("Active today", "Hai leo")), ("adm", t("Admins", "Wasimamizi")), ("inv", t("Invited", "Walioalikwa")), ("blk", t("Blocked", "Waliozuiwa"))], id: \.0) { k, label in
                            let n = k == "all" ? all.count : cnt(k)
                            if k == "all" || n > 0 { FilterPill(label: label, n: n, on: f == k) { pick(k) } }
                        }
                    }.padding(.vertical, 2)
                }.padding(.top, 10)
                if !filtered || !admins.isEmpty { TeamHead(crown: true, icon: "crown", title: t("Admins", "Wasimamizi"), n: admins.count, hu: ohue("amber")) }
                ForEach(Array(admins.enumerated()), id: \.offset) { i, p in PersonCard(p: p, i: i) { officeOpenPerson(p.str("name")) } }
                if !filtered || !field.isEmpty { TeamHead(crown: false, icon: "people", title: t("Field team", "Timu ya uwanjani"), n: field.count, hu: ohue("slate")) }
                ForEach(Array(field.enumerated()), id: \.offset) { i, p in PersonCard(p: p, i: admins.count + i) { officeOpenPerson(p.str("name")) } }
                if filtered && list.isEmpty { Text(t("Nobody matches.", "Hakuna anayelingana.")).font(.bMedium).foregroundColor(C.muted).frame(maxWidth: .infinity).padding(24) }
                else if !people.isEmpty && field.isEmpty && !filtered { Text(t("Nobody here yet. Add a person above.", "Hakuna mtu bado. Ongeza mtu hapo juu.")).font(.bSmall).foregroundColor(C.muted).padding(4) }
                alerts(all).id("alerts")
            }
            .onChange(of: people.count) { _ in
                if OfficeJump.to == "alerts" { OfficeJump.to = nil; DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { withAnimation { proxy.scrollTo("alerts", anchor: .top) } } }
            }
        }
        .task { await load() }
        .sheet(isPresented: $adding) { OfficeTeamAddSheet(close: { adding = false }) { Task { await load() } } }
    }

    func alerts(_ all: [JSON]) -> some View {
        let mails = all.filter { $0.bool("admin") && !$0.str("email").isEmpty && $0.str("email") != "null" }.map { $0.str("email") }
        let hu = ohue("teal")
        return CardV(pad: 16) {
            HStack(spacing: 12) {
                OTileIcon(icon: "mail", tint: hu.fg, bg: hu.bg, size: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Text(t("E-mail alerts", "Taarifa kwa barua pepe")).font(.mont(17, .heavy)).foregroundColor(C.ink)
                    Text(t("Repairs and reviews go to admins with an e-mail", "Matengenezo na mapitio yanaenda kwa wasimamizi wenye barua pepe")).font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true)
                }
            }
            if !mails.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(mails, id: \.self) { e in
                        HStack(spacing: 6) { OIcon("mail", size: 14, color: C.ink); Text(e).font(.mont(12, .bold)).foregroundColor(C.ink).lineLimit(1).truncationMode(.middle) }
                            .padding(.horizontal, 10).padding(.vertical, 4).background(ohue("slate").bg).clipShape(Capsule())
                    }
                }.padding(.top, 10)
            }
            Btn(t("Send a test e-mail", "Tuma barua pepe ya majaribio"), icon: "upload", light: true, small: true) {
                Task { tm = t("Sending…", "Inatuma…"); let x = await m.api.admin(["action": "test-mail"]); tm = officeMailResult(x) }
            }.padding(.top, 12)
            Msg(tm)
        }.padding(.top, 18)
    }
}

/// What the test e-mail did: sent, or why not.
func officeMailResult(_ x: Reply) -> String {
    let detail = x.json.str("detail")
    if x.ok { return t("Sent ✓ Check the inbox.", "Imetumwa ✓ Angalia barua pepe.") }
    if x.reason == "no-recipients" { return t("No e-mail addresses yet.", "Bado hakuna anwani za barua pepe.") }
    if x.reason == "no-mail" { return t("Sending e-mail isn’t set up yet. Whoever looks after the website can switch it on.", "Kutuma barua pepe bado hakujawekwa. Anayesimamia tovuti anaweza kuiwasha.") }
    if !detail.isEmpty { return t("Not sent. Resend said: ", "Haijatumwa. Resend imesema: ") + detail }
    if x.status == 0 { return t("Not sent: no connection. Try again with signal.", "Haijatumwa: hakuna mtandao. Jaribu tena ukiwa na mtandao.") }
    return t("Not sent. Please try again in a moment.", "Haijatumwa. Tafadhali jaribu tena baadaye.")
}

/// The navy head: how many people and admins, a few faces, four numbers that filter, and Add a person.
private struct TeamHero: View {
    let all: [JSON]; let nAdm: Int; let nT: Int; let nW: Int; let nI: Int; let nB: Int; let f: String; let pick: (String) -> Void; let add: () -> Void
    var body: some View {
        let faces = Array((all.filter { teamState($0) == "today" } + all.filter { teamState($0) != "today" }).prefix(3))
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(t("OFFICE", "OFISI")).font(.mont(11, .heavy)).tracking(1.8).foregroundColor(Color(hex: 0x9FD3F7))
                    Text(t("Your team", "Timu yako")).font(.mont(24, .heavy)).foregroundColor(.white)
                    Text((all.count == 1 ? t("1 person", "Mtu 1") : t("\(all.count) people", "Watu \(all.count)")) + " · " + (nAdm == 1 ? t("1 admin", "Msimamizi 1") : t("\(nAdm) admins", "Wasimamizi \(nAdm)")))
                        .font(.bMedium).foregroundColor(.white.opacity(0.82))
                }.frame(maxWidth: .infinity, alignment: .leading)
                HStack(spacing: -10) {
                    ForEach(Array(faces.enumerated()), id: \.offset) { _, p in OAvatar(name: p.str("name"), size: 35).padding(2.5).background(Color(hex: 0x063559)).clipShape(Circle()) }
                    if all.count > 3 { Text("+\(all.count - 3)").font(.mont(12, .heavy)).foregroundColor(Color(hex: 0x04263F)).frame(width: 35, height: 35).background(Color.white).clipShape(Circle()).padding(2.5).background(Color(hex: 0x063559)).clipShape(Circle()) }
                }.padding(.top, 6)
            }
            HStack(spacing: 8) {
                stat("today", nT, StToday, t("today", "leo"))
                stat("week", nW, Color(hex: 0x7CC4F5), t("this week", "wiki hii"))
                stat("inv", nI, StWeek, t("invited", "walioalikwa"))
                stat("blk", nB, Color(hex: 0xFF6B5E), t("blocked", "waliozuiwa"))
            }.fixedSize(horizontal: false, vertical: true).padding(.top, 16)
            Button { Haptic.tap(); add() } label: {
                HStack(spacing: 8) { Icon("plus", size: 22, color: Color(hex: 0x0A78C2)); Text(t("Add a person", "Ongeza mtu")).font(.mont(17, .heavy)).foregroundColor(Color(hex: 0x0A78C2)) }
                    .frame(maxWidth: .infinity, minHeight: 54).background(Color.white).clipShape(Capsule())
            }.buttonStyle(Press()).padding(.top, 16).accessibilityIdentifier("team_add")
        }
        .padding(20)
        .background(Group { if oSun { Color(hex: 0x002A57) } else { LinearGradient(colors: [Color(hex: 0x0B4F80), Color(hex: 0x063559), Color(hex: 0x04263F)], startPoint: .topLeading, endPoint: .bottomTrailing) } })
        .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous)).rise(0)
    }
    func stat(_ k: String, _ n: Int, _ dot: Color, _ label: String) -> some View {
        let on = f == k
        return Button { pick(k) } label: {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) { OfficeBreathDot(color: dot, live: k == "today" && n > 0); Text("\(n)").font(.mont(22, .heavy)).foregroundColor(on ? Color(hex: 0x04263F) : .white).lineLimit(1).minimumScaleFactor(0.6) }
                FitWords(label, size: 11, weight: .bold, color: (on ? Color(hex: 0x04263F) : Color.white).opacity(0.85), lines: 2)
            }
            .padding(.horizontal, 10).padding(.vertical, 9).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading).frame(minHeight: 62)
            .background(on ? Color.white : Color.white.opacity(0.1)).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Color.white.opacity(on ? 1 : 0.18), lineWidth: 1))
        }.buttonStyle(Press())
    }
}

/// A dot that breathes while something is live.
struct OfficeBreathDot: View {
    let color: Color; var live = false; var size: CGFloat = 8
    @State private var on = false
    var body: some View {
        ZStack {
            if live { Circle().fill(color.opacity(on ? 0 : 0.45)).frame(width: on ? size * 2.4 : size, height: on ? size * 2.4 : size) }
            Circle().fill(color).frame(width: size, height: size)
        }
        .frame(width: size * 2.4, height: size * 2.4).frame(width: size + 2, height: size + 2)
        .onAppear { if live && Motion.on { withAnimation(.easeOut(duration: 1.6).repeatForever(autoreverses: false)) { on = true } } }
    }
}

private struct FilterPill: View {
    let label: String; let n: Int; let on: Bool; let action: () -> Void
    var body: some View {
        let sl = ohue("slate")
        Button(action: action) {
            HStack(spacing: 8) {
                Text(label).font(.mont(15, .bold)).foregroundColor(on ? C.card : C.ink).lineLimit(1).fixedSize()
                Text("\(n)").font(.mont(12, .heavy)).foregroundColor(on ? C.ink : sl.fg).padding(.horizontal, 8).padding(.vertical, 2).background(on ? C.card : sl.bg).clipShape(Capsule())
            }
            .padding(.leading, 16).padding(.trailing, 8).frame(minHeight: 44).background(on ? C.ink : C.card).clipShape(Capsule())
            .overlay(Capsule().stroke(on ? C.ink : C.line, lineWidth: oSun ? 2 : 1))
        }.buttonStyle(Press())
    }
}

private struct TeamHead: View {
    let crown: Bool; let icon: String; let title: String; let n: Int; let hu: Hue
    var body: some View {
        HStack(spacing: 8) {
            if crown { Image(systemName: "crown.fill").font(.system(size: 15)).foregroundColor(CrownGold) } else { OIcon(icon, size: 18, color: hu.fg) }
            Text(title.uppercased()).font(.mont(12, .heavy)).tracking(1.2).foregroundColor(C.muted)
            Text("\(n)").font(.mont(11, .heavy)).foregroundColor(hu.fg).padding(.horizontal, 8).padding(.vertical, 2).background(hu.bg).clipShape(Capsule())
        }.padding(.top, 20).padding(.bottom, 2).padding(.leading, 4).accessibilityAddTraits(.isHeader)
    }
}

/// A round picture with initials, a status ring and dot; admins get a little crown on top.
struct OfficeTeamAvatar: View {
    let name: String; let admin: Bool; var size: CGFloat = 56; var st = ""
    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Color.clear.frame(width: size * 1.18, height: size * 1.18)
            OAvatar(name: name, size: size).overlay(Circle().stroke(st == "today" ? StToday : .clear, lineWidth: 2.5))
            if !st.isEmpty {
                Circle().fill(stColor(st)).frame(width: size * 0.26 - 5, height: size * 0.26 - 5).padding(2.5).background(C.card).clipShape(Circle())
                    .offset(x: size - size * 0.26, y: -size * 0.02)
            }
        }
        .overlay(alignment: .topTrailing) {
            if admin { Image(systemName: "crown.fill").font(.system(size: size * 0.24)).foregroundColor(CrownGold).frame(width: size * 0.5, height: size * 0.5).background(C.card).clipShape(Circle()).overlay(Circle().stroke(C.line, lineWidth: 1)) }
        }
    }
}

private struct TeamChip: View {
    let text: String; let hu: Hue; var icon: String? = nil; var crown = false
    var body: some View {
        HStack(spacing: 4) {
            if crown { Image(systemName: "crown.fill").font(.system(size: 10)).foregroundColor(hu.fg) } else if let icon { OIcon(icon, size: 12, color: hu.fg) }
            Text(text).font(.mont(11, .heavy)).foregroundColor(hu.fg).lineLimit(1)
        }.padding(.horizontal, 8).padding(.vertical, 3).background(hu.bg).clipShape(Capsule()).overlay(Capsule().stroke(oSun ? hu.fg : .clear, lineWidth: 1.5))
    }
}

/// One person: picture with a status ring and dot, name, role and when they last used the app, tags, then Call and
/// WhatsApp (or, when blocked, who blocked them).
private struct PersonCard: View {
    let p: JSON; let i: Int; let action: () -> Void
    var body: some View {
        let name = p.str("name"), st = teamState(p), blk = st == "blk"
        let phone = p.str("phone") == "null" ? "" : p.str("phone")
        let role = p.str("role") == "null" ? "" : p.str("role")
        let src = p.str("src").isEmpty ? p.str("source") : p.str("src")
        let seen = st == "inv" ? (src == "app" ? t("Invited · hasn’t signed in yet", "Amealikwa · bado hajaingia") : t("Not signed in yet", "Bado hajaingia"))
            : (p.str("lastSeen").isEmpty ? t("Not signed in yet", "Bado hajaingia") : t("Last active ", "Alitumia mwisho ") + When.text(p.str("lastSeen")))
        VStack(spacing: 0) {
            Button { Haptic.tap(); action() } label: {
                HStack(spacing: 12) {
                    OfficeTeamAvatar(name: name, admin: p.bool("admin"), size: 56, st: st)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(name).font(.mont(17, .heavy)).foregroundColor(C.ink).lineLimit(1)
                        Text((role.isEmpty ? "" : role + " · ") + seen).font(.bSmall).foregroundColor(blk ? C.bad : C.muted).lineLimit(1)
                        chips(src).padding(.top, 2)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                    Icon("chev", size: 18, color: C.muted)
                }.padding(.leading, 16).padding(.trailing, 12).padding(.vertical, 14).contentShape(Rectangle())
            }.buttonStyle(.plain)
            if blk {
                ODivider()
                let by = p.obj("blocked")?.str("by") ?? ""
                HStack(spacing: 8) {
                    Icon("x", size: 18, color: C.bad)
                    Text((by.isEmpty || by == "null" ? t("Blocked", "Amezuiwa") : t("Blocked by \(by)", "Amezuiwa na \(by)")) + " · " + t("can’t sign in", "hawezi kuingia")).font(.mont(12, .bold)).foregroundColor(C.bad)
                }.frame(maxWidth: .infinity, minHeight: 44, alignment: .leading).padding(.horizontal, 16).padding(.vertical, 4)
            } else if !phone.isEmpty {
                ODivider()
                HStack(spacing: 8) {
                    QuickPill(label: t("Call", "Piga"), hu: ohue("green"), icon: "phone") { Share.open("tel:" + phone.replacingOccurrences(of: " ", with: "")) }
                    QuickPill(label: "WhatsApp", hu: Hue(fg: C.dark ? Color(hex: 0x5FD08F) : Color(hex: 0x1C8A50), bg: Color(hex: 0x25D366).opacity(0.14)), icon: "whatsapp") { Share.open("https://wa.me/" + officeWaNumber(phone, p.str("home"))) }
                    Text(phone).font(.bSmall).foregroundColor(C.muted).lineLimit(1).truncationMode(.head).frame(maxWidth: .infinity, alignment: .trailing)
                }.padding(.horizontal, 14).padding(.top, 10).padding(.bottom, 12)
            }
        }
        .background(blk ? C.badBg.opacity(0.55) : C.card)
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).stroke(blk ? C.bad.opacity(0.4) : C.line, lineWidth: oSun ? 2 : 1))
        .padding(.top, 12).rise(i, step: 0.04)
    }
    func chips(_ src: String) -> some View {
        let h = p.str("home")
        return HStack(spacing: 4) {
            if p.bool("admin") { TeamChip(text: t("Admin", "Msimamizi"), hu: ohue("amber"), crown: true) }
            if src == "cloudflare" { TeamChip(text: t("PIN set by the office", "PIN ya ofisi"), hu: ohue("purple"), icon: "key") }
            else if src == "monday" { TeamChip(text: "monday.com", hu: ohue("blue"), icon: "people") }
            else { TeamChip(text: t("PIN from app", "PIN ya programu"), hu: ohue("green"), icon: "key") }
            if !h.isEmpty && h != "null" { TeamChip(text: h.uppercased(), hu: ohue("slate"), icon: "pin") }
        }
    }
}

private struct QuickPill: View {
    let label: String; let hu: Hue; let icon: String; let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 7) { OIcon(icon, size: 18, color: hu.fg); Text(label).font(.mont(14, .heavy)).foregroundColor(hu.fg).lineLimit(1).fixedSize() }
                .padding(.horizontal, 16).frame(minHeight: 44).background(hu.bg).clipShape(Capsule())
        }.buttonStyle(Press())
    }
}

/// A new PIN: shown for a minute with Copy, WhatsApp and SMS, then hidden for good.
struct OfficePinShown: View {
    let who: String; let pin: String; var phone = ""; var home = ""
    @State private var left = 60
    @State private var copied = false
    var body: some View {
        let ph = phone.trimmingCharacters(in: .whitespaces)
        CardV(fill: left > 0 ? C.okBg : ohue("slate").bg) {
            Text(t("PIN for ", "PIN ya ") + who).font(.tSmall).foregroundColor(left > 0 ? C.ok : C.muted)
            if left <= 0 {
                Text(t("Hidden now, to keep it safe. If they didn’t get it, make a new PIN.", "Imefichwa sasa, kwa usalama. Kama hawakuipata, tengeneza PIN mpya.")).font(.bMedium).foregroundColor(C.text).padding(.top, 6)
            } else {
                HStack {
                    Text(pin).font(.hLarge).tracking(8).foregroundColor(C.ink).padding(.vertical, 6).frame(maxWidth: .infinity, alignment: .leading)
                    Text(t("hides in \(left) s", "inajificha baada ya sekunde \(left)")).font(.lMedium).foregroundColor(C.muted).multilineTextAlignment(.trailing)
                }
                Text(t("Send it privately; it can’t be shown again.", "Mtumie kwa siri; haiwezi kuonyeshwa tena.")).font(.bSmall).foregroundColor(C.text)
                Btn(copied ? t("Copied ✓", "Imenakiliwa ✓") : t("Copy", "Nakili"), icon: "copy", light: true, small: true, wide: true) { UIPasteboard.general.string = pin; copied = true }.padding(.top, 10)
                HStack(spacing: 8) {
                    Btn("WhatsApp", icon: "whatsapp", light: true, small: true, wide: true) {
                        Share.open("https://wa.me/" + (ph.isEmpty ? "" : officeWaNumber(ph, home)) + "?text=" + (officePinMessage(who, pin).addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? ""))
                    }
                    Btn("SMS", icon: "phone", light: true, small: true, wide: true) {
                        Share.open("sms:" + ph.replacingOccurrences(of: " ", with: "") + "&body=" + (officePinMessage(who, pin).addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? ""))
                    }
                }.padding(.top, 8)
            }
        }
        .padding(.top, 12)
        .task(id: pin) { while left > 0 { try? await Task.sleep(nanoseconds: 1_000_000_000); left -= 1 } }
    }
}

/// Add a person: name, home country, e-mail, phone, admin; then their PIN.
struct OfficeTeamAddSheet: View {
    @EnvironmentObject var m: AppModel
    let close: () -> Void; let added: () -> Void
    @State private var name = ""
    @State private var home = ""
    @State private var email = ""
    @State private var phone = ""
    @State private var admin = false
    @State private var done: (String, String)?
    @State private var msg: String?
    @State private var busy = false
    var body: some View {
        OSheet {
            if case let (who, pin)? = done {
                head(who, who, t("Added to the team ✓", "Ameongezwa kwenye timu ✓"))
                OfficePinShown(who: who, pin: pin, phone: phone, home: home)
                Btn(t("Done", "Imekamilika"), light: true, wide: true, action: close).padding(.top, 12)
            } else {
                head(name, t("Add a person", "Ongeza mtu"), t("They get their own 4-digit PIN, shown once. Send it to them privately.", "Wanapata PIN yao ya tarakimu 4, inaonyeshwa mara moja. Wapelekee kwa siri."))
                Field(t("Name", "Jina"), $name, placeholder: t("e.g. Jessica Anderson", "mf. Jessica Anderson"))
                Picker1(label: t("Home country", "Nchi ya nyumbani"), value: home, options: [("", t("All countries", "Nchi zote"))] + COUNTRIES) { home = $0 }
                Field(t("E-mail (optional)", "Barua pepe (si lazima)"), $email, placeholder: t("for alerts", "kwa taarifa")).keyboardType(.emailAddress).textInputAutocapitalization(.never)
                Field(t("Phone (optional)", "Simu (si lazima)"), $phone, placeholder: "+254 712 345 678").keyboardType(.phonePad)
                adminSwitch
                Btn(t("Add and create PIN", "Ongeza na utengeneze PIN"), icon: "plus", enabled: !name.trimmingCharacters(in: .whitespaces).isEmpty && !busy, wide: true, busy: busy ? t("Saving…", "Inahifadhi…") : nil) {
                    busy = true
                    Task {
                        let n = name.trimmingCharacters(in: .whitespaces)
                        let x = await m.api.admin(["action": "team-add", "name": n, "home": home, "email": email.trimmingCharacters(in: .whitespaces), "phone": phone.trimmingCharacters(in: .whitespaces), "admin": admin])
                        busy = false
                        if x.ok { done = (n, x.json.str("pin")); Haptic.ok(); added() }
                        else { msg = x.reason == "exists" ? t("Someone with that name is already on the team.", "Mtu mwenye jina hilo tayari yuko kwenye timu.") : reasonText(x.reason) }
                    }
                }.padding(.top, 14)
                Msg(msg, "bad")
            }
        }
    }
    func head(_ who: String, _ title: String, _ sub: String) -> some View {
        ZStack(alignment: .topTrailing) {
            VStack(spacing: 4) {
                OfficeTeamAvatar(name: who.isEmpty ? "+" : who, admin: admin, size: 64).rise(0)
                Text(title).font(.tLarge).foregroundColor(C.ink).multilineTextAlignment(.center).padding(.top, 6)
                Text(sub).font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.center)
            }.frame(maxWidth: .infinity).padding(.horizontal, 44)
            OCloseX(action: close)
        }
    }
    var adminSwitch: some View {
        let hu = ohue("amber")
        return Button { admin.toggle() } label: {
            HStack(spacing: 12) {
                Image(systemName: "crown.fill").font(.system(size: 18)).foregroundColor(hu.fg).frame(width: 36, height: 36).background(hu.bg).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(t("Admin", "Msimamizi")).font(.tSmall).foregroundColor(C.ink)
                    Text(t("Sees the Office and can manage the team", "Anaona Ofisi na anaweza kusimamia timu")).font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.leading)
                }.frame(maxWidth: .infinity, alignment: .leading)
                Toggle("", isOn: $admin).labelsHidden().tint(C.blue)
            }.padding(12).overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.line, lineWidth: oEdge)).contentShape(Rectangle())
        }.buttonStyle(.plain).padding(.top, 14).accessibilityLabel(t("Admin", "Msimamizi"))
    }
}

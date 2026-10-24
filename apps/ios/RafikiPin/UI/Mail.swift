import PhotosUI
import SwiftUI
import UIKit

// Office › Send an e-mail: one message to donors, the admins or team members, one e-mail per person (the same server
// actions as the web and Android apps, see functions/api/mail.js and Android's Mail.kt).

/// Who an e-mail goes to when Office › Send an e-mail is opened from somewhere else (a contact sheet, Donors, a project).
struct MailPreset: Equatable { var aud: String; var sub = "all"; var project = ""; var picks: [String] = [] }
enum MailNav { static var preset: MailPreset? }

struct MlViewer { var key: String; var name: String; var why: String }
struct MlDonor { var id: String; var name: String; var projects: [String]; var viewers: [MlViewer] }
struct MlPerson { var key: String; var name: String; var role: String; var email: Bool }
struct MlRcpt { var name: String; var ok: Bool; var error: String }
struct MlLog { var id: String; var at: String; var by: String; var audience: String; var kind: String; var subject: String; var sent: Int; var failed: Int; var skipped: Int; var skippedBy: [String: Int]; var link: Bool; var rcpt: [MlRcpt] }
struct MlOverview { var ready: Bool; var month: Int; var donorsReachable: Int; var teamReachable: Int; var donors: [MlDonor]; var people: [MlPerson]; var log: [MlLog]; var me: Bool }

private extension Dictionary where Key == String, Value == Any {
    func s(_ k: String) -> String { let v = str(k); return v == "null" ? "" : v }
}
func parseMailOverview(_ j: JSON) -> MlOverview {
    let d = j.obj("donors") ?? [:], tm = j.obj("team") ?? [:]
    return MlOverview(
        ready: j.bool("ready"), month: (j.obj("use") ?? [:]).int("month"), donorsReachable: d.int("reachable"), teamReachable: tm.int("reachable"),
        donors: d.objs("list").map { x in MlDonor(id: x.s("id"), name: x.s("name"), projects: ((x["projects"] as? [Any]) ?? []).map { "\($0)" }, viewers: x.objs("viewers").map { MlViewer(key: $0.s("key"), name: $0.s("name"), why: $0.s("why")) }) },
        people: tm.objs("people").map { MlPerson(key: $0.s("key"), name: $0.s("name"), role: $0.s("role"), email: $0.bool("email")) },
        log: j.objs("log").map { e in
            let sb = e.obj("skippedBy") ?? [:]
            return MlLog(id: e.s("id"), at: e.s("at"), by: e.s("by"), audience: e.s("audience"), kind: e.s("kind"), subject: e.s("subject"), sent: e.int("sent"), failed: e.int("failed"), skipped: e.int("skipped"),
                         skippedBy: sb.reduce(into: [:]) { $0[$1.key] = ($1.value as? NSNumber)?.intValue ?? 0 }, link: e.bool("link"), rcpt: e.objs("rcpt").map { MlRcpt(name: $0.s("name"), ok: $0.s("s") == "sent", error: $0.s("e")) })
        },
        me: (j.obj("me") ?? [:]).bool("email"))
}
func mailAudience(_ aud: String, _ sub: String, _ project: String) -> String {
    switch aud {
    case "pick": return "pick"
    case "admins": return "admins"
    case "team": return sub == "pick" ? "people:pick" : "team"
    default: return sub == "project" && !project.isEmpty ? "donors:project:\(project)" : sub == "pick" ? "donors:pick" : "donors:all"
    }
}
/// How many it goes to, and who is skipped and why.
func mailCount(_ ov: MlOverview, _ audience: String, _ picks: [String]) -> (Int, [String: Int]) {
    var skip = ["unsub": 0, "revoked": 0, "no-email": 0]; var n = 0
    if audience == "pick" {
        for p in ov.people where picks.contains(p.key) { if p.email { n += 1 } else { skip["no-email", default: 0] += 1 } }
        for v in ov.donors.flatMap(\.viewers) where picks.contains(v.key) { if v.why.isEmpty { n += 1 } else { skip[v.why, default: 0] += 1 } }
    } else if audience.hasPrefix("donors") {
        for d in ov.donors { for v in d.viewers {
            let on = audience == "donors:all" || (audience == "donors:pick" && (picks.contains(v.key) || picks.contains(d.id))) || (audience.hasPrefix("donors:project:") && d.projects.contains(String(audience.dropFirst("donors:project:".count))))
            if on { if v.why.isEmpty { n += 1 } else { skip[v.why, default: 0] += 1 } }
        } }
    } else {
        for p in ov.people where audience == "team" || (audience == "admins" && p.role == "admin") || (audience == "people:pick" && picks.contains(p.key)) { if p.email { n += 1 } else { skip["no-email", default: 0] += 1 } }
    }
    return (n, skip)
}
/// "e-mail" never splits at its hyphen.
func mailNb(_ s: String) -> String { s.replacingOccurrences(of: "e-mail", with: "e\u{2011}mail").replacingOccurrences(of: "E-mail", with: "E\u{2011}mail") }
func mailSkipText(_ skip: [String: Int]) -> [String] {
    var out: [String] = []
    if let n = skip["unsub"], n > 0 { out.append(t("\(n) unsubscribed " + (n == 1 ? "is" : "are") + " skipped", "\(n) waliojiondoa wanarukwa")) }
    if let n = skip["revoked"], n > 0 { out.append(t("\(n) with a revoked link", "\(n) wenye kiungo kilichozimwa")) }
    if let n = skip["no-email"] ?? skip["noEmail"], n > 0 { out.append(t("\(n) without an e-mail", "\(n) bila barua pepe")) }
    return out.map(mailNb)
}
func mailCountWord(_ kind: String, _ n: Int) -> String {
    switch kind {
    case "donor": return n == 1 ? t("1 donor", "mfadhili 1") : t("\(n) donors", "wafadhili \(n)")
    case "admin": return n == 1 ? t("1 admin", "msimamizi 1") : t("\(n) admins", "wasimamizi \(n)")
    default: return n == 1 ? t("1 person", "mtu 1") : t("\(n) people", "watu \(n)")
    }
}
func mailKind(_ audience: String) -> String { audience.hasPrefix("donors") ? "donor" : audience == "admins" ? "admin" : "team" }
/// {first} and {name}, as the server replaces them.
func mailPersonalise(_ s: String, _ name: String) -> String {
    let n = name.trimmingCharacters(in: .whitespaces), first = n.split(separator: " ").first.map(String.init) ?? ""
    return s.replacingOccurrences(of: #"\{\s*first\s*\}"#, with: first.isEmpty ? "friend" : first, options: [.regularExpression, .caseInsensitive])
        .replacingOccurrences(of: #"\{\s*name\s*\}"#, with: n.isEmpty ? "friend" : n, options: [.regularExpression, .caseInsensitive])
}
/// The message as paragraphs and bullet lists: ("p", lines) or ("ul", items).
func mailBlocks(_ text: String) -> [(String, [String])] {
    let paras = text.replacingOccurrences(of: "\r\n", with: "\n").components(separatedBy: try! NSRegularExpression(pattern: #"\n\s*\n"#))
    var out: [(String, [String])] = []
    for b in paras.map({ $0.trimmingCharacters(in: CharacterSet(charactersIn: "\n")) }).filter({ !$0.trimmingCharacters(in: .whitespaces).isEmpty }) {
        var cur: (String, [String])?
        for l in b.components(separatedBy: "\n") {
            let bullet = l.range(of: #"^\s*[-•]\s+"#, options: .regularExpression)
            let k = bullet != nil ? "ul" : "p"
            if cur?.0 != k { if let c = cur { out.append(c) }; cur = (k, []) }
            cur!.1.append(bullet.map { String(l[$0.upperBound...]) } ?? l.trimmingCharacters(in: .whitespaces))
        }
        if let c = cur { out.append(c) }
    }
    return out
}
private extension String {
    func components(separatedBy re: NSRegularExpression) -> [String] {
        var out: [String] = []; var last = startIndex
        for mm in re.matches(in: self, range: NSRange(startIndex..., in: self)) { if let r = Range(mm.range, in: self) { out.append(String(self[last..<r.lowerBound])); last = r.upperBound } }
        out.append(String(self[last...])); return out
    }
}
func mailWhyText(_ reason: String?, _ j: JSON = [:]) -> String {
    let s: String
    switch reason {
    case "no-mail": s = t("E-mail isn’t switched on yet. Whoever looks after the website can turn it on.", "Barua pepe bado haijawashwa. Anayesimamia tovuti anaweza kuiwasha.")
    case "cap": s = t("One send can go to at most \(j.int("cap")) people. Choose fewer, or send it in parts.", "Utumaji mmoja unaweza kwenda kwa watu \(j.int("cap")) tu. Chagua wachache, au utume kwa sehemu.")
    case "day-cap": s = t("Today’s e-mail allowance is nearly used up: \(j.int("left")) left today. Choose fewer people or send it tomorrow.", "Kiwango cha barua pepe cha leo kinakaribia kwisha: \(j.int("left")) zimebaki leo. Chagua watu wachache au utume kesho.")
    case "month-cap": s = t("This month’s e-mail allowance is nearly used up: \(j.int("left")) left this month.", "Kiwango cha barua pepe cha mwezi huu kinakaribia kwisha: \(j.int("left")) zimebaki mwezi huu.")
    case "rate": s = t("That’s a lot of e-mails in the last hour. Please wait a while and try again.", "Barua pepe nyingi ndani ya saa iliyopita. Tafadhali subiri kidogo kisha ujaribu tena.")
    case "no-own-email": s = t("Add your own e-mail in Office › Team first, so the test can reach you.", "Ongeza barua pepe yako kwenye Ofisi › Timu kwanza, ili jaribio likufikie.")
    case "empty": s = t("Add a subject and a message.", "Ongeza kichwa na ujumbe.")
    case "nobody": s = t("Nobody to send to. Choose who it goes to.", "Hakuna wa kutumiwa. Chagua anaotumiwa.")
    case "changed": s = t("The list changed. Check the count and send again.", "Orodha imebadilika. Angalia idadi kisha utume tena.")
    case "not-admin": s = t("Only admins can send e-mails.", "Wasimamizi tu ndio wanaweza kutuma barua pepe.")
    case "too-large": s = t("That photo is too large.", "Picha hiyo ni kubwa mno.")
    case "type": s = t("That kind of file can’t be added.", "Aina hiyo ya faili haiwezi kuongezwa.")
    default: s = j.str("detail").isEmpty ? reasonText(reason ?? "") : t("Not sent. Resend said: ", "Haijatumwa. Resend imesema: ") + j.str("detail")
    }
    return mailNb(s)
}
private func techFor(_ reason: String?) -> [(String, Bool)]? {
    switch reason { case "no-mail": return [("RESEND_API_KEY", false), ("ALERT_FROM", false)]; case "cap", "day-cap", "month-cap": return [("MAIL_CAP", true), ("MAIL_DAY_CAP", true), ("MAIL_MONTH_CAP", true)]; default: return nil }
}
func mailShrink(_ data: Data, max: CGFloat = 1600) -> Data? {
    guard let img = UIImage(data: data) else { return nil }
    let k = min(1, max / Swift.max(img.size.width, img.size.height)), size = CGSize(width: img.size.width * k, height: img.size.height * k)
    let f = UIGraphicsImageRendererFormat(); f.scale = 1
    return UIGraphicsImageRenderer(size: size, format: f).image { _ in img.draw(in: CGRect(origin: .zero, size: size)) }.jpegData(compressionQuality: 0.82)
}
private func mailAudWord(_ a: String, _ snap: Snapshot) -> String {
    if a == "donors:all" { return t("All donors", "Wafadhili wote") }
    if a == "donors:pick" { return t("Chosen donors", "Wafadhili waliochaguliwa") }
    if a.hasPrefix("donors:project:") { let id = String(a.dropFirst("donors:project:".count)); return t("Donors of ", "Wafadhili wa ") + (snap.projects.first { $0.id == id }?.title ?? id) }
    if a == "admins" { return t("Admins", "Wasimamizi") }
    if a == "team" { return t("The whole team", "Timu nzima") }
    if a == "pick" { return t("Chosen people", "Watu waliochaguliwa") }
    return t("Chosen team members", "Wanatimu waliochaguliwa")
}

/// The Office tab: a head with three numbers, the composer, and what was sent.
struct MailSection: View {
    @EnvironmentObject var m: AppModel
    let s: Snapshot
    @State private var tick = 0
    @State private var ov: MlOverview?
    @State private var loadMsg: String?
    @State private var aud = "donors"
    @State private var sub = "all"
    @State private var project = ""
    @State private var picks: [String] = []
    @State private var q = ""
    @State private var subject = ""
    @State private var body_ = ""
    @State private var btnOn = false
    @State private var btnLabel = ""
    @State private var btnHref = ""
    @State private var photo: (String, String, String)?
    @State private var link = false
    @State private var msg: (String, String, String?)?
    @State private var busy = false
    @State private var preview: JSON?
    @State private var confirm: JSON?
    @State private var photoSheet = false
    @State private var logOpen: String?
    @State private var item: PhotosPickerItem?
    private var audience: String { mailAudience(aud, sub, project) }
    private func draft(_ action: String) -> JSON {
        var d: JSON = ["action": action, "audience": audience, "picks": picks, "subject": subject, "body": body_, "includeLink": aud == "donors" && link]
        if audience == "pick", let o = ov { let ks = Set(o.people.map(\.key)); d["aud"] = "pick"; d["people"] = picks.filter { ks.contains($0) }; d["donors"] = picks.filter { !ks.contains($0) } }
        d["button"] = btnOn ? ["label": btnLabel, "href": btnHref] as JSON : NSNull()
        d["photo"] = photo.map { ["kind": $0.0, "id": $0.1] as JSON } ?? NSNull()
        return d
    }
    private func check(_ full: Bool) -> String? {
        if ["donors:pick", "people:pick", "pick"].contains(audience) && picks.isEmpty { return t("Tick who it goes to.", "Chagua anaotumiwa.") }
        if aud == "donors" && sub == "project" && project.isEmpty { return t("Choose a project.", "Chagua mradi.") }
        if full && (subject.trimmingCharacters(in: .whitespaces).isEmpty || body_.trimmingCharacters(in: .whitespaces).isEmpty) { return t("Add a subject and a message.", "Ongeza kichwa na ujumbe.") }
        if btnOn && (btnLabel.isEmpty || btnHref.trimmingCharacters(in: .whitespaces).range(of: #"^https?://\S+\.\S+"#, options: .regularExpression) == nil) { return t("Give the button a text and a link starting with https://", "Kipe kitufe maandishi na kiungo kinachoanza na https://") }
        return nil
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            DqmHead(icon: "mail", hue: "blue", title: mailNb(t("Send an e-mail", "Tuma barua pepe")), line: mailNb(t("Write to donors, the admins or the team. Everyone gets their own e-mail.", "Waandikie wafadhili, wasimamizi au timu. Kila mtu anapata barua pepe yake.")),
                    nums: ov.map { o in [DqmNum(n: o.month, label: t("sent this month", "zilizotumwa mwezi huu"), tone: o.month > 0 ? "has" : "off"), DqmNum(n: o.donorsReachable, label: t("donors reachable", "wafadhili wanaofikiwa"), tone: o.donorsReachable > 0 ? "ok" : "off"),
                                         DqmNum(n: o.teamReachable, label: t("team reachable", "timu inayofikiwa"), tone: o.teamReachable > 0 ? "ok" : "off")] } ?? [])
            if let loadMsg { Msg(loadMsg, "bad") }
            if let o = ov { content(o) } else if loadMsg == nil { Text(t("Loading…", "Inapakia…")).foregroundColor(C.muted).padding(.top, 12) }
        }
        .onAppear {
            if let pre = MailNav.preset { aud = pre.aud; sub = pre.sub; project = pre.project; picks = pre.picks; MailNav.preset = nil; if pre.aud != "donors" { link = false } }
        }
        .task(id: tick) { let r = await m.api.mailGet(); if r.ok { ov = parseMailOverview(r.json); loadMsg = nil } else { loadMsg = mailWhyText(r.reason, r.json) } }
        .onChange(of: item) { it in
            guard let it else { return }
            msg = ("", t("Adding the photo…", "Inaongeza picha…"), nil)
            Task {
                guard let raw = try? await it.loadTransferable(type: Data.self), let data = mailShrink(raw) else { msg = ("bad", t("Couldn’t read that file.", "Haikuweza kusoma faili hiyo."), nil); return }
                let r = await m.api.mail(["action": "photo", "type": "image/jpeg", "data": data.base64EncodedString()])
                if r.ok { photo = ("upload", r.json.str("id"), t("Your photo", "Picha yako")); msg = nil } else { msg = ("bad", mailWhyText(r.reason, r.json), r.reason) }
                item = nil
            }
        }
        .sheet(isPresented: $photoSheet) { photoPicker }
        .sheet(item: Binding(get: { preview.map { JBox(j: $0) } }, set: { preview = $0?.j })) { b in previewSheet(b.j) }
        .sheet(item: Binding(get: { confirm.map { JBox(j: $0) } }, set: { confirm = $0?.j })) { b in confirmSheet(b.j) }
        .sheet(item: Binding(get: { logOpen.map { WhoBox(name: $0) } }, set: { logOpen = $0?.name })) { b in if let e = ov?.log.first(where: { $0.id == b.name }) { logSheet(e) } }
    }

    @ViewBuilder private func content(_ o: MlOverview) -> some View {
        if !o.ready {
            VStack(alignment: .leading, spacing: 0) {
                Text(mailNb(t("E-mail isn’t switched on yet. You can write and preview; whoever looks after the website can turn sending on.", "Barua pepe bado haijawashwa. Unaweza kuandika na kuhakiki; anayesimamia tovuti anaweza kuwasha utumaji."))).font(.mont(14, .semibold)).foregroundColor(C.warn)
                TechDetails(names: [("RESEND_API_KEY", false), ("ALERT_FROM", false)])
            }.padding(14).frame(maxWidth: .infinity, alignment: .leading).background(C.warnBg).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).padding(.top, 12)
        }
        CardV(pad: 16) {
            Text(t("To", "Kwa")).font(.tSmall).foregroundColor(C.ink)
            VStack(spacing: 4) {
                ForEach([[("donors", t("Donors", "Wafadhili")), ("admins", t("Admins", "Wasimamizi"))], [("team", t("Team", "Timu")), ("pick", t("Choose people", "Chagua watu"))]], id: \.first!.0) { row in
                    HStack(spacing: 4) {
                        ForEach(row, id: \.0) { k, l in
                            Button { Haptic.tap(); aud = k; sub = "all"; picks = []; q = ""; if k != "donors" { link = false } } label: {
                                Text(l).font(.mont(14, .bold)).foregroundColor(aud == k ? .white : C.ink).lineLimit(1).minimumScaleFactor(0.8).frame(maxWidth: .infinity, minHeight: 44).background(Capsule().fill(aud == k ? C.blue : .clear))
                            }.buttonStyle(.plain)
                        }
                    }
                }
            }.padding(4).background(C.bg).clipShape(RoundedRectangle(cornerRadius: 26, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 26, style: .continuous).stroke(C.line, lineWidth: 1)).padding(.top, 8)
            let subs: [(String, String)] = aud == "donors" ? [("all", t("All donors", "Wafadhili wote")), ("project", t("One project", "Mradi mmoja")), ("pick", t("Choose people", "Chagua watu"))] : aud == "team" ? [("all", t("Everyone", "Wote")), ("pick", t("Choose people", "Chagua watu"))] : []
            if !subs.isEmpty { HStack(spacing: 8) { ForEach(subs, id: \.0) { k, l in FilterPill(label: l, on: sub == k) { sub = k; picks = []; q = "" } } }.padding(.top, 10) }
            if aud == "donors" && sub == "project" {
                let ps = Array(Set(o.donors.flatMap(\.projects))).map { id in (id, s.projects.first { $0.id == id }?.title ?? t("Project \(id)", "Mradi \(id)")) }.sorted { $0.1 < $1.1 }
                Picker1(label: t("Project", "Mradi"), value: project, options: [("", t("Choose a project…", "Chagua mradi…"))] + ps.map { ($0.0, "\($0.1) (\(mailCount(o, "donors:project:\($0.0)", []).0))") }) { project = $0 }
            }
            if sub == "pick" && aud != "admins" {
                let why = ["unsub": t("unsubscribed", "amejiondoa"), "revoked": t("link revoked", "kiungo kimezimwa"), "no-email": mailNb(t("no e-mail", "hana barua pepe"))]
                let rows: [[String]] = (aud == "donors" ? o.donors.flatMap { d in d.viewers.map { [$0.key, $0.name, d.name != $0.name ? d.name : "", $0.why] } }
                                        : o.people.map { [$0.key, $0.name, $0.role == "admin" ? t("Admin", "Msimamizi") : t("Field team", "Timu ya uwanjani"), $0.email ? "" : "no-email"] })
                    .filter { q.isEmpty || ($0[1] + " " + $0[2]).localizedCaseInsensitiveContains(q.trimmingCharacters(in: .whitespaces)) }
                    .sorted { $0[3].isEmpty != $1[3].isEmpty ? $0[3].isEmpty : $0[1] < $1[1] }
                Field(t("Find a name", "Tafuta jina"), $q)
                pickBox(Array(rows.prefix(60)), why)
            }
            if aud == "pick" { pickList(o) }
            let (n, skip) = mailCount(o, audience, picks)
            let countText = ["donors:pick", "people:pick", "pick"].contains(audience) && picks.isEmpty ? t("Tick who it goes to below.", "Chagua anaotumiwa hapa chini.")
                : aud == "donors" && sub == "project" && project.isEmpty ? t("Choose a project.", "Chagua mradi.")
                : ([n > 0 ? t("Goes to ", "Inaenda kwa ") + mailCountWord(mailKind(audience), n) : t("Nobody to send to yet", "Bado hakuna wa kutumiwa")] + mailSkipText(skip)).joined(separator: " · ")
            HStack(alignment: .top, spacing: 10) { Icon("mail", size: 20, color: C.blue2); Text(countText).font(.mont(14, .semibold)).foregroundColor(C.ink) }
                .padding(.horizontal, 12).padding(.vertical, 10).frame(maxWidth: .infinity, alignment: .leading).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).padding(.top, 12)
            Field(t("Subject", "Kichwa"), Binding(get: { subject }, set: { subject = String($0.prefix(150)) }), placeholder: aud == "donors" ? t("e.g. Water is flowing in Kapsaret", "mf. Maji yanatiririka Kapsaret") : t("e.g. Team meeting on Friday", "mf. Mkutano wa timu Ijumaa"))
            Field(t("Message", "Ujumbe"), Binding(get: { body_ }, set: { body_ = String($0.prefix(6000)) }), placeholder: aud == "donors" ? t("Dear {first},", "Mpendwa {first},") : t("Hello {first},", "Habari {first},"), lines: 6)
            Text(t("{first} becomes each person’s first name and {name} their full name. Start a line with “- ” for a bullet.", "{first} inakuwa jina la kwanza la kila mtu na {name} jina lake kamili. Anza mstari kwa “- ” kupata kitone.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 6)
            Check(label: t("Add a button", "Ongeza kitufe"), on: btnOn) { btnOn.toggle() }.padding(.top, 8)
            if btnOn {
                Field(t("Button text", "Maandishi ya kitufe"), Binding(get: { btnLabel }, set: { btnLabel = String($0.prefix(40)) }), placeholder: t("e.g. Read the story", "mf. Soma hadithi"))
                Field(t("Link", "Kiungo"), Binding(get: { btnHref }, set: { btnHref = String($0.prefix(500)) }), placeholder: "https://").keyboardType(.URL).textInputAutocapitalization(.never)
            }
            Text(t("Photo (optional)", "Picha (si lazima)")).font(.mont(13, .bold)).foregroundColor(C.ink).padding(.top, 12).padding(.bottom, 6)
            if let ph = photo {
                HStack(spacing: 10) { IconTile(icon: "camera", tint: C.blue2, bg: C.tint, size: 40); Text(ph.2).font(.mont(14, .semibold)).foregroundColor(C.ink); Spacer(); Btn(t("Remove", "Ondoa"), light: true, small: true) { photo = nil } }
                    .padding(10).overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(C.line, lineWidth: 1))
            } else {
                HStack(spacing: 8) {
                    Btn(t("Project photo", "Picha ya mradi"), icon: "camera", light: true, small: true, wide: true) { photoSheet = true }
                    PhotosPicker(selection: $item, matching: .images) {
                        HStack(spacing: 8) { Icon("upload", size: 18, color: C.ink); Text(t("Upload", "Pakia")).font(.mont(14, .bold)).foregroundColor(C.ink) }
                            .frame(maxWidth: .infinity, minHeight: 44).background(C.card).clipShape(Capsule()).overlay(Capsule().stroke(C.edge, lineWidth: 1))
                    }
                }
            }
            if aud == "donors" {
                Button { link.toggle() } label: {
                    HStack(alignment: .top, spacing: 10) {
                        Image(systemName: link ? "checkmark.square.fill" : "square").font(.system(size: 22)).foregroundColor(link ? C.blue : C.muted)
                        VStack(alignment: .leading, spacing: 1) { Text(t("Include their private project link", "Weka kiungo cha siri cha miradi yao")).font(.bMedium).foregroundColor(C.text); Text(t("Each donor gets their own link.", "Kila mfadhili anapata kiungo chake.")).font(.bSmall).foregroundColor(C.muted) }
                        Spacer(minLength: 0)
                    }.frame(minHeight: 44)
                }.buttonStyle(.plain).padding(.top, 8)
            }
            HStack(spacing: 8) {
                Btn(t("Preview", "Hakiki"), icon: "search", light: true, wide: true) {
                    if let e = check(false) { msg = ("bad", e, nil); return }
                    msg = ("", t("Getting the preview…", "Inaleta hakikisho…"), nil)
                    Task { let r = await m.api.mail(draft("preview")); if r.ok { msg = nil; preview = r.json } else { msg = ("bad", mailWhyText(r.reason, r.json), r.reason) } }
                }
                Btn(t("Send me a test", "Nitumie jaribio"), icon: "mail", light: true, wide: true) {
                    if let e = check(true) { msg = ("bad", e, nil); return }
                    msg = ("", t("Sending you a test…", "Inakutumia jaribio…"), nil)
                    Task { let r = await m.api.mail(draft("test")); msg = r.ok ? ("ok", t("Test sent to \(r.json.str("to")) ✓ Check your inbox.", "Jaribio limetumwa kwa \(r.json.str("to")) ✓ Angalia barua pepe yako."), nil) : ("bad", mailWhyText(r.reason, r.json), r.reason) }
                }
            }.padding(.top, 14)
            Btn(t("Send", "Tuma"), icon: "check", enabled: !busy, wide: true) {
                if let e = check(true) { msg = ("bad", e, nil); return }
                if !o.ready { msg = ("bad", mailWhyText("no-mail"), "no-mail"); return }
                msg = ("", t("Checking who it goes to…", "Inakagua inaenda kwa nani…"), nil)
                Task {
                    let r = await m.api.mail(draft("preview"))
                    if !r.ok { msg = ("bad", mailWhyText(r.reason, r.json), r.reason); return }
                    if r.json.int("count") == 0 { msg = ("bad", mailWhyText("nobody"), nil); return }
                    msg = nil; confirm = r.json
                }
            }.padding(.top, 8)
            if let msg { Msg(msg.1, msg.0); if let tf = techFor(msg.2) { TechDetails(names: tf) } }
        }.padding(.top, 12)
        Text(t("Sent", "Zilizotumwa")).font(.tMedium).foregroundColor(C.ink).padding(.top, 22).padding(.bottom, 2)
        if o.log.isEmpty { EmptyDrop(text: t("Nothing sent yet. What you send shows here.", "Bado hakuna kilichotumwa. Unachotuma kinaonekana hapa.")) }
        ForEach(Array(o.log.enumerated()), id: \.offset) { i, e in
            let tone = e.failed > 0 && e.sent == 0 ? "bad" : e.failed > 0 ? "has" : "ok", donor = e.kind == "donor", hu = C.named(donor ? "coral" : "blue")
            DqmCard(title: e.subject, pill: (tone, [t("\(e.sent) sent", "zimetumwa \(e.sent)"), e.failed > 0 ? t("\(e.failed) failed", "hazikufika \(e.failed)") : nil, e.skipped > 0 ? t("\(e.skipped) skipped", "zimerukwa \(e.skipped)") : nil].compactMap { $0 }.joined(separator: " · ")),
                    lines: [mailAudWord(e.audience, s), e.by + " · " + Dates.dateTime(e.at)], lead: { IconTile(icon: donor ? "star" : "people", tint: hu.fg, bg: hu.bg, size: 44) }) { logOpen = e.id }
                .padding(.top, 10).rise(min(i, 6))
        }
    }

    private func pickBox(_ rows: [[String]], _ why: [String: String]) -> some View {
        VStack(spacing: 0) {
            ForEach(Array(rows.enumerated()), id: \.offset) { i, r in
                let off = !r[3].isEmpty, on = picks.contains(r[0]) && !off
                if i > 0 { Rectangle().fill(C.line).frame(height: 1) }
                Button { if on { picks.removeAll { $0 == r[0] } } else { picks.append(r[0]) } } label: {
                    HStack(spacing: 8) {
                        Image(systemName: on ? "checkmark.square.fill" : "square").font(.system(size: 22)).foregroundColor(off ? C.off : on ? C.blue : C.muted)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(r[1]).font(.mont(14, .bold)).foregroundColor(off ? C.muted : C.ink)
                            let sub = [r[2], why[r[3]] ?? ""].filter { !$0.isEmpty }.joined(separator: " · ")
                            if !sub.isEmpty { Text(sub).font(.bSmall).foregroundColor(C.muted) }
                        }
                        Spacer(minLength: 0)
                    }.padding(.horizontal, 10).padding(.vertical, 4).frame(minHeight: 52).contentShape(Rectangle())
                }.buttonStyle(.plain).disabled(off)
            }
            if rows.isEmpty { Text(t("Nobody matches.", "Hakuna anayelingana.")).foregroundColor(C.muted).padding(12) }
        }.overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(C.line, lineWidth: 1)).padding(.top, 8)
    }

    /// Choose people: everyone who can get e-mail, under Admins, Team and Donors, each with "Select all"; then chips.
    @ViewBuilder private func pickList(_ o: MlOverview) -> some View {
        let groups: [(String, String, [[String]])] = [
            ("admins", t("Admins", "Wasimamizi"), o.people.filter { $0.role == "admin" }.map { [$0.key, $0.name, t("Admin", "Msimamizi"), $0.email ? "" : "no-email"] }),
            ("team", t("Team", "Timu"), o.people.filter { $0.role != "admin" }.map { [$0.key, $0.name, t("Field team", "Timu ya uwanjani"), $0.email ? "" : "no-email"] }),
            ("donors", t("Donors", "Wafadhili"), o.donors.flatMap { d in d.viewers.map { [$0.key, $0.name, d.name != $0.name ? d.name : t("Donor", "Mfadhili"), $0.why] } }),
        ]
        let names = Dictionary(groups.flatMap { $0.2 }.map { ($0[0], $0[1]) }, uniquingKeysWith: { a, _ in a })
        let why = ["unsub": t("unsubscribed", "amejiondoa"), "revoked": t("link revoked", "kiungo kimezimwa"), "no-email": mailNb(t("no e-mail", "hana barua pepe"))]
        Field(t("Find a name", "Tafuta jina"), $q, placeholder: t("Name, role or donor", "Jina, nafasi au mfadhili"))
        ForEach(groups, id: \.0) { _, title, all in
            let rows = all.filter { q.isEmpty || ($0[1] + " " + $0[2]).localizedCaseInsensitiveContains(q.trimmingCharacters(in: .whitespaces)) }.sorted { $0[3].isEmpty != $1[3].isEmpty ? $0[3].isEmpty : $0[1].lowercased() < $1[1].lowercased() }
            if !rows.isEmpty {
                let ok = rows.filter { $0[3].isEmpty }.map { $0[0] }, allOn = !ok.isEmpty && ok.allSatisfy { picks.contains($0) }
                HStack {
                    Text(title.uppercased()).font(.mont(12, .heavy)).tracking(1).foregroundColor(C.muted)
                    Spacer()
                    if !ok.isEmpty { Button(allOn ? t("Clear", "Ondoa wote") : t("Select all", "Chagua wote")) { if allOn { picks.removeAll { ok.contains($0) } } else { picks += ok.filter { !picks.contains($0) } } }.font(.lLarge).foregroundColor(C.blue).frame(minHeight: 40) }
                }.padding(.top, 14).padding(.bottom, 4)
                pickBox(Array(rows.prefix(80)), why)
            }
        }
        if !picks.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(picks, id: \.self) { k in
                        Button { picks.removeAll { $0 == k } } label: {
                            HStack(spacing: 4) { Text(names[k] ?? k).font(.mont(13, .bold)).foregroundColor(C.ink); Icon("x", size: 12, color: C.muted) }.padding(.horizontal, 12).frame(minHeight: 36).background(C.card).clipShape(Capsule()).overlay(Capsule().stroke(C.line, lineWidth: 1))
                        }.buttonStyle(.plain)
                    }
                }
            }.padding(.top, 10)
        }
    }

    private var photoPicker: some View {
        PhotoPickSheet(projects: s.projects, first: project) { p in photo = ("project", p.photo!, p.title) }
    }
    private func previewSheet(_ pv: JSON) -> some View {
        let cnt = pv.int("count"), names = ((pv["names"] as? [Any]) ?? []).compactMap { $0 as? String }
        return OcvSheet { close in
            DqmSheetHead(title: pv.str("subject").isEmpty ? t("(no subject)", "(hakuna kichwa)") : pv.str("subject"),
                         sub: [cnt > 0 ? t("Goes to ", "Inaenda kwa ") + mailCountWord(mailKind(audience), cnt) + ": " + names.prefix(5).joined(separator: ", ") + (cnt > 5 ? t(" and \(cnt - 5) more", " na wengine \(cnt - 5)") : "") : t("Nobody to send to yet", "Bado hakuna wa kutumiwa"),
                               t("Shown as \(pv.str("sample")) gets it. Links in the preview are samples.", "Inaonyeshwa kama \(pv.str("sample")) atakavyoipata. Viungo kwenye hakikisho ni mifano.")], close: close) {
                IconTile(icon: "mail", tint: C.blue2, bg: C.tint, size: 64)
            }
            MailPreview(subject: pv.str("subject"), text: mailPersonalise(body_, pv.str("sample")), donor: pv.str("kind") == "donor", by: s.who, button: btnOn ? btnLabel : nil, photo: photo?.2, link: aud == "donors" && link)
        }
    }
    private func confirmSheet(_ c: JSON) -> some View {
        let cnt = c.int("count"), who = mailCountWord(mailKind(audience), cnt)
        let sk = (c.obj("skipped") ?? [:]).reduce(into: [String: Int]()) { $0[$1.key] = ($1.value as? NSNumber)?.intValue ?? 0 }
        return OcvSheet { close in
            DqmSheetHead(title: t("Send to \(who)?", "Tuma kwa \(who)?"), close: close) { IconTile(icon: "mail", tint: C.blue2, bg: C.tint, size: 64) }
            Text(mailNb(t("Each person gets their own e-mail, so nobody sees anyone else’s address. ", "Kila mtu anapata barua pepe yake, kwa hiyo hakuna anayeona anwani ya mwingine. ") + { let x = mailSkipText(sk).joined(separator: " · "); return x.isEmpty ? "" : x + ". " }() + t("It can’t be called back once sent.", "Haiwezi kurudishwa ikishatumwa.")))
                .font(.bMedium).foregroundColor(C.text).multilineTextAlignment(.center).frame(maxWidth: .infinity).padding(.top, 12)
            Btn(t("Send to \(who)", "Tuma kwa \(who)"), icon: "check", wide: true) {
                busy = true; close()
                msg = ("", t("Sending…", "Inatuma…"), nil)
                Task {
                    var d = draft("send"); d["expect"] = cnt
                    let r = await m.api.mail(d)
                    busy = false
                    if !r.ok { msg = ("bad", mailWhyText(r.reason, r.json), r.reason); return }
                    let j = r.json
                    msg = (j.int("failed") > 0 ? "warn" : "ok", t("Sent ✓ ", "Imetumwa ✓ ") + [t("\(j.int("sent")) sent", "zimetumwa \(j.int("sent"))"), j.int("skipped") > 0 ? t("\(j.int("skipped")) skipped", "zimerukwa \(j.int("skipped"))") : nil, j.int("failed") > 0 ? t("\(j.int("failed")) failed", "hazikufika \(j.int("failed"))") : nil].compactMap { $0 }.joined(separator: " · "), nil)
                    Haptic.ok(); subject = ""; body_ = ""; photo = nil; btnOn = false; btnLabel = ""; btnHref = ""; tick += 1
                }
            }.padding(.top, 16)
            DqmLineBtn(text: t("Cancel", "Ghairi"), icon: "x") { close() }.padding(.top, 8)
        }.presentationDetents([.medium])
    }
    private func logSheet(_ e: MlLog) -> some View {
        OcvSheet { close in
            let (fg, bg) = dqmTone(e.failed > 0 ? "has" : "ok")
            DqmSheetHead(title: e.subject, sub: [mailAudWord(e.audience, s)], close: close) { IconTile(icon: "mail", tint: fg, bg: bg, size: 64) }
            DqmFact(label: t("Sent by", "Imetumwa na"), value: e.by); DqmFact(label: t("When", "Lini"), value: Dates.dateTime(e.at))
            DqmFact(label: t("Sent", "Zimetumwa"), value: "\(e.sent)", sub: e.failed > 0 ? t("\(e.failed) not delivered", "\(e.failed) hazikufika") : "")
            if e.skipped > 0 { DqmFact(label: t("Skipped", "Zimerukwa"), value: "\(e.skipped)", sub: mailSkipText(e.skippedBy).joined(separator: "\n")) }
            if e.link { DqmFact(label: t("Private link", "Kiungo cha siri"), value: t("Included", "Kimewekwa")) }
            GroupLabel(t("Each person", "Kila mtu"), top: 18)
            ForEach(Array(e.rcpt.enumerated()), id: \.offset) { _, r in
                HStack(spacing: 10) {
                    Circle().fill(r.ok ? C.ok : C.bad).frame(width: 8, height: 8)
                    VStack(alignment: .leading, spacing: 1) { Text(r.name).font(.mont(14, .bold)).foregroundColor(C.ink); if !r.error.isEmpty { Text(r.error).font(.bSmall).foregroundColor(C.muted) } }
                    Spacer(); Text(r.ok ? t("Sent", "Imetumwa") : t("Not delivered", "Haikufika")).font(.lMedium).foregroundColor(r.ok ? C.muted : C.bad)
                }.padding(.vertical, 8)
                Rectangle().fill(C.line).frame(height: 1)
            }
        }
    }
}

/// A project's website photo, for an e-mail.
private struct PhotoPickSheet: View {
    @Environment(\.dismiss) private var dismiss
    let projects: [Project]; let first: String; let pick: (Project) -> Void
    @State private var pq = ""
    var body: some View {
        OcvSheet { close in
            DqmSheetHead(title: t("Project photo", "Picha ya mradi"), sub: [t("The photo shown on the website for that project.", "Picha inayoonyeshwa kwenye tovuti kwa mradi huo.")], close: close) { IconTile(icon: "camera", tint: C.blue2, bg: C.tint, size: 64) }
            Field(t("Search projects", "Tafuta miradi"), $pq)
            let list = Array(projects.filter { $0.photo != nil && (pq.isEmpty || "\($0.title) \($0.area)".localizedCaseInsensitiveContains(pq.trimmingCharacters(in: .whitespaces))) }.sorted { ($0.id == first) != ($1.id == first) ? $0.id == first : $0.title < $1.title }.prefix(40))
            ForEach(list) { p in
                Button { pick(p); close() } label: {
                    HStack(spacing: 10) {
                        IconTile(icon: "camera", tint: C.blue2, bg: C.tint, size: 36)
                        VStack(alignment: .leading, spacing: 1) { Text(p.title).font(.mont(14, .bold)).foregroundColor(C.ink).multilineTextAlignment(.leading); Text([p.area, p.country].filter { !$0.isEmpty }.joined(separator: " · ")).font(.bSmall).foregroundColor(C.muted) }
                        Spacer(minLength: 0)
                    }.padding(10).frame(minHeight: 52).overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(C.line, lineWidth: 1)).contentShape(Rectangle())
                }.buttonStyle(.plain).padding(.top, 8)
            }
        }
    }
}

/// The e-mail as people get it, drawn from the same parts the server uses. E-mails are always light.
struct MailPreview: View {
    let subject: String; let text: String; let donor: Bool; let by: String; let button: String?; let photo: String?; let link: Bool
    var body: some View {
        let fg = donor ? Color(hex: 0xD23C32) : Color(hex: 0x0A78C2), tint = donor ? Color(hex: 0xFDE9E6) : Color(hex: 0xE3F0FA)
        let navy = Color(hex: 0x04263F), ink = Color(hex: 0x1F2A33), grey = Color(hex: 0x5A6066)
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 0) {
                Rectangle().fill(fg).frame(height: 5)
                HStack {
                    if let logo = UIImage(named: "LetterheadLogo") { Image(uiImage: logo).resizable().scaledToFit().frame(height: 30) }
                    Spacer()
                    Text((donor ? "RAFIKI Outreach" : "RAFIKI Pin").uppercased()).font(.system(size: 10, weight: .bold)).tracking(0.8).foregroundColor(fg).padding(.horizontal, 10).padding(.vertical, 4).background(tint).clipShape(Capsule())
                }.padding(.horizontal, 16).padding(.vertical, 10)
                Rectangle().fill(Color(hex: 0xE8EDF2)).frame(height: 1)
                VStack(alignment: .leading, spacing: 0) {
                    Image(systemName: donor ? "heart" : "bell").font(.system(size: 18, weight: .semibold)).foregroundColor(fg).frame(width: 40, height: 40).background(tint).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    Text((donor ? "News from RAFIKI Outreach" : "Message from " + (by.isEmpty ? "the office" : by)).uppercased()).font(.system(size: 11, weight: .heavy)).tracking(0.8).foregroundColor(fg).padding(.top, 12)
                    Text(subject).font(.system(size: 21, weight: .bold)).foregroundColor(navy).padding(.top, 4)
                    if let photo {
                        VStack(spacing: 4) { Image(systemName: "camera").font(.system(size: 24)).foregroundColor(.white); Text(photo).font(.system(size: 13)).foregroundColor(.white).multilineTextAlignment(.center).padding(.horizontal, 12) }
                            .frame(maxWidth: .infinity, minHeight: 150).background(Color(hex: 0x0A5A8C)).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous)).padding(.top, 14)
                    }
                    ForEach(Array(mailBlocks(text).enumerated()), id: \.offset) { _, b in
                        if b.0 == "p" { Text(b.1.joined(separator: "\n")).font(.system(size: 15)).foregroundColor(ink).lineSpacing(4).padding(.top, 8) }
                        else { VStack(alignment: .leading, spacing: 2) { ForEach(Array(b.1.enumerated()), id: \.offset) { _, l in HStack(alignment: .top, spacing: 8) { Text("•"); Text(l) }.font(.system(size: 15)).foregroundColor(ink) } }.padding(.top, 8).padding(.leading, 6) }
                    }
                    Text((by.isEmpty ? "" : "\(by), ") + "RAFIKI Outreach").font(.system(size: 15)).foregroundColor(ink).padding(.top, 8)
                    if let cta = button ?? (donor && link ? "See your projects, map and photos" : nil) {
                        Text("\(cta)  →").font(.system(size: 15, weight: .bold)).foregroundColor(.white).multilineTextAlignment(.center).frame(maxWidth: .infinity).padding(.vertical, 13).background(Capsule().fill(fg)).padding(.top, 16)
                    }
                    if donor { Text(button != nil && link ? "Your private project page: open it here. Please don’t forward it." : "Questions? Just reply to this e\u{2011}mail.").font(.system(size: 12)).foregroundColor(grey).multilineTextAlignment(.center).frame(maxWidth: .infinity).padding(.top, 10) }
                }.padding(18)
            }.background(Color.white).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            Text(donor ? "You get this because you support RAFIKI Outreach projects. Unsubscribe" : "You get this because you are on the RAFIKI Pin team.").font(.system(size: 11)).foregroundColor(Color(hex: 0x6B7680)).multilineTextAlignment(.center).padding(.vertical, 10)
            VStack(spacing: 4) {
                Text("1817 N. Beck Street, Salt Lake City, UT 84116, U.S.A.").font(.system(size: 11)).foregroundColor(Color(hex: 0xC9D6E2)).multilineTextAlignment(.center)
                Text("info@rafikioutreach.org").font(.system(size: 11, weight: .semibold)).foregroundColor(Color(hex: 0x8FD3FF))
            }.frame(maxWidth: .infinity).padding(16).background(navy).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }.padding(10).background(Color(hex: 0xEEF2F6)).clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous)).padding(.top, 14)
    }
}

import SwiftUI
import UIKit

// Office › Donors: private donor pages (same server actions as the web and Android apps, see lib/donor.js). Each donor
// gets a private link to a page with their projects, an exact-location map and updates; group donors (a church, a
// company) can have several viewers, each with their own link.

struct DonorViewer: Equatable { var vid: String; var name: String; var email: String; var primary: Bool; var lastOpen: String; var active: Bool; var code = "" }
struct Donor: Equatable {
    var id: String; var name: String; var email: String; var org: String; var projects: [String]; var showPhotos: Bool; var photos: [String: String]
    var digest: Bool; var lastOpen: String; var opens: Int; var revoked: Bool; var viewers: [DonorViewer]
}
private extension Dictionary where Key == String, Value == Any { func ds(_ k: String) -> String { let v = str(k); return v == "null" ? "" : v } }
func parseDonors(_ j: JSON) -> [Donor] {
    j.objs("donors").map { d in
        Donor(id: d.ds("id"), name: d.ds("name"), email: d.ds("email"), org: d.ds("org"), projects: ((d["projects"] as? [Any]) ?? []).map { "\($0)" }, showPhotos: d["showPhotos"] == nil ? true : d.bool("showPhotos"),
              photos: (d.obj("photos") ?? [:]).reduce(into: [:]) { $0[$1.key] = "\($1.value)" }, digest: d["digest"] == nil ? true : d.bool("digest"), lastOpen: d.ds("lastOpen"), opens: d.int("opens"), revoked: d.bool("revoked"),
              viewers: d.objs("viewers").map { v in DonorViewer(vid: v.ds("vid"), name: v.ds("name"), email: v.ds("email"), primary: v.bool("primary"), lastOpen: v.ds("lastOpen"), active: v["active"] == nil ? true : v.bool("active"), code: v.ds("code")) })
    }
}
/// water · school · health · children · other (as on the web)
func donorCat(_ type: String) -> String {
    let x = type.lowercased()
    if x.contains("borehole") || x.contains("collection") { return "water" }
    if x.contains("school") { return "school" }
    if x.contains("health") { return "health" }
    if x.contains("orphan") { return "children" }
    return "other"
}
/// Visit photos of schools, clinics and children's homes stay hidden from donors until an admin ticks "photos OK".
func donorSensitive(_ type: String) -> Bool { ["school", "health", "children"].contains(donorCat(type)) }
func pickProjects(_ all: [Project], _ q: String, _ ck: String, _ cat: String, _ ticked: Set<String>, _ only: Bool) -> [Project] {
    let s = q.trimmingCharacters(in: .whitespaces).lowercased()
    return all.filter { p in (!only || ticked.contains(p.id)) && (ck.isEmpty || p.ck == ck) && (cat.isEmpty || donorCat(p.type) == cat) && (s.isEmpty || "\(p.title) \(p.area) \(p.country)".lowercased().contains(s)) }
        .sorted { ticked.contains($0.id) != ticked.contains($1.id) ? ticked.contains($0.id) : $0.title < $1.title }
}
func donorShareText(_ name: String, _ link: String) -> String {
    "Hello " + (name.split(separator: " ").first.map(String.init) ?? "") + ", here is your private RAFIKI Outreach page with the projects you funded: photos, visit reports and a live map. It’s just for you, so please don’t forward it.\n" + link
}
let DONOR_APK = "https://pin.rafikioutreach.org/app/rafiki-pin.apk"
let DONOR_WEB_APP = "https://pin.rafikioutreach.org/pin"
func donorInviteText(_ name: String, _ code: String) -> String {
    "Hello " + (name.split(separator: " ").first.map(String.init) ?? "") + ", you can now follow the projects you funded in the RAFIKI Pin app: photos, visits and thank-you messages from the communities.\n\n" +
        "Android: download the app from \(DONOR_APK), open it and tap “I’m a donor”.\n" +
        "iPhone: open \(DONOR_WEB_APP), tap “I’m a donor”, then Share › Add to Home Screen.\n\n" +
        "Your donor code: \(code)\nIt’s just for you, so please don’t share it."
}
func donorState(_ d: Donor) -> String { d.revoked ? "revoked" : !d.lastOpen.isEmpty ? "opened" : "not" }
private func donorPill(_ d: Donor) -> (String, String) {
    switch donorState(d) { case "revoked": return ("off", t("Link revoked", "Kiungo kimezimwa")); case "opened": return ("ok", t("Opened their page", "Wamefungua ukurasa")); default: return ("has", t("Not opened yet", "Bado hawajafungua")) }
}
private func donorProjects(_ n: Int) -> String { n == 1 ? t("1 project", "mradi 1") : t("\(n) projects", "miradi \(n)") }
private func copyText(_ s: String) { UIPasteboard.general.string = s; Haptic.ok() }

/// Office › Donors: numbers and filters, a card per donor that opens a sheet with every action, "Add a donor" as a sheet,
/// and the e-mail digest folded away.
struct DonorsSection: View {
    @EnvironmentObject var m: AppModel
    let s: Snapshot
    @State private var tick = 0
    @State private var list: [Donor]?
    @State private var mailReady = false
    @State private var loadMsg: String?
    @State private var filter = "all"
    @State private var more = false
    @State private var picked: String?
    @State private var openName: String?
    @State private var shown: [String]?
    @State private var form: String?
    @State private var msg: (String, String)?
    var body: some View {
        let L = (list ?? []).sorted { a, b in
            if a.revoked != b.revoked { return !a.revoked }
            if a.lastOpen.isEmpty != b.lastOpen.isEmpty { return !a.lastOpen.isEmpty }
            if a.lastOpen != b.lastOpen { return a.lastOpen > b.lastOpen }
            return a.name < b.name
        }
        let count = Dictionary(grouping: L, by: donorState).mapValues(\.count)
        let shownL = L.filter { filter == "all" || donorState($0) == filter }
        VStack(alignment: .leading, spacing: 0) {
            DqmHead(icon: "star", hue: "coral", title: t("Donors", "Wafadhili"), line: t("Each donor gets a private page with their projects, a map and updates.", "Kila mfadhili anapata ukurasa wa siri wenye miradi yake, ramani na taarifa."),
                    nums: [DqmNum(n: L.count, label: t("donors", "wafadhili"), key: "all"), DqmNum(n: count["opened"] ?? 0, label: t("opened", "wamefungua"), tone: (count["opened"] ?? 0) > 0 ? "ok" : "off", key: "opened"),
                           DqmNum(n: count["not"] ?? 0, label: t("not opened", "hawajafungua"), tone: (count["not"] ?? 0) > 0 ? "has" : "off", key: "not")],
                    picked: filter, onNum: { filter = $0; more = false }) {
                Btn(t("Add a donor", "Ongeza mfadhili"), icon: "plus", wide: true) { form = "new" }.padding(.top, 14)
                if !L.isEmpty { Btn(mailNb(t("E-mail donors", "Tuma barua pepe")), icon: "mail", light: true, wide: true) { MailNav.preset = MailPreset(aud: "donors"); m.openOffice("mail") }.padding(.top, 8) }
            }
            if let loadMsg { Msg(loadMsg, "bad") }
            if let msg { Msg(msg.1, msg.0) }
            if list != nil && !L.isEmpty {
                DqmChips(items: [("all", t("All", "Wote"), L.count), ("opened", t("Opened", "Wamefungua"), count["opened"] ?? 0), ("not", t("Not opened", "Hawajafungua"), count["not"] ?? 0), ("revoked", t("Revoked", "Vimezimwa"), count["revoked"] ?? 0)], picked: filter) { filter = $0; more = false }
            }
            if list != nil && L.isEmpty { EmptyDrop(text: t("No donors yet. Tap “Add a donor” to make the first private page.", "Bado hakuna wafadhili. Gusa “Ongeza mfadhili” kutengeneza ukurasa wa kwanza wa siri.")) }
            else if list != nil && shownL.isEmpty { EmptyDrop(text: filter == "revoked" ? t("No revoked links.", "Hakuna viungo vilivyozimwa.") : filter == "not" ? t("Everyone has opened their page.", "Wote wamefungua ukurasa wao.") : t("Nobody has opened their page yet.", "Bado hakuna aliyefungua ukurasa wake.")) }
            ForEach(Array(shownL.prefix(more ? shownL.count : 30).enumerated()), id: \.element.id) { i, d in
                let hu = C.named(nameHue(d.name))
                DqmCard(title: d.name, pill: donorPill(d),
                        lines: [(!d.org.isEmpty && d.org != d.name ? d.org : d.email).isEmpty ? t("No e-mail", "Hakuna barua pepe") : (!d.org.isEmpty && d.org != d.name ? d.org : d.email),
                                donorProjects(d.projects.count) + (d.viewers.count > 1 ? t(" · \(d.viewers.count) people", " · watu \(d.viewers.count)") : "") + (!d.showPhotos ? t(" · photos off", " · picha zimezimwa") : "")],
                        time: d.lastOpen.isEmpty ? "" : smsAgo(d.lastOpen), dim: d.revoked, lead: { DqmAvatar(name: d.name, bg: hu.bg, fg: hu.fg) }) { picked = d.id }
                    .padding(.top, 10).rise(min(i, 6))
            }
            if shownL.count > 30 && !more { Btn(t("Show more", "Onyesha zaidi") + " (\(shownL.count - 30))", light: true, small: true, wide: true) { more = true }.padding(.top, 12) }
            Text(t("Settings", "Mipangilio")).font(.tMedium).foregroundColor(C.ink).padding(.top, 22).padding(.bottom, 8)
            CardV(pad: 4) {
                DqmFold(icon: "mail", hue: "teal", title: t("E-mail digest", "Barua pepe ya kila mwezi"), summary: mailReady ? t("On · each donor, on the 1st of the month", "Imewashwa · kila mfadhili, tarehe 1 ya mwezi") : t("Off", "Imezimwa"), tone: mailReady ? "ok" : "off") {
                    Text(mailReady ? t("On: each donor with an e-mail gets a monthly update on the 1st.", "Imewashwa: kila mfadhili mwenye barua pepe anapata taarifa ya mwezi tarehe 1.") : t("Off. Sending e-mail isn’t set up yet; whoever looks after the website can switch it on.", "Imezimwa. Kutuma barua pepe bado hakujawekwa; anayesimamia tovuti anaweza kuiwasha.")).font(.bMedium).foregroundColor(C.text)
                    if !mailReady { TechDetails(names: [("RESEND_API_KEY", false), ("ALERT_FROM", false)]) }
                    Text(t("To stop it for one donor, open them, tap Edit and untick “Monthly e-mail update”.", "Kuizima kwa mfadhili mmoja, mfungue, gusa Hariri na uondoe tiki “Taarifa ya mwezi kwa barua pepe”.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 8)
                }
                Rectangle().fill(C.line).frame(height: 1).padding(.horizontal, 14)
                DqmFold(icon: "info", hue: "blue", title: t("How donor pages work", "Jinsi kurasa za wafadhili zinavyofanya kazi"), summary: t("A private link, no password", "Kiungo cha siri, bila nenosiri"), tone: "has") {
                    Text(t("Each donor gets a private link to a page with their projects, an exact-location map, visit reports, repairs, story moments and photos. No password: anyone with the link can see it, so send it only to them.",
                           "Kila mfadhili anapata kiungo cha siri cha ukurasa wenye miradi yake, ramani ya mahali kamili, ripoti za ziara, matengenezo, hadithi na picha. Hakuna nenosiri: yeyote mwenye kiungo anaweza kuuona, kwa hiyo mtumie yeye tu.")).font(.bMedium).foregroundColor(C.text)
                    Text(t("A church or company can have several people, each with their own link, so you can switch one off without affecting the others.", "Kanisa au kampuni inaweza kuwa na watu kadhaa, kila mmoja na kiungo chake, ili uweze kuzima kimoja bila kuathiri wengine.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 8)
                    Text(t("Visit photos of water points are shown. Schools, clinics and children’s homes stay hidden until you tick “photos OK” (children’s faces need consent).", "Picha za ziara za vituo vya maji zinaonyeshwa. Shule, zahanati na makao ya watoto zinafichwa hadi uchague “picha sawa” (nyuso za watoto zinahitaji ridhaa).")).font(.bSmall).foregroundColor(C.muted).padding(.top, 8)
                }
            }
        }
        .task(id: tick) {
            let r = await m.api.adminGet("donors")
            if r.ok {
                list = parseDonors(r.json); mailReady = (r.json.obj("mail") ?? [:]).bool("ready"); loadMsg = nil
                if let n = openName, let d = list?.last(where: { $0.name == n }) { picked = d.id; openName = nil }
            } else { loadMsg = reasonText(r.reason) }
        }
        .sheet(item: Binding(get: { picked.map { WhoBox(name: $0) } }, set: { picked = $0?.name })) { b in
            if let d = list?.first(where: { $0.id == b.name }) {
                DonorSheet(d: d, link: shown.flatMap { $0[0] == d.id || $0[0] == d.name ? $0 : nil }, setLink: { shown = $0 }, changed: { tick += 1 }, edit: { picked = nil; DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { form = d.id } }).environmentObject(m)
            }
        }
        .sheet(item: Binding(get: { form.map { WhoBox(name: $0) } }, set: { form = $0?.name })) { b in
            DonorForm(s: s, editing: list?.first { $0.id == b.name }) { who, link, email in
                if let link { shown = [who, who, link, email]; openName = who; msg = ("ok", t("Added ✓ Send them their private link.", "Imeongezwa ✓ Mtumie kiungo chake cha siri.")) }
                else { msg = ("ok", t("Saved ✓", "Imehifadhiwa ✓")); picked = b.name }
                tick += 1
            }.environmentObject(m)
        }
    }
}

/// Add or edit a donor: their details, the projects they funded, photos and the monthly e-mail.
private struct DonorForm: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.dismiss) private var dismiss
    let s: Snapshot; let editing: Donor?; let saved: (String, String?, String) -> Void
    @State private var name = ""
    @State private var email = ""
    @State private var org = ""
    @State private var ticked: [String] = []
    @State private var photosOk: [String] = []
    @State private var showPhotos = true
    @State private var digest = true
    @State private var q = ""
    @State private var ck = ""
    @State private var cat = ""
    @State private var only = false
    @State private var busy = false
    @State private var msg: (String, String)?
    @State private var ready = false
    var body: some View {
        let shownP = pickProjects(s.projects, q, ck, cat, Set(ticked), only)
        let sens = ticked.compactMap { id in s.projects.first { $0.id == id } }.filter { donorSensitive($0.type) }
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                DqmSheetHead(title: editing?.name ?? t("Add a donor", "Ongeza mfadhili"), sub: [editing != nil ? t("Change their details, projects and photos.", "Badilisha maelezo yao, miradi na picha.") : t("They get a private link to their own page. You can copy it again, replace it or switch it off at any time.", "Wanapata kiungo cha siri cha ukurasa wao. Unaweza kukinakili tena, kukibadilisha au kukizima wakati wowote.")], close: { dismiss() }) {
                    IconTile(icon: editing != nil ? "edit" : "star", tint: C.blue2, bg: C.tint, size: 64)
                }
                Field(t("Name", "Jina"), $name, placeholder: t("e.g. Grace Community Church", "mf. Kanisa la Grace"))
                Field(t("E-mail", "Barua pepe"), $email, placeholder: t("for the monthly update", "kwa taarifa ya mwezi")).keyboardType(.emailAddress).textInputAutocapitalization(.never)
                Field(t("Organisation (optional)", "Shirika (si lazima)"), $org, placeholder: t("e.g. a church or company", "mf. kanisa au kampuni"))
                HStack { Text(t("Projects they funded", "Miradi waliyofadhili")).font(.tSmall).foregroundColor(C.ink); Spacer(); Tag("\(ticked.count) " + t("ticked", "zimechaguliwa"), bg: C.tint, fg: C.deep) }.padding(.top, 18)
                Field(t("Search", "Tafuta"), $q, placeholder: t("Name or place", "Jina au mahali"))
                HStack(alignment: .bottom, spacing: 8) {
                    Picker1(label: t("Country", "Nchi"), value: ck, options: [("", t("All countries", "Nchi zote"))] + COUNTRIES) { ck = $0 }
                    Picker1(label: t("Type", "Aina"), value: cat, options: [("", t("All types", "Aina zote")), ("water", t("Water", "Maji")), ("school", t("Schools", "Shule")), ("health", t("Health", "Afya")), ("children", t("Children’s homes", "Makao ya watoto")), ("other", t("Other", "Nyingine"))]) { cat = $0 }
                }
                Check(label: t("Ticked only", "Zilizochaguliwa tu"), on: only) { only.toggle() }.padding(.top, 6)
                VStack(spacing: 0) {
                    ForEach(Array(shownP.prefix(40).enumerated()), id: \.element.id) { k, p in
                        let on = ticked.contains(p.id)
                        if k > 0 { Rectangle().fill(C.line).frame(height: 1) }
                        Button { if on { ticked.removeAll { $0 == p.id } } else { ticked.append(p.id) } } label: {
                            HStack(spacing: 8) {
                                Image(systemName: on ? "checkmark.square.fill" : "square").font(.system(size: 22)).foregroundColor(on ? C.blue : C.muted)
                                VStack(alignment: .leading, spacing: 1) { Text(p.title).font(.mont(14, .bold)).foregroundColor(C.ink).multilineTextAlignment(.leading); Text([p.type, p.area, p.country].filter { !$0.isEmpty }.joined(separator: " · ")).font(.bSmall).foregroundColor(C.muted) }
                                Spacer(minLength: 0)
                            }.padding(.horizontal, 10).padding(.vertical, 4).frame(minHeight: 56).contentShape(Rectangle())
                        }.buttonStyle(.plain)
                    }
                    if shownP.isEmpty { Text(t("No projects match.", "Hakuna mradi unaolingana.")).foregroundColor(C.muted).padding(12) }
                    if shownP.count > 40 { Text(t("…and \(shownP.count - 40) more. Search to narrow it down.", "…na \(shownP.count - 40) zaidi. Tafuta ili kupunguza.")).font(.bSmall).foregroundColor(C.muted).padding(12) }
                }.overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(C.line, lineWidth: 1)).padding(.top, 4)
                HStack(spacing: 8) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(t("Show visit photos", "Onyesha picha za ziara")).font(.tSmall).foregroundColor(C.ink)
                        Text(t("Water points: shown. Schools, clinics and children’s homes: hidden until you tick “photos OK” (children’s faces need consent).", "Vituo vya maji: zinaonyeshwa. Shule, zahanati na makao ya watoto: zimefichwa hadi uchague “picha sawa” (nyuso za watoto zinahitaji ridhaa).")).font(.bSmall).foregroundColor(C.muted)
                    }
                    Toggle("", isOn: $showPhotos).labelsHidden().tint(C.ok)
                }.padding(12).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).padding(.top, 14)
                if showPhotos && !sens.isEmpty {
                    Text(t("Photos OK? (consent checked)", "Picha sawa? (ridhaa imethibitishwa)")).font(.lLarge).foregroundColor(C.ink).padding(.top, 10)
                    ForEach(sens) { p in Check(label: p.title, on: photosOk.contains(p.id)) { if photosOk.contains(p.id) { photosOk.removeAll { $0 == p.id } } else { photosOk.append(p.id) } } }
                }
                Check(label: t("Monthly e-mail update", "Taarifa ya mwezi kwa barua pepe"), on: digest) { digest.toggle() }.padding(.top, 6)
                Btn(editing != nil ? t("Save changes", "Hifadhi mabadiliko") : t("Add and make private link", "Ongeza na utengeneze kiungo cha siri"), icon: editing != nil ? "check" : "plus",
                    enabled: !name.trimmingCharacters(in: .whitespaces).isEmpty && !busy, wide: true) { save() }.padding(.top, 14)
                DqmLineBtn(text: t("Cancel", "Ghairi"), icon: "x") { dismiss() }.padding(.top, 8)
                if let msg { Msg(msg.1, msg.0) }
            }.padding(.horizontal, 20).padding(.top, 20).padding(.bottom, 28)
        }
        .background(C.card.ignoresSafeArea())
        .onAppear {
            guard !ready else { return }; ready = true
            if let d = editing { name = d.name; email = d.email; org = d.org; ticked = d.projects; photosOk = d.photos.filter { $0.value == "on" }.map(\.key); showPhotos = d.showPhotos; digest = d.digest; only = true }
        }
    }
    private func save() {
        busy = true; msg = ("", t("Saving…", "Inahifadhi…"))
        Task {
            var ph: JSON = [:]
            for id in photosOk where ticked.contains(id) { ph[id] = "on" }
            for (k, v) in editing?.photos ?? [:] where v == "off" && ticked.contains(k) { ph[k] = "off" }
            var b: JSON = ["action": editing != nil ? "donor-update" : "donor-add", "name": name.trimmingCharacters(in: .whitespaces), "email": email.trimmingCharacters(in: .whitespaces), "org": org.trimmingCharacters(in: .whitespaces),
                           "projects": ticked, "showPhotos": showPhotos, "photos": ph, "digest": digest]
            if let e = editing { b["id"] = e.id }
            let r = await m.api.admin(b)
            busy = false
            if !r.ok { msg = ("bad", reasonText(r.reason)); return }
            Haptic.ok(); dismiss()
            saved(name.trimmingCharacters(in: .whitespaces), editing == nil ? r.json.str("link") : nil, email.trimmingCharacters(in: .whitespaces))
        }
    }
}

/// The app code next to "Copy private link": the code, Copy, and Send app invite.
private struct DonorCodeRow: View {
    let name: String; let code: String; let said: (String) -> Void
    var body: some View {
        if !code.isEmpty {
            HStack {
                VStack(alignment: .leading, spacing: 1) {
                    Text(t("App code", "Msimbo wa programu")).font(.mont(12, .semibold)).foregroundColor(C.muted)
                    Text(code).font(.system(size: 16, weight: .semibold, design: .monospaced)).foregroundColor(C.ink).lineLimit(1)
                }
                Spacer()
                Btn(t("Copy", "Nakili"), icon: "copy", light: true, small: true) { copyText(code); said(t("Code copied ✓", "Msimbo umenakiliwa ✓")) }
            }.padding(.leading, 14).padding(.trailing, 6).padding(.vertical, 6).background(C.card).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.line, lineWidth: 1))
            DqmLineBtn(text: t("Send app invite", "Tuma mwaliko wa programu"), icon: "star") { Share.items([donorInviteText(name, code)]) }
        }
    }
}

/// One donor: the private link (copy, share, rotate, revoke), edit, who can open the page, delete.
private struct DonorSheet: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.dismiss) private var dismiss
    let d: Donor; let link: [String]?; let setLink: ([String]?) -> Void; let changed: () -> Void; let edit: () -> Void
    @State private var msg: (String, String)?
    @State private var ask: String?
    @State private var askMsg: String?
    @State private var busy = false
    @State private var vName = ""
    @State private var vEmail = ""
    @State private var viewer: String?
    @State private var localLink: [String]?
    var body: some View {
        let lk = localLink ?? link
        let pill = donorPill(d), (fg, bg) = dqmTone(pill.0)
        let pv = d.viewers.first { $0.primary } ?? d.viewers.first
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                DqmSheetHead(title: d.name, sub: [!d.org.isEmpty && d.org != d.name ? d.org : "", d.email], pill: pill, close: { dismiss() }) { IconTile(icon: "star", tint: fg, bg: bg, size: 64) }
                if let lk, lk.count >= 3 {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(t("Private link for ", "Kiungo cha siri cha ") + lk[1]).font(.tSmall).foregroundColor(C.ok)
                        Text(lk[2].replacingOccurrences(of: "/donor/", with: "/donor/\n")).font(.system(size: 11, design: .monospaced)).foregroundColor(C.ink).padding(10).frame(maxWidth: .infinity, alignment: .leading).background(C.card).clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous)).padding(.vertical, 8)
                        Text(t("Anyone with this link can see the page. Send it only to them.", "Yeyote mwenye kiungo hiki anaweza kuona ukurasa. Mtumie yeye tu.")).font(.bSmall).foregroundColor(C.text)
                        HStack(spacing: 8) {
                            Btn(t("Copy", "Nakili"), icon: "copy", light: true, small: true, wide: true) { copyText(lk[2]); msg = ("ok", t("Link copied ✓", "Kiungo kimenakiliwa ✓")) }
                            Btn(t("Share", "Shiriki"), icon: "share", light: true, small: true, wide: true) { Share.items([donorShareText(lk[1], lk[2])]) }
                        }.padding(.top, 10)
                    }.padding(14).background(C.okBg).clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous)).padding(.top, 16)
                }
                VStack(spacing: 8) {
                    if !d.revoked && lk != nil {
                        if let pv { DonorCodeRow(name: pv.name.isEmpty ? d.name : pv.name, code: pv.code) { msg = ("ok", $0) } }
                        DqmLineBtn(text: t("Edit", "Hariri"), icon: "edit") { dismiss(); edit() }
                    } else if !d.revoked {
                        Btn(t("Copy private link", "Nakili kiungo cha siri"), icon: "copy", enabled: !busy, wide: true) { act("donor-link") { j in copyText(j.str("link")); msg = ("ok", t("Link copied ✓", "Kiungo kimenakiliwa ✓")) } }
                        if let pv { DonorCodeRow(name: pv.name.isEmpty ? d.name : pv.name, code: pv.code) { msg = ("ok", $0) } }
                        HStack(spacing: 8) {
                            DqmLineBtn(text: t("Share link", "Shiriki kiungo"), icon: "share") { act("donor-link") { j in let s = [d.id, d.name, j.str("link"), d.email]; localLink = s; setLink(s); Share.items([donorShareText(d.name, j.str("link"))]) } }
                            DqmLineBtn(text: t("Edit", "Hariri"), icon: "edit") { dismiss(); edit() }
                        }
                    } else {
                        Btn(t("Make a new link", "Tengeneza kiungo kipya"), icon: "link", enabled: !busy, wide: true) { act("donor-rotate") { j in let s = [d.id, d.name, j.str("link"), d.email]; localLink = s; setLink(s); msg = ("ok", t("New link made ✓", "Kiungo kipya kimetengenezwa ✓")); changed() } }
                        DqmLineBtn(text: t("Edit", "Hariri"), icon: "edit") { dismiss(); edit() }
                    }
                }.padding(.top, 16)
                DqmLineBtn(text: mailNb(t("E-mail", "Barua pepe")), icon: "mail") { dismiss(); MailNav.preset = MailPreset(aud: "donors", sub: "pick", picks: [d.id]); m.openOffice("mail") }.padding(.top, 8)
                if let msg { Msg(msg.1, msg.0) }
                let ps = d.projects.map { id in m.snap?.projects.first { $0.id == id }?.title ?? id }
                VStack(spacing: 0) {
                    DqmFact(label: t("Projects", "Miradi"), value: donorProjects(ps.count), sub: ps.prefix(3).joined(separator: ", ") + (ps.count > 3 ? " …" : ""))
                    DqmFact(label: t("Last opened", "Ilifunguliwa mwisho"), value: d.lastOpen.isEmpty ? t("Not yet", "Bado") : Dates.dateTime(d.lastOpen), sub: d.opens > 1 ? t("\(d.opens) times", "mara \(d.opens)") : "")
                    DqmFact(label: t("Visit photos", "Picha za ziara"), value: d.showPhotos ? t("Shown", "Zinaonyeshwa") : t("Hidden", "Zimefichwa"))
                    DqmFact(label: t("Monthly e-mail", "Barua pepe ya mwezi"), value: d.digest ? t("On", "Imewashwa") : t("Off", "Imezimwa"))
                }.padding(.top, 12)
                VStack(spacing: 0) {
                    DqmFold(icon: "people", hue: "purple", title: t("Who can open this page", "Nani anaweza kufungua ukurasa huu"), summary: d.viewers.count == 1 ? t("1 person", "mtu 1") : t("\(d.viewers.count) people", "watu \(d.viewers.count)"), tone: d.viewers.count > 1 ? "has" : "off") {
                        Text(t("For a church or company: give each person their own link, so you can switch one off without affecting the others.", "Kwa kanisa au kampuni: mpe kila mtu kiungo chake, ili uweze kuzima kimoja bila kuathiri wengine.")).font(.bSmall).foregroundColor(C.muted)
                        ForEach(d.viewers, id: \.vid) { v in
                            let who = v.name.isEmpty ? d.name : v.name
                            Button { viewer = v.vid } label: {
                                HStack(spacing: 10) {
                                    PersonAvatar(name: who, size: 38)
                                    VStack(alignment: .leading, spacing: 1) {
                                        Text(who + (v.primary ? t(" (main)", " (mkuu)") : "")).font(.mont(14, .bold)).foregroundColor(C.ink)
                                        Text([!v.active ? t("Revoked", "Kimezimwa") : "", v.email, !v.lastOpen.isEmpty ? t("opened ", "kimefunguliwa ") + Dates.dateTime(v.lastOpen) : t("not opened yet", "bado hakijafunguliwa")].filter { !$0.isEmpty }.joined(separator: " · ")).font(.bSmall).foregroundColor(!v.active ? C.bad : C.muted)
                                    }
                                    Spacer(minLength: 0); Icon("chev", size: 18, color: C.muted)
                                }.padding(.horizontal, 10).padding(.vertical, 8).frame(minHeight: 56).overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(C.line, lineWidth: 1)).contentShape(Rectangle())
                            }.buttonStyle(.plain).padding(.top, 8)
                        }
                        Field(t("Add a person: name", "Ongeza mtu: jina"), $vName, placeholder: t("e.g. Pastor John", "mf. Mchungaji John"))
                        Field(t("Their e-mail (optional)", "Barua pepe (si lazima)"), $vEmail).keyboardType(.emailAddress).textInputAutocapitalization(.never)
                        Btn(t("Add and make their link", "Ongeza na utengeneze kiungo chake"), icon: "plus", enabled: !vName.trimmingCharacters(in: .whitespaces).isEmpty && !busy, small: true, wide: true) {
                            busy = true
                            Task {
                                let r = await m.api.admin(["action": "donor-viewer-add", "id": d.id, "name": vName.trimmingCharacters(in: .whitespaces), "email": vEmail.trimmingCharacters(in: .whitespaces)])
                                busy = false
                                if r.ok { let s = [d.id, vName.trimmingCharacters(in: .whitespaces), r.json.str("link"), vEmail]; localLink = s; setLink(s); vName = ""; vEmail = ""; changed() } else { msg = ("bad", reasonText(r.reason)) }
                            }
                        }.padding(.top, 10)
                    }
                }.overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(C.line, lineWidth: 1)).padding(.top, 14)
                Rectangle().fill(C.line).frame(height: 1).padding(.top, 16)
                VStack(spacing: 8) {
                    if !d.revoked {
                        DqmLineBtn(text: t("Rotate link", "Badilisha kiungo"), icon: "refresh") { ask = "rotate"; askMsg = nil }
                        DqmLineBtn(text: t("Revoke the link", "Zima kiungo"), icon: "x", red: true) { ask = "revoke"; askMsg = nil }
                    }
                    DqmLineBtn(text: t("Delete donor", "Futa mfadhili"), icon: "trash", red: true) { ask = "delete"; askMsg = nil }
                }.padding(.top, 14)
                if let a = ask {
                    DqmAsk(question: a == "revoke" ? t("Revoke the link for \(d.name)? Their page stops working straight away. You can make a new link later.", "Zima kiungo cha \(d.name)? Ukurasa wao utaacha kufanya kazi mara moja. Unaweza kutengeneza kiungo kipya baadaye.")
                                : a == "rotate" ? t("Make a new link for \(d.name)? The old link stops working, so send them the new one.", "Tengeneza kiungo kipya cha \(d.name)? Kiungo cha zamani kitaacha kufanya kazi, kwa hiyo watumie kipya.")
                                : t("Delete \(d.name)? Their links stop working straight away.", "Futa \(d.name)? Viungo vyao vitaacha kufanya kazi mara moja."),
                           yes: a == "revoke" ? t("Revoke", "Zima") : a == "rotate" ? t("Make a new link", "Tengeneza kiungo kipya") : t("Delete", "Futa"), red: a != "rotate", busy: busy, msg: askMsg, no: { ask = nil }) {
                        switch a {
                        case "revoke": act("donor-revoke") { _ in ask = nil; localLink = nil; setLink(nil); msg = ("ok", t("Link revoked", "Kiungo kimezimwa")); changed() }
                        case "rotate": act("donor-rotate") { j in ask = nil; let s = [d.id, d.name, j.str("link"), d.email]; localLink = s; setLink(s); msg = ("ok", t("New link made ✓", "Kiungo kipya kimetengenezwa ✓")); changed() }
                        default: act("donor-remove") { _ in dismiss(); changed() }
                        }
                    }
                }
            }.padding(.horizontal, 20).padding(.top, 20).padding(.bottom, 28)
        }
        .background(C.card.ignoresSafeArea())
        .sheet(item: Binding(get: { viewer.map { WhoBox(name: $0) } }, set: { viewer = $0?.name })) { b in
            if let v = d.viewers.first(where: { $0.vid == b.name }) { ViewerSheet(d: d, v: v, changed: changed).environmentObject(m) }
        }
    }
    private func act(_ action: String, vid: String? = nil, _ after: @escaping (JSON) -> Void) {
        busy = true
        Task {
            var b: JSON = ["action": action, "id": d.id]
            if let vid { b["vid"] = vid }
            let r = await m.api.admin(b)
            busy = false
            if !r.ok {
                let why = r.reason == "revoked" ? t("This link was revoked. Make a new one.", "Kiungo hiki kimezimwa. Tengeneza kipya.") : r.reason == "rotate" ? t("Make a new link (Rotate) to copy it.", "Tengeneza kiungo kipya ili ukinakili.") : reasonText(r.reason)
                if ask != nil { askMsg = why } else { msg = ("bad", why) }
                return
            }
            after(r.json)
        }
    }
}

/// One person who can open a donor's page: contact, details and access.
private struct ViewerSheet: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.dismiss) private var dismiss
    let d: Donor; let v: DonorViewer; let changed: () -> Void
    @State private var msg: (String, String)?
    @State private var busy = false
    @State private var sub: String?
    @State private var link: String?
    var body: some View {
        let who = v.name.isEmpty ? d.name : v.name
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ZStack(alignment: .topTrailing) {
                    VStack(spacing: 0) {
                        PersonAvatar(name: who, size: 72)
                        Text(who).font(.tLarge).foregroundColor(C.ink).multilineTextAlignment(.center).padding(.top, 8)
                        if who != d.name { Text(d.name).font(.bMedium).foregroundColor(C.text) }
                        HStack(spacing: 6) { DqmPill(tone: "has", label: t("Donor", "Mfadhili")); if v.primary { DqmPill(tone: "off", label: t("Main", "Mkuu")) }; if !v.active { DqmPill(tone: "bad", label: t("Access blocked", "Ufikiaji umezuiwa")) } }.padding(.top, 8)
                        Text(!v.lastOpen.isEmpty ? t("Opened their page ", "Alifungua ukurasa ") + Dates.dateTime(v.lastOpen) : t("Hasn’t opened their page yet", "Bado hajafungua ukurasa wake")).font(.mont(12, .semibold)).foregroundColor(C.muted).padding(.top, 8)
                    }.frame(maxWidth: .infinity).padding(.horizontal, 36)
                    Button { dismiss() } label: { Icon("x", size: 20, color: C.ink).frame(width: 44, height: 44).background(Circle().fill(C.bg)) }.buttonStyle(.plain)
                }
                if !v.email.isEmpty {
                    HStack(spacing: 8) { Icon("mail", size: 16, color: C.muted); Text(v.email).font(.bMedium).foregroundColor(C.ink); Spacer(); Button { copyText(v.email); msg = ("ok", t("E-mail copied ✓", "Barua pepe imenakiliwa ✓")) } label: { Icon("copy", size: 19, color: C.blue).frame(width: 44, height: 44) }.buttonStyle(.plain) }.padding(.top, 8)
                }
                if let link {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(t("New private link for ", "Kiungo kipya cha siri cha ") + who).font(.tSmall).foregroundColor(C.ok)
                        Text(link.replacingOccurrences(of: "/donor/", with: "/donor/\n")).font(.system(size: 11, design: .monospaced)).foregroundColor(C.ink).padding(10).frame(maxWidth: .infinity, alignment: .leading).background(C.card).clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous)).padding(.vertical, 8)
                        HStack(spacing: 8) {
                            Btn(t("Copy", "Nakili"), icon: "copy", light: true, small: true, wide: true) { copyText(link); msg = ("ok", t("Link copied ✓", "Kiungo kimenakiliwa ✓")) }
                            Btn(t("Share", "Shiriki"), icon: "share", light: true, small: true, wide: true) { Share.items([donorShareText(who, link)]) }
                        }
                    }.padding(12).background(C.okBg).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).padding(.top, 12)
                }
                if let msg { Msg(msg.1, msg.0) }
                GroupLabel(t("Contact", "Mawasiliano"), top: 18)
                group {
                    if !v.email.isEmpty { row("mail", "teal", t("Send an e-mail", "Tuma barua pepe"), v.email) { dismiss(); MailNav.preset = MailPreset(aud: "pick", picks: [d.id + "." + v.vid]); m.openOffice("mail") } }
                    if v.active {
                        row("copy", "blue", t("Copy private link", "Nakili kiungo cha siri"), t("Their own page, no password", "Ukurasa wao, bila nenosiri")) { act("donor-link") { j in copyText(j.str("link")); msg = ("ok", t("Link copied ✓", "Kiungo kimenakiliwa ✓")) } }
                        row("share", "purple", t("Share link", "Shiriki kiungo"), t("WhatsApp, SMS or e-mail", "WhatsApp, SMS au barua pepe")) { act("donor-link") { j in Share.items([donorShareText(who, j.str("link"))]) } }
                        if !v.code.isEmpty {
                            HStack(spacing: 12) {
                                IconTile(icon: "qr", tint: C.named("slate").fg, bg: C.named("slate").bg, size: 36)
                                VStack(alignment: .leading, spacing: 1) { Text(t("App code", "Msimbo wa programu")).font(.tSmall).foregroundColor(C.ink); Text(v.code).font(.system(size: 14, design: .monospaced)).foregroundColor(C.ink).lineLimit(1) }
                                Spacer()
                                Btn(t("Copy", "Nakili"), icon: "copy", light: true, small: true) { copyText(v.code); msg = ("ok", t("Code copied ✓", "Msimbo umenakiliwa ✓")) }
                            }.padding(.horizontal, 10).padding(.vertical, 6).frame(minHeight: 56)
                            row("star", "coral", t("Send app invite", "Tuma mwaliko wa programu"), t("With their app code", "Pamoja na msimbo wao wa programu")) { Share.items([donorInviteText(who, v.code)]) }
                        }
                    }
                }
                GroupLabel(t("Details", "Maelezo"), top: 18)
                group {
                    row("edit", "blue", t("Name", "Jina"), who) { sub = "name" }
                    row("mail", "teal", v.email.isEmpty ? t("Add e-mail", "Ongeza barua pepe") : t("Change e-mail", "Badilisha barua pepe"), v.email.isEmpty ? t("None yet", "Bado hakuna") : v.email) { sub = "email" }
                    row("mail", "green", d.digest ? t("Stop monthly e-mails", "Simamisha barua pepe za mwezi") : t("Start monthly e-mails", "Anza barua pepe za mwezi"), (d.digest ? t("On", "Zimewashwa") : t("Off", "Zimezimwa")) + t(" · for everyone at \(d.name)", " · kwa wote wa \(d.name)")) { sub = "digest" }
                }
                GroupLabel(t("Access", "Ufikiaji"), top: 18)
                group {
                    row("refresh", "blue", t("New link", "Kiungo kipya"), t("The old link stops working", "Kiungo cha zamani kitaacha kufanya kazi")) { sub = "rotate" }
                    if v.active { row("x", "coral", t("Block access", "Zuia ufikiaji"), t("Their link stops working", "Kiungo chao kitaacha kufanya kazi"), red: true) { sub = "revoke" } }
                    else { row("check", "green", t("Restore access", "Rudisha ufikiaji"), t("Their link works again", "Kiungo chao kitafanya kazi tena")) { sub = "restore" } }
                    if !v.primary { row("trash", "coral", t("Remove", "Ondoa"), t("Takes them off this page", "Anaondolewa kwenye ukurasa huu"), red: true) { sub = "remove" } }
                }
            }.padding(.horizontal, 20).padding(.top, 20).padding(.bottom, 28)
        }
        .background(C.card.ignoresSafeArea())
        .sheet(item: Binding(get: { sub.map { WhoBox(name: $0) } }, set: { sub = $0?.name })) { b in
            if b.name == "name" || b.name == "email" { DvFieldSheet(d: d, v: v, field: b.name) { msg = ("ok", t("Saved ✓", "Imehifadhiwa ✓")); changed() }.environmentObject(m) }
            else {
                DvAskSheet(d: d, v: v, what: b.name) { j in
                    switch b.name {
                    case "rotate": link = j.str("link").isEmpty ? nil : j.str("link"); msg = ("ok", t("New link made ✓", "Kiungo kipya kimetengenezwa ✓"))
                    case "revoke": msg = ("ok", t("Access blocked", "Ufikiaji umezuiwa"))
                    case "restore": msg = ("ok", t("Access restored ✓", "Ufikiaji umerudishwa ✓"))
                    case "digest": msg = ("ok", t("Saved ✓", "Imehifadhiwa ✓"))
                    default: dismiss()
                    }
                    changed()
                }.environmentObject(m)
            }
        }
    }
    private func group<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(spacing: 0) { content() }.padding(.vertical, 4).padding(.horizontal, 2).overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(C.line, lineWidth: 1))
    }
    private func row(_ icon: String, _ hue: String, _ label: String, _ value: String, red: Bool = false, _ action: @escaping () -> Void) -> some View {
        let hu = C.named(hue)
        return Button(action: action) {
            HStack(spacing: 12) {
                IconTile(icon: icon, tint: red ? C.bad : hu.fg, bg: red ? C.badBg : hu.bg, size: 36)
                VStack(alignment: .leading, spacing: 1) { Text(label).font(.tSmall).foregroundColor(red ? C.bad : C.ink); if !value.isEmpty { Text(value).font(.bSmall).foregroundColor(C.muted).lineLimit(2) } }
                Spacer(minLength: 6); Icon("chev", size: 18, color: C.muted)
            }.padding(.horizontal, 10).padding(.vertical, 8).frame(minHeight: 56).contentShape(Rectangle())
        }.buttonStyle(.plain).disabled(busy)
    }
    private func act(_ action: String, _ after: @escaping (JSON) -> Void) {
        busy = true
        Task {
            let r = await m.api.admin(["action": action, "id": d.id, "vid": v.vid])
            busy = false
            if !r.ok { msg = ("bad", r.reason == "revoked" ? t("This link was revoked. Make a new one.", "Kiungo hiki kimezimwa. Tengeneza kipya.") : r.reason == "rotate" ? t("Make a new link to copy it.", "Tengeneza kiungo kipya ili ukinakili.") : reasonText(r.reason)); return }
            after(r.json)
        }
    }
}

/// Rename, or add or change their e-mail: one field and Save (donor-viewer-update).
private struct DvFieldSheet: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.dismiss) private var dismiss
    let d: Donor; let v: DonorViewer; let field: String; let saved: () -> Void
    @State private var x = ""
    @State private var busy = false
    @State private var msg: String?
    var body: some View {
        let start = field == "name" ? (v.name.isEmpty ? d.name : v.name) : v.email
        VStack(alignment: .leading, spacing: 0) {
            VStack(spacing: 10) {
                IconTile(icon: field == "name" ? "edit" : "mail", tint: C.named(field == "name" ? "blue" : "teal").fg, bg: C.named(field == "name" ? "blue" : "teal").bg, size: 56)
                Text(field == "name" ? t("Rename", "Badilisha jina") : start.isEmpty ? t("Add e-mail", "Ongeza barua pepe") : t("Change e-mail", "Badilisha barua pepe")).font(.tLarge).foregroundColor(C.ink)
            }.frame(maxWidth: .infinity)
            Field(field == "name" ? t("Name", "Jina") : t("E-mail", "Barua pepe"), Binding(get: { x }, set: { x = $0; msg = nil }), placeholder: field == "name" ? t("e.g. Pastor John", "mf. Mchungaji John") : t("for the monthly update", "kwa taarifa ya mwezi"))
            Btn(t("Save", "Hifadhi"), icon: "check", enabled: !busy && x.trimmingCharacters(in: .whitespaces) != start.trimmingCharacters(in: .whitespaces), wide: true) { save(x) }.padding(.top, 14)
            if field == "email" && !start.isEmpty { DqmLineBtn(text: t("Remove e-mail", "Ondoa barua pepe"), icon: "trash", red: true, enabled: !busy) { save("") }.padding(.top, 8) }
            Msg(msg, "bad")
            Spacer(minLength: 0)
        }
        .padding(20).background(C.card.ignoresSafeArea()).presentationDetents([.medium])
        .onAppear { x = start }
    }
    private func save(_ value: String) {
        let y = value.trimmingCharacters(in: .whitespaces)
        if field == "name" && y.isEmpty { msg = t("Type a name.", "Andika jina."); return }
        if field == "email" && !y.isEmpty && y.range(of: #"^[^@\s]+@[^@\s]+\.[^@\s]+$"#, options: .regularExpression) == nil { msg = t("That doesn’t look like an e-mail address.", "Hiyo haionekani kama anwani ya barua pepe."); return }
        busy = true
        Task {
            let r = await m.api.admin(["action": "donor-viewer-update", "id": d.id, "vid": v.vid, "name": field == "name" ? y : v.name, "email": field == "email" ? y : v.email])
            busy = false
            if r.ok { dismiss(); saved() } else { msg = reasonText(r.reason) }
        }
    }
}

/// New link, block or restore access, remove, monthly e-mails on or off: says what happens, then does it.
private struct DvAskSheet: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.dismiss) private var dismiss
    let d: Donor; let v: DonorViewer; let what: String; let done: (JSON) -> Void
    @State private var busy = false
    @State private var msg: String?
    var body: some View {
        let who = v.name.isEmpty ? d.name : v.name, red = what == "revoke" || what == "remove"
        let hu = C.named(what == "rotate" ? "blue" : what == "restore" || what == "digest" ? "green" : "coral")
        VStack(spacing: 0) {
            IconTile(icon: what == "rotate" ? "refresh" : what == "restore" ? "check" : what == "digest" ? "mail" : what == "remove" ? "trash" : "x", tint: red ? C.bad : hu.fg, bg: red ? C.badBg : hu.bg, size: 56)
            Text(what == "rotate" ? t("Make a new link for \(who)?", "Tengeneza kiungo kipya cha \(who)?") : what == "revoke" ? t("Block access for \(who)?", "Zuia ufikiaji wa \(who)?")
                 : what == "restore" ? t("Restore access for \(who)?", "Rudisha ufikiaji wa \(who)?") : what == "remove" ? t("Remove \(who)?", "Ondoa \(who)?")
                 : d.digest ? t("Stop the monthly e-mails?", "Simamisha barua pepe za mwezi?") : t("Start the monthly e-mails?", "Anza barua pepe za mwezi?")).font(.tLarge).foregroundColor(C.ink).multilineTextAlignment(.center).padding(.top, 10)
            Text(what == "rotate" ? t("Their old link stops working straight away, so send them the new one.", "Kiungo chao cha zamani kitaacha kufanya kazi mara moja, kwa hiyo watumie kipya.")
                 : what == "revoke" ? t("Their page stops opening straight away. You can restore it later.", "Ukurasa wao utaacha kufunguka mara moja. Unaweza kuurudisha baadaye.")
                 : what == "restore" ? t("Their link works again.", "Kiungo chao kitafanya kazi tena.")
                 : what == "remove" ? t("They can’t open the page any more. Others at \(d.name) keep their links.", "Hawezi kufungua ukurasa tena. Wengine wa \(d.name) wanabaki na viungo vyao.")
                 : t("This is for everyone at \(d.name): the update on the 1st of each month.", "Hii ni kwa wote wa \(d.name): taarifa ya tarehe 1 ya kila mwezi.")).font(.bMedium).foregroundColor(C.text).multilineTextAlignment(.center).padding(.top, 12)
            HStack(spacing: 8) {
                Btn(t("Cancel", "Ghairi"), light: true, small: true, wide: true) { dismiss() }
                Button { go() } label: {
                    Text(what == "rotate" ? t("Make a new link", "Tengeneza kiungo kipya") : what == "revoke" ? t("Block", "Zuia") : what == "restore" ? t("Restore", "Rudisha") : what == "remove" ? t("Remove", "Ondoa") : d.digest ? t("Stop", "Simamisha") : t("Start", "Anza"))
                        .font(.mont(14, .bold)).foregroundColor(.white).frame(maxWidth: .infinity, minHeight: 44).background(Group { if busy { C.off } else if red { C.bad } else { BlueBrush } }).clipShape(Capsule())
                }.buttonStyle(Press()).disabled(busy)
            }.padding(.top, 14)
            Msg(msg, "bad")
            Spacer(minLength: 0)
        }.padding(20).background(C.card.ignoresSafeArea()).presentationDetents([.medium])
    }
    private func go() {
        busy = true; msg = nil
        Task {
            let b: JSON
            switch what {
            case "rotate": b = ["action": "donor-rotate", "id": d.id, "vid": v.vid]
            case "revoke": b = ["action": "donor-revoke", "id": d.id, "vid": v.vid]
            case "restore": b = ["action": "donor-viewer-restore", "id": d.id, "vid": v.vid]
            case "remove": b = ["action": "donor-viewer-remove", "id": d.id, "vid": v.vid]
            default: b = ["action": "donor-update", "id": d.id, "name": d.name, "email": d.email, "org": d.org, "projects": d.projects, "showPhotos": d.showPhotos, "photos": d.photos, "digest": !d.digest]
            }
            let r = await m.api.admin(b)
            busy = false
            if r.ok { dismiss(); done(r.json) } else { msg = reasonText(r.reason) }
        }
    }
}

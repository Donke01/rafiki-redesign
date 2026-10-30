import AVFoundation
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

// Office › Donors (Android Donors.kt, 2.16 / 2.22): private donor pages. Each donor gets a private link to a page with
// their projects, an exact-location map and updates; group donors (a church, a company) can have several viewers, each
// with their own link and app code. Also the project's "Thank-you for donors" (a photo or ≤ 60 s video).

struct DonorViewer { let vid: String; let name: String; let email: String; let primary: Bool; let lastOpen: String; let active: Bool; let code: String }
struct DonorRec: Identifiable {
    let id: String; let name: String; let email: String; let org: String; let projects: [String]; let showPhotos: Bool; let photos: [String: String]
    let digest: Bool; let lastOpen: String; let opens: Int; let revoked: Bool; let viewers: [DonorViewer]
    var state: String { revoked ? "revoked" : !lastOpen.isEmpty ? "opened" : "not" }
    static func list(_ j: JSON) -> [DonorRec] {
        func s(_ o: JSON, _ k: String) -> String { let v = o.str(k); return v == "null" ? "" : v }
        return j.objs("donors").map { d in
            var ph: [String: String] = [:]; (d.obj("photos") ?? [:]).forEach { k, v in if let x = v as? String { ph[k] = x } }
            return DonorRec(id: s(d, "id"), name: s(d, "name"), email: s(d, "email"), org: s(d, "org"), projects: (d.arr("projects") ?? []).compactMap { $0 as? String },
                            showPhotos: d["showPhotos"] == nil ? true : d.bool("showPhotos"), photos: ph, digest: d["digest"] == nil ? true : d.bool("digest"),
                            lastOpen: s(d, "lastOpen"), opens: d.int("opens"), revoked: d.bool("revoked"),
                            viewers: d.objs("viewers").map { v in DonorViewer(vid: s(v, "vid"), name: s(v, "name"), email: s(v, "email"), primary: v.bool("primary"), lastOpen: s(v, "lastOpen"),
                                                                               active: v["active"] == nil ? true : v.bool("active"), code: s(v, "code")) })
        }
    }
}

/// water · school · health · children · other (as on the web)
func donorCat(_ type: String) -> String {
    let t = type.lowercased()
    if t.contains("borehole") || t.contains("collection") { return "water" }
    if t.contains("school") { return "school" }
    if t.contains("health") { return "health" }
    if t.contains("orphan") { return "children" }
    return "other"
}
/// Visit photos of schools, clinics and children's homes stay hidden from donors until an admin ticks "photos OK".
func donorSensitive(_ type: String) -> Bool { ["school", "health", "children"].contains(donorCat(type)) }
private func firstName(_ n: String) -> String { n.split(separator: " ").first.map(String.init) ?? n }
func donorShareText(_ name: String, _ link: String) -> String {
    "Hello " + firstName(name) + ", here is your private RAFIKI Outreach page with the projects you funded: photos, visit reports and a live map. It’s just for you, so please don’t forward it.\n" + link
}
/// The invite is in English: donors read it (most of them abroad).
func donorInviteText(_ name: String, _ code: String) -> String {
    "Hello " + firstName(name) + ", you can now follow the projects you funded in the RAFIKI Pin app: photos, visits and thank-you messages from the communities.\n\n" +
    "Android: download the app from https://pin.rafikioutreach.org/app/rafiki-pin.apk, open it and tap “I’m a donor”.\n" +
    "iPhone: open https://pin.rafikioutreach.org/pin, tap “I’m a donor”, then Share › Add to Home Screen.\n\n" +
    "Your donor code: \(code)\nIt’s just for you, so please don’t share it."
}
private func donorPill(_ d: DonorRec) -> (String, String) {
    switch d.state { case "revoked": return (t("Link revoked", "Kiungo kimezimwa"), "off"); case "opened": return (t("Opened their page", "Wamefungua ukurasa"), "ok"); default: return (t("Not opened yet", "Bado hawajafungua"), "has") }
}
private func donorProjects(_ n: Int) -> String { n == 1 ? t("1 project", "mradi 1") : t("\(n) projects", "miradi \(n)") }

/// The link just made or asked for.
private struct ShownLink: Equatable { let key: String; let who: String; let url: String }

struct DonorsSection: View {
    @EnvironmentObject var m: AppModel
    let s: Snapshot
    @State private var list: [DonorRec]?
    @State private var mailReady = false
    @State private var loadMsg: String?
    @State private var filter = "all"
    @State private var more = false
    @State private var picked: OItem?
    @State private var openName: String?
    @State private var shown: ShownLink?
    @State private var form: OItem?
    @State private var msg: (String, String)?

    func load() async {
        let r = await m.api.adminGet("donors")
        if r.ok { list = DonorRec.list(r.json); mailReady = r.json.obj("mail")?.bool("ready") == true; loadMsg = nil } else { loadMsg = reasonText(r.reason) }
        if let n = openName, let d = list?.last(where: { $0.name == n }) { picked = OItem(id: d.id, o: [:]); openName = nil }
    }

    var body: some View {
        let L = (list ?? []).sorted { a, b in
            if a.revoked != b.revoked { return !a.revoked }
            if a.lastOpen.isEmpty != b.lastOpen.isEmpty { return !a.lastOpen.isEmpty }
            if a.lastOpen != b.lastOpen { return a.lastOpen > b.lastOpen }
            return a.name < b.name
        }
        let count = Dictionary(grouping: L, by: \.state).mapValues(\.count)
        let shownL = L.filter { filter == "all" || $0.state == filter }
        VStack(alignment: .leading, spacing: 0) {
            OHead(icon: "heart", hue: "coral", title: t("Donors", "Wafadhili"), line: t("Each donor gets a private page with their projects, a map and updates.", "Kila mfadhili anapata ukurasa wa siri wenye miradi yake, ramani na taarifa.")) {
                ONums(items: [ONum(value: "\(L.count)", label: t("donors", "wafadhili"), on: filter == "all") { filter = "all"; more = false },
                              ONum(value: "\(count["opened"] ?? 0)", label: t("opened", "wamefungua"), color: (count["opened"] ?? 0) > 0 ? C.ok : C.muted, on: filter == "opened") { filter = "opened"; more = false },
                              ONum(value: "\(count["not"] ?? 0)", label: t("not opened", "hawajafungua"), color: (count["not"] ?? 0) > 0 ? C.blue2 : C.muted, on: filter == "not") { filter = "not"; more = false }])
                Btn(t("Add a donor", "Ongeza mfadhili"), icon: "plus", wide: true) { form = OItem(id: "new", o: [:]) }.padding(.top, 14).accessibilityIdentifier("dn_add")
            }
            Msg(loadMsg, "bad")
            if case let (tone, text)? = msg { Msg(text, tone) }
            if list != nil && !L.isEmpty {
                OChips(items: [("all", t("All", "Wote"), L.count), ("opened", t("Opened", "Wamefungua"), count["opened"] ?? 0), ("not", t("Not opened", "Hawajafungua"), count["not"] ?? 0),
                               ("revoked", t("Revoked", "Vimezimwa"), count["revoked"] ?? 0)], picked: filter) { filter = $0; more = false }
            }
            if list != nil && L.isEmpty { OEmpty(t("No donors yet. Tap “Add a donor” to make the first private page.", "Bado hakuna wafadhili. Gusa “Ongeza mfadhili” kutengeneza ukurasa wa kwanza wa siri.")) }
            else if list != nil && shownL.isEmpty {
                OEmpty(filter == "revoked" ? t("No revoked links.", "Hakuna viungo vilivyozimwa.") : filter == "not" ? t("Everyone has opened their page.", "Wote wamefungua ukurasa wao.") : t("Nobody has opened their page yet.", "Bado hakuna aliyefungua ukurasa wake."))
            }
            ForEach(Array(shownL.prefix(more ? shownL.count : 30).enumerated()), id: \.element.id) { i, d in
                OCalmCard(title: d.name, pill: donorPill(d),
                          lines: [(!d.org.isEmpty && d.org != d.name ? d.org : d.email).isEmpty ? t("No e-mail", "Hakuna barua pepe") : (!d.org.isEmpty && d.org != d.name ? d.org : d.email),
                                  donorProjects(d.projects.count) + (d.viewers.count > 1 ? t(" · \(d.viewers.count) people", " · watu \(d.viewers.count)") : "") + (!d.showPhotos ? t(" · photos off", " · picha zimezimwa") : "")],
                          time: d.lastOpen.isEmpty ? "" : ODates.ago(d.lastOpen), dim: d.revoked, lead: { OAvatar(name: d.name, size: 44) }) { picked = OItem(id: d.id, o: [:]) }
                    .padding(.top, 10).rise(min(i, 6))
            }
            if shownL.count > 30 && !more { Btn(t("Show more", "Onyesha zaidi") + " (\(shownL.count - 30))", light: true, small: true, wide: true) { more = true }.padding(.top, 12) }
            Text(t("Settings", "Mipangilio")).font(.mont(17, .heavy)).foregroundColor(C.ink).padding(.top, 22).padding(.bottom, 8)
            CardV(pad: 4) {
                OFold(icon: "mail", hue: "teal", title: t("E-mail digest", "Barua pepe ya kila mwezi"), summary: mailReady ? t("On · each donor, on the 1st of the month", "Imewashwa · kila mfadhili, tarehe 1 ya mwezi") : t("Off", "Imezimwa"), tone: mailReady ? "ok" : "off") {
                    Text(mailReady ? t("On: each donor with an e-mail gets a monthly update on the 1st.", "Imewashwa: kila mfadhili mwenye barua pepe anapata taarifa ya mwezi tarehe 1.")
                         : t("Off. Sending e-mail isn’t set up yet; whoever looks after the website can switch it on.", "Imezimwa. Kutuma barua pepe bado hakujawekwa; anayesimamia tovuti anaweza kuiwasha.")).font(.bMedium).foregroundColor(C.text).fixedSize(horizontal: false, vertical: true)
                    if !mailReady { OTechDetails(names: [("RESEND_API_KEY", false), ("ALERT_FROM", false)]) }
                    Text(t("To stop it for one donor, open them, tap Edit and untick “Monthly e-mail update”.", "Kuizima kwa mfadhili mmoja, mfungue, gusa Hariri na uondoe tiki “Taarifa ya mwezi kwa barua pepe”.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 8).fixedSize(horizontal: false, vertical: true)
                }
                ODivider().padding(.horizontal, 14)
                OFold(icon: "info", hue: "blue", title: t("How donor pages work", "Jinsi kurasa za wafadhili zinavyofanya kazi"), summary: t("A private link, no password", "Kiungo cha siri, bila nenosiri"), tone: "has") {
                    Text(t("Each donor gets a private link to a page with their projects, an exact-location map, visit reports, repairs, story moments and photos. No password: anyone with the link can see it, so send it only to them.",
                           "Kila mfadhili anapata kiungo cha siri cha ukurasa wenye miradi yake, ramani ya mahali kamili, ripoti za ziara, matengenezo, hadithi na picha. Hakuna nenosiri: yeyote mwenye kiungo anaweza kuuona, kwa hiyo mtumie yeye tu.")).font(.bMedium).foregroundColor(C.text).fixedSize(horizontal: false, vertical: true)
                    Text(t("A church or company can have several people, each with their own link, so you can switch one off without affecting the others.", "Kanisa au kampuni inaweza kuwa na watu kadhaa, kila mmoja na kiungo chake, ili uweze kuzima kimoja bila kuathiri wengine.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 8).fixedSize(horizontal: false, vertical: true)
                    Text(t("Visit photos of water points are shown. Schools, clinics and children’s homes stay hidden until you tick “photos OK” (children’s faces need consent).", "Picha za ziara za vituo vya maji zinaonyeshwa. Shule, zahanati na makao ya watoto zinafichwa hadi uchague “picha sawa” (nyuso za watoto zinahitaji ridhaa).")).font(.bSmall).foregroundColor(C.muted).padding(.top, 8).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .task { await load() }
        .sheet(item: $picked) { x in
            if let d = list?.first(where: { $0.id == x.id }) {
                DonorSheet(d: d, s: s, link: shown.flatMap { $0.key == d.id || $0.key == d.name ? $0 : nil }, close: { picked = nil }, setLink: { shown = $0 }, changed: { Task { await load() } }) {
                    picked = nil; DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { form = OItem(id: d.id, o: [:]) }
                }
            }
        }
        .sheet(item: $form) { x in
            DonorForm(s: s, editing: list?.first { $0.id == x.id }, close: { form = nil }) { who, link, editedId in
                form = nil
                if let editedId { msg = ("ok", t("Saved ✓", "Imehifadhiwa ✓")); Task { await load(); picked = OItem(id: editedId, o: [:]) } }
                else { shown = ShownLink(key: who, who: who, url: link); openName = who; msg = ("ok", t("Added ✓ Send them their private link.", "Imeongezwa ✓ Mtumie kiungo chake cha siri.")); Task { await load() } }
            }
        }
    }
}

/// Add or edit a donor: name, e-mail, organisation, the projects they funded, photos and the monthly e-mail.
private struct DonorForm: View {
    @EnvironmentObject var m: AppModel
    let s: Snapshot; let editing: DonorRec?; let close: () -> Void; let saved: (String, String, String?) -> Void
    @State private var name = ""
    @State private var email = ""
    @State private var org = ""
    @State private var ticked: [String] = []
    @State private var photosOk: Set<String> = []
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
        let ts = Set(ticked)
        let qq = q.trimmingCharacters(in: .whitespaces).lowercased()
        let shownP = s.projects.filter { p in (!only || ts.contains(p.id)) && (ck.isEmpty || p.ck == ck) && (cat.isEmpty || donorCat(p.type) == cat) && (qq.isEmpty || "\(p.title) \(p.area) \(p.country)".lowercased().contains(qq)) }
            .sorted { a, b in ts.contains(a.id) != ts.contains(b.id) ? ts.contains(a.id) : a.title < b.title }
        let sens = ticked.compactMap { id in s.projects.first { $0.id == id } }.filter { donorSensitive($0.type) }
        let (fg, bg) = otone("has")
        OSheet {
            OSheetHeadC(title: editing?.name ?? t("Add a donor", "Ongeza mfadhili"),
                        sub: [editing != nil ? t("Change their details, projects and photos.", "Badilisha maelezo yao, miradi na picha.") : t("They get a private link to their own page. You can copy it again, replace it or switch it off at any time.", "Wanapata kiungo cha siri cha ukurasa wao. Unaweza kukinakili tena, kukibadilisha au kukizima wakati wowote.")],
                        lead: { OTileIcon(icon: editing != nil ? "edit" : "heart", tint: fg, bg: bg, size: 64) }, close: close)
            Field(t("Name", "Jina"), $name, placeholder: t("e.g. Grace Community Church", "mf. Kanisa la Grace"))
            Field(t("E-mail", "Barua pepe"), $email, placeholder: t("for the monthly update", "kwa taarifa ya mwezi")).keyboardType(.emailAddress).textInputAutocapitalization(.never)
            Field(t("Organisation (optional)", "Shirika (si lazima)"), $org, placeholder: t("e.g. a church or company", "mf. kanisa au kampuni"))
            HStack { Text(t("Projects they funded", "Miradi waliyofadhili")).font(.tSmall).foregroundColor(C.ink).frame(maxWidth: .infinity, alignment: .leading); Tag("\(ticked.count) " + t("ticked", "zimechaguliwa"), bg: C.tint, fg: C.deep) }.padding(.top, 18)
            Field(t("Search", "Tafuta"), $q, placeholder: t("Name or place", "Jina au mahali"))
            HStack(alignment: .bottom, spacing: 8) {
                Picker1(label: t("Country", "Nchi"), value: ck, options: [("", t("All countries", "Nchi zote"))] + COUNTRIES) { ck = $0 }
                Picker1(label: t("Type", "Aina"), value: cat, options: [("", t("All types", "Aina zote")), ("water", t("Water", "Maji")), ("school", t("Schools", "Shule")), ("health", t("Health", "Afya")), ("children", t("Children’s homes", "Makao ya watoto")), ("other", t("Other", "Nyingine"))]) { cat = $0 }
            }
            Check(label: t("Ticked only", "Zilizochaguliwa tu"), on: only) { only.toggle() }.padding(.top, 6)
            VStack(spacing: 0) {
                ForEach(Array(shownP.prefix(40).enumerated()), id: \.element.id) { k, p in
                    let on = ts.contains(p.id)
                    if k > 0 { ODivider() }
                    Button { if on { ticked.removeAll { $0 == p.id } } else { ticked.append(p.id) } } label: {
                        HStack(spacing: 10) {
                            Image(systemName: on ? "checkmark.square.fill" : "square").font(.system(size: 22)).foregroundColor(on ? C.blue : C.muted)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(p.title).font(.mont(14, .bold)).foregroundColor(C.ink).multilineTextAlignment(.leading)
                                Text([p.type, p.area, p.country].filter { !$0.isEmpty }.joined(separator: " · ")).font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.leading)
                            }.frame(maxWidth: .infinity, alignment: .leading)
                        }.padding(.horizontal, 10).padding(.vertical, 4).frame(minHeight: 56).contentShape(Rectangle())
                    }.buttonStyle(.plain)
                }
                if shownP.isEmpty { Text(t("No projects match.", "Hakuna mradi unaolingana.")).foregroundColor(C.muted).padding(12) }
                if shownP.count > 40 { Text(t("…and \(shownP.count - 40) more. Search to narrow it down.", "…na \(shownP.count - 40) zaidi. Tafuta ili kupunguza.")).font(.bSmall).foregroundColor(C.muted).padding(12) }
            }.overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(C.line, lineWidth: 1)).padding(.top, 4)
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(t("Visit photos", "Picha za ziara")).font(.tSmall).foregroundColor(C.ink)
                    Text(t("Shown on their page", "Zinaonyeshwa kwenye ukurasa wao")).font(.bSmall).foregroundColor(C.muted)
                }.frame(maxWidth: .infinity, alignment: .leading)
                Toggle("", isOn: $showPhotos).labelsHidden().tint(C.ok)
            }.padding(12).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).padding(.top, 14)
            if showPhotos && !sens.isEmpty {
                Text(t("Photos OK for these (children’s faces need consent):", "Picha sawa kwa hii (nyuso za watoto zinahitaji ridhaa):")).font(.bSmall).foregroundColor(C.muted).padding(.top, 8).fixedSize(horizontal: false, vertical: true)
                ForEach(sens) { p in Check(label: p.title, on: photosOk.contains(p.id)) { if photosOk.contains(p.id) { photosOk.remove(p.id) } else { photosOk.insert(p.id) } } }
            }
            Check(label: t("Monthly e-mail update", "Taarifa ya mwezi kwa barua pepe"), on: digest) { digest.toggle() }.padding(.top, 6)
            Btn(editing != nil ? t("Save changes", "Hifadhi mabadiliko") : t("Add and make private link", "Ongeza na utengeneze kiungo cha siri"), icon: editing != nil ? "check" : "plus",
                enabled: !name.trimmingCharacters(in: .whitespaces).isEmpty && !busy, wide: true, busy: busy ? t("Saving…", "Inahifadhi…") : nil) { save() }.padding(.top, 14)
            OLineBtn(text: t("Cancel", "Ghairi"), icon: "x", action: close).padding(.top, 8)
            if case let (tone, text)? = msg { Msg(text, tone) }
        }
        .onAppear {
            guard !ready else { return }
            ready = true
            if let d = editing { name = d.name; email = d.email; org = d.org; ticked = d.projects; photosOk = Set(d.photos.filter { $0.value == "on" }.keys); showPhotos = d.showPhotos; digest = d.digest; only = true }
        }
    }
    func save() {
        busy = true; msg = nil
        Task {
            var ph: JSON = [:]
            photosOk.filter { ticked.contains($0) }.forEach { ph[$0] = "on" }
            editing?.photos.filter { $0.value == "off" && ticked.contains($0.key) }.forEach { ph[$0.key] = "off" }
            var b: JSON = ["action": editing != nil ? "donor-update" : "donor-add", "name": name.trimmingCharacters(in: .whitespaces), "email": email.trimmingCharacters(in: .whitespaces),
                           "org": org.trimmingCharacters(in: .whitespaces), "projects": ticked, "showPhotos": showPhotos, "photos": ph, "digest": digest]
            if let e = editing { b["id"] = e.id }
            let r = await m.api.admin(b)
            busy = false
            guard r.ok else { msg = ("bad", reasonText(r.reason)); return }
            Haptic.ok()
            saved(name.trimmingCharacters(in: .whitespaces), r.json.str("link"), editing?.id)
        }
    }
}

/// One donor: the private link (copy, share, rotate, revoke), edit, who can open the page, delete.
private struct DonorSheet: View {
    @EnvironmentObject var m: AppModel
    let d: DonorRec; let s: Snapshot; let link: ShownLink?; let close: () -> Void; let setLink: (ShownLink?) -> Void; let changed: () -> Void; let edit: () -> Void
    @State private var msg: (String, String)?
    @State private var ask: String?
    @State private var askMsg: String?
    @State private var busy = false
    @State private var vName = ""
    @State private var vEmail = ""
    @State private var viewer: OItem?

    func act(_ action: String, vid: String? = nil, _ after: @escaping (JSON) -> Void = { _ in }) {
        busy = true
        Task {
            var b: JSON = ["action": action, "id": d.id]; if let vid { b["vid"] = vid }
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
    func share(_ who: String, _ url: String) { Share.items([donorShareText(who, url)]) }
    func copy(_ text: String) { UIPasteboard.general.string = text }

    var body: some View {
        let (pl, tone) = donorPill(d); let (fg, bg) = otone(tone)
        let pv = d.viewers.first { $0.primary } ?? d.viewers.first
        OSheet {
            OSheetHeadC(title: d.name, sub: [!d.org.isEmpty && d.org != d.name ? d.org : "", d.email], pill: (pl, tone), lead: { OTileIcon(icon: "heart", tint: fg, bg: bg, size: 64) }, close: close)
            if let l = link {
                VStack(alignment: .leading, spacing: 0) {
                    Text(t("New private link for ", "Kiungo kipya cha siri cha ") + l.who).font(.tSmall).foregroundColor(C.ok)
                    Text(l.url.replacingOccurrences(of: "/donor/", with: "/donor/\n")).font(.system(size: 11, design: .monospaced)).foregroundColor(C.ink).padding(10).frame(maxWidth: .infinity, alignment: .leading)
                        .background(C.card).clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous)).padding(.vertical, 8).textSelection(.enabled)
                    HStack(spacing: 8) {
                        Btn(t("Copy", "Nakili"), icon: "copy", light: true, small: true, wide: true) { copy(l.url); msg = ("ok", t("Link copied ✓", "Kiungo kimenakiliwa ✓")) }
                        Btn(t("Share", "Shiriki"), icon: "share", light: true, small: true, wide: true) { share(l.who, l.url) }
                    }.padding(.top, 2)
                }.padding(14).background(C.okBg).clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous)).padding(.top, 16)
            }
            VStack(spacing: 8) {
                if !d.revoked && link != nil {
                    if let pv { codeRow(pv.name.isEmpty ? d.name : pv.name, pv.code) }
                    OLineBtn(text: t("Edit", "Hariri"), icon: "edit", action: edit)
                } else if !d.revoked {
                    Btn(t("Copy private link", "Nakili kiungo cha siri"), icon: "copy", enabled: !busy, wide: true) { act("donor-link") { j in copy(j.str("link")); msg = ("ok", t("Link copied ✓", "Kiungo kimenakiliwa ✓")) } }
                    if let pv { codeRow(pv.name.isEmpty ? d.name : pv.name, pv.code) }
                    HStack(spacing: 8) {
                        OLineBtn(text: t("Share link", "Shiriki kiungo"), icon: "share") { act("donor-link") { j in let l = ShownLink(key: d.id, who: d.name, url: j.str("link")); setLink(l); share(l.who, l.url) } }
                        OLineBtn(text: t("Edit", "Hariri"), icon: "edit", action: edit)
                    }
                } else {
                    Btn(t("Make a new link", "Tengeneza kiungo kipya"), icon: "link", enabled: !busy, wide: true) { act("donor-rotate") { j in setLink(ShownLink(key: d.id, who: d.name, url: j.str("link"))); msg = ("ok", t("New link made ✓", "Kiungo kipya kimetengenezwa ✓")); changed() } }
                    OLineBtn(text: t("Edit", "Hariri"), icon: "edit", action: edit)
                }
            }.padding(.top, 16)
            if case let (tn, text)? = msg { Msg(text, tn) }
            VStack(spacing: 0) {
                let ps = d.projects.map { id in s.projects.first { $0.id == id }?.title ?? id }
                OFact(label: t("Projects", "Miradi"), value: donorProjects(ps.count), sub: ps.prefix(3).joined(separator: ", ") + (ps.count > 3 ? " …" : ""))
                OFact(label: t("Last opened", "Ilifunguliwa mwisho"), value: d.lastOpen.isEmpty ? t("Not yet", "Bado") : When.text(d.lastOpen), sub: d.opens > 1 ? t("\(d.opens) times", "mara \(d.opens)") : "")
                OFact(label: t("Visit photos", "Picha za ziara"), value: d.showPhotos ? t("Shown", "Zinaonyeshwa") : t("Hidden", "Zimefichwa"))
                OFact(label: t("Monthly e-mail", "Barua pepe ya mwezi"), value: d.digest ? t("On", "Imewashwa") : t("Off", "Imezimwa"))
            }.padding(.top, 12)
            VStack(spacing: 0) {
                OFold(icon: "people", hue: "purple", title: t("Who can open this page", "Nani anaweza kufungua ukurasa huu"), summary: d.viewers.count == 1 ? t("1 person", "mtu 1") : t("\(d.viewers.count) people", "watu \(d.viewers.count)"), tone: d.viewers.count > 1 ? "has" : "off") {
                    ForEach(d.viewers, id: \.vid) { v in
                        let who = v.name.isEmpty ? d.name : v.name
                        Button { viewer = OItem(id: v.vid, o: [:]) } label: {
                            HStack(spacing: 10) {
                                OAvatar(name: who, size: 38)
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(who + (v.primary ? t(" (main)", " (mkuu)") : "")).font(.mont(14, .bold)).foregroundColor(C.ink)
                                    Text([!v.active ? t("Revoked", "Kimezimwa") : "", v.email, v.lastOpen.isEmpty ? t("not opened yet", "bado hakijafunguliwa") : t("opened ", "kimefunguliwa ") + When.text(v.lastOpen)].filter { !$0.isEmpty }.joined(separator: " · "))
                                        .font(.bSmall).foregroundColor(v.active ? C.muted : C.bad).multilineTextAlignment(.leading)
                                }.frame(maxWidth: .infinity, alignment: .leading)
                                Icon("chev", size: 18, color: C.muted)
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
                            if r.ok { setLink(ShownLink(key: d.id, who: vName.trimmingCharacters(in: .whitespaces), url: r.json.str("link"))); vName = ""; vEmail = ""; changed() } else { msg = ("bad", reasonText(r.reason)) }
                        }
                    }.padding(.top, 10)
                }
            }.overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(C.line, lineWidth: 1)).padding(.top, 14)
            ODivider().padding(.top, 16)
            VStack(spacing: 8) {
                if !d.revoked {
                    OLineBtn(text: t("Rotate link", "Badilisha kiungo"), icon: "refresh") { ask = "rotate"; askMsg = nil }
                    OLineBtn(text: t("Revoke the link", "Zima kiungo"), icon: "x", red: true) { ask = "revoke"; askMsg = nil }
                }
                OLineBtn(text: t("Delete donor", "Futa mfadhili"), icon: "trash", red: true) { ask = "delete"; askMsg = nil }
            }.padding(.top, 14)
            if let a = ask {
                OAsk(question: a == "revoke" ? t("Revoke the link for \(d.name)? Their page stops working straight away. You can make a new link later.", "Zima kiungo cha \(d.name)? Ukurasa wao utaacha kufanya kazi mara moja. Unaweza kutengeneza kiungo kipya baadaye.")
                     : a == "rotate" ? t("Make a new link for \(d.name)? The old link stops working, so send them the new one.", "Tengeneza kiungo kipya cha \(d.name)? Kiungo cha zamani kitaacha kufanya kazi, kwa hiyo watumie kipya.")
                     : t("Delete \(d.name)? Their links stop working straight away.", "Futa \(d.name)? Viungo vyao vitaacha kufanya kazi mara moja."),
                     yes: a == "revoke" ? t("Revoke", "Zima") : a == "rotate" ? t("Make a new link", "Tengeneza kiungo kipya") : t("Delete", "Futa"), red: a != "rotate", busy: busy, msg: askMsg, onNo: { ask = nil }) {
                    switch a {
                    case "revoke": act("donor-revoke") { _ in ask = nil; setLink(nil); msg = ("ok", t("Link revoked", "Kiungo kimezimwa")); changed() }
                    case "rotate": act("donor-rotate") { j in ask = nil; setLink(ShownLink(key: d.id, who: d.name, url: j.str("link"))); msg = ("ok", t("New link made ✓", "Kiungo kipya kimetengenezwa ✓")); changed() }
                    default: act("donor-remove") { _ in changed(); close() }
                    }
                }
            }
        }
        .sheet(item: $viewer) { x in
            if let v = d.viewers.first(where: { $0.vid == x.id }) { ViewerSheet(d: d, v: v, close: { viewer = nil }, changed: changed) }
        }
    }

    @ViewBuilder func codeRow(_ name: String, _ code: String) -> some View {
        if !code.isEmpty {
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 1) {
                    Text(t("App code", "Msimbo wa programu")).font(.mont(12, .semibold)).foregroundColor(C.muted).lineLimit(1)
                    Text(code).font(.system(size: 16, weight: .bold, design: .monospaced)).foregroundColor(C.ink).lineLimit(1).minimumScaleFactor(0.7)
                }.frame(maxWidth: .infinity, alignment: .leading)
                Btn(t("Copy", "Nakili"), icon: "copy", light: true, small: true) { copy(code); msg = ("ok", t("Code copied ✓", "Msimbo umenakiliwa ✓")) }
            }.padding(.leading, 14).padding(.trailing, 6).padding(.vertical, 6).background(C.card).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.line, lineWidth: 1))
            OLineBtn(text: t("Send app invite", "Tuma mwaliko wa programu"), icon: "heart") { Share.items([donorInviteText(name, code)]) }
        }
    }
}

/// One person who can open a donor's page: their link, app invite, name and e-mail, the monthly e-mail, access.
private struct ViewerSheet: View {
    @EnvironmentObject var m: AppModel
    let d: DonorRec; let v: DonorViewer; let close: () -> Void; let changed: () -> Void
    @State private var sub: String?
    @State private var field = ""
    @State private var msg: (String, String)?
    @State private var busy = false
    func send(_ b: JSON, _ ok: String) {
        busy = true
        Task {
            let r = await m.api.admin(b)
            busy = false
            if r.ok { msg = ("ok", ok); sub = nil; changed() } else { msg = ("bad", reasonText(r.reason)) }
        }
    }
    var body: some View {
        let who = v.name.isEmpty ? d.name : v.name
        OSheet {
            OSheetHeadC(title: who, sub: [v.primary ? t("Main", "Mkuu") + " · " + d.name : t("Donor", "Mfadhili") + " · " + d.name, v.lastOpen.isEmpty ? t("Hasn’t opened their page yet", "Bado hajafungua ukurasa wake") : t("Opened their page ", "Alifungua ukurasa ") + When.text(v.lastOpen)],
                        pill: v.active ? nil : (t("Access blocked", "Ufikiaji umezuiwa"), "bad"), lead: { OAvatar(name: who, size: 64) }, close: close)
            OLabel(t("Access", "Ufikiaji"))
            VStack(spacing: 8) {
                if v.active {
                    row("copy", "blue", t("Copy private link", "Nakili kiungo cha siri"), t("Their own page, no password", "Ukurasa wao, bila nenosiri")) { link { UIPasteboard.general.string = $0; msg = ("ok", t("Link copied ✓", "Kiungo kimenakiliwa ✓")) } }
                    row("share", "purple", t("Share link", "Shiriki kiungo"), t("WhatsApp, SMS or e-mail", "WhatsApp, SMS au barua pepe")) { link { Share.items([donorShareText(who, $0)]) } }
                    if !v.code.isEmpty { row("heart", "coral", t("Send app invite", "Tuma mwaliko wa programu"), t("With their app code", "Pamoja na msimbo wao wa programu")) { Share.items([donorInviteText(who, v.code)]) } }
                }
            }
            OLabel(t("Details", "Maelezo"))
            VStack(spacing: 8) {
                row("edit", "blue", t("Name", "Jina"), who) { field = v.name; sub = "name" }
                row("mail", "teal", v.email.isEmpty ? t("Add e-mail", "Ongeza barua pepe") : t("Change e-mail", "Badilisha barua pepe"), v.email.isEmpty ? t("None yet", "Bado hakuna") : v.email) { field = v.email; sub = "email" }
                if sub == "name" || sub == "email" {
                    Field(sub == "name" ? t("Name", "Jina") : t("E-mail", "Barua pepe"), $field)
                    Btn(t("Save", "Hifadhi"), icon: "check", enabled: !busy, wide: true) {
                        let y = field.trimmingCharacters(in: .whitespaces)
                        if sub == "email" && !y.isEmpty && !y.contains("@") { msg = ("bad", t("That doesn’t look like an e-mail address.", "Hiyo haionekani kama anwani ya barua pepe.")); return }
                        send(["action": "donor-viewer-update", "id": d.id, "vid": v.vid, "name": sub == "name" ? y : v.name, "email": sub == "email" ? y : v.email], t("Saved ✓", "Imehifadhiwa ✓"))
                    }.padding(.top, 8)
                }
                row("mail", "green", d.digest ? t("Stop monthly e-mails", "Simamisha barua pepe za mwezi") : t("Start monthly e-mails", "Anza barua pepe za mwezi"), t(" · for everyone at \(d.name)", " · kwa wote wa \(d.name)")) {
                    var ph: JSON = [:]; d.photos.forEach { ph[$0.key] = $0.value }
                    send(["action": "donor-update", "id": d.id, "name": d.name, "email": d.email, "org": d.org, "projects": d.projects, "showPhotos": d.showPhotos, "photos": ph, "digest": !d.digest], t("Saved ✓", "Imehifadhiwa ✓"))
                }
                row("refresh", "blue", t("New link", "Kiungo kipya"), t("The old link stops working", "Kiungo cha zamani kitaacha kufanya kazi")) { sub = "rotate" }
                if v.active { row("x", "coral", t("Block access", "Zuia ufikiaji"), t("Their link stops working", "Kiungo chao kitaacha kufanya kazi"), red: true) { sub = "revoke" } }
                else { row("check", "green", t("Restore access", "Rudisha ufikiaji"), t("Their link works again", "Kiungo chao kitafanya kazi tena")) { sub = "restore" } }
                if !v.primary { row("trash", "coral", t("Remove", "Ondoa"), t("Takes them off this page", "Anaondolewa kwenye ukurasa huu"), red: true) { sub = "remove" } }
            }
            if let a = sub, a != "name" && a != "email" {
                OAsk(question: a == "rotate" ? t("Make a new link for \(who)?", "Tengeneza kiungo kipya cha \(who)?") : a == "revoke" ? t("Block access for \(who)?", "Zuia ufikiaji wa \(who)?") : a == "restore" ? t("Restore access for \(who)?", "Rudisha ufikiaji wa \(who)?") : t("Remove \(who)?", "Ondoa \(who)?"),
                     yes: a == "rotate" ? t("Make a new link", "Tengeneza kiungo kipya") : a == "revoke" ? t("Block", "Zuia") : a == "restore" ? t("Restore", "Rudisha") : t("Remove", "Ondoa"),
                     red: a == "revoke" || a == "remove", busy: busy, onNo: { sub = nil }) {
                    let action = a == "rotate" ? "donor-rotate" : a == "revoke" ? "donor-revoke" : a == "restore" ? "donor-viewer-restore" : "donor-viewer-remove"
                    let ok = a == "rotate" ? t("New link made ✓", "Kiungo kipya kimetengenezwa ✓") : a == "revoke" ? t("Access blocked", "Ufikiaji umezuiwa") : a == "restore" ? t("Access restored ✓", "Ufikiaji umerudishwa ✓") : t("Saved ✓", "Imehifadhiwa ✓")
                    send(["action": action, "id": d.id, "vid": v.vid], ok)
                    if a == "remove" { DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { close() } }
                }
            }
            if case let (tn, text)? = msg { Msg(text, tn) }
        }
    }
    func link(_ then: @escaping (String) -> Void) {
        busy = true
        Task {
            let r = await m.api.admin(["action": "donor-link", "id": d.id, "vid": v.vid])
            busy = false
            if r.ok { then(r.json.str("link")) } else { msg = ("bad", r.reason == "rotate" ? t("Make a new link to copy it.", "Tengeneza kiungo kipya ili ukinakili.") : reasonText(r.reason)) }
        }
    }
    func row(_ icon: String, _ hue: String, _ label: String, _ value: String, red: Bool = false, _ action: @escaping () -> Void) -> some View {
        let hu = ohue(hue)
        return Button { Haptic.tap(); action() } label: {
            HStack(spacing: 12) {
                OTileIcon(icon: icon, tint: red ? C.bad : hu.fg, bg: red ? C.badBg : hu.bg, size: 36)
                VStack(alignment: .leading, spacing: 1) {
                    Text(label).font(.tSmall).foregroundColor(red ? C.bad : C.ink).multilineTextAlignment(.leading)
                    Text(value).font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.leading)
                }.frame(maxWidth: .infinity, alignment: .leading)
                Icon("chev", size: 18, color: C.muted)
            }.padding(.horizontal, 12).padding(.vertical, 8).frame(minHeight: 56).overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(C.line, lineWidth: 1)).contentShape(Rectangle())
        }.buttonStyle(.plain).disabled(busy)
    }
}

// --- Project › Thank-you for donors -----------------------------------------------------------------------------

/// A thank-you photo or short video (≤ 60 s) from the community, for the project's donors (/api/pin-thanks).
struct DonorThanks: View {
    @EnvironmentObject var m: AppModel
    let p: Project
    @State private var open = false
    @State private var picking = false
    @State private var file: URL?
    @State private var caption = ""
    @State private var msg: (String, String)?
    @State private var busy = false
    var body: some View {
        CardV(pad: 14) {
            Button { withAnimation { open.toggle() } } label: {
                HStack(spacing: 12) {
                    OTileIcon(icon: "camera", tint: C.bad, bg: C.badBg, size: 38)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(t("Thank-you for donors", "Shukrani kwa wafadhili")).font(.tSmall).foregroundColor(C.ink)
                        Text(t("A photo or short video (up to 60 seconds) from the community", "Picha au video fupi (hadi sekunde 60) kutoka kwa jamii")).font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.leading)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                    Icon(open ? "down" : "chev", size: 20, color: C.muted)
                }.frame(minHeight: 44).contentShape(Rectangle())
            }.buttonStyle(.plain)
            if open {
                Text(t("Ask first: only people who agree to be filmed or photographed. It goes to monday.com and appears on the private pages of this project’s donors.", "Omba ruhusa kwanza: watu waliokubali kupigwa picha au video tu. Inaenda monday.com na kuonekana kwenye kurasa za siri za wafadhili wa mradi huu."))
                    .font(.bSmall).foregroundColor(C.muted).padding(.top, 8).fixedSize(horizontal: false, vertical: true)
                Btn(t("Choose a photo or video", "Chagua picha au video"), icon: "camera", light: true, small: true, wide: true) { picking = true }.padding(.top, 8)
                if let file { Text(t("Chosen: ", "Umechagua: ") + file.lastPathComponent).font(.bSmall).foregroundColor(C.ink).padding(.top, 6) }
                Field(t("A few words for the donors (optional)", "Maneno machache kwa wafadhili (si lazima)"), Binding(get: { caption }, set: { caption = String($0.prefix(140)) }), placeholder: t("e.g. Asante sana from the water committee!", "mf. Asante sana kutoka kamati ya maji!"))
                Btn(t("Send to donors", "Tuma kwa wafadhili"), icon: "upload", enabled: file != nil && !busy, wide: true, busy: busy ? t("Sending…", "Inatuma…") : nil) { send() }.padding(.top, 10)
                if case let (tone, text)? = msg { Msg(text, tone) }
            }
        }
        .padding(.top, 12)
        .sheet(isPresented: $picking) { MediaPicker { u in picking = false; if let u { file = u; msg = nil } } }
    }
    func send() {
        guard let f = file else { return }
        busy = true
        Task {
            let ext = f.pathExtension.lowercased()
            let video = ["mov", "mp4", "m4v", "3gp", "webm"].contains(ext)
            let mime = video ? (ext == "mp4" || ext == "m4v" ? "video/mp4" : ext == "webm" ? "video/webm" : ext == "3gp" ? "video/3gpp" : "video/quicktime") : (ext == "png" ? "image/png" : ext == "webp" ? "image/webp" : "image/jpeg")
            var secs = 0
            if video { let a = AVURLAsset(url: f); secs = Int((try? await a.load(.duration)).map { CMTimeGetSeconds($0) } ?? 0) }
            guard let data = try? Data(contentsOf: f) else { busy = false; msg = ("bad", t("Couldn’t read that file.", "Haikuweza kusoma faili hiyo.")); return }
            if video && secs > 61 { busy = false; msg = ("bad", t("Please keep it to 60 seconds or less.", "Tafadhali isizidi sekunde 60.")); return }
            if data.count > (video ? 40 : 8) * 1024 * 1024 { busy = false; msg = ("bad", t("That file is too large.", "Faili hiyo ni kubwa mno.")); return }
            let r = await m.api.multipart("/api/pin-thanks", data: data, mime: mime, filename: "thanks." + (video ? (mime == "video/mp4" ? "mp4" : "mov") : (mime == "image/png" ? "png" : "jpg")),
                                          fields: [("id", p.id), ("caption", caption.trimmingCharacters(in: .whitespaces)), ("seconds", "\(secs)")])
            busy = false
            if r.ok { msg = ("ok", t("Sent ✓ Thank you! The donors will see it on their page.", "Imetumwa ✓ Asante! Wafadhili wataiona kwenye ukurasa wao.")); file = nil; caption = ""; Haptic.ok() }
            else { msg = ("bad", r.status == 0 ? t("This needs a signal. Try again when you’re online.", "Hii inahitaji mtandao. Jaribu tena ukiwa mtandaoni.") : reasonText(r.reason)) }
        }
    }
}

/// Choose one photo or video from the phone; it is copied to a file the app can read.
struct MediaPicker: UIViewControllerRepresentable {
    let done: (URL?) -> Void
    func makeCoordinator() -> Coord { Coord(done) }
    func makeUIViewController(context: Context) -> PHPickerViewController {
        var c = PHPickerConfiguration(); c.selectionLimit = 1; c.filter = .any(of: [.images, .videos])
        let vc = PHPickerViewController(configuration: c); vc.delegate = context.coordinator; return vc
    }
    func updateUIViewController(_ vc: PHPickerViewController, context: Context) {}
    final class Coord: NSObject, PHPickerViewControllerDelegate {
        let done: (URL?) -> Void
        init(_ d: @escaping (URL?) -> Void) { done = d }
        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            guard let it = results.first?.itemProvider else { done(nil); return }
            let type = it.hasItemConformingToTypeIdentifier(UTType.movie.identifier) ? UTType.movie : UTType.image
            it.loadFileRepresentation(forTypeIdentifier: type.identifier) { url, _ in
                var out: URL?
                if let url {
                    let dst = FileManager.default.temporaryDirectory.appendingPathComponent("thanks-\(Int(Date().timeIntervalSince1970)).\(url.pathExtension.isEmpty ? (type == .movie ? "mov" : "jpg") : url.pathExtension)")
                    try? FileManager.default.removeItem(at: dst)
                    if (try? FileManager.default.copyItem(at: url, to: dst)) != nil { out = dst }
                }
                DispatchQueue.main.async { self.done(out) }
            }
        }
    }
}

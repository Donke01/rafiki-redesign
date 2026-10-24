import MapKit
import PhotosUI
import SwiftUI
import UIKit
import AVFoundation
import UniformTypeIdentifiers

// A project's repair tracker, its photo timeline, its donor summary (PDF on the letterhead), "Water test due",
// the repair cost line and a thank-you for donors. The same as Android's Tracker.kt, ProjectExtras.kt, Costs.kt and Donors.kt.

/// No water test for this many days and a finished water project shows "Water test due".
let TEST_DAYS = 182.0

/// A finished borehole or water collection project with no water test recorded in the app in 6 months.
func testDue(_ s: Snapshot, _ p: Project, now: Date = Date()) -> Bool {
    if !isWater(p) || p.status.range(of: "diligence|progress|construction", options: [.regularExpression, .caseInsensitive]) != nil { return false }
    guard let at = s.tests[p.id], let d = When.parse(at) else { return true }
    return now.timeIntervalSince(d) / 86400 >= TEST_DAYS
}
/// How many pinned projects are due a water test.
func testCount(_ s: Snapshot, _ list: [Project]? = nil) -> Int { (list ?? s.projects).filter { $0.gps != nil && testDue(s, $0) }.count }
/// After a visit that recorded a water test, the tag goes at once.
@MainActor func testedNow(_ m: AppModel, _ p: Project, _ body: JSON) { if !(body.obj("quality") ?? [:]).isEmpty { m.localTested(p) } }

/// A checklist problem's name in the app's language.
func problemName(_ en: String) -> String {
    let x = en.trimmingCharacters(in: .whitespaces).lowercased()
    return ITEMS.values.first { $0.0.lowercased() == x }.map { t($0.0, $0.1) } ?? en
}

struct WaterTestTag: View {
    var body: some View {
        let (bg, fg): (Color, Color) = C.sun ? (Color(hex: 0xD2F2F6), Color(hex: 0x003F47)) : C.dark ? (Color(hex: 0x0E3237), Color(hex: 0x7FDCE6)) : (Color(hex: 0xE0F5F7), Color(hex: 0x005F6B))
        HStack(spacing: 4) {
            Image(systemName: "flask").font(.system(size: 11, weight: .semibold)).foregroundColor(fg)
            Text(t("Water test due", "Kipimo cha maji kinahitajika")).font(.mont(12, .bold)).foregroundColor(fg).lineLimit(1)
        }.padding(.horizontal, 9).padding(.vertical, 4).background(bg).clipShape(Capsule()).overlay(Capsule().stroke(C.sun ? fg : .clear, lineWidth: 2))
    }
}

// MARK: - photo slots and the timeline

struct TlItem: Equatable { var url: String; var at: String; var by: String; var main = false }
struct TlGroup: Equatable { var key: String; var slot: String; var en: String; var sw: String; var items: [TlItem]; var title: String { t(en, sw) } }

private let SLOT_DEFS: [(String, String, String)] = [
    ("close", "Pump or tank, close up", "Pampu au tangi, kwa karibu"), ("flow", "Water flowing", "Maji yakitiririka"), ("site", "The site with people", "Eneo pamoja na watu"),
    ("damage", "Any damage", "Uharibifu wowote"), ("strip", "Water test strip", "Kipimo cha maji"), ("close", "The water point", "Kituo cha maji"), ("site", "Building and people", "Jengo na watu"),
    ("outside", "Outside, the whole building", "Nje, jengo lote"), ("inside", "A classroom inside", "Ndani ya darasa"), ("desks", "Desks and boards", "Madawati na ubao"),
    ("people", "Pupils and teachers", "Wanafunzi na walimu"), ("equip", "Equipment and beds", "Vifaa na vitanda"), ("supplies", "Medicine and supplies store", "Stoo ya dawa na vifaa"),
    ("washrooms", "Water and washrooms", "Maji na vyoo"), ("dorm", "Dormitory and beds", "Bweni na vitanda"), ("kitchen", "Kitchen", "Jiko"), ("washrooms", "Water and latrines", "Maji na vyoo"),
    ("animals", "The animals", "Mifugo"), ("pen", "The pen or coop", "Banda"), ("feed", "Feed and water for the animals", "Chakula na maji ya mifugo"), ("farmer", "The farmer at work", "Mkulima kazini"),
    ("inside", "Inside", "Ndani"), ("people", "People using it", "Watu wakiitumia"), ("progress", "The whole building now", "Jengo lote sasa"),
    ("work", "Work done since the last visit", "Kazi iliyofanyika tangu ziara iliyopita"), ("materials", "Materials on site", "Vifaa vya ujenzi"), ("issue", "Anything wrong", "Kitu chochote kibaya"),
]
func slotSlug(_ s: String) -> String {
    String(s.lowercased().replacingOccurrences(of: "[^a-z0-9]+", with: "-", options: .regularExpression).trimmingCharacters(in: CharacterSet(charactersIn: "-")).prefix(40))
}
private let BY_SLUG: [String: (String, String, String)] = Dictionary(SLOT_DEFS.map { (slotSlug($0.1), $0) }, uniquingKeysWith: { a, _ in a })
private let IMG_EXT: Set<String> = ["jpg", "jpeg", "png", "webp", "gif"]

private func slotOrder(_ p: Project) -> [String] {
    let ty = p.type.lowercased()
    let en: [String]
    if isWater(p) { en = ["Pump or tank, close up", "Water flowing", "The site with people", "Any damage", "Water test strip"] }
    else if p.status.lowercased().contains("under construction") { en = ["The whole building now", "Work done since the last visit", "Materials on site", "Anything wrong"] }
    else if ty.contains("school") { en = ["Outside, the whole building", "A classroom inside", "Desks and boards", "Pupils and teachers", "Any damage"] }
    else if ty.contains("health") { en = ["Outside, the whole building", "Equipment and beds", "Medicine and supplies store", "Water and washrooms", "Any damage"] }
    else if ty.contains("orphan") { en = ["Outside, the whole building", "Dormitory and beds", "Kitchen", "Water and latrines", "Any damage"] }
    else if ty.contains("farm") { en = ["The animals", "The pen or coop", "Feed and water for the animals", "The farmer at work", "Any damage"] }
    else { en = ["Outside, the whole building", "Inside", "People using it", "Any damage"] }
    return en.map(slotSlug)
}

/// Which group a file belongs to: (key, slot, (en, sw)).
func slotOf(_ name: String) -> (String, String, (String, String)) {
    let re = try! NSRegularExpression(pattern: #"^site-photo-(?:(.*?)-)?(\d{4}-\d{2}-\d{2}-\d{2}-\d{2})\.[a-z0-9]+$"#, options: .caseInsensitive)
    var slug = ""
    if let mm = re.firstMatch(in: name, range: NSRange(name.startIndex..., in: name)), let r = Range(mm.range(at: 1), in: name) { slug = String(name[r]) }
    if slug.isEmpty || slug == "other-photo" { return ("other", "other", ("Other photos", "Picha nyingine")) }
    if let d = BY_SLUG[slug] { return (slug, d.0, (d.1, d.2)) }
    let h = slug.replacingOccurrences(of: "-", with: " ").capitalized
    return (slug, slug, (h, h))
}
/// Who took it: the note says "Site visit by Grace (RAFIKI pin app)" or "… by Grace: …".
func photoBy(_ n: Note) -> String {
    let first = n.text.components(separatedBy: "\n").first ?? ""
    let re = try! NSRegularExpression(pattern: #"\bby ([^.:(\n<]{2,60}?)\s*(?:\(RAFIKI pin app\)|:|\.)"#, options: .caseInsensitive)
    if let mm = re.firstMatch(in: first, range: NSRange(first.startIndex..., in: first)), let r = Range(mm.range(at: 1), in: first) { return String(first[r]).trimmingCharacters(in: .whitespaces) }
    return n.by
}

/// The visit photos grouped by slot, newest first in each; the project's main photo on top; "Other photos" last.
func photoGroups(_ notes: [Note], _ p: Project, mainUrl: String?) -> [TlGroup] {
    var order: [String] = []; var g: [String: [TlItem]] = [:]; var meta: [String: (String, String, (String, String))] = [:]
    for n in notes {
        for f in n.files where !f.url.isEmpty && IMG_EXT.contains(f.ext.lowercased()) {
            let s = slotOf(f.name); meta[s.0] = s
            if g[s.0] == nil { order.append(s.0); g[s.0] = [] }
            g[s.0]!.append(TlItem(url: f.url, at: n.at, by: photoBy(n)))
        }
    }
    let so = slotOrder(p)
    func rank(_ k: String) -> Int { k == "other" ? 99 : (so.firstIndex(of: k) ?? 50) }
    let out = order.map { k -> TlGroup in let s = meta[k]!; return TlGroup(key: k, slot: s.1, en: s.2.0, sw: s.2.1, items: g[k]!.sorted { $0.at > $1.at }) }
        .sorted { rank($0.key) != rank($1.key) ? rank($0.key) < rank($1.key) : $0.items.count > $1.items.count }
    return (mainUrl.map { [TlGroup(key: "main", slot: "main", en: "Main photo", sw: "Picha kuu", items: [TlItem(url: $0, at: "", by: "", main: true)])] } ?? []) + out
}
func prjDay(_ iso: String) -> String { Dates.parse(iso).map { Dates.day($0) } ?? String(iso.prefix(10)) }

/// The timeline at the top of History: one swipeable strip per photo slot, newest first; tap for full screen.
struct PhotoTimeline: View {
    @EnvironmentObject var m: AppModel
    let p: Project; let notes: [Note]
    @State private var open: (Int, Int)?
    var body: some View {
        let groups = photoGroups(notes, p, mainUrl: p.photo.map { m.api.photoUrl($0, w: 1280) })
        let real = groups.filter { $0.key != "main" }
        let n = real.reduce(0) { $0 + $1.items.count }
        let visits = Set(real.flatMap { $0.items.map { String($0.at.prefix(10)) } }).count
        CardV(pad: 14) {
            HStack(spacing: 10) {
                let (bg, fg): (Color, Color) = C.sun ? (.white, Color(hex: 0x3D1F8A)) : C.dark ? (Color(hex: 0x2A2147), Color(hex: 0xC3A9FF)) : (Color(hex: 0xEFE8FB), Color(hex: 0x7A4FD0))
                Icon("photo", size: 20, color: fg).frame(width: 36, height: 36).background(bg).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                Text(t("Photo timeline", "Picha kwa wakati")).font(.tMedium).foregroundColor(C.ink)
                Spacer(minLength: 4)
                if n > 0 { Text(t("\(n) \(n == 1 ? "photo" : "photos") · \(visits) \(visits == 1 ? "visit" : "visits")", "picha \(n) · ziara \(visits)")).font(.lSmall).foregroundColor(C.deep).padding(.horizontal, 10).padding(.vertical, 4).background(C.tint).clipShape(Capsule()) }
            }
            if n > 0 { Text(t("The same view at each visit, newest first. Swipe along a row to compare; tap a photo to open it.", "Mwonekano ule ule kila ziara, ya karibuni kwanza. Telezesha mstari kulinganisha; gusa picha kuifungua.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 8) }
            ForEach(Array(groups.enumerated()), id: \.offset) { gi, g in
                HStack(alignment: .bottom) {
                    Text(g.title).font(.tSmall).foregroundColor(C.ink)
                    Spacer()
                    Text(g.key == "main" ? t("On the website", "Kwenye tovuti") : t("\(g.items.count) \(g.items.count == 1 ? "photo" : "photos")", "picha \(g.items.count)")).font(.lSmall).foregroundColor(C.muted)
                }.padding(.top, 14).padding(.bottom, 6)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(Array(g.items.enumerated()), id: \.offset) { ii, it in
                            Button { open = (gi, ii) } label: {
                                VStack(spacing: 0) {
                                    ZStack(alignment: .topLeading) {
                                        NetImage(url: it.url) { ZStack { C.tint; Icon("camera", size: 28, color: C.off) } }.frame(width: it.main ? 220 : 156, height: (it.main ? 220 : 156) * 3 / 4).clipped()
                                        if ii == 0 && !it.main && g.items.count > 1 {
                                            Text(t("Latest", "Mpya zaidi")).font(.mont(10, .bold)).foregroundColor(.white).padding(.horizontal, 8).padding(.vertical, 2).background(GreenC).clipShape(Capsule()).padding(8)
                                        }
                                    }
                                    VStack(alignment: .leading, spacing: 1) {
                                        Text(it.main ? t("Main photo", "Picha kuu") : prjDay(it.at)).font(.lMedium).foregroundColor(C.ink).lineLimit(1)
                                        if !it.by.isEmpty { Text(it.by).font(.mont(11)).foregroundColor(C.muted).lineLimit(1) }
                                    }.padding(.horizontal, 10).padding(.vertical, 7).frame(width: it.main ? 220 : 156, alignment: .leading).background(C.bg)
                                }
                                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.line, lineWidth: 1))
                            }.buttonStyle(.plain)
                                .accessibilityLabel(g.title + (it.at.isEmpty ? "" : ", " + prjDay(it.at)) + (it.by.isEmpty ? "" : ", " + it.by))
                        }
                    }.padding(.trailing, 4)
                }
            }
            if n == 0 { Text(t("No visit photos yet. Photos from each site visit show here, grouped by view, so you can compare them over time.", "Bado hakuna picha za ziara. Picha za kila ziara zinaonekana hapa, kwa mwonekano, ili uzilinganishe kwa wakati.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 10) }
        }
        .padding(.top, 10)
        .fullScreenCover(isPresented: Binding(get: { open != nil }, set: { if !$0 { open = nil } })) {
            if let o = open, o.0 < groups.count { PhotoViewer(g: groups[o.0], start: o.1) { open = nil } }
        }
    }
}

/// Full screen, one slot: swipe or tap the arrows; left is newer, right is older.
struct PhotoViewer: View {
    let g: TlGroup; let start: Int; let close: () -> Void
    @State private var i = 0
    var body: some View {
        let it = g.items[min(i, g.items.count - 1)]
        VStack(spacing: 0) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(g.title).font(.tMedium).foregroundColor(.white)
                    Text(it.main ? t("Main photo, on the website", "Picha kuu, kwenye tovuti") : prjDay(it.at) + (it.by.isEmpty ? "" : " · " + it.by)).font(.bSmall).foregroundColor(LightBlue)
                }
                Spacer()
                roundBtn("x", t("Close", "Funga"), true, close)
            }.padding(.leading, 18).padding(.trailing, 10).padding(.top, 14).padding(.bottom, 8)
            TabView(selection: $i) {
                ForEach(Array(g.items.enumerated()), id: \.offset) { k, x in
                    NetImage(url: x.url) { Icon("camera", size: 28, color: .white.opacity(0.6)) }.scaledToFit().frame(maxWidth: .infinity, maxHeight: .infinity).padding(.horizontal, 8).tag(k)
                }
            }.tabViewStyle(.page(indexDisplayMode: .never))
            HStack(spacing: 22) {
                roundBtn("back", t("Newer photo", "Picha mpya zaidi"), i > 0) { withAnimation { i -= 1 } }
                Text("\(i + 1) / \(g.items.count)").font(.tSmall).foregroundColor(.white)
                roundBtn("chev", t("Older photo", "Picha ya zamani zaidi"), i < g.items.count - 1) { withAnimation { i += 1 } }
            }.padding(.top, 12).padding(.bottom, 22)
        }
        .background(Color(hex: 0x040C14, alpha: 0.95).ignoresSafeArea())
        .onAppear { i = max(0, min(start, g.items.count - 1)) }
    }
    private func roundBtn(_ icon: String, _ label: String, _ on: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) { Icon(icon, size: 24, color: .white.opacity(on ? 1 : 0.3)).frame(width: 52, height: 52).background(Color.white.opacity(0.16)).clipShape(Circle()) }
            .buttonStyle(.plain).disabled(!on).accessibilityLabel(label)
    }
}

// MARK: - the donor-ready summary: only what we have, never made up

struct SumFact { var label: String; var value: String; var icon: String? = nil }
struct SumPhoto { var url: String; var caption: String }
struct ProjectSummary {
    var title: String; var sub: String; var photos: [SumPhoto]; var people: Int?; var peopleLabel: String; var peopleAt: String
    var quote: String?; var quoteBy: String; var facts: [SumFact]; var by: String; var date: String
    var fileName: String { "RAFIKI-" + String(title.replacingOccurrences(of: "[^A-Za-z0-9]+", with: "-", options: .regularExpression).trimmingCharacters(in: CharacterSet(charactersIn: "-")).prefix(60)) + "-summary.pdf" }
}

private let NO_DONOR_SLOTS: Set<String> = ["damage", "strip", "issue", "work"]
private let PREF = ["site", "flow", "people", "outside", "progress", "farmer", "animals", "inside", "dorm", "equip", "desks", "close", "kitchen", "washrooms", "pen", "feed", "supplies", "materials"]
private let PPL: [(String, String, (String, String))] = [
    ("users", #"people using it \(estimate\): ([\d,]+)"#, ("people use it (field estimate)", "watu wanautumia (makadirio ya uwanjani)")),
    ("pupils", #"pupils enrolled: ([\d,]+)"#, ("pupils enrolled", "wanafunzi waliosajiliwa")),
    ("patients", #"patients a week: ([\d,]+)"#, ("patients a week", "wagonjwa kwa wiki")),
    ("children", #"children living there: ([\d,]+)"#, ("children live there", "watoto wanaishi hapo")),
]
private func firstGroup(_ pattern: String, _ text: String, _ g: Int = 1, ci: Bool = true) -> String? {
    guard let re = try? NSRegularExpression(pattern: pattern, options: ci ? .caseInsensitive : []), let mm = re.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
          mm.range(at: g).location != NSNotFound, let r = Range(mm.range(at: g), in: text) else { return nil }
    return String(text[r])
}

/// Builds the summary from the project, its notes in monday.com and the last visits the server keeps (newest first).
func buildSummary(_ p: Project, _ s: Snapshot, _ notes: [Note], _ visits: [JSON], mainUrl: String?, by: String) -> ProjectSummary {
    let groups = photoGroups(notes, p, mainUrl: mainUrl)
    var photos: [SumPhoto] = []
    if let mg = groups.first(where: { $0.key == "main" }) { photos.append(SumPhoto(url: mg.items[0].url, caption: t("RAFIKI project photo", "Picha ya mradi wa RAFIKI"))) }
    for g in groups.filter({ $0.key != "main" && !NO_DONOR_SLOTS.contains($0.slot) && $0.key.range(of: "damage|broken|strip|wrong", options: .regularExpression) == nil })
        .sorted(by: { ($0.key == "other" ? 98 : (PREF.firstIndex(of: $0.slot) ?? 60)) < ($1.key == "other" ? 98 : (PREF.firstIndex(of: $1.slot) ?? 60)) }) where photos.count < 3 {
        photos.append(SumPhoto(url: g.items[0].url, caption: g.title + (g.items[0].at.isEmpty ? "" : " · " + prjDay(g.items[0].at))))
    }
    var people: Int?; var pLabel = ""; var pAt = ""
    for (k, re, lbl) in PPL {
        let label = k == "users" && isWater(p) ? t("people use this water (field estimate)", "watu wanatumia maji haya (makadirio ya uwanjani)") : t(lbl.0, lbl.1)
        if let v = visits.first(where: { ($0.obj("readings")?.num(k)) != nil || ($0.obj("numbers")?.num(k)) != nil }) {
            people = Int(v.obj("readings")?.num(k) ?? v.obj("numbers")?.num(k) ?? 0); pLabel = label; pAt = v.str("at"); break
        }
        if let n = notes.first(where: { firstGroup(re, $0.text) != nil }), let num = Int(firstGroup(re, n.text)!.replacingOccurrences(of: ",", with: "")) { people = num; pLabel = label; pAt = n.at; break }
    }
    let storyRe = #"Story moment: “([^”]+)”(?: \(([^)]+)\))?\. Consent to quote and photograph: given"#
    let storyNote = notes.first { firstGroup(storyRe, $0.text, ci: false) != nil }
    var facts: [SumFact] = []
    if !p.type.isEmpty { facts.append(SumFact(label: t("Type", "Aina"), value: typeName(p.type))) }
    let where_ = [p.area, p.country].filter { !$0.isEmpty }.joined(separator: ", ")
    if !where_.isEmpty { facts.append(SumFact(label: t("Location", "Mahali"), value: where_)) }
    let pr = [statusName(p.status), p.year.map(String.init) ?? ""].filter { !$0.isEmpty }.joined(separator: " · ")
    if !pr.isEmpty { facts.append(SumFact(label: t("Project", "Mradi"), value: pr)) }
    if let v = s.visits[p.id] { facts.append(SumFact(label: t("Last visit", "Ziara ya mwisho"), value: prjDay(v.at) + " · " + stateName(v.state), icon: ["working", "repair", "notworking"].contains(v.state) ? v.state : nil)) }
    if let tst = s.tests[p.id] { facts.append(SumFact(label: t("Water last tested", "Maji yalipimwa mwisho"), value: prjDay(tst))) }
    if let r = s.repairs[p.id] {
        facts.append(SumFact(label: t("Open repair", "Matengenezo yaliyo wazi"), value: stateName(r.state) + ", " + t("reported ", "iliripotiwa ") + prjDay(r.at) + (r.problems.isEmpty ? "" : ": " + r.problems.map(problemName).joined(separator: ", ")),
                             icon: r.state == "notworking" ? "notworking" : "repair"))
    }
    return ProjectSummary(title: p.title, sub: [typeName(p.type), where_].filter { !$0.isEmpty }.joined(separator: " · ") + " · RAFIKI Outreach", photos: photos, people: people, peopleLabel: pLabel, peopleAt: pAt,
                          quote: storyNote.flatMap { firstGroup(storyRe, $0.text, ci: false) }?.trimmingCharacters(in: .whitespaces),
                          quoteBy: storyNote.flatMap { firstGroup(storyRe, $0.text, 2, ci: false) }?.trimmingCharacters(in: .whitespaces) ?? "", facts: facts, by: by, date: Dates.day(Date(), year: true))
}

/// Gathers the notes, visits and photos, writes the PDF and opens the share sheet. Returns a message if something went wrong.
@MainActor func shareSummary(_ m: AppModel, _ p: Project) async -> String? {
    guard let snap = m.snap else { return nil }
    let notes = (await m.history(p.id)).0 ?? []
    let last = await m.api.last(p.id)
    let visits = last.ok ? last.json.objs("visits") : []
    var sum = buildSummary(p, snap, notes, visits, mainUrl: p.photo.map { m.api.photoUrl($0, w: 1280) }, by: m.prefs.name)
    var imgs: [UIImage] = []; var kept: [SumPhoto] = []
    for ph in sum.photos { if let im = await Pics.shared.load(ph.url) { imgs.append(im); kept.append(ph) } }
    sum.photos = kept
    let home = snap.me.home.isEmpty ? m.prefs.country : snap.me.home
    let data = summaryPdf(sum, home: home, photos: imgs)
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(sum.fileName)
    do { try data.write(to: url) } catch { return t("Couldn’t make the PDF on this phone.", "Haikuweza kutengeneza PDF kwenye simu hii.") }
    Share.items([url])
    return nil
}

/// The summary on the RAFIKI letterhead: title, up to 3 photos, people served, the story, then the facts.
func summaryPdf(_ s: ProjectSummary, home: String, photos: [UIImage]) -> Data {
    let SM: CGFloat = 43.2, SCW: CGFloat = 595 - 2 * 43.2, BOTTOM: CGFloat = 790
    func f(_ size: CGFloat, _ w: UIFont.Weight, _ hex: UInt32, italic: Bool = false) -> [NSAttributedString.Key: Any] {
        var font = UIFont(name: w.rawValue >= UIFont.Weight.bold.rawValue ? "Montserrat-Bold" : "Montserrat-Medium", size: size) ?? UIFont.systemFont(ofSize: size, weight: w)
        if italic, let d = font.fontDescriptor.withSymbolicTraits(.traitItalic) { font = UIFont(descriptor: d, size: size) }
        return [.font: font, .foregroundColor: UIColor(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: 1)]
    }
    func width(_ t: String, _ a: [NSAttributedString.Key: Any]) -> CGFloat { NSAttributedString(string: t, attributes: a).size().width }
    func fit(_ t: String, _ a: [NSAttributedString.Key: Any], _ w: CGFloat) -> String {
        if width(t, a) <= w { return t }
        var e = t; while !e.isEmpty && width(e.trimmingCharacters(in: .whitespaces) + "…", a) > w { e.removeLast() }
        return e.trimmingCharacters(in: .whitespaces) + "…"
    }
    func wrap(_ t: String, _ a: [NSAttributedString.Key: Any], _ w: CGFloat, _ max: Int) -> [String] {
        var out: [String] = []; var cur = ""
        for word in t.split(separator: " ").map(String.init) { let n = cur.isEmpty ? word : cur + " " + word; if cur.isEmpty || width(n, a) <= w { cur = n } else { out.append(cur); cur = word } }
        if !cur.isEmpty { out.append(cur) }
        if out.count > max { return Array(out.prefix(max - 1)) + [fit(out.dropFirst(max - 1).joined(separator: " "), a, w)] }
        return out.map { fit($0, a, w) }
    }
    func draw(_ t: String, _ x: CGFloat, _ baseline: CGFloat, _ a: [NSAttributedString.Key: Any]) {
        let font = a[.font] as! UIFont
        NSAttributedString(string: t, attributes: a).draw(at: CGPoint(x: x, y: baseline - font.ascender))
    }
    return UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 595, height: 842)).pdfData { ctx in
        ctx.beginPage(); drawLetterheadTop(home)
        var y: CGFloat = 124
        let title = f(19, .bold, 0x04263F), sub = f(9.5, .medium, 0x5A6066)
        draw(fit(s.title, title, SCW), SM, y + 17, title); draw(fit(s.sub, sub, SCW), SM, y + 33, sub); y += 46
        let n = min(photos.count, s.photos.count)
        if n > 0 {
            let gap: CGFloat = 8, w = (SCW - CGFloat(n - 1) * gap) / CGFloat(n), h = n == 1 ? SCW * 9 / 16 : w * 3 / 4
            let cap = f(7.5, .medium, 0x5A6066)
            for i in 0..<n {
                let r = CGRect(x: SM + CGFloat(i) * (w + gap), y: y, width: w, height: h), b = photos[i]
                let sc = max(r.width / b.size.width, r.height / b.size.height)
                ctx.cgContext.saveGState(); UIBezierPath(roundedRect: r, cornerRadius: 6).addClip()
                b.draw(in: CGRect(x: r.midX - b.size.width * sc / 2, y: r.midY - b.size.height * sc / 2, width: b.size.width * sc, height: b.size.height * sc))
                ctx.cgContext.restoreGState()
                draw(fit(s.photos[i].caption, cap, w), r.minX, y + h + 10, cap)
            }
            y += h + 22
        }
        if let ppl = s.people {
            let big = f(24, .heavy, 0x076DB1), lab = f(10.5, .bold, 0x04263F), at = f(8, .medium, 0x5A6066)
            UIColor(red: 0xE6 / 255, green: 0xF2 / 255, blue: 0xFB / 255, alpha: 1).setFill(); UIBezierPath(roundedRect: CGRect(x: SM, y: y, width: SCW, height: 46), cornerRadius: 9).fill()
            let num = Geo.grouped(ppl); draw(num, SM + 14, y + 32, big)
            let x = SM + 14 + width(num, big) + 12
            draw(fit(s.peopleLabel, lab, SM + SCW - x - 10), x, y + (s.peopleAt.isEmpty ? 27 : 21), lab)
            if !s.peopleAt.isEmpty { draw(t("Counted at the visit on ", "Ilihesabiwa kwenye ziara ya ") + prjDay(s.peopleAt), x, y + 34, at) }
            y += 58
        }
        if let q = s.quote {
            let qp = f(11.5, .medium, 0x141618, italic: true), wp = f(8.5, .bold, 0x5A6066)
            let lines = wrap("“\(q)”", qp, SCW - 30, 6); let h = 14 + CGFloat(lines.count) * 15 + (s.quoteBy.isEmpty ? 0 : 14) + 6
            UIColor(red: 1, green: 0xF7 / 255, blue: 0xE8 / 255, alpha: 1).setFill(); UIBezierPath(roundedRect: CGRect(x: SM, y: y, width: SCW, height: h), cornerRadius: 8).fill()
            UIColor(red: 0xF5 / 255, green: 0xA6 / 255, blue: 0x23 / 255, alpha: 1).setFill(); UIRectFill(CGRect(x: SM, y: y, width: 3.5, height: h))
            for (i, l) in lines.enumerated() { draw(l, SM + 16, y + 22 + CGFloat(i) * 15, qp) }
            if !s.quoteBy.isEmpty { draw(fit("— " + s.quoteBy, wp, SCW - 30), SM + 16, y + 22 + CGFloat(lines.count) * 15 + 4, wp) }
            y += h + 14
        }
        if !s.facts.isEmpty {
            let hp = f(12.5, .bold, 0x076DB1); draw(t("About the project", "Kuhusu mradi"), SM, y + 12.5, hp); y += 24
            let lp = f(9, .bold, 0x5A6066), vp = f(9, .medium, 0x141618), lw: CGFloat = 112
            for fct in s.facts {
                let vx = SM + lw + (fct.icon != nil ? 12 : 0)
                let lines = wrap(fct.value, vp, SM + SCW - vx, 3)
                if y + CGFloat(lines.count) * 12.5 > BOTTOM - 40 { continue }
                draw(fit(fct.label, lp, lw - 8), SM, y + 9, lp)
                if let ic = fct.icon {
                    (ic == "working" ? UIColor(red: 0.11, green: 0.61, blue: 0.35, alpha: 1) : ic == "repair" ? UIColor(red: 0.85, green: 0.51, blue: 0, alpha: 1) : UIColor(red: 0.7, green: 0.15, blue: 0.12, alpha: 1)).setFill()
                    UIBezierPath(ovalIn: CGRect(x: SM + lw, y: y + 1.2, width: 8.5, height: 8.5)).fill()
                }
                for (i, l) in lines.enumerated() { draw(l, vx, y + 9 + CGFloat(i) * 12.5, vp) }
                y += CGFloat(lines.count) * 12.5 + 5
            }
        }
        let note = f(8, .medium, 0x5A6066)
        let foot = t("Prepared by ", "Imeandaliwa na ") + s.by + ", " + s.date + ". " + t("From RAFIKI’s own field visits (RAFIKI Pin app and monday.com).", "Kutoka kwa ziara za RAFIKI zenyewe (programu ya RAFIKI Pin na monday.com).") +
            (s.quote != nil ? " " + t("The person quoted agreed to be quoted and photographed.", "Aliyenukuliwa alikubali kunukuliwa na kupigwa picha.") : "")
        for (i, l) in wrap(foot, note, SCW, 3).enumerated() { draw(l, SM, min(y + 16, BOTTOM - 30) + CGFloat(i) * 11, note) }
        drawLetterheadFoot()
    }
}

/// A project type as monday.com names it, in the app's language.
func typeName(_ type: String) -> String {
    let ty = type.lowercased()
    let sw: String? = ty.contains("borehole") ? "Kisima kirefu" : ty.contains("collection") ? "Ukusanyaji wa maji" : ty.contains("school") ? "Shule" : ty.contains("health") ? "Afya"
        : (ty.contains("orphan") || ty.contains("children")) ? "Makao ya watoto" : ty.contains("farm") ? "Kilimo" : ty == "community" ? "Jamii" : nil
    return sw.map { t(type, $0) } ?? type
}

/// Under More on the project page: the photo timeline, and a one-page summary for donors.
struct ProjectExtrasBar: View {
    @EnvironmentObject var m: AppModel
    let p: Project; let showTimeline: () -> Void
    @State private var busy = false
    @State private var msg: String?
    var body: some View {
        VStack(spacing: 8) {
            Btn(t("Photo timeline", "Picha kwa wakati"), icon: "camera", light: true, small: true, wide: true, action: showTimeline)
            Btn(busy ? t("Preparing…", "Inaandaa…") : t("Share project summary", "Shiriki muhtasari wa mradi"), icon: "share", enabled: !busy, light: true, small: true, wide: true) {
                busy = true; msg = nil
                Task { msg = await shareSummary(m, p); busy = false }
            }
            Msg(msg, "bad")
        }.padding(.top, 2)
    }
}

// MARK: - repair money: the second approval, parts ordered, actual cost and receipts

/// The second approval, in one line: on the Office job card and the repair tracker.
struct ApprovalNote: View {
    let r: Repair; let limit: Int
    var body: some View {
        let st = Money.state(r, limit)
        if st != "none" {
            let a = r.approval, est = Money.kes(Money.estOf(r)), lim = Money.kes(limit)
            let (title, sub, look): (String, String, String) = {
                switch st {
                case "pending": return (t("Waiting for a second approval", "Inasubiri idhini ya pili") + " · " + t("asked by ", "imeombwa na ") + (a?.by ?? "—"),
                                        t("\(est) is above \(lim), so another admin approves it before parts are ordered.", "\(est) ni zaidi ya \(lim), kwa hiyo msimamizi mwingine aidhinishe kabla vipuri havijaagizwa."), "purple")
                case "approved": return (t("Cost approved by ", "Gharama imeidhinishwa na ") + (a?.dec ?? "—"), est + ((a?.note ?? "").isEmpty ? "" : " · “\(a!.note)”") + " · " + t("asked by ", "imeombwa na ") + (a?.by ?? "—"), "green")
                case "declined": return (t("Cost declined by ", "Gharama imekataliwa na ") + (a?.dec ?? "—"), ((a?.note ?? "").isEmpty ? "" : "“\(a!.note)” · ") + t("Ask again with a new estimate or budget.", "Omba tena kwa makadirio au bajeti mpya."), "coral")
                default: return (t("Needs a second approval before parts are ordered", "Inahitaji idhini ya pili kabla ya kuagiza vipuri"), t("\(est) is above \(lim). Choosing “Parts ordered” asks another admin.", "\(est) ni zaidi ya \(lim). Ukichagua “Vipuri vimeagizwa” msimamizi mwingine ataombwa."), "amber")
                }
            }()
            let hu = C.named(look)
            HStack(alignment: .top, spacing: 10) {
                Icon(st == "pending" ? "hourglass" : st == "declined" ? "xc" : "shield", size: 20, color: hu.fg)
                VStack(alignment: .leading, spacing: 2) { Text(title).font(.tSmall).foregroundColor(C.ink); Text(sub).font(.bSmall).foregroundColor(C.muted) }
                Spacer(minLength: 0)
            }.padding(12).background(hu.bg).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(C.sun ? Color.black : .clear, lineWidth: 2))
        }
    }
}

/// The receipt photo: one photo box (camera or gallery), kept on the phone until sent.
struct ReceiptPick: View {
    @Binding var shots: [Shot]
    var body: some View { PhotoSlots(slots: [Slot(label: "Receipt", title: t("Receipt photo", "Picha ya risiti"), hint: t("Optional", "Si lazima"))], shots: $shots, max: 0) }
}

/// A still map around a point, for the repair card.
struct StillMap: UIViewRepresentable {
    let ll: LatLng; let color: UIColor
    func makeUIView(context: Context) -> MKMapView {
        let mv = MKMapView(); mv.isUserInteractionEnabled = false; mv.showsCompass = false; mv.pointOfInterestFilter = .excludingAll
        let c = CLLocationCoordinate2D(latitude: ll.lat, longitude: ll.lng)
        mv.setRegion(MKCoordinateRegion(center: c, latitudinalMeters: 1600, longitudinalMeters: 1600), animated: false)
        let a = MKPointAnnotation(); a.coordinate = c; mv.addAnnotation(a)
        mv.delegate = context.coordinator
        return mv
    }
    func updateUIView(_ mv: MKMapView, context: Context) {}
    func makeCoordinator() -> Coord { Coord(color) }
    final class Coord: NSObject, MKMapViewDelegate {
        let color: UIColor; init(_ c: UIColor) { color = c }
        func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
            let v = MKAnnotationView(annotation: annotation, reuseIdentifier: nil)
            v.image = UIGraphicsImageRenderer(size: CGSize(width: 26, height: 26)).image { _ in
                UIColor.white.setFill(); UIBezierPath(ovalIn: CGRect(x: 0, y: 0, width: 26, height: 26)).fill()
                color.setFill(); UIBezierPath(ovalIn: CGRect(x: 3.5, y: 3.5, width: 19, height: 19)).fill()
            }
            return v
        }
    }
}

/// An open repair: where it is, who has it, and how far the job has got.
struct RepairTracker: View {
    @EnvironmentObject var m: AppModel
    let p: Project; let r: Repair; let here: LatLng?
    @State private var msg: String?
    @State private var busy = false
    @State private var fixed = false
    @State private var fixCost = ""
    @State private var fixShots: [Shot] = []
    @State private var showFix = false
    @State private var pulse = false
    var body: some View {
        let bad = r.state == "notworking"
        let order = ["reported", "assigned", "parts", "fixed"]
        let si = fixed ? 3 : max(r.assignee.isEmpty ? 0 : 1, order.firstIndex(of: r.step) ?? 0)
        let d = here.flatMap { h in p.gps.map { Geo.metres(h, $0) } }
        let steps: [(String, String)] = [
            (t("Reported", "Imeripotiwa"), (bad ? t("Not working", "Haifanyi kazi") : t("Needs repair", "Inahitaji matengenezo")) + "\u{00A0}· " + r.by + ", " + Dates.dateTime(r.at)),
            (t("Assigned", "Imegawiwa"), r.assignee.isEmpty ? t("Waiting for the office", "Inasubiri ofisi") : r.assignee + (r.stepAt["assigned"].map { (x: String) in ", " + Dates.dateTime(x) } ?? "")),
            (t("Parts ordered", "Vipuri vimeagizwa"), r.stepAt["parts"].map { Dates.dateTime($0) } ?? (r.approval?.status == "pending" ? t("Waiting for a second approval · asked by ", "Inasubiri idhini ya pili · imeombwa na ") + (r.approval?.by ?? "") : r.parts.isEmpty ? "—" : t("Needs: ", "Inahitaji: ") + r.parts)),
            (t("Fixed", "Imerekebishwa"), fixed ? m.prefs.name : isWater(p) ? t("Mark it fixed when the water is running again", "Weka imerekebishwa maji yakitoka tena") : t("Mark it fixed when it’s working again", "Weka imerekebishwa ikifanya kazi tena")),
        ]
        CardV {
            HStack {
                Text(t("Repair job", "Kazi ya matengenezo")).font(.tMedium).foregroundColor(C.ink)
                Spacer()
                if fixed { Tag(t("Fixed ✓", "Imerekebishwa ✓"), bg: C.okBg, fg: C.ok) }
                else { Tag(r.urgency == "month" ? t("Fix this month", "Rekebisha mwezi huu") : (r.urgency == "urgent" ? urgencyName(r.urgency) + " · " : "") + (bad ? t("Not working", "Haifanyi kazi") : t("Needs repair", "Inahitaji matengenezo")), bg: C.badBg, fg: C.bad) }
            }
            if let g = p.gps, !fixed {
                ZStack(alignment: .topLeading) {
                    StillMap(ll: g, color: bad ? UIColor(red: 0.70, green: 0.15, blue: 0.12, alpha: 1) : UIColor(red: 0.85, green: 0.51, blue: 0, alpha: 1))
                    if let d {
                        HStack(spacing: 6) { Circle().fill(GreenC).frame(width: 7, height: 7); Text(Geo.dist(d, miles: m.miles) + " · " + t("Live", "Moja kwa moja")).font(.mont(12, .heavy)).foregroundColor(Color(hex: 0x04263F)) }
                            .padding(.horizontal, 10).padding(.vertical, 5).background(Color.white.opacity(0.85)).clipShape(Capsule()).padding(10)
                    }
                    VStack {
                        Spacer()
                        HStack(spacing: 10) {
                            Text(r.assignee.isEmpty ? "?" : initialsOf(r.assignee)).font(.mont(12, .heavy)).foregroundColor(.white).frame(width: 34, height: 34)
                                .background(LinearGradient(colors: [Color(hex: 0x47B4F5), Color(hex: 0x0A78C2)], startPoint: .topLeading, endPoint: .bottomTrailing)).clipShape(Circle())
                            VStack(alignment: .leading, spacing: 1) {
                                Text(r.assignee.isEmpty ? t("Not assigned yet", "Bado hajapewa mtu") : r.assignee).font(.mont(14, .bold)).foregroundColor(Color(hex: 0x04263F))
                                Text(!r.due.isEmpty ? t("Due ", "Mwisho ") + Dates.day(r.due) : !r.assignee.isEmpty ? t("Repair job", "Kazi ya matengenezo") : t("The office assigns it", "Ofisi itamgawia mtu")).font(.mont(12)).foregroundColor(Color(hex: 0x5A6066))
                            }
                            Spacer()
                        }.padding(8).background(Color.white.opacity(0.82)).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).padding(8)
                            .onTapGesture { if !r.assignee.isEmpty { Contact.show(r.assignee) } }
                    }
                }.frame(height: 150).clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous)).padding(.top, 10)
            }
            VStack(alignment: .leading, spacing: 0) {
                ForEach(steps.indices, id: \.self) { i in
                    let done = i < si || fixed, now = i == si && !fixed
                    HStack(alignment: .top, spacing: 12) {
                        ZStack(alignment: .top) {
                            if i < steps.count - 1 { Rectangle().fill(done ? C.ok : C.line).frame(width: 2).padding(.top, 28) }
                            ZStack {
                                if now && Motion.on { Circle().fill(C.ok.opacity(pulse ? 0 : 0.45)).frame(width: pulse ? 48 : 28, height: pulse ? 48 : 28) }
                                Circle().fill(done || now ? C.ok : C.line).frame(width: 28, height: 28)
                                if done || now { Icon("check", size: 15, color: .white) }
                            }.frame(width: 28, height: 28)
                        }.frame(width: 28)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(steps[i].0).font(.tSmall).foregroundColor(done || now ? C.ink : C.muted)
                            Text(steps[i].1).font(.bSmall).foregroundColor(C.muted)
                        }.padding(.bottom, 14)
                        Spacer(minLength: 0)
                    }.fixedSize(horizontal: false, vertical: true).rise(i + 1, step: 0.11)
                }
            }.padding(.top, 14)
            if !fixed {
                if !r.note.isEmpty || !r.problems.isEmpty || r.cost != nil {
                    VStack(alignment: .leading, spacing: 2) {
                        if !r.note.isEmpty { Text("“\(r.note)”").font(.bMedium).italic().foregroundColor(C.text) }
                        if !r.problems.isEmpty { Text(t("Problems: ", "Matatizo: ") + r.problems.map(problemName).joined(separator: ", ")).font(.bSmall).foregroundColor(C.muted) }
                        if let c = r.cost { Text(t("About ", "Takriban ") + Money.kes(c)).font(.bSmall).foregroundColor(C.muted) }
                    }.padding(.horizontal, 14).padding(.vertical, 12).wide().background(C.dark ? Color.white.opacity(0.08) : Color(hex: 0xF2F6F9)).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                if let sn = m.snap { TrackerMoneyBox(s: sn, p: p, r: r) }
                if !showFix {
                    Button { showFix = true } label: {
                        HStack(spacing: 8) { Icon("coins", size: 18, color: C.blue2); Text(t("Add what the repair cost (and a receipt)", "Ongeza gharama ya matengenezo (na risiti)")).font(.tSmall).foregroundColor(C.blue2) }.padding(.vertical, 8).padding(.horizontal, 2)
                    }.buttonStyle(.plain).padding(.top, 10)
                } else {
                    Field(t("Actual cost when fixed (optional)", "Gharama halisi ikirekebishwa (si lazima)"), Binding(get: { fixCost }, set: { fixCost = $0.filter(\.isNumber) }), placeholder: t("what the repair cost", "gharama ya matengenezo"), number: true)
                    ReceiptPick(shots: $fixShots).padding(.top, 8)
                }
                Btn(t("Mark fixed", "Weka imerekebishwa"), icon: "wrench", enabled: !busy, ok: true, wide: true) {
                    busy = true
                    Task {
                        var b: JSON = ["action": "fixed", "id": p.id, "note": ""]
                        if let c = Money.parse(fixCost) { b["actual"] = c }
                        if !fixShots.isEmpty { b["receipt"] = true }
                        let out = await m.submit(b, fixShots.map { OutFile(path: $0.path, kind: "photo", label: "receipt") })
                        var queued = false
                        if case .queued = out { queued = true }
                        switch out {
                        case .sent, .queued:
                            withAnimation { fixed = true }; Haptic.ok()
                            msg = queued ? t("Saved on this phone. It will be sent when you have signal.", "Imehifadhiwa kwenye simu. Itatumwa ukipata mtandao.") : nil
                            try? await Task.sleep(nanoseconds: 4_000_000_000); m.localFixed(p)
                        case .failed(let s): msg = s
                        case .locked: break
                        }
                        busy = false
                    }
                }.padding(.top, 12)
            }
            Msg(msg, "warn")
        }
        .padding(.top, 12)
        .onAppear { if Motion.on { withAnimation(.easeOut(duration: 1.8).repeatForever(autoreverses: false)) { pulse = true } } }
    }
}

/// The repair tracker: the second approval, "Parts ordered" for the person on the job (or an admin).
struct TrackerMoneyBox: View {
    @EnvironmentObject var m: AppModel
    let s: Snapshot; let p: Project; let r: Repair
    @State private var open = false
    @State private var cost = ""
    @State private var what = ""
    @State private var shots: [Shot] = []
    @State private var msg: (String, String)?
    @State private var busy = false
    var body: some View {
        let st = Money.state(r, s.approveAbove)
        let mine = s.admin || s.jobs.contains(p.id) || r.assignee.trimmingCharacters(in: .whitespaces).lowercased() == m.prefs.name.trimmingCharacters(in: .whitespaces).lowercased()
        VStack(alignment: .leading, spacing: 0) {
            ApprovalNote(r: r, limit: s.approveAbove).padding(.top, 10)
            if mine && !Money.past(r.step, "parts") && st != "pending" {
                let ask = st == "needed" || st == "declined"
                if open && !ask {
                    Field(t("Actual cost (optional)", "Gharama halisi (si lazima)"), Binding(get: { cost }, set: { cost = $0.filter(\.isNumber) }), placeholder: t("what the parts cost", "bei ya vipuri"), number: true)
                    Field(t("What was ordered? (optional)", "Nini kimeagizwa? (si lazima)"), $what, placeholder: r.parts.isEmpty ? t("e.g. 6 rising main pipes", "mf. mabomba 6") : r.parts)
                    ReceiptPick(shots: $shots).padding(.top, 8)
                }
                Btn(ask ? t("Parts ordered: ask for approval", "Vipuri vimeagizwa: omba idhini") : open ? t("Save: parts ordered", "Hifadhi: vipuri vimeagizwa") : t("Parts ordered", "Vipuri vimeagizwa"), icon: "box", enabled: !busy, light: true, wide: true) {
                    if !ask && !open { open = true; return }
                    busy = true
                    Task {
                        var b: JSON = ["action": "parts", "id": p.id]
                        if !ask { if let c = Money.parse(cost) { b["actual"] = c }; if !what.isEmpty { b["what"] = what.trimmingCharacters(in: .whitespaces) }; if !shots.isEmpty { b["receipt"] = true } }
                        switch await m.submit(b, ask ? [] : shots.map { OutFile(path: $0.path, kind: "photo", label: "receipt") }) {
                        case .sent(let reply, _):
                            let held = !reply.str("held").isEmpty
                            msg = held ? ("warn", reply.bool("asked") ? t("Sent for a second approval ✓ Parts can be ordered once another admin approves the cost.", "Imetumwa kwa idhini ya pili ✓ Vipuri vitaagizwa msimamizi mwingine akiidhinisha.") : t("Still waiting for a second approval.", "Bado inasubiri idhini ya pili."))
                                       : ("ok", t("Parts ordered ✓", "Vipuri vimeagizwa ✓"))
                            open = false; cost = ""; what = ""; shots = []; m.reload()
                        case .queued: msg = ("warn", t("Saved on this phone. It will be sent when you have signal.", "Imehifadhiwa kwenye simu. Itatumwa ukipata mtandao."))
                        case .failed(let x): msg = ("bad", x)
                        case .locked: break
                        }
                        busy = false
                    }
                }.padding(.top, 10)
            }
            if let msg { Msg(msg.1, msg.0) }
        }
    }
}

/// "Repairs: KES N in the last 12 months (3 repairs)" on a project.
struct ProjectCostLine: View {
    @EnvironmentObject var m: AppModel
    let p: Project
    @State private var c: JSON?
    var body: some View {
        Group {
            if let j = c {
                let sum = j.int("sum"), n = j.int("n"), last = j.objs("last").first
                HStack(spacing: 12) {
                    IconTile(icon: "coins", tint: C.named("amber").fg, bg: C.named("amber").bg, size: 40)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(n > 0 ? t("Repairs: \(Money.kes(sum)) in the last 12 months (\(n) \(n == 1 ? "repair" : "repairs"))", "Matengenezo: \(Money.kes(sum)) katika miezi 12 iliyopita (matengenezo \(n))") : t("Repairs: nothing spent in the last 12 months", "Matengenezo: hakuna kilichotumika katika miezi 12 iliyopita"))
                            .font(.tSmall).foregroundColor(C.ink)
                        if let l = last { Text(t("Latest: ", "Mpya zaidi: ") + [Money.kes(l.int("actual")), l.str("what"), l.str("by"), Dates.dateTime(l.str("at"))].filter { !$0.isEmpty }.joined(separator: " · ")).font(.bSmall).foregroundColor(C.muted) }
                    }
                    Spacer(minLength: 0)
                }
                .padding(12).background(C.card).clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(C.line, lineWidth: C.sun ? 2 : 1)).padding(.top, 12)
            }
        }.task(id: p.id) { let x = await m.api.last(p.id); if x.ok { c = x.json.obj("costs") } }
    }
}

// MARK: - Thank-you for donors: a photo or short video (≤ 60 s) from the community

let THANKS_MAX_SECONDS = 60
func thanksCheck(_ mime: String, _ size: Int, _ seconds: Int) -> String? {
    if !(mime.hasPrefix("image/") || mime.hasPrefix("video/")) { return "type" }
    if mime.hasPrefix("video/") && seconds > THANKS_MAX_SECONDS + 1 { return "too-long" }
    if size > (mime.hasPrefix("video/") ? 40 : 8) * 1024 * 1024 { return "too-large" }
    return nil
}

struct DonorThanks: View {
    @EnvironmentObject var m: AppModel
    let p: Project
    @State private var open = false
    @State private var item: PhotosPickerItem?
    @State private var picked: (Data, String, Int)?
    @State private var caption = ""
    @State private var msg: (String, String)?
    @State private var busy = false
    var body: some View {
        CardV(pad: 14) {
            Button { withAnimation { open.toggle() } } label: {
                HStack(spacing: 12) {
                    Icon("camera", size: 20, color: C.bad).frame(width: 38, height: 38).background(C.badBg).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(t("Thank-you for donors", "Shukrani kwa wafadhili")).font(.tSmall).foregroundColor(C.ink)
                        Text(t("A photo or short video (up to 60 seconds) from the community", "Picha au video fupi (hadi sekunde 60) kutoka kwa jamii")).font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.leading)
                    }
                    Spacer(minLength: 4)
                    Icon(open ? "down" : "chev", size: 20, color: C.muted)
                }.frame(minHeight: 44).contentShape(Rectangle())
            }.buttonStyle(.plain)
            if open {
                Text(t("Ask first: only people who agree to be filmed or photographed. It goes to monday.com and appears on the private pages of this project’s donors.", "Omba ruhusa kwanza: watu waliokubali kupigwa picha au video tu. Inaenda monday.com na kuonekana kwenye kurasa za siri za wafadhili wa mradi huu."))
                    .font(.bSmall).foregroundColor(C.muted).padding(.top, 8)
                PhotosPicker(selection: $item, matching: .any(of: [.images, .videos])) {
                    HStack(spacing: 8) { Icon("camera", size: 18, color: C.ink); Text(t("Choose a photo or video", "Chagua picha au video")).font(.mont(14, .bold)).foregroundColor(C.ink) }
                        .frame(maxWidth: .infinity, minHeight: 44).background(C.card).clipShape(Capsule()).overlay(Capsule().stroke(C.edge, lineWidth: 1))
                }.padding(.top, 8)
                if let pk = picked { Text(t("Chosen: ", "Umechagua: ") + (pk.1.hasPrefix("video/") ? t("a video (\(pk.2) s)", "video (sekunde \(pk.2))") : t("a photo", "picha"))).font(.bSmall).foregroundColor(C.ink).padding(.top, 6) }
                Field(t("A few words for the donors (optional)", "Maneno machache kwa wafadhili (si lazima)"), Binding(get: { caption }, set: { caption = String($0.prefix(140)) }), placeholder: t("e.g. Asante sana from the water committee!", "mf. Asante sana kutoka kamati ya maji!"))
                Btn(busy ? t("Sending…", "Inatuma…") : t("Send to donors", "Tuma kwa wafadhili"), icon: "upload", enabled: picked != nil && !busy, wide: true) { send() }.padding(.top, 10)
                if let msg { Msg(msg.1, msg.0) }
                if m.snap?.admin == true {
                    Btn(t("E-mail this project’s donors", "Tuma barua pepe kwa wafadhili wa mradi huu"), icon: "mail", light: true, small: true, wide: true) {
                        MailNav.preset = MailPreset(aud: "donors", sub: "project", project: p.id); TeamGo.section = "mail"; m.tab("office")
                    }.padding(.top, 10)
                }
            }
        }
        .padding(.top, 12)
        .onChange(of: item) { it in
            guard let it else { return }
            Task {
                let video = it.supportedContentTypes.contains { $0.conforms(to: .movie) }
                guard let data = try? await it.loadTransferable(type: Data.self) else { msg = ("bad", t("Couldn’t read that file.", "Haikuweza kusoma faili hiyo.")); return }
                var secs = 0
                if video {
                    let url = FileManager.default.temporaryDirectory.appendingPathComponent("thanks.mov"); try? data.write(to: url)
                    secs = Int((try? await AVURLAsset(url: url).load(.duration).seconds) ?? 0)
                }
                picked = (data, video ? "video/quicktime" : "image/jpeg", secs); msg = nil
            }
        }
    }
    private func send() {
        guard let (data, mime, secs) = picked else { return }
        if let e = thanksCheck(mime, data.count, secs) {
            msg = ("bad", e == "too-long" ? t("Please keep it to 60 seconds or less.", "Tafadhali isizidi sekunde 60.") : e == "too-large" ? t("That file is too large.", "Faili hiyo ni kubwa mno.") : t("That kind of file can’t be sent.", "Aina hiyo ya faili haiwezi kutumwa.")); return
        }
        busy = true
        Task {
            var d = data
            if mime == "image/jpeg", let img = UIImage(data: data) {
                let k = min(1, 1600 / max(img.size.width, img.size.height)); let size = CGSize(width: img.size.width * k, height: img.size.height * k)
                let f = UIGraphicsImageRendererFormat(); f.scale = 1
                d = UIGraphicsImageRenderer(size: size, format: f).image { _ in img.draw(in: CGRect(origin: .zero, size: size)) }.jpegData(compressionQuality: 0.85) ?? data
            }
            let r = await m.api.thanks(id: p.id, data: d, mime: mime, caption: caption.trimmingCharacters(in: .whitespaces), seconds: secs)
            busy = false
            if r.ok { msg = ("ok", t("Sent ✓ Thank you! The donors will see it on their page.", "Imetumwa ✓ Asante! Wafadhili wataiona kwenye ukurasa wao.")); picked = nil; item = nil; caption = "" }
            else { msg = ("bad", r.status == 0 ? t("This needs a signal. Try again when you’re online.", "Hii inahitaji mtandao. Jaribu tena ukiwa mtandaoni.") : reasonText(r.reason)) }
        }
    }
}

/// Directions: Apple Maps, Google Maps, or the share sheet.
struct DirectionsSheet: View {
    let title: String; let at: LatLng
    var body: some View {
        SubSheet { close in
            SubHead(icon: "compass", hue: "near", title: t("Directions", "Maelekezo"), sub: title)
            let ll = "\(at.lat),\(at.lng)"
            dirRow(t("Apple Maps", "Apple Maps"), t("Turn-by-turn directions", "Maelekezo hatua kwa hatua"), "map") { close(); Share.open("http://maps.apple.com/?daddr=\(ll)&dirflg=d") }
            dirRow("Google Maps", t("Turn-by-turn directions", "Maelekezo hatua kwa hatua"), "map") { close(); Share.open("https://www.google.com/maps/dir/?api=1&destination=\(ll)") }
        }.presentationDetents([.medium])
    }
    private func dirRow(_ title: String, _ sub: String, _ icon: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Icon(icon, size: 21, color: C.blue).frame(width: 40, height: 40).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                VStack(alignment: .leading, spacing: 1) { Text(title).font(.tSmall).foregroundColor(C.ink); Text(sub).font(.bSmall).foregroundColor(C.muted) }
                Spacer(); Icon("chev", size: 18, color: C.muted)
            }.padding(.horizontal, 14).padding(.vertical, 10).frame(minHeight: 64)
                .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(C.edge, lineWidth: 1)).contentShape(Rectangle())
        }.buttonStyle(.plain).padding(.top, 8)
    }
}

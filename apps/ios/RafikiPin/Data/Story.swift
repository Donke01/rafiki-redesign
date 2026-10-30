import Foundation

// Stories (same as Android 2.28 and the web app): every project's story, from the six questions, before / in progress /
// after photos, a quote and photo consent, to a drafted story that an admin approves and publishes on the website.
// The server is /api/story (functions/api/story.js); monday.com's story columns hold the words, photos and statuses.

/// Where a project's story is, as the app's list carries it: status, answers filled, photos per row, consent.
struct StoryBrief: Equatable {
    var st: String; var n = 0; var at = ""; var b = 0; var i = 0; var a = 0; var c = ""

    static func all(_ o: JSON?) -> [String: StoryBrief] {
        var out: [String: StoryBrief] = [:]
        (o ?? [:]).forEach { k, v in
            if let j = v as? JSON { out[k] = StoryBrief(st: j.str("st"), n: j.int("n"), at: j.str("at"), b: j.int("b"), i: j.int("i"), a: j.int("a"), c: j.str("c")) }
        }
        return out
    }
}

/// A story photo: its asset id, whether children are in it, who added it and when, its caption and the day it was taken.
struct StoryPhoto: Equatable { var id: String; var children: Bool; var by: String; var at: String; var cap: String; var taken: String }
/// A photo a story section shows (from story.pool: the story rows and the building stages).
struct DocPhoto: Equatable { var id: String; var row: String; var cap: String; var children: Bool }
/// One section of the drafted story: a heading, its words and maybe a photo.
struct DocSection: Equatable { var h: String; var body: String; var photo: DocPhoto? }

/// The drafted story: a title, the line under it, sections with photos, the quote.
struct StoryDoc: Equatable {
    var title: String; var dek: String; var sections: [DocSection]
    var pullQuote: String; var pullWho: String
    var at: String; var by: String; var model: String

    var hasPull: Bool { !pullQuote.isEmpty }
    var words: Int { ([title, dek] + sections.map { $0.body }).reduce(0) { $0 + wordCount($1) } }

    /// As the server wants it back (action doc / approve).
    func out() -> JSON {
        let secs: [JSON] = sections.map { x in
            var o: JSON = ["h": x.h, "body": x.body]
            if let ph = x.photo { o["photo"] = ["id": ph.id] as JSON } else { o["photo"] = NSNull() }
            return o
        }
        return ["title": title, "dek": dek, "sections": secs]
    }
}

/// Where the story is on the website, once approved and published.
struct StoryPub: Equatable { var at: String; var by: String; var url: String }

/// The month of each column ("YYYY-MM" or ""): Before, In progress from – to, After.
struct StoryDates: Equatable {
    var before = ""; var progress = ""; var progressEnd = ""; var after = ""
    func of(_ row: String) -> String { row == "before" ? before : row == "progress" ? progress : after }
    func value(_ key: String) -> String { key == "progressEnd" ? progressEnd : key == "progress" ? progress : key == "before" ? before : after }
}

struct Story: Equatable {
    var status: String; var consent: String; var answers: [String: String]
    var quote: String; var quoteBy: String; var credit: String; var text: String
    var photos: [String: [StoryPhoto]]; var missing: [String]; var canApprove: Bool
    var prompts: [String: [String]]
    var doc: StoryDoc?; var pool: [DocPhoto]; var pub: StoryPub?; var newPhotos: Int; var dates: StoryDates
    /// The photo chosen as the story's cover, or "" for the automatic choice.
    var cover: String

    var filled: Int { StoryKit.QS.filter { !(answers[$0] ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }.count }
    /// On the website (approved and published).
    var live: Bool { pub != nil || status == "published" }
    /// The photos the drafted story shows.
    var inStory: Set<String> { Set((doc?.sections ?? []).compactMap { $0.photo?.id }) }
    var allPhotos: [StoryPhoto] { StoryKit.ROWS.flatMap { photos[$0] ?? [] } }
    func rowOf(_ asset: String) -> String? { StoryKit.ROWS.first { r in (photos[r] ?? []).contains { $0.id == asset } } }

    private static func s(_ o: JSON, _ k: String) -> String { let v = o.str(k); return v == "null" ? "" : v }
    private static func ym(_ o: JSON, _ k: String) -> String {
        let v = s(o, k); return v.range(of: #"^\d{4}-\d{2}$"#, options: .regularExpression) != nil ? v : ""
    }
    private static func docPhoto(_ o: JSON?) -> DocPhoto? {
        guard let o, !s(o, "id").isEmpty else { return nil }
        return DocPhoto(id: s(o, "id"), row: s(o, "row"), cap: s(o, "cap"), children: o.bool("children"))
    }

    static func from(_ o: JSON?) -> Story? {
        guard let o else { return nil }
        let a = o.obj("answers") ?? [:]
        let ph = o.obj("photos") ?? [:]
        let pr = o.obj("prompts") ?? [:]
        var doc: StoryDoc? = nil
        if let d = o.obj("doc"), d["sections"] is [Any] {
            let secs = d.objs("sections").map { x in DocSection(h: s(x, "h"), body: s(x, "body"), photo: docPhoto(x.obj("photo"))) }
            let pull = d.obj("pull") ?? [:]
            doc = StoryDoc(title: s(d, "title"), dek: s(d, "dek"), sections: secs, pullQuote: s(pull, "quote"), pullWho: s(pull, "who"),
                           at: s(d, "at"), by: s(d, "by"), model: s(d, "model"))
        }
        var pub: StoryPub? = nil
        if let p = o.obj("pub"), !s(p, "at").isEmpty || !s(p, "url").isEmpty { pub = StoryPub(at: s(p, "at"), by: s(p, "by"), url: s(p, "url")) }
        var answers: [String: String] = [:]
        StoryKit.QS.forEach { answers[$0] = s(a, $0) }
        var photos: [String: [StoryPhoto]] = [:]
        StoryKit.ROWS.forEach { r in
            photos[r] = ph.objs(r).map { StoryPhoto(id: s($0, "id"), children: $0.bool("children"), by: s($0, "by"), at: s($0, "at"), cap: s($0, "cap"), taken: s($0, "taken")) }
        }
        var prompts: [String: [String]] = [:]
        pr.forEach { k, v in if let j = v as? JSON { prompts[k] = [s(j, "en"), s(j, "sw")] } }
        let dj = o.obj("dates") ?? [:]
        let st = s(o, "status"), cs = s(o, "consent")
        return Story(status: st.isEmpty ? "not-started" : st, consent: cs.isEmpty ? "not-yet" : cs, answers: answers,
                     quote: s(o, "quote"), quoteBy: s(o, "quoteBy"), credit: s(o, "credit"), text: s(o, "text"),
                     photos: photos, missing: (o.arr("missing") ?? []).compactMap { $0 as? String }, canApprove: o.bool("canApprove"),
                     prompts: prompts, doc: doc, pool: o.objs("pool").compactMap { docPhoto($0) }, pub: pub, newPhotos: o.int("newPhotos"),
                     dates: StoryDates(before: ym(dj, "before"), progress: ym(dj, "progress"), progressEnd: ym(dj, "progressEnd"), after: ym(dj, "after")),
                     cover: s(o, "cover"))
    }
}

func wordCount(_ s: String) -> Int { s.split(whereSeparator: { $0.isWhitespace }).count }

enum StoryKit {
    static let QS = ["q1", "q2", "q3", "q4", "q5", "q6"]
    static let ROWS = ["before", "progress", "after"]

    static func status(_ k: String) -> String {
        switch k {
        case "collecting": return t("Collecting", "Inakusanywa")
        case "draft": return t("Draft ready", "Rasimu iko tayari")
        case "approved": return t("Approved", "Imeidhinishwa")
        case "published": return t("Published", "Imechapishwa")
        default: return t("Not started", "Haijaanza")
        }
    }
    static func row(_ k: String) -> String {
        switch k { case "before": return t("Before", "Kabla"); case "progress": return t("In progress", "Wakati wa kazi"); default: return t("After", "Baada") }
    }
    /// The question's words: the server's own wording if it sent one, else the built-in one.
    static func prompt(_ s: Story?, _ q: String) -> String {
        let b = builtIn(q)
        let p = s?.prompts[q] ?? []
        let en = p.count > 0 && !p[0].isEmpty ? p[0] : b.0
        let sw = p.count > 1 && !p[1].isEmpty ? p[1] : b.1
        return t(en, sw)
    }
    private static func builtIn(_ q: String) -> (String, String) {
        switch q {
        case "q1": return ("What was life like here before the project?", "Maisha yalikuwaje hapa kabla ya mradi?")
        case "q2": return ("What was the biggest problem?", "Tatizo kubwa zaidi lilikuwa nini?")
        case "q3": return ("Who is this story about? (name, age, role)", "Hadithi hii inamhusu nani? (jina, umri, nafasi)")
        case "q4": return ("What part did the community play?", "Jamii ilishiriki vipi?")
        case "q5": return ("What has changed since?", "Nini kimebadilika tangu hapo?")
        case "q6": return ("Describe one moment that shows the difference.", "Eleza tukio moja linaloonyesha tofauti.")
        default: return (q, q)
        }
    }
    static func why(_ r: Reply) -> String {
        switch r.reason {
        case "consent": return t("Photo consent is needed first: children are in a photo.", "Idhini ya picha inahitajika kwanza: kuna watoto kwenye picha.")
        case "no-text": return t("Draft the story first.", "Andika rasimu ya hadithi kwanza.")
        case "not-approved": return t("Approve it before publishing.", "Idhinisha kabla ya kuchapisha.")
        case "admin-only": return t("Only admins can do this.", "Wasimamizi pekee wanaweza kufanya hivi.")
        default: return reasonText(r.reason)
        }
    }
    static func photoPath(_ id: String, _ asset: String, w: Int = 480) -> String { "/api/story?id=\(Api.enc(id))&asset=\(Api.enc(asset))&w=\(w)" }
}

// --- months and days ------------------------------------------------------------------------------------------------

private func monthNames() -> [String] {
    Lang.sw ? ["Januari", "Februari", "Machi", "Aprili", "Mei", "Juni", "Julai", "Agosti", "Septemba", "Oktoba", "Novemba", "Desemba"]
        : ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"]
}
func shortMonths() -> [String] {
    Lang.sw ? ["Jan", "Feb", "Mac", "Apr", "Mei", "Jun", "Jul", "Ago", "Sep", "Okt", "Nov", "Des"]
        : ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
}
func longMonths() -> [String] { monthNames() }

/// ("2025", 6) from "2025-06" or "2025-06-15".
func ymParts(_ ym: String) -> (Int, Int)? {
    guard ym.range(of: #"^\d{4}-\d{2}"#, options: .regularExpression) != nil else { return nil }
    let y = Int(ym.prefix(4)) ?? 0
    let mo = Int(ym.dropFirst(5).prefix(2)) ?? 0
    return (1...12).contains(mo) ? (y, mo) : nil
}
/// "June 2025" (the month and year kept together).
func monthText(_ ym: String) -> String? {
    guard let p = ymParts(ym) else { return nil }
    return monthNames()[p.1 - 1] + "\u{00A0}\(p.0)"
}
/// "Nov 2024" (never split between the month and the year).
func shortMonth(_ ym: String) -> String? {
    guard let p = ymParts(ym) else { return nil }
    return shortMonths()[p.1 - 1] + "\u{00A0}\(p.0)"
}
/// A column's month as its header shows it: "Nov 2024"; In progress "Jan – Mar 2025" or "Dec 2024 – Feb 2025"; nil when not set.
func colDateText(_ d: StoryDates, _ row: String) -> String? {
    if row != "progress" { return shortMonth(d.of(row)) }
    guard let a = ymParts(d.progress) else { return nil }
    guard let b = ymParts(d.progressEnd), d.progressEnd > d.progress, let end = shortMonth(d.progressEnd) else { return shortMonth(d.progress) }
    let from = a.0 == b.0 ? shortMonths()[a.1 - 1] : (shortMonth(d.progress) ?? "")
    return from + "\u{00A0}– " + end
}
/// The columns that have photos but no month, in story order.
func storyDatesMissing(_ st: Story) -> [String] { StoryKit.ROWS.filter { !(st.photos[$0] ?? []).isEmpty && st.dates.of($0).isEmpty } }

/// A suggestion for an empty month: key ("before", "progress", "progressEnd", "after") → (YYYY-MM, where it comes from).
func storyDateHint(_ st: Story, _ row: String, done: String) -> [String: (String, String)] {
    let fromPhotos = t("Suggested from the photos", "Imependekezwa kutoka kwenye picha")
    let taken = (st.photos[row] ?? []).map { String($0.taken.prefix(7)) }.filter { $0.range(of: #"^\d{4}-\d{2}$"#, options: .regularExpression) != nil }.sorted()
    var out: [String: (String, String)] = [:]
    switch row {
    case "progress":
        if let f = taken.first { out["progress"] = (f, fromPhotos) }
        if let l = taken.last, l != taken.first { out["progressEnd"] = (l, fromPhotos) }
    case "after":
        let d = String(done.prefix(7))
        if ymParts(d) != nil { out["after"] = (d, t("The project’s completion month (monday.com)", "Mwezi mradi ulipokamilika (monday.com)")) }
        else if let f = taken.first { out["after"] = (f, fromPhotos) }
    default:
        if let f = taken.first { out[row] = (f, fromPhotos) }
    }
    return out
}

/// Why the server refused the dates, in words the sheet shows.
func storyDateWhy(_ reason: String?, _ field: String) -> String {
    switch reason {
    case "order": return t("The months must go in order: Before, then In progress, then After.", "Miezi lazima ifuate mpangilio: Kabla, kisha Wakati wa kazi, kisha Baada.")
    case "date":
        let f: String
        switch field {
        case "progressEnd": f = t("In progress (to)", "Wakati wa kazi (hadi)")
        case "progress", "before", "after": f = StoryKit.row(field)
        default: f = ""
        }
        return (f.isEmpty ? "" : f + ": ") + t("choose a month that has already come, from 2000 on.", "chagua mwezi ambao tayari umefika, kuanzia mwaka 2000.")
    case "offline": return t("No signal. Try again when you’re back online.", "Hakuna mtandao. Jaribu tena ukiwa mtandaoni.")
    default: return StoryKit.why(Reply(ok: false, status: 400, json: [:], reason: reason))
    }
}

/// Why a photo didn't send, in a few words.
func upWhy(_ reason: String) -> String {
    switch reason {
    case "too-large": return t("the photo is too big.", "picha ni kubwa mno.")
    case "type": return t("that kind of file can’t be used.", "aina hiyo ya faili haitumiki.")
    case "rate": return t("too many photos this hour.", "picha nyingi mno saa hii.")
    case "missing": return t("the photo is no longer on this phone.", "picha haipo tena kwenye simu hii.")
    case "admin-only", "named-only": return t("you can’t add photos here.", "huwezi kuongeza picha hapa.")
    default: return t("the server didn’t answer.", "seva haikujibu.")
    }
}

/// "2 Nov 2024" (this year's days without the year: "28 Oct") from an ISO date or time.
func photoDay(_ iso: String) -> String? {
    guard let p = ymParts(iso), iso.count >= 10, let d = Int(iso.dropFirst(8).prefix(2)) else { return nil }
    let thisYear = Calendar.current.component(.year, from: Date())
    return "\(d)\u{00A0}" + shortMonths()[p.1 - 1] + (p.0 != thisYear ? "\u{00A0}\(p.0)" : "")
}

// --- the Stories tab's groups ------------------------------------------------------------------------------------------

/// The four groups a project's story is in: needs a story (none, not started, collecting), waiting for review (a draft),
/// approved, on the website.
let SH_GROUPS = ["need", "draft", "approved", "published"]
func shGroupName(_ k: String) -> String {
    switch k {
    case "draft": return t("Waiting for review", "Inasubiri ukaguzi")
    case "approved": return t("Approved", "Imeidhinishwa")
    case "published": return t("On the website", "Kwenye tovuti")
    default: return t("Needs a story", "Inahitaji hadithi")
    }
}

extension Snapshot {
    func storyStatus(_ p: Project) -> String { let s = stories[p.id]?.st ?? ""; return s.isEmpty ? "not-started" : s }
    func shGroup(_ p: Project) -> String {
        switch storyStatus(p) { case "draft": return "draft"; case "approved": return "approved"; case "published": return "published"; default: return "need" }
    }
    func shCount() -> [String: Int] {
        var c: [String: Int] = [:]
        SH_GROUPS.forEach { g in c[g] = projects.filter { shGroup($0) == g }.count }
        return c
    }
    /// Collecting first, then drafts, then completed projects, then the rest; the latest change first; then by name.
    func shSorted(_ list: [Project]) -> [Project] {
        func w(_ p: Project) -> Int {
            switch storyStatus(p) { case "collecting": return 0; case "draft": return 1; default: return p.status == "Completed" ? 2 : 3 }
        }
        return list.sorted { a, b in
            let wa = w(a), wb = w(b)
            if wa != wb { return wa < wb }
            let ta = stories[a.id]?.at ?? "", tb = stories[b.id]?.at ?? ""
            if ta != tb { return ta > tb }
            return a.title < b.title
        }
    }
    /// "3 of 6 answers · 2 photos · consent received" (nothing for a story not started).
    func shLine(_ p: Project) -> String {
        guard let x = stories[p.id], storyStatus(p) != "not-started" else { return "" }
        let ph = x.b + x.i + x.a
        let parts = [t("\(x.n) of 6 answers", "Majibu \(x.n) kati ya 6"), ph == 1 ? t("1 photo", "Picha 1") : t("\(ph) photos", "Picha \(ph)"),
                     x.c == "received" ? t("consent received", "ruhusa imepatikana") : t("consent not yet", "ruhusa bado")]
        // each part stays on one line; the line may break after a dot
        return parts.map { $0.replacingOccurrences(of: " ", with: "\u{00A0}") }.joined(separator: "\u{00A0}· ")
    }
    /// Admins: stories waiting for review (the badge on the Stories tab).
    var draftsWaiting: Int { admin ? projects.filter { stories[$0.id]?.st == "draft" }.count : 0 }
}

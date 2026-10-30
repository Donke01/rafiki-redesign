import SwiftUI

// The Office, Android 2.26 ("a simpler Office"): every section as a tile in four labelled groups until one is picked;
// then a strip of chips in the same order, so the section starts near the top. Overview has "Start here" for a new
// admin and "Needs you" above the numbers.

/// A place inside an Office section to bring into view once it opens, and the Repairs filter to open on.
enum OfficeJump {
    /// "alerts" (Team › E-mail alerts) or "limit" (Costs › Second approval).
    static var to: String?
    /// Office › Repairs opens on this filter once ("unassigned" from Needs you).
    static var repairs: String?
}

/// A person's contact sheet (Office › Team, the feed, Visits). Set by the Team & Me screens; until then a tap does nothing.
enum OfficePersonHook {
    @MainActor static var open: ((String) -> Void)?
}
@MainActor func officeOpenPerson(_ name: String) { OfficePersonHook.open?(name) }

/// The Office sections in the order an admin uses them; anything not listed goes to Records.
let OFFICE_GROUPS: [(String, [String])] = [("need", ["overview", "review", "repairs", "visits"]), ("field", ["plan", "trips", "teammap", "vehicles"]),
                                           ("people", ["team", "donors", "mail", "sms"]), ("records", ["costs", "export", "qr", "cleanup"])]
func officeGroupName(_ k: String) -> String {
    switch k { case "need": return t("Needs you", "Inakuhitaji"); case "field": return t("Field work", "Kazi ya uwanjani"); case "people": return t("People", "Watu"); default: return t("Records", "Kumbukumbu") }
}
func officeGroups(_ keys: [String]) -> [(String, [String])] {
    let listed = Set(OFFICE_GROUPS.flatMap { $0.1 })
    return OFFICE_GROUPS.map { g, l in (g, l.filter { keys.contains($0) } + (g == "records" ? keys.filter { !listed.contains($0) } : [])) }.filter { !$0.1.isEmpty }
}
/// The sections this app has (Send an e-mail, Trips and Vehicles come from other parts of the app when they are added).
func officeSections() -> [(String, String, String)] {
    [("overview", t("Overview", "Muhtasari"), "drop"), ("repairs", t("Repairs", "Matengenezo"), "wrench"), ("costs", t("Costs", "Gharama"), "coins"), ("visits", t("Visits", "Ziara"), "cal"),
     ("teammap", t("Team map", "Ramani ya timu"), "pin"), ("plan", t("Plan a week", "Panga wiki"), "compass"), ("review", t("Review", "Kagua"), "inbox"), ("team", t("Team", "Timu"), "people"),
     ("cleanup", t("Clean-up", "Safisha data"), "spark"), ("export", t("Export", "Hamisha"), "upload"), ("sms", t("Community", "Jamii"), "chat"), ("donors", t("Donors", "Wafadhili"), "link"),
     ("qr", t("QR codes", "Misimbo ya QR"), "qr")]
}

/// Opening a section ticks off its Overview › Start here step.
let START_TAB: [String: String] = ["team": "alerts", "costs": "approve", "plan": "plan", "qr": "qr"]
@MainActor func startMark(_ m: AppModel, _ k: String?) {
    guard let k else { return }
    var seen = Set(m.od.stringArray(forKey: "startSeen") ?? [])
    if !seen.contains(k) { seen.insert(k); m.od.set(Array(seen), forKey: "startSeen") }
}

struct OfficeHome: View {
    @EnvironmentObject var m: AppModel
    @State private var sec = "overview"
    @State private var strip = false
    @State private var ready = false

    var body: some View {
        if let snap = m.snap {
            let secs = officeSections()
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        Color.clear.frame(height: 0).id("otop")
                        Text(t("Office", "Ofisi")).font(.hLarge).foregroundColor(C.ink).frame(maxWidth: .infinity)
                        if !strip {
                            OfficeGroupsGrid(secs: secs, sel: sec, badge: { badge(snap, $0) }) { k in open(k) }
                        } else {
                            OfficeStrip(secs: secs, sel: sec, badge: { badge(snap, $0) }, all: { withAnimation(Motion.on ? .easeInOut(duration: 0.25) : nil) { strip = false; sec = "overview" } }) { k in open(k) }
                        }
                        VStack(alignment: .leading, spacing: 0) { section(snap, proxy) }.padding(.top, 14).id(sec)
                        Spacer().frame(height: 24)
                    }.padding(16)
                }
            }
            .onAppear {
                guard !ready else { return }
                ready = true
                if m.demo, let s = Demo.arg("-section") { sec = s; strip = s != "overview" }
            }
            .onChange(of: sec) { k in startMark(m, START_TAB[k]) }
        }
    }

    func badge(_ s: Snapshot, _ k: String) -> Int { k == "review" ? s.reviewOpen : k == "repairs" ? s.repairs.count : 0 }
    func open(_ k: String) { Haptic.tap(); withAnimation(Motion.on ? .easeInOut(duration: 0.25) : nil) { sec = k; strip = true } }

    @ViewBuilder func section(_ snap: Snapshot, _ proxy: ScrollViewProxy) -> some View {
        let go: (String) -> Void = { k in proxy.scrollTo("otop", anchor: .top); open(k) }
        switch sec {
        case "overview": OfficeOverview(s: snap, go: go)
        case "repairs": OfficeRepairs(s: snap)
        case "costs": OfficeCosts(s: snap)
        case "visits": OfficeVisits(s: snap)
        case "teammap": OfficeTeamMap(s: snap)
        case "plan": OfficePlanWeek(s: snap)
        case "review": OfficeReview()
        case "team": OfficeTeamList()
        case "cleanup": OfficeCleanup(s: snap)
        case "sms": OfficeCommunity()
        case "donors": DonorsSection(s: snap)
        case "qr": FieldQrSection(s: snap)
        default: OfficeExport(s: snap)
        }
    }
}

/// Every section as a tile in its labelled group; two groups side by side on a wide screen.
struct OfficeGroupsGrid: View {
    let secs: [(String, String, String)]; let sel: String; let badge: (String) -> Int; let pick: (String) -> Void
    var body: some View {
        let groups = officeGroups(secs.map(\.0))
        let byK = Dictionary(secs.map { ($0.0, $0) }, uniquingKeysWith: { a, _ in a })
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(groups.enumerated()), id: \.element.0) { gi, gr in
                Text(officeGroupName(gr.0).uppercased(with: Locale(identifier: Lang.sw ? "sw" : "en_US"))).font(.mont(11, .heavy)).tracking(0.7).foregroundColor(C.muted).lineLimit(1)
                    .padding(.leading, 2).padding(.top, 14).padding(.bottom, 6).accessibilityAddTraits(.isHeader)
                let rows = stride(from: 0, to: gr.1.count, by: 4).map { Array(gr.1[$0..<min($0 + 4, gr.1.count)]) }
                ForEach(rows.indices, id: \.self) { ri in
                    HStack(spacing: 8) {
                        ForEach(rows[ri], id: \.self) { k in
                            if let x = byK[k] { OfficeTile(label: x.1, icon: x.2, on: sel == k, badge: badge(k)) { pick(k) }.rise(gi * 4 + ri * 4, step: 0.035).accessibilityIdentifier("o_\(k)") }
                        }
                        ForEach(0..<(4 - rows[ri].count), id: \.self) { _ in Color.clear.frame(maxWidth: .infinity, minHeight: 1) }
                    }.padding(.top, ri > 0 ? 8 : 0)
                }
            }
        }
    }
}

/// A section tile: an icon over its name; the name shrinks rather than breaking a word.
struct OfficeTile: View {
    let label: String; let icon: String; let on: Bool; var badge = 0; let action: () -> Void
    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                OIcon(icon, size: 22, color: on ? .white : C.blue)
                FitWords(label, size: 11, weight: .bold, color: on ? .white : C.ink, lines: 2, align: .center, min: 0.68)
            }
            .padding(.horizontal, 3).padding(.vertical, 8)
            .frame(maxWidth: .infinity, minHeight: 70)
            .background(Group { if on { BlueBrush } else { C.card } })
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(on ? .clear : C.line, lineWidth: oEdge))
            .overlay(alignment: .topTrailing) {
                if badge > 0 { Text("\(badge)").font(.mont(10, .bold)).foregroundColor(.white).padding(.horizontal, 6).padding(.vertical, 1).background(RedC).clipShape(Capsule()).offset(x: 2, y: -4) }
            }
        }.buttonStyle(Press())
    }
}

/// The sections as a strip of chips (All first), the chosen one brought into view.
struct OfficeStrip: View {
    let secs: [(String, String, String)]; let sel: String; let badge: (String) -> Int; let all: () -> Void; let pick: (String) -> Void
    var body: some View {
        let order = officeGroups(secs.map(\.0)).flatMap { $0.1 }
        let byK = Dictionary(secs.map { ($0.0, $0) }, uniquingKeysWith: { a, _ in a })
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    OChip(label: t("All", "Zote"), icon: "grid", on: false, action: all).accessibilityIdentifier("o_all")
                    ForEach(order, id: \.self) { k in
                        if let x = byK[k] { OChip(label: x.1, icon: x.2, on: sel == k, badge: badge(k)) { pick(k) }.id(k).accessibilityIdentifier("o_\(k)") }
                    }
                }.padding(.vertical, 2)
            }
            .onAppear { proxy.scrollTo(sel, anchor: .center) }
            .onChange(of: sel) { k in withAnimation(Motion.on ? .easeInOut(duration: 0.3) : nil) { proxy.scrollTo(k, anchor: .center) } }
        }.padding(.top, 10)
    }
}

// --- Start here -------------------------------------------------------------------------------------------------

private struct StartStep { let key: String; let icon: String; let title: String; let line: String; let sec: String; var jump: String? = nil }
private func startSteps() -> [StartStep] {
    [StartStep(key: "team", icon: "people", title: t("Add your field team", "Ongeza timu yako ya uwanjani"), line: t("Give each person a PIN so they can sign in.", "Mpe kila mtu PIN ili aweze kuingia."), sec: "team"),
     StartStep(key: "alerts", icon: "mail", title: t("Check who gets alert e-\u{2060}mails", "Angalia nani anapata barua pepe za arifa"), line: t("Repairs and reviews are e-\u{2060}mailed to these admins.", "Matengenezo na ukaguzi hutumwa kwa wasimamizi hawa."), sec: "team", jump: "alerts"),
     StartStep(key: "approve", icon: "shield", title: t("Set when a repair needs a second approval", "Weka lini matengenezo yanahitaji idhini ya pili"), line: t("Big repair costs wait for another admin.", "Gharama kubwa za matengenezo zinasubiri msimamizi mwingine."), sec: "costs", jump: "limit"),
     StartStep(key: "plan", icon: "cal", title: t("Plan a week of visits", "Panga wiki ya ziara"), line: t("Pick a person; the app suggests the sites, in driving order.", "Chagua mtu; programu inapendekeza maeneo kwa mpangilio wa safari."), sec: "plan"),
     StartStep(key: "qr", icon: "qr", title: t("Print QR check-\u{2060}in stickers", "Chapisha stika za QR za kuingia"), line: t("One for each site: scanning it opens the Site visit.", "Moja kwa kila eneo: ukiichanganua inafungua Ziara ya eneo."), sec: "qr")]
}

/// Overview › Start here: five set-up steps for a new admin; each opens the right place and ticks itself off.
struct OfficeStart: View {
    @EnvironmentObject var m: AppModel
    let go: (String) -> Void
    @State private var off = false
    @State private var field = false
    @State private var seen: Set<String> = []
    var body: some View {
        let steps = startSteps()
        let done: (String) -> Bool = { k in seen.contains(k) || (k == "team" && field) }
        let n = steps.filter { done($0.key) }.count
        Group {
            if !off && n < steps.count {
                CardV(pad: 16) {
                    HStack(alignment: .top, spacing: 8) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(t("Start here", "Anza hapa")).font(.tSmall).foregroundColor(C.ink).accessibilityAddTraits(.isHeader)
                            Text(t("\(n) of \(steps.count) done. Each step opens the right place.", "\(n) kati ya \(steps.count) zimekamilika. Kila hatua inafungua mahali sahihi.")).font(.bSmall).foregroundColor(C.muted)
                        }.frame(maxWidth: .infinity, alignment: .leading)
                        Button { Haptic.tap(); m.od.set(true, forKey: "startOff"); withAnimation { off = true } } label: {
                            Text(t("Hide", "Ficha")).font(.mont(14, .bold)).foregroundColor(C.blue2).padding(.horizontal, 6).frame(minHeight: 44)
                        }.buttonStyle(.plain)
                    }
                    OBar(f: Double(n) / Double(steps.count), color: C.ok).padding(.top, 10).padding(.bottom, 6)
                    ForEach(Array(steps.enumerated()), id: \.offset) { i, x in
                        if i > 0 { ODivider() }
                        let ok = done(x.key)
                        let blue = ohue("blue")
                        ORow(icon: ok ? "check" : x.icon, fg: ok ? C.ok : blue.fg, bg: ok ? C.okBg : blue.bg, title: x.title, line: ok ? t("Done", "Imekamilika") : x.line, dim: ok) {
                            startMark(m, x.key); seen.insert(x.key); OfficeJump.to = x.jump; go(x.sec)
                        }
                    }
                }.padding(.top, 12)
            }
        }
        .task {
            off = m.od.bool(forKey: "startOff")
            seen = Set(m.od.stringArray(forKey: "startSeen") ?? [])
            if off { return }
            let a = await m.api.adminGet("team").json.objs("team")
            field = a.contains { !$0.bool("admin") }
        }
    }
}

/// Overview › Needs you: only what is waiting for an admin, each opening the place to act on it.
struct OfficeTodo: View {
    @EnvironmentObject var m: AppModel
    let s: Snapshot; let due: Int; let go: (String) -> Void
    var body: some View {
        let rv = s.reviewOpen
        let ua = s.repairs.values.filter { $0.urgency == "urgent" && $0.assignee.trimmingCharacters(in: .whitespaces).isEmpty }.count
        let bh = m.sx.stages.values.filter { $0.s != "handed" && $0.behind }.count
        var rows: [(String, String, String, String, String, () -> Void)] = []
        if rv > 0 { rows.append(("review", "inbox", "purple", rv == 1 ? t("1 thing is waiting for your review", "Jambo 1 linasubiri ukague") : t("\(rv) things are waiting for your review", "Mambo \(rv) yanasubiri ukague"),
                                 t("New sites, locations, stories and big costs", "Maeneo mapya, mahali, hadithi na gharama kubwa"), { go("review") })) }
        if ua > 0 { rows.append(("unassigned", "wrench", "coral", ua == 1 ? t("1 urgent repair has nobody on it", "Matengenezo 1 ya haraka hayana mtu") : t("\(ua) urgent repairs have nobody on them", "Matengenezo \(ua) ya haraka hayana mtu"),
                                 t("Give each one to a person", "Mpe mtu kila moja"), { OfficeJump.repairs = "unassigned"; go("repairs") })) }
        if due > 0 { rows.append(("plan", "cal", "blue", due == 1 ? t("1 site is due a visit", "Eneo 1 linasubiri ziara") : t("\(due) sites are due a visit", "Maeneo \(due) yanasubiri ziara"),
                                  t("Plan a week of visits for someone", "Mpangie mtu wiki ya ziara"), { go("plan") })) }
        if bh > 0 { rows.append(("build", "build", "amber", bh == 1 ? t("1 building project is behind schedule", "Mradi 1 wa ujenzi umechelewa") : t("\(bh) building projects are behind schedule", "Miradi \(bh) ya ujenzi imechelewa"),
                                 t("See what is holding them up", "Angalia kinachozichelewesha"), { officeToList(m, "build") })) }
        return CardV(pad: 16) {
            Text(t("Needs you", "Inakuhitaji")).font(.tSmall).foregroundColor(C.ink).accessibilityAddTraits(.isHeader)
            if rows.isEmpty { ONothing(t("Nothing needs you right now.", "Hakuna kinachokuhitaji sasa hivi.")) }
            else {
                VStack(spacing: 0) {
                    ForEach(Array(rows.enumerated()), id: \.offset) { i, r in
                        if i > 0 { ODivider() }
                        let hu = ohue(r.2)
                        ORow(icon: r.1, fg: hu.fg, bg: hu.bg, title: r.3, line: r.4, action: r.5)
                    }
                }.padding(.top, 6)
            }
        }.padding(.top, 12)
    }
}

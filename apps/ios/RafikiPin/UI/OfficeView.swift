import SwiftUI
import UIKit

/// The Office: tools for admins. Same server actions as the web and Android apps.
struct OfficeView: View {
    @EnvironmentObject var m: AppModel
    @State private var sec = "overview"

    var body: some View {
        if let snap = m.snap {
            let secs = [("overview", t("Overview", "Muhtasari"), "drop"), ("repairs", t("Repairs", "Ukarabati"), "wrench"), ("visits", t("Visits", "Ziara"), "cal"),
                        ("review", t("Review", "Idhinisha"), "inbox"), ("team", t("Team", "Timu"), "people"), ("cleanup", t("Clean-up", "Usafi"), "spark"), ("export", t("Export", "Pakua"), "upload")]
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text(t("Office", "Ofisi")).font(.hLarge).foregroundColor(C.ink)
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 4), spacing: 8) {
                        ForEach(secs, id: \.0) { k, l, ic in
                            Tile(label: l, icon: ic, selected: sec == k, badge: k == "review" ? snap.reviewOpen : k == "repairs" ? snap.repairs.count : 0) { sec = k }.accessibilityIdentifier("o_\(k)")
                        }
                    }.padding(.top, 8)
                    VStack(alignment: .leading, spacing: 0) {
                        switch sec {
                        case "overview": Overview(s: snap) { sec = $0 }
                        case "repairs": Repairs(s: snap)
                        case "visits": AssignVisits(s: snap)
                        case "review": ReviewQueue()
                        case "team": TeamSection()
                        case "cleanup": Cleanup(s: snap)
                        default: Export(s: snap)
                        }
                    }.padding(.top, 14)
                    Spacer().frame(height: 24)
                }.padding(16)
            }
            .onAppear { if m.demo, let s = Demo.arg("-section") { sec = s } }
        }
    }
}

/// People who can sign in (from the Team list), for "assigned to".
final class Team: ObservableObject {
    @Published var people: [JSON] = []
    func load(_ m: AppModel) async { people = await m.api.adminGet("team").json.objs("team") }
}

func feedIcon(_ a: String) -> String { ["pin": "pin", "visit": "clip", "fixed": "check", "new": "plus", "undo": "undo", "job": "wrench", "assign": "cal", "team": "people"][a] ?? "drop" }
func verb(_ a: String) -> String {
    switch a {
    case "pin": return t("pinned", "aliweka mahali pa"); case "visit": return t("reported a visit to", "aliripoti ziara ya"); case "fixed": return t("marked fixed", "aliweka imerekebishwa")
    case "new": return t("added a new site:", "aliongeza eneo jipya:"); case "undo": return t("undid a pin on", "alitendua mahali pa")
    case "request": return t("asked to move the confirmed location of", "aliomba kuhamisha mahali palipothibitishwa pa")
    case "approve": return t("approved", "aliidhinisha"); case "reject": return t("turned down", "alikataa"); case "job": return t("updated the repair job on", "alisasisha kazi ya matengenezo ya")
    case "assign": return t("assigned", "aligawa"); case "team": return t("team:", "timu:"); default: return a
    }
}

private struct Overview: View {
    @EnvironmentObject var m: AppModel
    let s: Snapshot; let go: (String) -> Void
    var body: some View {
        let pinned = s.projects.filter { $0.gps != nil }.count
        let pct = s.projects.isEmpty ? 0 : pinned * 100 / s.projects.count
        let due = s.projects.filter { $0.gps != nil && ageDays(s, $0) >= DUE_DAYS }.count
        let ms: Date = monthStartUTC()
        let vm = m.activity.map { a in a.filter { $0.str("a") == "visit" && (When.parse($0.str("at")).map { $0 >= ms } ?? false) }.count }
        let urgent = s.repairs.values.filter { $0.urgency == "urgent" }.count
        let cost = s.repairs.values.reduce(0) { $0 + ($1.cost ?? 0) }
        let st = Dictionary(grouping: s.visits.values, by: \.state).mapValues(\.count)
        let seen = st.values.reduce(0, +), unseen = max(0, s.projects.count - seen), tot = Double(max(1, s.projects.count))

        VStack(alignment: .leading, spacing: 0) {
            CardV(deep: true) {
                HStack(spacing: 16) {
                    ZStack(alignment: .bottom) {
                        Circle().fill(Color.white.opacity(0.12))
                        Rectangle().fill(LinearGradient(colors: [Color(hex: 0x5DB8F2), Color(hex: 0x0A78C2)], startPoint: .top, endPoint: .bottom)).frame(height: 96 * CGFloat(pct) / 100)
                        Text("\(pct)%").font(.hMedium).foregroundColor(.white).frame(maxHeight: .infinity)
                    }.frame(width: 96, height: 96).clipShape(Circle())
                    VStack(alignment: .leading, spacing: 4) {
                        Text(t("\(pinned) of \(s.projects.count) projects are on the map", "Miradi \(pinned) kati ya \(s.projects.count) iko kwenye ramani")).font(.tMedium).foregroundColor(.white)
                        Text(t("\(s.projects.count - pinned) still need a location pinned in the field.", "\(s.projects.count - pinned) bado zinahitaji mahali kuwekwa uwanjani.")).font(.bSmall).foregroundColor(LightBlue)
                    }
                }
            }
            HStack(alignment: .top, spacing: 10) {
                Stat(icon: "wrench", n: "\(s.repairs.count)", label: t("open repairs", "matengenezo yaliyo wazi"),
                     sub: (urgent > 0 ? t("\(urgent) urgent", "\(urgent) ya haraka") : "") + (cost > 0 ? " · KES " + Geo.grouped(cost) : ""), hot: !s.repairs.isEmpty) { go("repairs") }
                Stat(icon: "inbox", n: "\(s.reviewOpen)", label: t("waiting for your review", "zinasubiri uidhinishe"), sub: "", hot: s.reviewOpen > 0) { go("review") }
            }.padding(.top, 10)
            HStack(alignment: .top, spacing: 10) {
                Stat(icon: "cal", n: "\(due)", label: t("due a visit", "zinasubiri ziara"), sub: t("not seen for 6+ months", "hazijaonekana kwa miezi 6+")) { go("visits") }
                Stat(icon: "clip", n: vm.map { "\($0)" } ?? "…", label: t("visits this month", "ziara za mwezi huu"), sub: "") {}
            }.padding(.top, 10)
            CardV {
                Text(t("Condition at last visit", "Hali katika ziara ya mwisho")).font(.tSmall).foregroundColor(C.ink)
                GeometryReader { g in
                    HStack(spacing: 0) {
                        ForEach(Array([(st["working"] ?? 0, GreenC), (st["repair"] ?? 0, Amber), (st["notworking"] ?? 0, RedC), (unseen, C.off)].enumerated()), id: \.offset) { _, x in
                            if x.0 > 0 { Rectangle().fill(x.1).frame(width: g.size.width * CGFloat(Double(x.0) / tot)) }
                        }
                        Spacer(minLength: 0)
                    }.background(C.line).clipShape(Capsule())
                }.frame(height: 14).padding(.vertical, 10)
                let legend = [(st["working"] ?? 0, GreenC, t("working", "zinafanya kazi")), (st["repair"] ?? 0, Amber, t("needed repair", "zilihitaji matengenezo")),
                              (st["notworking"] ?? 0, RedC, t("not working", "hazifanyi kazi")), (unseen, C.off, t("not visited yet", "bado hazijatembelewa"))]
                LazyVGrid(columns: [GridItem(.flexible(), alignment: .leading), GridItem(.flexible(), alignment: .leading)], spacing: 6) {
                    ForEach(Array(legend.enumerated()), id: \.offset) { _, x in
                        HStack(spacing: 4) { RoundedRectangle(cornerRadius: 3).fill(x.1).frame(width: 10, height: 10); Text(" \(x.0) ").font(.tSmall).foregroundColor(C.ink); Text(x.2).font(.bSmall).foregroundColor(C.muted) }
                    }
                }
            }.padding(.top, 10)
            H2(t("By country", "Kwa nchi")).padding(.top, 14)
            ForEach(COUNTRIES, id: \.0) { k, n in
                let l = s.projects.filter { $0.ck == k }
                if !l.isEmpty {
                    let loc = l.filter { $0.gps != nil }.count
                    let rp = l.filter { s.repairs[$0.id] != nil }.count, du = l.filter { $0.gps != nil && ageDays(s, $0) >= DUE_DAYS }.count
                    let parts = [rp > 0 ? t("\(rp) open repairs", "matengenezo \(rp)") : nil, du > 0 ? t("\(du) due a visit", "\(du) zinasubiri ziara") : nil].compactMap { $0 }
                    CardV(pad: 14) {
                        HStack { Text(n).font(.tSmall).foregroundColor(C.ink); Spacer(); Text(t("\(loc) of \(l.count) mapped", "\(loc) kati ya \(l.count) zina mahali")).font(.bSmall).foregroundColor(C.muted) }
                        Meter(f: Double(loc) / Double(l.count)).padding(.top, 8)
                        Text(parts.isEmpty ? t("Nothing needs attention", "Hakuna kinachohitaji kushughulikiwa") : parts.joined(separator: " · ")).font(.bSmall).foregroundColor(C.muted).padding(.top, 6)
                    }.padding(.top, 8)
                }
            }
            H2(t("Latest from the field", "Habari za karibuni kutoka uwanjani")).padding(.top, 14)
            if let a = m.activity {
                if a.isEmpty { Text(t("Nothing yet.", "Bado hakuna kitu.")).foregroundColor(C.muted) }
                ForEach(Array(a.suffix(12).reversed().enumerated()), id: \.offset) { _, e in
                    HStack(alignment: .top, spacing: 10) {
                        IconTile(icon: feedIcon(e.str("a")), tint: C.blue, bg: C.tint, size: 34)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(feedLine(e)).font(.bMedium).foregroundColor(C.text)
                            Text(When.text(e.str("at"))).font(.bSmall).foregroundColor(C.muted)
                        }
                    }.padding(.top, 8)
                }
            } else { Text(t("Loading…", "Inapakia…")).foregroundColor(C.muted) }
        }
        .task { await m.loadActivity() }
    }
}

private func monthStartUTC() -> Date {
    var cal = Calendar(identifier: .gregorian); cal.timeZone = TimeZone(identifier: "UTC")!
    return cal.date(from: cal.dateComponents([.year, .month], from: Date()))!
}

private func feedLine(_ e: [String: Any]) -> String {
    let by: String = e.str("by"), what: String = verb(e.str("a")), title: String = e.str("t")
    return "\(by) \(what) \(title)"
}

private struct Stat: View {
    let icon: String; let n: String; let label: String; let sub: String; var hot = false; let action: () -> Void
    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 0) {
                IconTile(icon: icon, tint: hot ? C.warn : C.blue, bg: hot ? C.warnBg : C.tint, size: 40)
                Text(n).font(.hMedium).foregroundColor(C.ink).padding(.top, 8)
                Text(label).font(.lMedium).foregroundColor(C.muted).multilineTextAlignment(.leading)
                if !sub.isEmpty { Text(sub).font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.leading) }
            }
            .padding(14).frame(maxWidth: .infinity, alignment: .leading).background(C.card)
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(hot ? Amber : C.line, lineWidth: hot ? 2 : 1))
        }.buttonStyle(Press())
    }
}

private struct Repairs: View {
    @EnvironmentObject var m: AppModel
    @StateObject private var team = Team()
    let s: Snapshot
    var body: some View {
        let urg = ["urgent": 0, "month": 1, "watch": 2]
        let ids = s.repairs.keys.sorted { a, b in
            let ua = urg[s.repairs[a]?.urgency ?? ""] ?? 3, ub = urg[s.repairs[b]?.urgency ?? ""] ?? 3
            return ua != ub ? ua < ub : (s.repairs[a]?.at ?? "") < (s.repairs[b]?.at ?? "")
        }
        VStack(alignment: .leading, spacing: 0) {
            Text(t("Most urgent first. Changes leave a note on the project in monday.com, and the person assigned sees the job in their app.", "Za haraka kwanza. Mabadiliko yanaacha maelezo kwenye mradi katika monday.com, na aliyepewa anaona kazi kwenye programu yake.")).font(.bMedium).foregroundColor(C.muted)
            if ids.isEmpty { Text(t("No open repairs.", "Hakuna matengenezo yaliyo wazi.")).foregroundColor(C.muted).padding(.top, 12) }
            ForEach(ids, id: \.self) { id in if let r = s.repairs[id] { RepairJob(s: s, id: id, r: r, names: team.people.map { $0.str("name") }).id(id + r.step + r.assignee + r.due) } }
        }
        .task { await team.load(m) }
    }
}

private struct RepairJob: View {
    @EnvironmentObject var m: AppModel
    let s: Snapshot; let id: String; let r: Repair; let names: [String]
    @State private var step = ""
    @State private var who = ""
    @State private var due = ""
    @State private var budget = ""
    @State private var note = ""
    @State private var msg: (String, String)?
    @State private var ready = false
    var body: some View {
        let p = s.projects.first { $0.id == id }
        let nw = r.state == "notworking"
        CardV {
            HStack {
                Button { m.go(Place(tab: "office", project: id)) } label: { Text(p?.title ?? r.title).font(.tMedium).foregroundColor(C.blue2).multilineTextAlignment(.leading) }.buttonStyle(.plain)
                Spacer(minLength: 6)
                Tag(stateName(nw ? "notworking" : "repair"), bg: nw ? C.badBg : C.warnBg, fg: nw ? C.bad : C.warn)
            }
            Text((p?.country ?? "") + " · " + t("reported by ", "imeripotiwa na ") + r.by + ", " + When.text(r.at) + (r.urgency.isEmpty ? "" : " · " + urgencyName(r.urgency))).font(.bSmall).foregroundColor(C.muted)
            if !r.note.isEmpty { Text("“\(r.note)”").font(.bMedium).foregroundColor(C.text).padding(.top, 4) }
            if !r.problems.isEmpty { Text(t("Problems: ", "Matatizo: ") + r.problems.joined(separator: ", ")).font(.bSmall).foregroundColor(C.muted) }
            let extra = [r.parts.isEmpty ? nil : t("Parts: ", "Vipuri: ") + r.parts, r.cost.map { t("About KES ", "Takriban KES ") + Geo.grouped($0) }].compactMap { $0 }
            if !extra.isEmpty { Text(extra.joined(separator: " · ")).font(.bSmall).foregroundColor(C.muted) }
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 6), GridItem(.flexible(), spacing: 6)], spacing: 6) {
                ForEach([("reported", t("Reported", "Imeripotiwa")), ("assigned", t("Assigned", "Imegawiwa")), ("parts", t("Parts ordered", "Vipuri vimeagizwa")), ("fixed", t("Fixed", "Imerekebishwa"))], id: \.0) { k, l in
                    Pill(l, on: step == k, color: k == "fixed" ? C.ok : nil) { step = k }
                }
            }.padding(.top, 10)
            HStack(alignment: .bottom, spacing: 10) {
                Picker1(label: t("Assigned to", "Amepewa"), value: who, options: [("", t("Not assigned", "Hajapewa mtu"))] + names.map { ($0, $0) }) { who = $0 }
                DateField(label: t("Due", "Tarehe ya mwisho"), value: due) { due = $0 }
            }
            Field(t("Approved budget (KES)", "Bajeti iliyoidhinishwa (KES)"), Binding(get: { budget }, set: { budget = $0.filter(\.isNumber) }), number: true)
            Field(t("Note (optional)", "Maelezo (si lazima)"), $note, placeholder: t("e.g. parts bought in Eldoret", "mf. vipuri vilinunuliwa Eldoret"))
            Btn(t("Save", "Hifadhi"), icon: "check", small: true) {
                Task {
                    msg = ("", t("Saving…", "Inahifadhi…"))
                    let x = await m.api.admin(["action": "job", "id": id, "step": step, "assignee": who, "due": due, "budget": budget, "note": note])
                    msg = x.ok ? ("ok", t("Saved ✓", "Imehifadhiwa ✓")) : ("bad", reasonText(x.reason))
                    if x.ok { note = ""; m.reload() }
                }
            }.padding(.top, 12)
            if case let (tone, text)? = msg { Msg(text, tone) }
        }
        .padding(.top, 12)
        .onAppear { if !ready { ready = true; step = r.step; who = r.assignee; due = r.due; budget = r.budget.map { "\($0)" } ?? "" } }
    }
}

private struct AssignVisits: View {
    @EnvironmentObject var m: AppModel
    @StateObject private var team = Team()
    let s: Snapshot
    @State private var show = "due"
    @State private var ck = ""
    @State private var picked: [String] = []
    @State private var to = ""
    @State private var date = ""
    @State private var note = ""
    @State private var msg: (String, String)?
    @State private var plan: [JSON]?

    func loadPlan() async { plan = await m.api.adminGet("assign").json.objs("items").filter { $0["done"] == nil || $0["done"] is NSNull } }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            CardV {
                H2(t("Assign visits", "Gawa ziara"))
                HStack(alignment: .bottom, spacing: 10) {
                    Picker1(label: t("Field officer", "Afisa wa uwanjani"), value: to, options: [("", t("Choose", "Chagua"))] + team.people.map { ($0.str("name"), $0.str("name")) }) { to = $0 }
                    DateField(label: t("Date", "Tarehe"), value: date) { date = $0 }
                }
                Field(t("Note (optional)", "Maelezo (si lazima)"), $note, placeholder: t("e.g. check the pump and meet the committee", "mf. kagua pampu na ukutane na kamati"))
                HStack(alignment: .bottom, spacing: 10) {
                    Picker1(label: t("Show", "Onyesha"), value: show, options: [("due", t("Due a visit", "Inasubiri ziara")), ("repairs", t("Open repairs", "Matengenezo yaliyo wazi")), ("all", t("All with a location", "Zote zenye mahali"))]) { show = $0 }
                    Picker1(label: t("Country", "Nchi"), value: ck, options: [("", t("All", "Yote"))] + COUNTRIES) { ck = $0 }
                }
                let rows = Array(s.projects.filter { p in p.gps != nil && (ck.isEmpty || p.ck == ck) && (show == "due" ? ageDays(s, p) >= DUE_DAYS : show == "repairs" ? s.repairs[p.id] != nil : true) }.prefix(80))
                ForEach(rows) { p in
                    let on = picked.contains(p.id)
                    Button { if on { picked.removeAll { $0 == p.id } } else { picked.append(p.id) } } label: {
                        HStack(spacing: 8) {
                            Image(systemName: on ? "checkmark.square.fill" : "square").font(.system(size: 22)).foregroundColor(on ? C.blue : C.muted)
                            VStack(alignment: .leading, spacing: 2) { Text(p.title).font(.tSmall).foregroundColor(C.text); Text(p.sub).font(.bSmall).foregroundColor(C.muted) }
                            Spacer(minLength: 0)
                        }
                        .padding(8).background(on ? C.tint : C.card).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(on ? C.blue : C.line, lineWidth: 1))
                    }.buttonStyle(.plain).padding(.top, 6)
                }
                if rows.isEmpty { Text(t("No projects match.", "Hakuna mradi unaolingana.")).foregroundColor(C.muted).padding(.top, 8) }
                Btn(picked.isEmpty ? t("Assign", "Gawa") : t("Assign \(picked.count) visits", "Gawa ziara \(picked.count)"), icon: "cal", enabled: !picked.isEmpty && !to.isEmpty && !date.isEmpty, wide: true) {
                    Task {
                        msg = ("", t("Assigning…", "Inagawa…"))
                        let x = await m.api.admin(["action": "assign", "ids": picked, "to": to, "date": date, "note": note])
                        msg = x.ok ? ("ok", t("Assigned ✓ \(to) will see it in their app.", "Imegawiwa ✓ \(to) ataiona kwenye programu yake.")) : ("bad", reasonText(x.reason))
                        if x.ok { picked = []; note = ""; await loadPlan() }
                    }
                }.padding(.top, 12)
                if case let (tone, text)? = msg { Msg(text, tone) }
            }
            H2(t("Planned visits", "Ziara zilizopangwa")).padding(.top, 14)
            if let pl = plan {
                if pl.isEmpty { Text(t("No visits planned.", "Hakuna ziara zilizopangwa.")).foregroundColor(C.muted) }
                ForEach(Array(pl.sorted { $0.str("date") < $1.str("date") }.enumerated()), id: \.offset) { _, a in
                    CardV(pad: 14) {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(a.str("t").isEmpty ? a.str("id") : a.str("t")).font(.tSmall).foregroundColor(C.ink)
                                Text(a.str("to") + " · " + a.str("date") + (a.str("note").isEmpty ? "" : " · " + a.str("note"))).font(.bSmall).foregroundColor(C.muted)
                            }
                            Spacer()
                            Btn(t("Remove", "Ondoa"), light: true, small: true) { Task { _ = await m.api.admin(["action": "unassign", "aid": a.str("aid")]); await loadPlan() } }
                        }
                    }.padding(.top, 8)
                }
            } else { Text(t("Loading…", "Inapakia…")).foregroundColor(C.muted) }
        }
        .task { await team.load(m); await loadPlan() }
    }
}

private struct ReviewQueue: View {
    @EnvironmentObject var m: AppModel
    @State private var items: [JSON]?
    func load() async { items = await m.api.adminGet("review").json.objs("items").filter { $0.str("status") == "open" } }
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let list = items {
                if list.isEmpty { Text(t("Nothing waiting for review.", "Hakuna kinachosubiri kuidhinishwa.")).foregroundColor(C.muted) }
                ForEach(list.indices, id: \.self) { i in ReviewItem(it: list[i]) { await load() }.id(list[i].str("rid")) }
            } else { Text(t("Loading…", "Inapakia…")).foregroundColor(C.muted) }
        }.task { await load() }
    }
}

private struct ReviewItem: View {
    @EnvironmentObject var m: AppModel
    let it: JSON; let done: () async -> Void
    @State private var reason = ""
    @State private var msg: String?
    var body: some View {
        let d = it.obj("data") ?? [:]
        let kind = it.str("kind")
        let (icon, title): (String, String) = kind == "new" ? ("plus", t("New site", "Eneo jipya")) : kind == "move" ? ("pin", t("Big location change", "Mabadiliko makubwa ya mahali"))
            : kind == "request" ? ("lock", t("Change to a confirmed location", "Badiliko la mahali palipothibitishwa")) : ("spark", t("Story moment", "Hadithi"))
        CardV {
            HStack(spacing: 10) {
                IconTile(icon: icon, tint: C.blue, bg: C.tint, size: 36)
                Text(title).font(.tSmall).foregroundColor(C.ink); Spacer(); Text(When.text(it.str("at"))).font(.bSmall).foregroundColor(C.muted)
            }
            Button { m.go(Place(tab: "office", project: it.str("id"))) } label: { Text(it.str("t") + " · " + t("by ", "na ") + it.str("by")).font(.tSmall).foregroundColor(C.blue2).multilineTextAlignment(.leading) }.buttonStyle(.plain).padding(.top, 8)
            switch kind {
            case "move": Text(t("From ", "Kutoka ") + (d.str("from").isEmpty ? "—" : d.str("from")) + t(" to ", " hadi ") + d.str("to") + (d.num("dist").map { " · " + Geo.dist($0, miles: m.miles) } ?? "")).font(.bSmall).foregroundColor(C.muted)
            case "request": Text(t("Wants ", "Anataka ") + d.str("to") + t(" instead of ", " badala ya ") + d.str("from")).font(.bSmall).foregroundColor(C.muted)
            case "story":
                Text("“" + d.str("quote") + "”" + (d.str("who").isEmpty ? "" : " — " + d.str("who"))).font(.bMedium).foregroundColor(C.text)
                    .frame(maxWidth: .infinity, alignment: .leading).padding(10).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous)).padding(.top, 6)
            default: Text([d.str("type"), d.str("village"), countryName(d.str("ck")) ?? ""].filter { !$0.isEmpty }.joined(separator: " · ")).font(.bSmall).foregroundColor(C.muted)
            }
            Field(t("Reason (optional)", "Sababu (si lazima)"), $reason, placeholder: t("e.g. wrong school", "mf. shule isiyo sahihi"))
            HStack(spacing: 8) {
                Btn(kind == "story" ? t("Approve for the website", "Idhinisha kwa tovuti") : t("Approve", "Idhinisha"), icon: "check", ok: true, small: true) { decide("approve") }
                Btn(kind == "story" ? t("Don’t use", "Usitumie") : t("Reject", "Kataa"), light: true, small: true) { decide("reject") }
            }.padding(.top, 10)
            Msg(msg, "bad")
        }.padding(.top, 10)
    }
    func decide(_ dec: String) {
        Task {
            let x = await m.api.admin(["action": "review", "rid": it.str("rid"), "decision": dec, "reason": reason])
            if x.ok { await done(); m.reload() } else { msg = reasonText(x.reason) }
        }
    }
}

private struct TeamSection: View {
    @EnvironmentObject var m: AppModel
    @StateObject private var team = Team()
    @State private var name = ""
    @State private var home = ""
    @State private var email = ""
    @State private var phone = ""
    @State private var admin = false
    @State private var shown: (String, String)?
    @State private var msg: (String, String)?
    @State private var tm: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if case let (who, pin)? = shown {
                CardV(fill: C.okBg) {
                    Text(t("PIN for ", "PIN ya ") + who).font(.tSmall).foregroundColor(C.ok)
                    Text(pin).font(.hLarge).tracking(8).foregroundColor(C.ink).padding(.vertical, 6).textSelection(.enabled)
                    Text(t("Shown once. Send it privately; they can’t see it again.", "Inaonyeshwa mara moja. Mtumie kwa siri; hawezi kuiona tena.")).font(.bSmall).foregroundColor(C.text)
                    Btn(t("Done", "Imekamilika"), light: true, small: true) { shown = nil }.padding(.top, 8)
                }.padding(.bottom, 10)
            }
            ForEach(team.people.indices, id: \.self) { i in
                TeamPersonRow(p: team.people[i], shown: $shown, msg: $msg) { await team.load(m) }.id(team.people[i].str("name") + team.people[i].str("home") + team.people[i].str("email"))
            }
            if case let (tone, text)? = msg { Msg(text, tone) }
            CardV {
                H2(t("Add a person", "Ongeza mtu"))
                Text(t("They get their own 4-digit PIN, shown once. Send it to them privately.", "Wanapata PIN yao ya tarakimu 4, inaonyeshwa mara moja. Wapelekee kwa siri.")).font(.bMedium).foregroundColor(C.muted)
                Field(t("Name", "Jina"), $name, placeholder: t("e.g. Jessica Anderson", "mf. Jessica Anderson"))
                HStack(alignment: .bottom, spacing: 10) {
                    Picker1(label: t("Home country", "Nchi ya nyumbani"), value: home, options: [("", t("All countries", "Nchi zote"))] + COUNTRIES) { home = $0 }
                    Field(t("E-mail (optional)", "Barua pepe (si lazima)"), $email).keyboardType(.emailAddress).textInputAutocapitalization(.never)
                }
                Field(t("Phone (optional)", "Simu (si lazima)"), $phone, placeholder: "+254 712 345 678").keyboardType(.phonePad)
                Check(label: t("Admin (sees the Office)", "Msimamizi (anaona Ofisi)"), on: admin) { admin.toggle() }.padding(.top, 8)
                Btn(t("Add and create PIN", "Ongeza na utengeneze PIN"), icon: "plus", enabled: !name.trimmingCharacters(in: .whitespaces).isEmpty) {
                    Task {
                        let nm = name.trimmingCharacters(in: .whitespaces)
                        let x = await m.api.admin(["action": "team-add", "name": nm, "home": home, "email": email.trimmingCharacters(in: .whitespaces), "phone": phone.trimmingCharacters(in: .whitespaces), "admin": admin])
                        if x.ok { shown = (nm, x.json.str("pin")); name = ""; email = ""; phone = ""; admin = false; await team.load(m) }
                        else { msg = ("bad", x.reason == "exists" ? t("Someone with that name is already on the team.", "Mtu mwenye jina hilo tayari yuko kwenye timu.") : reasonText(x.reason)) }
                    }
                }.padding(.top, 10)
            }.padding(.top, 14)
            CardV {
                H2(t("E-mail alerts", "Taarifa kwa barua pepe"))
                Btn(t("Send a test e-mail", "Tuma barua pepe ya majaribio"), icon: "upload", light: true, small: true) {
                    Task {
                        tm = t("Sending…", "Inatuma…")
                        let x = await m.api.admin(["action": "test-mail"])
                        tm = x.ok ? t("Sent ✓ Check the inbox.", "Imetumwa ✓ Angalia barua pepe.") : t("Not sent: the e-mail settings aren’t finished in Cloudflare.", "Haijatumwa: mipangilio ya barua pepe haijakamilika Cloudflare.")
                    }
                }
                Msg(tm)
            }.padding(.top, 14)
        }
        .task { await team.load(m) }
    }
}

private struct TeamPersonRow: View {
    @EnvironmentObject var m: AppModel
    let p: JSON
    @Binding var shown: (String, String)?
    @Binding var msg: (String, String)?
    let again: () async -> Void
    @State private var ph = ""
    @State private var pe = ""
    @State private var pp = ""
    @State private var pa = false
    @State private var ready = false
    var body: some View {
        let inApp = p.str("source") == "app"
        let seen = p.str("lastSeen")
        CardV {
            HStack { Text(p.str("name")).font(.tMedium).foregroundColor(C.ink); Spacer(); if p.bool("admin") { Tag(t("Admin", "Msimamizi"), bg: C.tint, fg: C.deep) } }
            Text((p.str("source") == "app" ? t("PIN from this app", "PIN ya programu hii") : p.str("source") == "cloudflare" ? t("PIN set in Cloudflare", "PIN iliyowekwa Cloudflare") : t("Signs in with monday.com", "Anaingia kwa monday.com"))
                 + " · " + (seen.isEmpty || seen == "null" ? t("last active never", "bado hajatumia") : t("last active ", "alitumia mwisho ") + When.text(seen))).font(.bSmall).foregroundColor(C.muted)
            if inApp {
                HStack(alignment: .bottom, spacing: 10) {
                    Picker1(label: t("Home country", "Nchi ya nyumbani"), value: ph, options: [("", t("All countries", "Nchi zote"))] + COUNTRIES) { ph = $0 }
                    Field(t("E-mail (for alerts)", "Barua pepe (kwa taarifa)"), $pe).keyboardType(.emailAddress).textInputAutocapitalization(.never)
                }
                Field(t("Phone (for Call and WhatsApp)", "Simu (kwa kupiga na WhatsApp)"), $pp, placeholder: "+254 712 345 678").keyboardType(.phonePad)
                Check(label: t("Admin (sees the Office)", "Msimamizi (anaona Ofisi)"), on: pa) { pa.toggle() }.padding(.top, 8)
                HStack(spacing: 8) {
                    Btn(t("Save", "Hifadhi"), small: true) {
                        Task {
                            let x = await m.api.admin(["action": "team-update", "name": p.str("name"), "home": ph, "email": pe, "phone": pp, "admin": pa])
                            msg = x.ok ? ("ok", t("Saved ✓", "Imehifadhiwa ✓")) : ("bad", x.reason == "self" ? t("You can’t remove your own admin role.", "Huwezi kuondoa nafasi yako mwenyewe ya msimamizi.") : reasonText(x.reason))
                            await again()
                        }
                    }
                    Btn(t("New PIN", "PIN mpya"), light: true, small: true) {
                        Task { let x = await m.api.admin(["action": "team-pin", "name": p.str("name")]); if x.ok { shown = (p.str("name"), x.json.str("pin")) } else { msg = ("bad", reasonText(x.reason)) } }
                    }
                    Btn(t("Remove", "Ondoa"), light: true, small: true) { Task { _ = await m.api.admin(["action": "team-remove", "name": p.str("name")]); await again() } }
                }.padding(.top, 6)
            } else {
                Text(t("To change or remove, edit PIN_USERS in Cloudflare, or add them here with an app PIN and remove the Cloudflare entry.", "Kubadilisha au kuondoa, hariri PIN_USERS kwenye Cloudflare, au waongeze hapa kwa PIN ya programu na uondoe kwenye Cloudflare.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 6)
            }
        }
        .padding(.top, 10)
        .onAppear { if !ready { ready = true; ph = p.str("home"); pe = p.str("email"); pp = p.str("phone"); pa = p.bool("admin") } }
    }
}

/// Rough boxes around each country, to spot points that landed in the wrong place.
private let BOUNDS: [String: [Double]] = ["ke": [-4.9, 5.1, 33.8, 42.0], "tz": [-11.8, -0.9, 29.2, 40.6], "ug": [-1.6, 4.3, 29.5, 35.1], "mw": [-17.2, -9.3, 32.6, 36.0]]

private struct Cleanup: View {
    @EnvironmentObject var m: AppModel
    let s: Snapshot
    @State private var openLists: Set<Int> = []
    var body: some View {
        let outside = s.projects.filter { p in
            guard let g = p.gps, let b = BOUNDS[p.ck] else { return false }
            return !(b[0]...b[1]).contains(g.lat) || !(b[2]...b[3]).contains(g.lng)
        }
        let groups = Dictionary(grouping: s.projects.filter { $0.gps != nil }) { String(format: "%.5f,%.5f", $0.gps!.lat, $0.gps!.lng) }
        let dup = groups.values.filter { $0.count > 1 }.flatMap { $0 }
        let lists: [(String, String, [Project])] = [
            (t("No location", "Hakuna mahali"), t("Open each one and pin it (Find a place by name helps).", "Fungua kila mmoja na uweke mahali (Tafuta mahali kwa jina inasaidia)."), s.projects.filter { $0.gps == nil }),
            (t("Location outside its country", "Mahali nje ya nchi yake"), t("Usually latitude and longitude swapped, or a typing slip.", "Mara nyingi latitudo na longitudo zimebadilishana, au kosa la kuandika."), outside),
            (t("Same point as another project", "Mahali sawa na mradi mwingine"), t("Probably a copied location. Check which one is right.", "Huenda mahali hapa pamenakiliwa. Hakikisha kipi ni sahihi."), dup),
            (t("No photo", "Hakuna picha"), t("Add a photo to the “Photos of the project” column in monday.com.", "Ongeza picha kwenye safu ya “Photos of the project” katika monday.com."), s.projects.filter { $0.photo == nil }),
            (t("No visit for over a year", "Hakuna ziara kwa zaidi ya mwaka"), t("Only visits made with the app are known.", "Ziara zilizofanywa kwa programu tu ndizo zinajulikana."), s.projects.filter { $0.gps != nil && ageDays(s, $0) > 365 }),
        ]
        VStack(alignment: .leading, spacing: 0) {
            ForEach(lists.indices, id: \.self) { i in
                let (title, hint, rows) = lists[i]
                let open = openLists.contains(i)
                CardV {
                    Button { if open { openLists.remove(i) } else { openLists.insert(i) } } label: {
                        HStack { Text(title).font(.tSmall).foregroundColor(C.ink); Spacer(); Tag("\(rows.count)", bg: rows.isEmpty ? C.okBg : C.warnBg, fg: rows.isEmpty ? C.ok : C.warn) }
                    }.buttonStyle(.plain)
                    Text(hint).font(.bSmall).foregroundColor(C.muted)
                    if open {
                        ForEach(Array(rows.prefix(60))) { p in
                            Button { m.go(Place(tab: "office", project: p.id)) } label: { Text(p.title + " · " + p.sub).font(.bMedium).foregroundColor(C.blue2).multilineTextAlignment(.leading) }.buttonStyle(.plain).padding(.top, 8)
                        }
                    } else if !rows.isEmpty {
                        Button(t("Show", "Onyesha")) { openLists.insert(i) }.font(.lMedium).foregroundColor(C.blue2).padding(.top, 6)
                    }
                }.padding(.top, 10)
            }
        }
    }
}

private func csv(_ rows: [[Any?]]) -> String {
    rows.map { r in
        r.map { v -> String in
            var x = ""
            switch v { case nil: x = ""; case let s as String: x = s; case let d as Double: x = d == d.rounded() ? String(Int(d)) : String(d); case let n as Int: x = String(n); case let some?: x = "\(some)" }
            return x.contains(where: { $0 == "," || $0 == "\"" || $0 == "\n" }) ? "\"" + x.replacingOccurrences(of: "\"", with: "\"\"") + "\"" : x
        }.joined(separator: ",")
    }.joined(separator: "\r\n")
}

private struct Export: View {
    @EnvironmentObject var m: AppModel
    let s: Snapshot
    @State private var msg: String?
    func share(_ name: String, _ text: String) {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        try? ("\u{FEFF}" + text).write(to: url, atomically: true, encoding: .utf8)
        Share.items([url])
    }
    var body: some View {
        let stamp = When.day()
        VStack(alignment: .leading, spacing: 10) {
            Text(t("CSV files open in Excel and Google Sheets.", "Faili za CSV zinafunguka kwenye Excel na Google Sheets.")).font(.bMedium).foregroundColor(C.muted)
            Btn(t("Projects with locations and status", "Miradi yenye mahali na hali yake"), icon: "upload", light: true, wide: true) {
                share("rafiki-projects-\(stamp).csv", csv([["ID", "Project", "Country", "Area", "Type", "Status", "Latitude", "Longitude", "Last visit", "Condition", "Open repair"]] +
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
                    share("rafiki-visits-\(stamp).csv", csv([["Date", "ID", "Project", "By", "Condition", "Urgency", "Strokes to fill 20 L", "Flow (L/min)", "People using it", "Queue (min)", "E. coli"]] + rows))
                }
            }
            Btn(t("Open repairs and jobs", "Matengenezo na kazi zilizo wazi"), icon: "upload", light: true, wide: true) {
                share("rafiki-repairs-\(stamp).csv", csv([["ID", "Project", "Condition", "Urgency", "Reported", "By", "Problems", "Parts", "Est. KES", "Step", "Assigned to", "Due", "Budget KES"]] +
                    s.repairs.map { id, r in [id, s.projects.first { $0.id == id }?.title ?? r.title, r.state, r.urgency, String(r.at.prefix(10)), r.by, r.problems.joined(separator: "; "), r.parts, r.cost, r.step, r.assignee, r.due, r.budget] }))
            }
            Btn(t("Everything the team did (last 13 months)", "Kila kitu timu ilifanya (miezi 13 iliyopita)"), icon: "upload", light: true, wide: true) {
                Task {
                    if !(await m.loadActivity()) { msg = reasonText("offline"); return }
                    share("rafiki-activity-\(stamp).csv", csv([["Date", "Time", "Who", "What", "Project", "Result"]] + (m.activity ?? []).map { e in
                        let at = e.str("at"); return [String(at.prefix(10)), String(at.dropFirst(11).prefix(5)), e.str("by"), e.str("a"), e.str("t"), e.str("s")]
                    }))
                }
            }
            Msg(msg)
        }
    }
}

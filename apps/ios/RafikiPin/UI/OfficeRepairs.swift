import SwiftUI

// Office › Repairs and Office › Review (Android OfficeRepairs.kt): a head with the numbers that matter, filter chips
// with counts, calm cards that open a sheet holding every action. Server calls: "job" and "review" (/api/admin).

private let STEPS = ["reported", "assigned", "parts", "fixed"]
func repairStepName(_ k: String) -> String {
    switch k { case "assigned": return t("Assigned", "Imegawiwa"); case "parts": return t("Parts ordered", "Vipuri vimeagizwa"); case "fixed": return t("Fixed", "Imerekebishwa"); default: return t("Reported", "Imeripotiwa") }
}
private func urgOrder(_ r: Repair) -> Int { ["urgent": 0, "month": 1, "watch": 2][r.urgency] ?? 3 }
func repairCondition(_ r: Repair) -> String {
    (r.urgency == "urgent" ? t("Urgent", "Haraka") + " · " : r.urgency == "month" ? t("This month", "Mwezi huu") + " · " : "") + (r.state == "notworking" ? t("Not working", "Haifanyi kazi") : t("Needs repair", "Inahitaji matengenezo"))
}
private func approvalWord(_ st: String) -> String {
    switch st { case "pending": return t("waiting for approval", "inasubiri idhini"); case "needed": return t("needs approval", "inahitaji idhini"); case "declined": return t("cost declined", "gharama imekataliwa"); case "approved": return t("cost approved", "gharama imeidhinishwa"); default: return "" }
}
/// A review item's kind: filter key, sheet title, card label, icon, hue.
func reviewKind(_ k: String) -> [String] {
    switch k {
    case "cost": return ["cost", t("Second approval: repair cost", "Idhini ya pili: gharama ya matengenezo"), t("Repair cost", "Gharama ya matengenezo"), "shield", "purple"]
    case "new": return ["new", t("New site", "Eneo jipya"), t("New site", "Eneo jipya"), "plus", "green"]
    case "move": return ["loc", t("Location moved over 500 m", "Mahali pamehamishwa zaidi ya m 500"), t("Location moved", "Mahali pamehamishwa"), "pin", "blue"]
    case "request": return ["loc", t("Change to a confirmed location", "Badiliko la mahali palipothibitishwa"), t("Confirmed location", "Mahali palipothibitishwa"), "lock", "amber"]
    case "story": return ["story", t("Story moment", "Hadithi"), t("Story", "Hadithi"), "spark", "coral"]
    case "story-draft": return ["story", t("Story draft ready", "Rasimu ya hadithi iko tayari"), t("Story draft", "Rasimu ya hadithi"), "chat", "purple"]
    default: return [k, k, k, "inbox", "slate"]
    }
}
private func toneColors(_ tone: String) -> (Color, Color) {
    switch tone { case "bad", "warn", "ok": return otone(tone); default: let h = ohue(tone); return (h.fg, h.bg) }
}
/// "5 h", "6 d" (how long the oldest item has waited).
private func waitAge(_ iso: String) -> String {
    guard let d = When.parse(iso) else { return "—" }
    let h = Int(Date().timeIntervalSince(d) / 3600)
    return h < 1 ? t("< 1 h", "< saa 1") : h < 24 ? t("\(h) h", "saa \(h)") : t("\(h / 24) d", "siku \(h / 24)")
}

/// Who did something: a tap opens their contact sheet.
struct OPersonFact: View {
    let label: String; let name: String; var sub = ""
    var body: some View {
        VStack(spacing: 0) {
            HStack(alignment: .center, spacing: 12) {
                Text(label).font(.bMedium).foregroundColor(C.muted).frame(maxWidth: .infinity, alignment: .leading)
                Button { officeOpenPerson(name) } label: {
                    HStack(spacing: 8) {
                        VStack(alignment: .trailing, spacing: 1) {
                            Text(name).font(.mont(14, .bold)).foregroundColor(C.ink).multilineTextAlignment(.trailing)
                            if !sub.isEmpty { Text(sub).font(.bSmall).foregroundColor(C.muted) }
                        }
                        if name != "—" { OAvatar(name: name, size: 30) }
                    }
                }.buttonStyle(.plain).disabled(name == "—")
            }.padding(.vertical, 8)
            ODivider()
        }
    }
}

/// The second approval, in one line: on the Office job card and the repair tracker.
struct ApprovalNote: View {
    let r: Repair; let a: Approval?; let limit: Int
    var body: some View {
        let st = Money.state(r, a, limit)
        if st != "none" {
            let est = Money.kes(Money.est(r)), lim = Money.kes(limit)
            let (title, sub, look): (String, String, String) = {
                switch st {
                case "pending": return (t("Waiting for a second approval", "Inasubiri idhini ya pili") + " · " + t("asked by ", "imeombwa na ") + (a?.by ?? "—"),
                                        t("\(est) is above \(lim), so another admin approves it before parts are ordered.", "\(est) ni zaidi ya \(lim), kwa hiyo msimamizi mwingine aidhinishe kabla vipuri havijaagizwa."), "purple")
                case "approved": return (t("Cost approved by ", "Gharama imeidhinishwa na ") + (a?.dec ?? "—"), est + ((a?.note ?? "").isEmpty ? "" : " · “\(a?.note ?? "")”") + " · " + t("asked by ", "imeombwa na ") + (a?.by ?? "—"), "green")
                case "declined": return (t("Cost declined by ", "Gharama imekataliwa na ") + (a?.dec ?? "—"), ((a?.note ?? "").isEmpty ? "" : "“\(a?.note ?? "")” · ") + t("Ask again with a new estimate or budget.", "Omba tena kwa makadirio au bajeti mpya."), "coral")
                default: return (t("Needs a second approval before parts are ordered", "Inahitaji idhini ya pili kabla ya kuagiza vipuri"), t("\(est) is above \(lim). Choosing “Parts ordered” asks another admin.", "\(est) ni zaidi ya \(lim). Ukichagua “Vipuri vimeagizwa” msimamizi mwingine ataombwa."), "amber")
                }
            }()
            let hu = ohue(look)
            HStack(alignment: .top, spacing: 10) {
                OIcon(st == "pending" ? "hourglass" : st == "declined" ? "xc" : "shield", size: 20, color: hu.fg)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.tSmall).foregroundColor(C.ink).fixedSize(horizontal: false, vertical: true)
                    Text(sub).font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true)
                }.frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(12).background(hu.bg).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(oSun ? Color.black : .clear, lineWidth: 2))
        }
    }
}

/// The receipt photo: one photo box (camera or gallery), kept on the phone until sent.
struct ReceiptPick: View {
    @Binding var shots: [Shot]
    var body: some View { PhotoSlots(slots: [Slot(label: "Receipt", title: t("Receipt photo", "Picha ya risiti"), hint: t("Optional", "Si lazima"))], shots: $shots, max: 0) }
}

// --- Office › Repairs ------------------------------------------------------------------------------------------

struct OfficeRepairs: View {
    @EnvironmentObject var m: AppModel
    let s: Snapshot
    @State private var people: [String] = []
    @State private var filter = "all"
    @State private var shown = 30
    @State private var picked: OItem?
    @State private var ready = false

    func matches(_ f: String, _ id: String) -> Bool {
        guard let r = s.repairs[id] else { return false }
        switch f {
        case "urgent": return r.urgency == "urgent"; case "notworking": return r.state == "notworking"; case "unassigned": return r.assignee.trimmingCharacters(in: .whitespaces).isEmpty
        case "approval": let st = Money.state(r, m.sx.approvals[id], m.sx.approveAbove); return st == "needed" || st == "pending" || st == "declined"
        case "parts": return r.step == "parts"
        default: return true
        }
    }

    var body: some View {
        let lim = m.sx.approveAbove
        let ids = s.repairs.keys.sorted { a, b in
            let ra = s.repairs[a], rb = s.repairs[b]
            let ua = ra.map(urgOrder) ?? 3, ub = rb.map(urgOrder) ?? 3
            return ua != ub ? ua < ub : (ra?.at ?? "") < (rb?.at ?? "")
        }
        let n: (String) -> Int = { f in ids.filter { matches(f, $0) }.count }
        let list = ids.filter { matches(filter, $0) }
        VStack(alignment: .leading, spacing: 0) {
            OHead(icon: "wrench", hue: "amber", title: t("Repair jobs", "Kazi za matengenezo"), line: t("Most urgent first. Tap a job to move it on, assign it or mark it fixed.", "Za haraka kwanza. Gusa kazi kuisogeza, kuigawa au kuiweka imerekebishwa.")) {
                ONums(items: [
                    ONum(value: "\(ids.count)", label: t("open", "zilizo wazi"), on: filter == "all") { filter = "all"; shown = 30 },
                    ONum(value: "\(n("urgent"))", label: t("urgent", "za haraka"), color: n("urgent") > 0 ? C.bad : C.muted, on: filter == "urgent") { filter = "urgent"; shown = 30 },
                    ONum(value: "\(n("approval"))", label: t("need approval", "zinahitaji idhini"), color: n("approval") > 0 ? ohue("purple").fg : C.muted, on: filter == "approval") { filter = "approval"; shown = 30 },
                ])
            }
            if !ids.isEmpty {
                let fs = [("all", t("All", "Zote")), ("urgent", t("Urgent", "Za haraka")), ("notworking", t("Not working", "Hazifanyi kazi")), ("unassigned", t("Not assigned", "Hazijagawiwa")),
                          ("approval", t("Needs approval", "Zinahitaji idhini")), ("parts", t("Parts ordered", "Vipuri vimeagizwa"))]
                OChips(items: fs.map { ($0.0, $0.1, n($0.0)) }, picked: filter) { filter = $0; shown = 30 }
            }
            if ids.isEmpty { OEmpty(t("No open repairs.", "Hakuna matengenezo yaliyo wazi.")) }
            else if list.isEmpty { OEmpty(t("No repairs here. Try another filter.", "Hakuna matengenezo hapa. Jaribu kichujio kingine.")) }
            ForEach(Array(list.prefix(shown).enumerated()), id: \.element) { i, id in
                if let r = s.repairs[id] { jobCard(id, r, i) }
            }
            if list.count > shown { Btn(t("Show more", "Onyesha zaidi") + " (\(list.count - shown))", light: true, small: true, wide: true) { shown += 30 }.padding(.top, 12) }
            CardV(pad: 4) {
                OFold(icon: "info", hue: "blue", title: t("How repair jobs work", "Jinsi kazi za matengenezo zinavyoenda"), summary: t("Second approval above ", "Idhini ya pili zaidi ya ") + Money.kes(lim)) {
                    let lines = [t("Changes leave a note on the project in monday.com, and the person assigned sees the job in their app.", "Mabadiliko yanaacha maelezo kwenye mradi katika monday.com, na aliyepewa anaona kazi kwenye programu yake."),
                                 t("Repairs estimated above \(Money.kes(lim)) need a second admin to approve the cost before parts are ordered. The person who asks can’t approve their own. The amount is set in Costs.", "Matengenezo yanayokadiriwa zaidi ya \(Money.kes(lim)) yanahitaji msimamizi wa pili kuidhinisha gharama kabla ya kuagiza vipuri. Aliyeomba hawezi kuidhinisha mwenyewe. Kiasi kinawekwa kwenye Gharama."),
                                 t("Add the actual cost and a receipt photo when parts are ordered or the repair is fixed: they add up in Costs.", "Ongeza gharama halisi na picha ya risiti vipuri vikiagizwa au matengenezo yakikamilika: zinajumlishwa kwenye Gharama."),
                                 t("“Mark fixed” closes the job and takes it off this list.", "“Weka imerekebishwa” inafunga kazi na kuiondoa kwenye orodha hii.")]
                    ForEach(lines.indices, id: \.self) { i in
                        HStack(alignment: .top, spacing: 0) { Text("\(i + 1).").font(.bMedium).foregroundColor(C.muted).frame(width: 22, alignment: .leading); Text(lines[i]).font(.bMedium).foregroundColor(C.text).fixedSize(horizontal: false, vertical: true) }.padding(.top, 6)
                    }
                }
            }.padding(.top, 18)
        }
        .task {
            if !ready { ready = true; if let f = OfficeJump.repairs { filter = f; OfficeJump.repairs = nil } }
            people = await m.api.adminGet("team").json.objs("team").map { $0.str("name") }.filter { !$0.isEmpty }.sorted()
        }
        .sheet(item: $picked) { x in RepairJobSheet(s: s, id: x.id, names: people) { picked = nil } }
    }

    func jobCard(_ id: String, _ r: Repair, _ i: Int) -> some View {
        let p = s.projects.first { $0.id == id }
        let late = !r.due.isEmpty && r.due < ODates.today()
        let est = Money.est(r)
        let st = Money.state(r, m.sx.approvals[id], m.sx.approveAbove)
        let l1 = Text(repairStepName(r.step) + " · " + (r.assignee.isEmpty ? t("not assigned", "hajapewa mtu") : r.assignee))
            + (r.due.isEmpty ? Text("") : Text(" · ") + Text(t("due ", "mwisho ") + ODates.dayText(r.due, "")).foregroundColor(late ? C.bad : C.muted).bold())
        let p2 = [p?.country ?? "", est > 0 ? Money.kes(est) : ""].filter { !$0.isEmpty }.joined(separator: " · ")
        let aw = approvalWord(st)
        let l2 = Text(p2) + (aw.isEmpty ? Text("") : Text((p2.isEmpty ? "" : " · ")) + Text(aw).foregroundColor(ohue("purple").fg).bold())
        return ReviewCardV(icon: "wrench", tone: r.state == "notworking" || r.urgency == "urgent" ? "bad" : "warn", title: p?.title ?? (r.title.isEmpty ? id : r.title), pill: repairCondition(r),
                           line1: l1, line2: l2, when: ODates.ago(r.at)) { picked = OItem(id: id, o: [:]) }
            .padding(.top, 10).rise(min(i, 6))
    }
}

/// A clean card: an icon, the title, one pill, two short lines, when; tap for the sheet.
struct ReviewCardV: View {
    let icon: String; let tone: String; let title: String; let pill: String; let line1: Text; let line2: Text; let when: String; var quote = false; let action: () -> Void
    var body: some View {
        let (fg, bg) = toneColors(tone)
        Button { Haptic.tap(); action() } label: {
            HStack(alignment: .top, spacing: 12) {
                OIcon(icon, size: 20, color: fg).frame(width: 40, height: 40).background(bg).clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 13, style: .continuous).stroke(oSun ? fg : .clear, lineWidth: 1.5))
                VStack(alignment: .leading, spacing: 0) {
                    HStack(alignment: .top, spacing: 6) {
                        Text(title).font(.tSmall).foregroundColor(C.ink).lineLimit(2).multilineTextAlignment(.leading).frame(maxWidth: .infinity, alignment: .leading)
                        Text(when).font(.bSmall).foregroundColor(C.muted).lineLimit(1).fixedSize()
                        Icon("chev", size: 18, color: C.muted)
                    }
                    OPillTone(label: pill, fg: fg, bg: bg).padding(.top, 5)
                    line1.font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.leading).padding(.top, 5)
                    line2.font(.bSmall).italic(quote).foregroundColor(C.muted).lineLimit(quote ? 1 : 2).multilineTextAlignment(.leading).padding(.top, 2)
                }
            }
            .padding(14).frame(maxWidth: .infinity, alignment: .leading).background(C.card)
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(C.line, lineWidth: oEdge))
        }.buttonStyle(Press())
    }
}

/// A status pill in given colours.
struct OPillTone: View {
    let label: String; let fg: Color; let bg: Color
    var body: some View {
        HStack(spacing: 6) { Circle().fill(fg).frame(width: 7, height: 7); Text(label).font(.mont(12, .heavy)).foregroundColor(fg).fixedSize(horizontal: false, vertical: true) }
            .padding(.leading, 8).padding(.trailing, 10).padding(.vertical, 3).background(bg).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(oSun ? fg : .clear, lineWidth: 1.5))
    }
}

private struct RrFact: View {
    let label: String; let value: String; var long = false
    var body: some View {
        if !value.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                if long {
                    Text(label).font(.bMedium).foregroundColor(C.muted)
                    Text(value).font(.mont(14, .semibold)).foregroundColor(C.ink).padding(.top, 2).fixedSize(horizontal: false, vertical: true)
                } else {
                    HStack(alignment: .top) {
                        Text(label).font(.bMedium).foregroundColor(C.muted).frame(width: 118, alignment: .leading)
                        Text(value).font(.mont(14, .semibold)).foregroundColor(C.ink).multilineTextAlignment(.trailing).frame(maxWidth: .infinity, alignment: .trailing)
                    }
                }
            }.padding(.vertical, 7)
            ODivider()
        }
    }
}

/// A sheet's head with a toned icon.
private struct RrSheetHead: View {
    let icon: String; let tone: String; let title: String; let sub: String; let close: () -> Void
    var body: some View {
        let (fg, bg) = toneColors(tone)
        OSheetHead(label: sub, labelColor: fg, title: title, lead: { OTileIcon(icon: icon, tint: fg, bg: bg, size: 52) }, close: close)
    }
}

/// One job: the second approval, what was reported, the step, the person, the due date, the money, and Save / Mark fixed.
struct RepairJobSheet: View {
    @EnvironmentObject var m: AppModel
    let s: Snapshot; let id: String; let names: [String]; let close: () -> Void
    @State private var step = ""
    @State private var who = ""
    @State private var due = ""
    @State private var budget = ""
    @State private var note = ""
    @State private var dnote = ""
    @State private var actual = ""
    @State private var shots: [Shot] = []
    @State private var decided: String?
    @State private var ask = false
    @State private var busy = false
    @State private var msg: (String, String)?
    @State private var ready = false

    var body: some View {
        let r = m.snap?.repairs[id] ?? s.repairs[id]
        OSheet {
            if let r { content(r) }
        }
        .onAppear {
            guard !ready, let r else { return }
            ready = true; step = r.step; who = r.assignee; due = r.due; budget = r.budget.map { "\($0)" } ?? ""
        }
    }

    @ViewBuilder func content(_ r: Repair) -> some View {
        let p = s.projects.first { $0.id == id }
        let title = p?.title ?? (r.title.isEmpty ? id : r.title)
        let a = m.sx.approvals[id]
        let st = Money.state(r, a, m.sx.approveAbove)
        let own = a.map { !Money.canDecide($0.by, m.prefs.name) } ?? false
        RrSheetHead(icon: "wrench", tone: r.state == "notworking" || r.urgency == "urgent" ? "bad" : "warn", title: title, sub: repairCondition(r), close: close)
        ApprovalNote(r: r, a: a, limit: m.sx.approveAbove).padding(.top, 14)
        if st == "pending", let a, !a.rid.isEmpty, decided == nil {
            if own { Msg(t("You asked for this, so another admin has to approve it.", "Wewe uliomba hili, kwa hiyo msimamizi mwingine lazima aidhinishe."), "warn") }
            else {
                VStack(alignment: .leading, spacing: 0) {
                    Field(t("Note (optional)", "Maelezo (si lazima)"), $dnote, placeholder: t("e.g. quote checked with the driller", "mf. bei imehakikishwa na mchimbaji"))
                    Btn(t("Approve the cost", "Idhinisha gharama"), icon: "check", enabled: !busy, ok: true, wide: true) { decide(a, "approve") }.padding(.top, 12)
                    Btn(t("Decline", "Kataa"), enabled: !busy, light: true, wide: true) { decide(a, "reject") }.padding(.top, 8)
                }.padding(.horizontal, 14).padding(.bottom, 14).background(ohue("purple").bg).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).padding(.top, 12)
            }
        }
        VStack(spacing: 0) {
            OPersonFact(label: t("Reported by", "Imeripotiwa na"), name: r.by.isEmpty ? "—" : r.by, sub: When.text(r.at))
            RrFact(label: t("Country", "Nchi"), value: p?.country ?? "")
            RrFact(label: t("Problems", "Matatizo"), value: r.problems.joined(separator: ", "), long: true)
            RrFact(label: t("Their note", "Maelezo yao"), value: r.note.isEmpty ? "" : "“\(r.note)”", long: true)
            RrFact(label: t("Parts", "Vipuri"), value: r.parts, long: true)
            RrFact(label: t("Field estimate", "Makadirio ya uwanjani"), value: r.cost.map { Money.kes($0) } ?? "")
        }.padding(.top, 10)
        OLabel(t("Job step", "Hatua ya kazi"))
        let si = STEPS.firstIndex(of: step) ?? 0
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)], spacing: 8) {
            ForEach(Array(STEPS.enumerated()), id: \.offset) { i, k in StepBtn(n: i + 1, label: repairStepName(k), on: step == k, past: i <= si) { step = k } }
        }
        let opts = [("", t("Not assigned", "Hajapewa mtu"))] + ((r.assignee.isEmpty || names.contains(r.assignee)) ? [] : [r.assignee]).map { ($0, $0) } + names.map { ($0, $0) }
        Picker1(label: t("Assigned to", "Amepewa"), value: who, options: opts) { who = $0 }
        DateField(label: t("Due", "Tarehe ya mwisho"), value: due) { due = $0 }
        Field(t("Note (optional)", "Maelezo (si lazima)"), $note, placeholder: t("e.g. parts bought in Eldoret", "mf. vipuri vilinunuliwa Eldoret"))
        VStack(spacing: 0) {
            OFold(icon: "coins", hue: "amber", title: t("Budget, cost and receipt", "Bajeti, gharama na risiti"), summary: r.budget.map { t("Budget ", "Bajeti ") + Money.kes($0) } ?? t("Add what was spent and a receipt", "Ongeza kilichotumika na risiti")) {
                Field(t("Approved budget (KES)", "Bajeti iliyoidhinishwa (KES)"), Binding(get: { budget }, set: { budget = $0.filter(\.isNumber) }), placeholder: r.cost.map { "\($0)" } ?? "", number: true)
                Field(t("Actual cost at this step (KES)", "Gharama halisi katika hatua hii (KES)"), Binding(get: { actual }, set: { actual = $0.filter(\.isNumber) }), placeholder: t("what was spent", "kilichotumika"), number: true)
                OLabel(t("Receipt (optional)", "Risiti (si lazima)"))
                ReceiptPick(shots: $shots)
            }
        }
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(oSun ? Color.black : C.line, lineWidth: oEdge)).padding(.top, 16)
        Btn(t("Save", "Hifadhi"), icon: "check", enabled: !busy, wide: true, busy: busy ? t("Saving…", "Inahifadhi…") : nil) { if step == "fixed" { ask = true } else { save(r, step) } }.padding(.top, 18)
        Btn(t("Mark fixed", "Weka imerekebishwa"), icon: "okc", enabled: !busy, ok: true, wide: true) { withAnimation { ask = true } }.padding(.top, 8)
        if ask {
            OAsk(question: t("Mark \(title) as fixed? The job closes and leaves this list.", "Weka \(title) kama imerekebishwa? Kazi inafungwa na kuondoka kwenye orodha hii."), yes: t("Mark fixed", "Weka imerekebishwa"), red: false,
                 onNo: { ask = false }) { ask = false; save(r, "fixed") }
        }
        if p != nil { Btn(t("Open project", "Fungua mradi"), icon: "arrow", light: true, wide: true) { close(); m.go(Place(tab: "office", project: id)) }.padding(.top, 8) }
        if case let (tone, text)? = msg { Msg(text, tone) }
    }

    func decide(_ a: Approval, _ dec: String) {
        busy = true; msg = ("", t("Saving…", "Inahifadhi…"))
        Task {
            let x = await m.api.admin(["action": "review", "rid": a.rid, "decision": dec, "reason": dnote])
            busy = false
            if x.ok { decided = dec; msg = ("ok", dec == "approve" ? t("Cost approved ✓ Parts can be ordered.", "Gharama imeidhinishwa ✓ Vipuri vinaweza kuagizwa.") : t("Cost declined ✓", "Gharama imekataliwa ✓")); m.reload() }
            else { msg = ("bad", x.reason == "own" ? t("You asked for this, so another admin has to approve it.", "Wewe uliomba hili, kwa hiyo msimamizi mwingine lazima aidhinishe.") : reasonText(x.reason)) }
        }
    }

    func save(_ r: Repair, _ stp: String) {
        busy = true; msg = nil
        Task {
            var b: JSON = ["action": "job", "id": id, "step": stp, "assignee": who, "due": due, "budget": budget, "note": note]
            if let n = Money.parse(actual) { b["actual"] = n }
            if !shots.isEmpty { b["receipt"] = true }
            if !note.trimmingCharacters(in: .whitespaces).isEmpty { b["what"] = note }
            let x = await m.api.admin(b)
            busy = false
            guard x.ok else { msg = ("bad", reasonText(x.reason)); return }
            let held = !x.json.str("held").isEmpty
            if held {
                msg = ("warn", x.json.bool("asked") ? t("Saved. This repair is above \(Money.kes(m.sx.approveAbove)), so parts are ordered after a second admin approves the cost. Asked ✓", "Imehifadhiwa. Matengenezo haya ni zaidi ya \(Money.kes(m.sx.approveAbove)), kwa hiyo vipuri vitaagizwa baada ya msimamizi wa pili kuidhinisha. Imeombwa ✓")
                                                    : t("Still waiting for a second admin to approve the cost.", "Bado inasubiri msimamizi wa pili kuidhinisha gharama."))
                let js = x.json.obj("job")?.str("step") ?? ""; step = js.isEmpty ? r.step : js
            } else {
                step = stp
                let shot = shots.first; let uid = x.json.str("updateId")
                actual = ""; shots = []
                if let shot, !uid.isEmpty, !(await m.api.upload(updateId: uid, file: Outbox.resolve(shot.path), kind: "photo", label: "receipt")).ok {
                    msg = ("warn", t("Saved, but the receipt photo couldn’t be added.", "Imehifadhiwa, lakini picha ya risiti haikuongezwa."))
                } else {
                    msg = ("ok", stp == "fixed" ? t("Marked fixed ✓", "Imewekwa imerekebishwa ✓") : t("Saved ✓", "Imehifadhiwa ✓"))
                }
            }
            note = ""; Haptic.ok(); m.reload()
            if stp == "fixed" && !held { try? await Task.sleep(nanoseconds: 900_000_000); close() }
        }
    }
}

private struct StepBtn: View {
    let n: Int; let label: String; let on: Bool; let past: Bool; let action: () -> Void
    var body: some View {
        Button { Haptic.tap(); action() } label: {
            HStack(spacing: 8) {
                Text("\(n)").font(.mont(12, .heavy)).foregroundColor(on ? .white : past ? C.ok : C.muted).frame(width: 24, height: 24).background(on ? C.blue : past ? C.okBg : C.bg).clipShape(Circle())
                FitWords(label, size: 14, weight: .bold, color: C.ink, lines: 2)
            }
            .padding(.horizontal, 12).padding(.vertical, 8).frame(maxWidth: .infinity, minHeight: 50, alignment: .leading)
            .background(on ? C.tint : C.card).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(on ? (oSun ? Color.black : C.blue) : (oSun ? Color.black : C.line), lineWidth: on ? 2 : oEdge))
        }.buttonStyle(Press())
    }
}

// --- Office › Review -------------------------------------------------------------------------------------------

private func reviewGist(_ x: JSON, miles: Bool) -> String {
    let d = x.obj("data") ?? [:]
    switch x.str("kind") {
    case "cost": return Money.kes(d.int("est")) + (d.str("parts").isEmpty ? "" : " · " + d.str("parts"))
    case "new": return [d.str("type"), d.str("village"), countryName(d.str("ck")) ?? ""].filter { !$0.isEmpty }.joined(separator: " · ")
    case "story": return d.str("quote").isEmpty ? "" : "“\(d.str("quote"))”"
    default: return d.num("dist").map { t("Moved ", "Imehamishwa ") + Geo.dist($0, miles: miles) } ?? d.str("to")
    }
}

struct OfficeReview: View {
    @EnvironmentObject var m: AppModel
    @State private var items: [JSON]?
    @State private var err: String?
    @State private var filter = "all"
    @State private var shown = 30
    @State private var picked: OItem?

    func load() async {
        let x = await m.api.adminGet("review")
        if let a = x.json["items"] as? [Any] { items = a.compactMap { $0 as? JSON }; err = nil } else { err = reasonText(x.reason) }
    }

    var body: some View {
        Group {
            if let all = items { content(all) }
            else if let err { Msg(err, "bad") } else { Text(t("Loading…", "Inapakia…")).foregroundColor(C.muted) }
        }
        .task { await load() }
        .sheet(item: $picked) { x in ReviewSheet(x: x.o, close: { picked = nil }) { Task { await load(); m.reload() } } }
    }

    @ViewBuilder func content(_ all: [JSON]) -> some View {
        let openItems = all.filter { $0.str("status") == "open" }
        let done = Array(all.filter { $0.str("status") != "open" }.prefix(15))
        let n: (String) -> Int = { f in openItems.filter { f == "all" || reviewKind($0.str("kind"))[0] == f }.count }
        let oldest = openItems.map { $0.str("at") }.min()
        let list = openItems.filter { filter == "all" || reviewKind($0.str("kind"))[0] == filter }
        OHead(icon: "inbox", hue: "blue", title: t("Waiting for review", "Zinasubiri kuidhinishwa"), line: t("New sites, moved locations, stories and big repair costs wait here for an admin.", "Maeneo mapya, mahali palipohamishwa, hadithi na gharama kubwa za matengenezo zinasubiri msimamizi hapa.")) {
            ONums(items: [
                ONum(value: "\(openItems.count)", label: t("waiting", "zinasubiri"), on: filter == "all") { filter = "all"; shown = 30 },
                ONum(value: "\(n("cost"))", label: t("repair costs", "gharama za matengenezo"), color: n("cost") > 0 ? ohue("purple").fg : C.muted, on: filter == "cost") { filter = "cost"; shown = 30 },
                ONum(value: oldest.map(waitAge) ?? "—", label: t("oldest waiting", "iliyosubiri zaidi"), color: C.muted),
            ])
        }
        if !openItems.isEmpty {
            let fs = [("all", t("All", "Zote")), ("cost", t("Costs", "Gharama")), ("new", t("New sites", "Maeneo mapya")), ("loc", t("Locations", "Mahali")), ("story", t("Stories", "Hadithi"))]
            OChips(items: fs.map { ($0.0, $0.1, n($0.0)) }, picked: filter) { filter = $0; shown = 30 }
        }
        if openItems.isEmpty { OEmpty(t("Nothing waiting for review.", "Hakuna kinachosubiri kuidhinishwa.")) }
        else if list.isEmpty { OEmpty(t("Nothing here. Try another filter.", "Hakuna kitu hapa. Jaribu kichujio kingine.")) }
        ForEach(Array(list.prefix(shown).enumerated()), id: \.offset) { i, x in
            if x.str("kind") == "story-draft" { storyDraft(x) }
            else {
                let k = reviewKind(x.str("kind"))
                let own = x.str("kind") == "cost" && !Money.canDecide(x.str("by"), m.prefs.name)
                ReviewCardV(icon: k[3], tone: k[4], title: x.str("t").isEmpty ? x.str("id") : x.str("t"), pill: k[2],
                            line1: own ? Text(t("You asked: another admin approves", "Uliomba wewe: msimamizi mwingine aidhinishe")).foregroundColor(ohue("purple").fg).bold() : Text(t("by ", "na ") + (x.str("by").isEmpty ? "—" : x.str("by"))),
                            line2: Text(reviewGist(x, miles: m.miles)), when: ODates.ago(x.str("at")), quote: x.str("kind") == "story") { picked = OItem(id: x.str("rid"), o: x) }
                    .padding(.top, 10).rise(min(i, 6))
            }
        }
        if list.count > shown { Btn(t("Show more", "Onyesha zaidi") + " (\(list.count - shown))", light: true, small: true, wide: true) { shown += 30 }.padding(.top, 12) }
        CardV(pad: 4) {
            OFold(icon: "check", hue: "green", title: t("Recently decided", "Vilivyoamuliwa karibuni"),
                  summary: done.isEmpty ? t("None yet", "Bado hakuna") : t("\(done.count) lately", "\(done.count) karibuni") + (done[0].str("doneBy").isEmpty ? "" : " · " + t("last by ", "mwisho na ") + done[0].str("doneBy"))) {
                if done.isEmpty { Text(t("Nothing decided yet.", "Bado hakuna kilichoamuliwa.")).font(.bSmall).foregroundColor(C.muted) }
                ForEach(Array(done.enumerated()), id: \.offset) { i, x in
                    if i > 0 { ODivider() }
                    let ok = x.str("status") == "approved"
                    HStack(alignment: .top, spacing: 10) {
                        Icon(ok ? "check" : "x", size: 15, color: ok ? C.ok : C.bad).frame(width: 26, height: 26).background(ok ? C.okBg : C.badBg).clipShape(Circle())
                        VStack(alignment: .leading, spacing: 2) {
                            Text(x.str("t").isEmpty ? x.str("id") : x.str("t")).font(.tSmall).foregroundColor(C.ink)
                            Text([reviewKind(x.str("kind"))[2], ok ? t("approved", "imeidhinishwa") : t("turned down", "imekataliwa"), x.str("doneBy"), ODates.ago(x.str("done"))].filter { !$0.isEmpty }.joined(separator: " · ")
                                 + (x.str("reason").isEmpty ? "" : "\n“\(x.str("reason"))”")).font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true)
                        }.frame(maxWidth: .infinity, alignment: .leading)
                    }.padding(.vertical, 9)
                }
            }
        }.padding(.top, 18)
    }

    /// A story whose draft is ready to approve: it opens the project's Stories tab.
    func storyDraft(_ x: JSON) -> some View {
        let id = x.str("id"); let pu = ohue("purple")
        return CardV {
            HStack(spacing: 10) {
                OTileIcon(icon: "chat", tint: pu.fg, bg: pu.bg, size: 36)
                Text(t("Story draft ready", "Rasimu ya hadithi iko tayari")).font(.tSmall).foregroundColor(C.ink).frame(maxWidth: .infinity, alignment: .leading)
                Text(When.text(x.str("at"))).font(.bSmall).foregroundColor(C.muted)
            }
            Text(x.str("t").isEmpty ? id : x.str("t")).font(.tMedium).foregroundColor(C.ink).padding(.top, 6)
            if !x.str("by").isEmpty { Text(t("Drafted by ", "Imeandikwa na ") + x.str("by")).font(.bSmall).foregroundColor(C.muted) }
            Btn(t("Open the Stories tab", "Fungua kichupo cha Hadithi"), icon: "arrow", small: true, wide: true) { m.go(Place(tab: "office", project: id, projectTab: "story")) }.padding(.top, 10)
        }.padding(.top, 10)
    }
}

/// One item waiting for review: what it is, and approve or turn it down (turning down asks first, with a reason).
struct ReviewSheet: View {
    @EnvironmentObject var m: AppModel
    let x: JSON; let close: () -> Void; let decided: () -> Void
    @State private var reason = ""
    @State private var asking: Bool?
    @State private var busy = false
    @State private var done = false
    @State private var msg: (String, String)?

    var body: some View {
        let kind = x.str("kind"); let k = reviewKind(kind); let cost = kind == "cost"
        let d = x.obj("data") ?? [:]
        let own = cost && !Money.canDecide(x.str("by"), m.prefs.name)
        let ask = asking ?? cost
        let verbs: (String, String) = {
            switch kind {
            case "cost": return (t("Approve the cost", "Idhinisha gharama"), t("Decline", "Kataa"))
            case "move": return (t("Keep the new location", "Baki na mahali papya"), t("Put the old one back", "Rudisha mahali pa zamani"))
            case "request": return (t("Approve the change", "Idhinisha badiliko"), t("Keep the confirmed location", "Baki na mahali palipothibitishwa"))
            case "story": return (t("Approve for the website", "Idhinisha kwa tovuti"), t("Don’t use", "Usitumie"))
            default: return (t("Approve", "Idhinisha"), t("Reject", "Kataa"))
            }
        }()
        let whoLabel = cost ? t("Asked by", "Imeombwa na") : t("Sent by", "Imetumwa na")
        let who = x.str("by").isEmpty ? "—" : x.str("by")
        OSheet {
            RrSheetHead(icon: k[3], tone: k[4], title: x.str("t").isEmpty ? x.str("id") : x.str("t"), sub: k[1], close: close)
            switch kind {
            case "cost":
                HStack(alignment: .lastTextBaseline, spacing: 8) {
                    Text(Money.kes(d.int("est"))).font(.mont(22, .heavy)).foregroundColor(C.ink)
                    Text(t("estimate · above ", "makadirio · zaidi ya ") + Money.kes(m.sx.approveAbove)).font(.bSmall).foregroundColor(C.muted)
                }.padding(.top, 16)
                VStack(spacing: 0) {
                    RrFact(label: t("Parts", "Vipuri"), value: d.str("parts"), long: true)
                    RrFact(label: t("Their note", "Maelezo yao"), value: d.str("note").isEmpty ? "" : "“\(d.str("note"))”", long: true)
                    OPersonFact(label: whoLabel, name: who, sub: When.text(x.str("at")))
                }.padding(.top, 6)
                HStack(alignment: .top, spacing: 8) {
                    Icon("alert", size: 18, color: C.muted)
                    Text(t("Asked before ordering parts. The person who asked can’t approve it.", "Imeombwa kabla ya kuagiza vipuri. Aliyeomba hawezi kuidhinisha.")).font(.bMedium).foregroundColor(C.ink).fixedSize(horizontal: false, vertical: true)
                }.padding(12).frame(maxWidth: .infinity, alignment: .leading).background(ohue("slate").bg).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).padding(.top, 14)
            case "story":
                let co = ohue("coral")
                HStack(spacing: 0) {
                    Rectangle().fill(co.fg).frame(width: 4)
                    VStack(alignment: .leading, spacing: 4) {
                        Text("“" + d.str("quote") + "”").font(.bLarge).foregroundColor(C.ink).fixedSize(horizontal: false, vertical: true)
                        if !d.str("who").isEmpty { Text("— " + d.str("who")).font(.bSmall).foregroundColor(C.muted) }
                    }.padding(12).frame(maxWidth: .infinity, alignment: .leading)
                }.background(co.bg).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).fixedSize(horizontal: false, vertical: true).padding(.top, 16)
                VStack(spacing: 0) {
                    OPersonFact(label: whoLabel, name: who, sub: When.text(x.str("at")))
                    RrFact(label: t("Consent", "Ridhaa"), value: t("Given, to quote and photograph", "Imetolewa, kunukuu na kupiga picha"))
                }.padding(.top, 6)
            case "new":
                VStack(spacing: 0) {
                    RrFact(label: t("Type", "Aina"), value: d.str("type")); RrFact(label: t("Village", "Kijiji"), value: d.str("village")); RrFact(label: t("Country", "Nchi"), value: countryName(d.str("ck")) ?? "")
                    RrFact(label: t("Location", "Mahali"), value: d.str("gps")); RrFact(label: t("Their note", "Maelezo yao"), value: d.str("note").isEmpty ? "" : "“\(d.str("note"))”", long: true)
                    OPersonFact(label: whoLabel, name: who, sub: When.text(x.str("at")))
                }.padding(.top, 10)
            default:
                VStack(spacing: 0) {
                    RrFact(label: t("From", "Kutoka"), value: d.str("from").isEmpty ? t("none", "hakuna") : d.str("from")); RrFact(label: t("To", "Hadi"), value: d.str("to"))
                    RrFact(label: t("Distance", "Umbali"), value: d.num("dist").map { Geo.dist($0, miles: m.miles) } ?? ""); RrFact(label: t("How", "Jinsi"), value: d.str("how"), long: true)
                    OPersonFact(label: whoLabel, name: who, sub: When.text(x.str("at")))
                }.padding(.top, 10)
            }
            if own { Msg(t("You asked for this, so another admin approves it.", "Wewe uliomba hili, kwa hiyo msimamizi mwingine ataidhinisha."), "warn") }
            if ask {
                Field(cost ? t("Note (optional)", "Maelezo (si lazima)") : t("Reason (optional)", "Sababu (si lazima)"), $reason,
                      placeholder: cost ? t("e.g. quote checked with the driller", "mf. bei imehakikishwa na mchimbaji") : t("e.g. wrong school", "mf. shule isiyo sahihi"))
            }
            Btn(verbs.0, icon: "check", enabled: !own && !busy && !done, ok: true, wide: true) { decide("approve", ask) }.padding(.top, 18)
            Btn(ask && !cost ? t("Confirm: ", "Thibitisha: ") + verbs.1 : verbs.1, enabled: !own && !busy && !done, light: true, wide: true) { decide("reject", ask) }.padding(.top, 8)
            if !x.str("id").isEmpty { Btn(t("Open project", "Fungua mradi"), icon: "arrow", light: true, wide: true) { close(); m.go(Place(tab: "office", project: x.str("id"))) }.padding(.top, 8) }
            if case let (tone, text)? = msg { Msg(text, tone) }
        }
    }

    func decide(_ dec: String, _ ask: Bool) {
        if dec == "reject" && !ask { withAnimation { asking = true }; return }
        busy = true; msg = ("", t("Saving…", "Inahifadhi…"))
        Task {
            let r = await m.api.admin(["action": "review", "rid": x.str("rid"), "decision": dec, "reason": reason])
            busy = false
            if r.ok {
                done = true; msg = ("ok", dec == "approve" ? t("Approved ✓", "Imeidhinishwa ✓") : t("Turned down ✓", "Imekataliwa ✓")); Haptic.ok()
                try? await Task.sleep(nanoseconds: 700_000_000); decided(); close()
            } else { msg = ("bad", r.reason == "own" ? t("You asked for this, so another admin has to approve it.", "Wewe uliomba hili, kwa hiyo msimamizi mwingine lazima aidhinishe.") : reasonText(r.reason)) }
        }
    }
}

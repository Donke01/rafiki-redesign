import SwiftUI
import UIKit

// Office › Repairs and Office › Review, the same look as Office › Community: a head with the numbers that matter,
// filter chips with counts, calm cards (one title, one status pill, two short lines) that open a sheet holding every
// action, and the extras folded away. The same as Android's OfficeRepairs.kt; the server calls are "job" and "review".

private let STEPS = ["reported", "assigned", "parts", "fixed"]
func rrStepName(_ k: String) -> String {
    switch k { case "assigned": return t("Assigned", "Imegawiwa"); case "parts": return t("Parts ordered", "Vipuri vimeagizwa"); case "fixed": return t("Fixed", "Imerekebishwa"); default: return t("Reported", "Imeripotiwa") }
}
private func urgOrder(_ r: Repair) -> Int { ["urgent": 0, "month": 1, "watch": 2][r.urgency] ?? 3 }
/// Waiting for, or needing, the second approval.
func rrNeedsApproval(_ r: Repair, _ limit: Int) -> Bool { ["needed", "pending", "declined"].contains(Money.state(r, limit)) }
func rrMatches(_ f: String, _ r: Repair, _ limit: Int) -> Bool {
    switch f {
    case "urgent": return r.urgency == "urgent"; case "notworking": return r.state == "notworking"; case "unassigned": return r.assignee.trimmingCharacters(in: .whitespaces).isEmpty
    case "approval": return rrNeedsApproval(r, limit); case "parts": return r.step == "parts"; default: return true
    }
}
func rrCondition(_ r: Repair) -> String {
    (r.urgency == "urgent" ? t("Urgent", "Haraka") + " · " : r.urgency == "month" ? t("This month", "Mwezi huu") + " · " : "") + (r.state == "notworking" ? t("Not working", "Haifanyi kazi") : t("Needs repair", "Inahitaji matengenezo"))
}
private func approvalWord(_ r: Repair, _ limit: Int) -> String {
    switch Money.state(r, limit) {
    case "pending": return t("waiting for approval", "inasubiri idhini"); case "needed": return t("needs approval", "inahitaji idhini")
    case "declined": return t("cost declined", "gharama imekataliwa"); case "approved": return t("cost approved", "gharama imeidhinishwa"); default: return ""
    }
}
/// A review item's kind: filter key, sheet title, card label, icon, hue.
func rrKind(_ k: String) -> [String] {
    switch k {
    case "cost": return ["cost", t("Second approval: repair cost", "Idhini ya pili: gharama ya matengenezo"), t("Repair cost", "Gharama ya matengenezo"), "shield", "purple"]
    case "new": return ["new", t("New site", "Eneo jipya"), t("New site", "Eneo jipya"), "plus", "green"]
    case "move": return ["loc", t("Location moved over 500 m", "Mahali pamehamishwa zaidi ya m 500"), t("Location moved", "Mahali pamehamishwa"), "pin", "blue"]
    case "request": return ["loc", t("Change to a confirmed location", "Badiliko la mahali palipothibitishwa"), t("Confirmed location", "Mahali palipothibitishwa"), "lock", "amber"]
    case "story": return ["story", t("Story moment", "Hadithi"), t("Story", "Hadithi"), "spark", "coral"]
    default: return [k, k, k, "inbox", "slate"]
    }
}
/// "5 h", "6 d" (how long the oldest item has waited).
func rrAge(_ iso: String) -> String {
    guard let d = When.parse(iso) else { return "—" }
    let h = Int(Date().timeIntervalSince(d) / 3600)
    return h < 1 ? t("< 1 h", "< saa 1") : h < 24 ? t("\(h) h", "saa \(h)") : t("\(h / 24) d", "siku \(h / 24)")
}
private func rrTone(_ tone: String) -> (Color, Color) {
    switch tone { case "bad": return (C.bad, C.badBg); case "warn": return (C.warn, C.warnBg); case "ok": return (C.ok, C.okBg); default: let h = C.named(tone); return (h.fg, h.bg) }
}
/// Pieces joined by " · "; a piece with a colour is bold in that colour.
private func rrLine(_ parts: [(String, Color?)]) -> Text {
    var out = Text("")
    for (i, p) in parts.filter({ !$0.0.isEmpty }).enumerated() {
        if i > 0 { out = out + Text(" · ") }
        out = out + (p.1 != nil ? Text(p.0).bold().foregroundColor(p.1!) : Text(p.0))
    }
    return out
}

/// After an Office job save: the message to show (held for approval, or the receipt), and sends the receipt.
@MainActor func jobMoneyDone(_ m: AppModel, _ x: Reply, shots: [Shot], limit: Int) async -> (String, String)? {
    if !x.json.str("held").isEmpty {
        return ("warn", x.json.bool("asked") ? t("Saved. This repair is above \(Money.kes(limit)), so parts are ordered after a second admin approves the cost. Asked ✓", "Imehifadhiwa. Matengenezo haya ni zaidi ya \(Money.kes(limit)), kwa hiyo vipuri vitaagizwa baada ya msimamizi wa pili kuidhinisha. Imeombwa ✓")
                : t("Still waiting for a second admin to approve the cost.", "Bado inasubiri msimamizi wa pili kuidhinisha gharama."))
    }
    let uid = x.json.str("updateId")
    if let shot = shots.first, !uid.isEmpty, !(await m.api.upload(updateId: uid, file: Outbox.resolve(shot.path), kind: "photo", label: "receipt")).ok {
        return ("warn", t("Saved, but the receipt photo couldn’t be added.", "Imehifadhiwa, lakini picha ya risiti haikuongezwa."))
    }
    return nil
}

private struct RrStat: View {
    let n: String; let label: String; let color: Color; let on: Bool; var action: (() -> Void)?
    var body: some View {
        let inner = VStack(spacing: 1) {
            Text(n).font(.mont(22, .heavy)).foregroundColor(color).lineLimit(1)
            Text(label).font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.center)
            Spacer(minLength: 0)
        }.padding(.vertical, 10).padding(.horizontal, 6).frame(maxWidth: .infinity, minHeight: 64)
        .background(on ? C.tint : C.bg).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(on ? C.blue : C.sun ? Color.black : .clear, lineWidth: on ? 2 : 1.5))
        if let action { Button { Haptic.tap(); action() } label: { inner }.buttonStyle(Press()) } else { inner }
    }
}

/// A clean card: an icon, the title, one pill, two short lines, when; tap for the sheet.
private struct RrCard: View {
    let icon: String; let tone: String; let title: String; let pill: String; let line1: Text?; let line2: Text?; let when: String; var quote = false; let tap: () -> Void
    var body: some View {
        let (fg, bg) = rrTone(tone)
        Button { Haptic.tap(); tap() } label: {
            HStack(alignment: .top, spacing: 12) {
                Icon(icon, size: 20, color: fg).frame(width: 40, height: 40).background(bg).clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 13, style: .continuous).stroke(C.sun ? fg : .clear, lineWidth: 1.5))
                VStack(alignment: .leading, spacing: 0) {
                    HStack(alignment: .top, spacing: 8) {
                        Text(title).font(.tSmall).foregroundColor(C.ink).lineLimit(2).multilineTextAlignment(.leading).frame(maxWidth: .infinity, alignment: .leading)
                        Text(when).font(.bSmall).foregroundColor(C.muted).lineLimit(1).fixedSize()
                        Icon("chev", size: 18, color: C.muted)
                    }
                    OcvPill(label: pill, tone: tone == "bad" || tone == "warn" ? tone : "has").padding(.top, 5)
                    if let line1 { line1.font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.leading).padding(.top, 5) }
                    if let line2 { line2.font(.bSmall).italic(quote).foregroundColor(C.muted).lineLimit(quote ? 1 : 2).multilineTextAlignment(.leading).padding(.top, 2) }
                }
            }
            .padding(14).frame(maxWidth: .infinity, alignment: .leading).background(C.card).clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(C.line, lineWidth: C.sun ? 2 : 1))
        }.buttonStyle(Press())
    }
}

private struct RrSheetHead: View {
    let icon: String; let tone: String; let title: String; let sub: String; let close: () -> Void
    var body: some View {
        let (fg, bg) = rrTone(tone)
        HStack(spacing: 14) {
            Icon(icon, size: 26, color: fg).frame(width: 52, height: 52).background(bg).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            VStack(alignment: .leading, spacing: 1) { Text(sub).font(.mont(14, .heavy)).foregroundColor(fg); Text(title).font(.tLarge).foregroundColor(C.ink) }
            Spacer(minLength: 8)
            Button(action: close) { Icon("x", size: 20, color: C.ink).frame(width: 44, height: 44).background(Circle().fill(C.bg)) }.buttonStyle(.plain).accessibilityLabel(t("Close", "Funga"))
        }
    }
}

private struct RrFact: View {
    let label: String; let value: String; var long = false
    var body: some View {
        if !value.isEmpty {
            VStack(spacing: 0) {
                if long {
                    VStack(alignment: .leading, spacing: 2) { Text(label).font(.bMedium).foregroundColor(C.muted); Text(value).font(.mont(14, .semibold)).foregroundColor(C.ink) }.frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 7)
                } else {
                    HStack(alignment: .top) { Text(label).font(.bMedium).foregroundColor(C.muted).frame(width: 118, alignment: .leading); Text(value).font(.mont(14, .semibold)).foregroundColor(C.ink).multilineTextAlignment(.trailing).frame(maxWidth: .infinity, alignment: .trailing) }.padding(.vertical, 7)
                }
                Rectangle().fill(C.line).frame(height: 1)
            }
        }
    }
}

/// A secondary button in a sheet: a clear outline on the white sheet, the same size as the main ones.
private struct RrLight: View {
    let text: String; var icon: String? = nil; var enabled = true; var small = false; let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let icon { Icon(icon, size: small ? 18 : 20, color: enabled ? C.ink : C.muted) }
                Text(text).font(small ? .mont(14, .bold) : .lLarge).foregroundColor(enabled ? C.ink : C.muted).multilineTextAlignment(.center)
            }.padding(.horizontal, 18).frame(maxWidth: .infinity, minHeight: small ? 44 : 52)
            .background(C.dark ? C.bg : Color(hex: 0xF4F7FA)).clipShape(Capsule()).overlay(Capsule().stroke(C.sun ? Color.black : C.edge, lineWidth: C.sun ? 2 : 1.5))
        }.buttonStyle(Press()).disabled(!enabled)
    }
}

// MARK: - Office › Repairs

struct RepairsSection: View {
    @EnvironmentObject var m: AppModel
    let s: Snapshot
    @State private var people: [String] = []
    @State private var filter = "all"
    @State private var shown = 30
    @State private var picked: String?
    var body: some View {
        let lim = s.approveAbove
        let ids = s.repairs.keys.sorted { a, b in let ra = s.repairs[a]!, rb = s.repairs[b]!; return urgOrder(ra) != urgOrder(rb) ? urgOrder(ra) < urgOrder(rb) : ra.at < rb.at }
        let n = { (f: String) in ids.filter { rrMatches(f, s.repairs[$0]!, lim) }.count }
        let list = ids.filter { rrMatches(filter, s.repairs[$0]!, lim) }
        VStack(alignment: .leading, spacing: 0) {
            OcvHead(icon: "wrench", hue: "amber", title: t("Repair jobs", "Kazi za matengenezo"), line: t("Most urgent first. Tap a job to move it on, assign it or mark it fixed.", "Za haraka kwanza. Gusa kazi kuisogeza, kuigawa au kuiweka imerekebishwa.")) {
                HStack(spacing: 8) {
                    RrStat(n: "\(ids.count)", label: t("open", "zilizo wazi"), color: C.ink, on: filter == "all") { filter = "all"; shown = 30 }
                    RrStat(n: "\(n("urgent"))", label: t("urgent", "za haraka"), color: n("urgent") > 0 ? C.bad : C.muted, on: filter == "urgent") { filter = "urgent"; shown = 30 }
                    RrStat(n: "\(n("approval"))", label: t("need approval", "zinahitaji idhini"), color: n("approval") > 0 ? C.named("purple").fg : C.muted, on: filter == "approval") { filter = "approval"; shown = 30 }
                }.fixedSize(horizontal: false, vertical: true).padding(.top, 14)
            }
            if !ids.isEmpty {
                let fs = [("all", t("All", "Zote")), ("urgent", t("Urgent", "Za haraka")), ("notworking", t("Not working", "Hazifanyi kazi")), ("unassigned", t("Not assigned", "Hazijagawiwa")), ("approval", t("Needs approval", "Zinahitaji idhini")), ("parts", t("Parts ordered", "Vipuri vimeagizwa"))]
                OcvChips(items: fs.map { ($0.1, "\(n($0.0))", filter == $0.0) }) { filter = fs[$0].0; shown = 30 }
            }
            if ids.isEmpty { EmptyDrop(text: t("No open repairs.", "Hakuna matengenezo yaliyo wazi.")) }
            else if list.isEmpty { EmptyDrop(text: t("No repairs here. Try another filter.", "Hakuna matengenezo hapa. Jaribu kichujio kingine.")) }
            ForEach(Array(list.prefix(shown).enumerated()), id: \.element) { i, id in
                if let r = s.repairs[id] {
                    let p = s.projects.first { $0.id == id }
                    let late = !r.due.isEmpty && r.due < When.localDay(), est = Money.estOf(r)
                    RrCard(icon: "wrench", tone: r.state == "notworking" || r.urgency == "urgent" ? "bad" : "warn", title: p?.title ?? (r.title.isEmpty ? id : r.title), pill: rrCondition(r),
                           line1: rrLine([(rrStepName(r.step), nil), (r.assignee.isEmpty ? t("not assigned", "hajapewa mtu") : r.assignee, nil), (r.due.isEmpty ? "" : t("due ", "mwisho ") + Dates.day(r.due), late ? C.bad : nil)]),
                           line2: rrLine([(p?.country ?? "", nil), (est > 0 ? Money.kes(est) : "", nil), (approvalWord(r, lim), C.named("purple").fg)]), when: smsAgo(r.at)) { picked = id }
                        .padding(.top, 10).rise(min(i, 6))
                }
            }
            if list.count > shown { Btn(t("Show more", "Onyesha zaidi") + " (\(list.count - shown))", light: true, small: true, wide: true) { shown += 30 }.padding(.top, 12) }
            CardV(pad: 4) {
                OcvFold(icon: "info", hue: "blue", title: t("How repair jobs work", "Jinsi kazi za matengenezo zinavyoenda"), summary: t("Second approval above ", "Idhini ya pili zaidi ya ") + Money.kes(lim)) {
                    ForEach(Array([
                        t("Changes leave a note on the project in monday.com, and the person assigned sees the job in their app.", "Mabadiliko yanaacha maelezo kwenye mradi katika monday.com, na aliyepewa anaona kazi kwenye programu yake."),
                        t("Repairs estimated above \(Money.kes(lim)) need a second admin to approve the cost before parts are ordered. The person who asks can’t approve their own. The amount is set in Costs.", "Matengenezo yanayokadiriwa zaidi ya \(Money.kes(lim)) yanahitaji msimamizi wa pili kuidhinisha gharama kabla ya kuagiza vipuri. Aliyeomba hawezi kuidhinisha mwenyewe. Kiasi kinawekwa kwenye Gharama."),
                        t("Add the actual cost and a receipt photo when parts are ordered or the repair is fixed: they add up in Costs.", "Ongeza gharama halisi na picha ya risiti vipuri vikiagizwa au matengenezo yakikamilika: zinajumlishwa kwenye Gharama."),
                        t("“Mark fixed” closes the job and takes it off this list.", "“Weka imerekebishwa” inafunga kazi na kuiondoa kwenye orodha hii."),
                    ].enumerated()), id: \.offset) { i, line in
                        HStack(alignment: .top, spacing: 0) { Text("\(i + 1).").font(.bMedium).foregroundColor(C.muted).frame(width: 22, alignment: .leading); Text(line).font(.bMedium).foregroundColor(C.text) }.padding(.top, 6)
                    }
                }
            }.padding(.top, 18)
        }
        .task { let a = await m.api.adminGet("team").json.objs("team"); people = a.map { $0.str("name") }.filter { !$0.isEmpty }.sorted() }
        .sheet(item: Binding(get: { picked.map { WhoBox(name: $0) } }, set: { picked = $0?.name })) { b in
            RrJobSheet(s: m.snap ?? s, id: b.name, names: people) { picked = nil; m.go(Place(tab: "office", project: b.name)) }.environmentObject(m)
        }
    }
}

/// One job: the second approval, what was reported, the step, the person, the due date, the money, and Save / Mark fixed.
private struct RrJobSheet: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.dismiss) private var dismiss
    let s: Snapshot; let id: String; let names: [String]; let openIt: () -> Void
    @State private var first: Repair?
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
        let snap = m.snap ?? s
        if let r = snap.repairs[id] ?? first {
            let p = snap.projects.first { $0.id == id }
            let title = p?.title ?? (r.title.isEmpty ? id : r.title)
            let a = r.approval, st = Money.state(r, snap.approveAbove)
            let own = a.map { !Money.canDecide($0.by, m.prefs.name) } ?? false
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    RrSheetHead(icon: "wrench", tone: r.state == "notworking" || r.urgency == "urgent" ? "bad" : "warn", title: title, sub: rrCondition(r)) { dismiss() }
                    ApprovalNote(r: r, limit: snap.approveAbove).padding(.top, 14)
                    if st == "pending", let a, !a.rid.isEmpty, decided == nil {
                        if own { Msg(t("You asked for this, so another admin has to approve it.", "Wewe uliomba hili, kwa hiyo msimamizi mwingine lazima aidhinishe."), "warn") }
                        else {
                            VStack(alignment: .leading, spacing: 0) {
                                Field(t("Note (optional)", "Maelezo (si lazima)"), $dnote, placeholder: t("e.g. quote checked with the driller", "mf. bei imehakikishwa na mchimbaji"))
                                Btn(t("Approve the cost", "Idhinisha gharama"), icon: "check", enabled: !busy, ok: true, wide: true) { decide(a.rid, "approve") }.padding(.top, 12)
                                RrLight(text: t("Decline", "Kataa"), enabled: !busy) { decide(a.rid, "reject") }.padding(.top, 8)
                            }.padding(.horizontal, 14).padding(.bottom, 14).background(C.named("purple").bg).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).padding(.top, 12)
                        }
                    }
                    VStack(spacing: 0) {
                        PersonFact(label: t("Reported by", "Imeripotiwa na"), name: r.by.isEmpty ? "—" : r.by, sub: Dates.dateTime(r.at))
                        RrFact(label: t("Country", "Nchi"), value: p?.country ?? "")
                        RrFact(label: t("Problems", "Matatizo"), value: r.problems.map(problemName).joined(separator: ", "), long: true)
                        RrFact(label: t("Their note", "Maelezo yao"), value: r.note.isEmpty ? "" : "“\(r.note)”", long: true)
                        RrFact(label: t("Parts", "Vipuri"), value: r.parts, long: true)
                        RrFact(label: t("Field estimate", "Makadirio ya uwanjani"), value: r.cost.map { Money.kes($0) } ?? "")
                    }.padding(.top, 10)
                    GroupLabel(t("Job step", "Hatua ya kazi"), top: 18)
                    let si = STEPS.firstIndex(of: step) ?? 0
                    LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)], spacing: 8) {
                        ForEach(Array(STEPS.enumerated()), id: \.offset) { i, k in
                            Button { Haptic.tap(); step = k } label: {
                                HStack(spacing: 8) {
                                    Text("\(i + 1)").font(.mont(12, .heavy)).foregroundColor(step == k ? .white : i <= si ? C.ok : C.muted).frame(width: 24, height: 24).background(Circle().fill(step == k ? C.blue : i <= si ? C.okBg : C.bg))
                                    Text(rrStepName(k)).font(.mont(14, .bold)).foregroundColor(C.ink).multilineTextAlignment(.leading)
                                    Spacer(minLength: 0)
                                }.padding(.horizontal, 12).padding(.vertical, 8).frame(minHeight: 50).background(step == k ? C.tint : C.card).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(step == k ? C.blue : C.sun ? Color.black : C.line, lineWidth: step == k ? 2 : 1))
                            }.buttonStyle(.plain)
                        }
                    }
                    let opts = [("", t("Not assigned", "Hajapewa mtu"))] + ((!r.assignee.isEmpty && !names.contains(r.assignee) ? [r.assignee] : []) + names).map { ($0, $0) }
                    Picker1(label: t("Assigned to", "Amepewa"), value: who, options: opts) { who = $0 }
                    if !who.isEmpty { PersonChip(name: who).padding(.top, 6) }
                    DateField(label: t("Due", "Tarehe ya mwisho"), value: due) { due = $0 }
                    Field(t("Note (optional)", "Maelezo (si lazima)"), $note, placeholder: t("e.g. parts bought in Eldoret", "mf. vipuri vilinunuliwa Eldoret"))
                    VStack(spacing: 0) {
                        OcvFold(icon: "coins", hue: "amber", title: t("Budget, cost and receipt", "Bajeti, gharama na risiti"), summary: r.budget.map { t("Budget ", "Bajeti ") + Money.kes($0) } ?? t("Add what was spent and a receipt", "Ongeza kilichotumika na risiti")) {
                            Field(t("Approved budget (KES)", "Bajeti iliyoidhinishwa (KES)"), Binding(get: { budget }, set: { budget = $0.filter(\.isNumber) }), placeholder: r.cost.map { "\($0)" } ?? "", number: true)
                            Field(t("Actual cost at this step (KES)", "Gharama halisi katika hatua hii (KES)"), Binding(get: { actual }, set: { actual = $0.filter(\.isNumber) }), placeholder: t("what was spent", "kilichotumika"), number: true)
                            GroupLabel(t("Receipt (optional)", "Risiti (si lazima)"), top: 18)
                            ReceiptPick(shots: $shots)
                        }
                    }.overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.sun ? Color.black : C.line, lineWidth: C.sun ? 2 : 1)).padding(.top, 16)
                    Btn(t("Save", "Hifadhi"), icon: "check", enabled: !busy, wide: true) { if step == "fixed" { ask = true } else { save(r, step) } }.padding(.top, 18)
                    Btn(t("Mark fixed", "Weka imerekebishwa"), icon: "okc", enabled: !busy, ok: true, wide: true) { ask = true }.padding(.top, 8)
                    if ask {
                        VStack(alignment: .leading, spacing: 10) {
                            Text(t("Mark \(title) as fixed? The job closes and leaves this list.", "Weka \(title) kama imerekebishwa? Kazi inafungwa na kuondoka kwenye orodha hii.")).font(.mont(14, .semibold)).foregroundColor(C.ink)
                            HStack(spacing: 8) {
                                RrLight(text: t("Cancel", "Ghairi"), small: true) { ask = false }
                                Btn(t("Mark fixed", "Weka imerekebishwa"), ok: true, small: true, wide: true) { ask = false; save(r, "fixed") }
                            }
                        }.padding(14).background(C.named("slate").bg).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).padding(.top, 10)
                    }
                    RrLight(text: t("Open project", "Fungua mradi"), icon: "chev") { dismiss(); openIt() }.padding(.top, 8)
                    if let msg { Msg(msg.1, msg.0) }
                }.padding(.horizontal, 20).padding(.top, 20).padding(.bottom, 28)
            }
            .background(C.card.ignoresSafeArea())
            .onAppear { if !ready { ready = true; first = r; step = r.step; who = r.assignee; due = r.due; budget = r.budget.map { "\($0)" } ?? "" } }
        }
    }
    private func decide(_ rid: String, _ dec: String) {
        busy = true; msg = ("", t("Saving…", "Inahifadhi…"))
        Task {
            let x = await m.api.admin(["action": "review", "rid": rid, "decision": dec, "reason": dnote])
            busy = false
            if x.ok { decided = dec; msg = ("ok", dec == "approve" ? t("Cost approved ✓ Parts can be ordered.", "Gharama imeidhinishwa ✓ Vipuri vinaweza kuagizwa.") : t("Cost declined ✓", "Gharama imekataliwa ✓")); m.reload() }
            else { msg = ("bad", x.reason == "own" ? t("You asked for this, so another admin has to approve it.", "Wewe uliomba hili, kwa hiyo msimamizi mwingine lazima aidhinishe.") : reasonText(x.reason)) }
        }
    }
    private func save(_ r: Repair, _ stp: String) {
        busy = true; msg = ("", t("Saving…", "Inahifadhi…"))
        Task {
            var b: JSON = ["action": "job", "id": id, "step": stp, "assignee": who, "due": due, "budget": budget, "note": note]
            if let a = Money.parse(actual) { b["actual"] = a }
            if !shots.isEmpty { b["receipt"] = true }
            if !note.isEmpty { b["what"] = note }
            let x = await m.api.admin(b)
            busy = false
            let held = x.ok && !x.json.str("held").isEmpty
            if x.ok {
                msg = await jobMoneyDone(m, x, shots: shots, limit: s.approveAbove) ?? ("ok", stp == "fixed" ? t("Marked fixed ✓", "Imewekwa imerekebishwa ✓") : t("Saved ✓", "Imehifadhiwa ✓"))
                actual = ""; shots = []; note = ""; Haptic.ok()
                step = held ? ((x.json.obj("job")?.str("step")).flatMap { $0.isEmpty ? nil : $0 } ?? r.step) : stp
                m.reload()
                if stp == "fixed" && !held { try? await Task.sleep(nanoseconds: 900_000_000); dismiss() }
            } else { msg = ("bad", reasonText(x.reason)) }
        }
    }
}

// MARK: - Office › Review

struct ReviewSection: View {
    @EnvironmentObject var m: AppModel
    @State private var items: [JSON]?
    @State private var err: String?
    @State private var tick = 0
    @State private var filter = "all"
    @State private var shown = 30
    @State private var picked: JSON?
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let all = items {
                let open = all.filter { $0.str("status") == "open" }
                let done = Array(all.filter { $0.str("status") != "open" }.prefix(15))
                let n = { (f: String) in open.filter { f == "all" || rrKind($0.str("kind"))[0] == f }.count }
                let oldest = open.map { $0.str("at") }.min()
                OcvHead(icon: "inbox", hue: "blue", title: t("Waiting for review", "Zinasubiri kuidhinishwa"), line: t("New sites, moved locations, stories and big repair costs wait here for an admin.", "Maeneo mapya, mahali palipohamishwa, hadithi na gharama kubwa za matengenezo zinasubiri msimamizi hapa.")) {
                    HStack(spacing: 8) {
                        RrStat(n: "\(open.count)", label: t("waiting", "zinasubiri"), color: C.ink, on: filter == "all") { filter = "all"; shown = 30 }
                        RrStat(n: "\(n("cost"))", label: t("repair costs", "gharama za matengenezo"), color: n("cost") > 0 ? C.named("purple").fg : C.muted, on: filter == "cost") { filter = "cost"; shown = 30 }
                        RrStat(n: oldest.map(rrAge) ?? "—", label: t("oldest waiting", "iliyosubiri zaidi"), color: C.muted, on: false, action: nil)
                    }.fixedSize(horizontal: false, vertical: true).padding(.top, 14)
                }
                if !open.isEmpty {
                    let fs = [("all", t("All", "Zote")), ("cost", t("Costs", "Gharama")), ("new", t("New sites", "Maeneo mapya")), ("loc", t("Locations", "Mahali")), ("story", t("Stories", "Hadithi"))]
                    OcvChips(items: fs.map { ($0.1, "\(n($0.0))", filter == $0.0) }) { filter = fs[$0].0; shown = 30 }
                }
                let list = open.filter { filter == "all" || rrKind($0.str("kind"))[0] == filter }
                if open.isEmpty { EmptyDrop(text: t("Nothing waiting for review.", "Hakuna kinachosubiri kuidhinishwa.")) }
                else if list.isEmpty { EmptyDrop(text: t("Nothing here. Try another filter.", "Hakuna kitu hapa. Jaribu kichujio kingine.")) }
                ForEach(Array(list.prefix(shown).enumerated()), id: \.offset) { i, x in
                    let k = rrKind(x.str("kind"))
                    let own = x.str("kind") == "cost" && !Money.canDecide(x.str("by"), m.prefs.name)
                    RrCard(icon: k[3], tone: k[4], title: x.str("t").isEmpty ? x.str("id") : x.str("t"), pill: k[2],
                           line1: own ? rrLine([(t("You asked: another admin approves", "Uliomba wewe: msimamizi mwingine aidhinishe"), C.named("purple").fg)]) : rrLine([(t("by ", "na ") + (x.str("by").isEmpty ? "—" : x.str("by")), nil)]),
                           line2: rrLine([(rrGist(x, m.miles), nil)]), when: smsAgo(x.str("at")), quote: x.str("kind") == "story") { picked = x }
                        .padding(.top, 10).rise(min(i, 6))
                }
                if list.count > shown { Btn(t("Show more", "Onyesha zaidi") + " (\(list.count - shown))", light: true, small: true, wide: true) { shown += 30 }.padding(.top, 12) }
                CardV(pad: 4) {
                    OcvFold(icon: "check", hue: "green", title: t("Recently decided", "Vilivyoamuliwa karibuni"),
                            summary: done.isEmpty ? t("None yet", "Bado hakuna") : t("\(done.count) lately", "\(done.count) karibuni") + (done[0].str("doneBy").isEmpty ? "" : " · " + t("last by ", "mwisho na ") + done[0].str("doneBy"))) {
                        if done.isEmpty { Text(t("Nothing decided yet.", "Bado hakuna kilichoamuliwa.")).font(.bSmall).foregroundColor(C.muted) }
                        ForEach(Array(done.enumerated()), id: \.offset) { i, x in
                            let ok = x.str("status") == "approved"
                            if i > 0 { Rectangle().fill(C.line).frame(height: 1) }
                            HStack(alignment: .top, spacing: 10) {
                                Icon(ok ? "check" : "x", size: 15, color: ok ? C.ok : C.bad).frame(width: 26, height: 26).background(Circle().fill(ok ? C.okBg : C.badBg))
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(x.str("t").isEmpty ? x.str("id") : x.str("t")).font(.tSmall).foregroundColor(C.ink)
                                    Text([rrKind(x.str("kind"))[2], ok ? t("approved", "imeidhinishwa") : t("turned down", "imekataliwa"), x.str("doneBy"), smsAgo(x.str("done"))].filter { !$0.isEmpty }.joined(separator: " · ")
                                         + (x.str("reason").isEmpty ? "" : "\n“\(x.str("reason"))”")).font(.bSmall).foregroundColor(C.muted)
                                }
                                Spacer(minLength: 0)
                            }.padding(.vertical, 9)
                        }
                    }
                }.padding(.top, 18)
            } else if let err { Msg(err, "bad") } else { Text(t("Loading…", "Inapakia…")).foregroundColor(C.muted) }
        }
        .task(id: tick) { let x = await m.api.adminGet("review"); if x.json["items"] is [Any] { items = x.json.objs("items"); err = nil } else { err = reasonText(x.reason) } }
        .sheet(item: Binding(get: { picked.map { JBox(j: $0) } }, set: { picked = $0?.j })) { b in
            RrReviewSheet(x: b.j, openIt: { picked = nil; m.go(Place(tab: "office", project: b.j.str("id"))) }) { tick += 1; m.reload() }.environmentObject(m)
        }
    }
}

private func rrGist(_ x: JSON, _ miles: Bool) -> String {
    let d = x.obj("data") ?? [:]
    switch x.str("kind") {
    case "cost": return Money.kes(d.int("est")) + (d.str("parts").isEmpty ? "" : " · " + d.str("parts"))
    case "new": return [d.str("type"), d.str("village"), countryName(d.str("ck")) ?? ""].filter { !$0.isEmpty }.joined(separator: " · ")
    case "story": return d.str("quote").isEmpty ? "" : "“\(d.str("quote"))”"
    default: return d.num("dist").map { t("Moved ", "Imehamishwa ") + Geo.dist($0, miles: miles) } ?? d.str("to")
    }
}

/// One item waiting for review: what it is, and approve or turn it down (turning down asks first, with a reason).
private struct RrReviewSheet: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.dismiss) private var dismiss
    let x: JSON; let openIt: () -> Void; let decided: () -> Void
    @State private var reason = ""
    @State private var asking = false
    @State private var busy = false
    @State private var done = false
    @State private var msg: (String, String)?
    var body: some View {
        let kind = x.str("kind"), k = rrKind(kind), cost = kind == "cost"
        let d = x.obj("data") ?? [:]
        let own = cost && !Money.canDecide(x.str("by"), m.prefs.name)
        let limit = m.snap?.approveAbove ?? Money.APPROVE_DEFAULT
        let verb: (String, String) = kind == "cost" ? (t("Approve the cost", "Idhinisha gharama"), t("Decline", "Kataa"))
            : kind == "move" ? (t("Keep the new location", "Baki na mahali papya"), t("Put the old one back", "Rudisha mahali pa zamani"))
            : kind == "request" ? (t("Approve the change", "Idhinisha badiliko"), t("Keep the confirmed location", "Baki na mahali palipothibitishwa"))
            : kind == "story" ? (t("Approve for the website", "Idhinisha kwa tovuti"), t("Don’t use", "Usitumie")) : (t("Approve", "Idhinisha"), t("Reject", "Kataa"))
        let who = (cost ? t("Asked by", "Imeombwa na") : t("Sent by", "Imetumwa na"), x.str("by").isEmpty ? "—" : x.str("by"))
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                RrSheetHead(icon: k[3], tone: k[4], title: x.str("t").isEmpty ? x.str("id") : x.str("t"), sub: k[1]) { dismiss() }
                switch kind {
                case "cost":
                    HStack(alignment: .lastTextBaseline, spacing: 8) {
                        Text(Money.kes(d.int("est"))).font(.mont(24, .heavy)).foregroundColor(C.ink)
                        Text(t("estimate · above ", "makadirio · zaidi ya ") + Money.kes(limit)).font(.bSmall).foregroundColor(C.muted)
                    }.padding(.top, 16)
                    VStack(spacing: 0) {
                        RrFact(label: t("Parts", "Vipuri"), value: d.str("parts"), long: true)
                        RrFact(label: t("Their note", "Maelezo yao"), value: d.str("note").isEmpty ? "" : "“\(d.str("note"))”", long: true)
                        PersonFact(label: who.0, name: who.1, sub: Dates.dateTime(x.str("at")))
                    }.padding(.top, 6)
                    HStack(alignment: .top, spacing: 8) {
                        Icon("info", size: 18, color: C.muted)
                        Text(t("Asked before ordering parts. The person who asked can’t approve it.", "Imeombwa kabla ya kuagiza vipuri. Aliyeomba hawezi kuidhinisha.")).font(.bMedium).foregroundColor(C.ink)
                    }.padding(12).frame(maxWidth: .infinity, alignment: .leading).background(C.named("slate").bg).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).padding(.top, 14)
                case "story":
                    let co = C.named("coral")
                    HStack(spacing: 0) {
                        Rectangle().fill(co.fg).frame(width: 4)
                        VStack(alignment: .leading, spacing: 4) {
                            Text("“" + d.str("quote") + "”").font(.bLarge).foregroundColor(C.ink)
                            if !d.str("who").isEmpty { Text("— " + d.str("who")).font(.bSmall).foregroundColor(C.muted) }
                        }.padding(12)
                        Spacer(minLength: 0)
                    }.background(co.bg).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).padding(.top, 16)
                    VStack(spacing: 0) {
                        PersonFact(label: who.0, name: who.1, sub: Dates.dateTime(x.str("at")))
                        RrFact(label: t("Consent", "Ridhaa"), value: t("Given, to quote and photograph", "Imetolewa, kunukuu na kupiga picha"))
                    }.padding(.top, 6)
                case "new":
                    VStack(spacing: 0) {
                        RrFact(label: t("Type", "Aina"), value: d.str("type")); RrFact(label: t("Village", "Kijiji"), value: d.str("village")); RrFact(label: t("Country", "Nchi"), value: countryName(d.str("ck")) ?? "")
                        RrFact(label: t("Location", "Mahali"), value: d.str("gps")); RrFact(label: t("Their note", "Maelezo yao"), value: d.str("note").isEmpty ? "" : "“\(d.str("note"))”", long: true)
                        PersonFact(label: who.0, name: who.1, sub: Dates.dateTime(x.str("at")))
                    }.padding(.top, 10)
                default:
                    VStack(spacing: 0) {
                        RrFact(label: t("From", "Kutoka"), value: d.str("from").isEmpty ? t("none", "hakuna") : d.str("from")); RrFact(label: t("To", "Hadi"), value: d.str("to"))
                        RrFact(label: t("Distance", "Umbali"), value: d.num("dist").map { Geo.dist($0, miles: m.miles) } ?? ""); RrFact(label: t("How", "Jinsi"), value: d.str("how"), long: true)
                        PersonFact(label: who.0, name: who.1, sub: Dates.dateTime(x.str("at")))
                    }.padding(.top, 10)
                }
                if own { Msg(t("You asked for this, so another admin approves it.", "Wewe uliomba hili, kwa hiyo msimamizi mwingine ataidhinisha."), "warn") }
                if asking || cost {
                    Field(cost ? t("Note (optional)", "Maelezo (si lazima)") : t("Reason (optional)", "Sababu (si lazima)"), $reason,
                          placeholder: cost ? t("e.g. quote checked with the driller", "mf. bei imehakikishwa na mchimbaji") : t("e.g. wrong school", "mf. shule isiyo sahihi"))
                }
                Btn(verb.0, icon: "check", enabled: !own && !busy && !done, ok: true, wide: true) { decide("approve", cost) }.padding(.top, 18)
                RrLight(text: asking && !cost ? t("Confirm: ", "Thibitisha: ") + verb.1 : verb.1, enabled: !own && !busy && !done) { decide("reject", cost) }.padding(.top, 8)
                if !x.str("id").isEmpty { RrLight(text: t("Open project", "Fungua mradi"), icon: "chev") { dismiss(); openIt() }.padding(.top, 8) }
                if let msg { Msg(msg.1, msg.0) }
            }.padding(.horizontal, 20).padding(.top, 20).padding(.bottom, 28)
        }
        .background(C.card.ignoresSafeArea())
    }
    private func decide(_ dec: String, _ cost: Bool) {
        if dec == "reject" && !asking && !cost { withAnimation { asking = true }; return }
        busy = true; msg = ("", t("Saving…", "Inahifadhi…"))
        Task {
            let r = await m.api.admin(["action": "review", "rid": x.str("rid"), "decision": dec, "reason": reason])
            busy = false
            if r.ok {
                done = true; Haptic.ok(); msg = ("ok", dec == "approve" ? t("Approved ✓", "Imeidhinishwa ✓") : t("Turned down ✓", "Imekataliwa ✓"))
                try? await Task.sleep(nanoseconds: 700_000_000); decided(); dismiss()
            } else { msg = ("bad", r.reason == "own" ? t("You asked for this, so another admin has to approve it.", "Wewe uliomba hili, kwa hiyo msimamizi mwingine lazima aidhinishe.") : reasonText(r.reason)) }
        }
    }
}

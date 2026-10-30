import MapKit
import SwiftUI
import UIKit

// A project's extras (Android ProjectExtras.kt, Tracker.kt, Construction.kt, Costs.kt, ProjectScreen.kt): "Water test
// due", the repair tracker, the Construction card, the cost line, the photo timeline, the one-page summary on the
// letterhead, Directions and "More for this project".

/// "Water test due" in teal, with a flask.
struct WaterTestTag: View {
    var body: some View {
        let (bg, fg): (Color, Color) = oSun ? (Color(hex: 0xD2F2F6), Color(hex: 0x003F47)) : C.dark ? (Color(hex: 0x0E3237), Color(hex: 0x7FDCE6)) : (Color(hex: 0xE0F5F7), Color(hex: 0x005F6B))
        HStack(spacing: 4) { OIcon("flask", size: 13, color: fg); Text(t("Water test due", "Kipimo cha maji kinahitajika")).font(.mont(12, .bold)).foregroundColor(fg).lineLimit(1) }
            .padding(.horizontal, 9).padding(.vertical, 4).background(bg).clipShape(Capsule()).overlay(Capsule().stroke(oSun ? fg : .clear, lineWidth: 2))
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
                let sum = j.int("sum"), n = j.int("n"); let last = j.objs("last").first
                let am = ohue("amber")
                HStack(spacing: 12) {
                    OTileIcon(icon: "coins", tint: am.fg, bg: am.bg, size: 40)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(n > 0 ? t("Repairs: \(Money.kes(sum)) in the last 12 months (\(n) \(n == 1 ? "repair" : "repairs"))", "Matengenezo: \(Money.kes(sum)) katika miezi 12 iliyopita (matengenezo \(n))")
                             : t("Repairs: nothing spent in the last 12 months", "Matengenezo: hakuna kilichotumika katika miezi 12 iliyopita")).font(.tSmall).foregroundColor(C.ink).fixedSize(horizontal: false, vertical: true)
                        if let l = last {
                            Text(t("Latest: ", "Mpya zaidi: ") + [Money.kes(l.int("actual")), l.str("what"), l.str("by"), When.text(l.str("at"))].filter { !$0.isEmpty }.joined(separator: " · ")).font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true)
                        }
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(12).background(C.card).clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(C.line, lineWidth: oEdge)).padding(.top, 12)
            }
        }
        .task(id: p.id) { let x = await m.api.last(p.id); if x.ok { c = x.json.obj("costs") } }
    }
}

// --- the repair tracker ------------------------------------------------------------------------------------------

/// A still map of one point (for the repair tracker).
private struct StillMap: UIViewRepresentable {
    let ll: LatLng; let color: UIColor
    func makeUIView(context: Context) -> MKMapView {
        let mv = MKMapView(); mv.isUserInteractionEnabled = false; mv.pointOfInterestFilter = .excludingAll
        mv.setRegion(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: ll.lat, longitude: ll.lng), latitudinalMeters: 1600, longitudinalMeters: 1600), animated: false)
        let a = MKPointAnnotation(); a.coordinate = CLLocationCoordinate2D(latitude: ll.lat, longitude: ll.lng); mv.addAnnotation(a)
        mv.delegate = context.coordinator
        return mv
    }
    func updateUIView(_ mv: MKMapView, context: Context) {}
    func makeCoordinator() -> Coord { Coord(color) }
    final class Coord: NSObject, MKMapViewDelegate {
        let color: UIColor
        init(_ c: UIColor) { color = c }
        func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
            let v = MKAnnotationView(annotation: annotation, reuseIdentifier: nil)
            v.image = UIGraphicsImageRenderer(size: CGSize(width: 26, height: 26)).image { c in
                c.cgContext.setFillColor(UIColor.white.cgColor); c.cgContext.fillEllipse(in: CGRect(x: 0, y: 0, width: 26, height: 26))
                c.cgContext.setFillColor(color.cgColor); c.cgContext.fillEllipse(in: CGRect(x: 4, y: 4, width: 18, height: 18))
            }
            return v
        }
    }
}

/// An open repair: where it is, who has it, how far the job has got, "Parts ordered" (and the second approval), the cost and Mark fixed.
struct RepairTracker: View {
    @EnvironmentObject var m: AppModel
    let p: Project; let r: Repair; let here: LatLng?
    @State private var msg: String?
    @State private var busy = false
    @State private var fixed = false
    @State private var pulse = false
    @State private var partsOpen = false
    @State private var cost = ""
    @State private var what = ""
    @State private var shots: [Shot] = []
    @State private var pmsg: (String, String)?
    @State private var showFix = false
    @State private var fixCost = ""
    @State private var fixShots: [Shot] = []

    var body: some View {
        let bad = r.state == "notworking"
        let order = ["reported", "assigned", "parts", "fixed"]
        var si = fixed ? 3 : max(0, order.firstIndex(of: r.step) ?? 0)
        if !fixed && !r.assignee.isEmpty && si < 1 { si = 1 }
        let a = m.sx.approvals[p.id]; let stepAt = m.sx.stepAt[p.id] ?? [:]
        let steps: [(String, String)] = [
            (t("Reported", "Imeripotiwa"), (bad ? t("Not working", "Haifanyi kazi") : t("Needs repair", "Inahitaji matengenezo")) + "\u{00A0}· " + r.by + ", " + When.text(r.at)),
            (t("Assigned", "Imegawiwa"), r.assignee.isEmpty ? t("Waiting for the office", "Inasubiri ofisi") : r.assignee + (stepAt["assigned"].map { ", " + When.text($0) } ?? "")),
            (t("Parts ordered", "Vipuri vimeagizwa"), stepAt["parts"].map { When.text($0) } ?? (a?.status == "pending" ? t("Waiting for a second approval · asked by ", "Inasubiri idhini ya pili · imeombwa na ") + (a?.by ?? "") : r.parts.isEmpty ? t("When parts are needed", "Vipuri vikihitajika") : t("Needs: ", "Inahitaji: ") + r.parts)),
            (t("Fixed", "Imerekebishwa"), fixed ? m.prefs.name : isWater(p) ? t("Mark it fixed when the water is running again", "Weka imerekebishwa maji yakitoka tena") : t("Mark it fixed when it’s working again", "Weka imerekebishwa ikifanya kazi tena")),
        ]
        return CardV {
            HStack {
                Text(t("Repair job", "Kazi ya matengenezo")).font(.mont(17, .heavy)).foregroundColor(C.ink).frame(maxWidth: .infinity, alignment: .leading)
                if fixed { Tag(t("Fixed ✓", "Imerekebishwa ✓"), bg: C.okBg, fg: C.ok) }
                else { Tag(r.urgency == "month" ? t("Fix this month", "Rekebisha mwezi huu") : (r.urgency == "urgent" ? urgencyName(r.urgency) + " · " : "") + (bad ? t("Not working", "Haifanyi kazi") : t("Needs repair", "Inahitaji matengenezo")), bg: C.badBg, fg: C.bad) }
            }
            if let g = p.gps, !fixed { trackerMap(g, bad) }
            VStack(alignment: .leading, spacing: 0) {
                ForEach(steps.indices, id: \.self) { i in
                    let done = i < si || fixed, now = i == si && !fixed
                    HStack(alignment: .top, spacing: 12) {
                        ZStack(alignment: .top) {
                            if i < steps.count - 1 { Rectangle().fill(done ? C.ok : C.line).frame(width: 2).padding(.top, 28) }
                            ZStack {
                                if now { Circle().fill(Color(hex: 0x1C7C4A).opacity(pulse ? 0 : 0.45)).frame(width: pulse ? 48 : 28, height: pulse ? 48 : 28) }
                                Circle().fill(done || now ? C.ok : C.line).frame(width: 28, height: 28)
                                if done || now { Icon("check", size: 15, color: .white) }
                            }.frame(width: 28, height: 28)
                        }.frame(width: 28)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(steps[i].0).font(.tSmall).foregroundColor(done || now ? C.ink : C.muted)
                            Text(steps[i].1).font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true)
                        }.padding(.bottom, 14).frame(maxWidth: .infinity, alignment: .leading)
                    }.rise(i + 1, step: 0.11)
                }
            }.padding(.top, 14)
            if !fixed {
                if !r.note.isEmpty || !r.problems.isEmpty || r.cost != nil {
                    VStack(alignment: .leading, spacing: 2) {
                        if !r.note.isEmpty { Text("“\(r.note)”").font(.bMedium).italic().foregroundColor(C.text) }
                        if !r.problems.isEmpty { Text(t("Problems: ", "Matatizo: ") + r.problems.joined(separator: ", ")).font(.bSmall).foregroundColor(C.muted) }
                        if let c = r.cost { Text(t("About ", "Takriban ") + Money.kes(c)).font(.bSmall).foregroundColor(C.muted) }
                    }.padding(.horizontal, 14).padding(.vertical, 12).frame(maxWidth: .infinity, alignment: .leading)
                        .background(C.dark ? Color.white.opacity(0.08) : Color(hex: 0xF2F6F9)).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                money(a)
                Btn(t("Mark fixed", "Weka imerekebishwa"), icon: "wrench", enabled: !busy, ok: true, wide: true, busy: busy ? t("Saving…", "Inahifadhi…") : nil) { markFixed() }.padding(.top, 12)
            }
            Msg(msg, "warn")
        }
        .padding(.top, 12)
        .onAppear { if Motion.on { withAnimation(.easeOut(duration: 1.8).repeatForever(autoreverses: false)) { pulse = true } } }
    }

    func trackerMap(_ g: LatLng, _ bad: Bool) -> some View {
        let d = here.map { Geo.metres($0, g) }
        return ZStack(alignment: .topLeading) {
            StillMap(ll: g, color: UIColor(Color(hex: bad ? 0xB3261E : 0xD98200)))
            if let d {
                HStack(spacing: 6) { Circle().fill(Color(hex: 0x1C9C5A)).frame(width: 7, height: 7); Text(Geo.dist(d, miles: m.miles) + " · " + t("Live", "Moja kwa moja")).font(.mont(12, .heavy)).foregroundColor(Color(hex: 0x04263F)) }
                    .padding(.horizontal, 10).padding(.vertical, 5).background(Color.white.opacity(0.85)).clipShape(Capsule()).padding(10)
            }
            VStack { Spacer()
                HStack(spacing: 10) {
                    Text(r.assignee.isEmpty ? "?" : r.assignee.split(separator: " ").prefix(2).compactMap { $0.first.map { String($0).uppercased() } }.joined())
                        .font(.mont(12, .heavy)).foregroundColor(.white).frame(width: 34, height: 34).background(LinearGradient(colors: [Color(hex: 0x47B4F5), Color(hex: 0x0A78C2)], startPoint: .topLeading, endPoint: .bottomTrailing)).clipShape(Circle())
                    VStack(alignment: .leading, spacing: 1) {
                        Text(r.assignee.isEmpty ? t("Not assigned yet", "Bado hajapewa mtu") : r.assignee).font(.mont(14, .bold)).foregroundColor(Color(hex: 0x04263F))
                        Text(!r.due.isEmpty ? t("Due ", "Mwisho ") + ODates.day(r.due) : !r.assignee.isEmpty ? t("Repair job", "Kazi ya matengenezo") : t("The office assigns it", "Ofisi itamgawia mtu")).font(.mont(12)).foregroundColor(Color(hex: 0x5A6066))
                    }
                    Spacer(minLength: 0)
                }.padding(8).background(Color.white.opacity(0.82)).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).padding(8)
            }
        }
        .frame(height: 150).clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous)).padding(.top, 10)
    }

    /// The second approval, "Parts ordered" for the person on the job (or an admin), and the cost when fixed.
    @ViewBuilder func money(_ a: Approval?) -> some View {
        let s = m.snap
        let st = Money.state(r, a, m.sx.approveAbove)
        ApprovalNote(r: r, a: a, limit: m.sx.approveAbove).padding(.top, 10)
        let mine = s?.admin == true || (s?.jobs.contains(p.id) ?? false) || r.assignee.trimmingCharacters(in: .whitespaces).lowercased() == m.prefs.name.trimmingCharacters(in: .whitespaces).lowercased()
        if mine && !Money.past(r.step, "parts") && st != "pending" {
            let ask = st == "needed" || st == "declined"
            if partsOpen && !ask {
                Field(t("Actual cost (optional)", "Gharama halisi (si lazima)"), Binding(get: { cost }, set: { cost = $0.filter(\.isNumber) }), placeholder: t("what the parts cost", "bei ya vipuri"), number: true)
                Field(t("What was ordered? (optional)", "Nini kimeagizwa? (si lazima)"), $what, placeholder: r.parts.isEmpty ? t("e.g. 6 rising main pipes", "mf. mabomba 6") : r.parts)
                ReceiptPick(shots: $shots).padding(.top, 8)
            }
            Btn(ask ? t("Parts ordered: ask for approval", "Vipuri vimeagizwa: omba idhini") : partsOpen ? t("Save: parts ordered", "Hifadhi: vipuri vimeagizwa") : t("Parts ordered", "Vipuri vimeagizwa"), icon: "box", enabled: !busy, light: true, wide: true) {
                if !ask && !partsOpen { withAnimation { partsOpen = true }; return }
                parts(ask)
            }.padding(.top, 10)
        }
        if case let (tone, text)? = pmsg { Msg(text, tone) }
        if !showFix {
            Button { withAnimation { showFix = true } } label: {
                HStack(spacing: 8) { OIcon("coins", size: 18, color: C.blue2); Text(t("Add what the repair cost (and a receipt)", "Ongeza gharama ya matengenezo (na risiti)")).font(.tSmall).foregroundColor(C.blue2).multilineTextAlignment(.leading) }
                    .padding(.vertical, 8).padding(.horizontal, 2).frame(minHeight: 44)
            }.buttonStyle(.plain).padding(.top, 6)
        } else {
            Field(t("Actual cost when fixed (optional)", "Gharama halisi ikirekebishwa (si lazima)"), Binding(get: { fixCost }, set: { fixCost = $0.filter(\.isNumber) }), placeholder: t("what the repair cost", "gharama ya matengenezo"), number: true)
            ReceiptPick(shots: $fixShots).padding(.top, 8)
        }
    }

    func parts(_ ask: Bool) {
        busy = true
        Task {
            var b: JSON = ["action": "parts", "id": p.id]
            if !ask { if let n = Money.parse(cost) { b["actual"] = n }; if !what.trimmingCharacters(in: .whitespaces).isEmpty { b["what"] = what.trimmingCharacters(in: .whitespaces) }; if !shots.isEmpty { b["receipt"] = true } }
            switch await m.submit(b, ask ? [] : shots.map { OutFile(path: $0.path, kind: "photo", label: "receipt") }) {
            case .sent(let j, _):
                let held = !j.str("held").isEmpty
                pmsg = held ? ("warn", j.bool("asked") ? t("Sent for a second approval ✓ Parts can be ordered once another admin approves the cost.", "Imetumwa kwa idhini ya pili ✓ Vipuri vitaagizwa msimamizi mwingine akiidhinisha.") : t("Still waiting for a second approval.", "Bado inasubiri idhini ya pili."))
                    : ("ok", t("Parts ordered ✓", "Vipuri vimeagizwa ✓"))
                partsOpen = false; cost = ""; what = ""; shots = []; m.reload()
            case .queued: pmsg = ("warn", t("Saved on this phone. It will be sent when you have signal.", "Imehifadhiwa kwenye simu. Itatumwa ukipata mtandao."))
            case .failed(let s): pmsg = ("bad", s)
            case .locked: break
            }
            busy = false
        }
    }

    func markFixed() {
        busy = true
        Task {
            var b: JSON = ["action": "fixed", "id": p.id, "note": ""]
            if let n = Money.parse(fixCost) { b["actual"] = n }
            if !fixShots.isEmpty { b["receipt"] = true }
            let o = await m.submit(b, fixShots.map { OutFile(path: $0.path, kind: "photo", label: "receipt") })
            switch o {
            case .sent, .queued:
                var queued = false
                if case .queued = o { queued = true }
                withAnimation { fixed = true }; Haptic.ok()
                msg = queued ? t("Saved on this phone. It will be sent when you have signal.", "Imehifadhiwa kwenye simu. Itatumwa ukipata mtandao.") : nil
                try? await Task.sleep(nanoseconds: 4_000_000_000); m.localFixed(p)
            case .failed(let s): msg = s
            case .locked: break
            }
            busy = false
        }
    }
}

// --- construction ----------------------------------------------------------------------------------------------

/// The build stages, in order, with their words.
enum Stages {
    static let keys = ["planned", "foundation", "walls", "roof", "finishing", "handed"]
    static func en(_ k: String) -> String { ["planned": "Planned", "foundation": "Foundation", "walls": "Walls", "roof": "Roof", "finishing": "Finishing", "handed": "Handed over"][k] ?? k }
    static func label(_ k: String) -> String {
        switch k {
        case "planned": return t("Planned", "Imepangwa"); case "foundation": return t("Foundation", "Msingi"); case "walls": return t("Walls", "Kuta"); case "roof": return t("Roof", "Paa")
        case "finishing": return t("Finishing", "Umaliziaji"); case "handed": return t("Handed over", "Imekabidhiwa"); default: return k
        }
    }
    static func next(_ k: String?) -> String? { guard let k, !k.isEmpty, let i = keys.firstIndex(of: k) else { return keys.first }; return i + 1 < keys.count ? keys[i + 1] : nil }
    static func index(_ k: String?) -> Int { keys.firstIndex(of: k ?? "") ?? -1 }
}
struct StageStep { let stage: String; let at: String; let by: String; let note: String; let photos: [String] }
struct StageRecord {
    let id: String; let stage: String; let at: String; let by: String; let pct: Int?; let due: String; let parent: String; let ptitle: String; let hist: [StageStep]; let behind: Bool
    func photos(_ s: String) -> [String] { hist.filter { $0.stage == s }.flatMap(\.photos) }
    func reached(_ s: String) -> StageStep? { hist.first { $0.stage == s } }
    static func from(_ o: JSON?) -> StageRecord? {
        guard let o, !o.str("stage").isEmpty, o.str("stage") != "null" else { return nil }
        func s(_ k: String) -> String { let v = o.str(k); return v == "null" ? "" : v }
        return StageRecord(id: s("id"), stage: s("stage"), at: s("at"), by: s("by"), pct: o["pct"] == nil || o["pct"] is NSNull ? nil : o.int("pct"), due: s("due"), parent: s("parent"), ptitle: s("ptitle"),
                           hist: o.objs("hist").map { h in StageStep(stage: h.str("stage"), at: h.str("at"), by: h.str("by"), note: h.str("note"), photos: (h.arr("photos") ?? []).compactMap { $0 as? String }.filter { !$0.isEmpty }) },
                           behind: o.bool("behind"))
    }
}
struct LinkedProject { let id: String; let t: String; let s: String }
func stagePhotoPath(_ project: String, _ asset: String, _ w: Int = 480) -> String { "/api/pin-photo?stage=\(Api.enc(project))&asset=\(Api.enc(asset))&w=\(w)" }
private func stageWhy(_ reason: String?) -> String {
    switch reason {
    case "admin-only": return t("Only an admin can mark it handed over.", "Msimamizi pekee anaweza kuweka imekabidhiwa.")
    case "not-next": return t("Move one stage at a time. Ask an admin to skip or go back.", "Songa hatua moja kwa wakati. Mwombe msimamizi kuruka au kurudi nyuma.")
    case "stage": return t("That stage isn’t known.", "Hatua hiyo haijulikani.")
    default: return reasonText(reason)
    }
}

/// % done and the expected finish, with "Behind schedule" when it's past.
struct BuildProgress: View {
    let pct: Int?; let due: String; let behind: Bool
    var body: some View {
        if pct != nil || !due.isEmpty || behind {
            VStack(alignment: .leading, spacing: 6) {
                if let pct {
                    HStack(spacing: 10) {
                        OBar(f: Double(min(100, max(0, pct))) / 100, color: ohue("amber").fg, height: 10)
                        Text(t("\(pct)% done", "\(pct)% imekamilika")).font(.lLarge).foregroundColor(C.ink).lineLimit(1).fixedSize()
                    }
                }
                HStack(spacing: 8) {
                    if !due.isEmpty { Text(t("Expected: ", "Inatarajiwa: ") + ODates.day(due)).font(.mont(12, .semibold)).foregroundColor(C.muted) }
                    if behind { Tag(t("Behind schedule", "Imechelewa"), bg: C.badBg, fg: C.bad) }
                }
            }.padding(.top, 10)
        }
    }
}

/// The stage list: done stages ticked with when (and who); the current one lit; then what's to come.
struct StageStepper<G: View>: View {
    let current: String?; let reached: (String) -> (String, String)?; let photos: (String) -> Int
    @ViewBuilder let gallery: (String) -> G
    var body: some View {
        let ci = Stages.index(current)
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(Stages.keys.enumerated()), id: \.offset) { i, k in
                let done = i < ci, now = i == ci
                let hu = ohue(k == "handed" ? "green" : "amber")
                HStack(alignment: .top, spacing: 12) {
                    VStack(spacing: 0) {
                        ZStack {
                            Circle().fill(done ? C.ok : now ? hu.bg : C.tint).frame(width: 26, height: 26)
                            if now { Circle().stroke(hu.fg, lineWidth: 2.5).frame(width: 26, height: 26) }
                            if done { Icon("check", size: 15, color: .white) } else { Text("\(i + 1)").font(.mont(12, .heavy)).foregroundColor(now ? hu.fg : C.muted) }
                        }
                        if i < Stages.keys.count - 1 { Rectangle().fill(done ? C.ok : C.line).frame(width: 2).frame(minHeight: 22, maxHeight: .infinity) }
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(Stages.label(k)).font(.mont(14, now ? .heavy : .semibold)).foregroundColor(done || now ? C.ink : C.muted)
                        if done || now, let r = reached(k) { Text([ODates.day(r.0), r.1].filter { !$0.isEmpty }.joined(separator: " · ")).font(.bSmall).foregroundColor(C.muted) }
                        if (done || now) && photos(k) > 0 { gallery(k) }
                    }.padding(.top, 2).padding(.bottom, 10).frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }.padding(.top, 12)
    }
}

/// The Construction card on a project page: for projects being built (or that have stage data).
struct ConstructionCard: View {
    @EnvironmentObject var m: AppModel
    let p: Project
    @State private var rec: StageRecord?
    @State private var parent: LinkedProject?
    @State private var children: [LinkedProject] = []
    @State private var sheet: OItem?
    @State private var meta = false
    @State private var said: (String, String)?
    @State private var view: OItem?
    @State private var tick = 0

    func load() async {
        let r = await m.api.last(p.id)
        guard r.ok else { return }
        rec = StageRecord.from(r.json.obj("stage"))
        let l = r.json.obj("linked")
        parent = l?.obj("parent").flatMap { $0.str("id").isEmpty ? nil : LinkedProject(id: $0.str("id"), t: $0.str("t"), s: $0.str("s")) }
        children = (l?.objs("children") ?? []).map { LinkedProject(id: $0.str("id"), t: $0.str("t"), s: $0.str("s")) }
    }
    func go(_ id: String) { m.go(Place(tab: m.here.tab, project: id)) }

    var body: some View {
        let brief = m.sx.stages[p.id]
        let tracked = brief != nil || m.building(p)
        Group {
            if !tracked && rec == nil {
                if !children.isEmpty {
                    CardV {
                        HStack(spacing: 10) { let hu = ohue("amber"); OTileIcon(icon: "build", tint: hu.fg, bg: hu.bg, size: 36); Text(t("Being built here", "Kinachojengwa hapa")).font(.mont(17, .heavy)).foregroundColor(C.ink) }
                        ForEach(children, id: \.id) { c in linkRow(t("Also here: ", "Pia hapa: ") + c.t + (c.s.isEmpty ? "" : " · " + Stages.label(c.s))) { go(c.id) } }
                    }.padding(.top, 14)
                }
            } else { card(brief) }
        }
        .task(id: "\(p.id)|\(tick)") { await load() }
        .sheet(item: $sheet) { x in StageSheet(p: p, cur: rec?.stage ?? brief?.s, start: x.id, admin: m.snap?.admin == true, close: { sheet = nil }) { r, msg in if let r { rec = r }; said = msg; tick += 1; m.refresh() } }
        .sheet(isPresented: $meta) { MetaSheet(p: p, pct0: rec?.pct ?? brief?.pct, due0: rec?.due ?? brief?.due ?? "", close: { meta = false }) { r in if let r { rec = r }; said = ("ok", t("Saved ✓", "Imehifadhiwa ✓")); m.refresh() } }
        .fullScreenCover(item: $view) { x in stageViewer(x.id) }
    }

    @ViewBuilder func card(_ brief: StageBrief?) -> some View {
        let cur: String? = rec?.stage ?? ((brief?.s.isEmpty ?? true) ? nil : brief?.s)
        let admin = m.snap?.admin == true
        let next = Stages.next(cur)
        let hu = ohue("amber")
        CardV {
            HStack(spacing: 12) {
                OTileIcon(icon: "build", tint: hu.fg, bg: hu.bg, size: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Text(t("Construction", "Ujenzi")).font(.mont(17, .heavy)).foregroundColor(C.ink)
                    Text(cur.map { Stages.label($0) + ($0 != "handed" ? t(" · stage \(Stages.index($0) + 1) of 6", " · hatua \(Stages.index($0) + 1) kati ya 6") : "") } ?? t("Not started in the app yet", "Bado haijaanzishwa kwenye programu")).font(.bMedium).foregroundColor(C.muted)
                }
            }
            BuildProgress(pct: rec?.pct ?? brief?.pct, due: rec?.due ?? brief?.due ?? "", behind: rec?.behind ?? brief?.behind ?? false)
            StageStepper(current: cur, reached: { k in rec?.reached(k).map { ($0.at, $0.by) } ?? (k == cur && brief != nil ? (brief?.at ?? "", brief?.by ?? "") : nil) }, photos: { rec?.photos($0).count ?? 0 }) { k in
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(Array((rec?.photos(k) ?? []).enumerated()), id: \.offset) { i, a in
                            Button { view = OItem(id: "\(k)|\(i)", o: [:]) } label: { AuthPic(path: stagePhotoPath(p.id, a)).frame(width: 64, height: 64).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous)) }
                                .buttonStyle(.plain).accessibilityLabel(Stages.label(k) + " · " + t("photo \(i + 1)", "picha \(i + 1)"))
                        }
                    }
                }.padding(.top, 6)
            }
            if let pp = parent { linkRow(t("Part of: ", "Sehemu ya: ") + pp.t) { go(pp.id) } }
            else if let r = rec, !r.parent.isEmpty { linkRow(t("Part of: ", "Sehemu ya: ") + (r.ptitle.isEmpty ? r.parent : r.ptitle)) { go(r.parent) } }
            ForEach(children, id: \.id) { c in linkRow(t("Also here: ", "Pia hapa: ") + c.t + (c.s.isEmpty ? "" : " · " + Stages.label(c.s))) { go(c.id) } }
            if case let (tone, text)? = said { Msg(text, tone) }
            if let next, next != "handed" || admin {
                Btn(next == "handed" ? t("Hand over", "Kabidhi") : t("Move to next stage: ", "Hatua inayofuata: ") + Stages.label(next), icon: next == "handed" ? "check" : "arrow", wide: true) { sheet = OItem(id: next, o: [:]) }.padding(.top, 14)
            }
            if let c = cur, c != "handed" { Btn(t("Add progress photos", "Ongeza picha za maendeleo"), icon: "camera", light: true, small: true, wide: true) { sheet = OItem(id: c, o: [:]) }.padding(.top, 8) }
            if cur != "handed" { Btn(t("% done and expected finish", "% iliyokamilika na tarehe inayotarajiwa"), icon: "cal", light: true, small: true, wide: true) { meta = true }.padding(.top, 8) }
        }.padding(.top, 14)
    }

    func linkRow(_ text: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 8) { Icon("link", size: 18, color: C.blue2); Text(text).font(.tSmall).foregroundColor(C.blue2).multilineTextAlignment(.leading).frame(maxWidth: .infinity, alignment: .leading); Icon("chev", size: 16, color: C.muted) }
                .padding(.horizontal, 12).padding(.vertical, 8).frame(minHeight: 48).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }.buttonStyle(.plain).padding(.top, 10)
    }

    @ViewBuilder func stageViewer(_ key: String) -> some View {
        let parts = key.split(separator: "|").map(String.init)
        let k = parts.first ?? ""; let start = Int(parts.last ?? "0") ?? 0
        let ph = rec?.photos(k) ?? []
        StageViewer(paths: ph.map { stagePhotoPath(p.id, $0, 1600) }, start: start, caption: Stages.label(k)) { view = nil }
    }
}

/// Big photos from the server (with the sign-in), swiping between them; ✕ closes.
struct StageViewer: View {
    let paths: [String]; let start: Int; let caption: String; let close: () -> Void
    @State private var i = 0
    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()
            TabView(selection: $i) {
                ForEach(paths.indices, id: \.self) { j in AuthPic(path: paths[j], fit: true).tag(j) }
            }.tabViewStyle(.page(indexDisplayMode: .never))
            Button(action: close) { Icon("x", size: 20, color: .white).frame(width: 44, height: 44).background(Color.black.opacity(0.4)).clipShape(Circle()) }.padding(16).accessibilityLabel(t("Close", "Funga"))
            VStack { Spacer(); Text(caption + "  ·  \(i + 1)/\(paths.count)").font(.bMedium).foregroundColor(.white).padding(.horizontal, 12).padding(.vertical, 6).background(Color.black.opacity(0.6)).clipShape(RoundedRectangle(cornerRadius: 12)).padding(24) }.frame(maxWidth: .infinity)
        }
        .onAppear { i = min(max(0, start), max(0, paths.count - 1)) }
    }
}

/// Move to a stage (or add photos to the current one): at least one photo and a short note. Admins can pick any stage.
private struct StageSheet: View {
    @EnvironmentObject var m: AppModel
    let p: Project; let cur: String?; let start: String; let admin: Bool; let close: () -> Void; let done: (StageRecord?, (String, String)) -> Void
    @State private var stage = ""
    @State private var shots: [Shot] = []
    @State private var note = ""
    @State private var busy = false
    @State private var msg: String?
    @State private var sure = false
    var body: some View {
        let choices = admin ? Stages.keys : [cur, Stages.next(cur)].compactMap { $0 }.filter { $0 != "handed" }
        let hu = ohue("amber")
        let st = stage.isEmpty ? start : stage
        OSheet {
            ZStack(alignment: .topTrailing) {
                VStack(spacing: 4) {
                    OTileIcon(icon: "build", tint: hu.fg, bg: hu.bg, size: 56)
                    Text(st == cur ? t("Progress at ", "Maendeleo ya ") + Stages.label(st) : t("Move to ", "Hamia ") + Stages.label(st)).font(.tLarge).foregroundColor(C.ink).multilineTextAlignment(.center).padding(.top, 10)
                    Text(p.title).font(.bMedium).foregroundColor(C.muted).multilineTextAlignment(.center)
                }.frame(maxWidth: .infinity).padding(.horizontal, 44)
                OCloseX(action: close)
            }
            if choices.count > 1 {
                Text(t("Stage", "Hatua")).font(.lMedium).foregroundColor(C.ink).padding(.top, 14).padding(.bottom, 6)
                LazyVGrid(columns: [GridItem(.flexible(), spacing: 6), GridItem(.flexible(), spacing: 6)], spacing: 6) {
                    ForEach(choices, id: \.self) { k in Pill(Stages.label(k) + (k == cur ? t(" (now)", " (sasa)") : ""), on: st == k) { stage = k; sure = false } }
                }
            }
            Text(t("Photos (at least one)", "Picha (angalau moja)")).font(.lMedium).foregroundColor(C.ink).padding(.top, 14).padding(.bottom, 6)
            PhotoSlots(slots: [], shots: Binding(get: { shots }, set: { shots = $0; msg = nil }), max: 6)
            Field(t("Note", "Maelezo"), $note, placeholder: t("e.g. Walls up to the windows on 3 sides", "mf. Kuta zimefika madirishani pande 3"), lines: 2)
            if st == "handed" && sure {
                Text(t("Hand \(p.title) over? It shows as Completed / Operational in monday.com and on the website.", "Kabidhi \(p.title)? Itaonyesha Imekamilika / Inafanya kazi kwenye monday.com na tovuti.")).font(.bMedium).foregroundColor(C.ink)
                    .padding(12).frame(maxWidth: .infinity, alignment: .leading).background(C.okBg).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).padding(.top, 12)
            }
            Msg(msg, "bad")
            Btn(st == "handed" && !sure ? t("Hand over…", "Kabidhi…") : st == "handed" ? t("Yes, hand it over", "Ndiyo, kabidhi") : t("Save", "Hifadhi"), icon: "check", enabled: !busy, wide: true, busy: busy ? t("Saving…", "Inahifadhi…") : nil) { save(st) }.padding(.top, 16)
        }
    }
    func save(_ st: String) {
        if shots.isEmpty { msg = t("Add at least one photo of the work.", "Ongeza angalau picha moja ya kazi."); return }
        if st == "handed" && !sure { sure = true; return }
        busy = true; msg = nil
        Task {
            let r = await m.api.post(["action": "stage", "id": p.id, "stage": st, "note": note.trimmingCharacters(in: .whitespacesAndNewlines), "by": m.prefs.name])
            if !r.ok { busy = false; msg = stageWhy(r.reason); return }
            let upd = r.json.str("updateId"); var lost = 0
            if !upd.isEmpty {
                for s in shots {
                    let data = (try? Data(contentsOf: Outbox.resolve(s.path))) ?? Data()
                    let x = await m.api.multipart("/api/pin-photo", data: data, mime: "image/jpeg", filename: "construction.jpg", fields: [("update", upd), ("label", "Construction: " + Stages.en(st)), ("stage", st), ("project", p.id)])
                    if !x.ok { lost += 1 }
                }
            }
            busy = false; Haptic.ok()
            let saved = st == cur ? t("Photos added ✓", "Picha zimeongezwa ✓") : t("Now at \(Stages.label(st)) ✓", "Sasa iko \(Stages.label(st)) ✓")
            done(StageRecord.from(r.json.obj("stage")), lost > 0 ? ("warn", saved + " " + t("\(lost) photo(s) couldn’t be added.", "Picha \(lost) hazikuweza kuongezwa.")) : ("ok", saved))
            close()
        }
    }
}

/// % done (in steps of 5) and the expected finish date; either can be cleared.
private struct MetaSheet: View {
    @EnvironmentObject var m: AppModel
    let p: Project; let pct0: Int?; let due0: String; let close: () -> Void; let done: (StageRecord?) -> Void
    @State private var pct: Double = 0
    @State private var pctSet = false
    @State private var due = ""
    @State private var busy = false
    @State private var msg: String?
    @State private var ready = false
    var body: some View {
        OSheet {
            HStack { Text(t("% done and expected finish", "% iliyokamilika na tarehe inayotarajiwa")).font(.tLarge).foregroundColor(C.ink).frame(maxWidth: .infinity, alignment: .leading); OCloseX(action: close) }
            Text(pctSet ? t("\(Int(pct))% done", "\(Int(pct))% imekamilika") : t("% done: not set", "% iliyokamilika: haijawekwa")).font(.tMedium).foregroundColor(C.ink).padding(.top, 14)
            Slider(value: Binding(get: { pct }, set: { pct = ($0 / 5).rounded() * 5; pctSet = true }), in: 0...100, step: 5).tint(ohue("amber").fg).accessibilityLabel(t("% done", "% iliyokamilika"))
            DateField(label: t("Expected finish", "Tarehe inayotarajiwa kukamilika"), value: due) { due = $0 }
            Msg(msg, "bad")
            Btn(t("Save", "Hifadhi"), icon: "check", enabled: !busy, wide: true, busy: busy ? t("Saving…", "Inahifadhi…") : nil) {
                busy = true
                Task {
                    var b: JSON = ["action": "stage-meta", "id": p.id]
                    b["pct"] = pctSet ? Int(pct) as Any : NSNull() as Any
                    b["due"] = due.isEmpty ? NSNull() as Any : due as Any
                    let r = await m.api.post(b)
                    busy = false
                    if r.ok { done(StageRecord.from(r.json.obj("stage"))); close() } else { msg = stageWhy(r.reason) }
                }
            }.padding(.top, 16)
        }
        .onAppear { if !ready { ready = true; if let v = pct0 { pct = Double(v); pctSet = true }; due = due0 } }
    }
}

// --- the photo timeline ----------------------------------------------------------------------------------------

struct TlItem { let url: String; let at: String; let by: String; var main = false }
struct TlGroup { let key: String; let slot: String; let en: String; let sw: String; let items: [TlItem]; var title: String { t(en, sw) } }
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
    let x = s.lowercased().replacingOccurrences(of: "[^a-z0-9]+", with: "-", options: .regularExpression).trimmingCharacters(in: CharacterSet(charactersIn: "-"))
    return String(x.prefix(40))
}
private let BY_SLUG: [String: (String, String, String)] = { var d: [String: (String, String, String)] = [:]; for s in SLOT_DEFS where d[slotSlug(s.1)] == nil { d[slotSlug(s.1)] = s }; return d }()
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
/// Which group a file belongs to: its slot, or "Other photos". Files are named "site-photo-<slot>-YYYY-MM-DD-HH-MM.jpg".
func slotOf(_ name: String) -> (String, String, String, String) {
    var slug = ""
    if let re = try? NSRegularExpression(pattern: #"^site-photo-(?:(.*?)-)?(\d{4}-\d{2}-\d{2}-\d{2}-\d{2})\.[a-z0-9]+$"#, options: .caseInsensitive),
       let mm = re.firstMatch(in: name, range: NSRange(name.startIndex..., in: name)), let r = Range(mm.range(at: 1), in: name) { slug = String(name[r]) }
    if slug.isEmpty || slug == "other-photo" { return ("other", "other", "Other photos", "Picha nyingine") }
    if let d = BY_SLUG[slug] { return (slug, d.0, d.1, d.2) }
    let h = slug.replacingOccurrences(of: "-", with: " ").capitalized(with: nil)
    return (slug, slug, h, h)
}
/// Who took it: the note says "Site visit by Grace (RAFIKI pin app)" or "… by Grace: …".
func photoBy(_ n: Note) -> String {
    let first = n.text.split(separator: "\n").first.map(String.init) ?? ""
    if let re = try? NSRegularExpression(pattern: #"\bby ([^.:(\n<]{2,60}?)\s*(?:\(RAFIKI pin app\)|:|\.)"#, options: .caseInsensitive),
       let mm = re.firstMatch(in: first, range: NSRange(first.startIndex..., in: first)), let r = Range(mm.range(at: 1), in: first) { return String(first[r]).trimmingCharacters(in: .whitespaces) }
    return n.by
}
/// The visit photos grouped by slot, newest first in each; the project's main photo on top; "Other photos" last.
func photoGroups(_ notes: [Note], _ p: Project, _ mainUrl: String?) -> [TlGroup] {
    var g: [String: [TlItem]] = [:]; var meta: [String: (String, String, String, String)] = [:]; var orderSeen: [String] = []
    for n in notes {
        for f in n.files where !f.url.isEmpty && ["jpg", "jpeg", "png", "webp", "gif"].contains(f.ext.lowercased()) {
            let s = slotOf(f.name); meta[s.0] = s
            if g[s.0] == nil { orderSeen.append(s.0) }
            g[s.0, default: []].append(TlItem(url: f.url, at: n.at, by: photoBy(n)))
        }
    }
    let order = slotOrder(p)
    let rank: (String) -> Int = { k in k == "other" ? 99 : (order.firstIndex(of: k) ?? 50) }
    let out = orderSeen.compactMap { k -> TlGroup? in
        guard let s = meta[k], let items = g[k] else { return nil }
        return TlGroup(key: k, slot: s.1, en: s.2, sw: s.3, items: items.sorted { $0.at > $1.at })
    }.sorted { a, b in rank(a.key) != rank(b.key) ? rank(a.key) < rank(b.key) : a.items.count > b.items.count }
    return (mainUrl.map { [TlGroup(key: "main", slot: "main", en: "Main photo", sw: "Picha kuu", items: [TlItem(url: $0, at: "", by: "", main: true)])] } ?? []) + out
}

/// The timeline at the top of History: one swipeable strip per photo slot, newest first; tap for full screen.
struct PhotoTimeline: View {
    @EnvironmentObject var m: AppModel
    let p: Project; let notes: [Note]
    @State private var open: OItem?
    var body: some View {
        let groups = photoGroups(notes, p, p.photo.map { m.api.photoUrl($0, w: 1280) })
        let real = groups.filter { $0.key != "main" }
        let n = real.reduce(0) { $0 + $1.items.count }
        let visits = Set(real.flatMap { $0.items.map { String($0.at.prefix(10)) } }).count
        let (bg, fg): (Color, Color) = oSun ? (.white, Color(hex: 0x3D1F8A)) : C.dark ? (Color(hex: 0x2A2147), Color(hex: 0xC3A9FF)) : (Color(hex: 0xEFE8FB), Color(hex: 0x7A4FD0))
        CardV(pad: 14) {
            HStack(spacing: 10) {
                OIcon("photos", size: 20, color: fg).frame(width: 36, height: 36).background(bg).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                Text(t("Photo timeline", "Picha kwa wakati")).font(.mont(17, .heavy)).foregroundColor(C.ink).frame(maxWidth: .infinity, alignment: .leading)
                if n > 0 { Text(t("\(n) \(n == 1 ? "photo" : "photos") · \(visits) \(visits == 1 ? "visit" : "visits")", "picha \(n) · ziara \(visits)")).font(.lSmall).foregroundColor(C.deep).padding(.horizontal, 10).padding(.vertical, 4).background(C.tint).clipShape(Capsule()).lineLimit(1) }
            }
            if n > 0 { Text(t("The same view at each visit, newest first. Swipe along a row to compare; tap a photo to open it.", "Mwonekano ule ule kila ziara, ya karibuni kwanza. Telezesha mstari kulinganisha; gusa picha kuifungua.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 8).fixedSize(horizontal: false, vertical: true) }
            ForEach(Array(groups.enumerated()), id: \.offset) { gi, g in
                HStack(alignment: .bottom) {
                    Text(g.title).font(.tSmall).foregroundColor(C.ink).frame(maxWidth: .infinity, alignment: .leading)
                    Text(g.key == "main" ? t("On the website", "Kwenye tovuti") : t("\(g.items.count) \(g.items.count == 1 ? "photo" : "photos")", "picha \(g.items.count)")).font(.lSmall).foregroundColor(C.muted)
                }.padding(.top, 14).padding(.bottom, 6)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(Array(g.items.enumerated()), id: \.offset) { ii, it in
                            Button { open = OItem(id: "\(gi)|\(ii)", o: [:]) } label: {
                                VStack(alignment: .leading, spacing: 0) {
                                    Color.clear.aspectRatio(4 / 3, contentMode: .fit).overlay(NetImage(url: it.url) { Icon("camera", size: 28, color: C.off) }).clipped().background(C.tint)
                                        .overlay(alignment: .topLeading) {
                                            if ii == 0 && !it.main && g.items.count > 1 { Text(t("Latest", "Mpya zaidi")).font(.mont(10, .bold)).foregroundColor(.white).padding(.horizontal, 8).padding(.vertical, 2).background(GreenC).clipShape(Capsule()).padding(8) }
                                        }
                                    VStack(alignment: .leading, spacing: 1) {
                                        Text(it.main ? t("Main photo", "Picha kuu") : ODates.day(it.at)).font(.lMedium).foregroundColor(C.ink).lineLimit(1)
                                        if !it.by.isEmpty { Text(it.by).font(.mont(11)).foregroundColor(C.muted).lineLimit(1) }
                                    }.padding(.horizontal, 10).padding(.vertical, 7).frame(maxWidth: .infinity, alignment: .leading).background(C.bg)
                                }
                                .frame(width: it.main ? 220 : 156).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                                .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.line, lineWidth: 1))
                            }.buttonStyle(.plain).accessibilityLabel(g.title + (it.at.isEmpty ? "" : ", " + ODates.day(it.at)) + (it.by.isEmpty ? "" : ", " + it.by))
                        }
                    }.padding(.trailing, 4)
                }
            }
            if n == 0 { Text(t("No visit photos yet. Photos from each site visit show here, grouped by view, so you can compare them over time.", "Bado hakuna picha za ziara. Picha za kila ziara zinaonekana hapa, kwa mwonekano, ili uzilinganishe kwa wakati.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 10).fixedSize(horizontal: false, vertical: true) }
        }
        .padding(.top, 10)
        .fullScreenCover(item: $open) { x in
            let parts = x.id.split(separator: "|").compactMap { Int($0) }
            if parts.count == 2, parts[0] < groups.count { TimelineViewer(g: groups[parts[0]], start: parts[1]) { open = nil } }
        }
    }
}

/// Full screen, one slot: swipe or tap the arrows; left is newer, right is older.
private struct TimelineViewer: View {
    let g: TlGroup; let start: Int; let close: () -> Void
    @State private var i = 0
    var body: some View {
        let it = g.items[min(max(0, i), g.items.count - 1)]
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(g.title).font(.tMedium).foregroundColor(.white)
                    Text(it.main ? t("Main photo, on the website", "Picha kuu, kwenye tovuti") : ODates.day(it.at) + (it.by.isEmpty ? "" : " · " + it.by)).font(.bSmall).foregroundColor(Color(hex: 0xCFE8FA))
                }.frame(maxWidth: .infinity, alignment: .leading)
                round("x", t("Close", "Funga"), true, close)
            }.padding(.leading, 18).padding(.trailing, 10).padding(.top, 14).padding(.bottom, 8)
            TabView(selection: $i) {
                ForEach(g.items.indices, id: \.self) { k in
                    ONetFit(url: g.items[k].url).padding(.horizontal, 8).tag(k)
                }
            }.tabViewStyle(.page(indexDisplayMode: .never))
            HStack(spacing: 22) {
                round("left", t("Newer photo", "Picha mpya zaidi"), i > 0) { withAnimation { i -= 1 } }
                Text("\(i + 1) / \(g.items.count)").font(.tSmall).foregroundColor(.white)
                round("chev", t("Older photo", "Picha ya zamani zaidi"), i < g.items.count - 1) { withAnimation { i += 1 } }
            }.padding(.top, 12).padding(.bottom, 22)
        }
        .background(Color(hex: 0x040C14, alpha: 0.95).ignoresSafeArea())
        .onAppear { i = min(max(0, start), g.items.count - 1) }
    }
    func round(_ icon: String, _ label: String, _ enabled: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) { OIcon(icon, size: 24, color: .white.opacity(enabled ? 1 : 0.3)).frame(width: 52, height: 52).background(Color.white.opacity(0.16)).clipShape(Circle()) }
            .buttonStyle(.plain).disabled(!enabled).accessibilityLabel(label)
    }
}

// --- the one-page summary for donors ------------------------------------------------------------------------------

private let NO_DONOR_SLOTS: Set<String> = ["damage", "strip", "issue", "work"]
private let PREF_SLOTS = ["site", "flow", "people", "outside", "progress", "farmer", "animals", "inside", "dorm", "equip", "desks", "close", "kitchen", "washrooms", "pen", "feed", "supplies", "materials"]

/// Gathers the notes, visits and photos, draws one A4 page on the letterhead and opens the share sheet.
@MainActor func shareProjectSummary(_ m: AppModel, _ p: Project) async -> String? {
    guard let s = m.snap else { return nil }
    let notes = await m.history(p.id).0 ?? []
    let last = await m.api.last(p.id)
    let visits = last.ok ? last.json.objs("visits") : []
    let groups = photoGroups(notes, p, p.photo.map { m.api.photoUrl($0, w: 1280) })
    var photos: [(String, String)] = []
    if let g = groups.first(where: { $0.key == "main" }) { photos.append((g.items[0].url, t("RAFIKI project photo", "Picha ya mradi wa RAFIKI"))) }
    for g in groups.filter({ $0.key != "main" && !NO_DONOR_SLOTS.contains($0.slot) && $0.key.range(of: "damage|broken|strip|wrong", options: .regularExpression) == nil })
        .sorted(by: { (a, b) in (a.key == "other" ? 98 : PREF_SLOTS.firstIndex(of: a.slot) ?? 60) < (b.key == "other" ? 98 : PREF_SLOTS.firstIndex(of: b.slot) ?? 60) }) where photos.count < 3 {
        photos.append((g.items[0].url, g.title + (g.items[0].at.isEmpty ? "" : " · " + ODates.day(g.items[0].at))))
    }
    var imgs: [(UIImage, String)] = []
    for (u, c) in photos { if let i = await Pics.shared.load(u) { imgs.append((i, c)) } }
    // people served: from the readings the server keeps
    var people: Int?; var pLabel = ""
    for (k, lbl) in [("users", isWater(p) ? t("people use this water (field estimate)", "watu wanatumia maji haya (makadirio ya uwanjani)") : t("people use it (field estimate)", "watu wanautumia (makadirio ya uwanjani)")),
                     ("pupils", t("pupils enrolled", "wanafunzi waliosajiliwa")), ("patients", t("patients a week", "wagonjwa kwa wiki")), ("children", t("children live there", "watoto wanaishi hapo"))] {
        if let v = visits.first(where: { $0.obj("readings")?.num(k) != nil || $0.obj("numbers")?.num(k) != nil }) {
            people = Int(v.obj("readings")?.num(k) ?? v.obj("numbers")?.num(k) ?? 0); pLabel = lbl; break
        }
    }
    var story: (String, String)?
    if let re = try? NSRegularExpression(pattern: #"Story moment: “([^”]+)”(?: \(([^)]+)\))?\. Consent to quote and photograph: given"#) {
        for n in notes { if let mm = re.firstMatch(in: n.text, range: NSRange(n.text.startIndex..., in: n.text)), let r1 = Range(mm.range(at: 1), in: n.text) {
            story = (String(n.text[r1]), Range(mm.range(at: 2), in: n.text).map { String(n.text[$0]) } ?? ""); break } }
    }
    let x = m.sx
    var facts: [(String, String)] = []
    if !p.type.isEmpty { facts.append((t("Type", "Aina"), p.type)) }
    let whereText = [p.area, p.country].filter { !$0.isEmpty }.joined(separator: ", ")
    if !whereText.isEmpty { facts.append((t("Location", "Mahali"), whereText)) }
    let proj = [statusName(p.status), p.year.map(String.init) ?? ""].filter { !$0.isEmpty }.joined(separator: " · ")
    if !proj.isEmpty { facts.append((t("Project", "Mradi"), proj)) }
    if let v = s.visits[p.id] { facts.append((t("Last visit", "Ziara ya mwisho"), ODates.day(v.at) + " · " + stateName(v.state))) }
    if let tst = x.tests[p.id] { facts.append((t("Water last tested", "Maji yalipimwa mwisho"), ODates.day(tst))) }
    if let r = s.repairs[p.id] { facts.append((t("Open repair", "Matengenezo yaliyo wazi"), stateName(r.state) + ", " + t("reported ", "iliripotiwa ") + ODates.day(r.at) + (r.problems.isEmpty ? "" : ": " + r.problems.joined(separator: ", ")))) }
    let home = x.home.isEmpty ? m.prefs.country : x.home
    let sub = [p.type, whereText].filter { !$0.isEmpty }.joined(separator: " · ") + " · RAFIKI Outreach"
    let data = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 595, height: 842)).pdfData { ctx in
        ctx.beginPage(); drawLetterheadFoot(); drawLetterheadTop(home)
        let ink = UIColor(red: 0x04 / 255, green: 0x26 / 255, blue: 0x3F / 255, alpha: 1), muted = UIColor(red: 0x5A / 255, green: 0x60 / 255, blue: 0x66 / 255, alpha: 1)
        let L: CGFloat = 43.2, W: CGFloat = 595 - 2 * 43.2
        var y: CGFloat = 124
        func draw(_ s: String, _ font: UIFont, _ color: UIColor, _ x: CGFloat, _ w: CGFloat, maxH: CGFloat = 200) -> CGFloat {
            let a = NSAttributedString(string: s, attributes: [.font: font, .foregroundColor: color])
            let h = min(maxH, a.boundingRect(with: CGSize(width: w, height: maxH), options: [.usesLineFragmentOrigin], context: nil).height)
            a.draw(with: CGRect(x: x, y: y, width: w, height: h + 1), options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine], context: nil)
            return h
        }
        y += draw(p.title, .boldSystemFont(ofSize: 19), ink, L, W, maxH: 48) + 4
        y += draw(sub, .systemFont(ofSize: 9.5), muted, L, W, maxH: 26) + 10
        if !imgs.isEmpty {
            let n = CGFloat(imgs.count), gap: CGFloat = 8, w = (W - (n - 1) * gap) / n, h = n == 1 ? W * 9 / 16 : w * 3 / 4
            for (i, im) in imgs.enumerated() {
                let r = CGRect(x: L + CGFloat(i) * (w + gap), y: y, width: w, height: h)
                let sc = max(r.width / im.0.size.width, r.height / im.0.size.height)
                let dw = im.0.size.width * sc, dh = im.0.size.height * sc
                ctx.cgContext.saveGState(); UIBezierPath(roundedRect: r, cornerRadius: 6).addClip()
                im.0.draw(in: CGRect(x: r.midX - dw / 2, y: r.midY - dh / 2, width: dw, height: dh)); ctx.cgContext.restoreGState()
                let cy = y; y = r.maxY + 3; _ = draw(im.1, .systemFont(ofSize: 7.5), muted, r.minX, r.width, maxH: 20); y = cy
            }
            y += h + 22
        }
        if let people {
            y += draw(Geo.grouped(people), .boldSystemFont(ofSize: 28), ink, L, W, maxH: 36)
            y += draw(pLabel, .systemFont(ofSize: 10), muted, L, W, maxH: 30) + 12
        }
        if let st = story {
            y += draw("“" + st.0 + "”", .italicSystemFont(ofSize: 13), ink, L + 10, W - 10, maxH: 80)
            if !st.1.isEmpty { y += draw("— " + st.1, .systemFont(ofSize: 9.5), muted, L + 10, W - 10, maxH: 20) }
            y += 12
        }
        for f in facts where y < 760 {
            let cy = y
            _ = draw(f.0, .systemFont(ofSize: 10), muted, L, 140, maxH: 30)
            y = cy; let h = draw(f.1, .boldSystemFont(ofSize: 10), ink, L + 150, W - 150, maxH: 44)
            y = cy + max(16, h + 6)
        }
        y = min(y + 8, 770)
        _ = draw(t("Prepared by ", "Imeandaliwa na ") + m.prefs.name + " · " + ODates.fmt(Date(), "d MMM yyyy"), .systemFont(ofSize: 8.5), muted, L, W, maxH: 14)
    }
    let name = "RAFIKI-" + String(p.title.replacingOccurrences(of: "[^A-Za-z0-9]+", with: "-", options: .regularExpression).trimmingCharacters(in: CharacterSet(charactersIn: "-")).prefix(60)) + "-summary.pdf"
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
    do { try data.write(to: url) } catch { return t("Couldn’t make the PDF on this phone.", "Haikuweza kutengeneza PDF kwenye simu hii.") }
    Share.items([url])
    return nil
}

/// "More for this project": folded until tapped, so the project page starts short.
struct ProjectMore: View {
    @EnvironmentObject var m: AppModel
    let p: Project; let showTimeline: () -> Void
    @State private var open = false
    @State private var busy = false
    @State private var msg: String?
    var body: some View {
        let hu = ohue("slate")
        CardV(pad: 0) {
            Button { Haptic.tap(); withAnimation(Motion.on ? .easeInOut(duration: 0.22) : nil) { open.toggle() } } label: {
                HStack(spacing: 12) {
                    OTileIcon(icon: "more", tint: hu.fg, bg: hu.bg, size: 38)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(t("More for this project", "Zaidi kuhusu mradi huu")).font(.tSmall).foregroundColor(C.ink)
                        Text(t("Photo timeline, summary to share, thank-you for donors", "Picha kwa wakati, muhtasari, shukrani kwa wafadhili")).font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.leading)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                    Icon(open ? "up" : "down", size: 20, color: C.muted)
                }.padding(.horizontal, 14).padding(.vertical, 8).frame(minHeight: 60).contentShape(Rectangle())
            }.buttonStyle(.plain)
            if open {
                VStack(alignment: .leading, spacing: 8) {
                    Btn(t("Photo timeline", "Picha kwa wakati"), icon: "camera", light: true, small: true, wide: true, action: showTimeline)
                    Btn(t("Share project summary", "Shiriki muhtasari wa mradi"), icon: "share", enabled: !busy, light: true, small: true, wide: true, busy: busy ? t("Preparing…", "Inaandaa…") : nil) {
                        busy = true; msg = nil
                        Task { msg = await shareProjectSummary(m, p); busy = false }
                    }
                    Msg(msg, "bad")
                    DonorThanks(p: p)
                }.padding(.horizontal, 12).padding(.bottom, 12)
            }
        }.padding(.top, 12)
    }
}

/// Directions: Google Maps, Apple Maps, or another map app.
struct DirectionsSheet: View {
    let title: String; let at: LatLng; let close: () -> Void
    var body: some View {
        let ll = "\(at.lat),\(at.lng)"
        let hu = ohue("near")
        OSheet {
            OSheetHeadC(title: t("Directions", "Maelekezo"), sub: [title], lead: { OTileIcon(icon: "compass", tint: hu.fg, bg: hu.bg, size: 56) }, close: close)
            Spacer().frame(height: 14)
            row(t("Google Maps", "Google Maps"), t("Turn-by-turn directions", "Maelekezo hatua kwa hatua")) { close(); Share.open("https://www.google.com/maps/dir/?api=1&destination=\(ll)") }
            row(t("Apple Maps", "Ramani za Apple"), t("The map app on this iPhone", "Programu ya ramani kwenye iPhone hii")) {
                close(); Share.open("https://maps.apple.com/?daddr=\(ll)&q=" + (title.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? ""))
            }
        }
    }
    func row(_ title: String, _ sub: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Icon("map", size: 21, color: C.blue).frame(width: 40, height: 40).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                VStack(alignment: .leading, spacing: 2) { Text(title).font(.tSmall).foregroundColor(C.ink); Text(sub).font(.bSmall).foregroundColor(C.muted) }.frame(maxWidth: .infinity, alignment: .leading)
                Icon("chev", size: 18, color: C.muted)
            }.padding(.horizontal, 14).padding(.vertical, 10).frame(minHeight: 64)
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(C.edge, lineWidth: 1)).contentShape(Rectangle())
        }.buttonStyle(.plain).padding(.top, 8)
    }
}

/// A picture from the internet, fitted inside its frame (for full-screen viewers).
struct ONetFit: View {
    let url: String
    @State private var img: UIImage?
    var body: some View {
        Color.clear.overlay(Group {
            if let img { Image(uiImage: img).resizable().scaledToFit() } else { Icon("camera", size: 28, color: Color.white.opacity(0.6)) }
        })
        .task(id: url) { img = Pics.shared.cached(url); if img == nil { img = await Pics.shared.load(url) } }
    }
}

import SwiftUI

// Office › Costs, people served, Clean-up and Export (Android Costs.kt, OfficeOverview.kt › OcvCosts, OfficeScreen.kt).

private func peopleType(_ k: String) -> (String, String, String) {
    switch k {
    case "water": return (t("Water points", "Vituo vya maji"), "drop", "blue")
    case "health": return (t("Health", "Afya"), "cross", "coral")
    case "school": return (t("Schools", "Shule"), "school", "amber")
    case "children": return (t("Children’s homes", "Makao ya watoto"), "house", "purple")
    case "livelihoods": return (t("Livelihoods", "Riziki"), "leaf", "green")
    default: return (t("Community", "Jamii"), "people", "teal")
    }
}

/// How many people the projects serve today, the 12-month trend, by country and by type.
struct PeopleServedCard: View {
    let p: PeopleServed
    var body: some View {
        let teal = ohue("teal")
        CardV(pad: 16) {
            HStack(spacing: 12) {
                OTileIcon(icon: "people", tint: teal.fg, bg: teal.bg, size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(t("People served", "Watu wanaohudumiwa")).font(.mont(17, .heavy)).foregroundColor(C.ink)
                    Text(t("Latest visit counts on \(p.measured) of \(p.counted) projects; the project records for the rest", "Hesabu za ziara za karibuni kwenye miradi \(p.measured) kati ya \(p.counted); kumbukumbu za miradi kwa mingine"))
                        .font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true)
                }
            }
            HStack(spacing: 10) {
                CountUp(value: p.total, font: .mont(34, .heavy), color: C.ink)
                if p.growth > 0 {
                    HStack(spacing: 4) { OIcon("trend", size: 15, color: C.ok); Text("+" + Geo.grouped(p.growth) + " " + t("in 12 months", "kwa miezi 12")).font(.mont(12, .heavy)).foregroundColor(C.ok) }
                        .padding(.horizontal, 10).padding(.vertical, 5).background(C.okBg).clipShape(Capsule())
                }
            }.padding(.top, 12)
            OSpark(vals: p.trend.map { $0.1 }, color: teal.fg).frame(height: 70).padding(.top, 8)
            HStack {
                Text(p.trend.first.map { m in DateField.parse(m.0 + "-01").map { ODates.fmt($0, "MMM yy") } ?? m.0 } ?? "").font(.lSmall).foregroundColor(C.muted)
                Text(t("12-month trend", "Mwenendo wa miezi 12")).font(.lSmall).foregroundColor(C.muted).frame(maxWidth: .infinity)
                Text(t("now", "sasa")).font(.lSmall).foregroundColor(C.muted)
            }
            OLabel(t("By country", "Kwa nchi"))
            let cmax = max(1, p.byCountry.values.max() ?? 1)
            ForEach(COUNTRIES.filter { p.byCountry[$0.0] != nil }, id: \.0) { k, n in
                let v = p.byCountry[k] ?? 0
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 8) { Text(COUNTRY_FLAG[k] ?? "").font(.system(size: 15)); Text(n).font(.tSmall).foregroundColor(C.ink).frame(maxWidth: .infinity, alignment: .leading); Text(Geo.grouped(v)).font(.tSmall).foregroundColor(C.ink) }
                    OBar(f: Double(v) / Double(cmax), color: COUNTRY_COLOR[k] ?? C.blue, height: 8).padding(.leading, 26)
                }.padding(.top, 8)
            }
            OLabel(t("By type", "Kwa aina"))
            ForEach(p.byType.sorted { $0.value > $1.value }, id: \.key) { k, v in
                let (label, ic, hue) = peopleType(k); let hu = ohue(hue)
                HStack(spacing: 10) {
                    OTileIcon(icon: ic, tint: hu.fg, bg: hu.bg, size: 32)
                    Text(label).font(.tSmall).foregroundColor(C.ink).frame(maxWidth: .infinity, alignment: .leading)
                    Text(Geo.grouped(v)).font(.tSmall).foregroundColor(C.ink)
                }.padding(.top, 8)
            }
        }
    }
}

/// A small line chart that draws itself (nil = month not known yet).
struct OSpark: View {
    let vals: [Int?]; let color: Color
    @State private var drawn: CGFloat = 0
    var body: some View {
        let known = vals.compactMap { $0 }
        GeometryReader { g in
            if known.count >= 2 {
                let lo = Double(known.min() ?? 0), span = max(1, Double((known.max() ?? 0) - (known.min() ?? 0)))
                let pts: [CGPoint] = vals.enumerated().compactMap { i, v in
                    v.map { CGPoint(x: 6 + CGFloat(i) * (g.size.width - 12) / CGFloat(max(1, vals.count - 1)), y: g.size.height - 8 - CGFloat((Double($0) - lo) / span) * (g.size.height - 22)) }
                }
                let line = Path { p in p.move(to: pts[0]); pts.dropFirst().forEach { p.addLine(to: $0) } }
                ZStack {
                    Path { p in p.addPath(line); p.addLine(to: CGPoint(x: pts[pts.count - 1].x, y: g.size.height)); p.addLine(to: CGPoint(x: pts[0].x, y: g.size.height)); p.closeSubpath() }
                        .fill(color.opacity(0.13)).opacity(Double(drawn))
                    line.trim(from: 0, to: drawn).stroke(color, style: StrokeStyle(lineWidth: 2.6, lineCap: .round, lineJoin: .round))
                    if drawn >= 1, let last = pts.last { Circle().fill(color).frame(width: 9, height: 9).overlay(Circle().stroke(C.card, lineWidth: 3)).position(last) }
                }
            }
        }
        .accessibilityLabel(t("People served over the last 12 months", "Watu waliohudumiwa katika miezi 12 iliyopita"))
        .onAppear { if Motion.on { withAnimation(.easeOut(duration: 1.4)) { drawn = 1 } } else { drawn = 1 } }
    }
}

/// Every cost entry, newest first.
func costsCsv(_ v: CostView, _ projects: [Project]) -> String {
    officeCsv([["Date", "Project ID", "Project", "Country", "Repair reported", "Step", "What", "Estimate (KES)", "Actual (KES)", "By", "Receipt"]] +
        v.rows.map { r in let p = projects.first { $0.id == r.id }
            return [String(r.at.prefix(10)), r.id, p?.title ?? r.title, p?.country ?? "", String(r.rid.prefix(10)), r.step == "fixed" ? "Fixed" : r.step == "parts" ? "Parts ordered" : r.step, r.what, r.est, r.actual, r.by, r.receipt ? "yes" : ""] })
}
/// Costs and people served in one summary sheet.
func summaryCsv(_ v: CostView, _ people: PeopleServed?) -> String {
    var l: [[Any?]] = [["Section", "Item", "Value", "Repairs"]]
    l.append(["Repair spending", "This month", v.month, ""]); l.append(["Repair spending", "This year (\(v.yearKey))", v.year, ""])
    l.append(["Repair spending", "Last 12 months", v.sum12, v.repairs]); l.append(["Repair spending", "Average per repair (last 12 months)", v.avg, ""])
    COUNTRIES.forEach { k, n in l.append(["Spent this year by country", n, v.byCountry[k] ?? 0, ""]) }
    v.top.enumerated().forEach { i, p in l.append(["Top 10 water points (last 12 months)", "\(i + 1). \(p.title)" + (countryName(p.ck).map { " (\($0))" } ?? ""), p.sum, p.n]) }
    l.append(["Second approval", "Above (KES)", v.approveAbove, ""])
    if let people, people.total > 0 {
        l.append(["People served", "Total", people.total, ""])
        people.byCountry.forEach { k, n in l.append(["People served by country", countryName(k) ?? k, n, ""]) }
        people.byType.forEach { k, n in l.append(["People served by type", k, n, ""]) }
        people.trend.forEach { mo, n in if let n { l.append(["People served by month", mo, n, ""]) } }
    }
    return officeCsv(l)
}

// --- Office › Costs --------------------------------------------------------------------------------------------

private struct MonthKey: Hashable { let y: Int; let m: Int; var key: String { String(format: "%04d-%02d", y, m) } }
private func lastMonths() -> [MonthKey] {
    let cal = Calendar.current; let now = Date()
    return (0..<12).reversed().compactMap { i in cal.date(byAdding: .month, value: -i, to: now).map { MonthKey(y: cal.component(.year, from: $0), m: cal.component(.month, from: $0)) } }
}
private func monthDate(_ k: MonthKey) -> Date { DateField.parse(k.key + "-01") ?? Date() }

/// Office › Costs: the big numbers and the 12-month chart first, spending as cards that open the project's cost
/// history, by country, and the second-approval limit and the download folded away.
struct OfficeCosts: View {
    @EnvironmentObject var m: AppModel
    let s: Snapshot
    @State private var v: CostView?
    @State private var err: String?
    @State private var cf = "latest"
    @State private var shown = 30
    @State private var month = ""
    @State private var picked: OItem?

    func load() async { let x = await m.api.adminGet("costs"); if x.ok { v = CostView.from(x.json); err = nil } else { err = reasonText(x.reason) } }

    var body: some View {
        Group {
            if let cv = v { content(cv) } else { Text(err ?? t("Loading…", "Inapakia…")).foregroundColor(err != nil ? C.bad : C.muted) }
        }
        .task { await Currency.refresh(AppModel.server); await load() }
        .sheet(item: $picked) { x in if let cv = v { CostSheet(cv: cv, s: s, id: x.id) { picked = nil } } }
    }

    func title(_ id: String, _ fallback: String) -> String { s.projects.first { $0.id == id }?.title ?? (fallback.isEmpty ? id : fallback) }

    @ViewBuilder func content(_ cv: CostView) -> some View {
        OHead(icon: "coins", hue: "amber", title: t("Repair costs", "Gharama za matengenezo"), line: t("What repairs actually cost, as the team enters it.", "Gharama halisi za matengenezo, kama timu inavyoandika.")) {
            ONums(items: [ONum(value: Geo.grouped(Currency.num(cv.month)), label: t("this month", "mwezi huu"), prefix: Currency.tag, sub: Currency.kesSub(cv.month)),
                          ONum(value: Geo.grouped(Currency.num(cv.year)), label: t("this year (\(cv.yearKey))", "mwaka huu (\(cv.yearKey))"), prefix: Currency.tag, sub: Currency.kesSub(cv.year))])
            ONums(items: [ONum(value: Geo.grouped(Currency.num(cv.sum12)), label: t("last 12 months", "miezi 12 iliyopita"), prefix: Currency.tag, sub: Currency.kesSub(cv.sum12)),
                          ONum(value: Geo.grouped(Currency.num(cv.avg)), label: t("average per repair", "wastani kwa kila tengenezo"), prefix: Currency.tag,
                               sub: [Currency.kesSub(cv.avg), cv.repairs == 1 ? t("1 repair", "matengenezo 1") : t("\(cv.repairs) repairs", "matengenezo \(cv.repairs)")].filter { !$0.isEmpty }.joined(separator: " · "))])
                .padding(.top, -4)
        }
        chart(cv)
        Text(t("Spending", "Matumizi")).font(.mont(17, .heavy)).foregroundColor(C.ink).padding(.top, 18)
        OChips(items: [("latest", t("Latest", "Mpya zaidi"), cv.rows.count), ("top", t("Most spent", "Gharama kubwa zaidi"), cv.top.count)], picked: cf) { cf = $0; shown = 30 }
        let empty = t("No repair costs recorded yet. Enter the actual cost when parts are ordered or a repair is fixed.", "Bado hakuna gharama za matengenezo. Andika gharama halisi vipuri vikiagizwa au matengenezo yakikamilika.")
        if cf == "top" {
            if cv.top.isEmpty { OEmpty(empty) }
            let coral = ohue("coral"), amber = ohue("amber")
            ForEach(Array(cv.top.prefix(shown).enumerated()), id: \.offset) { i, tp in
                OListCard(title: title(tp.id, tp.title), right: Money.kes(tp.sum), rightStrong: true, lines: [(countryName(tp.ck).map { "\($0) · " } ?? "") + (tp.n == 1 ? t("1 repair", "matengenezo 1") : t("\(tp.n) repairs", "matengenezo \(tp.n)"))], index: i, lead: {
                    Text("\(i + 1)").font(.mont(14, .heavy)).foregroundColor(i == 0 ? .white : amber.fg).frame(width: 40, height: 40).background(i == 0 ? coral.fg : amber.bg).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }) { picked = OItem(id: tp.id, o: [:]) }
            }
            if cv.top.count > shown { OMore(n: cv.top.count - shown) { shown += 30 } }
        } else {
            if cv.rows.isEmpty { OEmpty(empty) }
            ForEach(Array(cv.rows.prefix(shown).enumerated()), id: \.offset) { i, r in
                let fx = r.step == "fixed"; let hu = ohue(fx ? "green" : "amber")
                OListCard(title: title(r.id, r.title), right: Money.kes(r.actual), rightStrong: true, pill: (fx ? t("Fixed", "Imerekebishwa") : t("Parts ordered", "Vipuri vimeagizwa"), fx ? "ok" : "warn"),
                          side: ODates.ago(r.at), lines: [[r.what, r.by].filter { !$0.isEmpty }.joined(separator: " · ") + (r.receipt ? " · " + t("receipt", "risiti") : "")], index: i,
                          lead: { OTileIcon(icon: fx ? "check" : "box", tint: hu.fg, bg: hu.bg, size: 40) }) { picked = OItem(id: r.id, o: [:]) }
            }
            if cv.rows.count > shown { OMore(n: cv.rows.count - shown) { shown += 30 } }
        }
        CardV(pad: 16) {
            Text(t("By country, this year", "Kwa nchi, mwaka huu")).font(.tSmall).foregroundColor(C.ink)
            let cmax = max(1, cv.byCountry.values.max() ?? 1)
            ForEach(COUNTRIES, id: \.0) { k, n in
                let amt = cv.byCountry[k] ?? 0
                VStack(alignment: .leading, spacing: 4) {
                    HStack(alignment: .firstTextBaseline) { Text((COUNTRY_FLAG[k] ?? "") + "  " + n).font(.tSmall).foregroundColor(C.ink).frame(maxWidth: .infinity, alignment: .leading); Text(Money.kes(amt)).font(.tSmall).foregroundColor(C.ink).multilineTextAlignment(.trailing) }
                    OBar(f: Double(amt) / Double(cmax), color: COUNTRY_COLOR[k] ?? C.blue, height: 8)
                }.padding(.top, 10)
            }
        }.padding(.top, 14)
        CardV(pad: 4) {
            ApproveLimit(open: OfficeJump.to == "limit", limit: cv.approveAbove) { Task { await load() } }
            ODivider().padding(.horizontal, 14)
            OFold(icon: "upload", hue: "blue", title: t("Download", "Hamisha"), summary: t("Every cost entry, as a spreadsheet", "Kila gharama, kama jedwali")) {
                Text(t("A CSV file that opens in Excel and Google Sheets: every cost entry from the last 2 years.", "Faili ya CSV inayofunguka kwenye Excel na Google Sheets: kila gharama ya miaka 2 iliyopita.")).font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true)
                Btn(t("Share CSV", "Shiriki CSV"), icon: "upload", light: true, wide: true) { shareCsv("rafiki-repair-costs-\(When.localDay()).csv", costsCsv(cv, s.projects)) }.padding(.top, 10)
            }
        }.padding(.top, 12).id("limit")
        .onAppear { if OfficeJump.to == "limit" { OfficeJump.to = nil } }
    }

    func chart(_ cv: CostView) -> some View {
        let ms = lastMonths()
        let sums = Dictionary(grouping: cv.rows, by: { String($0.at.prefix(7)) })
        let sel = ms.first { $0.key == month } ?? ms[ms.count - 1]
        let maxV = max(1, ms.map { k in sums[k.key]?.reduce(0) { $0 + $1.actual } ?? 0 }.max() ?? 1)
        let sr = sums[sel.key] ?? []
        let selSum = sr.reduce(0) { $0 + $1.actual }
        return CardV(pad: 16) {
            Text(t("Spent each month", "Matumizi kila mwezi")).font(.tSmall).foregroundColor(C.ink)
            (Text(ODates.fmt(monthDate(sel), "LLLL yyyy").capitalized).bold().foregroundColor(C.ink)
             + Text(" · " + Money.kes(selSum) + (sr.isEmpty ? "" : " · " + (sr.count == 1 ? t("1 entry", "kiingilio 1") : t("\(sr.count) entries", "viingilio \(sr.count)")))))
                .font(.bSmall).foregroundColor(C.muted).padding(.top, 2)
            HStack(alignment: .bottom, spacing: 4) {
                ForEach(ms, id: \.self) { k in
                    let amt = sums[k.key]?.reduce(0) { $0 + $1.actual } ?? 0; let on = k == sel
                    Button { Haptic.tap(); month = k.key } label: {
                        VStack(spacing: 5) {
                            ZStack(alignment: .bottom) {
                                RoundedRectangle(cornerRadius: 8, style: .continuous).fill(C.bg)
                                RoundedRectangle(cornerRadius: 8, style: .continuous).fill(ohue("amber").fg.opacity(on ? 1 : 0.5)).frame(height: max(3, 110 * CGFloat(amt) / CGFloat(maxV)))
                            }.frame(height: 110).overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).stroke(oSun ? Color.black : .clear, lineWidth: 1.5))
                            Text(String(ODates.fmt(monthDate(k), "LLL").prefix(3)).capitalized).font(.mont(10, .bold)).foregroundColor(on ? C.ink : C.muted).lineLimit(1).minimumScaleFactor(0.6)
                        }
                    }.buttonStyle(.plain).frame(maxWidth: .infinity)
                    .accessibilityLabel(ODates.fmt(monthDate(k), "LLLL yyyy") + ": " + Money.kes(amt))
                }
            }.padding(.top, 12)
        }.padding(.top, 12)
    }
}

/// Costs › Second approval: the amount above which a repair needs a second admin.
private struct ApproveLimit: View {
    @EnvironmentObject var m: AppModel
    let open: Bool; let limit: Int; let saved: () -> Void
    @State private var lim = ""
    @State private var msg: (String, String)?
    @State private var busy = false
    var body: some View {
        OFold(icon: "shield", hue: "purple", title: t("Second approval", "Idhini ya pili"), summary: t("Above ", "Zaidi ya ") + Money.kes(limit), open: open) {
            Text(t("Repairs estimated above this need a second admin to approve the cost before parts are ordered. The person who asks can’t approve their own.", "Matengenezo yanayokadiriwa zaidi ya kiasi hiki yanahitaji msimamizi wa pili kuidhinisha kabla ya kuagiza vipuri. Aliyeomba hawezi kuidhinisha mwenyewe."))
                .font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true)
            Field(t("Second approval above (KES)", "Idhini ya pili zaidi ya (KES)"), Binding(get: { lim }, set: { lim = $0.filter(\.isNumber) }), number: true)
            Btn(t("Save", "Hifadhi"), icon: "check", enabled: Money.parse(lim) != nil && !busy, wide: true, busy: busy ? t("Saving…", "Inahifadhi…") : nil) {
                busy = true
                Task {
                    let x = await m.api.admin(["action": "settings", "approveAbove": lim])
                    busy = false
                    msg = x.ok ? ("ok", t("Saved ✓ Second approval above ", "Imehifadhiwa ✓ Idhini ya pili zaidi ya ") + Money.kes(x.json.int("approveAbove"))) : ("bad", reasonText(x.reason))
                    if x.ok { Haptic.ok(); m.reload(); saved() }
                }
            }.padding(.top, 10)
            if case let (tone, text)? = msg { Msg(text, tone) }
        }
        .onAppear { if lim.isEmpty { lim = "\(limit)" } }
    }
}

/// One project's repair costs: the last 12 months, and every entry.
private struct CostSheet: View {
    @EnvironmentObject var m: AppModel
    let cv: CostView; let s: Snapshot; let id: String; let close: () -> Void
    var body: some View {
        let all = cv.rows.filter { $0.id == id }
        let p = s.projects.first { $0.id == id }
        let tp = cv.top.first { $0.id == id }
        let yr = Date().addingTimeInterval(-365 * 86400)
        let y = all.filter { (When.parse($0.at) ?? .distantPast) > yr }
        let sum = !y.isEmpty ? y.reduce(0) { $0 + $1.actual } : tp?.sum ?? 0
        let n = !y.isEmpty ? y.count : tp?.n ?? 0
        let firstTitle: String = all.first?.title ?? ""
        let title: String = p?.title ?? (!firstTitle.isEmpty ? firstTitle : (tp?.title ?? id))
        let am = ohue("amber")
        OSheet {
            OSheetHead(label: countryName(p?.ck ?? tp?.ck ?? "") ?? "", title: title, lead: { OTileIcon(icon: "coins", tint: am.fg, bg: am.bg, size: 52) }, close: close)
            VStack(spacing: 2) {
                Text(Money.kes(sum)).font(.mont(26, .heavy)).foregroundColor(C.ink).multilineTextAlignment(.center)
                Text(t("spent in the last 12 months", "zilizotumika katika miezi 12 iliyopita") + (n > 0 ? " · " + (n == 1 ? t("1 entry", "kiingilio 1") : t("\(n) entries", "viingilio \(n)")) : ""))
                    .font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.center)
            }.frame(maxWidth: .infinity).padding(.top, 16)
            if !all.isEmpty {
                OLabel(t("Cost history", "Historia ya gharama"))
                ForEach(Array(all.enumerated()), id: \.offset) { _, r in
                    let fx = r.step == "fixed"; let hu = ohue(fx ? "green" : "amber"); let step = fx ? t("Fixed", "Imerekebishwa") : t("Parts ordered", "Vipuri vimeagizwa")
                    HStack(spacing: 12) {
                        OTileIcon(icon: fx ? "check" : "box", tint: hu.fg, bg: hu.bg, size: 36)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(r.what.isEmpty ? step : r.what).font(.tSmall).foregroundColor(C.ink)
                            Text([step, r.by, ODates.ago(r.at), r.receipt ? t("receipt", "risiti") : ""].filter { !$0.isEmpty }.map { $0.replacingOccurrences(of: " ", with: "\u{00A0}") }.joined(separator: " · "))
                                .font(.bSmall).foregroundColor(C.muted)
                        }.frame(maxWidth: .infinity, alignment: .leading)
                        Text(Money.kes(r.actual)).font(.tSmall).foregroundColor(C.ink).lineLimit(1).fixedSize()
                    }.padding(.vertical, 10)
                    ODivider()
                }
            }
            if p != nil { Btn(t("Open project", "Fungua mradi"), icon: "arrow", wide: true) { close(); m.go(Place(tab: "office", project: id)) }.padding(.top, 16) }
        }
    }
}

// --- Office › Clean-up -----------------------------------------------------------------------------------------

/// Rough boxes around each country, to spot points that landed in the wrong place.
private let BOUNDS: [String: [Double]] = ["ke": [-4.9, 5.1, 33.8, 42.0], "tz": [-11.8, -0.9, 29.2, 40.6], "ug": [-1.6, 4.3, 29.5, 35.1], "mw": [-17.2, -9.3, 32.6, 36.0]]

struct OfficeCleanup: View {
    @EnvironmentObject var m: AppModel
    let s: Snapshot
    @State private var more: Set<Int> = []
    var body: some View {
        let outside = s.projects.filter { p in
            guard let g = p.gps, let b = BOUNDS[p.ck] else { return false }
            return !(b[0]...b[1]).contains(g.lat) || !(b[2]...b[3]).contains(g.lng)
        }
        let pinned = s.projects.filter { $0.gps != nil }
        let groups = Dictionary(grouping: pinned) { p in p.gps.map { String(format: "%.5f,%.5f", $0.lat, $0.lng) } ?? "" }
        let dup = groups.values.filter { $0.count > 1 }.flatMap { $0 }
        let lists: [(String, String, [Project])] = [
            (t("No location", "Hakuna mahali"), t("Open each one and pin it (Find a place by name helps).", "Fungua kila mmoja na uweke mahali (Tafuta mahali kwa jina inasaidia)."), s.projects.filter { $0.gps == nil }),
            (t("Location outside its country", "Mahali nje ya nchi yake"), t("Usually latitude and longitude swapped, or a typing slip.", "Mara nyingi latitudo na longitudo zimebadilishana, au kosa la kuandika."), outside),
            (t("Same point as another project", "Mahali sawa na mradi mwingine"), t("Probably a copied location. Check which one is right.", "Huenda mahali hapa pamenakiliwa. Hakikisha kipi ni sahihi."), dup),
            (t("No photo", "Hakuna picha"), t("Add a photo to the “Photos of the project” column in monday.com.", "Ongeza picha kwenye safu ya “Photos of the project” katika monday.com."), s.projects.filter { $0.photo == nil }),
            (t("No visit for over a year", "Hakuna ziara kwa zaidi ya mwaka"), t("Only visits made with the app are known.", "Ziara zilizofanywa kwa programu tu ndizo zinajulikana."), pinned.filter { ageDays(s, $0) > 365 }),
        ]
        let wrong = Set((outside + dup).map(\.id)).count
        let todo = Set(lists.flatMap { $0.2 }.map(\.id)).count
        let looks = [("pinq", "blue"), ("globe", "coral"), ("copy", "amber"), ("camera", "purple"), ("clock", "teal")]
        VStack(alignment: .leading, spacing: 0) {
            OHead(icon: "spark", hue: "teal", title: t("Clean-up", "Safisha data"), line: todo == 0 ? t("Every project looks tidy.", "Kila mradi uko sawa.") : t("Fix these to keep the map and monday.com tidy. Tap a project to open it.", "Rekebisha haya ili ramani na monday.com viwe safi. Gusa mradi kuufungua.")) {
                ONums(items: [ONum(value: "\(lists[0].2.count)", label: t("no location", "bila mahali"), color: lists[0].2.isEmpty ? C.ok : C.blue2),
                              ONum(value: "\(wrong)", label: t("location to check", "mahali pa kukagua"), color: wrong == 0 ? C.ok : C.bad),
                              ONum(value: "\(lists[3].2.count)", label: t("no photo", "bila picha"), color: lists[3].2.isEmpty ? C.ok : C.ink)])
            }
            ForEach(lists.indices, id: \.self) { i in
                let (title, hint, rows) = lists[i]; let hu = ohue(looks[i].1); let open = more.contains(i)
                CardV(pad: 16) {
                    HStack(spacing: 12) {
                        OTileIcon(icon: looks[i].0, tint: hu.fg, bg: hu.bg, size: 42)
                        VStack(alignment: .leading, spacing: 2) { Text(title).font(.tSmall).foregroundColor(C.ink); Text(hint).font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true) }.frame(maxWidth: .infinity, alignment: .leading)
                        if rows.isEmpty { Tag("✓", bg: C.okBg, fg: C.ok) } else { Tag("\(rows.count)", bg: hu.bg, fg: hu.fg) }
                    }
                    if rows.isEmpty { Text(t("All good here.", "Hakuna tatizo hapa.")).font(.lLarge).foregroundColor(C.ok).padding(.top, 10) }
                    else if i == 2 {
                        let gs = Dictionary(grouping: rows) { p in p.gps.map { String(format: "%.4f, %.4f", $0.lat, $0.lng) } ?? "" }.sorted { $0.key < $1.key }
                        ForEach(Array((open ? gs : Array(gs.prefix(2))).enumerated()), id: \.offset) { _, g in
                            HStack(spacing: 6) { Icon("pin", size: 14, color: hu.fg); Text(t("\(g.value.count) projects at \(g.key)", "Miradi \(g.value.count) mahali \(g.key)")).font(.lMedium).foregroundColor(hu.fg) }.padding(.top, 10).padding(.bottom, 2)
                            box(Array(g.value), nil)
                        }
                        if gs.count > 2 { moreBtn(i, open, gs.count) }
                    } else {
                        box(Array(rows.prefix(open ? 120 : 4)), tagFor(i)).padding(.top, 8)
                        if rows.count > 4 { moreBtn(i, open, rows.count) }
                    }
                }.padding(.top, 12).rise(i)
            }
        }
    }

    func tagFor(_ i: Int) -> ((Project) -> String?)? {
        if i == 1 { return { p in p.gps.map { String(format: "%.4f, %.4f", $0.lat, $0.lng) } } }
        if i == 4 { return { p in s.visits[p.id].map { checkedAgo($0.at) } } }
        return nil
    }

    func checkedAgo(_ iso: String) -> String {
        guard let d = When.parse(iso) else { return "" }
        let days = Int(Date().timeIntervalSince(d) / 86400)
        return days >= 365 ? t("\(days / 365) yr ago", "mwaka \(days / 365) uliopita") : t("\(days) days ago", "siku \(days) zilizopita")
    }

    func box(_ ps: [Project], _ tag: ((Project) -> String?)?) -> some View {
        VStack(spacing: 0) {
            ForEach(Array(ps.enumerated()), id: \.element.id) { j, p in
                if j > 0 { ODivider() }
                let (ic, hk) = typeLook(p); let hu = ohue(hk)
                Button { m.go(Place(tab: "office", project: p.id)) } label: {
                    HStack(spacing: 12) {
                        OTileIcon(icon: ic, tint: hu.fg, bg: hu.bg, size: 36)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(p.title).font(.mont(14.5, .bold)).foregroundColor(C.ink).lineLimit(2).multilineTextAlignment(.leading)
                            Text([p.area, p.country].filter { !$0.isEmpty }.joined(separator: " · ")).font(.bSmall).foregroundColor(C.muted).lineLimit(1)
                            if let x = tag?(p) { Text(x).font(.lSmall).foregroundColor(C.muted).padding(.top, 1) }
                        }.frame(maxWidth: .infinity, alignment: .leading)
                        Icon("chev", size: 18, color: C.muted)
                    }.padding(.horizontal, 12).padding(.vertical, 10).contentShape(Rectangle())
                }.buttonStyle(.plain)
            }
        }.overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.line, lineWidth: 1))
    }

    func moreBtn(_ i: Int, _ open: Bool, _ n: Int) -> some View {
        Button { withAnimation { if open { more.remove(i) } else { more.insert(i) } } } label: {
            HStack(spacing: 6) { Text(open ? t("Show fewer", "Onyesha machache") : t("Show all \(n)", "Onyesha zote \(n)")).font(.lLarge).foregroundColor(C.blue2); Icon("down", size: 16, color: C.blue2).rotationEffect(.degrees(open ? 180 : 0)) }
                .frame(maxWidth: .infinity, minHeight: 44).background(C.tint).clipShape(Capsule())
        }.buttonStyle(.plain).padding(.top, 10)
    }
}

// --- Office › Export -------------------------------------------------------------------------------------------

struct OfficeExport: View {
    @EnvironmentObject var m: AppModel
    let s: Snapshot
    @State private var msg: String?
    func withCosts(_ go: @escaping (CostView) -> Void) {
        Task {
            msg = t("Gathering repair costs…", "Inakusanya gharama za matengenezo…")
            let x = await m.api.adminGet("costs"); if !x.ok { msg = reasonText(x.reason); return }
            msg = nil; go(CostView.from(x.json))
        }
    }
    var body: some View {
        let stamp = When.localDay()
        VStack(alignment: .leading, spacing: 10) {
            OHead(icon: "upload", hue: "blue", title: t("Export", "Hamisha"), line: t("CSV files open in Excel and Google Sheets.", "Faili za CSV zinafunguka kwenye Excel na Google Sheets."))
            Btn(t("Projects with locations and status", "Miradi yenye mahali na hali yake"), icon: "upload", light: true, wide: true) {
                shareCsv("rafiki-projects-\(stamp).csv", officeCsv([["ID", "Project", "Country", "Area", "Type", "Status", "Latitude", "Longitude", "Last visit", "Condition", "Open repair"]] +
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
                    shareCsv("rafiki-visits-\(stamp).csv", officeCsv([["Date", "ID", "Project", "By", "Condition", "Urgency", "Strokes to fill 20 L", "Flow (L/min)", "People using it", "Queue (min)", "E. coli"]] + rows))
                }
            }
            Btn(t("Open repairs and jobs", "Matengenezo na kazi zilizo wazi"), icon: "upload", light: true, wide: true) {
                shareCsv("rafiki-repairs-\(stamp).csv", officeCsv([["ID", "Project", "Condition", "Urgency", "Reported", "By", "Problems", "Parts", "Est. KES", "Step", "Assigned to", "Due", "Budget KES"]] +
                    s.repairs.map { id, r in [id, s.projects.first { $0.id == id }?.title ?? r.title, r.state, r.urgency, String(r.at.prefix(10)), r.by, r.problems.joined(separator: "; "), r.parts, r.cost, r.step, r.assignee, r.due, r.budget] }))
            }
            Btn(t("Everything the team did (last 13 months)", "Kila kitu timu ilifanya (miezi 13 iliyopita)"), icon: "upload", light: true, wide: true) {
                Task {
                    if !(await m.loadActivity()) { msg = reasonText("offline"); return }
                    shareCsv("rafiki-activity-\(stamp).csv", officeCsv([["Date", "Time", "Who", "What", "Project", "Result"]] + (m.activity ?? []).map { e in
                        let at = e.str("at"); return [String(at.prefix(10)), String(at.dropFirst(11).prefix(5)), e.str("by"), e.str("a"), e.str("t"), e.str("s")]
                    }))
                }
            }
            Btn(t("Repair costs (every entry, last 2 years)", "Gharama za matengenezo (kila kiingilio, miaka 2)"), icon: "coins", light: true, wide: true) {
                withCosts { shareCsv("rafiki-repair-costs-\(stamp).csv", costsCsv($0, s.projects)) }
            }
            Btn(t("Costs and people served: summary", "Gharama na watu wanaohudumiwa: muhtasari"), icon: "coins", light: true, wide: true) {
                withCosts { shareCsv("rafiki-costs-and-people-\(stamp).csv", summaryCsv($0, m.sx.people)) }
            }
            Btn(t("Monthly report (PDF)", "Ripoti ya mwezi (PDF)"), icon: "file", light: true, wide: true) { m.go(Place(tab: "office", sub: "report")) }
            Msg(msg)
        }
    }
}

import SwiftUI
import UIKit

// Office › Overview, Costs and Visits, made calm and tappable (the same as Android's OfficeOverview.kt and the web
// app's ocv… code): a compact head with a few numbers, tiles and cards you tap, sheets for the details and every
// action, and rarely used settings folded away.

let FLAGS: [String: String] = ["ke": "🇰🇪", "tz": "🇹🇿", "ug": "🇺🇬", "mw": "🇲🇼"]
let COUNTRY_COLOR: [String: Color] = ["ke": Color(hex: 0x0A78C2), "tz": Color(hex: 0x1C9C5A), "ug": Color(hex: 0xE39A1E), "mw": Color(hex: 0x7A4FD0)]

/// A report sent by SMS or WhatsApp by someone in a community, not by the team.
func isCommunity(_ e: JSON) -> Bool { e.str("src") == "sms" || e.str("by").hasPrefix("SMS from") || e.str("by").hasPrefix("WhatsApp from") }
/// "now", "12 min", "3 h", "Yesterday", "19 Oct".
func smsAgo(_ iso: String, now: Date = Date()) -> String {
    guard let at = When.parse(iso) else { return "" }
    let m = Int(now.timeIntervalSince(at) / 60)
    if m < 1 { return t("now", "sasa") }
    if m < 60 { return "\(m) min" }
    if m < 24 * 60 { return "\(m / 60) h" }
    if m < 48 * 60 { return t("Yesterday", "Jana") }
    return Dates.day(at)
}
func ocvTone(_ tone: String) -> (Color, Color) {
    switch tone { case "bad": return (C.bad, C.badBg); case "warn": return (C.warn, C.warnBg); case "ok": return (C.ok, C.okBg); case "has": return (C.blue2, C.tint); default: return (C.muted, C.bg) }
}
func ocvFeedHue(_ a: String) -> String {
    switch a { case "pin", "approve", "assign": return "pin"; case "visit", "fixed": return "near"; case "new": return "new"; case "job", "request": return "repairs"; case "reject": return "me"; default: return "slate" }
}
func ocvFeedIcon(_ a: String) -> String {
    ["pin": "pin", "visit": "clip", "fixed": "check", "new": "plus", "undo": "undo", "job": "wrench", "assign": "cal", "team": "people", "ask": "hourglass", "settings": "shield", "approve": "check"][a] ?? "drop"
}
func csvOf(_ rows: [[Any?]]) -> String {
    rows.map { r in
        r.map { v -> String in
            var x = ""
            switch v { case nil: x = ""; case let s as String: x = s; case let d as Double: x = d == d.rounded() ? String(Int(d)) : String(d); case let n as Int: x = String(n); case let some?: x = "\(some)" }
            return x.contains(where: { $0 == "," || $0 == "\"" || $0 == "\n" }) ? "\"" + x.replacingOccurrences(of: "\"", with: "\"\"") + "\"" : x
        }.joined(separator: ",")
    }.joined(separator: "\r\n")
}
func shareCsv(_ name: String, _ text: String) {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
    try? ("\u{FEFF}" + text).write(to: url, atomically: true, encoding: .utf8)
    Share.items([url])
}
/// Every cost entry, newest first.
func costsCsv(_ v: CostView, _ projects: [Project]) -> String {
    csvOf([["Date", "Project ID", "Project", "Country", "Repair reported", "Step", "What", "Estimate (KES)", "Actual (KES)", "By", "Receipt"]] +
        v.rows.map { r in let p = projects.first { $0.id == r.pid }
            return [String(r.at.prefix(10)), r.pid, p?.title ?? r.title, p?.country ?? "", String(r.rid.prefix(10)), r.step == "fixed" ? "Fixed" : r.step == "parts" ? "Parts ordered" : r.step, r.what, r.est, r.actual, r.by, r.receipt ? "yes" : ""] })
}
/// Costs and people served in one summary sheet.
func summaryCsv(_ v: CostView, _ people: PeopleServed?) -> String {
    var l: [[Any?]] = [["Section", "Item", "Value", "Repairs"]]
    l.append(["Repair spending", "This month", v.month, ""]); l.append(["Repair spending", "This year (\(v.yearKey))", v.year, ""])
    l.append(["Repair spending", "Last 12 months", v.sum12, v.repairs]); l.append(["Repair spending", "Average per repair (last 12 months)", v.avg, ""])
    for (k, n) in COUNTRIES { l.append(["Spent this year by country", n, v.byCountry[k] ?? 0, ""]) }
    for (i, p) in v.top.enumerated() { l.append(["Top 10 water points (last 12 months)", "\(i + 1). \(p.title)" + (countryName(p.ck).map { " (\($0))" } ?? ""), p.sum, p.n]) }
    l.append(["Second approval", "Above (KES)", v.approveAbove, ""])
    if let people, people.total > 0 {
        l.append(["People served", "Total", people.total, ""])
        for (k, n) in people.byCountry { l.append(["People served by country", countryName(k) ?? k, n, ""]) }
        for (k, n) in people.byType { l.append(["People served by type", k, n, ""]) }
        for mo in people.trend { if let n = mo.n { l.append(["People served by month", mo.m, n, ""]) } }
    }
    return csvOf(l)
}

// MARK: - shared pieces

/// The head of a tab: icon, title, one short line; then whatever the tab adds.
struct OcvHead<Content: View>: View {
    let icon: String; let hue: String; let title: String; let line: String
    @ViewBuilder var content: () -> Content
    init(icon: String, hue: String, title: String, line: String, @ViewBuilder content: @escaping () -> Content = { EmptyView() }) {
        self.icon = icon; self.hue = hue; self.title = title; self.line = line; self.content = content
    }
    var body: some View {
        CardV(pad: 16) {
            HStack(spacing: 12) {
                IconTile(icon: icon, tint: C.named(hue).fg, bg: C.named(hue).bg, size: 44)
                VStack(alignment: .leading, spacing: 1) { Text(title).font(.tMedium).foregroundColor(C.ink); Text(line).font(.bSmall).foregroundColor(C.muted) }
                Spacer(minLength: 0)
            }
            content()
        }
    }
}

/// A small number in a head. Tappable ones set the filter.
/// The small label in front of a money tile: "KES", or "≈ USD" when another currency is chosen (Settings › Currency).
func curTag() -> String { Currency.code == "KES" ? "KES" : "≈\u{00A0}" + Currency.code }
/// Under a converted money tile: the KES amount as entered.
func kesSub(_ n: Int) -> String { Currency.code == "KES" ? "" : "KES\u{00A0}" + Geo.grouped(n) }

struct OcvNum: View {
    let value: String; let label: String; var color: Color = C.ink; var on = false; var prefix = ""; var sub = ""; var action: (() -> Void)? = nil
    var body: some View {
        let inner = VStack(alignment: .leading, spacing: 1) {
            HStack(alignment: .lastTextBaseline, spacing: 4) {
                if !prefix.isEmpty { Text(prefix).font(.mont(10, .heavy)).foregroundColor(C.muted) }
                Text(value).font(.mont(prefix.isEmpty ? 22 : 19, .heavy)).foregroundColor(color).lineLimit(1).minimumScaleFactor(0.6)
            }
            Text(label).font(.bSmall).foregroundColor(C.muted).lineLimit(3).multilineTextAlignment(.leading)
            if !sub.isEmpty { Text(sub).font(.bSmall).foregroundColor(C.muted) }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 10).padding(.vertical, 8).frame(maxWidth: .infinity, minHeight: 56, alignment: .topLeading)
        .background(on ? C.tint : C.bg).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(on ? C.blue : C.line, lineWidth: on ? 1.5 : C.sun ? 2 : 1))
        if let action { Button { Haptic.tap(); action() } label: { inner }.buttonStyle(Press()) } else { inner }
    }
}

/// A status: a dot and a short label, in its tone.
struct OcvPill: View {
    let label: String; let tone: String
    var body: some View {
        let (fg, bg) = ocvTone(tone)
        HStack(spacing: 6) { Circle().fill(fg).frame(width: 7, height: 7); Text(label).font(.mont(12, .heavy)).foregroundColor(fg) }
            .padding(.leading, 8).padding(.trailing, 10).padding(.vertical, 3).background(bg).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(C.sun ? fg : .clear, lineWidth: 1.5))
    }
}

/// A list card: something on the left, a title with the time (or amount) on the right, a status, up to two lines.
struct OcvCard<Lead: View>: View {
    let i: Int; @ViewBuilder var lead: () -> Lead
    let title: String; let right: String; let rightStrong: Bool; var pill: (String, String)? = nil; var side = ""; var lines: [String] = []; var italicLast = false
    let tap: () -> Void
    var body: some View {
        let ls = lines.filter { !$0.isEmpty }
        Button { Haptic.tap(); tap() } label: {
            HStack(alignment: .top, spacing: 12) {
                lead()
                VStack(alignment: .leading, spacing: 0) {
                    HStack(alignment: .top, spacing: 8) {
                        Text(title).font(.tSmall).foregroundColor(C.ink).multilineTextAlignment(.leading).frame(maxWidth: .infinity, alignment: .leading)
                        Text(right).font(rightStrong ? .tSmall : .mont(12, .semibold)).foregroundColor(rightStrong ? C.ink : C.muted).lineLimit(1).fixedSize()
                        Icon("chev", size: 16, color: C.muted)
                    }
                    if pill != nil || !side.isEmpty {
                        HStack { if let pill { OcvPill(label: pill.0, tone: pill.1) }; Spacer(minLength: 0); if !side.isEmpty { Text(side).font(.bSmall).foregroundColor(C.muted).lineLimit(1) } }.padding(.top, 5)
                    }
                    ForEach(Array(ls.enumerated()), id: \.offset) { k, l in
                        Text(l).font(.bSmall).italic(italicLast && k == ls.count - 1 && k > 0).foregroundColor(C.muted).lineLimit(1).padding(.top, k == 0 ? 5 : 1)
                    }
                }
            }
            .padding(14).frame(maxWidth: .infinity, alignment: .leading).background(C.card).clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(C.line, lineWidth: C.sun ? 2 : 1))
        }.buttonStyle(Press()).padding(.top, 8).rise(min(i, 6), step: 0.035)
    }
}

/// Filter chips with their counts, sideways.
struct OcvChips: View {
    let items: [(String, String, Bool)]; let pick: (Int) -> Void
    var body: some View {
        ScrollViewReader { sv in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Array(items.enumerated()), id: \.offset) { i, x in FilterPill(label: "\(x.0) · \(x.1)", on: x.2) { Haptic.tap(); pick(i) }.id(i) }
                }.padding(.vertical, 2)
            }
            .onAppear { if let i = items.firstIndex(where: { $0.2 }) { sv.scrollTo(i, anchor: .center) } }
        }.padding(.top, 12)
    }
}

/// The grey box shown while a number loads.
struct Skel: View {
    var w: CGFloat = 52; var h: CGFloat = 26; var dark = false
    @State private var a = 0.45
    var body: some View {
        RoundedRectangle(cornerRadius: 8).fill(dark ? Color.white.opacity(0.22) : C.line).opacity(a).frame(width: w, height: h)
            .onAppear { if Motion.on { withAnimation(.easeInOut(duration: 0.9).repeatForever()) { a = 0.9 } } else { a = 0.7 } }
    }
}

/// Initials in a coloured circle, as on the Team list.
struct OcvAvatar: View {
    let name: String; var size: CGFloat = 40
    var body: some View {
        let hu = C.named(nameHue(name))
        Text(initialsOf(name)).font(.mont(size * 0.34, .heavy)).foregroundColor(hu.fg).frame(width: size, height: size).background(Circle().fill(hu.bg))
            .overlay(Circle().stroke(C.sun ? Color.black : .clear, lineWidth: 2))
    }
}

struct OcvSheetHead<Lead: View>: View {
    @ViewBuilder var lead: () -> Lead
    let label: String; let labelColor: Color; let title: String; let close: () -> Void
    var body: some View {
        HStack(spacing: 14) {
            lead()
            VStack(alignment: .leading, spacing: 1) {
                if !label.isEmpty { Text(label).font(.mont(14, .heavy)).foregroundColor(labelColor) }
                Text(title).font(.tLarge).foregroundColor(C.ink)
            }
            Spacer(minLength: 8)
            Button(action: close) { Icon("x", size: 20, color: C.ink).frame(width: 44, height: 44).background(Circle().fill(C.bg)) }.buttonStyle(.plain).accessibilityLabel(t("Close", "Funga"))
        }
    }
}

struct OcvFact: View {
    let label: String; let value: String; var color: Color = C.ink; var sub = ""
    var body: some View {
        VStack(spacing: 0) {
            HStack(alignment: .top, spacing: 12) {
                Text(label).font(.bMedium).foregroundColor(C.muted)
                Spacer(minLength: 12)
                VStack(alignment: .trailing, spacing: 1) {
                    Text(value).font(.mont(14, .bold)).foregroundColor(color).multilineTextAlignment(.trailing)
                    if !sub.isEmpty { Text(sub).font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.trailing) }
                }.frame(maxWidth: 210, alignment: .trailing)
            }.padding(.vertical, 10)
            Rectangle().fill(C.line).frame(height: 1)
        }
    }
}

/// A settings row that opens with a tap; the summary under the title says what's inside.
struct OcvFold<Content: View>: View {
    let icon: String; let hue: String; let title: String; let summary: String
    @ViewBuilder var content: () -> Content
    @State private var open = false
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button { Haptic.tap(); withAnimation(Motion.on ? .easeOut(duration: 0.22) : nil) { open.toggle() } } label: {
                HStack(spacing: 12) {
                    IconTile(icon: icon, tint: C.named(hue).fg, bg: C.named(hue).bg, size: 36)
                    VStack(alignment: .leading, spacing: 1) { Text(title).font(.tSmall).foregroundColor(C.ink); Text(summary).font(.bSmall).foregroundColor(C.muted) }
                    Spacer(minLength: 6)
                    Icon("chev", size: 18, color: C.muted).rotationEffect(.degrees(open ? 90 : 0))
                }.padding(.horizontal, 14).padding(.vertical, 8).frame(minHeight: 60).contentShape(Rectangle())
            }.buttonStyle(.plain)
            if open { VStack(alignment: .leading, spacing: 0) { content() }.padding(.horizontal, 14).padding(.bottom, 14).transition(.opacity) }
        }
    }
}

struct OcvBar: View {
    let f: Double; let color: Color; var height: CGFloat = 6
    @State private var g = 0.0
    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) { Capsule().fill(C.line); if g > 0.001 { Capsule().fill(color).frame(width: geo.size.width * g) } }
        }.frame(height: height)
        .onAppear { let v = min(1, max(0, f)); if Motion.on { withAnimation(.easeOut(duration: 1.1)) { g = v } } else { g = v } }
        .onChange(of: f) { v in withAnimation(.easeOut(duration: 0.5)) { g = min(1, max(0, v)) } }
    }
}

struct OcvMore: View {
    let n: Int; let action: () -> Void
    var body: some View { Btn(t("Show \(n) more", "Onyesha \(n) zaidi"), light: true, small: true, wide: true, action: action).padding(.top, 10) }
}

/// A bottom sheet in the Office's look: scrolls, with room at the bottom.
struct OcvSheet<Content: View>: View {
    @ViewBuilder var content: (_ close: @escaping () -> Void) -> Content
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        ScrollView { VStack(alignment: .leading, spacing: 0) { content { dismiss() } }.padding(.horizontal, 20).padding(.top, 20).padding(.bottom, 28) }
            .background(C.card.ignoresSafeArea())
            .presentationDragIndicator(.visible)
    }
}

// MARK: - Overview

/// Office › Overview: how much is on the map, number tiles that open their list, tab or sheet, countries, the field.
struct OcvOverview: View {
    @EnvironmentObject var m: AppModel
    let s: Snapshot; let go: (String) -> Void
    @State private var people = false
    @State private var country: String?
    @State private var feedMore = false
    var body: some View {
        let pinned = s.projects.filter { $0.gps != nil }.count, missing = s.projects.count - pinned
        let pct = s.projects.isEmpty ? 0 : pinned * 100 / s.projects.count
        let due = s.projects.filter { $0.gps != nil && ageDays(s, $0) >= DUE_DAYS }.count
        let ms = Calendar.current.date(from: Calendar.current.dateComponents([.year, .month], from: Date()))!
        let vm = m.activity.map { a in a.filter { $0.str("a") == "visit" && (When.parse($0.str("at")).map { $0 >= ms } ?? false) }.count }
        let urgent = s.repairs.values.filter { $0.urgency == "urgent" }.count
        let cost = s.repairs.values.reduce(0) { $0 + ($1.cost ?? 0) }
        let tests = testCount(s)
        let pe = s.people.flatMap { $0.total > 0 ? $0 : nil }
        VStack(alignment: .leading, spacing: 0) {
            OcvHead(icon: "drop", hue: "blue", title: t("Overview", "Muhtasari"), line: t("All projects today. Tap a number to see the list.", "Miradi yote leo. Gusa namba kuona orodha.")) {
                Button { if missing > 0 { Haptic.tap(); toList("missing") } } label: {
                    HStack(spacing: 12) {
                        OcvRing(pct: pct)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(t("\(pinned) of \(s.projects.count) projects on the map", "Miradi \(pinned) kati ya \(s.projects.count) iko kwenye ramani")).font(.tSmall).foregroundColor(C.ink).multilineTextAlignment(.leading)
                            Text(missing == 0 ? t("Every project has a location", "Kila mradi una mahali") : missing == 1 ? t("1 still needs a location", "1 bado unahitaji mahali") : t("\(missing) still need a location", "\(missing) bado inahitaji mahali")).font(.bSmall).foregroundColor(C.muted)
                        }
                        Spacer(minLength: 0)
                        if missing > 0 { Icon("chev", size: 18, color: C.muted) }
                    }.padding(.horizontal, 12).padding(.vertical, 8).frame(minHeight: 64).background(C.bg).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.line, lineWidth: C.sun ? 2 : 1))
                }.buttonStyle(Press()).padding(.top, 12)
            }
            let tiles: [OcvT] = [
                OcvT(icon: "wrench", hue: "amber", n: s.repairs.count, label: s.repairs.count == 1 ? t("open repair", "tengenezo lililo wazi") : t("open repairs", "matengenezo yaliyo wazi"),
                     sub: [urgent > 0 ? t("\(urgent) urgent", "\(urgent) ya haraka") : nil, cost > 0 ? Currency.kes(cost) : nil].compactMap { $0 }.joined(separator: " · "), hot: !s.repairs.isEmpty, red: urgent > 0) { go("repairs") },
                OcvT(icon: "inbox", hue: "purple", n: s.reviewOpen, label: s.reviewOpen == 1 ? t("waiting for your review", "inasubiri uidhinishe") : t("waiting for your review", "zinasubiri uidhinishe"), hot: s.reviewOpen > 0) { go("review") },
                OcvT(icon: "cal", hue: "blue", n: due, label: due == 1 ? t("due a visit", "unasubiri ziara") : t("due a visit", "zinasubiri ziara")) { toList("due") },
                OcvT(icon: "drop", hue: "teal", n: tests, label: tests == 1 ? t("water test due", "kipimo cha maji kinahitajika") : t("water tests due", "vipimo vya maji vinahitajika"), hot: tests > 0) { toList("due") },
                OcvT(icon: "clip", hue: "green", n: vm, label: vm == 1 ? t("visit this month", "ziara ya mwezi huu") : t("visits this month", "ziara za mwezi huu")) { go("visits") },
            ] + (pe.map { p in [OcvT(icon: "people", hue: "teal", n: p.total, label: t("people served", "watu wanaohudumiwa"), sub: p.growth > 0 ? "+" + Geo.grouped(p.growth) + " " + t("in 12 months", "kwa miezi 12") : "", up: true) { people = true }] } ?? [])
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
                ForEach(Array(tiles.enumerated()), id: \.offset) { i, x in OcvTile(i: i + 1, x: x) }
            }.padding(.top, 10)
            health
            countries
            feed
            Text(t("Orange and red: open repairs. Green: other projects with a location.", "Machungwa na nyekundu: matengenezo yaliyo wazi. Kijani: miradi mingine yenye mahali.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 12)
            Btn(t("See them on the map", "Ziona kwenye ramani"), icon: "map", light: true, small: true, wide: true) { HomeLayers.s.revealed = true; m.tab("map") }.padding(.top, 6)
        }
        .task { await m.loadActivity() }
        .sheet(isPresented: $people) { if let pe { OcvSheet { close in
            HStack { Spacer(); Button(action: close) { Icon("x", size: 20, color: C.ink).frame(width: 44, height: 44).background(Circle().fill(C.bg)) }.buttonStyle(.plain) }
            PeopleServedCard(p: pe).padding(.top, 4)
        } } }
        .sheet(item: Binding(get: { country.map { WhoBox(name: $0) } }, set: { country = $0?.name })) { c in OcvCountrySheet(s: s, k: c.name) { f in toList(f, c.name) } }
    }
    private func toList(_ f: String, _ ck: String? = nil) { m.prefs.filter = f; if let ck { m.prefs.country = ck }; country = nil; m.tab("list") }

    private var health: some View {
        let st = Dictionary(grouping: s.visits.values, by: \.state).mapValues(\.count)
        let seen = st.values.reduce(0, +), unseen = max(0, s.projects.count - seen), tot = CGFloat(max(1, s.projects.count))
        let parts: [(Int, Color, String)] = [(st["working"] ?? 0, GreenC, t("working", "zinafanya kazi")), (st["repair"] ?? 0, Amber, t("needed repair", "zilihitaji matengenezo")), (st["notworking"] ?? 0, RedC, t("not working", "hazifanyi kazi")), (unseen, C.off, t("not visited yet", "bado hazijatembelewa"))]
        return CardV(pad: 16) {
            HStack { Text(t("Condition at last visit", "Hali katika ziara ya mwisho")).font(.tSmall).foregroundColor(C.ink); Spacer(); Text(t("\(seen) of \(s.projects.count) visited", "\(seen) kati ya \(s.projects.count) zimetembelewa")).font(.bSmall).foregroundColor(C.muted) }
            GeometryReader { g in
                HStack(spacing: 2) { ForEach(Array(parts.enumerated()), id: \.offset) { _, x in if x.0 > 0 { Rectangle().fill(x.1).frame(width: max(0, g.size.width * CGFloat(x.0) / tot - 2)) } }; Spacer(minLength: 0) }
                    .background(C.line).clipShape(Capsule())
            }.frame(height: 12).padding(.vertical, 10)
            LazyVGrid(columns: [GridItem(.flexible(), alignment: .leading), GridItem(.flexible(), alignment: .leading)], spacing: 6) {
                ForEach(Array(parts.enumerated()), id: \.offset) { _, x in
                    HStack(spacing: 4) { RoundedRectangle(cornerRadius: 3).fill(x.1).frame(width: 10, height: 10); Text(" \(x.0) ").font(.tSmall).foregroundColor(C.ink); Text(x.2).font(.bSmall).foregroundColor(C.muted).lineLimit(1).minimumScaleFactor(0.8) }
                }
            }
        }.padding(.top, 12)
    }

    private var countries: some View {
        CardV(pad: 16) {
            Text(t("By country", "Kwa nchi")).font(.tSmall).foregroundColor(C.ink)
            let present = COUNTRIES.filter { c in s.projects.contains { $0.ck == c.0 } }
            ForEach(Array(present.enumerated()), id: \.element.0) { i, c in
                let l = s.projects.filter { $0.ck == c.0 }
                let loc = l.filter { $0.gps != nil }.count, rp = l.filter { s.repairs[$0.id] != nil }.count, du = l.filter { $0.gps != nil && ageDays(s, $0) >= DUE_DAYS }.count
                if i > 0 { Rectangle().fill(C.line).frame(height: 1) }
                Button { Haptic.tap(); country = c.0 } label: {
                    HStack(spacing: 12) {
                        Text(FLAGS[c.0] ?? "").font(.system(size: 21)).frame(width: 42, height: 42).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
                        VStack(alignment: .leading, spacing: 0) {
                            HStack { Text(c.1).font(.tSmall).foregroundColor(C.ink); Spacer(); Text(t("\(loc) of \(l.count) mapped", "\(loc) kati ya \(l.count) zina mahali")).font(.bSmall).foregroundColor(C.muted).lineLimit(1) }
                            OcvBar(f: Double(loc) / Double(l.count), color: C.blue).padding(.vertical, 6)
                            if rp > 0 { OcvPill(label: rp == 1 ? t("1 open repair", "matengenezo 1") : t("\(rp) open repairs", "matengenezo \(rp)"), tone: "warn") }
                            else if du > 0 { OcvPill(label: t("\(du) due a visit", "\(du) zinasubiri ziara"), tone: "has") }
                            else { OcvPill(label: t("Nothing needs attention", "Hakuna kinachohitaji kushughulikiwa"), tone: "ok") }
                        }
                        Icon("chev", size: 18, color: C.muted)
                    }.padding(.vertical, 10).frame(minHeight: 72).contentShape(Rectangle())
                }.buttonStyle(.plain)
            }
        }.padding(.top, 12)
    }

    private var feed: some View {
        CardV(pad: 16) {
            Text(t("Latest from the field", "Habari za karibuni kutoka uwanjani")).font(.tSmall).foregroundColor(C.ink)
            if let a = m.activity {
                if a.isEmpty { Text(t("Nothing yet.", "Bado hakuna kitu.")).foregroundColor(C.muted).padding(.top, 8) }
                let all = Array(a.suffix(12).reversed()), list = feedMore ? all : Array(all.prefix(5))
                ForEach(Array(list.enumerated()), id: \.offset) { i, e in
                    let id = e.str("id"), known = !id.isEmpty && s.projects.contains { $0.id == id }, sms = isCommunity(e)
                    let hu = C.hue(sms ? "slate" : ocvFeedHue(e.str("a")))
                    if i > 0 { Rectangle().fill(C.line).frame(height: 1) }
                    HStack(spacing: 12) {
                        if sms || !Contact.isPerson(e.str("by").trimmingCharacters(in: .whitespaces)) {
                            Icon(sms ? "phone" : ocvFeedIcon(e.str("a")), size: 16, color: hu.fg).frame(width: 32, height: 32).background(Circle().fill(hu.bg))
                        } else {
                            PersonBadgeAvatar(name: e.str("by"), icon: ocvFeedIcon(e.str("a")), hue: hu, ring: C.card, size: 34).onTapGesture { Contact.show(e.str("by")) }
                        }
                        VStack(alignment: .leading, spacing: 1) {
                            (Text(sms ? communityName() : e.str("by")).font(.mont(14, .bold)).foregroundColor(C.ink) + Text(sms ? ": " + e.str("t") + (e.str("a") == "fixed" ? " · " + t("working again", "inafanya kazi tena") : e.str("a") == "visit" && !e.str("s").isEmpty ? " · " + stateName(e.str("s")) : "") : " " + verb(e.str("a")) + " " + e.str("t")).font(.bMedium).foregroundColor(C.text))
                            Text(smsAgo(e.str("at"))).font(.bSmall).foregroundColor(C.muted)
                        }
                        Spacer(minLength: 0)
                        if known { Icon("chev", size: 18, color: C.muted) }
                    }.padding(.vertical, 10).frame(minHeight: 56).contentShape(Rectangle())
                    .onTapGesture { if known { m.go(Place(tab: "office", project: id)) } }
                }
                if !feedMore && all.count > 5 { OcvMore(n: all.count - 5) { feedMore = true } }
            } else { Text(t("Loading…", "Inapakia…")).foregroundColor(C.muted).padding(.top, 8) }
        }.padding(.top, 12)
    }
}

struct WhoBox: Identifiable { let name: String; var id: String { name } }

/// The share of projects on the map, as a ring that fills.
struct OcvRing: View {
    let pct: Int
    @State private var f = 0.0
    var body: some View {
        ZStack {
            Circle().stroke(C.line, lineWidth: 5)
            Circle().trim(from: 0, to: f).stroke(C.blue, style: StrokeStyle(lineWidth: 5, lineCap: .round)).rotationEffect(.degrees(-90))
            Text("\(pct)%").font(.mont(12, .heavy)).foregroundColor(C.ink)
        }.frame(width: 48, height: 48)
        .onAppear { if Motion.on { withAnimation(.easeOut(duration: 1.3)) { f = Double(pct) / 100 } } else { f = Double(pct) / 100 } }
    }
}

struct OcvT { let icon: String; let hue: String; let n: Int?; let label: String; var sub = ""; var hot = false; var red = false; var up = false; let tap: () -> Void }

struct OcvTile: View {
    let i: Int; let x: OcvT
    @State private var pulse = false
    var body: some View {
        let hu = C.named(x.hue)
        Button { Haptic.tap(); x.tap() } label: {
            VStack(alignment: .leading, spacing: 0) {
                IconTile(icon: x.icon, tint: x.red ? C.bad : x.hot ? C.warn : hu.fg, bg: x.red ? C.badBg : x.hot ? C.warnBg : hu.bg, size: 38)
                if let n = x.n { CountUp(value: n, font: .mont(26, .heavy), color: C.ink).padding(.top, 8) } else { Skel().padding(.top, 12).padding(.bottom, 4) }
                Spacer(minLength: 0)
                HStack(alignment: .bottom) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(x.label).font(.lMedium).foregroundColor(C.muted).multilineTextAlignment(.leading)
                        if !x.sub.isEmpty { Text(x.sub).font(.mont(12, x.up ? .bold : .regular)).foregroundColor(x.up ? C.ok : C.muted).multilineTextAlignment(.leading) }
                    }
                    Spacer(minLength: 0)
                    Icon("chev", size: 16, color: C.muted)
                }
            }
            .padding(14).frame(maxWidth: .infinity, minHeight: 128, alignment: .topLeading).background(C.card).clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(x.red ? C.bad : x.hot ? C.warn : C.line, lineWidth: x.hot || C.sun ? 2 : 1))
            .overlay(alignment: .topTrailing) {
                if x.red {
                    ZStack {
                        Circle().fill(RedC.opacity(pulse ? 0 : 0.35)).frame(width: pulse ? 22 : 10, height: pulse ? 22 : 10)
                        Circle().fill(RedC).frame(width: 10, height: 10)
                    }.frame(width: 22, height: 22).padding(12)
                    .onAppear { if Motion.on { withAnimation(.easeOut(duration: 1.4).repeatForever(autoreverses: false)) { pulse = true } } }
                }
            }
        }.buttonStyle(Press()).rise(i)
    }
}

/// A country: what's going on there, and buttons that open the right list.
private struct OcvCountrySheet: View {
    let s: Snapshot; let k: String; let toList: (String) -> Void
    var body: some View {
        let l = s.projects.filter { $0.ck == k }
        let ks = { (st: String) in l.filter { s.visits[$0.id]?.state == st }.count }
        let loc = l.filter { $0.gps != nil }.count, rp = l.filter { s.repairs[$0.id] != nil }.count, du = l.filter { $0.gps != nil && ageDays(s, $0) >= DUE_DAYS }.count
        OcvSheet { close in
            OcvSheetHead(lead: { Text(FLAGS[k] ?? "").font(.system(size: 26)).frame(width: 52, height: 52).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)) },
                         label: l.count == 1 ? t("1 project", "Mradi 1") : t("\(l.count) projects", "Miradi \(l.count)"), labelColor: C.blue2, title: countryName(k) ?? k, close: close)
            OcvBar(f: Double(loc) / Double(max(1, l.count)), color: C.blue, height: 8).padding(.top, 16).padding(.bottom, 4)
            OcvFact(label: t("On the map", "Kwenye ramani"), value: t("\(loc) of \(l.count)", "\(loc) kati ya \(l.count)"))
            OcvFact(label: t("Working at the last visit", "Zinafanya kazi, ziara ya mwisho"), value: "\(ks("working"))", color: ks("working") > 0 ? C.ok : C.ink)
            OcvFact(label: t("Needed repair", "Zilihitaji matengenezo"), value: "\(ks("repair"))", color: ks("repair") > 0 ? C.warn : C.ink)
            OcvFact(label: t("Not working", "Hazifanyi kazi"), value: "\(ks("notworking"))", color: ks("notworking") > 0 ? C.bad : C.ink)
            OcvFact(label: t("Open repairs", "Matengenezo yaliyo wazi"), value: "\(rp)", color: rp > 0 ? C.warn : C.ink)
            OcvFact(label: t("Due a visit", "Zinasubiri ziara"), value: "\(du)", color: du > 0 ? C.blue2 : C.ink)
            Btn(t("Show these projects", "Onyesha miradi hii"), icon: "chev", wide: true) { toList("all") }.padding(.top, 16)
            if l.count - loc > 0 { Btn(t("Need a location", "Zinahitaji mahali") + " (\(l.count - loc))", icon: "pin", light: true, wide: true) { toList("missing") }.padding(.top, 8) }
            if du > 0 { Btn(t("Due a visit", "Zinasubiri ziara") + " (\(du))", icon: "cal", light: true, wide: true) { toList("due") }.padding(.top, 8) }
            if rp > 0 { Btn(t("Open repairs", "Matengenezo yaliyo wazi") + " (\(rp))", icon: "wrench", light: true, wide: true) { toList("repairs") }.padding(.top, 8) }
        }.presentationDetents([.medium, .large])
    }
}

/// People served: the total, the 12-month trend, by country and by type.
struct PeopleServedCard: View {
    let p: PeopleServed
    var body: some View {
        let teal = C.named("teal")
        CardV(pad: 16) {
            HStack(spacing: 12) {
                IconTile(icon: "people", tint: teal.fg, bg: teal.bg, size: 40)
                VStack(alignment: .leading, spacing: 1) {
                    Text(t("People served", "Watu wanaohudumiwa")).font(.tMedium).foregroundColor(C.ink)
                    Text(t("Latest visit counts on \(p.measured) of \(p.counted) projects; the project records for the rest", "Hesabu za ziara za karibuni kwenye miradi \(p.measured) kati ya \(p.counted); kumbukumbu za miradi kwa mingine")).font(.bSmall).foregroundColor(C.muted)
                }
            }
            HStack(spacing: 10) {
                CountUp(value: p.total, font: .mont(34, .heavy), color: C.ink)
                if p.growth > 0 {
                    HStack(spacing: 4) { Icon("trend", size: 15, color: C.ok); Text("+" + Geo.grouped(p.growth) + " " + t("in 12 months", "kwa miezi 12")).font(.mont(12, .heavy)).foregroundColor(C.ok) }
                        .padding(.horizontal, 10).padding(.vertical, 5).background(C.okBg).clipShape(Capsule())
                }
            }.padding(.top, 12)
            Spark(vals: p.trend.map(\.n), color: teal.fg).frame(height: 70).padding(.top, 8)
            HStack {
                Text(p.trend.first.map { Dates.parse($0.m + "-01").map { d in let f = DateFormatter(); f.locale = Locale(identifier: "en_US"); f.dateFormat = "MMM yy"; return f.string(from: d) } ?? $0.m } ?? "").font(.lSmall).foregroundColor(C.muted)
                Spacer(); Text(t("12-month trend", "Mwenendo wa miezi 12")).font(.lSmall).foregroundColor(C.muted); Spacer()
                Text(t("now", "sasa")).font(.lSmall).foregroundColor(C.muted)
            }
            GroupLabel(t("By country", "Kwa nchi"), top: 14)
            let cmax = Double(max(1, p.byCountry.values.max() ?? 1))
            ForEach(COUNTRIES.filter { p.byCountry[$0.0] != nil }, id: \.0) { k, n in
                VStack(alignment: .leading, spacing: 4) {
                    HStack { Text(FLAGS[k] ?? "").font(.system(size: 15)); Text(n).font(.tSmall).foregroundColor(C.ink); Spacer(); Text(Geo.grouped(p.byCountry[k] ?? 0)).font(.tSmall).foregroundColor(C.ink) }
                    OcvBar(f: Double(p.byCountry[k] ?? 0) / cmax, color: COUNTRY_COLOR[k] ?? C.blue).padding(.leading, 26)
                }.padding(.top, 8)
            }
            GroupLabel(t("By type", "Kwa aina"), top: 16)
            ForEach(p.byType.sorted { $0.value > $1.value }, id: \.key) { k, v in
                let look = typeInfo(k)
                HStack(spacing: 10) {
                    IconTile(icon: look.1, tint: C.named(look.2).fg, bg: C.named(look.2).bg, size: 32)
                    Text(look.0).font(.tSmall).foregroundColor(C.ink); Spacer(); Text(Geo.grouped(v)).font(.tSmall).foregroundColor(C.ink)
                }.padding(.top, 8)
            }
        }
    }
}
/// A project type's name, icon and colour, from monday.com's type.
func typeInfo(_ k: String) -> (String, String, String) {
    let x = k.lowercased()
    if x.contains("borehole") || x.contains("collection") || x == "water" { return (typeName(k), "drop", "blue") }
    if x.contains("school") { return (typeName(k), "school", "purple") }
    if x.contains("health") { return (typeName(k), "cross", "coral") }
    if x.contains("orphan") || x.contains("children") { return (typeName(k), "house", "teal") }
    if x.contains("farm") { return (typeName(k), "leaf", "green") }
    return (typeName(k), "house", "slate")
}

/// A small line chart that draws itself (nil = month not known yet).
struct Spark: View {
    let vals: [Int?]; let color: Color
    @State private var draw = 0.0
    var body: some View {
        let known = vals.compactMap { $0 }
        GeometryReader { g in
            if known.count >= 2 {
                let lo = Double(known.min()!), span = max(1, Double(known.max()! - known.min()!))
                let pts: [CGPoint] = vals.enumerated().compactMap { i, v in v.map { CGPoint(x: 6 + CGFloat(i) * (g.size.width - 12) / CGFloat(max(1, vals.count - 1)), y: g.size.height - 8 - CGFloat((Double($0) - lo) / span) * (g.size.height - 22)) } }
                Path { p in p.move(to: pts[0]); for q in pts.dropFirst() { p.addLine(to: q) } }.trim(from: 0, to: draw).stroke(color, style: StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round))
                if let l = pts.last, draw > 0.98 { Circle().fill(color).frame(width: 8, height: 8).position(l) }
            }
        }
        .onAppear { if Motion.on { withAnimation(.easeOut(duration: 1.4)) { draw = 1 } } else { draw = 1 } }
        .accessibilityLabel(t("People served over the last 12 months", "Watu waliohudumiwa katika miezi 12 iliyopita"))
    }
}

// MARK: - Costs

/// Office › Costs: the big numbers and the 12-month chart first, spending as cards that open the project's cost
/// history, by country, and the second-approval limit and the download folded away.
struct OcvCosts: View {
    @EnvironmentObject var m: AppModel
    let s: Snapshot
    @State private var v: CostView?
    @State private var err: String?
    @State private var tick = 0
    @State private var cf = "latest"
    @State private var shown = 30
    @State private var month = ""
    @State private var picked: String?
    @State private var lim = ""
    @State private var lm: (String, String)?
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let cv = v { content(cv) } else { Text(err ?? t("Loading…", "Inapakia…")).foregroundColor(err != nil ? C.bad : C.muted) }
        }
        .task(id: tick) { let x = await m.api.adminGet("costs"); if x.ok { v = CostView.from(x.json); err = nil; lim = "\(v!.approveAbove)" } else { err = reasonText(x.reason) } }
        .sheet(item: Binding(get: { picked.map { WhoBox(name: $0) } }, set: { picked = $0?.name })) { b in if let cv = v { OcvCostSheet(cv: cv, s: s, id: b.name) { picked = nil; m.go(Place(tab: "office", project: b.name)) } } }
    }
    private func title(_ id: String, _ fb: String) -> String { s.projects.first { $0.id == id }?.title ?? (fb.isEmpty ? id : fb) }
    private func months() -> [String] {
        let cal = Calendar.current; let now = Date()
        return (0..<12).reversed().map { k in let d = cal.date(byAdding: .month, value: -k, to: now)!; let c = cal.dateComponents([.year, .month], from: d); return String(format: "%04d-%02d", c.year!, c.month!) }
    }
    private func monthName(_ ym: String, short: Bool) -> String {
        guard let d = Dates.parse(ym + "-01") else { return ym }
        let f = DateFormatter(); f.locale = Locale(identifier: Lang.sw ? "sw" : "en_GB"); f.dateFormat = short ? "LLL" : "LLLL yyyy"
        let s = f.string(from: d); return short ? String(s.prefix(3)).capitalized : s.prefix(1).uppercased() + s.dropFirst()
    }
    @ViewBuilder private func content(_ cv: CostView) -> some View {
        OcvHead(icon: "coins", hue: "amber", title: t("Repair costs", "Gharama za matengenezo"), line: t("What repairs actually cost, as the team enters it.", "Gharama halisi za matengenezo, kama timu inavyoandika.")) {
            HStack(spacing: 8) {
                OcvNum(value: Geo.grouped(Currency.num(cv.month)), label: t("this month", "mwezi huu"), prefix: curTag(), sub: kesSub(cv.month))
                OcvNum(value: Geo.grouped(Currency.num(cv.year)), label: t("this year (\(String(cv.yearKey)))", "mwaka huu (\(String(cv.yearKey)))"), prefix: curTag(), sub: kesSub(cv.year))
            }.fixedSize(horizontal: false, vertical: true).padding(.top, 12)
            HStack(spacing: 8) {
                OcvNum(value: Geo.grouped(Currency.num(cv.sum12)), label: t("last 12 months", "miezi 12 iliyopita"), prefix: curTag(), sub: kesSub(cv.sum12))
                OcvNum(value: Geo.grouped(Currency.num(cv.avg)), label: t("average per repair", "wastani kwa kila tengenezo"), prefix: curTag(), sub: [kesSub(cv.avg), cv.repairs == 1 ? t("1 repair", "matengenezo 1") : t("\(cv.repairs) repairs", "matengenezo \(cv.repairs)")].filter { !$0.isEmpty }.joined(separator: " · "))
            }.fixedSize(horizontal: false, vertical: true).padding(.top, 8)
        }
        let ms = months(), sums = Dictionary(grouping: cv.rows, by: { String($0.at.prefix(7)) })
        let sel = ms.contains(month) ? month : ms.last!
        let maxV = Double(max(1, ms.map { ym in sums[ym]?.reduce(0) { $0 + $1.actual } ?? 0 }.max() ?? 1))
        let sr = sums[sel] ?? []
        CardV(pad: 16) {
            Text(t("Spent each month", "Matumizi kila mwezi")).font(.tSmall).foregroundColor(C.ink)
            (Text(monthName(sel, short: false)).font(.mont(12, .bold)).foregroundColor(C.ink) + Text(" · " + Money.kes(sr.reduce(0) { $0 + $1.actual }) + (sr.isEmpty ? "" : " · " + (sr.count == 1 ? t("1 entry", "kiingilio 1") : t("\(sr.count) entries", "viingilio \(sr.count)")))).font(.bSmall).foregroundColor(C.muted)).padding(.top, 2)
            HStack(alignment: .bottom, spacing: 4) {
                ForEach(ms, id: \.self) { ym in
                    let amt = sums[ym]?.reduce(0) { $0 + $1.actual } ?? 0, on = ym == sel
                    Button { Haptic.tap(); month = ym } label: {
                        VStack(spacing: 5) {
                            ZStack(alignment: .bottom) {
                                RoundedRectangle(cornerRadius: 8).fill(C.bg)
                                RoundedRectangle(cornerRadius: 8).fill(C.named("amber").fg.opacity(on ? 1 : 0.5)).frame(height: max(3, 110 * CGFloat(Double(amt) / maxV)))
                            }.frame(height: 110)
                            Text(monthName(ym, short: true)).font(.mont(10, .bold)).foregroundColor(on ? C.ink : C.muted).lineLimit(1).minimumScaleFactor(0.7)
                        }
                    }.buttonStyle(.plain).accessibilityLabel(monthName(ym, short: false) + ": " + Money.kes(amt))
                }
            }.padding(.top, 12)
        }.padding(.top, 12)
        Text(t("Spending", "Matumizi")).font(.tMedium).foregroundColor(C.ink).padding(.top, 18)
        OcvChips(items: [(t("Latest", "Mpya zaidi"), "\(cv.rows.count)", cf == "latest"), (t("Most spent", "Gharama kubwa zaidi"), "\(cv.top.count)", cf == "top")]) { cf = $0 == 0 ? "latest" : "top"; shown = 30 }
        let none = t("No repair costs recorded yet. Enter the actual cost when parts are ordered or a repair is fixed.", "Bado hakuna gharama za matengenezo. Andika gharama halisi vipuri vikiagizwa au matengenezo yakikamilika.")
        if cf == "top" {
            if cv.top.isEmpty { EmptyDrop(text: none) }
            ForEach(Array(cv.top.prefix(shown).enumerated()), id: \.offset) { i, tp in
                let am = C.named("amber"), co = C.named("coral")
                OcvCard(i: i, lead: { Text("\(i + 1)").font(.mont(14, .heavy)).foregroundColor(i == 0 ? .white : am.fg).frame(width: 40, height: 40).background(i == 0 ? co.fg : am.bg).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous)) },
                        title: title(tp.id, tp.title), right: Money.kes(tp.sum), rightStrong: true,
                        lines: [(countryName(tp.ck).map { "\($0) · " } ?? "") + (tp.n == 1 ? t("1 repair", "matengenezo 1") : t("\(tp.n) repairs", "matengenezo \(tp.n)"))]) { picked = tp.id }
            }
            if cv.top.count > shown { OcvMore(n: cv.top.count - shown) { shown += 30 } }
        } else {
            if cv.rows.isEmpty { EmptyDrop(text: none) }
            ForEach(Array(cv.rows.prefix(shown).enumerated()), id: \.offset) { i, r in
                let fx = r.step == "fixed", hu = C.named(fx ? "green" : "amber")
                OcvCard(i: i, lead: { IconTile(icon: fx ? "check" : "box", tint: hu.fg, bg: hu.bg, size: 40) }, title: title(r.pid, r.title), right: Money.kes(r.actual), rightStrong: true,
                        pill: (fx ? t("Fixed", "Imerekebishwa") : t("Parts ordered", "Vipuri vimeagizwa"), fx ? "ok" : "warn"), side: smsAgo(r.at),
                        lines: [[r.what, r.by].filter { !$0.isEmpty }.joined(separator: " · ") + (r.receipt ? " · " + t("receipt", "risiti") : "")]) { picked = r.pid }
            }
            if cv.rows.count > shown { OcvMore(n: cv.rows.count - shown) { shown += 30 } }
        }
        CardV(pad: 16) {
            Text(t("By country, this year", "Kwa nchi, mwaka huu")).font(.tSmall).foregroundColor(C.ink)
            let cmax = Double(max(1, cv.byCountry.values.max() ?? 1))
            ForEach(COUNTRIES, id: \.0) { k, n in
                VStack(alignment: .leading, spacing: 4) {
                    HStack { Text((FLAGS[k] ?? "") + "  " + n).font(.tSmall).foregroundColor(C.ink); Spacer(); Text(Money.kes(cv.byCountry[k] ?? 0)).font(.tSmall).foregroundColor(C.ink) }
                    OcvBar(f: Double(cv.byCountry[k] ?? 0) / cmax, color: COUNTRY_COLOR[k] ?? C.blue, height: 8)
                }.padding(.top, 10)
            }
        }.padding(.top, 14)
        CardV(pad: 4) {
            OcvFold(icon: "shield", hue: "purple", title: t("Second approval", "Idhini ya pili"), summary: t("Above ", "Zaidi ya ") + Money.kes(cv.approveAbove)) {
                Text(t("Repairs estimated above this need a second admin to approve the cost before parts are ordered. The person who asks can’t approve their own.", "Matengenezo yanayokadiriwa zaidi ya kiasi hiki yanahitaji msimamizi wa pili kuidhinisha kabla ya kuagiza vipuri. Aliyeomba hawezi kuidhinisha mwenyewe.")).font(.bSmall).foregroundColor(C.muted)
                Field(t("Second approval above (KES)", "Idhini ya pili zaidi ya (KES)"), Binding(get: { lim }, set: { lim = $0.filter(\.isNumber) }), number: true)
                Btn(t("Save", "Hifadhi"), icon: "check", enabled: Money.parse(lim) != nil, wide: true) {
                    Task {
                        let x = await m.api.admin(["action": "settings", "approveAbove": lim])
                        lm = x.ok ? ("ok", t("Saved ✓ Second approval above ", "Imehifadhiwa ✓ Idhini ya pili zaidi ya ") + Money.kes(x.json.int("approveAbove"))) : ("bad", reasonText(x.reason))
                        if x.ok { Haptic.ok(); m.reload(); tick += 1 }
                    }
                }.padding(.top, 10)
                if let lm { Msg(lm.1, lm.0) }
            }
            Rectangle().fill(C.line).frame(height: 1).padding(.horizontal, 14)
            OcvFold(icon: "upload", hue: "blue", title: t("Download", "Hamisha"), summary: t("Every cost entry, as a spreadsheet", "Kila gharama, kama jedwali")) {
                Text(t("A CSV file that opens in Excel and Google Sheets: every cost entry from the last 2 years.", "Faili ya CSV inayofunguka kwenye Excel na Google Sheets: kila gharama ya miaka 2 iliyopita.")).font(.bSmall).foregroundColor(C.muted)
                Btn(t("Share CSV", "Shiriki CSV"), icon: "upload", light: true, wide: true) { shareCsv("rafiki-repair-costs-\(When.localDay()).csv", costsCsv(cv, s.projects)) }.padding(.top, 10)
            }
        }.padding(.top, 12)
    }
}

/// One project's repair costs: the last 12 months, and every entry.
private struct OcvCostSheet: View {
    let cv: CostView; let s: Snapshot; let id: String; let openIt: () -> Void
    var body: some View {
        let all = cv.rows.filter { $0.pid == id }
        let p = s.projects.first { $0.id == id }, tp = cv.top.first { $0.id == id }
        let yr = Date().addingTimeInterval(-365 * 86400)
        let y = all.filter { (When.parse($0.at) ?? .distantPast) > yr }
        let sum = !y.isEmpty ? y.reduce(0) { $0 + $1.actual } : tp?.sum ?? 0, n = !y.isEmpty ? y.count : tp?.n ?? 0
        let title = p?.title ?? all.first.flatMap { $0.title.isEmpty ? nil : $0.title } ?? tp?.title ?? id
        let am = C.named("amber")
        OcvSheet { close in
            OcvSheetHead(lead: { IconTile(icon: "coins", tint: am.fg, bg: am.bg, size: 52) }, label: countryName(p?.ck ?? tp?.ck ?? "") ?? "", labelColor: C.muted, title: title, close: close)
            VStack(spacing: 2) {
                Text(Money.kes(sum)).font(.mont(28, .heavy)).foregroundColor(C.ink)
                Text(t("spent in the last 12 months", "zilizotumika katika miezi 12 iliyopita") + (n > 0 ? " · " + (n == 1 ? t("1 entry", "kiingilio 1") : t("\(n) entries", "viingilio \(n)")) : "")).font(.bSmall).foregroundColor(C.muted).multilineTextAlignment(.center)
            }.frame(maxWidth: .infinity).padding(.top, 16)
            if !all.isEmpty {
                GroupLabel(t("Cost history", "Historia ya gharama"), top: 18)
                ForEach(Array(all.enumerated()), id: \.offset) { _, r in
                    let fx = r.step == "fixed", hu = C.named(fx ? "green" : "amber"), step = fx ? t("Fixed", "Imerekebishwa") : t("Parts ordered", "Vipuri vimeagizwa")
                    HStack(spacing: 12) {
                        IconTile(icon: fx ? "check" : "box", tint: hu.fg, bg: hu.bg, size: 36)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(r.what.isEmpty ? step : r.what).font(.tSmall).foregroundColor(C.ink)
                            Text([step, r.by, smsAgo(r.at), r.receipt ? t("receipt", "risiti") : ""].filter { !$0.isEmpty }.joined(separator: " · ")).font(.bSmall).foregroundColor(C.muted)
                        }
                        Spacer(minLength: 8)
                        Text(Money.kes(r.actual)).font(.tSmall).foregroundColor(C.ink).fixedSize()
                    }.padding(.vertical, 10)
                    Rectangle().fill(C.line).frame(height: 1)
                }
            }
            if p != nil { Btn(t("Open project", "Fungua mradi"), icon: "chev", wide: true) { close(); openIt() }.padding(.top, 16) }
        }
    }
}

// MARK: - Visits

private func ocvDone(_ a: JSON) -> Bool { let d = a.str("done"); return !d.isEmpty && d != "null" }
private func ocvState(_ a: JSON, _ today: String) -> String { ocvDone(a) ? "done" : a.str("date") < today ? "late" : a.str("date") == today ? "today" : "soon" }
private func ocvDays(_ a: String, _ b: String) -> Int {
    guard let x = DateField.parse(a), let y = DateField.parse(b) else { return 0 }
    return Int((x.timeIntervalSince(y) / 86400).rounded())
}
private func ocvStatus(_ st: String) -> (String, String, String) {
    switch st {
    case "late": return (t("Overdue", "Imechelewa"), "bad", "alert")
    case "today": return (t("Today", "Leo"), "warn", "cal")
    case "done": return (t("Done", "Imefanyika"), "ok", "check")
    default: return (t("Planned", "Imepangwa"), "has", "cal")
    }
}
/// "19 Oct", "Today", "Tomorrow".
func ocvDayText(_ d: String, _ today: String) -> String {
    switch ocvDays(d, today) {
    case 0: return t("Today", "Leo"); case 1: return t("Tomorrow", "Kesho"); case -1: return t("Yesterday", "Jana")
    default: return Dates.day(d)
    }
}
/// "3 days late", "In 4 days".
func ocvRelText(_ date: String, _ today: String) -> String {
    let n = ocvDays(date, today)
    if n < -1 { return t("\(-n) days late", "Imechelewa siku \(-n)") }
    if n == -1 { return t("1 day late", "Imechelewa siku 1") }
    if n == 0 { return t("Today", "Leo") }
    if n == 1 { return t("Tomorrow", "Kesho") }
    return t("In \(n) days", "Baada ya siku \(n)")
}
private func ocvLateShort(_ date: String, _ today: String) -> String { let n = -ocvDays(date, today); return n == 1 ? t("1 day", "siku 1") : t("\(n) days", "siku \(n)") }

/// Office › Visits: the assigned visits as cards with filters (status and person); the assign form and each visit's
/// actions (reassign, cancel, open the project) live in sheets.
struct OcvVisits: View {
    @EnvironmentObject var m: AppModel
    let s: Snapshot
    @State private var team: [String] = []
    @State private var items: [JSON]?
    @State private var err: String?
    @State private var tick = 0
    @State private var vf = "open"
    @State private var vp = ""
    @State private var shown = 30
    @State private var picked: JSON?
    @State private var assigning = false
    @State private var assignTo = ""
    var body: some View {
        let today = When.localDay()
        VStack(alignment: .leading, spacing: 0) {
            if let all = items {
                let inF = { (a: JSON, f: String) -> Bool in let st = ocvState(a, today); return f == "open" ? st != "done" : st == f }
                let byP = { (a: JSON) -> Bool in vp.isEmpty || a.str("to") == vp }
                let fs = [("open", t("Planned", "Zimepangwa")), ("today", t("Today", "Leo")), ("late", t("Overdue", "Zimechelewa")), ("done", t("Done", "Zimefanyika"))]
                let cnt = Dictionary(uniqueKeysWithValues: fs.map { k, _ in (k, all.filter { inF($0, k) && byP($0) }.count) })
                let list = all.filter { inF($0, vf) && byP($0) }.sorted { vf == "done" ? $0.str("done") > $1.str("done") : ($0.str("date"), $0.str("at")) < ($1.str("date"), $1.str("at")) }
                OcvHead(icon: "cal", hue: "blue", title: t("Visits", "Ziara"), line: t("Site visits given to the field team. Tap one to change it.", "Ziara walizopewa timu ya uwanjani. Gusa moja kuibadilisha.")) {
                    HStack(spacing: 8) {
                        OcvNum(value: "\(cnt["open"] ?? 0)", label: t("planned", "zimepangwa"), on: vf == "open") { vf = "open"; shown = 30 }
                        OcvNum(value: "\(cnt["today"] ?? 0)", label: t("today", "leo"), color: (cnt["today"] ?? 0) > 0 ? C.warn : C.ink, on: vf == "today") { vf = "today"; shown = 30 }
                        OcvNum(value: "\(cnt["late"] ?? 0)", label: t("overdue", "zimechelewa"), color: (cnt["late"] ?? 0) > 0 ? C.bad : C.ink, on: vf == "late") { vf = "late"; shown = 30 }
                    }.fixedSize(horizontal: false, vertical: true).padding(.top, 12)
                    Btn(t("Assign a visit", "Gawa ziara"), icon: "plus", wide: true) { assigning = true }.padding(.top, 12)
                }
                if !all.isEmpty {
                    OcvChips(items: fs.map { k, l in (l, "\(cnt[k] ?? 0)", vf == k) }) { vf = fs[$0].0; shown = 30 }
                    let ppl: [String: Int] = { var d = Dictionary(grouping: all.filter { inF($0, vf) }, by: { $0.str("to") }).mapValues(\.count); if !vp.isEmpty && d[vp] == nil { d[vp] = 0 }; return d }()
                    let who = ppl.keys.sorted { (ppl[$0] ?? 0) != (ppl[$1] ?? 0) ? (ppl[$0] ?? 0) > (ppl[$1] ?? 0) : $0 < $1 }
                    if who.count > 1 || !vp.isEmpty {
                        OcvChips(items: [(t("Everyone", "Wote"), "\(all.filter { inF($0, vf) }.count)", vp.isEmpty)] + who.map { ($0, "\(ppl[$0] ?? 0)", vp == $0) }) { i in vp = i == 0 ? "" : who[i - 1]; shown = 30 }
                    }
                }
                if all.isEmpty { EmptyDrop(text: t("No visits assigned yet. Tap “Assign a visit” to give one to the field team.", "Bado hakuna ziara zilizogawiwa. Gusa “Gawa ziara” kumpa mtu wa timu.")) }
                else if list.isEmpty {
                    EmptyDrop(text: !vp.isEmpty ? t("Nothing here for \(vp). Try another filter.", "Hakuna kitu hapa kwa \(vp). Jaribu kichujio kingine.")
                              : vf == "today" ? t("Nothing planned for today.", "Hakuna kilichopangwa leo.") : vf == "late" ? t("Nothing overdue. Well done!", "Hakuna iliyochelewa. Hongera!")
                              : vf == "done" ? t("No visits done in the last 4 months.", "Hakuna ziara zilizofanyika katika miezi 4 iliyopita.") : t("No visits planned. Tap “Assign a visit” to give one to the field team.", "Hakuna ziara zilizopangwa. Gusa “Gawa ziara” kumpa mtu wa timu."))
                }
                ForEach(Array(list.prefix(shown).enumerated()), id: \.offset) { i, a in
                    let st = ocvState(a, today), (label, tone, _) = ocvStatus(st)
                    let to = a.str("to"), note = a.str("note")
                    let right = st == "done" ? smsAgo(a.str("done")) : ocvDayText(a.str("date"), today)
                    let rel = st == "done" ? "" : ocvRelText(a.str("date"), today)
                    OcvCard(i: i, lead: { OcvAvatar(name: to).onTapGesture { Contact.show(to) } },
                            title: a.str("t").isEmpty ? (s.projects.first { $0.id == a.str("id") }?.title ?? a.str("id")) : a.str("t"), right: right, rightStrong: false, pill: (label, tone),
                            lines: [to + (!rel.isEmpty && rel != right ? " · \(rel)" : ""), note.isEmpty ? "" : "“\(note)”"], italicLast: true) { picked = a }
                }
                if list.count > shown { OcvMore(n: list.count - shown) { shown += 30 } }
            } else { Text(err ?? t("Loading…", "Inapakia…")).foregroundColor(err != nil ? C.bad : C.muted) }
        }
        .task { let r = await m.api.adminGet("team"); team = r.json.objs("team").map { $0.str("name") }.filter { !$0.isEmpty }.sorted() }
        .task(id: tick) { let r = await m.api.adminGet("assign"); if r.ok { items = r.json.objs("items"); err = nil } else { err = reasonText(r.reason) } }
        .onAppear { if let a = TeamGo.assign { assignTo = a; TeamGo.assign = nil; assigning = true } }
        .sheet(item: Binding(get: { picked.map { JBox(j: $0) } }, set: { picked = $0?.j })) { b in
            OcvVisitSheet(s: s, a: b.j, team: team, today: today, openIt: { id in picked = nil; m.go(Place(tab: "office", project: id)) }) { tick += 1 }.environmentObject(m)
        }
        .sheet(isPresented: $assigning, onDismiss: { assignTo = "" }) { OcvAssignSheet(s: s, team: team, pre: assignTo) { vf = "open"; vp = ""; tick += 1 }.environmentObject(m) }
    }
}
struct JBox: Identifiable { let j: JSON; var id: String { j.str("aid") + j.str("rid") + j.str("id") + j.str("at") } }

/// One assigned visit: the details, and reassign, cancel or open the project.
private struct OcvVisitSheet: View {
    @EnvironmentObject var m: AppModel
    let s: Snapshot; let a: JSON; let team: [String]; let today: String; let openIt: (String) -> Void; let changed: () -> Void
    @State private var re = false
    @State private var ask = false
    @State private var to = ""
    @State private var date = ""
    @State private var msg: (String, String)?
    @State private var busy = false
    var body: some View {
        let st = ocvState(a, today), (label, tone, icon) = ocvStatus(st), (fg, bg) = ocvTone(tone)
        let id = a.str("id"), p = s.projects.first { $0.id == id }
        let title = a.str("t").isEmpty ? (p?.title ?? id) : a.str("t")
        OcvSheet { close in
            OcvSheetHead(lead: { IconTile(icon: icon, tint: fg, bg: bg, size: 52) },
                         label: label + (st == "late" ? " · " + ocvLateShort(a.str("date"), today) : st == "soon" ? " · " + ocvRelText(a.str("date"), today) : ""), labelColor: fg, title: title, close: close)
            VStack(spacing: 0) {
                PersonFact(label: t("Field officer", "Afisa wa uwanjani"), name: a.str("to"))
                OcvFact(label: t("Date", "Tarehe"), value: DateField.parse(a.str("date")).map { d in let f = DateFormatter(); f.locale = Locale(identifier: Lang.sw ? "sw" : "en_GB"); f.dateFormat = "EEEE d MMMM yyyy"; return f.string(from: d) } ?? a.str("date"))
                if !a.str("note").isEmpty { OcvFact(label: t("Note", "Maelezo"), value: a.str("note")) }
                if !a.str("by").isEmpty { PersonFact(label: t("Assigned by", "Aliyegawa"), name: a.str("by"), sub: a.str("at").isEmpty ? "" : Dates.dateTime(a.str("at"))) }
                if st == "done" { OcvFact(label: t("Done", "Imefanyika"), value: Dates.dateTime(a.str("done")), color: C.ok) }
            }.padding(.top, 10)
            if p != nil { Btn(t("Open project", "Fungua mradi"), icon: "chev", wide: true) { close(); openIt(id) }.padding(.top, 16) }
            if st != "done" {
                HStack(spacing: 8) {
                    Btn(t("Reassign", "Mpe mwingine"), icon: "people", light: true, small: true, wide: true) { withAnimation { re.toggle(); ask = false; msg = nil } }
                    Btn(t("Cancel visit", "Ghairi ziara"), icon: "trash", light: true, small: true, wide: true) { withAnimation { ask = true; re = false; msg = nil } }
                }.padding(.top, 8)
                if re {
                    VStack(alignment: .leading, spacing: 0) {
                        let names = (!to.isEmpty && !team.contains(to) ? [to] : []) + team
                        Picker1(label: t("Field officer", "Afisa wa uwanjani"), value: to, options: names.map { ($0, $0) }) { to = $0 }
                        DateField(label: t("Date", "Tarehe"), value: date) { date = $0 }
                        Btn(t("Save the change", "Hifadhi badiliko"), icon: "check", enabled: !busy && !to.isEmpty && !date.isEmpty, wide: true) {
                            if to == a.str("to") && date == a.str("date") { msg = ("warn", t("Nothing changed yet: choose another person or date.", "Hakuna kilichobadilika: chagua mtu au tarehe nyingine.")); return }
                            busy = true; msg = ("", t("Saving…", "Inahifadhi…"))
                            Task {
                                let x = await m.api.admin(["action": "assign", "ids": [id], "to": to, "date": date, "note": a.str("note")])
                                if !x.ok { busy = false; msg = ("bad", reasonText(x.reason)); return }
                                _ = await m.api.admin(["action": "unassign", "aid": a.str("aid")])
                                msg = ("ok", t("Changed ✓ \(to) will see it in their app.", "Imebadilishwa ✓ \(to) ataiona kwenye programu yake.")); changed()
                                try? await Task.sleep(nanoseconds: 800_000_000); close()
                            }
                        }.padding(.top, 14)
                    }.padding(.horizontal, 14).padding(.bottom, 14).background(C.named("slate").bg).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).padding(.top, 12)
                }
                if ask {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(t("Cancel this visit? It comes off \(a.str("to"))’s list.", "Ghairi ziara hii? Itaondolewa kwenye orodha ya \(a.str("to")).")).font(.mont(14, .semibold)).foregroundColor(C.ink)
                        HStack(spacing: 8) {
                            Btn(t("Keep it", "Iache"), light: true, small: true, wide: true) { ask = false }
                            Btn(t("Cancel visit", "Ghairi ziara"), enabled: !busy, small: true, wide: true) {
                                busy = true
                                Task {
                                    let x = await m.api.admin(["action": "unassign", "aid": a.str("aid")])
                                    busy = false
                                    if x.ok { msg = ("ok", t("Cancelled ✓", "Imeghairiwa ✓")); changed(); try? await Task.sleep(nanoseconds: 600_000_000); close() } else { msg = ("bad", reasonText(x.reason)) }
                                }
                            }
                        }
                    }.padding(14).background(C.badBg).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).padding(.top, 12)
                }
            }
            if let msg { Msg(msg.1, msg.0) }
        }
        .onAppear { to = a.str("to"); date = a.str("date") }
    }
}

/// Assign a visit: who, when, a note, and which projects.
private struct OcvAssignSheet: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.dismiss) private var dismiss
    let s: Snapshot; let team: [String]; let pre: String; let done: () -> Void
    @State private var show = "due"
    @State private var ck = ""
    @State private var q = ""
    @State private var picked: [String] = []
    @State private var to = ""
    @State private var date = DateField.text(Date().addingTimeInterval(86400))
    @State private var note = ""
    @State private var msg: (String, String)?
    @State private var busy = false
    var body: some View {
        let bl = C.named("blue")
        let rows = Array(s.projects.filter { p in
            p.gps != nil && (ck.isEmpty || p.ck == ck) && (q.isEmpty || (p.title + " " + p.area).localizedCaseInsensitiveContains(q.trimmingCharacters(in: .whitespaces)))
                && (show == "due" ? ageDays(s, p) >= DUE_DAYS : show == "repairs" ? s.repairs[p.id] != nil : true)
        }.sorted { show == "due" ? ageDays(s, $0) > ageDays(s, $1) : false }.prefix(80))
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    OcvSheetHead(lead: { IconTile(icon: "cal", tint: bl.fg, bg: bl.bg, size: 52) }, label: t("They see it in their app", "Ataiona kwenye programu yake"), labelColor: C.muted, title: t("Assign a visit", "Gawa ziara")) { dismiss() }
                    if team.isEmpty { Msg(t("Add people in the Team tab first.", "Ongeza watu kwenye Timu kwanza."), "warn") }
                    else { Picker1(label: t("Field officer", "Afisa wa uwanjani"), value: to, options: team.map { ($0, $0) }) { to = $0 } }
                    if !to.isEmpty { PersonChip(name: to).padding(.top, 6) }
                    DateField(label: t("Date", "Tarehe"), value: date) { date = $0 }
                    Field(t("Note (optional)", "Maelezo (si lazima)"), $note, placeholder: t("e.g. check the pump and meet the committee", "mf. kagua pampu na ukutane na kamati"))
                    GroupLabel(t("Which projects?", "Miradi ipi?"), top: 18)
                    Picker1(label: t("Show", "Onyesha"), value: show, options: [("due", t("Due a visit", "Zinasubiri ziara")), ("repairs", t("Open repairs", "Matengenezo yaliyo wazi")), ("all", t("All with a location", "Zote zenye mahali"))]) { show = $0 }
                    Picker1(label: t("Country", "Nchi"), value: ck, options: [("", t("All", "Zote"))] + COUNTRIES) { ck = $0 }
                    Field(t("Search projects", "Tafuta miradi"), $q, placeholder: t("Name or area", "Jina au eneo"))
                    ForEach(rows) { p in
                        let on = picked.contains(p.id)
                        Button { if on { picked.removeAll { $0 == p.id } } else { picked.append(p.id) } } label: {
                            HStack(spacing: 8) {
                                Image(systemName: on ? "checkmark.square.fill" : "square").font(.system(size: 22)).foregroundColor(on ? C.blue : C.muted)
                                VStack(alignment: .leading, spacing: 2) { Text(p.title).font(.tSmall).foregroundColor(C.text).multilineTextAlignment(.leading); Text(p.sub).font(.bSmall).foregroundColor(C.muted) }
                                Spacer(minLength: 0)
                            }.padding(8).background(on ? C.tint : C.card).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(on ? C.blue : C.line, lineWidth: 1))
                        }.buttonStyle(.plain).padding(.top, 6)
                    }
                    if rows.isEmpty { Text(t("No projects match.", "Hakuna mradi unaolingana.")).foregroundColor(C.muted).padding(.top, 8) }
                }.padding(.horizontal, 20).padding(.top, 20).padding(.bottom, 12)
            }
            Rectangle().fill(C.line).frame(height: 1)
            VStack(spacing: 0) {
                Btn(picked.isEmpty ? t("Assign", "Gawa") : picked.count == 1 ? t("Assign 1 visit", "Gawa ziara 1") : t("Assign \(picked.count) visits", "Gawa ziara \(picked.count)"), icon: "cal",
                    enabled: !busy && !picked.isEmpty && !to.isEmpty && !date.isEmpty, wide: true) {
                    busy = true; msg = ("", t("Assigning…", "Inagawa…"))
                    Task {
                        let x = await m.api.admin(["action": "assign", "ids": picked, "to": to, "date": date, "note": note])
                        busy = false
                        if !x.ok { msg = ("bad", reasonText(x.reason)); return }
                        msg = ("ok", picked.count == 1 ? t("Assigned ✓ \(to) will see it in their app.", "Imegawiwa ✓ \(to) ataiona kwenye programu yake.") : t("Assigned ✓ \(to) will see them in their app.", "Imegawiwa ✓ \(to) ataziona kwenye programu yake."))
                        Haptic.ok(); picked = []; note = ""; done()
                        try? await Task.sleep(nanoseconds: 900_000_000); dismiss()
                    }
                }
                if let msg { Msg(msg.1, msg.0) }
            }.padding(.horizontal, 20).padding(.top, 12).padding(.bottom, 20)
        }
        .background(C.card.ignoresSafeArea())
        .onAppear { to = pre.isEmpty ? (team.first ?? "") : pre }
    }
}

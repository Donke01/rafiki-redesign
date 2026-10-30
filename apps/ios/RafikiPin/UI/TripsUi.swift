import SwiftUI
import UIKit

// Trips: where they start.
//  - Office › Trips (admins): every field trip with its live progress, the notices from the road, "Plan a trip", and the
//    group trips (volunteers, donors) with "New group trip".
//  - Me › My trips (everyone): their own trips, today's next stop, and "Plan a trip".
// The trip pages open on top of the tab ("trip_new", "trip:<id>", "gtrip:<id>", "gtrip_new", "gview:<id>").

enum TripNav {
    @MainActor static func go(_ m: AppModel, _ sub: String) { m.go(Place(tab: m.here.tab, sub: sub)) }
}

/// Planned, on the road, done… as a coloured tag.
struct TripStatusTag: View {
    let status: String
    var body: some View {
        let x: (String, Hue) = {
            switch status {
            case "active": return (t("On the road", "Safarini"), ChatHues.named("green"))
            case "done": return (t("Done", "Imekamilika"), ChatHues.named("slate"))
            case "cancelled": return (t("Cancelled", "Imeghairiwa"), ChatHues.bad)
            case "draft": return (t("Draft", "Rasimu"), ChatHues.named("amber"))
            default: return (t("Planned", "Imepangwa"), ChatHues.named("blue"))
            }
        }()
        Tag(x.0, bg: x.1.bg, fg: x.1.fg)
    }
}

/// A small chip with an icon: "62 km tarmac", "Rain 25 mm", "~1 h 52".
struct TChip: View {
    let text: String; var icon: String? = nil; var hue: Hue = ChatHues.named("slate")
    var body: some View {
        HStack(spacing: 4) {
            if let icon { CIcon(icon, size: 13, color: hue.fg) }
            Text(text).font(.mont(12, .bold)).foregroundColor(hue.fg).lineLimit(1)
        }.padding(.horizontal, 8).padding(.vertical, 3).background(hue.bg).clipShape(Capsule())
    }
}

func tripRiskHue(_ level: String) -> Hue {
    switch level { case "difficult": return ChatHues.bad; case "caution": return ChatHues.warn; case "info": return ChatHues.named("slate"); default: return ChatHues.ok }
}
func tripRiskName(_ level: String) -> String {
    switch level { case "difficult": return t("Difficult", "Ngumu"); case "caution": return t("Take care", "Tahadhari"); default: return t("Good roads", "Barabara nzuri") }
}

/// A short title over a section, in capitals (with a count).
struct TripHead: View {
    let text: String; var extra = ""
    var body: some View {
        HStack(spacing: 8) {
            Text(text.uppercased()).font(.mont(12, .heavy)).tracking(1.1).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true)
            if !extra.isEmpty { Text(extra).font(.mont(11, .heavy)).foregroundColor(C.blue2).padding(.horizontal, 8).padding(.vertical, 2).background(C.tint).clipShape(Capsule()) }
        }.padding(.top, 20).padding(.bottom, 4).padding(.leading, 4)
    }
}

/// Tabs across the width whose labels shrink to fit (never cut mid-word).
struct TripTabs: View {
    let items: [(String, String)]; let sel: String; let pick: (String) -> Void
    var body: some View {
        HStack(spacing: 0) {
            ForEach(items, id: \.0) { k, l in
                let on = k == sel
                Button { Haptic.tap(); pick(k) } label: {
                    FitWords(l, size: 13, weight: .bold, color: on ? C.ink : C.muted, lines: 1, align: .center)
                        .padding(.horizontal, 4).frame(maxWidth: .infinity, minHeight: 40)
                        .background(Capsule().fill(on ? C.card : Color.clear).shadow(color: .black.opacity(on ? 0.12 : 0), radius: 2, y: 1))
                }.buttonStyle(.plain).accessibilityAddTraits(on ? .isSelected : [])
            }
        }.padding(4).background(Capsule().fill(C.dark ? Color(hex: 0x22303A) : Color(hex: 0xE8EDF1)))
    }
}

/// A row of choices that wrap onto more lines (never a word broken in the middle).
struct TripChoices: View {
    let opts: [(String, String)]; let sel: String; let pick: (String) -> Void
    var body: some View {
        TripFlow(spacing: 8) {
            ForEach(opts, id: \.0) { k, l in
                let on = k == sel
                Button { Haptic.tap(); pick(k) } label: {
                    Text(l).font(.mont(14, .bold)).foregroundColor(on ? .white : C.ink).lineLimit(1).fixedSize()
                        .padding(.horizontal, 14).frame(minHeight: 40).background(on ? C.blue : C.card).clipShape(Capsule())
                        .overlay(Capsule().stroke(on ? C.blue : C.line, lineWidth: 1))
                }.buttonStyle(.plain).accessibilityAddTraits(on ? .isSelected : [])
            }
        }.padding(.top, 8)
    }
}

/// Lays its children out left to right, wrapping onto new lines.
struct TripFlow: Layout {
    var spacing: CGFloat = 8
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxW = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, rowH: CGFloat = 0, widest: CGFloat = 0
        for s in subviews {
            let sz = s.sizeThatFits(ProposedViewSize(width: maxW, height: nil))
            let w = min(sz.width, maxW)
            if x > 0 && x + w > maxW { y += rowH + spacing; x = 0; rowH = 0 }
            x += w + spacing; rowH = max(rowH, sz.height); widest = max(widest, x - spacing)
        }
        return CGSize(width: maxW.isFinite ? maxW : widest, height: y + rowH)
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, rowH: CGFloat = 0
        for s in subviews {
            let sz = s.sizeThatFits(ProposedViewSize(width: bounds.width, height: nil))
            let w = min(sz.width, bounds.width)
            if x > bounds.minX && x + w > bounds.maxX { y += rowH + spacing; x = bounds.minX; rowH = 0 }
            s.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(width: w, height: sz.height))
            x += w + spacing; rowH = max(rowH, sz.height)
        }
    }
}

/// A coloured banner line (offline, waiting to send, read-only).
struct TripBanner: View {
    let icon: String; let text: String; let hue: Hue
    var body: some View {
        HStack(spacing: 8) {
            Icon(icon, size: 18, color: hue.fg)
            Text(text).font(.mont(13, .semibold)).foregroundColor(hue.fg).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }.padding(.horizontal, 12).padding(.vertical, 10).background(hue.bg).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).padding(.top, 10)
    }
}

/// A person's first letters in a small circle (people on a trip).
typealias TripInitials = ChatPersonAvatar

/// One trip in a list: title, dates and who, its status, and done / late / skipped while on the road.
struct TripRowView: View {
    let row: JSON; let kept: TripItin?; let open: () -> Void
    var body: some View {
        let st = chatS(row, "status")
        let hu = ChatHues.named(st == "active" ? "green" : "blue")
        let people = chatStrings(row["people"])
        Button { Haptic.tap(); open() } label: {
            HStack(spacing: 12) {
                IconTile(icon: "nav", tint: hu.fg, bg: hu.bg, size: 44)
                VStack(alignment: .leading, spacing: 3) {
                    Text(chatS(row, "title").isEmpty ? t("Trip", "Safari") : chatS(row, "title")).font(.tMedium).foregroundColor(C.ink).lineLimit(2).multilineTextAlignment(.leading)
                    Text(TripDates.range(chatS(row, "from"), chatS(row, "to")) + (people.isEmpty ? "" : " · " + people.joined(separator: ", "))).font(.bSmall).foregroundColor(C.muted).lineLimit(2)
                    HStack(spacing: 6) {
                        TripStatusTag(status: st)
                        Text(progress(st)).font(.mont(11, .medium)).foregroundColor(C.muted).lineLimit(1)
                    }.padding(.top, 3)
                }
                Spacer(minLength: 4)
                Icon("chev", size: 18, color: C.muted)
            }.padding(14).background(C.card).clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(C.line, lineWidth: ChatLook.sun ? 2 : 1))
            .contentShape(Rectangle())
        }.buttonStyle(Press()).padding(.top, 10)
    }
    private func progress(_ st: String) -> String {
        if let k = kept, st == "active" || st == "done" {
            let (d, l, s) = TripOnDay.counts(k)
            return t("\(d) of \(k.stops.count) done", "\(d) kati ya \(k.stops.count) zimekamilika") + (l > 0 ? t(" · \(l) late", " · \(l) zimechelewa") : "") + (s > 0 ? t(" · \(s) skipped", " · \(s) zimerukwa") : "")
        }
        if let a = row["projects"] as? [Any] { return a.count == 1 ? t("1 stop", "kituo 1") : t("\(a.count) stops", "vituo \(a.count)") }
        return ""
    }
}

/// Me › My trips: today's next stop up top, then their trips and "Plan a trip".
struct MyTrips: View {
    @EnvironmentObject var m: AppModel
    var body: some View { MyTripsCard(repo: m.trips) }
}
struct MyTripsCard: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var repo: TripRepo
    private func mineRows() -> [JSON] {
        let me = m.prefs.name
        let gone: Set<String> = ["done", "cancelled"]
        let mine: [JSON] = repo.list.filter { r in
            let st = chatS(r, "status")
            return st.isEmpty || (!gone.contains(st) && (chatSame(chatS(r, "owner"), me) || chatStrings(r["people"]).contains { chatSame($0, me) }))
        }
        if !mine.isEmpty || !repo.list.isEmpty { return mine }
        return repo.kept().filter { $0.isOn(me) && !gone.contains($0.status) }.map { ["id": $0.id, "title": $0.title, "from": $0.from, "to": $0.to, "status": $0.status] as JSON }
    }
    var body: some View {
        let today = TripOnDay.today()
        let mine = mineRows()
        let on: TripItin? = mine.lazy.compactMap { repo.cached(chatS($0, "id")) }.first { $0.from <= today && today <= $0.to }
        CardV {
            HStack(spacing: 10) {
                H2(t("My trips", "Safari zangu"))
                if !mine.isEmpty { Tag("\(mine.count)", bg: C.tint, fg: C.deep) }
            }
            if let on { TripNextStop(it: on, compact: true, open: { TripNav.go(m, "trip:" + on.id) }) }
            if mine.isEmpty {
                Text(t("Plan the sites you'll visit: the app puts them in order, times each day and checks the roads and weather.", "Panga maeneo utakayotembelea: programu inayapanga kwa mpangilio, inaweka muda kila siku na inaangalia barabara na hali ya hewa."))
                    .font(.bMedium).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true).padding(.top, 4)
            }
            ForEach(Array(mine.prefix(4).enumerated()), id: \.offset) { _, r in TripRowView(row: r, kept: repo.cached(chatS(r, "id"))) { TripNav.go(m, "trip:" + chatS(r, "id")) } }
            Btn(t("Plan a trip", "Panga safari"), icon: "plus", light: !mine.isEmpty, small: true, wide: true) { TripNav.go(m, "trip_new") }.padding(.top, 12)
        }
        .padding(.top, 14)
        .task { await repo.loadList("?mine=1") }
    }
}

/// The "Next stop" card: where, when, Directions and Check in. `compact` in Me.
struct TripNextStop: View {
    let it: TripItin; var compact = false; var checkIn: ((String) -> Void)? = nil; var skip: ((String) -> Void)? = nil; var open: (() -> Void)? = nil
    var body: some View {
        let today = TripOnDay.today()
        let sid = TripOnDay.next(it, today)
        let d = TripOnDay.dayIndex(it, today)
        let s = sid.flatMap { it.stop($0) }
        VStack(alignment: .leading, spacing: 4) {
            Text(t("NEXT STOP", "KITUO KIJACHO")).font(.mont(11, .heavy)).tracking(1.4).foregroundColor(ChatLook.ice)
            if let s, d >= 0 {
                Text(s.name).font(.tLarge).foregroundColor(.white).lineLimit(2)
                Text(line(d, s.sid)).font(.bMedium).foregroundColor(.white.opacity(0.85)).fixedSize(horizontal: false, vertical: true)
                if !compact {
                    HStack(spacing: 8) {
                        white(t("Directions", "Maelekezo"), "nav", strong: false) { if let ll = s.ll { Share.open("http://maps.apple.com/?daddr=\(ll.lat),\(ll.lng)") } }
                        white(t("Check in", "Ingia"), "check", strong: true) { checkIn?(s.sid) }
                    }.padding(.top, 8)
                    if let skip {
                        Button { skip(s.sid) } label: { Text(t("Skip this stop", "Ruka kituo hiki")).font(.lLarge).foregroundColor(.white).frame(maxWidth: .infinity, minHeight: 44) }.buttonStyle(.plain).padding(.top, 4)
                    }
                }
            } else {
                Text(d < 0 ? t("No stops today", "Hakuna vituo leo") : t("All of today's stops are done", "Vituo vyote vya leo vimekamilika")).font(.tLarge).foregroundColor(.white)
            }
        }
        .padding(16).frame(maxWidth: .infinity, alignment: .leading).background(DeepBrush).clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .contentShape(Rectangle()).onTapGesture { open?() }
        .padding(.top, 12)
    }
    private func line(_ d: Int, _ sid: String) -> String {
        let now = TripOnDay.nowMin()
        let plan = TripTimeline.day(it, d, now: now)
        let row = plan.rows.first { $0.kind == "stop" && $0.sid == sid }
        let drive = plan.rows.first { $0.kind == "drive" && $0.to == sid }?.leg
        let late = TripOnDay.lateBy(it, TripOnDay.today(), now)
        let parts = [row.map { t("Arrive about ", "Fika takriban ") + TripHm.text($0.at) }, drive.map { "~" + TripHm.dur($0.min) }].compactMap { $0 }
        return parts.joined(separator: " · ") + (late >= 15 ? t(" · \(late) min behind", " · nyuma dakika \(late)") : "")
    }
    private func white(_ label: String, _ icon: String, strong: Bool, _ go: @escaping () -> Void) -> some View {
        Button { Haptic.tap(); go() } label: {
            HStack(spacing: 6) {
                Icon(icon, size: 18, color: strong ? C.blue : .white)
                Text(label).font(.mont(15, .heavy)).foregroundColor(strong ? Color(hex: 0x0A78C2) : .white).lineLimit(1).minimumScaleFactor(0.8)
            }.frame(maxWidth: .infinity, minHeight: 48).background(strong ? Color.white : Color.white.opacity(0.16)).clipShape(Capsule())
        }.buttonStyle(Press())
    }
}

/// Office › Trips: field trips (with live progress and the notices from the road) and group trips.
struct TripsSection: View {
    @EnvironmentObject var m: AppModel
    var body: some View { TripsSectionBody(repo: m.trips) }
}
struct TripsSectionBody: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var repo: TripRepo
    @State private var pane = "field"
    @State private var alerts: [JSON] = []
    @State private var loading = true
    @State private var filter = "now"
    @State private var groups: [JSON]?
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack { Spacer(); SmallSeg(items: [("field", t("Field trips", "Safari za kazi")), ("group", t("Group trips", "Safari za vikundi"))], sel: pane) { pane = $0 }; Spacer() }
            if pane == "group" { groupList } else { fieldList }
        }
        .task { await repo.loadList(); loading = false; alerts = await repo.alerts() }
        .onAppear { if m.demo, Demo.arg("-pane") == "group" { pane = "group" } }
    }
    private var fieldList: some View {
        let open: Set<String> = ["planned", "active", "draft", ""], closed: Set<String> = ["done", "cancelled"]
        let rows = repo.list.filter { r in let st = chatS(r, "status"); return filter == "now" ? open.contains(st) : filter == "done" ? closed.contains(st) : true }
        return VStack(alignment: .leading, spacing: 0) {
            Btn(t("Plan a trip", "Panga safari"), icon: "plus", wide: true) { TripNav.go(m, "trip_new") }.padding(.top, 14)
            if !alerts.isEmpty {
                TripHead(text: t("From the road", "Kutoka safarini"), extra: "\(alerts.count)")
                ForEach(Array(alerts.prefix(5).enumerated()), id: \.offset) { _, a in alertRow(a) }
            }
            TripChoices(opts: [("now", t("Planned and on the road", "Zilizopangwa na safarini")), ("done", t("Done", "Zilizokamilika")), ("all", t("All", "Zote"))], sel: filter) { filter = $0 }.padding(.top, 6)
            if rows.isEmpty {
                Text(loading ? t("Loading…", "Inapakia…") : t("No trips here yet. Plan one above.", "Bado hakuna safari hapa. Panga moja hapo juu.")).font(.bMedium).foregroundColor(C.muted).frame(maxWidth: .infinity).padding(.vertical, 24)
            }
            ForEach(Array(rows.enumerated()), id: \.offset) { _, r in TripRowView(row: r, kept: repo.cached(chatS(r, "id"))) { TripNav.go(m, "trip:" + chatS(r, "id")) } }
        }
    }
    private func alertRow(_ a: JSON) -> some View {
        let hu = chatS(a, "kind") == "late" ? ChatHues.warn : ChatHues.named("blue")
        let words = tripWords(a)
        return Button { TripNav.go(m, "trip:" + chatS(a, "id")) } label: {
            HStack(spacing: 10) {
                Icon(chatS(a, "kind") == "skip" ? "x" : "clock", size: 20, color: hu.fg)
                VStack(alignment: .leading, spacing: 2) {
                    Text(words.isEmpty ? chatS(a, "title") : words).font(.mont(14, .semibold)).foregroundColor(C.ink).fixedSize(horizontal: false, vertical: true).multilineTextAlignment(.leading)
                    Text([chatS(a, "title"), chatS(a, "by"), When.text(chatS(a, "at"))].filter { !$0.isEmpty }.joined(separator: " · ")).font(.bSmall).foregroundColor(C.muted).lineLimit(1)
                }
                Spacer(minLength: 0)
            }.padding(12).background(hu.bg).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).padding(.top, 6)
        }.buttonStyle(.plain)
    }
    private var groupList: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(t("Volunteer and donor trips: the day-by-day plan, the group, costs, and a page to share with them.", "Safari za wanaojitolea na wafadhili: mpango wa kila siku, kikundi, gharama, na ukurasa wa kuwashirikisha."))
                .font(.bMedium).foregroundColor(C.muted).multilineTextAlignment(.center).frame(maxWidth: .infinity).fixedSize(horizontal: false, vertical: true).padding(.top, 10)
            Btn(t("New group trip", "Safari mpya ya kikundi"), icon: "plus", wide: true) { TripNav.go(m, "gtrip_new") }.padding(.top, 12)
            if let l = groups {
                if l.isEmpty { Text(t("No group trips yet.", "Bado hakuna safari za vikundi.")).font(.bMedium).foregroundColor(C.muted).frame(maxWidth: .infinity).padding(.vertical, 24) }
                ForEach(Array(l.enumerated()), id: \.offset) { _, g in groupRow(g) }
            } else { Text(t("Loading…", "Inapakia…")).font(.bMedium).foregroundColor(C.muted).padding(.top, 12) }
        }
        .task { groups = await repo.groups() ?? [] }
    }
    private func groupRow(_ g: JSON) -> some View {
        let hu = ChatHues.named("purple")
        let n = g.int("participants")
        let country = countryName(chatS(g, "country")) ?? chatS(g, "country").uppercased()
        return Button { TripNav.go(m, "gtrip:" + chatS(g, "id")) } label: {
            HStack(spacing: 12) {
                IconTile(icon: "people", tint: hu.fg, bg: hu.bg, size: 44)
                VStack(alignment: .leading, spacing: 3) {
                    Text(chatS(g, "name")).font(.tMedium).foregroundColor(C.ink).lineLimit(2).multilineTextAlignment(.leading)
                    Text(TripDates.range(chatS(g, "from"), chatS(g, "to")) + " · " + country + " · " + (n == 1 ? t("1 person", "mtu 1") : t("\(n) people", "watu \(n)"))).font(.bSmall).foregroundColor(C.muted).lineLimit(2)
                    if g.bool("public") { Tag(t("On the website", "Kwenye tovuti"), bg: C.okBg, fg: C.ok).padding(.top, 3) }
                }
                Spacer(minLength: 4)
                Icon("chev", size: 18, color: C.muted)
            }.padding(14).background(C.card).clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(C.line, lineWidth: ChatLook.sun ? 2 : 1))
        }.buttonStyle(Press()).padding(.top, 10)
    }
}

/// A trip's bottom sheet: a title with a close cross, then the content, scrolling.
struct TripSheet<Content: View>: View {
    let title: String
    @ViewBuilder var content: () -> Content
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ChatSheetHead(title: title)
                content()
            }.padding(20)
        }
    }
}

/// A date chosen with a calendar, shown as "Wed 28 Oct"; the value stays "2026-10-28".
struct TripDateField: View {
    let label: String; let value: String; let set: (String) -> Void
    @State private var open = false
    @State private var pick = Date()
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.mont(13, .bold)).foregroundColor(C.ink).lineLimit(1).minimumScaleFactor(0.8).padding(.top, 12)
            Button { pick = DateField.parse(value) ?? Date(); open = true } label: {
                HStack {
                    Text(value.isEmpty ? t("Choose a date", "Chagua tarehe") : TripDates.weekday(value)).font(.bLarge).foregroundColor(value.isEmpty ? C.muted : C.text).lineLimit(1).minimumScaleFactor(0.8)
                    Spacer(minLength: 4); Icon("cal", size: 20, color: C.blue)
                }.padding(.horizontal, 14).frame(minHeight: 52).background(C.card).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.edge, lineWidth: 1))
            }.buttonStyle(.plain)
        }
        .sheet(isPresented: $open) {
            VStack(spacing: 12) {
                DatePicker("", selection: $pick, displayedComponents: .date).datePickerStyle(.graphical).labelsHidden()
                    .environment(\.locale, Locale(identifier: Lang.sw ? "sw" : "en_GB"))
                HStack(spacing: 10) {
                    Btn(t("Cancel", "Ghairi"), light: true, small: true) { open = false }
                    Btn(t("OK", "Sawa"), small: true) { set(DateField.text(pick)); open = false }
                }
            }.padding(20).storySheet([.medium, .large])
        }
    }
}

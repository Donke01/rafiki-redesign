import SwiftUI
import UIKit

/// Office › Trips › Group trips (admins): a volunteer or donor expedition. The day-by-day items (flights, pick-ups,
/// lodging, drilling days, school visits, safari, free time, meals), the group (dietary and medical notes for admins only,
/// a passport-expiry reminder, never a passport number), costs per person, and the group page (private link, website).
struct TripGroupView: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var repo: TripRepo
    let id: String; var preview = false
    @State private var g: TripGroup?
    @State private var err: String?
    @State private var msg: String?
    @State private var sec = "plan"
    @State private var item: JSON?
    @State private var newItem = false
    @State private var person: JSON?
    @State private var newPerson = false
    @State private var cost = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                BackLink(label: t("Back", "Rudi")) { m.back() }
                if let x = g { content(x) } else {
                    Text(err ?? t("Loading…", "Inapakia…")).font(.bMedium).foregroundColor(C.muted).multilineTextAlignment(.center).frame(maxWidth: .infinity).padding(24)
                }
                Spacer().frame(height: 24)
            }.padding(16)
        }
        .task(id: id) { let (x, e) = await repo.group(id); g = x; err = e; if m.demo, let s = Demo.arg("-sheet"), s.hasPrefix("tab-") { sec = String(s.dropFirst(4)) } }
        .sheet(isPresented: Binding(get: { item != nil || newItem }, set: { if !$0 { item = nil; newItem = false } })) {
            if let x = g { TripGroupItemSheet(g: x, item: item) { b in change(b) }.environmentObject(m).storySheet([.large]) }
        }
        .sheet(isPresented: Binding(get: { person != nil || newPerson }, set: { if !$0 { person = nil; newPerson = false } })) {
            if let x = g { TripGroupPersonSheet(g: x, p: person) { b in change(b) }.environmentObject(m).storySheet([.large]) }
        }
        .sheet(isPresented: $cost) { TripGroupCostSheet { b in change(b) }.environmentObject(m).storySheet([.large]) }
    }
    private func change(_ body: JSON, then: ((Reply) -> Void)? = nil) {
        guard let cur = g else { return }
        Task {
            let r = await repo.groupChange(cur, body)
            if let o = r.json.obj("trip") { g = TripGroup(raw: o) }
            msg = r.ok ? nil : tripReason(r.reason)
            then?(r)
        }
    }

    @ViewBuilder private func content(_ x: TripGroup) -> some View {
        Text(x.name).font(.hMedium).foregroundColor(C.ink).multilineTextAlignment(.center).frame(maxWidth: .infinity).fixedSize(horizontal: false, vertical: true)
        Text(TripDates.range(x.from, x.to) + " · " + (countryName(x.country) ?? x.country.uppercased()) + (x.destination.isEmpty ? "" : " · " + x.destination)).font(.bMedium).foregroundColor(C.muted).multilineTextAlignment(.center).frame(maxWidth: .infinity)
        Msg(msg, "bad")
        if preview { previewBody(x) } else {
            TripTabs(items: [("plan", t("Days", "Siku")), ("group", t("Group", "Kikundi")), ("money", t("Costs", "Gharama")), ("page", t("Page", "Ukurasa"))], sel: sec) { sec = $0 }.padding(.top, 14)
            switch sec {
            case "group": groupPane(x)
            case "money": moneyPane(x)
            case "page": pagePane(x)
            default: daysPane(x, edit: true)
            }
        }
    }
    @ViewBuilder private func daysPane(_ x: TripGroup, edit: Bool) -> some View {
        let days = x.dayItems
        if days.isEmpty { Text(t("Add the flights, pick-ups, lodging and days of the trip.", "Ongeza ndege, kuchukuliwa, malazi na siku za safari.")).font(.bMedium).foregroundColor(C.muted).frame(maxWidth: .infinity).padding(.vertical, 20) }
        ForEach(Array(days.enumerated()), id: \.offset) { _, day in
            TripHead(text: TripDates.weekday(day.0))
            ForEach(Array(day.1.enumerated()), id: \.offset) { _, i in TripGroupItemRow(i: i) { if edit { item = i } } }
        }
        if edit { Btn(t("Add to the plan", "Ongeza kwenye mpango"), icon: "plus", wide: true) { newItem = true }.padding(.top, 12) }
    }
    @ViewBuilder private func groupPane(_ x: TripGroup) -> some View {
        TripHead(text: t("The group", "Kikundi"), extra: "\(x.participants.count)")
        Text(t("Dietary and medical notes are for admins only and never on the group page. Passport numbers are never kept.", "Maelezo ya chakula na afya ni kwa wasimamizi pekee na hayako kwenye ukurasa wa kikundi. Namba za pasipoti hazihifadhiwi kamwe."))
            .font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true).padding(.horizontal, 4)
        ForEach(Array(x.participants.enumerated()), id: \.offset) { _, p in
            Button { person = p } label: {
                HStack(alignment: .top, spacing: 10) {
                    TripInitials(name: chatS(p, "name"), size: 36)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(chatS(p, "name")).font(.tSmall).foregroundColor(C.ink)
                        Text([TripGroupPersonSheet.role(chatS(p, "role")), chatS(p, "email")].filter { !$0.isEmpty }.joined(separator: " · ")).font(.bSmall).foregroundColor(C.muted).lineLimit(1)
                        TripFlow(spacing: 4) {
                            if !chatS(p, "diet").isEmpty { TChip(text: chatS(p, "diet"), icon: "leaf", hue: ChatHues.named("green")) }
                            if !chatS(p, "medical").isEmpty { TChip(text: t("Medical note · admins only", "Maelezo ya afya · wasimamizi pekee"), icon: "lock", hue: ChatHues.bad) }
                            if p.bool("passportReminder") { TChip(text: t("Passport expires ", "Pasipoti inaisha ") + chatS(p, "passportExpiry"), icon: "clock", hue: ChatHues.named("amber")) }
                        }
                    }
                    Spacer(minLength: 0)
                }.padding(12).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            }.buttonStyle(.plain).padding(.top, 8)
        }
        Btn(t("Add a person", "Ongeza mtu"), icon: "plus", small: true, wide: true) { newPerson = true }.padding(.top, 10)
        TripHead(text: t("Leaders", "Viongozi"))
        Text(x.leaders.isEmpty ? "—" : x.leaders.joined(separator: ", ")).font(.bMedium).foregroundColor(C.text).padding(.horizontal, 4)
        TripHead(text: t("Emergency contacts", "Mawasiliano ya dharura"))
        ForEach(Array(x.emergency.enumerated()), id: \.offset) { _, e in Text([chatS(e, "name"), chatS(e, "role"), chatS(e, "phone")].filter { !$0.isEmpty }.joined(separator: " · ")).font(.bMedium).foregroundColor(C.text).padding(4) }
    }
    @ViewBuilder private func moneyPane(_ x: TripGroup) -> some View {
        TripHead(text: t("Costs", "Gharama"), extra: x.costs.isEmpty ? "" : "\(x.costs.count)")
        ForEach(Array(x.costs.enumerated()), id: \.offset) { _, c in
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(chatS(c, "label")).font(.tSmall).foregroundColor(C.ink)
                    Text(chatS(c, "per") == "group" ? t("For the group", "Kwa kikundi") : t("Per person", "Kwa kila mtu")).font(.bSmall).foregroundColor(C.muted)
                }
                Spacer(minLength: 6)
                Text(amount(c.num("amount") ?? 0, chatS(c, "currency"))).font(.tSmall).foregroundColor(C.ink).lineLimit(1).fixedSize()
                Button { change(["action": "cost-remove", "cid": chatS(c, "cid")]) } label: { Icon("trash", size: 16, color: C.muted).frame(width: 44, height: 44) }.buttonStyle(.plain).accessibilityLabel(t("Remove", "Ondoa"))
            }.padding(.leading, 12).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).padding(.top, 6)
        }
        Btn(t("Add a cost", "Ongeza gharama"), icon: "plus", small: true, wide: true) { cost = true }.padding(.top, 10)
        if let tot = x.totals {
            TripHead(text: t("Totals", "Jumla"))
            CardV(pad: 14) {
                ForEach(totalLines(tot), id: \.0) { k, v in HStack { Text(k).font(.bMedium).foregroundColor(C.text); Spacer(); Text(amount(v, k)).font(.tSmall).foregroundColor(C.ink) }.padding(.vertical, 4) }
            }
        }
        if !x.payments.isEmpty {
            TripHead(text: t("Payments", "Malipo"), extra: "\(x.payments.count)")
            ForEach(Array(x.payments.enumerated()), id: \.offset) { _, y in
                let who = x.participants.first { chatS($0, "pid") == chatS(y, "pid") }.map { chatS($0, "name") } ?? ""
                HStack { Text([who, chatS(y, "at")].filter { !$0.isEmpty }.joined(separator: " · ")).font(.bSmall).foregroundColor(C.muted); Spacer(); Text(amount(y.num("amount") ?? 0, chatS(y, "currency"))).font(.tSmall).foregroundColor(C.ok) }
                    .padding(12).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).padding(.top, 6)
            }
        }
    }
    private func totalLines(_ o: JSON) -> [(String, Double)] {
        let both = ["USD", "KES"].compactMap { k in o.num(k).map { (k, $0) } }
        if !both.isEmpty { return both }
        return (o.obj("byCurrency") ?? [:]).compactMap { k, v in (v as? NSNumber).map { (k, $0.doubleValue) } }.sorted { $0.0 < $1.0 }
    }
    private func amount(_ v: Double, _ cur: String) -> String { cur + " " + (v.truncatingRemainder(dividingBy: 1) == 0 ? Geo.grouped(Int(v)) : String(format: "%.2f", v)) }
    @ViewBuilder private func pagePane(_ x: TripGroup) -> some View {
        TripHead(text: t("The group page", "Ukurasa wa kikundi"))
        Text(t("A private page for the group: the days, what to pack and what to expect. No medical notes or payments.", "Ukurasa wa faragha wa kikundi: siku, cha kubeba na cha kutarajia. Hakuna maelezo ya afya wala malipo.")).font(.bMedium).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true)
        if !x.shareUrl.isEmpty { Text(x.shareUrl).font(.bSmall).foregroundColor(C.blue2).lineLimit(1).padding(.top, 6) }
        TripFlow(spacing: 8) {
            Btn(t("Share", "Shiriki"), icon: "share", light: true, small: true) { link(x) { Share.items([x.name + "\n" + $0]) } }
            Btn(t("Copy link", "Nakili kiungo"), icon: "link", light: true, small: true) { link(x) { UIPasteboard.general.string = $0; msg = nil } }
            Btn(t("Preview", "Hakiki"), icon: "search", light: true, small: true) { m.go(Place(tab: m.here.tab, sub: "gview:" + x.id)) }
        }.padding(.top, 10)
        if !x.shareUrl.isEmpty {
            Button { change(["action": "unshare"]) } label: { Text(t("Stop sharing the link", "Acha kushiriki kiungo")).font(.lLarge).foregroundColor(C.bad).frame(minHeight: 44) }.buttonStyle(.plain).padding(.top, 6)
        }
        ChatSwitchRow(label: t("Show it on the website", "Ionyeshe kwenye tovuti"), on: x.isPublic) { v in change(["action": "update", "public": v]) }
            .padding(8).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).padding(.top, 12)
    }
    private func link(_ x: TripGroup, _ f: @escaping (String) -> Void) {
        if !x.shareUrl.isEmpty { f(x.shareUrl); return }
        change(["action": "share"]) { r in let u = r.json.str("url"); if !u.isEmpty { f(u) } }
    }
    @ViewBuilder private func previewBody(_ x: TripGroup) -> some View {
        if !x.summaryText.isEmpty { Text(x.summaryText).font(.bLarge).foregroundColor(C.text).fixedSize(horizontal: false, vertical: true).padding(.top, 10) }
        daysPane(x, edit: false)
        if !x.packing.isEmpty { TripHead(text: t("What to pack", "Cha kubeba")); Text(x.packing).font(.bMedium).foregroundColor(C.text).fixedSize(horizontal: false, vertical: true) }
        if !x.expect.isEmpty { TripHead(text: t("What to expect", "Cha kutarajia")); Text(x.expect).font(.bMedium).foregroundColor(C.text).fixedSize(horizontal: false, vertical: true) }
    }
}

/// One item of the day: its time, kind, title, place and details (flight, lodging).
struct TripGroupItemRow: View {
    let i: JSON; let tap: () -> Void
    var body: some View {
        let k = chatS(i, "kind")
        let hu = ChatHues.named(hue(k))
        Button { tap() } label: {
            HStack(alignment: .top, spacing: 10) {
                VStack(alignment: .leading, spacing: 1) {
                    Text(chatS(i, "start")).font(.mont(14, .heavy)).foregroundColor(C.ink).lineLimit(1).fixedSize()
                    Text(chatS(i, "end")).font(.mont(11, .medium)).foregroundColor(C.muted).lineLimit(1).fixedSize()
                }.frame(width: 50, alignment: .leading)
                Image(systemName: TripGroup.kindIcon(k)).font(.system(size: 17, weight: .medium)).foregroundColor(hu.fg).frame(width: 36, height: 36).background(hu.bg).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(chatS(i, "title").isEmpty ? TripGroup.kindName(k) : chatS(i, "title")).font(.tSmall).foregroundColor(C.ink).fixedSize(horizontal: false, vertical: true)
                    if let f = i.obj("flight") {
                        let route = chatS(f, "from").isEmpty && chatS(f, "to").isEmpty ? "" : chatS(f, "from") + "→" + chatS(f, "to")
                        Text([chatS(f, "number"), route].filter { !$0.isEmpty }.joined(separator: " · ")).font(.bSmall).foregroundColor(C.text)
                    }
                    if let l = i.obj("lodging") { Text([chatS(l, "name"), chatS(l, "address")].filter { !$0.isEmpty }.joined(separator: " · ")).font(.bSmall).foregroundColor(C.text) }
                    if let n = i.obj("location").map({ chatS($0, "name") }), !n.isEmpty { Text(n).font(.bSmall).foregroundColor(C.muted) }
                    if !chatS(i, "notes").isEmpty { Text(chatS(i, "notes")).font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true) }
                }
                Spacer(minLength: 0)
            }.padding(12).background(C.card).clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous)).multilineTextAlignment(.leading)
        }.buttonStyle(.plain).padding(.top, 8)
    }
    private func hue(_ k: String) -> String {
        switch k { case "flight", "project": return "blue"; case "transfer": return "teal"; case "lodging", "school": return "purple"; case "safari": return "amber"; case "free": return "green"; case "meal": return "coral"; default: return "slate" }
    }
}

/// Add or change an item of the plan.
struct TripGroupItemSheet: View {
    let g: TripGroup; let item: JSON?; let save: (JSON) -> Void
    @State private var kind = "project"
    @State private var date = ""
    @State private var start = ""
    @State private var end = ""
    @State private var title = ""
    @State private var place = ""
    @State private var notes = ""
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        TripSheet(title: item == nil ? t("Add to the plan", "Ongeza kwenye mpango") : (chatS(item ?? [:], "title").isEmpty ? TripGroup.kindName(kind) : chatS(item ?? [:], "title"))) {
            TripChoices(opts: TripGroup.kinds.map { ($0, TripGroup.kindName($0)) }, sel: kind) { kind = $0 }
            TripDateField(label: t("Date", "Tarehe"), value: date) { date = $0 }
            HStack(alignment: .top, spacing: 10) {
                Field(t("From", "Kuanzia"), $start, placeholder: "09:00")
                Field(t("Until", "Hadi"), $end, placeholder: "12:00")
            }
            Field(t("Title", "Kichwa"), $title, placeholder: TripGroup.kindName(kind))
            Field(t("Place (optional)", "Mahali (si lazima)"), $place)
            Field(t("Notes (optional)", "Maelezo (si lazima)"), $notes, lines: 3)
            Btn(t("Save", "Hifadhi"), icon: "check", enabled: !date.isEmpty, wide: true) {
                var it: JSON = ["date": date, "start": start.isEmpty ? NSNull() as Any : start, "end": end.isEmpty ? NSNull() as Any : end, "kind": kind,
                                "title": title.trimmingCharacters(in: .whitespaces).isEmpty ? TripGroup.kindName(kind) : title.trimmingCharacters(in: .whitespaces), "notes": notes.trimmingCharacters(in: .whitespaces)]
                it["location"] = place.trimmingCharacters(in: .whitespaces).isEmpty ? NSNull() as Any : ["name": place.trimmingCharacters(in: .whitespaces)] as JSON
                var b: JSON = ["action": item == nil ? "item-add" : "item-update", "item": it]
                if let i = item { var x = it; x["iid"] = chatS(i, "iid"); b["item"] = x; b["iid"] = chatS(i, "iid") }
                save(b); dismiss()
            }.padding(.top, 14)
            if let i = item {
                Button { save(["action": "item-remove", "iid": chatS(i, "iid")]); dismiss() } label: { Text(t("Remove from the plan", "Ondoa kwenye mpango")).font(.lLarge).foregroundColor(C.bad).frame(maxWidth: .infinity, minHeight: 44) }.buttonStyle(.plain).padding(.top, 10)
            }
        }
        .onAppear {
            let i = item ?? [:]
            kind = chatS(i, "kind").isEmpty ? "project" : chatS(i, "kind"); date = chatS(i, "date").isEmpty ? g.from : chatS(i, "date")
            start = chatS(i, "start"); end = chatS(i, "end"); title = chatS(i, "title"); place = i.obj("location").map { chatS($0, "name") } ?? ""; notes = chatS(i, "notes")
        }
    }
}

/// Add or change a person in the group.
struct TripGroupPersonSheet: View {
    let g: TripGroup; let p: JSON?; let save: (JSON) -> Void
    @State private var name = ""
    @State private var email = ""
    @State private var phone = ""
    @State private var role = "volunteer"
    @State private var diet = ""
    @State private var med = ""
    @State private var remind = true
    @State private var exp = ""
    @Environment(\.dismiss) private var dismiss
    static func role(_ r: String) -> String { r == "donor" ? t("Donor", "Mfadhili") : r == "leader" ? t("Leader", "Kiongozi") : t("Volunteer", "Mjitolea") }
    var body: some View {
        TripSheet(title: p == nil ? t("Add a person", "Ongeza mtu") : chatS(p ?? [:], "name")) {
            Field(t("Name", "Jina"), $name)
            Field(t("E-mail", "Barua pepe"), $email)
            Field(t("Phone", "Simu"), $phone)
            TripChoices(opts: ["volunteer", "donor", "leader"].map { ($0, TripGroupPersonSheet.role($0)) }, sel: role) { role = $0 }
            Field(t("Dietary needs (admins only)", "Mahitaji ya chakula (wasimamizi pekee)"), $diet, placeholder: t("e.g. vegetarian", "mf. mboga tu"))
            Field(t("Medical notes (admins only)", "Maelezo ya afya (wasimamizi pekee)"), $med, placeholder: t("e.g. asthma", "mf. pumu"))
            Check(label: t("Remind them to check their passport", "Wakumbushe kuangalia pasipoti yao"), on: remind) { remind.toggle() }.padding(.top, 8)
            if remind { Field(t("Passport expiry month (YYYY-MM)", "Mwezi pasipoti inaisha (YYYY-MM)"), $exp, placeholder: "2027-02") }
            Text(t("Never type a passport number here.", "Usiandike namba ya pasipoti hapa kamwe.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 4)
            Btn(t("Save", "Hifadhi"), icon: "check", enabled: !name.trimmingCharacters(in: .whitespaces).isEmpty, wide: true) {
                let e = String(exp.filter { $0.isNumber || $0 == "-" }.prefix(7))
                var pp: JSON = ["name": name.trimmingCharacters(in: .whitespaces), "email": email.trimmingCharacters(in: .whitespaces), "phone": phone.trimmingCharacters(in: .whitespaces), "role": role,
                                "diet": diet.trimmingCharacters(in: .whitespaces), "medical": med.trimmingCharacters(in: .whitespaces), "passportReminder": remind, "passportExpiry": e.count == 7 ? e as Any : NSNull()]
                var b: JSON = ["action": p == nil ? "participant-add" : "participant-update"]
                if let p { pp["pid"] = chatS(p, "pid"); b["pid"] = chatS(p, "pid") }
                b["participant"] = pp
                save(b); dismiss()
            }.padding(.top, 14)
            if let p {
                Button { save(["action": "participant-remove", "pid": chatS(p, "pid")]); dismiss() } label: { Text(t("Remove from the group", "Ondoa kwenye kikundi")).font(.lLarge).foregroundColor(C.bad).frame(maxWidth: .infinity, minHeight: 44) }.buttonStyle(.plain).padding(.top, 10)
            }
        }
        .onAppear {
            let x = p ?? [:]
            name = chatS(x, "name"); email = chatS(x, "email"); phone = chatS(x, "phone"); role = chatS(x, "role").isEmpty ? "volunteer" : chatS(x, "role")
            diet = chatS(x, "diet"); med = chatS(x, "medical"); remind = p == nil ? true : x.bool("passportReminder"); exp = chatS(x, "passportExpiry")
        }
    }
}

/// A cost: per person or for the group, in a currency.
struct TripGroupCostSheet: View {
    let save: (JSON) -> Void
    @State private var label = ""
    @State private var amount = ""
    @State private var cur = "USD"
    @State private var per = "person"
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        TripSheet(title: t("Add a cost", "Ongeza gharama")) {
            Field(t("What", "Nini"), $label, placeholder: t("e.g. Lodging, 10 nights", "mf. Malazi, usiku 10"))
            Field(t("Amount", "Kiasi"), $amount, number: true)
            TripChoices(opts: ["USD", "KES", "GBP", "EUR", "CAD", "AUD", "TZS", "UGX", "MWK"].map { ($0, $0) }, sel: cur) { cur = $0 }
            TripChoices(opts: [("person", t("Per person", "Kwa kila mtu")), ("group", t("For the group", "Kwa kikundi"))], sel: per) { per = $0 }
            Btn(t("Save", "Hifadhi"), icon: "check", enabled: !label.trimmingCharacters(in: .whitespaces).isEmpty && Double(amount.replacingOccurrences(of: ",", with: "")) != nil, wide: true) {
                save(["action": "cost-add", "cost": ["label": label.trimmingCharacters(in: .whitespaces), "amount": Double(amount.replacingOccurrences(of: ",", with: "")) ?? 0, "currency": cur, "per": per] as JSON]); dismiss()
            }.padding(.top, 14)
        }
    }
}

/// A new group trip: name, dates, country, where.
struct TripGroupNewView: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var repo: TripRepo
    @State private var name = ""
    @State private var from = ""
    @State private var to = ""
    @State private var ck = "ke"
    @State private var dest = ""
    @State private var msg: String?
    @State private var busy = false
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                BackLink(label: t("Back", "Rudi")) { m.back() }
                Text(t("New group trip", "Safari mpya ya kikundi")).font(.hMedium).foregroundColor(C.ink).multilineTextAlignment(.center).frame(maxWidth: .infinity)
                Field(t("Name", "Jina"), $name, placeholder: t("e.g. Kenya water trip, July 2027", "mf. Safari ya maji Kenya, Julai 2027"))
                HStack(alignment: .top, spacing: 10) {
                    TripDateField(label: t("Arrive", "Kufika"), value: from) { v in from = v; if to < v { to = v } }
                    TripDateField(label: t("Leave", "Kuondoka"), value: to) { to = $0 }
                }
                Picker1(label: t("Country", "Nchi"), value: ck, options: COUNTRIES) { ck = $0 }
                Field(t("Where (optional)", "Wapi (si lazima)"), $dest, placeholder: t("e.g. Kitale, Trans Nzoia", "mf. Kitale, Trans Nzoia"))
                Btn(t("Create", "Unda"), icon: "check", enabled: !name.trimmingCharacters(in: .whitespaces).isEmpty && !from.isEmpty && !to.isEmpty, wide: true, busy: busy ? t("Creating…", "Inaunda…") : nil) {
                    busy = true
                    Task {
                        let r = await repo.groupCreate(["name": name.trimmingCharacters(in: .whitespaces), "from": from, "to": to, "country": ck, "destination": dest.trimmingCharacters(in: .whitespaces),
                                                        "leaders": [m.prefs.name], "packing": TripGroup.defaultPacking, "expect": TripGroup.defaultExpect])
                        busy = false
                        let id = r.json.obj("trip").map { chatS($0, "id") } ?? ""
                        if r.ok && !id.isEmpty { m.back(); m.go(Place(tab: m.here.tab, sub: "gtrip:" + id)) } else { msg = tripReason(r.reason) }
                    }
                }.padding(.top, 16)
                Msg(msg, "bad")
            }.padding(16)
        }
    }
}

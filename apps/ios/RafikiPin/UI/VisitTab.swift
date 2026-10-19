import SwiftUI

/// Checklist items: English (what monday.com shows) and Kiswahili. Same as the web and Android apps.
let ITEMS: [String: (String, String)] = [
    "handle": ("Pump handle and pivot pins", "Mkono wa pampu na pini zake"), "rods": ("Chain or rods, pump head", "Mnyororo au fimbo, kichwa cha pampu"),
    "spout": ("Spout and water flow", "Mdomo wa bomba na mtiririko wa maji"), "pumpworks": ("Pump works", "Pampu inafanya kazi"),
    "apron": ("Concrete apron or platform (no cracks)", "Sakafu ya saruji (bila nyufa)"), "drain": ("Drainage and soak pit", "Mfereji na shimo la maji machafu"),
    "fence": ("Fence and gate", "Uzio na lango"), "lock": ("Lock and opening hours kept", "Kufuli na saa za kufungua zinafuatwa"), "clean": ("Area clean, no standing water", "Eneo ni safi, hakuna maji yaliyosimama"),
    "power": ("Power supply (mains or generator)", "Umeme (KPLC au jenereta)"), "panels": ("Solar panels clean and undamaged", "Paneli za sola ni safi na nzima"),
    "pumpruns": ("Pump starts and runs smoothly", "Pampu inawaka na kufanya kazi vizuri"), "control": ("Control box and wiring", "Kisanduku cha kudhibiti na nyaya"),
    "tank": ("Storage tank and tower", "Tangi la maji na mnara wake"), "taps": ("Taps and water kiosk", "Mabomba na kioski cha maji"),
    "pipes": ("Pipes and joints (no leaks)", "Mabomba na viungo (hakuna kuvuja)"), "meter": ("Water meter", "Mita ya maji"),
    "gutters": ("Gutters and downpipes", "Mifereji ya paa na mabomba ya kushusha maji"), "firstflush": ("First-flush diverter", "Kifaa cha kuondoa maji ya kwanza"),
    "tankwalls": ("Tank walls (no cracks or leaks)", "Kuta za tangi (bila nyufa wala kuvuja)"), "tap": ("Tap", "Bomba"), "lid": ("Tank cover and inlet screen", "Kifuniko cha tangi na chujio"),
    "base": ("Tank base or stand", "Msingi wa tangi"), "waterpoint": ("Water point works", "Kituo cha maji kinafanya kazi"),
    "handwash": ("Handwashing station (water and soap)", "Kituo cha kunawa mikono (maji na sabuni)"), "latrines": ("Latrines clean and usable", "Vyoo ni safi na vinatumika"),
    "storage": ("Water storage tank", "Tangi la kuhifadhi maji"), "roof": ("Roof and building", "Paa na jengo"),
]

private func kind(_ p: Project) -> String {
    let ty = p.type.lowercased(), pump = p.pump.lowercased()
    if ty.contains("borehole") { return ["manual": "manual", "solar": "solar", "electric": "electric"][pump] ?? "borehole" }
    if ty.contains("collection") { return "tankk" }
    return "building"
}
private let LISTS: [String: [String]] = [
    "manual": ["handle", "rods", "spout", "apron", "drain", "fence", "lock", "clean"],
    "electric": ["power", "pumpruns", "control", "tank", "taps", "pipes", "meter", "fence", "clean"],
    "solar": ["panels", "pumpruns", "control", "tank", "taps", "pipes", "fence", "clean"],
    "borehole": ["pumpworks", "spout", "apron", "drain", "fence", "lock", "clean"],
    "tankk": ["gutters", "firstflush", "tankwalls", "tap", "lid", "base", "clean"],
    "building": ["waterpoint", "handwash", "latrines", "storage", "roof", "clean"],
]

private func slots(_ p: Project) -> [Slot] {
    let b = kind(p) == "building"
    return [
        Slot(label: b ? "The water point" : "Pump or tank, close up", title: b ? t("The water point", "Kituo cha maji") : t("Pump or tank, close up", "Pampu au tangi, kwa karibu"), hint: t("Fill the frame with it", "Ijaze picha nzima")),
        Slot(label: "Water flowing", title: t("Water flowing", "Maji yakitiririka"), hint: t("Water coming out of the spout or tap", "Maji yakitoka kwenye bomba")),
        Slot(label: b ? "Building and people" : "The site with people", title: b ? t("Building and people", "Jengo na watu") : t("The site with people", "Eneo pamoja na watu"), hint: t("Step back: the water point and people using it", "Rudi nyuma: kituo na watu wakikitumia")),
        Slot(label: "Any damage", title: t("Any damage", "Uharibifu wowote"), hint: t("Close up of anything broken", "Karibu na kilichoharibika")),
        Slot(label: "Water test strip", title: t("Water test strip", "Kipimo cha maji"), hint: t("Only if you tested the water", "Ikiwa tu umepima maji")),
    ]
}

/// Everything typed in a visit, kept while moving between steps (and between screens).
final class VisitDraft: ObservableObject {
    @Published var step = 0
    @Published var arrived: String?
    @Published var arrivedAt: LatLng?
    @Published var state = ""
    @Published var urgency = ""
    @Published var check: [String: String] = [:]
    @Published var other = ""; @Published var parts = ""; @Published var cost = ""
    @Published var strokes = ""; @Published var flow = ""; @Published var queue = ""; @Published var users = ""; @Published var clarity = ""
    @Published var ecoli = ""
    @Published var active = ""; @Published var chair = ""; @Published var phone = ""; @Published var fund = ""
    @Published var quote = ""; @Published var quoteBy = ""; @Published var consent = false
    @Published var note = ""
    @Published var shots: [Shot] = []
    @Published var voice: String?
    @Published var sent: String?

    static var drafts: [String: VisitDraft] = [:]
    static func of(_ id: String) -> VisitDraft { if let d = drafts[id] { return d }; let d = VisitDraft(); drafts[id] = d; return d }
}

struct VisitTab: View {
    let p: Project
    var body: some View { VisitForm(p: p, v: VisitDraft.of(p.id)) }
}

private struct VisitForm: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var loc = Locator.shared
    let p: Project
    @ObservedObject var v: VisitDraft
    @State private var msg: (String, String)?
    @State private var busy = false

    var steps: [String] { [t("Arrive", "Fika"), t("Condition", "Hali"), t("Checklist", "Ukaguzi"), t("Readings", "Vipimo"), t("Photos", "Picha"), t("People", "Watu"), t("Send", "Tuma")] }
    var items: [String] { LISTS[kind(p)] ?? [] }

    var body: some View {
        if let sent = v.sent {
            CardV {
                IconTile(icon: "okc", tint: C.ok, bg: C.okBg, size: 64)
                Text(sent == "sent" ? t("Visit report sent", "Ripoti ya ziara imetumwa") : t("Saved on this phone", "Imehifadhiwa kwenye simu")).font(.tLarge).foregroundColor(C.ink).padding(.top, 12)
                Text(sent == "sent" ? t("It’s on the project in monday.com.", "Iko kwenye mradi katika monday.com.") : t("It will be sent to monday.com when you have signal.", "Itatumwa monday.com ukipata mtandao.")).font(.bMedium).foregroundColor(C.muted)
                Btn(t("Start another visit here", "Anza ziara nyingine hapa"), light: true, small: true) { VisitDraft.drafts[p.id] = nil; v.sent = nil; v.step = 0; v.state = ""; v.shots = []; v.check = [:]; v.note = ""; v.voice = nil; v.arrived = nil }.padding(.top, 14)
            }
        } else {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 4) {
                    ForEach(Array(steps.enumerated()), id: \.offset) { i, s in
                        Button { v.step = i } label: {
                            VStack(spacing: 4) {
                                Capsule().fill(i < v.step ? AnyShapeStyle(C.ok) : i == v.step ? AnyShapeStyle(BlueBrush) : AnyShapeStyle(C.line)).frame(height: 5)
                                Text(s).font(.mont(9, .bold)).foregroundColor(i == v.step ? C.blue : C.muted).lineLimit(1).minimumScaleFactor(0.7)
                            }.padding(.vertical, 4).contentShape(Rectangle())
                        }.buttonStyle(.plain)
                    }
                }
                CardV { stepBody }.padding(.top, 12)
                HStack(spacing: 10) {
                    Btn(t("Back", "Rudi"), icon: "back", enabled: v.step > 0, light: true, wide: true) { if v.step > 0 { v.step -= 1 } }
                    if v.step < steps.count - 1 { Btn(t("Next", "Endelea"), enabled: v.step != 1 || !v.state.isEmpty, wide: true) { v.step += 1 } }
                }.padding(.top, 14)
            }
        }
    }

    @ViewBuilder var stepBody: some View {
        switch v.step {
        case 0:
            H2(t("Check in", "Ingia"))
            Text(t("Records the time you arrived and where you are.", "Inarekodi muda ulipofika na mahali ulipo.")).font(.bMedium).foregroundColor(C.muted)
            if let a = v.arrived {
                Text(t("Arrived ", "Umefika ") + When.text(a) + (v.arrivedAt.map { " · " + $0.text() } ?? "")).font(.tSmall).foregroundColor(C.ok).padding(.top, 12)
            } else {
                Btn(t("I’ve arrived", "Nimefika"), icon: "pin", wide: true) { v.arrived = When.now(); v.arrivedAt = loc.fix?.ll; v.step = 1 }.padding(.top, 14)
            }
        case 1:
            H2(t("How is it?", "Iko hali gani?"))
            ForEach(STATES, id: \.self) { k in
                let st: (String, Color, Color) = k == "working" ? ("okc", C.ok, C.okBg) : k == "repair" ? ("alert", Amber, C.warnBg) : ("xc", C.bad, C.badBg)
                Choice(label: stateName(k), icon: st.0, tint: st.1, bg: st.2, on: v.state == k) { v.state = k }
            }
            if !v.state.isEmpty && v.state != "working" {
                Text(t("How urgent?", "Ni haraka kiasi gani?")).font(.tSmall).foregroundColor(C.ink).padding(.top, 14)
                HStack(spacing: 6) { ForEach(URGENCY, id: \.self) { k in Pill(urgencyName(k), on: v.urgency == k) { v.urgency = k } } }.padding(.top, 8)
            }
        case 2:
            H2(t("Checklist", "Ukaguzi"))
            Text(t("Tap OK or Problem for each. Skip anything you couldn’t check.", "Gusa Sawa au Tatizo kwa kila kimoja. Ruka usichoweza kukagua.")).font(.bMedium).foregroundColor(C.muted)
            ForEach(items, id: \.self) { k in
                let cur = v.check[k] ?? ""
                HStack(spacing: 6) {
                    Text(t(ITEMS[k]!.0, ITEMS[k]!.1)).font(.bMedium).foregroundColor(C.text).frame(maxWidth: .infinity, alignment: .leading)
                    Pill(t("OK", "Sawa"), on: cur == "ok", color: C.ok) { v.check[k] = cur == "ok" ? "" : "ok" }.frame(width: 64)
                    Pill(t("Problem", "Tatizo"), on: cur == "bad", color: C.bad) { v.check[k] = cur == "bad" ? "" : "bad" }.frame(width: 84)
                }
                .padding(10).background(cur == "ok" ? C.okBg : cur == "bad" ? C.badBg : C.card)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(cur == "ok" ? C.ok : cur == "bad" ? C.bad : C.line, lineWidth: 1)).padding(.top, 8)
            }
            Field(t("Anything else wrong?", "Kuna kingine kibaya?"), $v.other, placeholder: t("e.g. tap drips", "mf. bomba linavuja"))
            if v.state != "working" && !v.state.isEmpty {
                Field(t("Parts needed", "Vipuri vinavyohitajika"), $v.parts, placeholder: t("e.g. 2 pump rods, handle bolt", "mf. fimbo 2 za pampu, boliti ya mkono"))
                Field(t("Rough cost (KES)", "Gharama ya makadirio (KES)"), $v.cost, number: true)
            }
        case 3:
            H2(t("Readings", "Vipimo"))
            Text(t("All optional.", "Vyote si lazima.")).font(.bMedium).foregroundColor(C.muted)
            HStack(alignment: .bottom, spacing: 10) {
                Field(t("Pump strokes to fill 20 L", "Mipigo ya pampu kujaza lita 20"), $v.strokes, placeholder: "e.g. 35", number: true)
                Field(t("Flow (litres a minute)", "Mtiririko (lita kwa dakika)"), $v.flow, placeholder: "e.g. 12", number: true)
            }
            HStack(alignment: .bottom, spacing: 10) {
                Field(t("People using it a day", "Watu wanaotumia kwa siku"), $v.users, number: true)
                Field(t("Queue (minutes)", "Foleni (dakika)"), $v.queue, number: true)
            }
            Text(t("Water looks", "Maji yanaonekana")).font(.tSmall).foregroundColor(C.ink).padding(.top, 14)
            HStack(spacing: 6) {
                ForEach([("clear", t("Clear", "Safi")), ("cloudy", t("Cloudy", "Mawingu")), ("dirty", t("Dirty", "Machafu"))], id: \.0) { k, l in Pill(l, on: v.clarity == k) { v.clarity = v.clarity == k ? "" : k } }
            }.padding(.top, 8)
            Text(t("E. coli test", "Kipimo cha E. coli")).font(.tSmall).foregroundColor(C.ink).padding(.top, 14)
            HStack(spacing: 6) {
                ForEach([("neg", t("None found", "Haikupatikana")), ("pos", t("Found", "Imepatikana"))], id: \.0) { k, l in Pill(l, on: v.ecoli == k, color: k == "pos" ? C.bad : C.ok) { v.ecoli = v.ecoli == k ? "" : k } }
            }.padding(.top, 8)
        case 4:
            H2(t("Photos", "Picha"))
            Text(t("Tap a box to take that photo or choose one from your photos. Each one is labelled in monday.com.", "Gusa kisanduku kupiga picha hiyo au kuchagua kutoka kwenye picha zako. Kila moja inapewa jina kwenye monday.com.")).font(.bMedium).foregroundColor(C.muted).padding(.bottom, 10)
            PhotoSlots(slots: slots(p), shots: $v.shots)
            VoiceNote(path: $v.voice)
        case 5:
            H2(t("Water committee", "Kamati ya maji"))
            Text(t("Is the committee active?", "Je, kamati iko hai?")).font(.tSmall).foregroundColor(C.ink).padding(.top, 4)
            HStack(spacing: 6) {
                ForEach([("yes", t("Yes", "Ndiyo")), ("no", t("No", "Hapana")), ("unsure", t("Not sure", "Sina uhakika"))], id: \.0) { k, l in Pill(l, on: v.active == k) { v.active = v.active == k ? "" : k } }
            }.padding(.top, 8)
            HStack(alignment: .bottom, spacing: 10) {
                Field(t("Chairperson", "Mwenyekiti"), $v.chair)
                Field(t("Their phone", "Simu yake"), $v.phone)
            }
            Field(t("Maintenance fund now (KES)", "Mfuko wa matengenezo sasa (KES)"), $v.fund, placeholder: "e.g. 3000", number: true)
            H2(t("Story moment (optional)", "Hadithi (si lazima)")).padding(.top, 18)
            Text(t("A few words from someone who uses the water, for the website.", "Maneno machache kutoka kwa mtu anayetumia maji, kwa ajili ya tovuti.")).font(.bMedium).foregroundColor(C.muted)
            Field(t("What they said", "Walichosema"), $v.quote, placeholder: t("e.g. “Now my girls get to school on time.”", "mf. “Sasa wasichana wangu wanafika shuleni kwa wakati.”"), lines: 3)
            Field(t("Who said it", "Aliyesema"), $v.quoteBy, placeholder: t("e.g. Mary, mother of three", "mf. Mary, mama wa watoto watatu"))
            Check(label: t("They agreed to be quoted and photographed for RAFIKI’s website", "Wamekubali kunukuliwa na kupigwa picha kwa tovuti ya RAFIKI"), on: v.consent) { v.consent.toggle() }.padding(.top, 12)
        default:
            H2(t("Check and send", "Hakiki na utume"))
            SumRow(k: t("Condition", "Hali"), v: v.state.isEmpty ? t("Not chosen yet", "Bado haijachaguliwa") : stateName(v.state), bad: v.state.isEmpty)
            SumRow(k: t("Problems", "Matatizo"), v: "\(v.check.values.filter { $0 == "bad" }.count + (v.other.trimmingCharacters(in: .whitespaces).isEmpty ? 0 : 1))")
            SumRow(k: t("Photos", "Picha"), v: "\(v.shots.count)" + (v.voice != nil ? t(" + voice note", " + ujumbe wa sauti") : ""))
            Field(t("Notes", "Maelezo"), $v.note, placeholder: t("Anything else the office should know", "Kitu kingine chochote ofisi inapaswa kujua"), lines: 3)
            Btn(busy ? t("Sending…", "Inatuma…") : t("Send visit report", "Tuma ripoti ya ziara"), icon: "upload", enabled: !v.state.isEmpty && !busy, wide: true) { send() }.padding(.top, 16)
            if case let (tone, text)? = msg { Msg(text, tone) }
        }
    }

    func send() {
        busy = true
        Task {
            func num(_ s: String) -> Double? { Double(s.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: "")) }
            func trim(_ s: String) -> String { s.trimmingCharacters(in: .whitespacesAndNewlines) }
            let bad = items.filter { v.check[$0] == "bad" }.map { ITEMS[$0]!.0 }
            var readings: JSON = [:]
            if let x = num(v.strokes) { readings["strokes"] = x }; if let x = num(v.flow) { readings["flow"] = x }
            if let x = num(v.queue) { readings["queue"] = x }; if let x = num(v.users) { readings["users"] = x }
            if !v.clarity.isEmpty { readings["clarity"] = v.clarity }
            var committee: JSON = ["chair": trim(v.chair), "phone": trim(v.phone)]
            if !v.active.isEmpty { committee["active"] = v.active }; if let f = num(v.fund) { committee["fund"] = f }
            var body: JSON = [
                "action": "visit", "id": p.id, "ck": p.ck, "state": v.state, "urgency": v.state == "working" ? "" : v.urgency,
                "problems": bad, "checked": items.filter { v.check[$0] == "ok" }.map { ITEMS[$0]!.0 },
                "other": trim(v.other), "parts": trim(v.parts), "readings": readings,
                "quality": (v.ecoli.isEmpty ? [:] : ["ecoli": v.ecoli]) as JSON, "committee": committee,
                "story": ["quote": trim(v.quote), "by": trim(v.quoteBy), "consent": v.consent] as JSON, "note": trim(v.note), "leave": When.now(),
            ]
            if let c = num(v.cost) { body["cost"] = c }
            if let a = v.arrived { body["arrive"] = a }
            if let ll = v.arrivedAt ?? loc.fix?.ll { body["lat"] = ll.lat; body["lng"] = ll.lng }
            let files = v.shots.map { OutFile(path: $0.path, kind: "photo", label: $0.label) } + (v.voice.map { [OutFile(path: $0, kind: "voice", label: "Voice note")] } ?? [])
            switch await m.submit(body, files) {
            case .sent(_, let note): m.localVisit(p, state: v.state, note: v.note, urgency: v.urgency, problems: bad); v.sent = "sent"; msg = note.map { ("warn", $0) }
            case .queued: m.localVisit(p, state: v.state, note: v.note, urgency: v.urgency, problems: bad); v.sent = "queued"
            case .failed(let s): msg = ("bad", s)
            case .locked: break
            }
            busy = false
        }
    }
}

private struct SumRow: View {
    let k: String; let v: String; var bad = false
    var body: some View {
        HStack { Text(k).font(.bMedium).foregroundColor(C.text); Spacer(); Text(v).font(.tSmall).foregroundColor(bad ? C.bad : C.ink) }.padding(.vertical, 8)
    }
}

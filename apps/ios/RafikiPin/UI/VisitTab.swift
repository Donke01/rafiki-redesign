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
    // schools
    "sroof": ("Roof, gutters and ceiling", "Paa, mifereji na dari"), "walls": ("Walls and floor (no cracks)", "Kuta na sakafu (bila nyufa)"),
    "doors": ("Doors, windows and locks", "Milango, madirisha na kufuli"), "desks": ("Desks and furniture", "Madawati na samani"),
    "boards": ("Chalkboards and paint", "Ubao na rangi"), "inuse": ("Classrooms in use", "Madarasa yanatumika"),
    // clinics
    "bldg": ("Building, roof and walls", "Jengo, paa na kuta"), "equip": ("Equipment and beds working", "Vifaa na vitanda vinafanya kazi"),
    "supplies": ("Medicines and supplies stocked and in date", "Dawa na vifaa vipo na havijaisha muda"), "washrooms": ("Water and washrooms", "Maji na vyoo"),
    "light": ("Power and lighting", "Umeme na taa"), "waste": ("Incinerator and waste disposal", "Kichomea taka na utupaji wa taka"),
    // children's homes
    "dorm": ("Dormitories", "Mabweni"), "beds": ("Beds, mattresses and bedding", "Vitanda, magodoro na matandiko"),
    "kitchen": ("Kitchen and food store", "Jiko na stoo ya chakula"), "wallgate": ("Wall, fence and gate", "Ukuta, uzio na lango"),
    // farms
    "pen": ("Pen or coop: roof, walls and floor", "Banda: paa, kuta na sakafu"), "animals": ("Animals healthy", "Mifugo ni wazima"),
    "feed": ("Feed and water for the animals", "Chakula na maji ya mifugo"), "drained": ("Clean and drained", "Safi na maji hayatuami"),
    "records": ("Records kept (births, deaths, sales)", "Kumbukumbu zinawekwa (kuzaliwa, vifo, mauzo)"),
    // any other building
    "furniture": ("Equipment and furniture", "Vifaa na samani"), "used": ("Being used as planned", "Inatumika kama ilivyopangwa"),
    // building sites
    "materials": ("Materials on site and stored safely", "Vifaa vya ujenzi vipo na vimehifadhiwa salama"), "workers": ("Workers on site", "Mafundi wako kazini"),
    "plumb": ("Walls straight, no cracks", "Kuta zimenyooka, bila nyufa"), "timber": ("Roof and timber", "Paa na mbao"),
    "fitted": ("Doors and windows fitted", "Milango na madirisha yamewekwa"), "plan": ("Built to the plan and size agreed", "Inajengwa kulingana na mpango na ukubwa uliokubaliwa"),
    "safety": ("Site safe and tidy", "Eneo ni salama na nadhifu"), "schedule": ("On schedule", "Inaenda kwa ratiba"),
]

/// Numbers counted on visits that aren't water.
private let NUM: [String: (String, String)] = [
    "pupils": ("Pupils enrolled", "Wanafunzi waliosajiliwa"), "teachers": ("Teachers", "Walimu"), "patients": ("Patients a week", "Wagonjwa kwa wiki"),
    "staff": ("Staff on duty", "Wafanyakazi kazini"), "beds": ("Beds in use", "Vitanda vinavyotumika"), "children": ("Children living there", "Watoto wanaoishi hapo"),
    "carers": ("Carers and staff", "Walezi na wafanyakazi"), "animals": ("Animals now", "Mifugo sasa"), "born": ("Born or hatched since the last visit", "Waliozaliwa au kuanguliwa tangu ziara iliyopita"),
    "died": ("Died since the last visit", "Waliokufa tangu ziara iliyopita"), "sold": ("Sold since the last visit", "Waliouzwa tangu ziara iliyopita"),
    "income": ("Income from sales since the last visit (KES)", "Mapato ya mauzo tangu ziara iliyopita (KES)"), "users": ("People using it (estimate)", "Watu wanaoitumia (makadirio)"),
    "workers": ("Workers on site today", "Mafundi kazini leo"),
]
private let NUMS: [String: [String]] = ["school": ["pupils", "teachers"], "health": ["patients", "staff", "beds"], "home": ["children", "carers"],
    "farm": ["animals", "born", "died", "sold", "income"], "building": ["users"], "build": ["workers"]]
private let CARER: [String: (String, String)] = ["school": ("Head teacher", "Mwalimu mkuu"), "health": ("Person in charge (e.g. clinical officer)", "Msimamizi (mf. afisa wa kliniki)"),
    "home": ("Home manager", "Msimamizi wa makao"), "farm": ("Farmer", "Mkulima"), "building": ("Person in charge", "Msimamizi"), "build": ("Fundi or site foreman", "Fundi au msimamizi wa ujenzi")]
private let STAGES: [(String, String, String)] = [("foundation", "Foundation", "Msingi"), ("walls", "Walls", "Kuta"), ("roof", "Roof", "Paa"), ("finishing", "Finishing", "Umaliziaji"), ("done", "Finished", "Imekamilika")]
func isWater(_ p: Project) -> Bool { let ty = p.type.lowercased(); return ty.contains("borehole") || ty.contains("collection") }
/// On a building site the condition reads On track / Problems to fix / Work stopped.
private func stateLabel(_ p: Project, _ k: String) -> String {
    guard kind(p) == "build" else { return stateName(k) }
    return k == "working" ? t("On track", "Inaendelea vizuri") : k == "repair" ? t("Problems to fix", "Kuna matatizo ya kurekebisha") : t("Work stopped", "Kazi imesimama")
}

private func kind(_ p: Project) -> String {
    let ty = p.type.lowercased(), pump = p.pump.lowercased()
    if ty.contains("borehole") { return ["manual": "manual", "solar": "solar", "electric": "electric"][pump] ?? "borehole" }
    if ty.contains("collection") { return "tankk" }
    // not water: a building site while under construction, otherwise by type
    if p.status.lowercased().contains("under construction") { return "build" }
    if ty.contains("school") { return "school" }
    if ty.contains("health") { return "health" }
    if ty.contains("orphan") { return "home" }
    if ty.contains("farm") { return "farm" }
    return "building"
}
private let LISTS: [String: [String]] = [
    "manual": ["handle", "rods", "spout", "apron", "drain", "fence", "lock", "clean"],
    "electric": ["power", "pumpruns", "control", "tank", "taps", "pipes", "meter", "fence", "clean"],
    "solar": ["panels", "pumpruns", "control", "tank", "taps", "pipes", "fence", "clean"],
    "borehole": ["pumpworks", "spout", "apron", "drain", "fence", "lock", "clean"],
    "tankk": ["gutters", "firstflush", "tankwalls", "tap", "lid", "base", "clean"],
    "school": ["sroof", "walls", "doors", "desks", "boards", "latrines", "handwash", "inuse"],
    "health": ["bldg", "equip", "supplies", "washrooms", "light", "waste", "fence", "clean"],
    "home": ["dorm", "beds", "bldg", "kitchen", "washrooms", "wallgate", "clean"],
    "farm": ["pen", "animals", "feed", "drained", "records", "fence"],
    "building": ["bldg", "doors", "furniture", "washrooms", "used", "clean"],
    "build": ["materials", "workers", "plumb", "timber", "fitted", "plan", "safety", "schedule"],
]

private func slots(_ p: Project) -> [Slot] {
    func s(_ en: String, _ sw: String, _ hen: String = "", _ hsw: String = "") -> Slot { Slot(label: en, title: t(en, sw), hint: hen.isEmpty ? "" : t(hen, hsw)) }
    let damage = Slot(label: "Any damage", title: t("Any damage", "Uharibifu wowote"), hint: t("Close up of anything broken", "Karibu na kilichoharibika"))
    let out = s("Outside, the whole building", "Nje, jengo lote")
    switch kind(p) {
    case "school": return [out, s("A classroom inside", "Ndani ya darasa"), s("Desks and boards", "Madawati na ubao"), s("Pupils and teachers", "Wanafunzi na walimu", "Only with the school’s permission", "Kwa ruhusa ya shule tu"), damage]
    case "health": return [out, s("Equipment and beds", "Vifaa na vitanda"), s("Medicine and supplies store", "Stoo ya dawa na vifaa"), s("Water and washrooms", "Maji na vyoo"), damage]
    case "home": return [out, s("Dormitory and beds", "Bweni na vitanda"), s("Kitchen", "Jiko"), s("Water and latrines", "Maji na vyoo"), damage]
    case "farm": return [s("The animals", "Mifugo"), s("The pen or coop", "Banda"), s("Feed and water for the animals", "Chakula na maji ya mifugo"), s("The farmer at work", "Mkulima kazini"), damage]
    case "building": return [out, s("Inside", "Ndani"), s("People using it", "Watu wakiitumia"), damage]
    case "build": return [s("The whole building now", "Jengo lote sasa", "Same spot as last time, so progress shows", "Mahali pale pale kama mara iliyopita, ili maendeleo yaonekane"),
        s("Work done since the last visit", "Kazi iliyofanyika tangu ziara iliyopita", "Close up", "Kwa karibu"), s("Materials on site", "Vifaa vya ujenzi"), s("Anything wrong", "Kitu chochote kibaya", "Cracks, leaks, poor work", "Nyufa, kuvuja, kazi mbaya")]
    default: break
    }
    let b = false
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
    @Published var stage = ""
    @Published var nums: [String: String] = [:]
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

    var wet: Bool { isWater(p) }
    var k0: String { kind(p) }
    var steps: [String] { [t("Arrive", "Fika"), t("Condition", "Hali"), t("Checklist", "Ukaguzi"), wet ? t("Readings", "Vipimo") : t("Numbers", "Idadi"), t("Photos", "Picha"), t("People", "Watu"), t("Send", "Tuma")] }
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
                FieldQrCheckedIn(id: p.id)
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
            H2(k0 == "build" ? t("How is the building going?", "Ujenzi unaendeleaje?") : t("How is it?", "Iko hali gani?"))
            ForEach(STATES, id: \.self) { k in
                let st: (String, Color, Color) = k == "working" ? ("okc", C.ok, C.okBg) : k == "repair" ? ("alert", Amber, C.warnBg) : ("xc", C.bad, C.badBg)
                Choice(label: stateLabel(p, k), icon: st.0, tint: st.1, bg: st.2, on: v.state == k) { v.state = k }
            }
            if !v.state.isEmpty && v.state != "working" {
                Text(t("How urgent?", "Ni haraka kiasi gani?")).font(.tSmall).foregroundColor(C.ink).padding(.top, 14)
                HStack(spacing: 6) { ForEach(URGENCY, id: \.self) { k in Pill(urgencyName(k), on: v.urgency == k) { v.urgency = k } } }.padding(.top, 8)
            }
        case 2:
            H2(t("Checklist", "Ukaguzi"))
            if k0 == "build" {
                Text(t("Stage the building has reached", "Hatua ambayo jengo limefikia")).font(.tSmall).foregroundColor(C.ink).padding(.top, 4)
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 3), spacing: 6) {
                    ForEach(STAGES, id: \.0) { k, en, sw in Pill(t(en, sw), on: v.stage == k) { v.stage = v.stage == k ? "" : k } }
                }.padding(.top, 8).padding(.bottom, 10)
            }
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
            Field(t("Anything else wrong?", "Kuna kingine kibaya?"), $v.other, placeholder: wet ? t("e.g. tap drips", "mf. bomba linavuja") : k0 == "farm" ? t("e.g. feeder broken", "mf. chombo cha chakula kimevunjika") : t("e.g. window glass broken", "mf. kioo cha dirisha kimevunjika"))
            if v.state != "working" && !v.state.isEmpty {
                Field(wet ? t("Parts needed", "Vipuri vinavyohitajika") : t("Materials or parts needed", "Vifaa vinavyohitajika"), $v.parts,
                      placeholder: wet ? t("e.g. 2 pump rods, handle bolt", "mf. fimbo 2 za pampu, boliti ya mkono") : k0 == "farm" ? t("e.g. 4 iron sheets, wire mesh", "mf. mabati 4, wavu wa waya") : t("e.g. 6 iron sheets, 2 door hinges", "mf. mabati 6, bawaba 2 za mlango"))
                Field(t("Rough cost (KES)", "Gharama ya makadirio (KES)"), $v.cost, number: true)
            }
        case 3 where !wet:
            H2(t("Numbers", "Idadi"))
            Text(t("All optional.", "Vyote si lazima.")).font(.bMedium).foregroundColor(C.muted)
            ForEach(NUMS[k0] ?? [], id: \.self) { k in
                Field(t(NUM[k]!.0, NUM[k]!.1), Binding(get: { v.nums[k] ?? "" }, set: { v.nums[k] = $0 }), placeholder: k == "income" ? "e.g. 12000" : "e.g. 12", number: true)
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
            if k0 == "home" {
                Text(t("Children: no close-up faces unless the home has agreed.", "Watoto: usipige picha za nyuso kwa karibu isipokuwa makao yamekubali.")).font(.tSmall).foregroundColor(C.warn)
                    .padding(12).frame(maxWidth: .infinity, alignment: .leading).background(C.warnBg).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous)).padding(.bottom, 10)
            }
            PhotoSlots(slots: slots(p), shots: $v.shots)
            VoiceNote(path: $v.voice)
        case 5 where !wet:
            let cr = CARER[k0] ?? CARER["building"]!
            H2(t("Who looks after it", "Anayeisimamia"))
            HStack(alignment: .bottom, spacing: 10) {
                Field(t(cr.0, cr.1), $v.chair, placeholder: t("Name", "Jina"))
                Field(t("Their phone", "Simu yake"), $v.phone)
            }
            H2(t("Story moment (optional)", "Hadithi (si lazima)")).padding(.top, 18)
            Text(t("A few words from someone it helps, for the website.", "Maneno machache kutoka kwa mtu anayesaidiwa, kwa ajili ya tovuti.")).font(.bMedium).foregroundColor(C.muted)
            Field(t("What they said", "Walichosema"), $v.quote, placeholder: t("e.g. “Our children learn in a dry classroom now.”", "mf. “Watoto wetu sasa wanasoma darasani pasipo kuvuja.”"), lines: 3)
            Field(t("Who said it", "Aliyesema"), $v.quoteBy, placeholder: t("e.g. Mary, a parent", "mf. Mary, mzazi"))
            Check(label: t("They agreed to be quoted and photographed for RAFIKI’s website", "Wamekubali kunukuliwa na kupigwa picha kwa tovuti ya RAFIKI"), on: v.consent) { v.consent.toggle() }.padding(.top, 12)
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
            if let sg = STAGES.first(where: { $0.0 == v.stage }) { SumRow(k: t("Stage", "Hatua"), v: t(sg.1, sg.2)) }
            SumRow(k: t("Condition", "Hali"), v: v.state.isEmpty ? t("Not chosen yet", "Bado haijachaguliwa") : stateLabel(p, v.state), bad: v.state.isEmpty)
            let nn = v.nums.values.filter { Double($0.trimmingCharacters(in: .whitespaces)) != nil }.count
            if nn > 0 { SumRow(k: t("Numbers", "Idadi"), v: "\(nn)") }
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
            if !wet {
                body["kind"] = k0; if !v.stage.isEmpty { body["stage"] = v.stage }
                var ns: JSON = [:]; for (k, s) in v.nums { if let x = num(s) { ns[k] = x } }
                body["numbers"] = ns; body["carer"] = ["name": trim(v.chair), "phone": trim(v.phone)] as JSON
            }
            if let a = v.arrived { body["arrive"] = a }
            if FieldQrCheckin.isFor(p.id) { body["qr"] = true }
            if let ll = v.arrivedAt ?? loc.fix?.ll { body["lat"] = ll.lat; body["lng"] = ll.lng }
            let files = v.shots.map { OutFile(path: $0.path, kind: "photo", label: $0.label) } + (v.voice.map { [OutFile(path: $0, kind: "voice", label: "Voice note")] } ?? [])
            switch await m.submit(body, files) {
            case .sent(_, let note): m.localVisit(p, state: v.state, note: v.note, urgency: v.urgency, problems: bad); v.sent = "sent"; msg = note.map { ("warn", $0) }; FieldQrCheckin.clear(p.id)
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

import SwiftUI
import UIKit

// Office › Community: water committees' SMS / WhatsApp reports (from /api/sms-admin). The same as Android's
// CommunityReports.kt: numbers for the last 7 days, filters, message cards that open a sheet with what happened and
// what to do next, and the settings (set-up, committee phones, voice notes) folded away.

/// What happened to a message: label and tone.
func smsResult(_ r: String) -> (String, String) {
    switch r {
    case "reported-broken": return (t("Not working", "Haifanyi kazi"), "bad")
    case "reported-repair": return (t("Needs repair", "Inahitaji matengenezo"), "warn")
    case "already-open": return (t("Already reported", "Imesharipotiwa"), "has")
    case "fixed": return (t("Working again", "Inafanya kazi tena"), "ok")
    case "fixed-unconfirmed": return (t("Says it works: check", "Wanasema inafanya kazi: hakikisha"), "ok")
    case "noted-working": return (t("Says it works", "Wanasema inafanya kazi"), "ok")
    case "help": return (t("Asked how to use it", "Waliuliza jinsi ya kutumia"), "has")
    case "unknown": return (t("Not understood", "Haikueleweka"), "has")
    case "no-project": return (t("Unknown project number", "Namba ya mradi haijulikani"), "has")
    case "ignored-unregistered": return (t("Ignored: phone not on the list", "Imepuuzwa: simu haipo kwenye orodha"), "off")
    case "ignored-no-project": return (t("Ignored: no such project", "Imepuuzwa: mradi haupo"), "off")
    case "rate-limited", "daily-limit": return (t("Ignored: too many messages", "Imepuuzwa: ujumbe mwingi mno"), "off")
    case "error": return (t("monday.com didn’t answer", "monday.com haikujibu"), "off")
    default: return (r.isEmpty ? "?" : r, "off")
    }
}
func smsBucket(_ r: String) -> String {
    switch r {
    case "reported-broken", "reported-repair", "already-open": return "broken"
    case "fixed", "fixed-unconfirmed", "noted-working": return "working"
    case "help", "unknown", "no-project", "error": return "other"
    default: return "ignored"
    }
}
func smsExplain(_ r: String) -> String {
    switch r {
    case "reported-broken": return t("A repair was opened and the admins were e-mailed.", "Matengenezo yalifunguliwa na wasimamizi walitumiwa barua pepe.")
    case "reported-repair": return t("A repair was opened.", "Matengenezo yalifunguliwa.")
    case "already-open": return t("A repair was already open, so a note was added.", "Matengenezo yalikuwa wazi tayari, kwa hiyo maelezo yaliongezwa.")
    case "fixed": return t("The open repair was closed.", "Matengenezo yaliyokuwa wazi yalifungwa.")
    case "fixed-unconfirmed": return t("Noted on monday.com; the repair stays open until the team checks.", "Imeandikwa kwenye monday.com; matengenezo yanabaki wazi hadi timu ihakikishe.")
    case "noted-working": return t("Noted on monday.com.", "Imeandikwa kwenye monday.com.")
    case "help": return t("They were sent the instructions.", "Walitumiwa maelekezo.")
    case "unknown": return t("The message wasn’t understood; they were sent the instructions.", "Ujumbe haukueleweka; walitumiwa maelekezo.")
    case "no-project": return t("There is no project with that number; they were told.", "Hakuna mradi wenye namba hiyo; waliambiwa.")
    case "ignored-no-project": return t("There is no project with that number and the phone isn’t registered, so nothing was done.", "Hakuna mradi wenye namba hiyo na simu haijasajiliwa, kwa hiyo hakuna kilichofanyika.")
    case "ignored-unregistered": return t("No project number, and the phone isn’t on the committee list, so nothing was done.", "Hakuna namba ya mradi, na simu haipo kwenye orodha ya kamati, kwa hiyo hakuna kilichofanyika.")
    case "rate-limited", "daily-limit": return t("Too many messages from this phone today, so it was ignored.", "Ujumbe mwingi mno kutoka simu hii leo, kwa hiyo ulipuuzwa.")
    case "error": return t("monday.com didn’t answer; try again later.", "monday.com haikujibu; jaribu tena baadaye.")
    default: return ""
    }
}
/// "+254712345678" → "+254 712 ••• 678"; "wa:KE.1461…436" → "WhatsApp user ••• 436".
func smsSender(_ from: String) -> String {
    if from.hasPrefix("wa:") { return t("WhatsApp user ••• ", "Mtumiaji wa WhatsApp ••• ") + from.suffix(3) }
    if from.hasPrefix("+") && from.count > 10 { let a = Array(from); return String(a[0..<4]) + " " + String(a[4..<7]) + " ••• " + String(from.suffix(3)) }
    return from
}
func smsDialable(_ from: String) -> Bool { from.range(of: #"^\+\d{9,15}$"#, options: .regularExpression) != nil }
private let WaGreen = Color(hex: 0x1E9E4A)

struct CommunityReports: View {
    @EnvironmentObject var m: AppModel
    @State private var data: JSON?
    @State private var err: String?
    @State private var reload = 0
    @State private var filter = "all"
    @State private var shown = 40
    @State private var picked: JSON?
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let j = data { content(j) } else if let err { Msg(err, "bad") } else { Text(t("Loading…", "Inapakia…")).foregroundColor(C.muted) }
        }
        .task(id: reload) {
            let r = await m.api.smsAdmin()
            if r.ok { data = r.json; err = nil } else { err = r.status == 404 ? t("Community reports aren’t on the server yet.", "Ripoti za jamii bado hazipo kwenye seva.") : reasonText(r.reason) }
        }
        .sheet(item: Binding(get: { picked.map { JBox(j: $0) } }, set: { picked = $0?.j })) { b in
            SmsSheet(x: b.j, phones: data?.objs("phones") ?? [], openIt: { id in picked = nil; m.go(Place(tab: "office", project: id)) }) { reload += 1 }.environmentObject(m)
        }
    }
    @ViewBuilder private func content(_ j: JSON) -> some View {
        let reps = j.objs("reports"), phones = j.objs("phones")
        let week = Date().addingTimeInterval(-7 * 86400)
        let recent = reps.filter { (When.parse($0.str("at")) ?? .distantPast) > week }
        DqmHead(icon: "chat", hue: "blue", title: t("Community reports", "Ripoti za jamii"), line: t("Water committees text or WhatsApp when a water point breaks.", "Kamati za maji zinatuma SMS au WhatsApp kituo cha maji kikiharibika."),
                nums: [DqmNum(n: recent.count, label: t("last 7 days", "siku 7 zilizopita")),
                       DqmNum(n: recent.filter { smsBucket($0.str("result")) == "broken" }.count, label: t("not working", "hazifanyi kazi"), tone: "bad"),
                       DqmNum(n: recent.filter { smsBucket($0.str("result")) == "ignored" }.count, label: t("ignored", "zimepuuzwa"), tone: "off")])
        let n = { (k: String) in reps.filter { smsBucket($0.str("result")) == k }.count }
        DqmChips(items: [("all", t("All", "Zote"), reps.count), ("broken", t("Broken", "Zimeharibika"), n("broken")), ("working", t("Working", "Zinafanya kazi"), n("working")), ("other", t("Other", "Nyingine"), n("other")), ("ignored", t("Ignored", "Zimepuuzwa"), n("ignored"))],
                 picked: filter) { filter = $0; shown = 40 }
        let list = reps.filter { filter == "all" || smsBucket($0.str("result")) == filter }
        if list.isEmpty { EmptyDrop(text: reps.isEmpty ? t("No messages yet. When a water committee texts the RAFIKI number, it shows here.", "Hakuna ujumbe bado. Kamati ya maji ikituma ujumbe kwa namba ya RAFIKI, utaonekana hapa.") : t("No messages here.", "Hakuna ujumbe hapa.")) }
        ForEach(Array(list.prefix(shown).enumerated()), id: \.offset) { i, x in SmsCard(x: x) { picked = x }.padding(.top, 10).rise(min(i, 6)) }
        if list.count > shown { Btn(t("Show more", "Onyesha zaidi") + " (\(list.count - shown))", light: true, small: true, wide: true) { shown += 40 }.padding(.top, 12) }
        let setup = j.obj("setup") ?? [:], at = setup.obj("at") ?? [:], tw = setup.obj("twilio") ?? [:], voice = j.obj("voice") ?? [:]
        Text(t("Settings", "Mipangilio")).font(.tMedium).foregroundColor(C.ink).padding(.top, 22).padding(.bottom, 8)
        CardV(pad: 4) {
            DqmFold(icon: "cog", hue: "blue", title: t("Set-up", "Usanidi"), summary: "WhatsApp " + (tw.bool("on") ? t("on", "imewashwa") : t("off", "imezimwa")) + " · SMS " + (at.bool("on") ? t("on", "imewashwa") : t("off", "imezimwa")), tone: tw.bool("on") || at.bool("on") ? "ok" : "off") {
                OnRow(on: at.bool("on"), label: "SMS (Africa’s Talking)")
                OnRow(on: at.bool("replies"), label: t("Answers by SMS", "Majibu kwa SMS"))
                OnRow(on: tw.bool("on"), label: "WhatsApp / SMS (Twilio)")
                let vars = [at.obj("vars"), tw.obj("vars")].compactMap { $0 }.flatMap { v in v.keys.sorted().map { ($0, v.bool($0)) } }
                if !vars.isEmpty {
                    let missing = vars.filter { !$0.1 }.count
                    Text(missing == 0 ? t("Everything it needs is set up.", "Kila kinachohitajika kimewekwa.") : missing == 1 ? t("1 setting still needs adding on the server by whoever looks after the website.", "Mpangilio 1 bado unahitaji kuongezwa kwenye seva na anayesimamia tovuti.")
                         : t("\(missing) settings still need adding on the server by whoever looks after the website.", "Mipangilio \(missing) bado inahitaji kuongezwa kwenye seva na anayesimamia tovuti.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 12)
                    TechDetails(names: vars)
                }
                Text(t("Paste these addresses in Africa’s Talking or Twilio:", "Bandika anwani hizi kwenye Africa’s Talking au Twilio:")).font(.bSmall).foregroundColor(C.muted).padding(.top, 12)
                ForEach([(j.obj("urls") ?? [:]).str("at"), (j.obj("urls") ?? [:]).str("twilio")].filter { !$0.isEmpty }, id: \.self) { CopyLine(text: $0) }
            }
            Rectangle().fill(C.line).frame(height: 1).padding(.horizontal, 14)
            DqmFold(icon: "phone", hue: "blue", title: t("Committee phones", "Simu za kamati"), summary: phones.count == 1 ? t("1 phone", "simu 1") : t("\(phones.count) phones", "simu \(phones.count)"), tone: phones.isEmpty ? "off" : "has") {
                if phones.isEmpty { Text(t("None yet. Open a message from a committee and tap “Add as committee phone”.", "Bado hakuna. Fungua ujumbe kutoka kwa kamati na uguse “Ongeza kama simu ya kamati”.")).font(.bSmall).foregroundColor(C.muted) }
                ForEach(Array(phones.enumerated()), id: \.offset) { _, p in
                    HStack(spacing: 10) {
                        IconTile(icon: "phone", tint: C.blue, bg: C.tint, size: 34)
                        VStack(alignment: .leading, spacing: 1) { Text(p.str("phone")).font(.tSmall).foregroundColor(C.ink); Text([p.str("name"), p.str("t")].filter { !$0.isEmpty }.joined(separator: " · ")).font(.bSmall).foregroundColor(C.muted) }
                        Spacer(minLength: 0)
                    }.padding(.top, 10)
                }
            }
            Rectangle().fill(C.line).frame(height: 1).padding(.horizontal, 14)
            DqmFold(icon: "mic", hue: "blue", title: t("Voice notes typed out", "Sauti kuandikwa"), summary: voice.bool("on") ? t("On", "Imewashwa") : t("Off", "Imezimwa"), tone: voice.bool("on") ? "ok" : "off") {
                OnRow(on: voice.bool("on"), label: t("Typed out on monday.com", "Huandikwa kwenye monday.com"))
                Text(voice.bool("on") ? t("Voice notes from visits are typed out (Kiswahili or English) and added to the visit’s note, so History shows them.", "Sauti za ziara zinaandikwa (Kiswahili au Kiingereza) na kuongezwa kwenye maelezo ya ziara.")
                     : t("Off. Whoever looks after the website can switch it on.", "Imezimwa. Anayesimamia tovuti anaweza kuiwasha.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 6)
                if !voice.bool("on") { let ms = ((voice["missing"] as? [Any]) ?? []).compactMap { $0 as? String }; TechDetails(names: (ms.isEmpty ? ["OPENAI_API_KEY"] : ms).map { ($0, false) }) }
            }
        }
    }
}

/// One message: who and which project, what happened, and the message in a chat bubble. Tap for more.
private struct SmsCard: View {
    let x: JSON; let tap: () -> Void
    var body: some View {
        let (label, tone) = smsResult(x.str("result")), (fg, _) = dqmTone(tone), wa = x.str("ch") == "whatsapp", id = x.str("id"), title = x.str("t")
        Button { Haptic.tap(); tap() } label: {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 12) {
                    Icon(wa ? "chat" : "phone", size: 21, color: wa ? WaGreen : C.blue).frame(width: 40, height: 40).background(Circle().fill(wa ? WaGreen.opacity(0.14) : C.tint))
                    VStack(alignment: .leading, spacing: 3) {
                        Text(title.isEmpty ? (id.isEmpty ? t("No project number", "Hakuna namba ya mradi") : t("Project ", "Mradi ") + id) : title).font(.tSmall).foregroundColor(C.ink).lineLimit(1)
                        HStack(spacing: 6) { Circle().fill(fg).frame(width: 8, height: 8); Text(label).font(.mont(12, .bold)).foregroundColor(fg).lineLimit(1) }
                    }
                    Spacer(minLength: 8)
                    Text(smsAgo(x.str("at"))).font(.bSmall).foregroundColor(C.muted).lineLimit(1)
                    Icon("chev", size: 18, color: C.muted)
                }
                Text(x.str("text")).font(.mont(15)).foregroundColor(C.text).multilineTextAlignment(.leading).padding(.horizontal, 12).padding(.vertical, 9)
                    .background(C.dark ? C.bg : Color(hex: 0xF1F5F9)).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)).padding(.top, 10)
                HStack {
                    Text([smsSender(x.str("from")), x.str("name"), wa ? "WhatsApp" : "SMS"].filter { !$0.isEmpty }.joined(separator: " · ")).font(.bSmall).foregroundColor(C.muted).lineLimit(1)
                    Spacer(minLength: 4)
                    if !x.str("reply").isEmpty { Text("↩ " + t("Replied", "Imejibiwa")).font(.bSmall).foregroundColor(C.muted) }
                }.padding(.top, 8)
            }
            .padding(14).opacity(tone == "off" ? 0.72 : 1).frame(maxWidth: .infinity, alignment: .leading).background(C.card).clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(C.line, lineWidth: 1))
        }.buttonStyle(Press())
    }
}

/// What happened with one message, and the next steps: open the project, call, WhatsApp, add the phone.
private struct SmsSheet: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.dismiss) private var dismiss
    let x: JSON; let phones: [JSON]; let openIt: (String) -> Void; let changed: () -> Void
    @State private var msg: (String, String)?
    @State private var busy = false
    @State private var added = false
    var body: some View {
        let result = x.str("result"), (label, tone) = smsResult(result), (fg, bg) = dqmTone(tone)
        let from = x.str("from"), wa = x.str("ch") == "whatsapp", id = x.str("id"), title = x.str("t"), dial = smsDialable(from)
        let registered = x.bool("reg") || phones.contains { $0.str("phone") == from }
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 14) {
                    IconTile(icon: tone == "bad" ? "alert" : tone == "warn" ? "wrench" : tone == "ok" ? "check" : "chat", tint: fg, bg: bg, size: 52)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(label).font(.mont(14, .heavy)).foregroundColor(fg)
                        Text(title.isEmpty ? (id.isEmpty ? t("No project number", "Hakuna namba ya mradi") : t("Project ", "Mradi ") + id) : title).font(.tLarge).foregroundColor(C.ink)
                    }
                    Spacer(minLength: 8)
                    Button { dismiss() } label: { Icon("x", size: 20, color: C.ink).frame(width: 44, height: 44).background(Circle().fill(C.bg)) }.buttonStyle(.plain).accessibilityLabel(t("Close", "Funga"))
                }
                Text(t("Their message", "Ujumbe wao")).font(.lMedium).foregroundColor(C.muted).padding(.top, 18).padding(.bottom, 6)
                Text(x.str("text")).font(.bLarge).foregroundColor(C.text).padding(.horizontal, 14).padding(.vertical, 10)
                    .background(C.dark ? C.bg : Color(hex: 0xF1F5F9)).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                if !x.str("reply").isEmpty {
                    Text(t("RAFIKI replied", "RAFIKI ilijibu")).font(.lMedium).foregroundColor(C.muted).frame(maxWidth: .infinity, alignment: .trailing).padding(.top, 12).padding(.bottom, 6)
                    HStack { Spacer(minLength: 40); Text(x.str("reply")).font(.bMedium).foregroundColor(.white).padding(.horizontal, 14).padding(.vertical, 10).background(C.blue).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous)) }
                }
                if !smsExplain(result).isEmpty {
                    HStack(alignment: .top, spacing: 8) { Icon("info", size: 18, color: fg); Text(smsExplain(result)).font(.bMedium).foregroundColor(C.ink) }
                        .padding(12).frame(maxWidth: .infinity, alignment: .leading).background(bg).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).padding(.top, 16)
                }
                VStack(spacing: 0) {
                    fact(t("From", "Kutoka"), smsSender(from) + (x.str("name").isEmpty ? "" : " · " + x.str("name")) + (registered ? t(" (committee phone)", " (simu ya kamati)") : ""))
                    fact(t("Channel", "Njia"), wa ? "WhatsApp" : "SMS")
                    fact(t("Received", "Ilipokelewa"), Dates.dateTime(x.str("at")))
                }.padding(.top, 14)
                VStack(spacing: 8) {
                    if !title.isEmpty && !id.isEmpty { Btn(t("Open project", "Fungua mradi"), icon: "pin", wide: true) { dismiss(); openIt(id) } }
                    if dial {
                        HStack(spacing: 8) {
                            Btn(t("Call", "Piga simu"), icon: "phone", light: true, small: true, wide: true) { Links.call(from) }
                            Btn("WhatsApp", icon: "chat", light: true, small: true, wide: true) { Share.open("https://wa.me/" + from.filter(\.isNumber)) }
                        }
                        Btn(t("Copy number", "Nakili namba"), icon: "copy", light: true, small: true, wide: true) { UIPasteboard.general.string = from; msg = ("ok", t("Copied ✓", "Imenakiliwa ✓")) }
                    }
                    if dial && !title.isEmpty && !id.isEmpty && !registered && !added {
                        Btn(t("Add as committee phone", "Ongeza kama simu ya kamati"), icon: "plus", enabled: !busy, light: true, wide: true) {
                            busy = true
                            Task {
                                let r = await m.api.smsAdminPost(["action": "phone-add", "phone": from, "id": id, "name": x.str("name"), "cc": ""])
                                busy = false
                                if r.ok { added = true; msg = ("ok", t("Added ✓ This phone can now report and close repairs for \(title).", "Imeongezwa ✓ Simu hii sasa inaweza kuripoti na kufunga matengenezo ya \(title).")); changed() } else { msg = ("bad", reasonText(r.reason)) }
                            }
                        }
                    }
                }.padding(.top, 16)
                if !dial && from.hasPrefix("wa:") { Text(t("WhatsApp hid this person’s number, so you can’t call them from here.", "WhatsApp imeficha namba ya mtu huyu, kwa hiyo huwezi kumpigia kutoka hapa.")).font(.bSmall).foregroundColor(C.muted).padding(.top, 10) }
                if let msg { Msg(msg.1, msg.0) }
            }.padding(.horizontal, 20).padding(.top, 20).padding(.bottom, 28)
        }.background(C.card.ignoresSafeArea())
    }
    private func fact(_ l: String, _ v: String) -> some View {
        HStack(alignment: .top) { Text(l).font(.bMedium).foregroundColor(C.muted).frame(width: 96, alignment: .leading); Text(v).font(.mont(14, .semibold)).foregroundColor(C.ink); Spacer(minLength: 0) }.padding(.vertical, 6)
    }
}

struct OnRow: View {
    let on: Bool; let label: String
    var body: some View {
        HStack(spacing: 10) {
            Icon(on ? "check" : "x", size: 14, color: on ? C.ok : C.muted).frame(width: 22, height: 22).background(Circle().fill(on ? C.okBg : C.bg))
            Text(label).font(.bMedium).foregroundColor(C.text); Spacer()
            Text(on ? t("On", "Imewashwa") : t("Off", "Imezimwa")).font(.lLarge).foregroundColor(on ? C.ok : C.muted)
        }.padding(.top, 10)
    }
}

struct CopyLine: View {
    let text: String
    @State private var done = false
    var body: some View {
        Button { UIPasteboard.general.string = text; done = true; Haptic.tap() } label: {
            HStack(spacing: 8) { Text(text.replacingOccurrences(of: "?", with: "\n?")).font(.bSmall).foregroundColor(C.ink).multilineTextAlignment(.leading); Spacer(minLength: 0); Icon(done ? "check" : "copy", size: 18, color: done ? C.ok : C.muted) }
                .padding(.horizontal, 12).padding(.vertical, 10).background(C.bg).clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        }.buttonStyle(.plain).padding(.top, 6)
    }
}

/// "Technical details": the server's setting names (✓ when set), folded away for whoever looks after the website.
struct TechDetails: View {
    let names: [(String, Bool)]
    @State private var open = false
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button { withAnimation { open.toggle() } } label: {
                HStack(spacing: 4) { Text(t("Technical details", "Maelezo ya kiufundi")).font(.mont(13, .bold)).foregroundColor(C.blue2); Icon("down", size: 16, color: C.blue2).rotationEffect(.degrees(open ? 180 : 0)) }.frame(minHeight: 40)
            }.buttonStyle(.plain)
            if open {
                Text(t("Setting names on the server (Cloudflare), for whoever looks after the website:", "Majina ya mipangilio kwenye seva (Cloudflare), kwa anayesimamia tovuti:")).font(.bSmall).foregroundColor(C.muted)
                VStack(alignment: .leading, spacing: 6) { ForEach(Array(names.enumerated()), id: \.offset) { _, x in Tag((x.1 ? "✓ " : "") + x.0, bg: x.1 ? C.okBg : C.bg, fg: x.1 ? C.ok : C.muted) } }.padding(.top, 6)
            }
        }.padding(.top, 8)
    }
}

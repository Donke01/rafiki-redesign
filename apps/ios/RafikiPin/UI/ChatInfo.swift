import SwiftUI
import UIKit

/// Chat info: the picture and name, Contact / Open project / Edit; notifications (mute, sound); the chat (my bubble
/// colour, wallpaper, pin, only admins send, invite link); the members (message, make admin, remove); Leave group.
struct ChatInfoView: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var hub: ChatHub
    let c: String
    @State private var sheet: String?
    @State private var member: String?
    @State private var said: (String, String)?
    @State private var pickPhoto = false

    var body: some View {
        let me = hub.me
        if let row = hub.row(c) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    head(row, me)
                    VStack(alignment: .leading, spacing: 12) {
                        if let s = said { Msg(s.1, s.0) }
                        card(t("NOTIFICATIONS", "ARIFA")) {
                            let muteText = !row.muted() ? t("Not muted", "Hazijanyamazishwa") : row.mutedUntil == ChatRow.always ? t("Always", "Daima") : t("Until ", "Hadi ") + ChatKit.dateTime(row.mutedUntil)
                            ChatSheetRow("mute", t("Mute notifications", "Nyamazisha arifa"), value: muteText) { if row.muted() { hub.mute(c, 0) } else { sheet = "mute" } }
                            ChatSwitchRow(label: t("Sound for new messages", "Sauti kwa ujumbe mpya"), on: !hub.quiet(c)) { hub.setQuiet(c, !$0) }
                        }
                        card(t("CHAT", "GUMZO")) {
                            ChatSheetRow("bubble", t("My bubble colour", "Rangi ya viputo vyangu"), value: ChatLook.bubbleName(row.bubble)) { sheet = "bubble" }
                            ChatSheetRow("wall", t("Wallpaper", "Mandhari"), value: ChatLook.wallName(row.wallpaper)) { sheet = "wall" }
                            ChatSheetRow("pushpin", row.pinned ? t("Unpin chat", "Ondoa kibandiko") : t("Pin chat", "Bandika gumzo")) { hub.pin(c, !row.pinned) }
                            if row.isGroup && row.kind != "team" {
                                if iAdmin(row, me) {
                                    ChatSwitchRow(label: t("Only admins can send messages", "Wasimamizi pekee wanaweza kutuma ujumbe"), on: row.onlyAdmins) { on in Task { if let e = await hub.updateGroup(c, ["onlyAdmins": on]) { said = ("bad", e) } } }
                                } else if row.onlyAdmins { Text(t("Only admins can send messages", "Wasimamizi pekee wanaweza kutuma ujumbe")).font(.bSmall).foregroundColor(C.muted).padding(8) }
                                if iAdmin(row, me) && row.kind == "group" {
                                    ChatSheetRow("link", t("Invite link", "Kiungo cha mwaliko"), value: t("Anyone on the team with the link can join", "Yeyote kwenye timu mwenye kiungo anaweza kujiunga")) { sheet = "link" }
                                }
                            }
                        }
                        if row.isGroup { members(row, me) }
                        if row.kind == "group" {
                            CardV(pad: 6) { ChatSheetRow("exit", t("Leave group", "Ondoka kwenye kikundi"), red: true) { sheet = "leave" } }
                        }
                        Spacer().frame(height: 24)
                    }.padding(16)
                }
            }
            .background(ChatLook.sand)
            .sheet(isPresented: isSet($sheet)) { sheetView(row) }
            .sheet(isPresented: isSet($member)) { if let n = member { ChatMemberSheet(hub: hub, row: row, n: n, iAdmin: iAdmin(row, me)) { said = $0 }.environmentObject(m).storySheet([.medium]) } }
            .sheet(isPresented: $pickPhoto) { GalleryPicker { img in pickPhoto = false; if let img { upload(img) } }.ignoresSafeArea() }
            .onAppear { if m.demo, let s = Demo.arg("-sheet"), ["bubble", "wall", "mute", "leave", "edit", "link"].contains(s) { sheet = s } }
        } else {
            VStack(spacing: 12) {
                Text(t("This chat isn’t on this phone any more.", "Gumzo hili halipo tena kwenye simu hii.")).font(.bMedium).foregroundColor(C.muted).multilineTextAlignment(.center)
                Btn(t("Back", "Rudi"), icon: "back", light: true, small: true) { m.back() }
            }.padding(24).frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
    private func iAdmin(_ row: ChatRow, _ me: String) -> Bool { row.admin(me) || m.snap?.admin == true }

    private func head(_ row: ChatRow, _ me: String) -> some View {
        ChatNight(round: 26) {
            VStack(spacing: 8) {
                HStack { ChatRoundBtn(icon: "back", label: t("Back", "Rudi"), color: NightInk.ink) { m.back() }; Spacer() }
                ZStack(alignment: .bottomTrailing) {
                    ChatAvatar(row: row, me: me, size: 96)
                    if row.kind == "group" && iAdmin(row, me) {
                        Button { pickPhoto = true } label: { Icon("camera", size: 16, color: C.blue).frame(width: 34, height: 34).background(C.card).clipShape(Circle()) }
                            .buttonStyle(.plain).accessibilityLabel(t("Change photo", "Badilisha picha"))
                    }
                }
                Text(chatTitle(row, me)).font(.hMedium).foregroundColor(NightInk.ink).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
                Text(subline(row, me)).font(.bMedium).foregroundColor(NightInk.muted).multilineTextAlignment(.center)
                if !row.description.isEmpty { Text(row.description).font(.bMedium).foregroundColor(NightInk.ink).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true).padding(.top, 2) }
                HStack(spacing: 8) {
                    if row.kind == "dm" {
                        headBtn("person", t("Contact", "Mawasiliano")) {
                            if let ph = m.team?.first(where: { chatSame($0.name, row.other(me)) })?.phone, !ph.isEmpty { Share.open("tel:" + ph.filter { $0.isNumber || $0 == "+" }) }
                            else { said = ("warn", t("No phone number saved for them.", "Hakuna namba ya simu iliyohifadhiwa.")) }
                        }
                    }
                    if row.kind == "project" && !row.project.isEmpty { headBtn("drop", t("Open project", "Fungua mradi")) { m.go(Place(tab: m.here.tab, project: row.project, projectTab: "pin")) } }
                    if row.kind == "group" && iAdmin(row, me) { headBtn("edit", t("Edit", "Hariri")) { sheet = "edit" } }
                }.padding(.top, 6)
            }.padding(.horizontal, 16).padding(.bottom, 18).padding(.top, 4)
        }
    }
    private func headBtn(_ icon: String, _ label: String, _ go: @escaping () -> Void) -> some View {
        Button { Haptic.tap(); go() } label: {
            HStack(spacing: 6) { Icon(icon, size: 18, color: NightInk.ink); Text(label).font(.lMedium).foregroundColor(NightInk.ink).lineLimit(1).minimumScaleFactor(0.8) }
                .padding(.horizontal, 14).frame(maxWidth: .infinity, minHeight: 44).background(NightInk.chip).clipShape(Capsule())
                .overlay(Capsule().stroke(ChatLook.deep ? Color.clear : C.edge, lineWidth: 1))
        }.buttonStyle(.plain)
    }
    private func subline(_ row: ChatRow, _ me: String) -> String {
        switch row.kind {
        case "dm": let p = hub.presence[row.other(me)]; return p.map { $0.online ? t("online", "yupo mtandaoni") : ChatKit.lastSeen($0.seen) } ?? ""
        case "team": return t("Team group", "Kikundi cha timu") + " · " + t("\(row.members.count) members", "wanachama \(row.members.count)")
        case "project": return t("Project thread", "Mazungumzo ya mradi") + " · " + t("\(row.members.count) people", "watu \(row.members.count)")
        default: return t("Group", "Kikundi") + " · " + t("\(row.members.count) members", "wanachama \(row.members.count)")
        }
    }
    private func card<V: View>(_ label: String, @ViewBuilder _ content: () -> V) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.mont(11, .heavy)).tracking(0.8).foregroundColor(C.muted).padding(.leading, 6)
            CardV(pad: 8) { content() }
        }
    }
    private func members(_ row: ChatRow, _ me: String) -> some View {
        card(t("\(row.members.count) MEMBERS", "WANACHAMA \(row.members.count)")) {
            if row.kind == "team" { Text(t("Members follow the team list (by country).", "Wanachama wanafuata orodha ya timu (kwa nchi).")).font(.bSmall).foregroundColor(C.muted).padding(.horizontal, 8).padding(.vertical, 4) }
            if iAdmin(row, me) && row.kind != "team" && !row.announce { ChatSheetRow("plus", t("Add members", "Ongeza wanachama")) { sheet = "add" } }
            ForEach(row.members.sorted { a, b in chatSame(a, me) ? true : chatSame(b, me) ? false : a < b }, id: \.self) { n in
                let p = hub.presence[n]
                Button { if !chatSame(n, me) { member = n } } label: {
                    HStack(spacing: 10) {
                        ChatAvatar(row: nil, size: 40, online: p?.online == true, name: n)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(chatSame(n, me) ? t("You", "Wewe") : n).font(.tSmall).foregroundColor(C.ink).lineLimit(1)
                            let s = p?.online == true ? t("online", "yupo mtandaoni") : ChatKit.lastSeen(p?.seen ?? 0)
                            if !s.isEmpty { Text(s).font(.bSmall).foregroundColor(C.muted).lineLimit(1) }
                        }
                        Spacer(minLength: 4)
                        if row.admin(n) { Text(t("Admin", "Msimamizi")).font(.mont(11, .heavy)).foregroundColor(ChatHues.named("green").fg).padding(.horizontal, 8).padding(.vertical, 3).background(ChatHues.named("green").bg).clipShape(Capsule()).fixedSize() }
                    }.padding(.horizontal, 6).frame(minHeight: 54).contentShape(Rectangle())
                }.buttonStyle(.plain)
            }
        }
    }
    private func upload(_ img: UIImage) {
        guard let md = ChatFiles.keepPhoto(img), let data = try? Data(contentsOf: Outbox.resolve(md.local)) else { return }
        said = ("", t("Uploading…", "Inapakia…"))
        Task {
            let up = await m.api.chatMedia(data, type: "photo", name: "group.jpg", mime: "image/jpeg")
            if up.ok, let e = await hub.updateGroup(c, ["photo": up.json.str("id")]) { said = ("bad", e) }
            else if up.ok { said = ("ok", t("Photo changed ✓", "Picha imebadilishwa ✓")) }
            else { said = ("bad", t("Couldn’t upload the photo. Check the signal.", "Imeshindwa kupakia picha. Angalia mtandao.")) }
        }
    }

    @ViewBuilder private func sheetView(_ row: ChatRow) -> some View {
        switch sheet ?? "" {
        case "mute": ChatMuteSheet(hub: hub, c: c).environmentObject(m).storySheet([.medium])
        case "bubble": ChatBubbleSheet(hub: hub, row: row).environmentObject(m).storySheet([.medium, .large])
        case "wall": ChatWallSheet(hub: hub, row: row).environmentObject(m).storySheet([.medium, .large])
        case "link": ChatLinkSheet(hub: hub, c: c).environmentObject(m).storySheet([.medium])
        case "add": ChatAddPeopleSheet(hub: hub, row: row).environmentObject(m).storySheet([.large])
        case "edit": ChatEditGroupSheet(hub: hub, row: row) { said = $0 }.environmentObject(m).storySheet([.medium, .large])
        case "leave": ChatLeaveSheet(hub: hub, row: row).environmentObject(m).storySheet([.medium])
        default: EmptyView()
        }
    }
}

/// My bubble colour in this chat (only I see it).
struct ChatBubbleSheet: View {
    @ObservedObject var hub: ChatHub
    let row: ChatRow
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 6) {
                ChatSheetHead(title: t("My bubble colour", "Rangi ya viputo vyangu"))
                Text(t("Only you see it, in this chat.", "Unaiona wewe tu, kwenye gumzo hili.")).font(.bSmall).foregroundColor(C.muted).padding(.bottom, 6)
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 4), spacing: 10) {
                    ForEach(ChatLook.bubbles, id: \.self) { k in
                        let on = row.bubble == k
                        Button { Haptic.tap(); Task { _ = await hub.prefs(row.id, ["bubble": k]) }; dismiss() } label: {
                            VStack(spacing: 6) {
                                Text(t("Hi!", "Habari!")).font(.mont(13, .bold)).foregroundColor(k.isEmpty && ChatLook.deep ? .white : C.text)
                                    .frame(maxWidth: .infinity, minHeight: 44).background(ChatLook.bubble(k) ?? ChatLook.mine).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                                    .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(on ? C.blue : C.line, lineWidth: on ? 3 : 1))
                                FitWords(ChatLook.bubbleName(k), size: 11, weight: .bold, color: C.ink, lines: 2, align: .center)
                            }
                        }.buttonStyle(.plain).accessibilityAddTraits(on ? .isSelected : [])
                    }
                }
            }.padding(20)
        }
    }
}

/// The chat's wallpaper.
struct ChatWallSheet: View {
    @ObservedObject var hub: ChatHub
    let row: ChatRow
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 6) {
                ChatSheetHead(title: t("Wallpaper", "Mandhari"))
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 3), spacing: 10) {
                    ForEach(ChatLook.walls, id: \.self) { k in
                        let on = row.wallpaper == k
                        Button { Haptic.tap(); Task { _ = await hub.prefs(row.id, ["wallpaper": k]) }; dismiss() } label: {
                            VStack(spacing: 6) {
                                ChatWall(kind: k).frame(height: 90).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                                    .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(on ? C.blue : C.line, lineWidth: on ? 3 : 1))
                                FitWords(ChatLook.wallName(k), size: 12, weight: .bold, color: C.ink, lines: 1, align: .center)
                            }
                        }.buttonStyle(.plain).accessibilityAddTraits(on ? .isSelected : [])
                    }
                }
            }.padding(20)
        }
    }
}

struct ChatMemberSheet: View {
    @ObservedObject var hub: ChatHub
    let row: ChatRow; let n: String; let iAdmin: Bool; let said: ((String, String)) -> Void
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 12) { ChatPersonAvatar(name: n, size: 44); Text(n).font(.tLarge).foregroundColor(C.ink).lineLimit(2) }.padding(.bottom, 8)
            ChatSheetRow("chat", t("Message \(n)", "Mtumie \(n) ujumbe")) { dismiss(); ChatNav.shared.dm(n) }
            if iAdmin && row.kind == "group" {
                if row.admin(n) { ChatSheetRow("people", t("Remove as admin", "Ondoa kama msimamizi")) { dismiss(); Task { if let e = await hub.members(row.id, unadmin: [n]) { said(("bad", e)) } } } }
                else { ChatSheetRow("crown", t("Make group admin", "Mfanye msimamizi wa kikundi")) { dismiss(); Task { if let e = await hub.members(row.id, admin: [n]) { said(("bad", e)) } } } }
                ChatSheetRow("trash", t("Remove from group", "Mwondoe kwenye kikundi"), red: true) {
                    dismiss(); Task { if let e = await hub.members(row.id, remove: [n]) { said(("bad", e)) } else { said(("ok", t("\(n) was removed. They lose access at once.", "\(n) ameondolewa. Amepoteza ufikiaji mara moja."))) } }
                }
            }
            Spacer(minLength: 0)
        }.padding(20)
    }
}

struct ChatEditGroupSheet: View {
    @ObservedObject var hub: ChatHub
    let row: ChatRow; let said: ((String, String)) -> Void
    @State private var title = ""
    @State private var desc = ""
    @State private var busy = false
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ChatSheetHead(title: t("Edit group", "Hariri kikundi"))
                Field(t("Name", "Jina"), $title)
                Field(t("Description (optional)", "Maelezo (si lazima)"), $desc, lines: 3)
                Btn(t("Save", "Hifadhi"), icon: "check", enabled: !title.trimmingCharacters(in: .whitespaces).isEmpty, wide: true, busy: busy ? t("Saving…", "Inahifadhi…") : nil) {
                    busy = true
                    Task {
                        let e = await hub.updateGroup(row.id, ["title": title.trimmingCharacters(in: .whitespaces), "description": desc.trimmingCharacters(in: .whitespaces)])
                        busy = false
                        if let e { said(("bad", e)) } else { said(("ok", t("Saved ✓", "Imehifadhiwa ✓"))); dismiss() }
                    }
                }.padding(.top, 16)
            }.padding(20)
        }
        .onAppear { title = row.title; desc = row.description }
    }
}

struct ChatLeaveSheet: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var hub: ChatHub
    let row: ChatRow
    @State private var err: String?
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ChatSheetHead(title: t("Leave “\(row.title)”?", "Uondoke kwenye “\(row.title)”?"))
            Text(t("You won’t get its messages any more. An admin can add you back.", "Hutapata tena ujumbe wake. Msimamizi anaweza kukurudisha.")).font(.bMedium).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true)
            Btn(t("Leave group", "Ondoka kwenye kikundi"), icon: "x", wide: true) {
                Task { if let e = await hub.leave(row.id) { err = e } else { dismiss(); if let i = m.stack.firstIndex(where: { $0.sub == "chat" }) { m.stack = Array(m.stack.prefix(i + 1)) } else { m.back(); m.back() } } }
            }.padding(.top, 6)
            Msg(err, "bad")
            Spacer(minLength: 0)
        }.padding(20)
    }
}

// MARK: - chat settings ----------------------------------------------------------------------------------------------------

/// Chat settings: the double-tap reaction, quick replies, Do-Not-Disturb hours, sounds, the data saver and the data used.
struct ChatSettingsView: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var hub: ChatHub
    @State private var add = ""
    var body: some View {
        let st = hub.settings
        let _ = hub.tick
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 4) {
                    ChatRoundBtn(icon: "back", label: t("Back", "Rudi")) { m.back() }
                    Text(t("Chat settings", "Mipangilio ya gumzo")).font(.hMedium).foregroundColor(C.ink).lineLimit(1).minimumScaleFactor(0.8)
                }
                section(t("DOUBLE-TAP TO REACT", "GUSA MARA MBILI KUJIBU")) {
                    Text(t("Double-tap a message to react with:", "Gusa ujumbe mara mbili kujibu kwa:")).font(.bSmall).foregroundColor(C.muted).padding(.horizontal, 8)
                    HStack(spacing: 0) {
                        ForEach(ChatHub.emoji, id: \.self) { e in
                            let on = st.doubleTap == e
                            Button { st.doubleTap = e; hub.saveSettings("doubleTap") } label: {
                                Text(e).font(.system(size: 24)).frame(width: 44, height: 44).background(on ? C.tint : .clear).clipShape(Circle()).overlay(Circle().stroke(on ? C.blue : .clear, lineWidth: 2))
                            }.buttonStyle(.plain).frame(maxWidth: .infinity).accessibilityLabel(e + (on ? t(", chosen", ", imechaguliwa") : ""))
                        }
                    }
                }
                section(t("QUICK REPLIES", "MAJIBU YA HARAKA")) {
                    Text(t("Shown above an empty message box; one tap sends. Up to \(CHAT_QUICK_MAX).", "Huonekana juu ya kisanduku kitupu; mguso mmoja unatuma. Hadi \(CHAT_QUICK_MAX).")).font(.bSmall).foregroundColor(C.muted).padding(.horizontal, 8).fixedSize(horizontal: false, vertical: true)
                    let list = st.quick
                    ForEach(Array(list.enumerated()), id: \.offset) { i, q in
                        HStack {
                            Text(q).font(.bMedium).foregroundColor(C.ink)
                            Spacer()
                            Button { var l = list; l.remove(at: i); if l.isEmpty { st.quickReset() } else { st.quick = l }; hub.saveSettings("quick") } label: { Icon("x", size: 16, color: C.muted).frame(width: 44, height: 44) }
                                .buttonStyle(.plain).accessibilityLabel(t("Remove “\(q)”", "Ondoa “\(q)”"))
                        }.padding(.leading, 8)
                    }
                    if list.count < CHAT_QUICK_MAX {
                        HStack(alignment: .bottom, spacing: 8) {
                            Field(t("New quick reply", "Jibu jipya la haraka"), $add)
                            Btn(t("Add", "Ongeza"), icon: "plus", enabled: !add.trimmingCharacters(in: .whitespaces).isEmpty, small: true) {
                                let x = String(add.trimmingCharacters(in: .whitespaces).prefix(60)); st.quick = list + [x]; add = ""; hub.saveSettings("quick")
                            }.padding(.bottom, 4)
                        }
                    } else { Msg(t("That’s the most (\(CHAT_QUICK_MAX)). Remove one to add another.", "Hiyo ndiyo idadi ya juu (\(CHAT_QUICK_MAX)). Ondoa moja ili kuongeza nyingine."), "warn") }
                    if st.quickCustom { ChatSheetRow("undo", t("Back to the usual replies", "Rudisha majibu ya kawaida")) { st.quickReset(); hub.saveSettings("quick") } }
                }
                section(t("DO NOT DISTURB", "USINISUMBUE")) {
                    let d = st.dnd
                    Text(t("During these hours: no sounds and quiet notifications.", "Katika saa hizi: hakuna sauti na arifa kimya.")).font(.bSmall).foregroundColor(C.muted).padding(.horizontal, 8).fixedSize(horizontal: false, vertical: true)
                    ChatSwitchRow(label: t("Quiet hours", "Saa za utulivu"), on: d.on) { v in var x = st.dnd; x.on = v; st.dnd = x; hub.saveSettings("dnd") }
                    if d.on {
                        ChatTimeRow(icon: "moon", label: t("From", "Kuanzia"), value: d.from) { v in var x = st.dnd; x.from = v; st.dnd = x; hub.saveSettings("dnd") }
                        ChatTimeRow(icon: "sun", label: t("Until", "Hadi"), value: d.to) { v in var x = st.dnd; x.to = v; st.dnd = x; hub.saveSettings("dnd") }
                        ChatSwitchRow(label: t("Let @mentions through", "Ruhusu @kutajwa kupita"), on: d.mentions) { v in var x = st.dnd; x.mentions = v; st.dnd = x; hub.saveSettings("dnd") }
                        if d.active() { Text(t("Quiet now, until \(d.to).", "Kimya sasa, hadi \(d.to).")).font(.mont(13, .semibold)).foregroundColor(ChatHues.named("purple").fg).padding(8) }
                    }
                }
                section(t("SOUNDS", "SAUTI")) {
                    ChatSwitchRow(label: t("Soft sound when I send", "Sauti laini ninapotuma"), on: st.sendSound) { st.sendSound = $0; hub.tick += 1 }
                    ChatSwitchRow(label: t("A chime when I’m @mentioned", "Kengele ninapotajwa kwa @"), on: st.mentionSound) { st.mentionSound = $0; hub.tick += 1 }
                }
                section(t("DATA SAVER", "KUOKOA DATA")) {
                    Text(t("Photos load as small previews; full size, videos and documents when you tap them. Voice messages still load by themselves.", "Picha hupakia kama hakikisho ndogo; ukubwa kamili, video na hati unapozigusa. Ujumbe wa sauti bado hupakia wenyewe."))
                        .font(.bSmall).foregroundColor(C.muted).padding(.horizontal, 8).fixedSize(horizontal: false, vertical: true)
                    ForEach([("off", t("Off", "Imezimwa")), ("mobile", t("On mobile data", "Kwenye data ya simu")), ("always", t("Always", "Daima"))], id: \.0) { k, label in
                        Check(label: label, on: st.saver == k) { st.saver = k; hub.tick += 1 }.padding(.horizontal, 8)
                    }
                    Text(t("Data used by chat this month: ", "Data iliyotumiwa na gumzo mwezi huu: ") + chatDataText(st.usedThisMonth)).font(.mont(14, .semibold)).foregroundColor(C.ink).padding(8)
                }
                Spacer().frame(height: 24)
            }.padding(16)
        }
        .background(ChatLook.sand)
    }
    private func section<V: View>(_ label: String, @ViewBuilder _ content: () -> V) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.mont(11, .heavy)).tracking(0.8).foregroundColor(C.muted).padding(.leading, 6)
            CardV(pad: 10) { VStack(alignment: .leading, spacing: 6) { content() } }
        }
    }
}

/// A time of day ("21:00") picked with the wheel.
struct ChatTimeRow: View {
    let icon: String; let label: String; let value: String; let set: (String) -> Void
    @State private var open = false
    @State private var pick = Date()
    var body: some View {
        ChatSheetRow(icon, label, value: value) {
            let mins = ChatDnd.minutes(value) ?? 0
            pick = Calendar.current.date(bySettingHour: mins / 60, minute: mins % 60, second: 0, of: Date()) ?? Date()
            open = true
        }
        .sheet(isPresented: $open) {
            VStack(spacing: 12) {
                ChatSheetHead(title: label)
                DatePicker("", selection: $pick, displayedComponents: .hourAndMinute).datePickerStyle(.wheel).labelsHidden()
                    .environment(\.locale, Locale(identifier: "en_GB"))
                Btn(t("OK", "Sawa"), icon: "check", wide: true) {
                    let c = Calendar.current.dateComponents([.hour, .minute], from: pick)
                    set(String(format: "%02d:%02d", c.hour ?? 0, c.minute ?? 0)); open = false
                }
            }.padding(20).storySheet([.medium])
        }
    }
}

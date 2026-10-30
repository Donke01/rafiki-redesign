import SwiftUI
import UIKit

/// Hold a message: a reaction, then Reply, Forward, Copy, Star as four tiles; Translate, Pin, Message info, Edit (own, 15
/// minutes), Select, Use in a story, Add to project photos, Report a repair, Assign a visit; Delete last in red.
struct ChatMsgSheet: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var hub: ChatHub
    let msg: ChatMsg; let row: ChatRow?
    let onReply: () -> Void; let onEdit: () -> Void; let onSelect: () -> Void; let onDelete: () -> Void; let onInfo: () -> Void
    @State private var fwd = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let me = hub.me
        let mine = chatSame(msg.from, me)
        ScrollView {
            VStack(alignment: .leading, spacing: 4) {
                if !msg.deleted && !msg.queued && msg.sys == nil { reactBar(me) }
                Text(t("Sent ", "Ilitumwa ") + ChatKit.dateTime(msg.at) + (!mine && !msg.from.isEmpty ? " · " + msg.from : "")).font(.bSmall).foregroundColor(C.muted).padding(.leading, 4).padding(.bottom, 6)
                if msg.queued || msg.status == "failed" {
                    if msg.status == "failed" { ChatSheetRow("undo", t("Try again", "Jaribu tena")) { close { hub.retry(msg) } } }
                    ChatSheetRow("trash", t("Don’t send", "Usitume"), red: true) { close { hub.unsend(msg) } }
                } else {
                    if !msg.deleted && msg.sys == nil { actions(me, mine) }
                    ChatSheetRow("trash", t("Delete", "Futa"), red: true) { close { onDelete() } }
                }
            }.padding(20)
        }
        .sheet(isPresented: $fwd) { ChatForwardSheet(hub: hub, msgs: [msg]) { dismiss() }.environmentObject(m).storySheet([.large]) }
    }
    private func close(_ then: @escaping () -> Void) { dismiss(); DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { then() } }

    private func reactBar(_ me: String) -> some View {
        let mineR = msg.reacts.first { $0.value.contains { chatSame($0, me) } }?.key
        return HStack(spacing: 0) {
            ForEach(ChatHub.emoji, id: \.self) { e in
                Button { close { Task { _ = await hub.react(msg, e) } } } label: {
                    Text(e).font(.system(size: 26)).frame(width: 44, height: 44).background(e == mineR ? C.card : Color.clear).clipShape(Circle())
                }.buttonStyle(.plain).accessibilityLabel(t("React \(e)", "Jibu kwa \(e)")).frame(maxWidth: .infinity)
            }
        }.padding(6).background(ChatHues.named("slate").bg).clipShape(Capsule()).padding(.bottom, 10)
    }

    @ViewBuilder private func actions(_ me: String, _ mine: Bool) -> some View {
        let admin = m.snap?.admin == true
        let canEdit = mine && msg.kind == "text" && chatNow() - msg.at < 15 * 60_000
        let pid: String? = { if let p = row?.project, !p.isEmpty { return p }; if let p = msg.sys?.project, !p.isEmpty { return p }; return ChatKit.projectIn(msg.text) }()
        let translate = !msg.text.isEmpty && !mine && (msg.kind == "text" || msg.kind == "media")
        HStack(spacing: 8) {
            tile("reply", t("Reply", "Jibu")) { close { onReply() } }
            tile("fwd", t("Forward", "Sambaza")) { fwd = true }
            if !msg.text.isEmpty { tile("copy", t("Copy", "Nakili")) { UIPasteboard.general.string = msg.text; hub.note = t("Copied ✓", "Imenakiliwa ✓"); dismiss() } }
            tile("star", msg.starred ? t("Unstar", "Ondoa nyota") : t("Star", "Weka nyota")) { close { Task { _ = await hub.star(msg, !msg.starred) } } }
        }.padding(.bottom, 8)
        if translate {
            ChatSheetRow("translate", "Translate · Tafsiri", value: "English ↔ Kiswahili") {
                close {
                    if hub.tools["translate"] != true { Share.open(ChatKit.google(msg.text)) }
                    else { Task { let (tx, e, g) = await hub.translate(msg.text); if let tx { hub.translated[msg.id] = tx } else if let g { Share.open(g) } else { hub.note = e } } }
                }
            }
        }
        let pinned = row?.pins.contains { $0.id == msg.id } == true
        if hub.canPin(row, officeAdmin: admin) {
            ChatSheetRow("pin", pinned ? t("Unpin", "Ondoa kibandiko") : t("Pin", "Bandika"),
                         value: !pinned && (row?.pins.count ?? 0) >= 3 ? t("Replaces the oldest of the 3 pins", "Inachukua nafasi ya kibandiko cha zamani zaidi kati ya 3") : "") {
                close { Task { if let e = await hub.pinMsg(msg, !pinned) { hub.note = e } } }
            }
        }
        if mine { ChatSheetRow("info", t("Message info", "Maelezo ya ujumbe")) { close { onInfo() } } }
        if canEdit { ChatSheetRow("edit", t("Edit", "Hariri")) { close { onEdit() } } }
        ChatSheetRow("selectall", t("Select", "Chagua")) { close { onSelect() } }
        if let r = row, r.kind == "project", !r.project.isEmpty, msg.kind == "text", !msg.text.isEmpty {
            ChatSheetRow("spark", t("Use in a story", "Tumia kwenye hadithi"), value: t("The words become the story’s quote", "Maneno yanakuwa nukuu ya hadithi")) { close { useInStory(r.project) } }
        }
        if let r = row, r.kind == "project", !r.project.isEmpty, msg.media.contains(where: { $0.type == "photo" }), !hub.added(msg) {
            ChatSheetRow("photoadd", t("Add to project photos", "Ongeza kwenye picha za mradi")) {
                close { Task { if let e = await hub.addPhotos(msg, project: r.project) { hub.note = e } else { hub.note = t("Added to the project ✓", "Imeongezwa kwenye mradi ✓") } } }
            }
        }
        if let pid {
            ChatSheetRow("wrench", t("Report a repair", "Ripoti matengenezo"), value: t("With this message’s words and photos", "Kwa maneno na picha za ujumbe huu")) { close { Task { await repair(pid) } } }
        }
        if admin, pid != nil {
            let who = mine ? (row?.other(me) ?? "") : msg.from
            ChatSheetRow("cal", t("Assign a visit", "Gawa ziara"), value: who.isEmpty ? "" : t("For \(who)", "Kwa \(who)")) { close { m.tab("office") } }
        }
    }
    private func tile(_ icon: String, _ label: String, _ go: @escaping () -> Void) -> some View {
        Button { Haptic.tap(); go() } label: {
            VStack(spacing: 6) {
                CIcon(icon, size: 22, color: ChatLook.deep ? ChatLook.acc : C.blue)
                FitWords(label, size: 12, weight: .bold, color: C.ink, lines: 2, align: .center)
            }.frame(maxWidth: .infinity, minHeight: 72).padding(.horizontal, 4).background(C.tint.opacity(0.7)).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }.buttonStyle(.plain)
    }
    /// "Use in a story": the words become the project story's quote (who said it: the sender), then the Stories tab opens.
    private func useInStory(_ pid: String) {
        let quote = String(msg.text.trimmingCharacters(in: .whitespacesAndNewlines).prefix(600))
        Task {
            let r = await m.api.postTo("/api/story", ["action": "answers", "id": pid, "quote": quote, "quoteBy": msg.from])
            hub.note = r.ok ? t("Added to the story as its quote ✓", "Imeongezwa kwenye hadithi kama nukuu ✓") : (r.status == 0 ? reasonText("offline") : reasonText(r.reason))
            if r.ok { m.go(Place(tab: m.here.tab, project: pid, projectTab: "story")) }
        }
    }
    /// "Report a repair": the project's Site visit, filled in with "Needs repair", the words and the photos.
    private func repair(_ pid: String) async {
        let v = VisitDraft.of(pid)
        if v.state.isEmpty { v.state = "repair" }
        let line = (msg.text.isEmpty ? "" : msg.text + "\n") + t("From \(msg.from) in chat, ", "Kutoka kwa \(msg.from) kwenye gumzo, ") + ChatKit.dateTime(msg.at)
        v.note = v.note.isEmpty ? line : v.note + "\n" + line
        for md in msg.media.filter({ $0.type == "photo" }).prefix(6) {
            guard let f = await hub.file(md), let img = UIImage(contentsOfFile: f.path), let path = m.keepPhoto(img) else { continue }
            v.shots.append(Shot(path: path, label: "Other photo"))
        }
        m.go(Place(tab: m.here.tab, project: pid, projectTab: "visit"))
    }
}

/// Who reacted with what; tap yours to take it away.
struct ChatReactsSheet: View {
    @ObservedObject var hub: ChatHub
    let msg: ChatMsg
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        let list: [(String, String)] = msg.reacts.sorted { $0.value.count > $1.value.count }.flatMap { e in e.value.map { (e.key, $0) } }
        ScrollView {
            VStack(alignment: .leading, spacing: 4) {
                ChatSheetHead(title: t("Reactions", "Miitikio"))
                ForEach(Array(list.enumerated()), id: \.offset) { _, pair in
                    let mine = chatSame(pair.1, hub.me)
                    Button { if mine { dismiss(); Task { _ = await hub.react(msg, pair.0) } } } label: {
                        HStack(spacing: 10) {
                            ChatPersonAvatar(name: pair.1, size: 36)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(mine ? t("You", "Wewe") : pair.1).font(.tSmall).foregroundColor(C.ink)
                                if mine { Text(t("Tap to remove", "Gusa kuondoa")).font(.bSmall).foregroundColor(C.muted) }
                            }
                            Spacer()
                            Text(pair.0).font(.system(size: 22))
                        }.frame(minHeight: 48).contentShape(Rectangle())
                    }.buttonStyle(.plain).disabled(!mine)
                }
            }.padding(20)
        }
    }
}

/// Message info: who read it and who received it, with the times.
struct ChatInfoSheet: View {
    @ObservedObject var hub: ChatHub
    let msg: ChatMsg; let row: ChatRow?
    @State private var info: ([(String, Double)], [(String, Double)])?
    @State private var failed = false
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 4) {
                ChatSheetHead(title: t("Message info", "Maelezo ya ujumbe"))
                Text(msg.preview()).font(.bMedium).foregroundColor(C.muted).lineLimit(3).padding(.bottom, 8)
                if failed { Msg(t("Couldn’t load who read it. Check the signal.", "Imeshindwa kupakia waliosoma. Angalia mtandao."), "warn") }
                else if let i = info {
                    group(t("READ BY", "IMESOMWA NA"), "read", i.0)
                    group(t("DELIVERED TO", "IMEFIKA KWA"), "delivered", i.1.filter { d in !i.0.contains { chatSame($0.0, d.0) } })
                    let others = (row?.members ?? []).filter { n in !chatSame(n, hub.me) && !(i.0 + i.1).contains { chatSame($0.0, n) } }
                    group(t("NOT YET", "BADO"), "sent", others.map { ($0, 0) })
                } else { Text(t("Loading…", "Inapakia…")).font(.bMedium).foregroundColor(C.muted) }
            }.padding(20)
        }
        .task { info = await hub.info(msg); failed = info == nil }
    }
    @ViewBuilder private func group(_ label: String, _ status: String, _ list: [(String, Double)]) -> some View {
        if !list.isEmpty {
            HStack(spacing: 6) { ChatTicks(status: status); Text(label).font(.mont(11, .heavy)).tracking(0.8).foregroundColor(C.muted) }.padding(.top, 12).padding(.bottom, 4)
            ForEach(Array(list.enumerated()), id: \.offset) { _, p in
                HStack(spacing: 10) {
                    ChatPersonAvatar(name: p.0, size: 34)
                    Text(p.0).font(.tSmall).foregroundColor(C.ink).lineLimit(1)
                    Spacer(minLength: 4)
                    if p.1 > 0 { Text(ChatKit.dateTime(p.1)).font(.bSmall).foregroundColor(C.muted).lineLimit(1).fixedSize() }
                }.padding(.vertical, 5)
            }
        }
    }
}

/// Delete one or several messages: for everyone (mine, or as the chat's admin) or for me.
struct ChatDeleteSheet: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var hub: ChatHub
    let row: ChatRow?; let c: String; let ms: [ChatMsg]; let done: () -> Void
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        let me = hub.me
        let n = ms.count, one = n == 1
        let others = ms.filter { !chatSame($0.from, me) }
        let admin = row?.admin(me) == true || (m.snap?.admin == true && row?.isGroup == true)
        let all = ms.allSatisfy { !$0.deleted } && (others.isEmpty || admin)
        let names = Array(Set(others.map { ChatKit.first($0.from) })).sorted()
        let why2 = t("so “Delete for everyone” is only there because you’re an admin of this chat.", "kwa hiyo “Futa kwa wote” ipo kwa sababu wewe ni msimamizi wa gumzo hili.")
        let adminLine: String? = others.isEmpty || !all ? nil : (one ? t("It’s \(names.first ?? "")’s message, ", "Ni ujumbe wa \(names.first ?? ""), ") + why2 : t("\(others.count) of them are other people’s, ", "Jumbe \(others.count) ni za watu wengine, ") + why2)
        let mem = row?.members.count ?? 0
        let everyone = row?.kind == "dm" ? t("Gone for both of you. They see “This message was deleted”.", (one ? "Unaondoka" : "Zinaondoka") + " kwa nyote wawili. Wataona “Ujumbe huu ulifutwa”.")
            : t("Gone for all \(mem) people. They see “This message was deleted”.", (one ? "Unaondoka" : "Zinaondoka") + " kwa watu wote \(mem). Wataona “Ujumbe huu ulifutwa”.")
        let forMe = one ? t("Only gone from your phone. Others still see it.", "Unaondoka kwenye simu yako tu. Wengine bado wanauona.") : t("Only gone from your phone. Others still see them.", "Zinaondoka kwenye simu yako tu. Wengine bado wanaziona.")
        VStack(alignment: .leading, spacing: 6) {
            ChatSheetHead(title: one ? t("Delete message?", "Futa ujumbe?") : t("Delete \(n) messages?", "Futa jumbe \(n)?"))
            if let adminLine { Text(adminLine).font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true) }
            if all { ChatSheetRow("trash", t("Delete for everyone", "Futa kwa wote"), red: true, value: everyone) { go(true) } }
            ChatSheetRow("person", t("Delete for me", "Futa kwangu"), value: forMe) { go(false) }
            Button { dismiss() } label: { Text(t("Cancel", "Ghairi")).font(.tSmall).foregroundColor(C.ink).frame(maxWidth: .infinity, minHeight: 48) }.buttonStyle(.plain)
            Spacer(minLength: 0)
        }.padding(20)
    }
    private func go(_ forAll: Bool) {
        dismiss(); done()
        let n = ms.count
        Task { if let e = await hub.deleteMany(c, ms, forAll: forAll) { hub.note = e } else if n > 1 { hub.note = t("\(n) messages deleted", "Jumbe \(n) zimefutwa") } }
    }
}

/// Forward to one or more chats.
struct ChatForwardSheet: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var hub: ChatHub
    let msgs: [ChatMsg]; let done: () -> Void
    @State private var chosen: [String] = []
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        let me = hub.me
        let rows = hub.sorted(hub.rows.filter { !$0.archived && !($0.onlyAdmins && !$0.admin(me)) })
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 2) {
                    ChatSheetHead(title: t("Forward to…", "Sambaza kwa…"))
                    ForEach(rows) { r in
                        let on = chosen.contains(r.id)
                        Button { if on { chosen.removeAll { $0 == r.id } } else { chosen.append(r.id) } } label: {
                            HStack(spacing: 12) {
                                ChatAvatar(row: r, me: me, size: 40)
                                Text(chatTitle(r, me)).font(.tSmall).foregroundColor(C.ink).lineLimit(1)
                                Spacer(minLength: 4)
                                Circle().fill(on ? ChatLook.online : .clear).frame(width: 24, height: 24).overlay(Circle().stroke(on ? ChatLook.online : C.edge, lineWidth: 2))
                                    .overlay { if on { Icon("check", size: 14, color: .white) } }
                            }.frame(minHeight: 56).contentShape(Rectangle())
                        }.buttonStyle(.plain).accessibilityAddTraits(on ? .isSelected : [])
                    }
                }.padding(20)
            }
            Btn(chosen.isEmpty ? t("Choose chats", "Chagua magumzo") : t("Send to \(chosen.count)", "Tuma kwa \(chosen.count)"), icon: "next", enabled: !chosen.isEmpty, wide: true) {
                for c in chosen { for msg in msgs { hub.send(c, kind: msg.kind, text: msg.text, files: msg.media, loc: msg.loc, contact: msg.contact, fwd: true) } }
                Haptic.ok(); if hub.settings.sendSound { ChatSound.sent() }
                let one = chosen.count == 1 ? chosen.first : nil
                dismiss(); done()
                if let one { DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { m.go(Place(tab: m.here.tab, sub: "chat:" + one)) } }
            }.padding(16)
        }
    }
}

/// Clear chat (for me), or a DM's Delete chat (cleared and archived).
struct ChatClearSheet: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var hub: ChatHub
    let row: ChatRow; let del: Bool
    @State private var busy = false
    @State private var err: String?
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        let who = ChatKit.first(row.other(hub.me))
        VStack(alignment: .leading, spacing: 8) {
            ChatSheetHead(title: del ? t("Delete this chat?", "Futa gumzo hili?") : t("Clear this chat?", "Futa jumbe za gumzo hili?"))
            Text(del ? t("Its messages leave your view and it moves to Archived. \(who) still has them.", "Jumbe zake zinaondoka kwako na gumzo linahamia Zilizohifadhiwa. \(who) bado anazo.")
                 : t("All its messages leave your view. Everyone else still sees them.", "Jumbe zake zote zinaondoka kwako. Wengine bado wanaziona.")).font(.bMedium).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true)
            Btn(del ? t("Delete chat", "Futa gumzo") : t("Clear chat", "Futa jumbe"), icon: "trash", wide: true, busy: busy ? t("Deleting…", "Inafuta…") : nil) {
                busy = true
                Task {
                    let e = await hub.clear(row.id, archive: del)
                    busy = false
                    if let e { err = e } else { hub.note = del ? t("Chat deleted", "Gumzo limefutwa") : t("Chat cleared", "Jumbe zimefutwa"); dismiss(); if del { m.back() } }
                }
            }.padding(.top, 6)
            Msg(err, "bad")
            Button { dismiss() } label: { Text(t("Cancel", "Ghairi")).font(.tSmall).foregroundColor(C.ink).frame(maxWidth: .infinity, minHeight: 48) }.buttonStyle(.plain)
            Spacer(minLength: 0)
        }.padding(20)
    }
}

/// Add people to a group (admins), or ask the admins to (everyone else); share the invite link.
struct ChatAddPeopleSheet: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var hub: ChatHub
    let row: ChatRow
    @State private var pick: [String] = []
    @State private var q = ""
    @State private var link = false
    @State private var busy = false
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        let me = hub.me
        let canAdd = row.admin(me) || m.snap?.admin == true
        let w = q.trimmingCharacters(in: .whitespaces).lowercased()
        let team = (m.team ?? []).filter { !chatSame($0.name, me) && (w.isEmpty || $0.name.lowercased().contains(w)) }
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 6) {
                    ChatSheetHead(title: t("Add people", "Ongeza watu"))
                    if !canAdd { Text(t("Only the group’s admins can add people. Choose who you’d like, and the admins get a message asking them.", "Wasimamizi wa kikundi pekee wanaweza kuongeza watu. Chagua unaowataka, na wasimamizi watapata ujumbe wa kuwaomba.")).font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true) }
                    ChatSearchBox(q: $q, hint: t("Search the team", "Tafuta kwenye timu"))
                    if m.snap?.admin == true { ChatSheetRow("plus", t("Invite a new person", "Alika mtu mpya"), value: t("Add them to the team in Office", "Mwongeze kwenye timu kupitia Ofisi")) { dismiss(); m.tab("office") } }
                    if row.kind == "group" && canAdd { ChatSheetRow("link", t("Share invite link", "Shiriki kiungo cha mwaliko"), value: t("Teammates can join with it", "Wenzako wanaweza kujiunga nacho")) { link = true } }
                    if m.team == nil { Text(t("Loading…", "Inapakia…")).font(.bMedium).foregroundColor(C.muted).padding(12) }
                    ForEach(team) { p in
                        let inn = row.members.contains { chatSame($0, p.name) }
                        let on = pick.contains(p.name)
                        Button { if !inn { if on { pick.removeAll { $0 == p.name } } else { pick.append(p.name) } } } label: {
                            HStack(spacing: 12) {
                                ChatAvatar(row: nil, size: 40, online: hub.presence[p.name]?.online == true, name: p.name)
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(p.name).font(.tSmall).foregroundColor(inn ? C.muted : C.ink).lineLimit(1)
                                    Text(inn ? t("Already in the group", "Tayari yuko kwenye kikundi") : (countryName(p.home) ?? "")).font(.bSmall).foregroundColor(C.muted).lineLimit(1)
                                }
                                Spacer(minLength: 4)
                                if !inn {
                                    Circle().fill(on ? ChatLook.online : .clear).frame(width: 24, height: 24).overlay(Circle().stroke(on ? ChatLook.online : C.edge, lineWidth: 2))
                                        .overlay { if on { Icon("check", size: 14, color: .white) } }
                                }
                            }.frame(minHeight: 54).contentShape(Rectangle())
                        }.buttonStyle(.plain).disabled(inn)
                    }
                    if m.team != nil && team.isEmpty { Text(t("No one found", "Hakuna aliyepatikana")).font(.bMedium).foregroundColor(C.muted).padding(12) }
                }.padding(20)
            }
            Btn(label(canAdd, pick.count), icon: "check", enabled: !pick.isEmpty, wide: true, busy: busy ? t("Adding…", "Inaongeza…") : nil) { go(canAdd) }.padding(16)
        }
        .task { if m.team == nil { await m.loadTeam() } }
        .sheet(isPresented: $link) { ChatLinkSheet(hub: hub, c: row.id).environmentObject(m).storySheet([.medium]) }
    }
    private func label(_ canAdd: Bool, _ k: Int) -> String {
        if canAdd {
            if k == 1 { return t("Add 1 person", "Ongeza mtu 1") }
            return k > 0 ? t("Add \(k) people", "Ongeza watu \(k)") : t("Add people", "Ongeza watu")
        }
        if k == 1 { return t("Ask an admin to add 1 person", "Mwombe msimamizi aongeze mtu 1") }
        return k > 0 ? t("Ask an admin to add \(k) people", "Mwombe msimamizi aongeze watu \(k)") : t("Ask an admin to add someone", "Mwombe msimamizi aongeze mtu")
    }
    private func go(_ canAdd: Bool) {
        let names = pick
        busy = true
        Task {
            if canAdd {
                let e = await hub.members(row.id, add: names)
                busy = false
                if let e { hub.note = e } else { hub.note = names.count == 1 ? t("\(ChatKit.first(names[0])) added ✓", "\(ChatKit.first(names[0])) ameongezwa ✓") : t("\(names.count) people added ✓", "Watu \(names.count) wameongezwa ✓"); dismiss() }
            } else {
                let ad = row.admins.filter { !chatSame($0, hub.me) }
                busy = false
                if ad.isEmpty { hub.note = t("This group has no other admin to ask.", "Kikundi hiki hakina msimamizi mwingine wa kumwomba."); return }
                let list = names.joined(separator: ", ")
                hub.send(row.id, kind: "text", text: ad.map { "@" + $0 }.joined(separator: " ") + " " + t("could you add \(list) to this group, please?", "tafadhali mnaweza kuwaongeza \(list) kwenye kikundi hiki?"), mentions: ad)
                hub.note = t("Asked the admins ✓", "Wasimamizi wameombwa ✓"); dismiss()
            }
        }
    }
}

/// A group's invite link: copy, share, reset.
struct ChatLinkSheet: View {
    @ObservedObject var hub: ChatHub
    let c: String
    @State private var url: String?
    @State private var err: String?
    @State private var said: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ChatSheetHead(title: t("Invite link", "Kiungo cha mwaliko"))
            Text(t("A teammate who opens it in RAFIKI Pin joins this group. Donors can’t join.", "Mwenzako akikifungua kwenye RAFIKI Pin anajiunga na kikundi hiki. Wafadhili hawawezi kujiunga.")).font(.bSmall).foregroundColor(C.muted).fixedSize(horizontal: false, vertical: true)
            if let u = url {
                Text(u).font(.bSmall).foregroundColor(C.blue2).lineLimit(2).padding(.vertical, 4)
                ChatSheetRow("copy", t("Copy link", "Nakili kiungo")) { UIPasteboard.general.string = u; said = t("Copied ✓", "Imenakiliwa ✓") }
                ChatSheetRow("share", t("Share link", "Shiriki kiungo")) { Share.items([u]) }
                ChatSheetRow("refresh", t("Reset link", "Weka kiungo kipya"), red: true, value: t("The old link stops working", "Kiungo cha zamani kinaacha kufanya kazi")) {
                    Task { let (n, e) = await hub.link(c, reset: true); if let n { url = n; said = t("New link ready ✓", "Kiungo kipya kiko tayari ✓") } else { err = e } }
                }
            } else if err == nil { Text(t("Loading…", "Inapakia…")).font(.bMedium).foregroundColor(C.muted) }
            Msg(said, "ok"); Msg(err, "bad")
            Spacer(minLength: 0)
        }.padding(20)
        .task { let (u, e) = await hub.link(c, reset: false); url = u; err = e }
    }
}

/// Media, files and links in this chat.
struct ChatMediaFilesSheet: View {
    @ObservedObject var hub: ChatHub
    let msgs: [ChatMsg]; let openMedia: (Int) -> Void
    @State private var tab = "media"
    var body: some View {
        let live = msgs.filter { !$0.deleted }
        let media: [(ChatMsg, ChatMedia)] = live.flatMap { msg in msg.media.filter { $0.type == "photo" || $0.type == "video" }.map { (msg, $0) } }
        let files: [ChatMedia] = live.flatMap { $0.media.filter { $0.type == "doc" } }
        let links: [String] = live.flatMap { ChatKit.links($0.text) }
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                ChatSheetHead(title: t("Media, files and links", "Picha, faili na viungo"))
                SmallSeg(items: [("media", t("Media", "Picha") + " \(media.count)"), ("files", t("Files", "Faili") + " \(files.count)"), ("links", t("Links", "Viungo") + " \(links.count)")], sel: tab, fill: true) { tab = $0 }
                switch tab {
                case "files":
                    if files.isEmpty { empty(t("No files yet.", "Bado hakuna faili.")) }
                    ForEach(Array(files.enumerated()), id: \.offset) { _, md in ChatDocCard(hub: hub, md: md, fg: C.ink) }
                case "links":
                    if links.isEmpty { empty(t("No links yet.", "Bado hakuna viungo.")) }
                    ForEach(Array(links.enumerated()), id: \.offset) { _, u in
                        Button { Share.open(u) } label: {
                            HStack(spacing: 10) { Icon("link", size: 20, color: C.blue).frame(width: 38, height: 38).background(C.tint).clipShape(RoundedRectangle(cornerRadius: 10)); Text(u).font(.bSmall).foregroundColor(C.blue2).lineLimit(2).multilineTextAlignment(.leading) }
                        }.buttonStyle(.plain)
                    }
                default:
                    if media.isEmpty { empty(t("No photos or videos yet.", "Bado hakuna picha wala video.")) }
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 4), count: 3), spacing: 4) {
                        ForEach(Array(media.enumerated()), id: \.offset) { i, pair in
                            Button { openMedia(i) } label: { ChatPic(md: pair.1, small: true).aspectRatio(1, contentMode: .fit).clipShape(RoundedRectangle(cornerRadius: 8)) }.buttonStyle(.plain)
                        }
                    }
                }
            }.padding(20)
        }
    }
    private func empty(_ s: String) -> some View { Text(s).font(.bMedium).foregroundColor(C.muted).frame(maxWidth: .infinity).padding(.vertical, 28) }
}

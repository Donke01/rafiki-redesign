import SwiftUI
import UIKit

/// The chat list's chips: All, Unread, People, Groups, Projects, Teams.
enum ChatChip {
    static let keys = ["all", "unread", "dm", "group", "project", "team"]
    static func name(_ k: String) -> String {
        switch k {
        case "unread": return t("Unread", "Hazijasomwa"); case "project": return t("Projects", "Miradi"); case "group": return t("Groups", "Vikundi")
        case "team": return t("Teams", "Timu"); case "dm": return t("People", "Watu"); default: return t("All", "Zote")
        }
    }
    static func ok(_ r: ChatRow, _ k: String) -> Bool {
        switch k { case "all": return true; case "unread": return r.unread > 0 || r.mention; case "dm": return r.kind == "dm"; default: return !r.announce && r.kind == k }
    }
    static func empty(_ k: String) -> String {
        switch k {
        case "unread": return t("No unread chats", "Hakuna gumzo ambazo hazijasomwa"); case "project": return t("No project chats yet", "Bado hakuna gumzo za miradi")
        case "group": return t("No groups yet", "Bado hakuna vikundi"); case "team": return t("No team chats", "Hakuna gumzo za timu")
        case "dm": return t("No chats with people yet", "Bado hakuna gumzo na watu"); default: return t("No chats here", "Hakuna gumzo hapa")
        }
    }
}

/// Chat: every conversation in one list, Announcements and pinned ones first, then the newest. Each row: the picture (with
/// the green dot when they're online), the title, the last message, the time, the unread count and an @ when you were
/// mentioned. Swipe left to archive, right to mute; hold a row for pin, mute, archive and mark as unread. Search looks
/// through titles and the messages themselves.
struct ChatListView: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var hub: ChatHub
    let archived: Bool
    @State private var q = ""
    @State private var searching = false
    @State private var chip = "all"
    @State private var hits: [ChatHub.Hit]?
    @State private var muting: ChatRow?
    @State private var alerts = UNAuthorizationStatusBox.unknown
    @State private var alertsShut = UserDefaults.standard.bool(forKey: "chat.alertsAsked")

    var body: some View {
        let me = hub.me
        let live = hub.sorted(hub.rows.filter { archived ? $0.archived : (!$0.archived || $0.announce) })
        let shown = live.filter { ChatChip.ok($0, archived ? "all" : chip) }
        let nArch = hub.rows.filter { $0.archived && !$0.announce }.count
        ZStack(alignment: .bottomTrailing) {
            List {
                head.listRowInsets(EdgeInsets()).listRowSeparator(.hidden).listRowBackground(Color.clear)
                if hub.off {
                    ChatOffCard(need: hub.offNeed).padding(16).listRowInsets(EdgeInsets()).listRowSeparator(.hidden).listRowBackground(Color.clear)
                } else if !q.trimmingCharacters(in: .whitespaces).isEmpty {
                    searchRows(me)
                } else if !searching {
                    if !archived && alerts == .ask && !alertsShut { alertPrompt.listRowInsets(EdgeInsets()).listRowSeparator(.hidden).listRowBackground(Color.clear) }
                    ForEach(shown) { r in row(r, me) }
                    if !archived && !live.isEmpty && shown.isEmpty {
                        ChatEmpty(text: ChatChip.empty(chip), action: t("Show all chats", "Onyesha gumzo zote")) { chip = "all" }.listRowSeparator(.hidden).listRowBackground(Color.clear)
                    }
                    if !archived && nArch > 0 && chip == "all" { archivedRow(nArch).listRowInsets(EdgeInsets()).listRowSeparator(.hidden).listRowBackground(Color.clear) }
                    if live.isEmpty && hub.ready {
                        Group {
                            if archived { ChatEmpty(text: t("No archived chats. Swipe a chat left to tidy it away here.", "Hakuna magumzo yaliyohifadhiwa. Telezesha gumzo kushoto kuliweka hapa."), action: t("Back to chats", "Rudi kwenye magumzo")) { m.back() } }
                            else { ChatEmpty(text: t("No chats yet. Say hello to the team 👋", "Bado hakuna magumzo. Isalimie timu 👋"), action: t("New chat", "Gumzo jipya")) { m.go(Place(tab: m.here.tab, sub: "chatnew")) } }
                        }.listRowSeparator(.hidden).listRowBackground(Color.clear)
                    }
                    if !hub.ready { Text(t("Loading…", "Inapakia…")).font(.bMedium).foregroundColor(C.muted).padding(20).listRowSeparator(.hidden).listRowBackground(Color.clear) }
                    Color.clear.frame(height: 90).listRowSeparator(.hidden).listRowBackground(Color.clear)
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .background(ChatLook.sand)
            .refreshable { await hub.refreshList(); await m.refreshNow() }
            if !archived && !hub.off && !searching {
                Button { Haptic.tap(); m.go(Place(tab: m.here.tab, sub: "chatnew")) } label: {
                    CIcon("newchat", size: 25, color: .white).frame(width: 58, height: 58).background(ChatLook.go).clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                        .shadow(color: ChatLook.acc.opacity(0.35), radius: 8, y: 4)
                }.buttonStyle(Press()).padding(.trailing, 16).padding(.bottom, 16).accessibilityLabel(t("New chat", "Gumzo jipya")).accessibilityIdentifier("chat_new")
            }
        }
        .onAppear {
            hub.start()
            ChatNotify.status { s in alerts = s == .notDetermined ? .ask : .known }
            if m.demo, let ch = Demo.arg("-chip") { chip = ch }
            if m.demo, Demo.arg("-sheet") == "search" { searching = true; q = Demo.arg("-q") ?? "pump" }
        }
        .task(id: q) {
            let w = q.trimmingCharacters(in: .whitespaces)
            hits = nil
            guard !w.isEmpty else { return }
            try? await Task.sleep(nanoseconds: 300_000_000)
            if Task.isCancelled { return }
            if let h = await hub.search(w) { hits = h }
            else {
                let lw = w.lowercased()
                var local: [ChatHub.Hit] = []
                for r in hub.rows { for x in hub.cache.loadMsgs(r.id) where x.text.lowercased().contains(lw) { local.append(ChatHub.Hit(c: r.id, id: x.id, from: x.from, at: x.at, snippet: x.text)) } }
                hits = Array(local.sorted { $0.at > $1.at }.prefix(30))
            }
        }
        .sheet(isPresented: isSet($muting)) { if let r = muting { ChatMuteSheet(hub: hub, c: r.id).environmentObject(m).storySheet([.medium]) } }
    }

    // MARK: the top

    private var head: some View {
        ChatNight {
            VStack(alignment: .leading, spacing: 10) {
                if searching {
                    HStack(spacing: 6) {
                        ChatRoundBtn(icon: "back", label: t("Close the search", "Funga utafutaji"), color: NightInk.ink) { q = ""; searching = false }
                        TextField("", text: $q, prompt: Text(t("Search chats and messages", "Tafuta gumzo na jumbe")).foregroundColor(NightInk.muted))
                            .font(.bLarge).foregroundColor(NightInk.ink).submitLabel(.search).frame(minHeight: 44)
                        if !q.isEmpty { ChatRoundBtn(icon: "x", label: t("Clear", "Futa"), color: NightInk.muted) { q = "" } }
                    }
                } else {
                    HStack(spacing: 4) {
                        if archived { ChatRoundBtn(icon: "back", label: t("Back", "Rudi"), color: NightInk.ink) { m.back() } }
                        Text(archived ? t("Archived", "Zilizohifadhiwa") : t("Chats", "Gumzo")).font(.mont(26, .heavy)).foregroundColor(NightInk.ink)
                            .lineLimit(1).minimumScaleFactor(0.8).accessibilityAddTraits(.isHeader)
                        Spacer(minLength: 4)
                        ChatRoundBtn(icon: "search", label: t("Search chats and messages", "Tafuta gumzo na jumbe"), color: NightInk.ink) { searching = true }
                        if !archived { menu }
                    }
                }
                if !archived && !hub.off && !searching && q.isEmpty {
                    chips
                    fieldRow
                }
            }.padding(.horizontal, 12).padding(.top, 6).padding(.bottom, 14)
        }
    }

    private var menu: some View {
        Menu {
            Button { m.go(Place(tab: m.here.tab, sub: "chatsettings")) } label: { Label(t("Chat settings", "Mipangilio ya gumzo"), systemImage: "gearshape") }
            Button { m.go(Place(tab: m.here.tab, sub: "chatstar")) } label: { Label(t("Starred messages", "Jumbe zenye nyota"), systemImage: "star") }
            Button { m.go(Place(tab: m.here.tab, sub: "chatarch")) } label: { Label(t("Archived", "Zilizohifadhiwa"), systemImage: "archivebox") }
            Button { m.go(Place(tab: m.here.tab, sub: "chatnew")) } label: { Label(t("New group", "Kikundi kipya"), systemImage: "person.3") }
            if hub.rows.contains(where: { $0.unread > 0 || $0.mention }) { Button { hub.allRead() } label: { Label(t("Mark all as read", "Weka zote kama zimesomwa"), systemImage: "checkmark") } }
            Button { Task { await hub.refreshList(); hub.note = t("Updated", "Imesasishwa") } } label: { Label(t("Refresh", "Onyesha upya"), systemImage: "arrow.clockwise") }
        } label: { CIcon("more", size: 22, color: NightInk.ink).frame(width: 44, height: 44).contentShape(Rectangle()) }
        .accessibilityLabel(t("More", "Zaidi"))
    }

    private var chips: some View {
        let nUn = hub.rows.filter { !$0.archived && ($0.unread > 0 || $0.mention) }.count
        return ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(ChatChip.keys, id: \.self) { k in
                    let on = chip == k
                    Button { Haptic.tap(); chip = k } label: {
                        HStack(spacing: 4) {
                            Text(ChatChip.name(k)).font(.mont(13, .bold)).lineLimit(1).fixedSize()
                            if k == "unread" && nUn > 0 { Text("\(nUn)").font(.mont(13, .heavy)).fixedSize() }
                        }
                        .foregroundColor(on ? (ChatLook.deep ? Color(hex: 0x04263F) : .white) : NightInk.ink)
                        .padding(.horizontal, 14).frame(minHeight: 36)
                        .background(on ? (ChatLook.deep ? Color.white : ChatLook.un) : NightInk.chip).clipShape(Capsule())
                        .overlay(Capsule().stroke(ChatLook.sun ? Color.black : Color.clear, lineWidth: 2))
                    }.buttonStyle(.plain).accessibilityAddTraits(on ? .isSelected : [])
                }
            }.padding(.horizontal, 2)
        }
    }

    /// The teammates online now: a tap opens a direct message.
    @ViewBuilder private var fieldRow: some View {
        let me = hub.me
        let people = hub.presence.filter { $0.value.online && !chatSame($0.key, me) }.map(\.key).sorted()
        if !people.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                Text(t("In the field now", "Uwanjani sasa").uppercased()).font(.mont(11, .heavy)).tracking(0.9).foregroundColor(ChatLook.deep ? ChatLook.ice : C.muted)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(people, id: \.self) { n in
                            Button { ChatNav.shared.dm(n) } label: {
                                VStack(spacing: 4) {
                                    ChatAvatar(row: nil, size: 44, online: true, name: n)
                                    Text(ChatKit.first(n)).font(.mont(11, .bold)).foregroundColor(NightInk.ink).lineLimit(1).frame(width: 64)
                                    Text(t("Online", "Mtandaoni")).font(.mont(10, .medium)).foregroundColor(NightInk.muted).lineLimit(1).minimumScaleFactor(0.8).frame(width: 64)
                                }.padding(.vertical, 6).frame(width: 72).background(NightInk.chip).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                            }.buttonStyle(.plain).accessibilityLabel(t("Message \(n)", "Mtumie \(n) ujumbe"))
                        }
                    }
                }
            }
        }
    }

    private var alertPrompt: some View {
        HStack(spacing: 8) {
            CIcon("bell", size: 18, color: C.blue)
            Text(t("Get message alerts", "Pata arifa za jumbe")).font(.bMedium).foregroundColor(C.text).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 4)
            Button { ChatNotify.ask { _ in alerts = .known } } label: { Text(t("Turn on", "Washa")).font(.mont(13, .heavy)).foregroundColor(ChatLook.link).frame(minHeight: 40).fixedSize() }.buttonStyle(.plain)
            Button { alertsShut = true; UserDefaults.standard.set(true, forKey: "chat.alertsAsked") } label: { Icon("x", size: 16, color: C.muted).frame(width: 40, height: 40) }
                .buttonStyle(.plain).accessibilityLabel(t("Not now", "Si sasa"))
        }
        .padding(.horizontal, 12).padding(.vertical, 4).background(C.card).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .padding(.horizontal, 12).padding(.vertical, 6)
    }

    private func archivedRow(_ n: Int) -> some View {
        Button { m.go(Place(tab: m.here.tab, sub: "chatarch")) } label: {
            HStack(spacing: 13) {
                CIcon("archive", size: 20, color: C.muted).frame(width: 52)
                Text(t("Archived", "Zilizohifadhiwa")).font(.mont(14, .semibold)).foregroundColor(C.muted)
                Spacer()
                Text("\(n)").font(.mont(13, .bold)).foregroundColor(C.muted)
            }.padding(.horizontal, 16).frame(minHeight: 52).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }

    // MARK: rows

    private func row(_ r: ChatRow, _ me: String) -> some View {
        ChatRowView(hub: hub, r: r, me: me) { m.go(Place(tab: m.here.tab, sub: "chat:" + r.id)) }
            .listRowInsets(EdgeInsets(top: ChatLook.deep ? 4 : 0, leading: ChatLook.deep ? 12 : 0, bottom: ChatLook.deep ? 4 : 0, trailing: ChatLook.deep ? 12 : 0))
            .listRowSeparator(.hidden).listRowBackground(Color.clear)
            .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                Button { Haptic.ok(); hub.archive(r.id, !r.archived); hub.note = r.archived ? t("Chat unarchived", "Gumzo limerudishwa") : t("Chat archived", "Gumzo limehifadhiwa") } label: {
                    Label(r.archived ? t("Unarchive", "Rudisha") : t("Archive", "Hifadhi"), systemImage: "archivebox")
                }.tint(ChatHues.named("green").fg)
            }
            .swipeActions(edge: .leading, allowsFullSwipe: true) {
                Button { Haptic.ok(); if r.muted() { hub.mute(r.id, 0); hub.note = t("Notifications on", "Arifa zimewashwa") } else { hub.mute(r.id, 8); hub.note = t("Muted for 8 hours", "Imenyamazishwa kwa saa 8") } } label: {
                    Label(r.muted() ? t("Unmute", "Washa arifa") : t("Mute", "Nyamazisha"), systemImage: r.muted() ? "bell" : "bell.slash")
                }.tint(ChatHues.named("slate").fg)
            }
            .contextMenu {
                Button { hub.pin(r.id, !r.pinned) } label: { Label(r.pinned ? t("Unpin", "Ondoa kibandiko") : t("Pin to the top", "Bandika juu"), systemImage: "pin") }
                if r.muted() { Button { hub.mute(r.id, 0) } label: { Label(t("Unmute", "Washa arifa"), systemImage: "bell") } }
                else { Button { muting = r } label: { Label(t("Mute notifications", "Nyamazisha arifa"), systemImage: "bell.slash") } }
                Button { hub.archive(r.id, !r.archived) } label: { Label(r.archived ? t("Unarchive", "Rudisha") : t("Archive", "Hifadhi"), systemImage: "archivebox") }
                if r.unread == 0 { Button { hub.markUnread(r.id) } label: { Label(t("Mark as unread", "Weka kama haijasomwa"), systemImage: "message.badge") } }
            }
    }

    @ViewBuilder private func searchRows(_ me: String) -> some View {
        let w = q.trimmingCharacters(in: .whitespaces).lowercased()
        let chats = hub.rows.filter { $0.title.lowercased().contains(w) || $0.members.contains { $0.lowercased().contains(w) } }
        if !chats.isEmpty { label(t("CHATS", "MAGUMZO")) }
        ForEach(chats) { r in row(r, me) }
        if let h = hits {
            if !h.isEmpty { label(t("MESSAGES", "UJUMBE")) }
            ForEach(h) { hit in
                Button { ChatNav.shared.focus = hit.id; m.go(Place(tab: m.here.tab, sub: "chat:" + hit.c)) } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        HStack {
                            Text(hub.row(hit.c).map { chatTitle($0, me) } ?? hit.c).font(.tSmall).foregroundColor(C.ink).lineLimit(1)
                            Spacer(minLength: 6)
                            Text(ChatKit.when(hit.at)).font(.bSmall).foregroundColor(C.muted)
                        }
                        Text((chatSame(hit.from, me) ? t("You", "Wewe") : ChatKit.first(hit.from)) + ": " + hit.snippet).font(.bSmall).foregroundColor(C.muted).lineLimit(2)
                    }.padding(.horizontal, 20).padding(.vertical, 10).frame(maxWidth: .infinity, alignment: .leading).background(C.card).contentShape(Rectangle())
                }.buttonStyle(.plain).listRowInsets(EdgeInsets()).listRowSeparator(.hidden).listRowBackground(Color.clear)
            }
            if h.isEmpty && chats.isEmpty {
                ChatEmpty(text: t("Nothing matches “\(q)”. Try another word.", "Hakuna kinacholingana na “\(q)”. Jaribu neno lingine."), action: t("Clear search", "Futa utafutaji")) { q = ""; searching = false }
                    .listRowSeparator(.hidden).listRowBackground(Color.clear)
            } else if h.isEmpty {
                Text(t("No messages match “\(q)”.", "Hakuna ujumbe unaolingana na “\(q)”.")).font(.bMedium).foregroundColor(C.muted).padding(20).listRowSeparator(.hidden).listRowBackground(Color.clear)
            }
        }
    }
    private func label(_ s: String) -> some View {
        Text(s).font(.mont(11, .heavy)).tracking(0.8).foregroundColor(C.muted).padding(.leading, 20).padding(.top, 14).padding(.bottom, 4)
            .listRowInsets(EdgeInsets()).listRowSeparator(.hidden).listRowBackground(Color.clear)
    }
}

enum UNAuthorizationStatusBox { case unknown, ask, known }

/// One row of the chat list: 74 points, a 52-point picture; unread = a bold name, a green time and count; a green "@"
/// when mentioned; "typing…" in green; muted and pinned small grey icons; ticks before my own last message.
struct ChatRowView: View {
    @ObservedObject var hub: ChatHub
    let r: ChatRow; let me: String; let open: () -> Void
    var body: some View {
        let other = r.other(me)
        let online = r.kind == "dm" && hub.presence[other]?.online == true
        let muted = r.muted()
        let un = r.unread > 0
        Button { Haptic.tap(); open() } label: {
            HStack(spacing: 13) {
                ChatAvatar(row: r, me: me, size: 52, online: online)
                VStack(alignment: .leading, spacing: 3) {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text(chatTitle(r, me)).font(.mont(16, un ? .heavy : .bold)).foregroundColor(C.ink).lineLimit(1)
                        Spacer(minLength: 4)
                        Text(ChatKit.when(r.last?.at ?? 0)).font(.mont(12, un && !muted ? .bold : .medium)).foregroundColor(un && !muted ? ChatLook.un : C.muted).lineLimit(1).fixedSize()
                    }
                    HStack(spacing: 5) {
                        preview(un)
                        if r.mention && un {
                            Text("@").font(.mont(12, .heavy)).foregroundColor(ChatLook.un).frame(minWidth: 26).padding(.vertical, 2).background(ChatLook.atBg).clipShape(Capsule())
                                .overlay(Capsule().stroke(ChatLook.sun ? Color.black : .clear, lineWidth: 2)).accessibilityLabel(t("You were mentioned", "Umetajwa"))
                        }
                        if muted { CIcon("mute", size: 15, color: C.muted).accessibilityLabel(t("Muted", "Imenyamazishwa")) }
                        if r.pinned || r.announce { CIcon("pushpin", size: 15, color: C.muted).accessibilityLabel(t("Pinned", "Imebandikwa")) }
                        if un {
                            Text(r.unread > 99 ? "99+" : "\(r.unread)").font(.mont(11, .heavy)).foregroundColor(.white).frame(minWidth: 22).padding(.horizontal, 5).padding(.vertical, 3)
                                .background(muted ? C.muted : ChatLook.un).clipShape(Capsule()).fixedSize()
                        }
                    }
                }.frame(minHeight: 74)
            }
            .padding(.horizontal, ChatLook.deep ? 12 : 16)
            .background(C.card)
            .clipShape(RoundedRectangle(cornerRadius: ChatLook.deep ? 18 : 0, style: .continuous))
            .shadow(color: Color(hex: 0x3C280A, alpha: ChatLook.deep && !C.dark ? 0.10 : 0), radius: 3, y: 1)
            .overlay(alignment: .bottom) { if !ChatLook.deep { Rectangle().fill(C.line).frame(height: ChatLook.sun ? 2 : 1).padding(.leading, 81) } }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("chatrow_" + r.id)
    }
    @ViewBuilder private func preview(_ un: Bool) -> some View {
        let font = Font.mont(14, un ? .medium : .regular)
        if let typed = hub.typingText(r.id, group: r.isGroup) {
            Text(typed).font(.mont(14, .semibold)).foregroundColor(ChatLook.un).lineLimit(1)
        } else if let draft = hub.drafts[r.id], !draft.isEmpty, hub.openChat != r.id {
            (Text(t("Draft: ", "Rasimu: ")).foregroundColor(ChatLook.un).bold() + Text(draft.replacingOccurrences(of: "\n", with: " ")).foregroundColor(C.muted)).font(font).lineLimit(1)
        } else {
            let l = r.last
            let mine = l.map { chatSame($0.from, me) && $0.kind != "sys" && $0.sys.isEmpty } ?? false
            if mine { ChatTicks(status: r.seen.isEmpty ? "sent" : "read") }
            Text(previewText(l, mine)).font(font).foregroundColor(un ? C.text : C.muted).lineLimit(1)
        }
        Spacer(minLength: 0)
    }
    private func previewText(_ l: ChatLast?, _ mine: Bool) -> String {
        guard let l else {
            if r.kind == "project" { return t("Project thread", "Mazungumzo ya mradi") }
            if r.announce { return t("News from the office", "Habari kutoka ofisini") }
            return t("No messages yet", "Bado hakuna ujumbe")
        }
        if l.kind == "sys" || !l.sys.isEmpty { return Lang.sw || l.text.isEmpty ? chatSysText(ChatSys(type: l.sys, by: l.from)) : l.text }
        let who = mine ? t("You: ", "Wewe: ") : (r.isGroup && !l.from.isEmpty ? ChatKit.first(l.from) + ": " : "")
        return who + chatPreview(l.kind, l.text, dur: l.dur)
    }
}

/// Mute for 8 hours, a week or always.
struct ChatMuteSheet: View {
    @ObservedObject var hub: ChatHub
    let c: String
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ChatSheetHead(title: t("Mute notifications", "Nyamazisha arifa"))
            Text(t("You’ll still see new messages in the app.", "Bado utaona ujumbe mpya ndani ya programu.")).font(.bSmall).foregroundColor(C.muted).padding(.bottom, 8)
            ChatSheetRow("clock", t("8 hours", "Saa 8")) { hub.mute(c, 8); dismiss() }
            ChatSheetRow("cal", t("1 week", "Wiki 1")) { hub.mute(c, 168); dismiss() }
            ChatSheetRow("mute", t("Always", "Daima")) { hub.mute(c, -1); dismiss() }
            Spacer(minLength: 0)
        }.padding(20)
    }
}

// MARK: - a new chat ------------------------------------------------------------------------------------------------------

/// New chat: a person from the team (a direct message), a new group (choose people, then name it), or join with a link.
struct ChatNewView: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var hub: ChatHub
    @State private var q = ""
    @State private var grouping = false
    @State private var chosen: [String] = []
    @State private var naming = false
    @State private var joining = false
    @State private var busy = false

    var body: some View {
        let me = hub.me
        let team = (m.team ?? []).filter { !chatSame($0.name, me) && !$0.name.isEmpty }
        let w = q.trimmingCharacters(in: .whitespaces).lowercased()
        let people = team.filter { w.isEmpty || $0.name.lowercased().contains(w) }
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 4) {
                    ChatRoundBtn(icon: "back", label: t("Back", "Rudi")) { if grouping { grouping = false; chosen = [] } else { m.back() } }
                    Text(grouping ? t("New group", "Kikundi kipya") : t("New chat", "Gumzo jipya")).font(.hMedium).foregroundColor(C.ink).lineLimit(1).minimumScaleFactor(0.8)
                }
                if grouping { Text(chosen.isEmpty ? t("Choose the people", "Chagua watu") : t("\(chosen.count) chosen", "\(chosen.count) wamechaguliwa")).font(.bMedium).foregroundColor(C.muted) }
                ChatSearchBox(q: $q, hint: t("Search the team", "Tafuta kwenye timu"))
                if !grouping {
                    CardV(pad: 6) {
                        ChatSheetRow("group", t("New group", "Kikundi kipya")) { grouping = true }
                        ChatSheetRow("link", t("Join with a link", "Jiunge kwa kiungo")) { joining = true }
                    }
                    Text(t("THE TEAM", "TIMU")).font(.mont(11, .heavy)).tracking(0.8).foregroundColor(C.muted).padding(.top, 8).padding(.leading, 4)
                }
                if m.team == nil { Text(t("Loading…", "Inapakia…")).font(.bMedium).foregroundColor(C.muted).padding(12) }
                ForEach(people) { p in person(p) }
                if grouping {
                    Btn(t("Next", "Endelea"), icon: "next", enabled: !chosen.isEmpty, wide: true) { naming = true }.padding(.top, 12)
                }
                Spacer().frame(height: 30)
            }.padding(16)
        }
        .task { if m.team == nil { await m.loadTeam() } }
        .sheet(isPresented: $naming) { ChatGroupNameSheet(hub: hub, members: chosen).environmentObject(m).storySheet([.medium, .large]) }
        .sheet(isPresented: $joining) { ChatJoinSheet(hub: hub).environmentObject(m).storySheet([.medium]) }
        .onAppear { if m.demo, Demo.arg("-sheet") == "group" { grouping = true; chosen = ["Meril Kiprop"] } }
    }
    private func person(_ p: Mate) -> some View {
        let on = chosen.contains(p.name)
        let pr = hub.presence[p.name]
        return Button {
            Haptic.tap()
            if grouping { if on { chosen.removeAll { $0 == p.name } } else { chosen.append(p.name) } }
            else if !busy { busy = true; Task { let (id, e) = await hub.openDm(p.name); busy = false; if let id { m.back(); m.go(Place(tab: m.here.tab, sub: "chat:" + id)) } else { hub.note = e } } }
        } label: {
            HStack(spacing: 12) {
                ChatAvatar(row: nil, size: 44, online: pr?.online == true, name: p.name)
                VStack(alignment: .leading, spacing: 2) {
                    Text(p.name).font(.tSmall).foregroundColor(C.ink).lineLimit(1)
                    let seen: String? = pr?.online == true ? t("online", "yupo mtandaoni") : pr.map { ChatKit.lastSeen($0.seen) }
                    let line: String = [countryName(p.home), seen].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
                    if !line.isEmpty { Text(line).font(.bSmall).foregroundColor(C.muted).lineLimit(1) }
                }
                Spacer(minLength: 4)
                if grouping {
                    Circle().fill(on ? ChatLook.online : Color.clear).frame(width: 24, height: 24)
                        .overlay(Circle().stroke(on ? ChatLook.online : C.edge, lineWidth: 2))
                        .overlay { if on { Icon("check", size: 14, color: .white) } }
                }
            }.padding(.horizontal, 8).frame(minHeight: 56).background(C.card).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)).contentShape(Rectangle())
        }.buttonStyle(.plain).accessibilityAddTraits(on ? .isSelected : [])
    }
}

/// A search box on the light background.
struct ChatSearchBox: View {
    @Binding var q: String; let hint: String
    var body: some View {
        HStack(spacing: 10) {
            Icon("search", size: 19, color: C.muted)
            TextField("", text: $q, prompt: Text(hint).foregroundColor(C.muted)).font(.bMedium).foregroundColor(C.text)
            if !q.isEmpty { Button { q = "" } label: { Icon("x", size: 16, color: C.muted).frame(width: 36, height: 36) }.buttonStyle(.plain).accessibilityLabel(t("Clear", "Futa")) }
        }
        .padding(.horizontal, 14).frame(minHeight: 46).background(C.card).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(C.line, lineWidth: 1))
    }
}

struct ChatGroupNameSheet: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var hub: ChatHub
    let members: [String]
    @State private var title = ""
    @State private var desc = ""
    @State private var busy = false
    @State private var err: String?
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ChatSheetHead(title: t("Name the group", "Kipe kikundi jina"))
                Field(t("Group name", "Jina la kikundi"), $title, placeholder: t("e.g. Kitale repairs", "mf. Matengenezo Kitale"))
                Field(t("Description (optional)", "Maelezo (si lazima)"), $desc, lines: 2)
                Btn(t("Create group", "Unda kikundi"), icon: "check", enabled: !title.trimmingCharacters(in: .whitespaces).isEmpty, wide: true, busy: busy ? t("Creating…", "Inaunda…") : nil) {
                    busy = true
                    Task {
                        let (id, e) = await hub.newGroup(title, members, desc)
                        busy = false
                        if let id { dismiss(); m.back(); m.go(Place(tab: m.here.tab, sub: "chat:" + id)) } else { err = e }
                    }
                }.padding(.top, 16)
                Msg(err, "bad")
            }.padding(20)
        }
    }
}

struct ChatJoinSheet: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var hub: ChatHub
    @State private var link = ""
    @State private var err: String?
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ChatSheetHead(title: t("Join with a link", "Jiunge kwa kiungo"))
            Field(t("Invite link", "Kiungo cha mwaliko"), $link)
            Btn(t("Join", "Jiunge"), icon: "check", enabled: ChatKit.joinToken(link) != nil, wide: true) {
                guard let tok = ChatKit.joinToken(link) else { return }
                Task { let (id, e) = await hub.join(tok); if let id { dismiss(); m.back(); m.go(Place(tab: m.here.tab, sub: "chat:" + id)) } else { err = e } }
            }.padding(.top, 16)
            Msg(err, "bad")
            Spacer(minLength: 0)
        }.padding(20)
    }
}

// MARK: - starred -----------------------------------------------------------------------------------------------------------

/// Messages starred in any chat; a tap opens the chat at that message.
struct ChatStarredView: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var hub: ChatHub
    @State private var list: [ChatMsg]?
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 4) {
                    ChatRoundBtn(icon: "back", label: t("Back", "Rudi")) { m.back() }
                    Text(t("Starred messages", "Ujumbe wenye nyota")).font(.hMedium).foregroundColor(C.ink).lineLimit(2).minimumScaleFactor(0.8)
                }
                if let l = list {
                    if l.isEmpty {
                        ChatEmpty(text: t("No starred messages. Hold a message and tap Star to keep it here.", "Hakuna ujumbe wenye nyota. Shikilia ujumbe na uguse Nyota kuuweka hapa."), action: t("Back to chats", "Rudi kwenye magumzo")) { m.back() }
                    }
                    ForEach(l) { msg in
                        Button { ChatNav.shared.focus = msg.id; m.go(Place(tab: m.here.tab, sub: "chat:" + msg.c)) } label: {
                            CardV(pad: 14) {
                                HStack {
                                    Text((chatSame(msg.from, hub.me) ? t("You", "Wewe") : msg.from) + " · " + (hub.row(msg.c).map { chatTitle($0, hub.me) } ?? "")).font(.lMedium).foregroundColor(C.muted).lineLimit(1)
                                    Spacer(minLength: 6)
                                    Text(ChatKit.when(msg.at)).font(.bSmall).foregroundColor(C.muted)
                                }
                                Text(msg.preview()).font(.bMedium).foregroundColor(C.text).lineLimit(4).padding(.top, 4).multilineTextAlignment(.leading)
                            }
                        }.buttonStyle(.plain)
                    }
                } else { Text(t("Loading…", "Inapakia…")).font(.bMedium).foregroundColor(C.muted) }
            }.padding(16)
        }
        .task(id: hub.tick) { list = await hub.starred() ?? [] }
    }
}

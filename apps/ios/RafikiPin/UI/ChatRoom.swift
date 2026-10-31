import SwiftUI
import UIKit

/// What the message list shows: a date pill, the "3 unread messages" line, or a bubble (first / last of a run).
enum ChatLine: Identifiable {
    case day(String, String)
    case unread(Int)
    case bubble(ChatMsg, Bool, Bool)
    var id: String {
        switch self { case .day(_, let k): return k; case .unread: return "unread"; case .bubble(let m, _, _): return m.key }
    }
    static func build(_ msgs: [ChatMsg], unreadFrom: String?, unreadN: Int) -> [ChatLine] {
        var out: [ChatLine] = []
        var day = ""
        for (i, m) in msgs.enumerated() {
            let d = ChatKit.dayKey(m.at)
            if d != day { day = d; out.append(.day(ChatKit.day(m.at), "d_" + d)) }
            if let u = unreadFrom, m.id == u, unreadN > 0 { out.append(.unread(unreadN)) }
            let prev: ChatMsg? = i > 0 ? msgs[i - 1] : nil
            let next: ChatMsg? = i + 1 < msgs.count ? msgs[i + 1] : nil
            out.append(.bubble(m, !same(prev, m), !same(next, m)))
        }
        return out
    }
    private static func same(_ a: ChatMsg?, _ b: ChatMsg) -> Bool {
        guard let a else { return false }
        return a.sys == nil && b.sys == nil && a.kind != "sys" && b.kind != "sys" && a.from == b.from && abs(a.at - b.at) < 5 * 60_000 && ChatKit.dayKey(a.at) == ChatKit.dayKey(b.at)
    }
}

/// One conversation: bubbles left and right in my chosen colour, the time and ticks, typing, swipe right to reply,
/// double-tap to react, hold for reactions and actions; date pills; the pinned messages' bar; "↓ 3 new"; "Seen by" in
/// groups; older messages load while scrolling up; selecting several; searching inside the chat.
struct ChatRoomView: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var hub: ChatHub
    let c: String

    @State private var reply: ChatMsg?
    @State private var editing: ChatMsg?
    @State private var held: ChatMsg?
    @State private var reactsOf: ChatMsg?
    @State private var infoOf: ChatMsg?
    @State private var viewer: Int?
    @State private var flash: String?
    @State private var atBottom = true
    @State private var awayAt: Double?
    @State private var loadingOlder = false
    @State private var unreadAtOpen = -1
    @State private var unreadFrom: String?
    @State private var selecting = false
    @State private var sel: Set<String> = []
    @State private var finding = false
    @State private var fq = ""
    @State private var fAt = 0
    @State private var sheet: String?
    @State private var delMsgs: [ChatMsg]?
    @State private var opened = false
    /// The chat has been put at its starting place (the newest message, or the first unread one); until then every
    /// change of the messages puts it there again, and older pages don't load.
    @State private var settled = false
    @State private var settleGen = 0

    var body: some View {
        let row = hub.row(c)
        let msgs = hub.threads[c] ?? hub.msgs(c)
        let me = hub.me
        VStack(spacing: 0) {
            top(row, me)
            if let row, !row.pins.isEmpty { ChatPinBar(row: row, msgs: msgs) { jump($0) } }
            ZStack(alignment: .bottom) {
                ChatWall(kind: row?.wallpaper ?? "").ignoresSafeArea(edges: .bottom)
                list(row, msgs, me)
                jumpButtons(msgs, me)
            }
            bottom(row, me)
        }
        .onAppear { open(row, msgs) }
        .onDisappear { if hub.openChat == c { hub.openChat = nil }; ChatPlayer.shared.stop(); hub.sendTyping(c, nil) }
        .task(id: c) { await hub.loadMsgs(c); await hub.refreshTrips(c) }
        .sheet(isPresented: isSet($held)) { if let msg = held { ChatMsgSheet(hub: hub, msg: msg, row: row, onReply: { reply = msg; editing = nil }, onEdit: { editing = msg; reply = nil }, onSelect: { selecting = true; sel = [msg.key] }, onDelete: { delMsgs = [msg] }, onInfo: { infoOf = msg }).environmentObject(m).storySheet([.medium, .large]) } }
        .sheet(isPresented: isSet($reactsOf)) { if let msg = reactsOf { ChatReactsSheet(hub: hub, msg: msg).environmentObject(m).storySheet([.medium]) } }
        .sheet(isPresented: isSet($infoOf)) { if let msg = infoOf { ChatInfoSheet(hub: hub, msg: msg, row: row).environmentObject(m).storySheet([.medium, .large]) } }
        .sheet(isPresented: isSet($delMsgs)) { if let l = delMsgs { ChatDeleteSheet(hub: hub, row: row, c: c, ms: l) { selecting = false; sel = [] }.environmentObject(m).storySheet([.medium]) } }
        .sheet(isPresented: isSet($sheet)) { sheetView(row, msgs) }
        .fullScreenCover(isPresented: isSet($viewer)) { if let v = viewer { ChatViewer(items: mediaAll(msgs), at: v).environmentObject(m) } }
    }

    private func open(_ row: ChatRow?, _ msgs: [ChatMsg]) {
        hub.openChat = c
        hub.markSeen(c)
        if unreadAtOpen < 0 {
            unreadAtOpen = row?.unread ?? 0
            if unreadAtOpen > 0 { unreadFrom = msgs.filter { !chatSame($0.from, hub.me) }.suffix(unreadAtOpen).first?.id }
        }
        if m.demo {
            switch Demo.arg("-sheet") ?? "" {
            case "msg": held = msgs.last { !chatSame($0.from, hub.me) && $0.sys == nil }
            case "select": selecting = true; if let l = msgs.last { sel = [l.key] }
            case "find": finding = true; fq = Demo.arg("-q") ?? "pump"
            // only the room's own sheets open here (the composer opens attach, live, contact and rec itself): an unknown
            // key must never present an empty sheet, which shows as a blank screen
            case "clear", "delete", "add", "media", "date", "mute": sheet = Demo.arg("-sheet")
            default: break
            }
        }
    }
    private func mediaAll(_ msgs: [ChatMsg]) -> [(ChatMsg, ChatMedia)] {
        msgs.filter { !$0.deleted }.flatMap { msg in msg.media.filter { $0.type == "photo" || $0.type == "video" }.map { (msg, $0) } }
    }
    private var found: [ChatMsg] {
        let w = fq.trimmingCharacters(in: .whitespaces).lowercased()
        guard !w.isEmpty else { return [] }
        return (hub.threads[c] ?? []).filter { !$0.deleted && $0.text.lowercased().contains(w) }.reversed()
    }

    // MARK: the top

    @ViewBuilder private func top(_ row: ChatRow?, _ me: String) -> some View {
        ChatNight(round: row?.kind == "project" ? 22 : 0) {
            if selecting { selBar }
            else if finding { findBar }
            else { ChatRoomHead(hub: hub, row: row, c: c, me: me, menu: { k in menuPick(k) }) }
            if let row, row.kind == "project", !row.project.isEmpty { ChatProjectStrip(pid: row.project) }
        }
    }
    private func menuPick(_ k: String) {
        switch k {
        case "search": finding = true
        case "select": selecting = true
        case "info": m.go(Place(tab: m.here.tab, sub: "chatinfo:" + c))
        default: sheet = k
        }
    }
    private var selBar: some View {
        let l = (hub.threads[c] ?? []).filter { sel.contains($0.key) }
        let one = l.count == 1 && !(l.first?.deleted ?? true) && !(l.first?.queued ?? true)
        return HStack(spacing: 2) {
            ChatRoundBtn(icon: "x", label: t("Stop selecting", "Acha kuchagua"), color: NightInk.ink) { selecting = false; sel = [] }
            Text(t("\(sel.count) selected", "\(sel.count) zimechaguliwa")).font(.mont(17, .heavy)).foregroundColor(NightInk.ink).lineLimit(1).minimumScaleFactor(0.8)
            Spacer(minLength: 4)
            ChatRoundBtn(icon: "fwd", label: t("Forward", "Sambaza"), color: one ? NightInk.ink : NightInk.muted) { if one { sheet = "fwd" } }.disabled(!one)
            ChatRoundBtn(icon: "star", label: t("Star", "Weka nyota"), color: NightInk.ink) {
                let on = !l.allSatisfy { $0.starred }
                Task { for x in l where !x.queued && !x.deleted { _ = await hub.star(x, on) } }
                selecting = false; sel = []
            }.disabled(l.isEmpty)
            ChatRoundBtn(icon: "trash", label: t("Delete", "Futa"), color: NightInk.ink) { if !l.isEmpty { delMsgs = l } }
        }.padding(.horizontal, 6).frame(minHeight: 58)
    }
    private var findBar: some View {
        let f = found
        return HStack(spacing: 2) {
            ChatRoundBtn(icon: "back", label: t("Close the search", "Funga utafutaji"), color: NightInk.ink) { finding = false; fq = "" }
            TextField("", text: $fq, prompt: Text(t("Search this chat", "Tafuta kwenye gumzo hili")).foregroundColor(NightInk.muted)).font(.bLarge).foregroundColor(NightInk.ink)
                .submitLabel(.search).onSubmit { fAt = 0; if let x = f.first { jump(x.id) } }
            if !fq.isEmpty { Text(f.isEmpty ? "0" : "\(fAt + 1)/\(f.count)").font(.mont(12, .bold)).foregroundColor(NightInk.muted).fixedSize() }
            ChatRoundBtn(icon: "up", label: t("Older", "Za zamani"), color: f.count > 1 ? NightInk.ink : NightInk.muted) { if !f.isEmpty { fAt = (fAt + 1) % f.count; jump(f[fAt].id) } }
            ChatRoundBtn(icon: "down", label: t("Newer", "Mpya zaidi"), color: f.count > 1 ? NightInk.ink : NightInk.muted) { if !f.isEmpty { fAt = (fAt - 1 + f.count) % f.count; jump(f[fAt].id) } }
        }.padding(.horizontal, 6).frame(minHeight: 58)
        .onChange(of: fq) { _ in fAt = 0; if let x = found.first { jump(x.id) } }
    }

    // MARK: the messages

    @State private var proxy: ScrollViewProxy?
    private func list(_ row: ChatRow?, _ msgs: [ChatMsg], _ me: String) -> some View {
        let lines = ChatLine.build(msgs, unreadFrom: unreadFrom, unreadN: max(0, unreadAtOpen))
        let group = row?.isGroup == true
        var findId: String? = nil
        if self.finding { let f = found; if f.indices.contains(fAt) { findId = f[fAt].id } }
        return GeometryReader { g in
            let maxW = min(g.size.width * 0.8, 560)
            let viewH = g.size.height
            let lastAt = msgs.last?.at ?? 0
            ScrollViewReader { sp in
                ScrollView {
                    LazyVStack(spacing: 0) {
                        Color.clear.frame(height: 1).onAppear { if settled { older(sp, msgs) } }
                        if loadingOlder || (hub.hasMore(c) && msgs.count >= 50) {
                            Text(t("Loading older…", "Inapakia za zamani…")).font(.lMedium).foregroundColor(C.muted).frame(maxWidth: .infinity).padding(10)
                        }
                        if msgs.isEmpty { ChatRoomEmpty(hub: hub, row: row, c: c, me: me) }
                        ForEach(lines) { line in
                            lineView(line, row: row, me: me, group: group, maxW: maxW, flashId: flash ?? findId, newest: msgs.last?.key, msgs: msgs).id(line.id)
                        }
                        // where the end of the list is, to know whether the reader is at (or within 150 points of) the bottom
                        Color.clear.frame(height: 4).id("bottom")
                            .background(GeometryReader { gp in Color.clear.preference(key: ChatBottomKey.self, value: gp.frame(in: .named("chatScroll")).maxY) })
                    }.padding(.horizontal, 8).padding(.top, 8)
                }
                .coordinateSpace(name: "chatScroll")
                .scrollDismissesKeyboard(.interactively)
                .onPreferenceChange(ChatBottomKey.self) { y in
                    let near = y.map { $0 - viewH <= 150 } ?? false
                    if near != atBottom { atBottom = near; awayAt = near ? nil : lastAt }
                }
                .onAppear {
                    proxy = sp
                    if let f = ChatNav.shared.focus { ChatNav.shared.focus = nil; settled = true; DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { jump(f) } }
                    else { settle(sp) }
                }
                .onChange(of: msgs.count) { _ in if !settled { settle(sp) } }
                .onChange(of: msgs.last?.key) { _ in
                    let now = hub.threads[c] ?? []
                    let mine = now.last.map { chatSame($0.from, hub.me) } ?? false
                    // a new message: follow it when the reader is at the bottom, or when it's my own; otherwise "↓ N new"
                    if settled && (atBottom || mine) {
                        DispatchQueue.main.async { withAnimation(Motion.on ? .easeOut(duration: 0.25) : nil) { sp.scrollTo("bottom", anchor: .bottom) } }
                    }
                    hub.markSeen(c)
                }
            }
        }
    }
    @ViewBuilder private func lineView(_ line: ChatLine, row: ChatRow?, me: String, group: Bool, maxW: CGFloat, flashId: String?, newest: String?, msgs: [ChatMsg]) -> some View {
        switch line {
        case .day(let label, _):
            Text(label).font(.mont(12, .bold)).foregroundColor(ChatLook.dayText).padding(.horizontal, 12).padding(.vertical, 4)
                .background(ChatLook.dayPill).clipShape(Capsule()).overlay(Capsule().stroke(ChatLook.sun ? Color.black : .clear, lineWidth: 1.5))
                .padding(.vertical, 8).frame(maxWidth: .infinity).accessibilityAddTraits(.isHeader)
        case .unread(let n):
            Text(n == 1 ? t("1 unread message", "Ujumbe 1 haujasomwa") : t("\(n) unread messages", "Ujumbe \(n) haujasomwa")).font(.mont(12, .bold)).foregroundColor(ChatLook.time)
                .padding(.horizontal, 12).padding(.vertical, 4).background(ChatLook.theirs).clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
                .shadow(color: .black.opacity(0.08), radius: 1, y: 1).padding(.vertical, 8).frame(maxWidth: .infinity)
        case .bubble(let msg, let first, let last):
            let seen: [String] = (group && msg.key == newest) ? (row?.seen ?? []).filter { !chatSame($0, me) && !chatSame($0, msg.from) } : []
            ChatBubbleLine(hub: hub, msg: msg, row: row, me: me, group: group, first: first, last: last, maxW: maxW, flash: flashId == msg.id, seen: seen,
                           selecting: selecting, selected: sel.contains(msg.key),
                           act: { a in act(a, msg, msgs) })
        }
    }
    private func act(_ a: String, _ msg: ChatMsg, _ msgs: [ChatMsg]) {
        switch a {
        case "reply": Haptic.tap(); reply = msg; editing = nil
        case "hold": Haptic.tap(); if selecting { toggle(msg) } else { held = msg }
        case "toggle": toggle(msg)
        case "double": if !msg.queued && !msg.deleted && msg.sys == nil { Haptic.tap(); Task { _ = await hub.react(msg, hub.settings.doubleTap) } }
        case "reacts": reactsOf = msg
        case "info": infoOf = msg
        case "retry": hub.retry(msg)
        default:
            if a.hasPrefix("media:") {
                let id = String(a.dropFirst(6))
                let all = mediaAll(msgs)
                viewer = all.firstIndex { $0.0.key == msg.key && ($0.1.id == id || $0.1.local == id) } ?? 0
            } else if a.hasPrefix("jump:") { jump(String(a.dropFirst(5))) }
        }
    }
    private func toggle(_ msg: ChatMsg) {
        if sel.contains(msg.key) { sel.remove(msg.key) } else { sel.insert(msg.key) }
        if sel.isEmpty { selecting = false }
    }
    /// Opens the chat at its newest message, or at the "N unread messages" line. The rows of a lazy list only exist after
    /// the first layout, so the scroll is repeated a few times as they appear (and when the newest page arrives).
    private func settle(_ sp: ScrollViewProxy) {
        let msgs = hub.threads[c] ?? []
        if unreadAtOpen > 0 && unreadFrom == nil && !msgs.isEmpty { unreadFrom = msgs.filter { !chatSame($0.from, hub.me) }.suffix(unreadAtOpen).first?.id }
        settleGen += 1
        let gen = settleGen
        let toUnread = unreadAtOpen > 0 && unreadFrom != nil
        for (i, d) in [0.0, 0.05, 0.15, 0.35, 0.6].enumerated() {
            DispatchQueue.main.asyncAfter(deadline: .now() + d) {
                guard gen == settleGen, !settled else { return }
                if toUnread { sp.scrollTo("unread", anchor: .top) } else { sp.scrollTo("bottom", anchor: .bottom) }
                if i == 4 && !msgs.isEmpty { settled = true }
            }
        }
    }
    /// Older messages while scrolling up; the message that was at the top stays where it was.
    private func older(_ sp: ScrollViewProxy, _ msgs: [ChatMsg]) {
        guard settled, !loadingOlder, hub.hasMore(c), !msgs.isEmpty else { return }
        loadingOlder = true
        let anchor = msgs.first?.key
        Task {
            await hub.loadOlder(c)
            if let anchor { sp.scrollTo(anchor, anchor: .top); DispatchQueue.main.async { sp.scrollTo(anchor, anchor: .top) } }
            try? await Task.sleep(nanoseconds: 300_000_000)
            loadingOlder = false
        }
    }
    /// Scroll to a message (a tapped quote, a search hit, a starred or pinned one), loading older pages if needed.
    private func jump(_ id: String) {
        Task {
            var tries = 0
            while !(hub.threads[c] ?? []).contains(where: { $0.id == id }) && hub.hasMore(c) && tries < 10 { _ = await hub.loadOlder(c); tries += 1 }
            guard let msg = (hub.threads[c] ?? []).first(where: { $0.id == id }) else { return }
            withAnimation(Motion.on ? .easeInOut(duration: 0.3) : nil) { proxy?.scrollTo(msg.key, anchor: .center) }
            flash = id
            try? await Task.sleep(nanoseconds: 1_600_000_000)
            if flash == id { flash = nil }
        }
    }

    @ViewBuilder private func jumpButtons(_ msgs: [ChatMsg], _ me: String) -> some View {
        let newer = awayAt.map { a in msgs.filter { $0.at > a && !chatSame($0.from, me) }.count } ?? 0
        if !atBottom {
            HStack {
                Spacer()
                if newer > 0 {
                    Button { withAnimation { proxy?.scrollTo("bottom", anchor: .bottom) } } label: {
                        Text("↓ " + (newer == 1 ? t("1 new", "1 mpya") : t("\(newer) new", "\(newer) mpya"))).font(.mont(14, .heavy)).foregroundColor(.white)
                            .padding(.horizontal, 14).frame(minHeight: 40).background(ChatLook.online).clipShape(Capsule()).shadow(radius: 4, y: 2)
                    }.buttonStyle(.plain)
                    Spacer()
                } else {
                    Button { withAnimation { proxy?.scrollTo("bottom", anchor: .bottom) } } label: {
                        Icon("down", size: 22, color: C.ink).frame(width: 44, height: 44).background(C.card).clipShape(Circle()).shadow(radius: 4, y: 2)
                    }.buttonStyle(.plain).accessibilityLabel(t("Jump to the newest", "Nenda kwa mpya zaidi")).padding(.trailing, 14)
                }
            }.padding(.bottom, 12)
        }
    }

    // MARK: the bottom

    @ViewBuilder private func bottom(_ row: ChatRow?, _ me: String) -> some View {
        if hub.off { ChatOffCard(need: hub.offNeed).padding(12) }
        else if let row, row.onlyAdmins && !row.admin(me) {
            Text(row.announce ? t("Only admins post announcements", "Wasimamizi pekee ndio hutuma matangazo") : t("Only admins can send messages", "Wasimamizi pekee wanaweza kutuma ujumbe"))
                .font(.bMedium).foregroundColor(C.muted).multilineTextAlignment(.center).frame(maxWidth: .infinity).padding(18).background(C.card)
        } else {
            ChatComposer(hub: hub, c: c, row: row, reply: $reply, editing: $editing)
        }
    }

    @ViewBuilder private func sheetView(_ row: ChatRow?, _ msgs: [ChatMsg]) -> some View {
        switch sheet ?? "" {
        case "fwd":
            if let msg = msgs.first(where: { sel.contains($0.key) }) { ChatForwardSheet(hub: hub, msgs: [msg]) { selecting = false; sel = [] }.environmentObject(m).storySheet([.large]) }
        case "clear", "delete":
            if let row { ChatClearSheet(hub: hub, row: row, del: sheet == "delete").environmentObject(m).storySheet([.medium]) }
        case "add":
            if let row { ChatAddPeopleSheet(hub: hub, row: row).environmentObject(m).storySheet([.large]) }
        case "media": ChatMediaFilesSheet(hub: hub, msgs: msgs) { i in sheet = nil; DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { viewer = i } }.environmentObject(m).storySheet([.large])
        case "date": ChatDateSheet { d in sheet = nil; goTo(d) }.environmentObject(m).storySheet([.medium, .large])
        case "mute": ChatMuteSheet(hub: hub, c: c).environmentObject(m).storySheet([.medium])
        default: EmptyView()
        }
    }
    /// Go to a date: older pages load until that day is on the phone, then its first message.
    private func goTo(_ day: Date) {
        let key = When.localDay(day)
        Task {
            var tries = 0
            while let f = hub.threads[c]?.first, ChatKit.dayKey(f.at) > key, hub.hasMore(c), tries < 20 { _ = await hub.loadOlder(c); tries += 1 }
            if let x = (hub.threads[c] ?? []).first(where: { ChatKit.dayKey($0.at) >= key }) { jump(x.id) }
        }
    }
}

/// The chat's head: back, its picture, name and what's happening (online, last seen, typing, members). A tap opens the info page.
struct ChatRoomHead: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var hub: ChatHub
    let row: ChatRow?; let c: String; let me: String; let menu: (String) -> Void
    var body: some View {
        HStack(spacing: 4) {
            ChatRoundBtn(icon: "back", label: t("Back", "Rudi"), color: NightInk.ink) { m.back() }
            Button { m.go(Place(tab: m.here.tab, sub: "chatinfo:" + c)) } label: {
                HStack(spacing: 10) {
                    let other = row?.other(me) ?? ""
                    ChatAvatar(row: row, me: me, size: 40, online: row?.kind == "dm" && hub.presence[other]?.online == true)
                    VStack(alignment: .leading, spacing: 1) {
                        HStack(spacing: 4) {
                            Text(row.map { chatTitle($0, me) } ?? t("Chat", "Gumzo")).font(.mont(16, .heavy)).foregroundColor(NightInk.ink).lineLimit(1)
                            if row?.muted() == true { CIcon("mute", size: 15, color: NightInk.muted) }
                        }
                        Text(sub).font(.mont(12, .medium)).foregroundColor(hub.typingText(c, group: row?.isGroup == true) != nil ? (ChatLook.deep ? ChatLook.mint : ChatLook.un) : NightInk.muted).lineLimit(1)
                    }
                    Spacer(minLength: 0)
                }.contentShape(Rectangle())
            }.buttonStyle(.plain).accessibilityHint(t("Chat info", "Maelezo ya gumzo"))
            Menu {
                Button { menu("search") } label: { Label(t("Search", "Tafuta"), systemImage: "magnifyingglass") }
                Button { menu("date") } label: { Label(t("Go to a date", "Nenda kwenye tarehe"), systemImage: "calendar") }
                Button { menu("select") } label: { Label(t("Select messages", "Chagua jumbe"), systemImage: "checkmark.circle") }
                Button { menu("media") } label: { Label(t("Media, files and links", "Picha, faili na viungo"), systemImage: "photo.on.rectangle") }
                if let r = row, r.isGroup, r.kind != "team", !r.announce {
                    Button { menu("add") } label: { Label(r.admin(me) || m.snap?.admin == true ? t("Add people", "Ongeza watu") : t("Ask to add someone", "Omba kuongeza mtu"), systemImage: "person.badge.plus") }
                }
                Button { menu("info") } label: { Label(t("Chat info", "Maelezo ya gumzo"), systemImage: "info.circle") }
                Button(role: .destructive) { menu("clear") } label: { Label(t("Clear chat", "Futa jumbe"), systemImage: "trash") }
                if row?.kind == "dm" { Button(role: .destructive) { menu("delete") } label: { Label(t("Delete chat", "Futa gumzo"), systemImage: "trash") } }
            } label: { CIcon("more", size: 22, color: NightInk.ink).frame(width: 44, height: 44).contentShape(Rectangle()) }
            .accessibilityLabel(t("More", "Zaidi"))
        }.padding(.horizontal, 6).frame(minHeight: 58)
    }
    private var sub: String {
        guard let row else { return "" }
        if let typed = hub.typingText(c, group: row.isGroup) { return typed }
        if row.kind == "dm" { let p = hub.presence[row.other(me)]; return p?.online == true ? t("online", "yupo mtandaoni") : ChatKit.lastSeen(p?.seen ?? 0) }
        if row.announce { return t("Everyone on the team · admins post", "Timu nzima · wasimamizi wanatuma") }
        if row.kind == "project" { return t("\(row.members.count) people", "watu \(row.members.count)") }
        let others: [String] = row.members.filter { !chatSame($0, me) }
        let mine: [String] = row.members.filter { chatSame($0, me) }
        return (others.map { ChatKit.first($0) } + mine.map { _ in t("You", "Wewe") }).joined(separator: ", ")
    }
}

/// A project thread's strip under the head: how the water point is doing, and "Open project ›".
struct ChatProjectStrip: View {
    @EnvironmentObject var m: AppModel
    let pid: String
    var body: some View {
        if let snap = m.snap, let p = snap.projects.first(where: { $0.id == pid }) {
            let rep = snap.repairs[pid]
            let st: (String, Color) = rep?.state == "notworking" ? (t("Not working", "Haifanyi kazi"), Color(hex: 0xFF8A80)) : rep != nil ? (t("Needs repair", "Inahitaji matengenezo"), Color(hex: 0xF2B35B)) : (t("Working", "Inafanya kazi"), ChatLook.mint)
            Button { m.go(Place(tab: m.here.tab, project: pid, projectTab: "pin")) } label: {
                HStack(spacing: 8) {
                    Circle().fill(ChatLook.deep ? st.1 : (rep == nil ? C.ok : C.warn)).frame(width: 9, height: 9)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(st.0).font(.mont(13, .bold)).foregroundColor(NightInk.ink).lineLimit(1)
                        let v = snap.visits[pid]
                        let line = [v.map { t("Visited ", "Ilitembelewa ") + When.text($0.at) }, p.area.isEmpty ? nil : p.area].compactMap { $0 }.joined(separator: " · ")
                        if !line.isEmpty { Text(line).font(.mont(11, .medium)).foregroundColor(NightInk.muted).lineLimit(1) }
                    }
                    Spacer(minLength: 4)
                    Text(t("Open project ›", "Fungua mradi ›")).font(.mont(13, .heavy)).foregroundColor(ChatLook.deep ? ChatLook.mint : C.blue).lineLimit(1).fixedSize()
                }.padding(.horizontal, 16).padding(.bottom, 12).padding(.top, 2).contentShape(Rectangle())
            }.buttonStyle(.plain)
        }
    }
}

/// The pinned messages' bar: a tap goes to the pinned message and on to the next.
struct ChatPinBar: View {
    let row: ChatRow; let msgs: [ChatMsg]; let jump: (String) -> Void
    @State private var i = 0
    var body: some View {
        let pins = row.pins
        let p = pins[i % max(1, pins.count)]
        let text: String = { let x = msgs.first { $0.id == p.id }?.preview() ?? ""; return x.isEmpty ? (p.text.isEmpty ? t("Pinned message", "Ujumbe uliobandikwa") : p.text) : x }()
        Button { jump(p.id); i += 1 } label: {
            HStack(spacing: 10) {
                Rectangle().fill(ChatLook.online).frame(width: 3).clipShape(Capsule())
                VStack(alignment: .leading, spacing: 1) {
                    Text(pins.count > 1 ? t("Pinned message \(i % pins.count + 1) of \(pins.count)", "Ujumbe uliobandikwa \(i % pins.count + 1) kati ya \(pins.count)") : t("Pinned message", "Ujumbe uliobandikwa"))
                        .font(.mont(12, .heavy)).foregroundColor(ChatLook.un).lineLimit(1)
                    Text(text).font(.bSmall).foregroundColor(C.text).lineLimit(1)
                }
                Spacer(minLength: 4)
                Icon("pin", size: 16, color: C.muted)
            }.padding(.horizontal, 14).padding(.vertical, 6).frame(minHeight: 50).background(C.card).contentShape(Rectangle())
        }.buttonStyle(.plain)
        .overlay(alignment: .bottom) { Rectangle().fill(C.line).frame(height: 1) }
    }
}

/// A chat with no messages yet: "Say hello to the Kenya team 👋", with a button that does.
struct ChatRoomEmpty: View {
    @ObservedObject var hub: ChatHub
    let row: ChatRow?; let c: String; let me: String
    var body: some View {
        let canPost = row == nil || !(row?.onlyAdmins ?? false) || (row?.admin(me) ?? false)
        let name = row.map { chatTitle($0, me) } ?? ""
        let text: String = {
            if row?.announce == true { return canPost ? t("No announcements yet. Write the first one for the whole team.", "Bado hakuna matangazo. Andika la kwanza kwa timu nzima.") : t("No announcements yet. The office posts news here.", "Bado hakuna matangazo. Ofisi huweka habari hapa.") }
            if row?.kind == "dm" { return t("Say hello to \(ChatKit.first(name)) 👋", "Msalimie \(ChatKit.first(name)) 👋") }
            if row?.kind == "team" { return t("Say hello to the \(name) 👋", "Isalimie \(name) 👋") }
            if !name.isEmpty { return t("Say hello to \(name) 👋", "Salimia \(name) 👋") }
            return t("No messages yet. Say hello!", "Bado hakuna ujumbe. Salimia!")
        }()
        ChatEmpty(text: text, action: canPost && row?.announce != true ? t("Say hello 👋", "Salimia 👋") : nil) { hub.send(c, kind: "text", text: "👋") }
            .background(C.card.opacity(0.85)).clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous)).padding(.horizontal, 20).padding(.top, 30)
    }
}

/// Pick a day to go to.
struct ChatDateSheet: View {
    let go: (Date) -> Void
    @State private var d = Date()
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ChatSheetHead(title: t("Go to a date", "Nenda kwenye tarehe"))
            DatePicker("", selection: $d, in: ...Date(), displayedComponents: .date).datePickerStyle(.graphical).labelsHidden()
                .environment(\.locale, Locale(identifier: Lang.sw ? "sw" : "en_GB"))
            Btn(t("Go", "Nenda"), icon: "next", wide: true) { go(d) }
        }.padding(20)
    }
}

/// Where the bottom of a chat's message list is, in the scroll view's own space (nil while it isn't laid out).
struct ChatBottomKey: PreferenceKey {
    static let defaultValue: CGFloat? = nil
    static func reduce(value: inout CGFloat?, nextValue: () -> CGFloat?) { if let n = nextValue() { value = n } }
}

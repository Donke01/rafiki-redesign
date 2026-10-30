import Combine
import Foundation
import UIKit
import UserNotifications

/// Chat for the whole app: the list, each open chat's messages, who is online or typing, and the live connection.
/// Everything is drawn from what is kept on the phone first, then brought up to date from the server.
///  - Real time: a WebSocket while the app is open, reconnecting (1 s … 30 s); after each reconnect it catches up.
///    Without it (no signal, a proxy), the list and the open chat are checked every 20 seconds instead.
///  - Sending: every message goes to the outbox first (a clock until the server has it), then out; it survives the app
///    being closed and goes when there is signal again.
///  - iPhone has no Firebase here: a message that arrives while the app is in the background (the first half minute
///    after leaving it) becomes a notification on this phone, as Android does without FCM.
@MainActor
final class ChatHub: ObservableObject {
    private static var one: ChatHub?
    static func of(_ m: AppModel) -> ChatHub {
        if let h = one { return h }
        if m.demo { DemoChat.install(m.api) }
        let h = ChatHub(api: m.api, name: { [weak m] in m?.prefs.name ?? "" }, demo: m.demo)
        one = h
        // signed out: the chats kept on this phone go (messages waiting to send stay)
        m.$signedIn.dropFirst().sink { [weak h] on in if !on { Task { @MainActor in h?.forget() } } }.store(in: &h.bag)
        return h
    }

    let api: Api
    let demo: Bool
    private let myName: () -> String
    let dir: URL
    let outbox: ChatOutbox
    let cache: ChatCache
    let settings: ChatSettings
    private let d: UserDefaults

    @Published var rows: [ChatRow] = []
    @Published var presence: [String: ChatPresence] = [:]
    /// The server says chat isn't set up yet (reason chat-off).
    @Published var off = false
    var offNeed: [String] = []
    /// The list was loaded (from the phone or the server) at least once.
    @Published var ready = false
    @Published var online = false
    /// Unread messages in chats that aren't muted: the badge on the header's Chat button.
    @Published var unread = 0
    /// Who is typing or recording, per chat: name → (what, until ms).
    @Published var typing: [String: [String: ChatTyping]] = [:]
    /// Each chat's messages, oldest first.
    @Published var threads: [String: [ChatMsg]] = [:]
    /// The last thing that went wrong (or went well), for a short note on screen.
    @Published var note: String?
    /// Bumped when something outside a message list changed (stars, settings).
    @Published var tick = 0
    /// Translations and typed-out voice notes, kept while the app runs (by message id).
    @Published var translated: [String: String] = [:]
    @Published var typedOut: [String: String] = [:]
    /// The server's extra tools: translating and typing out voice notes.
    var tools: [String: Bool] = ["translate": false, "transcribe": false]
    /// Where each chat was scrolled to (the message id at the bottom of the screen).
    var scrollAt: [String: String] = [:]
    /// Half-written messages, per chat.
    var drafts: [String: String] = [:]

    /// The chat on screen right now (its messages are read as they arrive).
    var openChat: String?
    private var front = true
    private var more: [String: Bool] = [:]
    private var lastSync: Double = 0
    private var me0 = ""
    private var started = false
    private var listBusy = false
    private var ws: URLSessionWebSocketTask?
    private var connTask: Task<Void, Never>?
    private var poll: Timer?
    private var bag: Set<AnyCancellable> = []
    private var bgTask: UIBackgroundTaskIdentifier = .invalid

    /// The name the server knows me by (falls back to the app's).
    var me: String { me0.isEmpty ? myName() : me0 }

    private init(api: Api, name: @escaping () -> String, demo: Bool) {
        self.api = api; self.myName = name; self.demo = demo
        let dir = Prefs.dataDir.appendingPathComponent("chat", isDirectory: true)
        self.dir = dir
        let ud = UserDefaults(suiteName: demo ? "rafiki-demo-chat" : "rafiki-chat") ?? .standard
        d = ud
        settings = ChatSettings(ud)
        outbox = ChatOutbox(file: dir.appendingPathComponent(demo ? "outbox-demo.json" : "outbox.json"))
        cache = ChatCache(dir: dir.appendingPathComponent(demo ? "demo" : "kept", isDirectory: true))
        let nc = NotificationCenter.default
        nc.publisher(for: UIApplication.didBecomeActiveNotification).sink { [weak self] _ in Task { @MainActor in self?.resume() } }.store(in: &bag)
        nc.publisher(for: UIApplication.didEnterBackgroundNotification).sink { [weak self] _ in Task { @MainActor in self?.pause() } }.store(in: &bag)
        Locator.shared.$fix.sink { [weak self] f in guard let f else { return }; Task { @MainActor in self?.onFix(f) } }.store(in: &bag)
        ChatNotify.setUp()
    }

    // MARK: start, stop, the list

    /// The Chat parts of the app opened: show what's on the phone, then fetch, connect and send what waits.
    func start() {
        if !started {
            started = true
            if let l = cache.loadList() { rows = l.0; me0 = l.1; presence = l.2; ready = true; recount() }
            poll = Timer.scheduledTimer(withTimeInterval: 20, repeats: true) { [weak self] _ in
                Task { @MainActor in
                    guard let self, self.front, !self.online, self.started else { return }
                    await self.catchUp()
                }
            }
        }
        Task { _ = await refreshList(full: lastSync == 0); await flush() }
        resume()
    }
    /// The app came to the front: connect.
    func resume() {
        front = true
        endBackground()
        guard started else { return }
        connect()
        if let c = openChat { markSeen(c) }
    }
    /// The app went to the background: the socket stays as long as iPhone lets the app finish (about half a minute), and
    /// a message then is a notification at once.
    func pause() {
        front = false
        guard started, !demo else { return }
        bgTask = UIApplication.shared.beginBackgroundTask(withName: "chat") { [weak self] in Task { @MainActor in self?.close(); self?.endBackground() } }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 25_000_000_000)
            if !self.front { self.close(); self.endBackground() }
        }
    }
    private func endBackground() { if bgTask != .invalid { UIApplication.shared.endBackgroundTask(bgTask); bgTask = .invalid } }
    private func close() { connTask?.cancel(); connTask = nil; ws?.cancel(with: .goingAway, reason: nil); ws = nil; online = false }

    /// Signed out: forget the chats kept on this phone (messages waiting to send stay).
    func forget() { close(); cache.clear(); rows = []; threads = [:]; ready = false; started = false; lastSync = 0; unread = 0; poll?.invalidate(); poll = nil }

    @discardableResult func refreshList(full: Bool = true) async -> Bool {
        if listBusy { return false }
        listBusy = true; defer { listBusy = false }
        let r = await api.chatList(since: full ? 0 : lastSync)
        if !r.ok {
            if r.reason == "chat-off" { off = true; ready = true; offNeed = chatStrings(r.json["need"]) }
            else if r.status != 0 && r.status != 401 { note = chatReason(r.reason) }
            return false
        }
        off = false
        let fresh = r.json.objs("chats").map(ChatRow.from).filter { !$0.id.isEmpty }
        let who = chatS(r.json, "me"); if !who.isEmpty { me0 = who }
        if let p = r.json.obj("prefs") { settings.fromServer(p); tick += 1 }
        if let tl = r.json.obj("tools") { tools = ["translate": tl.bool("translate"), "transcribe": tl.bool("transcribe")] }
        let gone = Set(chatStrings(r.json["gone"]))
        rows = (full ? fresh : merge(rows, fresh)).filter { !gone.contains($0.id) }
        let p = ChatCache.presence(r.json.obj("presence"))
        if full { presence = p } else { presence.merge(p) { _, n in n } }
        let n = chatMs(r.json["now"]); lastSync = n > 0 ? n : chatNow() - 5000
        ready = true
        recount(); save()
        // delivered: everything new in the list reached this phone
        let api = self.api, me = self.me
        for row in fresh where row.unread > 0 { if let l = row.last, !chatSame(l.from, me) { Task { _ = await api.chatDelivered(row.id, l.id) } } }
        return true
    }

    private func merge(_ old: [ChatRow], _ fresh: [ChatRow]) -> [ChatRow] {
        var byId: [String: ChatRow] = [:]; fresh.forEach { byId[$0.id] = $0 }
        let kept: [ChatRow] = old.map { o in
            guard var n = byId[o.id] else { return o }
            if n.pphoto.isEmpty && !o.pphoto.isEmpty { n.pphoto = o.pphoto }
            return n
        }
        let ids = Set(old.map(\.id))
        return kept + fresh.filter { !ids.contains($0.id) }
    }
    private func save() { cache.saveList(rows, me: me0, presence: presence) }
    private func recount() { let now = chatNow(); unread = rows.filter { !$0.muted(now) }.reduce(0) { $0 + $1.unread } }
    private func edit(_ c: String, _ f: (inout ChatRow) -> Void) {
        rows = rows.map { r in var x = r; if r.id == c { f(&x) }; return x }
        recount(); save()
    }
    func row(_ c: String) -> ChatRow? { rows.first { $0.id == c } }

    /// The list order: Announcements, then pinned chats, then the newest message.
    func sorted(_ list: [ChatRow]) -> [ChatRow] {
        list.sorted { a, b in
            if a.announce != b.announce { return a.announce }
            if a.pinned != b.pinned { return a.pinned }
            return (a.last?.at ?? 0) > (b.last?.at ?? 0)
        }
    }

    // MARK: opening a chat

    /// A direct message with someone: the one on the phone, or a new one from the server. Returns (id, error).
    func openDm(_ with: String) async -> (String?, String?) {
        if let r = rows.first(where: { $0.kind == "dm" && $0.members.contains { chatSame($0, with) } && $0.members.count <= 2 }) { return (r.id, nil) }
        let r = await api.chatOpenDm(with)
        if !r.ok { if r.reason == "chat-off" { off = true }; return (nil, r.status == 0 ? t("Starting a new chat needs signal.", "Kuanzisha gumzo jipya kunahitaji mtandao.") : chatReason(r.reason)) }
        await refreshList(full: false); return (r.json.str("id"), nil)
    }
    /// The project's thread.
    func openProject(_ pid: String) async -> (String?, String?) {
        if let r = rows.first(where: { $0.kind == "project" && $0.project == pid }) { return (r.id, nil) }
        let r = await api.chatOpenProject(pid)
        if !r.ok { if r.reason == "chat-off" { off = true }; return (nil, r.status == 0 ? t("Opening the project’s chat the first time needs signal.", "Kufungua gumzo la mradi mara ya kwanza kunahitaji mtandao.") : chatReason(r.reason)) }
        await refreshList(full: false); return (r.json.str("id"), nil)
    }

    // MARK: messages

    /// A chat's messages, oldest first: kept ones and waiting ones straight away.
    func msgs(_ c: String) -> [ChatMsg] {
        if let l = threads[c] { return l }
        let l = withWaiting(c, cache.loadMsgs(c))
        DispatchQueue.main.async { if self.threads[c] == nil { self.threads[c] = l } }
        return l
    }
    func hasMore(_ c: String) -> Bool { more[c] ?? true }
    private func current(_ c: String) -> [ChatMsg] { threads[c] ?? withWaiting(c, cache.loadMsgs(c)) }
    private func withWaiting(_ c: String, _ server: [ChatMsg]) -> [ChatMsg] {
        let cids = Set(server.map(\.cid).filter { !$0.isEmpty })
        let waiting = outbox.forChat(c).map { $0.asMsg(me) }.filter { !cids.contains($0.cid) }
        return (server + waiting).sorted { a, b in a.queued != b.queued ? !a.queued : a.at < b.at }
    }
    private func setThread(_ c: String, _ list: [ChatMsg]) { let l = unique(list); threads[c] = l; cache.saveMsgs(c, l) }
    /// One bubble per message: a waiting copy and the server's copy of the same message never both show.
    private func unique(_ list: [ChatMsg]) -> [ChatMsg] {
        if Set(list.map(\.key)).count == list.count { return list }
        var best: [String: ChatMsg] = [:]
        for m in list { if let b = best[m.key], !b.queued, m.queued { continue }; best[m.key] = m }
        var done = Set<String>(); var out: [ChatMsg] = []
        for m in list where !done.contains(m.key) { done.insert(m.key); if let b = best[m.key] { out.append(b) } }
        return out
    }

    /// The newest page from the server (on opening a chat, and after a reconnect).
    @discardableResult func loadMsgs(_ c: String) async -> Bool {
        let r = await api.chatMsgs(c)
        if !r.ok {
            if r.reason == "chat-off" { off = true }
            if r.reason == "not-member" || r.reason == "removed" || r.status == 403 { note = chatReason(r.reason); rows = rows.filter { $0.id != c }; save(); recount() }
            return false
        }
        guard r.json["msgs"] != nil else { return false }
        let fresh = r.json.objs("msgs").map { ChatMsg.from($0, chat: c) }
        let old = current(c).filter { !$0.queued }
        let oldest = fresh.map(\.at).min() ?? .infinity
        let freshIds = Set(fresh.map(\.id))
        let keep = old.filter { $0.at < oldest && !freshIds.contains($0.id) }
        if more[c] == nil { more[c] = r.json.bool("more") }
        setThread(c, withWaiting(c, keepLocal(keep + fresh, old)))
        markSeen(c)
        return true
    }
    /// Files already on this phone stay attached (no second download of a photo just sent).
    private func keepLocal(_ list: [ChatMsg], _ old: [ChatMsg]) -> [ChatMsg] {
        list.map { m in
            guard let o = old.first(where: { $0.id == m.id }) else { return m }
            var x = m
            x.media = m.media.map { md in var y = md; if y.local.isEmpty { y.local = o.media.first { $0.id == md.id }?.local ?? "" }; return y }
            x.starred = m.starred || o.starred
            return x
        }
    }
    /// Older messages (scrolling up). Returns false when there are none.
    @discardableResult func loadOlder(_ c: String) async -> Bool {
        guard hasMore(c) else { return false }
        let cur = current(c).filter { !$0.queued }
        guard let first = cur.first else { return await loadMsgs(c) }
        let r = await api.chatMsgs(c, before: first.id)
        guard r.ok, r.json["msgs"] != nil else { return false }
        let ids = Set(cur.map(\.id))
        let older = r.json.objs("msgs").map { ChatMsg.from($0, chat: c) }.filter { !ids.contains($0.id) }
        more[c] = r.json.bool("more") && !older.isEmpty
        setThread(c, withWaiting(c, older + cur))
        return !older.isEmpty
    }

    /// The chat is on screen: its messages are read (blue ticks for the sender) and its notifications go.
    func markSeen(_ c: String) {
        guard openChat == c, front else { return }
        if (row(c)?.unread ?? 0) > 0 || row(c)?.mention == true { edit(c) { $0.unread = 0; $0.mention = false } }
        guard let last = current(c).last(where: { !$0.queued && !chatSame($0.from, me) }) else { return }
        let key = "read_" + c
        if d.string(forKey: key) == last.id { return }
        d.set(last.id, forKey: key)
        let api = self.api
        Task { _ = await api.chatRead(c, last.id) }
        ChatNotify.clear(c)
    }

    // MARK: sending

    /// Puts a message in the outbox (a clock in the chat), then sends it.
    @discardableResult
    func send(_ c: String, kind: String, text: String = "", files: [ChatMedia] = [], reply: ChatMsg? = nil, mentions: [String] = [],
              loc: LatLng? = nil, liveMinutes: Int = 0, contact: String? = nil, fwd: Bool = false, trip: ChatTripCard? = nil) -> String {
        let cid = "a" + UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(20).lowercased()
        var body: JSON = ["kind": kind]
        let txt = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !txt.isEmpty { body["text"] = txt }
        if let reply { body["reply"] = reply.id; body["_reply"] = ChatQuote(id: reply.id, from: reply.from, kind: reply.kind, text: reply.preview()).json() }
        if !mentions.isEmpty { body["mentions"] = mentions }
        if let loc { body["loc"] = ["lat": loc.lat, "lng": loc.lng] as JSON }
        let until = chatNow() + Double(liveMinutes) * 60_000
        if liveMinutes > 0 { body["live"] = ["until": until] as JSON }
        if let contact { body["contact"] = ["name": contact] as JSON }
        if fwd { body["fwd"] = true }
        if let trip { body["trip"] = trip.json() }
        let item = ChatOutbox.Item(cid: cid, c: c, body: body, files: files, at: chatNow())
        outbox.add(item)
        let m = item.asMsg(me)
        threads[c] = unique(current(c) + [m])
        let preview: String = kind == "contact" ? (contact ?? "") : kind == "doc" ? (files.first?.name ?? "") : kind == "trip" ? (trip?.title ?? "") : txt
        let meNow = me
        edit(c) { r in r.last = ChatLast(id: cid, from: meNow, kind: kind, text: preview, at: m.at, dur: files.first?.dur ?? 0); r.archived = false; r.seen = [] }
        if kind == "live", loc != nil { d.set(c + "|" + String(Int64(until)), forKey: "live_" + cid) }
        Task { await flush() }
        return cid
    }

    private var flushing = false
    /// Sends what waits; a sent message replaces its waiting bubble (same place, ticks instead of the clock).
    func flush() async {
        if flushing || outbox.count() == 0 { return }
        flushing = true; defer { flushing = false }
        let res = await outbox.flush(api)
        settings.count(res.uploaded)
        if res.off { off = true }
        for (cid, m) in res.sent {
            let list = current(m.c)
            var out: [ChatMsg] = list.map { it in
                guard it.cid == cid || (it.id == cid && it.queued) else { return it }
                var x = m; x.cid = cid
                x.media = m.media.map { md in var y = md; if y.local.isEmpty { y.local = it.media.first { $0.type == md.type }?.local ?? "" }; return y }
                return x
            }
            if out.filter({ $0.id == m.id && !$0.queued }).count > 1 { var seen = Set<String>(); out = out.filter { seen.insert($0.id).inserted } }
            setThread(m.c, out)
            if let v = d.string(forKey: "live_" + cid) { d.removeObject(forKey: "live_" + cid); d.set(v, forKey: "live_" + m.id) }
            edit(m.c) { r in if let l = r.last, l.id == cid { r.last = ChatLast(id: m.id, from: m.from, kind: m.kind, text: l.text, at: m.at, dur: l.dur) } }
        }
        for (cid, why) in res.failed {
            for (c, l) in threads where l.contains(where: { $0.cid == cid }) {
                threads[c] = l.map { x in var y = x; if y.cid == cid { y.failed = why; y.status = "failed" }; return y }
            }
            note = why
        }
    }
    /// "Try again" on a message that the server refused: it goes again as a new message.
    func retry(_ m: ChatMsg) {
        threads[m.c] = current(m.c).filter { $0.key != m.key }
        send(m.c, kind: m.kind, text: m.text, files: m.media.filter { !$0.local.isEmpty }.map { var x = $0; x.id = ""; return x }, mentions: m.mentions, loc: m.loc, contact: m.contact, fwd: m.fwd)
    }
    /// Remove a waiting message before it goes.
    func unsend(_ m: ChatMsg) { outbox.remove(m.cid); threads[m.c] = current(m.c).filter { $0.key != m.key } }

    // MARK: what you can do to a message

    private func patch(_ c: String, _ id: String, _ f: (inout ChatMsg) -> Void) {
        let l = current(c)
        guard l.contains(where: { $0.id == id }) else { return }
        setThread(c, l.map { var x = $0; if x.id == id { f(&x) }; return x })
    }
    func editMsg(_ m: ChatMsg, _ text: String) async -> String? {
        let txt = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let r = await api.chatEdit(m.c, m.id, txt)
        if !r.ok { return chatReason(r.reason) }
        patch(m.c, m.id) { $0.text = txt; $0.edited = chatNow() }; return nil
    }
    func delete(_ m: ChatMsg, forAll: Bool) async -> String? {
        if m.queued || m.status == "failed" { unsend(m); return nil }
        let r = await api.chatDelete(m.c, m.id, forAll: forAll)
        if !r.ok { return chatReason(r.reason) }
        if forAll { patch(m.c, m.id) { $0.deleted = true; $0.text = ""; $0.media = [] } } else { setThread(m.c, current(m.c).filter { $0.id != m.id }) }
        return nil
    }
    /// Delete several: one at a time is the old call; more go together.
    func deleteMany(_ c: String, _ ms: [ChatMsg], forAll: Bool) async -> String? {
        ms.filter { $0.queued || $0.status == "failed" }.forEach { unsend($0) }
        let sent = ms.filter { !$0.queued && $0.status != "failed" }
        if sent.isEmpty { return nil }
        if sent.count == 1 { return await delete(sent[0], forAll: forAll) }
        let r = await api.chatDeleteMany(c, sent.map(\.id), forAll: forAll)
        if !r.ok { return chatReason(r.reason) }
        let ids = Set(sent.map(\.id))
        setThread(c, forAll ? current(c).map { x in var y = x; if ids.contains(y.id) { y.deleted = true; y.text = ""; y.media = [] }; return y } : current(c).filter { !ids.contains($0.id) })
        return nil
    }
    /// Clear chat (for me), or a DM's Delete chat (cleared and archived).
    func clear(_ c: String, archive: Bool) async -> String? {
        let r = await api.chatClear(c, archive: archive)
        if !r.ok { return chatReason(r.reason) }
        setThread(c, []); more[c] = false
        if let o = r.json.obj("chat") { let fresh = ChatRow.from(o); edit(c) { $0 = fresh } }
        else { edit(c) { $0.last = nil; $0.unread = 0; $0.mention = false; $0.archived = $0.archived || archive } }
        return nil
    }
    /// POST /api/translate: the text in the other language, or why not; "google" says where to open it instead.
    func translate(_ text: String) async -> (String?, String?, String?) {
        let r = await api.postTo("/api/translate", ["text": text])
        if r.ok { return (r.json.str("text"), nil, nil) }
        let g = r.json.str("google")
        return (nil, chatReason(r.reason), g.hasPrefix("http") ? g : nil)
    }
    func transcribe(_ m: ChatMsg) async -> (String?, String?) {
        let r = await api.chatTranscribe(m.c, m.id)
        if r.ok { return (r.json.str("text"), nil) }
        if r.reason == "off" { tools["transcribe"] = false }
        switch r.reason {
        case "off": return (nil, t("Typing out voice notes isn’t switched on.", "Kuandika jumbe za sauti hakujawashwa."))
        case "rate": return (nil, t("That’s a lot for one hour. Try again later.", "Ni nyingi kwa saa moja. Jaribu tena baadaye."))
        default: return (nil, chatReason(r.reason))
        }
    }
    /// Add to project photos: a note on the project ("📷 Photo from the … chat by …"), then each photo on it.
    func addPhotos(_ m: ChatMsg, project: String) async -> String? {
        let r = await api.post(["action": "chat-photo", "id": project, "from": m.from])
        let up = r.json.str("updateId")
        if !r.ok || up.isEmpty { return r.status == 0 ? t("Adding photos needs a signal.", "Kuongeza picha kunahitaji mtandao.") : reasonText(r.reason) }
        for md in m.media where md.type == "photo" {
            guard let f = await file(md), let data = try? Data(contentsOf: f) else { return t("Couldn’t add the photos. Please try again.", "Haikuweza kuongeza picha. Tafadhali jaribu tena.") }
            let x = await api.multipart("/api/pin-photo", data: data, mime: "image/jpeg", filename: "photo.jpg", fields: [("update", up), ("label", "chat")])
            if !x.ok { return reasonText(x.reason) }
        }
        d.set(chatNow(), forKey: "added_\(m.c)_\(m.id)"); tick += 1
        return nil
    }
    func added(_ m: ChatMsg) -> Bool { d.object(forKey: "added_\(m.c)_\(m.id)") != nil }
    func react(_ m: ChatMsg, _ emoji: String?) async -> String? {
        let mine = m.reacts.first { $0.value.contains { chatSame($0, me) } }?.key
        let next = emoji == mine ? nil : emoji
        let who = me
        patch(m.c, m.id) { $0.reacts = ChatHub.reactWith($0.reacts, who, next) }
        let r = await api.chatReact(m.c, m.id, next)
        if !r.ok { patch(m.c, m.id) { $0.reacts = m.reacts }; return chatReason(r.reason) }
        return nil
    }
    func star(_ m: ChatMsg, _ on: Bool) async -> String? {
        patch(m.c, m.id) { $0.starred = on }
        let r = await api.chatStar(m.c, m.id, on)
        if !r.ok { patch(m.c, m.id) { $0.starred = !on }; return chatReason(r.reason) }
        tick += 1; return nil
    }
    func starred() async -> [ChatMsg]? {
        let r = await api.chatStarred()
        guard r.ok else { return nil }
        return r.json.objs("msgs").map { ChatMsg.from($0) }
    }
    /// Who read and who received a message (group message info).
    func info(_ m: ChatMsg) async -> ([(String, Double)], [(String, Double)])? {
        let r = await api.chatInfo(m.c, m.id)
        guard r.ok else { return nil }
        func l(_ k: String) -> [(String, Double)] { r.json.objs(k).map { ($0.str("name"), chatMs($0["at"])) } }
        return (l("read"), l("delivered"))
    }

    // MARK: chat settings

    func prefs(_ c: String, _ f: JSON) async -> String? {
        guard let before = row(c) else { return nil }
        edit(c) { r in
            if f["pinned"] != nil { r.pinned = f.bool("pinned") }
            if f["muted"] != nil { r.mutedUntil = ChatRow.mutedFrom(f["muted"]) }
            if f["archived"] != nil { r.archived = f.bool("archived") }
            if f["wallpaper"] != nil { r.wallpaper = f.str("wallpaper") }
            if f["bubble"] != nil { r.bubble = f.str("bubble") }
        }
        let r = await api.chatPrefs(c, f)
        if !r.ok { edit(c) { $0 = before }; return chatReason(r.reason) }
        return nil
    }
    func pin(_ c: String, _ on: Bool) { Task { _ = await prefs(c, ["pinned": on]) } }
    func archive(_ c: String, _ on: Bool) { Task { _ = await prefs(c, ["archived": on]) } }
    /// Mute for `hours` (8, 168), forever (-1), or unmute (0).
    func mute(_ c: String, _ hours: Int) {
        let v: Any = hours < 0 ? "always" : hours == 0 ? 0 : chatNow() + Double(hours) * 3_600_000
        Task { _ = await prefs(c, ["muted": v]) }
    }
    func markUnread(_ c: String) { edit(c) { $0.unread = max(1, $0.unread) } }
    /// Mark all as read: every unread chat on the list (not archived), and the server is told.
    func allRead() {
        let todo = rows.filter { ($0.unread > 0 || $0.mention) && !$0.archived }
        let ids = Set(todo.map(\.id))
        rows = rows.map { r in var x = r; if ids.contains(r.id) { x.unread = 0; x.mention = false }; return x }
        recount(); save()
        let api = self.api
        for r in todo { if let id = r.last?.id, !id.isEmpty { Task { _ = await api.chatRead(r.id, id) } } }
    }
    /// The chat's sound for new messages (this phone only).
    func quiet(_ c: String) -> Bool { d.bool(forKey: "quiet_" + c) }
    func setQuiet(_ c: String, _ on: Bool) { d.set(on, forKey: "quiet_" + c); tick += 1 }

    /// Saves my synced settings ("quick", "dnd", "doubleTap") on the server; they're already kept on the phone.
    func saveSettings(_ keys: String...) {
        tick += 1
        let all = settings.serverJson()
        var body: JSON = [:]; keys.forEach { body[$0] = all[$0] ?? NSNull() }
        let api = self.api
        Task {
            let r = await api.chatPrefs(nil, body)
            if r.ok, let p = r.json.obj("prefs") { self.settings.fromServer(p); self.tick += 1 }
        }
    }
    /// May I pin in this chat? DMs: anyone; elsewhere chat admins (and Office admins).
    func canPin(_ row: ChatRow?, officeAdmin: Bool) -> Bool { guard let row else { return false }; return row.kind == "dm" || row.admin(me) || officeAdmin }
    /// Pin or unpin a message (a 4th pin replaces the oldest on the server).
    func pinMsg(_ m: ChatMsg, _ on: Bool) async -> String? {
        let r = await api.chatPin(m.c, m.id, on)
        if !r.ok { return chatReason(r.reason) }
        if let o = r.json.obj("chat") { let nr = ChatRow.from(o); if !nr.id.isEmpty { rows = merge(rows, [nr]); recount(); save() }; return nil }
        if r.json["pins"] != nil { let p = ChatPin.list(r.json["pins"]); edit(m.c) { $0.pins = p }; return nil }
        let who = me
        edit(m.c) { row in
            if on { row.pins = Array(([ChatPin(id: m.id, from: m.from, kind: m.kind, text: String(m.preview().prefix(160)), by: who, at: m.at, pinnedAt: chatNow())] + row.pins.filter { $0.id != m.id }).prefix(3)) }
            else { row.pins = row.pins.filter { $0.id != m.id } }
        }
        return nil
    }
    /// A trip's card fresh from the server, or nil.
    func tripFresh(_ id: String) async -> ChatTripCard? { let r = await api.chatTrip(id); return r.ok ? ChatTripCard.from(r.json.obj("trip")) : nil }
    /// Shares a trip into a chat: its card from the trips code first (so the bubble shows it at once), then sent.
    func shareTrip(_ c: String, _ id: String) async -> String? {
        let r = await api.chatTripCard(id)
        if !r.ok && r.status != 0 { return r.reason == "forbidden" ? t("This isn’t one of your trips.", "Hii si mojawapo ya safari zako.") : chatReason(r.reason) }
        var card = ChatTripCard.from(r.json.obj("card") ?? r.json.obj("trip") ?? r.json) ?? ChatTripCard(id: id, title: "")
        card.id = id
        send(c, kind: "trip", trip: card); return nil
    }
    /// Every trip card in an open chat, read again (it may have moved on since it was shared).
    func refreshTrips(_ c: String) async {
        var ids: [String] = []
        for m in current(c) { if let id = m.trip?.id, !id.isEmpty, !ids.contains(id) { ids.append(id) } }
        for id in ids.prefix(10) {
            guard let card = await tripFresh(id) else { continue }
            threads[c] = current(c).map { x in var y = x; if y.trip?.id == id { y.trip = card }; return y }
        }
    }

    // MARK: groups

    func newGroup(_ title: String, _ members: [String], _ description: String) async -> (String?, String?) {
        let r = await api.chatGroup(title.trimmingCharacters(in: .whitespaces), members, description.trimmingCharacters(in: .whitespaces))
        if !r.ok { return (nil, chatReason(r.reason)) }
        await refreshList(full: false); return (r.json.str("id"), nil)
    }
    func updateGroup(_ c: String, _ f: JSON) async -> String? {
        let r = await api.chatGroupUpdate(c, f)
        if !r.ok { return chatReason(r.reason) }
        edit(c) { row in
            if f["title"] != nil { row.title = f.str("title") }
            if f["description"] != nil { row.description = f.str("description") }
            if f["photo"] != nil { row.photo = f.str("photo") }
            if f["onlyAdmins"] != nil { row.onlyAdmins = f.bool("onlyAdmins") }
        }
        return nil
    }
    func members(_ c: String, add: [String] = [], remove: [String] = [], admin: [String] = [], unadmin: [String] = []) async -> String? {
        let r = await api.chatMembers(c, add: add, remove: remove, admin: admin, unadmin: unadmin)
        if !r.ok { return chatReason(r.reason) }
        edit(c) { row in
            var mem = row.members; for a in add where !mem.contains(where: { chatSame($0, a) }) { mem.append(a) }
            row.members = mem.filter { m in !remove.contains { chatSame($0, m) } }
            var ad = row.admins; for a in admin where !ad.contains(where: { chatSame($0, a) }) { ad.append(a) }
            row.admins = ad.filter { m in !unadmin.contains { chatSame($0, m) } && !remove.contains { chatSame($0, m) } }
        }
        return nil
    }
    func link(_ c: String, reset: Bool) async -> (String?, String?) { let r = await api.chatLink(c, reset: reset); return r.ok ? (r.json.str("url"), nil) : (nil, chatReason(r.reason)) }
    func join(_ token: String) async -> (String?, String?) {
        let r = await api.chatJoin(token)
        if !r.ok { return (nil, chatReason(r.reason)) }
        await refreshList(full: false); return (r.json.str("id"), nil)
    }
    func leave(_ c: String) async -> String? {
        let r = await api.chatLeave(c)
        if !r.ok { return chatReason(r.reason) }
        rows = rows.filter { $0.id != c }; recount(); save(); return nil
    }

    // MARK: search

    struct Hit: Identifiable { var c: String; var id: String; var from: String; var at: Double; var snippet: String }
    func search(_ q: String) async -> [Hit]? {
        let r = await api.chatSearch(q.trimmingCharacters(in: .whitespaces))
        guard r.ok else { return nil }
        return r.json.objs("hits").map { Hit(c: $0.str("c"), id: $0.str("id"), from: $0.str("from"), at: chatMs($0["at"]), snippet: $0.str("snippet")) }
    }

    // MARK: files

    /// A message's file on this phone: the one it was sent from, the one downloaded before, or downloaded now.
    func file(_ md: ChatMedia, thumb: Bool = false) async -> URL? {
        if thumb {
            if md.thumb.hasPrefix("chat/") { let u = Outbox.resolve(md.thumb); if FileManager.default.fileExists(atPath: u.path) { return u } }
            if md.thumb.isEmpty && md.thumbUrl.isEmpty { return nil }
            let tid = md.thumb.isEmpty ? md.id : md.thumb
            let tf = ChatFiles.cacheDir.appendingPathComponent("t_" + ChatFiles.safe(tid) + ".jpg")
            if FileManager.default.fileExists(atPath: tf.path) { return tf }
            let path = md.thumbUrl.hasPrefix("/") ? md.thumbUrl : Api.chatMediaPath(tid)
            let (st, data) = await api.bytes(path)
            guard (200..<300).contains(st), !data.isEmpty else { return nil }
            settings.count(data.count)
            return (try? data.write(to: tf)) != nil ? tf : nil
        }
        if !md.local.isEmpty { let u = Outbox.resolve(md.local); if FileManager.default.fileExists(atPath: u.path) { return u } }
        if let c = cachedFile(md) { return c }
        guard !md.id.isEmpty else { return nil }
        let path = md.url.hasPrefix("/") ? md.url : Api.chatMediaPath(md.id)
        let (st, data) = await api.bytes(path)
        guard (200..<300).contains(st), !data.isEmpty else { return nil }
        settings.count(data.count)
        let f = ChatFiles.cacheDir.appendingPathComponent(ChatFiles.safe(md.id) + "." + ext(md))
        return (try? data.write(to: f)) != nil ? f : nil
    }
    /// A message's file if it's already on this phone; never downloads.
    func cachedFile(_ md: ChatMedia) -> URL? {
        if !md.local.isEmpty { let u = Outbox.resolve(md.local); if FileManager.default.fileExists(atPath: u.path) { return u } }
        guard !md.id.isEmpty else { return nil }
        let f = ChatFiles.cacheDir.appendingPathComponent(ChatFiles.safe(md.id) + "." + ext(md))
        return FileManager.default.fileExists(atPath: f.path) ? f : nil
    }
    private func ext(_ md: ChatMedia) -> String {
        switch md.type {
        case "voice": return "m4a"
        case "photo": return "jpg"
        case "video": return "mp4"
        default: let e = (md.name as NSString).pathExtension; return e.isEmpty ? "bin" : String(e.prefix(5))
        }
    }

    // MARK: live location

    /// My live locations still running: message id → (chat, until).
    func myLive() -> [String: (String, Double)] {
        var out: [String: (String, Double)] = [:]
        let now = chatNow()
        for (k, v) in d.dictionaryRepresentation() where k.hasPrefix("live_") {
            guard let s = v as? String else { continue }
            let p = s.split(separator: "|").map(String.init)
            guard p.count == 2, let u = Double(p[1]), u > now else { continue }
            out[String(k.dropFirst(5))] = (p[0], u)
        }
        return out
    }
    private var lastLive: Double = 0
    private var lastLiveAt: LatLng?
    /// A new position while the app is open: my live locations move (every 20 s at most, or after 25 m).
    private func onFix(_ f: Fix) {
        let mine = myLive(); if mine.isEmpty { return }
        let moved = lastLiveAt.map { Geo.metres($0, f.ll) } ?? .infinity
        if chatNow() - lastLive < 20_000 && moved < 25 { return }
        lastLive = chatNow(); lastLiveAt = f.ll
        let api = self.api
        for (id, cu) in mine where !id.hasPrefix("a") {
            Task {
                _ = await api.chatLiveAt(cu.0, id, f.ll)
                self.patch(cu.0, id) { m in if var l = m.live { l.lat = f.ll.lat; l.lng = f.ll.lng; l.at = chatNow(); m.live = l } }
            }
        }
    }
    func stopLive(_ m: ChatMsg) async -> String? {
        d.removeObject(forKey: "live_" + m.id); d.removeObject(forKey: "live_" + m.cid)
        if m.queued { unsend(m); return nil }
        let r = await api.chatLiveStop(m.c, m.id)
        if !r.ok { return chatReason(r.reason) }
        patch(m.c, m.id) { x in if var l = x.live { l.until = chatNow(); x.live = l } }
        return nil
    }

    // MARK: typing

    private var typingSent: Double = 0
    private var typingWhat: String?
    /// "typing", "recording" or nil (stopped), at most every 3 seconds.
    func sendTyping(_ c: String, _ what: String?) {
        if what == typingWhat && (what == nil || chatNow() - typingSent < 3000) { return }
        typingWhat = what; typingSent = chatNow()
        let o: JSON = ["t": "typing", "c": c, "what": what.map { $0 as Any } ?? NSNull()]
        if let ws, let data = try? JSONSerialization.data(withJSONObject: o), let s = String(data: data, encoding: .utf8) { ws.send(.string(s)) { _ in } }
    }
    /// Who is typing in a chat now: "Grace is typing…", "typing…".
    func typingText(_ c: String, group: Bool) -> String? {
        let now = chatNow()
        let m = (typing[c] ?? [:]).filter { $0.value.until > now && !chatSame($0.key, me) }
        guard let first = m.first else { return nil }
        let name = first.key.split(separator: " ").first.map(String.init) ?? first.key
        if first.value.what == "recording" { return group ? t("\(name) is recording…", "\(name) anarekodi…") : t("recording…", "anarekodi…") }
        if m.count > 1 { return t("\(m.count) people are typing…", "Watu \(m.count) wanaandika…") }
        return group ? t("\(name) is typing…", "\(name) anaandika…") : t("typing…", "anaandika…")
    }

    // MARK: the live connection

    private func connect() {
        if demo || connTask != nil { return }
        connTask = Task { [weak self] in
            var wait: UInt64 = 1
            while !Task.isCancelled {
                guard let self, self.front || self.bgTask != .invalid else { break }
                if self.off { try? await Task.sleep(nanoseconds: 60_000_000_000); await self.refreshList(full: false); continue }
                let r = await self.api.chatTicket()
                var ticket = chatS(r.json, "t"); if ticket.isEmpty { ticket = chatS(r.json, "ticket") }
                if r.reason == "chat-off" { self.off = true }
                if r.ok, !ticket.isEmpty, let url = URL(string: self.api.server.replacingOccurrences(of: "https://", with: "wss://").replacingOccurrences(of: "http://", with: "ws://") + "/api/chat/ws?t=" + Api.enc(ticket)) {
                    let task = Api.net.webSocketTask(with: url)
                    self.ws = task
                    task.resume()
                    wait = 1
                    await self.readLoop(task)
                    self.ws = nil; self.online = false
                }
                if Task.isCancelled || !(self.front || self.bgTask != .invalid) { break }
                try? await Task.sleep(nanoseconds: wait * 1_000_000_000)
                wait = min(30, wait * 2)
            }
            self?.connTask = nil
        }
    }
    private func readLoop(_ task: URLSessionWebSocketTask) async {
        let ping = Task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 25_000_000_000)
                if Task.isCancelled { break }
                task.send(.string("{\"t\":\"ping\"}")) { _ in }
            }
        }
        defer { ping.cancel() }
        // the link is up once the server answers a ping (it may have nothing to say yet)
        task.sendPing { [weak self] err in
            guard err == nil else { return }
            Task { @MainActor in
                guard let self, self.ws === task, !self.online else { return }
                self.online = true
                await self.catchUp()
            }
        }
        while !Task.isCancelled {
            do {
                let msg = try await task.receive()
                if !online { online = true; Task { await self.catchUp() } }
                var text = ""
                switch msg {
                case .string(let s): text = s
                case .data(let d): text = String(data: d, encoding: .utf8) ?? ""
                @unknown default: break
                }
                if let d = text.data(using: .utf8), let o = (try? JSONSerialization.jsonObject(with: d)) as? JSON { onEvent(o) }
            } catch { return }
        }
    }
    /// After (re)connecting, or every 20 seconds without the socket: what happened meanwhile.
    func catchUp() async {
        await refreshList(full: lastSync == 0)
        if let c = openChat { await loadMsgs(c) }
        await flush()
    }

    private let rank: [String: Int] = ["queued": 0, "sent": 1, "delivered": 2, "read": 3]

    /// One event from the server.
    func onEvent(_ e: JSON) {
        let c = e.str("c")
        switch e.str("t") {
        case "msg":
            guard let o = e.obj("msg") else { return }
            onMsg(ChatMsg.from(o, chat: c))
        case "edit":
            let id = e.str("id")
            if let o = e.obj("msg") { let n = ChatMsg.from(o, chat: c); patch(c, id) { x in let media = x.media; x = n; x.media = media } }
            else { let ed = chatMs(e["edited"] ?? e["at"]); patch(c, id) { x in if e["text"] != nil { x.text = e.str("text") }; x.edited = ed > 0 ? ed : chatNow() } }
        case "delete":
            let id = e.str("id")
            if e["forAll"] == nil || e.bool("forAll") { patch(c, id) { $0.deleted = true; $0.text = ""; $0.media = [] } }
            else { setThread(c, current(c).filter { $0.id != id }) }
        case "react":
            let id = e.str("id")
            if let r = e.obj("reacts") {
                var out: [String: [String]] = [:]; r.forEach { k, v in let n = chatStrings(v); if !n.isEmpty { out[k] = n } }
                patch(c, id) { $0.reacts = out }
            } else {
                let who = e.str("who").isEmpty ? e.str("from") : e.str("who")
                let em = e.str("emoji")
                patch(c, id) { $0.reacts = ChatHub.reactWith($0.reacts, who, em.isEmpty || em == "null" ? nil : em) }
            }
        case "status":
            let upTo = e.str("upTo"), st = e.str("status")
            let list = current(c)
            let limitAt = list.first { $0.id == upTo }?.at ?? { let a = chatMs(e["at"]); return a > 0 ? a : .infinity }()
            let upN = Int64(upTo)
            let me = self.me
            setThread(c, list.map { m in
                let covered: Bool = { if let u = upN, let mi = Int64(m.id) { return mi <= u }; return m.at <= limitAt }()
                guard !m.queued, chatSame(m.from, me), covered, (rank[st] ?? 0) > (rank[m.status] ?? 0) else { return m }
                var x = m; x.status = st; return x
            })
        case "typing":
            let who = e.str("who"), what = e.str("what")
            var cur = typing[c] ?? [:]
            if what.isEmpty || what == "null" { cur[who] = nil } else { cur[who] = ChatTyping(what: what, until: chatNow() + 6000) }
            typing[c] = cur
        case "presence":
            let who = e.str("who"); let s = chatMs(e["seen"])
            presence[who] = ChatPresence(online: e.bool("online"), seen: s > 0 ? s : chatNow())
        case "chat":
            guard let o = e.obj("chat") else { return }
            let r = ChatRow.from(o)
            if o.bool("gone") { rows = rows.filter { $0.id != r.id }; recount(); save() }
            else if !r.id.isEmpty { rows = merge(rows, [r]); recount(); save() }
        case "seen":
            let who = e.str("who"), upS = e.str("upTo")
            guard let r = row(c) else { return }
            let lastS = r.last?.id ?? ""
            let covers: Bool = { if let u = Int64(upS), let l = Int64(lastS) { return u >= l }; return !upS.isEmpty && (upS == lastS || lastS.isEmpty) }()
            if !who.isEmpty, !chatSame(who, me), covers, !chatSame(who, r.last?.from ?? "") { edit(c) { $0.seen = [who] + $0.seen.filter { !chatSame($0, who) } } }
        case "prefs":
            settings.fromServer(e.obj("prefs")); tick += 1
        case "removed", "gone":
            rows = rows.filter { $0.id != c }; recount(); save()
        case "live":
            let id = e.str("id")
            patch(c, id) { m in
                var l = m.live ?? ChatLive(until: 0, lat: nil, lng: nil, at: 0)
                if let a = e.num("lat") { l.lat = a }; if let b = e.num("lng") { l.lng = b }
                let at = chatMs(e["at"]); l.at = at > 0 ? at : chatNow()
                let u = chatMs(e["until"]); if u > 0 { l.until = u }
                m.live = l
            }
        default: break
        }
    }

    private func onMsg(_ m0: ChatMsg) {
        var m = m0
        let list = current(m.c)
        if let i = list.firstIndex(where: { $0.id == m.id || (!m.cid.isEmpty && $0.cid == m.cid) }) {
            let prev = list[i]
            m.media = m.media.map { md in var y = md; if y.local.isEmpty { y.local = prev.media.first { $0.id == md.id || $0.type == md.type }?.local ?? "" }; return y }
            var l = list; l[i] = m
            setThread(m.c, l)
            if prev.queued { outbox.remove(m.cid, deleteFiles: false) }
        } else { setThread(m.c, list + [m]) }
        let mine = chatSame(m.from, me)
        let mentioned = m.mentions.contains { chatSame($0, me) }
        if row(m.c) != nil {
            let text: String = m.kind == "text" ? m.text : m.kind == "trip" ? (m.trip?.title ?? m.text) : m.preview()
            let isOpen = openChat == m.c && front
            edit(m.c) { r in
                r.last = ChatLast(id: m.id, from: m.from, kind: m.kind, text: text, at: m.at, dur: m.media.first?.dur ?? 0)
                r.seen = []
                if !mine && !isOpen { r.unread += 1 }
                r.mention = r.mention || (!mine && mentioned)
                if mine { r.archived = false }
            }
        } else { Task { await refreshList(full: false) } }
        // a trip card over the socket is the sender's snapshot: read it fresh for me
        if let tid = m.trip?.id, !tid.isEmpty { let mid = m.id, c = m.c; Task { if let card = await tripFresh(tid) { patch(c, mid) { $0.trip = card } } } }
        if !mine {
            let api = self.api, c = m.c, id = m.id
            Task { _ = await api.chatDelivered(c, id) }
            markSeen(m.c)
            var cur = typing[m.c] ?? [:]; cur[m.from] = nil; typing[m.c] = cur
            if front && mentioned && settings.mentionSound && row(m.c)?.muted() != true && !(settings.dnd.active() && !settings.dnd.mentions) { ChatSound.mention() }
            // the app is in the background: a notification straight away
            if !front, let r = row(m.c) { ChatNotify.show(r, m, me: me, unread: unread, quiet: quiet(m.c), dnd: settings.dnd) }
        }
    }

    /// Reactions after `who` chose `emoji` (nil: took theirs away). One reaction per person.
    static func reactWith(_ r: [String: [String]], _ who: String, _ emoji: String?) -> [String: [String]] {
        var out: [String: [String]] = [:]
        r.forEach { k, v in let n = v.filter { !chatSame($0, who) }; if !n.isEmpty { out[k] = n } }
        if let emoji { out[emoji] = (out[emoji] ?? []) + [who] }
        return out
    }
    static let emoji = ["👍", "❤️", "😂", "😮", "😢", "🙏"]
}

struct ChatTyping: Equatable { var what: String; var until: Double }

/// What the rest of the app asks Chat for: a direct message, a project's thread, a chat from a notification, a message to
/// scroll to, a trip to share (picked up by the header's Chat button, which has the navigation).
final class ChatNav: ObservableObject {
    static let shared = ChatNav()
    /// "" (the list), "n:<chat>", "c:<chat>", "dm:<name>", "p:<project>", "join:<token>".
    @Published var req: String?
    /// A message to scroll to when the chat opens (search, starred, a pinned message).
    var focus: String?
    func dm(_ name: String) { req = "dm:" + name }
    func project(_ id: String) { req = "p:" + id }
    func chat(_ id: String) { req = "c:" + id }
}

// MARK: - notifications on this phone ---------------------------------------------------------------------------------

/// Chat notifications made on the phone (no Firebase on iPhone): one thread per chat, the sender and the message, a muted
/// chat stays quiet (unless I'm @mentioned), Do-Not-Disturb hours make them silent. A tap opens that chat.
enum ChatNotify {
    final class Delegate: NSObject, UNUserNotificationCenterDelegate {
        func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler done: @escaping () -> Void) {
            let c = response.notification.request.content.userInfo["chat"] as? String
            if let c { Task { @MainActor in ChatNav.shared.req = c.isEmpty ? "" : "n:" + c } }
            done()
        }
        func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent n: UNNotification, withCompletionHandler done: @escaping (UNNotificationPresentationOptions) -> Void) {
            // inside the app the chat list and badge show it already
            done([])
        }
    }
    private static let delegate = Delegate()
    static func setUp() { UNUserNotificationCenter.current().delegate = delegate }
    /// Asks once, from the chat list's "Get message alerts".
    static func ask(_ done: @escaping (Bool) -> Void) {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { ok, _ in DispatchQueue.main.async { done(ok) } }
    }
    static func status(_ done: @escaping (UNAuthorizationStatus) -> Void) {
        UNUserNotificationCenter.current().getNotificationSettings { s in DispatchQueue.main.async { done(s.authorizationStatus) } }
    }
    static func show(_ row: ChatRow, _ m: ChatMsg, me: String, unread: Int, quiet: Bool, dnd: ChatDnd) {
        let mentioned = m.mentions.contains { chatSame($0, me) }
        if row.muted() && !mentioned { return }
        let hush = quiet || (dnd.active() && !(mentioned && dnd.mentions))
        let c = UNMutableNotificationContent()
        let title = chatTitle(row, me)
        c.title = row.isGroup ? title : m.from
        if row.isGroup { c.subtitle = m.from }
        c.body = m.kind == "sys" || m.sys != nil ? chatSysText(m.sys) : m.preview()
        c.threadIdentifier = "chat:" + row.id
        c.userInfo = ["chat": row.id]
        c.badge = NSNumber(value: max(0, unread))
        if !hush { c.sound = .default }
        let req = UNNotificationRequest(identifier: "chat:\(row.id):\(m.id)", content: c, trigger: nil)
        UNUserNotificationCenter.current().add(req) { _ in }
    }
    /// The chat was read: its notifications go.
    static func clear(_ c: String) {
        let center = UNUserNotificationCenter.current()
        center.getDeliveredNotifications { list in
            let ids = list.filter { $0.request.content.threadIdentifier == "chat:" + c }.map { $0.request.identifier }
            if !ids.isEmpty { center.removeDeliveredNotifications(withIdentifiers: ids) }
        }
    }
}

package org.rafikioutreach.pin.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.rafikioutreach.pin.t
import java.io.File
import java.util.UUID

/**
 * Chat for the whole app (feat-chata): the list, each open chat's messages, who is online or typing, and the live
 * connection. Everything is drawn from what is kept on the phone first, then brought up to date from the server.
 *
 *  - Real time: a WebSocket ([ChatWire]) while the app is open, reconnecting with backoff (1 s … 30 s); after each
 *    reconnect it catches up with list?since and the open chat's messages.
 *  - Sending: every message goes to [ChatOutbox] first (a clock until the server has it), then out; it survives the
 *    app being closed ([org.rafikioutreach.pin.data.ChatSendWorker] sends it when there's signal).
 *  - Closed app: [org.rafikioutreach.pin.data.ChatWorker] checks list?since about every 15 minutes and notifies.
 */
class ChatHub(private val ctx: Context, val api: Api, private val myName: () -> String, private val scope: CoroutineScope) {
    val chat = ChatApi(api)
    val dir = File(ctx.filesDir, "chat")
    val outbox = ChatOutbox(File(dir, "outbox.json"))
    val cache = ChatCache(dir)
    private val sp = ctx.getSharedPreferences("chat", Context.MODE_PRIVATE)
    /** The live connection; tests swap it for a fake. */
    var wire: ChatWire = OkWire()
    /** The clock (tests move it on). */
    var now: () -> Long = { System.currentTimeMillis() }

    private val _rows = MutableStateFlow<List<ChatRow>>(emptyList())
    val rows: StateFlow<List<ChatRow>> = _rows
    private val _presence = MutableStateFlow<Map<String, Presence>>(emptyMap())
    val presence: StateFlow<Map<String, Presence>> = _presence
    /** The server says chat isn't set up yet (no CHAT binding): {ok:false, reason:'chat-off'}. */
    private val _off = MutableStateFlow(false)
    val off: StateFlow<Boolean> = _off
    /** What the server says is missing when chat is off ("CHAT service binding → rafiki-chat"), for the technical fold. */
    @Volatile var offNeed: List<String> = emptyList()
    /** The list was loaded (from the phone or the server) at least once. */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready
    /** The last thing that went wrong, for a short note on screen. */
    val note = MutableStateFlow<String?>(null)
    /** The live connection is up. */
    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online
    /** Unread messages in chats that aren't muted: the Chat tab badge and the launcher badge. */
    private val _unread = MutableStateFlow(0)
    val unread: StateFlow<Int> = _unread
    /** Who is typing or recording, per chat: name → ("typing" | "recording", until ms). */
    private val _typing = MutableStateFlow<Map<String, Map<String, Pair<String, Long>>>>(emptyMap())
    val typing: StateFlow<Map<String, Map<String, Pair<String, Long>>>> = _typing
    /** Bumped when something outside a chat's message list changed (stars, a message removed). */
    val tick = MutableStateFlow(0)

    // --- chatpa: my chat settings (synced), push, scroll memory -----------------------------------------------------
    /** My chat settings: quick replies, Do-Not-Disturb, the double-tap reaction (synced); data saver, sounds (this phone). */
    val settings = ChatSettings(ctx)
    /** Bumped when [settings] change (here or from the server), so screens redraw. */
    val settingsTick = MutableStateFlow(0)
    /** The server can send push (list answer `push`). */
    @Volatile var pushReady = false
    /** Where each chat was scrolled to (index, offset), kept while the app runs. */
    val scrollAt = mutableMapOf<String, Pair<Int, Int>>()

    private val threads = mutableMapOf<String, MutableStateFlow<List<Msg>>>()
    private val more = mutableMapOf<String, Boolean>()
    /** The chat on screen right now (its messages are read as they arrive). */
    @Volatile var openChat: String? = null
    /** The app is in front (the socket is wanted). */
    @Volatile private var front = false
    private var conn: ChatWire.Conn? = null
    private var connJob: Job? = null
    private var lastSync = 0L
    private val listLock = Mutex()

    /** The name the server knows me by (falls back to the app's). */
    val me: String get() = _me.ifBlank { myName() }
    private var _me = ""

    // --- start, stop, the list --------------------------------------------------------------------------------------
    private var started = false
    /** The Chat parts of the app opened: show what's on the phone, then fetch, connect and send what waits. */
    fun start() {
        if (!started) {
            started = true
            cache.loadList()?.let { (r, m, p) -> _rows.value = withLocal(r); _me = m; _presence.value = p; _ready.value = true; recount() }
        }
        scope.launch { refreshList(full = lastSync == 0L); flush() }
        resume()
    }
    /** The app came to the front: connect. */
    fun resume() { front = true; ChatPush.inFront = true; closing?.cancel(); closing = null; connect(); openChat?.let { markSeen(it) } }
    private var closing: Job? = null
    /** The app went to the background: the socket stays a minute (a message then is a notification at once), then
     *  closes and the background check takes over. */
    fun pause() { front = false; ChatPush.inFront = false; closing?.cancel(); closing = scope.launch { delay(60_000); if (!front) close() } }
    private fun close() { connJob?.cancel(); connJob = null; runCatching { conn?.close() }; conn = null; _online.value = false }

    /** Signed out: forget the chats kept on this phone (messages waiting to send stay, like the visits outbox). */
    fun forget() { front = false; close(); cache.clear(); _rows.value = emptyList(); threads.clear(); _ready.value = false; started = false; lastSync = 0L; _unread.value = 0 }

    private fun withLocal(r: List<ChatRow>) = r

    suspend fun refreshList(full: Boolean = true): Boolean = listLock.withLock {
        val r = chat.list(if (full) 0L else lastSync)
        if (!r.ok) {
            if (r.reason == "chat-off") { _off.value = true; _ready.value = true; offNeed = r.json.optJSONArray("need")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty() }
            else if (r.status != 0 && r.status != 401) note.value = chatReason(r.reason)
            return false
        }
        _off.value = false
        val a = r.json.optJSONArray("chats")
        // a server that answered something else (no "chats"): nothing to show yet
        val fresh = if (a == null) emptyList() else (0 until a.length()).mapNotNull { runCatching { ChatRow.from(a.getJSONObject(it)) }.getOrNull() }.filter { it.id.isNotBlank() }
        r.json.optString("me").takeIf { it.isNotBlank() && it != "null" }?.let { _me = it }
        r.json.optJSONObject("prefs")?.let { settings.fromServer(it); settingsTick.value++ }
        if (r.json.has("push")) pushReady = r.json.optBoolean("push")
        val gone = r.json.optJSONArray("gone")?.let { g -> (0 until g.length()).map { g.optString(it) } }.orEmpty().toSet()
        _rows.value = (if (full) fresh else merge(_rows.value, fresh)).filter { it.id !in gone }
        val p = ChatCache.presenceFrom(r.json.optJSONObject("presence"))
        _presence.value = if (full) p else _presence.value + p
        // the server's own clock, passed back as since (contract changes)
        lastSync = chatMs(r.json.opt("now")).takeIf { it > 0 } ?: (now() - 5_000)
        _ready.value = true
        recount(); save()
        // delivered: everything new in the list reached this phone
        fresh.filter { it.unread > 0 && it.last != null && !it.last.from.equals(me, true) }.forEach { row -> scope.launch { chat.delivered(row.id, row.last!!.id) } }
        true
    }

    private fun merge(old: List<ChatRow>, fresh: List<ChatRow>): List<ChatRow> {
        val byId = fresh.associateBy { it.id }
        // a project's photo only comes with the full list (contract §1): keep the one we had
        return old.map { o -> byId[o.id]?.let { n -> if (n.pphoto.isBlank() && o.pphoto.isNotBlank()) n.copy(pphoto = o.pphoto) else n } ?: o } + fresh.filter { f -> old.none { it.id == f.id } }
    }
    private fun save() = cache.saveList(_rows.value, _me, _presence.value)
    private fun recount() { _unread.value = _rows.value.filter { !it.muted(now()) }.sumOf { it.unread } }
    private fun edit(c: String, f: (ChatRow) -> ChatRow) { _rows.value = _rows.value.map { if (it.id == c) f(it) else it }; recount(); save() }
    fun row(c: String): ChatRow? = _rows.value.firstOrNull { it.id == c }

    /** The list order: pinned first, then the newest message. */
    fun sorted(rows: List<ChatRow>): List<ChatRow> = rows.sortedWith(compareByDescending<ChatRow> { it.announce }.thenByDescending { it.pinned }.thenByDescending { it.last?.at ?: 0L })

    // --- opening a chat -------------------------------------------------------------------------------------------
    /** A direct message with [with]: the one on the phone, or a new one from the server. */
    suspend fun openDm(with: String): Pair<String?, String?> {
        _rows.value.firstOrNull { it.kind == "dm" && it.members.any { m -> m.equals(with, true) } && (it.members.size <= 2) }?.let { return it.id to null }
        val r = chat.openDm(with)
        if (!r.ok) { if (r.reason == "chat-off") _off.value = true; return null to if (r.status == 0) t("Starting a new chat needs signal.", "Kuanzisha gumzo jipya kunahitaji mtandao.") else chatReason(r.reason) }
        val id = r.json.optString("id"); refreshList(full = false); return id to null
    }
    /** The project's thread. */
    suspend fun openProject(pid: String): Pair<String?, String?> {
        _rows.value.firstOrNull { it.kind == "project" && it.project == pid }?.let { return it.id to null }
        val r = chat.openProject(pid)
        if (!r.ok) { if (r.reason == "chat-off") _off.value = true; return null to if (r.status == 0) t("Opening the project’s chat the first time needs signal.", "Kufungua gumzo la mradi mara ya kwanza kunahitaji mtandao.") else chatReason(r.reason) }
        val id = r.json.optString("id"); refreshList(full = false); return id to null
    }

    // --- messages ---------------------------------------------------------------------------------------------------
    /** A chat's messages, oldest first: kept ones and waiting ones straight away. */
    fun msgs(c: String): StateFlow<List<Msg>> = threads.getOrPut(c) {
        MutableStateFlow(withWaiting(c, cache.loadMsgs(c)))
    }
    fun hasMore(c: String) = more[c] ?: true
    private fun flowOf(c: String) = threads.getOrPut(c) { MutableStateFlow(withWaiting(c, cache.loadMsgs(c))) }
    private fun withWaiting(c: String, server: List<Msg>): List<Msg> {
        val waiting = outbox.forChat(c).map { it.asMsg(me) }.filter { w -> server.none { it.cid == w.cid && it.cid.isNotBlank() } }
        return (server + waiting).sortedWith(compareBy<Msg> { it.queued }.thenBy { it.at })
    }
    private fun setThread(c: String, list: List<Msg>) { val l = unique(list); flowOf(c).value = l; cache.saveMsgs(c, l) }
    /** One bubble per message: a waiting copy and the server's copy of the same message (same client id) never both
     *  show, whatever order the outbox, the socket and a refresh arrive in (the server's copy wins). */
    private fun unique(list: List<Msg>): List<Msg> {
        if (list.map { it.key }.toSet().size == list.size) return list
        val best = list.groupBy { it.key }.mapValues { (_, l) -> l.lastOrNull { !it.queued } ?: l.last() }
        val done = HashSet<String>()
        return list.mapNotNull { m -> if (done.add(m.key)) best[m.key] else null }
    }

    /** The newest page from the server (on opening a chat, and after a reconnect). */
    suspend fun loadMsgs(c: String): Boolean {
        val r = chat.msgs(c)
        if (!r.ok) {
            if (r.reason == "chat-off") _off.value = true
            if (r.reason == "not-member" || r.reason == "removed" || r.status == 403) { note.value = chatReason(r.reason); _rows.value = _rows.value.filter { it.id != c }; save(); recount() }
            return false
        }
        val a = r.json.optJSONArray("msgs") ?: return false
        val fresh = (0 until a.length()).mapNotNull { runCatching { Msg.from(a.getJSONObject(it), c) }.getOrNull() }
        val old = flowOf(c).value.filter { !it.queued }
        // keep older pages already loaded; the fresh page replaces what it covers
        val oldest = fresh.minOfOrNull { it.at } ?: Long.MAX_VALUE
        val keep = old.filter { it.at < oldest && fresh.none { f -> f.id == it.id } }
        if (more[c] == null) more[c] = r.json.optBoolean("more")
        setThread(c, withWaiting(c, keepLocal(keep + fresh, old)))
        markSeen(c)
        return true
    }
    /** Files already on this phone stay attached (no second download of a photo just sent). */
    private fun keepLocal(list: List<Msg>, old: List<Msg>): List<Msg> = list.map { m ->
        val o = old.firstOrNull { it.id == m.id } ?: return@map m
        m.copy(media = m.media.map { md -> md.copy(local = md.local.ifBlank { o.media.firstOrNull { it.id == md.id }?.local.orEmpty() }) }, starred = m.starred || o.starred)
    }

    /** Older messages (scrolling up). Returns false when there are none. */
    suspend fun loadOlder(c: String): Boolean {
        if (!hasMore(c)) return false
        val cur = flowOf(c).value.filter { !it.queued }
        val first = cur.firstOrNull() ?: return loadMsgs(c)
        val r = chat.msgs(c, first.id)
        if (!r.ok) return false
        val a = r.json.optJSONArray("msgs") ?: return false
        val older = (0 until a.length()).mapNotNull { runCatching { Msg.from(a.getJSONObject(it), c) }.getOrNull() }.filter { o -> cur.none { it.id == o.id } }
        more[c] = r.json.optBoolean("more") && older.isNotEmpty()
        setThread(c, withWaiting(c, older + cur))
        return older.isNotEmpty()
    }

    /** The chat is on screen: its messages are read (blue ticks for the sender) and its notification goes. */
    fun markSeen(c: String) {
        if (openChat != c || !front) return
        val last = flowOf(c).value.lastOrNull { !it.queued && !it.from.equals(me, true) } ?: run { if ((row(c)?.unread ?: 0) > 0) edit(c) { it.copy(unread = 0, mention = false) }; return }
        if ((row(c)?.unread ?: 0) > 0 || row(c)?.mention == true) edit(c) { it.copy(unread = 0, mention = false) }
        val seenKey = "read_$c"
        if (sp.getString(seenKey, "") == last.id) return
        sp.edit().putString(seenKey, last.id).apply()
        scope.launch { chat.read(c, last.id) }
        ChatNotify.cancel(ctx, c)
    }

    // --- sending ------------------------------------------------------------------------------------------------------
    /** Puts a message in the outbox (a clock in the chat), then sends it. Survives the app being closed. */
    fun send(c: String, kind: String, text: String = "", files: List<ChatMedia> = emptyList(), reply: Msg? = null, mentions: List<String> = emptyList(),
             loc: LatLng? = null, liveMinutes: Int = 0, contact: String? = null, fwd: Boolean = false, trip: TripCard? = null): String {
        val cid = "a" + UUID.randomUUID().toString().replace("-", "").take(20)
        val body = JSONObject().put("kind", kind)
        if (text.isNotBlank()) body.put("text", text.trim())
        reply?.let { body.put("reply", it.id).put("_reply", Quote(it.id, it.from, it.kind, it.preview()).json()) }
        if (mentions.isNotEmpty()) body.put("mentions", org.json.JSONArray(mentions))
        loc?.let { body.put("loc", JSONObject().put("lat", it.lat).put("lng", it.lng)) }
        if (liveMinutes > 0) body.put("live", JSONObject().put("until", now() + liveMinutes * 60_000L))
        contact?.let { body.put("contact", JSONObject().put("name", it)) }
        if (fwd) body.put("fwd", true)
        // a trip card: the server fills it from the trip itself (only the id counts); the rest draws the waiting bubble
        trip?.let { body.put("trip", it.json()) }
        val item = ChatOutbox.Item(cid, c, body, files, now())
        outbox.add(item)
        val m = item.asMsg(me)
        flowOf(c).value = unique(flowOf(c).value + m)
        edit(c) { it.copy(last = ChatLast(cid, me, kind, if (kind == "contact") contact.orEmpty() else if (kind == "doc") files.firstOrNull()?.name.orEmpty() else if (kind == "trip") trip?.title.orEmpty() else text, m.at, files.firstOrNull()?.dur ?: 0.0), archived = false, seen = emptyList()) }
        if (kind == "live" && loc != null) liveMine(cid, c, now() + liveMinutes * 60_000L)
        ChatSendWorker.enqueue(ctx)
        scope.launch { flush() }
        return cid
    }

    /** Sends what waits; a sent message replaces its waiting bubble (same place, ticks instead of the clock). */
    suspend fun flush() {
        if (outbox.count() == 0) return
        val res = outbox.flush(chat)
        settings.count(res.uploaded)
        if (res.off) _off.value = true
        res.sent.forEach { (cid, m) ->
            val list = flowOf(m.c).value
            setThread(m.c, list.map { if (it.cid == cid || (it.id == cid && it.queued)) m.copy(cid = cid, media = m.media.map { md -> md.copy(local = md.local.ifBlank { it.media.firstOrNull { o -> o.type == md.type }?.local.orEmpty() }) }) else it }
                .let { l -> if (l.any { it.id == m.id && !it.queued }) l.distinctBy { it.id } else l })
            // a live location now has its server id
            sp.getString("live_$cid", null)?.let { v -> sp.edit().remove("live_$cid").putString("live_${m.id}", v).apply() }
            edit(m.c) { r -> if (r.last?.id == cid) r.copy(last = ChatLast(m.id, m.from, m.kind, r.last.text, m.at, r.last.dur)) else r }
        }
        res.failed.forEach { (cid, why) ->
            threads.values.forEach { f -> if (f.value.any { it.cid == cid }) f.value = f.value.map { if (it.cid == cid) it.copy(failed = why, status = "failed") else it } }
            note.value = why
        }
    }

    /** "Retry" on a message that the server refused: it goes again as a new message. */
    fun retry(m: Msg) {
        threads[m.c]?.let { f -> f.value = f.value.filter { it.key != m.key } }
        send(m.c, m.kind, m.text, m.media.filter { it.local.isNotBlank() }, null, m.mentions, m.loc, 0, m.contact, m.fwd)
    }
    /** Remove a waiting message before it goes. */
    fun unsend(m: Msg) { outbox.remove(m.cid); threads[m.c]?.let { f -> f.value = f.value.filter { it.key != m.key } } }

    // --- what you can do to a message ----------------------------------------------------------------------------
    private fun patch(c: String, id: String, f: (Msg) -> Msg) { val l = flowOf(c).value; if (l.any { it.id == id }) setThread(c, l.map { if (it.id == id) f(it) else it }) }

    suspend fun editMsg(m: Msg, text: String): String? {
        val r = chat.edit(m.c, m.id, text.trim()); if (!r.ok) return chatReason(r.reason)
        patch(m.c, m.id) { it.copy(text = text.trim(), edited = now()) }; return null
    }
    suspend fun delete(m: Msg, forAll: Boolean): String? {
        if (m.queued) { unsend(m); return null }
        val r = chat.delete(m.c, m.id, forAll); if (!r.ok) return chatReason(r.reason)
        if (forAll) patch(m.c, m.id) { it.copy(deleted = true, text = "", media = emptyList()) } else setThread(m.c, flowOf(m.c).value.filter { it.id != m.id })
        return null
    }
    suspend fun react(m: Msg, emoji: String?): String? {
        val mine = m.reacts.filterValues { it.any { n -> n.equals(me, true) } }.keys.firstOrNull()
        val next = if (emoji == mine) null else emoji
        // straight away on screen, then the server
        patch(m.c, m.id) { it.copy(reacts = reactWith(it.reacts, me, next)) }
        val r = chat.react(m.c, m.id, next)
        if (!r.ok) { patch(m.c, m.id) { it.copy(reacts = m.reacts) }; return chatReason(r.reason) }
        return null
    }
    suspend fun star(m: Msg, on: Boolean): String? {
        patch(m.c, m.id) { it.copy(starred = on) }
        val r = chat.star(m.c, m.id, on); if (!r.ok) { patch(m.c, m.id) { it.copy(starred = !on) }; return chatReason(r.reason) }
        tick.value++; return null
    }
    suspend fun starred(): List<Msg>? = chat.starred().takeIf { it.ok }?.json?.optJSONArray("msgs")?.let { a -> (0 until a.length()).mapNotNull { runCatching { Msg.from(a.getJSONObject(it)) }.getOrNull() } }
    /** Who read and who received a message (group message info). */
    suspend fun info(m: Msg): Pair<List<Pair<String, Long>>, List<Pair<String, Long>>>? {
        val r = chat.info(m.c, m.id); if (!r.ok) return null
        fun l(k: String) = r.json.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.optString("name") to chatMs(it.opt("at")) } }.orEmpty()
        return l("read") to l("delivered")
    }

    // --- chat settings -------------------------------------------------------------------------------------------------
    suspend fun prefs(c: String, f: JSONObject): String? {
        val before = row(c) ?: return null
        edit(c) { r -> r.copy(
            pinned = if (f.has("pinned")) f.optBoolean("pinned") else r.pinned,
            mutedUntil = if (f.has("muted")) ChatRow.mutedFrom(f.opt("muted")) else r.mutedUntil,
            archived = if (f.has("archived")) f.optBoolean("archived") else r.archived,
            wallpaper = if (f.has("wallpaper")) f.optString("wallpaper") else r.wallpaper, bubble = if (f.has("bubble")) f.optString("bubble") else r.bubble) }
        val r = chat.prefs(c, JSONObject(f.toString()))
        if (!r.ok) { _rows.value = _rows.value.map { if (it.id == c) before else it }; recount(); save(); return chatReason(r.reason) }
        return null
    }
    fun pin(c: String, on: Boolean) = scope.launch { prefs(c, JSONObject().put("pinned", on)) }
    fun archive(c: String, on: Boolean) = scope.launch { prefs(c, JSONObject().put("archived", on)) }
    /** Mute for [hours] (8, 168), forever (-1), or unmute (0). */
    fun mute(c: String, hours: Int) = scope.launch { prefs(c, JSONObject().put("muted", when { hours < 0 -> "always"; hours == 0 -> 0; else -> now() + hours * 3_600_000L })) }
    fun markUnread(c: String) = edit(c) { it.copy(unread = maxOf(1, it.unread)) }
    /** The chat's sound for new messages (this phone only). */
    fun quiet(c: String) = sp.getBoolean("quiet_$c", false)
    fun setQuiet(c: String, on: Boolean) { sp.edit().putBoolean("quiet_$c", on).apply(); tick.value++ }

    // --- chatpa: settings, pins, bubble colour, trips -------------------------------------------------------------------
    /** Saves my synced settings ([keys]: "quick", "dnd", "doubleTap") on the server; they're already kept on the phone. */
    fun saveSettings(vararg keys: String) {
        settingsTick.value++
        val all = settings.serverJson(); val body = JSONObject(); keys.forEach { k -> body.put(k, all.opt(k)) }
        scope.launch { chat.myPrefs(body).takeIf { it.ok }?.json?.optJSONObject("prefs")?.let { settings.fromServer(it); settingsTick.value++ } }
    }
    /** May I pin in this chat? DMs: anyone; elsewhere chat admins (and Office admins). */
    fun canPin(row: ChatRow?, officeAdmin: Boolean): Boolean = row != null && (row.kind == "dm" || row.admin(me) || officeAdmin)
    /** Pin or unpin a message (a 4th pin replaces the oldest on the server). */
    suspend fun pinMsg(m: Msg, on: Boolean): String? {
        val r = chat.pin(m.c, m.id, on)
        if (!r.ok) return chatReason(r.reason)
        r.json.optJSONObject("chat")?.let { o -> val nr = ChatRow.from(o); if (nr.id.isNotBlank()) { _rows.value = merge(_rows.value, listOf(nr)); recount(); save() } }
            ?: r.json.optJSONArray("pins")?.let { a -> edit(m.c) { it.copy(pins = PinNote.list(a)) } }
            ?: edit(m.c) { row -> row.copy(pins = if (on) (listOf(PinNote(m.id, m.from, m.kind, m.preview().take(160), me, m.at, now())) + row.pins.filter { it.id != m.id }).take(3) else row.pins.filter { it.id != m.id }) }
        return null
    }
    /** Pin from a sheet that closes at once: the work outlives it; a refusal shows as the chat's note. */
    fun pinLater(m: Msg, on: Boolean) = scope.launch { pinMsg(m, on)?.let { note.value = it } }
    fun bubble(c: String, token: String) = scope.launch { prefs(c, JSONObject().put("bubble", token)) }
    /** A trip's card fresh from the server (contract §7), or null. */
    suspend fun tripFresh(id: String): TripCard? = chat.trip(id).takeIf { it.ok }?.json?.optJSONObject("trip")?.let { TripCard.from(it) }
    /** Shares a trip into a chat: its card from the trips code first (so the bubble shows it at once), then sent. */
    suspend fun shareTrip(c: String, id: String): String? {
        val r = chat.tripCard(id)
        if (!r.ok && r.status != 0) return if (r.reason == "forbidden") t("This isn’t one of your trips.", "Hii si mojawapo ya safari zako.") else chatReason(r.reason)
        val card = (r.json.optJSONObject("card") ?: r.json.optJSONObject("trip") ?: r.json).let { TripCard.from(it) }?.copy(id = id) ?: TripCard(id, "")
        send(c, "trip", trip = card); return null
    }
    /** Every trip card in an open chat, re-read (it may have moved on since it was shared). */
    suspend fun refreshTrips(c: String) {
        val ids = flowOf(c).value.mapNotNull { it.trip?.id }.filter { it.isNotBlank() }.distinct().take(10)
        ids.forEach { id -> tripFresh(id)?.let { card -> val l = flowOf(c).value; if (l.any { it.trip?.id == id }) flowOf(c).value = l.map { if (it.trip?.id == id) it.copy(trip = card) else it } } }
    }

    // --- groups --------------------------------------------------------------------------------------------------------
    suspend fun newGroup(title: String, members: List<String>, description: String): Pair<String?, String?> {
        val r = chat.group(title.trim(), members, description.trim()); if (!r.ok) return null to chatReason(r.reason)
        refreshList(full = false); return r.json.optString("id") to null
    }
    suspend fun updateGroup(c: String, f: JSONObject): String? {
        val r = chat.groupUpdate(c, f); if (!r.ok) return chatReason(r.reason)
        edit(c) { it.copy(title = f.optString("title", it.title), description = f.optString("description", it.description), photo = f.optString("photo", it.photo),
            onlyAdmins = if (f.has("onlyAdmins")) f.optBoolean("onlyAdmins") else it.onlyAdmins) }
        return null
    }
    suspend fun members(c: String, add: List<String> = emptyList(), remove: List<String> = emptyList(), admin: List<String> = emptyList(), unadmin: List<String> = emptyList()): String? {
        val r = chat.members(c, add, remove, admin, unadmin); if (!r.ok) return chatReason(r.reason)
        edit(c) { row -> row.copy(members = (row.members + add).distinct().filter { m -> remove.none { it.equals(m, true) } },
            admins = (row.admins + admin).distinct().filter { m -> unadmin.none { it.equals(m, true) } && remove.none { it.equals(m, true) } }) }
        return null
    }
    suspend fun link(c: String, reset: Boolean): Pair<String?, String?> { val r = chat.link(c, reset); return if (r.ok) r.json.optString("url") to null else null to chatReason(r.reason) }
    suspend fun join(token: String): Pair<String?, String?> { val r = chat.join(token); if (!r.ok) return null to chatReason(r.reason); refreshList(full = false); return r.json.optString("id") to null }
    suspend fun leave(c: String): String? { val r = chat.leave(c); if (!r.ok) return chatReason(r.reason); _rows.value = _rows.value.filter { it.id != c }; recount(); save(); return null }

    // --- search ----------------------------------------------------------------------------------------------------------
    data class Hit(val c: String, val id: String, val from: String, val at: Long, val snippet: String)
    suspend fun search(q: String): List<Hit>? {
        val r = chat.search(q.trim()); if (!r.ok) return null
        val a = r.json.optJSONArray("hits") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Hit(it.optString("c"), it.optString("id"), it.optString("from"), chatMs(it.opt("at")), it.optString("snippet")) }
    }
    /** Link previews, fetched once per address. */
    private val previews = mutableMapOf<String, JSONObject?>()
    suspend fun preview(url: String): JSONObject? = if (previews.containsKey(url)) previews[url] else chat.preview(url).let { r -> (if (r.ok || r.json.has("title")) r.json else null).also { previews[url] = it } }

    // --- files ---------------------------------------------------------------------------------------------------------
    /** A message's file on this phone: the one it was sent from, the one downloaded before, or downloaded now. */
    suspend fun file(md: ChatMedia, posterOnly: Boolean = false, thumbOnly: Boolean = false): File? {
        if (thumbOnly) {
            if (md.thumb.startsWith("/") && File(md.thumb).exists()) return File(md.thumb)
            val tid = md.thumb.ifBlank { md.thumbUrl.substringAfter("/media/", "").substringBefore('?') }
            if (tid.isBlank() && md.thumbUrl.isBlank()) return null
            val tf = File(File(ctx.cacheDir, "chat-media").apply { mkdirs() }, "t_" + (tid.ifBlank { md.id }).replace(Regex("[^A-Za-z0-9_-]"), "_") + ".jpg")
            if (tf.exists() && tf.length() > 0) return tf
            val (st, b) = when {
                md.thumbUrl.startsWith("http") -> withContext(kotlinx.coroutines.Dispatchers.IO) { api.fetch(md.thumbUrl, api.authHeaders()) }
                md.thumbUrl.startsWith("/") -> api.download(md.thumbUrl)
                else -> api.download(chat.mediaPath(tid))
            }
            if (st !in 200..299 || b == null) return null
            settings.count(b.size.toLong())
            return runCatching { tf.writeBytes(b); tf }.getOrNull()
        }
        val id = if (posterOnly) md.poster else md.id
        if (!posterOnly && md.local.isNotBlank() && File(md.local).exists()) return File(md.local)
        if (id.isBlank()) return null
        val ext = when { posterOnly -> "jpg"; md.type == "voice" -> "m4a"; md.type == "photo" -> "jpg"; md.type == "video" -> "mp4"; else -> md.name.substringAfterLast('.', "bin").take(5) }
        val f = File(File(ctx.cacheDir, "chat-media").apply { mkdirs() }, id.replace(Regex("[^A-Za-z0-9_-]"), "_") + "." + ext)
        if (f.exists() && f.length() > 0) return f
        // the signed address when there is one (no headers needed), otherwise the plain one with the sign-in headers
        val signed = if (posterOnly) "" else md.url
        val (status, bytes) = when {
            signed.startsWith("http") -> withContext(kotlinx.coroutines.Dispatchers.IO) { api.fetch(signed, api.authHeaders()) }
            signed.startsWith("/") -> api.download(signed)
            id.startsWith("http") -> withContext(kotlinx.coroutines.Dispatchers.IO) { api.fetch(id, api.authHeaders()) }
            id.startsWith("/") -> api.download(id)
            else -> api.download(chat.mediaPath(id))
        }
        if (status !in 200..299 || bytes == null) return null
        settings.count(bytes.size.toLong())
        return runCatching { f.writeBytes(bytes); f }.getOrNull()
    }

    /** A message's file if it's already on this phone (sent from here, or downloaded before); never downloads. */
    fun cachedFile(md: ChatMedia): File? {
        if (md.local.isNotBlank() && File(md.local).exists()) return File(md.local)
        if (md.id.isBlank()) return null
        val ext = when { md.type == "voice" -> "m4a"; md.type == "photo" -> "jpg"; md.type == "video" -> "mp4"; else -> md.name.substringAfterLast('.', "bin").take(5) }
        return File(File(ctx.cacheDir, "chat-media"), md.id.replace(Regex("[^A-Za-z0-9_-]"), "_") + "." + ext).takeIf { it.exists() && it.length() > 0 }
    }

    // --- live location ---------------------------------------------------------------------------------------------------
    private fun liveMine(id: String, c: String, until: Long) { sp.edit().putString("live_$id", "$c|$until").apply() }
    /** My live locations still running: message id → (chat, until). */
    fun myLive(): Map<String, Pair<String, Long>> = sp.all.filterKeys { it.startsWith("live_") }.mapNotNull { (k, v) ->
        val (c, u) = (v as? String)?.split('|')?.takeIf { it.size == 2 } ?: return@mapNotNull null
        k.removePrefix("live_") to (c to (u.toLongOrNull() ?: 0L))
    }.toMap().filterValues { it.second > now() }
    private var lastLive = 0L; private var lastLiveAt: LatLng? = null
    /** A new position while the app is open: my live locations move (every 20 s at most, or after 25 m). */
    fun onFix(f: Fix) {
        val mine = myLive(); if (mine.isEmpty()) return
        val moved = lastLiveAt?.let { Geo.metres(it, f.ll) } ?: Double.MAX_VALUE
        if (now() - lastLive < 20_000 && moved < 25) return
        lastLive = now(); lastLiveAt = f.ll
        mine.forEach { (id, cu) -> if (!id.startsWith("a")) scope.launch {
            chat.live(cu.first, id, f.ll.lat, f.ll.lng)
            patch(cu.first, id) { it.copy(live = it.live?.copy(lat = f.ll.lat, lng = f.ll.lng, at = now())) }
        } }
    }
    suspend fun stopLive(m: Msg): String? {
        sp.edit().remove("live_${m.id}").remove("live_${m.cid}").apply()
        if (m.queued) { unsend(m); return null }
        val r = chat.liveStop(m.c, m.id); if (!r.ok) return chatReason(r.reason)
        patch(m.c, m.id) { it.copy(live = it.live?.copy(until = now())) }; return null
    }

    // --- typing ------------------------------------------------------------------------------------------------------------
    private var typingSent = 0L; private var typingWhat: String? = null
    /** "typing", "recording" or null (stopped), at most every 3 seconds. */
    fun typing(c: String, what: String?) {
        if (what == typingWhat && (what == null || now() - typingSent < 3000)) return
        typingWhat = what; typingSent = now()
        conn?.send(JSONObject().put("t", "typing").put("c", c).put("what", what ?: JSONObject.NULL).toString())
    }
    /** Who is typing in [c] now: "Grace is typing…", "typing…". */
    fun typingText(c: String, group: Boolean): String? {
        val m = _typing.value[c]?.filter { it.value.second > now() && !it.key.equals(me, true) } ?: return null
        val (who, w) = m.entries.firstOrNull()?.let { it.key to it.value.first } ?: return null
        val first = who.substringBefore(' ')
        return when {
            w == "recording" && group -> t("$first is recording…", "$first anarekodi…")
            w == "recording" -> t("recording…", "anarekodi…")
            m.size > 1 -> t("${m.size} people are typing…", "Watu ${m.size} wanaandika…")
            group -> t("$first is typing…", "$first anaandika…")
            else -> t("typing…", "anaandika…")
        }
    }

    // --- the live connection ------------------------------------------------------------------------------------------
    private fun connect() {
        if (connJob?.isActive == true || conn != null) return
        connJob = scope.launch {
            var wait = 1_000L
            while (front || closing?.isActive == true) {
                if (_off.value) { delay(60_000); refreshList(full = false); continue }
                val r = chat.ticket()
                val ticket = (r.json.optString("t").takeIf { it.isNotBlank() && it != "null" } ?: r.json.optString("ticket")).takeIf { r.ok && it.isNotBlank() && it != "null" }
                if (r.reason == "chat-off") _off.value = true
                if (ticket != null) {
                    val closed = kotlinx.coroutines.CompletableDeferred<Int>()
                    val url = api.server.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/api/chat/ws?t=" + Api.enc(ticket)
                    val c = runCatching { wire.open(url, emptyMap(), object : ChatWire.Listener {
                        override fun onOpen() { scope.launch { _online.value = true; catchUp() } }
                        override fun onText(text: String) { scope.launch { runCatching { onEvent(JSONObject(text)) } } }
                        override fun onClosed(code: Int) { closed.complete(code) }
                    }) }.getOrNull()
                    if (c != null) {
                        conn = c; wait = 1_000L
                        // the app's own ping, so the server knows we're here (presence)
                        val ping = scope.launch { while (true) { delay(25_000); c.send("{\"t\":\"ping\"}") } }
                        val code = closed.await(); ping.cancel()
                        conn = null; _online.value = false
                        // 4401: blocked, removed or signed out: no reconnecting; the list call then signs the phone out (401)
                        if (code == 4401) { refreshList(full = false); break }
                    }
                }
                if (!front && closing?.isActive != true) break
                delay(wait); wait = (wait * 2).coerceAtMost(30_000L)
            }
        }
    }
    /** After (re)connecting: what happened meanwhile. */
    suspend fun catchUp() { refreshList(full = lastSync == 0L); openChat?.let { loadMsgs(it) }; flush() }

    private val statusRank = mapOf("queued" to 0, "sent" to 1, "delivered" to 2, "read" to 3)
    /** One event from the server (also used by tests). */
    fun onEvent(e: JSONObject) {
        val c = e.optString("c")
        when (e.optString("t")) {
            "msg" -> e.optJSONObject("msg")?.let { o ->
                val m = Msg.from(o, c)
                val list = flowOf(m.c).value
                val i = list.indexOfFirst { it.id == m.id || (m.cid.isNotBlank() && it.cid == m.cid) }
                setThread(m.c, if (i >= 0) list.toMutableList().apply { this[i] = m.copy(media = m.media.map { md -> md.copy(local = md.local.ifBlank { list[i].media.firstOrNull { x -> x.id == md.id || x.type == md.type }?.local.orEmpty() }) }) } else list + m)
                if (i >= 0 && list[i].queued) outbox.remove(m.cid, deleteFiles = false)
                val mine = m.from.equals(me, true)
                val known = row(m.c) != null
                if (known) edit(m.c) { r -> r.copy(last = ChatLast(m.id, m.from, m.kind, m.preview().let { if (m.kind == "text") m.text else if (m.kind == "trip") m.trip?.title ?: m.text else it }, m.at, m.media.firstOrNull()?.dur ?: 0.0), seen = emptyList(),
                    unread = if (mine || (openChat == m.c && front)) r.unread else r.unread + 1, mention = r.mention || (!mine && m.mentions.any { it.equals(me, true) }), archived = if (mine) false else r.archived) }
                else scope.launch { refreshList(full = false) }
                // a trip card over the socket is the sender's snapshot: read it fresh for me (contract §7)
                if (m.trip != null && m.trip.id.isNotBlank()) scope.launch { tripFresh(m.trip.id)?.let { card -> patch(m.c, m.id) { it.copy(trip = card) } } }
                if (!mine) { scope.launch { chat.delivered(m.c, m.id) }; markSeen(m.c); _typing.value = _typing.value + (m.c to (_typing.value[m.c].orEmpty() - m.from)) }
                // a soft chime when I'm @mentioned (not in Do-Not-Disturb hours, unless mentions may ring then)
                if (!mine && front && m.mentions.any { it.equals(me, true) } && settings.mentionSound && row(m.c)?.muted(now()) != true && !(settings.dnd.active(now()) && !settings.dnd.mentions)) Chime.mention(ctx)
                // the app just went to the background: a notification straight away
                if (!mine && !front) row(m.c)?.let { r -> ChatNotify.show(ctx, r, listOf(m), me, r.unread, quiet(m.c)) }
            }
            "edit" -> { val id = e.optString("id"); e.optJSONObject("msg")?.let { o -> patch(c, id) { Msg.from(o, c).copy(media = it.media) } } ?: patch(c, id) { it.copy(text = e.optString("text", it.text), edited = chatMs(e.opt("edited") ?: e.opt("at")).takeIf { a -> a > 0 } ?: now()) } }
            "delete" -> { val id = e.optString("id"); if (e.optBoolean("forAll", true)) patch(c, id) { it.copy(deleted = true, text = "", media = emptyList()) } else setThread(c, flowOf(c).value.filter { it.id != id }) }
            "react" -> { val id = e.optString("id")
                val r = e.optJSONObject("reacts")
                patch(c, id) { m -> if (r != null) m.copy(reacts = r.keys().asSequence().associateWith { k -> r.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty() }.filterValues { it.isNotEmpty() })
                    else m.copy(reacts = reactWith(m.reacts, e.optString("who").ifBlank { e.optString("from") }, e.optString("emoji").takeIf { it.isNotBlank() && it != "null" })) } }
            "status" -> {
                val upTo = e.optString("upTo"); val st = e.optString("status")
                val list = flowOf(c).value; val idx = list.indexOfFirst { it.id == upTo }
                val limitAt = if (idx >= 0) list[idx].at else chatMs(e.opt("at")).takeIf { it > 0 } ?: Long.MAX_VALUE
                val upN = upTo.toLongOrNull()
                fun covered(m: Msg) = if (upN != null && m.id.toLongOrNull() != null) m.id.toLong() <= upN else m.at <= limitAt
                setThread(c, list.map { m -> if (!m.queued && m.from.equals(me, true) && covered(m) && (statusRank[st] ?: 0) > (statusRank[m.status] ?: 0)) m.copy(status = st) else m })
            }
            "typing" -> { val who = e.optString("who"); val what = e.optString("what").takeIf { it.isNotBlank() && it != "null" }
                val cur = _typing.value[c].orEmpty()
                _typing.value = _typing.value + (c to if (what == null) cur - who else cur + (who to (what to now() + 6_000))) }
            "presence" -> { val who = e.optString("who"); _presence.value = _presence.value + (who to Presence(e.optBoolean("online"), chatMs(e.opt("seen")).takeIf { it > 0 } ?: now())) }
            "chat" -> e.optJSONObject("chat")?.let { o -> val r = ChatRow.from(o)
                if (o.optBoolean("gone")) { _rows.value = _rows.value.filter { it.id != r.id }; recount(); save() }
                else if (r.id.isNotBlank()) { _rows.value = merge(_rows.value, listOf(r)); recount(); save() } }
            // chatpa: someone read up to the last message ("Seen by"), and my settings changed on another device
            "seen" -> { val who = e.optString("who"); val upS = e.optString("upTo")
                row(c)?.let { r -> val lastS = r.last?.id.orEmpty()
                    val up = upS.toLongOrNull(); val last = lastS.toLongOrNull()
                    val covers = if (up != null && last != null) up >= last else upS.isNotBlank() && (upS == lastS || lastS.isBlank())
                    if (who.isNotBlank() && !who.equals(me, true) && covers && !who.equals(r.last?.from, true)) edit(c) { it.copy(seen = listOf(who) + it.seen.filter { n -> !n.equals(who, true) }) } } }
            "prefs" -> { settings.fromServer(e.optJSONObject("prefs")); settingsTick.value++ }
            "removed", "gone" -> { _rows.value = _rows.value.filter { it.id != c }; recount(); save() }
            "live" -> { val id = e.optString("id"); patch(c, id) { it.copy(live = (it.live ?: LiveLoc(0, null, null, 0)).let { l -> l.copy(lat = e.optDouble("lat").takeIf { v -> !v.isNaN() } ?: l.lat, lng = e.optDouble("lng").takeIf { v -> !v.isNaN() } ?: l.lng,
                at = chatMs(e.opt("at")).takeIf { a -> a > 0 } ?: now(), until = chatMs(e.opt("until")).takeIf { u -> u > 0 } ?: l.until) }) } }
        }
    }

    companion object {
        /** Reactions after [who] chose [emoji] (null: took theirs away). One reaction per person. */
        fun reactWith(r: Map<String, List<String>>, who: String, emoji: String?): Map<String, List<String>> {
            val out = r.mapValues { (_, n) -> n.filter { !it.equals(who, true) } }.toMutableMap()
            if (emoji != null) out[emoji] = out[emoji].orEmpty() + who
            return out.filterValues { it.isNotEmpty() }
        }
        val EMOJI = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
    }
}

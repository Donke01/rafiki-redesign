package org.rafikioutreach.pin

import org.json.JSONArray
import org.json.JSONObject
import org.rafikioutreach.pin.data.ChatWire
import java.net.URLDecoder
import java.time.Instant

/**
 * A fake /api/chat/... server for the Android chat tests (feat-chata), following the contract in S/chat-brief.md:
 * chats, messages with cid de-duplication, receipts, reactions, stars, groups, links, media, search, previews,
 * "chat-off", and a fake WebSocket that the test pushes events through.
 */
class FakeChat(val me: String = "Reid Anderson") {
    val chats = linkedMapOf<String, JSONObject>()
    val msgs = mutableMapOf<String, MutableList<JSONObject>>()
    val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
    val media = mutableMapOf<String, ByteArray>()
    val presence = JSONObject()
    @Volatile var off = false
    @Volatile var online = true
    // chatpa (S/chatpolish-contract.md): my synced settings, push tokens, trips the proxy can resolve
    val userPrefs = JSONObject().put("quick", JSONObject.NULL).put("dnd", JSONObject().put("on", false).put("from", "21:00").put("to", "06:30").put("mentions", false).put("tz", 180)).put("doubleTap", "❤️")
    val devices = mutableListOf<String>()
    val trips = mutableMapOf<String, JSONObject>()
    @Volatile var push = false
    private var n = 1000
    private fun id() = "m${n++}"
    val now: Long get() = System.currentTimeMillis()
    fun ago(min: Long) = Instant.ofEpochMilli(now - min * 60_000).toString()

    fun chat(id: String, kind: String, title: String, members: List<String>, admins: List<String> = emptyList(), unread: Int = 0, mention: Boolean = false, pinned: Boolean = false,
             muted: Any = 0, archived: Boolean = false, onlyAdmins: Boolean = false, project: String = "", description: String = "") {
        chats[id] = JSONObject().put("id", id).put("kind", kind).put("title", title).put("photo", "").put("project", project).put("members", JSONArray(members)).put("admins", JSONArray(admins))
            .put("unread", unread).put("mention", mention).put("pinned", pinned).put("muted", muted).put("archived", archived).put("onlyAdmins", onlyAdmins).put("description", description)
        msgs.getOrPut(id) { mutableListOf() }
    }
    fun msg(c: String, from: String, text: String, minAgo: Long, kind: String = "text", status: String = "read", extra: JSONObject.() -> Unit = {}): JSONObject {
        val o = JSONObject().put("id", id()).put("c", c).put("cid", "").put("from", from).put("at", ago(minAgo)).put("kind", kind).put("text", text)
            .put("media", JSONArray()).put("reply", JSONObject.NULL).put("fwd", false).put("mentions", JSONArray()).put("reacts", JSONObject()).put("edited", JSONObject.NULL)
            .put("deleted", false).put("sys", JSONObject.NULL).put("status", status)
        o.extra()
        msgs.getOrPut(c) { mutableListOf() }.add(o)
        msgs[c]!!.sortBy { it.optString("at") }
        chats[c]?.put("last", JSONObject().put("id", o.optString("id")).put("from", from).put("kind", kind).put("text", text).put("at", o.optString("at")))
        return o
    }

    private fun ok(o: JSONObject = JSONObject()) = 200 to o.put("ok", true).toString()
    private fun bad(reason: String, status: Int = 400) = status to JSONObject().put("ok", false).put("reason", reason).toString()
    private fun q(url: String, k: String) = Regex("[?&]$k=([^&]*)").find(url)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }

    /** Answers one call; returns null for anything that isn't chat (so the test's own fake answers it). */
    fun handle(method: String, url: String, body: ByteArray?): Pair<Int, String>? {
        if (!url.contains("/api/chat/")) return null
        val b = body?.let { runCatching { JSONObject(String(it)) }.getOrNull() } ?: JSONObject()
        calls += "$method ${url.substringAfter("/api/chat/")} ${if (url.contains("/api/chat/media")) "<${body?.size} bytes>" else b.toString()}"
        if (!online) return 0 to ""
        if (off) return bad("chat-off", 503)
        val path = url.substringAfter("/api/chat/").substringBefore('?')
        synchronized(this) {
            return when (path) {
                "list" -> ok(JSONObject().put("now", now).put("me", me).put("chats", JSONArray(chats.values.toList())).put("presence", presence).put("prefs", userPrefs).put("push", push))
                "pin" -> {
                    val c = chats[b.optString("c")] ?: return bad("not-member", 403)
                    val m = find(b) ?: return bad("not-found", 404)
                    if (c.optString("kind") != "dm" && !c.getJSONArray("admins").toString().contains(me)) return bad("not-admin", 403)
                    val pins = (0 until (c.optJSONArray("pins")?.length() ?: 0)).map { c.getJSONArray("pins").getJSONObject(it) }.filter { it.optString("id") != m.optString("id") }.toMutableList()
                    var replaced: String? = null
                    if (b.optBoolean("on")) {
                        val wasPinned = (c.optJSONArray("pins")?.toString() ?: "").contains("\"id\":\"${m.optString("id")}\"")
                        pins.add(0, JSONObject().put("id", m.optString("id")).put("from", m.optString("from")).put("kind", m.optString("kind")).put("text", m.optString("text").take(160)).put("at", m.optString("at")).put("by", me).put("pinnedAt", now))
                        if (pins.size > 3) replaced = pins.removeAt(pins.lastIndex).optString("id")
                        if (!wasPinned) msg(c.optString("id"), "", "$me pinned a message", 0, kind = "sys") { put("sys", JSONObject().put("type", "pinned").put("by", me).put("id", m.optString("id"))) }
                    }
                    c.put("pins", JSONArray(pins))
                    ok(JSONObject().put("pins", JSONArray(pins)).put("chat", c).apply { replaced?.let { put("replaced", it) } })
                }
                "device" -> { val tk = b.optString("token"); if (b.optBoolean("remove")) devices.remove(tk) else devices += tk; ok(JSONObject().put("push", push)) }
                "trip" -> trips[q(url, "id")]?.let { ok(JSONObject().put("trip", it)) } ?: bad("not-found", 404)
                "ticket" -> ok(JSONObject().put("t", "T123").put("exp", now + 60_000))
                "open" -> {
                    if (b.optString("kind") == "dm") {
                        val w = b.optString("with")
                        val found = chats.values.firstOrNull { it.optString("kind") == "dm" && (0 until it.getJSONArray("members").length()).any { i -> it.getJSONArray("members").optString(i) == w } }
                        val cid = found?.optString("id") ?: "dm_${w.replace(' ', '_')}".also { chat(it, "dm", w, listOf(me, w)) }
                        ok(JSONObject().put("id", cid))
                    } else {
                        val p = b.optString("project")
                        val found = chats.values.firstOrNull { it.optString("project") == p }
                        val cid = found?.optString("id") ?: "p_$p".also { chat(it, "project", "Project $p", listOf(me, "Meril Kiprop"), project = p) }
                        ok(JSONObject().put("id", cid))
                    }
                }
                "group" -> { val cid = "g${n++}"; chat(cid, "group", b.optString("title"), listOf(me) + (0 until b.getJSONArray("members").length()).map { b.getJSONArray("members").getString(it) }, listOf(me), description = b.optString("description")); ok(JSONObject().put("id", cid)) }
                "group-update" -> { val c = chats[b.optString("c")] ?: return bad("not-member", 403)
                    if (!c.getJSONArray("admins").toString().contains(me)) return bad("not-admin", 403)
                    listOf("title", "description", "photo", "onlyAdmins").forEach { k -> if (b.has(k)) c.put(k, b.get(k)) }; ok() }
                "members" -> { val c = chats[b.optString("c")] ?: return bad("not-member", 403)
                    val mem = (0 until c.getJSONArray("members").length()).map { c.getJSONArray("members").getString(it) }.toMutableList()
                    val adm = (0 until c.getJSONArray("admins").length()).map { c.getJSONArray("admins").getString(it) }.toMutableList()
                    fun l(k: String) = b.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                    mem += l("add"); mem -= l("remove").toSet(); adm += l("admin"); adm -= (l("unadmin") + l("remove")).toSet()
                    c.put("members", JSONArray(mem.distinct())).put("admins", JSONArray(adm.distinct())); ok() }
                "link" -> ok(JSONObject().put("url", "https://pin.rafikioutreach.org/pin#join=" + if (b.optBoolean("reset")) "NEWTOKEN99" else "TOKEN12345"))
                "join" -> { val cid = chats.keys.firstOrNull { chats[it]!!.optString("kind") == "group" } ?: return bad("link"); ok(JSONObject().put("id", cid)) }
                "leave" -> { chats.remove(b.optString("c")); ok() }
                "msgs" -> {
                    val c = q(url, "c")!!; val list = msgs[c] ?: return bad("not-member", 403)
                    val before = q(url, "before"); val limit = q(url, "limit")?.toIntOrNull() ?: 50
                    val upto = if (before != null) list.indexOfFirst { it.optString("id") == before }.let { if (it < 0) list.size else it } else list.size
                    val from = (upto - limit).coerceAtLeast(0)
                    ok(JSONObject().put("msgs", JSONArray(list.subList(from, upto))).put("more", from > 0))
                }
                "send" -> {
                    val c = b.optString("c"); val chat = chats[c] ?: return bad("not-member", 403)
                    if (chat.optBoolean("onlyAdmins") && !chat.getJSONArray("admins").toString().contains(me)) return bad("only-admins", 403)
                    val list = msgs.getOrPut(c) { mutableListOf() }
                    list.firstOrNull { it.optString("cid") == b.optString("cid") && b.optString("cid").isNotBlank() }?.let { return ok(JSONObject().put("msg", it)) }
                    val replyQ = b.optString("reply").takeIf { it.isNotBlank() }?.let { rid -> list.firstOrNull { it.optString("id") == rid }?.let { JSONObject().put("id", rid).put("from", it.optString("from")).put("kind", it.optString("kind")).put("text", it.optString("text")) } }
                    val o = JSONObject(b.toString()).put("id", id()).put("from", me).put("at", Instant.now().toString()).put("status", "sent").put("reacts", JSONObject()).put("deleted", false)
                        .put("reply", replyQ ?: JSONObject.NULL).put("edited", JSONObject.NULL).put("sys", JSONObject.NULL)
                    if (!o.has("media")) o.put("media", JSONArray())
                    // a trip card: the proxy fills it from the trip itself; unknown trips are refused
                    if (o.optString("kind") == "trip") { val tid = b.optJSONObject("trip")?.optString("id").orEmpty(); o.put("trip", trips[tid] ?: return bad("not-found", 404)) }
                    list += o
                    chat.put("last", JSONObject().put("id", o.optString("id")).put("from", me).put("kind", o.optString("kind")).put("text", if (o.optString("kind") == "trip") o.getJSONObject("trip").optString("title") else o.optString("text")).put("at", o.optString("at"))).put("seen", JSONArray())
                    ok(JSONObject().put("msg", o))
                }
                "edit" -> { val m = find(b) ?: return bad("not-found", 404); m.put("text", b.optString("text")).put("edited", Instant.now().toString()); ok() }
                "delete" -> { val m = find(b) ?: return bad("not-found", 404); if (b.optBoolean("forAll")) m.put("deleted", true).put("text", ""); ok() }
                "react" -> { val m = find(b) ?: return bad("not-found", 404); val r = m.getJSONObject("reacts")
                    r.keys().asSequence().toList().forEach { k -> val a = r.getJSONArray(k); val l = (0 until a.length()).map { a.getString(it) }.filter { it != me }; if (l.isEmpty()) r.remove(k) else r.put(k, JSONArray(l)) }
                    b.optString("emoji").takeIf { it.isNotBlank() && it != "null" }?.let { e -> r.put(e, (r.optJSONArray(e) ?: JSONArray()).put(me)) }; ok() }
                "star" -> { find(b)?.put("starred", b.optBoolean("on")); ok() }
                "starred" -> ok(JSONObject().put("msgs", JSONArray(msgs.values.flatten().filter { it.optBoolean("starred") })))
                "prefs" -> if (method == "GET" || !b.has("c")) { listOf("quick", "dnd", "doubleTap").forEach { k -> if (b.has(k)) userPrefs.put(k, b.get(k)) }; ok(JSONObject().put("prefs", userPrefs)) }
                    else { chats[b.optString("c")]?.let { c -> listOf("pinned", "muted", "archived", "wallpaper", "bubble").forEach { k -> if (b.has(k)) c.put(k, b.get(k)) } }; ok(JSONObject().put("chat", chats[b.optString("c")])) }
                "read", "delivered", "live", "live-stop" -> {
                    if (path == "read") chats[b.optString("c")]?.put("unread", 0)?.put("mention", false)
                    ok()
                }
                "info" -> ok(JSONObject().put("read", JSONArray().put(JSONObject().put("name", "Grace Wanjiru").put("at", ago(3)))).put("delivered", JSONArray().put(JSONObject().put("name", "Meril Kiprop").put("at", ago(4)))))
                "media" -> { val mid = "md${n++}"; media[mid] = body ?: ByteArray(0); ok(JSONObject().put("id", mid).put("url", "/api/chat/media/$mid")) }
                "search" -> { val w = q(url, "q").orEmpty().lowercase()
                    ok(JSONObject().put("hits", JSONArray(msgs.values.flatten().filter { it.optString("text").lowercase().contains(w) }.map { JSONObject().put("c", it.optString("c")).put("id", it.optString("id")).put("from", it.optString("from")).put("at", it.optString("at")).put("snippet", it.optString("text")) }))) }
                "preview" -> ok(JSONObject().put("title", "RAFIKI Outreach").put("description", "Clean water for communities in Kenya, Uganda and beyond.").put("site", "rafikioutreach.org").put("image", ""))
                else -> bad("unknown", 404)
            }
        }
    }
    private fun find(b: JSONObject) = msgs[b.optString("c")]?.firstOrNull { it.optString("id") == b.optString("id") }

    /** The live connection the app opens (the test pushes server events through it). */
    inner class Wire : ChatWire {
        var l: ChatWire.Listener? = null
        val sent = java.util.concurrent.CopyOnWriteArrayList<String>()
        var url = ""
        override fun open(url: String, headers: Map<String, String>, l: ChatWire.Listener): ChatWire.Conn {
            this.url = url; this.l = l; l.onOpen()
            return object : ChatWire.Conn { override fun send(text: String): Boolean { sent += text; return true }; override fun close() { this@Wire.l = null } }
        }
        fun push(o: JSONObject) { l?.onText(o.toString()) }
    }
}

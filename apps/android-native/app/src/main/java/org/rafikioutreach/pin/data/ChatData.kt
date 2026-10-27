package org.rafikioutreach.pin.data

import org.json.JSONArray
import org.json.JSONObject
import org.rafikioutreach.pin.t
import java.io.File
import java.time.Instant
import kotlinx.coroutines.sync.withLock

/*
 * RAFIKI Pin Chat (feat-chata): the shapes of the chat contract (S/chat-brief.md, "Client contract"), read leniently
 * so a missing or extra field never breaks the screen, plus the offline outbox and the copy kept on the phone.
 */

private fun JSONObject.s(k: String): String = if (isNull(k)) "" else optString(k).let { if (it == "null") "" else it }
private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() && s != "null" } }
private fun JSONObject.dbl(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null

/** A moment from the server: ISO text or epoch milliseconds (seconds are read too). 0 when unknown. */
fun chatMs(v: Any?): Long = when (v) {
    null, JSONObject.NULL -> 0L
    is Number -> v.toLong().let { if (it in 1..99_999_999_999L) it * 1000 else it }
    is String -> v.trim().let { s -> s.toLongOrNull()?.let { chatMs(it) } ?: runCatching { Instant.parse(s).toEpochMilli() }.getOrDefault(0L) }
    else -> 0L
}
fun chatIso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

/** Someone online now, or when they were last seen. */
data class Presence(val online: Boolean, val seen: Long)

/** A chat row's last message, for the preview. */
data class ChatLast(val id: String, val from: String, val kind: String, val text: String, val at: Long, val dur: Double = 0.0, val sys: String = "") {
    fun json(): JSONObject = JSONObject().put("id", id).put("from", from).put("kind", kind).put("text", text).put("at", chatIso(at)).put("dur", dur).put("sys", sys)
    companion object { fun from(o: JSONObject?) = o?.let { ChatLast(it.s("id"), it.s("from"), it.s("kind").ifBlank { "text" }, it.s("text"), chatMs(it.opt("at")), it.optDouble("dur", 0.0).takeIf { d -> !d.isNaN() } ?: 0.0,
        it.opt("sys").let { v -> if (v is JSONObject) v.s("type") else if (v is String && v != "null") v else "" }) } }
}

/** One conversation in the list: team (per country, and All RAFIKI), direct message, group or project thread. */
data class ChatRow(
    val id: String, val kind: String, val title: String, val photo: String = "", val project: String = "",
    val members: List<String> = emptyList(), val admins: List<String> = emptyList(), val last: ChatLast? = null,
    val unread: Int = 0, val mention: Boolean = false, val pinned: Boolean = false,
    /** 0 = not muted, [ALWAYS] = always, otherwise muted until this moment (ms). */
    val mutedUntil: Long = 0L, val archived: Boolean = false, val onlyAdmins: Boolean = false,
    val description: String = "", val wallpaper: String = "",
    /** chatpa (S/chatpolish-contract.md §1): my bubble colour here ('' = the usual), the pinned messages (newest pin
     *  first, 3 at most), who has read the last message ("Seen by", latest reader first), my oldest unseen @mention,
     *  the Announcements chat, a team chat's country (its flag) and a project chat's photo. */
    val bubble: String = "", val pins: List<PinNote> = emptyList(), val seen: List<String> = emptyList(), val mentionId: String = "",
    val announceFlag: Boolean = false, val countryCode: String = "", val pphoto: String = "",
) {
    fun muted(now: Long = System.currentTimeMillis()) = mutedUntil == ALWAYS || mutedUntil > now
    val isGroup get() = kind != "dm"
    /** The auto-created Announcements chat (everyone in the team; only admins post). */
    val announce get() = announceFlag || id == "t:news"
    /** The country of a team chat ("ke"; from the server, or its id "t:ke"), '' for All RAFIKI, Announcements and others. */
    val country get() = countryCode.ifBlank { if (kind == "team") id.substringAfterLast(':').substringAfterLast('_').lowercase().takeIf { it.length == 2 }.orEmpty() else "" }
    /** The other person in a direct message. */
    fun other(me: String) = members.firstOrNull { !it.equals(me, true) } ?: title
    fun admin(name: String) = admins.any { it.equals(name, true) }
    fun json(): JSONObject = JSONObject().put("id", id).put("kind", kind).put("title", title).put("photo", photo).put("project", project)
        .put("members", JSONArray(members)).put("admins", JSONArray(admins)).put("last", last?.json() ?: JSONObject.NULL).put("unread", unread).put("mention", mention)
        .put("pinned", pinned).put("muted", if (mutedUntil == ALWAYS) "always" else mutedUntil).put("archived", archived).put("onlyAdmins", onlyAdmins)
        .put("description", description).put("wallpaper", wallpaper).put("bubble", bubble)
        .put("pins", JSONArray().apply { pins.forEach { put(it.json()) } }).put("seen", JSONArray(seen)).put("mentionId", mentionId)
        .put("announce", announceFlag).put("country", countryCode).put("pphoto", pphoto)

    companion object {
        const val ALWAYS = Long.MAX_VALUE
        fun mutedFrom(v: Any?): Long = when {
            v == null || v == JSONObject.NULL || v == false -> 0L
            v == true || v.toString() == "always" -> ALWAYS
            else -> chatMs(v)
        }
        fun from(o: JSONObject) = ChatRow(
            o.s("id"), o.s("kind").ifBlank { "group" }, o.s("title"), o.s("photo"), o.s("project"),
            o.optJSONArray("members").strings(), o.optJSONArray("admins").strings(), ChatLast.from(o.optJSONObject("last")),
            o.optInt("unread"), o.optBoolean("mention"), o.optBoolean("pinned"), mutedFrom(o.opt("muted")), o.optBoolean("archived"), o.optBoolean("onlyAdmins"),
            o.s("description"), o.s("wallpaper"), o.s("bubble"), PinNote.list(o.opt("pins")), o.optJSONArray("seen").strings(),
            o.s("mentionId").takeIf { it != "0" }.orEmpty(), o.optBoolean("announce"), o.s("country").lowercase(), o.s("pphoto"),
        )
    }
}

/** A photo, video, voice note or document on a message. [local] is the file on this phone (before or after upload). */
data class ChatMedia(
    val id: String, val type: String, val w: Int = 0, val h: Int = 0, val dur: Double = 0.0, val wave: List<Int> = emptyList(),
    val name: String = "", val size: Long = 0L, val local: String = "", val poster: String = "", val mime: String = "",
    /** The server's signed address (relative, works for about a day without headers). */
    val url: String = "",
    /** chatpa (contract §8): a small preview for the data saver: a local file before upload, then its media id; and its signed address. */
    val thumb: String = "", val thumbUrl: String = "",
) {
    fun json(withLocal: Boolean = false): JSONObject = JSONObject().put("id", id).put("type", type).put("w", w).put("h", h).put("dur", dur).put("wave", JSONArray(wave))
        .put("name", name).put("size", size).apply { if (poster.isNotBlank()) put("poster", poster); if (mime.isNotBlank()) put("mime", mime); if (withLocal && local.isNotBlank()) put("local", local)
            if (thumb.isNotBlank() && (withLocal || !thumb.startsWith("/"))) put("thumb", thumb); if (withLocal && thumbUrl.isNotBlank()) put("thumbUrl", thumbUrl); if (withLocal && url.isNotBlank()) put("url", url) }
    companion object {
        /** The waveform: 0–100 per bar. Read from a list, or from "1,5,9" text. */
        fun waveFrom(v: Any?): List<Int> = when (v) {
            is JSONArray -> (0 until v.length()).map { v.optInt(it).coerceIn(0, 100) }
            is String -> v.split(',').mapNotNull { it.trim().toIntOrNull()?.coerceIn(0, 100) }
            else -> emptyList()
        }
        fun from(o: JSONObject) = ChatMedia(o.s("id"), o.s("type").ifBlank { "photo" }, o.optInt("w"), o.optInt("h"), o.optDouble("dur", 0.0).takeIf { !it.isNaN() } ?: 0.0,
            waveFrom(o.opt("wave")), o.s("name"), o.optLong("size"), o.s("local"), o.s("poster"), o.s("mime"), o.s("url"), o.s("thumb"), o.s("thumbUrl"))
    }
}

data class Quote(val id: String, val from: String, val kind: String, val text: String) {
    fun json(): JSONObject = JSONObject().put("id", id).put("from", from).put("kind", kind).put("text", text)
    companion object { fun from(o: JSONObject?) = o?.let { Quote(it.s("id"), it.s("from"), it.s("kind").ifBlank { "text" }, it.s("text")) } }
}

/** A message the app wrote into a project thread: repair reported, assigned, step changed, fixed; an SMS report; "Not working". */
data class SysNote(val type: String, val project: String, val repair: String, val step: String, val by: String, val who: List<String> = emptyList(), val to: String = "", val state: String = "") {
    fun json(): JSONObject = JSONObject().put("type", type).put("project", project).put("repair", repair).put("step", step).put("by", by).put("who", JSONArray(who)).put("to", to).put("state", state)
    companion object { fun from(o: JSONObject?) = o?.let { SysNote(it.s("type"), it.s("project"), it.s("repair"), it.s("step"), it.s("by"), it.optJSONArray("who").strings(), it.s("to"), it.s("state")) } }
}

data class LiveLoc(val until: Long, val lat: Double?, val lng: Double?, val at: Long) {
    fun active(now: Long = System.currentTimeMillis()) = until > now
    fun json(): JSONObject = JSONObject().put("until", until).put("lat", lat ?: JSONObject.NULL).put("lng", lng ?: JSONObject.NULL).put("at", if (at > 0) at else JSONObject.NULL)
    companion object { fun from(o: JSONObject?) = o?.let { LiveLoc(chatMs(it.opt("until")), it.dbl("lat"), it.dbl("lng"), chatMs(it.opt("at"))) } }
}

/**
 * One message. [status] is "sent", "delivered" or "read" from the server, or "queued" while it waits on this phone
 * (the clock). [failed] says why the server refused it.
 */
data class Msg(
    val id: String, val c: String, val cid: String = "", val from: String, val at: Long, val kind: String = "text", val text: String = "",
    val media: List<ChatMedia> = emptyList(), val reply: Quote? = null, val fwd: Boolean = false, val mentions: List<String> = emptyList(),
    val loc: LatLng? = null, val live: LiveLoc? = null, val contact: String? = null, val reacts: Map<String, List<String>> = emptyMap(),
    val edited: Long = 0L, val deleted: Boolean = false, val sys: SysNote? = null, val status: String = "sent", val failed: String? = null, val starred: Boolean = false,
    /** chatpa: a trip card (kind 'trip'). */
    val trip: TripCard? = null,
) {
    val queued get() = status == "queued"
    /** The key the list uses: the client id while waiting, so the bubble doesn't jump when the server's id arrives. */
    val key get() = cid.ifBlank { id }

    fun json(): JSONObject = JSONObject().put("id", id).put("c", c).put("cid", cid).put("from", from).put("at", chatIso(at)).put("kind", kind).put("text", text)
        .put("media", JSONArray().apply { media.forEach { put(it.json(true)) } }).put("reply", reply?.json() ?: JSONObject.NULL).put("fwd", fwd)
        .put("mentions", JSONArray(mentions)).put("loc", loc?.let { JSONObject().put("lat", it.lat).put("lng", it.lng) } ?: JSONObject.NULL)
        .put("live", live?.json() ?: JSONObject.NULL).put("contact", contact?.let { JSONObject().put("name", it) } ?: JSONObject.NULL)
        .put("reacts", JSONObject().apply { reacts.forEach { (e, n) -> put(e, JSONArray(n)) } }).put("edited", if (edited > 0) chatIso(edited) else JSONObject.NULL)
        .put("deleted", deleted).put("sys", sys?.json() ?: JSONObject.NULL).put("status", status).put("starred", starred).apply { failed?.let { put("failed", it) }; trip?.let { put("trip", it.json()) } }

    companion object {
        fun from(o: JSONObject, chat: String = ""): Msg {
            val loc = o.optJSONObject("loc")?.let { l -> val a = l.dbl("lat"); val b = l.dbl("lng"); if (a != null && b != null) LatLng(a, b) else null }
            val reacts = o.optJSONObject("reacts")?.let { r -> r.keys().asSequence().associateWith { k -> r.optJSONArray(k).strings() }.filterValues { it.isNotEmpty() } }.orEmpty()
            val fwd = o.opt("fwd").let { it == true || (it is String && it.isNotBlank() && it != "false" && it != "null") || it is JSONObject }
            return Msg(
                o.s("id"), o.s("c").ifBlank { chat }, o.s("cid"), o.s("from"), chatMs(o.opt("at")), o.s("kind").ifBlank { "text" }, o.s("text"),
                o.optJSONArray("media")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(ChatMedia::from) } }.orEmpty(),
                Quote.from(o.optJSONObject("reply")), fwd, o.optJSONArray("mentions").strings(), loc, LiveLoc.from(o.optJSONObject("live")),
                o.optJSONObject("contact")?.s("name")?.takeIf { it.isNotBlank() } ?: o.optString("contact").takeIf { it.isNotBlank() && !it.startsWith("{") && it != "null" },
                reacts, chatMs(o.opt("edited")), o.optBoolean("deleted"), SysNote.from(o.optJSONObject("sys")), o.s("status").ifBlank { "sent" },
                o.s("failed").takeIf { it.isNotBlank() }, o.optBoolean("starred"), TripCard.from(o.optJSONObject("trip")),
            )
        }
    }
}

/** "0:12", "1:05". */
fun clock(sec: Double): String { val s = sec.toInt().coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

/** What a message is, in a few words, for the chat list and notifications: "🎤 Voice message 0:12", "📷 Photo". */
fun chatPreview(kind: String, text: String, dur: Double = 0.0, deleted: Boolean = false, count: Int = 1, mediaType: String = ""): String = when {
    deleted -> t("This message was deleted", "Ujumbe huu ulifutwa")
    kind == "voice" -> "🎤 " + t("Voice message", "Ujumbe wa sauti") + if (dur > 0) " " + clock(dur) else ""
    kind == "media" -> (if (mediaType == "video") "🎥 " + t("Video", "Video") else if (count > 1) "📷 " + t("$count photos", "Picha $count") else "📷 " + t("Photo", "Picha")) + if (text.isNotBlank()) ": $text" else ""
    kind == "location" -> "📍 " + t("Location", "Mahali")
    kind == "live" -> "📍 " + t("Live location", "Mahali moja kwa moja")
    kind == "contact" -> "👤 " + text.ifBlank { t("Contact", "Mawasiliano") }
    kind == "doc" -> "📄 " + text.ifBlank { t("Document", "Hati") }
    kind == "trip" -> "🧭 " + t("Trip", "Safari") + if (text.isNotBlank()) ": $text" else ""
    else -> text
}
fun Msg.preview(): String = chatPreview(kind, if (kind == "contact") contact.orEmpty() else if (kind == "doc") media.firstOrNull()?.name ?: text else if (kind == "trip") trip?.title ?: text else text,
    media.firstOrNull()?.dur ?: 0.0, deleted, media.size, media.firstOrNull()?.type.orEmpty())

/** Why a chat call didn't work, in plain words. */
fun chatReason(reason: String?): String = when (reason) {
    "chat-off" -> t("Chat isn’t switched on yet. The office can turn it on.", "Gumzo bado halijawashwa. Ofisi inaweza kuliwasha.")
    "only-admins" -> t("Only admins can send messages in this group.", "Wasimamizi pekee wanaweza kutuma ujumbe kwenye kikundi hiki.")
    "not-member", "removed" -> t("You’re no longer in this chat.", "Hauko tena kwenye gumzo hili.")
    "edit-window", "too-late" -> t("Messages can only be edited for 15 minutes.", "Ujumbe unaweza kuhaririwa kwa dakika 15 tu.")
    "too-big" -> t("That file is too big to send.", "Faili hilo ni kubwa mno kutuma.")
    "donor" -> t("Chat is for the RAFIKI team only.", "Gumzo ni kwa timu ya RAFIKI pekee.")
    else -> reasonText(reason)
}

/** The /api/chat/... endpoints (the same PIN headers as the rest of the app). */
class ChatApi(val api: Api) {
    private fun enc(s: String) = Api.enc(s)
    private fun arr(l: List<String>) = JSONArray(l)
    suspend fun list(since: Long = 0L) = api.get("/api/chat/list" + if (since > 0) "?since=" + enc(chatIso(since)) else "")
    suspend fun openDm(with: String) = api.postTo("/api/chat/open", JSONObject().put("kind", "dm").put("with", with))
    suspend fun openProject(project: String) = api.postTo("/api/chat/open", JSONObject().put("kind", "project").put("project", project))
    suspend fun group(title: String, members: List<String>, description: String) = api.postTo("/api/chat/group", JSONObject().put("title", title).put("members", arr(members)).put("description", description))
    suspend fun groupUpdate(c: String, f: JSONObject) = api.postTo("/api/chat/group-update", f.put("c", c))
    suspend fun members(c: String, add: List<String> = emptyList(), remove: List<String> = emptyList(), admin: List<String> = emptyList(), unadmin: List<String> = emptyList()) =
        api.postTo("/api/chat/members", JSONObject().put("c", c).apply { if (add.isNotEmpty()) put("add", arr(add)); if (remove.isNotEmpty()) put("remove", arr(remove)); if (admin.isNotEmpty()) put("admin", arr(admin)); if (unadmin.isNotEmpty()) put("unadmin", arr(unadmin)) })
    suspend fun link(c: String, reset: Boolean = false) = api.postTo("/api/chat/link", JSONObject().put("c", c).apply { if (reset) put("reset", true) })
    suspend fun join(token: String) = api.postTo("/api/chat/join", JSONObject().put("token", token))
    suspend fun leave(c: String) = api.postTo("/api/chat/leave", JSONObject().put("c", c))
    suspend fun msgs(c: String, before: String? = null, limit: Int = 50) = api.get("/api/chat/msgs?c=" + enc(c) + (before?.let { "&before=" + enc(it) } ?: "") + "&limit=$limit")
    suspend fun send(body: JSONObject) = api.postTo("/api/chat/send", body)
    suspend fun edit(c: String, id: String, text: String) = api.postTo("/api/chat/edit", JSONObject().put("c", c).put("id", id).put("text", text))
    suspend fun delete(c: String, id: String, forAll: Boolean) = api.postTo("/api/chat/delete", JSONObject().put("c", c).put("id", id).put("forAll", forAll))
    suspend fun react(c: String, id: String, emoji: String?) = api.postTo("/api/chat/react", JSONObject().put("c", c).put("id", id).put("emoji", emoji ?: JSONObject.NULL))
    suspend fun star(c: String, id: String, on: Boolean) = api.postTo("/api/chat/star", JSONObject().put("c", c).put("id", id).put("on", on))
    suspend fun starred() = api.get("/api/chat/starred")
    suspend fun read(c: String, upTo: String) = api.postTo("/api/chat/read", JSONObject().put("c", c).put("upTo", upTo))
    suspend fun delivered(c: String, upTo: String) = api.postTo("/api/chat/delivered", JSONObject().put("c", c).put("upTo", upTo))
    suspend fun info(c: String, id: String) = api.get("/api/chat/info?c=" + enc(c) + "&id=" + enc(id))
    suspend fun live(c: String, id: String, lat: Double, lng: Double) = api.postTo("/api/chat/live", JSONObject().put("c", c).put("id", id).put("lat", lat).put("lng", lng))
    suspend fun liveStop(c: String, id: String) = api.postTo("/api/chat/live-stop", JSONObject().put("c", c).put("id", id))
    suspend fun prefs(c: String, f: JSONObject) = api.postTo("/api/chat/prefs", f.put("c", c))
    suspend fun media(bytes: ByteArray, type: String, name: String, mime: String, dur: Double = 0.0) = api.postMultipart("/api/chat/media", bytes, mime, name,
        mapOf("type" to type, "name" to name) + if (dur > 0) mapOf("dur" to "%.1f".format(java.util.Locale.ROOT, dur)) else emptyMap())
    suspend fun search(q: String) = api.get("/api/chat/search?q=" + enc(q))
    suspend fun preview(url: String) = api.get("/api/chat/preview?url=" + enc(url))
    suspend fun ticket() = api.postTo("/api/chat/ticket", JSONObject())
    // chatpa (S/chatpolish-contract.md)
    suspend fun myPrefs(f: JSONObject) = api.postTo("/api/chat/prefs", f)
    suspend fun pin(c: String, id: String, on: Boolean) = api.postTo("/api/chat/pin", JSONObject().put("c", c).put("id", id).put("on", on))
    suspend fun device(token: String) = api.postTo("/api/chat/device", JSONObject().put("token", token).put("platform", "android"))
    suspend fun deviceRemove(token: String) = api.postTo("/api/chat/device", JSONObject().put("token", token).put("remove", true))
    suspend fun trip(id: String) = api.get("/api/chat/trip?id=" + enc(id))
    suspend fun tripCard(id: String) = api.get("/api/itin/" + enc(id) + "?card=1")
    fun mediaPath(id: String) = "/api/chat/media/" + enc(id)
}

/**
 * Messages written while offline (or not yet sent): kept in a file, so they wait with a clock and survive the app being
 * closed or killed. Each has a client id ([Msg.cid]); the server returns the same message for the same cid, so sending
 * twice is safe. Files are uploaded first and their server ids saved, so a half-sent message never uploads twice.
 */
class ChatOutbox(private val file: File) {
    data class Item(val cid: String, val c: String, val body: JSONObject, val files: List<ChatMedia>, val at: Long, val tries: Int = 0) {
        fun json(): JSONObject = JSONObject().put("cid", cid).put("c", c).put("body", body).put("at", at).put("tries", tries)
            .put("files", JSONArray().apply { files.forEach { put(it.json(true)) } })
        /** How it shows in the chat while waiting: a bubble with the clock. */
        fun asMsg(me: String): Msg = Msg.from(JSONObject(body.toString()).put("id", cid).put("cid", cid).put("c", c).put("from", me).put("at", chatIso(at)).put("status", "queued")
            .put("media", JSONArray().apply { files.forEach { put(it.json(true)) } }).apply {
                // what only this phone needs to draw the waiting bubble ("_reply": the quoted message)
                optJSONObject("_reply")?.let { put("reply", it) } ?: remove("reply")
                optJSONObject("live")?.let { l -> put("live", JSONObject().put("until", l.opt("until"))) }
            }, c)
        companion object {
            fun from(o: JSONObject) = Item(o.optString("cid"), o.optString("c"), o.optJSONObject("body") ?: JSONObject(),
                o.optJSONArray("files")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(ChatMedia::from) } }.orEmpty(), o.optLong("at"), o.optInt("tries"))
        }
    }

    @Synchronized fun all(): List<Item> = runCatching { val a = JSONArray(file.readText()); (0 until a.length()).map { Item.from(a.getJSONObject(it)) } }.getOrDefault(emptyList())
    @Synchronized private fun save(items: List<Item>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONArray().apply { items.forEach { put(it.json()) } }.toString()); tmp.renameTo(file)
    }
    @Synchronized fun add(item: Item) = save(all().filter { it.cid != item.cid } + item)
    @Synchronized fun put(item: Item) = save(all().map { if (it.cid == item.cid) item else it })
    @Synchronized fun remove(cid: String, deleteFiles: Boolean = true) {
        val items = all()
        if (deleteFiles) items.firstOrNull { it.cid == cid }?.files?.forEach { f -> if (f.local.contains("/chat-out/")) runCatching { File(f.local).delete() } }
        save(items.filter { it.cid != cid })
    }
    fun count() = all().size
    fun forChat(c: String) = all().filter { it.c == c }

    data class Result(val sent: Map<String, Msg>, val failed: Map<String, String>, val waiting: Int, val off: Boolean, val locked: Boolean, val uploaded: Long = 0L)

    /** Sends everything waiting, oldest first. A message that can't go yet stays (and the ones after it in the same chat,
     *  so the order is kept). */
    suspend fun flush(chat: ChatApi): Result = lock.withLock { flushNow(chat) }

    private suspend fun flushNow(chat: ChatApi): Result {
        val sent = mutableMapOf<String, Msg>(); val failed = mutableMapOf<String, String>(); val stuck = mutableSetOf<String>()
        var waiting = 0; var off = false; var locked = false; var uploaded = 0L
        for (it0 in all().sortedBy { it.at }) {
            var it = it0
            if (it.c in stuck || off || locked) { waiting++; continue }
            // 1. the files: upload each once, keep its server id
            var ok = true
            val files = it.files.toMutableList()
            for ((i, f) in files.withIndex()) {
                if (f.id.isNotBlank()) continue
                val bytes = runCatching { File(f.local).readBytes() }.getOrNull()
                if (bytes == null) { failed[it.cid] = t("The file is no longer on this phone.", "Faili halipo tena kwenye simu hii."); ok = false; break }
                val r = chat.media(bytes, f.type, f.name.ifBlank { File(f.local).name }, f.mime.ifBlank { mimeOf(f.type, f.local) }, f.dur)
                if (!r.ok) {
                    when {
                        r.reason == "chat-off" -> off = true
                        r.status == 401 -> locked = true
                        r.retryLater -> {}
                        else -> failed[it.cid] = chatReason(r.reason)
                    }
                    ok = false; break
                }
                uploaded += bytes.size
                files[i] = f.copy(id = r.json.optString("id"))
                it = it.copy(files = files.toList()); put(it)
            }
            // a video's poster picture goes up with it
            if (ok) for ((i, f) in files.withIndex()) {
                if (f.poster.isBlank() || !f.poster.startsWith("/")) continue
                val bytes = runCatching { File(f.poster).readBytes() }.getOrNull() ?: run { files[i] = f.copy(poster = ""); null } ?: continue
                val r = chat.media(bytes, "photo", "poster.jpg", "image/jpeg")
                if (!r.ok) { if (!r.retryLater && r.status != 401) files[i] = f.copy(poster = "") else { ok = false; break } }
                else files[i] = f.copy(poster = r.json.optString("id"))
                it = it.copy(files = files.toList()); put(it)
            }
            // a photo's small preview (the data saver, contract §8): best effort, never holds the message up for long
            if (ok) for ((i, f) in files.withIndex()) {
                if (f.thumb.isBlank() || !f.thumb.startsWith("/")) continue
                val bytes = runCatching { File(f.thumb).readBytes() }.getOrNull() ?: run { files[i] = f.copy(thumb = ""); null } ?: continue
                val r = chat.media(bytes, "photo", "thumb.jpg", "image/jpeg")
                if (!r.ok) { if (!r.retryLater && r.status != 401) files[i] = f.copy(thumb = "") else { ok = false; break } }
                else { uploaded += bytes.size; files[i] = f.copy(thumb = r.json.optString("id")) }
                it = it.copy(files = files.toList()); put(it)
            }
            if (failed.containsKey(it.cid)) { remove(it.cid); continue }
            if (!ok) { waiting++; stuck += it.c; it = it.copy(tries = it.tries + 1); put(it); continue }
            // 2. the message itself, with the same client id every time
            val body = JSONObject(it.body.toString()).put("c", it.c).put("cid", it.cid)
            body.keys().asSequence().filter { k -> k.startsWith("_") }.toList().forEach { k -> body.remove(k) }
            if (files.isNotEmpty()) body.put("media", JSONArray().apply { files.forEach { put(it.json(false)) } })
            val r = chat.send(body)
            when {
                r.ok -> { val m = r.json.optJSONObject("msg")?.let { Msg.from(it, it0.c) }; sent[it.cid] = (m ?: it.asMsg("").copy(status = "sent")).let { mm -> mm.copy(media = mm.media.map { md -> md.copy(local = files.firstOrNull { f -> f.id == md.id }?.local.orEmpty()) }) }; remove(it.cid, deleteFiles = false) }
                r.reason == "chat-off" -> { off = true; waiting++ }
                r.status == 401 -> { locked = true; waiting++ }
                r.retryLater -> { waiting++; stuck += it.c; put(it.copy(tries = it.tries + 1)) }
                else -> { failed[it.cid] = chatReason(r.reason); remove(it.cid) }
            }
        }
        return Result(sent, failed, waiting, off, locked, uploaded)
    }

    companion object {
        /** One flush at a time in this app (the screen and the background sender share the file). */
        val lock = kotlinx.coroutines.sync.Mutex()
        fun mimeOf(type: String, path: String): String = when {
            type == "voice" -> "audio/mp4"
            type == "photo" -> "image/jpeg"
            type == "video" -> "video/mp4"
            path.endsWith(".pdf", true) -> "application/pdf"
            else -> java.net.URLConnection.guessContentTypeFromName(path) ?: "application/octet-stream"
        }
    }
}

/** The chat list and the latest messages of each chat, kept on the phone so Chat opens offline. */
class ChatCache(private val dir: File) {
    private fun f(name: String) = File(dir.apply { mkdirs() }, name.replace(Regex("[^A-Za-z0-9_.-]"), "_"))
    private fun write(name: String, text: String) = runCatching { val x = f(name); val tmp = File(x.path + ".tmp"); tmp.writeText(text); tmp.renameTo(x) }
    fun saveList(rows: List<ChatRow>, me: String, presence: Map<String, Presence>) = write("list.json", JSONObject().put("me", me)
        .put("chats", JSONArray().apply { rows.forEach { put(it.json()) } })
        .put("presence", JSONObject().apply { presence.forEach { (k, p) -> put(k, JSONObject().put("online", p.online).put("seen", chatIso(p.seen))) } }).toString())
    fun loadList(): Triple<List<ChatRow>, String, Map<String, Presence>>? = runCatching {
        val j = JSONObject(f("list.json").readText())
        val a = j.optJSONArray("chats") ?: JSONArray()
        Triple((0 until a.length()).map { ChatRow.from(a.getJSONObject(it)) }, j.optString("me"), presenceFrom(j.optJSONObject("presence")))
    }.getOrNull()
    fun saveMsgs(c: String, msgs: List<Msg>) = write("c_$c.json", JSONArray().apply { msgs.filter { !it.queued }.takeLast(200).forEach { put(it.json()) } }.toString())
    fun loadMsgs(c: String): List<Msg> = runCatching { val a = JSONArray(f("c_$c.json").readText()); (0 until a.length()).map { Msg.from(a.getJSONObject(it), c) } }.getOrDefault(emptyList())
    fun clear() { runCatching { dir.listFiles()?.forEach { if (it.name != "outbox.json") it.delete() } } }

    companion object {
        fun presenceFrom(o: JSONObject?): Map<String, Presence> = o?.keys()?.asSequence()?.associateWith { k -> o.optJSONObject(k)?.let { Presence(it.optBoolean("online"), chatMs(it.opt("seen"))) } ?: Presence(false, 0) }.orEmpty()
    }
}

/** The live connection (a WebSocket). Tests use a fake one. */
interface ChatWire {
    /** [code]: the close code (4401: blocked, removed or signed out, don't reconnect); 0 for a dropped link. */
    interface Listener { fun onOpen(); fun onText(text: String); fun onClosed(code: Int = 0) }
    interface Conn { fun send(text: String): Boolean; fun close() }
    fun open(url: String, headers: Map<String, String>, l: Listener): Conn
}

/** The real one: OkHttp's WebSocket, with a ping every 25 seconds so a dead link is noticed. */
class OkWire : ChatWire {
    private val client by lazy { okhttp3.OkHttpClient.Builder().pingInterval(25, java.util.concurrent.TimeUnit.SECONDS).connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build() }
    override fun open(url: String, headers: Map<String, String>, l: ChatWire.Listener): ChatWire.Conn {
        val req = okhttp3.Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        val ws = client.newWebSocket(req, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) = l.onOpen()
            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) = l.onText(text)
            override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) = l.onClosed(code)
            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) = l.onClosed(0)
        })
        return object : ChatWire.Conn { override fun send(text: String) = ws.send(text); override fun close() { ws.close(1000, null) } }
    }
}

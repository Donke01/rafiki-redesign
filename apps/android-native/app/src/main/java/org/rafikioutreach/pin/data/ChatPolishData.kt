package org.rafikioutreach.pin.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.rafikioutreach.pin.t
import java.text.BreakIterator
import java.time.LocalTime
import java.time.ZoneId
import java.time.YearMonth

/*
 * Chat polish round (chatpa): the shapes and settings the new chat features need, read as leniently as the rest of
 * the chat data (S/chatpolish-brief.md). Everything here works without the server's new fields: an older server just
 * means no pins, no "Seen by" and settings kept on this phone only.
 */

private fun JSONObject.cs(k: String): String = if (isNull(k)) "" else optString(k).let { if (it == "null") "" else it }

/** A pinned message in a chat (the slim bar at the top). The server may send only ids; the text then comes from the messages. */
data class PinNote(val id: String, val from: String = "", val kind: String = "text", val text: String = "", val by: String = "", val at: Long = 0L, val pinnedAt: Long = 0L) {
    fun json(): JSONObject = JSONObject().put("id", id).put("from", from).put("kind", kind).put("text", text).put("by", by).put("at", at).put("pinnedAt", pinnedAt)
    companion object {
        fun from(o: JSONObject) = PinNote(o.cs("id"), o.cs("from"), o.cs("kind").ifBlank { "text" }, o.cs("text"), o.cs("by"), chatMs(o.opt("at")), chatMs(o.opt("pinnedAt")))
        fun list(v: Any?): List<PinNote> = when (v) {
            is JSONArray -> (0 until v.length()).mapNotNull { i -> v.optJSONObject(i)?.let(::from) ?: v.optString(i).takeIf { it.isNotBlank() && it != "null" }?.let { PinNote(it) } }.filter { it.id.isNotBlank() }.take(3)
            else -> emptyList()
        }
    }
}

/**
 * A trip shared into a chat (kind 'trip'): what /api/itin/<id>?card=1 answers. The server resolves it fresh when the
 * messages are fetched; someone who may not see the trip gets the title only ([limited]).
 */
data class TripCard(val id: String, val title: String, val dates: String = "", val from: String = "", val to: String = "", val next: String = "", val done: Int = 0, val total: Int = 0, val limited: Boolean = false,
                    val status: String = "", val nextDate: String = "", val nextAt: String = "", val gone: Boolean = false) {
    fun json(): JSONObject = JSONObject().put("id", id).put("title", title).put("from", from).put("to", to).put("status", status)
        .put("next", if (next.isNotBlank()) JSONObject().put("name", next).put("date", nextDate).put("at", nextAt.ifBlank { null }) else JSONObject.NULL)
        .put("done", done).put("total", total).put("access", !limited).apply { if (dates.isNotBlank()) put("dates", dates) }
    val progress get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    companion object {
        fun from(o: JSONObject?): TripCard? {
            o ?: return null
            val id = o.cs("id").ifBlank { o.cs("itin") }
            if (id.isBlank() && o.cs("title").isBlank()) return null
            val d = o.opt("dates")
            val (from, to, dates) = when (d) {
                is JSONObject -> Triple(d.cs("from"), d.cs("to"), "")
                is JSONArray -> Triple(d.optString(0), d.optString(1), "")
                is String -> if (d == "null") Triple(o.cs("from"), o.cs("to"), "") else Triple(o.cs("from"), o.cs("to"), d)
                else -> Triple(o.cs("from"), o.cs("to"), "")
            }
            val nx = o.optJSONObject("next")
            val next = o.opt("next").let { n -> when (n) { is JSONObject -> n.cs("name").ifBlank { n.cs("title") }; is String -> if (n == "null") "" else n; else -> "" } }
            val prog = o.optJSONObject("progress")
            val done = if (o.has("done")) o.optInt("done") else prog?.optInt("done") ?: 0
            val total = if (o.has("total")) o.optInt("total") else prog?.optInt("total") ?: 0
            val limited = (o.has("access") && !o.optBoolean("access", true)) || o.optBoolean("limited")
            return TripCard(id, o.cs("title").ifBlank { o.cs("name") }, dates, from, to, next, done, total, limited, o.cs("status"), nx?.cs("date").orEmpty(), nx?.cs("at").orEmpty(), o.optBoolean("gone"))
        }
    }
}

/** Do-Not-Disturb hours ("21:00" to "06:30"): no sounds, quiet notifications, no push. [mentions]: @mentions still ring. */
data class Dnd(val on: Boolean = false, val from: String = "21:00", val to: String = "06:30", val mentions: Boolean = false) {
    /** tz: minutes east of UTC on this phone, so the server keeps the same quiet hours for push. */
    fun json(): JSONObject = JSONObject().put("on", on).put("from", from).put("to", to).put("mentions", mentions)
        .put("tz", java.time.ZonedDateTime.now().offset.totalSeconds / 60)
    /** Inside the quiet hours now (they may run past midnight). */
    fun active(ms: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (!on) return false
        val a = parse(from) ?: return false; val b = parse(to) ?: return false
        if (a == b) return false
        val now = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
        return if (a < b) !now.isBefore(a) && now.isBefore(b) else !now.isBefore(a) || now.isBefore(b)
    }
    companion object {
        fun parse(s: String): LocalTime? = runCatching { LocalTime.parse(s.trim().padStart(5, '0')) }.getOrNull()
        fun from(v: Any?): Dnd = when (v) {
            is JSONObject -> Dnd(if (v.has("on")) v.optBoolean("on") else (v.cs("from").isNotBlank() && v.cs("to").isNotBlank()), v.cs("from").ifBlank { "21:00" }, v.cs("to").ifBlank { "06:30" }, v.optBoolean("mentions"))
            else -> Dnd()
        }
    }
}

/** The six quick replies to start with (English and Kiswahili); the user can change them in Chat settings (8 at most). */
val QUICK_DEFAULTS = listOf(
    "On my way" to "Niko njiani", "Arrived at site" to "Nimefika eneo la kazi", "Pump fixed ✅" to "Pampu imetengenezwa ✅",
    "Need parts" to "Nahitaji vipuri", "Running late" to "Nimechelewa", "Call me" to "Nipigie simu",
)
fun quickDefaults(): List<String> = QUICK_DEFAULTS.map { (en, sw) -> t(en, sw) }
const val QUICK_MAX = 8

/**
 * My chat settings. Synced with the server (prefs: quick, dnd, doubleTap) so they follow me to the web app; kept on
 * the phone too, so they work offline and with a server that doesn't know them yet. The data saver, sounds and the
 * data counter are this phone's own.
 */
class ChatSettings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("chat_settings", Context.MODE_PRIVATE)
    /** Quick replies; empty = the defaults (in the app's language). */
    var quick: List<String>
        get() = runCatching { JSONArray(sp.getString("quick", "") ?: "").let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } }.getOrDefault(emptyList()).ifEmpty { quickDefaults() }
        set(v) { sp.edit().putString("quick", JSONArray(v.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(QUICK_MAX)).toString()).apply() }
    val quickCustom get() = (sp.getString("quick", "") ?: "").let { it.isNotBlank() && it != "[]" }
    fun quickReset() = sp.edit().remove("quick").apply()
    var dnd: Dnd
        get() = runCatching { Dnd.from(JSONObject(sp.getString("dnd", "") ?: "")) }.getOrDefault(Dnd())
        set(v) { sp.edit().putString("dnd", v.json().toString()).apply() }
    /** The emoji a double tap reacts with: ❤️ (the default) or 👍. */
    var doubleTap: String get() = sp.getString("doubleTap", "❤️") ?: "❤️"; set(v) = sp.edit().putString("doubleTap", v).apply()
    /** "off", "mobile" (the default: on mobile data and other metered networks) or "always". */
    var saver: String get() = sp.getString("saver", "mobile") ?: "mobile"; set(v) = sp.edit().putString("saver", v).apply()
    var sendSound: Boolean get() = sp.getBoolean("sendSound", true); set(v) = sp.edit().putBoolean("sendSound", v).apply()
    var mentionSound: Boolean get() = sp.getBoolean("mentionSound", true); set(v) = sp.edit().putBoolean("mentionSound", v).apply()

    /** What the server knows of these (prefs in the list answer, or the prefs call's answer). */
    fun fromServer(o: JSONObject?) {
        o ?: return
        if (o.has("quick")) o.optJSONArray("quick")?.let { a -> val l = (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }; if (l.isEmpty()) quickReset() else quick = l } ?: quickReset()
        if (o.has("dnd") && !o.isNull("dnd")) dnd = Dnd.from(o.opt("dnd"))
        o.cs("doubleTap").takeIf { it.isNotBlank() }?.let { doubleTap = it }
    }
    fun serverJson(): JSONObject = JSONObject().put("quick", if (quickCustom) JSONArray(quick) else JSONObject.NULL).put("dnd", dnd.json()).put("doubleTap", doubleTap)

    // --- the data counter: chat downloads and uploads this month, on this phone --------------------------------------
    private fun monthKey() = "data_" + YearMonth.now()
    fun count(bytes: Long) { if (bytes > 0) sp.edit().putLong(monthKey(), sp.getLong(monthKey(), 0L) + bytes).apply() }
    val usedThisMonth: Long get() = sp.getLong(monthKey(), 0L)
}

/** The data saver is on right now: always, or on mobile data and other metered networks. */
fun saverOn(ctx: Context, mode: String): Boolean = when (mode) {
    "always" -> true
    "off" -> false
    else -> runCatching { (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered }.getOrDefault(false)
}

/** "3.2 MB", "850 KB", "1.4 GB" (the app's storage convention). */
fun dataText(b: Long): String = when {
    b < 1024 * 1024 -> "${(b + 1023) / 1024} KB"
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(java.util.Locale.ROOT, b / 1048576.0)
    else -> "%.1f GB".format(java.util.Locale.ROOT, b / 1073741824.0)
}

/** 1–3 emoji and nothing else (spaces aside): shown large, without a bubble. 0 otherwise. */
fun emojiOnly(text: String): Int {
    val s = text.trim()
    if (s.isEmpty() || s.length > 40) return 0
    val it = BreakIterator.getCharacterInstance(); it.setText(s)
    var n = 0; var start = it.first(); var end = it.next()
    while (end != BreakIterator.DONE) {
        val g = s.substring(start, end)
        if (g.isNotBlank()) { if (!isEmoji(g)) return 0; n++; if (n > 3) return 0 }
        start = end; end = it.next()
    }
    return n
}
private fun isEmoji(g: String): Boolean {
    var any = false
    var i = 0
    while (i < g.length) {
        val cp = g.codePointAt(i); i += Character.charCount(cp)
        when {
            cp == 0x200D || cp == 0xFE0F || cp == 0xFE0E || cp in 0x1F3FB..0x1F3FF || cp == 0x20E3 || cp in 0xE0020..0xE007F -> {}
            cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF || cp in 0x2190..0x21FF || cp in 0x2300..0x23FF || cp in 0x1F1E6..0x1F1FF || cp == 0x00A9 || cp == 0x00AE || cp == 0x2122 || cp == 0x3030 || cp == 0x303D -> any = true
            (cp in 0x30..0x39 || cp == 0x23 || cp == 0x2A) && g.contains('⃣') -> any = true
            else -> return false
        }
    }
    return any
}

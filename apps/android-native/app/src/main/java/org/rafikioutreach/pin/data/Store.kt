package org.rafikioutreach.pin.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.rafikioutreach.pin.t
import java.io.File

/** Small settings (PIN, name, units) plus the last project list, kept on the phone. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("rafiki-pin", Context.MODE_PRIVATE)
    private val listFile = File(context.filesDir, "projects.json")

    var pin: String get() = sp.getString("pin", "") ?: ""; set(v) = sp.edit().putString("pin", v).apply()
    var name: String get() = sp.getString("name", "") ?: ""; set(v) = sp.edit().putString("name", v).apply()
    var miles: Boolean get() = sp.getBoolean("miles", false); set(v) = sp.edit().putBoolean("miles", v).apply()
    var filter: String get() = sp.getString("filter", "missing") ?: "missing"; set(v) = sp.edit().putString("filter", v).apply()
    var country: String get() = sp.getString("country", "") ?: ""; set(v) = sp.edit().putString("country", v).apply()
    var session: String get() = sp.getString("session", "") ?: ""; set(v) = sp.edit().putString("session", v).apply()
    var verifier: String get() = sp.getString("verifier", "") ?: ""; set(v) = sp.edit().putString("verifier", v).apply()
    var route: String get() = sp.getString("route", "") ?: ""; set(v) = sp.edit().putString("route", v).apply()
    var onb: Boolean get() = sp.getBoolean("onb", false); set(v) = sp.edit().putBoolean("onb", v).apply()
    var theme: String get() = sp.getString("theme", "") ?: ""; set(v) = sp.edit().putString("theme", v).apply()
    /** The newest app on the website, as last seen: version code, name, what's new, and when it was checked (ms). */
    var updCode: Int get() = sp.getInt("updCode", 0); set(v) = sp.edit().putInt("updCode", v).apply()
    var updName: String get() = sp.getString("updName", "") ?: ""; set(v) = sp.edit().putString("updName", v).apply()
    var updNotes: String get() = sp.getString("updNotes", "") ?: ""; set(v) = sp.edit().putString("updNotes", v).apply()
    var updSha: String get() = sp.getString("updSha", "") ?: ""; set(v) = sp.edit().putString("updSha", v).apply()
    var updAt: Long get() = sp.getLong("updAt", 0L); set(v) = sp.edit().putLong("updAt", v).apply()
    /** "Remind me later" on the update pop-up: don't ask again before this time. */
    var updLater: Long get() = sp.getLong("updLater", 0L); set(v) = sp.edit().putLong("updLater", v).apply()
    /** Text size: "s" small, "" normal, "l" large, "xl" larger. */
    var textSize: String get() = sp.getString("textSize", "") ?: ""; set(v) = sp.edit().putString("textSize", v).apply()
    /** Me & team: which half is showing, "me" or "team". */
    var mePane: String get() = sp.getString("mePane", "me") ?: "me"; set(v) = sp.edit().putString("mePane", v).apply()
    var lang: String get() = sp.getString("lang", "") ?: ""; set(v) = sp.edit().putString("lang", v).apply()

    fun saveSnapshot(raw: JSONObject) = runCatching { listFile.writeText(raw.toString()) }
    fun loadSnapshot(): JSONObject? = runCatching { JSONObject(listFile.readText()) }.getOrNull()
    var bio: Boolean get() = sp.getBoolean("bio", false); set(v) = sp.edit().putBoolean("bio", v).apply()
    fun forget() { pin = ""; session = ""; bio = false }
}

/** One change waiting to be sent: the action, its photos, and how far it got. */
data class OutItem(
    val k: Long,
    val body: JSONObject,
    val files: List<OutFile>,
    var updateId: String? = null,
    var done: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("k", k).put("body", body).put("updateId", updateId ?: JSONObject.NULL).put("done", done)
        .put("files", JSONArray().apply { files.forEach { put(JSONObject().put("path", it.path).put("kind", it.kind).put("label", it.label)) } })

    companion object {
        fun from(o: JSONObject) = OutItem(
            o.getLong("k"), o.getJSONObject("body"),
            o.optJSONArray("files")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { f -> OutFile(f.getString("path"), f.optString("kind", "photo"), f.optString("label")) } } } ?: emptyList(),
            o.optString("updateId").takeIf { it.isNotBlank() && it != "null" }, o.optInt("done"),
        )
    }
}
data class OutFile(val path: String, val kind: String, val label: String)

/** Every change is written to the phone first, then sent; nothing is lost without signal. */
class Outbox(private val file: File) {
    @Synchronized fun all(): List<OutItem> = runCatching {
        val a = JSONArray(file.readText()); (0 until a.length()).map { OutItem.from(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    @Synchronized private fun save(items: List<OutItem>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONArray().apply { items.forEach { put(it.toJson()) } }.toString())
        tmp.renameTo(file)
    }

    @Synchronized fun add(body: JSONObject, files: List<OutFile>): Long {
        val items = all(); val k = (items.maxOfOrNull { it.k } ?: 0) + 1
        save(items + OutItem(k, body, files)); return k
    }
    @Synchronized fun put(item: OutItem) = save(all().map { if (it.k == item.k) item else it })
    @Synchronized fun remove(k: Long) {
        val items = all(); items.firstOrNull { it.k == k }?.files?.forEach { runCatching { File(it.path).delete() } }
        save(items.filter { it.k != k })
    }
    fun count() = all().size

    data class Result(val sent: List<Long>, val failed: Map<Long, String>, val waiting: Int, val locked: Boolean, val results: Map<Long, Reply>)

    /** Sends everything waiting, oldest first. Same rules as the web app's outbox. */
    suspend fun flush(api: Api): Result {
        val sent = mutableListOf<Long>(); val failed = mutableMapOf<Long, String>(); val results = mutableMapOf<Long, Reply>()
        var waiting = 0; var locked = false
        for (it in all().sortedBy { i -> i.k }) {
            if (locked) { waiting++; continue }
            val r = if (it.updateId != null) Reply(true, 200, JSONObject().put("ok", true).put("updateId", it.updateId), null) else api.post(it.body)
            if (!r.ok) {
                when {
                    r.retryLater -> waiting++
                    r.status == 401 -> { locked = true; waiting++ }
                    else -> { failed[it.k] = reasonText(r.reason); remove(it.k) }
                }
                continue
            }
            results[it.k] = r
            val upd = r.json.optString("updateId")
            if (it.files.isEmpty() || upd.isBlank()) { sent += it.k; remove(it.k); continue }
            if (it.updateId == null) { it.updateId = upd; it.done = 0; put(it) }
            var lost = 0; var stopped = false
            for ((i, f) in it.files.withIndex()) {
                if (i < it.done) continue
                val p = api.upload(it.updateId!!, File(f.path), f.kind, f.label)
                if (p.ok) { it.done = i + 1; put(it); continue }
                if (p.retryLater || p.status == 401) { stopped = true; break }
                lost++; it.done = i + 1; put(it)
            }
            if (stopped) { waiting++; continue }
            sent += it.k
            if (lost > 0) failed[it.k] = t("Saved, but " + (if (lost == 1) "one file" else "$lost files") + " couldn’t be added.", "Imehifadhiwa, lakini " + (if (lost == 1) "faili moja" else "faili $lost") + " hazikuweza kuongezwa.")
            remove(it.k)
        }
        return Result(sent, failed, waiting, locked, results)
    }
}

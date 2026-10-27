package org.rafikioutreach.pin.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.rafikioutreach.pin.t
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Profile photos (feat-mea, S/me-contract.md): who has one (name → the URL the server gave, with its version),
 * and the pictures themselves, kept on the phone so the last photo still shows without signal.
 *
 * The URLs come from every list that carries them: the app's list (/api/pin "photos" and me.photo), the team
 * lists (?team=1, Office › Team, the team map) and chat presence. A new photo is a new URL, so a URL is cached for good.
 * Anyone without a URL (or whose picture won't load) keeps their initials.
 */
object TeamPhotos {
    /** Lower-case trimmed name → URL ("/api/team-photo?name=…&v=…"). Compose reads it, so avatars change by themselves. */
    val urls = mutableStateMapOf<String, String>()
    /** Donor mode: team photos are never asked for or shown. */
    @Volatile var off = false
    /** Goes up whenever a picture arrives, for things drawn outside Compose (map markers) to draw again. */
    var tick by androidx.compose.runtime.mutableIntStateOf(0)
    private var api: Api? = null
    private var dir: File? = null
    private val mem = android.util.LruCache<String, Bitmap>(80)
    private val inflight = HashMap<String, CompletableDeferred<Bitmap?>>()

    fun key(name: String) = name.trim().lowercase()

    fun init(context: Context, api: Api) {
        this.api = api
        dir = File(context.filesDir, "teamphotos").apply { mkdirs() }
        // the last known URLs, so a fresh open without signal still shows faces
        runCatching { JSONObject(File(dir, "index.json").readText()) }.getOrNull()?.let { j -> j.keys().forEach { k -> j.optString(k).takeIf { it.isNotBlank() }?.let { urls[k] = it } } }
    }

    /** This person's photo URL, or null for initials. */
    fun url(name: String?): String? = if (off || name.isNullOrBlank()) null else urls[key(name)]?.takeIf { it.isNotBlank() }

    /** One person's photo changed (or went: [url] ""). */
    fun set(name: String, url: String) {
        if (name.isBlank()) return
        val k = key(name)
        if (url.isBlank()) { if (urls.remove(k) != null) save() } else if (urls[k] != url) { urls[k] = url; save() }
    }

    /** Takes the URLs out of any server reply that carries them. [path] is the request's path. */
    fun absorb(path: String, j: JSONObject) {
        if (off) return
        // the app's list: "photos" is everyone who has one, so anyone missing from it has none now
        if (path.startsWith("/api/pin") && !path.contains("?") && j.has("projects") && j.has("photos")) {
            val ps = j.optJSONObject("photos") ?: JSONObject()
            val fresh = HashMap<String, String>()
            ps.keys().forEach { n -> ps.optString(n).takeIf { it.isNotBlank() }?.let { fresh[key(n)] = it } }
            val who = j.optString("who")
            j.optJSONObject("me")?.takeIf { it.has("photo") }?.optString("photo")?.let { u -> if (who.isNotBlank()) { if (u.isBlank()) fresh.remove(key(who)) else fresh[key(who)] = u } }
            if (fresh != urls.toMap()) { urls.keys.retainAll(fresh.keys); urls.putAll(fresh); save() }
            return
        }
        // a list of people: the team, the team map, chat members
        listOf("team", "people", "members").forEach { a -> j.optJSONArray(a)?.let { people(it) } }
        // chat presence: { "<Name>": { online, seen, photo } }
        j.optJSONObject("presence")?.let { p -> p.keys().forEach { n -> p.optJSONObject(n)?.takeIf { it.has("photo") }?.let { set(n, it.optString("photo")) } } }
    }

    private fun people(a: JSONArray) {
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.has("photo") && o.optString("name").isNotBlank()) set(o.optString("name"), o.optString("photo").takeIf { it != "null" }.orEmpty())
        }
    }

    private fun save() {
        val d = dir ?: return
        runCatching { File(d, "index.json").writeText(JSONObject(urls.toMap() as Map<*, *>).toString()) }
    }

    private fun file(name: String): File? = dir?.let { File(it, hash(key(name)) + ".img") }
    private fun hash(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

    /** Already in memory: no loading flash when a list scrolls. */
    fun cached(url: String?): Bitmap? = url?.let { mem.get(it) }

    /**
     * The picture for [name] at [url]: from memory, the phone, or the server (with the usual sign-in). Without signal
     * (or if the server fails) the last picture kept for that person; null when there is none (initials).
     */
    suspend fun load(name: String, url: String): Bitmap? {
        if (off) return null
        mem.get(url)?.let { return it }
        val (d, mine) = synchronized(inflight) {
            inflight[url]?.let { it to false } ?: CompletableDeferred<Bitmap?>().also { inflight[url] = it }.let { it to true }
        }
        if (!mine) return d.await()
        val b = runCatching { fetch(name, url) }.getOrNull()
        synchronized(inflight) { inflight.remove(url) }
        if (b != null) tick++
        d.complete(b)
        return b
    }

    private suspend fun fetch(name: String, url: String): Bitmap? = withContext(Dispatchers.IO) {
        val f = file(name); val tag = f?.let { File(it.path + ".url") }
        if (f != null && f.exists() && tag?.takeIf { it.exists() }?.readText() == url) decode(f.readBytes())?.let { mem.put(url, it); return@withContext it }
        val a = api
        val full = if (url.startsWith("http")) url else (a?.server ?: "") + url
        // the sign-in headers only ever go to our own server
        val (code, bytes) = if (a != null && full.startsWith(a.server)) a.fetch(full, a.authHeaders()) else 0 to null
        if (code in 200..299 && bytes != null) {
            val b = decode(bytes)
            if (b != null) { mem.put(url, b); if (f != null) runCatching { f.writeBytes(bytes); tag!!.writeText(url) }; return@withContext b }
        }
        if (code == 404 && f != null) { f.delete(); tag?.delete(); return@withContext null }
        // no signal or a hiccup: the last photo kept for them (not remembered under the new URL, so it's tried again)
        if (f != null && f.exists()) decode(f.readBytes()) else null
    }

    /** Draws [name]'s photo as a circle (map markers), if it's already loaded. Returns false to draw initials instead. */
    fun drawFace(c: android.graphics.Canvas, name: String, cx: Float, cy: Float, r: Float, faded: Boolean = false): Boolean {
        val b = cached(url(name)) ?: return false
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        val sh = android.graphics.BitmapShader(b, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        val side = minOf(b.width, b.height).toFloat(); val k = 2 * r / side
        sh.setLocalMatrix(android.graphics.Matrix().apply { setScale(k, k); postTranslate(cx - b.width * k / 2, cy - b.height * k / 2) })
        p.shader = sh; if (faded) p.alpha = 150
        c.drawCircle(cx, cy, r, p)
        return true
    }

    private fun decode(b: ByteArray): Bitmap? = runCatching { BitmapFactory.decodeByteArray(b, 0, b.size) }.getOrNull()

    /** Sets a photo ([forName] null = my own; another name needs an admin). Returns the person's new URL, or an error text. */
    suspend fun upload(api: Api, forName: String?, jpeg: ByteArray): Pair<String?, String?> {
        val r = api.postRaw(path(forName), jpeg, "image/jpeg")
        if (!r.ok) return null to why(r)
        val who = r.json.optString("name").ifBlank { forName.orEmpty() }
        val url = r.json.optString("photo")
        // shown at once, and kept, without fetching it back
        decode(jpeg)?.let { b -> if (url.isNotBlank()) { mem.put(url, b); tick++ } }
        withContext(Dispatchers.IO) { file(who)?.let { f -> runCatching { f.writeBytes(jpeg); File(f.path + ".url").writeText(url) } } }
        set(who, url)
        return url to null
    }

    /** Removes a photo. Returns an error text, or null when done. */
    suspend fun remove(api: Api, forName: String?, me: String): String? {
        val r = api.postTo(path(forName), JSONObject().put("remove", true))
        if (!r.ok) return why(r)
        val who = r.json.optString("name").ifBlank { forName ?: me }
        set(who, "")
        withContext(Dispatchers.IO) { file(who)?.let { f -> f.delete(); File(f.path + ".url").delete() } }
        return null
    }

    private fun path(forName: String?) = "/api/team-photo" + (forName?.takeIf { it.isNotBlank() }?.let { "?name=" + Api.enc(it) } ?: "")

    fun why(r: Reply): String = when {
        r.status == 0 -> t("No signal. Try again when you’re online.", "Hakuna mtandao. Jaribu tena ukiwa mtandaoni.")
        r.reason == "too-large" -> t("That photo is too big. Try another one.", "Picha hiyo ni kubwa mno. Jaribu nyingine.")
        r.reason == "type" -> t("That kind of picture can’t be used. Choose a photo.", "Aina hiyo ya picha haiwezi kutumika. Chagua picha.")
        r.reason == "forbidden" -> t("Only admins can change someone else’s photo.", "Wasimamizi pekee wanaweza kubadilisha picha ya mtu mwingine.")
        r.reason == "named-only" -> t("You need your own PIN to add a photo. Ask the office for one.", "Unahitaji PIN yako mwenyewe kuweka picha. Omba ofisi ikupe.")
        r.reason == "no-person" -> t("They aren’t on the team list.", "Hayupo kwenye orodha ya timu.")
        r.reason == "rate" -> reasonText("rate")
        else -> reasonText(r.reason)
    }
}

/** Shrinks a chosen picture for a profile photo: a square, about 512 px, JPEG, 200 KB or less. */
object AvatarImage {
    const val SIZE = 512
    const val MAX_BYTES = 200_000

    /**
     * The square of the original picture under the round frame. The picture fills a [view] px square at [zoom] (1 = just
     * covers it), moved by ([dx], [dy]) px from the centre. Returns left, top and side in the picture's pixels.
     */
    fun cropRect(w: Int, h: Int, view: Float, zoom: Float, dx: Float, dy: Float): IntArray {
        val s = view / minOf(w, h) * zoom
        val side = (view / s).coerceAtMost(minOf(w, h).toFloat())
        val cx = w / 2f - dx / s; val cy = h / 2f - dy / s
        val l = (cx - side / 2).coerceIn(0f, w - side); val tp = (cy - side / 2).coerceIn(0f, h - side)
        return intArrayOf(l.toInt(), tp.toInt(), side.toInt().coerceAtLeast(1))
    }

    /** How far the picture may move from the centre at [zoom] and still cover the frame (px each way, x and y). */
    fun maxShift(w: Int, h: Int, view: Float, zoom: Float): Pair<Float, Float> {
        val s = view / minOf(w, h) * zoom
        return ((w * s - view) / 2f).coerceAtLeast(0f) to ((h * s - view) / 2f).coerceAtLeast(0f)
    }

    fun crop(src: Bitmap, r: IntArray): Bitmap {
        val side = minOf(r[2], src.width - r[0], src.height - r[1])
        val sq = Bitmap.createBitmap(src, r[0], r[1], side, side)
        return if (side > SIZE) Bitmap.createScaledBitmap(sq, SIZE, SIZE, true) else sq
    }

    /** JPEG at the best quality that stays under [MAX_BYTES] (smaller if it must). */
    fun jpeg(b: Bitmap): ByteArray {
        var bmp = b
        while (true) {
            var q = 88
            while (q >= 50) {
                val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
                if (out.size() <= MAX_BYTES) return out.toByteArray()
                q -= 8
            }
            if (bmp.width <= 128) { val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 40, out); return out.toByteArray() }
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * 0.8f).toInt(), (bmp.height * 0.8f).toInt(), true)
        }
    }
}

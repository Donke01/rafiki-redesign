package org.rafikioutreach.pin.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Story photos on their way to the server (2.27.0, the owner's request: "show an animated process … allow selecting
 * several photos"). Each photo is made smaller on the phone (longest side 1600 px, JPEG 82, turned the right way up),
 * kept in app storage with a small JSON list, and sent one after another to POST /api/story { action: photo, … } with
 * its progress. The list survives leaving the screen, the app being closed and no signal: it carries on when the
 * phone is back online, when the app starts and when a story opens.
 */
class StoryUploads(private val dir: File, private val api: Api, private val scope: CoroutineScope, private val ctx: Context? = null) {
    /** One photo: [state] queued → sending → done, or waiting (no signal; sends by itself) or failed ([reason]; Tap to retry). */
    class Up(val key: String, val project: String, val row: String, val children: Boolean, val cap: String, val file: String, val batch: String, val taken: String = "") {
        var state by mutableStateOf("queued")
        var progress by mutableFloatStateOf(0f)
        var reason by mutableStateOf("")
        /** The photo's asset id on the server once it is there. */
        var asset by mutableStateOf("")
        /** Hidden from the banner (Hide, or the banner said "added ✓" and went). */
        var seen by mutableStateOf(false)
        val active get() = state == "queued" || state == "sending" || state == "waiting"
    }

    val items = mutableStateListOf<Up>()
    /** The freshest story from the server per project (every photo answer carries it), for an open story writer. */
    val fresh = mutableStateMapOf<String, JSONObject>()
    /** Tests: hold the queue still (nothing is sent) to look at it. */
    var paused = false
    private var job: Job? = null
    private val list = File(dir, "queue.json")

    init {
        dir.mkdirs()
        load()
        runCatching {
            (ctx?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { if (items.any { it.state == "waiting" }) scope.launch(Dispatchers.Main) { resume() } }
            })
        }
    }

    /** Photos still to send for a project (queued, sending or waiting for signal). */
    fun sending(project: String? = null) = items.count { it.active && (project == null || it.project == project) }

    /**
     * Adds photos ([files] with "shows children") to a project's row: shrinks each into app storage, then sends them.
     * Each carries the day it was taken (EXIF DateTimeOriginal; a camera shot taken now: today), sent quietly with it.
     * The originals are left alone (a gallery copy or a camera file in the cache is deleted).
     */
    suspend fun add(project: String, row: String, files: List<Pair<File, Boolean>>, camera: Boolean = false) {
        val batch = "b" + System.currentTimeMillis()
        val made = withContext(Dispatchers.IO) {
            files.mapIndexedNotNull { i, (src, kids) ->
                val out = File(dir, "${batch}_$i.jpg")
                // the day it was taken: from the photo itself (the shrunk copy has no EXIF), else today for a camera shot
                val day = takenDay(src) ?: if (camera) java.time.LocalDate.now().toString() else ""
                if (shrink(src, out)) { if (src.path.contains("/cache/")) src.delete(); Up("${batch}_$i", project, row, kids, "", out.path, batch, day) } else null
            }
        }
        items.addAll(made); save(); resume()
    }

    /** Sends what is waiting (on start, back online, a story opened). */
    fun resume() {
        items.filter { it.state == "waiting" }.forEach { it.state = "queued" }
        if (paused || job?.isActive == true || items.none { it.state == "queued" }) return
        job = scope.launch { run() }
    }

    /** Tap to retry: a photo that couldn't send (or all of them with [key] null) goes back in the queue. */
    fun retry(key: String? = null) {
        items.filter { it.state == "failed" && (key == null || it.key == key) }.forEach { it.state = "queued"; it.reason = ""; it.progress = 0f; it.seen = false }
        save(); resume()
    }

    /** Hide the banner for these photos (they keep sending). */
    fun hide(project: String) { items.filter { it.project == project }.forEach { it.seen = true }; tidy() }

    /** Done photos leave the list once nobody needs to see them. */
    fun tidy() { items.removeAll { it.state == "done" && it.seen }; save() }

    private suspend fun run() {
        while (!paused) {
            val u = items.firstOrNull { it.state == "queued" } ?: break
            u.state = "sending"; u.progress = 0f
            val f = File(u.file)
            val bytes = withContext(Dispatchers.IO) { runCatching { f.readBytes() }.getOrNull() }
            if (bytes == null) { u.state = "failed"; u.reason = "missing"; save(); continue }
            val fields = linkedMapOf("action" to "photo", "id" to u.project, "row" to u.row, "children" to u.children.toString())
            if (u.cap.isNotBlank()) fields["cap"] = u.cap
            if (u.taken.isNotBlank()) fields["taken"] = u.taken
            var last = -1
            val r = api.postMultipart("/api/story", bytes, "image/jpeg", "story.jpg", fields) { p ->
                val pc = (p * 100).toInt(); if (pc != last) { last = pc; scope.launch(Dispatchers.Main) { if (u.state == "sending") u.progress = p } }
            }
            when {
                r.ok -> {
                    u.asset = r.json.optJSONObject("photo")?.optString("id").orEmpty(); u.progress = 1f; u.state = "done"
                    r.json.optJSONObject("story")?.let { fresh[u.project] = it }
                    withContext(Dispatchers.IO) { f.delete() }
                }
                r.status == 0 -> {   // no signal: this one and the rest wait, and go by themselves when the phone is back online
                    u.state = "waiting"; u.progress = 0f
                    items.filter { it.state == "queued" }.forEach { it.state = "waiting" }
                    save(); return
                }
                else -> { u.state = "failed"; u.reason = r.reason ?: "down"; u.progress = 0f }
            }
            save()
        }
    }

    // --- the list on disk: what is still to send (and what failed, with why) ---------------------------------------
    @Synchronized private fun save() {
        val a = JSONArray()
        items.toList().filter { it.state != "done" }.forEach { u ->
            a.put(JSONObject().put("key", u.key).put("project", u.project).put("row", u.row).put("children", u.children).put("cap", u.cap).put("file", u.file).put("batch", u.batch).put("taken", u.taken)
                .put("failed", u.state == "failed").put("reason", u.reason))
        }
        runCatching { list.writeText(a.toString()) }
    }
    private fun load() {
        val a = runCatching { JSONArray(list.readText()) }.getOrNull() ?: return
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (!File(o.optString("file")).exists()) continue
            items += Up(o.optString("key"), o.optString("project"), o.optString("row"), o.optBoolean("children"), o.optString("cap"), o.optString("file"), o.optString("batch"), o.optString("taken")).apply {
                if (o.optBoolean("failed")) { state = "failed"; reason = o.optString("reason") }
            }
        }
    }

    companion object {
        /** "2024-11-02" from the photo's EXIF DateTimeOriginal ("2024:11:02 10:42:00"), or null. */
        fun takenDay(f: File): String? = runCatching {
            val e = ExifInterface(f.path)
            val v = e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: e.getAttribute(ExifInterface.TAG_DATETIME)
            Regex("^(\\d{4}):(\\d{2}):(\\d{2})").find(v.orEmpty())?.groupValues?.let { g -> "${g[1]}-${g[2]}-${g[3]}" }?.takeIf { it >= "2000-01-01" && it <= java.time.LocalDate.now().toString() }
        }.getOrNull()
        /** Longest side 1600 px, JPEG quality 82, turned upright from the camera's EXIF note. */
        fun shrink(src: File, out: File, max: Int = 1600): Boolean = runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(src.path, o)
            var sample = 1; while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= max) sample *= 2
            var bmp = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching false
            val k = max.toFloat() / maxOf(bmp.width, bmp.height)
            if (k < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
            val rot = when (runCatching { ExifInterface(src.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f
            }
            if (rot != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
            out.parentFile?.mkdirs()
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            true
        }.getOrDefault(false)
    }
}

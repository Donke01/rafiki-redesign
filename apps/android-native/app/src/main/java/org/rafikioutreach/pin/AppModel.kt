package org.rafikioutreach.pin

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.rafikioutreach.pin.data.Api
import org.rafikioutreach.pin.data.Fix
import org.rafikioutreach.pin.data.Locator
import org.rafikioutreach.pin.data.Note
import org.rafikioutreach.pin.data.NoteFile
import org.rafikioutreach.pin.data.OutFile
import org.rafikioutreach.pin.data.Outbox
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.data.Project
import org.rafikioutreach.pin.data.Repair
import org.rafikioutreach.pin.data.LastVisit
import org.rafikioutreach.pin.data.Snapshot
import org.rafikioutreach.pin.data.reasonText
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

/** What happened to a change: sent now, saved for later, or refused. */
sealed class Outcome {
    data class Sent(val reply: JSONObject, val note: String?) : Outcome()
    object Queued : Outcome()
    data class Failed(val text: String) : Outcome()
    object Locked : Outcome()
}

class AppModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)
    val api = Api(BuildConfig.SERVER, { prefs.pin }, { prefs.session })
    val outbox = Outbox(File(app.filesDir, "outbox.json"))
    val locator = Locator(app)
    private val sendLock = Mutex()

    private val _snap = MutableStateFlow<Snapshot?>(prefs.loadSnapshot()?.let { runCatching { Snapshot.from(it) }.getOrNull() })
    val snap: StateFlow<Snapshot?> = _snap
    private val _signedIn = MutableStateFlow((prefs.pin.isNotBlank() || prefs.session.isNotBlank()) && _snap.value != null)
    val signedIn: StateFlow<Boolean> = _signedIn
    private val _waiting = MutableStateFlow(outbox.count())
    val waiting: StateFlow<Int> = _waiting
    private val _banner = MutableStateFlow<String?>(null)
    val banner: StateFlow<String?> = _banner
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    val fix: StateFlow<Fix?> get() = locator.fix

    init {
        viewModelScope.launch { while (true) { delay(60000); if (_signedIn.value) sendWaiting() } }
    }

    fun showBanner(t: String?) { _banner.value = t }

    /** Sign in with a PIN; shared team PINs need a name. Returns an error text, or null when in. */
    suspend fun signIn(pin: String, name: String): String? {
        prefs.pin = pin
        val r = api.list()
        if (!r.ok) {
            prefs.pin = ""
            return if (r.status == 0) t("No signal. Connect to the internet to sign in the first time.", "Hakuna mtandao. Unganisha intaneti ili uingie mara ya kwanza.") else reasonText(r.reason)
        }
        val who = r.json.optString("who")
        if (who.isBlank() && name.isBlank()) { prefs.pin = ""; return "NEED_NAME" }
        prefs.name = who.ifBlank { name.trim() }
        accept(r.json)
        _signedIn.value = true
        return null
    }

    fun lock() {
        val s = prefs.session
        prefs.forget(); _signedIn.value = false
        if (s.isNotBlank()) viewModelScope.launch { Api(BuildConfig.SERVER, { "" }, { s }).also { it.transport = api.transport }.logout() }
    }

    /** Is "Sign in with monday.com" switched on for this server? */
    suspend fun mondayAvailable(): Boolean = api.authMe().json.optBoolean("available")

    /** The address that starts a monday.com sign-in. A secret stays on the phone; only its fingerprint goes out. */
    fun mondayStartUrl(): String {
        val rnd = java.security.SecureRandom(); val b = ByteArray(48); rnd.nextBytes(b)
        val verifier = android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
        prefs.verifier = verifier
        val cc = android.util.Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
        return BuildConfig.SERVER + "/api/pin-auth/start?app=1&cc=" + cc
    }

    /** monday.com sent the person back to the app. Returns an error text, or null when signed in. */
    suspend fun mondayReturn(code: String?, fail: String?): String? {
        if (fail != null || code.isNullOrBlank()) return when (fail) {
            "denied" -> t("Sign-in was cancelled.", "Kuingia kumeghairiwa.")
            "account" -> t("That monday.com login isn’t part of RAFIKI’s monday.com account. Sign in with your RAFIKI login.", "Akaunti hiyo ya monday.com si sehemu ya akaunti ya RAFIKI. Ingia kwa akaunti yako ya RAFIKI.")
            "board" -> t("Your monday.com login can’t see the projects board yet. Ask an admin to add you to it, then try again.", "Akaunti yako ya monday.com bado haioni bodi ya miradi. Muombe msimamizi akuongeze, kisha ujaribu tena.")
            "expired" -> t("Sign-in took too long. Please try again.", "Kuingia kumechukua muda mrefu. Tafadhali jaribu tena.")
            else -> t("monday.com didn’t finish the sign-in. Please try again.", "monday.com haikukamilisha kuingia. Tafadhali jaribu tena.")
        }
        val x = api.authExchange(code, prefs.verifier)
        prefs.verifier = ""
        val sid = x.json.optString("sid")
        if (!x.ok || sid.isBlank()) return if (x.status == 0) reasonText("offline") else t("Sign-in took too long. Please try again.", "Kuingia kumechukua muda mrefu. Tafadhali jaribu tena.")
        prefs.pin = ""; prefs.session = sid
        val r = api.list()
        if (!r.ok) { prefs.session = ""; return reasonText(r.reason) }
        prefs.name = r.json.optString("who")
        accept(r.json); _signedIn.value = true
        return null
    }

    // --- activity (for the Office overview and the monthly report) ---------------------------
    private val _activity = MutableStateFlow<List<JSONObject>?>(null)
    val activity: StateFlow<List<JSONObject>?> = _activity
    suspend fun loadActivity(): Boolean {
        val r = api.activity(); val a = r.json.optJSONArray("activity") ?: return false
        _activity.value = (0 until a.length()).map { a.getJSONObject(it) }; return true
    }
    /** Apply a fresh copy of the list (after an Office change). */
    fun reload() = refresh()

    private fun accept(raw: JSONObject) {
        prefs.saveSnapshot(raw); _snap.value = Snapshot.from(raw)
    }

    /** Sends anything waiting, then fetches the latest list. */
    fun refresh(done: ((Boolean) -> Unit)? = null) = viewModelScope.launch {
        _busy.value = true
        sendWaiting()
        val r = api.list()
        _busy.value = false
        when {
            r.ok -> { accept(r.json); r.json.optString("who").takeIf { it.isNotBlank() }?.let { prefs.name = it }; done?.invoke(true) }
            r.status == 401 -> { lock(); _banner.value = reasonText(r.reason); done?.invoke(false) }
            else -> { if (r.status == 0) _banner.value = t("No signal: showing the list saved on this phone.", "Hakuna mtandao: inaonyesha orodha iliyohifadhiwa kwenye simu hii."); done?.invoke(false) }
        }
    }

    suspend fun sendWaiting(): Outbox.Result = sendLock.withLock {
        val res = outbox.flush(api)
        _waiting.value = outbox.count()
        if (res.locked) { lock(); _banner.value = t("Your PIN stopped working. Please sign in again; nothing was lost.", "PIN yako imeacha kufanya kazi. Tafadhali ingia tena; hakuna kilichopotea.") }
        res
    }

    /** Queue a change, try to send it now, and say what happened. */
    suspend fun submit(body: JSONObject, files: List<OutFile>): Outcome {
        body.put("by", prefs.name)
        val k = outbox.add(body, files)
        _waiting.value = outbox.count()
        val res = sendWaiting()
        return when {
            res.locked -> Outcome.Locked
            res.failed.containsKey(k) && !res.sent.contains(k) -> Outcome.Failed(res.failed[k]!!)
            res.sent.contains(k) -> Outcome.Sent(res.results[k]?.json ?: JSONObject(), res.failed[k])
            else -> Outcome.Queued
        }
    }

    // --- local updates so the screens change straight away -----------------------------------
    fun localPin(p: Project, gps: org.rafikioutreach.pin.data.LatLng?) = edit { s ->
        s.copy(projects = s.projects.map { if (it.id == p.id) it.copy(gps = gps) else it })
    }
    fun localVisit(p: Project, state: String, note: String, urgency: String, problems: List<String>) = edit { s ->
        val now = Instant.now().toString()
        val reps = s.repairs.toMutableMap()
        if (state == "working") reps.remove(p.id) else reps[p.id] = Repair(state, prefs.name, now, note, urgency, problems, "", "reported")
        s.copy(repairs = reps, visits = s.visits + (p.id to LastVisit(now, prefs.name, state)))
    }
    fun localFixed(p: Project) = edit { s -> s.copy(repairs = s.repairs - p.id) }
    private fun edit(f: (Snapshot) -> Snapshot) { _snap.value?.let { _snap.value = f(it) } }

    suspend fun history(id: String): Pair<List<Note>?, String?> {
        val r = api.history(id)
        if (!r.ok) return null to reasonText(r.reason)
        val a = r.json.optJSONArray("notes") ?: return emptyList<Note>() to null
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val fs = o.optJSONArray("files")
            Note(o.optString("id"), o.optString("text"), o.optString("at"), o.optString("by"),
                fs?.let { f -> (0 until f.length()).map { j -> f.getJSONObject(j).let { NoteFile(it.optString("name"), it.optString("ext"), it.optString("url")) } } } ?: emptyList())
        } to null
    }

    /** The last pin on a project (for "Undo"). */
    suspend fun lastPin(id: String): JSONObject? = api.last(id).takeIf { it.ok }?.json?.optJSONObject("last")

    /** A camera photo, turned upright and shrunk to 1600 px, kept until it has been sent. */
    suspend fun keepPhoto(src: File): File? = withContext(Dispatchers.IO) {
        runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(src.path, o)
            var sample = 1; while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 1600) sample *= 2
            var bmp = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching null
            val k = 1600f / maxOf(bmp.width, bmp.height)
            if (k < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
            val rot = when (ExifInterface(src.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f
            }
            if (rot != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
            val dir = File(getApplication<Application>().filesDir, "photos").apply { mkdirs() }
            val out = File(dir, "p${System.currentTimeMillis()}.jpg")
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            src.delete(); out
        }.getOrNull()
    }
}

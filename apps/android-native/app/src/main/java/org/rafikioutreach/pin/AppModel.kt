package org.rafikioutreach.pin

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import org.rafikioutreach.pin.data.Me
import org.rafikioutreach.pin.data.Alert
import org.rafikioutreach.pin.data.alertsFor
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
    /** Donor mode (feat-donorapp): a donor's own projects, signed in with their link or code. */
    val donor = org.rafikioutreach.pin.data.DonorState(app, BuildConfig.SERVER)
    val locator = Locator(app)
    private val sendLock = Mutex()

    private val _snap = MutableStateFlow<Snapshot?>(prefs.loadSnapshot()?.let { runCatching { Snapshot.from(it) }.getOrNull() })
    init { org.rafikioutreach.pin.ui.GoogleTiles.configure(app, _snap.value?.gmapsAndroid) }
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
        // Settings › Currency: the saved choice now, fresh rates (and where the phone is) from the server
        org.rafikioutreach.pin.data.Currency.init(app, myHome())
        viewModelScope.launch { org.rafikioutreach.pin.data.Currency.refresh(BuildConfig.SERVER) }
        // blocked, or signed out of all phones by an admin: sign this phone out with a kind word (feat-conta)
        api.onAuthEnd = { why -> ended = why; viewModelScope.launch(Dispatchers.Main) { authEnded(why) } }
        api.gen = { prefs.pinGen }
    }

    /** Why the server ended this sign-in ("blocked" or "signed-out"), until the next sign-in. */
    @Volatile var ended: String? = null
        private set
    /** Signs this phone out (changes waiting to send stay) and says why, kindly, on the sign-in screen. */
    fun authEnded(why: String) {
        ended = why
        if (_signedIn.value) lock()
        _banner.value = reasonText(why)
    }

    // --- app updates: the newest Android app on the website ----------------------------------
    data class Update(val code: Int, val name: String, val notes: String, val at: Long, val sha256: String = "")
    private val _update = MutableStateFlow(prefs.updCode.takeIf { it > 0 }?.let { Update(it, prefs.updName, prefs.updNotes, prefs.updAt, prefs.updSha) })
    val update: StateFlow<Update?> = _update
    /** Is there a newer app than this one? */
    val updateReady: Boolean get() = (_update.value?.code ?: 0) > BuildConfig.VERSION_CODE
    /** Asks the website. Returns false when it couldn't (no signal). */
    suspend fun checkUpdate(): Boolean {
        if (BuildConfig.PLAY) return checkPlayUpdate()
        val r = api.appVersion()
        if (!r.ok || r.json.optInt("versionCode") <= 0) return false
        val u = Update(r.json.optInt("versionCode"), r.json.optString("versionName"), r.json.optString("notes"), System.currentTimeMillis(), r.json.optString("sha256"))
        prefs.updCode = u.code; prefs.updName = u.name; prefs.updNotes = u.notes; prefs.updAt = u.at; prefs.updSha = u.sha256
        _update.value = u; return true
    }
    /** Google Play build: Play says whether there is a newer version; the website's version.json only gives its
     *  name and notes, when it is about that same version. */
    private suspend fun checkPlayUpdate(): Boolean {
        val code = org.rafikioutreach.pin.data.Distro.availableCode(getApplication()) ?: return false
        val r = if (code > BuildConfig.VERSION_CODE) runCatching { api.appVersion() }.getOrNull() else null
        val same = r != null && r.ok && r.json.optInt("versionCode") == code
        val u = Update(code, if (same) r!!.json.optString("versionName") else "", if (same) r!!.json.optString("notes") else "", System.currentTimeMillis())
        prefs.updCode = u.code; prefs.updName = u.name; prefs.updNotes = u.notes; prefs.updAt = u.at; prefs.updSha = ""
        _update.value = u; return true
    }
    /** Once a day, quietly, when the app opens. */
    fun checkUpdateDaily() { if (System.currentTimeMillis() - prefs.updAt > 24 * 3600_000L) viewModelScope.launch { checkUpdate() } }
    /** Checks for a newer app if the last check was more than [minutes] ago (on open, on coming back, and every 15 minutes). */
    fun checkUpdateSoon(minutes: Int = 2) { if (System.currentTimeMillis() - prefs.updAt > minutes * 60_000L) viewModelScope.launch { checkUpdate() } }

    fun showBanner(t: String?) { _banner.value = t }

    /** Me → "Show the welcome screens again", and where to go when the welcome ends. */
    var replayOnb by androidx.compose.runtime.mutableStateOf(false)
    var goTab by androidx.compose.runtime.mutableStateOf<String?>(null)
    /** Open this project (a tap on a notification). */
    var goProject by androidx.compose.runtime.mutableStateOf<String?>(null)
    /** Open Projects on this list ("near" or "missing"): the widget's Visit nearby and Pin a location. */
    var goFilter by androidx.compose.runtime.mutableStateOf<String?>(null)
    fun quick(q: String) { if (q == "near" || q == "missing") { prefs.filter = q; goFilter = q; goTab = "list" } }
    /** Me: scroll to the update card (after "Check" finds one, or "Update now" on the pop-up). */
    var focusUpdate by androidx.compose.runtime.mutableStateOf(false)
    /** The update card should start downloading as soon as it shows ("Update now" on the pop-up). */
    var updateGo by androidx.compose.runtime.mutableStateOf(false)
    /** The update pop-up was closed with ✕: not again until the app is opened next time. */
    var updPromptClosed by androidx.compose.runtime.mutableStateOf(false)
    /** The version the pop-up was closed (✕) for in this session: a newer one pops up again. */
    var updPromptClosedCode = 0
    /** "You’re at …" banners closed with ×, until the app is closed. */
    val dismissed = androidx.compose.runtime.mutableStateListOf<String>()
    /** A short title for the top bar while a screen's big title is scrolled away. */
    val bar = org.rafikioutreach.pin.ui.BarTitle()

    /** Sign in with a PIN; shared team PINs need a name. Returns an error text, or null when in. */
    suspend fun signIn(pin: String, name: String): String? {
        prefs.pin = pin
        // a PIN typed here starts a new session; the server's session number comes back with the list
        prefs.pinGen = ""; api.fresh = true
        val r = try { api.list() } finally { api.fresh = false }
        if (!r.ok) {
            prefs.pin = ""
            return if (r.status == 0) t("No signal. Connect to the internet to sign in the first time.", "Hakuna mtandao. Unganisha intaneti ili uingie mara ya kwanza.") else reasonText(r.reason)
        }
        val who = r.json.optString("who")
        if (who.isBlank() && name.isBlank()) { prefs.pin = ""; return "NEED_NAME" }
        ended = null
        prefs.name = who.ifBlank { name.trim() }
        accept(r.json)
        _signedIn.value = true
        return null
    }

    // --- fingerprint / face lock -----------------------------------------------------------------
    /** True while the app waits for a fingerprint, face or the phone's screen lock before showing anything. */
    val bioLocked = MutableStateFlow(prefs.bio && (prefs.pin.isNotBlank() || prefs.session.isNotBlank()))
    private var awayAt = 0L
    fun wentAway() { awayAt = System.currentTimeMillis() }
    /** Back in the app: after a minute away, ask for the fingerprint again. */
    fun cameBack() { if (prefs.bio && _signedIn.value && awayAt > 0 && System.currentTimeMillis() - awayAt > 60_000) bioLocked.value = true; awayAt = 0 }
    fun unlocked() { bioLocked.value = false }
    /** "Lock the app": with the fingerprint lock on it only locks (the PIN stays); otherwise it signs out. */
    fun lockOrSignOut() { if (prefs.bio) bioLocked.value = true else lock() }

    fun lock() {
        bioLocked.value = false
        val s = prefs.session
        prefs.forget(); _signedIn.value = false
        org.rafikioutreach.pin.widget.PinWidget.update(getApplication())
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

    /** Sign in on the lock screen with a personal monday.com token. Returns an error text, or null when signed in. */
    suspend fun mondayToken(token: String): String? {
        val x = api.authToken(token.trim())
        val sid = x.json.optString("sid")
        if (!x.ok || sid.isBlank()) return reasonText(x.reason)
        prefs.pin = ""; prefs.session = sid
        val r = api.list()
        if (!r.ok) { prefs.session = ""; return reasonText(r.reason) }
        prefs.name = r.json.optString("who")
        accept(r.json); _signedIn.value = true
        return null
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
    // --- the team: names, home country, phone and when each was last seen ----------------------
    private val _team = MutableStateFlow<List<org.rafikioutreach.pin.ui.Mate>?>(null)
    val team: StateFlow<List<org.rafikioutreach.pin.ui.Mate>?> = _team
    suspend fun loadTeam(): Boolean {
        val a = api.team().json.optJSONArray("team") ?: return false
        _team.value = (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            org.rafikioutreach.pin.ui.Mate(o.optString("name"), o.optString("home"), o.optString("phone"), o.optString("lastSeen").takeIf { it.isNotBlank() && it != "null" })
        }.filter { it.name.isNotBlank() }
        return true
    }
    /** Me › Connect monday.com: link this person's own monday.com token to their PIN. Returns an error text, or null. */
    suspend fun linkMonday(token: String): String? {
        val r = api.post(JSONObject().put("action", "monday-link").put("token", token.trim()))
        if (!r.ok) return reasonText(r.reason)
        _snap.value?.let { _snap.value = it.copy(linked = r.json.optString("name").ifBlank { prefs.name }) }
        return null
    }
    suspend fun unlinkMonday(): Boolean {
        val r = api.post(JSONObject().put("action", "monday-unlink"))
        if (r.ok) _snap.value?.let { _snap.value = it.copy(linked = "") }
        return r.ok
    }
    // --- Me › Settings ------------------------------------------------------------------------
    /** Personal details: a display name, phone and home country. Returns an error text, or null. */
    suspend fun saveProfile(nick: String, phone: String, home: String): String? {
        val r = api.post(JSONObject().put("action", "profile").put("nick", nick.trim()).put("phone", phone.trim()).put("home", home))
        if (!r.ok) return reasonText(r.reason)
        r.json.optJSONObject("me")?.let { m -> edit { it.copy(me = Me.from(m)) } }
        return null
    }
    /** Change my PIN (a PIN given out in the app). Returns an error text, or null. */
    suspend fun changePin(current: String, new: String): String? {
        val r = api.post(JSONObject().put("action", "pin-change").put("current", current.trim()).put("pin", new.trim()))
        if (!r.ok) return reasonText(r.reason)
        prefs.pin = new.trim(); return null
    }
    /** Sign out of this phone: forgets the PIN and name (changes waiting to send stay). */
    fun signOut() { lock(); prefs.name = "" }

    // --- notifications: the bell in Me & team ---------------------------------------------------
    private val _noteTick = MutableStateFlow(0)
    val noteTick: StateFlow<Int> = _noteTick
    fun alerts(): List<Alert> = _snap.value?.let { alertsFor(it, _activity.value.orEmpty(), prefs.name, prefs.noteOff, prefs.reviewAt) }.orEmpty()
    /** Unread: newer than "Mark all as read" and not opened one by one. Opening the list doesn't read them. */
    fun isNew(a: Alert): Boolean = org.rafikioutreach.pin.data.alertNew(a, prefs.noteSeen, prefs.noteRead)
    fun unread(): Int { val seen = prefs.noteSeen; val read = prefs.noteRead; return alerts().count { org.rafikioutreach.pin.data.alertNew(it, seen, read) } }
    /** Mark all as read. */
    fun markRead() { prefs.noteSeen = java.time.Instant.now().toString(); prefs.noteRead = emptySet(); _noteTick.value++ }
    /** One opened: read (only keys still in the list are kept). */
    fun markRead(a: Alert) { val keys = alerts().map { it.key }.toSet(); prefs.noteRead = (prefs.noteRead + a.key).filter { it in keys }.toSet(); _noteTick.value++ }
    fun noteChanged() { _noteTick.value++ }

    /** Apply a fresh copy of the list (after an Office change). */
    fun reload() = refresh()

    /** The user's home country ("ke"): from the team list, else the one picked at the start. */
    private fun myHome() = _snap.value?.me?.home.orEmpty().takeIf { it.isNotBlank() && it != "null" } ?: prefs.country

    private fun accept(raw: JSONObject) {
        val n = raw.optInt("reviewOpen"); if (n > (_snap.value?.reviewOpen ?: 0)) prefs.reviewAt = java.time.Instant.now().toString()
        // the PIN session's number, sent back with every request (X-Pin-Gen)
        if (prefs.pin.isNotBlank() && raw.has("gen")) prefs.pinGen = raw.optInt("gen").toString()
        prefs.saveSnapshot(raw); _snap.value = Snapshot.from(raw)
        org.rafikioutreach.pin.data.Currency.home(myHome())
        org.rafikioutreach.pin.ui.GoogleTiles.configure(getApplication(), _snap.value?.gmapsAndroid)
        org.rafikioutreach.pin.cmap.CountryMaps.baseFrom(raw.optJSONObject("countryMaps")) // whole-country maps (feat-cmap)
        // the home-screen widget counts from this list
        org.rafikioutreach.pin.widget.PinWidget.update(getApplication())
    }

    /** When the list was last fetched (ms), so coming back to the app doesn't reload every time. */
    var lastRefresh = 0L
        private set

    /** Sends anything waiting, then fetches the latest list. [quiet] skips the spinning refresh button. */
    fun refresh(quiet: Boolean = false, done: ((Boolean) -> Unit)? = null) = viewModelScope.launch {
        lastRefresh = System.currentTimeMillis()
        if (!quiet) _busy.value = true
        sendWaiting()
        val r = api.list()
        if (!quiet) _busy.value = false
        when {
            r.ok -> { accept(r.json); r.json.optString("who").takeIf { it.isNotBlank() }?.let { prefs.name = it }; done?.invoke(true) }
            r.status == 401 -> { lock(); _banner.value = reasonText(r.reason); done?.invoke(false) }
            else -> { if (r.status == 0) _banner.value = t("No signal: showing the list saved on this phone.", "Hakuna mtandao: inaonyesha orodha iliyohifadhiwa kwenye simu hii."); done?.invoke(false) }
        }
    }

    suspend fun sendWaiting(): Outbox.Result = sendLock.withLock {
        val res = outbox.flush(api)
        _waiting.value = outbox.count()
        if (res.locked) { lock(); _banner.value = ended?.let { reasonText(it) } ?: t("Your PIN stopped working. Please sign in again; nothing was lost.", "PIN yako imeacha kufanya kazi. Tafadhali ingia tena; hakuna kilichopotea.") }
        res
    }

    /** Queue a change, try to send it now, and say what happened. */
    suspend fun submit(body: JSONObject, files: List<OutFile>): Outcome {
        body.put("by", prefs.name)
        if (!body.has("savedAt")) body.put("savedAt", Instant.now().toString())
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
    fun localTested(p: Project) = edit { s -> s.copy(tests = s.tests + (p.id to Instant.now().toString())) }
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

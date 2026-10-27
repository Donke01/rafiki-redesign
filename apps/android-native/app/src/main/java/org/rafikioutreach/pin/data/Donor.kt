package org.rafikioutreach.pin.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.rafikioutreach.pin.t
import java.io.File

// RAFIKI Pin in donor mode (feat-donorapp). A donor signs in with their private link or short donor code and the
// app then asks /api/donor with the same token as the private page (header X-Donor-Token). The server only ever
// sends that donor's own projects, with whitelisted fields; nothing here touches the staff endpoints.
// A phone is either staff or donor: signing in as a donor needs the staff sign-in gone first (and the other way round).

/** The donor's sign-in and the last answer, kept on the phone (its own file, apart from the staff settings). */
class DonorPrefs(context: Context) {
    private val sp = context.getSharedPreferences("rafiki-donor", Context.MODE_PRIVATE)
    private val file = File(context.filesDir, "donor.json")
    var token: String get() = sp.getString("token", "") ?: ""; set(v) = sp.edit().putString("token", v).apply()
    /** The newest update the person has seen on Updates, and the newest one the phone notified about. */
    var seen: String get() = sp.getString("seen", "") ?: ""; set(v) = sp.edit().putString("seen", v).apply()
    var notifiedAt: String get() = sp.getString("notifiedAt", "") ?: ""; set(v) = sp.edit().putString("notifiedAt", v).apply()
    /** Signed out because the link or code was switched off: the next start says so kindly. */
    var gone: Boolean get() = sp.getBoolean("gone", false); set(v) = sp.edit().putBoolean("gone", v).apply()
    fun save(raw: JSONObject) = runCatching { file.writeText(raw.toString()) }
    fun load(): JSONObject? = runCatching { JSONObject(file.readText()) }.getOrNull()
    fun clear() { sp.edit().remove("token").remove("seen").remove("notifiedAt").apply(); runCatching { file.delete() } }
}

/** Reading what a donor types or pastes: a private link (either address), a bare token, or a 10-letter code. */
object DonorEntry {
    const val ALPHA = "23456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val LINK = Regex("(?:/donor/|#donor=)([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])")
    private val TOKEN = Regex("^[A-Za-z0-9_-]{43}$")
    data class Read(val token: String? = null, val code: String? = null)
    fun read(s: String): Read? {
        val x = s.trim()
        LINK.find(x)?.let { return Read(token = it.groupValues[1]) }
        if (TOKEN.matches(x)) return Read(token = x)
        val c = x.uppercase().replace(Regex("[\\s\\u2010-\\u2015-]+"), "")
        return if (c.length == 10 && c.all { it in ALPHA }) Read(code = c) else null
    }
    /** https://pin.rafikioutreach.org/pin#donor=<token> (the App Link) or …/donor/<token> → the token. */
    fun tokenFromLink(path: String?, fragment: String?): String? {
        fragment?.let { f -> Regex("^donor=([A-Za-z0-9_-]{43})$").find(f)?.let { return it.groupValues[1] } }
        path?.let { p -> Regex("^/donor/([A-Za-z0-9_-]{43})/?$").find(p)?.let { return it.groupValues[1] } }
        return null
    }
}

/** /api/donor, as the private page uses it. */
class DonorApi(val server: String, private val token: () -> String) {
    var transport: (method: String, url: String, headers: Map<String, String>, body: ByteArray?) -> Pair<Int, String> = { m, u, h, b -> Api.http(m, u, h, b) }
    suspend fun view(bg: Boolean = false): Reply = call("GET", "/api/donor" + if (bg) "?bg=1" else "", null)
    suspend fun redeem(code: String): Reply = call("POST", "/api/donor", JSONObject().put("code", code))
    suspend fun mail(on: Boolean): Reply = call("POST", "/api/donor", JSONObject().put("action", "mail").put("on", on))
    fun photo(id: String, w: Int = 960) = "$server/api/donor?photo=${Api.enc(id)}&w=$w&t=${Api.enc(token())}"
    fun board(id: String, w: Int = 960) = "$server/api/photo/${Api.enc(id)}?w=$w"
    private suspend fun call(method: String, path: String, body: JSONObject?): Reply = withContext(Dispatchers.IO) {
        val h = mutableMapOf("Accept" to "application/json")
        token().takeIf { it.isNotBlank() }?.let { h["X-Donor-Token"] = it }
        if (body != null) { h["Content-Type"] = "application/json"; h["Origin"] = server }
        val (status, text) = transport(method, server + path, h, body?.toString()?.toByteArray())
        if (status == 0) return@withContext Reply(false, 0, JSONObject(), "offline")
        val j = try { JSONObject(text) } catch (e: Exception) { JSONObject().put("ok", false).put("reason", "down") }
        Reply(j.optBoolean("ok"), status, j, j.optString("reason").takeIf { it.isNotBlank() })
    }
}

// ---- what the donor sees ----------------------------------------------------------------------------------------
data class DVisit(val at: String, val state: String, val build: Boolean, val stage: String, val users: Int, val flow: Double, val water: String, val clarity: String,
                  val pupils: Int, val patients: Int, val children: Int)
data class DRepair(val at: String, val problems: List<String>, val cost: Int, val fixedAt: String, val open: Boolean)
data class DMedia(val id: String, val at: String, val label: String, val video: Boolean = false)
data class DStory(val at: String, val quote: String, val who: String)
data class DProject(
    val id: String, val title: String, val type: String, val cat: String, val water: Boolean, val country: String, val area: String, val status: String, val year: Int, val done: String,
    val gps: LatLng?, val depth: Int, val pump: String, val photos: List<String>, val health: String, val healthAt: String,
    val people: Int, val litresPerDay: Int, val years: Double, val pupils: Int, val patients: Int, val children: Int,
    val visits: List<DVisit>, val repairs: List<DRepair>, val stories: List<DStory>, val gallery: List<DMedia>, val thanks: List<DMedia>,
    /** Being built: the stages and their photos, read-only (feat-mea, construction). */
    val build: org.rafikioutreach.pin.ui.DonorBuild? = null,
) {
    val place get() = listOf(area, country).filter { it.isNotBlank() }.joinToString(", ")
    /** working · repairing · notworking (from the last check), else building · planned · done */
    val state get() = health.ifBlank { when (status) { "Under construction" -> "building"; "Upcoming" -> "planned"; else -> "done" } }
}
data class DUpdate(val type: String, val pid: String, val at: String, val n: Int, val photos: List<String>, val text: String, val who: String, val media: String, val id: String,
                   val years: Int, val visit: DVisit?, val problems: List<String>, val fixed: Boolean)
data class DonorView(val name: String, val viewer: String, val org: String, val group: Boolean, val projects: List<DProject>, val updates: List<DUpdate>,
                     val nProjects: Int, val nWater: Int, val people: Int, val visitsYear: Int, val email: Boolean, val digest: Boolean, val mailOn: Boolean) {
    fun project(id: String) = projects.firstOrNull { it.id == id }
    val newest: String get() = updates.maxOfOrNull { it.at } ?: ""

    companion object {
        private fun JSONObject.s(k: String) = optString(k).takeIf { it != "null" } ?: ""
        private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
        private fun JSONArray?.strs(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
        private fun visit(v: JSONObject): DVisit { val n = v.optJSONObject("numbers") ?: JSONObject()
            return DVisit(v.s("at"), v.s("state"), v.optBoolean("build"), v.s("stage"), v.optInt("users"), v.optDouble("flow", 0.0).let { if (it.isNaN()) 0.0 else it }, v.s("water"), v.s("clarity"), n.optInt("pupils"), n.optInt("patients"), n.optInt("children")) }

        fun from(j: JSONObject): DonorView {
            val d = j.optJSONObject("donor") ?: JSONObject(); val t = j.optJSONObject("totals") ?: JSONObject(); val me = j.optJSONObject("me") ?: JSONObject()
            val ps = j.optJSONArray("projects").objs().map { p ->
                val h = p.optJSONObject("health"); val im = p.optJSONObject("impact") ?: JSONObject(); val g = p.optJSONArray("gps")
                DProject(p.s("id"), p.s("title"), p.s("type"), p.s("cat"), p.optBoolean("water"), p.s("country"), p.s("area"), p.s("status"), p.optInt("year"), p.s("done"),
                    if (g != null && g.length() == 2) LatLng(g.optDouble(0), g.optDouble(1)) else null, p.optInt("depth"), p.s("pump"), p.optJSONArray("photos").strs(),
                    h?.s("state") ?: "", h?.s("at") ?: "", im.optInt("people"), im.optInt("litresPerDay"), im.optDouble("years", 0.0), im.optInt("pupils"), im.optInt("patients"), im.optInt("children"),
                    p.optJSONArray("visits").objs().map(::visit),
                    p.optJSONArray("repairs").objs().map { r -> DRepair(r.s("at"), r.optJSONArray("problems").strs(), r.optInt("cost"), r.s("fixedAt"), r.optBoolean("open")) },
                    p.optJSONArray("stories").objs().map { s -> DStory(s.s("at"), s.s("quote"), s.s("who")) },
                    p.optJSONArray("gallery").objs().map { m -> DMedia(m.s("id"), m.s("at"), m.s("label")) },
                    p.optJSONArray("thanks").objs().map { m -> DMedia(m.s("id"), m.s("at"), m.s("caption"), m.s("media") == "video") },
                    org.rafikioutreach.pin.ui.DonorBuild.from(p.optJSONObject("construction")))
            }
            val us = j.optJSONArray("updates").objs().map { u ->
                DUpdate(u.s("type"), u.s("pid"), u.s("at"), u.optInt("n"), u.optJSONArray("photos").strs(), u.s("quote").ifBlank { u.s("caption") }, u.s("who"), u.s("media"), u.s("id"),
                    u.optInt("years"), if (u.s("type") == "visit") visit(u) else null, u.optJSONArray("problems").strs(), u.s("fixedAt").isNotBlank())
            }
            return DonorView(d.s("name"), d.s("viewer").ifBlank { d.s("name") }, d.s("org"), d.optBoolean("group"), ps, us,
                t.optInt("projects"), t.optInt("water"), t.optInt("people"), t.optInt("visitsYear"), me.optBoolean("email"), me.optBoolean("digest", true), me.optBoolean("on"))
        }
    }
}

/** New photos and news since [since], as phone notifications: (title, text, project id). Visits and repairs
 *  only say what happened in donor words; photos and thank-yous say how many. */
fun donorNews(v: DonorView, since: String): List<Triple<String, String, String>> =
    v.updates.filter { it.at > since && it.type in setOf("photos", "thanks", "visit", "story", "anniversary", "repair") }.sortedBy { it.at }.mapNotNull { u ->
        val p = v.project(u.pid) ?: return@mapNotNull null
        val text = when (u.type) {
            "photos" -> if (u.n == 1) t("1 new photo", "Picha 1 mpya") else t("${u.n} new photos", "Picha ${u.n} mpya")
            "thanks" -> if (u.media == "video") t("A thank-you video from the community", "Video ya shukrani kutoka kwa jamii") else t("A thank-you photo from the community", "Picha ya shukrani kutoka kwa jamii")
            "visit" -> t("The RAFIKI team visited", "Timu ya RAFIKI ilitembelea") + (u.visit?.let { donorVisitSay(it) }?.takeIf { it.isNotBlank() }?.let { ": " + it.replaceFirstChar { c -> c.lowercase() } } ?: ".")
            "story" -> t("A story from the community", "Hadithi kutoka kwa jamii") + ": “" + u.text + "”"
            "anniversary" -> if (u.years == 1) t("One year of clean water!", "Mwaka mmoja wa maji safi!") else t("${u.years} years of clean water!", "Miaka ${u.years} ya maji safi!")
            else -> if (u.fixed) t("Repaired and working again.", "Imerekebishwa na inafanya kazi tena.") else t("Our team found a problem and is on it.", "Timu yetu iligundua tatizo na inalishughulikia.")
        }
        Triple(p.title, text, p.id)
    }

private fun num(n: Number) = String.format(java.util.Locale.US, "%,d", n.toLong())

/** A visit in a sentence, with no names, notes or costs (the server never sends them). */
fun donorVisitSay(v: DVisit): String {
    val a = mutableListOf<String>()
    if (v.build) a += if (v.stage.isNotBlank()) t("Building: ", "Ujenzi: ") + donorStage(v.stage).lowercase() else t("Building checked", "Ujenzi umekaguliwa")
    else when (v.state) { "working" -> a += t("Working well", "Inafanya kazi vizuri"); "repair" -> a += t("Needs a repair", "Inahitaji matengenezo"); "notworking" -> a += t("Not working", "Haifanyi kazi") }
    if (v.users > 0) a += t("about ${num(v.users)} people using it", "takriban watu ${num(v.users)} wanaitumia")
    if (v.flow > 0) a += t("${num(Math.round(v.flow))} litres a minute", "lita ${num(Math.round(v.flow))} kwa dakika")
    if (v.water == "safe") a += t("water tested safe", "maji yamepimwa ni salama")
    if (v.clarity == "clear") a += t("clear water", "maji safi")
    if (v.pupils > 0) a += t("${num(v.pupils)} pupils", "wanafunzi ${num(v.pupils)}")
    if (v.patients > 0) a += t("${num(v.patients)} patients a week", "wagonjwa ${num(v.patients)} kwa wiki")
    if (v.children > 0) a += t("${num(v.children)} children", "watoto ${num(v.children)}")
    val s = a.joinToString(", ")
    return if (s.isBlank()) "" else s.replaceFirstChar { it.uppercase() } + "."
}
fun donorStage(s: String) = if (!org.rafikioutreach.pin.Lang.sw) s else when (s) { "Foundation" -> "Msingi"; "Walls going up" -> "Kuta zinajengwa"; "Roofing" -> "Kuezeka paa"; "Finishing" -> "Kumalizia"; "Finished" -> "Imekamilika"; else -> s }

/** Donor mode's state: who is signed in, their projects, and signing in and out. Lives in AppModel as `donor`. */
class DonorState(context: Context, server: String) {
    val prefs = DonorPrefs(context)
    val api = DonorApi(server) { prefs.token }
    private val _view = kotlinx.coroutines.flow.MutableStateFlow(if (prefs.token.isNotBlank()) prefs.load()?.let { runCatching { DonorView.from(it) }.getOrNull() } else null)
    val view: kotlinx.coroutines.flow.StateFlow<DonorView?> = _view
    /** Signed in as a donor (the app shows donor mode only, never the staff screens). */
    private val _on = kotlinx.coroutines.flow.MutableStateFlow(prefs.token.isNotBlank())
    val on: kotlinx.coroutines.flow.StateFlow<Boolean> = _on
    /** Switched off by RAFIKI: shown once, kindly, on the sign-in side. */
    private val _gone = kotlinx.coroutines.flow.MutableStateFlow(prefs.gone)
    val gone: kotlinx.coroutines.flow.StateFlow<Boolean> = _gone
    /** A private link opened from outside the app (App Link): the sign-in screen tries it straight away. */
    var prefill by androidx.compose.runtime.mutableStateOf("")
    /** The sign-in screen asked for from the staff sign-in ("I'm a donor"). */
    var entry by androidx.compose.runtime.mutableStateOf(false)
    var seen by androidx.compose.runtime.mutableStateOf(prefs.seen)
    /** Open this project (a tap on a donor notification). */
    var goProject by androidx.compose.runtime.mutableStateOf<String?>(null)

    /** Signs in with a link, token or code. Returns an error text, or null when in. */
    suspend fun signIn(typed: String): String? {
        val e = DonorEntry.read(typed) ?: return t("That doesn’t look like a donor code or link. A code has 10 letters and numbers, like K7QM4-XR9TB.", "Hiyo haionekani kama msimbo au kiungo cha mfadhili. Msimbo una herufi na namba 10, mf. K7QM4-XR9TB.")
        val token = e.token ?: api.redeem(e.code!!).let { r -> if (!r.ok) return why(r, true); r.json.optString("token") }
        val old = prefs.token
        prefs.token = token
        val r = api.view()
        if (!r.ok) { prefs.token = old; return why(r, e.code != null) }
        if (old != token) { prefs.seen = ""; prefs.notifiedAt = "" }
        accept(r.json)
        prefs.gone = false; _gone.value = false; _on.value = true; entry = false
        return null
    }
    /** Fresh data (bg: the quiet check that doesn't count as opening the page). A switched-off link signs out. */
    suspend fun refresh(bg: Boolean = false): Reply {
        if (prefs.token.isBlank()) return Reply(false, 401, JSONObject(), "link")
        val r = api.view(bg)
        if (r.ok) accept(r.json) else if (r.status == 404 && r.reason == "link") switchedOff()
        return r
    }
    private fun accept(j: JSONObject) {
        prefs.save(j); val v = DonorView.from(j); _view.value = v
        if (prefs.seen.isBlank()) { prefs.seen = v.newest; seen = prefs.seen }
        if (prefs.notifiedAt.isBlank()) prefs.notifiedAt = v.newest
    }
    fun switchedOff() { prefs.clear(); prefs.gone = true; _gone.value = true; _view.value = null; _on.value = false; seen = "" }
    fun signOut() { prefs.clear(); prefs.gone = false; _gone.value = false; _view.value = null; _on.value = false; seen = "" }
    fun dismissGone() { prefs.gone = false; _gone.value = false }
    fun markSeen() { _view.value?.newest?.let { prefs.seen = it; seen = it } }
    /** E-mail updates on/off (off is the same as the e-mail's unsubscribe link). Returns an error text or null. */
    suspend fun setMail(on: Boolean): String? {
        val r = api.mail(on)
        if (!r.ok) { if (r.status == 404 && r.reason == "link") switchedOff(); return why(r, false) }
        val me = r.json.optJSONObject("me") ?: return null
        prefs.load()?.let { j -> j.put("me", me); accept(j) }
        return null
    }
    fun why(r: Reply, code: Boolean): String = when (r.reason) {
        "rate" -> t("Too many tries from this connection. Please try again in an hour.", "Majaribio mengi kutoka mtandao huu. Tafadhali jaribu tena baada ya saa moja.")
        "offline" -> t("No connection. Check your signal and try again.", "Hakuna muunganisho. Angalia mtandao kisha ujaribu tena.")
        "code", "link" -> if (code) t("That code isn’t working. Check it, or write to us for a new one.", "Msimbo huo haufanyi kazi. Uhakikishe, au tuandikie upate mpya.") else t("That link isn’t working. It may have been replaced with a new one.", "Kiungo hicho hakifanyi kazi. Huenda kimebadilishwa na kipya.")
        else -> t("Something went wrong. Please try again in a moment.", "Kuna hitilafu. Tafadhali jaribu tena baada ya muda mfupi.")
    }
}

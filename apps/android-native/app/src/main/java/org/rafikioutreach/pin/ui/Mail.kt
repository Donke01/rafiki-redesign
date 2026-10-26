package org.rafikioutreach.pin.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.R
import org.rafikioutreach.pin.data.Snapshot
import org.rafikioutreach.pin.data.reasonText
import org.rafikioutreach.pin.t
import java.io.ByteArrayOutputStream

// Office › Send an e-mail: one message to donors, the admins or team members, one e-mail per person
// (the same server actions as the web app's Office › Send an e-mail, see functions/api/mail.js).

/** Opens Office › Send an e-mail with an audience chosen (from Office › Donors or a project's donor area). */
data class MailPreset(val aud: String, val sub: String = "all", val project: String = "", val picks: List<String> = emptyList())
object MailNav { var preset by mutableStateOf<MailPreset?>(null) }

data class MlViewer(val key: String, val name: String, val why: String)
data class MlDonor(val id: String, val name: String, val projects: List<String>, val viewers: List<MlViewer>)
data class MlPerson(val key: String, val name: String, val role: String, val email: Boolean)
data class MlRcpt(val name: String, val ok: Boolean, val error: String)
data class MlLog(val id: String, val at: String, val by: String, val audience: String, val kind: String, val subject: String, val sent: Int, val failed: Int, val skipped: Int,
                 val skippedBy: Map<String, Int>, val link: Boolean, val rcpt: List<MlRcpt>)
data class MlOverview(val ready: Boolean, val month: Int, val donorsReachable: Int, val teamReachable: Int, val donors: List<MlDonor>, val people: List<MlPerson>, val log: List<MlLog>, val me: Boolean)

private fun JSONObject.s(k: String) = optString(k).takeIf { it != "null" } ?: ""
private fun JSONArray?.objs() = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
fun parseMailOverview(j: JSONObject): MlOverview {
    val d = j.optJSONObject("donors") ?: JSONObject(); val tm = j.optJSONObject("team") ?: JSONObject()
    return MlOverview(
        j.optBoolean("ready"), j.optJSONObject("use")?.optInt("month") ?: 0, d.optInt("reachable"), tm.optInt("reachable"),
        d.optJSONArray("list").objs().map { x -> MlDonor(x.s("id"), x.s("name"), x.optJSONArray("projects")?.let { a -> (0 until a.length()).map { a.get(it).toString() } } ?: emptyList(),
            x.optJSONArray("viewers").objs().map { v -> MlViewer(v.s("key"), v.s("name"), v.s("why")) }) },
        tm.optJSONArray("people").objs().map { p -> MlPerson(p.s("key"), p.s("name"), p.s("role"), p.optBoolean("email")) },
        j.optJSONArray("log").objs().map { e ->
            val sb = e.optJSONObject("skippedBy") ?: JSONObject()
            MlLog(e.s("id"), e.s("at"), e.s("by"), e.s("audience"), e.s("kind"), e.s("subject"), e.optInt("sent"), e.optInt("failed"), e.optInt("skipped"),
                sb.keys().asSequence().associateWith { sb.optInt(it) }, e.optBoolean("link"), e.optJSONArray("rcpt").objs().map { r -> MlRcpt(r.s("name"), r.s("s") == "sent", r.s("e")) })
        },
        j.optJSONObject("me")?.optBoolean("email") == true,
    )
}

/** donors: all · project · pick; admins; team: all · pick → the server's audience name. */
fun mailAudience(aud: String, sub: String, project: String): String = when (aud) {
    "pick" -> "pick" // Choose people: admins, team and donors picked one by one (feat-teama)
    "admins" -> "admins"
    "team" -> if (sub == "pick") "people:pick" else "team"
    else -> if (sub == "project" && project.isNotBlank()) "donors:project:$project" else if (sub == "pick") "donors:pick" else "donors:all"
}
/** How many it goes to, and who is skipped and why (worked out here for the live count; the server's count is the one confirmed). */
fun mailCount(ov: MlOverview, audience: String, picks: Collection<String>): Pair<Int, Map<String, Int>> {
    val skip = mutableMapOf("unsub" to 0, "revoked" to 0, "no-email" to 0)
    var n = 0
    if (audience == "pick") {
        ov.people.filter { it.key in picks }.forEach { if (it.email) n++ else skip["no-email"] = skip["no-email"]!! + 1 }
        ov.donors.flatMap { it.viewers }.filter { it.key in picks }.forEach { v -> if (v.why.isBlank()) n++ else skip[v.why] = (skip[v.why] ?: 0) + 1 }
    } else if (audience.startsWith("donors")) {
        ov.donors.forEach { d -> d.viewers.forEach { v ->
            val on = audience == "donors:all" || (audience == "donors:pick" && (v.key in picks || d.id in picks)) || (audience.startsWith("donors:project:") && audience.removePrefix("donors:project:") in d.projects)
            if (on) { if (v.why.isBlank()) n++ else skip[v.why] = (skip[v.why] ?: 0) + 1 }
        } }
    } else ov.people.filter { audience == "team" || (audience == "admins" && it.role == "admin") || (audience == "people:pick" && it.key in picks) }.forEach { if (it.email) n++ else skip["no-email"] = skip["no-email"]!! + 1 }
    return n to skip
}
/** "e-mail" never splits at its hyphen. */
// a plain hyphen glued to the next letter with a word joiner (Montserrat has no U+2011 non-breaking hyphen, so that one drew wrong)
fun mailNb(s: String) = s.replace("e-mail", "e-⁠mail").replace("E-mail", "E-⁠mail")
/** A phrase whose number keeps its word and whose last two words stay together. */
private fun keep(s: String) = s.replaceFirst(Regex("^(\\S+) "), "$1 ").replace(Regex(" (\\S+)$"), " $1")
fun mailSkipText(skip: Map<String, Int>): List<String> = buildList {
    skip["unsub"]?.takeIf { it > 0 }?.let { add(t("$it unsubscribed " + (if (it == 1) "is" else "are") + " skipped", "$it waliojiondoa wanarukwa")) }
    skip["revoked"]?.takeIf { it > 0 }?.let { add(t("$it with a revoked link", "$it wenye kiungo kilichozimwa")) }
    (skip["no-email"] ?: skip["noEmail"])?.takeIf { it > 0 }?.let { add(t("$it without an e-mail", "$it bila barua pepe")) }
}.map { mailNb(keep(it)) }
fun mailCountWord(kind: String, n: Int): String = when (kind) {
    "donor" -> if (n == 1) t("1 donor", "mfadhili 1") else t("$n donors", "wafadhili $n")
    "admin" -> if (n == 1) t("1 admin", "msimamizi 1") else t("$n admins", "wasimamizi $n")
    else -> if (n == 1) t("1 person", "mtu 1") else t("$n people", "watu $n")
}
fun mailKind(audience: String) = if (audience.startsWith("donors")) "donor" else if (audience == "admins") "admin" else "team"
/** Choose people: the server's shape, { aud: 'pick', people: [team ids], donors: [viewer keys] }. */
fun mailPickBody(ov: MlOverview, picks: Collection<String>): Pair<JSONArray, JSONArray> {
    val people = ov.people.map { it.key }.toSet()
    return JSONArray(picks.filter { it in people }) to JSONArray(picks.filterNot { it in people })
}
/** {first} and {name}, as the server replaces them. */
fun mailPersonalise(s: String, name: String): String {
    val n = name.trim(); val first = n.split(Regex("\\s+")).firstOrNull().orEmpty()
    return s.replace(Regex("\\{\\s*first\\s*\\}", RegexOption.IGNORE_CASE), first.ifBlank { "friend" }).replace(Regex("\\{\\s*name\\s*\\}", RegexOption.IGNORE_CASE), n.ifBlank { "friend" })
}
/** The message as paragraphs and bullet lists: ("p", lines) or ("ul", items). */
fun mailBlocks(text: String): List<Pair<String, List<String>>> = text.replace("\r\n", "\n").split(Regex("\\n\\s*\\n")).map { it.trim('\n').trimEnd() }.filter { it.isNotBlank() }.flatMap { b ->
    val out = mutableListOf<Pair<String, List<String>>>(); var cur: Pair<String, MutableList<String>>? = null
    b.split("\n").forEach { l ->
        val m = Regex("^\\s*[-•]\\s+(.*)$").find(l); val k = if (m != null) "ul" else "p"
        if (cur?.first != k) { cur?.let { out += it.first to it.second.toList() }; cur = k to mutableListOf() }
        cur!!.second += m?.groupValues?.get(1) ?: l.trim()
    }
    cur?.let { out += it.first to it.second.toList() }
    out
}
fun mailWhy(reason: String?, j: JSONObject = JSONObject()): String = mailNb(when (reason) {
    "no-mail" -> t("E-mail isn’t switched on yet. Whoever looks after the website can turn it on.", "Barua pepe bado haijawashwa. Anayesimamia tovuti anaweza kuiwasha.")
    "cap" -> j.optInt("cap").let { t("One send can go to at most $it people. Choose fewer, or send it in parts.", "Utumaji mmoja unaweza kwenda kwa watu $it tu. Chagua wachache, au utume kwa sehemu.") }
    "day-cap" -> j.optInt("left").let { t("Today’s e-mail allowance is nearly used up: $it left today. Choose fewer people or send it tomorrow.", "Kiwango cha barua pepe cha leo kinakaribia kwisha: $it zimebaki leo. Chagua watu wachache au utume kesho.") }
    "month-cap" -> j.optInt("left").let { t("This month’s e-mail allowance is nearly used up: $it left this month.", "Kiwango cha barua pepe cha mwezi huu kinakaribia kwisha: $it zimebaki mwezi huu.") }
    "rate" -> t("That’s a lot of e-mails in the last hour. Please wait a while and try again.", "Barua pepe nyingi ndani ya saa iliyopita. Tafadhali subiri kidogo kisha ujaribu tena.")
    "no-own-email" -> t("Add your own e-mail in Office › Team first, so the test can reach you.", "Ongeza barua pepe yako kwenye Ofisi › Timu kwanza, ili jaribio likufikie.")
    "empty" -> t("Add a subject and a message.", "Ongeza kichwa na ujumbe.")
    "nobody" -> t("Nobody to send to. Choose who it goes to.", "Hakuna wa kutumiwa. Chagua anaotumiwa.")
    "changed" -> t("The list changed. Check the count and send again.", "Orodha imebadilika. Angalia idadi kisha utume tena.")
    "not-admin" -> t("Only admins can send e-mails.", "Wasimamizi tu ndio wanaweza kutuma barua pepe.")
    "too-large" -> t("That photo is too large.", "Picha hiyo ni kubwa mno.")
    "type" -> t("That kind of file can’t be added.", "Aina hiyo ya faili haiwezi kuongezwa.")
    else -> j.optString("detail").takeIf { it.isNotBlank() }?.let { t("Not sent. Resend said: ", "Haijatumwa. Resend imesema: ") + it } ?: reasonText(reason)
})
private fun techFor(reason: String?): List<Pair<String, Boolean>>? = when (reason) {
    "no-mail" -> listOf("RESEND_API_KEY" to false, "ALERT_FROM" to false)
    "cap", "day-cap", "month-cap" -> listOf("MAIL_CAP" to true, "MAIL_DAY_CAP" to true, "MAIL_MONTH_CAP" to true)
    else -> null
}
/** A photo from the phone, made smaller (≤ 1600 px, JPEG) for the e-mail. */
fun mailShrink(bytes: ByteArray, max: Int = 1600): ByteArray {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    var k = 1; while (maxOf(o.outWidth, o.outHeight) / (k * 2) >= max) k *= 2
    val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = k }) ?: return bytes
    val s = minOf(1f, max.toFloat() / maxOf(b.width, b.height))
    val c = if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true) else b
    return ByteArrayOutputStream().also { c.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
}

/** The Office tab: a head with three numbers, the composer, and what was sent. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MailSection(m: AppModel, snap: Snapshot) {
    val scope = rememberScope()
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    var ov by remember { mutableStateOf<MlOverview?>(null) }
    var loadMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tick) { val r = m.api.mailGet(); if (r.ok) { ov = parseMailOverview(r.json); loadMsg = null } else loadMsg = mailWhy(r.reason, r.json) }
    val pre = MailNav.preset
    var aud by remember { mutableStateOf(pre?.aud ?: "donors") }
    var sub by remember { mutableStateOf(pre?.sub ?: "all") }
    var project by remember { mutableStateOf(pre?.project ?: "") }
    val picks = remember { mutableStateListOf<String>().apply { pre?.picks?.let { addAll(it) } } }
    LaunchedEffect(pre) { if (pre != null) { aud = pre.aud; sub = pre.sub; project = pre.project; picks.clear(); picks.addAll(pre.picks); MailNav.preset = null } }
    var q by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var btnOn by remember { mutableStateOf(false) }
    var btnLabel by remember { mutableStateOf("") }
    var btnHref by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<Triple<String, String, String>?>(null) } // kind, id, label
    var link by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<Triple<String, String, String?>?>(null) } // tone, text, reason (for the technical fold)
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<JSONObject?>(null) }
    var confirm by remember { mutableStateOf<JSONObject?>(null) }
    var photoSheet by remember { mutableStateOf(false) }
    var logOpen by remember { mutableStateOf<String?>(null) }
    val audience = mailAudience(aud, sub, project)

    fun draft(action: String) = JSONObject().put("action", action).put("audience", audience).put("picks", JSONArray(picks.toList())).put("subject", subject).put("body", body)
        .apply { if (audience == "pick") ov?.let { o -> val (pp, dd) = mailPickBody(o, picks); put("aud", "pick").put("people", pp).put("donors", dd) } }
        .put("button", if (btnOn) JSONObject().put("label", btnLabel).put("href", btnHref) else JSONObject.NULL)
        .put("photo", photo?.let { JSONObject().put("kind", it.first).put("id", it.second) } ?: JSONObject.NULL).put("includeLink", aud == "donors" && link)
    fun check(full: Boolean): String? = when {
        (audience == "donors:pick" || audience == "people:pick" || audience == "pick") && picks.isEmpty() -> t("Tick who it goes to.", "Chagua anaotumiwa.")
        aud == "donors" && sub == "project" && project.isBlank() -> t("Choose a project.", "Chagua mradi.")
        full && (subject.isBlank() || body.isBlank()) -> t("Add a subject and a message.", "Ongeza kichwa na ujumbe.")
        btnOn && (btnLabel.isBlank() || !Regex("^https?://\\S+\\.\\S+").containsMatchIn(btnHref.trim())) -> t("Give the button a text and a link starting with https://", "Kipe kitufe maandishi na kiungo kinachoanza na https://")
        else -> null
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u == null) return@rememberLauncherForActivityResult
        msg = Triple("", t("Adding the photo…", "Inaongeza picha…"), null)
        scope.launch {
            val data = withContext(Dispatchers.IO) { runCatching { mailShrink(ctx.contentResolver.openInputStream(u)!!.use { it.readBytes() }) }.getOrNull() }
            if (data == null) { msg = Triple("bad", t("Couldn’t read that file.", "Haikuweza kusoma faili hiyo."), null); return@launch }
            val r = m.api.mail(JSONObject().put("action", "photo").put("type", "image/jpeg").put("data", Base64.encodeToString(data, Base64.NO_WRAP)))
            if (r.ok) { photo = Triple("upload", r.json.optString("id"), t("Your photo", "Picha yako")); msg = null } else msg = Triple("bad", mailWhy(r.reason, r.json), r.reason)
        }
    }

    val o = ov
    DqmHead("ml_head", "mail", "blue", mailNb(t("Send an e-mail", "Tuma barua pepe")), mailNb(t("Write to donors, the admins or the team. Everyone gets their own e-mail.", "Waandikie wafadhili, wasimamizi au timu. Kila mtu anapata barua pepe yake.")),
        if (o == null) emptyList() else listOf(DqmNum(o.month, t("sent this month", "zilizotumwa mwezi huu"), if (o.month > 0) "has" else "off"), DqmNum(o.donorsReachable, t("donors reachable", "wafadhili wanaofikiwa"), if (o.donorsReachable > 0) "ok" else "off"),
            DqmNum(o.teamReachable, t("team reachable", "timu inayofikiwa"), if (o.teamReachable > 0) "ok" else "off")))
    loadMsg?.let { Msg(it, "bad") }
    if (o == null) { if (loadMsg == null) Text(t("Loading…", "Inapakia…"), color = C.muted, modifier = Modifier.padding(top = 12.dp)); return }
    if (!o.ready) Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.warnBg).padding(14.dp).testTag("ml_off")) {
        Text(mailNb(t("E-mail isn’t switched on yet. You can write and preview; whoever looks after the website can turn sending on.", "Barua pepe bado haijawashwa. Unaweza kuandika na kuhakiki; anayesimamia tovuti anaweza kuwasha utumaji.")),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = C.warn)
        TechDetails(listOf("RESEND_API_KEY" to false, "ALERT_FROM" to false), "ml_tech")
    }

    Card(Modifier.padding(top = 12.dp).testTag("ml_form"), pad = 16.dp) {
        Text(t("To", "Kwa"), style = MaterialTheme.typography.titleSmall, color = C.ink)
        // who it goes to: a segmented choice, then which ones
        // four choices in two rows of two, so no label has to shrink (Choose people: feat-teama)
        Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(C.bg).border(1.dp, C.line, RoundedCornerShape(26.dp)).padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("donors" to t("Donors", "Wafadhili"), "admins" to t("Admins", "Wasimamizi"), "team" to t("Team", "Timu"), "pick" to t("Choose people", "Chagua watu")).chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { (k, l) ->
                        val on = aud == k
                        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(999.dp)).background(if (on) C.blue else Color.Transparent).clickable(role = Role.Tab) { aud = k; sub = "all"; picks.clear(); q = ""; if (k != "donors") link = false }
                            .heightIn(min = 44.dp).padding(horizontal = 4.dp).testTag("ml_aud_$k"), contentAlignment = Alignment.Center) {
                            FitWords(l, MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, textAlign = TextAlign.Center), if (on) Color.White else C.ink, lines = 1)
                        }
                    }
                }
            }
        }
        val subs = when (aud) { "donors" -> listOf("all" to t("All donors", "Wafadhili wote"), "project" to t("One project", "Mradi mmoja"), "pick" to t("Choose people", "Chagua watu")); "team" -> listOf("all" to t("Everyone", "Wote"), "pick" to t("Choose people", "Chagua watu")); else -> emptyList() }
        if (subs.isNotEmpty()) FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            subs.forEach { (k, l) -> Chip(l, null, sub == k, { sub = k; picks.clear(); q = "" }, tag = "ml_sub_$k", hue = "blue") }
        }
        if (aud == "donors" && sub == "project") {
            val ps = o.donors.flatMap { it.projects }.distinct().map { id -> id to (snap.projects.firstOrNull { it.id == id }?.title ?: t("Project $id", "Mradi $id")) }.sortedBy { it.second }
            Picker(t("Project", "Mradi"), project, listOf("" to t("Choose a project…", "Chagua mradi…")) + ps.map { (id, title) -> id to "$title (" + mailCount(o, "donors:project:$id", emptyList()).first + ")" }, { project = it }, Modifier.testTag("ml_project"))
        }
        if (sub == "pick" && aud != "admins") {
            Field(q, { q = it }, t("Find a name", "Tafuta jina"), Modifier.testTag("ml_q"))
            val why = mapOf("unsub" to t("unsubscribed", "amejiondoa"), "revoked" to t("link revoked", "kiungo kimezimwa"), "no-email" to mailNb(t("no e-mail", "hana barua pepe")))
            val rows = (if (aud == "donors") o.donors.flatMap { d -> d.viewers.map { v -> listOf(v.key, v.name, if (d.name != v.name) d.name else "", v.why) } }
                else o.people.map { p -> listOf(p.key, p.name, if (p.role == "admin") t("Admin", "Msimamizi") else t("Field team", "Timu ya uwanjani"), if (p.email) "" else "no-email") })
                .filter { q.isBlank() || (it[1] + " " + it[2]).contains(q.trim(), true) }.sortedWith(compareBy({ it[3].isNotBlank() }, { it[1] }))
            Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, C.line, RoundedCornerShape(14.dp))) {
                rows.take(60).forEachIndexed { i, r ->
                    val off = r[3].isNotBlank(); val on = r[0] in picks && !off
                    if (i > 0) HorizontalDivider(color = C.line)
                    Row(Modifier.fillMaxWidth().clickable(enabled = !off, role = Role.Checkbox) { if (on) picks.remove(r[0]) else picks.add(r[0]) }.heightIn(min = 52.dp).padding(horizontal = 6.dp, vertical = 4.dp).testTag("ml_p_" + r[0]),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(on, { if (it) picks.add(r[0]) else picks.remove(r[0]) }, enabled = !off, colors = CheckboxDefaults.colors(checkedColor = C.blue))
                        Column(Modifier.weight(1f)) {
                            Text(r[1], style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = if (off) C.muted else C.ink)
                            listOf(r[2], why[r[3]] ?: "").filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = C.muted) }
                        }
                    }
                }
                if (rows.isEmpty()) Text(t("Nobody matches.", "Hakuna anayelingana."), color = C.muted, modifier = Modifier.padding(12.dp))
            }
        }
        if (aud == "pick") MailPickList(o, picks, q) { q = it }
        // the live count
        val (n, skip) = mailCount(o, audience, picks)
        val countText = when {
            (audience == "donors:pick" || audience == "people:pick" || audience == "pick") && picks.isEmpty() -> t("Tick who it goes to below.", "Chagua anaotumiwa hapa chini.")
            aud == "donors" && sub == "project" && project.isBlank() -> t("Choose a project.", "Chagua mradi.")
            else -> (listOf(if (n > 0) t("Goes to ", "Inaenda kwa ") + mailCountWord(mailKind(audience), n) else t("Nobody to send to yet", "Bado hakuna wa kutumiwa")) + mailSkipText(skip)).joinToString(" · ")
        }
        Row(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.tint).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
            Icon(Ic.get("mail"), null, tint = C.blue2, modifier = Modifier.padding(top = 1.dp).size(20.dp)); Spacer(Modifier.width(10.dp))
            Text(countText, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = C.ink, modifier = Modifier.testTag("ml_count"))
        }
        Field(subject, { subject = it.take(150) }, t("Subject", "Kichwa"), Modifier.testTag("ml_subject"), placeholder = if (aud == "donors") t("e.g. Water is flowing in Kapsaret", "mf. Maji yanatiririka Kapsaret") else t("e.g. Team meeting on Friday", "mf. Mkutano wa timu Ijumaa"))
        Field(body, { body = it.take(6000) }, t("Message", "Ujumbe"), Modifier.testTag("ml_body"), placeholder = if (aud == "donors") t("Dear {first},", "Mpendwa {first},") else t("Hello {first},", "Habari {first},"), lines = 6)
        Text(t("{first} becomes each person’s first name and {name} their full name. Start a line with “- ” for a bullet.", "{first} inakuwa jina la kwanza la kila mtu na {name} jina lake kamili. Anza mstari kwa “- ” kupata kitone."),
            style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).clickable { btnOn = !btnOn }.padding(end = 10.dp).testTag("ml_btn_on"), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(btnOn, { btnOn = it }, colors = CheckboxDefaults.colors(checkedColor = C.blue)); Text(t("Add a button", "Ongeza kitufe"), style = MaterialTheme.typography.bodyMedium, color = C.text)
        }
        if (btnOn) {
            Field(btnLabel, { btnLabel = it.take(40) }, t("Button text", "Maandishi ya kitufe"), placeholder = t("e.g. Read the story", "mf. Soma hadithi"))
            Field(btnHref, { btnHref = it.take(500) }, t("Link", "Kiungo"), placeholder = "https://")
        }
        Text(t("Photo (optional)", "Picha (si lazima)"), style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp), color = C.ink, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
        val ph = photo
        if (ph == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn(t("Project photo", "Picha ya mradi"), { photoSheet = true }, Modifier.weight(1f).testTag("ml_photo_project"), icon = "camera", light = true, small = true)
            Btn(t("Upload", "Pakia"), { pickPhoto.launch("image/*") }, Modifier.weight(1f).testTag("ml_photo_upload"), icon = "upload", light = true, small = true)
        } else Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, C.line, RoundedCornerShape(14.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            DqmTile("camera", C.blue2, C.tint, 40.dp); Spacer(Modifier.width(10.dp))
            Text(ph.third, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = C.ink, modifier = Modifier.weight(1f))
            Btn(t("Remove", "Ondoa"), { photo = null }, small = true, light = true)
        }
        if (aud == "donors") Row(Modifier.padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).clickable { link = !link }.padding(end = 10.dp).testTag("ml_link"), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(link, { link = it }, colors = CheckboxDefaults.colors(checkedColor = C.blue))
            Column { Text(t("Include their private project link", "Weka kiungo cha siri cha miradi yao"), style = MaterialTheme.typography.bodyMedium, color = C.text); Text(t("Each donor gets their own link.", "Kila mfadhili anapata kiungo chake."), style = MaterialTheme.typography.bodySmall, color = C.muted) }
        }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn(t("Preview", "Hakiki"), {
                check(false)?.let { msg = Triple("bad", it, null); return@Btn }
                msg = Triple("", t("Getting the preview…", "Inaleta hakikisho…"), null)
                scope.launch { val r = m.api.mail(draft("preview")); if (r.ok) { msg = null; preview = r.json } else msg = Triple("bad", mailWhy(r.reason, r.json), r.reason) }
            }, Modifier.weight(1f).testTag("ml_preview"), icon = "search", light = true)
            Btn(t("Send me a test", "Nitumie jaribio"), {
                check(true)?.let { msg = Triple("bad", it, null); return@Btn }
                msg = Triple("", t("Sending you a test…", "Inakutumia jaribio…"), null)
                scope.launch { val r = m.api.mail(draft("test")); msg = if (r.ok) Triple("ok", t("Test sent to ${r.json.optString("to")} ✓ Check your inbox.", "Jaribio limetumwa kwa ${r.json.optString("to")} ✓ Angalia barua pepe yako."), null) else Triple("bad", mailWhy(r.reason, r.json), r.reason) }
            }, Modifier.weight(1f).testTag("ml_test"), icon = "mail", light = true)
        }
        Btn(t("Send", "Tuma"), {
            check(true)?.let { msg = Triple("bad", it, null); return@Btn }
            if (!o.ready) { msg = Triple("bad", mailWhy("no-mail"), "no-mail"); return@Btn }
            msg = Triple("", t("Checking who it goes to…", "Inakagua inaenda kwa nani…"), null)
            scope.launch {
                val r = m.api.mail(draft("preview"))
                if (!r.ok) { msg = Triple("bad", mailWhy(r.reason, r.json), r.reason); return@launch }
                if (r.json.optInt("count") == 0) { msg = Triple("bad", mailWhy("nobody"), null); return@launch }
                msg = null; confirm = r.json
            }
        }, Modifier.padding(top = 8.dp).fillMaxWidth().testTag("ml_send"), icon = "check", enabled = !busy)
        msg?.let { (tone, text, reason) -> Msg(text, tone); techFor(reason)?.let { TechDetails(it, "ml_msg_tech") } }
    }

    // what was sent
    Text(t("Sent", "Zilizotumwa"), style = MaterialTheme.typography.titleMedium, color = C.ink, modifier = Modifier.padding(top = 22.dp, bottom = 2.dp))
    if (o.log.isEmpty()) EmptyDrop(t("Nothing sent yet. What you send shows here.", "Bado hakuna kilichotumwa. Unachotuma kinaonekana hapa."))
    o.log.forEachIndexed { i, e ->
        val tone = if (e.failed > 0 && e.sent == 0) "bad" else if (e.failed > 0) "has" else "ok"
        val donor = e.kind == "donor"; val hu = C.hue(if (donor) "coral" else "blue")
        DqmCard("ml_log_${e.id}", Modifier.padding(top = 10.dp).rise(minOf(i, 6)), e.subject,
            tone to listOfNotNull(t("${e.sent} sent", "zimetumwa ${e.sent}"), e.failed.takeIf { it > 0 }?.let { t("$it failed", "hazikufika $it") }, e.skipped.takeIf { it > 0 }?.let { t("$it skipped", "zimerukwa $it") }).joinToString(" · ") { it.replace(' ', '\u00A0') },
            listOf(mailAudWord(e.audience, snap), e.by + " · " + whenText(e.at)), leading = { DqmTile(if (donor) "heart" else "people", hu.fg, hu.bg, 44.dp) }) { logOpen = e.id }
    }

    // sheets
    if (photoSheet) DqmSheet("ml_photo_sheet", { photoSheet = false }) { hide ->
        DqmSheetHead(t("Project photo", "Picha ya mradi"), listOf(t("The photo shown on the website for that project.", "Picha inayoonyeshwa kwenye tovuti kwa mradi huo.")), closeTag = "ml_photo_close", onClose = { hide {} }) { DqmTile("camera", C.blue2, C.tint, 64.dp) }
        var pq by remember { mutableStateOf("") }
        Field(pq, { pq = it }, t("Search projects", "Tafuta miradi"))
        snap.projects.filter { it.photo != null && (pq.isBlank() || "${it.title} ${it.area}".contains(pq.trim(), true)) }.sortedWith(compareBy({ it.id != project }, { it.title })).take(40).forEach { p ->
            Row(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, C.line, RoundedCornerShape(14.dp)).clickable { photo = Triple("project", p.photo!!, p.title); hide {} }.heightIn(min = 52.dp).padding(10.dp).testTag("ml_pp_${p.id}"),
                verticalAlignment = Alignment.CenterVertically) {
                DqmTile("camera", C.blue2, C.tint, 36.dp); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) { Text(p.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = C.ink); Text(listOf(p.area, p.country).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = C.muted) }
            }
        }
    }
    preview?.let { pv ->
        DqmSheet("ml_preview_sheet", { preview = null }) { hide ->
            val cnt = pv.optInt("count"); val names = pv.optJSONArray("names")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            DqmSheetHead(pv.optString("subject").ifBlank { t("(no subject)", "(hakuna kichwa)") },
                listOf(if (cnt > 0) t("Goes to ", "Inaenda kwa ") + mailCountWord(mailKind(audience), cnt) + ": " + names.take(5).joinToString(", ") + (if (cnt > 5) t(" and ${cnt - 5} more", " na wengine ${cnt - 5}") else "") else t("Nobody to send to yet", "Bado hakuna wa kutumiwa"),
                    t("Shown as ${pv.optString("sample")} gets it. Links in the preview are samples.", "Inaonyeshwa kama ${pv.optString("sample")} atakavyoipata. Viungo kwenye hakikisho ni mifano.")),
                closeTag = "ml_preview_close", onClose = { hide {} }) { DqmTile("mail", C.blue2, C.tint, 64.dp) }
            MailPreview(pv.optString("subject"), mailPersonalise(body, pv.optString("sample")), pv.optString("kind") == "donor", snap.who, if (btnOn) btnLabel else null, photo?.third, aud == "donors" && link)
        }
    }
    confirm?.let { c ->
        val cnt = c.optInt("count"); val who = mailCountWord(mailKind(audience), cnt)
        val sk = c.optJSONObject("skipped")?.let { s -> s.keys().asSequence().associateWith { s.optInt(it) } } ?: emptyMap()
        DqmSheet("ml_confirm", { confirm = null }) { hide ->
            DqmSheetHead(t("Send to $who?", "Tuma kwa $who?"), emptyList(), closeTag = "ml_confirm_close", onClose = { hide {} }) { DqmTile("mail", C.blue2, C.tint, 64.dp) }
            Text(mailNb(t("Each person gets their own e-mail, so nobody sees anyone else’s address. ", "Kila mtu anapata barua pepe yake, kwa hiyo hakuna anayeona anwani ya mwingine. ") + mailSkipText(sk).joinToString(" · ").let { if (it.isBlank()) "" else "$it. " } +
                t("It can’t be called back once sent.", "Haiwezi kurudishwa ikishatumwa.")), style = MaterialTheme.typography.bodyMedium, color = C.text, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp).fillMaxWidth())
            Btn(t("Send to $who", "Tuma kwa $who"), {
                busy = true
                hide {
                    msg = Triple("", t("Sending…", "Inatuma…"), null)
                    scope.launch {
                        val r = m.api.mail(draft("send").put("expect", cnt))
                        busy = false
                        if (!r.ok) { msg = Triple("bad", mailWhy(r.reason, r.json), r.reason); return@launch }
                        val j = r.json
                        msg = Triple(if (j.optInt("failed") > 0) "warn" else "ok", t("Sent ✓ ", "Imetumwa ✓ ") + listOfNotNull(t("${j.optInt("sent")} sent", "zimetumwa ${j.optInt("sent")}"), j.optInt("skipped").takeIf { it > 0 }?.let { t("$it skipped", "zimerukwa $it") }, j.optInt("failed").takeIf { it > 0 }?.let { t("$it failed", "hazikufika $it") }).joinToString(" · "), null)
                        subject = ""; body = ""; photo = null; btnOn = false; btnLabel = ""; btnHref = ""; tick++
                    }
                }
            }, Modifier.padding(top = 16.dp).fillMaxWidth().testTag("ml_confirm_yes"), icon = "check")
            DqmLineBtn(t("Cancel", "Ghairi"), "x", { hide {} }, Modifier.padding(top = 8.dp).fillMaxWidth().testTag("ml_confirm_no"))
        }
    }
    o.log.firstOrNull { it.id == logOpen }?.let { e ->
        DqmSheet("ml_log_sheet", { logOpen = null }) { hide ->
            DqmSheetHead(e.subject, listOf(mailAudWord(e.audience, snap)), closeTag = "ml_log_close", onClose = { hide {} }) { val (fg, bg) = dqmTone(if (e.failed > 0) "has" else "ok"); DqmTile("mail", fg, bg, 64.dp) }
            DqmFact(t("Sent by", "Imetumwa na"), e.by); DqmFact(t("When", "Lini"), whenText(e.at))
            DqmFact(t("Sent", "Zimetumwa"), "${e.sent}", if (e.failed > 0) t("${e.failed} not delivered", "${e.failed} hazikufika") else "")
            if (e.skipped > 0) DqmFact(t("Skipped", "Zimerukwa"), "${e.skipped}", mailSkipText(e.skippedBy).joinToString("\n"))
            if (e.link) DqmFact(t("Private link", "Kiungo cha siri"), t("Included", "Kimewekwa"))
            DqmLabel(t("Each person", "Kila mtu"))
            e.rcpt.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (r.ok) C.ok else C.bad)); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = C.ink)
                        if (r.error.isNotBlank()) Text(r.error, style = MaterialTheme.typography.bodySmall, color = C.muted)
                    }
                    Text(if (r.ok) t("Sent", "Imetumwa") else t("Not delivered", "Haikufika"), style = MaterialTheme.typography.labelMedium, color = if (r.ok) C.muted else C.bad)
                }
                HorizontalDivider(color = C.line)
            }
        }
    }
}

private fun mailAudWord(a: String, snap: Snapshot): String = when {
    a == "donors:all" -> t("All donors", "Wafadhili wote")
    a == "donors:pick" -> t("Chosen donors", "Wafadhili waliochaguliwa")
    a.startsWith("donors:project:") -> a.removePrefix("donors:project:").let { id -> t("Donors of ", "Wafadhili wa ") + (snap.projects.firstOrNull { it.id == id }?.title ?: id) }
    a == "admins" -> t("Admins", "Wasimamizi")
    a == "team" -> t("The whole team", "Timu nzima")
    a == "pick" -> t("Chosen people", "Watu waliochaguliwa")
    else -> t("Chosen team members", "Wanatimu waliochaguliwa")
}

/**
 * The e-mail as people get it, drawn natively from the same parts the server uses (lib/mail.js renderMail with
 * lib/pin-store.js mailHtml): the coloured stripe and logo, icon, eyebrow, heading, photo, the message, the
 * signature, the button, the note and footer. E-mails are always light, so these are the e-mail's own colours.
 */
@Composable
fun MailPreview(subject: String, body: String, donor: Boolean, by: String, button: String?, photo: String?, link: Boolean) {
    val fg = if (donor) Color(0xFFD23C32) else Color(0xFF0A78C2); val tint = if (donor) Color(0xFFFDE9E6) else Color(0xFFE3F0FA)
    val navy = Color(0xFF04263F); val text = Color(0xFF1F2A33); val grey = Color(0xFF5A6066)
    Column(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFFEEF2F6)).padding(10.dp).testTag("ml_mail")) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White)) {
            Box(Modifier.fillMaxWidth().height(5.dp).background(fg))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.rafiki_logo_dark), "RAFIKI Outreach", Modifier.height(30.dp), contentScale = ContentScale.Fit)
                Spacer(Modifier.weight(1f))
                Text((if (donor) "RAFIKI Outreach" else "RAFIKI Pin").uppercase(), color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(tint).padding(horizontal = 10.dp, vertical = 4.dp))
            }
            HorizontalDivider(color = Color(0xFFE8EDF2))
            Column(Modifier.padding(18.dp)) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tint), contentAlignment = Alignment.Center) { Icon(DqmIc.get(if (donor) "heart" else "bell"), null, tint = fg, modifier = Modifier.size(22.dp)) }
                Text((if (donor) "News from RAFIKI Outreach" else "Message from " + by.ifBlank { "the office" }).uppercase(), color = fg, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp, modifier = Modifier.padding(top = 12.dp))
                Text(subject, color = navy, fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                if (photo != null) Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(150.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0A5A8C)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Ic.get("camera"), null, tint = Color.White, modifier = Modifier.size(28.dp)); Text(photo, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp, start = 12.dp, end = 12.dp)) }
                }
                Spacer(Modifier.height(10.dp))
                mailBlocks(body).forEach { (k, lines) ->
                    if (k == "p") Text(lines.joinToString("\n"), color = text, fontSize = 15.sp, lineHeight = 23.sp, modifier = Modifier.padding(top = 8.dp))
                    else Column(Modifier.padding(top = 8.dp, start = 6.dp)) { lines.forEach { l -> Row { Text("•", color = text, fontSize = 15.sp, lineHeight = 23.sp); Spacer(Modifier.width(8.dp)); Text(l, color = text, fontSize = 15.sp, lineHeight = 23.sp) } } }
                }
                Text((if (by.isNotBlank()) "$by, " else "") + "RAFIKI Outreach", color = text, fontSize = 15.sp, lineHeight = 23.sp, modifier = Modifier.padding(top = 8.dp))
                val cta = button ?: if (donor && link) "See your projects, map and photos" else null
                if (cta != null) Box(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(99.dp)).background(fg).padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
                    Text("$cta  →", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
                if (donor) Text(if (button != null && link) "Your private project page: open it here. Please don’t forward it." else "Questions? Just reply to this e-⁠mail.", color = grey, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp).fillMaxWidth())
            }
        }
        Text(if (donor) "You get this because you support RAFIKI Outreach projects. Unsubscribe" else "You get this because you are on the RAFIKI Pin team.", color = Color(0xFF6B7680), fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 10.dp).fillMaxWidth())
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(navy).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("1817 N. Beck Street, Salt Lake City, UT 84116, U.S.A.", color = Color(0xFFC9D6E2), fontSize = 11.sp, textAlign = TextAlign.Center)
            Text("info@rafikioutreach.org", color = Color(0xFF8FD3FF), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Choose people: one searchable list of everyone who can get e-mail, under Admins, Team and Donors, each group with
 *  "Select all"; then the people picked as chips that can be taken off (feat-teama). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MailPickList(o: MlOverview, picks: androidx.compose.runtime.snapshots.SnapshotStateList<String>, q: String, setQ: (String) -> Unit) {
    // row: key, name, what (role or donor), why it can't get e-mail ("" when it can)
    val groups = listOf(
        Triple("admins", t("Admins", "Wasimamizi"), o.people.filter { it.role == "admin" }.map { listOf(it.key, it.name, t("Admin", "Msimamizi"), if (it.email) "" else "no-email") }),
        Triple("team", t("Team", "Timu"), o.people.filter { it.role != "admin" }.map { listOf(it.key, it.name, t("Field team", "Timu ya uwanjani"), if (it.email) "" else "no-email") }),
        Triple("donors", t("Donors", "Wafadhili"), o.donors.flatMap { d -> d.viewers.map { v -> listOf(v.key, v.name, if (d.name != v.name) d.name else t("Donor", "Mfadhili"), v.why) } }),
    )
    val names = groups.flatMap { it.third }.associate { it[0] to it[1] }
    Field(q, setQ, t("Find a name", "Tafuta jina"), Modifier.testTag("ml_q"), placeholder = t("Name, role or donor", "Jina, nafasi au mfadhili"))
    val why = mapOf("unsub" to t("unsubscribed", "amejiondoa"), "revoked" to t("link revoked", "kiungo kimezimwa"), "no-email" to mailNb(t("no e-mail", "hana barua pepe")))
    groups.forEach { (gk, title, all) ->
        val rows = all.filter { q.isBlank() || (it[1] + " " + it[2]).contains(q.trim(), true) }.sortedWith(compareBy({ it[3].isNotBlank() }, { it[1].lowercase() }))
        if (rows.isEmpty()) return@forEach
        val ok = rows.filter { it[3].isBlank() }.map { it[0] }
        val allOn = ok.isNotEmpty() && ok.all { it in picks }
        Row(Modifier.padding(top = 14.dp, bottom = 4.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.sp, fontWeight = FontWeight.ExtraBold), color = C.muted, modifier = Modifier.weight(1f).testTag("ml_g_$gk"))
            if (ok.isNotEmpty()) Text(if (allOn) t("Clear", "Ondoa wote") else t("Select all", "Chagua wote"), style = MaterialTheme.typography.labelLarge, color = C.blue, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { if (allOn) picks.removeAll(ok) else ok.filterNot { it in picks }.forEach { picks.add(it) } }
                    .heightIn(min = 40.dp).padding(horizontal = 10.dp, vertical = 10.dp).testTag("ml_all_$gk"))
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, C.line, RoundedCornerShape(14.dp))) {
            rows.take(80).forEachIndexed { i, r ->
                val off = r[3].isNotBlank(); val on = r[0] in picks && !off
                if (i > 0) HorizontalDivider(color = C.line)
                Row(Modifier.fillMaxWidth().clickable(enabled = !off, role = Role.Checkbox) { if (on) picks.remove(r[0]) else picks.add(r[0]) }.heightIn(min = 52.dp).padding(horizontal = 6.dp, vertical = 4.dp).testTag("ml_pk_" + r[0]),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(on, { if (it) picks.add(r[0]) else picks.remove(r[0]) }, enabled = !off, colors = CheckboxDefaults.colors(checkedColor = C.blue))
                    Column(Modifier.weight(1f)) {
                        Text(r[1], style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = if (off) C.muted else C.ink)
                        listOf(r[2], why[r[3]] ?: "").filter { it.isNotBlank() }.joinToString(" · ").let { Text(it, style = MaterialTheme.typography.bodySmall, color = C.muted) }
                    }
                }
            }
        }
    }
    if (groups.all { g -> g.third.none { q.isBlank() || (it[1] + " " + it[2]).contains(q.trim(), true) } }) Text(t("Nobody matches.", "Hakuna anayelingana."), color = C.muted, modifier = Modifier.padding(12.dp))
    // the people picked, as chips above the subject
    if (picks.isNotEmpty()) FlowRow(Modifier.padding(top = 10.dp).testTag("ml_chips"), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        picks.toList().forEach { k -> Chip(names[k] ?: k, null, false, { picks.remove(k) }, trailing = "x", tag = "ml_chip_$k", hue = "blue") }
    }
}

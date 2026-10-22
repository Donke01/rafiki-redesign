package org.rafikioutreach.pin.ui

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.COUNTRIES
import org.rafikioutreach.pin.data.Project
import org.rafikioutreach.pin.data.Snapshot
import org.rafikioutreach.pin.data.reasonText
import org.rafikioutreach.pin.t

// Office › Donors: private donor pages (same server actions as the web app's Office › Donors, see lib/donor.js).
// Each donor gets a private link to a page with their projects, an exact-location map and updates; group donors
// (a church, a company) can have several viewers, each with their own link.

data class DonorViewer(val vid: String, val name: String, val email: String, val primary: Boolean, val lastOpen: String, val active: Boolean, val code: String = "")
data class Donor(val id: String, val name: String, val email: String, val org: String, val projects: List<String>, val showPhotos: Boolean, val photos: Map<String, String>,
                 val digest: Boolean, val lastOpen: String, val opens: Int, val revoked: Boolean, val viewers: List<DonorViewer>)

private fun JSONObject.str(k: String) = optString(k).takeIf { it != "null" } ?: ""
fun parseDonors(j: JSONObject): List<Donor> {
    val a = j.optJSONArray("donors") ?: JSONArray()
    return (0 until a.length()).map { i ->
        val d = a.getJSONObject(i)
        val ps = d.optJSONArray("projects") ?: JSONArray()
        val ph = d.optJSONObject("photos") ?: JSONObject()
        val vs = d.optJSONArray("viewers") ?: JSONArray()
        Donor(d.str("id"), d.str("name"), d.str("email"), d.str("org"), (0 until ps.length()).map { ps.getString(it) }, d.optBoolean("showPhotos", true),
            ph.keys().asSequence().associateWith { ph.getString(it) }, d.optBoolean("digest", true), d.str("lastOpen"), d.optInt("opens"), d.optBoolean("revoked"),
            (0 until vs.length()).map { k -> vs.getJSONObject(k).let { v -> DonorViewer(v.str("vid"), v.str("name"), v.str("email"), v.optBoolean("primary"), v.str("lastOpen"), v.optBoolean("active", true), v.str("code")) } })
    }
}

/** water · school · health · children · other (as on the web) */
fun donorCat(type: String): String = type.lowercase().let {
    when {
        "borehole" in it || "collection" in it -> "water"
        "school" in it -> "school"
        "health" in it -> "health"
        "orphan" in it -> "children"
        else -> "other"
    }
}
/** Visit photos of schools, clinics and children's homes stay hidden from donors until an admin ticks "photos OK". */
fun donorSensitive(type: String) = donorCat(type) in setOf("school", "health", "children")

/** The projects shown in the picker: search, country, type, "ticked only"; ticked first. */
fun pickProjects(all: List<Project>, q: String, ck: String, cat: String, ticked: Set<String>, only: Boolean): List<Project> {
    val s = q.trim().lowercase()
    return all.filter { p ->
        (!only || p.id in ticked) && (ck.isBlank() || p.ck == ck) && (cat.isBlank() || donorCat(p.type) == cat) &&
            (s.isBlank() || "${p.title} ${p.area} ${p.country}".lowercase().contains(s))
    }.sortedWith(compareBy<Project>({ it.id !in ticked }, { it.title }))
}

fun donorShareText(name: String, link: String) =
    "Hello " + name.trim().split(Regex("\\s+")).firstOrNull().orEmpty() + ", here is your private RAFIKI Outreach page with the projects you funded: photos, visit reports and a live map. It’s just for you, so please don’t forward it.\n" + link

// feat-donorapp: the RAFIKI Pin app for donors. The invite is in English: donors read it (most of them abroad).
const val DONOR_APK = "https://pin.rafikioutreach.org/app/rafiki-pin.apk"
const val DONOR_WEB_APP = "https://pin.rafikioutreach.org/pin"
fun donorInviteText(name: String, code: String) =
    "Hello " + name.trim().split(Regex("\\s+")).firstOrNull().orEmpty() + ", you can now follow the projects you funded in the RAFIKI Pin app: photos, visits and thank-you messages from the communities.\n\n" +
        "Android: download the app from $DONOR_APK, open it and tap “I’m a donor”.\n" +
        "iPhone: open $DONOR_WEB_APP, tap “I’m a donor”, then Share › Add to Home Screen.\n\n" +
        "Your donor code: $code\nIt’s just for you, so please don’t share it."
private fun shareInvite(ctx: android.content.Context, name: String, code: String) {
    runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Your projects in the RAFIKI Pin app").putExtra(Intent.EXTRA_TEXT, donorInviteText(name, code)), t("Send the app invite", "Tuma mwaliko wa programu")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
/** The app code next to "Copy private link": the code, Copy, and Send app invite. */
@Composable
private fun DonorCodeRow(name: String, code: String, copy: (String) -> Unit, said: (String) -> Unit) {
    if (code.isBlank()) return
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, C.line, RoundedCornerShape(16.dp)).background(C.card).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp).testTag("dn_code"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(t("App code", "Msimbo wa programu"), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = C.muted, maxLines = 1, softWrap = false)
            Text(code, style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 16.sp, letterSpacing = 0.5.sp), color = C.ink, maxLines = 1, softWrap = false)
        }
        Btn(t("Copy", "Nakili"), { copy(code); said(t("Code copied ✓", "Msimbo umenakiliwa ✓")) }, Modifier.testTag("dn_code_copy"), small = true, light = true, icon = "copy")
    }
    DqmLineBtn(t("Send app invite", "Tuma mwaliko wa programu"), "heart", { shareInvite(ctx, name, code) }, Modifier.fillMaxWidth().testTag("dn_invite"))
}

/** all · opened · not (not opened yet) · revoked */
fun donorState(d: Donor) = when { d.revoked -> "revoked"; d.lastOpen.isNotBlank() -> "opened"; else -> "not" }

@Composable
private fun donorPill(d: Donor): Pair<String, String> = when (donorState(d)) {
    "revoked" -> "off" to t("Link revoked", "Kiungo kimezimwa")
    "opened" -> "ok" to t("Opened their page", "Wamefungua ukurasa")
    else -> "has" to t("Not opened yet", "Bado hawajafungua")
}
private fun donorProjects(n: Int) = if (n == 1) t("1 project", "mradi 1") else t("$n projects", "miradi $n")

/**
 * Office › Donors: numbers and filters, a calm card per donor that opens a sheet with every action (copy, share,
 * rotate, revoke, edit, who can open the page, delete), "Add a donor" as a sheet, and the e-mail digest folded away.
 */
@Composable
fun DonorsSection(m: AppModel, snap: Snapshot) {
    val scope = rememberScope()
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var tick by remember { mutableStateOf(0) }
    var list by remember { mutableStateOf<List<Donor>?>(null) }
    var mailReady by remember { mutableStateOf(false) }
    var loadMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tick) {
        val r = m.api.adminGet("donors")
        if (r.ok) { list = parseDonors(r.json); mailReady = r.json.optJSONObject("mail")?.optBoolean("ready") == true; loadMsg = null } else loadMsg = reasonText(r.reason)
    }
    var filter by remember { mutableStateOf("all") }
    var more by remember { mutableStateOf(false) }
    // the donor whose sheet is open (by id), and one to open once the list has it (by name, after adding)
    var picked by remember { mutableStateOf<String?>(null) }
    var openName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(list) { openName?.let { n -> list?.lastOrNull { it.name == n }?.let { picked = it.id; openName = null } } }
    // the link just made or asked for: (donor id or name, who, link, e-mail)
    var shown by remember { mutableStateOf<List<String>?>(null) }
    // the form (add, or edit when editId is set), in its own sheet
    var formOpen by remember { mutableStateOf(false) }
    var editId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var org by remember { mutableStateOf("") }
    val ticked = remember { mutableStateListOf<String>() }
    val photosOk = remember { mutableStateListOf<String>() }
    var showPhotos by remember { mutableStateOf(true) }
    var digest by remember { mutableStateOf(true) }
    var q by remember { mutableStateOf("") }
    var ck by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("") }
    var only by remember { mutableStateOf(false) }
    var formMsg by remember { mutableStateOf<Pair<String, String>?>(null) }
    var msg by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun reset(d: Donor?) {
        editId = d?.id; name = d?.name ?: ""; email = d?.email ?: ""; org = d?.org ?: ""
        ticked.clear(); d?.projects?.let { ticked.addAll(it) }
        photosOk.clear(); d?.photos?.filterValues { it == "on" }?.keys?.let { photosOk.addAll(it) }
        showPhotos = d?.showPhotos ?: true; digest = d?.digest ?: true; q = ""; only = d != null; formMsg = null
    }
    fun share(who: String, link: String) {
        runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Your RAFIKI project page").putExtra(Intent.EXTRA_TEXT, donorShareText(who, link)), t("Send the private link", "Tuma kiungo cha siri")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    val L = (list ?: emptyList()).sortedWith(compareBy<Donor>({ it.revoked }, { it.lastOpen.isBlank() }).thenByDescending { it.lastOpen }.thenBy { it.name })
    val count = L.groupingBy { donorState(it) }.eachCount()
    DqmHead("dn_head", "heart", "coral", t("Donors", "Wafadhili"), t("Each donor gets a private page with their projects, a map and updates.", "Kila mfadhili anapata ukurasa wa siri wenye miradi yake, ramani na taarifa."),
        listOf(DqmNum(L.size, t("donors", "wafadhili"), "", "all"), DqmNum(count["opened"] ?: 0, t("opened", "wamefungua"), if ((count["opened"] ?: 0) > 0) "ok" else "off", "opened"),
            DqmNum(count["not"] ?: 0, t("not opened", "hawajafungua"), if ((count["not"] ?: 0) > 0) "has" else "off", "not")), filter, { filter = it; more = false }) {
        Btn(t("Add a donor", "Ongeza mfadhili"), { reset(null); formOpen = true }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("dn_add"), icon = "plus")
    }
    loadMsg?.let { Msg(it, "bad") }
    msg?.let { (tone, text) -> Msg(text, tone) }
    if (list != null && L.isNotEmpty()) DqmChips(listOf(Triple("all", t("All", "Wote"), L.size), Triple("opened", t("Opened", "Wamefungua"), count["opened"] ?: 0),
        Triple("not", t("Not opened", "Hawajafungua"), count["not"] ?: 0), Triple("revoked", t("Revoked", "Vimezimwa"), count["revoked"] ?: 0)), filter, "dn_filter_") { filter = it; more = false }
    val shownL = L.filter { filter == "all" || donorState(it) == filter }
    if (list != null && L.isEmpty()) EmptyDrop(t("No donors yet. Tap “Add a donor” to make the first private page.", "Bado hakuna wafadhili. Gusa “Ongeza mfadhili” kutengeneza ukurasa wa kwanza wa siri."))
    else if (list != null && shownL.isEmpty()) EmptyDrop(when (filter) { "revoked" -> t("No revoked links.", "Hakuna viungo vilivyozimwa."); "not" -> t("Everyone has opened their page.", "Wote wamefungua ukurasa wao."); else -> t("Nobody has opened their page yet.", "Bado hakuna aliyefungua ukurasa wake.") })
    shownL.take(if (more) shownL.size else 30).forEachIndexed { i, d ->
        val hu = C.hue(listOf("blue", "green", "purple", "teal", "coral", "slate")[(d.name.lowercase().hashCode() and 0x7fffffff) % 6])
        DqmCard("dn_${d.id}", Modifier.padding(top = 10.dp).rise(minOf(i, 6)), d.name, donorPill(d),
            listOf((if (d.org.isNotBlank() && d.org != d.name) d.org else d.email).ifBlank { t("No e-mail", "Hakuna barua pepe") },
                donorProjects(d.projects.size) + (if (d.viewers.size > 1) t(" · ${d.viewers.size} people", " · watu ${d.viewers.size}") else "") + (if (!d.showPhotos) t(" · photos off", " · picha zimezimwa") else "")),
            if (d.lastOpen.isNotBlank()) smsAgo(d.lastOpen) else "", d.revoked, { DqmAvatar(d.name, hu.bg, hu.fg) }) { picked = d.id }
    }
    if (shownL.size > 30 && !more) Btn(t("Show more", "Onyesha zaidi") + " (" + (shownL.size - 30) + ")", { more = true }, Modifier.padding(top = 12.dp).fillMaxWidth(), light = true, small = true)

    // settings, folded away
    Text(t("Settings", "Mipangilio"), style = MaterialTheme.typography.titleMedium, color = C.ink, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
    Card(pad = 4.dp) {
        DqmFold("dn_mail_fold", "mail", "teal", t("E-mail digest", "Barua pepe ya kila mwezi"), if (mailReady) t("On · each donor, on the 1st of the month", "Imewashwa · kila mfadhili, tarehe 1 ya mwezi") else t("Off", "Imezimwa"), if (mailReady) "ok" else "off") {
            Text(if (mailReady) t("On: each donor with an e-mail gets a monthly update on the 1st.", "Imewashwa: kila mfadhili mwenye barua pepe anapata taarifa ya mwezi tarehe 1.")
                 else t("Off. Sending e-mail isn’t set up yet; whoever looks after the website can switch it on.", "Imezimwa. Kutuma barua pepe bado hakujawekwa; anayesimamia tovuti anaweza kuiwasha."), style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.testTag("dn_mail"))
            if (!mailReady) TechDetails(listOf("RESEND_API_KEY" to false, "ALERT_FROM" to false), "dn_mail_tech")
            Text(t("To stop it for one donor, open them, tap Edit and untick “Monthly e-mail update”.", "Kuizima kwa mfadhili mmoja, mfungue, gusa Hariri na uondoe tiki “Taarifa ya mwezi kwa barua pepe”."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 8.dp))
        }
        HorizontalDivider(color = C.line, modifier = Modifier.padding(horizontal = 14.dp))
        DqmFold("dn_how", "info", "blue", t("How donor pages work", "Jinsi kurasa za wafadhili zinavyofanya kazi"), t("A private link, no password", "Kiungo cha siri, bila nenosiri"), "has") {
            Text(t("Each donor gets a private link to a page with their projects, an exact-location map, visit reports, repairs, story moments and photos. No password: anyone with the link can see it, so send it only to them.",
                "Kila mfadhili anapata kiungo cha siri cha ukurasa wenye miradi yake, ramani ya mahali kamili, ripoti za ziara, matengenezo, hadithi na picha. Hakuna nenosiri: yeyote mwenye kiungo anaweza kuuona, kwa hiyo mtumie yeye tu."), style = MaterialTheme.typography.bodyMedium, color = C.text)
            Text(t("A church or company can have several people, each with their own link, so you can switch one off without affecting the others.", "Kanisa au kampuni inaweza kuwa na watu kadhaa, kila mmoja na kiungo chake, ili uweze kuzima kimoja bila kuathiri wengine."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 8.dp))
            Text(t("Visit photos of water points are shown. Schools, clinics and children’s homes stay hidden until you tick “photos OK” (children’s faces need consent).", "Picha za ziara za vituo vya maji zinaonyeshwa. Shule, zahanati na makao ya watoto zinafichwa hadi uchague “picha sawa” (nyuso za watoto zinahitaji ridhaa)."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 8.dp))
        }
    }

    // a donor's sheet
    list?.firstOrNull { it.id == picked }?.let { d ->
        DonorSheet(m, d, shown?.takeIf { it[0] == d.id || it[0] == d.name }, { picked = null }, { s -> shown = s }, { tick++ }, { share(it[1], it[2]) },
            { link -> clip.setText(AnnotatedString(link)) }, edit = { reset(d); formOpen = true })
    }
    // add / edit
    if (formOpen) {
        val editing = list?.firstOrNull { it.id == editId }
        DqmSheet("dn_form", { formOpen = false }) { hide ->
            DqmSheetHead(if (editing != null) editing.name else t("Add a donor", "Ongeza mfadhili"),
                listOf(if (editing != null) t("Change their details, projects and photos.", "Badilisha maelezo yao, miradi na picha.") else t("They get a private link to their own page. You can copy it again, replace it or switch it off at any time.", "Wanapata kiungo cha siri cha ukurasa wao. Unaweza kukinakili tena, kukibadilisha au kukizima wakati wowote.")),
                closeTag = "dn_form_close", onClose = { hide {} }) { val (fg, bg) = dqmTone("has"); DqmTile(if (editing != null) "edit" else "heart", fg, bg, 64.dp) }
            Field(name, { name = it }, t("Name", "Jina"), Modifier.testTag("dn_name"), placeholder = t("e.g. Grace Community Church", "mf. Kanisa la Grace"))
            Field(email, { email = it }, t("E-mail", "Barua pepe"), placeholder = t("for the monthly update", "kwa taarifa ya mwezi"))
            Field(org, { org = it }, t("Organisation (optional)", "Shirika (si lazima)"), placeholder = t("e.g. a church or company", "mf. kanisa au kampuni"))
            Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t("Projects they funded", "Miradi waliyofadhili"), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.weight(1f))
                Tag("${ticked.size} " + t("ticked", "zimechaguliwa"), C.tint, C.deep)
            }
            Field(q, { q = it }, t("Search", "Tafuta"), Modifier.testTag("dn_q"), placeholder = t("Name or place", "Jina au mahali"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Picker(t("Country", "Nchi"), ck, listOf("" to t("All countries", "Nchi zote")) + COUNTRIES.toList(), { ck = it }, Modifier.weight(1f))
                Picker(t("Type", "Aina"), cat, listOf("" to t("All types", "Aina zote"), "water" to t("Water", "Maji"), "school" to t("Schools", "Shule"), "health" to t("Health", "Afya"), "children" to t("Children’s homes", "Makao ya watoto"), "other" to t("Other", "Nyingine")), { cat = it }, Modifier.weight(1f))
            }
            Row(Modifier.padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).clickable { only = !only }.padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(only, { only = it }, colors = CheckboxDefaults.colors(checkedColor = C.blue)); Text(t("Ticked only", "Zilizochaguliwa tu"), style = MaterialTheme.typography.bodyMedium, color = C.text)
            }
            val shownP = pickProjects(snap.projects, q, ck, cat, ticked.toSet(), only)
            Column(Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, C.line, RoundedCornerShape(14.dp))) {
                shownP.take(40).forEachIndexed { k, p ->
                    val on = p.id in ticked
                    if (k > 0) HorizontalDivider(color = C.line)
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { if (on) ticked.remove(p.id) else ticked.add(p.id) }.heightIn(min = 56.dp).padding(horizontal = 6.dp, vertical = 4.dp).testTag("dn_p_${p.id}"), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(on, { if (it) ticked.add(p.id) else ticked.remove(p.id) }, colors = CheckboxDefaults.colors(checkedColor = C.blue))
                        Column(Modifier.weight(1f)) {
                            Text(p.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = C.ink)
                            Text(listOf(p.type, p.area, p.country).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = C.muted)
                        }
                    }
                }
                if (shownP.isEmpty()) Text(t("No projects match.", "Hakuna mradi unaolingana."), color = C.muted, modifier = Modifier.padding(12.dp))
                if (shownP.size > 40) Text(t("…and ${shownP.size - 40} more. Search to narrow it down.", "…na ${shownP.size - 40} zaidi. Tafuta ili kupunguza."), color = C.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
            }
            Row(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.tint).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t("Show visit photos", "Onyesha picha za ziara"), style = MaterialTheme.typography.titleSmall, color = C.ink)
                    Text(t("Water points: shown. Schools, clinics and children’s homes: hidden until you tick “photos OK” (children’s faces need consent).", "Vituo vya maji: zinaonyeshwa. Shule, zahanati na makao ya watoto: zimefichwa hadi uchague “picha sawa” (nyuso za watoto zinahitaji ridhaa)."), style = MaterialTheme.typography.bodySmall, color = C.muted)
                }
                Spacer(Modifier.width(8.dp))
                Switch(showPhotos, { showPhotos = it }, Modifier.testTag("dn_photos"), colors = SwitchDefaults.colors(checkedTrackColor = C.ok))
            }
            val sens = ticked.mapNotNull { id -> snap.projects.firstOrNull { it.id == id } }.filter { donorSensitive(it.type) }
            if (showPhotos && sens.isNotEmpty()) {
                Text(t("Photos OK? (consent checked)", "Picha sawa? (ridhaa imethibitishwa)"), style = MaterialTheme.typography.labelLarge, color = C.ink, modifier = Modifier.padding(top = 10.dp))
                sens.forEach { p ->
                    val on = p.id in photosOk
                    Row(Modifier.clickable { if (on) photosOk.remove(p.id) else photosOk.add(p.id) }.testTag("dn_ok_${p.id}"), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(on, { if (it) photosOk.add(p.id) else photosOk.remove(p.id) }, colors = CheckboxDefaults.colors(checkedColor = C.blue)); Text(p.title, style = MaterialTheme.typography.bodyMedium, color = C.text)
                    }
                }
            }
            Row(Modifier.padding(top = 6.dp).clickable { digest = !digest }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(digest, { digest = it }, colors = CheckboxDefaults.colors(checkedColor = C.blue)); Text(t("Monthly e-mail update", "Taarifa ya mwezi kwa barua pepe"), style = MaterialTheme.typography.bodyMedium, color = C.text)
            }
            var busy by remember { mutableStateOf(false) }
            Btn(if (editing != null) t("Save changes", "Hifadhi mabadiliko") else t("Add and make private link", "Ongeza na utengeneze kiungo cha siri"), {
                busy = true; formMsg = "" to t("Saving…", "Inahifadhi…")
                scope.launch {
                    val ph = JSONObject(); photosOk.filter { it in ticked }.forEach { ph.put(it, "on") }
                    editing?.photos?.filter { it.value == "off" && it.key in ticked }?.forEach { ph.put(it.key, "off") }
                    val body = JSONObject().put("action", if (editing != null) "donor-update" else "donor-add").put("name", name.trim()).put("email", email.trim()).put("org", org.trim())
                        .put("projects", JSONArray(ticked.toList())).put("showPhotos", showPhotos).put("photos", ph).put("digest", digest)
                    if (editing != null) body.put("id", editing.id)
                    val r = m.api.admin(body)
                    busy = false
                    if (!r.ok) { formMsg = "bad" to reasonText(r.reason); return@launch }
                    val who = name.trim()
                    if (editing == null) { shown = listOf(who, who, r.json.optString("link"), email.trim()); openName = who; msg = "ok" to t("Added ✓ Send them their private link.", "Imeongezwa ✓ Mtumie kiungo chake cha siri.") }
                    else { msg = "ok" to t("Saved ✓", "Imehifadhiwa ✓"); picked = editing.id }
                    hide { reset(null); tick++ }
                }
            }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("dn_save"), icon = if (editing != null) "check" else "plus", enabled = name.isNotBlank() && !busy)
            DqmLineBtn(t("Cancel", "Ghairi"), "x", { hide { reset(null) } }, Modifier.padding(top = 8.dp).fillMaxWidth().testTag("dn_cancel"))
            formMsg?.let { (tone, text) -> Msg(text, tone) }
        }
    }
}

/** One donor: the private link (copy, share, rotate, revoke), edit, who can open the page, delete. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DonorSheet(m: AppModel, d: Donor, link: List<String>?, close: () -> Unit, setLink: (List<String>?) -> Unit, changed: () -> Unit, share: (List<String>) -> Unit, copy: (String) -> Unit, edit: () -> Unit) {
    val scope = rememberScope()
    val ctx = LocalContext.current
    var msg by remember { mutableStateOf<Pair<String, String>?>(null) }
    var ask by remember { mutableStateOf<String?>(null) }
    var askMsg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var vName by remember { mutableStateOf("") }
    var vEmail by remember { mutableStateOf("") }
    var vAsk by remember { mutableStateOf<Pair<String, String>?>(null) }
    val viewersOpen = remember { mutableStateOf(false) }
    fun act(action: String, vid: String? = null, after: (JSONObject) -> Unit = {}) {
        busy = true
        scope.launch {
            val body = JSONObject().put("action", action).put("id", d.id).apply { if (vid != null) put("vid", vid) }
            val r = m.api.admin(body)
            busy = false
            if (!r.ok) { val why = when (r.reason) { "revoked" -> t("This link was revoked. Make a new one.", "Kiungo hiki kimezimwa. Tengeneza kipya."); "rotate" -> t("Make a new link (Rotate) to copy it.", "Tengeneza kiungo kipya ili ukinakili."); else -> reasonText(r.reason) }; if (ask != null) askMsg = why else msg = "bad" to why; return@launch }
            after(r.json)
        }
    }
    val (fg, bg) = dqmTone(donorPill(d).first)
    DqmSheet("dn_sheet", close) { hide ->
        DqmSheetHead(d.name, listOf(if (d.org.isNotBlank() && d.org != d.name) d.org else "", d.email), donorPill(d), "dn_close", { hide {} }) { DqmTile("heart", fg, bg, 64.dp) }
        // the link just made or asked for
        link?.let { (_, who, url, _) ->
            Column(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.okBg).padding(14.dp).testTag("dn_link")) {
                Text(t("Private link for ", "Kiungo cha siri cha ") + who, style = MaterialTheme.typography.titleSmall, color = C.ok)
                // the long link breaks after "/donor/", never inside the private token
                Text(url.replace("/donor/", "/donor/\n"), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp), color = C.ink,
                    modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.card).padding(10.dp))
                Text(t("Anyone with this link can see the page. Send it only to them.", "Yeyote mwenye kiungo hiki anaweza kuona ukurasa. Mtumie yeye tu."), style = MaterialTheme.typography.bodySmall, color = C.text)
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn(t("Copy", "Nakili"), { copy(url); msg = "ok" to t("Link copied ✓", "Kiungo kimenakiliwa ✓") }, Modifier.weight(1f).testTag("dn_link_copy"), small = true, light = true, icon = "copy")
                    Btn(t("Share", "Shiriki"), { share(link) }, Modifier.weight(1f).testTag("dn_link_share"), small = true, light = true, icon = "share")
                }
            }
        }
        // the main actions
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!d.revoked && link != null) {
                // the link is shown above with its own Copy and Share, so only Edit here
                (d.viewers.firstOrNull { it.primary } ?: d.viewers.firstOrNull())?.let { pv -> DonorCodeRow(pv.name.ifBlank { d.name }, pv.code, copy) { msg = "ok" to it } }
                DqmLineBtn(t("Edit", "Hariri"), "edit", { hide { edit() } }, Modifier.fillMaxWidth().testTag("dn_edit"))
            } else if (!d.revoked) {
                Btn(t("Copy private link", "Nakili kiungo cha siri"), { act("donor-link") { j -> copy(j.optString("link")); msg = "ok" to t("Link copied ✓", "Kiungo kimenakiliwa ✓") } }, Modifier.fillMaxWidth().testTag("dn_copy"), icon = "copy", enabled = !busy)
                (d.viewers.firstOrNull { it.primary } ?: d.viewers.firstOrNull())?.let { pv -> DonorCodeRow(pv.name.ifBlank { d.name }, pv.code, copy) { msg = "ok" to it } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DqmLineBtn(t("Share link", "Shiriki kiungo"), "share", { act("donor-link") { j -> val s = listOf(d.id, d.name, j.optString("link"), d.email); setLink(s); share(s) } }, Modifier.weight(1f).testTag("dn_share"))
                    DqmLineBtn(t("Edit", "Hariri"), "edit", { hide { edit() } }, Modifier.weight(1f).testTag("dn_edit"))
                }
            } else {
                Btn(t("Make a new link", "Tengeneza kiungo kipya"), { act("donor-rotate") { j -> setLink(listOf(d.id, d.name, j.optString("link"), d.email)); msg = "ok" to t("New link made ✓", "Kiungo kipya kimetengenezwa ✓"); changed() } }, Modifier.fillMaxWidth().testTag("dn_newlink"), icon = "link", enabled = !busy)
                DqmLineBtn(t("Edit", "Hariri"), "edit", { hide { edit() } }, Modifier.fillMaxWidth().testTag("dn_edit"))
            }
        }
        msg?.let { (tone, text) -> Box(Modifier.testTag("dn_msg")) { Msg(text, tone) } }
        // facts
        Column(Modifier.padding(top = 12.dp)) {
            val ps = d.projects.map { id -> m.snap.value?.projects?.firstOrNull { it.id == id }?.title ?: id }
            DqmFact(t("Projects", "Miradi"), donorProjects(ps.size), ps.take(3).joinToString(", ") + if (ps.size > 3) " …" else "")
            DqmFact(t("Last opened", "Ilifunguliwa mwisho"), if (d.lastOpen.isNotBlank()) whenText(d.lastOpen) else t("Not yet", "Bado"), if (d.opens > 1) t("${d.opens} times", "mara ${d.opens}") else "")
            DqmFact(t("Visit photos", "Picha za ziara"), if (d.showPhotos) t("Shown", "Zinaonyeshwa") else t("Hidden", "Zimefichwa"))
            DqmFact(t("Monthly e-mail", "Barua pepe ya mwezi"), if (d.digest) t("On", "Imewashwa") else t("Off", "Imezimwa"))
        }
        // who can open this page, folded
        Column(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).border(1.dp, C.line, RoundedCornerShape(18.dp)).padding(vertical = 2.dp)) {
            DqmFold("dn_viewers", "people", "purple", t("Who can open this page", "Nani anaweza kufungua ukurasa huu"), if (d.viewers.size == 1) t("1 person", "mtu 1") else t("${d.viewers.size} people", "watu ${d.viewers.size}"), if (d.viewers.size > 1) "has" else "off", viewersOpen) {
                Text(t("For a church or company: give each person their own link, so you can switch one off without affecting the others.", "Kwa kanisa au kampuni: mpe kila mtu kiungo chake, ili uweze kuzima kimoja bila kuathiri wengine."), style = MaterialTheme.typography.bodySmall, color = C.muted)
                d.viewers.forEach { v ->
                    Column(Modifier.padding(top = 10.dp).fillMaxWidth().testTag("dn_v_${v.vid}")) {
                        Text(v.name.ifBlank { d.name } + (if (v.primary) t(" (main)", " (mkuu)") else "") + (if (!v.active) t(" · revoked", " · kimezimwa") else ""), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = C.ink)
                        Text(listOf(v.email, if (v.lastOpen.isNotBlank()) t("opened ", "kimefunguliwa ") + whenText(v.lastOpen) else t("not opened yet", "bado hakijafunguliwa")).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = C.muted)
                        if (v.active && v.code.isNotBlank()) Text(t("App code ", "Msimbo wa programu ") + v.code, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = C.ink, modifier = Modifier.testTag("dn_v_code_${v.vid}"))
                        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (v.active) {
                                DqmLineBtn(t("Copy link", "Nakili kiungo"), "copy", { act("donor-link", v.vid) { j -> copy(j.optString("link")); msg = "ok" to t("Link copied ✓", "Kiungo kimenakiliwa ✓") } })
                                if (v.code.isNotBlank()) DqmLineBtn(t("App invite", "Mwaliko wa programu"), "heart", { shareInvite(ctx, v.name.ifBlank { d.name }, v.code) })
                                DqmLineBtn(t("Rotate", "Badilisha"), "refresh", { act("donor-rotate", v.vid) { j -> setLink(listOf(d.id, v.name.ifBlank { d.name }, j.optString("link"), v.email)); changed() } })
                                DqmLineBtn(t("Revoke", "Zima"), "x", { vAsk = v.vid to "donor-revoke" }, Modifier.testTag("dn_v_revoke_${v.vid}"), red = true)
                            } else DqmLineBtn(t("New link", "Kiungo kipya"), "link", { act("donor-rotate", v.vid) { j -> setLink(listOf(d.id, v.name.ifBlank { d.name }, j.optString("link"), v.email)); changed() } })
                            if (!v.primary) DqmLineBtn(t("Remove", "Ondoa"), "trash", { vAsk = v.vid to "donor-viewer-remove" }, red = true)
                        }
                        vAsk?.takeIf { it.first == v.vid }?.let { (vid, a) ->
                            DqmAsk((if (a == "donor-viewer-remove") t("Remove ", "Ondoa ") else t("Revoke the link for ", "Zima kiungo cha ")) + v.name.ifBlank { d.name } + "?",
                                if (a == "donor-viewer-remove") t("Remove", "Ondoa") else t("Revoke", "Zima"), true, busy, null, { vAsk = null }, { act(a, vid) { vAsk = null; changed() } }, "dn_vask")
                        }
                    }
                }
                Field(vName, { vName = it }, t("Add a person: name", "Ongeza mtu: jina"), Modifier.testTag("dn_vname"), placeholder = t("e.g. Pastor John", "mf. Mchungaji John"))
                Field(vEmail, { vEmail = it }, t("Their e-mail (optional)", "Barua pepe (si lazima)"))
                Btn(t("Add and make their link", "Ongeza na utengeneze kiungo chake"), {
                    busy = true
                    scope.launch {
                        val r = m.api.admin(JSONObject().put("action", "donor-viewer-add").put("id", d.id).put("name", vName.trim()).put("email", vEmail.trim()))
                        busy = false
                        if (r.ok) { setLink(listOf(d.id, vName.trim(), r.json.optString("link"), vEmail.trim())); vName = ""; vEmail = ""; changed() } else msg = "bad" to reasonText(r.reason)
                    }
                }, Modifier.padding(top = 10.dp).fillMaxWidth().testTag("dn_vadd"), small = true, icon = "plus", enabled = vName.isNotBlank() && !busy)
            }
        }
        // the rest: rotate, revoke, delete (each asks first)
        HorizontalDivider(color = C.line, modifier = Modifier.padding(top = 16.dp))
        // one to a row, so a longer Kiswahili label is never squeezed
        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!d.revoked) {
                DqmLineBtn(t("Rotate link", "Badilisha kiungo"), "refresh", { ask = "rotate"; askMsg = null }, Modifier.fillMaxWidth().testTag("dn_rotate"))
                DqmLineBtn(t("Revoke the link", "Zima kiungo"), "x", { ask = "revoke"; askMsg = null }, Modifier.fillMaxWidth().testTag("dn_revoke"), red = true)
            }
            DqmLineBtn(t("Delete donor", "Futa mfadhili"), "trash", { ask = "delete"; askMsg = null }, Modifier.fillMaxWidth().testTag("dn_delete"), red = true)
        }
        ask?.let { a ->
            DqmAsk(when (a) {
                "revoke" -> t("Revoke the link for ${d.name}? Their page stops working straight away. You can make a new link later.", "Zima kiungo cha ${d.name}? Ukurasa wao utaacha kufanya kazi mara moja. Unaweza kutengeneza kiungo kipya baadaye.")
                "rotate" -> t("Make a new link for ${d.name}? The old link stops working, so send them the new one.", "Tengeneza kiungo kipya cha ${d.name}? Kiungo cha zamani kitaacha kufanya kazi, kwa hiyo watumie kipya.")
                else -> t("Delete ${d.name}? Their links stop working straight away.", "Futa ${d.name}? Viungo vyao vitaacha kufanya kazi mara moja.")
            }, when (a) { "revoke" -> t("Revoke", "Zima"); "rotate" -> t("Make a new link", "Tengeneza kiungo kipya"); else -> t("Delete", "Futa") }, a != "rotate", busy, askMsg, { ask = null }, {
                when (a) {
                    "revoke" -> act("donor-revoke") { ask = null; setLink(null); msg = "ok" to t("Link revoked", "Kiungo kimezimwa"); changed() }
                    "rotate" -> act("donor-rotate") { j -> ask = null; setLink(listOf(d.id, d.name, j.optString("link"), d.email)); msg = "ok" to t("New link made ✓", "Kiungo kipya kimetengenezwa ✓"); changed() }
                    else -> act("donor-remove") { hide { changed() } }
                }
            }, "dn_ask")
        }
    }
}

// --- Project › Thank-you for donors: a photo or short video (≤ 60 s) from the community -------------------------
const val THANKS_MAX_SECONDS = 60
fun thanksCheck(mime: String, size: Long, seconds: Int): String? = when {
    !(mime.startsWith("image/") || mime.startsWith("video/")) -> "type"
    mime.startsWith("video/") && seconds > THANKS_MAX_SECONDS + 1 -> "too-long"
    size > (if (mime.startsWith("video/")) 40L else 8L) * 1024 * 1024 -> "too-large"
    else -> null
}

@Composable
fun DonorThanks(m: AppModel, p: Project) {
    val ctx = LocalContext.current
    val scope = rememberScope()
    var open by remember(p.id) { mutableStateOf(false) }
    var picked by remember(p.id) { mutableStateOf<Uri?>(null) }
    var caption by remember(p.id) { mutableStateOf("") }
    var msg by remember(p.id) { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) { picked = u; msg = null } }
    Card(Modifier.padding(top = 12.dp).testTag("dn_thanks"), pad = 14.dp) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(C.badBg), contentAlignment = Alignment.Center) { Icon(Ic.get("camera"), null, tint = C.bad, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t("Thank-you for donors", "Shukrani kwa wafadhili"), style = MaterialTheme.typography.titleSmall, color = C.ink)
                Text(t("A photo or short video (up to 60 seconds) from the community", "Picha au video fupi (hadi sekunde 60) kutoka kwa jamii"), style = MaterialTheme.typography.bodySmall, color = C.muted)
            }
            Icon(Ic.get(if (open) "down" else "chev"), null, tint = C.muted, modifier = Modifier.size(20.dp))
        }
        if (open) {
            Text(t("Ask first: only people who agree to be filmed or photographed. It goes to monday.com and appears on the private pages of this project’s donors.", "Omba ruhusa kwanza: watu waliokubali kupigwa picha au video tu. Inaenda monday.com na kuonekana kwenye kurasa za siri za wafadhili wa mradi huu."),
                style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(t("Photo", "Picha"), { pick.launch("image/*") }, Modifier.weight(1f), small = true, light = true, icon = "camera")
                Btn(t("Video", "Video"), { pick.launch("video/*") }, Modifier.weight(1f), small = true, light = true, icon = "camera")
            }
            picked?.let { Text(t("Chosen: ", "Umechagua: ") + (it.lastPathSegment ?: "file"), style = MaterialTheme.typography.bodySmall, color = C.ink, modifier = Modifier.padding(top = 6.dp)) }
            Field(caption, { caption = it.take(140) }, t("A few words for the donors (optional)", "Maneno machache kwa wafadhili (si lazima)"), placeholder = t("e.g. Asante sana from the water committee!", "mf. Asante sana kutoka kamati ya maji!"))
            Btn(if (busy) t("Sending…", "Inatuma…") else t("Send to donors", "Tuma kwa wafadhili"), {
                val u = picked ?: return@Btn
                busy = true
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        runCatching {
                            val mime = ctx.contentResolver.getType(u) ?: "image/jpeg"
                            val bytes = ctx.contentResolver.openInputStream(u)!!.use { it.readBytes() }
                            val secs = if (mime.startsWith("video/")) runCatching { MediaMetadataRetriever().run { setDataSource(ctx, u); val d = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L; release(); (d / 1000).toInt() } }.getOrDefault(0) else 0
                            Triple(mime, bytes, secs)
                        }.getOrNull()
                    }
                    if (res == null) { busy = false; msg = "bad" to t("Couldn’t read that file.", "Haikuweza kusoma faili hiyo."); return@launch }
                    val (mime, bytes, secs) = res
                    thanksCheck(mime, bytes.size.toLong(), secs)?.let { busy = false; msg = "bad" to when (it) { "too-long" -> t("Please keep it to 60 seconds or less.", "Tafadhali isizidi sekunde 60."); "too-large" -> t("That file is too large.", "Faili hiyo ni kubwa mno."); else -> t("That kind of file can’t be sent.", "Aina hiyo ya faili haiwezi kutumwa.") }; return@launch }
                    val r = m.api.thanks(p.id, bytes, mime, caption.trim(), secs)
                    busy = false
                    if (r.ok) { msg = "ok" to t("Sent ✓ Thank you! The donors will see it on their page.", "Imetumwa ✓ Asante! Wafadhili wataiona kwenye ukurasa wao."); picked = null; caption = "" }
                    else msg = "bad" to (if (r.status == 0) t("This needs a signal. Try again when you’re online.", "Hii inahitaji mtandao. Jaribu tena ukiwa mtandaoni.") else reasonText(r.reason))
                }
            }, Modifier.padding(top = 10.dp).fillMaxWidth().testTag("dn_thanks_send"), icon = "upload", enabled = picked != null && !busy)
            msg?.let { (tone, text) -> Msg(text, tone) }
        }
    }
}

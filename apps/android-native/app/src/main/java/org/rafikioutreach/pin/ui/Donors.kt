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
import androidx.compose.ui.text.font.FontWeight
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

data class DonorViewer(val vid: String, val name: String, val email: String, val primary: Boolean, val lastOpen: String, val active: Boolean)
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
            (0 until vs.length()).map { k -> vs.getJSONObject(k).let { v -> DonorViewer(v.str("vid"), v.str("name"), v.str("email"), v.optBoolean("primary"), v.str("lastOpen"), v.optBoolean("active", true)) } })
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

@OptIn(ExperimentalLayoutApi::class)
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
    // the form (add, or edit when editId is set)
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
    var msg by remember { mutableStateOf<Pair<String, String>?>(null) }
    // the link just made or asked for: who, link, e-mail
    var shown by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var vName by remember { mutableStateOf("") }
    var vEmail by remember { mutableStateOf("") }

    fun reset(d: Donor?) {
        editId = d?.id; name = d?.name ?: ""; email = d?.email ?: ""; org = d?.org ?: ""
        ticked.clear(); d?.projects?.let { ticked.addAll(it) }
        photosOk.clear(); d?.photos?.filterValues { it == "on" }?.keys?.let { photosOk.addAll(it) }
        showPhotos = d?.showPhotos ?: true; digest = d?.digest ?: true; q = ""; only = d != null; msg = null
    }
    fun copy(link: String) { clip.setText(AnnotatedString(link)); msg = "ok" to t("Link copied ✓", "Kiungo kimenakiliwa ✓") }
    fun share(who: String, link: String) {
        runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Your RAFIKI project page").putExtra(Intent.EXTRA_TEXT, donorShareText(who, link)), t("Send the private link", "Tuma kiungo cha siri")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    fun act(d: Donor, action: String, vid: String? = null, after: (JSONObject) -> Unit = {}) {
        scope.launch {
            val body = JSONObject().put("action", action).put("id", d.id).apply { if (vid != null) put("vid", vid) }
            val r = m.api.admin(body)
            if (!r.ok) { msg = "bad" to when (r.reason) { "revoked" -> t("This link was revoked. Make a new one.", "Kiungo hiki kimezimwa. Tengeneza kipya."); "rotate" -> t("Make a new link (Rotate) to copy it.", "Tengeneza kiungo kipya ili ukinakili."); else -> reasonText(r.reason) }; return@launch }
            after(r.json)
        }
    }

    // e-mail digest status
    Card(Modifier.padding(bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.get("link"), null, tint = C.blue, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(8.dp))
            Text(t("Donors", "Wafadhili"), style = MaterialTheme.typography.titleLarge, color = C.ink)
        }
        Text(t("Each donor gets a private link to a page with their projects, an exact-location map, visit reports, repairs, story moments and photos. No password: anyone with the link can see it, so send it only to them.",
            "Kila mfadhili anapata kiungo cha siri cha ukurasa wenye miradi yake, ramani ya mahali kamili, ripoti za ziara, matengenezo, hadithi na picha. Hakuna nenosiri: yeyote mwenye kiungo anaweza kuuona, kwa hiyo mtumie yeye tu."),
            style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.padding(top = 4.dp))
        Text(if (mailReady) t("E-mail digest: on · a monthly update on the 1st", "Barua pepe ya mwezi: imewashwa") else t("E-mail digest: off — add RESEND_API_KEY and ALERT_FROM in Cloudflare", "Barua pepe ya mwezi: imezimwa — ongeza RESEND_API_KEY na ALERT_FROM kwenye Cloudflare"),
            color = if (mailReady) C.ok else C.warn, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (mailReady) C.okBg else C.warnBg).padding(10.dp).testTag("dn_mail"))
    }
    loadMsg?.let { Msg(it, "bad") }
    shown?.let { (who, link, mail) ->
        Card(brush = androidx.compose.ui.graphics.SolidColor(C.okBg), modifier = Modifier.padding(bottom = 10.dp).testTag("dn_link")) {
            Text(t("Private link for ", "Kiungo cha siri cha ") + who, style = MaterialTheme.typography.titleSmall, color = C.ok)
            Text(link, style = MaterialTheme.typography.bodySmall, color = C.ink, modifier = Modifier.padding(vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.card).padding(8.dp))
            Text(t("Anyone with this link can see the page. Send it only to them.", "Yeyote mwenye kiungo hiki anaweza kuona ukurasa. Mtumie yeye tu."), style = MaterialTheme.typography.bodySmall, color = C.text)
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(t("Copy", "Nakili"), { copy(link) }, Modifier.testTag("dn_link_copy"), small = true, light = true, icon = "copy")
                Btn(t("Share (WhatsApp, e-mail…)", "Shiriki (WhatsApp, barua pepe…)"), { share(who, link) }, small = true, light = true, icon = "share")
                Btn(t("Done", "Imekamilika"), { shown = null }, small = true, light = true)
            }
        }
    }
    msg?.let { (tone, text) -> Msg(text, tone) }

    // the list
    val L = (list ?: emptyList()).sortedWith(compareBy<Donor>({ it.revoked }, { it.lastOpen.isBlank() }).thenByDescending { it.lastOpen }.thenBy { it.name })
    if (list != null && L.isEmpty()) Text(t("No donors yet. Add the first one below.", "Bado hakuna wafadhili. Ongeza wa kwanza hapa chini."), color = C.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(4.dp))
    L.forEach { d ->
        Card(Modifier.padding(top = 10.dp).testTag("dn_${d.id}")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.name, style = MaterialTheme.typography.titleMedium, color = C.ink, modifier = Modifier.weight(1f))
                if (d.revoked) Tag(t("Link revoked", "Kiungo kimezimwa"), C.badBg, C.bad) else Tag("${d.projects.size} " + (if (d.projects.size == 1) t("project", "mradi") else t("projects", "miradi")), C.tint, C.deep)
            }
            listOf(d.org.takeIf { it != d.name } ?: "", d.email).filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = C.muted) }
            Text((if (d.lastOpen.isNotBlank()) t("Opened ", "Kimefunguliwa ") + whenText(d.lastOpen) + (if (d.opens > 1) " · ${d.opens}×" else "") else t("Not opened yet", "Bado hakijafunguliwa")) +
                (if (d.viewers.size > 1) t(" · group page, ${d.viewers.size} viewers", " · ukurasa wa kikundi, watu ${d.viewers.size}") else "") + (if (!d.showPhotos) t(" · photos off", " · picha zimezimwa") else ""),
                style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 2.dp))
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (d.revoked) Btn(t("New link", "Kiungo kipya"), { act(d, "donor-rotate") { j -> shown = Triple(d.name, j.optString("link"), d.email); tick++ } }, small = true)
                else {
                    Btn(t("Copy private link", "Nakili kiungo cha siri"), { act(d, "donor-link") { j -> copy(j.optString("link")) } }, Modifier.testTag("dn_copy_${d.id}"), small = true, icon = "link")
                    Btn(t("Share", "Shiriki"), { act(d, "donor-link") { j -> shown = Triple(d.name, j.optString("link"), d.email); share(d.name, j.optString("link")) } }, small = true, light = true, icon = "share")
                }
                Btn(t("Edit", "Hariri"), { reset(d) }, Modifier.testTag("dn_edit_${d.id}"), small = true, light = true)
                if (!d.revoked) {
                    Btn(t("Rotate link", "Badilisha kiungo"), { act(d, "donor-rotate") { j -> shown = Triple(d.name, j.optString("link"), d.email); tick++ } }, small = true, light = true)
                    Btn(t("Revoke", "Zima"), { act(d, "donor-revoke") { msg = "ok" to t("Link revoked", "Kiungo kimezimwa"); tick++ } }, small = true, color = C.bad)
                }
            }
        }
    }

    // add / edit
    val editing = list?.firstOrNull { it.id == editId }
    Card(Modifier.padding(top = 14.dp).testTag("dn_form")) {
        H2(if (editing != null) t("Edit ", "Hariri ") + editing.name else t("Add a donor", "Ongeza mfadhili"))
        Field(name, { name = it }, t("Name", "Jina"), Modifier.testTag("dn_name"), placeholder = t("e.g. Grace Community Church", "mf. Kanisa la Grace"))
        Field(email, { email = it }, t("E-mail", "Barua pepe"), placeholder = t("for the monthly update", "kwa taarifa ya mwezi"))
        Field(org, { org = it }, t("Organisation (optional)", "Shirika (si lazima)"), placeholder = t("e.g. a church or company", "mf. kanisa au kampuni"))
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("Projects they funded", "Miradi waliyofadhili"), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.weight(1f))
            Tag("${ticked.size} " + t("ticked", "zimechaguliwa"), C.tint, C.deep)
        }
        Field(q, { q = it }, t("Search", "Tafuta"), Modifier.testTag("dn_q"), placeholder = t("Name or place", "Jina au mahali"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Picker(t("Country", "Nchi"), ck, listOf("" to t("All countries", "Nchi zote")) + COUNTRIES.toList(), { ck = it }, Modifier.weight(1f))
            Picker(t("Type", "Aina"), cat, listOf("" to t("All types", "Aina zote"), "water" to t("Water", "Maji"), "school" to t("Schools", "Shule"), "health" to t("Health", "Afya"), "children" to t("Children’s homes", "Makao ya watoto"), "other" to t("Other", "Nyingine")), { cat = it }, Modifier.weight(1f))
        }
        Row(Modifier.padding(top = 6.dp).clickable { only = !only }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(only, { only = it }, colors = CheckboxDefaults.colors(checkedColor = C.blue)); Text(t("Ticked only", "Zilizochaguliwa tu"), style = MaterialTheme.typography.bodyMedium, color = C.text)
        }
        val shownP = pickProjects(snap.projects, q, ck, cat, ticked.toSet(), only)
        Column(Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, C.line, RoundedCornerShape(14.dp))) {
            shownP.take(40).forEach { p ->
                val on = p.id in ticked
                Row(Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { if (on) ticked.remove(p.id) else ticked.add(p.id) }.heightIn(min = 52.dp).padding(horizontal = 8.dp, vertical = 4.dp).testTag("dn_p_${p.id}"), verticalAlignment = Alignment.CenterVertically) {
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
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn(if (editing != null) t("Save changes", "Hifadhi mabadiliko") else t("Add and make private link", "Ongeza na utengeneze kiungo cha siri"), {
                scope.launch {
                    val ph = JSONObject(); photosOk.filter { it in ticked }.forEach { ph.put(it, "on") }
                    editing?.photos?.filter { it.value == "off" && it.key in ticked }?.forEach { ph.put(it.key, "off") }
                    val body = JSONObject().put("action", if (editing != null) "donor-update" else "donor-add").put("name", name.trim()).put("email", email.trim()).put("org", org.trim())
                        .put("projects", JSONArray(ticked.toList())).put("showPhotos", showPhotos).put("photos", ph).put("digest", digest)
                    if (editing != null) body.put("id", editing.id)
                    val r = m.api.admin(body)
                    if (!r.ok) { msg = "bad" to reasonText(r.reason); return@launch }
                    if (editing == null) shown = Triple(name.trim(), r.json.optString("link"), email.trim())
                    msg = "ok" to (if (editing != null) t("Saved ✓", "Imehifadhiwa ✓") else t("Added ✓", "Imeongezwa ✓"))
                    reset(null); tick++
                }
            }, Modifier.testTag("dn_save"), icon = if (editing != null) "check" else "plus", enabled = name.isNotBlank())
            if (editing != null) Btn(t("Cancel", "Ghairi"), { reset(null) }, small = true, light = true)
        }
        // group pages: everyone who can open this page, each with their own link
        if (editing != null) {
            Text(t("Who can open this page", "Nani anaweza kufungua ukurasa huu"), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.padding(top = 18.dp))
            Text(t("For a church or company: give each person their own link, so you can switch one off without affecting the others.", "Kwa kanisa au kampuni: mpe kila mtu kiungo chake, ili uweze kuzima kimoja bila kuathiri wengine."), style = MaterialTheme.typography.bodySmall, color = C.muted)
            editing.viewers.forEach { v ->
                Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, C.line, RoundedCornerShape(14.dp)).padding(10.dp).testTag("dn_v_${v.vid}")) {
                    Text(v.name.ifBlank { editing.name } + (if (v.primary) t(" (main)", " (mkuu)") else "") + (if (!v.active) t(" · revoked", " · kimezimwa") else ""), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = C.ink)
                    Text(listOf(v.email, if (v.lastOpen.isNotBlank()) t("opened ", "kimefunguliwa ") + whenText(v.lastOpen) else t("not opened yet", "bado hakijafunguliwa")).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = C.muted)
                    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (v.active) {
                            Btn(t("Copy link", "Nakili kiungo"), { act(editing, "donor-link", v.vid) { j -> copy(j.optString("link")) } }, small = true, light = true)
                            Btn(t("Rotate", "Badilisha"), { act(editing, "donor-rotate", v.vid) { j -> shown = Triple(v.name.ifBlank { editing.name }, j.optString("link"), v.email); tick++ } }, small = true, light = true)
                            Btn(t("Revoke", "Zima"), { act(editing, "donor-revoke", v.vid) { tick++ } }, small = true, color = C.bad)
                        } else Btn(t("New link", "Kiungo kipya"), { act(editing, "donor-rotate", v.vid) { j -> shown = Triple(v.name.ifBlank { editing.name }, j.optString("link"), v.email); tick++ } }, small = true, light = true)
                        if (!v.primary) Btn(t("Remove", "Ondoa"), { act(editing, "donor-viewer-remove", v.vid) { tick++ } }, small = true, light = true)
                    }
                }
            }
            Field(vName, { vName = it }, t("Add a person: name", "Ongeza mtu: jina"), Modifier.testTag("dn_vname"), placeholder = t("e.g. Pastor John", "mf. Mchungaji John"))
            Field(vEmail, { vEmail = it }, t("Their e-mail (optional)", "Barua pepe (si lazima)"))
            Btn(t("Add and make their link", "Ongeza na utengeneze kiungo chake"), {
                scope.launch {
                    val r = m.api.admin(JSONObject().put("action", "donor-viewer-add").put("id", editing.id).put("name", vName.trim()).put("email", vEmail.trim()))
                    if (r.ok) { shown = Triple(vName.trim(), r.json.optString("link"), vEmail.trim()); vName = ""; vEmail = ""; tick++ } else msg = "bad" to reasonText(r.reason)
                }
            }, Modifier.padding(top = 10.dp).testTag("dn_vadd"), small = true, icon = "plus", enabled = vName.isNotBlank())
            Btn(t("Delete this donor", "Futa mfadhili huyu"), { act(editing, "donor-remove") { reset(null); msg = "ok" to t("Deleted", "Imefutwa"); tick++ } }, Modifier.padding(top = 14.dp), small = true, color = C.bad)
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
            }, Modifier.padding(top = 10.dp).testTag("dn_thanks_send"), small = true, icon = "upload", enabled = picked != null && !busy)
            msg?.let { (tone, text) -> Msg(text, tone) }
        }
    }
}

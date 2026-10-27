package org.rafikioutreach.pin.ui

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.clickable

import android.content.Intent
import androidx.compose.ui.platform.testTag
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.BuildConfig
import org.rafikioutreach.pin.Outcome
import org.rafikioutreach.pin.data.COUNTRIES
import org.rafikioutreach.pin.data.OutFile
import org.rafikioutreach.pin.t

private val TYPES = listOf(
    "borehole" to ("Borehole well" to "Kisima kirefu"), "collection" to ("Water collection" to "Ukusanyaji wa maji"), "school" to ("School" to "Shule"),
    "health" to ("Health" to "Afya"), "children" to ("Children's home" to "Makao ya watoto"), "farming" to ("Farming" to "Kilimo"),
    "community" to ("Community" to "Jamii"), "other" to ("Other" to "Nyingine"),
)

/** A project type as monday.com names it ("Borehole well"), in the app's language ("Kisima kirefu"). Unknown types stay as they are. */
fun typeName(type: String): String {
    val ty = type.lowercase()
    val k = when {
        "borehole" in ty -> "borehole"; "collection" in ty -> "collection"; "school" in ty -> "school"; "health" in ty -> "health"
        "orphan" in ty || "children" in ty -> "children"; "farm" in ty -> "farming"; ty == "community" -> "community"; else -> ""
    }
    return TYPES.firstOrNull { it.first == k }?.second?.let { t(type, it.second) } ?: type
}

/**
 * A half-typed New site kept on the phone (files/newsite.json), like a visit draft (VisitDrafts): written on every
 * change, offered again (Resume / Start again) when the screen opens, and removed once the site is sent or saved to send.
 */
object NewSiteDrafts {
    private fun file(ctx: android.content.Context) = java.io.File(ctx.filesDir, "newsite.json")
    fun save(ctx: android.content.Context, json: JSONObject) = runCatching {
        val f = file(ctx); val tmp = java.io.File(f.path + ".tmp")
        tmp.writeText(JSONObject(json.toString()).put("saved", System.currentTimeMillis()).toString()); tmp.renameTo(f)
    }.isSuccess
    fun load(ctx: android.content.Context): JSONObject? = runCatching { JSONObject(file(ctx).readText()) }.getOrNull()
    fun has(ctx: android.content.Context) = file(ctx).exists()
    fun remove(ctx: android.content.Context) { runCatching { file(ctx).delete() } }
    /** Start again: the kept form and its photos go. */
    fun discard(ctx: android.content.Context) {
        load(ctx)?.optJSONArray("shots")?.let { a -> (0 until a.length()).forEach { i -> a.optJSONObject(i)?.optString("path")?.takeIf { it.isNotBlank() }?.let { runCatching { java.io.File(it).delete() } } } }
        remove(ctx)
    }
}

@Composable
fun NewSiteScreen(m: AppModel, done: (String?) -> Unit) {
    val scope = rememberScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var name by remember { mutableStateOf("") }
    var ck by remember { mutableStateOf(m.prefs.country.ifBlank { "ke" }) }
    var type by remember { mutableStateOf("borehole") }
    var village by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var shots by remember { mutableStateOf(listOf<Shot>()) }
    // what's happening here (feat-mea, construction): already built, being built now, or planned; and what it's part of
    var build by remember { mutableStateOf("built") }
    var parent by remember { mutableStateOf("") }
    var partOf by remember { mutableStateOf(false) }
    // a long press on the map ("Add a new site here") starts it at that point
    val at = remember { MapHome.takeNewSite() }
    val spot = remember { Spot().also { s -> if (at != null) s.ll = at } }
    var msg by remember { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val fix by m.fix.collectAsState()
    // a form kept on the phone from before: offer to carry on with it
    var offer by remember { mutableStateOf(NewSiteDrafts.load(ctx)) }
    var kept by remember { mutableStateOf<org.rafikioutreach.pin.data.LatLng?>(null) } // where the kept form's map was
    fun blank() = name.isBlank() && village.isBlank() && note.isBlank() && shots.isEmpty()
    androidx.compose.runtime.LaunchedEffect(offer == null) {
        if (offer != null) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow {
            if (blank()) null else JSONObject().put("v", 1).put("name", name).put("ck", ck).put("type", type).put("village", village).put("note", note).put("build", build).put("parent", parent)
                .apply { spot.ll?.let { put("lat", it.lat).put("lng", it.lng) } }
                .put("shots", org.json.JSONArray().apply { shots.forEach { put(JSONObject().put("path", it.file.path).put("label", it.label)) } })
        }.collect { j ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { if (j != null) NewSiteDrafts.save(ctx, j) else NewSiteDrafts.remove(ctx) }
        }
    }
    offer?.let { o ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(t("New site", "Eneo jipya"), style = MaterialTheme.typography.headlineLarge, color = C.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Card(Modifier.padding(top = 14.dp).testTag("site_resume")) {
                IconTile("clock", C.blue, C.tint, 56.dp)
                val at0 = Dates.timeOrDateTime(java.time.Instant.ofEpochMilli(o.optLong("saved", System.currentTimeMillis())))
                Text(t("Carry on with the new site from $at0?", "Uendelee na eneo jipya ulilolianza $at0?"), style = MaterialTheme.typography.titleLarge, color = C.ink, modifier = Modifier.padding(top = 12.dp))
                Text(o.optString("name").ifBlank { t("No name yet", "Bado halina jina") }, style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.padding(top = 4.dp))
                Btn(t("Resume", "Endelea nalo"), {
                    name = o.optString("name"); ck = o.optString("ck").ifBlank { ck }; type = o.optString("type").ifBlank { type }
                    village = o.optString("village"); note = o.optString("note"); build = o.optString("build").ifBlank { "built" }; parent = o.optString("parent")
                    shots = o.optJSONArray("shots")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Shot(java.io.File(it.optString("path")), it.optString("label")) } } }.orEmpty().filter { it.file.exists() }
                    if (at == null && o.has("lat") && o.has("lng")) org.rafikioutreach.pin.data.LatLng(o.optDouble("lat"), o.optDouble("lng")).let { spot.ll = it; kept = it }
                    offer = null
                }, Modifier.fillMaxWidth().padding(top = 16.dp).testTag("site_resume_yes"), icon = "arrow")
                Btn(t("Start again", "Anza upya"), { NewSiteDrafts.discard(ctx); offer = null }, Modifier.fillMaxWidth().padding(top = 10.dp).testTag("site_resume_no"), icon = "refresh", light = true, outline = true)
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("new_site")) {
        Text(t("New site", "Eneo jipya"), style = MaterialTheme.typography.headlineLarge, color = C.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(t("The office checks every new site first. It isn’t on the website until they approve it.", "Ofisi hukagua kila eneo jipya kwanza. Halionekani kwenye tovuti hadi ofisi iliidhinishe."), style = MaterialTheme.typography.bodyMedium, color = C.muted)
        Card(Modifier.padding(top = 14.dp)) {
            Field(name, { name = it }, t("Site name", "Jina la eneo"), placeholder = t("e.g. Kiptoo Primary School", "mf. Shule ya Msingi Kiptoo"))
            Text(t("Country", "Nchi"), style = MaterialTheme.typography.labelMedium, color = C.ink, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
            COUNTRIES.entries.chunked(2).forEach { row -> Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { (k, n) -> Pill(n, ck == k, Modifier.weight(1f)) { ck = k } } } }
            Text(t("Type", "Aina"), style = MaterialTheme.typography.labelMedium, color = C.ink, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
            TYPES.chunked(2).forEach { row -> Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { (k, l) -> Pill(t(l.first, l.second), type == k, Modifier.weight(1f)) { type = k } } } }
            Text(t("What’s happening here?", "Nini kinaendelea hapa?"), style = MaterialTheme.typography.labelMedium, color = C.ink, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
            // one per line, so no word is ever split (Kiswahili's are long)
            Column(Modifier.padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("built" to t("Already built", "Imeshajengwa"), "building" to t("Being built now", "Inajengwa sasa"), "planned" to t("Planned", "Imepangwa")).forEach { (k, l) ->
                    Box(Modifier.fillMaxWidth().testTag("site_build_$k")) { Pill(l, build == k, Modifier.fillMaxWidth()) { build = k } }
                }
            }
            if (build == "building") Text(t("The app tracks the build stage by stage: foundation, walls, roof…", "Programu itafuatilia ujenzi hatua kwa hatua: msingi, kuta, paa…"), style = MaterialTheme.typography.bodySmall, color = C.muted)
            // part of an existing project (e.g. a dormitory at a school), optional
            val pp = m.snap.collectAsState().value?.projects?.firstOrNull { it.id == parent }
            Text(t("Part of (optional)", "Sehemu ya (si lazima)"), style = MaterialTheme.typography.labelMedium, color = C.ink, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Btn(pp?.title ?: t("Choose a project", "Chagua mradi"), { partOf = true }, Modifier.weight(1f).testTag("site_partof"), icon = "link", light = true, small = true)
                if (pp != null) Btn(t("Remove", "Ondoa"), { parent = "" }, Modifier.padding(start = 8.dp).testTag("site_partof_clear"), light = true, small = true, outline = true)
            }
            if (partOf) PartOfSheet(m, { partOf = false }) { parent = it }
            Field(village, { village = it }, t("Village / community", "Kijiji / jamii"), placeholder = t("e.g. Kiptoo, Nandi", "mf. Kiptoo, Nandi"))
            Field(note, { note = it }, t("Notes (optional)", "Maelezo (si lazima)"), placeholder = t("Who asked, how many people, current water source…", "Nani aliomba, watu wangapi, chanzo cha maji sasa…"), lines = 3)
        }
        Card(Modifier.padding(top = 14.dp)) {
            H2(t("Where is it?", "Iko wapi?"))
            SpotPicker(m, spot, at ?: kept ?: fix?.ll, ck)
        }
        Card(Modifier.padding(top = 14.dp)) {
            H2(t("Photos (optional)", "Picha (si lazima)"))
            PhotoSlots(m, emptyList(), shots, { shots = it }, max = 5)
        }
        Btn(t("Add this site", "Ongeza eneo hili"), {
            val ll = spot.ll ?: return@Btn
            busy = true
            scope.launch {
                val body = JSONObject().put("action", "new").put("name", name.trim()).put("ck", ck).put("village", village.trim()).put("type", type).put("note", note.trim()).put("lat", ll.lat).put("lng", ll.lng)
                    .put("build", build).apply { if (parent.isNotBlank()) put("parent", parent) }
                when (val o = m.submit(body, shots.map { OutFile(it.file.path, "photo", "Site photo") })) {
                    // sent or saved to send: the kept form goes (and the form empties, so it isn't kept again)
                    is Outcome.Sent -> { busy = false; name = ""; village = ""; note = ""; shots = emptyList(); build = "built"; parent = ""; NewSiteDrafts.remove(ctx); m.refresh(); done(o.reply.optString("id").takeIf { it.isNotBlank() }) }
                    Outcome.Queued -> { busy = false; msg = "warn" to t("Saved on this phone. It will be added when you have signal.", "Imehifadhiwa kwenye simu. Itaongezwa ukipata mtandao."); name = ""; village = ""; note = ""; shots = emptyList(); build = "built"; parent = ""; NewSiteDrafts.remove(ctx) }
                    is Outcome.Failed -> { busy = false; msg = "bad" to o.text }
                    Outcome.Locked -> busy = false
                }
            }
        }, Modifier.fillMaxWidth().padding(top = 16.dp), icon = "plus", enabled = name.isNotBlank() && spot.ll != null && !busy)
        if (name.isBlank() || spot.ll == null) Msg(t("Add the name, then choose where it is.", "Weka jina, kisha chagua mahali lilipo."))
        msg?.let { (tone, text) -> Msg(text, tone) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun MeScreen(m: AppModel, open: (String) -> Unit = {}, report: () -> Unit = {}, notes: () -> Unit = {}, settings: (String) -> Unit = {}) {
    val s by m.snap.collectAsState()
    val act by m.activity.collectAsState()
    val mates by m.team.collectAsState()
    var pane by remember { mutableStateOf(m.prefs.mePane) }
    // back on Team: Me again (the switch's first side)
    BackStep(pane == "team") { pane = "me"; m.prefs.mePane = "me" }
    val scroll = rememberScrollState()
    val scope = rememberScope()
    LaunchedEffect(Unit) { launch { m.loadActivity() }; launch { m.loadTeam() } }
    LaunchedEffect(m.focusUpdate) { if (m.focusUpdate && pane != "me") { pane = "me"; m.prefs.mePane = "me" } }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp)) {
        // the title in the middle, Me | Team under it (the bell is in the app header, top right)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(t("Me & team", "Mimi na timu"), style = MaterialTheme.typography.headlineLarge, color = C.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
            SmallSeg(listOf("me" to t("Me", "Mimi"), "team" to t("Team", "Timu")), pane, tag = "pane_") { pane = it; m.prefs.mePane = it; scope.launch { scroll.scrollTo(0) } }
        }
        if (pane == "team") {
            TeamToday(m, mates, act, s, open)
            TeamMonth(m, act)
            TeamFeed(act, s, open)
        } else {
            MeHero(m, act, s, report)
            UpdateCard(m)
            WaitingList(m, s)
            MyWork(s, open)
            MyTrips(m) // field trips: next stop today, Plan a trip (TripsUi.kt)
            MyMonth(m, act)
            BadgeStamps(m)
            SettingsCard(m, settings)
            UpdateRow(m)
        }
    }
}

@Composable
private fun MeRow(k: String, v: String) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Text(k, color = Color(0xFFCFE8FA), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(v, color = Color.White, style = MaterialTheme.typography.titleSmall)
        }
        HorizontalDivider(color = Color(0x26FFFFFF))
    }
}


/** "Part of": search the projects and pick the one this new site belongs to (feat-mea, construction). */
@Composable
private fun PartOfSheet(m: AppModel, close: () -> Unit, pick: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val all = m.snap.collectAsState().value?.projects.orEmpty()
    TeamSubSheet("partof_sheet", close) { hide ->
        Text(t("Part of which project?", "Sehemu ya mradi gani?"), style = MaterialTheme.typography.titleLarge, color = C.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Box(Modifier.testTag("partof_q")) { Field(q, { q = it }, t("Search", "Tafuta"), placeholder = t("e.g. Ndalat", "mf. Ndalat")) }
        val hits = all.filter { q.isBlank() || it.title.contains(q.trim(), true) || it.area.contains(q.trim(), true) }.take(30)
        hits.forEach { p ->
            Row(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.tint).clickable(role = androidx.compose.ui.semantics.Role.Button) { pick(p.id); hide {} }
                .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp).testTag("partof_${p.id}"), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(p.title, style = MaterialTheme.typography.titleSmall, color = C.ink); Text(p.sub, style = MaterialTheme.typography.bodySmall, color = C.muted) }
            }
        }
        if (hits.isEmpty()) Text(t("No project matches.", "Hakuna mradi unaolingana."), style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.padding(top = 10.dp))
    }
}

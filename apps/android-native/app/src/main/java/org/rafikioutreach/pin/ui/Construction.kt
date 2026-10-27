package org.rafikioutreach.pin.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.Api
import org.rafikioutreach.pin.data.Project
import org.rafikioutreach.pin.data.Snapshot
import org.rafikioutreach.pin.data.reasonText
import org.rafikioutreach.pin.t
import java.io.File
import java.time.LocalDate

// feat-mea, me-brief §8: projects being built, stage by stage (S/me-contract.md "Construction projects with stages").
// The project's type never changes (a school stays a school); the Construction card tracks the build.

/** The build stages, in order, with their words. */
object Stages {
    val KEYS = listOf("planned", "foundation", "walls", "roof", "finishing", "handed")
    fun en(k: String) = when (k) { "planned" -> "Planned"; "foundation" -> "Foundation"; "walls" -> "Walls"; "roof" -> "Roof"; "finishing" -> "Finishing"; "handed" -> "Handed over"; else -> k }
    fun label(k: String) = when (k) {
        "planned" -> t("Planned", "Imepangwa"); "foundation" -> t("Foundation", "Msingi"); "walls" -> t("Walls", "Kuta"); "roof" -> t("Roof", "Paa")
        "finishing" -> t("Finishing", "Umaliziaji"); "handed" -> t("Handed over", "Imekabidhiwa"); else -> k
    }
    /** The stage after [k] (the first one when there's none yet); null after Handed over. */
    fun next(k: String?) = if (k.isNullOrBlank()) KEYS.first() else KEYS.getOrNull(KEYS.indexOf(k) + 1)
    fun index(k: String?) = KEYS.indexOf(k ?: "")
}

data class StageStep(val stage: String, val at: String, val by: String, val note: String, val photos: List<String>)
data class StageRecord(val id: String, val stage: String, val at: String, val by: String, val pct: Int?, val due: String, val parent: String, val ptitle: String, val hist: List<StageStep>, val behind: Boolean) {
    /** A stage's gallery: the photos of every change at that stage. */
    fun photos(stage: String) = hist.filter { it.stage == stage }.flatMap { it.photos }
    /** When and by whom a stage was reached (its first entry). */
    fun reached(stage: String) = hist.firstOrNull { it.stage == stage }

    companion object {
        private fun JSONObject.str(k: String) = optString(k).takeIf { it != "null" }.orEmpty()
        private fun JSONArray?.strs() = if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
        fun from(o: JSONObject?): StageRecord? {
            if (o == null || o.str("stage").isBlank()) return null
            val h = o.optJSONArray("hist") ?: JSONArray()
            return StageRecord(o.str("id"), o.str("stage"), o.str("at"), o.str("by"), if (o.has("pct") && !o.isNull("pct")) o.optInt("pct") else null, o.str("due"), o.str("parent"), o.str("ptitle"),
                (0 until h.length()).mapNotNull { h.optJSONObject(it) }.map { StageStep(it.str("stage"), it.str("at"), it.str("by"), it.str("note"), it.optJSONArray("photos").strs()) }, o.optBoolean("behind"))
        }
    }
}
data class LinkedProject(val id: String, val t: String, val s: String)

/** Stage photos come from our server with the sign-in, so they're loaded here (not with [Pics]). */
object StagePics {
    private val mem = android.util.LruCache<String, Bitmap>(40)
    fun cached(path: String): Bitmap? = mem.get(path)
    suspend fun load(api: Api, path: String): Bitmap? = mem.get(path) ?: withContext(Dispatchers.IO) {
        val (code, b) = runCatching { api.fetch(api.server + path, api.authHeaders()) }.getOrDefault(0 to null)
        if (code in 200..299 && b != null) runCatching { BitmapFactory.decodeByteArray(b, 0, b.size) }.getOrNull()?.also { mem.put(path, it) } else null
    }
}

@Composable
private fun AuthPic(m: AppModel, path: String, modifier: Modifier, fit: ContentScale = ContentScale.Crop) {
    val bmp by produceState(StagePics.cached(path), path) { if (value == null) value = StagePics.load(m.api, path) }
    Box(modifier, contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, contentScale = fit, modifier = Modifier.matchParentSize())
        else Icon(Ic.get("camera"), null, tint = C.muted, modifier = Modifier.size(22.dp))
    }
}

/** Big photos, swiping between them; Back or ✕ closes. [pic] draws one. */
@Composable
internal fun SwipeViewer(count: Int, start: Int, caption: (Int) -> String, close: () -> Unit, pic: @Composable (Int, Modifier) -> Unit) {
    Dialog(close, DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(start) { count }
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("build_viewer")) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { i -> pic(i, Modifier.fillMaxSize()) }
            Text(caption(pager.currentPage) + "  ·  ${pager.currentPage + 1}/$count", color = Color.White, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 6.dp).testTag("build_viewer_caption"))
            Box(Modifier.align(Alignment.TopEnd).padding(16.dp).size(44.dp).clip(CircleShape).background(Color(0x66000000)).clickable(role = Role.Button) { close() }
                .semantics { contentDescription = t("Close", "Funga") }.testTag("build_viewer_x"), contentAlignment = Alignment.Center) { Icon(Ic.get("x"), null, tint = Color.White, modifier = Modifier.size(20.dp)) }
        }
    }
}

/** The stage list: done stages ticked with when (and, for the team, who); the current one lit; then what's to come. */
@Composable
internal fun StageStepper(current: String?, reached: (String) -> Pair<String, String>?, photos: (String) -> Int, gallery: @Composable (String) -> Unit) {
    val ci = Stages.index(current)
    Column(Modifier.padding(top = 12.dp).testTag("build_stepper")) {
        Stages.KEYS.forEachIndexed { i, k ->
            val done = i < ci; val now = i == ci
            val hu = C.hue(if (k == "handed") "green" else "amber")
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.testTag("build_step_$k")) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(when { done -> C.ok; now -> hu.bg; else -> C.tint }).then(if (now) Modifier.border(2.5.dp, hu.fg, CircleShape) else Modifier), contentAlignment = Alignment.Center) {
                        if (done) Icon(Ic.get("check"), null, tint = Color.White, modifier = Modifier.size(15.dp))
                        else Text("${i + 1}", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold), color = if (now) hu.fg else C.muted)
                    }
                    if (i < Stages.KEYS.size - 1) Box(Modifier.width(2.dp).height(if (photos(k) > 0 && (done || now)) 92.dp else 22.dp).background(if (done) C.ok else C.line))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(top = 2.dp)) {
                    Text(Stages.label(k), style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (now) FontWeight.ExtraBold else FontWeight.SemiBold), color = if (done || now) C.ink else C.muted)
                    reached(k)?.takeIf { done || now }?.let { (at, by) ->
                        Text(listOf(Dates.day(at), by).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.testTag("build_when_$k"))
                    }
                    if ((done || now) && photos(k) > 0) gallery(k)
                }
            }
        }
    }
}

/** % done and the expected finish, with "Behind schedule" when it's past. */
@Composable
private fun BuildProgress(pct: Int?, due: String, behind: Boolean) {
    if (pct == null && due.isBlank() && !behind) return
    Column(Modifier.padding(top = 10.dp).testTag("build_progress")) {
        if (pct != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(99.dp)).background(C.tint)) {
                    Box(Modifier.fillMaxWidth(pct.coerceIn(0, 100) / 100f).fillMaxHeight().clip(RoundedCornerShape(99.dp)).background(C.hue("amber").fg))
                }
                Text(t("$pct% done", "$pct% imekamilika"), style = MaterialTheme.typography.labelLarge, color = C.ink, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 10.dp).testTag("build_pct"))
            }
        }
        FlowRowLite {
            if (due.isNotBlank()) Text(t("Expected: ", "Inatarajiwa: ") + Dates.day(due), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = C.muted, modifier = Modifier.padding(top = 6.dp, end = 8.dp).testTag("build_due"))
            if (behind) Box(Modifier.padding(top = 4.dp).testTag("build_behind")) { Tag(t("Behind schedule", "Imechelewa"), C.badBg, C.bad) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowLite(content: @Composable () -> Unit) = FlowRow(verticalArrangement = Arrangement.Center) { content() }

/** The Construction card on a project page: for projects being built (or that have stage data). */
@Composable
fun ConstructionCard(m: AppModel, p: Project, snap: Snapshot) {
    val brief = snap.stages[p.id]
    val tracked = brief != null || snap.building(p)
    var tick by remember(p.id) { mutableIntStateOf(0) }
    var rec by remember(p.id) { mutableStateOf<StageRecord?>(null) }
    var parent by remember(p.id) { mutableStateOf<LinkedProject?>(null) }
    var children by remember(p.id) { mutableStateOf<List<LinkedProject>>(emptyList()) }
    LaunchedEffect(p.id, tick) {
        val r = m.api.last(p.id)
        if (r.ok) {
            rec = StageRecord.from(r.json.optJSONObject("stage"))
            val l = r.json.optJSONObject("linked")
            parent = l?.optJSONObject("parent")?.let { LinkedProject(it.optString("id"), it.optString("t"), it.optString("s")) }?.takeIf { it.id.isNotBlank() }
            children = l?.optJSONArray("children")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { LinkedProject(it.optString("id"), it.optString("t"), it.optString("s")) } }.orEmpty()
        }
    }
    // not being built itself: only its builds, if it has any ("Also here: Dormitory construction · Walls")
    if (!tracked && rec == null) {
        if (children.isNotEmpty()) Card(Modifier.padding(top = 14.dp).testTag("build_children")) {
            Row(verticalAlignment = Alignment.CenterVertically) { val hu = C.hue("amber"); IconTile("build", hu.fg, hu.bg, 36.dp); Spacer(Modifier.width(10.dp)); Text(t("Being built here", "Kinachojengwa hapa"), style = MaterialTheme.typography.titleMedium, color = C.ink) }
            children.forEach { c -> LinkRow("build_child_${c.id}", t("Also here: ", "Pia hapa: ") + c.t + (if (c.s.isNotBlank()) " · " + Stages.label(c.s) else "")) { m.goProject = c.id } }
        }
        return
    }
    val cur = rec?.stage ?: brief?.s?.takeIf { it.isNotBlank() }
    val admin = snap.admin
    val next = Stages.next(cur)
    var sheet by remember { mutableStateOf<String?>(null) }   // the stage the sheet starts on
    var meta by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<Pair<String, String>?>(null) }
    var view by remember { mutableStateOf<Pair<String, Int>?>(null) }
    Card(Modifier.padding(top = 14.dp).testTag("build_card")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val hu = C.hue("amber")
            IconTile("build", hu.fg, hu.bg, 44.dp); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t("Construction", "Ujenzi"), style = MaterialTheme.typography.titleMedium, color = C.ink)
                Text(cur?.let { Stages.label(it) + if (it != "handed") t(" · stage ${Stages.index(it) + 1} of 6", " · hatua ${Stages.index(it) + 1} kati ya 6") else "" } ?: t("Not started in the app yet", "Bado haijaanzishwa kwenye programu"),
                    style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.testTag("build_now"))
            }
        }
        BuildProgress(rec?.pct ?: brief?.pct, rec?.due ?: brief?.due.orEmpty(), rec?.behind ?: brief?.behind ?: false)
        StageStepper(cur, { k -> rec?.reached(k)?.let { it.at to it.by } ?: if (k == cur && brief != null) brief.at to brief.by else null }, { k -> rec?.photos(k)?.size ?: 0 }) { k ->
            val ph = rec?.photos(k).orEmpty()
            Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()).testTag("build_photos_$k"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ph.forEachIndexed { i, a ->
                    AuthPic(m, m.api.stagePhotoPath(p.id, a), Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(C.tint).clickable(role = Role.Button) { view = k to i }
                        .semantics { contentDescription = Stages.label(k) + " · " + t("photo ${i + 1}", "picha ${i + 1}") }.testTag("build_photo_${k}_$i"))
                }
            }
        }
        // linked projects: the build is part of a bigger project, or the project has builds going on
        parent?.let { pp -> LinkRow("build_parent", t("Part of: ", "Sehemu ya: ") + pp.t) { m.goProject = pp.id } }
        if (parent == null && rec?.parent?.isNotBlank() == true) LinkRow("build_parent", t("Part of: ", "Sehemu ya: ") + rec!!.ptitle.ifBlank { rec!!.parent }) { m.goProject = rec!!.parent }
        children.forEach { c -> LinkRow("build_child_${c.id}", t("Also here: ", "Pia hapa: ") + c.t + (if (c.s.isNotBlank()) " · " + Stages.label(c.s) else "")) { m.goProject = c.id } }
        said?.let { (tone, text) -> Box(Modifier.testTag("build_msg")) { Msg(text, tone) } }
        // what you can do: the next stage (Handed over is for admins), more photos, % and date
        if (next != null && (next != "handed" || admin)) Btn(if (next == "handed") t("Hand over", "Kabidhi") else t("Move to next stage: ", "Hatua inayofuata: ") + Stages.label(next),
            { sheet = next }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("build_next"), icon = if (next == "handed") "check" else "arrow")
        if (cur != null && cur != "handed") Btn(t("Add progress photos", "Ongeza picha za maendeleo"), { sheet = cur }, Modifier.padding(top = 8.dp).fillMaxWidth().testTag("build_more"), icon = "camera", light = true, small = true)
        if (cur != "handed") Btn(t("% done and expected finish", "% iliyokamilika na tarehe inayotarajiwa"), { meta = true }, Modifier.padding(top = 8.dp).fillMaxWidth().testTag("build_meta"), icon = "cal", light = true, small = true, outline = true)
    }
    sheet?.let { s0 -> StageSheet(m, p, cur, s0, admin, { sheet = null }) { r, msg -> if (r != null) rec = r; said = msg; tick++; m.refresh(quiet = true) } }
    if (meta) MetaSheet(m, p, rec?.pct ?: brief?.pct, rec?.due ?: brief?.due.orEmpty(), { meta = false }) { r -> if (r != null) rec = r; said = "ok" to t("Saved ✓", "Imehifadhiwa ✓"); m.refresh(quiet = true) }
    view?.let { (k, i) ->
        val ph = rec?.photos(k).orEmpty()
        if (ph.isNotEmpty()) SwipeViewer(ph.size, i.coerceIn(0, ph.size - 1), { Stages.label(k) }, { view = null }) { j, mod -> AuthPic(m, m.api.stagePhotoPath(p.id, ph[j], 1600), mod, ContentScale.Fit) }
    }
}

@Composable
private fun LinkRow(tag: String, text: String, onClick: () -> Unit) {
    Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.tint).clickable(role = Role.Button, onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Ic.get("link"), null, tint = C.blue2, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = C.blue2, modifier = Modifier.weight(1f))
        Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(16.dp))
    }
}

/** Why the server said no to a stage change. */
private fun stageWhy(reason: String?) = when (reason) {
    "admin-only" -> t("Only an admin can mark it handed over.", "Msimamizi pekee anaweza kuweka imekabidhiwa.")
    "not-next" -> t("Move one stage at a time. Ask an admin to skip or go back.", "Songa hatua moja kwa wakati. Mwombe msimamizi kuruka au kurudi nyuma.")
    "stage" -> t("That stage isn’t known.", "Hatua hiyo haijulikani.")
    else -> reasonText(reason)
}

/**
 * Move to a stage (or add photos to the current one): at least one photo and a short note. Admins can pick any stage;
 * Handed over asks them to confirm. The photos go to the stage's monday.com note and its gallery.
 */
@Composable
private fun StageSheet(m: AppModel, p: Project, cur: String?, start: String, admin: Boolean, close: () -> Unit, done: (StageRecord?, Pair<String, String>) -> Unit) {
    val scope = rememberScope()
    var stage by remember { mutableStateOf(start) }
    var shots by remember { mutableStateOf(listOf<Shot>()) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var sure by remember { mutableStateOf(false) }
    // field users: the same stage (more photos) or the next one; admins: any
    val choices = if (admin) Stages.KEYS else listOfNotNull(cur, Stages.next(cur)).filter { it != "handed" }
    TeamSubSheet("build_sheet", close) { hide ->
        val hu = C.hue("amber")
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            IconTile("build", hu.fg, hu.bg, 56.dp)
            Text(if (stage == cur) t("Progress at ", "Maendeleo ya ") + Stages.label(stage) else t("Move to ", "Hamia ") + Stages.label(stage), style = MaterialTheme.typography.titleLarge, color = C.ink, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp).testTag("build_sheet_title"))
            Text(p.title, style = MaterialTheme.typography.bodyMedium, color = C.muted, textAlign = TextAlign.Center)
        }
        if (choices.size > 1) {
            Text(t("Stage", "Hatua"), style = MaterialTheme.typography.labelMedium, color = C.ink, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
            choices.chunked(2).forEach { row -> Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { k -> Box(Modifier.weight(1f).testTag("build_pick_$k")) { Pill(Stages.label(k) + if (k == cur) t(" (now)", " (sasa)") else "", stage == k, Modifier.fillMaxWidth()) { stage = k; sure = false } } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            } }
        }
        Text(t("Photos (at least one)", "Picha (angalau moja)"), style = MaterialTheme.typography.labelMedium, color = C.ink, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        Box(Modifier.testTag("build_shots")) { PhotoSlots(m, emptyList(), shots, { shots = it; msg = null }, max = 6) }
        Box(Modifier.testTag("build_note")) { Field(note, { note = it }, t("Note", "Maelezo"), placeholder = t("e.g. Walls up to the windows on 3 sides", "mf. Kuta zimefika madirishani pande 3"), lines = 2) }
        if (stage == "handed" && sure) Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.okBg).padding(12.dp).testTag("build_hand_ask")) {
            Text(t("Hand ${p.title} over? It shows as Completed / Operational in monday.com and on the website.", "Kabidhi ${p.title}? Itaonyesha Imekamilika / Inafanya kazi kwenye monday.com na tovuti."), style = MaterialTheme.typography.bodyMedium, color = C.ink)
        }
        msg?.let { Box(Modifier.padding(top = 8.dp).testTag("build_sheet_msg")) { Msg(it, "bad") } }
        Btn(when { busy -> t("Saving…", "Inahifadhi…"); stage == "handed" && !sure -> t("Hand over…", "Kabidhi…"); stage == "handed" -> t("Yes, hand it over", "Ndiyo, kabidhi"); else -> t("Save", "Hifadhi") }, {
            if (shots.isEmpty()) { msg = t("Add at least one photo of the work.", "Ongeza angalau picha moja ya kazi."); return@Btn }
            if (stage == "handed" && !sure) { sure = true; return@Btn }
            busy = true; msg = null
            scope.launch {
                val r = m.api.post(JSONObject().put("action", "stage").put("id", p.id).put("stage", stage).put("note", note.trim()).put("by", m.prefs.name))
                if (!r.ok) { busy = false; msg = stageWhy(r.reason); return@launch }
                val upd = r.json.optString("updateId")
                var lost = 0
                if (upd.isNotBlank()) for (s in shots) { val x = m.api.stagePhoto(upd, s.file, "Construction: " + Stages.en(stage), stage, p.id); if (!x.ok) lost++ }
                busy = false
                val saved = if (stage == cur) t("Photos added ✓", "Picha zimeongezwa ✓") else t("Now at ${Stages.label(stage)} ✓", "Sasa iko ${Stages.label(stage)} ✓")
                done(StageRecord.from(r.json.optJSONObject("stage")), if (lost > 0) "warn" to saved + " " + t("$lost photo(s) couldn’t be added.", "Picha $lost hazikuweza kuongezwa.") else "ok" to saved)
                hide {}
            }
        }, Modifier.padding(top = 16.dp).fillMaxWidth().testTag("build_save"), icon = "check", enabled = !busy)
    }
}

/** % done (in steps of 5) and the expected finish date; either can be cleared. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MetaSheet(m: AppModel, p: Project, pct0: Int?, due0: String, close: () -> Unit, done: (StageRecord?) -> Unit) {
    val scope = rememberScope()
    var pct by remember { mutableStateOf(pct0) }
    var due by remember { mutableStateOf(Dates.parse(due0)) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var pick by remember { mutableStateOf(false) }
    TeamSubSheet("build_meta_sheet", close) { hide ->
        Text(t("% done and expected finish", "% iliyokamilika na tarehe inayotarajiwa"), style = MaterialTheme.typography.titleLarge, color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(pct?.let { t("$it% done", "$it% imekamilika") } ?: t("% done: not set", "% iliyokamilika: haijawekwa"), style = MaterialTheme.typography.titleMedium, color = C.ink, modifier = Modifier.padding(top = 14.dp).testTag("build_meta_pct"))
        androidx.compose.material3.Slider((pct ?: 0).toFloat(), { pct = (it / 5).toInt() * 5 }, Modifier.fillMaxWidth().semantics { contentDescription = t("% done", "% iliyokamilika") }.testTag("build_meta_slider"), valueRange = 0f..100f, steps = 19)
        Text(t("Expected finish", "Tarehe inayotarajiwa kukamilika"), style = MaterialTheme.typography.labelMedium, color = C.ink, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn(due?.let { Dates.day(it, true) } ?: t("Choose a date", "Chagua tarehe"), { pick = true }, Modifier.weight(1f).testTag("build_meta_date"), icon = "cal", light = true, small = true)
            if (due != null) Btn(t("Clear", "Futa"), { due = null }, Modifier.testTag("build_meta_clear"), light = true, small = true, outline = true)
        }
        if (pick) {
            val st = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = (due ?: LocalDate.now().plusMonths(2)).toEpochDay() * 86_400_000L)
            androidx.compose.material3.DatePickerDialog({ pick = false }, confirmButton = {
                androidx.compose.material3.TextButton({ st.selectedDateMillis?.let { due = LocalDate.ofEpochDay(it / 86_400_000L) }; pick = false }, Modifier.testTag("build_meta_date_ok")) { Text(t("OK", "Sawa")) }
            }, dismissButton = { androidx.compose.material3.TextButton({ pick = false }) { Text(t("Cancel", "Ghairi")) } }) { androidx.compose.material3.DatePicker(st) }
        }
        msg?.let { Msg(it, "bad") }
        Btn(if (busy) t("Saving…", "Inahifadhi…") else t("Save", "Hifadhi"), {
            busy = true
            scope.launch {
                val r = m.api.post(JSONObject().put("action", "stage-meta").put("id", p.id).put("pct", pct ?: JSONObject.NULL).put("due", due?.toString() ?: JSONObject.NULL))
                busy = false
                if (r.ok) { done(StageRecord.from(r.json.optJSONObject("stage"))); hide {} } else msg = stageWhy(r.reason)
            }
        }, Modifier.padding(top = 16.dp).fillMaxWidth().testTag("build_meta_save"), icon = "check", enabled = !busy)
    }
}

// --- donor mode: read-only -------------------------------------------------------------------------------------

/** A donor's view of a build: the stages with dates and photos (no names, no notes). */
data class DonorBuild(val stage: String, val at: String, val pct: Int?, val due: String, val behind: Boolean, val stages: List<Triple<String, String, List<String>>>) {
    companion object {
        fun from(o: JSONObject?): DonorBuild? {
            if (o == null || o.optString("stage").isBlank()) return null
            val a = o.optJSONArray("stages") ?: JSONArray()
            return DonorBuild(o.optString("stage"), o.optString("at"), if (o.has("pct") && !o.isNull("pct")) o.optInt("pct") else null, o.optString("due").takeIf { it != "null" }.orEmpty(), o.optBoolean("behind"),
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { s -> Triple(s.optString("stage"), s.optString("at"), s.optJSONArray("photos")?.let { p -> (0 until p.length()).map { p.optString(it) } }.orEmpty()) })
        }
    }
}

/** Donor mode: how the build is going, read-only. [photo] makes a donor photo URL; [open] opens the photo viewer at one. */
@Composable
fun DonorBuildCard(b: DonorBuild, photo: (String, Int) -> String, open: (List<Pair<String, String>>, Int) -> Unit) {
    Card(Modifier.padding(top = 14.dp).testTag("dbuild_card")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val hu = C.hue("amber"); IconTile("build", hu.fg, hu.bg, 44.dp); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t("How the building is going", "Ujenzi unavyoendelea"), style = MaterialTheme.typography.titleMedium, color = C.ink)
                Text(Stages.label(b.stage), style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.testTag("dbuild_now"))
            }
        }
        BuildProgress(b.pct, b.due, b.behind)
        val all = b.stages.flatMap { (s, _, ph) -> ph.map { photo(it, 1600) to Stages.label(s) } }
        StageStepper(b.stage, { k -> b.stages.firstOrNull { it.first == k }?.let { it.second to "" } }, { k -> b.stages.filter { it.first == k }.sumOf { it.third.size } }) { k ->
            Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                b.stages.filter { it.first == k }.flatMap { it.third }.forEach { a ->
                    NetImage(photo(a, 480), Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(C.tint).clickable(role = Role.Button) { open(all, all.indexOfFirst { it.first == photo(a, 1600) }.coerceAtLeast(0)) }.testTag("dbuild_photo_$a")) {}
                }
            }
        }
    }
}

/** The small hard-hat badge on a building project's map pin (also in the map's Key). */
@Composable
internal fun BuildBadge(size: Dp = 16.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Color.White).padding(1.5.dp).clip(CircleShape).background(Color(PinArt.BUILD_ARGB)), contentAlignment = Alignment.Center) {
        Icon(Ic.get("hat"), null, tint = Color.White, modifier = Modifier.size(size * 0.62f))
    }
}

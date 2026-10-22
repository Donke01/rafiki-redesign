package org.rafikioutreach.pin.ui

import androidx.compose.ui.semantics.contentDescription

import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.text.withStyle

import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.derivedStateOf

import androidx.compose.foundation.shape.CircleShape

import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent

import androidx.compose.ui.focus.focusRequester

import kotlinx.coroutines.launch

import androidx.compose.runtime.rememberCoroutineScope

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.t
import org.rafikioutreach.pin.data.COUNTRIES
import org.rafikioutreach.pin.data.DUE_DAYS
import org.rafikioutreach.pin.data.Geo
import org.rafikioutreach.pin.data.LatLng
import org.rafikioutreach.pin.data.Project
import org.rafikioutreach.pin.data.Snapshot
import java.time.Instant

fun ageDays(s: Snapshot, p: Project): Double {
    val v = s.visits[p.id] ?: return 1e6
    return runCatching { (System.currentTimeMillis() - Instant.parse(v.at).toEpochMilli()) / 864e5 }.getOrDefault(1e6)
}

fun dotColor(s: Snapshot, p: Project): Int = when (s.repairs[p.id]?.state) {
    "notworking" -> 0xFFB3261E.toInt(); "repair" -> 0xFFD98200.toInt(); null -> 0xFF1C7C4A.toInt(); else -> 0xFFD98200.toInt()
}

/** The Map tab: pins by condition, filter chips, search, clusters, the preview card and long press (MapHome.kt). */
@Composable
fun MapScreen(m: AppModel, open: (String) -> Unit, visit: (String) -> Unit = open, offline: () -> Unit = {}, newSite: (LatLng) -> Unit = {}) =
    MapHome(m, open, visit, offline, newSite)

/** "Nearest to you": the closest pinned projects, folded away until you open it. On the Map tab it is told whether it
 *  is open ([expanded], [onExpand]), rests as one slim line while a filter is on ([slim]: "Nearest: … · 21.3 km ⌃")
 *  and, open, grows no taller than [maxFull], so it stays under the controls at the top (MapHome.kt). */
@Composable
fun NearestSheet(m: AppModel, snap: Snapshot, here: LatLng?, modifier: Modifier, open: (String) -> Unit,
                 slim: Boolean = false, expanded: Boolean? = null, onExpand: ((Boolean) -> Unit)? = null, maxFull: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Unspecified,
                 pool: List<Project>? = null, emptyText: String = "") {
    // with a filter on the map, the closest of what the filter leaves ([pool])
    val list = here?.let { h -> (pool ?: snap.projects).filter { it.gps != null }.map { it to Geo.metres(h, it.gps!!) }.sortedBy { it.second }.take(5) } ?: emptyList()
    val none: @Composable ColumnScope.() -> Unit = {
        if (here != null) Text(emptyText.ifBlank { t("No projects near you", "Hakuna miradi karibu nawe") }, style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.padding(6.dp).testTag("nearest_empty"))
        else Row(Modifier.fillMaxWidth().padding(start = 6.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("See the projects closest to you.", "Ona miradi iliyo karibu nawe."), style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            AllowLocationBtn(m, Modifier, "nearest_allow")
        }
    }
    val line: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        val first = list.firstOrNull()
        if (first == null) Text(if (here != null && emptyText.isNotBlank()) emptyText else t("Nearest to you", "Iliyo karibu nawe"), style = MaterialTheme.typography.titleSmall, color = C.ink, maxLines = 2,
            modifier = Modifier.weight(1f).testTag("nearest_name"))
        else Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(t("Nearest:", "Karibu zaidi:"), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold), color = C.ink, maxLines = 1, softWrap = false)
            Spacer(Modifier.width(6.dp))
            // a long name takes two lines (never "…"); the distance always shows
            WordFitText(first.first.title, MaterialTheme.typography.titleSmall.copy(lineHeight = 17.sp), C.ink, 2, Modifier.weight(1f, fill = false), minSp = 11f, tag = "nearest_name")
            Text(" · " + Geo.dist(first.second, m.prefs.miles), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold), color = C.deep, maxLines = 1, softWrap = false)
        }
    }
    // drag it up for the five closest; it rests showing the closest one
    DragSheet(t("Nearest to you", "Iliyo karibu nawe"), modifier,
        peek = { if (list.isEmpty()) none() else list.first().let { (p, _) -> ProjectRow(m, snap, p, here) { open(p.id) } } },
        full = { if (list.isEmpty()) none() else list.forEach { (p, _) -> ProjectRow(m, snap, p, here) { open(p.id) } } },
        open = expanded, onOpen = onExpand, slim = if (slim) line else null, maxFull = maxFull)
}

private class HereKeep { var id: String? = null; var last: Pair<Project, Double>? = null }

/** The "You’re at …" banner. It appears within 200 m and stays until you are 260 m away, so GPS wobble
 *  near the edge doesn’t make it blink; it slides in and out instead of popping. */
@Composable
fun HereSlot(m: AppModel, snap: Snapshot, here: LatLng?, modifier: Modifier, visit: (String) -> Unit, open: (String) -> Unit, chipAfter: Boolean = false, chipNow: Boolean = false, slim: Boolean = false) {
    val keep = remember { HereKeep() }
    val near = here?.let { h -> snap.projects.filter { it.gps != null }.map { it to Geo.metres(h, it.gps!!) }.minByOrNull { it.second } }
    val now = near?.takeIf { (it.second <= 200 || (it.first.id == keep.id && it.second <= 260)) && !m.dismissed.contains(it.first.id) }
    keep.id = now?.first?.id
    if (now != null) keep.last = now
    androidx.compose.animation.AnimatedVisibility(now != null, modifier, enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(), exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()) {
        val (p, d) = now ?: keep.last ?: return@AnimatedVisibility
        // on a small phone's map it folds into a chip after 5 seconds, leaving the map room; a tap opens it again
        var folded by remember(p.id, chipNow) { mutableStateOf(chipNow) }
        if (chipAfter || chipNow) androidx.compose.runtime.LaunchedEffect(p.id, folded) { if (!folded) { kotlinx.coroutines.delay(5000); folded = true } }
        if ((chipAfter || chipNow) && folded) HereChip(p) { folded = false }
        else HereBanner(p, d, m.prefs.miles, Modifier, { visit(p.id) }, { open(p.id) }, slim) { m.dismissed.add(p.id) }
    }
}

fun nearHere(s: Snapshot, here: LatLng?): Pair<Project, Double>? {
    here ?: return null
    return s.projects.filter { it.gps != null }.map { it to Geo.metres(here, it.gps!!) }.minByOrNull { it.second }?.takeIf { it.second <= 200 }
}

@Composable
fun HereBanner(p: Project, d: Double, miles: Boolean, modifier: Modifier, visit: () -> Unit, open: () -> Unit, slim: Boolean = false, dismiss: () -> Unit) {
    // [slim]: over the map (map3) the strip is compact: less padding, the same touch targets
    val vPad = if (slim) 3.dp else 8.dp
    // one slim strip: where you are (tap to open it), Report, and ×. The whole name, never cut short: a name too long
    // for two lines beside Report gets the strip's full width, and Report moves under it
    val nameStyle = MaterialTheme.typography.titleSmall.copy(lineHeight = 18.sp)
    // one line, never cut: without the distance when there is no room for it
    val status: @Composable (Modifier) -> Unit = { mod -> FirstFitText(listOf(t("YOU’RE AT", "UKO") + " · " + Geo.dist(d, miles), t("YOU’RE AT", "UKO")), MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.ExtraBold), Color(0xCCFFFFFF), mod.testTag("here_status")) }
    val report: @Composable () -> Unit = {
        Text(t("Report", "Ripoti"), color = Color(0xFF0E4A2B), style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false,
            modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(Color.White).clickable(role = Role.Button, onClick = visit).heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 10.dp).testTag("here_visit"))
    }
    val x: @Composable () -> Unit = {
        Box(Modifier.size(40.dp).clip(CircleShape).border(1.dp, Color(0x99FFFFFF), CircleShape).clickable(role = Role.Button, onClick = dismiss).semantics { contentDescription = t("Not now", "Si sasa") }.testTag("here_x"), contentAlignment = Alignment.Center) {
            Icon(Ic.get("x"), null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
        val dens = androidx.compose.ui.platform.LocalDensity.current
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val reportW = with(dens) { measurer.measure(t("Report", "Ripoti"), MaterialTheme.typography.labelLarge).size.width + 28.dp.roundToPx() }
        val slimW = with(dens) { (maxWidth - 22.dp - 30.dp - 8.dp - 6.dp - 40.dp).roundToPx() } - reportW
        val fits = rememberWordFitSize(listOf(p.title), nameStyle, slimW, 2, minSp = nameStyle.fontSize.value) >= nameStyle.fontSize.value &&
            !measurer.measure(p.title, nameStyle, maxLines = 2, constraints = androidx.compose.ui.unit.Constraints(maxWidth = slimW.coerceAtLeast(1))).hasVisualOverflow
        val shape = RoundedCornerShape(22.dp)
        if (fits) Row(Modifier.fillMaxWidth().clip(shape).background(OkBrush).padding(start = 14.dp, end = 8.dp, top = vPad, bottom = vPad).testTag("here"), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = open).heightIn(min = 44.dp).testTag("here_open"), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.get("pin"), null, tint = Color.White, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                Column { status(Modifier); Text(p.title, color = Color.White, style = nameStyle, maxLines = 2, modifier = Modifier.testTag("here_name")) }
            }
            Spacer(Modifier.width(8.dp)); report(); Spacer(Modifier.width(6.dp)); x()
        }
        else Column(Modifier.fillMaxWidth().clip(shape).background(OkBrush).padding(start = 14.dp, end = 8.dp, top = vPad, bottom = vPad).testTag("here")) {
            Row(verticalAlignment = Alignment.Top) {
                Row(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = open).heightIn(min = 44.dp).testTag("here_open"), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ic.get("pin"), null, tint = Color.White, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                    WordFitText(p.title, nameStyle, Color.White, 3, Modifier.weight(1f), minSp = 11f, tag = "here_name")
                }
                Spacer(Modifier.width(6.dp)); x()
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) { status(Modifier.weight(1f).padding(start = 30.dp)); report() }
        }
    }
}

/** "You're at" folded: a small green chip; a tap opens the full strip again. */
@Composable
private fun HereChip(p: Project, open: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(99.dp)).background(OkBrush).clickable(role = Role.Button, onClick = open).heightIn(min = 44.dp).padding(start = 12.dp, end = 14.dp)
        .semantics { contentDescription = t("You’re at ", "Uko ") + p.title }.testTag("here_chip"), verticalAlignment = Alignment.CenterVertically) {
        Icon(Ic.get("pin"), null, tint = Color.White, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
        Text(t("You’re at a project", "Uko kwenye mradi"), color = Color.White, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold), maxLines = 1, softWrap = false)
        Spacer(Modifier.width(4.dp))
        Icon(Ic.get("down"), null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(15.dp))
    }
}

@Composable
fun Meter(f0: Float, modifier: Modifier = Modifier, light: Boolean = false, glass: Boolean = false) {
    val f = rememberFill(f0)
    // Sun: a white track with a black edge (a black track looked full)
    Box(modifier.fillMaxWidth().height(if (light || glass) 8.dp else 10.dp).clip(RoundedCornerShape(99.dp)).background(if (glass) Color(0x1F0A78C2) else if (light) Color(0x2EFFFFFF) else if (!C.glassy) Color.White else C.line)
        .then(if (!glass && !light && !C.glassy) Modifier.border(1.5.dp, Color.Black, RoundedCornerShape(99.dp)) else Modifier)) {
        Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).height(if (light || glass) 8.dp else 10.dp).clip(RoundedCornerShape(99.dp))
            .background(if (glass) androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFF0A78C2), Color(0xFF47B4F5))) else if (light) androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFF5DB8F2), Color(0xFFBFE3FB))) else OkBrush))
    }
}

private val FILTERS = listOf(
    Triple("all", "All|Yote", "list"), Triple("missing", "Needs a location|Hakuna mahali", "pinq"), Triple("near", "Nearby|Karibu nawe", "compass"),
    Triple("repairs", "Repairs|Matengenezo", "wrench"), Triple("due", "Due a visit|Inasubiri ziara", "cal"),
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ProjectsScreen(m: AppModel, open: (String) -> Unit, planRoute: () -> Unit = {}, newSite: () -> Unit = {}, visit: (String) -> Unit = open, scan: () -> Unit = {}, search: () -> Unit = {}) {
    val s by m.snap.collectAsState()
    val fix by m.fix.collectAsState()
    val waiting by m.waiting.collectAsState()
    val banner by m.banner.collectAsState()
    val snap = s ?: return
    var f by remember { mutableStateOf(m.prefs.filter) }
    var ck by remember { mutableStateOf(m.prefs.country) }
    var ty by remember { mutableStateOf(m.prefs.ftype) }
    val here = fix?.ll
    val tcount = snap.projects.filter { ck.isBlank() || it.ck == ck }.groupingBy { tgroup(it) }.eachCount()
    if (ty.isNotBlank() && (tcount[ty] ?: 0) == 0) ty = ""

    fun inFilter(p: Project, k: String) = when (k) {
        "missing" -> p.gps == null
        "near" -> p.gps != null
        "repairs" -> snap.repairs.containsKey(p.id)
        "due" -> p.gps != null && (ageDays(snap, p) >= DUE_DAYS || testDue(snap, p))
        else -> true
    }
    // what the Filter pill (country, type) leaves; the chips count within it
    val scoped = snap.projects.filter { p -> (ck.isBlank() || p.ck == ck) && (ty.isBlank() || tgroup(p) == ty) }
    var rows = scoped.filter { inFilter(it, f) }
    if (f == "near" && here != null) rows = rows.sortedBy { Geo.metres(here, it.gps!!) }.take(30)
    if (f == "repairs") rows = rows.sortedBy { mapOf("urgent" to 0, "month" to 1, "watch" to 2)[snap.repairs[it.id]?.urgency] ?: 3 }
    if (f == "due") rows = rows.sortedByDescending { ageDays(snap, it) }
    val count = when (f) {
        "near" -> if (here != null) t("Closest pinned projects to you", "Miradi iliyo karibu nawe zaidi") else t("Finding where you are…", "Inatafuta mahali ulipo…")
        "missing" -> t("${rows.size} still need a location", "${rows.size} bado hazina mahali")
        "repairs" -> t("${rows.size} reported as needing repair or not working, most urgent first", "${rows.size} zimeripotiwa kuhitaji matengenezo au kutofanya kazi, za haraka kwanza")
        "due" -> testCount(snap, rows).let { n -> if (n > 0) t("${rows.size} due a visit · $n ${if (n == 1) "needs" else "need"} a water test", "${rows.size} zinasubiri ziara · $n zinahitaji kipimo cha maji")
            else t("${rows.size} not visited in the last 6 months, longest first", "${rows.size} hazijatembelewa miezi 6 iliyopita, za muda mrefu kwanza") }
        else -> t("${rows.size} projects", "Miradi ${rows.size}")
    }
    val mine = snap.assigned.mapNotNull { a -> snap.projects.firstOrNull { it.id == a.id }?.let { it to t("Visit by ${a.date}", "Tembelea kabla ya ${a.date}") + if (a.note.isNotBlank()) " · ${a.note}" else "" } } +
        snap.jobs.mapNotNull { id -> snap.projects.firstOrNull { it.id == id }?.let { it to t("Repair job", "Kazi ya matengenezo") } }

    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()
    var filterSheet by remember { mutableStateOf(false) }
    val seen = remember { mutableSetOf<Any>() }
    // where the "Projects" heading is in the list, so the top bar can take over the title once it scrolls away
    val headIdx = 2 + (if (banner != null) 1 else 0) + (if (waiting > 0) 1 else 0) + (if (mine.isNotEmpty()) 1 else 0)
    val scrolledPast by remember(headIdx) { derivedStateOf { list.firstVisibleItemIndex > headIdx } }
    ShowInBar(m, t("Projects", "Miradi"), scrolledPast)
    fun pick(k: String) { f = k; m.prefs.filter = k; scope.launch { list.animateScrollToItem(headIdx + 1) } }
    // the home-screen widget's Visit nearby / Pin a location
    val goFilter = m.goFilter
    androidx.compose.runtime.LaunchedEffect(goFilter) { if (goFilter != null) { pick(goFilter); m.goFilter = null } }
    LazyColumn(Modifier.fillMaxSize().testTag("projects"), state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "top") { HomeTop(m, snap, here, search, { pick(it) }, newSite, scan) }
        item(key = "here") { HereSlot(m, snap, here, Modifier, visit, open) }
        if (banner != null) item { Card(border = C.warnBg) { Text(banner!!, color = C.warn, style = MaterialTheme.typography.bodyMedium) } }
        if (waiting > 0) item {
            Card(brush = SolidColor(C.warnBg)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (waiting == 1) t("1 change waiting to send", "Badiliko 1 linasubiri kutumwa") else t("$waiting changes waiting to send", "Mabadiliko $waiting yanasubiri kutumwa"), color = C.warn, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Btn(t("Send now", "Tuma sasa"), { m.refresh() }, small = true, light = true)
                }
            }
        }
        if (mine.isNotEmpty()) item {
            Card(border = Color(0xFF9FD3F7)) {
                Text(t("Your work", "Kazi yako"), style = MaterialTheme.typography.titleLarge, color = C.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
                mine.forEach { (p, why) -> Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(14.dp)).clickable { open(p.id) }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(if (why == t("Repair job", "Kazi ya matengenezo")) "wrench" else "cal", C.blue, C.tint)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text(p.title, style = MaterialTheme.typography.titleSmall, color = C.ink); Text(why, style = MaterialTheme.typography.bodySmall, color = C.muted) }
                } }
            }
        }
        // the head, like Office's tabs: the title, how many are pinned, and three numbers that set the filter
        item(key = "head") {
            val pn = snap.projects.count { it.gps != null }; val all = snap.projects.size
            fun n(k: String) = scoped.count { inFilter(it, k) }
            DqmHead("projects_head", "list", "blue", t("Projects", "Miradi"),
                t("$pn of $all projects pinned · ${all - pn} to go", "Miradi $pn kati ya $all ina mahali · ${all - pn} imebaki"),
                listOf(
                    n("missing").let { DqmNum(it, if (it == 1) t("needs a location", "haina mahali") else t("need a location", "hazina mahali"), if (it > 0) "has" else "off", "missing") },
                    n("repairs").let { DqmNum(it, if (it == 1) t("open repair", "ina hitilafu") else t("open repairs", "zina hitilafu"), if (it > 0) "bad" else "off", "repairs") },
                    n("due").let { DqmNum(it, t("due a visit", "bila ziara"), if (it > 0) "" else "off", "due") },
                ), picked = f, onNum = { pick(it) },
                actions = {
                    Row(Modifier.padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = planRoute).heightIn(min = 44.dp).padding(horizontal = 4.dp).testTag("plan_route"), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Ic.get("map"), null, tint = C.blue, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text(t("Plan a route", "Panga njia"), style = MaterialTheme.typography.labelLarge, color = C.blue)
                    }
                })
        }
        // one row of filters with their counts, that stays at the top while the list scrolls; country and type are under Filter
        stickyHeader(key = "chips") {
            Box(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(C.bg, C.bg, C.bg.copy(alpha = 0f)))).padding(top = 4.dp, bottom = 8.dp)) {
                // the row fades at its right edge ("swipe for more", not cut off) and brings the chosen chip into view
                val rowScroll = rememberScrollState()
                val chipX = remember { mutableMapOf<String, Int>() }
                androidx.compose.runtime.LaunchedEffect(f) { chipX[f]?.let { x -> rowScroll.animateScrollTo((x - 48).coerceAtLeast(0)) } }
                Row(Modifier.fillMaxWidth().graphicsLayer(compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen).drawWithContent {
                        drawContent()
                        if (rowScroll.value < rowScroll.maxValue) drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(0f to Color.Black, 1f - 28.dp.toPx() / size.width to Color.Black, 1f to Color.Transparent), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                    }.horizontalScroll(rowScroll).padding(end = 20.dp).testTag("chips"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProjFilterPill(listOfNotNull(COUNTRIES[ck], TGROUPS.firstOrNull { it.first == ty }?.let { t(it.second, it.third) })) { filterSheet = true }
                    FILTERS.forEach { (k, label, icon) ->
                        Box(Modifier.onGloballyPositioned { chipX[k] = it.positionInParent().x.toInt() }) {
                            ProjChip(label.split("|").let { t(it[0], it[1]) }, icon, scoped.count { inFilter(it, k) }, f == k, "chip_$k") { f = k; m.prefs.filter = k }
                        }
                    }
                }
            }
        }
        item(key = "count") {
            Text(count, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = C.muted, modifier = Modifier.padding(start = 4.dp).testTag("proj_count"))
        }
                // "Pinned" only says something when some rows aren't
        val allPinned = rows.all { it.gps != null }
        itemsIndexed(rows, key = { _, it -> it.id }) { i, p -> Box(Modifier.rise(i, seen, p.id)) { ProjectRow(m, snap, p, here, showPinned = !allPinned) { open(p.id) } } }
        if (rows.isEmpty()) item {
            EmptyDrop(when {
                f == "missing" && ck.isBlank() -> t("Every project has a location.", "Kila mradi una mahali.")
                f == "repairs" && ck.isBlank() -> t("No open repairs.", "Hakuna matengenezo yaliyo wazi.")
                f == "due" -> t("Everything has been visited in the last 6 months.", "Kila kitu kimetembelewa katika miezi 6 iliyopita.")
                else -> t("No projects match.", "Hakuna mradi unaolingana.")
            })
        }
    }
    if (filterSheet) ProjFilterSheet(snap, ck, ty, rows.size, { ck = it; m.prefs.country = it }, { ty = it; m.prefs.ftype = it }) { filterSheet = false }
}

/** Kinds of project for the type filter. */
val TGROUPS = listOf(Triple("water", "Water", "Maji"), Triple("school", "Schools", "Shule"), Triple("health", "Health", "Afya"), Triple("home", "Children’s homes", "Makao ya watoto"), Triple("farm", "Farming", "Kilimo"), Triple("other", "Other", "Nyingine"))
fun tgroup(p: Project): String { val t = p.type.lowercase(); return when { "borehole" in t || "collection" in t -> "water"; "school" in t -> "school"; "health" in t -> "health"; "orphan" in t -> "home"; "farm" in t -> "farm"; else -> "other" } }

/** A filter in the Projects row, with its count: blue when chosen, as on Office's tabs. Never wraps. */
@Composable
private fun ProjChip(label: String, icon: String, n: Int, on: Boolean, tag: String, onClick: () -> Unit) {
    val h = rememberHaptics()
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier.clip(shape).background(if (on) C.blue else C.card).border(1.dp, if (on) C.blue else C.line, shape).clickable(role = Role.Tab) { h.tap(); onClick() }
            .heightIn(min = 44.dp).padding(start = 12.dp, end = 12.dp).testTag(tag).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Ic.get(icon), null, tint = if (on) Color.White else C.blue, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp), color = if (on) Color.White else C.ink, maxLines = 1, softWrap = false)
        Spacer(Modifier.width(6.dp))
        Text("$n", style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold), color = if (on) C.blue else C.muted, maxLines = 1, softWrap = false,
            modifier = Modifier.clip(shape).background(if (on) Color.White else C.bg).padding(horizontal = 7.dp, vertical = 1.dp))
    }
}

/** Country and type in one pill: "Filter", or what is chosen ("Kenya · Water"), blue while anything is chosen. */
@Composable
private fun ProjFilterPill(chosen: List<String>, onClick: () -> Unit) {
    val h = rememberHaptics()
    val on = chosen.isNotEmpty()
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier.clip(shape).background(if (on) C.blue else C.card).border(1.dp, if (on) C.blue else C.line, shape).clickable(role = Role.Button) { h.tap(); onClick() }
            .heightIn(min = 44.dp).padding(start = 12.dp, end = 10.dp).testTag("proj_filter")
            .semantics { contentDescription = t("Filter by country and type", "Chuja kwa nchi na aina") + if (on) ": " + chosen.joinToString(", ") else "" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Ic.get("filter"), null, tint = if (on) Color.White else C.blue, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(6.dp))
        Text(if (on) chosen.joinToString(" · ") else t("Filter", "Chuja"), style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp), color = if (on) Color.White else C.ink, maxLines = 1, softWrap = false)
        Spacer(Modifier.width(4.dp))
        Icon(Ic.get("down"), null, tint = if (on) Color.White else C.muted, modifier = Modifier.size(15.dp))
    }
}

/** The Filter sheet: country and type of project, each with how many there are; a close X and "Show N projects". */
@Composable
private fun ProjFilterSheet(snap: Snapshot, ck: String, ty: String, shown: Int, onCountry: (String) -> Unit, onType: (String) -> Unit, close: () -> Unit) {
    val h = rememberHaptics()
    DqmSheet("proj_filter_sheet", close) { hide ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("Filter", "Chuja"), style = MaterialTheme.typography.titleLarge, color = C.ink, modifier = Modifier.weight(1f))
            SheetCloseX("proj_filter_x") { hide {} }
        }
        @Composable
        fun opt(k: String, label: String, n: Int, on: Boolean, tag: String, pick: () -> Unit) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(14.dp)).background(if (on) C.tint else Color.Transparent)
                    .clickable(role = Role.RadioButton) { h.tap(); pick() }.heightIn(min = 48.dp).padding(horizontal = 12.dp).testTag(tag),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.weight(1f))
                Text("$n", style = MaterialTheme.typography.labelLarge, color = C.muted)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { if (on) Icon(Ic.get("check"), null, tint = C.blue, modifier = Modifier.size(22.dp)) }
            }
        }
        DqmLabel(t("Country", "Nchi"))
        opt("", t("All countries", "Nchi zote"), snap.projects.size, ck.isBlank(), "pick_") { onCountry("") }
        COUNTRIES.forEach { (k, n) -> val c = snap.projects.count { it.ck == k }; if (c > 0 || ck == k) opt(k, n, c, ck == k, "pick_$k") { onCountry(k) } }
        val tcount = snap.projects.filter { ck.isBlank() || it.ck == ck }.groupingBy { tgroup(it) }.eachCount()
        if (tcount.size > 1) {
            DqmLabel(t("Type of project", "Aina ya mradi"))
            opt("", t("All types", "Aina zote"), tcount.values.sum(), ty.isBlank(), "type_") { onType("") }
            TGROUPS.filter { (tcount[it.first] ?: 0) > 0 }.forEach { (k, en, sw) -> opt(k, t(en, sw), tcount[k] ?: 0, ty == k, "type_$k") { onType(k) } }
        }
        Btn(if (shown == 1) t("Show 1 project", "Onyesha mradi 1") else t("Show $shown projects", "Onyesha miradi $shown"), { hide {} }, Modifier.fillMaxWidth().padding(top = 14.dp).testTag("proj_filter_done"))
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ProjectRow(m: AppModel, s: Snapshot, p: Project, here: LatLng?, showPinned: Boolean = true, onClick: () -> Unit) {
    val rep = s.repairs[p.id]
    // the icon box carries the state colour; the tag sits under the name so names aren't cut short
    val (boxBg, boxFg) = when {
        rep?.state == "notworking" -> C.badBg to C.bad
        rep != null -> C.warnBg to C.warn
        p.gps == null -> C.tint to C.blue
        else -> C.okBg to C.ok
    }
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        Modifier.press(src).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.card).border(1.dp, C.line, RoundedCornerShape(20.dp))
            .clickable(interactionSource = src, indication = androidx.compose.material3.ripple(), onClick = onClick).heightIn(min = 76.dp).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NetImage(p.photo?.let { m.api.photoUrl(it) }, Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(boxBg)) {
            Icon(Ic.get(typeLook(p).first), null, tint = boxFg, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            // the whole name, on two lines if needed; a long place line drops the status before anything is cut
            Text(p.title, style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp), color = C.text, maxLines = 3, modifier = Modifier.testTag("row_title"))
            FirstFitText(listOf(p.sub, listOf(p.area, p.country).filter { it.isNotBlank() }.joinToString(" · "), p.area.ifBlank { p.country }).distinct(), MaterialTheme.typography.bodySmall, C.muted)
            // how far first, then how it is: both show (they wrap to a second line rather than squeeze)
            val dist = if (here != null && p.gps != null) Geo.dist(Geo.metres(here, p.gps), m.prefs.miles) else null
            val due = testDue(s, p)
            if (dist != null || rep != null || p.gps == null || showPinned || due) androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 5.dp).testTag("row_tags"), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (dist != null) Tag(dist, C.tint, C.deep)
                if (rep != null) Tag(if (rep.state == "notworking") t("Not working", "Haifanyi kazi") else t("Needs repair", "Inahitaji matengenezo"), if (rep.state == "notworking") C.badBg else C.warnBg, if (rep.state == "notworking") C.bad else C.warn)
                if (p.gps == null) Tag(t("No location", "Hakuna mahali"), C.warnBg, C.warn)
                else if (dist == null && rep == null && showPinned) Tag(t("Pinned", "Ina mahali"), C.okBg, C.ok)
                if (due) WaterTestTag()
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(20.dp))
    }
}

/** Home: a greeting, where you are, a big question and four quick actions. */
@Composable
private fun HomeTop(m: AppModel, s: Snapshot, here: LatLng?, search: () -> Unit, filter: (String) -> Unit, newSite: () -> Unit, scan: () -> Unit = {}) {
    val first = m.prefs.name.substringBefore(' ')
    val where = here?.let { h -> s.projects.filter { it.gps != null }.minByOrNull { Geo.metres(h, it.gps!!) }?.takeIf { Geo.metres(h, it.gps!!) < 60000 && it.area.isNotBlank() }?.let { it.area + ", " + it.country } }
        ?: COUNTRIES[m.prefs.country]
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Habari, " + first.ifBlank { "karibu" }, style = MaterialTheme.typography.bodyMedium, color = C.muted)
        Row(Modifier.padding(top = 2.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
            Text(androidx.compose.ui.text.buildAnnotatedString {
                withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Normal)) { append(t("Which project", "Mradi gani") + "\n") }
                append(t("today?", "leo?"))
            }, style = MaterialTheme.typography.headlineLarge.copy(fontSize = 27.sp, lineHeight = 31.sp, letterSpacing = (-0.8).sp), color = C.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f))
        }
        // looks like a search box; takes you to the search field above the list
        Row(Modifier.padding(bottom = 10.dp).fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(18.dp)).background(C.card).border(1.dp, C.line, RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = search).padding(horizontal = 16.dp).testTag("home_search").semantics { contentDescription = t("Search projects", "Tafuta miradi") }, verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.get("search"), null, tint = C.muted, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
            Text(t("Search a project or place", "Tafuta mradi au mahali"), style = MaterialTheme.typography.bodyLarge, color = C.muted, maxLines = 1)
        }
        // the five actions in one row, so the whole home fits on one screen. Labels wrap only between words and all
        // shrink together until the longest word fits, so no word is ever split ("Matengenezo" on a 360 dp phone)
        val acts = listOf(Triple("missing", t("Pin a location", "Weka mahali"), "pin"), Triple("near", t("Visit nearby", "Tembelea karibu"), "clip"),
            Triple("new", t("New site", "Eneo jipya"), "plus"), Triple("repairs", t("Repairs", "Rekebisha"), "wrench"), Triple("scan", t("Scan QR", "Soma QR"), "scan"))
        val labelStyle = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 14.sp)
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val tileText = with(androidx.compose.ui.platform.LocalDensity.current) { ((maxWidth - 6.dp * 4) / 5 - 4.dp).roundToPx() }
        val fit = rememberWordFitSize(acts.map { it.second }, labelStyle, tileText, 2)
        Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            acts.forEachIndexed { ci, (k, l, ic) ->
                val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Column(Modifier.weight(1f).rise(ci + 1, step = 60).press(src).heightIn(min = 84.dp).clip(RoundedCornerShape(18.dp)).background(if (C.glassy) C.glass2 else C.card).border(1.dp, if (C.glassy) C.glassEdge else C.line, RoundedCornerShape(18.dp))
                    .clickable(interactionSource = src, indication = androidx.compose.material3.ripple(), role = Role.Button) { if (k == "new") newSite() else if (k == "scan") scan() else filter(k) }.padding(horizontal = 2.dp, vertical = 10.dp).testTag("quick_$k"),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    val hu = C.hue(k)
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)).background(hu.bg), contentAlignment = Alignment.Center) { Icon(Ic.get(ic), null, tint = hu.fg, modifier = Modifier.size(20.dp)) }
                    Text(l, style = labelStyle.copy(fontSize = fit.sp, lineHeight = (fit * 14f / 12f).sp), color = C.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2,
                        modifier = Modifier.padding(top = 6.dp).testTag("quick_label_$k"))
                }
            }
        }
        }
    }
}

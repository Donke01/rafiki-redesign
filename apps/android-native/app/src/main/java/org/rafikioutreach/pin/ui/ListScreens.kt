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

@Composable
fun MapScreen(m: AppModel, open: (String) -> Unit, visit: (String) -> Unit = open) {
    val s by m.snap.collectAsState()
    val fix by m.fix.collectAsState()
    val snap = s ?: return
    val pinned = snap.projects.count { it.gps != null }
    var sheetH by remember { mutableIntStateOf(0) }
    val dens = androidx.compose.ui.platform.LocalDensity.current
    Box(Modifier.fillMaxSize()) {
        ProjectsMap(
            snap.projects.filter { it.gps != null }.map { Dot(it.id, it.gps!!, dotColor(snap, it), snap.repairs.containsKey(it.id), it.title) },
            fix?.ll, open, Modifier.fillMaxSize(), fitKey = snap.projects.size, badgeBottom = with(dens) { sheetH.toDp() } + 12.dp,
        )
        Column(Modifier.padding(12.dp).padding(end = 58.dp).align(Alignment.TopStart)) {
        Card(pad = 12.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("$pinned of ${snap.projects.size} projects pinned", "Miradi $pinned kati ya ${snap.projects.size} ina mahali"), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.weight(1f))
                Text(t("${snap.projects.size - pinned} to go", "${snap.projects.size - pinned} zimebaki"), style = MaterialTheme.typography.bodySmall, color = C.muted)
            }
            Meter(pinned.toFloat() / maxOf(1, snap.projects.size), Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Legend(Color(0xFF1C7C4A), t("Pinned", "Ina mahali")); Legend(Color(0xFFD98200), t("Needs repair", "Inahitaji matengenezo")); Legend(Color(0xFFB3261E), t("Not working", "Haifanyi kazi"))
            }
        }
        HereSlot(m, snap, fix?.ll, Modifier.padding(top = 10.dp), visit, open)
        }
        NearestSheet(m, snap, fix?.ll, Modifier.align(Alignment.BottomCenter).padding(12.dp).onSizeChanged { sheetH = it.height }, open)
    }
}

/** "Nearest to you": the three closest pinned projects, folded away until you open it. */
@Composable
private fun NearestSheet(m: AppModel, snap: Snapshot, here: LatLng?, modifier: Modifier, open: (String) -> Unit) {
    val list = here?.let { h -> snap.projects.filter { it.gps != null }.map { it to Geo.metres(h, it.gps!!) }.sortedBy { it.second }.take(5) } ?: emptyList()
    val none: @Composable ColumnScope.() -> Unit = { Text(t("Allow location to see the projects closest to you.", "Ruhusu mahali ili uone miradi iliyo karibu nawe."), style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.padding(6.dp)) }
    // drag it up for the five closest; it rests showing the closest one
    DragSheet(t("Nearest to you", "Iliyo karibu nawe"), modifier,
        peek = { if (list.isEmpty()) none() else list.first().let { (p, _) -> ProjectRow(m, snap, p, here) { open(p.id) } } },
        full = { if (list.isEmpty()) none() else list.forEach { (p, _) -> ProjectRow(m, snap, p, here) { open(p.id) } } })
}

private class HereKeep { var id: String? = null; var last: Pair<Project, Double>? = null }

/** The "You’re at …" banner. It appears within 200 m and stays until you are 260 m away, so GPS wobble
 *  near the edge doesn’t make it blink; it slides in and out instead of popping. */
@Composable
fun HereSlot(m: AppModel, snap: Snapshot, here: LatLng?, modifier: Modifier, visit: (String) -> Unit, open: (String) -> Unit) {
    val keep = remember { HereKeep() }
    val near = here?.let { h -> snap.projects.filter { it.gps != null }.map { it to Geo.metres(h, it.gps!!) }.minByOrNull { it.second } }
    val now = near?.takeIf { (it.second <= 200 || (it.first.id == keep.id && it.second <= 260)) && !m.dismissed.contains(it.first.id) }
    keep.id = now?.first?.id
    if (now != null) keep.last = now
    androidx.compose.animation.AnimatedVisibility(now != null, modifier, enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(), exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()) {
        val (p, d) = now ?: keep.last ?: return@AnimatedVisibility
        HereBanner(p, d, m.prefs.miles, Modifier, { visit(p.id) }, { open(p.id) }) { m.dismissed.add(p.id) }
    }
}

fun nearHere(s: Snapshot, here: LatLng?): Pair<Project, Double>? {
    here ?: return null
    return s.projects.filter { it.gps != null }.map { it to Geo.metres(here, it.gps!!) }.minByOrNull { it.second }?.takeIf { it.second <= 200 }
}

@Composable
fun HereBanner(p: Project, d: Double, miles: Boolean, modifier: Modifier, visit: () -> Unit, open: () -> Unit, dismiss: () -> Unit) {
    // one slim strip: where you are (tap to open it), Report, and ×
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(OkBrush).padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp).testTag("here"), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = open).heightIn(min = 44.dp).testTag("here_open"), verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.get("pin"), null, tint = Color.White, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
            Column {
                Text(t("YOU’RE AT", "UKO") + " · " + Geo.dist(d, miles), color = Color(0xCCFFFFFF), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.ExtraBold), maxLines = 1)
                Text(p.title, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(t("Report", "Ripoti"), color = Color(0xFF0E4A2B), style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(Color.White).clickable(role = Role.Button, onClick = visit).heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 10.dp).testTag("here_visit"))
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(40.dp).clip(CircleShape).border(1.dp, Color(0x99FFFFFF), CircleShape).clickable(role = Role.Button, onClick = dismiss).semantics { contentDescription = t("Not now", "Si sasa") }.testTag("here_x"), contentAlignment = Alignment.Center) {
            Icon(Ic.get("x"), null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun Legend(c: Color, t: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(c)); Spacer(Modifier.width(5.dp))
        Text(t, style = MaterialTheme.typography.labelSmall, color = C.muted)
    }
}

@Composable
fun Meter(f0: Float, modifier: Modifier = Modifier, light: Boolean = false, glass: Boolean = false) {
    val f = rememberFill(f0)
    Box(modifier.fillMaxWidth().height(if (light || glass) 8.dp else 10.dp).clip(RoundedCornerShape(99.dp)).background(if (glass) Color(0x1F0A78C2) else if (light) Color(0x2EFFFFFF) else C.line)) {
        Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).height(if (light || glass) 8.dp else 10.dp).clip(RoundedCornerShape(99.dp))
            .background(if (glass) androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFF0A78C2), Color(0xFF47B4F5))) else if (light) androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFF5DB8F2), Color(0xFFBFE3FB))) else OkBrush))
    }
}

private val FILTERS = listOf(
    Triple("missing", "Needs a location|Hakuna mahali", "pinq"), Triple("near", "Nearby|Karibu nawe", "compass"), Triple("repairs", "Repairs|Ukarabati", "wrench"),
    Triple("due", "Due a visit|Inasubiri ziara", "cal"), Triple("all", "All|Yote", "list"),
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ProjectsScreen(m: AppModel, open: (String) -> Unit, planRoute: () -> Unit = {}, newSite: () -> Unit = {}, visit: (String) -> Unit = open, search: () -> Unit = {}) {
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

    var rows = snap.projects.filter { p ->
        when (f) {
            "missing" -> p.gps == null
            "near" -> p.gps != null
            "repairs" -> snap.repairs.containsKey(p.id)
            "due" -> p.gps != null && ageDays(snap, p) >= DUE_DAYS
            else -> true
        } && (ck.isBlank() || p.ck == ck) && (ty.isBlank() || tgroup(p) == ty)
    }
    if (f == "near" && here != null) rows = rows.sortedBy { Geo.metres(here, it.gps!!) }.take(30)
    if (f == "repairs") rows = rows.sortedBy { mapOf("urgent" to 0, "month" to 1, "watch" to 2)[snap.repairs[it.id]?.urgency] ?: 3 }
    if (f == "due") rows = rows.sortedByDescending { ageDays(snap, it) }
    val count = when (f) {
        "near" -> if (here != null) t("Closest pinned projects to you", "Miradi iliyo karibu nawe zaidi") else t("Finding where you are…", "Inatafuta mahali ulipo…")
        "missing" -> t("${rows.size} still need a location", "${rows.size} bado hazina mahali")
        "repairs" -> t("${rows.size} reported as needing repair or not working, most urgent first", "${rows.size} zimeripotiwa kuhitaji matengenezo au kutofanya kazi, za haraka kwanza")
        "due" -> t("${rows.size} not visited in the last 6 months, longest first", "${rows.size} hazijatembelewa miezi 6 iliyopita, za muda mrefu kwanza")
        else -> t("${rows.size} projects", "Miradi ${rows.size}")
    }
    val mine = snap.assigned.mapNotNull { a -> snap.projects.firstOrNull { it.id == a.id }?.let { it to t("Visit by ${a.date}", "Tembelea kabla ya ${a.date}") + if (a.note.isNotBlank()) " · ${a.note}" else "" } } +
        snap.jobs.mapNotNull { id -> snap.projects.firstOrNull { it.id == id }?.let { it to t("Repair job", "Kazi ya matengenezo") } }

    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()
    var countrySheet by remember { mutableStateOf(false) }
    val seen = remember { mutableSetOf<Any>() }
    // where the "Projects" heading is in the list, so the top bar can take over the title once it scrolls away
    val headIdx = 2 + (if (banner != null) 1 else 0) + (if (waiting > 0) 1 else 0) + (if (mine.isNotEmpty()) 1 else 0)
    val scrolledPast by remember(headIdx) { derivedStateOf { list.firstVisibleItemIndex > headIdx } }
    ShowInBar(m, t("Projects", "Miradi"), scrolledPast)
    fun pick(k: String) { f = k; m.prefs.filter = k; scope.launch { list.animateScrollToItem(headIdx + 1) } }
    LazyColumn(Modifier.fillMaxSize().testTag("projects"), state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "top") { HomeTop(m, snap, here, search, { pick(it) }, newSite) }
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
        item(key = "head") {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(t("Projects", "Miradi"), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold), color = C.ink)
                Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = planRoute).heightIn(min = 44.dp).padding(horizontal = 6.dp).testTag("plan_route"), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ic.get("map"), null, tint = C.blue, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(t("Plan a route", "Panga njia"), style = MaterialTheme.typography.labelLarge, color = C.blue)
                }
            }
        }
        item {
            val pinned = snap.projects.count { it.gps != null }
            // light colours: glass, like the web app; dark and bright sun: deep blue
            val glass = C.inky
            Card(brush = if (glass) null else DeepBrush, pad = 14.dp) {
                val n = kotlin.math.round(rememberFill(pinned.toFloat())).toInt() // counts up
                Row { Text(t("$n of ${snap.projects.size} projects pinned", "Miradi $n kati ya ${snap.projects.size} ina mahali"), color = if (glass) C.ink else Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f)); Text(t("${snap.projects.size - pinned} to go", "${snap.projects.size - pinned} zimebaki"), color = if (glass) C.muted else Color(0xFFCFE8FA), style = MaterialTheme.typography.bodySmall) }
                Meter(pinned.toFloat() / maxOf(1, snap.projects.size), Modifier.padding(top = 10.dp), light = !glass, glass = glass)
                // one meter per country, only when there is more than one country to compare
                if (COUNTRIES.keys.count { k -> snap.projects.any { it.ck == k } } > 1) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    COUNTRIES.forEach { (k, n) ->
                        val l = snap.projects.filter { it.ck == k }
                        if (l.isNotEmpty()) Column(Modifier.weight(1f)) {
                            val pn = l.count { it.gps != null }
                            Text("$n $pn/${l.size}", color = if (glass) C.muted else Color(0xFFCFE8FA), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            Meter(pn.toFloat() / l.size, Modifier.padding(top = 4.dp), light = !glass, glass = glass)
                        }
                    }
                }
            }
        }
        // filters: a row of chips you can swipe, that stays at the top while the list scrolls
        stickyHeader(key = "chips") {
            Box(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(C.bg, C.bg, C.bg.copy(alpha = 0f)))).padding(top = 4.dp, bottom = 10.dp)) {
                // all five filters across the width, no sideways scrolling
                Row(Modifier.fillMaxWidth().testTag("chips"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FILTERS.sortedBy { if (it.first == "all") 0 else 1 }.forEach { (k, label, icon) ->
                        FilterTile(label.split("|").let { t(it[0], it[1]) }, icon, f == k, { f = k; m.prefs.filter = k }, Modifier.weight(1f), badge = if (k == "repairs") snap.repairs.size else 0, tag = "chip_$k", hue = k)
                    }
                }
            }
        }
        // the country sits with the count: tap it to choose another
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Chip(COUNTRIES[ck] ?: t("All countries", "Nchi zote"), "map", false, { countrySheet = true }, trailing = "down", tag = "chip_country", hue = "country")
                Spacer(Modifier.width(10.dp))
                Text(count, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = C.muted, modifier = Modifier.weight(1f), maxLines = 2)
            }
        }
        // type of project: only the kinds there are
        if (tcount.size > 1) item(key = "types") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("type_chips"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TypeChip(t("All types", "Aina zote"), null, ty.isBlank(), "type_") { ty = ""; m.prefs.ftype = "" }
                TGROUPS.filter { (tcount[it.first] ?: 0) > 0 }.forEach { (k, en, sw) -> TypeChip(t(en, sw), tcount[k], ty == k, "type_$k") { ty = k; m.prefs.ftype = k } }
            }
        }
        itemsIndexed(rows, key = { _, it -> it.id }) { i, p -> Box(Modifier.rise(i, seen, p.id)) { ProjectRow(m, snap, p, here) { open(p.id) } } }
        if (rows.isEmpty()) item {
            EmptyDrop(when {
                f == "missing" && ck.isBlank() -> t("Every project has a location.", "Kila mradi una mahali.")
                f == "repairs" && ck.isBlank() -> t("No open repairs.", "Hakuna matengenezo yaliyo wazi.")
                f == "due" -> t("Everything has been visited in the last 6 months.", "Kila kitu kimetembelewa katika miezi 6 iliyopita.")
                else -> t("No projects match.", "Hakuna mradi unaolingana.")
            })
        }
    }
    if (countrySheet) PickSheet(t("Country", "Nchi"), listOf(Triple("", t("All countries", "Nchi zote"), "map")) + COUNTRIES.map { (k, n) -> Triple(k, n, "pin") }, ck, { ck = it; m.prefs.country = it }) { countrySheet = false }
}

/** Kinds of project for the type filter. */
val TGROUPS = listOf(Triple("water", "Water", "Maji"), Triple("school", "Schools", "Shule"), Triple("health", "Health", "Afya"), Triple("home", "Children’s homes", "Makao ya watoto"), Triple("farm", "Farming", "Kilimo"), Triple("other", "Other", "Nyingine"))
fun tgroup(p: Project): String { val t = p.type.lowercase(); return when { "borehole" in t || "collection" in t -> "water"; "school" in t -> "school"; "health" in t -> "health"; "orphan" in t -> "home"; "farm" in t -> "farm"; else -> "other" } }

@Composable
private fun TypeChip(label: String, n: Int?, on: Boolean, tag: String, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(99.dp)).background(if (on) C.ink else C.card).border(1.dp, if (on) C.ink else C.line, RoundedCornerShape(99.dp)).clickable(role = Role.Button, onClick = onClick)
        .heightIn(min = 36.dp).padding(horizontal = 14.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) C.card else C.ink)
        if (n != null) { Spacer(Modifier.width(6.dp)); Text("$n", style = MaterialTheme.typography.labelMedium, color = if (on) C.card.copy(alpha = 0.75f) else C.muted) }
    }
}

@Composable
fun ProjectRow(m: AppModel, s: Snapshot, p: Project, here: LatLng?, onClick: () -> Unit) {
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
            Text(p.title, style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp), color = C.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(p.sub, style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.padding(top = 5.dp)) {
                when {
                    rep != null -> Tag(if (rep.state == "notworking") t("Not working", "Haifanyi kazi") else t("Needs repair", "Inahitaji matengenezo"), if (rep.state == "notworking") C.badBg else C.warnBg, if (rep.state == "notworking") C.bad else C.warn)
                    p.gps == null -> Tag(t("No location", "Hakuna mahali"), C.warnBg, C.warn)
                    here != null -> Tag(Geo.dist(Geo.metres(here, p.gps!!), m.prefs.miles), C.tint, C.deep)
                    else -> Tag(t("Pinned", "Ina mahali"), C.okBg, C.ok)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(20.dp))
    }
}

/** Home: a greeting, where you are, a big question and four quick actions. */
@Composable
private fun HomeTop(m: AppModel, s: Snapshot, here: LatLng?, search: () -> Unit, filter: (String) -> Unit, newSite: () -> Unit) {
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
        // the four actions in one row, so the whole home fits on one screen
        Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Triple("missing", t("Pin a location", "Weka mahali"), "pin"), Triple("near", t("Visit nearby", "Tembelea karibu"), "clip"),
                Triple("new", t("Add a site", "Ongeza eneo"), "plus"), Triple("repairs", t("Repairs", "Ukarabati"), "wrench")).forEachIndexed { ci, (k, l, ic) ->
                val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Column(Modifier.weight(1f).rise(ci + 1, step = 60).press(src).heightIn(min = 84.dp).clip(RoundedCornerShape(18.dp)).background(if (C.glassy) C.glass2 else C.card).border(1.dp, if (C.glassy) C.glassEdge else C.line, RoundedCornerShape(18.dp))
                    .clickable(interactionSource = src, indication = androidx.compose.material3.ripple(), role = Role.Button) { if (k == "new") newSite() else filter(k) }.padding(horizontal = 4.dp, vertical = 10.dp).testTag("quick_$k"),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    val hu = C.hue(k)
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)).background(hu.bg), contentAlignment = Alignment.Center) { Icon(Ic.get(ic), null, tint = hu.fg, modifier = Modifier.size(20.dp)) }
                    Text(l, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 14.sp), color = C.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

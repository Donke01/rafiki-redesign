package org.rafikioutreach.pin.ui

import androidx.compose.ui.semantics.contentDescription

import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.text.withStyle

import androidx.compose.ui.unit.sp

import androidx.compose.foundation.shape.CircleShape

import androidx.compose.ui.draw.shadow

import androidx.compose.ui.focus.focusRequester

import kotlinx.coroutines.launch

import androidx.compose.runtime.rememberCoroutineScope

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
    Box(Modifier.fillMaxSize()) {
        ProjectsMap(
            snap.projects.filter { it.gps != null }.map { Dot(it.id, it.gps!!, dotColor(snap, it), snap.repairs.containsKey(it.id), it.title) },
            fix?.ll, open, Modifier.fillMaxSize(), fitKey = snap.projects.size,
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
        nearHere(snap, fix?.ll)?.takeIf { !m.dismissed.contains(it.first.id) }?.let { (p, d) ->
            HereBanner(p, d, m.prefs.miles, Modifier.padding(top = 10.dp), { visit(p.id) }, { open(p.id) }) { m.dismissed.add(p.id) }
        }
        }
        NearestSheet(m, snap, fix?.ll, Modifier.align(Alignment.BottomCenter).padding(12.dp), open)
    }
}

/** "Nearest to you": the three closest pinned projects, folded away until you open it. */
@Composable
private fun NearestSheet(m: AppModel, snap: Snapshot, here: LatLng?, modifier: Modifier, open: (String) -> Unit) {
    var shut by remember { mutableStateOf(true) }
    val list = here?.let { h -> snap.projects.filter { it.gps != null }.map { it to Geo.metres(h, it.gps!!) }.sortedBy { it.second }.take(3) } ?: emptyList()
    Card(modifier.testTag("nearest"), pad = 0.dp) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { shut = !shut }.heightIn(min = 56.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("Nearest to you", "Iliyo karibu nawe"), style = MaterialTheme.typography.titleMedium, color = C.ink, modifier = Modifier.weight(1f))
            Text(if (shut) t("Show", "Onyesha") else t("Hide", "Ficha"), style = MaterialTheme.typography.labelLarge, color = C.muted)
        }
        if (!shut) Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (here == null) Text(t("Allow location to see the projects closest to you.", "Ruhusu mahali ili uone miradi iliyo karibu nawe."), style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.padding(6.dp))
            list.forEach { (p, _) -> ProjectRow(m, snap, p, here) { open(p.id) } }
        }
    }
}

fun nearHere(s: Snapshot, here: LatLng?): Pair<Project, Double>? {
    here ?: return null
    return s.projects.filter { it.gps != null }.map { it to Geo.metres(here, it.gps!!) }.minByOrNull { it.second }?.takeIf { it.second <= 200 }
}

@Composable
fun HereBanner(p: Project, d: Double, miles: Boolean, modifier: Modifier, visit: () -> Unit, open: () -> Unit, dismiss: () -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(OkBrush).padding(14.dp).testTag("here")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.get("pin"), null, tint = Color.White, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
            Text(t("You’re at ${p.title}", "Uko ${p.title}") + " · " + Geo.dist(d, miles), color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 2)
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("Report a visit", "Ripoti ziara"), color = Color(0xFF0E4A2B), style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(Color.White).clickable(role = Role.Button, onClick = visit).heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 12.dp).testTag("here_visit"))
            Text(t("Open", "Fungua"), color = Color.White, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(RoundedCornerShape(99.dp)).border(1.dp, Color(0x99FFFFFF), RoundedCornerShape(99.dp)).clickable(role = Role.Button, onClick = open).heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 12.dp).testTag("here_open"))
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(44.dp).clip(CircleShape).border(1.dp, Color(0x99FFFFFF), CircleShape).clickable(role = Role.Button, onClick = dismiss).semantics { contentDescription = t("Not now", "Si sasa") }.testTag("here_x"), contentAlignment = Alignment.Center) {
                Icon(Ic.get("x"), null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
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
fun Meter(f: Float, modifier: Modifier = Modifier, light: Boolean = false) {
    Box(modifier.fillMaxWidth().height(if (light) 8.dp else 10.dp).clip(RoundedCornerShape(99.dp)).background(if (light) Color(0x2EFFFFFF) else C.line)) {
        Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).height(if (light) 8.dp else 10.dp).clip(RoundedCornerShape(99.dp))
            .background(if (light) androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFF5DB8F2), Color(0xFFBFE3FB))) else OkBrush))
    }
}

private val FILTERS = listOf(
    Triple("missing", "Needs a location|Hakuna mahali", "pinq"), Triple("near", "Nearby|Karibu nawe", "compass"), Triple("repairs", "Repairs|Matengenezo", "wrench"),
    Triple("due", "Due a visit|Inasubiri ziara", "cal"), Triple("all", "All|Yote", "list"),
)

@Composable
fun ProjectsScreen(m: AppModel, open: (String) -> Unit, planRoute: () -> Unit = {}, newSite: () -> Unit = {}, visit: (String) -> Unit = open) {
    val s by m.snap.collectAsState()
    val fix by m.fix.collectAsState()
    val waiting by m.waiting.collectAsState()
    val banner by m.banner.collectAsState()
    val snap = s ?: return
    var q by remember { mutableStateOf("") }
    var f by remember { mutableStateOf(m.prefs.filter) }
    var ck by remember { mutableStateOf(m.prefs.country) }
    var menu by remember { mutableStateOf(false) }
    val here = fix?.ll

    var rows = snap.projects.filter { p ->
        when (f) {
            "missing" -> p.gps == null
            "near" -> p.gps != null
            "repairs" -> snap.repairs.containsKey(p.id)
            "due" -> p.gps != null && ageDays(snap, p) >= DUE_DAYS
            else -> true
        } && (ck.isBlank() || p.ck == ck) && (q.isBlank() || "${p.title} ${p.area} ${p.country}".contains(q.trim(), ignoreCase = true))
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
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    fun pick(k: String) { f = k; m.prefs.filter = k; scope.launch { list.animateScrollToItem(maxOf(0, list.layoutInfo.totalItemsCount - rows.size - 2)) } }
    LazyColumn(Modifier.fillMaxSize().testTag("projects"), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { HomeTop(m, snap, here, { scope.launch { list.animateScrollToItem(6); runCatching { focus.requestFocus() } } }, { pick(it) }, newSite) }
        nearHere(snap, here)?.takeIf { !m.dismissed.contains(it.first.id) }?.let { (p, d) -> item { HereBanner(p, d, m.prefs.miles, Modifier, { visit(p.id) }, { open(p.id) }) { m.dismissed.add(p.id) } } }
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
                Text(t("Your work", "Kazi yako"), style = MaterialTheme.typography.titleLarge, color = C.ink)
                mine.forEach { (p, why) -> Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(14.dp)).clickable { open(p.id) }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(if (why == t("Repair job", "Kazi ya matengenezo")) "wrench" else "cal", C.blue, C.tint)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text(p.title, style = MaterialTheme.typography.titleSmall, color = C.ink); Text(why, style = MaterialTheme.typography.bodySmall, color = C.muted) }
                } }
            }
        }
        item {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t("Projects", "Miradi"), style = MaterialTheme.typography.headlineLarge, color = C.ink, modifier = Modifier.weight(1f))
                Btn(t("Plan a route", "Panga njia"), planRoute, small = true, light = true, icon = "map")
            }
        }
        item {
            val pinned = snap.projects.count { it.gps != null }
            Card(brush = DeepBrush) {
                Row { Text(t("$pinned of ${snap.projects.size} projects pinned", "Miradi $pinned kati ya ${snap.projects.size} ina mahali"), color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f)); Text(t("${snap.projects.size - pinned} to go", "${snap.projects.size - pinned} zimebaki"), color = Color(0xFFCFE8FA), style = MaterialTheme.typography.bodySmall) }
                Meter(pinned.toFloat() / maxOf(1, snap.projects.size), Modifier.padding(top = 10.dp), light = true)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    COUNTRIES.forEach { (k, n) ->
                        val l = snap.projects.filter { it.ck == k }
                        if (l.isNotEmpty()) Column(Modifier.weight(1f)) {
                            val pn = l.count { it.gps != null }
                            Text("$n $pn/${l.size}", color = Color(0xFFCFE8FA), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            Meter(pn.toFloat() / l.size, Modifier.padding(top = 4.dp), light = true)
                        }
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                q, { q = it }, Modifier.fillMaxWidth().heightIn(min = 52.dp).focusRequester(focus), placeholder = { Text(t("Search by project name or place", "Tafuta kwa jina la mradi au mahali"), color = C.muted) },
                leadingIcon = { Icon(Ic.get("search"), null, tint = C.muted) }, singleLine = true, shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.blue, unfocusedBorderColor = C.edge, focusedContainerColor = C.card, unfocusedContainerColor = C.card),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FILTERS.forEach { (k, label, icon) ->
                    Tile(label.split("|").let { t(it[0], it[1]) }, icon, f == k, { f = k; m.prefs.filter = k }, Modifier.weight(1f), badge = if (k == "repairs") snap.repairs.size else 0)
                }
            }
        }
        item {
            Box {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.card).border(1.dp, C.edge, RoundedCornerShape(16.dp))
                        .clickable(role = Role.DropdownList) { menu = true }.heightIn(min = 52.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { Text(COUNTRIES[ck] ?: t("All countries", "Nchi zote"), style = MaterialTheme.typography.bodyLarge, color = C.text, modifier = Modifier.weight(1f)); Icon(Ic.get("down"), null, tint = C.muted) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(t("All countries", "Nchi zote")) }, { ck = ""; m.prefs.country = ""; menu = false })
                    COUNTRIES.forEach { (k, n) -> DropdownMenuItem({ Text(n) }, { ck = k; m.prefs.country = k; menu = false }) }
                }
            }
        }
        item { Text((COUNTRIES[ck]?.let { "$it · " } ?: "") + count, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = C.muted) }
        items(rows, key = { it.id }) { p -> ProjectRow(m, snap, p, here) { open(p.id) } }
        if (rows.isEmpty()) item {
            EmptyDrop(when {
                f == "missing" && q.isBlank() && ck.isBlank() -> t("Every project has a location.", "Kila mradi una mahali.")
                f == "repairs" && q.isBlank() && ck.isBlank() -> t("No open repairs.", "Hakuna matengenezo yaliyo wazi.")
                f == "due" && q.isBlank() -> t("Everything has been visited in the last 6 months.", "Kila kitu kimetembelewa katika miezi 6 iliyopita.")
                else -> t("No projects match.", "Hakuna mradi unaolingana.")
            })
        }
    }
}

@Composable
fun ProjectRow(m: AppModel, s: Snapshot, p: Project, here: LatLng?, onClick: () -> Unit) {
    val rep = s.repairs[p.id]
    val edge = when (rep?.state) { "notworking" -> C.bad; null -> if (p.gps != null) C.ok else Amber; else -> Amber }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.card).border(1.dp, C.line, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).heightIn(min = 76.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(76.dp).background(edge))
        Spacer(Modifier.width(10.dp))
        NetImage(p.photo?.let { m.api.photoUrl(it) }, Modifier.size(54.dp).clip(RoundedCornerShape(14.dp)).background(C.tint)) {
            Icon(Ic.get("drop"), null, tint = C.blue, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(p.title, style = MaterialTheme.typography.titleSmall.copy(fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp)), color = C.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(p.sub, style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 2)
        }
        Spacer(Modifier.width(8.dp))
        when {
            rep != null -> Tag(if (rep.state == "notworking") t("Not working", "Haifanyi kazi") else t("Needs repair", "Inahitaji matengenezo"), if (rep.state == "notworking") C.badBg else C.warnBg, if (rep.state == "notworking") C.bad else C.warn)
            p.gps == null -> Tag(t("No location", "Hakuna mahali"), C.warnBg, C.warn)
            here != null -> Tag(Geo.dist(Geo.metres(here, p.gps!!), m.prefs.miles), C.tint, C.deep)
            else -> Tag(t("Pinned", "Ina mahali"), C.okBg, C.ok)
        }
        Spacer(Modifier.width(12.dp))
    }
}

/** Home: a greeting, where you are, a big question and four quick actions. */
@Composable
private fun HomeTop(m: AppModel, s: Snapshot, here: LatLng?, search: () -> Unit, filter: (String) -> Unit, newSite: () -> Unit) {
    val first = m.prefs.name.substringBefore(' ')
    val where = here?.let { h -> s.projects.filter { it.gps != null }.minByOrNull { Geo.metres(h, it.gps!!) }?.takeIf { Geo.metres(h, it.gps!!) < 60000 && it.area.isNotBlank() }?.let { it.area + ", " + it.country } }
        ?: COUNTRIES[m.prefs.country]
    Column {
        Text("Habari, " + first.ifBlank { "karibu" }, style = MaterialTheme.typography.bodySmall, color = C.muted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.get("pin"), null, tint = C.blue, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(4.dp))
            Text(where ?: "RAFIKI Pin", style = MaterialTheme.typography.titleSmall, color = C.ink, maxLines = 1)
        }
        Row(Modifier.padding(top = 10.dp, bottom = 14.dp), verticalAlignment = Alignment.Bottom) {
            Text(androidx.compose.ui.text.buildAnnotatedString {
                withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Normal)) { append(t("Which water point", "Kituo gani cha maji") + "\n") }
                append(t("today?", "leo?"))
            }, style = MaterialTheme.typography.headlineLarge.copy(fontSize = 31.sp, lineHeight = 34.sp, letterSpacing = (-1).sp), color = C.ink, modifier = Modifier.weight(1f))
            Box(Modifier.size(54.dp).shadow(12.dp, CircleShape, spotColor = Color(0x550C1924)).clip(CircleShape).background(C.primary).clickable(role = Role.Button, onClick = search).testTag("home_search"), contentAlignment = Alignment.Center) {
                Icon(Ic.get("search"), t("Search projects", "Tafuta miradi"), tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
        listOf(
            listOf(Triple("missing", t("Pin a location", "Weka mahali"), "pin"), Triple("near", t("Visit nearby", "Tembelea karibu"), "clip")),
            listOf(Triple("new", t("Add a site", "Ongeza eneo"), "plus"), Triple("repairs", t("Repairs", "Ukarabati"), "wrench")),
        ).forEach { row ->
            Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (k, l, ic) ->
                    Row(Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(18.dp)).background(if (C.glassy) C.glass2 else C.card).border(1.dp, if (C.glassy) C.glassEdge else C.line, RoundedCornerShape(18.dp))
                        .clickable(role = Role.Button) { if (k == "new") newSite() else filter(k) }.padding(horizontal = 12.dp).testTag("quick_$k"), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(32.dp).clip(RoundedCornerShape(11.dp)).background(C.tint), contentAlignment = Alignment.Center) { Icon(Ic.get(ic), null, tint = C.blue, modifier = Modifier.size(18.dp)) }
                        Spacer(Modifier.width(10.dp)); Text(l, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp), color = C.ink)
                    }
                }
            }
        }
    }
}

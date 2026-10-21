package org.rafikioutreach.pin.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.*
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.COUNTRIES
import org.rafikioutreach.pin.data.DUE_DAYS
import org.rafikioutreach.pin.data.Geo
import org.rafikioutreach.pin.data.Project
import org.rafikioutreach.pin.data.Snapshot
import org.rafikioutreach.pin.data.reasonText
import org.rafikioutreach.pin.data.stateName
import org.rafikioutreach.pin.data.urgencyName
import org.rafikioutreach.pin.t
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The Office: tools for admins. Same server actions as the web app's Office tab. */
@Composable
fun OfficeScreen(m: AppModel, open: (String) -> Unit) {
    var sec by rememberSaveable { mutableStateOf("overview") }
    val s by m.snap.collectAsState()
    val snap = s ?: return
    val secs = listOf(
        Triple("overview", t("Overview", "Muhtasari"), "drop"), Triple("repairs", t("Repairs", "Ukarabati"), "wrench"), Triple("visits", t("Visits", "Ziara"), "cal"),
        Triple("review", t("Review", "Idhinisha"), "inbox"), Triple("team", t("Team", "Timu"), "people"), Triple("cleanup", t("Clean-up", "Usafi"), "spark"), Triple("export", t("Export", "Pakua"), "upload"),
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(t("Office", "Ofisi"), style = MaterialTheme.typography.headlineLarge, color = C.ink)
        secs.chunked(4).forEachIndexed { ri, row ->
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { ci, (k, l, ic) -> Tile(l, ic, sec == k, { sec = k }, Modifier.weight(1f).rise(ri * 4 + ci, step = 35).testTag("o_$k"), badge = when (k) { "review" -> snap.reviewOpen; "repairs" -> snap.repairs.size; else -> 0 }) }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(14.dp))
        when (sec) {
            "overview" -> Overview(m, snap, open) { sec = it }
            "repairs" -> Repairs(m, snap, open)
            "visits" -> AssignVisits(m, snap)
            "review" -> ReviewQueue(m, open)
            "team" -> TeamSection(m)
            "cleanup" -> Cleanup(snap, open)
            else -> Export(m, snap)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** People who can sign in (from the Team list), for "assigned to". */
@Composable
private fun rememberTeam(m: AppModel): Pair<List<JSONObject>, () -> Unit> {
    var people by remember { mutableStateOf(listOf<JSONObject>()) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tick) {
        val r = m.api.adminGet("team"); val a = r.json.optJSONArray("team") ?: JSONArray()
        people = (0 until a.length()).map { a.getJSONObject(it) }
    }
    return people to { tick++ }
}

@Composable
private fun Overview(m: AppModel, s: Snapshot, open: (String) -> Unit, go: (String) -> Unit) {
    val act by m.activity.collectAsState()
    LaunchedEffect(Unit) { m.loadActivity() }
    val pinned = s.projects.count { it.gps != null }
    val pct = if (s.projects.isEmpty()) 0 else pinned * 100 / s.projects.size
    val due = s.projects.count { it.gps != null && ageDays(s, it) >= DUE_DAYS }
    val monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    val vm = act?.count { it.optString("a") == "visit" && runCatching { Instant.parse(it.optString("at")).toEpochMilli() >= monthStart }.getOrDefault(false) }
    val urgent = s.repairs.values.count { it.urgency == "urgent" }
    val cost = s.repairs.values.sumOf { it.cost ?: 0L }

    Card(brush = DeepBrush) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tank(pct)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(t("$pinned of ${s.projects.size} projects are on the map", "Miradi $pinned kati ya ${s.projects.size} iko kwenye ramani"), color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text(if (pinned < s.projects.size) t("${s.projects.size - pinned} still need a location pinned in the field.", "${s.projects.size - pinned} bado zinahitaji mahali kuwekwa uwanjani.") else t("Every project has a location.", "Kila mradi una mahali."), color = Color(0xFFCFE8FA), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                // "Show them": the projects with no location, in Projects
                if (pinned < s.projects.size) Row(Modifier.padding(top = 10.dp).clip(RoundedCornerShape(99.dp)).background(Color(0x26FFFFFF)).clickable(role = Role.Button) { m.prefs.filter = "missing"; m.goTab = "list" }
                    .padding(horizontal = 14.dp, vertical = 8.dp).testTag("show_missing"), verticalAlignment = Alignment.CenterVertically) {
                    Text(t("Show them", "Zionyeshe"), color = Color.White, style = MaterialTheme.typography.labelLarge); Spacer(Modifier.width(6.dp))
                    Icon(Ic.get("arrow"), null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Stat(Modifier.weight(1f).rise(1), "wrench", s.repairs.size, t("open repairs", "matengenezo yaliyo wazi"), (if (urgent > 0) t("$urgent urgent", "$urgent ya haraka") else "") + (if (cost > 0) " · KES %,d".format(cost) else "").ifBlank { if (s.repairs.isNotEmpty() && urgent == 0) t("no cost estimate yet", "bado hakuna makadirio ya gharama") else "" }, hot = s.repairs.isNotEmpty(), red = urgent > 0, hue = "repairs") { go("repairs") }
        Stat(Modifier.weight(1f).rise(2), "inbox", s.reviewOpen, t("waiting for your review", "zinasubiri uidhinishe"), "", hot = s.reviewOpen > 0, hue = "new") { go("review") }
    }
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Stat(Modifier.weight(1f).rise(3), "cal", due, t("due a visit", "zinasubiri ziara"), t("not seen for 6+ months", "hazijaonekana kwa miezi 6+"), hue = "due") { go("visits") }
        Stat(Modifier.weight(1f).rise(4), "clip", vm, t("visits this month", "ziara za mwezi huu"), "", hue = "near") { }
    }
    // condition at last visit
    val st = s.visits.values.groupingBy { it.state }.eachCount()
    val seen = st.values.sum(); val unseen = (s.projects.size - seen).coerceAtLeast(0); val tot = maxOf(1, s.projects.size).toFloat()
    Card(Modifier.padding(top = 10.dp)) {
        Text(t("Condition at last visit", "Hali katika ziara ya mwisho"), style = MaterialTheme.typography.titleSmall, color = C.ink)
        val grow = rememberFill(1f, 1100)
        Row(Modifier.padding(vertical = 10.dp).fillMaxWidth().height(14.dp).clip(RoundedCornerShape(99.dp)).background(C.line), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf((st["working"] ?: 0) to Green, (st["repair"] ?: 0) to Amber, (st["notworking"] ?: 0) to Red, unseen to C.off).forEach { (n, c) -> if (n > 0 && grow > 0.01f) Box(Modifier.weight(n / tot * grow).height(14.dp).background(c)) }
            if (grow < 0.999f) Spacer(Modifier.weight(1.0001f - grow))
        }
        listOf(Triple(st["working"] ?: 0, Green, t("working", "zinafanya kazi")), Triple(st["repair"] ?: 0, Amber, t("needed repair", "zilihitaji matengenezo")), Triple(st["notworking"] ?: 0, Red, t("not working", "hazifanyi kazi")), Triple(unseen, C.off, t("not visited yet", "bado hazijatembelewa")))
            .chunked(2).forEach { r -> Row { r.forEach { (n, c, l) -> Row(Modifier.weight(1f).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(c)); Text(" $n ", style = MaterialTheme.typography.titleSmall, color = C.ink); Text(l, style = MaterialTheme.typography.bodySmall, color = C.muted) } } } }
    }
    H2(t("By country", "Kwa nchi"), Modifier.padding(top = 14.dp))
    val FLAG = mapOf("ke" to "🇰🇪", "tz" to "🇹🇿", "ug" to "🇺🇬", "mw" to "🇲🇼")
    COUNTRIES.entries.forEachIndexed { ci, (k, n) ->
        val l = s.projects.filter { it.ck == k }; if (l.isEmpty()) return@forEachIndexed
        val loc = l.count { it.gps != null }
        Card(Modifier.padding(top = 8.dp).rise(ci), pad = 14.dp) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(C.tint), contentAlignment = Alignment.Center) { Text(FLAG[k] ?: "", fontSize = 24.sp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row { Text(n, style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.weight(1f)); Text(t("$loc of ${l.size} mapped", "$loc kati ya ${l.size} zina mahali"), style = MaterialTheme.typography.bodySmall, color = C.muted) }
                    Meter(loc.toFloat() / l.size, Modifier.padding(top = 8.dp), glass = true)
                    val ks = { st: String -> l.count { s.visits[it.id]?.state == st } }
                    val rp = l.count { s.repairs.containsKey(it.id) }; val du = l.count { it.gps != null && ageDays(s, it) >= DUE_DAYS }
                    val pills = listOfNotNull(
                        ks("working").takeIf { it > 0 }?.let { t("$it working", "$it zinafanya kazi") to Green }, ks("repair").takeIf { it > 0 }?.let { t("$it needed repair", "$it zilihitaji matengenezo") to Amber },
                        ks("notworking").takeIf { it > 0 }?.let { t("$it not working", "$it hazifanyi kazi") to Red }, rp.takeIf { it > 0 }?.let { t("$it open " + if (it == 1) "repair" else "repairs", "matengenezo $it") to Amber },
                        du.takeIf { it > 0 }?.let { t("$it due a visit", "$it zinasubiri ziara") to Color(0xFF0A78C2) },
                    ).ifEmpty { listOf(t("Nothing needs attention", "Hakuna kinachohitaji kushughulikiwa") to Green) }
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        pills.forEach { (txt, c) ->
                            Row(Modifier.clip(RoundedCornerShape(99.dp)).background(c.copy(alpha = 0.12f)).padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).clip(CircleShape).background(c)); Spacer(Modifier.width(5.dp))
                                Text(txt, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = C.ink)
                            }
                        }
                    }
                }
            }
        }
    }
    H2(t("Latest from the field", "Habari za karibuni kutoka uwanjani"), Modifier.padding(top = 14.dp))
    val a = act
    if (a == null) Text(t("Loading…", "Inapakia…"), color = C.muted)
    else if (a.isEmpty()) Text(t("Nothing yet.", "Bado hakuna kitu."), color = C.muted)
    else { val list = a.takeLast(12).reversed(); list.forEachIndexed { i, e ->
        // a timeline: a coloured ring for each kind of change, joined by a thin line
        val hu = C.hue(feedHue(e.optString("a")))
        Row(Modifier.padding(top = 2.dp).rise(i, step = 40).height(androidx.compose.foundation.layout.IntrinsicSize.Min), verticalAlignment = Alignment.Top) {
            Box(Modifier.width(36.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                if (i < list.size - 1) Box(Modifier.padding(top = 36.dp).width(2.dp).fillMaxHeight().background(hu.fg.copy(alpha = 0.25f)))
                Box(Modifier.padding(top = 4.dp).size(32.dp).clip(CircleShape).background(C.card).border(2.dp, hu.fg, CircleShape), contentAlignment = Alignment.Center) { Icon(Ic.get(feedIcon(e.optString("a"))), null, tint = hu.fg, modifier = Modifier.size(16.dp)) }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.padding(top = 6.dp, bottom = 10.dp)) {
                Text(androidx.compose.ui.text.buildAnnotatedString {
                    withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append(e.optString("by")) }
                    append(" " + verb(e.optString("a")) + " " + e.optString("t"))
                }, style = MaterialTheme.typography.bodyMedium, color = C.text)
                Text(whenText(e.optString("at")), style = MaterialTheme.typography.bodySmall, color = C.muted)
            }
        }
    } }
    // the map: open repairs in orange and red, other projects with a location in green
    Card(Modifier.padding(top = 14.dp).rise(), pad = 0.dp) {
        ProjectsMap(s.projects.filter { it.gps != null }.map { Dot(it.id, it.gps!!, dotColor(s, it), s.repairs.containsKey(it.id), it.title) }, null, open, Modifier.fillMaxWidth().height(280.dp).testTag("office_map"))
    }
    Text(t("Orange and red: open repairs. Green: other projects with a location.", "Machungwa na nyekundu: matengenezo yaliyo wazi. Kijani: miradi mingine yenye mahali."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 6.dp))
}

private fun feedHue(a: String) = when (a) { "pin", "approve", "assign" -> "pin"; "visit", "fixed" -> "near"; "new" -> "new"; "job", "request" -> "repairs"; "reject" -> "me"; else -> "all" }
private fun feedIcon(a: String) = when (a) { "pin" -> "pin"; "visit" -> "clip"; "fixed" -> "check"; "new" -> "plus"; "undo" -> "undo"; "job" -> "wrench"; "assign" -> "cal"; "team" -> "people"; else -> "drop" }
fun verb(a: String) = when (a) {
    "pin" -> t("pinned", "aliweka mahali pa"); "visit" -> t("reported a visit to", "aliripoti ziara ya"); "fixed" -> t("marked fixed", "aliweka imerekebishwa")
    "new" -> t("added a new site:", "aliongeza eneo jipya:"); "undo" -> t("undid a pin on", "alitendua mahali pa"); "request" -> t("asked to move the confirmed location of", "aliomba kuhamisha mahali palipothibitishwa pa")
    "approve" -> t("approved", "aliidhinisha"); "reject" -> t("turned down", "alikataa"); "job" -> t("updated the repair job on", "alisasisha kazi ya matengenezo ya")
    "assign" -> t("assigned", "aligawa"); "team" -> t("team:", "timu:"); else -> a
}

@Composable
private fun Stat(modifier: Modifier, icon: String, n: Int?, label: String, sub: String, hot: Boolean = false, red: Boolean = false, hue: String = "pin", onClick: () -> Unit) {
    val hu = C.hue(if (hot) "repairs" else hue)
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.card).border(if (hot) 2.dp else 1.dp, if (hot) (if (red) Red else Amber) else C.line, RoundedCornerShape(20.dp))
                .clickable(role = Role.Button, onClick = onClick).padding(14.dp),
        ) {
            IconTile(icon, hu.fg, hu.bg, 40.dp)
            val shown = n?.let { kotlin.math.round(rememberFill(it.toFloat(), 900)).toInt() }
            Text(shown?.let { "%,d".format(it) } ?: "…", style = MaterialTheme.typography.headlineMedium, color = C.ink, modifier = Modifier.padding(top = 8.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = C.muted)
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = C.muted)
        }
        if (red) Pulse(Modifier.align(Alignment.TopEnd).padding(14.dp))
    }
}

/** A red dot that breathes, for something urgent. */
@Composable
private fun Pulse(modifier: Modifier) {
    val k = if (Motion.on && Motion.loops) {
        val tr = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
        tr.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1400)), label = "p").value
    } else 0f
    Box(modifier.size(22.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size((10 + 12 * k).dp).clip(CircleShape).background(Red.copy(alpha = 0.35f * (1f - k))))
        Box(Modifier.size(10.dp).clip(CircleShape).background(Red))
    }
}

/** The water tank: fills up to the share of projects on the map, with a moving water line. */
@Composable
private fun Tank(pct: Int) {
    val level = rememberFill(maxOf(pct, 4) / 100f, 1400)
    val shown = kotlin.math.round(rememberFill(pct.toFloat(), 1100)).toInt()
    val ph = if (Motion.on && Motion.loops) {
        val tr = androidx.compose.animation.core.rememberInfiniteTransition(label = "wave")
        tr.animateFloat(0f, (2 * Math.PI).toFloat(), androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(2600, easing = androidx.compose.animation.core.LinearEasing)), label = "ph").value
    } else 0f
    Box(Modifier.size(96.dp).clip(CircleShape).background(Color(0x1FFFFFFF)), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height; val top = h * (1f - level)
            for ((amp, shift, col) in listOf(Triple(5f, 1.6f, Color(0x805DB8F2)), Triple(4f, 0f, Color(0xFF2E9BE3)))) {
                val path = androidx.compose.ui.graphics.Path()
                path.moveTo(0f, h)
                var x = 0f
                while (x <= w) { path.lineTo(x, top + amp * kotlin.math.sin((x / w * 2 * Math.PI + ph + shift).toFloat())); x += 4f }
                path.lineTo(w, h); path.close()
                drawPath(path, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(col, Color(0xFF0A78C2)), startY = top, endY = h))
            }
        }
        Text("$shown%", color = Color.White, style = MaterialTheme.typography.headlineMedium)
    }
}

/** A date chosen with a calendar. Empty = none. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp), color = C.ink, modifier = Modifier.padding(bottom = 6.dp, top = 12.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.card).border(1.dp, C.edge, RoundedCornerShape(16.dp)).clickable { open = true }.heightIn(min = 52.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { Text(value.ifBlank { t("Choose a date", "Chagua tarehe") }, color = if (value.isBlank()) C.muted else C.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f)); Icon(Ic.get("cal"), null, tint = C.blue) }
    }
    if (open) {
        val st = rememberDatePickerState(initialSelectedDateMillis = runCatching { LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull())
        DatePickerDialog({ open = false }, confirmButton = {
            TextButton({ st.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }; open = false }) { Text(t("OK", "Sawa")) }
        }, dismissButton = { TextButton({ onChange(""); open = false }) { Text(t("Clear", "Futa")) } }) { DatePicker(st) }
    }
}

/** A list to pick one from (a person, a country). */
@Composable
fun Picker(label: String, value: String, options: List<Pair<String, String>>, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp), color = C.ink, modifier = Modifier.padding(bottom = 6.dp, top = 12.dp))
        Box {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.card).border(1.dp, C.edge, RoundedCornerShape(16.dp)).clickable(role = Role.DropdownList) { open = true }.heightIn(min = 52.dp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { Text(options.firstOrNull { it.first == value }?.second ?: value, style = MaterialTheme.typography.bodyLarge, color = C.text, modifier = Modifier.weight(1f).padding(vertical = 6.dp), maxLines = 2); Icon(Ic.get("down"), null, tint = C.muted) }
            DropdownMenu(open, { open = false }) { options.forEach { (k, l) -> DropdownMenuItem({ Text(l) }, { onChange(k); open = false }) } }
        }
    }
}

@Composable
private fun Repairs(m: AppModel, s: Snapshot, open: (String) -> Unit) {
    val (people, _) = rememberTeam(m)
    val ids = s.repairs.keys.sortedWith(compareBy({ mapOf("urgent" to 0, "month" to 1, "watch" to 2)[s.repairs[it]?.urgency] ?: 3 }, { s.repairs[it]?.at }))
    Text(t("Most urgent first. Changes leave a note on the project in monday.com, and the person assigned sees the job in their app.", "Za haraka kwanza. Mabadiliko yanaacha maelezo kwenye mradi katika monday.com, na aliyepewa anaona kazi kwenye programu yake."), style = MaterialTheme.typography.bodyMedium, color = C.muted)
    if (ids.isEmpty()) EmptyDrop(t("No open repairs.", "Hakuna matengenezo yaliyo wazi."))
    ids.forEach { id -> RepairJob(m, s, id, people.map { it.optString("name") }, open) }
}

@Composable
private fun RepairJob(m: AppModel, s: Snapshot, id: String, names: List<String>, open: (String) -> Unit) {
    val r = s.repairs[id] ?: return
    val p = s.projects.firstOrNull { it.id == id }
    val scope = rememberScope()
    var step by remember(id, r.step) { mutableStateOf(r.step) }
    var who by remember(id, r.assignee) { mutableStateOf(r.assignee) }
    var due by remember(id, r.due) { mutableStateOf(r.due) }
    var budget by remember(id, r.budget) { mutableStateOf(r.budget?.toString() ?: "") }
    var note by remember(id) { mutableStateOf("") }
    var msg by remember(id) { mutableStateOf<Pair<String, String>?>(null) }
    Card(Modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p?.title ?: r.title, style = MaterialTheme.typography.titleMedium, color = C.blue2, modifier = Modifier.weight(1f).clickable { open(id) })
            Tag(if (r.state == "notworking") stateName("notworking") else stateName("repair"), if (r.state == "notworking") C.badBg else C.warnBg, if (r.state == "notworking") C.bad else C.warn)
        }
        Text((p?.country ?: "") + " · " + t("reported by ", "imeripotiwa na ") + r.by + ", " + whenText(r.at) + (if (r.urgency.isNotBlank()) " · " + urgencyName(r.urgency) else ""), style = MaterialTheme.typography.bodySmall, color = C.muted)
        if (r.note.isNotBlank()) Text("“${r.note}”", style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.padding(top = 4.dp))
        if (r.problems.isNotEmpty()) Text(t("Problems: ", "Matatizo: ") + r.problems.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = C.muted)
        if (r.parts.isNotBlank() || r.cost != null) Text(listOfNotNull(r.parts.takeIf { it.isNotBlank() }?.let { t("Parts: ", "Vipuri: ") + it }, r.cost?.let { t("About KES ", "Takriban KES ") + "%,d".format(it) }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = C.muted)
        listOf("reported" to t("Reported", "Imeripotiwa"), "assigned" to t("Assigned", "Imegawiwa"), "parts" to t("Parts ordered", "Vipuri vimeagizwa"), "fixed" to t("Fixed", "Imerekebishwa")).chunked(2).forEachIndexed { i, pair ->
            Row(Modifier.padding(top = if (i == 0) 10.dp else 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pair.forEach { (k, l) -> Pill(l, step == k, Modifier.weight(1f), color = if (k == "fixed") C.ok else null) { step = k } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Picker(t("Assigned to", "Amepewa"), who, listOf("" to t("Not assigned", "Hajapewa mtu")) + names.map { it to it }, { who = it }, Modifier.weight(1f))
            DateField(t("Due", "Tarehe ya mwisho"), due, { due = it }, Modifier.weight(1f))
        }
        Field(budget, { budget = it.filter { c -> c.isDigit() } }, t("Approved budget (KES)", "Bajeti iliyoidhinishwa (KES)"), number = true)
        Field(note, { note = it }, t("Note (optional)", "Maelezo (si lazima)"), placeholder = t("e.g. parts bought in Eldoret", "mf. vipuri vilinunuliwa Eldoret"))
        var greenText by remember(id) { mutableStateOf<String?>(null) }
        LaunchedEffect(greenText) { if (greenText != null) { kotlinx.coroutines.delay(2600); greenText = null } }
        Btn(t("Save", "Hifadhi"), {
            scope.launch {
                msg = "" to t("Saving…", "Inahifadhi…")
                val x = m.api.admin(JSONObject().put("action", "job").put("id", id).put("step", step).put("assignee", who).put("due", due).put("budget", budget).put("note", note))
                msg = if (x.ok) "ok" to t("Saved ✓", "Imehifadhiwa ✓") else "bad" to reasonText(x.reason)
                if (x.ok) { note = ""; greenText = if (step == "fixed") t("Fixed", "Imerekebishwa") else t("Saved", "Imehifadhiwa"); m.reload() }
            }
        }, Modifier.padding(top = 12.dp), small = true, icon = "check", saved = greenText)
        msg?.let { (tone, text) -> Msg(text, tone) }
    }
}

@Composable
private fun AssignVisits(m: AppModel, s: Snapshot) {
    val scope = rememberScope()
    val (people, _) = rememberTeam(m)
    var show by remember { mutableStateOf("due") }
    var ck by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<String>() }
    var to by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<Pair<String, String>?>(null) }
    var plan by remember { mutableStateOf<List<JSONObject>?>(null) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tick) { val a = m.api.adminGet("assign").json.optJSONArray("items") ?: JSONArray(); plan = (0 until a.length()).map { a.getJSONObject(it) }.filter { !it.has("done") || it.isNull("done") } }

    Card {
        H2(t("Assign visits", "Gawa ziara"))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Picker(t("Field officer", "Afisa wa uwanjani"), to, listOf("" to t("Choose", "Chagua")) + people.map { it.optString("name") to it.optString("name") }, { to = it }, Modifier.weight(1f))
            DateField(t("Date", "Tarehe"), date, { date = it }, Modifier.weight(1f))
        }
        Field(note, { note = it }, t("Note (optional)", "Maelezo (si lazima)"), placeholder = t("e.g. check the pump and meet the committee", "mf. kagua pampu na ukutane na kamati"))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Picker(t("Show", "Onyesha"), show, listOf("due" to t("Due a visit", "Inasubiri ziara"), "repairs" to t("Open repairs", "Matengenezo yaliyo wazi"), "all" to t("All with a location", "Zote zenye mahali")), { show = it }, Modifier.weight(1f))
            Picker(t("Country", "Nchi"), ck, listOf("" to t("All", "Yote")) + COUNTRIES.toList(), { ck = it }, Modifier.weight(1f))
        }
        val rows = s.projects.filter { p -> p.gps != null && (ck.isBlank() || p.ck == ck) && when (show) { "due" -> ageDays(s, p) >= DUE_DAYS; "repairs" -> s.repairs.containsKey(p.id); else -> true } }.take(80)
        rows.forEach { p ->
            Row(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (p.id in picked) C.tint else C.card).border(1.dp, if (p.id in picked) C.blue else C.line, RoundedCornerShape(14.dp))
                .clickable { if (p.id in picked) picked.remove(p.id) else picked.add(p.id) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(p.id in picked, { if (it) picked.add(p.id) else picked.remove(p.id) }, colors = CheckboxDefaults.colors(checkedColor = C.blue))
                Column(Modifier.weight(1f)) { Text(p.title, style = MaterialTheme.typography.titleSmall, color = C.text); Text(p.sub, style = MaterialTheme.typography.bodySmall, color = C.muted) }
            }
        }
        if (rows.isEmpty()) Text(t("No projects match.", "Hakuna mradi unaolingana."), color = C.muted, modifier = Modifier.padding(top = 8.dp))
        Btn(if (picked.isEmpty()) t("Assign", "Gawa") else t("Assign ${picked.size} visits", "Gawa ziara ${picked.size}"), {
            scope.launch {
                msg = "" to t("Assigning…", "Inagawa…")
                val x = m.api.admin(JSONObject().put("action", "assign").put("ids", JSONArray(picked.toList())).put("to", to).put("date", date).put("note", note))
                msg = if (x.ok) "ok" to t("Assigned ✓ $to will see it in their app.", "Imegawiwa ✓ $to ataiona kwenye programu yake.") else "bad" to reasonText(x.reason)
                if (x.ok) { picked.clear(); note = ""; tick++ }
            }
        }, Modifier.fillMaxWidth().padding(top = 12.dp), icon = "cal", enabled = picked.isNotEmpty() && to.isNotBlank() && date.isNotBlank())
        msg?.let { (tone, text) -> Msg(text, tone) }
    }
    H2(t("Planned visits", "Ziara zilizopangwa"), Modifier.padding(top = 14.dp))
    val pl = plan
    if (pl == null) Text(t("Loading…", "Inapakia…"), color = C.muted)
    else if (pl.isEmpty()) Text(t("No visits planned.", "Hakuna ziara zilizopangwa."), color = C.muted)
    else pl.sortedBy { it.optString("date") }.forEach { a ->
        Card(Modifier.padding(top = 8.dp), pad = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.optString("t").ifBlank { a.optString("id") }, style = MaterialTheme.typography.titleSmall, color = C.ink)
                    Text(a.optString("to") + " · " + a.optString("date") + (a.optString("note").takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = C.muted)
                }
                Btn(t("Remove", "Ondoa"), { scope.launch { m.api.admin(JSONObject().put("action", "unassign").put("aid", a.optString("aid"))); tick++ } }, light = true, small = true)
            }
        }
    }
}

@Composable
private fun ReviewQueue(m: AppModel, open: (String) -> Unit) {
    val scope = rememberScope()
    var items by remember { mutableStateOf<List<JSONObject>?>(null) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tick) { val a = m.api.adminGet("review").json.optJSONArray("items") ?: JSONArray(); items = (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("status") == "open" } }
    val list = items
    if (list == null) { Text(t("Loading…", "Inapakia…"), color = C.muted); return }
    if (list.isEmpty()) EmptyDrop(t("Nothing waiting for review.", "Hakuna kinachosubiri kuidhinishwa."))
    list.forEach { it2 ->
        val d = it2.optJSONObject("data") ?: JSONObject()
        var reason by remember(it2.optString("rid")) { mutableStateOf("") }
        var msg by remember(it2.optString("rid")) { mutableStateOf<String?>(null) }
        Card(Modifier.padding(top = 10.dp)) {
            val (icon, kind) = when (it2.optString("kind")) {
                "new" -> "plus" to t("New site", "Eneo jipya"); "move" -> "pin" to t("Big location change", "Mabadiliko makubwa ya mahali")
                "request" -> "lock" to t("Change to a confirmed location", "Badiliko la mahali palipothibitishwa"); else -> "spark" to t("Story moment", "Hadithi")
            }
            Row(verticalAlignment = Alignment.CenterVertically) { IconTile(icon, C.blue, C.tint, 36.dp); Spacer(Modifier.width(10.dp)); Text(kind, style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.weight(1f)); Text(whenText(it2.optString("at")), style = MaterialTheme.typography.bodySmall, color = C.muted) }
            Text(it2.optString("t") + " · " + t("by ", "na ") + it2.optString("by"), style = MaterialTheme.typography.titleSmall, color = C.blue2, modifier = Modifier.padding(top = 8.dp).clickable { open(it2.optString("id")) })
            when (it2.optString("kind")) {
                "move" -> Text(t("From ", "Kutoka ") + d.optString("from").ifBlank { "—" } + t(" to ", " hadi ") + d.optString("to") + (if (d.has("dist")) " · " + Geo.dist(d.optDouble("dist"), m.prefs.miles) else ""), style = MaterialTheme.typography.bodySmall, color = C.muted)
                "request" -> Text(t("Wants ", "Anataka ") + d.optString("to") + t(" instead of ", " badala ya ") + d.optString("from"), style = MaterialTheme.typography.bodySmall, color = C.muted)
                "story" -> Text("“" + d.optString("quote") + "”" + (d.optString("who").takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""), style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(C.tint).padding(10.dp))
                else -> Text(listOf(d.optString("type"), d.optString("village"), COUNTRIES[d.optString("ck")] ?: "").filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = C.muted)
            }
            Field(reason, { reason = it }, t("Reason (optional)", "Sababu (si lazima)"), placeholder = t("e.g. wrong school", "mf. shule isiyo sahihi"))
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fun decide(dec: String) = scope.launch {
                    val x = m.api.admin(JSONObject().put("action", "review").put("rid", it2.optString("rid")).put("decision", dec).put("reason", reason))
                    if (x.ok) { tick++; m.reload() } else msg = reasonText(x.reason)
                }
                Btn(if (it2.optString("kind") == "story") t("Approve for the website", "Idhinisha kwa tovuti") else t("Approve", "Idhinisha"), { decide("approve") }, ok = true, small = true, icon = "check")
                Btn(if (it2.optString("kind") == "story") t("Don’t use", "Usitumie") else t("Reject", "Kataa"), { decide("reject") }, light = true, small = true)
            }
            Msg(msg, "bad")
        }
    }
}

@Composable
private fun TeamSection(m: AppModel) {
    val scope = rememberScope()
    val (people, again) = rememberTeam(m)
    var name by remember { mutableStateOf("") }
    var home by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var admin by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<Pair<String, String>?>(null) }
    var msg by remember { mutableStateOf<Pair<String, String>?>(null) }
    shown?.let { (who, pin) ->
        Card(brush = SolidColor(C.okBg)) {
            Text(t("PIN for ", "PIN ya ") + who, style = MaterialTheme.typography.titleSmall, color = C.ok)
            Text(pin, style = MaterialTheme.typography.headlineLarge.copy(letterSpacing = 8.sp), color = C.ink, modifier = Modifier.padding(vertical = 6.dp))
            Text(t("Shown once. Send it privately; they can’t see it again.", "Inaonyeshwa mara moja. Mtumie kwa siri; hawezi kuiona tena."), style = MaterialTheme.typography.bodySmall, color = C.text)
            Btn(t("Done", "Imekamilika"), { shown = null }, Modifier.padding(top = 8.dp), small = true, light = true)
        }
        Spacer(Modifier.height(10.dp))
    }
    people.forEach { p ->
        var ph by remember(p.optString("name")) { mutableStateOf(p.optString("home")) }
        var pe by remember(p.optString("name")) { mutableStateOf(p.optString("email")) }
        var pp by remember(p.optString("name")) { mutableStateOf(p.optString("phone")) }
        var pa by remember(p.optString("name")) { mutableStateOf(p.optBoolean("admin")) }
        val inApp = p.optString("source") == "app"
        Card(Modifier.padding(top = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.optString("name"), style = MaterialTheme.typography.titleMedium, color = C.ink, modifier = Modifier.weight(1f))
                if (p.optBoolean("admin")) Tag(t("Admin", "Msimamizi"), C.tint, C.deep)
            }
            Text(when (p.optString("source")) { "app" -> t("PIN from this app", "PIN ya programu hii"); "cloudflare" -> t("PIN set in Cloudflare", "PIN iliyowekwa Cloudflare"); else -> t("Signs in with monday.com", "Anaingia kwa monday.com") } +
                " · " + (p.optString("lastSeen").takeIf { it.isNotBlank() && it != "null" }?.let { t("last active ", "alitumia mwisho ") + whenText(it) } ?: t("last active never", "bado hajatumia")), style = MaterialTheme.typography.bodySmall, color = C.muted)
            if (inApp) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Picker(t("Home country", "Nchi ya nyumbani"), ph, listOf("" to t("All countries", "Nchi zote")) + COUNTRIES.toList(), { ph = it }, Modifier.weight(1f))
                    Field(pe, { pe = it }, t("E-mail (for alerts)", "Barua pepe (kwa taarifa)"), Modifier.weight(1f))
                }
                Field(pp, { pp = it }, t("Phone (the team sees it, for calls and WhatsApp)", "Simu (timu inaiona, kwa kupiga na WhatsApp)"), placeholder = "+254 712 345 678")
                Row(Modifier.padding(top = 8.dp).clickable { pa = !pa }, verticalAlignment = Alignment.CenterVertically) { Checkbox(pa, { pa = it }, colors = CheckboxDefaults.colors(checkedColor = C.blue)); Text(t("Admin (sees the Office)", "Msimamizi (anaona Ofisi)"), style = MaterialTheme.typography.bodyMedium, color = C.text) }
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn(t("Save", "Hifadhi"), { scope.launch { val x = m.api.admin(JSONObject().put("action", "team-update").put("name", p.optString("name")).put("home", ph).put("email", pe).put("phone", pp).put("admin", pa)); msg = if (x.ok) "ok" to t("Saved ✓", "Imehifadhiwa ✓") else "bad" to (if (x.reason == "self") t("You can’t remove your own admin role.", "Huwezi kuondoa nafasi yako mwenyewe ya msimamizi.") else reasonText(x.reason)); again() } }, small = true)
                    Btn(t("New PIN", "PIN mpya"), { scope.launch { val x = m.api.admin(JSONObject().put("action", "team-pin").put("name", p.optString("name"))); if (x.ok) shown = p.optString("name") to x.json.optString("pin") else msg = "bad" to reasonText(x.reason) } }, small = true, light = true)
                    Btn(t("Remove", "Ondoa"), { scope.launch { m.api.admin(JSONObject().put("action", "team-remove").put("name", p.optString("name"))); again() } }, small = true, light = true)
                }
            } else Text(t("To change or remove, edit PIN_USERS in Cloudflare, or add them here with an app PIN and remove the Cloudflare entry.", "Kubadilisha au kuondoa, hariri PIN_USERS kwenye Cloudflare, au waongeze hapa kwa PIN ya programu na uondoe kwenye Cloudflare."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 6.dp))
        }
    }
    msg?.let { (tone, text) -> Msg(text, tone) }
    Card(Modifier.padding(top = 14.dp)) {
        H2(t("Add a person", "Ongeza mtu"))
        Text(t("They get their own 4-digit PIN, shown once. Send it to them privately.", "Wanapata PIN yao ya tarakimu 4, inaonyeshwa mara moja. Wapelekee kwa siri."), style = MaterialTheme.typography.bodyMedium, color = C.muted)
        Field(name, { name = it }, t("Name", "Jina"), placeholder = t("e.g. Jessica Anderson", "mf. Jessica Anderson"))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Picker(t("Home country", "Nchi ya nyumbani"), home, listOf("" to t("All countries", "Nchi zote")) + COUNTRIES.toList(), { home = it }, Modifier.weight(1f))
            Field(email, { email = it }, t("E-mail (optional)", "Barua pepe (si lazima)"), Modifier.weight(1f))
        }
        Field(phone, { phone = it }, t("Phone (optional)", "Simu (si lazima)"), placeholder = "+254 712 345 678")
        Row(Modifier.padding(top = 8.dp).clickable { admin = !admin }, verticalAlignment = Alignment.CenterVertically) { Checkbox(admin, { admin = it }, colors = CheckboxDefaults.colors(checkedColor = C.blue)); Text(t("Admin (sees the Office)", "Msimamizi (anaona Ofisi)"), style = MaterialTheme.typography.bodyMedium, color = C.text) }
        Btn(t("Add and create PIN", "Ongeza na utengeneze PIN"), {
            scope.launch {
                val x = m.api.admin(JSONObject().put("action", "team-add").put("name", name.trim()).put("home", home).put("email", email.trim()).put("phone", phone.trim()).put("admin", admin))
                if (x.ok) { shown = name.trim() to x.json.optString("pin"); name = ""; email = ""; phone = ""; admin = false; again() }
                else msg = "bad" to (if (x.reason == "exists") t("Someone with that name is already on the team.", "Mtu mwenye jina hilo tayari yuko kwenye timu.") else reasonText(x.reason))
            }
        }, Modifier.padding(top = 10.dp), icon = "plus", enabled = name.isNotBlank())
    }
    Card(Modifier.padding(top = 14.dp)) {
        H2(t("E-mail alerts", "Taarifa kwa barua pepe"))
        var tm by remember { mutableStateOf<String?>(null) }
        Btn(t("Send a test e-mail", "Tuma barua pepe ya majaribio"), { scope.launch { tm = t("Sending…", "Inatuma…"); val x = m.api.admin(JSONObject().put("action", "test-mail")); tm = if (x.ok) t("Sent ✓ Check the inbox.", "Imetumwa ✓ Angalia barua pepe.") else t("Not sent: the e-mail settings aren’t finished in Cloudflare.", "Haijatumwa: mipangilio ya barua pepe haijakamilika Cloudflare.") } }, small = true, light = true, icon = "upload")
        Msg(tm)
    }
}

/** Rough boxes around each country, to spot points that landed in the wrong place. */
private val BOUNDS = mapOf("ke" to doubleArrayOf(-4.9, 5.1, 33.8, 42.0), "tz" to doubleArrayOf(-11.8, -0.9, 29.2, 40.6), "ug" to doubleArrayOf(-1.6, 4.3, 29.5, 35.1), "mw" to doubleArrayOf(-17.2, -9.3, 32.6, 36.0))

@Composable
private fun Cleanup(s: Snapshot, open: (String) -> Unit) {
    val outside = s.projects.filter { p -> p.gps != null && BOUNDS[p.ck]?.let { b -> p.gps.lat !in b[0]..b[1] || p.gps.lng !in b[2]..b[3] } == true }
    val dup = s.projects.filter { it.gps != null }.groupBy { "%.5f,%.5f".format(it.gps!!.lat, it.gps.lng) }.values.filter { it.size > 1 }.flatten()
    val lists = listOf(
        Triple(t("No location", "Hakuna mahali"), t("Open each one and pin it (Find a place by name helps).", "Fungua kila mmoja na uweke mahali (Tafuta mahali kwa jina inasaidia)."), s.projects.filter { it.gps == null }),
        Triple(t("Location outside its country", "Mahali nje ya nchi yake"), t("Usually latitude and longitude swapped, or a typing slip.", "Mara nyingi latitudo na longitudo zimebadilishana, au kosa la kuandika."), outside),
        Triple(t("Same point as another project", "Mahali sawa na mradi mwingine"), t("Probably a copied location. Check which one is right.", "Huenda mahali hapa pamenakiliwa. Hakikisha kipi ni sahihi."), dup),
        Triple(t("No photo", "Hakuna picha"), t("Add a photo to the “Photos of the project” column in monday.com.", "Ongeza picha kwenye safu ya “Photos of the project” katika monday.com."), s.projects.filter { it.photo == null }),
        Triple(t("No visit for over a year", "Hakuna ziara kwa zaidi ya mwaka"), t("Only visits made with the app are known.", "Ziara zilizofanywa kwa programu tu ndizo zinajulikana."), s.projects.filter { it.gps != null && ageDays(s, it) > 365 }),
    )
    val looks = listOf("pinq" to "blue", "globe" to "coral", "copy" to "amber", "camera" to "purple", "clock" to "teal")
    val tags: List<(Project) -> String?> = listOf(
        { null },
        { p -> "%.4f, %.4f".format(p.gps!!.lat, p.gps.lng) },
        { null },
        { null },
        { p -> s.visits[p.id]?.at?.let { checkedAgo(it) } },
    )
    lists.forEachIndexed { i, (title, hint, rows) ->
        var more by remember(title) { mutableStateOf(false) }
        val hu = C.hue(looks[i].second)
        Card(Modifier.padding(top = 12.dp).rise(i).testTag("clean_$i"), pad = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(looks[i].first, hu.fg, hu.bg, 42.dp); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = C.ink)
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = C.muted)
                }
                Spacer(Modifier.width(8.dp))
                if (rows.isEmpty()) Tag("✓", C.okBg, C.ok) else Tag("${rows.size}", hu.bg, hu.fg)
            }
            if (rows.isEmpty()) {
                Text(t("All good here.", "Hakuna tatizo hapa."), style = MaterialTheme.typography.labelLarge, color = C.ok, modifier = Modifier.padding(top = 10.dp))
                return@Card
            }
            Spacer(Modifier.height(8.dp))
            if (i == 2) {
                // same point: the projects that share it, together
                val groups = rows.groupBy { "%.4f, %.4f".format(it.gps!!.lat, it.gps.lng) }.entries.toList()
                (if (more) groups else groups.take(2)).forEach { (pt, ps) ->
                    Row(Modifier.padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Ic.get("pin"), null, tint = hu.fg, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp))
                        Text(t("${ps.size} projects at $pt", "Miradi ${ps.size} mahali $pt"), style = MaterialTheme.typography.labelMedium, color = hu.fg)
                    }
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, C.line, RoundedCornerShape(16.dp))) {
                        ps.forEachIndexed { j, p -> if (j > 0) HorizontalDivider(color = C.line); CleanRow(p, null) { open(p.id) } }
                    }
                }
                if (groups.size > 2) MoreBtn(more, groups.size) { more = !more }
            } else {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, C.line, RoundedCornerShape(16.dp))) {
                    (if (more) rows.take(120) else rows.take(4)).forEachIndexed { j, p -> if (j > 0) HorizontalDivider(color = C.line); CleanRow(p, tags[i](p)) { open(p.id) } }
                }
                if (rows.size > 4) MoreBtn(more, rows.size) { more = !more }
            }
        }
    }
}

private fun checkedAgo(iso: String): String = runCatching {
    val days = java.time.Duration.between(java.time.Instant.parse(iso), java.time.Instant.now()).toDays()
    if (days >= 365) t("${days / 365} yr ago", "mwaka ${days / 365} uliopita") else t("$days days ago", "siku $days zilizopita")
}.getOrDefault("")

/** One project in a clean-up list: its kind, name, where it is, and what to fix. */
@Composable
private fun CleanRow(p: Project, tag: String?, onClick: () -> Unit) {
    val (ic, hk) = typeLook(p); val hu = C.hue(hk)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp).testTag("clean_row"), verticalAlignment = Alignment.CenterVertically) {
        IconTile(ic, hu.fg, hu.bg, 36.dp); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.5.sp), color = C.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(p.area, p.country).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (tag != null) Text(tag, style = MaterialTheme.typography.labelSmall, color = C.muted, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(8.dp))
        Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun MoreBtn(more: Boolean, n: Int, onClick: () -> Unit) {
    Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(99.dp)).background(C.tint).clickable(onClick = onClick).padding(vertical = 11.dp).testTag("clean_more"),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text(if (more) t("Show fewer", "Onyesha machache") else t("Show all $n", "Onyesha zote $n"), style = MaterialTheme.typography.labelLarge, color = C.blue2)
        Spacer(Modifier.width(6.dp)); Icon(Ic.get("down"), null, tint = C.blue2, modifier = Modifier.size(16.dp).rotate(if (more) 180f else 0f))
    }
}

private fun csv(rows: List<List<Any?>>): String = rows.joinToString("\r\n") { r -> r.joinToString(",") { v -> val x = (v ?: "").toString(); if (x.any { it == ',' || it == '"' || it == '\n' }) "\"" + x.replace("\"", "\"\"") + "\"" else x } }

@Composable
private fun Export(m: AppModel, s: Snapshot) {
    val ctx = LocalContext.current
    val scope = rememberScope()
    var msg by remember { mutableStateOf<String?>(null) }
    fun share(name: String, text: String) {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, name); f.writeText("﻿" + text)
        val uri = FileProvider.getUriForFile(ctx, "org.rafikioutreach.pin.files", f)
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), name))
    }
    val stamp = LocalDate.now().toString()
    Text(t("CSV files open in Excel and Google Sheets.", "Faili za CSV zinafunguka kwenye Excel na Google Sheets."), style = MaterialTheme.typography.bodyMedium, color = C.muted)
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Btn(t("Projects with locations and status", "Miradi yenye mahali na hali yake"), {
            share("rafiki-projects-$stamp.csv", csv(listOf(listOf("ID", "Project", "Country", "Area", "Type", "Status", "Latitude", "Longitude", "Last visit", "Condition", "Open repair")) +
                s.projects.map { p -> val v = s.visits[p.id]; listOf(p.id, p.title, p.country, p.area, p.type, p.status, p.gps?.lat, p.gps?.lng, v?.at?.take(10), v?.state, s.repairs[p.id]?.state) }))
        }, Modifier.fillMaxWidth(), light = true, icon = "upload")
        Btn(t("Site visits with readings", "Ziara pamoja na vipimo"), {
            scope.launch {
                msg = t("Gathering visit records…", "Inakusanya rekodi za ziara…")
                val x = m.api.adminGet("export"); val a = x.json.optJSONArray("visits")
                if (a == null) { msg = reasonText(x.reason); return@launch }
                msg = null
                val rows = (0 until a.length()).map { a.getJSONObject(it) }.map { v -> val r = v.optJSONObject("readings") ?: JSONObject(); val q = v.optJSONObject("quality") ?: JSONObject()
                    listOf(v.optString("at").take(10), v.optString("id"), s.projects.firstOrNull { it.id == v.optString("id") }?.title, v.optString("by"), v.optString("state"), v.optString("urgency"), r.opt("strokes"), r.opt("flow"), r.opt("users"), r.opt("queue"), q.optString("ecoli")) }
                share("rafiki-visits-$stamp.csv", csv(listOf(listOf("Date", "ID", "Project", "By", "Condition", "Urgency", "Strokes to fill 20 L", "Flow (L/min)", "People using it", "Queue (min)", "E. coli")) + rows))
            }
        }, Modifier.fillMaxWidth(), light = true, icon = "upload")
        Btn(t("Open repairs and jobs", "Matengenezo na kazi zilizo wazi"), {
            share("rafiki-repairs-$stamp.csv", csv(listOf(listOf("ID", "Project", "Condition", "Urgency", "Reported", "By", "Problems", "Parts", "Est. KES", "Step", "Assigned to", "Due", "Budget KES")) +
                s.repairs.map { (id, r) -> listOf(id, s.projects.firstOrNull { it.id == id }?.title ?: r.title, r.state, r.urgency, r.at.take(10), r.by, r.problems.joinToString("; "), r.parts, r.cost, r.step, r.assignee, r.due, r.budget) }))
        }, Modifier.fillMaxWidth(), light = true, icon = "upload")
        Btn(t("Everything the team did (last 13 months)", "Kila kitu timu ilifanya (miezi 13 iliyopita)"), {
            scope.launch {
                if (!m.loadActivity()) { msg = reasonText("offline"); return@launch }
                share("rafiki-activity-$stamp.csv", csv(listOf(listOf("Date", "Time", "Who", "What", "Project", "Result")) + (m.activity.value ?: emptyList()).map { e -> listOf(e.optString("at").take(10), e.optString("at").drop(11).take(5), e.optString("by"), e.optString("a"), e.optString("t"), e.optString("s")) }))
            }
        }, Modifier.fillMaxWidth(), light = true, icon = "upload")
        Msg(msg)
    }
}

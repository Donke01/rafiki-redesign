package org.rafikioutreach.pin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.Hm
import org.rafikioutreach.pin.data.Itin
import org.rafikioutreach.pin.data.OnTheDay
import org.rafikioutreach.pin.data.Timeline
import org.rafikioutreach.pin.data.strs
import org.rafikioutreach.pin.data.trips
import org.rafikioutreach.pin.data.words
import org.rafikioutreach.pin.t

/*
 * Trips (feat-itina): where they start.
 *  - Office › Trips (admins): every field trip with its live progress, the office's late/re-plan notices, "Plan a trip",
 *    and the group trips (volunteers, donors) with "New group trip". Office › Vehicles is in Vehicles.kt.
 *  - Me › My trips (everyone): their own trips, today's next stop, and "Plan a trip".
 * The trip pages themselves open on top of the tab (MainActivity.kt: sub "trip_new", "trip:<id>", "gtrip:<id>",
 * "gtrip_new", "gview:<id>"), so back closes them one step at a time.
 */

/** Asks the app to open a trip page on top of the current tab (picked up by Shell in MainActivity.kt). */
object TripNav {
    var open by mutableStateOf<String?>(null)
    fun go(sub: String) { open = sub }
}

/** "28–30 Oct", "28 Oct", or "30 Oct – 2 Nov". */
fun tripDates(from: String, to: String): String {
    val a = Dates.parse(from) ?: return from; val b = Dates.parse(to) ?: return Dates.day(a)
    return when {
        a == b -> Dates.weekday(a)
        a.monthValue == b.monthValue && a.year == b.year -> "${a.dayOfMonth}–" + Dates.day(b)
        else -> Dates.day(a, a.year != b.year && a.year != Dates.today().year) + " – " + Dates.day(b)
    }
}

/** Planned, on the road, done… as a coloured tag. */
@Composable
fun TripStatus(status: String) {
    val (label, hu) = when (status) {
        "active" -> t("On the road", "Safarini") to C.hue("green")
        "done" -> t("Done", "Imekamilika") to C.hue("slate")
        "cancelled" -> t("Cancelled", "Imeghairiwa") to Hue(C.bad, C.badBg)
        "draft" -> t("Draft", "Rasimu") to C.hue("amber")
        else -> t("Planned", "Imepangwa") to C.hue("blue")
    }
    Tag(label, hu.bg, hu.fg)
}

/** Tabs across the width whose labels shrink to fit (never cut mid-word, e.g. "Gharama" at 360 dp). */
@Composable
fun TripTabs(items: List<Pair<String, String>>, sel: String, tag: String, onPick: (String) -> Unit) {
    val track = if (C.dark) Color(0xFF22303A) else Color(0xFFE8EDF1)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(99.dp)).background(track).padding(4.dp)) {
        items.forEach { (k, l) ->
            val on = k == sel
            Box(Modifier.weight(1f).clip(RoundedCornerShape(99.dp)).background(if (on) C.card else Color.Transparent).clickable(role = Role.Tab) { onPick(k) }
                .heightIn(min = 40.dp).padding(horizontal = 6.dp, vertical = 9.dp).testTag(tag + k), contentAlignment = Alignment.Center) {
                FitWords(l, MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), if (on) C.ink else C.muted, lines = 1)
            }
        }
    }
}

/** A date picked with a calendar, shown as "Wed 28 Oct" (never ISO on screen); the value stays "2026-10-28". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDate(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp), color = C.ink, maxLines = 1, modifier = Modifier.padding(bottom = 6.dp, top = 12.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.card).border(1.dp, C.edge, RoundedCornerShape(16.dp)).clickable(role = Role.Button) { open = true }.heightIn(min = 52.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (value.isBlank()) t("Choose a date", "Chagua tarehe") else Dates.weekday(value), color = if (value.isBlank()) C.muted else C.text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f))
            Icon(Ic.get("cal"), null, tint = C.blue)
        }
    }
    if (open) {
        val st = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = runCatching { java.time.LocalDate.parse(value).atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }.getOrNull())
        androidx.compose.material3.DatePickerDialog({ open = false }, confirmButton = {
            androidx.compose.material3.TextButton({ st.selectedDateMillis?.let { onChange(java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()) }; open = false }) { Text(t("OK", "Sawa")) }
        }, dismissButton = { androidx.compose.material3.TextButton({ open = false }) { Text(t("Cancel", "Ghairi")) } }) { androidx.compose.material3.DatePicker(st) }
    }
}

/** A short title over a section, in capitals. */
@Composable
fun TripHead(text: String, modifier: Modifier = Modifier, extra: String = "") {
    Row(modifier.padding(top = 20.dp, bottom = 4.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.1.sp, fontWeight = FontWeight.ExtraBold), color = C.muted, modifier = Modifier.weight(1f, fill = false))
        if (extra.isNotBlank()) { Spacer(Modifier.width(8.dp)); Text(extra, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold), color = C.blue2, modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(C.tint).padding(horizontal = 8.dp, vertical = 2.dp)) }
    }
}

/** A small chip with an icon: "62 km tarmac · 8 km murram", "Rain 25 mm", "~1 h 52". */
@Composable
fun TChip(text: String, icon: String? = null, hue: Hue = C.hue("slate"), tag: String = "") {
    Row(Modifier.clip(RoundedCornerShape(99.dp)).background(hue.bg).padding(horizontal = 8.dp, vertical = 3.dp).then(if (tag.isNotBlank()) Modifier.testTag(tag) else Modifier), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(Ic.get(icon), null, tint = hue.fg, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(4.dp)) }
        Text(text, color = hue.fg, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

/** Colours for a risk level. */
@Composable
fun riskHue(level: String): Hue = when (level) { "difficult" -> Hue(C.bad, C.badBg); "caution" -> Hue(C.warn, C.warnBg); "info" -> C.hue("slate"); else -> Hue(C.ok, C.okBg) }
fun riskName(level: String) = when (level) { "difficult" -> t("Difficult", "Ngumu"); "caution" -> t("Take care", "Tahadhari"); else -> t("Good roads", "Barabara nzuri") }

/** A bottom sheet with a title and a close X (its own window: the phone's back closes it first). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripSheet(tag: String, title: String, close: () -> Unit, content: @Composable ColumnScope.(hide: () -> Unit) -> Unit) {
    val scope = rememberScope()
    val state = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val hide: () -> Unit = { sheetHide(scope, state, close) }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = close, sheetState = state, containerColor = C.card, modifier = Modifier.testTag(tag)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = C.ink, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp)); SheetCloseX(tag + "_x", hide)
            }
            content(hide)
        }
    }
}

/** A row of choices that wrap onto more lines (never a word broken in the middle). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Choices(opts: List<Pair<String, String>>, sel: String, tag: String, modifier: Modifier = Modifier, pick: (String) -> Unit) {
    FlowRow(modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        opts.forEach { (k, l) ->
            val on = k == sel
            Text(l, color = if (on) C.onFill else C.ink, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp), maxLines = 1, softWrap = false,
                modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(if (on) C.selected else C.card).border(1.dp, if (on) C.selected else C.line, RoundedCornerShape(99.dp))
                    .clickable(role = Role.RadioButton) { pick(k) }.heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 10.dp).testTag(tag + k))
        }
    }
}

/** One trip in a list: title, dates and who, its status, and done / late / skipped while on the road. */
@Composable
fun TripRow(row: JSONObject, kept: Itin?, i: Int, onClick: () -> Unit) {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val shape = RoundedCornerShape(20.dp)
    val id = row.optString("id")
    Row(Modifier.padding(top = 10.dp).rise(i, step = 40).press(src).fillMaxWidth().clip(shape).background(if (C.glassy) C.glass2 else C.card)
        .border(if (C.glassy) 1.dp else 2.dp, if (C.glassy) C.glassEdge else C.line, shape)
        .clickable(interactionSource = src, indication = androidx.compose.material3.ripple(), role = Role.Button, onClick = onClick).padding(14.dp).testTag("trip_row_$id"),
        verticalAlignment = Alignment.CenterVertically) {
        val st = row.optString("status")
        val hu = C.hue(if (st == "active") "green" else "blue")
        IconTile("nav", hu.fg, hu.bg, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(row.optString("title").ifBlank { t("Trip", "Safari") }, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = C.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val people = row.optJSONArray("people").strs()
            Text(tripDates(row.optString("from"), row.optString("to")) + (if (people.isNotEmpty()) " · " + people.joinToString(", ") { it.replace(' ', '\u00A0') } else ""),
                style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TripStatus(st)
                if (kept != null && (st == "active" || st == "done")) {
                    val (done, late, skip) = OnTheDay.counts(kept)
                    Spacer(Modifier.width(6.dp))
                    Text(t("$done of ${kept.stops.size} done", "$done kati ya ${kept.stops.size} zimekamilika") + (if (late > 0) t(" · $late late", " · $late zimechelewa") else "") + (if (skip > 0) t(" · $skip skipped", " · $skip zimerukwa") else ""),
                        style = MaterialTheme.typography.labelSmall, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else if (row.optJSONArray("projects") != null) {
                    Spacer(Modifier.width(6.dp))
                    val n = row.optJSONArray("projects")!!.length()
                    Text(if (n == 1) t("1 stop", "kituo 1") else t("$n stops", "vituo $n"), style = MaterialTheme.typography.labelSmall, color = C.muted, maxLines = 1)
                }
            }
        }
        Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(18.dp))
    }
}

/** Office › Trips: field trips (with live progress and the office's notices) and group trips. */
@Composable
fun TripsSection(m: AppModel) {
    val repo = m.trips
    var pane by rememberSaveable { mutableStateOf("field") }
    // back from Group trips: Field trips again (the first side), then the Office grid
    BackStep(pane != "field") { pane = "field" }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        SmallSeg(listOf("field" to t("Field trips", "Safari za kazi"), "group" to t("Group trips", "Safari za vikundi")), pane, tag = "trips_pane_") { pane = it }
    }
    if (pane == "group") { GroupTripsList(m); return }
    val list by repo.list.collectAsState()
    val tick by repo.tick.collectAsState()
    var alerts by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var filter by rememberSaveable { mutableStateOf("now") }
    LaunchedEffect(Unit) { repo.loadList(); loading = false; alerts = runCatching { repo.alerts() }.getOrDefault(emptyList()) }
    Btn(t("Plan a trip", "Panga safari"), { TripNav.go("trip_new") }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("trips_plan"), icon = "plus")
    if (alerts.isNotEmpty()) {
        TripHead(t("From the road", "Kutoka safarini"), extra = "${alerts.size}")
        alerts.take(5).forEach { a ->
            val hu = if (a.optString("kind") == "late") Hue(C.warn, C.warnBg) else C.hue("blue")
            Row(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(hu.bg).clickable { TripNav.go("trip:" + a.optString("id")) }.padding(12.dp).testTag("trip_alert"), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.get(if (a.optString("kind") == "skip") "x" else "clock"), null, tint = hu.fg, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(words(a).ifBlank { a.optString("title") }, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = C.ink)
                    Text(a.optString("title") + " · " + a.optString("by") + " · " + whenText(a.optString("at")), style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    Choices(listOf("now" to t("Planned and on the road", "Zilizopangwa na safarini"), "done" to t("Done", "Zilizokamilika"), "all" to t("All", "Zote")), filter, "trips_f_", Modifier.padding(top = 6.dp)) { filter = it }
    val rows = list.filter { r -> when (filter) { "now" -> r.optString("status") in setOf("planned", "active", "draft", ""); "done" -> r.optString("status") in setOf("done", "cancelled"); else -> true } }
    if (rows.isEmpty()) EmptyDrop(if (loading) t("Loading…", "Inapakia…") else t("No trips here yet. Plan one above.", "Bado hakuna safari hapa. Panga moja hapo juu."))
    tick.let { _ -> rows.forEachIndexed { i, r -> TripRow(r, repo.cached(r.optString("id")), i) { TripNav.go("trip:" + r.optString("id")) } } }
}

/** Me › My trips: today's next stop up top, then their trips and "Plan a trip". */
@Composable
fun MyTrips(m: AppModel) {
    val repo = m.trips
    val list by repo.list.collectAsState()
    val tick by repo.tick.collectAsState()
    LaunchedEffect(Unit) { repo.loadList("?mine=1") }
    val me = m.prefs.name
    val today = Dates.today().toString()
    val mine = list.filter { r -> r.optString("status") !in setOf("done", "cancelled") && (r.optString("owner").equals(me, true) || r.optJSONArray("people").strs().any { it.equals(me, true) }) || r.optString("status").isBlank() }
        .ifEmpty { if (list.isEmpty()) repo.kept().filter { it.isOn(me) && it.status !in setOf("done", "cancelled") }.map { JSONObject().put("id", it.id).put("title", it.title).put("from", it.from).put("to", it.to).put("status", it.status) } else emptyList() }
    Card(Modifier.padding(top = 14.dp).testTag("my_trips")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            H2(t("My trips", "Safari zangu"), wide = false)
            if (mine.isNotEmpty()) Tag("${mine.size}", C.tint, C.deep)
        }
        tick.let { _ ->
            val on = mine.firstNotNullOfOrNull { r -> repo.cached(r.optString("id"))?.takeIf { it.from <= today && today <= it.to } }
            if (on != null) NextStopCard(m, on, compact = true) { TripNav.go("trip:" + on.id) }
        }
        if (mine.isEmpty()) Text(t("Plan the sites you'll visit: the app puts them in order, times each day and checks the roads and weather.", "Panga maeneo utakayotembelea: programu inayapanga kwa mpangilio, inaweka muda kila siku na inaangalia barabara na hali ya hewa."),
            style = MaterialTheme.typography.bodyMedium, color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        mine.take(4).forEachIndexed { i, r -> TripRow(r, repo.cached(r.optString("id")), i) { TripNav.go("trip:" + r.optString("id")) } }
        Btn(t("Plan a trip", "Panga safari"), { TripNav.go("trip_new") }, Modifier.padding(top = 12.dp).fillMaxWidth().testTag("me_plan_trip"), icon = "plus", small = true, light = mine.isNotEmpty(), outline = true)
    }
}

/** The "Next stop" card: where, when, Directions and Check in (the visit flow). [compact] in Me. */
@Composable
fun NextStopCard(m: AppModel, it: Itin, compact: Boolean = false, checkIn: ((String) -> Unit)? = null, skip: ((String) -> Unit)? = null, open: (() -> Unit)? = null) {
    val today = Dates.today().toString()
    val sid = OnTheDay.next(it, today)
    val d = OnTheDay.dayIndex(it, today)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val shape = RoundedCornerShape(22.dp)
    Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(shape).background(DeepBrush).then(if (open != null) Modifier.clickable(onClick = open) else Modifier).padding(16.dp).testTag("next_stop")) {
        Text(t("NEXT STOP", "KITUO KIJACHO"), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp, fontWeight = FontWeight.ExtraBold), color = Color(0xFF9FD3F7))
        val s = sid?.let { x -> it.stop(x) }
        if (s == null || d < 0) {
            Text(if (d < 0) t("No stops today", "Hakuna vituo leo") else t("All of today's stops are done", "Vituo vyote vya leo vimekamilika"), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold), color = Color.White)
            return@Column
        }
        Text(s.name, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val now = nowMin()
        val row = Timeline.day(it, d, now).rows.firstOrNull { r -> r.kind == "stop" && r.sid == sid }
        val drive = Timeline.day(it, d, now).rows.firstOrNull { r -> r.kind == "drive" && r.to == sid }?.leg
        val late = OnTheDay.lateBy(it, today, now)
        Text(listOfNotNull(row?.let { r -> t("Arrive about ", "Fika takriban ") + Hm.text(r.at) }, drive?.let { l -> "~" + Hm.dur(l.min) }).joinToString(" · ") +
            (if (late >= 15) t(" · $late min behind", " · nyuma dakika $late") else ""), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
        if (!compact) Row(Modifier.padding(top = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WhiteBtn(t("Directions", "Maelekezo"), "nav", "next_dir", Modifier.weight(1f)) { s.ll?.let { ll -> openDirections(ctx, ll.lat, ll.lng) } }
            WhiteBtn(t("Check in", "Ingia"), "check", "next_checkin", Modifier.weight(1f), strong = true) { checkIn?.invoke(s.sid) }
        }
        if (!compact && skip != null) Text(t("Skip this stop", "Ruka kituo hiki"), color = Color.White, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(99.dp)).clickable(role = Role.Button) { skip(s.sid) }.heightIn(min = 44.dp).padding(12.dp).testTag("next_skip"))
    }
}

@Composable
private fun WhiteBtn(label: String, icon: String, tag: String, modifier: Modifier, strong: Boolean = false, onClick: () -> Unit) {
    Row(modifier.clip(RoundedCornerShape(99.dp)).background(if (strong) Color.White else Color.White.copy(alpha = 0.16f)).clickable(role = Role.Button, onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 10.dp).testTag(tag),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(Ic.get(icon), null, tint = if (strong) Color(0xFF0A78C2) else Color.White, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
        Text(label, color = if (strong) Color(0xFF0A78C2) else Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, maxLines = 1, softWrap = false)
    }
}

/** Google Maps directions to a point (works with Google's offline maps too), or the browser. */
fun openDirections(ctx: android.content.Context, lat: Double, lng: Double) {
    val nav = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("google.navigation:q=$lat,$lng"))
    runCatching { ctx.startActivity(nav) }.onFailure {
        runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lng"))) }
    }
}

/** A person's first letters in a small circle (people on a trip). */
@Composable
fun Initials(name: String, size: Dp = 28.dp) {
    val hu = C.hue(listOf("blue", "green", "purple", "teal", "coral", "slate")[(name.lowercase().fold(7L) { h, c -> (h * 31 + c.code) % 4294967296L } % 6).toInt()])
    Box(Modifier.size(size).clip(CircleShape).background(hu.bg), contentAlignment = Alignment.Center) {
        Text(name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }, color = hu.fg, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.38f).sp)
    }
}

/** Office › Trips › Group trips: the list and "New group trip" (GroupTrips.kt has the editor). */
@Composable
fun GroupTripsList(m: AppModel) {
    val repo = m.trips
    var items by remember { mutableStateOf<List<JSONObject>?>(null) }
    LaunchedEffect(Unit) { items = repo.groups() ?: emptyList() }
    Text(t("Volunteer and donor trips: the day-by-day plan, the group, costs, and a page to share with them.", "Safari za wanaojitolea na wafadhili: mpango wa kila siku, kikundi, gharama, na ukurasa wa kuwashirikisha."),
        style = MaterialTheme.typography.bodyMedium, color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
    Btn(t("New group trip", "Safari mpya ya kikundi"), { TripNav.go("gtrip_new") }, Modifier.padding(top = 12.dp).fillMaxWidth().testTag("gtrip_new"), icon = "plus")
    val l = items
    if (l == null) { Text(t("Loading…", "Inapakia…"), color = C.muted, modifier = Modifier.padding(top = 12.dp)); return }
    if (l.isEmpty()) EmptyDrop(t("No group trips yet.", "Bado hakuna safari za vikundi."))
    l.forEachIndexed { i, g ->
        val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val shape = RoundedCornerShape(20.dp)
        Row(Modifier.padding(top = 10.dp).rise(i, step = 40).press(src).fillMaxWidth().clip(shape).background(if (C.glassy) C.glass2 else C.card).border(if (C.glassy) 1.dp else 2.dp, if (C.glassy) C.glassEdge else C.line, shape)
            .clickable(interactionSource = src, indication = androidx.compose.material3.ripple(), role = Role.Button) { TripNav.go("gtrip:" + g.optString("id")) }.padding(14.dp).testTag("gtrip_row_" + g.optString("id")),
            verticalAlignment = Alignment.CenterVertically) {
            val hu = C.hue("purple"); IconTile("people", hu.fg, hu.bg, 44.dp); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(g.optString("name"), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = C.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val n = g.optInt("participants")
                Text(tripDates(g.optString("from"), g.optString("to")) + " · " + (org.rafikioutreach.pin.data.COUNTRIES[g.optString("country")] ?: g.optString("country").uppercase()) + " · " + (if (n == 1) t("1 person", "mtu 1") else t("$n people", "watu $n")),
                    style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 2)
                if (g.optBoolean("public")) Box(Modifier.padding(top = 6.dp)) { Tag(t("On the website", "Kwenye tovuti"), C.okBg, C.ok) }
            }
            Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(18.dp))
        }
    }
}

/** The trip pages, opened on top of a tab (MainActivity.kt): [sub] says which. */
@Composable
fun TripPage(m: AppModel, sub: String, go: (String) -> Unit, swap: (String) -> Unit, openProject: (String, Boolean) -> Unit, back: () -> Unit) {
    when {
        sub == "trip_new" -> PlanTrip(m, { id -> swap("trip:$id") }, back)
        sub.startsWith("trip:") -> TripScreen(m, sub.removePrefix("trip:"), go, openProject, back)
        sub == "gtrip_new" -> GroupTripEditor(m, null, { id -> swap("gtrip:$id") }, go, openProject, back)
        sub.startsWith("gtrip:") -> GroupTripEditor(m, sub.removePrefix("gtrip:"), { }, go, openProject, back)
        sub.startsWith("gview:") -> GroupTripPreview(m, sub.removePrefix("gview:"), back)
    }
}

package org.rafikioutreach.pin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.COUNTRIES
import org.rafikioutreach.pin.data.DUE_DAYS
import org.rafikioutreach.pin.data.Geo
import org.rafikioutreach.pin.data.Hm
import org.rafikioutreach.pin.data.Itin
import org.rafikioutreach.pin.data.LatLng
import org.rafikioutreach.pin.data.Snapshot
import org.rafikioutreach.pin.data.Timeline
import org.rafikioutreach.pin.data.itinReason
import org.rafikioutreach.pin.data.objs
import org.rafikioutreach.pin.data.strs
import org.rafikioutreach.pin.data.trips
import org.rafikioutreach.pin.data.words
import org.rafikioutreach.pin.t
import java.time.LocalDate

/** A suggested or picked stop while planning. */
private data class Pick(val id: String, val name: String, val area: String, val ck: String, val why: List<String>, val score: Double, val ll: LatLng?)

/** What the planner keeps between its steps (and while the phone turns). */
private class PlanDraft {
    val picked = mutableStateListOf<String>()
    var title by mutableStateOf("")
    var from by mutableStateOf(LocalDate.now().plusDays(1).toString())
    var to by mutableStateOf(LocalDate.now().plusDays(1).toString())
    val people = mutableStateListOf<String>()
    var start by mutableStateOf("office")
    var placeName by mutableStateOf("")
    var place by mutableStateOf<LatLng?>(null)
    var end by mutableStateOf("same")
    var endName by mutableStateOf("")
    var endPlace by mutableStateOf<LatLng?>(null)
    var hStart by mutableStateOf("07:30")
    var hEnd by mutableStateOf("17:30")
    var vehicle by mutableStateOf("")
    var passengers by mutableStateOf("")
    var plan by mutableStateOf<JSONObject?>(null)
}
private val drafts = HashMap<String, PlanDraft>()

/**
 * Plan a trip in a few taps (Office for admins, Me for field officers): 1 choose the stops (or "Suggest stops", ranked
 * and explained), 2 days, people, start and end, vehicle, 3 the plan the server builds (shortest drive, split across
 * days by working hours, overnight towns), then Save. Back goes to the step before, keeping what was chosen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanTrip(m: AppModel, created: (String) -> Unit, back: () -> Unit) {
    val snap = m.snap.collectAsState().value ?: return
    val fix by m.fix.collectAsState()
    val key = rememberSaveable { "d" + System.nanoTime() }
    val d = remember(key) { drafts.getOrPut(key) { PlanDraft().apply { if (!snap.admin || m.prefs.name.isNotBlank()) people.add(m.prefs.name) } } }
    var step by rememberSaveable { mutableStateOf(1) }
    BackStep(step > 1) { step-- }
    val scroll = rememberScrollState()
    LaunchedEffect(step) { scroll.scrollTo(0) }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp).testTag("plan_trip")) {
        Text(t("Plan a trip", "Panga safari"), style = MaterialTheme.typography.headlineLarge, color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        StepDots(step)
        when (step) {
            1 -> PlanStops(m, snap, d, fix?.ll) { step = 2 }
            2 -> PlanDetails(m, snap, d, fix?.ll) { step = 3 }
            else -> PlanPreview(m, d, fix?.ll, created, { step = 1 }) { d.start = "place"; step = 2 }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StepDots(step: Int) {
    val names = listOf(t("Stops", "Vituo"), t("Days and people", "Siku na watu"), t("Your plan", "Mpango wako"))
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        names.forEachIndexed { i, n ->
            val on = i + 1 == step; val past = i + 1 < step
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(99.dp)).background(if (on || past) C.blue else C.line))
                FitWords(n, MaterialTheme.typography.labelSmall.copy(fontWeight = if (on) FontWeight.ExtraBold else FontWeight.Normal), if (on) C.ink else C.muted, Modifier.padding(top = 4.dp).testTag("plan_step_${i + 1}"), lines = 1)
            }
        }
    }
}

/** The sites the phone itself would suggest when the server can't be reached (repairs, due a visit, water test due). */
private fun localSuggest(s: Snapshot, ck: String): List<Pick> = s.projects.filter { it.gps != null && (ck.isBlank() || it.ck == ck) }.mapNotNull { p ->
    val why = ArrayList<String>(); var score = 0.0
    s.repairs[p.id]?.let { r -> why += if (r.urgency == "urgent") t("open repair · urgent", "matengenezo yaliyo wazi · ya dharura") else t("open repair", "matengenezo yaliyo wazi"); score += if (r.urgency == "urgent") 50 else 30 }
    val age = ageDays(s, p)
    if (age >= DUE_DAYS) { why += if (age > 1e5) t("never visited with the app", "haijawahi kutembelewa kwa programu") else t("due a visit · ${(age / 30).toInt()} months", "Ziara inahitajika · miezi ${(age / 30).toInt()}"); score += minOf(40.0, age / 10) }
    if (testDue(s, p)) { why += t("water test due", "kipimo cha maji kinahitajika"); score += 10 }
    if (why.isEmpty()) null else Pick(p.id, p.title, p.area, p.ck, why, score, p.gps)
}.sortedByDescending { it.score }.take(30)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanStops(m: AppModel, s: Snapshot, d: PlanDraft, here: LatLng?, next: () -> Unit) {
    val scope = rememberScope()
    val cks = s.projects.filter { it.gps != null }.map { it.ck }.filter { it.isNotBlank() }.distinct()
    var ck by rememberSaveable { mutableStateOf(m.prefs.country.takeIf { it in cks } ?: cks.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "") }
    val areas = remember { mutableStateListOf<String>() }
    var areaList by remember { mutableStateOf(listOf<String>()) }
    var sugg by remember { mutableStateOf<List<Pick>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var q by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(ck) { areaList = s.projects.filter { it.ck == ck && it.gps != null }.map { it.area }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(12); areas.clear(); sugg = null }
    Text(t("Pick the sites to visit, or let the app suggest them.", "Chagua maeneo ya kutembelea, au acha programu ipendekeze."), style = MaterialTheme.typography.bodyMedium, color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
    if (cks.size > 1) Choices(cks.map { it to (COUNTRIES[it] ?: it.uppercase()) }, ck, "plan_ck_", Modifier.padding(top = 4.dp)) { ck = it }
    if (areaList.isNotEmpty()) {
        TripHead(t("Areas", "Maeneo"))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            areaList.forEach { a -> Chip(a, if (a in areas) "check" else null, a in areas, { if (a in areas) areas.remove(a) else areas.add(a); sugg = null }, tag = "plan_area_$a", hue = "blue") }
        }
    }
    Btn(t("Suggest stops", "Pendekeza vituo"), {
        scope.launch {
            busy = true; msg = null
            val r = m.trips.suggest(JSONObject().put("ck", ck).put("areas", JSONArray(areas.toList())).put("limit", 30).apply { here?.let { put("from", JSONObject().put("lat", it.lat).put("lng", it.lng)) } })
            sugg = if (r.ok) r.json.optJSONArray("items").objs().map { o ->
                Pick(o.optString("id"), o.optString("name"), o.optString("area"), o.optString("ck"), o.optJSONArray("why").let { a -> if (a == null) emptyList() else (0 until a.length()).map { words(a.opt(it)) } }, o.optDouble("score", 0.0),
                    if (o.has("lat")) LatLng(o.optDouble("lat"), o.optDouble("lng")) else null)
            } else localSuggest(s, ck).filter { areas.isEmpty() || it.area in areas }.also { if (r.status != 0) msg = itinReason(r.reason) }
            if (r.ok) r.json.optJSONArray("areas").strs().takeIf { it.isNotEmpty() }?.let { a -> areaList = (areaList + a).distinct().take(16) }
            busy = false
        }
    }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("plan_suggest"), icon = "spark", light = sugg != null, outline = true, enabled = !busy)
    Msg(msg, "bad")
    sugg?.let { l ->
        TripHead(t("Suggested", "Yanayopendekezwa"), extra = "${l.size}")
        if (l.isEmpty()) Text(t("Nothing needs a visit here right now.", "Hakuna kinachohitaji ziara hapa kwa sasa."), color = C.muted, modifier = Modifier.padding(4.dp))
        l.forEach { p -> PickRow(p.id, p.name, p.area, p.why, p.id in d.picked, here?.let { h -> p.ll?.let { Geo.dist(Geo.metres(h, it), m.prefs.miles) } }, "plan_sug_") { if (p.id in d.picked) d.picked.remove(p.id) else d.picked.add(p.id) } }
    }
    TripHead(t("Or pick sites", "Au chagua maeneo"))
    Field(q, { q = it }, t("Find a site", "Tafuta eneo"), Modifier.testTag("plan_q"), placeholder = t("Name or area", "Jina au eneo"))
    val rows = s.projects.filter { p -> p.gps != null && (ck.isBlank() || p.ck == ck) && (areas.isEmpty() || p.area in areas) && (q.isBlank() || (p.title + " " + p.area).contains(q.trim(), true)) }
        .sortedWith(compareBy({ it.id !in d.picked }, { it.title })).take(if (q.isBlank()) 15 else 40)
    rows.forEach { p -> PickRow(p.id, p.title, p.area, emptyList(), p.id in d.picked, null) { if (p.id in d.picked) d.picked.remove(p.id) else d.picked.add(p.id) } }
    Spacer(Modifier.height(12.dp))
    Btn(if (d.picked.isEmpty()) t("Choose at least one stop", "Chagua angalau kituo kimoja") else if (d.picked.size == 1) t("Next: 1 stop", "Endelea: kituo 1") else t("Next: ${d.picked.size} stops", "Endelea: vituo ${d.picked.size}"),
        next, Modifier.fillMaxWidth().testTag("plan_next1"), icon = "arrow", enabled = d.picked.isNotEmpty())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickRow(id: String, name: String, area: String, why: List<String>, on: Boolean, dist: String?, tag: String = "plan_pick_", flip: () -> Unit) {
    Row(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (on) C.tint else C.card).border(1.dp, if (on) C.blue else C.line, RoundedCornerShape(16.dp))
        .clickable(role = Role.Checkbox, onClick = flip).padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp).testTag(tag + id), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(on, { flip() }, colors = CheckboxDefaults.colors(checkedColor = C.blue))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, color = C.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (area.isNotBlank() || dist != null) Text(listOfNotNull(area.takeIf { it.isNotBlank() }, dist).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 1)
            if (why.isNotEmpty()) FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                why.forEach { w -> TChip(w, hue = if ("urgent" in w || "dharura" in w) Hue(C.bad, C.badBg) else C.hue("amber")) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanDetails(m: AppModel, s: Snapshot, d: PlanDraft, here: LatLng?, next: () -> Unit) {
    val mates by m.team.collectAsState()
    val vehicles by m.trips.vehicles.collectAsState()
    var best by remember { mutableStateOf<String?>(null) }
    var addBike by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { launch { m.loadTeam() }; launch { m.trips.loadVehicles() } }
    // the driest day for these sites (weather only, from the trip engine): shown when choosing days
    LaunchedEffect(d.from, d.to, d.picked.size) {
        val days = runCatching { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(d.from), LocalDate.parse(d.to)).toInt() + 3 }.getOrDefault(5).coerceIn(3, 14)
        val r = m.api.req("GET", "/api/trip/week?ids=" + d.picked.joinToString(",") + "&start=" + d.from + "&days=" + days)
        best = r.json.optJSONObject("bestDay")?.let { b -> words(b.opt("name")).ifBlank { Dates.weekday(b.optString("date")) } + " · " + roadWord(b.optString("roadLevel")) }
    }
    if (d.title.isBlank()) d.title = s.projects.filter { it.id in d.picked }.map { it.area }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key?.let { t("Trip to $it", "Safari ya $it") } ?: ""
    Field(d.title, { d.title = it.take(80) }, t("Name of the trip", "Jina la safari"), Modifier.padding(top = 8.dp).testTag("plan_title"), placeholder = t("e.g. Moiben repairs", "mf. Matengenezo Moiben"))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TripDate(t("First day", "Siku ya kwanza"), d.from, { d.from = it; if (d.to < it) d.to = it }, Modifier.weight(1f).testTag("plan_from"))
        TripDate(t("Last day", "Siku ya mwisho"), d.to, { d.to = it }, Modifier.weight(1f).testTag("plan_to"))
    }
    best?.let { b -> Row(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.okBg).padding(10.dp).testTag("plan_best"), verticalAlignment = Alignment.CenterVertically) {
        Icon(Ic.get("sun"), null, tint = C.ok, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text(t("Best day for these roads: ", "Siku bora kwa barabara hizi: ") + b, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = C.ok)
    } }
    TripHead(t("People", "Watu"))
    val names = (listOf(m.prefs.name) + mates.orEmpty().map { it.name }).filter { it.isNotBlank() }.distinct()
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        names.forEach { n -> val me = n == m.prefs.name && !s.admin
            Chip(n, if (n in d.people) "check" else "plus", n in d.people, { if (!me) { if (n in d.people) d.people.remove(n) else d.people.add(n) } }, tag = "plan_p_$n", hue = "blue") }
    }
    TripHead(t("Start", "Kuanzia"))
    Choices(listOf("office" to t("The office", "Ofisi"), "here" to t("Where I am", "Nilipo"), "place" to t("Another place", "Mahali pengine")), d.start, "plan_start_") { d.start = it }
    if (d.start == "here" && here == null) Msg(t("Waiting for your location…", "Inasubiri mahali ulipo…"), "warn")
    if (d.start == "place") PlacePick(d.placeName, { d.placeName = it }, d.place ?: here, { d.place = it }, "plan_place")
    TripHead(t("End", "Mwisho"))
    Choices(listOf("same" to t("Back to the start", "Kurudi mwanzo"), "place" to t("Another place", "Mahali pengine")), d.end, "plan_end_") { d.end = it }
    if (d.end == "place") PlacePick(d.endName, { d.endName = it }, d.endPlace ?: here, { d.endPlace = it }, "plan_endplace")
    TripHead(t("Working hours", "Saa za kazi"))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Field(d.hStart, { d.hStart = it.take(5) }, t("Leave at", "Ondoka saa"), Modifier.weight(1f).testTag("plan_hs"), placeholder = "07:30")
        Field(d.hEnd, { d.hEnd = it.take(5) }, t("Back by", "Rudi kabla ya"), Modifier.weight(1f).testTag("plan_he"), placeholder = "17:30")
    }
    TripHead(t("Vehicle", "Gari"))
    Picker(t("Vehicle", "Gari"), d.vehicle, listOf("" to t("Choose later", "Chagua baadaye")) + vehicles.map { v -> v.vid to (v.name + " · " + v.typeName()) }, { d.vehicle = it }, Modifier.testTag("plan_vehicle"))
    if (!s.admin) Text(t("Add my motorbike", "Ongeza pikipiki yangu"), color = C.blue2, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(99.dp)).clickable(role = Role.Button) { addBike = true }.heightIn(min = 44.dp).padding(horizontal = 6.dp, vertical = 12.dp).testTag("plan_bike"))
    Field(d.passengers, { d.passengers = it.filter { c -> c.isDigit() }.take(2) }, t("People in the vehicle", "Watu ndani ya gari"), number = true, placeholder = "${maxOf(1, d.people.size)}")
    val ok = Hm.parse(d.hStart) != null && Hm.parse(d.hEnd) != null && d.from.isNotBlank() && d.to >= d.from && d.people.isNotEmpty() && (d.start != "place" || d.place != null) && (d.start != "here" || here != null)
    Btn(t("Build the plan", "Tengeneza mpango"), { d.plan = null; next() }, Modifier.padding(top = 18.dp).fillMaxWidth().testTag("plan_next2"), icon = "compass", enabled = ok)
    if (addBike) VehicleSheet(m, null, bikeOnly = true, close = { addBike = false }) { vid -> if (vid != null) d.vehicle = vid }
}

fun roadWord(level: String) = when (level) { "dry" -> t("dry roads", "barabara kavu"); "light" -> t("a little rain", "mvua kidogo"); "wet" -> t("wet roads", "barabara zenye maji"); "heavy" -> t("heavy rain", "mvua kubwa"); else -> level }

/** A place by name with a small map to drop it on. */
@Composable
fun PlacePick(name: String, onName: (String) -> Unit, at: LatLng?, onAt: (LatLng) -> Unit, tag: String) {
    Field(name, onName, t("Name of the place", "Jina la mahali"), Modifier.testTag(tag + "_name"), placeholder = t("e.g. Iten town", "mf. mji wa Iten"))
    Text(t("Move the map so the pin is on the place.", "Sogeza ramani ili alama iwe juu ya mahali."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 8.dp))
    Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(200.dp).clip(RoundedCornerShape(18.dp)).testTag(tag + "_map")) { PickMap(at ?: LatLng(0.52, 35.27), onAt, Modifier.fillMaxSize()) }
}

/** The request for /api/itin/optimise (and create). */
private fun planBody(m: AppModel, d: PlanDraft, here: LatLng?): JSONObject = JSONObject()
    .put("from", d.from).put("to", d.to).put("people", JSONArray(d.people.toList())).put("ck", m.snap.value?.projects?.firstOrNull { it.id in d.picked }?.ck ?: "")
    .put("start", when (d.start) {
        "here" -> JSONObject().put("kind", "here").put("name", t("Where I was", "Nilipokuwa")).apply { here?.let { put("lat", it.lat); put("lng", it.lng) } }
        "place" -> JSONObject().put("kind", "place").put("name", d.placeName.ifBlank { t("Start", "Mwanzo") }).apply { d.place?.let { put("lat", it.lat); put("lng", it.lng) } }
        else -> JSONObject().put("kind", "office")
    })
    .put("end", if (d.end == "place") JSONObject().put("kind", "place").put("name", d.endName.ifBlank { t("End", "Mwisho") }).apply { d.endPlace?.let { put("lat", it.lat); put("lng", it.lng) } } else JSONObject().put("kind", "same"))
    .put("hours", JSONObject().put("start", Hm.text(Hm.parse(d.hStart) ?: 450)).put("end", Hm.text(Hm.parse(d.hEnd) ?: 1050)))
    .put("stops", JSONArray(d.picked.toList())).put("weather", true)
    .apply { if (d.vehicle.isNotBlank()) put("vehicleId", d.vehicle); d.passengers.toIntOrNull()?.let { put("passengers", it) } }

@Composable
private fun PlanPreview(m: AppModel, d: PlanDraft, here: LatLng?, created: (String) -> Unit, restart: () -> Unit, pickPlace: () -> Unit) {
    val scope = rememberScope()
    var err by remember { mutableStateOf<String?>(null) }
    var noOffice by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (d.plan != null) return@LaunchedEffect
        val r = m.trips.optimise(planBody(m, d, here))
        noOffice = r.reason == "no-office"
        if (r.ok) d.plan = r.json.optJSONObject("plan") else err = if (r.status == 0) t("No signal: the plan is built on the server. Try again when you're connected.", "Hakuna mtandao: mpango hutengenezwa kwenye seva. Jaribu tena ukiunganishwa.") else itinReason(r.reason)
    }
    val p = d.plan
    if (p == null) {
        if (err == null) Column(Modifier.fillMaxWidth().padding(top = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.CircularProgressIndicator(color = C.blue)
            Text(t("Finding the shortest drive and checking the roads…", "Inatafuta njia fupi zaidi na kuangalia barabara…"), color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        }
        Msg(err, "bad")
        if (noOffice) Btn(t("Choose where to start", "Chagua mahali pa kuanzia"), pickPlace, Modifier.padding(top = 12.dp).fillMaxWidth().testTag("plan_pick_place"), icon = "pin")
        if (err != null) Btn(t("Change the stops", "Badilisha vituo"), restart, Modifier.padding(top = 12.dp).fillMaxWidth(), light = true, outline = true, icon = "back")
        return
    }
    val it = Itin(p.put("title", d.title))
    Text(d.title.ifBlank { t("Your plan", "Mpango wako") }, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold), color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
    Text(tripDates(d.from, d.to) + " · " + (if (it.stops.size == 1) t("1 stop", "kituo 1") else t("${it.stops.size} stops", "vituo ${it.stops.size}")) + " · " + "%,.0f km".format(java.util.Locale.US, it.days.indices.sumOf { i -> Timeline.day(it, i).km }),
        style = MaterialTheme.typography.bodyMedium, color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    it.warnings.forEach { w -> WarnRow(w, null) }
    if (it.unplanned.isNotEmpty()) Msg(t("Didn't fit in the days: ", "Hazikutosha kwenye siku: ") + it.unplanned.mapNotNull { s -> it.stop(s)?.name }.joinToString(", "), "warn")
    it.days.indices.forEach { i -> DayCard(it, i, null, editable = false) }
    it.cost?.let { c -> Text(t("Estimated cost: ", "Gharama inayokadiriwa: ") + org.rafikioutreach.pin.data.Money.kes(c.estTotal), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.padding(top = 12.dp).testTag("plan_cost")) }
    Btn(t("Save the trip", "Hifadhi safari"), {
        scope.launch {
            saving = true; err = null
            val (x, why) = m.trips.create(planBody(m, d, here).put("title", d.title.ifBlank { t("Trip", "Safari") }).put("plan", p))
            saving = false
            if (x != null) created(x.id) else err = why
        }
    }, Modifier.padding(top = 16.dp).fillMaxWidth().testTag("plan_save"), icon = "check", ok = true, enabled = !saving)
    Msg(err, "bad")
}

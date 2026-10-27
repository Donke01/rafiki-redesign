package org.rafikioutreach.pin.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.FuelLog
import org.rafikioutreach.pin.data.Hm
import org.rafikioutreach.pin.data.Itin
import org.rafikioutreach.pin.data.ItinEdit
import org.rafikioutreach.pin.data.LatLng
import org.rafikioutreach.pin.data.Money
import org.rafikioutreach.pin.data.OnTheDay
import org.rafikioutreach.pin.data.TExtra
import org.rafikioutreach.pin.data.TNight
import org.rafikioutreach.pin.data.TProgress
import org.rafikioutreach.pin.data.TRow
import org.rafikioutreach.pin.data.TWarn
import org.rafikioutreach.pin.data.Timeline
import org.rafikioutreach.pin.data.TripShare
import org.rafikioutreach.pin.data.itinReason
import org.rafikioutreach.pin.data.perL
import org.rafikioutreach.pin.data.trips
import org.rafikioutreach.pin.t
import java.io.File
import java.time.Instant

/** What a trip screen changes: re-timed on the phone at once, then sent (or kept until there is signal). */
class TripCtl(val m: AppModel, val id: String, private val scope: CoroutineScope, private val get: () -> Itin?, private val set: (Itin) -> Unit) {
    var msg by mutableStateOf<String?>(null)
    val itin get() = get()
    fun edit(local: Itin, body: JSONObject) {
        set(local); msg = null
        scope.launch { val e = m.trips.change(local, body); if (e != null) msg = e; m.trips.cached(id)?.let(set) }
    }
    /** A change that needs the server (re-order, swap, share…): shows its answer. */
    fun ask(body: JSONObject, done: (org.rafikioutreach.pin.data.Reply) -> Unit = {}) = scope.launch {
        val r = m.trips.ask(id, body)
        if (!r.ok) msg = if (r.status == 0) itinReason("offline") else itinReason(r.reason)
        m.trips.cached(id)?.let(set); done(r)
    }
    /** Fresh from the server (new costs after fuel was added). */
    fun refresh() = scope.launch { m.trips.open(id).first?.let(set) }
    fun stops(local: Itin, changed: Collection<String> = emptyList()) = edit(local, ItinEdit.stopsBody(local, changed))

    /** A warning's one-tap fix. */
    fun fix(w: TWarn) {
        val it = itin ?: return; val f = w.fix ?: return
        when (f.optString("action")) {
            "move" -> { val sid = f.optString("sid"); val from = it.days.indexOfFirst { d -> sid in d.stops }; if (from >= 0) stops(ItinEdit.toDay(it, from, sid, f.optInt("toDay")), listOf(sid)) }
            "swap" -> { val a = f.optInt("day"); val b = f.optInt("with"); edit(ItinEdit.swapDays(it, a, b), JSONObject().put("action", "swap-days").put("day", a).put("with", b)) }
            "extend" -> ask(JSONObject().put("action", "update").put("to", runCatching { java.time.LocalDate.parse(it.to).plusDays(1).toString() }.getOrDefault(it.to))) { r -> if (r.ok) ask(JSONObject().put("action", "optimise").put("keepDays", true)) }
        }
    }
    fun fixLabel(w: TWarn): String? = w.fix?.let { f ->
        when (f.optString("action")) {
            "move" -> itin?.days?.getOrNull(f.optInt("toDay"))?.let { d -> t("Move to ", "Hamishia ") + Dates.weekday(d.date) }
            "swap" -> itin?.days?.getOrNull(f.optInt("with"))?.let { d -> t("Swap with ", "Badilisha na ") + Dates.weekday(d.date) }
            "extend" -> t("Add a day", "Ongeza siku")
            else -> null
        }
    }
}

/** The time of day in minutes (tests set their own). */
object TripClock { var now: () -> Int = { java.time.LocalTime.now(Dates.zone()).let { it.hour * 60 + it.minute } } }
internal fun nowMin(): Int = TripClock.now()

/** One field trip: on the day (next stop, check in, running late), the plan, costs, packing and sharing. */
@Composable
fun TripScreen(m: AppModel, id: String, go: (String) -> Unit, openProject: (String, Boolean) -> Unit, back: () -> Unit) {
    val repo = m.trips
    val snap = m.snap.collectAsState().value
    var trip by remember(id) { mutableStateOf(repo.cached(id)) }
    var prog by remember(id) { mutableStateOf<TProgress?>(null) }
    var err by remember(id) { mutableStateOf<String?>(null) }
    var loaded by remember(id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctl = remember(id) { TripCtl(m, id, scope, { trip }, { trip = it }) }
    val me = m.prefs.name
    val admin = snap?.admin == true
    val today = Dates.today().toString()
    LaunchedEffect(id) {
        while (true) {
            val watch = admin && trip?.isOn(me) != true
            val (x, p, e) = repo.open(id, progress = watch)
            if (x != null) trip = x; prog = p ?: prog; err = e; loaded = true
            // the office's live view follows the day: a fresh look every minute while the trip is on
            if (!(watch && trip?.let { it.from <= today && today <= it.to } == true)) break
            kotlinx.coroutines.delay(60_000)
        }
    }
    val online by OfflineMaps.online.collectAsState()
    val it = trip
    if (it == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (!loaded) t("Loading…", "Inapakia…") else err ?: t("No signal, and this trip isn't saved on this phone yet.", "Hakuna mtandao, na safari hii bado haijahifadhiwa kwenye simu hii."), color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.testTag("trip_none"))
        }
        return
    }
    val onTrip = it.isOn(me)
    val live = it.from <= today && today <= it.to && it.status !in setOf("done", "cancelled")
    val editable = it.canEdit && it.status !in setOf("done", "cancelled")
    var tab by rememberSaveable { mutableStateOf("plan") }
    BackStep(tab != "plan") { tab = "plan" }
    var day by rememberSaveable(id) { mutableStateOf(OnTheDay.dayIndex(it, today).coerceAtLeast(0)) }
    if (day > it.days.lastIndex) day = maxOf(0, it.days.lastIndex)
    var replan by remember { mutableStateOf<Int?>(null) }
    var road by remember { mutableStateOf(false) }
    var skipAsk by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    var mapMsg by remember { mutableStateOf<String?>(null) }

    // km driven: the GPS track while on the road (sent in batches)
    if (live && onTrip) TrackPoints(m, ctl)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("trip_screen")) {
        Text(it.title.ifBlank { t("Trip", "Safari") }, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold), color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("trip_title"))
        Text(tripDates(it.from, it.to) + " · " + (if (it.stops.size == 1) t("1 stop", "kituo 1") else t("${it.stops.size} stops", "vituo ${it.stops.size}")),
            style = MaterialTheme.typography.bodyMedium, color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            TripStatus(it.status)
            Spacer(Modifier.width(8.dp))
            it.people.take(4).forEachIndexed { k, p -> Box(Modifier.offset(x = (-6 * k).dp)) { Initials(p, 28.dp) } }
            if (it.people.size > 4) Text("+${it.people.size - 4}", style = MaterialTheme.typography.labelSmall, color = C.muted)
        }
        if (!online) Banner("offline", t("No signal: showing the trip saved on this phone. Changes are sent when you're back online.", "Hakuna mtandao: inaonyesha safari iliyohifadhiwa kwenye simu hii. Mabadiliko yatatumwa ukipata mtandao."), C.hue("slate"), "trip_offline")
        if (it.waiting > 0) Banner("clock", if (it.waiting == 1) t("1 change waiting to send", "Badiliko 1 linasubiri kutumwa") else t("${it.waiting} changes waiting to send", "Mabadiliko ${it.waiting} yanasubiri kutumwa"), Hue(C.warn, C.warnBg), "trip_waiting")
        if (!it.canEdit) Banner("info", t("You can see this trip but not change it.", "Unaweza kuona safari hii lakini huwezi kuibadilisha."), C.hue("slate"), "trip_readonly")
        Msg(ctl.msg, "bad")

        // --- on the day --------------------------------------------------------------------------------------
        if (live && onTrip) {
            val late = OnTheDay.lateBy(it, today, nowMin())
            NextStopCard(m, it, checkIn = { sid -> checkIn(m, ctl, sid, openProject) }, skip = { sid -> skipAsk = sid })
            if (late >= 30 && OnTheDay.dayIndex(it, today) >= 0) Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.warnBg).clickable { replan = OnTheDay.dayIndex(it, today) }.padding(12.dp).testTag("trip_late"), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.get("clock"), null, tint = C.warn, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                Text(t("Running $late min late. Move the rest?", "Umechelewa dakika $late. Hamisha vilivyobaki?"), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = C.warn, modifier = Modifier.weight(1f))
                Icon(Ic.get("chev"), null, tint = C.warn, modifier = Modifier.size(18.dp))
            }
            Row(Modifier.padding(top = 10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(t("Report the road", "Ripoti barabara"), { road = true }, Modifier.weight(1f).testTag("trip_road"), small = true, light = true, outline = true, icon = "alert")
                Btn(t("Re-plan", "Panga upya"), { replan = OnTheDay.dayIndex(it, today).coerceAtLeast(0) }, Modifier.weight(1f).testTag("trip_replan"), small = true, light = true, outline = true, icon = "refresh")
            }
        }
        if (live && !onTrip && prog != null) ProgressCard(it, prog!!)

        if (it.status == "planned" && editable && onTrip && it.from <= today) Btn(t("Start the trip", "Anza safari"), { ctl.ask(JSONObject().put("action", "update").put("status", "active")) }, Modifier.padding(top = 10.dp).fillMaxWidth().testTag("trip_start"), icon = "nav", ok = true)

        Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.Center) {
            TripTabs(listOf("plan" to t("Plan", "Mpango"), "costs" to t("Costs", "Gharama"), "pack" to t("Packing", "Mzigo"), "share" to t("Share", "Shiriki")), tab, "trip_tab_") { tab = it }
        }
        when (tab) {
            "costs" -> CostsPane(m, it, ctl, editable)
            "pack" -> PackingPane(it, ctl, editable)
            "share" -> SharePane(m, it, ctl, admin)
            else -> {
                if (it.days.size > 1) Choices(it.days.mapIndexed { i, d -> "$i" to Dates.weekday(d.date) }, "$day", "trip_day_", Modifier.padding(top = 6.dp)) { k -> day = k.toInt() }
                if (it.days.isNotEmpty()) {
                    TripMap(it, day, prog)
                    DayCard(it, day, if (editable) ctl else null, editable = editable, progress = prog, openProject = openProject, live = live && onTrip && it.days[day].date == today)
                }
                if (it.unplanned.isNotEmpty()) {
                    TripHead(t("Didn't fit", "Hazikutosha"), extra = "${it.unplanned.size}")
                    it.unplanned.mapNotNull { s -> it.stop(s) }.forEach { s -> Text("· " + s.name, style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.padding(start = 6.dp, top = 2.dp)) }
                }
                it.warnings.forEach { w -> WarnRow(w, ctl) }
                if (editable) {
                    var adding by remember { mutableStateOf(false) }
                    Row(Modifier.padding(top = 14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Btn(t("Add a stop", "Ongeza kituo"), { adding = true }, Modifier.weight(1f).testTag("trip_add_stop"), small = true, light = true, outline = true, icon = "plus")
                        Btn(t("Shortest order", "Mpangilio mfupi"), { ctl.ask(JSONObject().put("action", "optimise").put("keepDays", true)) }, Modifier.weight(1f).testTag("trip_optimise"), small = true, light = true, outline = true, icon = "compass")
                    }
                    if (adding) AddStopSheet(m, it, day, ctl) { adding = false }
                }
                Btn(t("Save the map for this trip", "Hifadhi ramani ya safari hii"), { mapMsg = saveTripMap(it) ; ctl.packTickOffline() }, Modifier.padding(top = 10.dp).fillMaxWidth().testTag("trip_save_map"), small = true, light = true, outline = true, icon = "offline")
                Msg(mapMsg, "ok")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    replan?.let { d -> ReplanSheet(it, d, ctl) { replan = null } }
    if (road) RoadReportSheet(m) { road = false }
    skipAsk?.let { sid -> SkipSheet(it, sid, ctl, { replan = it }) { skipAsk = null } }
}

/** The "offline map saved" packing line ticks itself. */
private fun TripCtl.packTickOffline() { val it = itin ?: return; it.packing.firstOrNull { p -> p.key == "std:offline" && !p.done }?.let { p -> edit(ItinEdit.packTick(it, p.key, true), JSONObject().put("action", "packing").put("items", JSONArray().put(JSONObject().put("key", p.key).put("done", true)))) } }

/** Keeps the map of every stop on the trip (and the start, end and overnight towns) on the phone. */
fun saveTripMap(it: Itin): String {
    val pts = it.stops.mapNotNull { s -> s.ll } + listOfNotNull(it.start?.ll, it.end?.ll) + it.days.mapNotNull { d -> d.night?.ll }
    if (pts.isEmpty()) return t("No locations to save yet.", "Bado hakuna mahali pa kuhifadhi.")
    val pad = 0.04
    val box = org.osmdroid.util.BoundingBox(pts.maxOf { p -> p.lat } + pad, pts.maxOf { p -> p.lng } + pad, pts.minOf { p -> p.lat } - pad, pts.minOf { p -> p.lng } - pad)
    val bulk = OfflineMaps.office != null
    val a = OfflineMaps.add(it.title.ifBlank { t("Trip", "Safari") }, box, bulk) ?: return t("That area is too big to keep. Save each day instead.", "Eneo hilo ni kubwa mno kuhifadhi. Hifadhi kila siku badala yake.")
    return if (bulk) { OfflineMaps.startDownload(a); t("Saving the map for this trip… (Me › Offline maps)", "Inahifadhi ramani ya safari hii… (Mimi › Ramani za nje ya mtandao)") }
    else { OfflineMaps.flyTo.value = a; t("Saved as an offline area: open the map and look over it to keep it.", "Imehifadhiwa kama eneo la nje ya mtandao: fungua ramani na uiangalie ili kuihifadhi.") }
}

@Composable
fun Banner(icon: String, text: String, hu: Hue, tag: String) {
    Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(hu.bg).padding(horizontal = 12.dp, vertical = 10.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Icon(Ic.get(icon), null, tint = hu.fg, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = hu.fg)
    }
}

/** Check in: the arrival time is kept (real leg times for the trip engine), then the visit flow for that project. */
private fun checkIn(m: AppModel, ctl: TripCtl, sid: String, openProject: (String, Boolean) -> Unit) {
    val it = ctl.itin ?: return; val s = it.stop(sid) ?: return
    val at = Instant.now().toString(); val f = m.fix.value
    ctl.edit(ItinEdit.mark(it, sid, "done", at, m.prefs.name), JSONObject().put("action", "checkin").put("sid", sid).put("at", at).apply { f?.let { x -> put("lat", x.ll.lat); put("lng", x.ll.lng) } })
    if (s.pid.isNotBlank()) openProject(s.pid, true)
}

/** GPS track points while on the road, sent 20 at a time (km driven for the summary). */
@Composable
private fun TrackPoints(m: AppModel, ctl: TripCtl) {
    val fix by m.fix.collectAsState()
    val pts = remember { ArrayList<JSONArray>() }
    var last by remember { mutableStateOf<LatLng?>(null) }
    LaunchedEffect(fix) {
        val f = fix ?: return@LaunchedEffect
        if (last != null && org.rafikioutreach.pin.data.Geo.metres(last!!, f.ll) < 150) return@LaunchedEffect
        last = f.ll; pts += JSONArray().put(f.ll.lat).put(f.ll.lng).put(f.at)
        if (pts.size >= 20) { val send = JSONArray(pts.toList()); pts.clear(); ctl.ask(JSONObject().put("action", "track").put("points", send)) }
    }
}

/** The office's live view: done, late or skipped per stop, and where people are (when sharing their location). */
@Composable
private fun ProgressCard(it: Itin, p: TProgress) {
    Card(Modifier.padding(top = 12.dp).testTag("trip_progress")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BreathDot(C.ok, true, 10.dp); Spacer(Modifier.width(8.dp))
            Text(t("Live", "Moja kwa moja") + (if (p.now.isNotBlank()) " · " + p.now else ""), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = C.ink, modifier = Modifier.weight(1f))
            // from the live check-ins when the server sent them, else from the trip itself
            val st = p.stops.values.map { x -> x.optString("state") }
            val (done, late, skip) = if (st.isNotEmpty()) Triple(st.count { x -> x == "done" }, st.count { x -> x == "late" }, st.count { x -> x == "skipped" }) else OnTheDay.counts(it)
            Text(t("$done done", "$done zimekamilika") + (if (late > 0) t(" · $late late", " · $late zimechelewa") else "") + (if (skip > 0) t(" · $skip skipped", " · $skip zimerukwa") else ""), style = MaterialTheme.typography.labelMedium, color = C.muted)
        }
        p.where.forEach { (name, _, at) -> Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Initials(name, 24.dp); Spacer(Modifier.width(8.dp))
            Text(name + " · " + t("seen ", "alionekana ") + ago(at), style = MaterialTheme.typography.bodySmall, color = C.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } }
    }
}

/** A map of the day: its stops numbered by colour (done green, skipped grey), the people sharing their location. */
@Composable
private fun TripMap(it: Itin, day: Int, prog: TProgress?) {
    val stops = it.dayStops(day).filter { s -> s.ll != null }
    if (stops.isEmpty()) return
    val dots = stops.map { s -> Dot(s.sid, s.ll!!, when { s.status == "skipped" -> 0xFF8A99A6.toInt(); !s.open -> 0xFF1C7C4A.toInt(); else -> 0xFF0A78C2.toInt() }, false, s.name) } +
        (prog?.where ?: emptyList()).map { (n, ll, _) -> Dot("p:$n", ll, 0xFFD2553A.toInt(), true, n) }
    val lines = it.days[day].stops.let { sids -> (listOf(it.ends(day).first) + sids + it.ends(day).second).zipWithNext().mapNotNull { (a, b) -> it.legs["$a>$b"]?.geometry?.takeIf { g -> g.size > 1 } } }
    Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(170.dp).clip(RoundedCornerShape(20.dp)).testTag("trip_map")) {
        ProjectsMap(dots, null, {}, Modifier.fillMaxSize(), fitKey = "$day:${stops.size}", onMap = { mv ->
            mv.overlays.removeAll { o -> o is org.osmdroid.views.overlay.Polyline }
            lines.forEach { g -> mv.overlays.add(0, org.osmdroid.views.overlay.Polyline().apply { setPoints(g.map { p -> org.osmdroid.util.GeoPoint(p.lat, p.lng) }); outlinePaint.color = 0xCC0A78C2.toInt(); outlinePaint.strokeWidth = 8f }) }
        })
    }
}

/** A warning with its one-tap fix. */
@Composable
fun WarnRow(w: TWarn, ctl: TripCtl?) {
    val hu = riskHue(w.level)
    Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(hu.bg).padding(12.dp).testTag("trip_warn_" + w.code)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Ic.get(if (w.level == "info") "info" else "alert"), null, tint = hu.fg, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(w.text, style = (if (w.level == "info") MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium).copy(fontWeight = FontWeight.SemiBold), color = hu.fg, modifier = Modifier.weight(1f))
        }
        val label = ctl?.fixLabel(w)
        if (label != null) Text(label, color = hu.fg, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold), maxLines = 1, softWrap = false,
            modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(99.dp)).border(1.5.dp, hu.fg, RoundedCornerShape(99.dp)).clickable(role = Role.Button) { ctl.fix(w) }.heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 9.dp).testTag("trip_fix_" + w.code))
    }
}

/**
 * A day: its numbers, warnings, the timeline (leave → drive → stop → lunch → … → return) and tonight's lodging. With
 * [ctl] stops can be dragged by their handle, tapped for more (lock the time, move to another day, remove) and every
 * time changes at once.
 */
@Composable
fun DayCard(it: Itin, i: Int, ctl: TripCtl?, editable: Boolean, progress: TProgress? = null, openProject: ((String, Boolean) -> Unit)? = null, live: Boolean = false) {
    val d = it.days.getOrNull(i) ?: return
    val plan = Timeline.day(it, i, if (live) nowMin() else null)
    var sheet by remember { mutableStateOf<String?>(null) }
    var night by remember { mutableStateOf(false) }
    // dragging: which stop, how far, and where each stop row sits
    var dragSid by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val tops = remember { mutableStateMapOf<String, Pair<Float, Float>>() }
    Card(Modifier.padding(top = 12.dp).testTag("trip_daycard_$i"), pad = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Dates.weekday(d.date), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = C.ink)
                Text("%,.0f km".format(java.util.Locale.US, plan.km) + " · " + t("driving ", "kuendesha ") + Hm.dur(plan.driveMin) + " · " + t("back ", "kurudi ") + Hm.text(plan.endsAt),
                    style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.testTag("trip_day_sum_$i"))
            }
            val rh = riskHue(d.risk); TChip(riskName(d.risk), if (d.risk == "ok") "check" else "alert", rh)
        }
        Timeline.warnings(it, i, plan).forEach { w -> WarnRow(w, ctl) }
        Spacer(Modifier.height(6.dp))
        val stopRows = plan.rows.filter { r -> r.kind == "stop" }.map { r -> r.sid }
        plan.rows.forEachIndexed { k, r ->
            when (r.kind) {
                "leave" -> PointRow(Hm.text(r.at), t("Leave", "Ondoka") + " · " + it.placeName(r.from).ifBlank { t("start", "mwanzo") }, "nav")
                "drive" -> LegRow(r, k)
                "wait" -> Text(Hm.text(r.at) + "–" + Hm.text(r.until) + "  " + t("Wait ${r.until - r.at}\u00A0min", "Subiri dakika\u00A0${r.until - r.at}"), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(start = 48.dp, top = 2.dp, bottom = 2.dp))
                "lunch" -> PointRow(Hm.text(r.at) + "–" + Hm.text(r.until), t("Lunch", "Chakula cha mchana"), "leaf", muted = true)
                "skipped" -> it.stop(r.sid)?.let { s -> PointRow("", t("Skipped", "Imerukwa") + " · " + s.name, "x", muted = true) }
                "overnight" -> PointRow(Hm.text(r.at), t("Overnight in ", "Kulala ") + it.placeName(r.to), "house")
                "return" -> PointRow(Hm.text(r.at), t("Back at ", "Kurudi ") + it.placeName(r.to).ifBlank { t("the start", "mwanzo") }, "nav")
                "stop" -> {
                    val s = it.stop(r.sid) ?: return@forEachIndexed
                    val dragging = dragSid == s.sid
                    Box(Modifier.zIndex(if (dragging) 1f else 0f).onGloballyPositioned { c -> tops[s.sid] = c.positionInParent().y to c.size.height.toFloat() }
                        .graphicsLayer { translationY = if (dragging) dragY else 0f; shadowElevation = if (dragging) 18f else 0f }) {
                        StopRow(it, r, progress, editable, live, onTap = { sheet = s.sid }, handle = if (editable && ctl != null && s.open) Modifier.pointerInput(s.sid, it.raw.hashCode()) {
                            detectDragGestures(onDragStart = { dragSid = s.sid; dragY = 0f }, onDragCancel = { dragSid = null; dragY = 0f }, onDragEnd = {
                                val (top, h) = tops[s.sid] ?: (0f to 1f)
                                val centre = top + dragY + h / 2
                                val others = stopRows.filter { x -> x != s.sid }
                                val to = others.count { x -> tops[x]?.let { (tp, hh) -> tp + hh / 2 < centre } == true }
                                val from = d.stops.indexOf(s.sid)
                                // the target among this day's stops (skipped ones keep their place)
                                val target = d.stops.filter { x -> x != s.sid }.let { all -> val before = others.take(to); if (before.isEmpty()) 0 else all.indexOf(before.last()) + 1 }
                                dragSid = null; dragY = 0f
                                if (target != from && to >= 0) ctl.stops(ItinEdit.move(it, i, from, target))
                            }) { ch, amount -> ch.consume(); dragY += amount.y }
                        } else null)
                    }
                }
            }
        }
        if (i < it.days.lastIndex) {
            val n = d.night
            Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.hue("purple").bg).then(if (editable && ctl != null) Modifier.clickable { night = true } else Modifier).padding(12.dp).testTag("trip_night_$i"), verticalAlignment = Alignment.CenterVertically) {
                val hu = C.hue("purple")
                Icon(Ic.get("house"), null, tint = hu.fg, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (n == null) t("Home tonight", "Nyumbani usiku huu") else t("Tonight: ", "Usiku huu: ") + n.town + (if (n.suggested) t(" (suggested)", " (inapendekezwa)") else ""), style = MaterialTheme.typography.titleSmall, color = hu.fg)
                    val sub = listOfNotNull(n?.lodging?.takeIf { it.isNotBlank() }, n?.kes?.takeIf { it > 0 }?.let { Money.kes(it) }, n?.why?.takeIf { it.isNotBlank() && n.lodging.isBlank() }).joinToString(" · ")
                    if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = hu.fg.copy(alpha = 0.85f))
                    else if (n != null && editable) Text(t("Add where you'll stay", "Ongeza utakapolala"), style = MaterialTheme.typography.bodySmall, color = hu.fg.copy(alpha = 0.85f))
                }
                if (editable && ctl != null) Icon(Ic.get("chev"), null, tint = hu.fg, modifier = Modifier.size(18.dp))
            }
        }
    }
    sheet?.let { sid -> if (ctl != null || openProject != null) StopSheet(it, i, sid, ctl, editable, live, openProject) { sheet = null } }
    if (night && ctl != null) LodgingSheet(it, i, ctl) { night = false }
}


@Composable
private fun PointRow(time: String, text: String, icon: String, muted: Boolean = false) {
    Row(Modifier.padding(vertical = 4.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(time, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = C.muted, maxLines = 1, softWrap = false, modifier = Modifier.width(if (time.length > 5) 92.dp else 44.dp))
        Icon(Ic.get(icon), null, tint = if (muted) C.muted else C.blue2, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (muted) C.muted else C.ink, modifier = Modifier.weight(1f))
    }
}

/** A drive: time, tarmac/murram, rain and the risk, as chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LegRow(r: TRow, k: Int) {
    val l = r.leg ?: return
    Row(Modifier.padding(start = 20.dp).fillMaxWidth().testTag("trip_leg_$k"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(2.dp).height(34.dp).background(C.line))
        Spacer(Modifier.width(26.dp))
        FlowRow(Modifier.weight(1f).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TChip(l.time.ifBlank { "~" + Hm.dur(l.min) } + (if (l.km > 0) " · " + "%,.0f km".format(java.util.Locale.US, l.km) + (if (l.estimate || !l.roads) t(" (estimate)", " (makadirio)") else "") else ""), "clock", C.hue("blue"))
            if (l.surface.isNotBlank()) TChip(l.surface, "map", C.hue("slate"))
            if (l.rain.isNotBlank()) TChip(l.rain, "cloud", if (l.roadLevel == "heavy" || l.roadLevel == "wet") Hue(C.warn, C.warnBg) else C.hue("teal"))
            if (l.risk != "ok") TChip(riskName(l.risk), "alert", riskHue(l.risk))
        }
    }
}

/** A stop: its times, name, status (done / late / skipped / next), lock and window warning, and the drag handle. */
@Composable
private fun StopRow(it: Itin, r: TRow, progress: TProgress?, editable: Boolean, live: Boolean, onTap: () -> Unit, handle: Modifier?) {
    val s = it.stop(r.sid) ?: return
    val p = progress?.stops?.get(s.sid)
    val state = p?.optString("state")?.takeIf { it.isNotBlank() } ?: when { s.status == "late" -> "late"; !s.open -> "done"; live && OnTheDay.next(it, Dates.today().toString()) == s.sid -> "next"; else -> "" }
    val shape = RoundedCornerShape(16.dp)
    val bg = if (r.warn != null) Hue(C.warn, C.warnBg).bg else C.tint
    Row(Modifier.padding(vertical = 4.dp).fillMaxWidth().clip(shape).background(bg).clickable(role = Role.Button, onClick = onTap).padding(start = 0.dp, end = 6.dp).testTag("trip_stop_" + s.sid),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(56.dp).padding(start = 8.dp, top = 10.dp, bottom = 10.dp)) {
            Text(Hm.text(r.at), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold), color = C.ink, maxLines = 1, softWrap = false, modifier = Modifier.testTag("trip_at_" + s.sid))
            Text(Hm.text(r.until), style = MaterialTheme.typography.labelSmall, color = C.muted, maxLines = 1, softWrap = false)
        }
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (s.fixed != null) { Icon(Ic.get("lock"), t("Time locked", "Muda umefungwa"), tint = C.blue2, modifier = Modifier.size(15.dp).testTag("trip_lock_" + s.sid)); Spacer(Modifier.width(4.dp)) }
                Text(s.name, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold), color = C.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            r.warn?.let { w -> Text(w.text, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = C.warn, modifier = Modifier.testTag("trip_stopwarn_" + s.sid)) }
            if (state.isNotBlank()) Box(Modifier.padding(top = 4.dp)) {
                when (state) {
                    "done" -> TChip(t("Done", "Imekamilika") + (p?.optString("at")?.takeIf { it.isNotBlank() && it != "null" }?.let { " $it" } ?: Timeline.clock(s.checkinAt)?.let { " " + Hm.text(it) } ?: ""), "check", Hue(C.ok, C.okBg), "trip_state_" + s.sid)
                    "late" -> TChip(t("Late", "Imechelewa") + (p?.optInt("lateMin")?.takeIf { it > 0 }?.let { t(" $it min", " dak $it") } ?: ""), "clock", Hue(C.warn, C.warnBg), "trip_state_" + s.sid)
                    "skipped" -> TChip(t("Skipped", "Imerukwa"), "x", C.hue("slate"), "trip_state_" + s.sid)
                    "next" -> TChip(t("Next", "Kinachofuata"), "arrow", C.hue("blue"), "trip_state_" + s.sid)
                    "due" -> TChip(t("Due now", "Sasa"), "clock", C.hue("amber"), "trip_state_" + s.sid)
                    else -> {}
                }
            }
        }
        if (handle != null) Box(Modifier.size(44.dp).then(handle).semantics { contentDescription = t("Drag to move", "Buruta kuhamisha") }.testTag("trip_drag_" + s.sid), contentAlignment = Alignment.Center) {
            Icon(Ic.get("list"), null, tint = C.muted, modifier = Modifier.size(20.dp))
        }
    }
}

/** A stop: why it's on the trip, its hours, the visit length, lock its time, move it, remove it, open the project. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StopSheet(it: Itin, day: Int, sid: String, ctl: TripCtl?, editable: Boolean, live: Boolean, openProject: ((String, Boolean) -> Unit)?, close: () -> Unit) {
    val s = it.stop(sid) ?: return
    val ctx = LocalContext.current
    val row = Timeline.day(it, day).rows.firstOrNull { r -> r.kind == "stop" && r.sid == sid }
    TripSheet("trip_stop_sheet", s.name, close) { hide ->
        if (s.why.isNotEmpty()) FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { s.why.forEach { w -> TChip(w, hue = C.hue("amber")) } }
        Text(listOfNotNull(row?.let { r -> Hm.text(r.at) + "–" + Hm.text(r.until) }, s.window?.let { w -> t("open ", "wazi ") + (w.open?.let { Hm.text(it) } ?: "") + "–" + (w.close?.let { Hm.text(it) } ?: "") }).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.padding(top = 8.dp))
        if (editable && ctl != null) {
            TripHead(t("Visit length", "Muda wa ziara"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Btn("−15", { ctl.stops(ItinEdit.visitMin(it, sid, s.visitMin - 15), listOf(sid)) }, Modifier.testTag("stop_minus"), small = true, light = true, outline = true, enabled = s.visitMin > 15)
                Text(Hm.dur(s.visitMin), style = MaterialTheme.typography.titleMedium, color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).testTag("stop_len"))
                Btn("+15", { ctl.stops(ItinEdit.visitMin(it, sid, s.visitMin + 15), listOf(sid)) }, Modifier.testTag("stop_plus"), small = true, light = true, outline = true)
            }
            TripHead(t("Time", "Muda"))
            if (s.fixed == null) Btn(t("Lock at ", "Funga saa ") + (row?.at?.let { Hm.text(it) } ?: ""), { row?.at?.let { at -> ctl.stops(ItinEdit.lock(it, sid, at), listOf(sid)) } }, Modifier.fillMaxWidth().testTag("stop_lock"), small = true, light = true, outline = true, icon = "lock")
            else Btn(t("Unlock the time (", "Fungua muda (") + Hm.text(s.fixed) + ")", { ctl.stops(ItinEdit.lock(it, sid, null), listOf(sid)) }, Modifier.fillMaxWidth().testTag("stop_unlock"), small = true, light = true, outline = true, icon = "lock")
            Text(t("A locked time stays put, like a meeting with the committee.", "Muda uliofungwa haubadiliki, kama mkutano na kamati."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 4.dp))
            val idx = it.days[day].stops.indexOf(sid)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(t("Earlier", "Mapema"), { ctl.stops(ItinEdit.move(it, day, idx, idx - 1)); hide() }, Modifier.weight(1f).testTag("stop_up"), small = true, light = true, outline = true, icon = "up", enabled = idx > 0)
                Btn(t("Later", "Baadaye"), { ctl.stops(ItinEdit.move(it, day, idx, idx + 1)); hide() }, Modifier.weight(1f).testTag("stop_down"), small = true, light = true, outline = true, icon = "down", enabled = idx < it.days[day].stops.lastIndex)
            }
            if (it.days.size > 1) {
                TripHead(t("Move to another day", "Hamishia siku nyingine"))
                Choices(it.days.mapIndexedNotNull { k, d -> if (k == day) null else "$k" to Dates.weekday(d.date) }, "", "stop_day_") { k -> ctl.stops(ItinEdit.toDay(it, day, sid, k.toInt()), listOf(sid)); hide() }
            }
        }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            s.ll?.let { ll -> Btn(t("Directions", "Maelekezo"), { openDirections(ctx, ll.lat, ll.lng) }, Modifier.weight(1f), small = true, light = true, outline = true, icon = "nav") }
            if (s.pid.isNotBlank() && openProject != null) Btn(t("Open the project", "Fungua mradi"), { hide(); openProject(s.pid, false) }, Modifier.weight(1f).testTag("stop_open"), small = true, light = true, outline = true, icon = "arrow")
        }
        if (live && s.open && ctl != null && openProject != null) Btn(t("Check in", "Ingia"), { hide(); checkIn(ctl.m, ctl, sid, openProject) }, Modifier.padding(top = 10.dp).fillMaxWidth().testTag("stop_checkin"), icon = "check", ok = true)
        if (editable && ctl != null) Text(t("Remove from the trip", "Ondoa kwenye safari"), color = C.bad, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(99.dp)).clickable(role = Role.Button) { ctl.stops(ItinEdit.remove(it, day, sid)); hide() }.heightIn(min = 44.dp).padding(12.dp).testTag("stop_remove"))
    }
}

/** Tonight: the suggested town, or another one, and the lodging (name, phone, cost per night). */
@Composable
private fun LodgingSheet(it: Itin, day: Int, ctl: TripCtl, close: () -> Unit) {
    val n = it.days[day].night
    var town by remember { mutableStateOf(n?.town ?: "") }
    var name by remember { mutableStateOf(n?.lodging ?: "") }
    var phone by remember { mutableStateOf(n?.phone ?: "") }
    var kes by remember { mutableStateOf(n?.kes?.takeIf { it > 0 }?.toString() ?: "") }
    TripSheet("trip_night_sheet", t("Tonight", "Usiku huu"), close) { hide ->
        n?.why?.takeIf { it.isNotBlank() }?.let { Text(t("Suggested: ", "Inapendekezwa: ") + n.town + " · " + it, style = MaterialTheme.typography.bodyMedium, color = C.muted) }
        Field(town, { town = it }, t("Town", "Mji"), Modifier.testTag("night_town"))
        Field(name, { name = it }, t("Lodging", "Malazi"), Modifier.testTag("night_name"), placeholder = t("e.g. Kerio View Hotel", "mf. Kerio View Hotel"))
        Field(phone, { phone = it }, t("Phone", "Simu"), phone = true)
        Field(kes, { kes = it }, t("Cost per night (for the group)", "Gharama kwa usiku (kwa kikundi)"), money = true)
        Btn(t("Save", "Hifadhi"), {
            val x = TNight(town.trim(), n?.ll?.takeIf { _ -> town.trim() == n.town }, false, "", name.trim(), phone.trim(), kes.toLongOrNull() ?: 0, "")
            ctl.edit(ItinEdit.lodging(it, day, x), JSONObject().put("action", "lodging").put("day", day).put("overnight", JSONObject().put("town", x.town).apply { x.ll?.let { l -> put("lat", l.lat); put("lng", l.lng) } }
                .put("lodging", JSONObject().put("name", x.lodging).put("phone", x.phone).put("kes", x.kes).put("note", ""))))
            hide()
        }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("night_save"), icon = "check", enabled = town.isNotBlank())
        Btn(t("Go home tonight instead", "Rudi nyumbani usiku huu badala yake"), { ctl.edit(ItinEdit.lodging(it, day, null), JSONObject().put("action", "lodging").put("day", day).put("overnight", JSONObject.NULL)); hide() },
            Modifier.padding(top = 8.dp).fillMaxWidth(), light = true, outline = true, small = true, icon = "nav")
    }
}

/** Add a site to this day (re-timed at once; the server places it properly when it answers). */
@Composable
private fun AddStopSheet(m: AppModel, it: Itin, day: Int, ctl: TripCtl, close: () -> Unit) {
    val snap = m.snap.collectAsState().value ?: return
    var q by remember { mutableStateOf("") }
    TripSheet("trip_add_sheet", t("Add a stop", "Ongeza kituo"), close) { hide ->
        Field(q, { q = it }, t("Find a site", "Tafuta eneo"), Modifier.testTag("add_q"), placeholder = t("Name or area", "Jina au eneo"))
        val have = it.stops.map { s -> s.pid }.toSet()
        snap.projects.filter { p -> p.gps != null && p.id !in have && (it.ck.isBlank() || p.ck == it.ck) && (q.isBlank() || (p.title + " " + p.area).contains(q.trim(), true)) }.take(25).forEach { p ->
            Row(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.tint).clickable {
                val sid = "n" + System.currentTimeMillis().toString(36)
                val raw = it.raw.let { r -> JSONObject(r.toString()) }
                raw.optJSONArray("stops")?.put(JSONObject().put("sid", sid).put("id", p.id).put("name", p.title).put("lat", p.gps!!.lat).put("lng", p.gps.lng).put("type", p.type).put("visitMin", 45).put("day", day).put("status", "planned"))
                raw.optJSONArray("days")?.optJSONObject(day)?.optJSONArray("stops")?.put(sid)
                ctl.edit(it.copy(raw = raw, waiting = it.waiting + 1), JSONObject().put("action", "add-stop").put("stop", JSONObject().put("id", p.id)).put("day", day))
                hide()
            }.padding(12.dp).testTag("add_" + p.id)) {
                Column { Text(p.title, style = MaterialTheme.typography.titleSmall, color = C.ink); Text(p.sub, style = MaterialTheme.typography.bodySmall, color = C.muted) }
            }
        }
    }
}

/** Running late or a stop skipped: "Move the rest?" — push to tomorrow, drop them, or keep the plan. */
@Composable
private fun ReplanSheet(it: Itin, day: Int, ctl: TripCtl, close: () -> Unit) {
    val now = nowMin()
    val out = ItinEdit.overflow(it, day, now)
    TripSheet("trip_replan_sheet", t("Move the rest?", "Hamisha vilivyobaki?"), close) { hide ->
        val plan = Timeline.day(it, day, now)
        Text(t("From now (${Hm.text(now)}) the day ends at ${Hm.text(plan.endsAt)}.", "Kuanzia sasa (${Hm.text(now)}) siku inaisha saa ${Hm.text(plan.endsAt)}."), style = MaterialTheme.typography.bodyMedium, color = C.text)
        if (out.isEmpty()) Text(t("Everything still fits today.", "Kila kitu bado kinatosha leo."), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = C.ok, modifier = Modifier.padding(top = 8.dp).testTag("replan_fits"))
        else {
            Text(t("These no longer fit:", "Hivi havitoshi tena:"), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = C.warn, modifier = Modifier.padding(top = 8.dp))
            out.mapNotNull { s -> it.stop(s) }.forEach { s -> Text("· " + s.name, style = MaterialTheme.typography.bodyMedium, color = C.ink, modifier = Modifier.padding(start = 6.dp, top = 2.dp).testTag("replan_out_" + s.sid)) }
            val tomorrow = it.days.getOrNull(day + 1)?.let { d -> Dates.weekday(d.date) }
            Btn(if (tomorrow != null) t("Push to $tomorrow", "Hamishia $tomorrow") else t("Push to tomorrow", "Hamishia kesho"), {
                ctl.edit(ItinEdit.push(it, day, out), JSONObject().put("action", "replan").put("day", day).put("now", Hm.text(now)).put("mode", "push")); hide()
            }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("replan_push"), icon = "arrow")
            Btn(t("Drop them", "Viondoe"), { ctl.edit(ItinEdit.drop(it, out), JSONObject().put("action", "replan").put("day", day).put("now", Hm.text(now)).put("mode", "drop")); hide() },
                Modifier.padding(top = 8.dp).fillMaxWidth().testTag("replan_drop"), light = true, outline = true, icon = "x")
        }
        Text(t("The office is told when you change the plan.", "Ofisi inaarifiwa ukibadilisha mpango."), style = MaterialTheme.typography.bodySmall, color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        Btn(t("Keep the plan", "Baki na mpango"), hide, Modifier.padding(top = 8.dp).fillMaxWidth().testTag("replan_keep"), light = true, outline = true, small = true)
    }
}

/** Skip a stop: why, then "Move the rest?" if the day is still too long. */
@Composable
private fun SkipSheet(it: Itin, sid: String, ctl: TripCtl, replan: (Int) -> Unit, close: () -> Unit) {
    val s = it.stop(sid) ?: return
    var why by remember { mutableStateOf("") }
    TripSheet("trip_skip_sheet", t("Skip ", "Ruka ") + s.name + "?", close) { hide ->
        Field(why, { why = it }, t("Why (optional)", "Kwa nini (si lazima)"), Modifier.testTag("skip_why"), placeholder = t("e.g. road flooded", "mf. barabara imefurika"))
        Btn(t("Skip this stop", "Ruka kituo hiki"), {
            val x = ItinEdit.mark(it, sid, "skipped")
            ctl.edit(x, JSONObject().put("action", "skip").put("sid", sid).put("why", why))
            hide()
            val d = it.days.indexOfFirst { dd -> sid in dd.stops }
            if (d >= 0 && ItinEdit.overflow(x, d, nowMin()).isNotEmpty()) replan(d)
        }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("skip_go"), icon = "x")
    }
}

/** "Report the road" on the way, with the existing road-reports API. */
@Composable
fun RoadReportSheet(m: AppModel, close: () -> Unit) {
    val scope = rememberScope()
    var kind by remember { mutableStateOf("potholes") }
    var note by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<Pair<String, String>?>(null) }
    val fix by m.fix.collectAsState()
    TripSheet("road_sheet", t("Report the road", "Ripoti barabara"), close) { hide ->
        Text(t("Where you are now. Others see it on their trips.", "Mahali ulipo sasa. Wengine wataiona kwenye safari zao."), style = MaterialTheme.typography.bodyMedium, color = C.muted)
        Choices(listOf("potholes" to t("Potholes", "Mashimo"), "flooded" to t("Flooded", "Imefurika"), "slow_murram" to t("Slow murram", "Murram ya polepole"), "impassable_when_wet" to t("Impassable when wet", "Haipitiki ikinyesha"),
            "bridge_out" to t("Bridge out", "Daraja limeharibika"), "under_construction" to t("Roadworks", "Ujenzi wa barabara"), "good_tarmac" to t("Good tarmac", "Lami nzuri")), kind, "road_k_") { kind = it }
        Field(note, { note = it }, t("Note (optional)", "Maelezo (si lazima)"), placeholder = t("e.g. deep, by the shop", "mf. ni marefu, karibu na duka"))
        if (fix == null) Msg(t("Waiting for your location…", "Inasubiri mahali ulipo…"), "warn")
        Btn(t("Send", "Tuma"), {
            val f = fix ?: return@Btn
            scope.launch {
                val r = m.api.req("POST", "/api/road-reports", JSONObject().put("action", "add").put("kind", kind).put("note", note).put("lat", f.ll.lat).put("lng", f.ll.lng).put("acc", f.acc))
                msg = if (r.ok) "ok" to t("Thank you. Sent ✓", "Asante. Imetumwa ✓") else "bad" to itinReason(r.reason)
                if (r.ok) { kotlinx.coroutines.delay(900); hide() }
            }
        }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("road_send"), icon = "upload", enabled = fix != null)
        msg?.let { (tone, x) -> Msg(x, tone) }
    }
}

// --- costs -----------------------------------------------------------------------------------------------------------

/** Costs: the vehicle and fuel price (with its source and date), estimate vs actual per day and per trip, fuel bought, extras. */
@Composable
private fun CostsPane(m: AppModel, it: Itin, ctl: TripCtl, editable: Boolean) {
    val vehicles by m.trips.vehicles.collectAsState()
    var logs by remember(it.id) { mutableStateOf<List<FuelLog>>(emptyList()) }
    var tick by remember { mutableStateOf(0) }
    var addFuel by remember { mutableStateOf(false) }
    var addExtra by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { m.trips.loadVehicles() }
    LaunchedEffect(it.id, tick) { logs = m.trips.logs(it.id) }
    val c = it.cost
    val v = vehicles.firstOrNull { x -> x.vid == it.vehicleId }
    Card(Modifier.padding(top = 12.dp).testTag("trip_costs")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t("Estimate", "Makadirio"), style = MaterialTheme.typography.labelMedium, color = C.muted)
                Text(c?.let { Money.kes(it.estTotal) } ?: "—", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold), color = C.ink, modifier = Modifier.testTag("cost_est"))
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(t("Actual so far", "Halisi hadi sasa"), style = MaterialTheme.typography.labelMedium, color = C.muted)
                Text(c?.actualTotal?.let { Money.kes(it) } ?: "—", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold), color = C.ok, textAlign = TextAlign.End, modifier = Modifier.testTag("cost_actual"))
            }
        }
        if (c?.fuelMissing == true) Text(t("Fuel isn't in the estimate yet.", "Mafuta bado hayamo kwenye makadirio."), style = MaterialTheme.typography.bodySmall, color = C.warn, modifier = Modifier.padding(top = 4.dp))
        if (it.waiting > 0) Text(t("Costs update when your changes are sent.", "Gharama zitasasishwa mabadiliko yako yakitumwa."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 4.dp))
    }
    TripHead(t("Vehicle and fuel", "Gari na mafuta"))
    Card(Modifier.padding(top = 4.dp), pad = 14.dp) {
        if (editable) Picker(t("Vehicle", "Gari"), it.vehicleId, listOf("" to t("No vehicle yet", "Bado hakuna gari")) + org.rafikioutreach.pin.data.Vehicle.options(vehicles, it.vehicleId, it.raw.optJSONObject("cost")?.optJSONObject("fuel")?.optJSONObject("vehicle")),
            { vid -> ctl.edit(it.copy(raw = JSONObject(it.raw.toString()).put("vehicleId", vid), waiting = it.waiting + 1), JSONObject().put("action", "update").put("vehicleId", vid)) }, Modifier.testTag("cost_vehicle"))
        if (!editable) org.rafikioutreach.pin.data.Vehicle.options(vehicles, it.vehicleId, it.raw.optJSONObject("cost")?.optJSONObject("fuel")?.optJSONObject("vehicle")).firstOrNull { o -> o.first == it.vehicleId }?.let { (_, l) ->
            Text(l, style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.testTag("cost_vehicle_name")) }
        v?.let { x -> Text(listOfNotNull(x.typeName(), x.cc.takeIf { it > 0 }?.let { "%,d cc".format(java.util.Locale.US, it) }, x.fuelName()).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.padding(top = 8.dp)) }
        c?.lPer100?.let { l -> Text("%.1f L/\u2060100\u00A0km".format(java.util.Locale.US, l) + (c.litres?.let { lt -> " · " + "%.1f L".format(java.util.Locale.US, lt) } ?: ""), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.padding(top = 6.dp).testTag("cost_l100")) }
        c?.basis?.takeIf { it.isNotBlank() }?.let { b -> Text(b, style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 2.dp)) }
        val label = c?.fuelLabel?.takeIf { it.isNotBlank() } ?: t("Fuel: add a fuel price", "Mafuta: weka bei ya mafuta")
        Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (c?.fuelMissing == true || c == null) C.warnBg else C.tint).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.get("drop"), null, tint = if (c?.fuelMissing == true || c == null) C.warn else C.blue2, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = if (c?.fuelMissing == true || c == null) C.warn else C.ink, modifier = Modifier.weight(1f).testTag("cost_fuel_label"))
        }
        if ((c?.fuelMissing == true || c == null) && m.snap.value?.admin == true) Text(t("Add a fuel price (Office › Vehicles)", "Weka bei ya mafuta (Ofisi › Magari)"), color = C.blue2, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(99.dp)).clickable(role = Role.Button) { TeamGo.section = "vehicles"; m.goTab = "office" }.heightIn(min = 44.dp).padding(horizontal = 6.dp, vertical = 12.dp).testTag("cost_add_price"))
    }
    if (c != null) {
        TripHead(t("Estimate", "Makadirio"))
        Card(Modifier.padding(top = 4.dp), pad = 14.dp) {
            listOf("fuelKes" to t("Fuel", "Mafuta"), "lunchKes" to t("Lunch", "Chakula cha mchana"), "perDiemKes" to t("Allowances", "Posho"), "lodgingKes" to t("Lodging", "Malazi"), "extrasKes" to t("Tolls, parking, extras", "Ushuru, maegesho, mengineyo")).forEach { (k, l) ->
                val est = c.est.optLong(k, -1); val act = c.actual?.optLong(k, -1) ?: -1
                if (est > 0 || act > 0) MoneyRow(l, est.takeIf { it >= 0 }, act.takeIf { it > 0 }, "cost_$k")
            }
        }
        if (c.days.size > 1) {
            TripHead(t("Per day", "Kwa siku"))
            Card(Modifier.padding(top = 4.dp), pad = 14.dp) { c.days.forEach { d -> MoneyRow(Dates.weekday(d.optString("date")) + " · " + "%,.0f km".format(java.util.Locale.US, d.optDouble("km", 0.0)), d.optLong("totalKes"), null, "cost_day_" + d.optString("date")) } }
        }
    }
    TripHead(t("Fuel bought", "Mafuta yaliyonunuliwa"), extra = if (logs.isNotEmpty()) "${logs.size}" else "")
    logs.forEach { l -> FuelLogRow(l) }
    if (editable || it.status == "done") Btn(t("Add fuel", "Ongeza mafuta"), { addFuel = true }, Modifier.padding(top = 8.dp).fillMaxWidth().testTag("cost_add_fuel"), small = true, light = true, outline = true, icon = "drop", enabled = it.vehicleId.isNotBlank())
    if (it.vehicleId.isBlank()) Text(t("Choose the vehicle first.", "Chagua gari kwanza."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 4.dp))
    TripHead(t("Tolls, parking and extras", "Ushuru, maegesho na mengineyo"), extra = if (it.extras.isNotEmpty()) "${it.extras.size}" else "")
    it.extras.forEach { e ->
        Row(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.tint).padding(12.dp).testTag("cost_extra_" + e.xid), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.label.ifBlank { TExtra.kindName(e.kind) }, style = MaterialTheme.typography.titleSmall, color = C.ink)
                Text(TExtra.kindName(e.kind) + (if (e.actual) t(" · paid", " · imelipwa") else t(" · estimate", " · makadirio")), style = MaterialTheme.typography.bodySmall, color = C.muted)
            }
            Text(Money.kes(e.kes), style = MaterialTheme.typography.titleSmall, color = C.ink, textAlign = TextAlign.End)
            if (editable) Box(Modifier.size(44.dp).clip(CircleShape).clickable { ctl.edit(ItinEdit.extras(it, it.extras.filter { x -> x.xid != e.xid }), JSONObject().put("action", "extras").put("extras", JSONArray(it.extras.filter { x -> x.xid != e.xid }.map { x -> x.json() }))) }
                .semantics { contentDescription = t("Remove", "Ondoa") }, contentAlignment = Alignment.Center) { Icon(Ic.get("trash"), null, tint = C.muted, modifier = Modifier.size(18.dp)) }
        }
    }
    if (editable || it.status == "done") Btn(t("Add a cost", "Ongeza gharama"), { addExtra = true }, Modifier.padding(top = 8.dp).fillMaxWidth().testTag("cost_add_extra"), small = true, light = true, outline = true, icon = "coins")
    if (addFuel) FuelLogSheet(m, it, { addFuel = false }) { tick++; ctl.refresh() }
    if (addExtra) ExtraSheet(it, ctl) { addExtra = false }
}

@Composable
private fun MoneyRow(label: String, est: Long?, actual: Long?, tag: String) {
    Row(Modifier.padding(vertical = 5.dp).fillMaxWidth().testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(est?.let { Money.kes(it) } ?: "—", style = MaterialTheme.typography.titleSmall, color = C.ink, textAlign = TextAlign.End)
            actual?.let { a -> Text(t("actual ", "halisi ") + Money.kes(a), style = MaterialTheme.typography.labelSmall, color = C.ok, textAlign = TextAlign.End) }
        }
    }
}

@Composable
private fun FuelLogRow(l: FuelLog) {
    Row(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.tint).padding(12.dp).testTag("fuel_log"), verticalAlignment = Alignment.CenterVertically) {
        val bmp = remember(l.local) { l.local.takeIf { it.isNotBlank() }?.let { p -> runCatching { android.graphics.BitmapFactory.decodeFile(p) }.getOrNull() } }
        if (bmp != null) Image(bmp.asImageBitmap(), t("Receipt", "Risiti"), contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
        else IconTile("drop", C.blue2, C.card, 44.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("%.1f L".format(java.util.Locale.US, l.litres) + " × KES " + perL(l.perL), style = MaterialTheme.typography.titleSmall, color = C.ink)
            Text(listOfNotNull(l.at.takeIf { it.isNotBlank() }?.let { whenText(it) }, l.town.takeIf { it.isNotBlank() }, l.odo?.let { "%,d km".format(java.util.Locale.US, it) }, l.by.takeIf { it.isNotBlank() }, if (l.waiting) t("waiting to send", "inasubiri kutumwa") else null).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = C.muted)
        }
        Text(Money.kes(l.total), style = MaterialTheme.typography.titleSmall, color = C.ink)
    }
}

/** Fuel bought: litres, price per litre (or the total), odometer, a full tank, and a receipt photo. */
@Composable
private fun FuelLogSheet(m: AppModel, it: Itin, close: () -> Unit, done: () -> Unit) {
    val scope = rememberScope()
    val ctx = LocalContext.current
    var litres by remember { mutableStateOf("") }
    var price by remember { mutableStateOf(it.cost?.let { c -> c.fuelKes?.let { _ -> "" } } ?: "") }
    var total by remember { mutableStateOf("") }
    var odo by remember { mutableStateOf("") }
    var town by remember { mutableStateOf("") }
    var full by remember { mutableStateOf(true) }
    var photo by remember { mutableStateOf<File?>(null) }
    var pending by remember { mutableStateOf<File?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    val cam = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> val p = pending; pending = null; if (ok && p != null) scope.launch { m.keepPhoto(p)?.let { f -> photo = f } } }
    TripSheet("fuel_sheet", t("Add fuel", "Ongeza mafuta"), close) { hide ->
        Field(litres, { litres = it.filter { c -> c.isDigit() || c == '.' }.take(6) }, t("Litres", "Lita"), Modifier.testTag("fuel_litres"), number = true, placeholder = "40")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(price, { price = it.filter { c -> c.isDigit() || c == '.' }.take(8); total = "" }, t("Price per litre", "Bei kwa lita"), Modifier.weight(1f).testTag("fuel_price"), number = true, placeholder = "171.47")
            Field(total, { total = it; price = "" }, t("Or the total", "Au jumla"), Modifier.weight(1f).testTag("fuel_total"), money = true)
        }
        Field(odo, { odo = it }, t("Odometer (km)", "Odomita (km)"), Modifier.testTag("fuel_odo"), money = false, number = true)
        Field(town, { town = it }, t("Town", "Mji"), placeholder = t("e.g. Eldoret", "mf. Eldoret"))
        Row(Modifier.padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Checkbox) { full = !full }.padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(full, { full = it }, colors = CheckboxDefaults.colors(checkedColor = C.blue)); Text(t("Filled the tank", "Nimejaza tangi"), style = MaterialTheme.typography.bodyMedium, color = C.text)
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            photo?.let { f -> remember(f) { android.graphics.BitmapFactory.decodeFile(f.path) }?.let { b -> Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp))); Spacer(Modifier.width(10.dp)) } }
            Btn(if (photo == null) t("Photo of the receipt", "Picha ya risiti") else t("Take it again", "Piga tena"), {
                val f = File(File(ctx.cacheDir, "cam").apply { mkdirs() }, "r${System.currentTimeMillis()}.jpg"); pending = f
                runCatching { cam.launch(FileProvider.getUriForFile(ctx, "org.rafikioutreach.pin.files", f)) }
            }, Modifier.testTag("fuel_photo"), small = true, light = true, outline = true, icon = "camera")
        }
        val l = litres.toDoubleOrNull()
        Btn(t("Save", "Hifadhi"), {
            scope.launch {
                val body = JSONObject().put("action", "log").put("vid", it.vehicleId).put("itinId", it.id).put("at", Instant.now().toString()).put("litres", l).put("full", full)
                    .apply { price.toDoubleOrNull()?.let { p -> put("kesPerL", p) }; total.toLongOrNull()?.let { x -> put("totalKes", x) }; odo.filter { c -> c.isDigit() }.toLongOrNull()?.let { o -> put("odometer", o) }
                        if (town.isNotBlank()) put("town", town.trim()); photo?.let { f -> put("localReceipt", f.path) }; m.fix.value?.let { f -> put("lat", f.ll.lat); put("lng", f.ll.lng) } }
                val r = m.trips.addLog(body)
                if (r.ok || r.status == 0) { done(); hide() } else msg = itinReason(r.reason)
            }
        }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("fuel_save"), icon = "check", enabled = l != null && l >= 0.5 && (price.toDoubleOrNull() != null || total.toLongOrNull() != null))
        Msg(msg, "bad")
    }
}

/** A toll, parking, lodging, lunch or other cost: estimated, or paid. */
@Composable
private fun ExtraSheet(it: Itin, ctl: TripCtl, close: () -> Unit) {
    var kind by remember { mutableStateOf("toll") }
    var label by remember { mutableStateOf("") }
    var kes by remember { mutableStateOf("") }
    var paid by remember { mutableStateOf(true) }
    TripSheet("extra_sheet", t("Add a cost", "Ongeza gharama"), close) { hide ->
        Choices(listOf("toll", "parking", "lodging", "lunch", "other").map { k -> k to TExtra.kindName(k) }, kind, "extra_k_") { kind = it }
        Field(label, { label = it }, t("What (optional)", "Nini (si lazima)"), placeholder = t("e.g. Parking Eldoret", "mf. Maegesho Eldoret"))
        Field(kes, { kes = it }, t("Amount", "Kiasi"), Modifier.testTag("extra_kes"), money = true)
        Row(Modifier.padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Checkbox) { paid = !paid }.padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(paid, { paid = it }, colors = CheckboxDefaults.colors(checkedColor = C.blue)); Text(t("Already paid", "Imeshalipwa"), style = MaterialTheme.typography.bodyMedium, color = C.text)
        }
        Btn(t("Save", "Hifadhi"), {
            val list = it.extras + TExtra("x" + System.currentTimeMillis().toString(36), null, kind, label.trim(), kes.toLongOrNull() ?: 0, paid, null)
            ctl.edit(ItinEdit.extras(it, list), JSONObject().put("action", "extras").put("extras", JSONArray(list.map { e -> e.json() }))); hide()
        }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("extra_save"), icon = "check", enabled = (kes.toLongOrNull() ?: 0) > 0)
    }
}

// --- packing ---------------------------------------------------------------------------------------------------------

/** The packing checklist, made from the stops (test strips, parts, consent forms) plus the standard items. */
@Composable
private fun PackingPane(it: Itin, ctl: TripCtl, editable: Boolean) {
    var add by remember { mutableStateOf("") }
    val done = it.packing.count { p -> p.done }
    TripHead(t("Packing list", "Orodha ya mzigo"), extra = "$done/${it.packing.size}")
    if (it.packing.isEmpty()) EmptyDrop(t("The list is made when the trip is saved.", "Orodha hutengenezwa safari ikihifadhiwa."))
    it.packing.forEach { p ->
        Row(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (p.done) C.okBg else C.tint)
            .clickable(enabled = editable, role = Role.Checkbox) { ctl.edit(ItinEdit.packTick(it, p.key, !p.done), JSONObject().put("action", "packing").put("items", JSONArray().put(JSONObject().put("key", p.key).put("done", !p.done)))) }
            .padding(start = 2.dp, end = 4.dp).testTag("pack_" + p.key), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(p.done, null, colors = CheckboxDefaults.colors(checkedColor = C.ok), enabled = editable)
            Text(p.text, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (p.auto) FontWeight.SemiBold else FontWeight.Normal), color = if (p.done) C.ok else C.ink, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
            if (editable) Box(Modifier.size(44.dp).clip(CircleShape).clickable { ctl.edit(ItinEdit.packRemove(it, p.key), JSONObject().put("action", "packing").put("items", JSONArray().put(JSONObject().put("key", p.key).put("removed", true)))) }
                .semantics { contentDescription = t("Remove", "Ondoa") }.testTag("pack_rm_" + p.key), contentAlignment = Alignment.Center) { Icon(Ic.get("x"), null, tint = C.muted, modifier = Modifier.size(16.dp)) }
        }
    }
    if (editable) Row(verticalAlignment = Alignment.Bottom) {
        Field(add, { add = it }, t("Add an item", "Ongeza kitu"), Modifier.weight(1f).testTag("pack_add"), placeholder = t("e.g. Spare tyre checked", "mf. Tairi ya akiba imeangaliwa"))
        Spacer(Modifier.width(8.dp))
        Btn(t("Add", "Ongeza"), { val x = add.trim(); if (x.isNotBlank()) { ctl.edit(ItinEdit.packAdd(it, x), JSONObject().put("action", "packing").put("add", JSONArray().put(JSONObject().put("en", x).put("sw", x)))); add = "" } }, Modifier.testTag("pack_add_btn"), small = true, icon = "plus")
    }
}

// --- share and report ------------------------------------------------------------------------------------------------

/** Share a read-only page (WhatsApp, e-mail, copy link), with teammates in the app, in a chat; and the summary after. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SharePane(m: AppModel, it: Itin, ctl: TripCtl, admin: Boolean) {
    val ctx = LocalContext.current
    var url by remember(it.id) { mutableStateOf(it.shareUrl) }
    var team by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    fun withLink(f: (String) -> Unit) { if (url.isNotBlank()) f(url) else ctl.ask(JSONObject().put("action", "share")) { r -> r.json.optString("url").takeIf { it.isNotBlank() }?.let { u -> url = u; f(u) } } }
    val text = { u: String -> it.title + " · " + tripDates(it.from, it.to) + "\n" + u }
    TripHead(t("Share the plan", "Shiriki mpango"))
    Text(t("A private, read-only page: the days, stops and times. No PINs, phone numbers or costs.", "Ukurasa wa faragha wa kusoma tu: siku, vituo na muda. Hakuna PIN, namba za simu wala gharama."), style = MaterialTheme.typography.bodyMedium, color = C.muted)
    if (url.isNotBlank()) Text(url, style = MaterialTheme.typography.bodySmall, color = C.blue2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp).testTag("share_url"))
    FlowRow(Modifier.padding(top = 10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn("WhatsApp", { withLink { u -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/?text=" + Uri.encode(text(u))))) } } }, Modifier.testTag("share_wa"), small = true, light = true, outline = true, icon = "b:whatsapp")
        Btn(t("E-mail", "Barua pepe"), { withLink { u -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("mailto:?subject=" + Uri.encode(it.title) + "&body=" + Uri.encode(text(u))))) } } }, Modifier.testTag("share_mail"), small = true, light = true, outline = true, icon = "mail")
        Btn(t("Copy link", "Nakili kiungo"), { withLink { u -> (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)?.setPrimaryClip(android.content.ClipData.newPlainText("trip", u)); msg = t("Link copied", "Kiungo kimenakiliwa") } }, Modifier.testTag("share_copy"), small = true, light = true, outline = true, icon = "copy")
        Btn(t("Teammates", "Wenzako"), { team = true }, Modifier.testTag("share_team"), small = true, light = true, outline = true, icon = "people")
        TripShare.shareToChat?.let { hook -> Btn(t("In a chat", "Kwenye gumzo"), { hook(it.id) }, Modifier.testTag("share_chat"), small = true, light = true, outline = true, icon = "chat") }
    }
    Msg(msg, "ok")
    if (url.isNotBlank() && it.canEdit) Text(t("Stop sharing the link", "Acha kushiriki kiungo"), color = C.bad, style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(99.dp)).clickable(role = Role.Button) { ctl.ask(JSONObject().put("action", "unshare")) { r -> if (r.ok) url = "" } }.heightIn(min = 44.dp).padding(horizontal = 6.dp, vertical = 12.dp).testTag("share_stop"))

    TripHead(t("After the trip", "Baada ya safari"))
    val sm = it.summary
    if (sm == null) {
        Text(t("When the trip is done: sites visited, km driven, estimated and actual cost, problems found, repairs opened and water tests. It goes into the monthly report.", "Safari ikikamilika: maeneo yaliyotembelewa, km zilizoendeshwa, gharama inayokadiriwa na halisi, matatizo, matengenezo yaliyofunguliwa na vipimo vya maji. Inaingia kwenye ripoti ya mwezi."),
            style = MaterialTheme.typography.bodyMedium, color = C.muted)
        if (it.canEdit && it.status != "done") Btn(t("Finish the trip", "Maliza safari"), { ctl.ask(JSONObject().put("action", "update").put("status", "done")) { r -> if (r.ok) ctl.ask(JSONObject().put("action", "summary")) } }, Modifier.padding(top = 10.dp).fillMaxWidth().testTag("trip_finish"), icon = "check", ok = true)
    } else SummaryCard(sm)
    if (team) ShareTeamSheet(m, it, ctl) { team = false }
}

@Composable
private fun SummaryCard(s: JSONObject) {
    Card(Modifier.padding(top = 6.dp).testTag("trip_summary")) {
        val visited = s.optJSONArray("visited")?.length() ?: 0; val skipped = s.optJSONArray("skipped")?.length() ?: 0
        val km = s.optJSONObject("km"); val cost = s.optJSONObject("cost")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SumTile("$visited", if (visited == 1) t("site visited", "eneo lililotembelewa") else t("sites visited", "maeneo yaliyotembelewa"), Modifier.weight(1f))
            SumTile("%,d".format(java.util.Locale.US, km?.optLong("value") ?: 0), if (km?.optString("src") == "gps") t("km driven (GPS)", "km zilizoendeshwa (GPS)") else t("km (planned)", "km (zilizopangwa)"), Modifier.weight(1f))
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SumTile("${s.optInt("repairsOpened")}", t("repairs opened", "matengenezo yaliyofunguliwa"), Modifier.weight(1f))
            SumTile("${s.optInt("waterTests")}", t("water tests", "vipimo vya maji"), Modifier.weight(1f))
        }
        if (cost != null) {
            MoneyRow(t("Estimated cost", "Gharama inayokadiriwa"), cost.optLong("estKes"), null, "sum_est")
            MoneyRow(t("Actual cost", "Gharama halisi"), cost.optLong("actualKes").takeIf { it > 0 }, null, "sum_act")
        }
        if (skipped > 0) Text(t("Skipped: ", "Zilizorukwa: ") + (0 until skipped).joinToString(", ") { k -> s.optJSONArray("skipped")!!.optJSONObject(k).optString("name") }, style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 6.dp))
        val probs = s.optJSONArray("problems")
        if (probs != null && probs.length() > 0) {
            Text(t("Problems found", "Matatizo yaliyopatikana"), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.padding(top = 10.dp))
            (0 until probs.length()).map { k -> probs.optJSONObject(k) }.forEach { p -> Text("· " + p.optString("name") + " · " + org.rafikioutreach.pin.data.stateName(p.optString("state")), style = MaterialTheme.typography.bodySmall, color = C.bad) }
        }
    }
}

@Composable
private fun SumTile(value: String, label: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(C.tint).padding(12.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold), color = C.ink, maxLines = 1)
        FitWords(label, MaterialTheme.typography.labelSmall, C.muted, align = Alignment.CenterStart)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShareTeamSheet(m: AppModel, it: Itin, ctl: TripCtl, close: () -> Unit) {
    val mates by m.team.collectAsState()
    LaunchedEffect(Unit) { m.loadTeam() }
    val picked = remember { androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(it.viewers) } }
    TripSheet("share_team_sheet", t("Share with teammates", "Shiriki na wenzako"), close) { hide ->
        Text(t("They see the trip in their list (read-only).", "Wataiona safari kwenye orodha yao (kusoma tu)."), style = MaterialTheme.typography.bodyMedium, color = C.muted)
        FlowRow(Modifier.padding(top = 10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            mates.orEmpty().map { x -> x.name }.filter { n -> !it.isOn(n) }.forEach { n -> Chip(n, if (n in picked) "check" else "plus", n in picked, { if (n in picked) picked.remove(n) else picked.add(n) }, tag = "share_p_$n", hue = "blue") }
        }
        Btn(t("Share", "Shiriki"), { ctl.ask(JSONObject().put("action", "share-team").put("names", JSONArray(picked.toList()))); hide() }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("share_team_go"), icon = "people", enabled = picked.isNotEmpty())
    }
}


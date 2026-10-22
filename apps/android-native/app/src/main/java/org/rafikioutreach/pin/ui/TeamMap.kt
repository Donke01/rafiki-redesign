package org.rafikioutreach.pin.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.COUNTRIES
import org.rafikioutreach.pin.data.Geo
import org.rafikioutreach.pin.data.LatLng
import org.rafikioutreach.pin.data.Project
import org.rafikioutreach.pin.data.ShareHere
import org.rafikioutreach.pin.data.Snapshot
import org.rafikioutreach.pin.data.reasonText
import org.rafikioutreach.pin.t
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// Live team map (Office › Team map), Share my location (Me › Settings › Security and alerts, and the
// "Sharing location with the office · Stop" bar), and Office › Plan a week. Same server as the web app:
// POST /api/pin { action: "here" }, GET /api/admin?view=teammap, GET /api/admin?view=plan, POST "assign".

private val OFA_COLORS = listOf(0xFF0A78C2, 0xFF1C9C5A, 0xFFD98200, 0xFF7A4FD0, 0xFFD23C32, 0xFF0E8F96).map { it.toInt() }
private fun ofaStateColor(s: String) = when (s) { "notworking" -> 0xFFB3261E.toInt(); "repair" -> 0xFFD98200.toInt(); else -> 0xFF1C9C5A.toInt() }

// --- data ---------------------------------------------------------------------------------------------
data class Sharer(val name: String, val ll: LatLng, val acc: Int, val at: String, val phone: String, val home: String)
data class DayVisit(val id: String, val by: String, val at: String, val state: String, val title: String)
data class TeamMapData(val people: List<Sharer>, val visits: List<DayVisit>) {
    companion object {
        fun from(o: JSONObject): TeamMapData {
            val p = o.optJSONArray("people") ?: JSONArray(); val v = o.optJSONArray("visits") ?: JSONArray()
            return TeamMapData(
                (0 until p.length()).map { p.getJSONObject(it) }.filter { it.has("lat") && it.has("lng") }.map {
                    Sharer(it.optString("name"), LatLng(it.optDouble("lat"), it.optDouble("lng")), it.optInt("acc"), it.optString("at"), it.optString("phone"), it.optString("home"))
                },
                (0 until v.length()).map { v.getJSONObject(it) }.map { DayVisit(it.optString("id"), it.optString("by"), it.optString("at"), it.optString("s"), it.optString("t")) },
            )
        }
    }
}

/** Why a site is suggested, and how long since its last visit (days). */
data class PlanInfo(val why: List<String>, val age: Int?)
/** A week's plan for one person: days of project ids (in driving order), the dates, and sites to add. */
data class WeekPlan(val to: String, val info: Map<String, PlanInfo>, val total: Int, val days: List<List<String>>, val spare: List<String>, val dates: List<LocalDate>, val assigned: Boolean = false)

/** Working days from [start] on: Sundays are skipped (the team works Monday to Saturday). */
fun planDates(start: LocalDate, n: Int): List<LocalDate> {
    val out = mutableListOf<LocalDate>(); var d = start
    while (out.size < n) { if (d.dayOfWeek != DayOfWeek.SUNDAY) out += d; d = d.plusDays(1) }
    return out
}

/** Nearest next, with the route planner's own ordering. */
fun planOrder(ids: List<String>, s: Snapshot): List<String> = orderStops(ids.mapNotNull { id -> s.projects.firstOrNull { it.id == id && it.gps != null } }, null).map { it.id }

/** Straight-line metres along the stops, in order. */
fun planMetres(ids: List<String>, s: Snapshot): Double = ids.mapNotNull { id -> s.projects.firstOrNull { it.id == id }?.gps }.zipWithNext().sumOf { (a, b) -> Geo.metres(a, b) }

fun parsePlan(j: JSONObject, to: String, start: LocalDate, s: Snapshot): WeekPlan {
    val info = mutableMapOf<String, PlanInfo>()
    fun read(o: JSONObject): String { val id = o.optString("id"); info[id] = PlanInfo(o.optJSONArray("why")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(), if (o.isNull("age") || !o.has("age")) null else o.optInt("age")); return id }
    val ds = j.optJSONArray("days") ?: JSONArray(); val sp = j.optJSONArray("spare") ?: JSONArray()
    val days = (0 until ds.length()).map { i -> val st = ds.getJSONObject(i).optJSONArray("stops") ?: JSONArray(); planOrder((0 until st.length()).map { read(st.getJSONObject(it)) }, s) }
    val spare = (0 until sp.length()).map { read(sp.getJSONObject(it)) }.filter { id -> s.projects.any { it.id == id && it.gps != null } }
    return WeekPlan(to, info, j.optInt("total"), days, spare, planDates(start, days.size))
}

/** The reasons as short tags: (tone, text). */
fun planWhy(info: PlanInfo?, repairState: String?): List<Pair<String, String>> = (info?.why ?: emptyList()).mapNotNull { w ->
    when (w) {
        "repair" -> "bad" to (if (repairState == "repair") t("Needs repair", "Inahitaji matengenezo") else t("Not working", "Haifanyi kazi"))
        "urgent" -> "bad" to t("Urgent", "Haraka")
        "overdue" -> "" to ((info?.age ?: 0) / 30.4).let { m -> t("Not visited for ${Math.round(m)} months", "Haijatembelewa kwa miezi ${Math.round(m)}") }
        "never" -> "" to t("No visit in the app yet", "Bado haijatembelewa kwenye programu")
        "test" -> "has" to t("Water test due", "Kipimo cha maji kinahitajika")
        else -> null
    }
}

// --- the map --------------------------------------------------------------------------------------------
data class MapAvatar(val key: String, val ll: LatLng, val initials: String, val color: Int, val label: String, val faded: Boolean)
data class MapPin(val ll: LatLng, val color: Int, val number: String?, val title: String, val dim: Boolean = false)
data class MapLine(val pts: List<LatLng>, val color: Int, val dim: Boolean)

private fun avatarIcon(ctx: android.content.Context, a: MapAvatar): BitmapDrawable {
    val d = ctx.resources.displayMetrics.density; val w = (64 * d).toInt(); val h = (60 * d).toInt(); val r = 20 * d
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = android.graphics.Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val cx = w / 2f; val cy = r + 2 * d
    p.color = 0x55000000; c.drawCircle(cx, cy + 1.5f * d, r, p)
    p.color = android.graphics.Color.WHITE; c.drawCircle(cx, cy, r, p)
    p.color = a.color; p.alpha = if (a.faded) 150 else 255; c.drawCircle(cx, cy, r - 3 * d, p); p.alpha = 255
    p.color = android.graphics.Color.WHITE; p.textSize = 13 * d; p.typeface = Typeface.DEFAULT_BOLD; p.textAlign = Paint.Align.CENTER
    c.drawText(a.initials, cx, cy + 4.5f * d, p)
    p.textSize = 10 * d; val tw = p.measureText(a.label) + 12 * d; val top = cy + r + 2 * d
    p.color = 0x33000000; c.drawRoundRect(RectF(cx - tw / 2, top + 1 * d, cx + tw / 2, top + 17 * d), 9 * d, 9 * d, p)
    p.color = android.graphics.Color.WHITE; c.drawRoundRect(RectF(cx - tw / 2, top, cx + tw / 2, top + 16 * d), 8 * d, 8 * d, p)
    p.color = 0xFF04263F.toInt(); c.drawText(a.label, cx, top + 11.5f * d, p)
    return BitmapDrawable(ctx.resources, b)
}

private fun pinIcon(ctx: android.content.Context, pin: MapPin): BitmapDrawable {
    val d = ctx.resources.displayMetrics.density; val s = ((if (pin.number != null) 26 else 16) * d).toInt()
    val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = android.graphics.Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val alpha = if (pin.dim) 90 else 255
    p.color = android.graphics.Color.WHITE; p.alpha = alpha; c.drawCircle(s / 2f, s / 2f, s / 2f, p)
    p.color = pin.color; p.alpha = alpha; c.drawCircle(s / 2f, s / 2f, s / 2f - 2.2f * d, p)
    pin.number?.let { p.color = android.graphics.Color.WHITE; p.alpha = alpha; p.textSize = 11 * d; p.typeface = Typeface.DEFAULT_BOLD; p.textAlign = Paint.Align.CENTER; c.drawText(it, s / 2f, s / 2f + 4 * d, p) }
    return BitmapDrawable(ctx.resources, b)
}

private class OfaMapState { var key: Any? = null; var overlays: List<Overlay> = emptyList(); var fitted: Any? = Unit }

/** A map with teammates (initials, last seen), small pins and route lines. Fits to [focus] (or everything) when [fitKey] changes. */
@Composable
fun OfaMap(avatars: List<MapAvatar>, pins: List<MapPin>, lines: List<MapLine>, modifier: Modifier = Modifier, fitKey: Any? = null, focus: List<LatLng>? = null, onAvatar: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    MapSetup.init(ctx)
    val g by GoogleTiles.maps.collectAsState()
    val tap by rememberUpdatedState(onAvatar)
    val map = remember { MapView(ctx).apply { setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); controller.setZoom(7.0); controller.setCenter(GeoPoint(0.5, 35.3)); minZoomLevel = 3.0 } }
    val st = remember { OfaMapState() }
    val cm = countryMapOn(false) // the whole-country map with no signal, or when picked as the map style (feat-cmap)
    DisposableEffect(Unit) { map.onResume(); onDispose { map.onPause(); map.onDetach() } }
    Box(modifier.clipToBounds()) {
    AndroidView({ map }, Modifier.matchParentSize().semantics { contentDescription = t("Map", "Ramani") }, update = { mv ->
        g?.road?.let { if (mv.tileProvider.tileSource != it) mv.setTileSource(it) }
        org.rafikioutreach.pin.cmap.CountryMaps.apply(mv, cm)
        val key = Triple(avatars, pins, lines)
        if (st.key != key) {
            st.key = key
            st.overlays.forEach { mv.overlays.remove(it) }
            val ls = lines.filter { it.pts.size > 1 }.map { l ->
                Polyline(mv).apply { setPoints(l.pts.map { GeoPoint(it.lat, it.lng) }); outlinePaint.color = l.color; outlinePaint.alpha = if (l.dim) 70 else 220; outlinePaint.strokeWidth = 4 * ctx.resources.displayMetrics.density; outlinePaint.strokeCap = Paint.Cap.ROUND }
            }
            val ps = pins.sortedBy { !it.dim }.map { p -> Marker(mv).apply { position = GeoPoint(p.ll.lat, p.ll.lng); icon = pinIcon(ctx, p); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); title = p.title } }
            val av = avatars.map { a ->
                Marker(mv).apply {
                    position = GeoPoint(a.ll.lat, a.ll.lng); icon = avatarIcon(ctx, a); setAnchor(Marker.ANCHOR_CENTER, 22f / 60f); setInfoWindow(null)
                    setOnMarkerClickListener { _, _ -> tap(a.key); true }
                }
            }
            st.overlays = ls + ps + av; mv.overlays.addAll(st.overlays)
            mv.invalidate()
        }
        if (st.fitted != fitKey) {
            st.fitted = fitKey
            val pts = (focus?.takeIf { it.isNotEmpty() } ?: (avatars.map { it.ll } + pins.filter { !it.dim }.map { it.ll }).ifEmpty { pins.map { it.ll } })
            if (pts.size == 1) mv.post { mv.controller.setZoom(13.0); mv.controller.setCenter(GeoPoint(pts[0].lat, pts[0].lng)) }
            else if (pts.size > 1) {
                val box = BoundingBox.fromGeoPointsSafe(pts.map { GeoPoint(it.lat, it.lng) })
                mv.post { runCatching { mv.zoomToBoundingBox(box.increaseByScale(1.35f), false); if (mv.zoomLevelDouble > 13.0) mv.controller.setZoom(13.0) } }
            }
        }
    })
    if (cm) CountryCredit(Modifier.align(Alignment.BottomEnd))
    }
}

// --- the "Sharing location with the office · Stop" bar (under the app header, on every screen) ------
@Composable
fun SharingBar(m: AppModel) {
    val ctx = LocalContext.current
    val rev by ShareHere.rev.collectAsState()
    val snap by m.snap.collectAsState()
    val fix by m.fix.collectAsState()
    var minute by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); minute++ } }
    val own = snap?.who?.isNotBlank() == true
    val on = remember(rev, minute, own) { ShareHere.active(ShareHere.load(ctx), own) }
    // at most every 10 minutes, while the app is open (the GPS only runs while it is)
    LaunchedEffect(on, fix?.at?.div(60_000), minute) { if (on) ShareHere.maybeSend(ctx.applicationContext, m.api, own, fix) }
    val scope = rememberCoroutineScope()
    AnimatedVisibility(on, enter = if (Motion.on) expandVertically() + fadeIn() else EnterTransition.None, exit = if (Motion.on) shrinkVertically() + fadeOut() else ExitTransition.None) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), contentAlignment = Alignment.Center) {
            Row(Modifier.clip(RoundedCornerShape(99.dp)).background(C.okBg).border(1.dp, C.line, RoundedCornerShape(99.dp)).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp).testTag("share_bar"),
                verticalAlignment = Alignment.CenterVertically) {
                OfaPulse(C.ok)
                Spacer(Modifier.width(8.dp))
                Text(t("Sharing location with the office", "Unashiriki mahali na ofisi"), color = C.ok, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold, fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                Text(t("Stop", "Acha"), color = C.ink, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold, fontSize = 13.sp),
                    modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(C.card).clickable(role = Role.Button) { scope.launch { ShareHere.stop(ctx.applicationContext, m.api) } }
                        .heightIn(min = 32.dp).padding(horizontal = 14.dp, vertical = 7.dp).testTag("share_stop"))
            }
        }
    }
}

@Composable
private fun OfaPulse(c: Color) {
    val loop = Motion.on && Motion.loops
    val tr = rememberInfiniteTransition(label = "ping")
    val k by if (loop) tr.animateFloat(0f, 1f, infiniteRepeatable(tween(2200), RepeatMode.Restart), label = "k") else remember { mutableFloatStateOf(1f) }
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        if (loop) Box(Modifier.size(20.dp).graphicsLayer { scaleX = 0.45f + k; scaleY = 0.45f + k; alpha = (1f - k) * 0.85f }.border(2.dp, c, CircleShape))
        Box(Modifier.size(9.dp).clip(CircleShape).background(c))
    }
}

// --- Me › Settings › Security and alerts: the row, and its page --------------------------------------
@Composable
fun ShareRow(m: AppModel, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val rev by ShareHere.rev.collectAsState()
    val h = remember(rev) { ShareHere.load(ctx) }
    val hu = C.hue("green")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp, horizontal = 2.dp).testTag("set_share"), verticalAlignment = Alignment.CenterVertically) {
        IconTile("pin", hu.fg, hu.bg, 40.dp); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t("Share my location with the office", "Shiriki mahali nilipo na ofisi"), style = MaterialTheme.typography.titleSmall, color = C.ink)
            Text(if (h.on) t("On · ", "Imewashwa · ") + ShareHere.hoursText(h) else t("Off", "Imezimwa"), style = MaterialTheme.typography.bodySmall, color = if (h.on) C.ok else C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun ShareSettingsPage(m: AppModel, back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberScope()
    val rev by ShareHere.rev.collectAsState()
    val snap by m.snap.collectAsState()
    val own = snap?.who?.isNotBlank() == true
    val h = remember(rev) { ShareHere.load(ctx) }
    var note by remember { mutableStateOf<Pair<String, String>?>(null) }
    fun granted() = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    // only the location permission for while the app is open: background location is never asked for
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) { m.locator.start(); ShareHere.save(ctx, ShareHere.load(ctx).copy(on = true)); note = null }
        else note = "bad" to t("Allow location for RAFIKI Pin first, then turn it on again.", "Ruhusu mahali kwa RAFIKI Pin kwanza, kisha uiwashe tena.")
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 100.dp)) {
        Text(t("Share my location", "Shiriki mahali nilipo"), style = MaterialTheme.typography.headlineLarge, color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp).testTag("set_title"))
        Card(Modifier.padding(top = 8.dp)) {
            Text(t("While you work, the office can see where you are on a map, to plan visits and keep the team safe. Only admins see it. Only your latest position is kept, for 12 hours, never a history. It is off unless you turn it on, and the app never asks to track you in the background.",
                "Unapofanya kazi, ofisi inaweza kuona ulipo kwenye ramani, ili kupanga ziara na kuilinda timu. Wasimamizi pekee wanaona. Mahali pako pa mwisho tu panahifadhiwa, kwa saa 12, si historia. Imezimwa usipoiwasha, na programu haiombi kukufuatilia chinichini."),
                style = MaterialTheme.typography.bodyMedium, color = C.muted)
            if (!own) { Msg(t("You need your own PIN (or monday.com sign-in) to share your location, so the office knows it’s you.", "Unahitaji PIN yako mwenyewe (au kuingia kwa monday.com) ili kushiriki mahali, ili ofisi ijue ni wewe."), "warn"); return@Card }
            val hu = C.hue("green")
            Row(Modifier.padding(top = 14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconTile("pin", hu.fg, hu.bg, 40.dp); Spacer(Modifier.width(12.dp))
                Text(t("Share my location with the office during work hours", "Shiriki mahali nilipo na ofisi wakati wa saa za kazi"), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Toggle(h.on, if (h.on) t("Turn off", "Zima") else t("Turn on", "Washa"), Modifier.testTag("share_switch")) {
                    if (h.on) scope.launch { ShareHere.stop(ctx.applicationContext, m.api) }
                    else if (granted()) { ShareHere.save(ctx, h.copy(on = true)); m.locator.start() }
                    else ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            }
            Text(t("Work days", "Siku za kazi"), style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp), color = C.ink, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..7).forEach { d ->
                    val sel = d in h.days
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(99.dp)).background(if (sel) C.selected else C.card).border(1.dp, if (sel) C.selected else C.edge, RoundedCornerShape(99.dp))
                        .clickable(role = Role.Checkbox) { ShareHere.save(ctx, h.copy(days = if (sel) h.days - d else h.days + d)) }.heightIn(min = 40.dp).testTag("share_day_$d"), contentAlignment = Alignment.Center) {
                        Text(ShareHere.dayName(d), color = if (sel) Color.White else C.ink, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp))
                    }
                }
            }
            val times = (10..44).map { it * 30 }.map { ShareHere.hhmm(it) to ShareHere.hhmm(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Picker(t("From", "Kuanzia"), ShareHere.hhmm(h.from), times, { v -> ShareHere.save(ctx, h.copy(from = v.take(2).toInt() * 60 + v.takeLast(2).toInt())) }, Modifier.weight(1f).testTag("share_from"))
                Picker(t("Until", "Hadi"), ShareHere.hhmm(h.to), times, { v -> ShareHere.save(ctx, h.copy(to = v.take(2).toInt() * 60 + v.takeLast(2).toInt())) }, Modifier.weight(1f).testTag("share_to"))
            }
            var minute by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) { while (true) { delay(30_000); minute++ } }
            val status = remember(rev, minute) {
                val sent = ShareHere.lastSent(ctx)
                when {
                    !h.on -> "" to t("Off. The office can’t see where you are.", "Imezimwa. Ofisi haioni ulipo.")
                    h.days.isEmpty() -> "warn" to t("Choose at least one work day.", "Chagua angalau siku moja ya kazi.")
                    ShareHere.inHours(h) -> "ok" to (t("Sharing now", "Inashiriki sasa") + if (sent > 0) " · " + t("last sent ", "ilitumwa ") + ago(java.time.Instant.ofEpochMilli(sent).toString()) else "")
                    else -> "" to t("Outside your work hours: not sharing now.", "Nje ya saa zako za kazi: haishiriki sasa.")
                }
            }
            Box(Modifier.testTag("share_status")) { Msg((note ?: status).second, (note ?: status).first) }
        }
    }
}

// --- Office › Team map ------------------------------------------------------------------------------
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TeamMapSection(m: AppModel, s: Snapshot, open: (String) -> Unit) {
    val ctx = LocalContext.current
    var data by remember { mutableStateOf<TeamMapData?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var who by remember { mutableStateOf("") }
    LaunchedEffect(tick) { val r = m.api.adminGet("teammap"); if (r.ok) { data = TeamMapData.from(r.json); err = null } else err = reasonText(r.reason) }
    val d = data
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("Team map", "Ramani ya timu"), style = MaterialTheme.typography.titleLarge, color = C.ink, modifier = Modifier.weight(1f))
            Btn(t("Refresh", "Onyesha upya"), { tick++ }, small = true, light = true, icon = "refresh")
        }
        Text(when {
            err != null -> err!!
            d == null -> t("Loading…", "Inapakia…")
            else -> (when (d.people.size) { 0 -> t("Nobody is sharing their location right now.", "Hakuna anayeshiriki mahali kwa sasa."); 1 -> t("1 teammate sharing their location", "Mwenzako 1 anashiriki mahali"); else -> t("${d.people.size} teammates sharing their location", "Wenzako ${d.people.size} wanashiriki mahali") }) +
                " · " + if (d.visits.size == 1) t("1 visit today", "ziara 1 leo") else t("${d.visits.size} visits today", "ziara ${d.visits.size} leo")
        }, style = MaterialTheme.typography.bodyMedium, color = if (err != null) C.bad else C.muted, modifier = Modifier.padding(top = 4.dp).testTag("tm_sum"))
        if (d != null) {
            val colors = d.people.mapIndexed { i, p -> p.name to OFA_COLORS[i % OFA_COLORS.size] }.toMap()
            val avatars = d.people.map { p -> MapAvatar(p.name, p.ll, initialsOf(p.name), colors.getValue(p.name), ago(p.at), ofaOld(p.at)) }
            val pins = d.visits.mapNotNull { v -> s.projects.firstOrNull { it.id == v.id }?.gps?.let { MapPin(it, ofaStateColor(v.state), null, v.title) } }
            val focus = if (who.isBlank()) null else d.people.filter { it.name == who }.map { it.ll } + d.visits.filter { it.by.equals(who, true) }.mapNotNull { v -> s.projects.firstOrNull { it.id == v.id }?.gps }
            OfaMap(avatars, pins, emptyList(), Modifier.padding(top = 12.dp).fillMaxWidth().height(320.dp).clip(RoundedCornerShape(18.dp)).testTag("tm_map"), fitKey = d to who, focus = focus) { who = it }
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OfaLegend(Color(0xFF1C9C5A), t("Visit today: working", "Ziara ya leo: inafanya kazi")); OfaLegend(Color(0xFFD98200), t("needs repair", "inahitaji matengenezo")); OfaLegend(Color(0xFFB3261E), t("not working", "haifanyi kazi"))
            }
            d.people.forEach { p ->
                val vs = d.visits.filter { it.by.equals(p.name, true) }
                val openNow = who == p.name
                OfaRule()
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { who = if (openNow) "" else p.name }.padding(vertical = 6.dp).testTag("tm_person_${p.name}"), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White).padding(3.dp).clip(CircleShape).background(Color(colors.getValue(p.name))), contentAlignment = Alignment.Center) {
                        Text(initialsOf(p.name), color = Color.White, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall, color = C.ink)
                        Text(t("Last seen ", "Alionekana ") + ago(p.at) + (if (p.acc > 0) " · ±" + (if (p.acc < 1000) "${p.acc} m" else "${p.acc / 1000} km") else ""), style = MaterialTheme.typography.bodySmall, color = C.muted)
                    }
                }
                FlowRow(Modifier.padding(start = 52.dp, top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (p.phone.isNotBlank()) {
                        Btn(t("Call", "Piga simu"), { runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + p.phone.replace(" ", "")))) } }, small = true, light = true, icon = "phone")
                        Btn("WhatsApp", { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + waNumber(p.phone, p.home)))) } }, small = true, light = true, icon = "chat")
                    }
                    Btn(t("Visits today", "Ziara za leo") + " · ${vs.size}", { who = if (openNow) "" else p.name }, Modifier.testTag("tm_visits_${p.name}"), small = true, light = !openNow, icon = "clip")
                }
                if (openNow) Column(Modifier.padding(start = 52.dp, top = 6.dp)) {
                    if (vs.isEmpty()) Text(t("No visits reported yet today.", "Hakuna ziara zilizoripotiwa leo bado."), style = MaterialTheme.typography.bodySmall, color = C.muted)
                    vs.forEach { v ->
                        Row(Modifier.fillMaxWidth().clickable { open(v.id) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(9.dp).clip(CircleShape).background(Color(ofaStateColor(v.state))))
                            Spacer(Modifier.width(8.dp))
                            Text(s.projects.firstOrNull { it.id == v.id }?.title ?: v.title, style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.weight(1f))
                            Text(ofaClock(v.at), style = MaterialTheme.typography.bodySmall, color = C.muted)
                        }
                    }
                }
            }
            if (d.people.isEmpty()) Text(t("People turn it on in Me › Settings › Security and alerts. It’s off unless they choose it.", "Watu wanaiwasha kwenye Mimi › Mipangilio › Usalama na arifa. Imezimwa wasipochagua."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 10.dp))
        }
    }
    Row(Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp)) {
        Icon(Ic.get("lock"), null, tint = C.muted, modifier = Modifier.size(16.dp).padding(top = 2.dp)); Spacer(Modifier.width(8.dp))
        Text(t("Only people who turned on “Share my location” in their Settings show here, and only in their work hours. Only the latest position is kept, for 12 hours. Field officers never see this map.",
            "Wanaoonekana hapa ni wale tu waliowasha “Shiriki mahali nilipo” kwenye Mipangilio yao, na wakati wa saa zao za kazi tu. Mahali pa mwisho tu panahifadhiwa, kwa saa 12. Maafisa wa uwanjani hawaoni ramani hii."),
            style = MaterialTheme.typography.bodySmall, color = C.muted)
    }
}

private fun ofaOld(iso: String) = runCatching { java.time.Duration.between(java.time.Instant.parse(iso), java.time.Instant.now()).toMinutes() > 60 }.getOrDefault(false)
private fun ofaClock(iso: String) = runCatching { DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.parse(iso)) }.getOrDefault("")

@Composable
private fun OfaRule() = Box(Modifier.padding(top = 10.dp, bottom = 4.dp).fillMaxWidth().height(1.dp).background(C.line))

@Composable
private fun OfaLegend(c: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(11.dp).clip(CircleShape).background(Color.White).padding(2.dp).clip(CircleShape).background(c)); Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = C.muted)
    }
}

// --- Office › Plan a week -------------------------------------------------------------------------------
@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PlanWeekSection(m: AppModel, s: Snapshot, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberScope()
    var names by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(Unit) { val a = m.api.adminGet("team").json.optJSONArray("team") ?: JSONArray(); names = (0 until a.length()).map { a.getJSONObject(it).optString("name") }.filter { it.isNotBlank() }.sorted() }
    var to by rememberSaveable { mutableStateOf("") }
    var ck by rememberSaveable { mutableStateOf(m.prefs.country.ifBlank { s.me.home }.ifBlank { "ke" }) }
    var area by rememberSaveable { mutableStateOf("") }
    var days by rememberSaveable { mutableStateOf("5") }
    var per by rememberSaveable { mutableStateOf("5") }
    var start by rememberSaveable { mutableStateOf(LocalDate.now().plusDays(1).let { if (it.dayOfWeek == DayOfWeek.SUNDAY) it.plusDays(1) else it }.toString()) }
    var plan by remember { mutableStateOf<WeekPlan?>(null) }
    var msg by remember { mutableStateOf<Pair<String, String>?>(null) }
    var amsg by remember { mutableStateOf<Pair<String, String>?>(null) }
    var day by remember { mutableIntStateOf(-1) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // a new suggestion scrolls into view (edits to it don't)
    var made by remember { mutableIntStateOf(0) }
    val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    LaunchedEffect(made) { if (made > 0) { delay(80); runCatching { bring.bringIntoView() } } }
    LaunchedEffect(names) { if (to.isBlank()) names.firstOrNull()?.let { to = it } }
    fun proj(id: String) = s.projects.firstOrNull { it.id == id }

    Card {
        H2(t("Plan a week", "Panga wiki"))
        Text(t("Suggests visits for one person: open repairs first, then sites not visited for 6 months or more, and water tests that are due. Sites close together go on the same day, in driving order.",
            "Inapendekeza ziara kwa mtu mmoja: matengenezo yaliyo wazi kwanza, kisha maeneo yasiyotembelewa kwa miezi 6 au zaidi, na vipimo vya maji vinavyohitajika. Maeneo yaliyo karibu yanawekwa siku moja, kwa mpangilio wa safari."), style = MaterialTheme.typography.bodyMedium, color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Picker(t("Field officer", "Afisa wa uwanjani"), to, listOf("" to t("Choose", "Chagua")) + names.map { it to it }, { to = it }, Modifier.weight(1f).testTag("plan_to"))
            Picker(t("Country", "Nchi"), ck, COUNTRIES.toList(), { ck = it; area = "" }, Modifier.weight(1f))
        }
        val areas = remember(ck, s) { s.projects.filter { it.ck == ck && it.gps != null }.map { it.area.split(",").first().trim() }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().entries.sortedWith(compareBy({ -it.value }, { it.key })).take(60) }
        Picker(t("Area", "Eneo"), area, listOf("" to t("All of ", "Yote ya ") + (COUNTRIES[ck] ?: ck)) + areas.map { it.key to "${it.key} (${it.value})" }, { area = it }, Modifier.testTag("plan_area"))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Picker(t("Days", "Siku"), days, (1..6).map { "$it" to "$it" }, { days = it }, Modifier.weight(1f))
            Picker(t("Stops a day", "Vituo kwa siku"), per, (3..8).map { "$it" to "$it" }, { per = it }, Modifier.weight(1f))
        }
        DateField(t("First day", "Siku ya kwanza"), start, { if (it.isNotBlank()) start = it })
        Btn(t("Suggest visits", "Pendekeza ziara"), {
            scope.launch {
                busy = true; msg = "" to t("Working it out…", "Inapanga…")
                val r = m.api.adminGet("plan", mapOf("ck" to ck, "area" to area, "days" to days, "per" to per))
                busy = false
                if (!r.ok) { msg = "bad" to reasonText(r.reason); return@launch }
                plan = parsePlan(r.json, to, runCatching { LocalDate.parse(start) }.getOrDefault(LocalDate.now().plusDays(1)), s); day = -1; amsg = null; made++
                msg = if (plan!!.total == 0) "" to t("Nothing needs a visit there right now.", "Hakuna kinachohitaji ziara huko kwa sasa.") else null
            }
        }, Modifier.fillMaxWidth().padding(top = 14.dp).testTag("plan_go"), icon = "spark", enabled = to.isNotBlank() && !busy)
        msg?.let { (tone, text) -> Msg(text, tone) }
    }

    val p = plan ?: return
    if (p.days.isEmpty()) return
    val n = p.days.sumOf { it.size }; val km = p.days.sumOf { planMetres(it, s) }
    Card(Modifier.padding(top = 12.dp).bringIntoViewRequester(bring)) {
        Text(t("$n visits over ${p.days.size} " + (if (p.days.size == 1) "day" else "days") + " · about ${Geo.dist(km, m.prefs.miles)}", "Ziara $n kwa siku ${p.days.size} · takriban ${Geo.dist(km, m.prefs.miles)}"), style = MaterialTheme.typography.titleMedium, color = C.ink, modifier = Modifier.testTag("plan_sum"))
        Text(t("For ${p.to}. Straight lines between sites; roads are longer.", "Kwa ${p.to}. Mistari iliyonyooka kati ya maeneo; barabara ni ndefu zaidi.") + if (p.total > n) " " + t("${p.total - n} more sites need a visit: add them below.", "Maeneo mengine ${p.total - n} yanahitaji ziara: yaongeze hapa chini.") else "", style = MaterialTheme.typography.bodySmall, color = C.muted)
        val lines = p.days.mapIndexed { i, ids -> MapLine(ids.mapNotNull { proj(it)?.gps }, OFA_COLORS[i % 6], day >= 0 && day != i) }
        val pins = p.days.flatMapIndexed { i, ids -> ids.mapIndexedNotNull { k, id -> proj(id)?.let { pr -> pr.gps?.let { MapPin(it, OFA_COLORS[i % 6], "${k + 1}", pr.title, day >= 0 && day != i) } } } }
        OfaMap(emptyList(), pins, lines, Modifier.padding(top = 12.dp).fillMaxWidth().height(300.dp).clip(RoundedCornerShape(18.dp)).testTag("plan_map"), fitKey = Triple(p.days, day, p.dates))
        Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OfaDayChip(t("All days", "Siku zote"), null, day < 0) { day = -1 }
            p.days.indices.forEach { i -> OfaDayChip(t("Day ", "Siku ") + (i + 1), Color(OFA_COLORS[i % 6]), day == i) { day = i } }
        }
    }
    p.days.forEachIndexed { i, ids ->
        val col = Color(OFA_COLORS[i % 6])
        val stops = ids.mapNotNull { proj(it) }
        val ctr = if (stops.isEmpty()) null else LatLng(stops.sumOf { it.gps!!.lat } / stops.size, stops.sumOf { it.gps!!.lng } / stops.size)
        Card(Modifier.padding(top = 12.dp).testTag("plan_day_$i"), border = col) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(col)); Spacer(Modifier.width(8.dp))
                Text(t("Day ", "Siku ") + (i + 1) + " · " + ofaDayLabel(p.dates[i]), style = MaterialTheme.typography.titleMedium, color = C.ink, modifier = Modifier.weight(1f))
                Tag((if (stops.size == 1) t("1 stop", "kituo 1") else t("${stops.size} stops", "vituo ${stops.size}")) + (if (stops.size > 1) " · ≈ " + Geo.dist(planMetres(ids, s), m.prefs.miles) else ""), C.tint, C.deep)
            }
            stops.forEachIndexed { k, pr ->
                if (k > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(col), contentAlignment = Alignment.Center) { Text("${k + 1}", color = Color.White, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold)) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f).clickable { open(pr.id) }) {
                        Text(pr.title, style = MaterialTheme.typography.titleSmall, color = C.text)
                        Text(pr.area.ifBlank { pr.country } + (if (k > 0) " · " + Geo.dist(Geo.metres(stops[k - 1].gps!!, pr.gps!!), m.prefs.miles) + t(" from the last stop", " kutoka kituo cha mwisho") else ""), style = MaterialTheme.typography.bodySmall, color = C.muted)
                        FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            planWhy(p.info[pr.id], s.repairs[pr.id]?.state).forEach { (tone, txt) -> when (tone) { "bad" -> Tag(txt, C.badBg, C.bad); "has" -> Tag(txt, C.tint, C.deep); else -> Tag(txt, C.warnBg, C.warn) } }
                        }
                    }
                    if (!p.assigned) Box(Modifier.size(40.dp).clip(CircleShape).border(1.dp, C.line, CircleShape).clickable(role = Role.Button) {
                        plan = p.copy(days = p.days.mapIndexed { j, d -> if (j == i) planOrder(d - pr.id, s) else d }, spare = listOf(pr.id) + p.spare)
                    }.semantics { contentDescription = t("Remove ", "Ondoa ") + pr.title }.testTag("plan_rm_${pr.id}"), contentAlignment = Alignment.Center) { Icon(Ic.get("x"), null, tint = C.muted, modifier = Modifier.size(16.dp)) }
                }
            }
            if (stops.isEmpty()) Text(t("No stops on this day.", "Hakuna vituo siku hii."), color = C.muted, style = MaterialTheme.typography.bodySmall)
            if (!p.assigned && p.spare.isNotEmpty()) {
                val spare = p.spare.mapNotNull { proj(it) }.sortedBy { sp -> ctr?.let { Geo.metres(it, sp.gps!!) } ?: 0.0 }.take(30)
                Picker(t("Add a stop", "Ongeza kituo"), "", listOf("" to "+ " + t("Choose a site…", "Chagua eneo…")) + spare.map { sp -> sp.id to sp.title + (ctr?.let { " · " + Geo.dist(Geo.metres(it, sp.gps!!), m.prefs.miles) } ?: "") }, { id ->
                    if (id.isNotBlank()) { plan = p.copy(days = p.days.mapIndexed { j, d -> if (j == i) planOrder(d + id, s) else d }, spare = p.spare - id); day = i }
                }, Modifier.testTag("plan_add_$i"))
            }
            if (stops.isNotEmpty()) mapsLegs(stops, null).firstOrNull()?.let { (_, url) ->
                Btn(t("Route in Google Maps", "Njia kwenye Google Maps"), { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }, Modifier.padding(top = 10.dp), small = true, light = true, icon = "compass")
            }
        }
    }
    Card(Modifier.padding(top = 12.dp)) {
        Field(note, { note = it.take(200) }, t("Note for ", "Maelezo kwa ") + p.to + " " + t("(optional)", "(si lazima)"), placeholder = t("e.g. take the water test kit", "mf. beba vifaa vya kupima maji"))
        Btn(if (p.assigned) t("Assigned", "Imegawiwa") else t("Assign $n visits to ${p.to}", "Mpe ${p.to} ziara $n"), {
            scope.launch {
                busy = true; amsg = "" to t("Assigning…", "Inagawa…")
                var made = 0; var cur = p
                for (i in p.days.indices) {
                    if (cur.days[i].isEmpty()) continue
                    val r = m.api.admin(JSONObject().put("action", "assign").put("ids", JSONArray(cur.days[i])).put("to", p.to).put("date", p.dates[i].toString())
                        .put("note", (if (note.isNotBlank()) note.trim() + " · " else "") + "Week plan, day ${i + 1} of ${p.days.size}"))
                    if (!r.ok) { busy = false; plan = cur; amsg = "bad" to reasonText(r.reason) + if (made > 0) t(" ($made assigned before this.)", " ($made zimegawiwa kabla ya hili.)") else ""; return@launch }
                    made += r.json.optJSONArray("items")?.length() ?: cur.days[i].size
                    cur = cur.copy(days = cur.days.mapIndexed { j, d -> if (j == i) emptyList() else d })
                }
                busy = false; plan = p.copy(assigned = true)
                amsg = "ok" to t("Assigned ✓ $made visits. ${p.to} sees them in “My work” and gets a notification.", "Imegawiwa ✓ ziara $made. ${p.to} anaziona kwenye “Kazi yangu” na anapata arifa.")
            }
        }, Modifier.fillMaxWidth().padding(top = 12.dp).testTag("plan_assign"), icon = if (p.assigned) "check" else "cal", enabled = n > 0 && !p.assigned && !busy)
        amsg?.let { (tone, text) -> Box(Modifier.testTag("plan_msg")) { Msg(text, tone) } }
    }
}

private fun ofaDayLabel(d: LocalDate): String = ShareHere.dayName(d.dayOfWeek.value) + " " + d.dayOfMonth + " " + DateTimeFormatter.ofPattern("MMM", if (org.rafikioutreach.pin.Lang.sw) Locale("sw") else Locale.UK).format(d)

@Composable
private fun OfaDayChip(label: String, dot: Color?, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(99.dp)).background(if (on) C.tint else C.card).border(if (on) 2.dp else 1.dp, if (on) C.ink else C.line, RoundedCornerShape(99.dp)).clickable(role = Role.Tab, onClick = onClick)
        .heightIn(min = 36.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        dot?.let { Box(Modifier.size(10.dp).clip(CircleShape).background(it)); Spacer(Modifier.width(6.dp)) }
        Text(label, style = MaterialTheme.typography.labelMedium, color = C.ink)
    }
}

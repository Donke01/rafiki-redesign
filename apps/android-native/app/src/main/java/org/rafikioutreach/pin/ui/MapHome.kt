package org.rafikioutreach.pin.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.DUE_DAYS
import org.rafikioutreach.pin.data.Geo
import org.rafikioutreach.pin.data.LatLng
import org.rafikioutreach.pin.data.Project
import org.rafikioutreach.pin.data.Snapshot
import org.rafikioutreach.pin.t
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

/*
 * The Map tab: pins coloured by how each project is doing, filter chips, search, clusters when zoomed out,
 * a card that slides up for a tapped pin (swipe it through the projects nearby) and a long press for
 * "Add a new site here" or "Measure distance from me". The map itself (tiles, map style, "where I am",
 * Google's logo) is ProjectsMap in Maps.kt.
 */

object MapHome {
    /** A point chosen with a long press on the map: the New site screen starts there. */
    var newSiteAt by mutableStateOf<LatLng?>(null)
    fun takeNewSite(): LatLng? = newSiteAt.also { newSiteAt = null }
}

/** What a pin's colour says: working, needs repair, not working, or not visited for 6 months (or never). */
object PinCond { const val OK = "ok"; const val REPAIR = "repair"; const val BAD = "bad"; const val OLD = "old" }

/** An open repair says how it is (a fixed repair is closed, so it no longer counts); otherwise a visit in the
 *  last 6 months means it was seen working, and no visit for 6 months (or none in the app) is grey. */
fun mapCondition(s: Snapshot, p: Project, now: Long = System.currentTimeMillis()): String {
    s.repairs[p.id]?.let { return if (it.state == "notworking") PinCond.BAD else PinCond.REPAIR }
    val v = s.visits[p.id] ?: return PinCond.OLD
    val at = runCatching { Instant.parse(v.at).toEpochMilli() }.getOrNull() ?: return PinCond.OLD
    return if ((now - at) / 864e5 >= DUE_DAYS) PinCond.OLD else PinCond.OK
}
fun condRank(c: String) = when (c) { PinCond.BAD -> 3; PinCond.REPAIR -> 2; PinCond.OLD -> 1; else -> 0 }
/** Deep enough for white numbers on top, with a white edge that reads on satellite photos. */
fun condArgb(c: String): Int = when (c) { PinCond.BAD -> 0xFFB3261E; PinCond.REPAIR -> 0xFFB86E00; PinCond.OLD -> 0xFF66727C; else -> 0xFF1C7C4A }.toInt()
fun condName(c: String) = when (c) {
    PinCond.BAD -> t("Not working", "Haifanyi kazi"); PinCond.REPAIR -> t("Needs repair", "Inahitaji matengenezo")
    PinCond.OLD -> t("No visit in 6 months", "Bila ziara miezi 6"); else -> t("Working", "Inafanya kazi")
}

/** The filter chips over the map. The type filter is the same one as in Projects. */
val MAP_STATUS = listOf(Triple("broken", "Broken|Zimeharibika", "alert"), Triple("due", "Due a visit|Inasubiri ziara", "cal"), Triple("mine", "My assigned visits|Ziara nilizopewa", "person"))
val MAP_TYPES = listOf(Triple("water", "drop", "blue"), Triple("school", "school", "purple"), Triple("health", "cross", "coral"), Triple("home", "house", "teal"), Triple("farm", "leaf", "green"))
fun mapStatusOk(s: Snapshot, p: Project, k: String): Boolean = when (k) {
    "broken" -> s.repairs.containsKey(p.id)
    "due" -> ageDays(s, p) >= DUE_DAYS || testDue(s, p)
    "mine" -> s.assigned.any { it.id == p.id } || p.id in s.jobs
    else -> true
}

data class HomePin(val id: String, val ll: LatLng, val cond: String)

/** Groups pins that would sit within [radiusPx] of each other at [zoom] (256-pixel tiles, as osmdroid draws them).
 *  Past street level every pin stands alone. The worst pins seed the groups, so a broken pump is never hidden. */
fun clusterPins(pins: List<HomePin>, zoom: Double, radiusPx: Double): List<List<HomePin>> {
    if (zoom >= 15.0 || pins.size < 2) return pins.map { listOf(it) }
    val scale = 256.0 * 2.0.pow(zoom)
    val xy = pins.map { p ->
        val la = Math.toRadians(p.ll.lat.coerceIn(-85.0, 85.0))
        ((p.ll.lng + 180) / 360 * scale) to ((1 - ln(tan(la) + 1 / cos(la)) / PI) / 2 * scale)
    }
    val order = pins.indices.sortedWith(compareByDescending<Int> { condRank(pins[it].cond) }.thenBy { pins[it].id })
    val used = BooleanArray(pins.size)
    val out = mutableListOf<List<HomePin>>()
    val r2 = radiusPx * radiusPx
    for (i in order) {
        if (used[i]) continue
        used[i] = true
        val g = mutableListOf(pins[i])
        for (j in order) if (!used[j]) {
            val dx = xy[i].first - xy[j].first; val dy = xy[i].second - xy[j].second
            if (dx * dx + dy * dy <= r2) { used[j] = true; g += pins[j] }
        }
        out += g
    }
    return out
}

/** The pins and number bubbles on the map. Rebuilt only when the pins, the chosen one or the zoom (in half steps) change. */
class HomePins(private val mv: MapView, private val dens: Float) {
    var onPin: (String) -> Unit = {}
    var animate = true
    private var pins: List<HomePin> = emptyList()
    private var sel: String? = null
    private val shown = mutableListOf<Marker>()
    private var key: Any? = null
    private val icons = HashMap<String, BitmapDrawable>()
    private val listener = object : MapListener {
        override fun onScroll(e: ScrollEvent?) = false
        override fun onZoom(e: ZoomEvent?): Boolean { mv.post { refresh() }; return false }
    }
    init { mv.addMapListener(listener) }

    fun set(p: List<HomePin>, selected: String?) { pins = p; sel = selected; refresh() }
    fun detach() { mv.removeMapListener(listener); shown.forEach { mv.overlays.remove(it) }; shown.clear(); mv.invalidate() }

    fun refresh() {
        val z = floor(mv.zoomLevelDouble * 2) / 2
        val k = Triple(pins, sel, z)
        if (k == key) return
        key = k
        shown.forEach { mv.overlays.remove(it) }; shown.clear()
        // the chosen pin always stands alone, on top
        val groups = clusterPins(pins.filter { it.id != sel }, z, 30.0 * dens) + pins.filter { it.id == sel }.map { listOf(it) }
        groups.forEach { g -> shown += if (g.size == 1) pinMarker(g[0]) else bubbleMarker(g) }
        // just above the long-press catcher, under your own dot and anything drawn later
        val at = mv.overlays.indexOfFirst { it is MapEventsOverlay } + 1
        mv.overlays.addAll(at.coerceIn(0, mv.overlays.size), shown)
        mv.invalidate()
    }

    private fun pinMarker(p: HomePin) = Marker(mv).apply {
        id = "pin:" + p.id; title = p.id; snippet = p.cond
        position = GeoPoint(p.ll.lat, p.ll.lng); icon = dot(p.cond, p.id == sel); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); setInfoWindow(null)
        setOnMarkerClickListener { _, _ -> onPin(p.id); true }
    }

    private fun bubbleMarker(g: List<HomePin>) = Marker(mv).apply {
        val worst = g.maxBy { condRank(it.cond) }.cond
        id = "cluster:" + g.size; title = g.size.toString(); snippet = worst
        position = GeoPoint(g.sumOf { it.ll.lat } / g.size, g.sumOf { it.ll.lng } / g.size)
        icon = bubble(worst, g.size); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); setInfoWindow(null)
        setOnMarkerClickListener { _, _ -> zoomInto(g); true }
    }

    /** A tap on a bubble zooms in until its pins come apart. */
    fun zoomInto(g: List<HomePin>) {
        val box = BoundingBox.fromGeoPointsSafe(g.map { GeoPoint(it.ll.lat, it.ll.lng) })
        val z = mv.zoomLevelDouble
        if (box.latitudeSpan < 1e-4 && box.longitudeSpanWithDateLine < 1e-4) { mapFly(mv, LatLng(box.centerLatitude, box.centerLongitude), (z + 3).coerceAtMost(18.0), animate); return }
        mv.zoomToBoundingBox(box.increaseByScale(1.6f), animate)
        // never leave it where it was: at least two steps closer
        mv.post { if (mv.zoomLevelDouble < z + 1) mapFly(mv, LatLng(box.centerLatitude, box.centerLongitude), z + 2, animate) }
    }

    private fun paint() = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun dot(c: String, big: Boolean): BitmapDrawable = icons.getOrPut("d$c$big") {
        val r = (if (big) 11f else 8f) * dens; val edge = (if (big) 3f else 2.5f) * dens; val halo = (if (big) 5f else 1.5f) * dens
        val n = (2 * (r + edge + halo)).roundToInt() + 2; val m = n / 2f
        val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888); val cv = Canvas(b); val p = paint()
        p.color = if (big) 0x800A78C2.toInt() else 0x47000000; cv.drawCircle(m, m, r + edge + halo, p)
        p.color = android.graphics.Color.WHITE; cv.drawCircle(m, m, r + edge, p)
        p.color = condArgb(c); cv.drawCircle(m, m, r, p)
        BitmapDrawable(mv.resources, b)
    }

    private fun bubble(c: String, count: Int): BitmapDrawable {
        val label = if (count > 99) "99+" else count.toString()
        return icons.getOrPut("b$c$label") {
            val r = (if (count < 10) 15f else if (count < 100) 18f else 21f) * dens; val edge = 3f * dens; val halo = 2f * dens
            val n = (2 * (r + edge + halo)).roundToInt() + 2; val m = n / 2f
            val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888); val cv = Canvas(b); val p = paint()
            p.color = 0x47000000; cv.drawCircle(m, m, r + edge + halo, p)
            p.color = android.graphics.Color.WHITE; cv.drawCircle(m, m, r + edge, p)
            p.color = condArgb(c); cv.drawCircle(m, m, r, p)
            p.color = android.graphics.Color.WHITE; p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.DEFAULT_BOLD; p.textSize = 14f * dens
            cv.drawText(label, m, m - (p.descent() + p.ascent()) / 2, p)
            BitmapDrawable(mv.resources, b)
        }
    }
}

fun mapFly(mv: MapView, ll: LatLng, zoom: Double, animate: Boolean) {
    val g = GeoPoint(ll.lat, ll.lng)
    if (animate) mv.controller.animateTo(g, zoom, 650L) else { mv.controller.setZoom(zoom); mv.controller.setCenter(g) }
}

fun mapFlyAll(mv: MapView, pts: List<LatLng>, animate: Boolean, maxZoom: Double = 15.0) {
    if (pts.isEmpty()) return
    if (pts.size == 1) { mapFly(mv, pts[0], maxOf(mv.zoomLevelDouble, 14.0).coerceAtMost(maxZoom), animate); return }
    val box = BoundingBox.fromGeoPointsSafe(pts.map { GeoPoint(it.lat, it.lng) })
    runCatching { mv.zoomToBoundingBox(box.increaseByScale(1.4f), animate) }
    mv.post { if (mv.zoomLevelDouble > maxZoom) mapFly(mv, LatLng(box.centerLatitude, box.centerLongitude), maxZoom, animate) }
}

/** Moves and zooms only when the phone allows animations (and the app's own motion is on). */
@Composable
fun rememberMapMotion(): Boolean {
    val ctx = LocalContext.current
    return remember { Motion.on && runCatching { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) > 0f }
}

private fun visitDay(iso: String) = runCatching {
    Instant.parse(iso).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy", if (org.rafikioutreach.pin.Lang.sw) Locale("sw") else Locale.UK))
}.getOrDefault("")

@Composable
fun MapHome(m: AppModel, open: (String) -> Unit, visit: (String) -> Unit, offline: () -> Unit, newSite: (LatLng) -> Unit) {
    val s by m.snap.collectAsState()
    val fix by m.fix.collectAsState()
    val snap = s ?: return
    val here = fix?.ll
    val ctx = LocalContext.current
    val dens = LocalDensity.current
    val focus = LocalFocusManager.current
    val anim = rememberMapMotion()
    val hp = rememberHaptics()
    val prefs = remember { ctx.getSharedPreferences("map_style", Context.MODE_PRIVATE) }
    var status by remember { mutableStateOf(prefs.getString("mapf", "") ?: "") }
    var ty by remember { mutableStateOf(m.prefs.ftype) }
    var legend by remember { mutableStateOf(!prefs.getBoolean("legend_off", false)) }
    var mv by remember { mutableStateOf<MapView?>(null) }
    var sel by remember { mutableStateOf<String?>(null) }     // the project on the card
    var anchor by remember { mutableStateOf<String?>(null) }  // the pin first tapped: the card swipes through the projects nearest it
    var spot by remember { mutableStateOf<LatLng?>(null) }    // a long press
    var measuring by remember { mutableStateOf(false) }
    var bottomH by remember { mutableIntStateOf(0) }

    val pinned = remember(snap) { snap.projects.filter { it.gps != null } }
    val conds = remember(snap) { pinned.associate { it.id to mapCondition(snap, it) } }
    val tyOn = ty.takeIf { k -> k.isNotBlank() && MAP_TYPES.any { it.first == k } && pinned.any { tgroup(it) == k } } ?: ""
    val visible = pinned.filter { mapStatusOk(snap, it, status) && (tyOn.isBlank() || tgroup(it) == tyOn) }
    val pins = visible.map { HomePin(it.id, it.gps!!, conds[it.id] ?: PinCond.OLD) }
    val order = remember(anchor, visible) {
        val a = anchor?.let { id -> pinned.firstOrNull { it.id == id } }
        if (a == null) emptyList() else (visible.filter { it.id != a.id }.sortedBy { Geo.metres(a.gps!!, it.gps!!) }.take(24).let { listOf(a) + it })
    }
    if (sel != null && pinned.none { it.id == sel }) { sel = null; anchor = null }
    val dots = remember(pinned) { pinned.map { Dot(it.id, it.gps!!, 0, false, it.title) } }

    fun pick(id: String, fly: Boolean) {
        hp.tap(); spot = null; measuring = false; anchor = id; sel = id; focus.clearFocus()
        val p = pinned.firstOrNull { it.id == id } ?: return
        val v = mv ?: return
        if (fly) mapFly(v, p.gps!!, maxOf(v.zoomLevelDouble, 15.0), anim)
    }
    fun step(d: Int) {
        val i = order.indexOfFirst { it.id == sel }.takeIf { it >= 0 } ?: return
        val p = order.getOrNull(i + d) ?: return
        hp.tap(); sel = p.id
        mv?.let { v -> if (anim) v.controller.animateTo(GeoPoint(p.gps!!.lat, p.gps.lng), v.zoomLevelDouble, 450L) else v.controller.setCenter(GeoPoint(p.gps!!.lat, p.gps.lng)) }
    }
    fun closeCards() { sel = null; anchor = null; spot = null; measuring = false }

    val pickNow by rememberUpdatedState<(String) -> Unit> { pick(it, false) }
    val onTapMap by rememberUpdatedState { focus.clearFocus(); if (sel != null || spot != null) { closeCards(); true } else false }
    val onLong by rememberUpdatedState<(LatLng) -> Unit> { ll -> hp.tap(); focus.clearFocus(); sel = null; anchor = null; measuring = false; spot = ll }

    // a long press anywhere on the map; a tap on empty map closes the card
    DisposableEffect(mv) {
        val v = mv ?: return@DisposableEffect onDispose {}
        val o = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?) = onTapMap()
            override fun longPressHelper(p: GeoPoint?): Boolean { p ?: return false; onLong(LatLng(p.latitude, p.longitude)); return true }
        })
        v.overlays.add(0, o)
        onDispose { v.overlays.remove(o) }
    }
    val layer = remember(mv) { mv?.let { HomePins(it, dens.density) } }
    DisposableEffect(layer) { onDispose { layer?.detach() } }
    SideEffect { layer?.let { it.onPin = pickNow; it.animate = anim; it.set(pins, sel) } }
    // the long-pressed spot, and the line from you to it
    DisposableEffect(mv, spot, measuring, here) {
        val v = mv; val sp = spot
        if (v == null || sp == null) return@DisposableEffect onDispose {}
        val added = mutableListOf<org.osmdroid.views.overlay.Overlay>()
        if (measuring && here != null) {
            val pts = listOf(GeoPoint(here.lat, here.lng), GeoPoint(sp.lat, sp.lng))
            added += Polyline(v).apply { setPoints(pts); outlinePaint.color = android.graphics.Color.WHITE; outlinePaint.strokeWidth = 7f * dens.density; outlinePaint.strokeCap = Paint.Cap.ROUND; infoWindow = null }
            added += Polyline(v).apply {
                setPoints(pts); outlinePaint.color = 0xFF0A78C2.toInt(); outlinePaint.strokeWidth = 3.5f * dens.density; outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.pathEffect = DashPathEffect(floatArrayOf(10f * dens.density, 7f * dens.density), 0f); infoWindow = null
            }
        }
        added += Marker(v).apply { id = "spot"; position = GeoPoint(sp.lat, sp.lng); icon = spotIcon(v, dens.density); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); setInfoWindow(null); setOnMarkerClickListener { _, _ -> true } }
        v.overlays.addAll(added); v.invalidate()
        onDispose { added.forEach { v.overlays.remove(it) }; v.invalidate() }
    }
    // a new filter: show what it leaves on the map
    val firstFit = remember { booleanArrayOf(true) }
    LaunchedEffect(status, tyOn, mv) {
        val v = mv ?: return@LaunchedEffect
        if (firstFit[0]) { firstFit[0] = false; return@LaunchedEffect }
        if (status.isNotBlank() || tyOn.isNotBlank()) mapFlyAll(v, visible.map { it.gps!! }, anim, 14.0)
    }

    val bottomDp = with(dens) { bottomH.toDp() }
    Box(Modifier.fillMaxSize().testTag("map_home")) {
        ProjectsMap(dots, here, { pick(it, false) }, Modifier.fillMaxSize(), fitKey = snap.projects.size, badgeBottom = bottomDp + 12.dp, drawDots = false, onMap = { mv = it })
        Column(Modifier.padding(12.dp).padding(end = 58.dp).align(Alignment.TopStart)) {
            Spacer(Modifier.height(56.dp)) // the search box (drawn last, so its suggestions sit over everything)
            MapChips(snap, pinned, status, tyOn, {
                status = if (status == it) "" else it; prefs.edit().putString("mapf", status).apply()
            }, {
                ty = if (tyOn == it) "" else it; m.prefs.ftype = ty
            }) { status = ""; ty = ""; prefs.edit().putString("mapf", "").apply(); m.prefs.ftype = "" }
            if ((status.isNotBlank() || tyOn.isNotBlank()) && visible.isEmpty())
                Text(t("Nothing on the map matches these filters.", "Hakuna kwenye ramani kinacholingana na vichujio hivi."), style = MaterialTheme.typography.labelMedium, color = C.ink,
                    modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(C.card).padding(horizontal = 12.dp, vertical = 8.dp).testTag("map_none"))
            val pn = pinned.size
            Card(Modifier.padding(top = 8.dp), pad = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("$pn of ${snap.projects.size} projects pinned", "Miradi $pn kati ya ${snap.projects.size} ina mahali"), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.weight(1f))
                    Text(t("${snap.projects.size - pn} to go", "${snap.projects.size - pn} zimebaki"), style = MaterialTheme.typography.bodySmall, color = C.muted)
                }
                Meter(pn.toFloat() / maxOf(1, snap.projects.size), Modifier.padding(top = 8.dp))
            }
            HereSlot(m, snap, here, Modifier.padding(top = 10.dp), visit, open)
        }
        // the key to the colours, above whatever is at the bottom; tap it away
        if (sel == null && spot == null) MapLegend(legend, Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = bottomDp + 12.dp + 36.dp)) {
            legend = !legend; prefs.edit().putBoolean("legend_off", !legend).apply()
        }
        val panel = when { sel != null -> "card"; spot != null -> "spot"; else -> "near" }
        AnimatedContent(panel, Modifier.align(Alignment.BottomCenter).padding(12.dp).onSizeChanged { bottomH = it.height }, contentAlignment = Alignment.BottomCenter, label = "mapcard",
            transitionSpec = {
                if (!anim) EnterTransition.None togetherWith ExitTransition.None
                else (slideInVertically(tween(280)) { it / 2 } + fadeIn(tween(200))) togetherWith (slideOutVertically(tween(200)) { it / 2 } + fadeOut(tween(150)))
            }) { k ->
            when (k) {
                "card" -> PreviewCard(m, snap, order, sel, conds, here, anim, { step(it) }, { closeCards() }, visit, open)
                "spot" -> spot?.let { sp ->
                    SpotCard(sp, here, measuring, m.prefs.miles, { closeCards() }, { newSite(sp) }) {
                        measuring = !measuring
                        if (measuring && here != null) mv?.let { v -> mapFlyAll(v, listOf(here, sp), anim, 16.0) }
                    }
                } ?: Box(Modifier)
                else -> NearestSheet(m, snap, here, Modifier, open)
            }
        }
        MapSearch(m, snap, here, Modifier.align(Alignment.TopStart).padding(12.dp).padding(end = 58.dp), { pick(it, true) }, { pts -> mv?.let { mapFlyAll(it, pts, anim, 14.0) }; closeCards() }) { ll ->
            mv?.let { mapFly(it, ll, 13.0, anim) }; closeCards()
        }
        // Save this area for offline (OfflineMaps.kt)
        OfflineMapControls(m, offline)
    }
}

private fun spotIcon(mv: MapView, d: Float): BitmapDrawable {
    val r = 9f * d; val n = (2 * (r + 4 * d)).roundToInt(); val m = n / 2f
    val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888); val cv = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = 0x47000000; cv.drawCircle(m, m, r + 3.5f * d, p)
    p.color = android.graphics.Color.WHITE; cv.drawCircle(m, m, r + 2.5f * d, p)
    p.color = 0xFF04263F.toInt(); cv.drawCircle(m, m, r, p)
    p.color = android.graphics.Color.WHITE; cv.drawCircle(m, m, 3f * d, p)
    return BitmapDrawable(mv.resources, b)
}

/** Broken, Due a visit, My assigned visits, then the kinds of project; each with how many there are. Swipe sideways. */
@Composable
private fun MapChips(s: Snapshot, pinned: List<Project>, status: String, ty: String, onStatus: (String) -> Unit, onType: (String) -> Unit, clear: () -> Unit) {
    // the row fades out at its right edge, so it reads as "swipe for more" rather than cut off
    Row(Modifier.fillMaxWidth().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
            drawContent()
            drawRect(Brush.horizontalGradient(0f to Color.Black, 1f - 28.dp.toPx() / size.width to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
        }.horizontalScroll(rememberScrollState()).padding(end = 20.dp).testTag("map_chips"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (status.isNotBlank() || ty.isNotBlank()) Box(
            Modifier.size(44.dp).shadow(3.dp, CircleShape).clip(CircleShape).background(C.card).border(1.dp, C.line, CircleShape)
                .clickable(role = Role.Button, onClick = clear).semantics { contentDescription = t("Clear filters", "Ondoa vichujio") }.testTag("mchip_clear"),
            contentAlignment = Alignment.Center,
        ) { Icon(Ic.get("x"), null, tint = C.ink, modifier = Modifier.size(18.dp)) }
        MAP_STATUS.forEach { (k, label, icon) ->
            val n = pinned.count { mapStatusOk(s, it, k) && (ty.isBlank() || tgroup(it) == ty) }
            val hu = when (k) { "broken" -> Hue(C.bad, C.badBg); "due" -> C.hue("due"); else -> C.hue("blue") }
            MapChip(label.split("|").let { t(it[0], it[1]) }, icon, n, status == k, hu, "mchip_$k") { onStatus(k) }
        }
        MAP_TYPES.forEach { (k, icon, hue) ->
            if (pinned.none { tgroup(it) == k }) return@forEach
            val n = pinned.count { tgroup(it) == k && mapStatusOk(s, it, status) }
            val g = TGROUPS.first { it.first == k }
            MapChip(t(g.second, g.third), icon, n, ty == k, C.hue(hue), "mchip_$k") { onType(k) }
        }
    }
}

@Composable
private fun MapChip(label: String, icon: String, n: Int, on: Boolean, hu: Hue, tag: String, onClick: () -> Unit) {
    val h = rememberHaptics()
    Row(
        Modifier.padding(vertical = 3.dp).shadow(3.dp, RoundedCornerShape(999.dp)).clip(RoundedCornerShape(999.dp)).background(if (on) hu.fg else C.card)
            .border(1.dp, if (on) hu.fg else C.line, RoundedCornerShape(999.dp)).clickable(role = Role.Checkbox) { h.tap(); onClick() }
            .heightIn(min = 44.dp).padding(start = 12.dp, end = 10.dp).testTag(tag)
            .semantics { contentDescription = "$label, $n" + if (on) ", " + t("on", "imewashwa") else "" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Ic.get(icon), null, tint = if (on) Color.White else hu.fg, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp), color = if (on) Color.White else C.ink, maxLines = 1, softWrap = false)
        Spacer(Modifier.width(7.dp))
        Text("$n", style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold), color = if (on) hu.fg else C.muted, maxLines = 1,
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) Color.White else C.bg).padding(horizontal = 7.dp, vertical = 1.dp))
    }
}

/** The colours of the pins. Tap it away; a small button brings it back (remembered on this phone). */
@Composable
private fun MapLegend(on: Boolean, modifier: Modifier, toggle: () -> Unit) {
    if (!on) {
        Box(
            modifier.size(44.dp).shadow(3.dp, CircleShape).clip(CircleShape).background(C.card).border(1.dp, C.line, CircleShape)
                .clickable(role = Role.Button, onClick = toggle).semantics { contentDescription = t("Show the map key", "Onyesha maelezo ya rangi") }.testTag("legend_show"),
            contentAlignment = Alignment.Center,
        ) { Icon(Ic.get("info"), null, tint = C.ink, modifier = Modifier.size(20.dp)) }
        return
    }
    Column(
        modifier.shadow(4.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(C.card).border(1.dp, C.line, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClickLabel = t("Hide", "Ficha"), onClick = toggle).padding(horizontal = 11.dp, vertical = 9.dp).testTag("map_legend"),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        listOf(PinCond.OK, PinCond.REPAIR, PinCond.BAD, PinCond.OLD).forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).shadow(1.dp, CircleShape).clip(CircleShape).background(Color.White).padding(2.dp).clip(CircleShape).background(Color(condArgb(c))))
                Spacer(Modifier.width(7.dp))
                Text(condName(c), style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = C.ink, maxLines = 1)
            }
        }
    }
}

/** Search on the map: projects and their villages or areas as you type (from the list on this phone, so it works
 *  with no signal), and places by name when there is signal. Choosing one flies the map there. */
@Composable
private fun MapSearch(m: AppModel, snap: Snapshot, here: LatLng?, modifier: Modifier, onProject: (String) -> Unit, onArea: (List<LatLng>) -> Unit, onPlace: (LatLng) -> Unit) {
    var q by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    var places by remember { mutableStateOf<List<Triple<String, String, LatLng>>>(emptyList()) }
    var byGoogle by remember { mutableStateOf(false) }
    val online by OfflineMaps.online.collectAsState()
    val g0 by GoogleTiles.maps.collectAsState()
    val free = OfflineMaps.google(g0) == null // Google's places only over Google's own map
    val focus = LocalFocusManager.current
    val words = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    val pinned = snap.projects.filter { it.gps != null }
    val areas = if (words.isEmpty()) emptyList() else pinned.filter { it.area.isNotBlank() }.groupBy { it.area.trim() }
        .filter { (a, l) -> val hay = (a + " " + l.first().country).lowercase(); words.all { hay.contains(it) } }.toList()
        .sortedWith(compareBy({ !it.first.lowercase().startsWith(words.first()) }, { -it.second.size })).take(2)
    val projs = if (words.isEmpty()) emptyList() else pinned.filter { p -> val hay = "${p.title} ${p.area} ${p.country}".lowercase(); words.all { hay.contains(it) } }
        .sortedWith(compareBy<Project>({ !it.title.lowercase().startsWith(words.first()) }, { here?.let { h -> Geo.metres(h, it.gps!!) } ?: 0.0 })).take(4)
    LaunchedEffect(q, online, free) {
        places = emptyList()
        if (!online || q.trim().length < 3) return@LaunchedEffect
        kotlinx.coroutines.delay(450)
        val r = runCatching { m.api.place(q.trim(), here, "", free) }.getOrNull() ?: return@LaunchedEffect
        val a = r.json.optJSONArray("places") ?: return@LaunchedEffect
        byGoogle = (0 until a.length()).any { a.optJSONObject(it)?.optString("src") == "google" }
        places = (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o -> if (o.has("lat")) Triple(o.optString("name"), o.optString("where"), LatLng(o.getDouble("lat"), o.getDouble("lng"))) else null } }.take(3)
    }
    fun done(text: String) { q = text; focus.clearFocus() }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).shadow(4.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(C.card)
                .border(if (focused) 1.5.dp else 1.dp, if (focused) C.blue else C.line, RoundedCornerShape(16.dp)).padding(start = 14.dp, end = 4.dp).testTag("map_search"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Ic.get("search"), null, tint = if (focused) C.blue else C.muted, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (q.isEmpty()) Text(t("Search a village or project", "Tafuta kijiji au mradi"), style = MaterialTheme.typography.bodyLarge, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicTextField(q, { q = it }, Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.testTag("map_search_input")
                    .semantics { contentDescription = t("Search a village or project", "Tafuta kijiji au mradi") }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = C.text), cursorBrush = SolidColor(C.blue),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = {
                        when { projs.isNotEmpty() -> { done(projs[0].title); onProject(projs[0].id) }; areas.isNotEmpty() -> { done(areas[0].first); onArea(areas[0].second.map { it.gps!! }) }; places.isNotEmpty() -> { done(places[0].first); onPlace(places[0].third) } }
                    }))
            }
            if (q.isNotEmpty()) Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) { q = "" }.semantics { contentDescription = t("Clear", "Futa") }.testTag("map_search_x"), contentAlignment = Alignment.Center) {
                Icon(Ic.get("x"), null, tint = C.muted, modifier = Modifier.size(16.dp))
            }
        }
        if (focused && words.isNotEmpty()) Column(
            Modifier.padding(top = 6.dp).fillMaxWidth().shadow(8.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(C.card).border(1.dp, C.line, RoundedCornerShape(18.dp))
                .padding(vertical = 6.dp).testTag("map_sugg"),
        ) {
            areas.forEach { (a, l) ->
                SuggRow("pin", C.hue("green"), a, t("${l.first().country} · ${l.size} projects", "${l.first().country} · miradi ${l.size}").let { if (l.size == 1) t("${l.first().country} · 1 project", "${l.first().country} · mradi 1") else it }, "sugg_a_$a") {
                    done(a); onArea(l.map { it.gps!! })
                }
            }
            projs.forEach { p ->
                val (ic, hue) = typeLook(p)
                SuggRow(ic, C.hue(hue), p.title, listOf(p.area, here?.let { Geo.dist(Geo.metres(it, p.gps!!), m.prefs.miles) } ?: "").filter { it.isNotBlank() }.joinToString(" · "), "sugg_p_${p.id}") {
                    done(p.title); onProject(p.id)
                }
            }
            places.forEachIndexed { i, (n, w, ll) -> SuggRow("globe", C.hue("slate"), n, w, "sugg_g_$i") { done(n); onPlace(ll) } }
            if (areas.isEmpty() && projs.isEmpty() && places.isEmpty())
                Text(if (online) t("Nothing found yet. Try a village, school or town.", "Bado hakuna kilichopatikana. Jaribu kijiji, shule au mji.") else t("No project or area matches.", "Hakuna mradi wala eneo linalolingana."),
                    style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            if (places.isNotEmpty()) Text(if (byGoogle) "Powered by Google" else t("Places: © OpenStreetMap contributors", "Maeneo: © OpenStreetMap contributors"), style = MaterialTheme.typography.labelSmall, color = C.muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            if (!online) Text(t("No signal: searching the projects on this phone.", "Hakuna mtandao: inatafuta miradi iliyo kwenye simu hii."), style = MaterialTheme.typography.labelSmall, color = C.muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("map_sugg_offline"))
        }
    }
}

@Composable
private fun SuggRow(icon: String, hu: Hue, title: String, sub: String, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 6.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon, hu.fg, hu.bg, 36.dp); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The card for a tapped pin: photo, how it is, the last visit and how far; swipe sideways for the projects nearest it. */
@Composable
private fun PreviewCard(m: AppModel, snap: Snapshot, order: List<Project>, sel: String?, conds: Map<String, String>, here: LatLng?, anim: Boolean,
                        step: (Int) -> Unit, close: () -> Unit, visit: (String) -> Unit, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val i = order.indexOfFirst { it.id == sel }
    val p = order.getOrNull(i) ?: snap.projects.firstOrNull { it.id == sel } ?: return
    val swipe = with(LocalDensity.current) { 56.dp.toPx() }
    var dx by remember { mutableStateOf(0f) }
    Column(
        Modifier.fillMaxWidth().shadow(12.dp, RoundedCornerShape(26.dp)).clip(RoundedCornerShape(26.dp)).background(C.card).border(1.dp, C.line, RoundedCornerShape(26.dp))
            .pointerInput(order, sel) { detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = { if (dx < -swipe) step(1) else if (dx > swipe) step(-1); dx = 0f }) { _, d -> dx += d } }
            .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 14.dp).testTag("map_card"),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 2.dp).size(40.dp, 5.dp).clip(RoundedCornerShape(99.dp)).background(C.edge))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (order.size > 1 && i >= 0) t("${i + 1} of ${order.size} nearby", "${i + 1} kati ya ${order.size} karibu") else t("Project", "Mradi"),
                    style = MaterialTheme.typography.labelMedium, color = C.ink, maxLines = 1, modifier = Modifier.testTag("card_pos"))
                if (order.size > 1) Text(t("Swipe for more", "Telezesha kuona zaidi"), style = MaterialTheme.typography.labelSmall, color = C.muted, maxLines = 1)
            }
            CardIcon("back", t("Previous project nearby", "Mradi uliotangulia karibu"), i > 0, "card_prev") { step(-1) }
            CardIcon("arrow", t("Next project nearby", "Mradi unaofuata karibu"), i >= 0 && i < order.size - 1, "card_next") { step(1) }
            CardIcon("x", t("Close", "Funga"), true, "card_x", close)
        }
        AnimatedContent(i to p, label = "cardp", transitionSpec = {
            val d = targetState.first - initialState.first
            if (!anim || d == 0) EnterTransition.None togetherWith ExitTransition.None
            else (slideInHorizontally(tween(260)) { w -> if (d > 0) w / 3 else -w / 3 } + fadeIn(tween(200))) togetherWith (slideOutHorizontally(tween(200)) { w -> if (d > 0) -w / 3 else w / 3 } + fadeOut(tween(140)))
        }, contentKey = { it.second.id }) { (_, pr) -> CardBody(m, snap, pr, conds[pr.id] ?: mapCondition(snap, pr), here) }
        Row(Modifier.padding(top = 12.dp, end = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionTile(t("Visit now", "Tembelea sasa"), "clip", true, Modifier.weight(1f), "card_visit") { visit(p.id) }
            ActionTile(t("Directions", "Maelekezo"), "nav", false, Modifier.weight(1f), "card_dir") {
                val g = p.gps ?: return@ActionTile
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + "%.6f,%.6f".format(Locale.US, g.lat, g.lng)))) }
            }
            ActionTile(t("Open project", "Fungua mradi"), "chev", false, Modifier.weight(1f), "card_open") { open(p.id) }
        }
    }
}

@Composable
private fun CardBody(m: AppModel, snap: Snapshot, p: Project, c: String, here: LatLng?) {
    val (ic, _) = typeLook(p)
    val (bg, fg) = when (c) { PinCond.BAD -> C.badBg to C.bad; PinCond.REPAIR -> C.warnBg to C.warn; PinCond.OLD -> C.hue("slate").let { it.bg to it.fg }; else -> C.okBg to C.ok }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, end = 6.dp), verticalAlignment = Alignment.Top) {
        NetImage(p.photo?.let { m.api.photoUrl(it) }, Modifier.size(78.dp).clip(RoundedCornerShape(18.dp)).background(bg)) {
            Icon(Ic.get(ic), null, tint = fg, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp), color = C.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("card_title"))
            Text(listOf(p.area, p.country).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.clip(RoundedCornerShape(999.dp)).background(bg).padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp).testTag("card_cond"), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(Color(condArgb(c)))); Spacer(Modifier.width(6.dp))
                    Text(condName(c), color = fg, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                }
            }
            if (here != null) Text(t("${Geo.dist(Geo.metres(here, p.gps!!), m.prefs.miles)} from you", "${Geo.dist(Geo.metres(here, p.gps), m.prefs.miles)} kutoka kwako"),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp), color = C.deep, maxLines = 1, modifier = Modifier.padding(top = 6.dp).testTag("card_dist"))
            val v = snap.visits[p.id]
            Text(if (v == null) t("No visit in the app yet", "Bado hakuna ziara kwenye programu") else t("Last visit ${visitDay(v.at)}", "Ziara ya mwisho ${visitDay(v.at)}") + if (v?.by?.isNotBlank() == true) " · " + v.by else "",
                style = MaterialTheme.typography.bodySmall, color = C.text, maxLines = 2, modifier = Modifier.padding(top = 2.dp).testTag("card_visit_line"))
        }
    }
}

@Composable
private fun CardIcon(icon: String, label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label }.testTag(tag), contentAlignment = Alignment.Center) {
        Icon(Ic.get(icon), null, tint = if (enabled) C.ink else C.off, modifier = Modifier.size(19.dp))
    }
}

/** A button with its icon over a short label, so three fit side by side in either language. */
@Composable
private fun ActionTile(label: String, icon: String, primary: Boolean, modifier: Modifier, tag: String, onClick: () -> Unit) {
    val hu = C.hue("map")
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(if (primary) OkBrush else SolidColor(hu.bg)).clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 58.dp).padding(horizontal = 4.dp, vertical = 8.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(Ic.get(icon), null, tint = if (primary) Color.White else hu.fg, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold), color = if (primary) Color.White else C.ink,
            textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
    }
}

/** After a long press: add a new site there, or measure how far it is from you. */
@Composable
private fun SpotCard(sp: LatLng, here: LatLng?, measuring: Boolean, miles: Boolean, close: () -> Unit, add: () -> Unit, measure: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().shadow(12.dp, RoundedCornerShape(26.dp)).clip(RoundedCornerShape(26.dp)).background(C.card).border(1.dp, C.line, RoundedCornerShape(26.dp))
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 14.dp).testTag("spot_card"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t("This spot", "Mahali hapa"), style = MaterialTheme.typography.titleMedium, color = C.ink)
                Text(sp.text(), style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 1)
            }
            CardIcon("x", t("Close", "Funga"), true, "spot_x", close)
        }
        if (measuring) {
            if (here == null) Text(t("Allow location to measure from where you are.", "Ruhusu mahali ili upime kutoka ulipo."), style = MaterialTheme.typography.bodyMedium, color = C.warn,
                modifier = Modifier.padding(top = 8.dp, end = 8.dp).testTag("spot_dist"))
            else Row(Modifier.padding(top = 8.dp, end = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.tint).padding(horizontal = 14.dp, vertical = 10.dp).testTag("spot_dist"), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.get("compass"), null, tint = C.blue, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(10.dp))
                Column {
                    Text(Geo.dist(Geo.metres(here, sp), miles), style = MaterialTheme.typography.titleLarge, color = C.ink)
                    Text(t("from you, to the ${Geo.heading(here, sp)} (straight line)", "kutoka kwako, upande wa ${Geo.heading(here, sp)} (mstari mnyoofu)"), style = MaterialTheme.typography.bodySmall, color = C.muted)
                }
            }
        }
        Row(Modifier.padding(top = 12.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(Color(0xFF9A78F0), Color(0xFF6E46C8)))).clickable(role = Role.Button, onClick = add)
                    .heightIn(min = 58.dp).padding(horizontal = 6.dp, vertical = 8.dp).testTag("spot_new"),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Icon(Ic.get("plus"), null, tint = Color.White, modifier = Modifier.size(20.dp))
                Text(t("Add a new site here", "Ongeza eneo jipya hapa"), style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold), color = Color.White,
                    textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
            }
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(if (measuring) C.blue else C.tint).clickable(role = Role.Button, onClick = measure)
                    .heightIn(min = 58.dp).padding(horizontal = 6.dp, vertical = 8.dp).testTag("spot_measure").semantics { if (measuring) contentDescription = t("Stop measuring", "Acha kupima") },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Icon(Ic.get("compass"), null, tint = if (measuring) Color.White else C.blue, modifier = Modifier.size(20.dp))
                Text(t("Measure distance from me", "Pima umbali kutoka kwangu"), style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold),
                    color = if (measuring) Color.White else C.ink, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

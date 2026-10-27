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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.stateDescription
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
 *
 * Kept clear: with a filter on, the chip row folds into one pill, the pinned bar steps aside, Nearest to you rests
 * as one slim line and the map fits the matching pins. The key is a small Key button, the buttons on the right are
 * one compact column (Offline areas and Follow me under More). Calm by default (map3): the map opens with only the pins,
 * a round search button and the column; a tap on empty map reveals the chips, the pinned bar, Nearest to you and the Key
 * (another tap or back hides them). A filter's pill, the search box, a card and "You're at" show regardless. The pins
 * (MapPins.kt) say what they are and how they are. Drawn
 * bottom to top: the bottom panel, the column on the right, the controls at the top; open, Nearest to you stops
 * under them, and the map's credit always stays in view above the bottom panel. The same as the web app (mcl…).
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

/** Fits the map to [pts] inside the part of the screen left free by what is over it: [l], [t], [r], [b] px from each edge.
 *  The middle of the pins goes to the middle of that part; no closer than [maxZoom]. */
fun mapFitPadded(mv: MapView, pts: List<LatLng>, animate: Boolean, maxZoom: Double, l: Int, t: Int, r: Int, b: Int) {
    if (pts.isEmpty() || mv.width <= 0 || mv.height <= 0) return
    val ts = MapView.getTileSystem()
    val w = (mv.width - l - r).coerceAtLeast(64); val h = (mv.height - t - b).coerceAtLeast(64)
    val box = BoundingBox.fromGeoPointsSafe(pts.map { GeoPoint(it.lat, it.lng) })
    val z = (if (pts.size == 1) maxOf(mv.zoomLevelDouble, 14.0) else ts.getBoundingBoxZoom(box, w, h).takeIf { it.isFinite() && it > 0 } ?: maxZoom).coerceIn(mv.minZoomLevel, maxZoom)
    val size = org.osmdroid.util.TileSystem.MapSize(z)
    val x = (ts.getX01FromLongitude(box.lonWest, true) + ts.getX01FromLongitude(box.lonEast, true)) / 2 * size - (l - r) / 2.0
    val y = (ts.getY01FromLatitude(box.latNorth, true) + ts.getY01FromLatitude(box.latSouth, true)) / 2 * size - (t - b) / 2.0
    val c = GeoPoint(ts.getLatitudeFromY01(y / size, true), ts.getLongitudeFromX01(x / size, true))
    if (animate) mv.controller.animateTo(c, z, 650L) else { mv.controller.setZoom(z); mv.controller.setCenter(c) }
}

/** Moves and zooms only when the phone allows animations (and the app's own motion is on). */
@Composable
fun rememberMapMotion(): Boolean {
    val ctx = LocalContext.current
    return remember { Motion.on && runCatching { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) > 0f }
}

private fun visitDay(iso: String) = runCatching {
    Instant.parse(iso).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy", if (org.rafikioutreach.pin.Lang.sw) Locale("sw") else Locale.US))
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
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("map_style", Context.MODE_PRIVATE) }
    var status by remember { mutableStateOf(prefs.getString("mapf", "") ?: "") }
    var ty by remember { mutableStateOf(m.prefs.ftype) }
    var mv by remember { mutableStateOf<MapView?>(null) }
    var sel by remember { mutableStateOf<String?>(null) }     // the project on the card
    var anchor by remember { mutableStateOf<String?>(null) }  // the pin first tapped: the card swipes through the projects nearest it
    var spot by remember { mutableStateOf<LatLng?>(null) }    // a long press
    var measuring by remember { mutableStateOf(false) }
    var bottomH by remember { mutableIntStateOf(0) }
    // the map, decluttered: the chip row opened from the pill, Nearest to you open, the key's colours, the tray under More
    var rowOpen by remember { mutableStateOf(false) }
    var nearOpen by remember { mutableStateOf(false) }
    var keyOpen by remember { mutableStateOf(false) }
    var tray by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var suggOpen by remember { mutableStateOf(false) } // the search's suggestions are showing: "You're at" steps aside
    var askSearch by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) } // calm: the search box open from its round button
    var quick by remember { mutableStateOf<Triple<String, Float, Float>?>(null) } // a long-pressed pin's quick actions (id, x, y px)
    var easeFor by remember { mutableStateOf<Pair<String, Long>?>(null) } // a pin to bring above its card (id, after ms)
    // "Tap the map for filters", once on this phone
    var hint by remember { mutableStateOf(!prefs.getBoolean("calm_hint", false)) }
    val styleSheet = remember { mutableStateOf(false) }
    val offlineSheet = remember { mutableStateOf(false) }
    // where things over the map are (px, in this box): the open sheet stops under the lowest of the top controls and the column
    var boxH by remember { mutableIntStateOf(0) }
    var leftPx by remember { mutableIntStateOf(0) }
    var sidePx by remember { mutableIntStateOf(0) }
    val room = remember { MapRoom() }
    // today's route, follow me, heat, the time slider and live team (MapLayers.kt)
    val extras = rememberMapExtras(m, snap, fix, open, visit)
    val clean = !MapClean.revealed // calm: the tiles over the map are tucked away
    val online by OfflineMaps.online.collectAsState()
    val offlineAreas by OfflineMaps.areas.collectAsState()
    val cmOn = countryMapOn(false)
    // a small phone (360 dp): "You're at" folds into a chip after 5 seconds
    val narrow = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 380
    // a short map (a small phone, a split screen): the extras step aside so nothing sits on anything else
    var compact by remember { mutableStateOf(false) }

    val pinned = remember(snap) { snap.projects.filter { it.gps != null } }
    val conds = remember(snap) { pinned.associate { it.id to mapCondition(snap, it) } }
    val tyOn = ty.takeIf { k -> k.isNotBlank() && MAP_TYPES.any { it.first == k } && pinned.any { tgroup(it) == k } } ?: ""
    val filtered = status.isNotBlank() || tyOn.isNotBlank()
    // the time slider shows its own dots (extras.keep), so the pins and their bubbles step aside meanwhile
    val visible = pinned.filter { extras.keep(it) && mapStatusOk(snap, it, status) && (tyOn.isBlank() || tgroup(it) == tyOn) }
    // each pin's icon (the kind), a visit or water test due, and its short name: worked out once per list
    val looks = remember(snap) { pinned.associate { it.id to Triple(typeLook(it).first, mapStatusOk(snap, it, "due"), shortName(it.title)) } }
    val pins = visible.map { p -> val l = looks[p.id]; HomePin(p.id, p.gps!!, conds[p.id] ?: PinCond.OLD, l?.first ?: "drop", l?.second == true, l?.third ?: "", snap.building(p)) }
    val order = remember(anchor, visible) {
        val a = anchor?.let { id -> pinned.firstOrNull { it.id == id } }
        if (a == null) emptyList() else (visible.filter { it.id != a.id }.sortedBy { Geo.metres(a.gps!!, it.gps!!) }.take(24).let { listOf(a) + it })
    }
    if (sel != null && pinned.none { it.id == sel }) { sel = null; anchor = null }
    val dots = remember(pinned) { pinned.map { Dot(it.id, it.gps!!, 0, false, it.title) } }
    // the map's default view fits the home country's projects (as on the web); no home country: all of them
    val home = snap.me.home.ifBlank { m.prefs.country }
    val homeDots = remember(dots, home) { if (home.isBlank()) null else pinned.filter { it.ck == home }.map { p -> dots.first { it.id == p.id } } }

    fun pick(id: String, fly: Boolean) {
        HomeLayers.follow = false // looking at a project: following you stops, as when you drag the map
        hp.tap(); spot = null; measuring = false; anchor = id; sel = id; focus.clearFocus(); tray = false; keyOpen = false; quick = null
        val p = pinned.firstOrNull { it.id == id } ?: return
        val v = mv ?: return
        if (fly) mapFly(v, p.gps!!, maxOf(v.zoomLevelDouble, 15.0), anim)
        // the map eases so the pin sits above the card (once the card is up, or the flight is over)
        easeFor = id to (if (!anim) 0L else if (fly) 700L else 300L)
    }
    fun step(d: Int) {
        val i = order.indexOfFirst { it.id == sel }.takeIf { it >= 0 } ?: return
        val p = order.getOrNull(i + d) ?: return
        hp.tap(); sel = p.id
        mv?.let { v -> if (anim) v.controller.animateTo(GeoPoint(p.gps!!.lat, p.gps.lng), v.zoomLevelDouble, 450L) else v.controller.setCenter(GeoPoint(p.gps!!.lat, p.gps.lng)) }
    }
    fun closeCards() { sel = null; anchor = null; spot = null; measuring = false; quick = null; room.cardTop = 0 }
    fun clearFilters() { status = ""; ty = ""; prefs.edit().putString("mapf", "").apply(); m.prefs.ftype = ""; rowOpen = false }
    // back closes what is open over the map, the last opened first: the card, Nearest to you, the tray or key,
    // the chip row, a stop or teammate, the map alone; only then does back leave the map (Nav.kt)
    BackStep(sel != null || spot != null) { closeCards() }
    BackStep(nearOpen) { nearOpen = false }
    BackStep(tray || keyOpen) { tray = false; keyOpen = false }
    BackStep(rowOpen && (status.isNotBlank() || ty.isNotBlank())) { rowOpen = false }
    BackStep(HomeLayers.pick != null) { HomeLayers.pick = null }
    BackStep(MapClean.revealed) { MapClean.revealed = false }
    BackStep(quick != null) { quick = null }

    // exploring the map clears the screen (feat-mea, item 7): a pin or group tapped, a pinch, the zoom buttons, a
    // double tap, a drag or "Where I am" put the revealed tiles away (the card still opens). The app's own camera
    // moves (opening, starting over, the reset after time away) don't: only a move while or just after a touch counts.
    fun calmDown() { if (MapClean.revealed) { MapClean.revealed = false; tray = false; keyOpen = false; nearOpen = false } }
    val touch = remember { MapTouch() }
    DisposableEffect(mv) {
        val v = mv
        val l = v?.let { mvv -> object : MapListener {
            fun moved() {
                if (!MapClean.revealed || !touch.recent()) return
                val c = touch.center ?: return
                val p0 = mvv.projection.toPixels(c, null); val p1 = mvv.projection.toPixels(mvv.mapCenter, null)
                val far = kotlin.math.hypot((p1.x - p0.x).toDouble(), (p1.y - p0.y).toDouble()) > 10 * dens.density
                if (far || kotlin.math.abs(mvv.zoomLevelDouble - touch.zoom) > 0.01) calmDown()
            }
            override fun onScroll(e: org.osmdroid.events.ScrollEvent?): Boolean { moved(); return false }
            override fun onZoom(e: org.osmdroid.events.ZoomEvent?): Boolean { moved(); return false }
        } }
        l?.let { v.addMapListener(it) }
        onDispose { l?.let { v.removeMapListener(it) } }
    }
    val pickNow by rememberUpdatedState<(String) -> Unit> { calmDown(); pick(it, false) }
    val layerRef = remember { mutableStateOf<HomePins?>(null) }
    // a tap on empty map: put the quick actions or the card away, fold the open chip row, or else reveal the tiles (and hide them again)
    val onTapMap by rememberUpdatedState {
        val wasTyping = typing
        focus.clearFocus()
        when {
            quick != null -> quick = null
            sel != null || spot != null -> closeCards()
            wasTyping -> {}
            rowOpen && filtered -> rowOpen = false
            else -> { hp.tap(); MapClean.revealed = !MapClean.revealed; tray = false; keyOpen = false; hint = false; if (!MapClean.revealed) nearOpen = false }
        }
        layerRef.value?.clearSpider()
        true
    }
    val onLong by rememberUpdatedState<(LatLng) -> Unit> { ll ->
        if (!extras.years) { HomeLayers.follow = false; hp.tap(); focus.clearFocus(); sel = null; anchor = null; measuring = false; spot = ll; tray = false; keyOpen = false; quick = null }
    }
    // a long press on a pin: Report a visit, Directions, Open, in a small popover by the pin
    val onLongPin by rememberUpdatedState<(String, Float, Float) -> Unit> { id, x, y ->
        if (!extras.years) { hp.long(); focus.clearFocus(); spot = null; measuring = false; tray = false; keyOpen = false; quick = Triple(id, x, y) }
    }
    // following you or showing the years: the card and "Nearest to you" step aside
    LaunchedEffect(extras.quiet) { if (extras.quiet) closeCards() }
    // the key's colours close by themselves after a few seconds
    LaunchedEffect(keyOpen) { if (keyOpen) { kotlinx.coroutines.delay(5000); keyOpen = false } }

    // a long press anywhere on the map; a tap on empty map (MapEventsOverlay gets only taps no pin took)
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
    layerRef.value = layer
    DisposableEffect(layer) { onDispose { layer?.detach() } }
    SideEffect { layer?.let { it.onExplore = { calmDown() }; it.onPin = pickNow; it.onLongPin = onLongPin; it.animate = anim; it.scope = scope; it.set(pins, sel) } }
    // the chosen pin comes clear of the card: above it and under the controls at the top
    LaunchedEffect(easeFor) {
        val (id, wait) = easeFor ?: return@LaunchedEffect
        kotlinx.coroutines.delay(wait)
        // the card's top, once it is laid out
        repeat(30) { if (room.cardTop > 0) return@repeat; androidx.compose.runtime.withFrameNanos { } }
        val v = mv ?: return@LaunchedEffect
        if (sel == id) pinned.firstOrNull { it.id == id }?.gps?.let { easeAbove(v, it, room, dens.density, anim) }
    }
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
    // searching (keyboard up, little room): only the search box and its suggestions, nothing else over the map
    val panel = when { extras.quiet || typing -> "none"; sel != null -> "card"; spot != null -> "spot"; clean -> "none"; else -> "near" }
    val sheetOpen = nearOpen && panel == "near"
    val keyShown = !clean && !typing && !compact && !extras.years && sel == null && spot == null && !sheetOpen
    val hintShown = hint && clean && panel == "none" && !typing && !extras.quiet && !extras.years && quick == null
    LaunchedEffect(hintShown) { if (hintShown) { prefs.edit().putBoolean("calm_hint", true).apply(); kotlinx.coroutines.delay(4500); hint = false } }
    // a new filter: the screen clears (pill, slim handle), then the map fits what the filter leaves, clear of what is over it
    // Map tapped again while on it (feat-mea): start over in place: filters, card, sheets and search close, and the
    // same map eases back to the default view (no new map, so no tiles reloading and no blink)
    val again = Nav.mapAgain
    val seenAgain = remember { intArrayOf(again) }
    LaunchedEffect(again) {
        if (seenAgain[0] == again) return@LaunchedEffect
        seenAgain[0] = again
        status = ""; ty = ""; sel = null; anchor = null; spot = null; measuring = false; quick = null; easeFor = null
        rowOpen = false; nearOpen = false; keyOpen = false; tray = false; searchOpen = false; askSearch = false; typing = false; suggOpen = false
        focus.clearFocus()
        val v = mv ?: return@LaunchedEffect
        val fit = (homeDots?.takeIf { it.isNotEmpty() } ?: dots).map { it.ll }
        if (fit.size == 1) mapFly(v, fit[0], 12.0, anim)
        else if (fit.size > 1) runCatching { v.zoomToBoundingBox(BoundingBox.fromGeoPointsSafe(fit.map { GeoPoint(it.lat, it.lng) }).increaseByScale(1.25f), anim) }
    }
    val firstFit = remember { booleanArrayOf(true) }
    LaunchedEffect(status, tyOn, mv) {
        val v = mv ?: return@LaunchedEffect
        if (firstFit[0]) { firstFit[0] = false; return@LaunchedEffect }
        if (status.isBlank() && tyOn.isBlank()) return@LaunchedEffect
        kotlinx.coroutines.delay(60) // the pill and the slim handle take their places first
        val g = (12 * dens.density).roundToInt()
        val low = if (keyShown && room.keyTop > 0) minOf(room.panelTop, room.keyTop) else room.panelTop
        mapFitPadded(v, visible.map { it.gps!! }, anim, 14.0, g, room.top + g, (room.w - room.sideLeft).coerceAtLeast(0) + g, (room.h - low).coerceAtLeast(0) + g)
    }

    val bottomDp = with(dens) { bottomH.toDp() }
    // the layer cards (MapLayers.kt) sit above the map's credit, which sits above whatever is at the bottom
    val cardsDp = with(dens) { HomeLayers.cardsH.toDp() }.let { if (it > 0.dp) it + 8.dp else 0.dp }
    val creditDp = 40.dp
    // open, Nearest to you stops under the lowest of the top controls and the column, with room for the credit and those cards
    val maxFull = (with(dens) { (boxH - maxOf(leftPx, sidePx)).toDp() } - 12.dp - creditDp - cardsDp - 12.dp).coerceAtLeast(120.dp)
    room.top = maxOf(leftPx, sidePx)
    Box(Modifier.fillMaxSize().onSizeChanged { boxH = it.height; room.h = it.height; room.w = it.width; compact = with(dens) { it.height.toDp() } < 600.dp }.onGloballyPositioned { room.rootY = it.positionInRoot().y.roundToInt() }.testTag("map_home")) {
        ProjectsMap(dots, here, { calmDown(); pick(it, false) }, Modifier.fillMaxSize().pointerInput(mv) {
            // every touch on the map itself (not on what's over it) notes where the camera was when it began
            awaitPointerEventScope { while (true) {
                val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Press && e.changes.count { it.pressed } == 1) mv?.let { touch.begin(GeoPoint(it.mapCenter.latitude, it.mapCenter.longitude), it.zoomLevelDouble) }
                touch.at = System.nanoTime()
            } }
        }, fitKey = snap.projects.size, badgeBottom = bottomDp + 12.dp, drawDots = false, onMap = { mv = it }, extras = extras, styleSheet = styleSheet, fitDots = homeDots)
        // 1. the bottom panel: the card, the long-pressed spot, or Nearest to you (under everything else)
        AnimatedContent(panel, Modifier.align(Alignment.BottomCenter).padding(12.dp).onSizeChanged { bottomH = it.height }.onGloballyPositioned { room.panelTop = it.boundsInParent().top.roundToInt() },
            contentAlignment = Alignment.BottomCenter, label = "mapcard",
            transitionSpec = {
                if (!anim) EnterTransition.None togetherWith ExitTransition.None
                else (slideInVertically(tween(280)) { it / 2 } + fadeIn(tween(200))) togetherWith (slideOutVertically(tween(200)) { it / 2 } + fadeOut(tween(150)))
            }) { k ->
            when (k) {
                "card" -> Box(Modifier.onGloballyPositioned { room.cardTop = it.positionInRoot().y.roundToInt() - room.rootY }) {
                    PreviewCard(m, snap, order, sel, conds, here, anim, { step(it) }, { closeCards() }, visit, open)
                }
                "spot" -> spot?.let { sp ->
                    SpotCard(sp, here, measuring, m.prefs.miles, { closeCards() }, { newSite(sp) }) {
                        measuring = !measuring
                        if (measuring && here != null) mv?.let { v -> mapFlyAll(v, listOf(here, sp), anim, 16.0) }
                    }
                } ?: Box(Modifier)
                "none" -> Box(Modifier)
                else -> NearestSheet(m, snap, here, Modifier, open, slim = filtered || compact, expanded = nearOpen, onExpand = { nearOpen = it; if (it) { tray = false; keyOpen = false } }, maxFull = maxFull,
                    pool = if (filtered) visible else null, emptyText = nearNone(tyOn, status))
            }
        }
        // 2. the Key button, bottom left above the credit (and the layers' cards: MapLayers.kt)
        val keyBottom = bottomDp + 12.dp + creditDp + cardsDp
        Stagger(keyShown, 2, anim, Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = keyBottom), up = true) {
            // (inside its animation's box: its top in the map's box from the room under it)
            MapKeyButton(keyOpen, Modifier.onGloballyPositioned { room.keyTop = room.h - with(dens) { keyBottom.roundToPx() } - it.size.height }) {
                keyOpen = !keyOpen; if (keyOpen) tray = false
            }
        }
        // the one-time hint: "Tap the map for filters"
        Stagger(hintShown, 0, anim, Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = keyBottom), up = true) { CalmHint { hint = false } }
        // 3. the controls at the top left: the chip row or its pill, the pinned bar, "You're at"
        Column(Modifier.align(Alignment.TopStart).padding(12.dp).padding(end = 60.dp).onGloballyPositioned { leftPx = it.boundsInParent().bottom.roundToInt() }) {
            Spacer(Modifier.height(50.dp)) // the search box or its button (drawn last, so its suggestions sit over everything)
            // calm, only an active filter's pill shows; revealed, the chips (staggered in)
            Stagger(!extras.years && !typing && (!clean || filtered), 0, anim) {
                Column {
                    if (filtered && !rowOpen) FilterPill(status, tyOn, visible.size, { hp.tap(); rowOpen = true }) { hp.tap(); clearFilters() }
                    else MapChips(snap, pinned, status, tyOn, {
                        status = if (status == it) "" else it; prefs.edit().putString("mapf", status).apply()
                        rowOpen = false; if (status.isNotBlank() || tyOn.isNotBlank()) nearOpen = false
                    }, {
                        ty = if (tyOn == it) "" else it; m.prefs.ftype = ty
                        rowOpen = false; if (status.isNotBlank() || ty.isNotBlank()) nearOpen = false
                    }) { clearFilters() }
                    if (filtered && visible.isEmpty())
                        Text(t("Nothing on the map matches these filters.", "Hakuna kwenye ramani kinacholingana na vichujio hivi."), style = MaterialTheme.typography.labelMedium, color = C.ink,
                            modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).background(mapGlass()).padding(horizontal = 12.dp, vertical = 7.dp).testTag("map_none"))
                }
            }
            // the pinned bar: one slim line, only with no filter and Nearest to you resting
            Stagger(!filtered && !sheetOpen && !clean && !typing && !compact, 1, anim) { PinnedBar(snap, pinned.size) }
            // "You're at" shows either way (it matters), compact
            if (!suggOpen && !typing) HereSlot(m, snap, here, Modifier.padding(top = 8.dp), visit, open, chipAfter = narrow, chipNow = compact, slim = true)
            // no signal and no map kept on this phone: say so, and where to save one
            if (!typing && !online && !cmOn && !extras.years) NoSignalNote(offlineAreas.isNotEmpty(), Modifier.padding(top = 8.dp), offline)
        }
        // 4. the column on the right: Map style, where I am, More; the compass and Follow me while they are on
        MapSide(m, mv, here, anim, { ll -> calmDown(); layer?.pulseYou(ll) }, tray, { tray = it; if (it) { keyOpen = false; nearOpen = false; quick = null } }, { styleSheet.value = true },
            Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 10.dp).onGloballyPositioned { val b = it.boundsInParent(); sidePx = b.bottom.roundToInt(); room.sideLeft = b.left.roundToInt() })
        // 5. the search (its suggestions over everything); calm, just its round button, which opens the box ready to type
        if (clean && !searchOpen) Box(
            Modifier.align(Alignment.TopStart).padding(12.dp).size(44.dp).shadow(3.dp, CircleShape).clip(CircleShape).background(mapGlass(0.9f)).border(1.dp, C.line, CircleShape)
                .clickable(role = Role.Button) { hp.tap(); hint = false; searchOpen = true; askSearch = true }.semantics { contentDescription = t("Search a village or project", "Tafuta kijiji au mradi") }.testTag("map_search_btn"),
            contentAlignment = Alignment.Center,
        ) { Icon(Ic.get("search"), null, tint = C.ink, modifier = Modifier.size(19.dp)) }
        else MapSearch(m, snap, here, Modifier.align(Alignment.TopStart).padding(12.dp).padding(end = 60.dp), askSearch, { askSearch = false }, { f -> if (!f && typing && clean) searchOpen = false; typing = f }, { suggOpen = it }, { pick(it, true) }, { pts -> HomeLayers.follow = false; mv?.let { mapFlyAll(it, pts, anim, 14.0) }; closeCards() }, maxList = (with(dens) { boxH.toDp() } - 12.dp - 50.dp - 12.dp).coerceAtLeast(120.dp)) { ll ->
            HomeLayers.follow = false; mv?.let { mapFly(it, ll, 13.0, anim) }; closeCards()
        }
        // Save this area for offline: the sheet (opened from More) and the bar while an area fills (OfflineMaps.kt)
        OfflineMapControls(m, offline, offlineSheet)
        // 6. on top: the tray under More and the key's colours; a tap anywhere else closes them
        if (tray || keyOpen || quick != null) Box(Modifier.matchParentSize().pointerInput(Unit) { awaitPointerEventScope { awaitPointerEvent(); tray = false; keyOpen = false; quick = null } }.testTag("map_tap_away"))
        MapTray(m, here, anim, tray, Modifier.align(Alignment.TopEnd).padding(top = with(dens) { maxOf(sidePx, leftPx).toDp() } + 8.dp, end = 10.dp), { tray = false }) { offlineSheet.value = true }
        MapKeyPop(keyOpen && keyShown, anim, Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = keyBottom + 50.dp),
            stops = HomeLayers.route && snap.assigned.any { it.date == java.time.LocalDate.now().toString() }, team = HomeLayers.team && snap.admin, build = pinned.any { snap.building(it) })
        // a long-pressed pin's quick actions, by the pin
        val qp = quick?.let { q -> pinned.firstOrNull { it.id == q.first }?.let { it to q } }
        PinQuick(qp?.first, qp?.second?.second ?: 0f, qp?.second?.third ?: 0f, room, anim, dens.density, { quick = null }, visit, open)
    }
}

/** Calm by default (map3): the map with its pins, a round search button, the column on the right and the credit. A tap on
 *  empty map reveals the chips, the pinned bar, Nearest to you and the Key ([revealed]); another tap, back or starting the
 *  map over (Nav.kt) tucks them away again. Kept while the app is open (this session), not saved. */
/** When the person last touched the map and where the camera was then (feat-mea): their moves, not the app's. */
internal class MapTouch {
    var at = 0L; var center: GeoPoint? = null; var zoom = 0.0
    fun begin(c: GeoPoint, z: Double) { center = c; zoom = z; at = System.nanoTime() }
    /** A finger is on the map, or was in the last moment (a fling, a double tap's zoom, a zoom button's animation). */
    fun recent() = center != null && System.nanoTime() - at < 1_500_000_000L
}

object MapClean {
    var revealed by mutableStateOf(false)
    /** Calm: the tiles are tucked away. */
    val on: Boolean get() = !revealed
}

/** Over the map: a little see-through (glass) so the map shows; text on it stays AA over any photo. Bright sun stays solid. */
@Composable
fun mapGlass(a: Float = 0.86f): Color = if (!C.glassy) C.card else C.card.copy(alpha = if (C.dark) maxOf(a, 0.9f) else a)

/** A pill drawn [v] inside its 44 dp touch area: the tiles look smaller while staying easy to tap. */
private fun Modifier.insetPill(fill: Color, edge: Color?, v: androidx.compose.ui.unit.Dp = 4.dp): Modifier = drawBehind {
    val i = v.toPx(); val h = size.height - 2 * i; val r = androidx.compose.ui.geometry.CornerRadius(h / 2, h / 2)
    drawRoundRect(Color(0x24000000), topLeft = androidx.compose.ui.geometry.Offset(0f, i + 1.2.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width, h), cornerRadius = r)
    drawRoundRect(fill, topLeft = androidx.compose.ui.geometry.Offset(0f, i), size = androidx.compose.ui.geometry.Size(size.width, h), cornerRadius = r)
    if (edge != null) drawRoundRect(edge, topLeft = androidx.compose.ui.geometry.Offset(0f, i), size = androidx.compose.ui.geometry.Size(size.width, h), cornerRadius = r, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
}

/** The tiles come and go one after another ([i]: their place in the order), a short fade and slide; none with reduced motion. */
@Composable
private fun Stagger(show: Boolean, i: Int, anim: Boolean, modifier: Modifier = Modifier, up: Boolean = false, content: @Composable () -> Unit) {
    androidx.compose.animation.AnimatedVisibility(show, modifier,
        enter = if (anim) fadeIn(tween(200, i * 40)) + slideInVertically(tween(240, i * 40)) { if (up) it / 3 else -it / 3 } else EnterTransition.None,
        exit = if (anim) fadeOut(tween(150, i * 25)) + slideOutVertically(tween(170, i * 25)) { if (up) it / 4 else -it / 4 } else ExitTransition.None) { content() }
}

/** Once on this phone: the map is calm, a tap shows the filters. */
@Composable
private fun CalmHint(close: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Row(Modifier.shadow(4.dp, shape).clip(shape).background(Color(0xE604263F)).clickable(role = Role.Button, onClick = close).heightIn(min = 44.dp).padding(start = 12.dp, end = 14.dp).testTag("map_hint"),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Ic.get("finger"), null, tint = Color.White, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(8.dp))
        Text(t("Tap the map for filters", "Gusa ramani kuona vichujio"), style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp), color = Color.White, maxLines = 1, softWrap = false)
    }
}

/** Brings a chosen pin clear of its card: if it sits under the card or the controls at the top, the map eases so the pin
 *  is in the middle of the free part above the card. */
private fun easeAbove(mv: MapView, ll: LatLng, room: MapRoom, d: Float, anim: Boolean) {
    if (mv.width <= 0 || mv.height <= 0 || room.cardTop <= 0) return
    val pt = mv.projection.toPixels(GeoPoint(ll.lat, ll.lng), null)
    val top = room.top + 26 * d; val bottom = room.cardTop - 30 * d
    if (bottom - top < 40 * d) return
    val left = 26 * d; val right = minOf(room.sideLeft.toFloat().takeIf { it > 0 } ?: mv.width.toFloat(), mv.width.toFloat()) - 10 * d
    val inX = pt.x in left.toInt()..right.toInt(); val inY = pt.y in top.toInt()..bottom.toInt()
    if (inX && inY) return
    val dy = if (inY) 0f else pt.y - (top + bottom) / 2
    val dx = if (inX) 0f else pt.x - (left + right) / 2
    val c = mv.projection.fromPixels((mv.width / 2f + dx).roundToInt(), (mv.height / 2f + dy).roundToInt())
    val g = GeoPoint(c.latitude, c.longitude)
    if (anim) mv.controller.animateTo(g, mv.zoomLevelDouble, 300L) else mv.controller.setCenter(g)
}

/** Google Maps directions to a point. */
private fun directions(ctx: Context, g: LatLng) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + "%.6f,%.6f".format(Locale.US, g.lat, g.lng)))) }
}

/** A long-pressed pin's quick actions: Report a visit, Directions, Open; a small popover above the pin (below it near the top). */
@Composable
private fun PinQuick(p: Project?, x: Float, y: Float, room: MapRoom, anim: Boolean, d: Float, close: () -> Unit, visit: (String) -> Unit, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val last = remember { mutableStateOf<Project?>(null) }
    if (p != null) last.value = p
    val pr = p ?: last.value ?: return
    androidx.compose.ui.layout.Layout({
        androidx.compose.animation.AnimatedVisibility(p != null,
            enter = if (anim) fadeIn(tween(160)) + androidx.compose.animation.scaleIn(tween(200), initialScale = 0.9f) else EnterTransition.None,
            exit = if (anim) fadeOut(tween(120)) else ExitTransition.None) {
            val shape = RoundedCornerShape(16.dp)
            Column(Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max).widthIn(min = 196.dp, max = 260.dp).shadow(10.dp, shape).clip(shape).background(C.card).border(1.dp, C.line, shape).padding(6.dp).testTag("pin_quick")) {
                Text(pr.title, style = MaterialTheme.typography.labelMedium, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 2.dp))
                QuickRow("clip", t("Report a visit", "Ripoti ziara"), "quick_visit") { close(); visit(pr.id) }
                QuickRow("nav", t("Directions", "Maelekezo"), "quick_dir") { close(); pr.gps?.let { directions(ctx, it) } }
                QuickRow("chev", t("Open", "Fungua"), "quick_open") { close(); open(pr.id) }
            }
        }
    }, Modifier.fillMaxSize()) { ms, c ->
        val pl = ms.firstOrNull()?.measure(c.copy(minWidth = 0, minHeight = 0))
        layout(c.maxWidth, c.maxHeight) {
            if (pl == null) return@layout
            val g = (12 * d).roundToInt(); val gap = (22 * d).roundToInt()
            val px = (x - pl.width / 2f).roundToInt().coerceIn(g, (c.maxWidth - pl.width - g).coerceAtLeast(g))
            val above = y.roundToInt() - gap - pl.height
            // below the pin, it leaves room for the pin's name
            val py = if (above >= room.top + g) above else (y.roundToInt() + gap + (16 * d).roundToInt()).coerceAtMost((c.maxHeight - pl.height - g).coerceAtLeast(g))
            pl.place(px, py)
        }
    }
}

@Composable
private fun QuickRow(icon: String, label: String, tag: String, onClick: () -> Unit) {
    val h = rememberHaptics()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { h.tap(); onClick() }.heightIn(min = 44.dp).padding(start = 8.dp, end = 12.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Ic.get(icon), null, tint = C.blue, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, color = C.ink, maxLines = 1, softWrap = false)
    }
}

/** Where things over the map are, in px in the map's box, as last drawn: the map fits a filter clear of them. */
private class MapRoom { var w = 0; var h = 0; var top = 0; var sideLeft = 0; var panelTop = 0; var keyTop = 0; var rootY = 0; var cardTop = 0 }

/** One pill for the active filter(s): "Water · 98" with its icon, or "Water + Broken · 12". Tap it for the whole
 *  row of chips again; × clears the filters (and the row comes back). */
@Composable
private fun FilterPill(status: String, ty: String, n: Int, open: () -> Unit, clear: () -> Unit) {
    val items = buildList {
        MAP_TYPES.firstOrNull { it.first == ty }?.let { (k, icon, hue) -> TGROUPS.firstOrNull { it.first == k }?.let { g -> add(Triple(t(g.second, g.third), icon, C.hue(hue))) } }
        MAP_STATUS.firstOrNull { it.first == status }?.let { (k, label, icon) ->
            add(Triple(label.split("|").let { t(it[0], it[1]) }, icon, when (k) { "broken" -> Hue(C.bad, C.badBg); "due" -> C.hue("due"); else -> C.hue("blue") }))
        }
    }
    if (items.isEmpty()) return
    val hu = if (items.size == 1) items[0].third else C.hue("blue")
    val label = items.joinToString(" + ") { it.first }
    val shape = RoundedCornerShape(999.dp)
    Row(Modifier.insetPill(hu.fg, null).testTag("map_pill"), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f, fill = false).clip(shape).clickable(role = Role.Button, onClick = open).heightIn(min = 44.dp).padding(start = 12.dp, end = 2.dp)
                .semantics { contentDescription = t("Filter: ", "Kichujio: ") + items.joinToString(", ") { it.first } + ", $n. " + t("Show all the filters", "Onyesha vichujio vyote") }.testTag("map_pill_open"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Ic.get(if (items.size == 1) items[0].second else "filter"), null, tint = Color.White, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(6.dp))
            // a long label ends with "…"; the count and × always show
            Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).testTag("map_pill_label"))
            Text(" · $n", style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp), color = Color.White, maxLines = 1, softWrap = false)
            Spacer(Modifier.width(3.dp))
            Icon(Ic.get("down"), null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(14.dp))
        }
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = clear).semantics { contentDescription = t("Clear filters", "Ondoa vichujio") }.testTag("map_pill_x"), contentAlignment = Alignment.Center) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(Color(0x38FFFFFF)), contentAlignment = Alignment.Center) { Icon(Ic.get("x"), null, tint = Color.White, modifier = Modifier.size(12.dp)) }
        }
    }
}

/** "116 of 181 projects pinned · 65 to go" on one line, with a thin meter under it. */
@Composable
private fun PinnedBar(snap: Snapshot, pn: Int) {
    val all = snap.projects.size
    Card(Modifier.padding(top = 6.dp).testTag("map_pinned"), pad = 7.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // never "…": on a small phone the shorter wording
            FirstFitText(listOf(t("$pn of $all projects pinned", "Miradi $pn kati ya $all ina mahali"), t("$pn/$all pinned", "$pn/$all zina mahali")),
                MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp), C.ink, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp)) // a gap, so "… ina mahali" and "0 imebaki" never run together in Kiswahili
            Text(t("${all - pn} to go", "${all - pn} imebaki"), style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = C.muted, maxLines = 1, softWrap = false)
        }
        Box(Modifier.padding(top = 5.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(99.dp)).background(C.line)) {
            Box(Modifier.fillMaxWidth(pn.toFloat() / maxOf(1, all)).height(4.dp).clip(RoundedCornerShape(99.dp)).background(OkBrush))
        }
    }
}

/** The small "Key" button: its colours pop up above it (MapKeyPop). */
@Composable
private fun MapKeyButton(open: Boolean, modifier: Modifier, toggle: () -> Unit) {
    val h = rememberHaptics()
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier.insetPill(mapGlass(0.9f), C.line).clip(shape).clickable(role = Role.Button) { h.tap(); toggle() }.heightIn(min = 44.dp).padding(start = 10.dp, end = 13.dp)
            .semantics { contentDescription = t("Map key: what the colours mean", "Maelezo ya rangi za ramani"); stateDescription = if (open) t("open", "imefunguliwa") else t("closed", "imefungwa") }.testTag("map_key"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Ic.get("info"), null, tint = C.blue, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(5.dp))
        Text(t("Key", "Rangi"), style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold), color = C.ink, maxLines = 1, softWrap = false)
    }
}

/** The colours of the pins, over the map above the Key button; closes on a tap anywhere else or by itself. */
@Composable
private fun MapKeyPop(show: Boolean, anim: Boolean, modifier: Modifier, stops: Boolean = false, team: Boolean = false, build: Boolean = false) {
    androidx.compose.animation.AnimatedVisibility(show, modifier,
        enter = if (anim) fadeIn(tween(150)) + androidx.compose.animation.scaleIn(tween(180), initialScale = 0.94f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 1f)) else EnterTransition.None,
        exit = if (anim) fadeOut(tween(120)) else ExitTransition.None) {
        val shape = RoundedCornerShape(16.dp)
        Column(
            Modifier.shadow(8.dp, shape).clip(shape).background(C.card).border(1.dp, C.line, shape).padding(horizontal = 12.dp, vertical = 10.dp).testTag("map_legend"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(PinCond.OK, PinCond.REPAIR, PinCond.BAD, PinCond.OLD).forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(13.dp).shadow(1.dp, CircleShape).clip(CircleShape).background(Color.White).padding(1.dp).border(3.dp, Color(condArgb(c)), CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(condName(c), style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp), color = C.ink, maxLines = 1, softWrap = false)
                }
            }
            // the other marks on the map: a group of pins, you, today's route stops and (office) teammates
            KeyRow(t("Visit or water test due", "Ziara au kipimo cha maji kinasubiri"), "key_due") {
                Box(Modifier.size(9.dp).clip(CircleShape).background(Color.White).padding(1.5.dp).clip(CircleShape).background(Color(PinArt.DUE_ARGB)))
            }
            // being built (feat-mea, construction)
            if (build) KeyRow(t("Being built", "Inajengwa"), "key_build") { BuildBadge(15.dp) }
            KeyRow(t("A group: tap to zoom in", "Kundi: gusa ili kukuza"), "key_cluster") {
                Box(Modifier.size(16.dp).shadow(1.dp, CircleShape).clip(CircleShape).background(Color.White).padding(1.dp)
                    .drawBehind { val w = 2.6.dp.toPx(); val st = androidx.compose.ui.graphics.drawscope.Stroke(w)
                        val tl = androidx.compose.ui.geometry.Offset(w / 2, w / 2); val sz = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
                        drawArc(Color(condArgb(PinCond.BAD)), -90f, 120f, false, tl, sz, style = st); drawArc(Color(condArgb(PinCond.OK)), 30f, 240f, false, tl, sz, style = st) },
                    contentAlignment = Alignment.Center) {
                    Text("3", color = Color(0xFF04263F), style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, lineHeight = 9.sp, fontWeight = FontWeight.ExtraBold), maxLines = 1, softWrap = false)
                }
            }
            KeyRow(t("You", "Wewe"), "key_you") { Box(Modifier.size(14.dp).clip(CircleShape).background(Color(0x38098BE1)).padding(2.dp).clip(CircleShape).background(Color.White).padding(2.dp).clip(CircleShape).background(Color(0xFF098BE1))) }
            if (stops) KeyRow(t("Today’s route stop", "Kituo cha njia ya leo"), "key_stop") {
                Box(Modifier.size(16.dp).clip(CircleShape).background(Color.White).padding(1.5.dp).clip(CircleShape).background(Color(0xFF0A78C2)), contentAlignment = Alignment.Center) {
                    Text("1", color = Color.White, style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, lineHeight = 9.sp, fontWeight = FontWeight.ExtraBold), maxLines = 1, softWrap = false)
                }
            }
            if (team) KeyRow(t("Teammate, last seen", "Mwenzako, alionekana"), "key_team") {
                Box(Modifier.size(16.dp).clip(CircleShape).background(Color.White).padding(1.5.dp).clip(CircleShape).background(Color(0xFF7A4FD0)), contentAlignment = Alignment.Center) {
                    Text("A", color = Color.White, style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, lineHeight = 9.sp, fontWeight = FontWeight.ExtraBold), maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun KeyRow(label: String, tag: String, mark: @Composable () -> Unit) {
    Row(Modifier.testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { mark() }
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp), color = C.ink, maxLines = 1, softWrap = false)
    }
}

/** With no signal and no map kept on this phone the map is a blank grid: this says why, and opens Offline maps. */
@Composable
private fun NoSignalNote(saved: Boolean, modifier: Modifier, offline: () -> Unit) {
    val hu = C.hue("offline")
    Row(
        modifier.fillMaxWidth().shadow(4.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(C.card).border(1.dp, C.line, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = offline).heightIn(min = 48.dp).padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 8.dp).testTag("map_nosignal"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(hu.bg), contentAlignment = Alignment.Center) { Icon(Ic.get("offline"), null, tint = hu.fg, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(if (saved) t("No signal: showing pins and saved map areas only.", "Hakuna mtandao: inaonyesha alama za miradi na maeneo yaliyohifadhiwa tu.")
                else t("No signal: showing pins only.", "Hakuna mtandao: inaonyesha alama za miradi tu."), style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp), color = C.ink)
            Text(t("Save an offline map", "Hifadhi ramani ya bila mtandao") + " ›", style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold), color = C.blue, modifier = Modifier.testTag("map_nosignal_go"))
        }
    }
}

/** Nearest to you with a filter on and nothing of that kind: "No water points near you". */
fun nearNone(ty: String, status: String): String = if (status.isNotBlank()) t("Nothing near you matches these filters", "Hakuna kilicho karibu nawe kinacholingana na vichujio hivi") else when (ty) {
    "water" -> t("No water points near you", "Hakuna vituo vya maji karibu nawe")
    "school" -> t("No schools near you", "Hakuna shule karibu nawe")
    "health" -> t("No health centres near you", "Hakuna vituo vya afya karibu nawe")
    "home" -> t("No children’s homes near you", "Hakuna makao ya watoto karibu nawe")
    "farm" -> t("No farms near you", "Hakuna mashamba karibu nawe")
    else -> t("No projects near you", "Hakuna miradi karibu nawe")
}

/** The column of buttons on the right: Map style, Go to where I am and More in one compact strip; under it the
 *  compass while the map is turned and Follow me while it is on (tap to stop). Every button is 48 dp. */
@Composable
private fun MapSide(m: AppModel, mv: MapView?, here: LatLng?, anim: Boolean, onMe: (LatLng) -> Unit, tray: Boolean, onTray: (Boolean) -> Unit, onStyles: () -> Unit, modifier: Modifier) {
    val follow = HomeLayers.follow
    val turned = kotlin.math.abs(((HomeLayers.rot % 360f) + 360f) % 360f).let { kotlin.math.min(it, 360f - it) } > 1f
    val shape = RoundedCornerShape(14.dp)
    val face = if (C.glassy) Color(0xE6FFFFFF) else Color.White
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.width(44.dp).shadow(3.dp, shape).clip(shape).background(face).border(1.dp, Color(0x29000000), shape).testTag("map_side")) {
            SideBtn("layers", t("Map style", "Mtindo wa ramani"), "ml_layers") { onTray(false); onStyles() }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x1F04263F)))
            SideBtn("target", t("Go to where I am", "Nenda nilipo"), "ml_me") {
                onTray(false)
                if (here != null) mv?.let { v -> mapFly(v, here, 15.0, anim); onMe(here) }
                else m.showBanner(t("Finding where you are… Turn on location.", "Inatafuta mahali ulipo… Washa mahali."))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x1F04263F)))
            SideBtn("more", t("More map tools", "Zana zaidi za ramani"), "ml_more", state = if (tray) t("open", "imefunguliwa") else t("closed", "imefungwa"), on = tray) { onTray(!tray) }
        }
        if (turned) Box(
            Modifier.size(44.dp).shadow(3.dp, shape).clip(shape).background(face).border(1.dp, Color(0x29000000), shape)
                .clickable(role = Role.Button) {
                    val v = mv ?: return@clickable
                    HomeLayers.north = true
                    if (Motion.on) v.controller.animateTo(v.mapCenter, v.zoomLevelDouble, 500L, shortest(v.mapOrientation, 0f)) else v.mapOrientation = 0f
                    HomeLayers.rot = 0f
                }.semantics { contentDescription = t("Turn the map back to north", "Rudisha ramani kaskazini") }.testTag("ml_compass"),
            contentAlignment = Alignment.Center,
        ) { Needle(Modifier.size(28.dp).rotate(HomeLayers.rot)) }
        // following: the button stays here, blue, until tapped (off, it is in the tray under More)
        if (follow) {
            val h = rememberHaptics()
            Box(
                Modifier.size(44.dp).shadow(3.dp, shape).clip(shape).background(Color(0xFF0A78C2)).clickable(role = Role.Button) { h.tap(); HomeLayers.follow = false; HomeLayers.north = false; HomeLayers.pick = null }
                    .semantics { contentDescription = t("Stop following me", "Acha kunifuata"); stateDescription = "on" }.testTag("ml_follow"),
                contentAlignment = Alignment.Center,
            ) { Icon(Ic.get("nav"), null, tint = Color.White, modifier = Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun SideBtn(icon: String, label: String, tag: String, state: String? = null, on: Boolean = false, onClick: () -> Unit) {
    val h = rememberHaptics()
    Box(
        Modifier.size(44.dp).background(if (on) Color(0x1A0A78C2) else Color.Transparent).clickable(role = Role.Button) { h.tap(); onClick() }
            .semantics { contentDescription = label; if (state != null) stateDescription = state }.testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Icon(Ic.get(icon), null, tint = Color(0xFF04263F), modifier = Modifier.size(19.dp)) }
}

/** Under More: Offline areas and Follow me, with their names; it slides open below the column. */
@Composable
private fun MapTray(m: AppModel, here: LatLng?, anim: Boolean, show: Boolean, modifier: Modifier, close: () -> Unit, offline: () -> Unit) {
    androidx.compose.animation.AnimatedVisibility(show, modifier,
        enter = if (anim) fadeIn(tween(160)) + androidx.compose.animation.expandVertically(tween(200), expandFrom = Alignment.Top) else EnterTransition.None,
        exit = if (anim) fadeOut(tween(120)) + androidx.compose.animation.shrinkVertically(tween(160), shrinkTowards = Alignment.Top) else ExitTransition.None) {
        val shape = RoundedCornerShape(18.dp)
        Column(Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max).widthIn(min = 200.dp).shadow(10.dp, shape).clip(shape).background(C.card).border(1.dp, C.line, shape).padding(6.dp).testTag("ml_tray")) {
            TrayRow("offline", C.hue("offline"), t("Offline areas", "Ramani bila mtandao"), t("Save this area for offline", "Hifadhi eneo hili kwa matumizi bila mtandao"), "offline_btn") { close(); offline() }
            if (!HomeLayers.follow) TrayRow("nav", C.hue("blue"), t("Follow me", "Nifuate"), t("Follow me while driving", "Nifuate ninapoendesha"), "ml_follow") {
                close()
                if (here == null) m.showBanner(t("Finding where you are… Turn on location to follow.", "Inatafuta mahali ulipo… Washa mahali ili ikufuate."))
                HomeLayers.follow = true; HomeLayers.north = false; HomeLayers.pick = null
            }
        }
    }
}

@Composable
private fun TrayRow(icon: String, hu: Hue, label: String, says: String, tag: String, onClick: () -> Unit) {
    val h = rememberHaptics()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { h.tap(); onClick() }.heightIn(min = 48.dp).padding(start = 6.dp, end = 14.dp)
            .semantics { contentDescription = says }.testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(hu.bg), contentAlignment = Alignment.Center) { Icon(Ic.get(icon), null, tint = hu.fg, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, color = C.ink, maxLines = 1, softWrap = false)
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
            Modifier.size(44.dp).insetPill(mapGlass(0.9f), C.line).clip(CircleShape)
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
        Modifier.insetPill(if (on) hu.fg else mapGlass(), if (on) hu.fg else C.line).clip(RoundedCornerShape(999.dp)).clickable(role = Role.Checkbox) { h.tap(); onClick() }
            .heightIn(min = 44.dp).padding(start = 10.dp, end = 8.dp).testTag(tag)
            .semantics { contentDescription = "$label, $n" + if (on) ", " + t("on", "imewashwa") else "" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Ic.get(icon), null, tint = if (on) Color.White else hu.fg, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp), color = if (on) Color.White else C.ink, maxLines = 1, softWrap = false)
        Spacer(Modifier.width(6.dp))
        Text("$n", style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold), color = if (on) hu.fg else C.muted, maxLines = 1,
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) Color.White else C.bg).padding(horizontal = 6.dp, vertical = 0.dp))
    }
}

/** Search on the map: projects and their villages or areas as you type (from the list on this phone, so it works
 *  with no signal), and places by name when there is signal. Choosing one flies the map there. */
@Composable
private fun MapSearch(m: AppModel, snap: Snapshot, here: LatLng?, modifier: Modifier, ask: Boolean, asked: () -> Unit, onFocus: (Boolean) -> Unit, onSugg: (Boolean) -> Unit,
                      onProject: (String) -> Unit, onArea: (List<LatLng>) -> Unit, maxList: androidx.compose.ui.unit.Dp = 520.dp, onPlace: (LatLng) -> Unit) {
    var q by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    // the search button of the map alone (MapClean) opens the search ready to type
    val fr = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(ask) { if (ask) { runCatching { fr.requestFocus() }; asked() } }
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
    // back closes the search box (and what was typed)
    BackStep(focused) { q = ""; focus.clearFocus() }
    val suggShown = focused && words.isNotEmpty()
    LaunchedEffect(suggShown) { onSugg(suggShown) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).shadow(3.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(if (focused) C.card else mapGlass(0.9f))
                .border(if (focused) 1.5.dp else 1.dp, if (focused) C.blue else C.line, RoundedCornerShape(14.dp)).padding(start = 12.dp, end = 2.dp).testTag("map_search"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Ic.get("search"), null, tint = if (focused) C.blue else C.muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(9.dp))
            Box(Modifier.weight(1f)) {
                if (q.isEmpty()) Text(t("Search a village or project", "Tafuta kijiji au mradi"), style = MaterialTheme.typography.bodyMedium, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicTextField(q, { q = it }, Modifier.fillMaxWidth().focusRequester(fr).onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }.testTag("map_search_input")
                    .semantics { contentDescription = t("Search a village or project", "Tafuta kijiji au mradi") }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = C.text), cursorBrush = SolidColor(C.blue),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = {
                        when { projs.isNotEmpty() -> { done(projs[0].title); onProject(projs[0].id) }; areas.isNotEmpty() -> { done(areas[0].first); onArea(areas[0].second.map { it.gps!! }) }; places.isNotEmpty() -> { done(places[0].first); onPlace(places[0].third) } }
                    }))
            }
            if (q.isNotEmpty()) Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) { q = "" }.semantics { contentDescription = t("Clear", "Futa") }.testTag("map_search_x"), contentAlignment = Alignment.Center) {
                Icon(Ic.get("x"), null, tint = C.muted, modifier = Modifier.size(16.dp))
            }
        }
        if (suggShown) Column(
            // it scrolls in the room the keyboard leaves, never past the bottom of the map
            Modifier.padding(top = 6.dp).fillMaxWidth().heightIn(max = maxList).shadow(8.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(C.card).border(1.dp, C.line, RoundedCornerShape(18.dp))
                .verticalScroll(rememberScrollState()).padding(vertical = 6.dp).testTag("map_sugg"),
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
        Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(mapGlass(0.94f)).border(1.dp, C.line, RoundedCornerShape(22.dp))
            .pointerInput(order, sel) { detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = { if (dx < -swipe) step(1) else if (dx > swipe) step(-1); dx = 0f }) { _, d -> dx += d } }
            .padding(start = 12.dp, end = 6.dp, top = 5.dp, bottom = 10.dp).testTag("map_card"),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 1.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(99.dp)).background(C.edge))
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
        Row(Modifier.padding(top = 9.dp, end = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionTile(t("Report a visit", "Ripoti ziara"), "clip", true, Modifier.weight(1f), "card_visit") { visit(p.id) }
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
        NetImage(p.photo?.let { m.api.photoUrl(it) }, Modifier.size(62.dp).clip(RoundedCornerShape(15.dp)).background(bg)) {
            Icon(Ic.get(ic), null, tint = fg, modifier = Modifier.size(25.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp), color = C.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("card_title"))
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
        Icon(Ic.get(icon), null, tint = if (enabled) C.ink else C.off, modifier = Modifier.size(17.dp))
    }
}

/** A button with its icon over a short label, so three fit side by side in either language. */
@Composable
private fun ActionTile(label: String, icon: String, primary: Boolean, modifier: Modifier, tag: String, onClick: () -> Unit) {
    val hu = C.hue("map")
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(if (primary) OkBrush else SolidColor(hu.bg)).clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 4.dp, vertical = 6.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(Ic.get(icon), null, tint = if (primary) Color.White else hu.fg, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold), color = if (primary) Color.White else C.ink,
            textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 3.dp))
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
                Text(t("New site here", "Eneo jipya hapa"), style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold), color = Color.White,
                    textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
            }
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(if (measuring) C.blue else C.tint).clickable(role = Role.Button, onClick = measure)
                    .heightIn(min = 58.dp).padding(horizontal = 6.dp, vertical = 8.dp).testTag("spot_measure").semantics { if (measuring) contentDescription = t("Stop measuring", "Acha kupima") },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Icon(Ic.get("compass"), null, tint = if (measuring) Color.White else C.blue, modifier = Modifier.size(20.dp))
                Text(t("Measure from me", "Pima kutoka kwangu"), style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold),
                    color = if (measuring) Color.White else C.ink, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

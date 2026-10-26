package org.rafikioutreach.pin.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.Fix
import org.rafikioutreach.pin.data.Geo
import org.rafikioutreach.pin.data.LatLng
import org.rafikioutreach.pin.data.Project
import org.rafikioutreach.pin.data.Snapshot
import org.rafikioutreach.pin.data.reasonText
import org.rafikioutreach.pin.t
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// The home map's layers, with small hooks in ProjectsMap (Maps.kt), PickSheet (Native.kt) and MapScreen
// (ListScreens.kt): today's route, follow me (heading-up, next stop, compass), and for admins a heat layer,
// a time slider by completion year and live team dots. Pins drop in, urgent / not-working sites pulse,
// and nothing moves when Motion.on is off. The same layers as the web app (pin.html, mpb…).

/** What the home map shows on top of the dots: kept while you move between tabs. Admin layers start off. */
object HomeLayers {
    var route by mutableStateOf(true)
    var heat by mutableStateOf("")         // "", "repairs", "people"
    var years by mutableStateOf(false)
    var year by mutableIntStateOf(0)
    var playing by mutableStateOf(false)
    var team by mutableStateOf(false)
    var follow by mutableStateOf(false)
    var north by mutableStateOf(false)
    var rot by mutableFloatStateOf(0f)     // the map's turn (osmdroid orientation)
    var pick by mutableStateOf<String?>(null)   // a stop ("s:id") or a teammate ("t:name") tapped on the map
    var tm by mutableStateOf<TeamMapData?>(null)
    var tmErr by mutableStateOf<String?>(null)
    var compass by mutableStateOf<Float?>(null)
    /** How tall the cards over the bottom of the map are (px): the map key sits above them (MapHome.kt). */
    var cardsH by mutableIntStateOf(0)
    internal var loaded = false
    internal var dropped = false
    /** For tests: a phone heading without a compass sensor. */
    var testHeading: Float? = null
    fun reset() { route = true; heat = ""; years = false; year = 0; playing = false; team = false; follow = false; north = false; rot = 0f; pick = null; tm = null; tmErr = null; compass = null; cardsH = 0; loaded = false; dropped = false; testHeading = null; MapClean.revealed = false }
    internal fun load(ctx: Context) { if (loaded) return; loaded = true; route = ctx.getSharedPreferences("map_layers", Context.MODE_PRIVATE).getBoolean("route", true) }
    internal fun saveRoute(ctx: Context, on: Boolean) { route = on; ctx.getSharedPreferences("map_layers", Context.MODE_PRIVATE).edit().putBoolean("route", on).apply() }
}

/** What the Map tab (MapHome.kt) hands to ProjectsMap: which pins it draws itself, the layers, and the sheet's switches. */
class MapExtras(
    val keep: (Project) -> Boolean,
    /** "Nearest to you" and the pin's card step aside while following or showing the years. */
    val quiet: Boolean,
    val overlay: @Composable BoxScope.(MapView, Dp) -> Unit,
    val sheet: @Composable ColumnScope.() -> Unit,
    /** The time slider is showing its own dots: the pins, their filters and their key step aside. */
    val years: Boolean = false,
)

/** Drawn over the map tiles but under the project pins: the heat, the pulse and the route's line (MapHome's HomePins goes above them). */
interface UnderPins

/** Just above the map's own tiles (the whole-country map is a tiles layer in the list too). */
internal fun aboveTiles(mv: MapView): Int = mv.overlays.indexOfLast { it is org.osmdroid.views.overlay.TilesOverlay } + 1

/** Where the next layer under the pins goes: above the tiles and what is already under the pins. */
internal fun underPinsAt(mv: MapView): Int = maxOf(aboveTiles(mv), mv.overlays.indexOfLast { it is UnderPins } + 1)

private class RouteLine(mv: MapView) : Polyline(mv), UnderPins

@Composable
fun rememberMapExtras(m: AppModel, snap: Snapshot, fix: Fix?, open: (String) -> Unit, visit: (String) -> Unit): MapExtras {
    val ctx = LocalContext.current
    HomeLayers.load(ctx)
    if (!snap.admin) { HomeLayers.heat = ""; HomeLayers.years = false; HomeLayers.playing = false; HomeLayers.team = false } // never for field users
    val yearsOn = HomeLayers.years && snap.admin
    return MapExtras(
        keep = { !yearsOn },
        quiet = HomeLayers.follow || yearsOn,
        overlay = { mv, bottom -> HomeLayersOverlay(m, snap, fix, mv, bottom, open, visit) },
        sheet = { LayerSwitches(m, snap, fix) },
        years = yearsOn,
    )
}

// --- the logic (plain functions, tested in MapLayersTest) ----------------------------------------------

private fun localDay(iso: String): LocalDate? = runCatching { Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()

/** A visit was sent for this project today: its stop is ticked off. */
fun visitedToday(s: Snapshot, id: String, today: LocalDate = LocalDate.now()): Boolean = s.visits[id]?.let { localDay(it.at) == today } ?: false

/** Today's stops in driving order (orderStops from where you are), kept for the day so the numbers don't
 *  change as you drive. The server drops a visited assignment, so the day's list is remembered here. */
fun dayStops(ctx: Context, s: Snapshot, here: LatLng?, today: LocalDate = LocalDate.now()): List<Project> {
    val sp = ctx.getSharedPreferences("map_layers", Context.MODE_PRIVATE)
    val d = today.toString()
    val mine = s.assigned.filter { it.date == d }.map { it.id }.distinct()
    val had = if (sp.getString("day", "") == d) sp.getString("ids", "").orEmpty().split(",").filter { it.isNotBlank() } else emptyList()
    val keep = had.filter { it in mine || visitedToday(s, it, today) }
    val add = mine.filter { it !in keep }
    var ids = had
    if (add.isNotEmpty() || keep.size != had.size) {
        val done = keep.filter { visitedToday(s, it, today) }
        val from = done.lastOrNull()?.let { id -> s.projects.firstOrNull { it.id == id }?.gps } ?: here
        val rest = orderStops((keep.filter { it !in done } + add).mapNotNull { id -> s.projects.firstOrNull { it.id == id } }, from).map { it.id }
        ids = done + rest + (keep + add).filter { it !in done && it !in rest }
        sp.edit().putString("day", d).putString("ids", ids.joinToString(",")).apply()
    }
    return ids.mapNotNull { id -> s.projects.firstOrNull { it.id == id && it.gps != null } }
}

/** The completion years on the map: first and last. */
fun yearSpan(s: Snapshot): IntRange? {
    val ys = s.projects.filter { it.gps != null && it.status == "Completed" && it.year != null }.map { it.year!! }
    return if (ys.isEmpty()) null else ys.min()..ys.max()
}

/** Projects completed by the end of [y]. */
fun doneBy(s: Snapshot, y: Int): List<Project> = s.projects.filter { it.gps != null && it.status == "Completed" && it.year != null && it.year <= y }

/** Heat points: open repairs (not working counts double, urgent half as much again) or people served. */
fun heatPoints(s: Snapshot, kind: String, year: Int? = null): List<Pair<LatLng, Double>> = s.projects.mapNotNull { p ->
    val g = p.gps ?: return@mapNotNull null
    if (year != null && !(p.status == "Completed" && p.year != null && p.year <= year)) return@mapNotNull null
    when (kind) {
        "repairs" -> s.repairs[p.id]?.let { r -> g to (if (r.state == "notworking") 2.0 else 1.0) * (if (r.urgency == "urgent") 1.5 else 1.0) }
        "people" -> p.people?.takeIf { it > 0 }?.let { g to it.toDouble() }
        else -> null
    }
}

private val HEAT = mapOf(
    "repairs" to listOf(0x00FFD64A, 0xFFFFD64A.toInt(), 0xFFF28C28.toInt(), 0xFFD7301F.toInt(), 0xFF8C0000.toInt()),
    "people" to listOf(0x009AD8F5, 0xFF9AD8F5.toInt(), 0xFF3EA7EE.toInt(), 0xFF0A5FB0.toInt(), 0xFF3B1E8F.toInt()),
)
private fun palette(kind: String): IntArray {
    val cs = HEAT[kind] ?: HEAT.getValue("repairs")
    return IntArray(256) { i ->
        val f = i / 255f * (cs.size - 1); val k = min(cs.size - 2, f.toInt()); val u = f - k
        val a = cs[k]; val b = cs[k + 1]
        fun ch(sh: Int) = (((a shr sh) and 255) * (1 - u) + ((b shr sh) and 255) * u).roundToInt()
        (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

// --- the drawing on the map ------------------------------------------------------------------------------

private fun dp(ctx: Context) = ctx.resources.displayMetrics.density

private fun stopIcon(ctx: Context, n: Int, done: Boolean, next: Boolean): BitmapDrawable {
    val d = dp(ctx); val s = ((if (next) 38 else 30) * d).toInt() + (if (next) (10 * d).toInt() else 0)
    val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG); val cx = s / 2f
    val r = (if (next) 19 else 15) * d
    if (next) { p.color = 0x477A4FD0; c.drawCircle(cx, cx, r + 5 * d, p) }
    p.color = 0x55000000; c.drawCircle(cx, cx + 1.5f * d, r, p)
    p.color = android.graphics.Color.WHITE; c.drawCircle(cx, cx, r, p)
    p.color = if (done) 0xFF1C9C5A.toInt() else if (next) 0xFF7A4FD0.toInt() else 0xFF0A78C2.toInt(); c.drawCircle(cx, cx, r - 2.6f * d, p)
    p.color = android.graphics.Color.WHITE
    if (done) {
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f * d; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        val path = android.graphics.Path().apply { moveTo(cx - 6 * d, cx + 0.5f * d); lineTo(cx - 1.8f * d, cx + 4.6f * d); lineTo(cx + 6.5f * d, cx - 4.5f * d) }
        c.drawPath(path, p)
    } else { p.textSize = (if (next) 16 else 13) * d; p.typeface = Typeface.DEFAULT_BOLD; p.textAlign = Paint.Align.CENTER; c.drawText("$n", cx, cx + p.textSize * 0.36f, p) }
    return BitmapDrawable(ctx.resources, b)
}

private fun yearIcon(ctx: Context, fresh: Boolean): BitmapDrawable {
    val d = dp(ctx); val s = (34 * d).toInt(); val cx = s / 2f
    val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
    if (fresh) { p.color = 0x262FA8F5; c.drawCircle(cx, cx, 16 * d, p); p.color = 0x592FA8F5; c.drawCircle(cx, cx, 12 * d, p) }
    p.color = 0x66000000; c.drawCircle(cx, cx + 1.2f * d, 9 * d, p)
    p.color = android.graphics.Color.WHITE; c.drawCircle(cx, cx, 9 * d, p)
    p.color = if (fresh) 0xFF2FA8F5.toInt() else 0xFF0A78C2.toInt(); c.drawCircle(cx, cx, 6.6f * d, p)
    return BitmapDrawable(ctx.resources, b)
}

private val MATE = listOf(0xFF0A78C2, 0xFF1C9C5A, 0xFFD98200, 0xFF7A4FD0, 0xFFD23C32, 0xFF0E8F96).map { it.toInt() }
private fun mateIcon(ctx: Context, name: String, color: Int, label: String, faded: Boolean): BitmapDrawable {
    val d = dp(ctx); val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10 * d; typeface = Typeface.DEFAULT_BOLD }
    val w = max(72 * d, p.measureText(label) + 20 * d).toInt(); val h = (66 * d).toInt(); val r = 21 * d
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b)
    val cx = w / 2f; val cy = r + 2 * d
    p.color = 0x55000000; c.drawCircle(cx, cy + 1.5f * d, r, p)
    p.color = android.graphics.Color.WHITE; c.drawCircle(cx, cy, r, p)
    p.color = color; p.alpha = if (faded) 150 else 255; c.drawCircle(cx, cy, r - 3 * d, p); p.alpha = 255
    p.color = android.graphics.Color.WHITE; p.textSize = 13 * d; p.typeface = Typeface.DEFAULT_BOLD; p.textAlign = Paint.Align.CENTER
    c.drawText(initialsOf(name), cx, cy + 4.5f * d, p)
    p.textSize = 10 * d; val tw = p.measureText(label) + 12 * d; val top = cy + r + 3 * d
    p.color = 0x33000000; c.drawRoundRect(RectF(cx - tw / 2, top + 1 * d, cx + tw / 2, top + 17 * d), 9 * d, 9 * d, p)
    p.color = android.graphics.Color.WHITE; c.drawRoundRect(RectF(cx - tw / 2, top, cx + tw / 2, top + 16 * d), 8 * d, 8 * d, p)
    p.color = 0xFF04263F.toInt(); c.drawText(label, cx, top + 11.5f * d, p)
    return BitmapDrawable(ctx.resources, b)
}

/** You, while following: a blue dot with a soft cone the way you're heading. */
private fun meIcon(ctx: Context, cone: Boolean): BitmapDrawable {
    val d = dp(ctx); val s = (72 * d).toInt(); val cx = s / 2f
    val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
    if (cone) {
        p.shader = RadialGradient(cx, cx, 34 * d, intArrayOf(0x99098BE1.toInt(), 0x00098BE1), null, Shader.TileMode.CLAMP)
        c.drawArc(RectF(cx - 34 * d, cx - 34 * d, cx + 34 * d, cx + 34 * d), -90f - 32f, 64f, true, p); p.shader = null
    }
    p.color = 0x38098BE1; c.drawCircle(cx, cx, 16 * d, p)
    p.color = android.graphics.Color.WHITE; c.drawCircle(cx, cx, 11 * d, p)
    p.color = 0xFF098BE1.toInt(); c.drawCircle(cx, cx, 8 * d, p)
    return BitmapDrawable(ctx.resources, b)
}

/** A soft pulse around sites that are not working or need an urgent repair. */
private class PulseOverlay(private val density: Float) : Overlay(), UnderPins {
    var pts: List<Pair<GeoPoint, Int>> = emptyList()
    var animate = true
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pt = android.graphics.Point()
    override fun draw(c: Canvas, pj: Projection) {
        if (pts.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        p.strokeWidth = 2.6f * density
        pts.forEach { (g, col) ->
            pj.toPixels(g, pt)
            if (!animate) { p.color = col; p.alpha = 90; c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 15 * density, p); return@forEach }
            val f = (now % 2400) / 2400f
            p.color = col; p.alpha = ((1 - f) * 130).toInt(); c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), (10 + 18 * f) * density, p)
        }
    }
}

/** The heat layer: a small picture of the screen, recoloured from how much is close by. */
private class HeatOverlay(private val density: Float) : Overlay(), UnderPins {
    var kind = ""; var pts: List<Pair<GeoPoint, Double>> = emptyList()
    private var key: Any? = null; private var bmp: Bitmap? = null; private var at = RectF()
    private val pt = android.graphics.Point()
    override fun draw(c: Canvas, pj: Projection) {
        if (kind.isEmpty() || pts.isEmpty()) return
        val w = c.width; val h = c.height; if (w <= 0 || h <= 0) return
        val turned = abs(pj.orientation % 360f) > 0.5f
        val dg = if (turned) hypot(w.toFloat(), h.toFloat()) else 0f
        val ox = if (turned) ((dg - w) / 2).toInt() else 0; val oy = if (turned) ((dg - h) / 2).toInt() else 0
        val k = Triple(kind to pts, pj.zoomLevel, Triple(pj.boundingBox.centerLatitude, pj.boundingBox.centerLongitude, Triple(w, h, turned)))
        if (k != key || bmp == null) { key = k; bmp = render(pj, w, h, ox, oy) }
        val b = bmp ?: return
        at.set(-ox.toFloat(), -oy.toFloat(), (w + ox).toFloat(), (h + oy).toFloat())
        c.drawBitmap(b, null, at, Paint(Paint.FILTER_BITMAP_FLAG))
    }
    private fun render(pj: Projection, w: Int, h: Int, ox: Int, oy: Int): Bitmap {
        val s = 4; val bw = (w + 2 * ox) / s + 1; val bh = (h + 2 * oy) / s + 1
        val r = 42 * density / s; val cell = 14 * density / s
        val grid = HashMap<Long, DoubleArray>()
        pts.forEach { (g, wt) ->
            pj.toPixels(g, pt); val x = (pt.x + ox) / s.toFloat(); val y = (pt.y + oy) / s.toFloat()
            if (x < -r || y < -r || x > bw + r || y > bh + r) return@forEach
            val key = (x / cell).toLong() * 100000 + (y / cell).toLong()
            val a = grid.getOrPut(key) { DoubleArray(3) }; a[0] += x * wt; a[1] += y * wt; a[2] += wt
        }
        var mx = if (kind == "people") 1500.0 else 2.0; grid.values.forEach { if (it[2] > mx) mx = it[2] }
        val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888); val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        grid.values.forEach { a ->
            val x = (a[0] / a[2]).toFloat(); val y = (a[1] / a[2]).toFloat(); val al = (max(0.18, min(1.0, sqrt(a[2] / mx))) * 255).toInt()
            p.shader = RadialGradient(x, y, r, intArrayOf(al shl 24, ((al * 0.6).toInt()) shl 24, 0), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r, p)
        }
        val px = IntArray(bw * bh); b.getPixels(px, 0, bw, 0, 0, bw, bh); val pal = palette(kind)
        for (i in px.indices) { val a = px[i] ushr 24; px[i] = if (a < 6) 0 else (min(215, (40 + a * 0.75).toInt()) shl 24) or pal[a] }
        b.setPixels(px, 0, bw, 0, 0, bw, bh)
        return b
    }
}

/** Notices you dragging the map (stops following) and turning it (the compass shows). */
private class TouchWatch(private val density: Float, val onDrag: () -> Unit, val onTurn: (Float) -> Unit) : Overlay() {
    private var x0 = 0f; private var y0 = 0f; private var moved = false
    override fun onTouchEvent(e: MotionEvent, mv: MapView): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; moved = false }
            MotionEvent.ACTION_MOVE -> if (!moved && e.pointerCount == 1 && hypot(e.x - x0, e.y - y0) > 18 * density) { moved = true; onDrag() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onTurn(mv.mapOrientation)
        }
        if (e.pointerCount > 1) onTurn(mv.mapOrientation)
        return false
    }
}

private class LayerState {
    var key: Any? = null; var route: List<Overlay> = emptyList()
    var pulse: PulseOverlay? = null; var heat: HeatOverlay? = null; var watch: TouchWatch? = null; var rotate: RotationGestureOverlay? = null
    var years = HashMap<String, Marker>(); var team: List<Marker> = emptyList(); var teamKey: Any? = null
    var me: Marker? = null; var meCone: Boolean? = null; var fixed = false
}

internal fun shortest(from: Float, to: Float): Float { val d = (((to - from) % 360f) + 540f) % 360f - 180f; return from + d }

/** Drops markers in from a little above, fading in, north to south. */
private suspend fun dropIn(mv: MapView, ms: List<Marker>) {
    if (ms.isEmpty() || !Motion.on) return
    val home = ms.associateWith { it.position.clone() }
    val bb = mv.boundingBox; val perPx = (bb.latNorth - bb.latSouth) / max(1, mv.height); val lift = 40 * dp(mv.context) * perPx
    val order = ms.sortedByDescending { it.position.latitude }.withIndex().associate { (i, m) -> m to min(i * 14L, 700L) }
    ms.forEach { it.alpha = 0f; it.position = GeoPoint(home.getValue(it).latitude + lift, home.getValue(it).longitude) }
    mv.invalidate()
    val total = 600L + (order.values.maxOrNull() ?: 0L); var el = 0L
    try { while (true) {
        ms.forEach { m ->
            val f = ((el - order.getValue(m)) / 600f).coerceIn(0f, 1f)
            val e = 1 - (1 - f) * (1 - f) * (1 - f) * (1 + 2.2f * f) // a small bounce at the end
            val g = home.getValue(m); m.alpha = min(1f, f * 1.8f); m.position = GeoPoint(g.latitude + lift * (1 - e), g.longitude)
        }
        mv.invalidate()
        if (el >= total) break
        delay(16); el += 16
    } } finally { ms.forEach { it.alpha = 1f; it.position = home.getValue(it) }; mv.invalidate() }
}

@Composable
private fun BoxScope.HomeLayersOverlay(m: AppModel, snap: Snapshot, fix: Fix?, mv: MapView, bottom: Dp, open: (String) -> Unit, visit: (String) -> Unit) {
    val ctx = LocalContext.current
    val d = dp(ctx)
    val st = remember(mv) { LayerState() }
    DisposableEffect(Unit) { onDispose { HomeLayers.cardsH = 0 } }
    val today = LocalDate.now()
    val here = fix?.ll
    val stops = if (HomeLayers.route) dayStops(ctx, snap, here, today) else emptyList()
    val yearsOn = HomeLayers.years && snap.admin
    val pickNow by rememberUpdatedState(HomeLayers.pick)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // set up once: the heat and pulse layers over the tiles (a street, satellite or whole-country map) and under the pins,
    // the touch watcher, two-finger turning
    DisposableEffect(mv) {
        val heat = HeatOverlay(d).also { st.heat = it }; val pulse = PulseOverlay(d).also { st.pulse = it }
        val watch = TouchWatch(d, onDrag = { if (HomeLayers.follow) HomeLayers.follow = false }, onTurn = { HomeLayers.rot = it }).also { st.watch = it }
        val rg = RotationGestureOverlay(mv).apply { isEnabled = true }.also { st.rotate = it }
        aboveTiles(mv).let { at -> mv.overlays.add(at, heat); mv.overlays.add(at + 1, pulse) }; mv.overlays.add(rg); mv.overlays.add(watch)
        onDispose {
            mv.overlays.removeAll(listOf(heat, pulse, rg, watch) + st.route + st.years.values + st.team + listOfNotNull(st.me))
            st.route = emptyList(); st.years.clear(); st.team = emptyList(); st.me = null
        }
    }
    // today's route: a line through the stops, numbered, ticked once visited
    val doneIds = stops.filter { visitedToday(snap, it.id, today) }.map { it.id }
    val nextIdx = stops.indexOfFirst { it.id !in doneIds }
    val routeKey = listOf(stops.map { it.id }, doneIds, yearsOn, here?.let { (it.lat * 2000).roundToInt() to (it.lng * 2000).roundToInt() })
    if (st.key != routeKey) {
        st.key = routeKey
        mv.overlays.removeAll(st.route)
        val out = mutableListOf<Overlay>()
        if (!yearsOn && stops.isNotEmpty()) {
            val pts = stops.map { GeoPoint(it.gps!!.lat, it.gps.lng) }
            if (pts.size > 1) {
                out += RouteLine(mv).apply { setPoints(pts); outlinePaint.color = android.graphics.Color.WHITE; outlinePaint.alpha = 220; outlinePaint.strokeWidth = 10 * d; outlinePaint.strokeCap = Paint.Cap.ROUND; outlinePaint.strokeJoin = Paint.Join.ROUND; infoWindow = null; setOnClickListener { _, _, _ -> false } }
                for (i in 1 until pts.size) out += RouteLine(mv).apply {
                    setPoints(listOf(pts[i - 1], pts[i])); outlinePaint.color = if (stops[i].id in doneIds) 0xFF1C9C5A.toInt() else 0xFF0A78C2.toInt()
                    outlinePaint.strokeWidth = 5 * d; outlinePaint.strokeCap = Paint.Cap.ROUND; infoWindow = null; setOnClickListener { _, _, _ -> false }
                }
            }
            if (here != null && nextIdx >= 0) out += RouteLine(mv).apply {
                setPoints(listOf(GeoPoint(here.lat, here.lng), pts[nextIdx])); outlinePaint.color = 0xFF0A78C2.toInt(); outlinePaint.strokeWidth = 3 * d; outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(1f, 9 * d), 0f); infoWindow = null; setOnClickListener { _, _, _ -> false }
            }
            stops.forEachIndexed { i, p ->
                out += Marker(mv).apply {
                    position = pts[i]; icon = stopIcon(ctx, i + 1, p.id in doneIds, i == nextIdx); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); setInfoWindow(null)
                    title = t("Stop ${i + 1}: ", "Kituo ${i + 1}: ") + p.title
                    setOnMarkerClickListener { _, _ -> HomeLayers.pick = if (pickNow == "s:" + p.id) null else "s:" + p.id; true }
                }
            }
        }
        // the lines go over the tiles and under the pins; the numbered stops over the pins
        st.route = out; mv.overlays.addAll(underPinsAt(mv), out.filterIsInstance<RouteLine>()); mv.overlays.addAll(out.filter { it !is RouteLine }); mv.invalidate()
    }
    // only not-working sites pulse, gently (map3; not on the history view), and not with reduced motion
    val moving = rememberMapMotion() && Motion.loops
    st.pulse?.let { pl ->
        pl.pts = if (yearsOn) emptyList() else snap.projects.mapNotNull { p -> val r = snap.repairs[p.id]; val g = p.gps; if (g != null && r != null && r.state == "notworking") GeoPoint(g.lat, g.lng) to 0xFFB3261E.toInt() else null }
        pl.animate = moving
    }
    if (st.pulse?.pts?.isNotEmpty() == true && moving) LaunchedEffect(mv) { while (isActive) { mv.postInvalidate(); delay(50) } }
    // heat
    val heatKind = if (snap.admin) HomeLayers.heat else ""
    st.heat?.let { h ->
        val pts = if (heatKind.isEmpty()) emptyList() else heatPoints(snap, heatKind, if (yearsOn) HomeLayers.year else null).map { GeoPoint(it.first.lat, it.first.lng) to it.second }
        if (h.kind != heatKind || h.pts != pts) { h.kind = heatKind; h.pts = pts; mv.invalidate() }
    }
    // the years: projects completed by the year on the slider, the new ones dropping in
    val span = yearSpan(snap)
    if (yearsOn && span != null) {
        if (HomeLayers.year !in span) HomeLayers.year = span.last
        val want = doneBy(snap, HomeLayers.year).associateBy { it.id }
        val gone = st.years.keys.filter { it !in want }
        gone.forEach { mv.overlays.remove(st.years.remove(it)) }
        val first = st.years.isEmpty()
        val added = want.values.filter { it.id !in st.years }.map { p ->
            Marker(mv).apply {
                position = GeoPoint(p.gps!!.lat, p.gps.lng); icon = yearIcon(ctx, !first && p.year == HomeLayers.year); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); setInfoWindow(null)
                title = p.title + " · " + p.year; setOnMarkerClickListener { _, _ -> open(p.id); true }
            }.also { st.years[p.id] = it }
        }
        // last year's "new" glow goes back to plain
        st.years.forEach { (id, mk) -> if (mk !in added) want[id]?.let { if (it.year != HomeLayers.year || first) mk.icon = yearIcon(ctx, false) } }
        if (added.isNotEmpty()) { mv.overlays.addAll(added); mv.invalidate() }
        if (added.isNotEmpty()) androidx.compose.runtime.SideEffect {
            val box = org.osmdroid.util.BoundingBox.fromGeoPointsSafe(added.map { it.position })
            scope.launch {
                if (first) { delay(80); runCatching { mv.zoomToBoundingBox(box.increaseByScale(1.4f), false); if (mv.zoomLevelDouble > 9.0) mv.controller.setZoom(9.0) } }
                dropIn(mv, added)
            }
        }
    } else if (st.years.isNotEmpty()) { mv.overlays.removeAll(st.years.values); st.years.clear(); mv.invalidate() }
    if (HomeLayers.playing) LaunchedEffect(Unit) {
        val sp = yearSpan(snap)
        if (sp != null && HomeLayers.year >= sp.last) HomeLayers.year = sp.first
        while (HomeLayers.playing && sp != null && HomeLayers.year < sp.last) { delay(1300); if (HomeLayers.playing) HomeLayers.year = HomeLayers.year + 1 }
        HomeLayers.playing = false
    }
    // live team (admins): fetched now and every 3 minutes while the map shows
    val online by OfflineMaps.online.collectAsState()
    if (HomeLayers.team && snap.admin) LaunchedEffect(Unit) {
        var first = true
        while (isActive && HomeLayers.team) {
            if (!OfflineMaps.online.value) HomeLayers.tmErr = t("No signal: the team’s places need a connection.", "Hakuna mtandao: mahali pa timu panahitaji mtandao.")
            else {
                val r = m.api.adminGet("teammap")
                if (r.ok) { HomeLayers.tm = TeamMapData.from(r.json); HomeLayers.tmErr = null } else HomeLayers.tmErr = reasonText(r.reason)
                val ppl = HomeLayers.tm?.people.orEmpty()
                if (first && ppl.isNotEmpty()) runCatching {
                    if (ppl.size == 1) mv.controller.animateTo(GeoPoint(ppl[0].ll.lat, ppl[0].ll.lng), 13.0, if (Motion.on) 700L else 0L)
                    else { mv.zoomToBoundingBox(org.osmdroid.util.BoundingBox.fromGeoPointsSafe(ppl.map { GeoPoint(it.ll.lat, it.ll.lng) }).increaseByScale(1.6f), Motion.on); if (mv.zoomLevelDouble > 13.0) mv.controller.setZoom(13.0) }
                }
                first = false
            }
            delay(180_000)
        }
    }
    val tm = if (HomeLayers.team && snap.admin) HomeLayers.tm else null
    // teammates close together sit side by side, so their names and times never run into each other; laid out
    // again when the zoom changes
    var teamZoom by remember(mv) { mutableIntStateOf((mv.zoomLevelDouble * 2).roundToInt()) }
    if (tm != null) DisposableEffect(mv) {
        val l = object : org.osmdroid.events.MapListener {
            override fun onScroll(e: org.osmdroid.events.ScrollEvent?) = false
            override fun onZoom(e: org.osmdroid.events.ZoomEvent?): Boolean { mv.post { teamZoom = (mv.zoomLevelDouble * 2).roundToInt() }; return false }
        }
        mv.addMapListener(l); onDispose { mv.removeMapListener(l) }
    }
    val teamKey = Triple(tm, System.currentTimeMillis() / 60000, teamZoom to (mv.width > 0))
    if (st.teamKey != teamKey) {
        st.teamKey = teamKey
        mv.overlays.removeAll(st.team)
        val ppl = tm?.people.orEmpty()
        val icons = ppl.mapIndexed { i, p -> mateIcon(ctx, p.name, MATE[i % MATE.size], ago(p.at), minutesSince(p.at) > 60) }
        val shift = if (mv.width > 0 && ppl.size > 1) {
            val pts = ppl.map { mv.projection.toPixels(GeoPoint(it.ll.lat, it.ll.lng), null) }
            mateSpread(pts.map { it.x.toFloat() }, pts.map { it.y.toFloat() }, icons.map { it.bitmap.width.toFloat() }, 66 * d, 4 * d)
        } else List(ppl.size) { 0f }
        st.team = ppl.mapIndexed { i, p ->
            Marker(mv).apply {
                position = GeoPoint(p.ll.lat, p.ll.lng); icon = icons[i]
                setAnchor(0.5f - shift[i] / icons[i].bitmap.width, 23f / 66f); setInfoWindow(null); title = p.name
                setOnMarkerClickListener { _, _ -> HomeLayers.pick = if (pickNow == "t:" + p.name) null else "t:" + p.name; true }
            }
        }
        mv.overlays.addAll(st.team); mv.invalidate()
    }
    // pins drop in the first time the map shows them
    LaunchedEffect(mv) {
        if (HomeLayers.dropped) return@LaunchedEffect
        var tries = 0
        while (tries++ < 40 && mv.overlays.none { it is Marker && it !in st.route && it !in st.team && it !in st.years.values }) delay(50)
        if (HomeLayers.dropped) return@LaunchedEffect
        HomeLayers.dropped = true
        dropIn(mv, mv.overlays.filterIsInstance<Marker>().filter { it !in st.team && it !in st.years.values && it !== st.me }.take(400))
    }
    // follow me: centred on you, turned to your heading, until you drag the map
    val follow = HomeLayers.follow
    if (follow) DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val l = object : SensorEventListener {
            val rm = FloatArray(9); val o = FloatArray(3)
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rm, e.values); SensorManager.getOrientation(rm, o)
                val h = ((Math.toDegrees(o[0].toDouble()) + 360) % 360).toFloat(); val c = HomeLayers.compass
                if (c == null || abs(shortest(c, h) - c) > 4f) HomeLayers.compass = h
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(l); HomeLayers.compass = null }
    }
    val heading = if (follow) fix?.bearing ?: HomeLayers.testHeading ?: HomeLayers.compass else null
    if (follow && here != null) {
        val me = st.me ?: Marker(mv).apply { setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); setInfoWindow(null); isFlat = true; setOnMarkerClickListener { _, _ -> true } }.also { st.me = it; mv.overlays.add(it) }
        if (st.meCone != (heading != null)) { st.meCone = heading != null; me.icon = meIcon(ctx, heading != null) }
        me.position = GeoPoint(here.lat, here.lng); me.rotation = -(heading ?: 0f)
        val target = if (heading != null && !HomeLayers.north) -heading else 0f
        LaunchedEffect(here, target) {
            val gp = GeoPoint(here.lat, here.lng); val turn = shortest(mv.mapOrientation, target)
            val z = if (!st.fixed) max(mv.zoomLevelDouble, 16.0) else mv.zoomLevelDouble
            st.fixed = true
            if (Motion.on) mv.controller.animateTo(gp, z, 650L, turn) else { mv.mapOrientation = turn; mv.controller.setZoom(z); mv.controller.setCenter(gp) }
            HomeLayers.rot = turn
        }
    } else {
        st.me?.let { mv.overlays.remove(it); st.me = null; st.meCone = null; mv.invalidate() }
        st.fixed = false
    }
    if (!follow) LaunchedEffect(Unit) { HomeLayers.north = false }
    // the follow-me button and the compass are in the Map tab's column of buttons (MapHome.kt, MapSide)

    // the cards over the bottom of the map, above the map's credit; a layer that is on keeps its card even on the calm map (map3)
    val clean = false
    Column(Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = bottom + 40.dp).widthIn(max = 520.dp).fillMaxWidth()
        .onSizeChanged { if (HomeLayers.cardsH != it.height) HomeLayers.cardsH = it.height }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val pick = HomeLayers.pick
        val pStop = pick?.takeIf { it.startsWith("s:") }?.let { k -> stops.indexOfFirst { it.id == k.drop(2) }.takeIf { it >= 0 } }
        if (pStop != null) StopCard(m, snap, stops, pStop, here, today, visit, open)
        val pMate = pick?.takeIf { it.startsWith("t:") }?.let { k -> tm?.people?.firstOrNull { it.name == k.drop(2) } }
        if (pMate != null) MateCard(pMate)
        if (follow && !clean) NextCard(m, snap, stops, nextIdx, here)
        if (heatKind.isNotEmpty() && !clean) HeatLegend(snap, heatKind, if (yearsOn) HomeLayers.year else null)
        if (yearsOn && span != null && !clean) YearsCard(snap, span)
        if (HomeLayers.team && snap.admin && (!online || HomeLayers.tmErr != null) && !clean) Note(HomeLayers.tmErr ?: t("No signal: the team’s places need a connection.", "Hakuna mtandao: mahali pa timu panahitaji mtandao."))
    }
}

/** How far (px, sideways) to move each teammate's marker so none of them overlap: markers whose boxes ([ws] wide,
 *  [h] tall, centred on [xs], [ys]) touch are put side by side, [gap] apart, centred where they were. */
fun mateSpread(xs: List<Float>, ys: List<Float>, ws: List<Float>, h: Float, gap: Float): List<Float> {
    val n = xs.size
    val group = IntArray(n) { it }
    fun root(i: Int): Int { var k = i; while (group[k] != k) k = group[k]; return k }
    for (i in 0 until n) for (j in i + 1 until n)
        if (abs(xs[i] - xs[j]) < (ws[i] + ws[j]) / 2 + gap && abs(ys[i] - ys[j]) < h) group[root(j)] = root(i)
    val out = MutableList(n) { 0f }
    (0 until n).groupBy { root(it) }.values.filter { it.size > 1 }.forEach { g ->
        val order = g.sortedWith(compareBy({ xs[it] }, { it }))
        val total = order.sumOf { ws[it].toDouble() }.toFloat() + gap * (order.size - 1)
        var x = order.map { xs[it] }.average().toFloat() - total / 2
        order.forEach { k -> out[k] = x + ws[k] / 2 - xs[k]; x += ws[k] + gap }
    }
    return out
}

private fun minutesSince(iso: String) = runCatching { java.time.Duration.between(Instant.parse(iso), Instant.now()).toMinutes() }.getOrDefault(0L)

@Composable
internal fun Needle(modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width; val h = size.height
        drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(w / 2, h * 0.08f); lineTo(w * 0.66f, h / 2); lineTo(w * 0.34f, h / 2); close() }, Color(0xFFD23C32))
        drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(w / 2, h * 0.92f); lineTo(w * 0.34f, h / 2); lineTo(w * 0.66f, h / 2); close() }, Color(0x8004263F))
    }
}

@Composable
private fun MapCard(tag: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(modifier.fillMaxWidth().shadow(10.dp, shape, spotColor = Color(0x33144678)).clip(shape).background(C.card).border(1.dp, C.line, shape).testTag(tag)) { content() }
}

@Composable
private fun CloseX(label: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(C.tint).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(Ic.get("x"), null, tint = C.ink, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun Caps(text: String, color: Color = C.muted) = Text(text.uppercase(), color = color, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.7.sp, fontWeight = FontWeight.ExtraBold))

@Composable
private fun NextCard(m: AppModel, snap: Snapshot, stops: List<Project>, nextIdx: Int, here: LatLng?) {
    val near = if (stops.isEmpty() && here != null) snap.projects.filter { it.gps != null }.minByOrNull { Geo.metres(here, it.gps!!) } else null
    val p = if (nextIdx >= 0) stops[nextIdx] else near
    val label = when { nextIdx >= 0 -> t("Next stop · ${nextIdx + 1}/${stops.size}", "Kituo ${nextIdx + 1}/${stops.size}"); stops.isNotEmpty() -> t("Today’s route is done", "Njia ya leo imekamilika"); near != null -> t("Closest site", "Eneo la karibu zaidi"); else -> t("Following you", "Inakufuata") }
    MapCard("ml_next") {
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF7A4FD0)), contentAlignment = Alignment.Center) { Icon(Ic.get("nav"), null, tint = Color.White, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Caps(label)
                Text(p?.title ?: if (stops.isNotEmpty()) t("All visits sent ✓", "Ziara zote zimetumwa ✓") else t("Finding where you are…", "Inatafuta mahali ulipo…"), style = MaterialTheme.typography.titleSmall, color = C.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (p != null && here != null) Text("≈ " + Geo.dist(Geo.metres(here, p.gps!!), m.prefs.miles), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = C.ink, maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 8.dp).testTag("ml_next_km"))
            CloseX(t("Stop following me", "Acha kunifuata")) { HomeLayers.follow = false }
        }
    }
}

@Composable
private fun StopCard(m: AppModel, snap: Snapshot, stops: List<Project>, i: Int, here: LatLng?, today: LocalDate, visit: (String) -> Unit, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val p = stops[i]; val done = visitedToday(snap, p.id, today)
    MapCard("ml_stop") {
        Column(Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Caps(t("Today’s route · stop ${i + 1} of ${stops.size}", "Njia ya leo · kituo ${i + 1} kati ya ${stops.size}"), Color(0xFF7A4FD0))
                    Text(p.title, style = MaterialTheme.typography.titleMedium, color = C.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(p.sub + (here?.let { " · " + Geo.dist(Geo.metres(it, p.gps!!), m.prefs.miles) + t(" from you", " kutoka ulipo") } ?: ""), style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 2)
                }
                CloseX(t("Close", "Funga")) { HomeLayers.pick = null }
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (done) Icon(Ic.get("check"), null, tint = Green, modifier = Modifier.size(16.dp))
                Text(if (done) t("Visited today", "Imetembelewa leo") else t("Not visited yet", "Bado haijatembelewa"), style = MaterialTheme.typography.labelMedium, color = if (done) Green else C.muted, modifier = Modifier.padding(start = if (done) 6.dp else 0.dp))
            }
            Row(Modifier.padding(top = 10.dp, end = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(if (done) t("Open", "Fungua") else t("Report a visit", "Ripoti ziara"), { HomeLayers.pick = null; if (done) open(p.id) else visit(p.id) }, Modifier.weight(1f).testTag("ml_stop_go"), small = true)
                Btn(t("Directions", "Maelekezo"), {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=%.6f,%.6f&travelmode=driving".format(java.util.Locale.US, p.gps!!.lat, p.gps.lng)))) }
                }, Modifier.weight(1f), small = true, light = true, icon = "nav")
            }
        }
    }
}

@Composable
private fun MateCard(p: Sharer) {
    val ctx = LocalContext.current
    MapCard("ml_mate") {
        Column(Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF0A78C2)), contentAlignment = Alignment.Center) { Text(initialsOf(p.name), color = Color.White, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold)) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleSmall, color = C.ink)
                    Text(t("Last seen ", "Alionekana ") + ago(p.at) + (if (p.acc > 0) " · " + Geo.acc(p.acc) else ""), style = MaterialTheme.typography.bodySmall, color = C.muted)
                }
                CloseX(t("Close", "Funga")) { HomeLayers.pick = null }
            }
            if (p.phone.isNotBlank()) Row(Modifier.padding(top = 10.dp, end = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(t("Call", "Piga simu"), { runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + p.phone.replace(" ", "")))) } }, Modifier.weight(1f).testTag("ml_call"), small = true, icon = "phone")
                Btn("WhatsApp", { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + waNumber(p.phone, p.home)))) } }, Modifier.weight(1f).testTag("ml_wa"), small = true, light = true, icon = "b:whatsapp")
            } else Text(t("No phone number saved for them.", "Hakuna namba ya simu iliyohifadhiwa."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun HeatLegend(snap: Snapshot, kind: String, year: Int?) {
    val pts = heatPoints(snap, kind, year); val tot = pts.sumOf { it.second }
    val line = if (kind == "repairs") (if (pts.size == 1) t("1 open repair", "Matengenezo 1 yaliyo wazi") else t("${pts.size} open repairs", "Matengenezo ${pts.size} yaliyo wazi")) + " · " + t("not working counts double", "isiyofanya kazi inahesabiwa mara mbili")
        else if (pts.isNotEmpty()) t("%,d people served at %d sites".format(tot.roundToInt(), pts.size), "Watu %,d wanahudumiwa katika maeneo %d".format(tot.roundToInt(), pts.size))
        else t("No people counts yet. They come from the board and site visits.", "Bado hakuna idadi ya watu. Zinatoka kwenye bodi na ziara.")
    val cs = (HEAT[kind] ?: emptyList()).drop(1).map { Color(it) }
    MapCard("ml_legend") {
        Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (kind == "repairs") t("Open repairs", "Matengenezo yaliyo wazi") else t("People served", "Watu wanaohudumiwa"), style = MaterialTheme.typography.titleSmall, color = C.ink)
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t("Fewer", "Chache"), style = MaterialTheme.typography.labelSmall, color = C.muted)
                    Box(Modifier.padding(horizontal = 8.dp).weight(1f).height(10.dp).clip(RoundedCornerShape(99.dp)).background(Brush.horizontalGradient(cs)))
                    Text(t("More", "Zaidi"), style = MaterialTheme.typography.labelSmall, color = C.muted)
                }
                Text(line, style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.testTag("ml_legend_line"))
            }
            Spacer(Modifier.width(6.dp))
            CloseX(t("Hide the heat map", "Ficha ramani ya joto")) { HomeLayers.heat = "" }
        }
    }
}

@Composable
private fun YearsCard(snap: Snapshot, span: IntRange) {
    val y = HomeLayers.year.coerceIn(span.first, span.last)
    val ps = doneBy(snap, y); val ppl = ps.sumOf { it.people ?: 0 }
    val h = rememberHaptics()
    MapCard("ml_years") {
        Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF0A78C2)).clickable(role = Role.Button) { h.tap(); HomeLayers.playing = !HomeLayers.playing }
                    .semantics { contentDescription = if (HomeLayers.playing) t("Pause", "Simamisha") else t("Play through the years", "Cheza kupitia miaka") }.testTag("ml_play"), contentAlignment = Alignment.Center) {
                    if (HomeLayers.playing) Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { repeat(2) { Box(Modifier.size(4.dp, 16.dp).clip(RoundedCornerShape(2.dp)).background(Color.White)) } }
                    else androidx.compose.foundation.Canvas(Modifier.size(18.dp).offset(x = 2.dp)) { drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(0f, 0f); lineTo(size.width, size.height / 2); lineTo(0f, size.height); close() }, Color.White) }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t("By $y", "Hadi $y"), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold), color = C.ink, modifier = Modifier.testTag("ml_year"))
                    Text((if (ps.size == 1) t("1 project completed", "Mradi 1 umekamilika") else t("${ps.size} projects completed", "Miradi ${ps.size} imekamilika")) + (if (ppl > 0) " · " + t("%,d people".format(ppl), "watu %,d".format(ppl)) else ""),
                        style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.testTag("ml_year_n"))
                }
                CloseX(t("Close the time slider", "Funga kitelezi cha miaka")) { HomeLayers.playing = false; HomeLayers.years = false }
            }
            Slider(y.toFloat(), { HomeLayers.playing = false; HomeLayers.year = it.roundToInt() }, valueRange = span.first.toFloat()..span.last.toFloat(), steps = max(0, span.last - span.first - 1),
                colors = SliderDefaults.colors(thumbColor = Color(0xFF0A78C2), activeTrackColor = Color(0xFF0A78C2)),
                modifier = Modifier.padding(end = 4.dp).heightIn(min = 44.dp).testTag("ml_slider").semantics { contentDescription = t("Year", "Mwaka") })
            // the first and last year under the ends of the track, clear of the thumb
            Row(Modifier.padding(start = 2.dp, end = 4.dp, top = 2.dp, bottom = 4.dp).testTag("ml_year_ends")) {
                Text("${span.first}", style = MaterialTheme.typography.labelSmall, color = C.muted); Spacer(Modifier.weight(1f)); Text("${span.last}", style = MaterialTheme.typography.labelSmall, color = C.muted)
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    MapCard("ml_note") { Text(text, style = MaterialTheme.typography.bodySmall, color = C.ink, modifier = Modifier.padding(14.dp)) }
}

// --- the switches in the map-style sheet -------------------------------------------------------------------

@Composable
private fun LayerSwitches(m: AppModel, snap: Snapshot, fix: Fix?) {
    val ctx = LocalContext.current
    val stops = dayStops(ctx, snap, fix?.ll)
    val done = stops.count { visitedToday(snap, it.id) }
    Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(1.dp).background(C.line))
    SheetHead(t("On the map", "Kwenye ramani"))
    SwitchRow("cal", t("Today’s route", "Njia ya leo"),
        if (stops.isEmpty()) t("No visits for you today", "Huna ziara leo") else t("${stops.size} ${if (stops.size == 1) "visit" else "visits"} for you today · $done done", "Ziara ${stops.size} zako leo · $done zimekamilika"),
        HomeLayers.route, "ml_sw_route") { HomeLayers.saveRoute(ctx, !HomeLayers.route) }
    if (!snap.admin) return
    SheetHead(t("For the office", "Kwa ofisi"))
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile("flame", C.blue, C.tint, 38.dp); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t("Heat map", "Ramani ya joto"), style = MaterialTheme.typography.titleSmall, color = C.ink)
            Text(t("Shade the areas by…", "Paka rangi maeneo kwa…"), style = MaterialTheme.typography.bodySmall, color = C.muted)
        }
    }
    val h = rememberHaptics()
    Row(Modifier.padding(start = 64.dp, end = 14.dp, bottom = 6.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).border(1.dp, C.line, RoundedCornerShape(18.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("" to t("Off", "Zima"), "repairs" to t("Repairs", "Matengenezo"), "people" to t("People served", "Watu")).forEach { (k, label) ->
            val on = HomeLayers.heat == k
            Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp)).background(if (on) C.blue else Color.Transparent)
                .clickable(role = Role.RadioButton) { h.tap(); HomeLayers.heat = k }.semantics { stateDescription = if (on) "on" else "off" }.testTag("ml_heat_" + k.ifEmpty { "off" }).padding(horizontal = 4.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center) {
                Text(label, color = if (on) Color.White else C.ink, style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, lineHeight = 15.sp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
    SwitchRow("clock", t("Projects by year", "Miradi kwa mwaka"), t("A time slider with a play button, for presentations", "Kitelezi cha miaka chenye kitufe cha kucheza, kwa mawasilisho"), HomeLayers.years, "ml_sw_years") {
        val on = !HomeLayers.years
        if (on && yearSpan(snap) == null) { m.showBanner(t("No completed projects with a year to show.", "Hakuna miradi iliyokamilika yenye mwaka wa kuonyesha.")); return@SwitchRow }
        HomeLayers.playing = false; HomeLayers.years = on; yearSpan(snap)?.let { HomeLayers.year = it.last }
    }
    SwitchRow("people", t("Team live", "Timu sasa hivi"), t("People sharing their location · updates every 3 min", "Wanaoshiriki mahali · inasasishwa kila dakika 3"), HomeLayers.team, "ml_sw_team") {
        HomeLayers.team = !HomeLayers.team; if (!HomeLayers.team) { HomeLayers.tm = null; HomeLayers.tmErr = null; if (HomeLayers.pick?.startsWith("t:") == true) HomeLayers.pick = null }
    }
}

@Composable
private fun SheetHead(text: String) = Box(Modifier.padding(start = 6.dp, top = 14.dp, bottom = 4.dp)) { Caps(text) }

@Composable
private fun SwitchRow(icon: String, title: String, sub: String, on: Boolean, tag: String, onToggle: () -> Unit) {
    val h = rememberHaptics()
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(16.dp)).toggleable(on, role = Role.Switch) { h.tap(); onToggle() }
            .heightIn(min = 58.dp).padding(horizontal = 14.dp, vertical = 6.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, C.blue, C.tint, 38.dp); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = C.ink)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = C.muted)
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(50.dp, 30.dp).clip(RoundedCornerShape(99.dp)).background(if (on) Green else Color(0xFFC9D1D8))) {
            Box(Modifier.offset(x = if (on) 23.dp else 3.dp, y = 3.dp).size(24.dp).shadow(1.dp, CircleShape).clip(CircleShape).background(Color.White))
        }
    }
}

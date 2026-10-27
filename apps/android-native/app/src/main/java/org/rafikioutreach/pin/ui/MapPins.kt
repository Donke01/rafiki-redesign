package org.rafikioutreach.pin.ui

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.view.MotionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.rafikioutreach.pin.data.LatLng
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/*
 * The Map tab's pins that talk (map3): each project pin has its kind's icon inside (the icons the map search uses), a ring
 * in the colour of how it is, and a tiny blue dot when a visit or water test is due. Groups are a donut split by how the
 * projects in them are, with the count in the middle. From zoom 13 short names sit under the pins, a name hidden rather
 * than drawn over another. A tapped pin lifts and grows while the others dim; pins that a filter takes away shrink and
 * fade out, and the ones it brings grow in; pins on the same spot fan out. Every picture is drawn once per look and kept
 * (PinArt); nothing is made new while the map is drawn. Animations run on the screen's clock and are off with reduced motion.
 */

/** A project's pin: where it is, how it is ([PinCond]), its kind's icon (typeLook: drop, school, cross, house, leaf,
 *  brick), whether a visit or water test is due, and its short name. */
data class HomePin(val id: String, val ll: LatLng, val cond: String, val type: String = "drop", val due: Boolean = false, val name: String = "")

/** Groups pins that would sit within [radiusPx] of each other at [zoom] (256-pixel tiles, as osmdroid draws them).
 *  Past street level only pins on (nearly) the same spot group, so they can fan out. The worst pins seed the groups,
 *  so a broken pump is never hidden. */
fun clusterPins(pins: List<HomePin>, zoom: Double, radiusPx: Double): List<List<HomePin>> {
    if (pins.size < 2) return pins.map { listOf(it) }
    val r = if (zoom >= 15.0) minOf(radiusPx, 4.0) else radiusPx
    val scale = 256.0 * 2.0.pow(zoom)
    val xy = pins.map { p ->
        val la = Math.toRadians(p.ll.lat.coerceIn(-85.0, 85.0))
        ((p.ll.lng + 180) / 360 * scale) to ((1 - ln(tan(la) + 1 / cos(la)) / PI) / 2 * scale)
    }
    val order = pins.indices.sortedWith(compareByDescending<Int> { condRank(pins[it].cond) }.thenBy { pins[it].id })
    val used = BooleanArray(pins.size)
    val out = mutableListOf<List<HomePin>>()
    val r2 = r * r
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

/** How many of a group are working, not visited, needing repair and not working (indexed by [condRank]). */
fun condParts(g: List<HomePin>): IntArray = IntArray(4).also { a -> g.forEach { a[condRank(it.cond)]++ } }

/** A name short enough to sit under a pin: whole words only, "…" when some are left out. */
fun shortName(title: String, max: Int = 20): String {
    val s = title.trim()
    if (s.length <= max) return s
    val b = StringBuilder()
    for (w in s.split(Regex("\\s+"))) {
        if (b.isNotEmpty() && b.length + 1 + w.length > max - 1) break
        if (b.isNotEmpty()) b.append(' ')
        b.append(w)
    }
    return b.toString().trimEnd(',', '·', '-', '–', ' ') + "…"
}

/** Lays out names under pins with nothing drawn over anything else: the [n] pins (centres [xs], [ys], radius [pinR]),
 *  names [ws] wide and [h] tall, [below] px under each centre, in a [w] × [hgt] screen, in order of importance.
 *  [show] says which names fit; their boxes (left, top, right, bottom) go into [boxes]. No allocation: it runs every frame. */
fun placeLabels(n: Int, xs: FloatArray, ys: FloatArray, ws: FloatArray, h: Float, below: Float, pinR: Float, w: Float, hgt: Float, show: BooleanArray, boxes: FloatArray): Int {
    var placed = 0
    for (i in 0 until n) {
        show[i] = false
        if (ws[i] < 0) continue // a mark to keep clear of, with no name of its own
        val l = xs[i] - ws[i] / 2; val t = ys[i] + below; val r = xs[i] + ws[i] / 2; val b = t + h
        if (l < 0 || t < 0 || r > w || b > hgt) continue
        var ok = true
        // other pins' icons
        for (j in 0 until n) if (j != i) {
            if (xs[j] + pinR > l && xs[j] - pinR < r && ys[j] + pinR > t && ys[j] - pinR < b) { ok = false; break }
        }
        // names already placed
        if (ok) for (k in 0 until i) if (show[k]) {
            val o = k * 4
            if (boxes[o] < r && l < boxes[o + 2] && boxes[o + 1] < b && t < boxes[o + 3]) { ok = false; break }
        }
        if (!ok) continue
        show[i] = true; placed++
        val o = i * 4; boxes[o] = l; boxes[o + 1] = t; boxes[o + 2] = r; boxes[o + 3] = b
    }
    return placed
}

/** The pictures of pins and groups, drawn once per look and kept. */
class PinArt(private val res: Resources, private val d: Float) {
    private val cache = HashMap<String, BitmapDrawable>()
    private val icons = HashMap<String, List<Path>>()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    /** The radius (px) a pin takes on screen, to its outer white edge. */
    fun radius(big: Boolean) = ((if (big) 11.5f else 8.5f) + (if (big) 3.5f else 3f) + 1.5f) * d
    /** A group's radius (px). */
    fun groupRadius(count: Int) = ((if (count < 10) 15f else if (count < 100) 17f else 20f) + 1.5f) * d
    /** How many pictures are kept (tests: the cache is by type, condition and due, not one per pin). */
    val size get() = cache.size

    private fun paths(type: String) = icons.getOrPut(type) { Ic.paths(type).mapNotNull { runCatching { androidx.core.graphics.PathParser.createPathFromPathData(it) }.getOrNull() } }

    /** A project's pin: the kind's icon on white, a ring in the colour of how it is, a blue dot when something is due. */
    fun pin(type: String, cond: String, due: Boolean, big: Boolean): BitmapDrawable = cache.getOrPut("p$type|$cond|$due|$big") {
        val r = (if (big) 11.5f else 8.5f) * d; val ring = (if (big) 3.5f else 3f) * d; val edge = 1.5f * d; val halo = (if (big) 4.5f else 1.5f) * d
        val n = ceil(2 * (r + ring + edge + halo)).toInt() + 2; val m = n / 2f
        val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        p.style = Paint.Style.FILL; p.shader = null
        p.color = if (big) 0x660A78C2 else 0x47000000; c.drawCircle(m, m, r + ring + edge + halo, p)
        p.color = android.graphics.Color.WHITE; c.drawCircle(m, m, r + ring + edge, p)
        p.color = condArgb(cond); c.drawCircle(m, m, r + ring, p)
        p.color = android.graphics.Color.WHITE; c.drawCircle(m, m, r, p)
        // the icon, 24 units drawn in about 1.5 × the inner radius
        val s = r * 1.5f
        c.save(); c.translate(m - s / 2, m - s / 2); c.scale(s / 24f, s / 24f)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.6f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND; p.color = 0xFF04263F.toInt()
        paths(type).forEach { c.drawPath(it, p) }
        c.restore(); p.style = Paint.Style.FILL
        if (due) {
            val o = (r + ring) * 0.72f
            p.color = android.graphics.Color.WHITE; c.drawCircle(m + o, m - o, 4.4f * d, p)
            p.color = DUE_ARGB; c.drawCircle(m + o, m - o, 3.1f * d, p)
        }
        BitmapDrawable(res, b)
    }

    /** A group: a donut split by how its projects are (not working at the top, then needs repair, not visited, working),
     *  and the count in the middle. Shares are kept to 1/36ths, so similar groups share a picture. */
    fun donut(parts: IntArray, count: Int): BitmapDrawable {
        val label = if (count > 99) "99+" else count.toString()
        val total = parts.sum().coerceAtLeast(1)
        val q = IntArray(4) { (parts[it] * 36.0 / total).roundToInt().let { v -> if (parts[it] > 0) v.coerceAtLeast(1) else 0 } }
        return cache.getOrPut("g$label|${q[0]},${q[1]},${q[2]},${q[3]}") {
            val big = (if (count < 10) 15f else if (count < 100) 17f else 20f) * d; val w = 4.5f * d; val edge = 1.5f * d; val halo = 1.5f * d
            val n = ceil(2 * (big + edge + halo)).toInt() + 2; val m = n / 2f
            val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888); val c = Canvas(b)
            p.style = Paint.Style.FILL; p.shader = null
            p.color = 0x47000000; c.drawCircle(m, m, big + edge + halo, p)
            p.color = android.graphics.Color.WHITE; c.drawCircle(m, m, big + edge, p)
            val qs = q.sum().coerceAtLeast(1); val many = q.count { it > 0 } > 1
            val oval = RectF(m - big + w / 2, m - big + w / 2, m + big - w / 2, m + big - w / 2)
            p.style = Paint.Style.STROKE; p.strokeWidth = w; p.strokeCap = Paint.Cap.BUTT
            var at = -90f
            for (k in 3 downTo 0) {
                if (q[k] == 0) continue
                val sweep = 360f * q[k] / qs
                p.color = condArgb(RANKED[k]); c.drawArc(oval, at + if (many) 1.5f else 0f, sweep - if (many) 3f else 0f, false, p)
                at += sweep
            }
            p.style = Paint.Style.FILL
            p.color = android.graphics.Color.WHITE; c.drawCircle(m, m, big - w, p)
            p.color = 0xFF04263F.toInt(); p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.DEFAULT_BOLD; p.textSize = (if (count > 99) 11f else 13f) * d
            c.drawText(label, m, m - (p.descent() + p.ascent()) / 2, p)
            BitmapDrawable(res, b)
        }
    }

    companion object {
        /** The colours by [condRank]: working, not visited, needs repair, not working. */
        val RANKED = arrayOf(PinCond.OK, PinCond.OLD, PinCond.REPAIR, PinCond.BAD)
        const val DUE_ARGB = 0xFF0A78C2.toInt()
    }
}

/** A pin or group on the map that can grow, lift and fade (drawn scaled about its point, no new objects per frame). */
class PinMarker(mv: MapView, val pin: HomePin?, val group: List<HomePin>?, val big: Boolean) : Marker(mv) {
    var scale = 1f
    var lift = 0f
    var onLong: ((PinMarker) -> Boolean)? = null
    override fun draw(c: Canvas, pj: Projection) {
        if (scale == 1f && lift == 0f) { super.draw(c, pj); return }
        if (scale < 0.02f) return
        pj.toPixels(position, at)
        c.save(); c.translate(0f, -lift); c.scale(scale, scale, at.x.toFloat(), at.y.toFloat()); super.draw(c, pj); c.restore()
    }
    override fun onLongPress(e: MotionEvent, mv: MapView): Boolean = onLong?.let { cb -> hitTest(e, mv) && cb(this) } ?: false
    companion object { private val at = android.graphics.Point() }
}

/** Short names under the pins from zoom 13, never over another name or pin; the drawn ones are in [shown]. */
class PinLabels(private val d: Float) : Overlay() {
    private var ids: Array<String> = emptyArray(); private var texts: Array<String> = emptyArray()
    private var geo: Array<GeoPoint> = emptyArray(); private var obstacle = BooleanArray(0)
    private var xs = FloatArray(0); private var ys = FloatArray(0); private var ws = FloatArray(0)
    private var show = BooleanArray(0); private var boxes = FloatArray(0)
    private var pinR = 0f; private var below = 0f
    var alpha = 1f
    /** The names drawn last time (tests). */
    val shown = ArrayList<String>()
    /** Their boxes, px (tests). */
    fun boxes(): List<RectF> = (0 until ids.size).filter { show.getOrElse(it) { false } && !obstacle[it] }.map { RectF(boxes[it * 4], boxes[it * 4 + 1], boxes[it * 4 + 2], boxes[it * 4 + 3]) }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * d; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); color = 0xFF04263F.toInt(); textAlign = Paint.Align.CENTER }
    private val halo = Paint(txt).apply { style = Paint.Style.STROKE; strokeWidth = 3.2f * d; strokeJoin = Paint.Join.ROUND; color = 0xF2FFFFFF.toInt() }
    private val lineH = txt.fontMetrics.let { it.descent - it.ascent }
    private val pt = android.graphics.Point()

    /** [labels]: (id, where, name) in order of importance; [others]: where the other marks are (groups, the chosen pin), to keep clear of. */
    fun set(labels: List<Triple<String, GeoPoint, String>>, others: List<GeoPoint>, pinRadius: Float, groupRadius: Float) {
        val n = labels.size + others.size
        ids = Array(n) { if (it < labels.size) labels[it].first else "" }
        texts = Array(n) { if (it < labels.size) labels[it].third else "" }
        geo = Array(n) { if (it < labels.size) labels[it].second else others[it - labels.size] }
        obstacle = BooleanArray(n) { it >= labels.size }
        xs = FloatArray(n); ys = FloatArray(n); ws = FloatArray(n) { if (it < labels.size) txt.measureText(texts[it]) + 6 * d else -1f }
        show = BooleanArray(n); boxes = FloatArray(n * 4)
        pinR = maxOf(pinRadius, groupRadius * 0.8f); below = pinRadius + 1.5f * d
    }

    override fun draw(c: Canvas, pj: Projection) {
        shown.clear()
        val n = ids.size
        if (n == 0 || pj.zoomLevel < 13.0) { show.fill(false); return }
        for (i in 0 until n) { pj.toPixels(geo[i], pt); xs[i] = pt.x.toFloat(); ys[i] = pt.y.toFloat() }
        placeLabels(n, xs, ys, ws, lineH, below, pinR, c.width.toFloat(), c.height.toFloat(), show, boxes)
        txt.alpha = (alpha * 255).toInt(); halo.alpha = (alpha * 242).toInt()
        for (i in 0 until n) if (show[i] && !obstacle[i]) {
            val y = boxes[i * 4 + 1] - txt.fontMetrics.ascent
            c.drawText(texts[i], xs[i], y, halo); c.drawText(texts[i], xs[i], y, txt)
            shown += ids[i]
        }
    }
}

/** Under the pins: the legs of pins fanned out from one spot, and the you-dot's single pulse. */
private class PinsUnder(private val d: Float) : Overlay(), UnderPins {
    var hub: GeoPoint? = null
    var legs: List<Marker> = emptyList()
    var you: GeoPoint? = null
    var youF = -1f
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val a = android.graphics.Point(); private val b = android.graphics.Point()
    override fun draw(c: Canvas, pj: Projection) {
        hub?.let { h ->
            pj.toPixels(h, a)
            legs.forEach { m ->
                pj.toPixels(m.position, b)
                line.color = 0xE6FFFFFF.toInt(); line.strokeWidth = 3.5f * d; c.drawLine(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), line)
                line.color = 0xFF04263F.toInt(); line.strokeWidth = 1.5f * d; c.drawLine(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), line)
            }
        }
        val y = you
        if (y != null && youF in 0f..1f) {
            pj.toPixels(y, a)
            line.color = 0xFF098BE1.toInt(); line.alpha = ((1 - youF) * 220).toInt(); line.strokeWidth = 3f * d
            c.drawCircle(a.x.toFloat(), a.y.toFloat(), (10 + 26 * youF) * d, line); line.alpha = 255
        }
    }
}

/** The pins and groups on the map. Rebuilt only when the pins, the chosen one or the zoom (in half steps) change. */
class HomePins(private val mv: MapView, private val dens: Float) {
    var onPin: (String) -> Unit = {}
    /** A tap on a group (it zooms in): the revealed tiles step aside (feat-mea). */
    var onExplore: () -> Unit = {}
    /** A long press on a pin: its id and where it is on the map (px). */
    var onLongPin: (String, Float, Float) -> Unit = { _, _, _ -> }
    var animate = true
    /** The screen's clock for the animations (MapHome's coroutine scope). */
    var scope: CoroutineScope? = null
    val art = PinArt(mv.resources, dens)
    val labels = PinLabels(dens)
    private val under = PinsUnder(dens)
    private var pins: List<HomePin> = emptyList()
    private var sel: String? = null
    private val shown = mutableListOf<PinMarker>()
    private val leaving = mutableListOf<PinMarker>()
    private var key: Any? = null
    private var lastZ = -1.0
    private var lastIds: Set<String> = emptySet()
    private var tween: Job? = null
    // pins on one spot, fanned out
    private var spider: List<HomePin> = emptyList()
    private var spiderAt: GeoPoint? = null
    private var spiderZ = -1.0
    private var fan = 1f
    private var fanJob: Job? = null
    private var youJob: Job? = null
    private val listener = object : MapListener {
        override fun onScroll(e: ScrollEvent?) = false
        override fun onZoom(e: ZoomEvent?): Boolean { mv.post { refresh() }; return false }
    }
    init { mv.addMapListener(listener) }

    fun set(p: List<HomePin>, selected: String?) { pins = p; sel = selected; refresh() }
    fun detach() {
        tween?.cancel(); fanJob?.cancel(); youJob?.cancel()
        mv.removeMapListener(listener); mv.overlays.removeAll(shown + leaving + listOf(labels, under)); shown.clear(); leaving.clear(); mv.invalidate()
    }
    /** The pins fanned out from one spot (tests). */
    val fanned: List<String> get() = spider.map { it.id }

    private fun fanPos(i: Int, n: Int, hub: GeoPoint, f: Float): GeoPoint {
        val pj = mv.projection
        val c = pj.toPixels(hub, null)
        val r = (if (n <= 8) 30f else 30f + 3f * (n - 8)) * dens * f
        val a = -PI / 2 + 2 * PI * i / n
        val g = pj.fromPixels((c.x + r * cos(a)).roundToInt(), (c.y + r * sin(a)).roundToInt())
        return GeoPoint(g.latitude, g.longitude)
    }

    fun clearSpider() { if (spider.isEmpty()) return; spider = emptyList(); spiderAt = null; fanJob?.cancel(); key = null; refresh() }

    /** Pins on one spot: they fan out round it, with legs back to the spot. */
    fun spiderfy(g: List<HomePin>) {
        spider = g.sortedBy { it.id }; spiderAt = GeoPoint(g.sumOf { it.ll.lat } / g.size, g.sumOf { it.ll.lng } / g.size); spiderZ = floor(mv.zoomLevelDouble * 2) / 2
        fan = if (animate) 0f else 1f
        key = null; refresh()
        fanJob?.cancel(); fanJob = null
        val sc = scope
        if (animate && sc != null) fanJob = sc.launch { try { steps(220) { f -> fan = ease(f); placeFan() } } finally { fan = 1f; placeFan() } }
        else { fan = 1f; placeFan() }
    }
    private fun placeFan() {
        val hub = spiderAt ?: return
        shown.forEach { m -> val i = spider.indexOfFirst { it.id == m.pin?.id }; if (i >= 0) m.position = fanPos(i, spider.size, hub, fan) }
        mv.invalidate()
    }

    /** The you-dot pulses once (Go to where I am). */
    fun pulseYou(ll: LatLng) {
        if (!animate) return
        under.you = GeoPoint(ll.lat, ll.lng)
        youJob?.cancel(); youJob = scope?.launch { try { steps(350) { f -> under.youF = f; mv.invalidate() } } finally { under.youF = -1f; mv.invalidate() } }
    }

    fun refresh() {
        val z = floor(mv.zoomLevelDouble * 2) / 2
        if (spider.isNotEmpty() && (z != spiderZ || spider.any { s -> pins.none { it.id == s.id } })) { spider = emptyList(); spiderAt = null; fanJob?.cancel() }
        val k = listOf(pins, sel, z, spider.map { it.id })
        if (k == key) return
        key = k
        val ids = pins.map { it.id }.toSet()
        // a filter changed what is on the map (same zoom): what leaves shrinks and fades out, what comes grows in
        val filtered = animate && z == lastZ && ids != lastIds && lastIds.isNotEmpty()
        lastZ = z; lastIds = ids
        tween?.cancel(); tween = null
        mv.overlays.removeAll(leaving); leaving.clear()
        val before = shown.associateBy { it.id }
        mv.overlays.removeAll(shown); mv.overlays.remove(labels); mv.overlays.remove(under)
        val spIds = spider.map { it.id }.toSet()
        // the chosen pin always stands alone, on top
        val groups = clusterPins(pins.filter { it.id != sel && it.id !in spIds }, z, 30.0 * dens)
        val out = ArrayList<PinMarker>(groups.size + spider.size + 1)
        groups.forEach { g -> out += if (g.size == 1) pinMarker(g[0], false) else groupMarker(g) }
        val hub = spiderAt
        spider.forEachIndexed { i, p -> if (p.id != sel && hub != null) out += pinMarker(p, false).apply { position = fanPos(i, spider.size, hub, fan) } }
        pins.firstOrNull { it.id == sel }?.let { p -> out += pinMarker(p, true).apply { val i = spider.indexOfFirst { it.id == p.id }; if (i >= 0 && hub != null) position = fanPos(i, spider.size, hub, fan) } }
        // names under single pins (not the chosen one: its card names it); the groups and the chosen pin kept clear of
        labels.set(out.filter { it.pin != null && !it.big }.sortedWith(compareByDescending<PinMarker> { condRank(it.pin!!.cond) }.thenBy { it.pin!!.name }).map { Triple(it.pin!!.id, it.position, it.pin.name) },
            out.filter { it.pin == null || it.big }.map { it.position }, art.radius(false), art.groupRadius(9))
        under.hub = hub; under.legs = if (hub != null) out.filter { m -> m.pin?.id in spIds } else emptyList()
        // above the map's tiles, the long-press catcher and what goes under the pins (heat, pulse, the route's line:
        // MapLayers.kt), under your own dot and anything drawn later
        val at = mv.overlays.indexOfLast { it is MapEventsOverlay || it is org.osmdroid.views.overlay.TilesOverlay || it is UnderPins } + 1
        mv.overlays.addAll(at.coerceIn(0, mv.overlays.size), listOf(under) + out + listOf(labels))
        shown.clear(); shown += out
        // where each starts and ends: the chosen pin lifts and grows, the others dim a little
        val dim = sel != null
        val lift = 3f * dens
        val from = out.map { m ->
            val was = before[m.id]
            val a1 = if (dim && !m.big) 0.6f else 1f
            when {
                !animate -> floatArrayOf(1f, a1, if (m.big) lift else 0f)
                was != null -> floatArrayOf(was.scale * size(was) / size(m), was.alpha, was.lift)
                filtered -> floatArrayOf(0.3f, 0f, 0f)
                m.big -> floatArrayOf(art.radius(false) / art.radius(true), 1f, 0f)
                else -> floatArrayOf(1f, a1, 0f)
            }
        }
        val to = out.map { m -> floatArrayOf(1f, if (dim && !m.big) 0.6f else 1f, if (m.big) lift else 0f) }
        labels.alpha = if (dim) 0.6f else 1f
        if (filtered) before.values.filter { o -> out.none { it.id == o.id } }.forEach { o -> leaving += o }
        if (leaving.isNotEmpty()) mv.overlays.addAll(mv.overlays.indexOf(labels).coerceAtLeast(0), leaving)
        val lFrom = leaving.map { floatArrayOf(it.scale, it.alpha) }
        fun apply(f: Float) {
            out.forEachIndexed { i, m -> m.scale = lerp(from[i][0], to[i][0], f); m.alpha = lerp(from[i][1], to[i][1], f); m.lift = lerp(from[i][2], to[i][2], f) }
            leaving.forEachIndexed { i, m -> m.scale = lerp(lFrom[i][0], 0.3f, f); m.alpha = lerp(lFrom[i][1], 0f, f) }
            mv.invalidate()
        }
        val moving = animate && out.indices.any { i -> from[i].indices.any { j -> from[i][j] != to[i][j] } } || leaving.isNotEmpty()
        val sc = scope
        if (!moving || sc == null) { apply(1f); mv.overlays.removeAll(leaving); leaving.clear(); return }
        apply(0f)
        tween = sc.launch {
            try { steps(if (filtered) 240 else 220) { f -> apply(ease(f)) } }
            finally { apply(1f); mv.overlays.removeAll(leaving); leaving.clear(); mv.invalidate() }
        }
    }

    private fun size(m: PinMarker) = if (m.group != null) art.groupRadius(m.group.size) else art.radius(m.big)

    private fun pinMarker(p: HomePin, big: Boolean) = PinMarker(mv, p, null, big).apply {
        id = "pin:" + p.id; title = p.id; snippet = p.cond
        position = GeoPoint(p.ll.lat, p.ll.lng); icon = art.pin(p.type, p.cond, p.due, big); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); setInfoWindow(null)
        setOnMarkerClickListener { _, _ -> onPin(p.id); true }
        onLong = { m -> val pt = mv.projection.toPixels(m.position, null); onLongPin(p.id, pt.x.toFloat(), pt.y.toFloat()); true }
    }

    private fun groupMarker(g: List<HomePin>) = PinMarker(mv, null, g, false).apply {
        val worst = g.maxBy { condRank(it.cond) }.cond
        val parts = condParts(g)
        id = "cluster:" + g.size + ":" + g[0].id; title = g.size.toString(); snippet = worst; subDescription = parts.joinToString(",")
        position = GeoPoint(g.sumOf { it.ll.lat } / g.size, g.sumOf { it.ll.lng } / g.size)
        icon = art.donut(parts, g.size); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); setInfoWindow(null)
        setOnMarkerClickListener { _, _ -> onExplore(); zoomInto(g); true }
    }

    /** A tap on a group zooms smoothly into its bounds; pins on the same spot fan out where they are instead. */
    fun zoomInto(g: List<HomePin>) {
        val box = BoundingBox.fromGeoPointsSafe(g.map { GeoPoint(it.ll.lat, it.ll.lng) })
        val z = mv.zoomLevelDouble
        // (osmdroid pads a box round a single spot, so the spread is measured here)
        if (g.maxOf { it.ll.lat } - g.minOf { it.ll.lat } < 1e-4 && g.maxOf { it.ll.lng } - g.minOf { it.ll.lng } < 1e-4) { spiderfy(g); return }
        mv.zoomToBoundingBox(box.increaseByScale(1.6f), animate)
        // never leave it where it was: at least two steps closer
        mv.post { if (mv.zoomLevelDouble < z + 1) mapFly(mv, LatLng(box.centerLatitude, box.centerLongitude), z + 2, animate) }
    }

    companion object {
        fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f
        fun ease(f: Float) = 1 - (1 - f) * (1 - f) * (1 - f)
        /** Steps [f] from 0 to 1 over [ms] on the screen's clock. */
        suspend fun steps(ms: Int, step: (Float) -> Unit) {
            var el = 0
            while (el < ms) { step(el / ms.toFloat()); delay(16); el += 16 }
            step(1f)
        }
    }
}

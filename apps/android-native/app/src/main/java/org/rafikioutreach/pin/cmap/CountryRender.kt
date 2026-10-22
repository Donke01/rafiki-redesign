package org.rafikioutreach.pin.cmap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow

/**
 * Draws one map tile from the country files' vector tiles: land and water, rivers, borders, roads and tracks,
 * schools, clinics, churches and water points as small dots with a picture, and the names of places.
 * The same look as the web app (pin.html, cmDraw). Closer than zoom 14 it draws from the zoom-14 tile ("over-zoom").
 */
object CountryRender {
    const val BG = 0xFFF2EFE9.toInt()
    private val LAND = mapOf("park" to 0xFFD3EBC5.toInt(), "forest" to 0xFFC3DDAF.toInt(), "town" to 0xFFE8E2DB.toInt())
    private const val WATER = 0xFFA9D3E7.toInt(); private const val WETLAND = 0xFFCFE7EA.toInt(); private const val WLINE = 0xFF7DB7D8.toInt()
    /** kind → fill, casing (null: a single line), drawing order */
    private val ROAD = mapOf(
        "major" to Triple(0xFFF4A259.toInt(), 0xFFB5703A.toInt(), 7), "primary" to Triple(0xFFF8CD6C.toInt(), 0xFFB39245.toInt(), 6),
        "secondary" to Triple(0xFFFAE88F.toInt(), 0xFFB2A25E.toInt(), 5), "tertiary" to Triple(0xFFFFFFFF.toInt(), 0xFFB1A99B.toInt(), 4),
        "minor" to Triple(0xFFFFFFFF.toInt(), 0xFFBFB8AB.toInt(), 3), "service" to Triple(0xFFFFFFFF.toInt(), 0xFFCBC4B8.toInt(), 2),
        "track" to Triple(0xFF99734D.toInt(), null, 1), "path" to Triple(0xFFA6856A.toInt(), null, 0), "rail" to Triple(0xFF8A8A8A.toInt(), null, 1))
    val POI = mapOf("school" to 0xFFDB7614.toInt(), "health" to 0xFFD63333.toInt(), "worship" to 0xFF7756B5.toInt(), "water" to 0xFF1683D6.toInt())
    /** place kind → text size, bold, colour, importance */
    private val PLACE = mapOf("city" to arrayOf(16f, true, 0xFF1D2125.toInt(), 0), "town" to arrayOf(14f, true, 0xFF24292D.toInt(), 1), "village" to arrayOf(12.5f, true, 0xFF2E3338.toInt(), 2),
        "suburb" to arrayOf(11.5f, false, 0xFF495056.toInt(), 3), "hamlet" to arrayOf(11.5f, false, 0xFF3B4146.toInt(), 4), "neighbourhood" to arrayOf(11f, false, 0xFF535A60.toInt(), 5), "locality" to arrayOf(11f, false, 0xFF535A60.toInt(), 6))
    private val W = mapOf(
        "major" to listOf(5f to .8f, 8f to 1.6f, 10f to 2.6f, 12f to 4f, 14f to 7f, 16f to 12f, 18f to 22f), "primary" to listOf(7f to .8f, 10f to 2f, 12f to 3.4f, 14f to 6f, 16f to 10f, 18f to 18f),
        "secondary" to listOf(9f to .8f, 11f to 1.6f, 12f to 2.6f, 14f to 5f, 16f to 9f, 18f to 16f), "tertiary" to listOf(10f to .7f, 12f to 2f, 14f to 4.4f, 16f to 8f, 18f to 14f),
        "minor" to listOf(12f to .8f, 13f to 1.6f, 14f to 3.2f, 16f to 7f, 18f to 12f), "service" to listOf(13f to .6f, 14f to 1.6f, 16f to 4f, 18f to 8f),
        "track" to listOf(13f to .9f, 14f to 1.3f, 16f to 2.2f, 18f to 3.2f), "path" to listOf(14f to .8f, 16f to 1.3f, 18f to 2f), "rail" to listOf(8f to .6f, 12f to 1.2f, 16f to 2.5f),
        "river" to listOf(8f to .6f, 10f to 1f, 12f to 1.6f, 14f to 2.6f, 16f to 5f, 18f to 9f), "stream" to listOf(12f to .5f, 14f to 1f, 16f to 2f, 18f to 3f), "canal" to listOf(11f to .6f, 14f to 1.6f, 16f to 3f, 18f to 5f))

    fun width(kind: String, z: Int): Float {
        val st = W[kind] ?: W.getValue("stream"); val zz = z.toFloat()
        if (zz <= st[0].first) return st[0].second
        for (i in 1 until st.size) if (zz <= st[i].first) { val a = st[i - 1]; val b = st[i]; return a.second + (b.second - a.second) * (zz - a.first) / (b.first - a.first) }
        return st.last().second
    }

    /** The layers of one source tile, and the zoom of that tile (14 or less). */
    class Source(val layers: Map<String, MvtLayer>, val z: Int)

    /**
     * Draws tile z/x/y into a [size] × [size] bitmap. [density] is the screen's (osmdroid draws map tiles 256 pixels
     * wide whatever the screen), so lines, dots and names are sized for the screen, not the tile.
     */
    fun draw(sources: List<Source>, z: Int, x: Int, y: Int, size: Int, density: Float): Bitmap {
        val bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); val c = Canvas(bm)
        c.drawColor(BG)
        if (sources.isEmpty()) return bm
        val lw = maxOf(1f, density * .65f) * size / 256f; val tw = density * .85f * size / 256f; val iw = maxOf(1f, density * .75f) * size / 256f
        val sz = sources[0].z; val f = 2.0.pow(z - sz).toInt(); val ox = (x - floor(x.toDouble() / f) * f).toFloat() * size; val oy = (y - floor(y.toDouble() / f) * f).toFloat() * size
        class It(val f: MvtFeature, val k: Float)
        fun layer(n: String): List<It> = sources.flatMap { s -> s.layers[n]?.let { l -> l.features.map { It(it, size.toFloat() * f / l.extent) } } ?: emptyList() }
        fun path(it: It, close: Boolean): Path {
            val p = Path(); p.fillType = Path.FillType.EVEN_ODD
            for (pt in it.f.parts) { var i = 0; while (i + 1 < pt.size) { val px = pt[i] * it.k - ox; val py = pt[i + 1] * it.k - oy; if (i == 0) p.moveTo(px, py) else p.lineTo(px, py); i += 2 }; if (close) p.close() }
            return p
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        for (it in layer("land")) if (it.f.type == MvtFeature.AREA) { fill.color = LAND[it.f.str("kind")] ?: LAND.getValue("park"); c.drawPath(path(it, true), fill) }
        for (it in layer("water")) if (it.f.type == MvtFeature.AREA) { fill.color = if (it.f.str("kind") == "wetland") WETLAND else WATER; c.drawPath(path(it, true), fill) }
        for (it in layer("waterway")) if (it.f.type == MvtFeature.LINE) {
            val k = when (it.f.str("kind")) { "river" -> "river"; "canal" -> "canal"; else -> "stream" }
            line.color = WLINE; line.strokeWidth = width(k, z) * lw; line.pathEffect = null; c.drawPath(path(it, false), line)
        }
        for (it in layer("boundary")) if (it.f.type == MvtFeature.AREA) {
            val two = it.f.num("level").toInt() == 2
            line.color = if (two) 0xFF8A6AAF.toInt() else 0xFFA996C6.toInt(); line.strokeWidth = (if (two) (if (z < 8) 1.4f else 2.2f) else (if (z < 10) .9f else 1.4f)) * lw
            line.pathEffect = DashPathEffect(if (two) floatArrayOf(6 * lw, 3 * lw, 1.5f * lw, 3 * lw) else floatArrayOf(4 * lw, 3 * lw), 0f); c.drawPath(path(it, true), line)
        }
        line.pathEffect = null
        val roads = layer("roads").filter { it.f.type == MvtFeature.LINE }.sortedBy { (ROAD[it.f.str("kind")] ?: ROAD.getValue("minor")).third }
        val paths = roads.associateWith { path(it, false) }
        for (it in roads) { val st = ROAD[it.f.str("kind")] ?: ROAD.getValue("minor"); val w = width(it.f.str("kind") ?: "minor", z); if (st.second == null || w < 1.8f) continue
            line.color = st.second!!; line.strokeWidth = (w + if (z >= 15) 2.2f else 1.6f) * lw; c.drawPath(paths.getValue(it), line) }
        for (it in roads) {
            val k = it.f.str("kind") ?: "minor"; val st = ROAD[k] ?: ROAD.getValue("minor"); val w = width(k, z)
            line.color = if (st.second != null && w < 1.8f) st.second!! else st.first; line.strokeWidth = w * lw
            line.pathEffect = when (k) { "track" -> DashPathEffect(floatArrayOf(5 * lw, 3 * lw), 0f); "path" -> DashPathEffect(floatArrayOf(1.5f * lw, 3 * lw), 0f); else -> null }
            c.drawPath(paths.getValue(it), line); line.pathEffect = null
            if (k == "rail" && z >= 12) { line.color = -1; line.strokeWidth = w * .5f * lw; line.pathEffect = DashPathEffect(floatArrayOf(4 * lw, 4 * lw), 0f); c.drawPath(paths.getValue(it), line); line.pathEffect = null }
        }
        // names: never on top of each other
        val boxes = ArrayList<RectF>()
        fun free(r: RectF): Boolean { if (boxes.any { RectF.intersects(it, r) }) return false; boxes.add(r); return true }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; color = 0xEBFFFFFF.toInt() }
        fun label(s: String, px: Float, py: Float, sizePx: Float, bold: Boolean, col: Int, h: Float) {
            val tf = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            text.typeface = tf; halo.typeface = tf; text.textSize = sizePx; halo.textSize = sizePx; halo.strokeWidth = h; text.color = col
            val base = py - (text.ascent() + text.descent()) / 2
            c.drawText(s, px, base, halo); c.drawText(s, px, base, text)
        }
        // schools, clinics, churches and water points
        val pr = (if (z >= 15) 8.5f else if (z >= 14) 7f else if (z >= 13) 5.5f else 3f) * iw
        val pois = layer("pois").filter { it.f.type == MvtFeature.POINT && it.f.parts.isNotEmpty() && it.f.parts[0].size >= 2 }
        val placed = ArrayList<Triple<MvtFeature, Float, Float>>()
        for (it in pois) {
            val px = it.f.parts[0][0] * it.k - ox; val py = it.f.parts[0][1] * it.k - oy
            if (px < -pr || py < -pr || px > size + pr || py > size + pr) continue
            val col = POI[it.f.str("kind")] ?: POI.getValue("water")
            fill.color = col; c.drawCircle(px, py, pr, fill)
            line.color = -1; line.strokeWidth = 1.4f * iw; c.drawCircle(px, py, pr, line)
            if (z >= 13) glyph(c, it.f.str("kind") ?: "water", it.f.str("rel"), px, py, pr * .62f, col)
            boxes.add(RectF(px - pr, py - pr, px + pr, py + pr)); placed.add(Triple(it.f, px, py))
        }
        val places = layer("places").filter { it.f.type == MvtFeature.POINT && !it.f.str("name").isNullOrBlank() && it.f.parts.isNotEmpty() }
            .sortedWith(compareBy<It>({ (PLACE[it.f.str("kind")]?.get(3) as? Int) ?: 9 }, { -it.f.num("pop") }, { it.f.str("name") }))
        for (it in places) {
            val st = PLACE[it.f.str("kind")] ?: PLACE.getValue("locality")
            val px = it.f.parts[0][0] * it.k - ox; val py = it.f.parts[0][1] * it.k - oy
            val ts = (st[0] as Float) * (if (z < 7) .85f else 1f) * tw; text.typeface = if (st[1] as Boolean) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; text.textSize = ts
            val name = it.f.str("name")!!; val w = text.measureText(name); val h = ts * 1.25f
            if (px + w / 2 < -40 * tw || px - w / 2 > size + 40 * tw || py < -30 * tw || py > size + 30 * tw) continue
            if (!free(RectF(px - w / 2 - 2 * tw, py - h / 2, px + w / 2 + 2 * tw, py + h / 2))) continue
            label(name, px, py, ts, st[1] as Boolean, st[2] as Int, 3.2f * tw)
        }
        // road numbers (A104, C39) in small boxes
        if (z >= 9) {
            val seen = HashSet<String>()
            for (it in roads) {
                val ref = it.f.str("ref") ?: continue; val k = it.f.str("kind") ?: continue
                if (ref in seen || k !in setOf("major", "primary", "secondary")) continue
                var best: Pair<Float, Float>? = null; var bl = 0f
                for (pt in it.f.parts) { var i = 2; while (i + 1 < pt.size) { val dx = (pt[i] - pt[i - 2]).toFloat(); val dy = (pt[i + 1] - pt[i - 1]).toFloat(); val l = dx * dx + dy * dy; if (l > bl) { bl = l; best = (pt[i] + pt[i - 2]) / 2f to (pt[i + 1] + pt[i - 1]) / 2f }; i += 2 } }
                val b = best ?: continue; val px = b.first * it.k - ox; val py = b.second * it.k - oy
                if (px < 20 * tw || py < 12 * tw || px > size - 20 * tw || py > size - 12 * tw) continue
                text.typeface = Typeface.DEFAULT_BOLD; text.textSize = 10 * tw; val w = text.measureText(ref) + 8 * tw; val h = 15 * tw
                val r = RectF(px - w / 2, py - h / 2, px + w / 2, py + h / 2); if (!free(r)) continue; seen.add(ref)
                fill.color = -1; c.drawRoundRect(r, 3 * tw, 3 * tw, fill); line.color = ROAD.getValue(k).second!!; line.strokeWidth = 1.2f * tw; c.drawRoundRect(r, 3 * tw, 3 * tw, line)
                text.color = 0xFF3A3226.toInt(); c.drawText(ref, px, py - (text.ascent() + text.descent()) / 2, text)
            }
        }
        if (z >= 16) for ((f2, px, py0) in placed) {
            val name = f2.str("name") ?: continue; val ts = 10.5f * tw; text.typeface = Typeface.DEFAULT_BOLD; text.textSize = ts
            val py = py0 + pr + 8 * tw; val w = text.measureText(name)
            if (!free(RectF(px - w / 2, py - 7 * tw, px + w / 2, py + 7 * tw))) continue
            label(name, px, py, ts, true, 0xFF3A3F44.toInt(), 3 * tw)
        }
        return bm
    }

    /** The white pictures in the dots: an open book, a cross, a church cross or crescent, a drop of water. */
    fun glyph(c: Canvas, kind: String, rel: String?, x: Float, y: Float, r: Float, col: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -1; style = Paint.Style.FILL }
        c.save(); c.translate(x, y); c.scale(r, r)
        when {
            kind == "school" -> {
                c.drawPath(Path().apply { moveTo(-.8f, -.45f); lineTo(0f, -.25f); lineTo(.8f, -.45f); lineTo(.8f, .55f); lineTo(0f, .75f); lineTo(-.8f, .55f); close() }, p)
                c.drawLine(0f, -.25f, 0f, .75f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; strokeWidth = .16f; style = Paint.Style.STROKE })
            }
            kind == "health" -> { c.drawRect(-.24f, -.72f, .24f, .72f, p); c.drawRect(-.72f, -.24f, .72f, .24f, p) }
            kind == "worship" && rel == "muslim" -> { c.drawCircle(0f, 0f, .66f, p); p.color = col; c.drawCircle(.28f, -.12f, .56f, p) }
            kind == "worship" -> { c.drawRect(-.16f, -.8f, .16f, .8f, p); c.drawRect(-.56f, -.4f, .56f, -.1f, p) }
            else -> c.drawPath(Path().apply {
                moveTo(0f, -.82f); cubicTo(.42f, -.28f, .62f, .02f, .62f, .3f); arcTo(RectF(-.62f, -.32f, .62f, .92f), 0f, 180f); cubicTo(-.62f, .02f, -.42f, -.28f, 0f, -.82f); close()
            }, p)
        }
        c.restore()
    }

    /** The edges of tile z/x/y in degrees: west, south, east, north. */
    fun bounds(z: Int, x: Int, y: Int): DoubleArray {
        val n = 2.0.pow(z)
        fun lat(t: Double): Double { val m = PI - 2 * PI * t / n; return Math.toDegrees(atan(.5 * (exp(m) - exp(-m)))) }
        return doubleArrayOf(x / n * 360 - 180, lat(y + 1.0), (x + 1) / n * 360 - 180, lat(y.toDouble()))
    }
    fun srcZoom(z: Int, maxZoom: Int) = min(z, maxZoom)
}

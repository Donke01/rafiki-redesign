package org.rafikioutreach.pin.ui

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.IConfigurationProvider
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.Geo
import org.rafikioutreach.pin.data.LatLng
import org.rafikioutreach.pin.t
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.tan

/*
 * Offline map areas: "Save this area for offline" on the Map.
 *
 * What the map servers allow decides how this works (checked October 2026):
 *  - tile.openstreetmap.org (the street map) forbids bulk downloading and prefetching areas
 *    (operations.osmfoundation.org/policies/tiles). Tiles may be kept once they have been viewed.
 *  - Esri World Imagery (satellite photos) is for viewing; saving areas ahead needs an ArcGIS plan with export.
 *  - Google Map Tiles API (when the office sets a key) may only be cached as its cache headers say, and never
 *    prefetched or stored for offline use.
 * So, by default, saving an area downloads nothing by itself. It marks the area, and while you look over it
 * (with signal) the street map and satellite tiles you see are kept on the phone, and are never cleared by the
 * storage clean-up below until you delete the area. While you fill an area, and whenever there is no signal,
 * the map shows the free maps (OpenStreetMap / Esri), never Google. Google tiles are cleared as soon as Google's
 * cache time runs out.
 * If the office sets PIN_OFFLINE_TILES in Cloudflare (a tile server whose terms allow saving areas for offline
 * use, e.g. RAFIKI's own), /api/pin sends it as "offlineTiles": it becomes the street map, and saving an area
 * downloads the whole area from it, with progress. No paid service is used.
 *
 * Storage: map tiles live in the app's own files (not the cache folder Android may empty when the phone runs low),
 * up to the limit chosen in Offline maps (100 MB – 1 GB). Tiles you viewed outside saved areas are cleared once
 * they have been out of date for the chosen number of days (7, 30 or 90).
 */

/** One saved area: a box on the map and the zooms kept for it. */
data class OfflineArea(
    val id: String, val name: String, val n: Double, val s: Double, val e: Double, val w: Double,
    val minZ: Int, val maxZ: Int, val at: Long, val bulk: Boolean,
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("n", n).put("s", s).put("e", e).put("w", w)
        .put("minZ", minZ).put("maxZ", maxZ).put("at", at).put("bulk", bulk)
    val box: BoundingBox get() = BoundingBox(n, e, s, w)
    val tiles: Long get() = (minZ..maxZ).sumOf { TileMath.count(n, s, e, w, it) }
    companion object {
        fun from(o: JSONObject) = OfflineArea(o.getString("id"), o.optString("name"), o.getDouble("n"), o.getDouble("s"), o.getDouble("e"), o.getDouble("w"),
            o.optInt("minZ", 9), o.optInt("maxZ", 14), o.optLong("at"), o.optBoolean("bulk"))
    }
}

/** Web-mercator tile numbers (the same as OpenStreetMap, Esri and Google). */
object TileMath {
    fun x(lon: Double, z: Int): Int = floor((lon + 180.0) / 360.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
    fun y(lat: Double, z: Int): Int {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return floor((1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
    }
    fun rect(n: Double, s: Double, e: Double, w: Double, z: Int) = Rect(x(w, z), y(n, z), x(e, z), y(s, z))
    fun count(n: Double, s: Double, e: Double, w: Double, z: Int): Long = (x(e, z) - x(w, z) + 1).toLong() * (y(s, z) - y(n, z) + 1).toLong()
}

/** A tile server the office set up for offline use (PIN_OFFLINE_TILES). */
class OfficeTiles(private val tpl: String, maxZoom: Int, attr: String) :
    OnlineTileSourceBase(OfflineMaps.OFFICE_NAME, 3, maxZoom, 256, "", arrayOf(tpl), attr) {
    override fun getTileURLString(i: Long) = tpl.replace("{z}", MapTileIndex.getZoom(i).toString()).replace("{x}", MapTileIndex.getX(i).toString()).replace("{y}", MapTileIndex.getY(i).toString())
    val template get() = tpl
}

/** osmdroid's own tile database (the map cache), so areas can be counted, kept and cleared. */
private class TileDb : SqlTileWriter() {
    fun db(): SQLiteDatabase? = runCatching { getDb() }.getOrNull()
    fun where(z: Int, r: Rect): String = "(" + getWhereClause(z, r) + ")"
}

object OfflineMaps {
    const val OFFICE_NAME = "RAFIKI offline"
    private const val DAY = 86_400_000L
    /** Average size of one map tile, for the size estimate. */
    const val STREET_BYTES = 15L * 1024
    /** Browse-to-keep areas: the deepest zoom kept has at most this many tiles (about 10 MB for the area). */
    const val VIEW_CAP = 700L
    /** Downloaded areas (office tile server): at most this many tiles in all. */
    const val BULK_CAP = 6000L
    val LIMITS = listOf(100, 250, 500, 1000)
    val KEEPS = listOf(7, 30, 90)

    private var app: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db by lazy { TileDb() }
    private var cfg: IConfigurationProvider? = null

    private val _areas = MutableStateFlow<List<OfflineArea>>(emptyList())
    val areas: StateFlow<List<OfflineArea>> = _areas
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online
    /** The area being filled by looking over it (browse-to-keep), shown on the map with a progress bar. */
    val filling = MutableStateFlow<OfflineArea?>(null)
    /** Ask the map to show this area. */
    val flyTo = MutableStateFlow<OfflineArea?>(null)
    data class Download(val id: String, val name: String, val done: Int, val total: Int, val failed: Int, val finished: Boolean)
    val download = MutableStateFlow<Download?>(null)
    private var dlJob: Job? = null
    /** The office tile server, if set (from /api/pin "offlineTiles"). */
    var office: OfficeTiles? = null; private set
    /** The map on screen, to read the area you are looking at. */
    var mapView: WeakReference<MapView>? = null
    /** Lets tests swap the network for downloads. */
    var fetch: (String) -> Pair<Int, ByteArray?> = ::get

    var limitMb: Int
        get() = sp()?.getInt("limit", 250) ?: 250
        set(v) { sp()?.edit()?.putInt("limit", v)?.apply(); cfg?.let { applyLimit(it) } }
    var keepDays: Int
        get() = sp()?.getInt("keep", 30) ?: 30
        set(v) { sp()?.edit()?.putInt("keep", v)?.apply(); scope.launch { cleanup() } }

    private fun sp() = app?.getSharedPreferences("offline_maps", Context.MODE_PRIVATE)

    /** Called once from MapSetup: where tiles are kept, how much room they get, the network watch and a daily tidy-up. */
    fun configure(ctx: Context, c: IConfigurationProvider) {
        app = ctx.applicationContext; cfg = c
        // in the app's own files: Android may empty the cache folder when the phone runs low on space
        val dir = File(ctx.filesDir, "osm/tiles"); val old = File(ctx.cacheDir, "tiles")
        if (!File(dir, "cache.db").exists() && File(old, "cache.db").exists()) {
            dir.mkdirs(); old.listFiles()?.filter { it.name.startsWith("cache.db") }?.forEach { it.renameTo(File(dir, it.name)) }
        }
        c.osmdroidTileCache = dir
        applyLimit(c)
        _areas.value = load()
        officeFrom(runCatching { org.rafikioutreach.pin.data.Prefs(ctx).loadSnapshot()?.optJSONObject("offlineTiles") }.getOrNull())
        watchNet(ctx)
        val last = sp()?.getLong("tidy", 0L) ?: 0L
        if (System.currentTimeMillis() - last > DAY) scope.launch { runCatching { cleanup() }; sp()?.edit()?.putLong("tidy", System.currentTimeMillis())?.apply() }
    }

    private fun applyLimit(c: IConfigurationProvider) {
        val b = limitMb.toLong() * 1024 * 1024
        c.tileFileSystemCacheMaxBytes = b; c.tileFileSystemCacheTrimBytes = b * 85 / 100
    }

    private fun watchNet(ctx: Context) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        _online.value = cm.activeNetwork != null
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { _online.value = true }
                override fun onLost(network: Network) { _online.value = cm.activeNetwork != null && cm.activeNetwork != network }
            })
        }
    }
    /** For tests: pretend the signal went (or came back). */
    fun setOnline(v: Boolean) { _online.value = v }

    /** The office tile server from the server's answer; known no-bulk servers are refused here too. */
    fun officeFrom(o: JSONObject?) {
        val url = o?.optString("url").orEmpty()
        val host = runCatching { URL(url.replace(Regex("\\{[a-z]\\}"), "0")).host }.getOrDefault("")
        office = if (url.startsWith("https://") && url.contains("{z}") && host.isNotBlank() && !NO_BULK.containsMatchIn(host))
            (office?.takeIf { it.template == url } ?: OfficeTiles(url, o!!.optInt("maxZoom", 16).coerceIn(10, 18), o.optString("attr"))) else null
    }
    private val NO_BULK = Regex("(^|\\.)(openstreetmap\\.(org|fr)|arcgisonline\\.com|arcgis\\.com|googleapis\\.com|google\\.com|opentopomap\\.org|cartocdn\\.com)$", RegexOption.IGNORE_CASE)

    /** The street map when Google isn't used: the office's server if it has one, else OpenStreetMap. */
    fun street(): ITileSource = office ?: TileSourceFactory.MAPNIK

    /** Google maps only with signal and when not filling an offline area (Google tiles can't be kept offline). */
    @Composable
    fun google(g: GoogleMaps?): GoogleMaps? {
        val on by online.collectAsState(); val f by filling.collectAsState()
        return if (!on || f != null) null else g
    }

    // --- saved areas ------------------------------------------------------------------------------
    private fun load(): List<OfflineArea> = runCatching {
        val a = JSONArray(sp()?.getString("areas", "[]") ?: "[]"); (0 until a.length()).map { OfflineArea.from(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())
    private fun save(list: List<OfflineArea>) { _areas.value = list; sp()?.edit()?.putString("areas", JSONArray(list.map { it.json() }).toString())?.apply() }

    /** The zooms to keep for a box, or null when it is too big. */
    fun zoomsFor(n: Double, s: Double, e: Double, w: Double, bulk: Boolean): IntRange? {
        if (bulk) {
            var total = 0L; var top = 5
            for (z in 6..(office?.maximumZoomLevel ?: 16)) { total += TileMath.count(n, s, e, w, z); if (total > BULK_CAP) break; top = z }
            return if (top < 11) null else 6..top
        }
        var top = -1
        for (z in 10..16) if (TileMath.count(n, s, e, w, z) <= VIEW_CAP) top = z
        return if (top < 0) null else maxOf(6, top - 5)..top
    }

    fun add(name: String, b: BoundingBox, bulk: Boolean): OfflineArea? {
        val zs = zoomsFor(b.latNorth, b.latSouth, b.lonEast, b.lonWest, bulk) ?: return null
        val a = OfflineArea("a" + System.currentTimeMillis().toString(36), name.trim().ifBlank { t("My area", "Eneo langu") }, b.latNorth, b.latSouth, b.lonEast, b.lonWest, zs.first, zs.last, System.currentTimeMillis(), bulk)
        save(_areas.value + a)
        return a
    }

    /** Deletes an area and the map tiles kept only for it. */
    fun delete(a: OfflineArea) {
        if (download.value?.id == a.id) stopDownload()
        if (filling.value?.id == a.id) filling.value = null
        val rest = _areas.value.filter { it.id != a.id }
        save(rest)
        scope.launch {
            runCatching {
                val d = db.db() ?: return@runCatching
                val mine = clause(listOf(a), 3..19); val others = clause(rest, 3..19)
                d.delete("tiles", "($mine)" + (if (others.isNotBlank()) " AND NOT ($others)" else ""), null)
            }
        }
    }

    private fun clause(list: List<OfflineArea>, zs: IntRange): String = list.flatMap { a -> zs.map { z -> db.where(z, TileMath.rect(a.n, a.s, a.e, a.w, z)) } }.joinToString(" OR ")

    /** How much of the area is kept: street map and satellite, 0..1. */
    fun coverage(a: OfflineArea): Pair<Float, Float> = runCatching {
        val d = db.db() ?: return@runCatching 0f to 0f
        val where = clause(listOf(a), a.minZ..a.maxZ)
        fun n(providers: List<String>): Long = d.rawQuery("SELECT count(*) FROM tiles WHERE provider IN (" + providers.joinToString(",") { "?" } + ") AND ($where)", providers.toTypedArray()).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        val all = a.tiles.coerceAtLeast(1).toFloat()
        (n(listOf(TileSourceFactory.MAPNIK.name(), OFFICE_NAME)) / all).coerceAtMost(1f) to (n(listOf(Satellite.name())) / all).coerceAtMost(1f)
    }.getOrDefault(0f to 0f)

    /** Bytes of map tiles kept inside the area. */
    fun areaBytes(a: OfflineArea): Long = runCatching {
        val d = db.db() ?: return@runCatching 0L
        d.rawQuery("SELECT sum(length(tile)) FROM tiles WHERE " + clause(listOf(a), 3..19), null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    }.getOrDefault(0L)

    /** All map tiles on the phone. */
    fun usedBytes(): Long = cfg?.osmdroidTileCache?.listFiles()?.filter { it.name.startsWith("cache.db") }?.sumOf { it.length() } ?: 0L

    /**
     * Keeps the tile store tidy: tiles viewed outside saved areas go once they have been out of date for
     * [keepDays] days; Google tiles go as soon as Google's cache time has run out (its terms).
     */
    fun cleanup(now: Long = System.currentTimeMillis()): Int = runCatching {
        val d = db.db() ?: return@runCatching 0
        val keep = clause(_areas.value, 3..19)
        var n = d.delete("tiles", "provider NOT LIKE 'Google%' AND expires < ?" + (if (keep.isNotBlank()) " AND NOT ($keep)" else ""), arrayOf((now - keepDays * DAY).toString()))
        n += d.delete("tiles", "provider LIKE 'Google%' AND expires < ?", arrayOf(now.toString()))
        n
    }.getOrDefault(0)

    // --- downloading a whole area (only from the office's own tile server) -------------------------
    fun startDownload(a: OfflineArea) {
        val src = office ?: return
        stopDownload()
        val total = a.tiles.toInt()
        download.value = Download(a.id, a.name, 0, total, 0, false)
        dlJob = scope.launch {
            var done = 0; var failed = 0
            loop@ for (z in a.minZ..a.maxZ) {
                val r = TileMath.rect(a.n, a.s, a.e, a.w, z)
                for (x in r.left..r.right) for (y in r.top..r.bottom) {
                    if (!isActive) break@loop
                    val idx = MapTileIndex.getTileIndex(z, x, y)
                    if (!db.exists(src.name(), idx)) {
                        val (st, bytes) = runCatching { fetch(src.getTileURLString(idx)) }.getOrDefault(0 to null)
                        if (st in 200..299 && bytes != null && bytes.isNotEmpty()) db.saveFile(src, idx, ByteArrayInputStream(bytes), System.currentTimeMillis() + 365 * DAY) else failed++
                        if (st == 0 && !_online.value) break@loop
                    }
                    done++
                    if (done % 10 == 0 || done == total) download.value = Download(a.id, a.name, done, total, failed, false)
                }
            }
            download.value = Download(a.id, a.name, done, total, failed, true)
        }
    }
    fun stopDownload() { dlJob?.cancel(); dlJob = null; download.value?.let { download.value = it.copy(finished = true) } }

    private fun get(url: String): Pair<Int, ByteArray?> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("User-Agent", runCatching { org.osmdroid.config.Configuration.getInstance().userAgentValue }.getOrDefault("RAFIKI-Pin"))
        val st = c.responseCode
        return st to (if (st in 200..299) c.inputStream.use { it.readBytes() } else null)
    }
}

/** Storage: "under 1 MB", "4.2 MB", "250 MB", then "8.0 GB" from 1 GB (the same as the web app). */
fun mbText(b: Long): String = if (b < 1024L * 1024) t("under 1 MB", "chini ya 1 MB") else org.rafikioutreach.pin.cmap.CountryMaps.mb(b)
private fun limitText(mb: Int) = if (mb >= 1000) "${mb / 1000} GB" else "$mb MB"
private fun dayText(ms: Long) = runCatching { Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy", if (org.rafikioutreach.pin.Lang.sw) java.util.Locale("sw") else java.util.Locale.US)) }.getOrDefault("")

/** Inside the map: knows the area on screen, draws the area being saved, and flies to an area when asked. */
@Composable
fun OfflineMapLayer(map: MapView) {
    val filling by OfflineMaps.filling.collectAsState()
    val fly by OfflineMaps.flyTo.collectAsState()
    DisposableEffect(map) { OfflineMaps.mapView = WeakReference(map); onDispose { if (OfflineMaps.mapView?.get() === map) OfflineMaps.mapView = null } }
    DisposableEffect(map, filling) {
        val a = filling
        val box = a?.let { Polygon(map).apply {
            points = Polygon.pointsAsRect(it.box).map { p -> p as GeoPoint }
            fillPaint.color = 0x1A1C8A50; outlinePaint.color = 0xFF1C8A50.toInt(); outlinePaint.strokeWidth = 5f
            setOnClickListener { _, _, _ -> false }; infoWindow = null
        } }
        if (box != null) { map.overlays.add(box); map.invalidate() }
        onDispose { if (box != null) { map.overlays.remove(box); map.invalidate() } }
    }
    LaunchedEffect(fly) {
        val a = fly ?: return@LaunchedEffect
        delay(650) // after the map has fitted the projects
        runCatching { map.zoomToBoundingBox(a.box.increaseByScale(1.1f), Motion.on) }
        OfflineMaps.flyTo.value = null
    }
}

/** The "Save this area for offline" sheet (opened from More › Offline areas) and the bar shown while an area fills. */
@Composable
fun BoxScope.OfflineMapControls(m: AppModel, openList: () -> Unit, sheetState: androidx.compose.runtime.MutableState<Boolean>) {
    val filling by OfflineMaps.filling.collectAsState()
    val dl by OfflineMaps.download.collectAsState()
    val online by OfflineMaps.online.collectAsState()
    // the button that opens the sheet is "Offline areas" under More, in the Map tab's column (MapHome.kt, MapSide)
    var sheet by sheetState
    val hu = C.hue("offline")
    val d = dl
    val f = filling
    if (f != null || (d != null && !d.finished)) {
        var pct by remember { mutableStateOf(0f to 0f) }
        if (f != null) LaunchedEffect(f) { while (true) { pct = withContext(Dispatchers.IO) { OfflineMaps.coverage(f) }; delay(2000) } }
        Column(
            Modifier.align(Alignment.TopStart).padding(12.dp).padding(end = 58.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.card)
                .border(1.dp, C.line, RoundedCornerShape(22.dp)).padding(14.dp).testTag("offline_bar"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile("offline", hu.fg, hu.bg, 38.dp); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    if (f != null) {
                        Text(t("Keeping “${f.name}” for offline", "Inahifadhi “${f.name}” kwa matumizi bila mtandao"), style = MaterialTheme.typography.titleSmall, color = C.ink)
                        Text(if (online) t("Move and zoom around the green box while you have signal. The map you see is kept on this phone.", "Sogeza na kuza ramani ndani ya kisanduku kijani ukiwa na mtandao. Ramani unayoona inahifadhiwa kwenye simu hii.")
                            else t("No signal: only what is already kept shows. Carry on when you have signal.", "Hakuna mtandao: kinachoonekana ni kile kilichohifadhiwa tu. Endelea ukipata mtandao."), style = MaterialTheme.typography.bodySmall, color = C.muted)
                    } else if (d != null) {
                        Text(t("Downloading “${d.name}”", "Inapakua “${d.name}”"), style = MaterialTheme.typography.titleSmall, color = C.ink)
                        Text(t("%,d of %,d map tiles".format(d.done, d.total), "Vigae %,d kati ya %,d vya ramani".format(d.done, d.total)), style = MaterialTheme.typography.bodySmall, color = C.muted)
                    }
                }
            }
            val frac = if (f != null) pct.first else d!!.done.toFloat() / maxOf(1, d.total)
            Meter(frac, Modifier.padding(top = 10.dp))
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (f != null) t("Street map ${(pct.first * 100).roundToInt()}% · Satellite ${(pct.second * 100).roundToInt()}%", "Ramani ya barabara ${(pct.first * 100).roundToInt()}% · Satelaiti ${(pct.second * 100).roundToInt()}%")
                    else "${(frac * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, color = C.ink, modifier = Modifier.weight(1f).testTag("offline_pct"))
                if (f != null) Btn(t("Done", "Imekamilika"), {
                    OfflineMaps.filling.value = null
                    m.showBanner(t("“${f.name}” is saved for offline: ${(pct.first * 100).roundToInt()}% of the street map is kept. Look over it again any time to keep more.",
                        "“${f.name}” imehifadhiwa kwa matumizi bila mtandao: ${(pct.first * 100).roundToInt()}% ya ramani ya barabara imehifadhiwa. Iangalie tena wakati wowote ili kuhifadhi zaidi."))
                }, small = true, ok = true, icon = "check")
                else Btn(t("Stop", "Simamisha"), { OfflineMaps.stopDownload() }, small = true, light = true)
            }
        }
    }
    if (sheet) OfflineSheet(m, { sheet = false }, openList)
}

/** "Save this area for offline": which area, its size, and how it will be kept. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineSheet(m: AppModel, close: () -> Unit, openList: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val snap by m.snap.collectAsState()
    val fix by m.fix.collectAsState()
    val areas by OfflineMaps.areas.collectAsState()
    LaunchedEffect(Unit) { OfflineMaps.officeFrom(m.prefs.loadSnapshot()?.optJSONObject("offlineTiles")) }
    val bulk = OfflineMaps.office != null
    var which by remember { mutableStateOf("screen") }
    val view = remember { OfflineMaps.mapView?.get()?.boundingBox }
    val me = fix?.ll
    // "around me": about 50 × 50 km, roughly a county
    val box: BoundingBox? = if (which == "me") me?.let { BoundingBox(it.lat + 0.225, it.lng + 0.225, it.lat - 0.225, it.lng - 0.225) } else view
    val zs = box?.let { OfflineMaps.zoomsFor(it.latNorth, it.latSouth, it.lonEast, it.lonWest, bulk) }
    val tiles = if (box != null && zs != null) zs.sumOf { TileMath.count(box.latNorth, box.latSouth, box.lonEast, box.lonWest, it) } else 0L
    val est = tiles * OfflineMaps.STREET_BYTES
    var used by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { used = withContext(Dispatchers.IO) { OfflineMaps.usedBytes() } }
    val limit = OfflineMaps.limitMb.toLong() * 1024 * 1024
    val others = areas.sumOf { it.tiles } * OfflineMaps.STREET_BYTES
    val fits = est + others <= limit
    val suggested = remember(box, snap) {
        val c = box?.centerWithDateLine
        snap?.projects?.filter { p -> p.gps != null && box != null && box.contains(p.gps.lat, p.gps.lng) && p.area.isNotBlank() }
            ?.minByOrNull { p -> if (c != null) Geo.metres(LatLng(c.latitude, c.longitude), p.gps!!) else 0.0 }?.area
            ?: t("Area ${areas.size + 1}", "Eneo ${areas.size + 1}")
    }
    var name by remember(suggested) { mutableStateOf(suggested) }
    var err by remember { mutableStateOf<String?>(null) }
    val xScope = androidx.compose.runtime.rememberCoroutineScope()
    ModalBottomSheet(close, sheetState = state, containerColor = C.card, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp).testTag("offline_sheet")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val hu = C.hue("offline"); IconTile("offline", hu.fg, hu.bg, 44.dp); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t("Save this area for offline", "Hifadhi eneo hili kwa matumizi bila mtandao"), style = MaterialTheme.typography.titleLarge, color = C.ink)
                    Text(t("Before a trip, so the map works with no signal.", "Kabla ya safari, ili ramani ifanye kazi bila mtandao."), style = MaterialTheme.typography.bodySmall, color = C.muted)
                }
                Spacer(Modifier.width(8.dp))
                SheetCloseX("oa_x") { sheetHide(xScope, state, close) }
            }
            SmallSeg(listOf("screen" to t("Area on screen", "Eneo lililo kwenye skrini"), "me" to t("Around me", "Karibu nami")), which, Modifier.fillMaxWidth().padding(top = 16.dp), tag = "oa_") { which = it }
            if (which == "me" && me == null) Msg(t("Allow location to save the area around you.", "Ruhusu mahali ili uhifadhi eneo linalokuzunguka."), "warn")
            if (which == "me" && me != null) Text(t("About 50 × 50 km around you, roughly a county.", "Takriban km 50 × 50 kukuzunguka, karibu ukubwa wa kaunti."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 8.dp))
            Field(name, { name = it }, t("Name", "Jina"), Modifier.testTag("oa_name"))
            Column(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.tint).padding(14.dp)) {
                if (box != null && zs == null) Text(t("That area is too big. Zoom in on the map, or choose Around me.", "Eneo hilo ni kubwa mno. Kuza ramani, au chagua Karibu nami."), style = MaterialTheme.typography.titleSmall, color = C.bad)
                else if (box != null) {
                    Text(t("About %,d map tiles · up to ${mbText(est)}".format(tiles), "Takriban vigae %,d vya ramani · hadi ${mbText(est)}".format(tiles)), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.testTag("oa_est"))
                    Text(if (zs!!.last >= 14) t("Zoom ${zs.first}–${zs.last}: from the whole area down to villages and roads.", "Ukuzaji ${zs.first}–${zs.last}: kutoka eneo zima hadi vijiji na barabara.")
                        else t("Zoom ${zs.first}–${zs.last}: a wide area, so only down to towns. Zoom in on the map first to keep village roads.", "Ukuzaji ${zs.first}–${zs.last}: eneo pana, kwa hiyo hadi miji tu. Kuza ramani kwanza ili kuhifadhi barabara za vijijini."), style = MaterialTheme.typography.bodySmall, color = C.muted)
                }
                Text(t("Maps on this phone: ${mbText(used)} of ${limitText(OfflineMaps.limitMb)}", "Ramani kwenye simu hii: ${mbText(used)} kati ya ${limitText(OfflineMaps.limitMb)}"), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 6.dp))
                Meter((used.toFloat() / limit).coerceIn(0f, 1f), Modifier.padding(top = 6.dp))
                if (box != null && zs != null && !fits) Text(t("Not enough room: raise the storage limit or delete an area in Saved areas.", "Nafasi haitoshi: ongeza kikomo cha hifadhi au futa eneo kwenye Maeneo yaliyohifadhiwa."), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = C.bad, modifier = Modifier.padding(top = 6.dp))
            }
            Text(if (bulk) t("The whole area downloads now from RAFIKI’s own map server. Stay on Wi-Fi or good signal until it finishes.", "Eneo lote linapakuliwa sasa kutoka kwa seva ya ramani ya RAFIKI. Baki kwenye Wi-Fi au mtandao mzuri hadi likamilike.")
                else t("The free map servers don’t allow downloading whole areas, so the app keeps what you look at. After saving, move and zoom around the area while you have signal: everything you see stays on this phone until you delete the area.",
                    "Seva za ramani za bure haziruhusu kupakua maeneo yote, kwa hiyo programu inahifadhi unachoangalia. Baada ya kuhifadhi, sogeza na kuza ramani ndani ya eneo ukiwa na mtandao: kila unachoona kinabaki kwenye simu hii hadi utakapofuta eneo."),
                style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 12.dp))
            Btn(if (bulk) t("Download this area", "Pakua eneo hili") else t("Save this area", "Hifadhi eneo hili"), {
                val b = box ?: return@Btn
                val a = OfflineMaps.add(name, b, bulk)
                if (a == null) { err = t("That area is too big.", "Eneo hilo ni kubwa mno."); return@Btn }
                if (bulk) OfflineMaps.startDownload(a) else OfflineMaps.filling.value = a
                OfflineMaps.flyTo.value = a
                close()
            }, Modifier.fillMaxWidth().padding(top = 16.dp).testTag("oa_save"), icon = "offline", enabled = box != null && zs != null && fits)
            err?.let { Msg(it, "bad") }
            Btn(t("Saved areas (${areas.size})", "Maeneo yaliyohifadhiwa (${areas.size})"), { close(); openList() }, Modifier.fillMaxWidth().padding(top = 10.dp).testTag("oa_list"), icon = "list", light = true)
        }
    }
}

/** Offline maps: the saved areas (how much is kept, delete), the storage limit and how long viewed tiles stay. */
@Composable
fun OfflineScreen(m: AppModel, back: () -> Unit) {
    val areas by OfflineMaps.areas.collectAsState()
    val dl by OfflineMaps.download.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    var used by remember { mutableLongStateOf(0L) }
    var stats by remember { mutableStateOf(mapOf<String, Pair<Float, Long>>()) }
    LaunchedEffect(areas, tick, dl?.done) {
        withContext(Dispatchers.IO) {
            used = OfflineMaps.usedBytes()
            stats = areas.associate { it.id to (OfflineMaps.coverage(it).first to OfflineMaps.areaBytes(it)) }
        }
    }
    var limit by remember { mutableIntStateOf(OfflineMaps.limitMb) }
    var keep by remember { mutableIntStateOf(OfflineMaps.keepDays) }
    var ask by remember { mutableStateOf<OfflineArea?>(null) }
    // one title: the page's own heading, and the top bar takes it over only once it has scrolled away
    val scroll = rememberScrollState()
    val headPx = with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.roundToPx() }
    ShowInBar(m, t("Offline maps", "Ramani bila mtandao"), scroll.value > headPx)
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 110.dp).testTag("offline_screen")) {
        Text(t("Offline maps", "Ramani bila mtandao"), style = MaterialTheme.typography.headlineLarge, color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(t("Map areas kept on this phone for places with no signal.", "Maeneo ya ramani yaliyohifadhiwa kwenye simu hii kwa mahali pasipo na mtandao."), style = MaterialTheme.typography.bodyMedium, color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp))
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val hu = C.hue("offline"); IconTile("offline", hu.fg, hu.bg); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t("Storage", "Hifadhi"), style = MaterialTheme.typography.titleMedium, color = C.ink)
                    Text(t("${mbText(used)} of ${limitText(limit)} used by maps", "${mbText(used)} kati ya ${limitText(limit)} zimetumiwa na ramani"), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.testTag("offline_used"))
                }
            }
            Meter((used.toFloat() / (limit * 1048576f)).coerceIn(0f, 1f), Modifier.padding(top = 10.dp))
        }
        CountryMapsSection(m) // whole-country street maps (CountryMapsUi.kt); hidden until the office sets them up
        H2(t("Saved areas", "Maeneo yaliyohifadhiwa"), Modifier.padding(top = 18.dp))
        if (areas.isEmpty()) Card {
            Text(t("No areas saved yet. On the Map, tap the green download button to save an area before a trip.", "Bado hakuna eneo lililohifadhiwa. Kwenye Ramani, gusa kitufe kijani cha kupakua ili uhifadhi eneo kabla ya safari."), style = MaterialTheme.typography.bodyMedium, color = C.muted)
        }
        areas.forEachIndexed { i, a ->
            val st = stats[a.id]
            Card(Modifier.padding(top = 10.dp).testTag("area_$i"), pad = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val hu = C.hue(listOf("green", "blue", "purple", "teal", "amber")[i % 5]); IconTile("map", hu.fg, hu.bg, 40.dp); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.name, style = MaterialTheme.typography.titleMedium, color = C.ink, maxLines = 1)
                        Text(t("Saved ${dayText(a.at)} · zoom ${a.minZ}–${a.maxZ}", "Imehifadhiwa ${dayText(a.at)} · ukuzaji ${a.minZ}–${a.maxZ}"), style = MaterialTheme.typography.bodySmall, color = C.muted)
                    }
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { ask = a }.semantics { contentDescription = t("Delete ${a.name}", "Futa ${a.name}") }.testTag("area_del_$i"), contentAlignment = Alignment.Center) {
                        Icon(Ic.get("trash"), null, tint = C.bad, modifier = Modifier.size(22.dp))
                    }
                }
                val pct = st?.first ?: 0f
                Meter(pct, Modifier.padding(top = 10.dp))
                Text(t("${(pct * 100).roundToInt()}% of the street map kept · ${mbText(st?.second ?: 0L)}", "${(pct * 100).roundToInt()}% ya ramani ya barabara imehifadhiwa · ${mbText(st?.second ?: 0L)}"), style = MaterialTheme.typography.labelMedium, color = C.ink, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn(t("Show on map", "Onyesha kwenye ramani"), { OfflineMaps.flyTo.value = a; back() }, Modifier.weight(1f), small = true, light = true, icon = "map")
                    Btn(if (a.bulk && OfflineMaps.office != null) t("Download again", "Pakua tena") else t("Keep more", "Hifadhi zaidi"), {
                        if (a.bulk && OfflineMaps.office != null) OfflineMaps.startDownload(a) else OfflineMaps.filling.value = a
                        OfflineMaps.flyTo.value = a; back()
                    }, Modifier.weight(1f), small = true, icon = "offline")
                }
            }
        }
        H2(t("Settings", "Mipangilio"), Modifier.padding(top = 18.dp))
        Card {
            Text(t("Storage limit for maps", "Kikomo cha hifadhi ya ramani"), style = MaterialTheme.typography.titleSmall, color = C.ink)
            SmallSeg(OfflineMaps.LIMITS.map { it.toString() to limitText(it) }, limit.toString(), Modifier.fillMaxWidth().padding(top = 8.dp), tag = "lim_") { limit = it.toInt(); OfflineMaps.limitMb = limit }
            Text(t("Keep map tiles I’ve viewed for", "Hifadhi vigae vya ramani nilivyoangalia kwa"), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.padding(top = 16.dp))
            SmallSeg(OfflineMaps.KEEPS.map { it.toString() to t("$it days", "siku $it") }, keep.toString(), Modifier.fillMaxWidth().padding(top = 8.dp), tag = "keep_") { keep = it.toInt(); OfflineMaps.keepDays = keep; tick++ }
            Text(t("Outside saved areas, map tiles you have looked at are cleared once they have been out of date this long. Saved areas stay until you delete them.",
                "Nje ya maeneo yaliyohifadhiwa, vigae vya ramani ulivyoangalia vinafutwa vikishapitwa na wakati kwa muda huu. Maeneo yaliyohifadhiwa yanabaki hadi utakapoyafuta."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 8.dp))
        }
        Card(Modifier.padding(top = 12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                val hu = C.hue("slate"); IconTile("alert", hu.fg, hu.bg, 36.dp); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t("Why the app doesn’t download whole areas", "Kwa nini programu haipakui maeneo yote"), style = MaterialTheme.typography.titleSmall, color = C.ink)
                    Text(if (OfflineMaps.office != null) t("RAFIKI’s own map server allows saving areas, so saved areas download in full. Google maps are never kept offline (Google’s terms).", "Seva ya ramani ya RAFIKI inaruhusu kuhifadhi maeneo, kwa hiyo maeneo yanapakuliwa yote. Ramani za Google hazihifadhiwi kamwe (masharti ya Google).")
                        else t("OpenStreetMap, Esri and Google don’t allow apps to download map areas in bulk. RAFIKI Pin keeps the tiles you look at instead, and shows the free maps when there is no signal. If the office sets up its own map server, whole areas can be downloaded.",
                            "OpenStreetMap, Esri na Google haziruhusu programu kupakua maeneo ya ramani kwa wingi. RAFIKI Pin badala yake inahifadhi vigae unavyoangalia, na inaonyesha ramani za bure pasipo na mtandao. Ofisi ikiweka seva yake ya ramani, maeneo yote yanaweza kupakuliwa."),
                        style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
    ask?.let { a ->
        AlertDialog(onDismissRequest = { ask = null }, containerColor = C.card,
            title = { Text(t("Delete “${a.name}”?", "Futa “${a.name}”?"), color = C.ink) },
            text = { Text(t("The map tiles kept for this area are removed from this phone.", "Vigae vya ramani vilivyohifadhiwa kwa eneo hili vitaondolewa kwenye simu hii."), color = C.text) },
            confirmButton = { TextButton({ OfflineMaps.delete(a); ask = null; tick++ }, Modifier.testTag("area_del_yes")) { Text(t("Delete", "Futa"), color = C.bad) } },
            dismissButton = { TextButton({ ask = null }) { Text(t("Cancel", "Ghairi"), color = C.ink) } })
    }
}

package org.rafikioutreach.pin.cmap

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.os.StatFs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase
import org.osmdroid.tileprovider.tilesource.BitmapTileSourceBase
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.TilesOverlay
import org.rafikioutreach.pin.BuildConfig
import org.rafikioutreach.pin.t
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/*
 * Whole-country street maps: one map file per country (Kenya, Uganda, Tanzania, Malawi), made from OpenStreetMap
 * data (© OpenStreetMap contributors, ODbL) with tools/country-maps, kept by RAFIKI in Cloudflare R2.
 *  - /api/pin says where they are ("countryMaps": { base }); until the office sets it up the feature stays hidden.
 *  - <base>countries.json lists them: name, file, size, date, checksum and area.
 *  - Offline maps › Whole country maps downloads one into the app's own files (not the cache folder, which Android
 *    may empty), on Wi-Fi only unless mobile data is allowed. The download continues from where it stopped after a
 *    dropped connection, Stop, or closing the app (HTTP range requests into <file>.part).
 *  - The maps draw it on top of the usual map when there is no signal, or when "Offline street map" is picked in
 *    Map style. Projects, routes and your location stay on top. Satellite photos and Google maps are never stored.
 */
object CountryMaps {
    data class Country(val code: String, val name: String, val file: String, val bytes: Long, val date: String, val sha256: String,
                       val west: Double, val south: Double, val east: Double, val north: Double, val maxZoom: Int) {
        fun json(): JSONObject = JSONObject().put("code", code).put("name", name).put("file", file).put("bytes", bytes).put("date", date).put("sha256", sha256)
            .put("bounds", JSONArray().put(west).put(south).put(east).put(north)).put("maxzoom", maxZoom)
        companion object {
            fun from(o: JSONObject): Country? {
                val file = o.optString("file"); val code = o.optString("code")
                if (!Regex("^[a-z0-9][a-z0-9._-]{0,80}\\.pmtiles$").matches(file) || code.isBlank()) return null
                val b = o.optJSONArray("bounds")
                return Country(code, o.optString("name", code.uppercase()), file, o.optLong("bytes"), o.optString("date"), o.optString("sha256"),
                    b?.optDouble(0, -180.0) ?: -180.0, b?.optDouble(1, -85.0) ?: -85.0, b?.optDouble(2, 180.0) ?: 180.0, b?.optDouble(3, 85.0) ?: 85.0, o.optInt("maxzoom", 14))
            }
        }
    }
    /** A download: bytes so far, and what it is doing (run, wait = no signal, wifi = waiting for Wi-Fi, err). */
    data class Download(val code: String, val name: String, val done: Long, val total: Long, val state: String, val msg: String = "")

    private var app: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _list = MutableStateFlow<List<Country>>(emptyList()); val list: StateFlow<List<Country>> = _list
    private val _have = MutableStateFlow<Map<String, Country>>(emptyMap()); val have: StateFlow<Map<String, Country>> = _have
    val download = MutableStateFlow<Download?>(null)
    /** "Offline street map" picked in Map style. */
    val picked = MutableStateFlow(false)
    private val _base = MutableStateFlow<String?>(null); val base: StateFlow<String?> = _base
    /** Something to tell the person (a finished download), shown once by the screen. */
    val note = MutableStateFlow<String?>(null)
    private var job: Job? = null
    private val readers = HashMap<String, Pmtiles>()

    // --- swappable for tests --------------------------------------------------------------------------------------
    class Reply(val status: Int, val etag: String, val rangeStart: Long, val body: InputStream?)
    /** GET [url] from byte [from], only if the file is still [ifMatch] (when given). */
    var http: (url: String, from: Long, ifMatch: String) -> Reply = ::get
    var getText: (String) -> String? = { u -> runCatching { (URL(u).openConnection() as HttpURLConnection).run { connectTimeout = 15000; readTimeout = 20000; if (responseCode == 200) inputStream.use { String(it.readBytes()) } else null } }.getOrNull() }
    var online: () -> Boolean = { org.rafikioutreach.pin.ui.OfflineMaps.online.value }
    /** On Wi-Fi (or another connection that isn't charged by the megabyte). */
    var unmetered: () -> Boolean = { app?.getSystemService(ConnectivityManager::class.java)?.let { it.activeNetwork != null && !it.isActiveNetworkMetered } ?: true }
    var freeBytes: () -> Long = { runCatching { StatFs(dir().path).availableBytes }.getOrDefault(Long.MAX_VALUE) }
    var retryMs: (Int) -> Long = { n -> minOf(30_000L, 2_000L shl minOf(4, n)) }

    private fun sp() = app?.getSharedPreferences("country_maps", Context.MODE_PRIVATE)
    fun dir(): File = File(app!!.filesDir, "countrymaps").apply { mkdirs() }
    var mobileData: Boolean
        get() = sp()?.getBoolean("mobile", false) ?: false
        set(v) { sp()?.edit()?.putBoolean("mobile", v)?.apply() }

    fun configure(ctx: Context) {
        app = ctx.applicationContext
        _have.value = loadMap("have").filter { File(dir(), it.value.file).exists() }
        _list.value = runCatching { JSONArray(sp()?.getString("list", "[]")).let { a -> (0 until a.length()).mapNotNull { Country.from(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
        picked.value = sp()?.getBoolean("picked", false) ?: false
        baseFrom(runCatching { org.rafikioutreach.pin.data.Prefs(ctx).loadSnapshot()?.optJSONObject("countryMaps") }.getOrNull())
    }
    fun pick(on: Boolean) { picked.value = on; sp()?.edit()?.putBoolean("picked", on)?.apply() }

    /** Where the files are, from /api/pin "countryMaps": an https address, or a path on the app's own server. */
    fun baseFrom(o: JSONObject?) {
        val b = o?.optString("base").orEmpty().trim()
        _base.value = when {
            b.startsWith("https://") && !NO_HOSTS.containsMatchIn(runCatching { URL(b).host }.getOrDefault("")) -> if (b.endsWith("/")) b else "$b/"
            b.startsWith("/") && !b.startsWith("//") -> BuildConfig.SERVER + (if (b.endsWith("/")) b else "$b/")
            else -> null
        }
    }
    private val NO_HOSTS = Regex("(^|\\.)(openstreetmap\\.(org|fr)|arcgisonline\\.com|arcgis\\.com|googleapis\\.com|google\\.com)$", RegexOption.IGNORE_CASE)

    /** The countries on the phone, ready for the map. */
    val ready: Boolean get() = _have.value.isNotEmpty()

    private fun loadMap(key: String): Map<String, Country> = runCatching {
        val o = JSONObject(sp()?.getString(key, "{}") ?: "{}"); o.keys().asSequence().mapNotNull { k -> Country.from(o.getJSONObject(k))?.let { k to it } }.toMap()
    }.getOrDefault(emptyMap())
    private fun saveHave(m: Map<String, Country>) { _have.value = m; sp()?.edit()?.putString("have", JSONObject().apply { m.forEach { (k, v) -> put(k, v.json()) } }.toString())?.apply() }

    /** Reads countries.json (with signal); keeps the last copy for when there is none. */
    fun refreshList(): List<Country> {
        val b = _base.value ?: return _list.value
        val txt = getText(b + "countries.json") ?: return _list.value
        val l = runCatching { JSONObject(txt).getJSONArray("countries").let { a -> (0 until a.length()).mapNotNull { Country.from(a.getJSONObject(it)) } } }.getOrNull() ?: return _list.value
        _list.value = l; sp()?.edit()?.putString("list", JSONArray(l.map { it.json() }).toString())?.apply()
        return l
    }

    /** Bytes of a country already saved (all of it, or the part downloaded so far). */
    fun partBytes(c: Country): Long = File(dir(), c.file + ".part").length()
    fun newer(code: String): Country? { val h = _have.value[code] ?: return null; return _list.value.firstOrNull { it.code == code && it.file != h.file } }

    // --- downloading ----------------------------------------------------------------------------------------------
    fun start(c: Country): String? {
        if (download.value?.let { it.state != "err" } == true) return t("Another map is downloading.", "Ramani nyingine inapakuliwa.")
        val need = c.bytes - partBytes(c) + 20L * 1048576
        if (freeBytes() < need) return t("Not enough free space for ${c.name} (${mb(c.bytes)}). Free some space on the phone first.", "Nafasi haitoshi kwa ${c.name} (${mb(c.bytes)}). Ondoa vitu kwenye simu kwanza.")
        val url = (_base.value ?: return t("Country maps aren’t set up yet.", "Ramani za nchi bado hazijawekwa.")) + c.file
        synchronized(lock) {
            download.value = Download(c.code, c.name, partBytes(c), c.bytes, "run")
            job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { run(c, url) }.also { it.start() }
        }
        return null
    }
    fun stop() = synchronized(lock) { job?.cancel(); job = null; download.value = null }
    private val lock = Any()
    /** Progress from a download that is still the current one (a stopped one may still be finishing a read). */
    private fun post(me: Job?, d: Download?) = synchronized(lock) { if (me != null && job === me && me.isActive) download.value = d }

    private suspend fun run(c: Country, url: String) {
        val me = kotlin.coroutines.coroutineContext[Job]
        val part = File(dir(), c.file + ".part"); var tries = 0
        // a part-download of an older file of this country is no use any more
        dir().listFiles()?.filter { it.name.endsWith(".part") && it.name != part.name && it.name.startsWith(c.file.substringBefore('-')) }?.forEach { it.delete() }
        while (me?.isActive != false) {
            val have = part.length()
            if (have >= c.bytes) { synchronized(lock) { if (job !== me) return }; finish(c, part); return }
            if (!online()) { post(me, Download(c.code, c.name, have, c.bytes, "wait")); delay(2000); continue }
            if (!mobileData && !unmetered()) { post(me, Download(c.code, c.name, have, c.bytes, "wifi")); delay(2000); continue }
            post(me, Download(c.code, c.name, have, c.bytes, "run"))
            val etag = sp()?.getString("etag_" + c.file, "") ?: ""
            try {
                val r = http(url, have, etag)
                when {
                    r.status == 412 -> { part.delete(); sp()?.edit()?.remove("etag_" + c.file)?.apply(); r.body?.close(); continue } // the file was replaced: start again
                    r.status == 206 && r.rangeStart == have -> {}
                    r.status == 200 -> part.delete() // the server sent all of it: start from the beginning
                    else -> { r.body?.close(); post(me, Download(c.code, c.name, have, c.bytes, "err", t("The map server didn’t answer as expected (${r.status}). Ask the office to check the country maps set-up.", "Seva ya ramani haikujibu kama inavyotarajiwa (${r.status}). Omba ofisi iangalie mipangilio ya ramani za nchi."))); return }
                }
                if (r.etag.isNotBlank() && etag.isBlank()) sp()?.edit()?.putString("etag_" + c.file, r.etag)?.apply()
                var got = part.length(); var last = 0L
                r.body!!.use { input ->
                    FileOutputStream(part, true).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (me?.isActive == false) return
                            if (!mobileData && !unmetered()) throw IOException("left Wi-Fi")
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); got += n
                            val now = System.currentTimeMillis()
                            if (now - last > 200) { last = now; post(me, Download(c.code, c.name, got, c.bytes, "run")) }
                        }
                    }
                }
                tries = 0
                if (part.length() < c.bytes) throw IOException("short")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // the connection dropped: keep what arrived and carry on when it can
                post(me, Download(c.code, c.name, part.length(), c.bytes, if (!mobileData && !unmetered()) "wifi" else "wait"))
                delay(retryMs(tries++))
            }
        }
    }

    private fun finish(c: Country, part: File) {
        if (part.length() > c.bytes) { part.delete(); download.value = Download(c.code, c.name, 0, c.bytes, "err", t("The download went wrong. Please try again.", "Upakuaji umeharibika. Tafadhali jaribu tena.")); return }
        if (c.sha256.isNotBlank() && sha256(part) != c.sha256.lowercase()) {
            part.delete(); download.value = Download(c.code, c.name, 0, c.bytes, "err", t("The downloaded map was damaged and has been removed. Please download it again.", "Ramani iliyopakuliwa iliharibika na imeondolewa. Tafadhali ipakue tena."))
            return
        }
        val target = File(dir(), c.file)
        if (!part.renameTo(target)) { download.value = Download(c.code, c.name, 0, c.bytes, "err", t("Couldn’t save the map. Free some space and try again.", "Imeshindwa kuhifadhi ramani. Ondoa vitu kwenye simu kisha ujaribu tena.")); return }
        val old = _have.value[c.code]
        download.value = null; job = null
        saveHave(_have.value + (c.code to c))
        if (old != null && old.file != c.file) { closeReader(old.file); File(dir(), old.file).delete() }
        sp()?.edit()?.remove("etag_" + c.file)?.apply()
        synchronized(tileCache) { tileCache.clear() }
        note.value = t("${c.name} street map saved ✓ It works with no signal.", "Ramani ya ${c.name} imehifadhiwa ✓ Inafanya kazi bila mtandao.")
    }

    fun delete(code: String) {
        if (download.value?.code == code) stop()
        val c = _have.value[code] ?: _list.value.firstOrNull { it.code == code }
        if (c != null) { closeReader(c.file); File(dir(), c.file).delete(); File(dir(), c.file + ".part").delete(); sp()?.edit()?.remove("etag_" + c.file)?.apply() }
        saveHave(_have.value - code); tileCache.clear()
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { val b = ByteArray(256 * 1024); while (true) { val n = it.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun get(url: String, from: Long, ifMatch: String): Reply {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "RAFIKI-Pin/" + BuildConfig.VERSION_NAME)
        c.setRequestProperty("Range", "bytes=$from-")
        if (ifMatch.isNotBlank()) c.setRequestProperty("If-Match", ifMatch)
        val st = c.responseCode
        val start = Regex("^bytes (\\d+)-").find(c.getHeaderField("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull() ?: -1L
        return Reply(st, c.getHeaderField("ETag").orEmpty(), start, if (st in 200..299) c.inputStream else null)
    }

    // --- reading for the map ---------------------------------------------------------------------------------------
    private fun reader(c: Country): Pmtiles? = synchronized(readers) {
        readers[c.file] ?: runCatching { Pmtiles(File(dir(), c.file)) }.getOrNull()?.also { readers[c.file] = it }
    }
    private fun closeReader(file: String) = synchronized(readers) { readers.remove(file)?.close() }
    private val tileCache = object : LinkedHashMap<String, Map<String, MvtLayer>>(32, .75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Map<String, MvtLayer>>?) = size > 24 }

    /** The decoded source tiles for map tile z/x/y from every country file that covers it (a border tile can need two). */
    fun sources(z: Int, x: Int, y: Int): List<CountryRender.Source> {
        val b = CountryRender.bounds(z, x, y)
        return _have.value.values.filter { c -> b[2] >= c.west && b[0] <= c.east && b[3] >= c.south && b[1] <= c.north }.mapNotNull { c ->
            val sz = CountryRender.srcZoom(z, c.maxZoom); val d = z - sz; val sx = x shr d; val sy = y shr d; val key = "${c.file}/$sz/$sx/$sy"
            val layers = synchronized(tileCache) { tileCache[key] } ?: run {
                val r = reader(c) ?: return@mapNotNull null
                val bytes = runCatching { r.tile(sz, sx, sy) }.getOrNull()
                (if (bytes != null) runCatching { Mvt.decode(bytes) }.getOrDefault(emptyMap()) else emptyMap()).also { synchronized(tileCache) { tileCache[key] = it } }
            }
            CountryRender.Source(layers, sz)
        }
    }
    /** A drawn map tile, or null where no downloaded country covers it (what lies under then shows). */
    fun renderTile(z: Int, x: Int, y: Int, size: Int = 256): android.graphics.Bitmap? {
        val s = sources(z, x, y); if (s.isEmpty()) return null
        return CountryRender.draw(s, z, x, y, size, app?.resources?.displayMetrics?.density ?: 1f)
    }

    // --- on the maps ----------------------------------------------------------------------------------------------
    /** Puts the country map on [mv] (under the pins, routes and your location) or takes it off. */
    fun apply(mv: MapView, on: Boolean) {
        val cur = mv.overlays.firstOrNull { it is CountryOverlay } as CountryOverlay?
        if (on && cur == null) { mv.overlays.add(0, CountryOverlay(mv)); mv.setUseDataConnection(false); mv.invalidate() }
        else if (!on && cur != null) { mv.overlays.remove(cur); cur.onDetach(mv); mv.setUseDataConnection(true); mv.invalidate() }
        else if (on && cur != null && cur.version != version) { mv.overlays.remove(cur); cur.onDetach(mv); mv.overlays.add(0, CountryOverlay(mv)); mv.invalidate() }
    }
    /** Changes whenever the countries on the phone change, so maps redraw. */
    val version: Int get() = _have.value.values.joinToString { it.file }.hashCode()
    fun isOn(mv: MapView) = mv.overlays.any { it is CountryOverlay }

    object Source : BitmapTileSourceBase("RAFIKI country map", 0, 21, 256, ".png", "© OpenStreetMap contributors")

    /** Draws the tiles; a tile outside every downloaded country is left empty. */
    class Module : MapTileModuleProviderBase(2, 40) {
        override fun getName() = "RAFIKI country map"
        override fun getThreadGroupName() = "cmap"
        override fun getUsesDataConnection() = false
        override fun getMinimumZoomLevel() = 0
        override fun getMaximumZoomLevel() = 21
        override fun setTileSource(s: ITileSource?) {}
        override fun getTileLoader(): TileLoader = Loader()
        private inner class Loader : TileLoader() {
            override fun loadTile(i: Long): Drawable? {
                val b = renderTile(MapTileIndex.getZoom(i), MapTileIndex.getX(i), MapTileIndex.getY(i)) ?: return null
                return BitmapDrawable(app?.resources, b)
            }
        }
    }
    class CountryOverlay(mv: MapView) : TilesOverlay(MapTileProviderArray(Source, null, arrayOf<MapTileModuleProviderBase>(Module())), mv.context) {
        val version = CountryMaps.version
        /** The drawn tile if it is ready (asks for it otherwise). */
        fun tile(i: Long): Drawable? = mTileProvider.getMapTile(i)
        init {
            loadingBackgroundColor = android.graphics.Color.TRANSPARENT; loadingLineColor = android.graphics.Color.TRANSPARENT; setLoadingDrawable(null)
            mTileProvider.tileRequestCompleteHandlers.add(mv.tileRequestCompleteHandler) // redraw the map as tiles are ready
        }
    }

    fun mb(b: Long): String = if (b >= 10L * 1048576) "${Math.round(b / 1048576.0)} MB" else "%.1f MB".format(java.util.Locale.US, b / 1048576.0)
}

package org.rafikioutreach.pin

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.rafikioutreach.pin.cmap.CountryMaps
import org.rafikioutreach.pin.cmap.CountryRender
import org.rafikioutreach.pin.cmap.Mvt
import org.rafikioutreach.pin.cmap.MvtFeature
import org.rafikioutreach.pin.cmap.Pmtiles
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.ui.Look
import org.rafikioutreach.pin.ui.MapSetup
import org.rafikioutreach.pin.ui.OfflineMaps
import org.rafikioutreach.pin.ui.RafikiTheme
import org.rafikioutreach.pin.ui.TileMath
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Collections

/**
 * Whole-country street maps: reading the country file (PMTiles + vector tiles), drawing tiles, the download manager
 * (progress, resume after a dropped connection, Stop and Resume, Wi-Fi only, update, delete) and the maps switching
 * to the country file with no signal. The country file is tools/country-maps/test/test-area.pmtiles: a small
 * SYNTHETIC area around Eldoret built with the real pipeline (Planetiler + rafiki-country.yml).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class CountryMapsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val fixture = File(System.getProperty("user.dir"), "../../../tools/country-maps/test/test-area.pmtiles")
    private val data by lazy { fixture.readBytes() }
    private val sha by lazy { MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) } }
    private val asked = Collections.synchronizedList(mutableListOf<Long>())
    private var wifi = true
    private var dropAt = -1L
    private var slowMs = 0L

    private fun kenya(file: String = "kenya-2026-10-20.pmtiles", s: String = sha) = JSONObject().put("code", "ke").put("name", "Kenya").put("file", file).put("date", "2026-10-20")
        .put("bytes", data.size).put("sha256", s).put("bounds", JSONArray().put(34.85).put(0.05).put(35.75).put(1.0)).put("maxzoom", 14)
    private var list = JSONObject()

    /** The file server: range requests from [from]; can break off part way (a dropped connection) or go slowly. */
    private fun body(from: Long): InputStream = object : InputStream() {
        var p = from.toInt()
        override fun read(): Int = if (p >= data.size) -1 else data[p++].toInt() and 0xFF
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (p >= data.size) return -1
            if (dropAt in (from + 1)..p.toLong()) { dropAt = -1; throw IOException("connection reset") }
            if (slowMs > 0) Thread.sleep(slowMs)
            var n = minOf(len, 16 * 1024, data.size - p); if (dropAt > p) n = minOf(n, (dropAt - p).toInt())
            System.arraycopy(data, p, b, off, n); p += n; return n
        }
    }

    @Before fun fresh() {
        MapSetup.init(app)
        OfflineMaps.configure(app, org.osmdroid.config.Configuration.getInstance())
        OfflineMaps.setOnline(true)
        CountryMaps.stop()
        CountryMaps.configure(app)
        CountryMaps.dir().listFiles()?.forEach { it.delete() }
        CountryMaps.configure(app)
        CountryMaps.pick(false); CountryMaps.mobileData = false
        CountryMaps.baseFrom(JSONObject().put("base", "https://maps.rafiki.example/"))
        list = JSONObject().put("countries", JSONArray().put(kenya())
            .put(JSONObject().put("code", "ug").put("name", "Uganda").put("file", "uganda-2026-10-20.pmtiles").put("date", "2026-10-20").put("bytes", 104857600)))
        CountryMaps.getText = { u -> if (u == "https://maps.rafiki.example/countries.json") list.toString() else null }
        CountryMaps.http = { u, from, _ ->
            assertTrue(u, u.startsWith("https://maps.rafiki.example/")); asked.add(from)
            if (u.endsWith("/kenya-2026-10-20.pmtiles") || u.endsWith("/kenya-2026-11-01.pmtiles")) CountryMaps.Reply(206, "\"v1\"", from, body(from)) else CountryMaps.Reply(404, "", -1, null)
        }
        CountryMaps.unmetered = { wifi }
        CountryMaps.freeBytes = { 8L shl 30 }
        CountryMaps.retryMs = { 50L }
    }
    @After fun reset() { CountryMaps.stop(); Lang.sw = false; Look.mode = ""; OfflineMaps.setOnline(true) }

    private fun waitFor(ms: Long = 15000, what: () -> Boolean) { val end = System.currentTimeMillis() + ms; while (!what()) { assertTrue("timed out", System.currentTimeMillis() < end); Thread.sleep(20) } }
    private fun ke() = CountryMaps.refreshList().first { it.code == "ke" }
    private fun downloadKenya() { assertNull(CountryMaps.start(ke())); waitFor { CountryMaps.have.value.containsKey("ke") } }

    // --- reading and drawing ------------------------------------------------------------------------------------
    @Test fun reads_the_country_file() {
        assertTrue("fixture missing: $fixture", fixture.exists())
        // tile numbers along the Hilbert curve, as the PMTiles spec counts them
        assertEquals(0L, Pmtiles.tileId(0, 0, 0))
        assertEquals(listOf(1L, 2L, 3L, 4L), listOf(Pmtiles.tileId(1, 0, 0), Pmtiles.tileId(1, 0, 1), Pmtiles.tileId(1, 1, 1), Pmtiles.tileId(1, 1, 0)))
        assertEquals(5L, Pmtiles.tileId(2, 0, 0)); assertEquals(20L, Pmtiles.tileId(2, 3, 0))
        Pmtiles(fixture).use { p ->
            assertEquals(14, p.header.maxZoom); assertEquals(0, p.header.minZoom); assertEquals(1, p.header.type) // vector tiles
            assertEquals(34.85, p.header.west, 1e-6); assertEquals(1.0, p.header.north, 1e-6)
            val x = TileMath.x(35.2698, 14); val y = TileMath.y(0.5143, 14)
            val t = Mvt.decode(p.tile(14, x, y)!!)
            assertTrue(t.keys.toString(), "roads" in t)
            assertTrue(t.getValue("roads").features.any { it.type == MvtFeature.LINE && it.str("kind") != null })
            // Eldoret's name, and villages, a few zooms out
            val z10 = Mvt.decode(p.tile(10, TileMath.x(35.27, 10), TileMath.y(0.51, 10))!!)
            val names = z10.getValue("places").features.mapNotNull { it.str("name") }
            assertTrue(names.toString(), "Eldoret" in names && "Kapsaret" in names)
            // schools, clinics, churches and water points at zoom 14
            val kinds = (0..3).flatMap { dx -> (0..3).flatMap { dy -> p.tile(14, x - 2 + dx, y - 2 + dy)?.let { b -> Mvt.decode(b)["pois"]?.features?.mapNotNull { it.str("kind") } } ?: emptyList() } }.toSet()
            assertTrue(kinds.toString(), kinds.containsAll(listOf("school", "health", "worship", "water")))
            assertNull("nothing outside the area", p.tile(14, TileMath.x(10.0, 14), TileMath.y(50.0, 14)))
        }
    }

    @Test fun draws_map_tiles_and_closer_than_zoom_14() {
        downloadKenya()
        // nothing downloaded there: no tile, so the usual map shows through
        assertNull(CountryMaps.renderTile(13, TileMath.x(10.0, 13), TileMath.y(50.0, 13)))
        val tiles = mutableListOf<Bitmap>()
        for (z in listOf(10, 13, 16)) {
            val cx = TileMath.x(35.2698, z); val cy = TileMath.y(0.5143, z)
            // a 3 × 3 block of tiles around Eldoret, as on the map
            val b = Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888); val c = Canvas(b)
            for (dx in -1..1) for (dy in -1..1) c.drawBitmap(CountryMaps.renderTile(z, cx + dx, cy + dy)!!, (dx + 1) * 256f, (dy + 1) * 256f, null)
            File(out, "cmap-render-z$z.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tiles += b
        }
        for (b in tiles) {
            val colours = HashSet<Int>(); for (i in 0 until 768 step 3) for (j in 0 until 768 step 3) colours.add(b.getPixel(i, j))
            assertTrue("a real drawing, not a blank tile: ${colours.size} colours", colours.size > 40)
        }
        val z10 = tiles[0]; assertTrue("land colour is the background", (0 until 768 step 4).any { i -> (0 until 768 step 4).any { j -> z10.getPixel(i, j) == CountryRender.BG } })
        // the water point, school and clinic colours are on the zoom-16 tiles
        val z16 = tiles[2]; val seen = HashSet<Int>(); for (i in 0 until 768) for (j in 0 until 768) seen.add(z16.getPixel(i, j))
        assertTrue("road colours drawn", seen.contains(0xFFF4A259.toInt()) || seen.contains(0xFFFFFFFF.toInt()))
    }

    // --- downloading ------------------------------------------------------------------------------------------
    @Test fun downloads_with_progress_into_the_apps_files() {
        slowMs = 90
        val seen = Collections.synchronizedList(mutableListOf<Long>())
        assertNull(CountryMaps.start(ke()))
        waitFor { CountryMaps.download.value?.let { seen.add(it.done) }; CountryMaps.have.value.containsKey("ke") }
        val f = File(app.filesDir, "countrymaps/kenya-2026-10-20.pmtiles")
        assertTrue("in the app's own files, not the cache", f.exists() && !f.path.startsWith(app.cacheDir.path))
        assertArrayEquals(data, f.readBytes())
        assertTrue("progress went up in steps: $seen", seen.distinct().size >= 3 && seen.zipWithNext().all { (a, b) -> b >= a })
        assertNull(CountryMaps.download.value)
        assertTrue(CountryMaps.ready)
        assertEquals("Kenya street map saved ✓ It works with no signal.", CountryMaps.note.value)
        assertEquals(listOf(0L), asked.toList())
    }

    @Test fun carries_on_after_a_dropped_connection() {
        dropAt = 70_000
        downloadKenya()
        assertArrayEquals(data, File(CountryMaps.dir(), "kenya-2026-10-20.pmtiles").readBytes())
        assertEquals("asked twice: from the start, then from where it broke off", 2, asked.size)
        assertEquals(0L, asked[0]); assertEquals(70_000L, asked[1])
    }

    @Test fun stop_keeps_the_part_and_resume_carries_on() {
        slowMs = 40
        assertNull(CountryMaps.start(ke()))
        waitFor { (CountryMaps.download.value?.done ?: 0) > 40_000 }
        CountryMaps.stop()
        Thread.sleep(200)
        val part = File(CountryMaps.dir(), "kenya-2026-10-20.pmtiles.part")
        val kept = part.length()
        assertTrue("part of it is kept: $kept", kept in 40_000 until data.size)
        assertNull(CountryMaps.download.value); assertFalse(CountryMaps.ready)
        assertEquals(kept, CountryMaps.partBytes(ke()))
        slowMs = 0
        downloadKenya()
        assertEquals("Resume asks only for the rest", kept, asked.last())
        assertArrayEquals(data, File(CountryMaps.dir(), "kenya-2026-10-20.pmtiles").readBytes())
    }

    @Test fun wifi_only_unless_mobile_data_is_allowed() {
        wifi = false
        assertNull(CountryMaps.start(ke()))
        waitFor { CountryMaps.download.value?.state == "wifi" }
        Thread.sleep(300)
        assertTrue("nothing downloads on mobile data", asked.isEmpty())
        CountryMaps.mobileData = true
        waitFor { CountryMaps.have.value.containsKey("ke") }
        assertEquals(1, asked.size)
        // Wi-Fi back and mobile data off again: an update waits for Wi-Fi as well, and goes once it is back
        CountryMaps.mobileData = false; wifi = true
    }

    @Test fun no_signal_waits_and_goes_when_it_comes_back() {
        OfflineMaps.setOnline(false)
        assertNull(CountryMaps.start(ke()))
        waitFor { CountryMaps.download.value?.state == "wait" }
        Thread.sleep(200); assertTrue(asked.isEmpty())
        OfflineMaps.setOnline(true)
        waitFor { CountryMaps.have.value.containsKey("ke") }
    }

    @Test fun update_replaces_the_old_file_and_delete_removes_it() {
        downloadKenya()
        val old = File(CountryMaps.dir(), "kenya-2026-10-20.pmtiles"); assertTrue(old.exists())
        list = JSONObject().put("countries", JSONArray().put(kenya("kenya-2026-11-01.pmtiles")))
        CountryMaps.refreshList()
        val nw = CountryMaps.newer("ke"); assertNotNull(nw); assertEquals("kenya-2026-11-01.pmtiles", nw!!.file)
        assertNull(CountryMaps.start(nw))
        waitFor { CountryMaps.have.value["ke"]?.file == "kenya-2026-11-01.pmtiles" }
        assertFalse("the old file goes once the new one is in", old.exists())
        assertNull(CountryMaps.newer("ke"))
        CountryMaps.delete("ke")
        assertFalse(CountryMaps.ready)
        assertTrue("nothing left on the phone", CountryMaps.dir().listFiles()!!.isEmpty())
        // and it stays deleted after the app starts again
        CountryMaps.configure(app); assertFalse(CountryMaps.ready)
    }

    @Test fun a_damaged_download_is_not_kept() {
        list = JSONObject().put("countries", JSONArray().put(kenya(s = "0".repeat(64))))
        assertNull(CountryMaps.start(ke()))
        waitFor { CountryMaps.download.value?.state == "err" }
        assertFalse(CountryMaps.ready)
        assertFalse(File(CountryMaps.dir(), "kenya-2026-10-20.pmtiles").exists())
        assertTrue(CountryMaps.download.value!!.msg.contains("damaged"))
    }

    @Test fun not_enough_room_says_so() {
        CountryMaps.freeBytes = { 1000L }
        val why = CountryMaps.start(ke())
        assertTrue(why, why!!.startsWith("Not enough free space for Kenya"))
        assertTrue(asked.isEmpty())
    }

    @Test fun only_rafikis_own_server() {
        CountryMaps.baseFrom(null); assertNull(CountryMaps.base.value)
        CountryMaps.baseFrom(JSONObject().put("base", "https://tile.openstreetmap.org/")); assertNull(CountryMaps.base.value)
        CountryMaps.baseFrom(JSONObject().put("base", "http://maps.example.org/")); assertNull(CountryMaps.base.value)
        CountryMaps.baseFrom(JSONObject().put("base", "/api/cmap/")); assertEquals(BuildConfig.SERVER + "/api/cmap/", CountryMaps.base.value)
        CountryMaps.baseFrom(JSONObject().put("base", "https://pub-1.r2.dev/maps")); assertEquals("https://pub-1.r2.dev/maps/", CountryMaps.base.value)
    }

    // --- on the map and in Offline maps -----------------------------------------------------------------------
    private fun proj(id: String, t: String, lat: Double, lng: Double) = JSONObject().put("id", id).put("title", t).put("country", "Kenya").put("ck", "ke").put("area", "Eldoret").put("type", "Borehole well")
        .put("status", "Completed").put("gps", JSONArray().put(lat).put(lng))
    private fun snapshot() = JSONObject().put("ok", true).put("who", "Meril Kiprop").put("admin", false).put("reviewOpen", 0)
        .put("projects", JSONArray().put(proj("204", "Kapsaret Borehole", 0.47, 35.25)).put(proj("101", "Chepkongi Dispensary Well", 0.5143, 35.2698)).put(proj("107", "Moiben Water Kiosk", 0.56, 35.33)))
        .put("repairs", JSONObject()).put("visits", JSONObject()).put("jobs", JSONArray()).put("assigned", JSONArray()).put("confirmed", JSONObject())
        .put("countryMaps", JSONObject().put("base", "https://maps.rafiki.example/"))
    private fun model(): AppModel {
        org.rafikioutreach.pin.ui.Onb.enabled = false
        org.rafikioutreach.pin.ui.Motion.on = false
        Prefs(app).apply { pin = "1111"; name = "Meril Kiprop"; saveSnapshot(snapshot()); filter = "all"; country = ""; lang = "en" }
        org.rafikioutreach.pin.ui.clearDrafts()
        val m = AppModel(app)
        m.api.transport = { method, url, _, _ -> if (method == "GET" && url.contains("pin-auth")) 200 to "{\"available\":false}" else if (method == "GET" && url.contains("activity=")) 200 to "{\"ok\":true,\"activity\":[]}" else 200 to snapshot().toString() }
        return m
    }
    private fun show(m: AppModel, dark: Boolean = false) = rule.setContent { RafikiTheme(dark = dark) { Box(Modifier.size(390.dp, 844.dp)) { App(m) } } }
    private fun bitmap(): Bitmap { rule.waitForIdle(); val v = rule.activity.window.decorView; return Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) } }
    private fun shot(name: String) = File(out, "$name.png").outputStream().use { bitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    private fun has(t: String) = rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun findMap(v: android.view.View): org.osmdroid.views.MapView? {
        if (v is org.osmdroid.views.MapView) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findMap(v.getChildAt(i))?.let { return it }
        return null
    }
    private fun credit() = rule.onAllNodesWithContentDescription("osm_credit").fetchSemanticsNodes().isNotEmpty()

    @Test fun the_map_switches_to_the_country_file_with_no_signal() {
        downloadKenya()
        val m = model(); show(m)
        rule.waitUntil(8000) { rule.onAllNodesWithTag("nav_map").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav_map").performClick(); rule.waitForIdle(); rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        val mv = findMap(rule.activity.window.decorView)!!
        assertFalse("with signal: the usual map", CountryMaps.isOn(mv)); assertFalse(credit())
        OfflineMaps.setOnline(false); rule.waitForIdle()
        assertTrue("no signal: the country map", CountryMaps.isOn(mv)); assertTrue("with the OpenStreetMap credit", credit())
        // under the project dots and your location: the country map is the first overlay, the dots come after it
        assertTrue(mv.overlays.first() is CountryMaps.CountryOverlay)
        assertTrue(mv.overlays.drop(1).count { it is org.osmdroid.views.overlay.Marker } >= 3)
        rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        rule.runOnUiThread { mv.controller.setZoom(14.0); mv.controller.setCenter(org.osmdroid.util.GeoPoint(0.505, 35.268)) }
        rule.waitForIdle()
        // the overlay's own tile provider draws the tiles from the file (on its own threads)
        val ov = mv.overlays.first() as CountryMaps.CountryOverlay
        val idx = org.osmdroid.util.MapTileIndex.getTileIndex(14, TileMath.x(35.268, 14), TileMath.y(0.505, 14))
        val end = System.currentTimeMillis() + 10000
        while (ov.tile(idx) == null && System.currentTimeMillis() < end) { Thread.sleep(50); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
        assertNotNull("the map gets drawn tiles from the country file", ov.tile(idx))
        repeat(30) { Thread.sleep(60); rule.runOnUiThread { mv.invalidate() }; org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
        shot("cmap-map-no-signal")
        OfflineMaps.setOnline(true); rule.waitForIdle()
        assertFalse("signal back: the usual map again", CountryMaps.isOn(mv))
        // picked as the map style: on even with signal
        rule.onAllNodesWithContentDescription("Map style")[0].performClick(); rule.mainClock.advanceTimeBy(700); rule.waitForIdle()
        rule.waitUntil(5000) { has("Offline street map") }
        run {
            val b = bitmap(); val c = Canvas(b); c.drawColor(0x66000000)
            val d = org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window?.decorView
            if (d != null && d.width > 0) { c.save(); c.translate(0f, (b.height - d.height).toFloat().coerceAtLeast(0f)); d.draw(c); c.restore() }
            File(out, "cmap-map-style.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        rule.onAllNodesWithText("Offline street map")[0].performClick(); rule.mainClock.advanceTimeBy(700); rule.waitForIdle()
        assertTrue(CountryMaps.picked.value)
        assertTrue(CountryMaps.isOn(mv)); assertTrue(credit())
        CountryMaps.pick(false); rule.waitForIdle()
        assertFalse(CountryMaps.isOn(mv))
        // deleted: never on
        CountryMaps.delete("ke"); OfflineMaps.setOnline(false); rule.waitForIdle()
        assertFalse(CountryMaps.isOn(mv))
    }

    /** The map style sheet in Kiswahili: the three map styles, then the home map's layer switches (MapLayers.kt). */
    private fun styleSheetSw(dark: Boolean) {
        downloadKenya(); Lang.sw = true
        val m = model(); show(m, dark)
        rule.waitUntil(8000) { rule.onAllNodesWithTag("nav_map").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav_map").performClick(); rule.waitForIdle(); rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        rule.onAllNodesWithContentDescription("Mtindo wa ramani")[0].performClick(); rule.mainClock.advanceTimeBy(700); rule.waitForIdle()
        rule.waitUntil(5000) { has("Ramani ya barabara bila mtandao") }
        assertTrue("the layer switches under the styles", has("Njia ya leo") && rule.onAllNodesWithTag("ml_sw_route").fetchSemanticsNodes().isNotEmpty())
        val b = bitmap(); val c = Canvas(b); c.drawColor(0x66000000)
        val d = org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window?.decorView
        if (d != null && d.width > 0) { c.save(); c.translate(0f, (b.height - d.height).toFloat().coerceAtLeast(0f)); d.draw(c); c.restore() }
        File(out, "cmap-map-style-layers-sw" + (if (dark) "-dark" else "") + ".png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        rule.onAllNodesWithText("Ramani ya barabara bila mtandao")[0].performClick(); rule.mainClock.advanceTimeBy(700); rule.waitForIdle()
        val mv = findMap(rule.activity.window.decorView)!!
        assertTrue(CountryMaps.isOn(mv)); assertTrue("the OpenStreetMap credit shows", credit())
        CountryMaps.pick(false); rule.waitForIdle()
    }
    @Test fun map_style_sheet_with_layers_kiswahili() = styleSheetSw(false)
    @Test fun map_style_sheet_with_layers_kiswahili_dark() = styleSheetSw(true)

    private fun openOffline() {
        rule.waitUntil(8000) { rule.onAllNodesWithTag("nav_map").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav_map").performClick(); rule.waitForIdle(); rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        rule.onNodeWithTag("ml_more").performClick(); rule.waitForIdle(); rule.onNodeWithTag("offline_btn").performClick(); rule.mainClock.advanceTimeBy(700); rule.waitForIdle()
        rule.onNodeWithTag("oa_list").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(700); rule.waitForIdle()
        rule.waitUntil(8000) { rule.onAllNodesWithTag("cm_section").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun offline_maps_screen_lists_downloads_and_deletes_countries() {
        slowMs = 250
        val m = model(); show(m); openOffline()
        rule.waitUntil(8000) { has("Uganda") }
        assertTrue(has("Whole country maps") && has("Kenya") && has("100 MB") && has("Free space on this phone"))
        assertTrue("Wi-Fi only by default", has("Off: country maps download only on Wi-Fi."))
        rule.onNodeWithTag("cm_section").performScrollTo(); shot("cmap-offline-list")
        rule.onNodeWithTag("cm_get_ke").performScrollTo().performClick()
        rule.waitUntil(8000) { has("Downloading…") }
        rule.onNodeWithTag("cm_stop_ke").performScrollTo(); shot("cmap-offline-downloading")
        rule.onNodeWithTag("cm_stop_ke").performClick(); rule.waitForIdle()
        rule.waitUntil(5000) { has("Stopped part way") && has("Resume") }
        shot("cmap-offline-stopped")
        slowMs = 0
        rule.onNodeWithTag("cm_get_ke").performScrollTo().performClick()
        rule.waitUntil(15000) { has("On this phone") }
        assertTrue(CountryMaps.ready)
        rule.onNodeWithTag("cm_row_ke").performScrollTo(); shot("cmap-offline-downloaded")
        Lang.sw = true; rule.waitForIdle()
        assertTrue(has("Ramani za nchi nzima") && has("Kwenye simu hii") && has("Tumia data ya simu pia"))
        shot("cmap-offline-sw")
        Lang.sw = false; rule.waitForIdle()
        rule.onNodeWithTag("cm_del_ke").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("cm_del_yes").performClick(); rule.waitForIdle()
        assertFalse(CountryMaps.ready)
        rule.waitUntil(5000) { rule.onAllNodesWithTag("cm_get_ke").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun offline_maps_screen_dark() {
        downloadKenya(); Look.mode = "dark"
        val m = model(); show(m, dark = true); openOffline(); rule.waitUntil(8000) { has("Uganda") }
        rule.onNodeWithTag("cm_section").performScrollTo(); shot("cmap-offline-dark")
        assertTrue(has("On this phone"))
    }

    @Test fun offline_maps_screen_sun() {
        downloadKenya(); Look.mode = "sun"; Lang.sw = true
        val m = model(); show(m); openOffline(); rule.waitUntil(8000) { has("Uganda") }
        rule.onNodeWithTag("cm_section").performScrollTo(); shot("cmap-offline-sun-sw")
        assertTrue(has("Pakua"))
    }

    @Test fun hidden_until_the_office_sets_it_up() {
        CountryMaps.baseFrom(null)
        val m = model()
        Prefs(app).saveSnapshot(snapshot().put("countryMaps", JSONObject.NULL))
        m.api.transport = { _, url, _, _ -> if (url.contains("pin-auth")) 200 to "{\"available\":false}" else 200 to snapshot().put("countryMaps", JSONObject.NULL).toString() }
        show(m)
        rule.waitUntil(8000) { rule.onAllNodesWithTag("nav_map").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav_map").performClick(); rule.waitForIdle(); rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        rule.onNodeWithTag("ml_more").performClick(); rule.waitForIdle(); rule.onNodeWithTag("offline_btn").performClick(); rule.mainClock.advanceTimeBy(700); rule.waitForIdle()
        rule.onNodeWithTag("oa_list").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(700); rule.waitForIdle()
        rule.waitUntil(5000) { has("Saved areas") }
        assertTrue(rule.onAllNodesWithTag("cm_section").fetchSemanticsNodes().isEmpty())
        val mv = findMap(rule.activity.window.decorView)
        OfflineMaps.setOnline(false); rule.waitForIdle()
        if (mv != null) assertFalse(CountryMaps.isOn(mv))
    }
}

package org.rafikioutreach.pin

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.MapTileIndex
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.data.Project
import org.rafikioutreach.pin.ui.GoogleSource
import org.rafikioutreach.pin.ui.Look
import org.rafikioutreach.pin.ui.MapSetup
import org.rafikioutreach.pin.ui.OfflineMaps
import org.rafikioutreach.pin.ui.QrCheckin
import org.rafikioutreach.pin.ui.QrCode
import org.rafikioutreach.pin.ui.QrStickers
import org.rafikioutreach.pin.ui.RafikiTheme
import org.rafikioutreach.pin.ui.Satellite
import org.rafikioutreach.pin.ui.TileMath
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File

/** Field tools: offline map areas, QR check-in stickers and Scan QR. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class FieldToolsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val posts = mutableListOf<String>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    /** Each test has a fresh app: point the map store at it (MapSetup only runs once per test run). */
    @Before fun fresh() {
        MapSetup.init(app)
        OfflineMaps.configure(app, org.osmdroid.config.Configuration.getInstance())
        SqlTileWriter().refreshDb()
        OfflineMaps.setOnline(true)
    }
    @After fun reset() { org.rafikioutreach.pin.ui.Motion.on = true; Lang.sw = false; Look.mode = "" }

    private fun proj(id: String, t: String, area: String, lat: Double, lng: Double, type: String = "Borehole well", ck: String = "ke", country: String = "Kenya") =
        JSONObject().put("id", id).put("title", t).put("country", country).put("ck", ck).put("area", area).put("type", type).put("status", "Completed").put("pump", "manual").put("gps", JSONArray().put(lat).put(lng))
    private fun snapshot(admin: Boolean) = JSONObject().put("ok", true).put("who", "Meril Kiprop").put("admin", admin).put("reviewOpen", 0)
        .put("projects", JSONArray().put(proj("204", "Kapsaret Borehole", "Eldoret", 0.47, 35.25)).put(proj("101", "Chepkongi Dispensary Well", "Eldoret", 0.5143, 35.2698))
            .put(proj("105", "Turbo Spring", "Turbo", 0.63, 35.05, "Water collection")).put(proj("311", "Nzoia Bridge Primary School", "Bungoma", 0.58, 34.62, "School"))
            .put(proj("402", "Mto wa Mbu Borehole", "Arusha", -3.37, 35.85, ck = "tz", country = "Tanzania")))
        .put("repairs", JSONObject()).put("visits", JSONObject()).put("jobs", JSONArray()).put("assigned", JSONArray()).put("confirmed", JSONObject())

    private fun model(admin: Boolean = false): AppModel {
        org.rafikioutreach.pin.ui.Onb.enabled = false
        org.rafikioutreach.pin.ui.Motion.on = false
        Prefs(app).apply { pin = "1111"; name = "Meril Kiprop"; saveSnapshot(snapshot(admin)); filter = "all"; country = ""; lang = "en" }
        org.rafikioutreach.pin.ui.clearDrafts()
        val m = AppModel(app)
        m.api.transport = { method, url, _, body ->
            posts += "$method $url ${body?.let { String(it).take(4000) } ?: ""}"
            when {
                url.contains("history=") -> 200 to "{\"ok\":true,\"notes\":[]}"
                url.contains("pin-auth/me") -> 200 to "{\"ok\":true,\"available\":false}"
                url.contains("activity=1") -> 200 to "{\"ok\":true,\"activity\":[]}"
                method == "GET" -> 200 to snapshot(admin).toString()
                else -> 200 to "{\"ok\":true,\"updateId\":\"9001\"}"
            }
        }
        return m
    }
    private fun show(m: AppModel, dark: Boolean = false) = rule.setContent { RafikiTheme(dark = dark) { Box(Modifier.size(390.dp, 844.dp)) { App(m) } } }
    private fun bitmap(): Bitmap {
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        return Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
    }
    private fun save(b: Bitmap, name: String) = File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    private fun shot(name: String) = save(bitmap(), name)
    /** A sheet lives in its own window: draw it over the dimmed screen, the way it looks on the phone. */
    private fun sheetShot(tag: String, name: String) {
        rule.mainClock.advanceTimeBy(700); rule.waitForIdle()
        rule.onNodeWithTag(tag).assertExists()
        val b = bitmap(); val c = Canvas(b); c.drawColor(0x66000000)
        val d = org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window?.decorView
        if (d != null && d.width > 0) { c.save(); c.translate(0f, (b.height - d.height).toFloat().coerceAtLeast(0f)); d.draw(c); c.restore() }
        save(b, name)
    }
    private fun has(t: String) = rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(t: String, ms: Long = 6000) = rule.waitUntil(ms) { has(t) }
    private fun findMap(v: android.view.View): org.osmdroid.views.MapView? {
        if (v is org.osmdroid.views.MapView) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findMap(v.getChildAt(i))?.let { return it }
        return null
    }
    private fun project(id: String) = Project(id, "Kapsaret Borehole", "Kenya", "ke", "Eldoret", "Borehole well", "Completed", null, null, null, "")

    @Test fun qr_codes_are_made_and_read_back() {
        assertEquals("204", QrCheckin.parse("https://pin.rafikioutreach.org/pin#p=204"))
        assertEquals("204", QrCheckin.parse("https://rafiki-redesign.pages.dev/pin#p/204/visit"))
        assertEquals("1234567890", QrCheckin.parse(" 1234567890 "))
        assertNull(QrCheckin.parse("https://example.com/hello")); assertNull(QrCheckin.parse(""))
        assertEquals("https://pin.rafikioutreach.org/pin#p=204", QrCheckin.url("204"))
        // a phone camera opening the sticker's address
        assertTrue(QrCheckin.fromLink(android.net.Uri.parse("https://pin.rafikioutreach.org/pin#p=204"))); assertEquals("204", QrCheckin.openReq); QrCheckin.openReq = null
        assertFalse(QrCheckin.fromLink(android.net.Uri.parse("https://pin.rafikioutreach.org/pin?signin=ok")))
        // what the code holds comes back out of a picture of it
        assertEquals(QrCheckin.url("204"), QrCode.decode(QrCode.bitmap(QrCheckin.url("204"), 6, 4)))
        // the A4 sheet: 12 stickers a page; every sticker on the printed page scans
        val list = (1..14).map { project((1000 + it).toString()).copy(title = if (it == 2) "Nzoia Bridge Primary School and Community Water Kiosk" else "Kapsaret Borehole $it") }
        val k = 2f
        val page = Bitmap.createBitmap((595 * k).toInt(), (842 * k).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(page); c.scale(k, k)
        QrStickers.drawPage(app, c, list.take(12), 1, 2)
        save(page, "field-10-qr-sheet")
        val cw = (595f - 56f) / 3 * k; val ch = (842f - 74f) / 4 * k
        for (i in listOf(0, 4, 11)) {
            val cell = Bitmap.createBitmap(page, ((28 + (i % 3) * (595f - 56f) / 3) * k).toInt(), ((34 + (i / 3) * (842f - 74f) / 4) * k).toInt(), cw.toInt(), ch.toInt())
            assertEquals(QrCheckin.url(list[i].id), QrCode.decode(cell))
        }
    }

    @Test fun office_prints_qr_stickers() {
        Lang.sw = false
        val m = model(admin = true); show(m)
        rule.waitUntil(8000) { rule.onAllNodesWithTag("nav_office").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav_office").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("o_qr").performScrollTo().performClick(); rule.waitForIdle()
        assertTrue(has("QR check-in stickers") && has("5 of 5 chosen"))
        shot("field-11-office-qr")
        rule.onNodeWithTag("qr_ck_tz").performClick(); rule.waitForIdle()
        assertTrue(has("1 of 1 chosen") && has("Mto wa Mbu Borehole"))
        rule.onNodeWithTag("qr_ck_").performClick(); rule.onNodeWithTag("qr_none").performScrollTo().performClick(); rule.waitForIdle()
        assertTrue(has("0 of 5 chosen"))
        rule.onNodeWithTag("qr_p_204").performScrollTo().performClick(); rule.onNodeWithTag("qr_p_105").performScrollTo().performClick(); rule.waitForIdle()
        assertTrue(has("2 of 5 chosen") && has("Share PDF to print (2 stickers, 1 A4)"))
        rule.onNodeWithTag("qr_sticker").performScrollTo(); shot("field-12-office-qr-sticker")
        Lang.sw = true; rule.waitForIdle()
        assertTrue(has("Vibandiko vya QR vya kuingia") && has("Shiriki PDF ya kuchapisha"))
        Lang.sw = false
    }

    @Test fun scan_qr_opens_the_visit_and_marks_it() {
        Lang.sw = false
        val m = model(); show(m)
        var next: String? = "https://example.com/not-ours"
        QrCheckin.scanner = { _, ok, _ -> ok(next) }
        rule.waitUntil(8000) { rule.onAllNodesWithTag("quick_scan").fetchSemanticsNodes().isNotEmpty() }
        shot("field-01-home-scan-qr")
        rule.onNodeWithTag("quick_scan").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("scan_cam").performScrollTo().performClick(); rule.waitForIdle()
        assertTrue(has("isn’t a RAFIKI project sticker"))
        next = QrCheckin.url("999")
        rule.onNodeWithTag("scan_cam").performScrollTo().performClick(); rule.waitForIdle()
        assertTrue(has("Project 999 isn’t in your list"))
        shot("field-02-scan")
        // no Google Play services: says so and offers a photo instead
        QrCheckin.scanner = { _, _, fail -> fail("no play services") }
        rule.onNodeWithTag("scan_cam").performScrollTo().performClick(); rule.waitForIdle()
        assertTrue(has("Take a photo of the code instead"))
        next = QrCheckin.url("204"); QrCheckin.scanner = { _, ok, _ -> ok(next) }
        rule.onNodeWithTag("scan_cam").performScrollTo().performClick()
        waitFor("Checked in by scanning the site’s QR code")
        assertTrue(has("Kapsaret Borehole") && has("Arrived"))
        shot("field-03-visit-checked-in-by-qr")
        rule.onNodeWithText("Next").performScrollTo().performClick()
        rule.onNodeWithText("Working well").performScrollTo().performClick()
        repeat(5) { rule.onNodeWithText("Next").performScrollTo().performClick() }
        rule.onNodeWithText("Send visit report").performScrollTo().performClick()
        rule.waitUntil(6000) { posts.any { it.contains("\"action\":\"visit\"") } }
        val p = posts.first { it.contains("\"action\":\"visit\"") }
        assertTrue(p, p.contains("\"qr\":true") && p.contains("\"id\":\"204\"") && p.contains("\"arrive\""))
        rule.waitUntil(5000) { !QrCheckin.isFor("204") }
    }

    @Test fun typed_number_and_phone_camera_link() {
        Lang.sw = true
        Look.mode = "sun"
        val m = model(); show(m)
        rule.waitUntil(8000) { rule.onAllNodesWithTag("quick_scan").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("quick_scan").performClick(); rule.waitForIdle()
        assertTrue(has("Ingia kwa QR") && has("Changanua kwa kamera"))
        shot("field-04-scan-sw-sun")
        // typing the number opens the visit, but not "checked in by QR"
        rule.onNode(androidx.compose.ui.test.hasSetTextAction() and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("scan_id"))).performScrollTo().performTextInput("105")
        rule.onNodeWithTag("scan_open").performScrollTo().performClick(); rule.waitForIdle()
        rule.waitUntil(5000) { has("Turbo Spring") }
        assertFalse("badge shown for a typed number", has("Umeingia kwa kuchanganua"))
        // a sticker scanned with the phone's own camera opens the app on that visit
        QrCheckin.fromLink(android.net.Uri.parse("https://pin.rafikioutreach.org/pin#p=204"))
        waitFor("Umeingia kwa kuchanganua msimbo wa QR")
        assertTrue(has("Kapsaret Borehole"))
        Look.mode = ""; Lang.sw = false
        QrCheckin.clear("204")
    }

    @Test fun offline_area_save_fill_list_and_delete() {
        Lang.sw = false
        OfflineMaps.officeFrom(null)
        val m = model(); show(m)
        rule.waitUntil(8000) { rule.onAllNodesWithTag("nav_map").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav_map").performClick(); rule.waitForIdle()
        rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        val mv = findMap(rule.activity.window.decorView)!!
        rule.runOnUiThread { mv.controller.setZoom(12.0); mv.controller.setCenter(org.osmdroid.util.GeoPoint(0.5, 35.27)) }
        rule.waitForIdle()
        val b = mv.boundingBox
        val zs = OfflineMaps.zoomsFor(b.latNorth, b.latSouth, b.lonEast, b.lonWest, false)!!
        // as if the officer had already looked over part of it: tiles kept in the map store
        val w = SqlTileWriter()
        for (z in zs.first..zs.first + 2) { val r = TileMath.rect(b.latNorth, b.latSouth, b.lonEast, b.lonWest, z); for (x in r.left..r.right) for (y in r.top..r.bottom) w.saveFile(TileSourceFactory.MAPNIK, MapTileIndex.getTileIndex(z, x, y), ByteArrayInputStream(ByteArray(2000)), System.currentTimeMillis() + 86_400_000L) }
        rule.onNodeWithTag("offline_btn").performClick(); rule.waitForIdle()
        assertTrue(has("Save this area for offline") && has("map tiles") && has("Zoom ${zs.first}–${zs.last}"))
        rule.onNodeWithTag("oa_name").performScrollTo()
        sheetShot("offline_sheet", "field-05-offline-sheet")
        rule.onNodeWithTag("oa_save").performScrollTo().performClick(); rule.waitForIdle()
        assertEquals(1, OfflineMaps.areas.value.size)
        assertNotNull(OfflineMaps.filling.value)
        rule.waitUntil(8000) { rule.onAllNodesWithTag("offline_pct").fetchSemanticsNodes().isNotEmpty() && !has("Street map 0%") }
        assertTrue(has("Keeping “Eldoret” for offline"))
        // while filling, the free maps show (never Google)
        assertEquals(TileSourceFactory.MAPNIK.name(), mv.tileProvider.tileSource.name())
        rule.mainClock.advanceTimeBy(1000); shot("field-06-offline-filling")
        rule.onNodeWithText("Done").performClick(); rule.waitForIdle()
        assertNull(OfflineMaps.filling.value)
        // the list of saved areas, settings
        rule.onNodeWithTag("offline_btn").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("oa_list").performScrollTo().performClick(); rule.waitForIdle()
        waitFor("of the street map kept")
        assertTrue(has("Eldoret") && has("Saved areas") && has("Storage limit for maps"))
        shot("field-07-offline-list")
        rule.onNodeWithTag("lim_500").performScrollTo().performClick(); rule.waitForIdle()
        assertEquals(500, OfflineMaps.limitMb)
        assertEquals(500L * 1024 * 1024, org.osmdroid.config.Configuration.getInstance().tileFileSystemCacheMaxBytes)
        rule.onNodeWithTag("keep_90").performScrollTo().performClick(); rule.waitForIdle()
        assertEquals(90, OfflineMaps.keepDays)
        rule.onNodeWithTag("area_del_0").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("area_del_yes").performClick(); rule.waitForIdle()
        assertTrue(OfflineMaps.areas.value.isEmpty())
        rule.waitUntil(5000) { !w.exists(TileSourceFactory.MAPNIK.name(), MapTileIndex.getTileIndex(zs.first, TileMath.x(35.27, zs.first), TileMath.y(0.5, zs.first))) }
        OfflineMaps.limitMb = 250; OfflineMaps.keepDays = 30
    }

    @Test fun offline_list_in_dark_colours() {
        Lang.sw = false
        val m = model()
        OfflineMaps.areas.value.forEach { OfflineMaps.delete(it) }
        OfflineMaps.add("Kitale", BoundingBox(1.10, 35.10, 0.90, 34.90), false)
        OfflineMaps.add("Mto wa Mbu", BoundingBox(-3.30, 35.92, -3.44, 35.78), false)
        show(m, dark = true)
        rule.waitUntil(8000) { rule.onAllNodesWithTag("nav_map").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav_map").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("offline_btn").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("oa_list").performScrollTo().performClick(); rule.waitForIdle()
        waitFor("Mto wa Mbu")
        shot("field-08-offline-list-dark")
        Lang.sw = true; rule.waitForIdle()
        assertTrue(has("Ramani bila mtandao") && has("Maeneo yaliyohifadhiwa"))
        Lang.sw = false
        OfflineMaps.areas.value.forEach { OfflineMaps.delete(it) }
    }

    @Test fun tidy_up_keeps_saved_areas_and_follows_google() {
        MapSetup.init(app)
        OfflineMaps.areas.value.forEach { OfflineMaps.delete(it) }
        val area = OfflineMaps.add("Kitale", BoundingBox(1.10, 35.10, 0.90, 34.90), false)!!
        val w = SqlTileWriter(); val now = System.currentTimeMillis(); val day = 86_400_000L
        val inside = MapTileIndex.getTileIndex(13, TileMath.x(35.0, 13), TileMath.y(1.0, 13))
        val outsideOld = MapTileIndex.getTileIndex(13, TileMath.x(36.5, 13), TileMath.y(-1.0, 13))
        val outsideNew = MapTileIndex.getTileIndex(13, TileMath.x(36.6, 13), TileMath.y(-1.0, 13))
        fun put(src: org.osmdroid.tileprovider.tilesource.ITileSource, i: Long, exp: Long) = w.saveFile(src, i, ByteArrayInputStream(ByteArray(500)), exp)
        put(TileSourceFactory.MAPNIK, inside, now - 40 * day); put(Satellite, inside, now - 40 * day)
        put(TileSourceFactory.MAPNIK, outsideOld, now - 40 * day); put(TileSourceFactory.MAPNIK, outsideNew, now - 2 * day)
        val g = GoogleSource("Google Roadmap", "S", "K", 21)
        put(g, inside, now - 3_600_000L); put(g, outsideNew, now + day)
        OfflineMaps.keepDays = 30
        val gone = OfflineMaps.cleanup(now)
        assertTrue(gone >= 1)
        assertTrue(w.exists(TileSourceFactory.MAPNIK.name(), inside) && w.exists(Satellite.name(), inside))
        assertFalse(w.exists(TileSourceFactory.MAPNIK.name(), outsideOld))
        assertTrue(w.exists(TileSourceFactory.MAPNIK.name(), outsideNew))
        assertFalse("Google tiles go when Google's cache time ends, even in a saved area", w.exists(g.name(), inside))
        assertTrue(w.exists(g.name(), outsideNew))
        val cov = OfflineMaps.coverage(area)
        assertTrue(cov.first > 0f && cov.second > 0f)
        OfflineMaps.delete(area)
        rule.waitUntil(5000) { !w.exists(TileSourceFactory.MAPNIK.name(), inside) }
    }

    @Test fun whole_areas_download_only_from_an_office_tile_server() {
        MapSetup.init(app)
        // the public servers that forbid bulk downloads are refused
        OfflineMaps.officeFrom(JSONObject().put("url", "https://tile.openstreetmap.org/{z}/{x}/{y}.png"))
        assertNull(OfflineMaps.office)
        OfflineMaps.officeFrom(JSONObject().put("url", "https://tile.googleapis.com/v1/2dtiles/{z}/{x}/{y}"))
        assertNull(OfflineMaps.office)
        OfflineMaps.officeFrom(JSONObject().put("url", "https://tiles.rafiki.example/{z}/{x}/{y}.png").put("maxZoom", 13).put("attr", "© OpenStreetMap contributors"))
        assertNotNull(OfflineMaps.office)
        assertEquals("https://tiles.rafiki.example/12/2448/2034.png", OfflineMaps.office!!.getTileURLString(MapTileIndex.getTileIndex(12, 2448, 2034)))
        val asked = mutableListOf<String>()
        OfflineMaps.fetch = { u -> synchronized(asked) { asked += u }; 200 to ByteArray(300) { 1 } }
        val a = OfflineMaps.add("Eldoret", BoundingBox(0.56, 35.32, 0.46, 35.22), true)!!
        assertEquals(6, a.minZ); assertEquals(13, a.maxZ)
        OfflineMaps.startDownload(a)
        rule.waitUntil(20000) { OfflineMaps.download.value?.finished == true }
        val d = OfflineMaps.download.value!!
        assertEquals(a.tiles.toInt(), d.done); assertEquals(0, d.failed)
        assertTrue(asked.all { it.startsWith("https://tiles.rafiki.example/") })
        assertTrue(SqlTileWriter().exists(OfflineMaps.OFFICE_NAME, MapTileIndex.getTileIndex(13, TileMath.x(35.27, 13), TileMath.y(0.51, 13))))
        assertEquals(OfflineMaps.OFFICE_NAME, OfflineMaps.street().name())
        OfflineMaps.delete(a); OfflineMaps.officeFrom(null); OfflineMaps.download.value = null
        assertEquals(TileSourceFactory.MAPNIK.name(), OfflineMaps.street().name())
    }
}

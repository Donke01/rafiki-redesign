package org.rafikioutreach.pin

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.rafikioutreach.pin.data.Fix
import org.rafikioutreach.pin.data.LatLng
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.data.Project
import org.rafikioutreach.pin.data.Snapshot
import org.rafikioutreach.pin.ui.HomeLayers
import org.rafikioutreach.pin.ui.Look
import org.rafikioutreach.pin.ui.MapSetup
import org.rafikioutreach.pin.ui.Motion
import org.rafikioutreach.pin.ui.OfflineMaps
import org.rafikioutreach.pin.ui.RafikiTheme
import org.rafikioutreach.pin.ui.dayStops
import org.rafikioutreach.pin.ui.doneBy
import org.rafikioutreach.pin.ui.heatPoints
import org.rafikioutreach.pin.ui.visitedToday
import org.rafikioutreach.pin.ui.yearSpan
import org.rafikioutreach.pin.t
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.LocalDate

/** The home map's layers (MapLayers.kt): today's route, follow me, heat, years and live team. Screenshots in build/shots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class MapLayersTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ago = { min: Long -> Instant.now().minusSeconds(min * 60).toString() }
    private val today = LocalDate.now().toString()

    @Before fun setUp() {
        MapSetup.init(app); OfflineMaps.configure(app, org.osmdroid.config.Configuration.getInstance()); SqlTileWriter().refreshDb(); OfflineMaps.setOnline(true)
        org.rafikioutreach.pin.ui.Onb.enabled = false; Motion.on = true; Motion.loops = false; Lang.sw = false; HomeLayers.reset(); calls.clear()
        app.getSharedPreferences("map_layers", 0).edit().clear().commit()
    }
    @After fun tearDown() { Motion.on = true; Motion.loops = true; Lang.sw = false; Look.mode = ""; HomeLayers.reset() }

    private fun proj(id: String, t: String, area: String, lat: Double, lng: Double, year: Int?, people: Int?, type: String = "Borehole well", status: String = "Completed", ck: String = "ke", country: String = "Kenya") =
        JSONObject().put("id", id).put("title", t).put("country", country).put("ck", ck).put("area", area).put("type", type).put("status", status).put("pump", "")
            .put("year", year ?: JSONObject.NULL).put("people", people ?: JSONObject.NULL).put("gps", JSONArray().put(lat).put(lng))
    private val projects = JSONArray()
        .put(proj("101", "Kapsaret Borehole", "Kapsaret, Eldoret", 0.47, 35.25, 2014, 1200)).put(proj("102", "Chepkongi Dispensary", "Kapsaret, Eldoret", 0.514, 35.27, 2016, 3400, "Health"))
        .put(proj("103", "Turbo Spring", "Turbo", 0.63, 35.05, 2015, 600, "Water collection")).put(proj("104", "Sigowet Church Well", "Sigowet", -0.27, 35.15, 2018, 900))
        .put(proj("105", "Kitale Primary Tank", "Kitale", 1.015, 35.0, 2019, 1500, "School")).put(proj("106", "Kiminini Borehole", "Kitale", 0.9, 34.92, 2020, 2200))
        .put(proj("107", "Bungoma Bridge School", "Bungoma", 0.563, 34.56, 2021, 800, "School")).put(proj("108", "Webuye Well", "Bungoma", 0.61, 34.77, 2022, 1100))
        .put(proj("109", "Moi’s Bridge Kiosk", "Eldoret", 0.87, 35.12, 2023, 700)).put(proj("110", "Ziwa Borehole", "Eldoret", 0.75, 35.3, 2024, 1900))
        .put(proj("112", "Kapcherop Well", "Kapcherop", 1.07, 35.33, null, null, status = "Upcoming")).put(proj("113", "Mbale Borehole", "Mbale", 1.07, 34.18, 2017, 2600, ck = "ug", country = "Uganda"))
    private var visits = JSONObject().put("101", JSONObject().put("at", ago(400 * 1440)).put("by", "Meril").put("state", "working"))
    private var assigned = listOf("102" to "Chepkongi Dispensary", "110" to "Ziwa Borehole", "109" to "Moi’s Bridge Kiosk")
    private fun snapshot(admin: Boolean, who: String) = JSONObject().put("ok", true).put("who", who).put("admin", admin).put("reviewOpen", 0).put("projects", projects)
        .put("repairs", JSONObject().put("104", JSONObject().put("state", "notworking").put("by", "Bertin").put("at", ago(3000)).put("urgency", "urgent").put("t", "Sigowet Church Well"))
            .put("107", JSONObject().put("state", "repair").put("by", "Grace").put("at", ago(9000)).put("t", "Bungoma Bridge School"))
            .put("103", JSONObject().put("state", "repair").put("by", "Grace").put("at", ago(5000)).put("urgency", "urgent").put("t", "Turbo Spring")))
        .put("visits", visits).put("jobs", JSONArray()).put("confirmed", JSONObject()).put("me", JSONObject().put("kind", "app").put("home", "ke"))
        .put("assigned", JSONArray().apply { if (!admin) assigned.forEachIndexed { i, (id, t) -> put(JSONObject().put("aid", "a$i").put("id", id).put("t", t).put("date", today).put("by", "Reid Anderson").put("note", "")) } })
    private val teammap = JSONObject().put("ok", true)
        .put("people", JSONArray().put(JSONObject().put("name", "Bertin Mwangi").put("lat", 0.52).put("lng", 35.28).put("acc", 12).put("at", ago(12)).put("phone", "0722 111 222").put("home", "ke"))
            .put(JSONObject().put("name", "Grace Achieng").put("lat", 0.58).put("lng", 34.6).put("acc", 30).put("at", ago(95)).put("phone", "").put("home", "ke"))
            .put(JSONObject().put("name", "Meril Kiprop").put("lat", 1.0).put("lng", 35.02).put("acc", 8).put("at", ago(3)).put("phone", "+254 712 345 678").put("home", "ke")))
        .put("visits", JSONArray())

    private fun model(admin: Boolean, who: String = if (admin) "Reid Anderson" else "Meril Kiprop"): AppModel {
        Prefs(app).apply { pin = "9999"; name = who; saveSnapshot(snapshot(admin, who)); filter = "all"; country = ""; lang = "en" }
        val m = AppModel(app)
        m.api.transport = { method, url, _, body ->
            calls += "$method $url"
            when {
                url.contains("view=teammap") -> if (admin) 200 to teammap.toString() else 403 to "{\"ok\":false,\"reason\":\"not-admin\"}"
                url.contains("view=") -> 200 to "{\"ok\":true,\"items\":[],\"team\":[]}"
                url.contains("activity=1") -> 200 to "{\"ok\":true,\"activity\":[]}"
                url.contains("team=1") -> 200 to "{\"ok\":true,\"team\":[]}"
                url.contains("pin-auth/me") -> 200 to "{\"ok\":true,\"available\":false}"
                method == "GET" -> 200 to snapshot(admin, who).toString()
                else -> 200 to "{\"ok\":true}"
            }
        }
        return m
    }
    private fun show(m: AppModel, dark: Boolean = false) {
        rule.setContent { RafikiTheme(dark = dark) { Box(Modifier.size(390.dp, 844.dp)) { App(m) } } }
        rule.waitUntil(8000) { has("nav_map") }; rule.onNodeWithTag("nav_map").performClick(); rule.waitForIdle()
        org.rafikioutreach.pin.ui.MapClean.revealed = true; rule.waitForIdle() // the tiles shown, as after a tap on the calm map
    }
    private fun bitmap(): Bitmap {
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        return Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
    }
    private fun save(b: Bitmap, name: String) = File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    private fun shot(name: String) { rule.mainClock.advanceTimeBy(900); save(bitmap(), name) }
    /** A sheet lives in its own window: draw it over the dimmed screen, the way it looks on the phone. */
    private fun sheetShot(name: String) {
        rule.mainClock.advanceTimeBy(700); rule.waitForIdle()
        val b = bitmap(); val c = Canvas(b); c.drawColor(0x66000000)
        val d = org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window?.decorView
        if (d != null && d.width > 0) { c.save(); c.translate(0f, (b.height - d.height).toFloat().coerceAtLeast(0f)); d.draw(c); c.restore() }
        save(b, name)
    }
    private fun has(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun waitTag(tag: String) = rule.waitUntil(8000) { has(tag) }
    private fun hasText(t: String) = rule.onAllNodesWithText(t, substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
    private fun map(): MapView = OfflineMaps.mapView!!.get()!!
    private fun markers(prefix: String) = map().overlays.filterIsInstance<Marker>().filter { (it.title ?: "").startsWith(prefix) }
    private fun openSheet() { rule.onNodeWithContentDescription(t("Map style", "Mtindo wa ramani")).performClick(); rule.mainClock.advanceTimeBy(700); rule.waitForIdle(); waitTag("sheet") }
    private fun closeSheet() { rule.onNodeWithTag("pick_sat").performClick(); rule.mainClock.advanceTimeBy(900); rule.waitForIdle() }
    private fun here(lat: Double, lng: Double, bearing: Float? = null) { rule.runOnIdle { lastModel!!.locator.pretend(Fix(LatLng(lat, lng), 12, System.currentTimeMillis(), bearing)) }; rule.mainClock.advanceTimeBy(900); rule.waitForIdle() }
    private var lastModel: AppModel? = null
    /** Follow me is in the tray under More while it is off (MapHome.kt, MapSide); on, it shows in the column. */
    private fun followOn() { rule.onNodeWithTag("ml_more").performClick(); rule.waitForIdle(); rule.onNodeWithTag("ml_follow").performClick() }

    @Test fun logic_route_order_ticks_years_and_heat() {
        val s = Snapshot.from(snapshot(false, "Meril Kiprop"))
        val stops = dayStops(app, s, LatLng(0.5205, 35.2699))
        assertEquals("nearest first, then the next nearest", listOf("102", "110", "109"), stops.map { it.id })
        assertFalse(visitedToday(s, "102"))
        // checked in at 102: the server drops that assignment; the day's order stays
        visits = JSONObject(visits.toString()).put("102", JSONObject().put("at", ago(5)).put("by", "Meril Kiprop").put("state", "working")); assigned = assigned.drop(1)
        val s2 = Snapshot.from(snapshot(false, "Meril Kiprop"))
        assertTrue(visitedToday(s2, "102"))
        assertEquals(listOf("102", "110", "109"), dayStops(app, s2, LatLng(0.9, 35.1)).map { it.id })
        // an assignment taken away (not visited) leaves the route
        assigned = assigned.filter { it.first != "109" }
        assertEquals(listOf("102", "110"), dayStops(app, Snapshot.from(snapshot(false, "Meril Kiprop")), null).map { it.id })
        // a new day starts afresh
        assertEquals(emptyList<String>(), dayStops(app, s2, null, LocalDate.now().plusDays(1)).map { it.id })
        // years: completed projects with a year only
        assertEquals(2014..2024, yearSpan(s))
        assertEquals(setOf("101", "103", "102", "113"), doneBy(s, 2017).map { it.id }.toSet())
        // heat: not working counts double, urgent half as much again; people served from the board
        val rep = heatPoints(s, "repairs").map { it.second }.sorted()
        assertEquals(listOf(1.0, 1.5, 3.0), rep)
        assertEquals(16900.0, heatPoints(s, "people").sumOf { it.second }, 0.1)
        assertEquals(3, heatPoints(s, "people", 2016).size)
        // the project list carries people served
        assertEquals(1200, s.projects.first { it.id == "101" }.people); assertNull(s.projects.first { it.id == "112" }.people)
        assertEquals(1200, Project.from(s.projects.first { it.id == "101" }.toJson()).people)
    }

    @Test fun field_route_follow_compass() {
        val m = model(admin = false); lastModel = m; show(m)
        waitTag("ml_more"); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        here(0.5205, 35.2699)
        assertEquals("three numbered stops", 3, markers("Stop ").size)
        assertTrue("urgent and not-working sites", map().overlays.any { it.javaClass.simpleName == "PulseOverlay" })
        assertFalse("no office layers for field users", has("ml_years") || has("ml_legend"))
        shot("ml_01_route")
        // the layers sheet: Today's route, no office switches
        openSheet()
        assertTrue(has("ml_sw_route")); assertFalse(has("ml_sw_team") || has("ml_sw_years") || has("ml_heat_repairs"))
        assertTrue(hasText("3 visits for you today · 0 done"))
        sheetShot("ml_02_sheet_field")
        rule.onNodeWithTag("ml_sw_route").performClick(); rule.waitForIdle()
        assertEquals("route off", 0, markers("Stop ").size)
        rule.onNodeWithTag("ml_sw_route").performClick(); rule.waitForIdle()
        assertEquals("and back on", 3, markers("Stop ").size)
        closeSheet()
        // tap the first stop: its card, with Report the visit and Directions
        rule.runOnIdle { markers("Stop 1").first().let { HomeLayers.pick = "s:102" } }; rule.waitForIdle()
        waitTag("ml_stop"); assertTrue(hasText("Report a visit") && hasText("Directions") && hasText("Chepkongi Dispensary"))
        shot("ml_03_stop_card")
        rule.onNodeWithTag("ml_stop").assertExists(); HomeLayers.pick = null; rule.waitForIdle()
        // follow me: centred on you, the next stop with its distance; Nearest to you steps aside
        followOn(); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        here(0.5206, 35.2699)
        waitTag("ml_next")
        assertTrue(hasText("Next stop · 1/3") && hasText("Chepkongi Dispensary"))
        assertTrue(rule.onAllNodesWithText("≈ ", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertFalse("Nearest to you steps aside", has("nearest"))
        val c = map().mapCenter; assertEquals(0.5206, c.latitude, 0.002); assertEquals(35.2699, c.longitude, 0.002)
        assertTrue(map().zoomLevelDouble >= 15.9)
        shot("ml_04_follow")
        // moving north-east at speed: the map turns heading-up and the compass shows
        here(0.5216, 35.2709, 60f)
        rule.mainClock.advanceTimeBy(1200); rule.waitForIdle()
        assertEquals(-60f, map().mapOrientation, 1.5f)
        waitTag("ml_compass")
        shot("ml_05_follow_turned")
        rule.onNodeWithTag("ml_compass").performClick(); rule.mainClock.advanceTimeBy(1200); rule.waitForIdle()
        assertEquals(0f, ((map().mapOrientation % 360f) + 360f) % 360f, 1f)
        assertFalse("the compass hides at north", has("ml_compass"))
        here(0.5226, 35.2719, 65f)
        assertEquals("north-up stays while following", 0f, ((map().mapOrientation % 360f) + 360f) % 360f, 1f)
        // checked in at the first stop: it ticks, the card moves on to stop 2
        visits = JSONObject(visits.toString()).put("102", JSONObject().put("at", ago(1)).put("by", "Meril Kiprop").put("state", "working")); assigned = assigned.drop(1)
        rule.runOnIdle { m.refresh(quiet = true) }; rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        rule.waitUntil(6000) { hasText("Next stop · 2/3") }
        assertEquals(3, markers("Stop ").size)
        // dragging the map stops following
        rule.runOnIdle {
            val mv = map(); val t0 = SystemClock.uptimeMillis(); val x = mv.width / 2f; val y = mv.height / 2f
            mv.dispatchTouchEvent(MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, x, y, 0))
            mv.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + 30, MotionEvent.ACTION_MOVE, x + 60, y + 40, 0))
            mv.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + 60, MotionEvent.ACTION_MOVE, x + 160, y + 90, 0))
            mv.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + 90, MotionEvent.ACTION_UP, x + 160, y + 90, 0))
        }
        rule.mainClock.advanceTimeBy(600); rule.waitForIdle()
        assertFalse("dragging stops following", HomeLayers.follow); assertFalse(has("ml_next")); assertTrue(has("nearest"))
        // the app never asks a field user's team map
        assertFalse(calls.any { it.contains("teammap") })
    }

    @Test fun admin_heat_years_team() {
        val m = model(admin = true); lastModel = m; show(m)
        waitTag("ml_more"); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        assertEquals("no route without visits today", 0, markers("Stop ").size)
        openSheet()
        assertTrue(has("ml_heat_off") && has("ml_heat_repairs") && has("ml_heat_people") && has("ml_sw_years") && has("ml_sw_team"))
        assertTrue(hasText("No visits for you today"))
        sheetShot("ml_10_sheet_admin")
        rule.onNodeWithTag("ml_heat_repairs").performClick(); rule.waitForIdle()
        closeSheet()
        waitTag("ml_legend"); assertTrue(hasText("Open repairs") && hasText("3 open repairs"))
        shot("ml_11_heat_repairs")
        openSheet(); rule.onNodeWithTag("ml_heat_people").performClick(); rule.waitForIdle(); closeSheet()
        assertTrue(hasText("16,900 people served at 11 sites"))
        shot("ml_12_heat_people")
        // the time slider: only what was completed by the year; play steps on
        openSheet(); rule.onNodeWithTag("ml_sw_years").performClick(); rule.waitForIdle(); closeSheet()
        waitTag("ml_years"); rule.mainClock.advanceTimeBy(2000); rule.waitForIdle()
        assertTrue(hasText("By 2024") && hasText("11 projects completed"))
        assertEquals(11, markers("").count { (it.title ?: "").contains(" · 20") })
        assertFalse("Nearest to you steps aside for the presentation", has("nearest"))
        shot("ml_13_years_all")
        // the first and last year sit under the track, clear of the slider's thumb
        run {
            val sl = rule.onNodeWithTag("ml_slider").fetchSemanticsNode().boundsInRoot; val ends = rule.onNodeWithTag("ml_year_ends").fetchSemanticsNode().boundsInRoot
            assertTrue("year labels under the slider: ${ends.top} >= ${sl.bottom}", ends.top >= sl.bottom - 0.5f)
        }
        rule.onNodeWithTag("ml_slider").performSemanticsAction(SemanticsActions.SetProgress) { it(2017f) }; rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        assertTrue(hasText("By 2017") && hasText("4 projects completed"))
        assertEquals(4, markers("").count { (it.title ?: "").contains(" · 20") })
        shot("ml_14_years_2017")
        rule.onNodeWithTag("ml_play").performClick(); rule.mainClock.advanceTimeBy(1400); rule.waitForIdle()
        assertTrue(hasText("By 2018"))
        shot("ml_15_years_playing")
        rule.mainClock.advanceTimeBy(1300L * 8); rule.waitForIdle()
        assertTrue(hasText("By 2024")); assertFalse(HomeLayers.playing)
        rule.onNodeWithContentDescription(t("Close the time slider", "Funga kitelezi cha miaka")).performClick(); rule.waitForIdle()
        assertFalse(has("ml_years")); assertTrue(has("nearest"))
        // live team: initials with "min ago", tap for Call / WhatsApp, refreshed every 3 minutes
        openSheet(); rule.onNodeWithTag("ml_sw_team").performClick(); rule.waitForIdle(); closeSheet()
        rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        assertEquals(1, calls.count { it.contains("view=teammap") })
        rule.waitUntil(5000) { markers("Bertin").isNotEmpty() }
        assertEquals(setOf("Bertin Mwangi", "Grace Achieng", "Meril Kiprop"), map().overlays.filterIsInstance<Marker>().map { it.title }.filter { it in setOf("Bertin Mwangi", "Grace Achieng", "Meril Kiprop") }.toSet())
        shot("ml_16_team")
        // teammates close together sit side by side: no two markers (initials and time) overlap on screen
        run {
            val mv = map()
            val boxes = mutableListOf<android.graphics.RectF>()
            rule.runOnUiThread {
                mv.overlays.filterIsInstance<Marker>().filter { it.title in setOf("Bertin Mwangi", "Grace Achieng", "Meril Kiprop") }.forEach { mk ->
                    val pt = mv.projection.toPixels(mk.position, null)
                    fun f(n: String) = Marker::class.java.getDeclaredField(n).apply { isAccessible = true }.getFloat(mk)
                    val w = mk.icon.intrinsicWidth.toFloat(); val h = mk.icon.intrinsicHeight.toFloat()
                    val l = pt.x - f("mAnchorU") * w; val tp = pt.y - f("mAnchorV") * h
                    boxes += android.graphics.RectF(l, tp, l + w, tp + h)
                }
            }
            val hits = boxes.indices.flatMap { i -> (i + 1 until boxes.size).filter { j -> android.graphics.RectF.intersects(boxes[i], boxes[j]) }.map { i to it } }
            assertTrue("team markers overlap: $hits $boxes", boxes.size == 3 && hits.isEmpty())
        }
        rule.runOnIdle { HomeLayers.pick = "t:Bertin Mwangi" }; rule.waitForIdle()
        waitTag("ml_mate"); assertTrue(has("ml_call") && has("ml_wa") && hasText("Last seen 12 min ago"))
        shot("ml_17_team_card")
        rule.mainClock.advanceTimeBy(181_000); rule.waitForIdle()
        // the request itself runs on a background thread (Api.call): give it a moment to land
        rule.waitUntil(5000) { calls.count { it.contains("view=teammap") } >= 2 }
        assertEquals("refreshed after 3 minutes", 2, calls.count { it.contains("view=teammap") })
        // offline: the team needs a signal
        OfflineMaps.setOnline(false); rule.mainClock.advanceTimeBy(181_000); rule.waitForIdle()
        assertTrue(hasText("No signal"))
        OfflineMaps.setOnline(true)
    }

    @Test fun dark_and_kiswahili() {
        Lang.sw = true
        val m = model(admin = true); lastModel = m; show(m, dark = true)
        waitTag("ml_more"); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        HomeLayers.heat = "repairs"; HomeLayers.team = true; rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        waitTag("ml_legend"); assertTrue(hasText("Matengenezo yaliyo wazi"))
        shot("ml_20_dark_sw")
        openSheet(); assertTrue(hasText("Kwenye ramani".uppercase()) && hasText("Njia ya leo") && hasText("Timu sasa hivi"))
        sheetShot("ml_21_sheet_dark_sw")
    }

    /** With the Map tab (MapHome.kt): the layers sit between the tiles and the pins, the slider's dots replace the pins, and the card steps aside. */
    @Test fun with_the_map_tab_pins_kiswahili() {
        Lang.sw = true
        val m = model(admin = false); lastModel = m; show(m)
        waitTag("ml_more"); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        here(0.5205, 35.2699)
        val ov = map().overlays
        val firstPin = ov.indexOfFirst { it is Marker && ((it.id ?: "").startsWith("pin:") || (it.id ?: "").startsWith("cluster:")) }
        assertTrue("the Map tab's pins are drawn", firstPin >= 0)
        assertTrue("the pulse is under the pins", ov.indexOfFirst { it.javaClass.simpleName == "PulseOverlay" } in 0 until firstPin)
        assertTrue("the route's line is under the pins", ov.indexOfFirst { it.javaClass.simpleName == "RouteLine" } in 0 until firstPin)
        assertTrue("the numbered stops are over the pins", ov.indexOfFirst { it is Marker && (it.title ?: "").startsWith("Kituo ") } > firstPin)
        shot("ml_40_route_pins_sw")
        // following: the next stop takes the place of the card and Nearest to you
        followOn(); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        here(0.5215, 35.2709, 60f); waitTag("ml_next")
        assertFalse(has("nearest") || has("map_card"))
        shot("ml_41_follow_turned_sw")
    }

    private fun isPin(o: Any) = (o as? Marker)?.id?.let { it.startsWith("pin:") || it.startsWith("cluster:") } == true
    private fun heatYearsSw(dark: Boolean) {
        Lang.sw = true
        val m = model(admin = true); lastModel = m; show(m, dark)
        waitTag("ml_more"); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        assertTrue(map().overlays.any { isPin(it) })
        HomeLayers.heat = "people"; rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        val ov = map().overlays
        assertTrue("the heat is under the pins", ov.indexOfFirst { it.javaClass.simpleName == "HeatOverlay" } in 0 until ov.indexOfFirst { isPin(it) })
        shot("ml_42_heat_pins_sw" + if (dark) "_dark" else "")
        HomeLayers.years = true; rule.mainClock.advanceTimeBy(2000); rule.waitForIdle()
        waitTag("ml_years")
        assertFalse("the slider's dots replace the pins", map().overlays.any { isPin(it) })
        assertFalse("and their filters and key", has("map_chips") || has("map_legend") || has("nearest"))
        shot("ml_43_years_sw" + if (dark) "_dark" else "")
        HomeLayers.years = false; rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        assertTrue("the pins come back", map().overlays.any { isPin(it) })
    }
    @Test fun with_the_map_tab_heat_years_kiswahili() = heatYearsSw(false)
    @Test fun with_the_map_tab_heat_years_kiswahili_dark() = heatYearsSw(true)

    @Test fun field_user_kiswahili_follow_and_reduced_motion() {
        Lang.sw = true; Motion.on = false
        val m = model(admin = false); lastModel = m; show(m)
        waitTag("ml_more"); rule.waitForIdle()
        here(0.5205, 35.2699)
        followOn(); rule.waitForIdle()
        here(0.5215, 35.2709, 300f)
        waitTag("ml_next"); assertTrue(hasText("Kituo 1/3"))
        assertEquals("no motion: turned straight away", 60f, map().mapOrientation, 1.5f)
        shot("ml_30_follow_sw_still")
        openSheet(); assertTrue(hasText("Ziara 3 zako leo · 0 zimekamilika")); sheetShot("ml_31_sheet_sw")
    }
}

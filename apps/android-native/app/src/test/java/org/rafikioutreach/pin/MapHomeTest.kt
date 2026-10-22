package org.rafikioutreach.pin

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.rafikioutreach.pin.data.Fix
import org.rafikioutreach.pin.data.LatLng
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.data.Snapshot
import org.rafikioutreach.pin.ui.PinCond
import org.rafikioutreach.pin.ui.Look
import org.rafikioutreach.pin.ui.MapHome
import org.rafikioutreach.pin.ui.HomePin
import org.rafikioutreach.pin.ui.MapSetup
import org.rafikioutreach.pin.ui.OfflineMaps
import org.rafikioutreach.pin.ui.RafikiTheme
import org.rafikioutreach.pin.ui.Satellite
import org.rafikioutreach.pin.ui.clusterPins
import org.rafikioutreach.pin.ui.mapCondition
import org.rafikioutreach.pin.ui.mapStatusOk
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

/** The Map tab: pins by condition, filter chips, search, clusters, the swipeable card and long press. Screenshots in build/shots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class MapHomeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ago = { days: Long -> Instant.now().minusSeconds(days * 86400).toString() }

    @Before fun fresh() { MapSetup.init(app); OfflineMaps.setOnline(true) }
    @After fun reset() { org.rafikioutreach.pin.ui.Motion.on = true; Lang.sw = false; Look.mode = ""; OfflineMaps.setOnline(true) }

    private fun proj(id: String, t: String, area: String, lat: Double?, lng: Double?, type: String = "Borehole well", ck: String = "ke", country: String = "Kenya") =
        JSONObject().put("id", id).put("title", t).put("country", country).put("ck", ck).put("area", area).put("type", type).put("status", "Completed").put("pump", "")
            .apply { if (lat != null) put("gps", JSONArray().put(lat).put(lng)) }
    private fun visit(days: Long, by: String = "Meril", state: String = "working") = JSONObject().put("at", ago(days)).put("by", by).put("state", state)
    private fun snapshot() = JSONObject().put("ok", true).put("who", "Meril Kiprop").put("admin", false).put("reviewOpen", 0)
        .put("projects", JSONArray()
            .put(proj("101", "Kapsaret Borehole", "Eldoret", 0.47, 35.25))
            .put(proj("102", "Chepkongi Dispensary", "Eldoret", 0.5143, 35.2698, "Health"))
            .put(proj("103", "Turbo Spring", "Turbo", 0.63, 35.05, "Water collection"))
            .put(proj("104", "Nzoia Bridge Primary School", "Bungoma", 0.58, 34.62, "School"))
            .put(proj("105", "Tamiha Children’s Home", "Kitale", 1.0, 35.0, "Orphanage"))
            .put(proj("106", "Kaptebee Pigsty", "Nandi", 0.2, 35.1, "Farming"))
            .put(proj("107", "Moi’s Bridge Borehole", "Eldoret", 0.87, 35.12))
            .put(proj("108", "Mto wa Mbu Borehole", "Arusha", -3.37, 35.85, ck = "tz", country = "Tanzania"))
            .put(proj("109", "Kapsaret Primary School", "Eldoret", 0.475, 35.255, "School"))
            .put(proj("110", "Sosiot Well", "Kericho", null, null)))
        .put("repairs", JSONObject()
            .put("101", JSONObject().put("state", "notworking").put("by", "Bertin").put("at", ago(3)).put("urgency", "urgent").put("t", "Kapsaret Borehole"))
            .put("102", JSONObject().put("state", "repair").put("by", "Grace").put("at", ago(9)).put("urgency", "month").put("t", "Chepkongi Dispensary")))
        .put("visits", JSONObject().put("101", visit(3, "Bertin", "notworking")).put("103", visit(20)).put("104", visit(400)).put("106", visit(10, "Grace"))
            .put("107", visit(30)).put("109", visit(50, "Reid")))
        .put("jobs", JSONArray()).put("assigned", JSONArray().put(JSONObject().put("aid", "a1").put("id", "105").put("t", "Tamiha Children’s Home").put("date", "2026-10-30").put("by", "Reid").put("note", "")))
        .put("confirmed", JSONObject()).put("me", JSONObject().put("kind", "app").put("home", "ke"))

    private fun model(): AppModel {
        org.rafikioutreach.pin.ui.Onb.enabled = false
        org.rafikioutreach.pin.ui.Motion.on = false
        Prefs(app).apply { pin = "1111"; name = "Meril Kiprop"; saveSnapshot(snapshot()); filter = "all"; country = ""; lang = "en"; ftype = "" }
        val m = AppModel(app)
        m.api.transport = { method, url, _, _ ->
            calls += "$method $url"
            when {
                url.contains("/api/place") -> 200 to "{\"ok\":true,\"places\":[{\"name\":\"Kitale\",\"where\":\"Trans Nzoia, Kenya\",\"lat\":1.0157,\"lng\":35.0062}]}"
                url.contains("history=") -> 200 to "{\"ok\":true,\"notes\":[]}"
                url.contains("pin-auth/me") -> 200 to "{\"ok\":true,\"available\":false}"
                url.contains("activity=1") -> 200 to "{\"ok\":true,\"activity\":[]}"
                url.contains("?id=") -> 200 to "{\"ok\":true,\"last\":null,\"canUndo\":false,\"visits\":[]}"
                method == "GET" -> 200 to snapshot().toString()
                else -> 200 to "{\"ok\":true,\"updateId\":\"9001\"}"
            }
        }
        m.locator.pretend(Fix(LatLng(0.5150, 35.2700), 8, System.currentTimeMillis()))
        return m
    }
    private fun show(m: AppModel, dark: Boolean = false) {
        rule.setContent { RafikiTheme(dark = dark) { Box(Modifier.size(390.dp, 844.dp)) { App(m) } } }
        rule.waitUntil(8000) { rule.onAllNodesWithTag("nav_map").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav_map").performClick(); rule.waitForIdle()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("map_home").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun bitmap(): Bitmap {
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        return Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
    }
    private fun shot(name: String) = File(out, "$name.png").outputStream().use { bitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    private fun has(t: String) = rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun tagged(t: String) = rule.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty()
    private fun text(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.let { c ->
        c.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString("") { it.text } ?: ""
    }
    private fun findMap(v: android.view.View): MapView? {
        if (v is MapView) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findMap(v.getChildAt(i))?.let { return it }
        return null
    }
    private fun map(): MapView = findMap(rule.activity.window.decorView)!!
    private fun look(mv: MapView, lat: Double, lng: Double, z: Double) {
        rule.runOnUiThread { mv.controller.setZoom(z); mv.controller.setCenter(GeoPoint(lat, lng)) }
        rule.waitForIdle(); bitmap(); rule.runOnUiThread { mv.invalidate() }; rule.waitForIdle()
    }
    private fun markers(mv: MapView) = mv.overlays.filterIsInstance<Marker>().filter { it.id?.startsWith("pin:") == true || it.id?.startsWith("cluster:") == true }
    private fun tapMarker(mv: MapView, id: String) {
        bitmap()
        rule.runOnUiThread {
            val mk = mv.overlays.filterIsInstance<Marker>().first { it.id == id }
            val pt = mv.projection.toPixels(mk.position, null)
            assertTrue("marker $id takes the tap", mk.onSingleTapConfirmed(MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, pt.x.toFloat(), pt.y.toFloat(), 0), mv))
        }
        rule.waitForIdle()
    }
    private fun longPress(mv: MapView, lat: Double, lng: Double) {
        bitmap()
        rule.runOnUiThread {
            val pt = mv.projection.toPixels(GeoPoint(lat, lng), null)
            val o = mv.overlays.filterIsInstance<MapEventsOverlay>().first()
            assertTrue(o.onLongPress(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, pt.x.toFloat(), pt.y.toFloat(), 0), mv))
        }
        rule.waitForIdle()
    }

    @Test fun conditions_and_clusters_rules() {
        val s = Snapshot.from(snapshot())
        val c = s.projects.filter { it.gps != null }.associate { it.id to mapCondition(s, it) }
        assertEquals(PinCond.BAD, c["101"]); assertEquals(PinCond.REPAIR, c["102"]); assertEquals(PinCond.OK, c["103"])
        assertEquals("a visit over 6 months ago is grey", PinCond.OLD, c["104"]); assertEquals("never visited is grey", PinCond.OLD, c["105"]); assertEquals(PinCond.OK, c["109"])
        // a fixed repair is closed: its last visit (not working) no longer makes it red
        val fixed = Snapshot.from(snapshot().put("repairs", JSONObject()))
        assertEquals(PinCond.OK, mapCondition(fixed, fixed.projects.first { it.id == "101" }))
        // filters
        assertEquals(setOf("101", "102"), s.projects.filter { mapStatusOk(s, it, "broken") }.map { it.id }.toSet())
        assertEquals(setOf("105"), s.projects.filter { mapStatusOk(s, it, "mine") }.map { it.id }.toSet())
        assertTrue(s.projects.filter { mapStatusOk(s, it, "due") }.map { it.id }.containsAll(listOf("104", "105", "108")))
        // clusters: near pins group, the worst inside seeds the group; street level shows every pin
        val pins = s.projects.filter { it.gps != null }.map { HomePin(it.id, it.gps!!, c[it.id]!!) }
        val far = clusterPins(pins, 6.0, 90.0)
        val eld = far.first { g -> g.any { it.id == "101" } }
        assertTrue(eld.size >= 3 && eld.first().id == "101")
        assertEquals(listOf(listOf("108")), far.filter { g -> g.any { it.id == "108" } }.map { g -> g.map { it.id } })
        assertEquals(pins.size, clusterPins(pins, 15.0, 90.0).size)
        assertEquals(pins.size, clusterPins(pins, 6.0, 90.0).sumOf { it.size })
    }

    @Test fun pins_chips_legend_and_satellite() {
        Lang.sw = false
        val m = model(); show(m)
        val mv = map()
        assertEquals("the map opens on satellite photos", Satellite.name(), mv.tileProvider.tileSource.name())
        look(mv, 0.62, 35.05, 9.0)
        rule.waitUntil(5000) { markers(mv).isNotEmpty() }
        assertTrue(tagged("map_chips") && has("Broken") && has("Due a visit") && has("My assigned visits") && has("Water") && has("Schools") && has("Health") && has("Children’s homes") && has("Farming"))
        assertTrue(has("projects pinned") && tagged("map_legend") && has("No visit in 6 months"))
        shot("map-01-home")
        // Broken: only the two open repairs
        rule.onNodeWithTag("mchip_broken").performClick(); rule.waitForIdle(); look(mv, 0.49, 35.26, 13.0)
        assertEquals(setOf("pin:101", "pin:102"), markers(mv).map { it.id }.toSet())
        assertEquals("broken", app.getSharedPreferences("map_style", 0).getString("mapf", ""))
        shot("map-02-broken")
        // a type on top of it: Schools + Broken leaves nothing, and says so
        rule.onNodeWithTag("mchip_school").performScrollTo().performClick(); rule.waitForIdle()
        assertTrue(tagged("map_none")); assertEquals("school", m.prefs.ftype)
        rule.onNodeWithTag("mchip_clear").performScrollTo().performClick(); rule.waitForIdle()
        assertEquals("", m.prefs.ftype); assertFalse(tagged("map_none"))
        rule.onNodeWithTag("mchip_mine").performScrollTo().performClick(); rule.waitForIdle(); look(mv, 1.0, 35.0, 12.0)
        assertEquals(listOf("pin:105"), markers(mv).map { it.id })
        rule.onNodeWithTag("mchip_mine").performScrollTo().performClick(); rule.waitForIdle()
        // the legend taps away, and comes back
        rule.onNodeWithTag("map_legend").performClick(); rule.waitForIdle()
        assertTrue(tagged("legend_show") && !tagged("map_legend"))
        assertTrue(app.getSharedPreferences("map_style", 0).getBoolean("legend_off", false))
        rule.onNodeWithTag("legend_show").performClick(); rule.waitForIdle()
        assertTrue(tagged("map_legend"))
    }

    @Test fun clusters_zoomed_out_and_tap_to_zoom_in() {
        Lang.sw = false
        val m = model(); show(m)
        val mv = map()
        look(mv, -1.2, 35.4, 6.0)
        rule.waitUntil(5000) { markers(mv).any { it.id.startsWith("cluster:") } }
        val bubble = markers(mv).first { it.id.startsWith("cluster:") && it.snippet == PinCond.BAD }
        assertTrue(bubble.title.toInt() >= 3)
        assertTrue("Tanzania stands alone", markers(mv).any { it.id == "pin:108" })
        shot("map-03-clusters")
        val z = mv.zoomLevelDouble
        tapMarker(mv, bubble.id)
        rule.mainClock.advanceTimeBy(1000); rule.waitForIdle()
        assertTrue("a tap on a bubble zooms in: ${mv.zoomLevelDouble}", mv.zoomLevelDouble >= z + 1)
    }

    @Test fun search_card_swipe_and_actions() {
        Lang.sw = false
        val m = model(); show(m)
        val mv = map()
        rule.onNodeWithTag("map_search_input").performClick(); rule.onNodeWithTag("map_search_input").performTextInput("Eldo")
        rule.waitUntil(3000) { tagged("sugg_a_Eldoret") }
        assertTrue(tagged("sugg_p_101") && tagged("sugg_p_109") && has("4 projects"))
        shot("map-04-search")
        rule.onNodeWithTag("sugg_p_101").performClick(); rule.waitForIdle()
        rule.waitUntil(3000) { tagged("map_card") }
        assertEquals("Kapsaret Borehole", text("card_title"))
        assertTrue(has("Not working") && text("card_dist").endsWith("from you") && text("card_visit_line").startsWith("Last visit") && text("card_visit_line").endsWith("Bertin"))
        assertTrue(text("card_pos").startsWith("1 of "))
        assertTrue("flew to it", mv.zoomLevelDouble >= 14.0)
        shot("map-05-card")
        // the next nearest to it: the school next door, then the dispensary
        rule.onNodeWithTag("card_next").performClick(); rule.waitForIdle()
        assertEquals("Kapsaret Primary School", text("card_title"))
        rule.onNodeWithTag("map_card").performTouchInput { swipeLeft() }; rule.waitForIdle()
        assertEquals("Chepkongi Dispensary", text("card_title")); assertTrue(text("card_pos").startsWith("3 of "))
        assertTrue(has("Needs repair"))
        shot("map-06-card-swiped")
        rule.onNodeWithTag("map_card").performTouchInput { swipeRight() }; rule.waitForIdle()
        assertEquals("Kapsaret Primary School", text("card_title"))
        // Directions: Google Maps to the point
        rule.onNodeWithTag("card_dir").performClick(); rule.waitForIdle()
        val i = shadowOf(rule.activity).nextStartedActivity
        assertTrue(i.dataString!!.contains("destination=0.475000,35.255000"))
        // a tap on a pin opens its card
        rule.onNodeWithTag("card_x").performClick(); rule.waitForIdle(); assertFalse(tagged("map_card"))
        look(mv, 0.62, 35.05, 13.0)
        tapMarker(mv, "pin:103")
        assertEquals("Turbo Spring", text("card_title")); assertTrue(has("Working"))
        // Visit now opens the site visit
        rule.onNodeWithTag("card_visit").performClick()
        rule.waitUntil(4000) { has("I’ve arrived") }
    }

    @Test fun search_with_no_signal_and_places_with_signal() {
        Lang.sw = false
        val m = model(); show(m)
        OfflineMaps.setOnline(false); rule.waitForIdle()
        rule.onNodeWithTag("map_search_input").performClick(); rule.onNodeWithTag("map_search_input").performTextInput("Kitale")
        rule.mainClock.advanceTimeBy(1200); rule.waitForIdle()
        assertTrue(tagged("sugg_a_Kitale") && tagged("sugg_p_105") && tagged("map_sugg_offline"))
        assertFalse("no place search with no signal", calls.any { it.contains("/api/place") })
        shot("map-07-search-offline")
        OfflineMaps.setOnline(true); rule.waitForIdle()
        rule.onNodeWithTag("map_search_input").performTextClearance(); rule.onNodeWithTag("map_search_input").performTextInput("Kitale")
        rule.waitUntil(5000) { tagged("sugg_g_0") }
        assertTrue(calls.any { it.contains("/api/place?q=Kitale") && it.contains("free=1") })
        assertTrue(has("OpenStreetMap"))
        val mv = map()
        rule.onNodeWithTag("sugg_g_0").performClick(); rule.waitForIdle()
        assertEquals(13.0, mv.zoomLevelDouble, 0.01)
        assertEquals(1.0157, mv.mapCenter.latitude, 0.001)
    }

    @Test fun long_press_measure_and_new_site() {
        Lang.sw = false
        val m = model(); show(m)
        val mv = map()
        look(mv, 0.52, 35.29, 13.0)
        longPress(mv, 0.53, 35.30)
        assertTrue(tagged("spot_card") && has("Add a new site here") && has("Measure distance from me"))
        rule.onNodeWithTag("spot_measure").performClick(); rule.waitForIdle()
        assertTrue(has("from you, to the north-east"))
        assertEquals(2, mv.overlays.filterIsInstance<Polyline>().size)
        shot("map-08-measure")
        rule.onNodeWithTag("spot_new").performClick(); rule.waitForIdle()
        rule.waitUntil(4000) { has("Add a new site") && has("Where is it?") }
        assertTrue("the new site starts at the pressed point", has("0.53"))
        assertEquals(null, MapHome.newSiteAt)
        shot("map-09-new-site-here")
    }

    @Test fun dark_sun_and_kiswahili() {
        Lang.sw = false
        val m = model(); show(m, dark = true)
        val mv = map()
        rule.onNodeWithTag("map_search_input").performClick(); rule.onNodeWithTag("map_search_input").performTextInput("Turbo")
        rule.waitUntil(3000) { tagged("sugg_p_103") }
        rule.onNodeWithTag("sugg_p_103").performClick(); rule.waitForIdle()
        shot("map-10-card-dark")
        Look.mode = "sun"; rule.waitForIdle()
        rule.onNodeWithTag("card_x").performClick(); rule.waitForIdle()
        look(mv, 0.62, 35.05, 9.0)
        shot("map-11-sun")
        Look.mode = ""; Lang.sw = true; rule.waitForIdle()
        assertTrue(has("Zimeharibika") && has("Ziara nilizopewa") && has("Bila ziara miezi 6"))
        look(mv, 0.49, 35.26, 13.0)
        tapMarker(mv, "pin:102")
        assertTrue(has("Inahitaji matengenezo") && has("Tembelea sasa") && has("Maelekezo") && has("Fungua mradi"))
        shot("map-12-sw-card")
        rule.onNodeWithTag("card_x").performClick(); rule.waitForIdle()
        longPress(mv, 0.50, 35.27)
        assertTrue(has("Ongeza eneo jipya hapa") && has("Pima umbali kutoka kwangu"))
        shot("map-13-sw-spot")
        assertNotNull(mv)
    }
}

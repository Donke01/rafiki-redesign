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
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Rect
import android.graphics.RectF
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
    @After fun reset() { org.rafikioutreach.pin.ui.Motion.on = true; Lang.sw = false; Look.mode = ""; OfflineMaps.setOnline(true); org.rafikioutreach.pin.ui.HomeLayers.reset(); twins = false; longName = false }

    private fun proj(id: String, t: String, area: String, lat: Double?, lng: Double?, type: String = "Borehole well", ck: String = "ke", country: String = "Kenya") =
        JSONObject().put("id", id).put("title", t).put("country", country).put("ck", ck).put("area", area).put("type", type).put("status", "Completed").put("pump", "")
            .apply { if (lat != null) put("gps", JSONArray().put(lat).put(lng)) }
    private var longName = false
    private var twins = false // a second project on the very same spot as Turbo Spring
    private fun visit(days: Long, by: String = "Meril", state: String = "working") = JSONObject().put("at", ago(days)).put("by", by).put("state", state)
    private fun snapshot() = JSONObject().put("ok", true).put("who", "Meril Kiprop").put("admin", false).put("reviewOpen", 0)
        .put("projects", JSONArray()
            .put(proj("101", "Kapsaret Borehole", "Eldoret", 0.47, 35.25))
            .put(proj("102", if (longName) "Chepkongi Dispensary and Maternity Wing, Kapsaret Ward" else "Chepkongi Dispensary", "Eldoret", 0.5143, 35.2698, "Health"))
            .put(proj("103", "Turbo Spring", "Turbo", 0.63, 35.05, "Water collection"))
            .put(proj("104", "Nzoia Bridge Primary School", "Bungoma", 0.58, 34.62, "School"))
            .put(proj("105", "Tamiha Children’s Home", "Kitale", 1.0, 35.0, "Orphanage"))
            .put(proj("106", "Kaptebee Pigsty", "Nandi", 0.2, 35.1, "Farming"))
            .put(proj("107", "Moi’s Bridge Borehole", "Eldoret", 0.87, 35.12))
            .put(proj("108", "Mto wa Mbu Borehole", "Arusha", -3.37, 35.85, ck = "tz", country = "Tanzania"))
            .put(proj("109", "Kapsaret Primary School", "Eldoret", 0.475, 35.255, "School"))
            .put(proj("110", "Sosiot Well", "Kericho", null, null))
            .apply { if (twins) put(proj("111", "Turbo Spring Kiosk", "Turbo", 0.63, 35.05, "Farming")) })
        .put("repairs", JSONObject()
            .put("101", JSONObject().put("state", "notworking").put("by", "Bertin").put("at", ago(3)).put("urgency", "urgent").put("t", "Kapsaret Borehole"))
            .put("102", JSONObject().put("state", "repair").put("by", "Grace").put("at", ago(9)).put("urgency", "month").put("t", "Chepkongi Dispensary")))
        .put("visits", JSONObject().put("101", visit(3, "Bertin", "notworking")).put("103", visit(20)).put("104", visit(400)).put("106", visit(10, "Grace"))
            .put("107", visit(30)).put("109", visit(50, "Reid")))
        .put("jobs", JSONArray()).put("assigned", JSONArray().put(JSONObject().put("aid", "a1").put("id", "105").put("t", "Tamiha Children’s Home").put("date", "2026-10-30").put("by", "Reid").put("note", "")))
        .put("confirmed", JSONObject()).put("me", JSONObject().put("kind", "app").put("home", "ke"))

    private fun model(fix: Boolean = true): AppModel {
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
        if (fix) m.locator.pretend(Fix(LatLng(0.5150, 35.2700), 8, System.currentTimeMillis()))
        return m
    }
    /** The Map tab. It opens calm (map3); unless [calm], the tiles are then shown, as after a tap on the map. */
    private fun show(m: AppModel, dark: Boolean = false, w: Int = 390, h: Int = 844, calm: Boolean = false) {
        rule.setContent { RafikiTheme(dark = dark) { Box(Modifier.size(w.dp, h.dp)) { App(m) } } }
        rule.waitUntil(8000) { rule.onAllNodesWithTag("nav_map").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav_map").performClick(); rule.waitForIdle()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("map_home").fetchSemanticsNodes().isNotEmpty() }
        if (!calm) { org.rafikioutreach.pin.ui.MapClean.revealed = true; rule.waitForIdle() }
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
        // the key is a small Key button; its colours open on a tap (the map, decluttered)
        assertTrue(has("projects pinned") && tagged("map_key") && !tagged("map_legend"))
        shot("map-01-home")
        // Broken: only the two open repairs
        rule.onNodeWithTag("mchip_broken").performClick(); rule.waitForIdle(); look(mv, 0.49, 35.26, 13.0)
        assertEquals(setOf("pin:101", "pin:102"), markers(mv).map { it.id }.toSet())
        assertEquals("broken", app.getSharedPreferences("map_style", 0).getString("mapf", ""))
        shot("map-02-broken")
        // a filter folds the row into one pill; tap it for the row. A type on top of it: Schools + Broken leaves nothing, and says so
        assertTrue(tagged("map_pill") && !tagged("map_chips"))
        rule.onNodeWithTag("map_pill_open").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("mchip_school").performScrollTo().performClick(); rule.waitForIdle()
        assertTrue(tagged("map_none")); assertEquals("school", m.prefs.ftype)
        rule.onNodeWithTag("map_pill_x").performClick(); rule.waitForIdle()
        assertEquals("", m.prefs.ftype); assertFalse(tagged("map_none")); assertTrue(tagged("map_chips") && !tagged("map_pill"))
        rule.onNodeWithTag("mchip_mine").performScrollTo().performClick(); rule.waitForIdle(); look(mv, 1.0, 35.0, 12.0)
        assertEquals(listOf("pin:105"), markers(mv).map { it.id })
        rule.onNodeWithTag("map_pill_open").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("mchip_mine").performScrollTo().performClick(); rule.waitForIdle()
        // the Key button: its colours, closing by themselves after a few seconds
        rule.onNodeWithTag("map_key").performClick(); rule.waitForIdle()
        assertTrue(tagged("map_legend") && has("No visit in 6 months"))
        rule.mainClock.advanceTimeBy(5300); rule.waitForIdle()
        assertFalse(tagged("map_legend"))
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
        // no signal and no map kept: the map says why it is a blank grid, and where to save one
        assertTrue(tagged("map_nosignal") && has("No signal: showing pins only.") && has("Save an offline map"))
        assertEquals("no signal: nothing overlaps", emptyList<String>(), overlaps())
        shot("map-40-no-signal")
        assertTrue("You’re at shows before the search opens", tagged("here"))
        rule.onNodeWithTag("map_search_input").performClick(); rule.onNodeWithTag("map_search_input").performTextInput("Kitale")
        rule.mainClock.advanceTimeBy(1200); rule.waitForIdle()
        assertTrue(tagged("sugg_a_Kitale") && tagged("sugg_p_105") && tagged("map_sugg_offline"))
        assertFalse("You’re at steps aside while the suggestions show", tagged("here"))
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

    /** No signal: the note opens Offline maps. */
    @Test fun no_signal_note_opens_offline_maps() {
        Lang.sw = true
        val m = model(); show(m, dark = true)
        OfflineMaps.setOnline(false); rule.waitForIdle()
        assertTrue(has("Hakuna mtandao: inaonyesha alama za miradi tu.") && has("Hifadhi ramani ya bila mtandao"))
        assertEquals(emptyList<String>(), overlaps())
        shot("map-41-no-signal-sw-dark")
        rule.onNodeWithTag("map_nosignal").performClick(); rule.waitForIdle()
        rule.waitUntil(4000) { tagged("offline_screen") }
    }

    /** Nearest to you with no location: "Allow location" is a real button. It asks; asked and refused before, it opens
     *  the app's settings. */
    @Test fun allow_location_is_a_button() {
        Lang.sw = false
        val m = model(fix = false); show(m)
        rule.waitUntil(4000) { tagged("nearest_allow") }
        shot("map-42-allow-location")
        rule.onNodeWithTag("nearest_allow").performClick(); rule.waitForIdle()
        val asked = shadowOf(rule.activity).lastRequestedPermission
        assertTrue("it asks for the location permission", asked != null && asked.requestedPermissions.contains(android.Manifest.permission.ACCESS_FINE_LOCATION))
        // refused (Android won't ask again): the app's settings
        rule.onNodeWithTag("nearest_allow").performClick(); rule.waitForIdle()
        val started = generateSequence { shadowOf(rule.activity).nextStartedActivity }.map { it.action }.toList()
        assertTrue("the app's settings open: $started", android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS in started)
    }

    /** Nearest to you follows the filter: with Water on it is the nearest water point; nothing left says so. */
    @Test fun nearest_follows_the_filter() {
        Lang.sw = false
        val m = model(); show(m)
        rule.onNodeWithTag("mchip_water").performScrollTo().performClick(); rule.waitForIdle()
        assertEquals("Water: the nearest water point", "Kapsaret Borehole", text("nearest_name"))
        rule.onNodeWithTag("nearest_peek").performClick(); rule.waitForIdle(); rule.mainClock.advanceTimeBy(600); rule.waitForIdle()
        val inList = { txt: String -> rule.onAllNodes(androidx.compose.ui.test.hasText(txt) and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("nearest_list")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("open: water points only", inList("Kapsaret Borehole") && !inList("Chepkongi Dispensary and Maternity Wing, Kapsaret Ward") && !inList("Chepkongi Dispensary") && !inList("Kapsaret Primary School"))
        // distance first, then how it is: both show
        assertTrue(has("Not working") && has("5.5 km"))
        shot("map-43-nearest-water")
        rule.onNodeWithTag("sheet_handle").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("map_pill_open").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("mchip_mine").performScrollTo().performClick(); rule.waitForIdle()
        assertEquals("Water + My visits leaves nothing: it says so", "Nothing near you matches these filters", text("nearest_name"))
        assertEquals("No water points near you", org.rafikioutreach.pin.ui.nearNone("water", ""))
        Lang.sw = true; assertEquals("Hakuna vituo vya maji karibu nawe", org.rafikioutreach.pin.ui.nearNone("water", "")); Lang.sw = false
        shot("map-44-nearest-none")
    }

    @Test fun long_press_measure_and_new_site() {
        Lang.sw = false
        val m = model(); show(m)
        val mv = map()
        look(mv, 0.52, 35.29, 13.0)
        longPress(mv, 0.53, 35.30)
        assertTrue(tagged("spot_card") && has("New site here") && has("Measure from me"))
        rule.onNodeWithTag("spot_measure").performClick(); rule.waitForIdle()
        assertTrue(has("from you, to the north-east"))
        assertEquals(2, mv.overlays.filterIsInstance<Polyline>().size)
        shot("map-08-measure")
        rule.onNodeWithTag("spot_new").performClick(); rule.waitForIdle()
        rule.waitUntil(4000) { has("New site") && has("Where is it?") }
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
        rule.onNodeWithTag("map_key").performClick(); rule.waitForIdle()
        assertTrue(has("Zimeharibika") && has("Ziara nilizopewa") && has("Bila ziara miezi 6") && has("Rangi"))
        rule.mainClock.advanceTimeBy(5300); rule.waitForIdle()
        look(mv, 0.49, 35.26, 13.0)
        tapMarker(mv, "pin:102")
        assertTrue(has("Inahitaji matengenezo") && has("Ripoti ziara") && has("Maelekezo") && has("Fungua mradi"))
        shot("map-12-sw-card")
        rule.onNodeWithTag("card_x").performClick(); rule.waitForIdle()
        longPress(mv, 0.50, 35.27)
        assertTrue(has("Eneo jipya hapa") && has("Pima kutoka kwangu"))
        shot("map-13-sw-spot")
        assertNotNull(mv)
    }

    // --- the map, decluttered: one pill, the slim handle, the Key button, More, map only; nothing overlaps -------------

    /** The keyboard leaves a short map (about 480 dp): searching shows only the search box and its list, which stays
     *  inside the map; nothing overlaps, in English and Kiswahili. */
    private fun searchShort(sw: Boolean) {
        Lang.sw = sw
        try {
            val m = model(); show(m, h = 480)
            assertTrue("resting: ${overlaps()}", overlaps().isEmpty())
            rule.onNodeWithTag("map_search_input").performClick(); rule.waitForIdle()
            assertTrue("others step aside", !tagged("map_chips") && !tagged("map_pinned") && !tagged("map_key") && !tagged("nearest"))
            rule.onNodeWithTag("map_search_input").performTextInput("Ka"); rule.waitForIdle()
            rule.waitUntil(4000) { tagged("map_sugg") }
            assertTrue("typing: ${overlaps()}", overlaps().isEmpty())
            assertTrue("list inside the map", box("map_sugg")!!.bottom <= box("map_home")!!.bottom + 0.5f)
            shot("map-50-search-short" + if (sw) "-sw" else "")
        } finally { Lang.sw = false }
    }
    @Test fun search_short_screen_en() = searchShort(false)
    @Test fun search_short_screen_sw() = searchShort(true)

    private fun box(tag: String): Rect? = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.takeIf { it.width > 1f && it.height > 1f }
    private fun meet(a: Rect, b: Rect) = a.left < b.right - 0.5f && b.left < a.right - 0.5f && a.top < b.bottom - 0.5f && b.top < a.bottom - 0.5f
    /** Everything drawn over the map, by its tag. */
    private val over = listOf("map_search", "map_search_btn", "map_chips", "map_pill", "map_none", "map_pinned", "here", "here_chip", "map_nosignal", "map_side", "ml_compass", "ml_tray", "map_key", "map_legend", "nearest", "map_card", "spot_card", "map_credit", "map_hint")
    /** Pairs of things over the map whose boxes overlap (none should). */
    private fun overlaps(): List<String> {
        val b = over.mapNotNull { t -> box(t)?.let { t to it } }
        return b.flatMapIndexed { i, (x, a) -> b.drop(i + 1).filter { (_, c) -> meet(a, c) }.map { (y, _) -> "$x/$y" } }
    }
    /** The credit is in the map's box, clear of everything else over the map. */
    private fun creditClear(): Boolean {
        val c = box("map_credit") ?: return false; val home = box("map_home")!!
        return c.top >= home.top && c.bottom <= home.bottom && c.left >= home.left - 1 && c.right <= home.right + 1 && over.filter { it != "map_credit" }.mapNotNull { box(it) }.none { meet(it, c) }
    }
    /** The lowest of the controls at the top (left stack and the column on the right). */
    private fun topLine() = listOf("map_search", "map_search_btn", "map_chips", "map_pill", "map_none", "map_pinned", "here", "map_side", "ml_compass", "ml_follow").mapNotNull { box(it)?.bottom }.max()
    /** How many of the filter's pins and bubbles are outside the free part of the map (under the top controls, left of the column, above the bottom). */
    private fun pinsOutside(mv: MapView): Pair<Int, Int> {
        bitmap()
        val home = box("map_home")!!; val side = box("map_side")!!
        val top = listOf("map_search", "map_chips", "map_pill", "map_pinned", "here").mapNotNull { box(it)?.bottom }.max()
        val bottom = listOfNotNull(box("nearest")?.top, box("map_key")?.top).minOrNull() ?: home.bottom
        var out = 0; val ms = markers(mv)
        rule.runOnUiThread {
            ms.forEach { mk -> val p = mv.projection.toPixels(mk.position, null); val x = home.left + p.x; val y = home.top + p.y
                if (!(x > home.left && x < side.left && y > top && y < bottom)) out++ }
        }
        return ms.size to out
    }
    /** A tap away from the tray and the key's colours: near the left edge, halfway down. */
    private fun tapAway() { rule.onNodeWithTag("map_tap_away").performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.04f, height * 0.5f)) }; rule.waitForIdle() }
    private fun tapMap(mv: MapView) {
        bitmap()
        rule.runOnUiThread { val o = mv.overlays.filterIsInstance<MapEventsOverlay>().first(); o.onSingleTapConfirmed(MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, mv.width / 2f, mv.height * 0.55f, 0), mv) }
        rule.waitForIdle()
    }
    private fun ellipsized(tag: String): Boolean {
        val n = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val r = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        n.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult)?.action?.invoke(r)
        return r.firstOrNull()?.hasVisualOverflow == true
    }
    /** The text is cut short: a line ends with "…", or not all of it is laid out in the lines it may use. */
    private fun cutShort(tag: String): Boolean {
        val n = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val r = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        n.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult)?.action?.invoke(r)
        val l = r.firstOrNull() ?: return true
        return l.didOverflowHeight || (0 until l.lineCount).any { l.isLineEllipsized(it) } || l.getLineEnd(l.lineCount - 1) < l.layoutInput.text.length
    }
    /** A sheet lives in its own window: draw it over the dimmed screen, the way it looks on the phone. */
    private fun sheetShot(name: String) {
        rule.mainClock.advanceTimeBy(700); rule.waitForIdle()
        val b = bitmap(); val c = Canvas(b); c.drawColor(0x66000000)
        val d = org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window?.decorView
        if (d != null && d.width > 0) { c.save(); c.translate(0f, (b.height - d.height).toFloat().coerceAtLeast(0f)); d.draw(c); c.restore() }
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The user's first picture: Water picked. The row folds into one pill, the pinned bar steps aside, Nearest to you is one
     *  slim line, the map fits the water projects; nothing overlaps and the credit shows. */
    @Test fun water_filter_clears_the_screen() {
        Lang.sw = false; longName = true
        val m = model(); show(m)
        val mv = map()
        look(mv, -1.2, 35.4, 6.0)
        assertTrue("resting: chips, pinned bar, Key, More", tagged("map_chips") && tagged("map_pinned") && tagged("map_key") && tagged("ml_more") && !tagged("map_pill"))
        assertTrue("Offline areas and Follow me are under More", !tagged("offline_btn") && !tagged("ml_follow"))
        assertTrue("the pinned bar is one slim line: " + box("map_pinned"), box("map_pinned")!!.height / rule.density.density <= 48f)
        assertEquals("resting: nothing overlaps", emptyList<String>(), overlaps()); assertTrue("resting: the credit shows", creditClear())
        assertTrue("the key sits at the bottom left, above the credit", box("map_key")!!.left < 60 * rule.density.density && box("map_key")!!.bottom <= box("map_credit")!!.top)
        shot("map-20-resting")
        look(mv, 4.0, 40.0, 8.0) // looking somewhere else: the filter brings the map back to its pins
        rule.onNodeWithTag("mchip_water").performScrollTo().performClick(); rule.waitForIdle(); rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        assertTrue("one pill, no row", tagged("map_pill") && !tagged("map_chips"))
        assertTrue("the pill: " + text("map_pill_label"), text("map_pill_label") == "Water" && has(" · 4"))
        assertFalse("the pinned bar steps aside", tagged("map_pinned"))
        assertTrue("Nearest to you is one slim line", tagged("nearest_peek") && has("Nearest:") && box("nearest")!!.height / rule.density.density <= 52f)
        assertEquals("Nearest follows the filter: a water point", "Kapsaret Borehole", text("nearest_name"))
        assertFalse("never cut short with …", cutShort("nearest_name"))
        val (n, outside) = pinsOutside(mv)
        assertTrue("the map fits the water projects, clear of what is over it ($n shown, $outside outside)", n > 0 && outside == 0)
        assertEquals("Water: nothing overlaps", emptyList<String>(), overlaps()); assertTrue("Water: the credit shows", creditClear())
        shot("map-21-water")
        // the pill opens the row; another chip folds it into "Water + Broken"; × clears
        rule.onNodeWithTag("map_pill_open").performClick(); rule.waitForIdle()
        assertTrue(tagged("map_chips") && !tagged("map_pill") && !tagged("map_pinned"))
        rule.onNodeWithTag("mchip_broken").performScrollTo().performClick(); rule.waitForIdle()
        assertEquals("Water + Broken", text("map_pill_label"))
        shot("map-22-two-filters")
        rule.onNodeWithTag("map_pill_x").performClick(); rule.waitForIdle()
        assertTrue("× clears: the row, the pinned bar and the resting sheet are back", tagged("map_chips") && !tagged("map_pill") && tagged("map_pinned") && tagged("sheet_handle") && !tagged("nearest_peek"))
        assertEquals("", m.prefs.ftype)
    }

    /** The user's second picture: Nearest to you swiped up. It stops under every top control, its list scrolls inside,
     *  the key and the pinned bar step aside, the credit sits in the gap. With a filter and without. */
    @Test fun nearest_open_stays_under_the_top_controls() {
        Lang.sw = false; longName = true
        val m = model(); show(m)
        rule.onNodeWithTag("mchip_water").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("nearest_peek").performClick(); rule.waitForIdle(); rule.mainClock.advanceTimeBy(600); rule.waitForIdle()
        assertTrue("open: the five closest", tagged("nearest_list") && has("Kapsaret Borehole") && tagged("sheet_handle"))
        assertFalse("open: the key and the pinned bar step aside", tagged("map_key") || tagged("map_pinned"))
        assertEquals("open with Water: nothing overlaps", emptyList<String>(), overlaps())
        val sheet = box("nearest")!!; val credit = box("map_credit")!!
        assertTrue("the sheet stops under the search, the pill, You’re at and the column: ${sheet.top} > ${topLine()}", sheet.top >= topLine() + 8)
        assertTrue("the credit sits between them, in view", creditClear() && credit.bottom <= sheet.top + 0.5f && credit.top >= topLine())
        assertTrue("the list scrolls inside the sheet", rule.onNodeWithTag("nearest_list").fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange) != null)
        shot("map-23-nearest-open-water")
        rule.onNodeWithTag("sheet_handle").performClick(); rule.waitForIdle()
        assertTrue("and back to the slim line, the key back", tagged("nearest_peek") && tagged("map_key"))
        rule.onNodeWithTag("map_pill_x").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("sheet_handle").performClick(); rule.waitForIdle(); rule.mainClock.advanceTimeBy(600); rule.waitForIdle()
        assertEquals("open, no filter: nothing overlaps", emptyList<String>(), overlaps())
        assertTrue("open, no filter: under the chips and the column", box("nearest")!!.top >= topLine() + 8 && creditClear() && !tagged("map_pinned") && !tagged("map_key"))
        shot("map-24-nearest-open")
    }

    /** The Key button, More (Offline areas, Follow me), and a tap on empty map for the map alone. */
    @Test fun key_more_and_map_only() {
        Lang.sw = false
        val m = model(); show(m)
        val mv = map()
        look(mv, 0.62, 35.05, 9.0)
        // Key: the colours pop up, clear of the search and the column; they close by themselves, or on a tap elsewhere
        rule.onNodeWithTag("map_key").performClick(); rule.waitForIdle()
        assertTrue(tagged("map_legend") && has("Working") && has("Needs repair") && has("Not working") && has("No visit in 6 months"))
        val legend = box("map_legend")!!
        assertTrue("the key covers neither the search nor the column", listOf("map_search", "map_side", "map_chips", "map_credit", "nearest").mapNotNull { box(it) }.none { meet(it, legend) })
        shot("map-25-key")
        rule.mainClock.advanceTimeBy(5300); rule.waitForIdle()
        assertFalse("closes by itself", tagged("map_legend"))
        rule.onNodeWithTag("map_key").performClick(); rule.waitForIdle()
        tapAway(); rule.waitForIdle()
        assertFalse("or on a tap elsewhere", tagged("map_legend")); assertFalse(org.rafikioutreach.pin.ui.MapClean.on)
        // More: a tray with names, under the column, clear of the chips, the search, the key and the credit
        rule.onNodeWithTag("ml_more").performClick(); rule.waitForIdle()
        assertTrue(tagged("ml_tray") && has("Offline areas") && has("Follow me") && tagged("offline_btn") && tagged("ml_follow"))
        val tray = box("ml_tray")!!
        assertTrue("the tray hangs under the column", tray.top >= box("map_side")!!.bottom)
        assertTrue("the tray covers none of the chips, search, pinned bar, You’re at, key or credit", listOf("map_chips", "map_search", "map_pinned", "here", "map_key", "map_credit").mapNotNull { box(it) }.none { meet(it, tray) })
        // map3: smaller tiles, but every touch target stays at least 44 dp
        listOf("offline_btn", "ml_follow", "ml_more", "ml_layers", "ml_me", "map_key").forEach { t -> val b = box(t)!!; assertTrue("$t is 44 dp", b.height / rule.density.density >= 43.5f && b.width / rule.density.density >= 43.5f || t == "offline_btn" || t == "ml_follow" || t == "map_key") }
        assertTrue("tray rows and the Key button are at least 44 dp tall", listOf("offline_btn", "ml_follow", "map_key").all { box(it)!!.height / rule.density.density >= 43.5f })
        shot("map-26-more")
        tapAway(); rule.waitForIdle()
        assertFalse("a tap elsewhere closes it", tagged("ml_tray"))
        // Follow me from the tray: on, it shows in the column (blue) until tapped
        rule.onNodeWithTag("ml_more").performClick(); rule.waitForIdle(); rule.onNodeWithTag("ml_follow").performClick(); rule.waitForIdle()
        assertTrue(org.rafikioutreach.pin.ui.HomeLayers.follow); assertFalse(tagged("ml_tray"))
        val fol = box("ml_follow")!!; assertTrue("following: its button is in the column", fol.top >= box("map_side")!!.bottom && fol.right <= box("map_home")!!.right)
        assertEquals("following: nothing overlaps", emptyList<String>(), overlaps())
        shot("map-27-following")
        rule.onNodeWithTag("ml_follow").performClick(); rule.waitForIdle()
        assertFalse(org.rafikioutreach.pin.ui.HomeLayers.follow); assertFalse("off: back under More", tagged("ml_follow"))
        // a tap on empty map: calm again, with a search button, the column and the credit; "You're at" stays (it matters)
        tapMap(mv)
        assertTrue(org.rafikioutreach.pin.ui.MapClean.on)
        assertTrue("calm", tagged("map_search_btn") && tagged("map_side") && !tagged("map_search") && !tagged("map_chips") && !tagged("map_pinned") && !tagged("nearest") && !tagged("map_key") && tagged("here"))
        assertTrue("the credit still shows", creditClear()); assertEquals(emptyList<String>(), overlaps())
        shot("map-29-map-only")
        tapMap(mv)
        assertTrue("tap again: all back", org.rafikioutreach.pin.ui.MapClean.revealed && tagged("map_search") && tagged("nearest") && tagged("map_key"))
        tapMap(mv); rule.onNodeWithTag("map_search_btn").performClick(); rule.waitForIdle()
        assertTrue("the search button opens the box ready to type; the tiles stay tucked away", org.rafikioutreach.pin.ui.MapClean.on && !tagged("map_chips") &&
            rule.onNodeWithTag("map_search_input").fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Focused) == true)
        // a pin still opens its card; a tap on the map then only puts the card away
        look(mv, 0.62, 35.05, 13.0); tapMarker(mv, "pin:103")
        assertEquals("Turbo Spring", text("card_title")); assertEquals("card: nothing overlaps", emptyList<String>(), overlaps())
        assertTrue("searching is over: the round button is back", tagged("map_search_btn"))
        tapMap(mv)
        assertTrue(!tagged("map_card") && org.rafikioutreach.pin.ui.MapClean.on)
        // Offline areas (under More) opens Save this area for offline
        rule.onNodeWithTag("ml_more").performClick(); rule.waitForIdle(); rule.onNodeWithTag("offline_btn").performClick(); rule.waitForIdle()
        rule.waitUntil(4000) { tagged("offline_sheet") }
        sheetShot("map-28-offline-from-more")
    }

    /** Kiswahili at 360 dp and dark: the pill, the slim line, the key and the tray, nothing overlapping. */
    @Config(qualifiers = "w360dp-h740dp-xxhdpi")
    @Test fun kiswahili_dark_360() {
        Lang.sw = true; longName = true
        val m = model(); show(m, dark = true, w = 360, h = 740)
        // You're at: the whole long name (no …), then after 5 seconds a chip, leaving the map room; a tap opens it again
        assertTrue("here: ${tagged("here")} chip: ${tagged("here_chip")}", tagged("here"))
        assertFalse("the name is whole", cutShort("here_name"))
        assertTrue(text("here_name"), text("here_name").startsWith("Chepkongi Dispensary and Maternity Wing"))
        shot("map-34-sw-dark-360-here")
        rule.mainClock.advanceTimeBy(5300); rule.waitForIdle()
        assertTrue("folded into a chip", tagged("here_chip") && !tagged("here"))
        assertEquals(emptyList<String>(), overlaps())
        shot("map-35-sw-dark-360-here-chip")
        rule.onNodeWithTag("here_chip").performClick(); rule.waitForIdle()
        assertTrue(tagged("here")); rule.mainClock.advanceTimeBy(5300); rule.waitForIdle(); assertTrue(tagged("here_chip"))
        rule.onNodeWithTag("mchip_home").performScrollTo().performClick(); rule.waitForIdle()
        assertEquals("Makao ya watoto", text("map_pill_label")); assertTrue(has("Karibu zaidi:") && has("Rangi"))
        rule.onNodeWithTag("map_pill_open").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("mchip_due").performScrollTo().performClick(); rule.waitForIdle()
        assertTrue("two long names: the label ends with …, × in reach", text("map_pill_label") == "Makao ya watoto + Inasubiri ziara" && box("map_pill_x")!!.right <= box("map_side")!!.left)
        assertEquals("filter: nothing overlaps", emptyList<String>(), overlaps()); assertTrue(creditClear())
        shot("map-30-sw-dark-360-filter")
        rule.onNodeWithTag("nearest_peek").performClick(); rule.waitForIdle(); rule.mainClock.advanceTimeBy(600); rule.waitForIdle()
        assertEquals("open: nothing overlaps", emptyList<String>(), overlaps())
        assertTrue(box("nearest")!!.top >= topLine() + 8 && creditClear())
        shot("map-31-sw-dark-360-open")
        rule.onNodeWithTag("sheet_handle").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("ml_more").performClick(); rule.waitForIdle()
        assertTrue(has("Ramani bila mtandao") && has("Nifuate"))
        assertFalse("nothing was switched on meanwhile", org.rafikioutreach.pin.ui.HomeLayers.follow)
        val tray = box("ml_tray")!!
        assertTrue("the tray fits on screen and covers nothing", tray.left >= 0 && listOf("map_pill", "map_search", "here", "map_key", "map_credit").mapNotNull { box(it) }.none { meet(it, tray) })
        shot("map-32-sw-dark-360-more")
        tapAway(); assertFalse("a tap away only closes the tray", tagged("ml_tray") || org.rafikioutreach.pin.ui.HomeLayers.follow); rule.waitForIdle()
        rule.onNodeWithTag("map_key").performClick(); rule.waitForIdle()
        assertTrue(has("Bila ziara miezi 6") && listOf("map_search", "map_side").mapNotNull { box(it) }.none { meet(it, box("map_legend")!!) })
        assertTrue("the key shows groups and you as well", tagged("key_cluster") && tagged("key_you") && has("Wewe"))
        shot("map-33-sw-dark-360-key")
    }

    // --- map3: calm by default, pins that talk, more animation ---------------------------------------------------------

    private val Calm get() = org.rafikioutreach.pin.ui.MapClean
    private fun back() { rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle() }
    private fun labels(mv: MapView) = mv.overlays.filterIsInstance<org.rafikioutreach.pin.ui.PinLabels>().first()
    private fun pinBitmap(mv: MapView, id: String) = (mv.overlays.filterIsInstance<Marker>().first { it.id == id }.icon as android.graphics.drawable.BitmapDrawable).bitmap
    private fun near(c: Int, want: Int, tol: Int = 40) = listOf(16, 8, 0).all { sh -> kotlin.math.abs(((c shr sh) and 255) - ((want shr sh) and 255)) <= tol } && (c ushr 24) > 200
    private fun longPressMarker(mv: MapView, id: String) {
        bitmap()
        rule.runOnUiThread {
            val mk = mv.overlays.filterIsInstance<Marker>().first { it.id == id }
            val pt = mv.projection.toPixels(mk.position, null)
            assertTrue("the pin takes the long press", mk.onLongPress(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, pt.x.toFloat(), pt.y.toFloat(), 0), mv))
        }
        rule.waitForIdle()
    }
    private fun calmTiles() = tagged("map_search_btn") && tagged("map_side") && !tagged("map_search") && !tagged("map_chips") && !tagged("map_pinned") && !tagged("nearest") && !tagged("map_key")
    private fun revealedTiles() = tagged("map_search") && tagged("map_chips") && tagged("nearest") && !tagged("map_search_btn")

    /** The map opens calm: pins, a round search button, the column; the one-time hint. A tap reveals the chips, the pinned
     *  bar, Nearest to you and the Key; another tap hides them; back hides them too. Nothing overlaps either way. */
    private fun calmRevealHide(h: Int, sw: Boolean) {
        Lang.sw = sw
        try {
            val m = model(); show(m, h = h, calm = true)
            val mv = map()
            val sfx = (if (h < 600) "-short" else "") + (if (sw) "-sw" else "")
            assertTrue("calm by default", Calm.on && calmTiles())
            assertTrue("You’re at still shows (within 200 m)", tagged("here") || tagged("here_chip"))
            assertTrue("the one-time hint", tagged("map_hint") && has(if (sw) "Gusa ramani kuona vichujio" else "Tap the map for filters"))
            assertTrue("remembered", app.getSharedPreferences("map_style", 0).getBoolean("calm_hint", false))
            assertEquals("calm: nothing overlaps", emptyList<String>(), overlaps()); assertTrue(creditClear())
            shot("map-60-calm$sfx")
            tapMap(mv)
            assertTrue("a tap reveals the tiles", Calm.revealed && revealedTiles() && !tagged("map_hint"))
            if (h >= 600) assertTrue("and the pinned bar and Key", tagged("map_pinned") && tagged("map_key"))
            assertEquals("revealed: nothing overlaps", emptyList<String>(), overlaps()); assertTrue(creditClear())
            shot("map-61-revealed$sfx")
            tapMap(mv)
            assertTrue("another tap hides them", Calm.on && calmTiles())
            tapMap(mv); assertTrue(Calm.revealed)
            back()
            assertTrue("back hides the revealed tiles, still on the map", Calm.on && calmTiles() && tagged("map_home"))
            assertFalse(rule.activity.isFinishing)
        } finally { Lang.sw = false }
    }
    @Test fun calm_tap_reveals_tap_hides_844() = calmRevealHide(844, false)
    @Test fun calm_tap_reveals_tap_hides_844_sw() = calmRevealHide(844, true)
    @Test fun calm_tap_reveals_tap_hides_480() = calmRevealHide(480, false)
    @Test fun calm_tap_reveals_tap_hides_480_sw() = calmRevealHide(480, true)

    /** Calm keeps what is active: a filter's pill, the search box while searching, the card. The bottom bar starts it over. */
    @Test fun calm_keeps_what_is_active_and_starts_over() {
        Lang.sw = false
        val m = model(); show(m, calm = true)
        val mv = map()
        tapMap(mv); rule.onNodeWithTag("mchip_water").performScrollTo().performClick(); rule.waitForIdle()
        tapMap(mv)
        assertTrue("calm with a filter: its pill, no chips, no Nearest", Calm.on && tagged("map_pill") && !tagged("map_chips") && !tagged("nearest") && !tagged("map_key"))
        assertEquals(emptyList<String>(), overlaps())
        shot("map-62-calm-filter")
        rule.onNodeWithTag("map_search_btn").performClick(); rule.waitForIdle()
        assertTrue("searching: the search box", tagged("map_search") && !tagged("map_search_btn") && Calm.on)
        rule.onNodeWithTag("map_search_input").performTextInput("Turbo"); rule.waitUntil(3000) { tagged("sugg_p_103") }
        rule.onNodeWithTag("sugg_p_103").performClick(); rule.waitForIdle()
        assertTrue("a card: it shows, calm or not", tagged("map_card") && Calm.on && tagged("map_search_btn"))
        assertEquals(emptyList<String>(), overlaps())
        rule.onNodeWithTag("card_x").performClick(); rule.waitForIdle()
        tapMap(mv); assertTrue(Calm.revealed)
        rule.onNodeWithTag("nav_list").performClick(); rule.waitForIdle(); rule.onNodeWithTag("nav_map").performClick(); rule.waitForIdle()
        rule.waitUntil(4000) { tagged("map_home") }
        assertTrue("the bottom bar starts the map over: calm, no filter", Calm.on && calmTiles() && !tagged("map_pill"))
    }

    /** Each pin: the kind's icon inside, a ring for how it is, a dot when a visit or water test is due. Pictures are kept by look. */
    @Test fun pin_icons_per_condition() {
        Lang.sw = false
        val m = model(); show(m)
        val mv = map()
        look(mv, 0.55, 35.1, 15.0)
        look(mv, 0.47, 35.25, 15.0)
        val d = rule.density.density
        fun ring(id: String): Int { val b = pinBitmap(mv, id); return b.getPixel(b.width / 2 + (10f * d).toInt(), b.height / 2) }
        fun dot(id: String): Int { val b = pinBitmap(mv, id); val o = (11.5f * 0.72f * d).toInt(); return b.getPixel(b.width / 2 + o, b.height / 2 - o) }
        fun inked(id: String): Int { val b = pinBitmap(mv, id); val r = (6f * d).toInt(); var n = 0
            for (x in -r..r) for (y in -r..r) { val c = b.getPixel(b.width / 2 + x, b.height / 2 + y); if (((c shr 16) and 255) < 90 && ((c shr 8) and 255) < 90) n++ }; return n }
        assertTrue("not working: red ring", near(ring("pin:101"), org.rafikioutreach.pin.ui.condArgb(PinCond.BAD)))
        assertTrue("its icon is drawn inside", inked("pin:101") > 10)
        assertTrue("a water point is due a water test: the blue dot", near(dot("pin:101"), org.rafikioutreach.pin.ui.PinArt.DUE_ARGB))
        look(mv, 0.5143, 35.2698, 15.0)
        assertTrue("needs repair: amber ring", near(ring("pin:102"), org.rafikioutreach.pin.ui.condArgb(PinCond.REPAIR)))
        look(mv, 0.63, 35.05, 15.0)
        assertTrue("working: green ring", near(ring("pin:103"), org.rafikioutreach.pin.ui.condArgb(PinCond.OK)))
        look(mv, 0.58, 34.62, 15.0)
        assertTrue("not visited in 6 months: grey ring, due", near(ring("pin:104"), org.rafikioutreach.pin.ui.condArgb(PinCond.OLD)) && near(dot("pin:104"), org.rafikioutreach.pin.ui.PinArt.DUE_ARGB))
        look(mv, 0.2, 35.1, 15.0)
        assertFalse("a farm visited 10 days ago: nothing due", near(dot("pin:106"), org.rafikioutreach.pin.ui.PinArt.DUE_ARGB))
        assertTrue("the leaf is drawn", inked("pin:106") > 10)
        // kept by type, condition and due: two working, due water points share one picture
        look(mv, 0.75, 35.1, 9.0); look(mv, 0.75, 35.1, 15.0)
        look(mv, 0.63, 35.05, 15.0); val a = pinBitmap(mv, "pin:103")
        look(mv, 0.87, 35.12, 15.0); val b = pinBitmap(mv, "pin:107")
        assertTrue("one picture per look", a === b)
        shot("map-63-pins-z15")
    }

    /** A group is a donut split by how its projects are, with the count in the middle. */
    @Test fun cluster_donut() {
        Lang.sw = false
        val m = model(); show(m)
        val mv = map()
        look(mv, -1.2, 35.4, 6.0)
        rule.waitUntil(5000) { markers(mv).any { it.id.startsWith("cluster:") } }
        val g = markers(mv).first { it.id.startsWith("cluster:") && it.snippet == PinCond.BAD }
        val parts = g.subDescription.split(",").map { it.toInt() }
        assertEquals("the parts add up to the count", g.title.toInt(), parts.sum())
        assertTrue("a mix: ${parts}", parts[3] >= 1 && parts[2] >= 1 && parts[0] >= 1)
        val b = (g.icon as android.graphics.drawable.BitmapDrawable).bitmap
        val d = rule.density.density; val r = (15f - 2.25f) * d
        val seen = (0 until 72).map { k -> val a = Math.toRadians(k * 5.0); b.getPixel((b.width / 2 + r * kotlin.math.cos(a)).toInt(), (b.height / 2 + r * kotlin.math.sin(a)).toInt()) }
        listOf(PinCond.BAD to parts[3], PinCond.REPAIR to parts[2], PinCond.OLD to parts[1], PinCond.OK to parts[0]).filter { it.second > 0 }.forEach { (c, _) ->
            assertTrue("the donut shows $c", seen.any { near(it, org.rafikioutreach.pin.ui.condArgb(c), 30) })
        }
        assertTrue("not working starts at the top", near(b.getPixel(b.width / 2 + 1, (b.height / 2 - r).toInt()), org.rafikioutreach.pin.ui.condArgb(PinCond.BAD), 30))
        shot("map-64-donuts")
        // a tap zooms smoothly into the group
        val z = mv.zoomLevelDouble
        tapMarker(mv, g.id); rule.mainClock.advanceTimeBy(1000); rule.waitForIdle()
        assertTrue(mv.zoomLevelDouble >= z + 1)
    }

    /** Pins on the very same spot: a tap on their group fans them out, each its own pin. */
    @Test fun same_spot_fans_out() {
        Lang.sw = false; twins = true
        val m = model(); show(m)
        val mv = map()
        look(mv, 0.63, 35.05, 16.0)
        val g = markers(mv).first { it.id.startsWith("cluster:2:") }
        tapMarker(mv, g.id); bitmap()
        assertTrue("both pins stand alone: ${markers(mv).map { it.id }}", markers(mv).count { it.id == "pin:103" || it.id == "pin:111" } == 2)
        val a = markers(mv).first { it.id == "pin:103" }; val b = markers(mv).first { it.id == "pin:111" }
        val pa = mv.projection.toPixels(a.position, null); val pb = mv.projection.toPixels(b.position, null)
        assertTrue("fanned apart: ${pa} ${pb}", kotlin.math.hypot((pa.x - pb.x).toDouble(), (pa.y - pb.y).toDouble()) > 40 * rule.density.density)
        assertTrue(markers(mv).none { it.id.startsWith("cluster:") })
        shot("map-65-fanned")
        tapMarker(mv, "pin:111")
        assertEquals("Turbo Spring Kiosk", text("card_title"))
    }

    /** From zoom 13 short names sit under the pins, never over another name or pin; under 13, none. */
    @Test fun labels_at_zoom_13_and_above() {
        Lang.sw = false
        val m = model(); show(m)
        val mv = map()
        look(mv, 0.49, 35.26, 12.0)
        assertTrue("zoom 12: no names", labels(mv).shown.isEmpty())
        look(mv, 0.49, 35.26, 13.0)
        assertTrue("zoom 13: names: ${labels(mv).shown}", labels(mv).shown.contains("102"))
        look(mv, 0.4725, 35.2525, 15.0)
        assertTrue("zoom 15: the two Kapsaret pins are close, one name gives way: ${labels(mv).shown}", labels(mv).shown.contains("101") && !labels(mv).shown.contains("109"))
        look(mv, 0.4725, 35.2525, 16.0)
        val l = labels(mv); val bx = l.boxes()
        assertTrue("zoom 16: both Kapsaret names: ${l.shown}", l.shown.containsAll(listOf("101", "109")))
        for (i in bx.indices) for (j in i + 1 until bx.size) assertFalse("names never overlap", RectF.intersects(bx[i], bx[j]))
        val pins = markers(mv).map { mv.projection.toPixels(it.position, null) }
        val pr = 14f * rule.density.density
        bx.forEach { r -> assertTrue("a name sits under its pin, clear of the others", pins.count { p -> RectF.intersects(r, RectF(p.x - pr, p.y - pr, p.x + pr, p.y + pr)) } == 0) }
        shot("map-66-names-z16")
        // the rule itself: crowded pins show what fits and hide the rest
        val xs = floatArrayOf(100f, 120f, 300f); val ys = floatArrayOf(100f, 100f, 100f); val ws = floatArrayOf(120f, 120f, 80f)
        val show = BooleanArray(3); val boxes = FloatArray(12)
        val n = org.rafikioutreach.pin.ui.placeLabels(3, xs, ys, ws, 30f, 20f, 15f, 1000f, 1000f, show, boxes)
        assertEquals(2, n); assertTrue(show[0] && !show[1] && show[2])
        assertEquals("Chepkongi Dispensary and…", org.rafikioutreach.pin.ui.shortName("Chepkongi Dispensary and Maternity Wing, Kapsaret Ward", 26))
        assertEquals("Kapsaret Borehole", org.rafikioutreach.pin.ui.shortName("Kapsaret Borehole"))
    }

    /** A tapped pin lifts and grows, the others dim, the map eases so it sits above the card; back puts it all back. */
    @Test fun tap_pin_lifts_and_sits_above_the_card() {
        Lang.sw = false
        val m = model(); show(m, calm = true)
        val mv = map()
        look(mv, 0.62, 35.05, 13.0)
        // put Turbo Spring low on the map, where the card will be
        rule.runOnUiThread { val p = mv.projection.fromPixels(mv.width / 2, (mv.height * 0.15).toInt()); mv.controller.setCenter(GeoPoint(p.latitude, p.longitude)) }
        rule.waitForIdle(); bitmap()
        tapMarker(mv, "pin:103"); rule.waitUntil(3000) { tagged("map_card") }; rule.mainClock.advanceTimeBy(500); rule.waitForIdle(); bitmap()
        val chosen = markers(mv).first { it.id == "pin:103" } as org.rafikioutreach.pin.ui.PinMarker
        assertTrue("it lifts and is the big pin", chosen.big && chosen.lift > 0f && chosen.alpha == 1f)
        assertTrue("the others dim", markers(mv).filter { it.id != "pin:103" }.all { it.alpha < 1f })
        val home = box("map_home")!!; val card = box("map_card")!!
        val y = rule.runOnUiThread<Float> { home.top + mv.projection.toPixels(chosen.position, null).y }
        assertTrue("the pin sits above the card: $y < ${card.top}", y < card.top - 10)
        shot("map-67-pin-open")
        back()
        assertFalse(tagged("map_card"))
        bitmap()
        assertTrue("closed: no pin dimmed", markers(mv).all { it.alpha == 1f } && markers(mv).none { (it as org.rafikioutreach.pin.ui.PinMarker).big })
    }

    /** A long press on a pin: Report a visit, Directions and Open in a small popover by the pin. Back closes it. */
    @Test fun long_press_pin_quick_actions() {
        Lang.sw = false
        val m = model(); show(m)
        val mv = map()
        look(mv, 0.62, 35.05, 13.0)
        longPressMarker(mv, "pin:103")
        assertTrue("the quick actions", tagged("pin_quick") && has("Report a visit") && has("Directions") && tagged("quick_open"))
        assertFalse("not the long press on the map", tagged("spot_card") || tagged("map_card"))
        val q = box("pin_quick")!!; val home = box("map_home")!!
        assertTrue("inside the map", q.left >= home.left && q.right <= home.right && q.top >= home.top && q.bottom <= home.bottom)
        shot("map-68-quick")
        back()
        assertTrue("back closes only the popover", !tagged("pin_quick") && tagged("map_home") && Calm.revealed)
        longPressMarker(mv, "pin:103"); rule.onNodeWithTag("quick_dir").performClick(); rule.waitForIdle()
        assertTrue(shadowOf(rule.activity).nextStartedActivity.dataString!!.contains("destination=0.630000,35.050000"))
        assertFalse(tagged("pin_quick"))
        longPressMarker(mv, "pin:103"); rule.onNodeWithTag("quick_visit").performClick()
        rule.waitUntil(4000) { has("I’ve arrived") }
    }

    /** Kiswahili: the popover and the hint. */
    @Test fun long_press_kiswahili_dark() {
        Lang.sw = true
        val m = model(); show(m, dark = true)
        val mv = map()
        look(mv, 0.62, 35.05, 13.0)
        longPressMarker(mv, "pin:103")
        assertTrue(has("Ripoti ziara") && has("Maelekezo") && has("Fungua"))
        shot("map-69-quick-sw-dark")
        Lang.sw = false
    }

    /** The six key pictures at 360 dp, in English and Kiswahili, light and dark: calm, revealed, a pin open, a group,
     *  names at zoom, and the quick actions; the sun theme once. Nothing overlaps; no word is cut. */
    private fun tour360(sw: Boolean, dark: Boolean) {
        Lang.sw = sw
        try {
            val m = model(); show(m, dark = dark, w = 360, h = 760, calm = true)
            val mv = map()
            val sfx = (if (sw) "-sw" else "-en") + (if (dark) "-dark" else "-light")
            look(mv, 0.62, 35.1, 9.0)
            assertEquals(emptyList<String>(), overlaps()); shot("map3-1-calm$sfx")
            tapMap(mv); rule.waitForIdle()
            assertEquals(emptyList<String>(), overlaps()); shot("map3-2-revealed$sfx")
            tapMap(mv)
            look(mv, 0.62, 35.05, 13.0); tapMarker(mv, "pin:103"); rule.waitUntil(3000) { tagged("map_card") }; rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
            assertEquals(emptyList<String>(), overlaps()); shot("map3-3-pin-open$sfx")
            rule.onNodeWithTag("card_x").performClick(); rule.waitForIdle()
            look(mv, -1.2, 35.4, 6.0); shot("map3-4-groups$sfx")
            look(mv, 0.4725, 35.2525, 15.0); shot("map3-5-names$sfx")
            if (!dark && !sw) { Look.mode = "sun"; rule.waitForIdle(); look(mv, 0.62, 35.1, 9.0); tapMap(mv); shot("map3-6-sun$sfx"); Look.mode = "" }
        } finally { Lang.sw = false; Look.mode = "" }
    }
    @Config(qualifiers = "w360dp-h760dp-xxhdpi") @Test fun shots_360_en_light() = tour360(false, false)
    @Config(qualifiers = "w360dp-h760dp-xxhdpi") @Test fun shots_360_en_dark() = tour360(false, true)
    @Config(qualifiers = "w360dp-h760dp-xxhdpi") @Test fun shots_360_sw_light() = tour360(true, false)
    @Config(qualifiers = "w360dp-h760dp-xxhdpi") @Test fun shots_360_sw_dark() = tour360(true, true)
}

package org.rafikioutreach.pin

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.activity.compose.setContent
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.rafikioutreach.pin.data.Fix
import org.rafikioutreach.pin.data.LatLng
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.ui.HomeLayers
import org.rafikioutreach.pin.ui.MapClean
import org.rafikioutreach.pin.ui.Motion
import org.rafikioutreach.pin.ui.Nav
import org.rafikioutreach.pin.ui.Onb
import org.rafikioutreach.pin.ui.RafikiTheme
import org.rafikioutreach.pin.ui.VisitDrafts
import org.rafikioutreach.pin.ui.NewSiteDrafts
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.time.Instant

/** Back, refresh and "start over" (feat-nava): the owner's sequences, walked like a user. Screenshots "nav-…". */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class NavTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @After fun reset() { home = ""; uganda = false; NewSiteDrafts.remove(app); Lang.sw = false; Motion.on = true; Nav.now = { System.currentTimeMillis() }; HomeLayers.reset(); MapClean.revealed = false; Onb.enabled = true }

    // 20 projects around Eldoret: enough to scroll; 10 of them need a repair
    private fun proj(i: Int) = JSONObject().put("id", "${100 + i}").put("title", "Site ${100 + i} Borehole").put("country", "Kenya").put("ck", "ke").put("area", "Eldoret")
        .put("type", "Borehole well").put("status", "Completed").put("pump", "manual").put("gps", JSONArray().put(0.40 + i * 0.012).put(35.10 + i * 0.011))
    private fun ago(d: Long) = Instant.now().minusSeconds(d * 86400).toString()
    // home country and two pinned projects in Uganda, for the map's default view
    private var home = ""; private var uganda = false
    private fun ug(id: String, lat: Double, lng: Double) = JSONObject().put("id", id).put("title", "Uganda $id").put("country", "Uganda").put("ck", "ug").put("area", "Mbale")
        .put("type", "Borehole well").put("status", "Completed").put("gps", JSONArray().put(lat).put(lng))
    private fun snapshot() = JSONObject().put("ok", true).put("who", "Reid Anderson").put("admin", true).put("reviewOpen", 0)
        .put("me", JSONObject().put("kind", "app").put("home", home))
        .put("projects", JSONArray().apply { (1..20).forEach { put(proj(it)) }; if (uganda) { put(ug("301", 1.08, 34.17)); put(ug("302", 0.35, 32.58)) } })
        .put("repairs", JSONObject().apply { (11..20).forEach { put("${100 + it}", JSONObject().put("state", "repair").put("by", "Bertin").put("at", ago(3)).put("urgency", "month").put("t", "Site ${100 + it} Borehole")) } })
        .put("visits", JSONObject()).put("jobs", JSONArray()).put("assigned", JSONArray()).put("confirmed", JSONObject())

    private fun model(): AppModel {
        Onb.enabled = false; Motion.on = false
        Prefs(app).apply { pin = "9999"; name = "Reid Anderson"; saveSnapshot(snapshot()); filter = "repairs"; country = ""; ftype = ""; onb = true; lang = if (Lang.sw) "sw" else "en"; mePane = "team"; phoneAlerts = false }
        org.rafikioutreach.pin.ui.clearDrafts()
        (1..20).forEach { VisitDrafts.remove(app, "${100 + it}") }
        val m = AppModel(app)
        m.api.transport = { method, url, _, _ ->
            when {
                url.contains("activity=1") -> 200 to "{\"ok\":true,\"activity\":[]}"
                url.contains("team=1") || url.contains("view=team") -> 200 to "{\"ok\":true,\"team\":[]}"
                url.contains("view=") -> 200 to "{\"ok\":true,\"items\":[]}"
                url.contains("history=") -> 200 to "{\"ok\":true,\"notes\":[]}"
                url.contains("pin-auth/me") -> 200 to "{\"ok\":true,\"available\":false}"
                url.contains("version.json") -> 0 to ""
                url.contains("?id=") -> 200 to "{\"ok\":true,\"last\":null,\"canUndo\":false,\"visits\":[]}"
                method == "GET" -> 200 to snapshot().toString()
                else -> 200 to "{\"ok\":true,\"updateId\":\"9001\"}"
            }
        }
        m.locator.pretend(Fix(LatLng(0.4150, 35.1100), 8, System.currentTimeMillis()))
        return m
    }
    private fun start(dark: Boolean = false): AppModel {
        val m = model()
        rule.setContent { RafikiTheme(dark = dark) { Box(Modifier.size(390.dp, 844.dp)) { App(m) } } }
        waitTag("nav_map"); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        return m
    }

    // --- helpers -------------------------------------------------------------------------------------------------
    private fun tagged(t: String) = rule.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty()
    private fun has(t: String) = rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun exact(t: String) = rule.onAllNodesWithText(t).fetchSemanticsNodes().isNotEmpty()
    private fun waitTag(t: String) = rule.waitUntil(8000) { tagged(t) }
    private fun tap(t: String) { rule.onNodeWithTag(t).performScrollTo().performClick(); settle() }
    private fun nav(k: String) { rule.onNodeWithTag("nav_$k").performClick(); settle() }
    private fun settle() { rule.mainClock.advanceTimeBy(800); rule.waitForIdle() }
    private fun text(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.let { c ->
        c.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString("") { it.text } ?: ""
    }
    /** The phone's back: to the window in front (a sheet or dialog is its own window), as the system does. */
    private fun back() {
        rule.runOnUiThread {
            val d = ShadowDialog.getLatestDialog()
            @Suppress("DEPRECATION")
            if (d != null && d.isShowing) d.onBackPressed() else rule.activity.onBackPressedDispatcher.onBackPressed()
        }
        settle()
    }
    private fun sheetOpen() = ShadowDialog.getLatestDialog()?.isShowing == true
    private fun shot(name: String) {
        settle()
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); val c = Canvas(b); v.draw(c)
        val d = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView
        if (d != null && d.width > 0) { c.drawColor(0x66000000); c.save(); c.translate(0f, (v.height - d.height).toFloat().coerceAtLeast(0f)); d.draw(c); c.restore() }
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun maps(v: android.view.View, acc: MutableList<MapView> = mutableListOf()): List<MapView> {
        if (v is MapView) acc += v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) maps(v.getChildAt(i), acc)
        return acc
    }
    private fun map(): MapView { rule.waitForIdle(); return maps(rule.activity.window.decorView).first { it.isAttachedToWindow } }
    private data class View(val z: Double, val lat: Double, val lng: Double)
    private fun view(mv: MapView): View { var r: View? = null; rule.runOnUiThread { r = View(mv.zoomLevelDouble, mv.mapCenter.latitude, mv.mapCenter.longitude) }; return r!! }
    private fun look(mv: MapView, lat: Double, lng: Double, z: Double) { rule.runOnUiThread { mv.controller.setZoom(z); mv.controller.setCenter(GeoPoint(lat, lng)) }; settle() }
    private fun drawMap() { val v = rule.activity.window.decorView; v.draw(Canvas(Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888))); rule.waitForIdle() }
    private fun assertSameView(why: String, a: View, b: View) {
        assertEquals("$why: zoom", a.z, b.z, 0.05); assertEquals("$why: lat", a.lat, b.lat, 0.01); assertEquals("$why: lng", a.lng, b.lng, 0.01)
    }
    private fun homeView(): View { waitTag("map_home"); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle(); drawMap(); settle(); return view(map()) }

    // --- 1. Office: a tile expands to its tab; back is the grid, not Map home -------------------------------------
    private fun officeTileBack(sw: Boolean) {
        Lang.sw = sw; start()
        nav("office"); waitTag("o_repairs")
        assertFalse(tagged("office_strip"))
        tap("o_repairs"); waitTag("office_strip")
        shot(if (sw) "nav-01-office-tab-sw" else "nav-01-office-tab")
        back()
        assertTrue("back from an Office tab shows the grid", tagged("o_repairs") && !tagged("office_strip"))
        assertFalse("not Map home", tagged("map_home"))
        rule.onNodeWithTag("nav_office").assertIsSelected()
        shot(if (sw) "nav-02-office-grid-sw" else "nav-02-office-grid")
        // the grid is the Office's own first state: back again leaves the Office for Map home
        back(); waitTag("map_home")
    }
    // --- tapping the tab you are already on: it starts over in place, with no fade through (no blink) ---------------
    @Test fun same_tab_again_does_not_blink() {
        start()
        for ((k, root) in listOf("me" to "me_hero", "list" to "projects", "office" to "o_repairs")) {
            nav(k); waitTag(root)
            rule.mainClock.autoAdvance = false
            rule.onNodeWithTag("nav_$k").performClick()
            rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeByFrame()
            // a fade through would show the old and the new screen together (or neither) for a few frames
            assertEquals("$k: one screen, straight away", 1, rule.onAllNodesWithTag(root).fetchSemanticsNodes().size)
            rule.mainClock.autoAdvance = true; settle()
            assertEquals("$k: still one screen", 1, rule.onAllNodesWithTag(root).fetchSemanticsNodes().size)
            rule.onNodeWithTag("nav_$k").assertIsSelected()
        }
        // from a deeper screen the same tab still goes back to its first screen
        nav("office"); tap("o_repairs"); waitTag("office_strip")
        nav("office"); waitTag("o_repairs"); assertFalse(tagged("office_strip"))
    }

    @Test fun office_tile_then_back_is_the_grid() = officeTileBack(false)
    @Test fun office_tile_then_back_is_the_grid_kiswahili() = officeTileBack(true)

    // --- 2. Project: History, back = Pin location; back again = the list, scrolled where it was ---------------------
    @Test fun project_history_back_then_list_where_it_was() {
        Lang.sw = false; val m = start(); m.prefs.filter = "all"
        nav("list"); waitTag("projects")
        rule.onNodeWithTag("chip_all").performScrollTo().performClick(); settle()
        rule.onNodeWithTag("projects").performScrollToNode(hasText("Site 117 Borehole")); settle()
        assertFalse("scrolled past the head", tagged("projects_head"))
        rule.onNodeWithText("Site 117 Borehole").performClick(); settle()
        tap("tab_hist"); rule.waitUntil(5000) { has("Notes on this project") }
        back()
        assertTrue("back from History is Pin location", has("Move the map so the cross") && !has("Notes on this project"))
        tagged("hback").let { assertTrue("still on the project", it) }
        back()
        waitTag("projects")
        assertTrue("the list, where it was", has("Site 117 Borehole") && !tagged("projects_head"))
        rule.onNodeWithTag("nav_list").assertIsSelected()
        shot("nav-03-list-kept-after-back")
    }

    // --- 3. Map: pan and zoom, Projects, Map again = the default view; a card closes with back ---------------------
    @Test fun map_starts_over_and_back_closes_the_card_only() {
        Lang.sw = false; start()
        nav("map"); val home = homeView()
        val mv = map(); look(mv, 0.55, 35.25, 16.0)
        assertEquals(16.0, view(mv).z, 0.01)
        // a filter and the map alone are also put away
        nav("list"); waitTag("projects"); nav("map")
        assertSameView("back via the bottom bar: the default view", home, homeView())
        // a pin's card: back closes the card only
        val mv2 = map(); look(mv2, 0.40 + 5 * 0.012, 35.10 + 5 * 0.011, 16.0); drawMap()
        rule.runOnUiThread {
            val mk = mv2.overlays.filterIsInstance<Marker>().first { it.id == "pin:105" }
            val pt = mv2.projection.toPixels(mk.position, null)
            assertTrue(mk.onSingleTapConfirmed(MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, pt.x.toFloat(), pt.y.toFloat(), 0), mv2))
        }
        settle(); waitTag("map_card"); shot("nav-04-map-card")
        back()
        assertFalse("the card closed", tagged("map_card"))
        assertTrue("still on the map", tagged("map_home")); rule.onNodeWithTag("nav_map").assertIsSelected()
        // nothing open: back on Map home asks before leaving, it never quits
        back(); back()
        assertFalse(rule.activity.isFinishing)
    }

    @Test fun map_filter_and_layers_start_over() {
        Lang.sw = false; val m = start()
        nav("map"); waitTag("map_home")
        app.getSharedPreferences("map_style", 0).edit().putString("mapf", "broken").apply(); MapClean.revealed = true; HomeLayers.heat = "repairs"
        nav("list"); nav("map")
        assertEquals("", app.getSharedPreferences("map_style", 0).getString("mapf", "x"))
        assertFalse(MapClean.revealed); assertEquals("", HomeLayers.heat)
        assertTrue("calm again: the search button, no chips or pill", tagged("map_search_btn") && !tagged("map_chips") && !tagged("map_pill"))
        assertTrue("saved choices stay", m.prefs.pin == "9999")
    }

    @Test fun map_resets_after_five_minutes_away_not_before() {
        Lang.sw = false; start()
        nav("map"); val home = homeView()
        look(map(), 0.55, 35.25, 16.0)
        // two minutes away: as it was
        var t0 = System.currentTimeMillis(); Nav.now = { t0 }
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED); Nav.now = { t0 + 2 * 60_000L }
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED); settle()
        assertEquals("a short break keeps the map", 16.0, view(map()).z, 0.01)
        // six minutes away: the default view again
        t0 = System.currentTimeMillis(); Nav.now = { t0 }
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED); Nav.now = { t0 + 6 * 60_000L }
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED); settle()
        assertSameView("after 6 minutes away", home, homeView())
    }

    @Test fun small_maps_open_at_their_default_zoom() {
        Lang.sw = false; val m = start(); m.prefs.filter = "all"
        nav("list"); rule.onNodeWithTag("chip_all").performScrollTo().performClick(); settle()
        rule.onNodeWithTag("projects").performScrollToNode(hasText("Site 103 Borehole")); rule.onNodeWithText("Site 103 Borehole").performClick(); settle()
        rule.waitUntil(5000) { has("Move the map so the cross") }; drawMap()
        val first = view(map()); look(map(), 0.2, 35.5, first.z + 3)
        back(); waitTag("projects")
        rule.onNodeWithText("Site 103 Borehole").performClick(); settle(); drawMap()
        assertSameView("the project's map opens at its default", first, view(map()))
        // New site
        nav("new"); drawMap(); val ns = view(map()); look(map(), 0.2, 35.5, ns.z + 3)
        nav("list"); nav("new"); drawMap()
        assertSameView("New site's map opens at its default", ns, view(map()))
    }

    // the default view fits the home country's projects (as on the web); with no home country, all of them
    private fun defaultCentre(h: String): View {
        home = h; uganda = true; start(); nav("map"); return homeView()
    }
    @Test fun map_default_fits_the_home_country() {
        val v = defaultCentre("ke")
        // Kenya's 20 sites span lat 0.41..0.64, lng 35.11..35.32; Uganda's are far west (lng 32.6..34.2)
        assertTrue("centred on Kenya's projects: $v", v.lat in 0.40..0.66 && v.lng in 35.05..35.40)
        // and it comes back to that after a pan, via the bottom bar
        look(map(), 0.35, 32.58, 15.0); nav("list"); nav("map")
        assertSameView("back to the home country's fit", v, homeView())
        shot("nav-08-map-home-country")
    }
    @Test fun map_default_with_no_home_country_fits_everything() {
        val v = defaultCentre("")
        assertTrue("between Uganda and Kenya: $v", v.lng in 33.0..35.0)
    }

    // --- New site: a half-typed form is never lost ---------------------------------------------------------------
    private fun fillNewSite() {
        nav("new"); rule.waitUntil(5000) { has("Site name") }
        rule.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextInput("Kiptoo Primary")
        rule.onAllNodes(hasSetTextAction())[1].performScrollTo().performTextInput("Kiptoo, Nandi")
        rule.onNodeWithText("School").performScrollTo().performClick(); settle()
        until("the draft on disk") { NewSiteDrafts.load(app)?.optString("village") == "Kiptoo, Nandi" }
    }
    @Test fun new_site_draft_survives_leaving_the_tab() {
        Lang.sw = false; start()
        fillNewSite()
        nav("map"); nav("new")
        waitTag("site_resume"); assertTrue(exact("Kiptoo Primary")); shot("nav-09-new-site-resume")
        tap("site_resume_yes")
        assertTrue(exact("Kiptoo Primary") && exact("Kiptoo, Nandi"))
        assertEquals("school", NewSiteDrafts.load(app)!!.optString("type"))
        // Start again clears it
        nav("map"); nav("new"); waitTag("site_resume"); tap("site_resume_no")
        assertFalse(exact("Kiptoo Primary")); until("the draft gone") { !NewSiteDrafts.has(app) }
    }
    @Test fun new_site_draft_survives_the_activity_being_recreated() {
        Lang.sw = false; start()
        fillNewSite()
        // Android closes and recreates the activity: everything in memory goes, a new model reads the phone
        rule.activityRule.scenario.recreate(); rule.waitForIdle()
        val m2 = model()
        rule.runOnUiThread { rule.activity.setContent { RafikiTheme(dark = false) { Box(Modifier.size(390.dp, 844.dp)) { App(m2) } } } }
        waitTag("nav_new"); settle(); nav("new")
        waitTag("site_resume"); tap("site_resume_yes")
        assertTrue(exact("Kiptoo Primary") && exact("Kiptoo, Nandi"))
    }
    @Test fun new_site_draft_goes_once_saved_to_send() {
        Lang.sw = false; val m = start()
        m.api.transport = { _, _, _, _ -> 0 to "" } // no signal: saved to send later
        fillNewSite()
        rule.onNodeWithText("Add this site").performScrollTo().performClick()
        until("saved to send") { has("Saved on this phone") }
        until("the draft gone") { !NewSiteDrafts.has(app) }
        nav("map"); nav("new"); assertFalse(tagged("site_resume"))
    }

    // --- 4. Me: Team, back = Me; Monthly report, back = Me ------------------------------------------------------
    @Test fun me_team_back_and_monthly_report_back() {
        Lang.sw = false; val m = start()
        nav("me")
        assertTrue("Me opens on Me (start over), not the last side", tagged("me_hero"))
        tap("pane_team"); settle()
        assertFalse(tagged("me_hero")); assertEquals("team", m.prefs.mePane)
        back()
        assertTrue("back from Team is Me", tagged("me_hero")); rule.onNodeWithTag("nav_me").assertIsSelected()
        tap("me_report"); waitTag("hback")
        assertFalse(tagged("me_hero")); shot("nav-05-monthly-report")
        back()
        waitTag("me_hero"); rule.onNodeWithTag("nav_me").assertIsSelected()
        // back from Me goes to Map home
        back(); waitTag("map_home")
    }

    // --- 5. Visit: step 4, back = step 3; back on step 1 asks and keeps the draft ---------------------------------
    private fun visitBack(sw: Boolean) {
        Lang.sw = sw; val m = start(); m.prefs.filter = "all"
        nav("list"); rule.onNodeWithTag("chip_all").performScrollTo().performClick(); settle()
        rule.onNodeWithTag("projects").performScrollToNode(hasText("Site 102 Borehole")); rule.onNodeWithText("Site 102 Borehole").performClick(); settle()
        tap("tab_visit")
        rule.onNodeWithText(if (sw) "Nimefika" else "I’ve arrived").performScrollTo().performClick(); settle()
        rule.onNodeWithText(if (sw) "Inafanya kazi vizuri" else "Working well").performScrollTo().performClick(); settle()
        val next = if (sw) "Endelea" else "Next"
        rule.onNodeWithText(next).performScrollTo().performClick(); settle()
        rule.onNodeWithText(next).performScrollTo().performClick(); settle()
        assertTrue(text("step_label"), text("step_label").startsWith(if (sw) "Hatua 4 kati ya 7" else "Step 4 of 7"))
        back(); assertTrue(text("step_label"), text("step_label").startsWith(if (sw) "Hatua 3 kati ya 7" else "Step 3 of 7"))
        back(); back(); assertTrue(text("step_label"), text("step_label").startsWith(if (sw) "Hatua 1 kati ya 7" else "Step 1 of 7"))
        back()
        waitTag("visit_leave_sheet"); assertTrue("back on step 1 asks first", sheetOpen())
        shot(if (sw) "nav-06-visit-leave-sw" else "nav-06-visit-leave")
        // the phone's back on the question only closes the question
        back(); assertFalse(sheetOpen()); assertTrue(text("step_label").startsWith(if (sw) "Hatua 1" else "Step 1"))
        back(); waitTag("visit_leave_sheet")
        rule.onNodeWithTag("visit_leave_go").performScrollTo().performClick(); settle(); settle()
        assertTrue("left the visit for Pin location", !tagged("step_label") && tagged("hback"))
        rule.waitUntil(5000) { VisitDrafts.has(app, "102") }
        // the draft is kept: Site visit again carries on where it was
        tap("tab_visit")
        assertTrue(text("step_label").startsWith(if (sw) "Hatua 1" else "Step 1")); assertTrue(has(if (sw) "Umefika" else "Arrived"))
    }
    @Test fun visit_back_steps_and_asks_on_step_one() = visitBack(false)
    @Test fun visit_back_steps_and_asks_on_step_one_kiswahili() = visitBack(true)

    // --- 6. A sheet: back closes only the sheet -------------------------------------------------------------------
    @Test fun back_closes_only_the_sheet() {
        Lang.sw = false; val m = start(); m.prefs.filter = "all"
        nav("list"); rule.onNodeWithTag("chip_all").performScrollTo().performClick(); settle()
        rule.onNodeWithTag("projects").performScrollToNode(hasText("Site 104 Borehole")); rule.onNodeWithText("Site 104 Borehole").performClick(); settle()
        tap("prj_more_toggle"); assertTrue(tagged("prj_more_body"))
        tap("prj_dirs"); waitTag("dir_sheet"); assertTrue(sheetOpen())
        back()
        assertFalse("the sheet closed", sheetOpen())
        assertTrue("the fold under it is still open, on the same project", tagged("prj_more_body") && tagged("hback"))
        // then the fold, then the project
        back(); assertFalse(tagged("prj_more_body")); assertTrue(tagged("hback"))
        back(); waitTag("projects")
        // a sheet on a list: back closes it and stays on Projects
        rule.onNodeWithTag("projects").performScrollToNode(hasTestTag("proj_filter")); rule.onNodeWithTag("proj_filter").performClick(); settle()
        assertTrue(sheetOpen()); back(); assertFalse(sheetOpen()); assertTrue(tagged("projects"))
    }

    // --- 7. Refresh: you stay put ----------------------------------------------------------------------------------
    /** Waits for work that ends on the main thread (the main looper has to run in between). */
    private fun until(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond()) { assertTrue("timed out: $what", System.currentTimeMillis() < end); rule.mainClock.advanceTimeBy(100); rule.waitForIdle(); Thread.sleep(20) }
    }
    private fun refresh(m: AppModel) { var done = false; rule.runOnUiThread { m.refresh { done = true } }; until("refresh") { done }; settle() }
    @Test fun refresh_stays_put_on_every_tab() {
        Lang.sw = false; val m = start(); m.prefs.filter = "all"
        // Projects, scrolled
        nav("list"); rule.onNodeWithTag("chip_all").performScrollTo().performClick(); settle()
        rule.onNodeWithTag("projects").performScrollToNode(hasText("Site 118 Borehole")); settle()
        refresh(m)
        assertTrue(has("Site 118 Borehole") && !tagged("projects_head")); rule.onNodeWithTag("nav_list").assertIsSelected()
        // the map, zoomed in, with a card
        nav("map"); waitTag("map_home"); val mv = map(); look(mv, 0.5, 35.2, 13.0)
        refresh(m)
        assertEquals("the map stays where it was", 13.0, view(map()).z, 0.01); rule.onNodeWithTag("nav_map").assertIsSelected()
        // Office on a tab
        nav("office"); tap("o_repairs"); waitTag("office_strip")
        refresh(m); assertTrue(tagged("office_strip")); rule.onNodeWithTag("nav_office").assertIsSelected()
        // Me on Team
        nav("me"); tap("pane_team"); refresh(m); assertFalse(tagged("me_hero")); rule.onNodeWithTag("nav_me").assertIsSelected()
        // a project on History
        nav("list"); rule.onNodeWithTag("projects").performScrollToNode(hasText("Site 101 Borehole")); rule.onNodeWithText("Site 101 Borehole").performClick(); settle()
        tap("tab_hist"); refresh(m); assertTrue(has("Notes on this project") && tagged("hback"))
        // New site
        nav("new"); refresh(m); rule.onNodeWithTag("nav_new").assertIsSelected()
    }
    @Test fun pull_to_refresh_says_updated_and_stays() {
        Lang.sw = false; start()
        nav("me"); waitTag("me_hero")
        rule.onNodeWithTag("pull").performTouchInput { swipeDown(startY = top + 40f, endY = top + 900f, durationMillis = 400) }
        until("the Updated toast") { ShadowToast.getTextOfLatestToast() == "Updated" }
        settle()
        assertTrue(tagged("me_hero")); rule.onNodeWithTag("nav_me").assertIsSelected()
    }

    // --- 8. Projects: start over from the bottom bar, kept with back ----------------------------------------------
    private fun projectsStartOver(sw: Boolean) {
        Lang.sw = sw; val m = start()
        nav("list"); waitTag("projects")
        assertEquals("a fresh open: All", "all", m.prefs.filter)
        rule.onNodeWithTag("chip_repairs").performScrollTo().performClick(); settle(); assertEquals("repairs", m.prefs.filter)
        rule.onNodeWithTag("projects").performScrollToNode(hasText("Site 120 Borehole")); settle()
        assertFalse(tagged("projects_head"))
        // away via the bottom bar and back: All, at the top
        nav("me"); nav("list")
        assertEquals("all", m.prefs.filter); assertTrue("at the top", tagged("projects_head"))
        assertTrue(text("proj_count"), text("proj_count") == (if (sw) "Miradi 20" else "20 projects"))
        shot(if (sw) "nav-07-projects-start-over-sw" else "nav-07-projects-start-over")
        // away via a project and back: kept
        rule.onNodeWithTag("chip_repairs").performScrollTo().performClick(); settle(); assertEquals("repairs", m.prefs.filter)
        rule.onNodeWithTag("projects").performScrollToNode(hasText("Site 120 Borehole")); settle()
        rule.onNodeWithText("Site 120 Borehole").performClick(); settle(); waitTag("hback")
        back(); waitTag("projects")
        assertEquals("repairs", m.prefs.filter)
        assertTrue("where it was", has("Site 120 Borehole") && !tagged("projects_head"))
        // back from Projects: Map home
        back(); waitTag("map_home")
    }
    @Test fun projects_start_over_from_bar_kept_with_back() = projectsStartOver(false)
    @Test fun projects_start_over_from_bar_kept_with_back_kiswahili() = projectsStartOver(true)

    // --- 9. Back from any top-level tab goes to Map home; the app's ← is the same as back -------------------------
    @Test fun every_tab_backs_to_map_home_then_asks() {
        Lang.sw = false; start()
        listOf("list", "new", "me", "office").forEach { k -> nav(k); back(); assertTrue("$k → Map home", tagged("map_home")); rule.onNodeWithTag("nav_map").assertIsSelected() }
        back()
        assertEquals("Press back again to leave", ShadowToast.getTextOfLatestToast())
        back(); assertFalse("never quits", rule.activity.isFinishing)
        // ← on a project with History chosen: Pin location first
        nav("list"); rule.onNodeWithTag("projects").performScrollToNode(hasText("Site 120 Borehole")); rule.onNodeWithText("Site 120 Borehole").performClick(); settle()
        tap("tab_hist"); rule.onNodeWithTag("hback").performClick(); settle()
        assertTrue(has("Move the map so the cross") && tagged("hback"))
        rule.onNodeWithTag("hback").performClick(); settle(); waitTag("projects")
    }
}

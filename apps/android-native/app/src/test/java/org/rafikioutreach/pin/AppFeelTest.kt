package org.rafikioutreach.pin

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.ui.Onb
import org.rafikioutreach.pin.ui.RafikiTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The 2026 look: welcome, first-run screens, home, repair tracker, stamps. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class AppFeelTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }

    private fun proj(id: String, t: String, gps: JSONArray?) = JSONObject().put("id", id).put("title", t).put("country", "Kenya").put("ck", "ke").put("area", "Eldoret")
        .put("type", "borehole").put("status", "Completed").put("pump", "manual").apply { if (gps != null) put("gps", gps) }
    private fun snapshot() = JSONObject().put("ok", true).put("who", "Meril Kiprop").put("admin", true).put("reviewOpen", 0)
        .put("projects", JSONArray().put(proj("101", "Chepkongi Dispensary", JSONArray().put(0.5143).put(35.2698))).put(proj("102", "Kiptoo Well", null))
            .put(proj("104", "Kapsaret Borehole", JSONArray().put(0.5101).put(35.2742))).put(proj("105", "Turbo Spring", JSONArray().put(0.63).put(35.05))))
        .put("repairs", JSONObject().put("104", JSONObject().put("state", "notworking").put("by", "Bertin").put("at", "2026-10-18T08:00:00Z").put("note", "Handle loose").put("urgency", "urgent")
            .put("t", "Kapsaret Borehole").put("cost", 4500).put("step", "parts").put("assignee", "Meril Kiprop").put("due", "2026-10-24").put("parts", "pump handle")
            .put("stepAt", JSONObject().put("assigned", "2026-10-18T10:20:00Z").put("parts", "2026-10-19T07:45:00Z"))))
        .put("visits", JSONObject()).put("jobs", JSONArray()).put("assigned", JSONArray()).put("confirmed", JSONObject())

    private fun model(signedIn: Boolean, onbDone: Boolean): AppModel {
        Onb.enabled = true
        val app = ApplicationProvider.getApplicationContext<Application>()
        Prefs(app).apply { pin = if (signedIn) "9999" else ""; name = "Meril Kiprop"; saveSnapshot(snapshot()); filter = "all"; onb = onbDone; country = ""; lang = "en" }
        Lang.sw = false
        val m = AppModel(app)
        m.api.transport = { method, url, _, _ ->
            when {
                url.contains("activity=1") -> 200 to JSONObject().put("ok", true).put("activity", JSONArray()
                    .put(JSONObject().put("by", "Meril Kiprop").put("a", "pin").put("id", "101").put("t", "Chepkongi").put("at", "2026-10-19T08:00:00Z"))
                    .put(JSONObject().put("by", "Meril Kiprop").put("a", "visit").put("s", "working").put("id", "101").put("t", "Chepkongi").put("at", "2026-10-19T09:00:00Z"))).toString()
                url.contains("pin-auth/me") -> 200 to "{\"ok\":true,\"available\":false}"
                url.contains("history=") -> 200 to "{\"ok\":true,\"notes\":[]}"
                method == "GET" -> 200 to snapshot().toString()
                else -> 200 to "{\"ok\":true,\"updateId\":\"9\"}"
            }
        }
        return m
    }
    private fun show(m: AppModel) = rule.setContent { RafikiTheme(dark = false) { Box(Modifier.size(390.dp, 844.dp)) { App(m) } } }
    private fun shot(name: String) {
        rule.mainClock.advanceTimeBy(700); rule.waitForIdle()
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun welcome_then_sign_in_then_first_run() {
        rule.mainClock.autoAdvance = false
        val m = model(signedIn = false, onbDone = false); show(m)
        rule.onNodeWithTag("onb_signin").assertExists(); shot("30-welcome")
        rule.onNodeWithTag("onb_signin").performClick(); rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("key9").assertExists()
        rule.mainClock.autoAdvance = true
        repeat(4) { rule.onNodeWithTag("key9").performClick() }
        rule.waitUntil(5000) { rule.onAllNodesWithText("Get started").fetchSemanticsNodes().isNotEmpty() }
        rule.mainClock.autoAdvance = false
        shot("31-onb-start")
        rule.onNodeWithText("Get started").performClick(); rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithTag("onb_r_visit").performClick(); shot("32-onb-role")
        rule.onNodeWithTag("onb_go").performClick(); rule.mainClock.advanceTimeBy(400); shot("33-onb-lang")
        rule.onNodeWithTag("onb_pager").performTouchInput { swipeLeft() }; rule.mainClock.advanceTimeBy(1200)
        rule.onNodeWithTag("onb_lang").assertTextEquals("Kiswahili")
        rule.onNodeWithTag("onb_go").performClick(); rule.mainClock.advanceTimeBy(400)
        assertTrue(Lang.sw)
        rule.onNodeWithTag("onb_c_ke").performClick(); shot("34-onb-country")
        rule.onNodeWithTag("onb_go").performClick(); rule.mainClock.advanceTimeBy(400); shot("35-onb-location")
        rule.onNodeWithTag("onb_notnow").performClick(); rule.mainClock.advanceTimeBy(400); shot("36-onb-stamp")
        rule.onNodeWithTag("onb_done").performClick(); rule.mainClock.advanceTimeBy(800)
        assertTrue(m.prefs.onb); assertEquals("near", m.prefs.filter); assertEquals("ke", m.prefs.country)
        rule.onNodeWithTag("quick_missing").assertExists()
        shot("37-home-sw")
        Lang.sw = false
    }

    private fun until(what: () -> Boolean) { repeat(60) { if (what()) return; rule.mainClock.advanceTimeBy(250); Thread.sleep(20) }; assertTrue("timed out", what()) }

    @Test fun splash_home_tracker_stamps() {
        org.rafikioutreach.pin.ui.Motion.on = false
        rule.mainClock.autoAdvance = false
        val m = model(signedIn = true, onbDone = true); show(m)
        shot("40-splash")
        rule.mainClock.autoAdvance = true
        rule.waitUntil(8000) { rule.onAllNodesWithTag("quick_missing").fetchSemanticsNodes().isNotEmpty() }
        rule.mainClock.autoAdvance = false; shot("41-home"); rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("projects").performScrollToNode(hasText("Kapsaret Borehole"))
        rule.onAllNodesWithText("Kapsaret Borehole").onLast().performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("tracker").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("tracker").performScrollTo()
        rule.mainClock.autoAdvance = false; shot("42-tracker"); rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("hback").performClick()
        rule.onNodeWithTag("hav").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("stamp_got").fetchSemanticsNodes().size == 2 }
        rule.onNodeWithTag("stamps").performScrollTo()
        rule.mainClock.autoAdvance = false; shot("43-stamps")
        org.rafikioutreach.pin.ui.Motion.on = true
    }

    @Test fun map_nearest_and_you_are_here() {
        org.rafikioutreach.pin.ui.Motion.on = false
        val m = model(signedIn = true, onbDone = true)
        Onb.enabled = false
        m.locator.pretend(org.rafikioutreach.pin.data.Fix(org.rafikioutreach.pin.data.LatLng(0.5150, 35.2700), 8, System.currentTimeMillis()))
        show(m)
        rule.onNodeWithTag("nav_map").performClick()
        rule.onNodeWithTag("here").assertExists()
        rule.onNodeWithText("Nearest to you").performClick()
        rule.waitUntil(3000) { rule.onAllNodesWithText("Kapsaret Borehole").fetchSemanticsNodes().isNotEmpty() }
        rule.mainClock.autoAdvance = false; shot("44-map-near"); rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("here_x").performClick()
        rule.onNodeWithTag("here").assertDoesNotExist()
        m.dismissed.clear(); rule.waitForIdle()
        rule.onNodeWithTag("here_visit").performClick()
        rule.waitUntil(3000) { rule.onAllNodesWithText("I’ve arrived").fetchSemanticsNodes().isNotEmpty() }
        org.rafikioutreach.pin.ui.Motion.on = true
    }

    @Test fun phone_back_steps_through_tabs_before_closing() {
        org.rafikioutreach.pin.ui.Motion.on = false; Onb.enabled = false
        val m = model(signedIn = true, onbDone = true); show(m)
        fun back() { rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle() }
        rule.onNodeWithTag("nav_map").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("nav_me").performClick(); rule.waitForIdle()
        rule.onNodeWithText("Kapsaret Borehole", substring = true).assertDoesNotExist()
        back()   // Me -> Map
        rule.onNodeWithText("projects pinned", substring = true).assertExists()
        back()   // Map -> Projects
        rule.onNodeWithTag("projects").assertExists()
        assertTrue("back on Projects closes the app", !rule.activity.onBackPressedDispatcher.hasEnabledCallbacks())
        Onb.enabled = true; org.rafikioutreach.pin.ui.Motion.on = true
    }

    @Test fun native_touches_search_sheets_and_swipe() {
        org.rafikioutreach.pin.ui.Motion.on = false; Onb.enabled = false
        val m = model(signedIn = true, onbDone = true)
        m.locator.pretend(org.rafikioutreach.pin.data.Fix(org.rafikioutreach.pin.data.LatLng(0.5150, 35.2700), 8, System.currentTimeMillis()))
        show(m); rule.waitForIdle(); rule.mainClock.advanceTimeBy(2000); rule.waitForIdle()
        // full-screen search
        rule.onNodeWithTag("home_search").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("search_screen").assertExists()
        rule.onNodeWithTag("search_input").performTextInput("kaps"); rule.waitForIdle()
        shot("50-search")
        rule.onAllNodesWithText("Kapsaret Borehole").onFirst().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("tracker").assertExists()
        // project tabs swipe
        rule.onNodeWithTag("tab_hist").performScrollTo(); rule.waitForIdle()
        rule.onNodeWithText("Pin location").performTouchInput { swipeLeft() }; rule.waitForIdle()
        rule.onNodeWithTag("hback").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("hback").performClick(); rule.waitForIdle()
        // country chip opens a bottom sheet
        rule.onNodeWithTag("chip_country").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("sheet").assertExists(); shot("51-country-sheet")
        rule.onNodeWithTag("pick_ke").performClick(); rule.waitForIdle()
        assertEquals("ke", m.prefs.country)
        // title moves into the top bar once scrolled
        rule.onNodeWithTag("projects").performScrollToNode(hasText("Turbo Spring")); rule.waitForIdle()
        rule.onNodeWithTag("bar_title").assertExists(); shot("52-list-scrolled")
        // map: drag-up sheet and map style sheet
        rule.onNodeWithTag("nav_map").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("sheet_handle").performClick(); rule.waitForIdle()
        rule.onAllNodesWithText("Turbo Spring").onFirst().assertExists(); shot("53-map-sheet")
        rule.onNodeWithContentDescription("Map style").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("pick_sat").assertExists(); shot("54-map-style")
        rule.onNodeWithTag("pick_sat").performClick(); rule.waitForIdle()
        m.prefs.country = ""
        Onb.enabled = true; org.rafikioutreach.pin.ui.Motion.on = true
    }

    @Test fun motion_frames_for_a_clip() {
        org.rafikioutreach.pin.ui.Motion.on = true; org.rafikioutreach.pin.ui.Motion.loops = false
        val m = model(signedIn = true, onbDone = true); Onb.enabled = false
        rule.mainClock.autoAdvance = false
        show(m)
        val dir = File(out, "motion").apply { mkdirs() }
        var n = 0
        fun frame() {
            val v = rule.activity.window.decorView
            val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
            val s = Bitmap.createScaledBitmap(b, v.width / 3, v.height / 3, true)
            File(dir, "%03d.png".format(n++)).outputStream().use { s.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        repeat(22) { rule.mainClock.advanceTimeBy(50); frame() }
        repeat(6) { rule.mainClock.advanceTimeBy(120); frame() }
        rule.mainClock.autoAdvance = true; rule.onNodeWithTag("projects").performScrollToNode(hasText("Kapsaret Borehole")); rule.mainClock.autoAdvance = false
        repeat(12) { rule.mainClock.advanceTimeBy(50); frame() }
        rule.mainClock.autoAdvance = true; rule.onAllNodesWithText("Kapsaret Borehole").onFirst().performClick(); rule.mainClock.autoAdvance = false
        repeat(34) { rule.mainClock.advanceTimeBy(50); frame() }
        rule.mainClock.autoAdvance = true; rule.onNodeWithTag("tracker").performScrollTo(); rule.mainClock.autoAdvance = false
        repeat(30) { rule.mainClock.advanceTimeBy(50); frame() }
        rule.mainClock.autoAdvance = true; rule.onNodeWithTag("nav_me").performClick(); rule.mainClock.autoAdvance = false
        repeat(16) { rule.mainClock.advanceTimeBy(50); frame() }
        rule.mainClock.autoAdvance = true; rule.onNodeWithText("Your stamps").performScrollTo(); rule.mainClock.autoAdvance = false
        repeat(34) { rule.mainClock.advanceTimeBy(50); frame() }
        rule.mainClock.autoAdvance = true; Onb.enabled = true; org.rafikioutreach.pin.ui.Motion.loops = true
    }
}

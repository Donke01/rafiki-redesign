package org.rafikioutreach.pin

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.rafikioutreach.pin.data.LatLng
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.data.Project
import org.rafikioutreach.pin.ui.RafikiTheme
import org.rafikioutreach.pin.ui.mapsLegs
import org.rafikioutreach.pin.ui.orderStops
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Office tools, route, monthly report and monday.com sign-in in the native app. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class OfficeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val posts = mutableListOf<String>()

    private fun proj(id: String, t: String, gps: JSONArray?) = JSONObject().put("id", id).put("title", t).put("country", "Kenya").put("ck", "ke").put("area", "Eldoret")
        .put("type", "borehole").put("status", "Completed").put("pump", "manual").apply { if (gps != null) put("gps", gps) }
    private fun snapshot() = JSONObject().put("ok", true).put("who", "Reid Anderson").put("admin", true).put("reviewOpen", 1)
        .put("projects", JSONArray().put(proj("101", "Chepkongi Dispensary", JSONArray().put(0.5143).put(35.2698))).put(proj("102", "Kiptoo Well", null))
            .put(proj("104", "Kapsaret Borehole", JSONArray().put(0.47).put(35.25))).put(proj("105", "Turbo Spring", JSONArray().put(0.63).put(35.05))))
        .put("repairs", JSONObject().put("104", JSONObject().put("state", "notworking").put("by", "Bertin").put("at", "2026-10-18T08:00:00Z").put("note", "Handle loose").put("urgency", "urgent").put("t", "Kapsaret Borehole").put("cost", 4500)))
        .put("visits", JSONObject()).put("jobs", JSONArray()).put("assigned", JSONArray()).put("confirmed", JSONObject())

    private fun model(): AppModel {
        org.rafikioutreach.pin.ui.Onb.enabled = false
        val app = ApplicationProvider.getApplicationContext<Application>()
        Prefs(app).apply { pin = "9999"; name = "Reid Anderson"; saveSnapshot(snapshot()); filter = "all" }
        val m = AppModel(app)
        m.api.transport = { method, url, h, body ->
            posts += "$method $url ${h["Cookie"] ?: ""} ${body?.let { String(it) } ?: ""}"
            when {
                url.contains("view=team") -> 200 to JSONObject().put("ok", true).put("team", JSONArray()
                    .put(JSONObject().put("name", "Meril Kiprop").put("home", "ke").put("admin", false).put("source", "app").put("lastSeen", "2026-10-19T09:00:00Z"))
                    .put(JSONObject().put("name", "Reid Anderson").put("admin", true).put("source", "cloudflare"))).toString()
                url.contains("view=review") -> 200 to JSONObject().put("ok", true).put("items", JSONArray().put(JSONObject().put("rid", "r1").put("kind", "story").put("id", "101").put("t", "Chepkongi Dispensary").put("by", "Meril").put("at", "2026-10-19T09:00:00Z").put("status", "open")
                    .put("data", JSONObject().put("quote", "Now the nurses can wash their hands").put("who", "Mary")))).toString()
                url.contains("view=assign") -> 200 to "{\"ok\":true,\"items\":[]}"
                url.contains("activity=1") -> 200 to JSONObject().put("ok", true).put("activity", JSONArray().put(JSONObject().put("by", "Meril").put("a", "visit").put("s", "notworking").put("id", "104").put("t", "Kapsaret Borehole").put("at", java.time.Instant.now().toString()))).toString()
                url.contains("pin-auth/me") -> 200 to "{\"ok\":true,\"available\":true,\"user\":null}"
                url.contains("pin-auth/exchange") -> if (String(body!!).contains("\"code\":\"good\"")) 200 to "{\"ok\":true,\"sid\":\"SESSION123456789012345\"}" else 400 to "{\"ok\":false,\"reason\":\"expired\"}"
                url.contains("team-add") || (body != null && String(body).contains("team-add")) -> 200 to "{\"ok\":true,\"pin\":\"4821\"}"
                method == "GET" -> 200 to snapshot().toString()
                else -> 200 to "{\"ok\":true}"
            }
        }
        return m
    }
    private fun show(m: AppModel) = rule.setContent { RafikiTheme(dark = false) { Box(Modifier.size(390.dp, 844.dp)) { App(m) } } }
    private fun shot(name: String) {
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun tap(text: String) = rule.onNodeWithText(text).performScrollTo().performClick()
    private fun waitFor(text: String) = rule.waitUntil(5000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test fun route_order_and_google_maps_parts() {
        val ps = (1..12).map { Project("$it", "P$it", "Kenya", "ke", "", "borehole", "Completed", null, LatLng(0.0, it * 0.01), null, "") }
        val o = orderStops(ps.shuffled(), LatLng(0.0, 0.0))
        assertEquals((1..12).map { "$it" }, o.map { it.id })
        val legs = mapsLegs(o, LatLng(0.0, 0.0))
        assertEquals(2, legs.size)
        assertTrue(legs[0].second.contains("waypoints=") && legs[0].second.contains("destination=0.000000,0.100000"))
        assertTrue(legs[1].second.contains("origin=0.000000,0.100000"))
    }

    @Test fun office_overview_repairs_review_team() {
        Lang.sw = false
        val m = model(); show(m)
        rule.onNodeWithTag("nav_office").performClick()
        waitFor("Kapsaret Borehole")
        shot("20-office-overview")
        rule.onNodeWithTag("show_missing").assertExists(); rule.onNodeWithTag("office_map").assertExists()
        rule.onNodeWithTag("o_repairs").performClick()
        waitFor("Handle loose")
        tap("Parts ordered"); tap("Save")
        rule.waitUntil(5000) { posts.any { it.contains("\"action\":\"job\"") } }
        assertTrue(posts.first { it.contains("\"action\":\"job\"") }.let { it.contains("\"step\":\"parts\"") && it.contains("\"id\":\"104\"") })
        shot("21-office-repairs")
        rule.onNodeWithTag("o_review").performScrollTo().performClick()
        waitFor("Now the nurses")
        shot("22-office-review")
        tap("Approve for the website")
        rule.waitUntil(5000) { posts.any { it.contains("\"action\":\"review\"") && it.contains("\"decision\":\"approve\"") } }
        rule.onNodeWithTag("o_team").performScrollTo().performClick()
        waitFor("Meril Kiprop")
        rule.onNodeWithText("e.g. Jessica Anderson").performScrollTo().performTextInput("Jessica Anderson")
        tap("Add and create PIN")
        waitFor("4821")
        shot("23-office-team")
        assertTrue(posts.any { it.contains("\"action\":\"team-add\"") && it.contains("Jessica Anderson") })
    }

    @Test fun office_in_kiswahili_and_report() {
        Lang.sw = true
        val m = model(); show(m)
        rule.onNodeWithTag("nav_office").performClick()
        waitFor("Muhtasari")
        shot("24-office-sw")
        rule.onNodeWithTag("nav_me").performClick()
        tap("Fungua ripoti")
        waitFor("Ripoti ya uwanjani")
        waitFor("Kapsaret Borehole")
        shot("25-report-sw")
        Lang.sw = false
    }

    @Test fun plan_a_route() {
        Lang.sw = false
        val m = model(); show(m)
        rule.onAllNodesWithText("Plan a route").onFirst().performClick()
        waitFor("Tick the projects")
        tap("Due a visit") // opens the picker
        rule.onAllNodesWithText("All with a location").onFirst().performClick()
        tap("Turbo Spring"); tap("Chepkongi Dispensary")
        tap("Plan route (2)")
        waitFor("Start in Google Maps")
        shot("26-route")
        assertEquals("105,101", m.prefs.route)
    }

    @Test fun monday_sign_in_from_the_app() = runBlocking {
        val m = model()
        Prefs(ApplicationProvider.getApplicationContext()).apply { pin = "" }
        val start = m.mondayStartUrl()
        assertTrue(start.contains("/api/pin-auth/start?app=1&cc=") && m.prefs.verifier.length >= 43)
        assertTrue(m.mondayReturn(null, "denied")!!.contains("cancelled"))
        assertTrue(m.mondayReturn("wrong", null) != null)
        m.mondayStartUrl()
        assertNull(m.mondayReturn("good", null))
        assertEquals("SESSION123456789012345", m.prefs.session)
        assertTrue(m.signedIn.value)
        // later calls carry the session the way a browser carries its cookie
        m.api.list()
        assertTrue(posts.last().contains("rfpin=SESSION123456789012345"))
    }

    @Test fun colours_light_dark_and_bright_sun() {
        Lang.sw = false
        val m = model(); show(m)
        rule.onNodeWithTag("nav_me").performClick()
        rule.onNodeWithTag("theme_sun").performScrollTo().performClick()
        assertEquals("sun", m.prefs.theme)
        shot("27-me-sun")
        rule.onNodeWithTag("theme_dark").performClick()
        assertEquals("dark", m.prefs.theme)
        rule.onNodeWithTag("nav_list").performClick()
        shot("28-projects-dark")
        rule.onNodeWithTag("nav_me").performClick()
        rule.onNodeWithTag("theme_light").performScrollTo().performClick()
        assertEquals("light", m.prefs.theme)
        org.rafikioutreach.pin.ui.Look.mode = ""
    }
}

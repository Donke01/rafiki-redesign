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
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertCountEquals
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
    private val posts = java.util.concurrent.CopyOnWriteArrayList<String>()

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
                body != null && String(body).contains("\"team-pin\"") -> 200 to "{\"ok\":true,\"pin\":\"5307\"}"
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
    /** The sheet is its own window: draw the app, then the sheet's window on top. */
    private fun sheetShot(name: String) {
        rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); val c = Canvas(b); v.draw(c)
        val d = org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window?.decorView
        if (d != null) { c.save(); c.translate(0f, (v.height - d.height).toFloat()); d.draw(c); c.restore() }
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

    @Test fun office_cleanup() {
        Lang.sw = false
        fun p(id: String, t: String, type: String, area: String, gps: JSONArray?, status: String = "Completed") = JSONObject().put("id", id).put("title", t).put("country", "Kenya").put("ck", "ke").put("area", area)
            .put("type", type).put("status", status).put("pump", "").apply { if (gps != null) put("gps", gps) }
        val same = { JSONArray().put(0.4012).put(35.2201) }
        val ps = JSONArray().put(p("1", "ACK St John's Sigowet Church", "Borehole well", "Sigowet", null)).put(p("2", "AIC Tapsagoi High School", "School", "Tapsagoi", null))
            .put(p("3", "Calvary Church Magara", "Borehole well", "Magara, Manyara", null)).put(p("4", "Gichameda Majengo", "Water collection", "Babati", null))
            .put(p("5", "Gichameda Msikitini", "Borehole well", "Babati", null)).put(p("6", "Chepkongi Dispensary - Incinerator", "Health", "Kapsaret, Eldoret", same()))
            .put(p("7", "Chepkongi Dispensary - Well & Tower", "Borehole well", "Kapsaret, Eldoret", same())).put(p("8", "Chepkongi Primary School - Paint", "School", "Kapsaret, Eldoret", same()))
            .put(p("9", "Kaptebee Pigsty", "Farming", "Kaptebee", JSONArray().put(0.2).put(35.1))).put(p("10", "Mtakuja Village", "Borehole well", "Mtakuja", JSONArray().put(35.1).put(0.2)))
            .put(p("11", "Nzoia Bridge Primary School - 6 Classrooms", "School", "Bungoma", JSONArray().put(0.56).put(34.6), "Under construction"))
        val snap = snapshot().put("projects", ps).put("repairs", JSONObject())
        org.rafikioutreach.pin.ui.Onb.enabled = false
        val app = ApplicationProvider.getApplicationContext<Application>()
        Prefs(app).apply { pin = "9999"; name = "Reid Anderson"; saveSnapshot(snap); filter = "all" }
        val m = AppModel(app)
        m.api.transport = { method, url, _, _ -> when { url.contains("activity=1") -> 200 to "{\"ok\":true,\"activity\":[]}"; url.contains("pin-auth/me") -> 200 to "{\"ok\":true,\"available\":false}"; method == "GET" -> 200 to snap.toString(); else -> 200 to "{\"ok\":true}" } }
        show(m)
        rule.onNodeWithTag("nav_office").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("o_cleanup").performScrollTo().performClick(); rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1500)
        rule.onNodeWithTag("clean_0").performScrollTo(); shot("28-cleanup-top")
        rule.onAllNodesWithText("3 projects at 0.4012, 35.2201", substring = true).onFirst().assertExists()
        rule.onNodeWithTag("clean_2").performScrollTo(); shot("29-cleanup-same-point")
        rule.onAllNodesWithTag("clean_more").onFirst().performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithText("Gichameda Msikitini").assertExists()
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
        // a short list grouped by role: Reid (admin, Cloudflare PIN) under Admins, Meril under Field team
        rule.onNodeWithTag("team_admins").assertExists(); rule.onNodeWithTag("team_field").assertExists()
        rule.onNodeWithTag("tm_Reid Anderson").assertExists(); rule.onNodeWithText("PIN in Cloudflare").assertExists()
        assertTrue(rule.onNodeWithTag("team_admins").fetchSemanticsNode().boundsInRoot.top < rule.onNodeWithTag("tm_Reid Anderson").fetchSemanticsNode().boundsInRoot.top)
        assertTrue(rule.onNodeWithTag("tm_Reid Anderson").fetchSemanticsNode().boundsInRoot.top < rule.onNodeWithTag("team_field").fetchSemanticsNode().boundsInRoot.top)
        assertTrue(rule.onNodeWithTag("team_field").fetchSemanticsNode().boundsInRoot.top < rule.onNodeWithTag("tm_Meril Kiprop").fetchSemanticsNode().boundsInRoot.top)
        rule.onNodeWithTag("tm_Meril Kiprop").performScrollTo()
        shot("23-office-team")
        // tap a person: a sheet with their details and actions
        rule.onNodeWithTag("tm_Meril Kiprop").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("team_sheet").fetchSemanticsNodes().isNotEmpty() }
        sheetShot("23b-team-person")
        rule.onNodeWithTag("tm_newpin").performScrollTo().performClick()
        rule.onNodeWithText("Make a new PIN for Meril Kiprop? Their old PIN stops working.").assertExists()
        assertTrue(posts.none { it.contains("\"action\":\"team-pin\"") })
        rule.onNodeWithTag("tm_yes").performScrollTo().performClick()
        waitFor("5307")
        sheetShot("23c-team-new-pin")
        rule.onNodeWithTag("tm_remove").performScrollTo().performClick()
        rule.onNodeWithText("Remove Meril Kiprop from the team? Their PIN stops working straight away.").assertExists()
        sheetShot("23d-team-remove-ask")
        rule.onNodeWithText("Cancel").performScrollTo().performClick()
        rule.onAllNodesWithTag("tm_ask").assertCountEquals(0)
        assertTrue(posts.none { it.contains("\"action\":\"team-remove\"") })
        rule.onNodeWithText("+254 712 345 678").performScrollTo().performTextInput("0712 000 111")
        rule.onNodeWithTag("tm_admin").performScrollTo().performClick()
        rule.onNodeWithTag("tm_save").performScrollTo().performClick()
        rule.waitUntil(5000) { posts.any { it.contains("\"action\":\"team-update\"") } }
        posts.first { it.contains("\"action\":\"team-update\"") }.let { assertTrue(it, it.contains("\"name\":\"Meril Kiprop\"") && it.contains("\"phone\":\"0712 000 111\"") && it.contains("\"admin\":true") && it.contains("\"home\":\"ke\"")) }
        rule.mainClock.advanceTimeBy(2000)
        rule.waitUntil(5000) { rule.onAllNodesWithTag("team_sheet").fetchSemanticsNodes().isEmpty() }
        // Add a person: its own sheet, then the PIN shown once
        rule.onNodeWithTag("team_add").performScrollTo().performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("team_add_sheet").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("e.g. Jessica Anderson").performScrollTo().performTextInput("Jessica Anderson")
        rule.onNodeWithTag("tm_admin").performScrollTo().performClick()
        sheetShot("23f-team-add")
        rule.onNodeWithTag("tm_add_go").performScrollTo().performClick()
        waitFor("4821")
        sheetShot("23g-team-added")
        posts.first { it.contains("\"action\":\"team-add\"") }.let { assertTrue(it, it.contains("\"name\":\"Jessica Anderson\"") && it.contains("\"admin\":true")) }
        rule.onNodeWithTag("tm_done").performScrollTo().performClick()
        rule.mainClock.advanceTimeBy(1500)
        rule.waitUntil(5000) { rule.onAllNodesWithTag("team_add_sheet").fetchSemanticsNodes().isEmpty() }
        // Cloudflare person: a short note and only the re-add action, which opens Add a person already filled in
        rule.onNodeWithTag("tm_Reid Anderson").performScrollTo().performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("team_sheet").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Set in Cloudflare — change it there, or re-add here with an app PIN.").assertExists()
        rule.onAllNodesWithTag("tm_save").assertCountEquals(0); rule.onAllNodesWithTag("tm_newpin").assertCountEquals(0)
        sheetShot("23e-team-cloudflare")
        rule.onNodeWithTag("tm_readd").performScrollTo().performClick()
        rule.mainClock.advanceTimeBy(1000)
        rule.waitUntil(5000) { rule.onAllNodesWithTag("team_add_sheet").fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasSetTextAction() and hasText("Reid Anderson")).assertExists()
        rule.onNodeWithTag("tm_readd").assertDoesNotExist()
    }

    @Test fun team_list_and_sheet_dark_and_sun() {
        Lang.sw = false
        org.rafikioutreach.pin.ui.Look.mode = "dark"
        try {
            val m = model(); show(m)
            rule.onNodeWithTag("nav_office").performClick()
            rule.onNodeWithTag("o_team").performScrollTo().performClick()
            waitFor("Meril Kiprop")
            rule.mainClock.advanceTimeBy(1500)
            rule.onNodeWithTag("tm_Meril Kiprop").performScrollTo()
            shot("23h-team-dark")
            rule.onNodeWithTag("tm_Meril Kiprop").performClick()
            rule.waitUntil(5000) { rule.onAllNodesWithTag("team_sheet").fetchSemanticsNodes().isNotEmpty() }
            sheetShot("23i-team-person-dark")
            // the whole admin row flips the switch, so the target is big enough
            assertTrue(rule.onNodeWithTag("tm_admin_row").fetchSemanticsNode().size.height >= with(rule.density) { 44.dp.roundToPx() })
            org.rafikioutreach.pin.ui.Look.mode = "sun"
            sheetShot("23j-team-person-sun")
        } finally { org.rafikioutreach.pin.ui.Look.mode = "" }
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
        rule.onNodeWithTag("set_look").performScrollTo().performClick(); rule.waitForIdle()   // Me › Settings › This phone › Appearance
        rule.onNodeWithTag("theme_sun").performScrollTo().performClick()
        assertEquals("sun", m.prefs.theme)
        shot("27-me-sun")
        rule.onNodeWithTag("theme_dark").performClick()
        assertEquals("dark", m.prefs.theme)
        rule.onNodeWithTag("nav_list").performClick()
        shot("28-projects-dark")
        rule.onNodeWithTag("nav_me").performClick()
        rule.onNodeWithTag("set_look").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("theme_light").performScrollTo().performClick()
        assertEquals("light", m.prefs.theme)
        org.rafikioutreach.pin.ui.Look.mode = ""
    }
}

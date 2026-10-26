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
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performTextClearance
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
    private var assign = JSONArray()
    private fun visit(aid: String, id: String, t: String, to: String, date: java.time.LocalDate, note: String = "", done: String? = null) = JSONObject().put("aid", aid).put("id", id).put("t", t).put("to", to)
        .put("date", date.toString()).put("note", note).put("by", "Jessica").put("at", "2026-10-15T08:00:00Z").apply { if (done != null) put("done", done) }

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
        app.getSharedPreferences("rafiki-pin", 0).edit().putString("currency", "KES").commit() // Settings › Currency: these screens show KES
        val m = AppModel(app)
        m.api.transport = { method, url, h, body ->
            posts += "$method $url ${h["Cookie"] ?: ""} ${body?.let { String(it) } ?: ""}"
            when {
                url.contains("view=team") -> 200 to JSONObject().put("ok", true).put("team", JSONArray()
                    .put(JSONObject().put("name", "Meril Kiprop").put("home", "ke").put("admin", false).put("source", "app").put("lastSeen", "2026-10-19T09:00:00Z"))
                    .put(JSONObject().put("name", "Reid Anderson").put("admin", true).put("source", "cloudflare"))).toString()
                url.contains("view=review") -> 200 to JSONObject().put("ok", true).put("items", JSONArray().put(JSONObject().put("rid", "r1").put("kind", "story").put("id", "101").put("t", "Chepkongi Dispensary").put("by", "Meril").put("at", "2026-10-19T09:00:00Z").put("status", "open")
                    .put("data", JSONObject().put("quote", "Now the nurses can wash their hands").put("who", "Mary")))).toString()
                url.contains("view=assign") -> 200 to JSONObject().put("ok", true).put("items", assign).toString()
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
        app.getSharedPreferences("rafiki-pin", 0).edit().putString("currency", "KES").commit() // Settings › Currency: these screens show KES
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
        // Repairs: a calm card per job; tap it for a sheet with the step, person, money and Save
        rule.waitUntil(5000) { rule.onAllNodesWithTag("rr_job_104").fetchSemanticsNodes().isNotEmpty() }
        rule.mainClock.advanceTimeBy(1200)
        shot("21-office-repairs")
        rule.onNodeWithTag("rr_job_104").performScrollTo().performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("rr_job_sheet").fetchSemanticsNodes().isNotEmpty() }
        waitFor("Handle loose")
        rule.onNodeWithTag("rr_step_parts").performScrollTo().performClick(); rule.onNodeWithTag("rr_save").performScrollTo().performClick()
        rule.waitUntil(5000) { posts.any { it.contains("\"action\":\"job\"") } }
        assertTrue(posts.first { it.contains("\"action\":\"job\"") }.let { it.contains("\"step\":\"parts\"") && it.contains("\"id\":\"104\"") })
        sheetShot("21b-office-repair-sheet")
        rule.onNodeWithTag("rr_close").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(1200)
        rule.waitUntil(5000) { rule.onAllNodesWithTag("rr_job_sheet").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("o_review").performScrollTo().performClick()
        waitFor("Now the nurses")
        shot("22-office-review")
        rule.onNodeWithTag("rr_rev_r1").performScrollTo().performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("rr_rev_sheet").fetchSemanticsNodes().isNotEmpty() }
        sheetShot("22b-office-review-sheet")
        rule.onNodeWithTag("rr_approve").performScrollTo().performClick()
        rule.waitUntil(5000) { posts.any { it.contains("\"action\":\"review\"") && it.contains("\"decision\":\"approve\"") } }
        rule.mainClock.advanceTimeBy(2000)
        rule.waitUntil(5000) { rule.onAllNodesWithTag("rr_rev_sheet").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("o_team").performScrollTo().performClick()
        waitFor("Meril Kiprop")
        // a short list grouped by role: Reid (admin, Cloudflare PIN) under Admins, Meril under Field team
        rule.onNodeWithTag("team_admins").assertExists(); rule.onNodeWithTag("team_field").assertExists()
        rule.onNodeWithTag("tm_Reid Anderson").assertExists(); rule.onNodeWithText("PIN set by the office").assertExists()
        assertTrue(rule.onNodeWithTag("team_admins").fetchSemanticsNode().boundsInRoot.top < rule.onNodeWithTag("tm_Reid Anderson").fetchSemanticsNode().boundsInRoot.top)
        assertTrue(rule.onNodeWithTag("tm_Reid Anderson").fetchSemanticsNode().boundsInRoot.top < rule.onNodeWithTag("team_field").fetchSemanticsNode().boundsInRoot.top)
        // the new person cards are taller (Office › Team redesign), so Meril is below the fold: scroll to them first
        rule.onNodeWithTag("tm_Meril Kiprop").performScrollTo(); rule.waitForIdle()
        assertTrue(rule.onNodeWithTag("team_field").fetchSemanticsNode().boundsInRoot.top < rule.onNodeWithTag("tm_Meril Kiprop").fetchSemanticsNode().boundsInRoot.top)
        shot("23-office-team")
        // tap a person: their contact sheet (feat-conta), a menu of actions; each opens its own small sheet
        rule.onNodeWithTag("tm_Meril Kiprop").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("ct_sheet").fetchSemanticsNodes().isNotEmpty() }
        rule.mainClock.advanceTimeBy(1000); rule.waitForIdle()
        sheetShot("23b-team-person")
        rule.onNodeWithTag("ct_newpin").performScrollTo().performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("ct_newpin_sheet").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Their old PIN stops working.").assertExists()
        assertTrue(posts.none { it.contains("\"action\":\"team-pin\"") })
        rule.onNodeWithTag("ct_yes").performScrollTo().performClick()
        waitFor("5307")
        sheetShot("23c-team-new-pin")
        rule.onNodeWithTag("ct_pin_done").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(1200)
        rule.waitUntil(5000) { rule.onAllNodesWithTag("ct_newpin_sheet").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("ct_remove").performScrollTo().performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("ct_ask_sheet").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Remove Meril Kiprop from the team?").assertExists()
        sheetShot("23d-team-remove-ask")
        rule.onNodeWithTag("ct_no").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(1200)
        rule.waitUntil(5000) { rule.onAllNodesWithTag("ct_ask_sheet").fetchSemanticsNodes().isEmpty() }
        assertTrue(posts.none { it.contains("\"action\":\"team-remove\"") })
        // change the phone: one field and Save; the rest of their entry is kept
        rule.onNodeWithTag("ct_phone_row").performScrollTo().performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("ct_field_sheet").fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("ct_field"))).performTextClearance()
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("ct_field"))).performTextInput("0712 000 111")
        rule.onNodeWithTag("ct_field_save").performScrollTo().performClick()
        rule.waitUntil(5000) { posts.any { it.contains("\"action\":\"team-update\"") } }
        posts.first { it.contains("\"action\":\"team-update\"") }.let { assertTrue(it, it.contains("\"name\":\"Meril Kiprop\"") && it.contains("\"phone\":\"0712 000 111\"") && it.contains("\"admin\":false") && it.contains("\"home\":\"ke\"")) }
        rule.mainClock.advanceTimeBy(1500)
        rule.waitUntil(5000) { rule.onAllNodesWithTag("ct_field_sheet").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("ct_x").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(1500)
        rule.waitUntil(5000) { rule.onAllNodesWithTag("ct_sheet").fetchSemanticsNodes().isEmpty() }
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
        // Cloudflare person: details read-only, and "Move to an app PIN" (asks first, technical names folded away)
        rule.onNodeWithTag("tm_Reid Anderson").performScrollTo().performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("ct_sheet").fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(5000) { rule.onAllNodesWithTag("ct_move").fetchSemanticsNodes().isNotEmpty() }
        // their own entry: name and e-mail are set by the office
        rule.onNodeWithTag("ct_setby").assertExists(); rule.onNodeWithTag("ct_b_cf").assertExists()
        rule.onAllNodesWithTag("ct_newpin").assertCountEquals(0); rule.onAllNodesWithTag("ct_email_row").assertCountEquals(0)
        sheetShot("23e-team-cloudflare")
        rule.onNodeWithTag("ct_move").performScrollTo().performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("ct_ask_sheet").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("ct_tech").performScrollTo().performClick(); rule.onNodeWithText("PIN_USERS", substring = true).assertExists()
        rule.onNodeWithTag("ct_yes").performScrollTo().performClick()
        rule.waitUntil(5000) { posts.any { it.contains("\"action\":\"team-move\"") && it.contains("\"name\":\"Reid Anderson\"") } }
    }

    /** Overview tiles and the country sheet; Visits as cards with filters, a sheet to reassign or cancel, and the assign sheet. */
    @Test fun office_overview_tiles_and_visits() {
        Lang.sw = false
        val today = java.time.LocalDate.now()
        assign = JSONArray().put(visit("a1", "104", "Kapsaret Borehole", "Meril Kiprop", today.minusDays(3), "Check the pump and meet the committee"))
            .put(visit("a2", "101", "Chepkongi Dispensary", "Reid Anderson", today)).put(visit("a3", "105", "Turbo Spring", "Meril Kiprop", today.plusDays(4)))
            .put(visit("a4", "101", "Chepkongi Dispensary", "Meril Kiprop", today.minusDays(9), done = java.time.Instant.now().minusSeconds(5 * 86400).toString()))
        val m = model(); show(m)
        rule.onNodeWithTag("nav_office").performClick()
        waitFor("Kapsaret Borehole"); rule.mainClock.advanceTimeBy(1500)
        rule.onNodeWithTag("ocv_overview_head").assertExists(); rule.onNodeWithText("3 of 4 projects on the map").assertExists(); rule.onNodeWithText("1 still needs a location").assertExists()
        rule.onNodeWithText("1 urgent · KES\u00A04,500").assertExists()
        shot("ocv-01-overview")
        rule.onNodeWithTag("ocv_countries").performScrollTo(); shot("ocv-01b-overview-countries")
        rule.onNodeWithTag("ocv_c_ke").performScrollTo().performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("ocv_country_sheet").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("4 projects").assertExists(); rule.onNodeWithText("3 of 4").assertExists()
        sheetShot("ocv-02-country-sheet")
        rule.onNodeWithTag("ocv_country_all").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(1000); rule.waitForIdle()
        assertEquals("all", m.prefs.filter); assertEquals("ke", m.prefs.country)
        m.prefs.country = ""
        rule.onNodeWithTag("nav_office").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("ocv_t_visits").performScrollTo().performClick()
        waitFor("Assign a visit"); rule.mainClock.advanceTimeBy(1000)
        rule.onAllNodesWithTag("ocv_visit").assertCountEquals(3)
        rule.onNodeWithText("Meril Kiprop · 3 days late").assertExists(); rule.onNodeWithText("“Check the pump and meet the committee”").assertExists()
        shot("ocv-03-visits")
        rule.onAllNodesWithTag("ocv_visit").onFirst().performScrollTo(); shot("ocv-03a-visit-cards")
        rule.onNodeWithTag("ocv_vf_late").performClick(); rule.onAllNodesWithTag("ocv_visit").assertCountEquals(1)
        rule.onNodeWithTag("ocv_vf_done").performClick(); rule.onAllNodesWithTag("ocv_visit").assertCountEquals(1)
        shot("ocv-03b-visits-done")
        rule.onNodeWithTag("ocv_vf_open").performClick(); rule.onNodeWithTag("ocv_vp_Meril Kiprop").performClick(); rule.onAllNodesWithTag("ocv_visit").assertCountEquals(2)
        rule.onNodeWithTag("ocv_vf_today").performClick(); rule.onAllNodesWithTag("ocv_visit").assertCountEquals(0); rule.onNodeWithText("Nothing here for Meril Kiprop. Try another filter.").assertExists()
        rule.onNodeWithTag("ocv_vp_all").performClick(); rule.onNodeWithTag("ocv_vf_open").performClick(); rule.waitForIdle()
        // a visit: reassign it to someone else
        rule.onAllNodesWithTag("ocv_visit").onFirst().performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("ocv_visit_sheet").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Check the pump and meet the committee").assertExists(); rule.onNodeWithTag("ocv_v_open").assertExists()
        sheetShot("ocv-04-visit-sheet")
        rule.onNodeWithTag("ocv_v_re").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(500)
        rule.onNode(hasText("Meril Kiprop") and androidx.compose.ui.test.hasClickAction() and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("ocv_v_re_box"))).performScrollTo().performClick(); rule.waitForIdle()
        rule.onAllNodes(hasText("Reid Anderson") and androidx.compose.ui.test.hasClickAction()).onLast().performClick(); rule.waitForIdle()
        rule.onNode(hasText("Reid Anderson") and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("ocv_v_re_box"))).assertExists()
        rule.onNodeWithTag("ocv_v_re_go").performScrollTo()
        sheetShot("ocv-05-visit-reassign")
        rule.onNodeWithTag("ocv_v_re_go").performClick()
        rule.waitUntil(5000) { posts.any { it.contains("\"action\":\"unassign\"") && it.contains("\"aid\":\"a1\"") } }
        posts.first { it.contains("\"action\":\"assign\"") }.let { assertTrue(it, it.contains("\"ids\":[\"104\"]") && it.contains("\"to\":\"Reid Anderson\"") && it.contains("\"note\":\"Check the pump and meet the committee\"") && it.contains("\"date\":\"" + today.minusDays(3) + "\"")) }
        rule.mainClock.advanceTimeBy(2000)
        rule.waitUntil(5000) { rule.onAllNodesWithTag("ocv_visit_sheet").fetchSemanticsNodes().isEmpty() }
        // cancel: asks first
        rule.onAllNodesWithTag("ocv_visit").onFirst().performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("ocv_visit_sheet").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("ocv_v_rm").performScrollTo().performClick()
        rule.onNodeWithTag("ocv_v_ask").assertExists()
        assertTrue(posts.count { it.contains("\"action\":\"unassign\"") } == 1)
        rule.onNodeWithTag("ocv_v_yes").performScrollTo()
        sheetShot("ocv-06-visit-cancel-ask")
        rule.onNodeWithTag("ocv_v_yes").performClick()
        rule.waitUntil(5000) { posts.count { it.contains("\"action\":\"unassign\"") } == 2 }
        rule.mainClock.advanceTimeBy(2000)
        rule.waitUntil(5000) { rule.onAllNodesWithTag("ocv_visit_sheet").fetchSemanticsNodes().isEmpty() }
        // assign a visit, in its own sheet
        rule.onNodeWithTag("ocv_assign").performScrollTo().performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("ocv_assign_sheet").fetchSemanticsNodes().isNotEmpty() }
        rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithTag("ocv_pick_105").performScrollTo().performClick(); rule.onNodeWithTag("ocv_pick_101").performScrollTo().performClick()
        rule.onNodeWithText("Assign 2 visits").assertExists()
        sheetShot("ocv-07-assign-sheet")
        rule.onNodeWithTag("ocv_assign_go").performClick()
        rule.waitUntil(5000) { posts.any { it.contains("\"action\":\"assign\"") && it.contains("\"ids\":[\"105\",\"101\"]") } }
        posts.last { it.contains("\"action\":\"assign\"") }.let { assertTrue(it, it.contains("\"to\":\"Meril Kiprop\"") && it.contains("\"date\":\"" + today.plusDays(1) + "\"")) }
        assign = JSONArray()
    }

    @Test fun office_overview_and_visits_dark_sun_kiswahili() {
        val today = java.time.LocalDate.now()
        assign = JSONArray().put(visit("a1", "104", "Kapsaret Borehole", "Meril Kiprop", today.minusDays(3), "Check the pump and meet the committee"))
            .put(visit("a3", "105", "Turbo Spring", "Reid Anderson", today.plusDays(4)))
        try {
            for ((mode, sw) in listOf("dark" to false, "sun" to false, "" to true)) {
                org.rafikioutreach.pin.ui.Look.mode = mode; Lang.sw = sw
                val tagName = if (sw) "sw" else mode
                if (mode == "dark") { val m = model(); show(m) }
                rule.onNodeWithTag("nav_office").performClick(); rule.waitForIdle()
                rule.onNodeWithTag("o_overview").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
                rule.onNodeWithTag("ocv_overview_head").performScrollTo(); shot("ocv-08-overview-$tagName")
                rule.onNodeWithTag("o_visits").performScrollTo().performClick()
                rule.waitUntil(5000) { rule.onAllNodesWithTag("ocv_visit").fetchSemanticsNodes().isNotEmpty() }
                rule.mainClock.advanceTimeBy(1000)
                rule.onNodeWithTag("ocv_visits_head").performScrollTo(); shot("ocv-09-visits-$tagName")
                rule.onAllNodesWithTag("ocv_visit").onFirst().performScrollTo(); shot("ocv-09b-visit-cards-$tagName")
                rule.onAllNodesWithTag("ocv_visit").onFirst().performScrollTo().performClick()
                rule.waitUntil(5000) { rule.onAllNodesWithTag("ocv_visit_sheet").fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithTag("ocv_v_re").performScrollTo().performClick()
                sheetShot("ocv-10-visit-sheet-$tagName")
                rule.onNodeWithTag("ocv_close").performClick(); rule.mainClock.advanceTimeBy(1000)
                rule.waitUntil(5000) { rule.onAllNodesWithTag("ocv_visit_sheet").fetchSemanticsNodes().isEmpty() }
                if (sw) {
                    assertTrue(rule.onAllNodesWithText("Gawa ziara").fetchSemanticsNodes().isNotEmpty() && rule.onAllNodesWithText("Zimechelewa · 1").fetchSemanticsNodes().isNotEmpty())
                    rule.onNodeWithTag("ocv_assign").performScrollTo().performClick()
                    rule.waitUntil(5000) { rule.onAllNodesWithTag("ocv_assign_sheet").fetchSemanticsNodes().isNotEmpty() }
                    sheetShot("ocv-11-assign-sheet-sw")
                }
            }
        } finally { org.rafikioutreach.pin.ui.Look.mode = ""; Lang.sw = false; assign = JSONArray() }
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
            rule.waitUntil(5000) { rule.onAllNodesWithTag("ct_sheet").fetchSemanticsNodes().isNotEmpty() }
            rule.mainClock.advanceTimeBy(1000)
            sheetShot("23i-team-person-dark")
            // every menu row is big enough to tap
            assertTrue(rule.onNodeWithTag("ct_newpin").fetchSemanticsNode().size.height >= with(rule.density) { 44.dp.roundToPx() })
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
        rule.onNodeWithTag("nav_me").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("me_report").performScrollTo().performClick()
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

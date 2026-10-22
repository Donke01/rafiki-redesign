package org.rafikioutreach.pin

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.rafikioutreach.pin.data.Fix
import org.rafikioutreach.pin.data.LatLng
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.data.ShareHere
import org.rafikioutreach.pin.data.Snapshot
import org.rafikioutreach.pin.ui.RafikiTheme
import org.rafikioutreach.pin.ui.TeamMapData
import org.rafikioutreach.pin.ui.parsePlan
import org.rafikioutreach.pin.ui.planDates
import org.rafikioutreach.pin.ui.planMetres
import org.rafikioutreach.pin.ui.planWhy
import org.rafikioutreach.pin.ui.PlanInfo
import org.rafikioutreach.pin.ui.planAreas
import org.rafikioutreach.pin.ui.planAreasText
import org.rafikioutreach.pin.ui.planScopeText
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** Live team map, Share my location (with the "Sharing" bar) and Office › Plan a week. Screenshots in build/shots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class TeamMapTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val posts = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ago = { min: Long -> Instant.now().minusSeconds(min * 60).toString() }

    private fun proj(id: String, t: String, area: String, lat: Double, lng: Double, type: String = "Borehole well") = JSONObject().put("id", id).put("title", t).put("country", "Kenya").put("ck", "ke")
        .put("area", area).put("type", type).put("status", "Completed").put("pump", "").put("gps", JSONArray().put(lat).put(lng))
    private fun snapshot(admin: Boolean = true) = JSONObject().put("ok", true).put("who", "Reid Anderson").put("admin", admin).put("reviewOpen", 0)
        .put("projects", JSONArray().put(proj("101", "Kapsaret Borehole", "Kapsaret, Eldoret", 0.47, 35.25)).put(proj("102", "Chepkongi Dispensary", "Kapsaret, Eldoret", 0.514, 35.27))
            .put(proj("104", "Sigowet Church Well", "Sigowet", -0.27, 35.15)).put(proj("105", "Kitale Primary Tank", "Kitale", 1.015, 35.0)).put(proj("106", "Kiminini Borehole", "Kitale", 0.9, 34.92))
            .put(proj("107", "Bungoma Bridge School", "Bungoma", 0.563, 34.56, "School")).put(proj("108", "Webuye Well", "Bungoma", 0.61, 34.77)).put(proj("109", "Moi’s Bridge Kiosk", "Eldoret", 0.87, 35.12))
            .put(proj("111", "Lessos Dispensary", "Lessos", 0.0, 0.0, "Health").apply { remove("gps") })
            // for Areas and projects: Kapsaret (8, one with no location), Kaigat (3), Ndalat (2), and long area names
            .put(proj("121", "Kapsaret Primary School", "Kapsaret, Eldoret", 0.48, 35.26, "School")).put(proj("122", "Simat Water Kiosk", "Kapsaret, Eldoret", 0.49, 35.24, "Water collection"))
            .put(proj("123", "Kapsaret Girls Secondary", "Kapsaret, Eldoret", 0.46, 35.27, "School")).put(proj("124", "Lemook Spring", "Kapsaret, Eldoret", 0.45, 35.23, "Water collection"))
            .put(proj("125", "Kapsaret Market Tank", "Kapsaret, Eldoret", 0.475, 35.255)).put(proj("127", "Kapsaret Health Centre", "Kapsaret, Eldoret", 0.0, 0.0, "Health").apply { remove("gps") })
            .put(proj("131", "Kaigat Borehole", "Kaigat, Nandi", 0.31, 35.2)).put(proj("132", "Kaigat Primary School", "Kaigat, Nandi", 0.32, 35.21, "School")).put(proj("133", "Kaigat Dispensary", "Kaigat, Nandi", 0.3, 35.19, "Health"))
            .put(proj("141", "Ndalat Well", "Ndalat", 0.2, 35.1)).put(proj("142", "Ndalat Church Tank", "Ndalat", 0.21, 35.11))
            .put(proj("151", "Elgon Estate Primary Borehole", "Elgon Estate Primary", 0.9, 34.7)).put(proj("152", "Kapsabaot Comprehensive School Rainwater Harvesting Tank", "Kapsabaot Comprehensive School", 0.95, 35.08, "School")))
        .put("repairs", JSONObject().put("104", JSONObject().put("state", "notworking").put("by", "Bertin").put("at", ago(4000)).put("urgency", "urgent").put("t", "Sigowet Church Well"))
            .put("107", JSONObject().put("state", "repair").put("by", "Grace").put("at", ago(9000)).put("t", "Bungoma Bridge School")))
        .put("visits", JSONObject().put("101", JSONObject().put("at", ago(400 * 1440)).put("by", "Meril").put("state", "working")))
        .put("jobs", JSONArray()).put("assigned", JSONArray()).put("confirmed", JSONObject()).put("me", JSONObject().put("kind", "app").put("home", "ke"))
    private fun stop(id: String, vararg why: String, age: Int? = null) = JSONObject().put("id", id).put("why", JSONArray(why.toList())).apply { if (age != null) put("age", age) else put("age", JSONObject.NULL) }
    private val plan = JSONObject().put("ok", true).put("total", 9)
        .put("days", JSONArray().put(JSONObject().put("stops", JSONArray().put(stop("104", "repair", "urgent", age = 3)).put(stop("101", "overdue", age = 400))))
            .put(JSONObject().put("stops", JSONArray().put(stop("107", "repair")).put(stop("108", "never"))))
            .put(JSONObject().put("stops", JSONArray().put(stop("105", "never", "test")).put(stop("106", "never")))))
        .put("spare", JSONArray().put(stop("109", "never")).put(stop("102", "never")))
    private fun chosen(id: String, vararg why: String) = stop(id, *why).put("chosen", true)
    // projects chosen by hand in Kapsaret: only these, or with suggestions filling the days; one of them has no location
    private val planOnly = JSONObject().put("ok", true).put("total", 2).put("chosen", 2).put("only", true)
        .put("days", JSONArray().put(JSONObject().put("stops", JSONArray().put(stop("101", "overdue", age = 400).put("chosen", true)).put(chosen("102")))))
        .put("spare", JSONArray()).put("nogps", JSONArray().put(JSONObject().put("id", "127").put("t", "Kapsaret Health Centre")))
    private val planMix = JSONObject().put("ok", true).put("total", 9).put("chosen", 2).put("only", false)
        .put("days", JSONArray().put(JSONObject().put("stops", JSONArray().put(stop("101", "overdue", age = 400).put("chosen", true)).put(stop("124", "never"))))
            .put(JSONObject().put("stops", JSONArray().put(chosen("102")).put(stop("131", "never")))))
        .put("spare", JSONArray().put(stop("132", "never"))).put("nogps", JSONArray().put(JSONObject().put("id", "127").put("t", "Kapsaret Health Centre")))
    private val teammap = JSONObject().put("ok", true)
        .put("people", JSONArray().put(JSONObject().put("name", "Bertin Mwangi").put("lat", 0.52).put("lng", 35.28).put("acc", 12).put("at", ago(12)).put("phone", "0722 111 222").put("home", "ke"))
            .put(JSONObject().put("name", "Grace Achieng").put("lat", 0.58).put("lng", 34.6).put("acc", 30).put("at", ago(95)).put("phone", "").put("home", "ke"))
            .put(JSONObject().put("name", "Meril Kiprop").put("lat", 1.0).put("lng", 35.02).put("acc", 8).put("at", ago(3)).put("phone", "+254 712 345 678").put("home", "ke")))
        .put("visits", JSONArray().put(JSONObject().put("id", "102").put("by", "Bertin Mwangi").put("at", ago(70)).put("s", "working").put("t", "Chepkongi Dispensary"))
            .put(JSONObject().put("id", "101").put("by", "Bertin Mwangi").put("at", ago(150)).put("s", "repair").put("t", "Kapsaret Borehole"))
            .put(JSONObject().put("id", "107").put("by", "Grace Achieng").put("at", ago(120)).put("s", "notworking").put("t", "Bungoma Bridge School")))

    @Before fun setUp() { org.rafikioutreach.pin.ui.Onb.enabled = false; org.rafikioutreach.pin.ui.Motion.loops = false; Lang.sw = false; ShareHere.save(app, ShareHere.Hours()); posts.clear() }
    @After fun tearDown() { org.rafikioutreach.pin.ui.Motion.loops = true; Lang.sw = false; org.rafikioutreach.pin.ui.Look.mode = "" }

    private fun model(admin: Boolean = true): AppModel {
        val snap = snapshot(admin)
        Prefs(app).apply { pin = "9999"; name = "Reid Anderson"; saveSnapshot(snap); filter = "all"; country = "ke" }
        val m = AppModel(app)
        m.api.transport = { method, url, _, body ->
            posts += "$method $url ${body?.let { String(it) } ?: ""}"
            when {
                url.contains("view=teammap") -> 200 to teammap.toString()
                url.contains("view=plan") && url.contains("ids=") -> 200 to (if (url.contains("only=1")) planOnly else planMix).toString()
                url.contains("view=plan") -> 200 to plan.toString()
                url.contains("view=team") -> 200 to JSONObject().put("ok", true).put("team", JSONArray().put(JSONObject().put("name", "Meril Kiprop")).put(JSONObject().put("name", "Bertin Mwangi"))).toString()
                url.contains("activity=1") -> 200 to "{\"ok\":true,\"activity\":[]}"
                url.contains("team=1") -> 200 to "{\"ok\":true,\"team\":[]}"
                url.contains("pin-auth/me") -> 200 to "{\"ok\":true,\"available\":false}"
                body != null && String(body).contains("\"assign\"") -> 200 to JSONObject().put("ok", true).put("items", JSONObject(String(body)).getJSONArray("ids")).toString()
                method == "GET" -> 200 to snap.toString()
                else -> 200 to "{\"ok\":true}"
            }
        }
        return m
    }
    private fun show(m: AppModel, dark: Boolean = false) = rule.setContent { RafikiTheme(dark = dark) { Box(Modifier.size(390.dp, 844.dp)) { App(m) } } }
    private fun shot(name: String) {
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun waitFor(text: String) = try { rule.waitUntil(8000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() } } catch (e: Throwable) {
        rule.onAllNodes(androidx.compose.ui.test.isRoot()).let { r -> r.fetchSemanticsNodes().indices.forEach { println("TREE $it for '$text':\n" + r[it].printToString(maxDepth = 60)) } }; throw e
    }
    /** A sheet lives in its own window: draw it over the screen, the way it looks on the phone. */
    private fun sheetShot(name: String) {
        rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); val c = Canvas(b); v.draw(c); c.drawColor(0x66000000)
        val d = org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window?.decorView
        if (d != null && d.width > 0) { c.save(); c.translate(0f, (v.height - d.height).toFloat().coerceAtLeast(0f)); d.draw(c); c.restore() }
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    /** An area row lives in its own short scrolling list: bring it into that list's view first. */
    private fun tickArea(name: String) {
        rule.onNodeWithTag("plan_area_list").performScrollTo().performScrollToNode(androidx.compose.ui.test.hasTestTag("plan_area_$name"))
        rule.onNodeWithTag("plan_area_$name").performClick(); rule.waitForIdle()
    }
    private fun waitTag(tag: String) = rule.waitUntil(8000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    /** Work hours around now, so the test doesn't depend on the time of day. */
    private fun hoursNow(on: Boolean) = LocalDateTime.now().let { n -> val m = n.hour * 60 + n.minute; ShareHere.Hours(on, (1..7).toSet(), (m - 60 + 1440) % 1440, (m + 60) % 1440) }

    @Test fun work_hours_and_dates() {
        val h = ShareHere.Hours()
        assertFalse("off by default", h.on)
        assertEquals("Mon–Sat · 07:00–18:00", ShareHere.hoursText(h))
        assertTrue(ShareHere.inHours(h, LocalDateTime.of(2026, 10, 21, 10, 30)))      // Wednesday morning
        assertFalse(ShareHere.inHours(h, LocalDateTime.of(2026, 10, 21, 18, 0)))       // 18:00 is home time
        assertFalse(ShareHere.inHours(h, LocalDateTime.of(2026, 10, 21, 6, 59)))
        assertFalse(ShareHere.inHours(h, LocalDateTime.of(2026, 10, 25, 10, 0)))       // Sunday
        assertTrue(ShareHere.inHours(h.copy(from = 22 * 60, to = 6 * 60), LocalDateTime.of(2026, 10, 21, 23, 0)))   // a night shift
        assertFalse(ShareHere.active(h.copy(on = true), ownName = false, now = LocalDateTime.of(2026, 10, 21, 10, 30)))  // shared team PIN: never
        assertEquals(listOf("2026-10-24", "2026-10-26", "2026-10-27"), planDates(LocalDate.parse("2026-10-24"), 3).map { it.toString() })
        assertEquals("Mon, Wed · 08:30–17:00", ShareHere.hoursText(ShareHere.Hours(true, setOf(1, 3), 510, 1020)))
    }

    @Test fun plan_parsing_and_reasons() {
        val s = Snapshot.from(snapshot())
        val p = parsePlan(plan, "Meril Kiprop", LocalDate.parse("2026-10-24"), s)
        assertEquals(3, p.days.size); assertEquals(listOf("109", "102"), p.spare)
        assertEquals(LocalDate.parse("2026-10-26"), p.dates[1])
        assertTrue(planMetres(p.days[0], s) > 50_000)
        assertEquals(listOf("Not working", "Urgent"), planWhy(p.info["104"], "notworking").map { it.second })
        assertEquals("Not visited for 13 months", planWhy(PlanInfo(listOf("overdue"), 400), null)[0].second)
        val tm = TeamMapData.from(teammap)
        assertEquals(3, tm.people.size); assertEquals(2, tm.visits.count { it.by == "Bertin Mwangi" })
    }

    @Test fun share_my_location_settings_and_bar() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val m = model(admin = false); show(m)
        m.locator.pretend(Fix(LatLng(0.5205, 35.2699), 15, System.currentTimeMillis()))
        rule.onNodeWithTag("nav_me").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("set_share").performScrollTo(); waitFor("Share my location with the office")
        rule.onNodeWithText("Off", useUnmergedTree = true).assertExists()
        shot("33-settings-share-off")
        rule.onNodeWithTag("set_share").performClick(); waitTag("share_switch")
        assertTrue(rule.onAllNodesWithTag("share_bar").fetchSemanticsNodes().isEmpty())
        assertFalse(posts.any { it.contains("\"here\"") })
        ShareHere.save(app, hoursNow(false))
        rule.onNodeWithTag("share_switch").performClick(); rule.waitForIdle()
        assertTrue(ShareHere.load(app).on)
        rule.waitUntil(8000) { posts.any { it.contains("\"action\":\"here\"") && it.contains("0.5205") } }
        waitTag("share_bar"); waitFor("Sharing now")
        shot("34-share-settings-on")
        // at most every 10 minutes
        m.locator.pretend(Fix(LatLng(0.53, 35.27), 10, System.currentTimeMillis() + 120_000)); rule.mainClock.advanceTimeBy(61_000); rule.waitForIdle()
        assertEquals(1, posts.count { it.contains("\"action\":\"here\"") })
        rule.onNodeWithTag("nav_list").performClick(); rule.waitForIdle()
        shot("35-sharing-bar-projects")
        rule.onNodeWithTag("share_stop").performClick()
        rule.waitUntil(8000) { posts.any { it.contains("\"off\":true") } }
        assertFalse(ShareHere.load(app).on)
        rule.waitUntil(5000) { rule.onAllNodesWithTag("share_bar").fetchSemanticsNodes().isEmpty() }
        // a field officer has no Office and never asks for the team map
        assertTrue(rule.onAllNodesWithTag("nav_office").fetchSemanticsNodes().isEmpty())
        assertFalse(posts.any { it.contains("teammap") })
    }

    @Test fun share_page_in_kiswahili() {
        Lang.sw = true
        val m = model(); show(m)
        rule.onNodeWithTag("nav_me").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("set_share").performScrollTo().performClick(); waitTag("share_switch")
        waitFor("Shiriki mahali nilipo"); rule.onNodeWithText("Jtt").assertExists()
        shot("36-share-sw")
    }

    @Test fun office_team_map() {
        val m = model(); show(m)
        rule.onNodeWithTag("nav_office").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("o_teammap").performClick()
        waitFor("3 teammates sharing their location")
        waitFor("Last seen 12 min ago")
        rule.onNodeWithTag("tm_map").assertExists()
        rule.onNodeWithText("Not seen for over an hour", substring = true).assertExists()
        rule.onNodeWithTag("tm_head").performScrollTo()
        shot("37-office-teammap")
        // who shows here: folded
        rule.onAllNodesWithTag("tm_privacy_body").assertCountEquals(0)
        rule.onNodeWithTag("tm_privacy_toggle").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
        rule.onNodeWithText("Field officers never see this map", substring = true).assertExists()
        rule.onNodeWithTag("tm_person_Grace Achieng").performScrollTo()
        shot("37b-office-teammap-list")
        // a person: a sheet with call, WhatsApp and today's visits
        rule.onNodeWithTag("tm_person_Bertin Mwangi").performScrollTo().performClick()
        waitTag("tm_sheet"); waitFor("Chepkongi Dispensary")
        rule.onNodeWithText("Kapsaret Borehole").assertExists()
        rule.onNodeWithTag("tm_call").assertExists(); rule.onNodeWithTag("tm_wa").assertExists()
        sheetShot("38-office-teammap-person")
        rule.onNodeWithTag("tm_onmap").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(1500)
        rule.waitUntil(5000) { rule.onAllNodesWithTag("tm_sheet").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("tm_person_Grace Achieng").performScrollTo().performClick()
        waitTag("tm_sheet"); rule.onNodeWithTag("tm_nophone").assertExists()
        rule.onNodeWithTag("tm_close").performClick(); rule.mainClock.advanceTimeBy(1500)
        assertTrue(posts.any { it.contains("view=teammap") })
    }

    @Test fun office_team_map_dark() {
        val m = model(); show(m, dark = true)
        rule.onNodeWithTag("nav_office").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("o_teammap").performClick()
        waitFor("3 teammates sharing their location")
        rule.onNodeWithTag("tm_head").performScrollTo()
        shot("39-office-teammap-dark")
        rule.onNodeWithTag("tm_person_Meril Kiprop").performScrollTo().performClick()
        waitTag("tm_sheet")
        sheetShot("39b-office-teammap-person-dark")
    }

    @Test fun office_team_map_and_plan_kiswahili() {
        Lang.sw = true
        val m = model(); show(m)
        rule.onNodeWithTag("nav_office").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("o_teammap").performClick()
        waitFor("Wenzako 3 wanashiriki mahali"); waitFor("Wanashiriki sasa")
        rule.onNodeWithTag("tm_head").performScrollTo()
        shot("39c-office-teammap-sw")
        rule.onNodeWithTag("o_plan").performScrollTo().performClick()
        waitFor("Panga wiki"); waitFor("Bertin Mwangi")
        rule.onNodeWithTag("plan_go").performScrollTo().performClick()
        waitTag("plan_day_2")
        rule.onNodeWithTag("plan_day_0").performScrollTo()
        shot("44-plan-sw")
        rule.onNodeWithTag("plan_stop_104").performScrollTo().performClick()
        waitTag("plan_sheet"); waitFor("Fungua mradi")
        sheetShot("44b-plan-stop-sw")
    }

    @Test fun office_plan_a_week() {
        val m = model(); show(m)
        rule.onNodeWithTag("nav_office").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("o_plan").performClick()
        waitFor("Plan a week"); waitFor("Bertin Mwangi")
        rule.onNodeWithTag("plan_to").performScrollTo().performClick(); rule.waitForIdle()
        rule.onAllNodesWithText("Meril Kiprop").onFirst().performClick(); rule.waitForIdle()
        waitFor("Meril Kiprop · Kenya · all areas · 5 days · 5 a day")
        rule.onAllNodesWithTag("plan_how_body").assertCountEquals(0)
        rule.onNodeWithTag("plan_head").performScrollTo()
        shot("40-plan-form")
        rule.onNodeWithTag("plan_go").performScrollTo().performClick()
        waitTag("plan_sum")
        assertTrue(posts.any { it.contains("view=plan&ck=ke") && it.contains("days=5") })
        // after the first run the choices fold away
        rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        rule.onAllNodesWithTag("plan_choices_body").assertCountEquals(0)
        waitTag("plan_day_2")
        rule.onAllNodesWithText("Urgent").onFirst().assertExists()
        rule.onAllNodesWithText("Water test due").onFirst().assertExists()
        rule.mainClock.advanceTimeBy(1000)
        rule.onNodeWithTag("plan_sum").performScrollTo()
        shot("41-plan-result")
        rule.onNodeWithTag("plan_day_0").performScrollTo()
        shot("41b-plan-days")
        // a stop opens a sheet: take it off day 1
        rule.onNodeWithTag("plan_stop_101").performScrollTo().performClick()
        waitTag("plan_sheet"); waitFor("Stop 2 of 2")
        assertEquals(2, rule.onAllNodesWithText("Not visited for 13 months").fetchSemanticsNodes().size)   // on the day card and in the sheet
        sheetShot("42-plan-stop-sheet")
        rule.onNodeWithTag("plan_rm").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(1500)
        rule.waitUntil(5000) { rule.onAllNodesWithTag("plan_sheet").fetchSemanticsNodes().isEmpty() }
        rule.onAllNodesWithTag("plan_stop_101").assertCountEquals(0)
        waitFor("Taken off day 1")
        rule.onNodeWithTag("plan_add_1").performScrollTo().performClick(); rule.waitForIdle()
        rule.onAllNodesWithText("Moi’s Bridge Kiosk", substring = true).onFirst().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("plan_day_1").performScrollTo()
        shot("42b-plan-edited")
        rule.onNodeWithTag("plan_assign").performScrollTo().performClick()
        rule.waitUntil(8000) { rule.onAllNodesWithText("Assigned ✓", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val asg = posts.filter { it.contains("\"action\":\"assign\"") }.map { JSONObject(it.substringAfter(" ").substringAfter(" ")) }
        assertEquals(3, asg.size)
        assertEquals(listOf("104"), (0 until asg[0].getJSONArray("ids").length()).map { asg[0].getJSONArray("ids").getString(it) })
        assertTrue(asg[1].getJSONArray("ids").toString().contains("109"))
        assertTrue(asg.all { it.getString("to") == "Meril Kiprop" })
        assertEquals(planDates(LocalDate.parse(asg[0].getString("date")), 3).map { it.toString() }, asg.map { it.getString("date") })
        assertTrue(asg[1].getString("note").contains("Week plan, day 2 of 3"))
        rule.onNodeWithTag("plan_assign").performScrollTo()
        shot("43-plan-assigned")
        // the stop sheet of an assigned plan has no "take it off"
        rule.onNodeWithTag("plan_stop_104").performScrollTo().performClick()
        waitTag("plan_sheet"); rule.onAllNodesWithTag("plan_rm").assertCountEquals(0)
    }

    @Test fun plan_areas_and_scope_text() {
        val ps = Snapshot.from(snapshot()).projects
        val a = planAreas(ps, "ke")
        assertEquals("Kapsaret" to 8, a[0]); assertEquals("Kaigat" to 3, a[1])
        assertTrue(a.contains("Kapsabaot Comprehensive School" to 1))
        assertEquals("All of Kenya", planAreasText(emptyList(), "ke"))
        assertEquals("Kapsaret, Kaigat", planAreasText(listOf("Kapsaret", "Kaigat"), "ke"))
        assertEquals("Kapsaret, Kaigat +1 more", planAreasText(listOf("Kapsaret", "Kaigat", "Ndalat"), "ke"))
        assertEquals("All of Kenya · suggestions from every area", planScopeText(ps, "ke", emptyList(), emptyList()))
        assertEquals("All of Kenya · 2 chosen by hand", planScopeText(ps, "ke", emptyList(), listOf("101", "141")))
        assertEquals("Kapsaret: 3 of 8 projects · Kaigat: all 3", planScopeText(ps, "ke", listOf("Kapsaret", "Kaigat"), listOf("101", "102", "127")))
        assertEquals("Ndalat: all 2 · Kapsabaot Comprehensive School: 1 of 1 project", planScopeText(ps, "ke", listOf("Ndalat", "Kapsabaot Comprehensive School"), listOf("152")))
        assertEquals("Elgon Estate Primary: its 1 project", planScopeText(ps, "ke", listOf("Elgon Estate Primary"), emptyList()))
        Lang.sw = true
        assertEquals("Kapsaret: miradi 3 kati ya 8 · Kaigat: yote 3", planScopeText(ps, "ke", listOf("Kapsaret", "Kaigat"), listOf("101", "102", "127")))
        assertEquals("Yote ya Kenya", planAreasText(emptyList(), "ke"))
    }

    @Test fun office_plan_areas_and_projects() {
        val m = model(); show(m)
        rule.onNodeWithTag("nav_office").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("o_plan").performClick()
        waitFor("Plan a week"); waitFor("Bertin Mwangi")
        waitFor("All of Kenya · suggestions from every area")
        rule.onAllNodesWithTag("plan_only_row").assertCountEquals(0)
        rule.onNodeWithTag("plan_area").performScrollTo().performClick(); waitTag("plan_pick_sheet")
        waitFor("None ticked: projects from all of Kenya."); waitFor("Projects in Kenya")
        rule.onNodeWithText("Name or place").performScrollTo().performTextInput("ziwa")
        rule.waitForIdle(); waitFor("No projects match.")
        rule.onNodeWithText("ziwa").performTextClearance(); rule.waitForIdle()
        tickArea("Kapsaret")
        tickArea("Kaigat"); rule.waitForIdle()
        waitFor("2 ticked"); waitFor("Projects in these areas")
        rule.onAllNodesWithTag("plan_group_Bungoma").assertCountEquals(0)
        rule.onNodeWithTag("plan_group_Kapsaret").assertExists(); rule.onNodeWithTag("plan_group_Kaigat").assertExists()
        listOf("101", "102", "127").forEach { rule.onNodeWithTag("plan_pick_$it").performScrollTo().performClick() }
        waitFor("3 chosen"); waitFor("3 of 8 chosen")
        rule.onAllNodesWithText("No location").onFirst().assertExists()
        rule.onNodeWithTag("plan_all_Kaigat").performScrollTo().performClick(); waitFor("6 chosen")
        rule.onNodeWithTag("plan_none_Kaigat").performScrollTo().performClick(); waitFor("3 chosen")
        rule.onNodeWithTag("plan_pick_only").performScrollTo().performClick(); rule.waitForIdle()
        rule.onAllNodesWithTag("plan_pick_121").assertCountEquals(0); rule.onAllNodesWithTag("plan_group_Kaigat").assertCountEquals(0)
        rule.onNodeWithTag("plan_pick_only").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("plan_area_n").performScrollTo()
        sheetShot("45-plan-areas")
        rule.onNodeWithTag("plan_group_Kapsaret").performScrollTo()
        sheetShot("45a-plan-projects")
        rule.onNodeWithTag("plan_pick_done").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(1500)
        rule.waitUntil(5000) { rule.onAllNodesWithTag("plan_pick_sheet").fetchSemanticsNodes().isEmpty() }
        waitFor("Kapsaret: 3 of 8 projects · Kaigat: all 3")
        rule.onAllNodesWithText("Kapsaret, Kaigat").onFirst().assertExists()
        rule.onNodeWithTag("plan_only").performScrollTo().performClick(); rule.waitForIdle()
        waitFor("only the 3 chosen")
        rule.onNodeWithTag("plan_area").performScrollTo()
        shot("45b-plan-form-chosen")
        rule.onNodeWithTag("plan_go").performScrollTo().performClick()
        waitTag("plan_nogps"); waitFor("Kapsaret Health Centre")
        val q = posts.last { it.contains("view=plan") }
        assertTrue(q, q.contains("areas=Kapsaret%2CKaigat") && q.contains("ids=101%2C102%2C127") && q.contains("only=1") && !q.contains("&area="))
        waitFor("No location, so not placed")
        assertEquals(2, rule.onAllNodesWithText("Chosen").fetchSemanticsNodes().size)
        rule.onNodeWithTag("plan_nogps").performScrollTo()
        shot("45c-plan-only-chosen")
        // with suggestions: the chosen ones stand out
        rule.onNodeWithTag("plan_choices_toggle").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
        rule.onNodeWithTag("plan_only").performScrollTo().performClick()
        rule.onNodeWithTag("plan_go").performScrollTo().performClick()
        waitTag("plan_day_1"); waitFor("Lemook Spring")
        val q2 = posts.last { it.contains("view=plan") }
        assertTrue(q2, q2.contains("ids=101%2C102%2C127") && !q2.contains("only=1"))
        assertEquals(2, rule.onAllNodesWithText("Chosen").fetchSemanticsNodes().size)
        rule.onNodeWithTag("plan_day_0").performScrollTo()
        shot("45d-plan-chosen-with-suggestions")
        // the summary opens the picker; unticking an area lets go of its projects; a new country starts again
        rule.onNodeWithTag("plan_choices_toggle").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
        rule.onNodeWithTag("plan_pick").performScrollTo().performClick(); waitTag("plan_pick_sheet")
        tickArea("Kapsaret"); rule.waitForIdle()
        waitFor("0 chosen"); rule.onAllNodesWithTag("plan_group_Kapsaret").assertCountEquals(0)
        tickArea("Kaigat"); rule.waitForIdle()
        rule.onNodeWithTag("plan_pick_152").performScrollTo().performClick()
        tickArea("Ndalat"); rule.waitForIdle()
        waitFor("2 ticked")
        rule.onNodeWithTag("plan_group_Kapsabaot Comprehensive School").performScrollTo()
        sheetShot("45e-plan-long-area")
        rule.onNodeWithTag("plan_pick_done").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(1500)
        rule.waitUntil(5000) { rule.onAllNodesWithTag("plan_pick_sheet").fetchSemanticsNodes().isEmpty() }
        waitFor("Ndalat: all 2 · Kapsabaot Comprehensive School: 1 of 1 project")
        rule.onNodeWithTag("plan_pick").performScrollTo()
        shot("45f-plan-form-long-area")
    }

    @Test fun office_plan_areas_kiswahili_dark() {
        Lang.sw = true
        val m = model(); show(m, dark = true)
        rule.onNodeWithTag("nav_office").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("o_plan").performClick()
        waitFor("Panga wiki"); waitFor("Yote ya Kenya · mapendekezo kutoka maeneo yote")
        rule.onNodeWithTag("plan_area").performScrollTo().performClick(); waitTag("plan_pick_sheet")
        tickArea("Kapsaret")
        tickArea("Kapsabaot Comprehensive School"); rule.waitForIdle()
        rule.onNodeWithTag("plan_pick_101").performScrollTo().performClick()
        waitFor("1 zimechaguliwa"); waitFor("Maeneo na miradi"); waitFor("Chagua zote")
        rule.onNodeWithTag("plan_area_n").performScrollTo()
        sheetShot("46-plan-areas-sw-dark")
        rule.onNodeWithTag("plan_group_Kapsaret").performScrollTo()
        sheetShot("46a-plan-projects-sw-dark")
        rule.onNodeWithTag("plan_pick_done").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(1500)
        rule.waitUntil(5000) { rule.onAllNodesWithTag("plan_pick_sheet").fetchSemanticsNodes().isEmpty() }
        waitFor("Kapsaret: miradi 1 kati ya 8 · Kapsabaot Comprehensive School: mradi wake 1")
        waitFor("Miradi hii tu")
        rule.onNodeWithTag("plan_area").performScrollTo()
        shot("46b-plan-form-chosen-sw-dark")
    }

    @Test fun office_plan_areas_sun() {
        org.rafikioutreach.pin.ui.Look.mode = "sun"
        val m = model(); show(m)
        rule.onNodeWithTag("nav_office").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("o_plan").performClick()
        waitFor("Plan a week")
        rule.onNodeWithTag("plan_area").performScrollTo().performClick(); waitTag("plan_pick_sheet")
        tickArea("Kapsaret"); rule.waitForIdle()
        rule.onNodeWithTag("plan_pick_102").performScrollTo().performClick()
        rule.onNodeWithTag("plan_group_Kapsaret").performScrollTo()
        sheetShot("47-plan-projects-sun")
    }
}

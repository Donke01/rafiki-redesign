package org.rafikioutreach.pin

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.rafikioutreach.pin.data.Approval
import org.rafikioutreach.pin.data.CostView
import org.rafikioutreach.pin.data.Money
import org.rafikioutreach.pin.data.PeopleServed
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.data.Repair
import org.rafikioutreach.pin.data.Snapshot
import org.rafikioutreach.pin.ui.RafikiTheme
import org.rafikioutreach.pin.ui.costsCsv
import org.rafikioutreach.pin.ui.summaryCsv
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Repair costs, people served and the second approval for big repairs, in the native app. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class CostsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val posts = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun proj(id: String, t: String, ck: String, type: String, gps: JSONArray?) = JSONObject().put("id", id).put("title", t).put("country", org.rafikioutreach.pin.data.COUNTRIES[ck]).put("ck", ck).put("area", "Eldoret")
        .put("type", type).put("status", "Completed").put("pump", "").apply { if (gps != null) put("gps", gps) }
    private val people = JSONObject().put("total", 48250).put("counted", 61).put("measured", 23)
        .put("byCountry", JSONObject().put("ke", 26400).put("tz", 12850).put("ug", 3100).put("mw", 5900))
        .put("byType", JSONObject().put("water", 39800).put("school", 5200).put("health", 2400).put("children", 850))
        .put("trend", JSONArray().apply { (0 until 12).forEach { i -> put(JSONObject().put("m", "2025-%02d".format(i % 12 + 1)).put("n", if (i < 2) JSONObject.NULL else if (i == 11) 48250 else 41000 + i * 650)) } })
    private fun snapshot(who: String = "Reid Anderson", admin: Boolean = true) = JSONObject().put("ok", true).put("who", who).put("admin", admin).put("reviewOpen", 2).put("approveAbove", 50000)
        .put("projects", JSONArray().put(proj("104", "Kapsaret Borehole", "ke", "Borehole well", JSONArray().put(0.47).put(35.25))).put(proj("105", "Magara Well", "tz", "Borehole well", JSONArray().put(-4.2).put(35.7)))
            .put(proj("101", "Chepkongi Dispensary", "ke", "Health", JSONArray().put(0.52).put(35.28))))
        .put("repairs", JSONObject()
            .put("104", JSONObject().put("state", "notworking").put("by", "Bertin").put("at", "2026-10-18T08:00:00Z").put("t", "Kapsaret Borehole").put("urgency", "urgent").put("cost", 85000).put("parts", "Rising main pipes").put("step", "assigned").put("assignee", "Meril Kiprop"))
            .put("105", JSONObject().put("state", "repair").put("by", "Amina").put("at", "2026-10-17T08:00:00Z").put("t", "Magara Well").put("cost", 70000).put("step", "assigned").put("assignee", "Bertin Mwangi")
                .put("approval", JSONObject().put("status", "pending").put("rid", "c1").put("by", "Jessica").put("at", "2026-10-20T08:00:00Z").put("est", 70000)))
            .put("101", JSONObject().put("state", "repair").put("by", "Grace").put("at", "2026-10-19T08:00:00Z").put("t", "Chepkongi Dispensary").put("cost", 6000).put("step", "assigned").put("assignee", "Reid Anderson")))
        .put("visits", JSONObject()).put("jobs", JSONArray().put(if (who == "Reid Anderson") "101" else "104")).put("assigned", JSONArray()).put("confirmed", JSONObject())
        .apply { if (admin) put("people", people) }
    private val costs = JSONObject().put("ok", true).put("approveAbove", 50000)
        .put("rows", JSONArray().put(JSONObject().put("id", "104").put("t", "Kapsaret Borehole").put("rid", "2026-10-01T08:00:00Z").put("at", "2026-10-20T09:00:00Z").put("what", "Pump cylinder").put("est", 30000).put("actual", 28500).put("by", "Meril Kiprop").put("step", "parts").put("receipt", true))
            .put(JSONObject().put("id", "105").put("t", "Magara Well").put("rid", "2026-03-01T08:00:00Z").put("at", "2026-03-04T09:00:00Z").put("what", "Handle and pins").put("est", 9000).put("actual", 8200).put("by", "Bertin Mwangi").put("step", "fixed")))
        .put("stats", JSONObject().put("month", 28500).put("year", 36700).put("sum12", 36700).put("repairs", 2).put("avg", 18350).put("yearKey", 2026).put("monthKey", "2026-10")
            .put("byCountry", JSONObject().put("ke", 28500).put("tz", 8200))
            .put("top", JSONArray().put(JSONObject().put("id", "104").put("t", "Kapsaret Borehole").put("ck", "ke").put("sum", 28500).put("n", 1)).put(JSONObject().put("id", "105").put("t", "Magara Well").put("ck", "tz").put("sum", 8200).put("n", 1))))
    private val review = JSONObject().put("ok", true).put("items", JSONArray()
        .put(JSONObject().put("rid", "c1").put("kind", "cost").put("id", "105").put("t", "Magara Well").put("by", "Jessica").put("at", "2026-10-20T08:00:00Z").put("status", "open").put("data", JSONObject().put("est", 70000).put("parts", "New pump head").put("note", "Quote from Babati Hardware")))
        .put(JSONObject().put("rid", "c2").put("kind", "cost").put("id", "104").put("t", "Kapsaret Borehole").put("by", "Reid Anderson").put("at", "2026-10-21T08:00:00Z").put("status", "open").put("data", JSONObject().put("est", 85000))))

    private fun model(who: String = "Reid Anderson", admin: Boolean = true): AppModel {
        org.rafikioutreach.pin.ui.Onb.enabled = false
        val app = ApplicationProvider.getApplicationContext<Application>()
        Prefs(app).apply { pin = "9999"; name = who; saveSnapshot(snapshot(who, admin)); filter = "all" }
        val m = AppModel(app)
        m.api.transport = { method, url, _, body ->
            val b = body?.let { String(it) } ?: ""
            posts += "$method $url $b"
            when {
                url.contains("view=costs") -> 200 to costs.toString()
                url.contains("view=review") -> 200 to review.toString()
                url.contains("view=team") -> 200 to JSONObject().put("ok", true).put("team", JSONArray().put(JSONObject().put("name", "Meril Kiprop").put("source", "app")).put(JSONObject().put("name", "Reid Anderson").put("admin", true).put("source", "cloudflare"))).toString()
                url.contains("activity=1") -> 200 to "{\"ok\":true,\"activity\":[]}"
                url.contains("pin-auth/me") -> 200 to "{\"ok\":true,\"available\":false}"
                url.contains("/api/pin?id=104") -> 200 to "{\"ok\":true,\"last\":null,\"visits\":[],\"costs\":{\"sum\":36500,\"n\":3,\"last\":[{\"at\":\"2026-10-20T09:00:00Z\",\"what\":\"Fitting\",\"actual\":3500,\"by\":\"Meril Kiprop\"}]}}"
                method == "POST" && b.contains("\"action\":\"job\"") && b.contains("\"id\":\"104\"") && b.contains("\"step\":\"parts\"") ->
                    200 to JSONObject().put("ok", true).put("held", "parts").put("asked", true).put("job", snapshot().getJSONObject("repairs").getJSONObject("104").put("approval", JSONObject().put("status", "pending").put("by", "Reid Anderson").put("est", 85000))).toString()
                method == "POST" && b.contains("\"action\":\"parts\"") -> 200 to JSONObject().put("ok", true).put("held", "parts").put("asked", true).put("updateId", "7").toString()
                method == "POST" && b.contains("\"action\":\"settings\"") -> 200 to "{\"ok\":true,\"approveAbove\":50000}"
                method == "GET" -> 200 to snapshot(who, admin).toString()
                else -> 200 to "{\"ok\":true,\"updateId\":\"8\"}"
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
    private fun waitFor(text: String) = rule.waitUntil(5000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test fun approval_rules_match_the_server() {
        val big = Repair("notworking", "Bertin", "2026-10-18", "", "urgent", emptyList(), cost = 85000, step = "assigned")
        assertEquals(85000, Money.estOf(big.copy(budget = 60000)))
        assertEquals("needed", Money.state(big, 50000)); assertEquals("none", Money.state(big.copy(cost = 50000), 50000))
        assertEquals("pending", Money.state(big.copy(approval = Approval("pending", "c1", "Reid", "", 85000)), 50000))
        assertEquals("approved", Money.state(big.copy(approval = Approval("approved", "c1", "Reid", "", 85000, "Jessica")), 50000))
        assertEquals("needed", Money.state(big.copy(budget = 95000, approval = Approval("approved", "c1", "Reid", "", 85000, "Jessica")), 50000))
        assertEquals("needed", Money.blocked(big, "parts", 50000)); assertEquals("", Money.blocked(big, "assigned", 50000))
        assertEquals("", Money.blocked(big.copy(step = "parts"), "fixed", 50000))
        assertFalse(Money.canDecide("Reid Anderson", " reid anderson")); assertTrue(Money.canDecide("Reid Anderson", "Jessica")); assertFalse(Money.canDecide("", ""))
        assertEquals(12500L, Money.parse("12,500")); assertNull(Money.parse("")); assertNull(Money.parse("-3"))
        assertEquals("KES 85,000", Money.kes(85000))
        val s = Snapshot.from(snapshot())
        assertEquals("pending", s.repairs["105"]!!.approval!!.status); assertEquals(50000, s.approveAbove)
        val p = s.people!!; assertEquals(48250, p.total); assertEquals(48250 - 42300, p.growth.toInt()); assertEquals(12, p.trend.size); assertNull(p.trend[0].second)
        val v = CostView.from(costs)
        assertEquals("104", v.rows.first().id)
        val csv = costsCsv(v, s.projects)
        assertTrue(csv.startsWith("Date,Project ID,Project,Country")); assertTrue(csv.contains("2026-10-20,104,Kapsaret Borehole,Kenya,2026-10-01,Parts ordered,Pump cylinder,30000,28500,Meril Kiprop,yes"))
        val sum = summaryCsv(v, p)
        assertTrue(sum.contains("Repair spending,Average per repair (last 12 months),18350,")); assertTrue(sum.contains("Top 10 water points (last 12 months),1. Kapsaret Borehole (Kenya),28500,1")); assertTrue(sum.contains("People served,Total,48250,"))
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
    private fun sheet(tag: String) = rule.waitUntil(5000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun closeSheet(tag: String) { rule.onNodeWithTag("ocv_close").performClick(); rule.mainClock.advanceTimeBy(1000); rule.waitUntil(5000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() } }

    @Test fun people_served_and_costs() {
        Lang.sw = false
        val m = model(); show(m)
        rule.onNodeWithTag("nav_office").performClick()
        waitFor("people served")
        // people served: a tile on the overview, the details in a sheet
        rule.onNodeWithTag("ocv_t_people").performScrollTo(); rule.mainClock.advanceTimeBy(1500)
        rule.onAllNodesWithText("+5,950 in 12 months").onFirst().assertExists()
        rule.onNodeWithTag("ocv_t_people").performClick(); sheet("ocv_people_sheet"); rule.mainClock.advanceTimeBy(2000)
        rule.onNodeWithTag("people_total").assertExists(); rule.onAllNodesWithText("48,250").onFirst().assertExists()
        rule.onNodeWithText("Water points").assertExists(); rule.onNodeWithText("26,400").assertExists()
        sheetShot("60-office-people-served")
        closeSheet("ocv_people_sheet")
        rule.onNodeWithTag("o_costs").performScrollTo().performClick()
        waitFor("Spent each month")
        rule.mainClock.advanceTimeBy(1500)
        rule.onNodeWithText("18,350").assertExists(); rule.onNodeWithText("average per repair").assertExists(); rule.onNodeWithText("2 repairs").assertExists()
        rule.onNodeWithTag("ocv_costs_head").performScrollTo(); shot("61-office-costs")
        // the chart: tap a month
        val first = java.time.YearMonth.now().minusMonths(11)
        rule.onNodeWithTag("ocv_bar_$first").performScrollTo().performClick(); rule.waitForIdle()
        assertTrue(rule.onNodeWithTag("ocv_chart_sel").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString().contains(first.year.toString()))
        // a cost: its sheet with the project's cost history
        rule.onAllNodesWithTag("ocv_cost").assertCountEquals(2)
        rule.onAllNodesWithTag("ocv_cost").onFirst().performScrollTo(); shot("61b-office-costs-list")
        rule.onAllNodesWithTag("ocv_cost").onFirst().performClick(); sheet("ocv_cost_sheet")
        rule.onNodeWithText("Pump cylinder").assertExists(); rule.onNodeWithTag("ocv_cost_open").assertExists()
        sheetShot("61c-office-cost-sheet")
        closeSheet("ocv_cost_sheet")
        rule.onNodeWithTag("ocv_cf_top").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("costs_top").performScrollTo(); rule.onAllNodesWithTag("ocv_top").assertCountEquals(2); shot("62-office-costs-top")
        // the second approval: folded away until tapped
        rule.onAllNodesWithTag("approve_save").assertCountEquals(0)
        rule.onNodeWithTag("approve_setting_toggle").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("approve_save").performScrollTo().performClick()
        rule.waitUntil(5000) { posts.any { it.contains("\"action\":\"settings\"") && it.contains("\"approveAbove\":\"50000\"") } }
        rule.onNodeWithTag("approve_setting").performScrollTo(); shot("63-office-costs-setting")
    }

    @Test fun big_repair_waits_for_a_second_admin() {
        Lang.sw = false
        val m = model(); show(m)
        rule.onNodeWithTag("nav_office").performClick()
        rule.onNodeWithTag("o_repairs").performClick()
        waitFor("Needs a second approval before parts are ordered")
        waitFor("Waiting for a second approval · asked by Jessica")
        rule.onAllNodesWithText("Parts ordered").onFirst().performScrollTo().performClick()
        rule.onAllNodesWithText("Save").onFirst().performScrollTo().performClick()
        rule.waitUntil(5000) { posts.any { it.contains("\"action\":\"job\"") && it.contains("\"id\":\"104\"") && it.contains("\"step\":\"parts\"") } }
        waitFor("Asked ✓")
        rule.onNodeWithText("Asked ✓", substring = true).performScrollTo()
        shot("64-repairs-held-for-approval")
        rule.onNodeWithTag("o_review").performScrollTo().performClick()
        waitFor("Quote from Babati Hardware")
        val approve = rule.onAllNodesWithText("Approve the cost")
        approve[0].assertIsEnabled(); approve[1].assertIsNotEnabled()
        rule.onNodeWithText("You asked for this, so another admin approves it.").assertExists()
        shot("65-review-cost")
        approve[0].performScrollTo().performClick()
        rule.waitUntil(5000) { posts.any { it.contains("\"action\":\"review\"") && it.contains("\"rid\":\"c1\"") && it.contains("\"decision\":\"approve\"") } }
    }

    @Test fun tracker_asks_for_approval_and_shows_the_spend() {
        Lang.sw = false
        val m = model("Meril Kiprop", admin = false); show(m)
        rule.onNodeWithTag("nav_list").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("projects").performScrollToNode(hasText("Kapsaret Borehole"))
        rule.onNodeWithText("Kapsaret Borehole").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithText("Repairs: KES 36,500 in the last 12 months (3 repairs)").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("approval_needed").performScrollTo()
        shot("66-tracker-needs-approval")
        rule.onNodeWithTag("parts_btn").performScrollTo().performClick()
        rule.waitUntil(5000) { posts.any { it.contains("\"action\":\"parts\"") && it.contains("\"id\":\"104\"") } }
        waitFor("Sent for a second approval")
        rule.onNodeWithTag("fix_cost_open").performScrollTo().performClick()
        rule.onNodeWithText("Actual cost when fixed (KES, optional)").assertExists()
        rule.onNodeWithTag("project_costs").performScrollTo()
        shot("67-tracker-sent-and-costs")
    }

    @Test fun costs_dark_and_kiswahili() {
        Lang.sw = true
        val m = model(); show(m, dark = true)
        rule.onNodeWithTag("nav_office").performClick()
        waitFor("watu wanaohudumiwa")
        rule.onNodeWithTag("ocv_t_people").performScrollTo().performClick(); sheet("ocv_people_sheet"); rule.mainClock.advanceTimeBy(2000)
        rule.onNodeWithText("Watu wanaohudumiwa").assertExists()
        sheetShot("68-people-served-dark-sw"); closeSheet("ocv_people_sheet")
        rule.onNodeWithTag("o_costs").performScrollTo().performClick()
        waitFor("Idhini ya pili"); rule.mainClock.advanceTimeBy(1500)
        rule.onNodeWithTag("ocv_costs_head").performScrollTo(); shot("69-costs-dark-sw")
        rule.onNodeWithTag("approve_setting_toggle").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("approve_setting").performScrollTo(); shot("69b-costs-setting-dark-sw")
        rule.onAllNodesWithTag("ocv_cost").onFirst().performScrollTo().performClick(); sheet("ocv_cost_sheet")
        sheetShot("69c-cost-sheet-dark-sw")
        Lang.sw = false
    }

    @Test fun costs_sun() {
        Lang.sw = false
        org.rafikioutreach.pin.ui.Look.mode = "sun"
        try {
            val m = model(); show(m)
            rule.onNodeWithTag("nav_office").performClick()
            rule.onNodeWithTag("o_costs").performScrollTo().performClick()
            waitFor("Spent each month"); rule.mainClock.advanceTimeBy(1500)
            rule.onNodeWithTag("ocv_costs_head").performScrollTo(); shot("69d-costs-sun")
            rule.onAllNodesWithTag("ocv_cost").onFirst().performScrollTo(); shot("69e-costs-list-sun")
        } finally { org.rafikioutreach.pin.ui.Look.mode = "" }
    }
}

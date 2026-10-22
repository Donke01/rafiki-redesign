package org.rafikioutreach.pin

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.rafikioutreach.pin.data.Approval
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.data.Repair
import org.rafikioutreach.pin.ui.RafikiTheme
import org.rafikioutreach.pin.ui.rrAge
import org.rafikioutreach.pin.ui.rrKind
import org.rafikioutreach.pin.ui.rrMatches
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.LocalDate

/** Office › Repairs and Office › Review: numbers, filters, cards that open a sheet with every action. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class OfficeRepairsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val posts = java.util.concurrent.CopyOnWriteArrayList<String>()
    private fun ago(h: Long) = Instant.now().minusSeconds(h * 3600).toString()

    private fun proj(id: String, t: String, ck: String, country: String) = JSONObject().put("id", id).put("title", t).put("country", country).put("ck", ck).put("area", "Eldoret")
        .put("type", "Borehole well").put("status", "Completed").put("pump", "").put("gps", JSONArray().put(0.5).put(35.2))
    private fun snapshot() = JSONObject().put("ok", true).put("who", "Reid Anderson").put("admin", true).put("reviewOpen", 6).put("approveAbove", 50000)
        .put("projects", JSONArray().put(proj("104", "Kapsaret Borehole", "ke", "Kenya")).put(proj("105", "Magara Well", "tz", "Tanzania")).put(proj("101", "Chepkongi Dispensary", "ke", "Kenya"))
            .put(proj("107", "Mzuzu Water Kiosk", "mw", "Malawi")).put(proj("102", "Nzoia Bridge Primary School – Rainwater Tanks and Gutters", "ug", "Uganda")))
        .put("repairs", JSONObject()
            .put("104", JSONObject().put("state", "notworking").put("by", "Bertin").put("at", ago(50)).put("t", "Kapsaret Borehole").put("urgency", "urgent").put("cost", 85000).put("parts", "Rising main pipes, cylinder")
                .put("problems", JSONArray().put("Handle broken").put("No water")).put("note", "The handle came off on Monday").put("step", "assigned").put("assignee", "Meril Kiprop").put("due", LocalDate.now().minusDays(2).toString()))
            .put("105", JSONObject().put("state", "repair").put("by", "Amina").put("at", ago(90)).put("t", "Magara Well").put("cost", 70000).put("step", "assigned").put("assignee", "Bertin Mwangi")
                .put("approval", JSONObject().put("status", "pending").put("rid", "c1").put("by", "Jessica").put("at", ago(5)).put("est", 70000)))
            .put("101", JSONObject().put("state", "repair").put("by", "Grace").put("at", ago(30)).put("t", "Chepkongi Dispensary").put("cost", 6000).put("step", "assigned").put("assignee", "Reid Anderson"))
            .put("107", JSONObject().put("state", "repair").put("by", "Grace").put("at", ago(300)).put("t", "Mzuzu Water Kiosk").put("urgency", "month").put("cost", 12000).put("budget", 15000).put("parts", "Tap stands").put("step", "parts").put("due", LocalDate.now().plusDays(9).toString()))
            .put("102", JSONObject().put("state", "notworking").put("by", "Okello").put("at", ago(8)).put("t", "Nzoia Bridge Primary School – Rainwater Tanks and Gutters").put("urgency", "urgent").put("cost", 64000).put("step", "reported")
                .put("approval", JSONObject().put("status", "pending").put("rid", "c2").put("by", "Reid Anderson").put("at", ago(1)).put("est", 64000))))
        .put("visits", JSONObject()).put("jobs", JSONArray().put("101")).put("assigned", JSONArray()).put("confirmed", JSONObject())
    private val review = JSONObject().put("ok", true).put("items", JSONArray()
        .put(JSONObject().put("rid", "c1").put("kind", "cost").put("id", "105").put("t", "Magara Well").put("by", "Jessica").put("at", ago(5)).put("status", "open").put("data", JSONObject().put("est", 70000).put("parts", "New pump head").put("note", "Quote from Babati Hardware")))
        .put(JSONObject().put("rid", "c2").put("kind", "cost").put("id", "102").put("t", "Nzoia Bridge Primary School – Rainwater Tanks and Gutters").put("by", "Reid Anderson").put("at", ago(1)).put("status", "open").put("data", JSONObject().put("est", 64000)))
        .put(JSONObject().put("rid", "r1").put("kind", "story").put("id", "101").put("t", "Chepkongi Dispensary").put("by", "Meril Kiprop").put("at", ago(26)).put("status", "open")
            .put("data", JSONObject().put("quote", "Now the nurses can wash their hands between patients, and the children drink clean water at school.").put("who", "Mary, nurse")))
        .put(JSONObject().put("rid", "r2").put("kind", "new").put("id", "109").put("t", "Kipsangui Spring").put("by", "Bertin Mwangi").put("at", ago(70)).put("status", "open").put("data", JSONObject().put("type", "Spring protection").put("village", "Kipsangui").put("ck", "ke").put("gps", "0.51234, 35.27011")))
        .put(JSONObject().put("rid", "r3").put("kind", "move").put("id", "104").put("t", "Kapsaret Borehole").put("by", "Meril Kiprop").put("at", ago(100)).put("status", "open").put("data", JSONObject().put("from", "0.47000, 35.25000").put("to", "0.48120, 35.26310").put("dist", 1912).put("how", "GPS on the phone")))
        .put(JSONObject().put("rid", "r4").put("kind", "request").put("id", "107").put("t", "Mzuzu Water Kiosk").put("by", "Grace Achieng").put("at", ago(150)).put("status", "open").put("data", JSONObject().put("from", "-11.45000, 34.02000").put("to", "-11.45310, 34.02240").put("dist", 430)))
        .put(JSONObject().put("rid", "d1").put("kind", "new").put("id", "110").put("t", "Kaptebee Pigsty").put("by", "Meril Kiprop").put("at", ago(400)).put("status", "approved").put("done", ago(20)).put("doneBy", "Jessica"))
        .put(JSONObject().put("rid", "d2").put("kind", "move").put("id", "105").put("t", "Magara Well").put("by", "Amina").put("at", ago(500)).put("status", "rejected").put("done", ago(40)).put("doneBy", "Reid Anderson").put("reason", "Point was 2 km off")))

    private fun model(): AppModel {
        org.rafikioutreach.pin.ui.Onb.enabled = false
        val app = ApplicationProvider.getApplicationContext<Application>()
        Prefs(app).apply { pin = "9999"; name = "Reid Anderson"; saveSnapshot(snapshot()); filter = "all" }
        val m = AppModel(app)
        m.api.transport = { method, url, _, body ->
            val b = body?.let { String(it) } ?: ""
            posts += "$method $url $b"
            when {
                url.contains("view=review") -> 200 to review.toString()
                url.contains("view=team") -> 200 to JSONObject().put("ok", true).put("team", JSONArray().put(JSONObject().put("name", "Meril Kiprop")).put(JSONObject().put("name", "Bertin Mwangi")).put(JSONObject().put("name", "Reid Anderson").put("admin", true))).toString()
                url.contains("activity=1") -> 200 to "{\"ok\":true,\"activity\":[]}"
                url.contains("pin-auth/me") -> 200 to "{\"ok\":true,\"available\":false}"
                method == "POST" && b.contains("\"action\":\"job\"") && b.contains("\"id\":\"104\"") && b.contains("\"step\":\"parts\"") ->
                    200 to JSONObject().put("ok", true).put("held", "parts").put("asked", true).put("job", snapshot().getJSONObject("repairs").getJSONObject("104")).toString()
                method == "POST" && b.contains("\"action\":\"job\"") && b.contains("\"step\":\"fixed\"") -> 200 to "{\"ok\":true,\"job\":null,\"held\":\"\",\"updateId\":\"9\"}"
                method == "POST" && b.contains("\"action\":\"review\"") && b.contains("\"rid\":\"c2\"") -> 409 to "{\"ok\":false,\"reason\":\"own\"}"
                method == "GET" -> 200 to snapshot().toString()
                else -> 200 to "{\"ok\":true,\"updateId\":\"8\"}"
            }
        }
        return m
    }
    private fun show(m: AppModel) = rule.setContent { RafikiTheme(dark = false) { Box(Modifier.size(390.dp, 844.dp)) { App(m) } } }
    private fun shot(name: String) {
        rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
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
    private fun waitTag(tag: String) = rule.waitUntil(5000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun waitText(text: String) = rule.waitUntil(5000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    private fun tag(t: String) = rule.onNodeWithTag(t, useUnmergedTree = false)
    private fun has(t: String) = rule.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty()
    private fun close(sheet: String) { tag("rr_close").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(1200); rule.waitUntil(5000) { !has(sheet) } }
    private fun openRepairs(m: AppModel) { show(m); tag("nav_office").performClick(); tag("o_repairs").performClick(); waitTag("rr_job_104"); rule.mainClock.advanceTimeBy(1200) }
    private fun openReview(m: AppModel) { show(m); tag("nav_office").performClick(); tag("o_review").performScrollTo().performClick(); waitTag("rr_rev_c1"); rule.mainClock.advanceTimeBy(1200) }

    @Test fun filters_match_the_web() {
        Lang.sw = false
        val r = Repair("notworking", "B", "", "", "urgent", emptyList(), cost = 85000, step = "assigned", assignee = "")
        assertTrue(rrMatches("urgent", r, 50000) && rrMatches("notworking", r, 50000) && rrMatches("unassigned", r, 50000) && rrMatches("approval", r, 50000) && !rrMatches("parts", r, 50000))
        assertTrue(rrMatches("approval", r.copy(approval = Approval("pending", "c1", "J", "", 85000)), 50000))
        assertTrue(!rrMatches("approval", r.copy(approval = Approval("approved", "c1", "J", "", 85000, "R")), 50000))
        assertEquals("loc", rrKind("move")[0]); assertEquals("loc", rrKind("request")[0]); assertEquals("cost", rrKind("cost")[0])
        assertEquals("6 d", rrAge(Instant.now().minusSeconds(150 * 3600).toString())); assertEquals("5 h", rrAge(Instant.now().minusSeconds(5 * 3600 + 60).toString()))
    }

    @Test fun repairs_list_filters_and_sheet() {
        Lang.sw = false
        val m = model(); openRepairs(m)
        tag("rr_head").assertExists()
        rule.onNodeWithText("need approval").assertExists()
        rule.onNodeWithText("Needs approval · 3").assertExists(); rule.onNodeWithText("Not assigned · 2").assertExists(); rule.onNodeWithText("Parts ordered · 1").assertExists()
        // most urgent first
        assertTrue(tag("rr_job_104").fetchSemanticsNode().positionInRoot.y < tag("rr_job_102").fetchSemanticsNode().positionInRoot.y)
        assertTrue(tag("rr_job_102").fetchSemanticsNode().positionInRoot.y < tag("rr_job_107").fetchSemanticsNode().positionInRoot.y)
        tag("rr_head").performScrollTo(); shot("70-repairs-list")
        tag("rr_job_104").performScrollTo(); shot("70a-repairs-cards")
        tag("rr_filter_urgent").performScrollTo().performClick(); rule.waitForIdle()
        assertTrue(has("rr_job_104") && has("rr_job_102") && !has("rr_job_101"))
        tag("rr_n_approval").performScrollTo().performClick(); rule.waitForIdle()
        assertTrue(has("rr_job_104") && has("rr_job_102") && has("rr_job_105") && !has("rr_job_107"))
        tag("rr_filter_parts").performScrollTo().performClick(); rule.waitForIdle()
        assertTrue(has("rr_job_107") && !has("rr_job_104")); shot("70b-repairs-filter-parts")
        tag("rr_filter_all").performScrollTo().performClick(); rule.waitForIdle()
        // folded: how it works
        assertTrue(!has("rr_how_body")); tag("rr_how_toggle").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(500); assertTrue(has("rr_how_body"))
        tag("rr_how").performScrollTo(); shot("70c-repairs-how")

        // a small job: step, cost, save
        tag("rr_job_101").performScrollTo().performClick(); waitTag("rr_job_sheet")
        sheetShot("71-repairs-sheet")
        assertTrue(!has("rr_money_body"))
        tag("rr_step_parts").performScrollTo().performClick()
        tag("rr_money_toggle").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("what was spent").performScrollTo().performTextInput("5400")
        rule.onNodeWithText("e.g. parts bought in Eldoret").performScrollTo().performTextInput("Tap washers")
        tag("rr_money_body").performScrollTo(); sheetShot("71b-repairs-sheet-money")
        tag("rr_save").performScrollTo().performClick()
        rule.waitUntil(5000) { posts.any { it.contains("\"action\":\"job\"") && it.contains("\"id\":\"101\"") } }
        posts.first { it.contains("\"action\":\"job\"") && it.contains("\"id\":\"101\"") }.let { assertTrue(it, it.contains("\"step\":\"parts\"") && it.contains("\"actual\":5400") && it.contains("\"assignee\":\"Reid Anderson\"") && it.contains("\"what\":\"Tap washers\"")) }
        waitText("Saved ✓")
        close("rr_job_sheet")

        // a big job: parts ordered waits for a second approval
        tag("rr_job_104").performScrollTo().performClick(); waitTag("rr_job_sheet")
        waitText("Needs a second approval before parts are ordered"); waitText("The handle came off on Monday")
        tag("rr_step_parts").performScrollTo().performClick(); tag("rr_save").performScrollTo().performClick()
        waitText("Asked ✓")
        tag("rr_msg").performScrollTo(); sheetShot("71c-repairs-held")
        close("rr_job_sheet")

        // someone else's cost: approve it here
        tag("rr_job_105").performScrollTo().performClick(); waitTag("rr_job_sheet")
        tag("rr_cost_dec").assertExists(); sheetShot("72-repairs-sheet-approve")
        tag("rr_cost_approve").performScrollTo().performClick()
        rule.waitUntil(5000) { posts.any { it.contains("\"action\":\"review\"") && it.contains("\"rid\":\"c1\"") && it.contains("\"decision\":\"approve\"") } }
        waitText("Cost approved ✓")
        close("rr_job_sheet")
        // your own request: no approve buttons
        tag("rr_job_102").performScrollTo().performClick(); waitTag("rr_job_sheet")
        rule.onAllNodesWithTag("rr_cost_dec").assertCountEquals(0)
        rule.onNodeWithText("You asked for this, so another admin has to approve it.").assertExists()
        sheetShot("72b-repairs-sheet-own")
        close("rr_job_sheet")

        // mark fixed asks first
        tag("rr_job_107").performScrollTo().performClick(); waitTag("rr_job_sheet")
        tag("rr_fixed").performScrollTo().performClick()
        tag("rr_ask").assertExists(); assertTrue(posts.none { it.contains("\"step\":\"fixed\"") })
        tag("rr_ask").performScrollTo(); sheetShot("73-repairs-mark-fixed-ask")
        tag("rr_ask_no").performScrollTo().performClick(); rule.onAllNodesWithTag("rr_ask").assertCountEquals(0)
        tag("rr_fixed").performScrollTo().performClick(); tag("rr_ask_yes").performScrollTo().performClick()
        rule.waitUntil(5000) { posts.any { it.contains("\"action\":\"job\"") && it.contains("\"id\":\"107\"") && it.contains("\"step\":\"fixed\"") } }
        rule.mainClock.advanceTimeBy(2500)
        rule.waitUntil(5000) { !has("rr_job_sheet") }
    }

    @Test fun review_filters_and_sheet() {
        Lang.sw = false
        val m = model(); openReview(m)
        rule.onNodeWithText("Waiting for review").assertExists(); rule.onNodeWithText("6 d").assertExists()
        rule.onNodeWithText("Locations · 2").assertExists(); rule.onNodeWithText("Costs · 2").assertExists()
        rule.onNodeWithText("You asked: another admin approves").assertExists()
        tag("rr_head").performScrollTo(); shot("74-review-list")
        tag("rr_rev_c1").performScrollTo(); shot("74a-review-cards")
        tag("rr_kind_loc").performScrollTo().performClick(); rule.waitForIdle()
        assertTrue(has("rr_rev_r3") && has("rr_rev_r4") && !has("rr_rev_c1"))
        tag("rr_kind_story").performScrollTo().performClick(); rule.waitForIdle()
        assertTrue(has("rr_rev_r1") && !has("rr_rev_r3"))
        tag("rr_n_cost").performScrollTo().performClick(); rule.waitForIdle()
        assertTrue(has("rr_rev_c1") && has("rr_rev_c2") && !has("rr_rev_r1"))
        tag("rr_kind_all").performScrollTo().performClick(); rule.waitForIdle()
        // recently decided, folded
        assertTrue(!has("rr_done_body")); tag("rr_done_toggle").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(500)
        waitText("Point was 2 km off"); tag("rr_done").performScrollTo(); shot("75-review-decided")
        // your own cost: approve and decline are off
        tag("rr_rev_c2").performScrollTo().performClick(); waitTag("rr_rev_sheet")
        tag("rr_approve").assertIsNotEnabled(); tag("rr_reject").assertIsNotEnabled()
        close("rr_rev_sheet")
        // someone else's cost: approve with a note
        tag("rr_rev_c1").performScrollTo().performClick(); waitTag("rr_rev_sheet"); waitText("Quote from Babati Hardware")
        tag("rr_approve").assertIsEnabled(); sheetShot("76-review-cost-sheet")
        rule.onNodeWithText("e.g. quote checked with the driller").performScrollTo().performTextInput("Checked")
        tag("rr_approve").performScrollTo().performClick()
        rule.waitUntil(5000) { posts.any { it.contains("\"action\":\"review\"") && it.contains("\"rid\":\"c1\"") && it.contains("\"decision\":\"approve\"") && it.contains("\"reason\":\"Checked\"") } }
        rule.mainClock.advanceTimeBy(2000); rule.waitUntil(5000) { !has("rr_rev_sheet") }
        // a story: turning it down asks for a reason first
        tag("rr_rev_r1").performScrollTo().performClick(); waitTag("rr_rev_sheet")
        assertTrue(!has("rr_reason")); sheetShot("77-review-story-sheet")
        tag("rr_reject").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(400)
        assertTrue(has("rr_reason")); assertTrue(posts.none { it.contains("\"rid\":\"r1\"") })
        rule.onNodeWithText("Confirm: Don’t use").assertExists()
        rule.onNodeWithText("e.g. wrong school").performScrollTo().performTextInput("Not their words")
        tag("rr_reject").performScrollTo(); sheetShot("77b-review-story-reject")
        tag("rr_reject").performClick()
        rule.waitUntil(5000) { posts.any { it.contains("\"rid\":\"r1\"") && it.contains("\"decision\":\"reject\"") && it.contains("\"reason\":\"Not their words\"") } }
        rule.mainClock.advanceTimeBy(2000); rule.waitUntil(5000) { !has("rr_rev_sheet") }
        // a moved location
        tag("rr_rev_r3").performScrollTo().performClick(); waitTag("rr_rev_sheet"); waitText("Keep the new location"); waitText("1,912 m")
        sheetShot("78-review-move-sheet")
    }

    @Test fun dark_sun_and_kiswahili() {
        try {
            Lang.sw = false; org.rafikioutreach.pin.ui.Look.mode = "dark"
            val m = model(); openRepairs(m)
            tag("rr_head").performScrollTo(); shot("80-repairs-dark")
            tag("rr_job_105").performScrollTo().performClick(); waitTag("rr_job_sheet"); sheetShot("80b-repairs-sheet-dark")
            close("rr_job_sheet")
            tag("o_review").performScrollTo().performClick(); waitTag("rr_rev_c1"); rule.mainClock.advanceTimeBy(1200)
            tag("rr_head").performScrollTo(); shot("81-review-dark")
            tag("rr_rev_r1").performScrollTo().performClick(); waitTag("rr_rev_sheet"); sheetShot("81b-review-sheet-dark")
            org.rafikioutreach.pin.ui.Look.mode = "sun"; sheetShot("82-review-sheet-sun")
            close("rr_rev_sheet")
            tag("o_repairs").performScrollTo().performClick(); waitTag("rr_job_104"); rule.mainClock.advanceTimeBy(1200)
            tag("rr_job_104").performScrollTo(); shot("82b-repairs-sun")
            org.rafikioutreach.pin.ui.Look.mode = ""; Lang.sw = true
            rule.mainClock.advanceTimeBy(500)
        } finally { org.rafikioutreach.pin.ui.Look.mode = "" }
    }

    @Test fun repairs_and_review_in_kiswahili() {
        Lang.sw = true
        try {
            val m = model(); openRepairs(m)
            rule.onNodeWithText("Kazi za ukarabati").assertExists(); rule.onNodeWithText("Hazijagawiwa · 2").assertExists()
            tag("rr_head").performScrollTo(); shot("83-repairs-sw")
            tag("rr_job_104").performScrollTo(); shot("83a-repairs-cards-sw")
            tag("rr_job_104").performClick(); waitTag("rr_job_sheet"); waitText("Weka imerekebishwa")
            sheetShot("84-repairs-sheet-sw")
            tag("rr_money_toggle").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(500); tag("rr_money_body").performScrollTo(); sheetShot("84b-repairs-sheet-money-sw")
            close("rr_job_sheet")
            tag("o_review").performScrollTo().performClick(); waitTag("rr_rev_c1"); rule.mainClock.advanceTimeBy(1200)
            rule.onNodeWithText("Zinasubiri kuidhinishwa").assertExists()
            tag("rr_head").performScrollTo(); shot("85-review-sw")
            tag("rr_rev_r4").performScrollTo().performClick(); waitTag("rr_rev_sheet"); sheetShot("85b-review-sheet-sw")
        } finally { Lang.sw = false }
    }
}

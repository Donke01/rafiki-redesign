package org.rafikioutreach.pin

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
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
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.ui.Look
import org.rafikioutreach.pin.ui.Motion
import org.rafikioutreach.pin.ui.OfficeJump
import org.rafikioutreach.pin.ui.Onb
import org.rafikioutreach.pin.ui.RafikiTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.Instant

/** 2.26.0, web v71: Office tiles in four labelled groups; Overview "Start here" (five set-up steps that open the right
 *  place and tick themselves off, Hide) and "Needs you" (only what waits for an admin). Screenshots "ost-…". */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class OfficeStartTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    @After fun reset() { Lang.sw = false; Motion.on = true; Onb.enabled = true; Look.mode = ""; OfficeJump.to = null; OfficeJump.repairs = null }

    private var busy = true // something waits for the admin; false: nothing does
    private var field = true // the team has a field person
    private fun proj(id: String, t: String, gps: Boolean) = JSONObject().put("id", id).put("title", t).put("country", "Kenya").put("ck", "ke").put("area", "Eldoret")
        .put("type", "Borehole well").put("status", "Completed").put("pump", "manual").apply { if (gps) put("gps", JSONArray().put(0.45 + id.toInt() % 9 * 0.01).put(35.2)) }
    private fun ago(d: Long) = Instant.now().minusSeconds(d * 86400).toString()
    private fun snapshot() = JSONObject().put("ok", true).put("who", "Reid Anderson").put("admin", true).put("reviewOpen", if (busy) 3 else 0)
        .put("projects", JSONArray().put(proj("101", "Chepkongi Dispensary", true)).put(proj("102", "Kiptoo Well", false)).put(proj("104", "Kapsaret Borehole", true)).put(proj("105", "Turbo Spring", true)).put(proj("106", "Ndalat Classrooms", true)))
        .put("repairs", if (!busy) JSONObject() else JSONObject()
            .put("104", JSONObject().put("state", "notworking").put("by", "Bertin").put("at", ago(2)).put("urgency", "urgent").put("t", "Kapsaret Borehole"))
            .put("105", JSONObject().put("state", "repair").put("by", "Bertin").put("at", ago(4)).put("urgency", "urgent").put("assignee", "Meril Kiprop").put("t", "Turbo Spring")))
        // every pinned project visited lately when nothing is due
        .put("visits", JSONObject().apply { if (!busy) listOf("101", "104", "105", "106").forEach { put(it, JSONObject().put("at", ago(3)).put("by", "Meril").put("state", "working")) } else put("106", JSONObject().put("at", ago(3)).put("by", "Meril").put("state", "working")) })
        .put("stages", if (busy) JSONObject().put("106", JSONObject().put("s", "walls").put("behind", true).put("pct", 40)) else JSONObject())
        .put("jobs", JSONArray()).put("assigned", JSONArray()).put("confirmed", JSONObject())

    private fun model(seen: Set<String> = emptySet(), off: Boolean = false): AppModel {
        Onb.enabled = false; Motion.on = false
        Prefs(app).apply { pin = "9999"; name = "Reid Anderson"; saveSnapshot(snapshot()); filter = "all"; country = ""; ftype = ""; onb = true; lang = if (Lang.sw) "sw" else "en"; phoneAlerts = false; meHint = true; startSeen = seen; startOff = off }
        app.getSharedPreferences("rafiki-pin", 0).edit().putString("currency", "KES").commit()
        val m = AppModel(app)
        m.api.transport = { method, url, _, _ ->
            when {
                url.contains("view=team") -> 200 to JSONObject().put("ok", true).put("team", JSONArray()
                    .put(JSONObject().put("name", "Reid Anderson").put("admin", true).put("source", "cloudflare").put("email", "reid@rafikioutreach.org"))
                    .apply { if (field) put(JSONObject().put("name", "Meril Kiprop").put("home", "ke").put("admin", false).put("source", "app")) }).toString()
                url.contains("view=") -> 200 to "{\"ok\":true,\"items\":[]}"
                url.contains("activity=1") -> 200 to "{\"ok\":true,\"activity\":[]}"
                url.contains("pin-auth/me") -> 200 to "{\"ok\":true,\"available\":false}"
                url.contains("version.json") || url.contains("/api/chat") -> 0 to ""
                url.contains("?id=") -> 200 to "{\"ok\":true,\"last\":null}"
                method == "GET" -> 200 to snapshot().toString()
                else -> 200 to "{\"ok\":true}"
            }
        }
        return m
    }
    private var w by mutableStateOf(390); private var h by mutableStateOf(844); private var dark by mutableStateOf(false)
    private fun start(m: AppModel) {
        rule.setContent { RafikiTheme(dark = dark) { Box(Modifier.size(w.dp, h.dp)) { App(m) } } }
        rule.waitUntil(10000) { tagged("nav_office") }; settle(1500)
    }
    private fun tagged(t: String) = rule.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitTag(t: String) = rule.waitUntil(10000) { tagged(t) }
    private fun settle(ms: Long = 800) { rule.mainClock.advanceTimeBy(ms); rule.waitForIdle() }
    private fun tap(t: String) {
        rule.onNodeWithTag(t).let { n -> runCatching { n.performScrollTo() } }
        val target = rule.onAllNodes(hasTestTag(t) and hasClickAction()).fetchSemanticsNodes().let { if (it.isNotEmpty()) rule.onNode(hasTestTag(t) and hasClickAction()) else rule.onAllNodes(hasAnyAncestor(hasTestTag(t)) and hasClickAction()).onFirst() }
        target.performSemanticsAction(SemanticsActions.OnClick); settle()
    }
    private fun back() { rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; settle() }
    private fun text(t: String) = LayoutCheck.text(rule.onNodeWithTag(t, useUnmergedTree = true).fetchSemanticsNode())
    private fun top(t: String) = rule.onNodeWithTag(t, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
    private fun shot(name: String) {
        settle()
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); val c = Canvas(b); v.draw(c)
        val d = ShadowDialog.getShownDialogs().lastOrNull { it.isShowing }?.window?.decorView
        if (d != null && d.width > 0) { c.drawColor(0x66000000); c.save(); c.translate(0f, (v.height - d.height).toFloat().coerceAtLeast(0f)); d.draw(c); c.restore() }
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    /** On screen: inside the window, above the bottom bar. */
    private fun inView(t: String): Boolean {
        val n = rule.onNodeWithTag(t, useUnmergedTree = true).fetchSemanticsNode()
        val bar = rule.onNodeWithTag("bottom_bar", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
        return n.positionInRoot.y >= 0f && n.positionInRoot.y + n.size.height <= bar + 1f
    }
    private fun noLayoutProblems(root: String, label: String) {
        val px = w * rule.density.density
        assertEquals("$label: $root", emptyList<String>(), LayoutCheck.problems(rule, root, px))
    }

    private val oTag = SemanticsMatcher("an Office section") { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("o_") == true }

    // --- 1. the tiles in four labelled groups, in the web's order ------------------------------------------------------
    @Test fun office_tiles_in_four_groups() {
        start(model())
        tap("nav_office"); waitTag("office_groups")
        val want = listOf("need" to listOf("overview", "review", "repairs", "visits"), "field" to listOf("plan", "trips", "teammap", "vehicles"),
            "people" to listOf("team", "donors", "mail", "sms"), "records" to listOf("costs", "export", "qr", "cleanup"))
        var lastTop = -1f
        want.forEach { (g, keys) ->
            assertTrue("$g below the one before", top("ogrp_$g") > lastTop); lastTop = top("ogrp_$g")
            val tiles = rule.onAllNodes(hasAnyAncestor(hasTestTag("ogrp_$g")) and oTag).fetchSemanticsNodes()
                .sortedWith(compareBy({ it.positionInRoot.y }, { it.positionInRoot.x })).map { it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag] }
            assertEquals(g, keys.map { "o_$it" }, tiles)
        }
        assertEquals("NEEDS YOU", text("ogh_need")); assertEquals("RECORDS", text("ogh_records"))
        // the review count still shows on its tile
        rule.onAllNodes(hasAnyAncestor(hasTestTag("o_review")) and hasText("3"), useUnmergedTree = true).onFirst().assertExists()
        noLayoutProblems("office_groups", "390 en")
        shot("ost-01-groups-390-en")
        // a chosen section: the chips follow the same order
        tap("o_trips"); waitTag("office_strip")
        val chips = rule.onAllNodes(hasAnyAncestor(hasTestTag("office_strip")) and oTag).fetchSemanticsNodes().sortedBy { it.positionInRoot.x }
            .map { it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag] }
        assertEquals(listOf("o_all") + want.flatMap { it.second }.map { "o_$it" }, chips)
        back(); assertTrue("back: the groups", tagged("office_groups") && !tagged("office_strip"))
    }

    @Config(qualifiers = "w840dp-h900dp-xhdpi")
    @Test fun wide_two_groups_side_by_side() {
        w = 840; h = 900
        start(model())
        tap("nav_office"); waitTag("office_groups")
        fun r(t: String) = rule.onNodeWithTag(t).fetchSemanticsNode().let { androidx.compose.ui.geometry.Rect(it.positionInRoot, it.size.toSize()) }
        val a = r("ogrp_need"); val b = r("ogrp_field"); val c = r("ogrp_people")
        assertEquals("Needs you and Field work side by side", a.top, b.top, 1f); assertTrue(b.left > a.right)
        assertTrue("People on the next row", c.top >= a.bottom - 1f)
        noLayoutProblems("office_groups", "840 en")
        shot("ost-02-groups-wide")
    }

    // --- 2. Start here ----------------------------------------------------------------------------------------------------
    @Test fun start_here_steps_open_the_right_place_and_tick_off() {
        val m = model(); start(m)
        tap("nav_office"); waitTag("ocv_start"); settle(1200)
        // the team has a field person: step 1 is done already
        assertEquals("1 of 5 done. Each step opens the right place.", text("ocv_start_n"))
        rule.onAllNodes(hasAnyAncestor(hasTestTag("ocv_start_team")) and hasText("Done"), useUnmergedTree = true).onFirst().assertExists()
        rule.onAllNodes(hasAnyAncestor(hasTestTag("ocv_start_alerts")) and hasText("Repairs and reviews are e-⁠mailed to these admins."), useUnmergedTree = true).onFirst().assertExists()
        assertTrue("Start here, then Needs you, then the numbers: ${top("ocv_start")} ${top("ocv_todo")} ${top("ocv_t_repairs")}", top("ocv_start") < top("ocv_todo") && top("ocv_todo") < top("ocv_t_repairs"))
        noLayoutProblems("ocv_start", "390 en"); noLayoutProblems("ocv_todo", "390 en")
        rule.onNodeWithTag("ocv_start").performScrollTo(); shot("ost-03-start-390-en")
        // second approval: Costs, the limit fold open and in view
        tap("ocv_start_approve"); waitTag("approve_save"); settle(1500)
        assertTrue(tagged("office_strip") && tagged("approve_setting_body"))
        assertTrue("the limit is on screen", inView("approve_save"))
        assertTrue("approve" in m.prefs.startSeen)
        shot("ost-04-start-costs-limit")
        // back: the open fold closes first, then the Office's sections
        back(); assertFalse(tagged("approve_setting_body")); assertTrue(tagged("office_strip"))
        back(); waitTag("ocv_start"); settle(1200)
        assertEquals("2 of 5 done. Each step opens the right place.", text("ocv_start_n"))
        // alert e-mails: Team, scrolled to E-mail alerts
        tap("ocv_start_alerts"); waitTag("team_alerts"); settle(1500)
        assertTrue(tagged("team_hero")); assertTrue("E-mail alerts on screen", inView("team_alerts"))
        shot("ost-05-start-team-alerts")
        back(); waitTag("ocv_start")
        // opening a section from the tiles ticks off its step too (Plan a week)
        tap("o_plan"); settle(1200); back(); waitTag("ocv_start"); settle(1200)
        assertEquals("4 of 5 done. Each step opens the right place.", text("ocv_start_n"))
        // the last one: the card goes away
        tap("ocv_start_qr"); settle(1200); back(); settle(1200)
        assertFalse("all done: no card", tagged("ocv_start")); assertTrue(tagged("ocv_todo"))
        assertEquals(setOf("approve", "alerts", "plan", "qr"), m.prefs.startSeen)
    }

    @Test fun start_here_team_step_waits_for_a_field_person_and_hide_is_kept() {
        field = false
        val m = model(); start(m)
        tap("nav_office"); waitTag("ocv_start"); settle(1200)
        assertEquals("0 of 5 done. Each step opens the right place.", text("ocv_start_n"))
        rule.onAllNodes(hasAnyAncestor(hasTestTag("ocv_start_team")) and hasText("Give each person a PIN so they can sign in."), useUnmergedTree = true).onFirst().assertExists()
        tap("ocv_start_team"); waitTag("team_hero")
        back(); waitTag("ocv_start")
        tap("ocv_start_off"); assertFalse(tagged("ocv_start")); assertTrue(m.prefs.startOff)
        // kept: the next time the Office opens
        tap("nav_map"); tap("nav_office"); waitTag("ocv_todo"); settle(1200); assertFalse(tagged("ocv_start"))
    }

    // --- 3. Needs you ---------------------------------------------------------------------------------------------------
    @Test fun needs_you_rows_open_the_place_to_act() {
        start(model())
        tap("nav_office"); waitTag("ocv_todo"); settle(1200)
        assertEquals("3 things are waiting for your review", rule.onAllNodes(hasAnyAncestor(hasTestTag("ocv_todo_review")) and hasText("waiting", substring = true), useUnmergedTree = true).onFirst().fetchSemanticsNode().let(LayoutCheck::text))
        rule.onAllNodes(hasAnyAncestor(hasTestTag("ocv_todo_unassigned")) and hasText("1 urgent repair has nobody on it"), useUnmergedTree = true).onFirst().assertExists()
        rule.onAllNodes(hasAnyAncestor(hasTestTag("ocv_todo_plan")) and hasText("3 sites are due a visit"), useUnmergedTree = true).onFirst().assertExists()
        rule.onAllNodes(hasAnyAncestor(hasTestTag("ocv_todo_build")) and hasText("1 building project is behind schedule"), useUnmergedTree = true).onFirst().assertExists()
        rule.onNodeWithTag("ocv_todo").performScrollTo(); shot("ost-06-needs-you-390-en")
        tap("ocv_todo_unassigned"); waitTag("rr_filter_unassigned"); settle()
        assertTrue("only the unassigned job", tagged("rr_job_104") && !tagged("rr_job_105"))
        back(); waitTag("ocv_todo")
        tap("ocv_todo_review"); waitTag("rr_n_cost"); back(); waitTag("ocv_todo")
        tap("ocv_todo_plan"); waitTag("plan_to"); back(); waitTag("ocv_todo")
        tap("ocv_todo_build"); waitTag("projects"); rule.onNodeWithTag("nav_list").assertIsSelected()
    }

    @Test fun needs_you_nothing_right_now() {
        busy = false
        start(model(seen = setOf("alerts", "approve", "plan", "qr")))
        tap("nav_office"); waitTag("ocv_todo"); settle(1200)
        assertTrue(tagged("ocv_todo_none")); assertFalse(tagged("ocv_todo_review"))
        rule.onAllNodesWithText("Nothing needs you right now.").onFirst().assertExists()
        assertFalse("all five done: no Start here", tagged("ocv_start"))
        shot("ost-07-needs-you-empty")
    }

    // --- 4. every size, theme and language: nothing broken, cut or past the edge ----------------------------------------
    private fun looks(width: Int, height: Int, sw: Boolean, mode: String) {
        Lang.sw = sw; w = width; h = height; dark = mode == "dark"; Look.mode = if (mode == "sun") "sun" else ""
        field = false
        start(model(seen = setOf("approve")))
        tap("nav_office"); waitTag("ocv_start"); settle(1500)
        val tag = "$width-${if (sw) "sw" else "en"}-$mode"
        for (root in listOf("office_groups", "ocv_start", "ocv_todo")) noLayoutProblems(root, tag)
        if (sw) { assertEquals("KUMBUKUMBU", text("ogh_records")); assertEquals("1 kati ya 5 zimekamilika. Kila hatua inafungua mahali sahihi.", text("ocv_start_n")) }
        shot("ost-10-groups-$tag")
        rule.onNodeWithTag("ocv_start").performScrollTo(); shot("ost-11-start-$tag")
        rule.onNodeWithTag("ocv_todo").performScrollTo(); shot("ost-12-todo-$tag")
    }
    @Config(qualifiers = "w320dp-h640dp-xhdpi") @Test fun looks_320_en_light() = looks(320, 640, false, "light")
    @Config(qualifiers = "w320dp-h640dp-xhdpi") @Test fun looks_320_sw_dark() = looks(320, 640, true, "dark")
    @Config(qualifiers = "w320dp-h640dp-xhdpi") @Test fun looks_320_sw_sun() = looks(320, 640, true, "sun")
    @Config(qualifiers = "w360dp-h740dp-xxhdpi") @Test fun looks_360_sw_light() = looks(360, 740, true, "light")
    @Config(qualifiers = "w360dp-h740dp-xxhdpi") @Test fun looks_360_en_sun() = looks(360, 740, false, "sun")
    @Config(qualifiers = "w411dp-h891dp-xxhdpi") @Test fun looks_411_en_dark() = looks(411, 891, false, "dark")
    @Config(qualifiers = "w411dp-h891dp-xxhdpi") @Test fun looks_411_sw_light() = looks(411, 891, true, "light")
}

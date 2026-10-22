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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.rafikioutreach.pin.data.Alert
import org.rafikioutreach.pin.data.AlertWorker
import org.rafikioutreach.pin.data.Money
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.ui.Bio
import org.rafikioutreach.pin.ui.Look
import org.rafikioutreach.pin.ui.Motion
import org.rafikioutreach.pin.ui.Onb
import org.rafikioutreach.pin.ui.RafikiTheme
import org.rafikioutreach.pin.ui.dueTag
import org.rafikioutreach.pin.ui.dueText
import org.rafikioutreach.pin.ui.ocvLateShort
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.LocalDate

/** The polish round for the Office, Me & team, Settings, notifications and widgets (screenshots "po-…"). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class PolishOfficeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val posts = java.util.concurrent.CopyOnWriteArrayList<String>()
    private var width = 390

    private val bioWas = Bio.available
    @After fun reset() { Lang.sw = false; Look.mode = ""; Motion.on = true; Bio.available = bioWas }

    private fun proj(id: String, t: String, gps: JSONArray?) = JSONObject().put("id", id).put("title", t).put("country", "Kenya").put("ck", "ke").put("area", "Eldoret")
        .put("type", "borehole").put("status", "Completed").put("pump", "manual").apply { if (gps != null) put("gps", gps) }
    private fun snapshot() = JSONObject().put("ok", true).put("who", "Reid Anderson").put("admin", true).put("reviewOpen", 1).put("linked", "Reid Anderson")
        .put("projects", JSONArray().put(proj("101", "Chepkongi Dispensary", JSONArray().put(0.5143).put(35.2698))).put(proj("102", "Kiptoo Well", null))
            .put(proj("104", "Kapsaret Borehole", JSONArray().put(0.47).put(35.25))).put(proj("105", "Turbo Spring", JSONArray().put(0.63).put(35.05))))
        .put("repairs", JSONObject().put("104", JSONObject().put("state", "notworking").put("by", "Bertin").put("at", "2026-10-18T08:00:00Z").put("note", "Handle loose").put("urgency", "urgent").put("t", "Kapsaret Borehole").put("cost", 4500).put("assignee", "Reid Anderson").put("due", LocalDate.now().plusDays(2).toString())))
        .put("visits", JSONObject()).put("jobs", JSONArray().put("104")).put("assigned", JSONArray()).put("confirmed", JSONObject())

    private fun act(): JSONArray {
        val now = Instant.now()
        fun e(by: String, a: String, id: String, t: String, mins: Long, s: String = "", src: String = "") = JSONObject().put("by", by).put("a", a).put("id", id).put("t", t).put("at", now.minusSeconds(mins * 60).toString()).apply { if (s.isNotEmpty()) put("s", s); if (src.isNotEmpty()) put("src", src) }
        return JSONArray().put(e("Meril Kiprop", "visit", "105", "Turbo Spring", 30, "working")).put(e("Reid Anderson", "pin", "101", "Chepkongi Dispensary", 50))
            .put(e("SMS from +254 712 ••• 678", "visit", "104", "Kapsaret Borehole", 70, "notworking", "sms")).put(e("Bertin Mwangi", "fixed", "101", "Chepkongi Dispensary", 90))
    }

    private fun model(lateDays: Long = 3, actOk: Boolean = true): AppModel {
        Onb.enabled = false
        Prefs(app).apply { pin = "9999"; name = "Reid Anderson"; saveSnapshot(snapshot()); filter = "all"; onb = true; lang = if (Lang.sw) "sw" else "en"; mePane = "me"; phoneAlerts = false }
        val m = AppModel(app)
        val assign = JSONArray().put(JSONObject().put("aid", "a1").put("id", "105").put("t", "Turbo Spring").put("to", "Meril Kiprop").put("date", LocalDate.now().minusDays(lateDays).toString()).put("note", "").put("by", "Jessica").put("at", "2026-10-15T08:00:00Z"))
        m.api.transport = { method, url, _, body ->
            posts += "$method $url ${body?.let { String(it) } ?: ""}"
            when {
                url.contains("view=team") -> 200 to JSONObject().put("ok", true).put("team", JSONArray()
                    .put(JSONObject().put("name", "Meril Kiprop").put("home", "ke").put("admin", false).put("source", "app").put("phone", "0712 345 678").put("lastSeen", "2026-10-19T09:00:00Z"))
                    .put(JSONObject().put("name", "Reid Anderson").put("admin", true).put("source", "cloudflare"))).toString()
                url.contains("team=1") -> 200 to JSONObject().put("ok", true).put("team", JSONArray().put(JSONObject().put("name", "Meril Kiprop").put("home", "ke").put("phone", "0712 345 678"))).toString()
                url.contains("view=review") -> 200 to "{\"ok\":true,\"items\":[]}"
                url.contains("view=assign") -> 200 to JSONObject().put("ok", true).put("items", assign).toString()
                url.contains("activity=1") -> if (actOk) 200 to JSONObject().put("ok", true).put("activity", act()).toString() else 0 to ""
                url.contains("pin-auth/me") -> 200 to "{\"ok\":true,\"available\":true,\"user\":null}"
                url.contains("version.json") -> 0 to ""
                body != null && String(body).contains("\"team-pin\"") -> 200 to "{\"ok\":true,\"pin\":\"5307\"}"
                method == "GET" -> 200 to snapshot().toString()
                else -> 200 to "{\"ok\":true}"
            }
        }
        return m
    }
    private fun show(m: AppModel, dark: Boolean = false) = rule.setContent { RafikiTheme(dark = dark) { Box(Modifier.size(width.dp, 844.dp)) { App(m) } } }
    private fun shot(name: String) {
        rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun sheetShot(name: String) {
        rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); val c = Canvas(b); v.draw(c)
        val d = org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window?.decorView
        if (d != null) { c.save(); c.translate(0f, (v.height - d.height).toFloat()); d.draw(c); c.restore() }
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun tag(t: String) = rule.onNodeWithTag(t)
    private fun waitTag(t: String) = rule.waitUntil(8000) { rule.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty() }
    private fun waitFor(text: String) = rule.waitUntil(8000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    private fun has(text: String) = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun start(m: AppModel, dark: Boolean = false) { show(m, dark); waitTag("hav"); rule.mainClock.advanceTimeBy(2000) }
    private fun office(m: AppModel, dark: Boolean = false) { start(m, dark); tag("nav_office").performClick(); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle() }

    // --- small rules ------------------------------------------------------------------------------------------------

    @Test fun money_and_dates_follow_the_brief() {
        Lang.sw = false
        assertEquals("KES 4,500", Money.kes(4500))
        val today = LocalDate.of(2026, 10, 22)
        assertEquals("due today", dueText("2026-10-22", today)); assertEquals("due tomorrow", dueText("2026-10-23", today)); assertEquals("due in 2 days", dueText("2026-10-24", today))
        assertEquals("due Fri 30 Oct", dueText("2026-10-30", today)); assertEquals("3 days late", dueText("2026-10-19", today))
        assertEquals("Thu 29 Oct", dueTag("2026-10-29", today)); assertEquals("In 2 days", dueTag("2026-10-24", today)); assertEquals("3 Jan 2027", dueTag("2027-01-03", today))
        assertEquals("3 days", ocvLateShort("2026-10-19", "2026-10-22"))
        Lang.sw = true
        assertEquals("Alh 29 Okt", dueTag("2026-10-29", today)); assertEquals("Kesho", dueTag("2026-10-23", today)); assertEquals("imechelewa siku 3", dueText("2026-10-19", today))
        assertEquals("siku 3", ocvLateShort("2026-10-19", "2026-10-22"))
        Lang.sw = false
    }

    @Test fun notifications_titled_by_person_or_job_in_their_channels() {
        Lang.sw = false
        val visit = Alert("repair", "2026-10-20T08:00:00Z", "104", "Bertin: Kapsaret Borehole · needs repair", "Bertin Mwangi", "Kapsaret Borehole", "Reported it needs repair")
        val job = Alert("job", "2026-10-20T08:00:00Z", "104", "Repair job for you: Kapsaret Borehole", "", "Kapsaret Borehole", "This repair is yours to do", "Repair job")
        val sms = Alert("repair", "2026-10-20T08:00:00Z", "104", "x", "SMS from +254 712 ••• 678", "Kapsaret Borehole", "Reported it’s not working")
        assertEquals("Bertin Mwangi", AlertWorker.titleOf(visit)); assertEquals("Kapsaret Borehole · Reported it needs repair", AlertWorker.textOf(visit))
        assertEquals("Repair job", AlertWorker.titleOf(job)); assertEquals("Community report", AlertWorker.titleOf(sms))
        assertEquals(listOf("jobs", "jobs", "repairs", "repairs", "team", "team", "updates"), listOf("assign", "job", "repair", "fixed", "new", "review", "update").map { AlertWorker.channelOf(it) })
        // posting: one per alert in its own channel, plus one summary that groups them
        Prefs(app).phoneAlerts = true
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        AlertWorker.show(app, listOf(visit, job))
        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        val shown = org.robolectric.Shadows.shadowOf(nm).allNotifications
        assertEquals(3, shown.size)
        assertEquals(setOf("repairs", "jobs"), shown.filter { it.flags and android.app.Notification.FLAG_GROUP_SUMMARY == 0 }.map { it.channelId }.toSet())
        assertTrue(shown.any { it.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0 })
        assertEquals(setOf("jobs", "repairs", "team", "updates"), nm.notificationChannels.map { it.id }.toSet())
    }

    // --- Office -----------------------------------------------------------------------------------------------------

    @Test fun office_overview_plurals_and_tab_strip() {
        Lang.sw = false
        val m = model(); office(m)
        waitTag("ocv_t_repairs"); waitFor("open repair")
        // the words follow the number: 1 open repair (2 visits this month)
        tag("ocv_t_repairs").assertTextContains("open repair"); assertTrue(rule.onAllNodes(hasTestTag("ocv_t_repairs") and hasText("open repairs")).fetchSemanticsNodes().isEmpty())
        waitFor("visits this month")
        assertTrue(has("1 urgent · KES 4,500"))
        shot("po-01-office-overview")
        // picking a tab folds the 13 tiles into a strip of chips
        tag("o_visits").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        waitTag("office_strip"); tag("o_all").assertExists()
        waitTag("ocv_visit")
        shot("po-02-office-visits-strip")
        tag("o_qr").performScrollTo(); rule.mainClock.advanceTimeBy(800); tag("o_qr").performClick(); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        shot("po-03-office-strip-end")
        tag("o_all").performScrollTo(); rule.mainClock.advanceTimeBy(800); tag("o_all").performClick(); rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        rule.onAllNodesWithTag("office_strip").assertCountEquals(0)
    }

    @Test @Config(qualifiers = "w360dp-h800dp-xxhdpi") fun office_kiswahili_dark_360() {
        Lang.sw = true; width = 360
        val m = model(); office(m, dark = true)
        waitTag("ocv_t_repairs"); waitFor("tengenezo lililo wazi")
        assertTrue(has("1 ya haraka · KES 4,500"))
        assertTrue(has("Matengenezo") && has("Safisha data") && has("Hamisha") && has("Kagua"))
        assertTrue(!has("Ukarabati") && !has("Usafi") && !has("Pakua"))
        shot("po-04-office-overview-sw-dark-360")
        tag("o_visits").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        waitTag("ocv_visit")
        shot("po-05-office-visits-sw-dark-360")
        tag("ocv_visit").performScrollTo().performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("ocv_visit_sheet").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(has("Imechelewa · siku 3")); assertTrue(!has("Imechelewa · Imechelewa"))
        sheetShot("po-06-visit-sheet-sw-dark-360")
    }

    @Test fun office_cleanup_head_light_and_dark_sw() {
        Lang.sw = false
        val m = model(); office(m)
        tag("o_cleanup").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        waitTag("clean_head"); assertTrue(has("no location") && has("location to check") && has("no photo"))
        shot("po-07-cleanup")
    }

    @Test fun office_cleanup_kiswahili_dark() {
        Lang.sw = true
        val m = model(); office(m, dark = true)
        tag("o_cleanup").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        waitTag("clean_head"); assertTrue(has("Safisha data") && has("bila mahali"))
        shot("po-08-cleanup-sw-dark")
    }

    @Test fun new_team_pin_copy_send_then_hidden() {
        Lang.sw = false
        val m = model(); office(m)
        tag("o_team").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        waitTag("tm_Meril Kiprop"); assertTrue(has("PIN set by the office")); assertTrue(!has("Cloudflare"))
        tag("tm_Meril Kiprop").performScrollTo().performClick(); waitTag("team_sheet")
        tag("tm_newpin").performScrollTo().performClick(); rule.waitForIdle()
        tag("tm_yes").performScrollTo().performClick()
        waitTag("tm_pin_shown")
        tag("tm_pin_copy").assertExists(); tag("tm_pin_wa").assertExists(); tag("tm_pin_sms").assertExists()
        tag("tm_pin_card").performScrollTo()
        sheetShot("po-09-team-new-pin")
        tag("tm_pin_copy").performClick(); rule.waitForIdle()
        val clip = app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        assertEquals("5307", clip.primaryClip?.getItemAt(0)?.text?.toString())
        // after a minute it is hidden for good
        repeat(62) { rule.mainClock.advanceTimeBy(1000) }; rule.waitForIdle()
        rule.onAllNodesWithTag("tm_pin_shown").assertCountEquals(0); tag("tm_pin_hidden").assertExists()
        sheetShot("po-10-team-pin-hidden")
    }

    @Test fun new_team_pin_kiswahili_dark() {
        Lang.sw = true
        val m = model(); office(m, dark = true)
        tag("o_team").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        waitTag("tm_Meril Kiprop"); tag("tm_Meril Kiprop").performScrollTo().performClick(); waitTag("team_sheet")
        tag("tm_newpin").performScrollTo().performClick(); rule.waitForIdle(); tag("tm_yes").performScrollTo().performClick()
        waitTag("tm_pin_shown"); tag("tm_pin_card").performScrollTo()
        sheetShot("po-11-team-new-pin-sw-dark")
    }

    // --- Me & team -------------------------------------------------------------------------------------------------

    @Test fun me_month_once_and_team_with_community_reports() {
        Lang.sw = false
        val m = model(); start(m)
        tag("hav").performClick(); waitTag("me_hero"); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        // the month's tiles once, one Monthly report, the chart in My month
        assertEquals(1, rule.onAllNodesWithText("Monthly report").fetchSemanticsNodes().size)
        rule.onAllNodesWithTag("my_visit").assertCountEquals(0); rule.onAllNodesWithTag("my_week").assertCountEquals(6)
        shot("po-12-me-top")
        tag("my_work").performScrollTo(); assertTrue(has("Repair job · due in 2 days")); shot("po-13-me-work-month")
        // the team: a water committee's SMS is a "Community report", not a teammate
        tag("pane_team").performClick(); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        tag("tm_community").performScrollTo(); assertTrue(has("Community report"))
        tag("tm_total").assertTextEquals("1 pins · 1 visits · 1 fixed")
        shot("po-14-team-totals")
        tag("feed_community").performScrollTo(); assertTrue(!has("SMS ")); shot("po-15-team-feed")
    }

    @Test fun me_kiswahili_dark_lock_and_skeleton() {
        Lang.sw = true
        val m = model(actOk = false); start(m, dark = true)
        tag("hav").performClick(); waitTag("me_hero"); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        assertTrue(has("Funga kwa PIN")); assertTrue(!has("Funga programu"))
        // no numbers yet: grey boxes, not dashes
        assertTrue(rule.onAllNodesWithTag("skel").fetchSemanticsNodes().size >= 3); assertTrue(!has("–"))
        shot("po-16-me-loading-sw-dark")
    }

    // --- Settings -----------------------------------------------------------------------------------------------------

    private fun settings(m: AppModel, dark: Boolean = false) { start(m, dark); tag("hav").performClick(); waitTag("settings"); rule.mainClock.advanceTimeBy(1500) }

    @Test fun settings_rows_and_pages() {
        Lang.sw = false; Bio.available = { true }
        val m = model(); settings(m)
        tag("settings").performScrollTo()
        assertTrue(has("Connected as Reid Anderson")); rule.onAllNodesWithTag("monday_paste").assertCountEquals(0)
        assertTrue(has("Fingerprint or face unlock"))
        shot("po-17-settings")
        tag("set_notify").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        tag("set_title").assertTextEquals("Notification settings")
        tag("nk_off_note").assertExists(); tag("nk_fixed").assertIsNotEnabled()
        shot("po-18-notification-settings-off")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }; rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        tag("set_look").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        tag("theme_system").assertExists(); shot("po-19-appearance")
        tag("theme_dark").performScrollTo().performClick(); rule.waitForIdle(); assertEquals("dark", Look.mode)
        tag("theme_system").performScrollTo().performClick(); rule.waitForIdle(); assertEquals("", Look.mode); assertEquals("", m.prefs.theme)
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }; rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        tag("set_help").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        tag("replay").performScrollTo(); shot("po-20-help-welcome-again")
        tag("replay").performClick(); assertTrue(m.replayOnb); m.replayOnb = false
    }

    @Test fun settings_about_check_for_updates() {
        Lang.sw = false
        val m = model(); settings(m)
        tag("set_about").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        tag("about_update").assertExists(); assertTrue(has("Check for updates"))
        tag("about_update").performClick(); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        rule.waitUntil(5000) { has("Couldn’t check") || has("up to date") }
        shot("po-21-about")
    }

    @Test fun settings_monday_page() {
        Lang.sw = false
        val m = model(); settings(m)
        tag("monday_row").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        tag("set_title").assertTextEquals("Your name on monday.com"); tag("monday_off").assertExists()
        shot("po-22-monday-page")
    }

    @Test fun settings_kiswahili_dark() {
        Lang.sw = true; Bio.available = { true }
        val m = model(); settings(m, dark = true)
        tag("settings").performScrollTo()
        assertTrue(has("Fungua kwa kidole au uso"))
        shot("po-23-settings-sw-dark")
        tag("set_notify").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        tag("set_title").assertTextEquals("Mipangilio ya arifa"); shot("po-24-notification-settings-sw-dark")
    }

    @Test @Config(qualifiers = "w360dp-h800dp-xxhdpi") fun settings_kiswahili_360() {
        Lang.sw = true; width = 360; Bio.available = { true }
        val m = model(); settings(m)
        tag("settings").performScrollTo(); shot("po-25-settings-sw-360")
        tag("set_look").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        shot("po-26-appearance-sw-360")
    }

    // --- the bottom bar ---------------------------------------------------------------------------------------------

    @Test fun bottom_bar_keeps_the_tab_lit_under_a_project() {
        Lang.sw = false
        val m = model(); start(m)
        tag("nav_list").assertIsSelected()
        m.goProject = "104"; rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        tag("nav_list").assertIsSelected(); tag("nav_me").assertIsNotSelected()
        tag("nav_me").performClick(); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        tag("nav_me").assertIsSelected(); tag("nav_list").assertIsNotSelected()
        shot("po-27-nav-me")
    }
}

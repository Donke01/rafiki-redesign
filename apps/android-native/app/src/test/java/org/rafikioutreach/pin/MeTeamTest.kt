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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.ui.Onb
import org.rafikioutreach.pin.ui.RafikiTheme
import org.rafikioutreach.pin.ui.waNumber
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Me & team: my month, my work, waiting to send, team today, team totals, team feed. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class MeTeamTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private fun ago(h: Long) = Instant.now().minus(h, ChronoUnit.HOURS).toString()

    private fun proj(id: String, t: String) = JSONObject().put("id", id).put("title", t).put("country", "Kenya").put("ck", "ke").put("area", "Eldoret")
        .put("type", "borehole").put("status", "Completed").put("pump", "manual").put("gps", JSONArray().put(0.51).put(35.27))
    private fun snapshot() = JSONObject().put("ok", true).put("who", "Meril Kiprop").put("admin", true).put("reviewOpen", 0)
        .put("projects", JSONArray().put(proj("101", "Chepkongi Dispensary")).put(proj("104", "Kapsaret Borehole")).put(proj("105", "Turbo Spring")))
        .put("repairs", JSONObject().put("104", JSONObject().put("state", "repair").put("by", "Bertin").put("at", ago(30)).put("t", "Kapsaret Borehole").put("step", "assigned")
            .put("assignee", "Meril Kiprop").put("due", LocalDate.now().minusDays(2).toString())))
        .put("visits", JSONObject()).put("jobs", JSONArray().put("104"))
        .put("assigned", JSONArray().put(JSONObject().put("aid", "a1").put("id", "105").put("t", "Turbo Spring").put("date", LocalDate.now().toString()).put("by", "Grace").put("note", "Check the tank")))
        .put("confirmed", JSONObject())

    private fun act(by: String, a: String, id: String, t: String, h: Long, s: String = "") = JSONObject().put("by", by).put("a", a).put("id", id).put("t", t).put("at", ago(h)).apply { if (s.isNotBlank()) put("s", s) }

    @Test fun me_and_team() {
        org.rafikioutreach.pin.ui.Motion.on = false
        val app = ApplicationProvider.getApplicationContext<Application>()
        Prefs(app).apply { pin = "9999"; name = "Meril Kiprop"; saveSnapshot(snapshot()); filter = "all"; onb = true; country = ""; lang = "en" }
        Lang.sw = false
        val m = AppModel(app)
        Onb.enabled = false
        val feed = JSONArray()
        listOf(act("Meril Kiprop", "pin", "101", "Chepkongi Dispensary", 50), act("Meril Kiprop", "visit", "101", "Chepkongi Dispensary", 26, "working"),
            act("Bertin Mwangi", "visit", "104", "Kapsaret Borehole", 30, "repair"), act("Grace Achieng", "fixed", "105", "Turbo Spring", 3),
            act("Meril Kiprop", "visit", "104", "Kapsaret Borehole", 1, "repair"), act("Grace Achieng", "pin", "104", "Kapsaret Borehole", 2)).forEach { feed.put(it) }
        val team = JSONArray().put(JSONObject().put("name", "Bertin Mwangi").put("home", "tz").put("phone", "0754 123 456").put("lastSeen", ago(29)))
            .put(JSONObject().put("name", "Grace Achieng").put("home", "ke").put("phone", "+254 712 000 111").put("lastSeen", ago(2)))
            .put(JSONObject().put("name", "Meril Kiprop").put("home", "ke").put("phone", "").put("lastSeen", ago(0)))
        m.api.transport = { method, url, _, _ ->
            when {
                url.contains("activity=1") -> 200 to JSONObject().put("ok", true).put("activity", feed).toString()
                url.contains("team=1") -> 200 to JSONObject().put("ok", true).put("team", team).toString()
                url.contains("pin-auth/me") -> 200 to "{\"ok\":true,\"available\":false}"
                method == "GET" -> 200 to snapshot().toString()
                else -> 0 to ""   // no signal: changes stay waiting
            }
        }
        runBlocking { m.submit(JSONObject().put("action", "visit").put("id", "101").put("state", "working"), emptyList()) }
        rule.setContent { RafikiTheme(dark = false) { Box(Modifier.size(390.dp, 844.dp)) { App(m) } } }
        rule.waitUntil(8000) { rule.onAllNodesWithTag("hav").fetchSemanticsNodes().isNotEmpty() }
        rule.mainClock.advanceTimeBy(2000)
        rule.onNodeWithTag("hav").performClick()
        rule.waitUntil(8000) { rule.onAllNodesWithTag("hero_visit").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithTag("waiting_row").assertCountEquals(1)
        rule.onNodeWithText("Send now").assertExists()
        rule.mainClock.advanceTimeBy(1500)
        shot("60-me-top")
        rule.onNodeWithTag("my_work").performScrollTo(); rule.onNodeWithText("Overdue").assertExists(); rule.onNodeWithText("Today").assertExists()
        rule.onNodeWithTag("my_month").performScrollTo(); shot("61-me-month")
        if (LocalDate.now().dayOfMonth > 2) { rule.onNodeWithTag("my_visit").assertTextEquals("2"); rule.onNodeWithTag("hero_visit").assertTextEquals("2") }
        rule.onNodeWithTag("my_streak").assertTextContains("in a row", substring = true)
        rule.onNodeWithTag("this_phone").performScrollTo(); shot("61b-this-phone")
        rule.onNodeWithTag("monday_open").performScrollTo(); shot("61c-monday")
        rule.onNodeWithTag("size_s").performScrollTo().performClick(); rule.waitForIdle()
        assertEquals("s", org.rafikioutreach.pin.ui.TextSize.value); assertEquals("s", m.prefs.textSize); shot("61d-text-small")
        rule.onNodeWithTag("size_").performScrollTo().performClick(); rule.waitForIdle()
        assertEquals("", m.prefs.textSize)
        rule.onNodeWithTag("pane_me").performScrollTo()
        rule.onNodeWithTag("pane_team").performScrollTo().performClick(); rule.waitForIdle()
        rule.waitUntil(8000) { rule.onAllNodesWithTag("mate_row").fetchSemanticsNodes().size == 2 }
        rule.onAllNodesWithTag("my_work").assertCountEquals(0)
        rule.onNodeWithTag("team_today").performScrollTo(); Thread.sleep(4000); rule.mainClock.advanceTimeBy(500); shot("62-team-today")
        rule.onAllNodesWithTag("call_phone").assertCountEquals(2)
        rule.onNodeWithTag("team_month").performScrollTo(); shot("63-team-month")
        rule.onNodeWithTag("team_feed").performScrollTo(); shot("64-team-feed")
        rule.onAllNodesWithTag("feed_row").onFirst().performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("tracker").fetchSemanticsNodes().isNotEmpty() || rule.onAllNodesWithTag("tab_pin").fetchSemanticsNodes().isNotEmpty() }
        org.rafikioutreach.pin.ui.Motion.on = true
    }

    @Test fun update_button() {
        org.rafikioutreach.pin.ui.Motion.on = false
        val app = ApplicationProvider.getApplicationContext<Application>()
        Prefs(app).apply { pin = "9999"; name = "Meril Kiprop"; saveSnapshot(snapshot()); filter = "all"; onb = true; country = ""; lang = "en"; mePane = "me" }
        Lang.sw = false
        val m = AppModel(app); Onb.enabled = false
        m.api.transport = { _, url, _, _ ->
            when {
                url.contains("/app/version.json") -> 200 to "{\"ok\":true,\"versionCode\":99,\"versionName\":\"9.9\",\"notes\":\"Office map and faster sync.\"}"
                url.contains("activity=1") -> 200 to "{\"ok\":true,\"activity\":[]}"
                url.contains("team=1") -> 200 to "{\"ok\":true,\"team\":[]}"
                url.contains("pin-auth/me") -> 200 to "{\"ok\":true,\"available\":false}"
                else -> 200 to snapshot().toString()
            }
        }
        rule.setContent { RafikiTheme(dark = false) { Box(Modifier.size(390.dp, 844.dp)) { App(m) } } }
        rule.waitUntil(8000) { rule.onAllNodesWithTag("hav").fetchSemanticsNodes().isNotEmpty() }
        // the pop-up: Remind me later puts it off until tomorrow
        rule.mainClock.advanceTimeBy(2000)
        rule.waitUntil(8000) { rule.onAllNodesWithTag("update_prompt").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Version 9.9", substring = true).assertExists()
        shotNode("update_prompt", "68-update-popup")
        rule.onNodeWithTag("update_prompt_later").performClick(); rule.waitForIdle()
        rule.onAllNodesWithTag("update_prompt").assertCountEquals(0)
        assert(m.prefs.updLater > System.currentTimeMillis() + 23 * 3600_000L)
        rule.onNodeWithTag("hav").performClick()
        rule.waitUntil(8000) { rule.onAllNodesWithTag("update_go").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("update_title").assertTextEquals("Update available · 9.9")
        rule.onAllNodesWithTag("update_dot", useUnmergedTree = true).assertCountEquals(1) // the dot on Me
        rule.onNodeWithTag("app_update").performScrollTo(); shot("65-app-update")
        rule.onNodeWithTag("update_row").performScrollTo(); shot("66-update-row")
        rule.onNodeWithTag("update_status", useUnmergedTree = true).assertTextEquals("Update available · 9.9")
        // Check finds the update: the screen moves up to the update card
        rule.onNodeWithTag("app_update").assertIsNotDisplayed()
        rule.onNodeWithTag("update_check").performClick()
        rule.mainClock.advanceTimeBy(2500); rule.waitForIdle()
        rule.waitUntil(5000) { !m.focusUpdate }
        rule.mainClock.advanceTimeBy(600); rule.waitForIdle()
        rule.onNodeWithTag("app_update").assertIsDisplayed(); shot("69-check-scrolls-up")
        // Samsung's Auto Blocker cancels the install: explain it and link to the setting
        val U = org.rafikioutreach.pin.data.Updater
        U.samsung = false; U.setState(org.rafikioutreach.pin.data.Updater.State.Failed("aborted")); rule.waitForIdle()
        rule.onAllNodesWithTag("auto_blocker").assertCountEquals(0)
        U.samsung = true; U.setState(org.rafikioutreach.pin.data.Updater.State.Idle); U.setState(org.rafikioutreach.pin.data.Updater.State.Failed("aborted")); rule.waitForIdle()
        rule.onNodeWithTag("auto_blocker").performScrollTo(); rule.onNodeWithTag("auto_blocker_open").assertExists(); shot("67-auto-blocker")
        U.samsung = false; U.reset()
        org.rafikioutreach.pin.ui.Motion.on = true
    }

    @Test fun popup_update_now() {
        org.rafikioutreach.pin.ui.Motion.on = false
        val app = ApplicationProvider.getApplicationContext<Application>()
        Prefs(app).apply { pin = "9999"; name = "Meril Kiprop"; saveSnapshot(snapshot()); filter = "all"; onb = true; country = ""; lang = "en"; mePane = "team" }
        Lang.sw = false
        val m = AppModel(app); Onb.enabled = false
        m.api.transport = { _, url, _, _ ->
            when {
                url.contains("/app/version.json") -> 200 to "{\"ok\":true,\"versionCode\":99,\"versionName\":\"9.9\",\"notes\":\"Office map and faster sync.\"}"
                url.contains("activity=1") -> 200 to "{\"ok\":true,\"activity\":[]}"
                url.contains("team=1") -> 200 to "{\"ok\":true,\"team\":[]}"
                url.contains("pin-auth/me") -> 200 to "{\"ok\":true,\"available\":false}"
                else -> 200 to snapshot().toString()
            }
        }
        var asked: String? = null
        org.rafikioutreach.pin.data.Updater.fake = { asked = it }
        rule.setContent { RafikiTheme(dark = false) { Box(Modifier.size(390.dp, 844.dp)) { App(m) } } }
        rule.waitUntil(8000) { rule.onAllNodesWithTag("hav").fetchSemanticsNodes().isNotEmpty() }
        rule.mainClock.advanceTimeBy(2000)
        rule.waitUntil(8000) { rule.onAllNodesWithTag("update_prompt").fetchSemanticsNodes().isNotEmpty() }
        // Update now: goes to Me (even from the Team side), shows the card and starts the download
        rule.onNodeWithTag("update_prompt_go").performClick()
        rule.mainClock.advanceTimeBy(1000); rule.waitForIdle()
        rule.waitUntil(5000) { asked != null }
        rule.onAllNodesWithTag("update_prompt").assertCountEquals(0)
        rule.onNodeWithTag("app_update").assertIsDisplayed()
        assertEquals(org.rafikioutreach.pin.BuildConfig.SERVER + "/app/rafiki-pin.apk", asked)
        org.rafikioutreach.pin.data.Updater.fake = null
        org.rafikioutreach.pin.ui.Motion.on = true
    }

    @Test fun popup_close_and_later() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Prefs(app).apply { pin = "9999"; name = "Meril Kiprop"; saveSnapshot(snapshot()); onb = true; lang = "en"; updCode = 99; updName = "9.9"; updAt = System.currentTimeMillis(); updLater = System.currentTimeMillis() + 3600_000L }
        Lang.sw = false
        val m = AppModel(app); Onb.enabled = false
        m.api.transport = { _, _, _, _ -> 200 to snapshot().toString() }
        rule.setContent { RafikiTheme(dark = false) { Box(Modifier.size(390.dp, 844.dp)) { App(m) } } }
        rule.waitUntil(8000) { rule.onAllNodesWithTag("hav").fetchSemanticsNodes().isNotEmpty() }
        rule.mainClock.advanceTimeBy(3000); rule.waitForIdle()
        rule.onAllNodesWithTag("update_prompt").assertCountEquals(0)   // "Remind me later" still running
        m.prefs.updLater = 0L; m.updPromptClosed = false
        m.checkUpdateDaily()
    }

    private fun shotNode(tag: String, name: String) {
        rule.mainClock.advanceTimeBy(700); rule.waitForIdle()
        rule.onNodeWithTag(tag).assertExists()
        // the pop-up is its own window: draw the app, dim it, then draw the pop-up's window on top
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); val c = Canvas(b); v.draw(c)
        c.drawColor(0x99000000.toInt())
        val d = org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window?.decorView ?: return
        c.save(); c.translate(((v.width - d.width) / 2).toFloat(), ((v.height - d.height) / 2).toFloat()); d.draw(c); c.restore()
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun bell_and_settings() {
        org.rafikioutreach.pin.ui.Motion.on = false
        val app = ApplicationProvider.getApplicationContext<Application>()
        val snap = snapshot().put("me", JSONObject().put("kind", "app").put("nick", "").put("phone", "").put("home", "ke"))
            .put("jobs", JSONArray().put("104")).put("repairs", JSONObject().put("104", JSONObject().put("state", "notworking").put("by", "Bertin").put("at", ago(3)).put("t", "Kapsaret Borehole").put("assignee", "Meril Kiprop").put("stepAt", JSONObject().put("assigned", ago(1)))))
        Prefs(app).apply { pin = "9999"; name = "Meril Kiprop"; saveSnapshot(snap); filter = "all"; onb = true; country = ""; lang = "en"; mePane = "me"; noteSeen = ""; noteOff = emptySet() }
        Lang.sw = false
        val m = AppModel(app); Onb.enabled = false
        val feed = JSONArray().put(act("Bertin Mwangi", "visit", "104", "Kapsaret Borehole", 2, "notworking")).put(act("Grace Achieng", "fixed", "105", "Turbo Spring", 5))
            .put(act("Meril Kiprop", "pin", "101", "Chepkongi Dispensary", 1)).put(JSONObject().put("by", "Reid Anderson").put("a", "assign").put("to", "Meril Kiprop").put("n", 2).put("t", "2 visits for Meril Kiprop").put("at", ago(1)))
        val posted = java.util.concurrent.CopyOnWriteArrayList<String>()
        m.api.transport = { method, url, _, body ->
            if (body != null) posted += String(body)
            when {
                url.contains("activity=1") -> 200 to JSONObject().put("ok", true).put("activity", feed).toString()
                url.contains("team=1") -> 200 to "{\"ok\":true,\"team\":[]}"
                url.contains("pin-auth/me") -> 200 to "{\"ok\":true,\"available\":false}"
                method == "POST" && body != null && String(body).contains("\"profile\"") -> 200 to "{\"ok\":true,\"me\":{\"kind\":\"app\",\"nick\":\"Mery\",\"phone\":\"0712 345 678\",\"home\":\"ke\"}}"
                method == "POST" && body != null && String(body).contains("pin-change") -> 200 to "{\"ok\":true}"
                else -> 200 to snap.toString()
            }
        }
        rule.setContent { RafikiTheme(dark = false) { Box(Modifier.size(390.dp, 844.dp)) { App(m) } } }
        rule.waitUntil(8000) { rule.onAllNodesWithTag("hav").fetchSemanticsNodes().isNotEmpty() }
        rule.mainClock.advanceTimeBy(2000); rule.onNodeWithTag("hav").performClick()
        rule.waitUntil(8000) { rule.onAllNodesWithTag("bell_count", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(4, m.unread())   // assign, job, repair, fixed (not my own pin)
        rule.onNode(hasText("4") and hasAnyAncestor(hasTestTag("bell_count")), useUnmergedTree = true).assertExists()
        shot("80-me-bell")
        rule.onNodeWithTag("bell").performClick(); rule.waitForIdle(); rule.mainClock.advanceTimeBy(800)
        rule.onAllNodesWithTag("alert_row").assertCountEquals(4)
        rule.onNodeWithText("Reid Anderson asked you to make 2 visits").assertExists()
        shot("81-bell-list")
        org.junit.Assert.assertEquals(0, m.unread())
        rule.onNodeWithText("Grace Achieng fixed Turbo Spring").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("hback").performClick(); rule.waitForIdle()
        // Settings
        rule.onNodeWithTag("settings").performScrollTo(); shot("82-settings")
        rule.onNodeWithTag("set_profile").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("pf_nick"))).performTextInput("Mery")
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("pf_phone"))).performTextInput("0712 345 678")
        shot("83-profile")
        rule.onNodeWithTag("pf_save").performScrollTo().performClick()
        rule.waitUntil(5000) { posted.any { it.contains("\"profile\"") } }
        org.junit.Assert.assertTrue(posted.first { it.contains("\"profile\"") }.let { it.contains("0712 345 678") && it.contains("Mery") })
        rule.waitUntil(5000) { m.snap.value?.me?.phone == "0712 345 678" }
        rule.waitUntil(5000) { rule.onAllNodesWithTag("set_pin").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("set_pin").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("cp_old"))).performTextInput("9999")
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("cp_new"))).performTextInput("4826")
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("cp_new2"))).performTextInput("4826")
        shot("84-change-pin")
        rule.onNodeWithTag("cp_save").performScrollTo().performClick()
        rule.waitUntil(5000) { m.prefs.pin == "4826" }
        rule.waitUntil(5000) { rule.onAllNodesWithTag("set_notify").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("set_notify").performScrollTo().performClick(); rule.waitForIdle(); shot("85-notify")
        rule.onNodeWithTag("nk_fixed").performScrollTo().performClick()
        org.junit.Assert.assertTrue("fixed" in m.prefs.noteOff)
        rule.onNodeWithTag("hback").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("set_help").performScrollTo().performClick(); rule.waitForIdle(); shot("86-help")
        rule.onNodeWithTag("hback").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("set_about").performScrollTo().performClick(); rule.waitForIdle(); shot("87-about")
        org.rafikioutreach.pin.ui.Motion.on = true
    }

    @Test fun alerts_rules() {
        val s = org.rafikioutreach.pin.data.Snapshot.from(snapshot().put("admin", true).put("reviewOpen", 2))
        val a = org.rafikioutreach.pin.data.alertsFor(s, listOf(act("Meril Kiprop", "visit", "1", "A", 1, "repair"), act("Bertin", "new", "2", "B", 2), act("Bertin", "visit", "3", "C", 3, "working")), "Meril Kiprop", emptySet(), "")
        assertEquals(listOf("job", "new", "review"), a.map { it.kind }.sorted())   // not my own, not a working visit
        assertEquals(listOf("new"), org.rafikioutreach.pin.data.alertsFor(s, listOf(act("Bertin", "new", "2", "B", 2)), "Meril Kiprop", setOf("review", "job"), "").map { it.kind })
    }

    @Test fun whatsapp_numbers() {
        assertEquals("255754123456", waNumber("0754 123 456", "tz"))
        assertEquals("254712000111", waNumber("+254 712 000 111", "ke"))
        assertEquals("265991234567", waNumber("0991234567", "mw"))
        assertEquals("254700000000", waNumber("0700000000", ""))
    }

    private fun shot(name: String) {
        rule.mainClock.advanceTimeBy(700); rule.waitForIdle()
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

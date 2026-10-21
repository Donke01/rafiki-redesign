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
        rule.onNodeWithTag("pane_team").performScrollTo().performClick(); rule.waitForIdle()
        rule.waitUntil(8000) { rule.onAllNodesWithTag("mate_row").fetchSemanticsNodes().size == 2 }
        rule.onAllNodesWithTag("my_work").assertCountEquals(0)
        rule.onNodeWithTag("team_today").performScrollTo(); shot("62-team-today")
        rule.onAllNodesWithTag("call_phone").assertCountEquals(2)
        rule.onNodeWithTag("team_month").performScrollTo(); shot("63-team-month")
        rule.onNodeWithTag("team_feed").performScrollTo(); shot("64-team-feed")
        rule.onAllNodesWithTag("feed_row").onFirst().performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("tracker").fetchSemanticsNodes().isNotEmpty() || rule.onAllNodesWithTag("tab_pin").fetchSemanticsNodes().isNotEmpty() }
        org.rafikioutreach.pin.ui.Motion.on = true
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

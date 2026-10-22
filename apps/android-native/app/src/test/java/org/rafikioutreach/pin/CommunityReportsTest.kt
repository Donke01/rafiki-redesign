package org.rafikioutreach.pin

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.ui.Look
import org.rafikioutreach.pin.ui.RafikiTheme
import org.rafikioutreach.pin.ui.smsBucket
import org.rafikioutreach.pin.ui.smsDialable
import org.rafikioutreach.pin.ui.smsExplain
import org.rafikioutreach.pin.ui.smsResult
import org.rafikioutreach.pin.ui.smsSender
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Office › Community: SMS / WhatsApp reports from water committees, set-up status, committee phones, voice notes typed out. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class CommunityReportsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val calls = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun snapshot() = JSONObject().put("ok", true).put("who", "Reid Anderson").put("admin", true).put("reviewOpen", 0)
        .put("projects", JSONArray().put(JSONObject().put("id", "104").put("title", "Kapsaret Borehole").put("country", "Kenya").put("ck", "ke").put("area", "Eldoret").put("type", "borehole").put("status", "Completed").put("pump", "manual").put("gps", JSONArray().put(0.47).put(35.25))))
        .put("repairs", JSONObject().put("104", JSONObject().put("state", "notworking").put("by", "SMS from +254•••678").put("at", "2026-10-21T07:58:00Z").put("t", "Kapsaret Borehole").put("src", "sms")))
        .put("visits", JSONObject()).put("jobs", JSONArray()).put("assigned", JSONArray()).put("confirmed", JSONObject())

    private fun ago(min: Long) = java.time.Instant.now().minusSeconds(min * 60).toString()
    private fun sms(on: Boolean) = JSONObject().put("ok", true)
        .put("setup", JSONObject().put("storage", true).put("monday", true)
            .put("at", JSONObject().put("on", on).put("replies", on).put("vars", JSONObject().put("SMS_WEBHOOK_TOKEN", on).put("AT_USERNAME", on).put("AT_API_KEY", on).put("AT_SENDER_ID", false)))
            .put("twilio", JSONObject().put("on", false).put("vars", JSONObject().put("TWILIO_AUTH_TOKEN", false))))
        .put("voice", JSONObject().put("on", on).put("provider", "openai").put("missing", if (on) JSONArray() else JSONArray().put("OPENAI_API_KEY")))
        .put("urls", JSONObject().put("at", "https://rafiki.example/api/sms?token=YOUR_SMS_WEBHOOK_TOKEN").put("twilio", "https://rafiki.example/api/sms"))
        .put("reports", if (!on) JSONArray() else JSONArray()
            .put(JSONObject().put("at", ago(2)).put("ch", "whatsapp").put("from", "wa:KE.1461161065410436").put("text", "Broken 104").put("id", "104").put("t", "").put("result", "ignored-no-project"))
            .put(JSONObject().put("at", ago(30)).put("ch", "sms").put("ch", "sms").put("from", "+254712345678").put("text", "IMEHARIBIKA 104 pampu").put("id", "104").put("t", "Kapsaret Borehole").put("result", "reported-broken")
                .put("reply", "RAFIKI: Asante. Ripoti ya Kapsaret Borehole (haifanyi kazi) imepokelewa."))
            .put(JSONObject().put("at", ago(26 * 60)).put("ch", "whatsapp").put("from", "+255712000111").put("name", "Turbo water committee").put("text", "FIXED").put("id", "105").put("t", "Turbo Spring").put("result", "fixed"))
            .put(JSONObject().put("at", ago(10 * 24 * 60)).put("ch", "sms").put("from", "+254700000001").put("text", "win a prize now").put("id", "").put("t", "").put("result", "ignored-unregistered")))
        .put("phones", JSONArray().put(JSONObject().put("phone", "+255712000111").put("id", "105").put("t", "Turbo Spring").put("name", "Turbo water committee")))

    private fun model(on: Boolean): AppModel {
        org.rafikioutreach.pin.ui.Onb.enabled = false
        val app = ApplicationProvider.getApplicationContext<Application>()
        Prefs(app).apply { pin = "9999"; name = "Reid Anderson"; saveSnapshot(snapshot()); filter = "all" }
        val m = AppModel(app)
        m.api.transport = { method, url, _, _ ->
            calls += "$method $url"
            when {
                url.contains("/api/sms-admin") -> 200 to sms(on).toString()
                url.contains("view=team") -> 200 to "{\"ok\":true,\"team\":[]}"
                url.contains("view=") -> 200 to "{\"ok\":true,\"items\":[]}"
                url.contains("activity=1") -> 200 to "{\"ok\":true,\"activity\":[]}"
                url.contains("pin-auth/me") -> 200 to "{\"ok\":true,\"available\":false}"
                method == "GET" -> 200 to snapshot().toString()
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
    private fun sheetShot(name: String) {
        rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); val c = Canvas(b); v.draw(c)
        val d = org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window?.decorView
        if (d != null) { c.save(); c.translate(0f, (v.height - d.height).toFloat()); d.draw(c); c.restore() }
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun waitFor(text: String) = rule.waitUntil(5000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    private fun openCommunity() {
        rule.onNodeWithTag("nav_office").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("o_sms").performScrollTo().performClick(); rule.waitForIdle()
    }
    @After fun reset() { Lang.sw = false; Look.mode = "" }

    @Test fun result_labels() {
        Lang.sw = false
        assertEquals("Not working" to "bad", smsResult("reported-broken"))
        assertEquals("Working again" to "ok", smsResult("fixed"))
        assertEquals("off", smsResult("ignored-unregistered").second)
        assertEquals("off", smsResult("something-new").second)
        assertEquals("broken", smsBucket("already-open")); assertEquals("working", smsBucket("noted-working")); assertEquals("ignored", smsBucket("ignored-no-project")); assertEquals("other", smsBucket("help"))
        assertEquals("+254 712 ••• 678", smsSender("+254712345678"))
        assertEquals("WhatsApp user ••• 436", smsSender("wa:KE.1461161065410436"))
        assertTrue(smsDialable("+254712345678")); assertTrue(!smsDialable("wa:KE.1461161065410436"))
        assertTrue(smsExplain("ignored-no-project").contains("no project with that number"))
        Lang.sw = true
        assertEquals("Haifanyi kazi", smsResult("reported-broken").first)
    }

    @Test fun community_reports_in_the_office() {
        Lang.sw = false
        show(model(true)); openCommunity()
        waitFor("IMEHARIBIKA 104 pampu")
        assertTrue(calls.any { it.startsWith("GET ") && it.endsWith("/api/sms-admin") })
        rule.onNodeWithText("Community reports").assertExists()
        assertEquals(4, rule.onAllNodesWithTag("sms_msg").fetchSemanticsNodes().size)
        // the last 7 days: 3 messages, 1 not working, 1 ignored (the 10-day-old one doesn't count)
        rule.onNodeWithTag("sms_n_week").assertExists(); rule.onNodeWithText("Broken · 1").assertExists(); rule.onNodeWithText("Ignored · 2").assertExists()
        rule.onNodeWithText("Not working").assertExists()
        rule.onNodeWithText("Working again").assertExists()
        rule.onNodeWithText("Ignored: no such project").assertExists()
        rule.onNodeWithText("WhatsApp user ••• 436 · WhatsApp").assertExists()
        rule.onAllNodesWithText("Turbo water committee", substring = true).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        // settings start folded away
        assertEquals(0, rule.onAllNodesWithTag("sms_setup_body").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("✓ SMS_WEBHOOK_TOKEN").fetchSemanticsNodes().size)
        shot("60-community-reports")
        // filters
        rule.onNodeWithTag("sms_filter_broken").performScrollTo().performClick(); rule.waitForIdle()
        assertEquals(1, rule.onAllNodesWithTag("sms_msg").fetchSemanticsNodes().size)
        shot("60b-community-filter-broken")
        // the report from a known phone number: open, call, WhatsApp, add as committee phone
        rule.onNodeWithTag("sms_msg").performScrollTo().performClick(); rule.waitForIdle()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("sms_sheet").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("sms_explain").assertExists(); rule.onNodeWithTag("sms_reply").assertExists()
        rule.onNodeWithTag("sms_open").assertExists(); rule.onNodeWithTag("sms_call").assertExists(); rule.onNodeWithTag("sms_wa").assertExists()
        sheetShot("60c-community-sheet")
        rule.onNodeWithTag("sms_add").performScrollTo().performClick(); rule.waitForIdle()
        rule.waitUntil(5000) { calls.any { it.startsWith("POST ") && it.endsWith("/api/sms-admin") } }
        waitFor("Added ✓")
        rule.onNodeWithTag("sms_close").performClick(); rule.mainClock.advanceTimeBy(800); rule.waitForIdle(); rule.waitUntil(5000) { rule.onAllNodesWithTag("sms_sheet").fetchSemanticsNodes().isEmpty() }
        // the hidden WhatsApp number: no call buttons, an explanation instead
        rule.onNodeWithTag("sms_filter_ignored").performScrollTo().performClick(); rule.waitForIdle()
        rule.onAllNodesWithTag("sms_msg").onFirst().performScrollTo().performClick(); rule.waitForIdle()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("sms_hidden").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, rule.onAllNodesWithTag("sms_call").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithTag("sms_open").fetchSemanticsNodes().size)
        sheetShot("60d-community-sheet-hidden-number")
        rule.onNodeWithTag("sms_close").performClick(); rule.mainClock.advanceTimeBy(800); rule.waitForIdle(); rule.waitUntil(5000) { rule.onAllNodesWithTag("sms_sheet").fetchSemanticsNodes().isEmpty() }
        // settings open with a tap
        rule.onNodeWithTag("sms_setup_toggle").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(600); rule.waitForIdle()
        rule.onNodeWithTag("sms_setup_body").assertExists()
        // the setting names sit under "Technical details"
        assertEquals(0, rule.onAllNodesWithText("✓ SMS_WEBHOOK_TOKEN").fetchSemanticsNodes().size)
        rule.onNodeWithTag("sms_vars_line").assertExists()
        rule.onNodeWithTag("sms_tech_toggle").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithText("✓ SMS_WEBHOOK_TOKEN").assertExists(); rule.onNodeWithText("TWILIO_AUTH_TOKEN").assertExists()
        rule.onNodeWithText("?token=YOUR_SMS_WEBHOOK_TOKEN", substring = true).assertExists()
        shot("61-community-setup")
        rule.onNodeWithTag("sms_phones_toggle").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(600); rule.waitForIdle()
        rule.onNodeWithText("+255712000111").performScrollTo().assertExists()
        shot("61b-community-phones")
        rule.onNodeWithTag("sms_voice_toggle").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(600); rule.waitForIdle()
        rule.onNodeWithText("Voice notes typed out").assertExists()
        rule.onNodeWithTag("sms_voice_body").performScrollTo()
        shot("62-community-phones-voice")
    }

    @Test fun community_not_set_up_dark_kiswahili() {
        Lang.sw = true; Look.mode = "dark"
        show(model(false), dark = true); openCommunity()
        waitFor("Ripoti za jamii")
        rule.onNodeWithText("Hakuna ujumbe bado", substring = true).assertExists()
        rule.onNodeWithTag("sms_voice_toggle").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(600); rule.waitForIdle()
        // plain words first; the setting's name only under "Technical details"
        rule.onAllNodesWithText("OPENAI_API_KEY", substring = true).assertCountEquals(0)
        rule.onNodeWithText("Imezimwa. Anayesimamia tovuti anaweza kuiwasha.").assertExists()
        rule.onNodeWithTag("voice_tech_toggle").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithText("OPENAI_API_KEY", substring = true).assertExists()
        assertEquals(0, rule.onAllNodesWithText("✓ SMS_WEBHOOK_TOKEN").fetchSemanticsNodes().size)
        shot("63-community-off-dark-sw")
    }
}

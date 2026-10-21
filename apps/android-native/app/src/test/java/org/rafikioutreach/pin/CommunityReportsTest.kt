package org.rafikioutreach.pin

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import org.rafikioutreach.pin.ui.smsResult
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

    private fun sms(on: Boolean) = JSONObject().put("ok", true)
        .put("setup", JSONObject().put("storage", true).put("monday", true)
            .put("at", JSONObject().put("on", on).put("replies", on).put("vars", JSONObject().put("SMS_WEBHOOK_TOKEN", on).put("AT_USERNAME", on).put("AT_API_KEY", on).put("AT_SENDER_ID", false)))
            .put("twilio", JSONObject().put("on", false).put("vars", JSONObject().put("TWILIO_AUTH_TOKEN", false))))
        .put("voice", JSONObject().put("on", on).put("provider", "openai").put("missing", if (on) JSONArray() else JSONArray().put("OPENAI_API_KEY")))
        .put("urls", JSONObject().put("at", "https://rafiki.example/api/sms?token=YOUR_SMS_WEBHOOK_TOKEN").put("twilio", "https://rafiki.example/api/sms"))
        .put("reports", if (!on) JSONArray() else JSONArray()
            .put(JSONObject().put("at", "2026-10-21T07:58:00Z").put("ch", "sms").put("from", "+254712345678").put("text", "IMEHARIBIKA 104 pampu").put("id", "104").put("t", "Kapsaret Borehole").put("result", "reported-broken")
                .put("reply", "RAFIKI: Asante. Ripoti ya Kapsaret Borehole (haifanyi kazi) imepokelewa."))
            .put(JSONObject().put("at", "2026-10-20T15:10:00Z").put("ch", "whatsapp").put("from", "+255712000111").put("name", "Turbo water committee").put("text", "FIXED").put("id", "105").put("t", "Turbo Spring").put("result", "fixed"))
            .put(JSONObject().put("at", "2026-10-20T09:00:00Z").put("ch", "sms").put("from", "+254700000001").put("text", "win a prize now").put("id", "").put("t", "").put("result", "ignored-unregistered")))
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
        Lang.sw = true
        assertEquals("Haifanyi kazi", smsResult("reported-broken").first)
    }

    @Test fun community_reports_in_the_office() {
        Lang.sw = false
        show(model(true)); openCommunity()
        waitFor("IMEHARIBIKA 104 pampu")
        assertTrue(calls.any { it.startsWith("GET ") && it.endsWith("/api/sms-admin") })
        rule.onNodeWithText("Community reports").assertExists()
        assertEquals(3, rule.onAllNodesWithTag("sms_msg").fetchSemanticsNodes().size)
        rule.onNodeWithText("Not working").assertExists()
        rule.onNodeWithText("Working again").assertExists()
        rule.onNodeWithText("Ignored: phone not on the list").assertExists()
        rule.onAllNodesWithText("Turbo water committee", substring = true).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        shot("60-community-reports")
        rule.onNodeWithTag("sms_setup").performScrollTo()
        rule.onNodeWithText("✓ SMS_WEBHOOK_TOKEN").assertExists(); rule.onNodeWithText("TWILIO_AUTH_TOKEN").assertExists()
        rule.onNodeWithText("https://rafiki.example/api/sms?token=YOUR_SMS_WEBHOOK_TOKEN").assertExists()
        shot("61-community-setup")
        rule.onNodeWithTag("sms_voice").performScrollTo()
        rule.onNodeWithText("Voice notes typed out").assertExists()
        rule.onNodeWithText("+255712000111").assertExists()
        shot("62-community-phones-voice")
    }

    @Test fun community_not_set_up_dark_kiswahili() {
        Lang.sw = true; Look.mode = "dark"
        show(model(false), dark = true); openCommunity()
        waitFor("Ripoti za jamii")
        rule.onNodeWithText("Hakuna ujumbe bado", substring = true).assertExists()
        rule.onNodeWithTag("sms_voice").performScrollTo()
        rule.onNodeWithText("OPENAI_API_KEY", substring = true).assertExists()
        assertEquals(0, rule.onAllNodesWithText("✓ SMS_WEBHOOK_TOKEN").fetchSemanticsNodes().size)
        shot("63-community-off-dark-sw")
    }
}

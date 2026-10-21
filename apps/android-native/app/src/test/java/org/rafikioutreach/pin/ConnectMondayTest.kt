package org.rafikioutreach.pin

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.ui.Onb
import org.rafikioutreach.pin.ui.RafikiTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Me › Your name on monday.com: copy the token, paste and connect, connected as … ✓ */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class ConnectMondayTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val tok = "eyJhbGciOiJIUzI1NiJ9." + "a".repeat(120) + ".sig"

    @Test fun paste_and_connect() {
        org.rafikioutreach.pin.ui.Motion.on = false
        val app = ApplicationProvider.getApplicationContext<Application>()
        val snap = JSONObject().put("ok", true).put("who", "Meril Kiprop").put("admin", false).put("linked", "").put("projects", JSONArray()).put("repairs", JSONObject()).put("visits", JSONObject())
        Prefs(app).apply { pin = "1111"; name = "Meril Kiprop"; saveSnapshot(snap); onb = true; lang = "en" }
        Lang.sw = false
        val m = AppModel(app); Onb.enabled = false
        val sent = mutableListOf<String>()
        m.api.transport = { method, url, _, body ->
            when {
                method == "POST" -> { val b = JSONObject(String(body!!)); sent.add(b.optString("action") + ":" + b.optString("token").take(6))
                    if (b.optString("action") == "monday-link") (if (b.optString("token") == tok) 200 to "{\"ok\":true,\"name\":\"Meril Adoyo\"}" else 400 to "{\"ok\":false,\"reason\":\"token-bad\"}") else 200 to "{\"ok\":true}" }
                url.contains("activity=1") -> 200 to "{\"ok\":true,\"activity\":[]}"
                url.contains("team=1") -> 200 to "{\"ok\":true,\"team\":[]}"
                url.contains("pin-auth/me") -> 200 to "{\"ok\":true,\"available\":true}"
                else -> 200 to snap.toString()
            }
        }
        rule.setContent { RafikiTheme(dark = false) { Box(Modifier.size(390.dp, 844.dp)) { App(m) } } }
        rule.waitUntil(8000) { rule.onAllNodesWithTag("hav").fetchSemanticsNodes().isNotEmpty() }
        rule.mainClock.advanceTimeBy(2000)
        rule.onNodeWithTag("hav").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("monday_paste").fetchSemanticsNodes().isNotEmpty() }
        val clip = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("t", "hello"))
        rule.onNodeWithTag("monday_paste").performScrollTo().performClick()
        rule.waitUntil(3000) { rule.onAllNodesWithTag("monday_msg").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("monday_msg").assertTextContains("doesn’t look like", substring = true)
        clip.setPrimaryClip(ClipData.newPlainText("t", tok))
        rule.onNodeWithTag("monday_paste").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("monday_connected", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("monday_connected", useUnmergedTree = true).assertTextEquals("Connected as Meril Adoyo ✓")
        assertTrue(sent.contains("monday-link:eyJhbG"))
        assertEquals("", clip.primaryClip?.getItemAt(0)?.text?.toString().orEmpty())
        rule.onNodeWithTag("monday_off").performScrollTo().performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("monday_paste").fetchSemanticsNodes().isNotEmpty() }
        org.rafikioutreach.pin.ui.Motion.on = true
    }

    @Test fun lock_screen_sign_in_with_token() {
        org.rafikioutreach.pin.ui.Motion.on = false
        val app = ApplicationProvider.getApplicationContext<Application>()
        val snap = JSONObject().put("ok", true).put("who", "Meril Adoyo").put("monday", true).put("admin", false).put("projects", JSONArray()).put("repairs", JSONObject()).put("visits", JSONObject())
        Prefs(app).apply { pin = ""; session = ""; name = ""; onb = true; lang = "en" }
        Lang.sw = false
        val m = AppModel(app); Onb.enabled = false
        var origin = ""
        m.api.transport = { method, url, h, body ->
            when {
                url.contains("pin-auth/me") -> 200 to "{\"ok\":true,\"available\":true}"
                url.contains("pin-auth/token") -> { origin = h["Origin"].orEmpty(); if (JSONObject(String(body!!)).optString("token") == tok) 200 to "{\"ok\":true,\"name\":\"Meril Adoyo\",\"sid\":\"SID123456789012345678901234\"}" else 400 to "{\"ok\":false,\"reason\":\"token-bad\"}" }
                h["Cookie"]?.contains("SID123") == true -> 200 to snap.toString()
                else -> 401 to "{\"ok\":false,\"reason\":\"signin\"}"
            }
        }
        rule.setContent { RafikiTheme(dark = false) { Box(Modifier.size(390.dp, 844.dp)) { App(m) } } }
        rule.waitUntil(8000) { rule.onAllNodesWithTag("monday").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("monday").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("ms_paste").fetchSemanticsNodes().isNotEmpty() }
        val clip = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("t", tok))
        rule.onNodeWithTag("ms_paste").performClick()
        rule.waitUntil(8000) { rule.onAllNodesWithTag("hav").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("SID123456789012345678901234", m.prefs.session)
        assertTrue(origin.startsWith("https://"))
        assertEquals("Meril Adoyo", m.prefs.name)
        org.rafikioutreach.pin.ui.Motion.on = true
    }
}

package org.rafikioutreach.pin

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.ui.Bio
import org.rafikioutreach.pin.ui.Onb
import org.rafikioutreach.pin.ui.RafikiTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Fingerprint / face lock: turn it on in Me, lock, unlock with the fingerprint or the PIN. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class BioTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun shot(name: String) {
        rule.mainClock.advanceTimeBy(700); rule.waitForIdle()
        val v = rule.activity.window.decorView
        val b = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888); v.draw(android.graphics.Canvas(b))
        java.io.File(java.io.File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }, "$name.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun fingerprint_lock() {
        org.rafikioutreach.pin.ui.Motion.on = false
        val app = ApplicationProvider.getApplicationContext<Application>()
        val snap = JSONObject().put("ok", true).put("who", "Meril Kiprop").put("admin", false).put("projects", JSONArray()).put("repairs", JSONObject()).put("visits", JSONObject())
        Prefs(app).apply { pin = "1111"; name = "Meril Kiprop"; saveSnapshot(snap); onb = true; lang = "en"; bio = false }
        Lang.sw = false
        var answer = true; var asked = 0
        Bio.available = { true }; Bio.ask = { _, _, done -> asked++; done(answer) }
        val m = AppModel(app); Onb.enabled = false
        m.api.transport = { _, url, _, _ -> when { url.contains("activity=1") -> 200 to "{\"ok\":true,\"activity\":[]}"; url.contains("team=1") -> 200 to "{\"ok\":true,\"team\":[]}"; else -> 200 to snap.toString() } }
        rule.setContent { RafikiTheme(dark = false) { Box(Modifier.size(390.dp, 844.dp)) { App(m) } } }
        rule.waitUntil(8000) { rule.onAllNodesWithTag("hav").fetchSemanticsNodes().isNotEmpty() }
        rule.mainClock.advanceTimeBy(2000)
        rule.onNodeWithTag("hav").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("bio_switch").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("bio_switch").performScrollTo().performClick(); rule.waitForIdle()
        assertTrue(m.prefs.bio); assertEquals(1, asked)
        rule.onNodeWithTag("bio_card").performScrollTo(); shot("82-bio-card")
        // Lock the app: only locks, the PIN stays
        rule.onNodeWithText("Lock the app").performScrollTo().performClick(); rule.waitForIdle()
        assertEquals("1111", m.prefs.pin)
        // the lock screen asks straight away; a failed try stays locked
        answer = false
        rule.runOnUiThread { m.lockOrSignOut() }
        rule.waitUntil(3000) { rule.onAllNodesWithTag("bio_lock").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(m.bioLocked.value)
        answer = true
        rule.onNodeWithTag("bio_unlock").performClick(); rule.waitForIdle()
        assertFalse(m.bioLocked.value)
        // PIN instead of the fingerprint
        answer = false; rule.runOnUiThread { m.lockOrSignOut() }
        rule.waitUntil(3000) { rule.onAllNodesWithTag("bio_usepin").fetchSemanticsNodes().isNotEmpty() }
        shot("80-bio-lock")
        rule.onNodeWithTag("bio_usepin").performClick(); rule.waitForIdle()
        listOf("1", "1", "1", "2").forEach { rule.onNodeWithTag("bio_key$it").performClick() }
        rule.onNodeWithTag("bio_msg").assertTextContains("isn’t right", substring = true)
        shot("81-bio-pin")
        repeat(4) { rule.onNodeWithTag("bio_key1").performClick() }; rule.waitForIdle()
        assertFalse(m.bioLocked.value)
        // a minute away locks it again
        rule.runOnUiThread { m.wentAway() }
        Thread.sleep(5); rule.runOnUiThread { m.cameBack() }; assertFalse(m.bioLocked.value)
        // sign out from the lock screen forgets everything
        rule.runOnUiThread { m.lockOrSignOut() }
        rule.waitUntil(3000) { rule.onAllNodesWithTag("bio_signout").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("bio_signout").performClick(); rule.waitForIdle()
        assertEquals("", m.prefs.pin); assertFalse(m.prefs.bio)
        org.rafikioutreach.pin.ui.Motion.on = true
    }
}

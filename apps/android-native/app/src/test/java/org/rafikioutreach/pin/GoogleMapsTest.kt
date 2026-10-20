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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.ui.GoogleTiles
import org.rafikioutreach.pin.ui.Onb
import org.rafikioutreach.pin.ui.RafikiTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Google maps: sessions with the app's own headers, Google tiles, logo and copyright on the map. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class GoogleMapsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun google_map_with_logo_and_copyright() {
        org.rafikioutreach.pin.ui.Motion.on = false
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("gtiles", 0).edit().clear().commit()
        val calls = mutableListOf<String>()
        GoogleTiles.transport = { url, body, h ->
            calls.add(url.substringBefore("?") + " " + (body?.let { JSONObject(it).optString("mapType") } ?: "") + " pkg=" + h["X-Android-Package"])
            when {
                url.contains("createSession") -> 200 to JSONObject().put("session", "S-" + JSONObject(body!!).optString("mapType")).put("expiry", "9999999999").toString()
                url.contains("viewport") -> 200 to "{\"copyright\":\"Map data ©2026 Google\"}"
                else -> 404 to ""
            }
        }
        val proj = JSONObject().put("id", "101").put("title", "Chepkongi Dispensary").put("country", "Kenya").put("ck", "ke").put("area", "Eldoret").put("type", "borehole").put("status", "Completed").put("gps", JSONArray().put(0.51).put(35.27))
        val snap = JSONObject().put("ok", true).put("who", "Meril Kiprop").put("admin", false).put("gmaps", JSONObject().put("web", "W").put("android", "AKEY"))
            .put("projects", JSONArray().put(proj).put(JSONObject(proj.toString()).put("id", "102").put("gps", JSONArray().put(0.6).put(35.1)))).put("repairs", JSONObject()).put("visits", JSONObject())
        Prefs(app).apply { pin = "1111"; name = "Meril Kiprop"; saveSnapshot(snap); onb = true; lang = "en"; filter = "all" }
        Lang.sw = false
        val m = AppModel(app); Onb.enabled = false
        m.api.transport = { _, url, _, _ -> if (url.contains("pin-auth/me")) 200 to "{\"ok\":true,\"available\":false}" else 200 to snap.toString() }
        rule.waitUntil(5000) { GoogleTiles.maps.value != null }
        assertTrue(calls.any { it.contains("createSession roadmap pkg=org.rafikioutreach.pin") })
        assertTrue(calls.any { it.contains("createSession satellite") })
        rule.setContent { RafikiTheme(dark = false) { Box(Modifier.size(390.dp, 844.dp)) { App(m) } } }
        rule.waitUntil(8000) { rule.onAllNodesWithTag("nav_map").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav_map").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithContentDescription("Google").fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(5000) { rule.onAllNodesWithText("Map data ©2026 Google").fetchSemanticsNodes().isNotEmpty() }
        val mv = findMap(rule.activity.window.decorView)
        assertEquals("Google Roadmap", mv?.tileProvider?.tileSource?.name())
        rule.mainClock.advanceTimeBy(700); rule.waitForIdle()
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        File(File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }, "70-google-map.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        GoogleTiles.configure(app, "")
        org.rafikioutreach.pin.ui.Motion.on = true
    }

    private fun findMap(v: android.view.View): org.osmdroid.views.MapView? {
        if (v is org.osmdroid.views.MapView) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findMap(v.getChildAt(i))?.let { return it }
        return null
    }
}

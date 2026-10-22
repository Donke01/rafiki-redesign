package org.rafikioutreach.pin

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.printToString
import android.graphics.Canvas
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.ui.RafikiTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Draws each screen on the JVM and saves a picture of it (build/shots), in English and Kiswahili. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class ScreensTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val posts = mutableListOf<String>()

    private fun snapshot() = JSONObject().put("ok", true).put("who", "Meril Kiprop").put("admin", false)
        .put("projects", JSONArray()
            .put(proj("101", "Chepkongi Dispensary", "Eldoret", "health", JSONArray().put(0.5143).put(35.2698)))
            .put(proj("102", "Kiptoo Well", "Nandi", "borehole", null))
            .put(proj("104", "Kapsaret Borehole", "Eldoret", "borehole", JSONArray().put(0.47).put(35.25)))
            .put(proj("105", "Turbo Spring", "Turbo", "collection", JSONArray().put(0.63).put(35.05))))
        .put("repairs", JSONObject().put("104", JSONObject().put("state", "repair").put("by", "Bertin").put("at", "2026-10-18T08:00:00Z").put("note", "Handle loose").put("urgency", "month")))
        .put("visits", JSONObject()).put("jobs", JSONArray()).put("assigned", JSONArray()).put("confirmed", JSONObject())

    private fun proj(id: String, t: String, area: String, type: String, gps: JSONArray?) = JSONObject().put("id", id).put("title", t).put("country", "Kenya").put("ck", "ke")
        .put("area", area).put("type", type).put("status", "Completed").put("pump", if (id == "104") "manual" else "").apply { if (gps != null) put("gps", gps) }

    private fun model(signedIn: Boolean): AppModel {
        org.rafikioutreach.pin.ui.Onb.enabled = false
        val app = ApplicationProvider.getApplicationContext<Application>()
        val p = Prefs(app)
        if (signedIn) { p.pin = "1111"; p.name = "Meril Kiprop"; p.saveSnapshot(snapshot()) } else { p.pin = ""; p.name = "" }
        p.filter = "all"
        org.rafikioutreach.pin.ui.clearDrafts()
        val m = AppModel(app)
        m.api.transport = { method, url, _, body ->
            posts += "$method $url ${body?.let { String(it).take(4000) } ?: ""}"
            when {
                url.contains("history=") -> 200 to JSONObject().put("ok", true).put("notes", JSONArray().put(JSONObject().put("id", "1").put("text", "Site visit by Bertin (RAFIKI pin app): Needs repair. Notes: Handle loose.").put("at", "2026-10-18T09:00:00Z").put("by", "Morgan"))).toString()
                method == "GET" -> 200 to snapshot().toString()
                else -> 200 to "{\"ok\":true,\"updateId\":\"9001\"}"
            }
        }
        return m
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun openRow(t: String, click: Boolean = true) {
        rule.waitForIdle()
        if (runCatching { rule.onNodeWithTag("projects").assertExists() }.isFailure) println(rule.onRoot().printToString())
        rule.onNodeWithTag("projects").performScrollToNode(hasText(t))
        if (click) rule.onNodeWithText(t).performClick() else rule.onNodeWithText(t).assertExists()
    }

    private fun show(m: AppModel) = rule.setContent { RafikiTheme(dark = false) { Box(Modifier.size(390.dp, 844.dp)) { App(m) } } }

    @Test fun sign_in_pad_english_and_kiswahili() {
        Lang.sw = false
        val m = model(false)
        show(m)
        rule.onNodeWithText("Enter your PIN").assertExists()
        rule.onNodeWithTag("key4").performClick(); rule.onNodeWithTag("key2").performClick()
        shot("01-sign-in")
        rule.onNodeWithText("Kiswahili").performScrollTo().performClick()
        rule.onNodeWithText("Weka PIN yako").assertExists()
        shot("02-sign-in-sw")
        Lang.sw = false
    }

    @Test fun four_digits_sign_in() {
        Lang.sw = false
        val m = model(false)
        show(m)
        listOf("1", "1", "1", "1").forEach { rule.onNodeWithTag("key$it").performClick() }
        rule.waitUntil(5000) { m.signedIn.value }
        rule.waitForIdle()
        assertTrue(posts.any { it.startsWith("GET https://") && it.contains("/api/pin") })
        rule.onAllNodesWithText("Projects").onFirst().assertExists()
    }

    @Test fun projects_project_page_and_visit() {
        Lang.sw = false
        val m = model(true)
        show(m)
        openRow("Kapsaret Borehole", click = false)
        shot("03-projects")
        openRow("Kapsaret Borehole")
        rule.onNodeWithTag("tracker").assertExists(); rule.onNodeWithText("Repair job").assertExists()
        shot("04-project")
        rule.onNodeWithTag("tab_visit").performScrollTo().performClick()
        rule.onNodeWithText("I’ve arrived").performScrollTo().performClick()
        rule.onAllNodesWithText("Needs repair").onFirst().assertExists()
        shot("05-visit-condition")
        rule.onNodeWithText("Not working").performScrollTo().performClick()
        rule.onNodeWithText("Urgent").performScrollTo().performClick()
        rule.onNodeWithText("Next").performScrollTo().performClick()
        rule.onNodeWithText("Pump handle and pivot pins").assertExists()
        shot("06-visit-checklist")
        rule.onNodeWithTag("tab_hist").performScrollTo().performClick()
        rule.waitUntil(5000) { runCatching { rule.onNodeWithText("Site visit by Bertin", substring = true).assertExists(); true }.getOrDefault(false) }
        shot("07-history")
    }

    @Test fun kiswahili_screens() {
        Lang.sw = true
        val m = model(true)
        show(m)
        rule.onAllNodesWithText("Miradi").onFirst().assertExists()
        shot("08-projects-sw")
        openRow("Kapsaret Borehole")
        rule.onNodeWithTag("tab_visit").performScrollTo().performClick()
        rule.onNodeWithText("Nimefika").performScrollTo().performClick()
        rule.onNodeWithText("Haifanyi kazi").assertExists()
        shot("09-visit-sw")
        rule.onNodeWithTag("nav_new").performClick()
        assertTrue(rule.onAllNodesWithText("Eneo jipya").fetchSemanticsNodes().size >= 2)
        shot("10-new-site-sw")
        rule.onNodeWithTag("nav_me").performClick()
        rule.onNodeWithText("Mimi na timu").assertExists()
        shot("11-me-sw")
        Lang.sw = false
    }

    @Test fun visit_is_sent_with_the_web_apps_fields() {
        Lang.sw = false
        val m = model(true)
        show(m)
        openRow("Kapsaret Borehole")
        rule.onNodeWithTag("tab_visit").performScrollTo().performClick()
        rule.onNodeWithText("I’ve arrived").performScrollTo().performClick()
        rule.onNodeWithText("Working well").performScrollTo().performClick()
        repeat(5) { rule.onNodeWithText("Next").performScrollTo().performClick() }
        rule.onNodeWithText("Send visit report").performScrollTo().performClick()
        rule.waitUntil(15000) { posts.any { it.contains("\"action\":\"visit\"") } }
        rule.waitUntil(15000) { runCatching { rule.onNodeWithText("Visit report sent").assertExists(); true }.getOrDefault(false) }
        val p = posts.first { it.contains("\"action\":\"visit\"") }
        assertTrue(p, p.contains("\"state\":\"working\"") && p.contains("\"id\":\"104\"") && p.contains("\"by\":\"Meril Kiprop\""))
        shot("12-visit-sent")
    }
}

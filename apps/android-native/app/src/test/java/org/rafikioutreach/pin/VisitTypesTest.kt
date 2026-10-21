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
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.performTextInput
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

/** Site visits for schools, clinics, children's homes, farms and building sites. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class VisitTypesTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val posts = mutableListOf<String>()

    private fun proj(id: String, t: String, type: String, status: String = "Completed") = JSONObject().put("id", id).put("title", t).put("country", "Kenya").put("ck", "ke")
        .put("area", "Nandi").put("type", type).put("status", status).put("pump", "").put("gps", JSONArray().put(0.2 + id.toInt() * 0.001).put(35.1))
    private fun snapshot() = JSONObject().put("ok", true).put("who", "Meril Kiprop").put("admin", false)
        .put("projects", JSONArray().put(proj("201", "Kaptebee Pigsty", "Farming")).put(proj("202", "Nzoia Bridge Primary School", "School", "Under construction"))
            .put(proj("203", "Tamiha Orphanage", "Orphanage")).put(proj("204", "Kapsaret Borehole", "Borehole well")))
        .put("repairs", JSONObject()).put("visits", JSONObject()).put("jobs", JSONArray()).put("assigned", JSONArray()).put("confirmed", JSONObject())

    private fun model(): AppModel {
        org.rafikioutreach.pin.ui.Onb.enabled = false
        val app = ApplicationProvider.getApplicationContext<Application>()
        Prefs(app).apply { pin = "1111"; name = "Meril Kiprop"; saveSnapshot(snapshot()); filter = "all" }
        org.rafikioutreach.pin.ui.clearDrafts()
        val m = AppModel(app)
        m.api.transport = { method, url, _, body ->
            posts += "$method $url ${body?.let { String(it).take(4000) } ?: ""}"
            when { url.contains("history=") -> 200 to "{\"ok\":true,\"notes\":[]}"; method == "GET" -> 200 to snapshot().toString(); else -> 200 to "{\"ok\":true,\"updateId\":\"9001\"}" }
        }
        return m
    }
    private fun shot(name: String) {
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun open(t: String) {
        rule.waitForIdle()
        rule.onNodeWithTag("projects").performScrollToNode(hasText(t)); rule.onNodeWithText(t).performClick()
        rule.onNodeWithTag("tab_visit").performScrollTo().performClick()
        rule.onNodeWithText("I’ve arrived").performScrollTo().performClick()
    }
    private fun type(tag: String, text: String) = rule.onNode(hasSetTextAction() and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag)))).performScrollTo().performTextInput(text)
    private fun next() = rule.onNodeWithText("Next").performScrollTo().performClick()
    private fun has(t: String) = rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun farm_visit() {
        Lang.sw = false
        val m = model(); rule.setContent { RafikiTheme(dark = false) { Box(Modifier.size(390.dp, 844.dp)) { App(m) } } }
        open("Kaptebee Pigsty")
        rule.onNodeWithText("Working well").performScrollTo().performClick(); next()
        assertTrue(has("Pen or coop") && has("Feed and water for the animals") && !has("Pump")); shot("70-farm-checklist")
        next()
        assertTrue(has("Animals now") && !has("E. coli"))
        type("num_animals", "18"); type("num_born", "6")
        shot("71-farm-numbers")
        next(); assertTrue(has("The animals") && !has("Water flowing")); shot("72-farm-photos")
        next(); assertTrue(has("Who looks after it") && has("Farmer") && !has("Water committee"))
        type("carer_name", "Julius"); shot("73-farm-carer")
        next(); rule.onNodeWithText("Send visit report").performScrollTo().performClick()
        rule.waitUntil(5000) { posts.any { it.contains("\"action\":\"visit\"") } }
        val p = posts.first { it.contains("\"action\":\"visit\"") }
        assertTrue(p, p.contains("\"kind\":\"farm\"") && p.contains("\"animals\":18") && p.contains("\"born\":6") && p.contains("\"name\":\"Julius\""))
    }

    @Test fun building_site_visit() {
        Lang.sw = false
        val m = model(); rule.setContent { RafikiTheme(dark = false) { Box(Modifier.size(390.dp, 844.dp)) { App(m) } } }
        rule.onNodeWithTag("projects").performScrollToNode(hasTestTag("type_chips")); shot("78-type-chips")
        rule.onNodeWithTag("type_school").performClick(); rule.waitForIdle()
        assertTrue(!has("Kaptebee Pigsty") && has("Which project"))
        rule.onNodeWithTag("type_").performClick(); rule.waitForIdle()
        open("Nzoia Bridge Primary School")
        assertTrue(has("How is the building going") && has("On track") && has("Work stopped"))
        rule.onNodeWithText("Problems to fix").performScrollTo().performClick(); shot("74-build-state"); next()
        assertTrue(has("Stage the building has reached") && has("Materials on site"))
        rule.onNodeWithTag("stage_roof").performScrollTo().performClick(); shot("75-build-checklist")
        next(); type("num_workers", "9")
        next(); assertTrue(has("The whole building now"))
        next(); assertTrue(has("Fundi or site foreman"))
        next(); assertTrue(has("Roof")); shot("76-build-review")
        rule.onNodeWithText("Send visit report").performScrollTo().performClick()
        rule.waitUntil(5000) { posts.any { it.contains("\"action\":\"visit\"") } }
        val p = posts.first { it.contains("\"action\":\"visit\"") }
        assertTrue(p, p.contains("\"kind\":\"build\"") && p.contains("\"stage\":\"roof\"") && p.contains("\"workers\":9"))
    }

    @Test fun back_never_quits_and_bell_top_right() {
        Lang.sw = false
        val m = model(); rule.setContent { RafikiTheme(dark = false) { Box(Modifier.size(390.dp, 844.dp)) { App(m) } } }
        rule.waitForIdle()
        rule.onNodeWithTag("bell").assertExists()
        // from another tab, back goes to Projects; on Projects, back twice only sends the app to the background
        rule.onNodeWithTag("hav").performClick(); rule.waitForIdle()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle()
        rule.onNodeWithTag("projects").assertExists()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle()
        org.junit.Assert.assertFalse(rule.activity.isFinishing)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle()
        org.junit.Assert.assertFalse(rule.activity.isFinishing)
        // the bell opens Notifications, and back closes it again
        rule.onNodeWithTag("bell").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("hback").assertExists()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle()
        rule.onNodeWithTag("projects").assertExists()
        shot("79-header-bell")
    }

    @Test fun childrens_home_and_water_unchanged() {
        Lang.sw = false
        val m = model(); rule.setContent { RafikiTheme(dark = false) { Box(Modifier.size(390.dp, 844.dp)) { App(m) } } }
        open("Tamiha Orphanage")
        rule.onNodeWithText("Working well").performScrollTo().performClick(); next()
        assertTrue(has("Beds, mattresses and bedding")); next(); next()
        rule.onNodeWithTag("children_note").assertExists(); shot("77-home-photos")
        rule.onNodeWithTag("hback").performClick(); rule.waitForIdle()
        open("Kapsaret Borehole")
        rule.onNodeWithText("Working well").performScrollTo().performClick(); next()
        assertTrue(has("Pump works")); next(); assertTrue(has("Pump strokes"))
    }
}

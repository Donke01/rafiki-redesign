package org.rafikioutreach.pin

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.data.Project
import org.rafikioutreach.pin.ui.RafikiTheme
import org.rafikioutreach.pin.ui.donorCat
import org.rafikioutreach.pin.ui.donorSensitive
import org.rafikioutreach.pin.ui.donorShareText
import org.rafikioutreach.pin.ui.parseDonors
import org.rafikioutreach.pin.ui.pickProjects
import org.rafikioutreach.pin.ui.thanksCheck
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Office › Donors (private donor pages) and Project › Thank-you for donors in the native app. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class DonorsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val posts = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val link = "https://www.rafikioutreach.org/donor/Q7mF2kLx9PzR4tYc8VbN1sWq6HjD3gUe5AoKi0ZlM_x"

    private fun proj(id: String, t: String, type: String, ck: String = "ke", area: String = "Eldoret") = JSONObject().put("id", id).put("title", t).put("country", if (ck == "tz") "Tanzania" else "Kenya").put("ck", ck)
        .put("area", area).put("type", type).put("status", "Completed").put("pump", "").put("gps", JSONArray().put(0.47).put(35.25))
    private fun snapshot() = JSONObject().put("ok", true).put("who", "Reid Anderson").put("admin", true).put("reviewOpen", 0)
        .put("projects", JSONArray().put(proj("101", "Kapsaret Borehole", "Borehole well", area = "Kapsaret")).put(proj("102", "Ndalat Primary School", "School", area = "Ndalat"))
            .put(proj("104", "Magara Water Tank", "Water collection", "tz", "Magara")).put(proj("105", "Kipkaren Children’s Home", "Orphanage", area = "Kipkaren")))
        .put("repairs", JSONObject()).put("visits", JSONObject()).put("jobs", JSONArray()).put("assigned", JSONArray()).put("confirmed", JSONObject())
    private val donors = JSONArray().put(JSONObject().put("id", "dA1").put("name", "Anderson Family").put("email", "anderson@example.org").put("org", "").put("projects", JSONArray().put("101"))
        .put("showPhotos", true).put("photos", JSONObject()).put("digest", true).put("lastOpen", "2026-10-19T18:20:00Z").put("opens", 4).put("revoked", false)
        .put("viewers", JSONArray().put(JSONObject().put("vid", "vA").put("name", "Anderson Family").put("email", "anderson@example.org").put("primary", true).put("lastOpen", "2026-10-19T18:20:00Z").put("active", true))))

    private fun model(): AppModel {
        org.rafikioutreach.pin.ui.Onb.enabled = false
        val app = ApplicationProvider.getApplicationContext<Application>()
        Prefs(app).apply { pin = "9999"; name = "Reid Anderson"; saveSnapshot(snapshot()); filter = "all" }
        val m = AppModel(app)
        m.api.transport = { method, url, _, body ->
            val b = body?.let { String(it) } ?: ""
            posts += "$method $url $b"
            when {
                url.contains("view=donors") -> 200 to JSONObject().put("ok", true).put("donors", donors).put("mail", JSONObject().put("ready", false)).put("cron", true).toString()
                b.contains("\"donor-add\"") -> {
                    val j = JSONObject(b)
                    donors.put(JSONObject().put("id", "dG1").put("name", j.getString("name")).put("email", j.getString("email")).put("org", j.getString("org")).put("projects", j.getJSONArray("projects"))
                        .put("showPhotos", true).put("photos", j.getJSONObject("photos")).put("digest", true).put("opens", 0).put("revoked", false)
                        .put("viewers", JSONArray().put(JSONObject().put("vid", "vG").put("name", j.getString("name")).put("primary", true).put("active", true))))
                    200 to JSONObject().put("ok", true).put("link", link).toString()
                }
                b.contains("\"donor-link\"") -> 200 to JSONObject().put("ok", true).put("link", "https://www.rafikioutreach.org/donor/AndersonLinkAndersonLinkAndersonLinkAnderso").toString()
                url.contains("pin-thanks") -> 200 to "{\"ok\":true,\"asset\":\"700\"}"
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
    /** A sheet lives in its own window: draw it over the screen, the way it looks on the phone. */
    private fun sheetShot(name: String) {
        rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); val c = Canvas(b); v.draw(c); c.drawColor(0x66000000)
        val d = org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window?.decorView
        if (d != null && d.width > 0) { c.save(); c.translate(0f, (v.height - d.height).toFloat().coerceAtLeast(0f)); d.draw(c); c.restore() }
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun waitFor(text: String) = rule.waitUntil(5000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    private fun clip() = (ApplicationProvider.getApplicationContext<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()

    @Test fun picker_logic_and_texts() {
        val ps = listOf(Project("1", "Kapsaret Borehole", "Kenya", "ke", "Kapsaret", "Borehole well", "Completed", 2025, null, null, ""),
            Project("2", "Ndalat Primary School", "Kenya", "ke", "Ndalat", "School", "Completed", 2024, null, null, ""),
            Project("3", "Magara Water Tank", "Tanzania", "tz", "Magara", "Water collection", "Completed", 2026, null, null, ""))
        assertEquals(listOf("water", "school", "health", "children", "other"), listOf("Borehole well", "School", "Health", "Orphanage", "Farming").map { donorCat(it) })
        assertTrue(donorSensitive("School") && donorSensitive("Orphanage") && !donorSensitive("Borehole well"))
        assertEquals(listOf("1"), pickProjects(ps, "kap", "", "", emptySet(), false).map { it.id })
        assertEquals(listOf("3"), pickProjects(ps, "", "tz", "", emptySet(), false).map { it.id })
        assertEquals(listOf("2"), pickProjects(ps, "", "", "school", emptySet(), false).map { it.id })
        assertEquals(listOf("3", "1", "2"), pickProjects(ps, "", "", "", setOf("3"), false).map { it.id })
        assertEquals(listOf("3"), pickProjects(ps, "", "", "", setOf("3"), true).map { it.id })
        assertTrue(donorShareText("Grace Church", link).startsWith("Hello Grace,") && donorShareText("Grace Church", link).endsWith(link))
        val d = parseDonors(JSONObject().put("donors", donors))[0]
        assertEquals("Anderson Family", d.name); assertEquals(listOf("101"), d.projects); assertTrue(d.viewers[0].primary)
        assertNull(thanksCheck("video/mp4", 30L * 1024 * 1024, 45)); assertEquals("too-long", thanksCheck("video/mp4", 1000, 75))
        assertEquals("too-large", thanksCheck("image/jpeg", 9L * 1024 * 1024, 0)); assertEquals("type", thanksCheck("application/pdf", 10, 0))
    }

    @Test fun office_donors_add_and_copy_link() {
        Lang.sw = false
        val m = model(); show(m)
        rule.onNodeWithTag("nav_office").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("o_donors").performScrollTo().performClick()
        waitFor("Anderson Family")
        rule.onNodeWithText("Opened their page").assertExists()
        rule.onNodeWithTag("dn_head").performScrollTo()
        shot("40-office-donors")
        // the e-mail digest is folded into a settings row
        rule.onAllNodesWithTag("dn_mail").assertCountEquals(0)
        rule.onNodeWithTag("dn_mail_fold_toggle").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
        rule.onNodeWithText("Off. Add RESEND_API_KEY and ALERT_FROM in Cloudflare to switch it on.").assertExists()
        rule.onNodeWithTag("dn_mail").performScrollTo(); shot("40b-office-donors-digest")
        // filters: nobody left unopened; the Donors number brings everyone back
        rule.onNodeWithTag("dn_filter_not").performScrollTo().performClick(); waitFor("Everyone has opened their page.")
        rule.onAllNodesWithTag("dn_dA1").assertCountEquals(0)
        rule.onNodeWithTag("n_all").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("dn_dA1").assertExists()
        // a donor's sheet: copy the link; revoke asks first
        rule.onNodeWithTag("dn_dA1").performScrollTo().performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("dn_sheet").fetchSemanticsNodes().isNotEmpty() }
        sheetShot("40c-office-donor-sheet")
        rule.onNodeWithTag("dn_copy").performScrollTo().performClick()
        rule.waitUntil(5000) { clip()?.contains("AndersonLink") == true }
        rule.onNodeWithTag("dn_revoke").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithText("Revoke the link for Anderson Family? Their page stops working straight away. You can make a new link later.").assertExists()
        rule.onNodeWithTag("dn_ask").performScrollTo()
        sheetShot("40d-office-donor-revoke-ask")
        assertTrue(posts.none { it.contains("\"donor-revoke\"") })
        rule.onNodeWithTag("dn_ask_no").performScrollTo().performClick(); rule.waitForIdle()
        rule.onAllNodesWithTag("dn_ask").assertCountEquals(0)
        rule.onNodeWithTag("dn_viewers_toggle").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
        rule.onNodeWithTag("dn_vname").performScrollTo()
        sheetShot("40e-office-donor-viewers")
        rule.onNodeWithTag("dn_close").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(1500)
        rule.waitUntil(5000) { rule.onAllNodesWithTag("dn_sheet").fetchSemanticsNodes().isEmpty() }
        // add a donor: the form in its own sheet
        rule.onNodeWithTag("dn_add").performScrollTo().performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("dn_form").fetchSemanticsNodes().isNotEmpty() }
        sheetShot("41-office-donor-form")
        rule.onNodeWithText("e.g. Grace Community Church").performScrollTo().performTextInput("Grace Community Church")
        rule.onNodeWithText("Name or place").performScrollTo().performTextInput("kap")
        rule.onNodeWithTag("dn_p_101").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("dn_p_102").assertDoesNotExist()
        rule.onNodeWithTag("dn_p_101").performScrollTo()
        sheetShot("41b-office-donor-form-picked")
        rule.onNodeWithTag("dn_save").performScrollTo().performClick()
        rule.waitUntil(5000) { posts.any { it.contains("\"donor-add\"") } }
        val add = posts.first { it.contains("\"donor-add\"") }
        assertTrue(add, add.contains("\"projects\":[\"101\"]") && add.contains("Grace Community Church") && add.contains("\"showPhotos\":true"))
        // the new donor's sheet opens with the private link
        rule.mainClock.advanceTimeBy(1500)
        rule.waitUntil(8000) { rule.onAllNodesWithTag("dn_link").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Q7mF2kLx9PzR4tYc8VbN1sWq6HjD3gUe5AoKi0ZlM_x", substring = true).assertExists()
        sheetShot("42-office-donor-link")
        rule.onNodeWithTag("dn_link_copy").performScrollTo().performClick()
        rule.waitUntil(5000) { clip() == link }
    }

    @Test fun office_donors_kiswahili_dark() {
        Lang.sw = true
        try {
            val m = model(); show(m, dark = true)
            rule.onNodeWithTag("nav_office").performClick(); rule.waitForIdle()
            rule.onNodeWithTag("o_donors").performScrollTo().performClick()
            waitFor("Anderson Family"); waitFor("Wamefungua ukurasa")
            rule.onNodeWithTag("dn_head").performScrollTo()
            shot("44-office-donors-sw-dark")
            rule.onNodeWithTag("dn_dA1").performScrollTo().performClick()
            rule.waitUntil(5000) { rule.onAllNodesWithTag("dn_sheet").fetchSemanticsNodes().isNotEmpty() }
            waitFor("Nakili kiungo cha siri")
            sheetShot("45-office-donor-sheet-sw-dark")
        } finally { Lang.sw = false }
    }

    @Test fun thank_you_upload_call() = runBlocking {
        val m = model()
        val r = m.api.thanks("101", ByteArray(2048) { 1 }, "video/mp4", "Asante sana!", 42)
        assertTrue(r.ok)
        val p = posts.last { it.contains("pin-thanks") }
        assertTrue(p.startsWith("POST https://") && p.contains("/api/pin-thanks") && p.contains("name=\"id\"\r\n\r\n101") && p.contains("Asante sana!") && p.contains("name=\"seconds\"\r\n\r\n42") && p.contains("filename=\"thanks.mp4\""))
    }

    @Test fun project_screen_has_thank_you() {
        Lang.sw = false
        val m = model(); show(m)
        rule.onNodeWithTag("nav_list").performClick(); rule.waitForIdle()
        waitFor("Kapsaret Borehole")
        rule.onAllNodesWithText("Kapsaret Borehole")[0].performClick(); rule.waitForIdle()
        rule.onNodeWithTag("dn_thanks").performScrollTo()
        rule.onNodeWithText("Thank-you for donors").performClick(); rule.waitForIdle()
        rule.onNodeWithText("Video").assertExists()
        rule.onNodeWithTag("dn_thanks").performScrollTo()
        shot("43-project-thank-you")
    }
}

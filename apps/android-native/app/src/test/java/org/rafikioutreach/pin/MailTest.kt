package org.rafikioutreach.pin

import android.app.Application
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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.ui.RafikiTheme
import org.rafikioutreach.pin.ui.mailAudience
import org.rafikioutreach.pin.ui.mailBlocks
import org.rafikioutreach.pin.ui.mailCount
import org.rafikioutreach.pin.ui.mailPersonalise
import org.rafikioutreach.pin.ui.mailSkipText
import org.rafikioutreach.pin.ui.mailWhy
import org.rafikioutreach.pin.ui.parseMailOverview
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Office › Send an e-mail in the native app: the audience and count, the composer, the preview, the confirm sheet and what was sent. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class MailTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val posts = java.util.concurrent.CopyOnWriteArrayList<String>()
    private var ready = true

    private fun proj(id: String, t: String, type: String, photo: String? = null) = JSONObject().put("id", id).put("title", t).put("country", "Kenya").put("ck", "ke").put("area", "Eldoret").put("type", type)
        .put("status", "Completed").put("pump", "").put("gps", JSONArray().put(0.47).put(35.25)).apply { if (photo != null) put("photo", photo) }
    private fun snapshot() = JSONObject().put("ok", true).put("who", "Reid Anderson").put("admin", true).put("reviewOpen", 0)
        .put("projects", JSONArray().put(proj("101", "Kapsaret Borehole", "Borehole well", "900")).put(proj("104", "Nzoia Bridge Primary School", "School")))
        .put("repairs", JSONObject()).put("visits", JSONObject()).put("jobs", JSONArray()).put("assigned", JSONArray()).put("confirmed", JSONObject())
    private fun viewer(k: String, n: String, why: String = "") = JSONObject().put("key", k).put("name", n).put("why", why)
    private fun overview() = JSONObject().put("ok", true).put("ready", ready).put("caps", JSONObject().put("per", 80).put("day", 100).put("month", 3000)).put("use", JSONObject().put("today", 3).put("month", 27)).put("me", JSONObject().put("email", true))
        .put("donors", JSONObject().put("reachable", 3).put("unsub", 1).put("revoked", 1).put("noEmail", 0).put("list", JSONArray()
            .put(JSONObject().put("id", "dA1").put("name", "Anderson Family").put("projects", JSONArray().put("101")).put("viewers", JSONArray().put(viewer("dA1.vA", "Anderson Family"))))
            .put(JSONObject().put("id", "dB2").put("name", "Grace Community Church").put("projects", JSONArray().put("101").put("104")).put("viewers", JSONArray().put(viewer("dB2.vB", "Grace Community Church")).put(viewer("dB2.vC", "Pastor John Kamau")).put(viewer("dB2.vD", "Mary Wanjiku", "unsub"))))
            .put(JSONObject().put("id", "dC3").put("name", "Okello Family").put("projects", JSONArray().put("104")).put("viewers", JSONArray().put(viewer("dC3.vE", "Okello Family", "revoked"))))))
        .put("team", JSONObject().put("reachable", 3).put("admins", 2).put("noEmail", 1).put("people", JSONArray()
            .put(JSONObject().put("key", "t:reid anderson").put("name", "Reid Anderson").put("role", "admin").put("email", true))
            .put(JSONObject().put("key", "t:jessica").put("name", "Jessica Anderson").put("role", "admin").put("email", true))
            .put(JSONObject().put("key", "t:meril").put("name", "Meril Kiprop").put("role", "field").put("email", true))
            .put(JSONObject().put("key", "t:bertin").put("name", "Bertin Mwangi").put("role", "field").put("email", false))))
        .put("log", JSONArray().put(JSONObject().put("id", "L1").put("at", "2026-10-21T17:03:00Z").put("by", "Reid Anderson").put("audience", "donors:project:101").put("kind", "donor").put("subject", "Water is flowing again in Kapsaret")
            .put("n", 4).put("sent", 3).put("failed", 1).put("skipped", 2).put("skippedBy", JSONObject().put("unsub", 1).put("revoked", 1))
            .put("rcpt", JSONArray().put(JSONObject().put("name", "Anderson Family").put("s", "sent")).put(JSONObject().put("name", "Pastor John Kamau").put("s", "failed").put("e", "The recipient address bounced"))))
            .put(JSONObject().put("id", "L2").put("at", "2026-10-13T07:00:00Z").put("by", "Jessica Anderson").put("audience", "team").put("kind", "team").put("subject", "Team meeting on Friday").put("n", 3).put("sent", 3).put("failed", 0).put("skipped", 1)
                .put("skippedBy", JSONObject().put("noEmail", 1)).put("rcpt", JSONArray())))

    private fun model(): AppModel {
        org.rafikioutreach.pin.ui.Onb.enabled = false
        val app = ApplicationProvider.getApplicationContext<Application>()
        Prefs(app).apply { pin = "9999"; name = "Reid Anderson"; saveSnapshot(snapshot()); filter = "all" }
        val m = AppModel(app)
        m.api.transport = { method, url, _, body ->
            val b = body?.let { String(it) } ?: ""
            posts += "$method $url $b"
            when {
                url.contains("/api/mail?view=overview") -> 200 to overview().toString()
                url.endsWith("/api/mail") && b.contains("\"preview\"") -> 200 to JSONObject().put("ok", true).put("kind", if (b.contains("donors")) "donor" else "team").put("count", 3)
                    .put("names", JSONArray().put("Anderson Family").put("Grace Community Church").put("Pastor John Kamau")).put("skipped", JSONObject().put("unsub", 1).put("revoked", 0).put("noEmail", 0).put("dup", 0))
                    .put("sample", "Anderson Family").put("subject", "Water is flowing again in Kapsaret, Anderson").put("html", "<p>…</p>").toString()
                url.endsWith("/api/mail") && b.contains("\"send\"") -> 409 to JSONObject().put("ok", false).put("reason", "day-cap").put("cap", 100).put("left", 2).toString()
                url.endsWith("/api/mail") && b.contains("\"test\"") -> 200 to "{\"ok\":true,\"to\":\"reid@rafikioutreach.org\"}"
                url.contains("view=donors") -> 200 to JSONObject().put("ok", true).put("donors", JSONArray().put(JSONObject().put("id", "dA1").put("name", "Anderson Family").put("email", "a@example.org").put("org", "")
                    .put("projects", JSONArray().put("101")).put("showPhotos", true).put("photos", JSONObject()).put("digest", true).put("lastOpen", "").put("opens", 0).put("revoked", false)
                    .put("viewers", JSONArray().put(JSONObject().put("vid", "vA").put("name", "Anderson Family").put("email", "a@example.org").put("primary", true).put("active", true))))).put("mail", JSONObject().put("ready", true)).toString()
                url.contains("activity=1") -> 200 to "{\"ok\":true,\"activity\":[]}"
                url.contains("pin-auth/me") -> 200 to "{\"ok\":true,\"available\":false}"
                method == "GET" -> 200 to snapshot().toString()
                else -> 200 to "{\"ok\":true}"
            }
        }
        return m
    }
    private fun show(m: AppModel, dark: Boolean = false) = rule.setContent { RafikiTheme(dark = dark) { Box(Modifier.size(360.dp, 800.dp)) { App(m) } } }
    private fun shot(name: String) {
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun sheetShot(name: String) {
        rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); val c = Canvas(b); v.draw(c); c.drawColor(0x66000000)
        val d = org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window?.decorView
        if (d != null && d.width > 0) { c.save(); c.translate(0f, (v.height - d.height).toFloat().coerceAtLeast(0f)); d.draw(c); c.restore() }
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun waitFor(text: String) = rule.waitUntil(8000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    private fun waitTag(tag: String) = rule.waitUntil(8000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun openMail() {
        rule.onNodeWithTag("nav_office").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("o_mail").performScrollTo().performClick()
        waitTag("ml_form")
    }

    @Test fun logic() {
        Lang.sw = false
        val ov = parseMailOverview(overview())
        assertEquals("donors:all", mailAudience("donors", "all", "")); assertEquals("donors:project:101", mailAudience("donors", "project", "101")); assertEquals("donors:all", mailAudience("donors", "project", ""))
        assertEquals("people:pick", mailAudience("team", "pick", "")); assertEquals("admins", mailAudience("admins", "pick", ""))
        assertEquals(3, mailCount(ov, "donors:all", emptyList()).first); assertEquals(1, mailCount(ov, "donors:all", emptyList()).second["unsub"])
        assertEquals(3, mailCount(ov, "donors:project:101", emptyList()).first); assertEquals(1, mailCount(ov, "donors:project:104", emptyList()).second["revoked"])
        assertEquals(2, mailCount(ov, "donors:pick", listOf("dB2")).first); assertEquals(1, mailCount(ov, "donors:pick", listOf("dA1.vA")).first)
        assertEquals(2, mailCount(ov, "admins", emptyList()).first); assertEquals(3 to 1, mailCount(ov, "team", emptyList()).let { it.first to it.second["no-email"] })
        assertEquals("Hello Grace, Grace Wanjiru", mailPersonalise("Hello {first}, {NAME}", "Grace Wanjiru")); assertEquals("Hi friend", mailPersonalise("Hi {first}", ""))
        assertEquals(listOf("p" to listOf("Dear A,"), "p" to listOf("One"), "ul" to listOf("a", "b")), mailBlocks("Dear A,\n\nOne\n- a\n- b"))
        assertEquals(listOf("1 unsubscribed is skipped", "2 without an e‑mail"), mailSkipText(mapOf("unsub" to 1, "no-email" to 2)))
        assertTrue(mailWhy("day-cap", JSONObject().put("left", 12)).contains("12 left today"))
        assertEquals(2, ov.log.size); assertEquals(false, ov.log[0].rcpt[1].ok)
    }

    @Test fun compose_preview_confirm_english_light() {
        Lang.sw = false
        val m = model(); show(m)
        openMail()
        waitFor("Goes to 3 donors")
        rule.onNodeWithText("1 unsubscribed is skipped", substring = true).assertExists()
        rule.onNodeWithTag("ml_head").performScrollTo(); shot("90-mail-top")
        rule.onNodeWithTag("ml_sub_project").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithText("Choose a project.", substring = true).assertExists()
        rule.onNodeWithTag("ml_sub_pick").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("ml_p_dB2.vC").performScrollTo().performClick(); rule.waitForIdle()
        waitFor("Goes to 1 donor")
        rule.onAllNodesWithTag("ml_p_dB2.vD").assertCountEquals(1) // unsubscribed: shown, can't be ticked
        rule.onNodeWithTag("ml_sub_all").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("ml_subject").performScrollTo()
        rule.onNodeWithText("e.g. Water is flowing in Kapsaret").performTextInput("Water is flowing again in Kapsaret, {first}")
        rule.onNodeWithText("Dear {first},").performTextInput("Dear {first},\n\nThank you for standing with the Kapsaret community.\n- About 1,200 people collect water here every day")
        rule.onNodeWithTag("ml_link").performScrollTo().performClick()
        rule.onNodeWithTag("ml_photo_project").performScrollTo().performClick()
        waitTag("ml_pp_101"); rule.onNodeWithTag("ml_pp_101").performClick(); rule.mainClock.advanceTimeBy(1000); rule.waitForIdle()
        rule.onNodeWithTag("ml_subject").performScrollTo(); shot("91-mail-compose")
        // preview: the e-mail as Anderson Family gets it
        rule.onNodeWithTag("ml_preview").performScrollTo().performClick()
        waitTag("ml_preview_sheet"); waitTag("ml_mail")
        rule.onNodeWithText("Dear Anderson,", substring = true).assertExists()
        rule.onNodeWithText("See your projects, map and photos  →").assertExists()
        sheetShot("92-mail-preview")
        rule.onNodeWithTag("ml_preview_close").performClick(); rule.mainClock.advanceTimeBy(1500)
        rule.waitUntil(5000) { rule.onAllNodesWithTag("ml_preview_sheet").fetchSemanticsNodes().isEmpty() }
        val pv = posts.last { it.contains("\"preview\"") }
        assertTrue(pv, pv.contains("\"audience\":\"donors:all\"") && pv.contains("\"includeLink\":true") && pv.contains("\"kind\":\"project\"") && pv.contains("\"id\":\"900\""))
        // send asks first, in a sheet, with the count
        rule.onNodeWithTag("ml_send").performScrollTo().performClick()
        waitTag("ml_confirm")
        rule.onNodeWithText("Send to 3 donors?").assertExists()
        assertTrue(posts.none { it.contains("\"send\"") })
        sheetShot("93-mail-confirm")
        rule.onNodeWithTag("ml_confirm_yes").performClick(); rule.mainClock.advanceTimeBy(1500)
        rule.waitUntil(8000) { posts.any { it.contains("\"send\"") } }
        assertTrue(posts.last { it.contains("\"send\"") }.contains("\"expect\":3"))
        waitFor("2 left today")
        rule.onNodeWithTag("ml_send").performScrollTo(); shot("94-mail-cap")
        // what was sent
        rule.onNodeWithTag("ml_log_L1").performScrollTo().performClick()
        waitTag("ml_log_sheet"); waitFor("The recipient address bounced")
        sheetShot("95-mail-log")
    }

    @Test fun kiswahili_dark_and_from_donors() {
        Lang.sw = true
        try {
            val m = model(); show(m, dark = true)
            openMail()
            waitFor("Inaenda kwa wafadhili 3")
            rule.onNodeWithTag("ml_head").performScrollTo(); shot("96-mail-top-sw-dark")
            rule.onNodeWithTag("ml_aud_team").performClick(); rule.waitForIdle()
            waitFor("Inaenda kwa watu 3")
            rule.onNodeWithTag("ml_count").performScrollTo(); shot("97-mail-team-sw-dark")
            rule.onNodeWithTag("ml_log_L2").performScrollTo(); shot("98-mail-sent-sw-dark")
            // Office › Donors › E-mail donors opens this tab, for all donors
            rule.onNodeWithTag("office_strip").performScrollTo()
            rule.onNodeWithTag("o_donors").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
            waitTag("dn_mail_all"); rule.onNodeWithTag("dn_mail_all").performScrollTo().performClick()
            waitTag("ml_form"); waitFor("Inaenda kwa wafadhili 3")
        } finally { Lang.sw = false }
    }

    @Test fun english_dark_and_kiswahili_light() {
        Lang.sw = false
        val m = model(); show(m, dark = true)
        openMail(); waitFor("Goes to 3 donors")
        rule.onNodeWithTag("ml_head").performScrollTo(); shot("99-mail-top-dark")
        rule.onNodeWithTag("ml_preview").performScrollTo().performClick()
        waitTag("ml_mail"); sheetShot("99b-mail-preview-dark")
    }

    @Test fun kiswahili_light_not_set_up() {
        Lang.sw = true; ready = false
        try {
            val m = model(); show(m)
            openMail(); waitFor("Barua pepe bado haijawashwa")
            rule.onNodeWithTag("ml_head").performScrollTo(); shot("99c-mail-off-sw")
            rule.onAllNodesWithText("RESEND_API_KEY", substring = true).assertCountEquals(0) // only under the fold
            rule.onNodeWithTag("ml_tech_toggle").performScrollTo().performClick(); rule.waitForIdle()
            rule.onNodeWithText("RESEND_API_KEY", substring = true).assertExists()
            rule.onNodeWithTag("ml_body").performScrollTo(); shot("99d-mail-compose-sw")
        } finally { Lang.sw = false; ready = true }
    }
}

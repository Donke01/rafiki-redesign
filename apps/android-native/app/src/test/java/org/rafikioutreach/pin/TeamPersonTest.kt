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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.ui.Look
import org.rafikioutreach.pin.ui.MailNav
import org.rafikioutreach.pin.ui.Motion
import org.rafikioutreach.pin.ui.Onb
import org.rafikioutreach.pin.ui.RafikiTheme
import org.rafikioutreach.pin.ui.TeamGo
import org.rafikioutreach.pin.ui.inviteText
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.Instant

/** The app invite text and Office › Send an e-mail › Choose people (screenshots "tp-…"). The contact sheet itself is in ContactTest. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class TeamPersonTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val posts = java.util.concurrent.CopyOnWriteArrayList<String>()
    private var width = 390

    @After fun reset() { Lang.sw = false; Look.mode = ""; Motion.on = true; MailNav.preset = null; TeamGo.section = null; TeamGo.map = null; TeamGo.assign = null }

    private fun proj(id: String, t: String) = JSONObject().put("id", id).put("title", t).put("country", "Kenya").put("ck", "ke").put("area", "Eldoret")
        .put("type", "borehole").put("status", "Completed").put("pump", "manual").put("gps", JSONArray().put(0.5).put(35.2))
    private fun snapshot(admin: Boolean) = JSONObject().put("ok", true).put("who", "Reid Anderson").put("admin", admin).put("reviewOpen", 0)
        .put("projects", JSONArray().put(proj("101", "Chepkongi Dispensary")).put(proj("105", "Turbo Spring")))
        .put("repairs", JSONObject()).put("visits", JSONObject()).put("jobs", JSONArray()).put("assigned", JSONArray()).put("confirmed", JSONObject())

    private fun act(): JSONArray {
        val now = Instant.now()
        fun e(by: String, a: String, id: String, t: String, mins: Long, s: String = "") = JSONObject().put("by", by).put("a", a).put("id", id).put("t", t).put("at", now.minusSeconds(mins * 60).toString()).apply { if (s.isNotEmpty()) put("s", s) }
        return JSONArray().put(e("Meril Kiprop", "visit", "105", "Turbo Spring", 30, "working")).put(e("Meril Kiprop", "pin", "101", "Chepkongi Dispensary", 90)).put(e("Reid Anderson", "pin", "101", "Chepkongi Dispensary", 50))
    }

    private val inviteHtml = "<div><p>Hi Meril,</p><p>You’re invited to RAFIKI Pin, the field app RAFIKI Outreach uses to pin our water projects and report site visits.</p>" +
        "<p>On an iPhone, open the link in Safari, tap Share, then “Add to Home Screen”. On Android or a computer, just open the link.</p>" +
        "<p><a href=\"https://pin.rafikioutreach.org/pin\">Open RAFIKI Pin</a></p><p>Sign in with your PIN, which I’m sending you separately.</p><p>— Reid Anderson</p></div>"

    private fun model(admin: Boolean = true, reidApp: Boolean = false, sharing: Boolean = true, noEmail: Boolean = false): AppModel {
        Onb.enabled = false
        Prefs(app).apply { pin = "9999"; name = "Reid Anderson"; saveSnapshot(snapshot(admin)); filter = "all"; onb = true; lang = if (Lang.sw) "sw" else "en"; mePane = "me"; phoneAlerts = false }
        val m = AppModel(app)
        m.api.transport = { method, url, _, body ->
            val b = body?.let { String(it) } ?: ""
            posts += "$method $url $b"
            when {
                url.contains("view=teammap") -> 200 to JSONObject().put("ok", true).put("people", if (sharing) JSONArray().put(JSONObject().put("name", "Meril Kiprop").put("lat", 0.51).put("lng", 35.27).put("acc", 12).put("at", Instant.now().minusSeconds(600).toString()).put("phone", "0712 345 678").put("home", "ke")) else JSONArray()).put("visits", JSONArray()).toString()
                url.contains("view=team") -> 200 to JSONObject().put("ok", true).put("team", JSONArray()
                    .put(JSONObject().put("name", "Meril Kiprop").put("home", "ke").put("admin", false).put("source", "app").put("phone", "0712 345 678").put("email", if (noEmail) "" else "meril@rafikioutreach.org").put("lastSeen", Instant.now().minusSeconds(1800).toString()))
                    .put(JSONObject().put("name", "Reid Anderson").put("admin", true).put("source", if (reidApp) "app" else "cloudflare").put("email", "reid@rafikioutreach.org"))).toString()
                url.contains("team=1") -> 200 to JSONObject().put("ok", true).put("team", JSONArray().put(JSONObject().put("name", "Meril Kiprop").put("home", "ke").put("phone", "0712 345 678").put("lastSeen", Instant.now().minusSeconds(1800).toString()))).toString()
                url.contains("view=assign") -> 200 to "{\"ok\":true,\"items\":[]}"
                url.contains("view=review") -> 200 to "{\"ok\":true,\"items\":[]}"
                url.contains("activity=1") -> 200 to JSONObject().put("ok", true).put("activity", act()).toString()
                url.contains("/api/mail?view=overview") -> 200 to JSONObject().put("ok", true).put("ready", true).put("me", JSONObject().put("email", true)).put("donors", JSONObject().put("list", JSONArray()))
                    .put("donors", JSONObject().put("reachable", 1).put("list", JSONArray().put(JSONObject().put("id", "d1").put("name", "Hope Church").put("projects", JSONArray())
                        .put("viewers", JSONArray().put(JSONObject().put("key", "d1.v1").put("name", "Anna Smith").put("why", "")).put(JSONObject().put("key", "d1.v2").put("name", "Ben Otieno").put("why", "unsub"))))))
                    .put("team", JSONObject().put("reachable", 2).put("people", JSONArray().put(JSONObject().put("key", "t:meril kiprop").put("name", "Meril Kiprop").put("role", "field").put("email", true))
                        .put(JSONObject().put("key", "t:reid anderson").put("name", "Reid Anderson").put("role", "admin").put("email", true)).put(JSONObject().put("key", "t:jessica").put("name", "Jessica Anderson").put("role", "admin").put("email", false)))).put("log", JSONArray()).toString()
                url.contains("/api/mail") && b.contains("\"invite\"") && b.contains("\"preview\"") -> 200 to JSONObject().put("ok", true).put("subject", "You're invited to RAFIKI Pin").put("html", inviteHtml).toString()
                url.contains("/api/mail") && b.contains("\"invite\"") -> 200 to "{\"ok\":true,\"sent\":1}"
                url.contains("/api/mail") && b.contains("\"preview\"") -> 200 to JSONObject().put("ok", true).put("count", 2).put("kind", "team").put("subject", "Hello").put("sample", "Meril Kiprop").put("names", JSONArray().put("Meril Kiprop").put("Anna Smith")).toString()
                url.contains("/api/mail") && b.contains("\"send\"") -> 200 to "{\"ok\":true,\"sent\":2}"
                b.contains("\"team-update\"") && b.contains("\"Reid Anderson\"") && b.contains("\"admin\":false") -> 409 to "{\"ok\":false,\"reason\":\"self\"}"
                url.contains("pin-auth/me") -> 200 to "{\"ok\":true,\"available\":true,\"user\":null}"
                url.contains("version.json") -> 0 to ""
                method == "GET" -> 200 to snapshot(admin).toString()
                else -> 200 to "{\"ok\":true}"
            }
        }
        return m
    }
    private fun show(m: AppModel, dark: Boolean = false) = rule.setContent { RafikiTheme(dark = dark) { Box(Modifier.size(width.dp, 844.dp)) { App(m) } } }
    private fun settle() { rule.mainClock.advanceTimeBy(800); rule.waitForIdle() }
    private fun shot(name: String) {
        rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun sheetShot(name: String) {
        rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); val c = Canvas(b); v.draw(c)
        val d = ShadowDialog.getLatestDialog()?.window?.decorView
        if (d != null) { c.save(); c.translate(0f, (v.height - d.height).toFloat()); d.draw(c); c.restore() }
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun tag(t: String) = rule.onNodeWithTag(t)
    private fun tagged(t: String) = rule.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty()
    private fun waitTag(t: String) = rule.waitUntil(8000) { tagged(t) }
    private fun waitFor(text: String) = rule.waitUntil(8000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    private fun has(text: String) = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun sheetOpen() = ShadowDialog.getLatestDialog()?.isShowing == true
    private fun back() {
        rule.runOnUiThread {
            val d = ShadowDialog.getLatestDialog()
            @Suppress("DEPRECATION")
            if (d != null && d.isShowing) d.onBackPressed() else rule.activity.onBackPressedDispatcher.onBackPressed()
        }
        settle()
    }
    // --- the invite text --------------------------------------------------------------------------------------------

    @Test fun invite_text_never_has_a_pin() {
        Lang.sw = false
        val en = inviteText("Meril Kiprop", "Reid Anderson", admin = true)
        assertTrue(en.startsWith("Hi Meril,")); assertTrue(en.contains("https://pin.rafikioutreach.org/pin")); assertTrue(en.contains("Add to Home Screen"))
        assertTrue(en.contains("Office tab")); assertTrue(en.contains("which I’m sending you separately")); assertTrue(en.endsWith("— Reid Anderson"))
        assertFalse("no PIN-like digits", Regex("\\b\\d{4,6}\\b").containsMatchIn(en))
        assertFalse(inviteText("Meril Kiprop", "Reid Anderson", admin = false).contains("Office tab"))
        Lang.sw = true
        val sw = inviteText("Meril Kiprop", "Reid Anderson", admin = false)
        assertTrue(sw.startsWith("Habari Meril,")); assertTrue(sw.contains("Ongeza kwenye Skrini ya Nyumbani")); assertFalse(Regex("\\b\\d{4,6}\\b").containsMatchIn(sw))
    }

    // --- Office › Send an e-mail › Choose people ---------------------------------------------------------------------

    private fun mailPick(m: AppModel, dark: Boolean = false) {
        show(m, dark); waitTag("hav"); rule.mainClock.advanceTimeBy(2000)
        tag("nav_office").performClick(); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        tag("o_mail").performScrollTo().performClick(); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        waitTag("ml_aud_pick"); tag("ml_aud_pick").performClick(); settle()
    }

    @Test fun choose_people_across_admins_team_and_donors() {
        Lang.sw = false
        val m = model(); mailPick(m)
        listOf("ml_g_admins", "ml_g_team", "ml_g_donors", "ml_all_admins", "ml_all_team", "ml_all_donors").forEach { assertTrue(it, tagged(it)) }
        tag("ml_pk_t:jessica").assertIsNotEnabled() // no e-mail
        tag("ml_all_admins").performScrollTo().performClick(); settle()
        tag("ml_pk_d1.v1").performScrollTo().performClick(); settle()
        assertTrue(tagged("ml_chip_t:reid anderson") && tagged("ml_chip_d1.v1")); assertFalse(tagged("ml_chip_t:jessica"))
        tag("ml_count").assertTextEquals("Goes to 2 people")
        shot("tp-13-mail-choose-people")
        // the search narrows every group
        tag("ml_q").performScrollTo(); rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("ml_q"))).performTextInput("anna"); settle()
        assertFalse(tagged("ml_pk_t:meril kiprop")); assertTrue(tagged("ml_pk_d1.v1"))
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("ml_q"))).performTextClearance(); settle()
        // a chip comes off
        tag("ml_chip_t:reid anderson").performScrollTo().performClick(); settle(); tag("ml_count").assertTextEquals("Goes to 1 person")
        tag("ml_pk_t:meril kiprop").performScrollTo().performClick(); settle()
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("ml_subject"))).performTextInput("Hello")
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("ml_body"))).performTextInput("Hello {first},")
        posts.clear()
        tag("ml_send").performScrollTo().performClick(); settle(); waitTag("ml_confirm")
        tag("ml_confirm_yes").performScrollTo().performClick(); settle(); rule.mainClock.advanceTimeBy(1000); rule.waitForIdle()
        val send = posts.first { it.contains("/api/mail") && it.contains("\"send\"") }
        assertTrue(send, send.contains("\"aud\":\"pick\"") && send.contains("\"people\":[\"t:meril kiprop\"]") && send.contains("\"donors\":[\"d1.v1\"]"))
    }

    @Test @Config(qualifiers = "w360dp-h800dp-xxhdpi") fun choose_people_kiswahili_dark_360() {
        Lang.sw = true; width = 360
        val m = model(); mailPick(m, dark = true)
        assertTrue(has("Chagua wote")); tag("ml_all_team").performScrollTo().performClick(); settle()
        tag("ml_aud_pick").performScrollTo(); shot("tp-14-mail-choose-people-sw-dark-360")
        tag("ml_chips").performScrollTo(); shot("tp-15-mail-chips-sw-dark-360")
    }
}

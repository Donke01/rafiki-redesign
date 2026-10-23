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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.ui.Contact
import org.rafikioutreach.pin.ui.Look
import org.rafikioutreach.pin.ui.MailNav
import org.rafikioutreach.pin.ui.Motion
import org.rafikioutreach.pin.ui.Onb
import org.rafikioutreach.pin.ui.RafikiTheme
import org.rafikioutreach.pin.ui.TeamGo
import org.rafikioutreach.pin.ui.contactWhy
import org.rafikioutreach.pin.ui.pinProblem
import org.rafikioutreach.pin.ui.vcard
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.Instant

/**
 * feat-conta: one contact sheet for every person (screenshots "ct-…", 360 dp, English and Kiswahili, light and dark).
 * The menu (Contact, Details, Access, Their work), each action's own small sheet, the person's own sheet, a field
 * officer's view, the places that open it, a donor contact, and being blocked or signed out by the office.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class ContactTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val posts = java.util.concurrent.CopyOnWriteArrayList<String>()
    /** What GET /api/pin answers (a 401 reason, to test being blocked or signed out). */
    @Volatile private var listEnd: String? = null
    /** How the signed-in admin (Reid) got their PIN. */
    private var reidSrc = "app"

    @After fun reset() { reidSrc = "app"; Lang.sw = false; Look.mode = ""; Motion.on = true; MailNav.preset = null; TeamGo.section = null; TeamGo.map = null; TeamGo.assign = null; Contact.who = null }

    private val now = Instant.now()
    private fun ago(min: Long) = now.minusSeconds(min * 60).toString()
    private fun proj(id: String, t: String) = JSONObject().put("id", id).put("title", t).put("country", "Kenya").put("ck", "ke").put("area", "Eldoret")
        .put("type", "borehole").put("status", "Completed").put("pump", "manual").put("gps", JSONArray().put(0.5).put(35.2))
    private fun snapshot(admin: Boolean) = JSONObject().put("ok", true).put("who", "Reid Anderson").put("admin", admin).put("reviewOpen", if (admin) 1 else 0)
        .put("projects", JSONArray().put(proj("101", "Chepkongi Dispensary")).put(proj("105", "Turbo Spring")))
        .put("repairs", JSONObject().put("105", JSONObject().put("state", "repair").put("by", "Meril Kiprop").put("at", ago(300)).put("note", "Handle loose").put("urgency", "month").put("assignee", "Meril Kiprop").put("step", "assigned")))
        .put("visits", JSONObject().put("105", JSONObject().put("at", ago(30)).put("by", "Meril Kiprop").put("state", "repair")))
        .put("jobs", JSONArray()).put("assigned", JSONArray()).put("confirmed", JSONObject())

    private fun act() = JSONArray()
        .put(JSONObject().put("by", "Meril Kiprop").put("a", "visit").put("id", "105").put("t", "Turbo Spring").put("s", "repair").put("at", ago(30)))
        .put(JSONObject().put("by", "Meril Kiprop").put("a", "pin").put("id", "101").put("t", "Chepkongi Dispensary").put("at", ago(90)))

    private fun team() = JSONArray()
        .put(JSONObject().put("name", "Meril Kiprop").put("home", "ke").put("admin", false).put("src", "app").put("phone", "0712 345 678").put("email", "meril@rafikioutreach.org")
            .put("role", "Field officer, Kitale").put("lastSeen", ago(45)).put("aka", JSONArray().put("Meril K")).put("blocked", JSONObject.NULL).put("out", "2026-10-22T08:00:00Z"))
        .put(JSONObject().put("name", "Peter Otieno").put("home", "ke").put("admin", true).put("adminCf", true).put("alsoCloudflare", true).put("src", "app").put("lastSeen", ago(600)))
        .put(JSONObject().put("name", "Reid Anderson").put("home", "ke").put("admin", true).put("src", reidSrc).put("email", "reid@rafikioutreach.org").put("phone", "0700 111 222").put("lastSeen", ago(1)))
        .put(JSONObject().put("name", "Grace Wanjiru").put("home", "ke").put("admin", false).put("src", "app").put("phone", "0722 000 333")
            .put("blocked", JSONObject().put("at", "2026-10-23T08:00:00Z").put("by", "Morgan").put("reason", "Phone lost")))
        .put(JSONObject().put("name", "Jessica Anderson").put("admin", true).put("src", "cloudflare"))

    private val donors = JSONArray().put(JSONObject().put("id", "dA").put("name", "Hope Church").put("email", "office@hope.org").put("org", "Hope Church").put("projects", JSONArray().put("101"))
        .put("showPhotos", true).put("photos", JSONObject()).put("digest", true).put("lastOpen", "2026-10-19T18:20:00Z").put("opens", 3).put("revoked", false)
        .put("viewers", JSONArray()
            .put(JSONObject().put("vid", "v1").put("name", "Hope Church").put("email", "office@hope.org").put("primary", true).put("lastOpen", "2026-10-19T18:20:00Z").put("active", true).put("code", "HOPE-7Q2K"))
            .put(JSONObject().put("vid", "v2").put("name", "Pastor John").put("email", "john@hope.org").put("primary", false).put("lastOpen", "").put("active", false))))

    private fun model(admin: Boolean = true): AppModel {
        Onb.enabled = false
        Prefs(app).apply { pin = "9999"; name = "Reid Anderson"; saveSnapshot(snapshot(admin)); filter = "all"; onb = true; lang = if (Lang.sw) "sw" else "en"; mePane = "me"; phoneAlerts = false }
        val m = AppModel(app)
        m.api.transport = { method, url, _, body ->
            val b = body?.let { String(it) } ?: ""
            posts += "$method $url $b"
            when {
                url.contains("view=teammap") -> 200 to JSONObject().put("ok", true).put("people", JSONArray().put(JSONObject().put("name", "Meril Kiprop").put("lat", 0.51).put("lng", 35.27).put("acc", 12).put("at", ago(10)).put("phone", "0712 345 678").put("home", "ke"))).put("visits", JSONArray()).toString()
                url.contains("view=team") -> 200 to JSONObject().put("ok", true).put("team", team()).toString()
                url.contains("team=1") -> 200 to JSONObject().put("ok", true).put("team", JSONArray().put(JSONObject().put("name", "Meril Kiprop").put("home", "ke").put("phone", "0712 345 678").put("lastSeen", ago(45)))
                    .put(JSONObject().put("name", "Reid Anderson").put("home", "ke").put("lastSeen", ago(1)))).toString()
                url.contains("view=assign") -> 200 to "{\"ok\":true,\"items\":[]}"
                url.contains("view=review") -> 200 to JSONObject().put("ok", true).put("items", JSONArray().put(JSONObject().put("rid", "r1").put("kind", "story").put("id", "101").put("t", "Chepkongi Dispensary").put("by", "Meril Kiprop").put("at", ago(60)).put("status", "open")
                    .put("data", JSONObject().put("quote", "Now the nurses can wash their hands").put("who", "Mary")))).toString()
                url.contains("view=donors") -> 200 to JSONObject().put("ok", true).put("donors", donors).put("mail", JSONObject().put("ready", true)).toString()
                url.contains("activity=1") -> 200 to JSONObject().put("ok", true).put("activity", act()).toString()
                url.contains("history=") -> 200 to JSONObject().put("ok", true).put("notes", JSONArray().put(JSONObject().put("id", "n1").put("text", "Site visit by Meril Kiprop (RAFIKI pin app): needs repair").put("at", ago(30)).put("by", "RAFIKI Pin").put("files", JSONArray()))).toString()
                url.contains("/api/mail?view=overview") -> 200 to JSONObject().put("ok", true).put("ready", true).put("me", JSONObject().put("email", true))
                    .put("donors", JSONObject().put("reachable", 1).put("list", JSONArray())).put("team", JSONObject().put("reachable", 2).put("people", JSONArray()
                        .put(JSONObject().put("key", "t:meril kiprop").put("name", "Meril Kiprop").put("role", "field").put("email", true)))).put("log", JSONArray()).toString()
                b.contains("\"team-rename\"") && b.contains("\"to\":\"Grace Wanjiru\"") -> 409 to "{\"ok\":false,\"reason\":\"exists\"}"
                b.contains("\"team-set-pin\"") && b.contains("\"pin\":\"1357\"") -> 409 to "{\"ok\":false,\"reason\":\"pin-taken\"}"
                b.contains("\"team-pin\"") -> 200 to "{\"ok\":true,\"pin\":\"5307\"}"
                b.contains("\"team-move\"") -> 200 to "{\"ok\":true,\"pin\":\"8642\"}"
                b.contains("\"donor-link\"") -> 200 to "{\"ok\":true,\"link\":\"https://rafikioutreach.org/donor/abc\"}"
                b.contains("\"donor-rotate\"") -> 200 to "{\"ok\":true,\"link\":\"https://rafikioutreach.org/donor/new\"}"
                url.contains("pin-auth/me") -> 200 to "{\"ok\":true,\"available\":false,\"user\":null}"
                url.contains("version.json") -> 0 to ""
                method == "GET" && url.contains("/api/pin") && listEnd != null -> 401 to "{\"ok\":false,\"reason\":\"$listEnd\"}"
                method == "GET" -> 200 to snapshot(admin).toString()
                else -> 200 to "{\"ok\":true}"
            }
        }
        return m
    }

    private fun show(m: AppModel, dark: Boolean = false) = rule.setContent { RafikiTheme(dark = dark) { Box(Modifier.size(360.dp, 800.dp)) { App(m) } } }
    private fun settle(ms: Long = 800) { rule.mainClock.advanceTimeBy(ms); rule.waitForIdle() }
    private fun shot(name: String) {
        settle(1500)
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    /** The screen with the top-most sheet drawn over it. */
    private fun sheetShot(name: String) {
        settle(900)
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); val c = Canvas(b); v.draw(c)
        val d = (ShadowDialog.getShownDialogs().lastOrNull { it.isShowing } ?: ShadowDialog.getLatestDialog())?.window?.decorView
        if (d != null) { c.save(); c.translate(0f, (v.height - d.height).toFloat()); d.draw(c); c.restore() }
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun tag(t: String) = rule.onNodeWithTag(t)
    private fun tagged(t: String) = rule.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty()
    private fun waitTag(t: String) = rule.waitUntil(8000) { tagged(t) }
    private fun waitGone(t: String) = rule.waitUntil(8000) { !tagged(t) }
    private fun has(text: String) = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(text: String) = rule.waitUntil(8000) { has(text) }
    private fun type(field: String, text: String) {
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(field))).performTextClearance()
        if (text.isNotEmpty()) rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(field))).performTextInput(text)
        rule.waitForIdle()
    }
    private fun back() {
        rule.runOnUiThread {
            // the top-most sheet still showing gets back, as on a phone (the latest one may be closed already)
            val d = ShadowDialog.getShownDialogs().lastOrNull { it.isShowing }
            @Suppress("DEPRECATION")
            if (d != null) d.onBackPressed() else rule.activity.onBackPressedDispatcher.onBackPressed()
        }
        settle()
    }
    private fun office(m: AppModel, dark: Boolean = false) {
        show(m, dark); waitTag("hav"); settle(2000)
        tag("nav_office").performClick(); settle(1500)
    }
    /** Office › Team › a person: their contact sheet. */
    private fun openContact(m: AppModel, who: String = "Meril Kiprop", dark: Boolean = false) {
        office(m, dark)
        tag("o_team").performScrollTo().performClick(); settle(1500)
        waitTag("tm_$who"); tag("tm_$who").performScrollTo().performClick(); waitTag("ct_sheet")
        waitLoaded(); settle(1200)
    }
    /** The contact sheet has its person (the status line no longer says Loading). */
    private fun waitLoaded() = rule.waitUntil(8000) {
        runCatching { tag("ct_status").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString() }.getOrDefault("").let { it.isNotBlank() && !it.startsWith("Loading") && !it.startsWith("Inapakia") }
    }
    /** Waits for the sign-out (the network answers on another thread, then the main thread signs out). */
    private fun signedOut(m: AppModel) {
        repeat(60) { if (m.signedIn.value) { Thread.sleep(50); org.robolectric.shadows.ShadowLooper.idleMainLooper(); settle(200) } }
        assertFalse("signed out", m.signedIn.value); settle(1500)
    }
    private fun post(action: String) = posts.lastOrNull { it.contains("\"action\":\"$action\"") }

    // --- plain rules -------------------------------------------------------------------------------------------------

    @Test fun weak_pins_are_refused_before_asking_the_server() {
        listOf("1234", "0000", "9876", "1111", "123", "123456789", "12a4", "1212", "456456").forEach { assertTrue(it, pinProblem(it) != null) }
        listOf("2580", "1357", "4829", "73915", "20481736").forEach { assertNull(it, pinProblem(it)) }
        assertEquals("digits", pinProblem("12"))
        assertEquals("weak-pin", pinProblem("4321"))
        Lang.sw = false; assertTrue(contactWhy("exists").startsWith("Someone with that name"))
        Lang.sw = true; assertTrue(contactWhy("last-admin").contains("msimamizi wa mwisho"))
    }

    @Test fun vcard_has_name_phone_and_email() {
        val v = vcard("Meril Kiprop", "0712 345 678", "meril@rafikioutreach.org", "Field officer, Kitale")
        assertTrue(v.startsWith("BEGIN:VCARD")); assertTrue(v.contains("FN:Meril Kiprop")); assertTrue(v.contains("N:Kiprop;Meril;;;"))
        assertTrue(v.contains("TEL;TYPE=CELL:0712 345 678")); assertTrue(v.contains("EMAIL:meril@rafikioutreach.org")); assertTrue(v.contains("TITLE:Field officer\\, Kitale"))
        assertTrue(v.trimEnd().endsWith("END:VCARD"))
    }

    // --- the admin's view of a teammate ------------------------------------------------------------------------------

    @Test fun admin_contact_sheet_english_light() {
        Lang.sw = false
        val m = model(); openContact(m)
        tag("ct_name").assertTextEquals("Meril Kiprop"); tag("ct_role").assertTextEquals("Field officer, Kitale")
        listOf("ct_b_field", "ct_b_app").forEach { assertTrue(it, tagged(it)) }; assertFalse(tagged("ct_b_blocked")); assertFalse(tagged("ct_b_admin"))
        assertTrue(has("Active · last seen")); assertTrue(tagged("ct_out")); assertTrue(has("Signed out of all phones on 22\u00A0Oct"))
        listOf("ct_copy_email", "ct_copy_phone", "ct_mail", "ct_call", "ct_wa", "ct_sms", "ct_share", "ct_name_row", "ct_email_row", "ct_phone_row", "ct_home_row", "ct_role_row",
            "ct_invite", "ct_newpin", "ct_setpin", "ct_resetpin", "ct_admin", "ct_block", "ct_signout", "ct_remove", "ct_assign", "ct_jobs").forEach { assertTrue(it, tagged(it)) }
        assertTrue(has("Change e-mail") && has("Change phone"))
        sheetShot("ct-01-contact-admin-en-light")
        tag("ct_remove").performScrollTo(); sheetShot("ct-02-contact-access-en-light")
        rule.waitUntil(20000) { tagged("ct_map") } // they’re sharing their location
        tag("ct_jobs").performScrollTo(); assertTrue(has("1 open")); assertTrue(tagged("tma_act_105"))
        sheetShot("ct-03-contact-work-en-light")
        // every row is big enough to tap
        listOf("ct_mail", "ct_block", "ct_jobs").forEach { assertTrue(it, tag(it).fetchSemanticsNode().size.height >= with(rule.density) { 44.dp.roundToPx() }) }
        // copy the phone
        tag("ct_copy_phone").performScrollTo().performClick(); settle()
        val clip = app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        assertEquals("0712 345 678", clip.primaryClip?.getItemAt(0)?.text?.toString())
        // their repair jobs, then one opens the project
        tag("ct_jobs").performScrollTo().performClick(); waitTag("ct_jobs_sheet"); waitTag("ct_job_105")
        tag("ct_job_105").performClick(); settle(1500); waitGone("ct_sheet")
        waitFor("Turbo Spring"); assertTrue(tagged("hback"))
    }

    @Test fun admin_contact_sheet_kiswahili_dark() {
        Lang.sw = true
        val m = model(); openContact(m, dark = true)
        assertTrue(has("Tuma barua pepe") && has("Piga simu") && has("Badilisha barua pepe") && has("Mfanye msimamizi") && has("Zuia"))
        assertTrue(has("Anatumia · alionekana"))
        sheetShot("ct-04-contact-admin-sw-dark")
        tag("ct_remove").performScrollTo(); sheetShot("ct-05-contact-access-sw-dark")
    }

    @Test fun admin_contact_sheet_english_dark_and_kiswahili_light() {
        Lang.sw = false
        val m = model(); openContact(m, dark = true)
        sheetShot("ct-06-contact-admin-en-dark")
        Lang.sw = true; settle(); sheetShot("ct-07-contact-admin-sw-light-after-switch")
    }

    @Test fun send_an_email_opens_choose_people_with_them() {
        Lang.sw = false
        val m = model(); openContact(m)
        tag("ct_mail").performScrollTo().performClick(); settle(1500)
        waitGone("ct_sheet"); waitTag("ml_form"); waitTag("ml_chip_t:meril kiprop")
        assertNull(MailNav.preset)
    }

    // --- one field per sheet -----------------------------------------------------------------------------------------

    @Test fun change_email_one_field_then_saved_and_remove() {
        Lang.sw = false
        val m = model(); openContact(m)
        tag("ct_email_row").performScrollTo().performClick(); waitTag("ct_field_sheet"); settle()
        assertTrue(has("Change e-mail")); assertTrue(tagged("ct_field_clear"))
        sheetShot("ct-08-change-email-en-light")
        type("ct_field", "meril.kiprop"); tag("ct_field_save").performClick(); settle()
        assertTrue(has("doesn’t look like an e-mail")); assertNull(post("team-update"))
        type("ct_field", "meril.k@rafikioutreach.org"); tag("ct_field_save").performClick(); settle(1500)
        rule.waitUntil(5000) { post("team-update") != null }
        post("team-update")!!.let {
            assertTrue(it, it.contains("\"name\":\"Meril Kiprop\"") && it.contains("\"email\":\"meril.k@rafikioutreach.org\"") && it.contains("\"phone\":\"0712 345 678\"")
                && it.contains("\"admin\":false") && it.contains("\"home\":\"ke\"") && it.contains("\"role\":\"Field officer, Kitale\""))
        }
        waitGone("ct_field_sheet"); assertTrue(tagged("ct_sheet")); assertTrue(has("Saved ✓"))
        // Remove e-mail
        tag("ct_email_row").performScrollTo().performClick(); waitTag("ct_field_clear"); posts.clear()
        tag("ct_field_clear").performScrollTo().performClick(); settle(1500)
        rule.waitUntil(5000) { post("team-update") != null }; assertTrue(post("team-update")!!.contains("\"email\":\"\""))
    }

    @Test fun change_email_kiswahili_dark() {
        Lang.sw = true
        val m = model(); openContact(m, dark = true)
        tag("ct_email_row").performScrollTo().performClick(); waitTag("ct_field_sheet"); settle()
        assertTrue(has("Badilisha barua pepe") && has("Ondoa barua pepe"))
        sheetShot("ct-09-change-email-sw-dark")
    }

    @Test fun rename_refuses_a_taken_name_then_renames() {
        Lang.sw = false
        val m = model(); openContact(m)
        tag("ct_name_row").performScrollTo().performClick(); waitTag("ct_field_sheet"); settle()
        assertTrue(has("Rename")); assertTrue(has("stay linked to them"))
        type("ct_field", "Grace Wanjiru"); tag("ct_field_save").performClick(); settle()
        rule.waitUntil(5000) { has("Someone with that name is already on the team.") }
        assertTrue(tagged("ct_field_sheet"))
        type("ct_field", "Meril J. Kiprop"); tag("ct_field_save").performClick(); settle(1500)
        rule.waitUntil(5000) { posts.any { it.contains("\"team-rename\"") && it.contains("\"to\":\"Meril J. Kiprop\"") && it.contains("\"name\":\"Meril Kiprop\"") } }
        waitGone("ct_field_sheet"); assertTrue(has("Renamed ✓"))
    }

    @Test fun home_country_and_role() {
        Lang.sw = false
        val m = model(); openContact(m)
        tag("ct_home_row").performScrollTo().performClick(); waitTag("ct_home_sheet"); settle()
        tag("ct_home_ug").performClick(); tag("ct_home_save").performScrollTo().performClick(); settle(1500)
        rule.waitUntil(5000) { post("team-update")?.contains("\"home\":\"ug\"") == true }
        waitGone("ct_home_sheet"); posts.clear()
        tag("ct_role_row").performScrollTo().performClick(); waitTag("ct_field_sheet"); settle()
        type("ct_field", "Lead field officer"); tag("ct_field_save").performClick(); settle(1500)
        rule.waitUntil(5000) { post("team-update")?.contains("\"role\":\"Lead field officer\"") == true }
    }

    // --- access --------------------------------------------------------------------------------------------------------

    @Test fun block_asks_first_with_a_reason_then_blocks() {
        Lang.sw = false
        val m = model(); openContact(m)
        tag("ct_block").performScrollTo().performClick(); waitTag("ct_ask_sheet"); settle()
        assertTrue(has("Block Meril Kiprop?")); assertTrue(has("signed out on every phone")); assertTrue(has("history is kept"))
        assertNull(post("team-block"))
        sheetShot("ct-10-block-confirm-en-light")
        type("ct_reason", "Phone stolen")
        tag("ct_yes").performScrollTo().performClick(); settle(1500)
        rule.waitUntil(5000) { post("team-block") != null }
        assertTrue(post("team-block")!!.let { it.contains("\"name\":\"Meril Kiprop\"") && it.contains("\"reason\":\"Phone stolen\"") })
        waitGone("ct_ask_sheet"); assertTrue(tagged("ct_sheet")); assertTrue(has("Meril Kiprop is blocked"))
    }

    @Test fun block_confirm_kiswahili_dark() {
        Lang.sw = true
        val m = model(); openContact(m, dark = true)
        tag("ct_block").performScrollTo().performClick(); waitTag("ct_ask_sheet"); settle()
        assertTrue(has("Mzuie Meril Kiprop?") && has("Sababu (si lazima)"))
        sheetShot("ct-11-block-confirm-sw-dark")
    }

    @Test fun a_blocked_person_shows_who_blocked_them_and_can_be_unblocked() {
        Lang.sw = false
        val m = model(); openContact(m, "Grace Wanjiru")
        assertTrue("badge", tagged("ct_b_blocked")); assertTrue("status", has("Blocked by Morgan on 23\u00A0Oct")); assertTrue("reason", has("“Phone lost”"))
        assertFalse(tagged("ct_block")); assertTrue(tagged("ct_unblock"))
        sheetShot("ct-12-contact-blocked-en-light")
        tag("ct_unblock").performScrollTo().performClick(); waitTag("ct_ask_sheet"); settle()
        tag("ct_yes").performScrollTo().performClick(); settle(1500)
        rule.waitUntil(5000) { post("team-unblock")?.contains("\"name\":\"Grace Wanjiru\"") == true }
    }

    @Test fun set_pin_refuses_weak_and_taken_then_sets_with_send_options() {
        Lang.sw = false
        val m = model(); openContact(m)
        tag("ct_setpin").performScrollTo().performClick(); waitTag("ct_setpin_sheet"); settle()
        sheetShot("ct-13-set-pin-en-light")
        type("ct_setpin_field", "1234"); tag("ct_setpin_go").performClick(); settle()
        assertTrue(has("too easy to guess")); assertNull(post("team-set-pin"))
        type("ct_setpin_field", "7777"); tag("ct_setpin_go").performClick(); settle()
        assertTrue(has("too easy to guess")); assertNull(post("team-set-pin"))
        type("ct_setpin_field", "1357"); tag("ct_setpin_go").performClick(); settle(1200)
        rule.waitUntil(5000) { has("That PIN can’t be used") }
        type("ct_setpin_field", "2580"); tag("ct_setpin_go").performClick(); settle(1200)
        waitTag("ct_setpin_ok"); waitTag("tm_pin_shown")
        assertTrue(post("team-set-pin")!!.let { it.contains("\"name\":\"Meril Kiprop\"") && it.contains("\"pin\":\"2580\"") })
        tag("tm_pin_copy").assertExists(); tag("tm_pin_wa").assertExists(); tag("tm_pin_sms").assertExists()
        sheetShot("ct-14-set-pin-done-en-light")
    }

    @Test fun set_pin_kiswahili_dark() {
        Lang.sw = true
        val m = model(); openContact(m, dark = true)
        tag("ct_setpin").performScrollTo().performClick(); waitTag("ct_setpin_sheet"); settle()
        assertTrue(has("Weka PIN ya Meril Kiprop"))
        type("ct_setpin_field", "0000"); tag("ct_setpin_go").performClick(); settle()
        assertTrue(has("rahisi kubahatisha"))
        sheetShot("ct-15-set-pin-sw-dark")
    }

    @Test fun reset_and_send_a_new_pin() {
        Lang.sw = false
        val m = model(); openContact(m)
        tag("ct_resetpin").performScrollTo().performClick(); waitTag("ct_newpin_sheet"); settle()
        assertTrue(has("nobody can look up their old one"))
        tag("ct_yes").performScrollTo().performClick(); waitFor("5307")
        assertTrue(post("team-pin") != null); tag("tm_pin_wa").assertExists(); tag("tm_pin_sms").assertExists()
    }

    @Test fun sign_out_of_all_phones_and_remove() {
        Lang.sw = false
        val m = model(); openContact(m)
        tag("ct_signout").performScrollTo().performClick(); waitTag("ct_ask_sheet"); settle()
        assertTrue(has("Their PIN keeps working"))
        tag("ct_yes").performScrollTo().performClick(); settle(1500)
        rule.waitUntil(5000) { post("team-signout")?.contains("\"name\":\"Meril Kiprop\"") == true }
        waitGone("ct_ask_sheet")
        tag("ct_remove").performScrollTo().performClick(); waitTag("ct_ask_sheet"); settle()
        tag("ct_yes").performScrollTo().performClick(); settle(1500)
        rule.waitUntil(5000) { post("team-remove") != null }
        waitGone("ct_sheet"); assertTrue(tagged("tm_Meril Kiprop"))
    }

    @Test fun make_admin_confirm_keeps_their_details() {
        Lang.sw = false
        val m = model(); openContact(m)
        tag("ct_admin").performScrollTo().performClick(); waitTag("tma_admin_sheet"); settle()
        tag("tma_admin_yes").performScrollTo().performClick(); settle(1500)
        rule.waitUntil(5000) { post("team-update") != null }
        assertTrue(post("team-update")!!.let { it.contains("\"admin\":true") && it.contains("\"role\":\"Field officer, Kitale\"") && it.contains("\"email\":\"meril@rafikioutreach.org\"") })
    }

    @Test fun cloudflare_person_moves_to_an_app_pin() {
        Lang.sw = false
        val m = model(); openContact(m, "Jessica Anderson")
        assertTrue(tagged("ct_b_cf") && tagged("ct_cf") && tagged("ct_move") && tagged("ct_cf_note")); assertFalse(tagged("ct_newpin")); assertFalse(tagged("ct_name_row"))
        tag("ct_move").performScrollTo().performClick(); waitTag("ct_ask_sheet"); settle()
        assertFalse(tagged("ct_tech_body")); tag("ct_tech").performScrollTo().performClick(); settle(); assertTrue(tagged("ct_tech_body"))
        tag("ct_yes").performScrollTo().performClick(); settle(1500)
        waitTag("ct_moved"); waitFor("8642"); assertTrue(post("team-move")!!.contains("\"name\":\"Jessica Anderson\""))
        sheetShot("ct-16-moved-en-light")
    }

    // --- myself, and a field officer ---------------------------------------------------------------------------------

    @Test fun own_sheet_has_details_and_change_my_pin_but_no_block_remove_or_admin() {
        Lang.sw = false
        val m = model(); openContact(m, "Reid Anderson")
        listOf("ct_name_row", "ct_email_row", "ct_phone_row", "ct_mypin", "ct_newpin", "ct_signout").forEach { assertTrue(it, tagged(it)) }
        listOf("ct_block", "ct_remove", "ct_admin", "ct_unadmin", "ct_g_contact").forEach { assertFalse(it, tagged(it)) }
        assertTrue(has("YOUR WORK"))
        sheetShot("ct-17-own-sheet-en-light")
        tag("ct_mypin").performScrollTo().performClick(); settle(1500); waitGone("ct_sheet")
        waitFor("Change my PIN"); assertTrue(tagged("hback"))
    }

    @Test fun own_sheet_when_your_pin_is_set_in_cloudflare_edits_the_phone_through_profile() {
        Lang.sw = false; reidSrc = "cloudflare"
        val m = model(); openContact(m, "Reid Anderson")
        listOf("ct_name_ro", "ct_email_ro", "ct_setby", "ct_phone_row", "ct_mypin").forEach { assertTrue(it, tagged(it)) }
        listOf("ct_name_row", "ct_email_row", "ct_cf_note").forEach { assertFalse(it, tagged(it)) }
        assertTrue(has("Set by the office"))
        sheetShot("ct-27-own-sheet-cloudflare-en-light")
        tag("ct_phone_row").performScrollTo().performClick(); waitTag("ct_field_sheet"); settle()
        type("ct_field", "0711 222 333"); tag("ct_field_save").performClick(); settle(1500)
        rule.waitUntil(5000) { post("profile")?.contains("\"phone\":\"0711 222 333\"") == true }
        assertNull(post("team-update"))
    }

    @Test fun an_admin_set_in_cloudflare_cant_be_demoted_here() {
        Lang.sw = false
        val m = model(); openContact(m, "Peter Otieno")
        assertTrue(tagged("ct_admincf") && tagged("ct_alsocf")); assertFalse(tagged("ct_unadmin")); assertFalse(tagged("ct_admin"))
        tag("ct_alsocf").performScrollTo(); sheetShot("ct-28-admin-in-cloudflare-en-light")
        Lang.sw = true; settle(); assertTrue(has("Msimamizi kupitia Cloudflare"))
    }

    @Test fun own_sheet_kiswahili_dark() {
        Lang.sw = true
        val m = model(); openContact(m, "Reid Anderson", dark = true)
        assertTrue(has("Badilisha PIN yangu") && has("KAZI YAKO"))
        sheetShot("ct-18-own-sheet-sw-dark")
    }

    @Test fun field_officer_sees_contact_and_their_work_only() {
        Lang.sw = false
        val m = model(admin = false)
        show(m); waitTag("hav"); settle(2000)
        tag("nav_me").performClick(); settle(1500)
        tag("pane_team").performClick(); settle(1500)
        waitTag("mate_open"); rule.onAllNodesWithTag("mate_open")[0].performClick(); waitTag("ct_sheet"); waitLoaded(); settle(1500)
        listOf("ct_call", "ct_wa", "ct_sms", "ct_share", "ct_home_ro", "ct_jobs", "ct_g_work").forEach { assertTrue(it, tagged(it)) }
        listOf("ct_g_access", "ct_invite", "ct_block", "ct_assign", "ct_name_row", "ct_email_row", "ct_map").forEach { assertFalse(it, tagged(it)) }
        assertFalse(posts.any { it.contains("view=team") })
        sheetShot("ct-19-field-officer-view-en-light")
        // back closes only the sheet
        back(); waitGone("ct_sheet"); assertTrue(tagged("team_today"))
    }

    @Test fun field_officer_view_kiswahili_dark() {
        Lang.sw = true
        val m = model(admin = false)
        show(m, dark = true); waitTag("hav"); settle(2000)
        tag("nav_me").performClick(); settle(1500)
        tag("pane_team").performClick(); settle(1500)
        waitTag("mate_open"); rule.onAllNodesWithTag("mate_open")[0].performClick(); waitTag("ct_sheet"); waitLoaded(); settle(1500)
        assertTrue(has("Piga simu") && has("KAZI YAKE"))
        sheetShot("ct-20-field-officer-view-sw-dark")
    }

    // --- back: one step at a time --------------------------------------------------------------------------------------

    @Test fun back_closes_the_small_sheet_then_the_contact_sheet_then_nothing_else() {
        Lang.sw = false
        val m = model(); openContact(m)
        tag("ct_phone_row").performScrollTo().performClick(); waitTag("ct_field_sheet"); settle()
        back(); waitGone("ct_field_sheet"); assertTrue(tagged("ct_sheet"))
        back(); waitGone("ct_sheet"); assertTrue(tagged("tm_Meril Kiprop")); assertTrue(tagged("office_strip"))
        // X closes too
        tag("tm_Meril Kiprop").performScrollTo().performClick(); waitTag("ct_sheet"); settle()
        tag("ct_x").performClick(); settle(1200); waitGone("ct_sheet")
    }

    // --- every place a person shows opens the same sheet -------------------------------------------------------------

    @Test fun office_feed_review_repairs_visits_and_plan_open_the_contact_sheet() {
        Lang.sw = false
        val m = model(); office(m)
        // Overview › Latest from the field
        waitTag("ocv_feed"); waitTag("ocv_feed_who")
        rule.onAllNodesWithTag("ocv_feed_who")[0].performScrollTo().performClick(); waitTag("ct_sheet"); settle(1200)
        tag("ct_name").assertTextEquals("Meril Kiprop"); back(); waitGone("ct_sheet")
        // a review sheet's "Sent by"
        tag("o_review").performScrollTo().performClick(); settle(1500); waitTag("rr_rev_r1")
        tag("rr_rev_r1").performScrollTo().performClick(); waitTag("rr_rev_sheet"); settle()
        tag("rr_by").performScrollTo().performClick(); waitTag("ct_sheet"); settle(1200)
        tag("ct_name").assertTextEquals("Meril Kiprop")
        sheetShot("ct-21-from-review-sheet")
        back(); waitGone("ct_sheet"); assertTrue(tagged("rr_rev_sheet")); back(); waitGone("rr_rev_sheet")
        // a repair job: reported by, and assigned to
        tag("o_repairs").performScrollTo().performClick(); settle(1500); waitTag("rr_job_105")
        tag("rr_job_105").performScrollTo().performClick(); waitTag("rr_job_sheet"); settle()
        tag("rr_assignee").performScrollTo().performClick(); waitTag("ct_sheet"); settle(1200)
        tag("ct_name").assertTextEquals("Meril Kiprop"); back(); waitGone("ct_sheet")
        tag("rr_reported_by").performScrollTo().performClick(); waitTag("ct_sheet"); back(); waitGone("ct_sheet"); back(); waitGone("rr_job_sheet")
        // Plan a week: the chosen field officer
        tag("o_plan").performScrollTo().performClick(); settle(1500)
        // the chooser is open at first; the chosen person shows once the team has loaded
        waitTag("plan_choices_toggle"); rule.waitUntil(20000) { tagged("plan_who") || !tagged("plan_choices_body") }
        if (!tagged("plan_who")) { tag("plan_choices_toggle").performScrollTo().performClick(); settle() }
        waitTag("plan_who"); tag("plan_who").performScrollTo().performClick(); waitTag("ct_sheet"); back(); waitGone("ct_sheet")
        // Visits › Assign a visit: the chosen person
        tag("o_visits").performScrollTo().performClick(); settle(1500); waitTag("ocv_assign")
        tag("ocv_assign").performScrollTo().performClick(); waitTag("ocv_assign_sheet"); waitTag("ocv_assign_who")
        tag("ocv_assign_who").performScrollTo().performClick(); waitTag("ct_sheet"); back(); waitGone("ct_sheet"); assertTrue(tagged("ocv_assign_sheet"))
    }

    @Test fun me_team_feed_notifications_and_project_history_open_the_contact_sheet() {
        Lang.sw = false
        val m = model()
        show(m); waitTag("hav"); settle(2000)
        // the team feed
        tag("nav_me").performClick(); settle(1500); tag("pane_team").performClick(); settle(1500)
        waitTag("feed_who"); rule.onAllNodesWithTag("feed_who")[0].performScrollTo().performClick(); waitTag("ct_sheet"); settle(1200)
        tag("ct_name").assertTextEquals("Meril Kiprop"); back(); waitGone("ct_sheet")
        // team totals
        waitTag("tm_who_Meril Kiprop"); tag("tm_who_Meril Kiprop").performScrollTo().performClick(); waitTag("ct_sheet"); back(); waitGone("ct_sheet")
        // notifications: the person's picture
        tag("bell").performClick(); settle(1500); waitTag("note_avatar")
        rule.onAllNodesWithTag("note_avatar")[0].performClick(); waitTag("ct_sheet"); settle(1200)
        tag("ct_name").assertTextEquals("Meril Kiprop"); back(); waitGone("ct_sheet")
        // a project: who visited, the repair's reporter, and History's "by"
        rule.runOnUiThread { m.goProject = "105" }; settle(1500)
        waitTag("p_visit_by"); tag("p_visit_by").performScrollTo().performClick(); waitTag("ct_sheet"); back(); waitGone("ct_sheet")
        tag("tab_hist").performScrollTo().performClick(); settle(1500); waitTag("h_by")
        tag("h_by").performScrollTo().performClick(); waitTag("ct_sheet"); settle(1200)
        tag("ct_name").assertTextEquals("Meril Kiprop")
        sheetShot("ct-22-from-project-history")
    }

    // --- a donor contact ----------------------------------------------------------------------------------------------

    private fun openViewer(m: AppModel, vid: String, dark: Boolean = false) {
        office(m, dark)
        tag("o_donors").performScrollTo().performClick(); settle(1500); waitTag("dn_dA")
        tag("dn_dA").performScrollTo().performClick(); waitTag("dn_sheet"); settle()
        tag("dn_viewers_toggle").performScrollTo().performClick(); settle(600)
        tag("dn_v_$vid").performScrollTo().performClick(); waitTag("dv_sheet"); settle(1200)
    }

    @Test fun donor_contact_sheet_english_light() {
        Lang.sw = false
        val m = model(); openViewer(m, "v1")
        tag("dv_name").assertTextEquals("Hope Church")
        listOf("dv_mail", "dv_copy", "dv_share", "dv_code", "dv_invite", "dv_rename", "dv_email", "dv_digest", "dv_rotate", "dv_revoke", "dv_copy_email").forEach { assertTrue(it, tagged(it)) }
        assertFalse(tagged("dv_remove")) // the main contact stays
        sheetShot("ct-23-donor-contact-en-light")
        // copy the private link
        tag("dv_copy").performScrollTo().performClick(); settle(1200)
        rule.waitUntil(5000) { posts.any { it.contains("\"donor-link\"") && it.contains("\"vid\":\"v1\"") } }
        val clip = app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        assertEquals("https://rafikioutreach.org/donor/abc", clip.primaryClip?.getItemAt(0)?.text?.toString())
        // rename
        tag("dv_rename").performScrollTo().performClick(); waitTag("dv_field_sheet"); settle()
        type("dv_field", "Hope Church Kitale"); tag("dv_field_save").performClick(); settle(1500)
        rule.waitUntil(5000) { posts.any { it.contains("\"donor-viewer-update\"") && it.contains("\"vid\":\"v1\"") && it.contains("\"name\":\"Hope Church Kitale\"") && it.contains("\"email\":\"office@hope.org\"") } }
        waitGone("dv_field_sheet")
        // block access asks first
        tag("dv_revoke").performScrollTo().performClick(); waitTag("dv_ask_sheet"); settle()
        tag("dv_yes").performScrollTo().performClick(); settle(1500)
        rule.waitUntil(5000) { posts.any { it.contains("\"donor-revoke\"") && it.contains("\"vid\":\"v1\"") } }
        // monthly e-mails off (for the whole donor)
        tag("dv_digest").performScrollTo().performClick(); waitTag("dv_ask_sheet"); settle()
        tag("dv_yes").performScrollTo().performClick(); settle(1500)
        rule.waitUntil(5000) { posts.any { it.contains("\"donor-update\"") && it.contains("\"digest\":false") && it.contains("\"projects\":[\"101\"]") } }
    }

    @Test fun donor_contact_sheet_kiswahili_dark_and_restore() {
        Lang.sw = true
        val m = model(); openViewer(m, "v2", dark = true)
        assertTrue(tagged("dv_blocked") && tagged("dv_restore") && tagged("dv_remove")); assertFalse(tagged("dv_copy"))
        assertTrue(has("Rudisha ufikiaji") && has("Tuma barua pepe"))
        sheetShot("ct-24-donor-contact-sw-dark")
        tag("dv_restore").performScrollTo().performClick(); waitTag("dv_ask_sheet"); settle()
        tag("dv_yes").performScrollTo().performClick(); settle(1500)
        rule.waitUntil(5000) { posts.any { it.contains("\"donor-viewer-restore\"") && it.contains("\"vid\":\"v2\"") && it.contains("\"id\":\"dA\"") } }
    }

    // --- blocked or signed out by the office -----------------------------------------------------------------------------

    @Test fun blocked_401_signs_the_phone_out_with_a_kind_message() {
        Lang.sw = false
        val m = model()
        show(m); waitTag("hav"); settle(2000)
        listEnd = "blocked"
        rule.runOnUiThread { m.refresh() }
        signedOut(m)
        assertEquals("", Prefs(app).pin)
        waitTag("lock_msg"); assertTrue(has("The office has paused your access to RAFIKI Pin"))
        shot("ct-25-blocked-signed-out-en-light")
    }

    @Test fun signed_out_401_from_any_call_signs_the_phone_out_kiswahili_dark() {
        Lang.sw = true
        val m = model()
        show(m, dark = true); waitTag("hav"); settle(2000)
        // any refused call (here the Office's team list) ends the sign-in
        m.api.transport = { _, _, _, _ -> 401 to "{\"ok\":false,\"reason\":\"signed-out\"}" }
        kotlinx.coroutines.runBlocking { m.api.adminGet("team") }
        assertEquals("signed-out", m.ended)
        signedOut(m)
        waitFor("Ofisi imekutoa kwenye simu hii")
        shot("ct-26-signed-out-sw-dark")
    }

    @Test fun other_401s_do_not_trigger_the_kind_sign_out() {
        val m = model()
        m.api.transport = { _, _, _, _ -> 401 to "{\"ok\":false,\"reason\":\"pin\"}" }
        kotlinx.coroutines.runBlocking { m.api.adminGet("team") }
        assertNull(m.ended)
    }
}

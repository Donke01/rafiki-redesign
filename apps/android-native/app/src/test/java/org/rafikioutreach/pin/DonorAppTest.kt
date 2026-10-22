package org.rafikioutreach.pin

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
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
import org.rafikioutreach.pin.data.AlertWorker
import org.rafikioutreach.pin.data.DVisit
import org.rafikioutreach.pin.data.DonorApi
import org.rafikioutreach.pin.data.DonorEntry
import org.rafikioutreach.pin.data.DonorPrefs
import org.rafikioutreach.pin.data.DonorView
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.data.donorNews
import org.rafikioutreach.pin.data.donorVisitSay
import org.rafikioutreach.pin.ui.RafikiTheme
import org.rafikioutreach.pin.ui.donorInviteText
import org.rafikioutreach.pin.ui.donorMonthSum
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** RAFIKI Pin in donor mode (feat-donorapp): sign-in by code or link, the donor-only home, a project, updates,
 *  settings, revoked, notifications and the staff/donor switch. Screenshots at 360 dp, English and Kiswahili, light and dark. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class DonorAppTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val token = "Q7mF2kLx9PzR4tYc8VbN1sWq6HjD3gUe5AoKi0ZlM_x"
    @Volatile private var revoked = false
    @Volatile private var mailOn = true
    private var extra: JSONObject? = null

    private fun visit(at: String, state: String, users: Int = 0, flow: Int = 0, safe: Boolean = false) = JSONObject().put("at", at).put("state", state).apply { if (users > 0) put("users", users); if (flow > 0) put("flow", flow); if (safe) put("water", "safe") }
    private fun view(): JSONObject {
        val p1 = JSONObject().put("id", "101").put("title", "Kapsaret Borehole").put("type", "Borehole well").put("cat", "water").put("water", true).put("country", "Kenya").put("area", "Kapsaret, Eldoret")
            .put("status", "Completed").put("year", 2025).put("done", "2025-10-05").put("gps", JSONArray().put(0.470123).put(35.250456)).put("depth", 80).put("pump", "Manual").put("photos", JSONArray())
            .put("health", JSONObject().put("state", "working").put("at", "2026-10-14T08:00:00Z")).put("impact", JSONObject().put("people", 1450).put("litresPerDay", 10560).put("years", 1))
            .put("visits", JSONArray().put(visit("2026-10-14T08:00:00Z", "working", 1450, 22, true)).put(visit("2026-04-02T08:00:00Z", "repair", 1300, 9)))
            .put("repairs", JSONArray().put(JSONObject().put("at", "2026-04-02T08:00:00Z").put("problems", JSONArray().put("Pump handle and pivot pins")).put("cost", 5200).put("fixedAt", "2026-04-11T08:00:00Z").put("open", false)))
            .put("stories", JSONArray().put(JSONObject().put("at", "2026-10-14T08:00:00Z").put("quote", "My daughters used to walk two hours for water. Now they are in class before eight.").put("who", "Mary Chebet")))
            .put("gallery", JSONArray().put(JSONObject().put("id", "601").put("at", "2026-10-14T08:00:00Z").put("label", "water flowing")).put(JSONObject().put("id", "603").put("at", "2026-10-14T08:00:00Z").put("label", "the site with people")))
            .put("thanks", JSONArray().put(JSONObject().put("id", "700").put("at", "2026-10-15T10:00:00Z").put("media", "photo").put("caption", "Asante sana from the Kapsaret water committee!")).put(JSONObject().put("id", "701").put("at", "2026-10-16T10:00:00Z").put("media", "video").put("caption", "")))
        val p2 = JSONObject().put("id", "102").put("title", "Ndalat Primary School").put("type", "School").put("cat", "school").put("water", false).put("country", "Kenya").put("area", "Ndalat, Nandi")
            .put("status", "Completed").put("year", 2024).put("done", "2024-03-12").put("gps", JSONArray().put(0.2).put(35.1)).put("photos", JSONArray()).put("impact", JSONObject().put("people", 600).put("pupils", 640))
            .put("visits", JSONArray().put(JSONObject().put("at", "2026-09-18T08:00:00Z").put("state", "working").put("numbers", JSONObject().put("pupils", 640)))).put("repairs", JSONArray()).put("stories", JSONArray()).put("gallery", JSONArray()).put("thanks", JSONArray())
        val p3 = JSONObject().put("id", "104").put("title", "Magara Water Tank").put("type", "Water collection").put("cat", "water").put("water", true).put("country", "Tanzania").put("area", "Magara, Manyara")
            .put("status", "Under construction").put("year", 2026).put("gps", JSONArray().put(-3.71234).put(35.83721)).put("photos", JSONArray()).put("impact", JSONObject().put("people", 300))
            .put("visits", JSONArray().put(JSONObject().put("at", "2026-10-02T08:00:00Z").put("state", "working").put("build", true).put("stage", "Roofing"))).put("repairs", JSONArray()).put("stories", JSONArray()).put("gallery", JSONArray()).put("thanks", JSONArray())
        val ups = JSONArray()
            .put(JSONObject().put("type", "thanks").put("pid", "101").put("at", "2026-10-16T10:00:00Z").put("id", "701").put("media", "video").put("caption", ""))
            .put(JSONObject().put("type", "thanks").put("pid", "101").put("at", "2026-10-15T10:00:00Z").put("id", "700").put("media", "photo").put("caption", "Asante sana from the Kapsaret water committee!"))
            .put(visit("2026-10-14T08:00:00Z", "working", 1450, 22, true).put("type", "visit").put("pid", "101"))
            .put(JSONObject().put("type", "photos").put("pid", "101").put("at", "2026-10-14T08:00:00Z").put("photos", JSONArray().put("601").put("603")).put("n", 2))
            .put(JSONObject().put("type", "story").put("pid", "101").put("at", "2026-10-14T08:00:00Z").put("quote", "My daughters used to walk two hours for water.").put("who", "Mary Chebet"))
            .put(JSONObject().put("type", "visit").put("pid", "104").put("at", "2026-10-02T08:00:00Z").put("state", "working").put("build", true).put("stage", "Roofing"))
            .put(JSONObject().put("type", "visit").put("pid", "102").put("at", "2026-09-18T08:00:00Z").put("state", "working").put("numbers", JSONObject().put("pupils", 640)))
            .put(JSONObject().put("type", "repair").put("pid", "101").put("at", "2026-04-11T08:00:00Z").put("problems", JSONArray().put("Pump handle and pivot pins")).put("fixedAt", "2026-04-11T08:00:00Z"))
        extra?.let { ups.put(it) }
        return JSONObject().put("ok", true).put("donor", JSONObject().put("name", "Grace Community Church").put("org", "Grace Community Church").put("viewer", "Grace Community Church").put("group", false))
            .put("projects", JSONArray().put(p1).put(p2).put(p3)).put("updates", ups)
            .put("totals", JSONObject().put("projects", 3).put("water", 2).put("people", 2350).put("visitsYear", 4))
            .put("me", JSONObject().put("email", true).put("digest", true).put("on", mailOn))
    }
    private val transport: (String, String, Map<String, String>, ByteArray?) -> Pair<Int, String> = { method, url, h, body ->
        val b = body?.let { String(it) } ?: ""
        calls += "$method $url ${h["X-Donor-Token"] ?: ""} $b"
        when {
            url.contains("/api/donor") && b.contains("\"code\"") -> if (b.contains("K7QM4XR9TB")) 200 to JSONObject().put("ok", true).put("token", token).put("name", "Grace Community Church").toString() else 404 to "{\"ok\":false,\"reason\":\"code\"}"
            url.contains("/api/donor") && b.contains("\"mail\"") -> { mailOn = JSONObject(b).optBoolean("on"); 200 to JSONObject().put("ok", true).put("me", JSONObject().put("email", true).put("digest", true).put("on", mailOn)).toString() }
            url.contains("/api/donor") -> if (revoked || h["X-Donor-Token"] != token) 404 to "{\"ok\":false,\"reason\":\"link\"}" else 200 to view().toString()
            url.contains("pin-auth/me") -> 200 to "{\"ok\":true,\"available\":false}"
            url.contains("view=donors") -> 200 to JSONObject().put("ok", true).put("mail", JSONObject().put("ready", true)).put("donors", JSONArray().put(JSONObject().put("id", "dA1").put("name", "Anderson Family").put("email", "anderson@example.org").put("projects", JSONArray().put("101"))
                .put("showPhotos", true).put("photos", JSONObject()).put("digest", true).put("lastOpen", "2026-10-19T18:20:00Z").put("opens", 4).put("revoked", false)
                .put("viewers", JSONArray().put(JSONObject().put("vid", "vA").put("name", "Anderson Family").put("email", "anderson@example.org").put("primary", true).put("active", true).put("code", "K7QM4-XR9TB"))))).toString()
            url.contains("/api/pin") && method == "GET" && !url.contains("?") -> 200 to JSONObject().put("ok", true).put("who", "Reid Anderson").put("admin", true).put("reviewOpen", 0).put("projects", JSONArray()).put("repairs", JSONObject()).put("visits", JSONObject()).put("jobs", JSONArray()).put("assigned", JSONArray()).put("confirmed", JSONObject()).toString()
            else -> 200 to "{\"ok\":true,\"projects\":[],\"activity\":[]}"
        }
    }
    private fun model(staff: Boolean = false): AppModel {
        org.rafikioutreach.pin.ui.Onb.enabled = false
        val app = ApplicationProvider.getApplicationContext<Application>()
        if (staff) Prefs(app).apply { pin = "9999"; name = "Reid Anderson"; saveSnapshot(JSONObject().put("ok", true).put("who", "Reid Anderson").put("admin", true).put("projects", JSONArray()).put("repairs", JSONObject()).put("visits", JSONObject()).put("jobs", JSONArray()).put("assigned", JSONArray()).put("confirmed", JSONObject())) }
        val m = AppModel(app)
        m.api.transport = transport; m.donor.api.transport = transport
        return m
    }
    private fun show(m: AppModel, dark: Boolean) = rule.setContent { RafikiTheme(dark = dark) { Box(Modifier.size(360.dp, 800.dp)) { App(m) } } }
    private fun shot(name: String) {
        rule.mainClock.advanceTimeBy(600); rule.waitForIdle()
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
    private fun waitTag(tag: String) = rule.waitUntil(8000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun waitText(text: String) = rule.waitUntil(8000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    // ---- logic --------------------------------------------------------------------------------------------------
    @Test fun reading_links_and_codes() {
        assertEquals(token, DonorEntry.read("https://www.rafikioutreach.org/donor/$token")?.token)
        assertEquals(token, DonorEntry.read("Open this: https://pin.rafikioutreach.org/pin#donor=$token thanks")?.token)
        assertEquals(token, DonorEntry.read(token)?.token)
        assertEquals("K7QM4XR9TB", DonorEntry.read(" k7qm4-xr9tb ")?.code)
        assertNull(DonorEntry.read("K7QM4-XR9T"))
        assertNull(DonorEntry.read("O7QM4-XR9TB")) // O is never in a code
        assertEquals(token, DonorEntry.tokenFromLink("/pin", "donor=$token"))
        assertEquals(token, DonorEntry.tokenFromLink("/donor/$token", null))
        assertNull(DonorEntry.tokenFromLink("/pin", "p=101"))
    }

    @Test fun view_news_and_words() {
        Lang.sw = false
        val v = DonorView.from(view())
        assertEquals(3, v.projects.size); assertEquals("working", v.project("101")!!.state); assertEquals("building", v.project("104")!!.state)
        assertEquals(2350, v.people); assertEquals(4, v.visitsYear); assertTrue(v.mailOn)
        assertEquals("Working well, about 1,450 people using it, 22 litres a minute, water tested safe.", donorVisitSay(DVisit("x", "working", false, "", 1450, 22.0, "safe", "", 0, 0, 0)))
        val news = donorNews(v, "2026-10-14T09:00:00Z")
        assertEquals(listOf("A thank-you photo from the community", "A thank-you video from the community"), news.map { it.second })
        assertTrue(news.all { it.first == "Kapsaret Borehole" && it.third == "101" })
        assertEquals("3 visits · 2 new photos · 2 thank-yous · 1 story", donorMonthSum(v.updates.filter { it.at.startsWith("2026-10") }.plus(v.updates.filter { it.at.startsWith("2026-09") })))
        Lang.sw = true
        try { assertEquals("Picha 2 mpya", donorNews(v, "2026-10-13T00:00:00Z").first { it.second.startsWith("Picha") }.second) } finally { Lang.sw = false }
        assertTrue(donorInviteText("Grace Church", "K7QM4-XR9TB").let { it.startsWith("Hello Grace,") && it.contains("K7QM4-XR9TB") && it.contains("/app/rafiki-pin.apk") && it.contains("pin.rafikioutreach.org/pin") })
    }

    @Test fun notifications_new_photos_and_updates() = runBlocking {
        Lang.sw = false
        val app = ApplicationProvider.getApplicationContext<Application>()
        val dp = DonorPrefs(app).apply { this.token = this@DonorAppTest.token }
        val api = DonorApi("https://x.example") { dp.token }.also { it.transport = transport }
        // the first check only remembers where things are
        assertTrue(AlertWorker.donorWork(app, Prefs(app), dp, api).isEmpty())
        assertEquals("2026-10-16T10:00:00Z", dp.notifiedAt)
        assertTrue(calls.last().contains("bg=1"))
        extra = JSONObject().put("type", "photos").put("pid", "102").put("at", "2026-10-20T08:00:00Z").put("photos", JSONArray().put("650").put("651").put("652")).put("n", 3)
        val news = AlertWorker.donorWork(app, Prefs(app), dp, api)
        assertEquals(listOf(Triple("Ndalat Primary School", "3 new photos", "102")), news)
        assertTrue(AlertWorker.donorWork(app, Prefs(app), dp, api).isEmpty())
        // revoked: signed out on the phone, kindly told on the next start
        revoked = true
        assertTrue(AlertWorker.donorWork(app, Prefs(app), dp, api).isEmpty())
        assertEquals("", dp.token); assertTrue(dp.gone)
    }

    // ---- screens: sign-in by code, home, a project, updates, settings, revoked --------------------------------------
    private fun tour(sw: Boolean, dark: Boolean) {
        Lang.sw = sw
        val k = (if (sw) "sw" else "en") + "-" + (if (dark) "dark" else "light")
        try {
            val m = model(); show(m, dark)
            waitTag("lock_donor"); rule.onNodeWithTag("lock_donor").performScrollTo()
            shot("70-donor-lock-$k")
            rule.onNodeWithTag("lock_donor").performClick(); waitTag("donor_signin")
            shot("71-donor-signin-$k")
            rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("donor_code"))).performTextInput("ABCDE-FGHJK")
            rule.onNodeWithTag("donor_go").performClick(); waitTag("donor_msg")
            rule.waitUntil(5000) { calls.any { it.contains("ABCDEFGHJK") } }
            rule.mainClock.advanceTimeBy(300); shot("71b-donor-signin-wrong-$k")
            // the right code
            m.donor.prefill = "k7qm4 xr9tb"; waitTag("donor_app")
            assertEquals(token, m.donor.prefs.token)
            val before = calls.size
            waitTag("dcard_101"); shot("72-donor-home-$k")
            // hard rule: no staff tabs, and no staff endpoints are called in donor mode
            listOf("nav_map", "nav_list", "nav_new", "nav_me", "nav_office").forEach { rule.onAllNodesWithTag(it).assertCountEquals(0) }
            rule.onNodeWithTag("dcard_104").performScrollTo(); shot("72b-donor-home-more-$k")
            rule.onNodeWithTag("dcard_101").performScrollTo().performClick(); waitTag("dproject_title")
            shot("73-donor-project-$k")
            rule.onNodeWithTag("dproject_thanks").performScrollTo(); shot("73b-donor-project-thanks-$k")
            rule.onNodeWithTag("dproject_visits").performScrollTo(); shot("73c-donor-project-visits-$k")
            rule.onNodeWithTag("dproject_map").performScrollTo(); shot("73d-donor-project-map-$k")
            rule.onNodeWithTag("dtab_updates").performClick(); waitTag("dmonth_2026-10")
            shot("74-donor-updates-$k")
            rule.onNodeWithTag("dtab_settings").performClick(); waitTag("dset_look")
            shot("75-donor-settings-$k")
            rule.onNodeWithTag("dset_mail").performScrollTo().performClick()
            rule.waitUntil(5000) { calls.any { it.contains("\"action\":\"mail\"") && it.contains("\"on\":false") } }
            rule.onNodeWithTag("dset_out").performScrollTo(); shot("75b-donor-settings-bottom-$k")
            assertTrue(calls.drop(before).none { it.contains("/api/pin") || it.contains("/api/admin") || it.contains("/api/sms") })
            assertTrue(calls.drop(before).filter { it.contains("/api/donor") }.all { it.contains(token) })
            // revoked: the next check signs out with a kind word
            revoked = true
            runBlocking { m.donor.refresh() }
            waitTag("donor_gone"); shot("76-donor-revoked-$k")
            assertEquals("", m.donor.prefs.token)
            rule.onNodeWithTag("donor_again").performClick(); waitTag("donor_signin")
        } finally { Lang.sw = false }
    }
    @Test fun tour_en_light() = tour(false, false)
    @Test fun tour_en_dark() = tour(false, true)
    @Test fun tour_sw_light() = tour(true, false)
    @Test fun tour_sw_dark() = tour(true, true)

    // ---- a phone is either staff or donor ------------------------------------------------------------------------
    @Test fun donor_link_on_a_staff_phone_asks_to_sign_out() {
        Lang.sw = false
        val m = model(staff = true); show(m, false)
        rule.waitForIdle()
        m.donor.prefill = token; m.donor.entry = true
        waitTag("donor_signin")
        waitText("This phone is signed in as RAFIKI staff")
        sheetShot("77-donor-staff-ask-en-light")
        rule.onNodeWithTag("donor_staff_yes").performClick()
        waitTag("donor_app")
        assertEquals("", Prefs(ApplicationProvider.getApplicationContext()).pin); assertTrue(!m.signedIn.value)
        assertEquals(token, m.donor.prefs.token)
        // and signing out of donor mode goes back to the staff sign-in
        rule.onNodeWithTag("dtab_settings").performClick(); waitTag("dset_out")
        rule.onNodeWithTag("dset_out").performScrollTo().performClick(); waitTag("dset_out_yes")
        rule.onNodeWithTag("dset_out_yes").performClick()
        rule.mainClock.advanceTimeBy(1500); waitTag("lock_donor")
        assertEquals("", m.donor.prefs.token)
    }

    @Test fun notification_tap_opens_the_project() {
        Lang.sw = false
        val m = model()
        DonorPrefs(ApplicationProvider.getApplicationContext()).token = token
        val m2 = AppModel(ApplicationProvider.getApplicationContext()).also { it.donor.api.transport = transport; it.api.transport = transport }
        show(m2, false)
        waitTag("dcard_102")
        m2.donor.goProject = "102"; waitTag("dproject_title")
        waitText("Ndalat Primary School")
        assertTrue(m.donor.prefs.token == token)
    }

    @Test fun office_donor_code_sw_dark() {
        Lang.sw = true
        try {
            val m = model(staff = true); show(m, true)
            rule.onNodeWithTag("nav_office").performClick(); rule.waitForIdle()
            rule.onNodeWithTag("o_donors").performScrollTo().performClick()
            waitTag("dn_dA1"); rule.onNodeWithTag("dn_dA1").performScrollTo().performClick()
            waitTag("dn_sheet"); waitTag("dn_code"); rule.onNodeWithTag("dn_invite").performScrollTo()
            sheetShot("78b-office-donor-code-sw-dark")
        } finally { Lang.sw = false }
    }
    @Test fun office_donor_code_and_app_invite() {
        Lang.sw = false
        val m = model(staff = true); show(m, false)
        rule.onNodeWithTag("nav_office").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("o_donors").performScrollTo().performClick()
        waitTag("dn_dA1"); rule.onNodeWithTag("dn_dA1").performScrollTo().performClick()
        waitTag("dn_sheet"); waitTag("dn_code")
        rule.onNodeWithTag("dn_invite").performScrollTo()
        sheetShot("78-office-donor-code-en-light")
        waitText("K7QM4-XR9TB")
        rule.onNodeWithTag("dn_code_copy").performScrollTo().performClick()
        val clip = (ApplicationProvider.getApplicationContext<Application>().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
        rule.waitUntil(5000) { clip.primaryClip?.getItemAt(0)?.text?.toString() == "K7QM4-XR9TB" }
        rule.onNodeWithTag("dn_invite").performClick(); rule.waitForIdle()
        val sent = org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        val inner = sent?.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)
        assertTrue(inner?.getStringExtra(android.content.Intent.EXTRA_TEXT)?.contains("Your donor code: K7QM4-XR9TB") == true)
    }
}

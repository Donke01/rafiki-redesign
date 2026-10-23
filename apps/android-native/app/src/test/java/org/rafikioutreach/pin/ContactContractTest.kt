package org.rafikioutreach.pin

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.data.reasonText
import org.rafikioutreach.pin.ui.Person
import org.rafikioutreach.pin.ui.contactWhy
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app against the server's real reply shapes (feat-conta / feat-contw): src/test/resources/contact-contract.json was
 * recorded by running the server code (functions/api/admin.js, pin.js) in node. The team views, every refusal reason,
 * and the PIN session number (X-Pin-New on a typed PIN, X-Pin-Gen after; "Sign out of all phones" ends it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContactContractTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val fx: JSONObject by lazy { JSONObject(javaClass.classLoader!!.getResource("contact-contract.json").readText()) }
    private fun reply(k: String) = fx.getJSONObject(k)
    private fun body(k: String) = reply(k).getJSONObject("body")
    private fun team(k: String) = body(k).getJSONArray("team").let { a -> (0 until a.length()).map { Person.from(a.getJSONObject(it)) } }

    @After fun reset() { Lang.sw = false }

    @Test fun admin_team_view_reads_every_field() {
        val t = team("teamView")
        val grace = t.first { it.name == "Grace W. Wanjiru" }
        assertEquals("app", grace.src); assertTrue(grace.app); assertEquals("ke", grace.home); assertEquals("grace.w@example.org", grace.email)
        assertEquals("0712 000 111", grace.phone); assertEquals("Field officer, Kitale", grace.role); assertEquals(listOf("Grace Wanjiru"), grace.aka)
        assertTrue("old name still counts", grace.isCalled("Grace Wanjiru"))
        assertEquals("Morgan", grace.blocked?.by); assertEquals("Phone lost", grace.blocked?.reason); assertTrue(grace.blocked!!.at.startsWith("2026-"))
        assertTrue(grace.out != null); assertTrue(grace.lastSeen != null); assertFalse(grace.adminCf); assertFalse(grace.alsoCloudflare)
        val morgan = t.first { it.name == "Morgan" }
        assertTrue(morgan.cloudflare && morgan.admin && morgan.adminCf); assertNull(morgan.blocked); assertNull(morgan.out); assertNull(morgan.lastSeen)
        // after "Move to an app PIN" they're an app person who also still has a Cloudflare entry
        val meril = team("teamViewAfterMove").first { it.name == "Meril" }
        assertTrue(meril.app && meril.alsoCloudflare)
    }

    @Test fun me_team_list_reads_blocked_as_true_or_false_and_your_own_email() {
        val t = team("teamList")
        val grace = t.first { it.name == "Grace W. Wanjiru" }
        assertNull(grace.blocked); assertEquals("grace.w@example.org", grace.email); assertEquals(listOf("Grace Wanjiru"), grace.aka); assertEquals("", grace.src)
        assertEquals("", t.first { it.name == "Meril" }.email)
        // a blocked teammate, as that list says it
        assertTrue(Person.from(JSONObject().put("name", "X").put("blocked", true)).blocked != null)
    }

    @Test fun every_refusal_has_its_own_words_in_both_languages() {
        val reasons = fx.keys().asSequence().filter { it != "_about" }.mapNotNull { k -> reply(k).getJSONObject("body").optString("reason").takeIf { it.isNotBlank() } }.toSet()
        assertTrue(reasons.containsAll(setOf("weak-pin", "pin-taken", "signed-out", "exists", "bad-email", "not-admin", "blocked", "self", "cloudflare", "not-cloudflare")))
        for (sw in listOf(false, true)) {
            Lang.sw = sw
            val generic = reasonText("something-else")
            (reasons + setOf("not-in-app", "last-admin")).forEach { r -> assertNotEquals("$r (sw=$sw)", generic, contactWhy(r)) }
        }
    }

    @Test fun replies_carry_what_the_app_reads() {
        assertTrue(body("teamAdd").getString("pin").matches(Regex("\\d{4,8}")))
        assertTrue(body("move").getString("pin").matches(Regex("\\d{4,8}")))
        assertFalse("a typed PIN isn't sent back", body("setPinOk").has("pin"))
        assertEquals("Grace W. Wanjiru", body("rename").getString("name"))
        assertEquals(401, reply("listOldGen").getInt("status")); assertEquals(401, reply("listBlocked").getInt("status"))
        assertEquals(1, body("signout").getInt("gen")); assertEquals(0, body("listFirst").getInt("gen")); assertEquals(1, body("listNewSignIn").getInt("gen"))
    }

    /** The phone side of "Sign out of all phones": X-Pin-New on a typed PIN, then X-Pin-Gen with the session number. */
    @Test fun pin_session_number_round_trip() {
        Prefs(app).apply { pin = ""; name = ""; pinGen = "" }
        val m = AppModel(app)
        val heads = mutableListOf<Map<String, String>>()
        var next = "listFirst"
        fun list(k: String) = JSONObject(body(k).toString()).put("projects", JSONArray()).put("repairs", JSONObject()).put("visits", JSONObject())
        m.api.transport = { _, url, h, _ ->
            if (url.contains("/api/pin") && !url.contains("pin-auth") && !url.contains("?")) {
                heads += h; val r = reply(next); r.getInt("status") to (if (r.getInt("status") == 200) list(next) else r.getJSONObject("body")).toString()
            } else 200 to "{\"ok\":true}"
        }
        // a PIN typed on the lock screen starts a new session and keeps its number
        assertNull(runBlocking { m.signIn("58204", "") })
        assertEquals("1", heads.last()["X-Pin-New"]); assertNull(heads.last()["X-Pin-Gen"]); assertEquals("58204", heads.last()["X-Pin"])
        assertEquals("0", Prefs(app).pinGen)
        // later requests send the number back
        runBlocking { m.api.list() }
        assertEquals("0", heads.last()["X-Pin-Gen"]); assertNull(heads.last()["X-Pin-New"])
        // an admin signed them out of all phones: the old number is refused, the phone signs out kindly
        next = "listOldGen"
        runBlocking { m.api.list() }
        assertEquals("signed-out", m.ended)
        repeat(20) { if (m.signedIn.value) { Thread.sleep(20); org.robolectric.shadows.ShadowLooper.idleMainLooper() } }
        assertFalse(m.signedIn.value); assertEquals("", Prefs(app).pin); assertEquals("", Prefs(app).pinGen)
        // typing the PIN again starts a new session with the new number
        next = "listNewSignIn"
        assertNull(runBlocking { m.signIn("58204", "") })
        assertEquals("1", heads.last()["X-Pin-New"]); assertEquals("1", Prefs(app).pinGen)
        // blocked: sign-in is refused with the kind words
        next = "listBlocked"
        Lang.sw = false
        assertEquals(reasonText("blocked"), runBlocking { m.signIn("58204", "") })
    }
}

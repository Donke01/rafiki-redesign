package org.rafikioutreach.pin

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.rafikioutreach.pin.data.Fix
import org.rafikioutreach.pin.data.LatLng
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.ui.MapSetup
import org.rafikioutreach.pin.ui.OfflineMaps
import org.rafikioutreach.pin.ui.RafikiTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * Google Play (feat-play):
 *  - Android 16 (targetSdk 36) draws every app edge to edge with no way out, so each screen is shown at 360 dp with a
 *    status bar (24 dp) and a 3-button navigation bar (48 dp) handed to the app, and nothing it shows may sit under
 *    them. Pictures "e2e-…" (the bars are drawn in dark red so anything under them would show).
 *  - The store's phone screenshots: real screens at 1080 × 1920 with made-up demo projects and people ("play-…").
 * The people and phone numbers here are invented.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h760dp-xxhdpi")
class PlayStoreTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @After fun reset() { Lang.sw = false; org.rafikioutreach.pin.ui.Motion.on = true; org.rafikioutreach.pin.ui.Whoosh.enabled = true; org.rafikioutreach.pin.ui.ChatNav.req = null; org.rafikioutreach.pin.ui.ChatNav.focus = null; org.rafikioutreach.pin.ui.Onb.enabled = true; org.rafikioutreach.pin.ui.MapClean.revealed = false; org.rafikioutreach.pin.ui.HomeLayers.reset() }

    // --- demo data: projects around Eldoret and Kitale, invented people ---------------------------------------------
    /** [d] days ago at 10:30 in the morning (a usual time for a site visit) */
    private fun ago(d: Long) = LocalDate.now().minusDays(d).atTime(10, 30).atZone(java.time.ZoneId.systemDefault()).toInstant().let { if (it.isAfter(Instant.now())) Instant.now().minusSeconds(600) else it }.toString()
    private fun proj(id: String, t: String, area: String, type: String, lat: Double?, lng: Double?, pump: String = "") = JSONObject().put("id", id).put("title", t)
        .put("country", "Kenya").put("ck", "ke").put("area", area).put("type", type).put("status", "Completed").put("pump", pump)
        .apply { if (lat != null) put("gps", JSONArray().put(lat).put(lng)) }
    private fun visit(d: Long, by: String, state: String = "working") = JSONObject().put("at", ago(d)).put("by", by).put("state", state)
    private fun snapshot() = JSONObject().put("ok", true).put("who", "Amani Wekesa").put("admin", true).put("reviewOpen", 2)
        .put("me", JSONObject().put("kind", "app").put("home", "ke"))
        .put("projects", JSONArray()
            .put(proj("101", "Kapsaret Borehole", "Eldoret", "Borehole well", 0.4702, 35.2511, "manual"))
            .put(proj("102", "Chepkongi Dispensary", "Eldoret", "Health", 0.5143, 35.2698))
            .put(proj("103", "Turbo Spring", "Turbo", "Water collection", 0.6301, 35.0502))
            .put(proj("104", "Nzoia Bridge Primary School", "Bungoma", "School", 0.5803, 34.6207))
            .put(proj("105", "Tamiha Children’s Home", "Kitale", "Orphanage", 1.0154, 35.0021))
            .put(proj("106", "Kaptebee Farm", "Nandi", "Farming", 0.2012, 35.1033))
            .put(proj("107", "Moi’s Bridge Borehole", "Eldoret", "Borehole well", 0.8711, 35.1204, "manual"))
            .put(proj("108", "Kiplombe Water Kiosk", "Eldoret", "Water collection", 0.5419, 35.2902))
            .put(proj("109", "Kapsaret Primary School", "Eldoret", "School", 0.4751, 35.2552))
            .put(proj("110", "Sosiot Well", "Kericho", "Borehole well", null, null)))
        .put("repairs", JSONObject()
            .put("101", JSONObject().put("state", "notworking").put("by", "Daniel Kiprono").put("at", ago(2)).put("urgency", "urgent").put("note", "Pump handle broken").put("t", "Kapsaret Borehole").put("cost", 4500))
            .put("107", JSONObject().put("state", "repair").put("by", "Grace Achieng").put("at", ago(9)).put("urgency", "month").put("note", "Slow flow").put("t", "Moi’s Bridge Borehole")))
        .put("visits", JSONObject().put("101", visit(2, "Daniel Kiprono", "notworking")).put("102", visit(6, "Grace Achieng")).put("103", visit(20, "Amani Wekesa"))
            .put("105", visit(12, "Faith Njeri")).put("106", visit(4, "Grace Achieng")).put("107", visit(9, "Grace Achieng", "repair")).put("109", visit(40, "Daniel Kiprono")))
        .put("jobs", JSONArray())
        .put("assigned", JSONArray())
        .put("confirmed", JSONObject())
    private val team = JSONArray()
        .put(JSONObject().put("name", "Amani Wekesa").put("home", "ke").put("admin", true).put("source", "app").put("lastSeen", ago(0)))
        .put(JSONObject().put("name", "Grace Achieng").put("home", "ke").put("admin", false).put("source", "app").put("lastSeen", ago(1)))
        .put(JSONObject().put("name", "Daniel Kiprono").put("home", "ke").put("admin", false).put("source", "app").put("lastSeen", ago(2)))
        .put(JSONObject().put("name", "Faith Njeri").put("home", "ke").put("admin", false).put("source", "app").put("lastSeen", ago(3)))
    private fun activity() = JSONArray()
        .put(JSONObject().put("by", "Amani Wekesa").put("a", "visit").put("s", "working").put("id", "103").put("t", "Turbo Spring").put("at", ago(1)))
        .put(JSONObject().put("by", "Amani Wekesa").put("a", "pin").put("id", "108").put("t", "Kiplombe Water Kiosk").put("at", ago(3)))
        .put(JSONObject().put("by", "Amani Wekesa").put("a", "visit").put("s", "working").put("id", "109").put("t", "Kapsaret Primary School").put("at", ago(5)))
        .put(JSONObject().put("by", "Daniel Kiprono").put("a", "visit").put("s", "notworking").put("id", "101").put("t", "Kapsaret Borehole").put("at", ago(2)))
        .put(JSONObject().put("by", "Grace Achieng").put("a", "visit").put("s", "working").put("id", "106").put("t", "Kaptebee Farm").put("at", ago(4)))
        .put(JSONObject().put("by", "Grace Achieng").put("a", "visit").put("s", "working").put("id", "102").put("t", "Chepkongi Dispensary").put("at", ago(6)))

    /** Chat (feat-chata) with the demo team: a group about a repair, a direct message, the whole team. */
    /** Yesterday at [h]:[m], for the chat messages (a working afternoon whatever time the test runs). */
    private fun yday(h: Int, m: Int) = LocalDate.now().minusDays(1).atTime(h, m).atZone(java.time.ZoneId.systemDefault()).toInstant().toString()
    private fun chats(): FakeChat = FakeChat("Amani Wekesa").apply {
        chat("t_all", "team", "All RAFIKI", listOf("Amani Wekesa", "Grace Achieng", "Daniel Kiprono", "Faith Njeri"), listOf("Amani Wekesa"), pinned = true)
        chat("g1", "group", "Kapsaret repairs", listOf("Amani Wekesa", "Grace Achieng", "Daniel Kiprono"), listOf("Amani Wekesa"), unread = 2)
        chat("dm_faith", "dm", "Faith Njeri", listOf("Amani Wekesa", "Faith Njeri"), unread = 1)
        chat("p_101", "project", "Kapsaret Borehole", listOf("Amani Wekesa", "Daniel Kiprono"), project = "101")
        msg("t_all", "Amani Wekesa", "Good morning team! Visits for this week are in Trips.", 60 * 24 * 2) { put("at", yday(7, 45)) }
        msg("g1", "Daniel Kiprono", "The pump handle at Kapsaret Borehole is broken. The community is fetching water from the river.", 60 * 5) { put("at", yday(14, 10)) }
        msg("g1", "Amani Wekesa", "Thanks Daniel. Can you take a new handle on Thursday?", 60 * 4 + 40, status = "read") { put("at", yday(14, 32)) }
        msg("g1", "Grace Achieng", "I have a spare handle in the Kitale store", 60 * 3) { put("at", yday(15, 5)); put("reacts", JSONObject().put("👍", JSONArray().put("Amani Wekesa").put("Daniel Kiprono"))) }
        msg("g1", "Daniel Kiprono", "Great, I’ll collect it tomorrow at 8", 50) { put("at", yday(15, 21)) }
        msg("g1", "Amani Wekesa", "Perfect. Please send a photo once it’s fixed", 20, status = "delivered") { put("at", yday(15, 40)) }
        msg("dm_faith", "Faith Njeri", "Tamiha Children’s Home visit done, water is clear ✓", 35) { put("at", yday(16, 15)) }
        msg("p_101", "Daniel Kiprono", "Reported: not working", 60 * 6) { put("at", yday(13, 50)) }
        presence.put("Grace Achieng", JSONObject().put("online", true).put("seen", ago(0))).put("Daniel Kiprono", JSONObject().put("online", false).put("seen", ago(0)))
    }
    private val posts = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun model(signedIn: Boolean = true): AppModel {
        org.rafikioutreach.pin.ui.Motion.on = false
        org.rafikioutreach.pin.ui.Whoosh.enabled = false
        java.io.File(app.filesDir, "chat").deleteRecursively()
        listOf("chat", "chat_drafts", "chat_notes").forEach { app.getSharedPreferences(it, 0).edit().clear().commit() }
        MapSetup.init(app); OfflineMaps.setOnline(true)
        Prefs(app).apply {
            if (signedIn) { pin = "2580"; name = "Amani Wekesa"; saveSnapshot(snapshot()) } else { pin = ""; name = "" }
            filter = "all"; country = ""; ftype = ""; lang = if (Lang.sw) "sw" else "en"; phoneAlerts = false; mePane = "me"
        }
        org.rafikioutreach.pin.ui.clearDrafts()
        app.getSharedPreferences("rafiki-pin", 0).edit().putString("currency", "KES").commit()
        val m = AppModel(app)
        val chat = chats()
        // Trips (feat-itina): a two-day trip for the demo team, from the trip test's fake server
        val trips = TripFixtures.Server(posts, TripFixtures.itin(people = listOf("Amani Wekesa", "Grace Achieng")).put("title", "Kapsaret and Moiben repairs"), "Amani Wekesa", true)
        m.api.transport = { method, url, _, body ->
            chat.handle(method, url, body) ?: when {
                Regex("/api/(itin|vehicles|fuel|trip|group-trips|road-reports)").containsMatchIn(url) -> trips.answer(method, url, body?.let { String(it) } ?: "")
                url.contains("view=team") || url.contains("team=1") -> 200 to JSONObject().put("ok", true).put("team", team).toString()
                url.contains("view=review") -> 200 to JSONObject().put("ok", true).put("items", JSONArray()).toString()
                url.contains("view=") -> 200 to "{\"ok\":true,\"items\":[]}"
                url.contains("activity=1") -> 200 to JSONObject().put("ok", true).put("activity", activity()).toString()
                url.contains("history=") -> 200 to JSONObject().put("ok", true).put("notes", JSONArray()
                    .put(JSONObject().put("id", "1").put("text", "Site visit by Daniel Kiprono (RAFIKI pin app): Not working. Notes: Pump handle broken, the community fetches water from the river.").put("at", ago(2)).put("by", "Daniel Kiprono"))).toString()
                url.contains("pin-auth/me") -> 200 to "{\"ok\":true,\"available\":false}"
                url.contains("version.json") -> 0 to ""
                url.contains("/api/place") -> 200 to "{\"ok\":true,\"places\":[]}"
                url.contains("?id=") -> 200 to "{\"ok\":true,\"last\":null,\"canUndo\":false,\"visits\":[]}"
                method == "GET" -> 200 to snapshot().toString()
                else -> 200 to "{\"ok\":true,\"updateId\":\"9001\"}"
            }
        }
        m.chat.wire = chat.Wire()
        // standing at Kapsaret Borehole, the project the screenshots open
        m.locator.pretend(Fix(LatLng(0.4703, 35.2510), 8, System.currentTimeMillis()))
        return m
    }

    // --- helpers ---------------------------------------------------------------------------------------------------
    private fun settle() { rule.mainClock.advanceTimeBy(900); rule.waitForIdle() }
    private fun tagged(t: String) = rule.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty()
    private fun has(t: String) = rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun nav(k: String) {
        // Me is the picture at the top left; a screen without it (a chat, a project) goes back first, as a person would
        if (k == "me") repeat(4) { if (!tagged("hav")) { rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; settle() } }
        rule.onNodeWithTag(if (k == "me") "hav" else "nav_$k").performClick(); settle()
    }
    private fun waitTag(t: String) = rule.waitUntil(8000) { tagged(t) }
    private fun openChat(id: String) { nav("chat"); waitTag("chatrow_$id"); rule.onNodeWithTag("chatrow_$id").performClick(); waitTag("chat_room"); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle() }
    private fun openTrip() { nav("me"); waitTag("trip_row_it_1"); rule.onNodeWithTag("trip_row_it_1").performScrollTo().performClick(); settle(); waitTag("trip_screen"); settle() }
    private fun density() = rule.activity.resources.displayMetrics.density
    private fun bitmap(): Bitmap { rule.waitForIdle(); val v = rule.activity.window.decorView; return Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) } }
    private fun save(b: Bitmap, name: String) = File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }

    /** The status bar ([top] dp) and navigation bar ([bottom] dp) that Android 15+ hands an edge-to-edge app. */
    private fun bars(top: Int = 24, bottom: Int = 48) {
        rule.runOnUiThread {
            val d = density()
            val cv = rule.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val ins = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, (top * d).toInt(), 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, (bottom * d).toInt())).build()
            ViewCompat.setOnApplyWindowInsetsListener(cv) { _, _ -> ins }
            ViewCompat.dispatchApplyWindowInsets(cv, ins)
        }
        settle()
    }

    /** Everything on screen (with a size) that reaches under the bars: none may. */
    private fun underBars(top: Int = 24, bottom: Int = 48): List<String> {
        val d = density()
        val root = rule.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().first()
        val h = root.size.height.toFloat(); val t = top * d - 0.5f; val b = h - bottom * d + 0.5f
        val bad = mutableListOf<String>()
        fun walk(n: SemanticsNode) {
            val r = n.boundsInRoot
            if (r.width > 1 && r.height > 1 && (r.top < t || r.bottom > b)) {
                val what = n.config.getOrNull(SemanticsProperties.TestTag) ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
                    ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: "node ${n.id}"
                bad += "$what ${r.top.toInt()}..${r.bottom.toInt()} (safe ${t.toInt()}..${b.toInt()})"
            }
            n.children.forEach { walk(it) }
        }
        root.children.forEach { walk(it) }
        return bad
    }

    /** The screen with the bars drawn on it in dark red, so anything under them shows. */
    private fun e2eShot(name: String, top: Int = 24, bottom: Int = 48) {
        settle()
        val bm = bitmap(); val c = Canvas(bm); val d = density()
        val p = Paint().apply { color = 0xCC7A0010.toInt() }
        c.drawRect(0f, 0f, bm.width.toFloat(), top * d, p)
        c.drawRect(0f, bm.height - bottom * d, bm.width.toFloat(), bm.height.toFloat(), p)
        save(bm, name)
    }

    private fun check(name: String) {
        val bad = underBars()
        e2eShot(name)
        assertEquals("$name: nothing under the status or navigation bar", emptyList<String>(), bad)
    }

    // --- Android 16: edge to edge on every screen ------------------------------------------------------------------
    @Test fun every_screen_stays_clear_of_the_system_bars() {
        Lang.sw = false
        org.rafikioutreach.pin.ui.Onb.enabled = false
        val m = model()
        rule.setContent { RafikiTheme(dark = false) { App(m) } }
        rule.waitUntil(8000) { tagged("nav_map") }; settle()
        bars()
        check("e2e-01-projects")
        nav("map"); rule.waitUntil(5000) { tagged("map_home") }; org.rafikioutreach.pin.ui.MapClean.revealed = true; settle()
        check("e2e-02-map")
        nav("list")
        rule.onNodeWithTag("projects").performScrollToNode(hasText("Kapsaret Borehole")); rule.onNodeWithText("Kapsaret Borehole").performClick(); settle()
        check("e2e-03-project")
        rule.onNodeWithTag("tab_visit").performScrollTo().performClick(); settle()
        rule.onNodeWithText("I’ve arrived").performScrollTo().performClick(); settle()
        check("e2e-04-visit")
        nav("new"); check("e2e-05-new-site")
        nav("me"); check("e2e-06-me")
        nav("office"); rule.waitUntil(5000) { has("Kapsaret Borehole") }; check("e2e-07-office")
        nav("chat"); waitTag("chatrow_g1"); check("e2e-10-chat-list")
        openChat("g1"); check("e2e-11-chat-room")
        openTrip(); check("e2e-12-trip")
        nav("me"); rule.onNodeWithTag("set_about").performScrollTo().performClick(); settle()
        check("e2e-08-about")
    }

    @Test fun sign_in_and_welcome_stay_clear_of_the_system_bars() {
        Lang.sw = true
        org.rafikioutreach.pin.ui.Onb.enabled = true
        val m = model(signedIn = false)
        rule.setContent { RafikiTheme(dark = true) { App(m) } }
        rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        bars()
        check("e2e-09-welcome-sw-dark")
        org.rafikioutreach.pin.ui.Onb.enabled = false
    }

    /** Demo data only: no test ids, no phone numbers, no one's e-mail (the office's public info@ address is fine). */
    private fun demoOnly() {
        val texts = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }
        val bad = texts.filter { Regex("vh_|it_1|\\b(Meril|Reid|Bertin|Jessica|Wanjiru)\\b|\\+?254\\d|\\b07\\d{2}\\s?\\d{3}|[A-Za-z0-9._-]+@[A-Za-z0-9.-]+").containsMatchIn(it) && !it.contains("info@rafikioutreach.org") }
        assertEquals("only made-up demo data on the store screenshots", emptyList<String>(), bad)
    }

    // --- Google Play: phone screenshots, 1080 × 1920 (360 × 640 dp at xxhdpi) ---------------------------------------
    private fun findMap(v: android.view.View): MapView? {
        if (v is MapView && v.isAttachedToWindow) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findMap(v.getChildAt(i))?.let { return it }
        return null
    }

    @Config(qualifiers = "w360dp-h640dp-xxhdpi")
    @Test fun store_screenshots() {
        Lang.sw = false
        org.rafikioutreach.pin.ui.Onb.enabled = false
        val m = model()
        rule.setContent { RafikiTheme(dark = false) { App(m) } }
        rule.waitUntil(8000) { tagged("nav_map") }; settle()
        val size = bitmap().let { it.width to it.height }
        assertEquals("a 9:16 phone screenshot", 1080 to 1920, size)

        // 1. the map: every project, coloured by how it is doing (satellite tiles when there is a network)
        nav("map"); rule.waitUntil(5000) { tagged("map_home") }
        org.rafikioutreach.pin.ui.MapClean.revealed = true; settle()
        val mv = findMap(rule.activity.window.decorView)!!
        rule.runOnUiThread { mv.controller.setZoom(9.4); mv.controller.setCenter(GeoPoint(0.66, 35.0)) }
        repeat(8) { settle(); bitmap(); rule.runOnUiThread { mv.invalidate() }; Thread.sleep(500) }
        save(bitmap(), "play-1-map"); demoOnly()

        // 2. projects, 3. a project with its repair job
        nav("list"); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        save(bitmap(), "play-2-projects"); demoOnly()
        rule.onNodeWithTag("projects").performScrollToNode(hasText("Kapsaret Borehole")); rule.onNodeWithText("Kapsaret Borehole").performClick(); settle()
        save(bitmap(), "play-3-project"); demoOnly()

        // 4. report a visit
        rule.onNodeWithTag("tab_visit").performScrollTo().performClick(); settle()
        rule.onNodeWithText("I’ve arrived").performScrollTo().performClick(); settle()
        rule.onAllNodes(hasText("Not working") and hasClickAction()).onFirst().performScrollTo().performClick(); settle()
        save(bitmap(), "play-4-visit"); demoOnly()

        // 5. team chat: a group about the repair
        openChat("g1"); save(bitmap(), "play-5-chat"); demoOnly()

        // 6. a planned trip: stops, roads and the day plan
        openTrip(); save(bitmap(), "play-6-trip"); demoOnly()

        // 7. the office: repairs, review and the team
        nav("office"); rule.waitUntil(5000) { has("Kapsaret Borehole") }; settle()
        save(bitmap(), "play-7-office"); demoOnly()

        // 8. Me: this month's work and my trips
        nav("me"); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        save(bitmap(), "play-8-me"); demoOnly()

    }
}

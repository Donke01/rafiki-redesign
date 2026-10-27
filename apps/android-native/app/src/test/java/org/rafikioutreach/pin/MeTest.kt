package org.rafikioutreach.pin

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.rafikioutreach.pin.data.Api
import org.rafikioutreach.pin.data.AvatarImage
import org.rafikioutreach.pin.data.Fix
import org.rafikioutreach.pin.data.LatLng
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.data.TeamPhotos
import org.rafikioutreach.pin.ui.CentreLayout
import org.rafikioutreach.pin.ui.Contact
import org.rafikioutreach.pin.ui.Look
import org.rafikioutreach.pin.ui.Motion
import org.rafikioutreach.pin.ui.Onb
import org.rafikioutreach.pin.ui.PhotoPick
import org.rafikioutreach.pin.ui.RafikiTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant

/**
 * feat-mea: the labelled Me pill (top left), the one-time "Me is here now" hint, profile photos (add, change, remove;
 * shown everywhere; cached; initials as the fallback) and the centred column on a tablet. Screenshots "me-…".
 * The server is a fake that follows S/me-contract.md.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class MeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @After fun reset() { Lang.sw = false; Motion.on = true; Onb.enabled = true; Look.mode = ""; CentreLayout.on = true; PhotoPick.fake = null; Contact.who = null; TeamPhotos.urls.clear(); TeamPhotos.off = false }

    private val ME = "Reid Anderson"
    private val GRACE = "Grace Achieng"
    private fun url(n: String, v: String) = "/api/team-photo?name=" + Api.enc(n).replace("+", "%20") + "&v=" + v

    /** A small "photo": a coloured background and a face-like circle, as JPEG. */
    private fun face(bg: Int, fg: Int, w: Int = 300, h: Int = 300): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(bg); p.color = fg; c.drawCircle(w / 2f, h * 0.42f, minOf(w, h) * 0.22f, p); c.drawCircle(w / 2f, h * 1.05f, minOf(w, h) * 0.42f, p)
        return b
    }
    private fun jpeg(b: Bitmap) = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()

    private fun proj(i: Int) = JSONObject().put("id", "${100 + i}").put("title", "Site ${100 + i} Borehole").put("country", "Kenya").put("ck", "ke").put("area", "Eldoret")
        .put("type", "Borehole well").put("status", "Completed").put("pump", "manual").put("gps", JSONArray().put(0.40 + i * 0.012).put(35.10 + i * 0.011))
    private var myPhoto = ""
    private fun snapshot(admin: Boolean = true) = JSONObject().put("ok", true).put("who", ME).put("admin", admin).put("reviewOpen", 0)
        .put("me", JSONObject().put("kind", "app").put("photo", myPhoto))
        .put("photos", JSONObject().put(GRACE, url(GRACE, "g1")).apply { if (myPhoto.isNotBlank()) put(ME, myPhoto) })
        .put("projects", JSONArray().apply { (1..12).forEach { put(proj(it)) } })
        .put("repairs", JSONObject()).put("visits", JSONObject()).put("jobs", JSONArray()).put("assigned", JSONArray()).put("confirmed", JSONObject())
    private fun team() = JSONArray()
        .put(JSONObject().put("name", ME).put("home", "ke").put("admin", true).put("source", "app").put("lastSeen", Instant.now().toString()).put("photo", myPhoto))
        .put(JSONObject().put("name", GRACE).put("home", "ke").put("phone", "+254 712 000 111").put("source", "app").put("lastSeen", Instant.now().toString()).put("photo", url(GRACE, "g1")))
        .put(JSONObject().put("name", "Bertin Mwangi").put("home", "tz").put("source", "app").put("photo", ""))

    /** What the fake server was sent: method, path, content type and body size. */
    private val sent = mutableListOf<Triple<String, String, Pair<String, Int>>>()
    private fun model(admin: Boolean = true, onb: Boolean = false, hintSeen: Boolean = true): AppModel {
        Onb.enabled = onb; Motion.on = false
        Prefs(app).apply { pin = "9999"; name = ME; saveSnapshot(snapshot(admin)); filter = "all"; country = ""; ftype = ""; this.onb = true; lang = if (Lang.sw) "sw" else "en"; mePane = "me"; phoneAlerts = false; meHint = hintSeen }
        val m = AppModel(app)
        m.api.transport = { method, u, h, body ->
            val path = u.removePrefix(m.api.server)
            when {
                path.startsWith("/api/team-photo") -> {
                    sent += Triple(method, path, (h["Content-Type"] ?: "") to (body?.size ?: 0))
                    val who = Regex("name=([^&]+)").find(path)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ME
                    val remove = body != null && String(body).contains("\"remove\"")
                    val p = if (remove) "" else url(who, "n" + sent.size)
                    if (who == ME) myPhoto = p
                    200 to JSONObject().put("ok", true).put("name", who).put("photo", p).toString()
                }
                u.contains("activity=1") -> 200 to "{\"ok\":true,\"activity\":[]}"
                u.contains("team=1") || u.contains("view=team") -> 200 to JSONObject().put("ok", true).put("team", team()).toString()
                u.contains("view=") -> 200 to "{\"ok\":true,\"items\":[],\"people\":[],\"visits\":[]}"
                u.contains("pin-auth/me") -> 200 to "{\"ok\":true,\"available\":false}"
                u.contains("version.json") || u.contains("/api/chat") -> 0 to ""
                method == "GET" -> 200 to snapshot(admin).toString()
                else -> 200 to "{\"ok\":true}"
            }
        }
        // the photos themselves: Grace in green, anyone else in purple; signed-in requests only
        m.api.fetch = { u, h ->
            if (h["X-Pin"] != "9999") 401 to null
            else if (u.contains("name=Grace")) 200 to jpeg(face(0xFF1C9C5A.toInt(), 0xFFFFE0B8.toInt()))
            else 200 to jpeg(face(0xFF7A4FD0.toInt(), 0xFFFFD2A8.toInt()))
        }
        m.locator.pretend(Fix(LatLng(0.4150, 35.1100), 8, System.currentTimeMillis()))
        return m
    }

    private var w by mutableIntStateOf(360)
    private var h by mutableIntStateOf(780)
    private var dark by mutableStateOf(false)
    private fun start(m: AppModel) {
        rule.setContent { RafikiTheme(dark = dark) { Box(Modifier.size(w.dp, h.dp)) { App(m) } } }
        rule.waitUntil(10000) { tagged("hav") }; settle(1500)
    }

    // --- helpers -------------------------------------------------------------------------------------------------
    private fun tagged(t: String) = rule.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitTag(t: String) = rule.waitUntil(10000) { tagged(t) }
    private fun settle(ms: Long = 800) { rule.mainClock.advanceTimeBy(ms); rule.waitForIdle() }
    private fun tap(t: String) { rule.onNodeWithTag(t).let { n -> runCatching { n.performScrollTo() }; n.performClick() }; settle() }
    private fun bounds(t: String): Rect = rule.onNodeWithTag(t, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun shot(name: String) {
        settle()
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); val c = Canvas(b); v.draw(c)
        val d = (ShadowDialog.getShownDialogs().lastOrNull { it.isShowing })?.window?.decorView
        if (d != null && d.width > 0) { c.drawColor(0x66000000); c.save(); c.translate(0f, (v.height - d.height).toFloat().coerceAtLeast(0f)); d.draw(c); c.restore() }
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    /** The header's three parts sit side by side: Me | wordmark | bell, nothing overlapping, nothing cut off. */
    private fun headerClear(label: String) {
        val me = bounds("hav"); val mark = bounds("wordmark"); val bell = bounds("bell")
        val d = rule.density.density
        assertTrue("$label: Me ${me} runs into the wordmark ${mark}", me.right <= mark.left)
        assertTrue("$label: the wordmark ${mark} runs into the bell ${bell}", mark.right <= bell.left)
        assertTrue("$label: the bell is cut off", bell.right <= w * d + 0.5f)
        assertTrue("$label: Me is under 44 dp tall (${me.height / d})", bounds("nav_me").height / d >= 44f - 0.01f)
    }

    // --- 1. photos: the data side (no screen) ------------------------------------------------------------------------
    @Test fun photo_urls_cache_and_shrinking() {
        val api = Api("https://pin.test", { "1234" })
        TeamPhotos.urls.clear(); TeamPhotos.init(app, api)
        // the app's list: "photos" for everyone, me.photo for me
        TeamPhotos.absorb("/api/pin", JSONObject().put("who", ME).put("projects", JSONArray()).put("photos", JSONObject().put(GRACE, url(GRACE, "a1")))
            .put("me", JSONObject().put("photo", url(ME, "b2"))))
        assertEquals(url(GRACE, "a1"), TeamPhotos.url(" grace ACHIENG "))
        assertEquals(url(ME, "b2"), TeamPhotos.url(ME))
        assertNull(TeamPhotos.url("Bertin Mwangi"))
        // a team list and chat presence carry them too; "" means none now
        TeamPhotos.absorb("/api/pin?team=1", JSONObject().put("team", JSONArray().put(JSONObject().put("name", "Bertin Mwangi").put("photo", "/api/team-photo?name=Bertin&v=1"))))
        assertNotNull(TeamPhotos.url("Bertin Mwangi"))
        TeamPhotos.absorb("/api/chat/list", JSONObject().put("presence", JSONObject().put("Bertin Mwangi", JSONObject().put("online", true).put("photo", ""))))
        assertNull(TeamPhotos.url("Bertin Mwangi"))
        // an older server without "photos" changes nothing; a newer list without someone means they removed theirs
        TeamPhotos.absorb("/api/pin", JSONObject().put("who", ME).put("projects", JSONArray()))
        assertNotNull(TeamPhotos.url(GRACE))
        TeamPhotos.absorb("/api/pin", JSONObject().put("who", ME).put("projects", JSONArray()).put("photos", JSONObject().put(ME, url(ME, "b2"))))
        assertNull(TeamPhotos.url(GRACE)); assertNotNull(TeamPhotos.url(ME))
        // donor mode: none at all
        TeamPhotos.off = true; assertNull(TeamPhotos.url(ME)); TeamPhotos.off = false

        // loading: with the sign-in headers, to our own server only; kept on the phone; offline shows the last one
        val asked = mutableListOf<String>()
        var online = true
        api.fetch = { u, h -> asked += u; assertEquals("1234", h["X-Pin"]); if (online) 200 to jpeg(face(0xFF0A78C2.toInt(), -1)) else 0 to null }
        val b1 = runBlocking { TeamPhotos.load(ME, url(ME, "b2")) }
        assertNotNull(b1); assertEquals(listOf("https://pin.test" + url(ME, "b2")), asked)
        assertNotNull(runBlocking { TeamPhotos.load(ME, url(ME, "b2")) }); assertEquals(1, asked.size) // from memory
        online = false
        assertNotNull("no signal: the last photo kept for them", runBlocking { TeamPhotos.load(ME, url(ME, "c3")) })
        // gone from the server: initials
        api.fetch = { _, _ -> 404 to null }
        assertNull(runBlocking { TeamPhotos.load(ME, url(ME, "d4")) })
        assertNull("and nothing kept any more", runBlocking { api.fetch = { _, _ -> 0 to null }; TeamPhotos.load(ME, url(ME, "e5")) })
        // another site never gets the headers
        asked.clear(); api.fetch = { u, _ -> asked += u; 200 to jpeg(face(-1, -1)) }
        assertNull(runBlocking { TeamPhotos.load("X", "https://evil.example/x.jpg") }); assertTrue(asked.isEmpty())

        // shrinking: a square under the circle, 512 px, JPEG ≤ 200 KB even for a busy picture
        assertEquals(listOf(500, 0, 1000), AvatarImage.cropRect(2000, 1000, 500f, 1f, 0f, 0f).toList())
        assertEquals("moved right: the left part", listOf(0, 0, 1000), AvatarImage.cropRect(2000, 1000, 500f, 1f, 250f, 0f).toList())
        assertEquals("zoomed in 2×: half the side", 500, AvatarImage.cropRect(2000, 1000, 500f, 2f, 0f, 0f)[2])
        assertEquals(500f, AvatarImage.maxShift(2000, 1000, 1000f, 1f).first, 0.01f)
        val noisy = Bitmap.createBitmap(3000, 2200, Bitmap.Config.ARGB_8888).also { b ->
            val r = java.util.Random(7); val px = IntArray(3000 * 2200) { r.nextInt() or 0xFF000000.toInt() }; b.setPixels(px, 0, 3000, 0, 0, 3000, 2200)
        }
        val sq = AvatarImage.crop(noisy, AvatarImage.cropRect(3000, 2200, 400f, 1f, 0f, 0f))
        assertEquals(512, sq.width); assertEquals(512, sq.height)
        val bytes = AvatarImage.jpeg(sq)
        assertTrue("${bytes.size} bytes", bytes.size <= AvatarImage.MAX_BYTES)
        assertEquals(0xFF.toByte(), bytes[0]); assertEquals(0xD8.toByte(), bytes[1]) // a JPEG
    }

    // --- 2. the Me pill: labelled, tappable, never squeezing the wordmark or the bell ---------------------------------
    @Test fun me_pill_fits_every_width() {
        val m = model()
        start(m)
        for (sw in listOf(false, true)) for (width in listOf(320, 360, 390)) {
            Lang.sw = sw; w = width; settle()
            headerClear("${if (sw) "SW" else "EN"} $width")
            // the word fits at all three widths with the normal text size; the person icon is always there
            assertTrue(tagged("me_person")); assertTrue("${if (sw) "SW" else "EN"} $width: the word", tagged("me_word"))
            rule.onNodeWithTag("nav_me").assertContentDescriptionEquals(if (sw) "Mimi" else "Me")
        }
        // the largest text on a narrow phone: the word may go (it does in Kiswahili at 320), the person icon stays, nothing overlaps
        org.rafikioutreach.pin.ui.TextSize.value = "xl"
        try {
            Lang.sw = true; w = 320; settle(); headerClear("SW 320 larger text"); assertTrue(tagged("me_person"))
            shot("me-pill-320-sw-xl")
            w = 280; settle()
            headerClear("SW 280 larger text"); assertTrue(tagged("me_person")); assertFalse("no room for the word", tagged("me_word"))
            shot("me-pill-280-sw-xl")
        } finally { org.rafikioutreach.pin.ui.TextSize.value = "" }
        // EN light and SW dark at 360, with Grace's photo on the team and my initials (no photo yet)
        Lang.sw = false; w = 360; settle(); assertTrue(tagged("me_face_initials")); shot("me-pill-360-en-light")
        // a tap opens Me: the pill is lit; Back on a deeper page takes its place
        rule.onNodeWithTag("nav_me").assertIsNotSelected().performClick(); settle(1200)
        rule.onNodeWithTag("nav_me").assertIsSelected(); shot("me-pill-360-en-open")
        Lang.sw = true; dark = true; settle(); headerClear("SW 360 dark open"); shot("me-pill-360-sw-dark-open")
        // the waiting count and the update dot still show on it
        runBlocking { m.api.transport.let { tr -> m.api.transport = { a, b, c, d -> if (a == "POST" && b.endsWith("/api/pin")) 0 to "" else tr(a, b, c, d) } }; m.submit(JSONObject().put("action", "visit").put("id", "101").put("state", "working"), emptyList()) }
        settle(); assertTrue(tagged("waiting_badge"))
        // sun theme too
        Look.mode = "sun"; Lang.sw = false; settle(); headerClear("EN 360 sun"); shot("me-pill-360-en-sun")
    }

    // --- 3. the one-time hint --------------------------------------------------------------------------------------
    @Test fun hint_once_tap_anywhere_and_back() {
        // the first open of this version (welcome screens on, as on a phone): it points at the pill
        val m = model(onb = true, hintSeen = false)
        start(m)
        waitTag("me_hint"); settle(600)
        rule.onNodeWithTag("me_hint_text", useUnmergedTree = true).assertTextEquals("Me is here now: your work, settings and team")
        val hint = bounds("me_hint"); val pill = bounds("hav"); val bar = bounds("nav_map")
        assertTrue("under the pill", hint.top >= pill.bottom - 1f)
        assertTrue("clear of the bottom bar", hint.bottom < bar.top)
        shot("me-hint-360-en-light")
        // a tap anywhere closes it, and that tap still does its job: here the bottom bar's Map opens the map
        rule.onNodeWithTag("nav_map").performClick(); settle(1200)
        assertFalse(tagged("me_hint")); rule.onNodeWithTag("nav_map").assertIsSelected()
        // remembered: never again
        assertTrue(m.prefs.meHint); assertTrue(Prefs(app).meHint)
    }

    @Test fun hint_back_closes_it_first_sw_dark() {
        Lang.sw = true; dark = true
        val m = model(onb = true, hintSeen = false)
        start(m)
        waitTag("me_hint"); settle(600)
        rule.onNodeWithTag("me_hint_text", useUnmergedTree = true).assertTextEquals("Mimi iko hapa sasa: kazi yako, mipangilio na timu")
        rule.onNodeWithText("Sawa", useUnmergedTree = true).assertExists()
        shot("me-hint-360-sw-dark")
        // Back closes the hint, not the app or the screen
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; settle()
        assertFalse(tagged("me_hint")); assertTrue(m.prefs.meHint)
        rule.onNodeWithTag("nav_list").assertIsSelected(); rule.onNodeWithTag("nav_me").assertIsNotSelected()
    }

    @Test fun hint_is_for_admins_only() {
        val m = model(admin = false, onb = true, hintSeen = false)
        start(m); settle(4000)
        assertTrue(tagged("nav_me_tab")); assertFalse("field users see Me in the bar: no hint", tagged("me_hint"))
    }

    /** A field user's five tabs fit at 320 and 360 dp in English and Kiswahili: no label cut or squeezed. */
    @Test fun field_user_bar_fits() {
        val m = model(admin = false)
        rule.setContent { RafikiTheme(dark = dark) { androidx.compose.foundation.layout.Column(Modifier.size(w.dp, 120.dp)) { BottomBar(m, "me", 0, 3, waiting = 2) { } } } }
        settle()
        for (sw in listOf(false, true)) for (width in listOf(320, 360)) {
            Lang.sw = sw; w = width; settle()
            val cut = rule.onAllNodes(hasTestTag("nav_me_tab") or hasTestTag("nav_map") or hasTestTag("nav_list") or hasTestTag("nav_new") or hasTestTag("nav_chat"), useUnmergedTree = true).fetchSemanticsNodes().flatMap { tab ->
                tab.children.filter { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text) }.mapNotNull { n ->
                    val res = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    n.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult)?.action?.invoke(res)
                    val txt = n.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") { it.text }
                    if (res.firstOrNull()?.let { it.multiParagraph.intrinsics.maxIntrinsicWidth > n.size.width + 1f || n.boundsInRoot.right > tab.boundsInRoot.right + 1f } == true) txt else null
                }
            }
            assertEquals("${if (sw) "SW" else "EN"} $width", emptyList<String>(), cut)
            assertEquals(5, rule.onAllNodes(hasTestTag("nav_me_tab") or hasTestTag("nav_map") or hasTestTag("nav_list") or hasTestTag("nav_new") or hasTestTag("nav_chat")).fetchSemanticsNodes().size)
            assertTrue(tagged("me_tab_waiting"))
            val v = rule.activity.window.decorView
            val b = Bitmap.createBitmap(v.width, (120 * rule.density.density).toInt(), Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
            File(out, "me-tabbar-field-$width-${if (sw) "sw" else "en"}.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    // --- 4. photos: mine from Me, anyone's from the contact sheet (admin), shown everywhere -----------------------------
    @Test fun add_change_remove_photos() {
        val m = model()
        PhotoPick.fake = { face(0xFFD98200.toInt(), 0xFF5B3A1E.toInt(), 1200, 900) }
        start(m)
        tap("nav_me"); waitTag("me_photo")
        tap("me_photo"); waitTag("photo_gallery"); shot("me-photo-sheet-en")
        rule.onNodeWithTag("photo_remove").assertDoesNotExist() // none yet
        tap("photo_gallery"); waitTag("photo_crop"); settle(); shot("me-photo-crop-en")
        rule.onNodeWithTag("photo_crop").performTouchInput { pinch(center - androidx.compose.ui.geometry.Offset(40f, 0f), center - androidx.compose.ui.geometry.Offset(120f, 0f), center + androidx.compose.ui.geometry.Offset(40f, 0f), center + androidx.compose.ui.geometry.Offset(120f, 0f)) }
        settle()
        tap("photo_use")
        rule.waitUntil(10000) { TeamPhotos.url(ME) != null }; settle(1200)
        // POST /api/team-photo, my own (no name), a JPEG of 200 KB or less
        val (method, path, ct) = sent.last()
        assertEquals("POST", method); assertEquals("/api/team-photo", path); assertEquals("image/jpeg", ct.first); assertTrue(ct.second in 1..200_000)
        // straight away in the pill and on Me
        waitTag("me_face_photo"); assertTrue(tagged("photo_av"))
        shot("me-photo-mine-en")

        // an admin sets Grace's from her contact sheet
        rule.runOnUiThread { Contact.show(GRACE) }; settle(1500); waitTag("ct_photo_row")
        rule.onNodeWithTag("ct_photo_row").performScrollTo(); rule.onNodeWithTag("ct_photo_row").assertTextContains("Change photo", substring = true)
        rule.onNodeWithTag("ct_photo").performScrollTo(); shot("me-contact-grace-photo-en")
        tap("ct_photo_row"); waitTag("photo_remove")
        tap("photo_remove"); waitTag("photo_remove_go"); shot("me-photo-remove-ask-en")
        tap("photo_remove_go")
        rule.waitUntil(10000) { TeamPhotos.url(GRACE) == null }
        assertEquals("POST", sent.last().first); assertTrue(sent.last().second, sent.last().second.startsWith("/api/team-photo?name=Grace"))
        settle(1200)
        // Kiswahili and dark: the sheet's words
        Lang.sw = true; dark = true; settle()
        tap("ct_photo_row"); waitTag("photo_gallery")
        rule.onNodeWithText("Chagua kutoka kwenye picha", useUnmergedTree = true).assertExists()
        shot("me-photo-sheet-sw-dark")
    }

    @Test fun field_user_sees_photos_but_changes_only_their_own() {
        val m = model(admin = false)
        start(m)
        // Grace's photo shows in Me › Team and on her contact sheet; no Change photo for someone else
        tap("nav_me"); waitTag("pane_team"); tap("pane_team"); settle(1500)
        rule.runOnUiThread { Contact.show(GRACE) }; settle(1500); waitTag("ct_name")
        assertFalse(tagged("ct_photo_row")); assertFalse(tagged("ct_photo"))
        rule.waitUntil(10000) { tagged("photo_av") }
        shot("me-contact-grace-field-en")
    }

    // --- 5. a tablet: the content in a centred column; the map stays full-bleed -----------------------------------------
    @Config(qualifiers = "w800dp-h1280dp-hdpi")
    @Test fun tablet_centred() {
        w = 800; h = 1280
        val m = model()
        start(m)
        // Projects: before (hugging the left) and after (centred)
        tap("nav_list"); settle(1500)
        CentreLayout.on = false; settle(); shot("me-tablet-800-projects-before")
        CentreLayout.on = true; settle()
        val col = bounds("centred"); val d = rule.density.density
        assertEquals("720 dp wide", 720f, col.width / d, 1f)
        assertEquals("in the middle", (800f * d - col.right), col.left, 2f)
        shot("me-tablet-800-projects-after")
        // Me, after, in Kiswahili and dark
        Lang.sw = true; dark = true
        tap("nav_me"); settle(1500); assertTrue(tagged("centred")); headerClear("tablet SW dark")
        shot("me-tablet-800-me-sw-dark")
        CentreLayout.on = false; settle(); shot("me-tablet-800-me-sw-dark-before"); CentreLayout.on = true
        // Map home: full-bleed
        Lang.sw = false; dark = false
        rule.onNodeWithTag("nav_map").performClick(); settle(1500)
        assertFalse(tagged("centred"))
        shot("me-tablet-800-map")
    }
}

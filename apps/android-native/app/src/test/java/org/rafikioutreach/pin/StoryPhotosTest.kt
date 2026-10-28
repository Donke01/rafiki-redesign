package org.rafikioutreach.pin

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.media.ExifInterface
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.rafikioutreach.pin.data.Api
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.data.StoryUploads
import org.rafikioutreach.pin.ui.Look
import org.rafikioutreach.pin.ui.Motion
import org.rafikioutreach.pin.ui.Onb
import org.rafikioutreach.pin.ui.PhotoPick
import org.rafikioutreach.pin.ui.RafikiTheme
import org.rafikioutreach.pin.ui.StoryDates
import org.rafikioutreach.pin.ui.StoryKit
import org.rafikioutreach.pin.ui.StoryPick
import org.rafikioutreach.pin.ui.colDateText
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant

/** 2.27.0, the owner's request (28 Oct): story photos several at a time, a visible upload with progress, the photo sheet,
 *  and a month per column (Before, In progress from – to, After). Design: design/story-photos-2026-10. Screenshots "sp-…". */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class StoryPhotosTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    @After fun reset() { Lang.sw = false; Motion.on = true; Onb.enabled = true; PhotoPick.fakeFile = null; StoryPick.fakeGallery = null; Look.mode = "" }

    private fun proj(id: String, t: String) = JSONObject().put("id", id).put("title", t).put("country", "Kenya").put("ck", "ke").put("area", "Eldoret")
        .put("type", "Borehole well").put("status", "Completed").put("pump", "manual").put("gps", JSONArray().put(0.40).put(35.10)).put("done", "2025-06-20")
    private fun snapshot(admin: Boolean) = JSONObject().put("ok", true).put("who", "Grace Achieng").put("admin", admin).put("reviewOpen", 0)
        .put("projects", JSONArray().put(proj("101", "Chepkongi Dispensary Well")))
        .put("stories", JSONObject().put("101", JSONObject().put("st", status).put("n", 4).put("at", Instant.now().toString())))
        .put("repairs", JSONObject()).put("visits", JSONObject()).put("jobs", JSONArray()).put("assigned", JSONArray()).put("confirmed", JSONObject())

    // the story on the fake server
    private var status = "draft"; private var consent = "not-yet"; private var newPhotos = 0; private var pub = false
    private val rows = linkedMapOf(
        "before" to mutableListOf(ph("b1", taken = "2024-11-02"), ph("b2")),
        "progress" to mutableListOf(ph("p1", taken = "2025-01-10"), ph("p2", taken = "2025-03-04"), ph("p3")),
        "after" to mutableListOf<JSONObject>())
    private val dates = JSONObject().put("before", "2024-11")
    private var upMode = "ok"; private var refuseDates: String? = null
    private fun ph(id: String, kids: Boolean = false, taken: String = "", cap: String = "") = JSONObject().put("id", id).put("children", kids).put("by", "Meril").put("at", "${java.time.Year.now()}-10-28T10:42:00Z").put("cap", cap).also { if (taken.isNotBlank()) it.put("taken", taken) }
    private fun story() = JSONObject().put("id", "101").put("status", status).put("consent", consent)
        .put("answers", JSONObject().apply { StoryKit.QS.forEachIndexed { i, q -> put(q, if (i < 4) "An answer." else "") } }).put("quote", "").put("quoteBy", "").put("credit", "").put("text", "A first draft.")
        .put("photos", JSONObject().apply { rows.forEach { (r, l) -> put(r, JSONArray(l)) } })
        .put("doc", JSONObject().put("title", "Water at the dispensary").put("dek", "Clean water, close by.").put("at", "2026-10-27T10:00:00Z").put("by", "Grace")
            .put("sections", JSONArray().put(JSONObject().put("h", "Before").put("body", "Long walks.").put("photo", JSONObject().put("id", "b1").put("row", "before")))
                .put(JSONObject().put("h", "The work").put("body", "Drilling.").put("photo", JSONObject().put("id", "p1").put("row", "progress")))
                .put(JSONObject().put("h", "Now").put("body", "Water.").put("photo", JSONObject().put("id", "p3").put("row", "progress")))))
        .put("dates", dates).put("newPhotos", newPhotos)
        .apply { if (pub) put("pub", JSONObject().put("at", "2026-10-27T12:00:00Z").put("by", "Jessica").put("url", "https://test.rafikioutreach.org/stories/101-water")) }
        .put("missing", JSONArray().apply {
            put("answers:4/6"); if (rows["after"]!!.isEmpty()) put("after-photos")
            if (rows.any { (r, l) -> l.isNotEmpty() && dates.optString(r).isBlank() }) put("dates"); if (consent != "received") put("consent")
        })
        .put("canApprove", false).put("prompts", JSONObject())
    private val posts = mutableListOf<JSONObject>(); private val forms = mutableListOf<String>()
    private fun pic(c: Int, w: Int = 320, h: Int = 240): ByteArray { val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); Canvas(b).apply { drawColor(c); drawCircle(w / 2f, h / 2f, 60f, Paint().apply { color = -1 }) }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray() }
    private val colours = listOf(0xFF2F7D5B, 0xFF0A78C2, 0xFFD98200, 0xFF7A4FD0, 0xFFB3261E, 0xFF1C9C5A).map { it.toInt() }

    private fun model(admin: Boolean): AppModel {
        Onb.enabled = false; Motion.on = false
        Prefs(app).apply { pin = "9999"; name = "Grace Achieng"; saveSnapshot(snapshot(admin)); filter = "all"; country = ""; ftype = ""; onb = true; lang = if (Lang.sw) "sw" else "en"; phoneAlerts = false; meHint = true }
        org.rafikioutreach.pin.ui.clearDrafts()
        val m = AppModel(app)
        m.api.transport = { method, u, h, body ->
            val path = u.removePrefix(m.api.server)
            when {
                path.startsWith("/api/story") && method == "GET" -> 200 to JSONObject().put("ok", true).put("story", story()).toString()
                path == "/api/story" && h["Content-Type"]?.startsWith("multipart") == true -> {
                    val f = String(body!!, Charsets.ISO_8859_1)
                    when (upMode) {
                        "offline" -> 0 to ""
                        "fail" -> 413 to JSONObject().put("ok", false).put("reason", "too-large").toString()
                        else -> {
                            forms += f
                            val row = Regex("name=\"row\"\r\n\r\n(\\w+)").find(f)!!.groupValues[1]
                            val id = "n${forms.size}"
                            rows[row]!! += ph(id, kids = f.contains("name=\"children\"\r\n\r\ntrue"), taken = Regex("name=\"taken\"\r\n\r\n([\\d-]+)").find(f)?.groupValues?.get(1).orEmpty())
                            200 to JSONObject().put("ok", true).put("photo", JSONObject().put("id", id)).put("story", story()).toString()
                        }
                    }
                }
                path == "/api/story" -> {
                    val j = JSONObject(String(body!!)); posts += j
                    var refuse: Pair<String, String>? = null
                    when (j.optString("action")) {
                        "consent" -> consent = j.optString("value")
                        "draft" -> { status = "draft"; newPhotos = 0 }
                        "dates" -> if (refuseDates != null) refuse = refuseDates!! to "progress" else listOf("before", "progress", "progressEnd", "after").forEach { k -> if (j.has(k)) { if (j.optString(k).isBlank()) dates.remove(k) else dates.put(k, j.optString(k)) } }
                        "photo-edit" -> {
                            val l = rows[j.optString("row")]!!; val p = l.first { it.optString("id") == j.optString("asset") }
                            if (j.has("cap")) p.put("cap", j.optString("cap")); if (j.has("children")) p.put("children", j.optBoolean("children"))
                            if (j.has("to")) { l.remove(p); rows[j.optString("to")]!! += p }
                        }
                        "photo-remove" -> rows[j.optString("row")]!!.removeAll { it.optString("id") == j.optString("asset") }
                    }
                    if (refuse != null) 400 to JSONObject().put("ok", false).put("reason", refuse.first).put("field", refuse.second).toString()
                    else 200 to JSONObject().put("ok", true).put("story", story()).toString()
                }
                u.contains("?id=") -> 200 to "{\"ok\":true,\"last\":null}"
                u.contains("activity=1") -> 200 to "{\"ok\":true,\"activity\":[]}"
                u.contains("team=1") || u.contains("view=") -> 200 to "{\"ok\":true,\"team\":[],\"items\":[]}"
                u.contains("pin-auth/me") -> 200 to "{\"ok\":true,\"available\":false}"
                u.contains("version.json") || u.contains("/api/chat") -> 0 to ""
                method == "GET" -> 200 to snapshot(admin).toString()
                else -> 200 to "{\"ok\":true}"
            }
        }
        m.api.fetch = { u, _ -> val k = Regex("asset=(\\w+)").find(u)?.groupValues?.get(1).orEmpty(); 200 to pic(colours[k.hashCode().mod(colours.size)]) }
        return m
    }
    private var w by mutableStateOf(360); private var h by mutableStateOf(780); private var dark by mutableStateOf(false)
    private fun start(m: AppModel) {
        rule.setContent { RafikiTheme(dark = dark) { Box(Modifier.size(w.dp, h.dp)) { App(m) } } }
        rule.waitUntil(10000) { tagged("nav_list") }; settle(1500)
    }
    private fun tagged(t: String) = rule.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitTag(t: String) = rule.waitUntil(10000) { tagged(t) }
    private fun settle(ms: Long = 800) { rule.mainClock.advanceTimeBy(ms); rule.waitForIdle() }
    /** Waits while the app works off the main thread (uploads): the main looper keeps running meanwhile. */
    private fun until(ms: Long = 15000, c: () -> Boolean) { val end = System.currentTimeMillis() + ms; while (!c()) { check(System.currentTimeMillis() < end) { "timed out" }; rule.waitForIdle(); Thread.sleep(15) } }
    private fun tap(t: String) {
        rule.onNodeWithTag(t).let { n -> runCatching { n.performScrollTo() } }
        val target = rule.onAllNodes(hasTestTag(t) and hasClickAction()).fetchSemanticsNodes().let { if (it.isNotEmpty()) rule.onNode(hasTestTag(t) and hasClickAction()) else rule.onAllNodes(hasAnyAncestor(hasTestTag(t)) and hasClickAction()).onFirst() }
        target.performSemanticsAction(SemanticsActions.OnClick); settle()
    }
    private fun back() {
        rule.runOnUiThread {
            val d = ShadowDialog.getLatestDialog()
            @Suppress("DEPRECATION")
            if (d != null && d.isShowing) d.onBackPressed() else rule.activity.onBackPressedDispatcher.onBackPressed()
        }
        settle()
    }
    private fun shot(name: String) {
        settle()
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); val c = Canvas(b); v.draw(c)
        val d = ShadowDialog.getShownDialogs().lastOrNull { it.isShowing }?.window?.decorView
        if (d != null && d.width > 0) { c.drawColor(0x66000000); c.save(); c.translate(0f, (v.height - d.height).toFloat().coerceAtLeast(0f)); d.draw(c); c.restore() }
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun noLayoutProblems(root: String, label: String) = assertEquals("$label: $root", emptyList<String>(), LayoutCheck.problems(rule, root, w * rule.density.density))
    private fun hasTextIn(root: String, s: String) = rule.onAllNodes(hasAnyAncestor(hasTestTag(root)) and hasText(s, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
        rule.onAllNodes(hasTestTag(root) and hasText(s, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun openStory() {
        tap("nav_list"); rule.onNodeWithTag("projects").performScrollToNode(hasText("Chepkongi Dispensary Well")); rule.onAllNodesWithText("Chepkongi Dispensary Well").onLast().performClick(); settle(1200)
        tap("tab_story"); waitTag("story_photos")
    }
    /** A JPEG on the phone, with the day it was taken written in it (or none). */
    private fun file(i: Int, taken: String? = null): File = File(app.cacheDir, "g${System.nanoTime()}_$i.jpg").also { f ->
        f.writeBytes(pic(colours[i % colours.size], 2400, 1800))
        if (taken != null) ExifInterface(f.path).apply { setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "$taken 10:42:00"); saveAttributes() }
    }

    // --- 1–3, C: the page: steps, still-needed cards, the Photos card with "In story" badges and the months -------------
    private fun page(width: Int, height: Int, sw: Boolean, mode: String) {
        Lang.sw = sw; w = width; h = height; dark = mode == "dark"; Look.mode = if (mode == "sun") "sun" else ""
        val m = model(admin = false)
        start(m); openStory()
        val tag = "${width}-${if (sw) "sw" else "en"}-$mode"
        // the stepper: Collecting done, Draft ready now
        rule.onNodeWithTag("story_st_draft", useUnmergedTree = true).assertExists()
        // still needed as cards: after photos, the dates (In progress), the answers, consent
        listOf("story_need_after", "story_need_dates", "story_need_answers", "story_need_consent").forEach { rule.onNodeWithTag(it, useUnmergedTree = true).assertExists() }
        assertTrue(hasTextIn("story_need_dates", if (sw) "Wakati wa kazi" else "In progress"))
        assertFalse("only the missing column is named", hasTextIn("story_need_dates", if (sw) "Kabla" else "Before"))
        // the months per column: Before Nov 2024, In progress (no month yet: Add date), After (Add date)
        assertEquals(if (sw) "Nov 2024" else "Nov 2024", LayoutCheck.text(rule.onNodeWithTag("story_date_text_before", useUnmergedTree = true).fetchSemanticsNode()))
        assertEquals(if (sw) "Ongeza tarehe" else "Add date", LayoutCheck.text(rule.onNodeWithTag("story_date_text_progress", useUnmergedTree = true).fetchSemanticsNode()))
        // "3 in the story", badges on the photos the draft shows
        assertTrue(hasTextIn("story_instory_n", "3"))
        listOf("story_instory_before_0", "story_instory_progress_0", "story_instory_progress_2").forEach { rule.onNodeWithTag(it, useUnmergedTree = true).assertExists() }
        assertFalse(tagged("story_instory_progress_1"))
        // the completion date the story will use
        rule.onNodeWithTag("story_finished", useUnmergedTree = true).performScrollTo()
        assertTrue(hasTextIn("story_finished", if (sw) "Juni 2025" else "June 2025"))
        rule.onNodeWithTag("story_status").performScrollTo(); shot("sp-page-top-$tag")
        rule.onNodeWithTag("story_photos").performScrollTo(); shot("sp-page-photos-$tag")
        listOf("story_status", "story_missing", "story_photos").forEach { noLayoutProblems(it, tag) }
    }
    @Test fun page_360_en_light() = page(360, 780, false, "light")
    @Config(qualifiers = "w320dp-h640dp-xhdpi") @Test fun page_320_sw_dark() = page(320, 640, true, "dark")
    @Config(qualifiers = "w411dp-h860dp-xxhdpi") @Test fun page_411_en_sun() = page(411, 860, false, "sun")
    @Config(qualifiers = "w320dp-h640dp-xhdpi") @Test fun page_320_en_light() = page(320, 640, false, "light")
    @Config(qualifiers = "w411dp-h860dp-xxhdpi") @Test fun page_411_sw_light() = page(411, 860, true, "light")

    // --- 4, 5, B: Add → gallery (several) → review (children) → tiles fill as they send, the banner, the day taken --------
    private fun addFromGallery(sw: Boolean, mode: String) {
        Lang.sw = sw; dark = mode == "dark"
        val m = model(admin = false)
        val files = listOf(file(0, "2025:06:14"), file(1), file(2, "2025:06:15"))
        StoryPick.fakeGallery = { files }
        start(m); openStory()
        val tag = "${if (sw) "sw" else "en"}-$mode"
        // the Still needed card goes straight to Add, with After chosen
        tap("story_need_after_go"); waitTag("story_add_sheet")
        rule.onNodeWithTag("story_add_row_after").assertIsSelected()
        shot("sp-add-sheet-$tag"); noLayoutProblems("story_add_sheet", tag)
        // the back key closes the sheet first
        back(); assertFalse(tagged("story_add_sheet")); assertTrue(tagged("story_photos"))
        tap("story_add_after"); waitTag("story_add_sheet")
        m.storyUp.paused = true
        tap("story_add_gallery"); waitTag("story_review")
        rule.onNodeWithTag("story_review_add").assertIsNotEnabled()   // the children question first
        shot("sp-review-$tag")
        tap("story_kids_yes"); tap("story_rev_ph_1")
        rule.onNodeWithTag("story_review_add").assertIsEnabled().assertTextContains(if (sw) "Ongeza picha 3 kwenye Baada" else "Add 3 photos to After")
        shot("sp-review-kids-$tag"); noLayoutProblems("story_review", tag)
        tap("story_review_add")
        // tiles appear at once from the phone, waiting their turn; the banner over the bottom bar
        until(10000) { m.storyUp.items.size == 3 }; settle()
        waitTag("story_banner")
        assertTrue(hasTextIn("story_banner_title", if (sw) "Inaongeza picha 3 kwenye Baada" else "Adding 3 photos to After"))
        // shrunk: longest side 1600 px
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }; android.graphics.BitmapFactory.decodeFile(m.storyUp.items[0].file, o)
        assertEquals(1600, maxOf(o.outWidth, o.outHeight))
        // one sent, the second on its way at 64 %, the third waiting for signal (the design's screen 4)
        rule.runOnUiThread { m.storyUp.items[0].state = "sending"; m.storyUp.items[0].progress = .64f; m.storyUp.items[2].state = "waiting" }
        rule.onNodeWithTag("story_row_after").performScrollTo(); settle()
        waitTag("story_up_pc"); assertTrue(hasTextIn("story_up_pc", "64%")); waitTag("story_up_wait"); waitTag("story_banner_wait")
        shot("sp-progress-$tag"); noLayoutProblems("story_banner", tag); noLayoutProblems("story_row_after", tag)
        // the Stories tab says photos are sending, while people use the app
        tap("nav_stories"); waitTag("sh_sending"); shot("sp-hub-pill-$tag"); noLayoutProblems("sh_sending", tag)
        assertTrue(hasTextIn("sh_sending", if (sw) "Inatuma picha 3" else "Sending 3 photos"))
        back(); if (!tagged("story_photos")) openStory()
        // now really send them, one after another
        rule.runOnUiThread { m.storyUp.items.forEach { it.state = "queued"; it.progress = 0f }; m.storyUp.paused = false; m.storyUp.resume() }
        until(15000) { forms.size == 3 }; settle(1500)
        forms.forEach { f -> assertTrue(f.contains("name=\"action\"\r\n\r\nphoto")); assertTrue(f.contains("name=\"row\"\r\n\r\nafter")); assertTrue(f.contains("name=\"id\"\r\n\r\n101")) }
        assertTrue("the second shows children", forms[1].contains("name=\"children\"\r\n\r\ntrue")); assertTrue(forms[0].contains("name=\"children\"\r\n\r\nfalse"))
        // the day each was taken, from the photo itself; none when the photo has no date (never the upload day)
        assertTrue(forms[0].contains("name=\"taken\"\r\n\r\n2025-06-14")); assertFalse(forms[1].contains("name=\"taken\"")); assertTrue(forms[2].contains("name=\"taken\"\r\n\r\n2025-06-15"))
        waitTag("story_banner_done"); assertTrue(hasTextIn("story_banner_done", if (sw) "Picha 3 zimeongezwa" else "3 photos added"))
        rule.onNodeWithTag("story_row_after").performScrollTo(); shot("sp-done-$tag")
        rule.onNodeWithTag("story_count_after", useUnmergedTree = true).assertTextEquals("3")
        // the banner goes by itself
        settle(4500); assertFalse(tagged("story_banner_done"))
    }
    @Test fun add_from_gallery_en_light() = addFromGallery(false, "light")
    @Config(qualifiers = "w320dp-h640dp-xhdpi") @Test fun add_from_gallery_sw_dark() { w = 320; h = 640; addFromGallery(true, "dark") }

    /** Take photos one after another: "2 taken · Take another / Add 2 photos"; camera shots carry today as the day taken. */
    @Test fun take_photos_one_after_another() {
        val m = model(admin = false)
        var n = 0
        PhotoPick.fakeFile = { File(app.cacheDir, "s${System.nanoTime()}.jpg").also { it.writeBytes(pic(colours[n++ % 6])) } }
        start(m); openStory()
        tap("story_add_progress"); waitTag("story_add_sheet")
        rule.onNodeWithTag("story_add_row_progress").assertIsSelected()
        tap("story_add_camera"); waitTag("story_tray"); rule.onNodeWithTag("story_tray_n", useUnmergedTree = true).assertTextEquals("1 taken")
        tap("story_tray_more"); waitTag("story_tray"); rule.onNodeWithTag("story_tray_n", useUnmergedTree = true).assertTextEquals("2 taken")
        rule.onNodeWithTag("story_tray_add").assertTextContains("Add 2 photos"); shot("sp-tray-en"); noLayoutProblems("story_tray", "tray")
        tap("story_tray_add"); waitTag("story_review"); tap("story_kids_no"); tap("story_review_add")
        until(15000) { forms.size == 2 }; settle()
        val today = java.time.LocalDate.now().toString()
        forms.forEach { assertTrue(it.contains("name=\"row\"\r\n\r\nprogress")); assertTrue(it.contains("name=\"taken\"\r\n\r\n$today")) }
    }

    /** No signal: photos wait and send by themselves; a refusal says why, and Tap to retry / Retry send again. */
    @Test fun offline_then_failed_then_retry() {
        val m = model(admin = false)
        StoryPick.fakeGallery = { listOf(file(0), file(1)) }
        start(m); openStory()
        upMode = "offline"
        tap("story_add_after"); waitTag("story_add_sheet"); tap("story_add_gallery"); waitTag("story_review"); tap("story_kids_no"); tap("story_review_add")
        until(10000) { m.storyUp.items.size == 2 && m.storyUp.items.all { it.state == "waiting" } }; settle()
        waitTag("story_banner_wait"); rule.onNodeWithTag("story_row_after").performScrollTo(); shot("sp-offline-en")
        // the list is kept on the phone: a new queue (the app started again) finds them
        val again = StoryUploads(File(app.filesDir, "story-queue"), m.api, CoroutineScope(Dispatchers.Unconfined)).apply { paused = true }
        assertEquals(2, again.items.size)
        // back online, but the server refuses: why, and Tap to retry
        upMode = "fail"
        rule.runOnUiThread { m.storyUp.resume() }
        until(10000) { m.storyUp.items.all { it.state == "failed" } }; settle()
        waitTag("story_up_retry"); waitTag("story_banner_fail")
        assertTrue(hasTextIn("story_banner_fail", "too big"))
        rule.onNodeWithTag("story_row_after").performScrollTo(); shot("sp-failed-en"); noLayoutProblems("story_banner_done", "failed")
        upMode = "ok"
        tap("story_banner_retry")
        until(15000) { forms.size == 2 }; settle()
        waitTag("story_banner_done"); assertFalse(tagged("story_banner_fail"))
    }

    // --- 6: the photo sheet: caption, move, children, delete --------------------------------------------------------
    private fun details(sw: Boolean, mode: String) {
        Lang.sw = sw; dark = mode == "dark"
        val m = model(admin = false)
        start(m); openStory()
        val tag = "${if (sw) "sw" else "en"}-$mode"
        tap("story_photo_progress_0"); waitTag("story_det")
        assertEquals(if (sw) "Iliongezwa 28\u00A0Okt na Meril" else "Added 28\u00A0Oct by Meril", LayoutCheck.text(rule.onNodeWithTag("story_det_added", useUnmergedTree = true).fetchSemanticsNode()))
        assertFalse("no date per photo", tagged("story_det_taken"))
        rule.onNode(hasTestTag("story_det_cap") and hasSetTextAction()).performTextInput("Drilling reached water")
        shot("sp-details-$tag"); noLayoutProblems("story_det", tag)
        tap("story_det_kids_sw"); rule.waitUntil(8000) { posts.any { it.optString("action") == "photo-edit" && it.has("children") } }
        assertTrue(posts.last().optBoolean("children")); assertEquals("p1", posts.last().optString("asset"))
        // Done saves the caption
        tap("story_det_done"); rule.waitUntil(8000) { posts.any { it.has("cap") } }
        assertEquals("Drilling reached water", posts.last { it.has("cap") }.optString("cap"))
        // move to After
        tap("story_photo_progress_1"); waitTag("story_det"); tap("story_det_row_after")
        rule.waitUntil(8000) { posts.any { it.optString("to") == "after" } }; settle()
        assertEquals("p2", posts.last().optString("asset")); assertEquals("progress", posts.last().optString("row"))
        // delete asks first
        tap("story_det_del"); waitTag("story_det_ask"); shot("sp-delete-ask-$tag")
        tap("story_det_ask_yes"); rule.waitUntil(8000) { posts.any { it.optString("action") == "photo-remove" } }
        assertEquals("p2", posts.last().optString("asset")); assertEquals("after", posts.last().optString("row"))
    }
    @Test fun photo_details_en_light() = details(false, "light")
    @Test fun photo_details_sw_dark() = details(true, "dark")

    // --- C: a month per column -------------------------------------------------------------------------------------
    @Test fun column_dates() {
        val m = model(admin = false)
        start(m); openStory()
        // the Still needed card opens the picker on the first column without a month (In progress), suggested from the photos
        tap("story_need_dates_go"); waitTag("story_dates")
        assertTrue(hasTextIn("story_dates_sub", "In progress"))
        rule.onNodeWithTag("story_dates_progress_value", useUnmergedTree = true).assertTextEquals("January 2025")
        rule.onNodeWithTag("story_dates_progressEnd_value", useUnmergedTree = true).assertTextEquals("March 2025")
        waitTag("story_dates_hint_progress"); shot("sp-dates-progress-en"); noLayoutProblems("story_dates", "dates")
        // the server says the order is wrong: said in the sheet, which stays open
        refuseDates = "order"; tap("story_dates_save"); waitTag("story_dates_err")
        assertTrue(hasTextIn("story_dates_err", "in order")); shot("sp-dates-error-en")
        refuseDates = null; tap("story_dates_save")
        rule.waitUntil(8000) { !tagged("story_dates") }
        val d = posts.last { it.optString("action") == "dates" }
        assertEquals("2025-01", d.optString("progress")); assertEquals("2025-03", d.optString("progressEnd")); assertFalse(d.has("before"))
        settle()
        assertEquals("Jan – Mar 2025", LayoutCheck.text(rule.onNodeWithTag("story_date_text_progress", useUnmergedTree = true).fetchSemanticsNode()))
        assertFalse("nothing missing now", tagged("story_need_dates"))
        // After: the project's completion month (monday.com) is suggested; pick another month by hand
        tap("story_date_after"); waitTag("story_dates")
        rule.onNodeWithTag("story_dates_after_value", useUnmergedTree = true).assertTextEquals("June 2025")
        tap("story_dates_after"); waitTag("story_dates_after_pick"); tap("story_dates_after_m7")
        rule.onNodeWithTag("story_dates_after_value", useUnmergedTree = true).assertTextEquals("July 2025")
        tap("story_dates_save"); rule.waitUntil(8000) { posts.last().optString("after") == "2025-07" }; settle()
        // Before: clear it
        tap("story_date_before"); waitTag("story_dates"); tap("story_dates_clear")
        rule.waitUntil(8000) { posts.last().has("before") && posts.last().optString("before") == "" }; settle()
        assertEquals("Add date", LayoutCheck.text(rule.onNodeWithTag("story_date_text_before", useUnmergedTree = true).fetchSemanticsNode()))
        rule.onNodeWithTag("story_photos").performScrollTo(); shot("sp-dates-headers-en")
    }

    @Test fun column_date_texts() {
        assertEquals("Nov 2024", colDateText(StoryDates(before = "2024-11"), "before"))
        assertEquals("Jan – Mar 2025", colDateText(StoryDates(progress = "2025-01", progressEnd = "2025-03"), "progress"))
        assertEquals("Dec 2024 – Feb 2025", colDateText(StoryDates(progress = "2024-12", progressEnd = "2025-02"), "progress"))
        assertEquals("Jan 2025", colDateText(StoryDates(progress = "2025-01", progressEnd = "2025-01"), "progress"))
        assertEquals(null, colDateText(StoryDates(), "after"))
        Lang.sw = true; assertEquals("Ago 2025", colDateText(StoryDates(after = "2025-08"), "after"))
    }

    /** The day a photo was taken comes from its EXIF DateTimeOriginal; never a date in the future. */
    @Test fun taken_day_from_exif() {
        assertEquals("2024-11-02", StoryUploads.takenDay(file(0, "2024:11:02")))
        assertEquals(null, StoryUploads.takenDay(file(1)))
        assertEquals(null, StoryUploads.takenDay(file(2, "2099:01:01")))
    }

    /** The queue on disk keeps the photo's day and what failed, with why (survives the app being closed). */
    @Test fun queue_survives_restart() = runBlocking {
        val dir = File(app.filesDir, "q-test").apply { deleteRecursively() }
        val api = Api("https://x.test", { "9999" })
        val a = StoryUploads(dir, api, CoroutineScope(Dispatchers.Unconfined)).apply { paused = true }
        a.add("101", "before", listOf(file(0, "2024:11:02") to false, file(1) to true))
        assertEquals(2, a.items.size); assertEquals("2024-11-02", a.items[0].taken)
        val b = StoryUploads(dir, api, CoroutineScope(Dispatchers.Unconfined)).apply { paused = true }
        assertEquals(listOf("2024-11-02", ""), b.items.map { it.taken }); assertEquals(listOf(false, true), b.items.map { it.children }); assertEquals(listOf("before", "before"), b.items.map { it.row })
    }

    // --- 7: new photos since the draft ------------------------------------------------------------------------------
    @Test fun new_photos_since_the_draft() {
        newPhotos = 2
        val m = model(admin = false)
        start(m); openStory()
        waitTag("story_newphotos"); assertTrue(hasTextIn("story_newphotos_text", "2 new photos since this draft"))
        shot("sp-newphotos-en"); noLayoutProblems("story_newphotos", "new")
        tap("story_newphotos_draft"); waitTag("story_draft_ask"); tap("story_draft_ask_yes")
        rule.waitUntil(8000) { posts.any { it.optString("action") == "draft" } }; settle()
        assertFalse(tagged("story_newphotos"))
    }
    @Test fun new_photos_on_the_website_admin() {
        newPhotos = 1; pub = true; status = "published"
        val m = model(admin = true)
        start(m); openStory()
        waitTag("story_newphotos"); assertTrue(hasTextIn("story_newphotos_text", "story editor")); assertFalse(tagged("story_newphotos_draft"))
    }
}

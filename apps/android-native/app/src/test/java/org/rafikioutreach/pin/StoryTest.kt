package org.rafikioutreach.pin

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
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
import org.rafikioutreach.pin.data.Snapshot
import org.rafikioutreach.pin.ui.DonorStory
import org.rafikioutreach.pin.ui.DonorStoryCard
import org.rafikioutreach.pin.ui.Motion
import org.rafikioutreach.pin.ui.Onb
import org.rafikioutreach.pin.ui.PhotoPick
import org.rafikioutreach.pin.ui.RafikiTheme
import org.rafikioutreach.pin.ui.StoryKit
import org.rafikioutreach.pin.ui.StoryNav
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant

/** Stories (feat-mea, me-brief §9; S/me-contract.md): one Stories tab on the project page. A fake server. Screenshots "story-…". */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class StoryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    @After fun reset() { Lang.sw = false; Motion.on = true; Onb.enabled = true; PhotoPick.fakeFile = null; StoryNav.open = null }

    private fun proj(id: String, t: String) = JSONObject().put("id", id).put("title", t).put("country", "Kenya").put("ck", "ke").put("area", "Eldoret")
        .put("type", "Borehole well").put("status", "Completed").put("pump", "manual").put("gps", JSONArray().put(0.40 + id.toInt() % 7 * 0.01).put(35.10))
    private fun snapshot(admin: Boolean) = JSONObject().put("ok", true).put("who", "Grace Achieng").put("admin", admin).put("reviewOpen", 0)
        .put("projects", JSONArray().put(proj("101", "Chepkongi Borehole")).put(proj("102", "Kapsaret Spring")).put(proj("103", "Turbo Well")))
        .put("stories", JSONObject().put("101", JSONObject().put("st", status).put("n", 3).put("at", Instant.now().toString())).put("102", JSONObject().put("st", "approved").put("n", 6).put("at", Instant.now().toString()))
            .put("103", JSONObject().put("st", "draft").put("n", 6).put("at", Instant.now().toString())))
        .put("repairs", JSONObject()).put("visits", JSONObject()).put("jobs", JSONArray()).put("assigned", JSONArray()).put("confirmed", JSONObject())

    // the story on the fake server
    private var status = "collecting"; private var consent = "not-yet"; private var text = ""
    private val answers = mutableMapOf("q1" to "We walked 2 hours for water.", "q2" to "The river was dirty and children fell sick.", "q3" to "")
    private val after = mutableListOf<JSONObject>()
    private fun story() = JSONObject().put("id", "101").put("status", status).put("consent", consent)
        .put("answers", JSONObject().apply { StoryKit.QS.forEach { put(it, answers[it] ?: "") } }).put("quote", "Now my girls get to school on time.").put("quoteBy", "Mary").put("credit", "").put("text", text)
        .put("photos", JSONObject().put("before", JSONArray().put(JSONObject().put("id", "b1").put("children", false))).put("progress", JSONArray()).put("after", JSONArray(after)))
        .put("missing", JSONArray().apply { val n = StoryKit.QS.count { !answers[it].isNullOrBlank() }; if (n < 6) put("answers:$n/6"); if (after.isEmpty()) put("after-photos"); if (consent != "received") put("consent"); if (text.isBlank()) put("text") })
        .put("canApprove", false)
        .put("prompts", JSONObject().put("q3", JSONObject().put("en", "Who is this story about? Give their name and age.").put("sw", "Hadithi hii inamhusu nani? Taja jina na umri.")))
    private val posts = mutableListOf<JSONObject>(); private val forms = mutableListOf<String>()
    private fun pic(c: Int): ByteArray { val b = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888); Canvas(b).apply { drawColor(c); drawCircle(160f, 120f, 60f, Paint().apply { color = -1 }) }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray() }

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
                    val f = String(body!!, Charsets.ISO_8859_1); forms += f
                    if (f.contains("name=\"row\"\r\n\r\nafter")) after += JSONObject().put("id", "a${after.size + 1}").put("children", f.contains("name=\"children\"\r\n\r\ntrue"))
                    if (f.contains("name=\"action\"\r\n\r\nvoice")) { answers["q3"] = "Mary, 34, a mother of three."; 200 to JSONObject().put("ok", true).put("typed", true).put("text", answers["q3"]).toString() }
                    else 200 to JSONObject().put("ok", true).put("story", story()).toString()
                }
                path == "/api/story" -> {
                    val j = JSONObject(String(body!!)); posts += j
                    var refuse: String? = null
                    when (j.optString("action")) {
                        "answers" -> StoryKit.QS.forEach { q -> if (j.has(q)) answers[q] = j.optString(q) }
                        "consent" -> consent = j.optString("value")
                        "draft" -> { text = "Before the borehole, families in Chepkongi walked two hours for water. " + "Now the water is close and clean. ".repeat(30); status = "draft" }
                        "approve" -> if (!admin) refuse = "admin-only" else if (consent != "received" && after.any { it.optBoolean("children") }) refuse = "consent" else status = "approved"
                        "publish" -> status = "published"
                        "collecting" -> status = "collecting"
                    }
                    if (refuse != null) 409 to JSONObject().put("ok", false).put("reason", refuse).toString() else 200 to JSONObject().put("ok", true).put("story", story()).toString()
                }
                u.contains("?id=") -> 200 to "{\"ok\":true,\"last\":null}"
                u.contains("activity=1") -> 200 to "{\"ok\":true,\"activity\":[]}"
                u.contains("view=review") -> 200 to JSONObject().put("ok", true).put("items", JSONArray().put(JSONObject().put("kind", "story-draft").put("id", "103").put("t", "Turbo Well").put("by", "Grace Achieng").put("at", Instant.now().toString()).put("status", "open"))).toString()
                u.contains("team=1") || u.contains("view=") -> 200 to "{\"ok\":true,\"team\":[],\"items\":[]}"
                u.contains("pin-auth/me") -> 200 to "{\"ok\":true,\"available\":false}"
                u.contains("version.json") || u.contains("/api/chat") -> 0 to ""
                method == "GET" -> 200 to snapshot(admin).toString()
                else -> 200 to "{\"ok\":true}"
            }
        }
        m.api.fetch = { u, h -> if (h["X-Pin"] != "9999") 401 to null else 200 to pic(if (u.contains("asset=b1")) 0xFF7A4FD0.toInt() else 0xFF1C9C5A.toInt()) }
        return m
    }
    private var dark by mutableStateOf(false)
    private fun start(m: AppModel) {
        rule.setContent { RafikiTheme(dark = dark) { Box(Modifier.size(360.dp, 780.dp)) { App(m) } } }
        rule.waitUntil(10000) { tagged("nav_list") }; settle(1500)
    }
    private fun tagged(t: String) = rule.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitTag(t: String) = rule.waitUntil(10000) { tagged(t) }
    private fun settle(ms: Long = 800) { rule.mainClock.advanceTimeBy(ms); rule.waitForIdle() }
    /** Scrolls to it and taps it by its click action (a touch near the bottom edge would land on the floating bar). */
    private fun tap(t: String) {
        rule.onNodeWithTag(t).let { n -> runCatching { n.performScrollTo() } }
        val target = rule.onAllNodes(hasTestTag(t) and hasClickAction()).fetchSemanticsNodes().let { if (it.isNotEmpty()) rule.onNode(hasTestTag(t) and hasClickAction()) else rule.onAllNodes(hasAnyAncestor(hasTestTag(t)) and hasClickAction()).onFirst() }
        target.performSemanticsAction(SemanticsActions.OnClick); settle()
    }
    private fun shot(name: String) {
        settle()
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); val c = Canvas(b); v.draw(c)
        val d = ShadowDialog.getShownDialogs().lastOrNull { it.isShowing }?.window?.decorView
        if (d != null && d.width > 0) { c.drawColor(0x66000000); c.save(); c.translate(0f, (v.height - d.height).toFloat().coerceAtLeast(0f)); d.draw(c); c.restore() }
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun openStories(title: String) {
        tap("nav_list"); rule.onNodeWithTag("projects").performScrollToNode(hasText(title)); rule.onAllNodesWithText(title).onLast().performClick(); settle(1200)
        tap("tab_story"); waitTag("story_status")
    }
    /** No word broken across lines in a tag's text (each line ends at a space or the end). */
    private fun noMidWordBreak(tag: String) {
        val n = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val r = mutableListOf<TextLayoutResult>(); n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(r)
        val l = r.firstOrNull() ?: return; val s = l.layoutInput.text.text
        for (i in 0 until l.lineCount - 1) { val e = l.getLineEnd(i); assertTrue("$tag: \"$s\" breaks mid-word", e >= s.length || s[e - 1].isWhitespace() || s[e].isWhitespace() || s[e - 1] == '-') }
    }

    @Test fun briefs_and_filters() {
        status = "collecting"
        val s = Snapshot.from(snapshot(false))
        assertTrue(s.storyIs(s.projects[0], "need")); assertTrue(s.storyIs(s.projects[1], "ok")); assertTrue(s.storyIs(s.projects[2], "draft"))
        Lang.sw = true; assertEquals("majibu 3 kati ya 6, hakuna picha za baada, idhini: bado", StoryKit.missing(listOf("answers:3/6", "after-photos", "consent"))); Lang.sw = false
    }

    private fun collect(sw: Boolean, d: Boolean) {
        Lang.sw = sw; dark = d
        val m = model(admin = false)
        PhotoPick.fakeFile = { File(app.cacheDir, "s${System.nanoTime()}.jpg").also { it.writeBytes(pic(0xFFD98200.toInt())) } }
        start(m)
        openStories("Chepkongi Borehole")
        // the four tabs fit: no word split
        listOf("pin", "visit", "hist", "story").forEach { noMidWordBreak("tab_$it") }
        assertTrue(rule.onAllNodes(hasAnyAncestor(hasTestTag("story_need_answers")) and hasText(if (sw) "Majibu 2 kati ya 6" else "2 of 6 answers", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        shot("story-top-${if (sw) "sw" else "en"}-${if (d) "dark" else "light"}")
        // question 3 (the first unanswered), with the server's wording
        rule.onNodeWithTag("story_prompt").performScrollTo().assertTextEquals(if (sw) "Hadithi hii inamhusu nani? Taja jina na umri." else "Who is this story about? Give their name and age.")
        rule.onNodeWithTag("story_answer").onChildren().filter(hasSetTextAction()).onFirst().performTextInput("Mary, 34")
        tap("story_qsave"); rule.waitUntil(8000) { posts.any { it.optString("action") == "answers" } }; settle()
        assertEquals("Mary, 34", posts.last().optString("q3")); assertEquals("answers", posts.last().optString("action"))
        rule.onNodeWithTag("story_prompt").assertTextContains(if (sw) "Jamii" else "community", substring = true)
        shot("story-questions-${if (sw) "sw" else "en"}-${if (d) "dark" else "light"}")
        // an after photo with children in it: consent is then needed
        // 2.27.0: Add → Take photos → the tray → check them (children?) → they send
        tap("story_add_after"); waitTag("story_add_sheet"); tap("story_add_camera"); waitTag("story_tray"); tap("story_tray_add")
        waitTag("story_review"); tap("story_kids_yes"); tap("story_rev_ph_0"); tap("story_review_add")
        run { val end = System.currentTimeMillis() + 15000; while (forms.isEmpty()) { check(System.currentTimeMillis() < end) { "no upload" }; rule.waitForIdle(); Thread.sleep(15) } }; settle()
        assertTrue(forms.last().contains("name=\"row\"\r\n\r\nafter")); assertTrue(forms.last().contains("name=\"children\"\r\n\r\ntrue")); assertTrue(forms.last().contains("name=\"id\"\r\n\r\n101"))
        waitTag("story_photo_after_0"); assertTrue(tagged("story_children_note"))
        rule.onNodeWithTag("story_count_after", useUnmergedTree = true).assertTextContains("1", substring = true)
        rule.onNodeWithTag("story_photos").performScrollTo(); shot("story-photos-${if (sw) "sw" else "en"}-${if (d) "dark" else "light"}")
        // consent, then draft; field users can't approve
        tap("story_consent_received"); rule.waitUntil(8000) { posts.any { it.optString("action") == "consent" } }
        assertEquals("received", posts.last { it.optString("action") == "consent" }.optString("value"))
        tap("story_draft"); rule.waitUntil(8000) { tagged("story_words") }
        assertFalse("field users don't approve", tagged("story_approve"))
        assertTrue(tagged("story_back"))
        rule.onNodeWithTag("story_text_card").performScrollTo(); shot("story-draft-${if (sw) "sw" else "en"}-${if (d) "dark" else "light"}")
    }
    @Test fun field_user_collects_and_drafts_en_light() = collect(false, false)
    @Test fun field_user_collects_and_drafts_sw_dark() = collect(true, true)

    @Test fun admin_approves_and_publishes() {
        status = "draft"; text = "A short draft."; consent = "received"; answers.putAll(mapOf("q3" to "Mary", "q4" to "Dug the trench", "q5" to "Clean water", "q6" to "Her first day on time"))
        val m = model(admin = true)
        start(m)
        openStories("Chepkongi Borehole")
        waitTag("story_approve"); rule.onNodeWithTag("story_text_edit").assertExists()
        tap("story_approve"); rule.waitUntil(8000) { posts.any { it.optString("action") == "approve" } }
        assertEquals("A short draft.", posts.last { it.optString("action") == "approve" }.optString("text"))
        waitTag("story_publish"); rule.onNodeWithTag("story_st_approved").performScrollTo()
        shot("story-approved-en")
        tap("story_publish"); rule.waitUntil(8000) { posts.any { it.optString("action") == "publish" } }
    }

    @Test fun approve_refused_without_consent_when_children() {
        status = "draft"; text = "A draft."; consent = "not-yet"; after += JSONObject().put("id", "a1").put("children", true)
        val m = model(admin = true)
        start(m); openStories("Chepkongi Borehole")
        tap("story_approve"); waitTag("story_msg")
        rule.onNodeWithText("Photo consent is needed first", substring = true).assertExists()
    }

    @Test fun list_story_filter_badge_and_visit_shortcut() {
        Lang.sw = true
        val m = model(admin = false)
        start(m)
        tap("nav_list"); tap("chip_story"); waitTag("story_seg")
        rule.onNodeWithTag("projects").performScrollToNode(hasTestTag("proj_count"))
        rule.onNodeWithTag("proj_count").assertTextContains("1 zinahitaji hadithi", substring = true)
        tap("chip_story:ok"); rule.onNodeWithText("Kapsaret Spring").assertExists(); assertTrue(tagged("row_story"))
        shot("story-list-filter-sw")
        // a visit sent with "Add to this project's story" opens the Stories tab
        rule.onAllNodesWithText("Kapsaret Spring").onLast().performClick(); settle(1200)
        rule.runOnUiThread { StoryNav.open = "102" }; settle(); waitTag("story_status")
    }

    /** Office › Review lists "Draft ready" stories; each opens that project's Stories tab (feat-mea). */
    @Test fun office_review_lists_draft_stories() {
        val m = model(admin = true)
        start(m)
        tap("nav_office"); waitTag("o_review"); tap("o_review"); waitTag("rv_story_103")
        rule.onNodeWithTag("rv_story_103").performScrollTo(); shot("story-review-en")
        tap("rv_story_open_103"); waitTag("story_status")
        rule.onNodeWithTag("tab_story").assertExists()
    }

    /** The monthly report counts stories collected, drafts ready and approved this month (feat-mea). */
    @Test fun report_counts_stories() {
        status = "collecting"
        for ((sw, d) in listOf(false to false, true to true)) {
            Lang.sw = sw
            val r = org.rafikioutreach.pin.ui.buildReport(Snapshot.from(snapshot(false)), emptyList(), java.time.YearMonth.now(), "", "Grace")
            assertEquals(3, r.storiesCollected); assertEquals(1, r.storiesDraft); assertEquals(1, r.storiesApproved)
        }
        Lang.sw = true; dark = true
        val m = model(admin = false)
        rule.setContent { RafikiTheme(dark = dark) { Box(Modifier.size(360.dp, 780.dp)) { org.rafikioutreach.pin.ui.ReportScreen(m) {} } } }
        waitTag("rep_stories"); rule.onNodeWithTag("rep_stories").performScrollTo()
        rule.onNodeWithText("rasimu iko tayari", substring = true).assertExists()
        shot("story-report-sw-dark")
    }

    @Test fun donor_sees_the_approved_story() {
        val s = DonorStory.from(JSONObject().put("text", "Before the borehole, families walked two hours for water. Now it is close and clean.").put("quote", "Now my girls get to school on time.").put("quoteBy", "Mary").put("credit", "Grace Achieng").put("after", JSONArray().put("p1").put("p2")))!!
        rule.setContent { RafikiTheme(dark = dark) { Column(Modifier.size(360.dp, 780.dp).verticalScroll(rememberScrollState()).padding(16.dp)) { DonorStoryCard(s, { id, w -> "https://x.test/$id?w=$w" }) { _, _ -> } } } }
        settle(); rule.onNodeWithTag("dstory_quote").assertTextContains("girls", substring = true); shot("story-donor-en-light")
        Lang.sw = true; dark = true; settle(); rule.onNodeWithText("Hadithi yao").assertExists(); shot("story-donor-sw-dark")
    }
}

package org.rafikioutreach.pin

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
import org.rafikioutreach.pin.ui.Motion
import org.rafikioutreach.pin.ui.Onb
import org.rafikioutreach.pin.ui.RafikiTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.Instant

/** 2.26.0, web v72: the Stories tab in the bottom bar (six tabs down to 320 dp), the hub (Write a story, four counts,
 *  the list, how a story gets to the website), opening the writer and back, and the review note for field users.
 *  Screenshots "sh-…". */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class StoriesHubTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    @After fun reset() { Lang.sw = false; Motion.on = true; Onb.enabled = true; Look.mode = "" }

    private fun ago(d: Long) = Instant.now().minusSeconds(d * 86400).toString()
    private fun proj(id: String, t: String, area: String, country: String = "Kenya", status: String = "Completed") = JSONObject().put("id", id).put("title", t).put("country", country).put("ck", if (country == "Malawi") "mw" else "ke").put("area", area)
        .put("type", "Borehole well").put("status", status).put("pump", "manual").put("gps", JSONArray().put(0.4 + id.toInt() % 9 * 0.01).put(35.1))
    // as the web QA (qa/pin/stories-hub.py): a draft, a story being collected, one on the website, two not started
    private fun snapshot(admin: Boolean) = JSONObject().put("ok", true).put("who", if (admin) "Reid Anderson" else "Meril Kiprop").put("admin", admin).put("reviewOpen", 0)
        .put("projects", JSONArray().put(proj("101", "Chepkongi Dispensary", "Kapsaret, Eldoret")).put(proj("102", "Kiptoo Well", "Kiptoo", status = "Under construction"))
            .put(proj("104", "Kapsaret Borehole", "Kapsaret")).put(proj("105", "Turbo Spring", "Turbo")).put(proj("107", "Mzuzu Water Point", "Mzuzu", "Malawi")))
        .put("stories", JSONObject().put("101", JSONObject().put("st", "draft").put("n", 6).put("b", 1).put("i", 0).put("a", 2).put("c", "received").put("at", ago(3)))
            .put("104", JSONObject().put("st", "collecting").put("n", 3).put("b", 0).put("i", 0).put("a", 1).put("c", "not-yet").put("at", ago(30)))
            .put("107", JSONObject().put("st", "published").put("n", 6).put("b", 2).put("i", 1).put("a", 2).put("c", "received").put("at", ago(300))))
        .put("repairs", JSONObject()).put("visits", JSONObject()).put("jobs", JSONArray()).put("assigned", JSONArray()).put("confirmed", JSONObject())
    private fun story0(id: String, st: String) = JSONObject().put("id", id).put("status", st).put("consent", "not-yet").put("answers", JSONObject().put("q1", "Before, we walked 4 km"))
        .put("quote", "").put("quoteBy", "").put("credit", "").put("text", "").put("photos", JSONObject().put("before", JSONArray()).put("progress", JSONArray()).put("after", JSONArray()))
        .put("missing", JSONArray()).put("canApprove", false).put("prompts", JSONObject())
    private fun story(id: String, st: String) = story0(id, st).apply {
        if (st == "published") {
            put("text", "Water at the school gate").put("pub", JSONObject().put("url", "https://test.rafikioutreach.org/stories/107-water").put("at", ago(1)))
            put("doc", JSONObject().put("v", 1).put("title", "Water at the school gate").put("dek", "How a dry hillside got its own clean water.").put("at", ago(2)).put("by", "Reid").put("model", "template")
                .put("pull", JSONObject().put("quote", "Now we drink clean water every day.").put("who", "Mary"))
                .put("sections", org.json.JSONArray().put(JSONObject().put("h", "The long walk").put("body", "Families walked four kilometres.\n\nThe water was brown.")).put(JSONObject().put("h", "Today").put("body", "Children fill their cans at the gate."))))
        }
    }

    private fun model(admin: Boolean, chat: Int = 0): AppModel {
        Onb.enabled = false; Motion.on = false
        Prefs(app).apply { pin = "9999"; name = if (admin) "Reid Anderson" else "Meril Kiprop"; saveSnapshot(snapshot(admin)); filter = "all"; country = ""; ftype = ""; onb = true; lang = if (Lang.sw) "sw" else "en"; phoneAlerts = false; meHint = true }
        val m = AppModel(app)
        m.api.transport = { method, u, _, _ ->
            val path = u.removePrefix(m.api.server)
            when {
                path.startsWith("/api/story") && method == "GET" -> { val id = Regex("id=(\\d+)").find(u)?.groupValues?.get(1) ?: "104"
                    200 to JSONObject().put("ok", true).put("story", story(id, mapOf("101" to "draft", "104" to "collecting", "107" to "published")[id] ?: "not-started")).toString() }
                u.contains("?id=") -> 200 to "{\"ok\":true,\"last\":null}"
                u.contains("activity=1") -> 200 to "{\"ok\":true,\"activity\":[]}"
                u.contains("view=") -> 200 to "{\"ok\":true,\"team\":[],\"items\":[]}"
                u.contains("pin-auth/me") -> 200 to "{\"ok\":true,\"available\":false}"
                u.contains("version.json") || u.contains("/api/chat") -> 0 to ""
                method == "GET" -> 200 to snapshot(admin).toString()
                else -> 200 to "{\"ok\":true}"
            }
        }
        return m
    }
    private var w by mutableStateOf(390); private var h by mutableStateOf(844); private var dark by mutableStateOf(false)
    private fun start(m: AppModel) {
        rule.setContent { RafikiTheme(dark = dark) { Box(Modifier.size(w.dp, h.dp)) { App(m) } } }
        rule.waitUntil(10000) { tagged("nav_stories") }; settle(1500)
    }
    private fun tagged(t: String) = rule.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitTag(t: String) = rule.waitUntil(10000) { tagged(t) }
    private fun settle(ms: Long = 800) { rule.mainClock.advanceTimeBy(ms); rule.waitForIdle() }
    private fun tap(t: String) {
        rule.onNodeWithTag(t).let { n -> runCatching { n.performScrollTo() } }
        val target = rule.onAllNodes(hasTestTag(t) and hasClickAction()).fetchSemanticsNodes().let { if (it.isNotEmpty()) rule.onNode(hasTestTag(t) and hasClickAction()) else rule.onAllNodes(hasAnyAncestor(hasTestTag(t)) and hasClickAction()).onFirst() }
        target.performSemanticsAction(SemanticsActions.OnClick); settle()
    }
    /** The phone's back: a sheet first (its own window), as the system does. */
    private fun back() {
        rule.runOnUiThread {
            val d = ShadowDialog.getLatestDialog()
            @Suppress("DEPRECATION")
            if (d != null && d.isShowing) d.onBackPressed() else rule.activity.onBackPressedDispatcher.onBackPressed()
        }
        settle()
    }
    private fun text(t: String) = LayoutCheck.text(rule.onNodeWithTag(t, useUnmergedTree = true).fetchSemanticsNode())
    private fun shot(name: String) {
        settle()
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); val c = Canvas(b); v.draw(c)
        val d = ShadowDialog.getShownDialogs().lastOrNull { it.isShowing }?.window?.decorView
        if (d != null && d.width > 0) { c.drawColor(0x66000000); c.save(); c.translate(0f, (v.height - d.height).toFloat().coerceAtLeast(0f)); d.draw(c); c.restore() }
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun rows(root: String) = rule.onAllNodes(hasAnyAncestor(hasTestTag(root)) and SemanticsMatcher("a project row") { n -> n.config.getOrNull(SemanticsProperties.TestTag)?.let { Regex("sh_(p|pick)_\\d+").matches(it) } == true })
        .fetchSemanticsNodes().sortedBy { it.positionInRoot.y }.map { it.config[SemanticsProperties.TestTag].substringAfterLast('_') }
    private fun noLayoutProblems(root: String, label: String) = assertEquals("$label: $root", emptyList<String>(), LayoutCheck.problems(rule, root, w * rule.density.density))

    /** Six tabs: every tab inside the bar, labels whole (never cut, never split inside a word), badges inside their tab. */
    private fun barChecks(label: String, tabs: List<String>) {
        val bar = rule.onNodeWithTag("bottom_bar", useUnmergedTree = true).fetchSemanticsNode()
        val bl = bar.positionInRoot.x; val br = bl + bar.size.width
        val shown = tabs.filter { tagged("nav_$it") || tagged("nav_${it}_tab") }
        assertEquals("$label: tabs", tabs.size, shown.size)
        noLayoutProblems("bottom_bar", label)
        // the bar keeps its usual height, even with a label on two lines (the map's room depends on it)
        assertEquals("$label: bar height", 68f, bar.size.height / rule.density.density, 0.6f)
        for (k in tabs) {
            val n = rule.onNodeWithTag(if (k == "me") "nav_me_tab" else "nav_$k", useUnmergedTree = true).fetchSemanticsNode()
            val l = n.positionInRoot.x; val r = l + n.size.width
            assertTrue("$label: $k inside the bar", l >= bl - 1 && r <= br + 1)
            val lab = rule.onNodeWithTag("nav_label_$k", useUnmergedTree = true).fetchSemanticsNode()
            assertTrue("$label: $k label inside its tab", lab.positionInRoot.x >= l - 1 && lab.positionInRoot.x + lab.size.width <= r + 1)
        }
        for (b in listOf("stories_badge", "office_badge", "chat_badge", "me_tab_waiting")) if (tagged(b)) {
            val n = rule.onNodeWithTag(b, useUnmergedTree = true).fetchSemanticsNode()
            val k = when (b) { "stories_badge" -> "nav_stories"; "office_badge" -> "nav_office"; "chat_badge" -> "nav_chat"; else -> "nav_me_tab" }
            val t = rule.onNodeWithTag(k, useUnmergedTree = true).fetchSemanticsNode()
            assertTrue("$label: $b inside its tab", n.positionInRoot.x >= t.positionInRoot.x - 1 && n.positionInRoot.x + n.size.width <= t.positionInRoot.x + t.size.width + 1)
            assertTrue("$label: $b inside the bar", n.positionInRoot.y >= bar.positionInRoot.y)
        }
    }

    // --- 1. admins: the hub opens on what waits for review; the writer and back ----------------------------------------
    @Test fun admin_hub_writer_and_back() {
        val m = model(admin = true); start(m)
        assertEquals("1", text("stories_badge"))
        tap("nav_stories"); waitTag("stories")
        rule.onNodeWithTag("nav_stories").assertIsSelected()
        assertEquals(listOf("3", "1", "0", "1"), listOf("need", "draft", "approved", "published").map { text("sh_f_${it}_n") })
        rule.onNodeWithTag("sh_f_draft").assertIsSelected()
        assertEquals(listOf("101"), rows("sh_list")); assertTrue(tagged("sh_note"))
        assertEquals("6\u00A0of\u00A06\u00A0answers\u00A0· 3\u00A0photos\u00A0· consent\u00A0received", text("sh_p_101_line"))
        rule.onAllNodes(hasAnyAncestor(hasTestTag("sh_p_101_pill")) and hasText("Waiting for review"), useUnmergedTree = true).onFirst().assertExists()
        noLayoutProblems("stories", "390 en admin")
        shot("sh-01-hub-admin-390-en")
        // needs a story: collecting first, then the completed ones
        tap("sh_f_need")
        assertEquals("104", rows("sh_list").first()); assertEquals(3, rows("sh_list").size); assertFalse(tagged("sh_note"))
        // how a story gets to the website: a fold, closed by back
        tap("sh_how_toggle"); waitTag("sh_how_3"); rule.onNodeWithTag("sh_how").performScrollTo(); shot("sh-02-how-390-en")
        back(); assertFalse(tagged("sh_how_body")); assertTrue("still on Stories", tagged("stories"))
        // open a project: its page on the Stories tab, the writer at the top; Stories stays lit
        tap("sh_p_104"); waitTag("story_status"); settle(1500)
        rule.onNodeWithTag("nav_stories").assertIsSelected()
        val tabsY = rule.onNodeWithTag("prj_tabs").fetchSemanticsNode().positionInRoot.y
        val head = rule.onNodeWithTag("hback").fetchSemanticsNode().let { it.positionInRoot.y + it.size.height }
        assertTrue("the writer is in view, the tabs near the top ($tabsY, header ends $head)", tabsY >= head - 1 && tabsY < head + 40 * rule.density.density)
        assertFalse("admins don't get the review note", tagged("sty_send"))
        shot("sh-03-writer-admin")
        // back: the hub, as it was left (Needs a story), Stories still lit
        back(); waitTag("stories"); rule.onNodeWithTag("nav_stories").assertIsSelected(); rule.onNodeWithTag("sh_f_need").assertIsSelected()
        // re-tapping Stories: one screen straight away (no fade through), it starts over (admins on Waiting for review)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("nav_stories").performClick(); rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeByFrame()
        assertEquals("one screen, straight away", 1, rule.onAllNodesWithTag("stories").fetchSemanticsNodes().size)
        rule.mainClock.autoAdvance = true; settle()
        rule.onNodeWithTag("sh_f_draft").assertIsSelected()
        // back from Stories: Map home
        back(); waitTag("map_home"); rule.onNodeWithTag("nav_map").assertIsSelected()
    }

    @Test fun a_story_with_words_view_or_edit() {
        val m = model(admin = true); start(m)
        tap("nav_stories"); waitTag("stories"); tap("sh_f_published")
        tap("sh_p_107"); waitTag("sh_choose"); assertTrue(tagged("sh_view") && tagged("sh_edit")); shot("sh-10-choose")
        tap("sh_view"); waitTag("sh_read_title")
        assertEquals("Water at the school gate", text("sh_read_title")); assertTrue("Open on the website at the top", tagged("sh_web"))
        val web = rule.onNodeWithTag("sh_web").fetchSemanticsNode(); val back = rule.onNodeWithTag("sh_read_back").fetchSemanticsNode()
        assertTrue("same top bar", kotlin.math.abs(web.positionInRoot.y + web.size.height / 2 - (back.positionInRoot.y + back.size.height / 2)) < 4 * rule.density.density)
        shot("sh-11-read")
        back(); settle(); assertFalse(tagged("sh_read_title")); assertTrue(tagged("stories"))
        tap("sh_p_107"); waitTag("sh_edit"); tap("sh_edit"); waitTag("story_status")
        back(); waitTag("stories")
        // still collecting: straight to the writer
        tap("sh_f_need"); tap("sh_p_104"); waitTag("story_status"); assertFalse(tagged("sh_choose"))
    }

    // --- 2. Write a story: the project picker ---------------------------------------------------------------------------
    @Test fun write_a_story_picker_search_and_open() {
        val m = model(admin = false); start(m)
        tap("nav_stories"); waitTag("stories")
        tap("sh_write"); waitTag("sh_pick_sheet"); settle()
        assertEquals("collecting first", "104", rows("sh_pick_sheet").first()); assertEquals(5, rows("sh_pick_sheet").size)
        shot("sh-04-picker-390-en")
        rule.onNodeWithTag("sh_q").performTextInput("mzuzu"); settle()
        assertEquals(listOf("107"), rows("sh_pick_sheet"))
        rule.onNodeWithTag("sh_q").performTextClearance(); rule.onNodeWithTag("sh_q").performTextInput("nowhere"); settle(); assertTrue(tagged("sh_pick_none"))
        // back closes the sheet, not the screen
        back(); settle(); assertFalse(tagged("sh_pick_sheet")); assertTrue(tagged("stories"))
        tap("sh_write"); waitTag("sh_pick_sheet"); rule.onNodeWithTag("sh_q").performTextInput("kapsaret"); settle()
        shot("sh-05-picker-search")
        tap("sh_pick_104"); waitTag("story_status"); settle(1500)
        assertFalse(tagged("sh_pick_sheet")); rule.onNodeWithTag("nav_stories").assertIsSelected()
        // field users: drafting sends it for review
        rule.onNodeWithTag("sty_send").performScrollTo()
        assertEquals("“Draft the story” sends it to an admin for review. Nothing goes on the website before it is approved.", text("sty_send"))
        assertTrue("under the Draft button", rule.onNodeWithTag("sty_send").fetchSemanticsNode().positionInRoot.y > rule.onNodeWithTag("story_draft").fetchSemanticsNode().positionInRoot.y)
        shot("sh-06-writer-field-note")
        back(); waitTag("stories"); rule.onNodeWithTag("nav_stories").assertIsSelected()
    }

    @Test fun field_user_opens_on_needs_a_story_and_has_no_badge() {
        start(model(admin = false))
        assertFalse(tagged("stories_badge")); assertFalse(tagged("nav_me_tab")); assertTrue(tagged("nav_me")); assertFalse(tagged("nav_office"))
        tap("nav_stories"); waitTag("stories")
        rule.onNodeWithTag("sh_f_need").assertIsSelected()
        tap("sh_f_draft"); assertEquals("An admin reads these and approves them for the website.", text("sh_note"))
        tap("sh_f_approved"); assertTrue(tagged("sh_none")); shot("sh-07-empty")
    }

    // --- 3. the bar and the hub at every size, theme and language --------------------------------------------------------
    private fun looks(width: Int, height: Int, sw: Boolean, mode: String, admin: Boolean) {
        Lang.sw = sw; w = width; h = height; dark = mode == "dark"; Look.mode = if (mode == "sun") "sun" else ""
        val m = model(admin)
        // badges on: chat unread, drafts (admin), what's waiting to send (field)
        start(m)
        val tag = "$width-${if (sw) "sw" else "en"}-$mode-${if (admin) "admin" else "field"}"
        val tabs = listOf("map", "list", "new", "chat", "stories") + (if (admin) listOf("office") else emptyList())
        barChecks(tag, tabs)
        tap("nav_stories"); waitTag("stories"); settle()
        noLayoutProblems("stories", tag)
        if (sw) assertEquals("Kila mradi una hadithi", LayoutCheck.text(rule.onAllNodes(hasAnyAncestor(hasTestTag("sh_hero")) and hasText("Kila", substring = true), useUnmergedTree = true).onFirst().fetchSemanticsNode()))
        // two by two on a phone
        val a = rule.onNodeWithTag("sh_f_need").fetchSemanticsNode().positionInRoot; val b = rule.onNodeWithTag("sh_f_approved").fetchSemanticsNode().positionInRoot
        assertTrue("$tag: 2×2", b.y > a.y && kotlin.math.abs(b.x - a.x) < 2f)
        shot("sh-10-hub-$tag")
        rule.onNodeWithTag("sh_list").performScrollTo(); shot("sh-11-list-$tag")
        tap("sh_how_toggle"); waitTag("sh_how_3"); noLayoutProblems("sh_how", tag); rule.onNodeWithTag("sh_how").performScrollTo(); shot("sh-12-how-$tag")
        back()
        tap("sh_write"); waitTag("sh_pick_sheet"); settle(); shot("sh-13-picker-$tag"); back()
    }
    @Config(qualifiers = "w320dp-h640dp-xhdpi") @Test fun looks_320_en_light_admin() = looks(320, 640, false, "light", true)
    @Config(qualifiers = "w320dp-h640dp-xhdpi") @Test fun looks_320_sw_dark_admin() = looks(320, 640, true, "dark", true)
    @Config(qualifiers = "w320dp-h640dp-xhdpi") @Test fun looks_320_sw_sun_field() = looks(320, 640, true, "sun", false)
    @Config(qualifiers = "w320dp-h640dp-xhdpi") @Test fun looks_320_en_dark_field() = looks(320, 640, false, "dark", false)
    @Config(qualifiers = "w360dp-h740dp-xxhdpi") @Test fun looks_360_sw_light_field() = looks(360, 740, true, "light", false)
    @Config(qualifiers = "w360dp-h740dp-xxhdpi") @Test fun looks_360_en_sun_admin() = looks(360, 740, false, "sun", true)
    @Config(qualifiers = "w411dp-h891dp-xxhdpi") @Test fun looks_411_sw_dark_admin() = looks(411, 891, true, "dark", true)
    @Config(qualifiers = "w411dp-h891dp-xxhdpi") @Test fun looks_411_en_light_field() = looks(411, 891, false, "light", false)

    /** The bar with every badge at its widest ("99+" chat, drafts, review), 320 dp. */
    private fun badges(sw: Boolean) {
        Lang.sw = sw; w = 320; h = 640
        val m = model(admin = true)
        rule.setContent { RafikiTheme(dark = false) { androidx.compose.foundation.layout.Column(Modifier.size(320.dp, 120.dp)) { BottomBar(m, "stories", review = 12, chat = 140) { } } } }
        settle()
        barChecks("badges ${if (sw) "sw" else "en"}", listOf("map", "list", "new", "chat", "stories", "office"))
        assertEquals("99+", text("chat_badge")); assertEquals("12", text("office_badge")); assertEquals("1", text("stories_badge"))
        val v = rule.activity.window.decorView
        val bm = Bitmap.createBitmap(v.width, (120 * rule.density.density).toInt(), Bitmap.Config.ARGB_8888); v.draw(Canvas(bm))
        File(out, "sh-20-bar-badges-320-${if (sw) "sw" else "en"}.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Config(qualifiers = "w320dp-h640dp-xhdpi") @Test fun bar_badges_stay_inside_at_320_en() = badges(false)
    @Config(qualifiers = "w320dp-h640dp-xhdpi") @Test fun bar_badges_stay_inside_at_320_sw() = badges(true)
}

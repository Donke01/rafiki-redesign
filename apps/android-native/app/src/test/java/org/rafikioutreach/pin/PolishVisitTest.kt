package org.rafikioutreach.pin

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
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
import org.rafikioutreach.pin.ui.Dates
import org.rafikioutreach.pin.ui.Look
import org.rafikioutreach.pin.ui.RafikiTheme
import org.rafikioutreach.pin.ui.Shot
import org.rafikioutreach.pin.ui.Thousands
import org.rafikioutreach.pin.ui.VisitDraft
import org.rafikioutreach.pin.ui.VisitDrafts
import org.rafikioutreach.pin.ui.problemName
import org.rafikioutreach.pin.ui.typeName
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Polish round (pavisit): the visit kept through Android closing the app, the decluttered project page, the checklist
 *  pill, money fields, and dates and names in the app's language. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class PolishVisitTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val posts = mutableListOf<String>()

    private fun proj(id: String, t: String, type: String, gps: JSONArray?) = JSONObject().put("id", id).put("title", t).put("country", "Kenya").put("ck", "ke")
        .put("area", "Eldoret").put("type", type).put("status", "Completed").put("pump", if (id == "104") "manual" else "").apply { if (gps != null) put("gps", gps) }
    private fun snapshot() = JSONObject().put("ok", true).put("who", "Meril Kiprop").put("admin", false)
        .put("projects", JSONArray().put(proj("104", "Kapsaret Borehole", "Borehole well", JSONArray().put(0.47).put(35.25))).put(proj("106", "Kiptoo Primary School", "School", JSONArray().put(0.48).put(35.26))))
        .put("repairs", JSONObject().put("104", JSONObject().put("state", "repair").put("by", "Bertin").put("at", "2026-10-18T08:00:00Z").put("note", "Handle loose").put("urgency", "month").put("problems", JSONArray().put("Pump works"))))
        .put("visits", JSONObject()).put("jobs", JSONArray()).put("assigned", JSONArray()).put("confirmed", JSONObject())

    private fun model(): AppModel {
        org.rafikioutreach.pin.ui.Onb.enabled = false
        Prefs(app).apply { pin = "1111"; name = "Meril Kiprop"; saveSnapshot(snapshot()); filter = "all" }
        org.rafikioutreach.pin.ui.clearDrafts()
        val m = AppModel(app)
        m.api.transport = { method, url, _, body ->
            synchronized(posts) { posts += "$method $url ${body?.let { String(it).take(4000) } ?: ""}" }
            when { url.contains("history=") -> 200 to "{\"ok\":true,\"notes\":[]}"; method == "GET" -> 200 to snapshot().toString(); else -> 200 to "{\"ok\":true,\"updateId\":\"9001\"}" }
        }
        return m
    }

    private var cur by mutableStateOf<AppModel?>(null)
    private fun show(m: AppModel, dark: Boolean = false, w: Int = 390, h: Int = 844) {
        cur = m
        rule.setContent { RafikiTheme(dark = dark) { Box(Modifier.size(w.dp, h.dp)) { cur?.let { c -> key(c) { App(c) } } } } }
    }
    /** As if Android closed the app: everything in memory goes, and the app starts again. */
    private fun restart() { org.rafikioutreach.pin.ui.clearDrafts(); cur = model(); rule.waitForIdle() }

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
    private fun has(t: String) = rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun hasTag(t: String) = rule.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty()
    private fun open(t: String) { rule.waitForIdle(); rule.onNodeWithTag("projects").performScrollToNode(hasText(t)); rule.onNodeWithText(t).performClick(); rule.waitForIdle() }
    private fun visit() { rule.onNodeWithTag("tab_visit").performScrollTo().performClick(); rule.waitForIdle() }
    /** A condition choice (the repair tag above says "Needs repair" too). */
    private fun choose(text: String) { rule.onNode(hasText(text) and androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.RadioButton)).performScrollTo().performClick(); rule.waitForIdle() }
    private fun tap(tag: String) { rule.onNodeWithTag(tag).performScrollTo().performClick(); rule.waitForIdle() }
    private fun next() { rule.onNodeWithText(if (Lang.sw) "Endelea" else "Next").performScrollTo().performClick(); rule.waitForIdle() }
    private fun type(tag: String, text: String) = rule.onNode(hasSetTextAction() and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag)))).performScrollTo().performTextInput(text)
    private val draft get() = File(app.filesDir, "visits/104.json")

    @After fun reset() { Lang.sw = false; Look.mode = ""; Dates.today = { LocalDate.now(Dates.zone()) }; Dates.zone = { java.time.ZoneId.systemDefault() } }

    @Test fun dates_names_and_money_follow_the_app_language() {
        Dates.zone = { ZoneOffset.UTC }; Dates.today = { LocalDate.of(2026, 10, 22) }
        Lang.sw = false
        assertEquals("12 Sep", Dates.day("2026-09-12"))
        assertEquals("Sat 24 Oct", Dates.weekday("2026-10-24"))
        assertEquals("24 Oct 2025", Dates.weekday("2025-10-24"))
        assertEquals("18 Oct, 08:00", Dates.dateTime("2026-10-18T08:00:00Z"))
        assertEquals("3 Mar 2025, 14:05", Dates.dateTime("2025-03-03T14:05:00Z"))
        assertEquals(listOf("Today", "Tomorrow", "In 2 days", "1 day late", "3 days late", "Mon 2 Nov"), listOf("2026-10-22", "2026-10-23", "2026-10-24", "2026-10-21", "2026-10-19", "2026-11-02").map { Dates.due(it) })
        assertEquals("not a date", Dates.day("not a date"))
        assertEquals("Borehole well", typeName("Borehole well")); assertEquals("Pump works", problemName("Pump works"))
        Lang.sw = true
        assertEquals(listOf("Jan", "Feb", "Mac", "Apr", "Mei", "Jun", "Jul", "Ago", "Sep", "Okt", "Nov", "Des"), (1..12).map { Dates.month(it) })
        assertEquals("Jmo 24 Okt", Dates.weekday("2026-10-24")); assertEquals("Jtt 2 Nov", Dates.weekday("2026-11-02"))
        assertEquals(listOf("Leo", "Kesho", "Baada ya siku 2", "Imechelewa siku 3"), listOf("2026-10-22", "2026-10-23", "2026-10-24", "2026-10-19").map { Dates.due(it) })
        assertEquals("Kisima kirefu", typeName("Borehole well")); assertEquals("Shule", typeName("School")); assertEquals("Spring protection", typeName("Spring protection"))
        assertEquals("Pampu inafanya kazi", problemName("Pump works")); assertEquals("Handle loose", problemName("Handle loose"))
        // money: "4,500" on screen, "4500" kept; the cursor keeps its place among the digits
        assertEquals(listOf("", "5", "450", "4,500", "45,000", "1,234,567"), listOf("", "5", "450", "4500", "45000", "1234567").map { Thousands.group(it) })
        val tx = Thousands.filter(AnnotatedString("1234567"))
        assertEquals("1,234,567", tx.text.text)
        assertEquals(listOf(0, 1, 3, 4, 5, 7, 8, 9), (0..7).map { tx.offsetMapping.originalToTransformed(it) })
        assertEquals(7, tx.offsetMapping.transformedToOriginal(9)); assertEquals(1, tx.offsetMapping.transformedToOriginal(2))
    }

    @Test fun a_draft_keeps_every_field_and_its_photos() {
        val dir = File(app.filesDir, "photos").apply { mkdirs() }
        val a = File(dir, "a.jpg").apply { writeText("x") }; val gone = File(dir, "gone.jpg")
        val v = VisitDraft().apply {
            step = 4; arrived = "2026-10-22T07:42:00Z"; state = "repair"; urgency = "month"; check["handle"] = "bad"; check["rods"] = "ok"; cost = "4500"; fund = "3000"
            nums["animals"] = "18"; quote = "Asante"; consent = true; shots = listOf(Shot(a, "Water flowing"), Shot(gone, "Any damage"))
        }
        assertTrue(VisitDraft().blank()); assertFalse(v.blank())
        assertTrue(VisitDrafts.save(app, "104", v.toJson()))
        val k = VisitDrafts.load(app, "104")!!
        assertEquals(Instant.parse("2026-10-22T07:42:00Z").toEpochMilli(), k.started); assertEquals(2, k.photos)
        val back = VisitDraft().apply { fill(k.json) }
        assertEquals(4, back.step); assertEquals("repair", back.state); assertEquals("month", back.urgency); assertEquals("bad", back.check["handle"]); assertEquals("ok", back.check["rods"])
        assertEquals("4500", back.cost); assertEquals("3000", back.fund); assertEquals("18", back.nums["animals"]); assertEquals("Asante", back.quote); assertTrue(back.consent)
        assertEquals("a photo no longer on the phone is left out", listOf(a.path), back.shots.map { it.file.path }); assertEquals("Water flowing", back.shots[0].label)
        // Start again: the kept visit and its photos go
        VisitDrafts.discard(app, "104")
        assertFalse(VisitDrafts.has(app, "104")); assertFalse(a.exists())
    }

    @Test fun a_half_done_visit_survives_android_closing_the_app() {
        Lang.sw = false
        show(model())
        open("Kapsaret Borehole"); visit()
        rule.onNodeWithText("I’ve arrived").performScrollTo().performClick(); rule.waitForIdle()
        assertTrue(has("Step 2 of 7 · Condition"))
        choose("Needs repair")
        rule.onNodeWithText("This month").performScrollTo().performClick(); next()
        assertTrue(has("Step 3 of 7 · Checklist"))
        tap("ck_handle_bad"); tap("ck_rods_ok")
        type("cost", "4500")
        rule.waitUntil(5000) { draft.exists() && draft.readText().contains("\"cost\":\"4500\"") }
        assertTrue("shows grouped", rule.onAllNodes(hasText("4,500") and (hasTestTag("cost") or hasAnyAncestor(hasTestTag("cost")))).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithTag("cost").performScrollTo(); shot("pv-06-money")

        restart()
        open("Kapsaret Borehole"); visit()
        rule.onNodeWithTag("visit_resume").assertExists()
        assertTrue(has("Carry on with your visit from") && has("step 3 of 7") && has("Resume") && has("Start again"))
        rule.onNodeWithTag("visit_resume").performScrollTo(); shot("pv-10-resume")
        tap("resume_yes")
        assertTrue("back on the checklist", has("Step 3 of 7 · Checklist"))
        rule.onNodeWithTag("cost").performScrollTo()
        assertTrue(rule.onAllNodes(hasText("4,500") and (hasTestTag("cost") or hasAnyAncestor(hasTestTag("cost")))).fetchSemanticsNodes().isNotEmpty())
        repeat(4) { next() }
        rule.onNodeWithText("Send visit report").performScrollTo().performClick()
        rule.waitUntil(8000) { synchronized(posts) { posts.any { it.contains("\"action\":\"visit\"") } } }
        val p = synchronized(posts) { posts.first { it.contains("\"action\":\"visit\"") } }
        assertTrue(p, p.contains("\"state\":\"repair\"") && p.contains("\"urgency\":\"month\"") && p.contains("\"cost\":4500") && p.contains("Pump handle and pivot pins") && p.contains("\"arrive\""))
        rule.waitUntil(5000) { !draft.exists() }
    }

    @Test fun start_again_in_kiswahili_dark() {
        Lang.sw = true; Look.mode = "dark"
        show(model(), dark = true)
        open("Kapsaret Borehole"); visit()
        rule.onNodeWithText("Nimefika").performScrollTo().performClick(); rule.waitForIdle()
        choose("Inahitaji matengenezo")
        rule.waitUntil(5000) { draft.exists() && draft.readText().contains("repair") }
        restart()
        open("Kapsaret Borehole"); visit()
        assertTrue(has("Uendelee na ziara uliyoanza") && has("Endelea nayo") && has("Anza upya"))
        rule.onNodeWithTag("visit_resume").performScrollTo(); shot("pv-11-resume-sw-dark")
        tap("resume_no")
        assertFalse(draft.exists()); assertFalse(hasTag("visit_resume"))
        assertTrue(has("Hatua 1 kati ya 7 · Fika") && has("Nimefika"))
    }

    @Test fun project_page_decluttered() {
        Lang.sw = false
        show(model())
        open("Kapsaret Borehole")
        assertTrue("fix tag", has("Fix this month") && !has("This month · Needs repair")); assertTrue("one Directions", has("Directions")); assertTrue("no Google Maps tile", rule.onAllNodesWithText("Google Maps").fetchSemanticsNodes().isEmpty())
        assertTrue("problem names", has("Problems: Pump works"))
        assertFalse("extras folded away", hasTag("share_summary"))
        shot("pv-01-project")
        tap("prj_dirs")
        rule.waitUntil(5000) { has("Another map app") }
        assertTrue(has("Google Maps") && has("Turn-by-turn directions"))
        sheetShot("pv-02-directions")
        rule.onNodeWithTag("dir_close").performClick(); rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        rule.waitUntil(5000) { !has("Another map app") }
        tap("prj_more_toggle")
        assertTrue(hasTag("share_summary") && hasTag("photo_timeline_go") && hasTag("dn_thanks"))
        rule.onNodeWithTag("prj_more").performScrollTo(); shot("pv-03-project-more")
    }

    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    @Test fun project_page_kiswahili_dark_360() {
        Lang.sw = true; Look.mode = "dark"
        show(model(), dark = true, w = 360, h = 780)
        open("Kapsaret Borehole")
        assertTrue(has("Rekebisha mwezi huu") && has("Maelekezo") && has("Shiriki mahali") && has("Matatizo: Pampu inafanya kazi"))
        shot("pv-04-project-sw-dark-360")
        tap("prj_more_toggle"); rule.onNodeWithTag("prj_more").performScrollTo()
        assertTrue(has("Shiriki muhtasari wa mradi"))
        shot("pv-05-project-more-sw-dark-360")
        tap("prj_dirs"); rule.waitUntil(5000) { has("Programu nyingine ya ramani") }
        sheetShot("pv-05b-directions-sw-dark-360")
    }

    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    @Test fun checklist_pill_and_choices_360() {
        Lang.sw = false
        show(model(), w = 360, h = 780)
        open("Kapsaret Borehole"); visit()
        rule.onNodeWithText("I’ve arrived").performScrollTo().performClick(); rule.waitForIdle()
        shot("pv-07-condition-360")
        choose("Needs repair"); next()
        tap("ck_handle_ok"); tap("ck_rods_bad")
        rule.onNodeWithTag("ck_handle").performScrollTo(); shot("pv-08-checklist-360")
        // tapping the chosen half again clears it
        tap("ck_rods_bad"); tap("ck_rods_bad")
        Lang.sw = true; rule.waitForIdle()
        assertTrue(has("Hatua 3 kati ya 7 · Ukaguzi") && has("Sawa") && has("Tatizo"))
        rule.onNodeWithTag("ck_handle").performScrollTo(); shot("pv-09-checklist-sw-360")
        Lang.sw = false
        rule.onNodeWithTag("nav_new").performClick(); rule.waitForIdle()
        assertTrue(has("The office checks every new site first") && !has("Due Diligence"))
        shot("pv-12-new-site-360")
    }

    @Test fun checklist_kiswahili_dark() {
        Lang.sw = true; Look.mode = "dark"
        show(model(), dark = true)
        open("Kapsaret Borehole"); visit()
        rule.onNodeWithText("Nimefika").performScrollTo().performClick(); rule.waitForIdle()
        choose("Inahitaji matengenezo"); next()
        tap("ck_handle_ok"); tap("ck_rods_bad"); type("cost", "12500")
        rule.onNodeWithTag("ck_handle").performScrollTo(); shot("pv-13-checklist-sw-dark")
        rule.onNodeWithTag("cost").performScrollTo(); shot("pv-14-money-sw-dark")
        Lang.sw = false; rule.waitForIdle()
        rule.onNodeWithTag("nav_new").performClick(); rule.waitForIdle()
        shot("pv-15-new-site-dark")
    }
}

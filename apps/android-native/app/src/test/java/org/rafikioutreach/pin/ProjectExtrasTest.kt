package org.rafikioutreach.pin

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.rafikioutreach.pin.data.Note
import org.rafikioutreach.pin.data.NoteFile
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.data.Snapshot
import org.rafikioutreach.pin.ui.Look
import org.rafikioutreach.pin.ui.PhotoViewerBody
import org.rafikioutreach.pin.ui.PrjPics
import org.rafikioutreach.pin.ui.RafikiTheme
import org.rafikioutreach.pin.ui.SummaryProbe
import org.rafikioutreach.pin.ui.buildSummary
import org.rafikioutreach.pin.ui.drawSummaryPage
import org.rafikioutreach.pin.ui.photoGroups
import org.rafikioutreach.pin.ui.testCount
import org.rafikioutreach.pin.ui.testDue
import org.rafikioutreach.pin.ui.writeSummaryPdf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

/** A project's photo timeline, the donor-ready summary (PDF on the letterhead) and "Water test due". */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class ProjectExtrasTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun ago(days: Long) = Instant.now().minus(days, ChronoUnit.DAYS).toString()
    private fun stamp(days: Long) = Instant.now().minus(days, ChronoUnit.DAYS).toString().take(10) + "-09-14"
    private fun proj(id: String, t: String, type: String, status: String = "Completed", photo: String? = null) = JSONObject().put("id", id).put("title", t).put("country", "Kenya").put("ck", "ke")
        .put("area", "Eldoret").put("type", type).put("status", status).put("pump", "manual").put("year", 2019).put("gps", JSONArray().put(0.4 + id.toInt() * 0.001).put(35.2)).apply { if (photo != null) put("photo", photo) }
    private fun snapshot() = JSONObject().put("ok", true).put("who", "Meril Kiprop").put("admin", true).put("reviewOpen", 0)
        .put("projects", JSONArray().put(proj("104", "Kapsaret Borehole", "Borehole well", photo = "main104")).put(proj("105", "Turbo Spring", "Water collection"))
            .put(proj("106", "Kiptoo Primary School", "School")).put(proj("107", "Moiben Borehole", "Borehole well", "In Progress")))
        .put("repairs", JSONObject().put("104", JSONObject().put("state", "repair").put("by", "Grace Achieng").put("at", ago(40)).put("urgency", "month").put("t", "Kapsaret Borehole").put("problems", JSONArray().put("Handle loose"))))
        .put("visits", JSONObject().put("104", JSONObject().put("at", ago(40)).put("by", "Grace Achieng").put("state", "repair"))
            .put("105", JSONObject().put("at", ago(20)).put("by", "Meril Kiprop").put("state", "working")).put("106", JSONObject().put("at", ago(10)).put("by", "Meril Kiprop").put("state", "working")))
        .put("tests", JSONObject().put("105", ago(30)))
        .put("jobs", JSONArray()).put("assigned", JSONArray()).put("confirmed", JSONObject()).put("me", JSONObject().put("kind", "app").put("home", "ke"))

    private fun file(name: String, days: Long, url: String) = JSONObject().put("name", "site-photo-$name-${stamp(days)}.jpg").put("ext", "jpg").put("url", "https://files.test/$url.png")
    private fun notesJson() = JSONArray()
        .put(JSONObject().put("id", "3").put("at", ago(40)).put("by", "RAFIKI Pin").put("text", "🩺 Site visit by Grace Achieng (RAFIKI pin app): ⚠️ Needs repair.\nReadings: people using it (estimate): 350.\nStory moment: “Now my girls get to school on time.” (Mary, mother of three). Consent to quote and photograph: given.")
            .put("files", JSONArray().put(file("pump-or-tank-close-up", 40, "close-3")).put(file("water-flowing", 40, "flow-3")).put(file("the-site-with-people", 40, "site-3")).put(file("any-damage", 40, "damage-3"))
                .put(JSONObject().put("name", "voice-note-x.m4a").put("ext", "m4a").put("url", "https://files.test/v.m4a"))))
        .put(JSONObject().put("id", "2").put("at", ago(220)).put("by", "RAFIKI Pin").put("text", "🩺 Site visit by Bertin Mwangi (RAFIKI pin app): ✅ Working well.\nStory moment: “We used to walk two hours.” (Joseph). Consent to quote and photograph: not given.")
            .put("files", JSONArray().put(file("pump-or-tank-close-up", 220, "close-2")).put(file("the-site-with-people", 220, "site-2"))))
        .put(JSONObject().put("id", "1").put("at", ago(400)).put("by", "RAFIKI Pin").put("text", "📍 Location pinned with the RAFIKI pin app by Meril Kiprop: 0.47, 35.25 (GPS).")
            .put("files", JSONArray().put(JSONObject().put("name", "site-photo-${stamp(400)}.jpg").put("ext", "jpg").put("url", "https://files.test/pin-1.png"))))
        .put(JSONObject().put("id", "0").put("at", ago(420)).put("by", "RAFIKI Pin").put("text", "🩺 Site visit by Meril Kiprop (RAFIKI pin app): ✅ Working well.")
            .put("files", JSONArray().put(file("pump-or-tank-close-up", 420, "close-1"))))
    private fun notes(): List<Note> { val a = notesJson(); return (0 until a.length()).map { i -> a.getJSONObject(i).let { o -> val f = o.getJSONArray("files"); Note(o.getString("id"), o.getString("text"), o.getString("at"), o.getString("by"), (0 until f.length()).map { j -> f.getJSONObject(j).let { NoteFile(it.getString("name"), it.getString("ext"), it.getString("url")) } }) } } }
    private val visits = listOf(JSONObject().put("at", ago(40)).put("by", "Grace Achieng").put("state", "repair").put("readings", JSONObject().put("users", 350).put("strokes", 30)))

    /** A coloured test picture with stripes, so each slot looks different. */
    private fun pic(url: String): Bitmap {
        val n = url.substringAfterLast('/').substringBefore('.')
        val col = mapOf("close" to 0xFF0A78C2, "flow" to 0xFF1C9C5A, "site" to 0xFFE39A1E, "damage" to 0xFFD23C32, "pin" to 0xFF7A4FD0).entries.firstOrNull { n.startsWith(it.key) }?.value?.toInt() ?: 0xFF054F81.toInt()
        val b = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888); val c = Canvas(b); c.drawColor(col)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.WHITE; strokeWidth = 4f }
        for (x in 0 until 420 step 30) c.drawLine(x.toFloat(), 240f, x + 100f, 100f, p)
        c.drawRect(10f, 10f, 150f, 44f, p); c.drawText(n, 18f, 32f, Paint().apply { color = AColor.BLACK; textSize = 18f })
        return b
    }

    private fun model(): AppModel {
        org.rafikioutreach.pin.ui.Onb.enabled = false
        Prefs(app).apply { pin = "9999"; name = "Meril Kiprop"; saveSnapshot(snapshot()); filter = "all" }
        PrjPics.fake = { pic(it) }
        val m = AppModel(app)
        m.api.transport = { method, url, _, _ ->
            when {
                url.contains("history=104") -> 200 to JSONObject().put("ok", true).put("notes", notesJson()).toString()
                url.contains("history=") -> 200 to "{\"ok\":true,\"notes\":[]}"
                url.contains("?id=104") -> 200 to JSONObject().put("ok", true).put("visits", JSONArray(visits)).toString()
                url.contains("?id=") -> 200 to "{\"ok\":true,\"visits\":[]}"
                url.contains("activity=1") -> 200 to "{\"ok\":true,\"activity\":[]}"
                method == "GET" -> 200 to snapshot().toString()
                else -> 200 to "{\"ok\":true}"
            }
        }
        return m
    }
    @After fun reset() { Look.mode = ""; Lang.sw = false; PrjPics.fake = null }
    private fun show(m: AppModel, dark: Boolean = false) = rule.setContent { RafikiTheme(dark = dark) { Box(Modifier.size(390.dp, 844.dp)) { App(m) } } }
    private fun shot(name: String) {
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun has(t: String) = rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun open(t: String) { rule.waitForIdle(); rule.onNodeWithTag("projects").performScrollToNode(hasText(t)); rule.onNodeWithText(t).performClick(); rule.waitForIdle() }
    /** The extras sit under "More for this project", folded until tapped. */
    private fun more() {
        if (rule.onAllNodesWithTag("prj_more_body").fetchSemanticsNodes().isEmpty()) { rule.onNodeWithTag("prj_more_toggle").performScrollTo().performClick(); rule.waitForIdle() }
    }
    private fun timeline() {
        more()
        rule.onNodeWithTag("photo_timeline_go").performScrollTo().performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("photo_timeline").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("photo_timeline").performScrollTo(); rule.waitForIdle()
    }

    @Test fun water_test_due_rules() {
        val s = Snapshot.from(snapshot()); val by = s.projects.associateBy { it.id }
        assertTrue("never tested borehole", testDue(s, by.getValue("104")))
        assertFalse("tested a month ago", testDue(s, by.getValue("105")))
        assertFalse("not a water project", testDue(s, by.getValue("106")))
        assertFalse("not finished yet", testDue(s, by.getValue("107")))
        assertTrue("7 months after the last test it's due again", testDue(s.copy(tests = mapOf("105" to ago(213))), by.getValue("105")))
        assertEquals(1, testCount(s))
    }

    @Test fun photos_grouped_by_slot_and_summary_facts() {
        Lang.sw = false
        val s = Snapshot.from(snapshot()); val p = s.projects.first { it.id == "104" }
        val g = photoGroups(notes(), p, "https://x.test/main")
        assertEquals(listOf("Main photo", "Pump or tank, close up", "Water flowing", "The site with people", "Any damage", "Other photos"), g.map { it.en })
        val close = g[1].items
        assertEquals(listOf("close-3", "close-2", "close-1"), close.map { it.url.substringAfterLast('/').substringBefore('.') })
        assertEquals(listOf("Grace Achieng", "Bertin Mwangi", "Meril Kiprop"), close.map { it.by })
        assertTrue("no voice notes", g.none { gr -> gr.items.any { it.url.endsWith(".m4a") } })
        Lang.sw = true; assertEquals("Pampu au tangi, kwa karibu", g[1].title); Lang.sw = false
        val sum = buildSummary(p, s, notes(), visits, "https://x.test/main", "Meril Kiprop")
        assertEquals("Now my girls get to school on time.", sum.quote); assertEquals("Mary, mother of three", sum.quoteBy)
        assertEquals(350L, sum.people); assertTrue(sum.peopleLabel.contains("people use this water"))
        assertEquals(listOf("https://x.test/main", "https://files.test/site-3.png", "https://files.test/flow-3.png"), sum.photos.map { it.url })
        assertTrue("open repair", sum.facts.any { it.label == "Open repair" && it.value.contains("Handle loose") })
        assertTrue("no test date made up", sum.facts.none { it.label == "Water last tested" })
        // with nothing to go on, nothing is invented
        val bare = buildSummary(s.projects.first { it.id == "106" }, s, emptyList(), emptyList(), null, "Meril Kiprop")
        assertEquals(null, bare.people); assertEquals(null, bare.quote); assertTrue(bare.photos.isEmpty()); assertTrue(bare.facts.none { it.label == "Open repair" })
        // the page on the letterhead, drawn at 2x as a picture to look at
        val pics = sum.photos.map { pic(it.url) }
        val b = Bitmap.createBitmap(1190, 1684, Bitmap.Config.ARGB_8888); Canvas(b).apply { drawColor(AColor.WHITE); scale(2f, 2f); drawSummaryPage(app, sum, "ke", pics, this) }
        File(out, "90-project-summary-pdf.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Lang.sw = true
        val sw = buildSummary(p, s, notes(), visits, "https://x.test/main", "Meril Kiprop")
        val b2 = Bitmap.createBitmap(1190, 1684, Bitmap.Config.ARGB_8888); Canvas(b2).apply { drawColor(AColor.WHITE); scale(2f, 2f); drawSummaryPage(app, sw, "ke", pics, this) }
        File(out, "91-project-summary-pdf-sw.png").outputStream().use { b2.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Lang.sw = false
        val f = File(out, "project-summary-device.pdf"); f.delete()
        runCatching { writeSummaryPdf(app, sum, "ke", pics, f) }.onFailure { println("PdfDocument not available here: $it") }
        if (f.exists() && f.length() > 0) assertTrue(f.readBytes().copyOfRange(0, 4).decodeToString() == "%PDF")
    }

    @Test fun project_screen_timeline_viewer_and_share() {
        Lang.sw = false
        val m = model(); show(m)
        rule.waitForIdle(); rule.onNodeWithTag("projects").performScrollToNode(hasText("Kapsaret Borehole"))
        assertTrue("row tag", rule.onAllNodesWithTag("water_test_due", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        open("Kapsaret Borehole")
        assertTrue("extras folded away", rule.onAllNodesWithTag("share_summary").fetchSemanticsNodes().isEmpty())
        more(); rule.onNodeWithTag("share_summary").performScrollTo(); shot("92-project-test-due")
        assertTrue(has("Water test due") && has("Share project summary") && has("Photo timeline"))
        timeline(); shot("93-photo-timeline")
        assertTrue(has("Pump or tank, close up") && has("Water flowing") && has("Grace Achieng") && has("Bertin Mwangi") && has("8 photos · 4 visits"))
        rule.onNodeWithTag("strip_pump-or-tank-close-up").performScrollTo(); shot("94-photo-timeline-strips")
        // full screen: newest first, right goes back in time
        rule.onNodeWithTag("ph_pump-or-tank-close-up_0").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("viewer_count").assertExists(); assertTrue(has("1 / 3"))
        rule.onNodeWithTag("viewer_next").performClick(); rule.mainClock.advanceTimeBy(1000); rule.waitForIdle()
        assertTrue("older photo", has("2 / 3") && has("Bertin Mwangi"))
        rule.onNodeWithTag("viewer_close").performClick(); rule.waitForIdle()
        assertTrue(rule.onAllNodesWithTag("photo_viewer").fetchSemanticsNodes().isEmpty())
        // share: the summary is gathered from the notes, the visits and the photos
        SummaryProbe.last = null
        more(); rule.onNodeWithTag("share_summary").performScrollTo().performClick()
        rule.waitUntil(8000) { SummaryProbe.last != null }
        val s = SummaryProbe.last!!
        assertEquals("Kapsaret Borehole", s.title); assertEquals(350L, s.people); assertEquals(3, s.photos.size); assertNotNull(s.quote)
    }

    @Test fun viewer_full_screen() {
        Lang.sw = false
        val s = Snapshot.from(snapshot()); val g = photoGroups(notes(), s.projects.first { it.id == "104" }, null)[0]
        PrjPics.fake = { pic(it) }
        rule.setContent { RafikiTheme(dark = true) { Box(Modifier.size(390.dp, 844.dp)) { PhotoViewerBody(g, 1) {} } } }
        rule.waitForIdle(); assertTrue(has("2 / 3") && has("Bertin Mwangi")); shot("95-photo-viewer")
    }

    @Test fun due_list_and_office_count() {
        Lang.sw = false
        val m = model(); show(m)
        rule.onNodeWithTag("projects").performScrollToNode(hasTestTag("chips")); rule.onNodeWithText("Due a visit").performScrollTo().performClick(); rule.waitForIdle()
        // Kapsaret (visited 40 days ago, never tested) and Moiben (never visited, not finished so no test)
        assertTrue("count line", has("2 due a visit · 1 needs a water test"))
        rule.onNodeWithTag("projects").performScrollToNode(hasText("Kapsaret Borehole")); assertTrue(has("Kapsaret Borehole"))
        shot("96-due-a-visit-water-test")
        rule.onNodeWithTag("nav_office").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("o_tests").performScrollTo(); assertTrue(has("water test due")); shot("97-office-water-tests")
        rule.onNodeWithTag("o_tests").performClick(); rule.waitForIdle()
        assertEquals("due", m.prefs.filter)
    }

    @Test fun dark_sun_and_kiswahili() {
        Look.mode = "dark"; Lang.sw = false
        val m = model(); show(m, dark = true)
        open("Kapsaret Borehole"); timeline(); shot("98-photo-timeline-dark")
        Look.mode = "sun"; rule.waitForIdle(); shot("99-photo-timeline-sun")
        Look.mode = ""; Lang.sw = true; rule.waitForIdle()
        assertTrue(has("Picha kwa wakati") && has("Pampu au tangi, kwa karibu") && has("Shiriki muhtasari wa mradi") && has("Kipimo cha maji kinahitajika"))
        rule.onNodeWithTag("photo_timeline").performScrollTo(); shot("99b-photo-timeline-sw")
    }
}

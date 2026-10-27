package org.rafikioutreach.pin

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import org.rafikioutreach.pin.data.Snapshot
import org.rafikioutreach.pin.ui.DonorBuild
import org.rafikioutreach.pin.ui.DonorBuildCard
import org.rafikioutreach.pin.ui.Motion
import org.rafikioutreach.pin.ui.Onb
import org.rafikioutreach.pin.ui.PhotoPick
import org.rafikioutreach.pin.ui.RafikiTheme
import org.rafikioutreach.pin.ui.Stages
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate

/** Construction projects with stages (feat-mea, me-brief §8; S/me-contract.md). A fake server. Screenshots "build-…". */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class ConstructionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    @After fun reset() { Lang.sw = false; Motion.on = true; Onb.enabled = true; PhotoPick.fakeFile = null; stage = "walls" }

    private fun ago(d: Long) = Instant.now().minusSeconds(d * 86400).toString()
    private fun proj(id: String, t: String, status: String = "Completed", type: String = "Borehole well") = JSONObject().put("id", id).put("title", t).put("country", "Kenya").put("ck", "ke").put("area", "Eldoret")
        .put("type", type).put("status", status).put("pump", "manual").put("gps", JSONArray().put(0.40 + id.toInt() % 7 * 0.01).put(35.10 + id.toInt() % 5 * 0.01))
    private var stage = "walls"
    private fun brief() = JSONObject().put("s", stage).put("at", ago(3)).put("by", "Meril Kiprop").put("pct", 40).put("due", LocalDate.now().minusDays(5).toString()).put("parent", "205").put("t", "Dormitory construction").put("n", 3).put("behind", true)
    private fun snapshot(admin: Boolean) = JSONObject().put("ok", true).put("who", "Meril Kiprop").put("admin", admin).put("reviewOpen", 0)
        .put("projects", JSONArray().put(proj("205", "Ndalat Jobs Outreach", type = "Educational")).put(proj("301", "Dormitory construction", "Under Construction", "Educational")).put(proj("101", "Site 101 Borehole")))
        .put("stages", JSONObject().put("301", brief()))
        .put("repairs", JSONObject()).put("visits", JSONObject()).put("jobs", JSONArray()).put("assigned", JSONArray()).put("confirmed", JSONObject())
    private fun record() = JSONObject().put("id", "301").put("stage", stage).put("at", ago(3)).put("by", "Meril Kiprop").put("pct", 40).put("due", LocalDate.now().minusDays(5).toString()).put("parent", "205").put("ptitle", "Ndalat Jobs Outreach").put("behind", true)
        .put("hist", JSONArray()
            .put(JSONObject().put("stage", "planned").put("at", ago(60)).put("by", "Reid Anderson").put("note", "Plans approved").put("photos", JSONArray().put("a1")))
            .put(JSONObject().put("stage", "foundation").put("at", ago(30)).put("by", "Meril Kiprop").put("note", "").put("photos", JSONArray().put("a2").put("a3")))
            .apply { if (stage != "foundation") put(JSONObject().put("stage", "walls").put("at", Instant.now().minusSeconds(60).toString()).put("by", "Meril Kiprop").put("note", "Up to the windows").put("photos", JSONArray().put("a4"))) })

    private val posts = mutableListOf<JSONObject>()
    private val uploads = mutableListOf<String>()
    private fun pic(c: Int): ByteArray { val b = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888); Canvas(b).apply { drawColor(c); drawCircle(160f, 120f, 60f, Paint().apply { color = -1 }) }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray() }

    private fun model(admin: Boolean): AppModel {
        Onb.enabled = false; Motion.on = false
        Prefs(app).apply { pin = "9999"; name = "Meril Kiprop"; saveSnapshot(snapshot(admin)); filter = "all"; country = ""; ftype = ""; onb = true; lang = if (Lang.sw) "sw" else "en"; phoneAlerts = false; meHint = true }
        org.rafikioutreach.pin.ui.clearDrafts(); org.rafikioutreach.pin.ui.NewSiteDrafts.remove(app)
        val m = AppModel(app)
        m.api.transport = { method, u, h, body ->
            val path = u.removePrefix(m.api.server)
            when {
                path.startsWith("/api/pin-photo") -> { uploads += String(body ?: ByteArray(0), Charsets.ISO_8859_1); 200 to "{\"ok\":true,\"asset\":\"a9\"}" }
                path == "/api/pin" && method == "POST" -> {
                    val j = JSONObject(String(body!!)); posts += j
                    when (j.optString("action")) {
                        "stage" -> { if (!admin && j.optString("stage") == "handed") 403 to "{\"ok\":false,\"reason\":\"admin-only\"}" else { stage = j.optString("stage"); 200 to JSONObject().put("ok", true).put("id", "301").put("updateId", "u77").put("stage", record()).toString() } }
                        "stage-meta" -> 200 to JSONObject().put("ok", true).put("stage", record().put("pct", j.opt("pct")).put("due", j.opt("due"))).toString()
                        "new" -> 200 to "{\"ok\":true,\"id\":\"302\",\"updateId\":\"u1\"}"
                        else -> 200 to "{\"ok\":true}"
                    }
                }
                u.contains("?id=301") -> 200 to JSONObject().put("ok", true).put("last", JSONObject.NULL).put("stage", record())
                    .put("linked", JSONObject().put("parent", JSONObject().put("id", "205").put("t", "Ndalat Jobs Outreach").put("s", "Completed")).put("children", JSONArray())).toString()
                u.contains("?id=205") -> 200 to JSONObject().put("ok", true).put("last", JSONObject.NULL).put("stage", JSONObject.NULL)
                    .put("linked", JSONObject().put("parent", JSONObject.NULL).put("children", JSONArray().put(JSONObject().put("id", "301").put("t", "Dormitory construction").put("s", stage)))).toString()
                u.contains("?id=") -> 200 to "{\"ok\":true,\"last\":null}"
                u.contains("activity=1") -> 200 to JSONObject().put("ok", true).put("activity", JSONArray()
                    .put(JSONObject().put("a", "stage").put("s", "walls").put("id", "301").put("t", "Dormitory construction").put("by", "Meril Kiprop").put("at", Instant.now().minusSeconds(60).toString()))).toString()
                u.contains("team=1") || u.contains("view=") -> 200 to "{\"ok\":true,\"team\":[],\"items\":[]}"
                u.contains("pin-auth/me") -> 200 to "{\"ok\":true,\"available\":false}"
                u.contains("version.json") || u.contains("/api/chat") -> 0 to ""
                method == "GET" -> 200 to snapshot(admin).put("stages", JSONObject().put("301", brief())).toString()
                else -> 200 to "{\"ok\":true}"
            }
        }
        m.api.fetch = { u, h -> if (h["X-Pin"] != "9999") 401 to null else if (u.contains("asset=a1")) 200 to pic(0xFF7A4FD0.toInt()) else if (u.contains("asset=a4")) 200 to pic(0xFFD98200.toInt()) else 200 to pic(0xFF1C9C5A.toInt()) }
        return m
    }
    private fun start(m: AppModel, dark: Boolean = false) {
        rule.setContent { RafikiTheme(dark = dark) { Box(Modifier.size(360.dp, 780.dp)) { App(m) } } }
        rule.waitUntil(10000) { tagged("nav_list") }; settle(1500)
    }
    private fun tagged(t: String) = rule.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitTag(t: String) = rule.waitUntil(10000) { tagged(t) }
    private fun settle(ms: Long = 800) { rule.mainClock.advanceTimeBy(ms); rule.waitForIdle() }
    private fun tap(t: String) { rule.onNodeWithTag(t).let { n -> runCatching { n.performScrollTo() }; n.performClick() }; settle() }
    private fun shot(name: String) {
        settle()
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); val c = Canvas(b); v.draw(c)
        val d = ShadowDialog.getShownDialogs().lastOrNull { it.isShowing }?.window?.decorView
        if (d != null && d.width > 0) { c.drawColor(0x66000000); c.save(); c.translate(0f, (v.height - d.height).toFloat().coerceAtLeast(0f)); d.draw(c); c.restore() }
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun openProject(title: String) {
        tap("nav_list"); rule.onNodeWithTag("projects").performScrollToNode(hasText(title)); rule.onAllNodesWithText(title).onLast().performClick(); settle(1200)
    }

    @Test fun stages_parse_and_rules() {
        val s = Snapshot.from(snapshot(false))
        assertEquals("walls", s.stages["301"]!!.s); assertTrue(s.stages["301"]!!.behind)
        assertTrue(s.building(s.projects.first { it.id == "301" })); assertFalse(s.building(s.projects.first { it.id == "101" }))
        assertEquals("roof", Stages.next("walls")); assertEquals("planned", Stages.next(null)); assertEquals(null, Stages.next("handed"))
        Lang.sw = true; assertEquals("Kuta", Stages.label("walls")); assertEquals("Imekabidhiwa", Stages.label("handed")); Lang.sw = false
        val r = org.rafikioutreach.pin.ui.StageRecord.from(record())!!
        assertEquals(listOf("a2", "a3"), r.photos("foundation")); assertEquals("Reid Anderson", r.reached("planned")!!.by)
    }

    private fun card(sw: Boolean, dark: Boolean) {
        Lang.sw = sw
        val m = model(admin = false)
        start(m, dark)
        openProject("Dormitory construction"); waitTag("build_card")
        rule.waitUntil(8000) { tagged("build_photo_foundation_1") }
        rule.onNodeWithTag("build_card").performScrollTo(); settle(1200)
        assertTrue(tagged("build_behind")); assertTrue(tagged("build_parent"))
        rule.onNodeWithTag("build_parent").assertTextContains(if (sw) "Sehemu ya: Ndalat Jobs Outreach" else "Part of: Ndalat Jobs Outreach", substring = true)
        rule.onNodeWithTag("build_next").assertTextContains(if (sw) "Hatua inayofuata: Paa" else "Move to next stage: Roof", substring = true)
        rule.onNodeWithTag("build_stepper").performScrollTo(); shot("build-card-${if (sw) "sw" else "en"}-${if (dark) "dark" else "light"}")
    }
    @Test fun card_en_light() = card(false, false)
    @Test fun card_sw_dark() = card(true, true)

    @Test fun move_to_next_stage_needs_a_photo_and_uploads_it() {
        val m = model(admin = false)
        val f = File(app.cacheDir, "w.jpg").apply { writeBytes(pic(0xFF0A78C2.toInt())) }
        PhotoPick.fakeFile = { File(app.cacheDir, "w${System.nanoTime()}.jpg").also { f.copyTo(it) } }
        start(m)
        openProject("Dormitory construction"); waitTag("build_next")
        // field users: no Handed over
        tap("build_next"); waitTag("build_sheet_title")
        assertFalse("a field user can't hand over", tagged("build_pick_handed"))
        tap("build_save"); assertTrue("a photo is required", tagged("build_sheet_msg")); assertTrue(posts.none { it.optString("action") == "stage" })
        rule.onAllNodesWithText("Add a photo").onLast().performScrollTo().performClick(); settle()
        rule.onNodeWithText("Choose from gallery").performClick(); settle()
        rule.onNodeWithTag("build_note").onChildren().filter(hasSetTextAction()).onFirst().performTextInput("Ring beam done")
        shot("build-sheet-en")
        tap("build_save"); rule.waitUntil(8000) { !tagged("build_sheet_title") }; settle(1200)
        val p = posts.last { it.optString("action") == "stage" }
        assertEquals("roof", p.optString("stage")); assertEquals("301", p.optString("id")); assertEquals("Ring beam done", p.optString("note"))
        val up = uploads.last()
        assertTrue(up.contains("name=\"update\"\r\n\r\nu77")); assertTrue(up.contains("Construction: Roof")); assertTrue(up.contains("name=\"stage\"\r\n\r\nroof")); assertTrue(up.contains("name=\"project\"\r\n\r\n301"))
        waitTag("build_msg"); rule.onNodeWithText("Now at Roof", substring = true).assertExists()
    }

    @Test fun admin_hands_over_with_a_confirm_and_sees_the_gallery() {
        Lang.sw = false; stage = "finishing"
        val m = model(admin = true)
        PhotoPick.fakeFile = { File(app.cacheDir, "h${System.nanoTime()}.jpg").also { it.writeBytes(pic(0xFF1C9C5A.toInt())) } }
        start(m)
        openProject("Dormitory construction"); waitTag("build_next")
        rule.onNodeWithTag("build_next").assertTextContains("Hand over", substring = true)
        // the gallery: tap a photo, swipe to the next
        rule.waitUntil(8000) { tagged("build_photo_foundation_0") }
        rule.onNodeWithTag("build_photos_foundation").performScrollTo(); settle()
        rule.onNodeWithTag("build_photo_foundation_0").performClick(); settle(600)
        waitTag("build_viewer"); settle(600)
        rule.onNodeWithTag("build_viewer_caption", useUnmergedTree = true).assertTextContains("1/2", substring = true)
        shot("build-viewer")
        rule.onNodeWithTag("build_viewer").performTouchInput { swipeLeft() }; settle(800)
        rule.onNodeWithTag("build_viewer_caption", useUnmergedTree = true).assertTextContains("2/2", substring = true)
        tap("build_viewer_x")
        tap("build_next"); waitTag("build_sheet_title")
        rule.onAllNodesWithText("Add a photo").onLast().performScrollTo().performClick(); settle(); rule.onNodeWithText("Choose from gallery").performClick(); settle()
        tap("build_save"); assertTrue("asks first", tagged("build_hand_ask")); assertTrue(posts.none { it.optString("stage") == "handed" })
        shot("build-handover-ask")
        tap("build_save"); rule.waitUntil(8000) { posts.any { it.optString("stage") == "handed" } }
    }

    @Test fun new_site_being_built_and_part_of() {
        Lang.sw = true
        val m = model(admin = false)
        start(m)
        tap("nav_new"); waitTag("site_build_building")
        rule.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextInput("Bweni la wasichana")
        tap("site_build_building"); tap("site_partof"); waitTag("partof_sheet")
        rule.onNodeWithTag("partof_q").onChildren().filter(hasSetTextAction()).onFirst().performTextInput("Ndalat"); settle()
        tap("partof_205"); settle()
        rule.onNodeWithTag("site_partof").assertTextContains("Ndalat Jobs Outreach", substring = true)
        rule.onNodeWithTag("site_build_building").performScrollTo(); shot("build-new-site-sw")
        // the draft keeps them
        val d = org.rafikioutreach.pin.ui.NewSiteDrafts.load(app)
        rule.waitUntil(5000) { org.rafikioutreach.pin.ui.NewSiteDrafts.load(app)?.optString("build") == "building" }
        assertEquals("205", org.rafikioutreach.pin.ui.NewSiteDrafts.load(app)!!.optString("parent")); d?.length()
    }

    @Test fun list_filter_office_tile_and_linked_parent() {
        val m = model(admin = true)
        start(m)
        tap("nav_list"); waitTag("chip_build"); tap("chip_build")
        rule.onNodeWithTag("proj_count").assertTextContains("1 being built", substring = true)
        rule.onNodeWithText("Dormitory construction").assertExists(); rule.onAllNodesWithText("Site 101 Borehole").assertCountEquals(0)
        shot("build-list-filter-en")
        tap("nav_office"); waitTag("ocv_t_build")
        rule.onNodeWithTag("ocv_t_build").performScrollTo().assertTextContains("1 behind schedule", substring = true)
        shot("build-office-tile-en")
        // the school shows its build: "Also here: Dormitory construction · Walls", and a tap opens it
        tap("nav_list"); tap("chip_all"); openProject("Ndalat Jobs Outreach"); waitTag("build_child_301")
        assertFalse("not a build itself", tagged("build_card"))
        rule.onNodeWithTag("build_child_301").performScrollTo().assertTextContains("Also here: Dormitory construction · Walls", substring = true)
        shot("build-parent-also-here-en")
        tap("build_child_301"); waitTag("build_card")
    }

    /** The monthly report (screen and PDF): the month's stage changes with their photos (feat-mea). */
    private fun report(sw: Boolean, dark: Boolean) {
        Lang.sw = sw
        val m = model(admin = false)
        rule.setContent { RafikiTheme(dark = dark) { Box(Modifier.size(360.dp, 780.dp)) { org.rafikioutreach.pin.ui.ReportScreen(m) {} } } }
        rule.waitUntil(10000) { tagged("rep_build") }
        rule.waitUntil(10000) { tagged("rep_build_photos") }
        rule.onNodeWithText(if (sw) "Kuta" else "Walls").assertExists(); rule.onNodeWithText("Dormitory construction").assertExists()
        rule.onNodeWithTag("rep_build").performScrollTo(); shot("build-report-${if (sw) "sw-dark" else "en-light"}")
        // the PDF has the section too (it draws without failing, with the photos)
        val act = m.activity.value.orEmpty()
        val r = org.rafikioutreach.pin.ui.buildReport(m.snap.value!!, act, java.time.YearMonth.now(), "", "Meril Kiprop")
        assertEquals(1, r.stages.size); assertEquals(if (sw) "Kuta" else "Walls", r.stages[0].stage)
        // the PDF's own drawing code onto pages (Robolectric has no PDF back end): the section and its photos are on a page
        val pages = mutableListOf<Bitmap>()
        val photo = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFD98200.toInt()) }
        org.rafikioutreach.pin.ui.drawReportPages(app, r.copy(buildPhotos = listOf(photo)), "ke", { Bitmap.createBitmap(1190, 1684, Bitmap.Config.ARGB_8888).also { pages += it }.let { Canvas(it).apply { drawColor(-1); scale(2f, 2f) } } }, {})
        assertTrue(pages.isNotEmpty())
        assertTrue("the photo is drawn", pages.any { b -> (0 until b.height step 6).any { y -> (0 until b.width step 6).any { x -> b.getPixel(x, y) == 0xFFD98200.toInt() } } })
        File(out, "build-report-pdf-p${pages.size}-${if (sw) "sw" else "en"}.png").outputStream().use { pages.last().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun report_construction_en_light() = report(false, false)
    @Test fun report_construction_sw_dark() = report(true, true)

    @Test fun donor_sees_a_read_only_tracker() {
        for ((sw, dark) in listOf(false to false, true to true)) {
            Lang.sw = sw
            val b = DonorBuild.from(JSONObject().put("stage", "walls").put("at", ago(3)).put("pct", 40).put("due", LocalDate.now().plusMonths(2).toString()).put("behind", false)
                .put("stages", JSONArray().put(JSONObject().put("stage", "planned").put("at", ago(60)).put("photos", JSONArray().put("d1")))
                    .put(JSONObject().put("stage", "foundation").put("at", ago(30)).put("photos", JSONArray().put("d2").put("d3"))).put(JSONObject().put("stage", "walls").put("at", ago(3)).put("photos", JSONArray()))))!!
            if (!sw) rule.setContent { RafikiTheme(dark = darkNow) { Column(Modifier.size(360.dp, 780.dp).verticalScroll(rememberScrollState()).padding(16.dp)) { DonorBuildCard(b, { id, w -> "https://x.test/$id?w=$w" }) { _, _ -> } } } }
            darkNow = dark; settle()
            rule.onNodeWithTag("dbuild_now", useUnmergedTree = true).assertTextEquals(if (sw) "Kuta" else "Walls")
            assertFalse("no buttons for donors", tagged("build_next")); assertFalse(tagged("build_meta"))
            shot("build-donor-${if (sw) "sw-dark" else "en-light"}")
        }
    }
    private var darkNow by androidx.compose.runtime.mutableStateOf(false)
}

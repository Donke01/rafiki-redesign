package org.rafikioutreach.pin

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.rafikioutreach.pin.data.Fix
import org.rafikioutreach.pin.data.LatLng
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.ui.Look
import org.rafikioutreach.pin.ui.MapSetup
import org.rafikioutreach.pin.ui.OfflineMaps
import org.rafikioutreach.pin.ui.RafikiTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

/** Projects home and list (polish round): the quick actions never split a word, the Office-style head, one row of filters
 *  with counts, country and type under Filter, both the distance and the repair state on a row; Today's route's Undo;
 *  Offline maps' single title and storage; the close X on the map style and offline sheets. Screenshots in build/shots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class ProjectsPolishTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ago = { days: Long -> Instant.now().minusSeconds(days * 86400).toString() }

    @Before fun fresh() { MapSetup.init(app); OfflineMaps.setOnline(true) }
    @After fun reset() { org.rafikioutreach.pin.ui.Motion.on = true; Lang.sw = false; Look.mode = ""; OfflineMaps.setOnline(true); org.rafikioutreach.pin.ui.HomeLayers.reset() }

    private fun proj(id: String, t: String, area: String, lat: Double?, lng: Double?, type: String = "Borehole well", ck: String = "ke", country: String = "Kenya") =
        JSONObject().put("id", id).put("title", t).put("country", country).put("ck", ck).put("area", area).put("type", type).put("status", "Completed").put("pump", "")
            .apply { if (lat != null) put("gps", JSONArray().put(lat).put(lng)) }
    private fun visit(days: Long, by: String = "Meril", state: String = "working") = JSONObject().put("at", ago(days)).put("by", by).put("state", state)
    private fun snapshot() = JSONObject().put("ok", true).put("who", "Meril Kiprop").put("admin", false).put("reviewOpen", 0)
        .put("projects", JSONArray()
            .put(proj("101", "Kapsaret Borehole", "Eldoret", 0.47, 35.25))
            .put(proj("102", "Chepkongi Dispensary and Maternity Wing, Kapsaret Ward", "Eldoret", 0.5143, 35.2698, "Health"))
            .put(proj("103", "Turbo Spring", "Turbo", 0.63, 35.05, "Water collection"))
            .put(proj("104", "Nzoia Bridge Primary School", "Bungoma", 0.58, 34.62, "School"))
            .put(proj("105", "Tamiha Children’s Home", "Kitale", 1.0, 35.0, "Orphanage"))
            .put(proj("106", "Kaptebee Pigsty", "Nandi", 0.2, 35.1, "Farming"))
            .put(proj("108", "Mto wa Mbu Borehole", "Arusha", -3.37, 35.85, ck = "tz", country = "Tanzania"))
            .put(proj("110", "Sosiot Well", "Kericho", null, null)))
        .put("repairs", JSONObject()
            .put("101", JSONObject().put("state", "notworking").put("by", "Bertin").put("at", ago(3)).put("urgency", "urgent").put("t", "Kapsaret Borehole"))
            .put("102", JSONObject().put("state", "repair").put("by", "Grace").put("at", ago(9)).put("urgency", "month").put("t", "Chepkongi Dispensary")))
        .put("visits", JSONObject().put("101", visit(3, "Bertin", "notworking")).put("103", visit(20)).put("104", visit(400)).put("106", visit(10, "Grace")))
        .put("jobs", JSONArray()).put("assigned", JSONArray())
        .put("confirmed", JSONObject()).put("me", JSONObject().put("kind", "app").put("home", "ke"))

    private fun model(fix: Boolean = true): AppModel {
        org.rafikioutreach.pin.ui.Onb.enabled = false
        org.rafikioutreach.pin.ui.Motion.on = false
        Prefs(app).apply { pin = "1111"; name = "Meril Kiprop"; saveSnapshot(snapshot()); filter = "all"; country = ""; lang = if (Lang.sw) "sw" else "en"; ftype = ""; route = "" }
        val m = AppModel(app)
        m.api.transport = { method, url, _, _ ->
            when {
                url.contains("pin-auth/me") -> 200 to "{\"ok\":true,\"available\":false}"
                url.contains("activity=1") -> 200 to "{\"ok\":true,\"activity\":[]}"
                url.contains("?id=") -> 200 to "{\"ok\":true,\"last\":null,\"canUndo\":false,\"visits\":[]}"
                method == "GET" -> 200 to snapshot().toString()
                else -> 200 to "{\"ok\":true}"
            }
        }
        if (fix) m.locator.pretend(Fix(LatLng(0.5150, 35.2700), 8, System.currentTimeMillis()))
        return m
    }
    private fun show(m: AppModel, dark: Boolean = false, w: Int = 390, h: Int = 844) {
        rule.setContent { RafikiTheme(dark = dark) { Box(Modifier.size(w.dp, h.dp)) { App(m) } } }
        rule.waitUntil(8000) { tagged("quick_missing") }
        rule.waitForIdle()
    }
    private fun bitmap(): Bitmap {
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        return Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
    }
    private fun shot(name: String) = File(out, "$name.png").outputStream().use { bitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    /** With the windows over the page drawn in: a sheet (dimmed behind) or the Undo bar. */
    private fun shotAll(name: String, dim: Boolean = false) {
        rule.mainClock.advanceTimeBy(700); rule.waitForIdle()
        val b = bitmap(); val c = Canvas(b); if (dim) c.drawColor(0x66000000)
        val main = rule.activity.window.decorView
        @Suppress("UNCHECKED_CAST")
        val views = runCatching {
            val g = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
            (g.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(g) as List<android.view.View>).toList()
        }.getOrDefault(emptyList())
        views.filter { it !== main && it.width > 0 && it.isShown }.forEach { v ->
            val at = IntArray(2); v.getLocationOnScreen(at)
            val lp = v.layoutParams as? android.view.WindowManager.LayoutParams
            val y = if (at[1] == 0 && v.height < b.height && lp?.gravity?.and(android.view.Gravity.BOTTOM) == android.view.Gravity.BOTTOM) b.height - v.height else at[1]
            c.save(); c.translate(at[0].toFloat(), y.toFloat()); v.draw(c); c.restore()
        }
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun has(t: String) = rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun tagged(t: String) = rule.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Every text on screen whose lines break inside a word, or leave one character alone on a line. */
    private fun midWordBreaks(): List<String> {
        val nodes = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes()
        return nodes.flatMap { n: SemanticsNode ->
            val r = mutableListOf<TextLayoutResult>()
            n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(r)
            val l = r.firstOrNull() ?: return@flatMap emptyList()
            val s = l.layoutInput.text.text
            (0 until l.lineCount).mapNotNull { i ->
                val end = l.getLineEnd(i)
                val line = s.substring(l.getLineStart(i), minOf(end, s.length)).trim()
                when {
                    i < l.lineCount - 1 && end in 1 until s.length && !s[end - 1].isWhitespace() && !s[end].isWhitespace() && s[end - 1] !in "-/–" -> "“$s” breaks inside a word after “${s.substring(0, end)}”"
                    l.lineCount > 1 && line.length == 1 -> "“$s” leaves “$line” alone on a line"
                    l.isLineEllipsized(i) -> "“$s” is cut short with …"
                    else -> null
                }
            }
        }
    }

    private fun homeChecks(name: String, sw: Boolean, dark: Boolean, w: Int, h: Int) {
        Lang.sw = sw
        val m = model(); show(m, dark, w, h)
        assertEquals("no word split, at $w dp", emptyList<String>(), midWordBreaks())
        if (sw) assertTrue(has("Rekebisha") && has("Soma QR") && has("Eneo jipya") && !has("Ukarabati"))
        shot(name)
    }

    @Test fun quick_actions_never_split_a_word_sw_360() = homeChecks("pp-01-home-sw-360", true, false, 360, 740)
    @Config(qualifiers = "w360dp-h740dp-xxhdpi")
    @Test fun quick_actions_never_split_a_word_sw_360_dark() = homeChecks("pp-02-home-sw-360-dark", true, true, 360, 740)
    @Test fun quick_actions_never_split_a_word_sw_390() = homeChecks("pp-03-home-sw-390", true, false, 390, 844)
    @Test fun quick_actions_never_split_a_word_en_360() = homeChecks("pp-04-home-en-360", false, false, 360, 740)

    @Test fun projects_list_head_chips_and_filter() {
        Lang.sw = false
        val m = model(); show(m)
        rule.onNodeWithTag("projects").performScrollToNode(hasTestTag("projects_head")); rule.waitForIdle()
        // the head: three numbers that set the filter
        assertTrue(tagged("n_missing") && tagged("n_repairs") && tagged("n_due") && has("7 of 8 projects pinned · 1 to go"))
        rule.onNodeWithTag("n_repairs").performClick(); rule.waitForIdle()
        assertEquals("repairs", m.prefs.filter)
        // one row of chips with counts; Filter holds country and type
        assertTrue(tagged("chips") && tagged("proj_filter") && tagged("chip_all") && tagged("chip_repairs") && !tagged("type_chips") && !tagged("chip_country"))
        assertTrue(rule.onAllNodesWithTag("chip_repairs").fetchSemanticsNodes().first().config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }?.contains("2") ?: has("2"))
        // both: the distance first, then the repair state
        rule.onNodeWithTag("projects").performScrollToNode(hasTestTag("proj_count")); rule.waitForIdle()
        assertTrue(has("5.5 km") && has("Not working") && has("Needs repair") && has("81 m"))
        shot("pp-10-list-repairs")
        rule.onNodeWithTag("chip_all").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("proj_filter").performClick(); rule.waitForIdle()
        rule.waitUntil(4000) { tagged("proj_filter_sheet") }
        assertTrue(tagged("pick_ke") && tagged("pick_tz") && tagged("type_water") && tagged("proj_filter_x"))
        shotAll("pp-11-filter-sheet", dim = true)
        rule.onNodeWithTag("pick_ke").performClick(); rule.onNodeWithTag("type_water").performClick(); rule.waitForIdle()
        assertEquals("ke", m.prefs.country); assertEquals("water", m.prefs.ftype)
        rule.onNodeWithTag("proj_filter_done").performClick(); rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        assertFalse(tagged("proj_filter_sheet"))
        assertTrue("the pill says what is chosen", has("Kenya · Water"))
        shot("pp-12-list-filtered")
        assertEquals(emptyList<String>(), midWordBreaks())
    }

    /** "Pinned" says nothing when every row has it (no location: no distance either). */
    @Test fun pinned_only_when_it_tells_something() {
        Lang.sw = true
        val m = model(fix = false); show(m, dark = true)
        rule.onNodeWithTag("projects").performScrollToNode(hasTestTag("chips")); rule.onNodeWithTag("chip_near").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("projects").performScrollToNode(hasTestTag("proj_count")); rule.waitForIdle()
        assertFalse("every row is pinned: no “Ina mahali”", has("Ina mahali"))
        shot("pp-13-list-near-sw-dark")
        rule.onNodeWithTag("chip_all").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("projects").performScrollToNode(androidx.compose.ui.test.hasText("Turbo Spring")); rule.waitForIdle()
        assertTrue("with a row that has no location, the others say they are pinned", has("Ina mahali"))
        rule.onNodeWithTag("projects").performScrollToNode(androidx.compose.ui.test.hasText("Sosiot Well")); rule.waitForIdle(); assertTrue(has("Hakuna mahali"))
    }

    /** Today's route: Clear route has an Undo. */
    @Test fun clear_route_can_be_undone() {
        Lang.sw = false
        val m = model(); show(m)
        rule.onNodeWithTag("projects").performScrollToNode(hasTestTag("plan_route")); rule.onNodeWithTag("plan_route").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("projects").assertDoesNotExist()
        rule.onAllNodesWithText("Nzoia Bridge Primary School")[0].performScrollTo().performClick(); rule.onAllNodesWithText("Tamiha Children’s Home")[0].performScrollTo().performClick(); rule.waitForIdle()
        rule.onAllNodesWithText("Plan route (2)")[0].performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("route_clear").performScrollTo().performClick(); rule.waitForIdle()
        assertEquals("", m.prefs.route)
        assertTrue(tagged("route_undo") && has("Route cleared: 2 stops"))
        shotAll("pp-20-route-cleared-undo")
        rule.onNodeWithTag("route_undo_undo").performClick(); rule.waitForIdle()
        assertEquals(2, m.prefs.route.split(",").size)
        assertTrue("the route is back", tagged("route_clear") && !tagged("route_undo"))
        // and it goes by itself
        rule.onNodeWithTag("route_clear").performScrollTo().performClick(); rule.waitForIdle()
        rule.mainClock.advanceTimeBy(6500); rule.waitForIdle()
        assertFalse(tagged("route_undo"))
    }

    /** Offline maps: one title (the bar takes it over only once scrolled); storage in GB from 1 GB. */
    @Test fun offline_maps_one_title_and_storage() {
        Lang.sw = true
        assertEquals("chini ya 1 MB", org.rafikioutreach.pin.ui.mbText(1000))
        assertEquals("250 MB", org.rafikioutreach.pin.ui.mbText(250L * 1048576))
        assertEquals("8.0 GB", org.rafikioutreach.pin.cmap.CountryMaps.mb(8192L * 1048576))
        assertEquals("4.2 MB", org.rafikioutreach.pin.cmap.CountryMaps.mb((4.2 * 1048576).toLong()))
        val m = model(); show(m)
        rule.onNodeWithTag("nav_map").performClick(); rule.waitForIdle()
        rule.waitUntil(5000) { tagged("ml_more") }
        rule.onNodeWithTag("ml_more").performClick(); rule.waitForIdle(); rule.onNodeWithTag("offline_btn").performClick(); rule.waitForIdle()
        rule.waitUntil(4000) { tagged("offline_sheet") }
        assertTrue("the offline sheet has a close X", tagged("oa_x"))
        shotAll("pp-30-offline-sheet-x-sw", dim = true)
        rule.onNodeWithTag("oa_x").performClick(); rule.mainClock.advanceTimeBy(900); rule.waitForIdle()
        assertFalse(tagged("offline_sheet"))
        // the map style sheet too
        rule.onNodeWithTag("ml_layers").performClick(); rule.mainClock.advanceTimeBy(700); rule.waitForIdle()
        assertTrue(tagged("style_x"))
        shotAll("pp-31-map-style-x-sw", dim = true)
        rule.onNodeWithTag("style_x").performClick(); rule.mainClock.advanceTimeBy(900); rule.waitForIdle()
        assertFalse(tagged("sheet"))
        OfflineMaps.setOnline(false); rule.waitForIdle()
        rule.onNodeWithTag("map_nosignal").performClick(); rule.waitForIdle()
        rule.waitUntil(4000) { tagged("offline_screen") }
        assertFalse("one title: the bar shows the logo until the heading scrolls away", tagged("bar_title"))
        assertTrue(rule.onAllNodesWithText("Ramani bila mtandao").fetchSemanticsNodes().size == 1)
        shot("pp-32-offline-maps-sw")
    }
}

package org.rafikioutreach.pin

import android.app.Application
import android.appwidget.AppWidgetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.rafikioutreach.pin.data.BadgeMemory
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.ui.AppBackground
import org.rafikioutreach.pin.ui.BadgeStamps
import org.rafikioutreach.pin.ui.Motion
import org.rafikioutreach.pin.ui.Onb
import org.rafikioutreach.pin.ui.RafikiTheme
import org.rafikioutreach.pin.widget.PinWidget
import org.rafikioutreach.pin.widget.PinWidgetSmall
import org.rafikioutreach.pin.widget.WidgetCounts
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Me › Your stamps (badges, progress, a new one celebrating) and the home-screen widget. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class BadgesWidgetTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @After fun reset() { Motion.on = true; Motion.loops = true; Lang.sw = false; Onb.enabled = true }

    private fun proj(id: String, t: String, c: String, ck: String, gps: Boolean = true) = JSONObject().put("id", id).put("title", t).put("country", c).put("ck", ck).put("area", "Eldoret")
        .put("type", "borehole").put("status", "Completed").put("pump", "manual").apply { if (gps) put("gps", JSONArray().put(0.51).put(35.27)) }
    private fun snapshot() = JSONObject().put("ok", true).put("who", "Meril Kiprop").put("admin", false).put("reviewOpen", 0)
        .put("projects", JSONArray().put(proj("101", "Chepkongi Dispensary", "Kenya", "ke")).put(proj("104", "Kapsaret Borehole", "Kenya", "ke")).put(proj("105", "Turbo Spring", "Kenya", "ke"))
            .put(proj("201", "Arusha School Tank", "Tanzania", "tz")).put(proj("301", "Gulu Borehole", "Uganda", "ug")).put(proj("401", "Zomba Well", "Malawi", "mw", gps = false)))
        .put("repairs", JSONObject().put("104", JSONObject().put("state", "notworking").put("by", "Bertin").put("at", Instant.now().toString()).put("urgency", "urgent").put("t", "Kapsaret Borehole")))
        .put("visits", JSONObject().put("105", JSONObject().put("at", Instant.now().minus(3, ChronoUnit.DAYS).toString()).put("by", "Grace").put("state", "working")))
        .put("jobs", JSONArray().put("104"))
        .put("assigned", JSONArray().put(JSONObject().put("aid", "a1").put("id", "201").put("t", "Arusha School Tank").put("date", LocalDate.now().toString()).put("by", "Grace").put("note", "")))
        .put("confirmed", JSONObject())

    /** My month so far: 7 visits over 5 days in a row (Kenya, Tanzania, Uganda), 10 pins, a fix, water tests and stories. */
    private fun feed(): JSONArray {
        val a = JSONArray(); val zone = ZoneId.systemDefault()
        val days = (0 until 5).map { LocalDate.now(zone).minusDays(it.toLong()) }.filter { it.month == LocalDate.now(zone).month }.ifEmpty { listOf(LocalDate.now(zone)) }
        fun at(d: LocalDate, h: Int) = d.atTime(h, 15).atZone(zone).toInstant().toString()
        fun e(a2: String, id: String, d: LocalDate, h: Int, by: String = "Meril Kiprop") = JSONObject().put("by", by).put("a", a2).put("id", id).put("t", "x").put("at", at(d, h))
        listOf("101", "104", "105", "201", "301", "101", "104").forEachIndexed { i, id ->
            a.put(e("visit", id, days[i % days.size], 9 + i).put("s", "working").apply { if (i < 2) put("wq", 1); if (i in 2..4) put("st", 1) })
        }
        repeat(10) { a.put(e("pin", listOf("101", "104", "105", "201")[it % 4], days[it % days.size], 8)) }
        a.put(e("fixed", "104", days[0], 14)); a.put(e("visit", "401", days[0], 15, by = "Grace Achieng"))
        return a
    }

    private fun model(name: String = "Meril Kiprop"): AppModel {
        Prefs(app).apply { pin = "9999"; this.name = name; saveSnapshot(snapshot()); filter = "all"; onb = true; country = ""; lang = "en"; mePane = "me" }
        Lang.sw = false; Onb.enabled = false
        val m = AppModel(app)
        m.api.transport = { method, url, _, _ ->
            when {
                url.contains("activity=1") -> 200 to JSONObject().put("ok", true).put("activity", feed()).toString()
                url.contains("team=1") -> 200 to "{\"ok\":true,\"team\":[]}"
                url.contains("pin-auth/me") -> 200 to "{\"ok\":true,\"available\":false}"
                url.contains("version.json") -> 0 to ""
                method == "GET" -> 200 to snapshot().toString()
                else -> 200 to "{\"ok\":true}"
            }
        }
        return m
    }

    private fun shot(name: String, popups: Boolean = false) {
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); val c = Canvas(b); v.draw(c)
        // pop-ups (the "new stamp" note) are windows of their own: draw them where they sit
        if (popups) runCatching {
            val g = Class.forName("android.view.WindowManagerGlobal"); val inst = g.getMethod("getInstance").invoke(null)
            @Suppress("UNCHECKED_CAST") val views = g.getDeclaredField("mViews").apply { isAccessible = true }.get(inst) as List<View>
            views.filter { it !== v && it.isAttachedToWindow }.forEach { p ->
                val lp = p.layoutParams as? WindowManager.LayoutParams
                c.save(); c.translate((lp?.x ?: 0).toFloat(), (lp?.y ?: 0).toFloat()); p.draw(c); c.restore()
            }
        }
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun badges_on_me_locked_earned_progress() {
        Motion.on = false
        val m = model()
        rule.setContent { RafikiTheme(dark = false) { Box(Modifier.size(390.dp, 844.dp)) { App(m) } } }
        rule.waitUntil(8000) { rule.onAllNodesWithTag("hav").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("hav").performClick()
        rule.waitUntil(8000) { rule.onAllNodesWithTag("stamp_got").fetchSemanticsNodes().size >= 6 }
        rule.onNodeWithTag("stamps").performScrollTo()
        // earned: first drop, first visit, fixer, mapper, on a roll (5 days), story collector
        val got = rule.onAllNodesWithTag("stamp_got").fetchSemanticsNodes().size
        val expectStreak = LocalDate.now().dayOfMonth >= 5
        assertEquals(if (expectStreak) 6 else 5, got)
        rule.onNodeWithTag("badge_month10", useUnmergedTree = true).assertTextEquals("7/10 visits")
        rule.onNodeWithTag("badge_countries4", useUnmergedTree = true).assertTextEquals("3/4 countries")
        rule.onNodeWithTag("badge_water3", useUnmergedTree = true).assertTextEquals("2/3 tests")
        rule.onNodeWithTag("badge_pin100", useUnmergedTree = true).assertTextEquals("10/100 pins")
        rule.onNodeWithTag("badge_new1", useUnmergedTree = true).assertTextEquals("Add a new site")
        rule.onNodeWithTag("badge_story3", useUnmergedTree = true).assertTextEquals("Earned")
        rule.onNodeWithTag("stamps_count").assertTextEquals("$got of 12")
        // the very first look only remembers (no flood of celebrations for work already done)
        rule.onAllNodesWithTag("badge_toast").assertCountEquals(0)
        assertEquals(got, BadgeMemory(app).seen("Meril Kiprop")!!.size)
        shot("b1-me-badges")
        rule.onAllNodesWithTag("stamp_no").onLast().performScrollTo(); shot("b2-me-badges-lower")
        // Kiswahili
        Lang.sw = true; rule.waitForIdle()
        rule.onNodeWithTag("badge_month10", useUnmergedTree = true).assertTextEquals("ziara 7/10")
        rule.onNodeWithTag("stamps").performScrollTo(); shot("b3-me-badges-sw")
        Lang.sw = false
        // the widget's Visit nearby: Projects opens on Nearby
        m.quick("near"); rule.waitForIdle()
        rule.waitUntil(5000) { rule.onAllNodesWithText("Finding where you are…").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("near", m.prefs.filter); assertEquals(null, m.goFilter)
        shot("b8-widget-opens-nearby")
    }

    @Test fun badges_dark() {
        Motion.on = false
        val m = model()
        rule.setContent { RafikiTheme(dark = true) { Box(Modifier.size(390.dp, 844.dp)) { AppBackground(Modifier.matchParentSize()); Column(Modifier.padding(16.dp)) { BadgeStamps(m) } } } }
        rule.waitUntil(8000) { rule.onAllNodesWithTag("stamp_got").fetchSemanticsNodes().size >= 5 }
        shot("b4-badges-dark")
    }

    @Test fun a_new_stamp_celebrates_once() {
        Motion.on = true; Motion.loops = false
        val m = model()
        BadgeMemory(app).save("Meril Kiprop", setOf("pin1", "visit1"))
        runBlocking { m.loadActivity() }
        rule.mainClock.autoAdvance = false
        rule.setContent { RafikiTheme(dark = false) { Box(Modifier.size(390.dp, 844.dp)) { AppBackground(Modifier.matchParentSize()); Column(Modifier.padding(16.dp)) { BadgeStamps(m) } } } }
        rule.mainClock.advanceTimeBy(100); rule.waitForIdle()
        val n = if (LocalDate.now().dayOfMonth >= 5) 4 else 3 // fixer, mapper, story collector (+ on a roll)
        rule.onAllNodesWithTag("stamp_new").assertCountEquals(n)
        rule.onAllNodesWithTag("confetti").assertCountEquals(n)
        rule.onNodeWithTag("badge_toast").assertExists()
        rule.onNodeWithText("$n new stamps earned!").assertExists()
        repeat(4) { rule.mainClock.advanceTimeBy(80); rule.waitForIdle() }
        shot("b5-celebrate-start", popups = true)
        repeat(5) { rule.mainClock.advanceTimeBy(80); rule.waitForIdle() }
        shot("b6-celebrate-burst", popups = true)
        rule.mainClock.advanceTimeBy(6500); rule.waitForIdle()
        rule.onAllNodesWithTag("badge_toast").assertCountEquals(0)
        shot("b7-celebrate-after")
        assertTrue(BadgeMemory(app).seen("Meril Kiprop")!!.containsAll(setOf("fixed1", "pin10", "story3")))
    }

    @Test fun less_motion_still_says_so_without_confetti() {
        Motion.on = false
        val m = model()
        BadgeMemory(app).save("Meril Kiprop", setOf("pin1", "visit1", "fixed1", "pin10", "streak5"))
        runBlocking { m.loadActivity() }
        rule.setContent { RafikiTheme(dark = false) { Box(Modifier.size(390.dp, 844.dp)) { Column(Modifier.padding(16.dp)) { BadgeStamps(m) } } } }
        rule.waitUntil(5000) { rule.onAllNodesWithTag("badge_toast").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("New stamp earned!").assertExists()
        rule.onNode(hasTestTag("badge_toast") and hasText("Story\u00A0collector", substring = true)).assertExists()
        rule.onAllNodesWithTag("confetti").assertCountEquals(0)
        rule.onAllNodesWithTag("stamp_new").assertCountEquals(1)
    }

    // --- the home-screen widget ---------------------------------------------------------------------

    private fun render(rv: RemoteViews, wDp: Int, hDp: Int, name: String, dark: Boolean = false, wall: Boolean = true): View {
        val ctx = app; val d = ctx.resources.displayMetrics.density
        val v = rv.apply(ctx, FrameLayout(ctx))
        val w = (wDp * d).toInt(); val h = (hDp * d).toInt(); val pad = if (wall) (20 * d).toInt() else 0
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); v.layout(0, 0, w, h)
        val b = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        // a wallpaper behind it, so the rounded card shows
        if (wall) c.drawRect(0f, 0f, b.width.toFloat(), b.height.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, b.width.toFloat(), b.height.toFloat(), if (dark) intArrayOf(0xFF0B1A2A.toInt(), 0xFF22184A.toInt()) else intArrayOf(0xFF8FD3FF.toInt(), 0xFFC9B8FF.toInt()), null, Shader.TileMode.CLAMP)
        })
        c.translate(pad.toFloat(), pad.toFloat()); v.draw(c)
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return v
    }
    private fun text(v: View, id: Int) = v.findViewById<TextView>(id).text.toString()

    @Test fun widget_medium_and_small() {
        model()
        val d = PinWidget.load(app)
        // due: 101, 104, 201, 301 (105 was visited lately; 401 has no location) · urgent: 104 · for you: a1 + job 104
        assertEquals(WidgetCounts(4, 1, 2), d.counts)
        val med = render(PinWidget.views(app, d, false), 300, 150, "w1-widget-medium")
        assertEquals("4", text(med, R.id.w_n_due)); assertEquals("Visits due", text(med, R.id.w_l_due))
        assertEquals("1", text(med, R.id.w_n_urgent)); assertEquals("Urgent repair", text(med, R.id.w_l_urgent))
        assertEquals("2", text(med, R.id.w_n_yours)); assertEquals("Visit nearby", text(med, R.id.w_go_near_t)); assertEquals("Pin a location", text(med, R.id.w_go_pin_t))
        assertEquals("4 visits due · 1 urgent repair · 2 assigned to you", med.findViewById<View>(R.id.w_tiles).contentDescription)
        val sm = render(PinWidget.views(app, d, true), 180, 72, "w2-widget-small")
        assertEquals("4 due · 1 urgent · 2 yours", text(sm, R.id.w_short)); assertEquals("Nearby", text(sm, R.id.w_go_near_t))
        // the picker preview (Android 11 and older use this picture)
        render(PinWidget.views(app, d, false), 300, 150, "w0-widget-preview", wall = false)
        render(PinWidget.views(app, d, true), 180, 72, "w0-widget-preview-small", wall = false)
        // Kiswahili
        Prefs(app).lang = "sw"
        val sw = render(PinWidget.views(app, PinWidget.load(app), false), 300, 150, "w3-widget-medium-sw")
        assertEquals("Tembelea karibu", text(sw, R.id.w_go_near_t)); assertEquals("Kutembelea", text(sw, R.id.w_l_due)); assertEquals("La haraka", text(sw, R.id.w_l_urgent))
        Prefs(app).lang = "en"
        // signed out: asks to open the app
        Prefs(app).forget()
        val off = render(PinWidget.views(app, PinWidget.load(app), false), 300, 150, "w4-widget-signed-out")
        assertEquals(View.VISIBLE, off.findViewById<View>(R.id.w_msg).visibility); assertEquals("Open RAFIKI Pin to sign in", text(off, R.id.w_msg))
        Lang.sw = false
    }

    @Test @Config(qualifiers = "+night") fun widget_dark() {
        model()
        val d = PinWidget.load(app)
        render(PinWidget.views(app, d, false), 300, 150, "w5-widget-medium-dark", dark = true)
        render(PinWidget.views(app, d, true), 180, 72, "w6-widget-small-dark", dark = true)
    }

    @Test fun widget_buttons_open_the_app_on_those_lists() {
        model()
        listOf(PinWidget.NEAR to "near", PinWidget.PIN to "missing").forEach { (q, want) ->
            val i = shadowOf(PinWidget.open(app, q)).savedIntent
            assertEquals(want, i.getStringExtra("quick")); assertEquals(MainActivity::class.java.name, i.component?.className)
        }
        // on the home screen: placed, then refreshed when the app gets a new list
        val mgr = AppWidgetManager.getInstance(app)
        val id = shadowOf(mgr).createWidget(PinWidgetSmall::class.java, R.layout.widget_small)
        PinWidget.update(app)
        assertTrue(mgr.getAppWidgetIds(android.content.ComponentName(app, PinWidgetSmall::class.java)).contains(id))
        val m = model()
        m.quick("missing"); assertEquals("missing", m.prefs.filter); assertEquals("list", m.goTab); assertEquals("missing", m.goFilter)
        m.quick(""); assertEquals("missing", m.goFilter)
    }
}

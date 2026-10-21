package org.rafikioutreach.pin

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.data.Snapshot
import org.rafikioutreach.pin.ui.RafikiTheme
import org.rafikioutreach.pin.ui.ReportData
import org.rafikioutreach.pin.ui.ReportScreen
import org.rafikioutreach.pin.ui.buildReport
import org.rafikioutreach.pin.ui.drawReportPages
import org.rafikioutreach.pin.ui.letterheadAddress
import org.rafikioutreach.pin.ui.writeReportPdf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/** The monthly report on the RAFIKI letterhead, laid out like the web report: tiles, tables, page numbers, the RAFIKI logo at the foot (no seal). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class LetterheadTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    // a realistic month: projects in all four countries, four open repairs, 46 visits, fixes, pins and new sites
    private val projects = listOf(
        listOf("104", "Kapsaret Borehole", "Kenya", "ke", "Eldoret"), listOf("105", "Turbo Spring", "Kenya", "ke", "Turbo"), listOf("106", "Chepkongi Dispensary Well", "Kenya", "ke", "Kitale"),
        listOf("107", "Moi's Bridge Primary School Rainwater Tank", "Kenya", "ke", "Moi's Bridge"), listOf("108", "Kiminini Borehole", "Kenya", "ke", "Kiminini"),
        listOf("201", "Mwanza Primary Well", "Tanzania", "tz", "Mwanza"), listOf("202", "Geita Spring", "Tanzania", "tz", "Geita"), listOf("203", "Shinyanga Borehole", "Tanzania", "tz", "Shinyanga"),
        listOf("301", "Jinja Borehole", "Uganda", "ug", "Jinja"), listOf("302", "Mbale Spring", "Uganda", "ug", "Mbale"), listOf("401", "Zomba Well", "Malawi", "mw", "Zomba"),
    )
    private fun snapJson(home: String, who: String): JSONObject {
        val ps = JSONArray(); projects.forEachIndexed { i, p -> ps.put(JSONObject().put("id", p[0]).put("title", p[1]).put("country", p[2]).put("ck", p[3]).put("area", p[4]).put("type", "Borehole well").put("status", "Completed").apply { if (i != 4) put("gps", JSONArray().put(0.4 + i * .1).put(35.0 + i * .1)) }) }
        fun rep(state: String, by: String, at: String, urg: String, probs: List<String>, parts: String, cost: Long?) = JSONObject().put("state", state).put("by", by).put("at", at).put("urgency", urg).put("problems", JSONArray(probs)).put("parts", parts).apply { cost?.let { put("cost", it) } }
        val repairs = JSONObject()
            .put("105", rep("repair", "Bertin Ngindu", "2026-10-09T08:00:00Z", "month", listOf("Slow flow", "Leaking pipe joint near the tank stand"), "Pipe joint, PTFE tape", 3200))
            .put("104", rep("notworking", "Meril Kiprop", "2026-10-18T09:30:00Z", "urgent", listOf("Pump handle broken"), "Handle, bolts", 4500))
            .put("202", rep("repair", "Grace Achieng", "2026-10-12T11:00:00Z", "watch", listOf("Apron cracked"), "", null))
            .put("301", rep("notworking", "Joseph Okello", "2026-10-20T07:15:00Z", "urgent", listOf("Cylinder worn", "No water"), "Cylinder, rods", 28000))
        val visits = JSONObject().put("104", JSONObject().put("at", "2026-10-18T09:30:00Z").put("by", "Meril Kiprop").put("state", "notworking"))
        return JSONObject().put("ok", true).put("who", who).put("admin", false).put("me", JSONObject().put("kind", "app").put("home", home))
            .put("projects", ps).put("repairs", repairs).put("visits", visits).put("jobs", JSONArray()).put("assigned", JSONArray()).put("confirmed", JSONObject())
    }
    private fun activity(): List<JSONObject> {
        val ids = listOf("104", "105", "106", "107", "201", "202", "203", "301", "302", "401")
        val by = listOf("Meril Kiprop", "Bertin Ngindu", "Grace Achieng", "Joseph Okello"); val st = listOf("working", "working", "repair", "working", "notworking")
        val a = (0 until 46).map { i ->
            val id = ids[i % ids.size]
            JSONObject().put("a", "visit").put("id", id).put("t", projects.first { it[0] == id }[1]).put("by", by[i % 4]).put("s", st[i % 5]).put("at", "2026-10-%02dT%02d:00:00Z".format(1 + i % 28, 7 + i % 9))
                .apply { if (st[i % 5] != "working") put("u", listOf("urgent", "month", "watch")[i % 3]) }
        }.toMutableList()
        a += JSONObject().put("a", "fixed").put("id", "106").put("by", "Bertin Ngindu").put("at", "2026-10-14T10:00:00Z")
        a += JSONObject().put("a", "fixed").put("id", "302").put("by", "Joseph Okello").put("at", "2026-10-16T10:00:00Z")
        a += JSONObject().put("a", "pin").put("id", "108").put("by", "Meril Kiprop").put("at", "2026-10-05T10:00:00Z")
        a += JSONObject().put("a", "new").put("id", "N1").put("t", "Kipsaina Spring").put("by", "Meril Kiprop").put("at", "2026-10-06T10:00:00Z")
        a += JSONObject().put("a", "visit").put("id", "104").put("by", "Meril Kiprop").put("s", "working").put("at", "2026-09-28T10:00:00Z") // last month: not counted
        return a
    }
    private fun report(ck: String = "", home: String = "ke"): ReportData =
        buildReport(Snapshot.from(snapJson(home, "Meril Kiprop")), activity(), YearMonth.of(2026, 10), ck, "Meril Kiprop", LocalDate.of(2026, 10, 21), ZoneOffset.UTC)

    @Test fun address_by_home_country() {
        assertEquals("Mega Centre, 3rd Floor, Makasembo Road", letterheadAddress("ke")[1])
        assertEquals("Email: info@rafikioutreach.org", letterheadAddress("ke")[4])
        listOf("tz", "ug", "mw", "").forEach { assertEquals(listOf("Rafiki Outreach", "1817 N. Beck Street", "Salt Lake City, UT 84116, U.S.A.", "Phone: +1 801 547 7847", "Email: Info@rafikioutreach.org"), letterheadAddress(it)) }
    }

    /** The same sums as reportHtml in pin.html. */
    @Test fun report_counts_like_the_web_report() {
        Lang.sw = false
        val r = report()
        assertEquals("October 2026", r.label); assertEquals("All countries", r.country); assertEquals("21 October 2026", r.date)
        val k = r.kpis.associate { it.label to it }
        assertEquals("46", k.getValue("site visits").value)
        assertEquals("10", k.getValue("projects visited").value)
        // % working: from each project's latest visit this month
        val latest = activity().filter { it.optString("a") == "visit" && it.optString("at").startsWith("2026-10") }.groupBy { it.optString("id") }.values.map { l -> l.maxBy { it.optString("at") } }
        val pct = Math.round(latest.count { it.optString("s") == "working" } * 100.0 / latest.size)
        assertEquals("$pct%", k.getValue("of visited projects working").value)
        assertEquals(if (pct < 80) "warn" else "", k.getValue("of visited projects working").tone)
        assertEquals("4", k.getValue("open repairs (2 urgent)").value); assertEquals("bad", k.getValue("open repairs (2 urgent)").tone)
        assertEquals("2", k.getValue("repairs fixed").value)
        assertEquals("1", k.getValue("new site added").value)
        assertEquals("1", k.getValue("location pinned").value)
        assertEquals(listOf("Kenya", "Tanzania", "Uganda", "Malawi"), r.byCountry.map { it.name })
        assertEquals(listOf(5, 3, 2, 1), r.byCountry.map { it.projects }); assertEquals(listOf(4, 3, 2, 1), r.byCountry.map { it.visited })
        assertEquals(listOf(2, 1, 1, 0), r.byCountry.map { it.open })
        assertEquals(10, r.byCountry.sumOf { it.working + it.repair + it.notWorking })
        // urgent first, then this month, then keep an eye on it; KES total of the estimates
        assertEquals(listOf("Urgent", "Urgent", "This month", "Keep an eye on it"), r.repairs.map { it.urgency })
        assertEquals(35700L, r.cost)
        assertEquals("–", r.repairs.last().cost?.toString() ?: "–")
        assertEquals("1 Oct", r.visits.first().date)
        // one country: only its projects and repairs, but new sites still count
        val ke = report("ke")
        assertEquals(listOf("Kenya"), ke.byCountry.map { it.name }); assertEquals(2, ke.repairs.size); assertEquals("1", ke.kpis[5].value)
        assertTrue(ke.visits.all { v -> projects.first { it[1] == v.title }[3] == "ke" })
    }

    @Test fun report_pdf_on_letterhead() {
        Lang.sw = false
        listOf(Triple("ke", "", "all"), Triple("tz", "", "all"), Triple("ke", "ke", "kenya")).forEach { (home, ck, tag) ->
            val r = report(ck, home)
            // the PDF exactly as shared from the app (Android's PdfDocument; Robolectric has no PDF back end, so this only works on a phone)
            val real = File(out, "report-$home-$tag-device.pdf"); real.delete()
            runCatching { writeReportPdf(app, r, home, real) }.onFailure { println("PdfDocument not available here: $it") }
            if (real.exists() && real.length() > 0) assertTrue("PDF written", real.readBytes().copyOfRange(0, 4).decodeToString() == "%PDF")
            // the same drawing code onto 2x bitmaps: saved as PNGs and as an A4 PDF to look at
            val pages = mutableListOf<Bitmap>()
            drawReportPages(app, r, home, {
                val b = Bitmap.createBitmap(1190, 1684, Bitmap.Config.ARGB_8888); pages += b; Canvas(b).apply { drawColor(Color.WHITE); scale(2f, 2f) }
            }, { File(out, "report-$home-$tag-p${pages.size}.png").outputStream().use { pages.last().compress(Bitmap.CompressFormat.PNG, 100, it) } })
            assertTrue("46 visits run onto a second page (${pages.size})", pages.size in 2..3 || (ck == "ke" && pages.size in 1..3))
            // the foot of every page: the RAFIKI logo (black wordmark, bottom right), and the round seal is gone from the app
            pages.forEach { b -> assertTrue("logo at the foot", (1000 until 1100).any { x -> (1610 until 1640).any { y -> Color.red(b.getPixel(x, y)) < 60 } }) }
            assertEquals(0, app.resources.getIdentifier("letterhead_seal", "drawable", app.packageName))
            val f = File(out, "report-$home-$tag.pdf"); pdfOf(pages, f)
            assertTrue("PDF written", f.readBytes().copyOfRange(0, 4).decodeToString() == "%PDF")
        }
    }

    /** A plain A4 PDF with one picture per page (595×842pt). */
    private fun pdfOf(pages: List<Bitmap>, f: File) {
        val bos = java.io.ByteArrayOutputStream(); val offs = mutableListOf<Int>()
        fun w(s: String) = bos.write(s.toByteArray(Charsets.ISO_8859_1))
        fun obj(n: Int, head: String, stream: ByteArray? = null) { offs += bos.size(); w("$n 0 obj\n$head"); if (stream != null) { w("\nstream\n"); bos.write(stream); w("\nendstream") }; w("\nendobj\n") }
        w("%PDF-1.4\n")
        obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        obj(2, "<< /Type /Pages /Kids [" + pages.indices.joinToString(" ") { "${3 + it * 3} 0 R" } + "] /Count ${pages.size} >>")
        pages.forEachIndexed { i, b ->
            val p = 3 + i * 3; val jpg = java.io.ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray(); val cs = "q 595 0 0 842 0 0 cm /Im0 Do Q".toByteArray()
            obj(p, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /XObject << /Im0 ${p + 2} 0 R >> >> /Contents ${p + 1} 0 R >>")
            obj(p + 1, "<< /Length ${cs.size} >>", cs)
            obj(p + 2, "<< /Type /XObject /Subtype /Image /Width ${b.width} /Height ${b.height} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpg.size} >>", jpg)
        }
        val x = bos.size(); w("xref\n0 ${offs.size + 1}\n0000000000 65535 f \n"); offs.forEach { w("%010d 00000 n \n".format(it)) }
        w("trailer\n<< /Size ${offs.size + 1} /Root 1 0 R >>\nstartxref\n$x\n%%EOF\n")
        f.writeBytes(bos.toByteArray())
    }

    private fun showReport(home: String, name: String, height: Int = 844) {
        org.rafikioutreach.pin.ui.Onb.enabled = false
        val snap = snapJson(home, name)
        Prefs(app).apply { pin = "9999"; this.name = name; saveSnapshot(snap); filter = "all" }
        val m = AppModel(app)
        // this month's activity, so the default month shows the tables
        val now = YearMonth.now()
        val act = JSONArray(); activity().forEach { e -> act.put(JSONObject(e.toString()).put("at", e.optString("at").replace("2026-10", now.toString()).replace("2026-09", now.minusMonths(1).toString()))) }
        m.api.transport = { _, url, _, _ ->
            if (url.contains("activity=1")) 200 to JSONObject().put("ok", true).put("activity", act).toString()
            else 200 to snap.toString()
        }
        rule.setContent { RafikiTheme(dark = false) { Box(Modifier.size(390.dp, height.dp)) { ReportScreen(m) {} } } }
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun report_screen_kenya_team() {
        Lang.sw = false
        showReport("ke", "Meril Kiprop")
        rule.onNodeWithText("Mega Centre, 3rd Floor, Makasembo Road").assertExists()
        rule.onNodeWithText("Email: info@rafikioutreach.org").assertExists()
        assertTrue(runCatching { rule.onNodeWithText("1817 N. Beck Street").assertExists() }.isFailure)
        rule.onNodeWithText("Share PDF").assertExists(); rule.onNodeWithText("Save as PDF").assertExists()
        rule.onNodeWithText("By country").assertExists(); rule.onNodeWithText("Visits this month").assertExists()
        rule.onNodeWithText("site visits").assertExists()
        rule.onNodeWithTag("footLogo").assertExists()
        shot("report-screen-ke")
    }

    @Test fun report_screen_other_countries() {
        Lang.sw = false
        showReport("tz", "Bertin Ngindu")
        rule.onNodeWithText("1817 N. Beck Street").assertExists()
        rule.onNodeWithText("Salt Lake City, UT 84116, U.S.A.").assertExists()
        assertTrue(runCatching { rule.onNodeWithText("Kitale, Kenya").assertExists() }.isFailure)
        shot("report-screen-tz")
    }

    /** The whole report sheet on a tall window, to look at. */
    @Config(qualifiers = "w390dp-h3000dp-xxhdpi")
    @Test fun report_screen_full() {
        Lang.sw = false
        showReport("ke", "Meril Kiprop", 3000)
        rule.onNodeWithText("Open repairs · about KES 35,700 estimated").assertExists()
        shot("report-screen-full")
    }
}

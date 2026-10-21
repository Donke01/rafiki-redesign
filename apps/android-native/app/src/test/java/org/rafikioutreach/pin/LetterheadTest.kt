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
import org.rafikioutreach.pin.ui.RafikiTheme
import org.rafikioutreach.pin.ui.ReportData
import org.rafikioutreach.pin.ui.ReportScreen
import org.rafikioutreach.pin.ui.drawReportPages
import org.rafikioutreach.pin.ui.letterheadAddress
import org.rafikioutreach.pin.ui.writeReportPdf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The monthly report on the RAFIKI letterhead: Kenya team → Kitale office, everyone else → the US head office. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class LetterheadTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun report(): ReportData {
        val names = listOf("Kapsaret Borehole", "Turbo Spring", "Chepkongi Dispensary"); val st = listOf("working", "repair", "notworking")
        val visits = (0 until 46).map { i -> JSONObject().put("a", "visit").put("id", "10$i").put("t", names[i % 3]).put("by", listOf("Meril Kiprop", "Bertin Ngindu")[i % 2]).put("s", st[i % 3]).put("at", "2026-10-%02dT09:00:00Z".format(1 + i % 20)) }
        return ReportData("October 2026", visits, setOf("101", "104", "105"), 2, 1, 1, 0, 1, 2, listOf(listOf("Kenya", "4", "3", "2", "0", "1", "1")))
    }

    @Test fun address_by_home_country() {
        assertEquals("Mega Centre, 3rd Floor, Makasembo Road", letterheadAddress("ke")[1])
        assertEquals("Email: info@rafikioutreach.org", letterheadAddress("ke")[4])
        listOf("tz", "ug", "mw", "").forEach { assertEquals(listOf("Rafiki Outreach", "1817 N. Beck Street", "Salt Lake City, UT 84116, U.S.A.", "Phone: +1 801 547 7847", "Email: Info@rafikioutreach.org"), letterheadAddress(it)) }
    }

    @Test fun report_pdf_on_letterhead() {
        Lang.sw = false
        listOf("ke" to "Kenya", "tz" to "Tanzania").forEach { (home, country) ->
            // the PDF exactly as shared from the app (Android's PdfDocument; Robolectric has no PDF back end, so this only works on a phone)
            val real = File(out, "letterhead-report-$home-device.pdf"); real.delete()
            runCatching { writeReportPdf(app, report(), country, "Meril Kiprop", home, real) }.onFailure { println("PdfDocument not available here: $it") }
            if (real.exists() && real.length() > 0) assertTrue("PDF written", real.readBytes().copyOfRange(0, 4).decodeToString() == "%PDF")
            // the same drawing code onto 2x bitmaps: saved as PNGs and as an A4 PDF to look at
            val pages = mutableListOf<Bitmap>()
            drawReportPages(app, report(), country, "Meril Kiprop", home, {
                val b = Bitmap.createBitmap(1190, 1684, Bitmap.Config.ARGB_8888); pages += b; Canvas(b).apply { drawColor(Color.WHITE); scale(2f, 2f) }
            }, { File(out, "letterhead-report-$home-p${pages.size}.png").outputStream().use { pages.last().compress(Bitmap.CompressFormat.PNG, 100, it) } })
            assertTrue("report runs onto a second page", pages.size >= 2)
            val f = File(out, "letterhead-report-$home.pdf"); pdfOf(pages, f)
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

    private fun showReport(home: String, name: String) {
        org.rafikioutreach.pin.ui.Onb.enabled = false
        val snap = JSONObject().put("ok", true).put("who", name).put("admin", false).put("me", JSONObject().put("kind", "app").put("home", home))
            .put("projects", JSONArray().put(JSONObject().put("id", "104").put("title", "Kapsaret Borehole").put("country", "Kenya").put("ck", "ke").put("area", "Eldoret").put("type", "borehole").put("status", "Completed").put("gps", JSONArray().put(0.47).put(35.25))))
            .put("repairs", JSONObject()).put("visits", JSONObject()).put("jobs", JSONArray()).put("assigned", JSONArray()).put("confirmed", JSONObject())
        Prefs(app).apply { pin = "9999"; this.name = name; saveSnapshot(snap); filter = "all" }
        val m = AppModel(app)
        m.api.transport = { _, url, _, _ ->
            if (url.contains("activity=1")) 200 to JSONObject().put("ok", true).put("activity", JSONArray().put(JSONObject().put("by", name).put("a", "visit").put("s", "working").put("id", "104").put("t", "Kapsaret Borehole").put("at", java.time.Instant.now().toString()))).toString()
            else 200 to snap.toString()
        }
        rule.setContent { RafikiTheme(dark = false) { Box(Modifier.size(390.dp, 844.dp)) { ReportScreen(m) {} } } }
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
        shot("letterhead-screen-ke")
    }

    @Test fun report_screen_other_countries() {
        Lang.sw = false
        showReport("tz", "Bertin Ngindu")
        rule.onNodeWithText("1817 N. Beck Street").assertExists()
        rule.onNodeWithText("Salt Lake City, UT 84116, U.S.A.").assertExists()
        assertTrue(runCatching { rule.onNodeWithText("Kitale, Kenya").assertExists() }.isFailure)
        shot("letterhead-screen-tz")
    }
}

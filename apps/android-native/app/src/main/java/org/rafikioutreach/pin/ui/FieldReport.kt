package org.rafikioutreach.pin.ui

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import org.json.JSONObject
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.R
import org.rafikioutreach.pin.data.COUNTRIES
import org.rafikioutreach.pin.data.DUE_DAYS
import org.rafikioutreach.pin.data.Snapshot
import org.rafikioutreach.pin.data.stateName
import org.rafikioutreach.pin.data.urgencyName
import org.rafikioutreach.pin.t
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// --- the monthly field report: the same sums, sections and design as the web report (reportHtml in pin.html) ---

data class Kpi(val value: String, val label: String, val tone: String = "")
data class CountryRow(val name: String, val projects: Int, val visited: Int, val working: Int, val repair: Int, val notWorking: Int, val open: Int)
data class RepairRow(val title: String, val country: String, val state: String, val date: String, val by: String, val urgency: String, val problems: String, val parts: String, val cost: Long?)
data class VisitRow(val date: String, val title: String, val by: String, val state: String, val urgency: String)
data class ReportData(
    val label: String, val country: String, val by: String, val date: String,
    val kpis: List<Kpi>, val byCountry: List<CountryRow>, val repairs: List<RepairRow>, val cost: Long, val visits: List<VisitRow>,
) {
    val fileName: String get() = "RAFIKI-field-report-" + label.replace(' ', '-') + ".pdf"
}

private val URGO = mapOf("urgent" to 0, "month" to 1, "watch" to 2)
private fun loc(): Locale = if (org.rafikioutreach.pin.Lang.sw) Locale("sw") else Locale.UK
fun reportMonth(ym: YearMonth): String = DateTimeFormatter.ofPattern("MMMM yyyy", loc()).format(ym)
private fun dayMonth(iso: String, zone: ZoneId) = runCatching { DateTimeFormatter.ofPattern("d MMM", loc()).withZone(zone).format(Instant.parse(iso)) }.getOrDefault(iso.take(10))
private fun num(n: Long) = "%,d".format(Locale.US, n)

/** The month's report for [ck] ("" = all countries), counted exactly like the web app's report. */
fun buildReport(snap: Snapshot, act: List<JSONObject>, ym: YearMonth, ck: String, by: String, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): ReportData {
    val byId = snap.projects.associateBy { it.id }
    val inC = { id: String -> ck.isBlank() || byId[id]?.ck == ck }
    val from = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(); val to = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val ev = act.filter { e ->
        val at = runCatching { Instant.parse(e.optString("at")).toEpochMilli() }.getOrNull()
        at != null && at >= from && at < to && (e.optString("a") == "new" || inC(e.optString("id")))
    }
    val visits = ev.filter { it.optString("a") == "visit" }
    val latest = LinkedHashMap<String, JSONObject>()
    visits.forEach { e -> val id = e.optString("id"); val l = latest[id]; if (l == null || l.optString("at") < e.optString("at")) latest[id] = e }
    val ids = latest.keys.toList()
    val working = ids.count { latest.getValue(it).optString("s") == "working" }
    val projs = snap.projects.filter { ck.isBlank() || it.ck == ck }
    val open = snap.repairs.entries.filter { inC(it.key) }.sortedBy { URGO[it.value.urgency] ?: 3 }
    val urgent = open.count { it.value.urgency == "urgent" }
    val cost = open.sumOf { it.value.cost ?: 0L }
    val due = projs.count { it.gps != null && ageDays(snap, it) >= DUE_DAYS }
    val n = { a: String -> ev.count { it.optString("a") == a } }
    val pc = if (ids.isEmpty()) "–" else "${(working * 100.0 / ids.size).roundToInt()}%"
    fun pl(k: Int, one: String, many: String) = if (k == 1) one else many
    val kpis = listOf(
        Kpi("${visits.size}", pl(visits.size, t("site visit", "ziara"), t("site visits", "ziara"))),
        Kpi("${ids.size}", pl(ids.size, t("project visited", "mradi uliotembelewa"), t("projects visited", "miradi iliyotembelewa"))),
        Kpi(pc, t("of visited projects working", "ya miradi iliyotembelewa inafanya kazi"), if (ids.isNotEmpty() && working.toDouble() / ids.size < .8) "warn" else ""),
        Kpi("${open.size}", pl(open.size, t("open repair", "tengenezo lililo wazi"), t("open repairs", "matengenezo yaliyo wazi")) + (if (urgent > 0) t(" ($urgent urgent)", " ($urgent ya haraka)") else ""), if (urgent > 0) "bad" else ""),
        Kpi("${n("fixed")}", pl(n("fixed"), t("repair fixed", "tengenezo lililokamilika"), t("repairs fixed", "matengenezo yaliyokamilika"))),
        Kpi("${n("new")}", pl(n("new"), t("new site added", "eneo jipya lililoongezwa"), t("new sites added", "maeneo mapya yaliyoongezwa"))),
        Kpi("${n("pin")}", pl(n("pin"), t("location pinned", "mahali palipowekwa"), t("locations pinned", "mahali palipowekwa"))),
        Kpi("$due", t("due a visit (6+ months)", "zinasubiri ziara (miezi 6+)")),
    )
    val byCountry = COUNTRIES.filter { ck.isBlank() || it.key == ck }.map { (c, name) ->
        val cid = ids.filter { byId[it]?.ck == c }
        val k = { s: String -> cid.count { latest.getValue(it).optString("s") == s } }
        CountryRow(name, snap.projects.count { it.ck == c }, cid.size, k("working"), k("repair"), k("notworking"), open.count { byId[it.key]?.ck == c })
    }
    val repairs = open.map { (id, r) ->
        val p = byId[id]
        RepairRow(p?.title ?: r.title.ifBlank { id }, p?.country.orEmpty(), if (r.state == "notworking") "notworking" else "repair", dayMonth(r.at, zone), r.by,
            if (r.urgency.isNotBlank()) urgencyName(r.urgency) else "–", r.problems.joinToString(", ").ifBlank { r.note.ifBlank { "–" } }, r.parts.ifBlank { "–" }, r.cost?.takeIf { it > 0 })
    }
    val rows = visits.sortedBy { it.optString("at") }.map { e ->
        VisitRow(dayMonth(e.optString("at"), zone), byId[e.optString("id")]?.title ?: e.optString("t"), e.optString("by"), e.optString("s"),
            e.optString("u").takeIf { it.isNotBlank() }?.let { urgencyName(it) }.orEmpty())
    }
    return ReportData(reportMonth(ym), COUNTRIES[ck] ?: t("All countries", "Nchi zote"), by, DateTimeFormatter.ofPattern("d MMMM yyyy", loc()).format(today),
        kpis, byCountry, repairs, cost, rows)
}

// --- the three tables, defined once for the screen and the PDF (column widths in PDF points, 508.6pt across) ---

/** One table cell: text (bold or not, after a status mark), and an optional small grey line under it. */
internal class RCell(val text: String, val bold: Boolean = false, val icon: String? = null, val sub: String = "", val subIcon: String? = null, val subTail: String = "") {
    val hasSub get() = sub.isNotEmpty() || subIcon != null || subTail.isNotEmpty()
}
internal class RTable(val cols: List<Float>, val head: List<RCell>, val rows: List<List<RCell>>)

internal fun reportTitle(r: ReportData) = t("Field report · ", "Ripoti ya uwanjani · ") + r.label
internal fun reportSub(r: ReportData) = r.country + " · RAFIKI Outreach · " + t("Prepared by ", "Imeandaliwa na ") + r.by + ", " + r.date
internal fun repairsHeading(r: ReportData) = t("Open repairs", "Matengenezo yaliyo wazi") + (if (r.cost > 0) t(" · about KES ${num(r.cost)} estimated", " · takriban KES ${num(r.cost)} kwa makadirio") else "")
internal fun reportNote() = t("Only visits, repairs and pins made with the RAFIKI Pin app are counted.", "Ziara, matengenezo na mahali vilivyowekwa kwa programu ya RAFIKI Pin tu ndivyo vinahesabiwa.")

internal fun countryTable(r: ReportData) = RTable(
    listOf(92f, 58f, 58f, 74f, 66f, 84f, 76.6f),
    listOf(RCell(t("Country", "Nchi")), RCell(t("Projects", "Miradi")), RCell(t("Visited", "Zimetembelewa")), RCell(t("Working", "Zinafanya kazi"), icon = "working"),
        RCell(t("Repair", "Matengenezo"), icon = "repair"), RCell(t("Not working", "Hazifanyi kazi"), icon = "notworking"), RCell(t("Open repairs", "Matengenezo wazi"))),
    r.byCountry.map { c -> listOf(RCell(c.name), RCell("${c.projects}"), RCell("${c.visited}"), RCell("${c.working}"), RCell("${c.repair}"), RCell("${c.notWorking}"), RCell("${c.open}")) },
)

internal fun repairTable(r: ReportData) = RTable(
    listOf(128f, 68f, 62f, 128f, 70f, 52.6f),
    listOf(RCell(t("Project", "Mradi")), RCell(t("Reported", "Iliripotiwa")), RCell(t("Urgency", "Uharaka")), RCell(t("Problems", "Matatizo")), RCell(t("Parts", "Vipuri")), RCell(t("Est. KES", "Makadirio KES"))),
    r.repairs.map { x ->
        listOf(RCell(x.title, bold = true, sub = if (x.country.isNotBlank()) x.country + " · " else "", subIcon = x.state, subTail = stateName(x.state).lowercase(loc())),
            RCell(x.date, sub = x.by), RCell(x.urgency), RCell(x.problems), RCell(x.parts), RCell(x.cost?.let { num(it) } ?: "–"))
    },
)

internal fun visitTable(r: ReportData) = RTable(
    listOf(62f, 176f, 118f, 152.6f),
    listOf(RCell(t("Date", "Tarehe")), RCell(t("Project", "Mradi")), RCell(t("By", "Na")), RCell(t("Result", "Matokeo"))),
    r.visits.map { v -> listOf(RCell(v.date), RCell(v.title), RCell(v.by), RCell(stateName(v.state) + (if (v.urgency.isNotBlank()) " · " + v.urgency else ""), icon = v.state.takeIf { it in STATE_ICONS })) },
)

private val STATE_ICONS = setOf("working", "repair", "notworking")

/** The web report's status marks (✅ ⚠️ ❌), drawn rather than typed so they look the same in every PDF viewer. [s] is the size, top-left at [x],[top]. */
fun drawStateIcon(c: Canvas, state: String, x: Float, top: Float, s: Float) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    when (state) {
        "working" -> {
            p.color = 0xFF3BA55C.toInt(); c.drawRoundRect(RectF(x, top, x + s, top + s), s * .22f, s * .22f, p)
            p.color = 0xFFFFFFFF.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = s * .15f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
            c.drawPath(Path().apply { moveTo(x + s * .25f, top + s * .53f); lineTo(x + s * .43f, top + s * .7f); lineTo(x + s * .76f, top + s * .31f) }, p)
        }
        "repair" -> {
            p.color = 0xFFF2B01E.toInt(); p.style = Paint.Style.FILL_AND_STROKE; p.strokeWidth = s * .12f; p.strokeJoin = Paint.Join.ROUND
            c.drawPath(Path().apply { moveTo(x + s / 2, top + s * .1f); lineTo(x + s * .95f, top + s * .9f); lineTo(x + s * .05f, top + s * .9f); close() }, p)
            p.color = 0xFF33270A.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = s * .12f; p.strokeCap = Paint.Cap.ROUND
            c.drawLine(x + s / 2, top + s * .4f, x + s / 2, top + s * .6f, p); c.drawPoint(x + s / 2, top + s * .76f, p)
        }
        "notworking" -> {
            p.color = 0xFFE0453A.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = s * .2f; p.strokeCap = Paint.Cap.ROUND
            c.drawLine(x + s * .2f, top + s * .2f, x + s * .8f, top + s * .8f, p); c.drawLine(x + s * .8f, top + s * .2f, x + s * .2f, top + s * .8f, p)
        }
    }
}

// --- the PDF: A4 (595×842pt) on the RAFIKI letterhead, laid out like the web report ------------------

private const val PW = 595f
private const val PM = 43.2f
private const val CW = PW - 2 * PM
private const val TOP1 = 124f
private const val TOPN = 40f
private const val INK = 0xFF04263F.toInt()
private const val BLUE = 0xFF076DB1.toInt()
private const val TEXT = 0xFF141618.toInt()
private const val MUTED = 0xFF5A6066.toInt()
private const val HEAD = 0xFF8A9198.toInt()

/** Montserrat (the app's font) at any weight; the phone's sans-serif if it can't be loaded. */
private class Fonts(ctx: Context) {
    private val base: Typeface = runCatching { ResourcesCompat.getFont(ctx, R.font.montserrat) }.getOrNull() ?: Typeface.SANS_SERIF
    fun p(size: Float, weight: Int, color: Int, align: Paint.Align = Paint.Align.LEFT) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color; textAlign = align; isSubpixelText = true
        typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, weight, false) else Typeface.create(base, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        if (Build.VERSION.SDK_INT >= 26) fontVariationSettings = "'wght' $weight"
    }
}

/** Fits [s] into [w] points, ending in "…" if it is too long. */
private fun fit(s: String, p: Paint, w: Float): String {
    if (p.measureText(s) <= w) return s
    var e = s; while (e.isNotEmpty() && p.measureText(e.trimEnd() + "…") > w) e = e.dropLast(1)
    return e.trimEnd() + "…"
}

/** Breaks [text] into lines of at most [w] points; the last of [max] lines ends in "…" if there is more. */
private fun wrap(text: String, p: Paint, w: Float, max: Int): List<String> {
    val out = mutableListOf<String>(); var cur = ""
    text.split(' ').forEach { word ->
        val next = if (cur.isEmpty()) word else "$cur $word"
        if (cur.isEmpty() || p.measureText(next) <= w) cur = next else { out += cur; cur = word }
    }
    if (cur.isNotEmpty() || out.isEmpty()) out += cur
    if (out.size > max) return out.take(max - 1) + fit(out.drop(max - 1).joinToString(" "), p, w)
    return out.map { fit(it, p, w) }
}

/** Collects what goes on each page first, so every footer can say "Page n of N". */
private class Pages(val bottom: Float) {
    val list = mutableListOf<MutableList<(Canvas) -> Unit>>()
    var y = 0f
    fun next() { list.add(mutableListOf()); y = if (list.size == 1) TOP1 else TOPN }
    fun op(f: (Canvas) -> Unit) { list.last().add(f) }
    fun fits(h: Float) = y + h <= bottom
}

private const val PAD_X = 5f
private const val PAD_Y = 5.5f
private const val LH = 11.5f
private const val SH = 10f
private const val ICON = 8.5f

private fun Pages.table(f: Fonts, tb: RTable) {
    val hp = f.p(7.5f, 600, HEAD); val bp = f.p(8.5f, 500, TEXT); val bb = f.p(8.5f, 700, TEXT); val sp = f.p(7f, 500, MUTED)
    val hair = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFDCE1E5.toInt(); strokeWidth = .6f }
    val under = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC3CBD2.toInt(); strokeWidth = .8f }
    val xs = tb.cols.runningFold(PM) { a, w -> a + w }
    fun header() {
        val y0 = y
        op { c ->
            tb.head.forEachIndexed { i, h ->
                var x = xs[i] + PAD_X
                if (h.icon != null) { drawStateIcon(c, h.icon, x, y0 + 7.2f, 7.5f); x += 10f }
                c.drawText(fit(h.text, hp, xs[i + 1] - x - PAD_X), x, y0 + 13.5f, hp)
            }
            c.drawLine(PM, y0 + 20f, PM + CW, y0 + 20f, under)
        }
        y += 20f
    }
    header()
    tb.rows.forEach { r ->
        val lines = r.mapIndexed { i, cell -> wrap(cell.text, if (cell.bold) bb else bp, tb.cols[i] - 2 * PAD_X - (if (cell.icon != null) ICON + 3f else 0f), 3) }
        val h = r.indices.maxOf { i -> lines[i].size * LH + (if (r[i].hasSub) SH else 0f) } + 2 * PAD_Y
        if (!fits(h)) { next(); header() }
        val y0 = y
        op { c ->
            r.forEachIndexed { i, cell ->
                val x = xs[i] + PAD_X; val p = if (cell.bold) bb else bp
                val tx = if (cell.icon != null) x + ICON + 3f else x
                lines[i].forEachIndexed { j, s ->
                    val base = y0 + PAD_Y + 8.4f + j * LH
                    if (j == 0 && cell.icon != null) drawStateIcon(c, cell.icon, x, base - 7.6f, ICON)
                    c.drawText(s, tx, base, p)
                }
                if (cell.hasSub) {
                    val base = y0 + PAD_Y + lines[i].size * LH + 7.6f; var sx = x; val room = xs[i + 1] - PAD_X
                    if (cell.sub.isNotEmpty()) { val s = fit(cell.sub, sp, room - sx); c.drawText(s, sx, base, sp); sx += sp.measureText(s) }
                    if (cell.subIcon != null) { drawStateIcon(c, cell.subIcon, sx, base - 6.4f, 7f); sx += 9f }
                    if (cell.subTail.isNotEmpty() && sx < room) c.drawText(fit(cell.subTail, sp, room - sx), sx, base, sp)
                }
            }
            c.drawLine(PM, y0 + h, PM + CW, y0 + h, hair)
        }
        y += h
    }
}

/** A section heading in RAFIKI blue, kept on the same page as the start of what follows it ([keep] points). */
private fun Pages.heading(f: Fonts, text: String, keep: Float) {
    if (!fits(20f + 22f + keep)) next() else y += 20f
    val y0 = y; val p = f.p(12.5f, 700, BLUE)
    op { c -> c.drawText(fit(text, p, CW), PM, y0 + 12.5f, p) }
    y += 22f
}

/** Draws the report onto 595×842pt pages: letterhead header on page 1, its footer (with "Page n of N") on every page. */
internal fun drawReportPages(ctx: Context, r: ReportData, home: String, startPage: () -> Canvas, endPage: () -> Unit) {
    val f = Fonts(ctx); val pg = Pages(LETTERHEAD_BODY_BOTTOM); pg.next()
    // title and who made it
    val title = f.p(19f, 700, INK); val sub = f.p(9.5f, 500, MUTED)
    pg.op { c -> c.drawText(fit(reportTitle(r), title, CW), PM, TOP1 + 17f, title); c.drawText(fit(reportSub(r), sub, CW), PM, TOP1 + 34f, sub) }
    pg.y += 50f
    // the numbers: tinted tiles, four to a row
    val gap = 8f; val tw = (CW - 3 * gap) / 4
    val vp = f.p(17f, 700, INK); val lp = f.p(7.5f, 600, 0xFF3A3F44.toInt())
    r.kpis.chunked(4).forEach { row ->
        val labs = row.map { wrap(it.label, lp, tw - 18f, 2) }
        val h = 10f + 19f + 4f + labs.maxOf { it.size } * 9.6f + 7f
        if (!pg.fits(h)) pg.next()
        val y0 = pg.y
        pg.op { c ->
            row.forEachIndexed { i, k ->
                val x = PM + i * (tw + gap)
                c.drawRoundRect(RectF(x, y0, x + tw, y0 + h), 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = when (k.tone) { "warn" -> 0xFFFDF0DC.toInt(); "bad" -> 0xFFFBE7E5.toInt(); else -> 0xFFE3F1FC.toInt() } })
                c.drawText(k.value, x + 9f, y0 + 10f + 16f, vp)
                labs[i].forEachIndexed { j, s -> c.drawText(s, x + 9f, y0 + 10f + 19f + 4f + 6.5f + j * 9.6f, lp) }
            }
        }
        pg.y += h + gap
    }
    val muted = f.p(9f, 500, MUTED)
    fun none(text: String) { if (!pg.fits(16f)) pg.next(); val y0 = pg.y; pg.op { c -> c.drawText(text, PM, y0 + 10f, muted) }; pg.y += 16f }
    pg.heading(f, t("By country", "Kwa nchi"), 45f); pg.table(f, countryTable(r))
    pg.heading(f, repairsHeading(r), 45f); if (r.repairs.isEmpty()) none(t("None.", "Hakuna.")) else pg.table(f, repairTable(r))
    pg.heading(f, t("Visits this month", "Ziara za mwezi huu"), 45f); if (r.visits.isEmpty()) none(t("No visits recorded in the app this month.", "Hakuna ziara zilizorekodiwa kwenye programu mwezi huu.")) else pg.table(f, visitTable(r))
    pg.y += 10f; none(reportNote())
    // now draw: letterhead on page 1, footer and page numbers on every page
    pg.list.forEachIndexed { i, ops ->
        val c = startPage()
        if (i == 0) drawLetterheadTop(ctx, c, home)
        drawLetterheadFoot(ctx, c, page = i + 1, pages = pg.list.size)
        ops.forEach { it(c) }
        endPage()
    }
}

/** The report as an A4 PDF on the RAFIKI letterhead (address by [home], the reporter's home country). */
internal fun writeReportPdf(ctx: Context, r: ReportData, home: String, f: File) {
    val doc = PdfDocument(); var pageNo = 0; var page: PdfDocument.Page? = null
    try {
        drawReportPages(ctx, r, home, { doc.startPage(PdfDocument.PageInfo.Builder(595, 842, ++pageNo).create()).also { page = it }.canvas }, { doc.finishPage(page!!) })
        f.outputStream().use { doc.writeTo(it) }
    } finally { doc.close() }
}

private fun reportPdf(ctx: Context, r: ReportData, home: String): File {
    val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
    return File(dir, r.fileName).also { writeReportPdf(ctx, r, home, it) }
}

/** Writes the PDF and opens the share sheet (WhatsApp, e-mail, Drive…). */
private fun sharePdf(ctx: Context, r: ReportData, home: String) {
    val f = reportPdf(ctx, r, home)
    val uri = FileProvider.getUriForFile(ctx, "org.rafikioutreach.pin.files", f)
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), f.name))
}

// --- the report screen: the same page as the PDF, on a white sheet ---------------------------------------

@Composable
fun ReportScreen(m: AppModel, back: () -> Unit) {
    val s by m.snap.collectAsState()
    val act by m.activity.collectAsState()
    val snap = s ?: return
    val ctx = LocalContext.current
    var ym by remember { mutableStateOf(YearMonth.now()) }
    var ck by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { if (!m.loadActivity()) err = t("No signal: the report needs a connection the first time.", "Hakuna mtandao: ripoti inahitaji mtandao mara ya kwanza.") }
    val months = (0 until 13).map { YearMonth.now().minusMonths(it.toLong()) }
    val home = snap.me.home.ifBlank { m.prefs.country }
    val rep = remember(snap, act, ym, ck, org.rafikioutreach.pin.Lang.sw) { buildReport(snap, act.orEmpty(), ym, ck, m.prefs.name) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) runCatching {
            val f = reportPdf(ctx, rep, home)
            ctx.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } } ?: error("no file")
        }.onSuccess { err = null; done = t("Saved: ", "Imehifadhiwa: ") + rep.fileName }.onFailure { done = null; err = t("Couldn't save the PDF. Try Share PDF instead.", "Imeshindwa kuhifadhi PDF. Jaribu Shiriki PDF.") }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Picker(t("Month", "Mwezi"), ym.toString(), months.map { it.toString() to reportMonth(it) }, { ym = YearMonth.parse(it); done = null }, Modifier.weight(1f))
            Picker(t("Country", "Nchi"), ck, listOf("" to t("All countries", "Nchi zote")) + COUNTRIES.toList(), { ck = it; done = null }, Modifier.weight(1f))
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn(t("Share PDF", "Shiriki PDF"), { runCatching { sharePdf(ctx, rep, home) }.onFailure { err = t("Couldn't make the PDF.", "Imeshindwa kutengeneza PDF.") } }, Modifier.weight(1f).testTag("sharePdf"), icon = "share")
            Btn(t("Save as PDF", "Hifadhi PDF"), { save.launch(rep.fileName) }, Modifier.weight(1f).testTag("savePdf"), icon = "file", light = true)
        }
        Msg(err, "warn"); Msg(done, "ok")
        ReportPaper(rep, home)
        Spacer(Modifier.height(24.dp))
    }
}

private val PInk = Color(0xFF04263F)
private val PBlue = Color(0xFF076DB1)
private val PText = Color(0xFF141618)
private val PMuted = Color(0xFF5A6066)
private val PLine = Color(0xFFDCE1E5)
private val PNavy = Color(0xFF1A365D)

/** The report as a white sheet, like the web report (the same sections and tables as the PDF). */
@Composable
private fun ReportPaper(r: ReportData, home: String) {
    val shape = RoundedCornerShape(18.dp)
    val body = MaterialTheme.typography.bodySmall
    val helv = body.copy(fontSize = 9.sp, lineHeight = 12.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal)
    Column(Modifier.padding(top = 14.dp).fillMaxWidth().shadow(4.dp, shape).clip(shape).background(Color.White).padding(horizontal = 16.dp, vertical = 18.dp).testTag("report")) {
        // the RAFIKI letterhead, as on the PDF (address by the reporter's home country)
        Row(Modifier.fillMaxWidth().testTag("letterhead"), verticalAlignment = Alignment.Bottom) {
            Image(painterResource(R.drawable.letterhead_logo), "RAFIKI Outreach", Modifier.weight(0.36f).padding(bottom = 8.dp), contentScale = ContentScale.FillWidth)
            Column(Modifier.weight(0.64f).padding(start = 8.dp), horizontalAlignment = Alignment.End) {
                letterheadAddress(home).forEach { Text(it, style = helv, color = Color.Black, textAlign = TextAlign.End) }
            }
        }
        Box(Modifier.padding(bottom = 16.dp).fillMaxWidth().height(1.5.dp).background(PNavy))
        Text(reportTitle(r), style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, lineHeight = 26.sp), color = PInk)
        Text(reportSub(r), style = body.copy(fontSize = 12.sp), color = PMuted, modifier = Modifier.padding(top = 2.dp))
        // the numbers: tinted tiles, two to a row on a phone (four on the PDF), as on the web report
        r.kpis.chunked(2).forEachIndexed { i, row ->
            Row(Modifier.padding(top = if (i == 0) 14.dp else 8.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { k ->
                    Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(when (k.tone) { "warn" -> Color(0xFFFDF0DC); "bad" -> Color(0xFFFBE7E5); else -> Color(0xFFE3F1FC) }).padding(10.dp)) {
                        Text(k.value, style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, lineHeight = 24.sp), color = PInk)
                        Text(k.label, style = body.copy(fontSize = 11.5.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold), color = Color(0xFF3A3F44))
                    }
                }
            }
        }
        PaperHeading(t("By country", "Kwa nchi")); PaperTable(countryTable(r))
        PaperHeading(repairsHeading(r))
        if (r.repairs.isEmpty()) Text(t("None. 🎉", "Hakuna. 🎉"), style = MaterialTheme.typography.bodyMedium, color = PMuted) else PaperTable(repairTable(r))
        PaperHeading(t("Visits this month", "Ziara za mwezi huu"))
        if (r.visits.isEmpty()) Text(t("No visits recorded in the app this month.", "Hakuna ziara zilizorekodiwa kwenye programu mwezi huu."), style = MaterialTheme.typography.bodyMedium, color = PMuted) else PaperTable(visitTable(r))
        Text(reportNote(), style = body.copy(fontSize = 11.5.sp), color = PMuted, modifier = Modifier.padding(top = 16.dp))
        // the letterhead foot: navy rule, the nonprofit line and the RAFIKI logo
        Row(Modifier.padding(top = 18.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(PNavy))
                Text(LETTERHEAD_FOOT, style = helv.copy(fontSize = 8.sp, lineHeight = 10.sp), color = Color(0xFF808080), modifier = Modifier.padding(top = 4.dp))
            }
            Image(painterResource(R.drawable.letterhead_logo), "RAFIKI Outreach", Modifier.padding(start = 12.dp).width(58.dp).testTag("footLogo"), contentScale = ContentScale.FillWidth)
        }
    }
}

@Composable
private fun PaperHeading(text: String) = Text(text, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = PBlue, modifier = Modifier.padding(top = 22.dp, bottom = 6.dp))

@Composable
private fun StateIcon(state: String, size: Dp) = androidx.compose.foundation.Canvas(Modifier.size(size)) { drawIntoCanvas { drawStateIcon(it.nativeCanvas, state, 0f, 0f, this.size.width) } }

/** A report table like the web one on screen: blue header row, light stripes, hairlines; wider than a phone, so it scrolls sideways. */
@Composable
private fun PaperTable(tb: RTable) {
    val cell = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, lineHeight = 15.sp, color = PText)
    val small = cell.copy(fontSize = 10.sp, lineHeight = 13.sp, color = PMuted)
    val head = small.copy(fontWeight = FontWeight.Bold, color = Color.White)
    // columns as wide as their header, and wide enough for most cells on one line (up to 1.6× the PDF column)
    val tm = rememberTextMeasurer(); val d = LocalDensity.current
    val w = remember(tb) {
        tb.cols.mapIndexed { i, pt ->
            fun px(text: String, st: androidx.compose.ui.text.TextStyle) = tm.measure(text, st, maxLines = 1).size.width
            val icon = with(d) { 16.dp.toPx() }
            val hw = px(tb.head[i].text, head) + (if (tb.head[i].icon != null) icon else 0f)
            val cw = tb.rows.maxOfOrNull { r ->
                val c = r[i]
                maxOf(px(c.text, cell.copy(fontWeight = if (c.bold) FontWeight.Bold else FontWeight.Medium)) + (if (c.icon != null) icon else 0f),
                    if (c.hasSub) px(c.sub + c.subTail, small) + (if (c.subIcon != null) icon else 0f) else 0f)
            } ?: 0f
            with(d) { maxOf((pt * 0.96f).dp, (hw + 12.dp.toPx()).toDp(), minOf((cw + 12.dp.toPx()).toDp(), (pt * 1.6f).dp)) }
        }
    }
    val total = w.fold(0.dp) { a, b -> a + b }
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        Row(Modifier.width(total).clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)).background(PBlue), verticalAlignment = Alignment.CenterVertically) {
            tb.head.forEachIndexed { i, h ->
                Row(Modifier.width(w[i]).padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (h.icon != null) { StateIcon(h.icon, 11.dp); Spacer(Modifier.width(4.dp)) }
                    Text(h.text, style = head, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        tb.rows.forEachIndexed { n, r ->
            Row(Modifier.width(total).background(if (n % 2 == 1) Color(0xFFF4F9FD) else Color.White)) {
                r.forEachIndexed { i, c ->
                    Column(Modifier.width(w[i]).padding(horizontal = 6.dp, vertical = 7.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            if (c.icon != null) { Box(Modifier.padding(top = 2.dp)) { StateIcon(c.icon, 12.dp) }; Spacer(Modifier.width(4.dp)) }
                            Text(c.text, style = cell.copy(fontWeight = if (c.bold) FontWeight.Bold else FontWeight.Medium), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        if (c.hasSub) Row(verticalAlignment = Alignment.CenterVertically) {
                            if (c.sub.isNotEmpty()) Text(c.sub, style = small, maxLines = 1)
                            if (c.subIcon != null) { StateIcon(c.subIcon, 10.dp); Spacer(Modifier.width(3.dp)) }
                            if (c.subTail.isNotEmpty()) Text(c.subTail, style = small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Box(Modifier.width(total).height(0.75.dp).background(PLine))
        }
    }
}

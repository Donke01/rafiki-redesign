package org.rafikioutreach.pin.ui

import androidx.compose.foundation.horizontalScroll

import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import org.json.JSONObject
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.COUNTRIES
import org.rafikioutreach.pin.data.DUE_DAYS
import org.rafikioutreach.pin.data.Geo
import org.rafikioutreach.pin.data.LatLng
import org.rafikioutreach.pin.data.Project
import org.rafikioutreach.pin.data.stateName
import org.rafikioutreach.pin.t
import java.io.File
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Nearest stop next, starting from where you are (or the first chosen stop). */
fun orderStops(stops: List<Project>, from: LatLng?): List<Project> {
    val left = stops.filter { it.gps != null }.toMutableList(); val out = mutableListOf<Project>()
    var at = from ?: left.firstOrNull()?.gps ?: return out
    while (left.isNotEmpty()) { val n = left.minBy { Geo.metres(at, it.gps!!) }; out += n; left -= n; at = n.gps!! }
    return out
}

/** Google Maps takes up to 9 stops on the way, so long routes are split into parts of 10. */
fun mapsLegs(stops: List<Project>, from: LatLng?): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>(); var origin = from; var i = 0
    while (i < stops.size) {
        val part = stops.subList(i, minOf(i + 10, stops.size)); val dest = part.last().gps!!
        val way = part.dropLast(1).joinToString("|") { "%.6f,%.6f".format(Locale.US, it.gps!!.lat, it.gps.lng) }
        val url = "https://www.google.com/maps/dir/?api=1" + (origin?.let { "&origin=%.6f,%.6f".format(Locale.US, it.lat, it.lng) } ?: "") +
            "&destination=%.6f,%.6f".format(Locale.US, dest.lat, dest.lng) + (if (way.isNotBlank()) "&waypoints=" + Uri.encode(way) else "") + "&travelmode=driving"
        out += (if (stops.size > 10) t("Part ${out.size + 1}: stops ${i + 1}–${i + part.size} in Google Maps", "Sehemu ${out.size + 1}: vituo ${i + 1}–${i + part.size} kwenye Google Maps") else t("Start in Google Maps", "Anza kwenye Google Maps")) to url
        origin = dest; i += 10
    }
    return out
}

@Composable
fun RouteScreen(m: AppModel, open: (String) -> Unit, back: () -> Unit) {
    val s by m.snap.collectAsState()
    val fix by m.fix.collectAsState()
    val snap = s ?: return
    val ctx = LocalContext.current
    val picked = remember { mutableStateListOf<String>().apply { addAll(m.prefs.route.split(",").filter { it.isNotBlank() }) } }
    var choosing by remember { mutableStateOf(picked.isEmpty()) }
    var show by remember { mutableStateOf("due") }
    var ck by remember { mutableStateOf(m.prefs.country) }
    fun save() { m.prefs.route = picked.joinToString(",") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(t("Today's route", "Njia ya leo"), style = MaterialTheme.typography.headlineLarge, color = C.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
        if (choosing) {
            Text(t("Tick the projects to visit. The app puts them in order, nearest first.", "Weka alama kwenye miradi utakayotembelea. Programu itaipanga, iliyo karibu kwanza."), style = MaterialTheme.typography.bodyMedium, color = C.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Picker(t("Show", "Onyesha"), show, listOf("due" to t("Due a visit", "Inasubiri ziara"), "repairs" to t("Open repairs", "Matengenezo yaliyo wazi"), "all" to t("All with a location", "Zote zenye mahali")), { show = it }, Modifier.weight(1f))
                Picker(t("Country", "Nchi"), ck, listOf("" to t("All", "Yote")) + COUNTRIES.toList(), { ck = it }, Modifier.weight(1f))
            }
            val rows = snap.projects.filter { p -> p.gps != null && (ck.isBlank() || p.ck == ck) && when (show) { "due" -> ageDays(snap, p) >= DUE_DAYS; "repairs" -> snap.repairs.containsKey(p.id); else -> true } }
                .let { l -> fix?.ll?.let { h -> l.sortedBy { Geo.metres(h, it.gps!!) } } ?: l }.take(80)
            rows.forEach { p ->
                Row(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (p.id in picked) C.tint else C.card).border(1.dp, if (p.id in picked) C.blue else C.line, RoundedCornerShape(14.dp))
                    .clickable { if (p.id in picked) picked.remove(p.id) else picked.add(p.id); save() }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(p.id in picked, { if (it) picked.add(p.id) else picked.remove(p.id); save() }, colors = CheckboxDefaults.colors(checkedColor = C.blue))
                    Column(Modifier.weight(1f)) { Text(p.title, style = MaterialTheme.typography.titleSmall, color = C.text); Text(p.sub, style = MaterialTheme.typography.bodySmall, color = C.muted) }
                    fix?.ll?.let { Tag(Geo.dist(Geo.metres(it, p.gps!!), m.prefs.miles), C.tint, C.deep) }
                }
            }
            Btn(if (picked.isEmpty()) t("Choose pinned projects to visit", "Chagua miradi yenye mahali utakayotembelea") else t("Plan route (${picked.size})", "Panga njia (${picked.size})"), { choosing = false }, Modifier.fillMaxWidth().padding(top = 14.dp), icon = "map", enabled = picked.isNotEmpty())
        } else {
            val stops = orderStops(snap.projects.filter { it.id in picked }, fix?.ll)
            var total = 0.0; var at = fix?.ll
            val legs = stops.map { p -> val d = at?.let { Geo.metres(it, p.gps!!) }; d?.let { total += it }; at = p.gps; p to d }
            Text(t("${stops.size} stops · about ${Geo.dist(total, m.prefs.miles)} in straight lines" + (if (fix != null) " from where you are" else "") + ". Roads will be longer. Saved on this phone, so it works offline.",
                "Vituo ${stops.size} · takriban ${Geo.dist(total, m.prefs.miles)} kwa mstari ulionyooka" + (if (fix != null) " kutoka ulipo" else "") + ". Barabara zitakuwa ndefu zaidi. Imehifadhiwa kwenye simu, kwa hiyo inafanya kazi bila mtandao."),
                style = MaterialTheme.typography.bodyMedium, color = C.muted)
            mapsLegs(stops, fix?.ll).forEach { (label, url) -> Btn(label, { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }, Modifier.fillMaxWidth().padding(top = 10.dp), icon = "compass") }
            legs.forEachIndexed { i, (p, d) ->
                Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.card).border(1.dp, C.line, RoundedCornerShape(16.dp)).clickable { open(p.id) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(BlueBrush), contentAlignment = Alignment.Center) { Text("${i + 1}", color = Color.White, style = MaterialTheme.typography.titleSmall) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.title, style = MaterialTheme.typography.titleSmall, color = C.text)
                        Text(p.area + (d?.let { " · " + t("${Geo.dist(it, m.prefs.miles)} from the last stop", "${Geo.dist(it, m.prefs.miles)} kutoka kituo cha mwisho") } ?: ""), style = MaterialTheme.typography.bodySmall, color = C.muted)
                    }
                    Icon(Ic.get("chev"), null, tint = C.muted)
                }
            }
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn(t("Change stops", "Badilisha vituo"), { choosing = true }, light = true, small = true)
                Btn(t("Clear route", "Futa njia"), { picked.clear(); save(); choosing = true }, light = true, small = true)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// --- monthly report -----------------------------------------------------------------------------

data class ReportData(val label: String, val visits: List<JSONObject>, val ids: Set<String>, val working: Int, val fixed: Int, val pins: Int, val newSites: Int, val openRepairs: Int, val due: Int, val byCountry: List<List<String>>)

@Composable
fun ReportScreen(m: AppModel, back: () -> Unit) {
    val s by m.snap.collectAsState()
    val act by m.activity.collectAsState()
    val snap = s ?: return
    val ctx = LocalContext.current
    var ym by remember { mutableStateOf(YearMonth.now()) }
    var ck by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { if (!m.loadActivity()) err = t("No signal: the report needs a connection the first time.", "Hakuna mtandao: ripoti inahitaji mtandao mara ya kwanza.") }
    val months = (0 until 13).map { YearMonth.now().minusMonths(it.toLong()) }
    val fmt = DateTimeFormatter.ofPattern("MMMM yyyy", if (org.rafikioutreach.pin.Lang.sw) Locale("sw") else Locale.UK)

    val inCountry = { id: String -> ck.isBlank() || snap.projects.firstOrNull { it.id == id }?.ck == ck }
    val from = ym.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(); val to = ym.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    val ev = (act ?: emptyList()).filter { e -> runCatching { Instant.parse(e.optString("at")).toEpochMilli() in from until to }.getOrDefault(false) && (e.optString("id").isBlank() || inCountry(e.optString("id"))) }
    val visits = ev.filter { it.optString("a") == "visit" }
    val latest = visits.groupBy { it.optString("id") }.mapValues { (_, v) -> v.maxBy { it.optString("at") } }
    val rep = ReportData(
        fmt.format(ym), visits, latest.keys, latest.values.count { it.optString("s") == "working" }, ev.count { it.optString("a") == "fixed" }, ev.count { it.optString("a") == "pin" }, ev.count { it.optString("a") == "new" },
        snap.repairs.keys.count { inCountry(it) }, snap.projects.count { it.gps != null && inCountry(it.id) && ageDays(snap, it) >= DUE_DAYS },
        COUNTRIES.filter { ck.isBlank() || it.key == ck }.mapNotNull { (k, n) ->
            val l = snap.projects.filter { it.ck == k }; if (l.isEmpty()) null else {
                val v = latest.filterKeys { id -> l.any { it.id == id } }
                listOf(n, "${l.size}", "${v.size}", "${v.values.count { it.optString("s") == "working" }}", "${v.values.count { it.optString("s") == "repair" }}", "${v.values.count { it.optString("s") == "notworking" }}", "${l.count { snap.repairs.containsKey(it.id) }}")
            }
        },
    )

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Picker(t("Month", "Mwezi"), ym.toString(), months.map { it.toString() to fmt.format(it) }, { ym = YearMonth.parse(it) }, Modifier.weight(1f))
            Picker(t("Country", "Nchi"), ck, listOf("" to t("All countries", "Nchi zote")) + COUNTRIES.toList(), { ck = it }, Modifier.weight(1f))
        }
        Msg(err, "warn")
        Card(Modifier.padding(top = 14.dp)) {
            Text(t("Field report · ", "Ripoti ya uwanjani · ") + rep.label, style = MaterialTheme.typography.titleLarge, color = C.ink)
            Text((COUNTRIES[ck] ?: t("All countries", "Nchi zote")) + " · RAFIKI Outreach · " + t("Prepared by ", "Imeandaliwa na ") + m.prefs.name, style = MaterialTheme.typography.bodySmall, color = C.muted)
            val pctW = if (rep.ids.isEmpty()) "—" else "${rep.working * 100 / rep.ids.size}%"
            listOf(
                listOf("${rep.visits.size}" to t("site visits", "ziara"), "${rep.ids.size}" to t("projects visited", "miradi iliyotembelewa")),
                listOf(pctW to t("of visited projects working", "ya miradi iliyotembelewa inafanya kazi"), "${rep.openRepairs}" to t("open repairs", "matengenezo yaliyo wazi")),
                listOf("${rep.fixed}" to t("repairs fixed", "matengenezo yaliyokamilika"), "${rep.newSites}" to t("new sites added", "maeneo mapya yaliyoongezwa")),
                listOf("${rep.pins}" to t("locations pinned", "mahali palipowekwa"), "${rep.due}" to t("due a visit (6+ months)", "zinasubiri ziara (miezi 6+)")),
            ).forEach { row ->
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (n, l) -> Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(C.tint).padding(12.dp)) { Text(n, style = MaterialTheme.typography.headlineMedium, color = C.ink); Text(l, style = MaterialTheme.typography.bodySmall, color = C.muted) } }
                }
            }
            H2(t("By country", "Kwa nchi"), Modifier.padding(top = 16.dp))
            val head = listOf(t("Country", "Nchi"), t("Projects", "Miradi"), t("Visited", "Zimetembelewa"), t("Working", "Zinafanya kazi"), t("Repair", "Matengenezo"), t("Not working", "Hazifanyi kazi"), t("Open repairs", "Matengenezo wazi"))
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                (listOf(head) + rep.byCountry).forEachIndexed { i, r ->
                    Row(Modifier.background(if (i == 0) C.blue2 else if (i % 2 == 0) C.tint else Color.Transparent).padding(vertical = 6.dp, horizontal = 4.dp)) {
                        r.forEachIndexed { j, c -> Text(c, style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (i == 0 || j == 0) FontWeight.Bold else FontWeight.Medium), color = if (i == 0) Color.White else C.text, modifier = Modifier.width(if (j == 0) 104.dp else 118.dp).padding(end = 6.dp), maxLines = 2) }
                    }
                }
            }
            H2(t("Visits this month", "Ziara za mwezi huu"), Modifier.padding(top = 16.dp))
            if (rep.visits.isEmpty()) Text(t("No visits recorded in the app this month.", "Hakuna ziara zilizorekodiwa kwenye programu mwezi huu."), color = C.muted, style = MaterialTheme.typography.bodyMedium)
            rep.visits.sortedBy { it.optString("at") }.forEach { e -> Text(whenText(e.optString("at")) + " · " + e.optString("t") + " · " + e.optString("by") + " · " + stateName(e.optString("s")), style = MaterialTheme.typography.bodySmall, color = C.text, modifier = Modifier.padding(top = 4.dp)) }
            Text(t("Only visits, repairs and pins made with the RAFIKI Pin app are counted.", "Ziara, matengenezo na mahali vilivyowekwa kwa programu ya RAFIKI Pin tu ndivyo vinahesabiwa."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 12.dp))
        }
        Btn(t("Share as PDF", "Shiriki kama PDF"), { sharePdf(ctx, rep, (COUNTRIES[ck] ?: t("All countries", "Nchi zote")), m.prefs.name) }, Modifier.fillMaxWidth().padding(top = 14.dp), icon = "upload")
        Spacer(Modifier.height(24.dp))
    }
}

/** Draws the report onto A4 pages and opens the share sheet (WhatsApp, e-mail, Drive…). */
private fun sharePdf(ctx: android.content.Context, r: ReportData, country: String, by: String) {
    val doc = PdfDocument(); var pageNo = 0
    val title = Paint().apply { isAntiAlias = true; textSize = 20f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); color = 0xFF04263F.toInt() }
    val h2 = Paint(title).apply { textSize = 13f; color = 0xFF076DB1.toInt() }
    val body = Paint().apply { isAntiAlias = true; textSize = 10f; color = 0xFF141618.toInt() }
    val muted = Paint(body).apply { color = 0xFF5A6066.toInt() }
    var page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, ++pageNo).create()); var c = page.canvas; var y = 56f
    fun line(text: String, p: Paint, gap: Float = 15f) {
        if (y > 800) { doc.finishPage(page); page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, ++pageNo).create()); c = page.canvas; y = 56f }
        c.drawText(text, 48f, y, p); y += gap
    }
    line(t("Field report · ", "Ripoti ya uwanjani · ") + r.label, title, 22f)
    line("$country · RAFIKI Outreach · " + t("Prepared by ", "Imeandaliwa na ") + by, muted, 26f)
    val pct = if (r.ids.isEmpty()) "—" else "${r.working * 100 / r.ids.size}%"
    listOf("${r.visits.size} " + t("site visits", "ziara"), "${r.ids.size} " + t("projects visited", "miradi iliyotembelewa"), "$pct " + t("of visited projects working", "ya miradi iliyotembelewa inafanya kazi"),
        "${r.openRepairs} " + t("open repairs", "matengenezo yaliyo wazi"), "${r.fixed} " + t("repairs fixed", "matengenezo yaliyokamilika"), "${r.newSites} " + t("new sites added", "maeneo mapya yaliyoongezwa"),
        "${r.pins} " + t("locations pinned", "mahali palipowekwa"), "${r.due} " + t("due a visit (6+ months)", "zinasubiri ziara (miezi 6+)")).forEach { line("•  $it", body) }
    y += 10; line(t("By country", "Kwa nchi"), h2, 18f)
    r.byCountry.forEach { row -> line(row[0] + ": " + t("${row[1]} projects, ${row[2]} visited, ${row[3]} working, ${row[4]} needed repair, ${row[5]} not working, ${row[6]} open repairs", "miradi ${row[1]}, ${row[2]} zimetembelewa, ${row[3]} zinafanya kazi, ${row[4]} zilihitaji matengenezo, ${row[5]} hazifanyi kazi, matengenezo ${row[6]} wazi"), body) }
    y += 10; line(t("Visits", "Ziara"), h2, 18f)
    if (r.visits.isEmpty()) line(t("No visits recorded in the app this month.", "Hakuna ziara zilizorekodiwa kwenye programu mwezi huu."), muted)
    r.visits.sortedBy { it.optString("at") }.forEach { e -> line(whenText(e.optString("at")) + "  " + e.optString("t").take(48) + "  ·  " + e.optString("by") + "  ·  " + stateName(e.optString("s")), body) }
    y += 14; line(t("Only visits, repairs and pins made with the RAFIKI Pin app are counted.", "Ziara, matengenezo na mahali vilivyowekwa kwa programu ya RAFIKI Pin tu ndivyo vinahesabiwa."), muted)
    doc.finishPage(page)
    val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
    val f = File(dir, "RAFIKI-field-report-" + r.label.replace(' ', '-') + ".pdf")
    f.outputStream().use { doc.writeTo(it) }; doc.close()
    val uri = FileProvider.getUriForFile(ctx, "org.rafikioutreach.pin.files", f)
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), f.name))
}

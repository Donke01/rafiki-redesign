package org.rafikioutreach.pin.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.Outcome
import org.rafikioutreach.pin.data.COUNTRIES
import org.rafikioutreach.pin.data.CostRow
import org.rafikioutreach.pin.data.CostView
import org.rafikioutreach.pin.data.Money
import org.rafikioutreach.pin.data.OutFile
import org.rafikioutreach.pin.data.PeopleServed
import org.rafikioutreach.pin.data.Project
import org.rafikioutreach.pin.data.Repair
import org.rafikioutreach.pin.data.Snapshot
import org.rafikioutreach.pin.data.reasonText
import org.rafikioutreach.pin.t
import java.io.File
import java.time.LocalDate

// Office money and reach in the native app: people served (Overview), Office › Costs, the second approval for
// big repairs (job card, tracker, Review) and the actual cost + receipt when parts are ordered or a repair is fixed.

private val FLAGS = mapOf("ke" to "🇰🇪", "tz" to "🇹🇿", "ug" to "🇺🇬", "mw" to "🇲🇼")
private val CC = mapOf("ke" to Color(0xFF0A78C2), "tz" to Color(0xFF1C9C5A), "ug" to Color(0xFFE39A1E), "mw" to Color(0xFF7A4FD0))
/** People-served groups: label, icon, hue. */
private fun typeInfo(k: String): Triple<String, String, String> = when (k) {
    "water" -> Triple(t("Water points", "Vituo vya maji"), "drop", "blue")
    "health" -> Triple(t("Health", "Afya"), "cross", "coral")
    "school" -> Triple(t("Schools", "Shule"), "school", "amber")
    "children" -> Triple(t("Children’s homes", "Makao ya watoto"), "house", "purple")
    "livelihoods" -> Triple(t("Livelihoods", "Riziki"), "leaf", "green")
    else -> Triple(t("Community", "Jamii"), "people", "teal")
}
private fun fmt(n: Long) = "%,d".format(java.util.Locale.US, n)

/** A number that counts up (or just shows, with reduced motion). */
@Composable
private fun CountUp(n: Long, style: androidx.compose.ui.text.TextStyle, color: Color, prefix: String = "", modifier: Modifier = Modifier) {
    val v = kotlin.math.round(rememberFill(n.toFloat(), 1000)).toLong()
    Text(prefix + fmt(v), style = style, color = color, modifier = modifier)
}

/** A bar that grows from empty. */
@Composable
private fun GrowBar(f: Float, color: Color, modifier: Modifier = Modifier) {
    val g = rememberFill(f.coerceIn(0f, 1f), 1100)
    Box(modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(99.dp)).background(C.line)) {
        if (g > 0.001f) Box(Modifier.fillMaxWidth(g).fillMaxHeight().clip(RoundedCornerShape(99.dp)).background(color))
    }
}

/** Office › Overview: how many people the projects serve today, the 12-month trend, by country and by type. */
@Composable
fun PeopleServedCard(p: PeopleServed, modifier: Modifier = Modifier) {
    val teal = C.hue("teal")
    Card(modifier.testTag("people_served"), pad = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile("people", teal.fg, teal.bg, 40.dp); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t("People served", "Watu wanaohudumiwa"), style = MaterialTheme.typography.titleMedium, color = C.ink)
                Text(t("Latest visit counts on ${p.measured} of ${p.counted} projects; the project records for the rest", "Hesabu za ziara za karibuni kwenye miradi ${p.measured} kati ya ${p.counted}; kumbukumbu za miradi kwa mingine"), style = MaterialTheme.typography.bodySmall, color = C.muted)
            }
        }
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CountUp(p.total, MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.ExtraBold), C.ink, modifier = Modifier.testTag("people_total"))
            if (p.growth > 0) {
                Spacer(Modifier.width(10.dp))
                Row(Modifier.clip(RoundedCornerShape(99.dp)).background(C.okBg).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ic.get("trend"), null, tint = C.ok, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(4.dp))
                    Text("+" + fmt(p.growth) + " " + t("in 12 months", "kwa miezi 12"), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold), color = C.ok)
                }
            }
        }
        Spark(p.trend.map { it.second }, teal.fg, Modifier.padding(top = 8.dp).fillMaxWidth().height(70.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(p.trend.firstOrNull()?.first?.let { runCatching { java.time.YearMonth.parse(it).format(java.time.format.DateTimeFormatter.ofPattern("MMM yy", java.util.Locale.UK)) }.getOrDefault(it) } ?: "", style = MaterialTheme.typography.labelSmall, color = C.muted)
            Text(t("12-month trend", "Mwenendo wa miezi 12"), style = MaterialTheme.typography.labelSmall, color = C.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f))
            Text(t("now", "sasa"), style = MaterialTheme.typography.labelSmall, color = C.muted)
        }
        Text(t("BY COUNTRY", "KWA NCHI"), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp, fontWeight = FontWeight.ExtraBold), color = C.muted, modifier = Modifier.padding(top = 14.dp))
        val cmax = (p.byCountry.values.maxOrNull() ?: 1L).coerceAtLeast(1L)
        COUNTRIES.forEach { (k, n) ->
            val v = p.byCountry[k] ?: return@forEach
            Column(Modifier.padding(top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(FLAGS[k] ?: "", fontSize = 15.sp); Spacer(Modifier.width(8.dp))
                    Text(n, style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.weight(1f)); Text(fmt(v), style = MaterialTheme.typography.titleSmall, color = C.ink)
                }
                GrowBar(v.toFloat() / cmax, CC[k] ?: C.blue, Modifier.padding(start = 26.dp, top = 4.dp))
            }
        }
        Text(t("BY TYPE", "KWA AINA"), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp, fontWeight = FontWeight.ExtraBold), color = C.muted, modifier = Modifier.padding(top = 16.dp))
        p.byType.entries.sortedByDescending { it.value }.forEach { (k, v) ->
            val (label, ic, hue) = typeInfo(k); val hu = C.hue(hue)
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTile(ic, hu.fg, hu.bg, 32.dp); Spacer(Modifier.width(10.dp))
                Text(label, style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.weight(1f)); Text(fmt(v), style = MaterialTheme.typography.titleSmall, color = C.ink)
            }
        }
    }
}

/** A small line chart that draws itself (null = month not known yet). */
@Composable
private fun Spark(vals: List<Long?>, color: Color, modifier: Modifier) {
    val known = vals.filterNotNull()
    if (known.size < 2) return
    val draw = rememberFill(1f, 1400)
    val bg = C.card
    Canvas(modifier.semantics { contentDescription = t("People served over the last 12 months", "Watu waliohudumiwa katika miezi 12 iliyopita") }) {
        val lo = known.min().toFloat(); val span = (known.max() - known.min()).toFloat().coerceAtLeast(1f)
        val pts = vals.mapIndexedNotNull { i, v -> v?.let { Offset(6f + i * (size.width - 12f) / (vals.size - 1), size.height - 8f - (it - lo) / span * (size.height - 22f)) } }
        val n = maxOf(2, kotlin.math.ceil(pts.size * draw).toInt()).coerceAtMost(pts.size)
        val line = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).take(n - 1).forEach { lineTo(it.x, it.y) } }
        val area = Path().apply { addPath(line); lineTo(pts[n - 1].x, size.height); lineTo(pts[0].x, size.height); close() }
        drawPath(area, color.copy(alpha = 0.13f))
        drawPath(line, color, style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (n == pts.size) { drawCircle(bg, 6.dp.toPx(), pts.last()); drawCircle(color, 4.5.dp.toPx(), pts.last()) }
    }
}

/** The second approval, in one line: on the Office job card and the repair tracker. */
@Composable
fun ApprovalNote(r: Repair, limit: Long, modifier: Modifier = Modifier) {
    val st = Money.state(r, limit); if (st == "none") return
    val a = r.approval; val est = Money.kes(Money.estOf(r)); val lim = Money.kes(limit)
    val (title, sub, look) = when (st) {
        "pending" -> Triple(t("Waiting for a second approval", "Inasubiri idhini ya pili") + " · " + t("asked by ", "imeombwa na ") + (a?.by ?: "—"),
            t("$est is above $lim, so another admin approves it before parts are ordered.", "$est ni zaidi ya $lim, kwa hiyo msimamizi mwingine aidhinishe kabla vipuri havijaagizwa."), "purple")
        "approved" -> Triple(t("Cost approved by ", "Gharama imeidhinishwa na ") + (a?.dec ?: "—"), est + (a?.note?.takeIf { it.isNotBlank() }?.let { " · “$it”" } ?: "") + " · " + t("asked by ", "imeombwa na ") + (a?.by ?: "—"), "green")
        "declined" -> Triple(t("Cost declined by ", "Gharama imekataliwa na ") + (a?.dec ?: "—"), (a?.note?.takeIf { it.isNotBlank() }?.let { "“$it” · " } ?: "") + t("Ask again with a new estimate or budget.", "Omba tena kwa makadirio au bajeti mpya."), "coral")
        else -> Triple(t("Needs a second approval before parts are ordered", "Inahitaji idhini ya pili kabla ya kuagiza vipuri"), t("$est is above $lim. Choosing “Parts ordered” asks another admin.", "$est ni zaidi ya $lim. Ukichagua “Vipuri vimeagizwa” msimamizi mwingine ataombwa."), "amber")
    }
    val hu = C.hue(look)
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(hu.bg).then(if (C.ink == Color.Black) Modifier.border(2.dp, Color.Black, RoundedCornerShape(14.dp)) else Modifier).padding(12.dp).testTag("approval_$st"), verticalAlignment = Alignment.Top) {
        Icon(Ic.get(when (st) { "pending" -> "hourglass"; "declined" -> "xc"; else -> "shield" }), null, tint = hu.fg, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
        Column { Text(title, style = MaterialTheme.typography.titleSmall, color = C.ink); Text(sub, style = MaterialTheme.typography.bodySmall, color = C.muted) }
    }
}

/** The receipt photo: one photo box (camera or gallery), kept on the phone until sent. max = 0 leaves out
 *  the extra "Add a photo" button: the box itself takes the one photo. */
@Composable
fun ReceiptPick(m: AppModel, shots: List<Shot>, onChange: (List<Shot>) -> Unit) =
    PhotoSlots(m, listOf(Slot("Receipt", t("Receipt photo", "Picha ya risiti"), t("Optional", "Si lazima"))), shots, onChange, max = 0)

/** What the Office job card adds: the approval line, the actual cost at this step and a receipt. */
class JobMoney { var actual by mutableStateOf(""); var shots by mutableStateOf(listOf<Shot>()) }

@Composable
fun JobMoneyFields(m: AppModel, r: Repair, limit: Long, st: JobMoney) {
    ApprovalNote(r, limit, Modifier.padding(top = 10.dp))
    Field(st.actual, { st.actual = it.filter { c -> c.isDigit() } }, t("Actual cost at this step (KES)", "Gharama halisi katika hatua hii (KES)"), number = true, placeholder = t("what was spent", "kilichotumika"))
    Text(t("Receipt (optional)", "Risiti (si lazima)"), style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp), color = C.ink, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
    ReceiptPick(m, st.shots) { st.shots = it }
}

/** After an Office job save: the message to show (held for approval, or the receipt), and sends the receipt. */
suspend fun jobMoneyDone(m: AppModel, x: org.rafikioutreach.pin.data.Reply, st: JobMoney, limit: Long): Pair<String, String>? {
    if (x.json.optString("held").isNotBlank()) return "warn" to (if (x.json.optBoolean("asked")) t("Saved. This repair is above ${Money.kes(limit)}, so parts are ordered after a second admin approves the cost. Asked ✓", "Imehifadhiwa. Ukarabati huu ni zaidi ya ${Money.kes(limit)}, kwa hiyo vipuri vitaagizwa baada ya msimamizi wa pili kuidhinisha. Imeombwa ✓")
        else t("Still waiting for a second admin to approve the cost.", "Bado inasubiri msimamizi wa pili kuidhinisha gharama."))
    val shot = st.shots.firstOrNull(); val uid = x.json.optString("updateId")
    st.actual = ""; st.shots = emptyList()
    if (shot != null && uid.isNotBlank() && !m.api.upload(uid, shot.file, "photo", "receipt").ok) return "warn" to t("Saved, but the receipt photo couldn’t be added.", "Imehifadhiwa, lakini picha ya risiti haikuongezwa.")
    return null
}

/** The money on the repair tracker: kept between "Parts ordered" and "Mark fixed". */
class TrackerMoney { var fixCost by mutableStateOf(""); var fixShots by mutableStateOf(listOf<Shot>())
    fun fill(b: JSONObject): JSONObject { Money.parse(fixCost)?.let { b.put("actual", it) }; if (fixShots.isNotEmpty()) b.put("receipt", true); return b }
    fun files(): List<OutFile> = fixShots.map { OutFile(it.file.path, "photo", "receipt") }
}

/** The repair tracker: the second approval, "Parts ordered" for the person on the job (or an admin), and the cost when fixed. */
@Composable
fun TrackerMoneyBox(m: AppModel, s: Snapshot, p: Project, r: Repair, money: TrackerMoney) {
    val st = Money.state(r, s.approveAbove)
    ApprovalNote(r, s.approveAbove, Modifier.padding(top = 10.dp))
    val mine = s.admin || p.id in s.jobs || r.assignee.trim().equals(m.prefs.name.trim(), ignoreCase = true)
    var open by remember(p.id) { mutableStateOf(false) }
    var cost by remember(p.id) { mutableStateOf("") }
    var what by remember(p.id) { mutableStateOf("") }
    var shots by remember(p.id) { mutableStateOf(listOf<Shot>()) }
    var msg by remember(p.id) { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberScope()
    if (mine && !Money.past(r.step, "parts") && st != "pending") {
        val ask = st == "needed" || st == "declined"
        if (open && !ask) {
            Field(cost, { cost = it.filter { c -> c.isDigit() } }, t("Actual cost (KES, optional)", "Gharama halisi (KES, si lazima)"), number = true, placeholder = t("what the parts cost", "bei ya vipuri"))
            Field(what, { what = it }, t("What was ordered? (optional)", "Nini kimeagizwa? (si lazima)"), placeholder = r.parts.ifBlank { t("e.g. 6 rising main pipes", "mf. mabomba 6") })
            Spacer(Modifier.height(8.dp)); ReceiptPick(m, shots) { shots = it }
        }
        Btn(if (ask) t("Parts ordered: ask for approval", "Vipuri vimeagizwa: omba idhini") else if (open) t("Save: parts ordered", "Hifadhi: vipuri vimeagizwa") else t("Parts ordered", "Vipuri vimeagizwa"), {
            if (!ask && !open) { open = true; return@Btn }
            busy = true
            scope.launch {
                val b = JSONObject().put("action", "parts").put("id", p.id)
                if (!ask) { Money.parse(cost)?.let { b.put("actual", it) }; if (what.isNotBlank()) b.put("what", what.trim()); if (shots.isNotEmpty()) b.put("receipt", true) }
                when (val o = m.submit(b, if (ask) emptyList() else shots.map { OutFile(it.file.path, "photo", "receipt") })) {
                    is Outcome.Sent -> {
                        val held = o.reply.optString("held").isNotBlank()
                        msg = if (held) "warn" to (if (o.reply.optBoolean("asked")) t("Sent for a second approval ✓ Parts can be ordered once another admin approves the cost.", "Imetumwa kwa idhini ya pili ✓ Vipuri vitaagizwa msimamizi mwingine akiidhinisha.") else t("Still waiting for a second approval.", "Bado inasubiri idhini ya pili."))
                            else "ok" to t("Parts ordered ✓", "Vipuri vimeagizwa ✓")
                        open = false; cost = ""; what = ""; shots = emptyList(); m.reload()
                    }
                    Outcome.Queued -> msg = "warn" to t("Saved on this phone. It will be sent when you have signal.", "Imehifadhiwa kwenye simu. Itatumwa ukipata mtandao.")
                    is Outcome.Failed -> msg = "bad" to o.text
                    Outcome.Locked -> {}
                }
                busy = false
            }
        }, Modifier.padding(top = 10.dp).fillMaxWidth().testTag("parts_btn"), icon = "box", light = true, enabled = !busy)
    }
    msg?.let { (tone, text) -> Msg(text, tone) }
    // the money spent, saved with "Mark fixed" (folded away until wanted)
    var showFix by remember(p.id) { mutableStateOf(false) }
    if (!showFix) Row(Modifier.padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).clickable { showFix = true }.padding(vertical = 8.dp, horizontal = 2.dp).testTag("fix_cost_open"), verticalAlignment = Alignment.CenterVertically) {
        Icon(Ic.get("coins"), null, tint = C.blue2, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text(t("Add what the repair cost (and a receipt)", "Ongeza gharama ya ukarabati (na risiti)"), style = MaterialTheme.typography.titleSmall, color = C.blue2)
    } else {
        Field(money.fixCost, { money.fixCost = it.filter { c -> c.isDigit() } }, t("Actual cost when fixed (KES, optional)", "Gharama halisi ikirekebishwa (KES, si lazima)"), number = true, placeholder = t("what the repair cost", "gharama ya ukarabati"))
        Spacer(Modifier.height(8.dp)); ReceiptPick(m, money.fixShots) { money.fixShots = it }
    }
}

/** "Repairs: KES N in the last 12 months (3 repairs)" on a project. */
@Composable
fun ProjectCostLine(m: AppModel, p: Project) {
    var c by remember(p.id) { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(p.id) { val x = m.api.last(p.id); if (x.ok) c = x.json.optJSONObject("costs") }
    val j = c ?: return
    val sum = j.optLong("sum"); val n = j.optInt("n"); val last = j.optJSONArray("last")?.optJSONObject(0)
    val am = C.hue("amber")
    Row(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.card).border(if (C.ink == Color.Black) 2.dp else 1.dp, C.line, RoundedCornerShape(18.dp)).padding(12.dp).testTag("project_costs"), verticalAlignment = Alignment.CenterVertically) {
        IconTile("coins", am.fg, am.bg, 40.dp); Spacer(Modifier.width(12.dp))
        Column {
            Text(if (n > 0) t("Repairs: ${Money.kes(sum)} in the last 12 months ($n ${if (n == 1) "repair" else "repairs"})", "Ukarabati: ${Money.kes(sum)} katika miezi 12 iliyopita (matengenezo $n)")
                else t("Repairs: nothing spent in the last 12 months", "Ukarabati: hakuna kilichotumika katika miezi 12 iliyopita"), style = MaterialTheme.typography.titleSmall, color = C.ink)
            if (last != null) Text(t("Latest: ", "Karibuni: ") + listOf(Money.kes(last.optLong("actual")), last.optString("what"), last.optString("by"), whenText(last.optString("at"))).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = C.muted)
        }
    }
}

/** Office › Review: a cost waiting for the second approval. Returns whether this person may approve it. */
@Composable
fun CostReviewDetail(item: JSONObject, me: String, limit: Long): Boolean {
    val d = item.optJSONObject("data") ?: JSONObject()
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
        Text(Money.kes(d.optLong("est")), style = MaterialTheme.typography.titleLarge, color = C.ink); Spacer(Modifier.width(8.dp))
        Text(t("estimate · above ", "makadirio · zaidi ya ") + Money.kes(limit), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(bottom = 3.dp))
    }
    if (d.optString("parts").isNotBlank()) Text(t("Parts: ", "Vipuri: ") + d.optString("parts"), style = MaterialTheme.typography.bodyMedium, color = C.text)
    if (d.optString("note").isNotBlank()) Text("“" + d.optString("note") + "”", style = MaterialTheme.typography.bodyMedium, color = C.text)
    Text(t("Asked before ordering parts. The person who asked can’t approve it.", "Imeombwa kabla ya kuagiza vipuri. Aliyeomba hawezi kuidhinisha."), style = MaterialTheme.typography.bodySmall, color = C.muted)
    val can = Money.canDecide(item.optString("by"), me)
    if (!can) Msg(t("You asked for this, so another admin approves it.", "Wewe uliomba hili, kwa hiyo msimamizi mwingine ataidhinisha."), "warn")
    return can
}

// --- Office › Costs -------------------------------------------------------------------------
fun shareCsv(ctx: Context, name: String, text: String) {
    val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
    val f = File(dir, name); f.writeText("﻿" + text)
    val uri = FileProvider.getUriForFile(ctx, "org.rafikioutreach.pin.files", f)
    runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), name)) }
}
private fun csvOf(rows: List<List<Any?>>): String = rows.joinToString("\r\n") { r -> r.joinToString(",") { v -> val x = (v ?: "").toString(); if (x.any { it == ',' || it == '"' || it == '\n' }) "\"" + x.replace("\"", "\"\"") + "\"" else x } }
/** Every cost entry, newest first. */
fun costsCsv(v: CostView, projects: List<Project>): String = csvOf(listOf(listOf("Date", "Project ID", "Project", "Country", "Repair reported", "Step", "What", "Estimate (KES)", "Actual (KES)", "By", "Receipt")) +
    v.rows.map { r -> val p = projects.firstOrNull { it.id == r.id }; listOf(r.at.take(10), r.id, p?.title ?: r.title, p?.country ?: "", r.rid.take(10), when (r.step) { "fixed" -> "Fixed"; "parts" -> "Parts ordered"; else -> r.step }, r.what, r.est, r.actual, r.by, if (r.receipt) "yes" else "") })
/** Costs and people served in one summary sheet. */
fun summaryCsv(v: CostView, people: PeopleServed?): String {
    val l = mutableListOf<List<Any?>>(listOf("Section", "Item", "Value", "Repairs"))
    l += listOf("Repair spending", "This month", v.month, ""); l += listOf("Repair spending", "This year (${v.yearKey})", v.year, "")
    l += listOf("Repair spending", "Last 12 months", v.sum12, v.repairs); l += listOf("Repair spending", "Average per repair (last 12 months)", v.avg, "")
    COUNTRIES.forEach { (k, n) -> l += listOf("Spent this year by country", n, v.byCountry[k] ?: 0, "") }
    v.top.forEachIndexed { i, p -> l += listOf("Top 10 water points (last 12 months)", "${i + 1}. ${p.title}" + (COUNTRIES[p.ck]?.let { " ($it)" } ?: ""), p.sum, p.n) }
    l += listOf("Second approval", "Above (KES)", v.approveAbove, "")
    if (people != null && people.total > 0) {
        l += listOf("People served", "Total", people.total, "")
        people.byCountry.forEach { (k, n) -> l += listOf("People served by country", COUNTRIES[k] ?: k, n, "") }
        people.byType.forEach { (k, n) -> l += listOf("People served by type", k, n, "") }
        people.trend.forEach { (mo, n) -> if (n != null) l += listOf("People served by month", mo, n, "") }
    }
    return csvOf(l)
}

@Composable
private fun MoneyTile(modifier: Modifier, icon: String, hue: String, n: Long, label: String, sub: String = "") {
    val hu = C.hue(hue)
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(C.card).border(if (C.ink == Color.Black) 2.dp else 1.dp, C.line, RoundedCornerShape(20.dp)).padding(14.dp)) {
        IconTile(icon, hu.fg, hu.bg, 40.dp)
        CountUp(n, MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold), C.ink, "KES ", Modifier.padding(top = 8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = C.muted)
        if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = C.muted)
    }
}

@Composable
fun CostsSection(m: AppModel, s: Snapshot, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberScope()
    var v by remember { mutableStateOf<CostView?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tick) { val x = m.api.adminGet("costs"); if (x.ok) { v = CostView.from(x.json); err = null } else err = reasonText(x.reason) }
    val cv = v
    if (cv == null) { Text(err ?: t("Loading…", "Inapakia…"), color = if (err != null) C.bad else C.muted); return }
    val month = LocalDate.now().month.getDisplayName(java.time.format.TextStyle.FULL, if (org.rafikioutreach.pin.Lang.sw) java.util.Locale("sw") else java.util.Locale.UK)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MoneyTile(Modifier.weight(1f).rise(0), "coins", "amber", cv.month, t("spent on repairs in $month", "zilizotumika kwa ukarabati $month"))
        MoneyTile(Modifier.weight(1f).rise(1), "cal", "blue", cv.year, t("spent this year (${cv.yearKey})", "zilizotumika mwaka huu (${cv.yearKey})"))
    }
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MoneyTile(Modifier.weight(1f).rise(2), "wrench", "teal", cv.avg, t("average per repair", "wastani kwa kila ukarabati"), t("${cv.repairs} repairs in the last 12 months", "matengenezo ${cv.repairs} katika miezi 12"))
        MoneyTile(Modifier.weight(1f).rise(3), "trend", "purple", cv.sum12, t("spent in the last 12 months", "zilizotumika katika miezi 12"))
    }
    Card(Modifier.padding(top = 12.dp).testTag("costs_top"), pad = 16.dp) {
        Text(t("Most expensive water points to keep running", "Vituo vya maji vinavyogharimu zaidi kuendesha"), style = MaterialTheme.typography.titleMedium, color = C.ink)
        Text(t("Top 10 by money actually spent on repairs, last 12 months.", "10 bora kwa pesa halisi zilizotumika kwa ukarabati, miezi 12 iliyopita."), style = MaterialTheme.typography.bodySmall, color = C.muted)
        if (cv.top.isEmpty()) EmptyDrop(t("No repair costs recorded yet. Enter the actual cost when parts are ordered or a repair is fixed.", "Bado hakuna gharama za ukarabati. Andika gharama halisi vipuri vikiagizwa au ukarabati ukikamilika."))
        val tmax = (cv.top.firstOrNull()?.sum ?: 1L).coerceAtLeast(1L); val coral = C.hue("coral"); val amber = C.hue("amber")
        cv.top.forEachIndexed { i, tp ->
            Row(Modifier.padding(top = 12.dp).rise(i, step = 45).clickable { open(tp.id) }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(if (i == 0) coral.fg else amber.bg), contentAlignment = Alignment.Center) { Text("${i + 1}", color = if (i == 0) Color.White else amber.fg, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row { Text(tp.title.ifBlank { tp.id }, style = MaterialTheme.typography.titleSmall, color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)); Text(Money.kes(tp.sum), style = MaterialTheme.typography.titleSmall, color = C.ink) }
                    Text((COUNTRIES[tp.ck]?.let { "$it · " } ?: "") + t("${tp.n} ${if (tp.n == 1) "repair" else "repairs"}", "matengenezo ${tp.n}"), style = MaterialTheme.typography.bodySmall, color = C.muted)
                    GrowBar(tp.sum.toFloat() / tmax, coral.fg, Modifier.padding(top = 4.dp))
                }
            }
        }
    }
    Card(Modifier.padding(top = 12.dp), pad = 16.dp) {
        Text(t("By country, this year", "Kwa nchi, mwaka huu"), style = MaterialTheme.typography.titleMedium, color = C.ink)
        val cmax = (cv.byCountry.values.maxOrNull() ?: 1L).coerceAtLeast(1L)
        COUNTRIES.forEach { (k, n) ->
            val amt = cv.byCountry[k] ?: 0L
            Column(Modifier.padding(top = 10.dp)) {
                Row { Text((FLAGS[k] ?: "") + "  " + n, style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.weight(1f)); Text(Money.kes(amt), style = MaterialTheme.typography.titleSmall, color = C.ink) }
                GrowBar(amt.toFloat() / cmax, CC[k] ?: C.blue, Modifier.padding(top = 4.dp))
            }
        }
    }
    // the setting: second approval above KES N
    Card(Modifier.padding(top = 12.dp).testTag("approve_setting"), pad = 16.dp) {
        var lim by remember(cv.approveAbove) { mutableStateOf(cv.approveAbove.toString()) }
        var lm by remember { mutableStateOf<Pair<String, String>?>(null) }
        var saved by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(saved) { if (saved != null) { kotlinx.coroutines.delay(2600); saved = null } }
        Text(t("Second approval", "Idhini ya pili"), style = MaterialTheme.typography.titleMedium, color = C.ink)
        Text(t("Repairs estimated above this need a second admin to approve the cost before parts are ordered. The person who asks can’t approve their own.", "Ukarabati unaokadiriwa zaidi ya kiasi hiki unahitaji msimamizi wa pili kuidhinisha kabla ya kuagiza vipuri. Aliyeomba hawezi kuidhinisha mwenyewe."), style = MaterialTheme.typography.bodySmall, color = C.muted)
        Field(lim, { lim = it.filter { c -> c.isDigit() } }, t("Second approval above (KES)", "Idhini ya pili zaidi ya (KES)"), number = true)
        Btn(t("Save", "Hifadhi"), {
            scope.launch {
                val x = m.api.admin(JSONObject().put("action", "settings").put("approveAbove", lim))
                lm = if (x.ok) "ok" to t("Saved ✓ Second approval above ", "Imehifadhiwa ✓ Idhini ya pili zaidi ya ") + Money.kes(x.json.optLong("approveAbove")) else "bad" to reasonText(x.reason)
                if (x.ok) { saved = t("Saved", "Imehifadhiwa"); m.reload(); tick++ }
            }
        }, Modifier.padding(top = 10.dp).testTag("approve_save"), small = true, icon = "check", enabled = Money.parse(lim) != null, saved = saved)
        lm?.let { (tone, text) -> Msg(text, tone) }
    }
    Card(Modifier.padding(top = 12.dp), pad = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("Latest spending", "Matumizi ya karibuni"), style = MaterialTheme.typography.titleMedium, color = C.ink, modifier = Modifier.weight(1f))
            Btn(t("CSV", "CSV"), { shareCsv(ctx, "rafiki-repair-costs-${LocalDate.now()}.csv", costsCsv(cv, s.projects)) }, small = true, light = true, icon = "upload")
        }
        if (cv.rows.isEmpty()) Text(t("Nothing yet.", "Bado hakuna kitu."), color = C.muted, modifier = Modifier.padding(top = 8.dp))
        cv.rows.take(12).forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider(color = C.line)
            val hu = C.hue(if (r.step == "fixed") "green" else "amber")
            Row(Modifier.fillMaxWidth().clickable { open(r.id) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTile(if (r.step == "fixed") "check" else "box", hu.fg, hu.bg, 36.dp); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.title.ifBlank { s.projects.firstOrNull { it.id == r.id }?.title ?: r.id }, style = MaterialTheme.typography.titleSmall, color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(r.what, r.by, whenText(r.at)).filter { it.isNotBlank() }.joinToString(" · ") + if (r.receipt) " · " + t("receipt", "risiti") else "", style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp)); Text(Money.kes(r.actual), style = MaterialTheme.typography.titleSmall, color = C.ink)
            }
        }
    }
}

/** Office › Export: the costs, and the costs-and-people summary. */
@Composable
fun CostExports(m: AppModel, s: Snapshot, onMsg: (String?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberScope()
    fun withCosts(go: (CostView) -> Unit) = scope.launch {
        onMsg(t("Gathering repair costs…", "Inakusanya gharama za ukarabati…"))
        val x = m.api.adminGet("costs"); if (!x.ok) { onMsg(reasonText(x.reason)); return@launch }
        onMsg(null); go(CostView.from(x.json))
    }
    Btn(t("Repair costs (every entry, last 2 years)", "Gharama za ukarabati (kila kiingilio, miaka 2)"), { withCosts { shareCsv(ctx, "rafiki-repair-costs-${LocalDate.now()}.csv", costsCsv(it, s.projects)) } }, Modifier.fillMaxWidth().testTag("x_costs"), light = true, icon = "coins")
    Btn(t("Costs and people served: summary", "Gharama na watu wanaohudumiwa: muhtasari"), { withCosts { shareCsv(ctx, "rafiki-costs-and-people-${LocalDate.now()}.csv", summaryCsv(it, s.people)) } }, Modifier.fillMaxWidth().testTag("x_costsum"), light = true, icon = "coins")
}

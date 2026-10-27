package org.rafikioutreach.pin.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.COUNTRIES
import org.rafikioutreach.pin.data.Geo
import org.rafikioutreach.pin.data.Money
import org.rafikioutreach.pin.data.Repair
import org.rafikioutreach.pin.data.Snapshot
import org.rafikioutreach.pin.data.reasonText
import org.rafikioutreach.pin.t
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

// Office › Repairs and Office › Review, the same look as Office › Community: a head with the numbers that matter,
// filter chips with counts, calm cards (one title, one status pill, two short lines) that open a sheet holding every
// action, and the extras folded away. The server calls are the ones the old cards made: "job" and "review".

private val STEPS = listOf("reported", "assigned", "parts", "fixed")
internal fun rrStepName(k: String) = when (k) { "assigned" -> t("Assigned", "Imegawiwa"); "parts" -> t("Parts ordered", "Vipuri vimeagizwa"); "fixed" -> t("Fixed", "Imerekebishwa"); else -> t("Reported", "Imeripotiwa") }
private fun urgOrder(r: Repair) = mapOf("urgent" to 0, "month" to 1, "watch" to 2)[r.urgency] ?: 3
/** Waiting for, or needing, the second approval (not yet asked, asked, or declined). */
internal fun rrNeedsApproval(r: Repair, limit: Long) = Money.state(r, limit).let { it == "needed" || it == "pending" || it == "declined" }
/** The repair filters: all, urgent, notworking, unassigned, approval, parts. */
internal fun rrMatches(f: String, r: Repair, limit: Long) = when (f) {
    "urgent" -> r.urgency == "urgent"; "notworking" -> r.state == "notworking"; "unassigned" -> r.assignee.isBlank()
    "approval" -> rrNeedsApproval(r, limit); "parts" -> r.step == "parts"; else -> true
}
internal fun rrCondition(r: Repair) = (when (r.urgency) { "urgent" -> t("Urgent", "Haraka") + " · "; "month" -> t("This month", "Mwezi huu") + " · "; else -> "" }) +
    (if (r.state == "notworking") t("Not working", "Haifanyi kazi") else t("Needs repair", "Inahitaji matengenezo"))
private fun approvalWord(r: Repair, limit: Long) = when (Money.state(r, limit)) {
    "pending" -> t("waiting for approval", "inasubiri idhini"); "needed" -> t("needs approval", "inahitaji idhini")
    "declined" -> t("cost declined", "gharama imekataliwa"); "approved" -> t("cost approved", "gharama imeidhinishwa"); else -> ""
}
/** A review item's kind: filter key, sheet title, card label, icon, hue. */
internal fun rrKind(k: String): List<String> = when (k) {
    "cost" -> listOf("cost", t("Second approval: repair cost", "Idhini ya pili: gharama ya matengenezo"), t("Repair cost", "Gharama ya matengenezo"), "shield", "purple")
    "new" -> listOf("new", t("New site", "Eneo jipya"), t("New site", "Eneo jipya"), "plus", "green")
    "move" -> listOf("loc", t("Location moved over 500 m", "Mahali pamehamishwa zaidi ya m 500"), t("Location moved", "Mahali pamehamishwa"), "pin", "blue")
    "request" -> listOf("loc", t("Change to a confirmed location", "Badiliko la mahali palipothibitishwa"), t("Confirmed location", "Mahali palipothibitishwa"), "lock", "amber")
    "story" -> listOf("story", t("Story moment", "Hadithi"), t("Story", "Hadithi"), "spark", "coral")
    "story-draft" -> listOf("story", t("Story draft ready", "Rasimu ya hadithi iko tayari"), t("Story draft", "Rasimu ya hadithi"), "chat", "purple")
    else -> listOf(k, k, k, "inbox", "slate")
}
/** "5 h", "6 d" (how long the oldest item has waited). */
internal fun rrAge(iso: String, now: Instant = Instant.now()): String = runCatching {
    val h = Duration.between(Instant.parse(iso), now).toHours()
    if (h < 1) t("< 1 h", "< saa 1") else if (h < 24) t("$h h", "saa $h") else t("${h / 24} d", "siku ${h / 24}")
}.getOrDefault("—")
/** Pieces joined by " · "; a piece with a colour is bold in that colour. */
private fun rrLine(parts: List<Pair<String, Color?>>) = androidx.compose.ui.text.buildAnnotatedString {
    parts.filter { it.first.isNotBlank() }.forEachIndexed { i, (txt, col) ->
        if (i > 0) append(" · ")
        if (col != null) { pushStyle(androidx.compose.ui.text.SpanStyle(color = col, fontWeight = FontWeight.Bold)); append(txt); pop() } else append(txt)
    }
}
private fun rrLocale() = if (org.rafikioutreach.pin.Lang.sw) java.util.Locale("sw") else java.util.Locale.US
private fun dayText(d: String) = runCatching { LocalDate.parse(d).format(java.time.format.DateTimeFormatter.ofPattern("d MMM", rrLocale())) }.getOrDefault(d)
/** "8 min", "5 h", "Yesterday", "3 Oct" (in the app's language). */
private fun rrAgo(iso: String): String = runCatching {
    val at = Instant.parse(iso); val m = Duration.between(at, Instant.now()).toMinutes()
    when {
        m < 1 -> t("now", "sasa"); m < 60 -> t("$m min", "dak $m"); m < 24 * 60 -> t("${m / 60} h", "saa ${m / 60}"); m < 48 * 60 -> t("Yesterday", "Jana")
        else -> java.time.format.DateTimeFormatter.ofPattern("d MMM", rrLocale()).withZone(java.time.ZoneId.systemDefault()).format(at)
    }
}.getOrDefault("")
private fun JSONArray.objs() = (0 until length()).map { getJSONObject(it) }

@Composable
private fun rrTone(tone: String): Pair<Color, Color> = when (tone) { "bad" -> C.bad to C.badBg; "warn" -> C.warn to C.warnBg; "ok" -> C.ok to C.okBg; else -> C.hue(tone).let { it.fg to it.bg } }

// --- Office › Repairs ----------------------------------------------------------------------------------------

@Composable
fun RepairsSection(m: AppModel, s: Snapshot, open: (String) -> Unit) {
    var people by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(Unit) { val a = m.api.adminGet("team").json.optJSONArray("team") ?: JSONArray(); people = a.objs().map { it.optString("name") }.filter { it.isNotBlank() }.sorted() }
    var filter by remember { mutableStateOf("all") }
    var shown by remember { mutableIntStateOf(30) }
    var picked by remember { mutableStateOf<String?>(null) }
    val lim = s.approveAbove
    val ids = s.repairs.keys.sortedWith(compareBy({ urgOrder(s.repairs.getValue(it)) }, { s.repairs.getValue(it).at }))
    fun n(f: String) = ids.count { rrMatches(f, s.repairs.getValue(it), lim) }

    Card(Modifier.testTag("rr_head")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val am = C.hue("amber"); IconTile("wrench", am.fg, am.bg, 44.dp); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t("Repair jobs", "Kazi za matengenezo"), style = MaterialTheme.typography.titleMedium, color = C.ink)
                Text(t("Most urgent first. Tap a job to move it on, assign it or mark it fixed.", "Za haraka kwanza. Gusa kazi kuisogeza, kuigawa au kuiweka imerekebishwa."), style = MaterialTheme.typography.bodySmall, color = C.muted)
            }
        }
        Row(Modifier.padding(top = 14.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RrStat(Modifier.weight(1f).testTag("rr_n_all"), "${ids.size}", t("open", "zilizo wazi"), C.ink, filter == "all") { filter = "all"; shown = 30 }
            RrStat(Modifier.weight(1f).testTag("rr_n_urgent"), "${n("urgent")}", t("urgent", "za haraka"), if (n("urgent") > 0) C.bad else C.muted, filter == "urgent") { filter = "urgent"; shown = 30 }
            RrStat(Modifier.weight(1f).testTag("rr_n_approval"), "${n("approval")}", t("need approval", "zinahitaji idhini"), if (n("approval") > 0) C.hue("purple").fg else C.muted, filter == "approval") { filter = "approval"; shown = 30 }
        }
    }
    val (rrScroll, rrSpot) = rememberKeepInView(filter)
    if (ids.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rrScroll).padding(top = 16.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("all" to t("All", "Zote"), "urgent" to t("Urgent", "Za haraka"), "notworking" to t("Not working", "Hazifanyi kazi"), "unassigned" to t("Not assigned", "Hazijagawiwa"),
            "approval" to t("Needs approval", "Zinahitaji idhini"), "parts" to t("Parts ordered", "Vipuri vimeagizwa"))
            .forEach { (k, l) -> Box(rrSpot(k)) { Chip("$l · ${n(k)}", null, filter == k, { filter = k; shown = 30 }, tag = "rr_filter_$k", hue = "blue") } }
    }
    val list = ids.filter { rrMatches(filter, s.repairs.getValue(it), lim) }
    if (ids.isEmpty()) EmptyDrop(t("No open repairs.", "Hakuna matengenezo yaliyo wazi."))
    else if (list.isEmpty()) EmptyDrop(t("No repairs here. Try another filter.", "Hakuna matengenezo hapa. Jaribu kichujio kingine."))
    list.take(shown).forEachIndexed { i, id -> RrJobCard(s, id, Modifier.padding(top = 10.dp).rise(minOf(i, 6))) { picked = id } }
    if (list.size > shown) Btn(t("Show more", "Onyesha zaidi") + " (" + (list.size - shown) + ")", { shown += 30 }, Modifier.padding(top = 12.dp).fillMaxWidth().testTag("rr_more"), light = true, small = true)

    Card(Modifier.padding(top = 18.dp), pad = 4.dp) {
        RrFold("rr_how", "info", "blue", t("How repair jobs work", "Jinsi kazi za matengenezo zinavyoenda"), t("Second approval above ", "Idhini ya pili zaidi ya ") + Money.kes(lim)) {
            listOf(
                t("Changes leave a note on the project in monday.com, and the person assigned sees the job in their app.", "Mabadiliko yanaacha maelezo kwenye mradi katika monday.com, na aliyepewa anaona kazi kwenye programu yake."),
                t("Repairs estimated above ${Money.kes(lim)} need a second admin to approve the cost before parts are ordered. The person who asks can’t approve their own. The amount is set in Costs.", "Matengenezo yanayokadiriwa zaidi ya ${Money.kes(lim)} yanahitaji msimamizi wa pili kuidhinisha gharama kabla ya kuagiza vipuri. Aliyeomba hawezi kuidhinisha mwenyewe. Kiasi kinawekwa kwenye Gharama."),
                t("Add the actual cost and a receipt photo when parts are ordered or the repair is fixed: they add up in Costs.", "Ongeza gharama halisi na picha ya risiti vipuri vikiagizwa au matengenezo yakikamilika: zinajumlishwa kwenye Gharama."),
                t("“Mark fixed” closes the job and takes it off this list.", "“Weka imerekebishwa” inafunga kazi na kuiondoa kwenye orodha hii."),
            ).forEachIndexed { i, line -> Row(Modifier.padding(top = 6.dp)) { Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.width(22.dp)); Text(line, style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.weight(1f)) } }
        }
    }
    picked?.let { id -> RrJobSheet(m, s, id, people, close = { picked = null }, open = { picked = null; open(id) }) }
}

@Composable
private fun RrStat(modifier: Modifier, n: String, label: String, color: Color, on: Boolean, onClick: (() -> Unit)?) {
    val h = rememberHaptics()
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.fillMaxHeight().clip(shape).background(if (on) C.tint else C.bg).border(if (on) 2.dp else if (!C.glassy) 1.5.dp else 0.dp, if (on) C.blue else if (!C.glassy) Color.Black else Color.Transparent, shape)
        .then(if (onClick != null) Modifier.clickable(role = Role.Tab) { h.tap(); onClick() } else Modifier).heightIn(min = 64.dp).padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(n, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold), color = color, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = C.muted, textAlign = TextAlign.Center)
    }
}

/** A clean card: an icon, the title, one pill, two short lines, when; tap for the sheet. */
@Composable
private fun RrCard(icon: String, tone: String, title: String, pill: String, line1: androidx.compose.ui.text.AnnotatedString, line2: androidx.compose.ui.text.AnnotatedString, whenText: String, tag: String, modifier: Modifier, quote: Boolean = false, onTap: () -> Unit) {
    val (fg, bg) = rrTone(tone)
    val h = rememberHaptics()
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val shape = RoundedCornerShape(20.dp)
    Row(modifier.press(src).fillMaxWidth().clip(shape).background(if (C.glassy) C.glass2 else C.card).border(if (C.glassy) 1.dp else 2.dp, if (C.glassy) C.glassEdge else C.line, shape)
        .clickable(interactionSource = src, indication = androidx.compose.material3.ripple(), role = Role.Button) { h.tap(); onTap() }.padding(14.dp).testTag(tag), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(bg).then(if (!C.glassy) Modifier.border(1.5.dp, fg, RoundedCornerShape(13.dp)) else Modifier), contentAlignment = Alignment.Center) {
            Icon(Ic.get(icon), null, tint = fg, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = C.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(whenText, style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 1, modifier = Modifier.padding(top = 1.dp))
                Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(18.dp))
            }
            Row(Modifier.padding(top = 5.dp).clip(RoundedCornerShape(12.dp)).background(bg).then(if (!C.glassy) Modifier.border(1.5.dp, fg, RoundedCornerShape(12.dp)) else Modifier).padding(start = 8.dp, end = 10.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(fg)); Spacer(Modifier.width(6.dp))
                Text(pill, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.ExtraBold), color = fg)
            }
            if (line1.isNotBlank()) Text(line1, style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 5.dp))
            if (line2.isNotBlank()) Text(line2, style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = if (quote) 1 else 2, overflow = TextOverflow.Ellipsis, fontStyle = if (quote) FontStyle.Italic else null, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun RrJobCard(s: Snapshot, id: String, modifier: Modifier, onTap: () -> Unit) {
    val r = s.repairs[id] ?: return
    val p = s.projects.firstOrNull { it.id == id }
    val late = r.due.isNotBlank() && r.due < LocalDate.now().toString()
    val est = Money.estOf(r)
    val bad = C.bad; val purple = C.hue("purple").fg
    val l1 = rrLine(listOf(rrStepName(r.step) to null, r.assignee.ifBlank { t("not assigned", "hajapewa mtu") } to null, (if (r.due.isNotBlank()) t("due ", "mwisho ") + dayText(r.due) else "") to (if (late) bad else null)))
    val l2 = rrLine(listOf((p?.country ?: "") to null, (if (est > 0) Money.kes(est) else "") to null, approvalWord(r, s.approveAbove) to purple))
    RrCard("wrench", if (r.state == "notworking" || r.urgency == "urgent") "bad" else "warn", p?.title ?: r.title.ifBlank { id }, rrCondition(r), l1, l2,
        rrAgo(r.at), "rr_job_$id", modifier, onTap = onTap)
}

@Composable
private fun RrSheetHead(icon: String, tone: String, title: String, sub: String, close: () -> Unit) {
    val (fg, bg) = rrTone(tone)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(bg).then(if (!C.glassy) Modifier.border(1.5.dp, fg, RoundedCornerShape(16.dp)) else Modifier), contentAlignment = Alignment.Center) {
            Icon(Ic.get(icon), null, tint = fg, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(sub, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold), color = fg)
            Text(title, style = MaterialTheme.typography.titleLarge, color = C.ink)
        }
        Box(Modifier.size(44.dp).clip(CircleShape).background(C.bg).then(if (!C.glassy) Modifier.border(2.dp, Color.Black, CircleShape) else Modifier).clickable(role = Role.Button, onClick = close).testTag("rr_close"), contentAlignment = Alignment.Center) {
            Icon(Ic.get("x"), t("Close", "Funga"), tint = C.ink, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun RrFact(label: String, value: String, long: Boolean = false) {
    if (value.isBlank()) return
    if (long) Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = C.muted)
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = C.ink, modifier = Modifier.padding(top = 2.dp))
    } else Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.width(118.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = C.ink, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
    HorizontalDivider(color = C.line)
}

@Composable
private fun RrLabel(text: String, modifier: Modifier = Modifier) = Text(text.uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp, fontWeight = FontWeight.ExtraBold), color = C.muted, modifier = modifier.padding(top = 18.dp, bottom = 8.dp))

/** Ask before something that can't be undone. */
@Composable
private fun RrAsk(q: String, yes: String, tag: String, no: () -> Unit, go: () -> Unit) {
    Column(Modifier.padding(top = 10.dp).rise(0).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.hue("slate").bg).then(if (!C.glassy) Modifier.border(2.dp, Color.Black, RoundedCornerShape(16.dp)) else Modifier).padding(14.dp).testTag(tag)) {
        Text(q, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = C.ink)
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RrLight(t("Cancel", "Ghairi"), no, Modifier.weight(1f).testTag(tag + "_no"), small = true)
            Btn(yes, go, Modifier.weight(1f).testTag(tag + "_yes"), small = true, ok = true)
        }
    }
}

/** One job: the second approval, what was reported, the step, the person, the due date, the money, and Save / Mark fixed. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RrJobSheet(m: AppModel, s: Snapshot, id: String, names: List<String>, close: () -> Unit, open: () -> Unit) {
    val first = remember(id) { s.repairs[id] }
    val r = s.repairs[id] ?: first ?: return
    val p = s.projects.firstOrNull { it.id == id }
    val title = p?.title ?: r.title.ifBlank { id }
    val scope = rememberScope()
    val state = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun hide() { scope.launch { state.hide() }.invokeOnCompletion { close() } }
    var step by remember(id) { mutableStateOf(r.step) }
    var who by remember(id) { mutableStateOf(r.assignee) }
    var due by remember(id) { mutableStateOf(r.due) }
    var budget by remember(id) { mutableStateOf(r.budget?.toString() ?: "") }
    var note by remember(id) { mutableStateOf("") }
    var dnote by remember(id) { mutableStateOf("") }
    var decided by remember(id) { mutableStateOf<String?>(null) }
    var ask by remember(id) { mutableStateOf(false) }
    var busy by remember(id) { mutableStateOf(false) }
    var msg by remember(id) { mutableStateOf<Pair<String, String>?>(null) }
    var green by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(green) { if (green != null) { kotlinx.coroutines.delay(2600); green = null } }
    val money = remember(id) { JobMoney() }
    val a = r.approval
    val st = Money.state(r, s.approveAbove)
    val own = a != null && !Money.canDecide(a.by, m.prefs.name)
    fun save(stp: String) {
        busy = true; msg = "" to t("Saving…", "Inahifadhi…")
        scope.launch {
            val x = m.api.admin(JSONObject().put("action", "job").put("id", id).put("step", stp).put("assignee", who).put("due", due).put("budget", budget).put("note", note)
                .apply { Money.parse(money.actual)?.let { put("actual", it) }; if (money.shots.isNotEmpty()) put("receipt", true); if (note.isNotBlank()) put("what", note) })
            busy = false
            val held = x.ok && x.json.optString("held").isNotBlank()
            msg = if (x.ok) (jobMoneyDone(m, x, money, s.approveAbove) ?: ("ok" to if (stp == "fixed") t("Marked fixed ✓", "Imewekwa imerekebishwa ✓") else t("Saved ✓", "Imehifadhiwa ✓"))) else "bad" to reasonText(x.reason)
            if (held) step = x.json.optJSONObject("job")?.optString("step")?.ifBlank { null } ?: r.step else if (x.ok) step = stp
            if (x.ok) { note = ""; green = if (stp == "fixed" && !held) t("Fixed", "Imerekebishwa") else t("Saved", "Imehifadhiwa"); m.reload() }
            if (x.ok && stp == "fixed" && !held) { kotlinx.coroutines.delay(900); hide() }
        }
    }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = close, sheetState = state, containerColor = C.card, modifier = Modifier.testTag("rr_job_sheet")) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            RrSheetHead("wrench", if (r.state == "notworking" || r.urgency == "urgent") "bad" else "warn", title, rrCondition(r), ::hide)
            ApprovalNote(r, s.approveAbove, Modifier.padding(top = 14.dp))
            // the second approval, here as well as in Review: someone else's request can be approved or declined
            if (st == "pending" && a != null && a.rid.isNotBlank() && decided == null) {
                if (own) Msg(t("You asked for this, so another admin has to approve it.", "Wewe uliomba hili, kwa hiyo msimamizi mwingine lazima aidhinishe."), "warn")
                else Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.hue("purple").bg).then(if (!C.glassy) Modifier.border(2.dp, Color.Black, RoundedCornerShape(16.dp)) else Modifier).padding(horizontal = 14.dp).padding(bottom = 14.dp).testTag("rr_cost_dec")) {
                    Field(dnote, { dnote = it }, t("Note (optional)", "Maelezo (si lazima)"), placeholder = t("e.g. quote checked with the driller", "mf. bei imehakikishwa na mchimbaji"))
                    fun decide(dec: String) {
                        busy = true; msg = "" to t("Saving…", "Inahifadhi…")
                        scope.launch {
                            val x = m.api.admin(JSONObject().put("action", "review").put("rid", a.rid).put("decision", dec).put("reason", dnote))
                            busy = false
                            if (x.ok) { decided = dec; msg = "ok" to if (dec == "approve") t("Cost approved ✓ Parts can be ordered.", "Gharama imeidhinishwa ✓ Vipuri vinaweza kuagizwa.") else t("Cost declined ✓", "Gharama imekataliwa ✓"); m.reload() }
                            else msg = "bad" to if (x.reason == "own") t("You asked for this, so another admin has to approve it.", "Wewe uliomba hili, kwa hiyo msimamizi mwingine lazima aidhinishe.") else reasonText(x.reason)
                        }
                    }
                    Btn(t("Approve the cost", "Idhinisha gharama"), { decide("approve") }, Modifier.padding(top = 12.dp).fillMaxWidth().testTag("rr_cost_approve"), icon = "check", ok = true, enabled = !busy)
                    RrLight(t("Decline", "Kataa"), { decide("reject") }, Modifier.padding(top = 8.dp).fillMaxWidth().testTag("rr_cost_decline"), enabled = !busy)
                }
            }
            Column(Modifier.padding(top = 10.dp)) {
                PersonFact(t("Reported by", "Imeripotiwa na"), r.by.ifBlank { "—" }, sub = whenText(r.at), tag = "rr_reported_by")
                RrFact(t("Country", "Nchi"), p?.country ?: "")
                RrFact(t("Problems", "Matatizo"), r.problems.joinToString(", "), long = true)
                RrFact(t("Their note", "Maelezo yao"), r.note.takeIf { it.isNotBlank() }?.let { "“$it”" } ?: "", long = true)
                RrFact(t("Parts", "Vipuri"), r.parts, long = true)
                RrFact(t("Field estimate", "Makadirio ya uwanjani"), r.cost?.let { Money.kes(it) } ?: "")
            }
            RrLabel(t("Job step", "Hatua ya kazi"))
            val si = STEPS.indexOf(step)
            STEPS.chunked(2).forEachIndexed { ri, pair ->
                Row(Modifier.padding(top = if (ri == 0) 0.dp else 8.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { k -> val i = STEPS.indexOf(k); RrStepBtn(i + 1, rrStepName(k), step == k, i <= si, Modifier.weight(1f).fillMaxHeight().testTag("rr_step_$k")) { step = k } }
                }
            }
            Picker(t("Assigned to", "Amepewa"), who, listOf("" to t("Not assigned", "Hajapewa mtu")) + (if (r.assignee.isNotBlank() && r.assignee !in names) listOf(r.assignee) else emptyList<String>()).plus(names).map { it to it }, { who = it })
            // the person it's assigned to: a tap opens their contact sheet (Contact.kt)
            if (who.isNotBlank()) PersonChip(who, Modifier.padding(top = 4.dp), tag = "rr_assignee")
            DateField(t("Due", "Tarehe ya mwisho"), due, { due = it })
            Field(note, { note = it }, t("Note (optional)", "Maelezo (si lazima)"), placeholder = t("e.g. parts bought in Eldoret", "mf. vipuri vilinunuliwa Eldoret"))
            Column(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(if (C.glassy) 1.dp else 2.dp, if (C.glassy) C.line else Color.Black, RoundedCornerShape(16.dp))) {
                RrFold("rr_money", "coins", "amber", t("Budget, cost and receipt", "Bajeti, gharama na risiti"),
                    r.budget?.let { t("Budget ", "Bajeti ") + Money.kes(it) } ?: t("Add what was spent and a receipt", "Ongeza kilichotumika na risiti")) {
                    Field(budget, { budget = it.filter { c -> c.isDigit() } }, t("Approved budget (KES)", "Bajeti iliyoidhinishwa (KES)"), number = true, placeholder = r.cost?.toString() ?: "")
                    Field(money.actual, { money.actual = it.filter { c -> c.isDigit() } }, t("Actual cost at this step (KES)", "Gharama halisi katika hatua hii (KES)"), number = true, placeholder = t("what was spent", "kilichotumika"))
                    RrLabel(t("Receipt (optional)", "Risiti (si lazima)"))
                    ReceiptPick(m, money.shots) { money.shots = it }
                }
            }
            Btn(t("Save", "Hifadhi"), { if (step == "fixed") ask = true else save(step) }, Modifier.padding(top = 18.dp).fillMaxWidth().testTag("rr_save"), icon = "check", enabled = !busy, saved = green)
            Btn(t("Mark fixed", "Weka imerekebishwa"), { ask = true }, Modifier.padding(top = 8.dp).fillMaxWidth().testTag("rr_fixed"), icon = "okc", ok = true, enabled = !busy)
            if (ask) RrAsk(t("Mark $title as fixed? The job closes and leaves this list.", "Weka $title kama imerekebishwa? Kazi inafungwa na kuondoka kwenye orodha hii."), t("Mark fixed", "Weka imerekebishwa"), "rr_ask", { ask = false }) { ask = false; save("fixed") }
            RrLight(t("Open project", "Fungua mradi"), { scope.launch { state.hide() }.invokeOnCompletion { open() } }, Modifier.padding(top = 8.dp).fillMaxWidth().testTag("rr_open"), icon = "arrow")
            msg?.let { (tone, text) -> Box(Modifier.testTag("rr_msg")) { Msg(text, tone) } }
        }
    }
}

@Composable
private fun RrStepBtn(n: Int, label: String, on: Boolean, past: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val h = rememberHaptics()
    val shape = RoundedCornerShape(14.dp)
    Row(modifier.clip(shape).background(if (on) C.tint else C.card).border(if (on) 2.dp else if (C.glassy) 1.dp else 2.dp, if (on) (if (C.glassy) C.blue else Color.Black) else if (C.glassy) C.line else Color.Black, shape)
        .clickable(role = Role.RadioButton) { h.tap(); onClick() }.heightIn(min = 50.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(if (on) C.blue else if (past) C.okBg else C.bg), contentAlignment = Alignment.Center) {
            Text("$n", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = if (on) Color.White else if (past) C.ok else C.muted)
        }
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp), color = C.ink, modifier = Modifier.weight(1f))
    }
}

/** A secondary button in a sheet: a clear outline on the white sheet, the same size as the main ones. */
@Composable
private fun RrLight(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: String? = null, enabled: Boolean = true, small: Boolean = false) {
    val shape = RoundedCornerShape(999.dp)
    Row(modifier.clip(shape).background(if (C.dark) C.bg else Color(0xFFF4F7FA)).border(if (C.glassy) 1.5.dp else 2.dp, if (C.glassy) C.edge else Color.Black, shape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = if (small) 44.dp else 52.dp).padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        val fg = if (enabled) C.ink else C.muted
        if (icon != null) { Icon(Ic.get(icon), null, tint = fg, modifier = Modifier.size(if (small) 18.dp else 20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, color = fg, style = if (small) MaterialTheme.typography.labelMedium.copy(fontSize = 14.sp) else MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/** A row that opens and closes with a tap: a title with a one-line summary under it, and a chevron that turns. */
@Composable
private fun RrFold(tag: String, icon: String, hue: String, title: String, summary: String, content: @Composable () -> Unit) {
    var openNow by remember { mutableStateOf(false) }
    val h = rememberHaptics()
    BackStep(openNow) { openNow = false }
    val turn by animateFloatAsState(if (openNow) 90f else 0f, tween(if (Motion.on) 220 else 0), label = "chev")
    val hu = C.hue(hue)
    Column(Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { h.tap(); openNow = !openNow }.heightIn(min = 60.dp).padding(horizontal = 14.dp, vertical = 8.dp).testTag(tag + "_toggle"),
            verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, hu.fg, hu.bg, 36.dp); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = C.ink)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.width(6.dp))
            Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(18.dp).rotate(turn))
        }
        AnimatedVisibility(openNow, enter = if (Motion.on) expandVertically(tween(240)) + fadeIn(tween(240)) else fadeIn(tween(0)), exit = if (Motion.on) shrinkVertically(tween(200)) + fadeOut(tween(200)) else fadeOut(tween(0))) {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp).testTag(tag + "_body")) { content() }
        }
    }
}

// --- Office › Review -----------------------------------------------------------------------------------------

@Composable
fun ReviewSection(m: AppModel, open: (String) -> Unit) {
    var items by remember { mutableStateOf<List<JSONObject>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf("all") }
    var shown by remember { mutableIntStateOf(30) }
    var picked by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(tick) { val x = m.api.adminGet("review"); val a = x.json.optJSONArray("items"); if (a != null) { items = a.objs(); err = null } else err = reasonText(x.reason) }
    val all = items
    if (all == null) { if (err != null) Msg(err, "bad") else Text(t("Loading…", "Inapakia…"), color = C.muted); return }
    val openItems = all.filter { it.optString("status") == "open" }
    val done = all.filter { it.optString("status") != "open" }.take(15)
    fun n(f: String) = openItems.count { f == "all" || rrKind(it.optString("kind"))[0] == f }
    val oldest = openItems.minByOrNull { it.optString("at") }?.optString("at")

    Card(Modifier.testTag("rr_head")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile("inbox", C.blue, C.tint, 44.dp); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t("Waiting for review", "Zinasubiri kuidhinishwa"), style = MaterialTheme.typography.titleMedium, color = C.ink)
                Text(t("New sites, moved locations, stories and big repair costs wait here for an admin.", "Maeneo mapya, mahali palipohamishwa, hadithi na gharama kubwa za matengenezo zinasubiri msimamizi hapa."), style = MaterialTheme.typography.bodySmall, color = C.muted)
            }
        }
        Row(Modifier.padding(top = 14.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RrStat(Modifier.weight(1f).testTag("rr_n_all"), "${openItems.size}", t("waiting", "zinasubiri"), C.ink, filter == "all") { filter = "all"; shown = 30 }
            RrStat(Modifier.weight(1f).testTag("rr_n_cost"), "${n("cost")}", t("repair costs", "gharama za matengenezo"), if (n("cost") > 0) C.hue("purple").fg else C.muted, filter == "cost") { filter = "cost"; shown = 30 }
            RrStat(Modifier.weight(1f).testTag("rr_n_age"), oldest?.let { rrAge(it) } ?: "—", t("oldest waiting", "iliyosubiri zaidi"), C.muted, false, null)
        }
    }
    val (rvScroll, rvSpot) = rememberKeepInView(filter)
    if (openItems.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rvScroll).padding(top = 16.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("all" to t("All", "Zote"), "cost" to t("Costs", "Gharama"), "new" to t("New sites", "Maeneo mapya"), "loc" to t("Locations", "Mahali"), "story" to t("Stories", "Hadithi"))
            .forEach { (k, l) -> Box(rvSpot(k)) { Chip("$l · ${n(k)}", null, filter == k, { filter = k; shown = 30 }, tag = "rr_kind_$k", hue = "blue") } }
    }
    val list = openItems.filter { filter == "all" || rrKind(it.optString("kind"))[0] == filter }
    if (openItems.isEmpty()) EmptyDrop(t("Nothing waiting for review.", "Hakuna kinachosubiri kuidhinishwa."))
    else if (list.isEmpty()) EmptyDrop(t("Nothing here. Try another filter.", "Hakuna kitu hapa. Jaribu kichujio kingine."))
    list.take(shown).forEachIndexed { i, x ->
        // a story with its draft ready (feat-mea, me-brief §9): it's approved on the Stories tab, so it opens there
        if (x.optString("kind") == "story-draft") StoryDraftItem(x) { id -> StoryNav.open = id; open(id) }
        else RrReviewCard(m, x, Modifier.padding(top = 10.dp).rise(minOf(i, 6))) { picked = x }
    }
    if (list.size > shown) Btn(t("Show more", "Onyesha zaidi") + " (" + (list.size - shown) + ")", { shown += 30 }, Modifier.padding(top = 12.dp).fillMaxWidth().testTag("rr_more"), light = true, small = true)

    Card(Modifier.padding(top = 18.dp), pad = 4.dp) {
        RrFold("rr_done", "check", "green", t("Recently decided", "Vilivyoamuliwa karibuni"),
            if (done.isEmpty()) t("None yet", "Bado hakuna") else t("${done.size} lately", "${done.size} karibuni") + (done.first().optString("doneBy").takeIf { it.isNotBlank() }?.let { " · " + t("last by ", "mwisho na ") + it } ?: "")) {
            if (done.isEmpty()) Text(t("Nothing decided yet.", "Bado hakuna kilichoamuliwa."), style = MaterialTheme.typography.bodySmall, color = C.muted)
            done.forEachIndexed { i, x ->
                if (i > 0) HorizontalDivider(color = C.line)
                val ok = x.optString("status") == "approved"
                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(if (ok) C.okBg else C.badBg), contentAlignment = Alignment.Center) { Icon(Ic.get(if (ok) "check" else "x"), null, tint = if (ok) C.ok else C.bad, modifier = Modifier.size(15.dp)) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(x.optString("t").ifBlank { x.optString("id") }, style = MaterialTheme.typography.titleSmall, color = C.ink)
                        Text(listOf(rrKind(x.optString("kind"))[2], if (ok) t("approved", "imeidhinishwa") else t("turned down", "imekataliwa"), x.optString("doneBy"), rrAgo(x.optString("done"))).filter { it.isNotBlank() }.joinToString(" · ") +
                            (x.optString("reason").takeIf { it.isNotBlank() }?.let { "\n“$it”" } ?: ""), style = MaterialTheme.typography.bodySmall, color = C.muted)
                    }
                }
            }
        }
    }
    picked?.let { x -> RrReviewSheet(m, x, close = { picked = null }, open = { picked = null; open(x.optString("id")) }, decided = { tick++; m.reload() }) }
}

private fun rrGist(x: JSONObject, miles: Boolean): String {
    val d = x.optJSONObject("data") ?: JSONObject()
    return when (x.optString("kind")) {
        "cost" -> Money.kes(d.optLong("est")) + (d.optString("parts").takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        "new" -> listOf(d.optString("type"), d.optString("village"), COUNTRIES[d.optString("ck")] ?: "").filter { it.isNotBlank() }.joinToString(" · ")
        "story" -> d.optString("quote").takeIf { it.isNotBlank() }?.let { "“$it”" } ?: ""
        else -> if (d.has("dist")) t("Moved ", "Imehamishwa ") + Geo.dist(d.optDouble("dist"), miles) else d.optString("to")
    }
}

@Composable
private fun RrReviewCard(m: AppModel, x: JSONObject, modifier: Modifier, onTap: () -> Unit) {
    val k = rrKind(x.optString("kind"))
    val own = x.optString("kind") == "cost" && !Money.canDecide(x.optString("by"), m.prefs.name)
    val purple = C.hue("purple").fg
    RrCard(k[3], k[4], x.optString("t").ifBlank { x.optString("id") }, k[2],
        if (own) rrLine(listOf(t("You asked: another admin approves", "Uliomba wewe: msimamizi mwingine aidhinishe") to purple)) else rrLine(listOf(t("by ", "na ") + x.optString("by").ifBlank { "—" } to null)),
        rrLine(listOf(rrGist(x, m.prefs.miles) to null)), rrAgo(x.optString("at")), "rr_rev_" + x.optString("rid"), modifier, quote = x.optString("kind") == "story", onTap = onTap)
}

/** One item waiting for review: what it is, and approve or turn it down (turning down asks first, with a reason). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RrReviewSheet(m: AppModel, x: JSONObject, close: () -> Unit, open: () -> Unit, decided: () -> Unit) {
    val scope = rememberScope()
    val state = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun hide(then: () -> Unit = close) { scope.launch { state.hide() }.invokeOnCompletion { then() } }
    val kind = x.optString("kind"); val k = rrKind(kind); val cost = kind == "cost"
    val d = x.optJSONObject("data") ?: JSONObject()
    val own = cost && !Money.canDecide(x.optString("by"), m.prefs.name)
    val limit = m.snap.value?.approveAbove ?: Money.APPROVE_DEFAULT
    var reason by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf(cost) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<Pair<String, String>?>(null) }
    val verb = when (kind) {
        "cost" -> t("Approve the cost", "Idhinisha gharama") to t("Decline", "Kataa"); "move" -> t("Keep the new location", "Baki na mahali papya") to t("Put the old one back", "Rudisha mahali pa zamani")
        "request" -> t("Approve the change", "Idhinisha badiliko") to t("Keep the confirmed location", "Baki na mahali palipothibitishwa"); "story" -> t("Approve for the website", "Idhinisha kwa tovuti") to t("Don’t use", "Usitumie")
        else -> t("Approve", "Idhinisha") to t("Reject", "Kataa")
    }
    fun decide(dec: String) {
        if (dec == "reject" && !asking) { asking = true; return }
        busy = true; msg = "" to t("Saving…", "Inahifadhi…")
        scope.launch {
            val r = m.api.admin(JSONObject().put("action", "review").put("rid", x.optString("rid")).put("decision", dec).put("reason", reason))
            busy = false
            if (r.ok) { done = true; msg = "ok" to if (dec == "approve") t("Approved ✓", "Imeidhinishwa ✓") else t("Turned down ✓", "Imekataliwa ✓"); kotlinx.coroutines.delay(700); decided(); hide() }
            else msg = "bad" to if (r.reason == "own") t("You asked for this, so another admin has to approve it.", "Wewe uliomba hili, kwa hiyo msimamizi mwingine lazima aidhinishe.") else reasonText(r.reason)
        }
    }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = close, sheetState = state, containerColor = C.card, modifier = Modifier.testTag("rr_rev_sheet")) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            RrSheetHead(k[3], k[4], x.optString("t").ifBlank { x.optString("id") }, k[1]) { hide() }
            val who = (if (cost) t("Asked by", "Imeombwa na") else t("Sent by", "Imetumwa na")) to x.optString("by").ifBlank { "—" }
            when (kind) {
                "cost" -> {
                    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.Bottom) {
                        Text(Money.kes(d.optLong("est")), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold), color = C.ink); Spacer(Modifier.width(8.dp))
                        Text(t("estimate · above ", "makadirio · zaidi ya ") + Money.kes(limit), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(bottom = 4.dp))
                    }
                    Column(Modifier.padding(top = 6.dp)) { RrFact(t("Parts", "Vipuri"), d.optString("parts"), long = true); RrFact(t("Their note", "Maelezo yao"), d.optString("note").takeIf { it.isNotBlank() }?.let { "“$it”" } ?: "", long = true); PersonFact(who.first, who.second, sub = whenText(x.optString("at")), tag = "rr_by") }
                    Row(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.hue("slate").bg).padding(12.dp), verticalAlignment = Alignment.Top) {
                        Icon(Ic.get("info"), null, tint = C.muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Text(t("Asked before ordering parts. The person who asked can’t approve it.", "Imeombwa kabla ya kuagiza vipuri. Aliyeomba hawezi kuidhinisha."), style = MaterialTheme.typography.bodyMedium, color = C.ink)
                    }
                }
                "story" -> {
                    val co = C.hue("coral")
                    Column(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp)).background(co.bg)) {
                        Row(Modifier.height(IntrinsicSize.Min)) {
                            Box(Modifier.width(4.dp).fillMaxHeight().background(co.fg))
                            Column(Modifier.padding(12.dp)) {
                                Text("“" + d.optString("quote") + "”", style = MaterialTheme.typography.bodyLarge, color = C.ink, modifier = Modifier.testTag("rr_quote"))
                                if (d.optString("who").isNotBlank()) Text("— " + d.optString("who"), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                    Column(Modifier.padding(top = 6.dp)) { PersonFact(who.first, who.second, sub = whenText(x.optString("at")), tag = "rr_by"); RrFact(t("Consent", "Ridhaa"), t("Given, to quote and photograph", "Imetolewa, kunukuu na kupiga picha")) }
                }
                "new" -> Column(Modifier.padding(top = 10.dp)) {
                    RrFact(t("Type", "Aina"), d.optString("type")); RrFact(t("Village", "Kijiji"), d.optString("village")); RrFact(t("Country", "Nchi"), COUNTRIES[d.optString("ck")] ?: "")
                    RrFact(t("Location", "Mahali"), d.optString("gps")); RrFact(t("Their note", "Maelezo yao"), d.optString("note").takeIf { it.isNotBlank() }?.let { "“$it”" } ?: "", long = true); PersonFact(who.first, who.second, sub = whenText(x.optString("at")), tag = "rr_by")
                }
                else -> Column(Modifier.padding(top = 10.dp)) {
                    RrFact(t("From", "Kutoka"), d.optString("from").ifBlank { t("none", "hakuna") }); RrFact(t("To", "Hadi"), d.optString("to"))
                    RrFact(t("Distance", "Umbali"), if (d.has("dist")) Geo.dist(d.optDouble("dist"), m.prefs.miles) else ""); RrFact(t("How", "Jinsi"), d.optString("how"), long = true); PersonFact(who.first, who.second, sub = whenText(x.optString("at")), tag = "rr_by")
                }
            }
            if (own) Msg(t("You asked for this, so another admin approves it.", "Wewe uliomba hili, kwa hiyo msimamizi mwingine ataidhinisha."), "warn")
            AnimatedVisibility(asking, enter = if (Motion.on) expandVertically(tween(240)) + fadeIn(tween(240)) else fadeIn(tween(0))) {
                Field(reason, { reason = it }, if (cost) t("Note (optional)", "Maelezo (si lazima)") else t("Reason (optional)", "Sababu (si lazima)"), Modifier.testTag("rr_reason"),
                    placeholder = if (cost) t("e.g. quote checked with the driller", "mf. bei imehakikishwa na mchimbaji") else t("e.g. wrong school", "mf. shule isiyo sahihi"))
            }
            Btn(verb.first, { decide("approve") }, Modifier.padding(top = 18.dp).fillMaxWidth().testTag("rr_approve"), icon = "check", ok = true, enabled = !own && !busy && !done)
            RrLight(if (asking && !cost) t("Confirm: ", "Thibitisha: ") + verb.second else verb.second, { decide("reject") }, Modifier.padding(top = 8.dp).fillMaxWidth().testTag("rr_reject"), enabled = !own && !busy && !done)
            if (x.optString("id").isNotBlank()) RrLight(t("Open project", "Fungua mradi"), { hide(open) }, Modifier.padding(top = 8.dp).fillMaxWidth().testTag("rr_open"), icon = "arrow")
            msg?.let { (tone, text) -> Box(Modifier.testTag("rr_msg")) { Msg(text, tone) } }
        }
    }
}

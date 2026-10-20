package org.rafikioutreach.pin.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.Snapshot
import org.rafikioutreach.pin.data.stateName
import org.rafikioutreach.pin.t
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Someone on the team, from /api/pin?team=1. */
data class Mate(val name: String, val home: String, val phone: String, val lastSeen: String?)

private fun same(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)
private fun day(iso: String): LocalDate? = runCatching { Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
private val WORK = setOf("pin", "visit", "fixed", "new")

/** "3 min ago", "2 h ago", "yesterday", "4 days ago". */
fun ago(iso: String?): String {
    val at = iso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return t("never", "bado")
    val min = ChronoUnit.MINUTES.between(at, Instant.now())
    return when {
        min < 2 -> t("just now", "sasa hivi")
        min < 60 -> t("$min min ago", "dakika $min zilizopita")
        min < 24 * 60 -> t("${min / 60} h ago", "saa ${min / 60} zilizopita")
        min < 48 * 60 -> t("yesterday", "jana")
        else -> t("${min / 1440} days ago", "siku ${min / 1440} zilizopita")
    }
}

/** My month: pins, visits and fixes this month, the last six weeks, and how many days in a row. */
@Composable
fun MyMonth(m: AppModel, act: List<JSONObject>?) {
    val mine = act?.filter { same(it.optString("by"), m.prefs.name) && it.optString("a") in WORK }
    Card(Modifier.padding(top = 14.dp).testTag("my_month")) {
        H2(t("My month", "Mwezi wangu"))
        if (mine == null) { Text(t("Loading…", "Inapakia…"), color = C.muted, style = MaterialTheme.typography.bodyMedium); return@Card }
        val today = LocalDate.now(); val start = today.withDayOfMonth(1)
        val month = mine.filter { day(it.optString("at"))?.let { d -> !d.isBefore(start) } == true }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Triple("pin", t("Pins", "Mahali"), "pin"), Triple("visit", t("Visits", "Ziara"), "clip"), Triple("fixed", t("Fixed", "Zimerekebishwa"), "wrench")).forEachIndexed { i, (k, l, ic) ->
                val n = month.count { it.optString("a") == k || (k == "pin" && it.optString("a") == "new") }
                val hu = C.hue(if (k == "fixed") "repairs" else if (k == "visit") "near" else "pin")
                val shown = kotlin.math.round(rememberFill(n.toFloat(), 900)).toInt()
                Column(Modifier.weight(1f).rise(i + 1).clip(RoundedCornerShape(16.dp)).background(hu.bg).padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Ic.get(ic), null, tint = hu.fg, modifier = Modifier.size(18.dp))
                    Text("$shown", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold), color = hu.fg, modifier = Modifier.testTag("my_$k"))
                    Text(l, style = MaterialTheme.typography.labelSmall, color = C.ink)
                }
            }
        }
        // the last six weeks, oldest first
        val weeks = (5 downTo 0).map { w -> val from = today.minusDays((w * 7L) + 6); val to = today.minusDays(w * 7L); mine.count { day(it.optString("at"))?.let { d -> !d.isBefore(from) && !d.isAfter(to) } == true } }
        val top = maxOf(1, weeks.max())
        Text(t("Last 6 weeks", "Wiki 6 zilizopita"), style = MaterialTheme.typography.labelMedium, color = C.muted, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            weeks.forEachIndexed { i, n ->
                val f = rememberFill(n.toFloat() / top, 800 + i * 80)
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                    if (n > 0) Text("$n", style = MaterialTheme.typography.labelSmall, color = C.muted)
                    Box(Modifier.fillMaxWidth().fillMaxHeight(maxOf(0.06f, f * 0.8f)).clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)).background(if (i == 5) C.blue else C.blue.copy(alpha = 0.35f)))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(t("6 wk ago", "Wiki 6"), style = MaterialTheme.typography.labelSmall, color = C.muted, modifier = Modifier.weight(1f))
            Text(t("This week", "Wiki hii"), style = MaterialTheme.typography.labelSmall, color = C.muted)
        }
        // days in a row
        val days = mine.mapNotNull { day(it.optString("at")) }.toSet()
        var d = if (today in days) today else today.minusDays(1); var streak = 0
        while (d in days) { streak++; d = d.minusDays(1) }
        Row(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.hue("me").bg).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🔥", fontSize = 18.sp); Spacer(Modifier.width(10.dp))
            Text(when (streak) { 0 -> t("Pin or visit today to start a streak.", "Weka mahali au tembelea leo kuanza mfululizo."); 1 -> t("1 day in a row", "Siku 1 mfululizo"); else -> t("$streak days in a row", "Siku $streak mfululizo") },
                style = MaterialTheme.typography.titleSmall, color = C.hue("me").fg, modifier = Modifier.testTag("my_streak"))
        }
    }
}

/** Visits and repair jobs given to me, overdue first. */
@Composable
fun MyWork(snap: Snapshot?, open: (String) -> Unit) {
    snap ?: return
    val today = LocalDate.now().toString()
    val visits = snap.assigned.map { a -> Triple(a, a.date, a.date < today) }.sortedBy { it.second }
    val jobs = snap.jobs.mapNotNull { id -> snap.projects.firstOrNull { it.id == id }?.let { p -> p to snap.repairs[id] } }
    if (visits.isEmpty() && jobs.isEmpty()) return
    Card(Modifier.padding(top = 14.dp).testTag("my_work")) {
        H2(t("My work", "Kazi yangu"))
        visits.sortedByDescending { it.third }.forEach { (a, date, late) ->
            WorkRow("cal", "due", a.title.ifBlank { snap.projects.firstOrNull { it.id == a.id }?.title ?: a.id },
                t("Visit", "Ziara") + (if (a.note.isNotBlank()) " · " + a.note else "") + " · " + t("from ", "kutoka ") + a.by,
                if (late) t("Overdue", "Imechelewa") else if (date == today) t("Today", "Leo") else date.drop(5), if (late) "bad" else if (date == today) "ok" else "") { open(a.id) }
        }
        jobs.forEach { (p, r) ->
            WorkRow("wrench", "repairs", p.title, t("Repair job", "Kazi ya matengenezo") + (r?.due?.takeIf { it.isNotBlank() }?.let { " · " + t("due ", "mwisho ") + it.drop(5) } ?: ""),
                if (r?.due?.isNotBlank() == true && r.due < today) t("Overdue", "Imechelewa") else t("Job", "Kazi"), if (r?.due?.isNotBlank() == true && r.due < today) "bad" else "warn") { open(p.id) }
        }
    }
}

@Composable
private fun WorkRow(icon: String, hue: String, title: String, sub: String, tag: String, tone: String, onClick: () -> Unit) {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(Modifier.padding(top = 8.dp).press(src).fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(interactionSource = src, indication = androidx.compose.material3.ripple(), onClick = onClick).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val hu = C.hue(hue)
        IconTile(icon, hu.fg, hu.bg)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        when (tone) { "bad" -> Tag(tag, C.badBg, C.bad); "ok" -> Tag(tag, C.okBg, C.ok); "warn" -> Tag(tag, C.warnBg, C.warn); else -> Tag(tag, C.tint, C.deep) }
    }
}

/** Each change still on the phone, and a button to send them now. */
@Composable
fun WaitingList(m: AppModel, snap: Snapshot?) {
    val waiting by m.waiting.collectAsState()
    if (waiting == 0) return
    val items = remember(waiting) { m.outbox.all() }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberScope()
    Card(Modifier.padding(top = 14.dp), border = C.warnBg) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (waiting == 1) t("1 change waiting to send", "Badiliko 1 linasubiri kutumwa") else t("$waiting changes waiting to send", "Mabadiliko $waiting yanasubiri kutumwa"),
                style = MaterialTheme.typography.titleMedium, color = C.warn, modifier = Modifier.weight(1f))
        }
        Text(t("They are safe on this phone and go as soon as there is signal.", "Yako salama kwenye simu hii na yatatumwa mara mtandao ukipatikana."), style = MaterialTheme.typography.bodySmall, color = C.muted)
        items.forEach { it ->
            val b = it.body; val id = b.optString("id")
            val title = snap?.projects?.firstOrNull { p -> p.id == id }?.title ?: b.optString("name").ifBlank { b.optString("title").ifBlank { id } }
            val (icon, what) = when (b.optString("action")) {
                "pin" -> "pin" to t("Location", "Mahali"); "visit" -> "clip" to t("Site visit", "Ziara") + (b.optString("state").takeIf { s -> s.isNotBlank() }?.let { s -> " · " + stateName(s) } ?: "")
                "fixed" -> "wrench" to t("Marked fixed", "Imerekebishwa"); "new" -> "plus" to t("New site", "Eneo jipya"); "undo" -> "undo" to t("Undo a pin", "Tendua mahali")
                else -> "inbox" to t("Change", "Badiliko")
            }
            val files = it.files.size - it.done
            Row(Modifier.padding(top = 10.dp).testTag("waiting_row"), verticalAlignment = Alignment.CenterVertically) {
                IconTile(icon, C.warn, C.warnBg, 36.dp); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(what + (if (files > 0) " · " + (if (files == 1) t("1 photo or voice note", "picha au sauti 1") else t("$files photos or voice notes", "picha au sauti $files")) else "") +
                        (b.optString("savedAt").takeIf { s -> s.isNotBlank() }?.let { s -> " · " + t("saved ", "imehifadhiwa ") + ago(s) } ?: ""), style = MaterialTheme.typography.bodySmall, color = C.muted)
                }
            }
        }
        Btn(if (busy) t("Sending…", "Inatuma…") else t("Send now", "Tuma sasa"), { busy = true; m.refresh { busy = false } }, Modifier.padding(top = 12.dp).fillMaxWidth(), icon = "upload", enabled = !busy)
    }
}

/** Who has been working today and where; call or WhatsApp them. */
@Composable
fun TeamToday(m: AppModel, mates: List<Mate>?, act: List<JSONObject>?, snap: Snapshot?, open: (String) -> Unit) {
    val ctx = LocalContext.current
    Card(Modifier.padding(top = 14.dp).testTag("team_today")) {
        H2(t("Team today", "Timu leo"))
        if (mates == null) { Text(t("Loading…", "Inapakia…"), color = C.muted, style = MaterialTheme.typography.bodyMedium); return@Card }
        val today = LocalDate.now()
        val last = mates.associateWith { p -> act?.filter { same(it.optString("by"), p.name) && it.optString("a") in WORK }?.maxByOrNull { it.optString("at") } }
        val sorted = mates.filter { !same(it.name, m.prefs.name) }.sortedWith(compareByDescending<Mate> { p -> (last[p]?.optString("at") ?: p.lastSeen ?: "") })
        val active = sorted.count { p -> listOfNotNull(last[p]?.optString("at"), p.lastSeen).any { day(it) == today } }
        Text(when (active) { 0 -> t("Nobody else has used the app today yet.", "Hakuna mwingine aliyetumia programu leo bado."); 1 -> t("1 person active today", "Mtu 1 yuko kazini leo"); else -> t("$active people active today", "Watu $active wako kazini leo") },
            style = MaterialTheme.typography.bodySmall, color = C.muted)
        sorted.forEachIndexed { i, p ->
            val e = last[p]
            val onToday = listOfNotNull(e?.optString("at"), p.lastSeen).any { day(it) == today }
            Row(Modifier.padding(top = 10.dp).rise(i, step = 50).fillMaxWidth().testTag("mate_row"), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Box(Modifier.size(42.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF47B4F5), Color(0xFF0A78C2)))), contentAlignment = Alignment.Center) {
                        Text(p.name.split(" ").mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""), color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                    if (onToday) Box(Modifier.align(Alignment.BottomEnd).size(13.dp).clip(CircleShape).background(Color.White).padding(2.dp).clip(CircleShape).background(Color(0xFF1C9C5A)))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).then(if (e != null) Modifier.clickable { open(e.optString("id")) } else Modifier)) {
                    Text(p.name, style = MaterialTheme.typography.titleSmall, color = C.ink, maxLines = 1)
                    val place = e?.let { x -> snap?.projects?.firstOrNull { it.id == x.optString("id") } }
                    Text(if (e != null) verb(e) + " " + (place?.title ?: e.optString("t")) + (place?.area?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") + " · " + ago(e.optString("at"))
                        else t("Last seen ", "Alionekana ") + ago(p.lastSeen), style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (p.phone.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    RoundAction("phone", t("Call ${p.name}", "Mpigie ${p.name}"), C.hue("near")) { runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + p.phone.replace(" ", "")))) } }
                    Spacer(Modifier.width(6.dp))
                    RoundAction("chat", t("WhatsApp ${p.name}", "WhatsApp ${p.name}"), C.hue("near")) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + waNumber(p.phone, p.home)))) } }
                }
            }
        }
        if (sorted.none { it.phone.isNotBlank() } && snap?.admin == true) Text(t("Add phone numbers in Office › Team to call or WhatsApp from here.", "Ongeza namba za simu kwenye Ofisi › Timu ili kupiga au kutuma WhatsApp kutoka hapa."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 10.dp))
    }
}

/** +254 712… from 0712… using the person's home country. */
fun waNumber(phone: String, home: String): String {
    val d = phone.filter { it.isDigit() }
    val cc = mapOf("ke" to "254", "tz" to "255", "ug" to "256", "mw" to "265")[home] ?: "254"
    return if (phone.trim().startsWith("+")) d else if (d.startsWith("0")) cc + d.drop(1) else d
}

@Composable
private fun RoundAction(icon: String, label: String, hu: Hue, onClick: () -> Unit) {
    val h = rememberHaptics()
    Box(Modifier.size(40.dp).clip(CircleShape).background(hu.bg).clickable(role = Role.Button) { h.tap(); onClick() }.semantics { contentDescription = label }.testTag("call_$icon"), contentAlignment = Alignment.Center) {
        Icon(Ic.get(icon), null, tint = hu.fg, modifier = Modifier.size(19.dp))
    }
}

private fun verb(e: JSONObject) = when (e.optString("a")) {
    "pin" -> t("Pinned", "Aliweka mahali")
    "visit" -> t("Visited", "Alitembelea")
    "fixed" -> t("Fixed", "Alirekebisha")
    "new" -> t("Added", "Aliongeza")
    else -> e.optString("a")
}

/** Friendly totals for each person, this week or this month. In name order: no ranking. */
@Composable
fun TeamMonth(m: AppModel, act: List<JSONObject>?) {
    var month by remember { mutableStateOf(true) }
    Card(Modifier.padding(top = 14.dp).testTag("team_month")) {
        H2(t("Team totals", "Jumla ya timu"))
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(t("This week", "Wiki hii"), !month, Modifier.weight(1f)) { month = false }
            Pill(t("This month", "Mwezi huu"), month, Modifier.weight(1f).testTag("tm_month")) { month = true }
        }
        if (act == null) { Text(t("Loading…", "Inapakia…"), color = C.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)); return@Card }
        val today = LocalDate.now(); val from = if (month) today.withDayOfMonth(1) else today.minusDays((today.dayOfWeek.value - 1).toLong())
        val rows = act.filter { it.optString("a") in WORK && day(it.optString("at"))?.let { d -> !d.isBefore(from) } == true }
        val by = rows.groupBy { it.optString("by").trim() }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
        val tot = { a: String -> rows.count { it.optString("a") == a || (a == "pin" && it.optString("a") == "new") } }
        Row(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.tint).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("Together", "Pamoja"), style = MaterialTheme.typography.titleSmall, color = C.deep, modifier = Modifier.weight(1f))
            Text(t("${tot("pin")} pins · ${tot("visit")} visits · ${tot("fixed")} fixed", "mahali ${tot("pin")} · ziara ${tot("visit")} · ${tot("fixed")} zimerekebishwa"), style = MaterialTheme.typography.labelMedium, color = C.deep, modifier = Modifier.testTag("tm_total"))
        }
        if (by.isEmpty()) Text(t("Nothing yet in this period.", "Hakuna bado katika kipindi hiki."), style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.padding(top = 10.dp))
        by.forEach { (name, l) ->
            Row(Modifier.padding(top = 10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                listOf("pin" to "pin", "visit" to "near", "fixed" to "repairs").forEach { (a, hue) ->
                    val n = l.count { it.optString("a") == a || (a == "pin" && it.optString("a") == "new") }
                    val hu = C.hue(hue)
                    Row(Modifier.padding(start = 6.dp).clip(RoundedCornerShape(99.dp)).background(hu.bg).padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Ic.get(when (a) { "pin" -> "pin"; "visit" -> "clip"; else -> "wrench" }), null, tint = hu.fg, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(4.dp))
                        Text("$n", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = hu.fg)
                    }
                }
            }
        }
    }
}

/** Everyone's latest pins, visits and fixes. Tap one to open the project. */
@Composable
fun TeamFeed(act: List<JSONObject>?, snap: Snapshot?, open: (String) -> Unit) {
    var more by remember { mutableStateOf(false) }
    Card(Modifier.padding(top = 14.dp).testTag("team_feed")) {
        H2(t("Team feed", "Habari za timu"))
        if (act == null) { Text(t("Loading…", "Inapakia…"), color = C.muted, style = MaterialTheme.typography.bodyMedium); return@Card }
        val list = act.filter { it.optString("a") in WORK || it.optString("a") == "job" }.sortedByDescending { it.optString("at") }
        if (list.isEmpty()) { EmptyDrop(t("Nothing yet. Pins, visits and fixes show here.", "Bado hakuna. Mahali, ziara na marekebisho yataonekana hapa.")); return@Card }
        list.take(if (more) 40 else 8).forEachIndexed { i, e ->
            val (icon, hue) = when (e.optString("a")) { "pin" -> "pin" to "pin"; "visit" -> "clip" to "near"; "fixed" -> "check" to "near"; "new" -> "plus" to "new"; else -> "wrench" to "repairs" }
            val hu = C.hue(hue)
            val id = e.optString("id")
            val known = snap?.projects?.any { it.id == id } == true
            Row(Modifier.padding(top = 8.dp).rise(i, step = 40).fillMaxWidth().clip(RoundedCornerShape(14.dp)).then(if (known) Modifier.clickable { open(id) } else Modifier).padding(6.dp).testTag("feed_row"), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(hu.bg), contentAlignment = Alignment.Center) { Icon(Ic.get(icon), null, tint = hu.fg, modifier = Modifier.size(17.dp)) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(androidx.compose.ui.text.buildAnnotatedString {
                        withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append(e.optString("by").substringBefore(' ')) }
                        append(" " + verb(e).lowercase() + " " + (snap?.projects?.firstOrNull { it.id == id }?.title ?: e.optString("t")))
                        if (e.optString("a") == "visit" && e.optString("s").isNotBlank()) append(" · " + stateName(e.optString("s")))
                    }, style = MaterialTheme.typography.bodyMedium, color = C.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(ago(e.optString("at")), style = MaterialTheme.typography.labelSmall, color = C.muted)
                }
                if (known) Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(18.dp))
            }
        }
        if (list.size > 8) Text(if (more) t("Show less", "Onyesha kidogo") else t("Show more", "Onyesha zaidi"), style = MaterialTheme.typography.labelLarge, color = C.blue,
            modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).clickable { more = !more }.padding(8.dp).testTag("feed_more"))
    }
}

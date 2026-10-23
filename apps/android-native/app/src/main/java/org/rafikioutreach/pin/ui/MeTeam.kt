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
import kotlinx.coroutines.launch
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

/** A water committee's SMS or WhatsApp report (lib/sms.js logs it "by: SMS from +254 7••", src "sms"): shown as
 *  "Community report" with a phone, and never counted as someone on the team. */
fun isCommunity(e: JSONObject) = e.optString("src") == "sms" || e.optString("by").startsWith("SMS from") || e.optString("by").startsWith("WhatsApp from")
fun communityName() = t("Community report", "Ripoti ya jamii")

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

/** My month: the last six weeks as a chart (this month's numbers are on the card at the top). */
@Composable
fun MyMonth(m: AppModel, act: List<JSONObject>?) {
    val mine = act?.filter { same(it.optString("by"), m.prefs.name) && it.optString("a") in WORK }
    Card(Modifier.padding(top = 14.dp).testTag("my_month")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            H2(t("My month", "Mwezi wangu"), wide = false)
            Text(t("last 6 weeks", "wiki 6 zilizopita"), style = MaterialTheme.typography.labelLarge, color = C.muted)
        }
        val today = LocalDate.now()
        // the last six weeks, oldest first
        val weeks = mine?.let { l -> (5 downTo 0).map { w -> val from = today.minusDays((w * 7L) + 6); val to = today.minusDays(w * 7L); l.count { day(it.optString("at"))?.let { d -> !d.isBefore(from) && !d.isAfter(to) } == true } } }
        val top = maxOf(1, weeks?.max() ?: 1)
        Row(Modifier.padding(top = 10.dp).fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            (weeks ?: List(6) { -1 }).forEachIndexed { i, n ->
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                    if (n < 0) { Skel(Modifier.fillMaxWidth().fillMaxHeight(0.3f + 0.1f * (i % 3))); return@Column }
                    val f = rememberFill(n.toFloat() / top, 800 + i * 80)
                    if (n > 0) Text("$n", style = MaterialTheme.typography.labelSmall, color = C.muted)
                    Box(Modifier.fillMaxWidth().fillMaxHeight(maxOf(0.06f, f * 0.8f)).clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)).background(if (i == 5) C.blue else C.blue.copy(alpha = 0.35f)).testTag("my_week"))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(t("6 wk ago", "Wiki 6"), style = MaterialTheme.typography.labelSmall, color = C.muted, modifier = Modifier.weight(1f))
            Text(t("This week", "Wiki hii"), style = MaterialTheme.typography.labelSmall, color = C.muted)
        }
    }
}

// Due dates as the brief writes them. A small local helper for now; it can join the shared date helper later.
private val DAYS_SW = listOf("Jtt", "Jnn", "Jtn", "Alh", "Iju", "Jmo", "Jpl")
private val MONTHS_SW = listOf("Jan", "Feb", "Mac", "Apr", "Mei", "Jun", "Jul", "Ago", "Sep", "Okt", "Nov", "Des")
/** "Thu 24 Oct" ("Alh 24 Okt"), or "24 Oct 2027" in another year. */
internal fun dueDay(d: LocalDate, today: LocalDate = LocalDate.now()): String {
    val sw = org.rafikioutreach.pin.Lang.sw
    val mon = if (sw) MONTHS_SW[d.monthValue - 1] else d.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US).take(3)
    if (d.year != today.year) return "${d.dayOfMonth}\u00A0$mon\u00A0${d.year}"
    val wd = if (sw) DAYS_SW[d.dayOfWeek.value - 1] else d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US).take(3)
    return "$wd\u00A0${d.dayOfMonth}\u00A0$mon"
}
/** "due today", "due tomorrow", "due in 2 days", "due Thu 24 Oct", "3 days late". */
internal fun dueText(iso: String, today: LocalDate = LocalDate.now()): String {
    val d = runCatching { LocalDate.parse(iso.take(10)) }.getOrNull() ?: return iso
    val n = ChronoUnit.DAYS.between(today, d).toInt()
    return when {
        n < -1 -> t("${-n} days late", "imechelewa siku ${-n}"); n == -1 -> t("1 day late", "imechelewa siku 1")
        n == 0 -> t("due today", "mwisho leo"); n == 1 -> t("due tomorrow", "mwisho kesho")
        n < 7 -> t("due in $n days", "mwisho baada ya siku $n")
        else -> t("due ", "mwisho ") + dueDay(d, today)
    }
}
/** The short tag on the right: "Today", "Tomorrow", "In 2 days", "Thu 24 Oct". */
internal fun dueTag(iso: String, today: LocalDate = LocalDate.now()): String {
    val d = runCatching { LocalDate.parse(iso.take(10)) }.getOrNull() ?: return iso
    val n = ChronoUnit.DAYS.between(today, d).toInt()
    return when { n == 0 -> t("Today", "Leo"); n == 1 -> t("Tomorrow", "Kesho"); n in 2..6 -> t("In $n days", "Baada ya siku $n"); else -> dueDay(d, today) }
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) { H2(t("My work", "Kazi yangu"), wide = false); Tag("${visits.size + jobs.size}", C.tint, C.deep) }
        visits.sortedByDescending { it.third }.forEach { (a, date, late) ->
            WorkRow("cal", "due", a.title.ifBlank { snap.projects.firstOrNull { it.id == a.id }?.title ?: a.id },
                t("Visit", "Ziara") + (if (a.note.isNotBlank()) " · " + a.note else "") + " · " + t("from\u00A0", "kutoka\u00A0") + a.by,
                if (late) t("Overdue", "Imechelewa") else dueTag(date), if (late) "bad" else if (date == today) "ok" else "") { open(a.id) }
        }
        jobs.forEach { (p, r) ->
            WorkRow("wrench", "repairs", p.title, t("Repair job", "Kazi ya matengenezo") + (r?.due?.takeIf { it.isNotBlank() }?.let { " · " + dueText(it) } ?: ""),
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
    Card(Modifier.padding(top = 14.dp), brush = androidx.compose.ui.graphics.SolidColor(if (C.dark) C.card else Color(0xFFFFFBF4)), border = Color(0xFFF2C98A)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val hu = C.hue("repairs"); IconTile("upload", hu.fg, hu.bg, 40.dp); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (waiting == 1) t("1 change waiting to send", "Badiliko 1 linasubiri kutumwa") else t("$waiting changes waiting to send", "Mabadiliko $waiting yanasubiri kutumwa"),
                    style = MaterialTheme.typography.titleMedium, color = C.warn)
                Text(t("Safe on this phone. They go as soon as there’s signal.", "Yako salama kwenye simu hii. Yatatumwa mara mtandao ukipatikana."), style = MaterialTheme.typography.bodySmall, color = C.muted)
            }
        }
        items.forEach { it ->
            val b = it.body; val id = b.optString("id")
            val title = snap?.projects?.firstOrNull { p -> p.id == id }?.title ?: b.optString("name").ifBlank { b.optString("title").ifBlank { id } }
            val (icon, what) = when (b.optString("action")) {
                "pin" -> "pin" to t("Location", "Mahali"); "visit" -> "clip" to t("Site visit", "Ziara") + (b.optString("state").takeIf { s -> s.isNotBlank() }?.let { s -> " · " + stateName(s) } ?: "")
                "fixed" -> "wrench" to t("Marked fixed", "Imerekebishwa"); "new" -> "plus" to t("New site", "Eneo jipya"); "undo" -> "undo" to t("Undo a pin", "Tendua mahali")
                else -> "inbox" to t("Change", "Badiliko")
            }
            val files = it.files.size - it.done
            Row(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.warnBg).padding(horizontal = 12.dp, vertical = 10.dp).testTag("waiting_row"), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.get(icon), null, tint = C.warn, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp))
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
    // a teammate's picture opens their contact sheet (Contact.kt)
    Card(Modifier.padding(top = 14.dp).testTag("team_today")) {
        val head = remember { mutableStateOf(0) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) { H2(t("Team today", "Timu leo"), wide = false); if (head.value > 0) Tag(t("${head.value} active", "${head.value} kazini"), C.okBg, C.ok) }
        if (mates == null) { Text(t("Loading…", "Inapakia…"), color = C.muted, style = MaterialTheme.typography.bodyMedium); return@Card }
        val today = LocalDate.now()
        val last = mates.associateWith { p -> act?.filter { same(it.optString("by"), p.name) && it.optString("a") in WORK }?.maxByOrNull { it.optString("at") } }
        val sorted = mates.filter { !same(it.name, m.prefs.name) }.sortedWith(compareByDescending<Mate> { p -> (last[p]?.optString("at") ?: p.lastSeen ?: "") })
        val active = sorted.count { p -> listOfNotNull(last[p]?.optString("at"), p.lastSeen).any { day(it) == today } }
        head.value = active
        Text(when { active == 0 -> t("Nobody else has used the app today yet.", "Hakuna mwingine aliyetumia programu leo bado."); sorted.any { it.phone.isNotBlank() } -> t("Tap someone to call or WhatsApp them. Tap what they did to open the project.", "Gusa mtu kumpigia au kumtumia WhatsApp. Gusa alichofanya kufungua mradi."); active == 1 -> t("1 person active today", "Mtu 1 yuko kazini leo"); else -> t("$active people active today", "Watu $active wako kazini leo") },
            style = MaterialTheme.typography.bodySmall, color = C.muted)
        sorted.forEachIndexed { i, p ->
            val e = last[p]
            val onToday = listOfNotNull(e?.optString("at"), p.lastSeen).any { day(it) == today }
            Row(Modifier.padding(top = 10.dp).rise(i, step = 50).fillMaxWidth().testTag("mate_row"), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(CircleShape).clickable(role = Role.Button) { Contact.show(p.name) }.semantics { contentDescription = p.name }.testTag("mate_open")) {
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
                    RoundAction("chat", t("WhatsApp ${p.name}", "WhatsApp ${p.name}"), Hue(WhatsAppGreen, WhatsAppGreen.copy(alpha = 0.14f)), brand = "business.whatsapp.com") { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + waNumber(p.phone, p.home)))) } }
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
private fun RoundAction(icon: String, label: String, hu: Hue, brand: String? = null, onClick: () -> Unit) {
    val h = rememberHaptics()
    Box(Modifier.size(40.dp).clip(CircleShape).background(hu.bg).clickable(role = Role.Button) { h.tap(); onClick() }.semantics { contentDescription = label }.testTag("call_$icon"), contentAlignment = Alignment.Center) {
        if (brand != null) Brand(brand, 22.dp, icon, hu.fg) else Icon(Ic.get(icon), null, tint = hu.fg, modifier = Modifier.size(19.dp))
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
        SmallSeg(listOf("w" to t("This week", "Wiki hii"), "m" to t("This month", "Mwezi huu")), if (month) "m" else "w", Modifier.padding(top = 4.dp).fillMaxWidth(), tag = "tm_") { month = it == "m" }
        if (act == null) { Text(t("Loading…", "Inapakia…"), color = C.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)); return@Card }
        val today = LocalDate.now(); val from = if (month) today.withDayOfMonth(1) else today.minusDays((today.dayOfWeek.value - 1).toLong())
        val all = act.filter { it.optString("a") in WORK && day(it.optString("at"))?.let { d -> !d.isBefore(from) } == true }
        val rows = all.filterNot { isCommunity(it) }
        val community = all.filter { isCommunity(it) }
        val by = rows.groupBy { it.optString("by").trim() }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
        val tot = { a: String -> rows.count { it.optString("a") == a || (a == "pin" && it.optString("a") == "new") } }
        Row(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.tint).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("Together", "Pamoja"), style = MaterialTheme.typography.titleSmall, color = C.deep, modifier = Modifier.weight(1f))
            Text(t("${tot("pin")} ${if (tot("pin") == 1) "pin" else "pins"} · ${tot("visit")} ${if (tot("visit") == 1) "visit" else "visits"} · ${tot("fixed")} fixed", "mahali ${tot("pin")} · ziara ${tot("visit")} · ${tot("fixed")} zimerekebishwa"), style = MaterialTheme.typography.labelMedium, color = C.deep, modifier = Modifier.testTag("tm_total"))
        }
        if (by.isEmpty() && community.isEmpty()) Text(t("Nothing yet in this period.", "Hakuna bado katika kipindi hiki."), style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.padding(top = 10.dp))
        // the team in name order, then water committees' reports on a line of their own
        (by.entries.map { it.key to it.value } + (if (community.isNotEmpty()) listOf("" to community) else emptyList())).forEach { (name, l) ->
            Row(Modifier.padding(top = 4.dp).fillMaxWidth().then(if (name.isEmpty()) Modifier.testTag("tm_community") else Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { Contact.show(name) }.testTag("tm_who_$name"))
                .heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                if (name.isEmpty()) { Icon(Ic.get("phone"), null, tint = C.muted, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
                Text(name.ifEmpty { communityName() }, style = MaterialTheme.typography.titleSmall, color = if (name.isEmpty()) C.muted else C.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    BackStep(more) { more = false }
    Card(Modifier.padding(top = 14.dp).testTag("team_feed")) {
        H2(t("Team feed", "Habari za timu"))
        if (act == null) { Text(t("Loading…", "Inapakia…"), color = C.muted, style = MaterialTheme.typography.bodyMedium); return@Card }
        val list = act.filter { it.optString("a") in WORK || it.optString("a") == "job" }.sortedByDescending { it.optString("at") }
        if (list.isEmpty()) { EmptyDrop(t("Nothing yet. Pins, visits and fixes show here.", "Bado hakuna. Mahali, ziara na marekebisho yataonekana hapa.")); return@Card }
        list.take(if (more) 40 else 8).forEachIndexed { i, e ->
            val sms = isCommunity(e)
            val (icon, hue) = if (sms) "phone" to "slate" else when (e.optString("a")) { "pin" -> "pin" to "pin"; "visit" -> "clip" to "near"; "fixed" -> "check" to "near"; "new" -> "plus" to "new"; else -> "wrench" to "repairs" }
            val hu = C.hue(hue)
            val id = e.optString("id")
            val known = snap?.projects?.any { it.id == id } == true
            Row(Modifier.padding(top = 8.dp).rise(i, step = 40).fillMaxWidth().clip(RoundedCornerShape(14.dp)).then(if (known) Modifier.clickable { open(id) } else Modifier).padding(6.dp).testTag(if (sms) "feed_community" else "feed_row"), verticalAlignment = Alignment.CenterVertically) {
                // the person (tap: their contact sheet) with what they did as a badge; community reports keep the phone
                if (sms || !Contact.isPerson(e.optString("by").trim())) Box(Modifier.size(34.dp).clip(CircleShape).background(hu.bg), contentAlignment = Alignment.Center) { Icon(Ic.get(icon), null, tint = hu.fg, modifier = Modifier.size(17.dp)) }
                else PersonTap(e.optString("by"), tag = "feed_who") { PersonBadgeAvatar(e.optString("by"), icon, hu, C.card, 34.dp) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(androidx.compose.ui.text.buildAnnotatedString {
                        withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append(if (sms) communityName() else e.optString("by").substringBefore(' ')) }
                        append((if (sms) ": " else " " + verb(e).lowercase() + " ") + (snap?.projects?.firstOrNull { it.id == id }?.title ?: e.optString("t")))
                        if (sms && e.optString("a") == "fixed") append(" · " + t("working again", "inafanya kazi tena"))
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

private const val TOKEN_PAGE = "https://rafikicap-company.monday.com/apps/manage/tokens"
private fun looksToken(s: String?) = s != null && Regex("^[A-Za-z0-9._-]{40,4000}$").matches(s.trim())

/** Me › Your name on monday.com: open the token page, copy, come back, and the app connects by itself. */
@Composable
fun ConnectMonday(m: AppModel, snap: Snapshot?, inline: Boolean = false) {
    snap ?: return
    if (m.prefs.session.isNotBlank()) return   // signed in with monday.com already
    val ctx = LocalContext.current
    val scope = rememberScope()
    var waiting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    val clip = remember { ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager }
    fun send(tok: String) {
        if (!looksToken(tok)) { msg = org.rafikioutreach.pin.data.reasonText("token-format"); return }
        busy = true; msg = t("Checking with monday.com…", "Inakagua na monday.com…")
        scope.launch {
            val err = m.linkMonday(tok); busy = false; msg = err
            if (err == null) { waiting = false; runCatching { clip.setPrimaryClip(android.content.ClipData.newPlainText("", "")) } }
        }
    }
    fun paste() = send(clip.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty())
    // back from the token page: read the clipboard straight away
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner, waiting) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && waiting && !busy) {
            val txt = clip.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
            if (looksToken(txt)) send(txt!!)
        } }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    Wrap(inline) {
        if (!inline) Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(MondayBlue.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) { Brand("monday.com", 24.dp, "people", MondayBlue) }; Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                H2(t("Your name on monday.com", "Jina lako kwenye monday.com"))
                when {
                    snap.linked.isNotBlank() -> Text(t("Connected as ${snap.linked} ✓", "Imeunganishwa kama ${snap.linked} ✓"), style = MaterialTheme.typography.titleSmall, color = C.ok, modifier = Modifier.testTag("monday_connected"))
                    snap.who.isBlank() -> Text(t("Your changes go in under the shared account. To use your own name, ask the office for your own PIN.", "Mabadiliko yako yanaingia kwa akaunti ya pamoja. Kutumia jina lako, omba ofisi PIN yako mwenyewe."), style = MaterialTheme.typography.bodySmall, color = C.muted)
                    else -> Text(t("Takes 20 seconds, once. Your changes then show on monday.com under your own name.", "Inachukua sekunde 20, mara moja. Mabadiliko yako yataonekana monday.com kwa jina lako."), style = MaterialTheme.typography.bodySmall, color = C.muted)
                }
            }
        }
        if (snap.linked.isNotBlank()) {
            Text(t("Everything you save goes to monday.com under your own name.", "Kila unachohifadhi kinaenda monday.com kwa jina lako."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = if (inline) 0.dp else 8.dp))
            Text(t("Disconnect", "Tenganisha"), style = MaterialTheme.typography.labelLarge, color = C.blue, modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).clickable { scope.launch { m.unlinkMonday() } }.padding(6.dp).testTag("monday_off"))
            return@Wrap
        }
        if (snap.who.isBlank()) return@Wrap
        Btn("1 · " + t("Open my monday.com token", "Fungua tokeni yangu ya monday.com"), { waiting = true; runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TOKEN_PAGE))) } }, Modifier.padding(top = 12.dp).fillMaxWidth().testTag("monday_open"), color = MondayBlue)
        Text(t("Tap Copy on that page, then come back here.", "Gusa Copy kwenye ukurasa huo, kisha rudi hapa."), style = MaterialTheme.typography.labelSmall, color = C.muted, modifier = Modifier.padding(top = 4.dp).align(Alignment.CenterHorizontally))
        Btn(if (busy) t("Checking…", "Inakagua…") else "2 · " + t("Paste and connect", "Bandika na uunganishe"), { paste() }, Modifier.padding(top = 10.dp).fillMaxWidth().testTag("monday_paste"), light = true, enabled = !busy)
        Text(t("Or paste it yourself", "Au ibandike mwenyewe"), style = MaterialTheme.typography.labelLarge, color = C.blue, modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).clickable { manual = !manual }.padding(6.dp))
        if (manual) {
            androidx.compose.material3.OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth().testTag("monday_token"), singleLine = true, placeholder = { Text(t("monday.com token", "tokeni ya monday.com")) },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            Btn(t("Connect", "Unganisha"), { send(typed) }, Modifier.padding(top = 8.dp), small = true, enabled = !busy)
        }
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (busy) C.muted else C.bad, modifier = Modifier.padding(top = 8.dp).testTag("monday_msg")) }
        Text(t("On a phone, monday.com may only open this page on a computer. You can do this step on any computer: sign in with your PIN at pin.rafikioutreach.org and connect. It then works on every phone that uses your PIN.", "Kwenye simu, monday.com inaweza kufungua ukurasa huu kwenye kompyuta tu. Unaweza kufanya hatua hii kwenye kompyuta yoyote: ingia kwa PIN yako kwenye pin.rafikioutreach.org na uunganishe. Kisha itafanya kazi kwenye kila simu inayotumia PIN yako."),
            style = MaterialTheme.typography.labelSmall, color = C.muted, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun Wrap(inline: Boolean, content: @Composable ColumnScope.() -> Unit) {
    if (inline) Column(Modifier.fillMaxWidth().testTag("connect_monday"), content = content) else Card(Modifier.padding(top = 14.dp).testTag("connect_monday"), content = content)
}

/** Lock screen › monday.com sign-in: with the person's own monday.com token (works before the app is installed for the account). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MondaySignInSheet(m: AppModel, close: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberScope()
    var waiting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    val clip = remember { ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager }
    fun send(tok: String) {
        if (!looksToken(tok)) { msg = org.rafikioutreach.pin.data.reasonText("token-format"); return }
        busy = true; msg = t("Checking with monday.com…", "Inakagua na monday.com…")
        scope.launch {
            val err = m.mondayToken(tok); busy = false; msg = err
            if (err == null) { runCatching { clip.setPrimaryClip(android.content.ClipData.newPlainText("", "")) }; close() }
        }
    }
    fun clipText() = clip.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner, waiting) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && waiting && !busy) clipText().let { if (looksToken(it)) send(it) } }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = close, containerColor = C.card, modifier = Modifier.testTag("monday_sheet")) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            H2(t("Sign in with monday.com", "Ingia kwa monday.com"))
            Text(t("Takes 20 seconds. Everything you save then goes to monday.com under your own name.", "Inachukua sekunde 20. Kila unachohifadhi kitaenda monday.com kwa jina lako."), style = MaterialTheme.typography.bodySmall, color = C.muted)
            Btn("1 · " + t("Open my monday.com token", "Fungua tokeni yangu ya monday.com"), { waiting = true; runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TOKEN_PAGE))) } }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("ms_open"))
            Text(t("Tap Copy on that page, then come back here.", "Gusa Copy kwenye ukurasa huo, kisha rudi hapa."), style = MaterialTheme.typography.labelSmall, color = C.muted, modifier = Modifier.padding(top = 4.dp).align(Alignment.CenterHorizontally))
            Btn(if (busy) t("Checking…", "Inakagua…") else "2 · " + t("Paste and sign in", "Bandika na uingie"), { send(clipText()) }, Modifier.padding(top = 10.dp).fillMaxWidth().testTag("ms_paste"), light = true, enabled = !busy)
            Text(t("Or paste it yourself", "Au ibandike mwenyewe"), style = MaterialTheme.typography.labelLarge, color = C.blue, modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).clickable { manual = !manual }.padding(6.dp))
            if (manual) {
                androidx.compose.material3.OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth().testTag("ms_token"), singleLine = true, placeholder = { Text(t("monday.com token", "tokeni ya monday.com")) },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                Btn(t("Sign in", "Ingia"), { send(typed) }, Modifier.padding(top = 8.dp), small = true, enabled = !busy)
            }
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (busy) C.muted else C.bad, modifier = Modifier.padding(top = 8.dp).testTag("ms_msg")) }
            Text(t("If the token page won’t open on your phone, sign in with your PIN for now, then connect it once from any computer under Me › Your name on monday.com.", "Ukurasa wa tokeni usipofunguka kwenye simu yako, ingia kwa PIN yako kwa sasa, kisha uiunganishe mara moja kutoka kompyuta yoyote kwenye Mimi › Jina lako kwenye monday.com."),
                style = MaterialTheme.typography.labelSmall, color = C.muted, modifier = Modifier.padding(top = 10.dp))
            Text(t("Or use monday.com’s approve page", "Au tumia ukurasa wa idhini wa monday.com"), style = MaterialTheme.typography.labelLarge, color = C.blue,
                modifier = Modifier.padding(top = 10.dp).align(Alignment.CenterHorizontally).clip(RoundedCornerShape(10.dp)).clickable { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(m.mondayStartUrl()))) } }.padding(6.dp))
        }
    }
}

package org.rafikioutreach.pin.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.Snapshot
import org.rafikioutreach.pin.t

// A simpler Office for new admins (web v71: oSeg groups, ocvStartHtml, ocvTodoHtml): the sections in four labelled
// groups, and on Overview a "Start here" checklist and a "Needs you" list above the numbers.

/** A place inside an Office section to bring into view once it opens, and the Repairs filter to open on. */
object OfficeJump {
    /** "alerts" (Team › E-mail alerts) or "limit" (Costs › Second approval). */
    var to by mutableStateOf<String?>(null)
    /** Office › Repairs opens on this filter once ("unassigned" from Needs you). */
    var repairs: String? = null
}

/** This element is the place [OfficeJump.to] asks for: once it is laid out (and [ready]), it is scrolled into view. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun jumpHere(key: String, ready: Boolean = true): Modifier {
    val r = remember { BringIntoViewRequester() }
    val want = OfficeJump.to
    LaunchedEffect(want, ready) {
        if (want != key || !ready) return@LaunchedEffect
        kotlinx.coroutines.delay(350) // the section has drawn (a fold opened, the team list came in)
        r.bringIntoView(); OfficeJump.to = null
    }
    return Modifier.bringIntoViewRequester(r)
}

/** The Office sections in the order an admin uses them; anything not listed goes to Records. */
internal val OFFICE_GROUPS = listOf(
    "need" to listOf("overview", "review", "repairs", "visits"), "field" to listOf("plan", "trips", "teammap", "vehicles"),
    "people" to listOf("team", "donors", "mail", "sms"), "records" to listOf("costs", "export", "qr", "cleanup"),
)
internal fun officeGroupName(k: String) = when (k) {
    "need" -> t("Needs you", "Inakuhitaji"); "field" -> t("Field work", "Kazi ya uwanjani"); "people" -> t("People", "Watu"); else -> t("Records", "Kumbukumbu")
}
/** [keys] (the sections there are) in their groups, in group order. */
internal fun officeGroups(keys: List<String>): List<Pair<String, List<String>>> {
    val listed = OFFICE_GROUPS.flatMap { it.second }.toSet()
    return OFFICE_GROUPS.map { (g, l) -> g to (l.filter { it in keys } + if (g == "records") keys.filter { it !in listed } else emptyList()) }.filter { it.second.isNotEmpty() }
}

/** Office: every section as a tile, in its labelled group; two groups side by side on a wide screen. */
@Composable
internal fun OfficeGroupsGrid(secs: List<Triple<String, String, String>>, sel: String, badgeOf: (String) -> Int, pick: (String) -> Unit) {
    val byK = secs.associateBy { it.first }
    val groups = officeGroups(secs.map { it.first })
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("office_groups")) {
        val two = maxWidth >= 600.dp
        var n = 0
        @Composable
        fun Group(g: String, keys: List<String>, modifier: Modifier) = Column(modifier.testTag("ogrp_$g")) {
            Text(officeGroupName(g).uppercase(if (org.rafikioutreach.pin.Lang.sw) java.util.Locale("sw") else java.util.Locale.US), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 0.7.sp),
                color = C.muted, maxLines = 1, modifier = Modifier.padding(start = 2.dp, top = 14.dp, bottom = 6.dp).semantics { heading() }.testTag("ogh_$g"))
            keys.chunked(4).forEachIndexed { ri, row ->
                Row(Modifier.padding(top = if (ri > 0) 8.dp else 0.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { k -> val (_, l, ic) = byK.getValue(k); Tile(l, ic, sel == k, { pick(k) }, Modifier.weight(1f).rise(n++, step = 35).testTag("o_$k"), badge = badgeOf(k)) }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Column {
            if (two) groups.chunked(2).forEach { pair -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { pair.forEach { (g, l) -> Group(g, l, Modifier.weight(1f)) }; if (pair.size == 1) Spacer(Modifier.weight(1f)) } }
            else groups.forEach { (g, l) -> Group(g, l, Modifier.fillMaxWidth()) }
        }
    }
}

// ---------------------------------------------------------------------------------------------- Start here

/** A set-up step: its key, icon, title, line, the section it opens and the place there to bring into view. */
internal class StartStep(val key: String, val icon: String, val title: String, val line: String, val sec: String, val jump: String? = null)
internal fun startSteps() = listOf(
    StartStep("team", "people", t("Add your field team", "Ongeza timu yako ya uwanjani"), t("Give each person a PIN so they can sign in.", "Mpe kila mtu PIN ili aweze kuingia."), "team"),
    StartStep("alerts", "mail", t("Check who gets alert e-⁠mails", "Angalia nani anapata barua pepe za arifa"), t("Repairs and reviews are e-⁠mailed to these admins.", "Matengenezo na ukaguzi hutumwa kwa wasimamizi hawa."), "team", "alerts"),
    StartStep("approve", "shield", t("Set when a repair needs a second approval", "Weka lini matengenezo yanahitaji idhini ya pili"), t("Big repair costs wait for another admin.", "Gharama kubwa za matengenezo zinasubiri msimamizi mwingine."), "costs", "limit"),
    StartStep("plan", "cal", t("Plan a week of visits", "Panga wiki ya ziara"), t("Pick a person; the app suggests the sites, in driving order.", "Chagua mtu; programu inapendekeza maeneo kwa mpangilio wa safari."), "plan"),
    StartStep("qr", "qr", t("Print QR check-⁠in stickers", "Chapisha stika za QR za kuingia"), t("One for each site: scanning it opens the Site visit.", "Moja kwa kila eneo: ukiichanganua inafungua Ziara ya eneo."), "qr"),
)
/** Opening a section ticks off its step (web START_TAB): Team › alerts, Costs › approve, Plan a week, QR codes. */
internal val START_TAB = mapOf("team" to "alerts", "costs" to "approve", "plan" to "plan", "qr" to "qr")
internal fun startMark(m: AppModel, k: String?) { if (k != null && k !in m.prefs.startSeen) m.prefs.startSeen = m.prefs.startSeen + k }

/** Overview › Start here: five set-up steps for a new admin; each opens the right place and ticks itself off.
 *  "Add your field team" is also done once the team has someone who isn't an admin. Hide puts it away for good. */
@Composable
internal fun OcvStart(m: AppModel, go: (String) -> Unit) {
    var off by remember { mutableStateOf(m.prefs.startOff) }
    if (off) return
    var field by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { val a = m.api.adminGet("team").json.optJSONArray("team"); if (a != null) field = (0 until a.length()).any { a.optJSONObject(it)?.optBoolean("admin") == false } }
    val seen = remember { m.prefs.startSeen }
    fun done(k: String) = k in seen || (k == "team" && field)
    val steps = startSteps(); val n = steps.count { done(it.key) }
    if (n == steps.size) return
    val h = rememberHaptics()
    Card(Modifier.padding(top = 12.dp).testTag("ocv_start"), pad = 16.dp) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(t("Start here", "Anza hapa"), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.semantics { heading() })
                Text(t("$n of ${steps.size} done. Each step opens the right place.", "$n kati ya ${steps.size} zimekamilika. Kila hatua inafungua mahali sahihi."), style = MaterialTheme.typography.bodySmall, color = C.muted,
                    modifier = Modifier.padding(top = 2.dp).testTag("ocv_start_n"))
            }
            Spacer(Modifier.width(8.dp))
            Text(t("Hide", "Ficha"), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = C.blue2, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { h.tap(); m.prefs.startOff = true; off = true }.heightIn(min = 44.dp).padding(horizontal = 6.dp, vertical = 12.dp).testTag("ocv_start_off"))
        }
        OcvBar(n / steps.size.toFloat(), C.ok, Modifier.padding(top = 10.dp, bottom = 6.dp).testTag("ocv_start_bar"))
        steps.forEachIndexed { i, x ->
            if (i > 0) HorizontalDivider(color = C.line)
            val ok = done(x.key)
            OstRow("ocv_start_${x.key}", if (ok) "check" else x.icon, if (ok) C.okBg to C.ok else C.hue("blue").let { it.bg to it.fg }, x.title, if (ok) t("Done", "Imekamilika") else x.line, dim = ok) {
                h.tap(); startMark(m, x.key); OfficeJump.to = x.jump; go(x.sec)
            }
        }
    }
}

/** A row of Start here or Needs you: an icon in its colour, a title and a line, a chevron. */
@Composable
private fun OstRow(tag: String, icon: String, colors: Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color>, title: String, line: String, dim: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).heightIn(min = 58.dp).padding(vertical = 8.dp, horizontal = 2.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(colors.first), contentAlignment = Alignment.Center) { Icon(Ic.get(icon), null, tint = colors.second, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, lineHeight = 18.sp), color = if (dim) C.muted else C.ink)
            Text(line, style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(8.dp))
        Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(18.dp))
    }
}

// ---------------------------------------------------------------------------------------------- Needs you

/** Overview › Needs you: only what is waiting for an admin (non-zero rows), each opening the place to act on it. */
@Composable
internal fun OcvTodo(s: Snapshot, due: Int, go: (String) -> Unit, toList: (String) -> Unit) {
    val rv = s.reviewOpen
    val ua = s.repairs.values.count { it.urgency == "urgent" && it.assignee.isBlank() }
    val bh = s.stages.values.count { it.s != "handed" && it.behind }
    val h = rememberHaptics()
    class R(val k: String, val icon: String, val hue: String, val title: String, val line: String, val act: () -> Unit)
    val rows = listOfNotNull(
        if (rv > 0) R("review", "inbox", "purple", if (rv == 1) t("1 thing is waiting for your review", "Jambo 1 linasubiri ukague") else t("$rv things are waiting for your review", "Mambo $rv yanasubiri ukague"),
            t("New sites, locations, stories and big costs", "Maeneo mapya, mahali, hadithi na gharama kubwa")) { go("review") } else null,
        if (ua > 0) R("unassigned", "wrench", "coral", if (ua == 1) t("1 urgent repair has nobody on it", "Matengenezo 1 ya haraka hayana mtu") else t("$ua urgent repairs have nobody on them", "Matengenezo $ua ya haraka hayana mtu"),
            t("Give each one to a person", "Mpe mtu kila moja")) { OfficeJump.repairs = "unassigned"; go("repairs") } else null,
        if (due > 0) R("plan", "cal", "blue", if (due == 1) t("1 site is due a visit", "Eneo 1 linasubiri ziara") else t("$due sites are due a visit", "Maeneo $due yanasubiri ziara"),
            t("Plan a week of visits for someone", "Mpangie mtu wiki ya ziara")) { go("plan") } else null,
        if (bh > 0) R("build", "build", "amber", if (bh == 1) t("1 building project is behind schedule", "Mradi 1 wa ujenzi umechelewa") else t("$bh building projects are behind schedule", "Miradi $bh ya ujenzi imechelewa"),
            t("See what is holding them up", "Angalia kinachozichelewesha")) { toList("build") } else null,
    )
    Card(Modifier.padding(top = 12.dp).testTag("ocv_todo"), pad = 16.dp) {
        Text(t("Needs you", "Inakuhitaji"), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.semantics { heading() })
        if (rows.isEmpty()) NothingHere(t("Nothing needs you right now.", "Hakuna kinachokuhitaji sasa hivi."), "ocv_todo_none")
        else Column(Modifier.padding(top = 6.dp)) {
            rows.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider(color = C.line)
                val hu = C.hue(r.hue)
                OstRow("ocv_todo_${r.k}", r.icon, hu.bg to hu.fg, r.title, r.line) { h.tap(); r.act() }
            }
        }
    }
}

/** "Nothing … right now." with a green check (Needs you, an empty Stories list). */
@Composable
internal fun NothingHere(text: String, tag: String) {
    Row(Modifier.padding(top = 10.dp, bottom = 2.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Icon(Ic.get("check"), null, tint = C.ok, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = C.ok)
    }
}

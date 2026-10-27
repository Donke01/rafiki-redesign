package org.rafikioutreach.pin.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.*
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.COUNTRIES
import org.rafikioutreach.pin.data.DUE_DAYS
import org.rafikioutreach.pin.data.Geo
import org.rafikioutreach.pin.data.Project
import org.rafikioutreach.pin.data.Snapshot
import org.rafikioutreach.pin.data.reasonText
import org.rafikioutreach.pin.data.stateName
import org.rafikioutreach.pin.data.urgencyName
import org.rafikioutreach.pin.data.Money
import org.rafikioutreach.pin.t
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The Office: tools for admins. Same server actions as the web app's Office tab. */
@Composable
fun OfficeScreen(m: AppModel, open: (String) -> Unit) {
    var sec by rememberSaveable { mutableStateOf("overview") }
    var strip by rememberSaveable { mutableStateOf(false) }
    // back from a tab picked on the grid: the grid again (on Overview), not Map home
    BackStep(strip) { strip = false; sec = "overview" }
    val s by m.snap.collectAsState()
    // "E-mail" from Office › Donors or a project's donor area opens Send an e-mail with that audience
    val mailGo = MailNav.preset
    LaunchedEffect(mailGo) { if (mailGo != null) { sec = "mail"; strip = true } }
    // a person's sheet in Office › Team can jump to the Team map or to Assign a visit (TeamPerson.kt)
    val teamGo = TeamGo.section
    LaunchedEffect(teamGo) { if (teamGo != null) { sec = teamGo; strip = true; TeamGo.section = null } }
    val snap = s ?: return
    val secs = listOf(
        Triple("overview", t("Overview", "Muhtasari"), "drop"), Triple("repairs", t("Repairs", "Matengenezo"), "wrench"), Triple("costs", t("Costs", "Gharama"), "coins"), Triple("visits", t("Visits", "Ziara"), "cal"),
        Triple("teammap", t("Team map", "Ramani ya timu"), "pin"), Triple("plan", t("Plan a week", "Panga wiki"), "compass"),
        Triple("review", t("Review", "Kagua"), "inbox"), Triple("team", t("Team", "Timu"), "people"), Triple("cleanup", t("Clean-up", "Safisha data"), "spark"), Triple("export", t("Export", "Hamisha"), "upload"),
        Triple("sms", t("Community", "Jamii"), "chat"),
        Triple("donors", t("Donors", "Wafadhili"), "link"),
        Triple("qr", t("QR codes", "Misimbo ya QR"), "qr"), // QR check-in stickers (QrCheckin.kt)
        Triple("mail", t("Send an e-\u2060mail", "Tuma barua pepe"), "mail"), // Office › Send an e-mail (Mail.kt)
        Triple("trips", t("Trips", "Safari"), "route"), Triple("vehicles", t("Vehicles", "Magari"), "car"), // field and group trips, vehicles and fuel (feat-itina)
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(t("Office", "Ofisi"), style = MaterialTheme.typography.headlineLarge, color = C.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
        val per = if (secs.size % 3 == 0 && secs.size % 4 != 0) 3 else 4
        val badgeOf = { k: String -> when (k) { "review" -> snap.reviewOpen; "repairs" -> snap.repairs.size; else -> 0 } }
        // all the sections as tiles until one is picked; then a strip of chips, so the tab's content starts near the top
        if (!strip) secs.chunked(per).forEachIndexed { ri, row ->
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { ci, (k, l, ic) -> Tile(l, ic, sec == k, { sec = k; strip = true }, Modifier.weight(1f).rise(ri * per + ci, step = 35).testTag("o_$k"), badge = badgeOf(k)) }
                repeat(per - row.size) { Spacer(Modifier.weight(1f)) }
            }
        } else {
            val (scroll, spot) = rememberKeepInView(sec)
            Row(Modifier.padding(top = 10.dp).fillMaxWidth().horizontalScroll(scroll).testTag("office_strip"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip(t("All", "Zote"), "grid", false, { strip = false }, tag = "o_all", hue = "blue")
                secs.forEach { (k, l, ic) -> Box(spot(k)) { Chip(l, ic, sec == k, { sec = k }, badge = badgeOf(k), tag = "o_$k", hue = "blue") } }
            }
        }
        Spacer(Modifier.height(14.dp))
        when (sec) {
            "overview" -> OcvOverview(m, snap, open) { sec = it; strip = true } // OfficeOverview.kt
            "repairs" -> RepairsSection(m, snap, open)   // OfficeRepairs.kt
            "costs" -> CostsSection(m, snap, open)
            "visits" -> OcvVisits(m, snap, open) // OfficeOverview.kt
            "teammap" -> TeamMapSection(m, snap, open)
            "plan" -> PlanWeekSection(m, snap, open)
            "review" -> ReviewSection(m, open)   // OfficeRepairs.kt
            "team" -> TeamSection(m, open)
            "cleanup" -> Cleanup(snap, open)
            "sms" -> CommunityReports(m, open)
            "donors" -> DonorsSection(m, snap)
            "qr" -> QrStickerSection(m, snap)
            "mail" -> MailSection(m, snap)
            "trips" -> TripsSection(m)       // TripsUi.kt
            "vehicles" -> VehiclesSection(m) // Vehicles.kt
            else -> Export(m, snap)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** People who can sign in (from the Team list), for "assigned to". */
@Composable
private fun rememberTeam(m: AppModel): Pair<List<JSONObject>, () -> Unit> {
    var people by remember { mutableStateOf(listOf<JSONObject>()) }
    var tick by remember { mutableStateOf(0) }
    // a change on someone's contact sheet (rename, block, new e-mail…) reloads the list
    LaunchedEffect(tick, Contact.changed) {
        val r = m.api.adminGet("team"); val a = r.json.optJSONArray("team") ?: JSONArray()
        people = (0 until a.length()).map { a.getJSONObject(it) }
    }
    return people to { tick++ }
}

fun verb(a: String) = when (a) {
    "pin" -> t("pinned", "aliweka mahali pa"); "visit" -> t("reported a visit to", "aliripoti ziara ya"); "fixed" -> t("marked fixed", "aliweka imerekebishwa")
    "new" -> t("added a new site:", "aliongeza eneo jipya:"); "undo" -> t("undid a pin on", "alitendua mahali pa"); "request" -> t("asked to move the confirmed location of", "aliomba kuhamisha mahali palipothibitishwa pa")
    "approve" -> t("approved", "aliidhinisha"); "reject" -> t("turned down", "alikataa"); "job" -> t("updated the repair job on", "alisasisha kazi ya matengenezo ya")
    "stage" -> t("moved the build stage of", "alisogeza hatua ya ujenzi ya"); "assign" -> t("assigned", "aligawa"); "team" -> t("team:", "timu:"); "ask" -> t("asked for a second approval on", "aliomba idhini ya pili ya"); "settings" -> t("changed a setting:", "alibadilisha mpangilio:"); else -> a
}

/** A date chosen with a calendar. Empty = none. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp), color = C.ink, modifier = Modifier.padding(bottom = 6.dp, top = 12.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.card).border(1.dp, C.edge, RoundedCornerShape(16.dp)).clickable { open = true }.heightIn(min = 52.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { Text(value.ifBlank { t("Choose a date", "Chagua tarehe") }, color = if (value.isBlank()) C.muted else C.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f)); Icon(Ic.get("cal"), null, tint = C.blue) }
    }
    if (open) {
        val st = rememberDatePickerState(initialSelectedDateMillis = runCatching { LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull())
        DatePickerDialog({ open = false }, confirmButton = {
            TextButton({ st.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }; open = false }) { Text(t("OK", "Sawa")) }
        }, dismissButton = { TextButton({ onChange(""); open = false }) { Text(t("Clear", "Futa")) } }) { DatePicker(st) }
    }
}

/** A list to pick one from (a person, a country). */
@Composable
fun Picker(label: String, value: String, options: List<Pair<String, String>>, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp), color = C.ink, modifier = Modifier.padding(bottom = 6.dp, top = 12.dp))
        Box {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.card).border(1.dp, C.edge, RoundedCornerShape(16.dp)).clickable(role = Role.DropdownList) { open = true }.heightIn(min = 52.dp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { Text(options.firstOrNull { it.first == value }?.second ?: value, style = MaterialTheme.typography.bodyLarge, color = C.text, modifier = Modifier.weight(1f).padding(vertical = 6.dp), maxLines = 2); Icon(Ic.get("down"), null, tint = C.muted) }
            DropdownMenu(open, { open = false }) { options.forEach { (k, l) -> DropdownMenuItem({ Text(l) }, { onChange(k); open = false }) } }
        }
    }
}

@Composable
private fun Repairs(m: AppModel, s: Snapshot, open: (String) -> Unit) {
    val (people, _) = rememberTeam(m)
    val ids = s.repairs.keys.sortedWith(compareBy({ mapOf("urgent" to 0, "month" to 1, "watch" to 2)[s.repairs[it]?.urgency] ?: 3 }, { s.repairs[it]?.at }))
    Text(t("Most urgent first. Changes leave a note on the project in monday.com, and the person assigned sees the job in their app.", "Za haraka kwanza. Mabadiliko yanaacha maelezo kwenye mradi katika monday.com, na aliyepewa anaona kazi kwenye programu yake."), style = MaterialTheme.typography.bodyMedium, color = C.muted)
    if (ids.isEmpty()) EmptyDrop(t("No open repairs.", "Hakuna matengenezo yaliyo wazi."))
    ids.forEach { id -> RepairJob(m, s, id, people.map { it.optString("name") }, open) }
}

@Composable
private fun RepairJob(m: AppModel, s: Snapshot, id: String, names: List<String>, open: (String) -> Unit) {
    val r = s.repairs[id] ?: return
    val p = s.projects.firstOrNull { it.id == id }
    val scope = rememberScope()
    var step by remember(id, r.step) { mutableStateOf(r.step) }
    var who by remember(id, r.assignee) { mutableStateOf(r.assignee) }
    var due by remember(id, r.due) { mutableStateOf(r.due) }
    var budget by remember(id, r.budget) { mutableStateOf(r.budget?.toString() ?: "") }
    var note by remember(id) { mutableStateOf("") }
    var msg by remember(id) { mutableStateOf<Pair<String, String>?>(null) }
    val money = remember(id) { JobMoney() }
    Card(Modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p?.title ?: r.title, style = MaterialTheme.typography.titleMedium, color = C.blue2, modifier = Modifier.weight(1f).clickable { open(id) })
            Tag(if (r.state == "notworking") stateName("notworking") else stateName("repair"), if (r.state == "notworking") C.badBg else C.warnBg, if (r.state == "notworking") C.bad else C.warn)
        }
        Text((p?.country ?: "") + " · " + t("reported by ", "imeripotiwa na ") + r.by + ", " + whenText(r.at) + (if (r.urgency.isNotBlank()) " · " + urgencyName(r.urgency) else ""), style = MaterialTheme.typography.bodySmall, color = C.muted)
        if (r.note.isNotBlank()) Text("“${r.note}”", style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.padding(top = 4.dp))
        if (r.problems.isNotEmpty()) Text(t("Problems: ", "Matatizo: ") + r.problems.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = C.muted)
        if (r.parts.isNotBlank() || r.cost != null) Text(listOfNotNull(r.parts.takeIf { it.isNotBlank() }?.let { t("Parts: ", "Vipuri: ") + it }, r.cost?.let { t("About ", "Takriban ") + Money.kes(it) }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = C.muted)
        listOf("reported" to t("Reported", "Imeripotiwa"), "assigned" to t("Assigned", "Imegawiwa"), "parts" to t("Parts ordered", "Vipuri vimeagizwa"), "fixed" to t("Fixed", "Imerekebishwa")).chunked(2).forEachIndexed { i, pair ->
            Row(Modifier.padding(top = if (i == 0) 10.dp else 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pair.forEach { (k, l) -> Pill(l, step == k, Modifier.weight(1f), color = if (k == "fixed") C.ok else null) { step = k } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Picker(t("Assigned to", "Amepewa"), who, listOf("" to t("Not assigned", "Hajapewa mtu")) + names.map { it to it }, { who = it }, Modifier.weight(1f))
            DateField(t("Due", "Tarehe ya mwisho"), due, { due = it }, Modifier.weight(1f))
        }
        Field(budget, { budget = it.filter { c -> c.isDigit() } }, t("Approved budget (KES)", "Bajeti iliyoidhinishwa (KES)"), number = true)
        Field(note, { note = it }, t("Note (optional)", "Maelezo (si lazima)"), placeholder = t("e.g. parts bought in Eldoret", "mf. vipuri vilinunuliwa Eldoret"))
        JobMoneyFields(m, r, s.approveAbove, money)
        var greenText by remember(id) { mutableStateOf<String?>(null) }
        LaunchedEffect(greenText) { if (greenText != null) { kotlinx.coroutines.delay(2600); greenText = null } }
        Btn(t("Save", "Hifadhi"), {
            scope.launch {
                msg = "" to t("Saving…", "Inahifadhi…")
                val x = m.api.admin(JSONObject().put("action", "job").put("id", id).put("step", step).put("assignee", who).put("due", due).put("budget", budget).put("note", note)
                    .apply { Money.parse(money.actual)?.let { put("actual", it) }; if (money.shots.isNotEmpty()) put("receipt", true); if (note.isNotBlank()) put("what", note) })
                msg = if (x.ok) (jobMoneyDone(m, x, money, s.approveAbove) ?: ("ok" to t("Saved ✓", "Imehifadhiwa ✓"))) else "bad" to reasonText(x.reason)
                if (x.ok && x.json.optString("held").isNotBlank()) step = x.json.optJSONObject("job")?.optString("step")?.ifBlank { null } ?: r.step
                if (x.ok) { note = ""; greenText = if (step == "fixed") t("Fixed", "Imerekebishwa") else t("Saved", "Imehifadhiwa"); m.reload() }
            }
        }, Modifier.padding(top = 12.dp), small = true, icon = "check", saved = greenText)
        msg?.let { (tone, text) -> Msg(text, tone) }
    }
}

@Composable
private fun ReviewQueue(m: AppModel, open: (String) -> Unit) {
    val scope = rememberScope()
    var items by remember { mutableStateOf<List<JSONObject>?>(null) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tick) { val a = m.api.adminGet("review").json.optJSONArray("items") ?: JSONArray(); items = (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("status") == "open" } }
    val list = items
    if (list == null) { Text(t("Loading…", "Inapakia…"), color = C.muted); return }
    if (list.isEmpty()) EmptyDrop(t("Nothing waiting for review.", "Hakuna kinachosubiri kuidhinishwa."))
    list.forEach { it2 ->
        val d = it2.optJSONObject("data") ?: JSONObject()
        var reason by remember(it2.optString("rid")) { mutableStateOf("") }
        var msg by remember(it2.optString("rid")) { mutableStateOf<String?>(null) }
        Card(Modifier.padding(top = 10.dp)) {
            val (icon, kind) = when (it2.optString("kind")) {
                "new" -> "plus" to t("New site", "Eneo jipya"); "move" -> "pin" to t("Big location change", "Mabadiliko makubwa ya mahali")
                "request" -> "lock" to t("Change to a confirmed location", "Badiliko la mahali palipothibitishwa"); "cost" -> "shield" to t("Second approval: repair cost", "Idhini ya pili: gharama ya matengenezo"); else -> "spark" to t("Story moment", "Hadithi")
            }
            Row(verticalAlignment = Alignment.CenterVertically) { IconTile(icon, C.blue, C.tint, 36.dp); Spacer(Modifier.width(10.dp)); Text(kind, style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.weight(1f)); Text(whenText(it2.optString("at")), style = MaterialTheme.typography.bodySmall, color = C.muted) }
            Text(it2.optString("t") + " · " + t("by ", "na ") + it2.optString("by"), style = MaterialTheme.typography.titleSmall, color = C.blue2, modifier = Modifier.padding(top = 8.dp).clickable { open(it2.optString("id")) })
            val canApprove = if (it2.optString("kind") == "cost") CostReviewDetail(it2, m.prefs.name, m.snap.value?.approveAbove ?: org.rafikioutreach.pin.data.Money.APPROVE_DEFAULT) else true
            when (it2.optString("kind")) {
                "cost" -> {}
                "move" -> Text(t("From ", "Kutoka ") + d.optString("from").ifBlank { "—" } + t(" to ", " hadi ") + d.optString("to") + (if (d.has("dist")) " · " + Geo.dist(d.optDouble("dist"), m.prefs.miles) else ""), style = MaterialTheme.typography.bodySmall, color = C.muted)
                "request" -> Text(t("Wants ", "Anataka ") + d.optString("to") + t(" instead of ", " badala ya ") + d.optString("from"), style = MaterialTheme.typography.bodySmall, color = C.muted)
                "story" -> Text("“" + d.optString("quote") + "”" + (d.optString("who").takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""), style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(C.tint).padding(10.dp))
                else -> Text(listOf(d.optString("type"), d.optString("village"), COUNTRIES[d.optString("ck")] ?: "").filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = C.muted)
            }
            Field(reason, { reason = it }, if (it2.optString("kind") == "cost") t("Note (optional)", "Maelezo (si lazima)") else t("Reason (optional)", "Sababu (si lazima)"), placeholder = if (it2.optString("kind") == "cost") t("e.g. quote checked with the driller", "mf. bei imehakikishwa na mchimbaji") else t("e.g. wrong school", "mf. shule isiyo sahihi"))
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fun decide(dec: String) = scope.launch {
                    val x = m.api.admin(JSONObject().put("action", "review").put("rid", it2.optString("rid")).put("decision", dec).put("reason", reason))
                    if (x.ok) { tick++; m.reload() } else msg = if (x.reason == "own") t("You asked for this, so another admin has to approve it.", "Wewe uliomba hili, kwa hiyo msimamizi mwingine lazima aidhinishe.") else reasonText(x.reason)
                }
                Btn(if (it2.optString("kind") == "story") t("Approve for the website", "Idhinisha kwa tovuti") else if (it2.optString("kind") == "cost") t("Approve the cost", "Idhinisha gharama") else t("Approve", "Idhinisha"), { decide("approve") }, ok = true, small = true, icon = "check", enabled = canApprove)
                Btn(if (it2.optString("kind") == "story") t("Don’t use", "Usitumie") else if (it2.optString("kind") == "cost") t("Decline", "Kataa") else t("Reject", "Kataa"), { decide("reject") }, light = true, small = true, enabled = canApprove)
            }
            Msg(msg, "bad")
        }
    }
}

/** Office › Team, from the Figma design (the same as the web app and the iPhone): a navy head with numbers (tap one to
 *  filter) and Add a person, search by name, phone or country, filter chips, and a card per person with a status ring,
 *  Call and WhatsApp right on it, and a red card for someone blocked. Tap a person for their sheet (Contact.kt). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TeamSection(m: AppModel, openProject: (String) -> Unit) {
    val scope = rememberScope()
    val (people, again) = rememberTeam(m)
    var adding by remember { mutableStateOf<JSONObject?>(null) }
    var q by rememberSaveable { mutableStateOf("") }
    var f by rememberSaveable { mutableStateOf("all") }
    val all = people.sortedBy { it.optString("name").lowercase() }
    fun cnt(k: String) = all.count { teamIs(it, k) }
    if (f != "all" && cnt(f) == 0) f = "all"
    val pick: (String) -> Unit = { k -> f = if (f == k && k != "all") "all" else k }
    TeamHero(all, cnt("adm"), cnt("today"), cnt("week"), cnt("inv"), cnt("blk"), f, pick) { adding = JSONObject() }
    Field(q, { q = it }, t("Find a person", "Tafuta mtu"), Modifier.padding(top = 8.dp).testTag("team_q"), placeholder = t("Name, phone or country", "Jina, simu au nchi"))
    FlowRow(Modifier.padding(top = 10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("all" to t("All", "Wote"), "today" to t("Active today", "Hai leo"), "adm" to t("Admins", "Wasimamizi"), "inv" to t("Invited", "Walioalikwa"), "blk" to t("Blocked", "Waliozuiwa")).forEach { (k, label) ->
            val n = if (k == "all") all.size else cnt(k)
            if (k == "all" || n > 0) FilterPill(label, n, f == k, "team_f_$k") { pick(k) }
        }
    }
    val qq = q.trim().lowercase(); val qd = qq.filter { it.isDigit() }
    val list = all.filter { p ->
        teamIs(p, f) && (qq.isEmpty() || listOf("name", "phone", "role", "email").joinToString(" ") { p.optString(it) }.plus(" " + (COUNTRIES[p.optString("home")] ?: "")).lowercase().contains(qq) ||
            (qd.length >= 3 && p.optString("phone").filter { it.isDigit() }.contains(qd)))
    }
    val admins = list.filter { it.optBoolean("admin") }
    val field = list.filterNot { it.optBoolean("admin") }
    var i = 0
    val filtered = qq.isNotEmpty() || f != "all"
    if (!filtered || admins.isNotEmpty()) TeamHead("crown", t("Admins", "Wasimamizi"), admins.size, C.hue("amber"), "team_admins")
    admins.forEach { p -> PersonCard(p, i++) { Contact.show(p.optString("name")) } }
    if (!filtered || field.isNotEmpty()) TeamHead("people", t("Field team", "Timu ya uwanjani"), field.size, C.hue("slate"), "team_field")
    field.forEach { p -> PersonCard(p, i++) { Contact.show(p.optString("name")) } }
    if (filtered && list.isEmpty()) Text(t("Nobody matches.", "Hakuna anayelingana."), style = MaterialTheme.typography.bodyMedium, color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp).testTag("team_none"))
    else if (people.isNotEmpty() && field.isEmpty() && !filtered) Text(t("Nobody here yet. Add a person above.", "Hakuna mtu bado. Ongeza mtu hapo juu."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(4.dp))
    Card(Modifier.padding(top = 18.dp), pad = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val hu = C.hue("teal")
            IconTile("mail", hu.fg, hu.bg, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t("E-mail alerts", "Taarifa kwa barua pepe"), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = C.ink)
                Text(t("Repairs and reviews go to admins with an e-mail", "Matengenezo na mapitio yanaenda kwa wasimamizi wenye barua pepe"), style = MaterialTheme.typography.bodySmall, color = C.muted)
            }
        }
        val mails = all.filter { it.optBoolean("admin") && it.optString("email").let { e -> e.isNotBlank() && e != "null" } }.map { it.optString("email") }
        if (mails.isNotEmpty()) Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            mails.forEach { e -> Row(Modifier.clip(RoundedCornerShape(99.dp)).background(C.hue("slate").bg).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.get("mail"), null, tint = C.ink, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp))
                Text(e, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } }
        }
        var tm by remember { mutableStateOf<String?>(null) }
        Btn(t("Send a test e-mail", "Tuma barua pepe ya majaribio"), { scope.launch { tm = t("Sending…", "Inatuma…"); val x = m.api.admin(JSONObject().put("action", "test-mail")); tm = mailResult(x) } }, Modifier.padding(top = 12.dp).testTag("team_test_mail"), small = true, light = true, icon = "upload")
        Msg(tm)
    }
    adding?.let { pre -> AddSheet(m, pre, close = { adding = null }, added = again) }
}

/** today (used the app today), week (in the last 7 days), old, inv (never signed in), blk (blocked). */
private fun teamState(p: JSONObject): String {
    if (p.optJSONObject("blocked") != null || p.optBoolean("blocked")) return "blk"
    val s = p.optString("lastSeen"); if (s.isBlank() || s == "null") return "inv"
    val at = runCatching { java.time.Instant.parse(s) }.getOrNull() ?: return "inv"
    val z = java.time.ZoneId.systemDefault()
    if (at.atZone(z).toLocalDate() == java.time.LocalDate.now(z)) return "today"
    return if (java.time.Duration.between(at, java.time.Instant.now()).toDays() < 7) "week" else "old"
}
private fun teamIs(p: JSONObject, f: String): Boolean { val st = teamState(p); return when (f) { "all" -> true; "adm" -> p.optBoolean("admin"); "week" -> st == "today" || st == "week"; else -> st == f } }
private val StToday = Color(0xFF22B566); private val StWeek = Color(0xFFF0A630); private val StNone = Color(0xFFAEB8C1); private val StBlk = Color(0xFFD93A2E)
private fun stColor(st: String) = when (st) { "today" -> StToday; "week" -> StWeek; "blk" -> StBlk; else -> StNone }

/** The navy head: how many people and admins, a few faces, four numbers that filter, and Add a person. */
@Composable
private fun TeamHero(all: List<JSONObject>, nAdm: Int, nT: Int, nW: Int, nI: Int, nB: Int, f: String, pick: (String) -> Unit, add: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    val navy = if (C.glassy) androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF0B4F80), Color(0xFF063559), Color(0xFF04263F))) else androidx.compose.ui.graphics.SolidColor(Color(0xFF002A57))
    Column(Modifier.fillMaxWidth().rise(0).clip(shape).background(navy).sheen(700).padding(20.dp).testTag("team_hero")) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(t("OFFICE", "OFISI"), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.8.sp, fontWeight = FontWeight.ExtraBold), color = Color(0xFF9FD3F7))
                Text(t("Your team", "Timu yako"), style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold), color = Color.White)
                Text((if (all.size == 1) t("1 person", "Mtu 1") else t("${all.size} people", "Watu ${all.size}")) + " · " + (if (nAdm == 1) t("1 admin", "Msimamizi 1") else t("$nAdm admins", "Wasimamizi $nAdm")),
                    style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.82f))
            }
            val faces = (all.filter { teamState(it) == "today" } + all.filterNot { teamState(it) == "today" }).take(3)
            Row(Modifier.padding(top = 6.dp)) {
                faces.forEachIndexed { k, p -> val hu = C.hue(teamHue(p.optString("name")))
                    Box(Modifier.offset(x = (-10 * k).dp).size(40.dp).clip(CircleShape).background(Color(0xFF063559)).padding(2.5.dp).clip(CircleShape).background(hu.bg), contentAlignment = Alignment.Center) {
                        Text(inits(p.optString("name")), color = hu.fg, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                    }
                }
                if (all.size > 3) Box(Modifier.offset(x = (-30).dp).size(40.dp).clip(CircleShape).background(Color(0xFF063559)).padding(2.5.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Text("+${all.size - 3}", color = Color(0xFF04263F), fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                }
            }
        }
        Row(Modifier.padding(top = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeroStat("today", nT, StToday, t("today", "leo"), f == "today", true, pick)
            HeroStat("week", nW, Color(0xFF7CC4F5), t("this week", "wiki hii"), f == "week", false, pick)
            HeroStat("inv", nI, StWeek, t("invited", "walioalikwa"), f == "inv", false, pick)
            HeroStat("blk", nB, Color(0xFFFF6B5E), t("blocked", "waliozuiwa"), f == "blk", false, pick)
        }
        val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        Row(Modifier.padding(top = 16.dp).fillMaxWidth().heightIn(min = 54.dp).press(src).clip(RoundedCornerShape(99.dp)).background(Color.White)
            .clickable(interactionSource = src, indication = androidx.compose.material3.ripple(), role = Role.Button, onClick = add).testTag("team_add"),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.get("plus"), null, tint = Color(0xFF0A78C2), modifier = Modifier.size(22.dp)); Spacer(Modifier.width(8.dp))
            Text(t("Add a person", "Ongeza mtu"), color = Color(0xFF0A78C2), fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
        }
    }
}

@Composable
private fun RowScope.HeroStat(k: String, n: Int, dot: Color, label: String, on: Boolean, live: Boolean, pick: (String) -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.weight(1f).heightIn(min = 62.dp).clip(shape).background(if (on) Color.White else Color.White.copy(alpha = 0.1f)).border(1.dp, Color.White.copy(alpha = if (on) 1f else 0.18f), shape)
        .clickable(role = Role.Button) { pick(k) }.padding(horizontal = 10.dp, vertical = 9.dp).testTag("team_s_$k")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BreathDot(dot, live && n > 0, 8.dp); Spacer(Modifier.width(6.dp))
            Text("$n", color = if (on) Color(0xFF04263F) else Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
        }
        Text(label, color = (if (on) Color(0xFF04263F) else Color.White).copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall, maxLines = 2)
    }
}

@Composable
private fun FilterPill(label: String, n: Int, on: Boolean, tag: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(99.dp)
    Row(Modifier.heightIn(min = 44.dp).clip(shape).background(if (on) C.ink else C.card).border(if (C.glassy) 1.dp else 2.dp, if (on) C.ink else C.line, shape)
        .clickable(role = Role.Button, onClick = onClick).padding(start = 16.dp, end = 8.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = if (on) C.card else C.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.width(8.dp))
        Text("$n", color = if (on) C.ink else C.hue("slate").fg, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp,
            modifier = Modifier.clip(shape).background(if (on) C.card else C.hue("slate").bg).padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

/** One person: picture with a status ring and dot, name, role and when they last used the app, tags, then Call and
 *  WhatsApp (or, when blocked, who blocked them). */
@Composable
private fun PersonCard(p: JSONObject, i: Int, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val name = p.optString("name"); val st = teamState(p); val blk = st == "blk"
    val phone = p.optString("phone").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
    val shape = RoundedCornerShape(24.dp)
    val bg = if (blk) androidx.compose.ui.graphics.lerp(C.card, C.badBg, 0.55f) else if (C.glassy) C.glass2 else C.card
    val edge = if (blk) androidx.compose.ui.graphics.lerp(C.line, C.bad, 0.4f) else if (C.glassy) C.glassEdge else C.line
    Column(Modifier.padding(top = 12.dp).rise(i, step = 40).fillMaxWidth().clip(shape).background(bg).border(if (C.glassy) 1.dp else 2.dp, edge, shape).testTag("tm_card_$name")) {
        Row(Modifier.fillMaxWidth().press(src).clickable(interactionSource = src, indication = androidx.compose.material3.ripple(), role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp).testTag("tm_$name"), verticalAlignment = Alignment.CenterVertically) {
            Avatar(name, p.optBoolean("admin"), 56.dp, st)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val role = p.optString("role").takeIf { it.isNotBlank() && it != "null" }
                val seen = if (st == "inv") (if (p.optString("src").ifBlank { p.optString("source") } == "app") t("Invited · hasn’t signed in yet", "Amealikwa · bado hajaingia") else t("Not signed in yet", "Bado hajaingia")) else seenText(p)
                Text((role?.let { "$it · " } ?: "") + seen, style = MaterialTheme.typography.bodySmall, color = if (blk) C.bad else C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Chips(p, long = true)
            }
            Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(18.dp))
        }
        if (blk) {
            HorizontalDivider(color = androidx.compose.ui.graphics.lerp(C.line, C.bad, 0.3f))
            val by = p.optJSONObject("blocked")?.optString("by")?.takeIf { it.isNotBlank() && it != "null" }
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.get("x"), null, tint = C.bad, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text((by?.let { t("Blocked by $it", "Amezuiwa na $it") } ?: t("Blocked", "Amezuiwa")) + " · " + t("can’t sign in", "hawezi kuingia"), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = C.bad)
            }
        } else if (phone.isNotBlank()) {
            HorizontalDivider(color = C.line)
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                QuickPill(t("Call", "Piga"), C.hue("green"), "tm_call_$name", icon = "phone") { runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + phone.replace(" ", "")))) } }
                Spacer(Modifier.width(8.dp))
                QuickPill("WhatsApp", Hue(if (C.dark) Color(0xFF5FD08F) else Color(0xFF1C8A50), WhatsAppGreen.copy(alpha = 0.14f)), "tm_wa_$name", brand = "whatsapp") { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://wa.me/" + waNumber(phone, p.optString("home"))))) } }
                Text(phone, style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f).padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun QuickPill(label: String, hu: Hue, tag: String, icon: String? = null, brand: String? = null, onClick: () -> Unit) {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(Modifier.heightIn(min = 44.dp).press(src).clip(RoundedCornerShape(99.dp)).background(hu.bg).clickable(interactionSource = src, indication = androidx.compose.material3.ripple(), role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        if (brand != null) BrandIcon(brand, 18.dp) else if (icon != null) Icon(Ic.get(icon), null, tint = hu.fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, color = hu.fg, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, maxLines = 1)
    }
}

private val CrownGold = Color(0xFFE0A100)
private val CrownGoldDark = Color(0xFFF2C14E)
private fun teamHue(name: String): String { var h = 7L; name.lowercase().forEach { h = (h * 31 + it.code) % 4294967296L }; return listOf("blue", "green", "purple", "teal", "coral", "slate")[(h % 6).toInt()] }
private fun inits(name: String) = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }.ifBlank { "·" }
private fun seenText(p: JSONObject) = p.optString("lastSeen").takeIf { it.isNotBlank() && it != "null" }?.let { t("Last active ", "Alitumia mwisho ") + whenText(it) } ?: t("Not signed in yet", "Bado hajaingia")

@Composable
private fun TeamHead(icon: String, title: String, n: Int, hu: Hue, tag: String) {
    Row(Modifier.padding(top = 20.dp, bottom = 2.dp, start = 4.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        if (icon == "crown") Icon(Ic.crownFill, null, tint = if (C.dark) CrownGoldDark else CrownGold, modifier = Modifier.size(18.dp))
        else Icon(Ic.get(icon), null, tint = hu.fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.ExtraBold), color = C.muted)
        Spacer(Modifier.width(8.dp))
        Text("$n", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold), color = hu.fg, modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(hu.bg).padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

/** A round picture with initials; admins get a little crown on top. */
@Composable
private fun Avatar(name: String, admin: Boolean, size: androidx.compose.ui.unit.Dp = 44.dp, st: String = "") {
    val hu = C.hue(teamHue(name))
    Box(Modifier.size(size + size * 0.18f)) {
        Box(Modifier.align(Alignment.BottomStart).size(size).clip(CircleShape).background(hu.bg), contentAlignment = Alignment.Center) {
            if (name.isBlank()) Icon(Ic.get("plus"), null, tint = hu.fg, modifier = Modifier.size(size * 0.45f))
            else PhotoOr(name, size) { Text(inits(name), color = hu.fg, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.34f).sp) }
            // the ring goes on top, so it shows round a photo too
            if (st == "today") Box(Modifier.matchParentSize().border(2.5.dp, StToday, CircleShape)) else if (!C.glassy) Box(Modifier.matchParentSize().border(2.dp, C.ink, CircleShape))
        }
        if (st.isNotBlank()) Box(Modifier.align(Alignment.BottomStart).offset(x = size - size * 0.26f, y = -(size * 0.02f)).size(size * 0.26f).clip(CircleShape).background(C.card).padding(2.5.dp).clip(CircleShape).background(stColor(st)).testTag("tm_st_$st"))
        if (admin) Box(Modifier.align(Alignment.TopEnd).size(size * 0.5f).clip(CircleShape).background(C.card).border(1.dp, C.line, CircleShape).padding(size * 0.08f), contentAlignment = Alignment.Center) {
            Icon(Ic.crownFill, t("Admin", "Msimamizi"), tint = if (C.dark) CrownGoldDark else CrownGold, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun Chip(text: String, hu: Hue, icon: String? = null, crown: Boolean = false) {
    Row(Modifier.clip(RoundedCornerShape(99.dp)).background(hu.bg).then(if (!C.glassy) Modifier.border(1.5.dp, hu.fg, RoundedCornerShape(99.dp)) else Modifier).padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        if (crown) Icon(Ic.crownFill, null, tint = hu.fg, modifier = Modifier.size(12.dp)) else if (icon != null) Icon(Ic.get(icon), null, tint = hu.fg, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = hu.fg, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 11.sp), maxLines = 1)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Chips(p: JSONObject, long: Boolean = false, center: Boolean = false) {
    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, if (center) Alignment.CenterHorizontally else Alignment.Start), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (p.optBoolean("admin")) Chip(t("Admin", "Msimamizi"), C.hue("amber"), crown = true)
        when (p.optString("src").ifBlank { p.optString("source") }) {
            "cloudflare" -> Chip(t("PIN set by the office", "PIN ya ofisi"), C.hue("purple"), "key")
            "monday" -> Chip("monday.com", C.hue("blue"), "people")
            else -> Chip(t("PIN from app", "PIN ya programu"), C.hue("green"), "key")
        }
        val h = p.optString("home")
        if (h.isNotBlank() && h != "null") Chip(if (long) COUNTRIES[h] ?: h.uppercase() else h.uppercase(), C.hue("slate"), "pin")
        // blocked people stay on the list, marked (feat-conta)
        if (p.optJSONObject("blocked") != null) Chip(t("Blocked", "Amezuiwa"), Hue(C.bad, C.badBg), "x")
    }
}

@Composable
private fun PersonRow(p: JSONObject, i: Int, onClick: () -> Unit) {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val name = p.optString("name")
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.padding(top = 8.dp).rise(i, step = 40).press(src).fillMaxWidth().clip(shape)
            .background(if (C.glassy) C.glass2 else C.card).border(if (C.glassy) 1.dp else 2.dp, if (C.glassy) C.glassEdge else C.line, shape)
            .clickable(interactionSource = src, indication = androidx.compose.material3.ripple(), role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp).testTag("tm_$name"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name, p.optBoolean("admin"))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Chips(p)
            Text(seenText(p), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 3.dp))
        }
        Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(18.dp))
    }
}

/** Admin on or off, as a switch with a crown. */
@Composable
private fun AdminSwitch(on: Boolean, flip: () -> Unit) {
    val hu = C.hue("amber")
    Row(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(if (C.glassy) 1.dp else 2.dp, C.line, RoundedCornerShape(16.dp)).clickable(role = Role.Switch, onClick = flip).testTag("tm_admin_row").padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(hu.bg).padding(8.dp)) { Icon(Ic.crownFill, null, tint = hu.fg, modifier = Modifier.fillMaxSize()) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t("Admin", "Msimamizi"), style = MaterialTheme.typography.titleSmall, color = C.ink)
            Text(t("Sees the Office and can manage the team", "Anaona Ofisi na anaweza kusimamia timu"), style = MaterialTheme.typography.bodySmall, color = C.muted)
        }
        Spacer(Modifier.width(8.dp))
        Toggle(on, t("Admin", "Msimamizi"), Modifier.testTag("tm_admin").minimumInteractiveComponentSize(), onClick = flip)
    }
}

/** How long a new PIN stays on screen before it is hidden. */
internal const val PIN_SHOWN_SECONDS = 60

/** The message that carries a new PIN to the person (sent by the admin, from their own phone). */
internal fun pinMessage(who: String, pin: String) = t("Hi ${who.substringBefore(' ')}, your RAFIKI Pin PIN is $pin. Open the app (or pin.rafikioutreach.org) and sign in with it. Please keep it to yourself.",
    "Habari ${who.substringBefore(' ')}, PIN yako ya RAFIKI Pin ni $pin. Fungua programu (au pin.rafikioutreach.org) uingie nayo. Tafadhali usimpe mtu mwingine.")

/** A new PIN: shown for a minute with Copy, WhatsApp and SMS, then hidden for good. */
@Composable
internal fun PinShown(who: String, pin: String, phone: String = "", home: String = "") {
    val ctx = LocalContext.current
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    var left by remember(pin) { mutableStateOf(PIN_SHOWN_SECONDS) }
    var copied by remember(pin) { mutableStateOf(false) }
    LaunchedEffect(pin) { while (left > 0) { kotlinx.coroutines.delay(1000); left-- } }
    val ph = phone.trim().takeIf { it.isNotBlank() && it != "null" }
    Card(Modifier.padding(top = 12.dp).testTag("tm_pin_card"), brush = SolidColor(if (left > 0) C.okBg else C.hue("slate").bg)) {
        Text(t("PIN for ", "PIN ya ") + who, style = MaterialTheme.typography.titleSmall, color = if (left > 0) C.ok else C.muted)
        if (left <= 0) {
            Text(t("Hidden now, to keep it safe. If they didn’t get it, make a new PIN.", "Imefichwa sasa, kwa usalama. Kama hawakuipata, tengeneza PIN mpya."), style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.padding(top = 6.dp).testTag("tm_pin_hidden"))
            return@Card
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(pin, style = MaterialTheme.typography.headlineLarge.copy(letterSpacing = 8.sp), color = C.ink, modifier = Modifier.weight(1f).padding(vertical = 6.dp).testTag("tm_pin_shown"))
            Text(t("hides in ${left} s", "inajificha baada ya sekunde $left"), style = MaterialTheme.typography.labelMedium, color = C.muted, modifier = Modifier.testTag("tm_pin_left"))
        }
        Text(t("Send it privately; it can’t be shown again.", "Mtumie kwa siri; haiwezi kuonyeshwa tena."), style = MaterialTheme.typography.bodySmall, color = C.text)
        Btn(if (copied) t("Copied ✓", "Imenakiliwa ✓") else t("Copy", "Nakili"), { clip.setText(androidx.compose.ui.text.AnnotatedString(pin)); copied = true }, Modifier.padding(top = 10.dp).fillMaxWidth().testTag("tm_pin_copy"), icon = "copy", light = true, small = true)
        Row(Modifier.padding(top = 8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("WhatsApp", { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://wa.me/" + (ph?.let { waNumber(it, home) } ?: "") + "?text=" + android.net.Uri.encode(pinMessage(who, pin))))) } },
                Modifier.weight(1f).testTag("tm_pin_wa"), icon = "b:whatsapp", light = true, small = true)
            Btn("SMS", { runCatching { ctx.startActivity(Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:" + (ph?.replace(" ", "") ?: ""))).putExtra("sms_body", pinMessage(who, pin))) } },
                Modifier.weight(1f).testTag("tm_pin_sms"), icon = "phone", light = true, small = true)
        }
    }
}

@Composable
private fun SheetHead(name: String, admin: Boolean, title: String, sub: String, xRoom: Boolean = false, chips: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = if (xRoom) 44.dp else 0.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.rise(0)) { Avatar(name, admin, 64.dp) }
        Text(title, style = MaterialTheme.typography.titleLarge, color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        chips?.invoke()
        Text(sub, style = MaterialTheme.typography.bodySmall, color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSheet(m: AppModel, pre: JSONObject, close: () -> Unit, added: () -> Unit) {
    val scope = rememberScope()
    val state = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(pre.optString("name")) }
    var home by remember { mutableStateOf(pre.optString("home").takeIf { it != "null" } ?: "") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var admin by remember { mutableStateOf(pre.optBoolean("admin")) }
    var done by remember { mutableStateOf<Pair<String, String>?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = close, sheetState = state, containerColor = C.card, modifier = Modifier.testTag("team_add_sheet")) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            val d = done
            if (d != null) {
                SheetHead(d.first, admin, d.first, t("Added to the team ✓", "Ameongezwa kwenye timu ✓"))
                PinShown(d.first, d.second, phone, home)
                Btn(t("Done", "Imekamilika"), { scope.launch { state.hide() }.invokeOnCompletion { close() } }, Modifier.padding(top = 12.dp).fillMaxWidth().testTag("tm_done"), light = true)
            } else {
                SheetHead(name, admin, t("Add a person", "Ongeza mtu"), t("They get their own 4-digit PIN, shown once. Send it to them privately.", "Wanapata PIN yao ya tarakimu 4, inaonyeshwa mara moja. Wapelekee kwa siri."))
                Field(name, { name = it }, t("Name", "Jina"), placeholder = t("e.g. Jessica Anderson", "mf. Jessica Anderson"))
                Picker(t("Home country", "Nchi ya nyumbani"), home, listOf("" to t("All countries", "Nchi zote")) + COUNTRIES.toList(), { home = it })
                Field(email, { email = it }, t("E-mail (optional)", "Barua pepe (si lazima)"), placeholder = t("for alerts", "kwa taarifa"))
                Field(phone, { phone = it }, t("Phone (optional)", "Simu (si lazima)"), placeholder = "+254 712 345 678", phone = true)
                AdminSwitch(admin) { admin = !admin }
                Btn(t("Add and create PIN", "Ongeza na utengeneze PIN"), {
                    busy = true
                    scope.launch {
                        val n = name.trim()
                        val x = m.api.admin(JSONObject().put("action", "team-add").put("name", n).put("home", home).put("email", email.trim()).put("phone", phone.trim()).put("admin", admin))
                        busy = false
                        if (x.ok) { done = n to x.json.optString("pin"); added() }
                        else msg = if (x.reason == "exists") t("Someone with that name is already on the team.", "Mtu mwenye jina hilo tayari yuko kwenye timu.") else reasonText(x.reason)
                    }
                }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("tm_add_go"), icon = "plus", enabled = name.isNotBlank() && !busy)
                Msg(msg, "bad")
            }
        }
    }
}

/** Rough boxes around each country, to spot points that landed in the wrong place. */
private val BOUNDS = mapOf("ke" to doubleArrayOf(-4.9, 5.1, 33.8, 42.0), "tz" to doubleArrayOf(-11.8, -0.9, 29.2, 40.6), "ug" to doubleArrayOf(-1.6, 4.3, 29.5, 35.1), "mw" to doubleArrayOf(-17.2, -9.3, 32.6, 36.0))

@Composable
private fun Cleanup(s: Snapshot, open: (String) -> Unit) {
    val outside = s.projects.filter { p -> p.gps != null && BOUNDS[p.ck]?.let { b -> p.gps.lat !in b[0]..b[1] || p.gps.lng !in b[2]..b[3] } == true }
    val dup = s.projects.filter { it.gps != null }.groupBy { "%.5f,%.5f".format(it.gps!!.lat, it.gps.lng) }.values.filter { it.size > 1 }.flatten()
    val lists = listOf(
        Triple(t("No location", "Hakuna mahali"), t("Open each one and pin it (Find a place by name helps).", "Fungua kila mmoja na uweke mahali (Tafuta mahali kwa jina inasaidia)."), s.projects.filter { it.gps == null }),
        Triple(t("Location outside its country", "Mahali nje ya nchi yake"), t("Usually latitude and longitude swapped, or a typing slip.", "Mara nyingi latitudo na longitudo zimebadilishana, au kosa la kuandika."), outside),
        Triple(t("Same point as another project", "Mahali sawa na mradi mwingine"), t("Probably a copied location. Check which one is right.", "Huenda mahali hapa pamenakiliwa. Hakikisha kipi ni sahihi."), dup),
        Triple(t("No photo", "Hakuna picha"), t("Add a photo to the “Photos of the project” column in monday.com.", "Ongeza picha kwenye safu ya “Photos of the project” katika monday.com."), s.projects.filter { it.photo == null }),
        Triple(t("No visit for over a year", "Hakuna ziara kwa zaidi ya mwaka"), t("Only visits made with the app are known.", "Ziara zilizofanywa kwa programu tu ndizo zinajulikana."), s.projects.filter { it.gps != null && ageDays(s, it) > 365 }),
    )
    // a calm head with the numbers, like the other Office tabs
    val wrongPlace = (outside + dup).map { it.id }.toSet().size
    val todo = lists.flatMap { it.third }.map { it.id }.toSet().size
    DqmHead("clean_head", "spark", "teal", t("Clean-up", "Safisha data"),
        if (todo == 0) t("Every project looks tidy.", "Kila mradi uko sawa.") else t("Fix these to keep the map and monday.com tidy. Tap a project to open it.", "Rekebisha haya ili ramani na monday.com viwe safi. Gusa mradi kuufungua."),
        listOf(DqmNum(lists[0].third.size, t("no location", "bila mahali"), if (lists[0].third.isEmpty()) "ok" else "has"),
            DqmNum(wrongPlace, t("location to check", "mahali pa kukagua"), if (wrongPlace == 0) "ok" else "bad"),
            DqmNum(lists[3].third.size, t("no photo", "bila picha"), if (lists[3].third.isEmpty()) "ok" else "")))
    val looks = listOf("pinq" to "blue", "globe" to "coral", "copy" to "amber", "camera" to "purple", "clock" to "teal")
    val tags: List<(Project) -> String?> = listOf(
        { null },
        { p -> "%.4f, %.4f".format(p.gps!!.lat, p.gps.lng) },
        { null },
        { null },
        { p -> s.visits[p.id]?.at?.let { checkedAgo(it) } },
    )
    lists.forEachIndexed { i, (title, hint, rows) ->
        var more by remember(title) { mutableStateOf(false) }
        val hu = C.hue(looks[i].second)
        Card(Modifier.padding(top = 12.dp).rise(i).testTag("clean_$i"), pad = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(looks[i].first, hu.fg, hu.bg, 42.dp); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = C.ink)
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = C.muted)
                }
                Spacer(Modifier.width(8.dp))
                if (rows.isEmpty()) Tag("✓", C.okBg, C.ok) else Tag("${rows.size}", hu.bg, hu.fg)
            }
            if (rows.isEmpty()) {
                Text(t("All good here.", "Hakuna tatizo hapa."), style = MaterialTheme.typography.labelLarge, color = C.ok, modifier = Modifier.padding(top = 10.dp))
                return@Card
            }
            Spacer(Modifier.height(8.dp))
            if (i == 2) {
                // same point: the projects that share it, together
                val groups = rows.groupBy { "%.4f, %.4f".format(it.gps!!.lat, it.gps.lng) }.entries.toList()
                (if (more) groups else groups.take(2)).forEach { (pt, ps) ->
                    Row(Modifier.padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Ic.get("pin"), null, tint = hu.fg, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp))
                        Text(t("${ps.size} projects at $pt", "Miradi ${ps.size} mahali $pt"), style = MaterialTheme.typography.labelMedium, color = hu.fg)
                    }
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, C.line, RoundedCornerShape(16.dp))) {
                        ps.forEachIndexed { j, p -> if (j > 0) HorizontalDivider(color = C.line); CleanRow(p, null) { open(p.id) } }
                    }
                }
                if (groups.size > 2) MoreBtn(more, groups.size) { more = !more }
            } else {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, C.line, RoundedCornerShape(16.dp))) {
                    (if (more) rows.take(120) else rows.take(4)).forEachIndexed { j, p -> if (j > 0) HorizontalDivider(color = C.line); CleanRow(p, tags[i](p)) { open(p.id) } }
                }
                if (rows.size > 4) MoreBtn(more, rows.size) { more = !more }
            }
        }
    }
}

private fun checkedAgo(iso: String): String = runCatching {
    val days = java.time.Duration.between(java.time.Instant.parse(iso), java.time.Instant.now()).toDays()
    if (days >= 365) t("${days / 365} yr ago", "mwaka ${days / 365} uliopita") else t("$days days ago", "siku $days zilizopita")
}.getOrDefault("")

/** One project in a clean-up list: its kind, name, where it is, and what to fix. */
@Composable
private fun CleanRow(p: Project, tag: String?, onClick: () -> Unit) {
    val (ic, hk) = typeLook(p); val hu = C.hue(hk)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp).testTag("clean_row"), verticalAlignment = Alignment.CenterVertically) {
        IconTile(ic, hu.fg, hu.bg, 36.dp); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.5.sp), color = C.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(p.area, p.country).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (tag != null) Text(tag, style = MaterialTheme.typography.labelSmall, color = C.muted, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(8.dp))
        Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun MoreBtn(more: Boolean, n: Int, onClick: () -> Unit) {
    Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(99.dp)).background(C.tint).clickable(onClick = onClick).padding(vertical = 11.dp).testTag("clean_more"),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text(if (more) t("Show fewer", "Onyesha machache") else t("Show all $n", "Onyesha zote $n"), style = MaterialTheme.typography.labelLarge, color = C.blue2)
        Spacer(Modifier.width(6.dp)); Icon(Ic.get("down"), null, tint = C.blue2, modifier = Modifier.size(16.dp).rotate(if (more) 180f else 0f))
    }
}

private fun csv(rows: List<List<Any?>>): String = rows.joinToString("\r\n") { r -> r.joinToString(",") { v -> val x = (v ?: "").toString(); if (x.any { it == ',' || it == '"' || it == '\n' }) "\"" + x.replace("\"", "\"\"") + "\"" else x } }

@Composable
private fun Export(m: AppModel, s: Snapshot) {
    val ctx = LocalContext.current
    val scope = rememberScope()
    var msg by remember { mutableStateOf<String?>(null) }
    fun share(name: String, text: String) {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, name); f.writeText("﻿" + text)
        val uri = FileProvider.getUriForFile(ctx, "org.rafikioutreach.pin.files", f)
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), name))
    }
    val stamp = LocalDate.now().toString()
    Text(t("CSV files open in Excel and Google Sheets.", "Faili za CSV zinafunguka kwenye Excel na Google Sheets."), style = MaterialTheme.typography.bodyMedium, color = C.muted)
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Btn(t("Projects with locations and status", "Miradi yenye mahali na hali yake"), {
            share("rafiki-projects-$stamp.csv", csv(listOf(listOf("ID", "Project", "Country", "Area", "Type", "Status", "Latitude", "Longitude", "Last visit", "Condition", "Open repair")) +
                s.projects.map { p -> val v = s.visits[p.id]; listOf(p.id, p.title, p.country, p.area, p.type, p.status, p.gps?.lat, p.gps?.lng, v?.at?.take(10), v?.state, s.repairs[p.id]?.state) }))
        }, Modifier.fillMaxWidth(), light = true, icon = "upload")
        Btn(t("Site visits with readings", "Ziara pamoja na vipimo"), {
            scope.launch {
                msg = t("Gathering visit records…", "Inakusanya rekodi za ziara…")
                val x = m.api.adminGet("export"); val a = x.json.optJSONArray("visits")
                if (a == null) { msg = reasonText(x.reason); return@launch }
                msg = null
                val rows = (0 until a.length()).map { a.getJSONObject(it) }.map { v -> val r = v.optJSONObject("readings") ?: JSONObject(); val q = v.optJSONObject("quality") ?: JSONObject()
                    listOf(v.optString("at").take(10), v.optString("id"), s.projects.firstOrNull { it.id == v.optString("id") }?.title, v.optString("by"), v.optString("state"), v.optString("urgency"), r.opt("strokes"), r.opt("flow"), r.opt("users"), r.opt("queue"), q.optString("ecoli")) }
                share("rafiki-visits-$stamp.csv", csv(listOf(listOf("Date", "ID", "Project", "By", "Condition", "Urgency", "Strokes to fill 20 L", "Flow (L/min)", "People using it", "Queue (min)", "E. coli")) + rows))
            }
        }, Modifier.fillMaxWidth(), light = true, icon = "upload")
        Btn(t("Open repairs and jobs", "Matengenezo na kazi zilizo wazi"), {
            share("rafiki-repairs-$stamp.csv", csv(listOf(listOf("ID", "Project", "Condition", "Urgency", "Reported", "By", "Problems", "Parts", "Est. KES", "Step", "Assigned to", "Due", "Budget KES")) +
                s.repairs.map { (id, r) -> listOf(id, s.projects.firstOrNull { it.id == id }?.title ?: r.title, r.state, r.urgency, r.at.take(10), r.by, r.problems.joinToString("; "), r.parts, r.cost, r.step, r.assignee, r.due, r.budget) }))
        }, Modifier.fillMaxWidth(), light = true, icon = "upload")
        Btn(t("Everything the team did (last 13 months)", "Kila kitu timu ilifanya (miezi 13 iliyopita)"), {
            scope.launch {
                if (!m.loadActivity()) { msg = reasonText("offline"); return@launch }
                share("rafiki-activity-$stamp.csv", csv(listOf(listOf("Date", "Time", "Who", "What", "Project", "Result")) + (m.activity.value ?: emptyList()).map { e -> listOf(e.optString("at").take(10), e.optString("at").drop(11).take(5), e.optString("by"), e.optString("a"), e.optString("t"), e.optString("s")) }))
            }
        }, Modifier.fillMaxWidth(), light = true, icon = "upload")
        CostExports(m, s) { msg = it }
        Msg(msg)
    }
}

/** What the test e-mail did: sent, or why not (Resend's own words when it refused). */
internal fun mailResult(x: org.rafikioutreach.pin.data.Reply): String {
    val detail = x.json.optString("detail", "")
    return when {
        x.ok -> t("Sent ✓ Check the inbox.", "Imetumwa ✓ Angalia barua pepe.")
        x.reason == "no-recipients" -> t("No e-mail addresses yet.", "Bado hakuna anwani za barua pepe.")
        x.reason == "no-mail" -> t("Sending e-mail isn’t set up yet. Whoever looks after the website can switch it on.", "Kutuma barua pepe bado hakujawekwa. Anayesimamia tovuti anaweza kuiwasha.")
        detail.isNotBlank() -> t("Not sent. Resend said: ", "Haijatumwa. Resend imesema: ") + detail
        x.status == 0 -> t("Not sent: no connection. Try again with signal.", "Haijatumwa: hakuna mtandao. Jaribu tena ukiwa na mtandao.")
        else -> t("Not sent. Please try again in a moment.", "Haijatumwa. Tafadhali jaribu tena baadaye.")
    }
}


/** Office › Review: a story whose draft is ready to approve; it opens the project's Stories tab. */
@Composable
internal fun StoryDraftItem(it2: JSONObject, open: (String) -> Unit) {
    val id = it2.optString("id")
    Card(Modifier.padding(top = 10.dp).testTag("rv_story_$id")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile("chat", C.hue("purple").fg, C.hue("purple").bg, 36.dp); Spacer(Modifier.width(10.dp))
            Text(t("Story draft ready", "Rasimu ya hadithi iko tayari"), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.weight(1f))
            Text(whenText(it2.optString("at")), style = MaterialTheme.typography.bodySmall, color = C.muted)
        }
        Text(it2.optString("t").ifBlank { id }, style = MaterialTheme.typography.titleMedium, color = C.ink, modifier = Modifier.padding(top = 6.dp))
        if (it2.optString("by").isNotBlank()) Text(t("Drafted by ", "Imeandikwa na ") + it2.optString("by"), style = MaterialTheme.typography.bodySmall, color = C.muted)
        Btn(t("Open the Stories tab", "Fungua kichupo cha Hadithi"), { open(id) }, Modifier.padding(top = 10.dp).fillMaxWidth().testTag("rv_story_open_$id"), icon = "arrow", small = true)
    }
}

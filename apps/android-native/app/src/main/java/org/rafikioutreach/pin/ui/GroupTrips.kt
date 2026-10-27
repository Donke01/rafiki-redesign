package org.rafikioutreach.pin.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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
import org.rafikioutreach.pin.data.GroupTrip
import org.rafikioutreach.pin.data.LatLng
import org.rafikioutreach.pin.data.Reply
import org.rafikioutreach.pin.data.itinReason
import org.rafikioutreach.pin.data.objs
import org.rafikioutreach.pin.data.trips
import org.rafikioutreach.pin.t

private val CURRENCIES = listOf("USD", "KES", "GBP", "EUR", "CAD", "AUD", "TZS", "UGX", "MWK")
/** A total in USD and KES; without exchange rates, each currency on its own (contract v1.1: byCurrency). */
private fun totalLines(o: JSONObject): List<Pair<String, Double>> {
    val both = listOf("USD", "KES").filter { o.has(it) && !o.isNull(it) }.map { it to o.optDouble(it) }
    if (both.isNotEmpty()) return both
    val by = o.optJSONObject("byCurrency") ?: return emptyList()
    return by.keys().asSequence().map { it to by.optDouble(it) }.toList()
}
private fun amt(v: Double, cur: String) = cur + " " + if (v % 1.0 == 0.0) "%,.0f".format(java.util.Locale.US, v) else "%,.2f".format(java.util.Locale.US, v)

/**
 * Office › Trips › Group trips (admins): a volunteer or donor expedition. The details, the group (dietary and medical
 * notes for admins only, a passport-expiry reminder, never a passport number), the day-by-day items (flights, pick-ups,
 * lodging, drilling days linked to real projects, school visits, safari, free time, meals), costs per person and payments,
 * the group page texts, sharing by private link, and e-mail reminders with Preview and Send.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupTripEditor(m: AppModel, id: String?, created: (String) -> Unit, go: (String) -> Unit, openProject: (String, Boolean) -> Unit, back: () -> Unit) {
    val repo = m.trips
    val scope = rememberScope()
    var g by remember(id) { mutableStateOf<GroupTrip?>(null) }
    var err by remember(id) { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(id) { if (id != null) { val (x, e) = repo.group(id); g = x; err = e } }
    fun change(body: JSONObject, then: (Reply) -> Unit = {}) = scope.launch {
        val cur = g ?: return@launch
        val r = repo.groupChange(cur, body)
        r.json.optJSONObject("trip")?.let { g = GroupTrip(it) }
        msg = if (r.ok) null else itinReason(r.reason); then(r)
    }
    var sec by rememberSaveable { mutableStateOf("plan") }
    BackStep(sec != "plan") { sec = "plan" }
    var sheet by remember { mutableStateOf<Pair<String, JSONObject?>?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("gtrip_editor")) {
        if (id == null) { NewGroupTrip(m, created); return@Column }
        val x = g
        if (x == null) { Text(err ?: t("Loading…", "Inapakia…"), color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp)); return@Column }
        Text(x.name, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold), color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("gtrip_title"))
        Text(tripDates(x.from, x.to) + " · " + (COUNTRIES[x.country] ?: x.country.uppercase()) + (if (x.destination.isNotBlank()) " · " + x.destination else ""), style = MaterialTheme.typography.bodyMedium, color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Msg(msg, "bad")
        Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) {
            TripTabs(listOf("plan" to t("Days", "Siku"), "group" to t("Group", "Kikundi"), "money" to t("Costs", "Gharama"), "page" to t("Page", "Ukurasa")), sec, "gt_tab_") { sec = it }
        }
        when (sec) {
            "group" -> {
                TripHead(t("The group", "Kikundi"), extra = "${x.participants.size}")
                Text(t("Dietary and medical notes are for admins only and never on the group page. Passport numbers are never kept.", "Maelezo ya chakula na afya ni kwa wasimamizi pekee na hayako kwenye ukurasa wa kikundi. Namba za pasipoti hazihifadhiwi kamwe."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(horizontal = 4.dp))
                x.participants.forEach { p -> PersonRow(p) { sheet = "person" to p } }
                Btn(t("Add a person", "Ongeza mtu"), { sheet = "person" to null }, Modifier.padding(top = 10.dp).fillMaxWidth().testTag("gt_add_person"), small = true, icon = "plus")
                TripHead(t("Leaders", "Viongozi"))
                Text(x.leaders.joinToString(", ").ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.padding(horizontal = 4.dp))
                TripHead(t("Emergency contacts", "Mawasiliano ya dharura"))
                x.emergency.forEach { e -> Text(e.optString("name") + " · " + e.optString("role") + " · " + e.optString("phone"), style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.padding(4.dp)) }
                Btn(t("Edit the details", "Hariri maelezo"), { sheet = "details" to null }, Modifier.padding(top = 10.dp).fillMaxWidth().testTag("gt_details"), small = true, light = true, outline = true, icon = "cog")
            }
            "money" -> GroupMoney(x, { sheet = "cost" to it }, { sheet = "pay" to it }) { b -> change(b) }
            "page" -> GroupPage(m, x, { b, then -> change(b, then) }, { go("gview:" + x.id) }) { sheet = "texts" to null }
            else -> {
                val days = x.dayItems
                if (days.isEmpty()) EmptyDrop(t("Add the flights, pick-ups, lodging and days of the trip.", "Ongeza ndege, kuchukuliwa, malazi na siku za safari."))
                days.forEach { (date, items) ->
                    TripHead(Dates.weekday(date))
                    items.forEach { i -> ItemRow(i, { sheet = "item" to i }, { sheet = "remind" to i }) }
                }
                Btn(t("Add to the plan", "Ongeza kwenye mpango"), { sheet = "item" to null }, Modifier.padding(top = 12.dp).fillMaxWidth().testTag("gt_add_item"), icon = "plus")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    val gg = g ?: return
    when (val k = sheet?.first) {
        "person" -> PersonSheet(gg, sheet?.second, { sheet = null }) { b -> change(b) }
        "item" -> ItemSheet(m, gg, sheet?.second, { sheet = null }, openProject) { b -> change(b) }
        "remind" -> RemindSheet(m, gg, sheet!!.second!!, { sheet = null }) { x -> g = x }
        "cost" -> CostSheet(sheet?.second, { sheet = null }) { b -> change(b) }
        "pay" -> PaySheet(gg, sheet?.second, { sheet = null }) { b -> change(b) }
        "texts" -> TextsSheet(gg, { sheet = null }) { b -> change(b) }
        "details" -> DetailsSheet(m, gg, { sheet = null }) { b -> change(b) }
        else -> {}
    }
}

@Composable
private fun NewGroupTrip(m: AppModel, created: (String) -> Unit) {
    val scope = rememberScope()
    var name by remember { mutableStateOf("") }
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var ck by remember { mutableStateOf("ke") }
    var dest by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    Text(t("New group trip", "Safari mpya ya kikundi"), style = MaterialTheme.typography.headlineMedium, color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Field(name, { name = it.take(100) }, t("Name", "Jina"), Modifier.testTag("gt_name"), placeholder = t("e.g. Kenya water trip, July 2027", "mf. Safari ya maji Kenya, Julai 2027"))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TripDate(t("Arrive", "Kufika"), from, { from = it; if (to < it) to = it }, Modifier.weight(1f))
        TripDate(t("Leave", "Kuondoka"), to, { to = it }, Modifier.weight(1f))
    }
    Picker(t("Country", "Nchi"), ck, COUNTRIES.toList(), { ck = it })
    Field(dest, { dest = it }, t("Where (optional)", "Wapi (si lazima)"), placeholder = t("e.g. Kitale, Trans Nzoia", "mf. Kitale, Trans Nzoia"))
    Btn(t("Create", "Unda"), {
        scope.launch {
            val r = m.trips.groupCreate(JSONObject().put("name", name.trim()).put("from", from).put("to", to).put("country", ck).put("destination", dest.trim()).put("leaders", JSONArray().put(m.prefs.name))
                .put("packing", GroupTrip.DEFAULT_PACKING).put("expect", GroupTrip.DEFAULT_EXPECT))
            val id = r.json.optJSONObject("trip")?.optString("id")
            if (r.ok && !id.isNullOrBlank()) created(id) else msg = itinReason(r.reason)
        }
    }, Modifier.padding(top = 16.dp).fillMaxWidth().testTag("gt_create"), icon = "check", enabled = name.isNotBlank() && from.isNotBlank() && to.isNotBlank())
    Msg(msg, "bad")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonRow(p: JSONObject, onClick: () -> Unit) {
    Row(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.tint).clickable(role = Role.Button, onClick = onClick).padding(12.dp).testTag("gt_person_" + p.optString("pid")), verticalAlignment = Alignment.CenterVertically) {
        Initials(p.optString("name"), 36.dp); Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(p.optString("name"), style = MaterialTheme.typography.titleSmall, color = C.ink)
            Text(listOf(roleName(p.optString("role")), p.optString("email")).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                p.optString("diet").takeIf { it.isNotBlank() && it != "null" }?.let { TChip(it, "leaf", C.hue("green")) }
                p.optString("medical").takeIf { it.isNotBlank() && it != "null" }?.let { TChip(t("Medical note · admins only", "Maelezo ya afya · wasimamizi pekee"), "lock", Hue(C.bad, C.badBg)) }
                if (p.optBoolean("passportReminder")) TChip(t("Passport expires ", "Pasipoti inaisha ") + p.optString("passportExpiry").takeIf { it.isNotBlank() && it != "null" }.orEmpty(), "clock", C.hue("amber"))
            }
        }
    }
}
private fun roleName(r: String) = when (r) { "donor" -> t("Donor", "Mfadhili"); "leader" -> t("Leader", "Kiongozi"); else -> t("Volunteer", "Mjitolea") }

@Composable
private fun PersonSheet(g: GroupTrip, p: JSONObject?, close: () -> Unit, save: (JSONObject) -> Unit) {
    var name by remember { mutableStateOf(p?.optString("name") ?: "") }
    var email by remember { mutableStateOf(p?.optString("email") ?: "") }
    var phone by remember { mutableStateOf(p?.optString("phone") ?: "") }
    var role by remember { mutableStateOf(p?.optString("role")?.ifBlank { "volunteer" } ?: "volunteer") }
    var diet by remember { mutableStateOf(p?.optString("diet")?.takeIf { it != "null" } ?: "") }
    var med by remember { mutableStateOf(p?.optString("medical")?.takeIf { it != "null" } ?: "") }
    var remind by remember { mutableStateOf(p?.optBoolean("passportReminder") ?: true) }
    var exp by remember { mutableStateOf(p?.optString("passportExpiry")?.takeIf { it != "null" } ?: "") }
    TripSheet("gt_person_sheet", if (p == null) t("Add a person", "Ongeza mtu") else p.optString("name"), close) { hide ->
        Field(name, { name = it }, t("Name", "Jina"), Modifier.testTag("gp_name"))
        Field(email, { email = it.trim() }, t("E-mail", "Barua pepe"), Modifier.testTag("gp_email"))
        Field(phone, { phone = it }, t("Phone", "Simu"), phone = true)
        Choices(listOf("volunteer" to roleName("volunteer"), "donor" to roleName("donor"), "leader" to roleName("leader")), role, "gp_role_") { role = it }
        Field(diet, { diet = it }, t("Dietary needs (admins only)", "Mahitaji ya chakula (wasimamizi pekee)"), placeholder = t("e.g. vegetarian", "mf. mboga tu"))
        Field(med, { med = it }, t("Medical notes (admins only)", "Maelezo ya afya (wasimamizi pekee)"), Modifier.testTag("gp_med"), placeholder = t("e.g. asthma", "mf. pumu"))
        Row(Modifier.padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Switch) { remind = !remind }.padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(remind, { remind = it }); Text(t("Remind them to check their passport", "Wakumbushe kuangalia pasipoti yao"), style = MaterialTheme.typography.bodyMedium, color = C.text)
        }
        if (remind) Field(exp, { exp = it.filter { c -> c.isDigit() || c == '-' }.take(7) }, t("Passport expiry month (YYYY-MM)", "Mwezi pasipoti inaisha (YYYY-MM)"), placeholder = "2027-02")
        Text(t("Never type a passport number here.", "Usiandike namba ya pasipoti hapa kamwe."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 4.dp))
        Btn(t("Save", "Hifadhi"), {
            val pp = JSONObject().put("name", name.trim()).put("email", email).put("phone", phone.trim()).put("role", role).put("diet", diet.trim()).put("medical", med.trim()).put("passportReminder", remind).put("passportExpiry", exp.takeIf { it.length == 7 } ?: JSONObject.NULL)
            if (p != null) pp.put("pid", p.optString("pid"))
            save(JSONObject().put("action", if (p == null) "participant-add" else "participant-update").put("participant", pp).apply { p?.let { put("pid", it.optString("pid")) } }); hide()
        }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("gp_save"), icon = "check", enabled = name.isNotBlank())
        if (p != null) Text(t("Remove from the group", "Ondoa kwenye kikundi"), color = C.bad, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(99.dp)).clickable(role = Role.Button) { save(JSONObject().put("action", "participant-remove").put("pid", p.optString("pid"))); hide() }.heightIn(min = 44.dp).padding(12.dp))
    }
}

/** One item of the day: its time, kind, title, place and details (flight, lodging, project). */
@Composable
private fun ItemRow(i: JSONObject, onClick: (() -> Unit)?, remind: (() -> Unit)?) {
    val k = i.optString("kind"); val hu = C.hue(GroupTrip.kindHue(k))
    Row(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (C.glassy) C.glass2 else C.card).then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier).padding(12.dp).testTag("gt_item_" + i.optString("iid")),
        verticalAlignment = Alignment.Top) {
        Column(Modifier.width(52.dp)) {
            Text(i.optString("start").takeIf { it.isNotBlank() && it != "null" } ?: "", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold), color = C.ink, maxLines = 1, softWrap = false)
            Text(i.optString("end").takeIf { it.isNotBlank() && it != "null" } ?: "", style = MaterialTheme.typography.labelSmall, color = C.muted, maxLines = 1, softWrap = false)
        }
        IconTile(GroupTrip.kindIcon(k), hu.fg, hu.bg, 36.dp); Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(i.optString("title").ifBlank { GroupTrip.kindName(k) }, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold), color = C.ink)
            i.optJSONObject("flight")?.let { f -> Text(listOf(f.optString("number"), f.optString("from") + "→" + f.optString("to"), f.optString("depart") + "–" + f.optString("arrive")).filter { it.isNotBlank() && it != "→" && it != "–" }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = C.text) }
            i.optJSONObject("lodging")?.let { l -> Text(listOf(l.optString("name"), l.optString("address")).filter { it.isNotBlank() && it != "null" }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = C.text) }
            i.optJSONObject("location")?.optString("name")?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = C.muted) }
            i.optString("notes").takeIf { it.isNotBlank() && it != "null" }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = C.muted) }
            i.optJSONObject("remind")?.let { r -> r.optString("sentAt").takeIf { it.isNotBlank() && it != "null" }?.let { s -> Text(t("Reminder sent ", "Kikumbusho kimetumwa ") + whenText(s), style = MaterialTheme.typography.labelSmall, color = C.ok) } }
        }
        if (remind != null) Box(Modifier.size(44.dp).clip(RoundedCornerShape(99.dp)).clickable(role = Role.Button, onClick = remind).testTag("gt_remind_" + i.optString("iid")), contentAlignment = Alignment.Center) {
            Icon(Ic.get("bell"), t("E-mail a reminder", "Tuma kikumbusho"), tint = C.blue2, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ItemSheet(m: AppModel, g: GroupTrip, i: JSONObject?, close: () -> Unit, openProject: (String, Boolean) -> Unit, save: (JSONObject) -> Unit) {
    val snap = m.snap.collectAsState().value
    var kind by remember { mutableStateOf(i?.optString("kind")?.ifBlank { null } ?: "project") }
    var date by remember { mutableStateOf(i?.optString("date") ?: g.from) }
    var start by remember { mutableStateOf(i?.optString("start")?.takeIf { it != "null" } ?: "") }
    var end by remember { mutableStateOf(i?.optString("end")?.takeIf { it != "null" } ?: "") }
    var title by remember { mutableStateOf(i?.optString("title") ?: "") }
    var place by remember { mutableStateOf(i?.optJSONObject("location")?.optString("name") ?: "") }
    var at by remember { mutableStateOf(i?.optJSONObject("location")?.let { l -> if (l.has("lat")) LatLng(l.optDouble("lat"), l.optDouble("lng")) else null }) }
    var notes by remember { mutableStateOf(i?.optString("notes")?.takeIf { it != "null" } ?: "") }
    val fl = i?.optJSONObject("flight") ?: JSONObject()
    var airline by remember { mutableStateOf(fl.optString("airline")) }
    var number by remember { mutableStateOf(fl.optString("number")) }
    var fFrom by remember { mutableStateOf(fl.optString("from")) }
    var fTo by remember { mutableStateOf(fl.optString("to")) }
    val lo = i?.optJSONObject("lodging") ?: JSONObject()
    var lName by remember { mutableStateOf(lo.optString("name")) }
    var lAddr by remember { mutableStateOf(lo.optString("address")) }
    var lContact by remember { mutableStateOf(lo.optString("contact")) }
    var pid by remember { mutableStateOf(i?.optString("projectId")?.takeIf { it != "null" } ?: "") }
    var remind by remember { mutableStateOf(i?.optJSONObject("remind")?.optString("before")?.takeIf { it != "null" } ?: "") }
    var map by remember { mutableStateOf(false) }
    TripSheet("gt_item_sheet", if (i == null) t("Add to the plan", "Ongeza kwenye mpango") else i.optString("title"), close) { hide ->
        Choices(GroupTrip.KINDS.map { it to GroupTrip.kindName(it) }, kind, "gi_k_") { kind = it }
        TripDate(t("Day", "Siku"), date, { date = it })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(start, { start = it.take(5) }, t("From", "Kuanzia"), Modifier.weight(1f).testTag("gi_start"), placeholder = "09:00")
            Field(end, { end = it.take(5) }, t("To", "Hadi"), Modifier.weight(1f), placeholder = "11:00")
        }
        Field(title, { title = it }, t("Title", "Kichwa"), Modifier.testTag("gi_title"), placeholder = GroupTrip.kindName(kind))
        if (kind == "flight") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Field(airline, { airline = it }, t("Airline", "Shirika la ndege"), Modifier.weight(1f), placeholder = "KQ"); Field(number, { number = it.uppercase() }, t("Flight", "Ndege"), Modifier.weight(1f).testTag("gi_flight"), placeholder = "KQ 640") }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Field(fFrom, { fFrom = it.uppercase().take(4) }, t("From airport", "Kutoka uwanja"), Modifier.weight(1f), placeholder = "NBO"); Field(fTo, { fTo = it.uppercase().take(4) }, t("To airport", "Hadi uwanja"), Modifier.weight(1f), placeholder = "EDL") }
        }
        if (kind == "lodging") { Field(lName, { lName = it }, t("Lodging", "Malazi")); Field(lAddr, { lAddr = it }, t("Address", "Anwani")); Field(lContact, { lContact = it }, t("Contact", "Mawasiliano")) }
        if (kind == "project" && snap != null) Picker(t("Project", "Mradi"), pid, listOf("" to t("Not linked", "Haijaunganishwa")) + snap.projects.filter { it.ck == g.country || g.country.isBlank() }.map { it.id to it.title }, { p -> pid = p
            snap.projects.firstOrNull { it.id == p }?.let { pr -> if (title.isBlank()) title = pr.title; if (place.isBlank()) place = pr.title; if (at == null) at = pr.gps } }, Modifier.testTag("gi_project"))
        Field(place, { place = it }, t("Place", "Mahali"), placeholder = t("e.g. Eldoret airport", "mf. Uwanja wa ndege Eldoret"))
        Text(if (at == null) t("Put it on the map", "Weka kwenye ramani") else t("On the map ✓ (change)", "Kwenye ramani ✓ (badilisha)"), color = C.blue2, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(99.dp)).clickable(role = Role.Button) { map = !map }.heightIn(min = 44.dp).padding(horizontal = 4.dp, vertical = 12.dp))
        if (map) Box(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(18.dp))) { PickMap(at ?: LatLng(0.52, 35.27), { at = it }, Modifier.fillMaxSize()) }
        Field(notes, { notes = it }, t("Notes", "Maelezo"), lines = 2)
        TripHead(t("E-mail reminder", "Kikumbusho kwa barua pepe"))
        Choices(listOf("" to t("None", "Hakuna"), "24h" to t("A day before", "Siku moja kabla"), "3h" to t("3 hours before", "Saa 3 kabla")), remind, "gi_r_") { remind = it }
        if (pid.isNotBlank()) Text(t("Open the project page", "Fungua ukurasa wa mradi"), color = C.blue2, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(99.dp)).clickable(role = Role.Button) { hide(); openProject(pid, false) }.heightIn(min = 44.dp).padding(horizontal = 4.dp, vertical = 12.dp))
        Btn(t("Save", "Hifadhi"), {
            val it2 = JSONObject().put("date", date).put("start", start.ifBlank { JSONObject.NULL }).put("end", end.ifBlank { JSONObject.NULL }).put("kind", kind).put("title", title.trim().ifBlank { GroupTrip.kindName(kind) }).put("notes", notes.trim())
                .put("location", if (place.isBlank() && at == null) JSONObject.NULL else JSONObject().put("name", place.trim()).apply { at?.let { put("lat", it.lat); put("lng", it.lng) } })
                .put("flight", if (kind == "flight") JSONObject().put("airline", airline).put("number", number).put("from", fFrom).put("to", fTo).put("depart", start).put("arrive", end) else JSONObject.NULL)
                .put("lodging", if (kind == "lodging") JSONObject().put("name", lName).put("address", lAddr).put("contact", lContact).put("checkIn", start).put("checkOut", end) else JSONObject.NULL)
                .put("projectId", pid.ifBlank { JSONObject.NULL }).put("remind", JSONObject().put("before", remind.ifBlank { JSONObject.NULL }))
            if (i != null) it2.put("iid", i.optString("iid"))
            save(JSONObject().put("action", if (i == null) "item-add" else "item-update").put("item", it2).apply { i?.let { put("iid", it.optString("iid")) } }); hide()
        }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("gi_save"), icon = "check", enabled = date.isNotBlank())
        if (i != null) Text(t("Remove from the plan", "Ondoa kwenye mpango"), color = C.bad, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(99.dp)).clickable(role = Role.Button) { save(JSONObject().put("action", "item-remove").put("iid", i.optString("iid"))); hide() }.heightIn(min = 44.dp).padding(12.dp))
    }
}

/** E-mail the group a reminder about an item: Preview first, then Send (admin-triggered, through /api/mail). */
@Composable
private fun RemindSheet(m: AppModel, g: GroupTrip, i: JSONObject, close: () -> Unit, updated: (GroupTrip) -> Unit) {
    val scope = rememberScope()
    var prev by remember { mutableStateOf<JSONObject?>(null) }
    var to by remember { mutableStateOf(0) }
    var missing by remember { mutableStateOf(0) }
    var res by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    // the preview is asked for here, so its answer lands in this sheet whatever the editor behind it does
    LaunchedEffect(i.optString("iid")) {
        val r = m.trips.groupChange(g, JSONObject().put("action", "remind").put("iid", i.optString("iid")).put("send", false))
        if (r.ok) { prev = r.json.optJSONObject("preview") ?: JSONObject(); to = r.json.optInt("to"); missing = r.json.optInt("missing") } else err = itinReason(r.reason)
    }
    TripSheet("gt_remind_sheet", t("E-mail a reminder", "Tuma kikumbusho"), close) { _ ->
        val p = prev
        if (p == null) { Text(err ?: t("Loading…", "Inapakia…"), color = if (err != null) C.bad else C.muted); return@TripSheet }
        Text(if (to == 1) t("To 1 person", "Kwa mtu 1") else t("To $to people", "Kwa watu $to") + (if (missing > 0) t(" ($missing without an e-mail)", " ($missing bila barua pepe)") else ""), style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.testTag("gr_to"))
        Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.tint).padding(14.dp).testTag("gr_preview")) {
            Text(p.optString("subject"), style = MaterialTheme.typography.titleSmall, color = C.ink)
            Text(p.optString("text"), style = MaterialTheme.typography.bodySmall, color = C.text, modifier = Modifier.padding(top = 6.dp))
        }
        Btn(t("Send", "Tuma"), {
            scope.launch {
                val r = m.trips.groupChange(g, JSONObject().put("action", "remind").put("iid", i.optString("iid")).put("send", true))
                res = if (r.ok) t("Sent to ${r.json.optInt("sent")} ✓", "Imetumwa kwa ${r.json.optInt("sent")} ✓") else itinReason(r.reason)
                r.json.optJSONObject("trip")?.let { updated(GroupTrip(it)) }
            }
        }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("gr_send"), icon = "mail", enabled = to > 0 && res == null)
        Msg(res, "ok")
    }
}

/** Costs per person (any currency) and payments received, with totals in USD and KES from the server. */
@Composable
private fun GroupMoney(g: GroupTrip, editCost: (JSONObject?) -> Unit, editPay: (JSONObject?) -> Unit, change: (JSONObject) -> Unit) {
    val tot = g.totals
    Card(Modifier.padding(top = 12.dp).testTag("gt_totals")) {
        Row {
            Column(Modifier.weight(1f)) {
                Text(t("Per person", "Kwa kila mtu"), style = MaterialTheme.typography.labelMedium, color = C.muted)
                tot?.optJSONObject("perPerson")?.let { pp -> totalLines(pp).forEach { (c, v) -> Text(amt(v, c), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = C.ink) } }
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(t("Whole group", "Kikundi kizima"), style = MaterialTheme.typography.labelMedium, color = C.muted)
                tot?.optJSONObject("group")?.let { pp -> totalLines(pp).forEach { (c, v) -> Text(amt(v, c), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = C.ink, textAlign = TextAlign.End) } }
            }
        }
        tot?.optJSONObject("fx")?.let { fx -> Text(t("Rate: 1 USD = ", "Kiwango: USD 1 = ") + "KES " + "%.2f".format(java.util.Locale.US, fx.optDouble("USDKES")) + " · " + Dates.day(fx.optString("at")), style = MaterialTheme.typography.labelSmall, color = C.muted, modifier = Modifier.padding(top = 6.dp)) }
    }
    TripHead(t("Costs", "Gharama"), extra = if (g.costs.isNotEmpty()) "${g.costs.size}" else "")
    g.costs.forEach { c -> Row(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.tint).clickable { editCost(c) }.padding(12.dp).testTag("gt_cost_" + c.optString("cid")), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(c.optString("label"), style = MaterialTheme.typography.titleSmall, color = C.ink); Text(if (c.optString("per") == "group") t("for the group", "kwa kikundi") else t("per person", "kwa kila mtu"), style = MaterialTheme.typography.bodySmall, color = C.muted) }
        Text(amt(c.optDouble("amount"), c.optString("currency")), style = MaterialTheme.typography.titleSmall, color = C.ink)
    } }
    Btn(t("Add a cost", "Ongeza gharama"), { editCost(null) }, Modifier.padding(top = 8.dp).fillMaxWidth().testTag("gt_add_cost"), small = true, light = true, outline = true, icon = "coins")
    TripHead(t("Payments", "Malipo"))
    g.participants.forEach { p ->
        val pid = p.optString("pid")
        val paid = tot?.optJSONObject("paid")?.optJSONObject(pid); val due = tot?.optJSONObject("due")?.optJSONObject(pid)
        Row(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.tint).clickable { editPay(JSONObject().put("pid", pid)) }.padding(12.dp).testTag("gt_pay_$pid"), verticalAlignment = Alignment.CenterVertically) {
            Initials(p.optString("name"), 30.dp); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(p.optString("name"), style = MaterialTheme.typography.titleSmall, color = C.ink)
                Text(t("Paid ", "Amelipa ") + (paid?.optDouble("USD")?.takeIf { !it.isNaN() }?.let { amt(it, "USD") } ?: "—") + (due?.optDouble("USD")?.takeIf { !it.isNaN() && it > 0 }?.let { t(" · due ", " · anadaiwa ") + amt(it, "USD") } ?: ""), style = MaterialTheme.typography.bodySmall, color = if ((due?.optDouble("USD") ?: 0.0) > 0) C.warn else C.ok)
            }
            Icon(Ic.get("plus"), t("Add a payment", "Ongeza malipo"), tint = C.blue2, modifier = Modifier.size(18.dp))
        }
        g.payments.filter { it.optString("pid") == pid }.forEach { y -> Row(Modifier.padding(start = 40.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Dates.day(y.optString("at")) + " · " + amt(y.optDouble("amount"), y.optString("currency")), style = MaterialTheme.typography.bodySmall, color = C.text, modifier = Modifier.weight(1f))
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(99.dp)).clickable { change(JSONObject().put("action", "payment-remove").put("payid", y.optString("payid"))) }, contentAlignment = Alignment.Center) { Icon(Ic.get("trash"), t("Remove", "Ondoa"), tint = C.muted, modifier = Modifier.size(16.dp)) }
        } }
    }
}

@Composable
private fun CostSheet(c: JSONObject?, close: () -> Unit, save: (JSONObject) -> Unit) {
    var label by remember { mutableStateOf(c?.optString("label") ?: "") }
    var amount by remember { mutableStateOf(c?.optDouble("amount")?.takeIf { !it.isNaN() }?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: "") }
    var cur by remember { mutableStateOf(c?.optString("currency")?.ifBlank { null } ?: "USD") }
    var per by remember { mutableStateOf(c?.optString("per")?.ifBlank { null } ?: "person") }
    TripSheet("gt_cost_sheet", if (c == null) t("Add a cost", "Ongeza gharama") else c.optString("label"), close) { hide ->
        Field(label, { label = it }, t("What", "Nini"), Modifier.testTag("gc_label"), placeholder = t("e.g. Lodging", "mf. Malazi"))
        Field(amount, { amount = it.filter { ch -> ch.isDigit() || ch == '.' } }, t("Amount", "Kiasi"), Modifier.testTag("gc_amount"), number = true)
        Choices(CURRENCIES.map { it to it }, cur, "gc_cur_") { cur = it }
        Choices(listOf("person" to t("Per person", "Kwa kila mtu"), "group" to t("For the group", "Kwa kikundi")), per, "gc_per_") { per = it }
        Btn(t("Save", "Hifadhi"), {
            if (c != null) save(JSONObject().put("action", "cost-remove").put("cid", c.optString("cid")))
            save(JSONObject().put("action", "cost-add").put("cost", JSONObject().put("label", label.trim()).put("amount", amount.toDouble()).put("currency", cur).put("per", per))); hide()
        }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("gc_save"), icon = "check", enabled = label.isNotBlank() && amount.toDoubleOrNull() != null)
        if (c != null) Text(t("Remove", "Ondoa"), color = C.bad, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(99.dp)).clickable(role = Role.Button) { save(JSONObject().put("action", "cost-remove").put("cid", c.optString("cid"))); hide() }.heightIn(min = 44.dp).padding(12.dp))
    }
}

@Composable
private fun PaySheet(g: GroupTrip, y: JSONObject?, close: () -> Unit, save: (JSONObject) -> Unit) {
    val pid = y?.optString("pid") ?: ""
    var amount by remember { mutableStateOf("") }
    var cur by remember { mutableStateOf("USD") }
    var at by remember { mutableStateOf(Dates.today().toString()) }
    var note by remember { mutableStateOf("") }
    TripSheet("gt_pay_sheet", t("Payment from ", "Malipo kutoka kwa ") + (g.participants.firstOrNull { it.optString("pid") == pid }?.optString("name") ?: ""), close) { hide ->
        Field(amount, { amount = it.filter { ch -> ch.isDigit() || ch == '.' } }, t("Amount", "Kiasi"), Modifier.testTag("gy_amount"), number = true)
        Choices(CURRENCIES.map { it to it }, cur, "gy_cur_") { cur = it }
        TripDate(t("Received", "Imepokelewa"), at, { at = it })
        Field(note, { note = it }, t("Note (optional)", "Maelezo (si lazima)"))
        Btn(t("Save", "Hifadhi"), { save(JSONObject().put("action", "payment-add").put("payment", JSONObject().put("pid", pid).put("amount", amount.toDouble()).put("currency", cur).put("at", at).put("note", note.trim()))); hide() },
            Modifier.padding(top = 14.dp).fillMaxWidth().testTag("gy_save"), icon = "check", enabled = amount.toDoubleOrNull() != null)
    }
}

/** The group page: share it by private link (it updates live), see it as the group does, edit its texts. */
@Composable
private fun GroupPage(m: AppModel, g: GroupTrip, ask: (JSONObject, (Reply) -> Unit) -> Unit, preview: () -> Unit, texts: () -> Unit) {
    val ctx = LocalContext.current
    var url by remember(g.id) { mutableStateOf(g.shareUrl) }
    fun withLink(f: (String) -> Unit) { if (url.isNotBlank()) f(url) else ask(JSONObject().put("action", "share")) { r -> r.json.optString("url").takeIf { it.isNotBlank() }?.let { u -> url = u; f(u) } } }
    TripHead(t("The group page", "Ukurasa wa kikundi"))
    Text(t("A private, read-only page in English: the plan, packing list, what to expect, emergency contacts and the map. It changes when you change the plan. Never shows payments or dietary and medical notes.", "Ukurasa wa faragha wa kusoma tu, kwa Kiingereza: mpango, orodha ya mzigo, cha kutarajia, mawasiliano ya dharura na ramani. Unabadilika ukibadilisha mpango. Hauonyeshi malipo wala maelezo ya chakula na afya."),
        style = MaterialTheme.typography.bodyMedium, color = C.muted)
    if (url.isNotBlank()) Text(url, style = MaterialTheme.typography.bodySmall, color = C.blue2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp).testTag("gt_url"))
    Row(Modifier.padding(top = 10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn("WhatsApp", { withLink { u -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/?text=" + Uri.encode(g.name + "\n" + u)))) } } }, Modifier.weight(1f).testTag("gt_share_wa"), small = true, light = true, outline = true, icon = "b:whatsapp")
        Btn(t("E-mail", "Barua pepe"), { withLink { u -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("mailto:?subject=" + Uri.encode(g.name) + "&body=" + Uri.encode(u)))) } } }, Modifier.weight(1f).testTag("gt_share_mail"), small = true, light = true, outline = true, icon = "mail")
    }
    Btn(t("See the group page", "Ona ukurasa wa kikundi"), preview, Modifier.padding(top = 8.dp).fillMaxWidth().testTag("gt_preview"), small = true, icon = "arrow")
    Btn(t("Packing list, what to expect, contacts", "Mzigo, cha kutarajia, mawasiliano"), texts, Modifier.padding(top = 8.dp).fillMaxWidth().testTag("gt_texts"), small = true, light = true, outline = true, icon = "text")
    if (url.isNotBlank()) Text(t("Stop sharing the link", "Acha kushiriki kiungo"), color = C.bad, style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(99.dp)).clickable(role = Role.Button) { ask(JSONObject().put("action", "unshare")) { r -> if (r.ok) url = "" } }.heightIn(min = 44.dp).padding(horizontal = 6.dp, vertical = 12.dp))
    TripHead(t("On the website", "Kwenye tovuti"))
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.tint).clickable(role = Role.Switch) { ask(JSONObject().put("action", "update").put("public", !g.public)) {} }.padding(14.dp).testTag("gt_public"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(t("List it on the trips page", "Iorodheshe kwenye ukurasa wa safari"), style = MaterialTheme.typography.titleSmall, color = C.ink)
            Text(t("Name, dates, a short description and \"Ask to join\".", "Jina, tarehe, maelezo mafupi na \"Omba kujiunga\"."), style = MaterialTheme.typography.bodySmall, color = C.muted)
        }
        androidx.compose.material3.Switch(g.public, { v -> ask(JSONObject().put("action", "update").put("public", v)) {} })
    }
}

@Composable
private fun TextsSheet(g: GroupTrip, close: () -> Unit, save: (JSONObject) -> Unit) {
    var pack by remember { mutableStateOf(g.packing.ifBlank { GroupTrip.DEFAULT_PACKING }) }
    var expect by remember { mutableStateOf(g.expect.ifBlank { GroupTrip.DEFAULT_EXPECT }) }
    var em by remember { mutableStateOf(g.emergency.joinToString("\n") { e -> listOf(e.optString("name"), e.optString("role"), e.optString("phone")).joinToString(" | ") }) }
    var summary by remember { mutableStateOf(g.summary) }
    TripSheet("gt_texts_sheet", t("The group page", "Ukurasa wa kikundi"), close) { hide ->
        Field(summary, { summary = it }, t("Short description (website)", "Maelezo mafupi (tovuti)"), lines = 2)
        Field(pack, { pack = it }, t("Packing list", "Orodha ya mzigo"), Modifier.testTag("gx_pack"), lines = 5)
        Field(expect, { expect = it }, t("What to expect", "Cha kutarajia"), lines = 5)
        Field(em, { em = it }, t("Emergency contacts: name | role | phone, one a line", "Mawasiliano ya dharura: jina | kazi | simu, moja kwa mstari"), lines = 3)
        Btn(t("Save", "Hifadhi"), {
            val list = JSONArray(em.lines().filter { it.isNotBlank() }.map { l -> val p = l.split("|").map { it.trim() }; JSONObject().put("name", p.getOrElse(0) { "" }).put("role", p.getOrElse(1) { "" }).put("phone", p.getOrElse(2) { "" }) })
            save(JSONObject().put("action", "update").put("packing", pack).put("expect", expect).put("emergency", list).put("summary", summary.trim())); hide()
        }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("gx_save"), icon = "check")
    }
}

@Composable
private fun DetailsSheet(m: AppModel, g: GroupTrip, close: () -> Unit, save: (JSONObject) -> Unit) {
    var name by remember { mutableStateOf(g.name) }
    var from by remember { mutableStateOf(g.from) }
    var to by remember { mutableStateOf(g.to) }
    var dest by remember { mutableStateOf(g.destination) }
    var leaders by remember { mutableStateOf(g.leaders.joinToString(", ")) }
    var contact by remember { mutableStateOf(g.contact) }
    TripSheet("gt_details_sheet", t("Details", "Maelezo"), close) { hide ->
        Field(name, { name = it }, t("Name", "Jina"))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { TripDate(t("Arrive", "Kufika"), from, { from = it }, Modifier.weight(1f)); TripDate(t("Leave", "Kuondoka"), to, { to = it }, Modifier.weight(1f)) }
        Field(dest, { dest = it }, t("Where", "Wapi"))
        Field(leaders, { leaders = it }, t("Leaders (comma between names)", "Viongozi (koma kati ya majina)"))
        Field(contact, { contact = it }, t("Contact e-mail", "Barua pepe ya mawasiliano"))
        Btn(t("Save", "Hifadhi"), { save(JSONObject().put("action", "update").put("name", name.trim()).put("from", from).put("to", to).put("destination", dest.trim()).put("contact", contact.trim())
            .put("leaders", JSONArray(leaders.split(",").map { it.trim() }.filter { it.isNotBlank() }))); hide() }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("gd_save"), icon = "check", enabled = name.isNotBlank())
    }
}

/** Staff: the group page as the group sees it (from the full record; never the private fields). */
@Composable
fun GroupTripPreview(m: AppModel, id: String, back: () -> Unit) {
    var g by remember(id) { mutableStateOf<GroupTrip?>(null) }
    LaunchedEffect(id) { g = m.trips.group(id).first }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        val x = g
        if (x == null) Text(t("Loading…", "Inapakia…"), color = C.muted) else GroupTripView(x)
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * The group page (participants, and donors in donor mode): the plan day by day, the map, packing, what to expect and
 * emergency contacts. Only the public fields are read.
 */
@Composable
fun GroupTripView(g: GroupTrip) {
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(26.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(DeepBrush).padding(20.dp).testTag("gview_hero"), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(t("YOUR TRIP", "SAFARI YAKO"), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp, fontWeight = FontWeight.ExtraBold), color = Color(0xFF9FD3F7))
        Text(g.name, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold), color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        Text(tripDates(g.from, g.to) + (if (g.destination.isNotBlank()) " · " + g.destination else ""), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center)
        if (g.summary.isNotBlank()) Text(g.summary, style = MaterialTheme.typography.bodyMedium, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
    }
    if (g.map.isNotEmpty()) Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(190.dp).clip(RoundedCornerShape(20.dp)).testTag("gview_map")) {
        ProjectsMap(g.map.mapIndexed { i, (n, ll) -> Dot("g$i", ll, 0xFF6E46C8.toInt(), false, n) }, null, {}, Modifier.fillMaxSize(), fitKey = g.map.size)
    }
    g.dayItems.forEach { (date, items) ->
        TripHead(Dates.weekday(date))
        items.forEach { i -> ItemRow(i, null, null) }
    }
    if (g.packing.isNotBlank()) { TripHead(t("What to pack", "Cha kubeba")); TextBlock(g.packing, "gview_pack") }
    if (g.expect.isNotBlank()) { TripHead(t("What to expect", "Cha kutarajia")); TextBlock(g.expect, "gview_expect") }
    if (g.emergency.isNotEmpty()) {
        TripHead(t("Emergency contacts", "Mawasiliano ya dharura"))
        g.emergency.forEach { e -> Row(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.tint).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(e.optString("name"), style = MaterialTheme.typography.titleSmall, color = C.ink); Text(listOf(e.optString("role"), e.optString("phone")).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = C.muted) }
            if (e.optString("phone").isNotBlank()) Btn(t("Call", "Piga"), { runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + e.optString("phone").replace(" ", "")))) } }, small = true, light = true, outline = true, icon = "phone")
        } }
    }
}

@Composable
private fun TextBlock(s: String, tag: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (C.glassy) C.glass2 else C.card).padding(14.dp).testTag(tag)) {
        s.lines().filter { it.isNotBlank() }.forEach { l -> Row(Modifier.padding(vertical = 3.dp)) { Text("•", color = C.blue2, modifier = Modifier.width(16.dp)); Text(l.trim(), style = MaterialTheme.typography.bodyMedium, color = C.text) } }
    }
}

/** Donor mode: the donor's own group trips (from /api/donor "groupTrips"). */
fun donorGroupTrips(m: AppModel): List<GroupTrip> = m.donor.prefs.load()?.optJSONArray("groupTrips").objs().map { GroupTrip(it) }

/** Donor mode › Home: a card for each of their group trips. */
@Composable
fun DonorTripCards(m: AppModel, open: (Int) -> Unit) {
    val trips = donorGroupTrips(m)
    if (trips.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (trips.size == 1) t("Your trip", "Safari yako") else t("Your trips", "Safari zako"), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold), color = C.ink, textAlign = TextAlign.Center)
    }
    trips.forEachIndexed { i, g ->
        Row(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(DeepBrush).clickable(role = Role.Button) { open(i) }.padding(18.dp).testTag("donor_trip_$i"), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(g.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = Color.White)
                Text(tripDates(g.from, g.to) + (if (g.destination.isNotBlank()) " · " + g.destination else ""), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
            }
            Icon(Ic.get("chev"), null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

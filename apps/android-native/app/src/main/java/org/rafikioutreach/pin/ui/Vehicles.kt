package org.rafikioutreach.pin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.COUNTRIES
import org.rafikioutreach.pin.data.FuelCountry
import org.rafikioutreach.pin.data.Hm
import org.rafikioutreach.pin.data.Money
import org.rafikioutreach.pin.data.Vehicle
import org.rafikioutreach.pin.data.itinReason
import org.rafikioutreach.pin.data.perL
import org.rafikioutreach.pin.data.trips
import org.rafikioutreach.pin.t

/** Office › Vehicles (admins): the vehicles, fuel prices (with where they came from and when), and the trip cost settings. */
@Composable
fun VehiclesSection(m: AppModel) {
    val repo = m.trips
    val vehicles by repo.vehicles.collectAsState()
    var edit by remember { mutableStateOf<Vehicle?>(null) }
    var adding by remember { mutableStateOf(false) }
    var prices by remember { mutableStateOf<List<FuelCountry>?>(null) }
    var tick by remember { mutableStateOf(0) }
    var setPrice by remember { mutableStateOf<String?>(null) }
    val scope = rememberScope()
    var fetchMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { repo.loadVehicles() }
    LaunchedEffect(tick) { prices = repo.prices() }
    TripHead(t("Vehicles", "Magari"), extra = if (vehicles.isNotEmpty()) "${vehicles.size}" else "")
    if (vehicles.isEmpty()) Text(t("Add the vehicles trips use. The fuel each trip needs comes from the engine size, the roads and the fuel logs.", "Ongeza magari yanayotumika kwa safari. Mafuta ya kila safari yanatokana na ukubwa wa injini, barabara na kumbukumbu za mafuta."),
        style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.padding(4.dp))
    vehicles.forEachIndexed { i, v -> VehicleRow(v, i) { edit = v } }
    Btn(t("Add a vehicle", "Ongeza gari"), { adding = true }, Modifier.padding(top = 10.dp).fillMaxWidth().testTag("veh_add"), icon = "plus", small = true)

    TripHead(t("Fuel prices", "Bei za mafuta"))
    Text(t("Official monthly maximum prices, fetched by the server. A price set here is used first for that town.", "Bei za juu za kila mwezi rasmi, zinazoletwa na seva. Bei iliyowekwa hapa inatumika kwanza kwa mji huo."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(horizontal = 4.dp))
    val p = prices
    if (p == null) Text(t("Loading…", "Inapakia…"), color = C.muted, modifier = Modifier.padding(8.dp))
    else listOf("ke", "tz", "ug", "mw").forEach { ck -> FuelCountryCard(ck, p.firstOrNull { it.ck == ck }, { setPrice = ck }) { town ->
        scope.launch { val r = repo.fuel(JSONObject().put("action", "override-remove").put("ck", ck).put("town", town)); if (r.ok) tick++ else fetchMsg = itinReason(r.reason) }
    } }
    Btn(t("Fetch the prices now", "Leta bei sasa"), {
        scope.launch {
            fetchMsg = t("Fetching…", "Inaleta…")
            val r = repo.fuel(JSONObject().put("action", "refresh"))
            val res = r.json.optJSONObject("results")
            fetchMsg = if (!r.ok) itinReason(r.reason) else res?.keys()?.asSequence()?.joinToString(" · ") { k -> (COUNTRIES[k] ?: k.uppercase()) + ": " + if (res.optJSONObject(k)?.optBoolean("ok") == true) t("updated", "imesasishwa") else fetchWhy(res.optJSONObject(k)?.optString("reason") ?: "") } ?: t("Updated", "Imesasishwa")
            tick++
        }
    }, Modifier.padding(top = 10.dp).fillMaxWidth().testTag("fuel_refresh"), small = true, light = true, outline = true, icon = "refresh")
    Msg(fetchMsg)

    CostSettings(m)
    if (adding) VehicleSheet(m, null, false, { adding = false }) {}
    edit?.let { v -> VehicleSheet(m, v, false, { edit = null }) {} }
    setPrice?.let { ck -> PriceSheet(m, ck, { setPrice = null }) { tick++ } }
}

private fun fetchWhy(r: String) = when (r) {
    "fetch" -> t("couldn't reach the site", "imeshindwa kufikia tovuti"); "blocked" -> t("the site blocked us", "tovuti imetuzuia"); "parse" -> t("the page changed", "ukurasa umebadilika")
    "stale" -> t("only an old price found", "bei ya zamani tu imepatikana"); "no-source" -> t("no source online: set the price here", "hakuna chanzo mtandaoni: weka bei hapa"); else -> r
}

@Composable
private fun VehicleRow(v: Vehicle, i: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(Modifier.padding(top = 8.dp).rise(i, step = 40).fillMaxWidth().clip(shape).background(if (C.glassy) C.glass2 else C.card).border(1.dp, if (C.glassy) C.glassEdge else C.line, shape)
        .clickable(role = Role.Button, onClick = onClick).padding(14.dp).testTag("veh_" + v.vid), verticalAlignment = Alignment.CenterVertically) {
        val hu = C.hue(if (v.type == "motorbike") "coral" else "teal")
        IconTile(if (v.type == "motorbike") "nav" else "map", hu.fg, hu.bg, 44.dp); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(v.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(v.typeName(), v.cc.takeIf { it > 0 }?.let { "%,d cc".format(java.util.Locale.US, it) }, v.fuelName(), v.owner.takeIf { it.isNotBlank() }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = C.muted)
            v.use?.let { u -> Text("%.1f L/\u2060100\u00A0km".format(java.util.Locale.US, u) + " · " + when (v.useSrc) {
                "blend" -> if (v.realLogs == 1) t("from 1 fuel log", "kutoka kumbukumbu 1 ya mafuta") else t("from ${v.realLogs} fuel logs", "kutoka kumbukumbu ${v.realLogs} za mafuta")
                "entered" -> t("entered", "imewekwa"); else -> t("model", "modeli") + (if (v.band.isNotBlank()) " " + v.band else "") }, style = MaterialTheme.typography.labelMedium, color = C.blue2, modifier = Modifier.padding(top = 2.dp)) }
        }
        Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(18.dp))
    }
}

/** Add or change a vehicle (a field officer: only their own motorbike). */
@Composable
fun VehicleSheet(m: AppModel, v: Vehicle?, bikeOnly: Boolean, close: () -> Unit, saved: (String?) -> Unit) {
    val scope = rememberScope()
    var name by remember { mutableStateOf(v?.name ?: "") }
    var plate by remember { mutableStateOf(v?.plate ?: "") }
    var type by remember { mutableStateOf(v?.type ?: if (bikeOnly) "motorbike" else "pickup") }
    var cc by remember { mutableStateOf(v?.cc?.takeIf { it > 0 }?.toString() ?: "") }
    var fuel by remember { mutableStateOf(v?.fuel ?: if (bikeOnly) "petrol" else "diesel") }
    var l100 by remember { mutableStateOf(v?.lPer100?.toString() ?: "") }
    var msg by remember { mutableStateOf<String?>(null) }
    var sure by remember { mutableStateOf(false) }
    TripSheet("veh_sheet", if (v == null) (if (bikeOnly) t("Add my motorbike", "Ongeza pikipiki yangu") else t("Add a vehicle", "Ongeza gari")) else v.name, close) { hide ->
        Field(name, { name = it.take(60) }, t("Name", "Jina"), Modifier.testTag("veh_name"), placeholder = t("e.g. Hilux", "mf. Hilux"))
        Field(plate, { plate = it.uppercase().take(12) }, t("Number plate", "Namba ya gari"), Modifier.testTag("veh_plate"), placeholder = "KDA 123X")
        if (!bikeOnly) Choices(Vehicle.TYPES.map { it to Vehicle.typeName(it) }, type, "veh_t_", Modifier.padding(top = 6.dp)) { type = it }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(cc, { cc = it.filter { c -> c.isDigit() }.take(5) }, t("Engine (cc)", "Injini (cc)"), Modifier.weight(1f).testTag("veh_cc"), number = true, placeholder = if (type == "motorbike") "150" else "2400")
            Field(l100, { l100 = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, t("L/100 km (optional)", "L/100 km (si lazima)"), Modifier.weight(1f).testTag("veh_l100"), number = true)
        }
        Choices(listOf("petrol" to Vehicle.fuelName("petrol"), "diesel" to Vehicle.fuelName("diesel")), fuel, "veh_f_") { fuel = it }
        Text(t("Leave L/100 km empty to use the model from the engine size; 3 fuel logs make it real.", "Acha L/100 km tupu ili kutumia modeli ya ukubwa wa injini; kumbukumbu 3 za mafuta zinaifanya halisi."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 6.dp))
        val ok = (name.isNotBlank() || plate.isNotBlank()) && (cc.toIntOrNull() ?: 0) in 49..9000
        Btn(t("Save", "Hifadhi"), {
            scope.launch {
                val b = JSONObject().put("action", if (v == null) "add" else "update").put("name", name.trim().ifBlank { plate.trim() }).put("plate", plate.trim()).put("type", type).put("cc", cc.toInt()).put("fuel", fuel)
                    .put("lPer100", l100.toDoubleOrNull() ?: JSONObject.NULL).apply { v?.let { put("vid", it.vid) } }
                val r = m.trips.vehicle(b)
                if (r.ok) { saved(r.json.optJSONObject("vehicle")?.optString("vid") ?: v?.vid); hide() } else msg = itinReason(r.reason)
            }
        }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("veh_save"), icon = "check", enabled = ok)
        if (v != null) Text(if (sure) t("Tap again to remove", "Gusa tena kuondoa") else t("Remove the vehicle", "Ondoa gari"), color = C.bad, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(99.dp)).clickable(role = Role.Button) {
                if (!sure) sure = true else scope.launch { val r = m.trips.vehicle(JSONObject().put("action", "remove").put("vid", v.vid).put("force", true)); if (r.ok) hide() else msg = itinReason(r.reason) }
            }.heightIn(min = 44.dp).padding(12.dp).testTag("veh_remove"))
        Msg(msg, "bad")
    }
}

/** One country's official prices: source, period, a few towns, and the office's own prices. */
@Composable
private fun FuelCountryCard(ck: String, f: FuelCountry?, set: () -> Unit, remove: (String) -> Unit) {
    var all by rememberSaveable(ck) { mutableStateOf(false) }
    Card(Modifier.padding(top = 10.dp).testTag("fuel_$ck"), pad = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text((COUNTRIES[ck] ?: ck.uppercase()), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = C.ink, modifier = Modifier.weight(1f))
            val (label, hu) = when (f?.kind) { "official" -> t("Official", "Rasmi") to Hue(C.ok, C.okBg); "cached" -> t("Last saved", "Iliyohifadhiwa mwisho") to Hue(C.warn, C.warnBg); "override" -> t("Set by the office", "Imewekwa na ofisi") to C.hue("blue"); else -> t("No price", "Hakuna bei") to Hue(C.bad, C.badBg) }
            Tag(label, hu.bg, hu.fg)
        }
        if (f != null && f.source.isNotBlank()) Text(f.source + (if (f.from.isNotBlank()) " · " + Dates.day(f.from, false) + "–" + Dates.day(f.to, false) else "") + (if (f.fetchedAt.isNotBlank()) t(" · fetched ", " · ililetwa ") + whenText(f.fetchedAt) else ""),
            style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 2.dp).testTag("fuel_src_$ck"))
        if (f != null && !f.statusOk && f.statusReason.isNotBlank()) Text(t("Last fetch: ", "Ilipoletwa mwisho: ") + fetchWhy(f.statusReason), style = MaterialTheme.typography.bodySmall, color = C.warn)
        val towns = f?.towns.orEmpty()
        if (towns.isNotEmpty()) {
            Row(Modifier.padding(top = 8.dp)) {
                Text(t("Town", "Mji"), style = MaterialTheme.typography.labelSmall, color = C.muted, modifier = Modifier.weight(1f))
                Text(Vehicle.fuelName("petrol"), style = MaterialTheme.typography.labelSmall, color = C.muted, textAlign = TextAlign.End, modifier = Modifier.width(76.dp))
                Text(Vehicle.fuelName("diesel"), style = MaterialTheme.typography.labelSmall, color = C.muted, textAlign = TextAlign.End, modifier = Modifier.width(76.dp))
                if (f!!.overrides.isNotEmpty()) Spacer(Modifier.width(44.dp))
            }
            (if (all) towns else towns.take(5)).forEach { (town, o) -> PriceRow(town, o, f!!.currency, pad = f.overrides.isNotEmpty()) }
            if (towns.size > 5) Text(if (all) t("Fewer towns", "Miji michache") else t("All ${towns.size} towns", "Miji yote ${towns.size}"), color = C.blue2, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(99.dp)).clickable(role = Role.Button) { all = !all }.heightIn(min = 44.dp).padding(horizontal = 4.dp, vertical = 12.dp).testTag("fuel_all_$ck"))
        }
        f?.overrides?.takeIf { it.isNotEmpty() }?.let { ov ->
            Text(t("Set by the office", "Imewekwa na ofisi"), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.padding(top = 10.dp))
            ov.forEach { (town, o) -> PriceRow(if (town == "*") t("Whole country", "Nchi nzima") else town, o, f.currency, pad = true) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(99.dp)).clickable { remove(town) }.testTag("fuel_ov_rm_$ck"), contentAlignment = Alignment.Center) { Icon(Ic.get("trash"), t("Remove", "Ondoa"), tint = C.muted, modifier = Modifier.size(18.dp)) }
            } }
        }
        Btn(t("Set a price", "Weka bei"), set, Modifier.padding(top = 8.dp).testTag("fuel_set_$ck"), small = true, light = true, outline = true, icon = "coins")
    }
}

@Composable
private fun PriceRow(town: String, o: JSONObject, cur: String, pad: Boolean = false, end: (@Composable () -> Unit)? = null) {
    Row(Modifier.padding(vertical = 3.dp).heightIn(min = if (end != null) 44.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(town, style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        listOf("petrol", "diesel").forEach { k -> Text(o.optDouble(k).takeIf { !it.isNaN() }?.let { perL(it) } ?: "—", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = C.ink, textAlign = TextAlign.End, maxLines = 1, softWrap = false, modifier = Modifier.width(76.dp)) }
        // the same columns in every row of the card (the office's own prices have a remove button at the end)
        if (end != null) end() else if (pad) Spacer(Modifier.width(44.dp))
    }
}

/** The office's own price for a town (or the whole country): used before the official one. */
@Composable
private fun PriceSheet(m: AppModel, ck: String, close: () -> Unit, saved: () -> Unit) {
    val scope = rememberScope()
    var town by remember { mutableStateOf("") }
    var petrol by remember { mutableStateOf("") }
    var diesel by remember { mutableStateOf("") }
    var until by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    TripSheet("price_sheet", t("Fuel price · ", "Bei ya mafuta · ") + (COUNTRIES[ck] ?: ck.uppercase()), close) { hide ->
        Field(town, { town = it }, t("Town (empty = whole country)", "Mji (tupu = nchi nzima)"), Modifier.testTag("price_town"), placeholder = t("e.g. Eldoret", "mf. Eldoret"))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(petrol, { petrol = it.filter { c -> c.isDigit() || c == '.' }.take(8) }, t("Petrol per litre", "Petroli kwa lita"), Modifier.weight(1f).testTag("price_petrol"), number = true)
            Field(diesel, { diesel = it.filter { c -> c.isDigit() || c == '.' }.take(8) }, t("Diesel per litre", "Dizeli kwa lita"), Modifier.weight(1f).testTag("price_diesel"), number = true)
        }
        TripDate(t("Use until (optional)", "Tumia hadi (si lazima)"), until, { until = it })
        Btn(t("Save", "Hifadhi"), {
            scope.launch {
                val r = m.trips.fuel(JSONObject().put("action", "override").put("ck", ck).put("town", town.trim().ifBlank { "*" })
                    .apply { petrol.toDoubleOrNull()?.let { put("petrol", it) }; diesel.toDoubleOrNull()?.let { put("diesel", it) }; if (until.isNotBlank()) put("until", until) })
                if (r.ok) { saved(); hide() } else msg = itinReason(r.reason)
            }
        }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("price_save"), icon = "check", enabled = petrol.toDoubleOrNull() != null || diesel.toDoubleOrNull() != null)
        Msg(msg, "bad")
    }
}

/** Lunch, per diem and lodging rates, working hours and lunch time, used by every trip's plan and costs. */
@Composable
private fun CostSettings(m: AppModel) {
    val scope = rememberScope()
    var s by remember { mutableStateOf<JSONObject?>(null) }
    var open by rememberSaveable { mutableStateOf(false) }
    BackStep(open) { open = false }
    LaunchedEffect(Unit) { s = m.trips.settings() }
    TripHead(t("Trip settings", "Mipangilio ya safari"))
    val cur = s ?: JSONObject()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.tint).clickable(role = Role.Button) { open = !open }.padding(14.dp).testTag("trip_settings"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(t("Lunch ", "Chakula ") + Money.kes(cur.optLong("lunchKes")) + t(" · allowance ", " · posho ") + Money.kes(cur.optLong("perDiemKes")), style = MaterialTheme.typography.bodyMedium, color = C.ink)
            Text(t("Lodging ", "Malazi ") + Money.kes(cur.optLong("lodgingKes")) + t(" a night · ", " kwa usiku · ") + (cur.optJSONObject("hours")?.let { h -> h.optString("start") + "–" + h.optString("end") } ?: "07:30–17:30"),
                style = MaterialTheme.typography.bodySmall, color = C.muted)
        }
        Icon(Ic.get(if (open) "up" else "down"), null, tint = C.muted, modifier = Modifier.size(18.dp))
    }
    if (!open || s == null) return
    var lunch by remember { mutableStateOf(cur.optLong("lunchKes").takeIf { it > 0 }?.toString() ?: "") }
    var perDiem by remember { mutableStateOf(cur.optLong("perDiemKes").takeIf { it > 0 }?.toString() ?: "") }
    var lodging by remember { mutableStateOf(cur.optLong("lodgingKes").takeIf { it > 0 }?.toString() ?: "") }
    var hs by remember { mutableStateOf(cur.optJSONObject("hours")?.optString("start") ?: "07:30") }
    var he by remember { mutableStateOf(cur.optJSONObject("hours")?.optString("end") ?: "17:30") }
    var la by remember { mutableStateOf(cur.optJSONObject("lunch")?.optString("at") ?: "12:30") }
    var msg by remember { mutableStateOf<Pair<String, String>?>(null) }
    // where trips start "from the office", per country (contract v1.2: settings.offices)
    val offices = cur.optJSONObject("offices") ?: JSONObject()
    var oCk by remember { mutableStateOf(offices.keys().asSequence().firstOrNull() ?: m.prefs.country.ifBlank { "ke" }) }
    var oName by remember(oCk) { mutableStateOf(offices.optJSONObject(oCk)?.optString("name") ?: "") }
    var oAt by remember(oCk) { mutableStateOf(offices.optJSONObject(oCk)?.let { o -> if (o.has("lat")) org.rafikioutreach.pin.data.LatLng(o.optDouble("lat"), o.optDouble("lng")) else null }) }
    Field(lunch, { lunch = it }, t("Lunch per person per day", "Chakula kwa mtu kwa siku"), Modifier.testTag("set_lunch"), money = true)
    Field(perDiem, { perDiem = it }, t("Allowance per person per day", "Posho kwa mtu kwa siku"), Modifier.testTag("set_perdiem"), money = true)
    Field(lodging, { lodging = it }, t("Lodging per night", "Malazi kwa usiku"), Modifier.testTag("set_lodging"), money = true)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Field(hs, { hs = it.take(5) }, t("Day starts", "Siku inaanza"), Modifier.weight(1f))
        Field(he, { he = it.take(5) }, t("Day ends", "Siku inaisha"), Modifier.weight(1f))
        Field(la, { la = it.take(5) }, t("Lunch at", "Chakula saa"), Modifier.weight(1f))
    }
    TripHead(t("The office", "Ofisi"))
    Text(t("Trips that start \"from the office\" start here.", "Safari zinazoanzia \"ofisini\" zinaanzia hapa."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(horizontal = 4.dp))
    Picker(t("Country", "Nchi"), oCk, listOf("ke", "tz", "ug", "mw").map { it to (COUNTRIES[it] ?: it.uppercase()) }, { oCk = it }, Modifier.testTag("set_office_ck"))
    PlacePick(oName, { oName = it }, oAt, { oAt = it }, "set_office")
    Btn(t("Save", "Hifadhi"), {
        scope.launch {
            val b = JSONObject(cur.toString()).put("lunchKes", lunch.toLongOrNull() ?: 0).put("perDiemKes", perDiem.toLongOrNull() ?: 0).put("lodgingKes", lodging.toLongOrNull() ?: 0)
                .put("hours", JSONObject().put("start", Hm.text(Hm.parse(hs) ?: 450)).put("end", Hm.text(Hm.parse(he) ?: 1050)))
                .put("lunch", (cur.optJSONObject("lunch") ?: JSONObject().put("min", 45)).put("at", Hm.text(Hm.parse(la) ?: 750)))
            val at = oAt
            if (oName.isNotBlank() && at != null) b.put("offices", JSONObject(offices.toString()).put(oCk, JSONObject().put("name", oName.trim()).put("lat", at.lat).put("lng", at.lng)))
            val r = m.trips.saveSettings(b)
            msg = if (r.ok) { s = r.json.optJSONObject("settings") ?: b; "ok" to t("Saved ✓", "Imehifadhiwa ✓") } else "bad" to itinReason(r.reason)
        }
    }, Modifier.padding(top = 12.dp).fillMaxWidth().testTag("set_save"), icon = "check", small = true)
    msg?.let { (tone, x) -> Msg(x, tone) }
}

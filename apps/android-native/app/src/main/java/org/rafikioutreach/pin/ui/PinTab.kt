package org.rafikioutreach.pin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.Outcome
import org.rafikioutreach.pin.data.Geo
import org.rafikioutreach.pin.data.LatLng
import org.rafikioutreach.pin.data.OutFile
import org.rafikioutreach.pin.data.Project
import org.rafikioutreach.pin.t

/**
 * Where something is: move the map under the cross, or use the phone's GPS, paste a point or
 * a Google Maps link, or search for a place by name. Shared by "Pin location" and "New site".
 */
class Spot { var ll by mutableStateOf<LatLng?>(null); var how by mutableStateOf("map"); var acc by mutableStateOf<Int?>(null); var found by mutableStateOf("") }

@Composable
fun SpotPicker(m: AppModel, spot: Spot, start: LatLng?, ck: String, others: List<Dot> = emptyList()) {
    val fix by m.fix.collectAsState()
    val scope = rememberScope()
    var jump by remember { mutableStateOf<LatLng?>(null) }
    var paste by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    var places by remember { mutableStateOf<List<Triple<String, String, LatLng>>>(emptyList()) }
    var msg by remember { mutableStateOf<String?>(null) }
    var waitGps by remember { mutableStateOf(false) }

    if (waitGps) fix?.let { f -> if (f.acc <= 25 || System.currentTimeMillis() - f.at < 3000) { waitGps = false; spot.how = "gps"; spot.acc = f.acc; jump = f.ll } }

    Text(t("Move the map so the cross is on the exact spot, or use your location.", "Sogeza ramani hadi msalaba uwe mahali kamili, au tumia mahali ulipo."), style = MaterialTheme.typography.bodyMedium, color = C.muted)
    PickMap(start ?: fix?.ll, { ll -> spot.ll = ll; if (spot.how == "gps" && spot.acc != null && jump != null && Geo.metres(ll, jump!!) > 3) { spot.how = "map"; spot.acc = null } },
        Modifier.fillMaxWidth().height(320.dp).padding(top = 10.dp), jumpTo = jump, others = others)
    spot.ll?.let { Text(it.text() + (spot.acc?.let { a -> " · GPS ±$a m" } ?: ""), style = MaterialTheme.typography.labelMedium, color = C.ink, modifier = Modifier.padding(top = 8.dp)) }
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn(if (waitGps) t("Finding you…", "Inakutafuta…") else t("Use my location", "Tumia mahali nilipo"), {
            val f = fix
            if (f == null) { waitGps = true; msg = if (m.locator.gpsOn) t("Waiting for GPS. Stand in the open for a moment.", "Inasubiri GPS. Simama mahali wazi kwa muda.") else t("Turn on Location in your phone’s settings.", "Washa Location kwenye mipangilio ya simu.") }
            else { spot.how = "gps"; spot.acc = f.acc; jump = f.ll; msg = if (f.acc > 25) t("GPS is ±${f.acc} m. It gets better in the open; tap again in a moment.", "GPS ni ±${f.acc} m. Inakuwa bora mahali wazi; gusa tena baada ya muda.") else null }
        }, Modifier.weight(1f), icon = "pin", small = true)
    }
    Msg(msg, "warn")

    Field(paste, { paste = it }, t("Coordinates or a Google Maps link", "Coordinates au kiungo cha Google Maps"), placeholder = "0.561368, 35.065480")
    if (paste.isNotBlank()) Btn(t("Use this point", "Tumia hapa"), {
        val ll = Geo.parse(paste)
        if (ll == null) msg = t("Couldn’t read that. Try 0.561368, 35.065480 or a Google Maps link with the point in it.", "Haikusomeka. Jaribu 0.561368, 35.065480 au kiungo cha Google Maps chenye mahali.")
        else { spot.how = "paste"; spot.acc = null; jump = ll; msg = null }
    }, Modifier.padding(top = 8.dp), light = true, small = true)

    Field(q, { q = it }, t("Find a place by name", "Tafuta mahali kwa jina"), placeholder = t("e.g. Kiptoo Primary School, Nandi", "mf. Shule ya Msingi Kiptoo, Nandi"))
    if (q.isNotBlank()) Btn(t("Search", "Tafuta"), {
        scope.launch {
            msg = t("Searching…", "Inatafuta…")
            val r = m.api.place(q, fix?.ll ?: start, ck)
            val a = r.json.optJSONArray("places")
            places = if (a == null) emptyList() else (0 until a.length()).mapNotNull { i -> a.getJSONObject(i).let { o -> if (o.has("lat")) Triple(o.optString("name"), o.optString("where"), LatLng(o.getDouble("lat"), o.getDouble("lng"))) else null } }
            msg = if (!r.ok) org.rafikioutreach.pin.data.reasonText(r.reason) else if (places.isEmpty()) t("Nothing found. Try a nearby town or school.", "Hakuna kilichopatikana. Jaribu mji au shule iliyo karibu.") else null
        }
    }, Modifier.padding(top = 8.dp), light = true, small = true, icon = "search")
    places.take(6).forEach { (n, w, ll) ->
        Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.card).border(1.dp, C.line, RoundedCornerShape(14.dp))
            .clickable { spot.how = "search"; spot.found = "$n, $w"; spot.acc = null; jump = ll; places = emptyList(); q = n }.padding(12.dp)) {
            Text(n, style = MaterialTheme.typography.titleSmall, color = C.ink); Text(w, style = MaterialTheme.typography.bodySmall, color = C.muted)
        }
    }
}

@Composable
fun PinTab(m: AppModel, p: Project) {
    val scope = rememberScope()
    val spot = remember(p.id) { Spot() }
    var shots by remember(p.id) { mutableStateOf(listOf<Shot>()) }
    var msg by remember(p.id) { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var saved by remember(p.id) { mutableStateOf(false) }
    val s by m.snap.collectAsState()

    SpotPicker(m, spot, p.gps, p.ck, others = s?.projects?.filter { it.id != p.id && it.gps != null }?.map { Dot(it.id, it.gps!!, 0xFF1C7C4A.toInt(), false, it.title) } ?: emptyList())
    Spacer(Modifier.height(14.dp))
    Text(t("Photos (optional)", "Picha (si lazima)"), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.padding(bottom = 8.dp))
    PhotoSlots(m, emptyList(), shots, { shots = it }, max = 5)
    Btn(t("Save this location", "Hifadhi mahali hapa"), {
        val ll = spot.ll ?: return@Btn
        busy = true
        scope.launch {
            val body = JSONObject().put("action", "pin").put("id", p.id).put("lat", ll.lat).put("lng", ll.lng).put("how", spot.how).put("found", spot.found)
            spot.acc?.let { body.put("acc", it) }
            when (val o = m.submit(body, shots.map { OutFile(it.file.path, "photo", "Location photo") })) {
                is Outcome.Sent -> {
                    if (o.reply.optBoolean("pending")) msg = "warn" to t("This location was confirmed by the office, so your change was sent to them to approve.", "Mahali hapa pamethibitishwa na ofisi, kwa hiyo badiliko lako limetumwa kwao kuidhinisha.")
                    else { m.localPin(p, ll); saved = true; msg = "ok" to (t("Location saved in monday.com ✓", "Mahali pamehifadhiwa kwenye monday.com ✓") + (o.note?.let { " $it" } ?: "")) }
                    shots = emptyList()
                }
                Outcome.Queued -> { m.localPin(p, ll); saved = true; shots = emptyList(); msg = "warn" to t("Saved on this phone. It will be sent when you have signal.", "Imehifadhiwa kwenye simu. Itatumwa ukipata mtandao.") }
                is Outcome.Failed -> msg = "bad" to o.text
                Outcome.Locked -> {}
            }
            busy = false
        }
    }, Modifier.fillMaxWidth().padding(top = 16.dp), icon = "check", enabled = spot.ll != null && !busy)
    msg?.let { (tone, text) -> Msg(text, tone) }
    if (saved) Btn(t("Undo", "Tendua"), {
        scope.launch {
            val o = m.submit(JSONObject().put("action", "undo").put("id", p.id), emptyList())
            if (o is Outcome.Sent) { m.refresh(); saved = false; msg = "ok" to t("Undone. The location is back to what it was.", "Imetenduliwa. Mahali pamerudi kama awali.") }
            else if (o is Outcome.Failed) msg = "bad" to o.text
        }
    }, Modifier.padding(top = 8.dp), icon = "undo", light = true, small = true)
}

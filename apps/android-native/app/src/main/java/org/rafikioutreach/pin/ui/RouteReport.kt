package org.rafikioutreach.pin.ui

import android.content.Intent
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
import androidx.compose.ui.unit.dp
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.COUNTRIES
import org.rafikioutreach.pin.data.DUE_DAYS
import org.rafikioutreach.pin.data.Geo
import org.rafikioutreach.pin.data.LatLng
import org.rafikioutreach.pin.data.Project
import org.rafikioutreach.pin.t
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

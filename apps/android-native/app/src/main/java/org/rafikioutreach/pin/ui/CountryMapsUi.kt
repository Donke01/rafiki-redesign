package org.rafikioutreach.pin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.cmap.CountryMaps
import org.rafikioutreach.pin.t
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Whether a map shows the whole-country map: picked as the map style, or by itself with no signal.
 *  [skip]: another style was chosen on this map since (it lasts until the signal changes). */
@Composable
fun countryMapOn(skip: Boolean): Boolean {
    val have by CountryMaps.have.collectAsState(); val picked by CountryMaps.picked.collectAsState(); val online by OfflineMaps.online.collectAsState()
    return have.isNotEmpty() && !skip && (picked || !online)
}

/** The credit OpenStreetMap's licence (ODbL) asks for, on the map while the country map shows. */
@Composable
fun CountryCredit(modifier: Modifier) {
    Text("© OpenStreetMap contributors", fontSize = TextUnit(9f, TextUnitType.Sp), color = Color(0xFF222222), maxLines = 1,
        modifier = modifier.padding(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xD9FFFFFF)).padding(horizontal = 4.dp, vertical = 1.dp).semantics { contentDescription = "osm_credit" })
}

private fun dayText(d: String) = runCatching { LocalDate.parse(d).format(DateTimeFormatter.ofPattern("d MMM yyyy", if (org.rafikioutreach.pin.Lang.sw) java.util.Locale("sw") else java.util.Locale.UK)) }.getOrDefault(d)

/** Offline maps › Whole country maps: each country with its size, Download / Update / Resume / Stop / Delete,
 *  progress, Wi-Fi only (with a switch for mobile data) and the free space on the phone. Hidden until set up. */
@Composable
fun CountryMapsSection(m: AppModel) {
    val base by CountryMaps.base.collectAsState()
    val list by CountryMaps.list.collectAsState()
    val have by CountryMaps.have.collectAsState()
    val dl by CountryMaps.download.collectAsState()
    val note by CountryMaps.note.collectAsState()
    val online by OfflineMaps.online.collectAsState()
    if (base == null && have.isEmpty()) return
    var free by remember { mutableLongStateOf(-1L) }
    var loading by remember { mutableStateOf(base != null && list.isEmpty()) }
    var msg by remember { mutableStateOf<Pair<String, String>?>(null) }
    var mobile by remember { mutableStateOf(CountryMaps.mobileData) }
    var ask by remember { mutableStateOf<CountryMaps.Country?>(null) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(base, online) { if (base != null && online) { loading = list.isEmpty(); withContext(Dispatchers.IO) { CountryMaps.refreshList() }; loading = false } else loading = false }
    LaunchedEffect(have, dl?.state, tick) { free = withContext(Dispatchers.IO) { CountryMaps.freeBytes() } }
    LaunchedEffect(note) { note?.let { m.showBanner(it); CountryMaps.note.value = null } }
    val rows = list + have.values.filter { h -> list.none { it.code == h.code } }
    Column(Modifier.padding(top = 18.dp).testTag("cm_section")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            H2(t("Whole country maps", "Ramani za nchi nzima"), wide = false)
        }
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val hu = C.hue("green"); IconTile("offline", hu.fg, hu.bg, 38.dp); Spacer(Modifier.width(12.dp))
                Text(t("No signal needed", "Bila mtandao"), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.weight(1f))
            }
            Text(t("Download a country once on Wi-Fi: the street map then works anywhere in it with no signal, with villages, roads and tracks, schools, clinics, churches, rivers and water points. Your projects, routes and location show on top as usual.",
                "Pakua nchi mara moja ukiwa kwenye Wi-Fi: ramani ya barabara itafanya kazi popote ndani yake bila mtandao, ikiwa na vijiji, barabara na njia, shule, zahanati, makanisa, mito na vituo vya maji. Miradi, njia na mahali ulipo vinaonekana juu kama kawaida."),
                style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 8.dp))
        }
        msg?.let { Msg(it.second, it.first) }
        if (rows.isEmpty()) Card(Modifier.padding(top = 10.dp)) {
            Text(if (loading) t("Loading the list of countries…", "Inapakia orodha ya nchi…") else if (online) t("No country maps yet. The office adds them.", "Bado hakuna ramani za nchi. Ofisi inaziongeza.")
                else t("The list of countries needs a signal the first time.", "Orodha ya nchi inahitaji mtandao mara ya kwanza."), style = MaterialTheme.typography.bodyMedium, color = C.muted)
        }
        rows.forEachIndexed { i, c ->
            val mine = have[c.code]; val newer = if (mine != null) list.firstOrNull { it.code == c.code && it.file != mine.file } else null
            val d = dl?.takeIf { it.code == c.code }
            val target = newer ?: c
            val part = remember(dl, have, tick) { if (d == null && mine?.file != target.file) CountryMaps.partBytes(target) else 0L }
            val hu = C.hue(listOf("green", "blue", "amber", "purple", "teal")[i % 5])
            Card(Modifier.padding(top = 10.dp).testTag("cm_row_${c.code}"), pad = 14.dp, border = if (mine != null) C.ok else null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile("map", hu.fg, hu.bg, 40.dp); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.titleMedium, color = C.ink, maxLines = 1)
                        val st = when {
                            d != null -> when (d.state) {
                                "wifi" -> t("Waiting for Wi-Fi…", "Inasubiri Wi-Fi…")
                                "wait" -> t("Connection lost: carries on when the signal is back", "Mtandao umekatika: itaendelea mtandao ukirudi")
                                "err" -> t("Didn’t finish", "Haikukamilika")
                                else -> t("Downloading…", "Inapakua…")
                            }
                            newer != null -> t("A newer map is ready", "Ramani mpya iko tayari")
                            mine != null -> t("On this phone · ", "Kwenye simu hii · ") + dayText(mine.date)
                            part > 0 -> t("Stopped part way", "Imesimama katikati")
                            else -> t("Map of ", "Ramani ya ") + dayText(c.date)
                        }
                        Text(CountryMaps.mb(target.bytes) + " · " + st, style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.testTag("cm_st_${c.code}"))
                    }
                    if (mine != null || part > 0) Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { ask = c }
                        .semantics { contentDescription = t("Delete ${c.name} map", "Futa ramani ya ${c.name}") }.testTag("cm_del_${c.code}"), contentAlignment = Alignment.Center) {
                        Icon(Ic.get("trash"), null, tint = C.bad, modifier = Modifier.size(22.dp))
                    }
                }
                if (d != null || part > 0) {
                    val done = d?.done ?: part; val total = maxOf(1L, d?.total ?: target.bytes)
                    Meter((done.toFloat() / total).coerceIn(0f, 1f), Modifier.padding(top = 10.dp))
                    Text(if (d != null) t("${CountryMaps.mb(done)} of ${CountryMaps.mb(total)} · ${done * 100 / total}%", "${CountryMaps.mb(done)} kati ya ${CountryMaps.mb(total)} · ${done * 100 / total}%")
                        else t("Part of it is saved; nothing is downloaded twice.", "Sehemu imehifadhiwa; hakuna kinachopakuliwa mara mbili."),
                        style = MaterialTheme.typography.labelMedium, color = C.ink, modifier = Modifier.padding(top = 6.dp).testTag("cm_pct_${c.code}"))
                    if (d?.state == "err" && d.msg.isNotBlank()) Msg(d.msg, "bad")
                }
                if (d != null && d.state != "err") Btn(t("Stop", "Simamisha"), { CountryMaps.stop(); tick++ }, Modifier.fillMaxWidth().padding(top = 10.dp).testTag("cm_stop_${c.code}"), small = true, light = true)
                else if (mine == null || newer != null) Btn(if (newer != null) t("Update", "Sasisha") else if (part > 0) t("Resume", "Endelea") else t("Download", "Pakua"), {
                    msg = null
                    if (!online) { msg = "warn" to t("Downloading needs a signal. Try again on Wi-Fi.", "Kupakua kunahitaji mtandao. Jaribu tena ukiwa kwenye Wi-Fi."); return@Btn }
                    CountryMaps.start(target)?.let { msg = "bad" to it }
                }, Modifier.fillMaxWidth().padding(top = 10.dp).testTag("cm_get_${c.code}"), small = true, icon = "offline")
            }
        }
        Card(Modifier.padding(top = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t("Use mobile data too", "Tumia data ya simu pia"), style = MaterialTheme.typography.titleSmall, color = C.ink)
                    Text(if (mobile) t("On: country maps also download on mobile data. A country is 50–300 MB.", "Imewashwa: ramani za nchi zinapakuliwa pia kwa data ya simu. Nchi moja ni 50–300 MB.")
                        else t("Off: country maps download only on Wi-Fi.", "Imezimwa: ramani za nchi zinapakuliwa kwenye Wi-Fi tu."), style = MaterialTheme.typography.bodySmall, color = C.muted)
                }
                Spacer(Modifier.width(10.dp))
                Toggle(mobile, t("Use mobile data too", "Tumia data ya simu pia"), Modifier.testTag("cm_mobile")) { mobile = !mobile; CountryMaps.mobileData = mobile }
            }
            Text(if (free >= 0) t("Free space on this phone: ${CountryMaps.mb(free)}", "Nafasi iliyo wazi kwenye simu hii: ${CountryMaps.mb(free)}") else "",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = C.ink, modifier = Modifier.padding(top = 12.dp).testTag("cm_free"))
            Text(t("Map data © OpenStreetMap contributors (ODbL).", "Data ya ramani © OpenStreetMap contributors (ODbL)."), style = MaterialTheme.typography.labelSmall, color = C.muted, modifier = Modifier.padding(top = 6.dp))
        }
    }
    ask?.let { c ->
        AlertDialog(onDismissRequest = { ask = null }, containerColor = C.card,
            title = { Text(t("Delete the ${c.name} map?", "Futa ramani ya ${c.name}?"), color = C.ink) },
            text = { Text(t("It is removed from this phone (${CountryMaps.mb(c.bytes)}). You can download it again on Wi-Fi.", "Inaondolewa kwenye simu hii (${CountryMaps.mb(c.bytes)}). Unaweza kuipakua tena ukiwa kwenye Wi-Fi."), color = C.text) },
            confirmButton = { TextButton({ CountryMaps.delete(c.code); ask = null; tick++ }, Modifier.testTag("cm_del_yes")) { Text(t("Delete", "Futa"), color = C.bad) } },
            dismissButton = { TextButton({ ask = null }) { Text(t("Cancel", "Ghairi"), color = C.ink) } })
    }
}

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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.Outcome
import org.rafikioutreach.pin.data.Geo
import org.rafikioutreach.pin.data.LatLng
import org.rafikioutreach.pin.data.Note
import org.rafikioutreach.pin.data.Project
import org.rafikioutreach.pin.data.stateName
import org.rafikioutreach.pin.data.urgencyName
import org.rafikioutreach.pin.t
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun whenText(iso: String): String = runCatching {
    DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.UK).withZone(ZoneId.systemDefault()).format(Instant.parse(iso))
}.getOrDefault(iso)

@Composable
fun ProjectScreen(m: AppModel, id: String, startTab: String, back: () -> Unit) {
    val s by m.snap.collectAsState()
    val fix by m.fix.collectAsState()
    val snap = s ?: return
    val p = snap.projects.firstOrNull { it.id == id } ?: run { back(); return }
    var tab by rememberSaveable(id) { mutableStateOf(startTab) }
    val ctx = LocalContext.current
    val here = fix?.ll

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (p.photo != null) NetImage(m.api.photoUrl(p.photo, 960), Modifier.fillMaxWidth().aspectRatio(16f / 9f).padding(vertical = 6.dp).clip(RoundedCornerShape(24.dp)).background(C.tint)) {}

        // the project, on deep blue
        Card(brush = DeepBrush, modifier = Modifier.padding(top = 6.dp)) {
            Text(p.title, style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text(p.sub, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFCFE8FA))
            val v = snap.visits[p.id]
            // the state at a glance
            val rep = snap.repairs[p.id]
            when {
                rep != null -> Box(Modifier.padding(top = 8.dp)) { Tag(if (rep.state == "notworking") t("Not working", "Haifanyi kazi") else t("Needs repair", "Inahitaji matengenezo"), if (rep.state == "notworking") C.badBg else C.warnBg, if (rep.state == "notworking") C.bad else C.warn) }
                v?.state == "working" -> Box(Modifier.padding(top = 8.dp)) { Tag(t("Working", "Inafanya kazi"), C.okBg, C.ok) }
                else -> {}
            }
            Text(p.gps?.let { it.text() + t(" · saved location", " · mahali palipohifadhiwa") } ?: t("No location saved yet.", "Bado hakuna mahali palipohifadhiwa."), style = MaterialTheme.typography.bodySmall, color = Color(0xB3FFFFFF), modifier = Modifier.padding(top = 8.dp))
            Text(if (v != null) t("Last visit: ", "Ziara ya mwisho: ") + whenText(v.at) + " · " + v.by + " · " + stateName(v.state) else t("No visit recorded in the app yet.", "Bado hakuna ziara iliyorekodiwa kwenye programu."),
                style = MaterialTheme.typography.bodySmall, color = Color(0xB3FFFFFF))
            if (snap.confirmed.contains(p.id)) Text(t("Location confirmed by the office", "Mahali pamethibitishwa na ofisi"), color = Color(0xFF9BE7BC), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
            if (snap.admin && p.gps != null) {
                val scope = rememberScope(); var cm by remember(p.id) { mutableStateOf<String?>(null) }
                val on = snap.confirmed.contains(p.id)
                Text(if (on) t("Unlock this location", "Ondoa uthibitisho wa mahali") else t("Confirm this location", "Thibitisha mahali hapa"), color = Color.White, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(99.dp)).background(Color(0x26FFFFFF)).clickable(role = Role.Button) {
                        scope.launch { val x = m.api.admin(JSONObject().put("action", "confirm").put("id", p.id).put("on", !on)); if (x.ok) m.reload() else cm = org.rafikioutreach.pin.data.reasonText(x.reason) }
                    }.padding(horizontal = 14.dp, vertical = 10.dp))
                cm?.let { Text(it, color = Color(0xFFFFE1DD), style = MaterialTheme.typography.bodySmall) }
            }
            if (here != null && p.gps != null) {
                val d = Geo.metres(here, p.gps)
                val far = d > 300
                Text(
                    if (far) t("You’re ${Geo.dist(d, m.prefs.miles)} from the saved point · head ${Geo.heading(here, p.gps)}. If you’re standing at the site, the saved location may be wrong.",
                        "Uko ${Geo.dist(d, m.prefs.miles)} kutoka mahali palipohifadhiwa · elekea ${Geo.heading(here, p.gps)}. Kama uko kwenye eneo, huenda mahali palipohifadhiwa si sahihi.")
                    else t("You’re ${Geo.dist(d, m.prefs.miles)} away · head ${Geo.heading(here, p.gps)}", "Uko umbali wa ${Geo.dist(d, m.prefs.miles)} · elekea ${Geo.heading(here, p.gps)}"),
                    color = if (far) Color(0xFFFFE1DD) else Color.White, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (far) Color(0x33FF786E) else Color(0x21FFFFFF)).padding(12.dp),
                )
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF9BE7BC))); Spacer(Modifier.width(6.dp))
                    Text(t("Live · GPS ±${fix!!.acc} m", "Moja kwa moja · GPS ±${fix!!.acc} m"), color = Color(0xFF9BE7BC), style = MaterialTheme.typography.labelSmall)
                }
            }
            if (p.gps != null) Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val ll = "${p.gps.lat},${p.gps.lng}"
                MapTile(t("Directions", "Maelekezo"), "compass", Modifier.weight(1f)) {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$ll"))) }
                }
                MapTile("Google Maps", "map", Modifier.weight(1f)) {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$ll?q=$ll(${Uri.encode(p.title)})"))) }
                }
                MapTile(t("Share", "Shiriki"), "share", Modifier.weight(1f)) {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, p.title + "\nhttps://www.google.com/maps?q=$ll"), null))
                }
            }
        }

        snap.repairs[p.id]?.let { r -> RepairTracker(m, p, r, here) }

        // what to do
        Row(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.card).border(1.dp, C.line, RoundedCornerShape(18.dp)).padding(5.dp)) {
            listOf("pin" to t("Pin location", "Weka mahali"), "visit" to t("Site visit", "Ziara"), "hist" to t("History", "Historia")).forEach { (k, label) ->
                val on = tab == k
                Text(label, color = if (on) Color.White else C.ink, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (on) C.primary else SolidColor(Color.Transparent))
                        .clickable(role = Role.Tab) { tab = k }.heightIn(min = 44.dp).padding(vertical = 12.dp).testTag("tab_$k"))
            }
        }
        Spacer(Modifier.height(14.dp))
        when (tab) {
            "pin" -> PinTab(m, p)
            "visit" -> VisitTab(m, p)
            else -> HistoryTab(m, p)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MapTile(label: String, icon: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Color(0x1FFFFFFF)).border(1.dp, Color(0x29FFFFFF), RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Color.White), contentAlignment = Alignment.Center) { Icon(Ic.get(icon), null, tint = Color(0xFF0A78C2), modifier = Modifier.size(21.dp)) }
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp), textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
private fun RepairCard(m: AppModel, p: Project, r: org.rafikioutreach.pin.data.Repair) {
    val scope = rememberScope()
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Card(Modifier.padding(top = 12.dp), brush = SolidColor(if (r.state == "notworking") C.badBg else C.warnBg)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.get("alert"), null, tint = if (r.state == "notworking") C.bad else Amber, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
            Text(if (r.state == "notworking") t("Reported as not working", "Imeripotiwa haifanyi kazi") else t("Reported as needing repair", "Imeripotiwa inahitaji matengenezo"),
                style = MaterialTheme.typography.titleSmall, color = if (r.state == "notworking") C.bad else C.warn)
        }
        Text(t("By ", "Na ") + r.by + " · " + whenText(r.at) + (if (r.urgency.isNotBlank()) " · " + urgencyName(r.urgency) else "") + (if (r.note.isNotBlank()) ": “${r.note}”" else ""),
            style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.padding(top = 4.dp))
        if (r.problems.isNotEmpty()) Text(r.problems.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = C.muted)
        Btn(t("Mark fixed", "Weka imerekebishwa"), {
            busy = true
            scope.launch {
                when (val o = m.submit(JSONObject().put("action", "fixed").put("id", p.id).put("note", ""), emptyList())) {
                    is Outcome.Sent, Outcome.Queued -> { m.localFixed(p); msg = if (o is Outcome.Queued) t("Saved on this phone. It will be sent when you have signal.", "Imehifadhiwa kwenye simu. Itatumwa ukipata mtandao.") else null }
                    is Outcome.Failed -> msg = o.text
                    Outcome.Locked -> {}
                }
                busy = false
            }
        }, Modifier.padding(top = 10.dp), icon = "wrench", ok = true, small = true, enabled = !busy)
        Msg(msg, "warn")
    }
}

@Composable
fun HistoryTab(m: AppModel, p: Project) {
    var notes by remember(p.id) { mutableStateOf<List<Note>?>(null) }
    var err by remember(p.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(p.id) { val (n, e) = m.history(p.id); notes = n; err = e }
    Text(t("Notes on this project in monday.com, newest first.", "Maelezo ya mradi huu kwenye monday.com, ya karibuni kwanza."), style = MaterialTheme.typography.bodyMedium, color = C.muted)
    Msg(err, "bad")
    val n = notes
    if (n == null && err == null) Text(t("Loading…", "Inapakia…"), color = C.muted, modifier = Modifier.padding(top = 12.dp))
    if (n != null && n.isEmpty()) EmptyDrop(t("No notes on this project yet.", "Bado hakuna maelezo kwenye mradi huu."))
    n?.forEach { note ->
        Card(Modifier.padding(top = 10.dp), pad = 14.dp) {
            Text(whenText(note.at) + " · " + note.by, style = MaterialTheme.typography.labelMedium, color = C.ink)
            Text(note.text, style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.padding(top = 4.dp))
            note.files.filter { it.isImage }.take(4).forEach { f ->
                NetImage(f.url, Modifier.padding(top = 8.dp).fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(14.dp)).background(C.tint)) {}
            }
        }
    }
}

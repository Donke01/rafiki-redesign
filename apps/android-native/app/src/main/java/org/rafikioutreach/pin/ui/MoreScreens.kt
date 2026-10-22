package org.rafikioutreach.pin.ui

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.clickable

import android.content.Intent
import androidx.compose.ui.platform.testTag
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.BuildConfig
import org.rafikioutreach.pin.Outcome
import org.rafikioutreach.pin.data.COUNTRIES
import org.rafikioutreach.pin.data.OutFile
import org.rafikioutreach.pin.t

private val TYPES = listOf(
    "borehole" to ("Borehole well" to "Kisima kirefu"), "collection" to ("Water collection" to "Ukusanyaji wa maji"), "school" to ("School" to "Shule"),
    "health" to ("Health" to "Afya"), "children" to ("Children's home" to "Makao ya watoto"), "farming" to ("Farming" to "Kilimo"),
    "community" to ("Community" to "Jamii"), "other" to ("Other" to "Nyingine"),
)

/** A project type as monday.com names it ("Borehole well"), in the app's language ("Kisima kirefu"). Unknown types stay as they are. */
fun typeName(type: String): String {
    val ty = type.lowercase()
    val k = when {
        "borehole" in ty -> "borehole"; "collection" in ty -> "collection"; "school" in ty -> "school"; "health" in ty -> "health"
        "orphan" in ty || "children" in ty -> "children"; "farm" in ty -> "farming"; ty == "community" -> "community"; else -> ""
    }
    return TYPES.firstOrNull { it.first == k }?.second?.let { t(type, it.second) } ?: type
}

@Composable
fun NewSiteScreen(m: AppModel, done: (String?) -> Unit) {
    val scope = rememberScope()
    var name by remember { mutableStateOf("") }
    var ck by remember { mutableStateOf(m.prefs.country.ifBlank { "ke" }) }
    var type by remember { mutableStateOf("borehole") }
    var village by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var shots by remember { mutableStateOf(listOf<Shot>()) }
    // a long press on the map ("Add a new site here") starts it at that point
    val at = remember { MapHome.takeNewSite() }
    val spot = remember { Spot().also { s -> if (at != null) s.ll = at } }
    var msg by remember { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val fix by m.fix.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(t("New site", "Eneo jipya"), style = MaterialTheme.typography.headlineLarge, color = C.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(t("The office checks every new site first. It isn’t on the website until they approve it.", "Ofisi hukagua kila eneo jipya kwanza. Halionekani kwenye tovuti hadi ofisi iliidhinishe."), style = MaterialTheme.typography.bodyMedium, color = C.muted)
        Card(Modifier.padding(top = 14.dp)) {
            Field(name, { name = it }, t("Site name", "Jina la eneo"), placeholder = t("e.g. Kiptoo Primary School", "mf. Shule ya Msingi Kiptoo"))
            Text(t("Country", "Nchi"), style = MaterialTheme.typography.labelMedium, color = C.ink, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
            COUNTRIES.entries.chunked(2).forEach { row -> Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { (k, n) -> Pill(n, ck == k, Modifier.weight(1f)) { ck = k } } } }
            Text(t("Type", "Aina"), style = MaterialTheme.typography.labelMedium, color = C.ink, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
            TYPES.chunked(2).forEach { row -> Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { (k, l) -> Pill(t(l.first, l.second), type == k, Modifier.weight(1f)) { type = k } } } }
            Field(village, { village = it }, t("Village / community", "Kijiji / jamii"), placeholder = t("e.g. Kiptoo, Nandi", "mf. Kiptoo, Nandi"))
            Field(note, { note = it }, t("Notes (optional)", "Maelezo (si lazima)"), placeholder = t("Who asked, how many people, current water source…", "Nani aliomba, watu wangapi, chanzo cha maji sasa…"), lines = 3)
        }
        Card(Modifier.padding(top = 14.dp)) {
            H2(t("Where is it?", "Iko wapi?"))
            SpotPicker(m, spot, at ?: fix?.ll, ck)
        }
        Card(Modifier.padding(top = 14.dp)) {
            H2(t("Photos (optional)", "Picha (si lazima)"))
            PhotoSlots(m, emptyList(), shots, { shots = it }, max = 5)
        }
        Btn(t("Add this site", "Ongeza eneo hili"), {
            val ll = spot.ll ?: return@Btn
            busy = true
            scope.launch {
                val body = JSONObject().put("action", "new").put("name", name.trim()).put("ck", ck).put("village", village.trim()).put("type", type).put("note", note.trim()).put("lat", ll.lat).put("lng", ll.lng)
                when (val o = m.submit(body, shots.map { OutFile(it.file.path, "photo", "Site photo") })) {
                    is Outcome.Sent -> { busy = false; m.refresh(); done(o.reply.optString("id").takeIf { it.isNotBlank() }) }
                    Outcome.Queued -> { busy = false; msg = "warn" to t("Saved on this phone. It will be added when you have signal.", "Imehifadhiwa kwenye simu. Itaongezwa ukipata mtandao."); name = ""; shots = emptyList() }
                    is Outcome.Failed -> { busy = false; msg = "bad" to o.text }
                    Outcome.Locked -> busy = false
                }
            }
        }, Modifier.fillMaxWidth().padding(top = 16.dp), icon = "plus", enabled = name.isNotBlank() && spot.ll != null && !busy)
        if (name.isBlank() || spot.ll == null) Msg(t("Add the name, then choose where it is.", "Weka jina, kisha chagua mahali lilipo."))
        msg?.let { (tone, text) -> Msg(text, tone) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun MeScreen(m: AppModel, open: (String) -> Unit = {}, report: () -> Unit = {}, notes: () -> Unit = {}, settings: (String) -> Unit = {}) {
    val s by m.snap.collectAsState()
    val act by m.activity.collectAsState()
    val mates by m.team.collectAsState()
    var pane by remember { mutableStateOf(m.prefs.mePane) }
    val scroll = rememberScrollState()
    val scope = rememberScope()
    LaunchedEffect(Unit) { launch { m.loadActivity() }; launch { m.loadTeam() } }
    LaunchedEffect(m.focusUpdate) { if (m.focusUpdate && pane != "me") { pane = "me"; m.prefs.mePane = "me" } }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp)) {
        // the title in the middle, Me | Team under it (the bell is in the app header, top right)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(t("Me & team", "Mimi na timu"), style = MaterialTheme.typography.headlineLarge, color = C.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
            SmallSeg(listOf("me" to t("Me", "Mimi"), "team" to t("Team", "Timu")), pane, tag = "pane_") { pane = it; m.prefs.mePane = it; scope.launch { scroll.scrollTo(0) } }
        }
        if (pane == "team") {
            TeamToday(m, mates, act, s, open)
            TeamMonth(m, act)
            TeamFeed(act, s, open)
        } else {
            MeHero(m, act, s, report)
            UpdateCard(m)
            WaitingList(m, s)
            MyWork(s, open)
            MyMonth(m, act)
            BadgeStamps(m)
            SettingsCard(m, settings)
            UpdateRow(m)
        }
    }
}

@Composable
private fun MeRow(k: String, v: String) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Text(k, color = Color(0xFFCFE8FA), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(v, color = Color.White, style = MaterialTheme.typography.titleSmall)
        }
        HorizontalDivider(color = Color(0x26FFFFFF))
    }
}

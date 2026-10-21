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
    "borehole" to ("Borehole well" to "Kisima cha borehole"), "collection" to ("Water collection" to "Ukusanyaji wa maji"), "school" to ("School" to "Shule"),
    "health" to ("Health" to "Afya"), "children" to ("Children's home" to "Makao ya watoto"), "farming" to ("Farming" to "Kilimo"),
    "community" to ("Community" to "Jamii"), "other" to ("Other" to "Nyingine"),
)

@Composable
fun NewSiteScreen(m: AppModel, done: (String?) -> Unit) {
    val scope = rememberScope()
    var name by remember { mutableStateOf("") }
    var ck by remember { mutableStateOf(m.prefs.country.ifBlank { "ke" }) }
    var type by remember { mutableStateOf("borehole") }
    var village by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var shots by remember { mutableStateOf(listOf<Shot>()) }
    val spot = remember { Spot() }
    var msg by remember { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val fix by m.fix.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(t("Add a new site", "Ongeza eneo jipya"), style = MaterialTheme.typography.headlineLarge, color = C.ink)
        Text(t("It goes onto the monday.com board as “Due Diligence”, so it isn’t on the website until the office moves it on.", "Inaingia kwenye monday.com kama “Due Diligence”, kwa hiyo haionekani kwenye tovuti hadi ofisi iisogeze."), style = MaterialTheme.typography.bodyMedium, color = C.muted)
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
            SpotPicker(m, spot, fix?.ll, ck)
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
fun MeScreen(m: AppModel, open: (String) -> Unit = {}, report: () -> Unit = {}) {
    val s by m.snap.collectAsState()
    val act by m.activity.collectAsState()
    val mates by m.team.collectAsState()
    var pane by remember { mutableStateOf(m.prefs.mePane) }
    val scroll = rememberScrollState()
    val scope = rememberScope()
    LaunchedEffect(Unit) { launch { m.loadActivity() }; launch { m.loadTeam() } }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("Me & team", "Mimi na timu"), style = MaterialTheme.typography.headlineLarge, color = C.ink, modifier = Modifier.weight(1f))
            SmallSeg(listOf("me" to t("Me", "Mimi"), "team" to t("Team", "Timu")), pane, tag = "pane_") { pane = it; m.prefs.mePane = it; scope.launch { scroll.scrollTo(0) } }
        }
        if (pane == "team") {
            TeamToday(m, mates, act, s, open)
            TeamMonth(m, act)
            TeamFeed(act, s, open)
        } else {
            MeHero(m, act, s, report)
            WaitingList(m, s)
            MyWork(s, open)
            MyMonth(m, act)
            ThisPhone(m, s)
            Card(Modifier.padding(top = 14.dp)) {
                H2(t("Monthly report", "Ripoti ya mwezi"))
                Text(t("Visits, repairs and how projects are doing for one month, ready to share as a PDF.", "Ziara, matengenezo na hali ya miradi kwa mwezi mmoja, tayari kushiriki kama PDF."), style = MaterialTheme.typography.bodyMedium, color = C.muted)
                Btn(t("Open report", "Fungua ripoti"), report, Modifier.padding(top = 10.dp), icon = "clip", small = true)
            }
            Stamps(m)
            Text("RAFIKI Pin " + BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 18.dp))
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

private val STAMPS = listOf(
    listOf("pin", 1, "First drop|Tone la kwanza", "Pin a location|Weka mahali", DropC.Blue), listOf("visit", 1, "First visit|Ziara ya kwanza", "Report a site visit|Ripoti ziara", DropC.Green),
    listOf("fixed", 1, "Fixer|Mrekebishaji", "Mark a repair fixed|Weka matengenezo kuwa yamekamilika", DropC.Coral), listOf("new", 1, "Explorer|Mvumbuzi", "Add a new site|Ongeza eneo jipya", DropC.Amber),
    listOf("pin", 10, "Mapper|Mchora ramani", "Pin 10 locations|Weka mahali 10", DropC.Purple), listOf("visit", 10, "Regular|Mzoefu", "Report 10 visits|Ripoti ziara 10", DropC.Green),
)
private fun tt(s: Any?) = (s as String).split("|").let { t(it[0], it[1]) }

/** A small collection that grows with what you do in the app. */
@Composable
private fun Stamps(m: AppModel) {
    val act by m.activity.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { m.loadActivity() }
    val me = m.prefs.name.lowercase()
    val n = (act ?: emptyList()).filter { it.optString("by").lowercase() == me }.groupingBy { it.optString("a") }.eachCount()
    Card(Modifier.padding(top = 14.dp).testTag("stamps")) {
        H2(t("Your stamps", "Stempu zako"))
        Text(t("Every point you pin and every visit you report adds to your collection.", "Kila mahali unapoweka na kila ziara unayoripoti inaongeza kwenye mkusanyiko wako."), style = MaterialTheme.typography.bodyMedium, color = C.muted)
        STAMPS.chunked(3).forEachIndexed { r, row ->
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEachIndexed { c, st ->
                    val k = r * 3 + c; val got = (n[st[0] as String] ?: 0) >= (st[1] as Int)
                    Column(Modifier.weight(1f).testTag(if (got) "stamp_got" else "stamp_no"), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.stampIn(k, got)) { Stamp(84.dp, st[4] as DropC, "", "%03d".format(k + 1), got, listOf(Mood.Smile, Mood.Wink, Mood.O)[k % 3], rot = if (k % 2 == 0) -2f else 2f) }
                        Text(tt(st[2]), style = MaterialTheme.typography.labelMedium, color = C.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                        Text(if (got) t("Earned", "Umepata") else tt(st[3]), style = MaterialTheme.typography.bodySmall, color = if (got) C.ok else C.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
        }
        Text(t("Show the welcome screens again", "Onyesha skrini za kukaribisha tena"), style = MaterialTheme.typography.labelLarge, color = C.blue2,
            modifier = Modifier.padding(top = 12.dp).clip(RoundedCornerShape(10.dp)).clickable { m.replayOnb = true }.padding(vertical = 10.dp).testTag("replay"))
    }
}

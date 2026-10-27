package org.rafikioutreach.pin.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import kotlinx.coroutines.launch
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
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

/** "24 Oct, 18:34" in the app's language (see [Dates]). */
fun whenText(iso: String): String = Dates.dateTime(iso)

@Composable
fun ProjectScreen(m: AppModel, id: String, startTab: String, back: () -> Unit) {
    val s by m.snap.collectAsState()
    val fix by m.fix.collectAsState()
    val snap = s ?: return
    val p = snap.projects.firstOrNull { it.id == id } ?: run { back(); return }
    val tabs = listOf("pin", "visit", "hist")
    // the three tabs: tap them, or swipe the page left and right
    val ctx = LocalContext.current
    // a half-done visit kept on the phone: open on Site visit, where it offers to carry on
    var tab by rememberSaveable(id) { mutableStateOf(if (startTab == "pin" && VisitDrafts.has(ctx, id)) "visit" else startTab) }
    // back from a tab chosen here: the tab the project opened on (Pin location), then the screen before
    val opened = rememberSaveable(id) { tab }
    BackStep(tab != opened) { tab = opened }
    val hp = rememberHaptics()
    val here = fix?.ll
    // the project name moves into the top bar once its big title scrolls away
    var top by remember { mutableFloatStateOf(0f) }
    var titleBottom by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    var dirs by remember(id) { mutableStateOf(false) }
    ShowInBar(m, p.title, titleBottom < top)

    Column(Modifier.fillMaxSize().onGloballyPositioned { top = it.boundsInRoot().top }.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 110.dp)) {
        if (p.photo != null) NetImage(m.api.photoUrl(p.photo, 960), Modifier.fillMaxWidth().aspectRatio(16f / 9f).padding(vertical = 6.dp).clip(RoundedCornerShape(24.dp)).background(C.tint)) {}

        // the project: glass in light colours (like the web app), deep blue in dark and bright sun
        val glass = C.inky
        val fg = if (glass) C.ink else Color.White
        val fg2 = if (glass) C.muted else Color(0xFFCFE8FA)
        val fg3 = if (glass) C.muted else Color(0xB3FFFFFF)
        val okc = if (glass) C.ok else Color(0xFF9BE7BC)
        Card(brush = if (glass) null else DeepBrush, modifier = Modifier.padding(top = 6.dp)) {
            Text(p.title, style = MaterialTheme.typography.headlineMedium, color = fg, modifier = Modifier.onGloballyPositioned { titleBottom = it.boundsInRoot().bottom })
            Text(p.sub, style = MaterialTheme.typography.bodyMedium, color = fg2)
            val v = snap.visits[p.id]
            // the state at a glance
            val rep = snap.repairs[p.id]
            when {
                rep != null -> Box(Modifier.padding(top = 8.dp)) { Tag(if (rep.state == "notworking") t("Not working", "Haifanyi kazi") else t("Needs repair", "Inahitaji matengenezo"), if (rep.state == "notworking") C.badBg else C.warnBg, if (rep.state == "notworking") C.bad else C.warn) }
                v?.state == "working" -> Box(Modifier.padding(top = 8.dp)) { Tag(t("Working", "Inafanya kazi"), C.okBg, C.ok) }
                else -> {}
            }
            if (testDue(snap, p)) WaterTestTag(Modifier.padding(top = 6.dp))
            Text(p.gps?.let { it.text() + t(" · saved location", " · mahali palipohifadhiwa") } ?: t("No location saved yet.", "Bado hakuna mahali palipohifadhiwa."), style = MaterialTheme.typography.bodySmall, color = fg3, modifier = Modifier.padding(top = 8.dp))
            Text(if (v != null) t("Last visit: ", "Ziara ya mwisho: ") + whenText(v.at) + " · " + stateName(v.state) else t("No visit recorded in the app yet.", "Bado hakuna ziara iliyorekodiwa kwenye programu."),
                style = MaterialTheme.typography.bodySmall, color = fg3)
            // who visited: a tap opens their contact sheet (Contact.kt)
            if (v != null && v.by.isNotBlank()) PersonChip(v.by, tag = "p_visit_by")
            if (snap.confirmed.contains(p.id)) Text(t("Location confirmed by the office", "Mahali pamethibitishwa na ofisi"), color = okc, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
            if (snap.admin && p.gps != null) {
                val scope = rememberScope(); var cm by remember(p.id) { mutableStateOf<String?>(null) }
                val on = snap.confirmed.contains(p.id)
                val act = { scope.launch { val x = m.api.admin(JSONObject().put("action", "confirm").put("id", p.id).put("on", !on)); if (x.ok) m.reload() else cm = org.rafikioutreach.pin.data.reasonText(x.reason) }; Unit }
                if (glass) Row(Modifier.padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { act() }.padding(vertical = 6.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ic.get("lock"), null, tint = C.blue2, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(if (on) t("Unlock this location", "Ondoa uthibitisho wa mahali") else t("Confirm this location", "Thibitisha mahali hapa"), color = C.blue2, style = MaterialTheme.typography.titleSmall)
                } else Text(if (on) t("Unlock this location", "Ondoa uthibitisho wa mahali") else t("Confirm this location", "Thibitisha mahali hapa"), color = Color.White, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(99.dp)).background(Color(0x26FFFFFF)).clickable(role = Role.Button) { act() }.padding(horizontal = 14.dp, vertical = 10.dp))
                cm?.let { Text(it, color = if (glass) C.bad else Color(0xFFFFE1DD), style = MaterialTheme.typography.bodySmall) }
            }
            if (here != null && p.gps != null) {
                val d = Geo.metres(here, p.gps)
                val far = d > 300
                Text(
                    if (far) t("You’re ${Geo.dist(d, m.prefs.miles)} from the saved point · head ${Geo.heading(here, p.gps)}. If you’re standing at the site, the saved location may be wrong.",
                        "Uko ${Geo.dist(d, m.prefs.miles)} kutoka mahali palipohifadhiwa · elekea ${Geo.heading(here, p.gps)}. Kama uko kwenye eneo, huenda mahali palipohifadhiwa si sahihi.")
                    else t("You’re ${Geo.dist(d, m.prefs.miles)} away · head ${Geo.heading(here, p.gps)}", "Uko umbali wa ${Geo.dist(d, m.prefs.miles)} · elekea ${Geo.heading(here, p.gps)}"),
                    color = if (glass) (if (far) C.bad else C.ink) else if (far) Color(0xFFFFE1DD) else Color.White, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (glass) (if (far) C.badBg else Color(0x140A78C2)) else if (far) Color(0x33FF786E) else Color(0x21FFFFFF)).padding(12.dp),
                )
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(okc)); Spacer(Modifier.width(6.dp))
                    Text(t("Live · GPS ±${fix!!.acc} m", "Moja kwa moja · GPS ±${fix!!.acc} m"), color = okc, style = MaterialTheme.typography.labelSmall)
                }
            }
            // one Directions button (it asks which map app) and Share: full width, one line each
            if (p.gps != null) Column(Modifier.padding(top = 14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val ll = "${p.gps.lat},${p.gps.lng}"
                Btn(t("Directions", "Maelekezo"), { hp.tap(); dirs = true }, Modifier.fillMaxWidth().testTag("prj_dirs"), icon = "compass", light = true, outline = true)
                Btn(t("Share location", "Shiriki mahali"), {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, p.title + "\nhttps://www.google.com/maps?q=$ll"), null))
                }, Modifier.fillMaxWidth().testTag("prj_share"), icon = "share", light = true, small = true, outline = true)
            }
            // the project's own chat thread (feat-chata)
            Btn(t("Chat", "Gumzo"), { hp.tap(); ChatNav.project(p.id) }, Modifier.padding(top = 8.dp).fillMaxWidth().testTag("prj_chat"), icon = "chat", light = true, small = true, outline = true)
        }
        if (dirs && p.gps != null) DirectionsSheet(p.title, p.gps) { dirs = false }

        snap.repairs[p.id]?.let { r -> RepairTracker(m, p, r, here) }
        ProjectCostLine(m, p)
        // the extras (photo timeline, the summary to share, a thank-you for donors) fold away under More
        ProjectMore(id) {
            ProjectExtrasBar(m, p) { if (tab != "hist") { hp.tap(); tab = "hist" } }
            DonorThanks(m, p)
        }

        // what to do
        Row(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.card).border(1.dp, C.line, RoundedCornerShape(18.dp)).padding(5.dp)) {
            listOf("pin" to t("Pin location", "Weka mahali"), "visit" to t("Site visit", "Ziara"), "hist" to t("History", "Historia")).forEachIndexed { i, (k, label) ->
                val on = tab == k
                Text(label, color = if (on) Color.White else C.ink, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (on) C.primary else SolidColor(Color.Transparent))
                        .clickable(role = Role.Tab) { if (tab != k) { hp.tap(); tab = k } }.heightIn(min = 44.dp).padding(vertical = 12.dp).testTag("tab_$k"))
            }
        }
        Spacer(Modifier.height(14.dp))
        var dragX by remember { mutableFloatStateOf(0f) }
        androidx.compose.animation.AnimatedContent(tab, Modifier.fillMaxWidth().pointerInput(tab) {
            // a sideways swipe on the page (not on a map or a scrolling row) moves to the next tab
            detectHorizontalDragGestures(onDragStart = { dragX = 0f }, onDragEnd = {
                val i = tabs.indexOf(tab)
                if (dragX < -90 && i < tabs.size - 1) { hp.tap(); tab = tabs[i + 1] } else if (dragX > 90 && i > 0) { hp.tap(); tab = tabs[i - 1] }
            }) { _, d -> dragX += d }
        }, label = "tabs", transitionSpec = {
            val fwd = tabs.indexOf(targetState) > tabs.indexOf(initialState); val ms = if (Motion.on) 260 else 0
            (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(ms)) { if (fwd) it / 3 else -it / 3 } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(ms))) togetherWith
                (androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(ms)) { if (fwd) -it / 3 else it / 3 } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(ms / 2)))
        }, contentAlignment = Alignment.TopStart) { k ->
            Column(Modifier.fillMaxWidth()) {
                when (k) {
                    "pin" -> PinTab(m, p)
                    "visit" -> VisitTab(m, p)
                    else -> HistoryTab(m, p)
                }
            }
        }
    }
}

/** Directions: Google Maps, or any other map app on the phone. */
@Composable
private fun DirectionsSheet(title: String, at: LatLng, close: () -> Unit) {
    val ctx = LocalContext.current
    val ll = "${at.lat},${at.lng}"
    DqmSheet("dir_sheet", close) { hide ->
        DqmSheetHead(t("Directions", "Maelekezo"), listOf(title), null, "dir_close", { hide {} }) { val hu = C.hue("near"); IconTile("compass", hu.fg, hu.bg, 56.dp) }
        Spacer(Modifier.height(14.dp))
        DirRow(t("Google Maps", "Google Maps"), t("Turn-by-turn directions", "Maelekezo hatua kwa hatua"), "dir_gmaps", GMAPS_ICON, "map") {
            hide { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$ll"))) } }
        }
        DirRow(t("Another map app", "Programu nyingine ya ramani"), t("Choose from the map apps on this phone", "Chagua kati ya programu za ramani kwenye simu hii"), "dir_other", null, "map") {
            hide { runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$ll?q=$ll(${Uri.encode(title)})")), t("Open with", "Fungua kwa"))) } }
        }
    }
}

@Composable
private fun DirRow(title: String, sub: String, tag: String, brandIcon: String?, icon: String, onClick: () -> Unit) {
    Row(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).border(1.dp, C.edge, RoundedCornerShape(18.dp)).clickable(role = Role.Button, onClick = onClick)
        .heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 10.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(C.tint), contentAlignment = Alignment.Center) {
            if (brandIcon != null) NetImage(brandIcon, Modifier.size(24.dp)) { Icon(Ic.get(icon), null, tint = C.blue, modifier = Modifier.size(21.dp)) }
            else Icon(Ic.get(icon), null, tint = C.blue, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = C.ink)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = C.muted)
        }
        Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(18.dp))
    }
}

/** "More for this project": folded until tapped, so the project page starts short. */
@Composable
private fun ProjectMore(id: String, content: @Composable () -> Unit) {
    var open by rememberSaveable(id) { mutableStateOf(false) }
    val hp = rememberHaptics()
    BackStep(open) { open = false }
    Card(Modifier.padding(top = 12.dp).testTag("prj_more"), pad = 0.dp) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { hp.tap(); open = !open }.heightIn(min = 60.dp).padding(horizontal = 14.dp, vertical = 8.dp).testTag("prj_more_toggle"),
            verticalAlignment = Alignment.CenterVertically) {
            val hu = C.hue("slate")
            IconTile("more", hu.fg, hu.bg, 38.dp); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t("More for this project", "Zaidi kuhusu mradi huu"), style = MaterialTheme.typography.titleSmall, color = C.ink)
                Text(t("Photo timeline, summary to share, thank-you for donors", "Picha kwa wakati, muhtasari, shukrani kwa wafadhili"), style = MaterialTheme.typography.bodySmall, color = C.muted)
            }
            Spacer(Modifier.width(6.dp))
            Icon(Ic.get(if (open) "up" else "down"), null, tint = C.muted, modifier = Modifier.size(20.dp))
        }
        if (open) Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp).testTag("prj_more_body")) { content() }
    }
}

private const val GMAPS_ICON = "https://www.google.com/s2/favicons?domain=maps.google.com&sz=64"

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
        if (r.by.isNotBlank()) PersonChip(r.by, Modifier.padding(top = 2.dp), tag = "p_repair_by")
        Text(whenText(r.at) + (if (r.urgency.isNotBlank()) " · " + urgencyName(r.urgency) else "") + (if (r.note.isNotBlank()) ": “${r.note}”" else ""),
            style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.padding(top = 2.dp))
        if (r.problems.isNotEmpty()) Text(r.problems.joinToString(", ") { problemName(it) }, style = MaterialTheme.typography.bodySmall, color = C.muted)
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
    n?.let { PhotoTimeline(m, p, it) }
    if (n != null && n.isEmpty()) EmptyDrop(t("No notes on this project yet.", "Bado hakuna maelezo kwenye mradi huu."))
    n?.forEach { note ->
        Card(Modifier.padding(top = 10.dp), pad = 14.dp) {
            // when, and who (a tap opens their contact sheet)
            val who = photoBy(note)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(whenText(note.at), style = MaterialTheme.typography.labelMedium, color = C.ink, modifier = Modifier.padding(end = 8.dp))
                if (who.isNotBlank()) PersonChip(who, Modifier.weight(1f, fill = false), tag = "h_by")
            }
            Text(note.text, style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.padding(top = 4.dp))
            note.files.filter { it.isImage }.take(4).forEach { f ->
                NetImage(f.url, Modifier.padding(top = 8.dp).fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(14.dp)).background(C.tint)) {}
            }
        }
    }
}

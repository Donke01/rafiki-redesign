package org.rafikioutreach.pin.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.BuildConfig
import org.rafikioutreach.pin.data.ALERT_KINDS
import org.rafikioutreach.pin.data.AlertWorker
import org.rafikioutreach.pin.data.COUNTRIES
import org.rafikioutreach.pin.t

// Me & team: the notifications bell, and Me › Settings (personal details, PIN, notifications, help, about).

/** The bell beside "Me & team": a red count of what's unread (it pops in, and the bell rings now and then); tap for Notifications. */
@Composable
fun Bell(m: AppModel, onOpen: () -> Unit) {
    val tick by m.noteTick.collectAsState()
    val act by m.activity.collectAsState()
    val s by m.snap.collectAsState()
    val n = remember(tick, act, s) { m.unread() }
    val h = rememberHaptics()
    val ring = n > 0 && Motion.on && Motion.loops
    val tr = androidx.compose.animation.core.rememberInfiniteTransition(label = "bell")
    val swing by if (!ring) remember { mutableFloatStateOf(0f) } else tr.animateFloat(0f, 0f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.keyframes {
        durationMillis = 5000; 0f at 500; 17f at 650; -15f at 800; 11f at 950; -8f at 1100; 5f at 1250; -2f at 1400; 0f at 1550
    }), label = "swing")
    Box(Modifier.size(46.dp)) {
        Box(Modifier.size(44.dp).align(Alignment.Center).clip(CircleShape).background(C.card).border(1.dp, C.line, CircleShape)
            .clickable(role = Role.Button) { h.tap(); onOpen() }.semantics { contentDescription = t("Notifications", "Arifa") + if (n > 0) " · $n " + t("unread", "hazijasomwa") else "" }.testTag("bell"),
            contentAlignment = Alignment.Center) {
            Icon(Ic.get("bell"), null, tint = C.ink, modifier = Modifier.size(21.dp).graphicsLayer { rotationZ = swing; transformOrigin = TransformOrigin(0.5f, 0.1f) })
        }
        // keyed on the number, so a new count pops in again
        if (n > 0) key(n) {
            Box(Modifier.align(Alignment.TopEnd).pop().defaultMinSize(minWidth = 20.dp, minHeight = 20.dp).clip(RoundedCornerShape(99.dp)).background(C.card).padding(2.dp)
                .clip(RoundedCornerShape(99.dp)).background(C.bad).padding(horizontal = 5.dp).testTag("bell_count"), contentAlignment = Alignment.Center) {
                Text(if (n > 9) "9+" else "$n", color = if (C.dark) Color(0xFF2A0B08) else Color.White, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp))
            }
        }
    }
}

/** Me › Settings: account, help and about, each opening its own page. */
@Composable
fun SettingsCard(m: AppModel, go: (String) -> Unit) {
    val s by m.snap.collectAsState()
    val me = s?.me ?: org.rafikioutreach.pin.data.Me()
    val ctx = LocalContext.current
    Card(Modifier.padding(top = 14.dp).testTag("settings")) {
        Text(t("Settings", "Mipangilio"), style = MaterialTheme.typography.titleLarge, color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        // most used first: who you are, then keeping the app safe and telling you things, then how it looks, then help
        Group(t("Account", "Akaunti"))
        MondayRow(m, s) { go("monday") }
        SetRow("person", "blue", t("Personal details", "Taarifa zangu"), listOf(me.nick.ifBlank { m.prefs.name }, me.phone).filter { it.isNotBlank() }.joinToString(" · "), "set_profile") { go("profile") }
        SetRow("lock", "purple", if (me.kind == "monday") t("Sign-in", "Kuingia") else t("Change my PIN", "Badilisha PIN yangu"), when (me.kind) {
            "monday" -> t("Signed in with monday.com", "Umeingia kwa monday.com"); "app" -> t("Your own PIN", "PIN yako mwenyewe")
            "cloudflare" -> t("Set by the office", "Imewekwa na ofisi"); else -> t("Shared team PIN", "PIN ya timu") }, "set_pin") { go("pin") }
        // This phone: what only this phone remembers
        Column(Modifier.testTag("this_phone")) {
            Group(t("Security and alerts", "Usalama na arifa"))
            BioRow(m)
            SetRow("bell", "amber", t("Notifications", "Arifa"), if (m.prefs.phoneAlerts && AlertWorker.allowed(ctx)) t("On this phone too", "Pia kwenye simu hii") else t("In the app only", "Ndani ya programu tu"), "set_notify") { go("notify") }
            ShareRow(m) { go("share") }
            Group(t("Display", "Mwonekano"))
            SetRow("sun", "amber", t("Appearance", "Mwonekano"), lookText(), "set_look") { go("look") }
            SetRow("globe", "blue", t("Language", "Lugha"), if (org.rafikioutreach.pin.Lang.sw) "Kiswahili" else "English", "set_lang") { go("lang") }
            SetRow("nav", "green", t("Distances", "Umbali"), if (m.prefs.miles) t("Miles", "Maili") else t("Kilometres (km)", "Kilomita (km)"), "set_units") { go("units") }
        }
        Group(t("Help and support", "Msaada"))
        SetRow("search", "teal", t("Help and FAQs", "Msaada na maswali"), t("Pinning, visits, no signal, updates", "Kuweka mahali, ziara, bila mtandao, masasisho"), "set_help") { go("help") }
        SetRow("chat", "green", t("Contact the office", "Wasiliana na ofisi"), "info@rafikioutreach.org", "set_contact") { mail(ctx, "RAFIKI Pin: " + m.prefs.name, "") }
        SetRow("alert", "coral", t("Report a problem with the app", "Ripoti tatizo la programu"), t("Sends the app version with your message", "Inatuma toleo la programu pamoja na ujumbe wako"), "set_problem") {
            mail(ctx, "RAFIKI Pin problem", "\n\nWhat happened:\n\n\n---\n${m.prefs.name} · Android app ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
        }
        Group(t("About", "Kuhusu"))
        SetRow("drop", "blue", t("About RAFIKI Pin", "Kuhusu RAFIKI Pin"), t("Version ", "Toleo ") + BuildConfig.VERSION_NAME, "set_about") { go("about") }
        SetRow("lock", "slate", t("Privacy policy", "Sera ya faragha"), "", "set_privacy") { web(ctx, "/privacy-policy") }
        SetRow("clip", "slate", t("Terms of use", "Masharti ya matumizi"), "", "set_terms") { web(ctx, "/terms-of-use") }
        // last, on its own
        HorizontalDivider(color = C.line, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        SetRow("undo", "coral", t("Sign out of this phone", "Toka kwenye simu hii"), t("You’ll need your PIN to come back in", "Utahitaji PIN yako kurudi"), "set_signout") { go("signout") }
    }
}

/** A short message at the bottom of the screen (posted to the main thread). */
private fun toast(ctx: android.content.Context, text: String) { android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { android.widget.Toast.makeText(ctx.applicationContext, text, android.widget.Toast.LENGTH_SHORT).show() } } }

private fun mail(ctx: android.content.Context, subject: String, body: String) {
    val u = Uri.parse("mailto:info@rafikioutreach.org?subject=" + Uri.encode(subject) + if (body.isNotBlank()) "&body=" + Uri.encode(body) else "")
    runCatching { ctx.startActivity(Intent(Intent.ACTION_SENDTO, u)) }
}
private fun web(ctx: android.content.Context, path: String) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.rafikioutreach.org$path"))) } }

@Composable
private fun Group(label: String) = Text(label.uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp), color = C.muted, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth().padding(start = 2.dp, top = 16.dp, bottom = 2.dp))

@Composable
private fun SetRow(icon: String, hue: String, title: String, sub: String, tag: String, onClick: () -> Unit) {
    val hu = C.hue(hue)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp, horizontal = 2.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon, hu.fg, hu.bg, 40.dp); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = C.ink)
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(18.dp))
    }
}

/** The colours in use: what was chosen, or the phone's own light or dark. */
@Composable
private fun lookMode(): String { val sys = androidx.compose.foundation.isSystemInDarkTheme(); return Look.mode.ifBlank { if (sys) "dark" else "light" } }

/** "Light · normal text" under Appearance. */
@Composable
private fun lookText(): String {
    val col = if (Look.mode.isBlank()) t("Same as phone", "Kama simu") else when (lookMode()) { "dark" -> t("Dark", "Giza"); "sun" -> t("Sun", "Jua"); else -> t("Light", "Mwanga") }
    val size = when (TextSize.value) { "s" -> t("small text", "maandishi madogo"); "l" -> t("large text", "maandishi makubwa"); "xl" -> t("larger text", "maandishi makubwa zaidi"); else -> t("normal text", "maandishi ya kawaida") }
    return "$col · $size"
}

/** Settings › This phone › Unlock with fingerprint or face: a switch right in the row. */
@Composable
private fun BioRow(m: AppModel) {
    val ctx = LocalContext.current
    val can = remember { Bio.available(ctx) }
    var bio by remember { mutableStateOf(m.prefs.bio) }
    var bioMsg by remember { mutableStateOf<String?>(null) }
    if (!can && !bio) return
    val hu = C.hue("due")
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 2.dp).testTag("bio_card"), verticalAlignment = Alignment.CenterVertically) {
        IconTile("finger", hu.fg, hu.bg, 40.dp); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t("Fingerprint or face unlock", "Fungua kwa kidole au uso"), style = MaterialTheme.typography.titleSmall, color = C.ink, maxLines = 2)
            Text(bioMsg ?: if (bio) t("On · asked when the app opens and after a minute away", "Imewashwa · inaulizwa programu ikifunguliwa na baada ya dakika moja") else t("Off · keeps the app private if someone picks up your phone", "Imezimwa · inalinda programu mtu akishika simu yako"),
                style = MaterialTheme.typography.bodySmall, color = if (bioMsg != null) C.bad else C.muted)
        }
        Spacer(Modifier.width(8.dp))
        Toggle(bio, if (bio) t("Turn off", "Zima") else t("Turn on", "Washa"), Modifier.testTag("bio_switch")) {
            if (bio) { bio = false; m.prefs.bio = false; bioMsg = null }
            else Bio.ask(ctx, t("Turn on fingerprint unlock", "Washa kufungua kwa alama ya kidole")) { ok -> if (ok) { bio = true; m.prefs.bio = true; bioMsg = null } else bioMsg = t("Not turned on.", "Haijawashwa.") }
        }
    }
}

/** Settings › Account › Your name on monday.com: one row ("Connected as … ›"); the steps to connect are on their own page
 *  (not shown when signed in with monday.com itself). */
@Composable
private fun MondayRow(m: AppModel, snap: org.rafikioutreach.pin.data.Snapshot?, onClick: () -> Unit) {
    if (snap == null || m.prefs.session.isNotBlank()) return
    val linked = snap.linked.isNotBlank()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp, horizontal = 2.dp).testTag("monday_row"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(MondayBlue.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) { Brand("monday.com", 24.dp, "link", MondayBlue) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t("Your name on monday.com", "Jina lako kwenye monday.com"), style = MaterialTheme.typography.titleSmall, color = C.ink)
            Text(when { linked -> t("Connected as ${snap.linked}", "Imeunganishwa kama ${snap.linked}"); snap.who.isBlank() -> t("Changes go in under the shared account", "Mabadiliko yanaingia kwa akaunti ya pamoja"); else -> t("Not connected · takes 20 seconds", "Haijaunganishwa · sekunde 20") },
                style = MaterialTheme.typography.bodySmall, color = if (linked) C.ok else C.muted, modifier = if (linked) Modifier.testTag("monday_connected") else Modifier)
        }
        Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun chevTurn(target: Float): State<Float> = androidx.compose.animation.core.animateFloatAsState(target, androidx.compose.animation.core.tween(if (Motion.on) 200 else 0), label = "chev")

/** A choice on a Settings page: a name, a line under it, and a tick when it's the one in use. */
@Composable
private fun SetChoice(title: String, sub: String, on: Boolean, tag: String, lead: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val h = rememberHaptics()
    val edge by androidx.compose.animation.animateColorAsState(if (on) C.blue else C.line, androidx.compose.animation.core.tween(if (Motion.on) 200 else 0), label = "edge")
    Row(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (on) C.tint else Color.Transparent).border(if (on) 2.dp else 1.dp, edge, RoundedCornerShape(16.dp))
        .clickable(role = Role.RadioButton) { h.tap(); onClick() }.semantics { stateDescription = if (on) "selected" else "" }.padding(horizontal = 14.dp, vertical = 12.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically) {
        if (lead != null) { lead(); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = C.ink)
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = C.muted)
        }
        Box(Modifier.size(24.dp).clip(CircleShape).background(if (on) C.blue else Color.Transparent).border(if (on) 0.dp else 1.5.dp, if (on) Color.Transparent else C.edge, CircleShape), contentAlignment = Alignment.Center) {
            if (on) Icon(Ic.get("check"), null, tint = Color.White, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun SetLabel(text: String) = Text(text.uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp), color = C.muted, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth().padding(start = 2.dp, top = 18.dp, bottom = 2.dp))

/** A small sample of the app in the colours: what Light, Dark and Sun look like before you pick one. */
@Composable
private fun Swatch(p: Palette) {
    Box(Modifier.size(46.dp, 38.dp).clip(RoundedCornerShape(10.dp)).background(p.bg).border(1.dp, if (p === Sun) Color.Black else p.line, RoundedCornerShape(10.dp)).padding(5.dp)) {
        Column(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(p.card).padding(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(Modifier.fillMaxWidth(0.8f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.ink))
            Box(Modifier.fillMaxWidth(0.55f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(p.muted))
            Box(Modifier.fillMaxWidth(0.4f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(p.blue))
        }
    }
}

/** "Same as phone": half Light, half Dark. */
@Composable
private fun PhoneSwatch() {
    Row(Modifier.size(46.dp, 38.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, C.line, RoundedCornerShape(10.dp))) {
        listOf(Light, Dark).forEach { p ->
            Box(Modifier.weight(1f).fillMaxHeight().background(p.bg).padding(start = if (p === Light) 5.dp else 0.dp, end = if (p === Dark) 5.dp else 0.dp, top = 5.dp, bottom = 5.dp)) {
                Column(Modifier.fillMaxSize().background(p.card).padding(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.fillMaxWidth(0.8f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.ink))
                    Box(Modifier.fillMaxWidth(0.5f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(p.blue))
                }
            }
        }
    }
}

/** About › Check for updates: asks the website now; a newer app opens Me, where it downloads and installs. */
@Composable
private fun CheckUpdatesRow(m: AppModel) {
    val u by m.update.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf(false) }
    val scope = rememberScope()
    val ready = (u?.code ?: 0) > BuildConfig.VERSION_CODE
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = !busy, role = Role.Button) {
        if (ready) { m.focusUpdate = true; m.goTab = "me"; return@clickable }
        busy = true; failed = false; asked = true
        scope.launch { failed = !m.checkUpdate(); busy = false; if (m.updateReady) { m.focusUpdate = true; m.goTab = "me" } }
    }.padding(vertical = 10.dp, horizontal = 2.dp).testTag("about_update"), verticalAlignment = Alignment.CenterVertically) {
        val hu = C.hue(if (ready) "pin" else "near"); IconTile(if (ready) "upload" else "refresh", hu.fg, hu.bg, 40.dp); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(if (ready) t("Update available · ${u!!.name}", "Toleo jipya · ${u!!.name}") else t("Check for updates", "Angalia masasisho"), style = MaterialTheme.typography.titleSmall, color = C.ink)
            Text(when {
                busy -> t("Checking…", "Inaangalia…")
                failed -> t("Couldn’t check. Needs a signal.", "Haikuweza kuangalia. Inahitaji mtandao.")
                ready -> t("Tap to update", "Gusa kusasisha")
                asked -> t("You’re up to date ✓", "Uko na toleo jipya ✓")
                else -> t("Version ", "Toleo ") + BuildConfig.VERSION_NAME
            }, style = MaterialTheme.typography.bodySmall, color = if (failed) C.bad else if (asked && !busy && !ready) C.ok else C.muted, modifier = Modifier.testTag("about_update_status"))
        }
        if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = C.blue)
        else Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(18.dp))
    }
}

/** Settings › This phone › Appearance: a live preview, then colours and text size. */
@Composable
private fun LookPage(m: AppModel) {
    val miles = m.prefs.miles
    // the preview: a project row as it shows in the app, in the colours and text size chosen
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.bg).border(1.dp, C.line, RoundedCornerShape(18.dp)).padding(12.dp).testTag("look_preview")) {
        Text(t("PREVIEW", "MFANO"), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp), color = C.muted)
        Row(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.card).border(1.dp, C.line, RoundedCornerShape(16.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val hu = C.hue("visit"); IconTile("drop", hu.fg, hu.bg, 40.dp); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Kapsaret Borehole", style = MaterialTheme.typography.titleSmall, color = C.ink)
                Text("Eldoret · " + if (miles) t("1.5 miles away", "maili 1.5 kutoka ulipo") else t("2.4 km away", "km 2.4 kutoka ulipo"), style = MaterialTheme.typography.bodySmall, color = C.muted)
            }
            Tag(t("Working", "Inafanya kazi"), C.okBg, C.ok)
        }
        Text(t("Visited 3 days ago by Grace. The pump works and the water is clear.", "Ilitembelewa siku 3 zilizopita na Grace. Pampu inafanya kazi na maji ni safi."), style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.padding(top = 10.dp, start = 2.dp, end = 2.dp))
    }
    SetLabel(t("Colours", "Rangi"))
    val sel = Look.mode.ifBlank { "system" }
    SetChoice(t("Same as phone", "Kama simu"), t("Light or dark, as the phone is set", "Mwanga au giza, kama simu ilivyowekwa"), sel == "system", "theme_system", lead = { PhoneSwatch() }) { Look.mode = ""; m.prefs.theme = "" }
    listOf(Triple("light", t("Light", "Mwanga"), t("Bright and clear, for most days", "Angavu na wazi, kwa siku nyingi")) to Light,
        Triple("dark", t("Dark", "Giza"), t("Easy on the eyes at night", "Rahisi kwa macho usiku")) to Dark,
        Triple("sun", t("Sun", "Jua"), t("Black on white, for bright sunshine outdoors", "Nyeusi juu ya nyeupe, kwa jua kali nje")) to Sun).forEach { (o, p) ->
        SetChoice(o.second, o.third, sel == o.first, "theme_" + o.first, lead = { Swatch(p) }) { Look.mode = o.first; m.prefs.theme = o.first }
    }
    SetLabel(t("Text size", "Ukubwa wa maandishi"))
    // four tiles, each "Aa" in its own size, so every label fits even at Larger
    val h = rememberHaptics()
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Triple("s", t("Small", "Ndogo"), 14), Triple("", t("Normal", "Kawaida"), 17), Triple("l", t("Large", "Kubwa"), 20), Triple("xl", t("Larger", "Kubwa zaidi"), 23)).forEach { (k, l, fs) ->
            val on = TextSize.value == k
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(if (on) C.tint else Color.Transparent).border(if (on) 2.dp else 1.dp, if (on) C.blue else C.line, RoundedCornerShape(16.dp))
                .clickable(role = Role.RadioButton) { h.tap(); TextSize.value = k; m.prefs.textSize = k }.semantics { stateDescription = if (on) "selected" else "" }
                .padding(horizontal = 4.dp, vertical = 10.dp).testTag("size_$k"), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.heightIn(min = 30.dp), contentAlignment = Alignment.BottomCenter) { Text("Aa", style = MaterialTheme.typography.titleLarge.copy(fontSize = fs.sp, lineHeight = fs.sp), color = C.ink, maxLines = 1) }
                Text(l, style = MaterialTheme.typography.labelSmall, color = if (on) C.ink else C.muted, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    Text(t("Changes every screen, straight away.", "Inabadilisha kila skrini, papo hapo."), style = MaterialTheme.typography.bodySmall, color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
}

private val FAQ = listOf(
    listOf("How do I pin a project’s location?", "Ninawekaje mahali pa mradi?", "Stand at the site, open the project, then Pin location › Use my location. Check the point on the map and save. You can also tap the map, or paste a Google Maps link.", "Simama kwenye eneo, fungua mradi, kisha Weka mahali › Tumia mahali nilipo. Hakikisha kwenye ramani kisha hifadhi. Unaweza pia kugusa ramani, au kubandika kiungo cha Google Maps."),
    listOf("How do I report a visit?", "Ninaripotije ziara?", "Open the project › Site visit. Tap “I’ve arrived”, choose how it is, go through the checklist, add photos, then Send. Each kind of project has its own checklist.", "Fungua mradi › Ziara. Gusa “Nimefika”, chagua hali, pitia orodha ya ukaguzi, ongeza picha, kisha Tuma. Kila aina ya mradi ina orodha yake."),
    listOf("What happens with no signal?", "Nini kinatokea bila mtandao?", "Keep working. Everything is saved on this phone and sent to monday.com by itself when there’s signal. Me shows what is still waiting.", "Endelea kufanya kazi. Kila kitu kinahifadhiwa kwenye simu na kutumwa monday.com chenyewe mtandao ukipatikana. Mimi inaonyesha kinachosubiri."),
    listOf("Why does my change need approval?", "Kwa nini badiliko langu linahitaji kuidhinishwa?", "If an admin confirmed a location, moving it by more than 500 m goes to the office for approval first. New sites and story moments are checked too.", "Ikiwa msimamizi amethibitisha mahali, kuhamisha zaidi ya mita 500 kunaenda ofisini kuidhinishwa kwanza. Maeneo mapya na hadithi pia hukaguliwa."),
    listOf("How do I update the app?", "Ninasasishaje programu?", "Me › App updates › Update now. It downloads and installs in the app. On Samsung phones, turn Auto Blocker off for a moment if the update is cancelled.", "Mimi › Masasisho › Sasisha sasa. Inapakuliwa na kusakinishwa ndani ya programu. Kwenye simu za Samsung, zima Auto Blocker kwa muda ikiwa sasisho limeghairiwa."),
    listOf("Why don’t I get phone alerts?", "Kwa nini sipati arifa kwenye simu?", "Check Me › Settings › Notifications, and that Android allows RAFIKI Pin to show notifications. The phone checks about every 15 minutes when there’s signal; battery saver can make it slower.", "Angalia Mimi › Mipangilio › Arifa, na kwamba Android inaruhusu RAFIKI Pin kuonyesha arifa. Simu inaangalia kila dakika 15 hivi kukiwa na mtandao; kuokoa betri kunaweza kuchelewesha."),
    listOf("I forgot my PIN", "Nimesahau PIN yangu", "Ask an admin in the office. They can give you a new PIN in Office › Team.", "Muombe msimamizi ofisini. Anaweza kukupa PIN mpya kwenye Ofisi › Timu."),
    listOf("Who can see my phone number?", "Nani anaweza kuona namba yangu ya simu?", "Only the RAFIKI team in this app, so they can call or WhatsApp you from Me & team. It isn’t shown on the website.", "Timu ya RAFIKI pekee kwenye programu hii, ili wakupigie au wakutumie WhatsApp kutoka Mimi na timu. Haionyeshwi kwenye tovuti."),
)

/** One Settings page (opened from the Settings card). */
@Composable
fun SettingsPage(m: AppModel, which: String, back: () -> Unit) {
    val s by m.snap.collectAsState()
    val me = s?.me ?: org.rafikioutreach.pin.data.Me()
    val scope = rememberScope()
    val ctx = LocalContext.current
    var msg by remember { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 100.dp)) {
        val title = when (which) { "profile" -> t("Personal details", "Taarifa zangu"); "pin" -> if (me.kind == "monday") t("Sign-in", "Kuingia") else t("Change my PIN", "Badilisha PIN yangu"); "notify" -> t("Notification settings", "Mipangilio ya arifa"); "monday" -> t("Your name on monday.com", "Jina lako kwenye monday.com"); "help" -> t("Help and FAQs", "Msaada na maswali"); "signout" -> t("Sign out of this phone?", "Toka kwenye simu hii?"); "look" -> t("Appearance", "Mwonekano"); "lang" -> t("Language", "Lugha"); "units" -> t("Distances", "Umbali"); else -> t("About RAFIKI Pin", "Kuhusu RAFIKI Pin") }
        Text(title, style = MaterialTheme.typography.headlineLarge, color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp).testTag("set_title"))
        Card(Modifier.padding(top = 8.dp)) {
            when (which) {
                "profile" -> {
                    val own = m.prefs.name.isNotBlank() && me.kind != "shared"
                    var name by remember { mutableStateOf(m.prefs.name) }
                    var nick by remember { mutableStateOf(me.nick) }
                    var phone by remember { mutableStateOf(me.phone) }
                    var home by remember { mutableStateOf(me.home.ifBlank { m.prefs.country }) }
                    Field(name, { if (!own) name = it }, t("Full name", "Jina kamili"), Modifier.testTag("pf_name"), readOnly = own)
                    Text(if (own) t("Your name comes from your sign-in. Ask the office to change it.", "Jina lako linatoka kwenye kuingia kwako. Omba ofisi ibadilishe.") else t("With the shared team PIN, this is the name your changes go in under.", "Kwa PIN ya timu, haya ndiyo majina yanayoonekana kwenye mabadiliko yako."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 4.dp))
                    if (own) {
                        Field(nick, { nick = it.take(40) }, t("What the team calls you (optional)", "Jina la kuitwa na timu (si lazima)"), Modifier.testTag("pf_nick"), t("e.g. Mery", "mf. Mery"))
                        Field(phone, { phone = it.take(30) }, t("Phone (for calls and WhatsApp from the team)", "Simu (kwa simu na WhatsApp kutoka kwa timu)"), Modifier.testTag("pf_phone"), "07… / +254 7…", phone = true)
                        Text(t("Home country", "Nchi ya nyumbani"), style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp), color = C.ink, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                        COUNTRIES.entries.chunked(2).forEach { row -> Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { (k, n) -> Pill(n, home == k, Modifier.weight(1f)) { home = if (home == k) "" else k } } } }
                    } else Text(t("Ask the office for your own PIN to add a phone number and the rest.", "Omba ofisi ikupe PIN yako mwenyewe ili kuongeza namba ya simu na mengine."), style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.padding(top = 12.dp))
                    Btn(if (busy) t("Saving…", "Inahifadhi…") else t("Save", "Hifadhi"), {
                        if (!own) { if (name.trim().length < 2) { msg = "bad" to t("Please write your name.", "Tafadhali andika jina lako."); return@Btn }; m.prefs.name = name.trim(); back(); return@Btn }
                        busy = true; scope.launch { val e = m.saveProfile(nick, phone, home); busy = false; if (e == null) { toast(ctx, t("Saved ✓", "Imehifadhiwa ✓")); back() } else msg = "bad" to e }
                    }, Modifier.fillMaxWidth().padding(top = 18.dp).testTag("pf_save"), enabled = !busy)
                }
                "pin" -> if (me.kind != "app") {
                    Text(when (me.kind) {
                        "monday" -> t("You sign in with your monday.com account, so there’s no PIN to change. To change your monday.com password, use monday.com.", "Unaingia kwa akaunti yako ya monday.com, kwa hiyo hakuna PIN ya kubadilisha. Kubadilisha nenosiri la monday.com, tumia monday.com.")
                        "cloudflare" -> t("Your PIN was set up by the office, so only they can change it. Ask an admin for a new one in Office › Team.", "PIN yako iliwekwa na ofisi, kwa hiyo wao pekee wanaweza kuibadilisha. Muombe msimamizi akupe mpya kwenye Ofisi › Timu.")
                        else -> t("You’re using the shared team PIN. Ask the office for your own PIN, then you can change it here.", "Unatumia PIN ya timu. Omba ofisi ikupe PIN yako mwenyewe, kisha unaweza kuibadilisha hapa.")
                    }, style = MaterialTheme.typography.bodyMedium, color = C.text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                } else {
                    var cur by remember { mutableStateOf("") }; var n1 by remember { mutableStateOf("") }; var n2 by remember { mutableStateOf("") }
                    Field(cur, { cur = it.filter(Char::isDigit).take(6) }, t("Current PIN", "PIN ya sasa"), Modifier.testTag("cp_old"), secret = true)
                    Field(n1, { n1 = it.filter(Char::isDigit).take(6) }, t("New PIN (4 to 6 digits)", "PIN mpya (tarakimu 4 hadi 6)"), Modifier.testTag("cp_new"), secret = true)
                    Field(n2, { n2 = it.filter(Char::isDigit).take(6) }, t("New PIN again", "PIN mpya tena"), Modifier.testTag("cp_new2"), secret = true)
                    Text(t("Avoid easy ones like 1234 or 0000. Don’t share it: your changes go in under your name.", "Epuka rahisi kama 1234 au 0000. Usimpe mtu: mabadiliko yako yanaingia kwa jina lako."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 10.dp))
                    Btn(if (busy) t("Changing…", "Inabadilisha…") else t("Change PIN", "Badilisha PIN"), {
                        if (n1 != n2) { msg = "bad" to t("The two new PINs aren’t the same.", "PIN mpya mbili hazifanani."); return@Btn }
                        busy = true; scope.launch { val e = m.changePin(cur, n1); busy = false; if (e == null) { toast(ctx, t("PIN changed ✓ Use the new one next time.", "PIN imebadilishwa ✓ Tumia mpya wakati ujao.")); back() } else msg = "bad" to e }
                    }, Modifier.fillMaxWidth().padding(top = 16.dp).testTag("cp_save"), enabled = !busy && cur.length >= 4 && n1.length >= 4)
                }
                "notify" -> {
                    var phoneOn by remember { mutableStateOf(m.prefs.phoneAlerts && AlertWorker.allowed(ctx)) }
                    var off by remember { mutableStateOf(m.prefs.noteOff) }
                    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
                        phoneOn = ok; m.prefs.phoneAlerts = ok; if (ok) AlertWorker.schedule(ctx.applicationContext)
                        if (!ok) msg = "warn" to t("Android didn’t allow notifications. You can allow them in the phone’s Settings › Apps › RAFIKI Pin › Notifications.", "Android haikuruhusu arifa. Unaweza kuziruhusu kwenye Settings › Apps › RAFIKI Pin › Notifications.")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val hu = C.hue("amber"); IconTile("bell", hu.fg, hu.bg, 40.dp); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t("Alerts on this phone", "Arifa kwenye simu hii"), style = MaterialTheme.typography.titleSmall, color = C.ink)
                            Text(t("Even when the app is closed. Checks about every 15 minutes when there’s signal.", "Hata programu ikiwa imefungwa. Inaangalia kila dakika 15 hivi kukiwa na mtandao."), style = MaterialTheme.typography.bodySmall, color = C.muted)
                        }
                        Toggle(phoneOn, t("Alerts on this phone", "Arifa kwenye simu hii"), Modifier.testTag("phone_alerts")) {
                            if (phoneOn) { phoneOn = false; m.prefs.phoneAlerts = false; AlertWorker.cancel(ctx.applicationContext) }
                            else if (Build.VERSION.SDK_INT >= 33 && !AlertWorker.allowed(ctx)) ask.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            else { phoneOn = true; m.prefs.phoneAlerts = true; AlertWorker.schedule(ctx.applicationContext) }
                        }
                    }
                    Text(t("TELL ME ABOUT", "NIARIFU KUHUSU"), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp), color = C.muted, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth().padding(start = 2.dp, top = 18.dp, bottom = 4.dp))
                    // with alerts on this phone off, the kinds are greyed out and can't be changed
                    if (!phoneOn) Text(t("Turn on Alerts on this phone to choose what it tells you about.", "Washa Arifa kwenye simu hii ili uchague inachokuarifu."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(bottom = 6.dp).testTag("nk_off_note"))
                    ALERT_KINDS.filter { it[0] != "review" || s?.admin == true }.forEach { k ->
                        HorizontalDivider(color = C.line)
                        Row(Modifier.padding(vertical = 10.dp).alpha(if (phoneOn) 1f else 0.42f), verticalAlignment = Alignment.CenterVertically) {
                            val hu = C.hue(k[4]); IconTile(k[3], hu.fg, hu.bg, 38.dp); Spacer(Modifier.width(12.dp))
                            Text(t(k[1], k[2]), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.weight(1f))
                            Toggle(phoneOn && k[0] !in off, t(k[1], k[2]), Modifier.testTag("nk_" + k[0]), enabled = phoneOn) { off = if (k[0] in off) off - k[0] else off + k[0]; m.prefs.noteOff = off; m.noteChanged() }
                        }
                    }
                }
                "help" -> {
                    FAQ.forEachIndexed { i, f ->
                        var open by remember { mutableStateOf(i == 0) }
                        Column(Modifier.padding(top = if (i == 0) 0.dp else 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, C.line, RoundedCornerShape(14.dp)).clickable { open = !open }.padding(14.dp).animateContentSize().testTag("faq")) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(t(f[0], f[1]), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.weight(1f))
                                Icon(Ic.get("down"), null, tint = C.muted, modifier = Modifier.size(18.dp).rotate(if (open) 180f else 0f))
                            }
                            if (open) Text(t(f[2], f[3]), style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    Btn(t("More questions on the website", "Maswali zaidi kwenye tovuti"), { web(ctx, "/faq") }, Modifier.fillMaxWidth().padding(top = 14.dp), light = true)
                    HorizontalDivider(color = C.line, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                    SetRow("spark", "purple", t("Show the welcome screens again", "Onyesha skrini za kukaribisha tena"), t("The short tour from the first day", "Utangulizi mfupi wa siku ya kwanza"), "replay") { m.replayOnb = true }
                }
                "look" -> LookPage(m)
                "monday" -> if (s?.linked.isNullOrBlank() && s?.who.isNullOrBlank()) Text(t("Your changes go in under the shared account. To use your own name, ask the office for your own PIN.", "Mabadiliko yako yanaingia kwa akaunti ya pamoja. Kutumia jina lako, omba ofisi PIN yako mwenyewe."), style = MaterialTheme.typography.bodyMedium, color = C.text)
                    else {
                        if (s?.linked?.isNotBlank() == true) Text(t("Connected as ${s?.linked} ✓", "Imeunganishwa kama ${s?.linked} ✓"), style = MaterialTheme.typography.titleSmall, color = C.ok, modifier = Modifier.padding(bottom = 6.dp).testTag("monday_connected"))
                        ConnectMonday(m, s, inline = true)
                    }
                "lang" -> {
                    Text(t("The whole app, and the reports you make, in the language you choose.", "Programu nzima, na ripoti unazotengeneza, kwa lugha unayochagua."), style = MaterialTheme.typography.bodyMedium, color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    val sw = org.rafikioutreach.pin.Lang.sw
                    listOf(Triple("en", "English", "Hello! Let’s find today’s water points."), Triple("sw", "Kiswahili", "Habari! Tutafute vituo vya maji vya leo.")).forEach { (k, n, hi) ->
                        SetChoice(n, hi, (k == "sw") == sw, "lang_$k") { org.rafikioutreach.pin.Lang.sw = k == "sw"; m.prefs.lang = k }
                    }
                }
                "units" -> {
                    var miles by remember { mutableStateOf(m.prefs.miles) }
                    Text(t("How far projects are from you, on lists, the map and routes.", "Umbali wa miradi kutoka ulipo, kwenye orodha, ramani na njia."), style = MaterialTheme.typography.bodyMedium, color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    SetChoice(t("Kilometres (km)", "Kilomita (km)"), t("e.g. 2.4 km away", "mf. km 2.4 kutoka ulipo"), !miles, "units_km") { miles = false; m.prefs.miles = false }
                    SetChoice(t("Miles", "Maili"), t("e.g. 1.5 miles away", "mf. maili 1.5 kutoka ulipo"), miles, "units_mi") { miles = true; m.prefs.miles = true }
                }
                "signout" -> {
                    Text(t("Changes waiting to send stay on this phone and go when you sign in again.", "Mabadiliko yanayosubiri yanabaki kwenye simu na kutumwa ukiingia tena."), style = MaterialTheme.typography.bodyMedium, color = C.text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Btn(t("Sign out", "Toka"), { AlertWorker.cancel(ctx.applicationContext); m.signOut() }, Modifier.fillMaxWidth().padding(top = 16.dp).testTag("so_go"))
                    Btn(t("Stay signed in", "Baki ndani"), back, Modifier.fillMaxWidth().padding(top = 8.dp), light = true)
                }
                else -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Wordmark(height = 34.dp)
                    Text(t("Version ", "Toleo ") + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")", style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.padding(top = 14.dp))
                    Text(t("The field app for RAFIKI Outreach: pin projects, report visits and repairs, and keep monday.com up to date, even with no signal.", "Programu ya uwanjani ya RAFIKI Outreach: weka mahali pa miradi, ripoti ziara na matengenezo, na usasishe monday.com, hata bila mtandao."), style = MaterialTheme.typography.bodyMedium, color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                    Text("rafikioutreach.org", style = MaterialTheme.typography.labelLarge, color = C.blue2, modifier = Modifier.padding(top = 12.dp).clickable { web(ctx, "") })
                    HorizontalDivider(color = C.line, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                    CheckUpdatesRow(m)
                }
            }
            msg?.let { (tone, text) -> Msg(text, tone) }
        }
    }
}

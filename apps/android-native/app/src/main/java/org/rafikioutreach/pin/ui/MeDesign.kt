package org.rafikioutreach.pin.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.Snapshot
import org.rafikioutreach.pin.t
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Me & team, to the Figma design "RAFIKI Pin — Me & team": a Me | Team switch, the profile card, and "This phone".

private val WORKS = setOf("pin", "visit", "fixed", "new")
private fun dayOf(iso: String): LocalDate? = runCatching { Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()

/** A small switch: grey track, the chosen one white (Me | Team, Light | Dark | Sun…). */
@Composable
fun SmallSeg(items: List<Pair<String, String>>, sel: String, modifier: Modifier = Modifier, tag: String = "", fill: Boolean = false, onPick: (String) -> Unit) {
    val h = rememberHaptics()
    val track = if (C.dark) Color(0xFF22303A) else Color(0xFFE8EDF1)
    Row(modifier.clip(RoundedCornerShape(99.dp)).background(track).padding(4.dp)) {
        items.forEach { (k, l) ->
            val on = k == sel
            Box(
                (if (fill || modifier != Modifier) Modifier.weight(1f) else Modifier)
                    .then(if (on) Modifier.shadow(2.dp, RoundedCornerShape(99.dp)) else Modifier)
                    .clip(RoundedCornerShape(99.dp)).background(if (on) C.card else Color.Transparent)
                    .clickable(role = Role.Tab) { h.tap(); onPick(k) }
                    .semantics { stateDescription = if (on) "selected" else "" }
                    .padding(horizontal = 12.dp, vertical = 9.dp).testTag(tag + k),
                contentAlignment = Alignment.Center,
            ) { Text(l, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = if (on) C.ink else C.muted, maxLines = 1) }
        }
    }
}

/** The deep blue profile card: who you are, this month, days in a row, Lock the app and Monthly report. */
@Composable
fun MeHero(m: AppModel, act: List<JSONObject>?, snap: Snapshot?, report: () -> Unit) {
    val mine = act?.filter { it.optString("by").trim().equals(m.prefs.name.trim(), true) && it.optString("a") in WORKS }
    val today = LocalDate.now(); val start = today.withDayOfMonth(1)
    val month = mine?.filter { dayOf(it.optString("at"))?.let { d -> !d.isBefore(start) } == true }
    val count = { k: String -> month?.count { it.optString("a") == k || (k == "pin" && it.optString("a") == "new") } }
    val streak = mine?.let { l ->
        val days = l.mapNotNull { dayOf(it.optString("at")) }.toSet()
        var d = if (today in days) today else today.minusDays(1); var n = 0
        while (d in days) { n++; d = d.minusDays(1) }; n
    }
    val ctry = mapOf("ke" to "Kenya", "tz" to "Tanzania", "ug" to "Uganda", "mw" to "Malawi")[m.prefs.country]
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier.padding(top = 12.dp).rise().fillMaxWidth().shadow(12.dp, shape, ambientColor = Color(0x47054F81), spotColor = Color(0x47054F81)).clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF0A78C2), Color(0xFF054F81), Color(0xFF04263F)))).padding(20.dp).testTag("me_hero"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(Color(0x29FFFFFF)).border(1.5.dp, Color(0x59FFFFFF), CircleShape), contentAlignment = Alignment.Center) {
                Text(m.prefs.name.split(" ").mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""), color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(m.prefs.name, color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold), maxLines = 1)
                Text((if (snap?.admin == true) t("Admin", "Msimamizi") else t("Field team", "Timu ya uwanjani")) + (ctry?.let { " · $it" } ?: ""), color = Color(0xFFCFE8FA), style = MaterialTheme.typography.labelLarge)
            }
        }
        Text(t("THIS MONTH", "MWEZI HUU"), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold), color = Color(0xFF9FD3F7), modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("pin" to t("Pins", "Mahali"), "visit" to t("Visits", "Ziara"), "fixed" to t("Fixed", "Zimerekebishwa")).forEach { (k, l) ->
                val n = count(k)
                val shown = n?.let { kotlin.math.round(rememberFill(it.toFloat(), 900)).toInt() }
                Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Color(0x1FFFFFFF)).padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(shown?.toString() ?: "–", color = Color.White, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold), modifier = Modifier.testTag("hero_$k"))
                    Text(l, color = Color(0xFFCFE8FA), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                }
            }
        }
        if (streak != null) Row(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x1AFFFFFF)).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.get("flame"), null, tint = Color(0xFFFFB27A), modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp))
            Text(when (streak) { 0 -> t("Pin or visit today to start a streak.", "Weka mahali au tembelea leo kuanza mfululizo."); 1 -> t("1 day in a row. Keep it going!", "Siku 1 mfululizo. Endelea hivyo!"); else -> t("$streak days in a row. Keep it going!", "Siku $streak mfululizo. Endelea hivyo!") },
                color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("my_streak"))
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeroBtn(t("Lock the app", "Funga programu"), "lock", true, Modifier.weight(1f)) { m.lockOrSignOut() }
            HeroBtn(t("Monthly report", "Ripoti ya mwezi"), "file", false, Modifier.weight(1f).testTag("me_report"), report)
        }
    }
}

@Composable
private fun HeroBtn(text: String, icon: String, solid: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val h = rememberHaptics(); val fg = if (solid) Color(0xFF04263F) else Color.White
    Row(
        modifier.heightIn(min = 46.dp).clip(RoundedCornerShape(99.dp)).then(if (solid) Modifier.background(Color.White) else Modifier.border(1.5.dp, Color(0x80FFFFFF), RoundedCornerShape(99.dp)))
            .clickable(role = Role.Button) { h.tap(); onClick() }.padding(horizontal = 6.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Ic.get(icon), null, tint = fg, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp), maxLines = 1, softWrap = false)
    }
}

/** One row of "This phone": a coloured icon, a name (and a line under it), and a control on the right. */
@Composable
private fun PhoneRow(icon: String, hue: String, title: String, sub: String? = null, subOk: Boolean = false, modifier: Modifier = Modifier, subTag: String = "", right: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        val hu = C.hue(hue); IconTile(icon, hu.fg, hu.bg, 40.dp); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = C.ink)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = if (subOk) C.ok else C.muted, modifier = if (subTag.isNotBlank()) Modifier.testTag(subTag) else Modifier)
        }
        Spacer(Modifier.width(8.dp)); right()
    }
}

/** An on/off switch drawn like the design: green when on. */
@Composable
fun Toggle(on: Boolean, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val x by animateDpAsState(if (on) 23.dp else 3.dp, label = "knob")
    Box(modifier.size(50.dp, 30.dp).clip(RoundedCornerShape(99.dp)).background(if (on) Green else Color(0xFFC9D1D8))
        .clickable(role = Role.Switch, onClick = onClick).semantics { contentDescription = label; stateDescription = if (on) "on" else "off" }) {
        Box(Modifier.offset(x = x, y = 3.dp).size(24.dp).shadow(1.dp, CircleShape).clip(CircleShape).background(Color.White))
    }
}

/** This phone: Face ID / fingerprint, your name on monday.com, colours, language, distances. */
@Composable
fun ThisPhone(m: AppModel, snap: Snapshot?) {
    val ctx = LocalContext.current
    Card(Modifier.padding(top = 14.dp).testTag("this_phone")) {
        H2(t("This phone", "Simu hii"))
        var first = true
        @Composable fun line() { if (!first) HorizontalDivider(color = C.line); first = false }
        // fingerprint or face
        val can = remember { Bio.available(ctx) }
        var bio by remember { mutableStateOf(m.prefs.bio) }
        var bioMsg by remember { mutableStateOf<String?>(null) }
        if (can || bio) {
            line()
            PhoneRow("finger", "due", t("Unlock with fingerprint or face", "Fungua kwa alama ya kidole au uso"),
                bioMsg ?: if (bio) t("Asked when the app opens and after a minute away.", "Inaulizwa programu inapofunguliwa na baada ya dakika moja nje.") else t("Keeps the app private if someone else picks up your phone.", "Inaweka programu faragha mtu mwingine akishika simu yako."),
                modifier = Modifier.testTag("bio_card")) {
                Toggle(bio, if (bio) t("Turn off", "Zima") else t("Turn on", "Washa"), Modifier.testTag("bio_switch")) {
                    if (bio) { bio = false; m.prefs.bio = false; bioMsg = null }
                    else Bio.ask(ctx, t("Turn on fingerprint unlock", "Washa kufungua kwa alama ya kidole")) { ok -> if (ok) { bio = true; m.prefs.bio = true; bioMsg = null } else bioMsg = t("Not turned on.", "Haijawashwa.") }
                }
            }
        }
        // your name on monday.com (not when signed in with monday.com itself)
        if (snap != null && m.prefs.session.isBlank()) {
            line()
            var open by remember { mutableStateOf(snap.linked.isBlank() && snap.who.isNotBlank()) }
            val turn by animateFloatAsState(if (open) 90f else 0f, label = "chev")
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button) { open = !open }.testTag("monday_row")) {
                PhoneRow("link", "new", t("Your name on monday.com", "Jina lako kwenye monday.com"),
                    when { snap.linked.isNotBlank() -> t("Connected as ${snap.linked} ✓", "Imeunganishwa kama ${snap.linked} ✓"); snap.who.isBlank() -> t("Changes go in under the shared account", "Mabadiliko yanaingia kwa akaunti ya pamoja"); else -> t("Takes 20 seconds, once", "Inachukua sekunde 20, mara moja") },
                    subOk = snap.linked.isNotBlank(), subTag = if (snap.linked.isNotBlank()) "monday_connected" else "") {
                    Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(18.dp).rotate(turn))
                }
            }
            if (open) Column(Modifier.padding(start = 52.dp, bottom = 12.dp)) { ConnectMonday(m, snap, inline = true) }
        }
        // colours
        line()
        val sysDark = androidx.compose.foundation.isSystemInDarkTheme()
        PhoneRow("sun", "repairs", t("Colours", "Rangi")) {
            SmallSeg(listOf("light" to t("Light", "Mwanga"), "dark" to t("Dark", "Giza"), "sun" to t("Sun", "Jua")), Look.mode.ifBlank { if (sysDark) "dark" else "light" }, tag = "theme_") { Look.mode = it; m.prefs.theme = it }
        }
        // language
        line()
        var sw by remember { mutableStateOf(org.rafikioutreach.pin.Lang.sw) }
        PhoneRow("globe", "pin", t("Language", "Lugha")) {
            SmallSeg(listOf("en" to "English", "sw" to "Kiswahili"), if (sw) "sw" else "en", tag = "lang_") { org.rafikioutreach.pin.Lang.sw = it == "sw"; m.prefs.lang = it; sw = it == "sw" }
        }
        // distances
        line()
        var miles by remember { mutableStateOf(m.prefs.miles) }
        PhoneRow("nav", "near", t("Distances", "Umbali")) {
            SmallSeg(listOf("km" to "km", "mi" to t("miles", "maili")), if (miles) "mi" else "km", tag = "units_") { miles = it == "mi"; m.prefs.miles = miles }
        }
    }
}

/** Me › App update: is there a newer RAFIKI Pin on the website? One tap downloads it; Android then offers to install it over this one. */
@Composable
fun AppUpdate(m: AppModel) {
    val ctx = LocalContext.current
    val mine = org.rafikioutreach.pin.BuildConfig.VERSION_CODE
    var latest by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var checked by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberScope()
    fun check() {
        busy = true
        scope.launch {
            val r = m.api.appVersion()
            if (r.ok) latest = r.json.optInt("versionCode") to r.json.optString("versionName")
            checked = true; busy = false
        }
    }
    LaunchedEffect(Unit) { check() }
    val newer = latest?.let { it.first > mine } == true
    fun download() = runCatching {
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(org.rafikioutreach.pin.BuildConfig.SERVER + "/app/rafiki-pin.apk")))
    }
    Card(Modifier.padding(top = 14.dp).testTag("app_update"), border = if (newer) C.blue else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val hu = C.hue(if (newer) "pin" else "near"); IconTile(if (newer) "upload" else "check", hu.fg, hu.bg, 40.dp); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (newer) t("Update to RAFIKI Pin ${latest!!.second}", "Sasisha hadi RAFIKI Pin ${latest!!.second}") else t("RAFIKI Pin ${org.rafikioutreach.pin.BuildConfig.VERSION_NAME}", "RAFIKI Pin ${org.rafikioutreach.pin.BuildConfig.VERSION_NAME}"),
                    style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.testTag("update_title"))
                Text(when {
                    newer -> t("A newer app is ready. You have ${org.rafikioutreach.pin.BuildConfig.VERSION_NAME}. Your PIN and saved work stay.", "Programu mpya iko tayari. Unayo ${org.rafikioutreach.pin.BuildConfig.VERSION_NAME}. PIN yako na kazi iliyohifadhiwa vinabaki.")
                    busy -> t("Checking for a newer app…", "Inatafuta programu mpya…")
                    checked && latest != null -> t("You have the latest app ✓", "Una programu ya karibuni ✓")
                    else -> t("Couldn’t check just now. Needs a signal.", "Haikuweza kuangalia sasa. Inahitaji mtandao.")
                }, style = MaterialTheme.typography.bodySmall, color = if (newer) C.muted else if (checked && latest != null) C.ok else C.muted)
            }
        }
        if (newer) {
            Btn(t("Update the app", "Sasisha programu"), { download() }, Modifier.padding(top = 12.dp).fillMaxWidth().testTag("update_go"), icon = "upload")
            Text(t("It downloads, then tap the file and Install (Update).", "Itapakuliwa, kisha gusa faili na Install (Update)."), style = MaterialTheme.typography.labelSmall, color = C.muted, modifier = Modifier.padding(top = 6.dp).align(Alignment.CenterHorizontally))
        } else Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(t("Check again", "Angalia tena"), style = MaterialTheme.typography.labelLarge, color = C.blue, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = !busy) { check() }.padding(6.dp).testTag("update_check"))
            Text(t("Download the app again", "Pakua programu tena"), style = MaterialTheme.typography.labelLarge, color = C.blue, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { download() }.padding(6.dp))
        }
    }
}

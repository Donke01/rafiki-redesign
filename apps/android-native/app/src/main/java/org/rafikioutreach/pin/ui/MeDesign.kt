package org.rafikioutreach.pin.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.relocation.bringIntoViewRequester
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

// Me & team, to the Figma design "RAFIKI Pin — Me & team": a Me | Team switch and the profile card (the phone's own settings are in Me › Settings › This phone).

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
                    if (shown == null) Skel(Modifier.padding(vertical = 5.dp).size(34.dp, 22.dp), dark = true)
                    else Text(shown.toString(), color = Color.White, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold), modifier = Modifier.testTag("hero_$k"))
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
            HeroBtn(t("Lock the app", "Funga kwa PIN"), "lock", true, Modifier.weight(1f)) { m.lockOrSignOut() }
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

/** An on/off switch drawn like the design: green when on. */
@Composable
fun Toggle(on: Boolean, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val x by animateDpAsState(if (on) 23.dp else 3.dp, label = "knob")
    Box(modifier.size(50.dp, 30.dp).clip(RoundedCornerShape(99.dp)).background(if (on) Green else Color(0xFFC9D1D8))
        .clickable(enabled = enabled, role = Role.Switch, onClick = onClick).semantics { contentDescription = label; stateDescription = if (on) "on" else "off" }) {
        Box(Modifier.offset(x = x, y = 3.dp).size(24.dp).shadow(1.dp, CircleShape).clip(CircleShape).background(Color.White))
    }
}

private fun openApk(ctx: android.content.Context) = runCatching {
    ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(org.rafikioutreach.pin.BuildConfig.SERVER + "/app/rafiki-pin.apk")))
}

/** "Checked just now", "Checked 5 min ago", "Checked yesterday"… */
private fun checkedText(at: Long): String {
    if (at <= 0) return t("Not checked yet", "Bado haijaangaliwa")
    val min = (System.currentTimeMillis() - at) / 60000
    return when {
        min < 2 -> t("Checked just now", "Imeangaliwa sasa hivi")
        min < 60 -> t("Checked $min min ago", "Imeangaliwa dakika $min zilizopita")
        min < 1440 -> t("Checked ${min / 60} h ago", "Imeangaliwa saa ${min / 60} zilizopita")
        min < 2880 -> t("Checked yesterday", "Imeangaliwa jana")
        else -> t("Checked ${min / 1440} days ago", "Imeangaliwa siku ${min / 1440} zilizopita")
    }
}

/** Me, near the top, only when a newer app is on the website: what's new, then download and install right here. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun UpdateCard(m: AppModel) {
    val u by m.update.collectAsState()
    val st by org.rafikioutreach.pin.data.Updater.state.collectAsState()
    val up = u ?: return
    if (up.code <= org.rafikioutreach.pin.BuildConfig.VERSION_CODE) return
    val ctx = LocalContext.current
    val scope = rememberScope()
    val U = org.rafikioutreach.pin.data.Updater
    fun start() { scope.launch { U.run(ctx.applicationContext, org.rafikioutreach.pin.BuildConfig.SERVER + "/app/rafiki-pin.apk", up.sha256) } }
    // brought into view when "Check" finds an update or the pop-up's "Update now" is tapped
    val seen = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    var glow by remember { mutableStateOf(false) }
    LaunchedEffect(m.focusUpdate, m.updateGo) {
        if (m.updateGo) { m.updateGo = false; if (st is org.rafikioutreach.pin.data.Updater.State.Idle || st is org.rafikioutreach.pin.data.Updater.State.Failed) start() }
        if (m.focusUpdate) { kotlinx.coroutines.delay(250); seen.bringIntoView(); m.focusUpdate = false; glow = true; kotlinx.coroutines.delay(1400); glow = false }
    }
    val ring by androidx.compose.animation.animateColorAsState(if (glow) Color(0xFF47B4F5) else Color.Transparent, androidx.compose.animation.core.tween(if (Motion.on) 450 else 0), label = "updGlow")
    // back from Android's "Install unknown apps" setting: carry on by itself
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && U.state.value is org.rafikioutreach.pin.data.Updater.State.NeedsPermission && U.allowed(ctx)) U.install(ctx.applicationContext) }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    Card(Modifier.padding(top = 14.dp).bringIntoViewRequester(seen).border(3.dp, ring, RoundedCornerShape(24.dp)).testTag("app_update"), border = C.blue) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val hu = C.hue("pin"); IconTile("upload", hu.fg, hu.bg, 40.dp); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t("Update available · ${up.name}", "Toleo jipya · ${up.name}"), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.testTag("update_title"))
                Text(up.notes.ifBlank { t("A newer RAFIKI Pin is ready.", "RAFIKI Pin mpya iko tayari.") } + t(" You have ${org.rafikioutreach.pin.BuildConfig.VERSION_NAME}.", " Unayo ${org.rafikioutreach.pin.BuildConfig.VERSION_NAME}."),
                    style = MaterialTheme.typography.bodySmall, color = C.muted)
            }
        }
        when (val s = st) {
            is org.rafikioutreach.pin.data.Updater.State.Downloading -> {
                val f = s.f
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t("Downloading…", "Inapakua…"), style = MaterialTheme.typography.labelLarge, color = C.ink, modifier = Modifier.weight(1f))
                    Text(if (s.total > 0) "${(f * 100).toInt()}% · ${"%.1f".format(s.done / 1048576.0)} of ${"%.1f".format(s.total / 1048576.0)} MB" else "${"%.1f".format(s.done / 1048576.0)} MB", style = MaterialTheme.typography.labelMedium, color = C.muted, modifier = Modifier.testTag("update_progress"))
                }
                Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(10.dp).clip(RoundedCornerShape(99.dp)).background(C.tint)) {
                    Box(Modifier.fillMaxWidth(f).height(10.dp).clip(RoundedCornerShape(99.dp)).background(Brush.horizontalGradient(listOf(Color(0xFF0A78C2), Color(0xFF47B4F5)))))
                }
                Text(t("Cancel", "Ghairi"), style = MaterialTheme.typography.labelLarge, color = C.blue, modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).clickable { U.cancel() }.padding(6.dp))
            }
            org.rafikioutreach.pin.data.Updater.State.NeedsPermission -> {
                Text(t("One more step: Android asks you to allow RAFIKI Pin to install updates. Turn on “Allow from this source”, then come back. The update carries on by itself.", "Hatua moja zaidi: Android inakuomba uruhusu RAFIKI Pin kusakinisha masasisho. Washa “Allow from this source”, kisha urudi. Sasisho litaendelea lenyewe."),
                    style = MaterialTheme.typography.bodySmall, color = C.text, modifier = Modifier.padding(top = 12.dp))
                Btn(t("Allow updates", "Ruhusu masasisho"), { runCatching { ctx.startActivity(U.permissionIntent(ctx)) } }, Modifier.padding(top = 10.dp).fillMaxWidth().testTag("update_allow"), icon = "lock")
            }
            org.rafikioutreach.pin.data.Updater.State.Installing -> Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = C.blue); Spacer(Modifier.width(10.dp))
                Text(t("Installing… tap Update when Android asks.", "Inasakinisha… gusa Update Android ikiuliza."), style = MaterialTheme.typography.labelLarge, color = C.ink)
            }
            is org.rafikioutreach.pin.data.Updater.State.Failed -> {
                Text(when (s.why) {
                    "offline", "http", "short" -> t("The download didn’t finish. Check your signal and try again.", "Upakuaji haukukamilika. Angalia mtandao kisha ujaribu tena.")
                    "check" -> t("The download didn’t match the app on the website, so it wasn’t installed. Try again.", "Kilichopakuliwa hakilingani na programu kwenye tovuti, kwa hiyo hakikusakinishwa. Jaribu tena.")
                    "aborted" -> t("The update was cancelled.", "Sasisho limeghairiwa.")
                    else -> t("Android couldn’t install the update.", "Android haikuweza kusakinisha sasisho.")
                }, style = MaterialTheme.typography.bodySmall, color = C.bad, modifier = Modifier.padding(top = 12.dp).testTag("update_error"))
                if (s.why == "aborted" && U.samsung) {
                    val hu = C.hue("due")
                    Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(hu.bg).padding(12.dp).testTag("auto_blocker")) {
                        Text(t("Tapped Update? Samsung’s Auto Blocker stopped it.", "Uligusa Sasisha? Auto Blocker ya Samsung imelizuia."), style = MaterialTheme.typography.labelLarge, color = hu.fg)
                        Text(t("Turn Auto Blocker off for a moment (Security and privacy › Auto Blocker), come back and tap Try again. It turns itself back on later.",
                               "Zima Auto Blocker kwa muda (Security and privacy › Auto Blocker), rudi kisha uguse Jaribu tena. Itajiwasha tena baadaye."),
                            style = MaterialTheme.typography.bodySmall, color = C.ink, modifier = Modifier.padding(top = 4.dp))
                        Btn(t("Open Security settings", "Fungua mipangilio ya usalama"), { runCatching { ctx.startActivity(U.securityIntent()) } }, Modifier.padding(top = 10.dp).fillMaxWidth().testTag("auto_blocker_open"), icon = "lock", light = true, small = true)
                    }
                }
                Btn(t("Try again", "Jaribu tena"), { if (s.why == "aborted" && U.file(ctx).exists()) U.install(ctx.applicationContext) else start() }, Modifier.padding(top = 10.dp).fillMaxWidth(), icon = "refresh")
                Text(t("Having trouble? Download the app from the website", "Una tatizo? Pakua programu kutoka kwenye tovuti"), style = MaterialTheme.typography.labelMedium, color = C.blue,
                    modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).clickable { openApk(ctx) }.padding(vertical = 6.dp))
            }
            else -> {
                Btn(t("Update now", "Sasisha sasa"), { start() }, Modifier.padding(top = 12.dp).fillMaxWidth().testTag("update_go"), icon = "upload")
                Text(t("Downloads here in the app, then Android asks once to update. Your PIN and saved work stay.", "Inapakuliwa hapa kwenye programu, kisha Android inauliza mara moja kusasisha. PIN yako na kazi iliyohifadhiwa vinabaki."),
                    style = MaterialTheme.typography.labelSmall, color = C.muted, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** Pops up over the app when a newer version is ready: Update now, Remind me later (tomorrow), or ✕ (until the app is opened again). */
@Composable
fun UpdatePrompt(m: AppModel, ready: Boolean) {
    val u by m.update.collectAsState()
    val st by org.rafikioutreach.pin.data.Updater.state.collectAsState()
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(ready, u?.code) {
        show = false
        val code = u?.code ?: 0
        val closed = m.updPromptClosed && m.updPromptClosedCode >= code
        val later = System.currentTimeMillis() < m.prefs.updLater && m.prefs.updLaterCode >= code   // "Remind me later" only holds back the same version
        if (ready && !closed && !later && st is org.rafikioutreach.pin.data.Updater.State.Idle) { kotlinx.coroutines.delay(1200); show = true }
    }
    val up = u ?: return
    if (!show) return
    fun close() { show = false; m.updPromptClosed = true; m.updPromptClosedCode = up.code }
    androidx.compose.ui.window.Dialog(onDismissRequest = { close() }) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(C.card).testTag("update_prompt")) {
            Box(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF0A78C2), Color(0xFF054F81), Color(0xFF04263F)))).padding(20.dp)) {
                Column {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) { Icon(Ic.get("upload"), null, tint = Color.White, modifier = Modifier.size(26.dp)) }
                    Text(t("A new RAFIKI Pin is ready", "RAFIKI Pin mpya iko tayari"), style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.padding(top = 14.dp))
                    Text(t("Version ${up.name} · you have ${org.rafikioutreach.pin.BuildConfig.VERSION_NAME}", "Toleo ${up.name} · unayo ${org.rafikioutreach.pin.BuildConfig.VERSION_NAME}"), style = MaterialTheme.typography.labelLarge, color = Color(0xFFCFE8FA), modifier = Modifier.padding(top = 4.dp))
                }
                Box(Modifier.align(Alignment.TopEnd).size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)).clickable(role = Role.Button) { close() }
                    .semantics { contentDescription = t("Close", "Funga") }.testTag("update_prompt_close"), contentAlignment = Alignment.Center) { Icon(Ic.get("x"), null, tint = Color.White, modifier = Modifier.size(18.dp)) }
            }
            Column(Modifier.padding(20.dp)) {
                Text(t("What’s new", "Kipya"), style = MaterialTheme.typography.labelLarge, color = C.muted)
                Text(up.notes.ifBlank { t("Fixes and improvements.", "Marekebisho na maboresho.") }, style = MaterialTheme.typography.bodyMedium, color = C.ink, modifier = Modifier.padding(top = 4.dp))
                Text(t("Downloads right here in the app. Your PIN and saved work stay.", "Inapakuliwa hapa hapa kwenye programu. PIN yako na kazi iliyohifadhiwa vinabaki."), style = MaterialTheme.typography.labelSmall, color = C.muted, modifier = Modifier.padding(top = 10.dp))
                Btn(t("Update now", "Sasisha sasa"), { show = false; m.updPromptClosed = true; m.updateGo = true; m.focusUpdate = true; m.goTab = "me" }, Modifier.padding(top = 16.dp).fillMaxWidth().testTag("update_prompt_go"), icon = "upload")
                Btn(t("Remind me later", "Nikumbushe baadaye"), { show = false; m.updPromptClosed = true; m.updPromptClosedCode = up.code; m.prefs.updLater = System.currentTimeMillis() + 24 * 3600_000L; m.prefs.updLaterCode = up.code }, Modifier.padding(top = 10.dp).fillMaxWidth().testTag("update_prompt_later"), light = true)
            }
        }
    }
}

/** Me, at the bottom: App updates · up to date · when it was checked · Check for updates. */
@Composable
fun UpdateRow(m: AppModel) {
    val u by m.update.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    val scope = rememberScope(); val ctx = LocalContext.current
    val ready = (u?.code ?: 0) > org.rafikioutreach.pin.BuildConfig.VERSION_CODE
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30000); tick++ } } // keeps "Checked … ago" fresh
    Card(Modifier.padding(top = 14.dp).testTag("update_row"), pad = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val hu = C.hue(if (ready) "pin" else "near"); IconTile(if (ready) "upload" else "check", hu.fg, hu.bg, 36.dp); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t("App updates", "Masasisho ya programu"), style = MaterialTheme.typography.titleSmall, color = C.ink)
                Text(when {
                    busy -> t("Checking…", "Inaangalia…")
                    failed -> t("Couldn’t check. Needs a signal.", "Haikuweza kuangalia. Inahitaji mtandao.")
                    ready -> t("Update available · ${u!!.name}", "Toleo jipya · ${u!!.name}")
                    else -> t("You’re up to date · ${org.rafikioutreach.pin.BuildConfig.VERSION_NAME} ✓", "Uko na toleo jipya · ${org.rafikioutreach.pin.BuildConfig.VERSION_NAME} ✓")
                }, style = MaterialTheme.typography.bodySmall, color = if (failed) C.bad else if (ready) C.blue else C.ok, modifier = Modifier.testTag("update_status"))
                key(tick) { Text(checkedText(u?.at ?: 0L), style = MaterialTheme.typography.labelSmall, color = C.muted) }
            }
            Spacer(Modifier.width(8.dp))
            Row(Modifier.clip(RoundedCornerShape(99.dp)).background(C.tint).clickable(enabled = !busy, role = Role.Button) {
                busy = true; failed = false; scope.launch { failed = !m.checkUpdate(); busy = false; if (m.updateReady) m.focusUpdate = true }
            }.padding(horizontal = 12.dp, vertical = 9.dp).testTag("update_check"), verticalAlignment = Alignment.CenterVertically) {
                if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = C.blue)
                else Icon(Ic.get("refresh"), null, tint = C.blue, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp)); Text(t("Check", "Angalia"), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = C.blue)
            }
        }
        if (failed) Text(t("Having trouble? Download the app from the website", "Una tatizo? Pakua programu kutoka kwenye tovuti"), style = MaterialTheme.typography.labelMedium, color = C.blue,
            modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).clickable { openApk(ctx) }.padding(vertical = 6.dp).testTag("update_web"))
    }
}

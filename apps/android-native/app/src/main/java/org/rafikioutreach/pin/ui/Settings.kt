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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

/** The bell beside "Me & team": a red count of what's new; tap for the list. */
@Composable
fun Bell(m: AppModel, open: (String) -> Unit) {
    val tick by m.noteTick.collectAsState()
    val act by m.activity.collectAsState()
    val s by m.snap.collectAsState()
    val n = remember(tick, act, s) { m.unread() }
    var sheet by remember { mutableStateOf(false) }
    val h = rememberHaptics()
    Box(Modifier.size(46.dp)) {
        Box(Modifier.size(44.dp).align(Alignment.Center).clip(CircleShape).background(C.card).border(1.dp, C.line, CircleShape)
            .clickable(role = Role.Button) { h.tap(); sheet = true }.semantics { contentDescription = t("Notifications", "Arifa") + if (n > 0) " · $n" else "" }.testTag("bell"),
            contentAlignment = Alignment.Center) { Icon(Ic.get("bell"), null, tint = C.ink, modifier = Modifier.size(21.dp)) }
        if (n > 0) Box(Modifier.align(Alignment.TopEnd).defaultMinSize(minWidth = 20.dp, minHeight = 20.dp).clip(RoundedCornerShape(99.dp)).background(Color.White).padding(2.dp)
            .clip(RoundedCornerShape(99.dp)).background(C.bad).padding(horizontal = 5.dp).testTag("bell_count"), contentAlignment = Alignment.Center) {
            Text(if (n > 9) "9+" else "$n", color = Color.White, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp))
        }
    }
    if (sheet) AlertsSheet(m, { sheet = false }, open)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AlertsSheet(m: AppModel, close: () -> Unit, open: (String) -> Unit) {
    val list = remember { m.alerts() }
    val seen = remember { m.prefs.noteSeen }
    LaunchedEffect(Unit) { m.markRead() }
    val kinds = ALERT_KINDS.associateBy { it[0] }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = close, containerColor = C.card, modifier = Modifier.testTag("alerts_sheet")) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(t("Notifications", "Arifa"), style = MaterialTheme.typography.titleLarge, color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
            if (list.isEmpty()) Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                IconTile("okc", C.ok, C.okBg, 56.dp)
                Text(t("You’re all caught up.", "Hakuna jipya kwa sasa."), style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.padding(top = 10.dp))
            }
            list.forEach { a ->
                val k = kinds[a.kind] ?: ALERT_KINDS[0]; val hu = C.hue(k[4]); val new = a.at > seen
                Row(Modifier.padding(bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (new) C.tint else C.card).border(1.dp, if (new) Color.Transparent else C.line, RoundedCornerShape(16.dp))
                    .clickable { close(); if (a.id != null) open(a.id) else if (a.kind == "review") m.goTab = "office" }.padding(12.dp).testTag("alert_row"), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(k[3], hu.fg, hu.bg, 38.dp); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.text, style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp), color = C.ink)
                        Text(whenText(a.at), style = MaterialTheme.typography.labelSmall, color = C.muted)
                    }
                    if (new) Box(Modifier.padding(start = 8.dp).size(9.dp).clip(CircleShape).background(C.blue))
                }
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
        Group(t("Account", "Akaunti"))
        SetRow("person", "blue", t("Personal details", "Taarifa zangu"), listOf(me.nick.ifBlank { m.prefs.name }, me.phone).filter { it.isNotBlank() }.joinToString(" · "), "set_profile") { go("profile") }
        SetRow("lock", "purple", if (me.kind == "monday") t("Sign-in", "Kuingia") else t("Change my PIN", "Badilisha PIN yangu"), when (me.kind) {
            "monday" -> t("Signed in with monday.com", "Umeingia kwa monday.com"); "app" -> t("Your own PIN", "PIN yako mwenyewe")
            "cloudflare" -> t("Set by the office", "Imewekwa na ofisi"); else -> t("Shared team PIN", "PIN ya timu") }, "set_pin") { go("pin") }
        SetRow("bell", "amber", t("Notifications", "Arifa"), if (m.prefs.phoneAlerts && AlertWorker.allowed(ctx)) t("On this phone too", "Pia kwenye simu hii") else t("In the app only", "Ndani ya programu tu"), "set_notify") { go("notify") }
        Group(t("Help", "Msaada"))
        SetRow("search", "teal", t("Help and FAQs", "Msaada na maswali"), t("Pinning, visits, no signal, updates", "Kuweka mahali, ziara, bila mtandao, masasisho"), "set_help") { go("help") }
        SetRow("chat", "green", t("Contact the office", "Wasiliana na ofisi"), "info@rafikioutreach.org", "set_contact") { mail(ctx, "RAFIKI Pin: " + m.prefs.name, "") }
        SetRow("alert", "coral", t("Report a problem with the app", "Ripoti tatizo la programu"), t("Sends the app version with your message", "Inatuma toleo la programu pamoja na ujumbe wako"), "set_problem") {
            mail(ctx, "RAFIKI Pin problem", "\n\nWhat happened:\n\n\n---\n${m.prefs.name} · Android app ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
        }
        Group(t("About", "Kuhusu"))
        SetRow("lock", "slate", t("Privacy policy", "Sera ya faragha"), "", "set_privacy") { web(ctx, "/privacy-policy") }
        SetRow("clip", "slate", t("Terms of use", "Masharti ya matumizi"), "", "set_terms") { web(ctx, "/terms-of-use") }
        SetRow("drop", "blue", t("About RAFIKI Pin", "Kuhusu RAFIKI Pin"), t("Version ", "Toleo ") + BuildConfig.VERSION_NAME, "set_about") { go("about") }
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
private fun Group(label: String) = Text(label.uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp), color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 2.dp))

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

private val FAQ = listOf(
    listOf("How do I pin a project’s location?", "Ninawekaje mahali pa mradi?", "Stand at the site, open the project, then Pin location › Use my location. Check the point on the map and save. You can also tap the map, or paste a Google Maps link.", "Simama kwenye eneo, fungua mradi, kisha Weka mahali › Tumia mahali nilipo. Hakikisha kwenye ramani kisha hifadhi. Unaweza pia kugusa ramani, au kubandika kiungo cha Google Maps."),
    listOf("How do I report a site visit?", "Ninaripotije ziara?", "Open the project › Site visit. Tap “I’ve arrived”, choose how it is, go through the checklist, add photos, then Send. Each kind of project has its own checklist.", "Fungua mradi › Ziara. Gusa “Nimefika”, chagua hali, pitia orodha ya ukaguzi, ongeza picha, kisha Tuma. Kila aina ya mradi ina orodha yake."),
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
        val title = when (which) { "profile" -> t("Personal details", "Taarifa zangu"); "pin" -> if (me.kind == "monday") t("Sign-in", "Kuingia") else t("Change my PIN", "Badilisha PIN yangu"); "notify" -> t("Notifications", "Arifa"); "help" -> t("Help and FAQs", "Msaada na maswali"); "signout" -> t("Sign out of this phone?", "Toka kwenye simu hii?"); else -> t("About RAFIKI Pin", "Kuhusu RAFIKI Pin") }
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
                    Text(t("TELL ME ABOUT", "NIARIFU KUHUSU"), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp), color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp))
                    ALERT_KINDS.filter { it[0] != "review" || s?.admin == true }.forEach { k ->
                        HorizontalDivider(color = C.line)
                        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            val hu = C.hue(k[4]); IconTile(k[3], hu.fg, hu.bg, 38.dp); Spacer(Modifier.width(12.dp))
                            Text(t(k[1], k[2]), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.weight(1f))
                            Toggle(k[0] !in off, t(k[1], k[2]), Modifier.testTag("nk_" + k[0])) { off = if (k[0] in off) off - k[0] else off + k[0]; m.prefs.noteOff = off; m.noteChanged() }
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
                }
            }
            msg?.let { (tone, text) -> Msg(text, tone) }
        }
    }
}

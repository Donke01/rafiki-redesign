package org.rafikioutreach.pin.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.DProject
import org.rafikioutreach.pin.data.DUpdate
import org.rafikioutreach.pin.data.DonorView
import org.rafikioutreach.pin.data.donorStage
import org.rafikioutreach.pin.data.donorVisitSay
import org.rafikioutreach.pin.t

// RAFIKI Pin in donor mode (feat-donorapp): a calm, donor-only app. None of the staff tabs (Map, Projects, New site,
// Me, Office) or the team's data are here: only the donor's own projects from /api/donor (see data/Donor.kt).

const val DONOR_CONTACT = "info@rafikioutreach.org"
const val DONOR_GIVE = "https://www.rafikioutreach.org/donate"

private fun num(n: Number) = String.format(java.util.Locale.US, "%,d", n.toLong())
private fun open(ctx: android.content.Context, url: String) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
private fun mail(ctx: android.content.Context, subject: String) = open(ctx, "mailto:$DONOR_CONTACT?subject=" + Uri.encode(subject))

/** Words for a project's state, as a donor reads them. */
fun donorStateName(k: String) = when (k) {
    "working" -> t("Working well", "Inafanya kazi vizuri"); "repairing" -> t("Being repaired", "Inarekebishwa"); "notworking" -> t("Waiting for a repair", "Inasubiri matengenezo")
    "building" -> t("Being built", "Inajengwa"); "planned" -> t("Planned", "Imepangwa"); else -> t("Completed", "Imekamilika")
}
fun donorStateSay(p: DProject): String {
    val at = if (p.healthAt.isNotBlank()) Dates.weekday(p.healthAt) else ""
    return when (p.state) {
        "working" -> t("Water is flowing and people are using it. Last checked $at.", "Maji yanatoka na watu wanayatumia. Imekaguliwa mwisho $at.")
        "repairing" -> t("Our team found a problem and is fixing it now.", "Timu yetu iligundua tatizo na inalirekebisha sasa.")
        "notworking" -> t("It stopped working at the last check ($at). Our team knows and is arranging the repair.", "Iliacha kufanya kazi katika ukaguzi wa mwisho ($at). Timu yetu inajua na inapanga matengenezo.")
        "building" -> p.visits.firstOrNull { it.stage.isNotBlank() }?.let { t("Building is under way: ", "Ujenzi unaendelea: ") + donorStage(it.stage).lowercase() + "." } ?: t("Building is under way.", "Ujenzi unaendelea.")
        "planned" -> t("This project is planned. We’ll share news as soon as work starts.", "Mradi huu umepangwa. Tutakujulisha kazi ikianza.")
        else -> if (p.done.isNotBlank()) t("Finished ${Dates.day(p.done)}.", "Ilikamilika ${Dates.day(p.done)}.") else if (p.year > 0) t("Finished in ${p.year}.", "Ilikamilika ${p.year}.") else t("Finished.", "Imekamilika.")
    }
}
@Composable
private fun stateTone(k: String): Pair<Color, Color> = when (k) {
    "working" -> C.ok to C.okBg; "repairing" -> C.warn to C.warnBg; "notworking" -> C.bad to C.badBg; "building" -> C.hue("purple").fg to C.hue("purple").bg; else -> C.blue2 to C.tint
}
private fun kindOf(p: DProject) = when (p.type) {
    "Borehole well" -> t("Borehole well", "Kisima kirefu"); "Water collection" -> t("Rainwater tank", "Tangi la maji ya mvua"); "School" -> t("School", "Shule"); "Health" -> t("Health centre", "Kituo cha afya")
    "Orphanage" -> t("Children’s home", "Makao ya watoto"); "Farming" -> t("Farming", "Kilimo"); else -> p.type
}
private fun iconOf(p: DProject) = when { p.water -> "drop"; p.cat == "school" -> "school"; p.cat == "children" -> "house"; p.cat == "health" -> "cross"; else -> "spark" }
private fun cover(m: AppModel, p: DProject): String? = p.photos.firstOrNull()?.let { m.donor.api.board(it) } ?: p.gallery.firstOrNull()?.let { m.donor.api.photo(it.id) }

@Composable
private fun DIcon(name: String, tint: Color, size: androidx.compose.ui.unit.Dp = 20.dp) =
    Icon(if (name == "heart" || name == "edit") DqmIc.get(name) else Ic.get(name), null, tint = tint, modifier = Modifier.size(size))

// ---- getting in ----------------------------------------------------------------------------------------------
/** "I'm a donor": paste the private link or type the short code. */
@Composable
fun DonorSignIn(m: AppModel, onStaff: () -> Unit) {
    val scope = rememberScope()
    val ctx = LocalContext.current
    var typed by rememberSaveable { mutableStateOf("") }
    var msg by remember { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var askStaff by remember { mutableStateOf(false) }
    fun go() {
        if (busy) return
        if (m.signedIn.value) { askStaff = true; return }
        busy = true; msg = "" to t("Checking…", "Inakagua…")
        scope.launch { val err = m.donor.signIn(typed); busy = false; msg = err?.let { "bad" to it } }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp).testTag("donor_signin"), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Drop(84.dp, DropC.Blue, Mood.Smile)
            Wordmark(Modifier.padding(top = 14.dp), height = 26.dp)
            Text(t("I’m a donor", "Mimi ni mfadhili"), style = MaterialTheme.typography.headlineMedium, color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
            Text(t("See the projects you funded: photos, visits and thank-you messages from the communities.", "Ona miradi uliyofadhili: picha, ziara na ujumbe wa shukrani kutoka kwa jamii."), style = MaterialTheme.typography.bodyMedium, color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            Field(typed, { typed = it; msg = null }, t("Your donor code or private link", "Msimbo wako wa mfadhili au kiungo cha siri"), Modifier.padding(top = 10.dp).testTag("donor_code"), placeholder = "K7QM4-XR9TB")
            Text(t("It’s in the message RAFIKI sent you. Paste the whole link, or type the 10-letter code.", "Iko kwenye ujumbe RAFIKI ilikutumia. Bandika kiungo kizima, au andika msimbo wa herufi 10."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            msg?.let { (tone, text) -> Box(Modifier.fillMaxWidth().testTag("donor_msg")) { Msg(text, tone) } }
            Btn(if (busy) t("Checking…", "Inakagua…") else t("Continue", "Endelea"), { go() }, Modifier.fillMaxWidth().padding(top = 16.dp).testTag("donor_go"), enabled = typed.isNotBlank() && !busy)
            TextButton({ mail(ctx, "My RAFIKI donor code") }, Modifier.padding(top = 8.dp)) { DIcon("mail", C.blue2, 18.dp); Spacer(Modifier.width(6.dp)); Text(t("No code? Write to us", "Huna msimbo? Tuandikie"), color = C.blue2, style = MaterialTheme.typography.labelLarge) }
            TextButton(onStaff, Modifier.testTag("donor_staff")) { Text(t("I’m RAFIKI staff", "Mimi ni mfanyakazi wa RAFIKI"), color = C.blue2, style = MaterialTheme.typography.labelLarge) }
            LangSwitch(m.prefs, Modifier.padding(top = 12.dp))
        }
    }
    if (askStaff) StaffSwitchSheet(m, { askStaff = false }) { askStaff = false; go() }
    // opened from a private link: try it straight away (a staff phone asks to sign out first)
    LaunchedEffect(m.donor.prefill) { if (m.donor.prefill.isNotBlank()) { typed = m.donor.prefill; m.donor.prefill = ""; go() } }
}

/** A phone is either staff or donor: signing in as a donor asks to sign out of the staff app first. */
@Composable
fun StaffSwitchSheet(m: AppModel, no: () -> Unit, yes: () -> Unit) {
    DqmSheet("donor_staff_ask", no) { hide ->
        DqmSheetHead(t("This phone is signed in as RAFIKI staff", "Simu hii imeingia kama mfanyakazi wa RAFIKI"), listOf(t("A phone can be used either by staff or by a donor. Sign out of the staff app to continue as a donor?", "Simu inaweza kutumiwa na mfanyakazi au na mfadhili. Toka kwenye programu ya wafanyakazi ili uendelee kama mfadhili?")),
            closeTag = "donor_staff_close", onClose = { hide {} }) { DqmTile("heart", C.hue("coral").fg, C.hue("coral").bg, 64.dp) }
        Btn(t("Sign out and continue", "Toka na uendelee"), { hide { m.signOut(); yes() } }, Modifier.padding(top = 18.dp).fillMaxWidth().testTag("donor_staff_yes"))
        Btn(t("Keep the staff sign-in", "Baki kama mfanyakazi"), { hide {} }, Modifier.padding(top = 8.dp).fillMaxWidth().testTag("donor_staff_no"), light = true)
    }
}

/** Switched off by RAFIKI (revoked or replaced): signed out, with a kind word. */
@Composable
fun DonorGone(m: AppModel) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 48.dp).testTag("donor_gone"), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            DqmTile("heart", C.hue("coral").fg, C.hue("coral").bg, 84.dp)
            Text(t("Your donor access has been switched off", "Ufikiaji wako wa mfadhili umezimwa"), style = MaterialTheme.typography.headlineMedium, color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 20.dp))
            Text(t("Thank you for everything you’ve given. This link or code isn’t active any more; it may have been replaced with a new one.", "Asante kwa yote uliyotoa. Kiungo au msimbo huu haufanyi kazi tena; huenda umebadilishwa na mpya."), style = MaterialTheme.typography.bodyLarge, color = C.text, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
            Text(t("Write to us and we’ll gladly send you a fresh one.", "Tuandikie na tutafurahi kukutumia mpya."), style = MaterialTheme.typography.bodyLarge, color = C.text, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            Btn(t("Write to RAFIKI", "Iandikie RAFIKI"), { mail(ctx, "My RAFIKI donor access") }, Modifier.fillMaxWidth().padding(top = 22.dp), icon = "mail")
            Btn(t("Sign in again", "Ingia tena"), { m.donor.dismissGone(); m.donor.entry = true }, Modifier.fillMaxWidth().padding(top = 10.dp).testTag("donor_again"), light = true)
        }
    }
}

// ---- the donor app -------------------------------------------------------------------------------------------
/** Home, Updates and Settings, with a project on top. */
@Composable
fun DonorApp(m: AppModel) {
    val v = m.donor.view.collectAsState().value
    var tab by rememberSaveable { mutableStateOf("home") }
    var pid by rememberSaveable { mutableStateOf<String?>(null) }
    val go = m.donor.goProject
    LaunchedEffect(go) { if (go != null) { pid = go; m.donor.goProject = null } }
    // fresh data on opening, and quietly every 5 minutes while open
    LaunchedEffect(Unit) { m.donor.refresh(); while (true) { kotlinx.coroutines.delay(5 * 60_000L); m.donor.refresh(bg = true) } }
    BackHandler(pid != null || tab != "home") { if (pid != null) pid = null else tab = "home" }
    val news = v?.updates?.count { m.donor.seen.isNotBlank() && it.at > m.donor.seen } ?: 0
    LaunchedEffect(tab) { if (tab == "updates") { kotlinx.coroutines.delay(1500); m.donor.markSeen() } }
    ProvideSpeaker {
    Column(Modifier.fillMaxSize().testTag("donor_app")) {
        DonorTop(m)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (v == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(t("Loading…", "Inapakia…"), color = C.muted) }
            else {
                val p = pid?.let { v.project(it) }
                // each screen keeps its own scroll, starting at the top
                androidx.compose.runtime.key(p?.id ?: tab) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 6.dp, bottom = 24.dp)) {
                        when {
                            p != null -> DonorProject(m, p) { pid = null }
                            tab == "updates" -> DonorUpdates(m, v) { pid = it }
                            tab == "settings" -> DonorSettings(m, v)
                            else -> DonorHome(m, v) { pid = it }
                        }
                    }
                }
            }
        }
        DonorTabs(if (pid != null) "" else tab, news) { tab = it; pid = null }
    }
    }
}

@Composable
private fun DonorTop(m: AppModel) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(if (C.dark) org.rafikioutreach.pin.R.drawable.rafiki_logo_light else org.rafikioutreach.pin.R.drawable.rafiki_logo_dark), "RAFIKI",
            modifier = Modifier.height(22.dp), contentScale = androidx.compose.ui.layout.ContentScale.FillHeight)
        Spacer(Modifier.width(8.dp))
        val co = C.hue("coral")
        Row(Modifier.clip(RoundedCornerShape(99.dp)).background(co.bg).padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            DIcon("heart", co.fg, 13.dp); Spacer(Modifier.width(4.dp))
            Text(t("DONOR", "MFADHILI"), color = co.fg, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp), maxLines = 1, softWrap = false)
        }
        Spacer(Modifier.weight(1f))
        // text size in one tap: Normal → Large → Larger
        Box(Modifier.padding(end = 8.dp).size(46.dp).clip(RoundedCornerShape(14.dp)).background(C.card).border(1.dp, C.line, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button) {
                val next = when (TextSize.value) { "" -> "l"; "l" -> "xl"; else -> "" }
                TextSize.value = next; m.prefs.textSize = next
                android.widget.Toast.makeText(ctx, when (next) { "l" -> t("Large text", "Maandishi makubwa"); "xl" -> t("Larger text", "Maandishi makubwa zaidi"); else -> t("Normal text", "Maandishi ya kawaida") }, android.widget.Toast.LENGTH_SHORT).show()
            }.semantics { contentDescription = t("Text size", "Ukubwa wa maandishi") }.testTag("donor_aa"), contentAlignment = Alignment.Center) {
            Text("Aa", color = C.ink, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold))
        }
        Row(Modifier.clip(RoundedCornerShape(99.dp)).background(C.primary).clickable(role = Role.Button) { open(ctx, DONOR_GIVE) }.heightIn(min = 40.dp).padding(horizontal = 14.dp).testTag("donor_give"), verticalAlignment = Alignment.CenterVertically) {
            DIcon("heart", Color.White, 16.dp); Spacer(Modifier.width(6.dp))
            Text(t("Give again", "Changia tena"), color = Color.White, style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp), maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun DonorTabs(on: String, news: Int, pick: (String) -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp, top = 6.dp).fillMaxWidth().shadow(16.dp, shape, spotColor = Color(0x33144678)).clip(shape)
        .background(if (C.glassy) (if (C.dark) Color(0xEB1E2A36) else Color(0xF2FFFFFF)) else C.card).border(1.dp, if (C.glassy) C.glassEdge else C.line, shape).height(68.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(Triple("home", t("Home", "Nyumbani"), "house"), Triple("updates", t("Updates", "Habari"), "bell"), Triple("settings", t("Settings", "Mipangilio"), "cog")).forEach { (k, label, icon) ->
            val sel = on == k; val hu = Hue(C.blue2, C.tint)
            Column(Modifier.weight(1f).clickable(role = Role.Tab) { pick(k) }.semantics { selected = sel }.padding(vertical = 6.dp).testTag("dtab_$k"), horizontalAlignment = Alignment.CenterHorizontally) {
                Box {
                    Box(Modifier.size(56.dp, 30.dp).clip(RoundedCornerShape(99.dp)).background(if (sel) hu.bg else Color.Transparent), contentAlignment = Alignment.Center) { DIcon(icon, if (sel) hu.fg else C.muted, 23.dp) }
                    if (k == "updates" && news > 0) Text("$news", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(99.dp)).background(C.hue("coral").fg).padding(horizontal = 5.dp).testTag("dtab_news"))
                }
                Text(label, style = MaterialTheme.typography.labelSmall, color = if (sel) hu.fg else C.muted, maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

@Composable
private fun SectionHead(text: String, extra: String = "") {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp, start = 2.dp, end = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold).heading(), color = C.ink, textAlign = TextAlign.Center)
        if (extra.isNotBlank()) Text(extra, style = MaterialTheme.typography.labelMedium.heading(), color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun DonorHome(m: AppModel, v: DonorView, open: (String) -> Unit) {
    val org = listOf(if (v.group || v.viewer != v.name) v.name else "", if (v.org.isNotBlank() && v.org != v.name) v.org else "").filter { it.isNotBlank() }.joinToString(" · ")
    val hp = v.projects.firstOrNull { cover(m, it) != null }
    val shape = RoundedCornerShape(28.dp)
    Box(Modifier.fillMaxWidth().shadow(14.dp, shape, ambientColor = Color(0x2604263F), spotColor = Color(0x4004263F)).clip(shape).background(DeepBrush).testTag("donor_hero")) {
        if (hp != null) {
            NetImage(cover(m, hp), Modifier.matchParentSize().drift()) {}
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color(0x8C04263F), Color(0xB8054F81), Color(0xF004263F)))))
        }
        Waves(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(70.dp), Color(0x14FFFFFF), Color(0x1F6BBDF2))
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.clip(RoundedCornerShape(99.dp)).background(Color(0x29FFFFFF)).padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                DIcon("lock", Color.White, 13.dp); Spacer(Modifier.width(6.dp))
                Text(t("JUST FOR YOU", "KWA AJILI YAKO TU"), color = Color.White, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp), maxLines = 1, softWrap = false)
            }
            Text(t("Hello, ", "Habari, ") + v.viewer.trim().substringBefore(' '), style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold).heading(), color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
            Text(org.ifBlank { t("Thank you for giving with RAFIKI", "Asante kwa kuchangia na RAFIKI") }, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold).heading(), color = Color(0xD9FFFFFF), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
            if (v.people > 0) Text(
                (if (v.nProjects == 1) t("Your project serves about ", "Mradi wako unahudumia takriban ") else t("Your ${v.nProjects}\u00A0projects serve about ", "Miradi yako ${v.nProjects} inahudumia takriban ")) + (num(v.people) + t(" people.", " watu.")).nb(),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold).heading(), color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp).widthIn(max = 420.dp))
            val allWater = v.nWater > 0 && v.nWater == v.nProjects
            Row(Modifier.padding(top = 18.dp).fillMaxWidth().widthIn(max = 560.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroTile(num(v.nProjects), if (allWater) (if (v.nWater == 1) t("well", "kisima") else t("wells", "visima")) else (if (v.nProjects == 1) t("project", "mradi") else t("projects", "miradi")), Modifier.weight(1f).testTag("dn_tile_projects"))
                HeroTile(num(v.people), t("people served", "wanufaika"), Modifier.weight(1f).testTag("dn_tile_people"))
                HeroTile(num(v.visitsYear), t("visits this year", "ziara mwaka huu"), Modifier.weight(1f).testTag("dn_tile_visits"))
            }
        }
    }
    if (v.projects.any { it.gps != null }) { SectionHead(t("Your projects on the map", "Miradi yako kwenye ramani")); DonorMap(m, v.projects, true, open) }
    SectionHead(if (v.projects.size == 1) t("Your project", "Mradi wako") else t("Your projects", "Miradi yako"), "${v.projects.size}")
    if (v.projects.isEmpty()) EmptyDrop(t("Your projects will appear here soon.", "Miradi yako itaonekana hapa hivi karibuni."))
    v.projects.forEachIndexed { i, p -> ProjectCard(m, p, i, Modifier.padding(bottom = 18.dp).rise(minOf(i, 5))) { open(p.id) } }
}

@Composable
private fun HeroTile(value: String, label: String, modifier: Modifier) {
    val n = value.filter { it.isDigit() }.toIntOrNull()
    val shown = if (n != null && value.all { it.isDigit() || it == ',' }) num(rememberFill(n.toFloat(), 1200).roundToInt()) else value
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Color(0x1FFFFFFF)).border(1.dp, Color(0x38FFFFFF), RoundedCornerShape(18.dp)).heightIn(min = 82.dp).padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        WordFitText(shown, MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp, fontWeight = FontWeight.ExtraBold), Color.White, 1, textAlign = TextAlign.Center)
        WordFitText(label, MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold).heading(), Color(0xE6FFFFFF), 2, textAlign = TextAlign.Center)
    }
}

/** The status on the photo: frosted white, with a dot that breathes while it's working or being fixed. */
@Composable
private fun StateBadge(p: DProject, modifier: Modifier = Modifier) {
    val (fg, _) = stateTone(p.state)
    val pill = RoundedCornerShape(99.dp)
    Row(modifier.shadow(4.dp, pill).clip(pill).background(Color(0xEDFFFFFF)).padding(start = 11.dp, end = 13.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        BreathDot(fg, p.state == "working" || p.state == "repairing"); Spacer(Modifier.width(7.dp))
        Text(donorStateName(p.state), color = fg, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, softWrap = false)
    }
}

/** No photo yet: a deep blue scene with the project's icon floating, rings spreading, bubbles and waves. */
@Composable
private fun NoPhotoScene(p: DProject) {
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF3EA6EC), Color(0xFF0A78C2), Color(0xFF04263F))))) {
        Bubbles(Modifier.matchParentSize())
        Waves(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(90.dp), Color(0x17FFFFFF), Color(0x246BBDF2))
        Box(Modifier.align(Alignment.TopCenter).padding(top = 46.dp).size(84.dp), contentAlignment = Alignment.Center) {
            Ripples(Modifier.matchParentSize())
            Box(Modifier.bob()) { DIcon(iconOf(p), Color(0x66FFFFFF), 60.dp) }
        }
    }
}

private data class St(val n: Int, val suffix: String, val count: Boolean, val label: String)

/** A project, the same card as the web donor page (designed in Figma): the photo with the name on it, three numbers,
 *  the last check as a living line, and one round arrow to open it. Everything centred. */
@Composable
private fun ProjectCard(m: AppModel, p: DProject, index: Int, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    val src = remember { MutableInteractionSource() }
    val (fg, bg) = stateTone(p.state)
    val nPics = p.photos.size + p.gallery.size
    Column(modifier.press(src).fillMaxWidth().shadow(14.dp, shape, ambientColor = Color(0x1404263F), spotColor = Color(0x2E04263F)).clip(shape).background(C.card)
        .border(1.dp, C.ink.copy(alpha = 0.06f), shape).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick).testTag("dcard_${p.id}"),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(358f / 250f).clipToBounds()) {
            NetImage(cover(m, p), Modifier.matchParentSize().drift()) { NoPhotoScene(p) }
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.35f to Color(0x0004263F), 1f to Color(0xE0021727))))
            Box(Modifier.matchParentSize().sheen(500L + minOf(index, 5) * 90L))
            StateBadge(p, Modifier.align(Alignment.TopStart).padding(16.dp))
            if (nPics > 1) Row(Modifier.align(Alignment.TopEnd).padding(16.dp).clip(RoundedCornerShape(99.dp)).background(Color(0x8C021727)).padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
                .semantics { contentDescription = t("$nPics photos", "Picha $nPics") }, verticalAlignment = Alignment.CenterVertically) {
                DIcon("camera", Color.White, 15.dp); Spacer(Modifier.width(6.dp))
                Text("$nPics", color = Color.White, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(kindOf(p).uppercase(), color = Color(0xD9FFFFFF), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp).heading(), textAlign = TextAlign.Center)
                Text(p.title, color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontSize = 25.sp, lineHeight = 29.sp, fontWeight = FontWeight.ExtraBold).heading(), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                if (p.place.isNotBlank()) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    DIcon("pin", Color(0xE6FFFFFF), 15.dp); Spacer(Modifier.width(5.dp))
                    Text(p.place.nb(), color = Color(0xE6FFFFFF), style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, softWrap = false)
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val stats = buildList {
                if (p.people > 0) add(St(p.people, "", true, t("people served", "wanaohudumiwa")))
                if (p.pupils > 0) add(St(p.pupils, "", true, t("pupils", "wanafunzi")))
                if (p.patients > 0) add(St(p.patients, "", true, t("patients a week", "wagonjwa kwa wiki")))
                if (p.children > 0) add(St(p.children, "", true, t("children", "watoto")))
                if (p.litresPerDay > 0) add(St(p.litresPerDay, "\u00A0L", true, t("water a day", "maji kwa siku")))
                if (p.year > 0) add(St(p.year, "", false, if (p.status == "Completed") t("completed", "ilikamilika") else t("year", "mwaka")))
            }.take(3)
            if (stats.isNotEmpty()) Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                stats.forEachIndexed { k, st ->
                    if (k > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(C.line))
                    Column(Modifier.weight(1f).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val shown = if (st.count) num(rememberFill(st.n.toFloat(), 1200).roundToInt()) else st.n.toString()
                        WordFitText(shown + st.suffix, MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.ExtraBold), C.ink, 1, textAlign = TextAlign.Center)
                        Text(st.label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium).heading(), color = C.muted, textAlign = TextAlign.Center)
                    }
                }
            }
            if (p.health.isNotBlank() && p.healthAt.isNotBlank()) {
                val live = p.state == "working" && p.water
                val what = if (live) t("Water flowing", "Maji yanatoka") else donorStateName(p.state)
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg).testTag("dcard_check_${p.id}")) {
                    if (live) Waves(Modifier.matchParentSize(), C.ok.copy(alpha = 0.10f), C.blue.copy(alpha = 0.08f))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        StateMark(p.state, donorStateColor(p.state)); Spacer(Modifier.width(10.dp))
                        Text(what.nb() + " · " + (t("checked ", "imekaguliwa ") + Dates.day(p.healthAt)).nb(), color = fg, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold).para(), textAlign = TextAlign.Center)
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(t("Photos, visits and repairs", "Picha, ziara na matengenezo"), color = C.blue, style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold).heading(), textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(44.dp).shadow(8.dp, CircleShape, spotColor = Color(0x4D0A78C2)).clip(CircleShape).background(Color(0xFF0A78C2)), contentAlignment = Alignment.Center) {
                Box(Modifier.nudge()) { DIcon("arrow", Color.White, 20.dp) }
            }
        }
    }
}

@Composable
private fun Fact(value: String, label: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(if (C.glassy) C.glass2 else C.card).border(1.dp, C.line, RoundedCornerShape(14.dp)).padding(horizontal = 10.dp, vertical = 8.dp)) {
        WordFitText(value, MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp), C.ink, 1)
        WordFitText(label, MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold), C.muted, 2)
    }
}

// ---- a project ---------------------------------------------------------------------------------------------------
@Composable
private fun DonorProject(m: AppModel, p: DProject, back: () -> Unit) {
    val ctx = LocalContext.current
    var viewer by remember { mutableStateOf<Triple<List<Pair<String, String>>, Int, Boolean>?>(null) }
    viewer?.let { (list, at, play) -> PhotoViewer(list, at, play) { viewer = null } }
    Btn(t("Back", "Rudi"), back, Modifier.padding(bottom = 6.dp).testTag("dproject_back"), icon = "back", light = true)
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(24.dp)).background(C.tint)) {
        NetImage(cover(m, p), Modifier.matchParentSize()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { DIcon(iconOf(p), C.blue, 56.dp) } }
    }
    Text(p.title, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold).heading(), color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 14.dp).testTag("dproject_title"))
    Text(listOf(kindOf(p).nb(), p.place.nb()).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold).heading(), color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    val (fg, bg) = stateTone(p.state)
    Card(Modifier.padding(top = 14.dp).testTag("dproject_state")) {
        Row(verticalAlignment = Alignment.Top) {
            IconTile(if (p.state == "repairing" || p.state == "notworking") "wrench" else iconOf(p), fg, bg, 44.dp); Spacer(Modifier.width(12.dp))
            Column { Text(donorStateName(p.state), style = MaterialTheme.typography.titleMedium, color = C.ink); Text(donorStateSay(p) + donorPeopleSay(p).let { if (it.isBlank()) "" else " $it" }, style = MaterialTheme.typography.bodyLarge, color = C.text, modifier = Modifier.padding(top = 2.dp)) }
        }
        SpeakBtn("p_" + p.id, p.title + ". " + donorStateName(p.state) + ". " + donorStateSay(p) + " " + donorPeopleSay(p), Modifier.fillMaxWidth().padding(top = 12.dp))
    }
    val facts = buildList {
        if (p.people > 0) add(num(p.people) to t("people served", "watu wanaohudumiwa"))
        if (p.litresPerDay > 0) add(num(p.litresPerDay) to t("litres a day", "lita kwa siku"))
        if (p.years > 0) add((if (p.years % 1.0 == 0.0) num(p.years.toLong()) else String.format(java.util.Locale.US, "%.1f", p.years)) to if (p.years == 1.0) t("year of water", "mwaka wa maji") else t("years of water", "miaka ya maji"))
        if (p.pupils > 0) add(num(p.pupils) to t("pupils", "wanafunzi"))
        if (p.patients > 0) add(num(p.patients) to t("patients a week", "wagonjwa kwa wiki"))
        if (p.children > 0) add(num(p.children) to t("children", "watoto"))
    }.take(3)
    if (facts.isNotEmpty()) Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { facts.forEach { (a, b) -> Fact(a, b, Modifier.weight(1f)) }; repeat(3 - facts.size) { Spacer(Modifier.weight(1f)) } }
    // photo timeline, newest first
    if (p.gallery.isNotEmpty()) {
        SectionHead(t("Photos", "Picha"), "${p.gallery.size}")
        Row(Modifier.horizontalScroll(rememberScrollState()).testTag("dproject_photos"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val pics = p.gallery.map { g -> m.donor.api.photo(g.id, 1600) to (Dates.weekday(g.at) + if (g.label.isNotBlank()) " · " + g.label else "") }
            p.gallery.forEachIndexed { k, g -> MediaTile(m.donor.api.photo(g.id), pics[k].second, false, true) { viewer = Triple(pics, k, false) } }
        }
        if (p.gallery.size > 1) Btn(t("Watch the ${p.gallery.size} photos as a slideshow", "Tazama picha ${p.gallery.size} kama onyesho"), {
            viewer = Triple(p.gallery.map { g -> m.donor.api.photo(g.id, 1600) to (Dates.weekday(g.at) + if (g.label.isNotBlank()) " · " + g.label else "") }, 0, true)
        }, Modifier.fillMaxWidth().padding(top = 10.dp).testTag("dproject_slides"), icon = "play")
    }
    // thank-yous from the community: photos, videos and their words
    if (p.thanks.isNotEmpty() || p.stories.isNotEmpty()) {
        SectionHead(t("Thank you from the community", "Asante kutoka kwa jamii"))
        if (p.thanks.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).testTag("dproject_thanks"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            p.thanks.forEach { x -> MediaTile(m.donor.api.photo(x.id), Dates.weekday(x.at) + if (x.label.isNotBlank()) " · “" + x.label + "”" else "", x.video, true) {
                if (x.video) open(ctx, m.donor.api.photo(x.id)) else viewer = Triple(listOf(m.donor.api.photo(x.id, 1600) to (if (x.label.isNotBlank()) "“" + x.label + "”" else "")), 0, false)
            } }
        }
        p.stories.forEach { s ->
            Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (C.glassy) C.glass2 else C.card).border(1.dp, C.line, RoundedCornerShape(18.dp)).padding(14.dp)) {
                Text("“" + s.quote + "”", style = MaterialTheme.typography.bodyLarge, color = C.ink)
                if (s.who.isNotBlank()) Text("— " + s.who, style = MaterialTheme.typography.labelMedium, color = C.muted, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
    // progress, and what was spent on it
    val spent = p.repairs.sumOf { it.cost }
    val rows = buildList {
        if (p.year > 0) add((if (p.status == "Completed") t("Completed", "Ilikamilika") else t("Started", "Ilianza")) to if (p.done.isNotBlank()) Dates.day(p.done) else "${p.year}")
        if (p.depth > 0) add(t("Depth", "Kina") to "${p.depth} m")
        if (p.pump.isNotBlank()) add(t("Pump", "Pampu") to p.pump)
        if (spent > 0) add(t("Spent on repairs", "Gharama za matengenezo") to "KES " + num(spent))
    }
    val stage = p.visits.firstOrNull { it.stage.isNotBlank() }?.stage
    if (rows.isNotEmpty() || (p.state == "building" && stage != null)) {
        SectionHead(t("Progress", "Maendeleo"))
        Card(Modifier.testTag("dproject_progress")) {
            if (p.state == "building" && stage != null) {
                val steps = listOf("Foundation", "Walls going up", "Roofing", "Finishing", "Finished"); val pct = (steps.indexOf(stage) + 1).coerceAtLeast(1) / steps.size.toFloat()
                Text(donorStage(stage), style = MaterialTheme.typography.titleSmall, color = C.ink)
                Box(Modifier.padding(top = 6.dp, bottom = 6.dp).fillMaxWidth().height(10.dp).clip(RoundedCornerShape(99.dp)).background(C.line)) {
                    Box(Modifier.fillMaxWidth(pct).height(10.dp).clip(RoundedCornerShape(99.dp)).background(Brush.horizontalGradient(listOf(Color(0xFF47B4F5), Color(0xFF0A78C2)))))
                }
            }
            rows.forEachIndexed { i, (a, b) ->
                if (i > 0 || (p.state == "building" && stage != null)) Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                    Text(a, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = C.muted, modifier = Modifier.weight(1f))
                    Text(b, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = C.ink, textAlign = TextAlign.End)
                }
            }
        }
    }
    // visits and repairs: date, who, what they found (no names or internal notes)
    data class Line(val at: String, val icon: String, val tone: String, val title: String, val text: String)
    val lines = (p.visits.take(8).map { v -> Line(v.at, "cal", if (v.state == "repair") "repairing" else if (v.state == "notworking") "notworking" else "done", t("Site visit", "Ziara") + " · " + Dates.weekday(v.at), donorVisitSay(v)) } +
        p.repairs.take(4).map { r -> val at = r.fixedAt.ifBlank { r.at }; Line(at, "wrench", if (r.open) "repairing" else "working", (if (r.open) t("Repair under way", "Matengenezo yanaendelea") else t("Repaired", "Imerekebishwa")) + " · " + Dates.weekday(at),
            (if (r.problems.isNotEmpty()) r.problems.joinToString(", ") else t("A problem was found", "Tatizo liligunduliwa")) + if (r.cost > 0) " · KES " + num(r.cost) else "") }).sortedByDescending { it.at }
    if (lines.isNotEmpty()) {
        SectionHead(t("Visits and repairs", "Ziara na matengenezo"))
        Card(Modifier.testTag("dproject_visits")) {
            lines.forEachIndexed { i, l ->
                if (i > 0) Box(Modifier.padding(vertical = 10.dp).fillMaxWidth().height(1.dp).background(C.line))
                val (f, b) = stateTone(l.tone)
                Row(verticalAlignment = Alignment.Top) {
                    IconTile(l.icon, f, b, 40.dp); Spacer(Modifier.width(12.dp))
                    Column {
                        Text(l.title, style = MaterialTheme.typography.titleSmall, color = C.ink)
                        Text(t("RAFIKI field team", "Timu ya RAFIKI uwanjani"), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = C.muted)
                        if (l.text.isNotBlank()) Text(l.text, style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
        }
    }
    // where it is
    p.gps?.let { ll ->
        SectionHead(t("Where it is", "Mahali ilipo"))
        DonorMap(m, listOf(p), false) {}
        Btn(t("Open in Google Maps", "Fungua kwenye Google Maps"), { open(ctx, "https://www.google.com/maps/search/?api=1&query=${ll.lat},${ll.lng}") }, Modifier.padding(top = 10.dp), light = true, small = true, icon = "b:gmaps")
    }
    SectionHead(t("Questions about this project?", "Una swali kuhusu mradi huu?"))
    DonorTalk(m, p)
}

@Composable
private fun MediaTile(url: String, caption: String, video: Boolean, tap: Boolean = video, onClick: () -> Unit) {
    Column(Modifier.width(240.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(18.dp)).background(C.tint).clickable(enabled = tap, role = Role.Button, onClick = onClick)) {
            NetImage(url, Modifier.matchParentSize()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { DIcon(if (video) "camera" else "camera", C.blue, 32.dp) } }
            if (video) Box(Modifier.align(Alignment.Center).size(52.dp).clip(CircleShape).background(Color(0xB3000000)).semantics { contentDescription = t("Play the video", "Cheza video") }, contentAlignment = Alignment.Center) {
                Text("▶", color = Color.White, fontSize = 20.sp)
            }
        }
        Text(caption, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = C.muted, modifier = Modifier.padding(top = 5.dp))
    }
}

// ---- updates -------------------------------------------------------------------------------------------------------
private val MONTH_EN = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
private val MONTH_SW = listOf("Januari", "Februari", "Machi", "Aprili", "Mei", "Juni", "Julai", "Agosti", "Septemba", "Oktoba", "Novemba", "Desemba")
fun donorMonth(key: String): String = runCatching { (if (org.rafikioutreach.pin.Lang.sw) MONTH_SW else MONTH_EN)[key.substring(5, 7).toInt() - 1] + " " + key.substring(0, 4) }.getOrDefault(key)
/** "3 visits · 2 new photos · 1 thank-you" for one month. */
fun donorMonthSum(us: List<DUpdate>): String {
    fun c(k: String) = us.count { it.type == k }
    val photos = us.filter { it.type == "photos" }.sumOf { it.n }
    return listOfNotNull(
        c("visit").takeIf { it > 0 }?.let { if (it == 1) t("1 visit", "ziara 1") else t("$it visits", "ziara $it") },
        photos.takeIf { it > 0 }?.let { if (it == 1) t("1 new photo", "picha 1 mpya") else t("$it new photos", "picha $it mpya") },
        c("thanks").takeIf { it > 0 }?.let { if (it == 1) t("1 thank-you", "shukrani 1") else t("$it thank-yous", "shukrani $it") },
        c("repair").takeIf { it > 0 }?.let { if (it == 1) t("1 repair", "matengenezo 1") else t("$it repairs", "matengenezo $it") },
        c("story").takeIf { it > 0 }?.let { if (it == 1) t("1 story", "hadithi 1") else t("$it stories", "hadithi $it") },
    ).joinToString(" · ")
}

@Composable
private fun DonorUpdates(m: AppModel, v: DonorView, open: (String) -> Unit) {
    SectionHead(t("Updates", "Habari"), t("From the RAFIKI field team, newest first", "Kutoka kwa timu ya RAFIKI, mpya kwanza"))
    if (v.updates.isEmpty()) { EmptyDrop(t("News from your projects will appear here: visits, photos and thank-you messages.", "Habari za miradi yako zitaonekana hapa: ziara, picha na ujumbe wa shukrani.")); return }
    val seen = m.donor.seen
    var n = 0
    v.updates.groupBy { it.at.take(7) }.entries.take(12).forEach { (k, us) ->
        Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp).testTag("dmonth_$k"), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(donorMonth(k).nb(), style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.ExtraBold), color = C.ink, textAlign = TextAlign.Center)
            Text(donorMonthSum(us), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold).heading(), color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
        }
        us.take(12).forEach { u -> v.project(u.pid)?.let { p -> UpdateCard(m, u, p, seen.isNotBlank() && u.at > seen, Modifier.padding(bottom = 14.dp).rise(minOf(n++, 6))) { open(p.id) } } }
    }
}

/** The update's icon: it pops in, then lives a little (a heart that beats, a spanner at work, a camera that flashes). */
@Composable
private fun UpdateIcon(icon: String, f: Color, b: Color) {
    val live = when (icon) { "heart" -> Modifier.beat(); "wrench" -> Modifier.atWork(2600); "camera" -> Modifier.flash(); "spark" -> Modifier.bob(4.dp, 2400); else -> Modifier }
    Box(Modifier.pop(250).then(live)) { if (icon == "heart") DqmTile("heart", f, b, 46.dp) else IconTile(icon, f, b, 46.dp) }
}

@Composable
private fun UpdateCard(m: AppModel, u: DUpdate, p: DProject, fresh: Boolean, modifier: Modifier, open: () -> Unit) {
    var viewer by remember { mutableStateOf<Int?>(null) }
    val (icon, tone, title, body) = when (u.type) {
        "visit" -> listOf("cal", if (u.visit?.state == "notworking") "notworking" else if (u.visit?.state == "repair") "repairing" else "done", t("Site visit", "Ziara"), u.visit?.let { donorVisitSay(it) } ?: "")
        "photos" -> listOf("camera", "planned", if (u.n == 1) t("1 new photo", "Picha 1 mpya") else t("${u.n} new photos", "Picha ${u.n} mpya"), "")
        "thanks" -> listOf("heart", "repairing", if (u.media == "video") t("A thank-you video", "Video ya shukrani") else t("A thank-you photo", "Picha ya shukrani"), if (u.text.isNotBlank()) "“" + u.text + "”" else "")
        "repair" -> listOf("wrench", if (u.fixed) "working" else "repairing", if (u.fixed) t("Repaired", "Imerekebishwa") else t("A problem was found", "Tatizo liligunduliwa"), u.problems.joinToString(", "))
        "story" -> listOf("chat", "building", t("A story from the community", "Hadithi kutoka kwa jamii"), "“" + u.text + "”" + if (u.who.isNotBlank()) " — " + u.who else "")
        "anniversary" -> listOf("spark", "working", if (u.years == 1) t("One year of clean water!", "Mwaka mmoja wa maji safi!") else t("${u.years} years of clean water!", "Miaka ${u.years} ya maji safi!"), "")
        else -> return
    }
    val (f, b) = stateTone(tone)
    val shape = RoundedCornerShape(28.dp)
    val story = donorStory(u).ifBlank { body }
    Column(modifier.fillMaxWidth().shadow(12.dp, shape, ambientColor = Color(0x1404263F), spotColor = Color(0x2604263F)).clip(shape)
        .background(if (u.type == "anniversary") Brush.linearGradient(listOf(Color(0xFFFFF6E0), Color(0xFFFDE7C2))) else SolidColor(C.card))
        .border(1.dp, C.ink.copy(alpha = 0.06f), shape).testTag("dupd_${u.type}_${u.pid}"), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            UpdateIcon(icon, f, b)
            Text(p.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold).heading(), color = C.blue2, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = open).padding(horizontal = 6.dp, vertical = 2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Dates.weekday(u.at).nb(), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium), color = C.muted)
                if (fresh) { Spacer(Modifier.width(8.dp)); Tag(t("NEW", "MPYA"), C.hue("coral").bg, C.hue("coral").fg) }
            }
            Text(title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.ExtraBold).heading(), color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            if (story.isNotBlank()) Text(story, style = MaterialTheme.typography.bodyLarge.para(), color = C.text, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            val thumbs = if (u.type == "photos") u.photos.take(3) else if (u.type == "thanks" && u.media != "video" && u.id.isNotBlank()) listOf(u.id) else emptyList()
            if (thumbs.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                thumbs.forEachIndexed { k, id ->
                    NetImage(m.donor.api.photo(id, 480), Modifier.weight(1f, fill = false).widthIn(max = 120.dp).fillMaxWidth().aspectRatio(100f / 84f).pop(350L + k * 100L).clip(RoundedCornerShape(16.dp)).background(C.tint).clickable(role = Role.Button) { viewer = k }) {}
                }
            }
            viewer?.let { k -> PhotoViewer(thumbs.map { id -> m.donor.api.photo(id, 1600) to (p.title + " · " + Dates.weekday(u.at)) }, k, false) { viewer = null } }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(if (u.type == "anniversary") Color(0x268A5200) else C.line))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            SpeakBtn("u_" + u.type + u.pid + u.at, p.title + ". " + title + ". " + Dates.weekday(u.at) + ". " + story, label = t("Listen", "Sikiliza"), small = true)
            Btn(t("See the project", "Ona mradi"), open, small = true, light = true, icon = "arrow")
        }
    }
}

// ---- settings ------------------------------------------------------------------------------------------------------
@Composable
private fun DonorSettings(m: AppModel, v: DonorView) {
    val scope = rememberScope()
    val ctx = LocalContext.current
    var msg by remember { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var askOut by remember { mutableStateOf(false) }
    var alerts by remember { mutableStateOf(m.prefs.phoneAlerts) }
    SectionHead(t("Settings", "Mipangilio"))
    Card(pad = 0.dp, modifier = Modifier.testTag("dset_look")) {
        SetBlock(t("Language", "Lugha")) { Seg(listOf("en" to "English", "sw" to "Kiswahili"), if (org.rafikioutreach.pin.Lang.sw) "sw" else "en", "dset_lang") { org.rafikioutreach.pin.Lang.sw = it == "sw"; m.prefs.lang = it } }
        Line()
        SetBlock(t("Text size", "Ukubwa wa maandishi")) { Seg(listOf("s" to t("Small", "Ndogo"), "" to t("Normal", "Kawaida"), "l" to t("Large", "Kubwa"), "xl" to t("Larger", "Kubwa zaidi")), TextSize.value, "dset_size") { TextSize.value = it; m.prefs.textSize = it } }
        Line()
        SetBlock(t("Colours", "Rangi")) { Seg(listOf("" to t("Automatic", "Kiotomatiki"), "light" to t("Light", "Mwanga"), "dark" to t("Dark", "Giza")), Look.mode, "dset_theme") { Look.mode = it; m.prefs.theme = it } }
    }
    Card(pad = 0.dp, modifier = Modifier.padding(top = 12.dp).testTag("dset_notify")) {
        val sub = when { !v.email -> t("We don’t have your e-mail yet. Write to us to get them.", "Bado hatuna barua pepe yako. Tuandikie uzipate."); !v.digest -> t("Paused by RAFIKI for now.", "Zimesimamishwa na RAFIKI kwa sasa.")
            v.mailOn -> t("A short summary on the 1st of each month.", "Muhtasari mfupi tarehe 1 kila mwezi."); else -> t("Off. You won’t get the monthly e-mail.", "Zimezimwa. Hutapata barua pepe ya mwezi.") }
        SetRow("mail", "teal", t("E-mail updates", "Habari kwa barua pepe"), sub) {
            Switch(v.mailOn, { on -> busy = true; scope.launch { val e = m.donor.setMail(on); busy = false; msg = if (e != null) "bad" to e else "ok" to if (on) t("E-mail updates on ✓", "Habari kwa barua pepe zimewashwa ✓") else t("E-mail updates off", "Habari kwa barua pepe zimezimwa") } },
                Modifier.testTag("dset_mail"), enabled = v.email && v.digest && !busy, colors = SwitchDefaults.colors(checkedTrackColor = C.ok))
        }
        Line()
        SetRow("bell", "amber", t("New photos & updates", "Picha mpya na habari"), t("A notification on this phone when there’s news from your projects.", "Arifa kwenye simu hii kukiwa na habari za miradi yako.")) {
            Switch(alerts, { on -> alerts = on; m.prefs.phoneAlerts = on; if (on) org.rafikioutreach.pin.data.AlertWorker.schedule(ctx.applicationContext) else org.rafikioutreach.pin.data.AlertWorker.cancel(ctx.applicationContext) },
                Modifier.testTag("dset_alerts"), colors = SwitchDefaults.colors(checkedTrackColor = C.ok))
        }
    }
    msg?.let { (tone, text) -> Msg(text, tone) }
    Card(pad = 0.dp, modifier = Modifier.padding(top = 12.dp)) {
        SetRow("heart", "green", t("Give again", "Changia tena"), "rafikioutreach.org/donate", { open(ctx, DONOR_GIVE) }, tag = "dset_give") { DIcon("chev", C.muted, 18.dp) }
        Line()
        SetRow("mail", "blue", t("Contact RAFIKI", "Wasiliana na RAFIKI"), DONOR_CONTACT, { open(ctx, "mailto:$DONOR_CONTACT") }, tag = "dset_contact") { DIcon("chev", C.muted, 18.dp) }
        Line()
        SetRow("back", "coral", t("Sign out", "Toka"), t("You can sign in again with your code or link.", "Unaweza kuingia tena kwa msimbo au kiungo chako."), { askOut = true }, tag = "dset_out") {}
    }
    SectionHead(t("Talk to RAFIKI", "Ongea na RAFIKI"))
    DonorTalk(m, null)
    Text(t("Signed in as ", "Umeingia kama ") + v.viewer, style = MaterialTheme.typography.bodySmall, color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
    if (askOut) DqmSheet("dset_out_ask", { askOut = false }) { hide ->
        DqmSheetHead(t("Sign out?", "Utoke?"), listOf(t("Your projects stay safe. Sign in again any time with your donor code or private link.", "Miradi yako iko salama. Ingia tena wakati wowote kwa msimbo wako wa mfadhili au kiungo cha siri.")), closeTag = "dset_out_close", onClose = { hide {} }) { DqmTile("heart", C.hue("coral").fg, C.hue("coral").bg, 64.dp) }
        Btn(t("Sign out", "Toka"), { hide { m.donor.signOut() } }, Modifier.padding(top = 18.dp).fillMaxWidth().testTag("dset_out_yes"))
        Btn(t("Cancel", "Ghairi"), { hide {} }, Modifier.padding(top = 8.dp).fillMaxWidth(), light = true)
    }
}

@Composable private fun Line() = Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
@Composable
private fun SetBlock(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) { Text(title, style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.padding(bottom = 8.dp)); content() }
}
@Composable
private fun SetRow(icon: String, hue: String, title: String, sub: String, onClick: (() -> Unit)? = null, tag: String = "", end: @Composable () -> Unit) {
    val h = C.hue(hue)
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier).heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp).then(if (tag.isNotBlank()) Modifier.testTag(tag) else Modifier), verticalAlignment = Alignment.CenterVertically) {
        if (icon == "heart") DqmTile("heart", h.fg, h.bg, 38.dp) else IconTile(icon, h.fg, h.bg, 38.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleSmall, color = C.ink); Text(sub, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = C.muted) }
        Spacer(Modifier.width(8.dp)); end()
    }
}
@Composable
private fun Seg(opts: List<Pair<String, String>>, cur: String, tag: String, pick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (C.glassy) C.glass2 else C.card).border(1.dp, C.line, RoundedCornerShape(16.dp)).padding(4.dp).testTag(tag), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        opts.forEach { (k, label) ->
            val on = k == cur
            Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (on) C.primary else SolidColor(Color.Transparent)).clickable(role = Role.RadioButton) { pick(k) }.semantics { selected = on }.heightIn(min = 44.dp).padding(horizontal = 4.dp).testTag(tag + "_" + k.ifBlank { "auto" }), contentAlignment = Alignment.Center) {
                WordFitText(label, MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, lineHeight = 15.sp, textAlign = TextAlign.Center), if (on) Color.White else C.ink, 2)
            }
        }
    }
}

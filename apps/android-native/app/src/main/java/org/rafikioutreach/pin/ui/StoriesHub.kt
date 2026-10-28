package org.rafikioutreach.pin.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.Project
import org.rafikioutreach.pin.data.Snapshot
import org.rafikioutreach.pin.t

// The Stories tab in the bottom bar (web v72: shRender, shPick, shOpen, SH, SHF): every project's story in one place.
// Write a story (find the project), see where each one is, and open the project's story writer (its Stories tab).

/** The four groups a project's story is in: needs a story (none, not started, collecting), waiting for review (a draft),
 *  approved, on the website. */
internal val SH_GROUPS = listOf("need", "draft", "approved", "published")
internal fun shGroupName(k: String) = when (k) {
    "draft" -> t("Waiting for review", "Inasubiri ukaguzi"); "approved" -> t("Approved", "Imeidhinishwa"); "published" -> t("On the website", "Kwenye tovuti"); else -> t("Needs a story", "Inahitaji hadithi")
}
internal fun Snapshot.storyStatus(p: Project) = stories[p.id]?.st?.ifBlank { null } ?: "not-started"
internal fun Snapshot.shGroup(p: Project) = when (storyStatus(p)) { "draft" -> "draft"; "approved" -> "approved"; "published" -> "published"; else -> "need" }
internal fun Snapshot.shCount(): Map<String, Int> = SH_GROUPS.associateWith { g -> projects.count { shGroup(it) == g } }
/** Collecting first, then drafts, then completed projects, then the rest; the latest change first; then by name. */
internal fun Snapshot.shSorted(list: List<Project>): List<Project> {
    fun w(p: Project) = when (storyStatus(p)) { "collecting" -> 0; "draft" -> 1; else -> if (p.status == "Completed") 2 else 3 }
    return list.sortedWith(compareBy<Project> { w(it) }.thenByDescending { stories[it.id]?.at.orEmpty() }.thenBy { it.title })
}
/** "3 of 6 answers · 2 photos · consent received" (nothing for a story not started). */
internal fun Snapshot.shLine(p: Project): String {
    val x = stories[p.id] ?: return ""
    if (storyStatus(p) == "not-started") return ""
    val ph = x.b + x.i + x.a
    return listOf(t("${x.n} of 6 answers", "Majibu ${x.n} kati ya 6"), if (ph == 1) t("1 photo", "Picha 1") else t("$ph photos", "Picha $ph"),
        if (x.c == "received") t("consent received", "ruhusa imepatikana") else t("consent not yet", "ruhusa bado")).joinToString("\u00A0· ") { it.replace(' ', ' ') }
}
private fun shPill(st: String): Pair<String, String> = when (st) {
    "collecting" -> "has" to t("Collecting", "Inakusanywa"); "draft" -> "warn" to t("Waiting for review", "Inasubiri ukaguzi")
    "approved" -> "ok" to t("Approved", "Imeidhinishwa"); "published" -> "ok" to t("On the website", "Kwenye tovuti"); else -> "off" to t("Not started", "Haijaanza")
}

/** The Stories tab. [open]: a project's page on its Stories tab, at the writer. */
@Composable
fun StoriesScreen(m: AppModel, open: (String) -> Unit) {
    val snap by m.snap.collectAsState()
    val s = snap ?: return
    val c = s.shCount()
    // admins open on what is waiting for them, everyone else on what still needs a story
    var f by rememberSaveable { mutableStateOf(if (s.admin && (c["draft"] ?: 0) > 0) "draft" else "need") }
    var picking by rememberSaveable { mutableStateOf(false) }
    val h = rememberHaptics()
    val list = s.shSorted(s.projects.filter { s.shGroup(it) == f })
    val max = 60
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("stories")) {
        Text(t("Stories", "Hadithi"), style = MaterialTheme.typography.headlineLarge, color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().semantics { heading() })
        // story photos still on their way (they keep sending while you use the app)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { StorySendingPill(m, Modifier.padding(top = 4.dp)) }
        // the hero: what this is, and the one big action
        Card(Modifier.padding(top = 8.dp).testTag("sh_hero"), pad = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val hu = C.hue("coral")
                IconTile("spark", hu.fg, hu.bg, 44.dp); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t("Every project has a story", "Kila mradi una hadithi"), style = MaterialTheme.typography.titleMedium, color = C.ink)
                    Text(t("How a project changed lives, in the words of the people it serves. Everything is saved to monday.com.", "Jinsi mradi ulivyobadilisha maisha, kwa maneno ya watu unaowahudumia. Kila kitu kinahifadhiwa monday.com."),
                        style = MaterialTheme.typography.bodySmall, color = C.muted)
                }
            }
            Btn(t("Write a story", "Andika hadithi"), { h.tap(); picking = true }, Modifier.padding(top = 14.dp).fillMaxWidth().heightIn(min = 50.dp).testTag("sh_write"), icon = "plus")
        }
        // the four counts: tap one to see those projects; two by two on a phone
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            val per = if (maxWidth < 560.dp) 2 else 4
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SH_GROUPS.chunked(per).forEach { row ->
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { g -> ShNum(Modifier.weight(1f).fillMaxHeight(), c[g] ?: 0, shGroupName(g), f == g, hot = g == "draft" && s.admin && (c[g] ?: 0) > 0, tag = "sh_f_$g") { h.tap(); f = g } }
                    }
                }
            }
        }
        // the projects in the chosen group
        Card(Modifier.padding(top = 12.dp).testTag("sh_list"), pad = 16.dp) {
            Text(shGroupName(f), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.semantics { heading() }.testTag("sh_list_title"))
            if (f == "draft") Text(if (s.admin) t("Open one to read it, edit the words, then Approve.", "Fungua moja uisome, hariri maneno, kisha Idhinisha.") else t("An admin reads these and approves them for the website.", "Msimamizi anazisoma na kuziidhinisha kwa ajili ya tovuti."),
                style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 4.dp).testTag("sh_note"))
            if (list.isEmpty()) NothingHere(t("Nothing here right now.", "Hakuna kitu hapa sasa hivi."), "sh_none")
            else Column(Modifier.padding(top = 4.dp)) {
                list.take(max).forEachIndexed { i, p -> if (i > 0) HorizontalDivider(color = C.line); ShRow(s, p, "sh_p_${p.id}") { h.tap(); open(p.id) } }
                if (list.size > max) Text(t("Showing $max of ${list.size}. Use “Write a story” to find any project.", "Inaonyesha $max kati ya ${list.size}. Tumia “Andika hadithi” kupata mradi wowote."),
                    style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 8.dp).testTag("sh_more"))
            }
        }
        ShHow()
        Spacer(Modifier.height(24.dp))
    }
    if (picking) ShPicker(s, { picking = false }) { id -> picking = false; open(id) }
}

/** A count: the number, and what it counts (wraps between words, never inside one). */
@Composable
private fun ShNum(modifier: Modifier, n: Int, label: String, on: Boolean, hot: Boolean, tag: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.clip(shape).background(if (on) C.tint else C.bg).border(if (on) 1.5.dp else if (hot) 1.5.dp else 1.dp, if (on) C.blue else if (hot) C.warn else C.line, shape)
        .clickable(role = Role.Button, onClick = onClick).semantics { selected = on }.heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 10.dp).testTag(tag)) {
        Text("$n", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold, fontSize = 22.sp), color = C.ink, maxLines = 1, modifier = Modifier.testTag(tag + "_n"))
        FitWords(label, MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp), C.muted, Modifier.fillMaxWidth(), Alignment.CenterStart, 2)
    }
}

/** A project in the list or the picker: its name and place, where its story is, and what it has so far. */
@Composable
private fun ShRow(s: Snapshot, p: Project, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).heightIn(min = 58.dp).padding(vertical = 10.dp, horizontal = 2.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        val hu = C.hue("coral")
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(hu.bg), contentAlignment = Alignment.Center) { Icon(Ic.get("spark"), null, tint = hu.fg, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, style = MaterialTheme.typography.titleSmall.copy(lineHeight = 18.sp), color = C.ink)
            val place = listOf(p.area, p.country).filter { it.isNotBlank() }.joinToString(" · ")
            if (place.isNotEmpty()) Text(place, style = MaterialTheme.typography.bodySmall, color = C.muted)
            val (tone, label) = shPill(s.storyStatus(p))
            OcvPill(label, tone, Modifier.padding(top = 5.dp, bottom = 2.dp).testTag(tag + "_pill"))
            val line = s.shLine(p)
            if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 2.dp).testTag(tag + "_line"))
        }
        Spacer(Modifier.width(8.dp))
        Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(18.dp))
    }
}

/** "How a story gets to the website": the four steps, folded away. */
@Composable
private fun ShHow() {
    var open by rememberSaveable { mutableStateOf(false) }
    BackStep(open) { open = false }
    val h = rememberHaptics()
    val turn by animateFloatAsState(if (open) 90f else 0f, tween(if (Motion.on) 220 else 0), label = "chev")
    Card(Modifier.padding(top = 12.dp).testTag("sh_how"), pad = 4.dp) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { h.tap(); open = !open }.heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 8.dp).testTag("sh_how_toggle"),
            verticalAlignment = Alignment.CenterVertically) {
            Text(t("How a story gets to the website", "Jinsi hadithi inavyofika kwenye tovuti"), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(18.dp).rotate(turn))
        }
        AnimatedVisibility(open, enter = if (Motion.on) expandVertically(tween(240)) + fadeIn(tween(240)) else fadeIn(tween(0)), exit = if (Motion.on) shrinkVertically(tween(200)) + fadeOut(tween(200)) else fadeOut(tween(0))) {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp).testTag("sh_how_body"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    t("Collect", "Kusanya") to t("Answer the 6 questions (typing or a voice note), add before, during and after photos, a quote, and photo consent.", "Jibu maswali 6 (kwa kuandika au sauti), ongeza picha za kabla, wakati na baada, nukuu, na ruhusa ya picha."),
                    t("Send for review", "Tuma kwa ukaguzi") to t("“Draft the story” writes it from your answers and sends it to an admin.", "“Andika rasimu ya hadithi” inaiandika kutoka kwa majibu yako na kuituma kwa msimamizi."),
                    t("Approve", "Idhinisha") to t("An admin reads it, fixes the words and approves it.", "Msimamizi anaisoma, anarekebisha maneno na kuiidhinisha."),
                    t("Publish", "Chapisha") to t("Approved stories show on the website. Nothing goes public before that.", "Hadithi zilizoidhinishwa zinaonekana kwenye tovuti. Hakuna kinachotoka kabla ya hapo."),
                ).forEachIndexed { i, (lead, text) ->
                    Row(Modifier.testTag("sh_how_$i"), verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(24.dp).clip(CircleShape).background(C.tint), contentAlignment = Alignment.Center) { Text("${i + 1}", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold), color = C.blue2) }
                        Spacer(Modifier.width(10.dp))
                        Text(buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, color = C.ink)) { append(lead) }; append(" "); append(text) },
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp), color = C.text, modifier = Modifier.weight(1f).padding(top = 1.dp))
                    }
                }
            }
        }
    }
}

/** "Which project is the story about?": search by project, village or country. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShPicker(s: Snapshot, close: () -> Unit, pick: (String) -> Unit) {
    val scope = rememberScope()
    val state = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var q by rememberSaveable { mutableStateOf("") }
    val all = s.shSorted(s.projects)
    val qq = q.trim().lowercase()
    val list = all.filter { p -> qq.isEmpty() || "${p.title} ${p.area} ${p.country}".lowercase().contains(qq) }.take(40)
    val h = rememberHaptics()
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = close, sheetState = state, containerColor = C.card, modifier = Modifier.testTag("sh_pick_sheet")) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text(t("Which project is the story about?", "Hadithi inahusu mradi gani?"), style = MaterialTheme.typography.titleLarge, color = C.ink, modifier = Modifier.semantics { heading() })
            OutlinedTextField(q, { q = it }, Modifier.padding(top = 12.dp, bottom = 6.dp).fillMaxWidth().defaultMinSize(minHeight = 52.dp).testTag("sh_q"), singleLine = true,
                placeholder = { Text(t("Search a project or village", "Tafuta mradi au kijiji"), color = C.muted, maxLines = 1) },
                leadingIcon = { Icon(Ic.get("search"), null, tint = C.muted, modifier = Modifier.size(20.dp)) },
                shape = RoundedCornerShape(16.dp), textStyle = MaterialTheme.typography.bodyLarge,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.blue, unfocusedBorderColor = C.edge, focusedContainerColor = C.card, unfocusedContainerColor = C.card))
            if (list.isEmpty()) Text(t("No project matches.", "Hakuna mradi unaolingana."), style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.padding(vertical = 12.dp).testTag("sh_pick_none"))
            list.forEachIndexed { i, p ->
                if (i > 0) HorizontalDivider(color = C.line)
                ShRow(s, p, "sh_pick_${p.id}") { h.tap(); scope.launch { state.hide() }.invokeOnCompletion { pick(p.id) } }
            }
        }
    }
}

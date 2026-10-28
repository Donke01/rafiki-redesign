package org.rafikioutreach.pin.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.COUNTRIES
import org.rafikioutreach.pin.data.ChatMedia
import org.rafikioutreach.pin.data.ChatRow
import org.rafikioutreach.pin.t
import java.time.LocalDate

// Chat 2, one chat (continued): Add people (B16), Media, files and links (B12), Go to a date (B13).

/** B16: who to add, by country (mine first) with "Add all <country> team"; chosen people as chips; already-members
 *  greyed; admins add (and can invite a new person in Office › Team); everyone can share the invite link; someone who
 *  isn't an admin asks the admins with a message in the group. */
@Composable
internal fun AddPeopleSheet(m: AppModel, row: ChatRow, canAdd: Boolean, close: () -> Unit, office: (String) -> Unit) {
    val team by m.team.collectAsState()
    LaunchedEffect(Unit) { if (team == null) m.loadTeam() }
    val presence by m.chat.presence.collectAsState()
    val me = m.chat.me
    val scope = rememberScope()
    var q by remember { mutableStateOf("") }
    val pick = remember { mutableStateListOf<String>() }
    var link by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val hp = rememberHaptics()
    fun inG(n: String) = row.members.any { it.equals(n, true) }
    val ppl = team.orEmpty().filter { !it.name.equals(me, true) && it.name.isNotBlank() }.sortedBy { it.name.lowercase() }
    val mine = team.orEmpty().firstOrNull { it.name.equals(me, true) }?.home.orEmpty()
    val order = listOf("ke", "tz", "ug", "mw").sortedByDescending { it == mine } + ""
    TeamSubSheet("add_people", close, x = false) { hide ->
        CwSheetTitle(t("Add people", "Ongeza watu"))
        if (!canAdd) Text(t("Only the group’s admins can add people. Choose who you’d like, and the admins get a message asking them.", "Wasimamizi wa kikundi pekee wanaweza kuongeza watu. Chagua unaowataka, na wasimamizi watapata ujumbe wa kuwaomba."),
            style = CwType.meta(), color = C.warn, modifier = Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.warnBg).padding(horizontal = 12.dp, vertical = 10.dp).testTag("ap_note"))
        ChatSearchField(q, { q = it }, t("Search the team", "Tafuta kwenye timu"), "ap_q")
        // the chosen people, as chips (tap to take one off)
        if (pick.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp).testTag("ap_chips"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            pick.forEach { n ->
                Row(Modifier.clip(RoundedCornerShape(99.dp)).background(C.tint).clickable(role = Role.Button) { pick.remove(n) }.semantics { contentDescription = t("Remove $n", "Ondoa $n") }
                    .heightIn(min = 36.dp).padding(start = 4.dp, end = 10.dp).testTag("ap_chip_$n"), verticalAlignment = Alignment.CenterVertically) {
                    PersonAvatar(n, 28.dp); Spacer(Modifier.width(6.dp))
                    Text(n.substringBefore(' '), style = CwType.meta(FontWeight.Bold), color = C.ink, maxLines = 1, softWrap = false); Spacer(Modifier.width(4.dp))
                    Icon(Ic.get("x"), null, tint = C.muted, modifier = Modifier.size(14.dp))
                }
            }
        }
        if (m.snap.collectAsState().value?.admin == true) SheetRow("ap_invite", "plus", t("Invite a new person", "Alika mtu mpya"), value = t("Add them to the team in Office", "Mwongeze kwenye timu kupitia Ofisi")) { hide { office("team") } }
        if (row.kind == "group") SheetRow("ap_link", "link", t("Share invite link", "Shiriki kiungo cha mwaliko"), value = t("Teammates can join with it", "Wenzako wanaweza kujiunga nacho")) { link = true }
        if (team == null) Text(t("Loading…", "Inapakia…"), style = CwType.meta(), color = C.muted, modifier = Modifier.padding(12.dp))
        val qq = q.trim().lowercase()
        var any = false
        order.forEach { ck ->
            val grp = ppl.filter { (if (COUNTRIES.containsKey(it.home)) it.home else "") == ck && (qq.isEmpty() || it.name.lowercase().contains(qq)) }
            if (grp.isEmpty()) return@forEach
            any = true
            val free = grp.filter { !inG(it.name) }
            val allOn = free.isNotEmpty() && free.all { it.name in pick }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp).testTag("ap_group_${ck.ifBlank { "other" }}"), verticalAlignment = Alignment.CenterVertically) {
                if (ck.isNotBlank()) FlagAvatar(ck, 22.dp) else Icon(Ic.get("people"), null, tint = C.muted, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(COUNTRIES[ck] ?: t("Other", "Wengine"), style = CwType.meta(FontWeight.ExtraBold), color = C.muted, modifier = Modifier.weight(1f))
                if (ck.isNotBlank() && free.size > 1) Text(if (allOn) t("Clear", "Ondoa") else t("Add all ${COUNTRIES[ck]} team", "Ongeza timu yote ya ${COUNTRIES[ck]}"), style = CwType.meta(FontWeight.ExtraBold), color = Cw2.link(), textAlign = TextAlign.End,
                    modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { hp.tap(); free.forEach { p -> if (allOn) pick.remove(p.name) else if (p.name !in pick) pick.add(p.name) } }
                        .heightIn(min = 36.dp).padding(horizontal = 8.dp, vertical = 9.dp).testTag("ap_all_$ck"))
            }
            grp.forEach { p ->
                val inn = inG(p.name); val on = p.name in pick
                val pr = presence[p.name]
                val line = if (inn) t("Already in the group", "Tayari yuko kwenye kikundi") else listOfNotNull(COUNTRIES[p.home], if (pr?.online == true) t("online", "yupo mtandaoni") else pr?.seen?.takeIf { it > 0 }?.let { lastSeen(it) }).joinToString(" · ")
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).then(if (inn) Modifier else Modifier.clickable(role = Role.Checkbox) { hp.tap(); if (on) pick.remove(p.name) else pick.add(p.name) })
                    .semantics { this.selected = on || inn }.heightIn(min = 56.dp).padding(horizontal = 4.dp, vertical = 6.dp).testTag("ap_p_${p.name}"), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.alpha(if (inn) 0.45f else 1f)) {
                        PersonAvatar(p.name, 42.dp)
                        if (pr?.online == true) Box(Modifier.align(Alignment.BottomEnd).size(13.dp).clip(CircleShape).background(C.card).padding(2.dp).clip(CircleShape).background(ChatLook.online))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = CwType.name(), color = if (inn) C.muted else C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (line.isNotBlank()) Text(line, style = CwType.meta(), color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!inn) Box(Modifier.size(24.dp).clip(CircleShape).background(if (on) Cw2.un() else Color.Transparent).border(2.dp, if (on) Cw2.un() else C.line, CircleShape), contentAlignment = Alignment.Center) {
                        if (on) Icon(Ic.get("check"), null, tint = Color.White, modifier = Modifier.size(15.dp))
                    }
                }
            }
        }
        if (team != null && !any) Text(t("No one found", "Hakuna aliyepatikana"), style = CwType.meta(), color = C.muted, modifier = Modifier.padding(12.dp).testTag("ap_none"))
        val k = pick.size
        Btn(if (canAdd) (if (k == 1) t("Add 1 person", "Ongeza mtu 1") else if (k > 0) t("Add $k people", "Ongeza watu $k") else t("Add people", "Ongeza watu"))
            else (if (k == 1) t("Ask an admin to add 1 person", "Mwombe msimamizi aongeze mtu 1") else if (k > 0) t("Ask an admin to add $k people", "Mwombe msimamizi aongeze watu $k") else t("Ask an admin to add someone", "Mwombe msimamizi aongeze mtu")), {
            busy = true
            val names = pick.toList()
            if (canAdd) scope.launch {
                val e = m.chat.members(row.id, add = names); busy = false
                if (e != null) m.chat.note.value = e else { m.chat.note.value = if (names.size == 1) t("${names[0].substringBefore(' ')} added ✓", "${names[0].substringBefore(' ')} ameongezwa ✓") else t("${names.size} people added ✓", "Watu ${names.size} wameongezwa ✓"); hide { } }
            } else {
                val ad = row.admins.filter { !it.equals(me, true) }
                if (ad.isEmpty()) { busy = false; m.chat.note.value = t("This group has no other admin to ask.", "Kikundi hiki hakina msimamizi mwingine wa kumwomba.") }
                else {
                    val list = names.joinToString(", ")
                    m.chat.send(row.id, "text", ad.joinToString(" ") { "@$it" } + " " + t("could you add $list to this group, please?", "tafadhali mnaweza kuwaongeza $list kwenye kikundi hiki?"), mentions = ad)
                    m.chat.note.value = t("Asked the admins ✓", "Wasimamizi wameombwa ✓"); hide { }
                }
            }
        }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("ap_go"), icon = "plus", enabled = k > 0 && !busy)
    }
    if (link) LinkSheet(m, row.id) { link = false }
}

/** B12: Media, files and links: three tabs from the messages on the phone; tap opens the photo, the file or the link. */
@Composable
internal fun MediaFilesSheet(m: AppModel, c: String, shown: List<ChatMedia>, close: () -> Unit) {
    val msgs by remember(c) { m.chat.msgs(c) }.collectAsState()
    var tab by remember { mutableStateOf("media") }
    var viewer by remember { mutableStateOf<Int?>(null) }
    val ctx = LocalContext.current
    val live = msgs.filter { !it.deleted }
    val media = live.flatMap { msg -> msg.media.filter { it.type == "photo" || it.type == "video" }.map { msg to it } }.asReversed()
    val files = live.flatMap { msg -> msg.media.filter { it.type == "doc" }.map { msg to it } }.asReversed()
    val links = live.flatMap { msg -> Regex("https?://[^\\s<>\"]+").findAll(msg.text).map { msg to it.value.trimEnd('.', ',', ')', '!', '?') }.toList() }.asReversed()
    TeamSubSheet("media_sheet", close) { _ ->
        CwSheetTitle(t("Media, files and links", "Picha, faili na viungo"))
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(99.dp)).background(C.bg).then(if (C === Sun) Modifier.border(2.dp, Color.Black, RoundedCornerShape(99.dp)) else Modifier).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("media" to t("Media", "Picha") + " ${media.size}", "files" to t("Files", "Faili") + " ${files.size}", "links" to t("Links", "Viungo") + " ${links.size}").forEach { (k, l) ->
                val on = tab == k
                Box(Modifier.weight(1f).clip(RoundedCornerShape(99.dp)).background(if (on) (if (C === Sun) Color.Black else C.card) else Color.Transparent).clickable(role = Role.Tab) { tab = k }
                    .semantics { selected = on }.heightIn(min = 40.dp).testTag("mf_tab_$k"), contentAlignment = Alignment.Center) {
                    FitWords(l, CwType.meta(FontWeight.ExtraBold), if (on) (if (C === Sun) Color.White else C.ink) else C.muted, Modifier.fillMaxWidth().padding(horizontal = 4.dp), Alignment.Center, 1)
                }
            }
        }
        when (tab) {
            "media" -> if (media.isEmpty()) MfEmpty(t("No photos or videos yet.", "Bado hakuna picha wala video.")) else media.chunked(3).forEachIndexed { ri, r ->
                Row(Modifier.padding(bottom = 3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    r.forEachIndexed { ci, (_, md) -> Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(C.tint).clickable(role = Role.Button) { viewer = ri * 3 + ci }.testTag("mf_media_${ri * 3 + ci}")) { ChatImage(m, md, Modifier.fillMaxSize(), max = 320) } }
                    repeat(3 - r.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            "files" -> if (files.isEmpty()) MfEmpty(t("No files yet.", "Bado hakuna faili.")) else files.forEach { (_, md) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(vertical = 8.dp).testTag("mf_file"), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(C.hue("coral").bg), contentAlignment = Alignment.Center) { Icon(ChatIc.get("doc"), null, tint = C.hue("coral").fg, modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(md.name.ifBlank { t("Document", "Hati") }, style = CwType.name(), color = C.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(sizeText(md.size), style = CwType.meta(), color = C.muted)
                    }
                }
            }
            else -> if (links.isEmpty()) MfEmpty(t("No links yet.", "Bado hakuna viungo.")) else links.forEach { (_, u) ->
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } }.heightIn(min = 60.dp).padding(vertical = 8.dp).testTag("mf_link"), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(C.tint), contentAlignment = Alignment.Center) { Icon(Ic.get("link"), null, tint = C.blue, modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(Uri.parse(u).host.orEmpty(), style = CwType.name(), color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(u, style = CwType.meta(), color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    viewer?.let { i -> MediaViewer(m, media, i) { viewer = null } }
}

@Composable
private fun MfEmpty(text: String) = Text(text, style = CwType.meta(), color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 8.dp).testTag("mf_none"))

/** B13: Go to a date: the phone's date picker; the chat loads older pages until that day and scrolls there. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun JumpDateSheet(close: () -> Unit, go: (LocalDate) -> Unit) {
    val st = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis(), selectableDates = object : androidx.compose.material3.SelectableDates {
        override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= System.currentTimeMillis()
    })
    androidx.compose.material3.DatePickerDialog(onDismissRequest = close, confirmButton = {
        androidx.compose.material3.TextButton({ st.selectedDateMillis?.let { go(java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate()) }; close() }, Modifier.testTag("date_go")) { Text(t("Go", "Nenda")) }
    }, dismissButton = { androidx.compose.material3.TextButton(close) { Text(t("Cancel", "Ghairi")) } }, modifier = Modifier.testTag("date_sheet")) {
        androidx.compose.material3.DatePicker(st, title = { Text(t("Go to a date", "Nenda kwenye tarehe"), style = CwType.sheet(), modifier = Modifier.padding(start = 24.dp, top = 16.dp)) })
    }
}

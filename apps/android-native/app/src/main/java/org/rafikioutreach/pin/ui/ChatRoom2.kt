package org.rafikioutreach.pin.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.withContext
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.ChatRow
import org.rafikioutreach.pin.data.Msg
import org.rafikioutreach.pin.data.Presence
import org.rafikioutreach.pin.t
import java.io.File
import java.time.Instant

// Chat 2, one chat (web feat-cwr, design/chat-2026-10/room.png and delete.png): the header, the project strip,
// selecting and deleting, clearing, finding, translating, typing out voice notes, project photos and adding people.

/** On a phone a chat has its own header (← picture name ⌕ ⋯) in place of the RAFIKI bar, as on the web app. */
@Composable
internal fun chatRoomOwnsTop(): Boolean = chatListOwnsTop()

/** B1: ←, the picture, name and typing / online / members (a tap opens chat info), search and ⋯. */
@Composable
internal fun RoomHead(m: AppModel, row: ChatRow?, c: String, me: String, presence: Map<String, Presence>, typing: Map<String, Map<String, Pair<String, Long>>>, back: (() -> Unit)?,
                      openInfo: () -> Unit, onSearch: () -> Unit, menu: @Composable () -> Unit) {
    val other = row?.other(me).orEmpty()
    val p = presence[other]
    val typed = remember(typing) { m.chat.typingText(c, row?.isGroup == true) }
    val status = when {
        typed != null -> typed
        row == null -> ""
        row.kind == "dm" -> if (p?.online == true) t("online", "yupo mtandaoni") else lastSeen(p?.seen ?: 0)
        row.announce -> t("Everyone on the team · admins post", "Timu nzima · wasimamizi wanatuma")
        row.kind == "project" -> t("${row.members.size} people", "watu ${row.members.size}")
        else -> row.members.sortedBy { !it.equals(me, true) }.joinToString(", ") { if (it.equals(me, true)) t("You", "Wewe") else it.substringBefore(' ') }
    }
    val hp = rememberHaptics()
    val narrow = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 360
    Row(Modifier.fillMaxWidth().background(C.card).heightIn(min = 60.dp).padding(start = 2.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) { hp.tap(); back() }.semantics { contentDescription = t("Back", "Rudi") }.testTag("room_back"), contentAlignment = Alignment.Center) {
            Icon(Ic.get("back"), null, tint = C.ink, modifier = Modifier.size(24.dp))
        }
        Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { openInfo() }.padding(horizontal = 6.dp, vertical = 4.dp).testTag("chat_head"), verticalAlignment = Alignment.CenterVertically) {
            ChatAvatar(row, me, 40.dp, row?.kind == "dm" && p?.online == true, m = m)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(row?.let { chatTitle(it, me) } ?: "", style = CwType.name(FontWeight.ExtraBold), color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("head_title"))
                if (status.isNotBlank()) Text(status, style = CwType.meta(if (typed != null) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (typed != null || (p?.online == true && row?.kind == "dm")) Cw2.un() else C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("head_status"))
            }
            if (row?.muted() == true) Icon(ChatIc.get("mute"), t("Muted", "Imenyamazishwa"), tint = C.muted, modifier = Modifier.size(17.dp))
        }
        // the smallest phones: the name gets the room; search is in ⋯ too
        if (!narrow) Box(Modifier.size(42.dp).clip(CircleShape).clickable(role = Role.Button) { hp.tap(); onSearch() }.semantics { contentDescription = t("Search this chat", "Tafuta kwenye gumzo hili") }.testTag("room_search"), contentAlignment = Alignment.Center) {
            Icon(Ic.get("search"), null, tint = C.ink, modifier = Modifier.size(22.dp))
        }
        menu()
    }
}

/** B2: a project chat's strip: its condition, "Visited 27 Oct · Eldoret, Kenya", Open project ›. */
@Composable
internal fun ProjectStrip(m: AppModel, row: ChatRow, openProject: (String, String) -> Unit) {
    if (row.kind != "project" || row.project.isBlank()) return
    val snap = m.snap.collectAsState().value ?: return
    val p = snap.projects.firstOrNull { it.id == row.project } ?: return
    val rep = snap.repairs[p.id]
    val (label, tone) = when {
        rep?.state == "notworking" -> t("Not working", "Haifanyi kazi") to "bad"
        rep != null -> t("Needs repair", "Inahitaji matengenezo") to "warn"
        snap.building(p) -> t("Under construction", "Inajengwa") to "build"
        else -> t("Working", "Inafanya kazi") to "ok"
    }
    val (fg, bg) = when (tone) { "bad" -> C.bad to C.badBg; "warn" -> C.warn to C.warnBg; "build" -> C.blue2 to C.tint; else -> (if (C === Sun) Color(0xFF00562A) else if (C.dark) C.ok else Color(0xFF11643A)) to (if (C === Sun) Color(0xFFD8F5E3) else if (C.dark) C.okBg else Color(0xFFE3F4EA)) }
    val v = snap.visits[p.id]
    val place = listOf(p.area, p.country).filter { it.isNotBlank() }.joinToString(", ")
    val line = listOfNotNull(v?.at?.takeIf { it.isNotBlank() }?.let { a -> runCatching { t("Visited ", "Ilitembelewa ") + Dates.day(Instant.parse(a).atZone(Dates.zone()).toLocalDate()) }.getOrNull() }, place.ifBlank { null }).joinToString(" · ")
    val hp = rememberHaptics()
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val st = CwType.meta(FontWeight.Bold); val so = CwType.meta(FontWeight.ExtraBold)
    val open = t("Open project ›", "Fungua mradi ›")
    BoxWithConstraints(Modifier.fillMaxWidth().background(if (C === Sun) Color.White else if (C.dark) Color(0xFF121A21) else Color(0xFFF7FAFC)).clickable(role = Role.Button) { hp.tap(); openProject(p.id, "pin") }
        .heightIn(min = 44.dp).padding(horizontal = 14.dp, vertical = 6.dp).testTag("proj_strip")) {
        val dens = androidx.compose.ui.platform.LocalDensity.current
        // the status and Open project always whole; the place gets what's left, or its own line when that's too little
        val need = with(dens) { (measurer.measure(label, st).size.width + measurer.measure(open, so).size.width).toDp() } + 31.dp + 18.dp + 80.dp
        val pill = @Composable {
            Row(Modifier.clip(RoundedCornerShape(99.dp)).then(if (C === Sun) Modifier.border(2.dp, Color.Black, RoundedCornerShape(99.dp)) else Modifier).background(bg).padding(horizontal = 10.dp, vertical = 3.dp).testTag("proj_strip_status"), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(fg)); Spacer(Modifier.width(6.dp))
                Text(label, style = st, color = fg, maxLines = 1, softWrap = false)
            }
        }
        val openT = @Composable { Text(open, style = so, color = Cw2.link(), maxLines = 1, softWrap = false, modifier = Modifier.testTag("proj_strip_open")) }
        if (need <= maxWidth) Row(verticalAlignment = Alignment.CenterVertically) {
            pill(); Spacer(Modifier.width(10.dp))
            Text(line, style = CwType.meta(), color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("proj_strip_line"))
            Spacer(Modifier.width(8.dp)); openT()
        } else Column {
            Row(verticalAlignment = Alignment.CenterVertically) { pill(); Spacer(Modifier.weight(1f).widthIn(min = 8.dp)); openT() }
            if (line.isNotBlank()) Text(line, style = CwType.meta(), color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp).testTag("proj_strip_line"))
        }
    }
    HorizontalDivider(color = if (C === Sun) Color.Black else C.line, thickness = if (C === Sun) 2.dp else 1.dp)
}

/** B10: selecting: a dark bar over the header: × "2 selected", forward (one), star, delete. */
@Composable
internal fun SelBar(n: Int, canFwd: Boolean, close: () -> Unit, fwd: () -> Unit, star: () -> Unit, del: () -> Unit) {
    val bar = if (C === Sun) Color.Black else if (C.dark) Color(0xFF04101A) else Color(0xFF0B1F2E)
    @Composable fun btn(icon: String, label: String, tag: String, on: Boolean = true, go: () -> Unit) =
        Box(Modifier.size(44.dp).clip(CircleShape).then(if (on) Modifier.clickable(role = Role.Button, onClick = go) else Modifier).semantics { contentDescription = label }.testTag(tag), contentAlignment = Alignment.Center) {
            Icon(ChatIc.get(icon), null, tint = Color.White.copy(alpha = if (on) 1f else 0.35f), modifier = Modifier.size(22.dp))
        }
    Row(Modifier.fillMaxWidth().background(bar).heightIn(min = 60.dp).padding(horizontal = 4.dp).testTag("sel_bar"), verticalAlignment = Alignment.CenterVertically) {
        btn("x", t("Stop selecting", "Acha kuchagua"), "sel_close", go = close)
        Text(t("$n selected", "$n zimechaguliwa"), style = CwType.name(FontWeight.ExtraBold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 6.dp).testTag("sel_n"))
        btn("fwd", t("Forward", "Sambaza"), "sel_fwd", canFwd, fwd)
        btn("star", t("Star", "Weka nyota"), "sel_star", n > 0, star)
        btn("trash", t("Delete", "Futa"), "sel_delete", n > 0, del)
    }
}

/** B10, delete.png: "Delete 2 messages?": for everyone (all mine, or I'm this chat's admin: then it says so), for me, Cancel. */
@Composable
internal fun DeleteSheet(m: AppModel, row: ChatRow?, ms: List<Msg>, close: () -> Unit, done: () -> Unit) {
    if (ms.isEmpty()) { close(); return }
    val me = m.chat.me
    val officeAdmin = m.snap.collectAsState().value?.admin == true
    val scope = rememberScope()
    val n = ms.size; val one = n == 1
    val mine = ms.all { it.from.equals(me, true) }
    val chatAdmin = row != null && (row.admin(me) || (officeAdmin && row.isGroup))
    val all = ms.any { !it.deleted } && (mine || chatAdmin)
    val others = ms.filter { !it.from.equals(me, true) }
    val names = others.map { it.from.substringBefore(' ') }.distinct()
    val why2 = t("so “Delete for everyone” is only there because you’re an admin of this chat.", "kwa hiyo “Futa kwa wote” ipo kwa sababu wewe ni msimamizi wa gumzo hili.")
    val note = if (!all || mine) "" else when {
        one -> t("It’s ${names[0]}’s message, ", "Ni ujumbe wa ${names[0]}, ") + why2
        others.size == 1 -> t("One of them is ${names[0]}’s, ", "Mmoja wao ni wa ${names[0]}, ") + why2
        names.size == 1 && others.size == n -> t("They are ${names[0]}’s, ", "Ni za ${names[0]}, ") + why2
        else -> t("${others.size} of them are other people’s, ", "Jumbe ${others.size} ni za watu wengine, ") + why2
    }
    val mem = row?.members?.size ?: 0
    val everyone = if (row?.kind == "dm") t("Gone for both of you. They see “This message was deleted”.", (if (one) "Unaondoka" else "Zinaondoka") + " kwa nyote wawili. Wataona “Ujumbe huu ulifutwa”.")
        else t("Gone for all $mem people. They see “This message was deleted”.", (if (one) "Unaondoka" else "Zinaondoka") + " kwa watu wote $mem. Wataona “Ujumbe huu ulifutwa”.")
    val forMe = if (one) t("Only gone from your phone. Others still see it.", "Unaondoka kwenye simu yako tu. Wengine bado wanauona.") else t("Only gone from your phone. Others still see them.", "Zinaondoka kwenye simu yako tu. Wengine bado wanaziona.")
    fun go(forAll: Boolean) {
        val c = ms.first().c
        // the sheet closes at once: the work runs in the app's own scope, not the sheet's
        m.viewModelScope.launch { m.chat.deleteMany(c, ms, forAll)?.let { m.chat.note.value = it } ?: run { if (n > 1) m.chat.note.value = t("$n messages deleted", "Jumbe $n zimefutwa") } }
        done()
    }
    TeamSubSheet("delete_sheet", close, x = false) { hide ->
        CwSheetTitle(if (one) t("Delete message?", "Futa ujumbe?") else t("Delete $n messages?", "Futa jumbe $n?"))
        if (note.isNotEmpty()) Text(note, style = CwType.meta(), color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 10.dp).testTag("del_note"))
        if (all) DelOption("del_all", "trash", t("Delete for everyone", "Futa kwa wote"), everyone, red = true) { hide { go(true) } }
        DelOption("del_me", "person", t("Delete for me", "Futa kwangu"), forMe) { hide { go(false) } }
        Text(t("Cancel", "Ghairi"), style = CwType.name(FontWeight.ExtraBold), color = C.ink, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { hide { } }.heightIn(min = 48.dp).padding(vertical = 13.dp).testTag("del_no"))
    }
}

/** A chat sheet's title (B17): one size for every chat sheet, the size Office and Stories sheets use. */
@Composable
internal fun CwSheetTitle(text: String, tag: String = "sheet_title") =
    Text(text, style = CwType.sheet(), color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 10.dp).semantics { heading() }.testTag(tag))

@Composable
private fun DelOption(tag: String, icon: String, title: String, line: String, red: Boolean = false, onClick: () -> Unit) {
    val hp = rememberHaptics()
    Row(Modifier.padding(bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).then(if (C === Sun) Modifier.border(2.dp, Color.Black, RoundedCornerShape(14.dp)) else Modifier)
        .background(if (C === Sun) Color.White else C.bg).clickable(role = Role.Button) { hp.tap(); onClick() }.heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 12.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(if (red) C.badBg else C.hue("slate").bg), contentAlignment = Alignment.Center) {
            Icon(ChatIc.get(icon), null, tint = if (red) C.bad else C.hue("slate").fg, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = CwType.name(FontWeight.ExtraBold), color = if (red) C.bad else C.ink)
            Text(line, style = CwType.meta(), color = C.muted, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** B11: Clear chat (for me), or a DM's Delete chat (cleared and moved to Archived). */
@Composable
internal fun ClearSheet(m: AppModel, row: ChatRow, del: Boolean, close: () -> Unit, deleted: () -> Unit) {
    val scope = rememberScope()
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val who = chatTitle(row, m.chat.me).substringBefore(' ')
    TeamSubSheet(if (del) "delchat_sheet" else "clear_sheet", close, x = false) { hide ->
        CwSheetTitle(if (del) t("Delete this chat?", "Futa gumzo hili?") else t("Clear this chat?", "Futa jumbe za gumzo hili?"))
        Text(if (del) t("Its messages leave your view and it moves to Archived. $who still has them.", "Jumbe zake zinaondoka kwako na gumzo linahamia Zilizohifadhiwa. $who bado anazo.")
            else t("All its messages leave your view. Everyone else still sees them.", "Jumbe zake zote zinaondoka kwako. Wengine bado wanaziona."),
            style = CwType.meta(), color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp))
        err?.let { Msg(it, "bad") }
        Btn(if (del) t("Delete chat", "Futa gumzo") else t("Clear chat", "Futa jumbe"), {
            busy = true
            scope.launch {
                val e = m.chat.clear(row.id, del); busy = false
                if (e != null) err = e else { m.chat.note.value = if (del) t("Chat deleted", "Gumzo limefutwa") else t("Chat cleared", "Jumbe zimefutwa"); hide { if (del) deleted() } }
            }
        }, Modifier.fillMaxWidth().testTag("clear_go"), icon = "trash", color = C.bad, enabled = !busy)
        Text(t("Cancel", "Ghairi"), style = CwType.name(FontWeight.ExtraBold), color = C.ink, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { hide { } }.heightIn(min = 48.dp).padding(vertical = 13.dp).testTag("clear_no"))
    }
}

/** ⋯ in a chat: search, select, media, add people, clear, delete (DMs), info. */
@Composable
internal fun RoomMenu(row: ChatRow?, canAdd: Boolean, pick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    BackStep(open) { open = false }
    val hp = rememberHaptics()
    Box {
        Box(Modifier.size(42.dp).clip(CircleShape).background(if (open) C.tint else Color.Transparent).clickable(role = Role.Button) { hp.tap(); open = true }.semantics { contentDescription = t("More", "Zaidi") }.testTag("room_menu"), contentAlignment = Alignment.Center) {
            Icon(Ic.get("more"), null, tint = C.ink, modifier = Modifier.size(22.dp))
        }
        DropdownMenu(open, { open = false }, modifier = Modifier.background(C.card).testTag("room_menu_pop")) {
            @Composable fun item(k: String, icon: String, label: String, red: Boolean = false) = DropdownMenuItem({ Text(label, style = CwType.name(FontWeight.SemiBold), color = if (red) C.bad else C.text) },
                { open = false; pick(k) }, leadingIcon = { Icon(ChatIc.get(icon), null, tint = if (red) C.bad else C.muted, modifier = Modifier.size(21.dp)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("room_m_$k"))
            item("search", "search", t("Search", "Tafuta"))
            item("date", "cal", t("Go to a date", "Nenda kwenye tarehe"))
            item("select", "selectall", t("Select messages", "Chagua jumbe"))
            item("media", "gallery", t("Media, files and links", "Picha, faili na viungo"))
            if (row?.isGroup == true && row.kind != "team" && !row.announce) item("add", "group", if (canAdd) t("Add people", "Ongeza watu") else t("Ask to add someone", "Omba kuongeza mtu"))
            item("info", "info", t("Chat info", "Maelezo ya gumzo"))
            item("clear", "trash", t("Clear chat", "Futa jumbe"), red = true)
            if (row?.kind == "dm") item("delete", "trash", t("Delete chat", "Futa gumzo"), red = true)
        }
    }
}

/** Search in this chat: the words, "2 of 5", ▲ ▼; Back closes it. */
@Composable
internal fun FindBar(q: String, set: (String) -> Unit, n: Int, at: Int, up: () -> Unit, down: () -> Unit, close: () -> Unit) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Row(Modifier.fillMaxWidth().background(C.card).heightIn(min = 56.dp).padding(horizontal = 4.dp).testTag("find_bar"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = close).semantics { contentDescription = t("Close the search", "Funga utafutaji") }.testTag("find_close"), contentAlignment = Alignment.Center) {
            Icon(Ic.get("back"), null, tint = C.ink, modifier = Modifier.size(22.dp))
        }
        Box(Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).background(C.bg).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
            if (q.isEmpty()) Text(t("Search this chat", "Tafuta kwenye gumzo hili"), style = CwType.msg(), color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(q, set, Modifier.fillMaxWidth().focusRequester(fr).testTag("find_q"), singleLine = true, textStyle = CwType.msg().copy(color = C.text), cursorBrush = SolidColor(C.blue))
        }
        if (q.isNotBlank()) Text(if (n == 0) "0" else "${at + 1}/$n", style = CwType.meta(FontWeight.Bold), color = C.muted, maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 6.dp).testTag("find_n"))
        Box(Modifier.size(40.dp).clip(CircleShape).clickable(enabled = n > 1, role = Role.Button, onClick = up).semantics { contentDescription = t("Older", "Za zamani") }.testTag("find_up"), contentAlignment = Alignment.Center) {
            Icon(Ic.get("chev"), null, tint = if (n > 1) C.ink else C.muted.copy(alpha = .4f), modifier = Modifier.size(20.dp).rotate(-90f))
        }
        Box(Modifier.size(40.dp).clip(CircleShape).clickable(enabled = n > 1, role = Role.Button, onClick = down).semantics { contentDescription = t("Newer", "Mpya zaidi") }.testTag("find_down"), contentAlignment = Alignment.Center) {
            Icon(Ic.get("chev"), null, tint = if (n > 1) C.ink else C.muted.copy(alpha = .4f), modifier = Modifier.size(20.dp).rotate(90f))
        }
    }
    HorizontalDivider(color = C.line)
}

// ---------------------------------------------------------------------------------------------- under a message

/** The web's lib/translate.js isSw: is this Kiswahili? (common words, or nearly every word ending in a vowel) */
private val SW_WORDS = setOf("na", "ya", "wa", "za", "la", "cha", "vya", "kwa", "ni", "si", "hii", "huu", "hiyo", "hapa", "pale", "kama", "pia", "sasa", "kila", "leo", "jana", "kesho", "bado", "sana", "asante",
    "habari", "ndiyo", "hapana", "hakuna", "kuna", "iko", "yuko", "nini", "lini", "wapi", "maji", "kisima", "pampu", "kazi", "watu", "watoto", "safi", "siku", "mzuri", "nzuri", "tafadhali", "karibu", "pole",
    "sawa", "mimi", "wewe", "yeye", "sisi", "nyinyi", "wao", "lakini", "au", "tu", "tena", "mwenyekiti", "kamati")
internal fun isSw(text: String): Boolean {
    val w = text.lowercase().replace(Regex("[^a-z'\\s]"), " ").split(Regex("\\s+")).filter { it.length > 1 }
    if (w.isEmpty()) return false
    val hits = w.count { it in SW_WORDS }; val vow = w.count { it.last() in "aeiou" }
    return hits.toFloat() / w.size >= 0.15f || (w.size >= 3 && vow.toFloat() / w.size >= 0.85f)
}
/** Under a message only when it's in the other language (and long enough to need it); always in the long-press menu. */
internal fun wantsTranslate(msg: Msg, mine: Boolean): Boolean {
    if (mine || msg.deleted || msg.text.isBlank() || (msg.kind != "text" && msg.kind != "media")) return false
    val tx = msg.text.replace(Regex("https?://\\S+"), " "); val n = Regex("[A-Za-z']{2,}").findAll(tx).count()
    if (n < 3) return false
    return if (org.rafikioutreach.pin.Lang.sw) n >= 5 && !isSw(tx) else isSw(tx)
}
internal fun googleTranslate(text: String) = "https://translate.google.com/?sl=auto&tl=" + (if (isSw(text)) "en" else "sw") + "&text=" + Uri.encode(text.take(2000)) + "&op=translate"

/** B7: "Translate · Tafsiri" under a message; the translation in muted italics with "Show original". */
@Composable
internal fun TranslateLine(m: AppModel, msg: Msg, mine: Boolean) {
    val got = m.chat.translated[msg.id]
    var showing by remember(msg.id) { mutableStateOf(got != null) }
    var busy by remember(msg.id) { mutableStateOf(false) }
    if (!wantsTranslate(msg, mine) && got == null) return
    val ctx = LocalContext.current
    val scope = rememberScope()
    if (got != null && showing) {
        Row(Modifier.padding(top = 4.dp).height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(C.line))
            Text(got, style = CwType.msg().copy(fontStyle = FontStyle.Italic), color = C.muted, modifier = Modifier.padding(start = 9.dp).testTag("translation"))
        }
        Text(t("Show original", "Onyesha ya asili"), style = CwType.small(FontWeight.ExtraBold), color = Cw2.link(), modifier = Modifier.clickable(role = Role.Button) { showing = false }.heightIn(min = 30.dp).padding(vertical = 7.dp).testTag("translate_orig"))
        return
    }
    if (busy) { Text(t("Translating…", "Inatafsiri…"), style = CwType.small(FontWeight.SemiBold), color = C.muted, modifier = Modifier.heightIn(min = 30.dp).padding(vertical = 7.dp)); return }
    Text("Translate · Tafsiri", style = CwType.small(FontWeight.ExtraBold), color = Cw2.link(), maxLines = 1, softWrap = false, modifier = Modifier.clickable(role = Role.Button) {
        if (got != null) { showing = true; return@clickable }
        if (m.chat.tools["translate"] != true) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(googleTranslate(msg.text)))) }; return@clickable }
        busy = true
        scope.launch {
            val (tx, err, google) = m.chat.translate(msg.text); busy = false
            if (tx != null) { m.chat.translated[msg.id] = tx; showing = true }
            else if (google != null) runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(google))) }
            else m.chat.note.value = err
        }
    }.heightIn(min = 30.dp).padding(vertical = 7.dp).testTag("translate"))
}

/** B8: "Show text" under a voice note (when typing out is switched on), the words under the waveform. */
@Composable
internal fun VoiceTextLine(m: AppModel, msg: Msg) {
    if (m.chat.tools["transcribe"] != true || msg.id.isBlank() || msg.queued || msg.deleted) return
    val got = m.chat.typedOut[msg.id]
    var showing by remember(msg.id) { mutableStateOf(false) }
    var busy by remember(msg.id) { mutableStateOf(false) }
    val scope = rememberScope()
    if (got != null && showing) Text(got.ifBlank { t("No words were heard.", "Hakuna maneno yaliyosikika.") }, style = CwType.msg().copy(fontStyle = if (got.isBlank()) FontStyle.Italic else FontStyle.Normal), color = C.text, modifier = Modifier.padding(top = 4.dp).testTag("voice_words"))
    if (busy) { Text(t("Typing it out…", "Inaandika maneno…"), style = CwType.small(FontWeight.SemiBold), color = C.muted, modifier = Modifier.heightIn(min = 30.dp).padding(vertical = 7.dp)); return }
    Text(if (got != null && showing) t("Hide text", "Ficha maandishi") else t("Show text", "Onyesha maandishi"), style = CwType.small(FontWeight.ExtraBold), color = Cw2.link(), maxLines = 1, softWrap = false,
        modifier = Modifier.clickable(role = Role.Button) {
            if (showing) { showing = false; return@clickable }
            if (got != null) { showing = true; return@clickable }
            busy = true
            scope.launch { val (tx, err) = m.chat.transcribe(msg); busy = false; if (tx != null) { m.chat.typedOut[msg.id] = tx; showing = true } else m.chat.note.value = err }
        }.heightIn(min = 30.dp).padding(vertical = 7.dp).testTag("voice_text"))
}

/** B5: "Add to project photos" under a photo in a project chat; ✓ "Added to project" after. */
@Composable
internal fun AddPhotosLine(m: AppModel, msg: Msg, row: ChatRow?, story: (String, List<File>) -> Unit) {
    if (row?.kind != "project" || msg.deleted || msg.queued || msg.id.isBlank() || msg.media.none { it.type == "photo" }) return
    val pid = row.project
    if (m.snap.collectAsState().value?.projects?.any { it.id == pid } != true) return
    m.chat.tick.collectAsState().value
    var busy by remember(msg.id) { mutableStateOf(false) }
    val scope = rememberScope()
    val shape = RoundedCornerShape(9.dp)
    val base = Modifier.padding(top = 7.dp, bottom = 2.dp).fillMaxWidth().clip(shape).then(if (C === Sun) Modifier.border(2.dp, Color.Black, shape) else Modifier)
    when {
        m.chat.added(msg) -> Row(base.background(C.okBg).padding(horizontal = 10.dp, vertical = 7.dp).testTag("photos_added"), verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.get("check"), null, tint = C.ok, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(7.dp))
            Text(t("Added to project", "Imeongezwa kwenye mradi"), style = CwType.meta(FontWeight.ExtraBold), color = C.ok)
        }
        busy -> Text(t("Adding to the project…", "Inaongeza kwenye mradi…"), style = CwType.meta(FontWeight.SemiBold), color = C.muted, modifier = base.background(C.tint).padding(horizontal = 10.dp, vertical = 7.dp))
        else -> Row(base.background(C.tint).clickable(role = Role.Button) {
            busy = true
            scope.launch {
                val (files, err) = m.chat.addPhotos(msg, pid); busy = false
                if (err != null) m.chat.note.value = err else { m.chat.note.value = t("Added to the project ✓", "Imeongezwa kwenye mradi ✓"); story(pid, files) }
            }
        }.padding(horizontal = 10.dp, vertical = 7.dp).testTag("add_photos"), verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.get("plus"), null, tint = Cw2.link(), modifier = Modifier.size(16.dp)); Spacer(Modifier.width(7.dp))
            Text(t("Add to project photos", "Ongeza kwenye picha za mradi"), style = CwType.meta(FontWeight.ExtraBold), color = Cw2.link())
        }
    }
}

/** "Also add to the story?": Before / During / After, and whether children can be seen. */
@Composable
internal fun StoryAskSheet(m: AppModel, pid: String, files: List<File>, close: () -> Unit) {
    var kids by remember { mutableStateOf(false) }
    val scope = rememberScope()
    TeamSubSheet("story_ask", close, x = false) { hide ->
        CwSheetTitle(t("Also add to the story?", "Ongeza pia kwenye hadithi?"))
        Text(t("The project’s story collects photos from before, during and after the work.", "Hadithi ya mradi inakusanya picha za kabla, wakati na baada ya kazi."), style = CwType.meta(), color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Row(Modifier.padding(top = 12.dp, bottom = 6.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("before" to t("Before", "Kabla"), "progress" to t("During", "Wakati wa kazi"), "after" to t("After", "Baada")).forEach { (k, l) ->
                Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(C.tint).clickable(role = Role.Button) {
                    hide {
                        m.chat.note.value = t("Adding to the story…", "Inaongeza kwenye hadithi…")
                        m.viewModelScope.launch {
                            var ok = true
                            for (f in files) {
                                val r = m.api.postMultipart("/api/story", withContext(Dispatchers.IO) { f.readBytes() }, "image/jpeg", "chat.jpg", linkedMapOf("action" to "photo", "id" to pid, "row" to k, "children" to kids.toString()))
                                if (!r.ok) { ok = false; m.chat.note.value = StoryKit.why(r); break }
                            }
                            if (ok) m.chat.note.value = t("Added to the story ✓", "Imeongezwa kwenye hadithi ✓")
                        }
                    }
                }.heightIn(min = 52.dp).padding(6.dp).testTag("story_row_$k"), contentAlignment = Alignment.Center) {
                    FitWords(l, CwType.meta(FontWeight.ExtraBold), Cw2.link(), Modifier.fillMaxWidth(), Alignment.Center, 2)
                }
            }
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Checkbox) { kids = !kids }.heightIn(min = 44.dp).testTag("story_kids"), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(kids, { kids = it }, colors = CheckboxDefaults.colors(checkedColor = C.blue))
            Text(t("Children can be seen", "Watoto wanaonekana"), style = CwType.name(FontWeight.SemiBold), color = C.text)
        }
        Text(t("Not now", "Si sasa"), style = CwType.name(FontWeight.ExtraBold), color = C.ink, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { hide { } }.heightIn(min = 48.dp).padding(vertical = 13.dp).testTag("story_no"))
    }
}

/** B14: a message waiting on this phone: "Waiting for signal…" with Retry; refused: "Not sent · Tap to retry". */
@Composable
internal fun SendState(m: AppModel, msg: Msg) {
    if (!msg.queued && msg.status != "failed") return
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(msg.key) { while (true) { kotlinx.coroutines.delay(2_000); tick = System.currentTimeMillis() } }
    val online by m.chat.online.collectAsState()
    if (msg.status == "failed" || msg.failed != null) {
        Row(Modifier.padding(top = 4.dp).clickable(role = Role.Button) { m.chat.retry(msg) }.heightIn(min = 32.dp).testTag("not_sent"), verticalAlignment = Alignment.CenterVertically) {
            Text(t("Not sent · Tap to retry", "Haijatumwa · Gusa kujaribu tena"), style = CwType.small(FontWeight.Bold), color = C.bad)
        }
        return
    }
    if (online && tick - msg.at < 8_000) return
    Row(Modifier.padding(top = 4.dp).testTag("waiting_signal"), verticalAlignment = Alignment.CenterVertically) {
        Text(t("Waiting for signal…", "Inasubiri mtandao…"), style = CwType.small(FontWeight.SemiBold), color = C.muted)
        Spacer(Modifier.width(8.dp))
        Text(t("Retry", "Jaribu tena"), style = CwType.small(FontWeight.ExtraBold), color = C.ink, maxLines = 1, softWrap = false,
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (C.dark) Color(0x1FFFFFFF) else Color(0x1704263F)).clickable(role = Role.Button) { m.chat.retry(msg) }.heightIn(min = 32.dp).padding(horizontal = 12.dp, vertical = 8.dp).testTag("retry"))
    }
}

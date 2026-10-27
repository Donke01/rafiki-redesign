package org.rafikioutreach.pin.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.ChatHub
import org.rafikioutreach.pin.data.ChatRow
import org.rafikioutreach.pin.data.chatPreview
import org.rafikioutreach.pin.data.preview
import org.rafikioutreach.pin.t

/**
 * Chat (feat-chata): every conversation in one list, newest at the top, pinned ones first. Each row: the picture
 * (with the green dot when they're online), the title, the last message ("You: …", "🎤 Voice message 0:12", "📷 Photo"),
 * the time, the unread count and an @ when you were mentioned. Swipe left to archive (Undo), right to mute or unmute;
 * hold a row for pin, mute, archive and mark as unread. Search looks through titles and the messages themselves.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatListScreen(m: AppModel, open: (String) -> Unit, newChat: () -> Unit, archived: Boolean = false, openArchived: () -> Unit = {}, openStarred: () -> Unit = {}) {
    val hub = m.chat
    LaunchedEffect(Unit) { hub.start() }
    val rows by hub.rows.collectAsState()
    val presence by hub.presence.collectAsState()
    val typing by hub.typing.collectAsState()
    val off by hub.off.collectAsState()
    val ready by hub.ready.collectAsState()
    var q by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    BackStep(searching || q.isNotEmpty()) { q = ""; searching = false }
    var menu by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf<ChatRow?>(null) }
    val undo = remember { UndoState() }
    val hp = rememberHaptics()
    val me = hub.me
    val list = hub.sorted(rows.filter { it.archived == archived })
    val nArchived = rows.count { it.archived }
    val state = rememberLazyListState()
    ShowInBar(m, if (archived) t("Archived", "Zilizohifadhiwa") else t("Chat", "Gumzo"), state.firstVisibleItemIndex > 0)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag(if (archived) "chat_archived" else "chat_list"), state, contentPadding = PaddingValues(bottom = 96.dp)) {
            item(key = "head") {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (archived) t("Archived", "Zilizohifadhiwa") else t("Chat", "Gumzo"), style = MaterialTheme.typography.headlineMedium, color = C.ink, modifier = Modifier.weight(1f).testTag("chat_title"))
                    if (!archived) Box {
                        Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) { menu = true }.semantics { contentDescription = t("More", "Zaidi") }.testTag("chat_menu"), contentAlignment = Alignment.Center) {
                            Icon(Ic.get("more"), null, tint = C.ink, modifier = Modifier.size(22.dp))
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text(t("New group", "Kikundi kipya")) }, { menu = false; newChat() }, leadingIcon = { Icon(ChatIc.get("group"), null, tint = C.blue) }, modifier = Modifier.testTag("menu_newgroup"))
                            DropdownMenuItem({ Text(t("Starred messages", "Ujumbe wenye nyota")) }, { menu = false; openStarred() }, leadingIcon = { Icon(ChatIc.get("star"), null, tint = C.blue) }, modifier = Modifier.testTag("menu_starred"))
                            DropdownMenuItem({ Text(t("Archived", "Zilizohifadhiwa")) }, { menu = false; openArchived() }, leadingIcon = { Icon(ChatIc.get("archive"), null, tint = C.blue) }, modifier = Modifier.testTag("menu_archived"))
                        }
                    }
                }
            }
            item(key = "search") { ChatSearchField(q, { q = it; searching = true }) }
            if (off) { item(key = "off") { ChatOff(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), m.chat.offNeed) }; return@LazyColumn }
            if (q.isNotBlank()) { chatSearch(this, m, q, rows, open); return@LazyColumn }
            if (!archived && nArchived > 0) item(key = "arch") {
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { openArchived() }.heightIn(min = 52.dp).padding(horizontal = 20.dp).testTag("chat_archived_row"), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(52.dp), contentAlignment = Alignment.Center) { Icon(ChatIc.get("archive"), null, tint = C.muted, modifier = Modifier.size(22.dp)) }
                    Text(t("Archived", "Zilizohifadhiwa"), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.weight(1f).padding(start = 12.dp))
                    Text("$nArchived", style = MaterialTheme.typography.labelMedium, color = C.muted)
                }
            }
            val pinned = list.filter { it.pinned }
            if (pinned.isNotEmpty()) item(key = "lp") { ChatLabel(t("PINNED", "ZILIZOBANDIKWA"), "chat_pinned_label") }
            items(pinned, key = { "p" + it.id }) { r -> ChatRowSwipe(m, r, me, presence, typing, undo, { open(r.id) }) { hp.long(); held = r } }
            if (pinned.isNotEmpty() && list.size > pinned.size) item(key = "la") { ChatLabel(t("ALL CHATS", "MAGUMZO YOTE"), "chat_all_label") }
            items(list.filter { !it.pinned }, key = { it.id }) { r -> ChatRowSwipe(m, r, me, presence, typing, undo, { open(r.id) }) { hp.long(); held = r } }
            if (list.isEmpty() && ready) item(key = "empty") {
                EmptyDrop(if (archived) t("No archived chats.", "Hakuna magumzo yaliyohifadhiwa.") else t("No chats yet. Start one with the button below, or from anyone’s contact.", "Bado hakuna magumzo. Anzisha moja kwa kitufe kilicho chini, au kutoka kwa mawasiliano ya mtu yeyote."), Modifier.padding(horizontal = 24.dp))
            }
            if (!ready) item(key = "loading") { Text(t("Loading…", "Inapakia…"), color = C.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp)) }
        }
        // New chat
        if (!archived && !off) Box(Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 18.dp).size(58.dp).shadow(10.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(C.primary)
            .clickable(role = Role.Button) { hp.tap(); newChat() }.semantics { contentDescription = t("New chat", "Gumzo jipya") }.testTag("chat_new"), contentAlignment = Alignment.Center) {
            Icon(DqmIc.get("edit"), null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        UndoSnack(undo, "chat_undo")
    }
    held?.let { r -> ChatRowSheet(m, r, { held = null }, undo) }
}

@Composable
internal fun ChatLabel(text: String, tag: String) =
    Text(text, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp, fontWeight = FontWeight.ExtraBold), color = C.muted, modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp).testTag(tag))

@Composable
internal fun ChatSearchField(q: String, set: (String) -> Unit, hint: String = t("Search chats and messages", "Tafuta magumzo na ujumbe"), tag: String = "chat_search") {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().heightIn(min = 46.dp).clip(RoundedCornerShape(16.dp)).background(if (C.glassy) C.glass2 else C.card)
        .border(1.dp, if (C.glassy) C.glassEdge else C.line, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Ic.get("search"), null, tint = C.muted, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (q.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyMedium, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(q, set, Modifier.fillMaxWidth().testTag(tag), singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = C.text), cursorBrush = SolidColor(C.blue))
        }
        if (q.isNotEmpty()) Box(Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button) { set("") }.semantics { contentDescription = t("Clear", "Futa") }, contentAlignment = Alignment.Center) {
            Icon(Ic.get("x"), null, tint = C.muted, modifier = Modifier.size(16.dp))
        }
    }
}

/** Search: chats by title or member, and messages (the server's search; what's on the phone without signal). */
private fun chatSearch(scope: androidx.compose.foundation.lazy.LazyListScope, m: AppModel, q: String, rows: List<ChatRow>, open: (String) -> Unit) {
    val words = q.trim().lowercase()
    val chats = rows.filter { r -> r.title.lowercase().contains(words) || r.members.any { it.lowercase().contains(words) } }
    scope.item(key = "s_chats") { if (chats.isNotEmpty()) ChatLabel(t("CHATS", "MAGUMZO"), "search_chats_label") }
    scope.items(chats, key = { "s" + it.id }) { r -> ChatRowPlain(m, r, m.chat.me, m.chat.presence.collectAsState().value, emptyMap(), { open(r.id) }, null) }
    scope.item(key = "s_msgs") { SearchHits(m, q, rows, open) }
}

@Composable
private fun SearchHits(m: AppModel, q: String, rows: List<ChatRow>, open: (String) -> Unit) {
    var hits by remember { mutableStateOf<List<ChatHub.Hit>?>(null) }
    LaunchedEffect(q) {
        delay(300)
        hits = m.chat.search(q) ?: run {
            // no signal: the messages kept on this phone
            val w = q.trim().lowercase()
            rows.flatMap { r -> m.chat.cache.loadMsgs(r.id).filter { it.text.lowercase().contains(w) }.map { ChatHub.Hit(r.id, it.id, it.from, it.at, it.text) } }.sortedByDescending { it.at }.take(30)
        }
    }
    val h = hits ?: return
    Column {
        if (h.isNotEmpty()) ChatLabel(t("MESSAGES", "UJUMBE"), "search_msgs_label")
        h.forEach { hit ->
            val r = rows.firstOrNull { it.id == hit.c }
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { ChatNav.focus = hit.id; open(hit.c) }.padding(horizontal = 20.dp, vertical = 10.dp).testTag("hit_${hit.id}"), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row { Text(r?.title ?: hit.c, style = MaterialTheme.typography.titleSmall, color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)); Text(chatWhen(hit.at), style = MaterialTheme.typography.labelSmall, color = C.muted) }
                    Text((if (hit.from.equals(m.chat.me, true)) t("You", "Wewe") else hit.from.substringBefore(' ')) + ": " + hit.snippet, style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (h.isEmpty()) Text(t("No messages match “$q”.", "Hakuna ujumbe unaolingana na “$q”."), style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.padding(20.dp).testTag("search_none"))
    }
}

/** A row that swipes: left to archive (or unarchive), right to mute or unmute. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatRowSwipe(m: AppModel, r: ChatRow, me: String, presence: Map<String, org.rafikioutreach.pin.data.Presence>, typing: Map<String, Map<String, Pair<String, Long>>>, undo: UndoState, open: () -> Unit, long: () -> Unit) {
    val hp = rememberHaptics()
    val st = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        when (v) {
            SwipeToDismissBoxValue.EndToStart -> { hp.ok(); m.chat.archive(r.id, !r.archived)
                undo.show(if (r.archived) t("Chat unarchived", "Gumzo limerudishwa") else t("Chat archived", "Gumzo limehifadhiwa")) { m.chat.archive(r.id, r.archived) } }
            SwipeToDismissBoxValue.StartToEnd -> { hp.ok(); val was = r.mutedUntil; if (r.muted()) m.chat.mute(r.id, 0) else m.chat.mute(r.id, 8)
                undo.show(if (r.muted()) t("Notifications on", "Arifa zimewashwa") else t("Muted for 8 hours", "Imenyamazishwa kwa saa 8")) { m.chat.mute(r.id, if (was == ChatRow.ALWAYS) -1 else if (was > 0) ((was - System.currentTimeMillis()) / 3_600_000L).toInt().coerceAtLeast(1) else 0) } }
            else -> {}
        }
        false
    }, positionalThreshold = { it * 0.35f })
    SwipeToDismissBox(st, backgroundContent = {
        val left = st.dismissDirection == SwipeToDismissBoxValue.StartToEnd
        Row(Modifier.fillMaxSize().background(if (left) C.hue("slate").bg else C.hue("green").bg).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (left) Arrangement.Start else Arrangement.End) {
            Icon(ChatIc.get(if (left) "mute" else "archive"), null, tint = if (left) C.hue("slate").fg else C.hue("green").fg, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (left) (if (r.muted()) t("Unmute", "Washa arifa") else t("Mute", "Nyamazisha")) else (if (r.archived) t("Unarchive", "Rudisha") else t("Archive", "Hifadhi")),
                style = MaterialTheme.typography.labelLarge, color = if (left) C.hue("slate").fg else C.hue("green").fg, maxLines = 1, softWrap = false)
        }
    }) { ChatRowPlain(m, r, me, presence, typing, open, long) }
}

/** One row of the chat list. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatRowPlain(m: AppModel, r: ChatRow, me: String, presence: Map<String, org.rafikioutreach.pin.data.Presence>, typing: Map<String, Map<String, Pair<String, Long>>>, open: () -> Unit, long: (() -> Unit)?) {
    val other = r.other(me)
    val online = r.kind == "dm" && presence[other]?.online == true
    val muted = r.muted()
    val typed = remember(typing, r.id) { m.chat.typingText(r.id, r.isGroup) }
    val bg = if (C.glassy) (if (C.dark) Color(0xFF111A21) else Color(0xFFF7FAFC)) else C.card
    Row(Modifier.fillMaxWidth().background(bg).combinedClickable(role = Role.Button, onClick = open, onLongClick = long).heightIn(min = 74.dp).padding(horizontal = 16.dp, vertical = 10.dp).testTag("chatrow_${r.id}"),
        verticalAlignment = Alignment.CenterVertically) {
        ChatAvatar(r, me, 52.dp, online, m = m)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.title.ifBlank { other }, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp), color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (r.kind == "project") { Spacer(Modifier.width(6.dp)); Icon(Ic.get("drop"), null, tint = C.hue("blue").fg, modifier = Modifier.size(14.dp)) }
                }
                Text(chatWhen(r.last?.at ?: 0), style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (r.unread > 0) FontWeight.ExtraBold else FontWeight.SemiBold),
                    color = if (r.unread > 0 && !muted) ChatLook.online else C.muted, maxLines = 1, softWrap = false, modifier = Modifier.testTag("rowtime"))
            }
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                if (typed != null) Text(typed, style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic, fontWeight = FontWeight.SemiBold), color = ChatLook.online, maxLines = 1, modifier = Modifier.weight(1f).testTag("row_typing"))
                else {
                    val l = r.last
                    val who = when { l == null -> ""; l.from.equals(me, true) -> t("You: ", "Wewe: "); r.isGroup && l.from.isNotBlank() && l.kind != "sys" -> l.from.substringBefore(' ') + ": "; else -> "" }
                    Text(if (l == null) t("No messages yet", "Bado hakuna ujumbe") else if (l.kind == "sys" || l.sys.isNotBlank()) (if (org.rafikioutreach.pin.Lang.sw || l.text.isBlank()) sysText(org.rafikioutreach.pin.data.SysNote(l.sys, "", "", "", l.from)) else l.text) else who + chatPreview(l.kind, l.text, l.dur), style = MaterialTheme.typography.bodySmall, color = C.muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("rowprev"))
                }
                if (muted) { Spacer(Modifier.width(4.dp)); Icon(ChatIc.get("mute"), t("Muted", "Imenyamazishwa"), tint = C.muted, modifier = Modifier.size(16.dp).testTag("row_muted")) }
                if (r.pinned) { Spacer(Modifier.width(4.dp)); Icon(Ic.get("pin"), t("Pinned", "Imebandikwa"), tint = C.muted, modifier = Modifier.size(16.dp)) }
                if (r.mention && r.unread > 0) { Spacer(Modifier.width(5.dp)); Box(Modifier.size(20.dp).clip(CircleShape).background(ChatLook.online).testTag("row_mention"), contentAlignment = Alignment.Center) { Text("@", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold) } }
                if (r.unread > 0) { Spacer(Modifier.width(5.dp)); Text(if (r.unread > 99) "99+" else "${r.unread}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, softWrap = false,
                    modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(if (muted) C.muted else ChatLook.online).widthIn(min = 20.dp).padding(horizontal = 6.dp, vertical = 2.dp).testTag("row_unread"),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
            }
        }
    }
}

/** Hold a row: pin, mute, archive, mark as unread. */
@Composable
private fun ChatRowSheet(m: AppModel, r: ChatRow, close: () -> Unit, undo: UndoState) {
    var muting by remember { mutableStateOf(false) }
    TeamSubSheet("chatrow_sheet", close) { hide ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChatAvatar(r, m.chat.me, 44.dp, m = m); Spacer(Modifier.width(12.dp))
            Text(r.title.ifBlank { r.other(m.chat.me) }, style = MaterialTheme.typography.titleLarge, color = C.ink, maxLines = 2)
        }
        Spacer(Modifier.height(10.dp))
        SheetRow("cr_pin", "pin", if (r.pinned) t("Unpin", "Ondoa kibandiko") else t("Pin to the top", "Bandika juu")) { hide { m.chat.pin(r.id, !r.pinned) } }
        SheetRow("cr_mute", "mute", if (r.muted()) t("Unmute", "Washa arifa") else t("Mute notifications", "Nyamazisha arifa")) { if (r.muted()) hide { m.chat.mute(r.id, 0) } else muting = true }
        SheetRow("cr_archive", "archive", if (r.archived) t("Unarchive", "Rudisha") else t("Archive", "Hifadhi")) {
            hide { m.chat.archive(r.id, !r.archived); undo.show(if (r.archived) t("Chat unarchived", "Gumzo limerudishwa") else t("Chat archived", "Gumzo limehifadhiwa")) { m.chat.archive(r.id, r.archived) } }
        }
        if (r.unread == 0) SheetRow("cr_unread", "chat", t("Mark as unread", "Weka kama haijasomwa")) { hide { m.chat.markUnread(r.id) } }
    }
    if (muting) MuteSheet(m, r.id, { muting = false; close() })
}

/** Mute for 8 hours, a week or always. */
@Composable
internal fun MuteSheet(m: AppModel, c: String, close: () -> Unit) {
    TeamSubSheet("mute_sheet", close) { hide ->
        Text(t("Mute notifications", "Nyamazisha arifa"), style = MaterialTheme.typography.titleLarge, color = C.ink)
        Text(t("You’ll still see new messages in the app.", "Bado utaona ujumbe mpya ndani ya programu."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        SheetRow("mute_8", "clock", t("8 hours", "Saa 8")) { hide { m.chat.mute(c, 8) } }
        SheetRow("mute_week", "cal", t("1 week", "Wiki 1")) { hide { m.chat.mute(c, 168) } }
        SheetRow("mute_always", "mute", t("Always", "Daima")) { hide { m.chat.mute(c, -1) } }
    }
}

/** A plain action row in a sheet: icon and label. */
@Composable
internal fun SheetRow(tag: String, icon: String, label: String, red: Boolean = false, value: String = "", onClick: () -> Unit) {
    val hp = rememberHaptics()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button) { hp.tap(); onClick() }.heightIn(min = 52.dp).padding(horizontal = 6.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(if (red) C.badBg else C.hue("blue").bg), contentAlignment = Alignment.Center) {
            Icon(ChatIc.get(icon), null, tint = if (red) C.bad else C.hue("blue").fg, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = if (red) C.bad else C.ink)
            if (value.isNotBlank()) Text(value, style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 2)
        }
    }
}

/** Messages starred in any chat; a tap opens the chat at that message. */
@Composable
fun StarredScreen(m: AppModel, open: (String) -> Unit) {
    var list by remember { mutableStateOf<List<org.rafikioutreach.pin.data.Msg>?>(null) }
    val tick by m.chat.tick.collectAsState()
    LaunchedEffect(tick) { list = m.chat.starred() ?: emptyList() }
    val rows by m.chat.rows.collectAsState()
    LazyColumn(Modifier.fillMaxSize().testTag("chat_starred"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(t("Starred messages", "Ujumbe wenye nyota"), style = MaterialTheme.typography.headlineMedium, color = C.ink) }
        val l = list
        when {
            l == null -> item { Text(t("Loading…", "Inapakia…"), color = C.muted) }
            l.isEmpty() -> item { EmptyDrop(t("No starred messages. Hold a message and tap Star to keep it here.", "Hakuna ujumbe wenye nyota. Shikilia ujumbe na uguse Nyota kuuweka hapa.")) }
            else -> items(l, key = { it.id }) { msg ->
                Card(Modifier.clickable(role = Role.Button) { ChatNav.focus = msg.id; open(msg.c) }.testTag("starred_${msg.id}"), pad = 14.dp) {
                    Row { Text((if (msg.from.equals(m.chat.me, true)) t("You", "Wewe") else msg.from) + " · " + (rows.firstOrNull { it.id == msg.c }?.title ?: ""), style = MaterialTheme.typography.labelMedium, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(chatWhen(msg.at), style = MaterialTheme.typography.labelSmall, color = C.muted) }
                    Text(msg.preview(), style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.padding(top = 4.dp), maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

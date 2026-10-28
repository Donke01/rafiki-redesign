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
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.text.withStyle
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
fun ChatListScreen(m: AppModel, open: (String) -> Unit, newChat: () -> Unit, archived: Boolean = false, openArchived: () -> Unit = {}, openStarred: () -> Unit = {}, openSettings: () -> Unit = {}, back: () -> Unit = {},
                   openMe: () -> Unit = {}, openNotes: () -> Unit = {}) {
    val hub = m.chat
    ChatDrafts.init(androidx.compose.ui.platform.LocalContext.current)
    LaunchedEffect(Unit) { hub.start() }
    val rows by hub.rows.collectAsState()
    val presence by hub.presence.collectAsState()
    val typing by hub.typing.collectAsState()
    val off by hub.off.collectAsState()
    val ready by hub.ready.collectAsState()
    var q by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    BackStep(searching || q.isNotEmpty()) { q = ""; searching = false }
    var held by remember { mutableStateOf<ChatRow?>(null) }
    val undo = remember { UndoState() }
    val hp = rememberHaptics()
    val me = hub.me
    val scope = rememberScope()
    val ownTop = !archived && chatListOwnsTop()
    val chip = if (archived) "all" else ChatChip.now
    // Announcements first, then pinned chats, then the rest newest first; the chip picks which; Archived is a row at the end
    val live = hub.sorted(rows.filter { if (archived) it.archived else !it.archived || it.announce })
    val shown = live.filter { ChatChip.ok(it, chip) }
    val nArchived = rows.count { it.archived && !it.announce }
    val state = rememberLazyListState()
    ShowInBar(m, if (archived) t("Archived", "Zilizohifadhiwa") else t("Chats", "Gumzo"), !ownTop && state.firstVisibleItemIndex > 0)

    Box(Modifier.fillMaxSize().background(if (ownTop) C.card else Color.Transparent)) {
        LazyColumn(Modifier.fillMaxSize().testTag(if (archived) "chat_archived" else "chat_list"), state, contentPadding = PaddingValues(bottom = 96.dp)) {
            item(key = "head") {
                if (archived) Text(t("Archived", "Zilizohifadhiwa"), style = CwType.title(), color = C.ink, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp).testTag("chat_title"))
                else ChatHead(m, searching, q, { q = it }, { searching = true }, { q = ""; searching = false }, {
                    ChatMoreMenu(m, ownTop, openSettings, openStarred, openArchived, newChat, openNotes) {
                        scope.launch { hub.refreshList(); m.refresh(quiet = true) }
                        android.widget.Toast.makeText(m.getApplication(), t("Updated", "Imesasishwa"), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }, openMe, ownTop)
            }
            if (off) { item(key = "off") { ChatOff(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), m.chat.offNeed) }; return@LazyColumn }
            if (archived) item(key = "search") { ChatSearchField(q, { q = it; searching = true }) }
            if (q.isNotBlank()) { chatSearch(this, m, q, rows, open) { q = ""; searching = false }; return@LazyColumn }
            if (searching) return@LazyColumn
            if (!archived) {
                item(key = "chips") { ChatChips(rows) }
                item(key = "field") { ChatFieldRow(m, presence) }
                item(key = "alerts") { ChatAlertPrompt(m) }
            }
            items(shown, key = { it.id }) { r -> ChatRowSwipe(m, r, me, presence, typing, undo, { open(r.id) }) { hp.long(); held = r } }
            if (!archived && live.isNotEmpty() && shown.isEmpty()) item(key = "chipnone") {
                ChatEmpty(ChatChip.empty(chip), "chat_none_chip", t("Show all chats", "Onyesha gumzo zote"), onAction = { ChatChip.now = "all" })
            }
            if (!archived && nArchived > 0 && chip == "all") item(key = "arch") { ChatArchivedRow(nArchived, openArchived) }
            if (live.isEmpty() && ready) item(key = "empty") {
                if (archived) ChatEmpty(t("No archived chats. Swipe a chat left to tidy it away here.", "Hakuna magumzo yaliyohifadhiwa. Telezesha gumzo kushoto kuliweka hapa."), "chat_none_archived", t("Back to chats", "Rudi kwenye magumzo"), onAction = back)
                else ChatEmpty(t("No chats yet. Say hello to the team 👋", "Bado hakuna magumzo. Isalimie timu 👋"), "chat_none", t("New chat", "Gumzo jipya"), onAction = newChat)
            }
            if (!ready) item(key = "loading") { Text(t("Loading…", "Inapakia…"), color = C.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp)) }
        }
        // New chat: a green rounded square above the bottom bar (the list has room under its last row)
        if (!archived && !off && !searching) ChatFab(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp)) { newChat() }
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
private fun chatSearch(scope: androidx.compose.foundation.lazy.LazyListScope, m: AppModel, q: String, rows: List<ChatRow>, open: (String) -> Unit, clear: () -> Unit) {
    val words = q.trim().lowercase()
    val chats = rows.filter { r -> r.title.lowercase().contains(words) || r.members.any { it.lowercase().contains(words) } }
    scope.item(key = "s_chats") { if (chats.isNotEmpty()) ChatLabel(t("CHATS", "MAGUMZO"), "search_chats_label") }
    scope.items(chats, key = { "s" + it.id }) { r -> ChatRowPlain(m, r, m.chat.me, m.chat.presence.collectAsState().value, emptyMap(), { open(r.id) }, null) }
    scope.item(key = "s_msgs") { SearchHits(m, q, rows, open, chats.isEmpty(), clear) }
}

@Composable
private fun SearchHits(m: AppModel, q: String, rows: List<ChatRow>, open: (String) -> Unit, noChats: Boolean = false, clear: () -> Unit = {}) {
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
        if (h.isEmpty() && noChats) ChatEmpty(t("Nothing matches “$q”. Try another word.", "Hakuna kinacholingana na “$q”. Jaribu neno lingine."), "search_none", t("Clear search", "Futa utafutaji"), onAction = clear)
        else if (h.isEmpty()) Text(t("No messages match “$q”.", "Hakuna ujumbe unaolingana na “$q”."), style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.padding(20.dp).testTag("search_none_msgs"))
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

/** One row of the chat list (chat 2, list.png): 74 dp, a 52 dp picture, a thin divider under the words; unread = a bold
 *  name, a green time and count; a light-green "@" when mentioned; "typing…" in green; muted and pinned small grey icons;
 *  ticks before my own last message. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatRowPlain(m: AppModel, r: ChatRow, me: String, presence: Map<String, org.rafikioutreach.pin.data.Presence>, typing: Map<String, Map<String, Pair<String, Long>>>, open: () -> Unit, long: (() -> Unit)?) {
    val other = r.other(me)
    val online = r.kind == "dm" && presence[other]?.online == true
    val muted = r.muted()
    val typed = remember(typing, r.id) { m.chat.typingText(r.id, r.isGroup) }
    val un = r.unread > 0
    val line = if (C === Sun) Color.Black else C.line
    val lineW = if (C === Sun) 2.dp else 1.dp
    Row(Modifier.fillMaxWidth().background(C.card).combinedClickable(role = Role.Button, onClick = open, onLongClick = long).padding(start = 16.dp, end = 16.dp).testTag("chatrow_${r.id}"),
        verticalAlignment = Alignment.CenterVertically) {
        ChatAvatar(r, me, 52.dp, online, m = m)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f).heightIn(min = 74.dp).drawBehind { val w = lineW.toPx(); drawLine(line, androidx.compose.ui.geometry.Offset(0f, size.height - w / 2), androidx.compose.ui.geometry.Offset(size.width, size.height - w / 2), w) }
            .padding(vertical = 8.dp), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(chatTitle(r, me), style = CwType.name(if (un) FontWeight.ExtraBold else FontWeight.Bold), color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp))
                Text(chatWhen(r.last?.at ?: 0), style = CwType.meta(if (un && !muted) FontWeight.Bold else FontWeight.Medium),
                    color = if (un && !muted) Cw2.un() else C.muted, maxLines = 1, softWrap = false, modifier = Modifier.testTag("rowtime"))
            }
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                // the half-written message, while it waits in the box (not for the chat that's open)
                val draft = remember(ChatDrafts.version, r.id) { ChatDrafts.peek(r.id) }
                val pv = CwType.msg(if (un) FontWeight.Medium else FontWeight.Normal)
                if (typed != null) Text(typed, style = CwType.msg(FontWeight.SemiBold), color = Cw2.un(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("row_typing"))
                else if (draft.isNotBlank() && m.chat.openChat != r.id) Text(androidx.compose.ui.text.buildAnnotatedString {
                    withStyle(androidx.compose.ui.text.SpanStyle(color = Cw2.un(), fontWeight = FontWeight.Bold)) { append(t("Draft: ", "Rasimu: ")) }
                    append(draft.replace('\n', ' '))
                }, style = pv, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("row_draft"))
                else {
                    val l = r.last
                    val mine = l != null && l.from.equals(me, true) && l.kind != "sys" && l.sys.isBlank()
                    if (mine) Ticks(if (r.seen.isNotEmpty()) "read" else "sent", Modifier.padding(end = 4.dp).testTag("row_ticks"), tint = if (r.seen.isNotEmpty()) cwRead() else ChatLook.tick())
                    val who = when { l == null -> ""; mine -> t("You: ", "Wewe: "); r.isGroup && l.from.isNotBlank() && l.kind != "sys" -> l.from.substringBefore(' ') + ": "; else -> "" }
                    Text(if (l == null) (if (r.kind == "project") t("Project thread", "Mazungumzo ya mradi") else if (r.announce) t("News from the office", "Habari kutoka ofisini") else t("No messages yet", "Bado hakuna ujumbe"))
                        else if (l.kind == "sys" || l.sys.isNotBlank()) (if (org.rafikioutreach.pin.Lang.sw || l.text.isBlank()) sysText(org.rafikioutreach.pin.data.SysNote(l.sys, "", "", "", l.from)) else l.text) else who + chatPreview(l.kind, l.text, l.dur),
                        style = pv, color = if (un) C.text else C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("rowprev"))
                }
                if (r.mention && un) { Spacer(Modifier.width(6.dp)); Text("@", color = Cw2.un(), style = CwType.meta(FontWeight.ExtraBold), textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(99.dp)).then(if (C === Sun) Modifier.border(2.dp, Color.Black, RoundedCornerShape(99.dp)) else Modifier).background(Cw2.at()).widthIn(min = 30.dp).padding(horizontal = 7.dp, vertical = 2.dp).testTag("row_mention")) }
                if (muted) { Spacer(Modifier.width(5.dp)); Icon(ChatIc.get("mute"), t("Muted", "Imenyamazishwa"), tint = C.muted, modifier = Modifier.size(15.dp).testTag("row_muted")) }
                if (r.pinned || r.announce) { Spacer(Modifier.width(5.dp)); Icon(ChatIc.get("pushpin"), t("Pinned", "Imebandikwa"), tint = C.muted, modifier = Modifier.size(15.dp).testTag("row_pinned")) }
                if (un) { Spacer(Modifier.width(6.dp)); Text(if (r.unread > 99) "99+" else "${r.unread}", color = Color.White, style = CwType.small(FontWeight.ExtraBold), maxLines = 1, softWrap = false,
                    modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(if (muted) C.muted else Cw2.un()).widthIn(min = 22.dp).padding(horizontal = 6.dp, vertical = 3.dp).testTag("row_unread"),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
            }
        }
    }
}

/** The blue of read ticks (the web's --cw-read). */
@Composable
internal fun cwRead() = if (C === Sun) Color(0xFF0050A0) else if (C.dark) Color(0xFF5BC0FF) else Color(0xFF1D8FE0)

/** Hold a row: pin, mute, archive, mark as unread. */
@Composable
private fun ChatRowSheet(m: AppModel, r: ChatRow, close: () -> Unit, undo: UndoState) {
    var muting by remember { mutableStateOf(false) }
    TeamSubSheet("chatrow_sheet", close) { hide ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChatAvatar(r, m.chat.me, 44.dp, m = m); Spacer(Modifier.width(12.dp))
            Text(chatTitle(r, m.chat.me), style = CwType.sheet(), color = C.ink, maxLines = 2)
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
        Text(t("Mute notifications", "Nyamazisha arifa"), style = CwType.sheet(), color = C.ink)
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
fun StarredScreen(m: AppModel, back: () -> Unit = {}, open: (String) -> Unit) {
    var list by remember { mutableStateOf<List<org.rafikioutreach.pin.data.Msg>?>(null) }
    val tick by m.chat.tick.collectAsState()
    LaunchedEffect(tick) { list = m.chat.starred() ?: emptyList() }
    val rows by m.chat.rows.collectAsState()
    LazyColumn(Modifier.fillMaxSize().testTag("chat_starred"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(t("Starred messages", "Ujumbe wenye nyota"), style = MaterialTheme.typography.headlineMedium, color = C.ink) }
        val l = list
        when {
            l == null -> item { Text(t("Loading…", "Inapakia…"), color = C.muted) }
            l.isEmpty() -> item { ChatEmpty(t("No starred messages. Hold a message and tap Star to keep it here.", "Hakuna ujumbe wenye nyota. Shikilia ujumbe na uguse Nyota kuuweka hapa."), "starred_none", t("Back to chats", "Rudi kwenye magumzo"), onAction = back) }
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

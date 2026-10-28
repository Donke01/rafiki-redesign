package org.rafikioutreach.pin.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.ChatMedia
import org.rafikioutreach.pin.data.ChatRow
import org.rafikioutreach.pin.data.Geo
import org.rafikioutreach.pin.data.Msg
import org.rafikioutreach.pin.data.emojiOnly
import org.rafikioutreach.pin.data.preview
import org.rafikioutreach.pin.t
import java.io.File
import java.time.Instant
import kotlin.math.roundToInt

/** What the message list shows, newest first (the list grows upwards from the composer). */
private sealed class Line(val key: String) {
    class Day(val label: String, k: String) : Line(k)
    class Unread(val n: Int) : Line("unread")
    class B(val msg: Msg, val first: Boolean, val last: Boolean) : Line(msg.key)
}

private fun lines(msgs: List<Msg>, unreadFrom: String?, unreadN: Int): List<Line> {
    val out = mutableListOf<Line>()
    var day = ""
    msgs.forEachIndexed { i, m ->
        val d = Instant.ofEpochMilli(m.at).atZone(Dates.zone()).toLocalDate().toString()
        if (d != day) { day = d; out += Line.Day(chatDay(m.at), "d_$d") }
        if (unreadFrom != null && m.id == unreadFrom && unreadN > 0) out += Line.Unread(unreadN)
        val prev = msgs.getOrNull(i - 1); val next = msgs.getOrNull(i + 1)
        fun same(a: Msg?, b: Msg) = a != null && a.sys == null && b.sys == null && a.kind != "sys" && b.kind != "sys" && a.from == b.from && kotlin.math.abs(a.at - b.at) < 5 * 60_000 &&
            Instant.ofEpochMilli(a.at).atZone(Dates.zone()).toLocalDate() == Instant.ofEpochMilli(b.at).atZone(Dates.zone()).toLocalDate()
        out += Line.B(m, !same(prev, m), !same(next, m))
    }
    return out.asReversed()
}

/** Is [me] @mentioned in this message? */
private fun mentionsMe(m: Msg, me: String) = m.mentions.any { it.equals(me, true) }

/**
 * One conversation (feat-chata, polished in chatpa). Bubbles left and right with a tail on the first of a run, in my
 * chosen colour; the time and ticks; typing dots; swipe right to reply, left to see the exact time; double-tap to react;
 * hold for reactions and actions; link, project and trip cards; date separators; the pinned messages' bar; "↓ 3 new"
 * and "Jump to @mention"; "Seen by" in groups; older messages load while scrolling up; the place is remembered per chat.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatRoom(m: AppModel, c: String, openInfo: () -> Unit, openChat: (String) -> Unit, openProject: (String, String) -> Unit, office: (String) -> Unit, back: (() -> Unit)? = null) {
    val hub = m.chat
    val rows by hub.rows.collectAsState()
    val row = rows.firstOrNull { it.id == c }
    val msgs by remember(c) { hub.msgs(c) }.collectAsState()
    val presence by hub.presence.collectAsState()
    val typingMap by hub.typing.collectAsState()
    val off by hub.off.collectAsState()
    hub.settingsTick.collectAsState().value
    val me = hub.me
    val scope = rememberScope()
    val hp = rememberHaptics()
    val motion = chatMotion()
    DisposableEffect(c) { hub.openChat = c; onDispose { if (hub.openChat == c) hub.openChat = null; VoicePlayer.stop(); hub.typing(c, null) } }
    var loaded by remember(c) { mutableStateOf(false) }
    LaunchedEffect(c) { hub.markSeen(c); hub.loadMsgs(c); loaded = true }
    LaunchedEffect(msgs.size) { hub.markSeen(c) }
    // "3 unread messages", as the chat was opened
    val unreadAtOpen = remember(c) { row?.unread ?: 0 }
    val unreadFrom = remember(c, msgs.isNotEmpty()) { if (unreadAtOpen > 0) msgs.filter { !it.from.equals(me, true) }.takeLast(unreadAtOpen).firstOrNull()?.id else null }
    val list = remember(msgs, unreadFrom) { lines(msgs, unreadFrom, unreadAtOpen) }
    // where this chat was left (kept while the app runs)
    val state = remember(c) { hub.scrollAt[c] }.let { s -> rememberLazyListState(s?.first ?: 0, s?.second ?: 0) }
    DisposableEffect(c) { onDispose { hub.scrollAt[c] = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset } }
    val group = row?.isGroup == true
    var reply by remember(c) { mutableStateOf<Msg?>(null) }
    var editing by remember(c) { mutableStateOf<Msg?>(null) }
    var held by remember { mutableStateOf<Msg?>(null) }
    var viewer by remember { mutableStateOf<Int?>(null) }
    var flash by remember { mutableStateOf<String?>(null) }
    var reactsOf by remember { mutableStateOf<Msg?>(null) }
    val mediaAll = remember(msgs) { msgs.filter { !it.deleted }.flatMap { msg -> msg.media.filter { it.type == "photo" || it.type == "video" }.map { msg to it } } }
    // messages that arrive while the chat is open slide in; the ones already there don't move
    val openedAt = remember(c) { System.currentTimeMillis() }
    val known = remember(c) { msgs.map { it.key }.toHashSet() }

    // scroll to a message (a tapped quote, a search hit, a starred or pinned message), loading older pages if needed
    fun jump(id: String) = scope.launch {
        var i = list.indexOfFirst { it is Line.B && it.msg.id == id }
        var tries = 0
        while (i < 0 && hub.hasMore(c) && tries < 10) { hub.loadOlder(c); tries++; delay(50); i = lines(hub.msgs(c).value, unreadFrom, unreadAtOpen).indexOfFirst { it is Line.B && it.msg.id == id } }
        if (i >= 0) { if (motion) state.animateScrollToItem(i) else state.scrollToItem(i); flash = id; delay(1600); flash = null }
    }
    val focus = ChatNav.focus
    LaunchedEffect(focus, msgs.isNotEmpty()) { if (focus != null && msgs.isNotEmpty()) { ChatNav.focus = null; jump(focus) } }

    // new messages: follow them when at the bottom; otherwise count them on the "↓ 3 new" chip
    val atBottom by remember { derivedStateOf { state.firstVisibleItemIndex <= 1 } }
    var awayAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(atBottom) { awayAt = if (atBottom) null else msgs.lastOrNull()?.at ?: 0L }
    val newer = awayAt?.let { a -> msgs.count { it.at > a && !it.from.equals(me, true) } } ?: 0
    val newest = list.firstOrNull()?.key
    LaunchedEffect(newest) { if (atBottom || msgs.lastOrNull()?.from?.equals(me, true) == true) { if (motion) state.animateScrollToItem(0) else state.scrollToItem(0) } }
    // older messages while scrolling up
    val nearTop by remember { derivedStateOf { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= state.layoutInfo.totalItemsCount - 3 } }
    var loadingOlder by remember { mutableStateOf(false) }
    LaunchedEffect(nearTop, msgs.size) { if (nearTop && !loadingOlder && hub.hasMore(c) && msgs.isNotEmpty()) { loadingOlder = true; hub.loadOlder(c); loadingOlder = false } }

    // the unread divider fades once it has scrolled past (out of sight above, after it was seen)
    val unreadIdx = list.indexOfFirst { it is Line.Unread }
    var dividerSeen by remember(c) { mutableStateOf(false) }
    var dividerGone by remember(c) { mutableStateOf(false) }
    val dividerVisible by remember { derivedStateOf { state.layoutInfo.visibleItemsInfo.any { it.key == "unread" } } }
    val lastVisible by remember { derivedStateOf { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 } }
    LaunchedEffect(dividerVisible, lastVisible, unreadIdx) {
        if (dividerVisible) dividerSeen = true
        else if (dividerSeen && unreadIdx > lastVisible) dividerGone = true
    }

    // @mentions of me I haven't seen yet: the unread ones, and the server's oldest unseen one
    val mentionLeft = remember(c) { mutableStateListOf<String>() }
    LaunchedEffect(c, msgs.isNotEmpty()) {
        if (msgs.isEmpty() || mentionLeft.isNotEmpty()) return@LaunchedEffect
        val from = unreadFrom?.let { u -> msgs.indexOfFirst { it.id == u } } ?: -1
        val ids = (if (from >= 0) msgs.drop(from) else emptyList()).filter { mentionsMe(it, me) }.map { it.id } + listOfNotNull(row?.mentionId?.takeIf { it.isNotBlank() })
        mentionLeft.addAll(ids.distinct())
    }
    val visibleKeys by remember { derivedStateOf { state.layoutInfo.visibleItemsInfo.map { it.key.toString() }.toSet() } }
    LaunchedEffect(visibleKeys, msgs) { if (mentionLeft.isNotEmpty()) mentionLeft.removeAll { id -> msgs.firstOrNull { it.id == id }?.key?.let { it in visibleKeys } == true } }

    val wall = row?.wallpaper.orEmpty()
    val dbl = hub.settings.doubleTap
    // chat 2: selecting (B10), finding (B13), the ⋯ sheets (B11, B12, B16), the story question after adding photos (B5)
    val sel = remember(c) { mutableStateListOf<String>() }
    var selecting by remember(c) { mutableStateOf(false) }
    BackStep(selecting) { selecting = false; sel.clear() }
    var finding by remember(c) { mutableStateOf(false) }
    var fq by remember(c) { mutableStateOf("") }
    var fAt by remember(c) { mutableIntStateOf(0) }
    BackStep(finding) { finding = false; fq = "" }
    var sheet by remember(c) { mutableStateOf<String?>(null) }
    var delMsgs by remember { mutableStateOf<List<Msg>?>(null) }
    var storyAsk by remember { mutableStateOf<Pair<String, List<File>>?>(null) }
    var infoOf by remember { mutableStateOf<Msg?>(null) }
    val officeAdmin = m.snap.collectAsState().value?.admin == true
    val canAdd = row != null && (row.admin(me) || officeAdmin)
    val found = remember(msgs, fq) { val w = fq.trim().lowercase(); if (w.isEmpty()) emptyList() else msgs.filter { !it.deleted && it.text.lowercase().contains(w) }.asReversed() }
    LaunchedEffect(found) { fAt = 0; found.firstOrNull()?.let { jump(it.id) } }
    val note by hub.note.collectAsState()
    val ctxNote = LocalContext.current
    LaunchedEffect(note) { note?.let { android.widget.Toast.makeText(ctxNote, it, android.widget.Toast.LENGTH_SHORT).show(); hub.note.value = null } }
    fun selected() = msgs.filter { it.key in sel }
    // RAFIKI Deep (web v75): a night header and project strip with a rounded bottom over the chat's sand
    val strip = row != null && row.kind == "project" && row.project.isNotBlank()
    Column(Modifier.fillMaxSize().then(if (Deep.on()) Modifier.background(if (wall.isBlank()) Deep.room() else C.bg) else Modifier).testTag("chat_room")) {
        NightBlock(round = if (strip) 22.dp else 0.dp) {
        when {
            selecting -> SelBar(sel.size, sel.size == 1 && selected().firstOrNull()?.let { !it.deleted && !it.queued } == true, { selecting = false; sel.clear() },
                fwd = { held = null; sheet = "fwd" }, star = { val l = selected(); scope.launch { l.filter { !it.queued && !it.deleted }.forEach { hub.star(it, !l.all { x -> x.starred }) } }; selecting = false; sel.clear() },
                del = { delMsgs = selected() })
            finding -> FindBar(fq, { fq = it }, found.size, fAt, up = { if (found.isNotEmpty()) { fAt = (fAt + 1) % found.size; jump(found[fAt].id) } },
                down = { if (found.isNotEmpty()) { fAt = (fAt - 1 + found.size) % found.size; jump(found[fAt].id) } }) { finding = false; fq = "" }
            else -> RoomHead(m, row, c, me, presence, typingMap, back, openInfo, { finding = true }) {
                RoomMenu(row, canAdd) { k ->
                    when (k) { "search" -> finding = true; "select" -> selecting = true; "info" -> openInfo(); else -> sheet = k }
                }
            }
        }
        if (row != null) ProjectStrip(m, row, openProject)
        }
        if (row != null) PinBar(row, msgs) { jump(it) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (wall.isBlank() && Deep.on()) SandWall(Modifier.matchParentSize()) else if (wall.isBlank() && C === Light) WarmWall(Modifier.matchParentSize()) else ChatWall(wall, Modifier.matchParentSize())
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val maxW = minOf(maxWidth * 0.8f, 560.dp)
                LazyColumn(Modifier.fillMaxSize().testTag("chat_msgs"), state, reverseLayout = true, contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 8.dp)) {
                    items(list, key = { it.key }, contentType = { it::class.simpleName }) { line ->
                        when (line) {
                            is Line.Day -> DayChip(line.label)
                            is Line.Unread -> {
                                val a by animateFloatAsState(if (dividerGone) 0f else 1f, tween(if (motion) 320 else 0), label = "unread")
                                // styled like the day chips, smaller and centred (B3)
                                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).graphicsLayer { alpha = a }, contentAlignment = Alignment.Center) {
                                    Text(if (line.n == 1) t("1 unread message", "Ujumbe 1 haujasomwa") else t("${line.n} unread messages", "Ujumbe ${line.n} haujasomwa"),
                                        style = CwType.small(FontWeight.Bold), color = if (C === Sun) Color.Black else cwTime(), textAlign = TextAlign.Center,
                                        modifier = Modifier.widthIn(max = 320.dp).shadow(1.dp, RoundedCornerShape(9.dp)).clip(RoundedCornerShape(9.dp)).background(if (C.dark) Color(0xFF1E2A36) else Color.White).padding(horizontal = 12.dp, vertical = 4.dp)
                                            .testTag(if (dividerGone) "unread_line_faded" else "unread_line"))
                                }
                            }
                            is Line.B -> {
                                val newestLine = list.firstOrNull { it is Line.B } === line
                                val fresh = line.msg.key !in known && line.msg.at > openedAt - 5_000
                                MsgLine(m, line.msg, row, me, group, line.first, line.last, maxW, flash == line.msg.id || (finding && found.getOrNull(fAt)?.id == line.msg.id), fresh, motion,
                                    seen = if (newestLine && group) row?.seen.orEmpty().filter { !it.equals(me, true) && !it.equals(line.msg.from, true) } else emptyList(),
                                    onReply = { hp.long(); reply = line.msg; editing = null },
                                    onLong = { hp.long(); if (selecting) { if (line.msg.key in sel) sel.remove(line.msg.key) else sel.add(line.msg.key) } else held = line.msg },
                                    selecting = selecting, selected = line.msg.key in sel, toggle = { if (line.msg.key in sel) sel.remove(line.msg.key) else sel.add(line.msg.key); if (sel.isEmpty()) selecting = false },
                                    story = { pid, files -> storyAsk = pid to files }, info = { infoOf = line.msg },
                                    onDouble = { if (!line.msg.queued && !line.msg.deleted && line.msg.sys == null) { hp.tap(); scope.launch { hub.react(line.msg, dbl) } } },
                                    openMedia = { md -> viewer = mediaAll.indexOfFirst { it.second == md && it.first.key == line.msg.key }.coerceAtLeast(0) },
                                    jump = { jump(it) }, openProject = openProject, reacts = { reactsOf = line.msg })
                            }
                        }
                    }
                    if (loadingOlder || (hub.hasMore(c) && msgs.size >= 50)) item(key = "older") { OlderSkeleton() }
                    if (msgs.isEmpty() && (loaded || !hub.hasMore(c))) item(key = "empty") { RoomEmpty(m, row, c, me) }
                }
            }
            // "Jump to @mention", above the "new" chip
            if (mentionLeft.isNotEmpty()) Box(Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = if (!atBottom) 66.dp else 12.dp)) {
                Box(Modifier.size(44.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(ChatLook.online).clickable(role = Role.Button) { mentionLeft.firstOrNull()?.let { id -> jump(id); mentionLeft.remove(id) } }
                    .semantics { contentDescription = t("Jump to @mention", "Nenda kwenye @kutajwa") }.testTag("jump_mention"), contentAlignment = Alignment.Center) {
                    Text("@", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            // back to the newest: "↓ 3 new" when messages came in meanwhile, otherwise a round arrow
            if (!atBottom) {
                if (newer > 0) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).shadow(6.dp, RoundedCornerShape(99.dp)).clip(RoundedCornerShape(99.dp)).background(ChatLook.online)
                    .clickable(role = Role.Button) { scope.launch { if (motion) state.animateScrollToItem(0) else state.scrollToItem(0) } }.padding(horizontal = 14.dp, vertical = 8.dp).testTag("new_chip"), verticalAlignment = Alignment.CenterVertically) {
                    Text("↓ " + if (newer == 1) t("1 new", "1 mpya") else t("$newer new", "$newer mpya"), color = Color.White, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold), maxLines = 1, softWrap = false, modifier = Modifier.testTag("jump_count"))
                }
                else Box(Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 12.dp).size(44.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(C.card).clickable(role = Role.Button) { scope.launch { if (motion) state.animateScrollToItem(0) else state.scrollToItem(0) } }
                    .semantics { contentDescription = t("Jump to the newest", "Nenda kwa mpya zaidi") }.testTag("jump_bottom"), contentAlignment = Alignment.Center) {
                    Icon(Ic.get("down"), null, tint = C.ink, modifier = Modifier.size(22.dp))
                }
            }
        }
        when {
            off -> ChatOff(Modifier.padding(12.dp), m.chat.offNeed)
            row != null && row.onlyAdmins && !row.admin(me) -> Text(if (row.announce) t("Only admins post announcements", "Wasimamizi pekee ndio hutuma matangazo") else t("Only admins can send messages", "Wasimamizi pekee wanaweza kutuma ujumbe"), style = MaterialTheme.typography.bodyMedium, color = C.muted, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().background(C.card).padding(18.dp).testTag("only_admins"))
            else -> Composer(m, c, row, reply, editing, { reply = null }, { editing = null }) { scope.launch { state.scrollToItem(0) } }
        }
    }
    held?.let { msg -> MsgSheet(m, msg, row, { held = null }, onReply = { reply = msg; editing = null }, onEdit = { editing = msg; reply = null }, openChat = openChat, openProject = openProject, office = office,
        onSelect = { held = null; selecting = true; sel.clear(); sel.add(msg.key) }, onDelete = { held = null; delMsgs = listOf(msg) }, story = { pid, files -> storyAsk = pid to files }) }
    delMsgs?.let { l -> DeleteSheet(m, row, l, { delMsgs = null }) { delMsgs = null; selecting = false; sel.clear() } }
    storyAsk?.let { (pid, files) -> StoryAskSheet(m, pid, files) { storyAsk = null } }
    infoOf?.let { msg -> InfoSheet(m, msg, row) { infoOf = null } }
    when (sheet) {
        "fwd" -> selected().firstOrNull()?.let { msg -> ForwardSheet(m, msg, { sheet = null; selecting = false; sel.clear() }) { openChat(it) } } ?: run { sheet = null }
        "clear", "delete" -> if (row != null) ClearSheet(m, row, sheet == "delete", { sheet = null }) { (back ?: {}).invoke() } else sheet = null
        "add" -> if (row != null) AddPeopleSheet(m, row, canAdd, { sheet = null }, office) else sheet = null
        "media" -> MediaFilesSheet(m, c, mediaAll.map { it.second }) { sheet = null }
        "date" -> JumpDateSheet({ sheet = null }) { day -> scope.launch {
            // load older pages until that day is on the phone, then go to its first message
            var tries = 0
            while (hub.msgs(c).value.firstOrNull()?.let { Instant.ofEpochMilli(it.at).atZone(Dates.zone()).toLocalDate() > day } == true && hub.hasMore(c) && tries < 20) { hub.loadOlder(c); tries++ }
            hub.msgs(c).value.firstOrNull { !Instant.ofEpochMilli(it.at).atZone(Dates.zone()).toLocalDate().isBefore(day) }?.let { jump(it.id) }
        } }
    }
    reactsOf?.let { msg -> ReactsSheet(m, msg) { reactsOf = null } }
    viewer?.let { i -> if (mediaAll.isNotEmpty()) MediaViewer(m, mediaAll, i) { viewer = null } else viewer = null }
}

/** A chat with no messages yet: the drop and "Say hello to the Kenya team 👋", with a button that does. */
@Composable
private fun RoomEmpty(m: AppModel, row: ChatRow?, c: String, me: String) {
    val canPost = row == null || !row.onlyAdmins || row.admin(me)
    val name = row?.let { chatTitle(it, me) }.orEmpty()
    val text = when {
        row?.announce == true -> if (canPost) t("No announcements yet. Write the first one for the whole team.", "Bado hakuna matangazo. Andika la kwanza kwa timu nzima.") else t("No announcements yet. The office posts news here.", "Bado hakuna matangazo. Ofisi huweka habari hapa.")
        row?.kind == "dm" -> t("Say hello to ${name.substringBefore(' ')} 👋", "Msalimie ${name.substringBefore(' ')} 👋")
        row?.kind == "team" -> t("Say hello to the $name 👋", "Isalimie $name 👋")
        name.isNotBlank() -> t("Say hello to $name 👋", "Salimia $name 👋")
        else -> t("No messages yet. Say hello!", "Bado hakuna ujumbe. Salimia!")
    }
    ChatEmpty(text, "chat_empty", if (canPost && row?.announce != true) t("Say hello 👋", "Salimia 👋") else null) { m.chat.send(c, "text", "👋") }
}

/** The chat's head: its picture, name and what's happening (online, last seen, typing dots, members). A tap opens the info page. */
@Composable
private fun ChatHead(m: AppModel, row: ChatRow?, c: String, me: String, presence: Map<String, org.rafikioutreach.pin.data.Presence>, typing: Map<String, Map<String, Pair<String, Long>>>, openInfo: () -> Unit) {
    val other = row?.other(me).orEmpty()
    val p = presence[other]
    val typed = remember(typing) { m.chat.typingText(c, row?.isGroup == true) }
    val status = when {
        typed != null -> typed
        row == null -> ""
        row.kind == "dm" -> if (p?.online == true) t("online", "yupo mtandaoni") else lastSeen(p?.seen ?: 0)
        row.kind == "project" -> t("Project thread", "Mazungumzo ya mradi") + " · " + t("${row.members.size} people", "watu ${row.members.size}")
        row.announce -> t("Everyone on the team · admins post", "Timu nzima · wasimamizi wanatuma")
        else -> row.members.sortedBy { !it.equals(me, true) }.joinToString(", ") { if (it.equals(me, true)) t("You", "Wewe") else it.substringBefore(' ') }
    }
    Row(Modifier.fillMaxWidth().background(if (C.glassy) C.glass2 else C.card).clickable(role = Role.Button) { openInfo() }.padding(horizontal = 14.dp, vertical = 8.dp).testTag("chat_head"),
        verticalAlignment = Alignment.CenterVertically) {
        ChatAvatar(row, me, 40.dp, row?.kind == "dm" && p?.online == true, m = m)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(row?.let { chatTitle(it, me) } ?: "", style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp), color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("head_title"))
            if (status.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                if (typed != null && !(typed.contains("record") || typed.contains("rekodi"))) { TypingDots(ChatLook.online, Modifier.padding(end = 6.dp), 4.dp) }
                Text(status, style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (typed != null) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (typed != null || p?.online == true && row?.kind == "dm") ChatLook.online else C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("head_status"))
            }
        }
        if (row?.muted() == true) Icon(ChatIc.get("mute"), t("Muted", "Imenyamazishwa"), tint = C.muted, modifier = Modifier.size(18.dp))
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(Ic.get("info"), t("Chat info", "Maelezo ya gumzo"), tint = C.muted, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
private fun DayChip(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        val deep = Deep.on()
        Text(label, style = CwType.meta(FontWeight.Bold), color = if (C === Sun) Color.Black else if (deep) Deep.dayText() else cwTime(), maxLines = 1, softWrap = false,
            modifier = Modifier.shadow(if (deep) 0.dp else 1.dp, RoundedCornerShape(9.dp)).clip(RoundedCornerShape(9.dp)).background(if (deep) Deep.day() else if (C.dark) Color(0xFF1E2A36) else Color.White).padding(horizontal = 11.dp, vertical = 4.dp).testTag("day_chip"))
    }
}

/** What a screen reader says for a bubble: "Grace, 10:42, Pump fixed, read". */
internal fun bubbleSays(msg: Msg, mine: Boolean): String {
    val who = if (mine) t("You", "Wewe") else msg.from
    val what = if (msg.deleted) t("This message was deleted", "Ujumbe huu ulifutwa") else msg.preview().ifBlank { msg.text }
    val st = if (!mine || msg.deleted) "" else when (msg.status) {
        "read" -> t("read", "imesomwa"); "delivered" -> t("delivered", "imefika"); "queued" -> t("waiting to send", "inasubiri kutumwa"); "failed" -> t("not sent", "haijatumwa"); else -> t("sent", "imetumwa")
    }
    return listOf(who, Dates.time(Instant.ofEpochMilli(msg.at)), what, st).filter { it.isNotBlank() }.joinToString(", ")
}

/** One message: a system line in the middle, or a bubble (left: theirs, right: mine). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MsgLine(m: AppModel, msg: Msg, row: ChatRow?, me: String, group: Boolean, first: Boolean, last: Boolean, maxW: androidx.compose.ui.unit.Dp, flash: Boolean, fresh: Boolean, motion: Boolean,
                    seen: List<String>, onReply: () -> Unit, onLong: () -> Unit, onDouble: () -> Unit, openMedia: (ChatMedia) -> Unit, jump: (String) -> Unit, openProject: (String, String) -> Unit, reacts: () -> Unit,
                    selecting: Boolean = false, selected: Boolean = false, toggle: () -> Unit = {}, story: (String, List<File>) -> Unit = { _, _ -> }, info: () -> Unit = {}) {
    if (msg.sys != null || msg.kind == "sys") { SysLine(m, msg, fresh, motion, openProject); return }
    val mine = msg.from.equals(me, true)
    val density = LocalDensity.current
    val trigger = with(density) { 64.dp.toPx() }
    var dx by remember { mutableFloatStateOf(0f) }
    var buzzed by remember { mutableStateOf(false) }
    val hp = rememberHaptics()
    val bg = if (mine) bubbleColor(row?.bubble.orEmpty()) else ChatLook.theirs()
    val flashA by animateFloatAsState(if (flash) 1f else 0f, label = "flash")
    // chat 2 (B3): rounded bubbles, a square-ish corner where a run starts (no tail), smaller corners where they join
    val shape = remember(mine, first, last) { bubble2(mine, first, last) }
    val big = if (msg.kind == "text" && !msg.deleted && msg.reply == null && !msg.fwd) emojiOnly(msg.text) else 0
    Box(Modifier.fillMaxWidth().settleIn(fresh, motion, mine).padding(top = if (first) 6.dp else 2.dp).background(C.blue.copy(alpha = 0.18f * flashA))
        .pointerInput(msg.key) {
            // right: reply (a haptic tick at the threshold); left: the exact time shows
            detectHorizontalDragGestures(onDragEnd = { if (dx > trigger) onReply(); dx = 0f; buzzed = false }, onDragCancel = { dx = 0f; buzzed = false }) { ch, d ->
                val n = (dx + d).coerceIn(-trigger * 1.3f, trigger * 1.4f)
                if (n != dx) { dx = n; ch.consume() }
                if (dx > trigger && !buzzed) { buzzed = true; hp.tap() }
            }
        }.testTag("msg_${msg.key}")) {
        // the reply arrow grows while swiping
        if (dx > 4f) {
            val k = (dx / trigger).coerceIn(0f, 1.2f)
            Box(Modifier.align(Alignment.CenterStart).padding(start = 6.dp).size(32.dp).graphicsLayer { alpha = k.coerceAtMost(1f); scaleX = 0.4f + 0.6f * k; scaleY = 0.4f + 0.6f * k }
                .clip(CircleShape).background(if (k >= 1f) C.blue else C.card).testTag("reply_arrow"), contentAlignment = Alignment.Center) {
                Icon(ChatIc.get("reply"), null, tint = if (k >= 1f) Color.White else C.blue, modifier = Modifier.size(18.dp))
            }
        }
        // the exact time, revealed by swiping left
        if (dx < -4f) Text(Dates.dateTime(Instant.ofEpochMilli(msg.at)) + ":" + "%02d".format(Instant.ofEpochMilli(msg.at).atZone(Dates.zone()).second), style = MaterialTheme.typography.labelSmall, color = C.muted, maxLines = 1, softWrap = false,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp).alpha((-dx / trigger).coerceIn(0f, 1f)).testTag("swipe_time"))
        Column(Modifier.offset { IntOffset(dx.roundToInt(), 0) }.align(if (mine) Alignment.CenterEnd else Alignment.CenterStart), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            val says = bubbleSays(msg, mine)
            if (big > 0) {
                // 1–3 emoji: large, without a bubble
                Column(Modifier.combinedClickable(onClick = {}, onLongClick = onLong, onDoubleClick = onDouble, role = Role.Button).semantics { contentDescription = says }.padding(horizontal = 6.dp).testTag("emoji_big"), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                    if (group && !mine && first) Text(msg.from, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold), color = ChatLook.nameColor(msg.from), maxLines = 1, modifier = Modifier.testTag("sender"))
                    Text(msg.text.trim(), fontSize = when (big) { 1 -> 48.sp; 2 -> 40.sp; else -> 34.sp }, lineHeight = when (big) { 1 -> 56.sp; 2 -> 48.sp; else -> 42.sp })
                    Row(Modifier.clip(RoundedCornerShape(8.dp)).background(if (C.dark) Color(0x66000000) else Color(0xCCFFFFFF)).padding(horizontal = 6.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(Dates.time(Instant.ofEpochMilli(msg.at)), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = C.muted, maxLines = 1, softWrap = false)
                        if (mine) { Spacer(Modifier.width(3.dp)); Ticks(msg.status) }
                    }
                }
            } else Night(mine && row?.bubble.isNullOrBlank()) { Column(Modifier.padding(start = if (mine) 0.dp else 4.dp, end = if (mine) 4.dp else 0.dp).widthIn(max = maxW).then(if (selected) Modifier.border(3.dp, cwSel(), shape) else Modifier).shadow(if (C.dark) 0.dp else 0.5.dp, shape).clip(shape).background(bg)
                .then(if (selecting) Modifier.clickable(role = Role.Checkbox, onClick = toggle) else Modifier.combinedClickable(onClick = { if (msg.status == "failed") m.chat.retry(msg) }, onLongClick = onLong, onDoubleClick = onDouble, role = Role.Button))
                .semantics { contentDescription = says; this.selected = selected }
                .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 5.dp).testTag(if (mine) "bubble_mine" else "bubble_theirs")) {
                if (group && !mine && first) Row(verticalAlignment = Alignment.CenterVertically) {
                    // their face next to their name, when they have a photo (feat-mea)
                    if (org.rafikioutreach.pin.data.TeamPhotos.url(msg.from) != null) { Box(Modifier.padding(bottom = 2.dp)) { PhotoOr(msg.from, 18.dp) {} }; Spacer(Modifier.width(5.dp)) }
                    Text(msg.from, style = CwType.meta(FontWeight.ExtraBold), color = ChatLook.nameColor(msg.from), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 1.dp).testTag("sender"))
                }
                if (msg.fwd && !msg.deleted) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
                    Icon(ChatIc.get("fwd"), null, tint = C.muted, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(4.dp))
                    Text(t("Forwarded", "Imesambazwa"), style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic), color = C.muted)
                }
                if (msg.deleted) {
                    Text("🚫 " + if (mine) t("You deleted this message", "Ulifuta ujumbe huu") else t("This message was deleted", "Ujumbe huu ulifutwa"), style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic), color = C.muted)
                } else {
                    msg.reply?.let { q -> QuoteBox(q, me, Modifier.padding(bottom = 4.dp).clickable(role = Role.Button) { jump(q.id) }) }
                    MsgBody(m, msg, mine, row, maxW, openMedia, openProject)
                    // chat 2: Translate · Tafsiri (B7), Show text (B8), Add to project photos (B5)
                    TranslateLine(m, msg, mine)
                    if (msg.media.any { it.type == "voice" }) VoiceTextLine(m, msg)
                    AddPhotosLine(m, msg, row, story)
                }
                // time, edited, star and ticks, bottom right; on mine a tap shows who read it (B9)
                Row(Modifier.align(Alignment.End).padding(top = 1.dp).then(if (mine && !msg.deleted && !msg.queued && !selecting) Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = info).testTag("ticks_btn") else Modifier), verticalAlignment = Alignment.CenterVertically) {
                    if (row?.pins?.any { it.id == msg.id } == true) { Icon(Ic.get("pin"), t("Pinned", "Imebandikwa"), tint = C.muted, modifier = Modifier.size(11.dp).testTag("pinned_mark")); Spacer(Modifier.width(3.dp)) }
                    if (msg.starred) { Icon(ChatIc.get("star"), t("Starred", "Ina nyota"), tint = C.muted, modifier = Modifier.size(11.dp)); Spacer(Modifier.width(3.dp)) }
                    if (msg.edited > 0 && !msg.deleted) Text(t("edited ", "imehaririwa "), style = CwType.small(FontWeight.Medium).copy(fontStyle = FontStyle.Italic), color = cwTime(), modifier = Modifier.testTag("edited"))
                    Text(Dates.time(Instant.ofEpochMilli(msg.at)), style = CwType.small(FontWeight.Medium), color = cwTime(), maxLines = 1, softWrap = false)
                    if (mine && !msg.deleted) { Spacer(Modifier.width(3.dp)); Ticks(msg.status) }
                }
                if (msg.failed != null) Text(msg.failed, style = CwType.small(FontWeight.Medium), color = C.bad, modifier = Modifier.padding(top = 2.dp))
                SendState(m, msg) // B14
            } }
            if (msg.reacts.isNotEmpty()) Row(Modifier.padding(top = 1.dp, start = 6.dp + if (mine) 0.dp else TAIL, end = 6.dp + if (mine) TAIL else 0.dp).offset(y = (-4).dp).pop(msg.reacts.hashCode(), motion).clip(RoundedCornerShape(99.dp)).background(C.card).border(1.dp, C.line, RoundedCornerShape(99.dp))
                .clickable(role = Role.Button) { reacts() }.padding(horizontal = 7.dp, vertical = 2.dp).testTag("reacts_${msg.key}"), verticalAlignment = Alignment.CenterVertically) {
                msg.reacts.entries.sortedByDescending { it.value.size }.take(4).forEach { (e, _) -> Text(e, fontSize = 13.sp) }
                val total = msg.reacts.values.sumOf { it.size }
                if (total > 1) Text(" $total", style = MaterialTheme.typography.labelSmall, color = C.muted)
            }
            if (seen.isNotEmpty()) SeenBy(seen, Modifier.padding(top = 3.dp, end = if (mine) TAIL else 0.dp, start = if (mine) 0.dp else TAIL + 4.dp))
        }
    }
}

/** A line the app wrote: an icon per kind and a short sentence with the time ("Meril fixed the pump · 10:42"). */
@Composable
private fun SysLine(m: AppModel, msg: Msg, fresh: Boolean, motion: Boolean, openProject: (String, String) -> Unit) {
    val s = msg.sys
    val (icon, hue) = when (s?.type) {
        "repair" -> "wrench" to "amber"; "notworking" -> "alert" to "coral"; "fixed" -> "okc" to "green"; "assigned" -> "cal" to "blue"; "sms" -> "phone" to "teal"; "step" -> "clip" to "slate"
        "pinned" -> "pin" to "blue"; "created", "added", "joined" -> "people" to "green"; "removed", "left" -> "people" to "slate"; else -> "info" to "slate"
    }
    // the server's line is English: in Kiswahili (or when it has none) the app builds its own short one from sys
    val text = if (org.rafikioutreach.pin.Lang.sw || msg.text.isBlank() || s?.type in setOf("fixed", "repair", "pinned", "notworking", "sms", "assigned")) sysText(s) else msg.text
    val hu = C.hue(hue)
    Box(Modifier.fillMaxWidth().settleIn(fresh, motion).padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Row(Modifier.widthIn(max = 330.dp).clip(RoundedCornerShape(14.dp)).background(if (C.dark) Color(0xFF1E2A36) else Color(0xF5FFFFFF)).border(1.dp, hu.bg, RoundedCornerShape(14.dp))
            .then(if (!s?.project.isNullOrBlank()) Modifier.clickable(role = Role.Button) { openProject(s!!.project, "pin") } else if (s?.type == "pinned" && s.repair.isNotBlank()) Modifier else Modifier)
            .semantics(mergeDescendants = true) {}.padding(horizontal = 10.dp, vertical = 6.dp).testTag("sys_${msg.key}"),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(hu.bg), contentAlignment = Alignment.Center) { Icon(Ic.get(icon), null, tint = hu.fg, modifier = Modifier.size(13.dp)) }
            Spacer(Modifier.width(8.dp))
            Text(text + " · " + Dates.time(Instant.ofEpochMilli(msg.at)), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = C.ink, modifier = Modifier.testTag("sys_text"))
        }
    }
}

/** A system line's short words: "Meril fixed the pump", "Morgan pinned a message". */
internal fun sysText(s: org.rafikioutreach.pin.data.SysNote?): String {
    val by = s?.by.orEmpty()
    val who = by.substringBefore(' ')
    return when (s?.type) {
        "repair" -> if (who.isNotBlank()) t("$who reported a repair", "$who ameripoti matengenezo") else t("Repair reported", "Matengenezo yameripotiwa")
        "notworking" -> t("A visit found it not working", "Ziara imekuta haifanyi kazi") + if (who.isNotBlank()) " · $who" else ""
        "assigned" -> (s.to.ifBlank { by }).substringBefore(' ').let { to -> if (to.isNotBlank()) t("Repair assigned to $to", "Matengenezo amepewa $to") else t("Repair assigned", "Matengenezo yamegawiwa") }
        "step" -> t("Repair step: ", "Hatua ya matengenezo: ") + s.step
        "fixed" -> if (who.isNotBlank()) t("$who fixed the pump", "$who ametengeneza pampu") else t("Pump fixed", "Pampu imetengenezwa")
        "sms" -> t("Community report", "Ripoti ya jamii")
        "pinned" -> t("${who.ifBlank { by }} pinned a message", "${who.ifBlank { by }} amebandika ujumbe")
        "created" -> t("$by created the group", "$by ameunda kikundi")
        "added" -> t("$by added ${s.who.joinToString(", ")}", "$by amemwongeza ${s.who.joinToString(", ")}")
        "removed" -> t("$by removed ${s.who.joinToString(", ")}", "$by amemwondoa ${s.who.joinToString(", ")}")
        "joined" -> t("${s.who.joinToString(", ").ifBlank { by }} joined with the invite link", "${s.who.joinToString(", ").ifBlank { by }} amejiunga kwa kiungo cha mwaliko")
        "left" -> t("${s.who.joinToString(", ").ifBlank { by }} left", "${s.who.joinToString(", ").ifBlank { by }} ameondoka")
        else -> t("Update", "Habari")
    }
}

/** The quoted message on a reply. */
@Composable
internal fun QuoteBox(q: org.rafikioutreach.pin.data.Quote, me: String, modifier: Modifier = Modifier) {
    val col = if (q.from.equals(me, true)) C.blue else ChatLook.nameColor(q.from)
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (C.dark) Color(0x22FFFFFF) else Color(0x0F000000)).height(IntrinsicSize.Min).testTag("quote")) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(col))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
            Text(if (q.from.equals(me, true)) t("You", "Wewe") else q.from, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold), color = col, maxLines = 1)
            Text(org.rafikioutreach.pin.data.chatPreview(q.kind, q.text), style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private val URL_RE = Regex("https?://[^\\s<>\"]+")
private val PROJECT_RE = Regex("pin\\.rafikioutreach\\.org/pin#p[/=]([A-Za-z0-9_-]+)")

/** The text with links (tappable) and @mentions (bold, coloured; mine also marked behind). */
@Composable
private fun richText(text: String, names: List<String>, me: String, onProject: (String) -> Unit): AnnotatedString {
    val link = C.blue; val mention = C.blue; val mark = C.blue.copy(alpha = 0.16f)
    return remember(text, names, link, me) {
        data class Span(val s: Int, val e: Int, val kind: String, val v: String)
        val spans = mutableListOf<Span>()
        URL_RE.findAll(text).forEach { spans += Span(it.range.first, it.range.last + 1, "url", it.value.trimEnd('.', ',', ')', '!', '?')) }
        names.filter { it.isNotBlank() }.sortedByDescending { it.length }.forEach { n ->
            var i = text.indexOf("@$n", ignoreCase = true)
            while (i >= 0) { val e = i + n.length + 1; if (spans.none { i < it.e && e > it.s }) spans += Span(i, e, "at", n); i = text.indexOf("@$n", e, ignoreCase = true) }
        }
        buildAnnotatedString {
            var at = 0
            spans.sortedBy { it.s }.forEach { sp ->
                if (sp.s < at) return@forEach
                append(text.substring(at, sp.s))
                val shown = text.substring(sp.s, sp.e)
                when (sp.kind) {
                    "url" -> {
                        val pid = PROJECT_RE.find(sp.v)?.groupValues?.get(1)
                        val style = TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline))
                        if (pid != null) withLink(LinkAnnotation.Clickable("p:$pid", style) { onProject(pid) }) { append(shown) }
                        else withLink(LinkAnnotation.Url(sp.v, style)) { append(shown) }
                    }
                    else -> withStyle(SpanStyle(color = mention, fontWeight = FontWeight.Bold, background = if (sp.v.equals(me, true)) mark else Color.Unspecified)) { append(shown) }
                }
                at = sp.e
            }
            if (at < text.length) append(text.substring(at))
        }
    }
}

/** What's in a bubble: photos, a voice note, a location, a contact, a document, a trip, then the text and a link preview. */
@Composable
private fun MsgBody(m: AppModel, msg: Msg, mine: Boolean, row: ChatRow?, maxW: androidx.compose.ui.unit.Dp, openMedia: (ChatMedia) -> Unit, openProject: (String, String) -> Unit) {
    val visual = msg.media.filter { it.type == "photo" || it.type == "video" }
    if (visual.isNotEmpty()) Box(Modifier.padding(bottom = if (msg.text.isNotBlank()) 4.dp else 0.dp)) { PhotoGrid(m, visual, maxW - 20.dp) { i -> openMedia(visual[i]) } }
    msg.media.firstOrNull { it.type == "voice" }?.let { VoiceBubble(m, msg, it, mine) }
    msg.media.filter { it.type == "doc" }.forEach { DocCard(m, it) }
    if (msg.kind == "location" || msg.kind == "live") msg.loc?.let { LocationCard(m, msg, mine) } ?: msg.live?.let { LocationCard(m, msg, mine) }
    msg.contact?.let { ContactCard(m, it) }
    if (msg.kind == "trip") msg.trip?.let { TripCardView(it, minOf(maxW - 20.dp, 260.dp)) }
    if (msg.text.isNotBlank() && msg.kind != "contact") {
        // @Name is highlighted when the name is in the message's mentions (older messages: any member)
        val names = msg.mentions.ifEmpty { row?.members.orEmpty() }
        Text(richText(msg.text, names, m.chat.me) { openProject(it, "pin") }, style = CwType.msg(), color = C.text, modifier = Modifier.testTag("msg_text"))
        URL_RE.find(msg.text)?.value?.trimEnd('.', ',', ')', '!', '?')?.let { LinkCard(m, it, openProject) }
    }
}

/** A website's preview (a large picture when it has one), or a project link as a rich card: photo, status, "Report a visit". */
@Composable
private fun LinkCard(m: AppModel, url: String, openProject: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val pid = PROJECT_RE.find(url)?.groupValues?.get(1)
    val shape = RoundedCornerShape(12.dp)
    val box = Modifier.padding(top = 6.dp).fillMaxWidth().clip(shape).background(if (C.dark) Color(0x22FFFFFF) else Color(0x0F000000))
    if (pid != null) {
        val snap = m.snap.collectAsState().value
        val p = snap?.projects?.firstOrNull { it.id == pid }
        val rep = p?.let { snap.repairs[it.id] }
        Column(box.clickable(role = Role.Button) { openProject(pid, "pin") }.testTag("project_card")) {
            p?.photo?.let { ph -> NetImage(m.api.photoUrl(ph, 480), Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(C.hue("blue").bg).testTag("project_photo")) { Icon(Ic.get("drop"), null, tint = C.hue("blue").fg, modifier = Modifier.size(30.dp)) } }
            Column(Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (p?.photo == null) { IconTile("drop", C.hue("blue").fg, C.hue("blue").bg, 36.dp); Spacer(Modifier.width(10.dp)) }
                    Column(Modifier.weight(1f)) {
                        Text(p?.title ?: t("Project $pid", "Mradi $pid"), style = MaterialTheme.typography.titleSmall, color = C.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(p?.let { listOf(it.area, it.country).filter { s -> s.isNotBlank() }.joinToString(", ") }.orEmpty().ifBlank { t("Open the project", "Fungua mradi") }, style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 1)
                    }
                }
                // the status: needs repair, not working, or working
                val (st, hue) = when { rep?.state == "notworking" -> t("Not working", "Haifanyi kazi") to "coral"; rep != null -> t("Needs repair", "Inahitaji matengenezo") to "amber"; p != null -> t("Working", "Inafanya kazi") to "green"; else -> "" to "slate" }
                if (st.isNotBlank()) Text(st, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold), color = C.hue(hue).fg, maxLines = 1, softWrap = false,
                    modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(99.dp)).background(C.hue(hue).bg).padding(horizontal = 8.dp, vertical = 3.dp).testTag("project_status"))
                Text(t("Report a visit", "Ripoti ziara"), style = MaterialTheme.typography.labelLarge, color = Color.White, textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.blue).clickable(role = Role.Button) { openProject(pid, "visit") }.padding(vertical = 9.dp).testTag("project_visit"))
            }
        }
        return
    }
    var data by remember(url) { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(url) { data = m.chat.preview(url) }
    val d = data ?: return
    val title = d.optString("title").takeIf { it.isNotBlank() && it != "null" } ?: return
    Column(box.clickable(role = Role.Button) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }.testTag("link_card")) {
        d.optString("image").takeIf { it.startsWith("http") }?.let { NetImage(it, Modifier.fillMaxWidth().aspectRatio(1.91f).background(C.tint).testTag("link_image")) {} }
        Column(Modifier.padding(10.dp)) {
            Text(d.optString("site").takeIf { it.isNotBlank() && it != "null" } ?: Uri.parse(url).host.orEmpty(), style = MaterialTheme.typography.labelSmall, color = C.muted, maxLines = 1)
            Text(title, style = MaterialTheme.typography.titleSmall, color = C.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            d.optString("description").takeIf { it.isNotBlank() && it != "null" }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** A place, or a live location that moves while it's shared (until it ends or is stopped). */
@Composable
private fun LocationCard(m: AppModel, msg: Msg, mine: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberScope()
    val live = msg.kind == "live"
    val lat = msg.live?.lat ?: msg.loc?.lat; val lng = msg.live?.lng ?: msg.loc?.lng
    var tickNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(live) { while (live) { delay(30_000); tickNow = System.currentTimeMillis() } }
    val active = live && (msg.live?.until ?: 0L) > tickNow
    Column(Modifier.width(240.dp).testTag(if (live) "live_card" else "loc_card")) {
        // a drawn map tile: streets, a river and the pin
        Box(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(12.dp)).background(if (C.dark) Color(0xFF203040) else Color(0xFFE6EEDF))
            .clickable(role = Role.Button) { if (lat != null && lng != null) runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng"))) } }, contentAlignment = Alignment.Center) {
            val road = if (C.dark) Color(0xFF34495C) else Color.White; val water = if (C.dark) Color(0xFF1C4E73) else Color(0xFFB5D8F2)
            Canvas(Modifier.matchParentSize()) {
                val w = size.width; val h = size.height
                drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(0f, h * .75f); cubicTo(w * .3f, h * .55f, w * .55f, h * .95f, w, h * .7f) }, water, style = Stroke(10.dp.toPx()))
                drawLine(road, Offset(0f, h * .3f), Offset(w, h * .42f), 6.dp.toPx()); drawLine(road, Offset(w * .35f, 0f), Offset(w * .48f, h), 5.dp.toPx())
                drawLine(road, Offset(w * .72f, 0f), Offset(w * .66f, h), 3.dp.toPx())
            }
            if (active) Box(Modifier.size(54.dp).clip(CircleShape).background(ChatLook.online.copy(alpha = 0.25f)))
            Icon(Ic.get("pin"), null, tint = if (live) ChatLook.online else C.bad, modifier = Modifier.size(34.dp).offset(y = (-8).dp))
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(ChatIc.get(if (live) "live" else "loc"), null, tint = if (active) ChatLook.online else C.muted, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(if (live) (if (active) t("Live location", "Mahali moja kwa moja") else t("Live location ended", "Mahali moja kwa moja pameisha")) else t("Location", "Mahali"), style = MaterialTheme.typography.titleSmall, color = C.ink)
                Text(when {
                    active -> t("Until ", "Hadi ") + Dates.time(Instant.ofEpochMilli(msg.live!!.until)) + (msg.live.at.takeIf { it > 0 }?.let { a -> " · " + t("updated ", "imesasishwa ") + ago(a, tickNow) } ?: "")
                    lat != null && lng != null -> org.rafikioutreach.pin.data.LatLng(lat, lng).text()
                    else -> ""
                }, style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 2)
                val here = m.fix.collectAsState().value
                if (here != null && lat != null && lng != null && !mine) Text(Geo.dist(Geo.metres(here.ll, org.rafikioutreach.pin.data.LatLng(lat, lng)), m.prefs.miles) + t(" away", " kutoka hapa"), style = MaterialTheme.typography.labelSmall, color = C.muted)
            }
        }
        if (active && mine) Text(t("Stop sharing", "Acha kushiriki"), style = MaterialTheme.typography.labelLarge, color = C.bad,
            modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { scope.launch { m.chat.stopLive(msg) } }.padding(vertical = 8.dp).testTag("live_stop"))
    }
}

private fun ago(at: Long, now: Long): String { val min = ((now - at) / 60_000).toInt(); return if (min < 1) t("just now", "sasa hivi") else t("$min min ago", "dakika $min zilizopita") }

/** A teammate's contact card: Message or see their contact sheet. */
@Composable
private fun ContactCard(m: AppModel, name: String) {
    Column(Modifier.width(230.dp).testTag("contact_card")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PersonAvatar(name, 44.dp); Spacer(Modifier.width(10.dp))
            Column { Text(name, style = MaterialTheme.typography.titleSmall, color = C.ink, maxLines = 2); Text(t("RAFIKI team", "Timu ya RAFIKI"), style = MaterialTheme.typography.bodySmall, color = C.muted) }
        }
        Row(Modifier.padding(top = 8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("Message", "Tuma ujumbe"), style = MaterialTheme.typography.labelLarge, color = C.blue, textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).border(1.dp, C.line, RoundedCornerShape(10.dp)).clickable(role = Role.Button) { ChatNav.dm(name) }.padding(vertical = 9.dp).testTag("cc_message"))
            Text(t("View", "Tazama"), style = MaterialTheme.typography.labelLarge, color = C.blue, textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).border(1.dp, C.line, RoundedCornerShape(10.dp)).clickable(role = Role.Button) { Contact.show(name) }.padding(vertical = 9.dp).testTag("cc_view"))
        }
    }
}

/** A document: its name and size; a tap downloads it once and opens it with the phone's app. */
@Composable
private fun DocCard(m: AppModel, md: ChatMedia) {
    val ctx = LocalContext.current
    val scope = rememberScope()
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    Row(Modifier.width(240.dp).clip(RoundedCornerShape(12.dp)).background(if (C.dark) Color(0x22FFFFFF) else Color(0x0F000000)).clickable(role = Role.Button) {
        if (busy) return@clickable
        busy = true
        scope.launch {
            val f = m.chat.file(md); busy = false
            if (f == null) { err = t("Couldn’t download it. Check the signal.", "Imeshindwa kupakua. Angalia mtandao."); return@launch }
            err = null
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "org.rafikioutreach.pin.files", f)
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, md.mime.ifBlank { org.rafikioutreach.pin.data.ChatOutbox.mimeOf("doc", f.name) }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                .onFailure { err = t("No app on this phone opens this file.", "Hakuna programu kwenye simu hii inayofungua faili hili.") }
        }
    }.padding(10.dp).testTag("doc_card"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(C.hue("coral").bg), contentAlignment = Alignment.Center) {
            if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = C.hue("coral").fg)
            else Icon(ChatIc.get("doc"), null, tint = C.hue("coral").fg, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(md.name.ifBlank { t("Document", "Hati") }, style = MaterialTheme.typography.titleSmall, color = C.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(md.name.substringAfterLast('.', "").uppercase().take(4), sizeText(md.size)).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = C.muted)
            err?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = C.bad) }
        }
    }
}

internal fun sizeText(b: Long): String = when { b <= 0 -> ""; b < 1024 * 1024 -> "${(b + 1023) / 1024} KB"; else -> "%.1f MB".format(java.util.Locale.ROOT, b / 1048576.0) }

/** B3 bubble corners: 14 dp, 6 dp where a run joins, 4 dp at the start of a run (top left theirs, top right mine). */
internal fun bubble2(mine: Boolean, first: Boolean, last: Boolean): RoundedCornerShape {
    val big = 14.dp; val join = 6.dp; val start = 4.dp
    return if (mine) RoundedCornerShape(topStart = big, topEnd = if (first) start else join, bottomEnd = if (last) big else join, bottomStart = big)
    else RoundedCornerShape(topStart = if (first) start else join, topEnd = big, bottomEnd = big, bottomStart = if (last) big else join)
}
/** The time inside a bubble and on the day chips: calmer than the text, with more contrast in sun. */
@Composable internal fun cwTime() = if (C === Sun) Color(0xFF1E1E1E) else if (C.dark) Color(0xFF9AA8B3) else Color(0xFF66737D)
/** The ring on a chosen message while selecting. */
@Composable internal fun cwSel() = if (C === Sun) Color(0xFF00562A) else if (C.dark) Color(0xFF3CC07A) else Color(0xFF1C9C5A)
/** The light theme's chat wall when no wallpaper is chosen: warm, with faint dots. */
@Composable
internal fun WarmWall(modifier: Modifier) {
    Canvas(modifier.background(Color(0xFFECE7DF)).testTag("wall_warm")) {
        val step = 40.dp.toPx(); val r = 2.dp.toPx()
        var y = 0f
        while (y < size.height + step) { var x = 0f; while (x < size.width + step) {
            drawCircle(Color(0x0D0A78C2), r, Offset(x + 10.dp.toPx(), y + 10.dp.toPx())); drawCircle(Color(0x0B1C9C5A), r, Offset(x + 30.dp.toPx(), y + 30.dp.toPx())); x += step }; y += step }
    }
}

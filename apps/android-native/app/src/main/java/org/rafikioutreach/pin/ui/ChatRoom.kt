package org.rafikioutreach.pin.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
        fun same(a: Msg?, b: Msg) = a != null && a.sys == null && b.sys == null && a.from == b.from && kotlin.math.abs(a.at - b.at) < 5 * 60_000 &&
            Instant.ofEpochMilli(a.at).atZone(Dates.zone()).toLocalDate() == Instant.ofEpochMilli(b.at).atZone(Dates.zone()).toLocalDate()
        out += Line.B(m, !same(prev, m), !same(next, m))
    }
    return out.asReversed()
}

/**
 * One conversation (feat-chata). Bubbles left and right, grouped by sender, with the sender's name and colour in groups;
 * the time and ticks; "typing…" and "recording…"; swipe a message right to reply; hold it for reactions and actions;
 * link and project previews; date separators; a jump-to-bottom button with the count of new messages; older messages
 * load while scrolling up. The list grows from the composer upwards, so the keyboard opening never makes it jump.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatRoom(m: AppModel, c: String, openInfo: () -> Unit, openChat: (String) -> Unit, openProject: (String, String) -> Unit, office: (String) -> Unit) {
    val hub = m.chat
    val rows by hub.rows.collectAsState()
    val row = rows.firstOrNull { it.id == c }
    val msgs by remember(c) { hub.msgs(c) }.collectAsState()
    val presence by hub.presence.collectAsState()
    val typingMap by hub.typing.collectAsState()
    val off by hub.off.collectAsState()
    val me = hub.me
    val scope = rememberScope()
    val hp = rememberHaptics()
    val ctx = LocalContext.current
    DisposableEffect(c) { hub.openChat = c; onDispose { if (hub.openChat == c) hub.openChat = null; VoicePlayer.stop(); hub.typing(c, null) } }
    LaunchedEffect(c) { hub.markSeen(c); hub.loadMsgs(c) }
    LaunchedEffect(msgs.size) { hub.markSeen(c) }
    // "3 unread messages", as the chat was opened
    val unreadAtOpen = remember(c) { row?.unread ?: 0 }
    val unreadFrom = remember(c, msgs.isNotEmpty()) { if (unreadAtOpen > 0) msgs.filter { !it.from.equals(me, true) }.takeLast(unreadAtOpen).firstOrNull()?.id else null }
    val list = remember(msgs, unreadFrom) { lines(msgs, unreadFrom, unreadAtOpen) }
    val state = rememberLazyListState()
    val group = row?.isGroup == true
    var reply by remember(c) { mutableStateOf<Msg?>(null) }
    var editing by remember(c) { mutableStateOf<Msg?>(null) }
    var held by remember { mutableStateOf<Msg?>(null) }
    var viewer by remember { mutableStateOf<Int?>(null) }
    var flash by remember { mutableStateOf<String?>(null) }
    var reactsOf by remember { mutableStateOf<Msg?>(null) }
    val mediaAll = remember(msgs) { msgs.filter { !it.deleted }.flatMap { msg -> msg.media.filter { it.type == "photo" || it.type == "video" }.map { msg to it } } }

    // scroll to a message (a tapped quote, a search hit, a starred message), loading older pages if needed
    fun jump(id: String) = scope.launch {
        var i = list.indexOfFirst { it is Line.B && it.msg.id == id }
        var tries = 0
        while (i < 0 && hub.hasMore(c) && tries < 10) { hub.loadOlder(c); tries++; delay(50); i = lines(hub.msgs(c).value, unreadFrom, unreadAtOpen).indexOfFirst { it is Line.B && it.msg.id == id } }
        if (i >= 0) { state.animateScrollToItem(i); flash = id; delay(1600); flash = null }
    }
    val focus = ChatNav.focus
    LaunchedEffect(focus, msgs.isNotEmpty()) { if (focus != null && msgs.isNotEmpty()) { ChatNav.focus = null; jump(focus) } }

    // new messages: follow them when at the bottom; otherwise count them on the jump button
    val atBottom by remember { derivedStateOf { state.firstVisibleItemIndex <= 1 } }
    var awayAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(atBottom) { awayAt = if (atBottom) null else msgs.lastOrNull()?.at ?: 0L }
    val newer = awayAt?.let { a -> msgs.count { it.at > a && !it.from.equals(me, true) } } ?: 0
    val newest = list.firstOrNull()?.key
    LaunchedEffect(newest) { if (atBottom || msgs.lastOrNull()?.from?.equals(me, true) == true) state.animateScrollToItem(0) }
    // older messages while scrolling up
    val nearTop by remember { derivedStateOf { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= state.layoutInfo.totalItemsCount - 3 } }
    var loadingOlder by remember { mutableStateOf(false) }
    LaunchedEffect(nearTop, msgs.size) { if (nearTop && !loadingOlder && hub.hasMore(c) && msgs.isNotEmpty()) { loadingOlder = true; hub.loadOlder(c); loadingOlder = false } }

    val wall = row?.wallpaper.orEmpty()
    Column(Modifier.fillMaxSize().testTag("chat_room")) {
        ChatHead(m, row, c, me, presence, typingMap, openInfo)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Wallpaper(wall, Modifier.matchParentSize())
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val maxW = maxWidth * 0.8f
                LazyColumn(Modifier.fillMaxSize().testTag("chat_msgs"), state, reverseLayout = true, contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 8.dp)) {
                    items(list, key = { it.key }, contentType = { it::class.simpleName }) { line ->
                        when (line) {
                            is Line.Day -> DayChip(line.label)
                            is Line.Unread -> Text(if (line.n == 1) t("1 unread message", "Ujumbe 1 haujasomwa") else t("${line.n} unread messages", "Ujumbe ${line.n} haujasomwa"),
                                style = MaterialTheme.typography.labelMedium, color = C.ink, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).background(if (C.dark) Color(0x22FFFFFF) else Color(0x14000000)).padding(vertical = 6.dp).testTag("unread_line"))
                            is Line.B -> MsgLine(m, line.msg, row, me, group, line.first, line.last, maxW, flash == line.msg.id,
                                onReply = { hp.long(); reply = line.msg; editing = null },
                                onLong = { hp.long(); held = line.msg },
                                openMedia = { md -> viewer = mediaAll.indexOfFirst { it.second == md && it.first.key == line.msg.key }.coerceAtLeast(0) },
                                jump = { jump(it) }, openProject = openProject, reacts = { reactsOf = line.msg })
                        }
                    }
                    if (loadingOlder || (hub.hasMore(c) && msgs.size >= 50)) item(key = "older") {
                        Box(Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = C.blue) }
                    }
                    if (msgs.isEmpty()) item(key = "empty") {
                        Text(t("No messages yet. Say hello!", "Bado hakuna ujumbe. Salimia!"), style = MaterialTheme.typography.bodyMedium, color = C.muted, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp).testTag("chat_empty"))
                    }
                }
            }
            // jump to the newest, with how many came in meanwhile
            if (!atBottom) Box(Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 12.dp)) {
                Box {
                    Box(Modifier.size(44.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(C.card).clickable(role = Role.Button) { scope.launch { state.animateScrollToItem(0) } }
                        .semantics { contentDescription = t("Jump to the newest", "Nenda kwa mpya zaidi") }.testTag("jump_bottom"), contentAlignment = Alignment.Center) {
                        Icon(Ic.get("down"), null, tint = C.ink, modifier = Modifier.size(22.dp))
                    }
                    if (newer > 0) Text("$newer", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.align(Alignment.TopEnd).offset(4.dp, (-4).dp)
                        .clip(RoundedCornerShape(99.dp)).background(ChatLook.online).padding(horizontal = 6.dp, vertical = 1.dp).testTag("jump_count"))
                }
            }
        }
        when {
            off -> ChatOff(Modifier.padding(12.dp), m.chat.offNeed)
            row != null && row.onlyAdmins && !row.admin(me) -> Text(t("Only admins can send messages", "Wasimamizi pekee wanaweza kutuma ujumbe"), style = MaterialTheme.typography.bodyMedium, color = C.muted, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().background(C.card).padding(18.dp).testTag("only_admins"))
            else -> Composer(m, c, row, reply, editing, { reply = null }, { editing = null }) { scope.launch { state.scrollToItem(0) } }
        }
    }
    held?.let { msg -> MsgSheet(m, msg, row, { held = null }, onReply = { reply = msg; editing = null }, onEdit = { editing = msg; reply = null }, openChat = openChat, openProject = openProject, office = office) }
    reactsOf?.let { msg -> ReactsSheet(m, msg) { reactsOf = null } }
    viewer?.let { i -> if (mediaAll.isNotEmpty()) MediaViewer(m, mediaAll, i) { viewer = null } else viewer = null }
}

/** The chat's head: its picture, name and what's happening (online, last seen, typing, members). A tap opens the info page. */
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
        else -> row.members.sortedBy { !it.equals(me, true) }.joinToString(", ") { if (it.equals(me, true)) t("You", "Wewe") else it.substringBefore(' ') }
    }
    Row(Modifier.fillMaxWidth().background(if (C.glassy) C.glass2 else C.card).clickable(role = Role.Button) { openInfo() }.padding(horizontal = 14.dp, vertical = 8.dp).testTag("chat_head"),
        verticalAlignment = Alignment.CenterVertically) {
        ChatAvatar(row, me, 40.dp, row?.kind == "dm" && p?.online == true, m = m)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(row?.title?.ifBlank { other } ?: "", style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp), color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("head_title"))
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (typed != null) FontWeight.SemiBold else FontWeight.Medium),
                color = if (typed != null || p?.online == true && row?.kind == "dm") ChatLook.online else C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("head_status"))
        }
        if (row?.muted() == true) Icon(ChatIc.get("mute"), t("Muted", "Imenyamazishwa"), tint = C.muted, modifier = Modifier.size(18.dp))
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(Ic.get("info"), t("Chat info", "Maelezo ya gumzo"), tint = C.muted, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
private fun DayChip(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = C.ink, maxLines = 1, softWrap = false,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (C.dark) Color(0xFF1E2A36) else Color(0xF2FFFFFF)).padding(horizontal = 12.dp, vertical = 5.dp).testTag("day_chip"))
    }
}

/** One message: a system note in the middle, or a bubble (left: theirs, right: mine). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MsgLine(m: AppModel, msg: Msg, row: ChatRow?, me: String, group: Boolean, first: Boolean, last: Boolean, maxW: androidx.compose.ui.unit.Dp, flash: Boolean,
                    onReply: () -> Unit, onLong: () -> Unit, openMedia: (ChatMedia) -> Unit, jump: (String) -> Unit, openProject: (String, String) -> Unit, reacts: () -> Unit) {
    if (msg.sys != null || msg.kind == "sys") { SysLine(m, msg, openProject); return }
    val mine = msg.from.equals(me, true)
    val density = LocalDensity.current
    val trigger = with(density) { 64.dp.toPx() }
    var dx by remember { mutableFloatStateOf(0f) }
    var buzzed by remember { mutableStateOf(false) }
    val hp = rememberHaptics()
    val bg = if (mine) ChatLook.mine() else ChatLook.theirs()
    val flashA by animateFloatAsState(if (flash) 1f else 0f, label = "flash")
    val tail = 5.dp; val r = 18.dp
    val shape = if (mine) RoundedCornerShape(r, if (first) tail else r, r, r) else RoundedCornerShape(if (first) tail else r, r, r, r)
    Box(Modifier.fillMaxWidth().padding(top = if (first) 6.dp else 2.dp).background(C.blue.copy(alpha = 0.18f * flashA))
        .pointerInput(msg.key) {
            detectHorizontalDragGestures(onDragEnd = { if (dx > trigger) onReply(); dx = 0f; buzzed = false }, onDragCancel = { dx = 0f; buzzed = false }) { ch, d ->
                val n = (dx + d).coerceIn(0f, trigger * 1.4f)
                if (n != dx) { dx = n; ch.consume() }
                if (dx > trigger && !buzzed) { buzzed = true; hp.tap() }
            }
        }.testTag("msg_${msg.key}")) {
        // the reply arrow that appears while swiping
        if (dx > 4f) Box(Modifier.align(Alignment.CenterStart).padding(start = 6.dp).size(32.dp).alpha((dx / trigger).coerceIn(0f, 1f)).clip(CircleShape).background(C.card), contentAlignment = Alignment.Center) {
            Icon(ChatIc.get("reply"), null, tint = C.blue, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.offset { IntOffset(dx.roundToInt(), 0) }.align(if (mine) Alignment.CenterEnd else Alignment.CenterStart), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            Column(Modifier.widthIn(max = maxW).shadow(if (C.dark) 0.dp else 0.5.dp, shape).clip(shape).background(bg)
                .combinedClickable(onClick = {}, onLongClick = onLong, role = Role.Button).padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 5.dp).testTag(if (mine) "bubble_mine" else "bubble_theirs")) {
                if (group && !mine && first) Text(msg.from, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold), color = ChatLook.nameColor(msg.from), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 2.dp).testTag("sender"))
                if (msg.fwd && !msg.deleted) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
                    Icon(ChatIc.get("fwd"), null, tint = C.muted, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(4.dp))
                    Text(t("Forwarded", "Imesambazwa"), style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic), color = C.muted)
                }
                if (msg.deleted) {
                    Text("🚫 " + if (mine) t("You deleted this message", "Ulifuta ujumbe huu") else t("This message was deleted", "Ujumbe huu ulifutwa"), style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic), color = C.muted)
                } else {
                    msg.reply?.let { q -> QuoteBox(q, me, Modifier.padding(bottom = 4.dp).clickable(role = Role.Button) { jump(q.id) }) }
                    MsgBody(m, msg, mine, row, maxW, openMedia, openProject)
                }
                // time, edited, star and ticks, bottom right
                Row(Modifier.align(Alignment.End).padding(top = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (msg.starred) { Icon(ChatIc.get("star"), t("Starred", "Ina nyota"), tint = C.muted, modifier = Modifier.size(11.dp)); Spacer(Modifier.width(3.dp)) }
                    if (msg.edited > 0 && !msg.deleted) Text(t("edited ", "imehaririwa "), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontStyle = FontStyle.Italic), color = C.muted, modifier = Modifier.testTag("edited"))
                    Text(Dates.time(Instant.ofEpochMilli(msg.at)), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium), color = C.muted, maxLines = 1, softWrap = false)
                    if (mine && !msg.deleted) { Spacer(Modifier.width(3.dp)); Ticks(msg.status) }
                }
                if (msg.failed != null) Text(msg.failed, style = MaterialTheme.typography.labelSmall, color = C.bad, modifier = Modifier.padding(top = 2.dp))
            }
            if (msg.reacts.isNotEmpty()) Row(Modifier.padding(top = 1.dp, start = 6.dp, end = 6.dp).offset(y = (-4).dp).clip(RoundedCornerShape(99.dp)).background(C.card).border(1.dp, C.line, RoundedCornerShape(99.dp))
                .clickable(role = Role.Button) { reacts() }.padding(horizontal = 7.dp, vertical = 2.dp).testTag("reacts_${msg.key}"), verticalAlignment = Alignment.CenterVertically) {
                msg.reacts.entries.sortedByDescending { it.value.size }.take(4).forEach { (e, n) -> Text(e, fontSize = 13.sp) }
                val total = msg.reacts.values.sumOf { it.size }
                if (total > 1) Text(" $total", style = MaterialTheme.typography.labelSmall, color = C.muted)
            }
        }
    }
}

/** A message the app wrote (repair reported, assigned, step changed, fixed; an SMS report; a visit "Not working"). */
@Composable
private fun SysLine(m: AppModel, msg: Msg, openProject: (String, String) -> Unit) {
    val s = msg.sys
    val (icon, hue) = when (s?.type) { "repair", "notworking" -> "wrench" to "amber"; "fixed" -> "okc" to "green"; "assigned" -> "cal" to "blue"; "sms" -> "phone" to "teal"; "step" -> "clip" to "slate"; else -> "info" to "slate" }
    // the server's line is English: in Kiswahili (or when it has none) the app builds its own from sys
    val text = if (org.rafikioutreach.pin.Lang.sw || msg.text.isBlank()) sysText(s) else msg.text
    val hu = C.hue(hue)
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Row(Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(14.dp)).background(if (C.dark) Color(0xFF1E2A36) else Color(0xF5FFFFFF)).border(1.dp, hu.bg, RoundedCornerShape(14.dp))
            .then(if (!s?.project.isNullOrBlank()) Modifier.clickable(role = Role.Button) { openProject(s!!.project, "pin") } else Modifier).padding(horizontal = 12.dp, vertical = 8.dp).testTag("sys_${msg.key}"),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.get(icon), null, tint = hu.fg, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
            Column {
                Text(text, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = C.ink)
                Text(Dates.time(Instant.ofEpochMilli(msg.at)), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = C.muted)
            }
        }
    }
}

internal fun sysText(s: org.rafikioutreach.pin.data.SysNote?): String {
    val by = s?.by.orEmpty()
    return when (s?.type) {
        "repair" -> t("Repair reported", "Matengenezo yameripotiwa") + if (by.isNotBlank()) t(" by $by", " na $by") else ""
        "notworking" -> t("A visit reported “Not working”", "Ziara iliripoti “Haifanyi kazi”") + if (by.isNotBlank()) " · $by" else ""
        "assigned" -> t("Repair assigned", "Matengenezo yamegawiwa") + (s.to.ifBlank { by }).let { if (it.isNotBlank()) t(" to $it", " kwa $it") else "" }
        "step" -> t("Repair step: ", "Hatua ya matengenezo: ") + s.step
        "fixed" -> t("Fixed ✓", "Imerekebishwa ✓") + if (by.isNotBlank()) " · $by" else ""
        "sms" -> t("Community report arrived", "Ripoti ya jamii imefika")
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

/** The text with links (tappable) and @mentions (bold, coloured). */
@Composable
private fun richText(text: String, names: List<String>, onProject: (String) -> Unit): AnnotatedString {
    val link = C.blue; val mention = C.blue
    return remember(text, names, link) {
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
                    else -> withStyle(SpanStyle(color = mention, fontWeight = FontWeight.Bold)) { append(shown) }
                }
                at = sp.e
            }
            if (at < text.length) append(text.substring(at))
        }
    }
}

/** What's in a bubble: photos, a voice note, a location, a contact, a document, then the text and a link preview. */
@Composable
private fun MsgBody(m: AppModel, msg: Msg, mine: Boolean, row: ChatRow?, maxW: androidx.compose.ui.unit.Dp, openMedia: (ChatMedia) -> Unit, openProject: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val visual = msg.media.filter { it.type == "photo" || it.type == "video" }
    if (visual.isNotEmpty()) Box(Modifier.padding(bottom = if (msg.text.isNotBlank()) 4.dp else 0.dp)) { PhotoGrid(m, visual, maxW - 20.dp) { i -> openMedia(visual[i]) } }
    msg.media.firstOrNull { it.type == "voice" }?.let { VoiceBubble(m, msg, it, mine) }
    msg.media.filter { it.type == "doc" }.forEach { DocCard(m, it) }
    if (msg.kind == "location" || msg.kind == "live") msg.loc?.let { LocationCard(m, msg, mine) } ?: msg.live?.let { LocationCard(m, msg, mine) }
    msg.contact?.let { ContactCard(m, it) }
    if (msg.text.isNotBlank() && msg.kind != "contact") {
        Text(richText(msg.text, row?.members.orEmpty()) { openProject(it, "pin") }, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.5.sp, lineHeight = 21.sp), color = C.text, modifier = Modifier.testTag("msg_text"))
        URL_RE.find(msg.text)?.value?.trimEnd('.', ',', ')', '!', '?')?.let { LinkCard(m, it, openProject) }
    }
}

/** A website's preview, or a project link as a small project card. */
@Composable
private fun LinkCard(m: AppModel, url: String, openProject: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val pid = PROJECT_RE.find(url)?.groupValues?.get(1)
    val shape = RoundedCornerShape(12.dp)
    val box = Modifier.padding(top = 6.dp).fillMaxWidth().clip(shape).background(if (C.dark) Color(0x22FFFFFF) else Color(0x0F000000))
    if (pid != null) {
        val p = m.snap.collectAsState().value?.projects?.firstOrNull { it.id == pid }
        Row(box.clickable(role = Role.Button) { openProject(pid, "pin") }.padding(10.dp).testTag("project_card"), verticalAlignment = Alignment.CenterVertically) {
            IconTile("drop", C.hue("blue").fg, C.hue("blue").bg, 40.dp); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(p?.title ?: t("Project $pid", "Mradi $pid"), style = MaterialTheme.typography.titleSmall, color = C.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(p?.let { listOf(it.area, it.country).filter { s -> s.isNotBlank() }.joinToString(", ") }.orEmpty().ifBlank { t("Open the project", "Fungua mradi") }, style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 1)
                val rep = p?.let { m.snap.collectAsState().value?.repairs?.get(it.id) }
                if (rep != null) Text(if (rep.state == "notworking") t("Not working", "Haifanyi kazi") else t("Needs repair", "Inahitaji matengenezo"), style = MaterialTheme.typography.labelSmall, color = if (rep.state == "notworking") C.bad else C.warn)
            }
            Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(18.dp))
        }
        return
    }
    var data by remember(url) { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(url) { data = m.chat.preview(url) }
    val d = data ?: return
    val title = d.optString("title").takeIf { it.isNotBlank() && it != "null" } ?: return
    Column(box.clickable(role = Role.Button) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }.padding(10.dp).testTag("link_card")) {
        d.optString("image").takeIf { it.startsWith("http") }?.let { NetImage(it, Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(8.dp)).padding(bottom = 6.dp)) {} }
        Text(d.optString("site").takeIf { it.isNotBlank() && it != "null" } ?: Uri.parse(url).host.orEmpty(), style = MaterialTheme.typography.labelSmall, color = C.muted, maxLines = 1)
        Text(title, style = MaterialTheme.typography.titleSmall, color = C.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        d.optString("description").takeIf { it.isNotBlank() && it != "null" }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 3, overflow = TextOverflow.Ellipsis) }
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

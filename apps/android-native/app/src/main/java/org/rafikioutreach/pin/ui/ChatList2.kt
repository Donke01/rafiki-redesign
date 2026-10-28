package org.rafikioutreach.pin.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.ChatRow
import org.rafikioutreach.pin.data.Geo
import org.rafikioutreach.pin.data.LatLng
import org.rafikioutreach.pin.data.Presence
import org.rafikioutreach.pin.t

// Chat 2 (web c66c421, design/chat-2026-10/list.png): the chat list's compact header with the Me photo, search in place
// and ⋯; filter chips; "In the field now"; a one-line alert prompt; the Archived row at the end; the new-chat button.

/** The chat type scale (the web's --cw-fs-*): every chat text uses only these, so the chat screens read as one design and
 *  follow Settings › Text size (the theme's font scale). */
internal object CwType {
    val title = 24.sp; val name = 15.5.sp; val msg = 15.5.sp; val meta = 12.5.sp; val small = 11.5.sp; val sheet = 20.sp
    @Composable fun title() = MaterialTheme.typography.titleLarge.copy(fontSize = title, lineHeight = 28.sp, fontWeight = FontWeight.ExtraBold)
    @Composable fun name(w: FontWeight = FontWeight.Bold) = MaterialTheme.typography.titleSmall.copy(fontSize = name, lineHeight = 20.sp, fontWeight = w)
    @Composable fun msg(w: FontWeight = FontWeight.Normal) = MaterialTheme.typography.bodyLarge.copy(fontSize = msg, lineHeight = 21.sp, fontWeight = w)
    @Composable fun meta(w: FontWeight = FontWeight.Medium) = MaterialTheme.typography.bodySmall.copy(fontSize = meta, lineHeight = 16.sp, fontWeight = w)
    @Composable fun small(w: FontWeight = FontWeight.Bold) = MaterialTheme.typography.labelSmall.copy(fontSize = small, lineHeight = 14.sp, fontWeight = w)
    @Composable fun sheet() = MaterialTheme.typography.titleLarge.copy(fontSize = sheet, lineHeight = 25.sp, fontWeight = FontWeight.ExtraBold)
}

/** Chat 2 colours: the chips, the "@" pill, the new-chat button, the alert prompt (light, dark, sun). */
internal object Cw2 {
    // in a night part (RAFIKI Deep): white-on-night chips, the chosen one white with navy words, mint for unread
    @Composable private fun night() = LocalNight.current
    @Composable fun chip() = if (night()) Color(0x1AFFFFFF) else if (C === Sun) Color.White else if (C.dark) Color(0xFF1E2830) else Color(0xFFEEF0F2)
    @Composable fun chipText() = if (night()) Color(0xFFDCEAF3) else if (C === Sun) Color.Black else if (C.dark) Color(0xFFE6EEF5) else Color(0xFF141618)
    @Composable fun on() = if (night()) Color.White else if (C === Sun) Color(0xFF00562A) else if (C.dark) Color(0x333CC07A) else Color(0xFFDDF1E6)
    @Composable fun onText() = if (night()) Color(0xFF04263F) else if (C === Sun) Color.White else if (C.dark) Color(0xFF8BE3B2) else Color(0xFF11643A)
    @Composable fun at() = if (night()) Color(0x297FE3B0) else if (C === Sun) Color.White else if (C.dark) Color(0x333CC07A) else Color(0xFFE3F4EA)
    @Composable fun un() = if (night()) Deep.mint else if (C === Sun) Color(0xFF00562A) else if (C.dark) Color(0xFF3CC07A) else Color(0xFF1C8A50)
    @Composable fun fab() = if (C === Sun) Color(0xFF00562A) else Color(0xFF1C8A50)
    @Composable fun note() = if (C === Sun) Color.White else if (C.dark) Color(0x1F5BC0FF) else Color(0xFFEAF4FB)
    @Composable fun link() = if (night()) Color.White else if (C === Sun) Color(0xFF0050A0) else if (C.dark) Color(0xFF5BC0FF) else C.blue
    @Composable fun edge(): Modifier = if (C === Sun) Modifier.border(2.dp, Color.Black, RoundedCornerShape(99.dp)) else Modifier
}

/** The filter chips and the one chosen for this visit (kept while the app runs; changing it is not a step for Back). */
internal object ChatChip {
    val ALL = listOf("all", "unread", "project", "group", "team", "dm")
    var now by mutableStateOf("all")
    fun name(k: String) = when (k) {
        "unread" -> t("Unread", "Hazijasomwa"); "project" -> t("Projects", "Miradi"); "group" -> t("Groups", "Vikundi"); "team" -> t("Teams", "Timu"); "dm" -> t("People", "Watu"); else -> t("All", "Zote")
    }
    fun ok(r: ChatRow, k: String) = when (k) { "all" -> true; "unread" -> r.unread > 0 || r.mention; "dm" -> r.kind == "dm"; else -> !r.announce && r.kind == k }
    fun empty(k: String) = when (k) {
        "unread" -> t("No unread chats", "Hakuna gumzo ambazo hazijasomwa"); "project" -> t("No project chats yet", "Bado hakuna gumzo za miradi"); "group" -> t("No groups yet", "Bado hakuna vikundi")
        "team" -> t("No team chats", "Hakuna gumzo za timu"); "dm" -> t("No chats with people yet", "Bado hakuna gumzo na watu"); else -> t("No chats here", "Hakuna gumzo hapa")
    }
}

/** On a phone the RAFIKI bar steps aside on the chat list (its bell and refresh move to ⋯); a tablet keeps it. */
@Composable
internal fun chatListOwnsTop(): Boolean = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600

/** The header: the Me photo (opens Me), "Chats", search (opens in place; Back closes it) and ⋯. */
@Composable
internal fun ChatHead(m: AppModel, searching: Boolean, q: String, setQ: (String) -> Unit, openSearch: () -> Unit, closeSearch: () -> Unit, menu: @Composable () -> Unit, openMe: () -> Unit, ownTop: Boolean) {
    val hp = rememberHaptics()
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp, top = if (ownTop) 6.dp else 0.dp).testTag("chat_head"), verticalAlignment = Alignment.CenterVertically) {
        if (searching) {
            val fr = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) { hp.tap(); closeSearch() }.semantics { contentDescription = t("Close the search", "Funga utafutaji") }.testTag("chat_search_close"), contentAlignment = Alignment.Center) {
                Icon(Ic.get("back"), null, tint = C.ink, modifier = Modifier.size(22.dp))
            }
            Box(Modifier.weight(1f).heightIn(min = 44.dp).padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
                if (q.isEmpty()) Text(t("Search chats and messages", "Tafuta gumzo na jumbe"), style = CwType.name(FontWeight.Medium), color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicTextField(q, setQ, Modifier.fillMaxWidth().focusRequester(fr).testTag("chat_search"), singleLine = true, textStyle = CwType.name(FontWeight.Medium).copy(color = C.text), cursorBrush = SolidColor(C.blue))
            }
            if (q.isNotEmpty()) Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) { setQ("") }.semantics { contentDescription = t("Clear", "Futa") }.testTag("chat_search_x"), contentAlignment = Alignment.Center) {
                Icon(Ic.get("x"), null, tint = C.muted, modifier = Modifier.size(18.dp))
            }
            return@Row
        }
        if (ownTop) {
            Box(Modifier.size(46.dp).clip(CircleShape).clickable(role = Role.Button) { hp.tap(); openMe() }.semantics { contentDescription = t("Me", "Mimi") }.testTag("chat_me"), contentAlignment = Alignment.Center) {
                Box(Modifier.size(42.dp).border(2.dp, if (LocalNight.current) Color(0xFF5CC8E8) else C.blue, CircleShape).padding(3.dp)) {
                    PhotoOr(m.prefs.name, 36.dp) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF1576BD), Color(0xFF054F81)))), contentAlignment = Alignment.Center) {
                            Text(initialsOf(m.prefs.name), color = Color.White, style = CwType.meta(FontWeight.ExtraBold), maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
        }
        Text(t("Chats", "Gumzo"), style = CwType.title(), color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() }.testTag("chat_title"))
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) { hp.tap(); openSearch() }.semantics { contentDescription = t("Search chats and messages", "Tafuta gumzo na jumbe") }.testTag("chat_search_btn"), contentAlignment = Alignment.Center) {
            Icon(Ic.get("search"), null, tint = C.ink, modifier = Modifier.size(23.dp))
        }
        menu()
    }
}

/** The ⋯ menu: settings, starred, archived, a new group, mark all read; on a phone also the bell and refresh. */
@Composable
internal fun ChatMoreMenu(m: AppModel, ownTop: Boolean, openSettings: () -> Unit, openStarred: () -> Unit, openArchived: () -> Unit, newGroup: () -> Unit, openNotes: () -> Unit, refresh: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    BackStep(open) { open = false } // Back closes the menu first
    val rows by m.chat.rows.collectAsState()
    val tick by m.noteTick.collectAsState(); val act by m.activity.collectAsState(); val s by m.snap.collectAsState()
    val bell = remember(tick, act, s) { m.unread() }
    val hp = rememberHaptics()
    val un = rows.any { (it.unread > 0 || it.mention) && !it.archived }
    Box {
        Box(Modifier.size(44.dp).clip(CircleShape).background(if (open) C.tint else Color.Transparent).clickable(role = Role.Button) { hp.tap(); open = true }
            .semantics { contentDescription = t("More", "Zaidi") + if (ownTop && bell > 0) " · $bell" else "" }.testTag("chat_menu"), contentAlignment = Alignment.Center) {
            Icon(Ic.get("more"), null, tint = C.ink, modifier = Modifier.size(23.dp))
            if (ownTop && bell > 0) Box(Modifier.align(Alignment.TopEnd).padding(top = 9.dp, end = 10.dp).size(11.dp).clip(CircleShape).background(C.card).padding(2.dp).clip(CircleShape).background(C.bad).testTag("chat_menu_dot"))
        }
        Day { DropdownMenu(open, { open = false }, modifier = Modifier.background(C.card).testTag("chat_menu_pop")) {
            @Composable fun item(tag: String, icon: String, label: String, n: Int = 0, go: () -> Unit) = DropdownMenuItem(
                { Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = CwType.name(FontWeight.SemiBold), color = C.text, modifier = Modifier.weight(1f, fill = false))
                    if (n > 0) { Spacer(Modifier.width(10.dp)); Text(if (n > 99) "99+" else "$n", color = Color.White, style = CwType.small(FontWeight.ExtraBold), modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(C.bad).padding(horizontal = 7.dp, vertical = 2.dp)) }
                } },
                { open = false; go() }, leadingIcon = { Icon(ChatIc.get(icon), null, tint = C.muted, modifier = Modifier.size(21.dp)) }, modifier = Modifier.heightIn(min = 48.dp).testTag(tag))
            item("menu_settings", "cog", t("Chat settings", "Mipangilio ya gumzo"), go = openSettings)
            item("menu_starred", "star", t("Starred messages", "Jumbe zenye nyota"), go = openStarred)
            item("menu_archived", "archive", t("Archived", "Zilizohifadhiwa"), go = openArchived)
            item("menu_newgroup", "group", t("New group", "Kikundi kipya"), go = newGroup)
            if (un) item("menu_read", "check", t("Mark all as read", "Weka zote kama zimesomwa")) { m.chat.allRead() }
            if (ownTop) {
                item("menu_bell", "bell", t("Notifications", "Arifa"), bell, openNotes)
                item("menu_refresh", "undo", t("Refresh", "Onyesha upya"), go = refresh)
            }
        } }
    }
}

/** All · Unread n · Projects · Groups · Teams · People. */
@Composable
internal fun ChatChips(rows: List<ChatRow>) {
    val nUn = rows.count { (!it.archived || it.announce) && (it.unread > 0 || it.mention) }
    val hp = rememberHaptics()
    val (scroll, spot) = rememberKeepInView(ChatChip.now)
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp).testTag("chat_chips"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChatChip.ALL.forEach { k ->
            val on = ChatChip.now == k
            Row(spot(k).heightIn(min = 36.dp).clip(RoundedCornerShape(99.dp)).then(Cw2.edge()).background(if (on) Cw2.on() else Cw2.chip()).clickable(role = Role.Tab) { hp.tap(); ChatChip.now = k }
                .semantics { selected = on }.padding(horizontal = 14.dp).testTag("chat_chip_$k"), verticalAlignment = Alignment.CenterVertically) {
                Text(ChatChip.name(k), style = CwType.meta(FontWeight.Bold), color = if (on) Cw2.onText() else Cw2.chipText(), maxLines = 1, softWrap = false)
                if (k == "unread" && nUn > 0) Text(" $nUn", style = CwType.meta(FontWeight.ExtraBold), color = if (on) Cw2.onText() else Cw2.chipText(), maxLines = 1, softWrap = false, modifier = Modifier.testTag("chat_chip_unread_n"))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- In the field now

/** Someone in the field row: "site" (sharing their location now: admins only), "on" (online) or "" (seen lately). */
internal data class FieldPerson(val name: String, val k: String, val label: String, val t: Long, val o: Int)

/** Admins: the team map (who shares their location, and today's visits), kept two minutes. Field users never get it. */
internal object ChatField {
    var map by mutableStateOf<TeamMapData?>(null)
    var at = 0L
    suspend fun load(m: AppModel, force: Boolean = false) {
        if (m.snap.value?.admin != true) return
        if (!force && at > 0 && System.currentTimeMillis() - at < 120_000) return
        at = System.currentTimeMillis()
        val r = m.api.adminGet("teammap"); if (r.ok) map = TeamMapData.from(r.json)
    }
    /** Who is online or was on in the last 3 hours; admins also see who shares their location ("At <project>", else "On the road"). */
    fun people(m: AppModel, presence: Map<String, Presence>, me: String, now: Long = System.currentTimeMillis()): List<FieldPerson> {
        val out = mutableListOf<FieldPerson>(); val got = HashSet<String>()
        val tm = if (m.snap.value?.admin == true) map else null
        val snap = m.snap.value
        tm?.people?.forEach { p ->
            val at = org.rafikioutreach.pin.data.chatMs(p.at)
            if (p.name.isBlank() || p.name.equals(me, true) || now - at >= 3_600_000L) return@forEach
            got += p.name.lowercase()
            // at a project: a visit there in the last 3 hours, else the nearest project within 500 m
            val v = tm.visits.filter { it.by.equals(p.name, true) && now - org.rafikioutreach.pin.data.chatMs(it.at) < 3 * 3_600_000L }.maxByOrNull { org.rafikioutreach.pin.data.chatMs(it.at) }
            val pr = v?.let { x -> snap?.projects?.firstOrNull { it.id == x.id } } ?: snap?.projects?.filter { it.gps != null }?.map { it to Geo.metres(p.ll, it.gps!!) }?.filter { it.second < 500 }?.minByOrNull { it.second }?.first
            out += FieldPerson(p.name, "site", if (pr != null) t("At ${pr.title}", "Yuko ${pr.title}") else t("On the road", "Njiani"), at, 0)
        }
        presence.forEach { (n, p) ->
            if (n.isBlank() || n.equals(me, true) || n.lowercase() in got) return@forEach
            if (p.online) out += FieldPerson(n, "on", t("Online", "Mtandaoni"), now, 1)
            else if (p.seen > 0 && now - p.seen < 3 * 3_600_000L) out += FieldPerson(n, "", agoShort(now - p.seen), p.seen, 2)
        }
        return out.sortedWith(compareBy<FieldPerson> { it.o }.thenByDescending { it.t }.thenBy { it.name }).take(24)
    }
    private fun agoShort(ms: Long): String { val min = ms / 60_000; return if (min < 60) t("$min min ago", "dakika $min zilizopita") else t("${min / 60} h ago", "saa ${min / 60} zilizopita") }
}

/** "In the field now": avatars with a ring (green gradient and a pin while sharing at a site, green when online). Tap: a DM. */
@Composable
internal fun ChatFieldRow(m: AppModel, presence: Map<String, Presence>) {
    LaunchedEffect(Unit) { ChatField.load(m) }
    val map = ChatField.map
    val people = remember(presence, map, m.chat.me) { ChatField.people(m, presence, m.chat.me) }
    if (people.isEmpty()) return
    val hp = rememberHaptics()
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).testTag("chat_field")) {
        Row(Modifier.padding(start = 16.dp, bottom = 8.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Cw2.un())); Spacer(Modifier.width(7.dp))
            Text(t("In the field now", "Uwanjani sasa").uppercase(if (org.rafikioutreach.pin.Lang.sw) java.util.Locale("sw") else java.util.Locale.US), style = CwType.small(FontWeight.ExtraBold).copy(letterSpacing = 0.9.sp), color = if (LocalNight.current) Deep.ice else C.muted, modifier = Modifier.semantics { heading() })
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            people.forEach { p ->
                Column(Modifier.width(80.dp).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button) { hp.tap(); ChatNav.dm(p.name) }
                    .semantics(mergeDescendants = true) { contentDescription = p.name + ", " + p.label }.padding(vertical = 2.dp).testTag("chat_field_${p.name}"), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(58.dp)) {
                        val ring = when (p.k) { "site" -> if (LocalNight.current) Brush.sweepGradient(listOf(Color(0xFF5CE0A0), Color(0xFF5CC8E8), Color(0xFF5CE0A0))) else Brush.sweepGradient(listOf(Color(0xFF1C9C5A), Color(0xFF0A78C2), Color(0xFF1C9C5A))); "on" -> SolidColor(Cw2.un()); else -> SolidColor(if (LocalNight.current) Color(0x40FFFFFF) else C.line) }
                        Box(Modifier.size(58.dp).clip(CircleShape).background(ring).padding(2.5.dp).clip(CircleShape).background(C.card).padding(2.5.dp)) { PersonAvatar(p.name, 48.dp) }
                        if (p.k == "site") Box(Modifier.align(Alignment.BottomEnd).offset(2.dp, 1.dp).size(22.dp).shadow(2.dp, CircleShape).clip(CircleShape).background(C.card).testTag("chat_field_pin"), contentAlignment = Alignment.Center) {
                            Icon(Ic.get("pin"), null, tint = Cw2.un(), modifier = Modifier.size(14.dp))
                        }
                    }
                    Text(p.name.substringBefore(' '), style = CwType.meta(FontWeight.SemiBold), color = C.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp))
                    FitWords(p.label, CwType.small(if (p.k.isEmpty()) FontWeight.SemiBold else FontWeight.Bold), if (p.k.isEmpty()) C.muted else Cw2.un(), Modifier.fillMaxWidth().padding(horizontal = 2.dp), Alignment.TopCenter, 3)
                }
            }
        }
    }
    if (!LocalNight.current) androidx.compose.material3.HorizontalDivider(color = if (C === Sun) Color.Black else C.line, thickness = if (C === Sun) 2.dp else 1.dp)
}

// ---------------------------------------------------------------------------------------------- the alert prompt

/** "Get message alerts · Turn on ×": one line, only while Android hasn't been asked (or said no) and it isn't closed. */
@Composable
internal fun ChatAlertPrompt(m: AppModel) {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("rafiki-pin", 0) }
    var shut by remember { mutableStateOf(sp.getBoolean("chatNoteAsk", false)) }
    var allowed by remember { mutableStateOf(org.rafikioutreach.pin.data.AlertWorker.allowed(ctx)) }
    if (shut || allowed || android.os.Build.VERSION.SDK_INT < 33) return
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> allowed = ok; if (ok) { m.prefs.phoneAlerts = true; org.rafikioutreach.pin.data.ChatWorker.schedule(ctx.applicationContext) } else { shut = true; sp.edit().putBoolean("chatNoteAsk", true).apply() } }
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp).fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(Cw2.note())
        .then(if (C === Sun) Modifier.border(2.dp, Color.Black, RoundedCornerShape(12.dp)) else Modifier).padding(start = 12.dp, end = 4.dp).testTag("chat_alerts"), verticalAlignment = Alignment.CenterVertically) {
        Icon(Ic.get("bell"), null, tint = C.blue, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text(t("Get message alerts", "Pata arifa za jumbe"), style = CwType.meta(), color = C.text, modifier = Modifier.weight(1f))
        Text(t("Turn on", "Washa"), style = CwType.meta(FontWeight.ExtraBold), color = Cw2.link(), maxLines = 1, softWrap = false,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { runCatching { ask.launch(android.Manifest.permission.POST_NOTIFICATIONS) } }.heightIn(min = 40.dp).padding(horizontal = 8.dp, vertical = 11.dp).testTag("chat_alerts_on"))
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { shut = true; sp.edit().putBoolean("chatNoteAsk", true).apply() }.semantics { contentDescription = t("Not now", "Si sasa") }.testTag("chat_alerts_x"), contentAlignment = Alignment.Center) {
            Icon(Ic.get("x"), null, tint = C.muted, modifier = Modifier.size(16.dp))
        }
    }
}

/** Archived: a small grey row at the end of the list. */
@Composable
internal fun ChatArchivedRow(n: Int, open: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = open).heightIn(min = 52.dp).padding(horizontal = 16.dp).testTag("chat_archived_row"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(52.dp), contentAlignment = Alignment.Center) { Icon(ChatIc.get("archive"), null, tint = C.muted, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(13.dp))
        Text(t("Archived", "Zilizohifadhiwa"), style = CwType.meta(FontWeight.SemiBold), color = C.muted, modifier = Modifier.weight(1f))
        Text("$n", style = CwType.meta(FontWeight.Bold), color = C.muted)
    }
}

/** New chat: a green rounded square above the bottom bar. */
@Composable
internal fun ChatFab(modifier: Modifier, onClick: () -> Unit) {
    val hp = rememberHaptics()
    Box(modifier.size(56.dp).shadow(10.dp, RoundedCornerShape(18.dp), spotColor = Color(0x591C8A50)).clip(RoundedCornerShape(18.dp)).then(if (Deep.on()) Modifier.background(Deep.go()) else Modifier.background(Cw2.fab()))
        .then(if (C === Sun) Modifier.border(2.dp, Color.Black, RoundedCornerShape(18.dp)) else Modifier)
        .clickable(role = Role.Button) { hp.tap(); onClick() }.semantics { contentDescription = t("New chat", "Gumzo jipya") }.testTag("chat_new"), contentAlignment = Alignment.Center) {
        Icon(ChatIc.get("newchat"), null, tint = Color.White, modifier = Modifier.size(25.dp))
    }
}

/** A project's picture in the chat list and the room header: its photo with a small blue water drop, else its kind on a coloured tile. */
@Composable
internal fun ProjectChatAvatar(m: AppModel?, row: ChatRow, size: Dp) {
    val p = m?.snap?.collectAsState()?.value?.projects?.firstOrNull { it.id == row.project }
    val url = row.pphoto.ifBlank { null }?.let { if (it.startsWith("http")) it else m?.api?.server + it } ?: p?.photo?.let { m?.api?.photoUrl(it, 160) }
    val (icon, hue) = projectKind(p?.type.orEmpty())
    val hu = C.hue(hue)
    Box(Modifier.size(size)) {
        if (url != null && m != null) NetImage(url, Modifier.size(size).clip(CircleShape).background(hu.bg).testTag("pphoto")) {
            Box(Modifier.size(size).clip(CircleShape).background(hu.bg), contentAlignment = Alignment.Center) { Icon(ChatIc.get(icon), null, tint = hu.fg, modifier = Modifier.size(size * 0.48f)) }
        }
        else Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(hu.bg).testTag("ptile"), contentAlignment = Alignment.Center) { Icon(ChatIc.get(icon), null, tint = hu.fg, modifier = Modifier.size(size * 0.5f)) }
        if (url != null) {
            val b = (size * 0.42f).coerceIn(16.dp, 22.dp)
            Box(Modifier.align(Alignment.BottomEnd).offset(3.dp, 3.dp).size(b).clip(RoundedCornerShape(b * 0.3f)).background(C.card).padding(2.dp).clip(RoundedCornerShape(b * 0.3f))
                .background(if (C === Sun) Color(0xFF0050A0) else Color(0xFF0A78C2)).testTag("pdrop"), contentAlignment = Alignment.Center) {
                Icon(Ic.get("drop"), null, tint = Color.White, modifier = Modifier.size(b * 0.58f))
            }
        }
    }
}

/** A project's kind as an icon and a colour (the web's mpaTyIcon, simplified to the app's icons). */
internal fun projectKind(type: String): Pair<String, String> {
    val s = type.lowercase()
    return when {
        "school" in s || "class" in s -> "school" to "purple"
        "health" in s || "clinic" in s || "dispensary" in s -> "heart" to "coral"
        "farm" in s || "pig" in s || "garden" in s -> "leaf" to "green"
        "church" in s -> "house" to "amber"
        else -> "drop" to "blue"
    }
}

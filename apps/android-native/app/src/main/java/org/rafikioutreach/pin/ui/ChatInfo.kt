package org.rafikioutreach.pin.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.ChatRow
import org.rafikioutreach.pin.t

/**
 * A chat's info page: its picture, name and description; members with admins and who's online; for admins: add or
 * remove members, make admins, change the name, description and photo, "Only admins can send messages", the invite
 * link (copy, share, reset). For everyone: mute (8 hours, a week, always), the sound, the wallpaper, leave.
 * A team group's members follow the team list, so they're not added or removed here.
 */
@Composable
fun ChatInfoScreen(m: AppModel, c: String, back: () -> Unit, openChat: (String) -> Unit, openProject: (String) -> Unit) {
    val hub = m.chat
    val rows by hub.rows.collectAsState()
    val presence by hub.presence.collectAsState()
    val tick by hub.tick.collectAsState()
    val row = rows.firstOrNull { it.id == c } ?: run { Text(t("This chat isn’t on this phone any more.", "Gumzo hili halipo tena kwenye simu hii."), color = C.muted, modifier = Modifier.padding(24.dp)); return }
    val me = hub.me
    val iAdmin = row.admin(me) || (m.snap.collectAsState().value?.admin == true && row.kind == "team")
    val ctx = LocalContext.current
    val scope = rememberScope()
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var member by remember { mutableStateOf<String?>(null) }
    var said by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(said) { if (said != null) { kotlinx.coroutines.delay(3000); said = null } }
    val photoPick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            said = "" to t("Uploading…", "Inapakia…")
            val f = withContext(Dispatchers.IO) { ChatPrep.copy(ctx, uri, "jpg")?.let { ChatPrep.photo(ctx, it, 640) } }
            val bytes = f?.local?.let { withContext(Dispatchers.IO) { java.io.File(it).readBytes() } }
            val up = bytes?.let { hub.chat.media(it, "photo", "group.jpg", "image/jpeg") }
            said = if (up?.ok == true) (hub.updateGroup(c, JSONObject().put("photo", up.json.optString("id")))?.let { "bad" to it } ?: ("ok" to t("Photo changed ✓", "Picha imebadilishwa ✓")))
                else "bad" to t("Couldn’t upload the photo. Check the signal.", "Imeshindwa kupakia picha. Angalia mtandao.")
        }
    }
    val other = row.other(me)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 110.dp).testTag("chat_info")) {
        // who or what this is
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.clickable(enabled = iAdmin && row.kind == "group", role = Role.Button) { photoPick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                ChatAvatar(row, me, 104.dp, row.kind == "dm" && presence[other]?.online == true, m = m)
                if (iAdmin && row.kind == "group") Box(Modifier.align(Alignment.BottomEnd).size(32.dp).clip(CircleShape).background(C.card).border(1.dp, C.line, CircleShape).testTag("info_photo"), contentAlignment = Alignment.Center) {
                    Icon(Ic.get("camera"), t("Change photo", "Badilisha picha"), tint = C.blue, modifier = Modifier.size(16.dp))
                }
            }
            Text(chatTitle(row, me), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold), color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp).testTag("info_title"))
            Text(when (row.kind) {
                "dm" -> presence[other]?.let { if (it.online) t("online", "yupo mtandaoni") else lastSeen(it.seen) }.orEmpty()
                "team" -> t("Team group", "Kikundi cha timu") + " · " + t("${row.members.size} members", "wanachama ${row.members.size}")
                "project" -> t("Project thread", "Mazungumzo ya mradi") + " · " + t("${row.members.size} people", "watu ${row.members.size}")
                else -> t("Group", "Kikundi") + " · " + t("${row.members.size} members", "wanachama ${row.members.size}")
            }, style = MaterialTheme.typography.bodyMedium, color = C.muted, textAlign = TextAlign.Center)
            if (row.description.isNotBlank()) Text(row.description, style = MaterialTheme.typography.bodyMedium, color = C.text, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp).testTag("info_desc"))
            said?.let { (tone, text) -> Msg(text, tone) }
        }
        Row(Modifier.padding(top = 14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (row.kind == "dm") Btn(t("Contact", "Mawasiliano"), { Contact.show(other) }, Modifier.weight(1f).testTag("info_contact"), icon = "person", light = true, small = true, outline = true)
            if (row.kind == "project" && row.project.isNotBlank()) Btn(t("Open project", "Fungua mradi"), { openProject(row.project) }, Modifier.weight(1f).testTag("info_project"), icon = "drop", light = true, small = true, outline = true)
            if (iAdmin && row.kind == "group") Btn(t("Edit", "Hariri"), { sheet = "edit" }, Modifier.weight(1f).testTag("info_edit"), icon = "edit", light = true, small = true, outline = true)
        }

        InfoCard(t("NOTIFICATIONS", "ARIFA")) {
            val muteText = when { !row.muted() -> t("Off", "Zimezimwa"); row.mutedUntil == ChatRow.ALWAYS -> t("Always", "Daima"); else -> t("Until ", "Hadi ") + Dates.dateTime(java.time.Instant.ofEpochMilli(row.mutedUntil)) }
            SheetRow("info_mute", "mute", t("Mute notifications", "Nyamazisha arifa"), value = if (row.muted()) muteText else t("Not muted", "Hazijanyamazishwa")) { if (row.muted()) hub.mute(c, 0) else sheet = "mute" }
            SwitchRow("info_sound", t("Sound for new messages", "Sauti kwa ujumbe mpya"), !hub.quiet(c).also { tick }) { hub.setQuiet(c, !it) }
        }
        InfoCard(t("CHAT", "GUMZO")) {
            SheetRow("info_bubble", "bubble", t("My bubble colour", "Rangi ya viputo vyangu"), value = bubbleName(row.bubble)) { sheet = "bubble" }
            SheetRow("info_wall", "wall", t("Wallpaper", "Mandhari"), value = wallLabel(row.wallpaper)) { sheet = "wall" }
            SheetRow("info_pin", "pin", if (row.pinned) t("Unpin chat", "Ondoa kibandiko") else t("Pin chat", "Bandika gumzo")) { hub.pin(c, !row.pinned) }
            if (row.isGroup) {
                if (iAdmin) SwitchRow("info_only", t("Only admins can send messages", "Wasimamizi pekee wanaweza kutuma ujumbe"), row.onlyAdmins) { on -> scope.launch { hub.updateGroup(c, JSONObject().put("onlyAdmins", on))?.let { said = "bad" to it } } }
                else if (row.onlyAdmins) Text(t("Only admins can send messages", "Wasimamizi pekee wanaweza kutuma ujumbe"), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(8.dp))
                if (iAdmin && row.kind == "group") SheetRow("info_link", "link", t("Invite link", "Kiungo cha mwaliko"), value = t("Anyone on the team with the link can join", "Yeyote kwenye timu mwenye kiungo anaweza kujiunga")) { sheet = "link" }
            }
        }
        if (row.isGroup) InfoCard(t("${row.members.size} MEMBERS", "WANACHAMA ${row.members.size}")) {
            if (row.kind == "team") Text(t("Members follow the team list (by country).", "Wanachama wanafuata orodha ya timu (kwa nchi)."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            if (iAdmin && row.kind != "team") SheetRow("info_add", "plus", t("Add members", "Ongeza wanachama")) { sheet = "add" }
            row.members.sortedWith(compareBy<String>({ !it.equals(me, true) }, { !row.admin(it) }, { it })).forEach { n ->
                val p = presence[n]
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button) { if (!n.equals(me, true)) member = n }.heightIn(min = 56.dp).padding(horizontal = 6.dp).testTag("member_$n"),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box { PersonAvatar(n, 40.dp); if (p?.online == true) Box(Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape).background(C.card).padding(2.dp).clip(CircleShape).background(ChatLook.online)) }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(if (n.equals(me, true)) t("You", "Wewe") else n, style = MaterialTheme.typography.titleSmall, color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val s = if (p?.online == true) t("online", "yupo mtandaoni") else lastSeen(p?.seen ?: 0)
                        if (s.isNotBlank() && !n.equals(me, true)) Text(s, style = MaterialTheme.typography.bodySmall, color = if (p?.online == true) ChatLook.online else C.muted, maxLines = 1)
                    }
                    if (row.admin(n)) Text(t("Admin", "Msimamizi"), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold), color = C.hue("green").fg, maxLines = 1, softWrap = false,
                        modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(C.hue("green").bg).padding(horizontal = 8.dp, vertical = 3.dp).testTag("admin_$n"))
                }
            }
        }
        if (row.kind == "group") InfoCard("") {
            SheetRow("info_leave", "exit", t("Leave group", "Ondoka kwenye kikundi"), red = true) { sheet = "leave" }
        }
    }
    when (sheet) {
        "mute" -> MuteSheet(m, c) { sheet = null }
        "wall" -> TeamSubSheet("wall_sheet", { sheet = null }) { hide ->
            Text(t("Wallpaper", "Mandhari"), style = MaterialTheme.typography.titleLarge, color = C.ink, modifier = Modifier.padding(bottom = 10.dp))
            // RAFIKI's own first (water, savanna, night), then the simple patterns; two rows of four
            WALLS.chunked(4).forEach { r ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    r.forEach { k ->
                        val on = row.wallpaper == k || (k.isBlank() && row.wallpaper == "none")
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable(role = Role.RadioButton) { hide { scope.launch { hub.prefs(c, JSONObject().put("wallpaper", k)) } } }.testTag("wall_${k.ifBlank { "none" }}"),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.fillMaxWidth().aspectRatio(0.62f).clip(RoundedCornerShape(12.dp)).background(C.bg).border(if (on) 3.dp else 1.dp, if (on) C.blue else C.line, RoundedCornerShape(12.dp))) {
                                ChatWall(k, Modifier.matchParentSize())
                                // a bubble in my colour, to see how it reads
                                Box(Modifier.align(Alignment.BottomEnd).padding(6.dp).size(28.dp, 12.dp).clip(RoundedCornerShape(6.dp)).background(bubbleColor(row.bubble)))
                            }
                            FitWords(wallLabel(k), MaterialTheme.typography.labelSmall, C.ink, Modifier.padding(top = 4.dp), lines = 1)
                        }
                    }
                }
            }
        }
        "bubble" -> TeamSubSheet("bubble_sheet", { sheet = null }) { hide ->
            Text(t("My bubble colour", "Rangi ya viputo vyangu"), style = MaterialTheme.typography.titleLarge, color = C.ink)
            Text(t("Only you see it, in this chat.", "Unaiona wewe tu, kwenye gumzo hili."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
            BUBBLES.chunked(4).forEach { r ->
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    r.forEach { k ->
                        val on = row.bubble == k
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable(role = Role.RadioButton) { hide { hub.bubble(c, k) } }.padding(vertical = 4.dp).testTag("bubble_${k.ifBlank { "usual" }}"), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(46.dp).clip(CircleShape).background(bubbleColor(k)).border(if (on) 3.dp else 1.dp, if (on) C.blue else C.line, CircleShape), contentAlignment = Alignment.Center) {
                                if (on) Icon(Ic.get("check"), null, tint = C.blue, modifier = Modifier.size(20.dp))
                            }
                            FitWords(bubbleName(k), MaterialTheme.typography.labelSmall, C.ink, Modifier.padding(top = 4.dp), lines = 1)
                        }
                    }
                }
            }
        }
        "edit" -> EditGroupSheet(m, row, { sheet = null }) { said = it }
        "add" -> TeammatePicker(m, t("Add members", "Ongeza wanachama"), row.members, multi = true, close = { sheet = null }) { names -> scope.launch { hub.members(c, add = names)?.let { said = "bad" to it } ?: run { said = "ok" to t("Added ✓", "Wameongezwa ✓") } } }
        "link" -> LinkSheet(m, c) { sheet = null }
        "leave" -> TeamSubSheet("leave_sheet", { sheet = null }) { hide ->
            Text(t("Leave “${row.title}”?", "Uondoke kwenye “${row.title}”?"), style = MaterialTheme.typography.titleLarge, color = C.ink)
            Text(t("You won’t get its messages any more. An admin can add you back.", "Hutapata tena ujumbe wake. Msimamizi anaweza kukurudisha."), style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.padding(vertical = 8.dp))
            Btn(t("Leave group", "Ondoka kwenye kikundi"), { hide { scope.launch { if (hub.leave(c) == null) back() } } }, Modifier.fillMaxWidth().testTag("leave_go"), color = C.bad, icon = "x")
        }
    }
    member?.let { n -> MemberSheet(m, row, n, iAdmin, { member = null }, openChat) { said = it } }
}

@Composable
private fun InfoCard(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.card).border(if (C.glassy) 1.dp else 2.dp, C.line, RoundedCornerShape(22.dp)).padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 6.dp)) {
        if (label.isNotBlank()) Text(label, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp, fontWeight = FontWeight.ExtraBold), color = C.muted, modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp))
        content()
    }
}

@Composable
private fun SwitchRow(tag: String, label: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Switch) { set(!on) }.heightIn(min = 52.dp).padding(start = 8.dp, end = 6.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.weight(1f))
        Switch(on, set, colors = SwitchDefaults.colors(checkedTrackColor = ChatLook.online))
    }
}

/** A member: message them, see their contact; admins can make or remove admins and remove them. */
@Composable
private fun MemberSheet(m: AppModel, row: ChatRow, n: String, iAdmin: Boolean, close: () -> Unit, openChat: (String) -> Unit, said: (Pair<String, String>) -> Unit) {
    val scope = rememberScope()
    TeamSubSheet("member_sheet", close) { hide ->
        Row(verticalAlignment = Alignment.CenterVertically) { PersonAvatar(n, 44.dp); Spacer(Modifier.width(12.dp)); Text(n, style = MaterialTheme.typography.titleLarge, color = C.ink) }
        Spacer(Modifier.height(8.dp))
        SheetRow("mem_msg", "chat", t("Message $n", "Mtumie $n ujumbe")) { hide { ChatNav.dm(n) } }
        SheetRow("mem_contact", "person", t("View contact", "Tazama mawasiliano")) { hide { Contact.show(n) } }
        if (iAdmin && row.kind != "team") {
            if (row.admin(n)) SheetRow("mem_unadmin", "people", t("Remove as admin", "Ondoa kama msimamizi")) { hide { scope.launch { m.chat.members(row.id, unadmin = listOf(n))?.let { said("bad" to it) } } } }
            else SheetRow("mem_admin", "crown", t("Make group admin", "Mfanye msimamizi wa kikundi")) { hide { scope.launch { m.chat.members(row.id, admin = listOf(n))?.let { said("bad" to it) } } } }
            SheetRow("mem_remove", "trash", t("Remove from group", "Mwondoe kwenye kikundi"), red = true) { hide { scope.launch { m.chat.members(row.id, remove = listOf(n))?.let { said("bad" to it) } ?: said("ok" to t("$n was removed. They lose access at once.", "$n ameondolewa. Amepoteza ufikiaji mara moja.")) } } }
        }
    }
}

@Composable
private fun EditGroupSheet(m: AppModel, row: ChatRow, close: () -> Unit, said: (Pair<String, String>) -> Unit) {
    val scope = rememberScope()
    var title by remember { mutableStateOf(row.title) }
    var desc by remember { mutableStateOf(row.description) }
    var busy by remember { mutableStateOf(false) }
    TeamSubSheet("edit_group", close) { hide ->
        Text(t("Edit group", "Hariri kikundi"), style = MaterialTheme.typography.titleLarge, color = C.ink)
        Field(title, { title = it }, t("Name", "Jina"), Modifier.fillMaxWidth().padding(top = 8.dp))
        Field(desc, { desc = it }, t("Description (optional)", "Maelezo (si lazima)"), Modifier.fillMaxWidth().padding(top = 8.dp), lines = 3)
        Btn(t("Save", "Hifadhi"), {
            busy = true
            scope.launch { val e = m.chat.updateGroup(row.id, JSONObject().put("title", title.trim()).put("description", desc.trim())); busy = false; if (e != null) said("bad" to e) else hide { said("ok" to t("Saved ✓", "Imehifadhiwa ✓")) } }
        }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("edit_save"), enabled = !busy && title.isNotBlank(), icon = "check")
    }
}

/** The invite link: copy, share, or reset it (the old one stops working). */
@Composable
private fun LinkSheet(m: AppModel, c: String, close: () -> Unit) {
    val scope = rememberScope()
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var url by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { val (u, e) = m.chat.link(c, false); url = u; err = e }
    TeamSubSheet("link_sheet", close) { _ ->
        Text(t("Invite link", "Kiungo cha mwaliko"), style = MaterialTheme.typography.titleLarge, color = C.ink)
        Text(t("A teammate who opens it in RAFIKI Pin joins this group. Donors can’t join.", "Mwenzako akikifungua kwenye RAFIKI Pin anajiunga na kikundi hiki. Wafadhili hawawezi kujiunga."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(vertical = 6.dp))
        val u = url
        when {
            err != null -> Msg(err, "bad")
            u == null -> Text(t("Loading…", "Inapakia…"), color = C.muted)
            else -> {
                Text(u, style = MaterialTheme.typography.bodyMedium, color = C.blue, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.hue("blue").bg).padding(12.dp).testTag("link_url"))
                SheetRow("link_copy", "copy", t("Copy link", "Nakili kiungo")) { clip.setText(AnnotatedString(u)); note = t("Copied ✓", "Imenakiliwa ✓") }
                SheetRow("link_share", "share", t("Share link", "Shiriki kiungo")) { runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, u), null)) } }
                SheetRow("link_reset", "refresh", t("Reset link", "Weka kiungo kipya"), red = true, value = t("The old link stops working", "Kiungo cha zamani kinaacha kufanya kazi")) {
                    scope.launch { val (n, e) = m.chat.link(c, true); if (n != null) { url = n; note = t("New link ready ✓", "Kiungo kipya kiko tayari ✓") } else err = e }
                }
                note?.let { Msg(it, "ok") }
            }
        }
    }
}

/** New chat: a teammate (direct message), a new group, or joining with a link. */
@Composable
fun NewChatScreen(m: AppModel, openChat: (String) -> Unit) {
    val hub = m.chat
    val team by m.team.collectAsState()
    val presence by hub.presence.collectAsState()
    LaunchedEffect(Unit) { m.loadTeam() }
    var q by rememberSaveable { mutableStateOf("") }
    var grouping by rememberSaveable { mutableStateOf(false) }
    val chosen = remember { mutableStateListOf<String>() }
    var naming by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberScope()
    BackStep(grouping) { grouping = false; chosen.clear() }
    val people = team.orEmpty().map { it.name }.filter { n -> !n.equals(hub.me, true) && (q.isBlank() || n.contains(q.trim(), true)) }.sorted()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 110.dp).testTag("chat_newscreen")) {
        Text(if (grouping) t("New group", "Kikundi kipya") else t("New chat", "Gumzo jipya"), style = MaterialTheme.typography.headlineMedium, color = C.ink, modifier = Modifier.padding(start = 20.dp, top = 8.dp))
        if (grouping) Text(if (chosen.isEmpty()) t("Choose the people", "Chagua watu") else t("${chosen.size} chosen", "${chosen.size} wamechaguliwa"), style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.padding(start = 20.dp))
        ChatSearchField(q, { q = it }, t("Search the team", "Tafuta kwenye timu"), "new_search")
        if (!grouping) {
            SheetRowPad("new_group", "group", t("New group", "Kikundi kipya")) { grouping = true }
            SheetRowPad("new_join", "link", t("Join with a link", "Jiunge kwa kiungo")) { joining = true }
            ChatLabel(t("THE TEAM", "TIMU"), "new_team_label")
        }
        err?.let { Box(Modifier.padding(horizontal = 20.dp)) { Msg(it, "bad") } }
        if (team == null) Text(t("Loading…", "Inapakia…"), color = C.muted, modifier = Modifier.padding(20.dp))
        people.forEach { n ->
            val on = n in chosen
            Row(Modifier.fillMaxWidth().clickable(role = if (grouping) Role.Checkbox else Role.Button) {
                if (grouping) { if (on) chosen.remove(n) else chosen.add(n) }
                else if (busy == null) { busy = n; scope.launch { val (id, e) = hub.openDm(n); busy = null; if (id != null) openChat(id) else err = e } }
            }.heightIn(min = 60.dp).padding(horizontal = 20.dp).testTag("new_$n"), verticalAlignment = Alignment.CenterVertically) {
                Box { PersonAvatar(n, 44.dp); if (presence[n]?.online == true) Box(Modifier.align(Alignment.BottomEnd).size(13.dp).clip(CircleShape).background(C.card).padding(2.dp).clip(CircleShape).background(ChatLook.online)) }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(n, style = MaterialTheme.typography.titleSmall, color = C.ink)
                    team?.firstOrNull { it.name == n }?.home?.let { org.rafikioutreach.pin.data.COUNTRIES[it] }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = C.muted) }
                }
                if (busy == n) androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = C.blue)
                if (grouping) Box(Modifier.size(24.dp).clip(CircleShape).background(if (on) ChatLook.online else Color.Transparent).border(2.dp, if (on) ChatLook.online else C.edge, CircleShape), contentAlignment = Alignment.Center) {
                    if (on) Icon(Ic.get("check"), null, tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
        if (grouping) Btn(t("Next", "Endelea"), { naming = true }, Modifier.padding(20.dp).fillMaxWidth().testTag("group_next"), enabled = chosen.isNotEmpty(), icon = "arrow")
    }
    if (naming) {
        var title by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        var b by remember { mutableStateOf(false) }
        TeamSubSheet("group_name_sheet", { naming = false }) { hide ->
            Text(t("Name the group", "Kipe kikundi jina"), style = MaterialTheme.typography.titleLarge, color = C.ink)
            Text(chosen.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 4.dp))
            Field(title, { title = it }, t("Group name", "Jina la kikundi"), Modifier.fillMaxWidth().padding(top = 8.dp), placeholder = t("e.g. Kitale repairs", "mf. Matengenezo Kitale"))
            Field(desc, { desc = it }, t("Description (optional)", "Maelezo (si lazima)"), Modifier.fillMaxWidth().padding(top = 8.dp), lines = 2)
            Btn(t("Create group", "Unda kikundi"), {
                b = true
                scope.launch { val (id, e) = hub.newGroup(title, chosen.toList(), desc); b = false; if (id != null) hide { openChat(id) } else err = e }
            }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("group_create"), enabled = !b && title.isNotBlank(), icon = "check")
        }
    }
    if (joining) {
        var link by remember { mutableStateOf("") }
        var b by remember { mutableStateOf(false) }
        TeamSubSheet("join_sheet", { joining = false }) { hide ->
            Text(t("Join with a link", "Jiunge kwa kiungo"), style = MaterialTheme.typography.titleLarge, color = C.ink)
            Field(link, { link = it }, t("Invite link", "Kiungo cha mwaliko"), Modifier.fillMaxWidth().padding(top = 8.dp))
            Btn(t("Join", "Jiunge"), {
                b = true
                scope.launch { val (id, e) = hub.join(joinToken(link) ?: link.trim()); b = false; if (id != null) hide { openChat(id) } else err = e }
            }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("join_go"), enabled = !b && link.isNotBlank(), icon = "arrow")
        }
    }
}

/** The token in an invite link: …/pin#join=TOKEN, …#chat/join/TOKEN or …?join=TOKEN. */
fun joinToken(link: String): String? = Regex("(?:join[=/])([A-Za-z0-9_-]{6,})").find(link)?.groupValues?.get(1)

@Composable
private fun SheetRowPad(tag: String, icon: String, label: String, onClick: () -> Unit) = Box(Modifier.padding(horizontal = 14.dp)) { SheetRow(tag, icon, label, onClick = onClick) }

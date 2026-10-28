package org.rafikioutreach.pin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.ChatHub
import org.rafikioutreach.pin.data.ChatRow
import org.rafikioutreach.pin.data.Msg
import org.rafikioutreach.pin.data.preview
import org.rafikioutreach.pin.t
import java.io.File
import java.time.Instant

/** Hold a message: a reaction, or Reply, Forward, Copy, Star, Info, Edit (own, 15 min), Delete, and "Turn into a job". */
@Composable
internal fun MsgSheet(m: AppModel, msg: Msg, row: ChatRow?, close: () -> Unit, onReply: () -> Unit, onEdit: () -> Unit, openChat: (String) -> Unit, openProject: (String, String) -> Unit, office: (String) -> Unit,
                       onSelect: () -> Unit = {}, onDelete: (() -> Unit)? = null, story: (String, List<File>) -> Unit = { _, _ -> }) {
    val hub = m.chat
    val me = hub.me
    val mine = msg.from.equals(me, true)
    val admin = m.snap.collectAsState().value?.admin == true
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    val scope = rememberScope()
    var sub by remember { mutableStateOf<String?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    val canEdit = mine && !msg.queued && msg.kind == "text" && !msg.deleted && System.currentTimeMillis() - msg.at < 15 * 60_000
    // the project this message is about: the thread's, a project link in it, or a system note's
    val pid = row?.project?.takeIf { it.isNotBlank() } ?: msg.sys?.project?.takeIf { it.isNotBlank() } ?: Regex("pin\\.rafikioutreach\\.org/pin#p[/=]([A-Za-z0-9_-]+)").find(msg.text)?.groupValues?.get(1)
    TeamSubSheet("msg_sheet", close) { hide ->
        if (!msg.deleted && !msg.queued && msg.sys == null) {
            // reactions: 👍 ❤️ 😂 😮 😢 🙏 (yours is circled; tap it again to take it away)
            val myR = msg.reacts.filterValues { it.any { n -> n.equals(me, true) } }.keys.firstOrNull()
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(99.dp)).background(C.hue("slate").bg).padding(6.dp).testTag("react_bar"), horizontalArrangement = Arrangement.SpaceEvenly) {
                ChatHub.EMOJI.forEach { e ->
                    Box(Modifier.size(44.dp).clip(CircleShape).background(if (e == myR) C.card else Color.Transparent).clickable(role = Role.Button) { hide { scope.launch { hub.react(msg, e) } } }
                        .semantics { contentDescription = t("React $e", "Jibu kwa $e") }.testTag("react_$e"), contentAlignment = Alignment.Center) { Text(e, fontSize = 24.sp) }
                }
            }
        }
        // when exactly it was sent (chatpa: also for other people's messages)
        Text(t("Sent ", "Ilitumwa ") + Dates.dateTime(Instant.ofEpochMilli(msg.at)) + ":" + "%02d".format(Instant.ofEpochMilli(msg.at).atZone(Dates.zone()).second) + if (!mine && msg.from.isNotBlank()) " · " + msg.from else "",
            style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp).testTag("act_time"))
        if (msg.queued || msg.status == "failed") {
            if (msg.status == "failed") SheetRow("act_retry", "undo", t("Try again", "Jaribu tena")) { hide { hub.retry(msg) } }
            SheetRow("act_unsend", "trash", t("Don’t send", "Usitume"), red = true) { hide { hub.unsend(msg) } }
            return@TeamSubSheet
        }
        if (!msg.deleted && msg.sys == null) {
            SheetRow("act_reply", "reply", t("Reply", "Jibu")) { hide { onReply() } }
            SheetRow("act_fwd", "fwd", t("Forward", "Sambaza")) { sub = "fwd" }
            if (msg.text.isNotBlank()) SheetRow("act_copy", "copy", t("Copy", "Nakili")) { clip.setText(AnnotatedString(msg.text)); hide { } }
            // pin it to the top of the chat (DMs: anyone; elsewhere admins); a 4th pin replaces the oldest
            val pinned = row?.pins?.any { it.id == msg.id } == true
            if (hub.canPin(row, admin)) SheetRow("act_pin", "pin", if (pinned) t("Unpin", "Ondoa kibandiko") else t("Pin", "Bandika"),
                value = if (!pinned && (row?.pins?.size ?: 0) >= 3) t("Replaces the oldest of the 3 pins", "Inachukua nafasi ya kibandiko cha zamani zaidi kati ya 3") else "") {
                hide { hub.pinLater(msg, !pinned) }
            }
            SheetRow("act_star", "star", if (msg.starred) t("Unstar", "Ondoa nyota") else t("Star", "Weka nyota")) { hide { scope.launch { hub.star(msg, !msg.starred) } } }
            if (mine) SheetRow("act_info", "info", t("Message info", "Maelezo ya ujumbe")) { sub = "info" }
            if (canEdit) SheetRow("act_edit", "edit", t("Edit", "Hariri")) { hide { onEdit() } }
            // chat 2: select several (B10), translate (B7), use in a story (B6), add a photo to the project (B5)
            SheetRow("act_select", "selectall", t("Select", "Chagua")) { hide { onSelect() } }
            if (msg.text.isNotBlank() && !mine && (msg.kind == "text" || msg.kind == "media")) SheetRow("act_translate", "translate", "Translate · Tafsiri") {
                hide {
                    if (hub.tools["translate"] != true) runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(googleTranslate(msg.text)))) }
                    else m.viewModelScope.launch { val (tx, err, g) = hub.translate(msg.text); if (tx != null) hub.translated[msg.id] = tx else if (g != null) runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(g))) } else hub.note.value = err }
                }
            }
            if (row?.kind == "project" && row.project.isNotBlank() && msg.kind == "text" && msg.text.isNotBlank()) SheetRow("act_story", "spark", t("Use in a story", "Tumia kwenye hadithi"), value = t("The words become the story’s quote", "Maneno yanakuwa nukuu ya hadithi")) {
                hide { StoryNav.pre = Triple(row.project, msg.text.trim().take(600), msg.from); StoryNav.open = row.project; openProject(row.project, "story") }
            }
            if (row?.kind == "project" && row.project.isNotBlank() && msg.media.any { it.type == "photo" } && !hub.added(msg)) SheetRow("act_addphotos", "photoadd", t("Add to project photos", "Ongeza kwenye picha za mradi")) {
                hide { m.viewModelScope.launch { val (files, err) = hub.addPhotos(msg, row.project); if (err != null) hub.note.value = err else { hub.note.value = t("Added to the project ✓", "Imeongezwa kwenye mradi ✓"); story(row.project, files) } } }
            }
            // turn it into a job: the existing repair and assign screens, filled in from the message
            SheetRow("act_repair", "wrench", t("Report a repair", "Ripoti matengenezo"), value = t("With this message’s words and photos", "Kwa maneno na picha za ujumbe huu")) {
                if (pid != null) hide { scope.launch { repairFrom(m, msg, pid); openProject(pid, "visit") } } else sub = "repair"
            }
            if (admin) SheetRow("act_assign", "cal", t("Assign a visit", "Gawa ziara"), value = t("For ${if (mine) row?.other(me) ?: "" else msg.from}", "Kwa ${if (mine) row?.other(me) ?: "" else msg.from}").trim()) {
                if (pid != null) hide { assignFrom(m, msg, row, pid); office("visits") } else sub = "assign"
            }
        }
        val canAll = (mine || (admin && row?.isGroup == true) || (row?.isGroup == true && row.admin(me))) && !msg.deleted
        SheetRow("act_delete", "trash", t("Delete", "Futa"), red = true) { if (onDelete != null) hide { onDelete() } else sub = if (canAll) "delete" else "deleteme" }
        said?.let { org.rafikioutreach.pin.ui.Msg(it, "bad") }
    }
    when (sub) {
        "fwd" -> ForwardSheet(m, msg, { sub = null; close() }) { openChat(it) }
        "info" -> InfoSheet(m, msg, row) { sub = null; close() }
        "delete", "deleteme" -> TeamSubSheet("delete_sheet", { sub = null }) { hide ->
            Text(t("Delete this message?", "Futa ujumbe huu?"), style = CwType.sheet(), color = C.ink, modifier = Modifier.padding(bottom = 8.dp))
            if (sub == "delete") SheetRow("del_all", "trash", t("Delete for everyone", "Futa kwa wote"), red = true) { hide { scope.launch { hub.delete(msg, true)?.let { said = it } }; close() } }
            SheetRow("del_me", "trash", t("Delete for me", "Futa kwangu"), red = sub != "delete") { hide { scope.launch { hub.delete(msg, false) }; close() } }
            SheetRow("del_no", "x", t("Cancel", "Ghairi")) { hide { } }
        }
        "repair", "assign" -> ProjectPicker(m, { sub = null }) { p ->
            val k = sub; sub = null; close()
            if (k == "repair") scope.launch { repairFrom(m, msg, p); openProject(p, "visit") } else { assignFrom(m, msg, row, p); office("visits") }
        }
    }
}

/** "Report a repair" from a message: a Site visit for the project, kept on the phone with "Needs repair", the words and the photos. */
internal suspend fun repairFrom(m: AppModel, msg: Msg, pid: String) {
    val ctx = m.getApplication<android.app.Application>()
    val shots = JSONArray()
    msg.media.filter { it.type == "photo" }.take(6).forEach { md ->
        val f = m.chat.file(md) ?: return@forEach
        val dst = File(File(ctx.filesDir, "photos").apply { mkdirs() }, "chat_${System.nanoTime()}.jpg")
        withContext(Dispatchers.IO) { runCatching { f.copyTo(dst, overwrite = true); shots.put(JSONObject().put("path", dst.path).put("label", "Other photo")) } }
    }
    val note = listOfNotNull(msg.text.takeIf { it.isNotBlank() }, t("From ${msg.from} in chat, ", "Kutoka kwa ${msg.from} kwenye gumzo, ") + Dates.dateTime(Instant.ofEpochMilli(msg.at))).joinToString("\n")
    val kept = VisitDrafts.load(ctx, pid)?.json
    val j = (kept ?: JSONObject().put("v", 1).put("step", 0)).put("state", kept?.optString("state")?.takeIf { it.isNotBlank() } ?: "repair")
        .put("note", listOfNotNull(kept?.optString("note")?.takeIf { it.isNotBlank() }, note).joinToString("\n"))
    val all = kept?.optJSONArray("shots") ?: JSONArray()
    for (i in 0 until shots.length()) all.put(shots.get(i))
    j.put("shots", all)
    VisitDrafts.save(ctx, pid, j)
}

/** "Assign a visit" from a message: Office › Visits opens its assign sheet with the person and the project chosen. */
internal fun assignFrom(m: AppModel, msg: Msg, row: ChatRow?, pid: String) {
    val me = m.chat.me
    TeamGo.assign = if (msg.from.equals(me, true)) row?.other(me) ?: "" else msg.from
    TeamGo.project = pid
    TeamGo.section = "visits"
}

/** Choose a project (when the chat isn't a project's own thread). */
@Composable
private fun ProjectPicker(m: AppModel, close: () -> Unit, pick: (String) -> Unit) {
    val snap by m.snap.collectAsState()
    var q by remember { mutableStateOf("") }
    TeamSubSheet("project_picker", close) { hide ->
        Text(t("Which project?", "Mradi upi?"), style = CwType.sheet(), color = C.ink)
        ChatSearchField(q, { q = it }, t("Search a project or place", "Tafuta mradi au mahali"), "pp_search")
        val list = snap?.projects.orEmpty().filter { q.isBlank() || "${it.title} ${it.area}".contains(q.trim(), true) }.take(30)
        list.forEach { p ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button) { hide { pick(p.id) } }.heightIn(min = 52.dp).padding(horizontal = 6.dp).testTag("pp_${p.id}"), verticalAlignment = Alignment.CenterVertically) {
                IconTile("drop", C.hue("blue").fg, C.hue("blue").bg, 36.dp); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Text(p.title, style = MaterialTheme.typography.titleSmall, color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(p.area, style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 1) }
            }
        }
    }
}

/** Forward to one or more chats. */
@Composable
internal fun ForwardSheet(m: AppModel, msg: Msg, close: () -> Unit, open: (String) -> Unit) {
    val rows by m.chat.rows.collectAsState()
    val chosen = remember { mutableStateListOf<String>() }
    val hp = rememberHaptics()
    val ctx = LocalContext.current
    TeamSubSheet("fwd_sheet", close) { hide ->
        Text(t("Forward to…", "Sambaza kwa…"), style = CwType.sheet(), color = C.ink, modifier = Modifier.padding(bottom = 8.dp))
        m.chat.sorted(rows.filter { !it.archived && !(it.onlyAdmins && !it.admin(m.chat.me)) }).forEach { r ->
            val on = r.id in chosen
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Checkbox) { if (on) chosen.remove(r.id) else chosen.add(r.id) }.heightIn(min = 56.dp).padding(horizontal = 6.dp).testTag("fwd_${r.id}"),
                verticalAlignment = Alignment.CenterVertically) {
                ChatAvatar(r, m.chat.me, 40.dp, m = m); Spacer(Modifier.width(12.dp))
                Text(r.title.ifBlank { r.other(m.chat.me) }, style = MaterialTheme.typography.titleSmall, color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Box(Modifier.size(24.dp).clip(CircleShape).background(if (on) ChatLook.online else Color.Transparent).border(2.dp, if (on) ChatLook.online else C.edge, CircleShape), contentAlignment = Alignment.Center) {
                    if (on) Icon(Ic.get("check"), null, tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
        Btn(if (chosen.isEmpty()) t("Choose chats", "Chagua magumzo") else t("Send to ${chosen.size}", "Tuma kwa ${chosen.size}"), {
            hide {
                chosen.forEach { c -> m.chat.send(c, msg.kind, msg.text, msg.media, mentions = emptyList(), loc = msg.loc, contact = msg.contact, fwd = true) }
                hp.ok(); Whoosh.play(ctx)
                if (chosen.size == 1) open(chosen.first())
            }
        }, Modifier.padding(top = 12.dp).fillMaxWidth().testTag("fwd_send"), enabled = chosen.isNotEmpty(), icon = "arrow")
    }
}

/** Message info: who read it and who received it, with the times (in groups, blue ticks once everyone has read it). */
@Composable
internal fun InfoSheet(m: AppModel, msg: Msg, row: ChatRow?, close: () -> Unit) {
    var info by remember { mutableStateOf<Pair<List<Pair<String, Long>>, List<Pair<String, Long>>>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(msg.id) { info = m.chat.info(msg); failed = info == null }
    TeamSubSheet("info_sheet", close) { _ ->
        Text(t("Message info", "Maelezo ya ujumbe"), style = CwType.sheet(), color = C.ink)
        Text(msg.preview(), style = MaterialTheme.typography.bodyMedium, color = C.muted, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        val i = info
        when {
            failed -> org.rafikioutreach.pin.ui.Msg(t("Couldn’t load who read it. Check the signal.", "Imeshindwa kupakia waliosoma. Angalia mtandao."), "warn")
            i == null -> Text(t("Loading…", "Inapakia…"), color = C.muted)
            else -> {
                InfoGroup(t("READ BY", "IMESOMWA NA"), "read", i.first)
                InfoGroup(t("DELIVERED TO", "IMEFIKA KWA"), "delivered", i.second.filter { d -> i.first.none { it.first.equals(d.first, true) } })
                val others = row?.members.orEmpty().filter { n -> !n.equals(m.chat.me, true) && (i.first + i.second).none { it.first.equals(n, true) } }
                if (others.isNotEmpty()) InfoGroup(t("NOT YET", "BADO"), "sent", others.map { it to 0L })
            }
        }
    }
}

@Composable
private fun InfoGroup(label: String, status: String, list: List<Pair<String, Long>>) {
    if (list.isEmpty()) return
    Row(Modifier.padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Ticks(if (status == "sent") "sent" else status); Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp, fontWeight = FontWeight.ExtraBold), color = C.muted)
    }
    list.forEach { (n, at) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).testTag("info_$status"), verticalAlignment = Alignment.CenterVertically) {
            PersonAvatar(n, 34.dp); Spacer(Modifier.width(10.dp))
            Text(n, style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (at > 0) Text(Dates.dateTime(Instant.ofEpochMilli(at)), style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 1, softWrap = false)
        }
    }
}

/** Who reacted with what; tap yours to take it away. */
@Composable
internal fun ReactsSheet(m: AppModel, msg: Msg, close: () -> Unit) {
    val scope = rememberScope()
    TeamSubSheet("reacts_sheet", close) { hide ->
        Text(t("Reactions", "Miitikio"), style = CwType.sheet(), color = C.ink, modifier = Modifier.padding(bottom = 8.dp))
        msg.reacts.entries.sortedByDescending { it.value.size }.forEach { (e, names) ->
            names.forEach { n ->
                val mine = n.equals(m.chat.me, true)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).then(if (mine) Modifier.clickable(role = Role.Button) { hide { scope.launch { m.chat.react(msg, e) } } } else Modifier)
                    .padding(vertical = 6.dp, horizontal = 4.dp).testTag("reacted_$n"), verticalAlignment = Alignment.CenterVertically) {
                    PersonAvatar(n, 36.dp); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (mine) t("You", "Wewe") else n, style = MaterialTheme.typography.titleSmall, color = C.ink)
                        if (mine) Text(t("Tap to remove", "Gusa kuondoa"), style = MaterialTheme.typography.bodySmall, color = C.muted)
                    }
                    Text(e, fontSize = 22.sp)
                }
            }
        }
    }
}

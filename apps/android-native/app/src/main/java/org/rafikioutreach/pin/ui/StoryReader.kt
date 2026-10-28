package org.rafikioutreach.pin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.*
import org.rafikioutreach.pin.t

/** Stories tab: a story that already has words — View story (full page) or Edit story (the writer). */
@Composable
internal fun StoryChoose(title: String, close: () -> Unit, view: () -> Unit, edit: () -> Unit) {
    DqmSheet("sh_choose", close) { hide ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = C.ink, modifier = Modifier.weight(1f))
            Box(Modifier.size(44.dp).clip(CircleShape).background(C.bg).clickable(role = Role.Button) { hide {} }.testTag("sh_choose_x"), contentAlignment = Alignment.Center) { Icon(Ic.get("x"), t("Close", "Funga"), tint = C.ink, modifier = Modifier.size(20.dp)) }
        }
        Btn(t("View story", "Tazama hadithi"), { hide { view() } }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("sh_view"), icon = "book")
        Btn(t("Edit story", "Hariri hadithi"), { hide { edit() } }, Modifier.padding(top = 10.dp).fillMaxWidth().testTag("sh_edit"), icon = "text", light = true)
    }
}

/** The story as a full page: title, the line under it, sections with their photos, the quote; Open on the website at the top. */
@Composable
internal fun StoryReader(m: AppModel, pid: String, close: () -> Unit) {
    var st by remember(pid) { mutableStateOf<Story?>(null) }
    var err by remember(pid) { mutableStateOf<String?>(null) }
    var view by remember { mutableStateOf<DocPhoto?>(null) }
    LaunchedEffect(pid) { val r = m.api.get("/api/story?id=" + Api.enc(pid)); if (r.ok) st = Story.from(r.json.optJSONObject("story")) else err = t("The story could not be opened. Check the signal and try again.", "Hadithi haikuweza kufunguliwa. Angalia mtandao kisha ujaribu tena.") }
    val uri = LocalUriHandler.current
    Dialog(close, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(C.card).systemBarsPadding().testTag("sh_read")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = close).testTag("sh_read_back"), contentAlignment = Alignment.Center) { Icon(Ic.get("back"), t("Back", "Rudi"), tint = C.ink, modifier = Modifier.size(24.dp)) }
                Spacer(Modifier.weight(1f))
                val url = st?.pub?.url.orEmpty()
                if (url.isNotBlank()) Btn(t("Open on the website", "Fungua kwenye tovuti"), { runCatching { uri.openUri(url) } }, Modifier.testTag("sh_web"), icon = "link", small = true)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                val s = st
                when {
                    err != null -> Text(err!!, style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.padding(top = 20.dp))
                    s == null -> repeat(3) { Box(Modifier.padding(top = 16.dp).fillMaxWidth().height(90.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(C.bg)) }
                    s.doc != null -> {
                        val d = s.doc!!
                        Text(d.title, style = MaterialTheme.typography.headlineSmall, color = C.ink, modifier = Modifier.padding(top = 6.dp).semantics { heading() }.testTag("sh_read_title"))
                        if (d.dek.isNotBlank()) Text(d.dek, style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic), color = C.muted, modifier = Modifier.padding(top = 8.dp))
                        d.sections.forEachIndexed { i, x ->
                            Column(Modifier.padding(top = 20.dp)) {
                                if (x.h.isNotBlank()) Text(x.h, style = MaterialTheme.typography.titleMedium, color = C.ink, modifier = Modifier.semantics { heading() })
                                x.body.split(Regex("\n\n+")).filter { it.isNotBlank() }.forEach { para -> Text(para.trim(), style = MaterialTheme.typography.bodyLarge, color = C.text, modifier = Modifier.padding(top = 8.dp)) }
                                SecPhoto(m, pid, x.photo, false, i, {}, {}) { view = x.photo }
                            }
                            if (i == 1) Pull(d = d, admin = false)
                        }
                        if (d.sections.size < 2) Pull(d = d, admin = false)
                    }
                    else -> s.text.split(Regex("\n\n+")).filter { it.isNotBlank() }.forEach { para -> Text(para.trim(), style = MaterialTheme.typography.bodyLarge, color = C.text, modifier = Modifier.padding(top = 10.dp)) }
                }
                if (s != null && s.pub == null) Text(if (s.status == "approved") t("Approved, not on the website yet.", "Imeidhinishwa, bado haiko kwenye tovuti.") else t("A draft: not on the website yet.", "Rasimu: bado haiko kwenye tovuti."),
                    style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 18.dp).testTag("sh_read_not"))
            }
        }
    }
    view?.let { ph -> SwipeViewer(1, 0, { ph.cap.ifBlank { t("Photo", "Picha") } }, { view = null }) { _, mod -> AuthPic(m, StoryKit.photoPath(pid, ph.id, 1600), mod, ContentScale.Fit) } }
}

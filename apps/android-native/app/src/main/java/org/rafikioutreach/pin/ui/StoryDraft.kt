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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.Project
import org.rafikioutreach.pin.data.Reply
import org.rafikioutreach.pin.t

// Part 2 of 2.27.0 (web v74, S/next-version.md item 4): the drafted story as sections with photos. Admins edit the
// title, the line under it, each heading and its words, and choose each section's photo from story.pool (action doc);
// field users read it as an article. "Approve and publish" asks first and puts it on the website; then
// "On the website ✓" links to the story page. Old drafts without sections keep the single text view.

/** One section while an admin edits it. */
private class EdSec(h: String, body: String, photo: DocPhoto?) { var h by mutableStateOf(h); var body by mutableStateOf(body); var photo by mutableStateOf(photo) }

@Composable
internal fun StoryDraftCard(m: AppModel, p: Project, st: Story, admin: Boolean, modifier: Modifier, post: (JSONObject, String?, (Reply) -> Unit) -> Unit, draftAgain: () -> Unit) {
    val doc = st.doc
    val live = st.live
    var busy by remember { mutableStateOf(false) }
    var askApprove by remember { mutableStateOf(false) }
    var askOff by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf<Int?>(null) }
    var view by remember { mutableStateOf<DocPhoto?>(null) }
    // what an admin is editing: kept until it is saved or a new draft comes
    var title by remember(doc) { mutableStateOf(doc?.title.orEmpty()) }
    var dek by remember(doc) { mutableStateOf(doc?.dek.orEmpty()) }
    val secs = remember(doc) { doc?.sections.orEmpty().map { EdSec(it.h, it.body, it.photo) }.toMutableStateList() }
    var text by remember(st.text) { mutableStateOf(st.text) }
    fun edited() = StoryDoc(title, dek, secs.map { DocSection(it.h, it.body, it.photo) }, doc?.pull, doc?.at.orEmpty(), doc?.by.orEmpty(), doc?.model.orEmpty())
    val dirty = doc != null && (title != doc.title || dek != doc.dek || secs.size != doc.sections.size || secs.zip(doc.sections).any { (a, b) -> a.h != b.h || a.body != b.body || a.photo?.id != b.photo?.id })
    val nWords = if (doc != null) (if (admin) edited() else doc).words else words(text)

    Card(modifier.testTag("story_text_card")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("The story", "Hadithi yenyewe"), style = MaterialTheme.typography.titleMedium, color = C.ink, modifier = Modifier.weight(1f).semantics { heading() })
            if (st.text.isNotBlank() || doc != null) Text(t("$nWords words", "Maneno $nWords"), style = MaterialTheme.typography.labelSmall, color = if (doc != null || nWords in 150..300) C.muted else C.warn, modifier = Modifier.testTag("story_words"))
        }
        doc?.let { d ->
            val how = when { d.model.startsWith("openai:") -> t("written with AI from the facts", "imeandikwa na AI kutoka kwa ukweli"); d.model == "text" -> t("from the earlier story", "kutoka kwa hadithi ya awali"); else -> t("written from the answers", "imeandikwa kutoka kwa majibu") }
            Text(how + (if (d.by.isNotBlank()) " · " + d.by else ""), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 2.dp).testTag("story_how"))
        }
        // on the website: a link to the story page
        if (live) StoryPubLink(st)
        when {
            st.text.isBlank() && doc == null -> Text(t("Answer the questions, then draft it here: 150–300 words from the answers, the quote and the project’s facts.", "Jibu maswali, kisha andika rasimu hapa: maneno 150–300 kutoka kwa majibu, nukuu na taarifa za mradi."),
                style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.padding(top = 6.dp))
            doc != null && admin -> Column(Modifier.testTag("story_doc_edit")) {
                Box(Modifier.testTag("story_doc_title")) { Field(title, { title = it.replace('\n', ' ').take(90) }, t("Title", "Kichwa")) }
                Box(Modifier.testTag("story_doc_dek")) { Field(dek, { dek = it.replace('\n', ' ').take(240) }, t("The line under the title", "Mstari chini ya kichwa"), lines = 2) }
                secs.forEachIndexed { i, x ->
                    Column(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.bg).padding(12.dp).testTag("story_sec_$i")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(26.dp).clip(CircleShape).background(C.tint), contentAlignment = Alignment.Center) { Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = C.blue2) }
                            Spacer(Modifier.width(8.dp))
                            Text(t("Section ${i + 1}", "Sehemu ${i + 1}"), style = MaterialTheme.typography.labelLarge, color = C.ink)
                        }
                        Box(Modifier.testTag("story_sec_h_$i")) { Field(x.h, { x.h = it.replace('\n', ' ').take(70) }, t("Heading", "Kichwa kidogo")) }
                        SecPhoto(m, p.id, x.photo, true, i, { pick = i }, { x.photo = null }) { view = x.photo }
                        Box(Modifier.testTag("story_sec_b_$i")) { Field(x.body, { x.body = it }, t("Words", "Maneno"), lines = 4) }
                    }
                }
                Pull(d = doc, admin = true)
            }
            doc != null -> Column(Modifier.padding(top = 10.dp).testTag("story_doc")) {
                Text(doc.title, style = MaterialTheme.typography.headlineSmall, color = C.ink, modifier = Modifier.semantics { heading() }.testTag("story_doc_title"))
                if (doc.dek.isNotBlank()) Text(doc.dek, style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic), color = C.text, modifier = Modifier.padding(top = 6.dp).testTag("story_doc_dek"))
                doc.sections.forEachIndexed { i, x ->
                    Column(Modifier.padding(top = 16.dp).testTag("story_sec_$i")) {
                        if (x.h.isNotBlank()) Text(x.h, style = MaterialTheme.typography.titleMedium, color = C.ink, modifier = Modifier.semantics { heading() })
                        SecPhoto(m, p.id, x.photo, false, i, {}, {}) { view = x.photo }
                        x.body.split(Regex("\n\n+")).filter { it.isNotBlank() }.forEach { para -> Text(para.trim(), style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.padding(top = 8.dp)) }
                    }
                }
                Pull(d = doc, admin = false)
            }
            // an old plain-text draft: the single text view
            admin && !live -> Box(Modifier.testTag("story_text_edit")) { Field(text, { text = it }, t("Story text", "Maandishi ya hadithi"), lines = 8) }
            else -> Text(st.text, style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.padding(top = 6.dp).testTag("story_text"))
        }

        // --- what can be done -------------------------------------------------------------------------------------------
        if (!live) Btn(if (busy) t("Drafting…", "Inaandika…") else if (st.text.isBlank() && doc == null) t("Draft the story", "Andika rasimu ya hadithi") else t("Draft it again", "Andika rasimu upya"),
            { if (st.text.isNotBlank() || doc != null) draftAgain() else { busy = true; post(JSONObject().put("action", "draft"), t("Draft ready ✓", "Rasimu iko tayari ✓")) { busy = false } } },
            Modifier.padding(top = 14.dp).fillMaxWidth().testTag("story_draft"), icon = "spark", enabled = !busy, light = st.text.isNotBlank() || doc != null)
        // the completion date the story will use (monday.com)
        if (!live && st.status in setOf("not-started", "collecting", "draft")) monthText(p.done)?.let { d ->
            Text(t("Finished: $d (from monday.com)", "Ilikamilika: $d (kutoka monday.com)"), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 6.dp).testTag("story_finished"))
        }
        if (admin && doc != null) Btn(if (live) t("Save and update the website", "Hifadhi na usasishe tovuti") else t("Save changes", "Hifadhi mabadiliko"),
            { post(JSONObject().put("action", "doc").put("doc", edited().out()), if (live) t("Saved and updated on the website ✓", "Imehifadhiwa na kusasishwa kwenye tovuti ✓") else t("Saved ✓", "Imehifadhiwa ✓")) {} },
            Modifier.padding(top = 8.dp).fillMaxWidth().testTag("story_doc_save"), icon = "check", light = true, small = true, enabled = dirty)
        else if (admin && doc == null && st.text.isNotBlank() && text != st.text && !live) Btn(t("Save text", "Hifadhi maandishi"), { post(JSONObject().put("action", "text").put("text", text), t("Saved ✓", "Imehifadhiwa ✓")) {} },
            Modifier.padding(top = 8.dp).fillMaxWidth().testTag("story_text_save"), icon = "check", light = true, small = true)
        if (admin && !live && st.status in setOf("draft", "collecting", "approved")) {
            Btn(t("Approve and publish", "Idhinisha na uchapishe"), { askApprove = true }, Modifier.padding(top = 8.dp).fillMaxWidth().testTag("story_approve"), icon = "check", enabled = st.text.isNotBlank() || doc != null)
            Text(t("“Approve and publish” puts it on the website’s Stories page straight away, with a copy in monday.com.", "“Idhinisha na uchapishe” inaiweka kwenye ukurasa wa Hadithi wa tovuti mara moja, na nakala kwenye monday.com."),
                style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 6.dp).testTag("story_approve_note"))
        }
        // field users: drafting sends it for review (web v72, sty_send)
        if (!admin && st.status in setOf("not-started", "collecting")) Text(t("“Draft the story” sends it to an admin for review. Nothing goes on the website before it is approved.", "“Andika rasimu ya hadithi” inaituma kwa msimamizi akague. Hakuna kinachoenda kwenye tovuti kabla ya kuidhinishwa."),
            style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 8.dp).testTag("sty_send"))
        if (st.status == "draft" || (admin && (st.status in setOf("approved", "published") || live))) Btn(t("Back to Collecting", "Rudi kukusanya"),
            { if (live) askOff = true else post(JSONObject().put("action", "collecting"), null) {} }, Modifier.padding(top = 8.dp).fillMaxWidth().testTag("story_back"), icon = "undo", light = true, small = true, outline = true)
    }

    if (askApprove) StoryAsk("story_approve_ask", t("Approve and publish this story?", "Idhinisha na uchapishe hadithi hii?"),
        t("It goes on the website’s Stories page straight away, and a copy goes to monday.com.", "Inaenda kwenye ukurasa wa Hadithi wa tovuti mara moja, na nakala inaenda monday.com."),
        t("Approve and publish", "Idhinisha na uchapishe"), false, { askApprove = false }) {
        askApprove = false
        val b = JSONObject().put("action", "approve")
        if (doc != null) b.put("doc", edited().out()) else b.put("text", text)
        post(b, t("On the website ✓", "Iko kwenye tovuti ✓")) {}
    }
    if (askOff) StoryAsk("story_off_ask", t("Take it off the website?", "Iondoe kwenye tovuti?"), t("The story goes back to Collecting and leaves the website’s Stories page.", "Hadithi inarudi kukusanya na kuondoka kwenye ukurasa wa Hadithi wa tovuti."),
        t("Take it off", "Iondoe"), true, { askOff = false }) { askOff = false; post(JSONObject().put("action", "collecting"), t("Taken off the website", "Imeondolewa kwenye tovuti")) {} }
    pick?.let { i ->
        if (i !in secs.indices) { LaunchedEffect(i) { pick = null } } else
        StoryPoolSheet(m, p.id, st.pool, secs.getOrNull(i)?.h.orEmpty(), i, secs.map { it.photo?.id }, { pick = null }) { ph ->
            // a photo shows once in the story
            if (ph != null) secs.forEachIndexed { j, x -> if (j != i && x.photo?.id == ph.id) x.photo = null }
            secs[i].photo = ph
        }
    }
    view?.let { ph -> SwipeViewer(1, 0, { ph.cap.ifBlank { t("Photo", "Picha") } }, { view = null }) { _, mod -> AuthPic(m, StoryKit.photoPath(p.id, ph.id, 1600), mod, ContentScale.Fit) } }
}

/** "On the website ✓ · Open the story page": opens the story on the website in the browser. */
@Composable
private fun StoryPubLink(st: Story) {
    val uri = LocalUriHandler.current
    val url = st.pub?.url.orEmpty()
    val shape = RoundedCornerShape(16.dp)
    Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(shape).background(C.okBg).border(1.dp, C.ok.copy(alpha = .35f), shape).then(if (url.isNotBlank()) Modifier.clickable(role = Role.Button) { runCatching { uri.openUri(url) } } else Modifier)
        .heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 10.dp).semantics(mergeDescendants = true) {}.testTag("story_pub"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(C.ok), contentAlignment = Alignment.Center) { Icon(Ic.get("check"), null, tint = Color.White, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t("On the website ✓", "Iko kwenye tovuti ✓"), style = MaterialTheme.typography.titleSmall, color = C.ink)
            if (url.isNotBlank()) Text(t("Open the story page", "Fungua ukurasa wa hadithi"), style = MaterialTheme.typography.bodySmall, color = C.ok)
        }
        if (url.isNotBlank()) Icon(Ic.get("arrow"), null, tint = C.ok, modifier = Modifier.size(18.dp))
    }
}

/** A section's photo: big, with its caption; for admins Change and Remove, or Add a photo. */
@Composable
private fun SecPhoto(m: AppModel, pid: String, ph: DocPhoto?, admin: Boolean, i: Int, change: () -> Unit, remove: () -> Unit, open: () -> Unit) {
    if (ph == null) {
        if (admin) Btn(t("Add a photo", "Ongeza picha"), change, Modifier.padding(top = 10.dp).fillMaxWidth().testTag("story_sec_add_$i"), icon = "camera", light = true, small = true)
        return
    }
    Column(Modifier.padding(top = 10.dp).fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(16.dp)).background(C.tint).clickable(role = Role.Button) { if (admin) change() else open() }
            .semantics { contentDescription = ph.cap.ifBlank { t("Photo", "Picha") } }.testTag("story_sec_ph_$i")) {
            AuthPic(m, StoryKit.photoPath(pid, ph.id, 960), Modifier.matchParentSize())
            if (ph.children) Text(t("Children", "Watoto"), style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = Color.White, maxLines = 1,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(99.dp)).background(Color(0xCC8A5200)).padding(horizontal = 8.dp, vertical = 2.dp))
        }
        if (ph.cap.isNotBlank()) Text(ph.cap, style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = C.muted, modifier = Modifier.padding(top = 4.dp))
        // side by side, or one under the other on a narrow phone (no word split)
        if (admin) BoxWithConstraints(Modifier.padding(top = 6.dp).fillMaxWidth()) {
            val wide = maxWidth >= 290.dp
            val b1 = @Composable { mod: Modifier -> Btn(t("Change", "Badilisha"), change, mod.testTag("story_sec_change_$i"), icon = "refresh", light = true, small = true) }
            val b2 = @Composable { mod: Modifier -> Btn(t("Remove", "Ondoa"), remove, mod.testTag("story_sec_rm_$i"), icon = "x", light = true, small = true) }
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { b1(Modifier.weight(1f)); b2(Modifier.weight(1f)) }
            else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { b1(Modifier.fillMaxWidth()); b2(Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun Pull(d: StoryDoc, admin: Boolean) {
    val q = d.pull ?: return
    Column(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.tint).padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp).testTag("story_pull")) {
        Text("“" + q.first + "”", style = MaterialTheme.typography.titleMedium.copy(fontStyle = FontStyle.Italic), color = C.ink)
        if (q.second.isNotBlank()) Text("— " + q.second, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = C.muted, modifier = Modifier.padding(top = 4.dp))
    }
    if (admin) Text(t("The quote is shown word for word. Change it under “Quote” above.", "Nukuu inaonyeshwa neno kwa neno. Ibadilishe chini ya “Nukuu” hapo juu."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 6.dp))
}

/** A photo for a section, from the project's photos (story rows and building stages); or none. */
@Composable
private fun StoryPoolSheet(m: AppModel, pid: String, pool: List<DocPhoto>, head: String, i: Int, used: List<String?>, close: () -> Unit, choose: (DocPhoto?) -> Unit) {
    DqmSheet("story_pool", close) { hide ->
        Row(verticalAlignment = Alignment.Top) {
            Text(t("Photo for “${head.ifBlank { "${i + 1}" }}”", "Picha ya “${head.ifBlank { "${i + 1}" }}”"), style = MaterialTheme.typography.titleLarge, color = C.ink, modifier = Modifier.weight(1f))
            Box(Modifier.size(44.dp).clip(CircleShape).background(C.bg).clickable(role = Role.Button) { hide {} }.testTag("story_pool_x"), contentAlignment = Alignment.Center) { Icon(Ic.get("x"), t("Close", "Funga"), tint = C.ink, modifier = Modifier.size(20.dp)) }
        }
        if (pool.isEmpty()) Text(t("No photos yet. Add them under Photos above, or with the building stages.", "Bado hakuna picha. Ziongeze chini ya Picha hapo juu, au pamoja na hatua za ujenzi."), style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.padding(top = 12.dp))
        pool.chunked(2).forEachIndexed { li, line ->
            Row(Modifier.padding(top = 10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                line.forEachIndexed { k, ph ->
                    val n = li * 2 + k
                    val on = used.getOrNull(i) == ph.id
                    val other = used.indexOfFirst { it == ph.id }.takeIf { it >= 0 && it != i }
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(if (on) C.tint else C.bg).border(if (on) 3.dp else 0.dp, if (on) C.blue2 else Color.Transparent, RoundedCornerShape(16.dp))
                        .clickable(role = Role.Button) { choose(ph); hide {} }.semantics { selected = on }.padding(6.dp).testTag("story_pool_$n")) {
                        Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(12.dp)).background(C.tint)) {
                            AuthPic(m, StoryKit.photoPath(pid, ph.id, 320), Modifier.matchParentSize())
                            if (ph.children) Text(t("Children", "Watoto"), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = Color.White, maxLines = 1,
                                modifier = Modifier.align(Alignment.TopStart).padding(5.dp).clip(RoundedCornerShape(99.dp)).background(Color(0xCC8A5200)).padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                        Text(when { ph.row in StoryKit.ROWS -> StoryKit.row(ph.row); ph.row.startsWith("stage:") -> t("Building", "Ujenzi"); else -> ph.row }, style = MaterialTheme.typography.labelMedium, color = C.ink, modifier = Modifier.padding(top = 4.dp))
                        if (ph.cap.isNotBlank()) Text(ph.cap, style = MaterialTheme.typography.bodySmall, color = C.muted)
                        other?.let { Text(t("In section ${it + 1}", "Kwenye sehemu ${it + 1}"), style = MaterialTheme.typography.labelSmall, color = C.blue2) }
                    }
                }
                if (line.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Btn(t("No photo for this section", "Hakuna picha kwa sehemu hii"), { choose(null); hide {} }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("story_pool_none"), light = true, small = true)
    }
}

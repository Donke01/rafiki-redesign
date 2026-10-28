package org.rafikioutreach.pin.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.Api
import org.rafikioutreach.pin.data.Project
import org.rafikioutreach.pin.data.Reply
import org.rafikioutreach.pin.data.reasonText
import org.rafikioutreach.pin.t
import java.io.File

// feat-mea, me-brief §9: everything about a project's story in one tab, "Stories" / "Hadithi", on the project page,
// against S/me-contract.md "Stories". monday.com's story columns hold the words, photos and statuses.

/** A story photo: its asset id, whether children are in it, who added it and when. */
data class StoryPhoto(val id: String, val children: Boolean, val by: String, val at: String)
data class Story(
    val status: String, val consent: String, val answers: Map<String, String>, val quote: String, val quoteBy: String, val credit: String, val text: String,
    val photos: Map<String, List<StoryPhoto>>, val missing: List<String>, val canApprove: Boolean, val prompts: Map<String, Pair<String, String>>,
) {
    val filled get() = StoryKit.QS.count { answers[it].orEmpty().isNotBlank() }
    companion object {
        private fun JSONObject.str(k: String) = optString(k).takeIf { it != "null" }.orEmpty()
        fun from(o: JSONObject?): Story? {
            if (o == null) return null
            val a = o.optJSONObject("answers") ?: JSONObject(); val ph = o.optJSONObject("photos") ?: JSONObject(); val pr = o.optJSONObject("prompts") ?: JSONObject()
            return Story(o.str("status").ifBlank { "not-started" }, o.str("consent").ifBlank { "not-yet" }, StoryKit.QS.associateWith { a.str(it) }, o.str("quote"), o.str("quoteBy"), o.str("credit"), o.str("text"),
                StoryKit.ROWS.associateWith { r -> ph.optJSONArray(r)?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { StoryPhoto(it.str("id"), it.optBoolean("children"), it.str("by"), it.str("at")) } }.orEmpty() },
                o.optJSONArray("missing")?.let { m -> (0 until m.length()).map { m.optString(it) } }.orEmpty(), o.optBoolean("canApprove"),
                pr.keys().asSequence().mapNotNull { k -> pr.optJSONObject(k)?.let { k to (it.str("en") to it.str("sw")) } }.toMap())
        }
    }
}

object StoryKit {
    val QS = listOf("q1", "q2", "q3", "q4", "q5", "q6")
    val ROWS = listOf("before", "progress", "after")
    val STATUSES = listOf("not-started", "collecting", "draft", "approved", "published")
    fun status(k: String) = when (k) {
        "collecting" -> t("Collecting", "Inakusanywa"); "draft" -> t("Draft ready", "Rasimu iko tayari"); "approved" -> t("Approved", "Imeidhinishwa")
        "published" -> t("Published", "Imechapishwa"); else -> t("Not started", "Haijaanza")
    }
    fun row(k: String) = when (k) { "before" -> t("Before", "Kabla"); "progress" -> t("In progress", "Wakati wa kazi"); else -> t("After", "Baada") }
    /** The six questions, if the server doesn't send its own wording. */
    fun prompt(s: Story?, q: String): String = s?.prompts?.get(q)?.let { (en, sw) -> t(en.ifBlank { null } ?: builtIn(q).first, sw.ifBlank { null } ?: builtIn(q).second) } ?: builtIn(q).let { t(it.first, it.second) }
    private fun builtIn(q: String) = when (q) {
        "q1" -> "What was life like here before the project?" to "Maisha yalikuwaje hapa kabla ya mradi?"
        "q2" -> "What was the biggest problem?" to "Tatizo kubwa zaidi lilikuwa nini?"
        "q3" -> "Who is this story about? (name, age, role)" to "Hadithi hii inamhusu nani? (jina, umri, nafasi)"
        "q4" -> "What part did the community play?" to "Jamii ilishiriki vipi?"
        "q5" -> "What has changed since?" to "Nini kimebadilika tangu hapo?"
        "q6" -> "Describe one moment that shows the difference." to "Eleza tukio moja linaloonyesha tofauti."
        else -> q to q
    }
    /** "3 of 6 answers, no after photos, consent: not yet" from the server's list. */
    fun missing(list: List<String>): String = list.mapNotNull { k ->
        when {
            k.startsWith("answers:") -> k.removePrefix("answers:").split("/").let { (a, b) -> t("$a of $b answers", "majibu $a kati ya $b") }
            k == "before-photos" -> t("no before photos", "hakuna picha za kabla")
            k == "progress-photos" -> t("no in-progress photos", "hakuna picha za maendeleo")
            k == "after-photos" -> t("no after photos", "hakuna picha za baada")
            k == "consent" -> t("consent: not yet", "idhini: bado")
            k == "text" -> t("no story text yet", "bado hakuna maandishi ya hadithi")
            else -> null
        }
    }.joinToString(", ")
    fun why(r: Reply) = when (r.reason) {
        "consent" -> t("Photo consent is needed first: children are in a photo.", "Idhini ya picha inahitajika kwanza: kuna watoto kwenye picha.")
        "no-text" -> t("Draft the story first.", "Andika rasimu ya hadithi kwanza.")
        "not-approved" -> t("Approve it before publishing.", "Idhinisha kabla ya kuchapisha.")
        "admin-only" -> t("Only admins can do this.", "Wasimamizi pekee wanaweza kufanya hivi.")
        else -> reasonText(r.reason)
    }
    fun photoPath(id: String, asset: String, w: Int = 480) = "/api/story?id=${Api.enc(id)}&asset=${Api.enc(asset)}&w=$w"
}

/** After a visit with "Add to this project's story" ticked: open that project on its Stories tab. */
object StoryNav {
    var open by mutableStateOf<String?>(null)
    /** "Use in a story" from a project chat (chat 2, B6): the project, the words and who said them, filled in (not saved). */
    var pre: Triple<String, String, String>? = null
}

/** The Stories tab. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun StoryTab(m: AppModel, p: Project) {
    val scope = rememberScope()
    val admin = m.snap.collectAsState().value?.admin == true
    var s by remember(p.id) { mutableStateOf<Story?>(null) }
    var err by remember(p.id) { mutableStateOf<String?>(null) }
    var said by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(p.id) { val r = m.api.get("/api/story?id=" + Api.enc(p.id)); if (r.ok) s = Story.from(r.json.optJSONObject("story")) else err = reasonText(r.reason) }
    /** Every POST answers with the fresh story. */
    fun post(body: JSONObject, ok: String? = null, after: (Reply) -> Unit = {}) = scope.launch {
        val r = m.api.postTo("/api/story", body.put("id", p.id))
        if (r.ok) { Story.from(r.json.optJSONObject("story"))?.let { s = it }; if (ok != null) said = "ok" to ok; m.refresh(quiet = true) } else said = "bad" to StoryKit.why(r)
        after(r)
    }
    val st = s
    Column(Modifier.fillMaxWidth().testTag("story_tab")) {
        if (st == null) { Text(err ?: t("Loading…", "Inapakia…"), style = MaterialTheme.typography.bodyMedium, color = if (err != null) C.bad else C.muted, modifier = Modifier.testTag("story_loading")); return }
        // 1. where it is, and what's still missing
        Card(Modifier.testTag("story_status")) {
            Text(t("Story", "Hadithi"), style = MaterialTheme.typography.titleMedium, color = C.ink)
            val ci = StoryKit.STATUSES.indexOf(st.status)
            Column(Modifier.padding(top = 8.dp)) {
                StoryKit.STATUSES.forEachIndexed { i, k ->
                    val done = i < ci; val now = i == ci
                    Row(Modifier.padding(vertical = 3.dp).testTag("story_st_$k"), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(22.dp).clip(CircleShape).background(when { done -> C.ok; now -> C.tint; else -> C.bg }).then(if (now) Modifier.border(2.dp, C.blue2, CircleShape) else Modifier), contentAlignment = Alignment.Center) {
                            if (done) Icon(Ic.get("check"), null, tint = Color.White, modifier = Modifier.size(13.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(StoryKit.status(k), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (now) FontWeight.ExtraBold else FontWeight.Normal), color = if (done || now) C.ink else C.muted)
                    }
                }
            }
            if (st.missing.isNotEmpty() && st.status !in setOf("approved", "published")) Text(t("Still needed: ", "Bado inahitajika: ") + StoryKit.missing(st.missing), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = C.warn, modifier = Modifier.padding(top = 8.dp).testTag("story_missing"))
        }
        said?.let { (tone, text) -> Box(Modifier.padding(top = 8.dp).testTag("story_msg")) { Msg(text, tone) } }
        // 2. photos: before, in progress, after
        Card(Modifier.padding(top = 12.dp).testTag("story_photos")) {
            Text(t("Photos", "Picha"), style = MaterialTheme.typography.titleMedium, color = C.ink)
            StoryKit.ROWS.forEach { r -> StoryPhotoRow(m, p, r, st.photos[r].orEmpty()) { fresh, msg -> if (fresh != null) s = fresh; said = msg } }
        }
        // 3. the six questions, one at a time
        StoryQuestions(m, p, st) { body, ok -> post(body, ok) }
        // 4. the quote and 5. consent
        // a quote from the chat (B6): filled in and marked, saved only with Save quote
        val pre = remember(p.id) { StoryNav.pre?.takeIf { it.first == p.id }.also { if (it != null) StoryNav.pre = null } }
        val preAt = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
        LaunchedEffect(pre) { if (pre != null) { kotlinx.coroutines.delay(700); preAt.bringIntoView(); said = "ok" to t("Quote filled in from the chat. Tap Save quote to keep it.", "Nukuu imejazwa kutoka gumzo. Gusa Hifadhi nukuu kuiweka.") } }
        Card(Modifier.padding(top = 12.dp).then(if (pre != null) Modifier.border(3.dp, C.blue, RoundedCornerShape(24.dp)).testTag("story_quote_from_chat") else Modifier).bringIntoViewRequester(preAt).testTag("story_quote")) {
            Text(t("Quote", "Nukuu"), style = MaterialTheme.typography.titleMedium, color = C.ink)
            var quote by remember(st.quote) { mutableStateOf(pre?.second ?: st.quote) }
            var by by remember(st.quoteBy) { mutableStateOf(pre?.third ?: st.quoteBy) }
            var credit by remember(st.credit) { mutableStateOf(st.credit.ifBlank { m.prefs.name }) }
            Box(Modifier.testTag("story_quote_text")) { Field(quote, { quote = it }, t("What they said", "Walichosema"), placeholder = t("e.g. “Now my girls get to school on time.”", "mf. “Sasa wasichana wangu wanafika shuleni kwa wakati.”"), lines = 3) }
            Box(Modifier.testTag("story_quote_by")) { Field(by, { by = it }, t("Who said it", "Aliyesema"), placeholder = t("e.g. Mary, mother of three", "mf. Mary, mama wa watoto watatu")) }
            Box(Modifier.testTag("story_credit")) { Field(credit, { credit = it }, t("Photo credit", "Mpiga picha"), placeholder = m.prefs.name) }
            Btn(t("Save quote", "Hifadhi nukuu"), { post(JSONObject().put("action", "answers").put("quote", quote.trim()).put("quoteBy", by.trim()).put("credit", credit.trim()), t("Saved ✓", "Imehifadhiwa ✓")) },
                Modifier.padding(top = 12.dp).fillMaxWidth().testTag("story_quote_save"), icon = "check", light = true, small = true)
            Text(t("Photo consent", "Idhini ya picha"), style = MaterialTheme.typography.labelMedium, color = C.ink, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("received" to t("Received", "Imepokelewa"), "not-yet" to t("Not yet", "Bado")).forEach { (k, l) ->
                    Box(Modifier.weight(1f).testTag("story_consent_$k")) { Pill(l, st.consent == k, Modifier.fillMaxWidth()) { if (st.consent != k) post(JSONObject().put("action", "consent").put("value", k)) } }
                }
            }
            if (st.photos.values.flatten().any { it.children }) Text(t("Children are in a photo, so consent is needed before it’s approved.", "Kuna watoto kwenye picha, kwa hiyo idhini inahitajika kabla ya kuidhinishwa."),
                style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 6.dp).testTag("story_children_note"))
        }
        // 6. the story itself
        Card(Modifier.padding(top = 12.dp).testTag("story_text_card")) {
            Text(t("The story", "Hadithi yenyewe"), style = MaterialTheme.typography.titleMedium, color = C.ink)
            var busy by remember { mutableStateOf(false) }
            var text by remember(st.text) { mutableStateOf(st.text) }
            if (st.text.isBlank()) Text(t("Answer the questions, then draft it here: 150–300 words from the answers, the quote and the project’s facts.", "Jibu maswali, kisha andika rasimu hapa: maneno 150–300 kutoka kwa majibu, nukuu na taarifa za mradi."),
                style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.padding(top = 6.dp))
            else if (admin && st.status != "published") Box(Modifier.testTag("story_text_edit")) { Field(text, { text = it }, t("Story text", "Maandishi ya hadithi"), lines = 8) }
            else Text(st.text, style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.padding(top = 6.dp).testTag("story_text"))
            if (st.text.isNotBlank()) Text(t("${words(text)} words", "maneno ${words(text)}"), style = MaterialTheme.typography.labelSmall, color = if (words(text) in 150..300) C.muted else C.warn, modifier = Modifier.padding(top = 4.dp).testTag("story_words"))
            if (st.status in setOf("not-started", "collecting", "draft")) Btn(if (busy) t("Drafting…", "Inaandika…") else if (st.text.isBlank()) t("Draft the story", "Andika rasimu ya hadithi") else t("Draft it again", "Andika rasimu upya"),
                { busy = true; post(JSONObject().put("action", "draft"), t("Draft ready ✓", "Rasimu iko tayari ✓")) { busy = false } }, Modifier.padding(top = 12.dp).fillMaxWidth().testTag("story_draft"), icon = "spark", enabled = !busy)
            // field users: drafting sends it for review (web v72, sty_send)
            if (!admin && st.status in setOf("not-started", "collecting")) Text(t("“Draft the story” sends it to an admin for review. Nothing goes on the website before it is approved.", "“Andika rasimu ya hadithi” inaituma kwa msimamizi akague. Hakuna kinachoenda kwenye tovuti kabla ya kuidhinishwa."),
                style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 8.dp).testTag("sty_send"))
            if (admin) {
                if (st.text.isNotBlank() && text != st.text && st.status != "published") Btn(t("Save text", "Hifadhi maandishi"), { post(JSONObject().put("action", "text").put("text", text), t("Saved ✓", "Imehifadhiwa ✓")) },
                    Modifier.padding(top = 8.dp).fillMaxWidth().testTag("story_text_save"), icon = "check", light = true, small = true)
                if (st.status == "draft") Btn(t("Approve", "Idhinisha"), { post(JSONObject().put("action", "approve").put("text", text), t("Approved ✓ It can go on the website.", "Imeidhinishwa ✓ Inaweza kuwekwa kwenye tovuti.")) },
                    Modifier.padding(top = 8.dp).fillMaxWidth().testTag("story_approve"), icon = "check", enabled = st.text.isNotBlank())
                if (st.status == "approved") Btn(t("Mark published", "Weka imechapishwa"), { post(JSONObject().put("action", "publish"), t("Published ✓", "Imechapishwa ✓")) }, Modifier.padding(top = 8.dp).fillMaxWidth().testTag("story_publish"), icon = "upload")
            }
            if (st.status == "draft" || (admin && st.status in setOf("approved", "published"))) Btn(t("Back to Collecting", "Rudi kwenye Kukusanya"), { post(JSONObject().put("action", "collecting")) },
                Modifier.padding(top = 8.dp).fillMaxWidth().testTag("story_back"), icon = "undo", light = true, small = true, outline = true)
        }
    }
}

private fun words(s: String) = s.trim().split(Regex("\\s+")).count { it.isNotBlank() }

/** One photo row (Before, In progress, After): its count, thumbnails and Add photo (camera or gallery; then "children in it?"). */
@Composable
private fun StoryPhotoRow(m: AppModel, p: Project, row: String, photos: List<StoryPhoto>, done: (Story?, Pair<String, String>) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberScope()
    var menu by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var camFile by remember { mutableStateOf<File?>(null) }
    var view by remember { mutableStateOf<Int?>(null) }
    fun got(f: File?) { if (f != null) scope.launch { picked = m.keepPhoto(f) } }
    val cam = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> val f = camFile; camFile = null; if (ok) got(f) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch { got(withContext(Dispatchers.IO) { runCatching { File(File(ctx.cacheDir, "cam").apply { mkdirs() }, "s${System.currentTimeMillis()}.jpg").also { f -> ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } } } }.getOrNull() }) }
    }
    fun upload(children: Boolean) {
        val f = picked ?: return
        busy = true
        scope.launch {
            val r = m.api.postMultipart("/api/story", withContext(Dispatchers.IO) { f.readBytes() }, "image/jpeg", "story.jpg", linkedMapOf("action" to "photo", "id" to p.id, "row" to row, "children" to children.toString()))
            busy = false; picked = null
            done(if (r.ok) Story.from(r.json.optJSONObject("story")) else null, if (r.ok) "ok" to t("Photo added ✓", "Picha imeongezwa ✓") else "bad" to StoryKit.why(r))
        }
    }
    Column(Modifier.padding(top = 12.dp).testTag("story_row_$row")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(StoryKit.row(row), style = MaterialTheme.typography.titleSmall, color = C.ink)
            Text("  ·  ${photos.size}", style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.weight(1f).testTag("story_count_$row"))
            Box {
                Btn(t("Add photo", "Ongeza picha"), { PhotoPick.fakeFile?.let { picked = it(); return@Btn }; menu = true }, Modifier.testTag("story_add_$row"), icon = "camera", light = true, small = true, enabled = !busy)
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(t("Take a photo", "Piga picha")) }, { menu = false; val f = File(File(ctx.cacheDir, "cam").apply { mkdirs() }, "s${System.currentTimeMillis()}.jpg"); camFile = f
                        runCatching { cam.launch(FileProvider.getUriForFile(ctx, "org.rafikioutreach.pin.files", f)) } }, leadingIcon = { Icon(Ic.get("camera"), null, tint = C.blue) })
                    DropdownMenuItem({ Text(t("Choose from gallery", "Chagua kutoka kwenye picha")) }, { menu = false; runCatching { gallery.launch("image/*") } }, leadingIcon = { Icon(Ic.get("upload"), null, tint = C.blue) })
                }
            }
        }
        if (photos.isNotEmpty()) Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()).testTag("story_thumbs_$row"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            photos.forEachIndexed { i, ph ->
                AuthPic(m, StoryKit.photoPath(p.id, ph.id), Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(C.tint).clickable(role = Role.Button) { view = i }
                    .semantics { contentDescription = StoryKit.row(row) + " · " + t("photo ${i + 1}", "picha ${i + 1}") }.testTag("story_photo_${row}_$i"))
            }
        }
        if (picked != null) Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.tint).padding(12.dp).testTag("story_children_ask")) {
            Text(t("Are there children in this photo?", "Kuna watoto kwenye picha hii?"), style = MaterialTheme.typography.titleSmall, color = C.ink)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(t("No", "Hapana"), { upload(false) }, Modifier.weight(1f).testTag("story_children_no"), light = true, small = true, enabled = !busy)
                Btn(t("Yes", "Ndiyo"), { upload(true) }, Modifier.weight(1f).testTag("story_children_yes"), small = true, enabled = !busy)
            }
        }
    }
    view?.let { i -> SwipeViewer(photos.size, i.coerceIn(0, photos.size - 1), { StoryKit.row(row) }, { view = null }) { j, mod -> AuthPic(m, StoryKit.photoPath(p.id, photos[j].id, 1600), mod, ContentScale.Fit) } }
}

/** The six questions as a guided flow, one card at a time: type an answer, or record it (transcribed when the server can). */
@Composable
private fun StoryQuestions(m: AppModel, p: Project, st: Story, save: (JSONObject, String) -> Unit) {
    val scope = rememberScope()
    var at by remember(p.id) { mutableIntStateOf(StoryKit.QS.indexOfFirst { st.answers[it].isNullOrBlank() }.coerceAtLeast(0)) }
    val q = StoryKit.QS[at]
    var answer by remember(q, st.answers[q]) { mutableStateOf(st.answers[q].orEmpty()) }
    var voice by remember(q) { mutableStateOf<File?>(null) }
    var vmsg by remember(q) { mutableStateOf<String?>(null) }
    Card(Modifier.padding(top = 12.dp).testTag("story_questions")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("Questions", "Maswali"), style = MaterialTheme.typography.titleMedium, color = C.ink, modifier = Modifier.weight(1f))
            Text(t("${st.filled} of 6 answered", "${st.filled} kati ya 6 yamejibiwa"), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.testTag("story_filled"))
        }
        // jump to any question; answered ones are ticked
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StoryKit.QS.forEachIndexed { i, k ->
                val has = st.answers[k].orEmpty().isNotBlank()
                Box(Modifier.size(40.dp).clip(CircleShape).background(if (i == at) C.blue2 else if (has) C.okBg else C.tint).clickable(role = Role.Tab) { at = i }
                    .semantics { contentDescription = t("Question ${i + 1}", "Swali ${i + 1}") }.testTag("story_qdot_$k"), contentAlignment = Alignment.Center) {
                    if (has && i != at) Icon(Ic.get("check"), null, tint = C.ok, modifier = Modifier.size(16.dp))
                    else Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = if (i == at) Color.White else C.ink)
                }
            }
        }
        Text(t("Question ${at + 1} of 6", "Swali ${at + 1} kati ya 6"), style = MaterialTheme.typography.labelMedium, color = C.muted, modifier = Modifier.padding(top = 12.dp))
        Text(StoryKit.prompt(st, q), style = MaterialTheme.typography.titleMedium, color = C.ink, modifier = Modifier.padding(top = 2.dp).testTag("story_prompt"))
        Box(Modifier.testTag("story_answer")) { Field(answer, { answer = it }, t("Answer", "Jibu"), lines = 4) }
        // or say it: a voice note, transcribed by the server when speech-to-text is on (else kept with the project)
        VoiceNote(voice) { f ->
            voice = f
            if (f != null) scope.launch {
                vmsg = t("Sending the recording…", "Inatuma sauti…")
                val r = m.api.postMultipart("/api/story", withContext(Dispatchers.IO) { f.readBytes() }, "audio/mp4", "answer.m4a", linkedMapOf("action" to "voice", "id" to p.id, "q" to q))
                vmsg = if (!r.ok) StoryKit.why(r) else if (r.json.optBoolean("typed")) { answer = r.json.optString("text"); t("Written out from your recording ✓ Check it, then save.", "Imeandikwa kutoka kwa sauti yako ✓ Iangalie, kisha uhifadhi.") }
                    else t("Kept as a voice note on monday.com.", "Imehifadhiwa kama ujumbe wa sauti kwenye monday.com.")
            }
        }
        vmsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 6.dp).testTag("story_voice_msg")) }
        // one under the other: the Kiswahili words are long
        Btn(if (at < 5) t("Save and next", "Hifadhi na uendelee") else t("Save", "Hifadhi"), {
            save(JSONObject().put("action", "answers").put(q, answer.trim()), t("Saved ✓", "Imehifadhiwa ✓"))
            if (at < 5) at++
        }, Modifier.padding(top = 12.dp).fillMaxWidth().testTag("story_qsave"), icon = "check", small = true)
        if (at > 0) Btn(t("Previous question", "Swali lililopita"), { at-- }, Modifier.padding(top = 8.dp).fillMaxWidth().testTag("story_qprev"), light = true, small = true)
    }
}

/** A tag on a project's row when its story is approved (or published). */
@Composable
internal fun StoryTag() = Box(Modifier.testTag("row_story")) { Tag(t("Story", "Hadithi"), C.hue("purple").bg, C.hue("purple").fg) }

/** Donor mode: an approved story, read-only: the text, the quote and the after photos. */
data class DonorStory(val text: String, val quote: String, val quoteBy: String, val credit: String, val after: List<String>) {
    companion object {
        fun from(o: JSONObject?): DonorStory? = o?.takeIf { it.optString("text").isNotBlank() }?.let {
            DonorStory(it.optString("text"), it.optString("quote"), it.optString("quoteBy"), it.optString("credit"), it.optJSONArray("after")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty())
        }
    }
}

@Composable
fun DonorStoryCard(s: DonorStory, photo: (String, Int) -> String, open: (List<Pair<String, String>>, Int) -> Unit) {
    Card(Modifier.padding(top = 14.dp).testTag("dstory_card")) {
        Text(t("Their story", "Hadithi yao"), style = MaterialTheme.typography.titleMedium, color = C.ink)
        Text(s.text, style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.padding(top = 6.dp).testTag("dstory_text"))
        if (s.quote.isNotBlank()) Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.tint).padding(12.dp)) {
            Text("“" + s.quote.trim('“', '”', '"') + "”", style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic), color = C.ink, modifier = Modifier.testTag("dstory_quote"))
            if (s.quoteBy.isNotBlank()) Text("— " + s.quoteBy, style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 4.dp))
        }
        if (s.after.isNotEmpty()) {
            val all = s.after.map { photo(it, 1600) to t("After", "Baada") }
            Row(Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.after.forEachIndexed { i, a -> NetImage(photo(a, 480), Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)).background(C.tint).clickable(role = Role.Button) { open(all, i) }.testTag("dstory_photo_$i")) {} }
            }
        }
        if (s.credit.isNotBlank()) Text(t("Photos: ", "Picha: ") + s.credit, style = MaterialTheme.typography.labelSmall, color = C.muted, modifier = Modifier.padding(top = 8.dp))
    }
}

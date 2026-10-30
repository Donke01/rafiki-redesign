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
import androidx.compose.runtime.saveable.rememberSaveable
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

/** A story photo: its asset id, whether children are in it, who added it and when, its caption and the day it was taken. */
data class StoryPhoto(val id: String, val children: Boolean, val by: String, val at: String, val cap: String = "", val taken: String = "")
/** A photo a story section shows (from story.pool: the story rows and the building stages). */
data class DocPhoto(val id: String, val row: String, val cap: String, val children: Boolean = false)
/** One section of the drafted story: a heading, its words and maybe a photo. */
data class DocSection(val h: String, val body: String, val photo: DocPhoto?)
/** The drafted story (web v74, lib/story-doc.js): a title, the line under it, sections with photos, the quote. */
data class StoryDoc(val title: String, val dek: String, val sections: List<DocSection>, val pull: Pair<String, String>?, val at: String, val by: String, val model: String) {
    val words get() = (listOf(title, dek) + sections.map { it.body }).sumOf { w -> w.trim().split(Regex("\\s+")).count { it.isNotBlank() } }
    /** As the server wants it back (action doc / approve). */
    fun out(): JSONObject = JSONObject().put("title", title).put("dek", dek).put("sections", org.json.JSONArray().apply {
        sections.forEach { x -> put(JSONObject().put("h", x.h).put("body", x.body).put("photo", x.photo?.let { JSONObject().put("id", it.id) } ?: JSONObject.NULL)) }
    })
}
/** Where the story is on the website, once approved and published. */
data class StoryPub(val at: String, val by: String, val url: String)
/** The month of each column ("YYYY-MM" or ""): Before, In progress from – to, After (story.dates). */
data class StoryDates(val before: String = "", val progress: String = "", val progressEnd: String = "", val after: String = "") {
    fun of(row: String) = when (row) { "before" -> before; "progress" -> progress; else -> after }
}
data class Story(
    val status: String, val consent: String, val answers: Map<String, String>, val quote: String, val quoteBy: String, val credit: String, val text: String,
    val photos: Map<String, List<StoryPhoto>>, val missing: List<String>, val canApprove: Boolean, val prompts: Map<String, Pair<String, String>>,
    val doc: StoryDoc? = null, val pool: List<DocPhoto> = emptyList(), val pub: StoryPub? = null, val newPhotos: Int = 0, val dates: StoryDates = StoryDates(),
    /** The photo chosen as the story's cover (web v78), or "" for the automatic choice. */
    val cover: String = "",
) {
    val filled get() = StoryKit.QS.count { answers[it].orEmpty().isNotBlank() }
    /** On the website (approved and published). */
    val live get() = pub != null || status == "published"
    /** The photos the drafted story shows. */
    val inStory: Set<String> get() = doc?.sections?.mapNotNull { it.photo?.id }?.toSet().orEmpty()
    companion object {
        private fun JSONObject.str(k: String) = optString(k).takeIf { it != "null" }.orEmpty()
        private fun JSONObject.ym(k: String) = str(k).takeIf { Regex("^\\d{4}-\\d{2}$").matches(it) }.orEmpty()
        private fun docPhoto(o: JSONObject?) = o?.takeIf { it.str("id").isNotBlank() }?.let { DocPhoto(it.str("id"), it.str("row"), it.str("cap"), it.optBoolean("children")) }
        fun from(o: JSONObject?): Story? {
            if (o == null) return null
            val a = o.optJSONObject("answers") ?: JSONObject(); val ph = o.optJSONObject("photos") ?: JSONObject(); val pr = o.optJSONObject("prompts") ?: JSONObject()
            val d = o.optJSONObject("doc")?.takeIf { it.optJSONArray("sections") != null }?.let { d ->
                val secs = d.optJSONArray("sections")!!
                StoryDoc(d.str("title"), d.str("dek"), (0 until secs.length()).mapNotNull { secs.optJSONObject(it) }.map { x -> DocSection(x.str("h"), x.str("body"), docPhoto(x.optJSONObject("photo"))) },
                    d.optJSONObject("pull")?.takeIf { it.str("quote").isNotBlank() }?.let { it.str("quote") to it.str("who") }, d.str("at"), d.str("by"), d.str("model"))
            }
            val pool = o.optJSONArray("pool")?.let { arr -> (0 until arr.length()).mapNotNull { docPhoto(arr.optJSONObject(it)) } }.orEmpty()
            val pub = o.optJSONObject("pub")?.takeIf { it.str("at").isNotBlank() || it.str("url").isNotBlank() }?.let { StoryPub(it.str("at"), it.str("by"), it.str("url")) }
            return Story(o.str("status").ifBlank { "not-started" }, o.str("consent").ifBlank { "not-yet" }, StoryKit.QS.associateWith { a.str(it) }, o.str("quote"), o.str("quoteBy"), o.str("credit"), o.str("text"),
                StoryKit.ROWS.associateWith { r -> ph.optJSONArray(r)?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { StoryPhoto(it.str("id"), it.optBoolean("children"), it.str("by"), it.str("at"), it.str("cap"), it.str("taken")) } }.orEmpty() },
                o.optJSONArray("missing")?.let { m -> (0 until m.length()).map { m.optString(it) } }.orEmpty(), o.optBoolean("canApprove"),
                pr.keys().asSequence().mapNotNull { k -> pr.optJSONObject(k)?.let { k to (it.str("en") to it.str("sw")) } }.toMap(),
                d, pool, pub, o.optInt("newPhotos"),
                o.optJSONObject("dates")?.let { x -> StoryDates(x.ym("before"), x.ym("progress"), x.ym("progressEnd"), x.ym("after")) } ?: StoryDates(), o.str("cover"))
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
            k == "dates" -> t("dates missing", "tarehe hazipo")
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
    val up = m.storyUp
    LaunchedEffect(p.id) { up.resume(); val r = m.api.get("/api/story?id=" + Api.enc(p.id)); if (r.ok) s = Story.from(r.json.optJSONObject("story")) else err = reasonText(r.reason) }
    // each photo that reaches the server brings the story with it
    val freshJ = up.fresh[p.id]
    LaunchedEffect(freshJ) { if (freshJ != null) Story.from(freshJ)?.let { s = it } }
    /** Every POST answers with the fresh story. */
    fun post(body: JSONObject, ok: String? = null, after: (Reply) -> Unit = {}) = scope.launch {
        val r = m.api.postTo("/api/story", body.put("id", p.id))
        if (r.ok) { Story.from(r.json.optJSONObject("story"))?.let { s = it }; if (ok != null) said = "ok" to ok; m.refresh(quiet = true) } else said = "bad" to StoryKit.why(r)
        after(r)
    }
    // adding photos: the sheet (which row, gallery or camera), the camera tray, the review sheet; kept if Android
    // closes the app while the camera is open
    val ctx = LocalContext.current
    var addRow by rememberSaveable(p.id) { mutableStateOf<String?>(null) }
    var pickRow by rememberSaveable(p.id) { mutableStateOf("after") }
    var camPath by rememberSaveable(p.id) { mutableStateOf<String?>(null) }
    var shots by rememberSaveable(p.id) { mutableStateOf(ArrayList<String>()) }
    var review by rememberSaveable(p.id) { mutableStateOf<ArrayList<String>?>(null) }
    var reviewCam by rememberSaveable(p.id) { mutableStateOf(false) }
    var kids by rememberSaveable(p.id) { mutableStateOf(ArrayList<String>()) }
    var kidsAsk by rememberSaveable(p.id) { mutableStateOf("") }
    var datesRow by rememberSaveable(p.id) { mutableStateOf<String?>(null) }   // the column whose month is being set
    var detail by rememberSaveable(p.id) { mutableStateOf<String?>(null) }   // "row/asset"
    var askDraft by remember { mutableStateOf(false) }
    fun toReview(files: List<String>, cam: Boolean) { review = ArrayList(files); reviewCam = cam; kids = ArrayList(); kidsAsk = "" }
    fun camFile() = File(File(ctx.cacheDir, "cam").apply { mkdirs() }, "s${System.currentTimeMillis()}.jpg")
    val cam = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> val f = camPath; camPath = null; if (ok && f != null && File(f).length() > 0) shots = ArrayList(shots + f) }
    fun shoot() {
        PhotoPick.fakeFile?.let { shots = ArrayList(shots + it().path); return }
        val f = camFile(); camPath = f.path
        runCatching { cam.launch(FileProvider.getUriForFile(ctx, "org.rafikioutreach.pin.files", f)) }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val files = withContext(Dispatchers.IO) { uris.mapIndexedNotNull { i, u -> runCatching { File(File(ctx.cacheDir, "cam").apply { mkdirs() }, "g${System.currentTimeMillis()}_$i.jpg").also { f -> ctx.contentResolver.openInputStream(u)!!.use { inp -> f.outputStream().use { o -> inp.copyTo(o) } } }.path }.getOrNull() } }
            if (files.isNotEmpty()) toReview(files, false)
        }
    }
    fun choose() {
        StoryPick.fakeGallery?.let { f -> toReview(f().map { it.path }, false); return }
        runCatching { gallery.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    }
    val st = s
    // scroll targets for the Still needed cards
    val atConsent = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val atQuestions = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val atDraft = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    Column(Modifier.fillMaxWidth().testTag("story_tab")) {
        if (st == null) { Text(err ?: t("Loading…", "Inapakia…"), style = MaterialTheme.typography.bodyMedium, color = if (err != null) C.bad else C.muted, modifier = Modifier.testTag("story_loading")); return }
        // 1. where it is, and what's still needed (each card goes and does it)
        StoryStepper(st)
        StoryNeedCards(st, addTo = { pickRow = it; addRow = it }, toConsent = { scope.launch { atConsent.bringIntoView() } }, toQuestions = { scope.launch { atQuestions.bringIntoView() } },
            toDraft = { scope.launch { atDraft.bringIntoView() } }, toDates = { datesRow = storyDatesMissing(st).firstOrNull() ?: "before" })
        StoryNewPhotos(st, admin) { askDraft = true }
        said?.let { (tone, text) -> Box(Modifier.padding(top = 8.dp).testTag("story_msg")) { Msg(text, tone) } }
        // 2. photos: before, in progress, after
        StoryPhotosCard(m, p.id, st, up.items.toList(), add = { pickRow = it; addRow = it }, dates = { datesRow = it }) { r, ph -> detail = "$r/${ph.id}" }
        // 3. the six questions, one at a time
        Box(Modifier.bringIntoViewRequester(atQuestions)) { StoryQuestions(m, p, st) { body, ok -> post(body, ok) } }
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
            Text(t("Photo consent", "Idhini ya picha"), style = MaterialTheme.typography.labelMedium, color = C.ink, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp).bringIntoViewRequester(atConsent))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("received" to t("Received", "Imepokelewa"), "not-yet" to t("Not yet", "Bado")).forEach { (k, l) ->
                    Box(Modifier.weight(1f).testTag("story_consent_$k")) { Pill(l, st.consent == k, Modifier.fillMaxWidth()) { if (st.consent != k) post(JSONObject().put("action", "consent").put("value", k)) } }
                }
            }
            if (st.photos.values.flatten().any { it.children }) Text(t("Children are in a photo, so consent is needed before it’s approved.", "Kuna watoto kwenye picha, kwa hiyo idhini inahitajika kabla ya kuidhinishwa."),
                style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 6.dp).testTag("story_children_note"))
        }
        // 6. the story itself: the drafted story as sections with photos (web v74), Approve and publish, On the website
        StoryDraftCard(m, p, st, admin, Modifier.padding(top = 12.dp).bringIntoViewRequester(atDraft), { b, ok, after -> post(b, ok, after) }) { askDraft = true }
    }
    if (st == null) return
    // --- the sheets ------------------------------------------------------------------------------------------------
    addRow?.let { StoryAddSheet(p.title, pickRow, { pickRow = it }, { addRow = null }, { choose() }, { shots = ArrayList(); shoot() }) }
    if (shots.isNotEmpty() && review == null) StoryShotsTray(shots, { shots = ArrayList() }, { shoot() }) { f -> shots = ArrayList(); toReview(f, true) }
    review?.let { files ->
        StoryReviewSheet(pickRow, files, kids.toSet(), kidsAsk, remove = { f -> review = ArrayList(files - f); kids = ArrayList(kids - f); if (files.size <= 1) review = null }, setAsk = { kidsAsk = it; if (it == "no") kids = ArrayList() },
            toggle = { f -> kids = ArrayList(if (f in kids) kids - f else kids + f) }, close = { review = null }) {
            val go = files.map { File(it) to (it in kids) }; val row = pickRow; val camera = reviewCam
            review = null
            scope.launch { up.add(p.id, row, go, camera) }
        }
    }
    detail?.let { d ->
        val (r, id) = d.split("/", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val ph = st.photos[r]?.firstOrNull { it.id == id }
        if (ph == null) { LaunchedEffect(d) { detail = null } } else {
            fun edit(extra: JSONObject.() -> Unit, ok: String? = null) = post(JSONObject().put("action", "photo-edit").put("row", r).put("asset", id).apply(extra), ok)
            // the story's cover (web v78): this photo, or "" for the automatic choice; the server republishes a live story
            var coverBusy by remember(id) { mutableStateOf(false) }
            var coverErr by remember(id) { mutableStateOf<String?>(null) }
            val isCover = st.cover == id
            StoryPhotoSheet(m, p.id, r, ph, id in st.inStory, close = { cap -> detail = null; if (cap.trim() != ph.cap) edit({ put("cap", cap.trim()) }, t("Caption saved ✓", "Maelezo yamehifadhiwa ✓")) },
                move = { to -> post(JSONObject().put("action", "photo-edit").put("row", r).put("asset", id).put("to", to), t("Moved to ${StoryKit.row(to)} ✓", "Imehamishiwa ${StoryKit.row(to)} ✓")) { if (it.ok) detail = "$to/$id" } },
                kids = { k -> edit({ put("children", k) }) },
                delete = { detail = null; post(JSONObject().put("action", "photo-remove").put("row", r).put("asset", id), t("Photo deleted", "Picha imefutwa")) },
                cover = isCover, coverBusy = coverBusy, coverErr = coverErr, setCover = {
                    if (!coverBusy) { coverBusy = true; coverErr = null; scope.launch {
                        val res = m.api.postTo("/api/story", JSONObject().put("action", "cover").put("id", p.id).put("asset", if (isCover) "" else id))
                        coverBusy = false
                        if (res.ok) { Story.from(res.json.optJSONObject("story"))?.let { s = it }; said = "ok" to (if (isCover) t("Cover back to automatic", "Jalada limerudi kuchaguliwa lenyewe") else t("Cover photo set ✓", "Picha ya jalada imewekwa ✓")) }
                        else coverErr = StoryKit.why(res)
                    } }
                })
        }
    }
    // the month of a column (Before, In progress from – to, After): saved for the whole column, not per photo
    datesRow?.let { r ->
        var busy by remember(r) { mutableStateOf(false) }
        var bad by remember(r) { mutableStateOf<String?>(null) }
        StoryDatesSheet(r, st.dates, storyDateHint(st, r, p.done), busy, bad, close = { datesRow = null }) { vals ->
            busy = true; bad = null
            scope.launch {
                val body = JSONObject().put("action", "dates").put("id", p.id); vals.forEach { (k, v) -> body.put(k, v) }
                val res = m.api.postTo("/api/story", body)
                busy = false
                if (res.ok) { Story.from(res.json.optJSONObject("story"))?.let { s = it }; datesRow = null; said = "ok" to t("Dates saved ✓", "Tarehe zimehifadhiwa ✓") }
                else bad = storyDateWhy(res.reason, res.json.optString("field"))
            }
        }
    }
    if (askDraft) StoryAsk("story_draft_ask", t("Write the draft again?", "Andika rasimu upya?"), t("A new draft replaces this one, with any changes made to it.", "Rasimu mpya inachukua nafasi ya hii, pamoja na mabadiliko yaliyofanywa."),
        t("Draft it again", "Andika rasimu upya"), false, { askDraft = false }) { askDraft = false; post(JSONObject().put("action", "draft"), t("Draft ready ✓", "Rasimu iko tayari ✓")) }
}

internal fun words(s: String) = s.trim().split(Regex("\\s+")).count { it.isNotBlank() }

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

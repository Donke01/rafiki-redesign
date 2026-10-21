package org.rafikioutreach.pin.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.Lang
import org.rafikioutreach.pin.R
import org.rafikioutreach.pin.data.Note
import org.rafikioutreach.pin.data.Project
import org.rafikioutreach.pin.data.Snapshot
import org.rafikioutreach.pin.data.stateName
import org.rafikioutreach.pin.data.statusName
import org.rafikioutreach.pin.t
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

// --- a project's photo timeline, its donor summary (PDF on the letterhead) and "Water test due" --------------
// The same as the web app (prj… in pin.html). Photos come from the project's notes in monday.com: each visit
// photo is named after its slot ("site-photo-pump-or-tank-close-up-2026-10-02-09-14.jpg").

/** No water test (E. coli, chlorine, fluoride…) for this many days and a finished water project shows "Water test due". */
const val TEST_DAYS = 182

/** A finished borehole or water collection project with no water test recorded in the app in 6 months. */
fun testDue(s: Snapshot, p: Project, now: Long = System.currentTimeMillis()): Boolean {
    if (!isWater(p) || Regex("diligence|progress|construction", RegexOption.IGNORE_CASE).containsMatchIn(p.status)) return false
    val at = s.tests[p.id] ?: return true
    return runCatching { (now - Instant.parse(at).toEpochMilli()) / 864e5 >= TEST_DAYS }.getOrDefault(true)
}
/** How many pinned projects are due a water test (the Office overview, the Due a visit list). */
fun testCount(s: Snapshot, list: List<Project> = s.projects) = list.count { it.gps != null && testDue(s, it) }
/** After a visit that recorded a water test, the tag goes at once (the server keeps the date too). */
fun testedNow(m: AppModel, p: Project, body: JSONObject) { if ((body.optJSONObject("quality")?.length() ?: 0) > 0) m.localTested(p) }

/** Two line icons this file needs (24×24 strokes, like Ic). */
object PrjIc {
    private fun v(name: String, vararg d: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        d.forEach { addPath(addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) }
    }.build()
    val flask by lazy { v("flask", "M9.5 3.5h5M10 3.5v5.5L4.8 18.2A1.5 1.5 0 0 0 6.1 20.5h11.8a1.5 1.5 0 0 0 1.3-2.3L14 9V3.5", "M7.5 14.5h9") }
    val photos by lazy { v("photos", "M6 5h12a2.5 2.5 0 0 1 2.5 2.5v9A2.5 2.5 0 0 1 18 19H6a2.5 2.5 0 0 1-2.5-2.5v-9A2.5 2.5 0 0 1 6 5z", "M7.2 10a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0", "M20.5 15.5l-5-5-9 8.5") }
    val left by lazy { v("left", "M15 6l-6 6 6 6") }
}

@Composable
fun WaterTestTag(modifier: Modifier = Modifier) {
    val (bg, fg) = when { C === Sun -> Color(0xFFD2F2F6) to Color(0xFF003F47); C.dark -> Color(0xFF0E3237) to Color(0xFF7FDCE6); else -> Color(0xFFE0F5F7) to Color(0xFF005F6B) }
    Row(modifier.clip(RoundedCornerShape(999.dp)).background(bg).then(if (C === Sun) Modifier.border(2.dp, fg, RoundedCornerShape(999.dp)) else Modifier).padding(horizontal = 9.dp, vertical = 4.dp).testTag("water_test_due"),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(PrjIc.flask, null, tint = fg, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(4.dp))
        Text(t("Water test due", "Kipimo cha maji kinahitajika"), color = fg, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold), maxLines = 1)
    }
}

// --- photo slots and the timeline ---------------------------------------------------------------------

data class TlItem(val url: String, val at: String, val by: String, val main: Boolean = false)
data class TlGroup(val key: String, val slot: String, val en: String, val sw: String, val items: List<TlItem>) { val title: String get() = t(en, sw) }

private data class SlotDef(val slot: String, val en: String, val sw: String)
/** Every photo slot of every kind of project (the labels the app sends with each photo). */
private val SLOT_DEFS = listOf(
    SlotDef("close", "Pump or tank, close up", "Pampu au tangi, kwa karibu"), SlotDef("flow", "Water flowing", "Maji yakitiririka"), SlotDef("site", "The site with people", "Eneo pamoja na watu"),
    SlotDef("damage", "Any damage", "Uharibifu wowote"), SlotDef("strip", "Water test strip", "Kipimo cha maji"), SlotDef("close", "The water point", "Kituo cha maji"), SlotDef("site", "Building and people", "Jengo na watu"),
    SlotDef("outside", "Outside, the whole building", "Nje, jengo lote"), SlotDef("inside", "A classroom inside", "Ndani ya darasa"), SlotDef("desks", "Desks and boards", "Madawati na ubao"),
    SlotDef("people", "Pupils and teachers", "Wanafunzi na walimu"), SlotDef("equip", "Equipment and beds", "Vifaa na vitanda"), SlotDef("supplies", "Medicine and supplies store", "Stoo ya dawa na vifaa"),
    SlotDef("washrooms", "Water and washrooms", "Maji na vyoo"), SlotDef("dorm", "Dormitory and beds", "Bweni na vitanda"), SlotDef("kitchen", "Kitchen", "Jiko"), SlotDef("washrooms", "Water and latrines", "Maji na vyoo"),
    SlotDef("animals", "The animals", "Mifugo"), SlotDef("pen", "The pen or coop", "Banda"), SlotDef("feed", "Feed and water for the animals", "Chakula na maji ya mifugo"), SlotDef("farmer", "The farmer at work", "Mkulima kazini"),
    SlotDef("inside", "Inside", "Ndani"), SlotDef("people", "People using it", "Watu wakiitumia"), SlotDef("progress", "The whole building now", "Jengo lote sasa"),
    SlotDef("work", "Work done since the last visit", "Kazi iliyofanyika tangu ziara iliyopita"), SlotDef("materials", "Materials on site", "Vifaa vya ujenzi"), SlotDef("issue", "Anything wrong", "Kitu chochote kibaya"),
)
fun slotSlug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)
private val BY_SLUG = SLOT_DEFS.associateBy { slotSlug(it.en) }
private val PHOTO_NAME = Regex("^site-photo-(?:(.*?)-)?(\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2})\\.[a-z0-9]+$", RegexOption.IGNORE_CASE)
private val WHO = Regex("\\bby ([^.:(\\n<]{2,60}?)\\s*(?:\\(RAFIKI pin app\\)|:|\\.)", RegexOption.IGNORE_CASE)
private val IMG_EXT = setOf("jpg", "jpeg", "png", "webp", "gif")

/** The slots in the order the visit asks for them, for this kind of project. */
private fun slotOrder(p: Project): List<String> {
    val ty = p.type.lowercase()
    val en = when {
        isWater(p) -> listOf("Pump or tank, close up", "Water flowing", "The site with people", "Any damage", "Water test strip")
        "under construction" in p.status.lowercase() -> listOf("The whole building now", "Work done since the last visit", "Materials on site", "Anything wrong")
        "school" in ty -> listOf("Outside, the whole building", "A classroom inside", "Desks and boards", "Pupils and teachers", "Any damage")
        "health" in ty -> listOf("Outside, the whole building", "Equipment and beds", "Medicine and supplies store", "Water and washrooms", "Any damage")
        "orphan" in ty -> listOf("Outside, the whole building", "Dormitory and beds", "Kitchen", "Water and latrines", "Any damage")
        "farm" in ty -> listOf("The animals", "The pen or coop", "Feed and water for the animals", "The farmer at work", "Any damage")
        else -> listOf("Outside, the whole building", "Inside", "People using it", "Any damage")
    }
    return en.map { slotSlug(it) }
}

/** Which group a file belongs to: its slot, or "Other photos". */
fun slotOf(name: String): Triple<String, String, Pair<String, String>> {
    val slug = PHOTO_NAME.find(name)?.groupValues?.get(1).orEmpty()
    if (slug.isBlank() || slug == "other-photo") return Triple("other", "other", "Other photos" to "Picha nyingine")
    BY_SLUG[slug]?.let { return Triple(slug, it.slot, it.en to it.sw) }
    val h = slug.replace('-', ' ').replaceFirstChar { it.uppercase() }
    return Triple(slug, slug, h to h)
}
/** Who took it: the note says "Site visit by Grace (RAFIKI pin app)" or "… with the RAFIKI pin app by Grace: …". */
fun photoBy(n: Note): String = WHO.find(n.text.lineSequence().firstOrNull().orEmpty())?.groupValues?.get(1)?.trim() ?: n.by

/** The visit photos grouped by slot, newest first in each; the project's main photo on top; "Other photos" last. */
fun photoGroups(notes: List<Note>, p: Project, mainUrl: String?): List<TlGroup> {
    val g = LinkedHashMap<String, MutableList<TlItem>>(); val meta = HashMap<String, Triple<String, String, Pair<String, String>>>()
    notes.forEach { n ->
        n.files.filter { it.url.isNotBlank() && it.ext.lowercase() in IMG_EXT }.forEach { f ->
            val s = slotOf(f.name); meta[s.first] = s
            g.getOrPut(s.first) { mutableListOf() } += TlItem(f.url, n.at, photoBy(n))
        }
    }
    val order = slotOrder(p)
    val rank = { k: String -> if (k == "other") 99 else order.indexOf(k).takeIf { it >= 0 } ?: 50 }
    val out = g.entries.map { (k, items) -> val s = meta.getValue(k); TlGroup(k, s.second, s.third.first, s.third.second, items.sortedByDescending { it.at }) }
        .sortedWith(compareBy<TlGroup> { rank(it.key) }.thenByDescending { it.items.size })
    return (if (mainUrl != null) listOf(TlGroup("main", "main", "Main photo", "Picha kuu", listOf(TlItem(mainUrl, "", "", main = true)))) else emptyList()) + out
}

private fun loc(): Locale = if (Lang.sw) Locale("sw") else Locale.UK
fun prjDay(iso: String): String = runCatching { DateTimeFormatter.ofPattern("d MMM yyyy", loc()).withZone(ZoneId.systemDefault()).format(Instant.parse(iso)) }.getOrDefault(iso.take(10))

/** Pictures for the timeline and the PDF; tests can hand in their own. */
object PrjPics {
    var fake: ((String) -> Bitmap?)? = null
    fun cached(url: String): Bitmap? = fake?.invoke(url) ?: Pics.cached(url)
    suspend fun load(url: String): Bitmap? = fake?.invoke(url) ?: Pics.load(url)
}

@Composable
private fun PrjImage(url: String, modifier: Modifier, fit: Boolean = false) {
    var bmp by remember(url) { mutableStateOf(PrjPics.cached(url)) }
    LaunchedEffect(url) { if (bmp == null) bmp = PrjPics.load(url) }
    Box(modifier, contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, contentScale = if (fit) ContentScale.Fit else ContentScale.Crop, modifier = Modifier.matchParentSize())
        else Icon(Ic.get("camera"), null, tint = if (fit) Color(0x99FFFFFF) else C.off, modifier = Modifier.size(28.dp))
    }
}

/** Under the project card: open the photo timeline, or share a one-page summary for donors. */
@Composable
fun ProjectExtrasBar(m: AppModel, p: Project, showTimeline: () -> Unit) {
    val ctx = LocalContext.current; val scope = rememberScope()
    var busy by remember(p.id) { mutableStateOf(false) }
    var msg by remember(p.id) { mutableStateOf<String?>(null) }
    Row(Modifier.padding(top = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn(t("Photo timeline", "Picha kwa wakati"), showTimeline, Modifier.weight(1f).testTag("photo_timeline_go"), icon = "camera", light = true, small = true)
        Btn(if (busy) t("Preparing…", "Inaandaa…") else t("Share project summary", "Shiriki muhtasari wa mradi"), {
            busy = true; msg = null
            scope.launch {
                msg = shareSummary(ctx, m, p)
                busy = false
            }
        }, Modifier.weight(1f).testTag("share_summary"), icon = "share", light = true, small = true, enabled = !busy)
    }
    Msg(msg, "bad")
}

/** The timeline at the top of History: one swipeable strip per photo slot, newest first; tap for full screen. */
@Composable
fun PhotoTimeline(m: AppModel, p: Project, notes: List<Note>) {
    val groups = remember(notes, p.id, p.photo) { photoGroups(notes, p, p.photo?.let { m.api.photoUrl(it, 1280) }) }
    var open by remember(p.id) { mutableStateOf<Pair<Int, Int>?>(null) }
    val n = groups.filter { it.key != "main" }.sumOf { it.items.size }
    val visits = groups.filter { it.key != "main" }.flatMap { g -> g.items.map { it.at.take(10) } }.toSet().size
    Card(Modifier.padding(top = 10.dp).testTag("photo_timeline"), pad = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (bg, fg) = when { C === Sun -> Color.White to Color(0xFF3D1F8A); C.dark -> Color(0xFF2A2147) to Color(0xFFC3A9FF); else -> Color(0xFFEFE8FB) to Color(0xFF7A4FD0) }
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(bg).then(if (C === Sun) Modifier.border(2.dp, Color.Black, RoundedCornerShape(12.dp)) else Modifier), contentAlignment = Alignment.Center) {
                Icon(PrjIc.photos, null, tint = fg, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(t("Photo timeline", "Picha kwa wakati"), style = MaterialTheme.typography.titleMedium, color = C.ink, modifier = Modifier.weight(1f))
            if (n > 0) Text(t("$n ${if (n == 1) "photo" else "photos"} · $visits ${if (visits == 1) "visit" else "visits"}", "picha $n · ziara $visits"), style = MaterialTheme.typography.labelSmall, color = C.deep,
                modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(C.tint).padding(horizontal = 10.dp, vertical = 4.dp))
        }
        if (n > 0) Text(t("The same view at each visit, newest first. Swipe along a row to compare; tap a photo to open it.", "Mwonekano ule ule kila ziara, ya karibuni kwanza. Telezesha mstari kulinganisha; gusa picha kuifungua."),
            style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 8.dp))
        groups.forEachIndexed { gi, g ->
            Row(Modifier.padding(top = 14.dp, bottom = 6.dp).fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(g.title, style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.weight(1f))
                Text(if (g.key == "main") t("On the website", "Kwenye tovuti") else t("${g.items.size} ${if (g.items.size == 1) "photo" else "photos"}", "picha ${g.items.size}"), style = MaterialTheme.typography.labelSmall, color = C.muted)
            }
            val st = rememberLazyListState()
            LazyRow(Modifier.fillMaxWidth().testTag("strip_${g.key}"), state = st, flingBehavior = rememberSnapFlingBehavior(st), horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(end = 4.dp)) {
                itemsIndexed(g.items) { ii, it ->
                    val desc = g.title + (if (it.at.isNotBlank()) ", " + prjDay(it.at) else "") + (if (it.by.isNotBlank()) ", " + it.by else "")
                    Column(Modifier.width(if (it.main) 220.dp else 156.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, C.line, RoundedCornerShape(16.dp))
                        .clickable(role = Role.Button) { open = gi to ii }.semantics { contentDescription = desc }.testTag("ph_${g.key}_$ii")) {
                        Box {
                            PrjImage(it.url, Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(C.tint))
                            if (ii == 0 && !it.main && g.items.size > 1) Text(t("Latest", "Karibuni"), color = Color.White, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(99.dp)).background(Green).padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                        Column(Modifier.fillMaxWidth().background(C.bg).padding(horizontal = 10.dp, vertical = 7.dp)) {
                            Text(if (it.main) t("Main photo", "Picha kuu") else prjDay(it.at), style = MaterialTheme.typography.labelMedium, color = C.ink, maxLines = 1)
                            if (it.by.isNotBlank()) Text(it.by, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        if (n == 0) Text(t("No visit photos yet. Photos from each site visit show here, grouped by view, so you can compare them over time.", "Bado hakuna picha za ziara. Picha za kila ziara zinaonekana hapa, kwa mwonekano, ili uzilinganishe kwa wakati."),
            style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 10.dp))
    }
    open?.let { (gi, ii) -> groups.getOrNull(gi)?.let { g -> PhotoViewer(g, ii) { open = null } } }
}

/** Full screen, one slot: swipe or tap the arrows; left is newer, right is older. */
@Composable
fun PhotoViewer(g: TlGroup, start: Int, close: () -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) { PhotoViewerBody(g, start, close) }
}

@Composable
fun PhotoViewerBody(g: TlGroup, start: Int, close: () -> Unit) {
    val pager = rememberPagerState(initialPage = start.coerceIn(0, maxOf(0, g.items.size - 1))) { g.items.size }
    val scope = rememberScope()
    val i = pager.currentPage; val it = g.items.getOrNull(i) ?: return
    fun go(k: Int) { scope.launch { if (Motion.on) pager.animateScrollToPage(k) else pager.scrollToPage(k) } }
    Column(Modifier.fillMaxSize().background(Color(0xF2040C14)).testTag("photo_viewer")) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(g.title, color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text(if (it.main) t("Main photo, on the website", "Picha kuu, kwenye tovuti") else prjDay(it.at) + (if (it.by.isNotBlank()) " · " + it.by else ""), color = Color(0xFFCFE8FA), style = MaterialTheme.typography.bodySmall)
            }
            RoundBtn(Ic.get("x"), t("Close", "Funga"), true, "viewer_close", close)
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth().testTag("viewer_pager"), pageSpacing = 12.dp) { k ->
            PrjImage(g.items[k].url, Modifier.fillMaxSize().padding(horizontal = 8.dp).clip(RoundedCornerShape(10.dp)), fit = true)
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 22.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            RoundBtn(PrjIc.left, t("Newer photo", "Picha mpya zaidi"), i > 0, "viewer_prev") { go(i - 1) }
            Text("${i + 1} / ${g.items.size}", color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 22.dp).testTag("viewer_count"))
            RoundBtn(Ic.get("chev"), t("Older photo", "Picha ya zamani zaidi"), i < g.items.size - 1, "viewer_next") { go(i + 1) }
        }
    }
}

@Composable
private fun RoundBtn(icon: ImageVector, label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Box(Modifier.size(52.dp).clip(CircleShape).background(Color(0x29FFFFFF)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label }.testTag(tag),
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White.copy(alpha = if (enabled) 1f else 0.3f), modifier = Modifier.size(24.dp))
    }
}

// --- the donor-ready summary: only what we have, never made up ------------------------------------------

/** One fact on the summary: label, value, and a status mark (working / repair / notworking) or none. */
data class SumFact(val label: String, val value: String, val icon: String? = null)
data class SumPhoto(val url: String, val caption: String)
data class ProjectSummary(
    val title: String, val sub: String, val photos: List<SumPhoto>, val people: Long?, val peopleLabel: String, val peopleAt: String,
    val quote: String?, val quoteBy: String, val facts: List<SumFact>, val by: String, val date: String,
) {
    val fileName: String get() = "RAFIKI-" + title.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(60) + "-summary.pdf"
}

private val STORY = Regex("Story moment: “([^”]+)”(?: \\(([^)]+)\\))?\\. Consent to quote and photograph: given")
private val NO_DONOR_SLOTS = setOf("damage", "strip", "issue", "work")
private val NO_DONOR = Regex("damage|broken|strip|wrong")
private val PREF = listOf("site", "flow", "people", "outside", "progress", "farmer", "animals", "inside", "dorm", "equip", "desks", "close", "kitchen", "washrooms", "pen", "feed", "supplies", "materials")
private val PPL = listOf(
    Triple("users", Regex("people using it \\(estimate\\): ([\\d,]+)", RegexOption.IGNORE_CASE), "people use it (field estimate)" to "watu wanautumia (makadirio ya uwanjani)"),
    Triple("pupils", Regex("pupils enrolled: ([\\d,]+)", RegexOption.IGNORE_CASE), "pupils enrolled" to "wanafunzi waliosajiliwa"),
    Triple("patients", Regex("patients a week: ([\\d,]+)", RegexOption.IGNORE_CASE), "patients a week" to "wagonjwa kwa wiki"),
    Triple("children", Regex("children living there: ([\\d,]+)", RegexOption.IGNORE_CASE), "children live there" to "watoto wanaishi hapo"),
)

/** Builds the summary from the project, its notes in monday.com and the last visits the server keeps ([visits], newest first). */
fun buildSummary(p: Project, s: Snapshot, notes: List<Note>, visits: List<JSONObject>, mainUrl: String?, by: String, today: LocalDate = LocalDate.now()): ProjectSummary {
    val groups = photoGroups(notes, p, mainUrl)
    val photos = mutableListOf<SumPhoto>()
    groups.firstOrNull { it.key == "main" }?.let { photos += SumPhoto(it.items[0].url, t("RAFIKI project photo", "Picha ya mradi wa RAFIKI")) }
    groups.filter { it.key != "main" && it.slot !in NO_DONOR_SLOTS && !NO_DONOR.containsMatchIn(it.key) }
        .sortedBy { g -> if (g.key == "other") 98 else PREF.indexOf(g.slot).takeIf { it >= 0 } ?: 60 }
        .forEach { g -> if (photos.size < 3) photos += SumPhoto(g.items[0].url, g.title + if (g.items[0].at.isNotBlank()) " · " + prjDay(g.items[0].at) else "") }
    // people served: from the readings the server keeps, or written in a note
    var people: Long? = null; var pLabel = ""; var pAt = ""
    for ((k, re, lbl) in PPL) {
        val label = if (k == "users" && isWater(p)) t("people use this water (field estimate)", "watu wanatumia maji haya (makadirio ya uwanjani)") else t(lbl.first, lbl.second)
        val v = visits.firstOrNull { (it.optJSONObject("readings")?.has(k) == true && !it.optJSONObject("readings")!!.isNull(k)) || (it.optJSONObject("numbers")?.has(k) == true && !it.optJSONObject("numbers")!!.isNull(k)) }
        if (v != null) { people = (v.optJSONObject("readings")?.takeIf { it.has(k) && !it.isNull(k) } ?: v.optJSONObject("numbers")!!).optDouble(k).toLong(); pLabel = label; pAt = v.optString("at"); break }
        val n = notes.firstOrNull { re.containsMatchIn(it.text) }
        if (n != null) { people = re.find(n.text)!!.groupValues[1].replace(",", "").toLongOrNull(); pLabel = label; pAt = n.at; if (people != null) break }
    }
    val story = notes.firstNotNullOfOrNull { STORY.find(it.text) }
    val facts = mutableListOf<SumFact>()
    if (p.type.isNotBlank()) facts += SumFact(t("Type", "Aina"), p.type)
    val where = listOf(p.area, p.country).filter { it.isNotBlank() }.joinToString(", ")
    if (where.isNotBlank()) facts += SumFact(t("Location", "Mahali"), where)
    listOf(statusName(p.status), p.year?.toString().orEmpty()).filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotBlank() }?.let { facts += SumFact(t("Project", "Mradi"), it) }
    s.visits[p.id]?.let { v -> facts += SumFact(t("Last visit", "Ziara ya mwisho"), prjDay(v.at) + " · " + stateName(v.state), v.state.takeIf { it in setOf("working", "repair", "notworking") }) }
    s.tests[p.id]?.let { facts += SumFact(t("Water last tested", "Maji yalipimwa mwisho"), prjDay(it)) }
    s.repairs[p.id]?.let { r -> facts += SumFact(t("Open repair", "Matengenezo yaliyo wazi"), stateName(r.state) + ", " + t("reported ", "iliripotiwa ") + prjDay(r.at) + if (r.problems.isNotEmpty()) ": " + r.problems.joinToString(", ") else "", if (r.state == "notworking") "notworking" else "repair") }
    return ProjectSummary(p.title, listOf(p.type, where).filter { it.isNotBlank() }.joinToString(" · ") + " · RAFIKI Outreach", photos, people, pLabel, pAt,
        story?.groupValues?.get(1)?.trim(), story?.groupValues?.get(2)?.trim().orEmpty(), facts, by, DateTimeFormatter.ofPattern("d MMMM yyyy", loc()).format(today))
}

/** For tests: the last summary made, before it went into the PDF. */
object SummaryProbe { var last: ProjectSummary? = null }

/** Gathers the notes, visits and photos, writes the PDF and opens the share sheet. Returns a message if something went wrong. */
suspend fun shareSummary(ctx: Context, m: AppModel, p: Project): String? {
    val snap = m.snap.value ?: return null
    val notes = m.history(p.id).first.orEmpty()
    val last = m.api.last(p.id)
    val va = last.json.optJSONArray("visits")
    val visits = if (last.ok && va != null) (0 until va.length()).mapNotNull { va.optJSONObject(it) } else emptyList()
    val sum = buildSummary(p, snap, notes, visits, p.photo?.let { m.api.photoUrl(it, 1280) }, m.prefs.name)
    val bmps = sum.photos.map { PrjPics.load(it.url) }
    val ok = sum.copy(photos = sum.photos.filterIndexed { i, _ -> bmps[i] != null })
    SummaryProbe.last = ok
    val home = snap.me.home.ifBlank { m.prefs.country }
    return withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
            val f = File(dir, ok.fileName); writeSummaryPdf(ctx, ok, home, bmps.filterNotNull(), f); f
        }
    }.fold({ f ->
        val uri = FileProvider.getUriForFile(ctx, "org.rafikioutreach.pin.files", f)
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, ok.title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), ok.fileName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    }, { t("Couldn’t make the PDF on this phone.", "Haikuweza kutengeneza PDF kwenye simu hii.") })
}

/** One A4 page (595×842pt). */
fun writeSummaryPdf(ctx: Context, s: ProjectSummary, home: String, photos: List<Bitmap>, f: File) {
    val doc = PdfDocument()
    try {
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        drawSummaryPage(ctx, s, home, photos, page.canvas)
        doc.finishPage(page)
        f.outputStream().use { doc.writeTo(it) }
    } finally { doc.close() }
}

private const val SM = 43.2f
private const val SCW = 595f - 2 * SM

/** The summary on the RAFIKI letterhead: title, up to 3 photos, people served, the story, then the facts. [photos] match s.photos. */
fun drawSummaryPage(ctx: Context, s: ProjectSummary, home: String, photos: List<Bitmap>, c: Canvas) {
    val base: Typeface = runCatching { ResourcesCompat.getFont(ctx, R.font.montserrat) }.getOrNull() ?: Typeface.SANS_SERIF
    fun p(size: Float, weight: Int, color: Int, italic: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color; isSubpixelText = true
        typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, weight, italic) else Typeface.create(base, if (weight >= 600) Typeface.BOLD else if (italic) Typeface.ITALIC else Typeface.NORMAL)
        if (Build.VERSION.SDK_INT >= 26) fontVariationSettings = "'wght' $weight"
    }
    fun fit(t: String, pt: Paint, w: Float): String { if (pt.measureText(t) <= w) return t; var e = t; while (e.isNotEmpty() && pt.measureText(e.trimEnd() + "…") > w) e = e.dropLast(1); return e.trimEnd() + "…" }
    fun wrap(t: String, pt: Paint, w: Float, max: Int): List<String> {
        val out = mutableListOf<String>(); var cur = ""
        t.split(' ').forEach { word -> val n = if (cur.isEmpty()) word else "$cur $word"; if (cur.isEmpty() || pt.measureText(n) <= w) cur = n else { out += cur; cur = word } }
        if (cur.isNotEmpty()) out += cur
        return if (out.size > max) out.take(max - 1) + fit(out.drop(max - 1).joinToString(" "), pt, w) else out.map { fit(it, pt, w) }
    }
    drawLetterheadTop(ctx, c, home)
    var y = 124f
    val title = p(19f, 700, 0xFF04263F.toInt()); val sub = p(9.5f, 500, 0xFF5A6066.toInt())
    c.drawText(fit(s.title, title, SCW), SM, y + 17f, title); c.drawText(fit(s.sub, sub, SCW), SM, y + 33f, sub); y += 46f
    // photos: one wide, or two or three side by side, cropped to fill
    val n = minOf(photos.size, s.photos.size)
    if (n > 0) {
        val gap = 8f; val w = (SCW - (n - 1) * gap) / n; val h = if (n == 1) SCW * 9f / 16f else w * 3f / 4f
        val cap = p(7.5f, 500, 0xFF5A6066.toInt()); val img = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        for (i in 0 until n) {
            val r = RectF(SM + i * (w + gap), y, SM + i * (w + gap) + w, y + h); val b = photos[i]
            val sc = max(r.width() / b.width, r.height() / b.height); val sw = r.width() / sc; val sh = r.height() / sc
            c.save(); c.clipPath(Path().apply { addRoundRect(r, 6f, 6f, Path.Direction.CW) })
            c.drawBitmap(b, Rect(((b.width - sw) / 2).toInt(), ((b.height - sh) / 2).toInt(), ((b.width + sw) / 2).toInt(), ((b.height + sh) / 2).toInt()), r, img); c.restore()
            c.drawText(fit(s.photos[i].caption, cap, w), r.left, y + h + 10f, cap)
        }
        y += h + 22f
    }
    // people served
    s.people?.let { ppl ->
        val big = p(24f, 800, 0xFF076DB1.toInt()); val lab = p(10.5f, 700, 0xFF04263F.toInt()); val at = p(8f, 500, 0xFF5A6066.toInt())
        c.drawRoundRect(RectF(SM, y, SM + SCW, y + 46f), 9f, 9f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE6F2FB.toInt() })
        val num = "%,d".format(Locale.US, ppl); c.drawText(num, SM + 14f, y + 32f, big)
        val x = SM + 14f + big.measureText(num) + 12f
        c.drawText(fit(s.peopleLabel, lab, SM + SCW - x - 10f), x, y + (if (s.peopleAt.isNotBlank()) 21f else 27f), lab)
        if (s.peopleAt.isNotBlank()) c.drawText(t("Counted at the visit on ", "Ilihesabiwa kwenye ziara ya ") + prjDay(s.peopleAt), x, y + 34f, at)
        y += 58f
    }
    // the story, with consent
    s.quote?.let { q ->
        val qp = p(11.5f, 500, 0xFF141618.toInt(), italic = true); val wp = p(8.5f, 700, 0xFF5A6066.toInt())
        val lines = wrap("“$q”", qp, SCW - 30f, 6); val h = 14f + lines.size * 15f + (if (s.quoteBy.isNotBlank()) 14f else 0f) + 6f
        c.drawRoundRect(RectF(SM, y, SM + SCW, y + h), 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFF7E8.toInt() })
        c.drawRect(RectF(SM, y, SM + 3.5f, y + h), Paint().apply { color = 0xFFF5A623.toInt() })
        lines.forEachIndexed { i, l -> c.drawText(l, SM + 16f, y + 22f + i * 15f, qp) }
        if (s.quoteBy.isNotBlank()) c.drawText(fit("— " + s.quoteBy, wp, SCW - 30f), SM + 16f, y + 22f + lines.size * 15f + 4f, wp)
        y += h + 14f
    }
    // the facts
    if (s.facts.isNotEmpty()) {
        val hp = p(12.5f, 700, 0xFF076DB1.toInt()); c.drawText(t("About the project", "Kuhusu mradi"), SM, y + 12.5f, hp); y += 24f
        val lp = p(9f, 700, 0xFF5A6066.toInt()); val vp = p(9f, 500, 0xFF141618.toInt()); val lw = 112f
        s.facts.forEach { f ->
            val vx = SM + lw + (if (f.icon != null) 12f else 0f)
            val lines = wrap(f.value, vp, SM + SCW - vx, 3)
            if (y + lines.size * 12.5f > LETTERHEAD_BODY_BOTTOM - 40f) return@forEach
            c.drawText(fit(f.label, lp, lw - 8f), SM, y + 9f, lp)
            f.icon?.let { drawStateIcon(c, it, SM + lw, y + 1.2f, 8.5f) }
            lines.forEachIndexed { i, l -> c.drawText(l, vx, y + 9f + i * 12.5f, vp) }
            y += lines.size * 12.5f + 5f
        }
    }
    val note = p(8f, 500, 0xFF5A6066.toInt())
    val foot = t("Prepared by ", "Imeandaliwa na ") + s.by + ", " + s.date + ". " + t("From RAFIKI’s own field visits (RAFIKI Pin app and monday.com).", "Kutoka kwa ziara za RAFIKI zenyewe (programu ya RAFIKI Pin na monday.com).") +
        if (s.quote != null) " " + t("The person quoted agreed to be quoted and photographed.", "Aliyenukuliwa alikubali kunukuliwa na kupigwa picha.") else ""
    wrap(foot, note, SCW, 3).forEachIndexed { i, l -> c.drawText(l, SM, minOf(y + 16f, LETTERHEAD_BODY_BOTTOM - 30f) + i * 11f, note) }
    drawLetterheadFoot(ctx, c)
}

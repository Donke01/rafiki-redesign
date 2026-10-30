package org.rafikioutreach.pin.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.StoryUploads
import org.rafikioutreach.pin.t
import java.io.File

// Story photos (2.27.0; design/story-photos-2026-10, Figma lj3LnAX52FUaDKWs86zJiO): the story's steps, what is still
// needed as cards, the Photos card (rows, a 3-column grid, "In story" badges, dashed Add photos tiles), the add flow
// (gallery with several at once, or take photos one after another), the review sheet (children?), tiles that fill
// as they send, a banner over the bottom bar, and a details sheet per photo (caption, row, children, delete).

/** Tests: photos "chosen in the gallery" without the system picker. */
internal object StoryPick { var fakeGallery: (() -> List<File>)? = null }

private val NIGHT = Color(0xFF04263F)
private val NIGHT2 = Color(0xFF06375A)
private val MINT = Color(0xFF7FE3B0)
private val SEND = Color(0xFF2FBF71)

/** The four steps: Collecting → Draft ready → Approved → On the website, as dots on a line with their names. */
@Composable
internal fun StoryStepper(st: Story) {
    val steps = listOf("collecting" to t("Collecting", "Inakusanywa"), "draft" to t("Draft ready", "Rasimu iko tayari"), "approved" to t("Approved", "Imeidhinishwa"), "published" to t("On the website", "Kwenye tovuti"))
    val cur = when { st.live -> 3; st.status == "approved" -> 2; st.status == "draft" -> 1; else -> 0 }
    Card(Modifier.testTag("story_status"), pad = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("The story", "Hadithi"), style = MaterialTheme.typography.titleLarge, color = C.ink, modifier = Modifier.weight(1f))
            val (bg, fg) = if (st.live) C.okBg to C.ok else C.tint to C.blue2
            Text(if (st.status == "not-started" && !st.live) t("Not started", "Haijaanza") else steps[cur].second, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(bg).padding(horizontal = 10.dp, vertical = 5.dp).testTag("story_now"))
        }
        Box(Modifier.padding(top = 14.dp).fillMaxWidth()) {
            // the line behind the dots, from the first dot's middle to the last's
            val lineDone = C.ok; val lineTodo = C.line
            Canvas(Modifier.fillMaxWidth().height(28.dp)) {
                val col = size.width / 4f; val y = size.height / 2
                for (i in 0 until 3) drawLine(if (i < cur || (st.live && i < 3)) lineDone else lineTodo, Offset(col * i + col / 2 + 16.dp.toPx(), y), Offset(col * (i + 1) + col / 2 - 16.dp.toPx(), y), 2.dp.toPx(), StrokeCap.Round)
            }
            Row(Modifier.fillMaxWidth()) {
                steps.forEachIndexed { i, (k, label) ->
                    val done = i < cur || (st.live && i == 3); val now = i == cur && !st.live && st.status != "not-started"
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) { stateDescription = if (done) t("done", "imekamilika") else if (now) t("now", "sasa") else t("to come", "inakuja") }.testTag("story_st_$k"),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(when { done -> C.ok; now -> C.card; else -> C.line }).then(if (now) Modifier.border(3.dp, C.blue2, CircleShape) else Modifier), contentAlignment = Alignment.Center) {
                            if (done) Icon(Ic.get("check"), null, tint = Color.White, modifier = Modifier.size(16.dp))
                            else if (now) Box(Modifier.size(12.dp).clip(CircleShape).background(C.blue2))
                        }
                        FitWords(label, MaterialTheme.typography.labelSmall.copy(textAlign = TextAlign.Center, fontWeight = if (now) FontWeight.ExtraBold else FontWeight.SemiBold),
                            if (done || now) C.ink else C.muted, Modifier.padding(top = 6.dp).fillMaxWidth(), min = 7f)
                    }
                }
            }
        }
    }
}

/** What is still needed, as cards that each go and do it. */
@Composable
internal fun StoryNeedCards(st: Story, addTo: (String) -> Unit, toConsent: () -> Unit, toQuestions: () -> Unit, toDraft: () -> Unit, toDates: () -> Unit) {
    if (st.live) return
    val miss = if (st.status in setOf("approved", "published")) emptyList() else st.missing
    if (miss.isEmpty()) return
    Column(Modifier.padding(top = 12.dp).testTag("story_missing"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        miss.forEach { k ->
            when {
                k.endsWith("-photos") -> { val r = k.removeSuffix("-photos"); val row = if (r in StoryKit.ROWS) r else "after"
                    NeedCard("story_need_$row", "camera", when (row) { "before" -> t("Add before photos", "Ongeza picha za kabla"); "progress" -> t("Add in-progress photos", "Ongeza picha za wakati wa kazi"); else -> t("Add after photos", "Ongeza picha za baada") },
                        when (row) { "after" -> t("The story ends with what changed. 2–3 photos of the finished project.", "Hadithi inaishia na kilichobadilika. Picha 2–3 za mradi uliokamilika."); "before" -> t("How it was before the work began.", "Ilivyokuwa kabla kazi haijaanza."); else -> t("The work as it happened.", "Kazi ilivyokuwa ikifanyika.") },
                        t("Add", "Ongeza"), "camera") { addTo(row) } }
                k.startsWith("answers:") -> { val (a, b) = k.removePrefix("answers:").split("/").let { (it.getOrNull(0) ?: "0") to (it.getOrNull(1) ?: "6") }
                    NeedCard("story_need_answers", "text", t("Answer the questions", "Jibu maswali"), t("$a of $b answers so far.", "Majibu $a kati ya $b hadi sasa."), t("Answer", "Jibu"), "arrow", toQuestions) }
                k == "consent" -> NeedCard("story_need_consent", "shield", t("Photo consent", "Idhini ya picha"), t("Not received yet. It is needed before photos of children go on the website.", "Bado haijapokelewa. Inahitajika kabla picha za watoto hazijaenda kwenye tovuti."), t("Open", "Fungua"), "arrow", toConsent)
                k == "dates" -> storyDatesMissing(st).takeIf { it.isNotEmpty() }?.let { cols ->
                    val names = cols.map { StoryKit.row(it) }.let { n -> if (n.size == 1) n[0] else n.dropLast(1).joinToString(", ") + t(" and ", " na ") + n.last() }
                    NeedCard("story_need_dates", "cal", t("Add the dates for $names", "Ongeza tarehe za $names"), t("The month of each part, so the story tells things in the right order.", "Mwezi wa kila sehemu, ili hadithi ieleze mambo kwa mpangilio sahihi."),
                        t("Add", "Ongeza"), "cal", toDates) }
                k == "text" -> NeedCard("story_need_text", "spark", t("Draft the story", "Andika rasimu ya hadithi"), t("When the answers and photos are in.", "Majibu na picha zikiwa tayari."), t("Open", "Fungua"), "arrow", toDraft)
            }
        }
    }
}

@Composable
private fun NeedCard(tag: String, icon: String, title: String, sub: String, action: String, actionIcon: String, go: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val btnBg = if (C.dark) Color(0xFFF2B35B) else Color(0xFF7A4A00)
    val btnFg = if (C.dark) Color(0xFF071521) else Color.White
    val btn = @Composable { mod: Modifier ->
        Row(mod.clip(RoundedCornerShape(99.dp)).background(btnBg).clickable(role = Role.Button, onClick = go).heightIn(min = 44.dp).padding(horizontal = 14.dp).testTag(tag + "_go"), verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.get(actionIcon), null, tint = btnFg, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
            Text(action, style = MaterialTheme.typography.labelLarge, color = btnFg, maxLines = 1)
        }
    }
    // on a narrow phone the button goes under the words, so they keep the width
    BoxWithConstraints(Modifier.fillMaxWidth().clip(shape).background(C.warnBg).border(1.dp, C.warn.copy(alpha = .35f), shape).padding(horizontal = 14.dp, vertical = 12.dp).testTag(tag)) {
        val narrow = maxWidth < 300.dp
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.get(icon), null, tint = C.warn, modifier = Modifier.size(22.dp).then(if (narrow) Modifier.align(Alignment.Top) else Modifier))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = C.ink)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = C.text, modifier = Modifier.padding(top = 2.dp))
                if (narrow) btn(Modifier.padding(top = 10.dp))
            }
            if (!narrow) { Spacer(Modifier.width(10.dp)); btn(Modifier) }
        }
    }
}

/** "2 new photos since this draft — Draft again to use them" (or, on the website, where admins add them). */
@Composable
internal fun StoryNewPhotos(st: Story, admin: Boolean, draftAgain: () -> Unit) {
    val n = st.newPhotos
    if (n <= 0 || st.doc == null) return
    if (st.live && !admin) return
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(shape).background(C.tint).border(1.dp, C.blue.copy(alpha = .3f), shape).padding(14.dp).testTag("story_newphotos")) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Ic.get("spark"), null, tint = C.blue2, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
            Text(if (st.live) (if (n == 1) t("1 new photo since this story went on the website. Add it in the story editor below.", "Picha 1 mpya tangu hadithi hii iwekwe kwenye tovuti. Iongeze kwenye kihariri cha hadithi hapa chini.")
                else t("$n new photos since this story went on the website. Add them in the story editor below.", "Picha $n mpya tangu hadithi hii iwekwe kwenye tovuti. Ziongeze kwenye kihariri cha hadithi hapa chini."))
                else if (n == 1) t("1 new photo since this draft. Draft again to use it.", "Picha 1 mpya tangu rasimu hii. Andika rasimu upya ili kuitumia.")
                else t("$n new photos since this draft. Draft again to use them.", "Picha $n mpya tangu rasimu hii. Andika rasimu upya ili kuzitumia."),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = C.ink, modifier = Modifier.weight(1f).testTag("story_newphotos_text"))
        }
        if (!st.live) Btn(t("Draft it again", "Andika rasimu upya"), draftAgain, Modifier.padding(top = 10.dp).fillMaxWidth().testTag("story_newphotos_draft"), icon = "spark", light = true, small = true)
    }
}

@OptIn(ExperimentalLayoutApi::class)
/** The Photos card: Before, In progress and After, each a 3-column grid that ends with a dashed Add photos tile. */
@Composable
internal fun StoryPhotosCard(m: AppModel, pid: String, st: Story, ups: List<StoryUploads.Up>, add: (String) -> Unit, dates: (String) -> Unit, open: (String, StoryPhoto) -> Unit) {
    val inStory = st.inStory
    val shown = StoryKit.ROWS.flatMap { st.photos[it].orEmpty() }.count { it.id in inStory }
    Card(Modifier.padding(top = 12.dp).testTag("story_photos"), pad = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("Photos", "Picha"), style = MaterialTheme.typography.titleLarge, color = C.ink, modifier = Modifier.weight(1f))
            if (shown > 0) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("story_instory_n")) {
                Icon(Ic.get("spark"), null, tint = C.ok, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
                Text(t("$shown in the story", "$shown kwenye hadithi"), style = MaterialTheme.typography.labelMedium, color = C.ok)
            }
        }
        StoryKit.ROWS.forEach { r ->
            val server = st.photos[r].orEmpty()
            val ids = server.map { it.id }.toSet()
            val mine = ups.filter { it.row == r && it.project == pid }
            val ticked = mine.filter { it.state == "done" && it.asset in ids }.map { it.asset }.toSet()
            val local = mine.filter { !(it.state == "done" && it.asset in ids) }
            Column(Modifier.padding(top = 14.dp).testTag("story_row_$r")) {
                // the title, the count and the column's month; the month moves to its own line when they don't fit
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(StoryKit.row(r), style = MaterialTheme.typography.titleSmall, color = C.ink, maxLines = 1, modifier = Modifier.align(Alignment.CenterVertically))
                    Text("${server.size + local.count { it.state != "done" }}", style = MaterialTheme.typography.labelMedium, color = C.muted,
                        modifier = Modifier.align(Alignment.CenterVertically).clip(RoundedCornerShape(99.dp)).background(C.bg).padding(horizontal = 8.dp, vertical = 2.dp).testTag("story_count_$r"))
                    // the column's month ("Nov 2024", "Jan – Mar 2025"), or a small Add date; tap to set it
                    val when_ = colDateText(st.dates, r)
                    val need = when_ == null && "dates" in st.missing && server.isNotEmpty()
                    Row(Modifier.align(Alignment.CenterVertically).clip(RoundedCornerShape(99.dp)).background(if (when_ != null) Color.Transparent else if (need) C.warnBg else C.tint).clickable(role = Role.Button) { dates(r) }
                        .heightIn(min = 36.dp).padding(horizontal = 10.dp).semantics(mergeDescendants = true) { contentDescription = StoryKit.row(r) + " · " + (when_ ?: t("Add date", "Ongeza tarehe")) }.testTag("story_date_$r"),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Ic.get("cal"), null, tint = if (need) C.warn else C.blue2, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(5.dp))
                        Text(when_ ?: t("Add date", "Ongeza tarehe"), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = if (need) C.warn else C.blue2, maxLines = 1,
                            modifier = Modifier.testTag("story_date_text_$r"))
                    }
                }
                val cells: List<@Composable (Modifier) -> Unit> = server.mapIndexed { i, ph -> @Composable { mod: Modifier ->
                    ServerTile(m, pid, r, i, ph, ph.id in inStory, ph.id in ticked, ph.id == st.cover, mod) { open(r, ph) } } } +
                    local.map { u -> @Composable { mod: Modifier -> LocalTile(m, u, mod) } } +
                    listOf(@Composable { mod: Modifier -> AddTile(r, mod) { add(r) } })
                Grid3(cells, Modifier.padding(top = 8.dp))
            }
        }
        Text(t("Tap a photo to add a caption, move it to another row, or mark that it shows children.", "Gusa picha kuongeza maelezo, kuihamisha safu nyingine, au kuonyesha kwamba ina watoto."),
            style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 12.dp).testTag("story_photos_hint"))
    }
}

@Composable
private fun Grid3(cells: List<@Composable (Modifier) -> Unit>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cells.chunked(3).forEach { line ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                line.forEach { c -> c(Modifier.weight(1f).aspectRatio(1f)) }
                repeat(3 - line.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private val TILE = RoundedCornerShape(16.dp)

@Composable
private fun ServerTile(m: AppModel, pid: String, row: String, i: Int, ph: StoryPhoto, inStory: Boolean, sent: Boolean, cover: Boolean, modifier: Modifier, tap: () -> Unit) {
    Box(modifier.clip(TILE).background(C.tint).clickable(role = Role.Button, onClick = tap)
        .semantics { contentDescription = StoryKit.row(row) + " · " + t("photo ${i + 1}", "picha ${i + 1}") + (if (cover) " · " + t("cover photo", "picha ya jalada") else "") + (if (inStory) " · " + t("in the story", "kwenye hadithi") else "") + (if (ph.cap.isNotBlank()) " · " + ph.cap else "") }
        .testTag("story_photo_${row}_$i")) {
        AuthPic(m, StoryKit.photoPath(pid, ph.id), Modifier.matchParentSize())
        if (inStory) InStoryBadge(Modifier.align(Alignment.BottomStart).padding(5.dp).testTag("story_instory_${row}_$i"))
        // with the cover star in the top right, "Children" goes just under it so the two never meet on a small tile
        if (ph.children) Text(t("Children", "Watoto"), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = Color.White, maxLines = 1,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 5.dp, end = 5.dp, top = if (cover) 35.dp else 5.dp, bottom = 5.dp).clip(RoundedCornerShape(99.dp)).background(Color(0xCC8A5200)).padding(horizontal = 6.dp, vertical = 2.dp))
        if (cover || sent) Row(Modifier.align(Alignment.TopEnd).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (cover) CoverBadge(Modifier.testTag("sty_cover_badge"))
            if (sent) Tick(Modifier.testTag("story_sent_${row}_$i"))
        }
    }
}

/** The story's cover photo: a small round amber star (web v78, .sty-cvb). */
@Composable
internal fun CoverBadge(modifier: Modifier = Modifier) = Box(modifier.size(26.dp).shadow(2.dp, CircleShape).clip(CircleShape).background(Color(0xFFF2B33D)), contentAlignment = Alignment.Center) {
    Icon(Ic.starFill, t("Cover photo", "Picha ya jalada"), tint = Color(0xFF3A2600), modifier = Modifier.size(15.dp))
}

/** "Use as cover", or for the cover "Cover photo ✓ · Tap to undo" in amber (web v78, .sty-cov); busy while it saves.
 *  The dot keeps to the tick, so a narrow phone breaks the line before "Tap to undo", never before the dot. */
@Composable
internal fun StoryCoverBtn(on: Boolean, busy: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val saving = if (busy) t("Saving…", "Inahifadhi…") else null
    val mod = modifier.fillMaxWidth().semantics { selected = on }.testTag("sty_cover")
    if (!on) { Btn(t("Use as cover", "Tumia kama jalada"), onClick, mod, icon = "star", light = true, small = true, busy = saving); return }
    val shape = RoundedCornerShape(999.dp)
    val fg = if (C.dark) Color(0xFFF7D27E) else Color(0xFF6B4300)
    Row(mod.clip(shape).background(if (C.dark) Color(0xFF3A2C10) else Color(0xFFFFF4DC)).border(1.dp, Color(0xFFF2B33D), shape)
        .then(if (busy) Modifier.semantics { stateDescription = saving ?: "" } else Modifier)
        .clickable(enabled = !busy, role = Role.Button, onClick = onClick).heightIn(min = 44.dp).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp).testTag("btn_busy"), color = fg, strokeWidth = 2.5.dp, trackColor = fg.copy(alpha = .2f))
        else Icon(Ic.starGold, null, tint = Color.Unspecified, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(if (busy) 10.dp else 8.dp))
        Text(saving ?: t("Cover photo ✓\u00A0· Tap to undo", "Picha ya jalada ✓\u00A0· Gusa kutendua"), color = fg, style = MaterialTheme.typography.labelMedium.copy(fontSize = 14.sp), textAlign = TextAlign.Center)
    }
}

@Composable
internal fun InStoryBadge(modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(99.dp)).background(Color(0xD904263F)).padding(start = 5.dp, end = 7.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Ic.get("spark"), null, tint = MINT, modifier = Modifier.size(10.dp)); Spacer(Modifier.width(3.dp))
        FitWords(t("In story", "Hadithini"), MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), Color.White, Modifier.weight(1f, fill = false), lines = 1)
    }
}

@Composable
private fun Tick(modifier: Modifier = Modifier) = Box(modifier.size(24.dp).clip(CircleShape).background(Color.White).padding(2.dp).clip(CircleShape).background(Color(0xFF1C9C5A)), contentAlignment = Alignment.Center) {
    Icon(Ic.get("check"), t("Sent", "Imetumwa"), tint = Color.White, modifier = Modifier.size(14.dp))
}

/** A photo that is on its way: the photo from the phone, with a ring and %, waiting for signal, or Tap to retry. */
@Composable
private fun LocalTile(m: AppModel, u: StoryUploads.Up, modifier: Modifier) {
    val spin = rememberMapMotion() && Motion.loops
    val tap = if (u.state == "failed") Modifier.clickable(role = Role.Button) { m.storyUp.retry(u.key) } else Modifier
    Box(modifier.clip(TILE).background(C.tint).then(tap).semantics(mergeDescendants = true) {}.testTag("story_up_${u.key}"), contentAlignment = Alignment.Center) {
        LocalPic(u.file, Modifier.matchParentSize())
        when (u.state) {
            "done" -> Tick(Modifier.align(Alignment.TopEnd).padding(5.dp))
            "waiting" -> Column(Modifier.matchParentSize().background(Color(0x8C04263F)).padding(4.dp).testTag("story_up_wait"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Ic.get("offline"), null, tint = Color.White, modifier = Modifier.size(22.dp))
                FitWords(t("Waiting for signal", "Inasubiri mtandao"), MaterialTheme.typography.labelSmall.copy(textAlign = TextAlign.Center), Color.White, Modifier.padding(top = 4.dp).fillMaxWidth())
            }
            "failed" -> Column(Modifier.matchParentSize().background(Color(0x8C04263F)).padding(4.dp).testTag("story_up_retry"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) { Icon(Ic.get("refresh"), null, tint = Color(0xFFB3261E), modifier = Modifier.size(18.dp)) }
                FitWords(t("Tap to retry", "Gusa ujaribu tena"), MaterialTheme.typography.labelSmall.copy(textAlign = TextAlign.Center), Color.White, Modifier.padding(top = 4.dp).fillMaxWidth())
            }
            else -> {   // queued or sending: the ring fills as it sends
                Box(Modifier.matchParentSize().background(Color(0x6604263F)))
                val p by animateFloatAsState(u.progress, if (spin) tween(250) else tween(0), label = "up")
                val turn = if (spin && u.state == "queued") rememberInfiniteTransition(label = "q").animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "t").value else 0f
                Canvas(Modifier.size(50.dp).rotate(turn)) {
                    val sw = 4.dp.toPx(); val inset = sw / 2
                    drawArc(Color(0x55FFFFFF), 0f, 360f, false, topLeft = Offset(inset, inset), size = Size(size.width - sw, size.height - sw), style = Stroke(sw))
                    val sweep = if (u.state == "queued") (if (spin) 70f else 0f) else 360f * p
                    if (sweep > 0f) drawArc(Color.White, -90f, sweep, false, topLeft = Offset(inset, inset), size = Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
                }
                Text("${(p * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = Color.White, modifier = Modifier.testTag("story_up_pc"))
            }
        }
    }
}

/** A photo file on this phone, made small for a tile. */
@Composable
internal fun LocalPic(path: String, modifier: Modifier, fit: ContentScale = ContentScale.Crop, px: Int = 360) {
    val bmp by produceState<Bitmap?>(null, path) { value = withContext(Dispatchers.IO) { decodeSmall(path, px) } }
    Box(modifier) { bmp?.let { Image(it.asImageBitmap(), null, contentScale = fit, modifier = Modifier.matchParentSize()) } }
}

internal fun decodeSmall(path: String, px: Int): Bitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(path, o)
    var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= px) s *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
}.getOrNull()

@Composable
private fun AddTile(row: String, modifier: Modifier, tap: () -> Unit) {
    val edge = C.blue.copy(alpha = .55f)
    Column(modifier.clip(TILE).background(C.tint.copy(alpha = .55f)).drawBehind {
        drawRoundRect(edge, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))), cornerRadius = CornerRadius(16.dp.toPx()),
            topLeft = Offset(1.dp.toPx(), 1.dp.toPx()), size = Size(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()))
    }.clickable(role = Role.Button, onClick = tap).padding(6.dp).testTag("story_add_$row"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Ic.get("plus"), null, tint = C.blue2, modifier = Modifier.size(26.dp))
        FitWords(t("Add photos", "Ongeza picha"), MaterialTheme.typography.labelMedium.copy(textAlign = TextAlign.Center), C.blue2, Modifier.padding(top = 4.dp).fillMaxWidth())
    }
}

/** Before / In progress / After as one segmented control. */
@Composable
internal fun RowSeg(sel: String, tagPrefix: String, pick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.bg).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        StoryKit.ROWS.forEach { r ->
            val on = r == sel
            Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).then(if (on) Modifier.shadow(2.dp, RoundedCornerShape(12.dp)).background(C.card) else Modifier)
                .clickable(role = Role.Tab) { pick(r) }.semantics { selected = on }.heightIn(min = 44.dp).padding(horizontal = 4.dp, vertical = 6.dp).testTag("${tagPrefix}_$r"), contentAlignment = Alignment.Center) {
                FitWords(StoryKit.row(r), MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, textAlign = TextAlign.Center, fontWeight = if (on) FontWeight.ExtraBold else FontWeight.SemiBold), if (on) C.blue2 else C.muted, Modifier.fillMaxWidth())
            }
        }
    }
}

/** Add photos: which part of the story, then Choose from gallery (several at once) or Take photos. */
@Composable
internal fun StoryAddSheet(title: String, row: String, pickRow: (String) -> Unit, close: () -> Unit, gallery: () -> Unit, camera: () -> Unit) {
    DqmSheet("story_add_sheet", close) { hide ->
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(t("Add photos", "Ongeza picha"), style = MaterialTheme.typography.titleLarge, color = C.ink)
                Text(title, style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.padding(top = 2.dp))
            }
            CloseX("story_add_x") { hide {} }
        }
        Text(t("Which part of the story?", "Sehemu gani ya hadithi?"), style = MaterialTheme.typography.labelLarge, color = C.ink, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        RowSeg(row, "story_add_row", pickRow)
        AddOption("story_add_gallery", "grid", t("Choose from gallery", "Chagua kutoka kwenye picha"), t("Pick several at once — tap each photo you want.", "Chagua kadhaa kwa mara moja — gusa kila picha unayotaka."), true) { hide { gallery() } }
        AddOption("story_add_camera", "camera", t("Take photos", "Piga picha"), t("Keep shooting, then add them all together.", "Endelea kupiga, kisha uziongeze zote pamoja."), false) { hide { camera() } }
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.Top) {
            Icon(Ic.get("down"), null, tint = C.ok, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
            Text(t("Photos are made smaller before sending to save data. No signal? They wait and send by themselves.", "Picha zinapunguzwa ukubwa kabla ya kutumwa ili kuokoa data. Hakuna mtandao? Zinasubiri na kujituma zenyewe."),
                style = MaterialTheme.typography.bodySmall, color = C.ok, modifier = Modifier.testTag("story_add_note"))
        }
    }
}

@Composable
private fun CloseX(tag: String, onClose: () -> Unit) = Box(Modifier.size(44.dp).clip(CircleShape).background(C.bg).clickable(role = Role.Button, onClick = onClose).testTag(tag), contentAlignment = Alignment.Center) {
    Icon(Ic.get("x"), t("Close", "Funga"), tint = C.ink, modifier = Modifier.size(20.dp))
}

@Composable
private fun AddOption(tag: String, icon: String, title: String, sub: String, main: Boolean, go: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val fg = if (main) Color.White else C.ink; val fg2 = if (main) Color(0xE6FFFFFF) else C.muted
    Row(Modifier.padding(top = 12.dp).fillMaxWidth().clip(shape).background(if (main) Brush.linearGradient(listOf(Color(0xFF0A78C2), Color(0xFF076DB1))) else androidx.compose.ui.graphics.SolidColor(C.tint))
        .clickable(role = Role.Button, onClick = go).heightIn(min = 76.dp).padding(14.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(if (main) Color(0x33FFFFFF) else C.card), contentAlignment = Alignment.Center) { Icon(Ic.get(icon), null, tint = if (main) Color.White else C.blue2, modifier = Modifier.size(24.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = fg)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = fg2, modifier = Modifier.padding(top = 2.dp))
        }
        Icon(Ic.get("chev"), null, tint = if (main) Color.White else C.blue2, modifier = Modifier.size(18.dp))
    }
}

/** Taking photos one after another: "2 taken · Take another / Add 2 photos". */
@Composable
internal fun StoryShotsTray(shots: List<String>, close: () -> Unit, another: () -> Unit, done: (List<String>) -> Unit) {
    DqmSheet("story_tray", close) { hide ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (shots.size == 1) t("1 taken", "1 imepigwa") else t("${shots.size} taken", "${shots.size} zimepigwa"), style = MaterialTheme.typography.titleLarge, color = C.ink, modifier = Modifier.weight(1f).testTag("story_tray_n"))
            CloseX("story_tray_x") { hide {} }
        }
        Row(Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            shots.forEach { LocalPic(it, Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)).background(C.tint)) }
        }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn(t("Take another", "Piga nyingine"), { another() }, Modifier.weight(1f).testTag("story_tray_more"), icon = "camera", light = true, small = true)
            Btn(if (shots.size == 1) t("Add 1 photo", "Ongeza picha 1") else t("Add ${shots.size} photos", "Ongeza picha ${shots.size}"), { val f = shots.toList(); hide { done(f) } }, Modifier.weight(1f).testTag("story_tray_add"), icon = "check", small = true)
        }
    }
}

/** Check the photos: remove any, say which show children, then "Add N photos to After". */
@Composable
internal fun StoryReviewSheet(row: String, files: List<String>, kids: Set<String>, ask: String, remove: (String) -> Unit, setAsk: (String) -> Unit, toggle: (String) -> Unit, close: () -> Unit, add: () -> Unit) {
    DqmSheet("story_review", close) { hide ->
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(t("Check the photos", "Angalia picha"), style = MaterialTheme.typography.titleLarge, color = C.ink)
                Text(t("For “${StoryKit.row(row)}”", "Kwa “${StoryKit.row(row)}”"), style = MaterialTheme.typography.bodyMedium, color = C.muted)
            }
            CloseX("story_review_x") { hide {} }
        }
        val cells: List<@Composable (Modifier) -> Unit> = files.mapIndexed { i, f -> @Composable { mod: Modifier ->
            val kid = f in kids
            Box(mod.clip(TILE).background(C.tint).then(if (ask == "yes") Modifier.border(if (kid) 3.dp else 0.dp, if (kid) Color(0xFFE39A1E) else Color.Transparent, TILE).clickable(role = Role.Checkbox) { toggle(f) } else Modifier)
                .semantics { selected = kid; contentDescription = t("Photo ${i + 1}", "Picha ${i + 1}") }.testTag("story_rev_ph_$i")) {
                LocalPic(f, Modifier.matchParentSize())
                Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp).clip(CircleShape).background(Color(0xB304263F)).clickable(role = Role.Button) { remove(f) }
                    .semantics { contentDescription = t("Remove", "Ondoa") }.testTag("story_rev_x_$i"), contentAlignment = Alignment.Center) { Icon(Ic.get("x"), null, tint = Color.White, modifier = Modifier.size(14.dp)) }
                if (kid) Text(t("Children", "Watoto"), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = Color.White, maxLines = 1,
                    modifier = Modifier.align(Alignment.BottomStart).padding(5.dp).clip(RoundedCornerShape(99.dp)).background(Color(0xE68A5200)).padding(horizontal = 6.dp, vertical = 2.dp))
            }
        } }
        Grid3(cells, Modifier.padding(top = 12.dp))
        Text(t("Do any of these show children?", "Je, yoyote kati ya hizi inaonyesha watoto?"), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.padding(top = 16.dp))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).testTag("story_kids_no")) { Pill(t("No", "Hapana"), ask == "no", Modifier.fillMaxWidth()) { setAsk("no") } }
            Box(Modifier.weight(1f).testTag("story_kids_yes")) { Pill(t("Yes", "Ndiyo"), ask == "yes", Modifier.fillMaxWidth()) { setAsk("yes") } }
        }
        if (ask == "yes") Text(if (kids.isEmpty()) t("Tap the ones that do.", "Gusa zile zinazoonyesha.") else t("${kids.size} marked. Consent is needed before they go on the website.", "${kids.size} zimewekwa alama. Idhini inahitajika kabla hazijaenda kwenye tovuti."),
            style = MaterialTheme.typography.bodySmall, color = C.warn, modifier = Modifier.padding(top = 8.dp).testTag("story_kids_hint"))
        val n = files.size
        Btn(if (n == 1) t("Add 1 photo to ${StoryKit.row(row)}", "Ongeza picha 1 kwenye ${StoryKit.row(row)}") else t("Add $n photos to ${StoryKit.row(row)}", "Ongeza picha $n kwenye ${StoryKit.row(row)}"),
            { hide { add() } }, Modifier.padding(top = 16.dp).fillMaxWidth().testTag("story_review_add"), icon = "check", enabled = n > 0 && (ask == "no" || (ask == "yes" && kids.isNotEmpty())))
    }
}

/** Over the bottom bar while a project's photos send: "Adding 3 photos to After · 1 of 3 sent", then "3 photos added ✓". */
@Composable
internal fun StoryUploadBanner(m: AppModel, pid: String, modifier: Modifier = Modifier) {
    val up = m.storyUp
    val mine = up.items.filter { it.project == pid && !it.seen }
    if (mine.isEmpty()) return
    val active = mine.count { it.active }
    val sent = mine.count { it.state == "done" }
    val failed = mine.filter { it.state == "failed" }
    val waiting = mine.count { it.state == "waiting" }
    val rows = mine.map { it.row }.distinct()
    val where = if (rows.size == 1) StoryKit.row(rows[0]) else ""
    // all sent: says so, then goes by itself
    LaunchedEffect(active, failed.size, sent) { if (active == 0 && failed.isEmpty() && sent > 0) { delay(4000); up.hide(pid) } }
    val shape = RoundedCornerShape(20.dp)
    if (active > 0) {
        val total = mine.size
        val sending = mine.firstOrNull { it.state == "sending" }
        val overall = (sent + (sending?.progress ?: 0f)) / total
        val p by animateFloatAsState(overall, tween(if (rememberMapMotion()) 250 else 0), label = "all")
        Column(modifier.fillMaxWidth().shadow(12.dp, shape).clip(shape).background(Brush.linearGradient(listOf(NIGHT2, NIGHT))).padding(14.dp).semantics(mergeDescendants = true) {}.testTag("story_banner")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x26FFFFFF)), contentAlignment = Alignment.Center) { Icon(Ic.get("upload"), null, tint = MINT, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (where.isNotBlank()) (if (total == 1) t("Adding 1 photo to $where", "Inaongeza picha 1 kwenye $where") else t("Adding $total photos to $where", "Inaongeza picha $total kwenye $where"))
                        else t("Adding $total photos", "Inaongeza picha $total"), style = MaterialTheme.typography.titleSmall, color = Color.White, modifier = Modifier.testTag("story_banner_title"))
                    Text(t("$sent of $total sent", "$sent kati ya $total zimetumwa") + (sending?.let { " · ${(it.progress * 100).toInt()}%" } ?: ""), style = MaterialTheme.typography.bodySmall, color = Color(0xCCFFFFFF), modifier = Modifier.testTag("story_banner_sub"))
                }
                Text(t("Hide", "Ficha"), style = MaterialTheme.typography.labelLarge, color = MINT, modifier = Modifier.clip(RoundedCornerShape(99.dp)).clickable(role = Role.Button) { up.hide(pid) }
                    .heightIn(min = 44.dp).padding(horizontal = 10.dp, vertical = 12.dp).testTag("story_banner_hide"))
            }
            Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(99.dp)).background(Color(0x33FFFFFF))) {
                Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(99.dp)).background(SEND).testTag("story_banner_bar"))
            }
            if (waiting > 0) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.get("offline"), null, tint = Color(0xFFF2B35B), modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp))
                Text(if (waiting == 1) t("1 is waiting for signal — it sends by itself.", "1 inasubiri mtandao — itajituma yenyewe.") else t("$waiting are waiting for signal — they send by themselves.", "$waiting zinasubiri mtandao — zitajituma zenyewe."),
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFFF2B35B), modifier = Modifier.testTag("story_banner_wait"))
            }
        }
    } else {
        Row(modifier.fillMaxWidth().shadow(12.dp, shape).clip(shape).background(C.card).border(1.dp, C.line, shape).padding(14.dp).testTag("story_banner_done"), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(if (sent > 0) Color(0xFF1C9C5A) else C.bad), contentAlignment = Alignment.Center) { Icon(Ic.get(if (sent > 0) "check" else "alert"), null, tint = Color.White, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (sent > 0) Text(if (sent == 1) t("1 photo added ✓", "Picha 1 imeongezwa ✓") else t("$sent photos added ✓", "Picha $sent zimeongezwa ✓"), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.testTag("story_banner_title"))
                if (failed.isNotEmpty()) Text((if (failed.size == 1) t("1 couldn’t send", "1 haikutumwa") else t("${failed.size} couldn’t send", "${failed.size} hazikutumwa")) + ": " + upWhy(failed.first().reason) + " " + t("Tap Retry.", "Gusa Jaribu tena."),
                    style = MaterialTheme.typography.bodySmall, color = C.bad, modifier = Modifier.testTag("story_banner_fail"))
            }
            if (failed.isNotEmpty()) Row(Modifier.padding(start = 8.dp).clip(RoundedCornerShape(99.dp)).background(C.badBg).clickable(role = Role.Button) { up.retry() }.heightIn(min = 44.dp).padding(horizontal = 12.dp).testTag("story_banner_retry"), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.get("refresh"), null, tint = C.bad, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                Text(t("Retry", "Jaribu tena"), style = MaterialTheme.typography.labelLarge, color = C.bad, maxLines = 1)
            }
            else Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) { up.hide(pid) }.testTag("story_banner_close"), contentAlignment = Alignment.Center) { Icon(Ic.get("x"), t("Close", "Funga"), tint = C.muted, modifier = Modifier.size(18.dp)) }
        }
    }
}

/** Why a photo didn't send, in a few words. */
internal fun upWhy(reason: String) = when (reason) {
    "too-large" -> t("the photo is too big.", "picha ni kubwa mno.")
    "type" -> t("that kind of file can’t be used.", "aina hiyo ya faili haitumiki.")
    "rate" -> t("too many photos this hour.", "picha nyingi mno saa hii.")
    "missing" -> t("the photo is no longer on this phone.", "picha haipo tena kwenye simu hii.")
    "admin-only", "named-only" -> t("you can’t add photos here.", "huwezi kuongeza picha hapa.")
    else -> t("the server didn’t answer.", "seva haikujibu.")
}

/** On the Stories tab: "Sending 2 photos…" while any story photo is on its way. */
@Composable
internal fun StorySendingPill(m: AppModel, modifier: Modifier = Modifier) {
    val n = m.storyUp.sending()
    if (n == 0) return
    // all of them waiting for signal, or some on their way
    val wait = m.storyUp.items.filter { it.active }.all { it.state == "waiting" }
    Row(modifier.clip(RoundedCornerShape(99.dp)).background(NIGHT2).padding(horizontal = 12.dp, vertical = 6.dp).semantics(mergeDescendants = true) {}.testTag("sh_sending"), verticalAlignment = Alignment.CenterVertically) {
        Icon(Ic.get(if (wait) "offline" else "upload"), null, tint = MINT, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp))
        Text(if (wait) (if (n == 1) t("1 photo waiting for signal", "Picha 1 inasubiri mtandao") else t("$n photos waiting for signal", "Picha $n zinasubiri mtandao"))
            else if (n == 1) t("Sending 1 photo…", "Inatuma picha 1…") else t("Sending $n photos…", "Inatuma picha $n…"), style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

/** One photo: a big look, its caption, which row, "shows children", and Delete. */
@Composable
internal fun StoryPhotoSheet(m: AppModel, pid: String, row: String, ph: StoryPhoto, inStory: Boolean, close: (cap: String) -> Unit, move: (String) -> Unit, kids: (Boolean) -> Unit, delete: () -> Unit,
                             cover: Boolean = false, coverBusy: Boolean = false, coverErr: String? = null, setCover: () -> Unit = {}) {
    var cap by remember(ph.id) { mutableStateOf(ph.cap) }
    var ask by remember { mutableStateOf(false) }
    var big by remember { mutableStateOf(false) }
    DqmSheet("story_det", { close(cap) }) { hide ->
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(t("Photo", "Picha"), style = MaterialTheme.typography.titleLarge, color = C.ink)
                val added = listOfNotNull(photoDay(ph.at, time = false)?.let { t("Added $it", "Iliongezwa $it") }, ph.by.ifBlank { null }?.let { t("by $it", "na $it") }).joinToString(" ")
                if (added.isNotBlank()) Text(added, style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 2.dp).testTag("story_det_added"))
            }
            CloseX("story_det_x") { hide { } }
        }
        Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(220.dp).clip(RoundedCornerShape(18.dp)).background(C.tint).clickable(role = Role.Button) { big = true }
            .semantics { contentDescription = t("Open the photo", "Fungua picha") }.testTag("story_det_pic")) {
            AuthPic(m, StoryKit.photoPath(pid, ph.id, 960), Modifier.matchParentSize())
            if (inStory) InStoryBadge(Modifier.align(Alignment.TopStart).padding(10.dp))
        }
        Text(t("Caption", "Maelezo ya picha"), style = MaterialTheme.typography.labelLarge, color = C.ink, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        OutlinedTextField(cap, { cap = it.replace('\n', ' ').take(120) }, Modifier.fillMaxWidth().testTag("story_det_cap"), placeholder = { Text(t("e.g. Drilling reached water", "mf. Uchimbaji ulifika majini"), color = C.muted) },
            shape = RoundedCornerShape(16.dp), textStyle = MaterialTheme.typography.bodyLarge, singleLine = false, maxLines = 3,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.blue, unfocusedBorderColor = C.edge, focusedContainerColor = C.card, unfocusedContainerColor = C.card))
        Text(t("The story uses captions to describe photos.", "Hadithi inatumia maelezo kueleza picha."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 4.dp))
        Text(t("Move to", "Hamishia"), style = MaterialTheme.typography.labelLarge, color = C.ink, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        RowSeg(row, "story_det_row") { if (it != row) move(it) }
        Row(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.bg).clickable(role = Role.Switch) { kids(!ph.children) }.padding(12.dp).testTag("story_det_kids"), verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.get("person"), null, tint = Color(0xFFB86E00), modifier = Modifier.size(22.dp)); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(t("Shows children", "Inaonyesha watoto"), style = MaterialTheme.typography.titleSmall, color = C.ink)
                Text(t("Only used on the website once photo consent is received.", "Inatumika kwenye tovuti tu baada ya idhini ya picha kupokelewa."), style = MaterialTheme.typography.bodySmall, color = C.muted)
            }
            Spacer(Modifier.width(8.dp))
            Switch(ph.children, { kids(it) }, Modifier.testTag("story_det_kids_sw"), colors = SwitchDefaults.colors(checkedTrackColor = C.blue2))
        }
        // the story's cover (web v78): this photo, or back to the automatic choice
        StoryCoverBtn(cover, coverBusy, setCover, Modifier.padding(top = 12.dp))
        if (coverErr != null) Box(Modifier.testTag("sty_cover_err")) { Msg(coverErr, "bad") }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn(t("Delete", "Futa"), { ask = true }, Modifier.weight(1f).testTag("story_det_del"), icon = "trash", light = true, small = true)
            Btn(t("Done", "Imekamilika"), { hide { } }, Modifier.weight(1f).testTag("story_det_done"), icon = "check", small = true)
        }
    }
    if (ask) StoryAsk("story_det_ask", t("Delete this photo?", "Futa picha hii?"), t("It is taken out of the story’s photos.", "Itaondolewa kwenye picha za hadithi."), t("Delete", "Futa"), true, { ask = false }) { ask = false; delete() }
    if (big) SwipeViewer(1, 0, { ph.cap.ifBlank { StoryKit.row(row) } }, { big = false }) { _, mod -> AuthPic(m, StoryKit.photoPath(pid, ph.id, 1600), mod, ContentScale.Fit) }
}

private fun monthNames() = if (org.rafikioutreach.pin.Lang.sw) listOf("Januari", "Februari", "Machi", "Aprili", "Mei", "Juni", "Julai", "Agosti", "Septemba", "Oktoba", "Novemba", "Desemba")
    else listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
/** "June 2025" from "2025-06" or "2025-06-15". */
internal fun monthText(ym: String): String? {
    val g = Regex("^(\\d{4})-(\\d{2})").find(ym)?.groupValues ?: return null
    return monthNames().getOrNull(g[2].toInt() - 1)?.let { it + "\u00A0" + g[1] }
}

/** A month and year: the chosen one (or "Not set"), and when open a year stepper, the twelve months and "Not sure". */
@Composable
internal fun MonthField(value: String, open: Boolean, setOpen: (Boolean) -> Unit, tag: String, none: String = t("Not sure", "Sina uhakika"), pick: (String) -> Unit) {
    val now = java.time.YearMonth.now()
    var year by remember(value) { mutableIntStateOf(value.take(4).toIntOrNull() ?: now.year) }
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.padding(top = 6.dp).fillMaxWidth().clip(shape).border(1.dp, C.edge, shape).background(C.card)) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { setOpen(!open) }.heightIn(min = 52.dp).padding(horizontal = 14.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.get("cal"), null, tint = C.blue2, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
            Text(monthText(value) ?: t("Not set", "Haijawekwa"), style = MaterialTheme.typography.bodyLarge, color = if (value.isBlank()) C.muted else C.ink, modifier = Modifier.weight(1f).testTag(tag + "_value"))
            Icon(Ic.get(if (open) "up" else "down"), null, tint = C.muted, modifier = Modifier.size(18.dp))
        }
        if (open) Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp).testTag(tag + "_pick")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).clickable(enabled = year > 2000, role = Role.Button) { year-- }.semantics { contentDescription = t("Year before", "Mwaka uliopita") }.testTag(tag + "_prev"), contentAlignment = Alignment.Center) {
                    Icon(Ic.get("back"), null, tint = C.ink, modifier = Modifier.size(18.dp)) }
                Text("$year", style = MaterialTheme.typography.titleMedium, color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).testTag(tag + "_year"))
                Box(Modifier.size(44.dp).clip(CircleShape).clickable(enabled = year < now.year, role = Role.Button) { year++ }.semantics { contentDescription = t("Next year", "Mwaka ujao") }.testTag(tag + "_next"), contentAlignment = Alignment.Center) {
                    Icon(Ic.get("arrow"), null, tint = if (year < now.year) C.ink else C.off, modifier = Modifier.size(18.dp)) }
            }
            val names = monthNames()
            (0 until 12).chunked(4).forEach { line ->
                Row(Modifier.padding(top = 6.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    line.forEach { i ->
                        val ym = "%04d-%02d".format(java.util.Locale.US, year, i + 1)
                        val future = java.time.YearMonth.of(year, i + 1).isAfter(now)
                        val on = value.take(7) == ym
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (on) C.blue2 else C.bg).clickable(enabled = !future, role = Role.Button) { pick(ym) }
                            .semantics { selected = on; contentDescription = names[i] + " " + year }.heightIn(min = 44.dp).testTag(tag + "_m${i + 1}"), contentAlignment = Alignment.Center) {
                            Text(names[i].take(3), style = MaterialTheme.typography.labelLarge, color = if (on) Color.White else if (future) C.off else C.ink, maxLines = 1)
                        }
                    }
                }
            }
            Btn(none, { pick("") }, Modifier.padding(top = 8.dp).fillMaxWidth().testTag(tag + "_none"), light = true, small = true)
        }
    }
}

/** "2 Nov 2024" (or "28 Oct, 10:42" with [time]) from an ISO date or time. */
internal fun photoDay(iso: String, time: Boolean = false): String? {
    if (iso.isBlank()) return null
    val months = if (org.rafikioutreach.pin.Lang.sw) listOf("Jan", "Feb", "Mac", "Apr", "Mei", "Jun", "Jul", "Ago", "Sep", "Okt", "Nov", "Des") else listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val g = Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(iso)?.groupValues ?: return null
    val mo = months.getOrNull(g[2].toInt() - 1) ?: return null
    // this year's days without the year ("28 Oct"), older ones with it ("2 Nov 2024")
    return "${g[3].toInt()}\u00A0$mo" + (if (g[1].toInt() != java.time.Year.now().value) "\u00A0" + g[1] else "")
}

/** Ask first: a question, a line, Cancel and the action. */
@Composable
internal fun StoryAsk(tag: String, title: String, body: String, yes: String, red: Boolean, no: () -> Unit, ok: () -> Unit) {
    AlertDialog(onDismissRequest = no, containerColor = C.card, modifier = Modifier.testTag(tag),
        title = { Text(title, color = C.ink, style = MaterialTheme.typography.titleLarge) },
        text = { Text(body, color = C.text, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(ok, Modifier.testTag(tag + "_yes")) { Text(yes, color = if (red) C.bad else C.blue2, style = MaterialTheme.typography.labelLarge) } },
        dismissButton = { TextButton(no, Modifier.testTag(tag + "_no")) { Text(t("Cancel", "Ghairi"), color = C.ink, style = MaterialTheme.typography.labelLarge) } })
}

// --- dates per column (the owner, 28 Oct: "before should have a date, during should have a date and after should have
// a date — not the photos but the columns") --------------------------------------------------------------------------

private fun shortMonths() = if (org.rafikioutreach.pin.Lang.sw) listOf("Jan", "Feb", "Mac", "Apr", "Mei", "Jun", "Jul", "Ago", "Sep", "Okt", "Nov", "Des")
    else listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private fun ymParts(ym: String) = Regex("^(\\d{4})-(\\d{2})").find(ym)?.groupValues?.let { g -> g[1] to (g[2].toIntOrNull() ?: 0) }?.takeIf { it.second in 1..12 }
/** "Nov 2024" (never split between the month and the year). */
internal fun shortMonth(ym: String): String? = ymParts(ym)?.let { (y, mo) -> shortMonths()[mo - 1] + " " + y }

/** A column's month as its header shows it: "Nov 2024"; In progress "Jan – Mar 2025" or "Dec 2024 – Feb 2025"; null when not set. */
internal fun colDateText(d: StoryDates, row: String): String? {
    if (row != "progress") return shortMonth(d.of(row))
    val a = ymParts(d.progress) ?: return null
    val b = ymParts(d.progressEnd)?.takeIf { d.progressEnd > d.progress } ?: return shortMonth(d.progress)
    val from = if (a.first == b.first) shortMonths()[a.second - 1] else shortMonth(d.progress)!!
    return from + " – " + shortMonth(d.progressEnd)
}

/** The columns that have photos but no month (story.missing has 'dates'), in story order. */
internal fun storyDatesMissing(st: Story): List<String> = StoryKit.ROWS.filter { r -> st.photos[r].orEmpty().isNotEmpty() && st.dates.of(r).isBlank() }

/** A suggestion for an empty month: [key] ("before", "progress", "progressEnd", "after") to (YYYY-MM, where it comes from). */
internal fun storyDateHint(st: Story, row: String, done: String): Map<String, Pair<String, String>> {
    val fromPhotos = t("Suggested from the photos", "Imependekezwa kutoka kwenye picha")
    val taken = st.photos[row].orEmpty().mapNotNull { p -> p.taken.take(7).takeIf { Regex("^\\d{4}-\\d{2}$").matches(it) } }.sorted()
    val out = mutableMapOf<String, Pair<String, String>>()
    when (row) {
        "progress" -> { taken.firstOrNull()?.let { out["progress"] = it to fromPhotos }; taken.lastOrNull()?.takeIf { it != taken.first() }?.let { out["progressEnd"] = it to fromPhotos } }
        "after" -> done.take(7).takeIf { ymParts(it) != null }?.let { out["after"] = it to t("The project’s completion month (monday.com)", "Mwezi mradi ulipokamilika (monday.com)") }
            ?: taken.firstOrNull()?.let { out["after"] = it to fromPhotos }
        else -> taken.firstOrNull()?.let { out[row] = it to fromPhotos }
    }
    return out
}

/** Why the server refused the dates, in words the sheet shows. */
internal fun storyDateWhy(reason: String?, field: String?): String = when (reason) {
    "order" -> t("The months must go in order: Before, then In progress, then After.", "Miezi lazima ifuate mpangilio: Kabla, kisha Wakati wa kazi, kisha Baada.")
    "date" -> {
        val f = when (field) { "progressEnd" -> t("In progress (to)", "Wakati wa kazi (hadi)"); "progress" -> StoryKit.row("progress"); "before", "after" -> StoryKit.row(field); else -> "" }
        (if (f.isNotBlank()) "$f: " else "") + t("choose a month that has already come, from 2000 on.", "chagua mwezi ambao tayari umefika, kuanzia mwaka 2000.")
    }
    "offline" -> t("No signal. Try again when you’re back online.", "Hakuna mtandao. Jaribu tena ukiwa mtandaoni.")
    else -> StoryKit.why(org.rafikioutreach.pin.data.Reply(false, 400, org.json.JSONObject(), reason))
}

/** The month of one column (In progress: From and an optional To): a month-and-year picker, Save, and Clear. */
@Composable
internal fun StoryDatesSheet(row: String, d: StoryDates, hints: Map<String, Pair<String, String>>, busy: Boolean, bad: String?, close: () -> Unit, save: (Map<String, String>) -> Unit) {
    val keys = if (row == "progress") listOf("progress", "progressEnd") else listOf(row)
    fun now(k: String) = when (k) { "progressEnd" -> d.progressEnd; "progress" -> d.progress; "before" -> d.before; else -> d.after }
    val vals = remember(row) { mutableStateMapOf<String, String>().apply { keys.forEach { k -> put(k, now(k).ifBlank { hints[k]?.first.orEmpty() }) } } }
    val open = remember(row) { mutableStateMapOf<String, Boolean>().apply { keys.forEach { k -> put(k, now(k).isBlank() && hints[k] == null && k != "progressEnd") } } }
    val had = keys.any { now(it).isNotBlank() }
    DqmSheet("story_dates", close) { hide ->
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(t("When was it?", "Ilikuwa lini?"), style = MaterialTheme.typography.titleLarge, color = C.ink)
                Text(when (row) { "before" -> t("Before: how it was before the work began", "Kabla: ilivyokuwa kabla kazi haijaanza"); "progress" -> t("In progress: when the work was done", "Wakati wa kazi: kazi ilipofanyika")
                    else -> t("After: the finished project", "Baada: mradi uliokamilika") }, style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.padding(top = 2.dp).testTag("story_dates_sub"))
            }
            CloseX("story_dates_x") { hide {} }
        }
        keys.forEach { k ->
            Text(when (k) { "progress" -> t("From", "Kuanzia"); "progressEnd" -> t("To (optional)", "Hadi (si lazima)"); else -> t("Month", "Mwezi") },
                style = MaterialTheme.typography.labelLarge, color = C.ink, modifier = Modifier.padding(top = 16.dp))
            MonthField(vals[k].orEmpty(), open[k] == true, { open[k] = it }, "story_dates_$k", none = t("Clear", "Ondoa")) { vals[k] = it; open[k] = false }
            val h = hints[k]
            if (h != null && now(k).isBlank() && vals[k] == h.first) Text(h.second, style = MaterialTheme.typography.bodySmall, color = C.ok, modifier = Modifier.padding(top = 4.dp).testTag("story_dates_hint_$k"))
        }
        Text(t("One month for this whole part of the story, not for each photo. The story and the website’s timeline use it.", "Mwezi mmoja kwa sehemu hii yote ya hadithi, si kwa kila picha. Hadithi na mpangilio wa wakati wa tovuti zinautumia."),
            style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 14.dp))
        bad?.let { Box(Modifier.padding(top = 12.dp).testTag("story_dates_err")) { Msg(it, "bad") } }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (had) Btn(t("Clear", "Ondoa"), { save(keys.associateWith { "" }) }, Modifier.weight(1f).testTag("story_dates_clear"), icon = "x", light = true, small = true, enabled = !busy)
            Btn(if (busy) t("Saving…", "Inahifadhi…") else t("Save", "Hifadhi"), { save(keys.associateWith { vals[it].orEmpty() }) }, Modifier.weight(1f).testTag("story_dates_save"), icon = "check", small = had,
                enabled = !busy && keys.any { vals[it].orEmpty() != now(it) })
        }
    }
}

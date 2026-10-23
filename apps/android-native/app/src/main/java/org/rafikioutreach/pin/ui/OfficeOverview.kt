package org.rafikioutreach.pin.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.COUNTRIES
import org.rafikioutreach.pin.data.CostView
import org.rafikioutreach.pin.data.DUE_DAYS
import org.rafikioutreach.pin.data.Money
import org.rafikioutreach.pin.data.Snapshot
import org.rafikioutreach.pin.data.reasonText
import org.rafikioutreach.pin.t
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

// Office › Overview, Costs and Visits, made calm and tappable (the web app's ocv… code does the same):
// a compact head with a few numbers, tiles and cards you tap, sheets for the details and every action,
// and rarely used settings folded away. Same server calls as before.

private val OCV_FLAG = mapOf("ke" to "🇰🇪", "tz" to "🇹🇿", "ug" to "🇺🇬", "mw" to "🇲🇼")
private val OCV_CC = mapOf("ke" to Color(0xFF0A78C2), "tz" to Color(0xFF1C9C5A), "ug" to Color(0xFFE39A1E), "mw" to Color(0xFF7A4FD0))
private fun JSONArray?.ocvObjs() = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
private fun ocvFmt(n: Long) = "%,d".format(Locale.US, n)
private fun ocvLoc(): Locale = if (org.rafikioutreach.pin.Lang.sw) Locale("sw") else Locale.US
private fun JSONObject.ocvS(k: String) = optString(k).takeIf { it.isNotBlank() && it != "null" } ?: ""

@Composable
private fun ocvTone(tone: String): Pair<Color, Color> = when (tone) {
    "bad" -> C.bad to C.badBg; "warn" -> C.warn to C.warnBg; "ok" -> C.ok to C.okBg; "has" -> C.blue2 to C.tint; else -> C.muted to C.bg
}
private val ocvSun @Composable get() = C.ink == Color.Black

// ---------------------------------------------------------------------------------------------- shared pieces

/** The head of a tab: icon, title, one short line; then whatever the tab adds (numbers, a button). */
@Composable
private fun OcvHead(tag: String, icon: String, hue: String, title: String, line: String, content: @Composable ColumnScope.() -> Unit = {}) {
    val hu = C.hue(hue)
    Card(Modifier.testTag(tag), pad = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, hu.fg, hu.bg, 44.dp); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = C.ink)
                Text(line, style = MaterialTheme.typography.bodySmall, color = C.muted)
            }
        }
        content()
    }
}

/** A small number in the head. Tappable ones set the filter. */
@Composable
private fun OcvNum(modifier: Modifier, value: String, label: String, color: Color = C.ink, on: Boolean = false, prefix: String = "", tag: String = "", sub: String = "", onClick: (() -> Unit)? = null) {
    val h = rememberHaptics()
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.fillMaxHeight().clip(shape).background(if (on) C.tint else C.bg).border(if (on) 1.5.dp else if (ocvSun) 2.dp else 1.dp, if (on) C.blue else C.line, shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button) { h.tap(); onClick() } else Modifier)
            .heightIn(min = 56.dp).padding(horizontal = 10.dp, vertical = 8.dp).then(if (tag.isNotEmpty()) Modifier.testTag(tag) else Modifier),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            if (prefix.isNotEmpty()) Text(prefix, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold), color = C.muted, modifier = Modifier.padding(end = 4.dp, bottom = 3.dp))
            Text(value, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold, fontSize = if (prefix.isNotEmpty()) 19.sp else 22.sp), color = color, maxLines = 1, softWrap = false)
        }
        FitWords(label, MaterialTheme.typography.bodySmall, C.muted, Modifier.fillMaxWidth(), Alignment.CenterStart, 3)
        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = C.muted)
    }
}

/** A number that counts up (or just shows, with reduced motion). */
@Composable
private fun ocvCount(n: Long): String = ocvFmt(kotlin.math.round(rememberFill(n.toFloat(), 1000)).toLong())

/** A status: a dot and a short label, in its tone. */
@Composable
private fun OcvPill(label: String, tone: String, modifier: Modifier = Modifier) {
    val (fg, bg) = ocvTone(tone)
    Row(modifier.clip(RoundedCornerShape(12.dp)).background(bg).then(if (ocvSun) Modifier.border(1.5.dp, fg, RoundedCornerShape(12.dp)) else Modifier).padding(start = 8.dp, end = 10.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(fg)); Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.ExtraBold), color = fg)
    }
}

/** A list card: something on the left, a title with the time (or amount) on the right, a status, up to two lines. */
@Composable
private fun OcvCard(tag: String, i: Int, lead: @Composable () -> Unit, title: String, right: String, rightStrong: Boolean, pill: (@Composable () -> Unit)?, side: String, lines: List<String>, italicLast: Boolean = false, onTap: () -> Unit) {
    val h = rememberHaptics()
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.padding(top = 8.dp).rise(minOf(i, 6), step = 35).press(src).fillMaxWidth().clip(shape).background(if (C.glassy) C.glass2 else C.card)
            .border(if (ocvSun) 2.dp else 1.dp, if (C.glassy) C.glassEdge else C.line, shape)
            .clickable(interactionSource = src, indication = androidx.compose.material3.ripple(), role = Role.Button) { h.tap(); onTap() }.padding(14.dp).testTag(tag),
        verticalAlignment = Alignment.Top,
    ) {
        lead(); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(right, style = if (rightStrong) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = if (rightStrong) C.ink else C.muted, maxLines = 1, softWrap = false)
                Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.padding(top = 1.dp).size(16.dp))
            }
            if (pill != null || side.isNotEmpty()) Row(Modifier.padding(top = 5.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { pill?.invoke() }
                if (side.isNotEmpty()) Text(side, style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 8.dp))
            }
            lines.filter { it.isNotBlank() }.forEachIndexed { k, l ->
                Text(l, style = MaterialTheme.typography.bodySmall.copy(fontStyle = if (italicLast && k == lines.filter { it.isNotBlank() }.size - 1 && k > 0) FontStyle.Italic else FontStyle.Normal), color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = if (k == 0) 5.dp else 1.dp))
            }
        }
    }
}

/** A filter chip with its count; the chosen one is scrolled into view. */
@Composable
private fun OcvChips(items: List<Triple<String, String, Boolean>>, tags: List<String>, pick: (Int) -> Unit) {
    val (scroll, spot) = rememberKeepInView(items.indexOfFirst { it.third })
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(top = 12.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { i, (label, n, on) -> Box(spot(i)) { Chip("$label · $n", null, on, { pick(i) }, tag = tags[i], hue = "blue") } }
    }
}

/** For a row that scrolls sideways: the scroll state, and a modifier for each item, so the item [sel] is
 *  scrolled into view whenever it changes (only sideways; the page itself doesn't move). */
@Composable
internal fun rememberKeepInView(sel: Any?): Pair<androidx.compose.foundation.ScrollState, (Any) -> Modifier> {
    val st = rememberScrollState()
    val spots = remember { HashMap<Any, Pair<Int, Int>>() }
    var laid by remember { mutableIntStateOf(0) }
    LaunchedEffect(sel, laid > 0) {
        val (x, w) = spots[sel ?: return@LaunchedEffect] ?: return@LaunchedEffect
        val vp = st.viewportSize; if (vp <= 0) return@LaunchedEffect
        val gap = (vp / 12)
        when {
            x - gap < st.value -> st.animateScrollTo((x - gap).coerceAtLeast(0))
            x + w + gap > st.value + vp -> st.animateScrollTo((x + w + gap - vp).coerceAtMost(st.maxValue))
        }
    }
    return st to { k -> Modifier.onGloballyPositioned { c -> spots[k] = c.positionInParent().x.toInt() to c.size.width; if (laid == 0) laid = 1 } }
}

/** The grey boxes shown while numbers load (the same in the Office and on Me), instead of dashes. */
@Composable
internal fun Skel(modifier: Modifier, dark: Boolean = false) {
    val a = if (Motion.on && Motion.loops) {
        val tr = androidx.compose.animation.core.rememberInfiniteTransition(label = "skel")
        tr.animateFloat(0.45f, 0.9f, androidx.compose.animation.core.infiniteRepeatable(tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "a").value
    } else 0.7f
    Box(modifier.clip(RoundedCornerShape(8.dp)).background((if (dark) Color.White.copy(alpha = 0.22f) else C.line).copy(alpha = if (dark) 0.22f * a else a)).testTag("skel"))
}

/** Initials in a coloured circle, as on the Team list. */
@Composable
private fun OcvAvatar(name: String, size: Dp = 40.dp) {
    var hh = 7L; name.lowercase().forEach { hh = (hh * 31 + it.code) % 4294967296L }
    val hu = C.hue(listOf("blue", "green", "purple", "teal", "coral", "slate")[(hh % 6).toInt()])
    val ini = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }.ifBlank { "·" }
    Box(Modifier.size(size).clip(CircleShape).background(hu.bg).then(if (ocvSun) Modifier.border(2.dp, Color.Black, CircleShape) else Modifier), contentAlignment = Alignment.Center) {
        Text(ini, color = hu.fg, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.34f).sp)
    }
}

/** A bottom sheet with a close X; hide() slides it away first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OcvSheet(tag: String, close: () -> Unit, content: @Composable ColumnScope.(hide: (() -> Unit) -> Unit) -> Unit) {
    val scope = rememberScope()
    val state = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val hide: (() -> Unit) -> Unit = { then -> scope.launch { state.hide() }.invokeOnCompletion { close(); then() } }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = close, sheetState = state, containerColor = C.card, modifier = Modifier.testTag(tag)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) { content(hide) }
    }
}

@Composable
private fun OcvSheetHead(lead: @Composable () -> Unit, label: String, labelColor: Color, title: String, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        lead(); Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold), color = labelColor)
            Text(title, style = MaterialTheme.typography.titleLarge, color = C.ink)
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(44.dp).clip(CircleShape).background(C.bg).clickable(role = Role.Button, onClick = onClose).testTag("ocv_close"), contentAlignment = Alignment.Center) {
            Icon(Ic.get("x"), t("Close", "Funga"), tint = C.ink, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun OcvFact(label: String, value: String, valueColor: Color = C.ink, sub: String = "") {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.widthIn(max = 210.dp), horizontalAlignment = Alignment.End) {
            Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = valueColor, textAlign = TextAlign.End)
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = C.muted, textAlign = TextAlign.End)
        }
    }
    HorizontalDivider(color = C.line)
}

/** A settings row that opens with a tap; the summary under the title says what's inside. */
@Composable
private fun OcvFold(tag: String, icon: String, hue: String, title: String, summary: String, content: @Composable () -> Unit) {
    var openNow by remember { mutableStateOf(false) }
    val h = rememberHaptics()
    BackStep(openNow) { openNow = false }
    val turn by animateFloatAsState(if (openNow) 90f else 0f, tween(if (Motion.on) 220 else 0), label = "chev")
    val hu = C.hue(hue)
    Column(Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { h.tap(); openNow = !openNow }.heightIn(min = 60.dp).padding(horizontal = 14.dp, vertical = 8.dp).testTag(tag + "_toggle"),
            verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, hu.fg, hu.bg, 36.dp); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = C.ink)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 1.dp))
            }
            Spacer(Modifier.width(6.dp))
            Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(18.dp).rotate(turn))
        }
        AnimatedVisibility(openNow, enter = if (Motion.on) expandVertically(tween(240)) + fadeIn(tween(240)) else fadeIn(tween(0)), exit = if (Motion.on) shrinkVertically(tween(200)) + fadeOut(tween(200)) else fadeOut(tween(0))) {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp).testTag(tag + "_body")) { content() }
        }
    }
}

@Composable
private fun OcvBar(f: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val g = rememberFill(f.coerceIn(0f, 1f), 1100)
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(99.dp)).background(C.line)) {
        if (g > 0.001f) Box(Modifier.fillMaxWidth(g).fillMaxHeight().clip(RoundedCornerShape(99.dp)).background(color))
    }
}

@Composable
private fun OcvMore(n: Int, tag: String, onClick: () -> Unit) =
    Btn(t("Show $n more", "Onyesha $n zaidi"), onClick, Modifier.padding(top = 10.dp).fillMaxWidth().testTag(tag), light = true, small = true)

// ---------------------------------------------------------------------------------------------- Overview

/** Office › Overview: how much is on the map, number tiles that open their list, tab or sheet, countries, the field. */
@Composable
fun OcvOverview(m: AppModel, s: Snapshot, open: (String) -> Unit, go: (String) -> Unit) {
    val act by m.activity.collectAsState()
    LaunchedEffect(Unit) { m.loadActivity() }
    var people by remember { mutableStateOf(false) }
    var country by remember { mutableStateOf<String?>(null) }
    var feedMore by remember { mutableStateOf(false) }
    BackStep(feedMore) { feedMore = false }
    fun toList(f: String, ck: String? = null) { m.prefs.filter = f; if (ck != null) m.prefs.country = ck; m.goTab = "list" }
    val pinned = s.projects.count { it.gps != null }
    val missing = s.projects.size - pinned
    val pct = if (s.projects.isEmpty()) 0 else pinned * 100 / s.projects.size
    val due = s.projects.count { it.gps != null && ageDays(s, it) >= DUE_DAYS }
    val monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    val vm = act?.count { it.optString("a") == "visit" && runCatching { Instant.parse(it.optString("at")).toEpochMilli() >= monthStart }.getOrDefault(false) }
    val urgent = s.repairs.values.count { it.urgency == "urgent" }
    val cost = s.repairs.values.sumOf { it.cost ?: 0L }
    val tests = testCount(s)

    OcvHead("ocv_overview_head", "drop", "blue", t("Overview", "Muhtasari"), t("All projects today. Tap a number to see the list.", "Miradi yote leo. Gusa namba kuona orodha.")) {
        // on the map: a ring, and a tap shows the projects with no location
        val h = rememberHaptics()
        Row(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.bg).border(if (ocvSun) 2.dp else 1.dp, C.line, RoundedCornerShape(16.dp))
            .then(if (missing > 0) Modifier.clickable(role = Role.Button) { h.tap(); toList("missing") } else Modifier).heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 8.dp).testTag("show_missing"),
            verticalAlignment = Alignment.CenterVertically) {
            OcvRing(pct); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t("$pinned of ${s.projects.size} projects on the map", "Miradi $pinned kati ya ${s.projects.size} iko kwenye ramani"), style = MaterialTheme.typography.titleSmall, color = C.ink)
                Text(if (missing == 0) t("Every project has a location", "Kila mradi una mahali") else if (missing == 1) t("1 still needs a location", "1 bado unahitaji mahali") else t("$missing still need a location", "$missing bado inahitaji mahali"),
                    style = MaterialTheme.typography.bodySmall, color = C.muted)
            }
            if (missing > 0) Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(18.dp))
        }
    }
    // the tiles, two by two
    val pe = s.people?.takeIf { it.total > 0 }
    val tiles = listOfNotNull(
        OcvT("ocv_t_repairs", "wrench", "amber", s.repairs.size.toLong(), if (s.repairs.size == 1) t("open repair", "tengenezo lililo wazi") else t("open repairs", "matengenezo yaliyo wazi"),
            listOfNotNull(if (urgent > 0) t("$urgent urgent", "$urgent ya haraka") else null, if (cost > 0) "KES\u00A0" + ocvFmt(cost) else null).joinToString(" · "), hot = s.repairs.isNotEmpty(), red = urgent > 0) { go("repairs") },
        OcvT("ocv_t_review", "inbox", "purple", s.reviewOpen.toLong(), if (s.reviewOpen == 1) t("waiting for your review", "inasubiri uidhinishe") else t("waiting for your review", "zinasubiri uidhinishe"), hot = s.reviewOpen > 0) { go("review") },
        OcvT("ocv_t_due", "cal", "blue", due.toLong(), if (due == 1) t("due a visit", "unasubiri ziara") else t("due a visit", "zinasubiri ziara")) { toList("due") },
        // water points with no water test in 6 months: Projects › Due a visit, where they carry the tag
        OcvT("o_tests", "drop", "teal", tests.toLong(), if (tests == 1) t("water test due", "kipimo cha maji kinahitajika") else t("water tests due", "vipimo vya maji vinahitajika"), hot = tests > 0) { toList("due") },
        OcvT("ocv_t_visits", "clip", "green", vm?.toLong(), if (vm == 1) t("visit this month", "ziara ya mwezi huu") else t("visits this month", "ziara za mwezi huu")) { go("visits") },
        pe?.let { OcvT("ocv_t_people", "people", "teal", it.total, t("people served", "watu wanaohudumiwa"), if (it.growth > 0) "+" + ocvFmt(it.growth) + " " + t("in 12 months", "kwa miezi 12") else "", up = true) { people = true } },
    )
    tiles.chunked(2).forEachIndexed { ri, row ->
        Row(Modifier.padding(top = 10.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEachIndexed { ci, x -> OcvTile(Modifier.weight(1f).fillMaxHeight(), ri * 2 + ci + 1, x) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    // condition at last visit
    val st = s.visits.values.groupingBy { it.state }.eachCount()
    val seen = st.values.sum(); val unseen = (s.projects.size - seen).coerceAtLeast(0); val tot = maxOf(1, s.projects.size).toFloat()
    Card(Modifier.padding(top = 12.dp).testTag("ocv_health"), pad = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("Condition at last visit", "Hali katika ziara ya mwisho"), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.weight(1f))
            Text(t("$seen of ${s.projects.size} visited", "$seen kati ya ${s.projects.size} zimetembelewa"), style = MaterialTheme.typography.bodySmall, color = C.muted)
        }
        val grow = rememberFill(1f, 1100)
        Row(Modifier.padding(vertical = 10.dp).fillMaxWidth().height(12.dp).clip(RoundedCornerShape(99.dp)).background(C.line), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf((st["working"] ?: 0) to Green, (st["repair"] ?: 0) to Amber, (st["notworking"] ?: 0) to Red, unseen to C.off).forEach { (n, c) -> if (n > 0 && grow > 0.01f) Box(Modifier.weight(n / tot * grow).height(12.dp).background(c)) }
            if (grow < 0.999f) Spacer(Modifier.weight(1.0001f - grow))
        }
        listOf(Triple(st["working"] ?: 0, Green, t("working", "zinafanya kazi")), Triple(st["repair"] ?: 0, Amber, t("needed repair", "zilihitaji matengenezo")), Triple(st["notworking"] ?: 0, Red, t("not working", "hazifanyi kazi")), Triple(unseen, C.off, t("not visited yet", "bado hazijatembelewa")))
            .chunked(2).forEach { r -> Row { r.forEach { (n, c, l) -> Row(Modifier.weight(1f).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(c)); Text(" $n ", style = MaterialTheme.typography.titleSmall, color = C.ink); Text(l, style = MaterialTheme.typography.bodySmall, color = C.muted) } } } }
    }
    // countries: tap for a sheet
    Card(Modifier.padding(top = 12.dp).testTag("ocv_countries"), pad = 16.dp) {
        Text(t("By country", "Kwa nchi"), style = MaterialTheme.typography.titleSmall, color = C.ink)
        var first = true
        COUNTRIES.forEach { (k, n) ->
            val l = s.projects.filter { it.ck == k }; if (l.isEmpty()) return@forEach
            val loc = l.count { it.gps != null }; val rp = l.count { s.repairs.containsKey(it.id) }; val du = l.count { it.gps != null && ageDays(s, it) >= DUE_DAYS }
            if (!first) HorizontalDivider(color = C.line); first = false
            val h = rememberHaptics()
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { h.tap(); country = k }.heightIn(min = 72.dp).padding(vertical = 10.dp).testTag("ocv_c_$k"), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(C.tint), contentAlignment = Alignment.Center) { Text(OCV_FLAG[k] ?: "", fontSize = 21.sp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(n, style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.weight(1f))
                        Text(t("$loc of ${l.size} mapped", "$loc kati ya ${l.size} zina mahali"), style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 1)
                    }
                    OcvBar(loc.toFloat() / l.size, C.blue, Modifier.padding(vertical = 6.dp))
                    when {
                        rp > 0 -> OcvPill(if (rp == 1) t("1 open repair", "matengenezo 1") else t("$rp open repairs", "matengenezo $rp"), "warn")
                        du > 0 -> OcvPill(t("$du due a visit", "$du zinasubiri ziara"), "has")
                        else -> OcvPill(t("Nothing needs attention", "Hakuna kinachohitaji kushughulikiwa"), "ok")
                    }
                }
                Spacer(Modifier.width(8.dp)); Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(18.dp))
            }
        }
    }
    // the latest from the field: five, then more
    Card(Modifier.padding(top = 12.dp).testTag("ocv_feed"), pad = 16.dp) {
        Text(t("Latest from the field", "Habari za karibuni kutoka uwanjani"), style = MaterialTheme.typography.titleSmall, color = C.ink)
        val a = act
        if (a == null) Text(t("Loading…", "Inapakia…"), color = C.muted, modifier = Modifier.padding(top = 8.dp))
        else if (a.isEmpty()) Text(t("Nothing yet.", "Bado hakuna kitu."), color = C.muted, modifier = Modifier.padding(top = 8.dp))
        else {
            val all = a.takeLast(12).reversed(); val list = if (feedMore) all else all.take(5)
            list.forEachIndexed { i, e ->
                if (i > 0) HorizontalDivider(color = C.line)
                val id = e.optString("id"); val known = id.isNotBlank() && s.projects.any { it.id == id }
                val sms = isCommunity(e)
                val hu = C.hue(if (sms) "slate" else ocvFeedHue(e.optString("a")))
                Row(Modifier.fillMaxWidth().then(if (known) Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { open(id) } else Modifier).heightIn(min = 56.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(hu.bg), contentAlignment = Alignment.Center) { Icon(Ic.get(if (sms) "phone" else ocvFeedIcon(e.optString("a"))), null, tint = hu.fg, modifier = Modifier.size(16.dp)) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(androidx.compose.ui.text.buildAnnotatedString {
                            withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = C.ink)) { append(if (sms) communityName() else e.optString("by")) }
                            append(if (sms) ": " + e.optString("t") + (when (e.optString("a")) { "fixed" -> " · " + t("working again", "inafanya kazi tena"); "visit" -> e.optString("s").takeIf { it.isNotBlank() }?.let { " · " + org.rafikioutreach.pin.data.stateName(it) } ?: ""; else -> "" }) else " " + verb(e.optString("a")) + " " + e.optString("t"))
                        }, style = MaterialTheme.typography.bodyMedium, color = C.text)
                        Text(smsAgo(e.optString("at")), style = MaterialTheme.typography.bodySmall, color = C.muted)
                    }
                    if (known) { Spacer(Modifier.width(8.dp)); Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(18.dp)) }
                }
            }
            if (!feedMore && all.size > 5) OcvMore(all.size - 5, "ocv_feed_more") { feedMore = true }
        }
    }
    // the map: open repairs in orange and red, other projects with a location in green
    Card(Modifier.padding(top = 12.dp).rise(), pad = 0.dp) {
        ProjectsMap(s.projects.filter { it.gps != null }.map { Dot(it.id, it.gps!!, dotColor(s, it), s.repairs.containsKey(it.id), it.title) }, null, open, Modifier.fillMaxWidth().height(260.dp).testTag("office_map"))
    }
    Text(t("Orange and red: open repairs. Green: other projects with a location.", "Machungwa na nyekundu: matengenezo yaliyo wazi. Kijani: miradi mingine yenye mahali."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 6.dp))

    if (people && pe != null) OcvSheet("ocv_people_sheet", { people = false }) { hide ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(C.bg).clickable(role = Role.Button) { hide {} }.testTag("ocv_close"), contentAlignment = Alignment.Center) { Icon(Ic.get("x"), t("Close", "Funga"), tint = C.ink, modifier = Modifier.size(20.dp)) }
        }
        PeopleServedCard(pe, Modifier.padding(top = 4.dp))
    }
    country?.let { k -> OcvCountrySheet(s, k, { country = null }) { f -> toList(f, k) } }
}

private fun ocvFeedHue(a: String) = when (a) { "pin", "approve", "assign" -> "pin"; "visit", "fixed" -> "near"; "new" -> "new"; "job", "request" -> "repairs"; "reject" -> "me"; else -> "slate" }
private fun ocvFeedIcon(a: String) = when (a) { "pin" -> "pin"; "visit" -> "clip"; "fixed" -> "check"; "new" -> "plus"; "undo" -> "undo"; "job" -> "wrench"; "assign" -> "cal"; "team" -> "people"; "ask" -> "hourglass"; "settings" -> "shield"; "approve" -> "check"; else -> "drop" }

/** The share of projects on the map, as a ring that fills. */
@Composable
private fun OcvRing(pct: Int) {
    val f = rememberFill(pct / 100f, 1300)
    val track = C.line; val fill = C.blue
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(48.dp)) {
            val w = 5.dp.toPx(); val r = Size(size.width - w, size.height - w); val o = Offset(w / 2, w / 2)
            drawArc(track, 0f, 360f, false, o, r, style = Stroke(w))
            drawArc(fill, -90f, 360f * f, false, o, r, style = Stroke(w, cap = StrokeCap.Round))
        }
        Text("$pct%", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold, fontSize = 12.sp), color = C.ink)
    }
}

private class OcvT(val tag: String, val icon: String, val hue: String, val n: Long?, val label: String, val sub: String = "", val hot: Boolean = false, val red: Boolean = false, val up: Boolean = false, val onClick: () -> Unit)

@Composable
private fun OcvTile(modifier: Modifier, i: Int, x: OcvT) {
    val tag = x.tag; val icon = x.icon; val hue = x.hue; val n = x.n; val label = x.label; val sub = x.sub; val hot = x.hot; val red = x.red; val up = x.up; val onClick = x.onClick
    val h = rememberHaptics()
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hu = C.hue(hue)
    val shape = RoundedCornerShape(20.dp)
    Box(modifier.rise(i)) {
        Column(
            Modifier.press(src).fillMaxWidth().fillMaxHeight().clip(shape).background(if (C.glassy) C.glass2 else C.card)
                .border(if (hot || ocvSun) 2.dp else 1.dp, if (red) C.bad else if (hot) C.warn else if (C.glassy) C.glassEdge else C.line, shape)
                .clickable(interactionSource = src, indication = androidx.compose.material3.ripple(), role = Role.Button) { h.tap(); onClick() }.heightIn(min = 128.dp).padding(14.dp).testTag(tag),
        ) {
            IconTile(icon, if (red) C.bad else if (hot) C.warn else hu.fg, if (red) C.badBg else if (hot) C.warnBg else hu.bg, 38.dp)
            val shown = n?.let { kotlin.math.round(rememberFill(it.toFloat(), 900)).toLong() }
            if (shown == null) Skel(Modifier.padding(top = 12.dp, bottom = 4.dp).size(52.dp, 26.dp))
            else Text(ocvFmt(shown), style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold), color = C.ink, modifier = Modifier.padding(top = 8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = C.muted)
                    if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (up) FontWeight.Bold else FontWeight.Normal), color = if (up) C.ok else C.muted, modifier = Modifier.padding(top = 1.dp))
                }
                Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(16.dp))
            }
        }
        if (red) OcvPulse(Modifier.align(Alignment.TopEnd).padding(12.dp))
    }
}

@Composable
private fun OcvPulse(modifier: Modifier) {
    val k = if (Motion.on && Motion.loops) {
        val tr = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
        tr.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1400)), label = "p").value
    } else 0f
    Box(modifier.size(22.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size((10 + 12 * k).dp).clip(CircleShape).background(Red.copy(alpha = 0.35f * (1f - k))))
        Box(Modifier.size(10.dp).clip(CircleShape).background(Red))
    }
}

/** A country: what's going on there, and buttons that open the right list. */
@Composable
private fun OcvCountrySheet(s: Snapshot, k: String, close: () -> Unit, toList: (String) -> Unit) {
    val l = s.projects.filter { it.ck == k }
    val ks = { st: String -> l.count { s.visits[it.id]?.state == st } }
    val loc = l.count { it.gps != null }; val rp = l.count { s.repairs.containsKey(it.id) }; val du = l.count { it.gps != null && ageDays(s, it) >= DUE_DAYS }
    OcvSheet("ocv_country_sheet", close) { hide ->
        OcvSheetHead({ Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(C.tint), contentAlignment = Alignment.Center) { Text(OCV_FLAG[k] ?: "", fontSize = 26.sp) } },
            if (l.size == 1) t("1 project", "Mradi 1") else t("${l.size} projects", "Miradi ${l.size}"), C.blue2, COUNTRIES[k] ?: k) { hide {} }
        OcvBar(loc.toFloat() / maxOf(1, l.size), C.blue, Modifier.padding(top = 16.dp, bottom = 4.dp), 8.dp)
        OcvFact(t("On the map", "Kwenye ramani"), t("$loc of ${l.size}", "$loc kati ya ${l.size}"))
        OcvFact(t("Working at the last visit", "Zinafanya kazi, ziara ya mwisho"), "${ks("working")}", if (ks("working") > 0) C.ok else C.ink)
        OcvFact(t("Needed repair", "Zilihitaji matengenezo"), "${ks("repair")}", if (ks("repair") > 0) C.warn else C.ink)
        OcvFact(t("Not working", "Hazifanyi kazi"), "${ks("notworking")}", if (ks("notworking") > 0) C.bad else C.ink)
        OcvFact(t("Open repairs", "Matengenezo yaliyo wazi"), "$rp", if (rp > 0) C.warn else C.ink)
        OcvFact(t("Due a visit", "Zinasubiri ziara"), "$du", if (du > 0) C.blue2 else C.ink)
        Btn(t("Show these projects", "Onyesha miradi hii"), { hide { toList("all") } }, Modifier.padding(top = 16.dp).fillMaxWidth().testTag("ocv_country_all"), icon = "arrow")
        if (l.size - loc > 0) Btn(t("Need a location", "Zinahitaji mahali") + " (${l.size - loc})", { hide { toList("missing") } }, Modifier.padding(top = 8.dp).fillMaxWidth(), icon = "pin", light = true)
        if (du > 0) Btn(t("Due a visit", "Zinasubiri ziara") + " ($du)", { hide { toList("due") } }, Modifier.padding(top = 8.dp).fillMaxWidth(), icon = "cal", light = true)
        if (rp > 0) Btn(t("Open repairs", "Matengenezo yaliyo wazi") + " ($rp)", { hide { toList("repairs") } }, Modifier.padding(top = 8.dp).fillMaxWidth(), icon = "wrench", light = true)
    }
}

// ---------------------------------------------------------------------------------------------- Costs

/** Office › Costs: the big numbers and the 12-month chart first, spending as cards that open the project's
 *  cost history, by country, and the second-approval limit and the download folded away. */
@Composable
fun OcvCosts(m: AppModel, s: Snapshot, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberScope()
    var v by remember { mutableStateOf<CostView?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var cf by remember { mutableStateOf("latest") }
    var shown by remember { mutableIntStateOf(30) }
    var month by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tick) { val x = m.api.adminGet("costs"); if (x.ok) { v = CostView.from(x.json); err = null } else err = reasonText(x.reason) }
    val cv = v
    if (cv == null) { Text(err ?: t("Loading…", "Inapakia…"), color = if (err != null) C.bad else C.muted); return }
    val title = { id: String, fallback: String -> s.projects.firstOrNull { it.id == id }?.title ?: fallback.ifBlank { id } }

    OcvHead("ocv_costs_head", "coins", "amber", t("Repair costs", "Gharama za matengenezo"), t("What repairs actually cost, as the team enters it.", "Gharama halisi za matengenezo, kama timu inavyoandika.")) {
        Row(Modifier.padding(top = 12.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OcvNum(Modifier.weight(1f), ocvCount(cv.month), t("this month", "mwezi huu"), prefix = "KES", tag = "ocv_c_month")
            OcvNum(Modifier.weight(1f), ocvCount(cv.year), t("this year (${cv.yearKey})", "mwaka huu (${cv.yearKey})"), prefix = "KES")
        }
        Row(Modifier.padding(top = 8.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OcvNum(Modifier.weight(1f), ocvCount(cv.sum12), t("last 12 months", "miezi 12 iliyopita"), prefix = "KES")
            OcvNum(Modifier.weight(1f), ocvCount(cv.avg), t("average per repair", "wastani kwa kila tengenezo"), prefix = "KES", tag = "ocv_c_avg", sub = if (cv.repairs == 1) t("1 repair", "matengenezo 1") else t("${cv.repairs} repairs", "matengenezo ${cv.repairs}"))
        }
    }
    // spent each month: tap a bar for that month
    val ms = (11 downTo 0).map { YearMonth.now().minusMonths(it.toLong()) }
    val sums = cv.rows.groupBy { it.at.take(7) }
    val sel = ms.firstOrNull { it.toString() == month } ?: ms.last()
    val maxV = ms.maxOf { ym -> sums[ym.toString()]?.sumOf { it.actual } ?: 0L }.coerceAtLeast(1L)
    Card(Modifier.padding(top = 12.dp).testTag("ocv_chart"), pad = 16.dp) {
        Text(t("Spent each month", "Matumizi kila mwezi"), style = MaterialTheme.typography.titleSmall, color = C.ink)
        val sr = sums[sel.toString()] ?: emptyList()
        Text(androidx.compose.ui.text.buildAnnotatedString {
            withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = C.ink)) { append(sel.format(DateTimeFormatter.ofPattern("LLLL yyyy", ocvLoc())).replaceFirstChar { it.uppercase() }) }
            append(" · " + Money.kes(sr.sumOf { it.actual }) + if (sr.isNotEmpty()) " · " + (if (sr.size == 1) t("1 entry", "kiingilio 1") else t("${sr.size} entries", "viingilio ${sr.size}")) else "")
        }, style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 2.dp).testTag("ocv_chart_sel"))
        val grow = rememberFill(1f, 1000)
        val h = rememberHaptics()
        Row(Modifier.padding(top = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
            ms.forEach { ym ->
                val amt = sums[ym.toString()]?.sumOf { it.actual } ?: 0L; val on = ym == sel
                Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { h.tap(); month = ym.toString() }
                    .semantics { contentDescription = ym.format(DateTimeFormatter.ofPattern("LLLL yyyy", ocvLoc())) + ": " + Money.kes(amt) }.testTag("ocv_bar_$ym"), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(8.dp)).background(C.bg).then(if (ocvSun) Modifier.border(1.5.dp, Color.Black, RoundedCornerShape(8.dp)) else Modifier), contentAlignment = Alignment.BottomCenter) {
                        Box(Modifier.fillMaxWidth().height((110f * amt / maxV * grow).dp.coerceAtLeast(3.dp)).clip(RoundedCornerShape(8.dp)).background(C.hue("amber").fg.copy(alpha = if (on) 1f else 0.5f)))
                    }
                    Text(ym.format(DateTimeFormatter.ofPattern("LLL", ocvLoc())).take(3).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold), color = if (on) C.ink else C.muted, maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
    }
    // spending: the latest entries, or the projects that cost the most
    Text(t("Spending", "Matumizi"), style = MaterialTheme.typography.titleMedium, color = C.ink, modifier = Modifier.padding(top = 18.dp))
    OcvChips(listOf(Triple(t("Latest", "Mpya zaidi"), "${cv.rows.size}", cf == "latest"), Triple(t("Most spent", "Gharama kubwa zaidi"), "${cv.top.size}", cf == "top")), listOf("ocv_cf_latest", "ocv_cf_top")) { cf = if (it == 0) "latest" else "top"; shown = 30 }
    if (cf == "top") Column(Modifier.testTag("costs_top")) {
        if (cv.top.isEmpty()) EmptyDrop(t("No repair costs recorded yet. Enter the actual cost when parts are ordered or a repair is fixed.", "Bado hakuna gharama za matengenezo. Andika gharama halisi vipuri vikiagizwa au matengenezo yakikamilika."))
        val tmax = (cv.top.firstOrNull()?.sum ?: 1L).coerceAtLeast(1L); val coral = C.hue("coral"); val amber = C.hue("amber")
        cv.top.take(shown).forEachIndexed { i, tp ->
            OcvCard("ocv_top", i, { Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (i == 0) coral.fg else amber.bg), contentAlignment = Alignment.Center) { Text("${i + 1}", color = if (i == 0) Color.White else amber.fg, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp) } },
                title(tp.id, tp.title), Money.kes(tp.sum), true, null, "", listOf((COUNTRIES[tp.ck]?.let { "$it · " } ?: "") + if (tp.n == 1) t("1 repair", "matengenezo 1") else t("${tp.n} repairs", "matengenezo ${tp.n}"))) { picked = tp.id }
        }
        if (cv.top.size > shown) OcvMore(cv.top.size - shown, "ocv_cmore") { shown += 30 }
    } else {
        if (cv.rows.isEmpty()) EmptyDrop(t("No repair costs recorded yet. Enter the actual cost when parts are ordered or a repair is fixed.", "Bado hakuna gharama za matengenezo. Andika gharama halisi vipuri vikiagizwa au matengenezo yakikamilika."))
        cv.rows.take(shown).forEachIndexed { i, r ->
            val fx = r.step == "fixed"; val hu = C.hue(if (fx) "green" else "amber")
            OcvCard("ocv_cost", i, { IconTile(if (fx) "check" else "box", hu.fg, hu.bg, 40.dp) }, title(r.id, r.title), Money.kes(r.actual), true,
                { OcvPill(if (fx) t("Fixed", "Imerekebishwa") else t("Parts ordered", "Vipuri vimeagizwa"), if (fx) "ok" else "warn") }, smsAgo(r.at),
                listOf(listOf(r.what, r.by).filter { it.isNotBlank() }.joinToString(" · ") + if (r.receipt) " · " + t("receipt", "risiti") else "")) { picked = r.id }
        }
        if (cv.rows.size > shown) OcvMore(cv.rows.size - shown, "ocv_cmore") { shown += 30 }
    }
    // by country, this year
    Card(Modifier.padding(top = 14.dp).testTag("ocv_by_country"), pad = 16.dp) {
        Text(t("By country, this year", "Kwa nchi, mwaka huu"), style = MaterialTheme.typography.titleSmall, color = C.ink)
        val cmax = (cv.byCountry.values.maxOrNull() ?: 1L).coerceAtLeast(1L)
        COUNTRIES.forEach { (k, n) ->
            val amt = cv.byCountry[k] ?: 0L
            Column(Modifier.padding(top = 10.dp)) {
                Row { Text((OCV_FLAG[k] ?: "") + "  " + n, style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.weight(1f)); Text(Money.kes(amt), style = MaterialTheme.typography.titleSmall, color = C.ink) }
                OcvBar(amt.toFloat() / cmax, OCV_CC[k] ?: C.blue, Modifier.padding(top = 4.dp), 8.dp)
            }
        }
    }
    // folded away: the second approval, and the download
    Card(Modifier.padding(top = 12.dp), pad = 4.dp) {
        OcvFold("approve_setting", "shield", "purple", t("Second approval", "Idhini ya pili"), t("Above ", "Zaidi ya ") + Money.kes(cv.approveAbove)) {
            var lim by remember(cv.approveAbove) { mutableStateOf(cv.approveAbove.toString()) }
            var lm by remember { mutableStateOf<Pair<String, String>?>(null) }
            var saved by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(saved) { if (saved != null) { kotlinx.coroutines.delay(2600); saved = null } }
            Text(t("Repairs estimated above this need a second admin to approve the cost before parts are ordered. The person who asks can’t approve their own.", "Matengenezo yanayokadiriwa zaidi ya kiasi hiki yanahitaji msimamizi wa pili kuidhinisha kabla ya kuagiza vipuri. Aliyeomba hawezi kuidhinisha mwenyewe."), style = MaterialTheme.typography.bodySmall, color = C.muted)
            Field(lim, { lim = it.filter { c -> c.isDigit() } }, t("Second approval above (KES)", "Idhini ya pili zaidi ya (KES)"), number = true)
            Btn(t("Save", "Hifadhi"), {
                scope.launch {
                    val x = m.api.admin(JSONObject().put("action", "settings").put("approveAbove", lim))
                    lm = if (x.ok) "ok" to t("Saved ✓ Second approval above ", "Imehifadhiwa ✓ Idhini ya pili zaidi ya ") + Money.kes(x.json.optLong("approveAbove")) else "bad" to reasonText(x.reason)
                    if (x.ok) { saved = t("Saved", "Imehifadhiwa"); m.reload(); tick++ }
                }
            }, Modifier.padding(top = 10.dp).fillMaxWidth().testTag("approve_save"), icon = "check", enabled = Money.parse(lim) != null, saved = saved)
            lm?.let { (tone, text) -> Msg(text, tone) }
        }
        HorizontalDivider(color = C.line, modifier = Modifier.padding(horizontal = 14.dp))
        OcvFold("ocv_csv", "upload", "blue", t("Download", "Hamisha"), t("Every cost entry, as a spreadsheet", "Kila gharama, kama jedwali")) {
            Text(t("A CSV file that opens in Excel and Google Sheets: every cost entry from the last 2 years.", "Faili ya CSV inayofunguka kwenye Excel na Google Sheets: kila gharama ya miaka 2 iliyopita."), style = MaterialTheme.typography.bodySmall, color = C.muted)
            Btn(t("Share CSV", "Shiriki CSV"), { shareCsv(ctx, "rafiki-repair-costs-${LocalDate.now()}.csv", costsCsv(cv, s.projects)) }, Modifier.padding(top = 10.dp).fillMaxWidth().testTag("ocv_csv_go"), light = true, icon = "upload")
        }
    }
    picked?.let { id -> OcvCostSheet(cv, s, id, { picked = null }) { picked = null; open(id) } }
}

/** One project's repair costs: the last 12 months, and every entry. */
@Composable
private fun OcvCostSheet(cv: CostView, s: Snapshot, id: String, close: () -> Unit, openIt: () -> Unit) {
    val all = cv.rows.filter { it.id == id }
    val p = s.projects.firstOrNull { it.id == id }
    val tp = cv.top.firstOrNull { it.id == id }
    val yr = Instant.now().minus(365, ChronoUnit.DAYS)
    val y = all.filter { runCatching { Instant.parse(it.at).isAfter(yr) }.getOrDefault(false) }
    val sum = if (y.isNotEmpty()) y.sumOf { it.actual } else tp?.sum ?: 0L
    val n = if (y.isNotEmpty()) y.size else tp?.n ?: 0
    val title = p?.title ?: all.firstOrNull()?.title?.takeIf { it.isNotBlank() } ?: tp?.title ?: id
    val am = C.hue("amber")
    OcvSheet("ocv_cost_sheet", close) { hide ->
        OcvSheetHead({ IconTile("coins", am.fg, am.bg, 52.dp) }, COUNTRIES[p?.ck ?: tp?.ck ?: ""] ?: "", C.muted, title) { hide {} }
        Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Money.kes(sum), style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold), color = C.ink, modifier = Modifier.testTag("ocv_cost_total"))
            Text(t("spent in the last 12 months", "zilizotumika katika miezi 12 iliyopita") + if (n > 0) " · " + (if (n == 1) t("1 entry", "kiingilio 1") else t("$n entries", "viingilio $n")) else "", style = MaterialTheme.typography.bodySmall, color = C.muted, textAlign = TextAlign.Center)
        }
        if (all.isNotEmpty()) {
            Text(t("COST HISTORY", "HISTORIA YA GHARAMA"), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp, fontWeight = FontWeight.ExtraBold), color = C.muted, modifier = Modifier.padding(top = 18.dp, bottom = 2.dp))
            all.forEach { r ->
                val fx = r.step == "fixed"; val hu = C.hue(if (fx) "green" else "amber"); val step = if (fx) t("Fixed", "Imerekebishwa") else t("Parts ordered", "Vipuri vimeagizwa")
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).testTag("ocv_hist"), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(if (fx) "check" else "box", hu.fg, hu.bg, 36.dp); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.what.ifBlank { step }, style = MaterialTheme.typography.titleSmall, color = C.ink)
                        Text(listOfNotNull(step, r.by, smsAgo(r.at), if (r.receipt) t("receipt", "risiti") else null).filter { it.isNotBlank() }.joinToString(" · ") { it.replace(' ', '\u00A0') }, style = MaterialTheme.typography.bodySmall, color = C.muted)
                    }
                    Spacer(Modifier.width(8.dp)); Text(Money.kes(r.actual), style = MaterialTheme.typography.titleSmall, color = C.ink, maxLines = 1, softWrap = false)
                }
                HorizontalDivider(color = C.line)
            }
        }
        if (p != null) Btn(t("Open project", "Fungua mradi"), { hide { openIt() } }, Modifier.padding(top = 16.dp).fillMaxWidth().testTag("ocv_cost_open"), icon = "arrow")
    }
}

// ---------------------------------------------------------------------------------------------- Visits

private fun ocvDone(a: JSONObject) = a.ocvS("done").isNotEmpty()
private fun ocvState(a: JSONObject, today: String) = when { ocvDone(a) -> "done"; a.optString("date") < today -> "late"; a.optString("date") == today -> "today"; else -> "soon" }
private fun ocvDays(a: String, b: String) = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(b), LocalDate.parse(a)).toInt() }.getOrDefault(0)
private fun ocvStatus(st: String): Triple<String, String, String> = when (st) {
    "late" -> Triple(t("Overdue", "Imechelewa"), "bad", "alert"); "today" -> Triple(t("Today", "Leo"), "warn", "cal")
    "done" -> Triple(t("Done", "Imefanyika"), "ok", "check"); else -> Triple(t("Planned", "Imepangwa"), "has", "cal")
}
/** "19 Oct", "Today", "Tomorrow". */
internal fun ocvDayText(d: String, today: String): String = when (ocvDays(d, today)) {
    0 -> t("Today", "Leo"); 1 -> t("Tomorrow", "Kesho"); -1 -> t("Yesterday", "Jana")
    else -> runCatching { val x = LocalDate.parse(d); x.format(DateTimeFormatter.ofPattern(if (x.year == LocalDate.now().year) "d MMM" else "d MMM yyyy", ocvLoc())) }.getOrDefault(d)
}
/** "3 days late", "In 4 days". */
internal fun ocvRelText(date: String, today: String): String {
    val n = ocvDays(date, today)
    return when {
        n < -1 -> t("${-n} days late", "Imechelewa siku ${-n}"); n == -1 -> t("1 day late", "Imechelewa siku 1")
        n == 0 -> t("Today", "Leo"); n == 1 -> t("Tomorrow", "Kesho"); else -> t("In $n days", "Baada ya siku $n")
    }
}

/** After "Overdue": how long, without saying "late" twice ("Overdue · 3 days", "Imechelewa · siku 3"). */
internal fun ocvLateShort(date: String, today: String): String {
    val n = -ocvDays(date, today)
    return if (n == 1) t("1 day", "siku 1") else t("$n days", "siku $n")
}

/** Office › Visits: the assigned visits as cards with filters (status and person); the assign form and each
 *  visit's actions (reassign, cancel, open the project) live in sheets. */
@Composable
fun OcvVisits(m: AppModel, s: Snapshot, open: (String) -> Unit) {
    var team by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(Unit) { val r = m.api.adminGet("team"); team = (r.json.optJSONArray("team")).ocvObjs().map { it.optString("name") }.filter { it.isNotBlank() }.sorted() }
    var items by remember { mutableStateOf<List<JSONObject>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var vf by remember { mutableStateOf("open") }
    var vp by remember { mutableStateOf("") }
    var shown by remember { mutableIntStateOf(30) }
    var picked by remember { mutableStateOf<JSONObject?>(null) }
    var assigning by remember { mutableStateOf(false) }
    // "Assign a visit" from a person's sheet in Office › Team: the sheet opens with them chosen (TeamPerson.kt)
    var assignTo by remember { mutableStateOf(TeamGo.assign.orEmpty()) }
    LaunchedEffect(Unit) { if (TeamGo.assign != null) { TeamGo.assign = null; assigning = true } }
    LaunchedEffect(tick) { val r = m.api.adminGet("assign"); if (r.ok) { items = r.json.optJSONArray("items").ocvObjs(); err = null } else err = reasonText(r.reason) }
    val all = items
    if (all == null) { Text(err ?: t("Loading…", "Inapakia…"), color = if (err != null) C.bad else C.muted); return }
    val today = LocalDate.now().toString()
    fun inF(a: JSONObject, f: String) = ocvState(a, today).let { if (f == "open") it != "done" else it == f }
    val byP = { a: JSONObject -> vp.isBlank() || a.optString("to") == vp }
    val fs = listOf("open" to t("Planned", "Zimepangwa"), "today" to t("Today", "Leo"), "late" to t("Overdue", "Zimechelewa"), "done" to t("Done", "Zimefanyika"))
    val cnt = fs.associate { (k, _) -> k to all.count { inF(it, k) && byP(it) } }
    val list = all.filter { inF(it, vf) && byP(it) }.let { l -> if (vf == "done") l.sortedByDescending { it.optString("done") } else l.sortedWith(compareBy({ it.optString("date") }, { it.optString("at") })) }

    OcvHead("ocv_visits_head", "cal", "blue", t("Visits", "Ziara"), t("Site visits given to the field team. Tap one to change it.", "Ziara walizopewa timu ya uwanjani. Gusa moja kuibadilisha.")) {
        Row(Modifier.padding(top = 12.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OcvNum(Modifier.weight(1f), "${cnt["open"]}", t("planned", "zimepangwa"), on = vf == "open", tag = "ocv_n_open") { vf = "open"; shown = 30 }
            OcvNum(Modifier.weight(1f), "${cnt["today"]}", t("today", "leo"), if ((cnt["today"] ?: 0) > 0) C.warn else C.ink, vf == "today", tag = "ocv_n_today") { vf = "today"; shown = 30 }
            OcvNum(Modifier.weight(1f), "${cnt["late"]}", t("overdue", "zimechelewa"), if ((cnt["late"] ?: 0) > 0) C.bad else C.ink, vf == "late", tag = "ocv_n_late") { vf = "late"; shown = 30 }
        }
        Btn(t("Assign a visit", "Gawa ziara"), { assigning = true }, Modifier.padding(top = 12.dp).fillMaxWidth().testTag("ocv_assign"), icon = "plus")
    }
    if (all.isNotEmpty()) {
        OcvChips(fs.map { (k, l) -> Triple(l, "${cnt[k]}", vf == k) }, fs.map { "ocv_vf_" + it.first }) { vf = fs[it].first; shown = 30 }
        val ppl = all.filter { inF(it, vf) }.groupingBy { it.optString("to") }.eachCount().toMutableMap().apply { if (vp.isNotBlank() && vp !in this) put(vp, 0) }
        val who = ppl.keys.sortedWith(compareBy({ -(ppl[it] ?: 0) }, { it }))
        if (who.size > 1 || vp.isNotBlank()) OcvChips(listOf(Triple(t("Everyone", "Wote"), "${all.count { inF(it, vf) }}", vp.isBlank())) + who.map { Triple(it, "${ppl[it]}", vp == it) },
            listOf("ocv_vp_all") + who.map { "ocv_vp_$it" }) { i -> vp = if (i == 0) "" else who[i - 1]; shown = 30 }
    }
    if (all.isEmpty()) EmptyDrop(t("No visits assigned yet. Tap “Assign a visit” to give one to the field team.", "Bado hakuna ziara zilizogawiwa. Gusa “Gawa ziara” kumpa mtu wa timu."))
    else if (list.isEmpty()) EmptyDrop(if (vp.isNotBlank()) t("Nothing here for $vp. Try another filter.", "Hakuna kitu hapa kwa $vp. Jaribu kichujio kingine.") else when (vf) {
        "today" -> t("Nothing planned for today.", "Hakuna kilichopangwa leo."); "late" -> t("Nothing overdue. Well done!", "Hakuna iliyochelewa. Hongera!")
        "done" -> t("No visits done in the last 4 months.", "Hakuna ziara zilizofanyika katika miezi 4 iliyopita."); else -> t("No visits planned. Tap “Assign a visit” to give one to the field team.", "Hakuna ziara zilizopangwa. Gusa “Gawa ziara” kumpa mtu wa timu.")
    })
    list.take(shown).forEachIndexed { i, a ->
        val st = ocvState(a, today); val (label, tone, _) = ocvStatus(st)
        val to = a.optString("to"); val note = a.ocvS("note")
        val right = if (st == "done") smsAgo(a.optString("done")) else ocvDayText(a.optString("date"), today)
        val rel = if (st == "done") "" else ocvRelText(a.optString("date"), today)
        OcvCard("ocv_visit", i, { OcvAvatar(to) }, a.ocvS("t").ifBlank { s.projects.firstOrNull { it.id == a.optString("id") }?.title ?: a.optString("id") },
            right, false, { OcvPill(label, tone) }, "",
            listOf(to + if (rel.isNotEmpty() && rel != right) " · $rel" else "", if (note.isNotBlank()) "“$note”" else ""), italicLast = true) { picked = a }
    }
    if (list.size > shown) OcvMore(list.size - shown, "ocv_vmore") { shown += 30 }

    picked?.let { a -> OcvVisitSheet(m, s, a, team, today, { picked = null }, { id -> picked = null; open(id) }) { tick++ } }
    if (assigning) OcvAssignSheet(m, s, team, { assigning = false; assignTo = "" }, assignTo) { vf = "open"; vp = ""; tick++ }
}

/** One assigned visit: the details, and reassign, cancel or open the project. */
@Composable
private fun OcvVisitSheet(m: AppModel, s: Snapshot, a: JSONObject, team: List<String>, today: String, close: () -> Unit, openIt: (String) -> Unit, changed: () -> Unit) {
    val scope = rememberScope()
    val st = ocvState(a, today); val (label, tone, icon) = ocvStatus(st); val (fg, bg) = ocvTone(tone)
    val id = a.optString("id"); val p = s.projects.firstOrNull { it.id == id }
    val title = a.ocvS("t").ifBlank { p?.title ?: id }
    var re by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf(false) }
    var to by remember { mutableStateOf(a.optString("to")) }
    var date by remember { mutableStateOf(a.optString("date")) }
    var msg by remember { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    OcvSheet("ocv_visit_sheet", close) { hide ->
        OcvSheetHead({ IconTile(icon, fg, bg, 52.dp) }, label + when (st) { "late" -> " · " + ocvLateShort(a.optString("date"), today); "soon" -> " · " + ocvRelText(a.optString("date"), today); else -> "" }, fg, title) { hide {} }
        Column(Modifier.padding(top = 10.dp)) {
            OcvFact(t("Field officer", "Afisa wa uwanjani"), a.optString("to"))
            OcvFact(t("Date", "Tarehe"), runCatching { LocalDate.parse(a.optString("date")).format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", ocvLoc())) }.getOrDefault(a.optString("date")))
            a.ocvS("note").takeIf { it.isNotBlank() }?.let { OcvFact(t("Note", "Maelezo"), it) }
            if (a.ocvS("by").isNotBlank()) OcvFact(t("Assigned by", "Aliyegawa"), a.ocvS("by"), sub = a.ocvS("at").takeIf { it.isNotBlank() }?.let { whenText(it) } ?: "")
            if (st == "done") OcvFact(t("Done", "Imefanyika"), whenText(a.optString("done")), C.ok)
        }
        if (p != null) Btn(t("Open project", "Fungua mradi"), { hide { openIt(id) } }, Modifier.padding(top = 16.dp).fillMaxWidth().testTag("ocv_v_open"), icon = "arrow")
        if (st != "done") {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(t("Reassign", "Mpe mwingine"), { re = !re; ask = false; msg = null }, Modifier.weight(1f).testTag("ocv_v_re"), icon = "people", light = true, small = true)
                Btn(t("Cancel visit", "Ghairi ziara"), { ask = true; re = false; msg = null }, Modifier.weight(1f).testTag("ocv_v_rm"), icon = "trash", light = true, small = true)
            }
            AnimatedVisibility(re, enter = if (Motion.on) expandVertically(tween(240)) + fadeIn(tween(240)) else fadeIn(tween(0)), exit = if (Motion.on) shrinkVertically(tween(200)) + fadeOut(tween(200)) else fadeOut(tween(0))) {
                Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.hue("slate").bg).padding(start = 14.dp, end = 14.dp, bottom = 14.dp).testTag("ocv_v_re_box")) {
                    val names = (if (to.isNotBlank() && to !in team) listOf(to) else emptyList()) + team
                    Picker(t("Field officer", "Afisa wa uwanjani"), to, names.map { it to it }, { to = it })
                    DateField(t("Date", "Tarehe"), date, { date = it })
                    Btn(t("Save the change", "Hifadhi badiliko"), {
                        if (to == a.optString("to") && date == a.optString("date")) { msg = "warn" to t("Nothing changed yet: choose another person or date.", "Hakuna kilichobadilika: chagua mtu au tarehe nyingine."); return@Btn }
                        busy = true; msg = "" to t("Saving…", "Inahifadhi…")
                        scope.launch {
                            val x = m.api.admin(JSONObject().put("action", "assign").put("ids", JSONArray(listOf(id))).put("to", to).put("date", date).put("note", a.ocvS("note")))
                            if (!x.ok) { busy = false; msg = "bad" to reasonText(x.reason); return@launch }
                            m.api.admin(JSONObject().put("action", "unassign").put("aid", a.optString("aid")))
                            msg = "ok" to t("Changed ✓ $to will see it in their app.", "Imebadilishwa ✓ $to ataiona kwenye programu yake."); changed()
                            kotlinx.coroutines.delay(800); hide {}
                        }
                    }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("ocv_v_re_go"), icon = "check", enabled = !busy && to.isNotBlank() && date.isNotBlank())
                }
            }
            if (ask) Column(Modifier.padding(top = 12.dp).rise(0).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.badBg).padding(14.dp).testTag("ocv_v_ask")) {
                Text(t("Cancel this visit? It comes off ${a.optString("to")}’s list.", "Ghairi ziara hii? Itaondolewa kwenye orodha ya ${a.optString("to")}."), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = C.ink)
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn(t("Keep it", "Iache"), { ask = false }, Modifier.weight(1f), light = true, small = true)
                    Btn(t("Cancel visit", "Ghairi ziara"), {
                        busy = true
                        scope.launch {
                            val x = m.api.admin(JSONObject().put("action", "unassign").put("aid", a.optString("aid")))
                            busy = false
                            if (x.ok) { msg = "ok" to t("Cancelled ✓", "Imeghairiwa ✓"); changed(); kotlinx.coroutines.delay(600); hide {} } else msg = "bad" to reasonText(x.reason)
                        }
                    }, Modifier.weight(1f).testTag("ocv_v_yes"), small = true, enabled = !busy, color = C.bad)
                }
            }
        }
        msg?.let { (tn, text) -> Msg(text, tn) }
    }
}

/** Assign a visit: who, when, a note, and which projects. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OcvAssignSheet(m: AppModel, s: Snapshot, team: List<String>, close: () -> Unit, pre: String = "", done: () -> Unit) {
    val scope = rememberScope()
    val state = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun hide(then: () -> Unit = {}) { scope.launch { state.hide() }.invokeOnCompletion { close(); then() } }
    var show by remember { mutableStateOf("due") }
    var ck by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<String>() }
    var to by remember { mutableStateOf(pre) }
    var date by remember { mutableStateOf(LocalDate.now().plusDays(1).toString()) }
    var note by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(team) { if (to.isBlank() && team.isNotEmpty()) to = team.first() }
    val bl = C.hue("blue")
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = close, sheetState = state, containerColor = C.card, modifier = Modifier.testTag("ocv_assign_sheet")) {
        Column(Modifier.fillMaxHeight(0.94f)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
                OcvSheetHead({ IconTile("cal", bl.fg, bl.bg, 52.dp) }, t("They see it in their app", "Ataiona kwenye programu yake"), C.muted, t("Assign a visit", "Gawa ziara")) { hide() }
                if (team.isEmpty()) Msg(t("Add people in the Team tab first.", "Ongeza watu kwenye Timu kwanza."), "warn")
                else Picker(t("Field officer", "Afisa wa uwanjani"), to, team.map { it to it }, { to = it })
                DateField(t("Date", "Tarehe"), date, { date = it })
                Field(note, { note = it }, t("Note (optional)", "Maelezo (si lazima)"), placeholder = t("e.g. check the pump and meet the committee", "mf. kagua pampu na ukutane na kamati"))
                Text(t("WHICH PROJECTS?", "MIRADI IPI?"), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp, fontWeight = FontWeight.ExtraBold), color = C.muted, modifier = Modifier.padding(top = 18.dp))
                Picker(t("Show", "Onyesha"), show, listOf("due" to t("Due a visit", "Zinasubiri ziara"), "repairs" to t("Open repairs", "Matengenezo yaliyo wazi"), "all" to t("All with a location", "Zote zenye mahali")), { show = it })
                Picker(t("Country", "Nchi"), ck, listOf("" to t("All", "Zote")) + COUNTRIES.toList(), { ck = it })
                Field(q, { q = it }, t("Search projects", "Tafuta miradi"), placeholder = t("Name or area", "Jina au eneo"))
                val rows = s.projects.filter { p -> p.gps != null && (ck.isBlank() || p.ck == ck) && (q.isBlank() || (p.title + " " + p.area).contains(q.trim(), ignoreCase = true)) && when (show) { "due" -> ageDays(s, p) >= DUE_DAYS; "repairs" -> s.repairs.containsKey(p.id); else -> true } }
                    .let { l -> if (show == "due") l.sortedByDescending { ageDays(s, it) } else l }.take(80)
                rows.forEach { p ->
                    val on = p.id in picked
                    Row(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) C.tint else C.card).border(1.dp, if (on) C.blue else C.line, RoundedCornerShape(14.dp))
                        .clickable(role = Role.Checkbox) { if (on) picked.remove(p.id) else picked.add(p.id) }.padding(8.dp).testTag("ocv_pick_${p.id}"), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(on, { if (it) picked.add(p.id) else picked.remove(p.id) }, colors = CheckboxDefaults.colors(checkedColor = C.blue))
                        Column(Modifier.weight(1f)) { Text(p.title, style = MaterialTheme.typography.titleSmall, color = C.text); Text(p.sub, style = MaterialTheme.typography.bodySmall, color = C.muted) }
                    }
                }
                if (rows.isEmpty()) Text(t("No projects match.", "Hakuna mradi unaolingana."), color = C.muted, modifier = Modifier.padding(top = 8.dp))
            }
            // the button stays in reach under the list
            HorizontalDivider(color = C.line)
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp)) {
                Btn(if (picked.isEmpty()) t("Assign", "Gawa") else if (picked.size == 1) t("Assign 1 visit", "Gawa ziara 1") else t("Assign ${picked.size} visits", "Gawa ziara ${picked.size}"), {
                    busy = true; msg = "" to t("Assigning…", "Inagawa…")
                    scope.launch {
                        val x = m.api.admin(JSONObject().put("action", "assign").put("ids", JSONArray(picked.toList())).put("to", to).put("date", date).put("note", note))
                        busy = false
                        if (!x.ok) { msg = "bad" to reasonText(x.reason); return@launch }
                        msg = "ok" to (if (picked.size == 1) t("Assigned ✓ $to will see it in their app.", "Imegawiwa ✓ $to ataiona kwenye programu yake.") else t("Assigned ✓ $to will see them in their app.", "Imegawiwa ✓ $to ataziona kwenye programu yake."))
                        picked.clear(); note = ""; done()
                        kotlinx.coroutines.delay(900); hide()
                    }
                }, Modifier.fillMaxWidth().testTag("ocv_assign_go"), icon = "cal", enabled = !busy && picked.isNotEmpty() && to.isNotBlank() && date.isNotBlank())
                msg?.let { (tone, text) -> Msg(text, tone) }
            }
        }
    }
}

package org.rafikioutreach.pin.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.rafikioutreach.pin.t

// Office › Donors, QR codes, Team map and Plan a week (dqm): the calm look of Office › Community. A head with an
// icon, one line and a few numbers; filter chips with counts; tappable cards that open a sheet holding every action;
// set-up and "how it works" folded into rows that open with a tap.

/** Two more line drawings (the same as the web app's), and everything else from [Ic]. */
internal object DqmIc {
    private val D = mapOf(
        "edit" to listOf("M4 20h4l10.5-10.5a2.8 2.8 0 0 0-4-4L4 16z", "M13.5 6.5l4 4"),
        "heart" to listOf("M12 20s-7.5-4.6-7.5-10.2A4.3 4.3 0 0 1 12 7.3a4.3 4.3 0 0 1 7.5 2.5C19.5 15.4 12 20 12 20z"),
    )
    private val cache = HashMap<String, ImageVector>()
    fun get(name: String): ImageVector = D[name]?.let { paths ->
        cache.getOrPut(name) {
            ImageVector.Builder("dqm_$name", 24.dp, 24.dp, 24f, 24f).apply {
                paths.forEach { addPath(addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) }
            }.build()
        }
    } ?: Ic.get(name)
}

/** A rounded square with an icon (also the two drawings above). */
@Composable
internal fun DqmTile(icon: String, tint: Color, bg: Color, size: Dp = 42.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(if (size > 50.dp) 20.dp else 14.dp)).background(bg), contentAlignment = Alignment.Center) {
        Icon(DqmIc.get(icon), null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

/** (text colour, background) for a tone: "bad", "warn", "ok", "has" or "off". */
@Composable
internal fun dqmTone(tone: String): Pair<Color, Color> = when (tone) {
    "bad" -> C.bad to C.badBg; "warn" -> C.warn to C.warnBg; "ok" -> C.ok to C.okBg; "has" -> C.blue2 to C.tint; else -> C.muted to C.bg
}

/** A number tile in a head: with a [key] it is tappable (it sets the filter). */
data class DqmNum(val n: Int, val label: String, val tone: String = "", val key: String? = null)

@Composable
internal fun DqmHead(tag: String, icon: String, hue: String, title: String, line: String, nums: List<DqmNum> = emptyList(), picked: String? = null, onNum: (String) -> Unit = {},
                     trailing: (@Composable () -> Unit)? = null, actions: (@Composable ColumnScope.() -> Unit)? = null) {
    Card(Modifier.testTag(tag), pad = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val h = C.hue(hue)
            DqmTile(icon, h.fg, h.bg, 44.dp); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = C.ink)
                Text(line, style = MaterialTheme.typography.bodySmall, color = C.muted)
            }
            trailing?.let { Spacer(Modifier.width(8.dp)); it() }
        }
        if (nums.isNotEmpty()) Row(Modifier.padding(top = 14.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            nums.forEach { x -> DqmStat(Modifier.weight(1f), x, x.key != null && x.key == picked, onNum) }
        }
        actions?.invoke(this)
    }
}

@Composable
private fun DqmStat(modifier: Modifier, x: DqmNum, on: Boolean, onNum: (String) -> Unit) {
    val h = rememberHaptics()
    val shape = RoundedCornerShape(14.dp)
    val col = when (x.tone) { "bad" -> C.bad; "ok" -> C.ok; "has" -> C.blue2; "off" -> C.muted; else -> C.ink }
    Column(
        modifier.fillMaxHeight().clip(shape).background(C.bg).border(if (on) 2.dp else 1.dp, if (on) C.blue else C.line, shape)
            .then(if (x.key != null) Modifier.clickable(role = Role.Tab) { h.tap(); onNum(x.key) }.testTag("n_" + x.key) else Modifier)
            .heightIn(min = 44.dp).padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Text("${x.n}", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold), color = col)
        Text(x.label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 15.sp), color = C.muted)
    }
}

/** Filter chips with counts, in a row that scrolls sideways. */
@Composable
internal fun DqmChips(items: List<Triple<String, String, Int>>, picked: String, tagPrefix: String, hue: String = "blue", onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 14.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (k, label, n) -> Chip("$label · $n", null, picked == k, { onPick(k) }, tag = tagPrefix + k, hue = hue) }
    }
}

/** A status: a dot and a word, in the tone's colours. */
@Composable
internal fun DqmPill(tone: String, label: String, modifier: Modifier = Modifier) {
    val (fg, bg) = dqmTone(tone)
    Row(modifier.clip(RoundedCornerShape(12.dp)).background(bg).padding(start = 8.dp, end = 10.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(fg)); Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.ExtraBold), color = fg)
    }
}

/** A round picture with initials. */
@Composable
internal fun DqmAvatar(name: String, bg: Color, fg: Color, size: Dp = 44.dp, ring: Boolean = false) {
    Box(Modifier.size(size).clip(CircleShape).then(if (ring) Modifier.background(Color.White).padding(3.dp).clip(CircleShape) else Modifier).background(bg), contentAlignment = Alignment.Center) {
        Text(initialsOf(name), color = fg, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.32f).sp)
    }
}

/** A calm card: something on the left, one title, a status, at most two lines, a time and a chevron. The whole card is tappable. */
@Composable
internal fun DqmCard(tag: String, modifier: Modifier = Modifier, title: String, pill: Pair<String, String>? = null, lines: List<String> = emptyList(), time: String = "", dim: Boolean = false,
                     leading: @Composable () -> Unit, onClick: () -> Unit) {
    val h = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier.press(src).fillMaxWidth().clip(shape).background(if (C.glassy) C.glass2 else C.card).border(1.dp, if (C.glassy) C.glassEdge else C.line, shape)
            .clickable(interactionSource = src, indication = androidx.compose.material3.ripple(), role = Role.Button) { h.tap(); onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp).alpha(if (dim) 0.72f else 1f).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading(); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = C.ink)
            pill?.let { (tone, label) -> DqmPill(tone, label, Modifier.padding(top = 4.dp)) }
            lines.filter { it.isNotBlank() }.take(2).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 3.dp), maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Spacer(Modifier.width(8.dp))
        if (time.isNotBlank()) Text(time, style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 1)
        Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(18.dp))
    }
}

/** A row that opens and closes with a tap; the summary under the title shows what's inside without opening it. */
@Composable
internal fun DqmFold(tag: String, icon: String, hue: String, title: String, summary: String, tone: String, open: MutableState<Boolean> = remember { mutableStateOf(false) }, content: @Composable ColumnScope.() -> Unit) {
    val h = rememberHaptics()
    val turn by animateFloatAsState(if (open.value) 90f else 0f, tween(if (Motion.on) 220 else 0), label = "chev")
    val (fg, _) = dqmTone(tone)
    val hu = C.hue(hue)
    Column(Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { h.tap(); open.value = !open.value }.heightIn(min = 60.dp).padding(horizontal = 14.dp, vertical = 8.dp).testTag(tag + "_toggle"),
            verticalAlignment = Alignment.CenterVertically) {
            DqmTile(icon, hu.fg, hu.bg, 38.dp); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = C.ink)
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 6.dp).size(7.dp).clip(CircleShape).background(if (tone == "off") C.muted else fg)); Spacer(Modifier.width(6.dp))
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = C.muted)
                }
            }
            Spacer(Modifier.width(6.dp))
            Icon(Ic.get("chev"), null, tint = C.muted, modifier = Modifier.size(18.dp).rotate(turn))
        }
        AnimatedVisibility(open.value, enter = if (Motion.on) expandVertically(tween(240)) + fadeIn(tween(240)) else fadeIn(tween(0)), exit = if (Motion.on) shrinkVertically(tween(200)) + fadeOut(tween(200)) else fadeOut(tween(0))) {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp).testTag(tag + "_body"), content = content)
        }
    }
}

/** A sheet that springs up from the bottom, with room to scroll; [content] gets a hide(then) that slides it away first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DqmSheet(tag: String, close: () -> Unit, content: @Composable ColumnScope.(hide: (() -> Unit) -> Unit) -> Unit) {
    val scope = rememberScope()
    val state = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val hide: (() -> Unit) -> Unit = { then -> scope.launch { state.hide() }.invokeOnCompletion { close(); then() } }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = close, sheetState = state, containerColor = C.card, modifier = Modifier.testTag(tag)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) { content(hide) }
    }
}

/** The top of a sheet: a picture, the title, a line or two, a status, and a close X. */
@Composable
internal fun DqmSheetHead(title: String, sub: List<String>, pill: Pair<String, String>? = null, closeTag: String, onClose: () -> Unit, leading: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.rise(0)) { leading() }
            Text(title, style = MaterialTheme.typography.titleLarge, color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            sub.filter { it.isNotBlank() }.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp)) }
            pill?.let { (tone, label) -> DqmPill(tone, label, Modifier.padding(top = 8.dp)) }
        }
        Box(Modifier.align(Alignment.TopEnd).size(44.dp).clip(CircleShape).background(C.bg).clickable(role = Role.Button, onClick = onClose).testTag(closeTag), contentAlignment = Alignment.Center) {
            Icon(Ic.get("x"), t("Close", "Funga"), tint = C.ink, modifier = Modifier.size(20.dp))
        }
    }
}

/** A label and its value, on one line (the value can have a small line under it). */
@Composable
internal fun DqmFact(label: String, value: String, sub: String = "", modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.padding(end = 16.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = C.ink, textAlign = TextAlign.End)
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = C.muted, textAlign = TextAlign.End)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
    }
}

/** A plain bordered button for the less common actions at the bottom of a sheet (red for the ones that remove things). */
@Composable
internal fun DqmLineBtn(text: String, icon: String, onClick: () -> Unit, modifier: Modifier = Modifier, red: Boolean = false, enabled: Boolean = true) {
    val shape = RoundedCornerShape(14.dp)
    val fg = if (red) C.bad else C.ink
    Row(modifier.clip(shape).border(1.dp, C.line, shape).clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(DqmIc.get(icon), null, tint = if (red) C.bad else C.blue, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg, textAlign = TextAlign.Center)
    }
}

/** Ask before something that can't be undone: the question, Cancel and the action. */
@Composable
internal fun DqmAsk(question: String, yes: String, red: Boolean, busy: Boolean, msg: String?, onNo: () -> Unit, onYes: () -> Unit, tag: String = "dqm_ask") {
    Column(Modifier.padding(top = 12.dp).rise(0).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (red) C.badBg else C.hue("slate").bg).padding(14.dp).testTag(tag)) {
        Text(question, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = C.ink)
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn(t("Cancel", "Ghairi"), onNo, Modifier.weight(1f).testTag(tag + "_no"), light = true, small = true)
            Btn(yes, onYes, Modifier.weight(1f).testTag(tag + "_yes"), small = true, enabled = !busy, color = if (red) C.bad else null)
        }
        Msg(msg, "bad")
    }
}

/** A small uppercase label over a group in a sheet. */
@Composable
internal fun DqmLabel(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.sp, fontWeight = FontWeight.ExtraBold), color = C.muted, modifier = modifier.padding(top = 18.dp, bottom = 6.dp))

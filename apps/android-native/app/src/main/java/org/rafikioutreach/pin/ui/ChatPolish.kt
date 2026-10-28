package org.rafikioutreach.pin.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.ChatRow
import org.rafikioutreach.pin.data.Msg
import org.rafikioutreach.pin.data.TripCard
import org.rafikioutreach.pin.t

/*
 * Chat polish round (chatpa, S/chatpolish-brief.md): the look and feel pieces the chat screens share.
 * Every animation is 150–350 ms and stands still when the app's motion is off or the phone removes animations.
 */

/** Chat may move: the app's motion setting and the phone's "remove animations". */
@Composable
internal fun chatMotion(): Boolean = rememberMapMotion()

/** The tail's width: the side of every bubble keeps this room, so a run of bubbles lines up whether or not it has the tail. */
internal val TAIL = 7.dp

/**
 * A bubble: big round corners, smaller ones where bubbles of the same run join, and a small tail on the first bubble of
 * a run (top left for theirs, top right for mine).
 */
internal class BubbleShape(private val mine: Boolean, private val first: Boolean, private val last: Boolean) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val tail = with(density) { TAIL.toPx() }; val big = with(density) { 18.dp.toPx() }; val join = with(density) { 6.dp.toPx() }
        val w = size.width; val h = size.height
        val nb = (if (last) big else join).coerceAtMost(h / 2); val far = big.coerceAtMost(h / 2)
        val p = Path()
        if (first) {
            p.moveTo(0f, 0f)
            p.lineTo(w - far, 0f)
            p.arcTo(Rect(w - 2 * far, 0f, w, 2 * far), -90f, 90f, false)
            p.lineTo(w, h - far)
            p.arcTo(Rect(w - 2 * far, h - 2 * far, w, h), 0f, 90f, false)
            p.lineTo(tail + nb, h)
            p.arcTo(Rect(tail, h - 2 * nb, tail + 2 * nb, h), 90f, 90f, false)
            p.lineTo(tail, minOf(tail * 2f, h - nb))
            p.quadraticTo(tail, tail * 0.45f, 0f, 0f)
            p.close()
        } else {
            p.addRoundRect(RoundRect(tail, 0f, w, h, topLeftCornerRadius = CornerRadius(join), topRightCornerRadius = CornerRadius(far), bottomRightCornerRadius = CornerRadius(far), bottomLeftCornerRadius = CornerRadius(nb)))
        }
        if (mine) p.asAndroidPath().transform(android.graphics.Matrix().apply { setScale(-1f, 1f); postTranslate(w, 0f) })
        return Outline.Generic(p)
    }
}

/** My bubble colours (contract tokens); '' keeps the app's usual blue. */
internal val BUBBLES = listOf("", "green", "teal", "blue", "purple", "pink", "orange", "grey")
internal fun bubbleName(k: String) = when (k) {
    "green" -> t("Green", "Kijani"); "teal" -> t("Teal", "Samawati"); "blue" -> t("Blue", "Buluu"); "purple" -> t("Purple", "Zambarau")
    "pink" -> t("Pink", "Waridi"); "orange" -> t("Orange", "Machungwa"); "grey" -> t("Grey", "Kijivu"); else -> t("Usual", "Kawaida")
}
@Composable
internal fun bubbleColor(k: String): Color {
    val d = C.dark
    return when (k) {
        "green" -> if (d) Color(0xFF125C43) else Color(0xFFDCF6CF)
        "teal" -> if (d) Color(0xFF0B5257) else Color(0xFFD2F1EE)
        "blue" -> if (d) Color(0xFF184F86) else Color(0xFFDCE8FF)
        "purple" -> if (d) Color(0xFF4A2E7A) else Color(0xFFEBE0FB)
        "pink" -> if (d) Color(0xFF6E2A4F) else Color(0xFFFBDDEB)
        "orange" -> if (d) Color(0xFF6B4214) else Color(0xFFFFE7CC)
        "grey" -> if (d) Color(0xFF39444E) else Color(0xFFE8ECEF)
        else -> ChatLook.mine()
    }
}

/** Three dots bouncing one after the other ("typing…"). Still when motion is off. */
@Composable
internal fun TypingDots(color: Color, modifier: Modifier = Modifier, dot: Dp = 5.dp) {
    val moving = chatMotion() && Motion.loops
    val tr = rememberInfiniteTransition(label = "typing")
    Row(modifier.height(dot * 3).semantics { contentDescription = t("typing", "anaandika") }.testTag("typing_dots"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dot * 0.7f)) {
        repeat(3) { i ->
            val y by if (moving) tr.animateFloat(0f, 0f, infiniteRepeatable(keyframes { durationMillis = 900; 0f at (i * 120); -1f at (i * 120 + 180); 0f at (i * 120 + 360) }), label = "d$i")
                else remember { mutableFloatStateOf(0f) }
            Box(Modifier.size(dot).graphicsLayer { translationY = y * dot.toPx() * 0.9f }.clip(CircleShape).background(color))
        }
    }
}

/** Slides up and settles the first time it's drawn ([fresh]: a message that just arrived, not one scrolled back to). */
internal fun Modifier.settleIn(fresh: Boolean, motion: Boolean, mine: Boolean = false): Modifier = composed {
    val p = remember { Animatable(if (fresh && motion) 0f else 1f) }
    LaunchedEffect(Unit) { if (p.value < 1f) p.animateTo(1f, tween(260)) }
    graphicsLayer {
        val v = p.value
        alpha = v; translationY = (1f - v) * 18.dp.toPx(); translationX = (1f - v) * (if (mine) 10.dp.toPx() else -10.dp.toPx())
        val s = 0.96f + 0.04f * v; scaleX = s; scaleY = s
    }
}

/** A short pop (reactions): grows past its size and settles, each time [key] changes. */
internal fun Modifier.pop(key: Any, motion: Boolean): Modifier = composed {
    val s = remember { Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(key) { if (first) { first = false; return@LaunchedEffect }; if (motion) { s.snapTo(0.55f); s.animateTo(1.15f, tween(150)); s.animateTo(1f, tween(120)) } }
    graphicsLayer { scaleX = s.value; scaleY = s.value }
}

// --- wallpapers ---------------------------------------------------------------------------------------------------------
/** The wallpapers: none, RAFIKI's own (water, savanna, night), and the simple patterns from before. */
internal val WALLS = listOf("", "water", "savanna", "night", "dots", "waves", "leaves", "drops")
internal fun wallLabel(k: String) = when (k) {
    "water" -> t("Water", "Maji"); "savanna" -> t("Savanna", "Savana"); "night" -> t("Night", "Usiku"); else -> wallName(k)
}

/** RAFIKI wallpapers, drawn on the phone (no pictures to download): water, savanna and night. Others: [Wallpaper]. */
@Composable
internal fun ChatWall(kind: String, modifier: Modifier) {
    when (kind) {
        "water", "savanna", "night" -> {
            val dark = C.dark
            Canvas(modifier.testTag("wallpaper_$kind")) { when (kind) { "water" -> water(dark); "savanna" -> savanna(dark); else -> night() } }
        }
        "none" -> {}
        else -> Wallpaper(kind, modifier)
    }
}

private fun DrawScope.water(dark: Boolean) {
    drawRect(Brush.verticalGradient(if (dark) listOf(Color(0xFF0B2233), Color(0xFF0E3048)) else listOf(Color(0xFFE6F3FB), Color(0xFFCFE6F5))))
    val ink = if (dark) Color(0x22A9D8F5) else Color(0x2A0A78C2)
    val step = 30.dp.toPx(); var y = step / 2; var row = 0
    while (y < size.height + step) {
        val p = Path(); var x = -step + (row % 2) * step / 2
        p.moveTo(x, y)
        while (x < size.width + step) { p.quadraticTo(x + step / 4, y - 5.dp.toPx(), x + step / 2, y); p.quadraticTo(x + 3 * step / 4, y + 5.dp.toPx(), x + step, y); x += step }
        drawPath(p, ink, style = Stroke(1.4.dp.toPx()))
        y += step * 1.6f; row++
    }
    val rnd = java.util.Random(11)
    repeat(26) { drawCircle(ink, (2 + rnd.nextInt(4)).dp.toPx(), Offset(rnd.nextFloat() * size.width, rnd.nextFloat() * size.height), style = Stroke(1.2.dp.toPx())) }
}

private fun DrawScope.savanna(dark: Boolean) {
    drawRect(Brush.verticalGradient(if (dark) listOf(Color(0xFF261D12), Color(0xFF1B150D)) else listOf(Color(0xFFFFF5E3), Color(0xFFF4E3C3))))
    val ink = if (dark) Color(0x22F2C98A) else Color(0x22A0661B)
    val tile = 130.dp.toPx(); var ty = 0f; var r = 0
    while (ty < size.height + tile) {
        var tx = if (r % 2 == 0) 0f else tile / 2
        while (tx < size.width + tile) {
            // an acacia: a thin trunk and a flat crown, with grass beside it
            val bx = tx + tile * 0.3f; val by = ty + tile * 0.62f
            drawLine(ink, Offset(bx, by), Offset(bx + 2.dp.toPx(), by - 22.dp.toPx()), 2.dp.toPx())
            drawOval(ink, Offset(bx - 18.dp.toPx(), by - 30.dp.toPx()), Size(40.dp.toPx(), 10.dp.toPx()))
            for (g in 0 until 4) { val gx = tx + tile * 0.62f + g * 4.dp.toPx(); drawLine(ink, Offset(gx, by), Offset(gx + (g - 1.5f) * 1.5.dp.toPx(), by - 7.dp.toPx()), 1.2.dp.toPx()) }
            drawLine(ink, Offset(tx + tile * 0.1f, by + 1.dp.toPx()), Offset(tx + tile * 0.85f, by + 1.dp.toPx()), 1.dp.toPx())
            tx += tile
        }
        ty += tile * 0.8f; r++
    }
}

private fun DrawScope.night() {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF0E1A2E), Color(0xFF1B2A4A))))
    val rnd = java.util.Random(5)
    repeat((size.width * size.height / 9000f).toInt().coerceIn(40, 400)) {
        val a = 0.25f + rnd.nextFloat() * 0.55f
        drawCircle(Color.White.copy(alpha = a), (0.6f + rnd.nextFloat() * 1.2f).dp.toPx(), Offset(rnd.nextFloat() * size.width, rnd.nextFloat() * size.height))
    }
    val c = Offset(size.width * 0.8f, 60.dp.toPx()); val rr = 16.dp.toPx()
    val moon = Path().apply { addOval(Rect(c, rr)) }
    clipPath(moon) { drawCircle(Color(0x33FFF3C4), rr, c); drawCircle(Color(0xFF13213B), rr, Offset(c.x + rr * 0.55f, c.y - rr * 0.25f)) }
}

// --- automatic group pictures: a country's flag ---------------------------------------------------------------------
/** Kenya, Uganda, Tanzania and Malawi drawn simply in a circle; another country: its letters. */
@Composable
internal fun FlagAvatar(cc: String, size: Dp) {
    val code = cc.lowercase()
    if (code !in setOf("ke", "ug", "tz", "mw")) {
        Box(Modifier.size(size).clip(CircleShape).background(C.hue("teal").bg), contentAlignment = Alignment.Center) {
            Text(code.uppercase(), color = C.hue("teal").fg, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.32f).sp)
        }
        return
    }
    Canvas(Modifier.size(size).clip(CircleShape).testTag("flag_$code")) {
        val w = this.size.width; val h = this.size.height
        fun band(top: Float, bottom: Float, c: Color) = drawRect(c, Offset(0f, h * top), Size(w, h * (bottom - top)))
        when (code) {
            "ke" -> {
                band(0f, .3f, Color(0xFF111111)); band(.3f, .35f, Color.White); band(.35f, .65f, Color(0xFFBB0000)); band(.65f, .7f, Color.White); band(.7f, 1f, Color(0xFF006600))
                drawOval(Color(0xFF111111), Offset(w * .36f, h * .18f), Size(w * .28f, h * .64f))
                drawOval(Color(0xFFBB0000), Offset(w * .41f, h * .2f), Size(w * .18f, h * .6f))
                drawLine(Color.White, Offset(w * .5f, h * .26f), Offset(w * .5f, h * .74f), w * .025f)
            }
            "ug" -> {
                val cols = listOf(Color(0xFF111111), Color(0xFFFCDC04), Color(0xFFD90000), Color(0xFF111111), Color(0xFFFCDC04), Color(0xFFD90000))
                cols.forEachIndexed { i, c -> band(i / 6f, (i + 1) / 6f, c) }
                drawCircle(Color.White, w * .2f, Offset(w / 2, h / 2)); drawCircle(Color(0xFF8A8A8A), w * .06f, Offset(w / 2, h / 2))
            }
            "tz" -> {
                drawPath(Path().apply { moveTo(0f, 0f); lineTo(w, 0f); lineTo(0f, h); close() }, Color(0xFF1EB53A))
                drawPath(Path().apply { moveTo(w, 0f); lineTo(w, h); lineTo(0f, h); close() }, Color(0xFF00A3DD))
                drawLine(Color(0xFFFCD116), Offset(-w * .1f, h * 1.1f), Offset(w * 1.1f, -h * .1f), w * .34f)
                drawLine(Color(0xFF111111), Offset(-w * .1f, h * 1.1f), Offset(w * 1.1f, -h * .1f), w * .22f)
            }
            "mw" -> {
                band(0f, 1 / 3f, Color(0xFF111111)); band(1 / 3f, 2 / 3f, Color(0xFFCE1126)); band(2 / 3f, 1f, Color(0xFF339E35))
                drawArc(Color(0xFFCE1126), 180f, 180f, true, Offset(w * .3f, h * .14f), Size(w * .4f, h * .4f))
            }
        }
    }
}

// --- friendly empty states ------------------------------------------------------------------------------------------
/** The drop, a few kind words and one thing to do. */
@Composable
internal fun ChatEmpty(text: String, tag: String, action: String? = null, modifier: Modifier = Modifier, onAction: () -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 28.dp).testTag(tag), horizontalAlignment = Alignment.CenterHorizontally) {
        Drop(72.dp, mood = Mood.Smile)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
        if (action != null) Btn(action, onAction, Modifier.padding(top = 14.dp).testTag(tag + "_go"), small = true)
    }
}

/** "Loading older…": grey bubbles where the older messages will be. */
@Composable
internal fun OlderSkeleton() {
    val moving = chatMotion() && Motion.loops
    val a by if (moving) rememberInfiniteTransition(label = "sk").animateFloat(0.45f, 0.9f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a") else remember { mutableFloatStateOf(0.7f) }
    val g = if (C.dark) Color(0x22FFFFFF) else Color(0x16000000)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("older_skeleton"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(t("Loading older…", "Inapakia za zamani…"), style = MaterialTheme.typography.labelMedium, color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        listOf(0.55f to false, 0.4f to true, 0.62f to false).forEach { (wf, right) ->
            Box(Modifier.fillMaxWidth(), contentAlignment = if (right) Alignment.CenterEnd else Alignment.CenterStart) {
                Box(Modifier.fillMaxWidth(wf).height(34.dp).graphicsLayer { alpha = a }.clip(RoundedCornerShape(16.dp)).background(g))
            }
        }
    }
}

/** "Seen by": tiny pictures of who has read the last message (up to 5, then "+3"). */
@Composable
internal fun SeenBy(names: List<String>, modifier: Modifier = Modifier) {
    if (names.isEmpty()) return
    val shown = names.take(5)
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = t("Seen by ", "Imeonwa na ") + names.joinToString(", ") }.testTag("seen_by"), verticalAlignment = Alignment.CenterVertically) {
        shown.forEachIndexed { i, n ->
            val hu = C.hue(nameHue(n))
            Box(Modifier.offset(x = (-4 * i).dp).size(20.dp).clip(CircleShape).background(C.card).padding(1.5.dp).clip(CircleShape).background(hu.bg), contentAlignment = Alignment.Center) {
                // a fixed size: the picture doesn't grow with large text, so its letter mustn't either
                PhotoOr(n, 17.dp) { Text(n.trim().take(1).uppercase(), color = hu.fg, fontSize = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.toSp() }, lineHeight = with(androidx.compose.ui.platform.LocalDensity.current) { 12.dp.toSp() }, fontWeight = FontWeight.ExtraBold, maxLines = 1, softWrap = false) }
            }
        }
        if (names.size > 5) Text("+${names.size - 5}", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = C.muted, modifier = Modifier.offset(x = (-4 * shown.size + 6).dp).testTag("seen_more"))
    }
}

/** The pinned messages: a slim bar under the chat's head; a tap jumps to the one shown, then shows the next. */
@Composable
internal fun PinBar(row: ChatRow, msgs: List<Msg>, jump: (String) -> Unit) {
    val pins = row.pins
    if (pins.isEmpty()) return
    var i by remember(row.id) { mutableIntStateOf(0) }
    val p = pins[i % pins.size]
    val text = msgs.firstOrNull { it.id == p.id }?.let { m -> org.rafikioutreach.pin.data.chatPreview(m.kind, if (m.kind == "trip") m.trip?.title.orEmpty() else m.text, m.media.firstOrNull()?.dur ?: 0.0, m.deleted, m.media.size, m.media.firstOrNull()?.type.orEmpty()) }
        ?.takeIf { it.isNotBlank() } ?: p.text.ifBlank { t("Pinned message", "Ujumbe uliobandikwa") }
    val hp = rememberHaptics()
    Row(Modifier.fillMaxWidth().background(if (C.glassy) C.glass2 else C.card).clickable(role = Role.Button) { hp.tap(); jump(p.id); i = (i + 1) % pins.size }
        .padding(horizontal = 14.dp, vertical = 7.dp).testTag("pin_bar"), verticalAlignment = Alignment.CenterVertically) {
        // which of the pins this is: a bar per pin, the current one dark
        Column(Modifier.height(30.dp).width(3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            pins.indices.forEach { k -> Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(2.dp)).background(if (k == i % pins.size) C.blue else C.line)) }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(if (pins.size > 1) t("Pinned message ${i % pins.size + 1} of ${pins.size}", "Ujumbe uliobandikwa ${i % pins.size + 1} kati ya ${pins.size}") else t("Pinned message", "Ujumbe uliobandikwa"),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold), color = C.blue, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text, style = MaterialTheme.typography.bodySmall, color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("pin_text"))
        }
        Icon(Ic.get("pin"), null, tint = C.muted, modifier = Modifier.size(16.dp))
    }
}

/** Quick replies: a row of chips above the box while it's empty; a tap sends one. */
@Composable
internal fun QuickChips(list: List<String>, pick: (String) -> Unit) {
    if (list.isEmpty()) return
    val sc = rememberScrollState()
    val bgc = C.bg
    // the row scrolls sideways; the edge fades so a chip that runs on reads as "more", never as a cut word
    Box(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().horizontalScroll(sc).padding(start = 4.dp, end = 28.dp, top = 4.dp, bottom = 4.dp).testTag("quick_row"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        list.forEachIndexed { i, q ->
            // chat 2 (B4): small chips
            Text(q, style = CwType.meta(FontWeight.Bold), color = C.ink, maxLines = 1, softWrap = false,
                modifier = Modifier.shadow(1.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(if (C.dark) C.card else Color.White.copy(alpha = .95f))
                    .then(if (C === Sun) Modifier.border(2.dp, Color.Black, RoundedCornerShape(16.dp)) else Modifier)
                    .clickable(role = Role.Button) { pick(q) }.heightIn(min = 32.dp).padding(horizontal = 12.dp, vertical = 7.dp).testTag("quick_$i"))
        }
    }
    Box(Modifier.matchParentSize()) {
        if (sc.canScrollForward) Box(Modifier.align(Alignment.CenterEnd).width(48.dp).fillMaxHeight().background(Brush.horizontalGradient(listOf(bgc.copy(alpha = 0f), bgc))))
        if (sc.canScrollBackward) Box(Modifier.align(Alignment.CenterStart).width(28.dp).fillMaxHeight().background(Brush.horizontalGradient(listOf(bgc, bgc.copy(alpha = 0f)))))
    }
    }
}

// --- trip cards -----------------------------------------------------------------------------------------------------
/** A trip shared in a chat: title, dates, the next stop, progress and "Open trip". Title only for people not on it. */
@Composable
internal fun TripCardView(card: TripCard, width: Dp) {
    Column(Modifier.width(width).testTag("trip_card")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(C.hue("teal").bg), contentAlignment = Alignment.Center) { Icon(Ic.get("route"), null, tint = C.hue("teal").fg, modifier = Modifier.size(19.dp)) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(t("TRIP", "SAFARI"), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp), color = C.hue("teal").fg)
                Text(card.title.ifBlank { t("A trip", "Safari") }, style = MaterialTheme.typography.titleSmall, color = C.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("trip_title"))
            }
        }
        when {
            card.gone -> Text(t("This trip was deleted.", "Safari hii ilifutwa."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 6.dp).testTag("trip_gone"))
            card.limited -> Text(t("Only people on this trip can open it", "Ni watu wa safari hii tu wanaoweza kuifungua"), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 6.dp).testTag("trip_limited"))
            else -> {
                val dates = if (card.from.isNotBlank()) tripDates(card.from, card.to.ifBlank { card.from }) else card.dates
                if (dates.isNotBlank()) Text(dates + tripStatus(card.status).let { if (it.isNotBlank()) " · $it" else "" }, style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                if (card.next.isNotBlank()) {
                    Text(t("Next: ", "Ijayo: ") + card.next, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = C.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp).testTag("trip_next"))
                    val whenTxt = listOfNotNull(Dates.parse(card.nextDate)?.let { Dates.weekday(it) }, card.nextAt.takeIf { it.isNotBlank() }).joinToString(", ")
                    if (whenTxt.isNotBlank()) Text(whenTxt, style = MaterialTheme.typography.bodySmall, color = C.muted, maxLines = 1, softWrap = false)
                }
                if (card.total > 0) {
                    Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (C.dark) Color(0x33FFFFFF) else Color(0x1A000000))) {
                        Box(Modifier.fillMaxWidth(card.progress).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(ChatLook.online).testTag("trip_progress"))
                    }
                    Text(t("${card.done} of ${card.total} stops", "Vituo ${card.done} kati ya ${card.total}"), style = MaterialTheme.typography.labelSmall, color = C.muted, modifier = Modifier.padding(top = 3.dp))
                }
                Text(t("Open trip", "Fungua safari"), style = MaterialTheme.typography.labelLarge, color = C.blue, textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, C.line, RoundedCornerShape(10.dp))
                        .clickable(role = Role.Button) { TripNav.go("trip:" + card.id) }.padding(vertical = 9.dp).testTag("trip_open"))
            }
        }
    }
}
private fun tripStatus(s: String) = when (s) {
    "active" -> t("on the road", "safarini"); "done" -> t("done", "imekamilika"); "planned" -> t("planned", "imepangwa"); "cancelled" -> t("cancelled", "imeghairiwa"); else -> ""
}

/** "Share to chat" from a trip: which chat? (TripShare.shareToChat opens this.) */
object ChatShare { var trip by mutableStateOf<String?>(null) }

@Composable
internal fun TripShareHost(m: AppModel) {
    DisposableEffect(Unit) { org.rafikioutreach.pin.data.TripShare.shareToChat = { id -> ChatShare.trip = id }; onDispose { org.rafikioutreach.pin.data.TripShare.shareToChat = null } }
    val id = ChatShare.trip ?: return
    val rows by m.chat.rows.collectAsState()
    val off by m.chat.off.collectAsState()
    val scope = rememberScope()
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { m.chat.start() }
    TeamSubSheet("trip_share_sheet", { ChatShare.trip = null }) { hide ->
        Text(t("Share the trip in a chat", "Shiriki safari kwenye gumzo"), style = MaterialTheme.typography.titleLarge, color = C.ink, modifier = Modifier.padding(bottom = 8.dp))
        if (off) { ChatOff(Modifier, m.chat.offNeed); return@TeamSubSheet }
        err?.let { Msg(it, "bad") }
        m.chat.sorted(rows.filter { !it.archived && !(it.onlyAdmins && !it.admin(m.chat.me)) }).forEach { r ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = !busy, role = Role.Button) {
                busy = true
                scope.launch {
                    val e = m.chat.shareTrip(r.id, id); busy = false
                    if (e != null) err = e else hide { ChatShare.trip = null; ChatNav.chat(r.id) }
                }
            }.heightIn(min = 56.dp).padding(horizontal = 6.dp).testTag("ts_${r.id}"), verticalAlignment = Alignment.CenterVertically) {
                ChatAvatar(r, m.chat.me, 40.dp, m = m); Spacer(Modifier.width(12.dp))
                Text(chatTitle(r, m.chat.me), style = MaterialTheme.typography.titleSmall, color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** A chat's name as shown: Announcements in Kiswahili is "Matangazo"; a DM is the other person. */
internal fun chatTitle(r: ChatRow, me: String): String = if (r.announce && org.rafikioutreach.pin.Lang.sw) "Matangazo" else r.title.ifBlank { r.other(me) }

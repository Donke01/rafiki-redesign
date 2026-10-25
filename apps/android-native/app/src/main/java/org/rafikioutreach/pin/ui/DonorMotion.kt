package org.rafikioutreach.pin.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

// Motion graphics for the donor screens, the same as the web donor page: water waves, rising bubbles, ripples,
// a light sweeping over a card, photos drifting slowly, and status marks that move (a tick that draws itself,
// a wrench at work, bricks stacking, a clock turning). Everything stands still when Motion is off.

/** Headings and short labels: lines balanced, words never split. */
fun TextStyle.heading(): TextStyle = copy(lineBreak = LineBreak.Heading, hyphens = Hyphens.None)
/** Paragraphs: no lonely last word, words never split. */
fun TextStyle.para(): TextStyle = copy(lineBreak = LineBreak.Paragraph, hyphens = Hyphens.None)
/** Keeps a date, an amount or a phone number on one line. */
fun String.nb(): String = replace(' ', ' ')

private val still: Boolean get() = !Motion.on || !Motion.loops

/** Two soft waves rolling sideways, one each way. */
@Composable
fun Waves(modifier: Modifier, back: Color, front: Color) {
    val tr = rememberInfiniteTransition(label = "waves")
    val a by if (still) remember { mutableFloatStateOf(0f) } else tr.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "wa")
    val b by if (still) remember { mutableFloatStateOf(0.3f) } else tr.animateFloat(1f, 0f, infiniteRepeatable(tween(14000, easing = LinearEasing)), label = "wb")
    Canvas(modifier) {
        fun wave(phase: Float, top: Float, amp: Float, color: Color) {
            val w = size.width; val h = size.height; val wl = w / 2f
            val path = Path()
            var x = -phase * wl - wl
            path.moveTo(x, h); path.lineTo(x, top)
            while (x < w + wl) {
                path.quadraticBezierTo(x + wl / 4f, top - amp, x + wl / 2f, top)
                path.quadraticBezierTo(x + wl * 3f / 4f, top + amp, x + wl, top)
                x += wl
            }
            path.lineTo(x, h); path.close()
            drawPath(path, color)
        }
        wave(b, size.height * 0.25f, size.height * 0.12f, back)
        wave(a, size.height * 0.45f, size.height * 0.10f, front)
    }
}

/** Little bubbles rising and fading. */
@Composable
fun Bubbles(modifier: Modifier, n: Int = 7, color: Color = Color(0x59FFFFFF)) {
    if (still) return
    val tr = rememberInfiniteTransition(label = "bubbles")
    val t by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "bt")
    Canvas(modifier) {
        for (i in 0 until n) {
            val speed = 1f + (i % 2)
            val p = (t * speed + i * 0.137f) % 1f
            val x = size.width * (0.08f + ((i * 37) % 84) / 100f) + p * 10.dp.toPx()
            val y = size.height * (1.05f - p * 1.1f)
            val r = (2f + (i * 3) % 4).dp.toPx()
            val fade = if (p < 0.15f) p / 0.15f else 1f - (p - 0.15f) / 0.85f
            drawCircle(color.copy(alpha = color.alpha * fade), r, Offset(x, y))
        }
    }
}

/** Two rings spreading out, like a drop falling into water. */
@Composable
fun Ripples(modifier: Modifier, color: Color = Color(0x59FFFFFF)) {
    if (still) return
    val tr = rememberInfiniteTransition(label = "ripples")
    val t by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "rt")
    Canvas(modifier) {
        for (k in 0..1) {
            val p = (t + k * 0.5f) % 1f
            drawCircle(color.copy(alpha = color.alpha * (1f - p)), size.minDimension / 2f * (0.5f + 1.7f * p), center, style = Stroke(2.dp.toPx()))
        }
    }
}

/** Floats gently up and down. */
fun Modifier.bob(px: Dp = 8.dp, ms: Int = 4000): Modifier = composed {
    if (still) return@composed this
    val tr = rememberInfiniteTransition(label = "bob")
    val v by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(ms / 2, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bv")
    graphicsLayer { translationY = -v * px.toPx() }
}

/** A photo drifting very slowly closer, then back. */
fun Modifier.drift(): Modifier = composed {
    if (still) return@composed this
    val tr = rememberInfiniteTransition(label = "drift")
    val v by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(16000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "dv")
    graphicsLayer { val s = 1f + 0.09f * v; scaleX = s; scaleY = s; translationX = -0.015f * size.width * v; translationY = -0.01f * size.height * v }
}

/** One streak of light across the card when it first appears. */
fun Modifier.sheen(delayMs: Long = 500): Modifier = composed {
    if (!Motion.on) return@composed this
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(delayMs); p.animateTo(1f, tween(1400, easing = FastOutSlowInEasing)) }
    drawWithContent {
        drawContent()
        val v = p.value
        if (v > 0f && v < 1f) {
            val w = size.width; val x = -0.6f * w + v * 2.2f * w
            drawRect(Brush.linearGradient(listOf(Color(0x00FFFFFF), Color(0x5CFFFFFF), Color(0x00FFFFFF)), start = Offset(x - w * 0.25f, 0f), end = Offset(x + w * 0.1f, size.height)))
        }
    }
}

/** A small push to the right every few seconds: "tap here". */
fun Modifier.nudge(): Modifier = composed {
    if (still) return@composed this
    val tr = rememberInfiniteTransition(label = "nudge")
    val v by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "nv")
    graphicsLayer { translationX = when { v < 0.7f -> 0f; v < 0.8f -> (v - 0.7f) / 0.1f; v < 0.9f -> 1f - (v - 0.8f) / 0.1f; else -> 0f } * 4.dp.toPx() }
}

/** A gentle heartbeat. */
fun Modifier.beat(): Modifier = composed {
    if (still) return@composed this
    val tr = rememberInfiniteTransition(label = "beat")
    val v by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "bv")
    graphicsLayer { val s = when { v < 0.2f -> 1f + 0.18f * v / 0.2f; v < 0.3f -> 1.18f - 0.22f * (v - 0.2f) / 0.1f; v < 0.4f -> 0.96f + 0.04f * (v - 0.3f) / 0.1f; else -> 1f }; scaleX = s; scaleY = s }
}

private fun wiggle(v: Float): Float = when {
    v < 0.15f -> -28f * v / 0.15f
    v < 0.30f -> -28f + 46f * (v - 0.15f) / 0.15f
    v < 0.45f -> 18f - 28f * (v - 0.30f) / 0.15f
    v < 0.60f -> -10f + 10f * (v - 0.45f) / 0.15f
    else -> 0f
}

/** A spanner at work: turns back and forth, then rests. */
fun Modifier.atWork(ms: Int = 1800): Modifier = composed {
    if (still) return@composed this
    val tr = rememberInfiniteTransition(label = "work")
    val v by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(ms, easing = LinearEasing)), label = "wv")
    graphicsLayer { rotationZ = wiggle(v) }
}

/** A camera flash now and then. */
fun Modifier.flash(): Modifier = composed {
    if (still) return@composed this
    val tr = rememberInfiniteTransition(label = "flash")
    val v by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "fv")
    drawWithContent {
        drawContent()
        val a = when { v < 0.92f -> 0f; v < 0.95f -> (v - 0.92f) / 0.03f; else -> 1f - (v - 0.95f) / 0.05f }
        if (a > 0f) drawCircle(Color.White.copy(alpha = 0.75f * a), size.minDimension * 0.7f, center)
    }
}

/** The project's state as a small round mark that moves: the tick draws itself and a ring pulses out (working),
 *  the wrench turns (being repaired), the "!" blinks (waiting for a repair), bricks stack up (being built),
 *  the clock's hands turn (planned). */
@Composable
fun StateMark(k: String, color: Color, size: Dp = 26.dp) {
    val tr = rememberInfiniteTransition(label = "mark")
    val ms = when (k) { "repairing" -> 1800; "notworking" -> 1600; "building" -> 3200; "planned" -> 4000; else -> 2600 }
    val loop by if (still) remember { mutableFloatStateOf(0.5f) } else tr.animateFloat(0f, 1f, infiniteRepeatable(tween(ms, easing = LinearEasing)), label = "ml")
    val drawn = remember { Animatable(if (Motion.on) 0f else 1f) }
    LaunchedEffect(Unit) { if (drawn.value < 1f) drawn.animateTo(1f, tween(700, delayMillis = 900, easing = FastOutSlowInEasing)) }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (k == "working" && !still) Canvas(Modifier.size(size)) {
            drawCircle(color.copy(alpha = 0.6f * (1f - loop)), this.size.minDimension / 2f * (1f + 0.6f * loop), style = Stroke(2.dp.toPx()))
        }
        Canvas(Modifier.size(size)) {
            val s = this.size.minDimension
            drawCircle(color)
            val sw = s * 0.09f
            when (k) {
                "repairing" -> Unit
                "notworking" -> {
                    val a = if (loop < 0.5f) 1f else 0.35f
                    drawLine(Color.White.copy(alpha = a), Offset(s * 0.5f, s * 0.27f), Offset(s * 0.5f, s * 0.56f), sw * 1.2f, StrokeCap.Round)
                    drawCircle(Color.White.copy(alpha = a), s * 0.06f, Offset(s * 0.5f, s * 0.72f))
                }
                "building" -> {
                    val f = s * 0.6f / 24f; val o = s * 0.2f
                    listOf(floatArrayOf(3f, 15f), floatArrayOf(13f, 15f), floatArrayOf(8f, 9f), floatArrayOf(8f, 3f)).forEachIndexed { j, r ->
                        val p = (loop - j * 0.06f).coerceIn(0f, 1f)
                        val (dy, al) = when { p < 0.18f -> -(1f - p / 0.18f) * s * 0.3f to p / 0.18f; p < 0.85f -> 0f to 1f; else -> 0f to 1f - (p - 0.85f) / 0.15f }
                        drawRoundRect(Color.White.copy(alpha = al), Offset(o + r[0] * f, o + r[1] * f + dy), Size(8f * f, 5f * f), androidx.compose.ui.geometry.CornerRadius(f, f))
                    }
                }
                "planned" -> {
                    val c = center
                    drawCircle(Color.White, s * 0.3f, c, style = Stroke(sw))
                    val m = Math.toRadians((loop * 360f - 90f).toDouble()); val h = Math.toRadians((loop * 30f - 90f).toDouble())
                    drawLine(Color.White, c, Offset(c.x + cos(m).toFloat() * s * 0.2f, c.y + sin(m).toFloat() * s * 0.2f), sw, StrokeCap.Round)
                    drawLine(Color.White, c, Offset(c.x + cos(h).toFloat() * s * 0.13f, c.y + sin(h).toFloat() * s * 0.13f), sw, StrokeCap.Round)
                }
                else -> {
                    val a = Offset(s * 0.3f, s * 0.53f); val b = Offset(s * 0.45f, s * 0.67f); val e = Offset(s * 0.72f, s * 0.38f)
                    val l1 = (b - a).getDistance(); val l2 = (e - b).getDistance(); val d = drawn.value * (l1 + l2)
                    if (d > 0f) drawLine(Color.White, a, if (d < l1) a + (b - a) * (d / l1) else b, sw, StrokeCap.Round)
                    if (d > l1) drawLine(Color.White, b, b + (e - b) * ((d - l1) / l2).coerceAtMost(1f), sw, StrokeCap.Round)
                }
            }
        }
        if (k == "repairing") Icon(Ic.get("wrench"), null, tint = Color.White, modifier = Modifier.size(size * 0.58f).atWork())
    }
}

/** A coloured dot that breathes out a soft ring (for "Working well" and "Being repaired"). */
@Composable
fun BreathDot(color: Color, live: Boolean, size: Dp = 8.dp) {
    val tr = rememberInfiniteTransition(label = "breath")
    val v by if (still || !live) remember { mutableFloatStateOf(1f) } else tr.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "brv")
    Canvas(Modifier.size(size)) {
        if (v < 0.8f) drawCircle(color.copy(alpha = 0.55f * (1f - v / 0.8f)), this.size.minDimension / 2f + 7.dp.toPx() * v / 0.8f)
        drawCircle(color)
    }
}

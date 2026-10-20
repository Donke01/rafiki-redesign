package org.rafikioutreach.pin.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Cards rise into place with a soft spring the first time they appear.
 *  [index] staggers them; with [seen] and [key] a card that has already appeared stays put
 *  (so live updates and scrolling back never replay the motion). */
fun Modifier.rise(index: Int = 0, seen: MutableSet<Any>? = null, key: Any? = null, step: Long = 45): Modifier = composed {
    val already = !Motion.on || (key != null && seen?.contains(key) == true)
    val p = remember { Animatable(if (already) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (p.value < 1f) { delay(index.coerceIn(0, 8) * step); p.animateTo(1f, spring(dampingRatio = 0.74f, stiffness = 240f)) }
        if (key != null) seen?.add(key)
    }
    graphicsLayer {
        val v = p.value
        alpha = v.coerceIn(0f, 1f)
        translationY = (1f - v) * 28.dp.toPx()
        val s = 0.96f + 0.04f * v; scaleX = s; scaleY = s
    }
}

/** Squishes a little under the finger and springs back. */
fun Modifier.press(src: MutableInteractionSource): Modifier = composed {
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.965f else 1f, spring(dampingRatio = 0.5f, stiffness = 700f), label = "press")
    graphicsLayer { scaleX = s; scaleY = s }
}

/** Light on the deep blue cards: a soft glow drifting slowly, and a sheen sweeping across now and then. */
fun Modifier.ambient(): Modifier = composed {
    if (!Motion.on || !Motion.loops) return@composed this
    val tr = rememberInfiniteTransition(label = "amb")
    val drift by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "drift")
    val sweep by tr.animateFloat(-0.4f, 1.8f, infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "sweep")
    drawWithContent {
        drawContent()
        val w = size.width; val h = size.height
        val c = Offset(w * (0.82f - 0.25f * drift), h * (0.1f + 0.35f * drift))
        drawCircle(Brush.radialGradient(listOf(Color(0x3363C4FF), Color(0x0063C4FF)), center = c, radius = w * 0.55f), w * 0.55f, c)
        if (sweep in 0f..1.2f) {
            val x = w * sweep
            drawRect(Brush.linearGradient(listOf(Color(0x00FFFFFF), Color(0x1FFFFFFF), Color(0x00FFFFFF)), start = Offset(x - w * 0.25f, 0f), end = Offset(x + w * 0.05f, h)))
        }
    }
}

/** A number or fraction that fills up from zero the first time it is shown. */
@androidx.compose.runtime.Composable
fun rememberFill(target: Float, ms: Int = 1100): Float {
    val a = remember { Animatable(if (Motion.on) 0f else target) }
    LaunchedEffect(target) { if (Motion.on) a.animateTo(target, tween(ms, delayMillis = 150, easing = FastOutSlowInEasing)) else a.snapTo(target) }
    return a.value
}

/** A pop for dots and badges: from nothing to a little too big, then settle. */
fun Modifier.pop(delayMs: Long = 0): Modifier = composed {
    val s = remember { Animatable(if (Motion.on) 0f else 1f) }
    LaunchedEffect(Unit) { if (s.value < 1f) { delay(delayMs); s.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 420f)) } }
    graphicsLayer { scaleX = s.value; scaleY = s.value }
}

/** A stamp landing: drops in tilted and settles at its angle. */
fun Modifier.stampIn(index: Int, got: Boolean): Modifier = composed {
    val p = remember { Animatable(if (Motion.on && got) 0f else 1f) }
    LaunchedEffect(Unit) { if (p.value < 1f) { delay(250L + index * 110L); p.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 260f)) } }
    graphicsLayer {
        val v = p.value
        alpha = v.coerceIn(0f, 1f); rotationZ = (1f - v) * -14f
        val s = 0.6f + 0.4f * v; scaleX = s; scaleY = s
        translationY = (1f - v) * -18.dp.toPx()
    }
}

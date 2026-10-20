package org.rafikioutreach.pin.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The RAFIKI drop: a glassy water drop with eyes, in five colours and a few moods. */
enum class DropC(val hi: Color, val mid: Color, val lo: Color) {
    Blue(Color(0xFFBFE8FF), Color(0xFF3EA7EE), Color(0xFF0757A0)),
    Green(Color(0xFFC8F5DA), Color(0xFF2FB872), Color(0xFF0B6B3D)),
    Coral(Color(0xFFFFD6C9), Color(0xFFF26A4F), Color(0xFFA3261A)),
    Purple(Color(0xFFE6DCFF), Color(0xFF8C6CF2), Color(0xFF4527B0)),
    Amber(Color(0xFFFFEDB8), Color(0xFFF2B13A), Color(0xFFA86200)),
    Grey(Color(0xFFF1F3F5), Color(0xFFC9CFD4), Color(0xFF8C969E)),
}
enum class Mood { Plain, Smile, Wink, O }

private val dropPath: Path by lazy { PathParser().parsePathString("M12 2.5s7 7.4 7 12.6a7 7 0 0 1-14 0c0-5.2 7-12.6 7-12.6z").toPath() }
private val shinePath: Path by lazy { PathParser().parsePathString("M8.2 11.4c.5-1.7 1.6-3.4 2.7-4.8").toPath() }

/** Moving drops and pulses; tests switch it off. */
object Motion { var on = true }

/** Draws the drop in a 24×27 box; it floats and blinks unless [still]. */
@Composable
fun Drop(size: Dp, color: DropC = DropC.Blue, mood: Mood = Mood.Plain, modifier: Modifier = Modifier, still0: Boolean = false) {
    val still = still0 || !Motion.on
    val tr = rememberInfiniteTransition(label = "drop")
    val float by if (still) remember { mutableFloatStateOf(0f) } else tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "f")
    val blink by if (still) remember { mutableFloatStateOf(1f) } else tr.animateFloat(1f, 1f, infiniteRepeatable(keyframes { durationMillis = 5000; 1f at 2300; 0.1f at 2400; 1f at 2500 }), label = "b")
    Canvas(modifier.size(size, size * 27f / 24f)) {
        val k = this.size.width / 24f
        scale(k, k, pivot = Offset.Zero) {
            // shadow
            val sw = 5.2f * (1f - .18f * float)
            drawOval(Color(0xFF0B3A5E).copy(alpha = .22f - .08f * float), Offset(12f - sw, 24.4f), Size(sw * 2, 2f))
            translate(0f, -2.2f * float) {
                drawPath(dropPath, Brush.radialGradient(listOf(color.hi, color.mid, color.lo), center = Offset(9.1f, 8.6f), radius = 15f))
                drawPath(dropPath, Color.White.copy(alpha = .55f), style = Stroke(.5f))
                drawPath(shinePath, Brush.verticalGradient(listOf(Color.White.copy(alpha = .95f), Color.White.copy(alpha = 0f)), 6.6f, 11.4f), style = Stroke(1.3f, cap = StrokeCap.Round))
                val ink = Color(0xFF04263F)
                fun eye(cx: Float) {
                    drawOval(Color.White, Offset(cx - 1.15f, 15.2f - 1.5f * blink), Size(2.3f, 3f * blink))
                    drawCircle(ink, .62f * blink.coerceAtLeast(.3f), Offset(cx + .25f, 15.5f))
                }
                if (mood == Mood.Wink) {
                    val p = Path().apply { moveTo(9f, 15.4f); quadraticTo(10f, 14.6f, 11f, 15.4f) }
                    drawPath(p, ink, style = Stroke(.7f, cap = StrokeCap.Round))
                } else eye(10f)
                eye(14f)
                when (mood) {
                    Mood.Smile, Mood.Wink -> drawPath(Path().apply { moveTo(10.4f, 18.1f); quadraticTo(12f, 19.3f, 13.6f, 18.1f) }, ink, style = Stroke(.7f, cap = StrokeCap.Round))
                    Mood.O -> drawOval(ink, Offset(11.3f, 17.55f), Size(1.4f, 1.7f))
                    else -> {}
                }
            }
        }
    }
}

/** The drop with a short line under it, for screens with nothing to show yet. */
@Composable
fun EmptyDrop(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Drop(64.dp, mood = Mood.Smile)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
    }
}

package org.rafikioutreach.pin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Chat face lift, direction B "RAFIKI Deep" (web v75; design/chat-2026-10b/board-b.webp; the web's <style id="cw3-css">):
// a night-blue header with a soft glow, warm sand behind the chats, chats as cards, my messages in deep blue, a dark
// long-press sheet and a night header on chat info. As on the web, the night parts re-point the app's own colours to
// light-on-dark values ([Night]), so every existing control keeps working. Bright sun keeps its plain look.

/** True inside a night part (the header, the long-press sheet, my deep-blue bubbles). */
val LocalNight = staticCompositionLocalOf { false }
/** The colours outside the night part, for pop-ups opened from inside it (a menu reads best light). */
val LocalDay = staticCompositionLocalOf<Palette?> { null }

internal object Deep {
    /** The face lift is on (every look but Bright sun). */
    @Composable fun on() = C !== Sun && LocalDay.current.let { it == null || it !== Sun }
    @Composable private fun dk() = (LocalDay.current ?: C).dark
    @Composable fun n1() = if (dk()) Color(0xFF03182A) else Color(0xFF04263F)
    @Composable fun n2() = if (dk()) Color(0xFF052C48) else Color(0xFF06375A)
    @Composable fun g1() = if (dk()) Color(0x422EB4AA) else Color(0x6B2EB4AA)
    @Composable fun g2() = if (dk()) Color(0x570A78C2) else Color(0x8C0A78C2)
    /** Behind the chat list. */
    @Composable fun sand() = if (dk()) Color(0xFF0D1318) else Color(0xFFF6F2EC)
    /** Behind the messages when no wallpaper is chosen, and the composer's field. */
    @Composable fun room() = if (dk()) Color(0xFF0F151A) else Color(0xFFF3ECE2)
    @Composable fun acc() = if (dk()) Color(0xFF2BB98C) else Color(0xFF0B8F6A)
    @Composable fun acc2() = if (dk()) Color(0xFF23A57D) else Color(0xFF12A37A)
    val mint = Color(0xFF7FE3B0)
    val ice = Color(0xFF9FD3F7)
    @Composable fun day() = if (dk()) Color(0xFF1C262E) else Color(0xFFE8DCCB)
    @Composable fun dayText() = if (dk()) Color(0xFFB9C6D0) else Color(0xFF6A5A44)
    /** My messages in the default colour. */
    @Composable fun me() = if (dk()) Color(0xFF0E4A75) else Color(0xFF06375A)
    /** The green of the new-chat and send buttons. */
    @Composable fun go(): Brush = Brush.linearGradient(listOf(acc2(), acc()))

    /** The night background with its glow (teal top right, blue top left). */
    @Composable
    fun Modifier.nightGlow(): Modifier {
        val n = n1(); val a = g1(); val b = g2()
        return drawBehind {
            drawRect(n)
            drawRect(Brush.radialGradient(listOf(a, Color.Transparent), Offset(size.width * 0.88f, -size.height * 0.1f), 380.dp.toPx() * 0.7f))
            drawRect(Brush.radialGradient(listOf(b, Color.Transparent), Offset(-size.width * 0.05f, -size.height * 0.2f), 320.dp.toPx() * 0.75f))
        }
    }
}

/** The night palette: white words and light controls on night blue (the web's re-pointed tokens). */
internal fun nightOf(card: Color) = Dark.copy(
    ink = Color.White, text = Color(0xFFE6EEF4), muted = Color(0xFFA9C3D6), tint = Color(0x1FFFFFFF), line = Color(0x24FFFFFF), edge = Color(0x40FFFFFF),
    blue = Deep.ice, blue2 = Deep.ice, deep = Deep.ice, bg = Color(0x14FFFFFF), card = card,
)

/** A night part: [content] drawn with the night palette (nothing changes in Bright sun). */
@Composable
internal fun Night(on: Boolean = true, content: @Composable () -> Unit) {
    if (!on || !Deep.on() || LocalNight.current) { content(); return }
    CompositionLocalProvider(LocalDay provides C, LocalPalette provides nightOf(Deep.n1()), LocalNight provides true, content = content)
}

/** Back to the day colours inside a night part (pop-up menus). */
@Composable
internal fun Day(content: @Composable () -> Unit) {
    val d = LocalDay.current
    if (d == null) { content(); return }
    CompositionLocalProvider(LocalPalette provides d, LocalNight provides false, LocalDay provides null, content = content)
}

/** A night block with the glow, rounded at the bottom (the chat list's top, a chat's header and strip). */
@Composable
internal fun NightBlock(modifier: Modifier = Modifier, round: Dp = 26.dp, content: @Composable ColumnScope.() -> Unit) {
    if (!Deep.on()) { Column(modifier.fillMaxWidth(), content = content); return }
    val shape = RoundedCornerShape(bottomStart = round, bottomEnd = round)
    Night {
        with(Deep) { Column(modifier.fillMaxWidth().shadow(if (round > 0.dp) 8.dp else 0.dp, shape, ambientColor = Color(0x2E04263F), spotColor = Color(0x2E04263F)).clip(shape).nightGlow().testTag("night"), content = content) }
    }
}

/** The default chat background (no wallpaper chosen): warm sand with soft contour lines; dark: the app's night with faint lines. */
@Composable
internal fun SandWall(modifier: Modifier) {
    val bg = Deep.room()
    val ink = if (C.dark) Color(0x0DFFFFFF) else Color(0xFFE4D8C6)
    Canvas(modifier.background(bg).testTag("wall_warm")) {
        val tile = 160.dp.toPx(); val sw = 1.2.dp.toPx()
        var y0 = 0f
        while (y0 < size.height + tile) {
            listOf(40f, 90f, 140f).forEach { dy ->
                val y = y0 + dy / 160f * tile
                val p = Path(); var x = 0f
                p.moveTo(0f, y)
                while (x < size.width + tile) {
                    // one wave a tile: down then up (the web's "c40-20 80 20 160 0")
                    p.cubicTo(x + tile * 0.25f, y - tile * 0.125f, x + tile * 0.5f, y + tile * 0.125f, x + tile, y)
                    x += tile
                }
                drawPath(p, ink, style = Stroke(sw))
            }
            y0 += tile
        }
    }
}

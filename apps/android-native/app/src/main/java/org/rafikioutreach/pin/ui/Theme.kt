package org.rafikioutreach.pin.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.rafikioutreach.pin.R

/** RAFIKI colours: the same as the web app. */
@Immutable
data class Palette(
    val blue: Color, val blue2: Color, val deep: Color, val ink: Color, val tint: Color, val bg: Color, val card: Color,
    val text: Color, val muted: Color, val line: Color, val edge: Color,
    val ok: Color, val okBg: Color, val warn: Color, val warnBg: Color, val bad: Color, val badBg: Color, val off: Color,
    val dark: Boolean,
)

val Light = Palette(
    blue = Color(0xFF0A78C2), blue2 = Color(0xFF076DB1), deep = Color(0xFF054F81), ink = Color(0xFF04263F), tint = Color(0xFFE3F1FC),
    bg = Color(0xFFEEF0F2), card = Color.White, text = Color(0xFF141618), muted = Color(0xFF5A6066), line = Color(0xFFDCE1E5), edge = Color(0xFFC9D1D8),
    ok = Color(0xFF1C7C4A), okBg = Color(0xFFE2F4EA), warn = Color(0xFF8A5200), warnBg = Color(0xFFFDF0DC), bad = Color(0xFFB3261E), badBg = Color(0xFFFBE7E5), off = Color(0xFFAFC4D3), dark = false,
)
val Dark = Palette(
    blue = Color(0xFF2F9BE6), blue2 = Color(0xFF4AAAEE), deep = Color(0xFF9FD3F7), ink = Color(0xFFE6EEF4), tint = Color(0xFF12324A),
    bg = Color(0xFF0D1318), card = Color(0xFF161E25), text = Color(0xFFE8EDF1), muted = Color(0xFF9CA8B2), line = Color(0xFF26323C), edge = Color(0xFF3A4955),
    ok = Color(0xFF5FD08F), okBg = Color(0xFF123222), warn = Color(0xFFF2B35B), warnBg = Color(0xFF3A2A10), bad = Color(0xFFFF8A80), badBg = Color(0xFF3D1715), off = Color(0xFF33485A), dark = true,
)

val LocalPalette = staticCompositionLocalOf { Light }
val C: Palette @Composable get() = LocalPalette.current

val Amber = Color(0xFFE39A1E)
val Red = Color(0xFFD23C32)
val Green = Color(0xFF1C9C5A)

/** The deep-blue hero gradient used on the top cards. */
val DeepBrush = Brush.linearGradient(listOf(Color(0xFF0A78C2), Color(0xFF054F81), Color(0xFF04263F)))
val BlueBrush = Brush.linearGradient(listOf(Color(0xFF1A8BD6), Color(0xFF0A78C2), Color(0xFF076DB1)))
val OkBrush = Brush.linearGradient(listOf(Color(0xFF25A262), Color(0xFF1C7C4A)))

@OptIn(ExperimentalTextApi::class)
private fun mont(w: Int) = Font(R.font.montserrat, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

@OptIn(ExperimentalTextApi::class)
val Montserrat = FontFamily(mont(400), mont(500), mont(600), mont(700), mont(800), mont(900))

private val type = Typography(
    headlineLarge = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.Bold, fontSize = 16.sp),
    titleSmall = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.Bold, fontSize = 14.sp),
    bodyLarge = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.Bold, fontSize = 15.sp),
    labelMedium = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.Bold, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.Bold, fontSize = 11.sp),
)

@Composable
fun RafikiTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (dark) Dark else Light
    val scheme = if (dark) darkColorScheme(primary = p.blue, background = p.bg, surface = p.card, onSurface = p.text, onBackground = p.text)
    else lightColorScheme(primary = p.blue, background = p.bg, surface = p.card, onSurface = p.text, onBackground = p.text)
    androidx.compose.runtime.CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}

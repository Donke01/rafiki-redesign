package org.rafikioutreach.pin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import org.rafikioutreach.pin.t

// Small pieces for the map, Projects, Route and Plan a week (polish round): text that shrinks rather than breaking a
// word, the close X for sheets, and the Undo bar for things that can be put back.

/** The largest font size (from [style]'s own, down to [minSp]) at which every one of [texts] fits [widthPx] in
 *  [maxLines] lines with no word broken in the middle. One size for a row of labels, so they match. */
@Composable
fun rememberWordFitSize(texts: List<String>, style: TextStyle, widthPx: Int, maxLines: Int, minSp: Float = 8f): Float {
    val measurer = rememberTextMeasurer()
    val top = if (style.fontSize.isSpecified) style.fontSize.value else 14f
    return remember(texts, style, widthPx, maxLines) {
        if (widthPx <= 0) return@remember top
        var s = top
        while (s > minSp) {
            val st = scaled(style, s, top)
            val ok = texts.all { txt ->
                val widest = txt.split(' ', ' ').filter { it.isNotEmpty() }.maxOfOrNull { w -> measurer.measure(w, st, softWrap = false, maxLines = 1).size.width } ?: 0
                widest <= widthPx && !measurer.measure(txt, st, maxLines = maxLines, constraints = Constraints(maxWidth = widthPx)).hasVisualOverflow
            }
            if (ok) break
            s -= 0.5f
        }
        s.coerceAtLeast(minSp)
    }
}

private fun scaled(style: TextStyle, s: Float, top: Float): TextStyle =
    style.copy(fontSize = s.sp, lineHeight = if (style.lineHeight.isSpecified) (style.lineHeight.value * s / top).sp else style.lineHeight)

/** A label that wraps only between words and shrinks to fit [maxLines] lines; never "…", never half a word on a line. */
@Composable
fun WordFitText(text: String, style: TextStyle, color: Color, maxLines: Int, modifier: Modifier = Modifier, textAlign: TextAlign? = null, minSp: Float = 8f, tag: String = "") {
    BoxWithConstraints(modifier) {
        val top = if (style.fontSize.isSpecified) style.fontSize.value else 14f
        val s = rememberWordFitSize(listOf(text), style, constraints.maxWidth, maxLines, minSp)
        Text(text, style = scaled(style, s, top), color = color, maxLines = maxLines + 1, textAlign = textAlign, modifier = if (tag.isEmpty()) Modifier else Modifier.testTag(tag))
    }
}

/** The first of [variants] that fits on one line (a long line drops its least useful part first, such as the status);
 *  if none does, the last one, on up to two lines. Never cut short with "…". */
@Composable
fun FirstFitText(variants: List<String>, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer()
        val w = constraints.maxWidth
        val pick = remember(variants, style, w) {
            variants.firstOrNull { v -> !measurer.measure(v, style, maxLines = 1, softWrap = false).let { it.size.width > w } } ?: variants.last()
        }
        Text(pick, style = style, color = color, maxLines = 2)
    }
}

/** The round close X at the top right of a sheet. */
@Composable
fun SheetCloseX(tag: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(C.bg).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = t("Close", "Funga") }.testTag(tag), contentAlignment = Alignment.Center) {
        Icon(Ic.get("x"), null, tint = C.ink, modifier = Modifier.size(20.dp))
    }
}

/** What the Undo bar shows: a short message and how to put it back. Shown for 6 seconds. */
class UndoState {
    var msg by mutableStateOf<String?>(null); private set
    internal var undo: (() -> Unit)? = null
    internal var shown by mutableIntStateOf(0)
    fun show(message: String, putBack: () -> Unit) { msg = message; undo = putBack; shown++ }
    fun clear() { msg = null; undo = null }
}

/** "Route cleared · Undo": a dark bar near the bottom of the screen, above the tabs, for 6 seconds. It floats over the
 *  page wherever it is used, so a tap elsewhere still works. */
@Composable
fun UndoSnack(st: UndoState, tag: String) {
    val msg = st.msg ?: return
    LaunchedEffect(st.shown) { kotlinx.coroutines.delay(6000); st.clear() }
    val gap = with(LocalDensity.current) { 108.dp.roundToPx() }
    val place = remember(gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
                IntOffset((windowSize.width - popupContentSize.width) / 2, windowSize.height - popupContentSize.height - gap)
        }
    }
    Popup(popupPositionProvider = place, properties = PopupProperties(focusable = false)) {
        val shape = RoundedCornerShape(16.dp)
        val h = rememberHaptics()
        Row(
            Modifier.padding(horizontal = 16.dp).widthIn(max = 520.dp).fillMaxWidth().shadow(10.dp, shape).clip(shape).background(Color(0xFF0C1924)).border(1.dp, Color(0x33FFFFFF), shape)
                .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp).testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(msg, style = MaterialTheme.typography.bodyMedium, color = Color.White, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            Spacer(Modifier.width(8.dp))
            Text(t("Undo", "Tendua"), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold), color = Color(0xFF7CC4F5), maxLines = 1, softWrap = false,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { h.tap(); st.undo?.invoke(); st.clear() }.heightIn(min = 44.dp)
                    .padding(horizontal = 14.dp, vertical = 12.dp).testTag(tag + "_undo"))
        }
    }
}


/** Slides a sheet away, then closes it (the close X). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
internal fun sheetHide(scope: kotlinx.coroutines.CoroutineScope, state: androidx.compose.material3.SheetState, then: () -> Unit) {
    scope.launch { state.hide() }.invokeOnCompletion { then() }
}

/** "Allow location" as a real button: it asks for the permission; if it was refused before (Android no longer
 *  shows the question) it opens the app's settings, and with the permission already given, the phone's location settings. */
@Composable
fun AllowLocationBtn(m: org.rafikioutreach.pin.AppModel, modifier: Modifier, tag: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val sp = remember { ctx.getSharedPreferences("map_style", android.content.Context.MODE_PRIVATE) }
    val perms = arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { r ->
        sp.edit().putBoolean("loc_asked", true).apply()
        if (r.values.any { it }) m.locator.start()
    }
    Btn(t("Allow location", "Ruhusu mahali"), {
        var c: android.content.Context? = ctx
        while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
        val act = c as? android.app.Activity
        val granted = perms.any { ctx.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        val refused = !granted && sp.getBoolean("loc_asked", false) && act != null && perms.none { act.shouldShowRequestPermissionRationale(it) }
        when {
            granted -> { m.locator.start(); runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } }
            refused -> runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", ctx.packageName, null))) }
            else -> { sp.edit().putBoolean("loc_asked", true).apply(); runCatching { ask.launch(perms) } }
        }
    }, modifier.testTag(tag), icon = "pin", small = true)
}

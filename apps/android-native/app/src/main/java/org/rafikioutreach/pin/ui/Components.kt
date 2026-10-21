package org.rafikioutreach.pin.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun Btn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: String? = null, enabled: Boolean = true, light: Boolean = false, ok: Boolean = false, small: Boolean = false, saved: String? = null, color: Color? = null) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    if (saved != null) {
        // turned green for a moment after saving
        Row(modifier.clip(RoundedCornerShape(999.dp)).background(OkBrush).heightIn(min = if (small) 44.dp else 52.dp).padding(horizontal = if (small) 16.dp else 22.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.get("check"), null, tint = Color.White, modifier = Modifier.size(if (small) 18.dp else 20.dp)); Spacer(Modifier.width(8.dp))
            Text(saved, color = Color.White, style = if (small) MaterialTheme.typography.labelMedium.copy(fontSize = 14.sp) else MaterialTheme.typography.labelLarge)
        }
        return
    }
    val bg: Brush = when { !enabled -> SolidColor(C.off); light -> SolidColor(if (C.glassy) C.glass2 else C.card); ok -> OkBrush; color != null -> SolidColor(color); else -> C.primary }
    val fg = if (light) C.ink else Color.White
    Row(
        modifier
            .scale(if (pressed) 0.97f else 1f)
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .then(if (light) Modifier.border(1.dp, if (C.glassy) C.glassEdge else C.edge, RoundedCornerShape(999.dp)) else Modifier)
            .clickable(enabled = enabled, interactionSource = src, indication = androidx.compose.material3.ripple(), role = Role.Button, onClick = onClick)
            .heightIn(min = if (small) 44.dp else 52.dp)
            .padding(horizontal = if (small) 16.dp else 22.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(Ic.get(icon), null, tint = fg, modifier = Modifier.size(if (small) 18.dp else 20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, color = fg, style = if (small) MaterialTheme.typography.labelMedium.copy(fontSize = 14.sp) else MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, pad: Dp = 18.dp, brush: Brush? = null, border: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    val glass = brush == null && C.glassy
    Column(
        modifier.rise().fillMaxWidth()
            .then(if (glass) Modifier.shadow(6.dp, shape, ambientColor = Color(0x14144678), spotColor = Color(0x1A144678)) else Modifier)
            .clip(shape)
            .then(if (brush != null) Modifier.background(brush).ambient() else Modifier.background(if (glass) C.glass2 else C.card))
            .then(if (brush == null) Modifier.border(1.dp, border ?: if (glass) C.glassEdge else C.line, shape) else Modifier)
            .padding(pad),
        content = content,
    )
}

/** A square-ish button with a drawn icon over a short label (filters, sections). */
@Composable
fun Tile(label: String, icon: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, badge: Int = 0) {
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(if (selected) C.primary else SolidColor(if (C.glassy) C.glass2 else C.card))
                .then(if (!selected) Modifier.border(1.dp, if (C.glassy) C.glassEdge else C.line, RoundedCornerShape(16.dp)) else Modifier)
                .clickable(role = Role.Button, onClick = onClick)
                .heightIn(min = 66.dp).padding(horizontal = 2.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Icon(Ic.get(icon), null, tint = if (selected) Color.White else C.blue, modifier = Modifier.size(22.dp))
            Spacer(Modifier.size(4.dp))
            Text(label, color = if (selected) Color.White else C.ink, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, lineHeight = 13.sp)
        }
        if (badge > 0) Text(
            "$badge", color = if (selected) Red else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopCenter).padding(start = 30.dp, top = 5.dp).clip(CircleShape).background(if (selected) Color.White else Red).padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
fun Tag(text: String, bg: Color, fg: Color) {
    Text(text, color = fg, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold), maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp))
}

@Composable
fun IconTile(icon: String, tint: Color, bg: Color, size: Dp = 42.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(bg), contentAlignment = Alignment.Center) {
        Icon(Ic.get(icon), null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, placeholder: String = "", number: Boolean = false, lines: Int = 1, secret: Boolean = false, readOnly: Boolean = false, phone: Boolean = false) {
    Column(modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp), color = C.ink, modifier = Modifier.padding(bottom = 6.dp, top = 12.dp))
        OutlinedTextField(
            value, onChange, Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp),
            placeholder = { Text(placeholder, color = C.muted) }, singleLine = lines == 1, minLines = lines,
            keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.NumberPassword) else if (phone) KeyboardOptions(keyboardType = KeyboardType.Phone) else if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
            visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None, readOnly = readOnly,
            shape = RoundedCornerShape(16.dp), textStyle = MaterialTheme.typography.bodyLarge,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.blue, unfocusedBorderColor = C.edge, focusedContainerColor = C.card, unfocusedContainerColor = C.card),
        )
    }
}

@Composable
fun Msg(text: String?, tone: String = "") {
    if (text.isNullOrBlank()) return
    Text(text, color = when (tone) { "bad" -> C.bad; "ok" -> C.ok; "warn" -> C.warn; else -> C.muted }, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 10.dp))
}

/** Pictures from the server, kept in memory so lists don't flicker. */
object Pics {
    private val mem = object : LruCache<String, Bitmap>(24 * 1024 * 1024) { override fun sizeOf(k: String, v: Bitmap) = v.byteCount }
    fun cached(url: String): Bitmap? = mem.get(url)
    suspend fun load(url: String): Bitmap? = mem.get(url) ?: withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 30000
            val b = c.inputStream.use { BitmapFactory.decodeStream(it) }
            c.disconnect(); b
        }.getOrNull()?.also { mem.put(url, it) }
    }
}

@Composable
fun NetImage(url: String?, modifier: Modifier, placeholder: @Composable () -> Unit) {
    var bmp by remember(url) { mutableStateOf(url?.let { Pics.cached(it) }) }
    LaunchedEffect(url) { if (url != null && bmp == null) bmp = Pics.load(url) }
    Box(modifier, contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()) else placeholder()
    }
}

/** A company's own icon (monday.com, WhatsApp, Google Maps), as on the web; [fallback] is drawn while it loads or with no signal. */
@Composable
fun Brand(domain: String, size: androidx.compose.ui.unit.Dp, fallback: String, tint: Color) {
    NetImage("https://www.google.com/s2/favicons?domain=$domain&sz=128", Modifier.size(size).clip(RoundedCornerShape(size * 0.22f))) { Icon(Ic.get(fallback), null, tint = tint, modifier = Modifier.size(size)) }
}
/** monday.com's own colours. */
val MondayBlue = Color(0xFF6161FF)
val WhatsAppGreen = Color(0xFF25D366)

/** The icon and colour for a kind of project: water, school, clinic, children's home, farm, building site. */
fun typeLook(p: org.rafikioutreach.pin.data.Project): Pair<String, String> {
    val t = p.type.lowercase()
    return when {
        "borehole" in t || "collection" in t -> "drop" to "blue"
        "under construction" in p.status.lowercase() -> "brick" to "amber"
        "school" in t -> "school" to "purple"
        "health" in t -> "cross" to "coral"
        "orphan" in t -> "house" to "teal"
        "farm" in t -> "leaf" to "green"
        else -> "house" to "slate"
    }
}

val Pad = PaddingValues(horizontal = 16.dp)
val Bordered = BorderStroke(1.dp, Color.Transparent)

/** English / Kiswahili switch. Changes every screen at once and is remembered. */
@Composable
fun LangSwitch(prefs: org.rafikioutreach.pin.data.Prefs, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(999.dp)).background(C.card).border(1.dp, C.line, RoundedCornerShape(999.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(false to "English", true to "Kiswahili").forEach { (sw, label) ->
            val on = org.rafikioutreach.pin.Lang.sw == sw
            Text(label, color = if (on) Color.White else C.ink, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) C.primary else SolidColor(Color.Transparent))
                    .clickable(role = Role.RadioButton) { org.rafikioutreach.pin.Lang.sw = sw; prefs.lang = if (sw) "sw" else "en" }
                    .heightIn(min = 44.dp).padding(horizontal = 18.dp, vertical = 13.dp))
        }
    }
}

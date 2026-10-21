package org.rafikioutreach.pin.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.ALERT_KINDS
import org.rafikioutreach.pin.data.Alert
import org.rafikioutreach.pin.data.alertNew
import org.rafikioutreach.pin.t
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Me & team › Notifications: a full screen from the bell, the same as the web app.
// All · Unread · For me · Repairs, grouped Today / This week / Earlier. Rows rise in one after another, unread
// dots pulse, and when there's nothing the RAFIKI drop sits among little notes that float in and settle.

private val NOTE_FILTERS = listOf(listOf("all", "All", "Zote"), listOf("unread", "Unread", "Hazijasomwa"), listOf("me", "For me", "Kwangu"), listOf("repairs", "Repairs", "Matengenezo"))
private val NOTE_HUES = listOf("blue", "green", "purple", "amber", "teal", "coral")

/** A colour for a person, worked out from their name (the same as the web app). */
fun nameHue(name: String): String {
    var h = 0L
    for (c in name.trim().lowercase()) h = (h * 31 + c.code) and 0xFFFFFFFFL
    return NOTE_HUES[(h % NOTE_HUES.size).toInt()]
}
fun initialsOf(name: String) = name.trim().split(Regex("\\s+")).mapNotNull { it.firstOrNull()?.uppercaseChar() }.take(2).joinToString("").ifBlank { "·" }

/** "just now", "5 min ago", "2 h ago", "yesterday", "3 days ago", then the date. */
fun agoText(iso: String, now: Instant = Instant.now()): String {
    val at = runCatching { Instant.parse(iso) }.getOrNull() ?: return ""
    val m = maxOf(0L, Duration.between(at, now).toMinutes()); val h = m / 60; val d = h / 24
    return when {
        m < 1 -> t("just now", "sasa hivi")
        m < 60 -> t("$m min ago", "dakika $m zilizopita")
        h < 24 -> t("$h h ago", "saa $h zilizopita")
        d < 7 -> if (d == 1L) t("yesterday", "jana") else t("$d days ago", "siku $d zilizopita")
        else -> DateTimeFormatter.ofPattern("d MMM", Locale.UK).withZone(ZoneId.systemDefault()).format(at)
    }
}

/** 0 today, 1 this week (the 6 days before), 2 earlier. */
fun noteGroup(iso: String, today: LocalDate = LocalDate.now()): Int {
    val d = runCatching { Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull() ?: return 2
    return when { !d.isBefore(today) -> 0; !d.isBefore(today.minusDays(6)) -> 1; else -> 2 }
}

/** Which notifications a filter shows: For me = visits assigned to me and repair jobs for me; Repairs = reported and fixed. */
fun notePass(a: Alert, f: String, new: Boolean) = when (f) {
    "unread" -> new
    "me" -> a.kind == "assign" || a.kind == "job"
    "repairs" -> a.kind == "repair" || a.kind == "fixed"
    else -> true
}

@Composable
fun NotificationsScreen(m: AppModel, open: (String) -> Unit, settings: () -> Unit, back: () -> Unit) {
    val tick by m.noteTick.collectAsState()
    val act by m.activity.collectAsState()
    val s by m.snap.collectAsState()
    val all = remember(tick, act, s) { m.alerts() }
    val seen = remember(tick) { m.prefs.noteSeen }
    val read = remember(tick) { m.prefs.noteRead }
    var f by rememberSaveable { mutableStateOf("all") }
    var menu by remember { mutableStateOf(false) }
    val h = rememberHaptics()
    val un = all.count { alertNew(it, seen, read) }
    val kinds = remember { ALERT_KINDS.associateBy { it[0] } }
    BackHandler(menu) { menu = false }
    Box(Modifier.fillMaxSize().testTag("notes_screen")) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 120.dp)) {
            // the title in the middle, "…" (Mark all as read, settings) on the right
            Box(Modifier.fillMaxWidth()) {
                Column(Modifier.align(Alignment.Center).padding(horizontal = 52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t("Notifications", "Arifa"), style = MaterialTheme.typography.headlineLarge, color = C.ink, textAlign = TextAlign.Center)
                    if (all.isNotEmpty()) Text(
                        when (un) { 0 -> t("All read ✓", "Zote zimesomwa ✓"); 1 -> t("1 unread", "1 haijasomwa"); else -> t("$un unread", "$un hazijasomwa") },
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp), color = C.muted, modifier = Modifier.testTag("notes_sub"),
                    )
                }
                MoreBtn(Modifier.align(Alignment.CenterEnd)) { h.tap(); menu = !menu }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 14.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NOTE_FILTERS.forEach { x -> FilterPill(t(x[1], x[2]), x[0] == f, if (x[0] == "unread") un else 0, "nf_" + x[0]) { if (f != x[0]) { h.tap(); f = x[0] } } }
            }
            val list = all.filter { notePass(it, f, alertNew(it, seen, read)) }
            // keyed on the filter, so switching replays the rise
            key(f) {
                if (list.isEmpty()) NotesEmpty(f)
                else {
                    var i = 0
                    val groups = list.groupBy { noteGroup(it.at) }
                    listOf(0, 1, 2).forEach { g ->
                        val items = groups[g] ?: return@forEach
                        Text(listOf(t("Today", "Leo"), t("This week", "Wiki hii"), t("Earlier", "Awali"))[g].uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp, fontWeight = FontWeight.ExtraBold), color = C.muted,
                            modifier = Modifier.rise(i).padding(start = 4.dp, top = 16.dp, bottom = 8.dp).testTag("note_group"))
                        items.forEach { a ->
                            NoteRow(a, kinds[a.kind] ?: ALERT_KINDS[0], alertNew(a, seen, read), Modifier.rise(i++, step = 50).padding(bottom = 8.dp)) {
                                h.tap(); m.markRead(a)
                                when { a.id != null -> open(a.id); a.kind == "review" -> m.goTab = "office" }
                            }
                        }
                    }
                }
            }
            Text(t("Choose what to be told about", "Chagua unachotaka kuarifiwa"), style = MaterialTheme.typography.labelLarge, color = C.blue2, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = settings).padding(10.dp).testTag("note_settings_link"))
        }
        // the little menu under "…"
        if (menu) Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { menu = false })
        AnimatedVisibility(menu, Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = 16.dp),
            enter = fadeIn(tween(140)) + scaleIn(tween(200), initialScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f)),
            exit = fadeOut(tween(120)) + scaleOut(tween(150), targetScale = 0.94f, transformOrigin = TransformOrigin(1f, 0f))) {
            val shape = RoundedCornerShape(18.dp); val sun = C === Sun
            Column(Modifier.width(264.dp).then(if (sun) Modifier else Modifier.shadow(16.dp, shape)).clip(shape).background(C.card).border(if (sun) 2.dp else 1.dp, C.line, shape).padding(6.dp).testTag("notes_menu_list")) {
                MenuItem("check", t("Mark all as read", "Weka zote kuwa zimesomwa"), un > 0, "mark_all") { h.tap(); menu = false; m.markRead() }
                MenuItem("bell", t("Choose what to be told about", "Chagua unachotaka kuarifiwa"), true, "note_settings") { menu = false; settings() }
            }
        }
    }
}

@Composable
private fun MoreBtn(modifier: Modifier, onClick: () -> Unit) {
    val sun = C === Sun
    Box(modifier.size(44.dp).clip(CircleShape).background(C.card).border(if (sun) 2.dp else 1.dp, C.line, CircleShape)
        .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = t("More: mark all as read, settings", "Zaidi: weka zote kuwa zimesomwa, mipangilio") }.testTag("notes_menu"),
        contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.5.dp)) { repeat(3) { Box(Modifier.size(4.5.dp).clip(CircleShape).background(C.ink)) } }
    }
}

@Composable
private fun MenuItem(icon: String, label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = 46.dp)
        .alpha(if (enabled) 1f else 0.45f).padding(horizontal = 12.dp, vertical = 10.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Icon(Ic.get(icon), null, tint = C.blue, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(11.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, color = C.ink)
    }
}

@Composable
private fun FilterPill(label: String, on: Boolean, count: Int, tag: String, onClick: () -> Unit) {
    val sun = C === Sun; val shape = RoundedCornerShape(99.dp)
    val bg by animateColorAsState(if (on) C.selected else C.card, tween(200), label = "pill")
    Row(Modifier.clip(shape).background(bg).border(if (sun) 2.dp else 1.dp, if (on) Color.Transparent else C.line, shape).clickable(role = Role.Tab, onClick = onClick)
        .semantics { selected = on }.heightIn(min = 40.dp).padding(horizontal = 15.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp), color = if (on) Color.White else C.ink, maxLines = 1)
        if (count > 0) key(count) {
            Box(Modifier.padding(start = 7.dp).pop().defaultMinSize(minWidth = 20.dp, minHeight = 20.dp).clip(shape).background(C.bad).padding(horizontal = 5.dp).testTag("unread_count"), contentAlignment = Alignment.Center) {
                Text(if (count > 9) "9+" else "$count", color = if (C.dark) Color(0xFF2A0B08) else Color.White, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp))
            }
        }
    }
}

/** One notification: the person's round avatar (or the kind's tile) with a coloured badge, name, "2 h ago · project", the message. */
@Composable
private fun NoteRow(a: Alert, k: List<String>, new: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp); val sun = C === Sun
    val bg by animateColorAsState(if (new) C.tint else C.card, tween(450), label = "row")
    val edge = when { sun && new -> C.blue; sun -> C.line; new -> Color.Transparent; else -> C.line }
    Row(modifier.fillMaxWidth().clip(shape).background(bg).border(if (sun) 2.dp else 1.dp, edge, shape).clickable(role = Role.Button, onClick = onClick)
        .semantics { if (new) stateDescription = t("Unread", "Haijasomwa") }.padding(start = 12.dp, end = 14.dp, top = 13.dp, bottom = 13.dp).testTag("alert_row"),
        verticalAlignment = Alignment.Top) {
        NoteAvatar(a, k, bg)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(a.by.ifBlank { a.name }, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 15.sp), color = C.ink)
            Text(listOf(agoText(a.at), a.title).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(a.msg.ifBlank { a.text }, style = MaterialTheme.typography.bodyMedium, color = C.text, modifier = Modifier.padding(top = 4.dp))
        }
        AnimatedVisibility(new, enter = fadeIn(), exit = scaleOut(tween(300)) + fadeOut(tween(300))) { UnreadDot() }
    }
}

@Composable
private fun NoteAvatar(a: Alert, k: List<String>, ring: Color) {
    val kh = C.hue(k[4])
    if (a.by.isBlank()) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(16.dp)).background(kh.fg), contentAlignment = Alignment.Center) { Icon(Ic.get(k[3]), null, tint = C.card, modifier = Modifier.size(23.dp)) }
        return
    }
    val hu = C.hue(nameHue(a.by))
    Box(Modifier.size(46.dp).testTag("note_avatar")) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(hu.bg), contentAlignment = Alignment.Center) {
            Text(initialsOf(a.by), color = hu.fg, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 15.sp))
        }
        Box(Modifier.align(Alignment.BottomEnd).offset(5.dp, 5.dp).size(24.dp).clip(CircleShape).background(ring).padding(2.5.dp).clip(CircleShape).background(kh.fg), contentAlignment = Alignment.Center) {
            Icon(Ic.get(k[3]), null, tint = C.card, modifier = Modifier.size(12.dp))
        }
    }
}

/** The blue unread dot, with a soft ring going out from it now and then. */
@Composable
private fun UnreadDot() {
    val c = C.blue
    val loops = Motion.on && Motion.loops
    val tr = rememberInfiniteTransition(label = "dot")
    val p by if (loops) tr.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), initialStartOffset = StartOffset(1000)), label = "p") else remember { mutableFloatStateOf(1f) }
    Box(Modifier.padding(start = 8.dp, top = 5.dp).size(10.dp).drawBehind {
        if (loops && p < .7f) { val q = p / .7f; drawCircle(c.copy(alpha = .5f * (1f - q)), radius = size.minDimension / 2 + 9.dp.toPx() * q) }
        drawCircle(c)
    }.testTag("note_dot"))
}

/** Nothing to show: the drop bobs in the middle while little notes slide in from the sides, settle, and drift away again. */
@Composable
private fun NotesEmpty(f: String) {
    val (title, body) = when (f) {
        "unread" -> t("You’re all caught up", "Umesoma zote") to t("Nothing unread. Nice work!", "Hakuna ambayo hujasoma. Kazi nzuri!")
        "me" -> t("Nothing assigned to you right now", "Hakuna ulichopewa kwa sasa") to t("Visits and repair jobs given to you will show up here.", "Ziara na kazi za matengenezo utakazopewa zitaonekana hapa.")
        "repairs" -> t("No repair news", "Hakuna habari za matengenezo") to t("Repairs the team reports and fixes will show up here.", "Matengenezo yanayoripotiwa na kurekebishwa na timu yataonekana hapa.")
        else -> t("You’re all caught up", "Hakuna jipya kwa sasa") to t("When the team reports a repair, fixes a water point or gives you visits, it shows up here.", "Timu ikiripoti matengenezo, ikirekebisha kituo cha maji au ikikupa ziara, itaonekana hapa.")
    }
    val cards = when (f) {
        "me" -> listOf("cal" to "teal", "wrench" to "amber", "clip" to "blue")
        "repairs" -> listOf("alert" to "coral", "okc" to "green", "wrench" to "amber")
        else -> listOf("okc" to "green", "cal" to "teal", "alert" to "coral")
    }
    val loops = Motion.on && Motion.loops
    val tr = rememberInfiniteTransition(label = "empty")
    val bob by if (loops) tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob") else remember { mutableFloatStateOf(0f) }
    val halo by if (loops) tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "halo") else remember { mutableFloatStateOf(.6f) }
    val okp by if (loops) tr.animateFloat(0f, 1f, infiniteRepeatable(tween(3500, easing = LinearEasing), initialStartOffset = StartOffset(600)), label = "ok") else remember { mutableFloatStateOf(.5f) }
    Column(Modifier.fillMaxWidth().rise(0).padding(top = 18.dp).testTag("notes_empty"), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(272.dp, 212.dp)) {
            val blue = C.hue("blue").fg
            Box(Modifier.size(170.dp).align(Alignment.Center).graphicsLayer { val k = .92f + .14f * halo; scaleX = k; scaleY = k }
                .background(Brush.radialGradient(listOf(blue.copy(alpha = .24f), blue.copy(alpha = 0f))), CircleShape))
            FloatNote(cards[0], -7f, false, 0, Modifier.align(Alignment.TopStart).offset((-8).dp, 14.dp), loops, tr)
            FloatNote(cards[1], 6f, true, 500, Modifier.align(Alignment.TopEnd).offset(18.dp, 66.dp), loops, tr)
            FloatNote(cards[2], 4f, false, 1000, Modifier.align(Alignment.BottomStart).offset(0.dp, (-16).dp), loops, tr)
            Drop(96.dp, DropC.Blue, Mood.Smile, Modifier.align(Alignment.Center).offset(y = (-5).dp).graphicsLayer { translationY = -7.dp.toPx() * bob })
            Box(Modifier.align(Alignment.Center).offset(41.dp, (-61).dp).graphicsLayer { val k = okScale(okp); scaleX = k; scaleY = k }
                .size(38.dp).clip(CircleShape).background(C.card).padding(4.dp).clip(CircleShape).background(C.ok), contentAlignment = Alignment.Center) {
                Icon(Ic.get("check"), null, tint = C.card, modifier = Modifier.size(17.dp))
            }
        }
        Text(title, style = MaterialTheme.typography.titleLarge, color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, start = 28.dp, end = 28.dp))
    }
}

/** The tick: pops in, stays, shrinks away, over and over. */
private fun okScale(p: Float): Float = when {
    p < .12f -> 1.15f * (p / .12f)
    p < .18f -> 1.15f - .15f * ((p - .12f) / .06f)
    p < .80f -> 1f
    p < .92f -> 1f - (p - .80f) / .12f
    else -> 0f
}

@Composable
private fun FloatNote(c: Pair<String, String>, r: Float, right: Boolean, delay: Int, modifier: Modifier, loops: Boolean, tr: InfiniteTransition) {
    val p by if (loops) tr.animateFloat(0f, 1f, infiniteRepeatable(tween(7000, easing = LinearEasing), initialStartOffset = StartOffset(delay)), label = "fn$delay") else remember { mutableFloatStateOf(.5f) }
    val hu = C.hue(c.second); val sun = C === Sun; val shape = RoundedCornerShape(15.dp)
    val e = FastOutSlowInEasing; val dir = if (right) 1f else -1f
    Row(modifier.graphicsLayer {
        rotationZ = r
        when {
            p < .11f -> { val q = e.transform(p / .11f); alpha = q; translationX = dir * 54.dp.toPx() * (1f - q) - dir * 5.dp.toPx() * q; rotationZ = dir * 16f + (r - dir * 16f) * q; val k = .9f + .12f * q; scaleX = k; scaleY = k }
            p < .17f -> { val q = e.transform((p - .11f) / .06f); translationX = -dir * 5.dp.toPx() * (1f - q); val k = 1.02f - .02f * q; scaleX = k; scaleY = k }
            p < .74f -> {}
            p < .86f -> { val q = e.transform((p - .74f) / .12f); alpha = 1f - q; translationY = -14.dp.toPx() * q; val k = 1f - .06f * q; scaleX = k; scaleY = k }
            else -> { alpha = 0f }
        }
    }.width(122.dp).then(if (sun) Modifier else Modifier.shadow(10.dp, shape)).clip(shape).background(C.card).border(if (sun) 2.dp else 1.dp, C.line, shape)
        .padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(hu.fg), contentAlignment = Alignment.Center) { Icon(Ic.get(c.first), null, tint = C.card, modifier = Modifier.size(13.dp)) }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(9.dp)).background(hu.bg))
            Box(Modifier.fillMaxWidth(.62f).height(6.dp).clip(RoundedCornerShape(9.dp)).background(if (sun) Color(0xFF8A99A6) else C.line))
        }
    }
}

package org.rafikioutreach.pin.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.Geo
import org.rafikioutreach.pin.data.LatLng
import org.rafikioutreach.pin.data.Project
import org.rafikioutreach.pin.data.Snapshot
import org.rafikioutreach.pin.t

/** A light tick under the finger, like other phone apps: [tap] for taps, [ok] when something is saved. */
class Haptics(private val view: android.view.View) {
    fun tap() { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
    fun ok() { view.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS) }
}

@Composable
fun rememberHaptics(): Haptics { val v = LocalView.current; return remember(v) { Haptics(v) } }

/** A choice that slides up from the bottom of the screen (country, map style…). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickSheet(title: String, options: List<Triple<String, String, String?>>, selected: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val h = rememberHaptics()
    ModalBottomSheet(onDismiss, sheetState = state, containerColor = C.card, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp).testTag("sheet")) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = C.ink, modifier = Modifier.padding(bottom = 10.dp))
            options.forEach { (k, label, icon) ->
                val on = k == selected
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(16.dp)).background(if (on) C.tint else Color.Transparent)
                        .clickable(role = Role.RadioButton) { h.tap(); onPick(k); onDismiss() }.heightIn(min = 54.dp).padding(horizontal = 14.dp).testTag("pick_$k"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (icon != null) { IconTile(icon, C.blue, if (on) C.card else C.tint, 38.dp); Spacer(Modifier.width(12.dp)) }
                    Text(label, style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.weight(1f))
                    if (on) Icon(Ic.get("check"), null, tint = C.blue, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

/** One filter chip in the swipeable row. */
@Composable
fun Chip(label: String, icon: String?, on: Boolean, onClick: () -> Unit, badge: Int = 0, trailing: String? = null, tag: String = "") {
    val h = rememberHaptics()
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) C.primary else SolidColor(C.card))
            .then(if (!on) Modifier.border(1.dp, C.line, RoundedCornerShape(999.dp)) else Modifier)
            .clickable(role = Role.Tab) { h.tap(); onClick() }.heightIn(min = 40.dp).padding(horizontal = 14.dp).then(if (tag.isNotEmpty()) Modifier.testTag(tag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(Ic.get(icon), null, tint = if (on) Color.White else C.blue, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)) }
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp), color = if (on) Color.White else C.ink, maxLines = 1)
        if (badge > 0) { Spacer(Modifier.width(6.dp)); Text("$badge", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(CircleShape).background(Red).padding(horizontal = 6.dp, vertical = 1.dp)) }
        if (trailing != null) { Spacer(Modifier.width(4.dp)); Icon(Ic.get(trailing), null, tint = if (on) Color.White else C.muted, modifier = Modifier.size(16.dp)) }
    }
}

/** A panel you drag up from the bottom of the map: a peek of the closest project, or the five closest. */
@Composable
fun DragSheet(title: String, modifier: Modifier, peek: @Composable ColumnScope.() -> Unit, full: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    val h = rememberHaptics()
    Column(
        modifier.fillMaxWidth().animateContentSize(spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow))
            .clip(RoundedCornerShape(26.dp)).background(if (C.glassy) C.glass2 else C.card).border(1.dp, if (C.glassy) C.glassEdge else C.line, RoundedCornerShape(26.dp))
            .testTag("nearest"),
    ) {
        // the handle: drag up or down, or tap
        Column(
            Modifier.fillMaxWidth().pointerInput(Unit) {
                detectVerticalDragGestures(onDragStart = { drag = 0f }, onDragEnd = { if (drag < -24 && !open) { open = true; h.tap() } else if (drag > 24 && open) { open = false; h.tap() } }) { _, d -> drag += d }
            }.clickable(role = Role.Button) { open = !open; h.tap() }.semantics { contentDescription = if (open) t("Show less", "Onyesha kidogo") else t("Show more", "Onyesha zaidi") }.testTag("sheet_handle"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.padding(top = 8.dp).size(40.dp, 5.dp).clip(RoundedCornerShape(99.dp)).background(C.edge))
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = C.ink, modifier = Modifier.weight(1f))
                Icon(Ic.get("down"), null, tint = C.muted, modifier = Modifier.size(20.dp).rotate(if (open) 0f else 180f))
            }
        }
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { if (open) full() else peek() }
    }
}


/** Full-screen search: the keyboard opens straight away and results show as you type. */
@Composable
fun SearchScreen(m: AppModel, snap: Snapshot, here: LatLng?, open: (String) -> Unit, back: () -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val words = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    val hits = if (words.isEmpty()) emptyList() else snap.projects.filter { p -> val hay = "${p.title} ${p.area} ${p.country} ${p.type}".lowercase(); words.all { hay.contains(it) } }
        .sortedWith(compareBy<Project>({ !it.title.lowercase().startsWith(words.first()) }, { here?.let { h -> it.gps?.let { g -> Geo.metres(h, g) } } ?: Double.MAX_VALUE }))
    Column(Modifier.fillMaxSize().testTag("search_screen")) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().heightIn(min = 54.dp).clip(RoundedCornerShape(18.dp)).background(C.card).border(1.5.dp, C.blue, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.get("search"), null, tint = C.blue, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (q.isEmpty()) Text(t("Search a project or place", "Tafuta mradi au mahali"), style = MaterialTheme.typography.bodyLarge, color = C.muted)
                BasicTextField(q, { q = it }, Modifier.fillMaxWidth().focusRequester(focus).testTag("search_input"), singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = C.text), cursorBrush = SolidColor(C.blue),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { kb?.hide() }))
            }
            if (q.isNotEmpty()) Box(Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button) { q = "" }.semantics { contentDescription = t("Clear", "Futa") }, contentAlignment = Alignment.Center) {
                Icon(Ic.get("x"), null, tint = C.muted, modifier = Modifier.size(16.dp))
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                words.isEmpty() -> {
                    item { Text(t("Type a project name, a village or a county.", "Andika jina la mradi, kijiji au kaunti."), style = MaterialTheme.typography.bodyMedium, color = C.muted, modifier = Modifier.padding(4.dp)) }
                    // a few to start with: the closest ones, or the first ones
                    val start = (here?.let { h -> snap.projects.filter { it.gps != null }.sortedBy { Geo.metres(h, it.gps!!) } } ?: snap.projects).take(5)
                    if (start.isNotEmpty()) item { Text(if (here != null) t("Near you", "Karibu nawe") else t("Projects", "Miradi"), style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.padding(top = 8.dp, start = 4.dp)) }
                    items(start, key = { "s" + it.id }) { p -> ProjectRow(m, snap, p, here) { kb?.hide(); open(p.id) } }
                }
                hits.isEmpty() -> item { EmptyDrop(t("No projects match “$q”.", "Hakuna mradi unaolingana na “$q”.")) }
                else -> {
                    item { Text(if (hits.size == 1) t("1 project", "Mradi 1") else t("${hits.size} projects", "Miradi ${hits.size}"), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = C.muted, modifier = Modifier.padding(start = 4.dp)) }
                    items(hits, key = { it.id }) { p -> ProjectRow(m, snap, p, here) { kb?.hide(); open(p.id) } }
                }
            }
        }
    }
}

/** Shows a short title in the top bar while the screen's own big title is scrolled away. */
class BarTitle { var owner by mutableStateOf<Any?>(null); var text by mutableStateOf<String?>(null) }

@Composable
fun ShowInBar(m: AppModel, title: String?, show: Boolean) {
    val me = remember { Any() }
    LaunchedEffect(show, title) { if (show && title != null) { m.bar.owner = me; m.bar.text = title } else if (m.bar.owner === me) { m.bar.text = null } }
    DisposableEffect(Unit) { onDispose { if (m.bar.owner === me) { m.bar.text = null; m.bar.owner = null } } }
}

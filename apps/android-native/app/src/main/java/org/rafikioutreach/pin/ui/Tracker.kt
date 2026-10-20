package org.rafikioutreach.pin.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.Outcome
import org.rafikioutreach.pin.data.Geo
import org.rafikioutreach.pin.data.LatLng
import org.rafikioutreach.pin.data.Project
import org.rafikioutreach.pin.data.Repair
import org.rafikioutreach.pin.data.urgencyName
import org.rafikioutreach.pin.t
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A still map around a point, for the repair card. */
@Composable
private fun StillMap(ll: LatLng, color: Int, modifier: Modifier) {
    val ctx = LocalContext.current
    MapSetup.init(ctx)
    val map = remember {
        MapView(ctx).apply {
            setMultiTouchControls(false); zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            setOnTouchListener { _, _ -> true }; isClickable = false
            controller.setZoom(15.0); controller.setCenter(GeoPoint(ll.lat, ll.lng))
            overlays.add(Marker(this).apply {
                position = GeoPoint(ll.lat, ll.lng); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); setInfoWindow(null)
                icon = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(color); setStroke(7, android.graphics.Color.WHITE); setSize(54, 54) }
            })
        }
    }
    DisposableEffect(Unit) { map.onResume(); onDispose { map.onPause(); map.onDetach() } }
    AndroidView({ map }, modifier.clipToBounds())
}

/** An open repair: where it is, who has it, and how far the job has got. */
@Composable
fun RepairTracker(m: AppModel, p: Project, r: Repair, here: LatLng?) {
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var fixed by remember(p.id) { mutableStateOf(false) }
    val bad = r.state == "notworking"
    val order = listOf("reported", "assigned", "parts", "fixed")
    var si = if (fixed) 3 else maxOf(0, order.indexOf(r.step))
    if (!fixed && r.assignee.isNotBlank() && si < 1) si = 1
    val d = if (here != null && p.gps != null) Geo.metres(here, p.gps) else null
    val steps = listOf(
        t("Reported", "Imeripotiwa") to ((if (bad) t("Not working", "Haifanyi kazi") else t("Needs repair", "Inahitaji matengenezo")) + " · " + r.by + ", " + whenText(r.at)),
        t("Assigned", "Imegawiwa") to (if (r.assignee.isNotBlank()) r.assignee + (r.stepAt["assigned"]?.let { ", " + whenText(it) } ?: "") else t("Waiting for the office", "Inasubiri ofisi")),
        t("Parts ordered", "Vipuri vimeagizwa") to (r.stepAt["parts"]?.let { whenText(it) } ?: if (r.parts.isNotBlank()) t("Needs: ", "Inahitaji: ") + r.parts else "—"),
        t("Fixed", "Imerekebishwa") to (if (fixed) m.prefs.name else t("Mark it fixed when the water is running again", "Weka imerekebishwa maji yakitoka tena")),
    )
    val pulse by if (Motion.on) rememberInfiniteTransition(label = "now").animateFloat(0f, 1f, infiniteRepeatable(tween(1800)), label = "p") else remember { mutableFloatStateOf(0f) }
    Card(Modifier.padding(top = 12.dp).testTag("tracker")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("Repair job", "Kazi ya matengenezo"), style = MaterialTheme.typography.titleMedium, color = C.ink, modifier = Modifier.weight(1f))
            if (fixed) Tag(t("Fixed ✓", "Imerekebishwa ✓"), C.okBg, C.ok)
            else Tag((if (r.urgency == "urgent" || r.urgency == "month") urgencyName(r.urgency) + " · " else "") + if (bad) t("Not working", "Haifanyi kazi") else t("Needs repair", "Inahitaji matengenezo"), C.badBg, C.bad)
        }
        if (p.gps != null && !fixed) Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(150.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFFEAF1F6))) {
            StillMap(p.gps, if (bad) 0xFFB3261E.toInt() else 0xFFD98200.toInt(), Modifier.matchParentSize())
            if (d != null) Row(Modifier.padding(10.dp).clip(RoundedCornerShape(99.dp)).background(Color(0xD9FFFFFF)).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF1C9C5A))); Spacer(Modifier.width(6.dp))
                Text(Geo.dist(d, m.prefs.miles) + " · " + t("Live", "Moja kwa moja"), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF04263F))
            }
            Text("© OpenStreetMap", fontSize = 8.sp, color = Color(0xFF5A6066), modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color(0xB3FFFFFF)).padding(horizontal = 3.dp))
            Row(Modifier.align(Alignment.BottomCenter).padding(8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xD1FFFFFF)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF47B4F5), Color(0xFF0A78C2)))), contentAlignment = Alignment.Center) {
                    Text(if (r.assignee.isNotBlank()) r.assignee.split(" ").mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString("") else "?", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(r.assignee.ifBlank { t("Not assigned yet", "Bado hajapewa mtu") }, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF04263F))
                    Text(if (r.due.isNotBlank()) t("Due ", "Mwisho ") + runCatching { LocalDate.parse(r.due).format(DateTimeFormatter.ofPattern("d MMM", Locale.UK)) }.getOrDefault(r.due)
                        else if (r.assignee.isNotBlank()) t("Repair job", "Kazi ya matengenezo") else t("The office assigns it", "Ofisi itamgawia mtu"), fontSize = 12.sp, color = Color(0xFF5A6066))
                }
            }
        }
        Column(Modifier.padding(top = 14.dp)) {
            steps.forEachIndexed { i, (title, sub) ->
                val done = i < si || fixed; val now = i == si && !fixed
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).testTag("step_$i")) {
                    Box(Modifier.width(28.dp).fillMaxHeight()) {
                        if (i < steps.size - 1) Box(Modifier.align(Alignment.TopCenter).padding(top = 28.dp).width(2.dp).fillMaxHeight().background(if (i < si || fixed) C.ok else C.line))
                        Box(
                            Modifier.size(28.dp).drawBehind { if (now) drawCircle(Color(0xFF1C7C4A).copy(alpha = .45f * (1 - pulse)), radius = size.minDimension / 2 + 10.dp.toPx() * pulse) }
                                .clip(CircleShape).background(if (done || now) C.ok else C.line),
                            contentAlignment = Alignment.Center,
                        ) { if (done || now) Icon(Ic.get("check"), null, tint = Color.White, modifier = Modifier.size(15.dp)) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.padding(bottom = 14.dp)) {
                        Text(title, style = MaterialTheme.typography.titleSmall, color = if (done || now) C.ink else C.muted)
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = C.muted)
                    }
                }
            }
        }
        if (!fixed) {
            if (r.note.isNotBlank()) Text("“${r.note}”", style = MaterialTheme.typography.bodyMedium, color = C.text)
            if (r.problems.isNotEmpty()) Text(t("Problems: ", "Matatizo: ") + r.problems.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = C.muted)
            r.cost?.let { Text(t("About KES ", "Takriban KES ") + "%,d".format(it), style = MaterialTheme.typography.bodySmall, color = C.muted) }
            Btn(t("Mark fixed", "Weka imerekebishwa"), {
                busy = true
                m.viewModelScope.launch {
                    when (val o = m.submit(JSONObject().put("action", "fixed").put("id", p.id).put("note", ""), emptyList())) {
                        is Outcome.Sent, Outcome.Queued -> {
                            fixed = true; msg = if (o is Outcome.Queued) t("Saved on this phone. It will be sent when you have signal.", "Imehifadhiwa kwenye simu. Itatumwa ukipata mtandao.") else null
                            delay(4000); m.localFixed(p)
                        }
                        is Outcome.Failed -> msg = o.text
                        Outcome.Locked -> {}
                    }
                    busy = false
                }
            }, Modifier.padding(top = 10.dp), icon = "wrench", ok = true, small = true, enabled = !busy)
        }
        Msg(msg, "warn")
    }
}

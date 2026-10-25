package org.rafikioutreach.pin.ui

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.Lang
import org.rafikioutreach.pin.data.DProject
import org.rafikioutreach.pin.t
import kotlin.math.ceil

// Donor mode made easier for older donors (the same as the web donor pages): an interactive map with big pins,
// big buttons and a card that steps through the projects; photos full screen with a slideshow; "Read it to me";
// and call or WhatsApp the office.

const val DONOR_PHONE = "+1 801 547 7847"
const val DONOR_WHATSAPP = "+254 791 674 001"

/** The colour and the small mark of each state, the same on the web: ✓ working, wrench, ! , bricks, clock. */
private val D_ARGB = mapOf("working" to 0xFF1C7C4A.toInt(), "repairing" to 0xFFC77700.toInt(), "notworking" to 0xFFB3261E.toInt(),
    "building" to 0xFF5B3CC4.toInt(), "planned" to 0xFF5E7A90.toInt(), "done" to 0xFF0A78C2.toInt())
private val D_MARK = mapOf("working" to "check", "repairing" to "wrench", "notworking" to "alert", "building" to "brick", "planned" to "clock", "done" to "check")
fun donorStateColor(k: String) = Color(D_ARGB[k] ?: D_ARGB.getValue("done"))
fun donorIcon(p: DProject) = when { p.water -> "drop"; p.cat == "school" -> "school"; p.cat == "children" -> "house"; p.cat == "health" -> "cross"; else -> "spark" }

/** "About 620 people are served here." (or pupils, patients, children), for the card and for reading aloud. */
fun donorPeopleSay(p: DProject): String {
    fun n(v: Int) = String.format(java.util.Locale.US, "%,d", v)
    if (p.status != "Completed") return if (p.people > 0) t("When it’s finished, about ${n(p.people)} people will be served.", "Ukikamilika, takriban watu ${n(p.people)} watahudumiwa.") else ""
    return when {
        p.pupils > 0 -> t("${n(p.pupils)} pupils learn here.", "Wanafunzi ${n(p.pupils)} wanasoma hapa.")
        p.patients > 0 -> t("About ${n(p.patients)} patients are seen here every week.", "Takriban wagonjwa ${n(p.patients)} wanahudumiwa hapa kila wiki.")
        p.children > 0 -> t("${n(p.children)} children are cared for here.", "Watoto ${n(p.children)} wanatunzwa hapa.")
        p.people > 0 -> t("About ${n(p.people)} people are served here.", "Takriban watu ${n(p.people)} wanahudumiwa hapa.")
        else -> ""
    }
}

// ---- read it to me ------------------------------------------------------------------------------------------------
/** The phone's own voice reads a card aloud; one thing at a time, and the same button stops it. */
class DonorSpeaker(ctx: Context) : TextToSpeech.OnInitListener {
    private var ready = false
    private val tts = TextToSpeech(ctx.applicationContext, this)
    var speaking by mutableStateOf<String?>(null)
    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { if (speaking == id) speaking = null }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { if (speaking == id) speaking = null }
        })
    }
    /** Starts reading [text], or stops when [key] is already being read. False when the phone has no voice. */
    fun toggle(key: String, text: String): Boolean {
        if (speaking == key) { stop(); return true }
        if (!ready) return false
        tts.language = if (Lang.sw) java.util.Locale("sw", "KE") else java.util.Locale.UK
        tts.setSpeechRate(0.9f)
        speaking = key
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, key)
        return true
    }
    fun stop() { tts.stop(); speaking = null }
    fun shutdown() { runCatching { tts.stop(); tts.shutdown() } }
}
val LocalSpeaker = staticCompositionLocalOf<DonorSpeaker?> { null }

@Composable
fun ProvideSpeaker(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val s = remember { DonorSpeaker(ctx) }
    DisposableEffect(Unit) { onDispose { s.shutdown() } }
    CompositionLocalProvider(LocalSpeaker provides s) { content() }
}

/** "Read it to me" (or "Listen"), which turns into "Stop reading" while the phone reads. */
@Composable
fun SpeakBtn(key: String, text: String, modifier: Modifier = Modifier, label: String = t("Read it to me", "Nisomee"), small: Boolean = false) {
    val sp = LocalSpeaker.current ?: return
    val ctx = LocalContext.current
    val on = sp.speaking == key
    Btn(if (on) t("Stop reading", "Acha kusoma") else label, {
        if (!sp.toggle(key, text)) android.widget.Toast.makeText(ctx, t("Reading aloud isn’t available on this phone.", "Kusoma kwa sauti hakupatikani kwenye simu hii."), android.widget.Toast.LENGTH_SHORT).show()
    }, modifier.testTag("speak_$key"), icon = if (on) "pause" else "speak", light = true, small = small)
}

// ---- call, WhatsApp or e-mail the office ----------------------------------------------------------------------------
@Composable
fun DonorTalk(m: AppModel, p: DProject?) {
    val ctx = LocalContext.current
    val who = m.donor.view.value?.viewer ?: ""
    fun go(u: String) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TalkRow("phone", C.blue2, t("Call RAFIKI", "Piga simu RAFIKI"), DONOR_PHONE, "talk_call") { go("tel:" + DONOR_PHONE.filter { it.isDigit() || it == '+' }) }
        TalkRow("chat", Color(0xFF1F8F4E), t("WhatsApp us", "Tuandikie WhatsApp"), DONOR_WHATSAPP, "talk_wa") {
            go("https://wa.me/" + DONOR_WHATSAPP.filter { it.isDigit() } + "?text=" + Uri.encode("Hello RAFIKI, this is $who" + if (p != null) ". A question about ${p.title}: " else ". "))
        }
        TalkRow("mail", C.blue2, t("E-mail us", "Tutumie barua pepe"), DONOR_CONTACT, "talk_mail") {
            go("mailto:$DONOR_CONTACT?subject=" + Uri.encode(if (p != null) "Question about ${p.title}" else "My RAFIKI projects"))
        }
    }
}

@Composable
private fun TalkRow(icon: String, color: Color, title: String, sub: String, tag: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val wa = icon == "chat"
    Row(Modifier.fillMaxWidth().clip(shape).background(if (wa) color else C.card).border(1.dp, if (wa) color else C.line, shape)
        .clickable(role = Role.Button, onClick = onClick).heightIn(min = 64.dp).padding(horizontal = 18.dp, vertical = 10.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Icon(Ic.get(icon), null, tint = if (wa) Color.White else color, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (wa) Color.White else C.ink)
            Text(sub, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = if (wa) Color.White.copy(alpha = .9f) else C.muted)
        }
    }
}

// ---- the map ------------------------------------------------------------------------------------------------------
private var donorFlown = false

/** A project's pin: its kind's icon on white inside a ring in its state's colour (dashed while planned), the state's
 *  small mark at the corner; the chosen one is bigger and filled. */
private fun donorPin(res: Resources, p: DProject, on: Boolean): BitmapDrawable {
    val d = res.displayMetrics.density
    val col = D_ARGB[p.state] ?: D_ARGB.getValue("done")
    val r = (if (on) 30f else 25f) * d; val ring = 5f * d; val R = r + ring; val bd = 12f * d
    val n = ceil(2 * R + 16 * d).toInt(); val c0 = R + 4 * d
    val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888); val c = Canvas(b)
    val pt = Paint(Paint.ANTI_ALIAS_FLAG)
    pt.color = 0x40000000; c.drawCircle(c0, c0 + 2 * d, R + 1.5f * d, pt)
    pt.color = android.graphics.Color.WHITE; c.drawCircle(c0, c0, R, pt)
    if (on) { pt.color = col; c.drawCircle(c0, c0, R, pt) }
    else {
        pt.style = Paint.Style.STROKE; pt.strokeWidth = ring; pt.color = col
        if (p.state == "planned") pt.pathEffect = DashPathEffect(floatArrayOf(7 * d, 5 * d), 0f)
        c.drawCircle(c0, c0, R - ring / 2, pt); pt.pathEffect = null; pt.style = Paint.Style.FILL
    }
    fun icon(name: String, cx: Float, cy: Float, size: Float, color: Int, w: Float) {
        c.save(); c.translate(cx - size / 2, cy - size / 2); c.scale(size / 24f, size / 24f)
        pt.style = Paint.Style.STROKE; pt.strokeWidth = w; pt.strokeCap = Paint.Cap.ROUND; pt.strokeJoin = Paint.Join.ROUND; pt.color = color
        Ic.paths(name).forEach { s -> runCatching { androidx.core.graphics.PathParser.createPathFromPathData(s) }.getOrNull()?.let { c.drawPath(it, pt) } }
        c.restore(); pt.style = Paint.Style.FILL
    }
    icon(donorIcon(p), c0, c0, r * 1.05f, if (on) android.graphics.Color.WHITE else col, 2.2f)
    val bx = c0 + R * 0.72f; val by = c0 + R * 0.72f
    pt.color = android.graphics.Color.WHITE; c.drawCircle(bx, by, bd, pt)
    pt.color = col; c.drawCircle(bx, by, bd - 3 * d, pt)
    icon(D_MARK[p.state] ?: "check", bx, by, bd * 1.25f, android.graphics.Color.WHITE, 3.2f)
    return BitmapDrawable(res, b)
}

/** The map itself (its own MapView, so the bigger map can have another): big + and −, Map | Satellite. */
@Composable
private fun DonorMapView(pts: List<DProject>, sat: Boolean, onSat: (Boolean) -> Unit, cur: String, chosen: Boolean, fitTick: Int, onPick: (String) -> Unit, modifier: Modifier, first: Boolean) {
    val ctx = LocalContext.current
    MapSetup.init(ctx)
    val res = ctx.resources
    val map = remember {
        MapView(ctx).apply {
            setMultiTouchControls(true); zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER); minZoomLevel = 3.0
            controller.setZoom(6.0); controller.setCenter(GeoPoint(0.6, 36.4))
        }
    }
    val markers = remember { HashMap<String, Marker>() }
    val pick by androidx.compose.runtime.rememberUpdatedState(onPick)
    DisposableEffect(Unit) { map.onResume(); onDispose { map.onPause(); map.onDetach() } }
    fun fit(anim: Boolean) {
        val gs = pts.mapNotNull { p -> p.gps?.let { GeoPoint(it.lat, it.lng) } }
        if (gs.isEmpty()) return
        map.post {
            if (gs.size == 1) { if (anim) map.controller.animateTo(gs[0], 14.0, 1500L) else { map.controller.setZoom(14.0); map.controller.setCenter(gs[0]) } }
            else runCatching { map.zoomToBoundingBox(BoundingBox.fromGeoPointsSafe(gs).increaseByScale(1.4f), anim) }
        }
    }
    // the first time: from East Africa, fly down to the projects; after that, open on them
    LaunchedEffect(Unit) { if (first && !donorFlown && Motion.on) { donorFlown = true; delay(700); fit(true) } else { delay(50); fit(false) } }
    LaunchedEffect(fitTick) { if (fitTick > 0) fit(Motion.on) }
    LaunchedEffect(cur, chosen) {
        if (!chosen) return@LaunchedEffect
        pts.firstOrNull { it.id == cur }?.gps?.let { map.controller.animateTo(GeoPoint(it.lat, it.lng), maxOf(map.zoomLevelDouble, 14.0), if (Motion.on) 1200L else 0L) }
    }
    Box(modifier.clipToBounds()) {
        AndroidView({ map }, Modifier.fillMaxSize(), update = { mv ->
            val src = if (sat) Satellite else OfflineMaps.street()
            if (mv.tileProvider.tileSource != src) mv.setTileSource(src)
            pts.forEach { p ->
                val g = p.gps ?: return@forEach
                val mk = markers.getOrPut(p.id) {
                    Marker(mv).apply { position = GeoPoint(g.lat, g.lng); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); title = p.title; setInfoWindow(null)
                        setOnMarkerClickListener { _, _ -> pick(p.id); true } }.also { mv.overlays.add(it) }
                }
                val on = p.id == cur
                mk.icon = donorPin(res, p, on); mk.alpha = if (chosen && !on) 0.6f else 1f
            }
            // the chosen pin drawn last, over the others
            markers[cur]?.let { mv.overlays.remove(it); mv.overlays.add(it) }
            mv.invalidate()
        })
        FreeCredit(sat, Modifier.align(Alignment.BottomEnd))
        // Map | Satellite, big
        Row(Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(4.dp)) {
            listOf(false to t("Map", "Ramani"), true to t("Satellite", "Satelaiti")).forEach { (k, label) ->
                val on = sat == k
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) Color(0xFF0A78C2) else Color.Transparent).clickable(role = Role.RadioButton) { onSat(k) }
                    .heightIn(min = 46.dp).padding(horizontal = 16.dp).testTag("dmap_" + if (k) "sat" else "map"), contentAlignment = Alignment.Center) {
                    Text(label, color = if (on) Color.White else Color(0xFF04263F), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold))
                }
            }
        }
        // big + and −
        Column(Modifier.align(Alignment.TopEnd).padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("+" to t("Zoom in", "Karibia"), "−" to t("Zoom out", "Ondoka mbali")).forEach { (s, label) ->
                Box(Modifier.size(60.dp).clip(RoundedCornerShape(18.dp)).background(Color.White).border(1.dp, Color(0x33000000), RoundedCornerShape(18.dp))
                    .clickable(role = Role.Button) { if (s == "+") map.controller.zoomIn() else map.controller.zoomOut() }.semantics { contentDescription = label }
                    .testTag(if (s == "+") "dmap_in" else "dmap_out"), contentAlignment = Alignment.Center) {
                    Text(s, color = Color(0xFF04263F), fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

/** Your projects on the map: the map, Show all and Bigger map, the colour key, and (on Home) a big card that steps
 *  through the projects with Previous and Next. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DonorMap(m: AppModel, projects: List<DProject>, withCard: Boolean, open: (String) -> Unit) {
    val pts = projects.filter { it.gps != null }
    if (pts.isEmpty()) return
    var sat by rememberSaveable { mutableStateOf(false) }
    var cur by rememberSaveable { mutableStateOf(pts.first().id) }
    var chosen by remember { mutableStateOf(false) }
    var full by remember { mutableStateOf(false) }
    var fitTick by remember { mutableIntStateOf(0) }
    val sp = LocalSpeaker.current
    val choose: (String) -> Unit = { id -> cur = id; chosen = true; sp?.stop() }
    Card(pad = 0.dp, modifier = Modifier.testTag("dmap")) {
        DonorMapView(pts, sat, { sat = it }, cur, chosen, fitTick, { if (withCard) choose(it) }, Modifier.fillMaxWidth().height(if (withCard) 380.dp else 260.dp), withCard)
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (pts.size > 1) Btn(t("Show all", "Onyesha yote"), { chosen = false; fitTick++ }, Modifier.weight(1f).testTag("dmap_all"), icon = "fit", light = true)
            Btn(t("Bigger map", "Ramani kubwa"), { full = true }, Modifier.weight(1f).testTag("dmap_big"), icon = "fit", light = true)
        }
        if (withCard) {
            FlowRow(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("working", "repairing", "notworking", "building", "planned", "done").filter { k -> pts.any { it.state == k } }.forEach { k ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(18.dp).clip(CircleShape).background(Color.White).border(4.dp, donorStateColor(k), CircleShape)); Spacer(Modifier.width(7.dp))
                        Text(if (k == "done") t("Completed, not checked yet", "Imekamilika, haijakaguliwa bado") else donorStateName(k), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = C.text)
                    }
                }
            }
            val i = pts.indexOfFirst { it.id == cur }.coerceAtLeast(0); val p = pts[i]
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
            Crossfade(p.id, label = "dcard") { _ -> DonorMapCard(m, p, i, pts.size, { choose(pts[(i - 1 + pts.size) % pts.size].id) }, { choose(pts[(i + 1) % pts.size].id) }, open) }
        }
    }
    if (full) Dialog({ full = false }, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            DonorMapView(pts, sat, { sat = it }, cur, chosen, fitTick, { if (withCard) choose(it) }, Modifier.fillMaxSize(), false)
            Btn(t("Close map", "Funga ramani"), { full = false }, Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp).testTag("dmap_close"), icon = "x")
        }
    }
}

@Composable
private fun DonorMapCard(m: AppModel, p: DProject, i: Int, n: Int, prev: () -> Unit, next: () -> Unit, open: (String) -> Unit) {
    val say = donorStateSay(p) + donorPeopleSay(p).let { if (it.isBlank()) "" else " $it" }
    Column(Modifier.padding(16.dp).testTag("dmap_card"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NetImage(p.photos.firstOrNull()?.let { m.donor.api.board(it, 480) } ?: p.gallery.firstOrNull()?.let { m.donor.api.photo(it.id, 480) },
                Modifier.size(84.dp).clip(RoundedCornerShape(18.dp)).background(C.tint)) { Icon(Ic.get(donorIcon(p)), null, tint = C.blue, modifier = Modifier.size(36.dp)) }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(p.title, style = MaterialTheme.typography.titleLarge, color = C.ink)
                Text(listOf(p.area, p.country).filter { it.isNotBlank() }.joinToString(", "), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = C.muted)
            }
        }
        val col = donorStateColor(p.state)
        Row(Modifier.clip(RoundedCornerShape(99.dp)).background(col.copy(alpha = .12f)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(col)); Spacer(Modifier.width(8.dp))
            Text(donorStateName(p.state), color = col, style = MaterialTheme.typography.labelLarge)
        }
        Text(say, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 26.sp), color = C.text)
        if (n > 1) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn(t("Previous", "Iliyotangulia"), prev, Modifier.weight(1f).testTag("dmap_prev"), icon = "back", light = true)
            Btn(t("Next", "Inayofuata"), next, Modifier.weight(1f).testTag("dmap_next"), icon = "arrow", light = true)
        }
        Btn(t("See this project", "Ona mradi huu"), { open(p.id) }, Modifier.fillMaxWidth().testTag("dmap_open"), icon = "chev")
        SpeakBtn("map_" + p.id, p.title + ". " + donorStateName(p.state) + ". " + say, Modifier.fillMaxWidth())
        if (n > 1) Text(t("Project ${i + 1} of $n", "Mradi ${i + 1} kati ya $n"), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = C.muted,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

// ---- photos: full screen, big arrows, swipe, slideshow ---------------------------------------------------------
/** The photos one at a time on black, with big ◀ ▶, a Slideshow button (a new photo every 4½ seconds) and a big Close. */
@Composable
fun PhotoViewer(photos: List<Pair<String, String>>, start: Int, play: Boolean, close: () -> Unit) {
    if (photos.isEmpty()) return
    Dialog(close, DialogProperties(usePlatformDefaultWidth = false)) {
        var i by remember { mutableIntStateOf(start.coerceIn(0, photos.size - 1)) }
        var playing by remember { mutableStateOf(play && photos.size > 1) }
        LaunchedEffect(playing, i) { if (playing) { delay(4500); i = (i + 1) % photos.size } }
        Column(Modifier.fillMaxSize().background(Color(0xFF0B0F13)).padding(12.dp).testTag("viewer")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (photos.size > 1) t("Photo ${i + 1} of ${photos.size}", "Picha ${i + 1} kati ya ${photos.size}") else "", color = Color.White,
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Btn(t("Close", "Funga"), close, Modifier.testTag("viewer_close"), icon = "x", light = true)
            }
            Box(Modifier.weight(1f).fillMaxWidth().pointerInput(photos.size) {
                var dx = 0f
                detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = {
                    if (kotlin.math.abs(dx) > 60f && photos.size > 1) { playing = false; i = (i + (if (dx < 0) 1 else -1) + photos.size) % photos.size }
                }) { _, d -> dx += d }
            }, contentAlignment = Alignment.Center) {
                Crossfade(i, label = "photo") { k -> FitImage(photos[k].first, Modifier.fillMaxSize()) }
            }
            Text(photos[i].second, color = Color(0xFFE6EEF4), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            if (photos.size > 1) Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                RoundNav("back", t("Previous photo", "Picha iliyotangulia")) { playing = false; i = (i - 1 + photos.size) % photos.size }
                Btn(if (playing) t("Pause", "Simamisha") else t("Slideshow", "Onyesho la picha"), { playing = !playing }, Modifier.testTag("viewer_play"), icon = if (playing) "pause" else "play")
                RoundNav("arrow", t("Next photo", "Picha inayofuata")) { playing = false; i = (i + 1) % photos.size }
            }
        }
    }
}

@Composable
private fun RoundNav(icon: String, label: String, onClick: () -> Unit) {
    Box(Modifier.size(70.dp).clip(CircleShape).background(Color(0x1FFFFFFF)).border(2.dp, Color(0x80FFFFFF), CircleShape).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) { Icon(Ic.get(icon), null, tint = Color.White, modifier = Modifier.size(32.dp)) }
}

/** A photo shown whole (not cropped). */
@Composable
private fun FitImage(url: String, modifier: Modifier) {
    var bmp by remember(url) { mutableStateOf(Pics.cached(url)) }
    LaunchedEffect(url) { if (bmp == null) bmp = Pics.load(url) }
    Box(modifier, contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)))
        else Text(t("Loading…", "Inapakia…"), color = Color(0xFF9CA8B2))
    }
}

// ---- updates told as short stories ----------------------------------------------------------------------------------
/** What an update says, in plain sentences (and read aloud by Listen). */
fun donorStory(u: org.rafikioutreach.pin.data.DUpdate): String = when (u.type) {
    "visit" -> {
        val v = u.visit
        val head = when {
            v?.build == true -> t("Our team visited the building work.", "Timu yetu ilitembelea ujenzi.")
            v?.state == "working" -> t("Our team visited and found it working well.", "Timu yetu ilitembelea na ikakuta inafanya kazi vizuri.")
            v?.state == "repair" -> t("Our team visited and found something that needs a repair. They are arranging it.", "Timu yetu ilitembelea na ikakuta kitu kinachohitaji matengenezo. Wanalipanga.")
            v?.state == "notworking" -> t("Our team visited and found it wasn’t working. They are arranging the repair.", "Timu yetu ilitembelea na ikakuta haifanyi kazi. Wanapanga matengenezo.")
            else -> t("Our team visited and checked it.", "Timu yetu ilitembelea na kukagua.")
        }
        val say = v?.let { org.rafikioutreach.pin.data.donorVisitSay(it) }.orEmpty()
        if (say.isBlank()) head else "$head $say"
    }
    "photos" -> if (u.n == 1) t("Our team took a new photo. Tap it to see it big.", "Timu yetu ilipiga picha mpya. Iguse uione kubwa.") else t("Our team took ${u.n} new photos. Tap one to see it big.", "Timu yetu ilipiga picha ${u.n} mpya. Gusa moja uione kubwa.")
    "thanks" -> (if (u.media == "video") t("The community recorded a thank-you video for you.", "Jamii ilirekodi video ya shukrani kwa ajili yako.") else t("The community sent you a thank-you photo.", "Jamii ilikutumia picha ya shukrani.")) + if (u.text.isNotBlank()) " “${u.text}”" else ""
    "repair" -> {
        val pr = u.problems.joinToString(", ").lowercase()
        if (u.fixed) t("It was repaired and is working again." + if (pr.isNotBlank()) " What was fixed: $pr." else "", "Imerekebishwa na inafanya kazi tena." + if (pr.isNotBlank()) " Kilichorekebishwa: $pr." else "")
        else t("Our team found a problem and is fixing it." + if (pr.isNotBlank()) " What broke: $pr." else "", "Timu yetu iligundua tatizo na inalirekebisha." + if (pr.isNotBlank()) " Kilichoharibika: $pr." else "")
    }
    "story" -> "“" + u.text + "”" + if (u.who.isNotBlank()) " — " + u.who else ""
    "anniversary" -> t("Thank you for every litre since.", "Asante kwa kila lita tangu hapo.")
    else -> ""
}

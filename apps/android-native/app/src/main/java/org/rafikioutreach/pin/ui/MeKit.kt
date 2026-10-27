package org.rafikioutreach.pin.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.AvatarImage
import org.rafikioutreach.pin.data.TeamPhotos
import org.rafikioutreach.pin.t
import java.io.File

// feat-mea: Me easy to find (a labelled pill at the top left and a one-time hint), profile photos, and every page's
// content in a centred column on wide screens.

// --- profile photos --------------------------------------------------------------------------------------------

/** [name]'s profile photo in a [size] circle when they have one (and it loads), else [initials]: what was there before. */
@Composable
internal fun PhotoOr(name: String, size: Dp, initials: @Composable () -> Unit) {
    val url = TeamPhotos.url(name)
    if (url == null) { initials(); return }
    val img by produceState<ImageBitmap?>(TeamPhotos.cached(url)?.asImageBitmap(), url, name) { value = TeamPhotos.load(name, url)?.asImageBitmap() }
    val i = img
    if (i == null) initials()
    else Image(i, null, Modifier.size(size).clip(CircleShape).testTag("photo_av"), contentScale = ContentScale.Crop)
}

/** Tests choose a picture without a camera or gallery. */
internal object PhotoPick { var fake: (() -> Bitmap?)? = null }

/**
 * Add, change or remove a profile photo: [name]'s ([self]: it's mine). Take a photo or choose one, fit it in the circle,
 * and it's shrunk on the phone (about 512 px, 200 KB or less) and saved. [saved] says what happened.
 */
@Composable
internal fun PhotoSheet(m: AppModel, name: String, self: Boolean, close: () -> Unit, saved: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberScope()
    var picked by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var askRemove by remember { mutableStateOf(false) }
    var camFile by remember { mutableStateOf<File?>(null) }
    fun use(f: File?) {
        if (f == null) return
        scope.launch {
            busy = true
            val kept = m.keepPhoto(f)
            val b = kept?.let { withContext(Dispatchers.IO) { BitmapFactory.decodeFile(it.path).also { _ -> it.delete() } } }
            busy = false
            if (b == null) msg = t("That picture couldn’t be opened. Try another one.", "Picha hiyo haikufunguka. Jaribu nyingine.") else { picked = b; msg = null }
        }
    }
    val cam = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> val f = camFile; camFile = null; if (ok) use(f) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val f = withContext(Dispatchers.IO) { runCatching { File(File(ctx.cacheDir, "cam").apply { mkdirs() }, "a${System.currentTimeMillis()}.jpg").also { f -> ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } } } }.getOrNull() }
            use(f)
        }
    }
    fun camera() {
        PhotoPick.fake?.let { picked = it(); return }
        val f = File(File(ctx.cacheDir, "cam").apply { mkdirs() }, "a${System.currentTimeMillis()}.jpg"); camFile = f
        runCatching { cam.launch(FileProvider.getUriForFile(ctx, "org.rafikioutreach.pin.files", f)) }
    }
    fun choose() { PhotoPick.fake?.let { picked = it(); return }; runCatching { gallery.launch("image/*") } }
    val has = TeamPhotos.url(name) != null
    TeamSubSheet("photo_sheet", close) { hide ->
        val src = picked
        if (src == null) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(104.dp).clip(CircleShape).background(C.card).border(1.dp, C.line, CircleShape).padding(4.dp), contentAlignment = Alignment.Center) { PersonAvatar(name, 96.dp) }
                Text(if (self) t("Your photo", "Picha yako") else t("$name’s photo", "Picha ya $name"), style = MaterialTheme.typography.titleLarge, color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp).testTag("photo_title"))
                Text(if (self) t("Your team sees it next to your name.", "Timu yako inaiona karibu na jina lako.") else t("Shown next to their name everywhere in the app.", "Inaonyeshwa karibu na jina lake kila mahali kwenye programu."),
                    style = MaterialTheme.typography.bodyMedium, color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp, start = 16.dp, end = 16.dp))
            }
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn(t("Take a photo", "Piga picha"), { camera() }, Modifier.fillMaxWidth().testTag("photo_cam"), icon = "camera", enabled = !busy)
                Btn(t("Choose from gallery", "Chagua kutoka kwenye picha"), { choose() }, Modifier.fillMaxWidth().testTag("photo_gallery"), icon = "upload", light = true, enabled = !busy)
                if (has && !askRemove) Btn(t("Remove photo", "Ondoa picha"), { askRemove = true }, Modifier.fillMaxWidth().testTag("photo_remove"), icon = "trash", light = true, outline = true, enabled = !busy)
                if (askRemove) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.badBg).padding(12.dp).testTag("photo_remove_ask")) {
                    Text(if (self) t("Remove your photo? Your initials show instead.", "Ondoa picha yako? Herufi za jina lako zitaonyeshwa badala yake.") else t("Remove $name’s photo? Their initials show instead.", "Ondoa picha ya $name? Herufi za jina lake zitaonyeshwa badala yake."),
                        style = MaterialTheme.typography.bodyMedium, color = C.ink)
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Btn(t("Cancel", "Ghairi"), { askRemove = false }, Modifier.weight(1f), light = true, small = true)
                        Btn(t("Remove", "Ondoa"), {
                            busy = true
                            scope.launch {
                                val e = TeamPhotos.remove(m.api, if (self) null else name, m.prefs.name)
                                busy = false; askRemove = false
                                if (e == null) { saved(t("Photo removed", "Picha imeondolewa")); hide {} } else msg = e
                            }
                        }, Modifier.weight(1f).testTag("photo_remove_go"), small = true, color = C.bad, enabled = !busy)
                    }
                }
            }
        } else {
            Text(t("Fit it in the circle", "Iweke ndani ya duara"), style = MaterialTheme.typography.titleLarge, color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text(t("Drag to move it, pinch to zoom.", "Buruta kuisogeza, bana kukuza."), style = MaterialTheme.typography.bodyMedium, color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
            var view by remember(src) { mutableFloatStateOf(0f) }
            var zoom by remember(src) { mutableFloatStateOf(1f) }
            var dx by remember(src) { mutableFloatStateOf(0f) }
            var dy by remember(src) { mutableFloatStateOf(0f) }
            val img = remember(src) { src.asImageBitmap() }
            fun clampAll() { val (mx, my) = AvatarImage.maxShift(src.width, src.height, view, zoom); dx = dx.coerceIn(-mx, mx); dy = dy.coerceIn(-my, my) }
            Box(Modifier.padding(top = 12.dp).fillMaxWidth().widthIn(max = 360.dp).aspectRatio(1f).align(Alignment.CenterHorizontally).clip(RoundedCornerShape(20.dp)).background(Color.Black)
                .onSizeChanged { view = it.width.toFloat(); clampAll() }
                .pointerInput(src) { detectTransformGestures { _, pan, z, _ -> zoom = (zoom * z).coerceIn(1f, 4f); dx += pan.x; dy += pan.y; clampAll() } }
                .semantics { contentDescription = t("The photo, inside a round frame", "Picha, ndani ya fremu ya duara") }.testTag("photo_crop")) {
                Canvas(Modifier.fillMaxSize()) {
                    val v = size.width
                    if (v > 0f) {
                        val s = v / minOf(src.width, src.height) * zoom
                        val w = src.width * s; val h = src.height * s
                        drawImage(img, dstOffset = IntOffset((v / 2 - w / 2 + dx).toInt(), (v / 2 - h / 2 + dy).toInt()), dstSize = IntSize(w.toInt(), h.toInt()))
                        // the round frame: darker outside it
                        val hole = Path().apply { fillType = PathFillType.EvenOdd; addRect(Rect(0f, 0f, v, v)); addOval(Rect(4f, 4f, v - 4f, v - 4f)) }
                        drawPath(hole, Color(0x99000000))
                        drawCircle(Color.White, radius = v / 2 - 4f, style = Stroke(2.dp.toPx()))
                    }
                }
            }
            Row(Modifier.padding(top = 10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("−", color = C.muted, style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics {})
                androidx.compose.material3.Slider(zoom, { zoom = it; clampAll() }, Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = t("Zoom", "Kuza") }.testTag("photo_zoom"), valueRange = 1f..4f)
                Text("+", color = C.muted, style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics {})
            }
            Btn(if (busy) t("Saving…", "Inahifadhi…") else t("Use photo", "Tumia picha"), {
                busy = true; msg = null
                scope.launch {
                    val bytes = withContext(Dispatchers.Default) { AvatarImage.jpeg(AvatarImage.crop(src, AvatarImage.cropRect(src.width, src.height, view.coerceAtLeast(1f), zoom, dx, dy))) }
                    val (url, e) = TeamPhotos.upload(m.api, if (self) null else name, bytes)
                    busy = false
                    if (url != null) { saved(t("Photo saved ✓", "Picha imehifadhiwa ✓")); hide {} } else msg = e
                }
            }, Modifier.padding(top = 8.dp).fillMaxWidth().testTag("photo_use"), icon = "check", enabled = !busy)
            Btn(t("Choose another", "Chagua nyingine"), { picked = null; msg = null }, Modifier.padding(top = 10.dp).fillMaxWidth().testTag("photo_again"), light = true, enabled = !busy)
        }
        msg?.let { Box(Modifier.padding(top = 10.dp).testTag("photo_msg")) { Msg(it, "bad") } }
    }
}

// --- the Me pill (top left) ---------------------------------------------------------------------------------------

/** Where the Me pill is on the screen, for the hint to point at (null while Back is there instead). */
object MeHint { var anchor by mutableStateOf<Rect?>(null) }

/** The pill's widths, in dp: with the word, and with the person icon only. */
private fun pillWidth(word: Float) = 4f + 34f + 6f + 16f + (if (word > 0f) 4f + word else 0f) + 12f

/**
 * The app header's three parts: [left] (Me or Back), the wordmark or title in the middle (centred on the screen when
 * there's room, never under the sides), and the bell. [left] gets whether there's room for the word "Me".
 */
@Composable
internal fun HeaderRow(left: @Composable (word: Boolean) -> Unit, middle: @Composable () -> Unit, right: @Composable () -> Unit) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold)
    val dens = LocalDensity.current
    val logo = painterResource(org.rafikioutreach.pin.R.drawable.rafiki_logo_dark)
    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp)) {
        val wordW = with(dens) { measurer.measure(t("Me", "Mimi"), style).size.width.toDp().value }
        val pinW = with(dens) { measurer.measure("Pin", MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = (24f * 0.72f).sp)).size.width.toDp().value }
        val markW = 24f * logo.intrinsicSize.width / logo.intrinsicSize.height.coerceAtLeast(1f) + 8f + pinW
        // the word fits when the pill, the wordmark (with its 8 dp each side) and the bell all fit on one line
        val word = maxWidth.value >= pillWidth(wordW) + 16f + markW + 46f + 4f
        Layout({ Box { left(word) }; Box { middle() }; Box { right() } }, Modifier.fillMaxWidth().heightIn(min = 56.dp)) { ms, c ->
            val gap = 8.dp.roundToPx()
            val l = ms[0].measure(c.copy(minWidth = 0, minHeight = 0)); val r = ms[2].measure(c.copy(minWidth = 0, minHeight = 0))
            val room = (c.maxWidth - l.width - r.width - 2 * gap).coerceAtLeast(0)
            val mid = ms[1].measure(c.copy(minWidth = 0, minHeight = 0, maxWidth = room))
            val h = maxOf(c.minHeight, l.height, r.height, mid.height)
            layout(c.maxWidth, h) {
                l.place(0, (h - l.height) / 2)
                r.place(c.maxWidth - r.width, (h - r.height) / 2)
                val lo = l.width + gap; val hi = c.maxWidth - r.width - gap - mid.width
                mid.place(((c.maxWidth - mid.width) / 2).coerceIn(lo, maxOf(lo, hi)), (h - mid.height) / 2)
            }
        }
    }
}

/**
 * Me, top left: the photo (or initials), a person icon and the word Me (Mimi), as one pill, so it reads as a button.
 * Lit while Me is open; it carries what's waiting to send and the app-update dot. [word] false on very narrow screens.
 */
@Composable
internal fun MePill(m: AppModel, on: Boolean, waiting: Int, updReady: Boolean, word: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(99.dp)
    val hu = Hue(C.blue2, C.tint)
    Box(Modifier.testTag("hav").onGloballyPositioned { MeHint.anchor = it.boundsInRoot() }) {
        Row(Modifier.heightIn(min = 44.dp).clip(shape).background(if (on) hu.bg else C.card.copy(alpha = if (C.glassy) .85f else 1f))
            .border(if (on) 2.dp else 1.dp, if (on) C.blue2 else C.line, shape)
            .clickable(role = Role.Tab, onClick = onClick).semantics { contentDescription = t("Me", "Mimi"); selected = on }.testTag("nav_me")
            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // the face: a photo once it has loaded, else initials (the tag says which, for tests)
            val face = TeamPhotos.tick.let { TeamPhotos.cached(TeamPhotos.url(m.prefs.name)) != null }
            Box(Modifier.clearAndSetSemantics { testTag = if (face) "me_face_photo" else "me_face_initials" }) {
                PhotoOr(m.prefs.name, 34.dp) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF47B4F5), Color(0xFF0A78C2)))), contentAlignment = Alignment.Center) {
                        Text(initialsOf(m.prefs.name).takeIf { m.prefs.name.isNotBlank() } ?: "", color = Color.White, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp), maxLines = 1, softWrap = false)
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            Icon(Ic.get("person"), null, tint = if (on) hu.fg else C.ink, modifier = Modifier.size(16.dp).testTag("me_person"))
            if (word) {
                Spacer(Modifier.width(4.dp))
                Text(t("Me", "Mimi"), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold), color = if (on) hu.fg else C.ink, maxLines = 1, softWrap = false,
                    modifier = Modifier.clearAndSetSemantics { testTag = "me_word" })
            }
        }
        // on the picture's corner, as on the old round button
        if (waiting == 0 && updReady) Box(Modifier.offset(30.dp, 0.dp).size(12.dp).clip(CircleShape).background(Color.White).padding(2.dp).clip(CircleShape).background(C.blue).testTag("update_dot"))
        if (waiting > 0) Text("$waiting", color = Color.White, fontSize = 10.sp, maxLines = 1, softWrap = false, modifier = Modifier.offset(26.dp, (-4).dp).clip(RoundedCornerShape(99.dp)).background(C.warn).padding(horizontal = 5.dp).testTag("waiting_badge"))
    }
    DisposableEffect(Unit) { onDispose { MeHint.anchor = null } }
}

// --- the one-time hint ----------------------------------------------------------------------------------------------

/** A tap anywhere closes the hint, and still does what it would have done (the map's first tap isn't lost). */
internal fun Modifier.closeOnAnyTap(on: () -> Boolean, close: () -> Unit): Modifier = pointerInput(Unit) {
    // always in place (adding or removing it mid-touch would cancel that touch for what's underneath); only looks
    awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); if (on()) close() }
}

/** "Me is here now": a speech bubble under the Me pill, the first time this version opens. Got it, a tap anywhere or Back closes it for good. */
@Composable
internal fun MeHintBubble(close: () -> Unit) {
    val a = MeHint.anchor ?: return
    BackHandler { close() }
    val d = LocalDensity.current
    var shown by remember { mutableStateOf(!Motion.on) }
    LaunchedEffect(Unit) { shown = true }
    val pointX = with(d) { (a.left + 21.dp.toPx()).toDp() }   // the middle of the picture
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(shown, enter = if (Motion.on) fadeIn(tween(220)) + scaleIn(tween(260), initialScale = 0.9f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)) else fadeIn(tween(0)), exit = fadeOut(tween(0)),
            modifier = Modifier.offset(x = with(d) { a.left.toDp() }, y = with(d) { a.bottom.toDp() } + 6.dp)) {
            val bg = Color(0xFF054F81)
            Column(Modifier.widthIn(max = 300.dp).padding(end = 16.dp).testTag("me_hint")) {
                // the little point up at the pill
                Box(Modifier.padding(start = (pointX - with(d) { a.left.toDp() } - 8.dp).coerceAtLeast(8.dp)).size(16.dp, 9.dp).background(bg, GenericShape { s, _ -> moveTo(s.width / 2, 0f); lineTo(s.width, s.height); lineTo(0f, s.height); close() }))
                Column(Modifier.shadow(14.dp, RoundedCornerShape(18.dp), spotColor = Color(0x55054F81)).clip(RoundedCornerShape(18.dp)).background(bg)
                    .then(if (!C.glassy) Modifier.border(2.dp, Color.Black, RoundedCornerShape(18.dp)) else Modifier).padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 10.dp)) {
                    Text(t("Me is here now: your work, settings and team", "Mimi iko hapa sasa: kazi yako, mipangilio na timu"), color = Color.White, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(end = 6.dp).testTag("me_hint_text"))
                    Box(Modifier.align(Alignment.End).padding(top = 6.dp).heightIn(min = 44.dp).clip(RoundedCornerShape(99.dp)).clickable(role = Role.Button) { close() }.testTag("me_hint_ok"), contentAlignment = Alignment.Center) {
                        Text(t("Got it", "Sawa"), color = bg, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold), maxLines = 1, softWrap = false,
                            modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            }
        }
    }
}

// --- centred content -----------------------------------------------------------------------------------------------

/** The widest a page's content gets on a tablet or a phone on its side: lists and forms read as one centred column. */
val CONTENT_MAX = 720.dp

/** Tests switch the centring off for a "before" picture. */
internal object CentreLayout { var on by mutableStateOf(true) }

/** A page's content, centred in a column no wider than [CONTENT_MAX] (phones: the full width, as before). */
@Composable
internal fun Centred(full: Boolean = false, content: @Composable () -> Unit) {
    if (full || !CentreLayout.on) { Box(Modifier.fillMaxSize()) { content() }; return }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) { Box(Modifier.widthIn(max = CONTENT_MAX).fillMaxSize().testTag("centred")) { content() } }
}

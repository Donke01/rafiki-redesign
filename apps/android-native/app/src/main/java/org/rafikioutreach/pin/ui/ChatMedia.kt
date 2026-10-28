package org.rafikioutreach.pin.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.graphics.graphicsLayer
import org.rafikioutreach.pin.data.saverOn
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.ChatMedia
import org.rafikioutreach.pin.data.Msg
import org.rafikioutreach.pin.data.clock
import org.rafikioutreach.pin.t
import java.io.File
import java.io.FileOutputStream

/** Size caps (the same as the server): photo 2 MB after compression, video 16 MB and 60 s, voice 5 min, document 10 MB. */
object ChatCaps {
    const val PHOTO = 2L * 1024 * 1024
    const val VIDEO = 16L * 1024 * 1024
    const val VIDEO_SECONDS = 60
    const val VOICE_SECONDS = 300
    const val DOC = 10L * 1024 * 1024
}

/** Files waiting to be sent live here until the server has them. */
internal fun outDir(ctx: Context) = File(File(ctx.filesDir, "chat"), "chat-out").apply { mkdirs() }

// --- photos and videos: picked, shrunk, ready to send -----------------------------------------------------------------
internal object ChatPrep {
    /** A photo turned upright, at most 1600 px on its longest side and under 2 MB (JPEG, quality stepped down if needed).
     *  [saver] (chat 2, B15: the data saver is on, e.g. on mobile data): at most 960 px and quality 70; on Wi-Fi full size. */
    fun photo(ctx: Context, src: File, maxSide0: Int = 1600, saver: Boolean = false): ChatMedia? = runCatching {
        val maxSide = if (saver) minOf(maxSide0, 960) else maxSide0
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(src.path, o)
        if (o.outWidth <= 0) return null
        var sample = 1; while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        var bmp = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val k = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        if (k < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
        val rot = runCatching { when (ExifInterface(src.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) { ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f } }.getOrDefault(0f)
        if (rot != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
        val out = File(outDir(ctx), "p${System.nanoTime()}.jpg")
        var q = if (saver) 70 else 82
        do { FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, q, it) }; q -= 12 } while (out.length() > ChatCaps.PHOTO && q > 30)
        // a small preview (longest side 320 px) for people with the data saver on (chatpa, contract §8)
        val thumb = if (maxSide > 320) runCatching {
            val k2 = 320f / maxOf(bmp.width, bmp.height)
            val tb = if (k2 < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * k2).toInt().coerceAtLeast(1), (bmp.height * k2).toInt().coerceAtLeast(1), true) else bmp
            File(outDir(ctx), "t${System.nanoTime()}.jpg").also { f -> FileOutputStream(f).use { tb.compress(Bitmap.CompressFormat.JPEG, 60, it) } }.path
        }.getOrDefault("") else ""
        ChatMedia("", "photo", bmp.width, bmp.height, size = out.length(), local = out.path, name = out.name, mime = "image/jpeg", thumb = thumb)
    }.getOrNull()

    /** A content:// picked from the gallery, copied to a file first. */
    fun copy(ctx: Context, uri: Uri, ext: String): File? = runCatching {
        val f = File(File(ctx.cacheDir, "cam").apply { mkdirs() }, "g${System.nanoTime()}.$ext")
        ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }; f
    }.getOrNull()

    fun isVideo(ctx: Context, uri: Uri) = ctx.contentResolver.getType(uri)?.startsWith("video") == true || uri.toString().contains("video", true)

    /** A video: at most 60 seconds; shrunk to 720p when it's big; a small poster picture to show before it's played.
     *  Returns the media, or why it can't be sent. */
    suspend fun video(ctx: Context, src: File): Pair<ChatMedia?, String?> = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(src.path)
            val dur = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val x = w; w = h; h = x }
            if (dur > ChatCaps.VIDEO_SECONDS + 0.5) return@withContext null to t("Videos can be up to 60 seconds. Trim it on your phone first.", "Video zinaweza kuwa hadi sekunde 60. Ifupishe kwenye simu yako kwanza.")
            val frame = r.getFrameAtTime(0)
            val posterFile = frame?.let { b ->
                val k = 640f / maxOf(b.width, b.height); val s = if (k < 1f) Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true) else b
                File(outDir(ctx), "v${System.nanoTime()}.jpg").also { f -> FileOutputStream(f).use { s.compress(Bitmap.CompressFormat.JPEG, 70, it) } }
            }
            // big or sharper than 720p: shrink it (Media3 Transformer, H.264 720p)
            var file = src
            if (src.length() > 8L * 1024 * 1024 || minOf(w, h) > 720) VideoShrink.to720(ctx, src)?.let { file = it; if (minOf(w, h) > 720) { val k = 720f / minOf(w, h); w = (w * k).toInt(); h = (h * k).toInt() } }
            if (file.length() > ChatCaps.VIDEO) return@withContext null to t("That video is too big to send (over 16 MB). Try a shorter one.", "Video hiyo ni kubwa mno kutuma (zaidi ya MB 16). Jaribu fupi zaidi.")
            val out = if (file.path.startsWith(outDir(ctx).path)) file else File(outDir(ctx), "v${System.nanoTime()}.mp4").also { file.copyTo(it, overwrite = true) }
            ChatMedia("", "video", w, h, dur, name = out.name, size = out.length(), local = out.path, poster = posterFile?.path.orEmpty(), mime = "video/mp4") to null
        } catch (e: Exception) {
            null to t("That video couldn’t be read.", "Video hiyo haikuweza kusomwa.")
        } finally { runCatching { r.release() } }
    }

    /** A document (PDF and the like), up to 10 MB. */
    fun doc(ctx: Context, uri: Uri): Pair<ChatMedia?, String?> {
        val name = runCatching { ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull() ?: "document"
        val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
        val f = File(outDir(ctx), "d${System.nanoTime()}_" + name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        runCatching { ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } } }.onFailure { return null to t("That file couldn’t be read.", "Faili hilo halikuweza kusomwa.") }
        if (f.length() > ChatCaps.DOC) { f.delete(); return null to t("Documents can be up to 10 MB.", "Hati zinaweza kuwa hadi MB 10.") }
        return ChatMedia("", "doc", name = name, size = f.length(), local = f.path, mime = mime) to null
    }
}

/** Shrinks a video to 720p H.264 with Media3 Transformer (on the phone, before upload). Null if it couldn't. */
internal object VideoShrink {
    suspend fun to720(ctx: Context, src: File): File? = withContext(Dispatchers.Main) {
        val out = File(outDir(ctx), "s${System.nanoTime()}.mp4")
        val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
        runCatching {
            val tr = androidx.media3.transformer.Transformer.Builder(ctx)
                .setVideoMimeType(androidx.media3.common.MimeTypes.VIDEO_H264)
                .addListener(object : androidx.media3.transformer.Transformer.Listener {
                    override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: androidx.media3.transformer.ExportResult) { done.complete(true) }
                    override fun onError(composition: androidx.media3.transformer.Composition, exportResult: androidx.media3.transformer.ExportResult, exportException: androidx.media3.transformer.ExportException) { done.complete(false) }
                }).build()
            val item = androidx.media3.transformer.EditedMediaItem.Builder(androidx.media3.common.MediaItem.fromUri(Uri.fromFile(src)))
                .setEffects(androidx.media3.transformer.Effects(emptyList(), listOf(androidx.media3.effect.Presentation.createForHeight(720)))).build()
            tr.start(item, out.path)
        }.onFailure { done.complete(false) }
        if (kotlinx.coroutines.withTimeoutOrNull(180_000) { done.await() } == true && out.length() > 0) out else { out.delete(); null }
    }
}

// --- pictures on screen ---------------------------------------------------------------------------------------------
internal object ChatPics {
    private val mem = object : LruCache<String, Bitmap>(20 * 1024 * 1024) { override fun sizeOf(k: String, v: Bitmap) = v.byteCount }
    fun cached(path: String, max: Int) = mem.get("$path@$max")
    fun load(path: String, max: Int): Bitmap? = mem.get("$path@$max") ?: runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(path, o)
        var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= max) s *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    }.getOrNull()?.also { mem.put("$path@$max", it) }
}

/** A photo (or a video's poster) from a message: from the phone, or downloaded once. */
@Composable
internal fun ChatImage(m: AppModel, md: ChatMedia, modifier: Modifier, max: Int = 720, crop: Boolean = true, full: Boolean = false) {
    val ctx = LocalContext.current
    val tick by m.chat.settingsTick.collectAsState()
    val local = if (md.type == "video") md.poster.takeIf { it.startsWith("/") } else md.local.takeIf { it.isNotBlank() }
    // the data saver (chatpa): a photo not on the phone yet shows its small preview, or its size and "Tap to load";
    // the full photo comes when it's opened
    val saving = remember(md.id, tick) { md.type == "photo" && !full && local == null && saverOn(ctx, m.chat.settings.saver) && m.chat.cachedFile(md) == null }
    val hasThumb = md.thumb.isNotBlank() || md.thumbUrl.isNotBlank()
    var bmp by remember(md.id, md.local, md.poster, saving) { mutableStateOf(local?.let { ChatPics.cached(it, max) }?.asImageBitmap()) }
    LaunchedEffect(md.id, md.local, md.poster, saving) {
        if (bmp != null) return@LaunchedEffect
        if (saving && !hasThumb) return@LaunchedEffect
        val f = withContext(Dispatchers.IO) { local?.let { File(it) }?.takeIf { it.exists() } ?: if (saving) m.chat.file(md, thumbOnly = true) else if (md.type == "video") m.chat.file(md, posterOnly = true) else m.chat.file(md) }
        bmp = f?.let { withContext(Dispatchers.IO) { ChatPics.load(it.path, max) } }?.asImageBitmap()
    }
    Box(modifier.background(if (C.dark) Color(0xFF26323C) else Color(0xFFDCE6EE)), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it, null, contentScale = if (crop) ContentScale.Crop else ContentScale.Fit, modifier = Modifier.matchParentSize().then(if (saving) Modifier.testTag("thumb_shown") else Modifier)) }
            ?: if (saving && !hasThumb) Column(Modifier.testTag("tap_load"), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Ic.get("down"), null, tint = C.muted, modifier = Modifier.size(24.dp))
                Text(listOf(org.rafikioutreach.pin.data.dataText(md.size).takeIf { md.size > 0 }, t("Tap to load", "Gusa kupakia")).filterNotNull().joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = C.muted, maxLines = 1, softWrap = false)
            } else Icon(Ic.get(if (md.type == "video") "play" else "camera"), null, tint = C.muted, modifier = Modifier.size(26.dp))
        if (md.type == "video") {
            Box(Modifier.size(46.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) { Icon(Ic.get("play"), null, tint = Color.White, modifier = Modifier.size(24.dp)) }
            Text(clock(md.dur), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x88000000)).padding(horizontal = 5.dp, vertical = 1.dp))
        }
    }
}

/** Several photos sent together: one large, two side by side, three as one big and two small, four or more as a grid with "+3". */
@Composable
internal fun PhotoGrid(m: AppModel, media: List<ChatMedia>, width: androidx.compose.ui.unit.Dp, open: (Int) -> Unit) {
    val gap = 3.dp
    val r = RoundedCornerShape(14.dp)
    @Composable fun cell(i: Int, mod: Modifier) {
        Box(mod.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Image) { open(i) }.testTag("media_$i")) {
            ChatImage(m, media[i], Modifier.fillMaxSize())
            if (i == 3 && media.size > 4) Box(Modifier.matchParentSize().background(Color(0x88000000)), contentAlignment = Alignment.Center) {
                Text("+${media.size - 4}", color = Color.White, style = MaterialTheme.typography.headlineMedium)
            }
        }
    }
    Box(Modifier.width(width).clip(r)) {
        when (media.size) {
            1 -> { val md = media[0]; val ratio = if (md.w > 0 && md.h > 0) (md.w.toFloat() / md.h).coerceIn(0.6f, 1.8f) else 1.33f; cell(0, Modifier.fillMaxWidth().aspectRatio(ratio)) }
            2 -> Row(horizontalArrangement = Arrangement.spacedBy(gap)) { cell(0, Modifier.weight(1f).aspectRatio(0.8f)); cell(1, Modifier.weight(1f).aspectRatio(0.8f)) }
            3 -> Row(Modifier.height(width * 0.75f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                cell(0, Modifier.weight(1.4f).fillMaxHeight())
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(gap)) { cell(1, Modifier.weight(1f).fillMaxWidth()); cell(2, Modifier.weight(1f).fillMaxWidth()) }
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) { cell(0, Modifier.weight(1f).aspectRatio(1f)); cell(1, Modifier.weight(1f).aspectRatio(1f)) }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) { cell(2, Modifier.weight(1f).aspectRatio(1f)); cell(3, Modifier.weight(1f).aspectRatio(1f)) }
            }
        }
    }
}

/** Full screen: swipe between every photo and video in the chat. Back closes it. */
@Composable
internal fun MediaViewer(m: AppModel, items: List<Pair<Msg, ChatMedia>>, start: Int, close: () -> Unit) {
    BackStep(true) { close() }
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (items.size - 1).coerceAtLeast(0))) { items.size }
    var zoomed by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { } }.testTag("media_viewer")) {
        HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed) { i ->
            val (msg, md) = items[i]
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (md.type == "video") VideoPage(m, md, pager.currentPage == i) else ZoomBox({ zoomed = it }, close) { ChatImage(m, md, Modifier.fillMaxSize(), max = 1600, crop = false, full = true) }
                if (msg.text.isNotBlank() && msg.media.firstOrNull() == md) Text(msg.text, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x88000000)).padding(16.dp))
            }
        }
        val (msg, _) = items.getOrNull(pager.currentPage) ?: return@Box
        Row(Modifier.fillMaxWidth().background(Color(0x66000000)).statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) { close() }.semantics { contentDescription = t("Close", "Funga") }.testTag("viewer_close"), contentAlignment = Alignment.Center) {
                Icon(Ic.get("x"), null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                Text(if (msg.from.equals(m.chat.me, true)) t("You", "Wewe") else msg.from, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(Dates.dateTime(java.time.Instant.ofEpochMilli(msg.at)), color = Color(0xCCFFFFFF), style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            Text("${pager.currentPage + 1} / ${items.size}", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp).testTag("viewer_count"))
        }
    }
}

/**
 * A photo in the viewer (chatpa): pinch or double-tap to zoom, drag to pan while zoomed, swipe down to close.
 * Sideways swipes go to the pager while it isn't zoomed.
 */
@Composable
private fun ZoomBox(zoomed: (Boolean) -> Unit, close: () -> Unit, content: @Composable () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var off by remember { mutableStateOf(Offset.Zero) }
    var drop by remember { mutableFloatStateOf(0f) }
    val motion = chatMotion()
    val shownScale by androidx.compose.animation.core.animateFloatAsState(scale, androidx.compose.animation.core.tween(if (motion) 220 else 0), label = "zoom")
    LaunchedEffect(scale > 1f) { zoomed(scale > 1f) }
    BoxWithConstraints(Modifier.fillMaxSize().testTag(if (scale > 1f) "zoomed" else "zoom_box")
        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { p -> if (scale > 1f) { scale = 1f; off = Offset.Zero } else { scale = 2.5f; off = (Offset(size.width / 2f, size.height / 2f) - p) * 1.5f } }) }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var vertical: Boolean? = null
                do {
                    val ev = awaitPointerEvent()
                    val zoom = ev.calculateZoom(); val pan = ev.calculatePan()
                    val fingers = ev.changes.count { it.pressed }
                    if (fingers > 1 || scale > 1f) {
                        scale = (scale * zoom).coerceIn(1f, 4f)
                        val mx = size.width * (scale - 1f) / 2f; val my = size.height * (scale - 1f) / 2f
                        off = if (scale > 1f) Offset((off.x + pan.x).coerceIn(-mx, mx), (off.y + pan.y).coerceIn(-my, my)) else Offset.Zero
                        ev.changes.forEach { if (it.positionChanged()) it.consume() }
                    } else {
                        if (vertical == null && (kotlin.math.abs(pan.x) > 2f || kotlin.math.abs(pan.y) > 2f)) vertical = kotlin.math.abs(pan.y) > kotlin.math.abs(pan.x) && pan.y > 0
                        if (vertical == true) { drop = (drop + pan.y).coerceAtLeast(0f); ev.changes.forEach { it.consume() } }
                    }
                } while (ev.changes.any { it.pressed })
                if (drop > 110.dp.toPx()) { drop = 0f; close() } else drop = 0f
            }
        }
        .graphicsLayer { scaleX = shownScale; scaleY = shownScale; translationX = off.x; translationY = off.y + drop; alpha = 1f - (drop / size.height.coerceAtLeast(1f)).coerceIn(0f, 0.6f) }) {
        content()
    }
}

/** Cuts a voice note's end on the phone (chatpa): the audio up to [end] seconds, copied without re-encoding. */
internal object VoiceTrim {
    fun cut(md: ChatMedia, end: Double): ChatMedia {
        val n = if (md.dur > 0) (md.wave.size * end / md.dur).toInt().coerceIn(1, md.wave.size.coerceAtLeast(1)) else md.wave.size
        val wave = if (md.wave.isEmpty()) md.wave else VoiceRec.bars(md.wave.take(n), 40)
        val src = File(md.local)
        val out = File(src.parentFile, "t${System.nanoTime()}.m4a")
        val ok = runCatching {
            val ex = android.media.MediaExtractor(); ex.setDataSource(src.path)
            val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ex.selectTrack(track)
            val mux = android.media.MediaMuxer(out.path, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val ti = mux.addTrack(ex.getTrackFormat(track)); mux.start()
            val buf = java.nio.ByteBuffer.allocate(256 * 1024); val info = android.media.MediaCodec.BufferInfo()
            val endUs = (end * 1_000_000).toLong()
            while (true) {
                val size = ex.readSampleData(buf, 0); if (size < 0) break
                if (ex.sampleTime > endUs) break
                info.set(0, size, ex.sampleTime, if (ex.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC != 0) android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                mux.writeSampleData(ti, buf, info); ex.advance()
            }
            mux.stop(); mux.release(); ex.release(); out.length() > 0
        }.getOrDefault(false)
        return if (ok) { src.delete(); md.copy(local = out.path, name = out.name, size = out.length(), dur = end, wave = wave) } else { out.delete(); md.copy(dur = end, wave = wave) }
    }
}

/** A video in the viewer: downloaded once, then played with the phone's player (pause by tapping). */
@Composable
private fun VideoPage(m: AppModel, md: ChatMedia, current: Boolean) {
    var file by remember(md.id) { mutableStateOf<File?>(md.local.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }) }
    LaunchedEffect(md.id) { if (file == null) file = m.chat.file(md) }
    val f = file
    if (f == null) { ChatImage(m, md, Modifier.fillMaxWidth().aspectRatio(if (md.w > 0 && md.h > 0) md.w.toFloat() / md.h else 1.77f), crop = false); return }
    androidx.compose.ui.viewinterop.AndroidView({ c -> android.widget.VideoView(c).apply { setVideoPath(f.path); setMediaController(android.widget.MediaController(c).also { mc -> mc.setAnchorView(this) }); setOnPreparedListener { it.isLooping = false } } },
        Modifier.fillMaxWidth().aspectRatio(if (md.w > 0 && md.h > 0) md.w.toFloat() / md.h else 1.77f), update = { v -> if (current) v.start() else v.pause() })
}

// --- voice notes ------------------------------------------------------------------------------------------------------
/** Recording a voice note: AAC in an .m4a, its loudness sampled for the waveform. */
internal class VoiceRec(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    var file: File? = null; private set
    var started = 0L; private set
    val levels = mutableStateListOf<Int>()
    fun start(): Boolean {
        val f = File(outDir(ctx), "a${System.nanoTime()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        return runCatching {
            r.setAudioSource(MediaRecorder.AudioSource.MIC); r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(48000); r.setAudioSamplingRate(44100); r.setMaxDuration(ChatCaps.VOICE_SECONDS * 1000); r.setOutputFile(f.path)
            r.prepare(); r.start(); rec = r; file = f; started = System.currentTimeMillis(); levels.clear(); true
        }.getOrElse { r.release(); false }
    }
    /** Called every ~100 ms while recording. */
    fun sample() { rec?.let { levels += (runCatching { it.maxAmplitude }.getOrDefault(0) * 100 / 22000).coerceIn(2, 100) } }
    fun seconds() = if (started == 0L) 0.0 else (System.currentTimeMillis() - started) / 1000.0
    /** Stops; returns the note ready to send, or null if it was too short (under a second) or failed. */
    fun stop(): ChatMedia? {
        val r = rec ?: return null; rec = null
        val dur = seconds()
        val ok = runCatching { r.stop() }.isSuccess; r.release()
        val f = file ?: return null
        if (!ok || dur < 0.8) { f.delete(); return null }
        return ChatMedia("", "voice", dur = dur, wave = bars(levels, 40), name = f.name, size = f.length(), local = f.path, mime = "audio/mp4")
    }
    fun cancel() { rec?.let { runCatching { it.stop() }; it.release() }; rec = null; file?.delete(); file = null }
    val active get() = rec != null

    companion object {
        /** The levels squeezed (or stretched) to [n] bars. */
        fun bars(l: List<Int>, n: Int): List<Int> = if (l.isEmpty()) List(n) { 8 } else List(n) { i ->
            val a = i * l.size / n; val b = ((i + 1) * l.size / n).coerceAtLeast(a + 1).coerceAtMost(l.size)
            l.subList(a.coerceAtMost(l.size - 1), b).maxOrNull() ?: 8
        }
    }
}

/** One player for every voice note: playing one stops another. Speed 1×, 1.5× or 2× (kept for the next note too). */
internal object VoicePlayer {
    data class State(val key: String = "", val playing: Boolean = false, val pos: Double = 0.0, val dur: Double = 0.0)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    var speed by mutableFloatStateOf(1f)
    private var mp: MediaPlayer? = null
    fun toggle(key: String, file: File, dur: Double) {
        val s = _state.value
        if (s.key == key && mp != null) { if (s.playing) { mp?.pause(); _state.value = s.copy(playing = false) } else { mp?.start(); applySpeed(); _state.value = s.copy(playing = true) }; return }
        stop()
        val p = MediaPlayer()
        runCatching {
            p.setDataSource(file.path); p.prepare()
            if (s.key == key && s.pos > 0) p.seekTo((s.pos * 1000).toInt())
            p.setOnCompletionListener { _state.value = State(key, false, 0.0, dur); runCatching { it.release() }; mp = null }
            mp = p; p.start(); applySpeed()
            _state.value = State(key, true, p.currentPosition / 1000.0, (p.duration / 1000.0).takeIf { it > 0 } ?: dur)
        }.onFailure { runCatching { p.release() } }
    }
    fun seek(key: String, frac: Float, dur: Double) {
        val s = _state.value
        if (s.key == key && mp != null) { runCatching { mp?.seekTo((frac * s.dur * 1000).toInt()) }; _state.value = s.copy(pos = frac * s.dur) }
        else _state.value = State(key, false, frac * dur, dur)
    }
    fun cycleSpeed() { speed = when (speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }; applySpeed() }
    private fun applySpeed() { if (Build.VERSION.SDK_INT >= 23) mp?.let { p -> runCatching { val play = p.isPlaying; p.playbackParams = p.playbackParams.setSpeed(speed); if (!play) p.pause() } } }
    fun tick() { val p = mp ?: return; val s = _state.value; if (s.playing) _state.value = s.copy(pos = runCatching { p.currentPosition / 1000.0 }.getOrDefault(s.pos)) }
    fun stop() { mp?.let { runCatching { it.stop() }; it.release() }; mp = null; _state.value = _state.value.copy(playing = false) }
}

/** The waveform: one bar per slice of the note, the played part coloured; tap or drag to jump. */
@Composable
internal fun Waveform(bars: List<Int>, progress: Float, played: Color, rest: Color, modifier: Modifier, seek: ((Float) -> Unit)? = null) {
    val list = if (bars.isEmpty()) List(40) { 10 + (it * 37 % 50) } else bars
    Canvas(modifier.then(if (seek != null) Modifier.pointerInput(Unit) {
        detectTapGestures { o -> seek((o.x / size.width).coerceIn(0f, 1f)) }
    }.pointerInput("drag") { detectHorizontalDragGestures { ch, _ -> seek((ch.position.x / size.width).coerceIn(0f, 1f)) } } else Modifier)) {
        val n = list.size; val slot = size.width / n; val bw = (slot * 0.62f).coerceAtLeast(1.5f)
        list.forEachIndexed { i, v ->
            val h = (size.height * (0.12f + 0.88f * v / 100f)).coerceAtMost(size.height)
            val x = i * slot + (slot - bw) / 2
            drawRoundRect(if ((i + 0.5f) / n <= progress) played else rest, Offset(x, (size.height - h) / 2), Size(bw, h), CornerRadius(bw / 2, bw / 2))
        }
    }
}

/** A voice note in a bubble: play/pause, the waveform with what's played, the time, and the speed (1×, 1.5×, 2×). */
@Composable
internal fun VoiceBubble(m: AppModel, msg: Msg, md: ChatMedia, mine: Boolean) {
    val st by VoicePlayer.state.collectAsState()
    val me = st.key == msg.key
    var file by remember(md.id, md.local) { mutableStateOf(md.local.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberScope()
    val hp = rememberHaptics()
    LaunchedEffect(me && st.playing) { while (me && st.playing) { VoicePlayer.tick(); delay(80) } }
    // voice notes load by themselves, also with the data saver on (they are small)
    LaunchedEffect(md.id) { if (file == null && md.id.isNotBlank() && !md.id.startsWith("/")) m.chat.file(md)?.let { file = it } }
    val accent = if (mine) C.blue else ChatLook.online
    Row(Modifier.widthIn(min = 220.dp, max = 260.dp).padding(top = 2.dp).testTag("voice_${msg.key}"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(accent).clickable(role = Role.Button) {
            hp.tap()
            val f = file
            if (f != null) VoicePlayer.toggle(msg.key, f, md.dur)
            else if (!loading) { loading = true; scope.launch { file = m.chat.file(md); loading = false; file?.let { VoicePlayer.toggle(msg.key, it, md.dur) } } }
        }.semantics { contentDescription = if (me && st.playing) t("Pause", "Simamisha") else t("Play voice message", "Cheza ujumbe wa sauti") }.testTag("voice_play"), contentAlignment = Alignment.Center) {
            if (loading) androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            else Icon(Ic.get(if (me && st.playing) "pause" else "play"), null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            val dur = if (me && st.dur > 0) st.dur else md.dur
            val prog = if (me && dur > 0) (st.pos / dur).toFloat().coerceIn(0f, 1f) else 0f
            Waveform(md.wave, prog, accent, if (C.dark) Color(0x55FFFFFF) else Color(0x40000000), Modifier.fillMaxWidth().height(30.dp).testTag("waveform")) { f -> VoicePlayer.seek(msg.key, f, md.dur) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(clock(if (me && (st.playing || st.pos > 0)) st.pos else md.dur), style = MaterialTheme.typography.labelSmall, color = C.muted, modifier = Modifier.weight(1f))
            }
        }
        // the speed: 1× → 1.5× → 2×
        val sp = VoicePlayer.speed
        Text(if (sp == 1f) "1×" else if (sp == 1.5f) "1.5×" else "2×", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold), color = if (sp == 1f) C.muted else C.card,
            maxLines = 1, softWrap = false,
            modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(99.dp)).background(if (sp == 1f) (if (C.dark) Color(0x22FFFFFF) else Color(0x14000000)) else accent)
                .clickable(role = Role.Button) { hp.tap(); VoicePlayer.cycleSpeed() }.semantics { contentDescription = t("Playback speed", "Kasi ya kucheza") }.padding(horizontal = 9.dp, vertical = 5.dp).testTag("voice_speed"))
    }
}

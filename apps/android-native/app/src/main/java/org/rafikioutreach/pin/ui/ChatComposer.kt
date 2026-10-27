package org.rafikioutreach.pin.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.data.ChatMedia
import org.rafikioutreach.pin.data.ChatRow
import org.rafikioutreach.pin.data.Msg
import org.rafikioutreach.pin.data.clock
import org.rafikioutreach.pin.data.preview
import org.rafikioutreach.pin.t
import java.io.File
import kotlin.math.roundToInt

/** Record a video with the phone's camera app: at most 60 seconds. */
private class CaptureVideo60 : ActivityResultContract<Uri, Boolean>() {
    override fun createIntent(context: Context, input: Uri) = Intent(MediaStore.ACTION_VIDEO_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, input)
        .putExtra(MediaStore.EXTRA_DURATION_LIMIT, ChatCaps.VIDEO_SECONDS).putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    override fun parseResult(resultCode: Int, intent: Intent?) = resultCode == android.app.Activity.RESULT_OK
}

/** Tests can pretend a microphone. */
internal object MicTest { var pretend = false }

/**
 * Writing: text with @mentions (a list pops up in groups), a reply or edit above it, the paperclip (photos and videos,
 * camera, document, location, live location, a teammate's contact), the camera, and the microphone: hold to record,
 * slide left to cancel, slide up to lock and talk hands-free.
 *
 * chatpa: quick replies above an empty box; the box grows to 6 lines and then scrolls, with a full-screen editor;
 * pasted pictures and keyboard images/GIFs; a voice note is heard, trimmed, deleted or sent before it goes; the send
 * button turns into the microphone when the box is empty.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Composer(m: AppModel, c: String, row: ChatRow?, reply: Msg?, editing: Msg?, clearReply: () -> Unit, clearEdit: () -> Unit, sent: () -> Unit) {
    val hub = m.chat
    val ctx = LocalContext.current
    val scope = rememberScope()
    val hp = rememberHaptics()
    val density = LocalDensity.current
    val motion = chatMotion()
    hub.settingsTick.collectAsState().value
    val box = rememberSaveable(c, saver = TextFieldState.Saver) { TextFieldState(ChatDrafts.get(ctx, c)) }
    val text = box.text.toString()
    val mentions = remember(c) { mutableStateListOf<String>() }
    var attach by remember { mutableStateOf(false) }
    var liveAsk by remember { mutableStateOf(false) }
    var contactAsk by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<List<ChatMedia>?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var full by rememberSaveable { mutableStateOf(false) }
    var lines by remember { mutableIntStateOf(1) }
    LaunchedEffect(msg) { if (msg != null) { delay(4000); msg = null } }
    BackStep(attach) { attach = false }
    // the draft is kept per chat (also if the app is closed)
    LaunchedEffect(text) { delay(400); ChatDrafts.put(ctx, c, if (editing == null) text else "") }
    LaunchedEffect(editing) { editing?.let { box.setTextAndPlaceCursorAtEnd(it.text) } }
    // "typing…" for the others, stopped 5 seconds after the last key
    LaunchedEffect(text) { if (text.isNotBlank()) { hub.typing(c, "typing"); delay(5000); hub.typing(c, null) } }

    fun quietChat() = hub.quiet(c)
    // the soft send sound: not when the chat or the setting is quiet, nor in Do-Not-Disturb hours
    fun after() { hp.tap(); if (!quietChat() && hub.settings.sendSound && !hub.settings.dnd.active()) Whoosh.play(ctx); hub.typing(c, null); sent() }
    fun sendText(t0: String = box.text.toString()) {
        val txt = t0.trim(); if (txt.isBlank()) return
        val ed = editing
        if (ed != null) { scope.launch { hub.editMsg(ed, txt)?.let { msg = it } }; clearEdit() }
        else { hub.send(c, "text", txt, reply = reply, mentions = mentions.filter { txt.contains("@$it", true) }.distinct()); clearReply() }
        box.clearText(); mentions.clear(); ChatDrafts.put(ctx, c, ""); full = false; after()
    }

    // --- picking photos, videos and documents ---------------------------------------------------------------------
    fun prepare(uris: List<Pair<Uri?, File?>>) {
        if (uris.isEmpty()) return
        preparing = true
        scope.launch {
            val out = mutableListOf<ChatMedia>(); var why: String? = null
            for ((u, f0) in uris) {
                val isVideo = u?.let { ChatPrep.isVideo(ctx, it) } ?: f0?.name?.endsWith(".mp4") ?: false
                val f = f0 ?: withContext(Dispatchers.IO) { ChatPrep.copy(ctx, u!!, if (isVideo) "mp4" else "jpg") } ?: continue
                if (isVideo) { val (md, w) = ChatPrep.video(ctx, f); md?.let { out += it }; if (w != null) why = w }
                else withContext(Dispatchers.IO) { ChatPrep.photo(ctx, f) }?.let { out += it } ?: run { why = t("That photo couldn’t be read.", "Picha hiyo haikuweza kusomwa.") }
                if (f.path.contains("/cam/")) f.delete()
            }
            preparing = false
            msg = why
            if (out.isNotEmpty()) picked = (picked.orEmpty() + out).take(10)
        }
    }
    // pasted or keyboard pictures (screenshots, GIFs) go the same way as picked ones; tests paste files through ChatPaste
    DisposableEffect(c) { ChatPaste.paste = { files -> prepare(files.map { null to it }) }; onDispose { ChatPaste.paste = null } }
    val receiver = remember(c) {
        object : ReceiveContentListener {
            override fun onReceive(transferableContent: TransferableContent): TransferableContent? {
                if (!transferableContent.hasMediaType(MediaType.Image)) return transferableContent
                val uris = mutableListOf<Uri>()
                val rest = transferableContent.consume { item -> item.uri?.let { uris += it; true } ?: false }
                if (uris.isNotEmpty()) prepare(uris.map { it to null })
                return rest
            }
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris -> prepare(uris.map { it to null }) }
    var camFile by remember { mutableStateOf<File?>(null) }
    val cam = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> val f = camFile; camFile = null; if (ok && f != null) prepare(listOf(null to f)) }
    val camVideo = rememberLauncherForActivityResult(CaptureVideo60()) { ok -> val f = camFile; camFile = null; if (ok && f != null && f.length() > 0) prepare(listOf(null to f)) }
    fun shoot(video: Boolean) {
        val f = File(File(ctx.cacheDir, "cam").apply { mkdirs() }, "c${System.nanoTime()}." + if (video) "mp4" else "jpg"); camFile = f
        val uri = FileProvider.getUriForFile(ctx, "org.rafikioutreach.pin.files", f)
        runCatching { if (video) camVideo.launch(uri) else cam.launch(uri) }.onFailure { msg = t("No camera app on this phone.", "Hakuna programu ya kamera kwenye simu hii.") }
    }
    val doc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val (md, why) = withContext(Dispatchers.IO) { ChatPrep.doc(ctx, uri) }
            if (md != null) { hub.send(c, "doc", "", listOf(md), reply = reply); clearReply(); after() } else msg = why
        }
    }
    val locAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if (it.values.any { ok -> ok }) m.locator.start() }
    fun here(): org.rafikioutreach.pin.data.LatLng? {
        val f = m.fix.value
        if (f == null) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) locAsk.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            msg = t("Finding where you are… try again in a moment.", "Inatafuta ulipo… jaribu tena baada ya muda mfupi."); return null
        }
        return f.ll
    }

    // --- the microphone ---------------------------------------------------------------------------------------------------
    val rec = remember { VoiceRec(ctx) }
    var recording by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var secs by remember { mutableDoubleStateOf(0.0) }
    // the note just recorded, to hear, trim, delete or send
    var note by remember(c) { mutableStateOf<ChatMedia?>(null) }
    var trimTo by remember(c) { mutableFloatStateOf(1f) }
    val micAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (!ok) msg = t("Allow the microphone to record voice messages.", "Ruhusu kipaza sauti ili kurekodi ujumbe wa sauti.") else msg = t("Now hold the microphone to record.", "Sasa shikilia kipaza sauti kurekodi.") }
    fun stopRec() {
        val md = if (MicTest.pretend) {
            val f = File(outDir(ctx), "a${System.nanoTime()}.m4a").apply { writeBytes(ByteArray(64)) }
            ChatMedia("", "voice", dur = secs.coerceAtLeast(1.0), wave = VoiceRec.bars(rec.levels, 40), name = f.name, size = f.length(), local = f.path, mime = "audio/mp4")
        } else rec.stop()
        recording = false; locked = false; dragX = 0f; dragY = 0f; hub.typing(c, null)
        if (md == null) { msg = t("Too short. Hold the microphone a little longer.", "Fupi mno. Shikilia kipaza sauti kidogo zaidi."); return }
        trimTo = 1f; note = md; hp.tap()
    }
    LaunchedEffect(recording) {
        while (recording) {
            if (!MicTest.pretend) rec.sample() else rec.levels += (10..90).random()
            secs = if (MicTest.pretend) secs + 0.1 else rec.seconds()
            if (secs >= ChatCaps.VOICE_SECONDS) stopRec()
            delay(100)
        }
    }
    DisposableEffect(Unit) { onDispose { if (rec.active) rec.cancel() } }
    fun startRec(): Boolean {
        if (!MicTest.pretend && ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { micAsk.launch(Manifest.permission.RECORD_AUDIO); return false }
        if (!MicTest.pretend && !rec.start()) { msg = t("The microphone couldn’t start.", "Kipaza sauti hakikuweza kuanza."); return false }
        if (MicTest.pretend) rec.levels.clear()
        VoicePlayer.stop(); secs = 0.0; dragX = 0f; dragY = 0f; locked = false; recording = true; hp.long(); hub.typing(c, "recording")
        return true
    }
    fun cancelRec() { if (!MicTest.pretend) rec.cancel(); recording = false; locked = false; dragX = 0f; dragY = 0f; hp.tap(); hub.typing(c, null) }
    fun dropNote() { VoicePlayer.stop(); note?.local?.let { runCatching { File(it).delete() } }; note = null; hp.tap() }
    fun sendNote() {
        val md = note ?: return
        VoicePlayer.stop(); note = null
        val end = md.dur * trimTo
        scope.launch {
            // trimmed: the end is cut off on the phone before it goes
            val out = if (trimTo < 0.98f && end >= 1.0) withContext(Dispatchers.IO) { VoiceTrim.cut(md, end) } else md
            hub.send(c, "voice", "", listOf(out), reply = reply); clearReply(); after()
        }
    }
    val cancelPx = with(density) { 110.dp.toPx() }; val lockPx = with(density) { 80.dp.toPx() }

    val members = row?.members.orEmpty().filter { !it.equals(hub.me, true) }
    // "@gr" being typed: the names that match
    val token = Regex("(?:^|\\s)@([\\p{L}]*)$").find(text.substring(0, box.selection.end.coerceIn(0, text.length)))?.groupValues?.get(1)
    val suggest = if (row?.isGroup == true && token != null) members.filter { it.startsWith(token, true) || it.split(' ').any { p -> p.startsWith(token, true) } }.take(5) else emptyList()
    fun pickMention(n: String) {
        val end = box.selection.end.coerceIn(0, text.length); val start = end - token!!.length - 1
        box.edit { replace(start, end, "@$n "); selection = TextRange(start + n.length + 2) }; mentions += n
    }

    Column(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 6.dp).testTag("composer")) {
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = C.warn, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp).testTag("composer_msg")) }
        if (preparing) Text(t("Getting it ready…", "Inaandaliwa…"), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
        if (suggest.isNotEmpty()) Column(Modifier.padding(bottom = 6.dp).fillMaxWidth().shadow(6.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(C.card).testTag("mentions")) {
            suggest.forEach { n ->
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { pickMention(n) }.padding(horizontal = 14.dp, vertical = 8.dp).testTag("mention_$n"), verticalAlignment = Alignment.CenterVertically) {
                    PersonAvatar(n, 30.dp); Spacer(Modifier.width(10.dp)); Text(n, style = MaterialTheme.typography.titleSmall, color = C.ink)
                }
            }
        }
        // quick replies while the box is empty: one tap sends
        if (text.isEmpty() && editing == null && !recording && note == null && suggest.isEmpty()) QuickChips(hub.settings.quick) { q -> sendText(q) }
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f).shadow(if (C.dark) 0.dp else 1.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(C.card).border(1.dp, if (C.glassy) C.glassEdge else C.line, RoundedCornerShape(24.dp))) {
                // replying to / editing
                if ((reply != null || editing != null) && note == null) Row(Modifier.padding(start = 8.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (editing != null) org.rafikioutreach.pin.ui.QuoteBox(org.rafikioutreach.pin.data.Quote(editing.id, t("Edit message", "Hariri ujumbe"), editing.kind, editing.text), "", Modifier.testTag("editing_bar"))
                        else reply?.let { QuoteBox(org.rafikioutreach.pin.data.Quote(it.id, it.from, it.kind, it.preview()), hub.me, Modifier.testTag("reply_bar")) }
                    }
                    Box(Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button) { if (editing != null) { clearEdit(); box.clearText() } else clearReply() }.semantics { contentDescription = t("Cancel", "Ghairi") }.testTag("reply_x"), contentAlignment = Alignment.Center) {
                        Icon(Ic.get("x"), null, tint = C.muted, modifier = Modifier.size(16.dp))
                    }
                }
                Row(Modifier.heightIn(min = 48.dp).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val n = note
                    when {
                        n != null -> VoiceNotePreview(n, trimTo, { trimTo = it }, { dropNote() })
                        recording && locked -> {
                            Box(Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button) { cancelRec() }.semantics { contentDescription = t("Delete recording", "Futa rekodi") }.testTag("rec_trash"), contentAlignment = Alignment.Center) {
                                Icon(Ic.get("trash"), null, tint = C.bad, modifier = Modifier.size(20.dp))
                            }
                            Text(clock(secs), style = MaterialTheme.typography.labelLarge, color = C.ink, modifier = Modifier.padding(horizontal = 6.dp).testTag("rec_time"))
                            Waveform(VoiceRec.bars(rec.levels.takeLast(60), 30), 1f, C.bad, C.line, Modifier.weight(1f).height(28.dp).padding(end = 4.dp))
                            // stop: hear it before it goes
                            Box(Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button) { stopRec() }.semantics { contentDescription = t("Stop and listen", "Simamisha usikilize") }.testTag("rec_stop"), contentAlignment = Alignment.Center) {
                                Icon(ChatIc.get("stop"), null, tint = C.bad, modifier = Modifier.size(20.dp))
                            }
                        }
                        recording -> {
                            val blink by rememberInfiniteTransition(label = "rec").animateFloat(1f, 0.2f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "dot")
                            Box(Modifier.size(10.dp).alpha(blink).clip(CircleShape).background(C.bad))
                            Text(clock(secs), style = MaterialTheme.typography.labelLarge, color = C.ink, modifier = Modifier.padding(horizontal = 8.dp).testTag("rec_time"))
                            Text("‹ " + t("Slide to cancel", "Telezesha kughairi"), style = MaterialTheme.typography.bodyMedium, color = C.muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                                modifier = Modifier.weight(1f).offset { IntOffset((dragX * 0.5f).roundToInt().coerceAtMost(0), 0) }.alpha((1f + dragX / cancelPx).coerceIn(0.2f, 1f)).testTag("rec_slide"))
                        }
                        else -> {
                            Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                                if (text.isEmpty()) Text(t("Message", "Ujumbe"), style = MaterialTheme.typography.bodyLarge, color = C.muted, maxLines = 1)
                                // grows to 6 lines, then scrolls
                                BasicTextField(box, Modifier.fillMaxWidth().contentReceiver(receiver).testTag("composer_input"),
                                    lineLimits = TextFieldLineLimits.MultiLine(1, 6),
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = C.text), cursorBrush = SolidColor(C.blue),
                                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                                    onTextLayout = { r -> r()?.let { lines = it.lineCount } })
                            }
                            // a long message: write it full screen
                            if (lines >= 3) ComposerIcon("expand", t("Full screen", "Skrini nzima"), "expand") { full = true }
                            ComposerIcon("clip", t("Attach", "Ambatisha"), "attach") { hp.tap(); attach = true }
                            if (text.isBlank()) ComposerIcon("camera", t("Camera", "Kamera"), "camera") { hp.tap(); shoot(false) }
                        }
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            // send, or the microphone (hold): one morphs into the other
            val canSend = text.isNotBlank() || (recording && locked) || note != null
            Box {
                if (recording && !locked) {
                    // the lock: slide up to here
                    Column(Modifier.align(Alignment.TopCenter).offset { IntOffset(0, -with(density) { 96.dp.roundToPx() } + (dragY * 0.4f).roundToInt().coerceAtMost(0)) }.width(40.dp)
                        .clip(RoundedCornerShape(20.dp)).background(C.card).border(1.dp, C.line, RoundedCornerShape(20.dp)).padding(vertical = 8.dp).testTag("rec_lock"), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Ic.get("lock"), null, tint = C.muted, modifier = Modifier.size(18.dp)); Icon(Ic.get("up"), null, tint = C.muted, modifier = Modifier.size(16.dp))
                    }
                }
                val sz by animateDpAsState(if (recording && !locked) 58.dp else 48.dp, tween(if (motion) 180 else 0), label = "mic")
                Box(Modifier.size(sz).offset { IntOffset(if (recording && !locked) dragX.roundToInt().coerceAtMost(0) / 3 else 0, 0) }
                    .clip(CircleShape).background(if (recording && !locked) C.bad else ChatLook.online)
                    .then(if (canSend) Modifier.clickable(role = Role.Button) { when { note != null -> sendNote(); recording -> stopRec(); else -> sendText() } } else Modifier.pointerInput(c) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (!startRec()) return@awaitEachGesture
                            var total = Offset.Zero; var gone = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                total += ch.positionChange(); ch.consume()
                                if (!locked && !gone) { dragX = total.x; dragY = total.y }
                                if (!locked && !gone && total.x < -cancelPx) { gone = true; cancelRec() }
                                if (!locked && !gone && total.y < -lockPx) { locked = true; dragX = 0f; dragY = 0f; hp.tap() }
                            }
                            // let go: the note waits to be heard, trimmed or sent
                            if (!gone && !locked && recording) stopRec()
                        }
                    }).semantics { contentDescription = if (canSend) t("Send", "Tuma") else t("Hold to record a voice message", "Shikilia kurekodi ujumbe wa sauti") }
                    .testTag(if (canSend) "send" else "mic"), contentAlignment = Alignment.Center) {
                    AnimatedContent(canSend, transitionSpec = { if (motion) (scaleIn(tween(200), 0.4f) + fadeIn(tween(200))) togetherWith (scaleOut(tween(150), 0.4f) + fadeOut(tween(150))) else EnterTransition.None togetherWith ExitTransition.None }, label = "sendmic") { s ->
                        Icon(ChatIc.get(if (s) "send" else "mic"), null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }

    if (full) FullEditor(box, { full = false }) { sendText() }
    if (attach) AttachSheet({ attach = false }) { k ->
        attach = false
        when (k) {
            "gallery" -> runCatching { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
            "camera" -> shoot(false)
            "video" -> shoot(true)
            "doc" -> runCatching { doc.launch(arrayOf("application/pdf", "application/msword", "application/vnd.openxmlformats-officedocument.*", "application/vnd.ms-excel", "text/*", "*/*")) }
            "loc" -> here()?.let { ll -> hub.send(c, "location", "", loc = ll, reply = reply); clearReply(); after() }
            "live" -> if (here() != null) liveAsk = true
            "contact" -> contactAsk = true
        }
    }
    if (liveAsk) LiveSheet({ liveAsk = false }) { min -> here()?.let { ll -> hub.send(c, "live", "", loc = ll, liveMinutes = min); after() } }
    if (contactAsk) TeammatePicker(m, t("Share a contact", "Shiriki mawasiliano"), exclude = emptyList(), multi = false, close = { contactAsk = false }) { names -> names.firstOrNull()?.let { hub.send(c, "contact", "", contact = it); after() } }
    picked?.let { list -> MediaPreviewSheet(m, list, { picked = it.ifEmpty { null } }, { picked = null }) { caption ->
        hub.send(c, "media", caption, list, reply = reply); clearReply(); picked = null; after()
    } }
}

/** Tests paste pictures through here (what the keyboard or the clipboard would give the box). */
internal object ChatPaste { var paste: ((List<File>) -> Unit)? = null }

/** The voice note before it goes: delete, play, the waveform with the end to trim (drag), its length. */
@Composable
private fun RowScope.VoiceNotePreview(md: ChatMedia, trimTo: Float, setTrim: (Float) -> Unit, delete: () -> Unit) {
    val st by VoicePlayer.state.collectAsState()
    val playing = st.key == "preview" && st.playing
    LaunchedEffect(playing) { while (playing) { VoicePlayer.tick(); if (st.dur > 0 && st.pos >= md.dur * trimTo) VoicePlayer.stop(); delay(80) } }
    Box(Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button) { delete() }.semantics { contentDescription = t("Delete voice message", "Futa ujumbe wa sauti") }.testTag("vp_delete"), contentAlignment = Alignment.Center) {
        Icon(Ic.get("trash"), null, tint = C.bad, modifier = Modifier.size(20.dp))
    }
    Box(Modifier.size(36.dp).clip(CircleShape).background(ChatLook.online).clickable(role = Role.Button) { md.local.takeIf { it.isNotBlank() }?.let { VoicePlayer.toggle("preview", File(it), md.dur) } }
        .semantics { contentDescription = if (playing) t("Pause", "Simamisha") else t("Listen", "Sikiliza") }.testTag("vp_play"), contentAlignment = Alignment.Center) {
        Icon(Ic.get(if (playing) "pause" else "play"), null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
    val prog = if (st.key == "preview" && md.dur > 0) (st.pos / md.dur).toFloat().coerceIn(0f, 1f) else 0f
    Box(Modifier.weight(1f).height(32.dp).padding(horizontal = 8.dp).testTag("vp_trim")) {
        Waveform(md.wave, prog, ChatLook.online, if (C.dark) Color(0x55FFFFFF) else Color(0x40000000), Modifier.matchParentSize()) { f -> setTrim(f.coerceIn(0.1f, 1f)) }
        // what's cut off is dimmed, with a handle at the new end
        BoxWithConstraints(Modifier.matchParentSize()) {
            Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(maxWidth * (1f - trimTo)).background(C.card.copy(alpha = 0.7f)))
            Box(Modifier.offset(x = maxWidth * trimTo - 2.dp).fillMaxHeight().width(4.dp).clip(RoundedCornerShape(2.dp)).background(C.blue).testTag("vp_handle"))
        }
    }
    Text(clock(md.dur * trimTo), style = MaterialTheme.typography.labelLarge, color = C.ink, maxLines = 1, softWrap = false, modifier = Modifier.padding(end = 6.dp).testTag("vp_time"))
}

/** The message full screen, for something long: the same box, with Send. */
@Composable
private fun FullEditor(box: TextFieldState, close: () -> Unit, send: () -> Unit) {
    androidx.compose.ui.window.Dialog(close, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding().imePadding().padding(16.dp).testTag("full_editor")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) { close() }.semantics { contentDescription = t("Close", "Funga") }.testTag("full_close"), contentAlignment = Alignment.Center) { Icon(Ic.get("x"), null, tint = C.ink, modifier = Modifier.size(22.dp)) }
                Text(t("Message", "Ujumbe"), style = MaterialTheme.typography.titleLarge, color = C.ink, modifier = Modifier.weight(1f).padding(start = 6.dp))
                Btn(t("Send", "Tuma"), send, Modifier.testTag("full_send"), small = true, icon = "arrow", enabled = box.text.isNotBlank())
            }
            BasicTextField(box, Modifier.padding(top = 12.dp).fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).background(C.card).padding(14.dp).testTag("full_input"),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = C.text), cursorBrush = SolidColor(C.blue),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
        }
    }
}



@Composable
private fun ComposerIcon(icon: String, label: String, tag: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label }.testTag(tag), contentAlignment = Alignment.Center) {
        Icon(ChatIc.get(icon), null, tint = C.muted, modifier = Modifier.size(22.dp))
    }
}

/** A chat's half-typed message, kept on the phone. */
internal object ChatDrafts {
    private var app: Context? = null
    private fun sp(ctx: Context) = ctx.applicationContext.also { app = it }.getSharedPreferences("chat_drafts", Context.MODE_PRIVATE)
    /** Bumped on every change, so the chat list shows "Draft: …" (chatpa). */
    var version by mutableIntStateOf(0)
    fun get(ctx: Context, c: String) = sp(ctx).getString(c, "") ?: ""
    fun put(ctx: Context, c: String, text: String) {
        val before = get(ctx, c)
        if (text.isBlank()) sp(ctx).edit().remove(c).apply() else sp(ctx).edit().putString(c, text).apply()
        if (before != text.ifBlank { "" }) version++
    }
    /** The draft for the list (no context to hand there). */
    fun peek(c: String) = app?.let { get(it, c) }.orEmpty()
    fun init(ctx: Context) { sp(ctx) }
}

/** The paperclip: what to send. */
@Composable
private fun AttachSheet(close: () -> Unit, pick: (String) -> Unit) {
    TeamSubSheet("attach_sheet", close) { hide ->
        Text(t("Send", "Tuma"), style = MaterialTheme.typography.titleLarge, color = C.ink, modifier = Modifier.padding(bottom = 12.dp))
        val items = listOf(
            listOf(Triple("gallery", t("Photos & videos", "Picha na video"), "blue"), Triple("camera", t("Camera", "Kamera"), "coral"), Triple("video", t("Video", "Video"), "purple"), Triple("doc", t("Document", "Hati"), "amber")),
            listOf(Triple("loc", t("Location", "Mahali"), "green"), Triple("live", t("Live location", "Moja kwa moja"), "teal"), Triple("contact", t("Contact", "Mawasiliano"), "slate")),
        )
        items.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                r.forEach { (k, label, hue) ->
                    val hu = C.hue(hue)
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { hide { pick(k) } }.padding(vertical = 8.dp).testTag("att_$k"), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(54.dp).clip(CircleShape).background(hu.bg), contentAlignment = Alignment.Center) {
                            Icon(ChatIc.get(when (k) { "gallery" -> "gallery"; "camera" -> "camera"; "video" -> "video"; "doc" -> "doc"; "loc" -> "loc"; "live" -> "live"; else -> "contact" }), null, tint = hu.fg, modifier = Modifier.size(24.dp))
                        }
                        FitWords(label, MaterialTheme.typography.labelSmall.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center), C.ink, Modifier.padding(top = 6.dp))
                    }
                }
                repeat(4 - r.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Live location: for 15 minutes, an hour or 8 hours (stop any time). Moves only while the app is open. */
@Composable
private fun LiveSheet(close: () -> Unit, pick: (Int) -> Unit) {
    TeamSubSheet("live_sheet", close) { hide ->
        Text(t("Share live location", "Shiriki mahali moja kwa moja"), style = MaterialTheme.typography.titleLarge, color = C.ink)
        Text(t("Your position updates while RAFIKI Pin is open. Stop any time.", "Mahali ulipo panasasishwa RAFIKI Pin ikiwa wazi. Acha wakati wowote."), style = MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        SheetRow("live_15", "clock", t("15 minutes", "Dakika 15")) { hide { pick(15) } }
        SheetRow("live_60", "clock", t("1 hour", "Saa 1")) { hide { pick(60) } }
        SheetRow("live_480", "clock", t("8 hours", "Saa 8")) { hide { pick(480) } }
    }
}

/** The photos and videos about to go: remove any, add a caption, send. */
@Composable
private fun MediaPreviewSheet(m: AppModel, list: List<ChatMedia>, change: (List<ChatMedia>) -> Unit, close: () -> Unit, send: (String) -> Unit) {
    var caption by remember { mutableStateOf("") }
    TeamSubSheet("media_preview", close) { hide ->
        Text(if (list.size == 1) t("Send 1 item", "Tuma kimoja") else t("Send ${list.size} items", "Tuma vitu ${list.size}"), style = MaterialTheme.typography.titleLarge, color = C.ink, modifier = Modifier.padding(bottom = 10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEach { md ->
                Box(Modifier.size(104.dp)) {
                    ChatImage(m, md, Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)))
                    Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp).clip(CircleShape).background(Color(0xCC000000)).clickable(role = Role.Button) { change(list - md) }
                        .semantics { contentDescription = t("Remove", "Ondoa") }, contentAlignment = Alignment.Center) { Icon(Ic.get("x"), null, tint = Color.White, modifier = Modifier.size(14.dp)) }
                    Text(sizeText(md.size), color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x88000000)).padding(horizontal = 4.dp))
                }
            }
        }
        Field(caption, { caption = it }, t("Caption (optional)", "Maelezo (si lazima)"), Modifier.padding(top = 12.dp).fillMaxWidth())
        Btn(t("Send", "Tuma"), { hide { send(caption.trim()) } }, Modifier.padding(top = 14.dp).fillMaxWidth().testTag("media_send"), icon = "arrow")
    }
}

/** Choose teammates (one, or several with ticks). */
@Composable
internal fun TeammatePicker(m: AppModel, title: String, exclude: List<String>, multi: Boolean, close: () -> Unit, done: (List<String>) -> Unit) {
    val team by m.team.collectAsState()
    LaunchedEffect(Unit) { if (team == null) m.loadTeam() }
    var q by remember { mutableStateOf("") }
    val chosen = remember { mutableStateListOf<String>() }
    TeamSubSheet("mate_picker", close) { hide ->
        Text(title, style = MaterialTheme.typography.titleLarge, color = C.ink)
        ChatSearchField(q, { q = it }, t("Search the team", "Tafuta kwenye timu"), "mate_search")
        val people = team.orEmpty().map { it.name }.filter { n -> !n.equals(m.chat.me, true) && exclude.none { it.equals(n, true) } && (q.isBlank() || n.contains(q.trim(), true)) }.sorted()
        if (team == null) Text(t("Loading…", "Inapakia…"), color = C.muted)
        else if (people.isEmpty()) Text(t("Nobody to add.", "Hakuna wa kuongeza."), color = C.muted, modifier = Modifier.padding(8.dp))
        people.forEach { n ->
            val on = n in chosen
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = if (multi) Role.Checkbox else Role.Button) {
                if (multi) { if (on) chosen.remove(n) else chosen.add(n) } else hide { done(listOf(n)) }
            }.heightIn(min = 54.dp).padding(horizontal = 6.dp).testTag("mate_$n"), verticalAlignment = Alignment.CenterVertically) {
                PersonAvatar(n, 38.dp); Spacer(Modifier.width(12.dp))
                Text(n, style = MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.weight(1f))
                if (multi) Box(Modifier.size(24.dp).clip(CircleShape).background(if (on) ChatLook.online else Color.Transparent).border(2.dp, if (on) ChatLook.online else C.edge, CircleShape), contentAlignment = Alignment.Center) {
                    if (on) Icon(Ic.get("check"), null, tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
        if (multi) Btn(if (chosen.isEmpty()) t("Choose people", "Chagua watu") else t("Add ${chosen.size}", "Ongeza ${chosen.size}"), { hide { done(chosen.toList()) } }, Modifier.padding(top = 12.dp).fillMaxWidth().testTag("mate_done"), enabled = chosen.isNotEmpty(), icon = "check")
    }
}

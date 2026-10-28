package org.rafikioutreach.pin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.rafikioutreach.pin.Lang
import org.rafikioutreach.pin.data.ChatRow
import org.rafikioutreach.pin.t
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Where Chat should go next: a chat ("c:<id>"), a direct message ("dm:<name>"), a project's thread ("p:<id>"),
 *  joining by link ("join:<token>"), or the Chat tab (""). Set from anywhere (a contact sheet, a project, a notification). */
object ChatNav {
    var req by mutableStateOf<String?>(null)
    fun dm(name: String) { req = "dm:$name" }
    fun project(id: String) { req = "p:$id" }
    fun chat(id: String) { req = "c:$id" }
    /** A message to scroll to when the chat opens (search, starred, a tapped quote in another chat). */
    var focus by mutableStateOf<String?>(null)
}

/** Chat's own line icons (the same 24×24 stroke style as [Ic]). */
internal object ChatIc {
    private val D = mapOf(
        "send" to listOf("M4.5 12 3.5 4.5 20.5 12 3.5 19.5z", "M4.5 12H11"),
        "clip" to listOf("M19 11.5l-7.2 7.2a4.6 4.6 0 0 1-6.5-6.5l7.6-7.6a3.1 3.1 0 0 1 4.4 4.4l-7.5 7.5a1.5 1.5 0 0 1-2.2-2.2l6.9-6.9"),
        "reply" to listOf("M9.5 6.5 4.5 11.5l5 5", "M4.5 11.5H14a5.5 5.5 0 0 1 5.5 5.5v1"),
        "fwd" to listOf("M14.5 6.5l5 5-5 5", "M19.5 11.5H10a5.5 5.5 0 0 0-5.5 5.5v1"),
        "star" to listOf("M12 3.8l2.5 5.2 5.7.8-4.1 4 1 5.6L12 16.7l-5.1 2.7 1-5.6-4.1-4 5.7-.8z"),
        "mute" to listOf("M6 16.5V11a6 6 0 0 1 9.4-4.9M18 10.6v5.9l1.5 2h-13", "M10 20.5a2 2 0 0 0 4 0", "M4 4l16 16"),
        "archive" to listOf("M3.5 5h17v4h-17z", "M5 9v10.5h14V9", "M10 13h4"),
        "doc" to listOf("M7 3.5h7l4.5 4.5v12.5H7z", "M14 3.5V8h4.5", "M9.5 12.5h6M9.5 15.5h6"),
        "loc" to listOf("M12 21s-7-6.2-7-11.5A7 7 0 0 1 19 9.5C19 14.8 12 21 12 21z", "M9.5 9.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0"),
        "live" to listOf("M12 13.5a1.6 1.6 0 1 0 0-3.2 1.6 1.6 0 0 0 0 3.2z", "M8.3 8.3a5.2 5.2 0 0 0 0 7.4M15.7 8.3a5.2 5.2 0 0 1 0 7.4M5.5 5.5a9.2 9.2 0 0 0 0 13M18.5 5.5a9.2 9.2 0 0 1 0 13"),
        "contact" to listOf("M4 5h16v14H4z", "M9.5 11.2a2 2 0 1 0 0-.1", "M6.8 16c.5-1.7 1.5-2.6 2.7-2.6s2.2.9 2.7 2.6", "M14.5 10h3M14.5 13h3"),
        "smile" to listOf("M12 20.5a8.5 8.5 0 1 0 0-17 8.5 8.5 0 0 0 0 17z", "M8.5 14c.9 1.3 2 2 3.5 2s2.6-.7 3.5-2", "M9 9.8v.1M15 9.8v.1"),
        "lockopen" to listOf("M5 10.5h14v10H5z", "M8.5 10.5V8a3.5 3.5 0 0 1 6.8-1.2"),
        "gallery" to listOf("M4 5h16v14H4z", "M4 16l4.5-4.5 4 4 2.5-2.5L20 18", "M15.5 9a1.3 1.3 0 1 0 0-.1"),
        "group" to listOf("M9 11.5a3.2 3.2 0 1 0 0-6.4 3.2 3.2 0 0 0 0 6.4z", "M3.5 19.5c.6-3.2 2.8-5 5.5-5s4.9 1.8 5.5 5", "M17 12.4a2.4 2.4 0 1 0 0-4.8", "M15.8 14.6c2.4.2 4 1.8 4.6 4.4", "M19 3v4M17 5h4"),
        "wall" to listOf("M4 4h16v16H4z", "M4 15l5-5 4 4 3-3 4 4"),
        "eye" to listOf("M2.5 12s3.5-6.5 9.5-6.5 9.5 6.5 9.5 6.5-3.5 6.5-9.5 6.5S2.5 12 2.5 12z", "M12 14.6a2.6 2.6 0 1 0 0-5.2 2.6 2.6 0 0 0 0 5.2z"),
        "exit" to listOf("M14 4.5h4.5v15H14", "M10 8l-4 4 4 4M6 12h9.5"),
        "speed" to listOf("M4.5 15.5a7.5 7.5 0 1 1 15 0", "M12 15.5l3.5-4.5"),
        "video" to listOf("M3.5 6.5h12v11h-12z", "M15.5 10.5l5-3v9l-5-3z"),
        // chatpa
        "expand" to listOf("M14 4.5h5.5V10", "M10 19.5H4.5V14", "M19.5 4.5l-6.5 6.5", "M4.5 19.5l6.5-6.5"),
        "stop" to listOf("M7 7h10v10H7z"),
        "megaphone" to listOf("M4 10v4h3l7 4V6l-7 4z", "M17.5 9.5a3.5 3.5 0 0 1 0 5", "M7 14l1.5 5h2.5l-1.3-5"),
        "bubble" to listOf("M5 5.5h14v10H10l-4 3.5v-3.5H5z"),
        "data" to listOf("M7 4v16M7 20l-3-3M7 20l3-3", "M17 20V4M17 4l-3 3M17 4l3 3"),
        "moon" to listOf("M19 14.5A7.5 7.5 0 0 1 9.5 5a7.5 7.5 0 1 0 9.5 9.5z"),
        // chat 2 (cw2): new chat, select, delete, translate, a check
        "pushpin" to listOf("M14.5 4l5.5 5.5-2.8 1.2-3.4 3.4.4 4-1.8 1.8-8-8 1.8-1.8 4 .4 3.4-3.4z", "M8.4 15.6L4 20"),
        "newchat" to listOf("M4.5 19.5l1.2-3.6A7.8 7.8 0 1 1 8.6 18.6z", "M12 8.6v6M9 11.6h6"),
        "trash" to listOf("M4.5 7h15M9.5 7V4.5h5V7M6.5 7l1 13h9l1-13", "M10 11v5.5M14 11v5.5"),
        "selectall" to listOf("M4 12.5l4 4 8.5-8.5", "M12.5 16.5l1 1 7-7"),
        "translate" to listOf("M4 5h8M8 3.5V5M5.5 5c.6 3 2.5 5.5 5.5 6.8M10.5 5c-.6 3-2.6 5.5-6 7", "M11.5 20l4-9 4 9M13 17h5"),
        "photoadd" to listOf("M4 6h11v4M4 6v13h16v-6", "M4 16l4.5-4.5 4 4", "M18.5 4v6M15.5 7h6"),
    )
    private val cache = HashMap<String, ImageVector>()
    fun get(name: String): ImageVector = D[name]?.let { paths ->
        cache.getOrPut(name) {
            ImageVector.Builder("chat_$name", 24.dp, 24.dp, 24f, 24f).apply {
                paths.forEach { addPath(addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) }
            }.build()
        }
    } ?: DqmIc.get(name)
}

/** Chat colours: the bubbles, ticks and online dot, in light and dark (the same as the web app). */
internal object ChatLook {
    val online = Color(0xFF22B566)
    val readBlue = Color(0xFF34B7F1)
    @Composable fun mine() = if (C.dark) Color(0xFF0E4A72) else if (C === Sun) Color(0xFFDCEBFF) else Color(0xFFD9EEFF)
    @Composable fun theirs() = if (C.dark) Color(0xFF1E2A36) else Color.White
    @Composable fun tick() = if (C.dark) Color(0xFF9CB4C6) else Color(0xFF6B7C8A)
    /** A sender's colour in a group (their name on the first bubble of a run). */
    @Composable fun nameColor(name: String) = C.hue(nameHue(name)).fg
}

/** The ticks on my messages: a clock while waiting, one grey tick sent, two grey delivered, two blue read; a red ! if refused. */
@Composable
internal fun Ticks(status: String, modifier: Modifier = Modifier, tint: Color = ChatLook.tick()) {
    // grey to blue: a soft fade when the message is read (chatpa)
    val col by androidx.compose.animation.animateColorAsState(if (status == "read") ChatLook.readBlue else if (status == "failed") C.bad else tint,
        androidx.compose.animation.core.tween(if (chatMotion()) 300 else 0), label = "tick")
    Canvas(modifier.size(if (status == "delivered" || status == "read") 18.dp else 14.dp, 12.dp).testTag("tick_$status")) {
        val sw = 1.6.dp.toPx()
        val h = size.height; val w = size.width
        when (status) {
            "queued" -> {
                val r = h * 0.42f; val c = Offset(w / 2, h / 2)
                drawCircle(col, r, c, style = Stroke(sw))
                drawLine(col, c, Offset(c.x, c.y - r * 0.62f), sw, StrokeCap.Round); drawLine(col, c, Offset(c.x + r * 0.5f, c.y), sw, StrokeCap.Round)
            }
            "failed" -> { drawLine(col, Offset(w / 2, h * 0.12f), Offset(w / 2, h * 0.62f), sw * 1.2f, StrokeCap.Round); drawCircle(col, sw * 0.8f, Offset(w / 2, h * 0.88f)) }
            else -> {
                fun tick(dx: Float) { val p = Path().apply { moveTo(dx + w * 0.05f, h * 0.55f); lineTo(dx + w * 0.25f, h * 0.85f); lineTo(dx + w * 0.62f, h * 0.15f) }
                    drawPath(p, col, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
                if (status == "sent") { val p = Path().apply { moveTo(w * 0.12f, h * 0.55f); lineTo(w * 0.40f, h * 0.85f); lineTo(w * 0.88f, h * 0.15f) }; drawPath(p, col, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
                else { tick(0f); tick(w * 0.32f) }
            }
        }
    }
}

/** A chat's picture: initials for a person (with the green dot when online), a group's or team's sign, or the project's drop. */
@Composable
internal fun ChatAvatar(row: ChatRow?, me: String, size: Dp = 50.dp, online: Boolean = false, name: String? = null, m: org.rafikioutreach.pin.AppModel? = null) {
    Box(Modifier.size(size)) {
        val kind = row?.kind ?: "dm"
        val title = name ?: if (kind == "dm") row?.other(me).orEmpty() else row?.let { chatTitle(it, me) }.orEmpty()
        val photo = row?.photo.orEmpty()
        // the automatic picture (chatpa, contract §1): its own photo → a country team's flag → the project's photo → initials
        val cc = row?.country.orEmpty()
        val pphoto = row?.pphoto.orEmpty()
        when {
            // chat 2: Announcements as a megaphone; a project as its photo with a water drop, else its kind on a tile
            row?.announce == true && photo.isBlank() -> Box(Modifier.size(size).clip(CircleShape).background(C.hue("amber").bg).testTag("av_news"), contentAlignment = Alignment.Center) {
                Icon(ChatIc.get("megaphone"), null, tint = C.hue("amber").fg, modifier = Modifier.size(size * 0.46f)) }
            row != null && kind == "project" && photo.isBlank() -> ProjectChatAvatar(m, row, size)
            m != null && photo.isNotBlank() && kind != "dm" -> ChatImage(m, org.rafikioutreach.pin.data.ChatMedia(photo, "photo"), Modifier.size(size).clip(CircleShape), max = 256)
            kind == "dm" -> PersonAvatar(title, size)
            cc.isNotBlank() -> FlagAvatar(cc, size)
            m != null && pphoto.isNotBlank() -> NetImage(if (pphoto.startsWith("http")) pphoto else m.api.server + pphoto, Modifier.size(size).clip(CircleShape).background(C.hue("blue").bg).testTag("pphoto")) {
                Icon(Ic.get("drop"), null, tint = C.hue("blue").fg, modifier = Modifier.size(size * 0.48f))
            }
            else -> {
                val hu = C.hue(if (row?.announce == true) "amber" else if (kind == "project") "blue" else if (kind == "team") "teal" else nameHue(title))
                Box(Modifier.size(size).clip(CircleShape).background(hu.bg), contentAlignment = Alignment.Center) {
                    if (title.isNotBlank()) Text(initialsOf(title), color = hu.fg, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.32f).sp)
                    else Icon(Ic.get("people"), null, tint = hu.fg, modifier = Modifier.size(size * 0.48f))
                }
            }
        }
        if (online) Box(Modifier.align(Alignment.BottomEnd).size(size * 0.3f).clip(CircleShape).background(C.card).padding(2.dp).clip(CircleShape).background(ChatLook.online).testTag("online_dot"))
    }
}

/** The list's time: "10:42" today, "Yesterday", "Mon" this week, then "24 Oct". */
internal fun chatWhen(ms: Long): String {
    if (ms <= 0) return ""
    val at = Instant.ofEpochMilli(ms)
    val d = at.atZone(Dates.zone()).toLocalDate(); val today = Dates.today()
    val days = ChronoUnit.DAYS.between(d, today)
    return when {
        days == 0L -> Dates.time(at)
        days == 1L -> t("Yesterday", "Jana")
        days in 2..6 -> Dates.weekday(d).substringBefore(' ')
        else -> Dates.day(d)
    }
}
/** A date separator in a chat: "Today", "Yesterday", "Mon 20 Oct". */
internal fun chatDay(ms: Long): String {
    val d = Instant.ofEpochMilli(ms).atZone(Dates.zone()).toLocalDate(); val days = ChronoUnit.DAYS.between(d, Dates.today())
    return when (days) { 0L -> t("Today", "Leo"); 1L -> t("Yesterday", "Jana"); else -> Dates.weekday(d) }
}
/** "last seen 10:42", "last seen yesterday at 10:42", "last seen 21 Oct, 10:42". */
internal fun lastSeen(ms: Long): String {
    if (ms <= 0) return ""
    val at = Instant.ofEpochMilli(ms); val d = at.atZone(Dates.zone()).toLocalDate(); val days = ChronoUnit.DAYS.between(d, Dates.today())
    return when (days) {
        0L -> t("last seen ", "alionekana ") + Dates.time(at)
        1L -> t("last seen yesterday at ", "alionekana jana saa ") + Dates.time(at)
        else -> t("last seen ", "alionekana ") + Dates.dateTime(at)
    }
}

/** Chat wallpapers: a few built in, drawn softly behind the bubbles (light and dark), or none. */
internal val WALLPAPERS = listOf("", "dots", "waves", "leaves", "drops")
internal fun wallName(k: String) = when (k) { "dots" -> t("Dots", "Vitone"); "waves" -> t("Waves", "Mawimbi"); "leaves" -> t("Leaves", "Majani"); "drops" -> t("Drops", "Matone"); else -> t("None", "Hakuna") }

@Composable
internal fun Wallpaper(kind: String, modifier: Modifier) {
    if (kind.isBlank()) return
    val ink = if (C.dark) Color(0x14FFFFFF) else Color(0x120A78C2)
    val tint = if (C.dark) Color(0xFF0F171E) else Color(0xFFEAF3F9)
    Canvas(modifier.background(tint)) {
        val step = 34.dp.toPx()
        var y = 0f; var row = 0
        while (y < size.height + step) {
            var x = if (row % 2 == 0) 0f else step / 2
            while (x < size.width + step) {
                when (kind) {
                    "dots" -> drawCircle(ink, 2.2.dp.toPx(), Offset(x, y))
                    "waves" -> drawPath(Path().apply { moveTo(x - step / 2, y); quadraticTo(x - step / 4, y - 6.dp.toPx(), x, y); quadraticTo(x + step / 4, y + 6.dp.toPx(), x + step / 2, y) }, ink, style = Stroke(1.6.dp.toPx()))
                    "leaves" -> drawPath(Path().apply { moveTo(x, y - 7.dp.toPx()); quadraticTo(x + 7.dp.toPx(), y, x, y + 7.dp.toPx()); quadraticTo(x - 7.dp.toPx(), y, x, y - 7.dp.toPx()) }, ink, style = Stroke(1.4.dp.toPx()))
                    "drops" -> drawPath(Path().apply { moveTo(x, y - 7.dp.toPx()); cubicTo(x + 6.dp.toPx(), y, x + 4.dp.toPx(), y + 6.dp.toPx(), x, y + 6.dp.toPx()); cubicTo(x - 4.dp.toPx(), y + 6.dp.toPx(), x - 6.dp.toPx(), y, x, y - 7.dp.toPx()) }, ink, style = Stroke(1.4.dp.toPx()))
                }
                x += step
            }
            y += step; row++
        }
    }
}

/** The short "whoosh" when a message goes: soft noise sweeping down for a fifth of a second, made on the phone (no sound
 *  file in the app). Quiet when the phone is on silent or vibrate. */
internal object Whoosh {
    var enabled = true
    fun play(ctx: android.content.Context) {
        if (!enabled) return
        val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
        if (am.ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL) return
        Thread {
            runCatching {
                val rate = 22050; val n = rate / 5
                val pcm = ShortArray(n); var lp = 0.0; val rnd = java.util.Random(7)
                for (i in 0 until n) {
                    val p = i.toDouble() / n
                    val a = 0.9 - 0.85 * p // the filter closes: a falling "whoosh"
                    lp += a * ((rnd.nextDouble() * 2 - 1) - lp)
                    val env = kotlin.math.sin(Math.PI * p) * 0.22
                    pcm[i] = (lp * env * Short.MAX_VALUE).toInt().toShort()
                }
                val tr = android.media.AudioTrack.Builder()
                    .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(n * 2).setTransferMode(android.media.AudioTrack.MODE_STATIC).build()
                tr.write(pcm, 0, n); tr.play(); Thread.sleep(260); tr.release()
            }
        }.start()
    }
}

/** "Chat isn't switched on yet": what the apps show while the server has no chat (reason chat-off), with the technical names in a fold. */
@Composable
internal fun ChatOff(modifier: Modifier = Modifier, need: List<String> = emptyList()) {
    var open by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    BackStep(open) { open = false }
    Card(modifier.testTag("chat_off")) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile("chat", C.hue("blue").fg, C.hue("blue").bg, 44.dp)
            androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
            Text(t("Chat isn’t switched on yet. The office can turn it on.", "Gumzo bado halijawashwa. Ofisi inaweza kuliwasha."), style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = C.ink, modifier = Modifier.weight(1f))
        }
        Text(if (open) t("Hide technical details", "Ficha maelezo ya kiufundi") else t("Technical details", "Maelezo ya kiufundi"), style = androidx.compose.material3.MaterialTheme.typography.labelLarge, color = C.blue,
            modifier = Modifier.padding(top = 10.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).clickable(role = androidx.compose.ui.semantics.Role.Button) { open = !open }.padding(vertical = 8.dp).testTag("chat_off_more"))
        if (open) Text(t("Every /api/chat/* call answers {ok:false, reason:'chat-off'}: the Pages project has no CHAT binding yet. Deploy the rafiki-chat worker (workers/chat, Durable Objects), add the CHAT service binding and the CHAT_SECRET in both, and the R2 bucket rafiki-chat-media as CHAT_MEDIA.",
            "Kila mwito wa /api/chat/* unajibu {ok:false, reason:'chat-off'}: mradi wa Pages bado hauna kiungo cha CHAT. Weka worker ya rafiki-chat (workers/chat, Durable Objects), ongeza kiungo cha huduma CHAT na CHAT_SECRET pande zote mbili, na ndoo ya R2 rafiki-chat-media kama CHAT_MEDIA."),
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.testTag("chat_off_tech"))
        if (open && need.isNotEmpty()) Text(t("The server says it needs: ", "Seva inasema inahitaji: ") + need.joinToString("; "), style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = C.muted, modifier = Modifier.padding(top = 6.dp).testTag("chat_off_need"))
    }
}

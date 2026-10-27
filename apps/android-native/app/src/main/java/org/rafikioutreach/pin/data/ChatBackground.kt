package org.rafikioutreach.pin.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.rafikioutreach.pin.BuildConfig
import org.rafikioutreach.pin.MainActivity
import org.rafikioutreach.pin.R
import org.rafikioutreach.pin.t
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Chat notifications (feat-chata): one per chat, in Android's conversation style (MessagingStyle) with the sender's
 * name and picture, grouped under "RAFIKI Pin chat", with a Reply box (RemoteInput) that sends from the notification,
 * and "Mark as read". A muted chat never notifies; a chat with its sound off notifies quietly. Each notification carries
 * its unread count, which launchers show as the badge on the app icon (with a dot on Android 8+).
 */
object ChatNotify {
    const val CH_CHAT = "chat"
    const val CH_QUIET = "chat_quiet"
    /** @mentions: their own channel (chatpa), so they can have a sound of their own in the phone's settings. */
    const val CH_MENTION = "chat_mention"
    private const val GROUP = "rafiki-chat"
    private const val SUMMARY = 7600
    const val KEY_REPLY = "reply"

    fun id(c: String) = 7700 + Math.floorMod(c.hashCode(), 100_000)

    fun channels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_CHAT, t("Chat messages", "Ujumbe wa gumzo"), NotificationManager.IMPORTANCE_HIGH).apply {
            description = t("New messages from the team", "Ujumbe mpya kutoka kwa timu"); setShowBadge(true)
        })
        nm.createNotificationChannel(NotificationChannel(CH_QUIET, t("Chat messages (no sound)", "Ujumbe wa gumzo (bila sauti)"), NotificationManager.IMPORTANCE_LOW).apply {
            description = t("Chats with their sound turned off", "Magumzo yaliyozimwa sauti"); setShowBadge(true); setSound(null, null)
        })
        nm.createNotificationChannel(NotificationChannel(CH_MENTION, t("Chat: you were mentioned", "Gumzo: umetajwa"), NotificationManager.IMPORTANCE_HIGH).apply {
            description = t("When someone @mentions you", "Mtu anapokutaja kwa @"); setShowBadge(true); enableVibration(true); vibrationPattern = longArrayOf(0, 120, 90, 120)
        })
    }

    /** The messages a chat's notification shows (kept, so a new one adds to it and a reply appears under it). */
    private fun store(ctx: Context) = ctx.getSharedPreferences("chat_notes", Context.MODE_PRIVATE)
    private fun history(ctx: Context, c: String): List<JSONObject> = runCatching { JSONArray(store(ctx).getString(c, "[]")).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } }.getOrDefault(emptyList())
    private fun keep(ctx: Context, c: String, list: List<JSONObject>) = store(ctx).edit().putString(c, JSONArray(list.takeLast(8)).toString()).apply()

    /** A round picture with initials, for the sender (no photos leave the server for notifications). */
    fun avatar(name: String): IconCompat {
        val s = 96; val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val cv = Canvas(b)
        val hues = intArrayOf(0xFF0A78C2.toInt(), 0xFF1C8A50.toInt(), 0xFF6E46C8.toInt(), 0xFFB86E00.toInt(), 0xFF0E7F86.toInt(), 0xFFD2553A.toInt())
        var h = 0L; for (ch in name.trim().lowercase()) h = (h * 31 + ch.code) and 0xFFFFFFFFL
        cv.drawCircle(s / 2f, s / 2f, s / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = hues[(h % hues.size).toInt()] })
        val ini = name.trim().split(Regex("\\s+")).mapNotNull { it.firstOrNull()?.uppercaseChar() }.take(2).joinToString("").ifBlank { "·" }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 38f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        cv.drawText(ini, s / 2f, s / 2f - (p.descent() + p.ascent()) / 2, p)
        return IconCompat.createWithBitmap(b)
    }

    /** New messages in one chat (from someone else). [unread] is the chat's count for the badge. */
    fun show(ctx: Context, row: ChatRow, msgs: List<Msg>, me: String, unread: Int, quiet: Boolean) {
        val mentioned = msgs.any { m -> m.mentions.any { it.equals(me, true) } }
        // a muted chat stays quiet, except when I'm @mentioned (as the server's push does, chatpa)
        if (msgs.isEmpty() || (row.muted() && !mentioned)) return
        // Do-Not-Disturb hours (Chat settings): silent, unless @mentions may ring then
        val dnd = ChatSettings(ctx).dnd
        val hush = quiet || (dnd.active() && !(mentioned && dnd.mentions))
        val h = history(ctx, row.id).toMutableList()
        msgs.forEach { m -> if (h.none { it.optString("id") == m.id }) h += JSONObject().put("id", m.id).put("from", m.from).put("text", m.preview()).put("at", m.at) }
        keep(ctx, row.id, h)
        post(ctx, row.id, row.title, row.isGroup, me, unread, hush, alert = true, mention = mentioned && !hush)
    }
    /** The channel a chat notification goes to (tests read it). */
    fun channelFor(quiet: Boolean, mention: Boolean) = if (quiet) CH_QUIET else if (mention) CH_MENTION else CH_CHAT

    /** Draws (or redraws) a chat's notification from what it keeps. */
    fun post(ctx: Context, c: String, title: String, group: Boolean, me: String, unread: Int, quiet: Boolean, alert: Boolean, mention: Boolean = false) {
        if (!AlertWorker.allowed(ctx)) return
        channels(ctx)
        val h = history(ctx, c); if (h.isEmpty()) return
        val you = Person.Builder().setName(t("You", "Wewe")).setKey("me:$me").build()
        val style = NotificationCompat.MessagingStyle(you).setGroupConversation(group)
        if (group) style.conversationTitle = title
        h.forEach { o ->
            val from = o.optString("from")
            val mine = from.equals(me, true) || from == "me"
            style.addMessage(o.optString("text"), o.optLong("at"), if (mine) null else Person.Builder().setName(from).setKey(from).setIcon(avatar(from)).build())
        }
        val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("chat", c)
        val pi = PendingIntent.getActivity(ctx, id(c), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Reply right from the notification: RemoteInput needs a mutable PendingIntent
        val replyPi = PendingIntent.getBroadcast(ctx, id(c), Intent(ctx, ChatReplyReceiver::class.java).setAction(ChatReplyReceiver.REPLY).putExtra("c", c).putExtra("title", title).putExtra("group", group),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
        val reply = NotificationCompat.Action.Builder(R.drawable.ic_stat_pin, t("Reply", "Jibu"), replyPi)
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel(t("Reply", "Jibu")).build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).setShowsUserInterface(false).setAllowGeneratedReplies(true).build()
        val readPi = PendingIntent.getBroadcast(ctx, id(c) + 1, Intent(ctx, ChatReplyReceiver::class.java).setAction(ChatReplyReceiver.READ).putExtra("c", c),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val read = NotificationCompat.Action.Builder(R.drawable.ic_stat_pin, t("Mark as read", "Weka kama imesomwa"), readPi)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ).setShowsUserInterface(false).build()
        val n = NotificationCompat.Builder(ctx, channelFor(quiet, mention)).setSmallIcon(R.drawable.ic_stat_pin).setStyle(style)
            .setContentIntent(pi).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_MESSAGE).setGroup(GROUP).setColor(0xFF0A78C2.toInt())
            .setNumber(unread.coerceAtLeast(0)).setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL).setOnlyAlertOnce(!alert)
            .setSilent(quiet || !alert).addAction(reply).addAction(read).build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id(c), n) }
        summary(ctx)
    }

    /** The group's summary line ("3 chats"), which also carries the total for the launcher badge. */
    private fun summary(ctx: Context) {
        val chats = store(ctx).all.keys.size
        if (chats == 0) return
        val total = store(ctx).all.values.sumOf { v -> runCatching { JSONArray(v as String).length() }.getOrDefault(0) }
        val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("chat", "")
        val pi = PendingIntent.getActivity(ctx, SUMMARY, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CH_CHAT).setSmallIcon(R.drawable.ic_stat_pin).setContentTitle(t("RAFIKI Pin chat", "Gumzo la RAFIKI Pin"))
            .setContentText(if (chats == 1) t("New messages", "Ujumbe mpya") else t("New messages in $chats chats", "Ujumbe mpya kwenye magumzo $chats"))
            .setGroup(GROUP).setGroupSummary(true).setContentIntent(pi).setAutoCancel(true).setNumber(total).setSilent(true).setColor(0xFF0A78C2.toInt()).build()
        runCatching { NotificationManagerCompat.from(ctx).notify(SUMMARY, n) }
    }

    /** The chat was read (opened in the app, or "Mark as read"): its notification goes. */
    fun cancel(ctx: Context, c: String) {
        store(ctx).edit().remove(c).apply()
        runCatching { NotificationManagerCompat.from(ctx).cancel(id(c)) }
        if (store(ctx).all.isEmpty()) runCatching { NotificationManagerCompat.from(ctx).cancel(SUMMARY) }
    }

    /** My reply from the notification appears under the messages straight away. */
    fun addMine(ctx: Context, c: String, me: String, text: String) {
        keep(ctx, c, history(ctx, c) + JSONObject().put("id", "r" + System.currentTimeMillis()).put("from", me).put("text", text).put("at", System.currentTimeMillis()))
    }
}

/** Reply and "Mark as read" from a chat notification. The reply goes to the outbox, so it's sent even without signal. */
class ChatReplyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val c = intent.getStringExtra("c") ?: return
        val prefs = Prefs(ctx)
        org.rafikioutreach.pin.Lang.sw = prefs.lang == "sw"
        val me = prefs.name
        val api = testApi ?: Api(BuildConfig.SERVER, { prefs.pin }, { prefs.session }).also { it.gen = { prefs.pinGen } }
        val pending: PendingResult? = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    REPLY -> {
                        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(ChatNotify.KEY_REPLY)?.toString()?.trim().orEmpty()
                        if (text.isNotBlank()) {
                            val cid = "n" + UUID.randomUUID().toString().replace("-", "").take(20)
                            ChatOutbox(File(File(ctx.filesDir, "chat"), "outbox.json")).add(ChatOutbox.Item(cid, c, JSONObject().put("kind", "text").put("text", text), emptyList(), System.currentTimeMillis()))
                            ChatNotify.addMine(ctx, c, me, text)
                            ChatNotify.post(ctx, c, intent.getStringExtra("title").orEmpty(), intent.getBooleanExtra("group", false), me, 0, quiet = true, alert = false)
                            ChatSendWorker.enqueue(ctx)
                            // answering is reading
                            ChatOutbox(File(File(ctx.filesDir, "chat"), "outbox.json")).flush(ChatApi(api))
                        }
                    }
                    READ -> {
                        ChatNotify.cancel(ctx, c)
                        val last = runCatching { ChatApi(api).msgs(c, limit = 1).json.optJSONArray("msgs")?.optJSONObject(0)?.optString("id") }.getOrNull()
                        if (!last.isNullOrBlank()) ChatApi(api).read(c, last)
                    }
                }
            } finally { pending?.finish() }
        }
    }
    companion object { const val REPLY = "org.rafikioutreach.pin.CHAT_REPLY"; const val READ = "org.rafikioutreach.pin.CHAT_READ"
        /** Tests: the API to answer with (a fake server). */
        @Volatile var testApi: Api? = null }
}

/** Sends the chat outbox when there's signal, also after the app was closed or killed (WorkManager keeps the job). */
class ChatSendWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (prefs.pin.isBlank() && prefs.session.isBlank()) return Result.success()
        val api = Api(BuildConfig.SERVER, { prefs.pin }, { prefs.session }).also { it.gen = { prefs.pinGen } }
        val box = ChatOutbox(File(File(applicationContext.filesDir, "chat"), "outbox.json"))
        if (box.count() == 0) return Result.success()
        val r = box.flush(ChatApi(api))
        return if (r.waiting > 0 && !r.off && !r.locked && runAttemptCount < 20) Result.retry() else Result.success()
    }
    companion object {
        fun enqueue(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<ChatSendWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            runCatching { WorkManager.getInstance(ctx).enqueueUniqueWork("chat-send", ExistingWorkPolicy.APPEND_OR_REPLACE, req) }
        }
    }
}

/**
 * New chat messages while the app is closed (no Firebase yet): about every 15 minutes, when there's signal, Android
 * lets this ask list?since; each chat with new messages from someone else gets its notification (not muted ones).
 * With the app open the WebSocket does this instantly. Firebase Cloud Messaging would make it instant when closed too.
 */
class ChatWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (!prefs.phoneAlerts || (prefs.pin.isBlank() && prefs.session.isBlank()) || DonorPrefs(applicationContext).token.isNotBlank()) return Result.success()
        check(applicationContext, prefs, Api(BuildConfig.SERVER, { prefs.pin }, { prefs.session }).also { it.gen = { prefs.pinGen } })
        return Result.success()
    }
    companion object {
        private const val WORK = "rafiki-chat"
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<ChatWorker>(15, TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            runCatching { WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req) }
        }
        fun cancel(ctx: Context) { runCatching { WorkManager.getInstance(ctx).cancelUniqueWork(WORK) } }

        /** One check: send what waits, then notify what's new. Returns the chats notified (for tests). */
        suspend fun check(ctx: Context, prefs: Prefs, api: Api): List<String> {
            org.rafikioutreach.pin.Lang.sw = prefs.lang == "sw"
            val chat = ChatApi(api)
            ChatOutbox(File(File(ctx.filesDir, "chat"), "outbox.json")).flush(chat)
            val sp = ctx.getSharedPreferences("chat", Context.MODE_PRIVATE)
            val since = sp.getLong("bg_since", 0L)
            val r = chat.list(since)
            if (!r.ok) return emptyList()
            val me = r.json.optString("me").takeIf { it.isNotBlank() && it != "null" } ?: prefs.name
            val a = r.json.optJSONArray("chats") ?: return emptyList()
            val rows = (0 until a.length()).mapNotNull { runCatching { ChatRow.from(a.getJSONObject(it)) }.getOrNull() }
            // the copy on the phone is brought up to date too, so the app opens on the news
            val cache = ChatCache(File(ctx.filesDir, "chat"))
            cache.loadList()?.let { (old, m, p) -> cache.saveList(old.map { o -> rows.firstOrNull { it.id == o.id } ?: o } + rows.filter { n -> old.none { it.id == n.id } }, m.ifBlank { me }, p) }
                ?: cache.saveList(rows, me, ChatCache.presenceFrom(r.json.optJSONObject("presence")))
            val first = since == 0L
            sp.edit().putLong("bg_since", chatMs(r.json.opt("now")).takeIf { it > 0 } ?: (System.currentTimeMillis() - 60_000)).apply()
            val shown = mutableListOf<String>()
            for (row in rows) {
                val last = row.last ?: continue
                if (row.unread <= 0 || last.from.equals(me, true)) continue
                val seen = sp.getLong("bg_at_${row.id}", 0L)
                if (last.at <= seen) continue
                sp.edit().putLong("bg_at_${row.id}", last.at).apply()
                chat.delivered(row.id, last.id)
                // the first check only remembers where things are, so nobody gets a flood of old news
                if (first || (row.muted() && !row.mention)) continue
                val mr = chat.msgs(row.id, limit = row.unread.coerceIn(1, 6))
                val msgs = mr.json.optJSONArray("msgs")?.let { m -> (0 until m.length()).mapNotNull { runCatching { Msg.from(m.getJSONObject(it), row.id) }.getOrNull() } }.orEmpty()
                    .filter { !it.from.equals(me, true) && it.at > seen && !it.deleted }
                if (msgs.isEmpty()) continue
                ChatNotify.show(ctx, row, msgs, me, row.unread, sp.getBoolean("quiet_${row.id}", false))
                shown += row.id
            }
            return shown
        }
    }
}

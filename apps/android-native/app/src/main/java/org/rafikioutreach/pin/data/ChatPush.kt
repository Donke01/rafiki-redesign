package org.rafikioutreach.pin.data

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.rafikioutreach.pin.BuildConfig
import java.io.File

/**
 * Instant chat notifications with Firebase Cloud Messaging (chatpa, S/chatpolish-contract.md §6).
 *
 * It's optional. The build turns app/google-services.json (gitignored) into string resources fcm_app_id, fcm_api_key,
 * fcm_project_id and fcm_sender_id; without them [configured] is false and nothing here runs: the app keeps its
 * 15-minute check plus the socket. With them, the app starts Firebase itself, registers its token with the server
 * (POST /api/chat/device) and removes it on sign-out. The server sends data-only messages; [handle] turns one into
 * the same notification as the background check (MessagingStyle, Reply box, grouped per chat, Do-Not-Disturb).
 */
object ChatPush {
    private fun res(ctx: Context, name: String): String = ctx.resources.getIdentifier(name, "string", ctx.packageName).takeIf { it != 0 }?.let { ctx.getString(it) }.orEmpty()

    /** The Firebase settings from the build, or null when the build had no google-services.json. */
    fun options(ctx: Context): FirebaseOptions? {
        val app = res(ctx, "fcm_app_id"); val key = res(ctx, "fcm_api_key")
        if (app.isBlank() || key.isBlank()) return null
        return FirebaseOptions.Builder().setApplicationId(app).setApiKey(key).setProjectId(res(ctx, "fcm_project_id")).setGcmSenderId(res(ctx, "fcm_sender_id")).build()
    }
    fun configured(ctx: Context) = options(ctx) != null
    /** The app is in front (its socket brings messages at once, so a push shows nothing). Set by [ChatHub]. */
    @Volatile var inFront = false
    /** Tests: never start Firebase (Robolectric), whatever the build had. */
    @Volatile var disabled = android.os.Build.FINGERPRINT == "robolectric"

    /** Starts Firebase once, if this build can. */
    fun init(ctx: Context): Boolean {
        if (disabled) return false
        val o = options(ctx) ?: return false
        return runCatching { if (FirebaseApp.getApps(ctx).isEmpty()) FirebaseApp.initializeApp(ctx.applicationContext, o); true }.getOrDefault(false)
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences("chat", Context.MODE_PRIVATE)

    /** Signed in with a name: ask Firebase for this phone's token and tell the server (again when it changes). */
    fun register(ctx: Context, api: Api) {
        if (!init(ctx)) return
        runCatching {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                val tok = if (task.isSuccessful) task.result else null
                if (!tok.isNullOrBlank()) CoroutineScope(Dispatchers.IO).launch { send(ctx, api, tok) }
            }
        }
    }
    suspend fun send(ctx: Context, api: Api, token: String) {
        val r = ChatApi(api).device(token)
        if (r.ok) sp(ctx).edit().putString("push_token", token).apply()
    }

    /** Signing out: the server forgets this phone's token first (with the old sign-in), then Firebase's token goes. */
    suspend fun signOut(ctx: Context, api: Api) {
        val tok = sp(ctx).getString("push_token", null) ?: return
        runCatching { ChatApi(api).deviceRemove(tok) }
        sp(ctx).edit().remove("push_token").apply()
        if (!disabled && FirebaseApp.getApps(ctx).isNotEmpty()) runCatching { FirebaseMessaging.getInstance().deleteToken() }
    }

    /**
     * One push (all values are strings): {t:'msg', c, id, from, title, kind, text, media, mention, unread, badge, at, sys, body}.
     * Returns true when it showed a notification. A chat that's open on screen shows nothing (the socket has it).
     */
    fun handle(ctx: Context, data: Map<String, String>, me: String, front: Boolean = false): Boolean {
        if ((data["t"] ?: "msg") != "msg" || front) return false
        val c = data["c"].orEmpty(); if (c.isBlank()) return false
        val from = data["from"].orEmpty()
        if (from.equals(me, true)) return false
        val kind = data["kind"].orEmpty().ifBlank { "text" }
        val sys = data["sys"].orEmpty()
        val media = data["media"].orEmpty()
        val o = JSONObject().put("id", data["id"].orEmpty()).put("c", c).put("from", from).put("kind", if (sys.isNotBlank()) "sys" else kind).put("text", data["text"].orEmpty())
            .put("at", data["at"]?.toLongOrNull() ?: System.currentTimeMillis())
        if (media.isNotBlank()) o.put("media", JSONArray().put(JSONObject().put("id", "").put("type", media)))
        if (data["mention"] == "1") o.put("mentions", JSONArray().put(me))
        if (sys.isNotBlank()) o.put("sys", JSONObject().put("type", sys).put("by", from))
        var msg = Msg.from(o, c)
        if (msg.kind == "sys" || (msg.text.isBlank() && media.isBlank() && kind == "text")) msg = msg.copy(text = data["body"].orEmpty().ifBlank { msg.text })
        // what this phone knows of the chat (title, group or not, mute); the push's own title otherwise
        val cache = ChatCache(File(ctx.filesDir, "chat"))
        val known = cache.loadList()?.first?.firstOrNull { it.id == c }
        val title = data["title"].orEmpty()
        val row = known?.copy(unread = data["unread"]?.toIntOrNull() ?: known.unread) ?: ChatRow(c, if (c.startsWith("d:")) "dm" else "group", title, unread = data["unread"]?.toIntOrNull() ?: 1)
        val unread = data["unread"]?.toIntOrNull() ?: row.unread
        ChatNotify.show(ctx, row, listOf(msg), me, unread, sp(ctx).getBoolean("quiet_$c", false))
        return true
    }
}

/** Firebase's messages arrive here (only when the build had google-services.json; see [ChatPush]). */
class ChatPushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val prefs = Prefs(applicationContext)
        if (prefs.pin.isBlank() && prefs.session.isBlank()) return
        val api = Api(BuildConfig.SERVER, { prefs.pin }, { prefs.session }).also { it.gen = { prefs.pinGen } }
        CoroutineScope(Dispatchers.IO).launch { ChatPush.send(applicationContext, api, token) }
    }
    override fun onMessageReceived(message: RemoteMessage) {
        val prefs = Prefs(applicationContext)
        if (!prefs.phoneAlerts || (prefs.pin.isBlank() && prefs.session.isBlank())) return
        org.rafikioutreach.pin.Lang.sw = prefs.lang == "sw"
        ChatPush.handle(applicationContext, message.data, prefs.name, ChatPush.inFront)
    }
}

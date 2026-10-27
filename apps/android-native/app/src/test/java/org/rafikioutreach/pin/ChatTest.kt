package org.rafikioutreach.pin

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.rafikioutreach.pin.data.Api
import org.rafikioutreach.pin.data.ChatApi
import org.rafikioutreach.pin.data.ChatHub
import org.rafikioutreach.pin.data.ChatMedia
import org.rafikioutreach.pin.data.ChatNotify
import org.rafikioutreach.pin.data.ChatOutbox
import org.rafikioutreach.pin.data.ChatReplyReceiver
import org.rafikioutreach.pin.data.ChatRow
import org.rafikioutreach.pin.data.ChatWorker
import org.rafikioutreach.pin.data.Msg
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.data.chatPreview
import org.rafikioutreach.pin.ui.ChatNav
import org.rafikioutreach.pin.ui.Contact
import org.rafikioutreach.pin.ui.Look
import org.rafikioutreach.pin.ui.MicTest
import org.rafikioutreach.pin.ui.Motion
import org.rafikioutreach.pin.ui.Onb
import org.rafikioutreach.pin.ui.RafikiTheme
import org.rafikioutreach.pin.ui.VisitDrafts
import org.rafikioutreach.pin.ui.Whoosh
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.Instant

/**
 * RAFIKI Pin Chat on Android (feat-chata), against a fake chat API and a fake WebSocket ([FakeChat]).
 * Screenshots "chat-…" at 360 dp, English and Kiswahili, light and dark.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class ChatTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var fake: FakeChat
    private lateinit var wire: FakeChat.Wire

    @After fun reset() { ChatReplyReceiver.testApi = null; Lang.sw = false; Look.mode = ""; Motion.on = true; MicTest.pretend = false; Whoosh.enabled = true; ChatNav.req = null; ChatNav.focus = null; Contact.who = null; Onb.enabled = true }

    private fun proj(id: String, t: String) = JSONObject().put("id", id).put("title", t).put("country", "Kenya").put("ck", "ke").put("area", "Kitale")
        .put("type", "Borehole well").put("status", "Completed").put("pump", "manual").put("gps", JSONArray().put(1.01).put(35.0))
    private fun snapshot(admin: Boolean = true) = JSONObject().put("ok", true).put("who", "Reid Anderson").put("admin", admin).put("reviewOpen", 0)
        .put("projects", JSONArray().put(proj("105", "Kapsaret Borehole")).put(proj("101", "Chepkongi Dispensary")))
        .put("repairs", JSONObject().put("105", JSONObject().put("state", "repair").put("by", "Meril Kiprop").put("at", fake.ago(300)).put("urgency", "month").put("t", "Kapsaret Borehole")))
        .put("visits", JSONObject()).put("jobs", JSONArray()).put("assigned", JSONArray()).put("confirmed", JSONObject())
    private fun team() = JSONArray().apply { listOf("Grace Wanjiru", "Meril Kiprop", "Peter Otieno", "Reid Anderson", "Jessica Anderson").forEach { put(JSONObject().put("name", it).put("home", "ke").put("lastSeen", fake.ago(30)).put("src", "app")
        .apply { if (it == "Grace Wanjiru") put("phone", "0722 000 333").put("email", "grace@rafikioutreach.org") }) } }

    /** A team with a few chats: All RAFIKI (pinned), Kenya team, a direct message, a group, a project thread. */
    private fun world() {
        fake = FakeChat()
        fake.chat("t_all", "team", "All RAFIKI", listOf("Reid Anderson", "Grace Wanjiru", "Meril Kiprop", "Peter Otieno"), listOf("Reid Anderson"), pinned = true)
        fake.chat("g1", "group", "Kitale repairs", listOf("Reid Anderson", "Grace Wanjiru", "Meril Kiprop"), listOf("Reid Anderson"), unread = 3, mention = true)
        fake.chat("dm_grace", "dm", "Grace Wanjiru", listOf("Reid Anderson", "Grace Wanjiru"), unread = 1)
        fake.chat("p_105", "project", "Kapsaret Borehole", listOf("Reid Anderson", "Meril Kiprop"), project = "105")
        fake.chat("t_ke", "team", "Kenya team", listOf("Reid Anderson", "Meril Kiprop"), muted = "always")
        fake.chat("g_old", "group", "2025 drilling", listOf("Reid Anderson", "Peter Otieno"), listOf("Reid Anderson"), archived = true)
        fake.msg("t_all", "Jessica Anderson", "Welcome to RAFIKI Pin chat, everyone!", 60 * 24 * 2)
        fake.msg("g1", "Meril Kiprop", "Handle on the Kapsaret pump is loose again", 60 * 24 + 30)
        fake.msg("g1", "Reid Anderson", "Thanks Meril. Can you check it Thursday?", 60 * 24 + 20, status = "read")
        fake.msg("g1", "Grace Wanjiru", "I can bring the spare handle from Kitale", 50)
        fake.msg("g1", "Grace Wanjiru", "It’s in the store", 49) { put("reacts", JSONObject().put("👍", JSONArray().put("Reid Anderson").put("Meril Kiprop"))) }
        val q = fake.msgs["g1"]!![2]
        fake.msg("g1", "Reid Anderson", "Perfect, please do", 30, status = "delivered") { put("reply", JSONObject().put("id", q.optString("id")).put("from", "Grace Wanjiru").put("kind", "text").put("text", q.optString("text"))) }
        fake.msg("g1", "Meril Kiprop", "", 12, kind = "voice") { put("media", JSONArray().put(JSONObject().put("id", "v1").put("type", "voice").put("dur", 12).put("wave", JSONArray((0 until 40).map { 10 + (it * 29) % 85 })))) }
        fake.msg("g1", "Grace Wanjiru", "@Reid Anderson see the photo of the pump https://pin.rafikioutreach.org/pin#p/105", 6) { put("mentions", JSONArray().put("Reid Anderson")) }
        fake.msg("dm_grace", "Grace Wanjiru", "", 8, kind = "media") { put("media", JSONArray().put(JSONObject().put("id", "ph1").put("type", "photo").put("w", 1200).put("h", 900))) }
        fake.msg("p_105", "Meril Kiprop", "", 200) { put("kind", "sys").put("text", "").put("sys", JSONObject().put("type", "repair").put("project", "105").put("by", "Meril Kiprop")) }
        fake.msg("p_105", "Reid Anderson", "Assigned to Meril for Thursday", 190, status = "read")
        fake.msg("t_ke", "Peter Otieno", "Fuel prices went up in Eldoret", 300)
        fake.presence.put("Grace Wanjiru", JSONObject().put("online", true).put("seen", fake.ago(0))).put("Meril Kiprop", JSONObject().put("online", false).put("seen", fake.ago(42)))
    }

    private fun model(admin: Boolean = true, offline: Boolean = false): AppModel {
        Onb.enabled = false; Motion.on = false; Whoosh.enabled = false
        Prefs(app).apply { pin = "9999"; name = "Reid Anderson"; saveSnapshot(snapshot(admin)); filter = "all"; onb = true; lang = if (Lang.sw) "sw" else "en"; mePane = "me"; phoneAlerts = false }
        File(app.filesDir, "chat").deleteRecursively()
        app.getSharedPreferences("chat", 0).edit().clear().commit(); app.getSharedPreferences("chat_drafts", 0).edit().clear().commit(); app.getSharedPreferences("chat_notes", 0).edit().clear().commit()
        val m = AppModel(app)
        fake.online = !offline
        m.api.transport = { method, url, _, body ->
            fake.handle(method, url, body) ?: when {
                url.contains("activity=1") -> 200 to "{\"ok\":true,\"activity\":[]}"
                url.contains("team=1") || url.contains("view=team") -> 200 to JSONObject().put("ok", true).put("team", team()).toString()
                url.contains("view=") -> 200 to "{\"ok\":true,\"items\":[]}"
                url.contains("history=") -> 200 to "{\"ok\":true,\"notes\":[]}"
                url.contains("pin-auth/me") -> 200 to "{\"ok\":true,\"available\":false}"
                url.contains("version.json") -> 0 to ""
                url.contains("?id=") -> 200 to "{\"ok\":true,\"last\":null,\"canUndo\":false,\"visits\":[]}"
                method == "GET" -> 200 to snapshot(admin).toString()
                else -> 200 to "{\"ok\":true,\"updateId\":\"9001\"}"
            }
        }
        // photos and voice notes from the server: a small picture
        m.api.fetch = { _, _ -> 200 to photoBytes() }
        wire = fake.Wire()
        m.chat.wire = wire
        return m
    }
    private fun photoBytes(): ByteArray { val b = Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888); Canvas(b).drawColor(0xFF6FA8DC.toInt()); return java.io.ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray() }

    private fun show(m: AppModel, dark: Boolean = false) {
        rule.setContent { RafikiTheme(dark = dark) { Box(Modifier.size(360.dp, 800.dp)) { App(m) } } }
        waitTag("nav_chat"); settle(1500)
    }
    private fun settle(ms: Long = 800) { rule.mainClock.advanceTimeBy(ms); rule.waitForIdle() }
    private fun until(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond()) { assertTrue("timed out: $what", System.currentTimeMillis() < end); rule.mainClock.advanceTimeBy(100); rule.waitForIdle(); Thread.sleep(20) }
    }
    /** Runs on the main thread while the test keeps the main looper turning (runBlocking there could deadlock with the app's own work). */
    private fun <T> onMain(block: suspend () -> T): T {
        var r: Result<T>? = null
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch { r = runCatching { block() } }
        until("main work") { r != null }
        return r!!.getOrThrow()
    }
    /** Scrolls the messages from the newest to the oldest and says which of [tags] appeared. */
    private fun seenScrolling(tags: List<String>): Set<String> {
        val seen = mutableSetOf<String>()
        for (i in 0 until 40) {
            if (runCatching { rule.onNodeWithTag("chat_msgs").performScrollToIndex(i) }.isFailure) break
            settle(300); tags.filter { tagged(it) }.forEach { seen += it }
        }
        rule.onNodeWithTag("chat_msgs").performScrollToIndex(0); settle()
        return seen
    }
    private fun seenText(text: String): Boolean {
        var found = false
        for (i in 0 until 40) { if (runCatching { rule.onNodeWithTag("chat_msgs").performScrollToIndex(i) }.isFailure) break; settle(300); if (has(text)) { found = true; break } }
        rule.onNodeWithTag("chat_msgs").performScrollToIndex(0); settle(); return found
    }
    private fun tagged(t: String) = rule.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitTag(t: String) = until("tag $t") { tagged(t) }
    private fun has(t: String) = rule.onAllNodesWithText(t, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(t: String) = until("text $t") { has(t) }
    private fun tap(t: String) { rule.onNodeWithTag(t, useUnmergedTree = true).performClick(); settle() }
    private fun back() {
        rule.runOnUiThread {
            val d = ShadowDialog.getShownDialogs().lastOrNull { it.isShowing }
            @Suppress("DEPRECATION")
            if (d != null) d.onBackPressed() else rule.activity.onBackPressedDispatcher.onBackPressed()
        }
        settle()
    }
    private fun shot(name: String) {
        settle(1200)
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun sheetShot(name: String) {
        settle(900)
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); val c = Canvas(b); v.draw(c)
        val d = (ShadowDialog.getShownDialogs().lastOrNull { it.isShowing } ?: ShadowDialog.getLatestDialog())?.window?.decorView
        if (d != null) { c.drawColor(0x66000000); c.save(); c.translate(0f, (v.height - d.height).toFloat()); d.draw(c); c.restore() }
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun openChat(id: String) { tap("nav_chat"); waitTag("chatrow_$id"); rule.onNodeWithTag("chat_list").performScrollToNode(hasTestTag("chatrow_$id")); tap("chatrow_$id"); waitTag("chat_room"); settle(1500) }
    private fun type(text: String) { rule.onNodeWithTag("composer_input").performTextInput(text); settle() }

    // --- the list -----------------------------------------------------------------------------------------------------
    private fun listLooks(sw: Boolean, dark: Boolean) {
        Lang.sw = sw; world(); val m = model(); show(m, dark)
        tap("nav_chat"); waitTag("chatrow_g1")
        // pinned first, newest next; previews, unread, @, muted, the online dot
        assertTrue(tagged("chat_pinned_label"))
        val order = rule.onAllNodes(hasTestTag("chatrow_t_all") or hasTestTag("chatrow_g1") or hasTestTag("chatrow_dm_grace"), useUnmergedTree = true).fetchSemanticsNodes().map { n -> n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag) }
        assertEquals(listOf("chatrow_t_all", "chatrow_g1", "chatrow_dm_grace"), order)
        assertTrue(has(if (sw) "Grace: @Reid Anderson" else "Grace: @Reid Anderson"))
        assertTrue(has("📷 " + (if (sw) "Picha" else "Photo")))
        assertTrue(tagged("row_mention") && tagged("row_muted") && tagged("online_dot"))
        assertFalse("archived chats live under Archived", tagged("chatrow_g_old")); assertTrue(tagged("chat_archived_row"))
        rule.onNodeWithTag("chat_badge", useUnmergedTree = true).assertTextEquals("4")
        shot("chat-01-list-" + (if (sw) "sw" else "en") + (if (dark) "-dark" else "-light"))
    }
    @Test fun list_en_light() = listLooks(false, false)
    @Test fun list_sw_dark() = listLooks(true, true)

    @Test fun search_titles_and_messages_and_back_clears_it() {
        world(); val m = model(); show(m)
        tap("nav_chat"); waitTag("chatrow_g1")
        rule.onNodeWithTag("chat_search").performTextInput("spare"); settle(600)
        until("server search") { tagged("search_msgs_label") }
        assertTrue(has("I can bring the spare handle"))
        assertTrue(fake.calls.any { it.startsWith("GET search?q=spare") })
        shot("chat-09-search")
        back(); assertTrue("back clears the search", rule.onNodeWithTag("chat_search").fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText)?.text.isNullOrEmpty())
        rule.onNodeWithTag("chat_search").performTextInput("Kitale"); settle()
        assertTrue(tagged("chatrow_g1")); assertFalse(tagged("chatrow_dm_grace"))
        // start over: away via the bottom bar and back, the search is gone
        tap("nav_map"); tap("nav_chat"); waitTag("chatrow_dm_grace")
    }

    @Test fun swipe_to_archive_with_undo_and_long_press_pin() {
        world(); val m = model(); show(m)
        tap("nav_chat"); waitTag("chatrow_dm_grace")
        rule.onNodeWithTag("chatrow_dm_grace").performTouchInput { swipeLeft(startX = right - 10f, endX = left + 10f, durationMillis = 300) }; settle(1200)
        until("archived") { fake.chats["dm_grace"]!!.optBoolean("archived") }
        assertFalse(tagged("chatrow_dm_grace")); waitTag("chat_undo")
        tap("chat_undo_undo"); until("unarchived") { !fake.chats["dm_grace"]!!.optBoolean("archived") }; waitTag("chatrow_dm_grace")
        // swipe right: mute for 8 hours
        rule.onNodeWithTag("chatrow_dm_grace").performTouchInput { swipeRight(startX = left + 10f, endX = right - 10f, durationMillis = 300) }; settle(1200)
        until("muted") { fake.chats["dm_grace"]!!.optLong("muted") > System.currentTimeMillis() }
        // hold: pin
        rule.onNodeWithTag("chatrow_g1").performTouchInput { longClick() }; settle(); waitTag("chatrow_sheet")
        sheetShot("chat-10-row-actions")
        tap("cr_pin"); until("pinned") { fake.chats["g1"]!!.optBoolean("pinned") }
    }

    // --- inside a chat --------------------------------------------------------------------------------------------------
    private fun roomLooks(sw: Boolean, dark: Boolean, name: String) {
        Lang.sw = sw; world(); val m = model(); show(m, dark)
        openChat("g1")
        waitTag("bubble_mine"); waitTag("sender")
        // names on the first bubble of a run, quotes, reactions, ticks (read = blue, delivered = grey), the voice note, the mention and the project card
        val seen = seenScrolling(listOf("quote", "tick_read", "tick_delivered", "voice_play", "project_card", "sender"))
        assertEquals(setOf("quote", "tick_read", "tick_delivered", "voice_play", "project_card", "sender"), seen)
        // the quick replies row (chatpa) leaves less room: the "Today" chip may sit a little higher
        assertTrue(seenText(if (sw) "Leo" else "Today"))
        assertTrue("yesterday's separator further up", seenText(if (sw) "Jana" else "Yesterday"))
        // read as it opened: the server is told up to the newest
        until("read") { fake.calls.any { it.startsWith("POST read") && it.contains("\"c\":\"g1\"") } }
        rule.onNodeWithTag("chat_msgs").performScrollToNode(hasTestTag("unread_line")); settle()
        shot(name)
    }
    @Test fun room_en_light() = roomLooks(false, false, "chat-02-room-en-light")
    @Test fun room_sw_dark() = roomLooks(true, true, "chat-03-room-sw-dark")

    @Test fun send_text_with_mention_reply_by_swipe_and_ticks() {
        world(); val m = model(); show(m)
        openChat("g1")
        // swipe right on Grace's message: it's quoted
        val target = rule.onAllNodesWithTag("bubble_theirs", useUnmergedTree = true).fetchSemanticsNodes().size
        assertTrue(target > 0)
        val gid = fake.msgs["g1"]!!.last().optString("id")
        rule.onNodeWithTag("msg_$gid").performTouchInput { swipeRight(startX = left + 30f, endX = left + 400f, durationMillis = 250) }; settle()
        waitTag("reply_bar")
        type("Thanks @Gr"); waitTag("mentions"); tap("mention_Grace Wanjiru")
        type("on my way")
        tap("send")
        until("sent") { fake.calls.any { it.startsWith("POST send") && it.contains("Thanks @Grace Wanjiru on my way") } }
        val sent = fake.calls.last { it.startsWith("POST send") }
        assertTrue(sent, sent.contains("\"mentions\":[\"Grace Wanjiru\"]") && sent.contains("\"reply\":\"$gid\"") && !sent.contains("_reply"))
        until("one grey tick") { tagged("tick_sent") }
        // the server says Grace read it: blue
        val mine = fake.msgs["g1"]!!.last()
        rule.runOnUiThread { m.chat.onEvent(JSONObject().put("t", "status").put("c", "g1").put("upTo", mine.optString("id")).put("status", "read")) }; settle()
        assertTrue(rule.onAllNodesWithTag("tick_sent", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        shot("chat-04-sent-reply-mention")
    }

    @Test fun offline_message_waits_with_a_clock_and_survives_the_app_closing() {
        world(); val m = model(); show(m)
        openChat("dm_grace")
        fake.online = false
        type("Are you at the site?"); tap("send")
        waitTag("tick_queued")
        assertEquals(1, m.chat.outbox.count())
        shot("chat-05-offline-clock")
        // the app is killed: a new model reads the outbox from the phone, the message is still there with its clock
        val m2 = AppModel(app); m2.api.transport = m.api.transport
        val waiting = m2.chat.msgs("dm_grace").value.last()
        assertEquals("Are you at the site?", waiting.text); assertTrue(waiting.queued)
        // signal again: it goes once, with the same client id, and sending it twice is safe
        fake.online = true
        onMain { m2.chat.flush() }
        until("outbox empty") { m2.chat.outbox.count() == 0 }
        val sends = fake.calls.filter { it.startsWith("POST send") && it.contains("Are you at the site?") }
        val cid = JSONObject(sends.last().substringAfter("send ")).optString("cid")
        assertTrue(cid.isNotBlank())
        assertTrue(onMain { ChatApi(m2.api).send(JSONObject().put("c", "dm_grace").put("cid", cid).put("kind", "text").put("text", "Are you at the site?")).ok })
        assertEquals(1, fake.msgs["dm_grace"]!!.count { it.optString("text") == "Are you at the site?" })
        assertFalse(m2.chat.msgs("dm_grace").value.last().queued)
    }

    @Test fun background_sender_and_worker_send_the_outbox() {
        world(); val m = model()
        fake.online = false
        rule.runOnUiThread { m.chat.send("g1", "text", "Written on the road") }
        // the app's own try (no signal) finishes; the message waits in the file
        until("first try done") { !ChatOutbox.lock.isLocked }
        assertEquals(1, ChatOutbox(File(File(app.filesDir, "chat"), "outbox.json")).count())
        fake.online = true
        // what the background sender does: a fresh outbox and API, from the file alone
        val api = Api(BuildConfig.SERVER, { "9999" }).also { it.transport = m.api.transport }
        val r = onMain { ChatOutbox(File(File(app.filesDir, "chat"), "outbox.json")).flush(ChatApi(api)) }
        assertEquals(1, r.sent.size); assertEquals(0, r.waiting)
        assertTrue(fake.msgs["g1"]!!.any { it.optString("text") == "Written on the road" })
    }

    @Test fun hold_to_record_slide_up_to_lock_then_send_and_slide_left_cancels() {
        world(); val m = model(); show(m)
        MicTest.pretend = true
        openChat("dm_grace")
        // hold and slide up: locked, hands-free
        rule.onNodeWithTag("mic").performTouchInput { down(center); moveBy(Offset(0f, -80f)); moveBy(Offset(0f, -200f)); moveBy(Offset(0f, -200f)) }
        settle(1500); waitTag("rec_trash")
        assertTrue(wire.sent.any { it.contains("\"what\":\"recording\"") })
        rule.onNodeWithTag("send").performTouchInput { up() }
        settle(1000)
        shot("chat-06-recording-locked")
        // stop: the note waits to be heard, trimmed or sent (chatpa)
        tap("send"); waitTag("vp_play"); assertTrue(tagged("vp_delete") && tagged("vp_trim"))
        tap("send")
        until("voice sent") { fake.calls.any { it.startsWith("POST send") && it.contains("\"kind\":\"voice\"") } }
        val body = JSONObject(fake.calls.last { it.startsWith("POST send") }.substringAfter("send "))
        val md = body.getJSONArray("media").getJSONObject(0)
        assertEquals("voice", md.optString("type")); assertTrue(md.optDouble("dur") >= 1.0); assertEquals(40, md.getJSONArray("wave").length())
        assertTrue("uploaded first", fake.calls.any { it.startsWith("POST media") })
        // hold and slide left: cancelled, nothing sent
        val before = fake.calls.count { it.startsWith("POST send") }
        rule.onNodeWithTag("mic").performTouchInput { down(center); moveBy(Offset(-150f, 0f)); moveBy(Offset(-300f, 0f)); moveBy(Offset(-300f, 0f)); up() }
        settle(1000)
        assertFalse(tagged("rec_time")); assertEquals(before, fake.calls.count { it.startsWith("POST send") })
    }

    @Test fun voice_note_speed_and_waveform() {
        world(); val m = model(); show(m)
        openChat("g1")
        rule.onNodeWithTag("chat_msgs").performScrollToNode(hasTestTag("voice_speed")); settle()
        rule.onNodeWithTag("voice_speed", useUnmergedTree = true).assertTextEquals("1×")
        tap("voice_speed"); rule.onNodeWithTag("voice_speed", useUnmergedTree = true).assertTextEquals("1.5×")
        tap("voice_speed"); rule.onNodeWithTag("voice_speed", useUnmergedTree = true).assertTextEquals("2×")
        assertTrue(tagged("waveform"))
        tap("voice_speed"); rule.onNodeWithTag("voice_speed", useUnmergedTree = true).assertTextEquals("1×")
    }

    @Test fun long_press_react_star_edit_and_delete_for_everyone() {
        world(); val m = model(); show(m)
        openChat("g1")
        val mine = fake.msgs["g1"]!!.last { it.optString("from") == "Reid Anderson" }
        rule.onNodeWithTag("chat_msgs").performScrollToNode(hasTestTag("msg_${mine.optString("id")}"))
        rule.onAllNodes(hasTestTag("bubble_mine") and hasAnyAncestor(hasTestTag("msg_${mine.optString("id")}")), useUnmergedTree = true).onFirst().performSemanticsAction(SemanticsActions.OnLongClick); settle()
        waitTag("msg_sheet"); sheetShot("chat-07-actions")
        tap("react_❤️")
        until("react") { fake.calls.any { it.startsWith("POST react") && it.contains("❤️") } }
        waitTag("reacts_${mine.optString("id")}")
        // the reactions: who reacted
        tap("reacts_${mine.optString("id")}"); waitTag("reacts_sheet"); assertTrue(tagged("reacted_Reid Anderson")); back()
        // it's older than 15 minutes: no Edit
        rule.onAllNodes(hasTestTag("bubble_mine") and hasAnyAncestor(hasTestTag("msg_${mine.optString("id")}")), useUnmergedTree = true).onFirst().performSemanticsAction(SemanticsActions.OnLongClick); settle()
        assertFalse(tagged("act_edit"))
        tap("act_star"); until("star") { fake.calls.any { it.startsWith("POST star") } }
        rule.onAllNodes(hasTestTag("bubble_mine") and hasAnyAncestor(hasTestTag("msg_${mine.optString("id")}")), useUnmergedTree = true).onFirst().performSemanticsAction(SemanticsActions.OnLongClick); settle()
        tap("act_delete"); waitTag("del_all"); tap("del_all")
        until("deleted") { fake.calls.any { it.startsWith("POST delete") && it.contains("\"forAll\":true") } }
        waitFor("You deleted this message")
        // a fresh message can be edited (15 minutes)
        type("Meet at 9"); tap("send"); until("sent") { fake.msgs["g1"]!!.any { it.optString("text") == "Meet at 9" } }; settle(1000)
        val fresh = fake.msgs["g1"]!!.last()
        rule.onAllNodes(hasTestTag("bubble_mine") and hasAnyAncestor(hasTestTag("msg_" + fresh.optString("cid").ifBlank { fresh.optString("id") })), useUnmergedTree = true).onFirst().performSemanticsAction(SemanticsActions.OnLongClick); settle()
        tap("act_edit"); waitTag("editing_bar")
        rule.onNodeWithTag("composer_input").performTextClearance(); type("Meet at 10"); tap("send")
        until("edited") { fake.calls.any { it.startsWith("POST edit") && it.contains("Meet at 10") } }
        waitTag("edited")
    }

    @Test fun live_events_typing_presence_new_message_and_receipts() {
        world(); val m = model(); show(m)
        openChat("dm_grace")
        until("socket") { wire.l != null }
        assertTrue(wire.url.contains("/api/chat/ws?t=T123"))
        assertEquals(if (Lang.sw) "yupo mtandaoni" else "online", text("head_status"))
        rule.runOnUiThread { wire.push(JSONObject().put("t", "typing").put("c", "dm_grace").put("who", "Grace Wanjiru").put("what", "typing")) }; settle()
        assertEquals("typing…", text("head_status"))
        rule.runOnUiThread { wire.push(JSONObject().put("t", "typing").put("c", "dm_grace").put("who", "Grace Wanjiru").put("what", "recording")) }; settle()
        assertEquals("recording…", text("head_status"))
        shot("chat-08-typing")
        val o = fake.msg("dm_grace", "Grace Wanjiru", "I’m here now", 0)
        rule.runOnUiThread { wire.push(JSONObject().put("t", "msg").put("msg", o)) }; settle()
        waitFor("I’m here now")
        until("delivered and read") { fake.calls.any { it.startsWith("POST delivered") && it.contains(o.optString("id")) } && fake.calls.any { it.startsWith("POST read") && it.contains(o.optString("id")) } }
        rule.runOnUiThread { wire.push(JSONObject().put("t", "presence").put("who", "Grace Wanjiru").put("online", false).put("seen", Instant.now().toString())) }; settle()
        assertTrue(text("head_status").startsWith("last seen "))
    }

    private fun text(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString("") { it.text } ?: ""

    @Test fun photos_grid_viewer_and_attach_sheet() {
        world()
        // three photos sent together, already on the phone
        val files = (1..3).map { i -> File(app.cacheDir, "ph$i.jpg").apply { writeBytes(photoBytes()) } }
        fake.msg("dm_grace", "Reid Anderson", "The new tank", 2, kind = "media", status = "read") { put("media", JSONArray(files.mapIndexed { i, f -> JSONObject().put("id", "p$i").put("type", "photo").put("w", 1200).put("h", 900) })) }
        val m = model(); show(m)
        openChat("dm_grace")
        waitTag("media_2")
        // my three (the newest message) come after Grace's one photo in the viewer
        rule.onAllNodesWithTag("media_0", useUnmergedTree = true)[0].performClick(); settle(); waitTag("media_viewer")
        rule.onNodeWithTag("viewer_count", useUnmergedTree = true).assertTextEquals("2 / 4")
        rule.onNodeWithTag("media_viewer").performTouchInput { swipeLeft() }; settle()
        rule.onNodeWithTag("viewer_count", useUnmergedTree = true).assertTextEquals("3 / 4")
        back(); assertFalse("back closes the viewer only", tagged("media_viewer")); assertTrue(tagged("chat_room"))
        shot("chat-11-photos")
        tap("attach"); waitTag("attach_sheet"); sheetShot("chat-12-attach")
        assertTrue(tagged("att_gallery") && tagged("att_doc") && tagged("att_live") && tagged("att_contact"))
    }

    @Test fun location_live_location_and_contact_card() {
        world(); val m = model(); show(m)
        m.locator.pretend(org.rafikioutreach.pin.data.Fix(org.rafikioutreach.pin.data.LatLng(1.0152, 35.0062), 8, System.currentTimeMillis()))
        openChat("dm_grace")
        tap("attach"); waitTag("attach_sheet"); tap("att_live"); waitTag("live_sheet"); tap("live_60")
        until("live sent") { fake.calls.any { it.startsWith("POST send") && it.contains("\"kind\":\"live\"") && it.contains("\"until\"") } }
        waitTag("live_card"); waitTag("live_stop")
        tap("attach"); waitTag("attach_sheet"); tap("att_contact"); waitTag("mate_picker"); tap("mate_Meril Kiprop")
        until("contact sent") { fake.calls.any { it.startsWith("POST send") && it.contains("\"contact\":{\"name\":\"Meril Kiprop\"}") } }
        waitTag("contact_card")
        shot("chat-13-live-and-contact")
        // the live location moves with the phone (every 20 s or 25 m)
        val live = fake.msgs["dm_grace"]!!.first { it.optString("kind") == "live" }
        m.chat.now = { System.currentTimeMillis() + 60_000 }
        rule.runOnUiThread { m.chat.onFix(org.rafikioutreach.pin.data.Fix(org.rafikioutreach.pin.data.LatLng(1.02, 35.01), 8, System.currentTimeMillis())) }
        until("live moved") { fake.calls.any { it.startsWith("POST live ") && it.contains(live.optString("id")) } }
        tap("live_stop"); until("stopped") { fake.calls.any { it.startsWith("POST live-stop") } }
    }

    @Test fun group_info_admin_tools_and_only_admins() {
        world(); val m = model(); show(m)
        openChat("g1"); tap("chat_head"); waitTag("chat_info")
        assertTrue(tagged("admin_Reid Anderson") && tagged("info_add") && tagged("info_link"))
        shot("chat-14-group-info")
        rule.onNodeWithTag("info_only").performScrollTo(); tap("info_only")
        until("only admins") { fake.chats["g1"]!!.optBoolean("onlyAdmins") }
        rule.onNodeWithTag("member_Grace Wanjiru").performScrollTo(); tap("member_Grace Wanjiru"); waitTag("member_sheet"); tap("mem_admin")
        until("admin") { fake.chats["g1"]!!.getJSONArray("admins").toString().contains("Grace") }
        until("member sheet closed") { !tagged("member_sheet") }; settle()
        rule.onNodeWithTag("info_link").performScrollTo(); tap("info_link"); waitTag("link_url")
        assertTrue(text("link_url").contains("join=TOKEN12345"))
        tap("link_reset"); until("reset") { text("link_url").contains("NEWTOKEN99") }
    }

    @Test fun only_admins_hides_the_composer_for_others() {
        world(); fake.chats["g1"]!!.put("onlyAdmins", true).put("admins", JSONArray().put("Grace Wanjiru"))
        val m = model(admin = false); show(m)
        openChat("g1"); waitTag("only_admins"); assertFalse(tagged("composer"))
    }

    @Test fun chat_off_says_so_with_details_in_a_fold() {
        Lang.sw = true; world(); fake.off = true; val m = model(); show(m)
        tap("nav_chat"); waitTag("chat_off")
        assertTrue(has("Gumzo bado halijawashwa"))
        tap("chat_off_more"); waitTag("chat_off_tech"); assertTrue(has("CHAT_SECRET"))
        shot("chat-15-off-sw")
        back(); assertFalse("back folds it", tagged("chat_off_tech"))
    }

    @Test fun message_from_a_contact_sheet_and_chat_from_a_project() {
        world(); val m = model(); show(m)
        // a contact sheet: Message
        rule.runOnUiThread { Contact.show("Peter Otieno") }; settle(); waitTag("ct_message"); settle(2000)
        tap("ct_message")
        if (!runCatching { waitTag("chat_room") }.isSuccess) { shot("debug-ct-message"); throw AssertionError("no chat room; sheet=${tagged("ct_sheet")} req=${ChatNav.req} calls=${fake.calls.takeLast(6)}") }
        assertTrue(fake.calls.any { it.startsWith("POST open") && it.contains("\"with\":\"Peter Otieno\"") })
        assertEquals("Peter Otieno", text("head_title"))
        back(); assertFalse(tagged("chat_room"))
        // a project: Chat opens its thread (already on the phone: no call)
        tap("nav_list"); rule.onNodeWithTag("projects").performScrollToNode(hasText("Kapsaret Borehole")); rule.onNodeWithText("Kapsaret Borehole").performClick(); settle()
        rule.onNodeWithTag("prj_chat").performScrollTo().performClick(); settle()
        if (!runCatching { waitTag("chat_room") }.isSuccess) { shot("debug-prj-chat"); throw AssertionError("no chat room; req=${ChatNav.req} calls=${fake.calls.takeLast(6)}") }
        assertEquals("Kapsaret Borehole", text("head_title")); waitTag("sys_" + fake.msgs["p_105"]!!.first().optString("id"))
        // back: the project again, still there
        back(); waitTag("prj_chat")
    }

    /** The contact sheet at 360 dp for an admin: Message full width above five tiles; "WhatsApp" whole, on one line. */
    private fun contactSheet(sw: Boolean) {
        Lang.sw = sw; world(); val m = model(); show(m, dark = sw)
        rule.runOnUiThread { Contact.show("Grace Wanjiru") }; settle(1500); waitTag("ct_message"); waitTag("ct_wa"); settle(1500)
        listOf("ct_call", "ct_wa", "ct_sms", "ct_mail", "ct_share").forEach { tg ->
            val n = rule.onNodeWithTag(tg + "_label", useUnmergedTree = true).onChildren().onFirst().fetchSemanticsNode()
            val r = mutableListOf<TextLayoutResult>(); n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(r)
            assertFalse("$tg label cut", r.first().hasVisualOverflow)
            assertEquals("$tg on one line", 1, r.first().lineCount)
        }
        sheetShot("chat-16-contact-message-" + if (sw) "sw-dark" else "en-light")
    }
    @Test fun contact_sheet_message_and_whole_whatsapp_at_360() = contactSheet(false)
    @Test fun contact_sheet_message_and_whole_whatsapp_at_360_kiswahili() = contactSheet(true)

    @Test fun turn_a_message_into_a_repair_report() {
        world(); val m = model(); show(m)
        VisitDrafts.remove(app, "105")
        openChat("p_105")
        val msg = fake.msgs["p_105"]!!.last()
        rule.onAllNodes(hasTestTag("bubble_mine") and hasAnyAncestor(hasTestTag("msg_${msg.optString("id")}")), useUnmergedTree = true).onFirst().performSemanticsAction(SemanticsActions.OnLongClick); settle()
        tap("act_repair"); until("draft") { VisitDrafts.has(app, "105") }
        val d = VisitDrafts.load(app, "105")!!.json
        assertEquals("repair", d.optString("state")); assertTrue(d.optString("note").contains("Assigned to Meril for Thursday"))
        waitTag("visit_resume")
    }

    @Test fun new_group_and_new_dm() {
        world(); val m = model(); show(m)
        tap("nav_chat"); tap("chat_new"); waitTag("new_Meril Kiprop")
        tap("new_group"); tap("new_Meril Kiprop"); tap("new_Peter Otieno"); tap("group_next"); waitTag("group_name_sheet")
        rule.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("group_name_sheet")))[0].performTextInput("Eldoret drilling"); settle()
        tap("group_create")
        until("group") { fake.calls.any { it.startsWith("POST group ") && it.contains("Eldoret drilling") && it.contains("Meril Kiprop") } }
        waitTag("chat_room"); assertEquals("Eldoret drilling", text("head_title"))
    }

    // --- notifications ---------------------------------------------------------------------------------------------------
    @Test fun notifications_grouped_per_chat_with_reply_and_badge_count_mute_respected() = runBlocking {
        world(); val m = model()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val prefs = Prefs(app)
        // the first check only remembers where things are
        app.getSharedPreferences("chat", 0).edit().putLong("bg_since", System.currentTimeMillis() - 3_600_000).apply()
        fake.chats["t_ke"]!!.put("unread", 2) // muted: never notifies
        val shown = ChatWorker.check(app, prefs, m.api)
        assertTrue(shown.toString(), shown.contains("g1") && shown.contains("dm_grace") && !shown.contains("t_ke"))
        val nm = app.getSystemService(NotificationManager::class.java)
        val n = shadowOf(nm).getNotification(ChatNotify.id("g1"))
        assertNotNull(n)
        assertEquals(3, n.number)
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)!!
        assertEquals("Kitale repairs", style.conversationTitle); assertTrue(style.isGroupConversation)
        assertTrue(style.messages.any { it.text.toString().contains("Voice message") })
        val reply = n.actions.first { it.remoteInputs?.isNotEmpty() == true }
        assertEquals(ChatNotify.KEY_REPLY, reply.remoteInputs[0].resultKey)
        // replying from the notification: into the outbox, then sent
        val i = Intent(app, ChatReplyReceiver::class.java).setAction(ChatReplyReceiver.REPLY).putExtra("c", "g1").putExtra("title", "Kitale repairs").putExtra("group", true)
        android.app.RemoteInput.addResultsToIntent(arrayOf(android.app.RemoteInput.Builder(ChatNotify.KEY_REPLY).build()), i, android.os.Bundle().apply { putCharSequence(ChatNotify.KEY_REPLY, "On my way") })
        ChatReplyReceiver.testApi = m.api
        try { ChatReplyReceiver().onReceive(app, i) } finally { }
        val end = System.currentTimeMillis() + 8000
        while (fake.msgs["g1"]!!.none { it.optString("text") == "On my way" } && System.currentTimeMillis() < end) { shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(50) }
        assertTrue(fake.msgs["g1"]!!.any { it.optString("text") == "On my way" })
    }

    // --- the bottom bar decision: 6 tabs don't fit a 360 dp phone in Kiswahili; Chat takes Me's place -----------------
    private fun overflows(): List<String> = rule.onAllNodes(hasAnyAncestor(hasTestTag("bar")) and hasText("", substring = true), useUnmergedTree = true).fetchSemanticsNodes().mapNotNull { n ->
        val r = mutableListOf<TextLayoutResult>()
        n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(r)
        val t = n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString("") { it.text }
        // cut: the words need more width than the label was given
        if (r.firstOrNull()?.let { it.multiParagraph.intrinsics.maxIntrinsicWidth > n.size.width + 1f } == true) t else null
    }
    private fun bar(sw: Boolean, six: Boolean): List<String> {
        Lang.sw = sw; world(); val m = model()
        val items = listOf(Triple("map", t("Map", "Ramani"), "map"), Triple("list", t("Projects", "Miradi"), "list"), Triple("new", t("New site", "Eneo jipya"), "plus"), Triple("chat", t("Chat", "Gumzo"), "chat")) +
            (if (six) listOf(Triple("me", t("Me", "Mimi"), "person")) else emptyList()) + Triple("office", t("Office", "Ofisi"), "inbox")
        rule.setContent { RafikiTheme(dark = false) { Column(Modifier.width(360.dp).testTag("bar")) { BottomBar(m, "chat", 2, 4, items) { } } } }
        settle()
        shot("chat-tabbar-${if (six) 6 else 5}-${if (sw) "sw" else "en"}")
        return overflows()
    }
    @Test fun tab_bar_six_tabs_break_in_kiswahili_at_360() { val o = bar(true, true); assertTrue("6 tabs at 360 dp: $o", o.isNotEmpty()) }
    @Test fun tab_bar_five_tabs_fit_at_360_kiswahili() { assertEquals(emptyList<String>(), bar(true, false)) }
    @Test fun tab_bar_five_tabs_fit_at_360_english() { assertEquals(emptyList<String>(), bar(false, false)) }

    // --- the data, without screens ------------------------------------------------------------------------------------
    @Test fun previews_and_parsing() {
        Lang.sw = false
        assertEquals("🎤 Voice message 0:12", chatPreview("voice", "", 12.0))
        assertEquals("📷 Photo", chatPreview("media", "", 0.0))
        assertEquals("📷 3 photos: Tank", chatPreview("media", "Tank", 0.0, count = 3))
        Lang.sw = true
        assertEquals("🎤 Ujumbe wa sauti 1:05", chatPreview("voice", "", 65.0))
        Lang.sw = false
        val r = ChatRow.from(JSONObject().put("id", "x").put("kind", "dm").put("title", "G").put("members", JSONArray().put("A").put("G")).put("muted", "always"))
        assertTrue(r.muted()); assertEquals("G", r.other("A"))
        assertEquals(ChatRow.ALWAYS, ChatRow.mutedFrom("always")); assertEquals(0L, ChatRow.mutedFrom(0))
        val m = Msg.from(JSONObject().put("id", "1").put("from", "A").put("at", "2026-10-26T10:00:00Z").put("kind", "voice").put("media", JSONArray().put(JSONObject().put("id", "v").put("type", "voice").put("dur", 3).put("wave", "1,50,100,200"))))
        assertEquals(listOf(1, 50, 100, 100), m.media[0].wave)
        assertEquals(mapOf("👍" to listOf("B", "A")), ChatHub.reactWith(mapOf("❤️" to listOf("A"), "👍" to listOf("B")), "A", "👍"))
        assertEquals("TOKEN12345", org.rafikioutreach.pin.ui.joinToken("https://pin.rafikioutreach.org/pin#join=TOKEN12345"))
    }
}

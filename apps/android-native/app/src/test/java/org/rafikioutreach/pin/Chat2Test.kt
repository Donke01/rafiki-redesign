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
import org.rafikioutreach.pin.ui.ChatChip
import org.rafikioutreach.pin.ui.ChatField
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
 * Chat 2 on Android (web c66c421 / feat-cwr, design/chat-2026-10): the chat list and one chat, against [FakeChat].
 * Screenshots "c2-…".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class Chat2Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var fake: FakeChat
    private lateinit var wire: FakeChat.Wire

    @After fun reset() { ChatChip.now = "all"; ChatField.map = null; ChatField.at = 0L; ChatReplyReceiver.testApi = null; Lang.sw = false; Look.mode = ""; Motion.on = true; MicTest.pretend = false; Whoosh.enabled = true; ChatNav.req = null; ChatNav.focus = null; Contact.who = null; Onb.enabled = true }

    private fun proj(id: String, t: String) = JSONObject().put("id", id).put("title", t).put("country", "Kenya").put("ck", "ke").put("area", "Kitale")
        .put("type", "Borehole well").put("status", "Completed").put("pump", "manual").put("gps", JSONArray().put(1.01).put(35.0)).apply { if (id == "105") put("photo", "ph105") }
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
        fake.msg("p_105", "Meril Kiprop", "Water flowing again 🙌 The committee chair is here.", 20, kind = "media") { put("media", JSONArray().put(JSONObject().put("id", "ph9").put("type", "photo").put("w", 1200).put("h", 800))) }
        fake.msg("p_105", "Meril Kiprop", "Mwenyekiti anasema watoto sasa wanakunywa maji safi kila siku.", 19)
        fake.chat("t:news", "team", "Announcements", listOf("Reid Anderson", "Grace Wanjiru", "Meril Kiprop", "Peter Otieno"), listOf("Reid Anderson"))
        fake.chats["t:news"]!!.put("announce", true)
        fake.msg("t:news", "Reid Anderson", "Kitale trip moved to 4 Nov", 60 * 24 * 3)
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
            if (!url.contains("/api/chat/")) other += "$method ${url.substringAfter("/api/")} ${body?.let { b -> String(b).take(300) } ?: ""}"
            fake.handle(method, url, body) ?: when {
                url.contains("/api/story") && method == "GET" -> 200 to JSONObject().put("ok", true).put("story", JSONObject().put("status", "collecting").put("consent", "not-yet").put("answers", JSONObject()).put("quote", "").put("quoteBy", "").put("credit", "").put("text", "")
                    .put("photos", JSONObject().put("before", JSONArray()).put("progress", JSONArray()).put("after", JSONArray())).put("missing", JSONArray()).put("canApprove", false).put("prompts", JSONObject())).toString()
                url.contains("/api/translate") -> 200 to JSONObject().put("ok", true).put("text", "The chairman says the children now drink clean water every day.").put("to", "en").toString()
                url.contains("activity=1") -> 200 to "{\"ok\":true,\"activity\":[]}"
                url.contains("view=teammap") -> 200 to JSONObject().put("ok", true).put("people", JSONArray().put(JSONObject().put("name", "Bertin Mwangi").put("lat", 1.0102).put("lng", 35.0003).put("acc", 8).put("at", fake.ago(5)))).put("visits", JSONArray()).toString()
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

    private var w = 360; private var h = 800
    private val other = java.util.concurrent.CopyOnWriteArrayList<String>()
    private fun show(m: AppModel, dark: Boolean = false) {
        rule.setContent { RafikiTheme(dark = dark) { Box(Modifier.size(w.dp, h.dp)) { App(m) } } }
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
    private fun tap(t: String) { runCatching { rule.onNodeWithTag(t).performScrollTo() }; rule.onNodeWithTag(t, useUnmergedTree = true).performClick(); settle() }
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

    private fun noLayoutProblems(root: String, label: String) = assertEquals("$label: $root", emptyList<String>(), LayoutCheck.problems(rule, root, w * rule.density.density))
    private fun order(tags: List<String>) = rule.onAllNodes(SemanticsMatcher("rows") { n -> n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag) in tags }, useUnmergedTree = true)
        .fetchSemanticsNodes().sortedBy { it.positionInRoot.y }.map { it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag] }

    // --- A1–A8: the list -----------------------------------------------------------------------------------------------
    @Test fun list_header_chips_field_rows() {
        world(); val m = model(); show(m)
        tap("nav_chat"); waitTag("chatrow_g1"); until("field") { tagged("chat_field_Bertin Mwangi") }
        // A1: the RAFIKI bar steps aside on a phone; Me photo, Chats, search, ⋯ (with the bell and refresh)
        assertFalse(tagged("bell")); assertFalse(tagged("hav")); rule.onNodeWithTag("chat_title", useUnmergedTree = true).assertTextEquals("Chats")
        tap("chat_menu"); listOf("menu_settings", "menu_starred", "menu_archived", "menu_newgroup", "menu_read", "menu_bell", "menu_refresh").forEach { assertTrue(it, tagged(it)) }
        shot("c2-list-menu"); back(); assertFalse(tagged("menu_settings"))
        // A7: Announcements first (megaphone, pin), then pinned, then newest; Archived as the last row
        assertEquals(listOf("chatrow_t:news", "chatrow_t_all", "chatrow_g1", "chatrow_dm_grace"), order(listOf("chatrow_t:news", "chatrow_t_all", "chatrow_g1", "chatrow_dm_grace")))
        assertTrue(tagged("av_news") && tagged("row_pinned"))
        rule.onNodeWithTag("chat_list").performScrollToNode(hasTestTag("chat_archived_row"))
        assertEquals("archived last", "chat_archived_row", order(listOf("chatrow_t_ke", "chatrow_p_105", "chat_archived_row", "chatrow_g1")).last())
        rule.onNodeWithTag("chat_list").performScrollToIndex(0); settle()
        // A6: the project's photo with a blue drop; A5: @ pill, unread count, ticks on my last message
        assertTrue(tagged("pdrop")); assertTrue(tagged("row_mention") && tagged("row_unread")); assertTrue(tagged("row_ticks"))
        // A2: chips; Unread shows its count; a chip is kept and is not a Back step
        rule.onNodeWithTag("chat_chip_unread_n", useUnmergedTree = true).assertTextEquals(" 2")
        tap("chat_chip_project"); assertTrue(tagged("chatrow_p_105")); assertFalse(tagged("chatrow_g1")); rule.onNodeWithTag("chat_chip_project").assertIsSelected()
        tap("chat_chip_dm"); assertTrue(tagged("chatrow_dm_grace")); assertFalse(tagged("chatrow_p_105"))
        tap("chat_chip_group"); assertFalse(tagged("chatrow_dm_grace"))
        tap("nav_map"); tap("nav_chat"); waitTag("chat_chips"); rule.onNodeWithTag("chat_chip_group").assertIsSelected()
        tap("chat_chip_all")
        // A3: In the field now: admins see who shares at a site; online; seen lately
        rule.onNodeWithTag("chat_field_Bertin Mwangi").assertContentDescriptionContains("At Kapsaret Borehole", substring = true)
        rule.onNodeWithTag("chat_field_Grace Wanjiru").assertContentDescriptionContains("Online", substring = true)
        rule.onNodeWithTag("chat_field_Meril Kiprop").assertContentDescriptionContains("min ago", substring = true)
        assertTrue(tagged("chat_field_pin"))
        assertEquals(listOf("chat_field_Bertin Mwangi", "chat_field_Grace Wanjiru", "chat_field_Meril Kiprop"), rule.onAllNodes(SemanticsMatcher("field") { n -> n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("chat_field_") == true && n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag) != "chat_field_pin" })
            .fetchSemanticsNodes().sortedBy { it.positionInRoot.x }.map { it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag] })
        // A4: the one-line alert prompt
        assertTrue(tagged("chat_alerts")); tap("chat_alerts_x"); assertFalse(tagged("chat_alerts"))
        shot("c2-list-360-en-light")
        // search in place; Back closes it
        tap("chat_search_btn"); assertTrue(tagged("chat_search")); assertFalse(tagged("chat_chips")); back(); assertTrue(tagged("chat_chips")); assertFalse(tagged("chat_search"))
        // tapping someone in the field row opens a DM
        tap("chat_field_Grace Wanjiru"); waitTag("chat_room"); back(); waitTag("chat_list")
        // ⋯ › Mark all as read; A8: the green new-chat button
        tap("chat_menu"); tap("menu_read"); settle(); assertFalse(tagged("row_unread")); assertTrue(fake.calls.any { it.startsWith("POST read") })
        tap("chat_new"); waitTag("new_Meril Kiprop"); back()
        // the Me photo opens Me; Back comes to Map (the tab's own rule)
        tap("nav_chat"); waitTag("chat_me"); tap("chat_me"); waitTag("me_hero")
    }

    @Test fun field_users_never_see_positions() {
        world(); val m = model(admin = false); show(m)
        tap("nav_chat"); waitTag("chatrow_g1"); until("field") { tagged("chat_field_Grace Wanjiru") }
        assertFalse("no team map for field users", tagged("chat_field_Bertin Mwangi")); assertFalse(tagged("chat_field_pin"))
        assertFalse(fake.calls.any { it.contains("teammap") })
    }

    private fun listLooks(width: Int, height: Int, sw: Boolean, mode: String) {
        Lang.sw = sw; w = width; h = height; Look.mode = if (mode == "sun") "sun" else ""
        world(); val m = model(); show(m, dark = mode == "dark")
        tap("nav_chat"); waitTag("chatrow_g1"); until("field") { tagged("chat_field_Bertin Mwangi") }; settle(1500)
        val tag = "$width-${if (sw) "sw" else "en"}-$mode"
        listOf("chat_head", "chat_chips", "chat_field", "chat_alerts").forEach { noLayoutProblems(it, tag) }
        // the names and times in the rows: whole (a long title ends with … only when it can't fit)
        if (sw) rule.onNodeWithTag("chat_title", useUnmergedTree = true).assertTextEquals("Gumzo")
        shot("c2-list-$tag")
        rule.onNodeWithTag("chat_list").performScrollToNode(hasTestTag("chat_archived_row")); shot("c2-list-end-$tag")
    }
    @Config(qualifiers = "w320dp-h640dp-xhdpi") @Test fun list_320_en_light() = listLooks(320, 640, false, "light")
    @Config(qualifiers = "w320dp-h640dp-xhdpi") @Test fun list_320_sw_dark() = listLooks(320, 640, true, "dark")
    @Config(qualifiers = "w320dp-h640dp-xhdpi") @Test fun list_320_sw_sun() = listLooks(320, 640, true, "sun")
    @Config(qualifiers = "w360dp-h800dp-xxhdpi") @Test fun list_360_sw_light() = listLooks(360, 800, true, "light")
    @Config(qualifiers = "w360dp-h800dp-xxhdpi") @Test fun list_360_en_dark() = listLooks(360, 800, false, "dark")
    @Config(qualifiers = "w411dp-h891dp-xxhdpi") @Test fun list_411_en_sun() = listLooks(411, 891, false, "sun")
    @Config(qualifiers = "w411dp-h891dp-xxhdpi") @Test fun list_411_sw_light() = listLooks(411, 891, true, "light")

    // --- B1–B17: one chat ----------------------------------------------------------------------------------------------
    private fun room(id: String) { tap("nav_chat"); waitTag("chat_list"); until("rows") { tagged("chatrow_g1") || tagged("chatrow_t:news") }; rule.onNodeWithTag("chat_list").performScrollToNode(hasTestTag("chatrow_$id")); tap("chatrow_$id"); waitTag("chat_room"); settle(1500) }
    /** Hold a message by its words (the newest of them, on screen). */
    private fun bubble(text: String) = rule.onAllNodes((hasTestTag("bubble_mine") or hasTestTag("bubble_theirs")) and hasAnyDescendant(hasText(text, substring = true)), useUnmergedTree = true).onFirst()
    private fun hold(text: String) { runCatching { rule.onNodeWithTag("chat_msgs").performScrollToNode(hasText(text, substring = true)) }; settle(); bubble(text).performSemanticsAction(SemanticsActions.OnLongClick); settle() }
    private fun tapMsg(text: String) { bubble(text).performSemanticsAction(SemanticsActions.OnClick); settle() }
    private fun longPress(tag: String, i: Int = 0) { rule.onAllNodesWithTag(tag, useUnmergedTree = true)[i].performTouchInput { longClick() }; settle() }
    private fun bubbleWith(text: String) = rule.onAllNodes(hasTestTag("msg_text") and hasText(text, substring = true), useUnmergedTree = true).onFirst()

    @Test fun room_header_strip_find_and_bubbles() {
        world(); val m = model(); show(m)
        room("p_105")
        // B1: its own header: ←, the project's photo, name and members, search, ⋯ (no RAFIKI bar, no call)
        assertFalse(tagged("bell")); assertTrue(tagged("room_back") && tagged("room_search") && tagged("room_menu"))
        rule.onNodeWithTag("head_title", useUnmergedTree = true).assertTextEquals("Kapsaret Borehole")
        // B2: the project strip
        rule.onAllNodes(hasAnyAncestor(hasTestTag("proj_strip_status")) and hasText("Needs repair"), useUnmergedTree = true).onFirst().assertExists()
        assertTrue(text("proj_strip_line").contains("Kitale, Kenya")); assertTrue(tagged("proj_strip_open"))
        // B3: the warm wall, day chips; a long bubble stays inside 80%
        assertTrue(tagged("wall_warm") && tagged("day_chip"))
        shot("c2-room-360-en-light")
        tap("proj_strip_open"); waitTag("tab_pin"); back(); waitTag("chat_room")
        // B13: search in this chat; Back closes it
        tap("room_search"); waitTag("find_bar"); rule.onNodeWithTag("find_q").performTextInput("assigned"); settle(800)
        assertEquals("1/1", text("find_n")); shot("c2-room-find")
        back(); assertFalse(tagged("find_bar")); assertTrue(tagged("room_search"))
        // ⋯ and Back
        tap("room_menu"); listOf("room_m_search", "room_m_date", "room_m_select", "room_m_media", "room_m_info", "room_m_clear").forEach { assertTrue(it, tagged(it)) }
        back(); assertFalse(tagged("room_m_clear")); assertTrue(tagged("chat_room"))
        // B12: media, files and links
        tap("room_menu"); tap("room_m_media"); waitTag("media_sheet"); assertTrue(tagged("mf_media_0")); tap("mf_tab_links"); assertTrue(tagged("mf_none")); sheetShot("c2-room-media"); back(); settle()
        // ← goes back to the list
        tap("room_back"); waitTag("chat_list")
    }

    @Test fun select_and_delete_for_everyone_or_me() {
        world(); val m = model(); show(m)
        room("g1")
        // B10: hold → Select; tap more bubbles; the dark bar with the count
        hold("see the photo of the pump"); waitTag("msg_sheet"); tap("act_select"); waitTag("sel_bar")
        assertEquals("1 selected", text("sel_n"))
        tapMsg("Perfect, please do")
        assertEquals("2 selected", text("sel_n")); shot("c2-room-select")
        // Back leaves selecting
        back(); assertFalse(tagged("sel_bar"))
        hold("see the photo of the pump"); tap("act_select"); tapMsg("Perfect, please do")
        tap("sel_delete"); waitTag("delete_sheet")
        // one of them is someone else's: everyone is offered because I'm this group's admin, and it says so
        assertTrue(tagged("del_all") && tagged("del_me") && tagged("del_note"))
        assertTrue(text("del_note").contains("because you’re an admin of this chat"))
        sheetShot("c2-room-delete")
        tap("del_all"); settle(1200)
        assertTrue(fake.calls.any { it.startsWith("POST delete-many") && it.contains("\"forAll\":true") })
        assertFalse(tagged("sel_bar"))
        // a single message of mine: the same sheet, from the long press
        hold("Thanks Meril"); tap("act_delete"); waitTag("delete_sheet"); assertFalse(tagged("del_note")); tap("del_me"); settle(1200)
        assertTrue(fake.calls.any { it.startsWith("POST delete ") && it.contains("\"forAll\":false") })
    }

    @Test fun field_user_cannot_delete_others_for_everyone() {
        world(); val m = model(admin = false); show(m)
        fake.chats["g1"]!!.put("admins", JSONArray().put("Grace Wanjiru"))
        room("g1")
        hold("see the photo of the pump"); tap("act_delete"); waitTag("delete_sheet")
        assertFalse(tagged("del_all")); assertTrue(tagged("del_me"))
    }

    @Test fun clear_and_delete_chat() {
        world(); val m = model(); show(m)
        room("dm_grace")
        tap("room_menu"); tap("room_m_clear"); waitTag("clear_sheet"); sheetShot("c2-room-clear"); tap("clear_go"); settle(1500)
        assertTrue(fake.calls.any { it.startsWith("POST clear") }); waitTag("chat_empty")
        tap("room_menu"); tap("room_m_delete"); waitTag("delchat_sheet"); tap("clear_go"); settle(1500)
        assertTrue(fake.calls.any { it.startsWith("POST clear") && it.contains("archive") })
        waitTag("chat_list"); assertFalse(tagged("chatrow_dm_grace"))
    }

    @Test fun add_people_by_country() {
        world(); val m = model(); show(m)
        room("g1")
        tap("room_menu"); tap("room_m_add"); waitTag("add_people"); until("team") { tagged("ap_p_Peter Otieno") }
        rule.onAllNodes(hasAnyAncestor(hasTestTag("ap_p_Meril Kiprop")) and hasText("Already in the group"), useUnmergedTree = true).onFirst().assertExists()
        tap("ap_p_Peter Otieno"); assertTrue(tagged("ap_chip_Peter Otieno"))
        rule.onNodeWithTag("ap_go").assertTextContains("Add 1 person", substring = true)
        tap("ap_all_ke"); rule.onNodeWithTag("ap_go").assertTextContains("Add 2 people", substring = true)
        sheetShot("c2-room-add-people")
        tap("ap_go"); settle(1200)
        until("members") { fake.calls.any { it.startsWith("POST members") && it.contains("Peter Otieno") && it.contains("Jessica Anderson") } }
    }

    @Test fun field_user_asks_an_admin_to_add() {
        world(); val m = model(admin = false); show(m)
        fake.chats["g1"]!!.put("admins", JSONArray().put("Grace Wanjiru"))
        room("g1")
        tap("room_menu"); tap("room_m_add"); waitTag("ap_note"); until("team") { tagged("ap_p_Peter Otieno") }
        tap("ap_p_Peter Otieno"); tap("ap_go"); settle(1500)
        until("asked") { fake.calls.any { it.startsWith("POST send") && it.contains("@Grace Wanjiru") && it.contains("Peter Otieno") } }
    }

    @Test fun photos_story_translate_voice_text_and_info() {
        world(); fake.tools.put("translate", true).put("transcribe", true); val m = model(); show(m)
        room("p_105")
        // B7: the Kiswahili message offers Translate · Tafsiri; the answer in italics with Show original
        waitTag("translate"); tap("translate"); waitTag("translation"); assertTrue(other.any { it.startsWith("POST translate") })
        tap("translate_orig"); assertFalse(tagged("translation"))
        // B5: Add to project photos → a note on the project, the photo on it → also the story?
        waitTag("add_photos"); tap("add_photos"); waitTag("story_ask")
        assertTrue(other.any { it.contains("pin ") && it.contains("chat-photo") }); assertTrue(other.any { it.startsWith("POST pin-photo") })
        sheetShot("c2-room-story-ask"); tap("story_row_after"); settle(1500)
        until("story") { other.any { it.startsWith("POST story") } }; waitTag("photos_added")
        shot("c2-room-added")
        // B9: the ticks on my message open Message info
        tap("ticks_btn"); waitTag("info_sheet"); back(); settle()
        // B8: a voice note's Show text
        tap("room_back"); waitTag("chat_list"); room("g1")
        rule.onNodeWithTag("chat_msgs").performScrollToNode(hasTestTag("voice_text")); tap("voice_text"); waitTag("voice_words")
        assertTrue(fake.calls.any { it.startsWith("POST transcribe") })
    }

    @Test fun use_a_message_in_the_story() {
        world(); val m = model(); show(m)
        room("p_105")
        hold("Mwenyekiti")
        tap("act_story"); waitTag("story_quote_from_chat"); settle(1500)
        // filled in, not saved: the words and who said them are in the fields, and nothing was sent
        assertTrue(has("Mwenyekiti anasema")); assertFalse(other.any { it.startsWith("POST story") && it.contains("answers") })
        rule.onNodeWithTag("nav_chat").assertIsSelected()
        shot("c2-room-story-quote")
    }

    @Test fun waiting_for_signal_then_not_sent() {
        world(); val m = model(); show(m)
        room("dm_grace")
        fake.online = false
        rule.onNodeWithTag("composer_input").performTextInput("On my way"); settle(); tap("send"); settle(9500)
        waitTag("waiting_signal"); assertTrue(tagged("retry")); shot("c2-room-waiting")
    }

    private fun roomLooks(width: Int, height: Int, sw: Boolean, mode: String) {
        Lang.sw = sw; w = width; h = height; Look.mode = if (mode == "sun") "sun" else ""
        world(); fake.tools.put("translate", true); val m = model(); show(m, dark = mode == "dark")
        room("p_105")
        val tag = "$width-${if (sw) "sw" else "en"}-$mode"
        // the strip's place may end with … (as on the web); its status and Open project never do
        listOf("chat_head", "proj_strip_status", "proj_strip_open").forEach { noLayoutProblems(it, tag) }
        rule.onAllNodesWithTag("msg_text", useUnmergedTree = true).fetchSemanticsNodes().forEach { n -> assertEquals(tag, null, LayoutCheck.problem(n, w * rule.density.density)) }
        shot("c2-room-$tag")
        tap("room_menu"); shot("c2-room-menu-$tag"); back()
    }
    @Config(qualifiers = "w320dp-h640dp-xhdpi") @Test fun room_320_en_light() = roomLooks(320, 640, false, "light")
    @Config(qualifiers = "w320dp-h640dp-xhdpi") @Test fun room_320_sw_dark() = roomLooks(320, 640, true, "dark")
    @Config(qualifiers = "w320dp-h640dp-xhdpi") @Test fun room_320_sw_sun() = roomLooks(320, 640, true, "sun")
    @Config(qualifiers = "w360dp-h800dp-xxhdpi") @Test fun room_360_sw_light() = roomLooks(360, 800, true, "light")
    @Config(qualifiers = "w411dp-h891dp-xxhdpi") @Test fun room_411_en_dark() = roomLooks(411, 891, false, "dark")
    @Config(qualifiers = "w411dp-h891dp-xxhdpi") @Test fun room_411_en_sun() = roomLooks(411, 891, false, "sun")
    private fun text(t: String) = LayoutCheck.text(rule.onNodeWithTag(t, useUnmergedTree = true).fetchSemanticsNode())
}

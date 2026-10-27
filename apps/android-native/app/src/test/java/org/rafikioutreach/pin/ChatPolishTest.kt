package org.rafikioutreach.pin

import android.app.Application
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.rafikioutreach.pin.data.ChatNotify
import org.rafikioutreach.pin.data.ChatPush
import org.rafikioutreach.pin.data.ChatRow
import org.rafikioutreach.pin.data.ChatSettings
import org.rafikioutreach.pin.data.Chime
import org.rafikioutreach.pin.data.Dnd
import org.rafikioutreach.pin.data.Msg
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.data.TripCard
import org.rafikioutreach.pin.data.TripShare
import org.rafikioutreach.pin.data.emojiOnly
import org.rafikioutreach.pin.ui.ChatNav
import org.rafikioutreach.pin.ui.ChatPaste
import org.rafikioutreach.pin.ui.ChatShare
import org.rafikioutreach.pin.ui.Contact
import org.rafikioutreach.pin.ui.Look
import org.rafikioutreach.pin.ui.MicTest
import org.rafikioutreach.pin.ui.Motion
import org.rafikioutreach.pin.ui.Onb
import org.rafikioutreach.pin.ui.RafikiTheme
import org.rafikioutreach.pin.ui.TripNav
import org.rafikioutreach.pin.ui.Whoosh
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.LocalTime

/**
 * The chat polish round on Android (chatpa, S/chatpolish-brief.md and S/chatpolish-contract.md), against the fake chat
 * server. Screenshots "chatp-…" at 360 dp, English and Kiswahili, light and dark.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class ChatPolishTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var fake: FakeChat
    private lateinit var wire: FakeChat.Wire

    @After fun reset() { Lang.sw = false; Look.mode = ""; Motion.on = true; MicTest.pretend = false; Whoosh.enabled = true; Chime.enabled = true; ChatNav.req = null; ChatNav.focus = null; Contact.who = null; Onb.enabled = true
        ChatShare.trip = null; TripNav.open = null; ChatPaste.paste = null; app.getSharedPreferences("chat_settings", 0).edit().clear().commit() }

    private fun proj(id: String, t: String) = JSONObject().put("id", id).put("title", t).put("country", "Kenya").put("ck", "ke").put("area", "Kitale")
        .put("type", "Borehole well").put("status", "Completed").put("pump", "manual").put("gps", JSONArray().put(1.01).put(35.0))
    private fun snapshot(admin: Boolean = true) = JSONObject().put("ok", true).put("who", "Reid Anderson").put("admin", admin).put("reviewOpen", 0)
        .put("projects", JSONArray().put(proj("105", "Kapsaret Borehole")).put(proj("101", "Chepkongi Dispensary")))
        .put("repairs", JSONObject().put("105", JSONObject().put("state", "repair").put("by", "Meril Kiprop").put("at", fake.ago(300)).put("urgency", "month").put("t", "Kapsaret Borehole")))
        .put("visits", JSONObject()).put("jobs", JSONArray()).put("assigned", JSONArray()).put("confirmed", JSONObject())
    private fun team() = JSONArray().apply { listOf("Grace Wanjiru", "Meril Kiprop", "Peter Otieno", "Reid Anderson", "Jessica Anderson").forEach { put(JSONObject().put("name", it).put("home", "ke").put("lastSeen", fake.ago(30)).put("src", "app")) } }

    private val trip = JSONObject().put("id", "it_7").put("title", "Kitale pump round").put("from", java.time.LocalDate.now().toString()).put("to", java.time.LocalDate.now().plusDays(2).toString())
        .put("status", "active").put("next", JSONObject().put("name", "Kapsaret Borehole").put("date", java.time.LocalDate.now().toString()).put("at", "14:00")).put("done", 3).put("total", 8).put("access", true)

    /** Announcements, the Kenya team (a flag), a group with pins, "Seen by", a trip and big emoji, a DM and a new empty group. */
    private fun world() {
        fake = FakeChat()
        fake.trips["it_7"] = trip
        fake.chat("t:news", "team", "Announcements", listOf("Reid Anderson", "Grace Wanjiru", "Meril Kiprop", "Peter Otieno"), listOf("Reid Anderson"), onlyAdmins = true)
        fake.chats["t:news"]!!.put("announce", true)
        fake.chat("g1", "group", "Kitale repairs", listOf("Reid Anderson", "Grace Wanjiru", "Meril Kiprop", "Peter Otieno", "Jessica Anderson", "Morgan Mamati", "Paul Kiptoo", "Ann Chebet"), listOf("Reid Anderson"), unread = 2, mention = true)
        fake.chat("t:ke", "team", "Kenya team", listOf("Reid Anderson", "Meril Kiprop"))
        fake.chats["t:ke"]!!.put("country", "ke")
        fake.chat("dm_grace", "dm", "Grace Wanjiru", listOf("Reid Anderson", "Grace Wanjiru"))
        fake.chat("g_new", "group", "Eldoret drilling", listOf("Reid Anderson", "Peter Otieno"), listOf("Reid Anderson"))
        fake.msg("g1", "Meril Kiprop", "Handle on the Kapsaret pump is loose again", 90)
        fake.msg("g1", "Meril Kiprop", "I’ll take a look today", 89)
        fake.msg("g1", "Reid Anderson", "Thanks Meril 🙏", 80, status = "read")
        fake.msg("g1", "Meril Kiprop", "", 40) { put("kind", "sys").put("sys", JSONObject().put("type", "fixed").put("project", "105").put("by", "Meril Kiprop")) }
        fake.msg("g1", "Reid Anderson", "", 30, kind = "trip", status = "read") { put("trip", trip) }
        fake.msg("g1", "Grace Wanjiru", "🎉👏", 20)
        val pinned = fake.msg("g1", "Grace Wanjiru", "Spare handles are in the Kitale store, shelf 3", 12)
        fake.msg("g1", "Grace Wanjiru", "@Reid Anderson can you bring the 13 mm spanner?", 5) { put("mentions", JSONArray().put("Reid Anderson")) }
        fake.msg("g1", "Reid Anderson", "On my way", 2, status = "read")
        fake.chats["g1"]!!.put("pins", JSONArray().put(JSONObject().put("id", pinned.optString("id")).put("from", "Grace Wanjiru").put("kind", "text").put("text", pinned.optString("text")).put("by", "Reid Anderson")))
            .put("seen", JSONArray(listOf("Grace Wanjiru", "Meril Kiprop", "Peter Otieno", "Jessica Anderson", "Morgan Mamati", "Paul Kiptoo", "Ann Chebet")))
            .put("bubble", "green").put("wallpaper", "savanna")
        fake.msg("t:ke", "Peter Otieno", "Fuel prices went up in Eldoret", 300)
        fake.msg("dm_grace", "Grace Wanjiru", "See you at the site", 600)
        fake.presence.put("Grace Wanjiru", JSONObject().put("online", true).put("seen", fake.ago(0)))
    }

    private fun model(admin: Boolean = true): AppModel {
        Onb.enabled = false; Motion.on = false; Whoosh.enabled = false; Chime.enabled = false
        Prefs(app).apply { pin = "9999"; name = "Reid Anderson"; saveSnapshot(snapshot(admin)); filter = "all"; onb = true; lang = if (Lang.sw) "sw" else "en"; mePane = "me"; phoneAlerts = false }
        File(app.filesDir, "chat").deleteRecursively()
        listOf("chat", "chat_drafts", "chat_notes", "chat_settings").forEach { app.getSharedPreferences(it, 0).edit().clear().commit() }
        val m = AppModel(app)
        m.api.transport = { method, url, _, body ->
            fake.handle(method, url, body) ?: when {
                url.contains("/api/itin/it_7") -> 200 to JSONObject(trip.toString()).put("ok", true).put("stops", JSONArray()).toString()
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
        m.api.fetch = { _, _ -> 200 to photoBytes() }
        wire = fake.Wire(); m.chat.wire = wire
        return m
    }
    private fun photoBytes(): ByteArray { val b = Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888); Canvas(b).drawColor(0xFF6FA8DC.toInt()); return java.io.ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray() }

    private fun show(m: AppModel, dark: Boolean = false, fontScale: Float = 1f) {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) { RafikiTheme(dark = dark) { Box(Modifier.size(360.dp, 800.dp)) { App(m) } } }
        }
        waitTag("nav_chat"); settle(1500)
    }
    private fun settle(ms: Long = 800) { rule.mainClock.advanceTimeBy(ms); rule.waitForIdle() }
    private fun until(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 12_000
        while (!cond()) { assertTrue("timed out: $what", System.currentTimeMillis() < end); rule.mainClock.advanceTimeBy(100); rule.waitForIdle(); Thread.sleep(20) }
    }
    private fun tagged(t: String) = rule.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitTag(t: String) = until("tag $t") { tagged(t) }
    private fun has(t: String) = rule.onAllNodesWithText(t, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(t: String) = until("text $t") { has(t) }
    private fun tap(t: String) { rule.onNodeWithTag(t, useUnmergedTree = true).performClick(); settle() }
    private fun text(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: ""
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
    private fun <T> onMain(block: suspend () -> T): T {
        var r: Result<T>? = null
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch { r = runCatching { block() } }
        until("main work") { r != null }
        return r!!.getOrThrow()
    }
    private fun scrollTo(tag: String) { rule.onNodeWithTag("chat_msgs").performScrollToNode(hasTestTag(tag)); settle() }

    // --- the look: a group with its pins, bubbles with tails in my colour, big emoji, a trip, "Seen by", savanna ----------
    private fun roomLooks(sw: Boolean, dark: Boolean) {
        Lang.sw = sw; world(); val m = model(); show(m, dark)
        openChat("g1")
        waitTag("pin_bar"); assertTrue(text("pin_text").contains("shelf 3"))
        waitTag("wallpaper_savanna")
        // "Seen by": 5 pictures and "+2" under the newest message
        waitTag("seen_by"); assertEquals("+2", text("seen_more"))
        // the mention is mine and unseen: the "@" button shows until it's on screen
        assertTrue(text("head_status").isNotBlank())
        scrollTo("emoji_big"); assertTrue(tagged("emoji_big"))
        scrollTo("msg_" + fake.msgs["g1"]!!.first { it.optString("kind") == "trip" }.optString("id")); assertTrue(text("trip_title") == "Kitale pump round" && tagged("trip_progress") && tagged("trip_next"))
        rule.onNodeWithTag("chat_msgs").performScrollToIndex(0); settle()
        // somebody typing: the dots in the head
        rule.runOnUiThread { wire.push(JSONObject().put("t", "typing").put("c", "g1").put("who", "Grace Wanjiru").put("what", "typing")) }; settle()
        waitTag("typing_dots")
        rule.onNodeWithTag("chat_msgs").performScrollToNode(hasTestTag("emoji_big")); settle()
        rule.onNodeWithTag("chat_msgs").performScrollToIndex(2); settle()
        shot("chatp-01-room-" + (if (sw) "sw" else "en") + (if (dark) "-dark" else "-light"))
    }
    @Test fun room_en_light() = roomLooks(false, false)
    @Test fun room_sw_dark() = roomLooks(true, true)
    @Test fun room_en_dark() = roomLooks(false, true)
    @Test fun room_sw_light() = roomLooks(true, false)

    // --- the list: Announcements on top, the Kenya flag, a draft in green ----------------------------------------------------
    private fun listLooks(sw: Boolean, dark: Boolean) {
        Lang.sw = sw; world(); val m = model(); show(m, dark)
        app.getSharedPreferences("chat_drafts", 0).edit().putString("dm_grace", "Bring the tank lid please").commit()
        tap("nav_chat"); waitTag("chatrow_g1")
        val order = rule.onAllNodes(hasTestTag("chatrow_t:news") or hasTestTag("chatrow_g1") or hasTestTag("chatrow_t:ke"), useUnmergedTree = true).fetchSemanticsNodes().map { it.config.getOrNull(SemanticsProperties.TestTag) }
        assertEquals("Announcements first", "chatrow_t:news", order.first())
        assertTrue(has(if (sw) "Matangazo" else "Announcements"))
        assertTrue(tagged("flag_ke"))
        waitTag("row_draft"); assertTrue(has(if (sw) "Rasimu: " else "Draft: "))
        rule.runOnUiThread { wire.push(JSONObject().put("t", "typing").put("c", "t:ke").put("who", "Meril Kiprop").put("what", "typing")) }; settle()
        waitTag("row_typing")
        shot("chatp-02-list-" + (if (sw) "sw" else "en") + (if (dark) "-dark" else "-light"))
    }
    @Test fun list_en_light() = listLooks(false, false)
    @Test fun list_sw_dark() = listLooks(true, true)

    // --- quick replies and Chat settings ----------------------------------------------------------------------------------
    @Test fun quick_replies_send_and_are_edited_and_synced() {
        world(); val m = model(); show(m)
        openChat("dm_grace")
        waitTag("quick_row"); assertEquals("On my way", text("quick_0"))
        tap("quick_1")
        until("quick reply sent") { fake.calls.any { it.startsWith("POST send") && it.contains("Arrived at site") } }
        // typing hides them
        type("Hi"); assertFalse(tagged("quick_row"))
        rule.onNodeWithTag("composer_input").performTextClearance(); settle()
        // Chat settings: add one, remove one; the server gets the whole list
        back(); tap("chat_menu"); tap("menu_settings"); waitTag("chat_settings")
        rule.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("qr_new")))[0].performTextInput("Borehole dry"); settle()
        tap("qr_add")
        until("synced") { fake.userPrefs.optJSONArray("quick")?.toString()?.contains("Borehole dry") == true }
        tap("qr_del_0")
        until("removed") { fake.userPrefs.optJSONArray("quick")?.toString()?.contains("On my way") == false }
        assertEquals(6, fake.userPrefs.getJSONArray("quick").length())
        rule.onNodeWithTag("qr_reset").performScrollTo(); tap("qr_reset")
        until("reset to defaults") { fake.userPrefs.isNull("quick") }
        // another device changed them: the socket says so
        rule.runOnUiThread { m.chat.onEvent(JSONObject().put("t", "prefs").put("prefs", JSONObject().put("quick", JSONArray().put("Gari limeharibika")).put("doubleTap", "👍"))) }; settle()
        assertEquals(listOf("Gari limeharibika"), m.chat.settings.quick); assertEquals("👍", m.chat.settings.doubleTap)
    }

    private fun settingsLooks(sw: Boolean, dark: Boolean) {
        Lang.sw = sw; world(); val m = model(); show(m, dark)
        fake.userPrefs.put("dnd", Dnd(true, "21:00", "06:30", true).json()); m.chat.settings.count(3_400_000)
        tap("nav_chat"); tap("chat_menu"); tap("menu_settings"); waitTag("chat_settings")
        assertTrue(tagged("dt_❤️") && tagged("dnd_from") && tagged("dnd_mentions"))
        assertTrue(text("data_used").endsWith("3.2 MB"))
        shot("chatp-03-settings-" + (if (sw) "sw" else "en") + (if (dark) "-dark" else "-light"))
        tap("dt_👍"); until("double tap synced") { fake.userPrefs.optString("doubleTap") == "👍" }
        rule.onNodeWithTag("dnd_mentions").performScrollTo(); tap("dnd_mentions")
        until("dnd synced") { fake.userPrefs.optJSONObject("dnd")?.optBoolean("mentions") == false && fake.userPrefs.getJSONObject("dnd").has("tz") }
        rule.onNodeWithTag("saver_always").performScrollTo(); tap("saver_always"); assertEquals("always", m.chat.settings.saver)
    }
    @Test fun settings_en_light() = settingsLooks(false, false)
    @Test fun settings_sw_dark() = settingsLooks(true, true)

    // --- double tap, pins, jump to @mention, "↓ 3 new" -----------------------------------------------------------------------
    @Test fun double_tap_reacts_with_the_chosen_emoji() {
        world(); val m = model(); show(m)
        openChat("dm_grace")
        val g = fake.msgs["dm_grace"]!!.last()
        rule.onAllNodes(hasTestTag("bubble_theirs") and hasAnyAncestor(hasTestTag("msg_${g.optString("id")}")), useUnmergedTree = true).onFirst().performTouchInput { doubleClick() }; settle()
        until("❤️") { fake.calls.any { it.startsWith("POST react") && it.contains("❤️") } }
        waitTag("reacts_${g.optString("id")}")
        rule.runOnUiThread { m.chat.settings.doubleTap = "👍"; m.chat.settingsTick.value++ }; settle()
        until("bubble back") { rule.onAllNodes(hasTestTag("bubble_theirs") and hasAnyAncestor(hasTestTag("msg_${g.optString("id")}")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasTestTag("bubble_theirs") and hasAnyAncestor(hasTestTag("msg_${g.optString("id")}")), useUnmergedTree = true).onFirst().performTouchInput { doubleClick() }; settle()
        until("👍") { fake.calls.any { it.startsWith("POST react") && it.contains("👍") } }
    }

    @Test fun pin_a_message_and_the_bar_cycles_through_the_pins() {
        world(); val m = model(); show(m)
        openChat("g1")
        val mine = fake.msgs["g1"]!!.last { it.optString("from") == "Reid Anderson" && it.optString("kind") == "text" }
        rule.onAllNodes(hasTestTag("bubble_mine") and hasAnyAncestor(hasTestTag("msg_${mine.optString("id")}")), useUnmergedTree = true).onFirst().performSemanticsAction(SemanticsActions.OnLongClick); settle()
        waitTag("msg_sheet"); waitTag("act_time"); tap("act_pin")
        until("pinned") { fake.calls.any { it.startsWith("POST pin") && it.contains("\"on\":true") } }
        until("two pins") { m.chat.row("g1")?.pins?.size == 2 }
        // newest pin first; a tap jumps there and shows the next
        waitFor(if (Lang.sw) "1 kati ya 2" else "1 of 2"); assertEquals("On my way", text("pin_text"))
        tap("pin_bar"); waitFor("2 of 2"); assertTrue(text("pin_text").contains("shelf 3"))
        // the system line comes over the socket from the server; it reads "Reid pinned a message"
        rule.runOnUiThread { wire.push(JSONObject().put("t", "msg").put("msg", fake.msgs["g1"]!!.last { it.optString("kind") == "sys" })) }; settle()
        rule.onNodeWithTag("chat_msgs").performScrollToIndex(0); settle()
        waitFor("Reid pinned a message")
    }

    @Test fun jump_to_mention_and_the_new_messages_chip() {
        world()
        repeat(30) { i -> fake.msg("g1", if (i % 2 == 0) "Meril Kiprop" else "Peter Otieno", "Update number $i from the field", 1) }
        fake.chats["g1"]!!.put("unread", 34).put("mentionId", fake.msgs["g1"]!!.first { it.optString("text").startsWith("@Reid") }.optString("id"))
        val m = model(); show(m)
        openChat("g1")
        waitTag("jump_mention")
        tap("jump_mention"); settle(1500)
        until("mention seen") { !tagged("jump_mention") }
        // scrolled up, a message comes in: "↓ 1 new"
        val o = fake.msg("g1", "Grace Wanjiru", "Handle fitted", 0)
        rule.runOnUiThread { wire.push(JSONObject().put("t", "msg").put("msg", o)) }; settle()
        waitTag("new_chip"); assertEquals("↓ 1 new", text("jump_count"))
        tap("new_chip"); until("at the bottom") { !tagged("new_chip") }
    }

    @Test fun the_unread_divider_fades_once_scrolled_past() {
        world()
        repeat(25) { i -> fake.msg("g1", "Meril Kiprop", "Older note $i", 1) }
        fake.chats["g1"]!!.put("unread", 3)
        val m = model(); show(m)
        openChat("g1")
        waitTag("unread_line")
        // new messages push it up and out of sight: it has been passed
        repeat(25) { i -> val o = fake.msg("g1", "Grace Wanjiru", "Live message $i", 0); rule.runOnUiThread { wire.push(JSONObject().put("t", "msg").put("msg", o)) } }
        settle(1500)
        rule.onNodeWithTag("chat_msgs").performScrollToIndex(0); settle(800)
        until("faded") { !tagged("unread_line") }
    }

    // --- the composer: voice preview with trim, paste, six lines and the full editor --------------------------------------
    @Test fun voice_note_is_heard_trimmed_or_deleted_before_it_goes() {
        world(); val m = model(); show(m)
        MicTest.pretend = true
        openChat("dm_grace")
        rule.onNodeWithTag("mic").performTouchInput { down(center) }; settle(2000)
        rule.onNodeWithTag("mic").performTouchInput { up() }; settle()
        waitTag("vp_play"); val full = text("vp_time")
        // drag the end to the middle: half the note
        rule.onNodeWithTag("vp_trim").performTouchInput { click(Offset(width * 0.5f, height / 2f)) }; settle()
        assertTrue("trimmed ${text("vp_time")} < $full", text("vp_time") < full)
        shot("chatp-04-voice-preview")
        tap("send")
        until("voice sent") { fake.calls.any { it.startsWith("POST send") && it.contains("\"kind\":\"voice\"") } }
        val dur = JSONObject(fake.calls.last { it.startsWith("POST send") }.substringAfter("send ")).getJSONArray("media").getJSONObject(0).optDouble("dur")
        assertTrue("dur $dur", dur in 0.5..1.5)
        // another: delete it, nothing goes
        val before = fake.calls.count { it.startsWith("POST send") }
        rule.onNodeWithTag("mic").performTouchInput { down(center) }; settle(1500); rule.onNodeWithTag("mic").performTouchInput { up() }; settle()
        waitTag("vp_delete"); tap("vp_delete")
        assertFalse(tagged("vp_play")); assertEquals(before, fake.calls.count { it.startsWith("POST send") })
    }

    @Test fun a_pasted_picture_goes_to_the_send_preview() {
        world(); val m = model(); show(m)
        openChat("dm_grace")
        val f = File(app.cacheDir, "cam").apply { mkdirs() }.let { File(it, "paste.jpg") }.apply { writeBytes(photoBytes()) }
        until("receiver") { ChatPaste.paste != null }
        rule.runOnUiThread { ChatPaste.paste!!(listOf(f)) }; settle(1500)
        waitTag("media_preview"); tap("media_send")
        until("photo sent") { fake.calls.any { it.startsWith("POST send") && it.contains("\"kind\":\"media\"") } }
        // a small preview went up too (the data saver's thumb)
        val md = JSONObject(fake.calls.last { it.startsWith("POST send") }.substringAfter("send ")).getJSONArray("media").getJSONObject(0)
        assertTrue(md.toString(), md.optString("thumb").startsWith("md"))
    }

    @Test fun the_box_grows_to_six_lines_and_opens_full_screen() {
        world(); val m = model(); show(m)
        openChat("dm_grace")
        type("Line one\nLine two\nLine three\nLine four")
        waitTag("expand"); tap("expand"); waitTag("full_editor")
        tap("full_send")
        until("sent") { fake.calls.any { it.startsWith("POST send") && it.contains("Line four") } }
        assertFalse(tagged("full_editor"))
    }

    // --- data saver ---------------------------------------------------------------------------------------------------
    @Test fun data_saver_shows_previews_and_counts_the_data() {
        world()
        fake.msg("dm_grace", "Grace Wanjiru", "", 3, kind = "media") { put("media", JSONArray().put(JSONObject().put("id", "big1").put("type", "photo").put("w", 1200).put("h", 900).put("size", 480_000))) }
        fake.msg("dm_grace", "Grace Wanjiru", "", 2, kind = "media") { put("media", JSONArray().put(JSONObject().put("id", "big2").put("type", "photo").put("w", 1200).put("h", 900).put("size", 420_000).put("thumb", "th2").put("thumbUrl", "/api/chat/media/th2?e=1&s=x"))) }
        val m = model()
        m.chat.settings.saver = "always"
        show(m)
        openChat("dm_grace")
        waitTag("tap_load"); waitTag("thumb_shown")
        assertTrue(has("469 KB"))
        until("counted") { m.chat.settings.usedThisMonth > 0 }
        shot("chatp-05-data-saver")
    }

    // --- trips --------------------------------------------------------------------------------------------------------
    @Test fun share_a_trip_to_a_chat_and_open_it() {
        world(); val m = model(); show(m)
        tap("nav_chat"); waitTag("chatrow_g1")
        val hook = TripShare.shareToChat; assertNotNull("trips can share to chat", hook)
        rule.runOnUiThread { hook!!("it_7") }; settle()
        waitTag("trip_share_sheet"); assertFalse("only admins post there", tagged("ts_t:news") && !m.chat.row("t:news")!!.admin(m.chat.me))
        sheetShot("chatp-06-trip-share")
        tap("ts_dm_grace")
        until("trip sent") { fake.calls.any { it.startsWith("POST send") && it.contains("\"kind\":\"trip\"") && it.contains("it_7") } }
        waitTag("chat_room"); waitTag("trip_card"); assertEquals("Kitale pump round", text("trip_title"))
        tap("trip_open"); until("trip opened") { TripNav.open == "trip:it_7" || TripNav.open == null }
        // someone not on the trip sees only its title
        val lim = TripCard.from(JSONObject().put("id", "it_9").put("title", "Office run").put("access", false))!!
        assertTrue(lim.limited && lim.next.isBlank())
        assertEquals("🧭 Trip: Kitale pump round", org.rafikioutreach.pin.data.chatPreview("trip", "Kitale pump round"))
    }

    // --- notifications: Do-Not-Disturb, mentions, push ----------------------------------------------------------------------
    @Test fun dnd_quiets_notifications_and_mentions_break_through_when_allowed() {
        world(); model()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val nm = app.getSystemService(NotificationManager::class.java)
        val row = ChatRow("g1", "group", "Kitale repairs")
        val plain = Msg("1", "g1", from = "Grace Wanjiru", at = System.currentTimeMillis(), text = "Tea?")
        val mention = Msg("2", "g1", from = "Grace Wanjiru", at = System.currentTimeMillis(), text = "@Reid Anderson come", mentions = listOf("Reid Anderson"))
        val st = ChatSettings(app)
        // quiet hours all day long
        val now = LocalTime.now(); st.dnd = Dnd(true, now.minusHours(1).withNano(0).toString().take(5), now.plusHours(1).withNano(0).toString().take(5), mentions = false)
        assertTrue(st.dnd.active())
        ChatNotify.show(app, row, listOf(plain), "Reid Anderson", 1, quiet = false)
        assertEquals(ChatNotify.CH_QUIET, shadowOf(nm).getNotification(ChatNotify.id("g1")).channelId)
        ChatNotify.show(app, row, listOf(mention), "Reid Anderson", 2, quiet = false)
        assertEquals("mentions stay quiet unless allowed", ChatNotify.CH_QUIET, shadowOf(nm).getNotification(ChatNotify.id("g1")).channelId)
        st.dnd = st.dnd.copy(mentions = true)
        ChatNotify.show(app, row, listOf(mention.copy(id = "3")), "Reid Anderson", 3, quiet = false)
        assertEquals(ChatNotify.CH_MENTION, shadowOf(nm).getNotification(ChatNotify.id("g1")).channelId)
        // a muted chat still tells me when I'm mentioned
        st.dnd = Dnd(false)
        ChatNotify.cancel(app, "g1")
        ChatNotify.show(app, row.copy(mutedUntil = ChatRow.ALWAYS), listOf(plain.copy(id = "4")), "Reid Anderson", 1, quiet = false)
        assertNull(shadowOf(nm).getNotification(ChatNotify.id("g1")))
        ChatNotify.show(app, row.copy(mutedUntil = ChatRow.ALWAYS), listOf(mention.copy(id = "5")), "Reid Anderson", 1, quiet = false)
        assertNotNull(shadowOf(nm).getNotification(ChatNotify.id("g1")))
        // the clock wraps past midnight
        assertTrue(Dnd(true, "21:00", "06:30").active(java.time.ZonedDateTime.now().withHour(23).toInstant().toEpochMilli()))
        assertFalse(Dnd(true, "21:00", "06:30").active(java.time.ZonedDateTime.now().withHour(12).toInstant().toEpochMilli()))
    }

    @Test fun a_push_becomes_the_chat_notification_and_registers_the_device() {
        world(); val m = model()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val nm = app.getSystemService(NotificationManager::class.java)
        val data = mapOf("t" to "msg", "c" to "g1", "id" to "77", "from" to "Grace Wanjiru", "title" to "Kitale repairs", "kind" to "text", "text" to "Pump is running", "media" to "",
            "mention" to "0", "unread" to "4", "badge" to "6", "at" to System.currentTimeMillis().toString(), "sys" to "", "body" to "Grace: Pump is running")
        assertFalse("the app in front: the socket has it", ChatPush.handle(app, data, "Reid Anderson", front = true))
        assertTrue(ChatPush.handle(app, data, "Reid Anderson"))
        val n = shadowOf(nm).getNotification(ChatNotify.id("g1")); assertNotNull(n)
        assertEquals(4, n.number)
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)!!
        assertTrue(style.messages.any { it.text.toString() == "Pump is running" })
        assertTrue("with a Reply box", n.actions.any { it.remoteInputs?.isNotEmpty() == true })
        assertEquals("tap opens the chat", "g1", shadowOf(n.contentIntent).savedIntent.getStringExtra("chat"))
        // my own message never notifies
        assertFalse(ChatPush.handle(app, data + ("from" to "Reid Anderson"), "Reid Anderson"))
        // the device token: registered, and removed on sign-out (before the session goes)
        onMain { ChatPush.send(app, m.api, "tok-123") }
        assertEquals(listOf("tok-123"), fake.devices)
        onMain { ChatPush.signOut(app, m.api) }
        assertTrue(fake.devices.isEmpty())
        // the build may or may not have google-services.json: either way nothing here starts Firebase in tests
        assertFalse(ChatPush.init(app))
    }

    // --- live: "Seen by" moves, a mention chimes -------------------------------------------------------------------------
    @Test fun seen_by_moves_with_the_socket_and_a_mention_chimes() {
        world(); fake.chats["g1"]!!.put("seen", JSONArray()); val m = model(); show(m)
        openChat("g1")
        val last = m.chat.row("g1")!!.last!!.id
        rule.runOnUiThread { m.chat.onEvent(JSONObject().put("t", "seen").put("c", "g1").put("who", "Meril Kiprop").put("upTo", last)) }; settle()
        assertEquals(listOf("Meril Kiprop"), m.chat.row("g1")!!.seen)
        waitTag("seen_by")
        // a new message clears it
        val o = fake.msg("g1", "Grace Wanjiru", "@Reid Anderson the spanner is here", 0) { put("mentions", JSONArray().put("Reid Anderson")) }
        val before = Chime.played
        rule.runOnUiThread { m.chat.onEvent(JSONObject().put("t", "msg").put("msg", o)) }; settle()
        assertTrue(m.chat.row("g1")!!.seen.isEmpty())
        assertEquals("the mention chime", before + 1, Chime.played)
    }

    // --- the photo viewer: double tap zooms, swipe down closes ---------------------------------------------------------
    @Test fun photo_viewer_zooms_and_swipes_down_to_close() {
        world()
        fake.msg("dm_grace", "Grace Wanjiru", "", 1, kind = "media") { put("media", JSONArray().put(JSONObject().put("id", "ph1").put("type", "photo").put("w", 1200).put("h", 900))) }
        val m = model(); show(m)
        openChat("dm_grace")
        waitTag("media_0"); rule.onAllNodesWithTag("media_0", useUnmergedTree = true)[0].performClick(); settle(); waitTag("media_viewer")
        rule.onNodeWithTag("zoom_box", useUnmergedTree = true).performTouchInput { doubleClick(center) }; settle(600)
        waitTag("zoomed")
        rule.onNodeWithTag("zoomed", useUnmergedTree = true).performTouchInput { doubleClick(center) }; settle(600)
        waitTag("zoom_box")
        rule.onNodeWithTag("zoom_box", useUnmergedTree = true).performTouchInput { swipeDown(startY = top + 100f, endY = bottom - 50f, durationMillis = 300) }; settle()
        until("closed") { !tagged("media_viewer") }
        assertTrue(tagged("chat_room"))
    }

    // --- accessibility, large text, the sun theme ------------------------------------------------------------------------
    @Test fun a_bubble_reads_as_one_line_and_large_text_fits() {
        world(); val m = model(); show(m, fontScale = 2f)
        openChat("g1")
        val mine = fake.msgs["g1"]!!.last()
        val desc = rule.onAllNodes(hasTestTag("bubble_mine") and hasAnyAncestor(hasTestTag("msg_${mine.optString("id")}")), useUnmergedTree = true).onFirst().fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(", ").orEmpty()
        assertTrue(desc, desc.startsWith("You, ") && desc.contains("On my way") && desc.endsWith("read"))
        val g = fake.msgs["g1"]!!.last { it.optString("from") == "Grace Wanjiru" }
        scrollTo("msg_${g.optString("id")}")
        val gd = rule.onAllNodes(hasTestTag("bubble_theirs") and hasAnyAncestor(hasTestTag("msg_${g.optString("id")}")), useUnmergedTree = true).onFirst().fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(", ").orEmpty()
        assertTrue(gd, gd.startsWith("Grace Wanjiru, ") && gd.contains("spanner"))
        rule.onNodeWithTag("chat_msgs").performScrollToIndex(0); settle()
        shot("chatp-07-room-text-200")
    }

    @Test fun sun_theme_room() {
        Look.mode = "sun"; world(); val m = model(); show(m)
        openChat("g1"); waitTag("pin_bar")
        shot("chatp-08-room-sun")
    }

    // --- empty states, swipe for the time, bubble colour --------------------------------------------------------------
    @Test fun friendly_empty_states_with_one_action() {
        Lang.sw = true; world(); val m = model(); show(m)
        openChat("g_new")
        waitTag("chat_empty"); assertTrue(has("Salimia Eldoret drilling 👋"))
        shot("chatp-09-empty-sw")
        tap("chat_empty_go")
        until("hello sent") { fake.calls.any { it.startsWith("POST send") && it.contains("👋") } }
        back(); tap("chat_menu"); tap("menu_starred"); waitTag("starred_none")
        back(); tap("chat_menu"); tap("menu_archived"); waitTag("chat_none_archived"); tap("chat_none_archived_go"); waitTag("chat_list")
        rule.onNodeWithTag("chat_search").performTextInput("zzqx"); settle(800)
        waitTag("search_none"); tap("search_none_go"); waitTag("chatrow_g1")
    }

    @Test fun swipe_left_shows_the_exact_time() {
        world(); val m = model(); show(m)
        openChat("dm_grace")
        val g = fake.msgs["dm_grace"]!!.last()
        rule.onNodeWithTag("msg_${g.optString("id")}").performTouchInput { down(Offset(width * 0.6f, height / 2f)); moveBy(Offset(-60f, 0f)); moveBy(Offset(-120f, 0f)) }; settle(200)
        waitTag("swipe_time")
        rule.onNodeWithTag("msg_${g.optString("id")}").performTouchInput { up() }; settle()
        assertFalse(tagged("swipe_time"))
    }

    @Test fun my_bubble_colour_and_a_rafiki_wallpaper_are_kept_per_chat() {
        world(); val m = model(); show(m)
        openChat("dm_grace"); tap("chat_head"); waitTag("chat_info")
        rule.onNodeWithTag("info_bubble").performScrollTo(); tap("info_bubble"); waitTag("bubble_sheet"); tap("bubble_purple")
        until("bubble saved") { fake.chats["dm_grace"]!!.optString("bubble") == "purple" }
        rule.onNodeWithTag("info_wall").performScrollTo(); tap("info_wall"); waitTag("wall_sheet")
        sheetShot("chatp-10-wallpapers")
        tap("wall_night"); until("wallpaper saved") { fake.chats["dm_grace"]!!.optString("wallpaper") == "night" }
    }

    // --- the data, without screens ---------------------------------------------------------------------------------------
    @Test fun parsing_and_helpers() {
        assertEquals(1, emojiOnly("❤️")); assertEquals(2, emojiOnly("🎉👏")); assertEquals(3, emojiOnly("👍🏽 🙏 🇰🇪")); assertEquals(0, emojiOnly("ok 👍")); assertEquals(0, emojiOnly("👍👍👍👍")); assertEquals(0, emojiOnly("12"))
        val r = ChatRow.from(JSONObject().put("id", "t:ke").put("kind", "team").put("title", "Kenya team").put("country", "ke").put("seen", JSONArray().put("A")).put("mentionId", 0)
            .put("pins", JSONArray().put(JSONObject().put("id", "5").put("text", "x").put("by", "M"))).put("bubble", "teal").put("pphoto", ""))
        assertEquals("ke", r.country); assertEquals(listOf("A"), r.seen); assertEquals("", r.mentionId); assertEquals("5", r.pins[0].id); assertEquals("teal", r.bubble)
        assertEquals("ug", ChatRow.from(JSONObject().put("id", "t:ug").put("kind", "team")).country)
        assertTrue(ChatRow.from(JSONObject().put("id", "t:news").put("kind", "team").put("announce", true)).announce)
        val c = TripCard.from(trip)!!; assertEquals(3, c.done); assertEquals(8, c.total); assertEquals("Kapsaret Borehole", c.next); assertEquals("14:00", c.nextAt)
        assertTrue(TripCard.from(JSONObject().put("id", "x").put("title", "T").put("gone", true))!!.gone)
        Lang.sw = true; assertEquals("Pampu imetengenezwa ✅", org.rafikioutreach.pin.data.quickDefaults()[2]); Lang.sw = false
        assertEquals("3.2 MB", org.rafikioutreach.pin.data.dataText(3_400_000))
    }
}

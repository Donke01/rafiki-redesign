package org.rafikioutreach.pin

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.rafikioutreach.pin.data.Fix
import org.rafikioutreach.pin.data.LatLng
import org.rafikioutreach.pin.data.Prefs
import org.rafikioutreach.pin.data.trips
import org.rafikioutreach.pin.ui.Motion
import org.rafikioutreach.pin.ui.Onb
import org.rafikioutreach.pin.ui.RafikiTheme
import org.rafikioutreach.pin.ui.TripClock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Trips (feat-itina) in the app, against a fake /api/itin (TripFixtures), at 360 dp, in English and Kiswahili, light
 * and dark. Screenshots "trip-…" in build/shots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class TripsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val posts = CopyOnWriteArrayList<String>()
    private lateinit var server: TripFixtures.Server

    @After fun reset() { Lang.sw = false; Motion.on = true; Onb.enabled = true; TripClock.now = { java.time.LocalTime.now().let { it.hour * 60 + it.minute } }; org.rafikioutreach.pin.ui.OfflineMaps.setOnline(true) }

    private fun model(who: String = "Meril Kiprop", admin: Boolean = false, itin: JSONObject = TripFixtures.itin()): AppModel {
        Onb.enabled = false; Motion.on = false
        Prefs(app).apply { pin = "9999"; name = who; saveSnapshot(TripFixtures.snapshot(who, admin)); filter = "all"; onb = true; lang = if (Lang.sw) "sw" else "en"; mePane = "me"; phoneAlerts = false }
        File(app.filesDir, "itins.json").delete()
        server = TripFixtures.Server(posts, itin, who, admin)
        val m = AppModel(app)
        m.api.transport = { method, url, _, body -> server.answer(method, url, body?.let { String(it) } ?: "") }
        m.locator.pretend(Fix(LatLng(0.44, 35.18), 10, System.currentTimeMillis()))
        return m
    }
    private fun show(m: AppModel, dark: Boolean = false) {
        rule.setContent { RafikiTheme(dark = dark) { Box(Modifier.size(360.dp, 800.dp)) { App(m) } } }
        waitTag("nav_map"); settle()
    }
    private fun tagged(t: String) = rule.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun has(t: String) = rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitTag(t: String) = rule.waitUntil(8000) { tagged(t) }
    private fun waitText(t: String) = rule.waitUntil(8000) { has(t) }
    private fun settle() { rule.mainClock.advanceTimeBy(900); rule.waitForIdle() }
    private fun tap(t: String) { rule.onNodeWithTag(t).performScrollTo().performClick(); settle() }
    private fun tapSheet(t: String) { rule.onNodeWithTag(t).performScrollTo().performClick(); settle() }
    private fun shot(name: String) {
        settle()
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    /** A sheet is its own window: draw the app, then the sheet's window on top. */
    private fun sheetShot(name: String) {
        rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); val c = Canvas(b); v.draw(c)
        val d = ShadowDialog.getLatestDialog()?.window?.decorView
        if (d != null) { c.save(); c.translate(0f, (v.height - d.height).toFloat()); d.draw(c); c.restore() }
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun back() {
        rule.runOnUiThread {
            val d = ShadowDialog.getLatestDialog()
            @Suppress("DEPRECATION")
            if (d != null && d.isShowing) d.onBackPressed() else rule.activity.onBackPressedDispatcher.onBackPressed()
        }
        settle()
    }
    private fun scrollTo(tag: String) { rule.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo(); settle() }
    private fun openTrip() { rule.onNodeWithTag("nav_me").performClick(); settle(); waitTag("trip_row_it_1"); tap("trip_row_it_1"); waitTag("trip_screen") }

    @Test fun plan_a_trip_in_a_few_taps() {
        val m = model(); show(m)
        rule.onNodeWithTag("nav_me").performClick(); settle()
        waitTag("my_trips"); scrollTo("my_trips"); shot("trip-01-me-my-trips")
        tap("me_plan_trip"); waitTag("plan_trip")
        tap("plan_suggest"); waitText("open repair · urgent")
        tap("plan_sug_105"); tap("plan_sug_104"); tap("plan_sug_101")
        scrollTo("plan_sug_101"); shot("trip-02-plan-suggested")
        tap("plan_next1"); waitTag("plan_title")
        waitTag("plan_best")
        scrollTo("plan_best"); shot("trip-03-plan-details")
        // back goes to the step before, with the picks kept
        back(); waitTag("plan_next1"); assertTrue(has("Next: 3 stops"))
        tap("plan_next1"); tap("plan_next2")
        waitTag("plan_save")
        assertTrue(posts.any { it.contains("/api/itin/optimise") && it.contains("\"stops\":[\"105\",\"104\",\"101\"]") && it.contains("\"people\":[\"Meril Kiprop\"]") })
        shot("trip-04-plan-preview")
        tap("plan_save"); waitTag("trip_screen")
        assertTrue(posts.any { it.startsWith("POST") && it.contains("/api/itin ") && it.contains("\"plan\"") })
        // the planner was replaced by the trip: back goes to Me
        back(); waitTag("my_trips")
    }

    @Test fun the_day_plan_drag_lock_and_fixes() {
        val m = model(); TripClock.now = { 6 * 60 }; show(m)
        openTrip()
        waitTag("trip_stop_s1")
        assertEquals("09:22", textOf("trip_at_s1")); assertEquals("11:02", textOf("trip_at_s2"))
        shot("trip-05-day-plan")
        scrollTo("trip_leg_1"); shot("trip-06-legs-chips")
        // drag the dispensary (third) to the top: the times change at once and the change is sent
        rule.onNodeWithTag("trip_drag_s3", useUnmergedTree = true).performScrollTo()
        rule.onNodeWithTag("trip_drag_s3", useUnmergedTree = true).performTouchInput { down(center); moveBy(Offset(0f, -900f)); moveBy(Offset(0f, -900f)); up() }
        settle()
        rule.waitUntil(8000) { posts.any { it.contains("\"action\":\"stops\"") } }
        assertTrue(posts.last { it.contains("\"action\":\"stops\"") }.contains("\"stops\":[\"s3\""))
        // lock the borehole's time from its sheet
        tap("trip_stop_s2"); waitTag("trip_stop_sheet")
        sheetShot("trip-07-stop-sheet")
        tapSheet("stop_lock")
        rule.waitUntil(8000) { posts.any { it.contains("\"fixed\":\"") } }
        back(); waitTag("trip_lock_s2")
        // tomorrow: heavy rain on murram and after school closes, each with its one-tap fix
        tap("trip_day_1"); waitTag("trip_fix_SWAP_DAY")
        scrollTo("trip_fix_AFTER_CLOSE"); shot("trip-08-warnings-fix")
        tap("trip_fix_SWAP_DAY")
        rule.waitUntil(8000) { posts.any { it.contains("\"action\":\"swap-days\"") && it.contains("\"day\":1") && it.contains("\"with\":0") } }
    }

    private fun textOf(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.let { c -> c.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString("") { it.text } ?: "" }
    private fun <T> androidx.compose.ui.semantics.SemanticsConfiguration.getOrNull(k: androidx.compose.ui.semantics.SemanticsPropertyKey<T>): T? = if (contains(k)) get(k) else null

    @Test fun on_the_day_next_stop_check_in_and_running_late() {
        val m = model(itin = TripFixtures.itin(status = "active")); TripClock.now = { 11 * 60 }; show(m)
        openTrip()
        waitTag("next_stop"); assertTrue(has("Bishop Luge Memorial"))
        waitTag("trip_late")
        shot("trip-09-next-stop-late")
        tap("trip_late"); waitTag("trip_replan_sheet")
        sheetShot("trip-10-move-the-rest")
        tapSheet("replan_push")
        rule.waitUntil(8000) { posts.any { it.contains("\"action\":\"replan\"") && it.contains("\"mode\":\"push\"") && it.contains("\"now\":\"11:00\"") } }
        // check in: the arrival is kept and the visit flow opens for that project
        tap("next_checkin")
        rule.waitUntil(8000) { posts.any { it.contains("\"action\":\"checkin\"") && it.contains("\"sid\":\"s1\"") } }
        waitTag("tab_visit")
        back()
        waitTag("trip_screen")
        // report the road
        tap("trip_road"); waitTag("road_sheet"); sheetShot("trip-11-report-road")
        tapSheet("road_send")
        rule.waitUntil(8000) { posts.any { it.contains("/api/road-reports") && it.contains("\"kind\":\"potholes\"") } }
    }

    @Test fun office_live_progress_and_alerts() {
        val m = model("Jessica", admin = true, itin = TripFixtures.itin(status = "active")); TripClock.now = { 11 * 60 + 5 }; show(m)
        rule.onNodeWithTag("nav_office").performClick(); settle()
        tap("o_trips"); waitTag("trip_alert")
        shot("trip-12-office-trips")
        tap("trip_row_it_1"); waitTag("trip_progress")
        assertTrue(posts.any { it.contains("/api/itin/it_1?progress=1") })
        waitTag("trip_state_s1")
        shot("trip-13-office-live")
        // back: the trip closes, the Office stays on Trips
        back(); waitTag("trips_plan")
        back(); waitTag("o_overview")
    }

    @Test fun costs_fuel_packing_and_share() {
        val m = model(); TripClock.now = { 6 * 60 }; show(m)
        openTrip()
        tap("trip_tab_costs"); waitTag("cost_fuel_label")
        assertEquals("Fuel: KES 171.60/L diesel, Eldoret, EPRA 15 Oct–14 Nov", textOf("cost_fuel_label").replace("\u2060", ""))
        // the vehicle by its name and type, never its id
        rule.waitUntil(8000) { has("Hilux KDA 123X · Pickup") }; assertFalse(has("vh_"))
        waitTag("fuel_log")
        shot("trip-14-costs")
        scrollTo("fuel_log"); shot("trip-15-costs-logs")
        tap("cost_add_fuel"); waitTag("fuel_sheet")
        rule.onNodeWithTag("fuel_litres").performScrollTo(); rule.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("fuel_litres"))).onFirst().performTextInput("38.5")
        rule.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("fuel_price"))).onFirst().performTextInput("174.65")
        sheetShot("trip-16-add-fuel")
        tapSheet("fuel_save")
        rule.waitUntil(8000) { posts.any { it.contains("\"action\":\"log\"") && it.contains("\"litres\":38.5") && it.contains("\"kesPerL\":174.65") && it.contains("\"itinId\":\"it_1\"") } }
        rule.waitUntil(8000) { !tagged("fuel_sheet") }; settle()
        // back from Costs: the Plan tab, not out of the trip
        back(); waitTag("trip_stop_s1")
        tap("trip_tab_pack"); waitTag("pack_auto:strips")
        tap("pack_auto:strips")
        rule.waitUntil(8000) { posts.any { it.contains("\"action\":\"packing\"") && it.contains("auto:strips") } }
        shot("trip-17-packing")
        tap("trip_tab_share"); waitTag("share_copy")
        tap("share_copy")
        rule.waitUntil(8000) { posts.any { it.contains("\"action\":\"share\"") } }
        waitTag("share_url")
        tap("trip_finish")
        waitTag("trip_summary")
        scrollTo("trip_summary"); shot("trip-18-share-summary")
    }

    @Test fun offline_the_trip_still_works_and_changes_wait() {
        val m = model(); TripClock.now = { 6 * 60 }; show(m)
        openTrip(); waitTag("trip_stop_s1")
        back(); waitTag("my_trips")
        // no signal: the trip opens from the phone, and a change waits
        server.offline = true; org.rafikioutreach.pin.ui.OfflineMaps.setOnline(false)
        tap("trip_row_it_1"); waitTag("trip_offline")
        tap("trip_stop_s3"); waitTag("trip_stop_sheet"); tapSheet("stop_up")
        waitTag("trip_waiting")
        assertEquals(1, m.trips.pendingAll())
        shot("trip-19-offline")
        server.offline = false
        rule.runOnIdle { kotlinx.coroutines.runBlocking { m.trips.flush() } }
        assertEquals(0, m.trips.pendingAll())
        assertTrue(posts.any { it.contains("\"action\":\"stops\"") && it.contains("\"s1\",\"s3\",\"s2\"") })
    }

    @Test fun vehicle_is_shown_by_name_never_its_id() {
        // the vehicle list can't be fetched: the trip's own vehicle is still named (from its costs)
        val m = model(); server.noVehicles = true; TripClock.now = { 6 * 60 }; show(m)
        openTrip(); tap("trip_tab_costs"); waitTag("cost_vehicle")
        rule.waitUntil(8000) { has("Hilux KDA 123X · Pickup") }; assertFalse(has("vh_"))
        // the planner's picker: names and types only
        server.noVehicles = false
        back(); back(); waitTag("my_trips")
        tap("me_plan_trip"); tap("plan_suggest"); waitTag("plan_sug_105"); tap("plan_sug_105"); tap("plan_next1"); waitTag("plan_title")
        tap("plan_vehicle"); settle()
        assertTrue(has("Hilux KDA 123X · Pickup") && has("Meril's motorbike · KMEX 456 · Motorbike")); assertFalse(has("vh_"))
        shot("trip-30-vehicle-picker")
    }

    @Test fun receipt_photo_is_uploaded_then_linked() {
        val m = model()
        val photo = File(app.filesDir, "r1.jpg").apply { writeBytes(byteArrayOf(-1, -40, -1, -32, 1, 2, 3)) }
        val body = { JSONObject().put("action", "log").put("vid", "vh_1").put("itinId", "it_1").put("litres", 30.0).put("kesPerL", 171.47).put("localReceipt", photo.path) }
        // with signal: the photo first (kind=receipt, the trip's id), then the log with its id; the phone path never leaves
        kotlinx.coroutines.runBlocking { m.trips.addLog(body()) }
        val up = posts.indexOfFirst { it.contains("/api/pin-photo") }
        val log = posts.indexOfFirst { it.contains("/api/fuel") && it.contains("\"action\":\"log\"") }
        assertTrue(up in 0 until log)
        assertTrue(posts[up].contains("name=\"kind\"\r\n\r\nreceipt") && posts[up].contains("name=\"itin\"\r\n\r\nit_1"))
        assertTrue(posts[log].contains("\"receipt\":\"rc_1\"") && !posts[log].contains("localReceipt"))
        // no signal: it waits, then goes with the next one
        posts.clear(); server.offline = true
        kotlinx.coroutines.runBlocking { m.trips.addLog(body()) }
        server.offline = false
        kotlinx.coroutines.runBlocking { m.trips.addLog(body().put("litres", 12.0)) }
        assertEquals(2, posts.count { it.contains("\"receipt\":\"rc_1\"") })
        val logs = kotlinx.coroutines.runBlocking { m.trips.logs("it_1") }
        assertTrue(logs.isNotEmpty())
    }

    @Test fun vehicles_and_fuel_prices() {
        val m = model("Jessica", admin = true); show(m)
        rule.onNodeWithTag("nav_office").performClick(); settle()
        tap("o_vehicles"); waitTag("veh_vh_1")
        waitTag("fuel_src_ke"); assertTrue(has("from 4 fuel logs"))
        shot("trip-20-vehicles")
        scrollTo("fuel_ke"); shot("trip-21-fuel-prices")
        tap("fuel_set_ke"); waitTag("price_sheet")
        rule.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("price_town"))).onFirst().performTextInput("Kitale")
        rule.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("price_diesel"))).onFirst().performTextInput("176.2")
        tapSheet("price_save")
        rule.waitUntil(8000) { posts.any { it.contains("\"action\":\"override\"") && it.contains("\"town\":\"Kitale\"") && it.contains("\"diesel\":176.2") } }
        tap("veh_add"); waitTag("veh_sheet"); sheetShot("trip-22-add-vehicle")
    }

    @Test fun group_trips_editor_and_page() {
        val m = model("Jessica", admin = true); show(m)
        rule.onNodeWithTag("nav_office").performClick(); settle()
        tap("o_trips"); tap("trips_pane_group"); waitTag("gtrip_row_gt_1")
        tap("gtrip_row_gt_1"); waitTag("gt_item_i1")
        shot("trip-23-group-days")
        tap("gt_tab_group"); waitTag("gt_person_p1"); shot("trip-24-group-people")
        tap("gt_tab_money"); waitTag("gt_totals"); shot("trip-25-group-costs")
        tap("gt_tab_plan"); waitTag("gt_remind_i1"); tap("gt_remind_i1")
        rule.waitUntil(15000) { tagged("gr_preview") }
        sheetShot("trip-26-group-reminder")
        tapSheet("gr_send")
        rule.waitUntil(8000) { posts.any { it.contains("\"action\":\"remind\"") && it.contains("\"send\":true") } }
        back()
        tap("gt_tab_page"); tap("gt_preview"); waitTag("gview_hero")
        // the group page never shows the private fields
        assertTrue(!has("asthma") && !has("vegetarian") && !has("sam@example.org"))
        shot("trip-27-group-page")
    }

    @Test fun donor_mode_shows_their_group_trip() {
        Onb.enabled = false; Motion.on = false
        val p = org.rafikioutreach.pin.data.DonorPrefs(app)
        p.token = "tok"; p.save(JSONObject().put("ok", true).put("name", "Ann Ochieng").put("viewer", "Ann Ochieng").put("projects", org.json.JSONArray()).put("updates", org.json.JSONArray())
            .put("groupTrips", org.json.JSONArray().put(TripFixtures.group(withPrivate = false))))
        val m = AppModel(app)
        m.donor.api.transport = { _, _, _, _ -> 0 to "" }
        rule.setContent { RafikiTheme { Box(Modifier.size(360.dp, 800.dp)) { App(m) } } }
        waitTag("donor_app"); settle()
        waitTag("donor_trip_0"); scrollTo("donor_trip_0"); shot("trip-28-donor-home-trip")
        tap("donor_trip_0"); waitTag("gview_hero"); shot("trip-29-donor-trip")
        back(); waitTag("donor_trip_0")
    }

    // --- Kiswahili and dark -----------------------------------------------------------------------------------------
    @Test fun kiswahili_light() { Lang.sw = true; swTour(false, "sw") }
    @Test fun kiswahili_dark() { Lang.sw = true; swTour(true, "sw-dark") }
    @Test fun english_dark() { swTour(true, "en-dark") }

    private fun swTour(dark: Boolean, k: String) {
        val m = model(itin = TripFixtures.itin(status = "active")); TripClock.now = { 11 * 60 }; show(m, dark)
        openTrip(); waitTag("next_stop")
        shot("trip-$k-a-next-stop")
        scrollTo("trip_stop_s1"); shot("trip-$k-b-day")
        tap("trip_day_1"); waitTag("trip_fix_SWAP_DAY"); scrollTo("trip_fix_SWAP_DAY"); shot("trip-$k-c-warnings")
        tap("trip_tab_costs"); waitTag("cost_fuel_label"); shot("trip-$k-d-costs")
        tap("trip_tab_pack"); waitTag("pack_auto:strips"); shot("trip-$k-e-packing")
        tap("trip_tab_share"); waitTag("share_copy"); shot("trip-$k-f-share")
        tap("trip_tab_plan"); tap("trip_late"); waitTag("trip_replan_sheet"); sheetShot("trip-$k-g-replan")
        back(); back(); waitTag("my_trips")
        tap("me_plan_trip"); tap("plan_suggest"); waitTag("plan_sug_105"); tap("plan_sug_105"); scrollTo("plan_sug_105"); shot("trip-$k-h-plan")
        tap("plan_next1"); waitTag("plan_best"); shot("trip-$k-i-plan-details")
    }

    @Test fun kiswahili_office_vehicles_and_group() {
        Lang.sw = true
        val m = model("Jessica", admin = true); show(m, dark = true)
        rule.onNodeWithTag("nav_office").performClick(); settle()
        shot("trip-sw-j-office-grid")
        tap("o_vehicles"); waitTag("veh_vh_1"); shot("trip-sw-k-vehicles")
        scrollTo("fuel_ke"); shot("trip-sw-l-fuel")
        // back: the Office grid (not Map home); then Trips from it
        back(); waitTag("o_overview")
        tap("o_trips"); waitTag("trips_plan"); tap("trips_pane_group"); waitTag("gtrip_row_gt_1"); tap("gtrip_row_gt_1"); waitTag("gt_item_i1"); shot("trip-sw-m-group")
        tap("gt_tab_money"); waitTag("gt_totals"); shot("trip-sw-n-group-costs")
    }
}

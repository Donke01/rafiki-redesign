package org.rafikioutreach.pin

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.rafikioutreach.pin.data.Api
import org.rafikioutreach.pin.data.Geo
import org.rafikioutreach.pin.data.LatLng
import org.rafikioutreach.pin.data.OutFile
import org.rafikioutreach.pin.data.Outbox
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LogicTest {
    @Test fun distances_like_the_web_app() {
        // the shared convention: whole metres under 1 km, one decimal under 10 km, then whole km
        assertEquals("910 m", Geo.dist(910.4, false))
        assertEquals("1.0 km", Geo.dist(999.6, false))
        assertEquals("4.8 km", Geo.dist(4750.0, false))
        assertEquals("5.5 km", Geo.dist(5512.0, false))
        assertEquals("10 km", Geo.dist(9999.0, false))
        assertEquals("12 km", Geo.dist(12400.0, false))
        assertEquals("1,234 km", Geo.dist(1234000.0, false))
        // miles: feet under 0.1 mi, then one decimal
        assertEquals("328 ft", Geo.dist(100.0, true))
        assertEquals("0.6 mi", Geo.dist(910.0, true))
        assertEquals("12.4 mi", Geo.dist(19956.0, true))
        // GPS accuracy, never rounded with integer division
        assertEquals("±8 m", Geo.acc(8)); assertEquals("±1.2 km", Geo.acc(1240)); assertEquals("±1.9 km", Geo.acc(1900))
        val d = Geo.metres(LatLng(0.5143, 35.2698), LatLng(0.47, 35.25))
        assertTrue(d in 5300.0..5600.0)
    }

    @Test fun reads_points_and_map_links() {
        assertEquals(LatLng(0.561368, 35.06548), Geo.parse("0.561368, 35.065480"))
        assertEquals(LatLng(-3.3869, 36.683), Geo.parse("https://www.google.com/maps/@-3.3869,36.683,17z"))
        assertEquals(LatLng(0.5, 35.2), Geo.parse("https://maps.google.com/?q=0.5,35.2"))
        assertNull(Geo.parse("Kiptoo school"))
    }

    @Test fun kiswahili_everywhere() {
        Lang.sw = true
        assertEquals("Haifanyi kazi", org.rafikioutreach.pin.data.stateName("notworking"))
        assertEquals("kusini-magharibi", Geo.heading(LatLng(0.5143, 35.2698), LatLng(0.47, 35.25)))
        Lang.sw = false
        assertEquals("south-west", Geo.heading(LatLng(0.5143, 35.2698), LatLng(0.47, 35.25)))
    }

    @Test fun outbox_keeps_changes_without_signal_then_sends_them_with_photos() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val dir = File(ctx.cacheDir, "t" + System.nanoTime()).apply { mkdirs() }
        val ob = Outbox(File(dir, "out.json"))
        val photo = File(dir, "a.jpg").apply { writeBytes(ByteArray(100) { 1 }) }
        var online = false
        val calls = mutableListOf<String>()
        val api = Api("https://x", { "1111" })
        api.transport = { method, url, headers, body ->
            calls += "$method $url ${headers["X-Pin"]}"
            if (!online) 0 to ""
            else if (url.endsWith("/api/pin-photo")) { assertTrue(String(body!!).contains("name=\"update\"\r\n\r\n9001")); 200 to "{\"ok\":true}" }
            else 200 to "{\"ok\":true,\"updateId\":\"9001\"}"
        }
        ob.add(JSONObject().put("action", "visit").put("id", "101").put("state", "working"), listOf(OutFile(photo.path, "photo", "Water flowing")))
        val r1 = ob.flush(api)
        assertEquals(1, r1.waiting); assertEquals(1, ob.count())
        online = true
        val r2 = ob.flush(api)
        assertEquals(listOf(1L), r2.sent); assertEquals(0, ob.count())
        assertTrue(calls.any { it.startsWith("POST https://x/api/pin-photo 1111") })
        assertTrue(!photo.exists()) // sent photos are cleaned up
    }

    @Test fun wrong_pin_stops_sending_and_keeps_the_change() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val ob = Outbox(File(ctx.cacheDir, "o" + System.nanoTime() + ".json"))
        val api = Api("https://x", { "0000" })
        api.transport = { _, _, _, _ -> 401 to "{\"ok\":false,\"reason\":\"pin\"}" }
        ob.add(JSONObject().put("action", "pin").put("id", "1"), emptyList())
        val r = ob.flush(api)
        assertTrue(r.locked); assertEquals(1, ob.count())
    }

    @Test fun refused_change_is_reported_not_retried() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val ob = Outbox(File(ctx.cacheDir, "r" + System.nanoTime() + ".json"))
        val api = Api("https://x", { "1111" })
        api.transport = { _, _, _, _ -> 404 to "{\"ok\":false,\"reason\":\"project\"}" }
        val k = ob.add(JSONObject().put("action", "pin").put("id", "999"), emptyList())
        val r = ob.flush(api)
        assertNotNull(r.failed[k]); assertEquals(0, ob.count())
    }
}

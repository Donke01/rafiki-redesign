package org.rafikioutreach.pin

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.rafikioutreach.pin.data.Hm
import org.rafikioutreach.pin.data.Itin
import org.rafikioutreach.pin.data.ItinEdit
import org.rafikioutreach.pin.data.OnTheDay
import org.rafikioutreach.pin.data.TCost
import org.rafikioutreach.pin.data.Timeline
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Trips (feat-itina): the day's times by the contract's schedule rule, editing, running late, costs and packing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TripLogicTest {
    @After fun reset() { Lang.sw = false }
    private fun trip() = Itin(TripFixtures.itin())
    private fun times(it: Itin, d: Int, now: Int? = null) = Timeline.day(it, d, now).rows.joinToString(" ") { r -> r.kind + (if (r.sid.isNotBlank()) ":" + r.sid else "") + "@" + Hm.text(r.at) + (if (r.until != r.at) "-" + Hm.text(r.until) else "") }

    @Test fun schedule_rule_times_the_day() {
        val it = trip()
        assertEquals(listOf("s1", "s2", "s3"), it.days[0].stops)
        assertEquals("S" to "T:Iten", it.ends(0)); assertEquals("T:Iten" to "E", it.ends(1))
        // leave 07:30, 112 min to the school, 60 min there; lunch after the stop during which 12:30 falls; overnight in Iten
        assertEquals("leave@07:30 drive@07:30-09:22 stop:s1@09:22-10:22 drive@10:22-11:02 stop:s2@11:02-11:47 drive@11:47-12:17 stop:s3@12:17-13:07 lunch@13:07-13:52 drive@13:52-14:42 overnight@14:42", times(it, 0))
        val p = Timeline.day(it, 0)
        assertEquals(232, p.driveMin); assertEquals(157.0, p.km, 0.01); assertEquals(14 * 60 + 42, p.endsAt); assertTrue(p.warnings.isEmpty())
        // day 1: the long murram drive arrives after school closes, and the day runs past the working hours
        val p1 = Timeline.day(it, 1)
        val w = p1.warnings.map { it.code }
        assertTrue(w.toString(), "AFTER_CLOSE" in w && "OVER_HOURS" in w)
        assertEquals("Visit runs until 16:00, after the school closes at 15:30", p1.warnings.first { it.code == "AFTER_CLOSE" }.text)
        assertEquals("caution", p1.warnings.first { it.code == "AFTER_CLOSE" }.level)
        assertEquals("Day ends 18:45, after 17:30", p1.warnings.first { it.code == "OVER_HOURS" }.text)
        // arriving after closing is "difficult"
        val late = Timeline.day(ItinEdit.visitMin(it, "s4", 60 + 45), 1).warnings.first { it.code == "AFTER_CLOSE" }
        assertEquals("difficult", late.level); assertEquals("Arrives 16:00, after the school closes", late.text)
        // the server's words and one-tap fix win when nothing is waiting to send; its road warning stays with its leg
        val shown = Timeline.warnings(it, 1, p1)
        assertEquals("Arrives 15:40, after the school closes", shown.first { it.code == "AFTER_CLOSE" }.text)
        assertEquals("move", shown.first { it.code == "AFTER_CLOSE" }.fix?.optString("action"))
        assertTrue(shown.any { it.code == "SWAP_DAY" && it.fix?.optString("action") == "swap" })
        assertTrue(Timeline.warnings(it, 0, p).any { it.code == "ROAD_REPORT" })
        Lang.sw = true
        assertEquals("Ziara inaendelea hadi 16:00, baada ya shule kufungwa saa 15:30", Timeline.day(it, 1).warnings.first { it.code == "AFTER_CLOSE" }.text)
    }

    @Test fun legs_come_from_the_engine_the_matrix_or_an_estimate() {
        val raw = TripFixtures.itin()
        raw.put("matrix", JSONObject().put("keys", org.json.JSONArray(listOf("S", "s1", "s3"))).put("min", org.json.JSONArray().put(org.json.JSONArray(listOf(0, 112, 95))).put(org.json.JSONArray(listOf(112, 0, 33))).put(org.json.JSONArray(listOf(95, 33, 0))))
            .put("km", org.json.JSONArray().put(org.json.JSONArray(listOf(0, 70, 60))).put(org.json.JSONArray(listOf(70, 0, 20))).put(org.json.JSONArray(listOf(60, 20, 0)))))
        val it = Itin(raw)
        assertEquals("trip", it.leg("S", "s1")!!.src); assertEquals(112, it.leg("S", "s1")!!.min)
        assertEquals("trip", it.leg("s2", "s1")!!.src) // the same leg both ways
        assertEquals("matrix", it.leg("s1", "s3")!!.src); assertEquals(33, it.leg("s1", "s3")!!.min); assertEquals(20.0, it.leg("s1", "s3")!!.km, 0.01)
        val g = it.leg("s4", "s1")!!
        assertEquals("estimate", g.src); assertTrue(g.min > 0); assertTrue(g.estimate)
    }

    @Test fun editing_re_times_at_once_and_says_what_to_send() {
        var it = trip()
        // drag the dispensary to the top: it now comes first and everything moves
        it = ItinEdit.move(it, 0, 2, 0)
        assertEquals(listOf("s3", "s1", "s2"), it.days[0].stops); assertEquals(1, it.waiting)
        assertTrue(times(it, 0).startsWith("leave@07:30 drive@07:30-"))
        val body = ItinEdit.stopsBody(it)
        assertEquals("stops", body.getString("action")); assertEquals("s3", body.getJSONArray("days").getJSONObject(0).getJSONArray("stops").getString(0))
        // lock the borehole at 11:30 (a committee meeting): wait for it
        it = ItinEdit.lock(trip(), "s2", 11 * 60 + 30)
        assertTrue(times(it, 0), times(it, 0).contains("wait:s2@11:02-11:30 stop:s2@11:30-12:15"))
        assertEquals("11:30", ItinEdit.stopsBody(it, listOf("s2")).getJSONArray("stops").getJSONObject(0).getString("fixed"))
        // locked too early: it can't be reached
        assertEquals("FIXED_LATE", Timeline.day(ItinEdit.lock(trip(), "s2", 10 * 60), 0).warnings.single().code)
        // move to another day, remove, swap days, visit length
        it = ItinEdit.toDay(trip(), 1, "s5", 0)
        assertEquals(listOf("s1", "s2", "s3", "s5"), it.days[0].stops); assertEquals(listOf("s4"), it.days[1].stops)
        assertEquals(listOf("s1", "s3"), ItinEdit.remove(trip(), 0, "s2").days[0].stops)
        assertEquals(listOf("s4", "s5"), ItinEdit.swapDays(trip(), 0, 1).days[0].stops)
        assertEquals(90, ItinEdit.visitMin(trip(), "s1", 90).stop("s1")!!.visitMin)
    }

    @Test fun running_late_moves_the_rest() {
        val it = trip()
        // on time at 07:30: nothing to move
        assertTrue(ItinEdit.overflow(it, 0, 7 * 60 + 30).isEmpty())
        // still at the office at 13:30: the last stops no longer fit before 17:30
        val out = ItinEdit.overflow(it, 0, 13 * 60 + 30)
        assertTrue(out.toString(), out.isNotEmpty() && "s1" !in out)
        val pushed = ItinEdit.push(it, 0, out)
        assertEquals(out + listOf("s4", "s5"), pushed.days[1].stops)
        assertEquals("skipped", ItinEdit.drop(it, out).stop(out.first())!!.status)
        // re-timed from now: the next drive starts at now
        assertTrue(times(it, 0, 9 * 60), times(it, 0, 9 * 60).startsWith("leave@07:30 drive@09:00-10:52"))
        // checked in: that stop keeps its check-in time, and is no longer "next"
        val done = ItinEdit.mark(it, "s1", "done", java.time.LocalDate.now().atTime(9, 41).atZone(org.rafikioutreach.pin.ui.Dates.zone()).toInstant().toString(), "Meril")
        assertTrue(times(done, 0, 10 * 60), times(done, 0, 10 * 60).contains("stop:s1@09:41-10:41"))
        val today = it.days[0].date
        assertEquals("s1", OnTheDay.next(it, today)); assertEquals("s2", OnTheDay.next(done, today))
        assertEquals(0, OnTheDay.lateBy(it, today, 7 * 60 + 30)); assertEquals(60, OnTheDay.lateBy(it, today, 8 * 60 + 30))
        assertEquals(Triple(1, 0, 0), OnTheDay.counts(done))
    }

    @Test fun costs_packing_and_extras_from_the_record() {
        val it = trip()
        val c = it.cost!!
        assertEquals("Fuel: KES 171.60/L diesel, Eldoret, EPRA 15 Oct–14 Nov", c.fuelLabel.replace("\u2060", ""))
        assertEquals(22355L, c.estTotal); assertEquals(7093L, c.actualTotal); assertFalse(c.fuelMissing); assertEquals(10.6, c.lPer100!!, 0.01)
        Lang.sw = true
        assertEquals("Mafuta: KES 171.60/L dizeli, Eldoret, EPRA 15 Okt–14 Nov", Itin(TripFixtures.itin()).cost!!.fuelLabel.replace("\u2060", ""))
        Lang.sw = false
        // no price: never invented
        val none = TCost.from(JSONObject().put("fuel", JSONObject().put("kes", JSONObject.NULL).put("reason", "no-price").put("label", JSONObject().put("en", "Fuel: add a fuel price").put("sw", "Mafuta: weka bei ya mafuta"))).put("est", JSONObject().put("totalKes", 11000).put("fuelMissing", true)))!!
        assertTrue(none.fuelMissing); assertNull(none.fuelKes); assertEquals("Fuel: add a fuel price", none.fuelLabel)
        assertEquals(5, it.packing.size); assertTrue(it.packing.first { it.key == "auto:parts:105" }.done)
        val ticked = ItinEdit.packTick(it, "auto:strips", true)
        assertTrue(ticked.packing.first { it.key == "auto:strips" }.done)
        assertEquals(4, ItinEdit.packRemove(it, "std:firstaid").packing.size)
        assertEquals("Spare tyre checked", ItinEdit.packAdd(it, "Spare tyre checked").packing.last().text)
        assertEquals("L/\u2060100\u00A0km", org.rafikioutreach.pin.data.unbreak("L/100 km")); assertEquals(200L, it.extras.single().kes); assertTrue(it.extras.single().actual)
        assertEquals("No office location is set for this country yet. Choose another place to start, or ask an admin to set it (Office › Vehicles › Trip settings).", org.rafikioutreach.pin.data.itinReason("no-office"))
        // vehicles by name (with the plate unless the name has it) and type, never the id
        val vs = org.json.JSONArray(TripFixtures.vehicles().getJSONArray("vehicles").toString()).let { a -> (0 until a.length()).map { org.rafikioutreach.pin.data.Vehicle(a.getJSONObject(it)) } }
        assertEquals(listOf("vh_1" to "Hilux KDA 123X · Pickup", "vh_2" to "Meril's motorbike · KMEX 456 · Motorbike"), org.rafikioutreach.pin.data.Vehicle.options(vs, "vh_1", null))
        assertEquals("vh_9" to "Hilux KDA 123X · Pickup", org.rafikioutreach.pin.data.Vehicle.options(emptyList(), "vh_9", JSONObject().put("name", "Hilux KDA 123X").put("type", "pickup")).single())
        assertEquals("The trip's vehicle", org.rafikioutreach.pin.data.Vehicle.options(emptyList(), "vh_9", null).single().second)
        assertEquals("Iten", it.days[0].night!!.town); assertEquals(3500L, it.days[0].night!!.kes)
        assertNotNull(ItinEdit.lodging(it, 0, null).days[0].let { d -> d }); assertNull(ItinEdit.lodging(it, 0, null).days[0].night)
    }
}

package org.rafikioutreach.pin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rafikioutreach.pin.data.Badges
import org.rafikioutreach.pin.data.Snapshot
import org.rafikioutreach.pin.widget.WidgetCounts
import org.rafikioutreach.pin.widget.WidgetText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Plain JUnit: the badge rules and the widget's words. */
class BadgeRulesTest {
    private val zone: ZoneId = ZoneId.of("Africa/Nairobi")
    private val now: Instant = Instant.parse("2026-10-21T09:00:00Z")
    private val ck = mapOf("101" to "ke", "201" to "tz", "301" to "ug", "401" to "mw")
    private fun e(a: String, day: String, by: String = "Meril Kiprop", id: String = "101", extra: (JSONObject) -> Unit = {}) =
        JSONObject().put("by", by).put("a", a).put("id", id).put("t", "x").put("at", day + "T08:00:00Z").also(extra)
    private fun of(act: List<JSONObject>, me: String = "Meril Kiprop") = Badges.compute(act, me, { ck[it] }, now, zone).associateBy { it.def.k }

    @After fun en() { Lang.sw = false }

    @Test fun nothing_yet_is_all_locked_with_how_to_earn() {
        val b = of(emptyList())
        assertEquals(12, b.size)
        assertTrue(b.values.none { it.got })
        assertEquals("Pin a location", b["pin1"]!!.progress)
        assertEquals("0/10 visits", b["visit10"]!!.progress)
        assertEquals("0/4 countries", b["countries4"]!!.progress)
    }

    @Test fun counts_only_my_own_work_ignoring_case() {
        val act = listOf(e("pin", "2026-10-01", "meril kiprop "), e("pin", "2026-10-02", "Grace Achieng"), e("visit", "2026-10-02", "MERIL KIPROP"), e("fixed", "2026-10-03"), e("new", "2026-10-03"))
        val b = of(act)
        assertTrue(b["pin1"]!!.got); assertTrue(b["visit1"]!!.got); assertTrue(b["fixed1"]!!.got); assertTrue(b["new1"]!!.got)
        assertEquals(1, b["pin10"]!!.n)
        assertEquals("Earned", b["pin1"]!!.progress)
        assertTrue(of(act, "").values.none { it.got })
    }

    @Test fun ten_visits_this_month_and_progress() {
        val act = (1..7).map { e("visit", "2026-10-%02d".format(it * 2)) } + (1..4).map { e("visit", "2026-09-%02d".format(it + 10)) }
        val b = of(act)
        assertEquals("7/10 visits", b["month10"]!!.progress)
        assertFalse(b["month10"]!!.got)
        assertTrue(b["visit10"]!!.got) // 11 in all
        assertEquals(0.7f, b["month10"]!!.fraction, 0.001f)
        Lang.sw = true
        assertEquals("ziara 7/10", b["month10"]!!.progress)
        assertEquals("Mwezi wa kazi", b["month10"]!!.def.title)
        Lang.sw = false
        val b2 = of(act + (1..3).map { e("visit", "2026-10-20") })
        assertTrue(b2["month10"]!!.got)
    }

    @Test fun five_day_streak_needs_days_in_a_row() {
        assertEquals(3, Badges.bestRun(listOf("2026-10-01", "2026-10-02", "2026-10-03", "2026-10-05", "2026-10-06").map { LocalDate.parse(it) }))
        val gap = listOf("01", "02", "03", "05", "06", "07").map { e("pin", "2026-10-$it") }
        assertEquals("3/5 days", of(gap)["streak5"]!!.progress)
        val run = listOf("01", "02", "02", "03", "04", "05").mapIndexed { i, d -> e(if (i % 2 == 0) "pin" else "visit", "2026-10-$d") }
        assertTrue(of(run)["streak5"]!!.got)
        // across the end of a month, in the person's own time zone
        val late = listOf("2026-09-28", "2026-09-29", "2026-09-30", "2026-10-01").map { JSONObject().put("by", "Meril Kiprop").put("a", "visit").put("id", "101").put("at", it + "T22:30:00Z") }
        assertEquals(4, of(late)["streak5"]!!.n)
    }

    @Test fun all_four_countries_by_visits() {
        val three = listOf(e("visit", "2026-10-01", id = "101"), e("visit", "2026-10-02", id = "201"), e("visit", "2026-10-03", id = "301"), e("pin", "2026-10-04", id = "401"), e("visit", "2026-10-05", id = "999"))
        assertEquals("3/4 countries", of(three)["countries4"]!!.progress)
        assertTrue(of(three + e("visit", "2026-10-06", id = "401"))["countries4"]!!.got)
    }

    @Test fun water_tests_and_story_moments() {
        val act = listOf(e("visit", "2026-10-01") { it.put("wq", 1) }, e("visit", "2026-10-02") { it.put("wq", 1).put("st", 1) }, e("visit", "2026-10-03"),
            e("visit", "2026-10-04") { it.put("st", true) }, e("visit", "2026-10-05") { it.put("st", 1) })
        val b = of(act)
        assertEquals("2/3 tests", b["water3"]!!.progress)
        assertTrue(b["story3"]!!.got)
    }

    @Test fun hundred_pins() {
        val b = of((1..100).map { e("pin", "2026-0%d-%02d".format(1 + it % 9, 1 + it % 28)) })
        assertTrue(b["pin100"]!!.got); assertTrue(b["pin10"]!!.got)
        assertEquals("99/100 pins", of((1..99).map { e("pin", "2026-10-01") })["pin100"]!!.progress)
    }

    @Test fun new_badges_celebrate_once_and_never_on_the_first_look() {
        val st = Badges.compute(listOf(e("pin", "2026-10-01"), e("visit", "2026-10-01")), "Meril Kiprop", { ck[it] }, now, zone)
        assertTrue(Badges.fresh(st, null).isEmpty())
        assertEquals(listOf("visit1"), Badges.fresh(st, setOf("pin1")).map { it.def.k })
        assertTrue(Badges.fresh(st, setOf("pin1", "visit1")).isEmpty())
    }

    // --- the home-screen widget -------------------------------------------------------------------

    private fun snap(): Snapshot {
        val old = Instant.now().minusSeconds(200L * 86400).toString(); val recent = Instant.now().minusSeconds(10L * 86400).toString()
        fun p(id: String, c: String, gps: Boolean) = JSONObject().put("id", id).put("title", "P$id").put("country", c).put("ck", c).put("type", "borehole").put("status", "Completed").apply { if (gps) put("gps", JSONArray().put(0.5).put(35.2)) }
        return Snapshot.from(JSONObject().put("who", "Meril Kiprop")
            .put("projects", JSONArray().put(p("1", "ke", true)).put(p("2", "ke", true)).put(p("3", "ke", true)).put(p("4", "ke", false)).put(p("5", "tz", true)))
            .put("visits", JSONObject().put("1", JSONObject().put("at", old).put("by", "x").put("state", "working")).put("2", JSONObject().put("at", recent).put("by", "x").put("state", "working")))
            .put("repairs", JSONObject().put("2", JSONObject().put("state", "repair").put("urgency", "urgent")).put("3", JSONObject().put("state", "repair").put("urgency", "month"))
                .put("5", JSONObject().put("state", "notworking").put("urgency", "urgent")))
            .put("assigned", JSONArray().put(JSONObject().put("aid", "a1").put("id", "1")).put(JSONObject().put("aid", "a2").put("id", "3")))
            .put("jobs", JSONArray().put("2")))
    }

    @Test fun widget_counts_from_the_saved_list() {
        // due: pinned and not visited for 6 months (1 old, 3 never, 5 never); 2 was visited lately; 4 has no location
        assertEquals(WidgetCounts(3, 2, 3), WidgetText.counts(snap(), ""))
        // in my home country only, like the Projects list
        assertEquals(WidgetCounts(2, 1, 3), WidgetText.counts(snap(), "ke"))
        assertEquals(null, WidgetText.counts(null, ""))
    }

    @Test fun widget_words_in_both_languages() {
        assertEquals("3 visits due · 1 urgent repair · 2 assigned to you", WidgetText.line(WidgetCounts(3, 1, 2)))
        assertEquals("1 visit due · 2 urgent repairs", WidgetText.line(WidgetCounts(1, 2, 0)))
        assertEquals("All caught up ✓", WidgetText.line(WidgetCounts(0, 0, 0)))
        assertEquals("Open RAFIKI Pin to sign in", WidgetText.line(null))
        assertEquals("3 due · 1 urgent · 2 yours", WidgetText.short(WidgetCounts(3, 1, 2)))
        assertEquals("2 yours", WidgetText.short(WidgetCounts(0, 0, 2)))
        Lang.sw = true
        assertEquals("Ziara 3 zinasubiri · Tengenezo 1 la haraka · 2 umepewa", WidgetText.line(WidgetCounts(3, 1, 2)))
        assertEquals("Ziara 1 inasubiri · Matengenezo 2 ya haraka", WidgetText.line(WidgetCounts(1, 2, 0)))
        assertEquals("3 zinasubiri · 1 haraka · 2 zako", WidgetText.short(WidgetCounts(3, 1, 2)))
        assertEquals("Tembelea karibu", WidgetText.nearLabel(false))
        Lang.sw = false
    }

    @Test fun widget_time_stamp() {
        val at = LocalDate.of(2026, 10, 21).atTime(10, 42).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals("10:42", org.rafikioutreach.pin.widget.PinWidget.updatedText(at, at + 3600_000))
        assertEquals("21 Oct", org.rafikioutreach.pin.widget.PinWidget.updatedText(at, at + 3 * 86400_000L))
        assertEquals("", org.rafikioutreach.pin.widget.PinWidget.updatedText(0))
    }
}

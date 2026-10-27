package org.rafikioutreach.pin.data

import org.json.JSONArray
import org.json.JSONObject
import org.rafikioutreach.pin.t
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/*
 * Field trips (feat-itina), as /api/itin sends them (S/itin-contract-changes.md, v1).
 *
 * The server orders the stops, splits them across days, prices the trip and writes the warnings. The phone keeps the
 * record it was last sent, so dragging a stop, moving it to another day, locking a time or skipping one re-times the day
 * at once ([Timeline], the contract's schedule rule, from the cached legs and matrix), with or without signal. The change
 * is then sent ([ItinRepo]); the server's answer (new costs, warnings and packing) replaces the local copy.
 */

/** Minutes after midnight <-> "07:30". */
object Hm {
    fun parse(s: String?): Int? = s?.trim()?.let { Regex("^(\\d{1,2}):(\\d{2})").find(it) }?.let { m -> m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt() }
    fun text(min: Int): String { val m = ((min % 1440) + 1440) % 1440; return "%02d:%02d".format(m / 60, m % 60) }
    /** "1 h 52", "45 min" (the trip engine's chip words). */
    fun dur(min: Int): String = when {
        min < 60 -> t("$min min", "dak $min")
        min % 60 == 0 -> t("${min / 60} h", "saa ${min / 60}")
        else -> t("${min / 60} h ${"%02d".format(min % 60)}", "saa ${min / 60} dak ${"%02d".format(min % 60)}")
    }
}

internal fun JSONObject.str(k: String): String = optString(k).takeIf { it != "null" } ?: ""
internal fun JSONObject.latLng(): LatLng? {
    if (has("lat") && has("lng") && !isNull("lat") && !isNull("lng")) return LatLng(optDouble("lat"), optDouble("lng")).takeIf { !it.lat.isNaN() && !it.lng.isNaN() }
    optJSONArray("ll")?.let { if (it.length() == 2) return LatLng(it.optDouble(0), it.optDouble(1)) }
    return null
}
internal fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONArray?.strs(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() && it != "null" }
internal fun JSONObject.longOrNull(k: String): Long? = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() }?.roundToLong() else null
internal fun JSONObject.dblOrNull(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null
internal fun JSONObject.copyJson(): JSONObject = JSONObject(toString())

/** Words the server sends in both languages ({en, sw}), in the app's language. */
fun words(o: Any?): String = when (o) {
    is JSONObject -> unbreak(t(o.str("en"), o.str("sw").ifBlank { o.str("en") }))
    is String -> o.takeIf { it != "null" } ?: ""
    else -> ""
}
/** Keeps "L/100 km" (and "15 Oct–14 Nov") together on one line: never broken after the slash or the dash. */
fun unbreak(s: String): String = s.replace(Regex("L/100 km"), "L/\u2060100\u00A0km").replace("–", "\u2060–\u2060")

/** {en, sw} that the phone itself makes (for a change made with no signal, until the server answers). */
fun bi(en: String, sw: String): JSONObject = JSONObject().put("en", en).put("sw", sw)

/** When a site is open: a school 08:00–15:30, a clinic its hours. */
data class TWindow(val open: Int?, val close: Int?, val src: String = "") {
    companion object {
        fun from(o: JSONObject?): TWindow? = o?.let { TWindow(Hm.parse(it.str("open")), Hm.parse(it.str("close")), it.str("src")) }?.takeIf { it.open != null || it.close != null }
    }
}

/** A place a day starts or ends at. */
data class TPoint(val kind: String, val name: String, val ll: LatLng?) {
    fun json(): JSONObject = JSONObject().put("kind", kind).put("name", name).apply { ll?.let { put("lat", it.lat); put("lng", it.lng) } }
    companion object { fun from(o: JSONObject?): TPoint? = o?.let { TPoint(it.str("kind"), it.str("name"), it.latLng()) } }
}

/** One stop (a project or any place). */
data class TStop(
    val sid: String, val pid: String, val name: String, val ll: LatLng?, val type: String, val visitMin: Int,
    val window: TWindow?, val fixed: Int?, val locked: Boolean, val day: Int, val why: List<String>,
    /** planned, done, late, skipped, moved. */
    val status: String, val checkinAt: String, val checkinBy: String, val note: String,
) {
    val done get() = status == "done" || status == "late" && checkinAt.isNotBlank()
    val open get() = status != "done" && status != "skipped" && checkinAt.isBlank()
    companion object {
        fun from(o: JSONObject): TStop {
            val ci = o.optJSONObject("checkin")
            return TStop(o.str("sid"), o.str("id"), o.str("name"), o.latLng(), o.str("type"), o.optInt("visitMin", 45).coerceIn(5, 720),
                TWindow.from(o.optJSONObject("window")), Hm.parse(o.str("fixed")), o.optBoolean("locked"), o.optInt("day"),
                o.optJSONArray("why").let { a -> if (a == null) emptyList() else (0 until a.length()).map { words(a.opt(it)) }.filter { it.isNotBlank() } },
                o.str("status").ifBlank { "planned" }, ci?.str("at") ?: "", ci?.str("by") ?: "", o.str("note"))
        }
    }
}

/** A warning, with the one-tap fix the server offers ("Swap with Tuesday?"). */
data class TWarn(val code: String, val level: String, val text: String, val sid: String = "", val leg: String = "", val fix: JSONObject? = null) {
    companion object {
        fun from(o: JSONObject) = TWarn(o.str("code"), o.str("level").ifBlank { "caution" }, words(o), o.str("sid"), o.str("leg"), o.optJSONObject("fix"))
    }
}

/** Tonight's town and lodging (a multi-day trip): suggested near tomorrow's first stop, or typed. */
data class TNight(val town: String, val ll: LatLng?, val suggested: Boolean, val why: String, val lodging: String, val phone: String, val kes: Long, val note: String) {
    val key get() = "T:$town"
    companion object {
        fun from(o: JSONObject?): TNight? = o?.takeIf { it.str("town").isNotBlank() }?.let {
            val l = it.optJSONObject("lodging") ?: JSONObject()
            TNight(it.str("town"), it.latLng(), it.optBoolean("suggested"), words(it.opt("why")), l.str("name"), l.str("phone"), l.longOrNull("kes") ?: 0, l.str("note"))
        }
    }
}

data class TDay(
    val date: String, val stops: List<String>, val startAt: TPoint?, val endAt: TPoint?, val night: TNight?,
    val km: Double, val driveMin: Int, val endsAt: String, val risk: String, val rainMm: Double?, val roadLevel: String, val warnings: List<TWarn>,
    /** The point keys the day starts and ends at (v1.1): "S", "E", "T:<town>". */
    val startKey: String = "", val endKey: String = "",
) {
    companion object {
        fun from(o: JSONObject): TDay {
            val w = o.optJSONObject("weather")
            return TDay(o.str("date"), o.optJSONArray("stops").strs(), TPoint.from(o.optJSONObject("startAt")), TPoint.from(o.optJSONObject("endAt")), TNight.from(o.optJSONObject("overnight")),
                o.optDouble("km", 0.0).let { if (it.isNaN()) 0.0 else it }, o.optInt("driveMin"), o.str("endsAt"), o.str("riskLevel").ifBlank { "ok" }, w?.dblOrNull("rainMm"), w?.str("roadLevel") ?: "",
                o.optJSONArray("warnings").objs().map { TWarn.from(it) }, o.optJSONObject("startAt")?.str("key") ?: "", o.optJSONObject("endAt")?.str("key") ?: "")
        }
    }
}

/** One leg from the trip engine: km, time, tarmac/murram, rain, risk and its own warnings. */
data class TLeg(
    val km: Double, val min: Int, val maxMin: Int, val src: String, val key: String, val roads: Boolean,
    val surface: String, val rain: String, val time: String, val risk: String, val roadLevel: String, val warnings: List<TWarn>,
    val geometry: List<LatLng> = emptyList(),
) {
    val estimate get() = src == "estimate"
    companion object {
        fun from(o: JSONObject): TLeg {
            val ch = o.optJSONObject("chips") ?: JSONObject()
            val g = o.optJSONArray("geometry")
            return TLeg(o.optDouble("km", 0.0), o.optInt("min"), o.optInt("maxMin", o.optInt("min")), o.str("src").ifBlank { "trip" }, o.str("key"), o.optBoolean("roads", true),
                words(ch.opt("surface")), words(ch.opt("rain")), words(ch.opt("time")), o.str("riskLevel").ifBlank { "ok" }, o.str("roadLevel"),
                o.optJSONArray("warnings").objs().map { TWarn.from(it) },
                if (g == null) emptyList() else (0 until g.length()).mapNotNull { i -> g.optJSONArray(i)?.let { p -> LatLng(p.optDouble(0), p.optDouble(1)) } })
        }
        /** Neither the leg nor the matrix has it (a stop added with no signal): the straight line × 1.4 at 45 km/h (the schedule rule). */
        fun guess(a: LatLng, b: LatLng): TLeg {
            val km = Geo.metres(a, b) / 1000 * 1.4
            val min = maxOf(3, (km / 45.0 * 60).roundToInt())
            return TLeg(km, min, (min * 1.3).roundToInt(), "estimate", "", false, "", "", "~" + Hm.dur(min), "ok", "", emptyList())
        }
    }
}

/** A line of the packing list (made from the stops, or typed). */
data class TPack(val key: String, val text: String, val done: Boolean, val auto: Boolean)

/** An extra cost: tolls, parking, lodging, lunch or other; [actual] once paid. */
data class TExtra(val xid: String, val day: Int?, val kind: String, val label: String, val kes: Long, val actual: Boolean, val receipt: String?) {
    fun json(): JSONObject = JSONObject().put("xid", xid).put("kind", kind).put("label", label).put("kes", kes).put("actual", actual).put("day", day ?: JSONObject.NULL).put("receipt", receipt ?: JSONObject.NULL)
    companion object {
        fun from(o: JSONObject) = TExtra(o.str("xid"), if (o.has("day") && !o.isNull("day")) o.optInt("day") else null, o.str("kind").ifBlank { "other" }, o.str("label"), o.longOrNull("kes") ?: 0, o.optBoolean("actual"), o.str("receipt").ifBlank { null })
        fun kindName(k: String) = when (k) { "toll" -> t("Toll", "Ushuru wa barabara"); "parking" -> t("Parking", "Maegesho"); "lodging" -> t("Lodging", "Malazi"); "lunch" -> t("Lunch", "Chakula cha mchana"); else -> t("Other", "Nyingine") }
    }
}

/** The trip's costs, as the server worked them out (always KES). */
data class TCost(
    val fuelLabel: String, val fuelKes: Long?, val fuelReason: String, val litres: Double?, val lPer100: Double?, val basis: String,
    val days: List<JSONObject>, val est: JSONObject, val actual: JSONObject?,
) {
    val estTotal get() = est.longOrNull("totalKes") ?: 0
    val actualTotal get() = actual?.longOrNull("totalKes")
    val fuelMissing get() = est.optBoolean("fuelMissing") || fuelKes == null
    companion object {
        fun from(o: JSONObject?): TCost? = o?.let { c ->
            val f = c.optJSONObject("fuel") ?: JSONObject()
            TCost(words(f.opt("label")), f.longOrNull("kes"), f.str("reason"), f.dblOrNull("litres"), f.dblOrNull("lPer100"), words(f.opt("basis")),
                c.optJSONArray("days").objs(), c.optJSONObject("est") ?: JSONObject(), c.optJSONObject("actual"))
        }
    }
}

/** Live progress for the office: done, late, skipped per stop, and where people are (when sharing their location). */
data class TProgress(val day: Int, val now: String, val stops: Map<String, JSONObject>, val where: List<Triple<String, LatLng, String>>) {
    companion object {
        fun from(o: JSONObject?): TProgress? = o?.let { p ->
            TProgress(p.optInt("day"), p.str("now"), p.optJSONArray("stops").objs().associateBy { it.str("sid") },
                p.optJSONArray("where").objs().mapNotNull { w -> w.latLng()?.let { Triple(w.str("name"), it, w.str("at")) } })
        }
    }
}

/** A field trip: the record as the server keeps it ([raw]) and the parts the screens use. */
data class Itin(val raw: JSONObject, val canEdit: Boolean = true, val waiting: Int = 0) {
    val id: String = raw.str("id")
    val v: Int = raw.optInt("v")
    val title: String = raw.str("title")
    val status: String = raw.str("status").ifBlank { "planned" }
    val owner: String = raw.str("owner")
    val people: List<String> = raw.optJSONArray("people").strs()
    val viewers: List<String> = raw.optJSONArray("viewers").strs()
    val ck: String = raw.str("ck")
    val from: String = raw.str("from")
    val to: String = raw.str("to")
    val start: TPoint? = TPoint.from(raw.optJSONObject("start"))
    val end: TPoint? = TPoint.from(raw.optJSONObject("end"))
    val dayStart: Int = Hm.parse(raw.optJSONObject("hours")?.str("start")) ?: 450
    val dayEnd: Int = Hm.parse(raw.optJSONObject("hours")?.str("end")) ?: 1050
    val lunchAt: Int = Hm.parse(raw.optJSONObject("lunch")?.str("at")) ?: 750
    val lunchMin: Int = raw.optJSONObject("lunch")?.optInt("min", 45) ?: 45
    val lunchSkip: Boolean = raw.optJSONObject("lunch")?.optBoolean("skip") ?: false
    val vehicleId: String = raw.str("vehicleId")
    val passengers: Int = raw.optInt("passengers", people.size.coerceAtLeast(1))
    val stops: List<TStop> = raw.optJSONArray("stops").objs().map { TStop.from(it) }
    val days: List<TDay> = raw.optJSONArray("days").objs().map { TDay.from(it) }
    val unplanned: List<String> = raw.optJSONArray("unplanned").strs()
    val legs: Map<String, TLeg> = raw.optJSONObject("legs")?.let { o -> o.keys().asSequence().associateWith { TLeg.from(o.getJSONObject(it)) } } ?: emptyMap()
    val cost: TCost? = TCost.from(raw.optJSONObject("cost"))
    val packing: List<TPack> = raw.optJSONArray("packing").objs().filter { !it.optBoolean("removed") }.map { TPack(it.str("key"), words(it), it.optBoolean("done"), it.optBoolean("auto") || it.str("key").startsWith("auto:") || it.str("key").startsWith("std:")) }
    val extras: List<TExtra> = raw.optJSONArray("extras").objs().map { TExtra.from(it) }
    val shareUrl: String = raw.optJSONObject("share")?.str("url") ?: ""
    val warnings: List<TWarn> = raw.optJSONArray("warnings").objs().map { TWarn.from(it) }
    val summary: JSONObject? = raw.optJSONObject("summary")
    val updated: String = raw.str("updated")

    private val matrixKeys: List<String> = raw.optJSONObject("matrix")?.optJSONArray("keys").strs()
    private val matrixMin: JSONArray? = raw.optJSONObject("matrix")?.optJSONArray("min")
    private val matrixKm: JSONArray? = raw.optJSONObject("matrix")?.optJSONArray("km")

    fun stop(sid: String): TStop? = stops.firstOrNull { it.sid == sid }
    fun dayStops(i: Int): List<TStop> = days.getOrNull(i)?.stops?.mapNotNull { stop(it) } ?: emptyList()
    fun isOn(name: String) = name.isNotBlank() && (owner.equals(name, true) || people.any { it.equals(name, true) })

    /** Where a point key is: S start, E end, a stop's sid, T:<town>. */
    fun place(key: String): LatLng? = when {
        key == "S" -> start?.ll
        key == "E" -> (end?.takeIf { it.kind != "same" && it.ll != null } ?: start)?.ll
        key.startsWith("T:") -> days.firstOrNull { it.night?.key == key }?.night?.ll
        else -> stop(key)?.ll
    }
    fun placeName(key: String): String = when {
        key == "S" -> start?.name ?: ""
        key == "E" -> (end?.takeIf { it.kind != "same" && it.name.isNotBlank() } ?: start)?.name ?: ""
        key.startsWith("T:") -> key.removePrefix("T:")
        else -> stop(key)?.name ?: ""
    }

    /** The leg a→b: the trip engine's, else the matrix, else a straight-line estimate. */
    fun leg(a: String, b: String): TLeg? {
        legs["$a>$b"]?.let { return it }
        legs["$b>$a"]?.let { return it }
        val i = matrixKeys.indexOf(a); val j = matrixKeys.indexOf(b)
        if (i >= 0 && j >= 0) {
            val mn = matrixMin?.optJSONArray(i)?.optDouble(j)
            if (mn != null && !mn.isNaN()) {
                val km = matrixKm?.optJSONArray(i)?.optDouble(j)?.takeIf { !it.isNaN() } ?: 0.0
                return TLeg(km, mn.roundToInt(), (mn * 1.3).roundToInt(), "matrix", "", false, "", "", "~" + Hm.dur(mn.roundToInt()), "ok", "", emptyList())
            }
        }
        val pa = place(a) ?: return null; val pb = place(b) ?: return null
        return TLeg.guess(pa, pb)
    }

    /** Where day [i] starts and ends (point keys). */
    fun ends(i: Int): Pair<String, String> {
        // the server's keys, unless tonight's lodging was changed on this phone (then worked out the same way)
        val d = days.getOrNull(i)
        if (d != null && waiting == 0 && d.startKey.isNotBlank() && d.endKey.isNotBlank()) return d.startKey to d.endKey
        val a = if (i == 0) "S" else days.getOrNull(i - 1)?.night?.key ?: "S"
        val b = if (i == days.lastIndex) "E" else days.getOrNull(i)?.night?.key ?: "S"
        return a to b
    }
}

// --- the day's timeline: the contract's schedule rule, on the phone ----------------------------------------------

/** One line of the day: leave → drive → (wait) → stop → lunch → … → return / overnight. */
data class TRow(val kind: String, val at: Int, val until: Int, val sid: String = "", val from: String = "", val to: String = "", val leg: TLeg? = null, val warn: TWarn? = null)

data class DayPlan(val rows: List<TRow>, val warnings: List<TWarn>, val driveMin: Int, val km: Double, val endsAt: Int)

object Timeline {
    /**
     * Day [i] of [it], timed by the schedule rule. With [now] (minutes, today) what's left is re-timed from now: stops
     * already checked in keep their check-in time, and the next drive starts no earlier than now.
     */
    fun day(it: Itin, i: Int, now: Int? = null): DayPlan {
        val d = it.days[i]
        val (a, b) = it.ends(i)
        val rows = ArrayList<TRow>(); val warns = ArrayList<TWarn>()
        var t = it.dayStart; var here = a; var drive = 0; var km = 0.0; var lunched = it.lunchSkip
        rows += TRow("leave", t, t, from = a)
        fun lunch() { rows += TRow("lunch", t, t + it.lunchMin); t += it.lunchMin; lunched = true }
        fun same(x: String, y: String): Boolean = x == y || (it.place(x) != null && it.place(x) == it.place(y))
        for (sid in d.stops) {
            val s = it.stop(sid) ?: continue
            if (s.status == "skipped") { rows += TRow("skipped", t, t, sid); continue }
            val checkedIn = clock(s.checkinAt)
            if (now != null && checkedIn == null && t < now) t = now
            val l = if (same(here, sid)) null else it.leg(here, sid)
            val dm = l?.min ?: 0
            // (b) lunch before a drive, when it's within the hour before lunch and the drive would arrive more than an hour after it
            if (!lunched && checkedIn == null && t >= it.lunchAt - 60 && t < it.lunchAt && t + dm > it.lunchAt + 60) lunch()
            if (l != null) { rows += TRow("drive", t, t + dm, from = here, to = sid, leg = l); t += dm; drive += dm; km += l.km }
            var arrive = checkedIn ?: t
            // (c) on arrival at or after an hour past lunch, unless eating first would miss the meeting or the closing time
            if (!lunched && checkedIn == null && arrive >= it.lunchAt + 60) {
                val after = arrive + it.lunchMin
                val misses = (s.fixed != null && after > s.fixed + 5) || (s.fixed == null && s.window?.close != null && after + s.visitMin > s.window.close)
                if (!misses) { t = arrive; lunch(); arrive = t }
            }
            var warn: TWarn? = null
            if (checkedIn == null) {
                if (s.fixed != null) {
                    if (arrive < s.fixed) { rows += TRow("wait", arrive, s.fixed, sid); arrive = s.fixed }
                    else if (arrive > s.fixed + 5) warn = TWarn("FIXED_LATE", "difficult", t("Arrives ${Hm.text(arrive)}, after the set time ${Hm.text(s.fixed)}", "Inafika ${Hm.text(arrive)}, baada ya muda uliowekwa ${Hm.text(s.fixed)}"), sid)
                } else if (s.window != null) {
                    val o = s.window.open; val c = s.window.close
                    if (o != null && arrive < o) {
                        if (o - arrive > 20) warns += TWarn("BEFORE_OPEN", "info", t("Waits ${o - arrive}\u00A0min for ${s.name} to open at ${Hm.text(o)}", "Inasubiri dakika\u00A0${o - arrive} ${s.name} ifunguliwe saa ${Hm.text(o)}"), sid)
                        rows += TRow("wait", arrive, o, sid); arrive = o
                    }
                    if (c != null && arrive >= c) warn = TWarn("AFTER_CLOSE", "difficult", t("Arrives ${Hm.text(arrive)}, after ${closeWord(s.type)}", "Inafika ${Hm.text(arrive)}, baada ya ${closeWord(s.type)}"), sid)
                    else if (c != null && arrive + s.visitMin > c) warn = TWarn("AFTER_CLOSE", "caution", t("Visit runs until ${Hm.text(arrive + s.visitMin)}, after ${closeWord(s.type)} at ${Hm.text(c)}", "Ziara inaendelea hadi ${Hm.text(arrive + s.visitMin)}, baada ya ${closeWord(s.type)} saa ${Hm.text(c)}"), sid)
                }
            }
            val leave = arrive + s.visitMin
            rows += TRow("stop", arrive, leave, sid, warn = warn)
            warn?.let { w -> warns += w }
            t = leave; here = sid
            // (a) right after a stop, once it's lunch time
            if (!lunched && t >= it.lunchAt) lunch()
        }
        val back = if (d.stops.any { s -> it.stop(s)?.status != "skipped" } && !same(here, b)) it.leg(here, b) else null
        if (back != null) {
            if (!lunched && t >= it.lunchAt - 60 && t < it.lunchAt && t + back.min > it.lunchAt + 60) lunch()
            rows += TRow("drive", t, t + back.min, from = here, to = b, leg = back); t += back.min; drive += back.min; km += back.km
        }
        rows += TRow(if (b.startsWith("T:")) "overnight" else "return", t, t, to = b)
        if (t > it.dayEnd) warns += TWarn("OVER_HOURS", "caution", t("Day ends ${Hm.text(t)}, after ${Hm.text(it.dayEnd)}", "Siku inaisha ${Hm.text(t)}, baada ya ${Hm.text(it.dayEnd)}"))
        return DayPlan(rows, warns, drive, km, t)
    }

    /** "school closes" (the window's own words for a school, a clinic or any site). */
    fun closeWord(type: String): String { val ty = type.lowercase(); return when { "school" in ty -> t("the school closes", "shule kufungwa"); "health" in ty || "clinic" in ty -> t("the clinic closes", "kliniki kufungwa"); else -> t("the site closes", "eneo kufungwa") } }

    /** "09:41" from a check-in time (ISO UTC → Nairobi / the phone's zone). */
    fun clock(iso: String): Int? = if (iso.isBlank()) null else runCatching {
        val z = java.time.Instant.parse(iso).atZone(org.rafikioutreach.pin.ui.Dates.zone()); z.hour * 60 + z.minute
    }.getOrNull()

    /**
     * The warnings to show on a day: the local schedule ones (times change as soon as a stop is dragged), with the server's
     * one-tap fix when it had the same one, plus the server's road and weather warnings for the legs still in the day.
     */
    fun warnings(it: Itin, i: Int, plan: DayPlan): List<TWarn> {
        val srv = it.days[i].warnings
        val sched = setOf("AFTER_CLOSE", "FIXED_LATE", "OVER_HOURS", "BEFORE_OPEN")
        val local = plan.warnings.map { w -> srv.firstOrNull { s -> s.code == w.code && s.sid == w.sid }?.let { s -> w.copy(fix = s.fix, text = if (it.waiting == 0) s.text else w.text) } ?: w }
        val legsNow = plan.rows.filter { it.kind == "drive" }.map { it.from + ">" + it.to }.toSet()
        val road = srv.filter { w -> w.code !in sched && (w.leg.isBlank() || w.leg in legsNow || w.leg.split(">").reversed().joinToString(">") in legsNow) }
        return road + local
    }
}

// --- editing on the phone (instant), then sent ------------------------------------------------------------------

/** Changes to the record that re-time at once; each also gives the body to send ([ItinRepo.change]). */
object ItinEdit {
    private fun days(r: JSONObject): JSONArray = r.optJSONArray("days") ?: JSONArray()
    private fun with(it: Itin, f: (JSONObject) -> Unit): Itin { val r = it.raw.copyJson(); f(r); return it.copy(raw = r, waiting = it.waiting + 1) }
    private fun stopsOf(r: JSONObject, i: Int): MutableList<String> = r.optJSONArray("days")?.optJSONObject(i)?.optJSONArray("stops").strs().toMutableList()
    private fun setStops(r: JSONObject, i: Int, l: List<String>) { r.optJSONArray("days")?.optJSONObject(i)?.put("stops", JSONArray(l)) }
    private fun stopObj(r: JSONObject, sid: String): JSONObject? = r.optJSONArray("stops").objs().firstOrNull { it.str("sid") == sid }

    /** The "stops" body for the server: each day's sids, the unplanned ones, and the stops changed here. */
    fun stopsBody(it: Itin, changed: Collection<String> = emptyList()): JSONObject = JSONObject().put("action", "stops")
        .put("days", JSONArray(it.days.map { d -> JSONObject().put("stops", JSONArray(d.stops)) })).put("unplanned", JSONArray(it.unplanned))
        .apply { if (changed.isNotEmpty()) put("stops", JSONArray(changed.mapNotNull { sid -> stopObj(it.raw, sid)?.let { o -> JSONObject().put("sid", sid).put("visitMin", o.optInt("visitMin")).put("fixed", o.opt("fixed") ?: JSONObject.NULL).put("locked", o.optBoolean("locked")).put("note", o.str("note")) } })) }

    fun move(it: Itin, day: Int, from: Int, to: Int): Itin = with(it) { r ->
        val l = stopsOf(r, day); if (from !in l.indices) return@with
        val s = l.removeAt(from); l.add(to.coerceIn(0, l.size), s); setStops(r, day, l)
    }
    fun toDay(it: Itin, day: Int, sid: String, target: Int): Itin = with(it) { r ->
        if (target == day || target !in 0 until days(r).length()) return@with
        setStops(r, day, stopsOf(r, day) - sid); setStops(r, target, stopsOf(r, target) + sid)
        stopObj(r, sid)?.put("day", target)
    }
    fun remove(it: Itin, day: Int, sid: String): Itin = with(it) { r -> setStops(r, day, stopsOf(r, day) - sid) }
    /** A stop's time locked (a meeting) at [at], or unlocked. */
    fun lock(it: Itin, sid: String, at: Int?): Itin = with(it) { r -> stopObj(r, sid)?.apply { put("fixed", at?.let { Hm.text(it) } ?: JSONObject.NULL); put("locked", at != null) } }
    fun visitMin(it: Itin, sid: String, min: Int): Itin = with(it) { r -> stopObj(r, sid)?.put("visitMin", min.coerceIn(5, 720)) }
    fun swapDays(it: Itin, a: Int, b: Int): Itin = with(it) { r -> val sa = stopsOf(r, a); val sb = stopsOf(r, b); setStops(r, a, sb); setStops(r, b, sa) }
    fun mark(it: Itin, sid: String, status: String, at: String = "", by: String = ""): Itin = with(it) { r ->
        stopObj(r, sid)?.apply { put("status", status); if (at.isNotBlank()) put("checkin", JSONObject().put("at", at).put("by", by)) }
    }
    fun packTick(it: Itin, key: String, done: Boolean): Itin = with(it) { r -> r.optJSONArray("packing").objs().firstOrNull { p -> p.str("key") == key }?.put("done", done) }
    fun packAdd(it: Itin, text: String): Itin = with(it) { r -> (r.optJSONArray("packing") ?: JSONArray().also { a -> r.put("packing", a) }).put(JSONObject().put("key", "u:" + System.currentTimeMillis()).put("auto", false).put("en", text).put("sw", text)) }
    fun packRemove(it: Itin, key: String): Itin = with(it) { r -> r.optJSONArray("packing").objs().firstOrNull { p -> p.str("key") == key }?.put("removed", true) }
    fun extras(it: Itin, list: List<TExtra>): Itin = with(it) { r -> r.put("extras", JSONArray(list.map { e -> e.json() })) }
    fun lodging(it: Itin, day: Int, n: TNight?): Itin = with(it) { r ->
        r.optJSONArray("days")?.optJSONObject(day)?.put("overnight", n?.let { x -> JSONObject().put("town", x.town).put("suggested", false).apply { x.ll?.let { put("lat", it.lat); put("lng", it.lng) } }
            .put("lodging", JSONObject().put("name", x.lodging).put("phone", x.phone).put("kes", x.kes).put("note", x.note)) } ?: JSONObject.NULL)
    }

    /** Running late: the stops of [day] that no longer fit before the end of the working day (never the very next one). */
    fun overflow(it: Itin, day: Int, now: Int): List<String> {
        val d = it.days.getOrNull(day) ?: return emptyList()
        val open = d.stops.filter { s -> it.stop(s)?.open == true }
        if (open.size < 2) return emptyList()
        val keep = ArrayList<String>(); val out = ArrayList<String>()
        for (k in open.indices) {
            val trial = with(it) { r -> setStops(r, day, d.stops.filter { s -> s !in open || s in keep || s == open[k] }) }
            if (k == 0 || Timeline.day(trial, day, now).endsAt <= it.dayEnd) keep += open[k] else out += open.subList(k, open.size)
            if (out.isNotEmpty()) break
        }
        return out
    }
    /** "Move the rest?": push them to the next day (the first stops there), or drop them (skipped). */
    fun push(it: Itin, day: Int, sids: List<String>): Itin = with(it) { r ->
        if (sids.isEmpty()) return@with
        setStops(r, day, stopsOf(r, day) - sids.toSet())
        if (day + 1 < days(r).length()) setStops(r, day + 1, sids + stopsOf(r, day + 1))
        else (r.optJSONArray("unplanned") ?: JSONArray().also { a -> r.put("unplanned", a) }).let { a -> sids.forEach { s -> a.put(s) } }
    }
    fun drop(it: Itin, sids: List<String>): Itin = with(it) { r -> sids.forEach { s -> stopObj(r, s)?.put("status", "skipped") } }
}

// --- on the day ------------------------------------------------------------------------------------------------------

object OnTheDay {
    fun dayIndex(it: Itin, today: String): Int = it.days.indexOfFirst { it.date == today }
    /** The next stop today (sid), or null. */
    fun next(it: Itin, today: String): String? = dayIndex(it, today).takeIf { it >= 0 }?.let { d -> it.days[d].stops.firstOrNull { s -> it.stop(s)?.open == true } }
    /** How many minutes behind the day's plan the next stop is. */
    fun lateBy(it: Itin, today: String, now: Int): Int {
        val d = dayIndex(it, today); val sid = next(it, today) ?: return 0
        val planned = Timeline.day(it, d).rows.firstOrNull { r -> r.kind == "stop" && r.sid == sid }?.at ?: return 0
        val live = Timeline.day(it, d, now).rows.firstOrNull { r -> r.kind == "stop" && r.sid == sid }?.at ?: return 0
        return maxOf(0, live - planned)
    }
    fun counts(it: Itin): Triple<Int, Int, Int> = Triple(it.stops.count { it.status == "done" }, it.stops.count { it.status == "late" }, it.stops.count { it.status == "skipped" })
}

// --- vehicles and fuel -----------------------------------------------------------------------------------------------

data class Vehicle(val raw: JSONObject) {
    val vid = raw.str("vid"); val name = raw.str("name").ifBlank { raw.str("plate") }; val plate = raw.str("plate"); val type = raw.str("type").ifBlank { "saloon" }
    val cc = raw.optInt("cc"); val fuel = raw.str("fuel").ifBlank { "petrol" }; val lPer100 = raw.dblOrNull("lPer100"); val owner = raw.str("owner"); val seats = raw.optInt("seats")
    val model = raw.optJSONObject("model")?.dblOrNull("lPer100"); val band = raw.optJSONObject("model")?.str("band") ?: ""
    val realL = raw.optJSONObject("real")?.dblOrNull("lPer100"); val realLogs = raw.optJSONObject("real")?.optInt("logs") ?: 0
    val use = raw.optJSONObject("use")?.dblOrNull("lPer100"); val useSrc = raw.optJSONObject("use")?.str("src") ?: ""
    fun typeName() = typeName(type)
    fun fuelName() = fuelName(fuel)
    /** How a vehicle is shown everywhere: "Hilux · KDA 123X · Pickup" (never its id). */
    fun label(): String = vehicleLabel(name, plate, type)
    companion object {
        val TYPES = listOf("saloon", "suv", "pickup", "4x4", "van", "minibus", "motorbike")
        fun typeName(k: String) = when (k) {
            "saloon" -> t("Saloon", "Saluni"); "suv" -> "SUV"; "pickup" -> t("Pickup", "Gari la mizigo"); "4x4" -> "4×4"; "van" -> t("Van", "Vani")
            "minibus" -> t("Minibus", "Basi dogo"); "motorbike" -> t("Motorbike", "Pikipiki"); else -> k
        }
        fun fuelName(k: String) = if (k == "diesel") t("diesel", "dizeli") else t("petrol", "petroli")
        /** The picker's options: every vehicle by its label, plus the trip's own vehicle when the list doesn't have it
         *  (not loaded yet, or no signal) — labelled from the trip's costs, never by its id. */
        fun options(list: List<Vehicle>, current: String, tripVehicle: JSONObject?): List<Pair<String, String>> =
            list.map { it.vid to it.label() } + (if (current.isNotBlank() && list.none { it.vid == current })
                listOf(current to (tripVehicle?.takeIf { it.str("vid") == current || it.str("vid").isBlank() }?.let { o -> vehicleLabel(o.str("name"), o.str("plate"), o.str("type")) }?.takeIf { it.isNotBlank() } ?: t("The trip's vehicle", "Gari la safari")))
            else emptyList())
    }
}

data class FuelLog(val raw: JSONObject) {
    val lid = raw.str("lid"); val vid = raw.str("vid"); val at = raw.str("at"); val by = raw.str("by"); val litres = raw.optDouble("litres", 0.0)
    val perL = raw.optDouble("kesPerL", 0.0); val total = raw.longOrNull("totalKes") ?: (litres * perL).roundToLong(); val odo = raw.longOrNull("odometer")
    val town = raw.str("town"); val receipt = raw.str("receipt"); val local = raw.str("localReceipt"); val waiting = raw.optBoolean("waiting")
}

/** Official fuel prices of a country: source, period and towns (and admin overrides). */
data class FuelCountry(val raw: JSONObject) {
    val ck = raw.str("ck"); val source = raw.str("source"); val sourceName = raw.str("sourceName"); val url = raw.str("url")
    val from = raw.str("from"); val to = raw.str("to"); val fetchedAt = raw.str("fetchedAt"); val kind = raw.str("kind").ifBlank { "none" }; val currency = raw.str("currency").ifBlank { "KES" }
    val towns: List<Pair<String, JSONObject>> = raw.optJSONObject("towns")?.let { o -> o.keys().asSequence().map { it to o.getJSONObject(it) }.toList() } ?: emptyList()
    val overrides: List<Pair<String, JSONObject>> = raw.optJSONObject("overrides")?.let { o -> o.keys().asSequence().map { it to o.getJSONObject(it) }.toList() } ?: emptyList()
    val statusOk = raw.optJSONObject("status")?.optBoolean("ok", true) ?: true
    val statusReason = raw.optJSONObject("status")?.str("reason") ?: ""
}

/** "Hilux · KDA 123X · Pickup": the name, the plate when it isn't already in the name, and the type. */
fun vehicleLabel(name: String, plate: String, type: String): String =
    listOf(name, plate.takeIf { p -> p.isNotBlank() && !name.contains(p, ignoreCase = true) } ?: "", if (type.isNotBlank()) Vehicle.typeName(type) else "").filter { it.isNotBlank() }.joinToString(" · ")

/** "189.84", "4,250" (a price per litre: 2 decimals when it has them). */
fun perL(v: Double): String = if (v >= 1000) "%,.0f".format(java.util.Locale.US, v) else "%,.2f".format(java.util.Locale.US, v)

// --- group trips -----------------------------------------------------------------------------------------------------

data class GroupTrip(val raw: JSONObject) {
    val id = raw.str("id"); val v = raw.optInt("v"); val name = raw.str("name"); val from = raw.str("from"); val to = raw.str("to")
    val country = raw.str("country"); val destination = raw.str("destination"); val summary = raw.str("summary"); val public = raw.optBoolean("public")
    val leaders = raw.optJSONArray("leaders").strs(); val contact = raw.str("contact")
    val participants = raw.optJSONArray("participants").objs()
    val items = raw.optJSONArray("items").objs().sortedWith(compareBy({ it.str("date") }, { it.str("start") }))
    val costs = raw.optJSONArray("costs").objs(); val payments = raw.optJSONArray("payments").objs()
    val packing = raw.str("packing"); val expect = raw.str("expect"); val emergency = raw.optJSONArray("emergency").objs()
    val totals = raw.optJSONObject("totals"); val shareUrl = raw.optJSONObject("share")?.str("url") ?: ""
    /** The public (shared / donor) shape groups items by day: flatten either shape. */
    val dayItems: List<Pair<String, List<JSONObject>>> = raw.optJSONArray("days")?.objs()?.map { d -> d.str("date") to d.optJSONArray("items").objs().map { i -> i.copyJson().put("date", d.str("date")) } }
        ?: items.groupBy { it.str("date") }.toList().sortedBy { it.first }
    val map: List<Pair<String, LatLng>> = raw.optJSONArray("map")?.objs()?.mapNotNull { m -> m.latLng()?.let { m.str("name") to it } }
        ?: items.mapNotNull { i -> i.optJSONObject("location")?.let { l -> l.latLng()?.let { l.str("name").ifBlank { i.str("title") } to it } } }
    companion object {
        val KINDS = listOf("flight", "transfer", "lodging", "project", "school", "safari", "free", "meal", "other")
        fun kindName(k: String) = when (k) {
            "flight" -> t("Flight", "Ndege"); "transfer" -> t("Pick-up / transfer", "Kuchukuliwa / usafiri"); "lodging" -> t("Lodging", "Malazi")
            "project" -> t("Drilling or project day", "Siku ya uchimbaji au mradi"); "school" -> t("School visit", "Ziara ya shule"); "safari" -> "Safari"
            "free" -> t("Free time", "Muda huru"); "meal" -> t("Meal", "Mlo"); else -> t("Other", "Nyingine")
        }
        fun kindIcon(k: String) = when (k) { "flight" -> "upload"; "transfer" -> "nav"; "lodging" -> "house"; "project" -> "drop"; "school" -> "school"; "safari" -> "sun"; "free" -> "spark"; "meal" -> "leaf"; else -> "cal" }
        fun kindHue(k: String) = when (k) { "flight" -> "blue"; "transfer" -> "teal"; "lodging" -> "purple"; "project" -> "blue"; "school" -> "purple"; "safari" -> "amber"; "free" -> "green"; "meal" -> "coral"; else -> "slate" }
        const val DEFAULT_PACKING = "Passport (valid 6 months after you travel) and visa\nYellow fever certificate\nSun hat, sunscreen and sunglasses\nWork clothes and closed shoes for drilling days\nA warm layer for cool evenings\nWater bottle and insect repellent\nPersonal medicines"
        const val DEFAULT_EXPECT = "Vaccinations: see a travel clinic 6–8 weeks before you go (yellow fever, hepatitis A, typhoid).\nMalaria: ask about tablets; advice at https://www.cdc.gov/malaria/travelers/\nDrilling days are long, dusty and hot: wear clothes you don't mind getting muddy.\nSafari: neutral colours, binoculars, and stay in the vehicle unless the guide says otherwise.\nPhotos: always ask before taking pictures of people, especially children."
    }
}

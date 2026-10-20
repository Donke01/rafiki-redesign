package org.rafikioutreach.pin.data

import org.json.JSONArray
import org.json.JSONObject
import org.rafikioutreach.pin.t
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One RAFIKI project, as /api/pin lists it. */
data class Project(
    val id: String,
    val title: String,
    val country: String,
    val ck: String,
    val area: String,
    val type: String,
    val status: String,
    val year: Int?,
    val gps: LatLng?,
    val photo: String?,
    val pump: String,
) {
    val sub: String get() = listOf(area, country, statusName(status)).filter { it.isNotBlank() }.joinToString(" · ")

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("country", country); put("ck", ck); put("area", area)
        put("type", type); put("status", status); year?.let { put("year", it) }
        gps?.let { put("gps", JSONArray().put(it.lat).put(it.lng)) }
        photo?.let { put("photo", it) }; put("pump", pump)
    }

    companion object {
        fun from(o: JSONObject) = Project(
            id = o.optString("id"),
            title = o.optString("title"),
            country = o.optString("country"),
            ck = o.optString("ck"),
            area = o.optString("area"),
            type = o.optString("type"),
            status = o.optString("status"),
            year = if (o.has("year") && !o.isNull("year")) o.optInt("year") else null,
            gps = o.optJSONArray("gps")?.let { if (it.length() == 2) LatLng(it.getDouble(0), it.getDouble(1)) else null },
            photo = o.optString("photo").takeIf { it.isNotBlank() && it != "null" },
            pump = o.optString("pump"),
        )
    }
}

data class LatLng(val lat: Double, val lng: Double) {
    fun text() = "%.6f, %.6f".format(java.util.Locale.US, lat, lng)
}

/** An open repair (needs repair / not working), from the last visit. */
data class Repair(
    val state: String, val by: String, val at: String, val note: String, val urgency: String, val problems: List<String>,
    val assignee: String = "", val step: String = "reported", val due: String = "", val budget: Long? = null, val parts: String = "", val cost: Long? = null, val title: String = "",
    val stepAt: Map<String, String> = emptyMap(),
) {
    companion object {
        fun from(o: JSONObject) = Repair(
            o.optString("state"), o.optString("by"), o.optString("at"), o.optString("note"), o.optString("urgency"),
            o.optJSONArray("problems")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            o.optString("assignee"), o.optString("step").ifBlank { "reported" }, o.optString("due"),
            if (o.has("budget") && !o.isNull("budget")) o.optLong("budget") else null, o.optString("parts"),
            if (o.has("cost") && !o.isNull("cost")) o.optLong("cost") else null, o.optString("t"),
            o.optJSONObject("stepAt")?.let { j -> j.keys().asSequence().associateWith { j.optString(it) } } ?: emptyMap(),
        )
    }
}

data class LastVisit(val at: String, val by: String, val state: String)

data class Assignment(val aid: String, val id: String, val title: String, val date: String, val by: String, val note: String)

/** Everything the server sends when the app opens. */
data class Snapshot(
    val who: String,
    val admin: Boolean,
    val projects: List<Project>,
    val repairs: Map<String, Repair>,
    val visits: Map<String, LastVisit>,
    val jobs: List<String>,
    val assigned: List<Assignment>,
    val confirmed: Set<String>,
    val reviewOpen: Int,
) {
    companion object {
        fun from(o: JSONObject): Snapshot {
            val ps = o.optJSONArray("projects") ?: JSONArray()
            val rs = o.optJSONObject("repairs") ?: JSONObject()
            val vs = o.optJSONObject("visits") ?: JSONObject()
            val js = o.optJSONArray("jobs") ?: JSONArray()
            val asg = o.optJSONArray("assigned") ?: JSONArray()
            val conf = o.optJSONObject("confirmed") ?: JSONObject()
            return Snapshot(
                who = o.optString("who"),
                admin = o.optBoolean("admin"),
                projects = (0 until ps.length()).map { Project.from(ps.getJSONObject(it)) },
                repairs = rs.keys().asSequence().associateWith { Repair.from(rs.getJSONObject(it)) },
                visits = vs.keys().asSequence().associateWith { k -> vs.getJSONObject(k).let { LastVisit(it.optString("at"), it.optString("by"), it.optString("state")) } },
                jobs = (0 until js.length()).map { js.optString(it) },
                assigned = (0 until asg.length()).map { i -> asg.getJSONObject(i).let { Assignment(it.optString("aid"), it.optString("id"), it.optString("t"), it.optString("date"), it.optString("by"), it.optString("note")) } },
                confirmed = conf.keys().asSequence().toSet(),
                reviewOpen = o.optInt("reviewOpen"),
            )
        }
    }
}

/** A note on a project in monday.com (the History tab). */
data class Note(val id: String, val text: String, val at: String, val by: String, val files: List<NoteFile>)
data class NoteFile(val name: String, val ext: String, val url: String) {
    val isImage get() = ext in setOf("jpg", "jpeg", "png", "webp", "gif", "heic")
}

object Geo {
    /** Metres between two points. */
    fun metres(a: LatLng, b: LatLng): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(b.lat - a.lat); val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }

    /** "910 m", "12.4 km", or in miles. Exact to the metre under 10 km, like the web app. */
    fun dist(m: Double, miles: Boolean): String {
        if (miles) {
            val mi = m / 1609.344
            return when {
                mi < 1 -> "%,d ft".format(java.util.Locale.US, (m * 3.28084).roundToInt())
                mi < 10 -> "%.2f mi".format(java.util.Locale.US, mi)
                mi < 100 -> "%.1f mi".format(java.util.Locale.US, mi)
                else -> "${mi.roundToInt()} mi"
            }
        }
        return when {
            m < 10000 -> "%,d m".format(java.util.Locale.US, m.roundToInt())
            m < 100000 -> "%.1f km".format(java.util.Locale.US, m / 1000)
            else -> "${(m / 1000).roundToInt()} km"
        }
    }

    private val DIRS = listOf("north" to "kaskazini", "north-east" to "kaskazini-mashariki", "east" to "mashariki", "south-east" to "kusini-mashariki",
        "south" to "kusini", "south-west" to "kusini-magharibi", "west" to "magharibi", "north-west" to "kaskazini-magharibi")
    fun heading(from: LatLng, to: LatLng): String {
        val y = sin(Math.toRadians(to.lng - from.lng)) * cos(Math.toRadians(to.lat))
        val x = cos(Math.toRadians(from.lat)) * sin(Math.toRadians(to.lat)) - sin(Math.toRadians(from.lat)) * cos(Math.toRadians(to.lat)) * cos(Math.toRadians(to.lng - from.lng))
        val deg = (Math.toDegrees(atan2(y, x)) + 360) % 360
        return DIRS[((deg + 22.5) / 45).toInt() % 8].let { t(it.first, it.second) }
    }

    /** "0.561368, 35.065480" or a Google/Apple Maps link with coordinates in it. */
    fun parse(text: String): LatLng? {
        val t = text.trim()
        val pats = listOf(
            Regex("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)"),
            Regex("[?&](?:q|ll|query|daddr|destination)=(-?\\d+\\.\\d+)(?:,|%2C)\\s*(-?\\d+\\.\\d+)"),
            Regex("^(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)$"),
            Regex("(-?\\d+\\.\\d{3,})\\s*[, ]\\s*(-?\\d+\\.\\d{3,})"),
        )
        for (p in pats) {
            val m = p.find(t) ?: continue
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lng = m.groupValues[2].toDoubleOrNull() ?: continue
            if (lat in -90.0..90.0 && lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0)) return LatLng(lat, lng)
        }
        return null
    }
}

val COUNTRIES = linkedMapOf("ke" to "Kenya", "tz" to "Tanzania", "ug" to "Uganda", "mw" to "Malawi")
fun statusName(s: String) = when (s) { "Completed" -> t("Completed", "Imekamilika"); "In Progress" -> t("In progress", "Inaendelea"); "Due Diligence" -> t("Due Diligence", "Inachunguzwa"); else -> s }
val STATES = listOf("working", "repair", "notworking")
fun stateName(k: String) = when (k) { "working" -> t("Working well", "Inafanya kazi vizuri"); "repair" -> t("Needs repair", "Inahitaji matengenezo"); "notworking" -> t("Not working", "Haifanyi kazi"); else -> k }
val URGENCY = listOf("urgent", "month", "watch")
fun urgencyName(k: String) = when (k) { "urgent" -> t("Urgent", "Haraka"); "month" -> t("This month", "Mwezi huu"); "watch" -> t("Keep an eye on it", "Fuatilia tu"); else -> k }
const val DUE_DAYS = 180

fun reasonText(reason: String?): String = when (reason) {
    "pin" -> t("That PIN isn’t right. Please check it with your team.", "PIN hiyo si sahihi. Tafadhali hakikisha na timu yako.")
    "locked" -> t("Too many wrong PINs from this connection. Please try again in an hour.", "PIN nyingi zisizo sahihi kutoka mtandao huu. Jaribu tena baada ya saa moja.")
    "not-set-up" -> t("The pin app isn’t switched on yet: PINs still need to be set up.", "Programu bado haijawashwa: PIN bado hazijawekwa.")
    "offline" -> t("No signal. It will be sent when you’re back online.", "Hakuna mtandao. Itatumwa ukipata mtandao tena.")
    "rate" -> t("Too many changes from this phone. Please wait a little and try again.", "Mabadiliko mengi kutoka simu hii. Subiri kidogo kisha ujaribu tena.")
    "project" -> t("That project couldn’t be found.", "Mradi huo haukupatikana.")
    "no-location" -> t("Save a location first.", "Hifadhi mahali kwanza.")
    else -> t("Something went wrong on the server. Please try again.", "Kuna hitilafu kwenye seva. Tafadhali jaribu tena.")
}

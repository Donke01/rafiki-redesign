package org.rafikioutreach.pin.ui

import org.rafikioutreach.pin.Lang
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Dates on screen, the same everywhere and the same as the web app, in the app's language (not the phone's):
 *  - date only: "Thu 24 Oct" ([weekday]) or "24 Oct" ([day])
 *  - date and time: "24 Oct, 18:34" ([dateTime])
 *  - the year is added ("24 Oct 2025") only when it isn't this year
 *  - due dates: "Today", "Tomorrow", "In 2 days", "3 days late" ([due])
 * Months are always 3 letters (never "Sept"); Kiswahili uses Jan Feb Mac Apr Mei Jun Jul Ago Sep Okt Nov Des and
 * Jtt Jnn Jtn Alh Iju Jmo Jpl. ISO dates are only for storage and the server.
 *
 * Every function also takes the ISO text as it comes from the server ("2026-10-20" or "2026-10-20T08:00:00Z") and gives
 * the text back unchanged if it can't be read.
 */
object Dates {
    private val MON_EN = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val MON_SW = listOf("Jan", "Feb", "Mac", "Apr", "Mei", "Jun", "Jul", "Ago", "Sep", "Okt", "Nov", "Des")
    private val DAY_EN = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val DAY_SW = listOf("Jtt", "Jnn", "Jtn", "Alh", "Iju", "Jmo", "Jpl")

    /** The phone's time zone and today's date; tests may set their own. */
    var zone: () -> ZoneId = { ZoneId.systemDefault() }
    var today: () -> LocalDate = { LocalDate.now(zone()) }

    /** "Oct" / "Okt". */
    fun month(m: Int): String = (if (Lang.sw) MON_SW else MON_EN)[(m - 1).coerceIn(0, 11)]

    /** "24 Oct", or "24 Oct 2025" when it isn't this year ([year] true always adds it, false never). */
    fun day(d: LocalDate, year: Boolean? = null): String =
        "${d.dayOfMonth} ${month(d.monthValue)}" + if (year ?: (d.year != today().year)) " ${d.year}" else ""

    /** "Thu 24 Oct" this year, "24 Oct 2025" another year. */
    fun weekday(d: LocalDate): String =
        if (d.year != today().year) day(d) else (if (Lang.sw) DAY_SW else DAY_EN)[d.dayOfWeek.value - 1] + " " + day(d)

    /** "18:34". */
    fun time(at: Instant): String = at.atZone(zone()).let { "%02d:%02d".format(it.hour, it.minute) }

    /** "24 Oct, 18:34" (with the year when it isn't this year). */
    fun dateTime(at: Instant): String = at.atZone(zone()).let { day(it.toLocalDate()) + ", " + "%02d:%02d".format(it.hour, it.minute) }

    /** Just the time today ("10:42"), otherwise the date and time ("21 Oct, 10:42"). */
    fun timeOrDateTime(at: Instant): String = if (at.atZone(zone()).toLocalDate() == today()) time(at) else dateTime(at)

    /** "Today", "Tomorrow", "In 2 days" (up to a week ahead, then "Thu 24 Oct"), "1 day late", "3 days late". */
    fun due(d: LocalDate): String {
        val n = ChronoUnit.DAYS.between(today(), d)
        return when {
            n == 0L -> if (Lang.sw) "Leo" else "Today"
            n == 1L -> if (Lang.sw) "Kesho" else "Tomorrow"
            n in 2..6 -> if (Lang.sw) "Baada ya siku $n" else "In $n days"
            n > 6 -> weekday(d)
            else -> if (Lang.sw) "Imechelewa siku ${-n}" else "${-n} ${if (n == -1L) "day" else "days"} late"
        }
    }

    // --- the same, from the ISO text the server sends -------------------------------------------------
    /** A date ("2026-10-20") or a moment ("2026-10-20T08:00:00Z"), as the date on this phone. */
    fun parse(iso: String): LocalDate? = iso.trim().let { s ->
        runCatching { if (s.length == 10) LocalDate.parse(s) else Instant.parse(s).atZone(zone()).toLocalDate() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(s).withZoneSameInstant(zone()).toLocalDate() }.getOrNull()
    }
    private fun instant(iso: String): Instant? = runCatching { Instant.parse(iso.trim()) }.getOrNull() ?: runCatching { ZonedDateTime.parse(iso.trim()).toInstant() }.getOrNull()

    fun day(iso: String, year: Boolean? = null): String = parse(iso)?.let { day(it, year) } ?: iso
    fun weekday(iso: String): String = parse(iso)?.let { weekday(it) } ?: iso
    fun dateTime(iso: String): String = instant(iso)?.let { dateTime(it) } ?: parse(iso)?.let { day(it) } ?: iso
    fun due(iso: String): String = parse(iso)?.let { due(it) } ?: iso
}

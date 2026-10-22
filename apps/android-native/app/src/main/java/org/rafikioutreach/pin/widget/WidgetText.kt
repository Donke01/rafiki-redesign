package org.rafikioutreach.pin.widget

import org.rafikioutreach.pin.data.DUE_DAYS
import org.rafikioutreach.pin.data.Snapshot
import org.rafikioutreach.pin.t
import java.time.Instant

/** What the home-screen widget counts: visits due, urgent repairs, and visits or repair jobs for this person. */
data class WidgetCounts(val due: Int, val urgent: Int, val mine: Int)

/** The widget's words, worked out from the list saved on the phone (no extra network). */
object WidgetText {
    /** Counts for [s], in the person's home country ([country], "" = all), like the Projects filters. Null when signed out. */
    fun counts(s: Snapshot?, country: String, now: Long = System.currentTimeMillis()): WidgetCounts? {
        s ?: return null
        val here = s.projects.filter { country.isBlank() || it.ck == country }
        val ids = here.map { it.id }.toSet()
        fun age(id: String): Double = s.visits[id]?.let { v -> runCatching { (now - Instant.parse(v.at).toEpochMilli()) / 864e5 }.getOrNull() } ?: 1e6
        val due = here.count { it.gps != null && age(it.id) >= DUE_DAYS }
        val urgent = s.repairs.count { (id, r) -> r.urgency == "urgent" && (country.isBlank() || id in ids) }
        val mine = (s.assigned.map { "a:" + it.aid } + s.jobs.map { "j:$it" }).toSet().size
        return WidgetCounts(due, urgent, mine)
    }

    /** "3 visits due · 1 urgent repair · 2 assigned to you" (only the parts that aren't zero). */
    fun line(c: WidgetCounts?): String {
        c ?: return t("Open RAFIKI Pin to sign in", "Fungua RAFIKI Pin uingie")
        val parts = listOfNotNull(
            c.due.takeIf { it > 0 }?.let { t(if (it == 1) "1 visit due" else "$it visits due", if (it == 1) "Ziara 1 inasubiri" else "Ziara $it zinasubiri") },
            c.urgent.takeIf { it > 0 }?.let { t(if (it == 1) "1 urgent repair" else "$it urgent repairs", if (it == 1) "Tengenezo 1 la haraka" else "Matengenezo $it ya haraka") },
            c.mine.takeIf { it > 0 }?.let { t("$it assigned to you", "$it umepewa") },
        )
        return if (parts.isEmpty()) t("All caught up ✓", "Umemaliza yote ✓") else parts.joinToString(" · ")
    }

    /** The small widget: "3 due · 1 urgent · 2 yours". */
    fun short(c: WidgetCounts?): String {
        c ?: return t("Open to sign in", "Fungua uingie")
        val parts = listOfNotNull(
            c.due.takeIf { it > 0 }?.let { t("$it due", "$it zinasubiri") },
            c.urgent.takeIf { it > 0 }?.let { t("$it urgent", "$it haraka") },
            c.mine.takeIf { it > 0 }?.let { t("$it yours", "$it zako") },
        )
        return if (parts.isEmpty()) t("All caught up ✓", "Umemaliza yote ✓") else parts.joinToString(" · ")
    }

    // short enough for a third of the widget, so nothing ends in "…"
    fun dueLabel(n: Int) = if (n == 1) t("Visit due", "Kutembelea") else t("Visits due", "Kutembelea")
    fun urgentLabel(n: Int) = if (n == 1) t("Urgent repair", "La haraka") else t("Urgent repairs", "Ya haraka")
    fun mineLabel() = t("For you", "Zako")
    fun nearLabel(small: Boolean) = if (small) t("Nearby", "Karibu") else t("Visit nearby", "Tembelea karibu")
    fun pinLabel(small: Boolean) = if (small) t("Pin", "Weka") else t("Pin a location", "Weka mahali")
}

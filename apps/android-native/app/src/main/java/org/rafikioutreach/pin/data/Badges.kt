package org.rafikioutreach.pin.data

import android.content.Context
import org.json.JSONObject
import org.rafikioutreach.pin.t
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Badges (the stamps on Me): earned from real work in the team's activity feed, the same rules as the web app.
 * [color] is one of b (blue), g (green), c (coral), y (amber), p (purple).
 */
data class BadgeDef(
    val k: String, val color: String, val en: String, val sw: String,
    val howEn: String, val howSw: String, val unitEn: String, val unitSw: String, val goal: Int,
) {
    val title: String get() = t(en, sw)
    val how: String get() = t(howEn, howSw)
}

data class BadgeState(val def: BadgeDef, val n: Int) {
    val got: Boolean get() = n >= def.goal
    /** "7/10 visits" (or "Pin a location" for the first-time ones). */
    val progress: String get() = when {
        got -> t("Earned", "Umepata")
        def.goal == 1 -> def.how
        else -> t("${n.coerceAtMost(def.goal)}/${def.goal} ${def.unitEn}", "${def.unitSw} ${n.coerceAtMost(def.goal)}/${def.goal}")
    }
    val fraction: Float get() = (n.toFloat() / def.goal).coerceIn(0f, 1f)
}

object Badges {
    val ALL = listOf(
        BadgeDef("pin1", "b", "First drop", "Tone la kwanza", "Pin a location", "Weka mahali", "pins", "pini", 1),
        BadgeDef("visit1", "g", "First visit", "Ziara ya kwanza", "Report a visit", "Ripoti ziara", "visits", "ziara", 1),
        BadgeDef("fixed1", "c", "Fixer", "Mrekebishaji", "Mark a repair fixed", "Weka matengenezo kuwa yamekamilika", "repairs", "matengenezo", 1),
        BadgeDef("new1", "y", "Explorer", "Mvumbuzi", "Add a new site", "Ongeza eneo jipya", "sites", "maeneo", 1),
        BadgeDef("pin10", "p", "Mapper", "Mchora ramani", "Pin 10 locations", "Weka mahali 10", "pins", "pini", 10),
        BadgeDef("visit10", "g", "Regular", "Mzoefu", "Report 10 visits", "Ripoti ziara 10", "visits", "ziara", 10),
        BadgeDef("month10", "b", "Busy month", "Mwezi wa kazi", "10 visits this month", "Ziara 10 mwezi huu", "visits", "ziara", 10),
        BadgeDef("streak5", "y", "On a roll", "Mfululizo", "Work 5 days in a row", "Fanya kazi siku 5 mfululizo", "days", "siku", 5),
        BadgeDef("countries4", "p", "Four countries", "Nchi nne", "Visit sites in all 4 countries", "Tembelea maeneo katika nchi zote 4", "countries", "nchi", 4),
        BadgeDef("water3", "b", "Water tester", "Mpimaji wa maji", "Test the water on 3 visits", "Pima maji katika ziara 3", "tests", "vipimo", 3),
        BadgeDef("story3", "c", "Story collector", "Mkusanyaji wa hadithi", "Collect 3 story moments", "Kusanya hadithi 3", "stories", "hadithi", 3),
        BadgeDef("pin100", "g", "100 pins", "Pini 100", "Pin 100 locations", "Weka mahali 100", "pins", "pini", 100),
    )

    private val WORK = setOf("pin", "visit", "fixed", "new")

    /** Longest run of days in a row in [days]. */
    fun bestRun(days: Collection<LocalDate>): Int {
        val s = days.toSortedSet(); var best = 0; var run = 0; var prev: LocalDate? = null
        for (d in s) { run = if (prev != null && prev.plusDays(1) == d) run + 1 else 1; best = maxOf(best, run); prev = d }
        return best
    }

    /**
     * Progress on every badge for [me], from the activity feed. [ckOf] maps a project id to its country (ke, tz, ug, mw).
     * Feed entries look like {by, a: pin|visit|fixed|new, s, id, t, at, wq?, st?}.
     */
    fun compute(act: List<JSONObject>, me: String, ckOf: (String) -> String?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): List<BadgeState> {
        val who = me.trim().lowercase()
        val mine = if (who.isBlank()) emptyList() else act.filter { it.optString("by").trim().lowercase() == who }
        fun count(a: String) = mine.count { it.optString("a") == a }
        val visits = mine.filter { it.optString("a") == "visit" }
        val monthStart = now.atZone(zone).toLocalDate().withDayOfMonth(1)
        fun day(e: JSONObject): LocalDate? = runCatching { Instant.parse(e.optString("at")).atZone(zone).toLocalDate() }.getOrNull()
        val n = mapOf(
            "pin1" to count("pin"), "visit1" to visits.size, "fixed1" to count("fixed"), "new1" to count("new"),
            "pin10" to count("pin"), "visit10" to visits.size, "pin100" to count("pin"),
            "month10" to visits.count { e -> day(e)?.let { !it.isBefore(monthStart) } == true },
            "streak5" to bestRun(mine.filter { it.optString("a") in WORK }.mapNotNull { day(it) }),
            "countries4" to visits.mapNotNull { e -> ckOf(e.optString("id"))?.takeIf { it in COUNTRIES.keys } }.toSet().size,
            "water3" to visits.count { truthy(it.opt("wq")) },
            "story3" to visits.count { truthy(it.opt("st")) },
        )
        return ALL.map { BadgeState(it, n[it.k] ?: 0) }
    }

    private fun truthy(v: Any?) = v == true || v == 1 || (v is Number && v.toInt() != 0) || v == "1" || v == "true"

    /**
     * Which badges are new since last time. [seen] is null the first time (then nothing celebrates,
     * so people with lots of work already don't get a flood).
     */
    fun fresh(states: List<BadgeState>, seen: Set<String>?): List<BadgeState> =
        if (seen == null) emptyList() else states.filter { it.got && it.def.k !in seen }
}

/** The badges this phone has already shown, per person, so each one celebrates once. */
class BadgeMemory(ctx: Context) {
    private val sp = ctx.getSharedPreferences("rafiki-pin", Context.MODE_PRIVATE)
    private fun key(who: String) = "badges:" + who.trim().lowercase()
    fun seen(who: String): Set<String>? = if (!sp.contains(key(who))) null else sp.getStringSet(key(who), emptySet())?.toSet()
    fun save(who: String, keys: Set<String>) = sp.edit().putStringSet(key(who), keys).apply()
}

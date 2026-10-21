package org.rafikioutreach.pin.data

import org.json.JSONObject
import org.rafikioutreach.pin.t

/**
 * Notifications (the bell in Me & team, and phone alerts): worked out from what the team did,
 * repair jobs and visits for you, and the review queue. Same as the web app.
 */
data class Alert(
    val kind: String, val at: String, val id: String?, val text: String,
    /** who did it ("" for a repair job or the review queue), the project's name, the short message, and the name shown when there's no person */
    val by: String = "", val title: String = "", val msg: String = "", val name: String = "",
) {
    /** Stays the same for the same notification, so one can be marked read. */
    val key: String get() = "$kind|${id.orEmpty()}|$at"
}

/** Unread: newer than "Mark all as read" ([seen]) and not opened one by one ([read]). */
fun alertNew(a: Alert, seen: String, read: Set<String>) = a.at > seen && a.key !in read

/** Kinds of notification: key, English, Kiswahili, icon, colour. */
val ALERT_KINDS = listOf(
    listOf("assign", "Visits assigned to me", "Ziara nilizopewa", "cal", "teal"),
    listOf("job", "Repair jobs for me", "Kazi za matengenezo kwangu", "wrench", "amber"),
    listOf("repair", "Repairs reported", "Matengenezo yaliyoripotiwa", "alert", "coral"),
    listOf("fixed", "Repairs fixed", "Matengenezo yaliyokamilika", "okc", "green"),
    listOf("new", "New sites added", "Maeneo mapya", "plus", "purple"),
    listOf("review", "Waiting for review (admins)", "Zinasubiri kuidhinishwa (wasimamizi)", "inbox", "blue"),
)

private fun same(a: String?, b: String?) = a.orEmpty().trim().equals(b.orEmpty().trim(), ignoreCase = true)

fun alertsFor(s: Snapshot, act: List<JSONObject>, me: String, off: Set<String>, reviewAt: String): List<Alert> {
    val cut = java.time.Instant.now().minus(java.time.Duration.ofDays(30)).toString()
    val out = mutableListOf<Alert>()
    act.forEach { e ->
        val at = e.optString("at"); val by = e.optString("by"); val tt = e.optString("t"); val id = e.optString("id").takeIf { it.isNotBlank() }
        if (at < cut || same(by, me)) return@forEach
        when (e.optString("a")) {
            "assign" -> if (same(e.optString("to"), me)) {
                val what = tt.replace(Regex(" for .*$"), ""); val sw = what.replace(Regex("visits?"), "ziara")
                val p = s.projects.firstOrNull { it.id == id }
                out += Alert("assign", at, p?.id, t("$by asked you to make $what", "$by amekuomba ufanye $sw"), by, p?.title.orEmpty(), t("Asked you to make $what", "Amekuomba ufanye $sw"))
            }
            "visit" -> if (e.optString("s") in setOf("repair", "notworking")) {
                val nw = e.optString("s") == "notworking"
                out += Alert("repair", at, id, "$by: $tt · " + if (nw) t("not working", "haifanyi kazi") else t("needs repair", "inahitaji matengenezo"), by, tt,
                    if (nw) t("Reported it’s not working", "Ameripoti kuwa haifanyi kazi") else t("Reported it needs repair", "Ameripoti kuwa inahitaji matengenezo"))
            }
            "fixed" -> out += Alert("fixed", at, id, t("$by fixed $tt", "$by amerekebisha $tt"), by, tt, t("Marked it fixed ✓", "Ameiweka kuwa imerekebishwa ✓"))
            "new" -> if (s.admin) out += Alert("new", at, id, t("$by added a new site: $tt", "$by ameongeza eneo jipya: $tt"), by, tt, t("Added a new site", "Ameongeza eneo jipya"))
        }
    }
    s.jobs.forEach { id -> s.repairs[id]?.let { r ->
        val pt = r.title.ifBlank { s.projects.firstOrNull { it.id == id }?.title.orEmpty() }
        out += Alert("job", r.stepAt["assigned"] ?: r.at, id, t("Repair job for you: ", "Kazi ya matengenezo kwako: ") + pt, "", pt, t("This repair is yours to do", "Matengenezo haya ni yako kufanya"), t("Repair job", "Kazi ya matengenezo"))
    } }
    if (s.admin && s.reviewOpen > 0) {
        val rv = if (s.reviewOpen == 1) t("1 change waiting for your review", "Badiliko 1 linasubiri uidhinishe") else t("${s.reviewOpen} changes waiting for your review", "Mabadiliko ${s.reviewOpen} yanasubiri uidhinishe")
        out += Alert("review", reviewAt.ifBlank { java.time.Instant.now().toString() }, null, rv, "", "", rv, t("Office review", "Ukaguzi wa ofisi"))
    }
    return out.filter { it.kind !in off }.sortedByDescending { it.at }.take(40)
}
